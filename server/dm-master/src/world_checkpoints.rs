//! Durable, directly playable world checkpoints.

use crate::{
    anvil,
    config::WorldCheckpointLimits,
    snapshot_io::{atomic_write, now_millis},
    store::{HostStore, validate_entry_path},
};
use anyhow::{Context, Result, bail};
use sha1::{Digest, Sha1};
use std::{
    collections::BTreeSet,
    fs::{self, File},
    io::Read,
    path::{Path, PathBuf},
    sync::Arc,
};
use uuid::Uuid;
use zip::{ZipArchive, ZipWriter, write::SimpleFileOptions};

pub const API_VERSION: u32 = 3;
pub const FORMAT_VERSION: u32 = 6;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorldFile {
    pub path: String,
    pub bytes: u64,
    pub sha1: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChunkMetadata {
    pub region: String,
    pub index: usize,
    pub timestamp: i32,
    pub length: u64,
    pub sha1: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ClearMetadata {
    pub region: String,
    pub index: usize,
    pub timestamp: i32,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Metadata {
    pub format_version: u32,
    pub kind: String,
    pub host_id: Uuid,
    pub session_id: Uuid,
    pub cycle_id: Uuid,
    pub sequence: i64,
    pub captured_at: String,
    pub baseline_complete: bool,
    #[serde(default)]
    pub files: Vec<WorldFile>,
    #[serde(default)]
    pub removed_files: Vec<String>,
    #[serde(default)]
    pub directories: Vec<String>,
    #[serde(default)]
    pub chunks: Vec<ChunkMetadata>,
    #[serde(default)]
    pub clears: Vec<ClearMetadata>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadReceipt {
    pub api_version: u32,
    pub session_id: Uuid,
    pub sequence: i64,
    pub cycle_id: Uuid,
    pub upload_sha1: String,
    pub upload_zip_bytes: u64,
    pub baseline_complete: bool,
    pub applied_sequence: i64,
    pub stored_at: u64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct State {
    last_sequence: i64,
    last_session_id: Option<Uuid>,
    last_cycle_id: Option<Uuid>,
    applied_sequence: i64,
    baseline_complete: bool,
    last_receipt: Option<UploadReceipt>,
}

#[derive(Debug, Clone, Copy, serde::Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum ErrorReason {
    InvalidRequest,
    SessionUnavailable,
    SequenceConflict,
    StorageError,
    TooLarge,
    Busy,
    NoCheckpoint,
}

#[derive(Clone)]
pub struct WorldCheckpointStore {
    store: HostStore,
    limits: WorldCheckpointLimits,
    gate: Arc<std::sync::Mutex<()>>,
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub last_upload: Option<UploadReceipt>,
    pub applied_sequence: i64,
    pub baseline_complete: bool,
    pub limits: WorldCheckpointLimits,
}

impl WorldCheckpointStore {
    pub fn new(store: HostStore, limits: WorldCheckpointLimits) -> Self {
        Self {
            store,
            limits,
            gate: Arc::new(std::sync::Mutex::new(())),
        }
    }
    pub fn limits(&self) -> WorldCheckpointLimits {
        self.limits
    }
    fn root(&self, host: Uuid) -> Result<PathBuf> {
        if self.store.get(host)?.is_none() {
            bail!("host not found")
        }
        Ok(self.store.host_root(host).join("world-checkpoint"))
    }
    fn world(&self, host: Uuid) -> Result<PathBuf> {
        Ok(self.root(host)?.join("world"))
    }
    fn state_path(&self, host: Uuid) -> Result<PathBuf> {
        Ok(self.root(host)?.join("state.json"))
    }

    pub fn recover(&self) -> Result<()> {
        for entry in fs::read_dir(self.store.root().join("hosts"))? {
            let entry = entry?;
            if !entry.file_type()?.is_dir() {
                continue;
            }
            let Ok(host) = Uuid::parse_str(&entry.file_name().to_string_lossy()) else {
                continue;
            };
            if self.store.get(host)?.is_none() {
                continue;
            }
            let root = self.root(host)?;
            fs::create_dir_all(root.join("journal"))?;
            fs::create_dir_all(root.join("staging"))?;
            let mut state = read_state(&root.join("state.json"))?;
            let mut journals = Vec::new();
            for item in fs::read_dir(root.join("journal"))? {
                let item = item?;
                let Some(name) = item.file_name().to_str().map(str::to_owned) else {
                    continue;
                };
                let Some(seq) = name
                    .strip_suffix(".zip")
                    .and_then(|v| v.parse::<i64>().ok())
                else {
                    continue;
                };
                if seq > state.applied_sequence {
                    journals.push((seq, item.path()));
                }
            }
            journals.sort_by_key(|v| v.0);
            for (seq, path) in journals {
                if seq > state.last_sequence {
                    let metadata = read_metadata(&path, &self.limits)?;
                    let upload_bytes = fs::metadata(&path)?.len();
                    let receipt = UploadReceipt {
                        api_version: API_VERSION,
                        session_id: metadata.session_id,
                        sequence: seq,
                        cycle_id: metadata.cycle_id,
                        upload_sha1: sha1_file(&path)?,
                        upload_zip_bytes: upload_bytes,
                        baseline_complete: metadata.baseline_complete,
                        applied_sequence: seq,
                        stored_at: now_millis(),
                    };
                    state.last_sequence = seq;
                    state.last_session_id = Some(metadata.session_id);
                    state.last_cycle_id = Some(metadata.cycle_id);
                    state.baseline_complete |= metadata.baseline_complete;
                    state.last_receipt = Some(receipt);
                    atomic_write(
                        &root.join("state.json"),
                        &serde_json::to_vec_pretty(&state)?,
                    )?;
                }
                self.apply_journal(host, seq, &path)?;
                state.applied_sequence = seq;
            }
        }
        Ok(())
    }

    pub fn status(&self, host: Uuid) -> Result<Status> {
        let state = read_state(&self.state_path(host)?)?;
        Ok(Status {
            last_upload: state.last_receipt,
            applied_sequence: state.applied_sequence,
            baseline_complete: state.baseline_complete,
            limits: self.limits,
        })
    }

    pub fn files(&self, host: Uuid) -> Result<(Vec<WorldFile>, Vec<String>)> {
        let root = self.world(host)?;
        if !root.is_dir() {
            return Ok((Vec::new(), Vec::new()));
        }
        let mut files = Vec::new();
        let mut dirs = BTreeSet::new();
        walk_world(&root, &root, &mut files, &mut dirs, &self.limits)?;
        Ok((files, dirs.into_iter().collect()))
    }

    pub fn regions(&self, host: Uuid) -> Result<Vec<(String, String)>> {
        let root = self.world(host)?;
        if !root.is_dir() {
            return Ok(Vec::new());
        }
        let mut found = Vec::new();
        for item in walk_files(&root)? {
            if item.extension().and_then(|v| v.to_str()) != Some("mca") {
                continue;
            }
            let relative = item
                .strip_prefix(&root)
                .unwrap()
                .to_string_lossy()
                .replace('\\', "/");
            let table = anvil::read_table(&item)?;
            found.push((relative, table.digest()));
        }
        found.sort_by(|a, b| a.0.cmp(&b.0));
        Ok(found)
    }

    pub fn region_table(&self, host: Uuid, region: &str) -> Result<anvil::RegionTable> {
        validate_region(region)?;
        Ok(anvil::read_table(&self.world(host)?.join(region))?)
    }

    pub fn commit(
        &self,
        host: Uuid,
        staged: &Path,
        session: Uuid,
        sequence: i64,
        cycle: Uuid,
        upload_sha1: &str,
        upload_bytes: u64,
    ) -> Result<UploadReceipt> {
        let _guard = self.gate.lock().expect("checkpoint gate poisoned");
        let root = self.root(host)?;
        fs::create_dir_all(root.join("journal"))?;
        fs::create_dir_all(root.join("staging"))?;
        let journal_bytes = fs::read_dir(root.join("journal"))?
            .filter_map(|entry| entry.ok())
            .filter_map(|entry| entry.metadata().ok())
            .map(|metadata| metadata.len())
            .sum::<u64>();
        if journal_bytes.saturating_add(upload_bytes) > self.limits.max_journal_bytes {
            bail!("checkpoint journal is busy")
        }
        let state_path = root.join("state.json");
        let state = read_state(&state_path)?;
        if let Some(receipt) = state.last_receipt.as_ref().filter(|r| {
            r.session_id == session
                && r.sequence == sequence
                && r.cycle_id == cycle
                && r.upload_sha1 == upload_sha1
        }) {
            return Ok(receipt.clone());
        }
        if sequence != state.last_sequence + 1 {
            bail!("sequence must be {}", state.last_sequence + 1);
        }
        let metadata = read_metadata(staged, &self.limits)?;
        if metadata.host_id != host
            || metadata.session_id != session
            || metadata.sequence != sequence
            || metadata.cycle_id != cycle
        {
            bail!("checkpoint metadata does not match request");
        }
        validate_archive(staged, &metadata, &self.limits)?;
        let journal = root.join("journal").join(format!("{sequence}.zip"));
        fs::copy(staged, &journal)?;
        sync_file(&journal)?;
        let receipt = UploadReceipt {
            api_version: API_VERSION,
            session_id: session,
            sequence,
            cycle_id: cycle,
            upload_sha1: upload_sha1.to_owned(),
            upload_zip_bytes: upload_bytes,
            baseline_complete: metadata.baseline_complete,
            applied_sequence: sequence,
            stored_at: now_millis(),
        };
        let next = State {
            last_sequence: sequence,
            last_session_id: Some(session),
            last_cycle_id: Some(cycle),
            applied_sequence: state.applied_sequence,
            baseline_complete: metadata.baseline_complete || state.baseline_complete,
            last_receipt: Some(receipt.clone()),
        };
        atomic_write(&state_path, &serde_json::to_vec_pretty(&next)?)?;
        self.apply_journal(host, sequence, &journal)?;
        Ok(receipt)
    }

    fn apply_journal(&self, host: Uuid, sequence: i64, journal: &Path) -> Result<()> {
        let root = self.root(host)?;
        let world = root.join("world");
        fs::create_dir_all(&world)?;
        let file = File::open(journal)?;
        let mut archive = ZipArchive::new(file)?;
        let metadata = read_metadata(journal, &self.limits)?;
        for directory in &metadata.directories {
            validate_world_path(directory, &self.limits)?;
            fs::create_dir_all(world.join(directory))?;
        }
        for removed in &metadata.removed_files {
            validate_world_path(removed, &self.limits)?;
            let path = world.join(removed);
            if path.is_file() {
                let garbage = root
                    .join("garbage")
                    .join(format!("{sequence}-{}", Uuid::now_v7()));
                fs::create_dir_all(garbage.parent().unwrap())?;
                fs::rename(path, garbage)?;
            }
        }
        for item in &metadata.files {
            validate_world_path(&item.path, &self.limits)?;
            let mut member = archive.by_name(&format!("world/{}", item.path))?;
            let target = world.join(&item.path);
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)?;
            }
            let temp = target.with_extension(format!("tmp-{}", Uuid::now_v7()));
            let mut output = File::create(&temp)?;
            std::io::copy(&mut member, &mut output)?;
            output.sync_all()?;
            drop(output);
            fs::rename(temp, target)?;
        }
        for chunk in &metadata.chunks {
            validate_region(&chunk.region)?;
            let name = format!("regions/{}/{}", chunk.region, chunk.index);
            let mut member = archive.by_name(&name)?;
            let mut raw = Vec::new();
            member.read_to_end(&mut raw)?;
            anvil::write_entry(&world, &chunk.region, chunk.index, chunk.timestamp, &raw)?;
        }
        for clear in &metadata.clears {
            validate_region(&clear.region)?;
            anvil::clear(&world, &clear.region, clear.index, clear.timestamp)?;
        }
        let state_path = root.join("state.json");
        let mut state = read_state(&state_path)?;
        state.applied_sequence = sequence;
        atomic_write(&state_path, &serde_json::to_vec_pretty(&state)?)?;
        let _ = fs::remove_file(journal);
        Ok(())
    }

    pub fn download(&self, host: Uuid) -> Result<Option<Vec<u8>>> {
        let _guard = self.gate.lock().expect("checkpoint gate poisoned");
        let state = read_state(&self.state_path(host)?)?;
        if !state.baseline_complete {
            return Ok(None);
        }
        let world = self.world(host)?;
        if !world.is_dir() {
            return Ok(None);
        }
        let mut output = std::io::Cursor::new(Vec::new());
        let mut zip = ZipWriter::new(&mut output);
        for path in walk_files(&world)? {
            let relative = path
                .strip_prefix(&world)
                .unwrap()
                .to_string_lossy()
                .replace('\\', "/");
            zip.start_file(relative, SimpleFileOptions::default())?;
            let mut file = File::open(path)?;
            std::io::copy(&mut file, &mut zip)?;
        }
        zip.finish()?;
        Ok(Some(output.into_inner()))
    }
}

fn read_metadata(path: &Path, limits: &WorldCheckpointLimits) -> Result<Metadata> {
    let file = File::open(path)?;
    let mut archive = ZipArchive::new(file)?;
    let mut member = archive.by_name("metadata.json")?;
    if member.size() > limits.max_metadata_bytes {
        bail!("metadata exceeds limit");
    }
    let mut bytes = Vec::new();
    member.read_to_end(&mut bytes)?;
    Ok(serde_json::from_slice(&bytes)?)
}

fn validate_archive(
    path: &Path,
    metadata: &Metadata,
    limits: &WorldCheckpointLimits,
) -> Result<()> {
    if metadata.format_version != FORMAT_VERSION || metadata.kind != "world-checkpoint" {
        bail!("unsupported checkpoint metadata");
    }
    let file = File::open(path)?;
    let mut archive = ZipArchive::new(file)?;
    let mut declared = BTreeSet::new();
    let mut expanded = 0u64;
    for index in 0..archive.len() {
        let member = archive.by_index(index)?;
        let name = member.name().to_owned();
        validate_entry_path(&name)?;
        if !declared.insert(name.clone()) {
            bail!("duplicate ZIP member");
        }
        if !member.is_dir() {
            expanded = expanded
                .checked_add(member.size())
                .context("expanded size overflow")?;
        }
    }
    if expanded > limits.max_world_bytes {
        bail!("checkpoint exceeds world limit");
    }
    if metadata.files.len() + metadata.directories.len() > limits.max_world_entries {
        bail!("checkpoint world entries exceed limit")
    }
    let mut files = BTreeSet::new();
    for item in &metadata.files {
        validate_world_path(&item.path, limits)?;
        if !files.insert(item.path.clone()) {
            bail!("duplicate file metadata");
        }
        if item.bytes > limits.max_file_bytes || !is_sha1(&item.sha1) {
            bail!("invalid file metadata");
        }
        let name = format!("world/{}", item.path);
        if !declared.contains(&name) {
            bail!("file member missing");
        }
        let mut member = archive.by_name(&name)?;
        let mut bytes = Vec::new();
        member.read_to_end(&mut bytes)?;
        if bytes.len() as u64 != item.bytes || sha1_hex(&bytes) != item.sha1 {
            bail!("file member hash does not match metadata");
        }
    }
    for item in &metadata.removed_files {
        validate_world_path(item, limits)?;
    }
    for item in &metadata.directories {
        validate_world_path(item, limits)?;
    }
    for item in &metadata.chunks {
        validate_region(&item.region)?;
        if item.index >= anvil::ENTRY_COUNT || !is_sha1(&item.sha1) {
            bail!("invalid chunk metadata");
        }
        let name = format!("regions/{}/{}", item.region, item.index);
        if !declared.contains(&name) {
            bail!("chunk member missing");
        }
        let mut member = archive.by_name(&name)?;
        let mut bytes = Vec::new();
        member.read_to_end(&mut bytes)?;
        if bytes.len() as u64 != item.length || sha1_hex(&bytes) != item.sha1 {
            bail!("chunk member hash does not match metadata");
        }
        anvil::validate_raw(&bytes)?;
    }
    for item in &metadata.clears {
        validate_region(&item.region)?;
        if item.index >= anvil::ENTRY_COUNT {
            bail!("invalid clear metadata");
        }
    }
    let expected_world: BTreeSet<String> = metadata
        .files
        .iter()
        .map(|item| format!("world/{}", item.path))
        .collect();
    let expected_regions: BTreeSet<String> = metadata
        .chunks
        .iter()
        .map(|item| format!("regions/{}/{}", item.region, item.index))
        .collect();
    for name in declared {
        if name == "metadata.json"
            || expected_world.contains(&name)
            || expected_regions.contains(&name)
        {
            continue;
        }
        bail!("unexpected checkpoint ZIP member")
    }
    Ok(())
}

fn walk_world(
    root: &Path,
    dir: &Path,
    files: &mut Vec<WorldFile>,
    dirs: &mut BTreeSet<String>,
    limits: &WorldCheckpointLimits,
) -> Result<()> {
    for item in fs::read_dir(dir)? {
        let item = item?;
        let path = item.path();
        let relative = path
            .strip_prefix(root)
            .unwrap()
            .to_string_lossy()
            .replace('\\', "/");
        if path.is_dir() {
            if !relative.is_empty() {
                dirs.insert(relative.clone());
            }
            walk_world(root, &path, files, dirs, limits)?;
        } else if path.extension().and_then(|v| v.to_str()) != Some("mca")
            && path.extension().and_then(|v| v.to_str()) != Some("mcc")
        {
            let bytes = fs::read(&path)?;
            if bytes.len() as u64 > limits.max_file_bytes {
                bail!("world file exceeds limit");
            }
            files.push(WorldFile {
                path: relative,
                bytes: bytes.len() as u64,
                sha1: sha1_hex(&bytes),
            });
        }
    }
    files.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(())
}

fn walk_files(root: &Path) -> Result<Vec<PathBuf>> {
    let mut result = Vec::new();
    for item in fs::read_dir(root)? {
        let item = item?;
        let path = item.path();
        if path.is_dir() {
            result.extend(walk_files(&path)?);
        } else {
            result.push(path);
        }
    }
    Ok(result)
}
fn validate_region(region: &str) -> Result<()> {
    validate_entry_path(region)?;
    if !region.ends_with(".mca")
        || region.contains("..")
        || region.split('/').any(|part| part.is_empty() || part == ".")
    {
        bail!("invalid region path")
    }
    Ok(())
}
fn validate_world_path(path: &str, limits: &WorldCheckpointLimits) -> Result<()> {
    validate_entry_path(path)?;
    if path.starts_with('/')
        || path.split('/').count() > limits.max_path_depth
        || path.len() > limits.max_path_bytes
        || path.is_empty()
        || path.split('/').any(|part| part.is_empty() || part == ".")
    {
        bail!("invalid world path")
    }
    Ok(())
}
fn is_sha1(value: &str) -> bool {
    value.len() == 40
        && value
            .bytes()
            .all(|v| v.is_ascii_hexdigit() && !v.is_ascii_uppercase())
}
fn sha1_hex(bytes: &[u8]) -> String {
    let mut h = Sha1::new();
    h.update(bytes);
    hex::encode(h.finalize())
}
fn sha1_file(path: &Path) -> Result<String> {
    let mut file = File::open(path)?;
    let mut hasher = Sha1::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
    }
    Ok(hex::encode(hasher.finalize()))
}
fn sync_file(path: &Path) -> Result<()> {
    File::open(path)?.sync_all()?;
    Ok(())
}
fn read_state(path: &Path) -> Result<State> {
    if !path.is_file() {
        return Ok(State::default());
    }
    Ok(serde_json::from_slice(&fs::read(path)?)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::tempdir;
    use zip::{ZipWriter, write::SimpleFileOptions};

    #[test]
    fn baseline_commit_is_playable_and_idempotent() {
        let dir = tempdir().unwrap();
        let store = HostStore::open(dir.path()).unwrap();
        let host = Uuid::now_v7();
        fs::create_dir(store.host_root(host)).unwrap();
        fs::write(
            store.host_root(host).join("host.json"),
            serde_json::to_vec(&serde_json::json!({"id": host, "name": "room"})).unwrap(),
        )
        .unwrap();
        let session = Uuid::now_v7();
        let cycle = Uuid::now_v7();
        let world = b"level";
        let metadata = Metadata {
            format_version: FORMAT_VERSION,
            kind: "world-checkpoint".into(),
            host_id: host,
            session_id: session,
            cycle_id: cycle,
            sequence: 1,
            captured_at: "2026-10-17T00:00:00Z".into(),
            baseline_complete: true,
            files: vec![WorldFile {
                path: "level.dat".into(),
                bytes: world.len() as u64,
                sha1: sha1_hex(world),
            }],
            removed_files: vec![],
            directories: vec![],
            chunks: vec![],
            clears: vec![],
        };
        let archive_path = dir.path().join("checkpoint.zip");
        let mut output = File::create(&archive_path).unwrap();
        let mut zip = ZipWriter::new(&mut output);
        zip.start_file("metadata.json", SimpleFileOptions::default())
            .unwrap();
        zip.write_all(&serde_json::to_vec(&metadata).unwrap())
            .unwrap();
        zip.start_file("world/level.dat", SimpleFileOptions::default())
            .unwrap();
        zip.write_all(world).unwrap();
        zip.finish().unwrap();
        drop(output);
        let upload_sha1 = sha1_hex(&fs::read(&archive_path).unwrap());
        let checkpoints =
            WorldCheckpointStore::new(store.clone(), WorldCheckpointLimits::default());
        let receipt = checkpoints
            .commit(
                host,
                &archive_path,
                session,
                1,
                cycle,
                &upload_sha1,
                fs::metadata(&archive_path).unwrap().len(),
            )
            .unwrap();
        assert!(receipt.baseline_complete);
        assert_eq!(
            fs::read(
                store
                    .host_root(host)
                    .join("world-checkpoint/world/level.dat")
            )
            .unwrap(),
            world
        );
        let retry = checkpoints
            .commit(
                host,
                &archive_path,
                session,
                1,
                cycle,
                &upload_sha1,
                receipt.upload_zip_bytes,
            )
            .unwrap();
        assert_eq!(retry.upload_sha1, receipt.upload_sha1);
        assert!(checkpoints.download(host).unwrap().is_some());
    }
}
