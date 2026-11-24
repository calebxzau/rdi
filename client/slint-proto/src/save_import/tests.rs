//! Ports of Kotlin `HostSaveImportMarkingServiceTest`, plus the record file, the import task
//! against a fake server and the marking launch arguments.
use super::api::*;
use super::marking::*;
use super::service::*;
use super::upload;
use rdi_anvilrw::compress::gzip;
use rdi_anvilrw::{NbtDocument, SaveSourceLock};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use uuid::Uuid;

const OWNER: Uuid = uuid::uuid!("00000000-0000-300c-9be5-0017dec2993d");
const RDI: Uuid = uuid::uuid!("00112233-4455-6677-8899-aabb00000000");
const FRIEND: Uuid = uuid::uuid!("1512ef02-2b79-4494-a4e9-e35cd8e03923");

struct Temp(PathBuf);
impl Temp {
    fn new(name: &str) -> Self {
        let path = std::env::temp_dir().join(format!("rdi-save-import-{name}-{}", Uuid::now_v7()));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn service(root: &Path) -> MarkingService {
    MarkingService::with_file(root.join("records.json"), || 42)
}

fn never() -> rdi_anvilrw::Result<()> {
    Ok(())
}

fn copy(service: &MarkingService, source: &Path, saves: &Path, selected: Option<Uuid>) -> anyhow::Result<MarkingRecord> {
    service.create_copy(source, "host", saves, RDI, selected, &never, &mut |_, _| {})
}

// ---- minimal NBT writer ----

#[derive(Default)]
struct Nbt(Vec<u8>);
impl Nbt {
    fn utf(&mut self, s: &str) {
        self.0.extend((s.len() as u16).to_be_bytes());
        self.0.extend(s.as_bytes());
    }
    fn named(&mut self, kind: u8, name: &str) -> &mut Self {
        self.0.push(kind);
        self.utf(name);
        self
    }
    fn string(&mut self, name: &str, value: &str) -> &mut Self {
        self.named(8, name).utf(value);
        self
    }
    fn int(&mut self, name: &str, value: i32) -> &mut Self {
        self.named(3, name).0.extend(value.to_be_bytes());
        self
    }
    fn compound(&mut self, name: &str, body: impl FnOnce(&mut Self)) -> &mut Self {
        self.named(10, name);
        body(self);
        self.0.push(0);
        self
    }
    fn player(&mut self, uuid: Uuid, x: f64, z: f64) -> &mut Self {
        self.named(11, "UUID").0.extend(4i32.to_be_bytes());
        self.0.extend(uuid.as_bytes());
        self.named(9, "Pos").0.push(6);
        self.0.extend(3i32.to_be_bytes());
        for v in [x, 64.0, z] {
            self.0.extend(v.to_be_bytes());
        }
        self.string("Dimension", "minecraft:overworld")
    }
}

fn nbt(body: impl FnOnce(&mut Nbt)) -> Vec<u8> {
    let mut n = Nbt::default();
    n.named(10, "");
    body(&mut n);
    n.0.push(0);
    n.0
}

fn level_dat(host: Option<Uuid>, x: f64, z: f64) -> Vec<u8> {
    gzip(&nbt(|n| {
        n.compound("Data", |d| {
            d.compound("Version", |v| {
                v.string("Name", "1.20.1");
            });
            if let Some(host) = host {
                d.compound("Player", |p| {
                    p.player(host, x, z);
                });
            }
        });
    }))
}

fn sync_list(chunks: &[(&str, i32, i32)]) -> Vec<u8> {
    sync_list_by(OWNER, chunks)
}

fn sync_list_by(owner: Uuid, chunks: &[(&str, i32, i32)]) -> Vec<u8> {
    gzip(&nbt(|n| {
        n.compound("data", |d| {
            d.named(9, "chunks").0.push(10);
            d.0.extend((chunks.len() as i32).to_be_bytes());
            for (dimension, x, z) in chunks {
                d.string("dimension", dimension).int("x", *x).int("z", *z).string("owner", &owner.to_string());
                d.0.push(0);
            }
        });
    }))
}

/// A region file whose chunks have the given type bytes; only headers matter here.
fn region(types: &[u8]) -> Vec<u8> {
    let mut bytes = vec![0u8; 8192 + types.len() * 4096];
    for (index, kind) in types.iter().enumerate() {
        let sector = 2 + index;
        bytes[index * 4..index * 4 + 4].copy_from_slice(&(((sector as u32) << 8) | 1).to_be_bytes());
        bytes[sector * 4096..sector * 4096 + 4].copy_from_slice(&2u32.to_be_bytes());
        bytes[sector * 4096 + 4] = *kind;
    }
    bytes
}

/// A region file with one real zlib chunk at 0,0.
fn generated_region() -> Vec<u8> {
    let data = rdi_anvilrw::compress::compress_chunk(2, &nbt(|n| {
        n.int("DataVersion", 3465).int("xPos", 0).int("zPos", 0);
    }))
    .unwrap();
    let mut bytes = vec![0u8; 8192 + (data.len() + 5).div_ceil(4096) * 4096];
    let sectors = (data.len() + 5).div_ceil(4096) as u32;
    bytes[0..4].copy_from_slice(&((2u32 << 8) | sectors).to_be_bytes());
    bytes[8192..8196].copy_from_slice(&(data.len() as u32 + 1).to_be_bytes());
    bytes[8196] = 2;
    bytes[8197..8197 + data.len()].copy_from_slice(&data);
    bytes
}

fn save(root: &Path, name: &str, host: Option<Uuid>) -> PathBuf {
    let save = root.join("source").join(name);
    std::fs::create_dir_all(save.join("region")).unwrap();
    std::fs::write(save.join("level.dat"), level_dat(host, 8.0, 8.0)).unwrap();
    std::fs::write(save.join("region/r.0.0.mca"), vec![0u8; 8192]).unwrap();
    save
}

fn entries(dir: &Path) -> usize {
    std::fs::read_dir(dir).map(|d| d.count()).unwrap_or(0)
}

#[test]
fn copies_next_to_earlier_copies_and_remembers_them() {
    let t = Temp::new("copies");
    let s = service(&t.0);
    let saves = t.0.join("pack/saves");
    let source = save(&t.0, "新的世界", Some(OWNER));

    let first = copy(&s, &source, &saves, None).unwrap();
    let second = copy(&s, &source, &saves, None).unwrap();

    assert_eq!(first.copy_folder_name(), "新的世界-RDI导入");
    assert_eq!(second.copy_folder_name(), "新的世界-RDI导入-2");
    assert!(first.copy().join("region/r.0.0.mca").is_file());
    drop(SaveSourceLock::acquire(&first.copy()).unwrap()); // Preparation releases its lock before publication.
    assert_eq!(first.launch_uuid, OWNER.to_string(), "the save's owner UUID is used for the launch");
    assert_eq!(first.created_at, 42);
    assert_eq!(s.find_reusable(&source, "host").unwrap(), Some(second.clone()));
    assert_eq!(s.find_reusable(&source, "other-host").unwrap(), None);

    s.delete(&second).unwrap();
    assert!(!second.copy().exists());
    assert_eq!(s.find_reusable(&source, "host").unwrap(), Some(first));
}

#[test]
fn a_save_without_owner_launches_as_the_rdi_account() {
    let t = Temp::new("no-owner");
    let s = service(&t.0);
    let record = copy(&s, &save(&t.0, "w", None), &t.0.join("saves"), None).unwrap();
    assert_eq!(record.launch_uuid, RDI.to_string());
}

#[test]
fn a_copy_opened_normally_is_rejected() {
    let t = Temp::new("opened");
    let s = service(&t.0);
    let record = copy(&s, &save(&t.0, "w", Some(OWNER)), &t.0.join("saves"), None).unwrap();
    s.check_identity(&record).unwrap();
    std::fs::write(record.copy().join("level.dat"), level_dat(Some(RDI), 8.0, 8.0)).unwrap();
    assert_eq!(s.check_identity(&record).unwrap_err().to_string(), "副本中的角色身份发生变化，请重新创建副本");
}

#[test]
fn summary_counts_marks_and_players_outside() {
    let t = Temp::new("summary");
    let s = service(&t.0);
    let record = copy(&s, &save(&t.0, "w", Some(OWNER)), &t.0.join("saves"), None).unwrap();
    std::fs::create_dir_all(record.copy().join("data")).unwrap();
    std::fs::write(record.copy().join("data/rdi_sync_chunks.dat"), sync_list(&[("minecraft:overworld", 0, 0), ("minecraft:the_nether", 3, 3)])).unwrap();
    std::fs::create_dir_all(record.copy().join("playerdata")).unwrap();
    std::fs::write(record.copy().join(format!("playerdata/{FRIEND}.dat")), gzip(&nbt(|n| {
        n.player(FRIEND, 100.0, 100.0);
    })))
    .unwrap();

    let summary = s.summary(&record).unwrap();

    assert_eq!(summary.marked.len(), 2);
    assert_eq!(summary.marked_by_dimension, vec![("minecraft:overworld".to_owned(), 1), ("minecraft:the_nether".to_owned(), 1)]);
    assert_eq!(summary.present_count, 0, "the region file has no generated chunks");
    assert_eq!(summary.players_outside, vec![FRIEND], "the owner stands in the kept chunk 0,0");

    // A generated chunk counts as present.
    std::fs::write(record.copy().join("region/r.0.0.mca"), region(&[2])).unwrap();
    assert_eq!(s.summary(&record).unwrap().present_count, 1);
}

#[test]
fn copies_with_unreadable_chunk_compression_are_refused() {
    let t = Temp::new("readable");
    let s = service(&t.0);
    let record = copy(&s, &save(&t.0, "w", Some(OWNER)), &t.0.join("saves"), None).unwrap();
    std::fs::write(record.copy().join("region/r.0.0.mca"), region(&[2, 8, 8 | 0x80])).unwrap();
    std::fs::create_dir_all(record.copy().join("data/somemod")).unwrap();
    std::fs::write(record.copy().join("data/somemod/r.0.0.mca"), region(&[4])).unwrap();
    s.check_readable(&record).unwrap();

    std::fs::create_dir_all(record.copy().join("DIM-1/region")).unwrap();
    std::fs::write(record.copy().join("DIM-1/region/r.0.0.mca"), region(&[4, 4, 127, 9])).unwrap();
    assert_eq!(
        s.check_readable(&record).unwrap_err().to_string(),
        "存档中有4个区块使用游戏无法读取的压缩方式（LZ4、编号9、自定义压缩），进入游戏后会被重新生成并覆盖，无法安全标记"
    );
}

#[test]
fn selecting_another_player_preserves_owner_and_original_bytes() {
    let t = Temp::new("select");
    let s = service(&t.0);
    let source = save(&t.0, "w", Some(OWNER));
    let original = std::fs::read(source.join("level.dat")).unwrap();
    std::fs::write(source.join("level.dat_old"), &original).unwrap();
    std::fs::create_dir_all(source.join("playerdata")).unwrap();
    let stale_owner = gzip(&nbt(|n| {
        n.player(OWNER, 999.0, 999.0);
    }));
    let friend_data = gzip(&nbt(|n| {
        n.player(FRIEND, 128.0, -32.0);
    }));
    std::fs::write(source.join(format!("playerdata/{OWNER}.dat")), &stale_owner).unwrap();
    std::fs::write(source.join(format!("playerdata/{FRIEND}.dat")), &friend_data).unwrap();

    let roles = s.players(&source).unwrap();
    assert_eq!(roles[0].player.uuid, OWNER);
    let record = copy(&s, &source, &t.0.join("pack/saves"), Some(FRIEND)).unwrap();

    assert_eq!(record.launch_uuid, FRIEND.to_string());
    assert_eq!(record.original_owner_uuid, Some(OWNER.to_string()));
    assert_eq!(record.selected_player_uuid, Some(FRIEND.to_string()));
    assert_eq!(record.identity_version, 1);
    for file in ["level.dat", "level.dat_old"] {
        let level = NbtDocument::read_level_dat(&record.copy().join(file)).unwrap();
        assert_eq!(level.singleplayer_uuid, Some(FRIEND));
    }
    assert_eq!(std::fs::read(record.copy().join(format!("playerdata/{FRIEND}.dat"))).unwrap(), friend_data);
    assert_eq!(std::fs::read(source.join("level.dat")).unwrap(), original);
    let backup = PathBuf::from(record.identity_backup_path.clone().unwrap());
    assert!(backup.starts_with(t.0.join("pack/rdi-save-import-staging")));
    assert_eq!(std::fs::read(backup.join(format!("playerdata/{OWNER}.dat"))).unwrap(), stale_owner);
    s.check_identity(&record).unwrap();
    assert_eq!(s.find_reusable(&source, "host").unwrap(), Some(record.clone()));
    std::fs::write(record.copy().join(format!("playerdata/{OWNER}.dat")), &friend_data).unwrap();
    assert_eq!(s.check_identity(&record).unwrap_err().to_string(), "副本中的原主人身份发生变化，请重新创建副本");
}

#[test]
fn interrupted_copy_is_never_published_or_reused() {
    let t = Temp::new("interrupted");
    let s = service(&t.0);
    let source = save(&t.0, "w", Some(OWNER));
    let saves = t.0.join("pack/saves");
    let stop = || Err(rdi_anvilrw::RemapError::Cancelled);
    let error = s.create_copy(&source, "host", &saves, RDI, None, &stop, &mut |_, _| {}).unwrap_err();
    assert_eq!(error.to_string(), "任务已取消");
    assert_eq!(s.find_reusable(&source, "host").unwrap(), None);
    assert_eq!(entries(&saves), 0);
    assert_eq!(entries(&t.0.join("pack/rdi-save-import-staging")), 0, "the half copy is removed");
}

#[test]
fn an_invalid_player_never_publishes_a_copy() {
    let t = Temp::new("invalid");
    let s = service(&t.0);
    let source = save(&t.0, "w", Some(OWNER));
    let saves = t.0.join("pack/saves");
    assert_eq!(copy(&s, &source, &saves, Some(FRIEND)).unwrap_err().to_string(), "所选角色已不存在，请重新选择存档");
    std::fs::write(source.join("level.dat_old"), [1, 2, 3]).unwrap();
    assert!(copy(&s, &source, &saves, Some(OWNER)).is_err());
    assert_eq!(s.find_reusable(&source, "host").unwrap(), None);
    assert_eq!(entries(&saves), 0);
}

#[test]
fn old_records_are_checked_and_running_copies_cannot_be_reused() {
    let t = Temp::new("legacy");
    let s = service(&t.0);
    let record = copy(&s, &save(&t.0, "w", Some(OWNER)), &t.0.join("saves"), None).unwrap();
    let legacy = MarkingRecord { identity_version: 0, original_owner_uuid: None, selected_player_uuid: None, ..record.clone() };
    std::fs::write(t.0.join("records.json"), serde_json::to_string(&[&legacy]).unwrap()).unwrap();
    assert_eq!(s.find_reusable(Path::new(&record.source_path), "host").unwrap(), Some(legacy.clone()));
    s.check_identity(&legacy).unwrap();
    {
        let _game = SaveSourceLock::acquire(&record.copy()).unwrap();
        assert_eq!(s.check_identity(&record).unwrap_err().to_string(), "该存档正在被游戏使用，请先在游戏中退出该存档");
    }
    std::fs::write(record.copy().join("level.dat"), level_dat(Some(FRIEND), 0.0, 0.0)).unwrap();
    assert!(s.check_identity(&legacy).is_err());
}

#[test]
fn records_are_shared_with_compose() {
    let t = Temp::new("records");
    // Kotlin leaves out fields equal to their defaults and may add fields later.
    let kotlin = r#"[{"sourcePath":"C:\\saves\\w","hostId":"h","copyPath":"C:\\pack\\saves\\w-RDI导入","launchUuid":"00112233-4455-6677-8899-aabb00000000","createdAt":1,"futureField":true},
        {"sourcePath":"C:\\saves\\w","hostId":"h","copyPath":"C:\\pack\\saves\\w-RDI导入-2","launchUuid":"1512ef02-2b79-4494-a4e9-e35cd8e03923","createdAt":2,"identityVersion":1,"originalOwnerUuid":"00000000-0000-300c-9be5-0017dec2993d","selectedPlayerUuid":"1512ef02-2b79-4494-a4e9-e35cd8e03923","selectedPlayerName":"Steve","identityBackupPath":"C:\\pack\\rdi-save-import-staging\\prepare-1\\backup"}]"#;
    std::fs::write(t.0.join("records.json"), kotlin).unwrap();
    let records = service(&t.0).records().unwrap();
    assert_eq!(records[0].identity_version, 0);
    assert_eq!(records[0].original_owner_uuid, None);
    assert_eq!(records[1].selected_player_name.as_deref(), Some("Steve"));
    assert_eq!(records[1].copy_folder_name(), if cfg!(windows) { "w-RDI导入-2" } else { "C:\\pack\\saves\\w-RDI导入-2" });
    let written = serde_json::to_value(&records).unwrap();
    assert_eq!(written[0].as_object().unwrap().keys().cloned().collect::<HashSet<_>>(), HashSet::from(["sourcePath", "hostId", "copyPath", "launchUuid", "createdAt"].map(String::from)));
    assert_eq!(written[1]["identityBackupPath"], "C:\\pack\\rdi-save-import-staging\\prepare-1\\backup");
}

#[test]
fn an_unreadable_records_file_is_never_overwritten() {
    let t = Temp::new("broken");
    let s = service(&t.0);
    std::fs::write(t.0.join("records.json"), "{broken").unwrap();
    let saves = t.0.join("saves");
    assert!(copy(&s, &save(&t.0, "w", Some(OWNER)), &saves, None).is_err());
    assert_eq!(std::fs::read_to_string(t.0.join("records.json")).unwrap(), "{broken");
    assert_eq!(entries(&saves), 0, "nothing is copied without a place to remember it");
}

#[test]
fn copy_folders_never_reuse_a_name() {
    let t = Temp::new("free");
    assert_eq!(free_copy_folder(&t.0, "w"), t.0.join("w-RDI导入"));
    std::fs::create_dir(t.0.join("w-RDI导入")).unwrap();
    std::fs::write(t.0.join("w-RDI导入-2"), "").unwrap();
    assert_eq!(free_copy_folder(&t.0, "w"), t.0.join("w-RDI导入-3"));
}

#[test]
fn marking_launch_arguments() {
    let record = MarkingRecord {
        source_path: "s".into(),
        host_id: "h".into(),
        copy_path: std::env::temp_dir().join("saves").join("新的世界-RDI导入").to_string_lossy().into_owned(),
        launch_uuid: FRIEND.to_string(),
        created_at: 0,
        identity_version: 1,
        original_owner_uuid: Some(OWNER.to_string()),
        selected_player_uuid: Some(FRIEND.to_string()),
        selected_player_name: Some("Steve_1".into()),
        identity_backup_path: None,
    };
    let o = marking_overrides(&record, "RDI玩家");
    let account = o.account.clone().unwrap();
    assert_eq!((account.name.as_str(), account.uuid.as_str(), account.access_token.as_str(), account.user_type.as_str()), ("Steve_1", FRIEND.to_string().as_str(), "0", "msa"));
    assert_eq!(o.extra_jvm_args, ["-Drdi.syncChunkMarking=true", "-Drdi.syncChunkMarkingWorld=新的世界-RDI导入"]);
    assert_eq!(o.extra_game_args, ["--quickPlaySingleplayer", "新的世界-RDI导入"]);
    for name in [None, Some("单机存档主人"), Some("a b"), Some(""), Some("abcdefghijklmnopq")] {
        let r = MarkingRecord { selected_player_name: name.map(String::from), ..record.clone() };
        assert_eq!(marking_overrides(&r, "RDI玩家").account.unwrap().name, "RDI玩家", "{name:?}");
    }
}

#[test]
fn loaders_and_object_ids_follow_kotlin() {
    use rdi_anvilrw::SaveLoaderDetection as D;
    assert_eq!(loader_name(D::FabricHistory).unwrap(), "fabric");
    assert_eq!(loader_name(D::Vanilla).unwrap_err().to_string(), "暂不支持导入原版存档");
    assert_eq!(object_id_of(RDI).unwrap(), "00112233445566778899aabb");
    assert!(object_id_of(FRIEND).is_err());
    assert_eq!(crate::play::room::uuid_of("00112233445566778899aabb").unwrap(), RDI.to_string());
}

#[test]
fn envelopes_keep_the_error_code() {
    let error = decode_envelope::<serde_json::Value>(br#"{"code":-1,"msg":"slow down","errorCode":"chunked-upload-part-retryable"}"#).unwrap_err();
    assert!(upload::is_retryable(&error));
    let error = decode_envelope::<serde_json::Value>(br#"{"code":-1,"msg":"sha1 mismatch"}"#).unwrap_err();
    assert!(!upload::is_retryable(&error));
    assert_eq!(error.to_string(), "sha1 mismatch");
    assert!(upload::is_retryable(&anyhow::anyhow!("服务器暂时无法处理存档导入:HTTP502")));
    assert_eq!(decode_envelope::<i32>(br#"{"code":0,"msg":"","data":null}"#).unwrap(), None);
}

// ---- the import task against a fake server ----

#[derive(Default)]
struct Fake {
    part_size: u64,
    already_uploaded: Vec<u64>,
    /// Errors returned by the first uploads of a part, in order.
    part_failures: HashMap<u64, Vec<(String, Option<String>)>>,
    precheck_warnings: Vec<MemberWarning>,
    statuses: Vec<ImportStatus>,
    // What happened:
    created: Option<CreateDto>,
    parts: HashMap<u64, Vec<u8>>,
    attempts: Vec<u64>,
    completed: bool,
    cancelled: Vec<String>,
}

struct FakeApi(Mutex<Fake>);

impl ImportApi for FakeApi {
    async fn precheck(&self, _host: &str, _dto: &PrecheckDto) -> anyhow::Result<PrecheckVo> {
        Ok(PrecheckVo { member_warnings: self.0.lock().unwrap().precheck_warnings.clone(), game_rule_override_count: 0 })
    }
    async fn create(&self, _host: &str, dto: &CreateDto) -> anyhow::Result<SessionVo> {
        let mut f = self.0.lock().unwrap();
        f.created = Some(dto.clone());
        let part_count = (dto.size - 1) / f.part_size + 1;
        Ok(SessionVo {
            id: "import-1".into(),
            size: dto.size,
            part_size: f.part_size,
            part_count,
            uploaded_parts: f.already_uploaded.clone(),
            expires_at: 0,
            status: ImportStatus::Uploading,
            error_message: None,
            member_warnings: vec![],
        })
    }
    async fn status(&self, _host: &str, _id: &str) -> anyhow::Result<SessionVo> {
        let mut f = self.0.lock().unwrap();
        let status = if f.statuses.len() > 1 { f.statuses.remove(0) } else { f.statuses[0] };
        Ok(SessionVo {
            id: "import-1".into(),
            size: 0,
            part_size: 0,
            part_count: 0,
            uploaded_parts: vec![],
            expires_at: 0,
            status,
            error_message: (status == ImportStatus::Failed).then(|| "服务器拒绝了存档".into()),
            member_warnings: vec![],
        })
    }
    async fn upload_part(&self, _host: &str, _id: &str, index: u64, bytes: Vec<u8>, sha1: String) -> anyhow::Result<()> {
        use sha1::Digest;
        let mut f = self.0.lock().unwrap();
        f.attempts.push(index);
        assert_eq!(sha1, sha1::Sha1::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect::<String>());
        if let Some(failures) = f.part_failures.get_mut(&index)
            && !failures.is_empty()
        {
            let (message, error_code) = failures.remove(0);
            return Err(Rejected { message, error_code }.into());
        }
        f.parts.insert(index, bytes);
        Ok(())
    }
    async fn complete(&self, _host: &str, _id: &str) -> anyhow::Result<SessionVo> {
        self.0.lock().unwrap().completed = true;
        self.status("", "").await
    }
    async fn cancel(&self, _host: &str, id: &str) -> anyhow::Result<()> {
        self.0.lock().unwrap().cancelled.push(id.into());
        Ok(())
    }
    async fn find_by_qq(&self, _qq: &str) -> anyhow::Result<AccountDto> {
        Err(Rejected { message: "无此账号".into(), error_code: None }.into())
    }
    async fn msid_match(&self, _uuids: &[Uuid]) -> anyhow::Result<Vec<MsidMatchVo>> {
        Ok(vec![])
    }
    async fn existing_accounts(&self, ids: &[String]) -> anyhow::Result<HashSet<String>> {
        Ok(ids.iter().cloned().collect())
    }
    async fn mojang_name(&self, _uuid: Uuid) -> anyhow::Result<String> {
        anyhow::bail!("offline")
    }
}

struct Setup {
    t: Temp,
    marking: MarkingService,
    plan: ImportPlan,
    env: ImportEnv,
}

/// A marked copy with an owner (mapped to RDI) and a generated chunk at 0,0.
fn setup(name: &str) -> Setup {
    let t = Temp::new(name);
    let marking = service(&t.0);
    let source = save(&t.0, "w", Some(OWNER));
    std::fs::write(source.join("region/r.0.0.mca"), generated_region()).unwrap();
    let record = copy(&marking, &source, &t.0.join("saves"), None).unwrap();
    std::fs::create_dir_all(record.copy().join("data")).unwrap();
    std::fs::write(record.copy().join("data/rdi_sync_chunks.dat"), sync_list(&[("minecraft:overworld", 0, 0)])).unwrap();
    let plan = ImportPlan {
        host_id: "66a000000000000000000001".into(),
        host_name: "测试房间".into(),
        record,
        mapping: HashMap::from([(OWNER, RDI)]),
        member_player_ids: vec![object_id_of(RDI).unwrap()],
        mc_version_name: "1.20.1".into(),
        loader: "forge".into(),
        confirmed_warnings: vec![],
    };
    let env = ImportEnv {
        temp_root: t.0.join("pack-proc/save-imports"),
        poll_interval: Duration::from_millis(1),
        poll_timeout: Duration::from_secs(5),
        upload: upload::Options { parallelism: 3, retries: 2, retry_delay: Duration::from_millis(1), max_retry_delay: Duration::from_millis(2) },
    };
    Setup { t, marking, plan, env }
}

fn run(s: &Setup, fake: Fake) -> (anyhow::Result<()>, Fake) {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let api = Arc::new(FakeApi(Mutex::new(fake)));
    let context = crate::tasks::Manager::default().test_context();
    let result = run_import(api.clone(), &s.marking, &s.plan, &s.env, runtime.handle(), &context);
    let fake = std::mem::take(&mut *api.0.lock().unwrap());
    (result, fake)
}

fn uploaded(fake: &Fake) -> Vec<u8> {
    let mut indices: Vec<_> = fake.parts.keys().copied().collect();
    indices.sort();
    indices.iter().flat_map(|i| fake.parts[i].clone()).collect()
}

#[test]
fn an_import_uploads_the_remapped_save_and_removes_the_copy() {
    let s = setup("import-ok");
    let fake = Fake {
        part_size: 64,
        part_failures: HashMap::from([(1, vec![("busy".into(), Some(upload::PART_RETRYABLE.into()))])]),
        statuses: vec![ImportStatus::Processing, ImportStatus::Ready],
        ..Default::default()
    };
    let (result, fake) = run(&s, fake);
    result.unwrap();
    let created = fake.created.clone().unwrap();
    assert_eq!((created.loader.as_str(), created.mc_version_name.as_str()), ("forge", "1.20.1"));
    let archive = uploaded(&fake);
    assert_eq!(archive.len() as u64, created.size);
    use sha1::Digest;
    assert_eq!(created.sha1, sha1::Sha1::digest(&archive).iter().map(|b| format!("{b:02x}")).collect::<String>());
    assert_eq!(fake.attempts.iter().filter(|i| **i == 1).count(), 2, "a retryable part is sent again");
    assert!(fake.completed && fake.cancelled.is_empty());

    // The archive holds the remapped owner under the RDI UUID.
    let tar = zstd::decode_all(archive.as_slice()).unwrap();
    let mut names = vec![];
    let mut level = None;
    for entry in tar::Archive::new(tar.as_slice()).entries().unwrap() {
        let mut entry = entry.unwrap();
        let name = entry.path().unwrap().to_string_lossy().into_owned();
        if name == "level.dat" {
            let mut bytes = vec![];
            std::io::Read::read_to_end(&mut entry, &mut bytes).unwrap();
            level = Some(bytes);
        }
        names.push(name);
    }
    assert!(names.contains(&"region/".to_owned()) && names.contains(&"region/r.0.0.mca".to_owned()), "{names:?}");
    let level = NbtDocument::open(rdi_anvilrw::compress::gunzip(&level.unwrap(), 1 << 20, "level.dat").unwrap()).unwrap();
    assert_eq!(level.level_metadata().unwrap().singleplayer_uuid, Some(RDI));

    assert!(!s.plan.record.copy().exists(), "the copy is deleted after the import");
    assert_eq!(s.marking.records().unwrap(), vec![]);
    assert_eq!(entries(&s.env.temp_root), 0, "the temp dir is removed");
}

#[test]
fn an_upload_resumes_after_acknowledged_parts() {
    let s = setup("import-resume");
    let (result, fake) = run(&s, Fake { part_size: 64, already_uploaded: vec![0, 2], statuses: vec![ImportStatus::Ready], ..Default::default() });
    result.unwrap();
    assert!(!fake.attempts.contains(&0) && !fake.attempts.contains(&2));
    assert!(fake.attempts.contains(&1));
}

#[test]
fn a_rejected_part_cancels_the_server_session_and_keeps_the_copy() {
    let s = setup("import-rejected");
    let fake = Fake { part_size: 64, part_failures: HashMap::from([(0, vec![("分片校验失败".into(), None)])]), statuses: vec![ImportStatus::Ready], ..Default::default() };
    let (result, fake) = run(&s, fake);
    assert_eq!(result.unwrap_err().to_string(), "分片校验失败");
    assert_eq!(fake.cancelled, ["import-1"]);
    assert!(!fake.completed);
    assert!(s.plan.record.copy().is_dir());
    assert_eq!(entries(&s.env.temp_root), 0);
}

#[test]
fn new_member_warnings_stop_before_upload() {
    let s = setup("import-warning");
    let warning = MemberWarning { player_id: object_id_of(RDI).unwrap(), reason: "已是其他房间的成员".into() };
    let (result, fake) = run(&s, Fake { part_size: 64, precheck_warnings: vec![warning], statuses: vec![ImportStatus::Ready], ..Default::default() });
    assert_eq!(result.unwrap_err().to_string(), "房间成员情况有变化（已是其他房间的成员），请重新确认后导入");
    assert!(fake.created.is_none() && fake.cancelled.is_empty());
}

#[test]
fn a_failed_server_import_keeps_the_copy() {
    let s = setup("import-failed");
    let (result, fake) = run(&s, Fake { part_size: 4 * 1024 * 1024, statuses: vec![ImportStatus::Failed], ..Default::default() });
    assert_eq!(result.unwrap_err().to_string(), "服务器拒绝了存档");
    assert!(fake.completed && fake.cancelled.is_empty(), "a completed session is not cancelled");
    assert!(s.plan.record.copy().is_dir());
}

#[test]
fn an_import_needs_marks_and_a_closed_game() {
    let s = setup("import-marks");
    {
        let _game = SaveSourceLock::acquire(&s.plan.record.copy()).unwrap();
        // `check_identity` runs first and already finds the game.
        assert_eq!(run(&s, Fake { part_size: 64, ..Default::default() }).0.unwrap_err().to_string(), "该存档正在被游戏使用，请先在游戏中退出该存档");
    }
    std::fs::remove_file(s.plan.record.copy().join("data/rdi_sync_chunks.dat")).unwrap();
    assert_eq!(run(&s, Fake { part_size: 64, ..Default::default() }).0.unwrap_err().to_string(), "请至少标记一个要保留的区块");
    assert_eq!(entries(&s.env.temp_root), 0);
}

#[test]
fn prepare_reads_the_copy_and_the_server() {
    let s = setup("prepare");
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let api = FakeApi(Mutex::new(Fake::default()));
    let preparation = runtime.block_on(prepare(&api, &s.marking, &s.plan.record)).unwrap();
    assert_eq!(preparation.scan.mc_version_name.as_deref(), Some("1.20.1"));
    let owner = preparation.scan.players.iter().find(|p| p.uuid == OWNER).unwrap();
    assert!(owner.is_singleplayer_host);
    assert!(preparation.mojang_names.is_empty());
}

/// A3 live check against a running master, without the screens: copy, simulated marks (the 5x5
/// generated overworld chunks around the marking role), preparation, precheck and the whole
/// import task. Run with `--ignored --nocapture` and these variables (credentials never printed):
/// `RDI_A3_SERVER`, `RDI_A3_USR`, `RDI_A3_PWD`, `RDI_A3_HOST`, `RDI_A3_SAVE` (the source save) and
/// `RDI_A3_WORK` (an empty folder for the records, the pack `saves` and the temp files).
#[test]
#[ignore]
fn live_import_into_a_running_master() {
    let var = |name: &str| std::env::var(name).unwrap_or_else(|_| panic!("{name} is not set"));
    let (host, source, work) = (var("RDI_A3_HOST"), PathBuf::from(var("RDI_A3_SAVE")), PathBuf::from(var("RDI_A3_WORK")));
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let mut server = crate::api::server::ServerApi::from_dir(var("RDI_A3_SERVER"), &work).unwrap();
    let session = runtime.block_on(server.login(&var("RDI_A3_USR"), &var("RDI_A3_PWD"), None)).unwrap();
    let uid = session.account["_id"].as_str().unwrap().to_owned();
    let rdi = Uuid::parse_str(&crate::play::room::uuid_of(&uid).unwrap()).unwrap();
    println!("signed in as {} ({uid})", session.account["name"]);

    let marking = MarkingService::with_file(work.join("save-import-marking.json"), || 0);
    let saves = work.join("pack").join("saves");
    let started = std::time::Instant::now();
    let record = marking.create_copy(&source, &host, &saves, rdi, None, &|| Ok(()), &mut |_, _| {}).unwrap();
    println!("copy {} in {:?}, launch as {}", record.copy_folder_name(), started.elapsed(), record.launch_uuid);
    marking.check_identity(&record).unwrap();
    marking.check_readable(&record).unwrap();

    // What `/syncchunk add` would write: the generated chunks of a 5x5 square around the role.
    let role = marking_players_of(&record);
    let (cx, cz) = role;
    let mut marks = vec![];
    for x in cx - 2..=cx + 2 {
        for z in cz - 2..=cz + 2 {
            marks.push(("minecraft:overworld", x, z));
        }
    }
    std::fs::create_dir_all(record.copy().join("data")).unwrap();
    let launch = Uuid::parse_str(&record.launch_uuid).unwrap();
    std::fs::write(record.copy().join("data/rdi_sync_chunks.dat"), sync_list_by(launch, &marks)).unwrap();
    let summary = marking.summary(&record).unwrap();
    println!("marked {} chunks, {} generated, players outside {:?}", summary.marked.len(), summary.present_count, summary.players_outside);

    let api = Arc::new(HttpImportApi::new(server));
    let preparation = runtime.block_on(prepare(api.as_ref(), &marking, &record)).unwrap();
    for p in &preparation.scan.players {
        println!("player {} {:?} host={} names={:?}", p.uuid, p.kind, p.is_singleplayer_host, p.name_hints.iter().map(|h| &h.name).collect::<Vec<_>>());
    }
    let owner = preparation.scan.singleplayer_host_uuid.expect("the save has an owner");
    let mc_version_name = preparation.scan.mc_version_name.clone().unwrap();
    let loader = loader_name(preparation.scan.loader_detection).unwrap().to_owned();
    println!("save {mc_version_name} {loader}, hardcore {}", preparation.scan.is_hardcore);
    let members = vec![uid.clone()];
    let precheck = runtime
        .block_on(api.precheck(&host, &PrecheckDto { mc_version_name: mc_version_name.clone(), loader: loader.clone(), member_player_ids: members.clone() }))
        .unwrap();
    println!("precheck warnings {:?}, game rules cleared {}", precheck.member_warnings, precheck.game_rule_override_count);
    let plan = ImportPlan {
        host_id: host.clone(),
        host_name: "live".into(),
        record: record.clone(),
        mapping: HashMap::from([(owner, rdi)]),
        member_player_ids: members,
        mc_version_name,
        loader,
        confirmed_warnings: precheck.member_warnings,
    };
    let tasks = crate::tasks::Manager::default();
    let run = submit(&tasks, runtime.handle(), api, marking.clone(), plan, ImportEnv::new(&work));
    let started = std::time::Instant::now();
    let mut last = String::new();
    let snapshot = loop {
        let snapshot = tasks.snapshot(&run).unwrap();
        let line = format!("{} {}", snapshot["message"], snapshot["fraction"]);
        if line != last {
            println!("[{:>6.1}s] {line}", started.elapsed().as_secs_f32());
            last = line;
        }
        if snapshot["terminal"].as_bool().unwrap() {
            break snapshot;
        }
        std::thread::sleep(Duration::from_millis(100));
    };
    println!("task status {} error {:?}", snapshot["status"], snapshot["error"]);
    assert_eq!(snapshot["status"], 2);
    assert!(!record.copy().exists(), "the copy is deleted after the import");
    assert_eq!(entries(&work.join("pack-proc/save-imports")), 0);
}

/// The chunk of the marking role (the copy's `Data.Player`).
fn marking_players_of(record: &MarkingRecord) -> (i32, i32) {
    let level = NbtDocument::open_gzip_file(&record.copy().join("level.dat")).unwrap();
    let root = level.root();
    let pos: Vec<f64> = root.child("Data").unwrap().child("Player").unwrap().child("Pos").unwrap().list_elements().unwrap().iter().filter_map(|n| n.double()).collect();
    ((pos[0].floor() as i64).div_euclid(16) as i32, (pos[2].floor() as i64).div_euclid(16) as i32)
}
