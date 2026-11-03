//! Durable, directly playable world checkpoints.

use crate::{
    anvil,
    config::WorldCheckpointLimits,
    mods::{self, ModFingerprint},
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
    /// The hosting player's mods; older clients leave the room's recorded fingerprint as is.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mod_fingerprint: Option<ModFingerprint>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub host_player_id: Option<Uuid>,
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

pub struct Download {
    pub sequence: i64,
    pub cycle_id: Option<Uuid>,
    pub bytes: Vec<u8>,
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
            // One damaged room must not keep every other room offline. Its downloads stay blocked
            // because applied_sequence stays behind last_sequence.
            if let Err(error) = self.recover_host(host) {
                tracing::error!(%host, error = %format!("{error:#}"), "world checkpoint recovery failed");
            }
        }
        Ok(())
    }

    fn recover_host(&self, host: Uuid) -> Result<()> {
        if self.store.get(host)?.is_none() {
            return Ok(());
        }
        let root = self.root(host)?;
        fs::create_dir_all(root.join("journal"))?;
        fs::create_dir_all(root.join("staging"))?;
        let mut state = read_state(&root.join("state.json"))?;
        for (seq, path) in unapplied_journals(&root, state.applied_sequence)? {
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
                    applied_sequence: state.applied_sequence,
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
        Ok(())
    }

    /// Runs `action` only when no checkpoint work is running, or returns `None` at once. Packing a
    /// world can hold this lock for a long time, and a caller holding `commit_gate` must not wait
    /// for that: it would stall every other room.
    pub fn try_while_idle<T>(&self, action: impl FnOnce() -> T) -> Option<T> {
        match self.gate.try_lock() {
            Ok(_guard) => Some(action()),
            Err(std::sync::TryLockError::WouldBlock) => None,
            Err(std::sync::TryLockError::Poisoned(_)) => panic!("checkpoint gate poisoned"),
        }
    }

    /// Finishes every accepted checkpoint that has not reached the playable world yet.
    pub fn apply_pending(&self, host: Uuid) -> Result<()> {
        let _guard = self.gate.lock().expect("checkpoint gate poisoned");
        self.apply_pending_locked(host)
    }

    /// Applying a journal again after a failure is safe: every step overwrites by path or chunk index.
    fn apply_pending_locked(&self, host: Uuid) -> Result<()> {
        let root = self.root(host)?;
        let state = read_state(&root.join("state.json"))?;
        if state.applied_sequence >= state.last_sequence {
            return Ok(());
        }
        let journals = unapplied_journals(&root, state.applied_sequence)?;
        for sequence in state.applied_sequence + 1..=state.last_sequence {
            let (_, path) = journals
                .iter()
                .find(|(seq, _)| *seq == sequence)
                .with_context(|| format!("checkpoint journal {sequence} is missing"))?;
            self.apply_journal(host, sequence, path)?;
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

    /// Validation can take a while on a large upload, so it runs before the session is rechecked.
    pub fn validate_upload(
        &self,
        host: Uuid,
        staged: &Path,
        session: Uuid,
        sequence: i64,
        cycle: Uuid,
    ) -> Result<Metadata> {
        let metadata = read_metadata(staged, &self.limits)?;
        if metadata.host_id != host
            || metadata.session_id != session
            || metadata.sequence != sequence
            || metadata.cycle_id != cycle
        {
            bail!("checkpoint metadata does not match request");
        }
        validate_archive(staged, &metadata, &self.limits)?;
        if let Some(fingerprint) = &metadata.mod_fingerprint {
            fingerprint.validate()?;
        }
        Ok(metadata)
    }

    /// Makes a validated upload durable without touching the playable world; [`Self::apply_pending`]
    /// applies it. Kept short because the caller holds the lock that orders it against host changes.
    /// The returned receipt's `applied_sequence` is the state before applying.
    pub fn accept(
        &self,
        host: Uuid,
        staged: &Path,
        metadata: &Metadata,
        upload_sha1: &str,
        upload_bytes: u64,
    ) -> Result<UploadReceipt> {
        let (session, sequence, cycle) = (metadata.session_id, metadata.sequence, metadata.cycle_id);
        let _guard = self.gate.lock().expect("checkpoint gate poisoned");
        let root = self.root(host)?;
        fs::create_dir_all(root.join("journal"))?;
        fs::create_dir_all(root.join("staging"))?;
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
        // Checked after the retry above, whose journal is already on disk and adds nothing.
        let journal_bytes = fs::read_dir(root.join("journal"))?
            .filter_map(|entry| entry.ok())
            .filter_map(|entry| entry.metadata().ok())
            .map(|metadata| metadata.len())
            .sum::<u64>();
        if journal_bytes.saturating_add(upload_bytes) > self.limits.max_journal_bytes {
            bail!("checkpoint journal is busy")
        }
        if sequence != state.last_sequence + 1 {
            bail!("sequence must be {}", state.last_sequence + 1);
        }
        // The caller finishes earlier checkpoints first, so this is only a concurrent upload's window.
        if state.applied_sequence < state.last_sequence {
            bail!("checkpoint journal is busy")
        }
        if let Some(fingerprint) = &metadata.mod_fingerprint {
            // The registered host is proven by its ticket; the upload's own field is only for sessions without one.
            let updated_by = self
                .store
                .get(host)?
                .and_then(|record| record.last_host_id)
                .or(metadata.host_player_id);
            mods::record(&self.store.host_root(host), fingerprint, updated_by)?;
        }
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
            applied_sequence: state.applied_sequence,
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
        Ok(receipt)
    }

    /// The whole upload path in one call, for tests that do not need a session check in between.
    #[cfg(test)]
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
        let metadata = self.validate_upload(host, staged, session, sequence, cycle)?;
        self.apply_pending(host)?;
        let mut receipt = self.accept(host, staged, &metadata, upload_sha1, upload_bytes)?;
        self.apply_pending(host)?;
        receipt.applied_sequence = sequence;
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
            let written = (|| -> Result<()> {
                let mut output = File::create(&temp)?;
                std::io::copy(&mut member, &mut output)?;
                output.sync_all()?;
                drop(output);
                fs::rename(&temp, &target)?;
                Ok(())
            })();
            if let Err(error) = written {
                // A retry uses a new name, and a leftover would end up in downloaded worlds.
                let _ = fs::remove_file(&temp);
                return Err(error);
            }
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
        let touched: BTreeSet<&str> = metadata
            .chunks
            .iter()
            .map(|chunk| chunk.region.as_str())
            .chain(metadata.clears.iter().map(|clear| clear.region.as_str()))
            .collect();
        for region in touched {
            // Space is reclaimed best effort; a failure leaves a valid, larger region file.
            let path = world.join(region);
            match anvil::compact(&path) {
                Ok(anvil::Compaction::Empty) => {
                    if let Err(error) = fs::remove_file(&path) {
                        tracing::warn!(%error, region, "empty region removal failed");
                    }
                }
                Ok(_) => {}
                Err(error) => tracing::warn!(%error, region, "region compaction failed"),
            }
        }
        let state_path = root.join("state.json");
        let mut state = read_state(&state_path)?;
        state.applied_sequence = sequence;
        if let Some(receipt) = state.last_receipt.as_mut().filter(|r| r.sequence == sequence) {
            receipt.applied_sequence = sequence;
        }
        atomic_write(&state_path, &serde_json::to_vec_pretty(&state)?)?;
        let _ = fs::remove_file(journal);
        Ok(())
    }

    /// Size of the playable world a downloader would receive, if a checkpoint is ready.
    pub fn world_bytes(&self, host: Uuid) -> Result<Option<u64>> {
        let _guard = self.gate.lock().expect("checkpoint gate poisoned");
        let state = read_state(&self.state_path(host)?)?;
        let world = self.world(host)?;
        if !state.baseline_complete || !world.is_dir() {
            return Ok(None);
        }
        let mut total = 0_u64;
        for path in walk_files(&world)? {
            total = total.saturating_add(fs::metadata(path)?.len());
        }
        Ok(Some(total))
    }

    /// Returns the zipped playable world and the checkpoint it reflects.
    /// A world with an unfinished checkpoint may mix old and new data, so it is never served.
    pub fn download(&self, host: Uuid) -> Result<Option<Download>> {
        let _guard = self.gate.lock().expect("checkpoint gate poisoned");
        self.apply_pending_locked(host)?;
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
        Ok(Some(Download {
            sequence: state.applied_sequence,
            cycle_id: state.last_cycle_id,
            bytes: output.into_inner(),
        }))
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
/// Windows only flushes through a handle opened for writing, so this reopens read-write.
fn sync_file(path: &Path) -> Result<()> {
    crate::snapshot_io::resync_file(path)
}
fn read_state(path: &Path) -> Result<State> {
    if !path.is_file() {
        return Ok(State::default());
    }
    Ok(serde_json::from_slice(&fs::read(path)?)?)
}

/// Journals after `applied_sequence`, oldest first.
fn unapplied_journals(root: &Path, applied_sequence: i64) -> Result<Vec<(i64, PathBuf)>> {
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
        if seq > applied_sequence {
            journals.push((seq, item.path()));
        }
    }
    journals.sort_by_key(|v| v.0);
    Ok(journals)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::tempdir;
    use zip::{ZipWriter, write::SimpleFileOptions};

    #[test]
    fn try_while_idle_refuses_instead_of_waiting_for_running_work() {
        let dir = tempdir().unwrap();
        let store = HostStore::open(dir.path()).unwrap();
        let checkpoints = WorldCheckpointStore::new(store, Default::default());
        assert_eq!(checkpoints.try_while_idle(|| 1), Some(1));
        let nested = checkpoints.try_while_idle(|| checkpoints.try_while_idle(|| 1));
        assert_eq!(nested, Some(None));
    }

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
            mod_fingerprint: Some(crate::mods::tests::fingerprint(&[("minecraft", "a")])),
            host_player_id: Some(session),
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
        let room = mods::read(&store.host_root(host)).unwrap().unwrap();
        assert_eq!(room.fingerprint, metadata.mod_fingerprint.clone().unwrap());
        assert_eq!(room.updated_by, Some(session));
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
        let download = checkpoints.download(host).unwrap().unwrap();
        assert_eq!(download.sequence, 1);
        assert_eq!(download.cycle_id, Some(cycle));
        assert_eq!(
            checkpoints.world_bytes(host).unwrap(),
            Some(world.len() as u64)
        );
    }

    struct Room {
        _dir: tempfile::TempDir,
        dir: PathBuf,
        store: HostStore,
        host: Uuid,
        session: Uuid,
        cycle: Uuid,
        checkpoints: WorldCheckpointStore,
    }

    fn room() -> Room {
        let dir = tempdir().unwrap();
        let path = dir.path().to_path_buf();
        let store = HostStore::open(&path).unwrap();
        let host = Uuid::now_v7();
        fs::create_dir(store.host_root(host)).unwrap();
        fs::write(
            store.host_root(host).join("host.json"),
            serde_json::to_vec(&serde_json::json!({"id": host, "name": "room"})).unwrap(),
        )
        .unwrap();
        let checkpoints =
            WorldCheckpointStore::new(store.clone(), WorldCheckpointLimits::default());
        Room {
            _dir: dir,
            dir: path,
            store,
            host,
            session: Uuid::now_v7(),
            cycle: Uuid::now_v7(),
            checkpoints,
        }
    }

    impl Room {
        fn world(&self) -> PathBuf {
            self.store.host_root(self.host).join("world-checkpoint/world")
        }

        /// A checkpoint that writes `files` in order; returns the staged path, its SHA-1 and size.
        fn archive(&self, sequence: i64, files: &[(&str, &str)]) -> (PathBuf, String, u64) {
            let metadata = Metadata {
                format_version: FORMAT_VERSION,
                kind: "world-checkpoint".into(),
                host_id: self.host,
                session_id: self.session,
                cycle_id: self.cycle,
                sequence,
                captured_at: "2026-11-01T00:00:00Z".into(),
                baseline_complete: true,
                files: files
                    .iter()
                    .map(|(path, bytes)| WorldFile {
                        path: (*path).into(),
                        bytes: bytes.len() as u64,
                        sha1: sha1_hex(bytes.as_bytes()),
                    })
                    .collect(),
                removed_files: vec![],
                directories: vec![],
                chunks: vec![],
                clears: vec![],
                mod_fingerprint: None,
                host_player_id: None,
            };
            let path = self.dir.join(format!("upload-{sequence}.zip"));
            let mut output = File::create(&path).unwrap();
            let mut zip = ZipWriter::new(&mut output);
            zip.start_file("metadata.json", SimpleFileOptions::default())
                .unwrap();
            zip.write_all(&serde_json::to_vec(&metadata).unwrap())
                .unwrap();
            for (name, bytes) in files {
                zip.start_file(format!("world/{name}"), SimpleFileOptions::default())
                    .unwrap();
                zip.write_all(bytes.as_bytes()).unwrap();
            }
            zip.finish().unwrap();
            drop(output);
            let sha1 = sha1_hex(&fs::read(&path).unwrap());
            let bytes = fs::metadata(&path).unwrap().len();
            (path, sha1, bytes)
        }

        fn commit(&self, sequence: i64, upload: &(PathBuf, String, u64)) -> Result<UploadReceipt> {
            self.checkpoints.commit(
                self.host,
                &upload.0,
                self.session,
                sequence,
                self.cycle,
                &upload.1,
                upload.2,
            )
        }

        /// Makes writing `name` fail: a file cannot be renamed over a non-empty directory.
        fn block(&self, name: &str) -> PathBuf {
            let blocker = self.world().join(name);
            fs::create_dir_all(blocker.join("blocker")).unwrap();
            blocker
        }

        fn files_in_world(&self) -> BTreeSet<String> {
            walk_files(&self.world())
                .unwrap()
                .iter()
                .map(|path| path.file_name().unwrap().to_string_lossy().into_owned())
                .collect()
        }
    }

    /// Sequence 1 is applied; sequence 2 reaches `a.dat` and fails on the blocked `z.dat`.
    fn room_with_half_applied_checkpoint() -> (Room, (PathBuf, String, u64), PathBuf) {
        let room = room();
        room.commit(1, &room.archive(1, &[("a.dat", "old-a"), ("z.dat", "old-z")]))
            .unwrap();
        fs::remove_file(room.world().join("z.dat")).unwrap();
        let blocker = room.block("z.dat");
        let upload = room.archive(2, &[("a.dat", "new-a"), ("z.dat", "new-z")]);
        assert!(room.commit(2, &upload).is_err());
        (room, upload, blocker)
    }

    #[test]
    fn a_failed_apply_is_reported_and_leaves_the_checkpoint_pending() {
        let (room, _, _) = room_with_half_applied_checkpoint();
        let status = room.checkpoints.status(room.host).unwrap();
        assert_eq!(status.applied_sequence, 1);
        let last = status.last_upload.unwrap();
        assert_eq!(last.sequence, 2);
        assert_eq!(last.applied_sequence, 1, "the stored receipt must not claim it was applied");
        assert_eq!(fs::read(room.world().join("a.dat")).unwrap(), b"new-a");
    }

    #[test]
    fn a_half_applied_world_is_never_downloaded() {
        let (room, _, blocker) = room_with_half_applied_checkpoint();
        assert!(room.checkpoints.download(room.host).is_err());
        fs::remove_dir_all(blocker).unwrap();
        let download = room.checkpoints.download(room.host).unwrap().unwrap();
        assert_eq!(download.sequence, 2);
        assert_eq!(fs::read(room.world().join("z.dat")).unwrap(), b"new-z");
    }

    #[test]
    fn retrying_a_failed_checkpoint_fails_until_it_really_applies() {
        let (room, upload, blocker) = room_with_half_applied_checkpoint();
        assert!(room.commit(2, &upload).is_err());
        assert_eq!(room.checkpoints.status(room.host).unwrap().applied_sequence, 1);
        fs::remove_dir_all(blocker).unwrap();
        let receipt = room.commit(2, &upload).unwrap();
        assert_eq!(receipt.applied_sequence, 2);
        let status = room.checkpoints.status(room.host).unwrap();
        assert_eq!(status.applied_sequence, 2);
        assert_eq!(status.last_upload.unwrap().applied_sequence, 2);
        assert_eq!(fs::read(room.world().join("z.dat")).unwrap(), b"new-z");
    }

    #[test]
    fn a_later_checkpoint_cannot_skip_an_unfinished_one() {
        let (room, _, blocker) = room_with_half_applied_checkpoint();
        let third = room.archive(3, &[("c.dat", "c")]);
        assert!(room.commit(3, &third).is_err());
        assert_eq!(room.checkpoints.status(room.host).unwrap().last_upload.unwrap().sequence, 2);
        assert!(!room.world().join("c.dat").exists());
        fs::remove_dir_all(blocker).unwrap();
        assert_eq!(room.commit(3, &third).unwrap().applied_sequence, 3);
        assert_eq!(fs::read(room.world().join("z.dat")).unwrap(), b"new-z");
        assert_eq!(fs::read(room.world().join("c.dat")).unwrap(), b"c");
    }

    #[test]
    fn accept_refuses_a_new_checkpoint_while_one_is_unfinished() {
        let (room, _, _) = room_with_half_applied_checkpoint();
        let third = room.archive(3, &[("c.dat", "c")]);
        let metadata = room
            .checkpoints
            .validate_upload(room.host, &third.0, room.session, 3, room.cycle)
            .unwrap();
        let error = room
            .checkpoints
            .accept(room.host, &third.0, &metadata, &third.1, third.2)
            .unwrap_err();
        assert!(error.to_string().contains("busy"), "{error}");
    }

    #[test]
    fn a_failed_file_write_leaves_no_temporary_file() {
        let (room, _, _) = room_with_half_applied_checkpoint();
        let leftovers: Vec<_> = room
            .files_in_world()
            .into_iter()
            .filter(|name| name.contains("tmp-"))
            .collect();
        assert!(leftovers.is_empty(), "{leftovers:?}");
    }

    #[test]
    fn recovery_finishes_an_unfinished_checkpoint_and_one_bad_room_does_not_stop_the_rest() {
        let (room, _, blocker) = room_with_half_applied_checkpoint();
        // A second, damaged room next to it.
        let broken = Uuid::now_v7();
        fs::create_dir(room.store.host_root(broken)).unwrap();
        fs::write(
            room.store.host_root(broken).join("host.json"),
            serde_json::to_vec(&serde_json::json!({"id": broken, "name": "broken"})).unwrap(),
        )
        .unwrap();
        let broken_root = room.store.host_root(broken).join("world-checkpoint");
        fs::create_dir_all(&broken_root).unwrap();
        fs::write(broken_root.join("state.json"), b"not json").unwrap();

        assert!(room.checkpoints.recover().is_ok());
        assert_eq!(room.checkpoints.status(room.host).unwrap().applied_sequence, 1);

        fs::remove_dir_all(blocker).unwrap();
        room.checkpoints.recover().unwrap();
        assert_eq!(room.checkpoints.status(room.host).unwrap().applied_sequence, 2);
        assert_eq!(fs::read(room.world().join("z.dat")).unwrap(), b"new-z");
    }

    /// An anvil entry: big-endian length (compression byte + payload), compression byte, payload.
    fn chunk_entry(payload: &[u8]) -> Vec<u8> {
        let mut raw = ((payload.len() + 1) as u32).to_be_bytes().to_vec();
        raw.push(2);
        raw.extend_from_slice(payload);
        raw
    }

    #[test]
    fn replaying_a_checkpoint_that_failed_during_its_chunks_gives_the_complete_world() {
        let room = room();
        room.commit(1, &room.archive(1, &[("level.dat", "level")])).unwrap();
        let first = "region/r.0.0.mca";
        let second = "region/r.1.0.mca";
        let (first_raw, second_raw) = (chunk_entry(b"first"), chunk_entry(b"second"));
        let chunk = |region: &str, raw: &[u8]| ChunkMetadata {
            region: region.into(),
            index: 0,
            timestamp: 100,
            length: raw.len() as u64,
            sha1: sha1_hex(raw),
        };
        let metadata = Metadata {
            format_version: FORMAT_VERSION,
            kind: "world-checkpoint".into(),
            host_id: room.host,
            session_id: room.session,
            cycle_id: room.cycle,
            sequence: 2,
            captured_at: "2026-11-01T00:00:00Z".into(),
            baseline_complete: true,
            files: vec![],
            removed_files: vec![],
            directories: vec![],
            chunks: vec![chunk(first, &first_raw), chunk(second, &second_raw)],
            clears: vec![],
            mod_fingerprint: None,
            host_player_id: None,
        };
        let path = room.dir.join("chunks.zip");
        let mut output = File::create(&path).unwrap();
        let mut zip = ZipWriter::new(&mut output);
        zip.start_file("metadata.json", SimpleFileOptions::default()).unwrap();
        zip.write_all(&serde_json::to_vec(&metadata).unwrap()).unwrap();
        for (region, raw) in [(first, &first_raw), (second, &second_raw)] {
            zip.start_file(format!("regions/{region}/0"), SimpleFileOptions::default()).unwrap();
            zip.write_all(raw).unwrap();
        }
        zip.finish().unwrap();
        drop(output);
        let upload = (path.clone(), sha1_hex(&fs::read(&path).unwrap()), fs::metadata(&path).unwrap().len());

        // The second region cannot be opened, so only the first chunk lands.
        let blocker = room.block(second);
        assert!(room.commit(2, &upload).is_err());
        assert_eq!(room.checkpoints.status(room.host).unwrap().applied_sequence, 1);
        assert!(room.checkpoints.download(room.host).is_err());

        fs::remove_dir_all(blocker).unwrap();
        assert_eq!(room.commit(2, &upload).unwrap().applied_sequence, 2);
        for (region, raw) in [(first, &first_raw), (second, &second_raw)] {
            let file = room.world().join(region);
            let table = anvil::read_table(&file).unwrap();
            assert_eq!(anvil::read_entry(&file, 0, &table).unwrap().as_ref(), Some(raw));
        }
    }

    #[test]
    fn a_missing_journal_blocks_downloads_instead_of_serving_a_partial_world() {
        let (room, _, _) = room_with_half_applied_checkpoint();
        fs::remove_file(
            room.store
                .host_root(room.host)
                .join("world-checkpoint/journal/2.zip"),
        )
        .unwrap();
        let error = room.checkpoints.download(room.host).err().unwrap();
        assert!(error.to_string().contains("missing"), "{error}");
    }
}
