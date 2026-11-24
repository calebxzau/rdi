use super::{
    install::{self, Outcome, Placement},
    modrinth,
    records::{self, ContentType, Record},
    status, *,
};
use crate::catalog::models::{ContentType as CatalogKind, Dependency, Project, ProjectRef};
use crate::digests::Digest;
use serde_json::json;
use std::collections::{BTreeMap, HashMap, HashSet};

fn temp(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rdi-content-{name}-{}", uuid::Uuid::now_v7()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn file(platform: Platform, project: &str, id: &str, published: &str, channel: Channel) -> File {
    File {
        reference: FileRef { platform, id: id.into() },
        project: ProjectRef { platform, id: project.into() },
        name: format!("{id}.jar"),
        version: Some(format!("v{id}")),
        filename: format!("{id}.jar"),
        channel,
        published: published.into(),
        size: 2048,
        minecraft: vec!["1.20.1".into()],
        loaders: vec!["forge".into()],
        environment: Environment::default(),
        digests: vec![
            Digest::new(Algorithm::Sha1, "a9993e364706816aba3e25717850c26c9cd0d89d").unwrap(),
            Digest::new(Algorithm::Murmur2, "12345").unwrap(),
        ],
        dependencies: vec![],
        url: Some("https://cdn.modrinth.com/a.jar".into()),
    }
}

fn catalog_mod(identity: &str, project: &str) -> Mod {
    let source = Project {
        reference: ProjectRef { platform: Platform::Modrinth, id: project.into() },
        slug: "jei".into(),
        name: "JEI".into(),
        summary: "s".into(),
        icon: None,
        downloads: 1,
        updated: String::new(),
        environment: Environment { client: Requirement::Required, server: Requirement::Unsupported },
        kind: CatalogKind::Mod,
    };
    Mod {
        identity: identity.into(),
        primary: source.reference.clone(),
        sources: vec![source],
        name: "Just Enough Items".into(),
        name_cn: Some(" 物品管理器 ".into()),
        summary: String::new(),
        icons: vec![],
        downloads: 1,
        updated: String::new(),
        environment: Environment::default(),
    }
}

#[test]
fn readable_text_drops_tags_and_shortens() {
    assert_eq!(readable("<p>Fix&amp;add</p><br/>more<IFRAME src=x>video</iframe>  end"), "Fix&add more end");
    assert_eq!(readable("a<br>b"), "a b");
    assert_eq!(readable(&"字".repeat(400)).chars().count(), 350);
    assert_eq!(readable("   "), "");
}

#[test]
fn counts_dates_and_names() {
    assert_eq!(separated_count(0), "0");
    assert_eq!(separated_count(1234567), "1,234,567");
    assert_eq!(compact_cn(9999), "9999");
    assert_eq!(compact_cn(12000), "1.20万");
    assert_eq!(compact_cn(345_000_000), "3.45亿");
    assert_eq!(display_date("not a date"), "not a date");
    assert_eq!(display_date("2025-03-01T00:00:00Z").len(), 10);
    let m = catalog_mod("mcmod:2489", "AANobbMI");
    assert_eq!(display_name(&m), "物品管理器");
    assert_eq!(mcmod_id(&m), Some("2489"));
    assert_eq!(mcmod_id(&catalog_mod("modrinth:AANobbMI", "AANobbMI")), None);
}

#[test]
fn files_sort_beta_first_and_latest_is_newest() {
    let mut files = vec![
        file(Platform::Modrinth, "p", "1", "2024-01-01T00:00:00Z", Channel::Release),
        file(Platform::Modrinth, "p", "2", "2025-01-01T00:00:00Z", Channel::Beta),
        file(Platform::Modrinth, "p", "3", "2024-06-01T00:00:00+08:00", Channel::Release),
    ];
    sort_files(&mut files);
    assert_eq!(files.iter().map(|f| f.reference.id.as_str()).collect::<Vec<_>>(), ["2", "1", "3"]);
    assert_eq!(latest(&files).unwrap().reference.id, "2");
    assert_eq!(file_label(&files[0]), "v2");
}

#[test]
fn required_refs_follow_required_edges_only() {
    let a = file(Platform::Modrinth, "a", "fa", "", Channel::Release);
    let b = file(Platform::Modrinth, "b", "fb", "", Channel::Release);
    let c = file(Platform::Modrinth, "c", "fc", "", Channel::Release);
    let dep = |target, requirement| Dependency { target, requirement };
    let graph = DependencyGraph {
        roots: vec![a.reference.clone()],
        nodes: [(a.reference.clone(), (a.clone(), 0)), (b.reference.clone(), (b.clone(), 1)), (c.reference.clone(), (c.clone(), 2))].into_iter().collect::<BTreeMap<_, _>>(),
        edges: vec![
            (a.reference.clone(), dep(DependencyTarget::Project(b.project.clone()), DependencyRequirement::Required)),
            (b.reference.clone(), dep(DependencyTarget::File(c.reference.clone()), DependencyRequirement::Required)),
            (a.reference.clone(), dep(DependencyTarget::File(FileRef { platform: Platform::Modrinth, id: "opt".into() }), DependencyRequirement::Optional)),
        ],
        unresolved: vec![],
        cycles: vec![],
    };
    let ids: Vec<String> = required_refs(&graph, &a.reference).into_iter().map(|r| r.id).collect();
    assert_eq!(ids, ["fa", "fb", "fc"]);
}

#[test]
fn legacy_mod_uses_the_platform_hash_and_side() {
    let m = catalog_mod("mcmod:1", "AANobbMI");
    let mut f = file(Platform::Modrinth, "AANobbMI", "abc", "", Channel::Release);
    let legacy = to_legacy(&m, &f, "https://cdn.modrinth.com/a.jar").unwrap();
    assert_eq!((legacy.platform.as_str(), legacy.slug.as_str(), legacy.file_id.as_str()), ("mr", "jei", "abc"));
    assert_eq!(legacy.hash, "a9993e364706816aba3e25717850c26c9cd0d89d");
    assert_eq!(legacy.side, Side::Client, "file Unknown falls back to the source environment");
    assert_eq!(legacy.download_urls, ["https://cdn.modrinth.com/a.jar"]);
    f.reference.platform = Platform::CurseForge;
    assert_eq!(to_legacy(&m, &f, "u").unwrap().hash, "12345");
    f.digests.clear();
    assert!(to_legacy(&m, &f, "u").is_err());
    assert_eq!(legacy_side(Environment { client: Requirement::Unsupported, server: Requirement::Required }), Side::Server);
    assert_eq!(legacy_side(Environment::default()), Side::Both);
}

#[test]
fn room_version_check() {
    let mut f = file(Platform::Modrinth, "p", "1", "", Channel::Release);
    assert!(check_room_version("1.20.1", &f).is_ok());
    assert_eq!(check_room_version("1.21.1", &f).unwrap_err().to_string(), "目标MC版本与当前模组版本不匹配");
    f.minecraft.clear();
    assert!(check_room_version("1.21.1", &f).is_ok());
}

#[test]
fn targets_describe_themselves() {
    let local = Target::Local { version_id: "local_x".into(), name: "包".into(), version: "1.20.1".into(), dir: PathBuf::from("x"), minecraft: "1.20.1".into(), loader: "forge".into() };
    assert_eq!(local.install_line(), "安装到整合包：包 1.20.1");
    assert!(local.check().is_ok());
    let room = Target::Room { id: "r".into(), name: "房间".into(), minecraft: "1.7.10".into(), loader: "forge".into() };
    assert_eq!(room.install_line(), "安装到房间：房间");
    assert_eq!(room.check().unwrap_err().to_string(), "1.7.10 forge的整合包暂不支持模组库");
    assert!(room.local().is_none());
    assert_eq!(Kind::parse("shaders").map(Kind::dir), Some("shaderpacks"));
}

#[test]
fn records_read_kotlin_json_and_replace_by_project_key() {
    let dir = temp("records");
    assert!(records::read(&dir).unwrap().is_empty());
    std::fs::create_dir_all(dir.join(".rdi")).unwrap();
    std::fs::write(
        records::path(&dir),
        json!({"entries":[
            {"type":"MOD","source":"mr","projectId":"p","versionId":"v1","fileName":"a.jar","hash":"h","installedAt":1},
            {"type":"SHADER_PACK","source":"modrinth","projectId":"s","versionId":"v","fileName":"s.zip"}
        ]})
        .to_string(),
    )
    .unwrap();
    let read = records::read(&dir).unwrap();
    assert_eq!(read.len(), 2);
    assert_eq!(read[1].hash, None);
    assert_eq!(read[0].project_key(), "MOD:mr:p");
    let new = Record { kind: ContentType::Mod, source: "mr".into(), project_id: "p".into(), version_id: "v2".into(), file_name: "b.jar".into(), hash: None, installed_at: 5 };
    records::replace(&dir, std::slice::from_ref(&new)).unwrap();
    let after = records::read(&dir).unwrap();
    assert_eq!(after.len(), 2);
    assert_eq!(records::find(&after, ContentType::Mod, "mr", "p").unwrap().version_id, "v2");
    let raw: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(records::path(&dir)).unwrap()).unwrap();
    assert_eq!(raw["entries"][1]["type"], "MOD");
    assert_eq!(raw["entries"][1]["fileName"], "b.jar");
    std::fs::write(records::path(&dir), "{").unwrap();
    assert!(records::read(&dir).is_err());
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn status_follows_task_then_records_then_running() {
    let f = file(Platform::Modrinth, "p", "v2", "", Channel::Release);
    let rec = |version: &str| Record { kind: ContentType::Mod, source: "mr".into(), project_id: "p".into(), version_id: version.into(), file_name: "a.jar".into(), hash: None, installed_at: 0 };
    let none = HashMap::new();
    assert_eq!(status::file_status(&f, None, &[], &none, false), status::NOT_INSTALLED);
    assert_eq!(status::file_status(&f, Some(1), &[], &none, false), status::DOWNLOADING);
    assert_eq!(status::file_status(&f, Some(3), &[], &none, false), status::FAILED);
    assert_eq!(status::file_status(&f, Some(2), &[rec("v2")], &none, false), status::INSTALLED);
    assert_eq!(status::file_status(&f, None, &[rec("v1")], &none, false), status::UPDATABLE);
    assert_eq!(status::file_status(&f, None, &[rec("v1")], &none, true), status::RUNNING);
    assert_eq!(status::file_status(&f, None, &[rec("v2")], &none, true), status::INSTALLED);
    assert_eq!(status::file_status(&f, None, &[], &none, true), status::NOT_INSTALLED, "an unmanaged mod can still be added");
    let health = HashMap::from([("MOD:mr:p".to_owned(), status::MISSING)]);
    assert_eq!(status::file_status(&f, None, &[rec("v2")], &health, false), status::MISSING);
    assert!(status::installable(status::UPDATABLE) && status::installable(status::MISSING));
    assert!(!status::installable(status::INSTALLED) && !status::installable(status::DOWNLOADING) && !status::installable(status::RUNNING));
    assert_eq!(status::mod_task_key("local_a", "p"), "catalog-mod-local:local_a:p");
    assert_eq!(status::resource_task_key("shaderpacks", "local_a", "p"), "modrinth-project-file:shaderpacks:local_a:p");
}

#[test]
fn health_checks_missing_and_modified_jars() {
    let dir = temp("health");
    std::fs::create_dir_all(dir.join("mods")).unwrap();
    std::fs::write(dir.join("mods").join("good.jar"), b"abc").unwrap();
    std::fs::write(dir.join("mods").join("bad.jar"), b"abd").unwrap();
    let rec = |project: &str, name: &str| Record {
        kind: ContentType::Mod,
        source: "mr".into(),
        project_id: project.into(),
        version_id: "v".into(),
        file_name: name.into(),
        hash: Some("A9993E364706816ABA3E25717850C26C9CD0D89D".into()),
        installed_at: 0,
    };
    let list = [rec("good", "good.jar"), rec("bad", "bad.jar"), rec("gone", "gone.jar"), rec("other", "x.jar")];
    let projects: HashSet<String> = ["good", "bad", "gone"].into_iter().map(String::from).collect();
    let health = status::health(&dir, &list, &projects);
    assert_eq!(health.len(), 2);
    assert_eq!(health["MOD:mr:bad"], status::MODIFIED);
    assert_eq!(health["MOD:mr:gone"], status::MISSING);
    std::fs::remove_dir_all(dir).unwrap();
}

const ABC_SHA1: &str = "a9993e364706816aba3e25717850c26c9cd0d89d";

fn placement(version: &str, name: &str) -> Placement {
    Placement {
        kind: ContentType::ResourcePack,
        source: "modrinth".into(),
        project_id: "proj".into(),
        version_id: version.into(),
        file_name: name.into(),
        hash: ABC_SHA1.into(),
        algorithm: Algorithm::Sha1,
        dir: "resourcepacks",
        backup: "proj.backup".into(),
        label: "资源包",
    }
}
fn write_abc(name: &'static str) -> impl FnOnce(&std::path::Path) -> anyhow::Result<()> {
    move |staging| Ok(std::fs::write(staging.join(name), b"abc")?)
}

#[test]
fn place_installs_records_and_skips_a_matching_file() {
    let dir = temp("place");
    assert_eq!(install::place(&dir, &placement("v1", "a.zip"), false, write_abc("a.zip")).unwrap(), Outcome::Installed);
    assert_eq!(std::fs::read(dir.join("resourcepacks/a.zip")).unwrap(), b"abc");
    let rec = records::read(&dir).unwrap();
    assert_eq!((rec[0].version_id.as_str(), rec[0].hash.as_deref()), ("v1", Some(ABC_SHA1)));
    let fetched = std::cell::Cell::new(false);
    let outcome = install::place(&dir, &placement("v1", "a.zip"), true, |_| {
        fetched.set(true);
        Ok(())
    })
    .unwrap();
    assert_eq!(outcome, Outcome::AlreadyThere);
    assert!(!fetched.get());
    let staging = std::fs::read_dir(dir.join(".rdi/installing")).unwrap().count();
    assert_eq!(staging, 0, "staging folders are removed");
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn place_updates_replaces_the_old_file_and_refuses_while_running() {
    let dir = temp("update");
    install::place(&dir, &placement("v1", "a.zip"), false, write_abc("a.zip")).unwrap();
    let err = install::place(&dir, &placement("v2", "b.zip"), true, write_abc("b.zip")).unwrap_err();
    assert_eq!(err.to_string(), "当前整合包正在运行，不能更新资源包");
    install::place(&dir, &placement("v2", "b.zip"), false, write_abc("b.zip")).unwrap();
    assert!(!dir.join("resourcepacks/a.zip").exists());
    assert!(dir.join("resourcepacks/b.zip").is_file());
    assert!(!dir.join(".rdi/installing/proj.backup").exists());
    assert_eq!(records::read(&dir).unwrap().len(), 1);
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn place_refuses_unmanaged_files_and_rolls_back_a_failed_download() {
    let dir = temp("conflict");
    std::fs::create_dir_all(dir.join("resourcepacks")).unwrap();
    std::fs::write(dir.join("resourcepacks/a.zip"), b"mine").unwrap();
    let err = install::place(&dir, &placement("v1", "a.zip"), false, write_abc("a.zip")).unwrap_err();
    assert_eq!(err.to_string(), "目标文件已存在且不属于RDI管理: a.zip");
    assert_eq!(std::fs::read(dir.join("resourcepacks/a.zip")).unwrap(), b"mine");
    install::place(&dir, &placement("v1", "b.zip"), false, write_abc("b.zip")).unwrap();
    let err = install::place(&dir, &placement("v2", "c.zip"), false, |_| anyhow::bail!("网络错误")).unwrap_err();
    assert_eq!(err.to_string(), "网络错误");
    assert!(dir.join("resourcepacks/b.zip").is_file(), "the old file stays");
    assert!(!dir.join("resourcepacks/c.zip").exists());
    assert_eq!(records::read(&dir).unwrap()[0].version_id, "v1");
    // A broken records file stops the swap after the move; the old file comes back.
    std::fs::write(records::path(&dir), "{").unwrap();
    assert!(install::place(&dir, &placement("v3", "d.zip"), false, write_abc("d.zip")).is_err());
    assert!(dir.join("resourcepacks/b.zip").is_file());
    assert!(!dir.join("resourcepacks/d.zip").exists());
    std::fs::remove_dir_all(&dir).unwrap();
    assert_eq!(install::place(&dir, &placement("v1", "a.zip"), false, write_abc("a.zip")).unwrap_err().to_string(), "目标整合包已不存在");
}

#[test]
fn safe_names() {
    assert_eq!(install::safe_file_name("a\\b/c.zip"), "c.zip");
    assert_eq!(install::safe_file_name("dir/"), "modrinth-project-file.zip");
    assert_eq!(install::safe_segment("a b/c:d"), "a_b_c_d");
}

#[test]
fn modrinth_search_and_project_parsing() {
    let q = modrinth::search_query("shader", " bsl ", Some("1.20.1"), 40);
    assert_eq!(q[0], ("query".into(), "bsl".into()));
    assert_eq!(q[1].1, r#"[["project_type:shader"],["versions:1.20.1"]]"#);
    assert!(q.contains(&("index".into(), "downloads".into())) && q.contains(&("offset".into(), "40".into())));
    assert!(modrinth::search_query("shader", "", None, 0).iter().all(|(k, _)| k != "query"));
    let page = modrinth::parse_search(
        &json!({"hits":[
            {"project_id":"a","project_type":"shader","title":"BSL","follows":1200,"icon_url":"i","gallery":["g1"]},
            {"project_id":"b","project_type":"mod","title":"x"},
            {"project_id":"c","project_type":"shader","title":"C","featured_gallery":"f","gallery":["g"]}
        ],"offset":0,"limit":20,"total_hits":21}),
        "shader",
    )
    .unwrap();
    assert!(page.has_more);
    assert_eq!(page.cards.len(), 2);
    assert_eq!((page.cards[0].banner.as_deref(), page.cards[1].banner.as_deref()), (Some("g1"), Some("f")));
    assert_eq!(page.cards[0].follows, 1200);
    let project = modrinth::parse_project(&json!({"id":"P","slug":"bsl","title":"BSL","gallery":[
        {"url":"u2","featured":false,"ordering":1},{"url":"u3","featured":false,"ordering":0,"title":"t"},{"url":"u1","raw_url":"r1","featured":true,"ordering":5}
    ]}))
    .unwrap();
    assert_eq!(project.gallery.iter().map(|g| g.url.as_str()).collect::<Vec<_>>(), ["u1", "u3", "u2"]);
    assert_eq!(project.source_url, "https://modrinth.com/mod/bsl");
    let versions = modrinth::parse_versions(&json!([
        {"id":"1","version_number":"1.0","version_type":"release","game_versions":["1.20.1"],"date_published":"2024-01-01T00:00:00Z",
         "files":[{"url":"u","filename":"a.zip","primary":false,"size":3,"hashes":{"sha1":"ABC"}},{"url":"p","filename":"p.zip","primary":true,"hashes":{}}]},
        {"id":"2","version_number":"2.0b","version_type":"beta","game_versions":["1.20.1"],"date_published":"2024-02-01T00:00:00Z","files":[]},
        {"id":"3","version_number":"3.0a","version_type":"alpha","game_versions":["1.20.1"],"date_published":"2024-03-01T00:00:00Z","files":[]},
        {"id":"4","version_number":"4","version_type":"release","game_versions":["1.21.1"],"date_published":"2024-04-01T00:00:00Z","files":[]}
    ]))
    .unwrap();
    assert_eq!(versions.iter().map(|v| v.id.as_str()).collect::<Vec<_>>(), ["4", "3", "2", "1"]);
    let one = versions.iter().find(|v| v.id == "1").unwrap().file.clone().unwrap();
    assert_eq!((one.file_name.as_str(), one.sha1), ("p.zip", None));
    let shown: Vec<&str> = modrinth::compatible(&versions, "1.20.1", false).iter().map(|v| v.id.as_str()).collect();
    assert_eq!(shown, ["2", "1"]);
    assert_eq!(modrinth::compatible(&versions, "1.20.1", true).len(), 3);
}
