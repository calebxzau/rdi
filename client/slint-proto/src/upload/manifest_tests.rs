//! Stage A2 part 2a: platform requests and the manifest to mod list rules, against a fake
//! transport.
use super::manifest::{self, CfManifestFile, IndexFile, Lookups};
use super::platform::{CfFile, CfMod, MrProject, MrVersion, Platforms};
use crate::catalog::models::Platform;
use crate::catalog::transport::{Transport, TransportFuture};
use crate::pack_install::extras::Kind;
use crate::pack_install::mods::Side;
use reqwest::Method;
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::{Arc, Mutex};

type Answer = Box<dyn Fn(&str, bool, &Value) -> Value + Send + Sync>;

/// Answers by path; records `(path, official, body or query)`.
struct Fake {
    calls: Mutex<Vec<(String, bool, Value)>>,
    answer: Answer,
}

impl Fake {
    fn platforms(answer: impl Fn(&str, bool, &Value) -> Value + Send + Sync + 'static) -> (Platforms, Arc<Fake>) {
        let fake = Arc::new(Fake { calls: Mutex::new(vec![]), answer: Box::new(answer) });
        (Platforms::new(fake.clone()), fake)
    }
    fn reply(&self, path: &str, official: bool, query: Vec<(String, String)>, body: Option<Value>) -> Value {
        let sent = body.unwrap_or_else(|| json!(query.into_iter().collect::<BTreeMap<_, _>>()));
        self.calls.lock().unwrap().push((path.to_owned(), official, sent.clone()));
        (self.answer)(path, official, &sent)
    }
    fn paths(&self) -> Vec<(String, bool)> {
        self.calls.lock().unwrap().iter().map(|c| (c.0.clone(), c.1)).collect()
    }
}

impl Transport for Fake {
    fn request<'a>(&'a self, _: Platform, _: Method, path: &'a str, query: Vec<(String, String)>, body: Option<Value>) -> TransportFuture<'a> {
        let value = self.reply(path, false, query, body);
        Box::pin(async move { Ok(value) })
    }
    fn request_official<'a>(&'a self, _: Platform, _: Method, path: &'a str, query: Vec<(String, String)>, body: Option<Value>) -> TransportFuture<'a> {
        let value = self.reply(path, true, query, body);
        Box::pin(async move { Ok(value) })
    }
}

fn run<T>(future: impl std::future::Future<Output = T>) -> T {
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(future)
}

fn sha(c: char) -> String {
    c.to_string().repeat(40)
}

fn entry(path: &str, sha1: &str, env: Option<(&str, &str)>, downloads: &[&str]) -> IndexFile {
    let mut v = json!({"path": path, "hashes": {"sha1": sha1, "sha512": "x"}, "downloads": downloads, "fileSize": 1});
    if let Some((client, server)) = env {
        v["env"] = json!({"client": client, "server": server});
    }
    serde_json::from_value(v).unwrap()
}

fn version(id: &str, project: &str, sha1: &str) -> MrVersion {
    serde_json::from_value(json!({"id": id, "project_id": project, "files": [{"url": format!("https://cdn.modrinth.com/{id}.jar"), "hashes": {"sha1": sha1}}]})).unwrap()
}

fn project(id: &str, slug: &str, client: &str, server: &str) -> MrProject {
    serde_json::from_value(json!({"id": id, "slug": slug, "client_side": client, "server_side": server})).unwrap()
}

fn cf_file(id: i64, project: i64, sha1: &str, versions: &[&str]) -> CfFile {
    serde_json::from_value(json!({"id": id, "modId": project, "fileName": format!("f {id}.jar"), "hashes": [{"value": "ignored", "algo": 2}, {"value": sha1, "algo": 1}],
        "gameVersions": versions, "fileFingerprint": id * 10}))
    .unwrap()
}

fn cf_mod(id: i64, slug: &str, class: i64) -> CfMod {
    serde_json::from_value(json!({"id": id, "name": slug, "slug": slug, "classId": class})).unwrap()
}

#[test]
fn manifest_paths_urls_and_file_ids() {
    assert_eq!(manifest::normalize_path("mods\\.\\a.jar").unwrap(), "mods/a.jar");
    assert_eq!(manifest::normalize_path("mods//a.jar").unwrap(), "mods/a.jar");
    for bad in ["", " ", "/mods/a.jar", "C:/mods/a.jar", "mods/a:b.jar"] {
        assert!(manifest::normalize_path(bad).unwrap_err().to_string().contains("必须是相对路径"), "{bad}");
    }
    assert!(manifest::normalize_path("mods/../a.jar").unwrap_err().to_string().contains("非法上级目录"));
    assert_eq!(manifest::content_kind("Mods/a.jar"), Some(Kind::Mod));
    assert_eq!(manifest::content_kind("RESOURCEPACKS"), Some(Kind::ResPack));
    assert_eq!(manifest::content_kind("shaderpacks/a.zip"), Some(Kind::ShaderPack));
    assert_eq!(manifest::content_kind("modsx/a.jar"), None);
    assert_eq!(manifest::content_kind("config/mods/a.jar"), None);
    assert_eq!(manifest::content_kind("配置文件夹/a.toml"), None);
    assert_eq!(manifest::content_kind("mods/模组.jar"), Some(Kind::Mod));
    assert_eq!(manifest::content_kind("资源"), None);
    let (url, id) = manifest::url_helpers_for_test(" https://edge.forgecdn.net/files/4567/89/My Mod.jar?a b#c d ");
    assert_eq!(url, "https://edge.forgecdn.net/files/4567/89/My%20Mod.jar?a b#c d");
    assert_eq!(id, Some(4567089));
    assert_eq!(manifest::url_helpers_for_test("https://cdn.modrinth.com/data/a/b.jar").1, None);
    // Past `Int`, as Kotlin `toIntOrNull`.
    assert_eq!(manifest::url_helpers_for_test("https://x/files/99999999/999/a.jar").1, None);
}

#[test]
fn curse_forge_file_addresses_and_sides() {
    let mut file = cf_file(4567890, 1, &sha('a'), &["Forge", " client "]);
    assert_eq!(file.sha1(), sha('a'));
    assert_eq!(file.side(), Some(Side::Client));
    assert_eq!(file.real_download_url().unwrap(), "https://mediafilez.forgecdn.net/files/4567/890/f+4567890.jar");
    file.file_name = Some("包 [1.0]+a.zip".into());
    assert_eq!(file.real_download_url().unwrap(), "https://mediafilez.forgecdn.net/files/4567/890/%E5%8C%85+%5B1.0%5D%2Ba.zip");
    file.download_url = Some("https://edge.forgecdn.net/x.jar".into());
    assert_eq!(file.real_download_url().unwrap(), "https://edge.forgecdn.net/x.jar");
    assert_eq!(cf_file(1, 1, "", &["Server", "Client"]).side(), Some(Side::Both));
    assert_eq!(cf_file(1, 1, "", &["1.20.1"]).side(), None);
    assert!(cf_file(4567, 1, "", &[]).real_download_url().is_err());
    assert_eq!(super::platform::form_encode_for_test("a-b_c.d*e~"), "a-b_c.d*e%7E");
}

#[test]
fn modrinth_entries_become_mods_and_extras() {
    let files = vec![
        entry("mods/a.jar", &sha('a').to_uppercase(), None, &["https://cdn.modrinth.com/a b.jar"]),
        entry("mods/client.jar", &sha('b'), Some(("required", "unsupported")), &[]),
        entry("resourcepacks/r.zip", &sha('c'), Some(("optional", "unsupported")), &["https://cdn.modrinth.com/r.zip"]),
        entry("shaderpacks/skipped.zip", &sha('d'), Some(("unsupported", "unsupported")), &[]),
        entry("config/ignored.toml", &sha('e'), None, &[]),
        entry("mods/cf.jar", &sha('f'), None, &["https://edge.forgecdn.net/files/1000/1/cf.jar"]),
        entry("mods/cf-by-slug.jar", &sha('1'), None, &[]),
        entry("mods/cf-unknown.jar", &sha('2'), None, &[]),
    ];
    let mut lookups = Lookups::default();
    for (hash, id, project_id) in [('a', "va", "pa"), ('b', "vb", "pb"), ('c', "vc", "pc")] {
        lookups.versions.insert(sha(hash), version(id, project_id, &sha(hash)));
    }
    lookups.projects.insert("pa".into(), project("pa", "alpha", "required", "required"));
    lookups.projects.insert("pb".into(), project("pb", "beta", "required", "unsupported"));
    lookups.projects.insert("pc".into(), project("pc", "pack", "required", "unsupported"));
    lookups.fallback.insert(5, (cf_file(1000001, 7, &sha('f'), &["Server"]), cf_mod(7, "seven", 6)));
    lookups.fallback.insert(6, (cf_file(1000002, 8, &sha('1'), &[]), cf_mod(8, "eight", 6)));
    lookups.fallback.insert(7, (cf_file(1000003, 9, &sha('2'), &[]), cf_mod(9, "nine", 6)));
    lookups.cf_to_mr_slug.insert("eight".into(), "Eight-MR".into());
    lookups.mr_by_slug.insert("eight-mr".into(), project("p8", "eight-mr", "unsupported", "required"));

    let resolved = manifest::resolve(&files, &lookups, false).unwrap();
    let mods: Vec<(&str, &str, &str, &str, Side)> = resolved.mods.iter().map(|m| (m.platform.as_str(), m.slug.as_str(), m.file_id.as_str(), m.hash.as_str(), m.side)).collect();
    assert_eq!(
        mods,
        [
            ("mr", "alpha", "va", sha('a').as_str(), Side::Both),
            ("mr", "beta", "vb", sha('b').as_str(), Side::Client),
            ("cf", "seven", "1000001", "10000010", Side::Server),
            ("cf", "eight", "1000002", "10000020", Side::Server),
            ("cf", "nine", "1000003", "10000030", Side::Unknown),
        ]
    );
    assert_eq!(resolved.mods[0].download_urls, ["https://cdn.modrinth.com/a%20b.jar"]);
    assert_eq!(resolved.extras.len(), 1);
    let extra = &resolved.extras[0];
    assert_eq!((extra.kind, extra.path.as_deref(), extra.required, extra.slug.as_str()), (Kind::ResPack, Some("resourcepacks/r.zip"), false, "pack"));

    // A Fabric pack takes the side from the entry and drops entries no side supports.
    let fabric = vec![
        entry("mods/a.jar", &sha('a'), Some(("unsupported", "required")), &[]),
        entry("mods/b.jar", &sha('b'), Some(("required", "required")), &[]),
        entry("mods/c.jar", &sha('c'), Some(("unsupported", "unsupported")), &[]),
    ];
    let sides: Vec<Side> = manifest::resolve(&fabric, &lookups, true).unwrap().mods.iter().map(|m| m.side).collect();
    assert_eq!(sides, [Side::Server, Side::Client]);
}

#[test]
fn modrinth_entries_that_cannot_be_resolved_fail() {
    let error = |files: &[IndexFile], lookups: &Lookups| manifest::resolve(files, lookups, false).unwrap_err().to_string();
    let files = vec![entry("mods/a.jar", &sha('a'), None, &[])];
    assert_eq!(error(&files, &Lookups::default()), "无法解析整合包文件: mods/a.jar");
    let mut lookups = Lookups::default();
    // A version that does not list the SHA-1 is not a match.
    lookups.versions.insert(sha('a'), version("va", "pa", &sha('b')));
    assert_eq!(error(&files, &lookups), "无法解析整合包文件: mods/a.jar");
    lookups.versions.insert(sha('a'), version("va", "pa", &sha('a')));
    assert_eq!(error(&files, &lookups), "无法解析整合包文件: mods/a.jar");
    let mut lookups = Lookups::default();
    lookups.fallback.insert(0, (cf_file(1000001, 7, &sha('b'), &[]), cf_mod(7, "seven", 6)));
    assert_eq!(error(&files, &lookups), "客户端文件SHA-1不匹配: mods/a.jar (file 1000001)");
    lookups.fallback.insert(0, (cf_file(1000001, 7, &sha('a'), &[]), cf_mod(7, "seven", 12)));
    assert_eq!(error(&files, &lookups), "无法解析整合包文件: mods/a.jar");

    // Two different files for one extra path.
    let files = vec![entry("resourcepacks/r.zip", &sha('a'), None, &[]), entry("ResourcePacks/R.zip", &sha('b'), None, &[])];
    let mut lookups = Lookups::default();
    lookups.versions.insert(sha('a'), version("va", "pa", &sha('a')));
    lookups.versions.insert(sha('b'), version("vb", "pa", &sha('b')));
    lookups.projects.insert("pa".into(), project("pa", "alpha", "required", "required"));
    assert!(error(&files, &lookups).contains("必须位于resourcepacks或shaderpacks"));
    let files = vec![entry("resourcepacks/r.zip", &sha('a'), None, &[]), entry("resourcepacks/R.zip", &sha('b'), None, &[])];
    assert_eq!(error(&files, &lookups), "客户端资源安装路径冲突: resourcepacks/R.zip");
}

#[test]
fn modrinth_index_asks_the_platforms_in_kotlin_order() {
    let files = vec![
        entry("mods/a.jar", &sha('a'), None, &[]),
        entry("mods/cf.jar", &sha('f'), None, &["https://edge.forgecdn.net/files/1000/1/cf.jar"]),
        entry("config/ignored.toml", &sha('e'), None, &[]),
    ];
    let (platforms, fake) = Fake::platforms(|path, _, sent| match path {
        "version_files" => json!({sha('a'): {"id": "va", "project_id": "pa", "files": [{"url": "https://cdn.modrinth.com/a.jar", "hashes": {"sha1": sha('a')}}]}}),
        "projects" if sent["ids"] == json!("[\"pa\"]") => json!([{"id": "pa", "slug": "alpha", "client_side": "required", "server_side": "required"}]),
        "projects" => json!([{"id": "p7", "slug": "seven-mr", "client_side": "required", "server_side": "unsupported"}]),
        "mods/files" => json!({"data": [{"id": 1000001, "modId": 7, "fileName": "cf.jar", "hashes": [{"value": sha('f'), "algo": 1}], "gameVersions": [], "fileFingerprint": 55}]}),
        "mods" => json!({"data": [{"id": 7, "name": "Seven", "slug": "seven", "classId": 6}]}),
        other => panic!("unexpected {other}"),
    });
    let slugs = |asked: &BTreeSet<String>| {
        assert_eq!(asked.iter().collect::<Vec<_>>(), ["seven"]);
        BTreeMap::from([("seven".to_owned(), "seven-mr".to_owned())])
    };
    let resolved = run(manifest::modrinth(&platforms, &files, false, &slugs)).unwrap();
    assert_eq!(resolved.mods.len(), 2);
    assert_eq!((resolved.mods[1].platform.as_str(), resolved.mods[1].hash.as_str(), resolved.mods[1].side), ("cf", "55", Side::Client));
    let calls = fake.calls.lock().unwrap();
    let paths: Vec<&str> = calls.iter().map(|c| c.0.as_str()).collect();
    assert_eq!(paths, ["version_files", "projects", "mods/files", "mods", "projects"]);
    assert_eq!(calls[0].2, json!({"hashes": [sha('a'), sha('f')], "algorithm": "sha1"}));
    assert_eq!(calls[2].2, json!({"fileIds": [1000001]}));
    assert_eq!(calls[3].2, json!({"modIds": [7], "filterPcOnly": false}));
    assert_eq!(calls[4].2["ids"], json!("[\"seven-mr\",\"seven\"]"));
    drop(calls);

    // An entry neither platform knows stops the read.
    let files = vec![entry("mods/x.jar", &sha('9'), None, &[])];
    let (platforms, _) = Fake::platforms(|path, _, _| match path {
        "version_files" => json!({}),
        _ => json!([]),
    });
    let none = |_: &BTreeSet<String>| BTreeMap::new();
    assert_eq!(run(manifest::modrinth(&platforms, &files, false, &none)).unwrap_err().to_string(), "无法解析整合包文件: mods/x.jar");
}

#[test]
fn curse_forge_manifest_mods_take_the_side_in_kotlin_order() {
    let files: Vec<CfManifestFile> = serde_json::from_value(json!([
        {"projectID": 1, "fileID": 101}, {"projectID": 2, "fileID": 102, "required": false}, {"projectID": 3, "fileID": 103},
        {"projectID": 4, "fileID": 104}, {"projectID": 5, "fileID": 105}
    ]))
    .unwrap();
    assert!(files[0].required && !files[1].required);
    let (platforms, fake) = Fake::platforms(|path, official, sent| match (path, official) {
        // The first answer leaves out project 3 and a resource pack (class 12, filtered out).
        ("mods", false) => json!({"data": [{"id": 1, "name": "One", "slug": "one", "classId": 6}, {"id": 2, "name": "Two", "slug": "two", "classId": 6},
            {"id": 4, "name": "Four", "slug": "four", "classId": 12}, {"id": 5, "name": "Five", "slug": "five", "classId": 6}]}),
        ("mods", true) => {
            assert_eq!(sent["modIds"], json!([3, 4]));
            json!({"data": [{"id": 3, "name": "Three", "slug": "three", "classId": 6}]})
        }
        ("mods/files", false) => json!({"data": [
            {"id": 101, "modId": 1, "fileName": "1.jar", "hashes": [{"value": sha('a'), "algo": 1}], "gameVersions": ["Client", "Forge"], "fileFingerprint": 11},
            {"id": 102, "modId": 2, "fileName": "2.jar", "hashes": [{"value": sha('b'), "algo": 1}], "gameVersions": ["Forge"], "fileFingerprint": 12},
            {"id": 103, "modId": 3, "fileName": "3.jar", "hashes": [], "gameVersions": [], "fileFingerprint": 13},
            {"id": 104, "modId": 4, "fileName": "4.zip", "hashes": [], "gameVersions": [], "fileFingerprint": 14}]}),
        ("mods/files", true) => {
            assert_eq!(sent["fileIds"], json!([105]));
            json!({"data": []})
        }
        ("version_files", _) => json!({sha('b'): {"id": "vb", "project_id": "pb", "files": []}}),
        ("projects", _) => json!([{"id": "pb", "slug": "two", "client_side": "unsupported", "server_side": "required"}]),
        other => panic!("unexpected {other:?}"),
    });
    let mods = run(manifest::curse_forge_mods(&platforms, &files)).unwrap();
    let got: Vec<(&str, &str, &str, Side)> = mods.iter().map(|m| (m.slug.as_str(), m.file_id.as_str(), m.hash.as_str(), m.side)).collect();
    // Game versions first, then the Modrinth project of the same SHA-1, else unknown. Project 4
    // is not a mod and file 105 is unknown, so both are left out.
    assert_eq!(got, [("one", "101", "11", Side::Client), ("two", "102", "12", Side::Server), ("three", "103", "13", Side::Unknown)]);
    assert!(mods.iter().all(|m| m.platform == "cf" && m.download_urls.is_empty()));
    assert_eq!(
        fake.paths(),
        [("mods", false), ("mods", true), ("mods/files", false), ("mods/files", true), ("version_files", false), ("projects", false)].map(|(p, o)| (p.to_owned(), o))
    );
    assert_eq!(fake.calls.lock().unwrap()[0].2, json!({"modIds": [1, 2, 3, 4, 5], "filterPcOnly": true}));
}

#[test]
fn curse_forge_manifest_extras_are_the_resource_and_shader_packs() {
    let files: Vec<CfManifestFile> = serde_json::from_value(json!([
        {"projectID": 1, "fileID": 101}, {"projectID": 4, "fileID": 104, "required": false}, {"projectID": 6, "fileID": 106}
    ]))
    .unwrap();
    let projects = BTreeMap::from([(1, cf_mod(1, "one", 6)), (4, cf_mod(4, "four", 12)), (6, cf_mod(6, " ", 6552))]);
    let infos = BTreeMap::from([(104, cf_file(1040000, 4, &sha('a'), &[])), (106, cf_file(1060000, 6, &sha('b'), &[]))]);
    let extras = manifest::resolve_cf_extras(&files, &projects, &infos).unwrap();
    let got: Vec<(Kind, &str, &str, &str, bool)> = extras.iter().map(|c| (c.kind, c.slug.as_str(), c.hash.as_str(), c.path.as_deref().unwrap(), c.required)).collect();
    assert_eq!(
        got,
        [
            (Kind::ResPack, "four", "10400000", "resourcepacks/f 1040000.jar", false),
            (Kind::ShaderPack, "6", "10600000", "shaderpacks/f 1060000.jar", true),
        ]
    );
    assert_eq!(extras[0].download_urls, ["https://mediafilez.forgecdn.net/files/1040/0/f+1040000.jar"]);

    let error = |files: &[CfManifestFile], projects: &BTreeMap<i64, CfMod>, infos: &BTreeMap<i64, CfFile>| manifest::resolve_cf_extras(files, projects, infos).unwrap_err().to_string();
    assert_eq!(error(&files, &BTreeMap::new(), &infos), "无法解析客户端资源项目: 1/101");
    assert_eq!(error(&files, &projects, &BTreeMap::new()), "无法解析客户端资源文件: 104");
    let wrong_owner = BTreeMap::from([(104, cf_file(1040000, 5, &sha('a'), &[])), (106, cf_file(1060000, 6, &sha('b'), &[]))]);
    assert_eq!(error(&files, &projects, &wrong_owner), "客户端资源文件不属于声明的项目: 4/104");
    let mut nameless = infos.clone();
    nameless.get_mut(&104).unwrap().file_name = Some("a/b.zip".into());
    assert_eq!(error(&files, &projects, &nameless), "客户端资源文件缺少文件名: 4/104");

    let (platforms, fake) = Fake::platforms(|_, _, _| json!({"data": []}));
    assert!(run(manifest::curse_forge_extras(&platforms, &[])).unwrap().is_empty());
    assert!(fake.paths().is_empty());
}

/// Live: the mod list of a real Modrinth pack through the real platforms.
#[test]
#[ignore = "reads a real modpack named by RDI_UPLOAD_SAMPLE and asks Modrinth and CurseForge"]
fn real_pack_mods() {
    use std::io::Read;
    let path = std::env::var("RDI_UPLOAD_SAMPLE").expect("RDI_UPLOAD_SAMPLE");
    let mut zip = zip::ZipArchive::new(std::fs::File::open(&path).unwrap()).unwrap();
    let name = zip.file_names().find(|n| n.rsplit('/').next() == Some("modrinth.index.json")).expect("a Modrinth pack").to_owned();
    let mut raw = String::new();
    zip.by_name(&name).unwrap().read_to_string(&mut raw).unwrap();
    let index: manifest::Index = serde_json::from_str(&raw).unwrap();
    let http = reqwest::Client::builder().timeout(std::time::Duration::from_secs(60)).build().unwrap();
    let transport = crate::catalog::transport::HttpTransport::new(http, true);
    let platforms = Platforms::new(Arc::new(transport));
    let none = |_: &BTreeSet<String>| BTreeMap::new();
    let started = std::time::Instant::now();
    let resolved = run(manifest::modrinth(&platforms, &index.files, false, &none)).unwrap();
    let count = |side: Side| resolved.mods.iter().filter(|m| m.side == side).count();
    println!(
        "{} {}: {} entries -> {} mods (mr {}, cf {}; both {}, client {}, server {}, unknown {}), {} extras, {:?}",
        index.name,
        index.version_id,
        index.files.len(),
        resolved.mods.len(),
        resolved.mods.iter().filter(|m| m.platform == "mr").count(),
        resolved.mods.iter().filter(|m| m.platform == "cf").count(),
        count(Side::Both),
        count(Side::Client),
        count(Side::Server),
        count(Side::Unknown),
        resolved.extras.len(),
        started.elapsed()
    );
    for extra in &resolved.extras {
        println!("extra {:?} {} required={}", extra.kind, extra.path.as_deref().unwrap_or_default(), extra.required);
    }
}
