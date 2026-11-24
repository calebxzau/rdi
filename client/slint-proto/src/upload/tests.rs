//! Stage A1: draft rules, the manifest look and the checks before upload, against fakes.
use super::draft::{self, Draft};
use super::source::{self, Kind};
use crate::mcp::tests::FakeServer;
use crate::test_dir::TestDir;
use serde_json::{Value, json};
use std::io::Write;

fn good() -> Draft {
    Draft {
        name: "测试包".into(),
        version: "1.0".into(),
        categories: vec!["TECH".into()],
        icon_url: "https://media.forgecdn.net/avatars/1/2/icon.png".into(),
        source_url: String::new(),
        info: "一个用来测试上传的整合包".into(),
    }
}

#[test]
fn draft_problems_follow_the_kotlin_page() {
    assert!(draft::problems(&good(), false).is_empty());
    let with = |change: fn(&mut Draft)| {
        let mut d = good();
        change(&mut d);
        draft::problems(&d, false)
    };
    assert_eq!(with(|d| d.name = " ".into()), ["整合包名称不能为空"]);
    assert_eq!(with(|d| d.version = "".into()), ["版本号不能为空"]);
    assert_eq!(with(|d| d.info = "".into()), ["简介不能为空"]);
    assert_eq!(with(|d| d.info = "九个字九个字九个字".into()), ["简介至少需要10个字符"]);
    // Code points, as Kotlin `codePointCount`: ten emoji are ten characters.
    assert!(with(|d| d.info = "🐱".repeat(10)).is_empty());
    assert_eq!(with(|d| d.info = "字".repeat(101)), ["简介最多100个字符"]);
    assert_eq!(with(|d| d.icon_url = "".into()), ["图标链接不能为空"]);
    assert_eq!(with(|d| d.icon_url = "https://example.com/a.png".into()), ["只接受整合包发布网页的图标链接"]);
    assert_eq!(with(|d| d.icon_url = "media.forgecdn.net/a.png".into()), ["链接必须以http或https开头"]);
    assert_eq!(with(|d| d.icon_url = "ftp://modrinth.com/a.png".into()), ["链接必须以http或https开头"]);
    assert!(with(|d| d.icon_url = "http://i0.hdslb.com/a.png".into()).is_empty());
    assert!(with(|d| d.icon_url = "https://modrinth.com/a.png".into()).is_empty());
    assert_eq!(with(|d| d.icon_url = "https://evilmodrinth.com/a.png".into()), ["只接受整合包发布网页的图标链接"]);
    assert_eq!(with(|d| d.categories = vec![]), ["至少选择1个分类"]);
    assert_eq!(
        with(|d| d.categories = ["TECH", "MAGIC", "RPG", "WAR", "OTHER"].map(String::from).to_vec()),
        ["分类最多选择4个"]
    );
    assert!(with(|d| d.categories = ["TECH", "TECH", "MAGIC", "RPG", "WAR"].map(String::from).to_vec()).is_empty());
    assert_eq!(with(|d| d.categories = vec!["科技".into()]), ["未知的分类：科技"]);
    // A new version keeps the pack's own fields, so only name and version are checked.
    let update = Draft { name: "包".into(), version: "2".into(), ..Default::default() };
    assert!(draft::problems(&update, true).is_empty());
    assert_eq!(draft::normalize_categories(&["A", "B", "A", "C", "D", "E"].map(String::from)), ["A", "B", "C", "D"]);
    assert_eq!(draft::version_name("1.0 beta 2"), "1.0_beta_2");
}

#[test]
fn pack_type_and_overrides_follow_the_entry_names() {
    assert_eq!(source::detect_for_test(&["manifest.json", "overrides/", "overrides/config/a.txt"]), (Some(Kind::CurseForge), true));
    assert_eq!(source::detect_for_test(&["pack/manifest.json", "pack/overrides/mods/a.jar"]), (Some(Kind::CurseForge), true));
    assert_eq!(source::detect_for_test(&["pack/manifest.json", "overrides/a.txt"]), (Some(Kind::CurseForge), false));
    assert_eq!(source::detect_for_test(&["manifest.json", "overrides"]), (Some(Kind::CurseForge), true));
    assert_eq!(source::detect_for_test(&["manifest.json", "modrinth.index.json"]).0, Some(Kind::Modrinth));
    assert_eq!(source::detect_for_test(&["\\pack\\manifest.json", "pack\\overrides\\x"]), (Some(Kind::CurseForge), true));
    assert_eq!(source::detect_for_test(&["readme.txt"]).0, None);
}

fn zip(dir: &TestDir, name: &str, files: &[(&str, &[u8])]) -> std::path::PathBuf {
    let path = dir.join(name);
    let mut out = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
    for (entry, bytes) in files {
        if entry.ends_with('/') {
            out.add_directory(entry.trim_end_matches('/'), zip::write::SimpleFileOptions::default()).unwrap();
        } else {
            out.start_file(*entry, zip::write::SimpleFileOptions::default()).unwrap();
            out.write_all(bytes).unwrap();
        }
    }
    out.finish().unwrap();
    path
}

fn cf(mc: &str, loader: &str) -> Vec<u8> {
    serde_json::to_vec(&json!({"name": "CF包", "version": "", "minecraft": {"version": mc,
        "modLoaders": [{"id": loader, "primary": true}]}, "files": []}))
    .unwrap()
}

fn mr(mc: &str, deps: Value) -> Vec<u8> {
    let mut dependencies = deps;
    dependencies["minecraft"] = json!(mc);
    serde_json::to_vec(&json!({"game": "minecraft", "formatVersion": 1, "name": "MR包", "versionId": "2.0 beta",
        "files": [], "dependencies": dependencies}))
    .unwrap()
}

fn error(path: &std::path::Path) -> String {
    format!("{:#}", source::summary(path).unwrap_err())
}

#[test]
fn manifests_are_read_from_zips_and_folders() {
    let dir = TestDir::new("rdi-upload-test");
    let path = zip(&dir, "cf.zip", &[("manifest.json", &cf("1.20.1", "forge-47.4.0")), ("overrides/", b"")]);
    let summary = source::summary(&path).unwrap();
    assert_eq!(
        summary,
        source::Summary { kind: Kind::CurseForge, name: "CF包".into(), version: "1.0".into(), mc_version: "V201".into(), loader: "forge".into() }
    );
    let path = zip(&dir, "mr.zip", &[("modrinth.index.json", &mr("1.20.1", json!({"fabric-loader": "0.16.0"})))]);
    let summary = source::summary(&path).unwrap();
    assert_eq!((summary.kind, summary.loader.as_str(), summary.version.as_str()), (Kind::Modrinth, "Fabric", "2.0 beta"));
    let path = zip(&dir, "mr21.zip", &[("modrinth.index.json", &mr("1.21.1", json!({"neoforge": "21.1.1"})))]);
    assert_eq!(source::summary(&path).unwrap().mc_version, "V211");

    let folder = dir.join("folder");
    std::fs::create_dir_all(folder.join("pack/overrides/config")).unwrap();
    std::fs::write(folder.join("pack/manifest.json"), cf("1.21.1", "neoforge-21.1.100")).unwrap();
    let summary = source::summary(&folder).unwrap();
    assert_eq!((summary.mc_version.as_str(), summary.loader.as_str()), ("V211", "neoforge"));

    assert_eq!(error(&zip(&dir, "a.zip", &[("manifest.json", &cf("1.20.1", "forge-47.4.0"))])), "找不到包中overrides目录");
    assert_eq!(error(&zip(&dir, "b.zip", &[("readme.txt", b"x")])), "找不到此包的概要文件(manifest.json/modrinth.index.json)");
    assert_eq!(error(&zip(&dir, "c.zip", &[("manifest.json", &cf("1.19.2", "forge-43.0.0")), ("overrides/", b"")])), "暂不支持MC版本1.19.2");
    assert_eq!(
        error(&zip(&dir, "d.zip", &[("manifest.json", &cf("1.7.10", "forge-10.13.4.1614")), ("overrides/", b"")])),
        "目前只支持上传MC1.20.1和MC1.21.1整合包"
    );
    assert_eq!(error(&zip(&dir, "e.zip", &[("modrinth.index.json", &mr("1.19.2", json!({"forge": "1"})))])), "不支持的MC版本: 1.19.2");
    assert_eq!(
        error(&zip(&dir, "f.zip", &[("modrinth.index.json", &mr("1.21.1", json!({"fabric-loader": "0.16.0"})))])),
        "已识别为Minecraft1.21.1／Fabric，当前版本暂不支持导入"
    );
    let mut wrong_game: Value = serde_json::from_slice(&mr("1.20.1", json!({"forge": "47"}))).unwrap();
    wrong_game["game"] = json!("terraria");
    assert_eq!(error(&zip(&dir, "g.zip", &[("modrinth.index.json", &serde_json::to_vec(&wrong_game).unwrap())])), "不支持的游戏类型: terraria");
    assert!(error(&zip(&dir, "h.zip", &[("manifest.json", b"{"), ("overrides/", b"")])).starts_with("manifest.json 解析失败"));
    assert!(error(&dir.join("missing.zip")).starts_with("找不到整合包文件"));
    assert!(error(&{
        let p = dir.join("not.zip");
        std::fs::write(&p, b"not a zip").unwrap();
        p
    })
    .starts_with("读取整合包文件失败"));
}

#[test]
fn check_fills_the_form_and_runs_the_preflight_for_an_update() {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let dir = TestDir::new("rdi-upload-test");
    let path = zip(&dir, "cf.zip", &[("manifest.json", &cf("1.20.1", "forge-47.4.0")), ("overrides/", b"")]);
    let pack = "66d000000000000000000003";
    let server = FakeServer::new(vec![
        (format!("/modpack/{pack}/detail"), json!({"_id": pack, "name": "已有包", "icon": "https://modrinth.com/i.png",
            "info": "已有的简介文字已有的简介", "categories": ["MAGIC"], "canUploadVersion": true, "modCount": 0,
            "modloader": "forge", "mcVer": "V201", "authorId": pack})),
        ("/modpack/preflight".into(), Value::Null),
    ]);
    let api = server.client();
    let request = super::Request {
        path: path.clone(),
        draft: Draft { name: "别的名字".into(), version: "2.0 rc".into(), ..Default::default() },
        update_modpack_id: Some(pack.into()),
    };
    let check = runtime.block_on(super::check(&api, request)).unwrap();
    assert!(check.problems.is_empty(), "{:?}", check.problems);
    assert_eq!(check.preflight, Some(Ok(())));
    assert_eq!(check.draft.name, "已有包", "an update keeps the pack's name");
    assert_eq!(check.draft.version, "2.0_rc");
    assert_eq!(check.draft.categories, ["MAGIC"]);
    assert!(server.hits.lock().unwrap().iter().any(|h| h == "/modpack/preflight"));

    // A new pack with an incomplete form: problems, and no request to the server.
    let before = server.hits.lock().unwrap().len();
    let check = runtime
        .block_on(super::check(&api, super::Request { path, draft: Draft::default(), update_modpack_id: None }))
        .unwrap();
    assert_eq!(check.draft.name, "CF包");
    assert_eq!(check.draft.version, "1.0");
    assert_eq!(check.problems, ["简介不能为空", "图标链接不能为空", "至少选择1个分类"]);
    assert_eq!(check.preflight, None);
    assert_eq!(server.hits.lock().unwrap().len(), before);

    // A pack the player may not add versions to.
    let locked = "66d000000000000000000004";
    let server = FakeServer::new(vec![(format!("/modpack/{locked}/detail"), json!({"_id": locked, "name": "别人的包", "canUploadVersion": false}))]);
    let request = super::Request { path: dir.join("cf.zip"), draft: Draft::default(), update_modpack_id: Some(locked.into()) };
    let error = runtime.block_on(super::check(&server.client(), request)).unwrap_err();
    assert_eq!(error.to_string(), "没有上传新版本的权限");
    assert_eq!(super::api::check_pack_id("../x").unwrap_err().to_string(), "整合包编号无效：../x");
}

#[test]
fn preflight_body_matches_the_kotlin_dto() {
    let mut d = good();
    d.categories = ["TECH", "TECH", "RPG"].map(String::from).to_vec();
    d.info = "  一个用来测试上传的整合包  ".into();
    assert_eq!(
        super::api::preflight_body(&d, "V211", "neoforge", None),
        json!({"modpackId": null, "name": "测试包", "verName": "1.0", "mcVer": "V211", "modLoader": "neoforge",
            "iconUrl": "https://media.forgecdn.net/avatars/1/2/icon.png", "sourceUrl": null,
            "info": "一个用来测试上传的整合包", "categories": ["TECH", "RPG"]})
    );
}

/// A real pack from disk: `RDI_UPLOAD_SAMPLE=<zip or folder>`; run with `--ignored`.
#[test]
#[ignore = "reads a real modpack named by RDI_UPLOAD_SAMPLE"]
fn real_pack_summary() {
    let path = std::env::var("RDI_UPLOAD_SAMPLE").expect("RDI_UPLOAD_SAMPLE");
    let summary = source::summary(std::path::Path::new(&path)).unwrap();
    eprintln!("{summary:?}");
}
