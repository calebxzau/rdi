//! Players of a save, the owner's choices about them, and the marking copy's player identity.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;
use std::time::SystemTime;

use md5::{Digest, Md5};
use regex::Regex;
use uuid::Uuid;

use crate::compress;
use crate::error::{IoContext, RemapError, Result};
use crate::fsutil;
use crate::limits::STRICT_NBT_FILE_BYTES;
use crate::reader::{LevelMetadata, NbtDocument, NbtNode, SaveLoaderDetection};
use crate::snapshot::SaveSnapshot;
use crate::sync_chunks::{SYNC_CHUNKS_RELATIVE_PATH, SaveSyncChunk, parse_sync_chunks};
use crate::text::is_strict_utf8;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SavePlayerKind {
    Online,
    Offline,
    Rdi,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum NameHintSource {
    Mojang,
    UserCache,
    FtbTeams,
    FtbQuests,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct NameHint {
    pub name: String,
    pub source: NameHintSource,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SavePlayer {
    pub uuid: Uuid,
    pub kind: SavePlayerKind,
    /// Ordered by confidence. Mojang hints are added by the client after the scan.
    pub name_hints: Vec<NameHint>,
    /// Set for an offline player when a hint's offline UUID equals `uuid`.
    pub verified_name: Option<String>,
    pub is_singleplayer_host: bool,
    /// The playerdata file's modification time, or `level.dat`'s for the singleplayer host.
    pub last_played: Option<SystemTime>,
    /// `stats/<uuid>.json` → `minecraft:custom` → `minecraft:play_time`.
    pub play_time_ticks: Option<i64>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SaveScanResult {
    pub mc_version_name: Option<String>,
    pub data_version: Option<i32>,
    /// `Data.ServerBrands`, the history of brands that ever opened the save.
    pub server_brands: Vec<String>,
    /// Mod IDs of the root `fml.LoadingModList`, or `None` when the save has no `fml` compound.
    pub fml_mod_ids: Option<Vec<String>>,
    pub loader_detection: SaveLoaderDetection,
    pub is_hardcore: bool,
    /// `Data.Player.UUID`. The marking copy is launched with this UUID.
    pub singleplayer_host_uuid: Option<Uuid>,
    pub players: Vec<SavePlayer>,
    /// `data/rdi_sync_chunks.dat`, or `None` when the save has none.
    pub sync_chunks: Option<Vec<SaveSyncChunk>>,
    pub total_bytes: u64,
}

/// Name hint files are small; anything larger is ignored rather than read.
const HINT_FILE_BYTES: u64 = 4 * 1024 * 1024;

static PLAYERDATA: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^playerdata/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.dat$")
        .expect("valid regex")
});
static PLAY_TIME: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#""minecraft:play_time"(?-u:\s)*:(?-u:\s)*([0-9]+)"#).expect("valid regex")
});
static FTB_TEAMS_NAME: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"\bplayer_name(?-u:\s)*:(?-u:\s)*"((?:[^"\\]|\\.)*)""#).expect("valid regex")
});
static FTB_QUESTS_NAME: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"\bname(?-u:\s)*:(?-u:\s)*"((?:[^"\\]|\\.)*)""#).expect("valid regex")
});
static JSON_OBJECT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\{[^{}]*\}").expect("valid regex"));
static JSON_NAME: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#""name"(?-u:\s)*:(?-u:\s)*"((?:[^"\\]|\\.)*)""#).expect("valid regex")
});
static JSON_UUID: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#""uuid"(?-u:\s)*:(?-u:\s)*"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})""#)
        .expect("valid regex")
});
static ESCAPE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\\(.)").expect("valid regex"));

fn unescape(value: &str) -> String {
    ESCAPE.replace_all(value, "$1").into_owned()
}

/// RDI identities are ObjectId-derived: 12 id bytes followed by 4 zero bytes.
pub fn is_rdi_uuid(uuid: Uuid) -> bool {
    uuid.as_u64_pair().1 & 0xffff_ffff == 0
}

pub fn classify(uuid: Uuid) -> SavePlayerKind {
    if is_rdi_uuid(uuid) {
        SavePlayerKind::Rdi
    } else {
        match uuid.get_version_num() {
            4 => SavePlayerKind::Online,
            3 => SavePlayerKind::Offline,
            _ => SavePlayerKind::Unknown,
        }
    }
}

/// Java's `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")`.
pub fn offline_uuid(name: &str) -> Uuid {
    let digest: [u8; 16] = Md5::digest(format!("OfflinePlayer:{name}").as_bytes()).into();
    uuid::Builder::from_md5_bytes(digest).into_uuid()
}

fn read_strict_nbt(snapshot: &SaveSnapshot, relative_path: &str) -> Result<Vec<u8>> {
    let state = snapshot.files().get(relative_path).ok_or_else(|| {
        RemapError::io(
            relative_path.to_string(),
            std::io::Error::new(
                std::io::ErrorKind::NotFound,
                format!("{relative_path} is missing"),
            ),
        )
    })?;
    if state.size > STRICT_NBT_FILE_BYTES as u64 {
        return Err(RemapError::LimitExceeded(format!(
            "{relative_path} exceeds {STRICT_NBT_FILE_BYTES} bytes"
        )));
    }
    compress::gunzip(
        &snapshot.read_file(relative_path)?,
        STRICT_NBT_FILE_BYTES,
        relative_path,
    )
}

/// A small text file of the save, or `None` when it is absent, too large or not UTF-8.
fn read_text(snapshot: &SaveSnapshot, relative_path: &str) -> Result<Option<String>> {
    let Some(state) = snapshot.files().get(relative_path) else {
        return Ok(None);
    };
    if state.size > HINT_FILE_BYTES {
        return Ok(None);
    }
    let bytes = snapshot.read_file(relative_path)?;
    Ok(if is_strict_utf8(&bytes) {
        String::from_utf8(bytes).ok()
    } else {
        None
    })
}

fn read_snbt_string(
    snapshot: &SaveSnapshot,
    relative_path: &str,
    pattern: &Regex,
) -> Result<Option<String>> {
    Ok(read_text(snapshot, relative_path)?
        .and_then(|text| pattern.captures(&text).map(|c| unescape(&c[1]))))
}

fn read_play_time(snapshot: &SaveSnapshot, uuid: Uuid) -> Result<Option<i64>> {
    Ok(
        read_text(snapshot, &format!("stats/{uuid}.json"))?.and_then(|text| {
            PLAY_TIME
                .captures(&text)
                .and_then(|c| c[1].parse::<i64>().ok())
        }),
    )
}

/// `usercache.json` of the game directory, when the save lives in `<game dir>/saves/<world>`.
/// Expired entries are still valid hints. It is outside the save, so it is read directly.
fn read_user_cache(world_dir: &Path) -> Result<HashMap<Uuid, String>> {
    let absolute = std::path::absolute(world_dir).ctx(|| world_dir.display().to_string())?;
    let normalized = fsutil::normalize(&absolute);
    let Some(parent) = normalized.parent() else {
        return Ok(HashMap::new());
    };
    if parent.file_name().and_then(|n| n.to_str()) != Some("saves") {
        return Ok(HashMap::new());
    }
    let Some(game) = parent.parent() else {
        return Ok(HashMap::new());
    };
    let file = game.join("usercache.json");
    match fs::symlink_metadata(&file) {
        Ok(metadata) if fsutil::is_plain_file(&metadata) && metadata.len() <= HINT_FILE_BYTES => {}
        _ => return Ok(HashMap::new()),
    }
    let bytes = fs::read(&file).ctx(|| file.display().to_string())?;
    if !is_strict_utf8(&bytes) {
        return Ok(HashMap::new());
    }
    let text = String::from_utf8(bytes).expect("checked as UTF-8");
    let mut names = HashMap::new();
    for entry in JSON_OBJECT.find_iter(&text) {
        let Some(name) = JSON_NAME.captures(entry.as_str()).map(|c| unescape(&c[1])) else {
            continue;
        };
        let Some(uuid) = JSON_UUID
            .captures(entry.as_str())
            .and_then(|c| Uuid::parse_str(&c[1]).ok())
        else {
            continue;
        };
        names.entry(uuid).or_insert(name);
    }
    Ok(names)
}

/// Finds the players of a save and the facts the import prechecks need. Pure file I/O: no network
/// access and no writes. All NBT is read through [`NbtDocument`], within the limits.
///
/// Links and special files fail the scan ([`RemapError::SaveLink`]), so the owner learns about them
/// before anything is copied.
pub fn scan_save(world_dir: &Path) -> Result<SaveScanResult> {
    let snapshot = SaveSnapshot::take(world_dir)?;
    let metadata = NbtDocument::open(read_strict_nbt(&snapshot, "level.dat")?)?.level_metadata()?;
    let host_uuid = metadata.singleplayer_uuid;

    let mut player_uuids = Vec::new();
    let mut seen = HashSet::new();
    for path in snapshot.files().keys() {
        if let Some(captures) = PLAYERDATA.captures(path) {
            let uuid = Uuid::parse_str(&captures[1]).expect("matched a dashed UUID");
            if seen.insert(uuid) {
                player_uuids.push(uuid);
            }
        }
    }
    if let Some(host) = host_uuid
        && seen.insert(host)
    {
        player_uuids.push(host);
    }

    let user_cache = read_user_cache(world_dir)?;
    let mut players = Vec::with_capacity(player_uuids.len());
    for uuid in player_uuids {
        let mut hints = Vec::new();
        if let Some(name) = user_cache.get(&uuid) {
            hints.push(NameHint {
                name: name.clone(),
                source: NameHintSource::UserCache,
            });
        }
        if let Some(name) = read_snbt_string(
            &snapshot,
            &format!("ftbteams/player/{uuid}.snbt"),
            &FTB_TEAMS_NAME,
        )? {
            hints.push(NameHint {
                name,
                source: NameHintSource::FtbTeams,
            });
        }
        if let Some(name) = read_snbt_string(
            &snapshot,
            &format!("ftbquests/{uuid}.snbt"),
            &FTB_QUESTS_NAME,
        )? {
            let name = match name.rfind('#') {
                Some(at) => name[..at].to_string(),
                None => name,
            };
            if !name.is_empty() {
                hints.push(NameHint {
                    name,
                    source: NameHintSource::FtbQuests,
                });
            }
        }
        let mut distinct = HashSet::new();
        hints.retain(|hint| distinct.insert((hint.name.clone(), hint.source)));
        let kind = classify(uuid);
        let is_host = Some(uuid) == host_uuid;
        let state_path = if is_host {
            "level.dat".to_string()
        } else {
            format!("playerdata/{uuid}.dat")
        };
        players.push(SavePlayer {
            uuid,
            kind,
            verified_name: if kind == SavePlayerKind::Offline {
                hints
                    .iter()
                    .find(|hint| offline_uuid(&hint.name) == uuid)
                    .map(|hint| hint.name.clone())
            } else {
                None
            },
            name_hints: hints,
            is_singleplayer_host: is_host,
            last_played: snapshot
                .files()
                .get(&state_path)
                .map(|state| state.modified),
            play_time_ticks: read_play_time(&snapshot, uuid)?,
        });
    }

    let sync_chunks = match snapshot.files().get(SYNC_CHUNKS_RELATIVE_PATH) {
        None => None,
        Some(state) => {
            if state.size > STRICT_NBT_FILE_BYTES as u64 {
                return Err(RemapError::LimitExceeded(format!(
                    "{SYNC_CHUNKS_RELATIVE_PATH} exceeds {STRICT_NBT_FILE_BYTES} bytes"
                )));
            }
            Some(parse_sync_chunks(
                &snapshot.read_file(SYNC_CHUNKS_RELATIVE_PATH)?,
            )?)
        }
    };

    Ok(SaveScanResult {
        loader_detection: metadata.loader_detection(),
        mc_version_name: metadata.version_name,
        data_version: metadata.data_version,
        server_brands: metadata.server_brands,
        fml_mod_ids: metadata.fml_mod_ids,
        is_hardcore: metadata.hardcore,
        singleplayer_host_uuid: host_uuid,
        players,
        sync_chunks,
        total_bytes: snapshot.total_bytes(),
    })
}

// ---- Owner choices ----

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DualIdentityKeep {
    Rdi,
    Other,
}

/// A save player `other` assigned to the RDI account `rdi`, whose RDI profile is also in the save.
/// The two profiles stay two separate players; the owner chooses which one the account plays as.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DualIdentity {
    pub other: Uuid,
    pub rdi: Uuid,
    pub other_play_time_ticks: Option<i64>,
    pub rdi_play_time_ticks: Option<i64>,
    /// The profile with the longer play time; `Rdi` on a tie or when unknown.
    pub default_keep: DualIdentityKeep,
}

/// Finds assignments whose RDI account already has a profile in the save. `assignments` maps save
/// players to the RDI accounts they belong to.
pub fn find_dual_identities(
    scan: &SaveScanResult,
    assignments: &BTreeMap<Uuid, Uuid>,
) -> Vec<DualIdentity> {
    let players: HashMap<Uuid, &SavePlayer> = scan.players.iter().map(|p| (p.uuid, p)).collect();
    assignments
        .iter()
        .filter_map(|(&other, &rdi)| {
            let rdi_player = players.get(&rdi)?;
            let other_ticks = players.get(&other).and_then(|p| p.play_time_ticks);
            Some(DualIdentity {
                other,
                rdi,
                other_play_time_ticks: other_ticks,
                rdi_play_time_ticks: rdi_player.play_time_ticks,
                default_keep: if other_ticks.unwrap_or(0) > rdi_player.play_time_ticks.unwrap_or(0)
                {
                    DualIdentityKeep::Other
                } else {
                    DualIdentityKeep::Rdi
                },
            })
        })
        .collect()
}

/// Turns the owner's choices into the UUID mapping used by the remapper.
///
/// - A save player assigned to an RDI account whose profile is not in the save is mapped to it.
/// - For a [`DualIdentity`], keeping the RDI profile leaves both untouched. Keeping the other
///   profile swaps the two everywhere, so the account plays as the other profile and the old RDI
///   profile moves to the other UUID.
///
/// Ownership is never merged. `keep` holds the owner's choice per [`DualIdentity::other`]; missing
/// choices use the default.
pub fn build_identity_mapping(
    scan: &SaveScanResult,
    assignments: &BTreeMap<Uuid, Uuid>,
    keep: &HashMap<Uuid, DualIdentityKeep>,
) -> Result<HashMap<Uuid, Uuid>> {
    let players: HashMap<Uuid, &SavePlayer> = scan.players.iter().map(|p| (p.uuid, p)).collect();
    for (source, target) in assignments {
        let player = players
            .get(source)
            .ok_or_else(|| RemapError::Invalid(format!("{source} is not a player of this save")))?;
        if player.kind == SavePlayerKind::Rdi {
            return Err(RemapError::Invalid(format!(
                "{source} is already an RDI profile"
            )));
        }
        if !is_rdi_uuid(*target) {
            return Err(RemapError::Invalid(format!(
                "{target} is not an RDI account UUID"
            )));
        }
    }
    let targets: HashSet<&Uuid> = assignments.values().collect();
    if targets.len() != assignments.len() {
        return Err(RemapError::Invalid(
            "Two save players are assigned to the same account".into(),
        ));
    }
    if !keep.keys().all(|other| assignments.contains_key(other)) {
        return Err(RemapError::Invalid(
            "A dual identity choice names a player without an assignment".into(),
        ));
    }
    let duals: HashMap<Uuid, DualIdentity> = find_dual_identities(scan, assignments)
        .into_iter()
        .map(|d| (d.other, d))
        .collect();
    let mut mapping = HashMap::new();
    for (&source, &target) in assignments {
        match duals.get(&source) {
            None => {
                mapping.insert(source, target);
            }
            Some(dual)
                if keep.get(&source).copied().unwrap_or(dual.default_keep)
                    == DualIdentityKeep::Other =>
            {
                mapping.insert(source, target);
                mapping.insert(target, source);
            }
            Some(_) => {}
        }
    }
    Ok(mapping)
}

// ---- Marking copy identity ----

#[derive(Debug, Clone, PartialEq)]
pub struct SavePlayerPosition {
    pub dimension: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SaveMarkingPlayer {
    pub player: SavePlayer,
    pub position: Option<SavePlayerPosition>,
}

impl SaveMarkingPlayer {
    pub fn display_name(&self) -> String {
        if let Some(name) = &self.player.verified_name {
            return name.clone();
        }
        if let Some(hint) = self.player.name_hints.first() {
            return hint.name.clone();
        }
        if self.player.is_singleplayer_host {
            "单机存档主人".to_string()
        } else {
            format!("角色{}", &self.player.uuid.to_string()[..8])
        }
    }
}

fn player_position(data: &NbtNode<'_>) -> Result<Option<SavePlayerPosition>> {
    let coordinates: Option<Vec<f64>> = data
        .child("Pos")
        .and_then(|n| n.list_elements())
        .map(|elements| {
            elements
                .iter()
                .filter_map(|e| e.double())
                .collect::<Vec<_>>()
        })
        .filter(|values| values.len() == 3 && values.iter().all(|v| v.is_finite()));
    let dimension = match data.child("Dimension") {
        Some(node) => node.string()?,
        None => None,
    };
    Ok(match (coordinates, dimension) {
        (Some(c), Some(dimension)) => Some(SavePlayerPosition {
            dimension,
            x: c[0],
            y: c[1],
            z: c[2],
        }),
        _ => None,
    })
}

/// The roles of a save that the marking copy can be launched as, the owner first, then by last
/// play time. Callers hold the save lock.
pub fn marking_players(world: &Path) -> Result<Vec<SaveMarkingPlayer>> {
    let scan = scan_save(world)?;
    let level = NbtDocument::open_gzip_file(&world.join("level.dat"))?;
    let owner_data = match level.root().unique_child("Data")? {
        Some(data) => data.unique_child("Player")?,
        None => None,
    };
    if let Some(owner) = owner_data {
        let uuid = match owner.unique_child("UUID")? {
            Some(node) => node.uuid(),
            None => None,
        };
        if scan.singleplayer_host_uuid.is_none() || uuid != scan.singleplayer_host_uuid {
            return Err(RemapError::Invalid("单机存档主人的UUID无效".into()));
        }
    }
    let mut players = scan.players.clone();
    // Stable: owner first, then the most recent play; unknown times last.
    players.sort_by(|a, b| {
        b.is_singleplayer_host
            .cmp(&a.is_singleplayer_host)
            .then(b.last_played.cmp(&a.last_played))
    });
    let mut roles = Vec::with_capacity(players.len());
    for player in players {
        let document;
        let data = if player.is_singleplayer_host {
            level
                .root()
                .child("Data")
                .and_then(|d| d.child("Player"))
                .ok_or_else(|| RemapError::Invalid("Required value was null.".into()))?
        } else {
            document = NbtDocument::open_gzip_file(
                &world
                    .join("playerdata")
                    .join(format!("{}.dat", player.uuid)),
            )?;
            document.root()
        };
        let uuid = match data.unique_child("UUID")? {
            Some(node) => node.uuid(),
            None => None,
        };
        if uuid != Some(player.uuid) {
            return Err(RemapError::Invalid(format!(
                "玩家文件与角色UUID不一致：{}",
                player.uuid
            )));
        }
        let position = player_position(&data)?;
        roles.push(SaveMarkingPlayer { player, position });
    }
    Ok(roles)
}

/// Makes `selected` the singleplayer owner of the unpublished staging copy `world`: its data moves
/// into `Data.Player` of `level.dat` (and `level.dat_old`), and the old owner's data is written to
/// its playerdata file. Originals are copied to `backup` (outside the world) before anything is
/// replaced. Returns the original owner. Callers hold the save lock.
pub fn prepare_marking_identity(
    world: &Path,
    selected: Option<Uuid>,
    backup: &Path,
) -> Result<Option<Uuid>> {
    SaveSnapshot::take(world)?; // Reject links before reading or writing any nested path.
    let level_path = world.join("level.dat");
    let level = NbtDocument::open_gzip_file(&level_path)?;
    let owner = level.level_metadata()?.singleplayer_uuid;
    let roles = marking_players(world)?;
    let present = match selected {
        None => roles.is_empty(),
        Some(selected) => roles.iter().any(|role| role.player.uuid == selected),
    };
    if !present {
        return Err(RemapError::Invalid(
            "所选角色已不存在，请重新选择存档".into(),
        ));
    }
    let Some(selected) = selected else {
        return Ok(owner);
    };
    let required = || RemapError::Invalid("Required value was null.".into());
    let owner_payload = level.compound_payload(&["Data", "Player"]);
    let selected_payload = if Some(selected) == owner {
        owner_payload.clone().ok_or_else(required)?
    } else {
        NbtDocument::open_gzip_file(&world.join("playerdata").join(format!("{selected}.dat")))?
            .compound_payload(&[])
            .ok_or_else(required)?
    };
    let mut changes: Vec<(PathBuf, Vec<u8>)> = Vec::new();
    if let Some(owner) = owner {
        let payload = owner_payload.ok_or_else(required)?;
        changes.push((
            world.join("playerdata").join(format!("{owner}.dat")),
            compress::gzip(&NbtDocument::root_document(&payload)),
        ));
    }
    changes.push((
        level_path.clone(),
        compress::gzip(&level.replace_compound(&["Data"], "Player", &selected_payload)?),
    ));
    let old_level = world.join("level.dat_old");
    if fs::symlink_metadata(&old_level).is_ok() {
        let edited = NbtDocument::open_gzip_file(&old_level)?.replace_compound(
            &["Data"],
            "Player",
            &selected_payload,
        )?;
        changes.push((old_level, compress::gzip(&edited)));
    }
    // Finish all backups before any replacement. An interrupted staging copy is never published.
    fs::create_dir_all(backup).ctx(|| backup.display().to_string())?;
    for (path, _) in &changes {
        if fs::symlink_metadata(path).is_err() {
            continue;
        }
        let relative = path
            .strip_prefix(world)
            .expect("changes are inside the world");
        let destination = backup.join(relative);
        if let Some(parent) = destination.parent() {
            fs::create_dir_all(parent).ctx(|| parent.display().to_string())?;
        }
        let mut input = fsutil::open_nofollow(path)?;
        let mut output = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&destination)
            .ctx(|| destination.display().to_string())?;
        std::io::copy(&mut input, &mut output).ctx(|| destination.display().to_string())?;
    }
    for (path, bytes) in &changes {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).ctx(|| parent.display().to_string())?;
        }
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(path)
            .ctx(|| path.display().to_string())?;
        std::io::Write::write_all(&mut file, bytes).ctx(|| path.display().to_string())?;
        file.sync_all().ctx(|| path.display().to_string())?;
    }
    if NbtDocument::read_level_dat(&level_path)?.singleplayer_uuid != Some(selected) {
        return Err(RemapError::Invalid("Failed requirement.".into()));
    }
    Ok(owner)
}

/// The level metadata of a gzip `level.dat`, re-exported for callers that only need prechecks.
pub fn read_level_metadata(level_dat: &Path) -> Result<LevelMetadata> {
    NbtDocument::read_level_dat(level_dat)
}
