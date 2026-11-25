//! Stage A2 part 2b: the pack reader against a fake transport and packs built in a temp folder.
use super::manifest_tests::Fake;
use super::policy::{self, SaveRegions};
use super::reader::{self, Context, Loaded, Progress};
use crate::pack_install::extras::Kind;
use crate::pack_install::mods::Side;
use crate::test_dir::TestDir;
use serde_json::{Value, json};
use sha1::Digest as _;
use std::collections::{BTreeMap, BTreeSet};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// A zip in memory; names ending in `/` are folders.
fn zip_bytes(files: &[(&str, &[u8])]) -> Vec<u8> {
    let mut out = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    for (entry, bytes) in files {
        if entry.ends_with('/') {
            out.add_directory(entry.trim_end_matches('/'), zip::write::SimpleFileOptions::default()).unwrap();
        } else {
            out.start_file(*entry, zip::write::SimpleFileOptions::default()).unwrap();
            out.write_all(bytes).unwrap();
        }
    }
    out.finish().unwrap().into_inner()
}

/// A jar that is a valid zip and differs by `text`.
fn jar(text: &str) -> Vec<u8> {
    zip_bytes(&[("marker.txt", text.as_bytes())])
}

fn sha1(bytes: &[u8]) -> String {
    format!("{:x}", sha1::Sha1::digest(bytes))
}

fn hex(c: char) -> String {
    c.to_string().repeat(40)
}

/// Modrinth as a fake: `known` maps a SHA-1 to `(version, project, slug, client, server)`.
fn modrinth(known: Vec<(String, [&'static str; 5])>) -> impl Fn(&str, bool, &Value) -> Value + Send + Sync + 'static {
    move |path, _, sent| match path {
        "version_files" => {
            let asked: Vec<String> = serde_json::from_value(sent["hashes"].clone()).unwrap();
            Value::Object(
                known
                    .iter()
                    .filter(|(hash, _)| asked.contains(hash))
                    .map(|(hash, v)| (hash.clone(), json!({"id": v[0], "project_id": v[1], "files": [{"url": format!("https://cdn.modrinth.com/{}.bin", v[0]), "hashes": {"sha1": hash}}]})))
                    .collect(),
            )
        }
        "projects" => {
            let asked: Vec<String> = serde_json::from_str(sent["ids"].as_str().unwrap()).unwrap();
            let mut seen = BTreeSet::new();
            json!(known
                .iter()
                .filter(|(_, v)| asked.contains(&v[1].to_owned()) && seen.insert(v[1]))
                .map(|(_, v)| json!({"id": v[1], "slug": v[2], "client_side": v[3], "server_side": v[4]}))
                .collect::<Vec<_>>())
        }
        "fingerprints/432" => json!({"data": {"exactMatches": []}}),
        "mods" | "mods/files" => json!({"data": []}),
        other => panic!("unexpected {other}"),
    }
}

fn load(work: &Path, input: &Path, answer: impl Fn(&str, bool, &Value) -> Value + Send + Sync + 'static) -> (anyhow::Result<Loaded>, Vec<Progress>, Vec<String>) {
    let (platforms, fake) = Fake::platforms(answer);
    let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().unwrap();
    let seen = Mutex::new(Vec::new());
    let progress = |p: Progress| seen.lock().unwrap().push(p);
    let slugs = |_: &BTreeSet<String>| BTreeMap::new();
    let ctx = Context { runtime: runtime.handle().clone(), platforms, work_dir: work.to_path_buf(), cancel: Default::default(), progress: &progress, mr_slugs: &slugs };
    let loaded = reader::load(&ctx, input);
    let paths = fake.paths().into_iter().map(|(p, _)| p).collect();
    (loaded, seen.into_inner().unwrap(), paths)
}

fn left_in(dir: &Path) -> Vec<String> {
    let mut names: Vec<String> = reader::walk_files(dir).unwrap().iter().map(|f| reader::relative(dir, f)).collect();
    names.sort();
    names
}

#[test]
fn skip_rules_and_save_regions_follow_kotlin() {
    let skip = |path: &str, directory: bool| policy::skip_entry(path, directory, true, &[]);
    assert!(skip("overrides/shaderpacks/a.zip", false));
    assert!(skip("shaderpacks/a.zip", false));
    assert!(!policy::skip_entry("overrides/shaderpacks/a.txt", false, true, &["shaderpacks\\a.txt".into()]));
    assert!(skip("overrides/kubejs/probe/x.d.ts", false));
    assert!(skip("overrides/config/mycache/a.txt", false));
    assert!(!skip("overrides/config/cache.txt", false));
    assert!(skip("overrides/config/cache", true));
    assert!(!policy::skip_entry("overrides/config/mycache/a.txt", false, false, &[]));
    assert!(skip("overrides/config/yes_steve_model/builtin/a", false));
    assert!(skip("overrides/config/touhou_little_maid/a.json", false));
    assert!(skip("overrides/史蒂夫模型/a.json", false));
    assert!(skip("overrides/a/intro.mp4", false));
    assert!(skip("overrides/config/ftbquests/quests/lang/ja_jp.snbt", false));
    assert!(skip("overrides/config/ftbquests/quests/lang/zh_cn/a.snbt", false));
    assert!(skip("overrides/config/ftbquests/quests/lang/zh_cn", true));
    assert!(!skip("overrides/config/ftbquests/quests/lang/zh_cn.snbt", false));
    assert!(!skip("overrides/config/ftbquests/quests/lang/", true));
    assert!(!skip("overrides/config/a.toml", false));
    assert!(policy::is_disabled("mods/a.jar.DISABLED", false));
    assert!(!policy::is_disabled("mods/a.disabled", true));
    assert!(!policy::is_disabled("mods/disabled", false));

    let regions = SaveRegions::of(["overrides/saves/World/level.dat", "overrides/config/a.toml"]);
    assert!(regions.is_save_region("overrides/saves/world/region/r.0.0.mca", false));
    assert!(regions.is_save_region("overrides\\saves\\World\\DIM-1\\region\\r.0.0.MCC", false));
    assert!(!regions.is_save_region("overrides/saves/world/region", true));
    assert!(!regions.is_save_region("overrides/saves/other/region/r.0.0.mca", false));
    assert!(!regions.is_save_region("overrides/saves/world/ftbteambases/region/r.0.0.mca", false));
    assert!(!regions.is_save_region("overrides/saves/world/level.dat", false));
    // A `level.dat` at the root makes the whole tree a save.
    assert!(SaveRegions::of(["level.dat"]).is_save_region("region/r.0.0.mca", false));
    assert!(!SaveRegions::of([]).is_save_region("region/r.0.0.mca", false));
}

#[test]
fn modrinth_pack_is_read_with_its_embedded_jars_and_packs() {
    let dir = TestDir::new("rdi-upload-reader");
    let (embedded, unknown, raw, client_only) = (jar("embedded"), jar("unknown"), jar("raw replacement"), jar("client only"));
    let (local_pack, declared_only) = (zip_bytes(&[("pack.mcmeta", b"local")]), hex('d'));
    let index = json!({"formatVersion": 1, "game": "minecraft", "versionId": "2.0", "name": "测试包",
        "dependencies": {"minecraft": "1.20.1", "forge": "47.4.0"},
        "files": [
            {"path": "mods/a.jar", "hashes": {"sha1": hex('a'), "sha512": "x"}, "downloads": ["https://cdn.modrinth.com/a.jar"], "fileSize": 1},
            {"path": "mods/replaced.jar", "hashes": {"sha1": hex('b'), "sha512": "x"}, "downloads": ["https://cdn.modrinth.com/b.jar"], "fileSize": 1},
            {"path": "resourcepacks/declared.zip", "hashes": {"sha1": declared_only, "sha512": "x"}, "downloads": [], "fileSize": 1},
            {"path": "resourcepacks/local.zip", "hashes": {"sha1": hex('e'), "sha512": "x"}, "downloads": [], "fileSize": 1}
        ]});
    let pack = dir.join("pack.zip");
    std::fs::write(
        &pack,
        zip_bytes(&[
            ("modrinth.index.json", index.to_string().as_bytes()),
            ("overrides/", b""),
            ("overrides/mods/embedded.jar", &embedded),
            ("overrides/mods/unknown.jar", &unknown),
            ("overrides/mods/note.txt", b"not a jar"),
            ("overrides/config/a.toml", b"a = 1"),
            ("overrides/resourcepacks/local.zip", &local_pack),
            ("overrides/saves/world/level.dat", b"level"),
            ("overrides/saves/world/region/r.0.0.mca", b"region"),
            ("client-overrides/mods/replaced.jar", &raw),
            ("client-overrides/mods/clientonly.jar", &client_only),
        ]),
    )
    .unwrap();
    let known = vec![
        (hex('a'), ["va", "pa", "alpha", "required", "required"]),
        (hex('b'), ["vb", "pb", "beta", "required", "required"]),
        (hex('d'), ["vd", "pd", "declared", "required", "unsupported"]),
        (hex('e'), ["ve", "pe", "remote-local", "required", "unsupported"]),
        (sha1(&embedded), ["vm", "pm", "embedded-mod", "required", "required"]),
        (sha1(&client_only), ["vc", "pc", "client-mod", "required", "required"]),
        (sha1(&local_pack), ["vl", "pl", "local-pack", "required", "unsupported"]),
    ];
    let work = dir.join("work");
    let (loaded, progress, paths) = load(&work, &pack, modrinth(known));
    let loaded = loaded.unwrap();

    assert_eq!((loaded.summary.name.as_str(), loaded.summary.version.as_str(), loaded.summary.loader.as_str()), ("测试包", "2.0", "forge"));
    let mods: Vec<(&str, Side, bool, bool)> = loaded.mods.iter().map(|m| (m.slug.as_str(), m.side, m.client_only_override, m.client_override_replaced)).collect();
    assert_eq!(
        mods,
        [
            ("alpha", Side::Both, false, false),
            // The unknown client override jar at mods/replaced.jar replaces this one for clients.
            ("beta", Side::Server, false, true),
            // Embedded jars follow in walk order: `client-overrides` sorts before `overrides`.
            ("client-mod", Side::Client, true, false),
            ("embedded-mod", Side::Both, false, false),
        ]
    );
    // Recognised jars are staged under their content store names and leave the pack copy.
    let staged: Vec<(String, &str)> = loaded.embedded_mods.iter().map(|e| (reader::relative(&work, &e.staged), e.original_name.as_str())).collect();
    assert_eq!(staged.len(), 2);
    assert!(staged[0].0.ends_with(&format!("/client-mod_mr_{}.jar", sha1(&client_only))) && staged[0].1 == "clientonly.jar", "{staged:?}");
    assert!(staged[1].0.ends_with(&format!("/embedded-mod_mr_{}.jar", sha1(&embedded))) && staged[1].1 == "embedded.jar", "{staged:?}");
    assert_eq!(std::fs::read(&loaded.embedded_mods[1].staged).unwrap(), embedded);
    assert_eq!(loaded.embedded_names.get(&format!("embedded-mod_mr_{}.jar", sha1(&embedded))).map(String::as_str), Some("embedded.jar"));
    assert_eq!(
        left_in(&loaded.source_dir),
        [
            "client-overrides/mods/replaced.jar",
            "modrinth.index.json",
            "overrides/config/a.toml",
            "overrides/mods/note.txt",
            "overrides/mods/unknown.jar",
            "overrides/resourcepacks/local.zip",
            "overrides/saves/world/level.dat",
            "overrides/saves/world/region/r.0.0.mca",
        ]
    );
    // The pack lying in the pack takes the place of the manifest entry for the same path.
    let extras: Vec<(Kind, &str, &str)> = loaded.extras.iter().map(|c| (c.kind, c.path.as_deref().unwrap(), c.slug.as_str())).collect();
    assert_eq!(extras, [(Kind::ResPack, "resourcepacks/declared.zip", "declared"), (Kind::ResPack, "resourcepacks/local.zip", "local-pack")]);
    assert_eq!(loaded.embedded_extras.len(), 1);
    let extra = &loaded.embedded_extras[0];
    assert_eq!((extra.source_relative.as_str(), extra.sha1.as_str()), ("overrides/resourcepacks/local.zip", sha1(&local_pack).as_str()));
    assert_eq!(std::fs::read(&extra.staged).unwrap(), local_pack);
    assert!(loaded.contains_mca);

    assert_eq!(paths, ["version_files", "projects", "fingerprints/432", "version_files", "projects", "version_files", "projects"]);
    let phases: Vec<&str> = progress.iter().filter_map(|p| if let Progress::Phase(text) = p { Some(text.as_str()) } else { None }).collect();
    assert_eq!(phases.first(), Some(&"开始读取整合包"));
    assert!(phases.contains(&"整合包概要读取完成 测试包 2.0"), "{phases:?}");
    assert!(phases.contains(&"开始整理Mod列表 MC1.20.1 forge"));
    assert!(phases.contains(&"发现包内内置mods4个"));
    assert!(phases.contains(&"已识别内置mods2个"));
    assert_eq!(phases.last(), Some(&"Mod列表整理完成，共4个，准备进入编辑"));
    assert!(progress.contains(&Progress::Percent("整合包解压完成".into(), 1.0)));
}

#[test]
fn curse_forge_folder_is_read_and_a_failed_read_leaves_nothing() {
    let dir = TestDir::new("rdi-upload-reader");
    let pack = dir.join("pack");
    let write = |path: &str, bytes: &[u8]| {
        let file: PathBuf = pack.join(path);
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(file, bytes).unwrap();
    };
    write(
        "manifest.json",
        json!({"name": "CF包", "version": "", "minecraft": {"version": "1.21.1", "modLoaders": [{"id": "neoforge-21.1.200", "primary": true}]},
            "files": [{"projectID": 1, "fileID": 101}, {"projectID": 4, "fileID": 104, "required": false}]})
        .to_string()
        .as_bytes(),
    );
    write("overrides/config/a.toml", b"a = 1");
    let answer = |path: &str, _: bool, sent: &Value| match path {
        "mods" if sent["filterPcOnly"] == json!(true) => json!({"data": [{"id": 1, "name": "One", "slug": "one", "classId": 6}]}),
        "mods" => json!({"data": [{"id": 1, "name": "One", "slug": "one", "classId": 6}, {"id": 4, "name": "Four", "slug": "four", "classId": 12}]}),
        "mods/files" => json!({"data": [
            {"id": 1010000, "modId": 1, "fileName": "1.jar", "hashes": [], "gameVersions": ["Client"], "fileFingerprint": 11},
            {"id": 101, "modId": 1, "fileName": "1.jar", "hashes": [], "gameVersions": ["Server", "Client"], "fileFingerprint": 11},
            {"id": 104, "modId": 4, "fileName": "four.zip", "hashes": [], "gameVersions": [], "fileFingerprint": 14, "downloadUrl": "https://edge.forgecdn.net/files/1/4/four.zip"}]}),
        other => panic!("unexpected {other}"),
    };
    let work = dir.join("work");
    let (loaded, _, paths) = load(&work, &pack, answer);
    let loaded = loaded.unwrap();
    assert_eq!((loaded.summary.name.as_str(), loaded.summary.version.as_str(), loaded.summary.loader.as_str()), ("CF包", "1.0", "neoforge"));
    assert_eq!(loaded.mods.iter().map(|m| (m.slug.as_str(), m.file_id.as_str(), m.side)).collect::<Vec<_>>(), [("one", "101", Side::Both)]);
    assert_eq!(loaded.extras.iter().map(|c| (c.path.as_deref().unwrap(), c.required)).collect::<Vec<_>>(), [("resourcepacks/four.zip", false)]);
    assert!(loaded.embedded_mods.is_empty() && loaded.embedded_extras.is_empty() && !loaded.contains_mca);
    // The folder is copied: the pack itself is never changed.
    assert_ne!(loaded.source_dir, pack);
    assert_eq!(left_in(&loaded.source_dir), ["manifest.json", "overrides/config/a.toml"]);
    // The mod id lists skip the second official request because nothing is missing... except
    // file 104's project for the mod-only list, which is asked again.
    assert_eq!(paths, ["mods", "mods", "mods/files", "mods", "mods/files"]);

    // A manifest without a required field passes the first look and fails the strict parse;
    // the copy is removed again.
    let broken = dir.join("broken");
    std::fs::create_dir_all(broken.join("overrides")).unwrap();
    std::fs::write(broken.join("manifest.json"), json!({"name": "坏包", "minecraft": {"version": "1.21.1", "modLoaders": [{"id": "neoforge-21.1.200"}]}}).to_string()).unwrap();
    let work = dir.join("work-broken");
    let (loaded, _, _) = load(&work, &broken, |path, _, _| panic!("unexpected {path}"));
    assert!(loaded.unwrap_err().to_string().starts_with("manifest.json 解析失败"));
    assert!(std::fs::read_dir(&work).unwrap().next().is_none());

    let (loaded, _, _) = load(&work, &dir.join("missing.zip"), |path, _, _| panic!("unexpected {path}"));
    assert!(loaded.unwrap_err().to_string().starts_with("找不到整合包文件"));
}

/// Live: the whole read of a real pack through the launcher's catalog (transport and slug map).
/// `RDI_UPLOAD_DUMP` names a JSON file for the mod and extra lists, to compare with Kotlin.
#[test]
#[ignore = "reads a real modpack named by RDI_UPLOAD_SAMPLE and asks Modrinth and CurseForge"]
fn real_pack_read() {
    let sample = PathBuf::from(std::env::var("RDI_UPLOAD_SAMPLE").expect("RDI_UPLOAD_SAMPLE"));
    let dir = TestDir::new("rdi-upload-live");
    let http = reqwest::Client::builder().timeout(std::time::Duration::from_secs(60)).build().unwrap();
    let catalog = crate::catalog::Catalog::new(http, &dir.join("data"));
    let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
    let progress = |p: Progress| {
        if let Progress::Phase(text) | Progress::Warn(text) = p {
            println!("[进度] {text}");
        }
    };
    let slugs = |asked: &BTreeSet<String>| super::manifest::catalog_mr_slugs(&catalog, asked);
    let ctx = Context {
        runtime: runtime.handle().clone(),
        platforms: super::platform::Platforms::new(catalog.transport()),
        work_dir: dir.join("work"),
        cancel: Default::default(),
        progress: &progress,
        mr_slugs: &slugs,
    };
    let started = std::time::Instant::now();
    let loaded = reader::load(&ctx, &sample).unwrap();
    let count = |side: Side| loaded.mods.iter().filter(|m| m.side == side).count();
    println!(
        "{} mods (both {}, client {}, server {}, unknown {}; client-only override {}, replaced {}), {} embedded jars staged, {} extras ({} from the pack), save regions: {}, {:?}",
        loaded.mods.len(),
        count(Side::Both),
        count(Side::Client),
        count(Side::Server),
        count(Side::Unknown),
        loaded.mods.iter().filter(|m| m.client_only_override).count(),
        loaded.mods.iter().filter(|m| m.client_override_replaced).count(),
        loaded.embedded_mods.len(),
        loaded.extras.len(),
        loaded.embedded_extras.len(),
        loaded.contains_mca,
        started.elapsed()
    );
    if let Ok(dump) = std::env::var("RDI_UPLOAD_DUMP") {
        let extras: Vec<Value> = loaded
            .extras
            .iter()
            .map(|c| json!({"platform": format!("{:?}", c.platform), "type": format!("{:?}", c.kind), "projectId": c.project_id, "fileId": c.file_id, "slug": c.slug,
                "hash": c.hash, "path": c.path, "required": c.required, "downloadUrls": c.download_urls}))
            .collect();
        let value = json!({"mods": loaded.mods, "extras": extras, "embeddedNames": loaded.embedded_names, "containsMca": loaded.contains_mca});
        std::fs::write(dump, serde_json::to_string_pretty(&value).unwrap()).unwrap();
    }
}
