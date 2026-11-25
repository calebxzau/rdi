//! Stage A2 part 2d: the steps around the readers (content store commit, sides, merging the
//! server pack's mods).
use super::prepare;
use super::reader::{self, Context, EmbeddedMod, Progress};
use super::reader_tests::{jar, sha1, with_ctx, zip_bytes};
use crate::pack_install::mods::{Mod, Side};
use crate::test_dir::TestDir;
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::path::PathBuf;

fn m(platform: &str, slug: &str, hash: &str, side: Side) -> Mod {
    Mod {
        platform: platform.into(),
        project_id: format!("p-{slug}"),
        slug: slug.into(),
        file_id: format!("f-{slug}-{hash}"),
        hash: hash.into(),
        side,
        download_urls: vec![format!("https://example.com/{platform}/{slug}.jar")],
        client_only_override: false,
        client_override_replaced: false,
    }
}

fn sha(c: char) -> String {
    c.to_string().repeat(40)
}

#[test]
fn recognised_jars_enter_the_content_store() {
    let dir = TestDir::new("rdi-upload-prepare");
    let (cache, staging) = (dir.join("cache"), dir.join("staging"));
    std::fs::create_dir_all(&staging).unwrap();
    let bytes = jar("embedded");
    let stage = |name: &str| {
        let path = staging.join(name);
        std::fs::write(&path, &bytes).unwrap();
        path
    };
    let good = m("mr", "good", &sha1(&bytes), Side::Both);
    let cf = m("cf", "cf-copy", &crate::digests::murmur2(&bytes).to_string(), Side::Both);
    let staged = vec![
        EmbeddedMod { m: good.clone(), staged: stage("good.jar"), original_name: "good.jar".into() },
        EmbeddedMod { m: cf.clone(), staged: stage("cf.jar"), original_name: "cf.jar".into() },
    ];
    assert_eq!(prepare::missing_mods(&cache, &[good.clone(), cf.clone()]).len(), 2);
    prepare::commit_mods(&cache, &Default::default(), &staged).unwrap();
    assert_eq!(std::fs::read(prepare::cached(&cache, &good).unwrap()).unwrap(), bytes);
    assert!(prepare::cached(&cache, &cf).unwrap().to_string_lossy().ends_with(".murmur2"));
    assert!(prepare::missing_mods(&cache, &[good, cf]).is_empty());
    // Staged copies are gone, and so is their folder.
    assert!(!staging.exists());

    std::fs::create_dir_all(&staging).unwrap();
    let wrong = m("mr", "wrong", &sha('a'), Side::Both);
    let staged = vec![EmbeddedMod { m: wrong.clone(), staged: stage("wrong.jar"), original_name: "wrong.jar".into() }];
    assert_eq!(prepare::commit_mods(&cache, &Default::default(), &staged).unwrap_err().to_string(), "内嵌mod校验失败: wrong.jar");
    assert!(prepare::cached(&cache, &wrong).is_none() && !staging.exists());
    // A record without a usable hash is never looked up.
    assert!(prepare::cached(&cache, &m("mr", "odd", "not-a-hash", Side::Both)).is_none());
}

#[test]
fn server_mods_merge_into_the_client_list() {
    let dir = TestDir::new("rdi-upload-prepare");
    let cache = dir.join("cache");
    let sides = |mods: &[Mod]| mods.iter().map(|m| (m.slug.clone(), m.side, m.client_only_override)).collect::<Vec<_>>();
    let both = |slug: &str| (slug.to_owned(), Side::Both, false);

    let client = vec![m("mr", "Zeta", &sha('a'), Side::Client), m("mr", "alpha", &sha('b'), Side::Unknown), m("cf", "cross", "123", Side::Both), m("mr", "only-client", &sha('c'), Side::Both)];
    let server = vec![m("mr", "zeta", &sha('a'), Side::Server), m("mr", "cross", &sha('d'), Side::Server), m("mr", "only-server", &sha('e'), Side::Both)];
    let merged = prepare::merge_client_server(&cache, &client, &server, false);
    // Sorted by slug; a mod of both packs counts for both sides, the rest for its own side.
    assert_eq!(
        sides(&merged),
        [("alpha".to_owned(), Side::Client, false), both("cross"), ("only-client".to_owned(), Side::Client, false), ("only-server".to_owned(), Side::Server, false), both("Zeta")]
    );
    // The client's record stays; the server's addresses join only when the bytes are the same.
    let zeta = merged.iter().find(|m| m.slug == "Zeta").unwrap();
    assert_eq!(zeta.download_urls, ["https://example.com/mr/Zeta.jar", "https://example.com/mr/zeta.jar"]);
    let cross = merged.iter().find(|m| m.slug == "cross").unwrap();
    assert_eq!((cross.platform.as_str(), cross.download_urls.len()), ("cf", 1));

    // Fabric keeps the sides the mods declare.
    let fabric = prepare::merge_client_server(&cache, &client, &server, true);
    assert_eq!(fabric.iter().map(|m| m.side).collect::<Vec<_>>(), [Side::Unknown, Side::Both, Side::Both, Side::Both, Side::Client]);

    // A client override with other bytes than the server's mod stays a client mod beside it.
    let mut replaced = m("mr", "tweak", &sha('1'), Side::Client);
    replaced.client_only_override = true;
    let merged = prepare::merge_client_server(&cache, std::slice::from_ref(&replaced), &[m("mr", "tweak", &sha('2'), Side::Both)], false);
    assert_eq!(sides(&merged), [("tweak".to_owned(), Side::Client, true), ("tweak".to_owned(), Side::Server, false)]);
    // With the same bytes it is simply one mod of both sides.
    let merged = prepare::merge_client_server(&cache, std::slice::from_ref(&replaced), &[m("mr", "tweak", &sha('1'), Side::Both)], false);
    assert_eq!(sides(&merged), [both("tweak")]);

    // A slug that two client mods share is not used to pair across sources.
    let client = vec![m("mr", "twin", &sha('a'), Side::Both), m("cf", "twin", "5", Side::Both)];
    let merged = prepare::merge_client_server(&cache, &client, &[m("mr", "twin", &sha('a'), Side::Both)], false);
    assert_eq!(merged.len(), 1);
    assert_eq!(merged[0].side, Side::Both);
}

#[test]
fn client_pack_is_prepared_with_default_sides_and_the_mod_rules() {
    let dir = TestDir::new("rdi-upload-prepare");
    let pack = dir.join("pack");
    std::fs::create_dir_all(pack.join("overrides/mods")).unwrap();
    let embedded = jar("embedded");
    std::fs::write(pack.join("overrides/mods/embedded.jar"), &embedded).unwrap();
    std::fs::write(
        pack.join("manifest.json"),
        json!({"name": "CF包", "version": "3", "minecraft": {"version": "1.20.1", "modLoaders": [{"id": "forge-47.4.0", "primary": true}]},
            "files": [{"projectID": 1, "fileID": 101}, {"projectID": 2, "fileID": 102}]})
        .to_string(),
    )
    .unwrap();
    let embedded_sha1 = sha1(&embedded);
    let answer = move |path: &str, _: bool, sent: &Value| match path {
        "mods" => json!({"data": [{"id": 1, "name": "One", "slug": "one", "classId": 6}, {"id": 2, "name": "Spark", "slug": "spark", "classId": 6}]}),
        "mods/files" => json!({"data": [
            {"id": 101, "modId": 1, "fileName": "1.jar", "hashes": [], "gameVersions": [], "fileFingerprint": 11},
            {"id": 102, "modId": 2, "fileName": "2.jar", "hashes": [], "gameVersions": ["Server"], "fileFingerprint": 12}]}),
        "version_files" if sent["hashes"] == json!([embedded_sha1]) => {
            json!({embedded_sha1.clone(): {"id": "ve", "project_id": "pe", "files": [{"url": "https://cdn.modrinth.com/e.jar", "hashes": {"sha1": embedded_sha1}}]}})
        }
        "version_files" => json!({}),
        "projects" => json!([{"id": "pe", "slug": "embedded-mod", "client_side": "required", "server_side": "required"}]),
        other => panic!("unexpected {other}"),
    };
    let (work, cache) = (dir.join("work"), dir.join("cache"));
    let (loaded, _, _) = with_ctx(&work, answer, |ctx| prepare::client(ctx, &cache, &pack));
    let loaded = loaded.unwrap();
    // The unknown CurseForge side becomes both; `spark` is on the upload's removal list.
    assert_eq!(loaded.mods.iter().map(|m| (m.slug.as_str(), m.side)).collect::<Vec<_>>(), [("one", Side::Both), ("embedded-mod", Side::Both)]);
    // The recognised jar is in the content store, and nothing is staged any more.
    assert!(prepare::cached(&cache, &loaded.mods[1]).is_some());
    assert_eq!(prepare::missing_mods(&cache, &loaded.mods).iter().map(|m| m.slug.as_str()).collect::<Vec<_>>(), ["one"]);
    assert!(loaded.embedded_mods.iter().all(|e| !e.staged.exists()));

    // The server pack carries the same embedded jar and one more: the first is recognised by
    // the platform and paired with the client's, the manifest mod stays a client mod.
    let server = dir.join("server.zip");
    std::fs::write(&server, zip_bytes(&[("mods/embedded.jar", &embedded), ("server.properties", b"motd=x")])).unwrap();
    let embedded_sha1 = sha1(&embedded);
    let answer = move |path: &str, _: bool, _: &Value| match path {
        "version_files" => json!({embedded_sha1.clone(): {"id": "ve", "project_id": "pe", "files": [{"url": "https://cdn.modrinth.com/e.jar", "hashes": {"sha1": embedded_sha1}}]}}),
        "projects" => json!([{"id": "pe", "slug": "embedded-mod", "client_side": "required", "server_side": "required"}]),
        other => panic!("unexpected {other}"),
    };
    let client_mods = loaded.mods.clone();
    let (prepared, progress, _) = with_ctx(&work, answer, |ctx| prepare::server(ctx, &cache, &server, &client_mods, false));
    let (server_pack, merged) = prepared.unwrap();
    assert_eq!(server_pack.mods.iter().map(|m| m.slug.as_str()).collect::<Vec<_>>(), ["embedded-mod"]);
    assert_eq!(server_pack.extras.iter().map(|e| e.relative.as_str()).collect::<Vec<_>>(), ["server.properties"]);
    assert_eq!(merged.iter().map(|m| (m.slug.as_str(), m.side)).collect::<Vec<_>>(), [("embedded-mod", Side::Both), ("one", Side::Client)]);
    assert_eq!(progress.first(), Some(&Progress::Phase("正在检查本地Mod".into())));
}

/// Live: a real client pack and its server pack, through the launcher's catalog.
#[test]
#[ignore = "reads real packs named by RDI_UPLOAD_SAMPLE and RDI_UPLOAD_SERVER_SAMPLE and asks Modrinth and CurseForge"]
fn real_server_pack() {
    let client = PathBuf::from(std::env::var("RDI_UPLOAD_SAMPLE").expect("RDI_UPLOAD_SAMPLE"));
    let server = PathBuf::from(std::env::var("RDI_UPLOAD_SERVER_SAMPLE").expect("RDI_UPLOAD_SERVER_SAMPLE"));
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
    let cache = dir.join("cache");
    let count = |mods: &[Mod], side: Side| mods.iter().filter(|m| m.side == side).count();
    let line = |mods: &[Mod]| format!("{} mods (both {}, client {}, server {}, unknown {})", mods.len(), count(mods, Side::Both), count(mods, Side::Client), count(mods, Side::Server), count(mods, Side::Unknown));
    let started = std::time::Instant::now();
    let loaded = prepare::client(&ctx, &cache, &client).unwrap();
    let fabric = loaded.summary.loader == "Fabric";
    println!("client: {}, {} embedded, {} missing from the store, {} left in the pack copy, {:?}", line(&loaded.mods), loaded.embedded_mods.len(), prepare::missing_mods(&cache, &loaded.mods).len(), reader::walk_files(&loaded.source_dir).unwrap().len(), started.elapsed());
    let started = std::time::Instant::now();
    let (server_pack, merged) = prepare::server(&ctx, &cache, &server, &loaded.mods, fabric).unwrap();
    println!("server: {}, {} extra files, save regions: {}, {:?}", line(&server_pack.mods), server_pack.extras.len(), server_pack.contains_mca, started.elapsed());
    println!("merged: {}", line(&merged));
}
