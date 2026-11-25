//! Stage A5a: the upload drafts without the network.
use super::drafts::{Drafts, GONE, IDLE, MCA_WARNING, Phase, parse_side};
use super::reader::Loaded;
use super::source::{Kind, Summary};
use crate::content_store::cache_file;
use crate::pack_install::mods::{Mod, Side};
use crate::test_dir::TestDir;
use std::collections::BTreeMap;
use std::io::Write;
use std::path::Path;
use std::time::Duration;

fn summary(loader: &str) -> Summary {
    Summary { kind: Kind::Modrinth, name: "测试包".into(), version: "1.0".into(), mc_version: "V201".into(), loader: loader.into() }
}

fn sha1_of(n: u8) -> String {
    format!("{n:02x}").repeat(20)
}

fn mr(slug: &str, n: u8, side: Side) -> Mod {
    Mod {
        platform: "mr".into(),
        project_id: format!("p{n}"),
        slug: slug.into(),
        file_id: format!("f{n}"),
        hash: sha1_of(n),
        side,
        download_urls: vec![],
        client_only_override: false,
        client_override_replaced: false,
    }
}

fn loaded(loader: &str, mods: Vec<Mod>, mca: bool) -> Loaded {
    Loaded {
        summary: summary(loader),
        source_dir: "work".into(),
        mods,
        embedded_names: BTreeMap::new(),
        embedded_mods: vec![],
        extras: vec![],
        embedded_extras: vec![],
        contains_mca: mca,
    }
}

/// A jar in the content store under the mod's digest, with a Fabric or Forge descriptor.
fn cache_jar(cache: &Path, m: &Mod, entry: &str, text: &str) -> std::path::PathBuf {
    let file = cache_file(cache, &m.digest().unwrap());
    std::fs::create_dir_all(file.parent().unwrap()).unwrap();
    let mut zip = zip::ZipWriter::new(std::fs::File::create(&file).unwrap());
    zip.start_file(entry, zip::write::SimpleFileOptions::default()).unwrap();
    zip.write_all(text.as_bytes()).unwrap();
    zip.finish().unwrap();
    file
}

fn key(m: &Mod) -> String {
    format!("{}:{}:{}:{}", m.platform, m.project_id, m.file_id, m.hash)
}

#[test]
fn sides_are_set_by_id_or_unique_slug_and_all_or_nothing() {
    let dir = TestDir::new("rdi-upload-drafts");
    let drafts = Drafts::new(dir.join("root"));
    let (a, b, c) = (mr("alpha", 1, Side::Both), mr("beta", 2, Side::Both), mr("beta", 3, Side::Client));
    let id = drafts.insert_read(loaded("forge", vec![a.clone(), b.clone(), c.clone()], false), &dir.join("cache"));

    let changed = drafts.with(&id, |d| d.set_sides(&[(key(&a), Side::Client), ("ALPHA".into(), Side::Client)])).unwrap();
    assert_eq!(changed, 1, "the second change names the same mod with the same side");

    // One bad name stops every change of the call.
    let error = drafts.with(&id, |d| d.set_sides(&[(key(&b), Side::Server), ("beta".into(), Side::Server), ("gamma".into(), Side::Both)])).unwrap_err().to_string();
    assert!(error.contains("有多个Mod叫beta") && error.contains("找不到Mod：gamma"), "{error}");
    let sides = drafts.with(&id, |d| Ok(d.pack.as_ref().unwrap().mods.iter().map(|m| m.side).collect::<Vec<_>>())).unwrap();
    assert_eq!(sides, [Side::Client, Side::Both, Side::Client]);

    assert_eq!(drafts.with(&id, |d| d.set_sides(&[(key(&c), Side::Both)])).unwrap(), 1);
    assert_eq!(parse_side(" Server ").unwrap(), Side::Server);
    assert!(parse_side("unknown").is_err());
}

#[test]
fn fabric_sides_declared_by_the_jar_are_locked() {
    let dir = TestDir::new("rdi-upload-drafts");
    let cache = dir.join("cache");
    let (declared, open, plain) = (mr("sodium", 1, Side::Client), mr("lithium", 2, Side::Both), mr("jei", 3, Side::Both));
    cache_jar(&cache, &declared, "fabric.mod.json", r#"{"id": "Sodium", "version": "0.5", "environment": "client"}"#);
    let open_jar = cache_jar(&cache, &open, "fabric.mod.json", r#"{"id": "lithium", "version": "0.11", "environment": "*"}"#);
    let drafts = Drafts::new(dir.join("root"));
    let id = drafts.insert_read(loaded("Fabric", vec![declared.clone(), open.clone(), plain.clone()], false), &cache);

    let error = drafts.with(&id, |d| d.set_sides(&[("sodium".into(), Side::Both)])).unwrap_err().to_string();
    assert!(error.contains("sodium的运行端由Mod文件(fabric.mod.json)声明"), "{error}");
    // `*` declares no side, so the agent decides.
    assert_eq!(drafts.with(&id, |d| d.set_sides(&[("lithium".into(), Side::Server)])).unwrap(), 1);

    let view = drafts.with(&id, |d| Ok(d.view())).unwrap();
    assert_eq!(view["status"], "ready");
    assert_eq!(view["jars_missing"], 1);
    let mods = view["mods"].as_array().unwrap();
    assert_eq!(mods[0]["side_locked"], true);
    assert_eq!(mods[0]["mod_id"], "sodium");
    assert_eq!(mods[1]["side"], "server");
    assert_eq!(mods[1]["side_locked"], false);
    assert_eq!(mods[1]["mod_version"], "0.11");
    assert_eq!(mods[1]["jar"], open_jar.display().to_string());
    assert_eq!(mods[2]["jar"], serde_json::Value::Null);
    assert_eq!(mods[2]["id"], key(&plain));
}

#[test]
fn forge_jars_are_never_locked_and_the_map_warning_comes_first() {
    let dir = TestDir::new("rdi-upload-drafts");
    let cache = dir.join("cache");
    let m = mr("create", 1, Side::Both);
    cache_jar(&cache, &m, "META-INF/mods.toml", "[[mods]]\nmodId=\"create\"\nversion=\"6.0\"\n");
    let drafts = Drafts::new(dir.join("root"));
    let id = drafts.insert_read(loaded("forge", vec![m], true), &cache);
    let view = drafts.with(&id, |d| Ok(d.view())).unwrap();
    assert_eq!(view["warnings"][0], MCA_WARNING);
    assert_eq!(view["mods"][0]["mod_id"], "create");
    assert_eq!(drafts.with(&id, |d| d.set_sides(&[("create".into(), Side::Client)])).unwrap(), 1);
}

#[test]
fn a_new_draft_replaces_the_old_one_and_its_folder() {
    let dir = TestDir::new("rdi-upload-drafts");
    let drafts = Drafts::new(dir.join("root"));
    let first = drafts.insert_read(loaded("forge", vec![], false), &dir.join("cache"));
    let first_dir = drafts.work_dir_of(&first);
    assert!(first_dir.is_dir());
    let second = drafts.insert_read(loaded("forge", vec![], false), &dir.join("cache"));
    assert!(!first_dir.exists(), "the replaced draft's folder goes");
    assert_eq!(drafts.with(&first, |_| Ok(())).unwrap_err().to_string(), GONE);

    // A draft being read leaves its folder to its task, which removes it when it ends.
    drafts.set_phase(Phase::Reading { task: "t1".into(), server: false });
    let dropped = drafts.discard(&second).unwrap();
    assert_eq!(dropped.0.as_deref(), Some("t1"));
    assert!(drafts.work_dir_of(&second).is_dir());
    drafts.finish_for_test(&second, false);
    assert!(!drafts.work_dir_of(&second).exists());
    assert!(drafts.discard(&second).is_err());
}

#[test]
fn an_idle_draft_expires_unless_its_pack_is_being_read() {
    let dir = TestDir::new("rdi-upload-drafts");
    let drafts = Drafts::new(dir.join("root"));
    let id = drafts.insert_read(loaded("forge", vec![mr("a", 1, Side::Both)], false), &dir.join("cache"));
    drafts.set_phase(Phase::Reading { task: "t".into(), server: true });
    drafts.age(IDLE + Duration::from_secs(1));
    assert!(drafts.with(&id, |_| Ok(())).is_ok(), "a read in progress keeps the draft");
    // The read ended: the draft is ready again, then idle for too long.
    drafts.finish_for_test(&id, true);
    drafts.age(IDLE + Duration::from_secs(1));
    assert_eq!(drafts.with(&id, |_| Ok(())).unwrap_err().to_string(), GONE);
    assert!(!drafts.work_dir_of(&id).exists());
}

#[test]
fn a_draft_still_reading_refuses_side_changes() {
    let dir = TestDir::new("rdi-upload-drafts");
    let drafts = Drafts::new(dir.join("root"));
    let id = drafts.insert_read(loaded("forge", vec![mr("a", 1, Side::Both)], false), &dir.join("cache"));
    drafts.set_phase(Phase::Reading { task: "t".into(), server: true });
    assert!(drafts.with(&id, |d| d.set_sides(&[("a".into(), Side::Client)])).unwrap_err().to_string().contains("正在读取"));
    drafts.set_phase(Phase::Failed("坏包".into()));
    let error = drafts.with(&id, |d| d.set_sides(&[("a".into(), Side::Client)])).unwrap_err().to_string();
    assert!(error.contains("读取整合包失败：坏包"), "{error}");
}

#[test]
fn a_new_version_keeps_the_pack_fields() {
    let mut form = super::draft::Draft { name: "原名".into(), version: "1".into(), info: "原来的简介内容".into(), ..Default::default() };
    let change = super::FormChange { name: Some("新名".into()), version: Some(" 2.0 beta ".into()), info: Some("x".into()), ..Default::default() };
    change.apply(&mut form, true);
    assert_eq!((form.name.as_str(), form.version.as_str(), form.info.as_str()), ("原名", "2.0_beta", "原来的简介内容"));
    change.apply(&mut form, false);
    assert_eq!((form.name.as_str(), form.info.as_str()), ("新名", "x"));
    assert!(super::FormChange::default().is_empty());
}
