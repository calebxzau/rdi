//! The marking copy, Kotlin `HostSaveImportMarkingService`: the save is copied into the host
//! pack's `saves` folder, the game opens it as the selected role in marking mode, and the player
//! marks chunks with `/syncchunk add`. The original save is only read, under its `session.lock`.
use anyhow::{Context, Result, anyhow, bail, ensure};
use rdi_anvilrw::{
    NbtDocument, NbtNode, RemapError, SYNC_CHUNKS_RELATIVE_PATH, SaveMarkingPlayer, SaveSnapshot, SaveSourceLock, SaveSyncChunk,
    canonical_dimension_id, chunk_store_dimension, dimension_storage_folder, marking_players, parse_sync_chunks, prepare_marking_identity, region,
};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::{Component, Path, PathBuf};
use uuid::Uuid;

/// The records file, shared with the Compose launcher until it is retired.
pub const RECORDS_FILE: &str = "save-import-marking.json";

/// gzip, zlib, uncompressed, and RDI's zstd (ID8, registered by the RDI client mod).
const GAME_READABLE_COMPRESSIONS: [u8; 4] = [1, 2, 3, 8];

/// A marking copy of a save, remembered so a later attempt can reuse it and its marks. The field
/// names and the omitted defaults match Kotlin `SaveImportMarkingRecord`, so Compose reads it too.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MarkingRecord {
    pub source_path: String,
    pub host_id: String,
    pub copy_path: String,
    /// The UUID the game is launched with in marking mode: `Data.Player.UUID`, else the RDI account.
    pub launch_uuid: String,
    pub created_at: i64,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub identity_version: i32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub original_owner_uuid: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub selected_player_uuid: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub selected_player_name: Option<String>,
    /// Original overwritten files, outside the playable and uploaded world.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub identity_backup_path: Option<String>,
}

fn is_zero(value: &i32) -> bool {
    *value == 0
}

impl MarkingRecord {
    pub fn copy(&self) -> PathBuf {
        PathBuf::from(&self.copy_path)
    }

    /// The folder name under `saves`, which the marking launch opens.
    pub fn copy_folder_name(&self) -> String {
        self.copy().file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
    }
}

/// What was marked in the copy (plan §4 step 6).
#[derive(Clone, Debug, PartialEq)]
pub struct MarkingSummary {
    pub marked: Vec<SaveSyncChunk>,
    /// Marked chunks per dimension, in the order the list first names them.
    pub marked_by_dimension: Vec<(String, usize)>,
    /// Marked chunks that exist in the save's `region` stores.
    pub present_count: usize,
    /// Players whose last position is in a dimension or chunk that is not kept.
    pub players_outside: Vec<Uuid>,
}

/// The player-facing message for a failure of the save lock, snapshot or copy (plan §8.0). The
/// task list shows an error's root cause, so a translated error carries no source; the original
/// is logged instead.
pub fn player_error(error: RemapError) -> anyhow::Error {
    let message = match &error {
        RemapError::SaveInUse { .. } => "该存档正在被游戏使用，请先在游戏中退出该存档",
        RemapError::SaveLink(_) => "存档中包含快捷方式或链接，无法导入",
        RemapError::SaveChanged(_) => "复制期间存档内容发生了变化，请关闭正在使用该存档的程序后重试",
        RemapError::Cancelled => "任务已取消",
        _ => return anyhow::Error::new(error),
    };
    tracing::warn!(error = %error, kind = error.kind_name(), "{message}");
    anyhow!(message)
}

/// A player-facing error with the detail only in the log (Kotlin `RequestError(message, cause)`).
fn logged(message: &'static str) -> impl FnOnce(RemapError) -> anyhow::Error {
    move |error| {
        tracing::warn!(error = %error, kind = error.kind_name(), "{message}");
        anyhow!(message)
    }
}

/// `toAbsolutePath().normalize()`: absolute, with `.` and `..` removed without touching the disk.
pub fn normalize(path: &Path) -> Result<PathBuf> {
    let absolute = std::path::absolute(path).with_context(|| format!("路径无效: {}", path.display()))?;
    let mut out = PathBuf::new();
    for component in absolute.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            other => out.push(other),
        }
    }
    Ok(out)
}

fn normalized_string(path: &Path) -> Result<String> {
    Ok(normalize(path)?.to_string_lossy().into_owned())
}

/// `<name>-RDI导入`, or `-2`, `-3`… when taken.
pub fn free_copy_folder(saves: &Path, source_name: &str) -> PathBuf {
    let base = format!("{source_name}-RDI导入");
    (1..)
        .map(|index| saves.join(if index == 1 { base.clone() } else { format!("{base}-{index}") }))
        .find(|candidate| std::fs::symlink_metadata(candidate).is_err())
        .expect("an unused copy folder")
}

fn exists_no_follow(path: &Path) -> bool {
    std::fs::symlink_metadata(path).is_ok()
}

fn is_plain_file(path: &Path) -> bool {
    std::fs::symlink_metadata(path).is_ok_and(|m| m.is_file())
}

#[derive(Clone)]
pub struct MarkingService {
    records_file: PathBuf,
    clock: fn() -> i64,
}

fn now_millis() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

impl MarkingService {
    /// The service on `<data>/save-import-marking.json`, the file Compose uses.
    pub fn new(data_dir: &Path) -> Self {
        Self::with_file(data_dir.join(RECORDS_FILE), now_millis)
    }

    pub fn with_file(records_file: PathBuf, clock: fn() -> i64) -> Self {
        Self { records_file, clock }
    }

    /// The roles of `source` the copy can be launched as, the owner first.
    pub fn players(&self, source: &Path) -> Result<Vec<SaveMarkingPlayer>> {
        let _lock = SaveSourceLock::acquire(source).map_err(player_error)?;
        marking_players(source).map_err(player_error)
    }

    /// The remembered copy of `source` for `host_id`, if it still exists.
    pub fn find_reusable(&self, source: &Path, host_id: &str) -> Result<Option<MarkingRecord>> {
        let source = normalized_string(source)?;
        Ok(self
            .records()?
            .into_iter()
            .rev()
            .find(|r| r.source_path == source && r.host_id == host_id && std::fs::symlink_metadata(r.copy()).is_ok_and(|m| m.is_dir())))
    }

    /// Copies `source` into `saves_dir` as `<name>-RDI导入` (or `-2`, `-3`…), holding the source's
    /// lock and verifying the copy against a snapshot. The copy is prepared outside `saves`, so
    /// Minecraft never sees a half-prepared world, and moved in when it is complete. `rdi_uuid`
    /// is the launch identity when the save has no player role.
    #[allow(clippy::too_many_arguments)]
    pub fn create_copy(
        &self,
        source: &Path,
        host_id: &str,
        saves_dir: &Path,
        rdi_uuid: Uuid,
        selected_player: Option<Uuid>,
        ensure_active: &dyn Fn() -> rdi_anvilrw::Result<()>,
        progress: &mut dyn FnMut(u64, u64),
    ) -> Result<MarkingRecord> {
        let source_root = normalize(source)?;
        let saves = normalize(saves_dir)?;
        ensure!(!saves.starts_with(&source_root), "整合包目录不能位于存档目录里");
        // Read before anything is copied: an unreadable records file stops the copy, not its record.
        let mut records = self.records()?;
        let lock = SaveSourceLock::acquire(&source_root).map_err(player_error)?;
        let snapshot = SaveSnapshot::take(&source_root).map_err(player_error)?;
        let roles = marking_players(&source_root).map_err(player_error)?;
        let selected = selected_player.or_else(|| roles.first().map(|r| r.player.uuid));
        let role = match selected {
            Some(uuid) => Some(roles.iter().find(|r| r.player.uuid == uuid).context("所选角色已不存在，请重新选择存档")?),
            None => None,
        };
        std::fs::create_dir_all(&saves).with_context(|| format!("创建存档目录失败: {}", saves.display()))?;
        let source_name = source_root.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let target = free_copy_folder(&saves, &source_name);
        let staging_root = saves.parent().context("整合包目录无效")?.join("rdi-save-import-staging");
        ensure!(!staging_root.starts_with(&source_root), "准备目录不能位于原存档内");
        let staging = staging_root.join(format!("prepare-{}", Uuid::now_v7()));
        std::fs::create_dir_all(&staging).with_context(|| format!("创建准备目录失败: {}", staging.display()))?;
        let world = staging.join("world");
        let backup = staging.join("backup");
        let prepared = (|| -> Result<Option<Uuid>> {
            let total = snapshot.total_bytes();
            snapshot.copy_to(&world, ensure_active, &mut |done| progress(done, total)).map_err(player_error)?;
            let owner = {
                let _world_lock = SaveSourceLock::acquire(&world).map_err(player_error)?;
                prepare_marking_identity(&world, selected, &backup).map_err(player_error)?
            };
            ensure_active().map_err(player_error)?;
            std::fs::rename(&world, &target).with_context(|| format!("移动存档副本失败: {}", target.display()))?;
            Ok(owner)
        })();
        drop(lock);
        let owner = match prepared {
            Ok(owner) => owner,
            Err(error) => {
                // Only the staging folder is removed; nothing was published.
                if let Err(cleanup) = std::fs::remove_dir_all(&staging) {
                    tracing::warn!(error = %cleanup, path = %staging.display(), "清理存档导入准备目录失败");
                }
                return Err(error);
            }
        };
        let record = MarkingRecord {
            source_path: source_root.to_string_lossy().into_owned(),
            host_id: host_id.into(),
            copy_path: target.to_string_lossy().into_owned(),
            launch_uuid: selected.unwrap_or(rdi_uuid).to_string(),
            created_at: (self.clock)(),
            identity_version: 1,
            original_owner_uuid: owner.map(|u| u.to_string()),
            selected_player_uuid: selected.map(|u| u.to_string()),
            selected_player_name: role.map(SaveMarkingPlayer::display_name),
            identity_backup_path: Some(backup.to_string_lossy().into_owned()),
        };
        records.retain(|r| r.copy_path != record.copy_path);
        records.push(record.clone());
        self.save_records(&records)?;
        Ok(record)
    }

    /// A copy opened normally (not in marking mode) is played as the RDI account, which writes the
    /// owner's data under the wrong UUID; such a copy must be recreated.
    pub fn check_identity(&self, record: &MarkingRecord) -> Result<()> {
        ensure!(matches!(record.identity_version, 0 | 1), "导入副本版本不受支持，请重新创建副本");
        ensure!(
            record.selected_player_uuid.as_ref().is_none_or(|s| *s == record.launch_uuid),
            "副本角色记录不一致，请重新创建副本"
        );
        let copy = record.copy();
        let _lock = SaveSourceLock::acquire(&copy).map_err(player_error)?;
        let current = NbtDocument::read_level_dat(&copy.join("level.dat")).map_err(logged("副本的level.dat无效"))?.singleplayer_uuid;
        if current.is_some_and(|c| c.to_string() != record.launch_uuid)
            || (record.identity_version == 1 && record.selected_player_uuid.is_some() && current.is_none())
        {
            bail!("副本中的角色身份发生变化，请重新创建副本");
        }
        if record.identity_version == 0
            && current.is_none()
            && marking_players(&copy).map_err(player_error)?.iter().any(|p| p.player.uuid.to_string() != record.launch_uuid)
        {
            bail!("旧副本无法确认标记角色，请重新创建副本");
        }
        if let Some(owner) = record.original_owner_uuid.as_ref().filter(|o| **o != record.launch_uuid) {
            let uuid = Uuid::parse_str(owner).map_err(|_| anyhow!("副本角色记录不一致，请重新创建副本"))?;
            let saved = NbtDocument::open_gzip_file(&copy.join("playerdata").join(format!("{uuid}.dat")))
                .map_err(logged("副本中的原主人数据缺失或损坏，请重新创建副本"))?;
            let saved_uuid = saved.root().unique_child("UUID").map_err(logged("副本中的原主人身份发生变化，请重新创建副本"))?.and_then(|n| n.uuid());
            ensure!(saved_uuid == Some(uuid), "副本中的原主人身份发生变化，请重新创建副本");
        }
        Ok(())
    }

    /// Refuses a copy whose chunks use a compression the game cannot read. The game would treat
    /// such chunks as missing, regenerate them and save the new terrain over them during marking.
    pub fn check_readable(&self, record: &MarkingRecord) -> Result<()> {
        let copy = record.copy();
        let mut counts: BTreeMap<u8, usize> = BTreeMap::new();
        let mut stack = vec![copy.clone()];
        while let Some(dir) = stack.pop() {
            for entry in std::fs::read_dir(&dir).with_context(|| format!("读取目录失败: {}", dir.display()))? {
                let path = entry?.path();
                let meta = std::fs::symlink_metadata(&path)?;
                if meta.is_dir() {
                    stack.push(path);
                    continue;
                }
                if !meta.is_file() || !path.extension().is_some_and(|e| e == "mca") {
                    continue;
                }
                let relative = path.strip_prefix(&copy)?.components().map(|c| c.as_os_str().to_string_lossy()).collect::<Vec<_>>().join("/");
                if chunk_store_dimension(&relative).is_none() {
                    continue;
                }
                for (kind, count) in region::compression_types(&path).map_err(anyhow::Error::new)? {
                    *counts.entry(kind).or_insert(0) += count;
                }
            }
        }
        let unreadable: Vec<(u8, usize)> = counts.into_iter().filter(|(k, _)| !GAME_READABLE_COMPRESSIONS.contains(k)).collect();
        if !unreadable.is_empty() {
            let names = unreadable
                .iter()
                .map(|(kind, _)| match kind {
                    4 => "LZ4".to_owned(),
                    127 => "自定义压缩".to_owned(),
                    other => format!("编号{other}"),
                })
                .collect::<Vec<_>>()
                .join("、");
            let count: usize = unreadable.iter().map(|(_, c)| c).sum();
            bail!("存档中有{count}个区块使用游戏无法读取的压缩方式（{names}），进入游戏后会被重新生成并覆盖，无法安全标记");
        }
        Ok(())
    }

    /// What was marked in the copy (plan §4 step 6).
    pub fn summary(&self, record: &MarkingRecord) -> Result<MarkingSummary> {
        let copy = record.copy();
        let sync_file = copy.join(SYNC_CHUNKS_RELATIVE_PATH);
        let marked = if is_plain_file(&sync_file) {
            let bytes = std::fs::read(&sync_file).with_context(|| format!("读取同步区块列表失败: {}", sync_file.display()))?;
            parse_sync_chunks(&bytes).map_err(logged("同步区块列表无效"))?
        } else {
            vec![]
        };
        let mut present = 0;
        let mut regions: HashMap<PathBuf, HashSet<(i32, i32)>> = HashMap::new();
        for chunk in &marked {
            let folder = dimension_storage_folder(&copy, &chunk.dimension_id).map_err(anyhow::Error::new)?;
            let file = folder.join("region").join(format!("r.{}.{}.mca", chunk.chunk_x >> 5, chunk.chunk_z >> 5));
            if !is_plain_file(&file) {
                continue;
            }
            if !regions.contains_key(&file) {
                let chunks = region::present_chunks(&file).map_err(anyhow::Error::new)?.into_iter().collect();
                regions.insert(file.clone(), chunks);
            }
            if regions[&file].contains(&(chunk.chunk_x, chunk.chunk_z)) {
                present += 1;
            }
        }
        let mut by_dimension: Vec<(String, usize)> = vec![];
        let mut keep: HashMap<&str, HashSet<(i32, i32)>> = HashMap::new();
        for chunk in &marked {
            match by_dimension.iter_mut().find(|(d, _)| *d == chunk.dimension_id) {
                Some((_, count)) => *count += 1,
                None => by_dimension.push((chunk.dimension_id.clone(), 1)),
            }
            keep.entry(chunk.dimension_id.as_str()).or_default().insert((chunk.chunk_x, chunk.chunk_z));
        }
        let players_outside = player_positions(&copy)
            .into_iter()
            .filter(|(_, (dimension, chunk))| !keep.get(dimension.as_str()).is_some_and(|k| k.contains(chunk)))
            .map(|(uuid, _)| uuid)
            .collect();
        Ok(MarkingSummary { marked, marked_by_dimension: by_dimension, present_count: present, players_outside })
    }

    /// Deletes the copy and forgets it.
    pub fn delete(&self, record: &MarkingRecord) -> Result<()> {
        let copy = record.copy();
        if exists_no_follow(&copy) {
            drop(SaveSourceLock::acquire(&copy).map_err(player_error)?);
            std::fs::remove_dir_all(&copy).with_context(|| format!("删除存档副本失败: {}", copy.display()))?;
        }
        let mut records = self.records()?;
        records.retain(|r| r.copy_path != record.copy_path);
        self.save_records(&records)
    }

    /// Every remembered copy. Unlike Kotlin, an unreadable file is an error rather than an empty
    /// list, so the next save cannot overwrite the records Compose wrote.
    pub fn records(&self) -> Result<Vec<MarkingRecord>> {
        let text = match std::fs::read_to_string(&self.records_file) {
            Ok(text) => text,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
            Err(error) => {
                tracing::error!(%error, path = %self.records_file.display(), "读取存档导入副本记录失败");
                bail!("读取存档导入副本记录失败");
            }
        };
        serde_json::from_str(&text).map_err(|error| {
            tracing::error!(%error, path = %self.records_file.display(), "读取存档导入副本记录失败");
            anyhow!("存档导入副本记录已损坏: {}", self.records_file.display())
        })
    }

    fn save_records(&self, records: &[MarkingRecord]) -> Result<()> {
        if let Some(parent) = self.records_file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut temporary = self.records_file.clone().into_os_string();
        temporary.push(".tmp");
        let temporary = PathBuf::from(temporary);
        std::fs::write(&temporary, serde_json::to_string(records)?).context("保存存档导入副本记录失败")?;
        std::fs::rename(&temporary, &self.records_file).context("保存存档导入副本记录失败")
    }
}

/// A player's dimension and chunk.
type Position = (String, (i32, i32));

/// Last positions of the owner (`Data.Player`) and every playerdata file, as
/// `(uuid, (dimension, chunk))`; the owner's entry replaces a playerdata entry in place.
fn player_positions(save: &Path) -> Vec<(Uuid, Position)> {
    let mut positions: Vec<(Uuid, Position)> = vec![];
    let mut put = |uuid: Uuid, position: Position| match positions.iter_mut().find(|(u, _)| *u == uuid) {
        Some(entry) => entry.1 = position,
        None => positions.push((uuid, position)),
    };
    let playerdata = save.join("playerdata");
    if std::fs::symlink_metadata(&playerdata).is_ok_and(|m| m.is_dir()) {
        let mut files: Vec<PathBuf> = match std::fs::read_dir(&playerdata) {
            Ok(entries) => entries.filter_map(|e| e.ok().map(|e| e.path())).collect(),
            Err(error) => {
                tracing::warn!(%error, path = %playerdata.display(), "读取玩家数据目录失败");
                vec![]
            }
        };
        files.sort();
        for file in files {
            let Some(stem) = file.file_name().and_then(|n| n.to_str()).and_then(|n| n.strip_suffix(".dat")) else {
                continue;
            };
            let Some(uuid) = (stem.len() == 36).then(|| Uuid::parse_str(stem).ok()).flatten() else {
                continue;
            };
            if let Some(document) = read_player(&file)
                && let Some(position) = position(&document.root())
            {
                put(uuid, position);
            }
        }
    }
    if let Some(level) = read_player(&save.join("level.dat")) {
        let root = level.root();
        if let Some(owner) = root.child("Data").and_then(|d| d.child("Player"))
            && let (Some(uuid), Some(position)) = (owner.child("UUID").and_then(|n| n.uuid()), position(&owner))
        {
            put(uuid, position);
        }
    }
    positions
}

/// Positions are informational; an unreadable file is logged and left out of the summary.
fn read_player(file: &Path) -> Option<NbtDocument> {
    NbtDocument::open_gzip_file(file)
        .map_err(|error| tracing::warn!(error = %error, path = %file.display(), "读取玩家位置失败"))
        .ok()
}

fn position(player: &NbtNode<'_>) -> Option<Position> {
    let pos: Vec<f64> = player.child("Pos")?.list_elements()?.iter().filter_map(|n| n.double()).collect();
    if pos.len() != 3 {
        return None;
    }
    let dimension = player
        .child("Dimension")
        .and_then(|n| n.string().ok().flatten())
        .map(|d| canonical_dimension_id(&d))
        .unwrap_or_else(|| "minecraft:overworld".into());
    let chunk = |v: f64| (v.floor() as i64).div_euclid(16) as i32;
    Some((dimension, (chunk(pos[0]), chunk(pos[2]))))
}
