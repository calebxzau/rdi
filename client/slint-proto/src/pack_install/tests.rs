//! End-to-end install against a local fake RDI server; nothing contacts external hosts.
use super::*;
use crate::{
    catalog::transport::{HttpTransport, Transport, TransportFuture},
    content_store::Store,
    digests::{hash, murmur2},
    download::Downloader,
    tasks::Cancellation,
};
use serde_json::Value;
use std::{
    collections::HashMap,
    io::{Read, Write},
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant},
};

static CANCEL_AT: Mutex<Vec<(String, usize)>> = Mutex::new(vec![]);
pub(super) fn cancel_at(version_id: &str) -> Option<usize> {
    CANCEL_AT
        .lock()
        .unwrap()
        .iter()
        .find(|(v, _)| v == version_id)
        .map(|(_, s)| *s)
}

/// Removed when dropped, also after a failed assertion.
fn temp() -> crate::test_dir::TestDir {
    crate::test_dir::TestDir::new("j4c")
}
fn sha1(bytes: &[u8]) -> String {
    use sha1::Digest as _;
    format!("{:x}", sha1::Sha1::digest(bytes))
}

#[derive(Clone)]
struct Route {
    status: u16,
    json: bool,
    body: Vec<u8>,
    delay: Duration,
}
#[derive(Clone, Default)]
struct Routes(Arc<Mutex<HashMap<String, Route>>>);
impl Routes {
    fn json(&self, path: &str, data: Value) {
        self.0.lock().unwrap().insert(
            path.into(),
            Route {
                status: 200,
                json: true,
                body: serde_json::to_vec(&json!({"code":0,"msg":"","data":data})).unwrap(),
                delay: Duration::ZERO,
            },
        );
    }
    fn file(&self, path: &str, body: Vec<u8>, delay: Duration) {
        self.0.lock().unwrap().insert(
            path.into(),
            Route {
                status: 200,
                json: false,
                body,
                delay,
            },
        );
    }
}
struct FakeServer {
    url: String,
    routes: Routes,
    log: Arc<Mutex<Vec<String>>>,
    stop: Arc<AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}
impl FakeServer {
    fn new() -> Self {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let routes = Routes::default();
        let log = Arc::new(Mutex::new(vec![]));
        let stop = Arc::new(AtomicBool::new(false));
        let (r, l, s) = (routes.clone(), log.clone(), stop.clone());
        let thread = std::thread::spawn(move || {
            while !s.load(Ordering::Relaxed) {
                let Ok((mut stream, _)) = listener.accept() else {
                    std::thread::sleep(Duration::from_millis(1));
                    continue;
                };
                let (r, l, s) = (r.clone(), l.clone(), s.clone());
                std::thread::spawn(move || {
                    stream.set_nonblocking(false).unwrap();
                    stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                    let mut input = Vec::new();
                    let mut b = [0u8; 4096];
                    while !input.windows(4).any(|w| w == b"\r\n\r\n") {
                        match stream.read(&mut b) {
                            Ok(0) | Err(_) => return,
                            Ok(n) => input.extend_from_slice(&b[..n]),
                        }
                    }
                    let request = String::from_utf8_lossy(&input).to_string();
                    let line = request.lines().next().unwrap_or("").to_owned();
                    let mut parts = line.split(' ');
                    let method = parts.next().unwrap_or("").to_owned();
                    let path = parts.next().unwrap_or("").to_owned();
                    let auth = request
                        .lines()
                        .any(|l| l.eq_ignore_ascii_case("authorization: bearer jwt"));
                    l.lock().unwrap().push(format!("{method} {path} {auth}"));
                    let route = r.0.lock().unwrap().get(&path).cloned().unwrap_or(Route {
                        status: 404,
                        json: false,
                        body: vec![],
                        delay: Duration::ZERO,
                    });
                    let until = Instant::now() + route.delay;
                    while Instant::now() < until && !s.load(Ordering::Relaxed) {
                        std::thread::sleep(Duration::from_millis(5));
                    }
                    let header = format!(
                        "HTTP/1.1 {} X\r\nContent-Length: {}\r\nContent-Type: {}\r\nConnection: close\r\n\r\n",
                        route.status,
                        route.body.len(),
                        if route.json { "application/json" } else { "application/octet-stream" }
                    );
                    let _ = stream.write_all(header.as_bytes());
                    if method != "HEAD" {
                        let _ = stream.write_all(&route.body);
                    }
                });
            }
        });
        Self {
            url,
            routes,
            log,
            stop,
            thread: Some(thread),
        }
    }
    fn gets(&self, path: &str) -> usize {
        self.log
            .lock()
            .unwrap()
            .iter()
            .filter(|l| l.starts_with(&format!("GET {path} ")))
            .count()
    }
}
impl Drop for FakeServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        self.thread.take().unwrap().join().unwrap();
    }
}

struct FakeCatalog(Arc<Mutex<Vec<String>>>, Value);
impl Transport for FakeCatalog {
    fn request<'a>(
        &'a self,
        p: crate::catalog::Platform,
        _: reqwest::Method,
        path: &'a str,
        _: Vec<(String, String)>,
        body: Option<Value>,
    ) -> TransportFuture<'a> {
        Box::pin(async move {
            self.0
                .lock()
                .unwrap()
                .push(format!("{p:?}:{path}:{}", body.unwrap_or_default()));
            Ok(self.1.clone())
        })
    }
}

/// A tar.zst built from (path, bytes) pairs.
fn tar_zst(files: &[(&str, &[u8])]) -> Vec<u8> {
    let mut builder = tar::Builder::new(Vec::new());
    for (path, data) in files {
        let mut header = tar::Header::new_gnu();
        header.set_size(data.len() as u64);
        header.set_mode(0o644);
        header.set_cksum();
        builder.append_data(&mut header, path, *data).unwrap();
    }
    zstd::encode_all(&builder.into_inner().unwrap()[..], 3).unwrap()
}

const PACK: &str = "6610000000000000000000ab";
struct Env {
    server: FakeServer,
    deps: Deps,
    _runtime: tokio::runtime::Runtime,
    catalog_calls: Arc<Mutex<Vec<String>>>,
    /// Last field, so the folder goes after the runtime and the deps that use it.
    data: crate::test_dir::TestDir,
}
const MR_JAR: &[u8] = b"modrinth jar";
const CF_JAR: &[u8] = b"curseforge jar";
const CF2_JAR: &[u8] = b"untrusted curseforge jar";
const RES: &[u8] = b"resource pack";

fn env(version: &str) -> Env {
    let server = FakeServer::new();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let data = temp();
    let url = &server.url;
    let mr_hash = sha1(MR_JAR);
    let cf_hash = murmur2(CF_JAR).to_string();
    let cf2_hash = murmur2(CF2_JAR).to_string();
    let res_hash = sha1(RES);
    server.routes.json(
        "/modpack/infos",
        json!([{"id":PACK,"name":"测试包","mcVer":"V211","modloader":"neoforge"}]),
    );
    server.routes.json(
        &format!("/modpack/{PACK}/version/{version}"),
        json!({"name":version,"status":"OK","totalSize":2048,"time":1,"modpackId":PACK,"changelog":"",
        "mods":[
            {"platform":"mr","projectId":"a","slug":"alpha","fileId":"1","hash":mr_hash,"side":"BOTH",
             "downloadUrls":[format!("{url}/files/alpha.jar")]},
            {"platform":"cf","projectId":"2","slug":"beta","fileId":"200","hash":cf_hash,"side":"CLIENT",
             "downloadUrls":[format!("{url}/files/beta.jar")]},
            {"platform":"cf","projectId":"3","slug":"gamma","fileId":"300","hash":cf2_hash,"side":"BOTH"},
            {"platform":"mr","projectId":"s","slug":"server-only","fileId":"9","hash":"9".repeat(40),"side":"SERVER"}
        ],
        "clientExtras":[
            {"platform":"Modrinth","type":"ResPack","projectId":"r","fileId":"1","slug":"res","hash":res_hash,
             "side":"Client","downloadUrls":[format!("{url}/files/res.zip")]},
            {"platform":"Modrinth","type":"ShaderPack","projectId":"k","fileId":"1","slug":"kept","hash":"b".repeat(40),
             "side":"Both","path":"shaderpacks/kept.zip","downloadUrls":[format!("{url}/files/never.zip")]}
        ]}),
    );
    let archive = tar_zst(&[
        ("options.txt", b"fov:0.5\r\nlang:en_us"),
        ("config/中文.json", b"{}"),
        ("shaderpacks/kept.zip", b"from the pack"),
    ]);
    server.routes.json(
        &format!("/modpack/{PACK}/version/{version}/client/hash"),
        json!(sha1(&archive)),
    );
    server.routes.file(
        &format!("/modpack/{PACK}/version/{version}/client"),
        archive,
        Duration::ZERO,
    );
    server.routes.file("/files/alpha.jar", MR_JAR.to_vec(), Duration::ZERO);
    server.routes.file("/files/beta.jar", CF_JAR.to_vec(), Duration::ZERO);
    server.routes.file(
        &format!("/mod/download/gamma_cf_{cf2_hash}.jar"),
        CF2_JAR.to_vec(),
        Duration::ZERO,
    );
    server.routes.file("/files/res.zip", RES.to_vec(), Duration::ZERO);
    let calls = Arc::new(Mutex::new(vec![]));
    let http = reqwest::Client::builder().no_proxy().build().unwrap();
    let catalog = Catalog::with_transport(
        Arc::new(FakeCatalog(
            calls.clone(),
            json!({"data":[{"id":300,"modId":3,"fileName":"gamma.jar","displayName":"Gamma","fileLength":24,
            "releaseType":1,"fileDate":"2026-01-01T00:00:00Z","gameVersions":["1.21.1","NeoForge"],
            "fileFingerprint":cf2_hash.parse::<u64>().unwrap(),"hashes":[],
            "downloadUrl":"https://127.0.0.1:1/gamma.jar","dependencies":[]}]}),
        )),
        HttpTransport::new(http.clone(), false),
        data.join("missing.db"),
    );
    let mut server_api = ServerApi::with_token(url, "jwt");
    server_api.http = http.clone();
    let deps = Deps {
        server: server_api,
        tasks: Default::default(),
        store: Store {
            root: data.join("dlc"),
            downloader: Downloader { http },
        },
        catalog: Arc::new(catalog),
        runtime: runtime.handle().clone(),
        versions: data.join("mc").join("versions"),
        prefer_mirror: true,
        running: Default::default(),
    };
    Env {
        server,
        deps,
        _runtime: runtime,
        catalog_calls: calls,
        data,
    }
}
fn wait(deps: &Deps, id: &str) -> Value {
    let until = Instant::now() + Duration::from_secs(30);
    while deps.tasks.running() && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(5));
    }
    deps.tasks.snapshot(id).unwrap()
}
fn request(version: &str, extras: bool) -> InstallRequest {
    InstallRequest {
        modpack_id: PACK.to_uppercase(),
        version: version.into(),
        include_extras: extras,
    }
}
fn hidden(versions: &Path) -> Vec<String> {
    std::fs::read_dir(versions)
        .map(|d| {
            d.flatten()
                .map(|e| e.file_name().to_string_lossy().into_owned())
                .filter(|n| n.starts_with('.'))
                .collect()
        })
        .unwrap_or_default()
}

#[test]
fn installs_room_pack_with_extras_and_kotlin_layout() {
    let e = env("1.0");
    assert_eq!(state(&e.deps, PACK, "1.0"), State::Missing);
    let id = start(&e.deps, request("1.0", true)).unwrap();
    let snap = wait(&e.deps, &id);
    assert_eq!(snap["status"], 2, "{snap}");
    assert_eq!(snap["title"], "完整下载整合包 测试包 2.0KB");
    let rows: Vec<_> = snap["rows"].as_array().unwrap().iter().map(|r| r["title"].clone()).collect();
    assert_eq!(rows.len(), 8);
    assert_eq!(rows[1], "下载客户端整合包");
    assert!(snap["rows"].as_array().unwrap().iter().all(|r| r["status"] == 2));
    let dir = e.deps.versions.join(format!("{PACK}_1.0"));
    assert_eq!(state(&e.deps, PACK, "1.0"), State::Installed);
    let read = |p: &str| std::fs::read(dir.join(p)).unwrap();
    assert_eq!(
        String::from_utf8(read("options.txt")).unwrap(),
        "fov:0.5\r\nlang:zh_cn\r\ndarkMojangStudiosBackground:true\r\nforceUnicodeFont:true"
    );
    assert_eq!(read("config/fancymenu/options.txt"), FANCYMENU_OPTIONS);
    assert_eq!(read("config/中文.json"), b"{}");
    assert_eq!(read(&format!("mods/alpha_mr_{}.jar", sha1(MR_JAR))), MR_JAR);
    assert_eq!(read(&format!("mods/beta_cf_{}.jar", murmur2(CF_JAR))), CF_JAR);
    assert_eq!(read(&format!("mods/gamma_cf_{}.jar", murmur2(CF2_JAR))), CF2_JAR);
    assert_eq!(std::fs::read_dir(dir.join("mods")).unwrap().count(), 3, "server mod skipped");
    assert_eq!(read(&format!("resourcepacks/res_mr_{}.zip", sha1(RES))), RES);
    assert_eq!(read("shaderpacks/kept.zip"), b"from the pack", "an existing file is kept");
    assert_eq!(e.server.gets("/files/never.zip"), 0);
    assert!(hidden(&e.deps.versions).is_empty());
    // One CurseForge lookup, only for the mod without a URL.
    let calls = e.catalog_calls.lock().unwrap().clone();
    assert_eq!(calls.len(), 1, "{calls:?}");
    assert!(calls[0].contains("300") && !calls[0].contains("200"));
    assert!(
        e.server.log.lock().unwrap().iter().any(|l| l.contains("/client true")),
        "the archive request carries the login token"
    );
    // The archive is fetched into the cache, not linked into the pack.
    assert!(!dir.join(format!("{PACK}_1.0.client-pack")).exists());

    // Reinstall reuses the cache: no second archive or mod download, the old copy is replaced.
    std::fs::write(dir.join("marker"), b"old").unwrap();
    let before = e.server.gets(&format!("/modpack/{PACK}/version/1.0/client"));
    let id = start(&e.deps, request("1.0", false)).unwrap();
    assert_eq!(wait(&e.deps, &id)["status"], 2);
    assert!(!dir.join("marker").exists());
    assert!(!dir.join("resourcepacks").exists(), "extras off");
    assert_eq!(e.server.gets(&format!("/modpack/{PACK}/version/1.0/client")), before);
    assert_eq!(e.catalog_calls.lock().unwrap().len(), 1, "cached mod needs no lookup");
    assert!(hidden(&e.deps.versions).is_empty());
    let _ = &e.data;
}

#[test]
fn second_start_returns_running_task_and_cancel_leaves_no_folder() {
    let e = env("2.0");
    let path = format!("/modpack/{PACK}/version/2.0/client");
    let body = e.server.routes.0.lock().unwrap()[&path].body.clone();
    e.server.routes.file(&path, body, Duration::from_secs(20));
    let id = start(&e.deps, request("2.0", true)).unwrap();
    assert_eq!(start(&e.deps, request("2.0", true)).unwrap(), id);
    assert_eq!(state(&e.deps, PACK, "2.0"), State::Installing(id.clone()));
    let until = Instant::now() + Duration::from_secs(10);
    while e.server.gets(&path) == 0 && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(5));
    }
    e.deps.tasks.cancel(&id);
    let snap = wait(&e.deps, &id);
    assert_eq!(snap["status"], 4);
    assert_eq!(state(&e.deps, PACK, "2.0"), State::Missing);
    assert!(!e.deps.versions.join(format!("{PACK}_2.0")).exists());
    assert!(hidden(&e.deps.versions).is_empty());
}

#[test]
fn cancel_at_every_step_keeps_older_install() {
    for step in 0..STEPS.len() {
        let version = format!("c{step}");
        let e = env(&version);
        let dir = e.deps.versions.join(format!("{PACK}_{version}"));
        std::fs::create_dir_all(dir.join("mods")).unwrap();
        std::fs::write(dir.join("marker"), b"old").unwrap();
        CANCEL_AT.lock().unwrap().push((format!("{PACK}_{version}"), step));
        let id = start(&e.deps, request(&version, true)).unwrap();
        let snap = wait(&e.deps, &id);
        assert_eq!(snap["status"], 4, "step {step}: {snap}");
        assert_eq!(std::fs::read(dir.join("marker")).unwrap(), b"old", "step {step}");
        assert!(hidden(&e.deps.versions).is_empty(), "step {step}");
    }
}

#[test]
fn failure_leaves_no_version_dir_and_older_install_survives() {
    let e = env("3.0");
    e.server
        .routes
        .file("/files/alpha.jar", b"tampered".to_vec(), Duration::ZERO);
    let id = start(&e.deps, request("3.0", true)).unwrap();
    let snap = wait(&e.deps, &id);
    assert_eq!(snap["status"], 3, "{snap}");
    assert!(!e.deps.versions.join(format!("{PACK}_3.0")).exists());
    assert!(hidden(&e.deps.versions).is_empty());

    let dir = e.deps.versions.join(format!("{PACK}_3.0"));
    std::fs::create_dir_all(dir.join("mods")).unwrap();
    std::fs::write(dir.join("marker"), b"old").unwrap();
    let id = start(&e.deps, request("3.0", true)).unwrap();
    assert_eq!(wait(&e.deps, &id)["status"], 3);
    assert_eq!(std::fs::read(dir.join("marker")).unwrap(), b"old");
}

#[test]
fn running_game_blocks_commit_and_unbuilt_version_is_refused() {
    let e = env("4.0");
    e.deps.running.update([format!("{PACK}_4.0")].into());
    let id = start(&e.deps, request("4.0", false)).unwrap();
    let snap = wait(&e.deps, &id);
    assert_eq!(snap["error"], "整合包正在运行，不能安装");
    assert!(!e.deps.versions.join(format!("{PACK}_4.0")).exists());
    assert!(hidden(&e.deps.versions).is_empty());

    e.server.routes.json(
        &format!("/modpack/{PACK}/version/5.0"),
        json!({"name":"5.0","status":"BUILDING","mods":[]}),
    );
    assert!(start(&e.deps, request("5.0", false)).unwrap_err().to_string().contains("尚未构建完成"));
    assert!(start(&e.deps, request("../x", false)).is_err());
}

#[test]
fn leftovers_older_than_the_run_are_removed() {
    let versions = temp();
    let older = format!(".installing-v-{}", uuid::Uuid::now_v7());
    let other = format!(".installing-w-{}", uuid::Uuid::now_v7());
    std::thread::sleep(Duration::from_millis(2));
    let run = uuid::Uuid::now_v7().to_string();
    std::thread::sleep(Duration::from_millis(2));
    let newer = format!(".replaced-v-{}", uuid::Uuid::now_v7());
    for d in [&older, &other, &newer] {
        std::fs::create_dir_all(versions.join(d).join("mods")).unwrap();
    }
    remove_leftovers(&versions, "v", &run);
    assert!(!versions.join(&older).exists());
    assert!(versions.join(&other).exists());
    assert!(versions.join(&newer).exists());
}

#[test]
fn options_merge_matches_kotlin() {
    let o = option_overrides("V211");
    assert_eq!(
        merge_options("", &o),
        "darkMojangStudiosBackground:true\nlang:zh_cn\nforceUnicodeFont:true"
    );
    let j4a: Value = serde_json::from_str(include_str!("../../protocol/fixtures/j4a.json")).unwrap();
    for (version, expected) in j4a["options"].as_object().unwrap() {
        assert_eq!(merge_options("", &option_overrides(version)), expected.as_str().unwrap());
    }
    assert_eq!(merge_options("a:1\n:x\nlang:en\n", &o), "a:1\n:x\nlang:zh_cn\n\ndarkMojangStudiosBackground:true\nforceUnicodeFont:true");
    assert_eq!(merge_options("lang:en", &option_overrides("V071")), "lang:zh_CN\nforceUnicodeFont:true");
}

// ---- archive reader ----

fn raw_tar(entries: &[(&str, u8, u64, &[u8])]) -> Vec<u8> {
    let mut out = vec![];
    for (name, kind, size, data) in entries {
        let mut h = tar::Header::new_ustar();
        // Raw name bytes: the builder refuses hostile names, which these tests need.
        h.as_mut_bytes()[..name.len()].copy_from_slice(name.as_bytes());
        h.set_entry_type(tar::EntryType::new(*kind));
        h.set_size(*size);
        h.set_mode(0o644);
        h.set_cksum();
        out.extend_from_slice(h.as_bytes());
        out.extend_from_slice(data);
        let pad = (512 - data.len() % 512) % 512;
        out.extend(std::iter::repeat_n(0u8, pad));
    }
    out.extend([0u8; 1024]);
    out
}
/// The folder guard comes back too, so `out` lives as long as the caller keeps it.
fn extract_bytes(bytes: Vec<u8>) -> (crate::test_dir::TestDir, PathBuf, Result<()>) {
    let dir = temp();
    let file = dir.join("pack");
    std::fs::write(&file, bytes).unwrap();
    let out = dir.join("out");
    std::fs::create_dir(&out).unwrap();
    let result = archive::extract(&file, &out, &Cancellation::default(), &|_, _, _| {});
    (dir, out, result)
}
fn rejected(bytes: Vec<u8>, message: &str) {
    let (_dir, _, result) = extract_bytes(bytes);
    let error = format!("{:#}", result.unwrap_err());
    assert!(error.contains(message), "{error} should contain {message}");
}

#[test]
fn archive_rejections_match_kotlin() {
    let z = |e: &[(&str, u8, u64, &[u8])]| zstd::encode_all(&raw_tar(e)[..], 1).unwrap();
    for kind in *b"12346" {
        rejected(z(&[("a", kind, 0, b"")]), "不支持的文件类型");
    }
    rejected(z(&[("../evil", b'0', 1, b"x")]), "非法整合包文件路径");
    rejected(z(&[("C:/evil", b'0', 1, b"x")]), "非法整合包文件路径");
    rejected(z(&[("a/b:c", b'0', 1, b"x")]), "非法整合包文件路径");
    rejected(
        z(&[("Mods/A.jar", b'0', 1, b"x"), ("mods/a.jar", b'0', 1, b"y")]),
        "重复文件路径",
    );
    rejected(
        z(&[("mods", b'0', 1, b"x"), ("mods/a.jar", b'0', 1, b"y")]),
        "文件与目录路径冲突",
    );
    // Declared size larger than the data: the stream ends inside the entry.
    let mut short = raw_tar(&[("a", b'0', 600, b"abc")]);
    short.truncate(512 + 512);
    rejected(zstd::encode_all(&short[..], 1).unwrap(), "截断");
    rejected(z(&[("big", b'0', archive::MODPACK_LIMITS.entry + 1, b"")]), "单文件解压大小超过");
    let mut long = vec![];
    for _ in 0..17 {
        long.push(("././@LongLink", b'L', 4, b"abc\0".as_slice()));
    }
    // The tar reader itself refuses two long names in a row; the raw guard's layer limit is
    // checked on the bounded stream alone.
    assert!(extract_bytes(z(&long)).2.is_err());
    let raw = raw_tar(&long);
    let mut bounded =
        archive::bounded_for_test(&raw[..], archive::MODPACK_LIMITS, Cancellation::default());
    let error = std::io::copy(&mut bounded, &mut std::io::sink()).unwrap_err();
    assert!(error.to_string().contains("元数据层数过多"), "{error}");
    rejected(z(&[("././@LongLink", b'L', 2 * 1024 * 1024, b"")]), "元数据过大");

    // ZIP: same path rules, Chinese names read.
    let mut zip_bytes = std::io::Cursor::new(vec![]);
    {
        let mut zip = zip::ZipWriter::new(&mut zip_bytes);
        zip.start_file("config/中文.txt", zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(b"ok").unwrap();
        zip.finish().unwrap();
    }
    let (_dir, out, result) = extract_bytes(zip_bytes.into_inner());
    result.unwrap();
    assert_eq!(std::fs::read(out.join("config/中文.txt")).unwrap(), b"ok");
}

#[test]
fn archive_limits_count_entries_and_total() {
    let dir = temp();
    let file = dir.join("pack");
    std::fs::write(
        &file,
        zstd::encode_all(&raw_tar(&[("a", b'0', 3, b"abc"), ("b", b'0', 3, b"def")])[..], 1).unwrap(),
    )
    .unwrap();
    let limits = |entry, total, entries| archive::Limits { entry, total, entries };
    let run = |l| archive::for_each(&file, l, &Cancellation::default(), &mut |_, _| Ok(()));
    assert!(format!("{:#}", run(limits(10, 100, 1)).unwrap_err()).contains("文件数量超过"));
    assert!(format!("{:#}", run(limits(10, 5, 10)).unwrap_err()).contains("总解压大小超过"));
    run(limits(10, 6, 2)).unwrap();
}

#[cfg(unix)]
#[test]
fn archive_refuses_symbolic_link_on_target_path() {
    let (_dir, out, _) = extract_bytes(tar_zst(&[("x", b"1")]));
    let elsewhere = temp();
    std::os::unix::fs::symlink(&elsewhere, out.join("mods")).unwrap();
    let file = out.parent().unwrap().join("pack");
    std::fs::write(&file, tar_zst(&[("mods/a.jar", b"1")])).unwrap();
    let error = archive::extract(&file, &out, &Cancellation::default(), &|_, _, _| {}).unwrap_err();
    assert!(format!("{error:#}").contains("符号链接"));
    assert!(!elsewhere.join("a.jar").exists());
}
#[cfg(windows)]
#[test]
fn archive_refuses_symbolic_link_on_target_path() {
    let (_dir, out, _) = extract_bytes(tar_zst(&[("x", b"1")]));
    let elsewhere = temp();
    // Creating a directory link may need developer mode; a junction-free machine skips the check.
    if std::os::windows::fs::symlink_dir(&elsewhere, out.join("mods")).is_err() {
        eprintln!("symlink_dir unavailable, skipped");
        return;
    }
    let file = out.parent().unwrap().join("pack");
    std::fs::write(&file, tar_zst(&[("mods/a.jar", b"1")])).unwrap();
    let error = archive::extract(&file, &out, &Cancellation::default(), &|_, _, _| {}).unwrap_err();
    assert!(format!("{error:#}").contains("符号链接"));
    assert!(!elsewhere.join("a.jar").exists());
}

// ---- Store::fetch ----

#[test]
fn fetch_hit_mismatch_and_single_flight() {
    let server = FakeServer::new();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let body = vec![7u8; 300_000];
    server.routes.file("/pack", body.clone(), Duration::from_millis(300));
    server.routes.file("/bad", b"other".to_vec(), Duration::ZERO);
    let cache = temp();
    let store = Store {
        root: cache.join("dlc"),
        downloader: Downloader {
            http: reqwest::Client::builder().no_proxy().build().unwrap(),
        },
    };
    let req = |path: &str, sha: &str| Request {
        id: "pack".into(),
        relative_path: "pack.client-pack".into(),
        size: None,
        digests: vec![Digest::new(Algorithm::Sha1, sha).unwrap()],
        sources: vec![Source {
            url: format!("{}{path}", server.url),
            headers: Default::default(),
        }],
    };
    let good = req("/pack", &sha1(&body));
    let c = Cancellation::default();
    // Requests one fetch makes (a probe plus the body), measured on a separate cache.
    let alone_cache = temp();
    let alone = Store {
        root: alone_cache.join("dlc"),
        ..store.clone()
    };
    runtime.block_on(alone.fetch(&good, &c, &|_| {})).unwrap();
    let per_fetch = server.gets("/pack");
    assert!(per_fetch >= 1);
    let (a, b) = runtime.block_on(async {
        tokio::join!(store.fetch(&good, &c, &|_| {}), store.fetch(&good, &c, &|_| {}))
    });
    let (a, b) = (a.unwrap(), b.unwrap());
    assert_eq!(a.path, b.path);
    assert_eq!(a.path, store.cache_path(&good.digests[0]));
    assert_eq!(server.gets("/pack"), 2 * per_fetch, "single flight");
    assert_eq!(hash(&a.path, Algorithm::Sha1, &c).unwrap(), sha1(&body));
    runtime.block_on(store.fetch(&good, &c, &|_| {})).unwrap();
    assert_eq!(server.gets("/pack"), 2 * per_fetch, "cache hit");
    let bad = req("/bad", &sha1(b"expected"));
    assert!(runtime.block_on(store.fetch(&bad, &c, &|_| {})).is_err());
    assert!(!store.cache_path(&bad.digests[0]).exists());
}

#[test]
fn only_application_json_is_rejected_as_a_download() {
    use crate::download::json_response;
    assert!(json_response(Some("application/json")));
    assert!(json_response(Some("Application/JSON; charset=utf-8")));
    assert!(!json_response(Some("application/x-amz-json-1.0")), "forgecdn zip");
    assert!(!json_response(Some("application/octet-stream")));
    assert!(!json_response(None));
}

#[test]
fn brief_and_version_accept_omitted_kotlin_defaults() {
    // Real server shape for a 1.21 NeoForge pack: mcVer, modloader and clientExtras are omitted.
    let brief: Brief = serde_json::from_value(json!({"id":PACK,"name":"1.21原版","modCount":2})).unwrap();
    assert_eq!((brief.mc_ver.as_str(), brief.modloader.as_str()), ("V211", "neoforge"));
    let version: Version = serde_json::from_value(json!({"time":1,"modpackId":PACK,"name":"1.0.0",
        "totalSize":6864,"status":"OK","mods":[{"platform":"mr","projectId":"a","slug":"s","fileId":"f",
        "hash":"d10d062caf1aad9aec82d7852f2fee1781735c1f","downloadUrls":[]}]}))
    .unwrap();
    assert!(version.client_extras.is_empty());
    assert_eq!(version.mods[0].side, mods::Side::Both);
}

#[test]
fn layered_upload_archives_prefer_client_overrides_and_skip_server_files() {
    let dir = temp();
    let file = dir.join("upload.tar.zst");
    std::fs::write(
        &file,
        tar_zst(&[
            ("manifest.json", b"{}"),
            ("overrides/config/a.json", b"common"),
            ("client-overrides/config/a.json", b"client"),
            ("overrides/config/b.json", b"b"),
            ("server/server.properties", b"motd=x"),
            ("overrides/server-overrides/x.txt", b"x"),
            ("resourcepacks/r.zip", b"r"),
            ("loose.txt", b"not a pack file"),
        ]),
    )
    .unwrap();
    let out = dir.join("out");
    std::fs::create_dir(&out).unwrap();
    archive::extract_layered(&file, &out, &Cancellation::default(), &|_, _, _| {}).unwrap();
    let read = |p: &str| std::fs::read(out.join(p)).ok();
    assert_eq!(read("config/a.json").as_deref(), Some(b"client".as_slice()));
    assert_eq!(read("config/b.json").as_deref(), Some(b"b".as_slice()));
    assert_eq!(read("resourcepacks/r.zip").as_deref(), Some(b"r".as_slice()));
    assert_eq!(read("server-overrides/x.txt").as_deref(), Some(b"x".as_slice()), "only a leading server folder is the server's");
    for gone in ["manifest.json", "server/server.properties", "server.properties", "loose.txt"] {
        assert!(read(gone).is_none(), "{gone}");
    }

    // Two files on one path and layer are refused, as in the flat archive.
    let twice = dir.join("twice.tar.zst");
    std::fs::write(&twice, tar_zst(&[("overrides/A.txt", b"1"), ("overrides/a.txt", b"2")])).unwrap();
    let out = dir.join("out2");
    std::fs::create_dir(&out).unwrap();
    let error = archive::extract_layered(&twice, &out, &Cancellation::default(), &|_, _, _| {}).unwrap_err();
    assert!(error.to_string().contains("客户端整合包包含重复文件路径"), "{error}");
}

#[test]
fn an_upload_archive_installs_locally_inside_the_upload_task() {
    let e = env("1.0");
    let file = e.data.join("upload.tar.zst");
    std::fs::write(
        &file,
        tar_zst(&[
            ("overrides/options.txt", b"lang:en_us"),
            ("client-overrides/config/c.json", b"client"),
            ("server/config/s.json", b"server"),
        ]),
    )
    .unwrap();
    let mr_hash = sha1(MR_JAR);
    let alpha = mods::Mod {
        platform: "mr".into(),
        project_id: "a".into(),
        slug: "alpha".into(),
        file_id: "1".into(),
        hash: mr_hash.clone(),
        side: mods::Side::Both,
        download_urls: vec![format!("{}/files/alpha.jar", e.server.url)],
        client_only_override: false,
        client_override_replaced: false,
    };
    let server_only = mods::Mod { slug: "server-only".into(), hash: "9".repeat(40), side: mods::Side::Server, ..alpha.clone() };
    let request = LocalInstall {
        modpack_id: PACK.to_uppercase(),
        version: "2.0".into(),
        mc_version: "V211".into(),
        fabric: false,
        mods: vec![alpha.clone(), server_only],
        extras: vec![],
        archive: file.clone(),
        embedded_names: [(alpha.file_name(), "mods/Alpha-1.0.jar".to_owned())].into(),
    };
    let worker = e.deps.clone();
    let id = e.deps.tasks.submit(&e.deps.runtime, "上传并安装整合包".into(), "upload-test".into(), move |ctx| install_local(&worker, &ctx, request));
    let snap = wait(&e.deps, &id);
    assert_eq!(snap["status"], 2, "{snap}");
    assert_eq!(snap["rows"].as_array().unwrap().len(), 1, "no step rows of its own");
    let dir = e.deps.versions.join(format!("{PACK}_2.0"));
    let read = |p: &str| std::fs::read(dir.join(p)).ok();
    assert_eq!(read("mods/Alpha-1.0.jar").as_deref(), Some(MR_JAR), "an embedded mod keeps its name in the pack");
    assert_eq!(std::fs::read_dir(dir.join("mods")).unwrap().count(), 1, "the server mod stays out");
    assert_eq!(read("config/c.json").as_deref(), Some(b"client".as_slice()));
    assert!(read("config/s.json").is_none());
    assert_eq!(read("config/fancymenu/options.txt").as_deref(), Some(FANCYMENU_OPTIONS));
    assert!(String::from_utf8(read("options.txt").unwrap()).unwrap().contains("lang:zh_cn"));
    assert_eq!(e.server.gets(&format!("/modpack/{PACK}/version/2.0/client")), 0, "nothing is downloaded from master");
    assert!(hidden(&e.deps.versions).is_empty());
    assert!(file.is_file(), "the caller removes the archive");
}

#[test]
fn local_mod_names_follow_kotlin_target_relative_path() {
    let m = mods::Mod {
        platform: "mr".into(),
        project_id: "p".into(),
        slug: "true-ending".into(),
        file_id: "1".into(),
        hash: "a".repeat(40),
        side: mods::Side::Both,
        download_urls: vec![],
        client_only_override: false,
        client_override_replaced: false,
    };
    let embedded: BTreeMap<String, String> = [(m.file_name(), "mods/TrueEnding.jar".to_owned())].into();
    assert_eq!(local_mod_name(&m, &embedded), m.file_name(), "an alias slug keeps the store name");
    let plain = mods::Mod { slug: "plain".into(), ..m };
    let embedded: BTreeMap<String, String> = [(plain.file_name(), "mods\\sub\\Plain 1.0.jar".to_owned()), ("x".into(), " ".into())].into();
    assert_eq!(local_mod_name(&plain, &embedded), "Plain 1.0.jar");
    assert_eq!(local_mod_name(&plain, &BTreeMap::new()), plain.file_name());
}
