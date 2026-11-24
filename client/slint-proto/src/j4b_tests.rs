//! Local deterministic contract tests. Servers use inert bytes and never contact external APIs.
use crate::{
    catalog::{
        self, Platform,
        transport::{Transport, TransportFuture},
    },
    content_store::{Request, Store},
    digests::{self, Algorithm, Digest},
    download::{Downloader, Source},
    import::archive,
    tasks::Cancellation,
};
use serde_json::{Value, json};
use std::{
    io::{Read, Write},
    path::PathBuf,
    sync::{Arc, Mutex, atomic::Ordering},
};
fn temp() -> PathBuf {
    let p = std::env::temp_dir().join(format!("j4b-{}", uuid::Uuid::now_v7()));
    std::fs::create_dir_all(&p).unwrap();
    p
}
fn fixture() -> Value {
    serde_json::from_str(include_str!("../protocol/fixtures/j4b.json")).unwrap()
}
fn client() -> reqwest::Client {
    reqwest::Client::builder().no_proxy().build().unwrap()
}
struct Fake {
    calls: Mutex<Vec<String>>,
}
impl Transport for Fake {
    fn request<'a>(
        &'a self,
        p: Platform,
        _: reqwest::Method,
        path: &'a str,
        _: Vec<(String, String)>,
        _: Option<Value>,
    ) -> TransportFuture<'a> {
        Box::pin(async move {
            self.calls.lock().unwrap().push(format!("{p:?}:{path}"));
            let f = fixture();
            Ok(match (p, path) {
                (Platform::Modrinth, "version_files") => {
                    f["catalog"]["modrinth"]["response"].clone()
                }
                (Platform::CurseForge, "mods/files") => {
                    f["catalog"]["curseforge"]["response"].clone()
                }
                (Platform::CurseForge, "fingerprints/432") => json!({"data":{"exactMatches":[]}}),
                (Platform::Modrinth, "projects") => json!([]),
                (Platform::CurseForge, "mods") => json!({"data":[]}),
                _ => json!([]),
            })
        })
    }
}
fn catalog() -> catalog::Catalog {
    catalog::Catalog::with_transport(
        Arc::new(Fake {
            calls: Default::default(),
        }),
        catalog::transport::HttpTransport::new(client(), false),
        temp().join("missing.db"),
    )
}
#[test]
fn shared_hashes_and_cache_layout() {
    let root = temp();
    for case in fixture()["digests"].as_array().unwrap() {
        let bytes = case["text"].as_str().unwrap().as_bytes();
        let p = root.join("hash");
        std::fs::write(&p, bytes).unwrap();
        for (a, k) in [(Algorithm::Sha1, "sha1"), (Algorithm::Murmur2, "murmur2")] {
            assert_eq!(
                digests::hash(&p, a, &Cancellation::default()).unwrap(),
                case[k].as_str().unwrap()
            );
        }
        assert_eq!(digests::murmur2(bytes).to_string(), case["murmur2"]);
    }
    let d = Digest::new(
        Algorithm::Sha1,
        " A9993E364706816ABA3E25717850C26C9CD0D89D ",
    )
    .unwrap();
    let store = Store {
        root,
        downloader: Downloader { http: client() },
    };
    assert_eq!(
        store.cache_path(&d).file_name().unwrap().to_str().unwrap(),
        fixture()["cache"]["sha1"]
    );
}
#[test]
fn hostile_paths_urls_and_sha1() {
    for case in fixture()["paths"].as_array().unwrap() {
        assert_eq!(
            archive::safe_target(case["path"].as_str().unwrap()).is_ok(),
            case["valid"].as_bool().unwrap()
        );
    }
    assert!(archive::valid_url("http://cdn.modrinth.com/a").is_err());
    assert!(archive::valid_url("https://evil.invalid/a").is_err());
    assert_eq!(
        archive::valid_url("https://cdn.modrinth.com/a b%20中文.jar").unwrap(),
        "https://cdn.modrinth.com/a%20b%20%E4%B8%AD%E6%96%87.jar"
    );
    assert!(Digest::new(Algorithm::Sha1, "bad").is_err());
}
#[tokio::test]
async fn shared_catalog_adapters_exact_hash_and_cache() {
    let tr = Arc::new(Fake {
        calls: Default::default(),
    });
    let c = catalog::Catalog::with_transport(
        tr.clone(),
        catalog::transport::HttpTransport::new(client(), false),
        temp().join("missing"),
    );
    let r = catalog::FileRef {
        platform: Platform::CurseForge,
        id: "42".into(),
    };
    let first = c.get_files(std::slice::from_ref(&r)).await.unwrap();
    let f = &first.value[&r];
    assert_eq!(f.filename, "fixture.jar");
    assert_eq!(f.loaders, ["neoforge"]);
    c.get_files(std::slice::from_ref(&r)).await.unwrap();
    assert_eq!(
        tr.calls
            .lock()
            .unwrap()
            .iter()
            .filter(|s| s.ends_with("mods/files"))
            .count(),
        1
    );
    let files = c
        .match_files(&[catalog::FileHashes {
            key: "a".into(),
            sha1: fixture()["digests"][0]["sha1"].as_str().unwrap().into(),
            murmur2: None,
        }])
        .await
        .unwrap();
    assert_eq!(files["a"].reference.id, "v1");
    assert!(c.search_metadata("中文", 10).unwrap().is_empty());
}
#[tokio::test]
async fn kotlin_zip_corpus_and_chinese_names() {
    let c = catalog();
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("protocol/fixtures/j4b-archives");
    assert!(
        root.is_dir(),
        "Run Kotlin J4bArchiveParityTest to generate ZIP fixtures"
    );
    for (i, case) in fixture()["paths"].as_array().unwrap().iter().enumerate() {
        let result = archive::inspect(
            &root.join(format!("path-{i}.zip")),
            &c,
            &Cancellation::default(),
            &|_, _| {},
        )
        .await;
        assert_eq!(
            result.is_ok(),
            case["valid"].as_bool().unwrap(),
            "{i}: {:?}",
            result.err()
        );
    }
    for name in ["bad-url", "bad-sha1", "duplicate-target"] {
        assert!(
            archive::inspect(
                &root.join(format!("{name}.zip")),
                &c,
                &Cancellation::default(),
                &|_, _| {}
            )
            .await
            .is_err(),
            "{name}"
        );
    }
    for encoding in ["UTF-8", "GBK"] {
        let p = archive::inspect(
            &root.join(format!("chinese-{encoding}.zip")),
            &c,
            &Cancellation::default(),
            &|_, _| {},
        )
        .await
        .unwrap();
        assert_eq!(p.overrides[0].target, "config/中文.json");
    }
}
struct Server {
    url: String,
    requests: Arc<Mutex<Vec<String>>>,
    stop: Arc<std::sync::atomic::AtomicBool>,
    thread: Option<std::thread::JoinHandle<()>>,
}
impl Server {
    fn new(data: Vec<u8>, range: bool) -> Self {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let requests = Arc::new(Mutex::new(vec![]));
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let (reqs, closed) = (requests.clone(), stop.clone());
        let data = Arc::new(data);
        let thread = std::thread::spawn(move || {
            while !closed.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let (data, reqs) = (data.clone(), reqs.clone());
                        std::thread::spawn(move || {
                            stream.set_nonblocking(false).unwrap();
                            stream
                                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                                .unwrap();
                            let mut input = Vec::new();
                            let mut b = [0u8; 4096];
                            loop {
                                let Ok(n) = stream.read(&mut b) else { return };
                                if n == 0 {
                                    return;
                                }
                                input.extend_from_slice(&b[..n]);
                                if input.windows(4).any(|w| w == b"\r\n\r\n") {
                                    break;
                                }
                            }
                            let request = String::from_utf8_lossy(&input).to_string();
                            reqs.lock().unwrap().push(request.clone());
                            let head = request.starts_with("HEAD");
                            let bad = request.lines().next().unwrap().contains("/bad");
                            let lower = request.to_ascii_lowercase();
                            let raw = lower.lines().find_map(|l| l.strip_prefix("range: bytes="));
                            let mut a = 0;
                            let mut z = data.len() - 1;
                            let mut status = "200 OK";
                            let mut extra = String::new();
                            if bad {
                                status = "503 Unavailable";
                            } else if range && let Some(raw) = raw {
                                let (x, y) = raw.trim().split_once('-').unwrap();
                                a = x.parse().unwrap();
                                z = if y.is_empty() {
                                    data.len() - 1
                                } else {
                                    y.parse().unwrap()
                                };
                                status = "206 Partial Content";
                                extra = format!("Content-Range: bytes {a}-{z}/{}\r\n", data.len());
                            }
                            let body = &data[a..=z];
                            let header = format!(
                                "HTTP/1.1 {status}\r\nContent-Length: {}\r\nETag: \"fixture\"\r\n{extra}Connection: close\r\n\r\n",
                                body.len()
                            );
                            if stream.write_all(header.as_bytes()).is_err() {
                                return;
                            }
                            if !head {
                                let _ = stream.write_all(body);
                            }
                        });
                    }
                    Err(_) => std::thread::sleep(std::time::Duration::from_millis(1)),
                }
            }
        });
        Self {
            url,
            requests,
            stop,
            thread: Some(thread),
        }
    }
    fn source(&self, path: &str) -> Source {
        Source {
            url: format!("{}{path}", self.url),
            ..Default::default()
        }
    }
}
impl Drop for Server {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        self.thread.take().unwrap().join().unwrap();
    }
}
#[tokio::test]
async fn downloads_ranges_fallback_and_existing_target() {
    let bytes = vec![53u8; 5 * 1024 * 1024 + 3];
    let server = Server::new(bytes.clone(), true);
    let d = Downloader { http: client() };
    let root = temp();
    let path = root.join("download");
    d.download(
        &[server.source("/bad"), server.source("/ok")],
        &path,
        Some(bytes.len() as u64),
        &Cancellation::default(),
        &|p| {
            anyhow::ensure!(std::fs::read(p)? == bytes, "wrong digest");
            Ok(())
        },
        &|_| {},
    )
    .await
    .unwrap();
    assert_eq!(std::fs::read(&path).unwrap(), bytes);
    assert!(
        server
            .requests
            .lock()
            .unwrap()
            .iter()
            .any(|s| s.to_lowercase().contains("range: bytes=1048576-")),
        "{:?}",
        server.requests.lock().unwrap()
    );
    assert!(!root.join("download.downloading").exists());
}
#[tokio::test]
async fn cache_singleflight_aliases_reuse_and_conflict() {
    let server = Server::new(b"abc".to_vec(), false);
    let root = temp();
    let store = Store {
        root: root.join("dlc"),
        downloader: Downloader { http: client() },
    };
    let digests = vec![
        Digest::new(
            Algorithm::Sha1,
            fixture()["digests"][0]["sha1"].as_str().unwrap(),
        )
        .unwrap(),
        Digest::new(Algorithm::Murmur2, "1621425345").unwrap(),
    ];
    let r = Request {
        id: "a".into(),
        relative_path: "mods/a.jar".into(),
        size: Some(3),
        digests,
        sources: vec![server.source("/ok")],
    };
    let c = Cancellation::default();
    let one_root = root.join("one");
    let two_root = root.join("two");
    let progress = |_| {};
    let (one, two) = tokio::join!(
        store.materialize(std::slice::from_ref(&r), &one_root, &c, &progress),
        store.materialize(std::slice::from_ref(&r), &two_root, &c, &progress)
    );
    one.unwrap();
    two.unwrap();
    assert_eq!(
        server
            .requests
            .lock()
            .unwrap()
            .iter()
            .filter(|s| s.starts_with("GET"))
            .count(),
        1
    );
    for d in &r.digests {
        assert!(store.cache_path(d).is_file());
    }
    let third = root.join("three");
    std::fs::create_dir_all(third.join("mods")).unwrap();
    std::fs::write(third.join("mods/a.jar"), b"xyz").unwrap();
    assert!(store.materialize(&[r], &third, &c, &|_| {}).await.is_err());
    assert_eq!(std::fs::read(third.join("mods/a.jar")).unwrap(), b"xyz");
}
#[tokio::test]
async fn cancel_download_removes_owned_partial() {
    let server = Server::new(vec![4u8; 8 * 1024 * 1024], false);
    let path = temp().join("file");
    let c = Cancellation::default();
    let d = Downloader { http: client() };
    let result = d
        .download(
            &[server.source("/ok")],
            &path,
            Some(8 * 1024 * 1024),
            &c,
            &|_| Ok(()),
            &|_| c.cancel(),
        )
        .await;
    assert!(result.is_err());
    assert!(!path.exists());
    assert!(!path.with_file_name("file.downloading").exists());
}
#[test]
#[ignore = "External APIs require an explicit live smoke run"]
fn real_catalog_smoke() {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let c = catalog::Catalog::new(client(), &temp());
    runtime.block_on(async {
        let result = c
            .search(&catalog::SearchRequest {
                query: "jei".into(),
                target: catalog::Target {
                    minecraft: "1.21.1".into(),
                    loader: "neoforge".into(),
                },
                sort: catalog::Sort::Relevance,
                page_size: 5,
                cursor: None,
            })
            .await
            .unwrap();
        assert!(!result.value.items.is_empty());
    });
}

#[test]
fn bundled_identity_database_queries_are_read_only() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../ui/mod-catalog/src/main/resources/mod_catalog.db");
    let before = std::fs::metadata(&root).unwrap();
    let c = catalog::Catalog::with_transport(
        Arc::new(Fake {
            calls: Default::default(),
        }),
        catalog::transport::HttpTransport::new(client(), false),
        root.clone(),
    );
    let m = c
        .get_metadata(&[
            (Platform::Modrinth, "jei".into()),
            (Platform::CurseForge, "jei".into()),
        ])
        .unwrap();
    assert_eq!(m.len(), 2);
    assert!(m.values().all(|m| m.mcmod_id == 459));
    for (query, id) in [
        ("机械动力", 2021),
        ("gongyeshidai2", 2),
        ("gysd2", 2),
        ("Just Enough Items", 459),
        ("jei", 459),
    ] {
        assert_eq!(c.search_metadata(query, 10).unwrap()[0].mcmod_id, id);
    }
    assert_eq!(
        std::fs::metadata(root).unwrap().modified().unwrap(),
        before.modified().unwrap()
    );
}

#[tokio::test]
async fn archive_installer_overrides_optional_cache_and_changed_identity() {
    let root = temp();
    let runtime = tokio::runtime::Handle::current();
    let source = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("protocol/fixtures/j4b-archives/chinese-GBK.zip");
    let path = root.join("pack.zip");
    std::fs::copy(source, &path).unwrap();
    let preview = archive::inspect(&path, &catalog(), &Cancellation::default(), &|_, _| {})
        .await
        .unwrap();
    let tasks = crate::tasks::Manager::default();
    let versions = root.join("versions");
    let id = uuid::Uuid::now_v7().to_string();
    let selection = archive::Selection {
        preview: Arc::new(preview.clone()),
        optional: Default::default(),
        store: Store {
            root: root.join("dlc"),
            downloader: Downloader { http: client() },
        },
        runtime: runtime.clone(),
    };
    let request = crate::import::installer::Request {
        source: None,
        archive: Some(selection),
        include_saves: false,
        version: "V211".into(),
        loader: "neoforge".into(),
        name: "Archive".into(),
        comment: String::new(),
        icon: String::new(),
    };
    let target = versions.join(format!("local_{id}"));
    let copy = versions.clone();
    let task = tasks.submit(&runtime, "导入".into(), id.clone(), move |c| {
        crate::import::installer::install(&copy, &id, &request, &c)
    });
    while tasks.running() {
        tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    }
    assert_eq!(tasks.snapshot(&task).unwrap()["status"], 2);
    assert_eq!(
        std::fs::read(target.join("config/中文.json")).unwrap(),
        b"{}"
    );
    let info: Value =
        serde_json::from_slice(&std::fs::read(target.join("rdi-local-pack.json")).unwrap())
            .unwrap();
    assert_eq!(info["source"], "Archive");
    std::fs::write(path, b"changed").unwrap();
    assert!(preview.unchanged().is_err());
}

#[tokio::test]
async fn ranged_validation_failure_retries_in_single_stream() {
    let bytes = vec![9u8; 5 * 1024 * 1024];
    let server = Server::new(bytes.clone(), true);
    let calls = std::sync::atomic::AtomicUsize::new(0);
    let path = temp().join("file");
    Downloader { http: client() }
        .download(
            &[server.source("/ok")],
            &path,
            Some(bytes.len() as u64),
            &Cancellation::default(),
            &|p| {
                if calls.fetch_add(1, Ordering::Relaxed) == 0 {
                    anyhow::bail!("wrong digest");
                }
                anyhow::ensure!(std::fs::read(p)? == bytes, "wrong bytes");
                Ok(())
            },
            &|_| {},
        )
        .await
        .unwrap();
    assert!(calls.load(Ordering::Relaxed) >= 2);
    assert!(
        server
            .requests
            .lock()
            .unwrap()
            .iter()
            .any(|s| s.starts_with("GET") && !s.to_lowercase().contains("range:"))
    );
    assert_eq!(std::fs::read(path).unwrap(), bytes);
}

#[tokio::test]
async fn rejected_download_preserves_existing_target_and_cleans_temp() {
    let server = Server::new(b"abc".to_vec(), false);
    let root = temp();
    let path = root.join("file");
    std::fs::write(&path, b"original").unwrap();
    let count = std::sync::atomic::AtomicUsize::new(0);
    let c = Cancellation::default();
    let error = Downloader { http: client() }
        .download(
            &[server.source("/ok")],
            &path,
            Some(3),
            &c,
            &|_| {
                count.fetch_add(1, Ordering::Relaxed);
                anyhow::bail!("wrong digest")
            },
            &|_| {},
        )
        .await;
    assert!(error.is_err());
    assert_eq!(std::fs::read(path).unwrap(), b"original");
    assert!(!root.join("file.downloading").exists());
}

#[tokio::test]
async fn archive_optional_selection_and_network_cancel_leave_no_pack() {
    let root = temp();
    let server = Server::new(vec![17u8; 8 * 1024 * 1024], false);
    let size = 8 * 1024 * 1024;
    let path = root.join("pack.zip");
    std::fs::copy(
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("protocol/fixtures/j4b-archives/chinese-UTF-8.zip"),
        &path,
    )
    .unwrap();
    let mut preview = archive::inspect(&path, &catalog(), &Cancellation::default(), &|_, _| {})
        .await
        .unwrap();
    use sha1::Digest as _;
    let sha = format!("{:x}", sha1::Sha1::digest(vec![17u8; size]));
    preview.files.push(archive::ArchiveFile {
        key: "optional".into(),
        target: "mods/optional.jar".into(),
        name: "Optional".into(),
        summary: String::new(),
        icon: String::new(),
        size: size as u64,
        sha1: sha,
        sources: vec![server.source("/ok")],
        required: false,
        kind: "Mod".into(),
    });
    assert!(
        !preview.contents(&Default::default())[0]["selected"]
            .as_bool()
            .unwrap()
    );
    let tasks = crate::tasks::Manager::default();
    let handle = tokio::runtime::Handle::current();
    let versions = root.join("versions");
    for selected in [false, true] {
        let id = uuid::Uuid::now_v7().to_string();
        let target = versions.join(format!("local_{id}"));
        let staging = versions.join(format!("local_{id}.installing"));
        let copy = versions.clone();
        let selection = archive::Selection {
            preview: Arc::new(preview.clone()),
            optional: if selected {
                ["optional".into()].into_iter().collect()
            } else {
                Default::default()
            },
            store: Store {
                root: root.join("dlc"),
                downloader: Downloader { http: client() },
            },
            runtime: handle.clone(),
        };
        let request = crate::import::installer::Request {
            source: None,
            archive: Some(selection),
            include_saves: false,
            version: "V211".into(),
            loader: "neoforge".into(),
            name: "Optional".into(),
            comment: String::new(),
            icon: String::new(),
        };
        let run = tasks.submit(&handle, "导入".into(), id.clone(), move |c| {
            crate::import::installer::install(&copy, &id, &request, &c)
        });
        if selected {
            tasks.cancel(&run);
        }
        while tasks.running() {
            tokio::time::sleep(std::time::Duration::from_millis(1)).await;
        }
        if selected {
            assert_eq!(tasks.snapshot(&run).unwrap()["status"], 4);
            assert!(!target.exists());
            assert!(!staging.exists());
        } else {
            assert_eq!(tasks.snapshot(&run).unwrap()["status"], 2);
            assert!(!target.join("mods/optional.jar").exists());
        }
    }
}

#[test]
fn catalog_domain_target_environment_and_icon_identity() {
    use catalog::*;
    assert!(
        Target {
            minecraft: "1.21.1".into(),
            loader: "forge".into()
        }
        .validate()
        .is_err()
    );
    assert!(
        Target {
            minecraft: "1.20.1".into(),
            loader: "Fabric".into()
        }
        .validate()
        .is_ok()
    );
    assert_eq!(
        Environment {
            client: Requirement::Unknown,
            server: Requirement::Required
        }
        .with_fallback(Environment {
            client: Requirement::Required,
            server: Requirement::Unsupported
        }),
        Environment {
            client: Requirement::Required,
            server: Requirement::Required
        }
    );
}

#[tokio::test]
async fn catalog_overlapping_files_are_single_flight() {
    let tr = Arc::new(Fake {
        calls: Default::default(),
    });
    let c = catalog::Catalog::with_transport(
        tr.clone(),
        catalog::transport::HttpTransport::new(client(), false),
        temp().join("missing"),
    );
    let refs = [catalog::FileRef {
        platform: Platform::CurseForge,
        id: "42".into(),
    }];
    let (a, b) = tokio::join!(c.get_files(&refs), c.get_files(&refs));
    assert_eq!(a.unwrap().value.len(), 1);
    assert_eq!(b.unwrap().value.len(), 1);
    assert_eq!(
        tr.calls
            .lock()
            .unwrap()
            .iter()
            .filter(|s| s.ends_with("mods/files"))
            .count(),
        1
    );
}

#[tokio::test]
async fn same_source_interruption_resumes_only_with_matching_etag() {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let resumed = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let flag = resumed.clone();
    let worker = std::thread::spawn(move || {
        let mut first = true;
        for _ in 0..4 {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut raw = vec![];
            let mut b = [0u8; 1024];
            loop {
                let n = stream.read(&mut b).unwrap();
                if n == 0 {
                    break;
                }
                raw.extend_from_slice(&b[..n]);
                if raw.windows(4).any(|w| w == b"\r\n\r\n") {
                    break;
                }
            }
            let r = String::from_utf8(raw).unwrap().to_ascii_lowercase();
            if r.starts_with("head") {
                stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\nETag: \"stable\"\r\nConnection: close\r\n\r\n").unwrap();
            } else if r.contains("range: bytes=0-0") {
                stream.write_all(b"HTTP/1.1 206 Partial Content\r\nContent-Length: 1\r\nContent-Range: bytes 0-0/6\r\nETag: \"stable\"\r\nConnection: close\r\n\r\na").unwrap();
            } else if r.contains("range: bytes=3-") {
                flag.store(r.contains("if-range: \"stable\""), Ordering::Relaxed);
                stream.write_all(b"HTTP/1.1 206 Partial Content\r\nContent-Length: 3\r\nContent-Range: bytes 3-5/6\r\nETag: \"stable\"\r\nConnection: close\r\n\r\ndef").unwrap();
                break;
            } else if first {
                first = false;
                stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\nETag: \"stable\"\r\nConnection: close\r\n\r\nabc").unwrap();
            }
        }
    });
    let path = temp().join("resume");
    let result = tokio::time::timeout(
        std::time::Duration::from_secs(8),
        Downloader { http: client() }.download(
            &[Source {
                url: format!("http://{addr}/file"),
                ..Default::default()
            }],
            &path,
            Some(6),
            &Cancellation::default(),
            &|p| {
                anyhow::ensure!(std::fs::read(p)? == b"abcdef", "wrong bytes");
                Ok(())
            },
            &|_| {},
        ),
    )
    .await
    .unwrap();
    result.unwrap();
    worker.join().unwrap();
    assert!(resumed.load(Ordering::Relaxed));
}

#[tokio::test]
async fn cache_normalizes_digest_and_adds_sha1_for_murmur_requests() {
    let server = Server::new(b"abc".to_vec(), false);
    let root = temp();
    let store = Store {
        root: root.join("dlc"),
        downloader: Downloader { http: client() },
    };
    let request = Request {
        id: "murmur".into(),
        relative_path: "mods/a.jar".into(),
        size: Some(3),
        digests: vec![Digest {
            algorithm: Algorithm::Murmur2,
            value: " 1621425345 ".into(),
        }],
        sources: vec![server.source("/ok")],
    };
    store
        .materialize(
            &[request],
            &root.join("pack"),
            &Cancellation::default(),
            &|_| {},
        )
        .await
        .unwrap();
    assert!(store.root.join("1621425345.murmur2").is_file());
    assert!(
        store
            .root
            .join("a9993e364706816aba3e25717850c26c9cd0d89d.sha1")
            .is_file()
    );
}

#[tokio::test]
async fn unwritable_cache_falls_back_to_verified_temporary() {
    let server = Server::new(b"abc".to_vec(), false);
    let root = temp();
    let cache = root.join("cache-file");
    std::fs::write(&cache, b"preserve").unwrap();
    let store = Store {
        root: cache.clone(),
        downloader: Downloader { http: client() },
    };
    let request = Request {
        id: "fallback".into(),
        relative_path: "mods/a.jar".into(),
        size: Some(3),
        digests: vec![
            Digest::new(Algorithm::Sha1, "a9993e364706816aba3e25717850c26c9cd0d89d").unwrap(),
        ],
        sources: vec![server.source("/ok")],
    };
    store
        .materialize(
            &[request],
            &root.join("pack"),
            &Cancellation::default(),
            &|_| {},
        )
        .await
        .unwrap();
    assert_eq!(std::fs::read(root.join("pack/mods/a.jar")).unwrap(), b"abc");
    assert_eq!(std::fs::read(cache).unwrap(), b"preserve");
}

#[tokio::test]
async fn catalog_mirror_unreachable_falls_back_and_preference_is_dynamic() {
    // A local JSON endpoint exercises the real transport without any external API.
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let worker = std::thread::spawn(move || {
        for _ in 0..2 {
            let (mut stream, _) = listener.accept().unwrap();
            let mut bytes = [0u8; 4096];
            let _ = stream.read(&mut bytes);
            let body = b"[]";
            write!(stream,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",body.len()).unwrap();
            stream.write_all(body).unwrap();
        }
    });
    let root = temp();
    let config = root.join("config.toml");
    std::fs::write(&config, "preferModMirror=true\n").unwrap();
    let mut t = catalog::transport::HttpTransport::new(client(), true);
    t.official[1] = format!("http://{address}");
    t.mirror = "http://127.0.0.1:1".into();
    t.preference_file = Some(config.clone());
    assert_eq!(
        t.request(
            Platform::Modrinth,
            reqwest::Method::GET,
            "projects",
            vec![],
            None
        )
        .await
        .unwrap(),
        json!([])
    );
    std::fs::write(config, "preferModMirror=false\n").unwrap();
    assert_eq!(
        t.request(
            Platform::Modrinth,
            reqwest::Method::GET,
            "projects",
            vec![],
            None
        )
        .await
        .unwrap(),
        json!([])
    );
    worker.join().unwrap();
}

#[test]
fn symlink_rule_covers_only_the_root_and_below() {
    use crate::content_store::no_symlink_under;
    let root = temp();
    // Missing parts and parts under a regular file are not links.
    no_symlink_under(&root, &root.join("a/b/c.jar")).unwrap();
    std::fs::write(root.join("file"), b"x").unwrap();
    no_symlink_under(&root, &root.join("file/below")).unwrap();
    assert!(no_symlink_under(&root.join("a"), &root.join("b")).is_err(), "outside the root");
    #[cfg(unix)]
    {
        let outside = temp();
        // A link above the root is the system's choice (macOS /var, Fedora Atomic /home).
        let linked = root.join("linked-parent");
        std::os::unix::fs::symlink(&outside, &linked).unwrap();
        let store_root = linked.join("store");
        no_symlink_under(&store_root, &store_root.join("x.sha1")).unwrap();
        // A link at the root or inside it is refused.
        assert!(no_symlink_under(&linked, &linked.join("x")).is_err());
        std::fs::create_dir_all(root.join("pack")).unwrap();
        std::os::unix::fs::symlink(&outside, root.join("pack/mods")).unwrap();
        let error = no_symlink_under(&root.join("pack"), &root.join("pack/mods/a.jar")).unwrap_err();
        assert!(error.to_string().starts_with("内容路径不能穿过符号链接"), "{error}");
        let _ = std::fs::remove_dir_all(outside);
    }
    let _ = std::fs::remove_dir_all(root);
}

/// The store works when an ancestor of its folders is a symlink, as macOS temp folders are.
#[cfg(unix)]
#[tokio::test]
async fn store_under_a_linked_ancestor_downloads() {
    let server = Server::new(b"abc".to_vec(), false);
    let real = temp();
    let link = std::env::temp_dir().join(format!("j4b-link-{}", uuid::Uuid::now_v7()));
    std::os::unix::fs::symlink(&real, &link).unwrap();
    let store = Store { root: link.join("cache"), downloader: Downloader { http: client() } };
    let request = Request {
        id: "linked".into(),
        relative_path: "mods/a.jar".into(),
        size: Some(3),
        digests: vec![Digest::new(Algorithm::Sha1, "a9993e364706816aba3e25717850c26c9cd0d89d").unwrap()],
        sources: vec![server.source("/ok")],
    };
    store.materialize(&[request], &link.join("pack"), &Cancellation::default(), &|_| {}).await.unwrap();
    assert_eq!(std::fs::read(real.join("pack/mods/a.jar")).unwrap(), b"abc");
    assert!(real.join("cache/a9993e364706816aba3e25717850c26c9cd0d89d.sha1").is_file());
    let _ = std::fs::remove_file(link);
    let _ = std::fs::remove_dir_all(real);
}
