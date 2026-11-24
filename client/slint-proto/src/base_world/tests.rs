//! The 地图模板 rules (ports of the Kotlin validators and task keys) and the upload task against a
//! fake server.
use super::api::*;
use super::upload::{Env, Request, run};
use super::*;
use std::collections::HashMap;
use std::io::Read;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

struct Temp(PathBuf);
impl Temp {
    fn new(name: &str) -> Self {
        let path = std::env::temp_dir().join(format!("rdi-baseworld-{name}-{}", uuid::Uuid::now_v7()));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

#[test]
fn names_follow_the_modpack_name_rules() {
    assert!(validate_name("我的世界").is_ok());
    assert!(validate_name("abc").is_ok());
    assert!(validate_name("a b.c_d-e").is_ok());
    assert_eq!(validate_name("ab").unwrap_err(), "地图模板名称长度需在3~32个字符，当前为2（一个汉字算两个）");
    assert_eq!(validate_name("界").unwrap_err(), "地图模板名称长度需在3~32个字符，当前为2（一个汉字算两个）");
    assert_eq!(validate_name(" abc").unwrap_err(), "地图模板名称只能包含字母、数字、汉字、空格或._-");
    assert_eq!(validate_name("abc ").unwrap_err(), "地图模板名称只能包含字母、数字、汉字、空格或._-");
    assert_eq!(validate_name("ab/c").unwrap_err(), "地图模板名称只能包含字母、数字、汉字、空格或._-");
    assert_eq!(validate_name("").unwrap_err(), "地图模板名称只能包含字母、数字、汉字、空格或._-");
    assert!(validate_name(&"一".repeat(16)).is_ok());
    assert!(validate_name(&"一".repeat(17)).is_err());
}

#[test]
fn level_types_are_namespaced_ids() {
    for ok in ["minecraft:normal", "skyblockbuilder:skyblock", " a.b-c_1:x/y.z_-1 ", "1:2"] {
        assert!(validate_level_type(ok).is_ok(), "{ok}");
    }
    for bad in ["normal", ":x", "x:", "Minecraft:normal", "a:b:c", "a b:c", "a:b c", ""] {
        assert_eq!(validate_level_type(bad).unwrap_err(), "地形类型必须是foo:bar格式", "{bad}");
    }
    assert_eq!(LevelChoice::from_index(2).and_then(LevelChoice::preset), Some("skyblockbuilder:skyblock"));
    assert_eq!(LevelChoice::from_index(3).and_then(LevelChoice::preset), None);
    assert_eq!(LevelChoice::from_index(4), None);
}

#[test]
fn generator_settings_are_blank_or_an_object() {
    assert!(validate_generator_settings("").is_ok());
    assert!(validate_generator_settings("  \n").is_ok());
    assert!(validate_generator_settings(r#"{"layers":[]}"#).is_ok());
    for bad in ["[]", "1", "{", "\"x\""] {
        assert_eq!(validate_generator_settings(bad).unwrap_err(), "生成器设置必须是JSON对象", "{bad}");
    }
    assert_eq!(generator_text(None), "未设置");
    assert_eq!(generator_text(Some("")), "空字符串");
    assert_eq!(generator_text(Some("{}")), "{}");
}

#[test]
fn world_folders_need_a_level_dat() {
    let temp = Temp::new("folder");
    assert_eq!(validate_directory(&temp.0.join("missing")).unwrap_err(), "选择的路径不存在");
    std::fs::write(temp.0.join("file"), b"x").unwrap();
    assert_eq!(validate_directory(&temp.0.join("file")).unwrap_err(), "请选择地图文件夹");
    assert_eq!(validate_directory(&temp.0).unwrap_err(), "不是有效的MC地图文件夹");
    std::fs::write(temp.0.join("level.dat"), b"").unwrap();
    assert_eq!(validate_directory(&temp.0).unwrap_err(), "没有成功读取地图摘要文件");
    std::fs::write(temp.0.join("level.dat"), b"nbt").unwrap();
    assert!(validate_directory(&temp.0).is_ok());
}

#[test]
fn task_keys_match_kotlin() {
    // Kotlin: sha1("名字\u0000minecraft:flat\u0000") for a template without generator settings.
    let key = generated_task_key(" 64e0aa ", " 名字 ", "minecraft:flat", None);
    assert!(key.starts_with("baseworld-generated:64e0aa:"));
    assert_eq!(key, generated_task_key("64e0aa", "名字", "minecraft:flat ", Some("  ")));
    assert_ne!(key, generated_task_key("64e0aa", "名字", "minecraft:flat", Some("{}")));
    let digest = &key["baseworld-generated:64e0aa:".len()..];
    assert_eq!(digest, super::hex_sha1("名字\u{0}minecraft:flat\u{0}".as_bytes()));
    let upload = upload_task_key("64e0aa", std::path::Path::new("/tmp/World"));
    assert!(upload.starts_with("baseworld-upload:64e0aa:"));
    if cfg!(windows) {
        assert_eq!(upload, upload.to_lowercase());
    }
}

#[test]
fn upload_time_reads_the_uuid_v7_timestamp() {
    // 0x019a_1b2c_3d4e ms = 2025-10-22T...; compare with chrono directly.
    let id = "019a1b2c-3d4e-7000-8000-000000000001";
    let expected = chrono::DateTime::from_timestamp_millis(0x019a_1b2c_3d4e)
        .unwrap()
        .with_timezone(&chrono::Local)
        .format("%Y年%m月%d日 %H:%M:%S")
        .to_string();
    assert_eq!(upload_time(id), expected);
    assert_eq!(upload_time("not-a-uuid"), "");
}

#[test]
fn worlds_and_bindings_decode_and_encode_like_kotlin() {
    let world: BaseWorld = serde_json::from_str(
        r#"{"id":"0193d3a4-1111-7000-8000-000000000001","ownerId":"64e0aa00-0000-0000-0000-000000000000","name":"空岛","levelType":"skyblockbuilder:skyblock","generatorSettings":null,"size":12}"#,
    )
    .unwrap();
    assert_eq!((world.name.as_str(), world.size, world.generator_settings), ("空岛", 12, None));
    let dto = CreateDto { name: "n".into(), level_type: "a:b".into(), generator_settings: None, size: 0, generated: true };
    assert_eq!(serde_json::to_string(&dto).unwrap(), r#"{"name":"n","levelType":"a:b","generatorSettings":null,"size":0,"generated":true}"#);
    let binding = Binding { id: world.id.clone(), required: true };
    assert_eq!(serde_json::json!({ "baseWorld": Some(&binding) }).to_string(), format!(r#"{{"baseWorld":{{"id":"{}","required":true}}}}"#, world.id));
    assert_eq!(serde_json::json!({ "baseWorld": None::<&Binding> }).to_string(), r#"{"baseWorld":null}"#);
    let session: SessionVo = serde_json::from_str(r#"{"id":"u","size":5,"partSize":4,"partCount":2,"uploadedParts":[],"expiresAt":1,"status":"Queued"}"#).unwrap();
    assert_eq!(session.status, UploadStatus::Queued);
}

// ---- upload task against a fake server ----

#[derive(Default)]
struct Fake {
    part_size: u64,
    completion: Option<UploadStatus>,
    fail_create_upload: bool,
    created: Vec<CreateDto>,
    session_size: u64,
    session_sha1: String,
    parts: HashMap<u64, Vec<u8>>,
    completed: bool,
    deleted: Vec<String>,
}

struct FakeApi(Mutex<Fake>);

impl WorldApi for FakeApi {
    async fn create(&self, dto: &CreateDto) -> anyhow::Result<BaseWorld> {
        self.0.lock().unwrap().created.push(dto.clone());
        Ok(BaseWorld { id: "world-1".into(), name: dto.name.clone(), level_type: dto.level_type.clone(), ..Default::default() })
    }
    async fn create_upload(&self, world: &str, size: u64, sha1: &str) -> anyhow::Result<SessionVo> {
        assert_eq!(world, "world-1");
        let mut f = self.0.lock().unwrap();
        anyhow::ensure!(!f.fail_create_upload, "服务器拒绝上传");
        f.session_size = size;
        f.session_sha1 = sha1.to_owned();
        let part_count = (size - 1) / f.part_size + 1;
        Ok(SessionVo { id: "upload-1".into(), size, part_size: f.part_size, part_count, uploaded_parts: vec![], status: UploadStatus::Uploading, error_message: None })
    }
    async fn upload_part(&self, world: &str, upload: &str, index: u64, bytes: Vec<u8>, _sha1: String) -> anyhow::Result<()> {
        assert_eq!((world, upload), ("world-1", "upload-1"));
        self.0.lock().unwrap().parts.insert(index, bytes);
        Ok(())
    }
    async fn complete(&self, _world: &str, _upload: &str) -> anyhow::Result<SessionVo> {
        let mut f = self.0.lock().unwrap();
        f.completed = true;
        Ok(SessionVo {
            id: "upload-1".into(),
            size: f.session_size,
            part_size: f.part_size,
            part_count: 0,
            uploaded_parts: vec![],
            status: f.completion.unwrap_or(UploadStatus::Queued),
            error_message: Some("level.dat损坏".into()),
        })
    }
    async fn delete(&self, world: &str) -> anyhow::Result<()> {
        self.0.lock().unwrap().deleted.push(world.to_owned());
        Ok(())
    }
}

fn world(root: &std::path::Path) -> PathBuf {
    let dir = root.join("世界 1");
    std::fs::create_dir_all(dir.join("region")).unwrap();
    std::fs::create_dir_all(dir.join("empty")).unwrap();
    std::fs::write(dir.join("level.dat"), b"level-bytes").unwrap();
    std::fs::write(dir.join("region").join("r.0.0.mca"), vec![7u8; 9000]).unwrap();
    dir
}

fn env(root: &std::path::Path) -> Env {
    let mut env = Env::new(root);
    env.upload.retry_delay = Duration::from_millis(1);
    env
}

fn unpack(bytes: &[u8]) -> Vec<(String, Vec<u8>)> {
    let decoder = zstd::stream::read::Decoder::new(bytes).unwrap();
    let mut archive = tar::Archive::new(decoder);
    let mut out = vec![];
    for entry in archive.entries().unwrap() {
        let mut entry = entry.unwrap();
        let path = entry.path().unwrap().to_string_lossy().into_owned();
        let mut data = vec![];
        entry.read_to_end(&mut data).unwrap();
        out.push((path, data));
    }
    out
}

#[test]
fn a_world_folder_is_packed_uploaded_and_submitted() {
    let temp = Temp::new("upload");
    let source = world(&temp.0);
    let api = Arc::new(FakeApi(Mutex::new(Fake { part_size: 1024, ..Default::default() })));
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let context = crate::tasks::Manager::default().test_context();
    let request = Request::new(Some(source.clone()), "我的地图", " minecraft:normal ", "  ");
    run(api.clone(), &request, &env(&temp.0.join("data")), runtime.handle(), &context).unwrap();

    let f = api.0.lock().unwrap();
    assert_eq!(f.created.len(), 1);
    assert_eq!(f.created[0], CreateDto { name: "我的地图".into(), level_type: "minecraft:normal".into(), generator_settings: None, size: 9011, generated: false });
    assert!(f.completed && f.deleted.is_empty());
    let mut bytes = vec![];
    for index in 0..f.parts.len() as u64 {
        bytes.extend(&f.parts[&index]);
    }
    assert_eq!(bytes.len() as u64, f.session_size);
    assert_eq!(super::hex_sha1(&bytes), f.session_sha1);
    let entries = unpack(&bytes);
    let names: Vec<&str> = entries.iter().map(|(n, _)| n.as_str()).collect();
    assert_eq!(names, ["empty/", "level.dat", "region/", "region/r.0.0.mca"]);
    assert_eq!(entries[1].1, b"level-bytes");
    assert_eq!(entries[3].1, vec![7u8; 9000]);
    // The temp dir is gone.
    let left: Vec<_> = std::fs::read_dir(temp.0.join("data").join("pack-proc").join("baseworld-uploads")).unwrap().collect();
    assert!(left.is_empty());
}

#[test]
fn a_generated_template_is_only_created() {
    let temp = Temp::new("generated");
    let api = Arc::new(FakeApi(Mutex::new(Fake { part_size: 1024, ..Default::default() })));
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let context = crate::tasks::Manager::default().test_context();
    let request = Request::new(None, "平坦世界", "minecraft:flat", r#" {"layers":[]} "#);
    run(api.clone(), &request, &env(&temp.0), runtime.handle(), &context).unwrap();
    let f = api.0.lock().unwrap();
    assert_eq!(f.created, vec![CreateDto {
        name: "平坦世界".into(),
        level_type: "minecraft:flat".into(),
        generator_settings: Some(r#"{"layers":[]}"#.into()),
        size: 0,
        generated: true,
    }]);
    assert!(f.parts.is_empty() && !f.completed);
}

#[test]
fn a_failed_upload_deletes_the_template_it_created() {
    let temp = Temp::new("fail");
    let source = world(&temp.0);
    let api = Arc::new(FakeApi(Mutex::new(Fake { part_size: 1024, fail_create_upload: true, ..Default::default() })));
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let context = crate::tasks::Manager::default().test_context();
    let request = Request::new(Some(source), "我的地图", "minecraft:normal", "");
    let error = run(api.clone(), &request, &env(&temp.0.join("data")), runtime.handle(), &context).unwrap_err();
    assert_eq!(error.to_string(), "服务器拒绝上传");
    assert_eq!(api.0.lock().unwrap().deleted, vec!["world-1".to_owned()]);
}

#[test]
fn a_failed_check_after_submission_keeps_the_template() {
    let temp = Temp::new("rejected");
    let source = world(&temp.0);
    let api = Arc::new(FakeApi(Mutex::new(Fake { part_size: 1024, completion: Some(UploadStatus::Failed), ..Default::default() })));
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let context = crate::tasks::Manager::default().test_context();
    let request = Request::new(Some(source), "我的地图", "minecraft:normal", "");
    let error = run(api.clone(), &request, &env(&temp.0.join("data")), runtime.handle(), &context).unwrap_err();
    assert_eq!(error.to_string(), "level.dat损坏");
    assert!(api.0.lock().unwrap().deleted.is_empty());
}

#[test]
fn folders_without_level_dat_or_with_bad_names_are_refused() {
    let temp = Temp::new("refused");
    let source = world(&temp.0);
    std::fs::remove_file(source.join("level.dat")).unwrap();
    let api = Arc::new(FakeApi(Mutex::new(Fake { part_size: 1024, ..Default::default() })));
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let context = crate::tasks::Manager::default().test_context();
    let request = Request::new(Some(source.clone()), "我的地图", "minecraft:normal", "");
    let error = run(api.clone(), &request, &env(&temp.0.join("data")), runtime.handle(), &context).unwrap_err();
    assert_eq!(error.to_string(), "世界目录缺少非空level.dat");
    assert!(api.0.lock().unwrap().created.is_empty());

    std::fs::write(source.join("level.dat"), b"x").unwrap();
    let mut small = env(&temp.0.join("data"));
    small.max_size = 100;
    let error = run(api.clone(), &request, &small, runtime.handle(), &context).unwrap_err();
    assert!(error.to_string().starts_with("地图文件总大小超过"), "{error}");

    let bad = Request::new(Some(source), "x", "minecraft:normal", "");
    assert!(run(api, &bad, &env(&temp.0.join("data")), runtime.handle(), &context).is_err());
}

/// e3 live check against a running master, without the screens: a generated template, a world
/// folder upload, the owner's list, rename, the version binding (set, then restored) and delete.
/// Run with `--ignored --nocapture` and `RDI_E3_SERVER`, `RDI_E3_USR`, `RDI_E3_PWD` (never
/// printed), `RDI_E3_WORLD` (a world folder), `RDI_E3_WORK` (an empty folder), and optionally `RDI_E3_PACK`
/// and `RDI_E3_VERSION` (a version the account may manage; else its first own pack). The uploaded template is kept when
/// `RDI_E3_KEEP` is set, so a room can be created from it by hand.
#[test]
#[ignore]
fn live_templates_on_a_running_master() {
    let var = |name: &str| std::env::var(name).unwrap_or_else(|_| panic!("{name} is not set"));
    let work = PathBuf::from(var("RDI_E3_WORK"));
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let mut server = crate::api::server::ServerApi::from_dir(var("RDI_E3_SERVER"), &work).unwrap();
    let session = runtime.block_on(server.login(&var("RDI_E3_USR"), &var("RDI_E3_PWD"), None)).unwrap();
    let uid = session.account["_id"].as_str().unwrap().to_owned();
    println!("signed in as {} ({uid})", session.account["name"]);
    let client = runtime.block_on(crate::api::Client::connect(server.clone(), session)).unwrap();
    let api = Arc::new(HttpWorldApi::new(server));
    let tasks = crate::tasks::Manager::default();
    let context = tasks.test_context();
    let env = Env::new(&work);
    let before = runtime.block_on(api.list_mine()).unwrap();
    println!("own templates before: {}", before.len());
    // Leftovers of an earlier run of this test.
    for old in before.iter().filter(|w| w.name.starts_with("e3")) {
        runtime.block_on(api.delete(&old.id)).unwrap();
    }

    let generated = Request::new(None, "e3生成平坦", "minecraft:flat", "");
    run(api.clone(), &generated, &env, runtime.handle(), &context).unwrap();
    let request = Request::new(Some(PathBuf::from(var("RDI_E3_WORLD"))), "e3上传测试", "minecraft:normal", "");
    let started = std::time::Instant::now();
    run(api.clone(), &request, &env, runtime.handle(), &context).unwrap();
    println!("upload finished in {:?}", started.elapsed());
    let mine = runtime.block_on(api.list_mine()).unwrap();
    let flat = mine.iter().find(|w| w.name == "e3生成平坦").expect("generated template listed").clone();
    let uploaded = mine.iter().find(|w| w.name == "e3上传测试").expect("uploaded template listed").clone();
    println!("generated {} size {} type {}", flat.id, flat.size, flat.level_type);
    println!("uploaded {} size {} at {}", uploaded.id, uploaded.size, upload_time(&uploaded.id));

    let renamed = runtime.block_on(api.rename(&flat.id, "e3生成平坦改名")).unwrap();
    assert_eq!(renamed.name, "e3生成平坦改名");
    let ready = runtime.block_on(api.list_ready()).unwrap();
    println!("released templates: {} (generated listed: {})", ready.len(), ready.iter().any(|w| w.id == flat.id));

    // The first own pack with a version, unless RDI_E3_PACK names one.
    let pack = match std::env::var("RDI_E3_PACK") {
        Ok(pack) => pack,
        Err(_) => {
            let query = [("mine", "true".to_owned()), ("offset", "0".to_owned()), ("limit", "60".to_owned())];
            let page: serde_json::Value = runtime.block_on(client.get_query("/modpack/search", &query)).unwrap();
            let items = page["items"].as_array().cloned().unwrap_or_default();
            println!("own packs: {:?}", items.iter().map(|i| (i["id"].as_str().unwrap_or(""), i["name"].as_str().unwrap_or(""))).collect::<Vec<_>>());
            items.first().and_then(|i| i["id"].as_str()).map(str::to_owned).unwrap_or_default()
        }
    };
    let version_of = |detail: &serde_json::Value, name: Option<&str>| -> Option<serde_json::Value> {
        detail["versions"].as_array()?.iter().find(|v| name.is_none_or(|n| v["name"] == n)).cloned()
    };
    if pack.is_empty() {
        println!("no own pack: binding not checked");
    } else {
        let detail: serde_json::Value = runtime.block_on(client.get(&format!("/modpack/{pack}/detail"))).unwrap();
        let wanted = std::env::var("RDI_E3_VERSION").ok();
        let first = version_of(&detail, wanted.as_deref()).expect("the pack has the version");
        let version = first["name"].as_str().unwrap().to_owned();
        let original = first["baseWorld"].clone();
        println!("binding of {pack} {version} before: {original}");
        let binding = Binding { id: flat.id.clone(), required: true };
        runtime.block_on(set_version_binding(&client, &pack, &version, Some(&binding))).unwrap();
        let detail: serde_json::Value = runtime.block_on(client.get(&format!("/modpack/{pack}/detail"))).unwrap();
        let now = version_of(&detail, Some(&version)).unwrap()["baseWorld"].clone();
        println!("binding after: {now}");
        assert_eq!(now["id"], flat.id.as_str());
        assert_eq!(now["required"], true);
        let restore = serde_json::from_value::<Option<Binding>>(original).unwrap();
        runtime.block_on(set_version_binding(&client, &pack, &version, restore.as_ref())).unwrap();
        let detail: serde_json::Value = runtime.block_on(client.get(&format!("/modpack/{pack}/detail"))).unwrap();
        println!("binding restored: {}", version_of(&detail, Some(&version)).unwrap()["baseWorld"]);
    }

    runtime.block_on(api.delete(&flat.id)).unwrap();
    if std::env::var_os("RDI_E3_KEEP").is_none() {
        runtime.block_on(api.delete(&uploaded.id)).unwrap();
    }
    let after = runtime.block_on(api.list_mine()).unwrap();
    println!("own templates after: {}", after.len());
    assert!(after.iter().all(|w| w.id != flat.id));
}
