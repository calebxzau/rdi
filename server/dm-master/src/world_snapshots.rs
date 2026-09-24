#![allow(clippy::too_many_arguments)]

//! World Snapshot v5: the single production synchronization protocol.
//!
//! One upload carries the authoritative WorldData manifest for the cycle plus only the
//! members whose content changed relative to a client-pinned base snapshot. Master
//! validates the upload and publishes a complete immutable directory. Unchanged members
//! use hard links; snapshots never depend on an older snapshot directory. Download/export
//! is deliberately unavailable in this storage API version.
//!
//! Nothing here claims the snapshot is restore-safe or that it was taken within a single
//! server tick; `restoreSafe` is always false and `captureMode` is always
//! `memory-and-files`.

use crate::{
    config::WorldSnapshotLimits,
    snapshot_io::{
        TempDirectory, atomic_write, hex_lower, now_millis, publish_directory, read_bounded,
        remove_directory_later, sha1_file, sync_directory_tree,
    },
    store::{HostStore, validate_entry_path},
    sync_chunks::{SyncChunkSnapshot, validate_dimension_id},
};
use anyhow::{Context, Result, bail};
use sha1::{Digest, Sha1};
use std::{
    collections::{BTreeMap, BTreeSet, HashMap, HashSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};
use uuid::Uuid;
use zip::ZipArchive;

pub const FORMAT_VERSION: u32 = 5;
pub const KIND: &str = "world-snapshot";
pub const CAPTURE_MODE: &str = "memory-and-files";
pub const METADATA_ENTRY: &str = "metadata.json";
pub const WORLD_PREFIX: &str = "world/";
pub const CHUNK_PREFIX: &str = "chunks/";
pub const API_VERSION: u32 = 2;
const DIRECTORY_NAME: &str = "world-snapshots";
const STATE_NAME: &str = "state.json";
const STORAGE_DIRECTORY: &str = "directories-v1";
const SNAPSHOTS_DIRECTORY: &str = "snapshots";
const STAGING_DIRECTORY: &str = "staging";
const MANIFEST_ENTRY: &str = "manifest.json";
const GARBAGE_DIRECTORY: &str = "garbage";

type ColumnKey = (String, i32, i32);

// ---------------------------------------------------------------------------
// Wire model
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(deny_unknown_fields)]
pub struct WorldFile {
    pub path: String,
    pub bytes: u64,
    pub sha1: String,
}

#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(deny_unknown_fields)]
pub struct WorldMetadata {
    #[serde(default)]
    pub excluded_files: Vec<String>,
    #[serde(default)]
    pub excluded_directories: Vec<String>,
    #[serde(default)]
    pub directories: Vec<String>,
    #[serde(default)]
    pub files: Vec<WorldFile>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(deny_unknown_fields)]
pub struct ChunkMetadata {
    pub dimension_id: String,
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub entities_present: bool,
    pub poi_present: bool,
    /// Column content digest, framed by the client over kind/presence/length/content.
    pub sha1: String,
    /// Stamped by Master from the authoritative roster; a client-supplied value is ignored.
    #[serde(default)]
    pub membership_revision: i64,
    /// Last cycle in which this column was actually observed.
    #[serde(default)]
    pub captured_at: Option<String>,
}

impl ChunkMetadata {
    fn key(&self) -> ColumnKey {
        (self.dimension_id.clone(), self.chunk_x, self.chunk_z)
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(deny_unknown_fields)]
pub struct Metadata {
    pub format_version: u32,
    pub kind: String,
    pub host_id: Uuid,
    pub session_id: Uuid,
    pub cycle_id: Uuid,
    pub sequence: i64,
    pub base_snapshot_id: Option<Uuid>,
    pub sync_chunk_revision: i64,
    pub captured_at: String,
    pub capture_mode: String,
    pub restore_safe: bool,
    pub world: WorldMetadata,
    pub chunks: Vec<ChunkMetadata>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(deny_unknown_fields)]
pub struct SnapshotRecord {
    pub snapshot_id: Uuid,
    pub session_id: Uuid,
    pub sequence: i64,
    pub sync_chunk_revision: i64,
    pub cycle_id: Uuid,
    pub manifest_sha1: String,
    pub expanded_bytes: u64,
    pub world_files: usize,
    pub world_bytes: u64,
    pub stored_columns: usize,
    pub total_columns: usize,
    pub complete: bool,
    pub captured_at: String,
    pub stored_at: u64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(deny_unknown_fields)]
pub struct UploadReceipt {
    pub snapshot_id: Uuid,
    pub session_id: Uuid,
    pub sequence: i64,
    pub sync_chunk_revision: i64,
    pub cycle_id: Uuid,
    /// Digest and size of the bytes the client sent for this cycle.
    pub upload_sha1: String,
    pub upload_zip_bytes: u64,
    pub manifest_sha1: String,
    pub expanded_bytes: u64,
    pub api_version: u32,
    pub world_files: usize,
    pub world_bytes: u64,
    pub observed_columns: usize,
    pub updated_columns: usize,
    pub stored_columns: usize,
    pub total_columns: usize,
    pub complete: bool,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct PointerState {
    storage_format_version: u32,
    latest: Option<SnapshotRecord>,
    previous: Option<SnapshotRecord>,
    #[serde(default)]
    last_upload: Option<UploadReceipt>,
}

impl Default for PointerState {
    fn default() -> Self {
        Self {
            storage_format_version: 1,
            latest: None,
            previous: None,
            last_upload: None,
        }
    }
}

#[derive(Debug, Clone, Copy, serde::Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum ErrorReason {
    InvalidRequest,
    SessionUnavailable,
    RevisionConflict,
    SequenceConflict,
    BaseConflict,
    StorageError,
    TooLarge,
    Busy,
    SnapshotNotFound,
    DownloadUnavailable,
}

#[derive(Debug)]
pub enum CommitError {
    Invalid(anyhow::Error),
    RevisionConflict,
    SequenceConflict,
    BaseConflict,
    TooLarge(anyhow::Error),
    Storage(anyhow::Error),
}

#[derive(Debug)]
pub enum CommitOutcome {
    Committed(UploadReceipt),
    Idempotent(UploadReceipt),
}

#[derive(Debug, Clone)]
pub struct Status {
    pub latest: Option<SnapshotRecord>,
    pub last_upload: Option<UploadReceipt>,
    pub stored_columns: usize,
    pub total_columns: usize,
    pub complete: bool,
}

/// Result of validating the uploaded ZIP without touching published state.
pub struct ValidatedUpload {
    metadata: Metadata,
    upload_sha1: String,
    upload_zip_bytes: u64,
    /// `world/<path>` members actually present in the upload.
    world_payloads: BTreeSet<String>,
    /// Columns whose full component set is present in the upload.
    updated_columns: BTreeSet<ColumnKey>,
}

impl ValidatedUpload {
    pub fn session_id(&self) -> Uuid {
        self.metadata.session_id
    }
    pub fn sequence(&self) -> i64 {
        self.metadata.sequence
    }
    pub fn cycle_id(&self) -> Uuid {
        self.metadata.cycle_id
    }
    pub fn sync_chunk_revision(&self) -> i64 {
        self.metadata.sync_chunk_revision
    }
    pub fn upload_zip_bytes(&self) -> u64 {
        self.upload_zip_bytes
    }

    /// Every observed column must belong to the authoritative roster.
    pub fn validate_subset(&self, chunks: &SyncChunkSnapshot) -> Result<()> {
        let roster = distinct_chunks(chunks);
        for chunk in &self.metadata.chunks {
            if !roster.contains(&chunk.key()) {
                bail!("world snapshot contains an out-of-roster column")
            }
        }
        Ok(())
    }
}

/// A durable complete directory waiting for the commit gate.
pub struct Candidate {
    temp: TempDirectory,
    base_snapshot_id: Option<Uuid>,
    metadata: Metadata,
    manifest_sha1: String,
    expanded_bytes: u64,
    upload_sha1: String,
    upload_zip_bytes: u64,
    world_files: usize,
    world_bytes: u64,
    observed_columns: usize,
    updated_columns: usize,
    stored_columns: usize,
    total_columns: usize,
    _base_pin: Option<BasePin>,
    pub io_stats: DirectoryIoStats,
}

impl Candidate {
    pub fn expanded_bytes(&self) -> u64 {
        self.expanded_bytes
    }
}

pub enum Prepared {
    Candidate(Box<Candidate>),
    /// The exact same upload was already committed; no rebuild is needed.
    Idempotent(UploadReceipt),
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct WorldSnapshotStore {
    store: HostStore,
    limits: WorldSnapshotLimits,
    pins: Arc<PinRegistry>,
}

struct PinRegistry {
    pins: Mutex<HashMap<(Uuid, Uuid), usize>>,
}

struct BasePin {
    registry: Arc<PinRegistry>,
    host_id: Uuid,
    snapshot_id: Uuid,
    directory: PathBuf,
}

impl BasePin {
    fn new(
        registry: &Arc<PinRegistry>,
        host_id: Uuid,
        snapshot_id: Uuid,
        directory: &Path,
    ) -> Self {
        Self {
            registry: Arc::clone(registry),
            host_id,
            snapshot_id,
            directory: directory.to_owned(),
        }
    }
}

impl Drop for BasePin {
    fn drop(&mut self) {
        let should_cleanup = {
            let mut pins = self
                .registry
                .pins
                .lock()
                .expect("world snapshot pin registry poisoned");
            let key = (self.host_id, self.snapshot_id);
            match pins.get_mut(&key) {
                Some(count) if *count > 1 => {
                    *count -= 1;
                    false
                }
                Some(_) => {
                    pins.remove(&key);
                    true
                }
                None => false,
            }
        };
        if should_cleanup {
            let pins = self
                .registry
                .pins
                .lock()
                .expect("world snapshot pin registry poisoned");
            if let Ok(state) = read_state(&self.directory.join(STATE_NAME)) {
                cleanup_archives_locked(&self.directory, &state, &pins, self.host_id);
            }
        }
    }
}

impl WorldSnapshotStore {
    pub fn new(store: HostStore, limits: WorldSnapshotLimits) -> Self {
        Self {
            store,
            limits,
            pins: Arc::new(PinRegistry {
                pins: Mutex::new(HashMap::new()),
            }),
        }
    }

    pub fn limits(&self) -> WorldSnapshotLimits {
        self.limits
    }

    fn directory(&self, host_id: Uuid) -> Result<PathBuf> {
        let root = self.store.host_root(host_id);
        if !root.is_dir() || self.store.get(host_id)?.is_none() {
            bail!("host not found")
        }
        Ok(root.join(DIRECTORY_NAME).join(STORAGE_DIRECTORY))
    }

    fn snapshot_for(&self, directory: &Path, snapshot_id: Uuid) -> PathBuf {
        directory
            .join(SNAPSHOTS_DIRECTORY)
            .join(snapshot_id.to_string())
    }

    fn staging_for(&self, directory: &Path, snapshot_id: Uuid) -> PathBuf {
        directory
            .join(STAGING_DIRECTORY)
            .join(snapshot_id.to_string())
    }

    /// Runs before accepting requests. Only this storage version's owned UUID
    /// directories are eligible; legacy ZIP/state files are deliberately untouched.
    pub fn recover(&self) -> Result<()> {
        for entry in fs::read_dir(self.store.root().join("hosts"))? {
            let entry = entry?;
            if !entry.file_type()?.is_dir() {
                continue;
            }
            let Ok(host_id) = Uuid::parse_str(&entry.file_name().to_string_lossy()) else {
                continue;
            };
            if self.store.get(host_id)?.is_none() {
                continue;
            }
            let directory = self.directory(host_id)?;
            match fs::symlink_metadata(&directory) {
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
                Err(error) => return Err(error.into()),
                Ok(stat) if !stat.is_dir() => bail!("snapshot storage root is not a directory"),
                _ => {}
            }
            let state = read_state(&directory.join(STATE_NAME))?;
            for record in state.latest.iter().chain(&state.previous) {
                pin_base(
                    &self.snapshot_for(&directory, record.snapshot_id),
                    record,
                    &self.limits,
                )?;
            }
            cleanup_archives(&directory, &state, &self.pins, host_id);
            for child in [STAGING_DIRECTORY, GARBAGE_DIRECTORY] {
                let parent = directory.join(child);
                if !parent.try_exists()? {
                    continue;
                }
                if !fs::symlink_metadata(&parent)?.is_dir() {
                    bail!("snapshot work root is not a directory")
                }
                for entry in fs::read_dir(parent)? {
                    let entry = entry?;
                    if entry.file_type()?.is_dir()
                        && owned_id(&entry.file_name().to_string_lossy()).is_some()
                    {
                        remove_directory_later(entry.path());
                    }
                }
            }
        }
        Ok(())
    }

    fn pin_retained(
        &self,
        host_id: Uuid,
        requested: Option<Uuid>,
    ) -> Result<Option<(SnapshotRecord, PathBuf, BasePin)>> {
        let directory = self.directory(host_id)?;
        let mut pins = self
            .pins
            .pins
            .lock()
            .expect("world snapshot pin registry poisoned");
        let state = read_state(&directory.join(STATE_NAME))?;
        let record = if let Some(id) = requested {
            state
                .latest
                .iter()
                .chain(&state.previous)
                .find(|record| record.snapshot_id == id)
        } else {
            state.latest.as_ref()
        };
        let Some(record) = record.cloned() else {
            return Ok(None);
        };
        let path = self.snapshot_for(&directory, record.snapshot_id);
        *pins.entry((host_id, record.snapshot_id)).or_default() += 1;
        let pin = BasePin::new(&self.pins, host_id, record.snapshot_id, &directory);
        Ok(Some((record, path, pin)))
    }

    /// Validates the uploaded ZIP against the request headers. Pure read of `path`.
    pub fn validate_upload(
        &self,
        path: &Path,
        host_id: Uuid,
        session_id: Uuid,
        sequence: i64,
        revision: i64,
        cycle_id: Uuid,
        declared_sha1: &str,
    ) -> Result<ValidatedUpload> {
        let upload_zip_bytes = fs::metadata(path)?.len();
        if upload_zip_bytes > self.limits.max_upload_bytes {
            bail!("world snapshot upload exceeds the upload limit")
        }
        let upload_sha1 = sha1_file(path)?;
        if upload_sha1 != declared_sha1 {
            bail!("world snapshot SHA-1 does not match header")
        }
        let metadata = read_archive_metadata(path, &self.limits)?;
        check_metadata_shape(&metadata, &self.limits)?;
        if metadata.host_id != host_id
            || metadata.session_id != session_id
            || metadata.sequence != sequence
            || metadata.sync_chunk_revision != revision
            || metadata.cycle_id != cycle_id
        {
            bail!("world snapshot metadata does not match request")
        }
        let members = scan_members(path, &metadata, &self.limits, true)?;
        verify_columns(path, &metadata, &members.full_columns, &self.limits)?;
        Ok(ValidatedUpload {
            metadata,
            upload_sha1,
            upload_zip_bytes,
            world_payloads: members.world_payloads,
            updated_columns: members.full_columns,
        })
    }

    /// Pins the base snapshot and assembles a complete candidate directory.
    ///
    /// Runs outside the commit gate: it only reads published state and writes a private
    /// temporary directory whose ownership moves into the returned [`Candidate`].
    pub fn prepare(
        &self,
        host_id: Uuid,
        validated: &ValidatedUpload,
        staged: &Path,
        chunks: &SyncChunkSnapshot,
    ) -> std::result::Result<Prepared, CommitError> {
        let directory = self.directory(host_id).map_err(CommitError::Storage)?;
        fs::create_dir_all(&directory).map_err(|error| CommitError::Storage(error.into()))?;
        fs::create_dir_all(directory.join(SNAPSHOTS_DIRECTORY))
            .map_err(|error| CommitError::Storage(error.into()))?;
        fs::create_dir_all(directory.join(STAGING_DIRECTORY))
            .map_err(|error| CommitError::Storage(error.into()))?;
        let state_path = directory.join(STATE_NAME);
        let (state, base) = {
            // State selection and pin registration share the cleanup registry lock. This
            // closes the gap where cleanup could unlink the selected archive before it is
            // owned by the candidate.
            let mut pins = self
                .pins
                .pins
                .lock()
                .expect("world snapshot pin registry poisoned");
            let state = read_state(&state_path).map_err(CommitError::Storage)?;

            if let Some(receipt) = idempotent_match(&state, validated) {
                return Ok(Prepared::Idempotent(receipt));
            }
            check_sequence(&state, validated)?;

            let base_snapshot_id = state.latest.as_ref().map(|record| record.snapshot_id);
            if validated.metadata.base_snapshot_id != base_snapshot_id {
                return Err(CommitError::BaseConflict);
            }
            let base = match state.latest.as_ref() {
                Some(record) => {
                    let path = self.snapshot_for(&directory, record.snapshot_id);
                    let pin = BasePin::new(&self.pins, host_id, record.snapshot_id, &directory);
                    *pins.entry((host_id, record.snapshot_id)).or_default() += 1;
                    Some((path, record.clone(), pin))
                }
                None => None,
            };
            (state, base)
        };
        let base_snapshot_id = state.latest.as_ref().map(|record| record.snapshot_id);
        let base_metadata = match base.as_ref() {
            Some((path, record, _pin)) => {
                pin_base(path, record, &self.limits).map_err(CommitError::Storage)?;
                Some(read_directory_metadata(path, &self.limits).map_err(CommitError::Storage)?)
            }
            None => None,
        };

        let plan = build_merge_plan(
            &validated.metadata,
            &validated.world_payloads,
            &validated.updated_columns,
            base_metadata.as_ref(),
            chunks,
        )
        .map_err(CommitError::Invalid)?;

        let candidate_id = Uuid::now_v7();
        let directory_temp = TempDirectory::new(self.staging_for(&directory, candidate_id))
            .map_err(CommitError::Storage)?;
        let (manifest, expanded_bytes, io_stats) = materialize_directory(
            directory_temp.path(),
            &plan,
            staged,
            base.as_ref().map(|(path, _, _)| path.as_path()),
            &self.limits,
        )
        .map_err(|error| {
            if error.downcast_ref::<SnapshotTooLarge>().is_some() {
                CommitError::TooLarge(error)
            } else {
                CommitError::Storage(error)
            }
        })?;
        let manifest_sha1 = sha1_bytes(&manifest);
        write_new_file(&directory_temp.path().join(MANIFEST_ENTRY), &manifest)
            .map_err(CommitError::Storage)?;
        sync_directory_tree(directory_temp.path()).map_err(CommitError::Storage)?;
        // Persist the layout ancestors as well; this may be the host's first snapshot.
        for path in [
            directory.join(STAGING_DIRECTORY),
            directory.join(SNAPSHOTS_DIRECTORY),
            directory.clone(),
            directory.parent().unwrap().to_owned(),
            directory.parent().unwrap().parent().unwrap().to_owned(),
        ] {
            crate::snapshot_io::sync_parent(&path).map_err(CommitError::Storage)?;
        }

        let world_files = plan.metadata.world.files.len();
        let world_bytes = plan
            .metadata
            .world
            .files
            .iter()
            .try_fold(0_u64, |total, file| total.checked_add(file.bytes))
            .context("world snapshot byte total overflow")
            .map_err(CommitError::Invalid)?;
        let stored_columns = plan.metadata.chunks.len();

        Ok(Prepared::Candidate(Box::new(Candidate {
            temp: directory_temp,
            io_stats,
            base_snapshot_id,
            manifest_sha1,
            expanded_bytes,
            upload_sha1: validated.upload_sha1.clone(),
            upload_zip_bytes: validated.upload_zip_bytes,
            world_files,
            world_bytes,
            observed_columns: validated.metadata.chunks.len(),
            updated_columns: validated.updated_columns.len(),
            stored_columns,
            total_columns: plan.total_columns,
            metadata: plan.metadata,
            _base_pin: base.map(|(_, _, pin)| pin),
        })))
    }

    /// Publishes a prepared candidate. Callers hold the commit gate for this call.
    pub fn commit(
        &self,
        host_id: Uuid,
        candidate: Candidate,
        chunks: &SyncChunkSnapshot,
    ) -> std::result::Result<CommitOutcome, CommitError> {
        self.commit_with_state_writer(host_id, candidate, chunks, atomic_write)
    }

    fn commit_with_state_writer(
        &self,
        host_id: Uuid,
        candidate: Candidate,
        chunks: &SyncChunkSnapshot,
        write_state: impl FnOnce(&Path, &[u8]) -> Result<()>,
    ) -> std::result::Result<CommitOutcome, CommitError> {
        let directory = self.directory(host_id).map_err(CommitError::Storage)?;
        let state_path = directory.join(STATE_NAME);
        let state = read_state(&state_path).map_err(CommitError::Storage)?;

        // The roster must not have moved between preparation and publication.
        if chunks.revision != candidate.metadata.sync_chunk_revision
            || distinct_chunks(chunks).len() != candidate.total_columns
        {
            return Err(CommitError::RevisionConflict);
        }
        if let Some(receipt) = idempotent_match_parts(
            &state,
            candidate.metadata.session_id,
            candidate.metadata.sequence,
            candidate.metadata.cycle_id,
            &candidate.upload_sha1,
        ) {
            return Ok(CommitOutcome::Idempotent(receipt));
        }
        check_sequence_parts(
            &state,
            candidate.metadata.session_id,
            candidate.metadata.sequence,
        )?;
        if state.latest.as_ref().map(|record| record.snapshot_id) != candidate.base_snapshot_id {
            return Err(CommitError::BaseConflict);
        }

        let snapshot_id = Uuid::now_v7();
        let record = SnapshotRecord {
            snapshot_id,
            session_id: candidate.metadata.session_id,
            sequence: candidate.metadata.sequence,
            sync_chunk_revision: candidate.metadata.sync_chunk_revision,
            cycle_id: candidate.metadata.cycle_id,
            manifest_sha1: candidate.manifest_sha1.clone(),
            expanded_bytes: candidate.expanded_bytes,
            world_files: candidate.world_files,
            world_bytes: candidate.world_bytes,
            stored_columns: candidate.stored_columns,
            total_columns: candidate.total_columns,
            complete: candidate.stored_columns == candidate.total_columns,
            captured_at: candidate.metadata.captured_at.clone(),
            stored_at: now_millis(),
        };
        let receipt = UploadReceipt {
            snapshot_id,
            session_id: record.session_id,
            sequence: record.sequence,
            sync_chunk_revision: record.sync_chunk_revision,
            cycle_id: record.cycle_id,
            upload_sha1: candidate.upload_sha1.clone(),
            upload_zip_bytes: candidate.upload_zip_bytes,
            manifest_sha1: record.manifest_sha1.clone(),
            expanded_bytes: record.expanded_bytes,
            api_version: API_VERSION,
            world_files: record.world_files,
            world_bytes: record.world_bytes,
            observed_columns: candidate.observed_columns,
            updated_columns: candidate.updated_columns,
            stored_columns: record.stored_columns,
            total_columns: record.total_columns,
            complete: record.complete,
        };

        let pins = self
            .pins
            .pins
            .lock()
            .expect("world snapshot pin registry poisoned");
        let archive = self.snapshot_for(&directory, snapshot_id);
        let staged = candidate.temp.release();
        let published = publish_directory(&staged, &archive);
        if let Err(error) = published {
            remove_directory_later(staged);
            cleanup_owned_archive(&archive, &state_path, snapshot_id);
            return Err(CommitError::Storage(error));
        }
        let next = PointerState {
            storage_format_version: 1,
            latest: Some(record),
            previous: state.latest.clone(),
            last_upload: Some(receipt.clone()),
        };
        let bytes = match serde_json::to_vec_pretty(&next) {
            Ok(bytes) => bytes,
            Err(error) => {
                cleanup_owned_archive(&archive, &state_path, snapshot_id);
                return Err(CommitError::Storage(error.into()));
            }
        };
        if let Err(error) = write_state(&state_path, &bytes) {
            cleanup_owned_archive(&archive, &state_path, snapshot_id);
            return Err(CommitError::Storage(error));
        }
        cleanup_archives_locked(&directory, &next, &pins, host_id);
        drop(pins);
        Ok(CommitOutcome::Committed(receipt))
    }

    pub fn status(&self, host_id: Uuid, chunks: &SyncChunkSnapshot) -> Result<Status> {
        let directory = self.directory(host_id)?;
        let (state, _pin) = {
            let mut pins = self
                .pins
                .pins
                .lock()
                .expect("world snapshot pin registry poisoned");
            let state = read_state(&directory.join(STATE_NAME))?;
            let pin = state.latest.as_ref().map(|record| {
                *pins.entry((host_id, record.snapshot_id)).or_default() += 1;
                BasePin::new(&self.pins, host_id, record.snapshot_id, &directory)
            });
            (state, pin)
        };
        let total_columns = distinct_chunks(chunks).len();
        let Some(record) = state.latest.clone() else {
            return Ok(Status {
                latest: None,
                last_upload: state.last_upload,
                stored_columns: 0,
                total_columns,
                complete: total_columns == 0,
            });
        };
        let path = self.snapshot_for(&directory, record.snapshot_id);
        // A latest pointer with a missing or corrupted archive is a storage failure,
        // never an empty/partial coverage result.
        pin_base(&path, &record, &self.limits)?;
        let metadata = read_directory_metadata(&path, &self.limits)?;
        let stored_columns = valid_column_count(&metadata.chunks, chunks);
        Ok(Status {
            latest: Some(record),
            last_upload: state.last_upload,
            stored_columns,
            total_columns,
            complete: stored_columns == total_columns,
        })
    }

    /// Retained directories are not downloadable in API v2.
    pub fn retained_archive(&self, host_id: Uuid, snapshot_id: Uuid) -> Result<Option<PathBuf>> {
        let directory = self.directory(host_id)?;
        let state = read_state(&directory.join(STATE_NAME))?;
        let retained = state
            .latest
            .iter()
            .chain(state.previous.iter())
            .any(|record| record.snapshot_id == snapshot_id);
        if !retained {
            return Ok(None);
        }
        let path = self.snapshot_for(&directory, snapshot_id);
        Ok(path.is_dir().then_some(path))
    }

    pub fn manifest(&self, host_id: Uuid, snapshot_id: Uuid) -> Result<Option<Metadata>> {
        let Some((record, path, _pin)) = self.pin_retained(host_id, Some(snapshot_id))? else {
            return Ok(None);
        };
        pin_base(&path, &record, &self.limits)?;
        Ok(Some(read_directory_metadata(&path, &self.limits)?))
    }

    pub fn latest_id(&self, host_id: Uuid) -> Result<Option<Uuid>> {
        let directory = self.directory(host_id)?;
        Ok(read_state(&directory.join(STATE_NAME))?
            .latest
            .map(|record| record.snapshot_id))
    }
}

// ---------------------------------------------------------------------------
// Idempotency and sequencing
// ---------------------------------------------------------------------------

fn idempotent_match(state: &PointerState, validated: &ValidatedUpload) -> Option<UploadReceipt> {
    idempotent_match_parts(
        state,
        validated.metadata.session_id,
        validated.metadata.sequence,
        validated.metadata.cycle_id,
        &validated.upload_sha1,
    )
}

fn idempotent_match_parts(
    state: &PointerState,
    session_id: Uuid,
    sequence: i64,
    cycle_id: Uuid,
    upload_sha1: &str,
) -> Option<UploadReceipt> {
    let existing = state.last_upload.as_ref()?;
    (existing.session_id == session_id
        && existing.sequence == sequence
        && existing.cycle_id == cycle_id
        && existing.upload_sha1 == upload_sha1)
        .then(|| existing.clone())
}

fn check_sequence(
    state: &PointerState,
    validated: &ValidatedUpload,
) -> std::result::Result<(), CommitError> {
    check_sequence_parts(
        state,
        validated.metadata.session_id,
        validated.metadata.sequence,
    )
}

fn check_sequence_parts(
    state: &PointerState,
    session_id: Uuid,
    sequence: i64,
) -> std::result::Result<(), CommitError> {
    let Some(existing) = state.last_upload.as_ref() else {
        return Ok(());
    };
    if existing.session_id != session_id {
        return Ok(());
    }
    // Equal sequence with different content, or any older sequence, is a conflict.
    if sequence <= existing.sequence {
        return Err(CommitError::SequenceConflict);
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Merge planning
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Source {
    Incoming,
    Base,
}

struct MergePlan {
    metadata: Metadata,
    /// Output entry name -> archive it is copied from. Names are identical in both.
    entries: BTreeMap<String, Source>,
    total_columns: usize,
}

fn build_merge_plan(
    incoming: &Metadata,
    world_payloads: &BTreeSet<String>,
    updated_columns: &BTreeSet<ColumnKey>,
    base: Option<&Metadata>,
    chunks: &SyncChunkSnapshot,
) -> Result<MergePlan> {
    let roster = column_membership(chunks);
    let base_files: HashMap<&str, &WorldFile> = base
        .map(|metadata| {
            metadata
                .world
                .files
                .iter()
                .map(|file| (file.path.as_str(), file))
                .collect()
        })
        .unwrap_or_default();
    let base_columns: HashMap<ColumnKey, &ChunkMetadata> = base
        .map(|metadata| {
            metadata
                .chunks
                .iter()
                .map(|chunk| (chunk.key(), chunk))
                .collect()
        })
        .unwrap_or_default();

    let mut entries = BTreeMap::new();

    for file in &incoming.world.files {
        let name = format!("{WORLD_PREFIX}{}", file.path);
        if world_payloads.contains(&name) {
            entries.insert(name, Source::Incoming);
            continue;
        }
        let Some(previous) = base_files.get(file.path.as_str()) else {
            bail!(
                "world snapshot omits payload for a file that the base does not contain: {}",
                file.path
            )
        };
        if previous.bytes != file.bytes || previous.sha1 != file.sha1 {
            bail!(
                "world snapshot omits payload for a changed file: {}",
                file.path
            )
        }
        entries.insert(name, Source::Base);
    }

    let mut merged: BTreeMap<ColumnKey, ChunkMetadata> = BTreeMap::new();
    for chunk in &incoming.chunks {
        let key = chunk.key();
        let Some(membership) = roster.get(&key).copied() else {
            bail!("world snapshot contains an out-of-roster column")
        };
        let mut stored = chunk.clone();
        stored.membership_revision = membership;
        // Every observed column carries this cycle's timestamp, changed or not.
        stored.captured_at = Some(incoming.captured_at.clone());
        let source = if updated_columns.contains(&key) {
            Source::Incoming
        } else {
            let Some(previous) = base_columns.get(&key) else {
                bail!("world snapshot omits payload for a column the base does not contain")
            };
            if previous.sha1 != chunk.sha1
                || previous.entities_present != chunk.entities_present
                || previous.poi_present != chunk.poi_present
                || previous.membership_revision != membership
            {
                bail!("world snapshot omits payload for a changed column")
            }
            Source::Base
        };
        for path in column_paths(&stored)? {
            entries.insert(path, source);
        }
        merged.insert(key, stored);
    }

    if let Some(base) = base {
        for chunk in &base.chunks {
            let key = chunk.key();
            if merged.contains_key(&key) {
                continue;
            }
            // Removing and re-adding a coordinate bumps its membership revision, which
            // drops the stale content instead of resurrecting it.
            if roster.get(&key).copied() != Some(chunk.membership_revision) {
                continue;
            }
            for path in column_paths(chunk)? {
                entries.insert(path, Source::Base);
            }
            merged.insert(key, chunk.clone());
        }
    }

    let mut metadata = incoming.clone();
    // The published archive is self-contained and never references a base.
    metadata.base_snapshot_id = None;
    metadata.chunks = merged.into_values().collect();
    Ok(MergePlan {
        metadata,
        entries,
        total_columns: roster.len(),
    })
}

fn column_paths(chunk: &ChunkMetadata) -> Result<Vec<String>> {
    let mut paths = vec![chunk_path(
        &chunk.dimension_id,
        "terrain",
        chunk.chunk_x,
        chunk.chunk_z,
    )?];
    if chunk.entities_present {
        paths.push(chunk_path(
            &chunk.dimension_id,
            "entities",
            chunk.chunk_x,
            chunk.chunk_z,
        )?);
    }
    if chunk.poi_present {
        paths.push(chunk_path(
            &chunk.dimension_id,
            "poi",
            chunk.chunk_x,
            chunk.chunk_z,
        )?);
    }
    Ok(paths)
}

pub fn chunk_path(dimension: &str, kind: &str, x: i32, z: i32) -> Result<String> {
    validate_dimension_id(dimension)?;
    let (namespace, path) = dimension
        .split_once(':')
        .context("dimensionId must be a resource location")?;
    Ok(format!(
        "{CHUNK_PREFIX}dimensions/{namespace}/{path}/{kind}/{x}.{z}.nbt"
    ))
}

// ---------------------------------------------------------------------------
// Archive IO
// ---------------------------------------------------------------------------

struct Members {
    world_payloads: BTreeSet<String>,
    full_columns: BTreeSet<ColumnKey>,
}

/// Walks every ZIP member once, enforcing paths, limits, and per-file digests.
///
/// With `complete = false` a member set that omits unchanged content is accepted; with
/// `complete = true` every declared file and column component must be present.
fn scan_members(
    path: &Path,
    metadata: &Metadata,
    limits: &WorldSnapshotLimits,
    incremental: bool,
) -> Result<Members> {
    let mut archive = ZipArchive::new(File::open(path)?).context("invalid world snapshot ZIP")?;
    if archive.len() > max_entries(limits, metadata.chunks.len()) {
        bail!("world snapshot contains too many members")
    }
    let expected_files: HashMap<&str, &WorldFile> = metadata
        .world
        .files
        .iter()
        .map(|file| (file.path.as_str(), file))
        .collect();
    let mut expected_columns: HashMap<String, ColumnKey> = HashMap::new();
    for chunk in &metadata.chunks {
        for member in column_paths(chunk)? {
            expected_columns.insert(member, chunk.key());
        }
    }

    let mut seen = HashSet::new();
    let mut world_payloads = BTreeSet::new();
    let mut column_hits: HashMap<ColumnKey, usize> = HashMap::new();
    let mut expanded = 0_u64;
    let mut has_metadata = false;

    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).context("invalid ZIP entry")?;
        let name = entry.name().to_owned();
        validate_entry_path(&name)?;
        if entry.is_dir()
            || entry
                .unix_mode()
                .is_some_and(|mode| mode & 0o170000 == 0o120000)
        {
            bail!("world snapshot directories and symlinks are not allowed")
        }
        if !seen.insert(name.clone()) {
            bail!("duplicate world snapshot member: {name}")
        }
        let declared = entry.size();
        let (limit, expected) = if name == METADATA_ENTRY {
            has_metadata = true;
            (limits.max_metadata_bytes, None)
        } else if let Some(relative) = name.strip_prefix(WORLD_PREFIX) {
            let Some(file) = expected_files.get(relative) else {
                bail!("world snapshot member is not declared in metadata: {name}")
            };
            (limits.max_file_bytes, Some(*file))
        } else if expected_columns.contains_key(&name) {
            (limits.max_nbt_bytes, None)
        } else {
            bail!("world snapshot contains an unreferenced member: {name}")
        };
        if declared > limit {
            bail!("world snapshot member exceeds its size limit: {name}")
        }
        let mut hasher = Sha1::new();
        let actual = read_bounded(&mut entry, limit, None, Some(&mut hasher))
            .with_context(|| format!("invalid world snapshot member data: {name}"))?;
        if actual != declared {
            bail!("truncated world snapshot member: {name}")
        }
        expanded = expanded.saturating_add(actual);
        if expanded > limits.max_expanded_bytes {
            bail!("expanded world snapshot exceeds the expansion limit")
        }
        if let Some(file) = expected {
            if actual != file.bytes || hex_lower(&hasher.finalize()) != file.sha1 {
                bail!("world snapshot member does not match its manifest entry: {name}")
            }
            world_payloads.insert(name.clone());
        } else if let Some(key) = expected_columns.get(&name) {
            *column_hits.entry(key.clone()).or_default() += 1;
        }
    }
    if !has_metadata {
        bail!("metadata.json is required")
    }

    let mut full_columns = BTreeSet::new();
    for chunk in &metadata.chunks {
        let key = chunk.key();
        let required = column_paths(chunk)?.len();
        match column_hits.get(&key).copied().unwrap_or(0) {
            0 if incremental => {}
            hits if hits == required => {
                full_columns.insert(key);
            }
            // A column is transported whole or not at all; mixing old and new
            // components of one column would publish an inconsistent column.
            _ => bail!("world snapshot provides a partial column"),
        }
    }
    if !incremental {
        if world_payloads.len() != metadata.world.files.len() {
            bail!("complete world snapshot is missing file payloads")
        }
        if full_columns.len() != metadata.chunks.len() {
            bail!("complete world snapshot is missing column payloads")
        }
    }
    Ok(Members {
        world_payloads,
        full_columns,
    })
}

/// Recomputes the client's framed column digest and checks the NBT of every column whose
/// payload is present in this upload.
///
/// Columns reused from the base are not re-read here: their bytes were verified when they
/// were uploaded, and the base archive's whole-file digest is verified when it is pinned.
fn verify_columns(
    path: &Path,
    metadata: &Metadata,
    updated: &BTreeSet<ColumnKey>,
    limits: &WorldSnapshotLimits,
) -> Result<()> {
    if updated.is_empty() {
        return Ok(());
    }
    let mut archive = ZipArchive::new(File::open(path)?)?;
    for chunk in &metadata.chunks {
        if !updated.contains(&chunk.key()) {
            continue;
        }
        let mut hasher = Sha1::new();
        for (kind, present) in [
            ("terrain", true),
            ("entities", chunk.entities_present),
            ("poi", chunk.poi_present),
        ] {
            // Framing must match the client byte for byte: big-endian kind length, kind
            // bytes, a presence byte, then either -1 or the length followed by content.
            hasher.update((kind.len() as i32).to_be_bytes());
            hasher.update(kind.as_bytes());
            if !present {
                hasher.update([0_u8]);
                hasher.update((-1_i64).to_be_bytes());
                continue;
            }
            let name = chunk_path(&chunk.dimension_id, kind, chunk.chunk_x, chunk.chunk_z)?;
            let mut bytes = Vec::new();
            {
                let mut entry = archive
                    .by_name(&name)
                    .with_context(|| format!("world snapshot column member is missing: {name}"))?;
                read_bounded(&mut entry, limits.max_nbt_bytes, Some(&mut bytes), None)?;
            }
            hasher.update([1_u8]);
            hasher.update((bytes.len() as i64).to_be_bytes());
            hasher.update(&bytes);
            let root =
                parse_chunk_nbt(&bytes).with_context(|| format!("invalid chunk NBT: {name}"))?;
            match kind {
                "terrain" => {
                    if root_int(&root, "xPos") != Some(chunk.chunk_x)
                        || root_int(&root, "zPos") != Some(chunk.chunk_z)
                    {
                        bail!("terrain NBT coordinates do not match its declared column")
                    }
                }
                "entities" => {
                    let position = root_int_array(&root, "Position")
                        .context("entities NBT has no Position")?;
                    if position != [chunk.chunk_x, chunk.chunk_z] {
                        bail!("entities NBT coordinates do not match its declared column")
                    }
                }
                _ => {}
            }
        }
        if hex_lower(&hasher.finalize()) != chunk.sha1 {
            bail!("world snapshot column digest does not match its content")
        }
    }
    Ok(())
}

fn read_archive_metadata(path: &Path, limits: &WorldSnapshotLimits) -> Result<Metadata> {
    let mut archive = ZipArchive::new(File::open(path)?).context("invalid world snapshot ZIP")?;
    let mut entry = archive
        .by_name(METADATA_ENTRY)
        .context("metadata.json is required")?;
    if entry.size() > limits.max_metadata_bytes {
        bail!("world snapshot metadata exceeds the metadata limit")
    }
    let mut bytes = Vec::new();
    read_bounded(
        &mut entry,
        limits.max_metadata_bytes,
        Some(&mut bytes),
        None,
    )?;
    let metadata: Metadata = serde_json::from_slice(&bytes).context("invalid metadata.json")?;
    if metadata.format_version != FORMAT_VERSION || metadata.kind != KIND {
        bail!("unsupported world snapshot metadata format")
    }
    Ok(metadata)
}

#[derive(Debug)]
struct SnapshotTooLarge;
impl std::fmt::Display for SnapshotTooLarge {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "world snapshot exceeds the expansion limit")
    }
}
impl std::error::Error for SnapshotTooLarge {}

#[derive(Debug, Clone, Copy, Default)]
pub struct DirectoryIoStats {
    pub linked_bytes: u64,
    pub copied_bytes: u64,
    pub written_bytes: u64,
}

#[derive(Debug, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StorageManifest {
    storage_format_version: u32,
    files: Vec<ManifestFile>,
}

#[derive(Debug, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ManifestFile {
    path: String,
    bytes: u64,
    sha1: String,
}

fn sha1_bytes(bytes: &[u8]) -> String {
    hex_lower(&Sha1::digest(bytes))
}

fn read_regular_bounded(path: &Path, limit: u64) -> Result<Vec<u8>> {
    let stat = fs::symlink_metadata(path)?;
    if !stat.is_file() || stat.len() > limit {
        bail!("invalid or oversized snapshot file: {}", path.display())
    }
    let mut bytes = Vec::new();
    read_bounded(&mut File::open(path)?, limit, Some(&mut bytes), None)?;
    Ok(bytes)
}

fn write_new_file(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut file = OpenOptions::new().create_new(true).write(true).open(path)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    Ok(())
}

fn copy_member(reader: &mut impl Read, target: &Path, limit: u64) -> Result<u64> {
    let mut output = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(target)?;
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let count = reader.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        total = total
            .checked_add(count as u64)
            .context("snapshot member size overflow")?;
        if total > limit {
            bail!("snapshot member exceeds its size limit")
        }
        output.write_all(&buffer[..count])?;
    }
    output.sync_all()?;
    Ok(total)
}

fn link_or_copy_with(
    source: &Path,
    destination: &Path,
    bytes: u64,
    link: impl FnOnce(&Path, &Path) -> std::io::Result<()>,
) -> Result<bool> {
    let stat = fs::symlink_metadata(source)?;
    if !stat.is_file() || stat.len() != bytes {
        bail!("inherited member is not the expected regular file")
    }
    match link(source, destination) {
        Ok(()) => Ok(true),
        Err(error)
            if matches!(
                error.kind(),
                std::io::ErrorKind::CrossesDevices | std::io::ErrorKind::Unsupported
            ) || matches!(error.raw_os_error(), Some(1 | 31)) =>
        {
            // EPERM on Unix or ERROR_GEN_FAILURE on Windows can mean hard links are
            // unsupported. Never overwrite a destination, including on this fallback.
            tracing::warn!(%error, path = %source.display(), "snapshot hard-link unavailable; copying");
            let copied = copy_member(&mut File::open(source)?, destination, bytes)?;
            if copied != bytes {
                bail!("inherited member size changed")
            }
            Ok(false)
        }
        Err(error) => Err(error.into()),
    }
}

fn expected_files(metadata: &Metadata) -> Result<BTreeSet<String>> {
    let mut paths = BTreeSet::from([METADATA_ENTRY.to_owned()]);
    paths.extend(
        metadata
            .world
            .files
            .iter()
            .map(|file| format!("{WORLD_PREFIX}{}", file.path)),
    );
    for chunk in &metadata.chunks {
        paths.extend(column_paths(chunk)?);
    }
    Ok(paths)
}

fn expected_directories(metadata: &Metadata, files: &BTreeSet<String>) -> BTreeSet<String> {
    let mut paths = BTreeSet::from(["world".to_owned(), "chunks".to_owned()]);
    for directory in &metadata.world.directories {
        let name = format!("{WORLD_PREFIX}{directory}");
        paths.extend(ancestors(&name).into_iter().map(str::to_owned));
        paths.insert(name);
    }
    for file in files {
        paths.extend(ancestors(file).into_iter().map(str::to_owned));
    }
    paths
}

fn storage_manifest_limit(files: &BTreeSet<String>) -> Result<u64> {
    // Exact expected names, plus ample fixed JSON/hash/size framing per entry.
    files.iter().try_fold(256_u64, |total, path| {
        total
            .checked_add(serde_json::to_vec(path)?.len() as u64 + 160)
            .context("snapshot manifest bound overflow")
    })
}

fn member_limit(name: &str, limits: &WorldSnapshotLimits) -> u64 {
    if name == METADATA_ENTRY {
        limits.max_metadata_bytes
    } else if name.starts_with(WORLD_PREFIX) {
        limits.max_file_bytes
    } else {
        limits.max_nbt_bytes
    }
}

fn check_world_files(metadata: &Metadata, files: &[ManifestFile]) -> Result<()> {
    let index: HashMap<&str, &ManifestFile> = files
        .iter()
        .map(|file| (file.path.as_str(), file))
        .collect();
    for file in &metadata.world.files {
        let name = format!("{WORLD_PREFIX}{}", file.path);
        let actual = index
            .get(name.as_str())
            .context("snapshot is missing a declared world file")?;
        if actual.bytes != file.bytes || actual.sha1 != file.sha1 {
            bail!("snapshot world manifest mismatch")
        }
    }
    Ok(())
}

fn materialize_directory(
    target: &Path,
    plan: &MergePlan,
    incoming: &Path,
    base: Option<&Path>,
    limits: &WorldSnapshotLimits,
) -> Result<(Vec<u8>, u64, DirectoryIoStats)> {
    let metadata = serde_json::to_vec(&plan.metadata)?;
    if metadata.len() as u64 > limits.max_metadata_bytes {
        bail!("world snapshot metadata exceeds its limit")
    }
    let paths = expected_files(&plan.metadata)?;
    if paths.len() > max_entries(limits, plan.metadata.chunks.len()) {
        bail!("too many snapshot members")
    }
    for directory in expected_directories(&plan.metadata, &paths) {
        fs::create_dir_all(target.join(directory))?;
    }
    write_new_file(&target.join(METADATA_ENTRY), &metadata)?;
    let mut expanded = metadata.len() as u64;
    if expanded > limits.max_expanded_bytes {
        return Err(SnapshotTooLarge.into());
    }
    let mut stats = DirectoryIoStats {
        written_bytes: expanded,
        ..DirectoryIoStats::default()
    };
    let mut archive = ZipArchive::new(File::open(incoming)?)?;
    for (name, source) in &plan.entries {
        let destination = target.join(name);
        let size = match source {
            Source::Incoming => archive.by_name(name)?.size(),
            Source::Base => {
                fs::symlink_metadata(base.context("snapshot base is missing")?.join(name))?.len()
            }
        };
        if size > member_limit(name, limits) {
            bail!("snapshot member exceeds its size limit")
        }
        expanded = expanded
            .checked_add(size)
            .context("snapshot expanded size overflow")?;
        if expanded > limits.max_expanded_bytes {
            return Err(SnapshotTooLarge.into());
        }
        match source {
            Source::Incoming => {
                let actual = copy_member(&mut archive.by_name(name)?, &destination, size)?;
                if actual != size {
                    bail!("snapshot member size changed")
                }
                stats.written_bytes += size;
            }
            Source::Base => {
                let path = base.context("snapshot base is missing")?.join(name);
                if link_or_copy_with(&path, &destination, size, |a, b| fs::hard_link(a, b))? {
                    stats.linked_bytes += size;
                } else {
                    stats.copied_bytes += size;
                }
            }
        }
    }
    let mut files = Vec::with_capacity(paths.len());
    for name in &paths {
        let path = target.join(name);
        let stat = fs::symlink_metadata(&path)?;
        if !stat.is_file() {
            bail!("snapshot member is not a regular file")
        }
        files.push(ManifestFile {
            path: name.clone(),
            bytes: stat.len(),
            sha1: sha1_file(&path)?,
        });
    }
    check_world_files(&plan.metadata, &files)?;
    let manifest = serde_json::to_vec(&StorageManifest {
        storage_format_version: 1,
        files,
    })?;
    if manifest.len() as u64 > storage_manifest_limit(&paths)? {
        bail!("snapshot storage manifest exceeds its bound")
    }
    stats.written_bytes += manifest.len() as u64;
    Ok((manifest, expanded, stats))
}

fn read_directory_metadata(path: &Path, limits: &WorldSnapshotLimits) -> Result<Metadata> {
    let bytes = read_regular_bounded(&path.join(METADATA_ENTRY), limits.max_metadata_bytes)?;
    let metadata: Metadata = serde_json::from_slice(&bytes).context("invalid metadata.json")?;
    check_metadata_shape(&metadata, limits)?;
    if metadata.base_snapshot_id.is_some() {
        bail!("published snapshot must be self-contained")
    }
    Ok(metadata)
}

fn validate_tree(
    root: &Path,
    current: &Path,
    files: &BTreeSet<String>,
    directories: &BTreeSet<String>,
    found: &mut BTreeSet<String>,
    found_dirs: &mut BTreeSet<String>,
) -> Result<()> {
    if !fs::symlink_metadata(current)?.is_dir() {
        bail!("snapshot contains a non-directory ancestor")
    }
    for entry in fs::read_dir(current)? {
        let entry = entry?;
        let path = entry.path();
        let name = path
            .strip_prefix(root)?
            .to_str()
            .context("non-UTF8 snapshot member")?
            .replace('\\', "/");
        let stat = fs::symlink_metadata(&path)?;
        if stat.is_dir() {
            if !directories.contains(&name) {
                bail!("unexpected snapshot directory: {name}")
            }
            found_dirs.insert(name);
            validate_tree(root, &path, files, directories, found, found_dirs)?;
        } else if stat.is_file() {
            if name != MANIFEST_ENTRY && !files.contains(&name) {
                bail!("unexpected snapshot file: {name}")
            }
            found.insert(name);
        } else {
            bail!("snapshot contains a symlink or special file")
        }
    }
    Ok(())
}

fn pin_base(path: &Path, record: &SnapshotRecord, limits: &WorldSnapshotLimits) -> Result<()> {
    if !fs::symlink_metadata(path)?.is_dir() {
        bail!("snapshot root is not a directory")
    }
    let metadata = read_directory_metadata(path, limits)?;
    let expected = expected_files(&metadata)?;
    let directories = expected_directories(&metadata, &expected);
    let mut found = BTreeSet::new();
    let mut found_dirs = BTreeSet::new();
    validate_tree(
        path,
        path,
        &expected,
        &directories,
        &mut found,
        &mut found_dirs,
    )?;
    found.remove(MANIFEST_ENTRY);
    if found != expected || found_dirs != directories {
        bail!("snapshot member set is incomplete")
    }
    let bytes = read_regular_bounded(
        &path.join(MANIFEST_ENTRY),
        storage_manifest_limit(&expected)?,
    )?;
    if sha1_bytes(&bytes) != record.manifest_sha1 {
        bail!("snapshot manifest hash does not match state")
    }
    let manifest: StorageManifest = serde_json::from_slice(&bytes)?;
    if manifest.storage_format_version != 1 || manifest.files.len() != expected.len() {
        bail!("unsupported or incomplete snapshot storage manifest")
    }
    let mut expanded = 0_u64;
    for (file, name) in manifest.files.iter().zip(&expected) {
        if file.path != *name || !is_sha1(&file.sha1) {
            bail!("snapshot storage manifest names or hashes are invalid")
        }
        let actual = path.join(name);
        let stat = fs::symlink_metadata(&actual)?;
        if !stat.is_file()
            || stat.len() != file.bytes
            || file.bytes > member_limit(name, limits)
            || sha1_file(&actual)? != file.sha1
        {
            bail!("snapshot member does not match its manifest")
        }
        expanded = expanded
            .checked_add(file.bytes)
            .context("snapshot size overflow")?;
        if expanded > limits.max_expanded_bytes {
            return Err(SnapshotTooLarge.into());
        }
    }
    check_world_files(&metadata, &manifest.files)?;
    if expanded != record.expanded_bytes
        || metadata.session_id != record.session_id
        || metadata.sequence != record.sequence
        || metadata.cycle_id != record.cycle_id
        || metadata.sync_chunk_revision != record.sync_chunk_revision
        || metadata.world.files.len() != record.world_files
        || metadata.world.files.iter().map(|f| f.bytes).sum::<u64>() != record.world_bytes
        || metadata.chunks.len() != record.stored_columns
    {
        bail!("snapshot content does not match its state record")
    }
    Ok(())
}

fn check_metadata_shape(metadata: &Metadata, limits: &WorldSnapshotLimits) -> Result<()> {
    if metadata.format_version != FORMAT_VERSION
        || metadata.kind != KIND
        || metadata.capture_mode != CAPTURE_MODE
        || metadata.restore_safe
    {
        bail!("world snapshot metadata header is not v5")
    }
    if metadata.sequence <= 0 || metadata.sync_chunk_revision < 0 {
        bail!("world snapshot sequence or revision is invalid")
    }
    if metadata.cycle_id.get_version_num() != 7 {
        bail!("world snapshot cycleId must be a UUIDv7")
    }
    if !is_iso_timestamp(&metadata.captured_at) {
        bail!("world snapshot capturedAt is invalid")
    }
    let world = &metadata.world;
    if world
        .files
        .len()
        .saturating_add(world.directories.len())
        .saturating_add(world.excluded_files.len())
        .saturating_add(world.excluded_directories.len())
        > limits.max_world_entries
    {
        bail!("world snapshot declares too many entries")
    }

    let mut files = HashSet::new();
    let mut target_paths: HashMap<String, bool> = HashMap::new();
    for file in &world.files {
        validate_world_path(&file.path, limits)?;
        if !files.insert(file.path.as_str()) {
            bail!("duplicate world snapshot file path: {}", file.path)
        }
        if target_paths
            .insert(normalize_target_path(&file.path), true)
            .is_some()
        {
            bail!(
                "world snapshot paths collide on a case-insensitive target: {}",
                file.path
            )
        }
        if !is_sha1(&file.sha1) {
            bail!("invalid world snapshot file digest: {}", file.path)
        }
        if file.bytes > limits.max_file_bytes {
            bail!("world snapshot file exceeds the file limit: {}", file.path)
        }
    }
    let mut directories = HashSet::new();
    for directory in &world.directories {
        validate_world_path(directory, limits)?;
        if !directories.insert(directory.as_str()) {
            bail!("duplicate world snapshot directory: {directory}")
        }
        if target_paths
            .insert(normalize_target_path(directory), false)
            .is_some()
        {
            bail!("world snapshot paths collide on a case-insensitive target: {directory}")
        }
        if files.contains(directory.as_str()) {
            bail!("world snapshot path is both a file and a directory: {directory}")
        }
    }
    // A file may not sit under a path that is itself declared as a file. Compare
    // normalized path segments because the archive may be restored on Windows.
    for file in &world.files {
        for ancestor in ancestors(&file.path) {
            if target_paths
                .get(&normalize_target_path(ancestor))
                .is_some_and(|is_file| *is_file)
            {
                bail!(
                    "world snapshot file path conflicts with a parent file: {}",
                    file.path
                )
            }
        }
    }
    for directory in &world.directories {
        for ancestor in ancestors(directory) {
            if target_paths
                .get(&normalize_target_path(ancestor))
                .is_some_and(|is_file| *is_file)
            {
                bail!(
                    "world snapshot directory conflicts with a parent file: {}",
                    directory
                )
            }
        }
    }
    if !files.contains("level.dat") {
        bail!("world snapshot manifest must contain root level.dat")
    }
    for path in world
        .excluded_files
        .iter()
        .chain(&world.excluded_directories)
    {
        validate_world_path(path, limits)?;
    }

    let mut spellings = HashMap::new();
    for path in world
        .files
        .iter()
        .map(|file| file.path.as_str())
        .chain(world.directories.iter().map(String::as_str))
    {
        for part in ancestors(path).into_iter().chain(std::iter::once(path)) {
            if spellings
                .insert(normalize_target_path(part), part)
                .is_some_and(|previous| previous != part)
            {
                bail!("world snapshot directory spelling aliases another path: {path}")
            }
        }
    }

    let mut columns = HashSet::new();
    for chunk in &metadata.chunks {
        validate_dimension_id(&chunk.dimension_id).context("invalid chunk dimensionId")?;
        for path in column_paths(chunk)? {
            validate_world_path(&path, limits)?;
        }
        if !columns.insert(chunk.key()) {
            bail!("world snapshot contains duplicate columns")
        }
        if !is_sha1(&chunk.sha1) {
            bail!("invalid world snapshot column digest")
        }
        if chunk.membership_revision < 0 {
            bail!("invalid world snapshot column membership revision")
        }
        if let Some(captured_at) = &chunk.captured_at {
            if !is_iso_timestamp(captured_at) {
                bail!("invalid world snapshot column capturedAt")
            }
        }
    }
    Ok(())
}

fn normalize_target_path(path: &str) -> String {
    path.to_lowercase()
}

/// Every proper directory prefix of `path`, e.g. `a/b/c` -> `["a", "a/b"]`.
fn ancestors(path: &str) -> Vec<&str> {
    path.match_indices('/')
        .map(|(offset, _)| &path[..offset])
        .collect()
}

/// Structural path rules Master enforces on every project-relative path.
///
/// The client applies a stricter, portability-oriented rule set before it stages a copy;
/// this check is the security boundary, not a portability gate.
fn validate_world_path(path: &str, limits: &WorldSnapshotLimits) -> Result<()> {
    if path.is_empty() {
        bail!("world snapshot path must not be empty")
    }
    if path.len() > limits.max_path_bytes {
        bail!("world snapshot path is too long: {path}")
    }
    if path.starts_with('/') || path.ends_with('/') {
        bail!("world snapshot path must be relative: {path}")
    }
    if path.contains('\\') || path.contains('\0') {
        bail!("world snapshot path contains an invalid separator: {path}")
    }
    if path.chars().any(|c| (c as u32) < 0x20 || c == '\u{7f}') {
        bail!("world snapshot path contains a control character")
    }
    let segments: Vec<&str> = path.split('/').collect();
    if segments.len() > limits.max_path_depth {
        bail!("world snapshot path is too deep: {path}")
    }
    for segment in &segments {
        if segment.is_empty() || *segment == "." || *segment == ".." {
            bail!("world snapshot path contains an empty or dot segment: {path}")
        }
        // Reject a drive-letter or alternate-data-stream form anywhere in the path.
        if segment.contains(':') {
            bail!("world snapshot path contains a drive or stream separator: {path}")
        }
        let stem = segment
            .split('.')
            .next()
            .unwrap_or_default()
            .to_ascii_uppercase();
        let reserved = matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
            || (stem.len() == 4
                && (stem.starts_with("COM") || stem.starts_with("LPT"))
                && matches!(stem.as_bytes()[3], b'1'..=b'9'));
        if segment.ends_with(['.', ' '])
            || segment.contains(['<', '>', '"', '|', '?', '*'])
            || reserved
        {
            bail!("world snapshot path aliases or is invalid on Windows: {path}")
        }
    }
    Ok(())
}

fn is_sha1(value: &str) -> bool {
    value.len() == 40
        && value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn max_entries(limits: &WorldSnapshotLimits, columns: usize) -> usize {
    limits
        .max_world_entries
        .saturating_add(columns.saturating_mul(3))
        .saturating_add(1)
}

// ---------------------------------------------------------------------------
// Roster helpers
// ---------------------------------------------------------------------------

fn distinct_chunks(chunks: &SyncChunkSnapshot) -> HashSet<ColumnKey> {
    chunks
        .chunks
        .iter()
        .map(|chunk| (chunk.dimension_id.clone(), chunk.chunk_x, chunk.chunk_z))
        .collect()
}

fn column_membership(chunks: &SyncChunkSnapshot) -> HashMap<ColumnKey, i64> {
    let explicit: HashMap<ColumnKey, i64> = chunks
        .membership_revisions
        .iter()
        .map(|revision| {
            (
                (
                    revision.dimension_id.clone(),
                    revision.chunk_x,
                    revision.chunk_z,
                ),
                revision.revision,
            )
        })
        .collect();
    distinct_chunks(chunks)
        .into_iter()
        .map(|key| {
            let revision = explicit.get(&key).copied().unwrap_or(0);
            (key, revision)
        })
        .collect()
}

fn valid_column_count(stored: &[ChunkMetadata], chunks: &SyncChunkSnapshot) -> usize {
    let membership = column_membership(chunks);
    stored
        .iter()
        .filter(|chunk| membership.get(&chunk.key()) == Some(&chunk.membership_revision))
        .count()
}

// ---------------------------------------------------------------------------
// Pointer state
// ---------------------------------------------------------------------------

fn read_state(path: &Path) -> Result<PointerState> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(PointerState::default());
        }
        Err(error) => return Err(error.into()),
        Ok(stat) if !stat.is_file() || stat.len() > 1024 * 1024 => {
            bail!("invalid snapshot pointer state")
        }
        _ => {}
    }
    let state: PointerState = serde_json::from_slice(&read_regular_bounded(path, 1024 * 1024)?)?;
    if state.storage_format_version != 1 {
        bail!("unsupported world snapshot storage state")
    }
    for record in state.latest.iter().chain(&state.previous) {
        if record.snapshot_id.get_version_num() != 7
            || !is_sha1(&record.manifest_sha1)
            || record.sequence <= 0
        {
            bail!("invalid snapshot pointer record")
        }
    }
    match (&state.latest, &state.last_upload) {
        (Some(latest), Some(receipt))
            if receipt.api_version == API_VERSION
                && latest.snapshot_id == receipt.snapshot_id
                && latest.manifest_sha1 == receipt.manifest_sha1
                && latest.expanded_bytes == receipt.expanded_bytes => {}
        (None, None) if state.previous.is_none() => {}
        _ => bail!("snapshot pointer and last receipt disagree"),
    }
    Ok(state)
}

fn cleanup_archives(
    directory: &Path,
    state: &PointerState,
    registry: &Arc<PinRegistry>,
    host_id: Uuid,
) {
    let _pins = registry
        .pins
        .lock()
        .expect("world snapshot pin registry poisoned");
    cleanup_archives_locked(directory, state, &_pins, host_id);
}

fn cleanup_archives_locked(
    directory: &Path,
    state: &PointerState,
    pins: &HashMap<(Uuid, Uuid), usize>,
    host_id: Uuid,
) {
    let keep: HashSet<Uuid> = state
        .latest
        .iter()
        .chain(&state.previous)
        .map(|record| record.snapshot_id)
        .collect();
    let Ok(entries) = fs::read_dir(directory.join(SNAPSHOTS_DIRECTORY)) else {
        return;
    };
    for entry in entries {
        let Ok(entry) = entry else {
            continue;
        };
        let Some(id) = owned_id(&entry.file_name().to_string_lossy()) else {
            continue;
        };
        if keep.contains(&id) || pins.get(&(host_id, id)).is_some_and(|count| *count > 0) {
            continue;
        }
        if !entry.file_type().is_ok_and(|kind| kind.is_dir()) {
            continue;
        }
        quarantine_directory(&entry.path(), directory);
    }
}

fn owned_id(name: &str) -> Option<Uuid> {
    Uuid::parse_str(name)
        .ok()
        .filter(|id| id.get_version_num() == 7 && id.to_string() == name)
}

fn quarantine_directory(path: &Path, directory: &Path) {
    let garbage = directory.join(GARBAGE_DIRECTORY);
    let result = (|| -> Result<PathBuf> {
        fs::create_dir_all(&garbage)?;
        if !fs::symlink_metadata(&garbage)?.is_dir() {
            bail!("snapshot garbage root is not a directory")
        }
        let destination = garbage.join(Uuid::now_v7().to_string());
        fs::rename(path, &destination)?;
        Ok(destination)
    })();
    match result {
        Ok(path) => remove_directory_later(path),
        Err(error) => tracing::warn!(%error, path = %path.display(), "snapshot retirement failed"),
    }
}

fn cleanup_owned_archive(path: &Path, state_path: &Path, snapshot_id: Uuid) {
    // A rename can have succeeded even if its durability sync failed. Never remove
    // an artifact when the authoritative pointer could already reference it.
    let Ok(state) = read_state(state_path) else {
        return;
    };
    if !state
        .latest
        .iter()
        .chain(&state.previous)
        .any(|record| record.snapshot_id == snapshot_id)
        && path.is_dir()
    {
        quarantine_directory(path, state_path.parent().unwrap());
    }
}

// ---------------------------------------------------------------------------
// Chunk NBT
// ---------------------------------------------------------------------------

/// Maximum element count for any NBT list inside an uploaded column.
///
/// `fastnbt` reads array types straight out of the borrowed slice, so their length is
/// already bounded by the member size limit; lists need this explicit cap.
const MAX_NBT_SEQUENCE_LEN: usize = 1_000_000;

/// Parses one uncompressed NBT document written by the client's `NbtIo.write`.
///
/// This proves the bytes are a well-formed NBT document whose root is a compound. It does
/// not interpret chunk semantics, so it does not prove the chunk is loadable. Trailing
/// bytes after the root compound are not inspected; the column digest already binds the
/// member's exact bytes.
fn parse_chunk_nbt(bytes: &[u8]) -> Result<HashMap<String, fastnbt::Value>> {
    let value: fastnbt::Value = fastnbt::from_bytes_with_opts(
        bytes,
        fastnbt::DeOpts::new().max_seq_len(MAX_NBT_SEQUENCE_LEN),
    )
    .map_err(|error| anyhow::anyhow!("invalid NBT document: {error}"))?;
    match value {
        fastnbt::Value::Compound(root) => Ok(root),
        _ => bail!("NBT document root must be a compound"),
    }
}

fn root_int(root: &HashMap<String, fastnbt::Value>, name: &str) -> Option<i32> {
    match root.get(name) {
        Some(fastnbt::Value::Int(value)) => Some(*value),
        _ => None,
    }
}

fn root_int_array<'a>(root: &'a HashMap<String, fastnbt::Value>, name: &str) -> Option<&'a [i32]> {
    match root.get(name) {
        Some(fastnbt::Value::IntArray(value)) => Some(value),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Timestamps
// ---------------------------------------------------------------------------

fn is_iso_timestamp(value: &str) -> bool {
    let bytes = value.as_bytes();
    if bytes.len() < 20
        || bytes.len() > 64
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || (bytes[10] != b'T' && bytes[10] != b't')
        || bytes[13] != b':'
        || bytes[16] != b':'
    {
        return false;
    }
    let digits = |range: std::ops::Range<usize>| bytes[range].iter().all(u8::is_ascii_digit);
    if !digits(0..4)
        || !digits(5..7)
        || !digits(8..10)
        || !digits(11..13)
        || !digits(14..16)
        || !digits(17..19)
    {
        return false;
    }
    let month = u32::from(bytes[5] - b'0') * 10 + u32::from(bytes[6] - b'0');
    let day = u32::from(bytes[8] - b'0') * 10 + u32::from(bytes[9] - b'0');
    let hour = u32::from(bytes[11] - b'0') * 10 + u32::from(bytes[12] - b'0');
    let minute = u32::from(bytes[14] - b'0') * 10 + u32::from(bytes[15] - b'0');
    let second = u32::from(bytes[17] - b'0') * 10 + u32::from(bytes[18] - b'0');
    if !(1..=12).contains(&month) || hour > 23 || minute > 59 || second > 59 {
        return false;
    }
    let year = u32::from(bytes[0] - b'0') * 1000
        + u32::from(bytes[1] - b'0') * 100
        + u32::from(bytes[2] - b'0') * 10
        + u32::from(bytes[3] - b'0');
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let days = [
        31,
        if leap { 29 } else { 28 },
        31,
        30,
        31,
        30,
        31,
        31,
        30,
        31,
        30,
        31,
    ];
    if day == 0 || day > days[(month - 1) as usize] {
        return false;
    }
    let mut index = 19;
    if bytes.get(index) == Some(&b'.') {
        index += 1;
        let start = index;
        while bytes.get(index).is_some_and(u8::is_ascii_digit) {
            index += 1;
        }
        if index == start || index - start > 9 {
            return false;
        }
    }
    match bytes.get(index..) {
        Some([b'Z' | b'z']) => true,
        Some([sign, h1, h2, b':', m1, m2])
            if (*sign == b'+' || *sign == b'-')
                && h1.is_ascii_digit()
                && h2.is_ascii_digit()
                && m1.is_ascii_digit()
                && m2.is_ascii_digit()
                && (u32::from(*h1 - b'0') * 10 + u32::from(*h2 - b'0')) <= 23
                && (u32::from(*m1 - b'0') * 10 + u32::from(*m2 - b'0')) <= 59 =>
        {
            true
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        model::SyncChunk,
        sync_chunks::{MembershipRevision, SyncChunkSnapshot},
    };
    use std::io::Write;
    use tempfile::{TempDir, tempdir};

    const DIMENSION: &str = "minecraft:overworld";
    const CAPTURED_AT: &str = "2026-09-21T00:00:00Z";
    const LATER_CAPTURED_AT: &str = "2026-09-21T00:00:30Z";

    // --- fixtures -------------------------------------------------------

    fn roster(revision: i64, columns: &[(i32, i32, i64)]) -> SyncChunkSnapshot {
        let owner_id = Uuid::now_v7();
        SyncChunkSnapshot {
            revision,
            chunks: columns
                .iter()
                .map(|(chunk_x, chunk_z, _)| SyncChunk {
                    dimension_id: DIMENSION.into(),
                    chunk_x: *chunk_x,
                    chunk_z: *chunk_z,
                    owner_id,
                })
                .collect(),
            membership_revisions: columns
                .iter()
                .map(|(chunk_x, chunk_z, membership)| MembershipRevision {
                    dimension_id: DIMENSION.into(),
                    chunk_x: *chunk_x,
                    chunk_z: *chunk_z,
                    revision: *membership,
                })
                .collect(),
        }
        .sorted()
    }

    fn terrain_nbt(x: i32, z: i32) -> Vec<u8> {
        let mut bytes = vec![10_u8, 0, 0];
        for (name, value) in [("xPos", x), ("zPos", z)] {
            bytes.push(3);
            bytes.extend_from_slice(&(name.len() as u16).to_be_bytes());
            bytes.extend_from_slice(name.as_bytes());
            bytes.extend_from_slice(&value.to_be_bytes());
        }
        bytes.push(0);
        bytes
    }

    fn entities_nbt(x: i32, z: i32) -> Vec<u8> {
        let mut bytes = vec![10_u8, 0, 0, 11];
        bytes.extend_from_slice(&(8_u16).to_be_bytes());
        bytes.extend_from_slice(b"Position");
        bytes.extend_from_slice(&2_i32.to_be_bytes());
        bytes.extend_from_slice(&x.to_be_bytes());
        bytes.extend_from_slice(&z.to_be_bytes());
        bytes.push(0);
        bytes
    }

    fn poi_nbt() -> Vec<u8> {
        vec![10, 0, 0, 0]
    }

    fn sha1_bytes(bytes: &[u8]) -> String {
        let mut hasher = Sha1::new();
        hasher.update(bytes);
        hex_lower(&hasher.finalize())
    }

    /// Mirrors the client's framed column digest.
    fn column_digest(components: &[(&str, Option<&[u8]>)]) -> String {
        let mut hasher = Sha1::new();
        for (kind, content) in components {
            hasher.update((kind.len() as i32).to_be_bytes());
            hasher.update(kind.as_bytes());
            match content {
                None => {
                    hasher.update([0_u8]);
                    hasher.update((-1_i64).to_be_bytes());
                }
                Some(bytes) => {
                    hasher.update([1_u8]);
                    hasher.update((bytes.len() as i64).to_be_bytes());
                    hasher.update(bytes);
                }
            }
        }
        hex_lower(&hasher.finalize())
    }

    #[derive(Clone)]
    struct FileSpec {
        path: String,
        content: Vec<u8>,
        payload: bool,
    }

    #[derive(Clone)]
    struct ColumnSpec {
        chunk_x: i32,
        chunk_z: i32,
        entities: bool,
        poi: bool,
        payload: bool,
        /// Overrides the declared digest to simulate a lying client.
        digest_override: Option<String>,
    }

    impl ColumnSpec {
        fn new(chunk_x: i32, chunk_z: i32) -> Self {
            Self {
                chunk_x,
                chunk_z,
                entities: true,
                poi: false,
                payload: true,
                digest_override: None,
            }
        }
        fn inherited(mut self) -> Self {
            self.payload = false;
            self
        }
    }

    #[derive(Clone)]
    struct UploadSpec {
        host_id: Uuid,
        session_id: Uuid,
        cycle_id: Uuid,
        sequence: i64,
        revision: i64,
        base: Option<Uuid>,
        captured_at: String,
        files: Vec<FileSpec>,
        directories: Vec<String>,
        columns: Vec<ColumnSpec>,
        extra_members: Vec<(String, Vec<u8>)>,
        drop_members: Vec<String>,
        metadata_override: Option<serde_json::Value>,
    }

    impl UploadSpec {
        fn new(host_id: Uuid, session_id: Uuid, sequence: i64, revision: i64) -> Self {
            Self {
                host_id,
                session_id,
                cycle_id: Uuid::now_v7(),
                sequence,
                revision,
                base: None,
                captured_at: CAPTURED_AT.into(),
                files: Vec::new(),
                directories: Vec::new(),
                columns: Vec::new(),
                extra_members: Vec::new(),
                drop_members: Vec::new(),
                metadata_override: None,
            }
        }

        fn file(mut self, path: &str, content: &str, payload: bool) -> Self {
            self.files.push(FileSpec {
                path: path.into(),
                content: content.as_bytes().to_vec(),
                payload,
            });
            self
        }

        fn directory(mut self, path: &str) -> Self {
            self.directories.push(path.into());
            self
        }

        fn column(mut self, column: ColumnSpec) -> Self {
            self.columns.push(column);
            self
        }

        fn metadata(&self) -> Metadata {
            Metadata {
                format_version: FORMAT_VERSION,
                kind: KIND.into(),
                host_id: self.host_id,
                session_id: self.session_id,
                cycle_id: self.cycle_id,
                sequence: self.sequence,
                base_snapshot_id: self.base,
                sync_chunk_revision: self.revision,
                captured_at: self.captured_at.clone(),
                capture_mode: CAPTURE_MODE.into(),
                restore_safe: false,
                world: WorldMetadata {
                    excluded_files: Vec::new(),
                    excluded_directories: Vec::new(),
                    directories: self.directories.clone(),
                    files: self
                        .files
                        .iter()
                        .map(|file| WorldFile {
                            path: file.path.clone(),
                            bytes: file.content.len() as u64,
                            sha1: sha1_bytes(&file.content),
                        })
                        .collect(),
                },
                chunks: self
                    .columns
                    .iter()
                    .map(|column| {
                        let terrain = terrain_nbt(column.chunk_x, column.chunk_z);
                        let entities = column
                            .entities
                            .then(|| entities_nbt(column.chunk_x, column.chunk_z));
                        let poi = column.poi.then(poi_nbt);
                        let digest = column.digest_override.clone().unwrap_or_else(|| {
                            column_digest(&[
                                ("terrain", Some(terrain.as_slice())),
                                ("entities", entities.as_deref()),
                                ("poi", poi.as_deref()),
                            ])
                        });
                        ChunkMetadata {
                            dimension_id: DIMENSION.into(),
                            chunk_x: column.chunk_x,
                            chunk_z: column.chunk_z,
                            entities_present: column.entities,
                            poi_present: column.poi,
                            sha1: digest,
                            membership_revision: 0,
                            captured_at: None,
                        }
                    })
                    .collect(),
            }
        }

        fn write(&self, path: &Path) {
            let mut members: BTreeMap<String, Vec<u8>> = BTreeMap::new();
            for file in &self.files {
                if file.payload {
                    members.insert(format!("{WORLD_PREFIX}{}", file.path), file.content.clone());
                }
            }
            for column in &self.columns {
                if !column.payload {
                    continue;
                }
                members.insert(
                    chunk_path(DIMENSION, "terrain", column.chunk_x, column.chunk_z).unwrap(),
                    terrain_nbt(column.chunk_x, column.chunk_z),
                );
                if column.entities {
                    members.insert(
                        chunk_path(DIMENSION, "entities", column.chunk_x, column.chunk_z).unwrap(),
                        entities_nbt(column.chunk_x, column.chunk_z),
                    );
                }
                if column.poi {
                    members.insert(
                        chunk_path(DIMENSION, "poi", column.chunk_x, column.chunk_z).unwrap(),
                        poi_nbt(),
                    );
                }
            }
            for (name, bytes) in &self.extra_members {
                members.insert(name.clone(), bytes.clone());
            }
            for name in &self.drop_members {
                members.remove(name);
            }
            let metadata_bytes = match &self.metadata_override {
                Some(value) => serde_json::to_vec(value).unwrap(),
                None => serde_json::to_vec(&self.metadata()).unwrap(),
            };
            members.insert(METADATA_ENTRY.into(), metadata_bytes);

            let file = fs::File::create(path).unwrap();
            let mut writer = zip::ZipWriter::new(file);
            let options = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated);
            for (name, bytes) in &members {
                writer.start_file(name, options).unwrap();
                writer.write_all(bytes).unwrap();
            }
            writer.finish().unwrap();
        }
    }

    struct Fixture {
        _root: TempDir,
        work: PathBuf,
        store: HostStore,
        snapshots: WorldSnapshotStore,
        host_id: Uuid,
    }

    impl Fixture {
        fn new() -> Self {
            Self::with_limits(WorldSnapshotLimits::default())
        }

        fn with_limits(limits: WorldSnapshotLimits) -> Self {
            let root = tempdir().unwrap();
            let store = HostStore::open(root.path()).unwrap();
            let host_id = Uuid::now_v7();
            fs::create_dir(store.host_root(host_id)).unwrap();
            fs::write(
                store.host_root(host_id).join("host.json"),
                serde_json::json!({"id": host_id, "name": "room"}).to_string(),
            )
            .unwrap();
            let work = root.path().join("work");
            fs::create_dir(&work).unwrap();
            Self {
                _root: root,
                work,
                snapshots: WorldSnapshotStore::new(store.clone(), limits),
                store,
                host_id,
            }
        }

        fn upload(&self, sequence: i64, revision: i64, session_id: Uuid) -> UploadSpec {
            UploadSpec::new(self.host_id, session_id, sequence, revision)
        }

        fn stage(&self, spec: &UploadSpec) -> PathBuf {
            let path = self
                .work
                .join(format!("upload-{}-{}.zip", spec.sequence, Uuid::now_v7()));
            spec.write(&path);
            path
        }

        fn validate(&self, spec: &UploadSpec, staged: &Path) -> Result<ValidatedUpload> {
            self.snapshots.validate_upload(
                staged,
                spec.host_id,
                spec.session_id,
                spec.sequence,
                spec.revision,
                spec.cycle_id,
                &sha1_file(staged).unwrap(),
            )
        }

        fn submit(
            &self,
            spec: &UploadSpec,
            chunks: &SyncChunkSnapshot,
        ) -> std::result::Result<CommitOutcome, CommitError> {
            let staged = self.stage(spec);
            let validated = self.validate(spec, &staged).map_err(CommitError::Invalid)?;
            validated
                .validate_subset(chunks)
                .map_err(|error| CommitError::Invalid(error))?;
            match self
                .snapshots
                .prepare(self.host_id, &validated, &staged, chunks)?
            {
                Prepared::Idempotent(receipt) => Ok(CommitOutcome::Idempotent(receipt)),
                Prepared::Candidate(candidate) => {
                    self.snapshots.commit(self.host_id, *candidate, chunks)
                }
            }
        }

        fn latest_manifest(&self) -> Metadata {
            let id = self.snapshots.latest_id(self.host_id).unwrap().unwrap();
            self.snapshots.manifest(self.host_id, id).unwrap().unwrap()
        }

        fn latest_members(&self) -> BTreeSet<String> {
            let id = self.snapshots.latest_id(self.host_id).unwrap().unwrap();
            let path = self
                .snapshots
                .retained_archive(self.host_id, id)
                .unwrap()
                .unwrap();
            let mut members = BTreeSet::new();
            fn walk(root: &Path, path: &Path, members: &mut BTreeSet<String>) {
                for entry in fs::read_dir(path).unwrap().flatten() {
                    let child = entry.path();
                    if child.is_dir() {
                        walk(root, &child, members);
                    } else if child.file_name().is_some_and(|name| name != MANIFEST_ENTRY) {
                        members.insert(
                            child
                                .strip_prefix(root)
                                .unwrap()
                                .to_string_lossy()
                                .replace('\\', "/"),
                        );
                    }
                }
            }
            walk(&path, &path, &mut members);
            members
        }

        fn member_bytes(&self, name: &str) -> Vec<u8> {
            let id = self.snapshots.latest_id(self.host_id).unwrap().unwrap();
            let path = self
                .snapshots
                .retained_archive(self.host_id, id)
                .unwrap()
                .unwrap();
            fs::read(path.join(name)).unwrap()
        }

        fn state_bytes(&self) -> Vec<u8> {
            fs::read(
                self.store
                    .host_root(self.host_id)
                    .join(DIRECTORY_NAME)
                    .join(STORAGE_DIRECTORY)
                    .join(STATE_NAME),
            )
            .unwrap()
        }
    }

    fn committed(outcome: CommitOutcome) -> UploadReceipt {
        match outcome {
            CommitOutcome::Committed(receipt) => receipt,
            CommitOutcome::Idempotent(receipt) => {
                panic!("expected a fresh commit, got idempotent {receipt:?}")
            }
        }
    }

    // --- protocol shape -------------------------------------------------

    #[test]
    fn v4_metadata_and_old_kinds_are_rejected() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let mut spec = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        let mut value = serde_json::to_value(spec.metadata()).unwrap();
        value["formatVersion"] = 4.into();
        spec.metadata_override = Some(value);
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());

        let mut spec = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        let mut value = serde_json::to_value(spec.metadata()).unwrap();
        value["kind"] = "sync-chunk-snapshot".into();
        spec.metadata_override = Some(value);
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());
    }

    #[test]
    fn chunk_paths_are_derived_exactly() {
        assert_eq!(
            chunk_path(DIMENSION, "terrain", 1, -2).unwrap(),
            "chunks/dimensions/minecraft/overworld/terrain/1.-2.nbt"
        );
        assert!(chunk_path("minecraft:../escape", "terrain", 0, 0).is_err());
        assert!(chunk_path("Minecraft:Overworld", "terrain", 0, 0).is_err());
    }

    #[test]
    fn world_paths_reject_traversal_and_platform_escapes() {
        let limits = WorldSnapshotLimits::default();
        for valid in [
            "level.dat",
            "playerdata/0000.dat",
            "serverconfig/ftbteams/a.snbt",
            "数据/测试 文件.json",
        ] {
            validate_world_path(valid, &limits).unwrap();
        }
        for invalid in [
            "",
            "/level.dat",
            "level.dat/",
            "../level.dat",
            "a/../b",
            "a//b",
            "a\\b",
            "C:/level.dat",
            "level.dat:stream",
            "a/./b",
        ] {
            assert!(
                validate_world_path(invalid, &limits).is_err(),
                "expected rejection: {invalid}"
            );
        }
        assert!(validate_world_path(&"a/".repeat(64).replace("//", "/b/"), &limits).is_err());
    }

    // --- first upload and inheritance -----------------------------------

    #[test]
    fn first_upload_publishes_a_self_contained_archive() {
        let fixture = Fixture::new();
        let chunks = roster(1, &[(1, 2, 1)]);
        let spec = fixture
            .upload(1, 1, Uuid::now_v7())
            .file("level.dat", "level", true)
            .file("playerdata/p.dat", "player", true)
            .directory("datapacks")
            .column(ColumnSpec::new(1, 2));
        let receipt = committed(fixture.submit(&spec, &chunks).unwrap());

        assert_eq!(receipt.world_files, 2);
        assert_eq!(receipt.world_bytes, ("level".len() + "player".len()) as u64);
        assert_eq!(receipt.observed_columns, 1);
        assert_eq!(receipt.updated_columns, 1);
        assert_eq!(receipt.stored_columns, 1);
        assert_eq!(receipt.total_columns, 1);
        assert!(receipt.complete);
        assert_ne!(receipt.upload_sha1, receipt.manifest_sha1);

        let manifest = fixture.latest_manifest();
        assert_eq!(manifest.format_version, 5);
        assert_eq!(manifest.kind, "world-snapshot");
        assert_eq!(manifest.capture_mode, "memory-and-files");
        assert!(!manifest.restore_safe);
        // A published archive must not depend on any other snapshot.
        assert_eq!(manifest.base_snapshot_id, None);
        assert_eq!(manifest.world.directories, vec!["datapacks".to_owned()]);
        assert_eq!(manifest.chunks[0].membership_revision, 1);
        assert_eq!(manifest.chunks[0].captured_at.as_deref(), Some(CAPTURED_AT));

        let members = fixture.latest_members();
        assert!(members.contains("world/level.dat"));
        assert!(members.contains("world/playerdata/p.dat"));
        assert!(members.contains("chunks/dimensions/minecraft/overworld/terrain/1.2.nbt"));
        assert!(members.contains("chunks/dimensions/minecraft/overworld/entities/1.2.nbt"));
        assert!(!members.contains("chunks/dimensions/minecraft/overworld/poi/1.2.nbt"));
    }

    #[test]
    fn unchanged_files_and_columns_are_inherited_from_the_base() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(1, &[(1, 2, 1)]);
        let first = fixture
            .upload(1, 1, session)
            .file("level.dat", "level", true)
            .file("stats/s.json", "stats", true)
            .column(ColumnSpec::new(1, 2));
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());

        let mut second = fixture
            .upload(2, 1, session)
            .file("level.dat", "level", false)
            .file("stats/s.json", "changed", true)
            .column(ColumnSpec::new(1, 2).inherited());
        second.base = Some(first_receipt.snapshot_id);
        second.captured_at = LATER_CAPTURED_AT.into();
        let receipt = committed(fixture.submit(&second, &chunks).unwrap());

        assert_eq!(receipt.observed_columns, 1);
        assert_eq!(receipt.updated_columns, 0);
        assert_eq!(receipt.stored_columns, 1);
        assert_eq!(fixture.member_bytes("world/level.dat"), b"level");
        assert_eq!(fixture.member_bytes("world/stats/s.json"), b"changed");
        assert_eq!(
            fixture.member_bytes("chunks/dimensions/minecraft/overworld/terrain/1.2.nbt"),
            terrain_nbt(1, 2)
        );
        // Observing a column without re-uploading it still advances its capturedAt.
        assert_eq!(
            fixture.latest_manifest().chunks[0].captured_at.as_deref(),
            Some(LATER_CAPTURED_AT)
        );
    }

    #[test]
    fn removed_files_are_dropped_and_missing_payloads_are_rejected() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(0, &[]);
        let first = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true)
            .file("obsolete.dat", "gone", true);
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());

        let mut second = fixture
            .upload(2, 0, session)
            .file("level.dat", "level", false);
        second.base = Some(first_receipt.snapshot_id);
        committed(fixture.submit(&second, &chunks).unwrap());
        let members = fixture.latest_members();
        assert!(members.contains("world/level.dat"));
        assert!(!members.contains("world/obsolete.dat"));
        assert_eq!(fixture.latest_manifest().world.files.len(), 1);

        // A file that is new relative to the base must carry its payload.
        let mut third = fixture
            .upload(3, 0, session)
            .file("level.dat", "level", false)
            .file("fresh.dat", "fresh", false);
        third.base = fixture.snapshots.latest_id(fixture.host_id).unwrap();
        assert!(matches!(
            fixture.submit(&third, &chunks),
            Err(CommitError::Invalid(_))
        ));

        // A changed file must carry its payload too.
        let mut fourth = fixture
            .upload(3, 0, session)
            .file("level.dat", "edited", false);
        fourth.base = fixture.snapshots.latest_id(fixture.host_id).unwrap();
        assert!(matches!(
            fixture.submit(&fourth, &chunks),
            Err(CommitError::Invalid(_))
        ));
    }

    #[test]
    fn zero_column_and_metadata_only_cycles_are_accepted() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(0, &[]);
        let first = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());
        assert_eq!(first_receipt.total_columns, 0);
        assert!(first_receipt.complete);

        // Only a new empty directory changed: no payload at all, still a valid commit.
        let mut second = fixture
            .upload(2, 0, session)
            .file("level.dat", "level", false)
            .directory("datapacks/empty");
        second.base = Some(first_receipt.snapshot_id);
        let receipt = committed(fixture.submit(&second, &chunks).unwrap());
        assert_eq!(receipt.world_files, 1);
        assert_eq!(
            fixture.latest_manifest().world.directories,
            vec!["datapacks/empty".to_owned()]
        );
    }

    // --- membership -----------------------------------------------------

    #[test]
    fn unobserved_columns_survive_but_re_added_membership_does_not() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(1, &[(1, 2, 1), (3, 4, 1)]);
        let first = fixture
            .upload(1, 1, session)
            .file("level.dat", "level", true)
            .column(ColumnSpec::new(1, 2))
            .column(ColumnSpec::new(3, 4));
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());
        assert_eq!(first_receipt.stored_columns, 2);

        // Only (1,2) is observed this cycle; (3,4) is inherited untouched.
        let mut second = fixture
            .upload(2, 1, session)
            .file("level.dat", "level", false)
            .column(ColumnSpec::new(1, 2).inherited());
        second.base = Some(first_receipt.snapshot_id);
        second.captured_at = LATER_CAPTURED_AT.into();
        let receipt = committed(fixture.submit(&second, &chunks).unwrap());
        assert_eq!(receipt.observed_columns, 1);
        assert_eq!(receipt.stored_columns, 2);
        let manifest = fixture.latest_manifest();
        let retained = manifest
            .chunks
            .iter()
            .find(|chunk| chunk.chunk_x == 3)
            .unwrap();
        // An unobserved column keeps the timestamp of the cycle that last saw it.
        assert_eq!(retained.captured_at.as_deref(), Some(CAPTURED_AT));

        // (3,4) is removed and re-added, which bumps its membership revision.
        let rebuilt = roster(3, &[(1, 2, 1), (3, 4, 3)]);
        let mut third = fixture
            .upload(3, 3, session)
            .file("level.dat", "level", false)
            .column(ColumnSpec::new(1, 2).inherited());
        third.base = fixture.snapshots.latest_id(fixture.host_id).unwrap();
        let receipt = committed(fixture.submit(&third, &rebuilt).unwrap());
        assert_eq!(receipt.stored_columns, 1);
        assert_eq!(receipt.total_columns, 2);
        assert!(!receipt.complete);
        assert!(
            !fixture
                .latest_members()
                .contains("chunks/dimensions/minecraft/overworld/terrain/3.4.nbt")
        );
    }

    #[test]
    fn out_of_roster_columns_are_rejected() {
        let fixture = Fixture::new();
        let chunks = roster(1, &[(1, 2, 1)]);
        let spec = fixture
            .upload(1, 1, Uuid::now_v7())
            .file("level.dat", "level", true)
            .column(ColumnSpec::new(9, 9));
        assert!(matches!(
            fixture.submit(&spec, &chunks),
            Err(CommitError::Invalid(_))
        ));
    }

    // --- member integrity -----------------------------------------------

    #[test]
    fn member_integrity_failures_are_rejected() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();

        // An unreferenced member.
        let mut spec = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        spec.extra_members
            .push(("world/undeclared.dat".into(), b"x".to_vec()));
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());

        // A member outside the two allowed prefixes.
        let mut spec = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        spec.extra_members.push(("stray.bin".into(), b"x".to_vec()));
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());

        // Content that does not match the declared digest.
        let mut spec = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        spec.extra_members
            .push(("world/level.dat".into(), b"tampered".to_vec()));
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());

        // A declared SHA-1 that is not 40 lowercase hex digits.
        let mut spec = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        let mut value = serde_json::to_value(spec.metadata()).unwrap();
        value["world"]["files"][0]["sha1"] = "NOTAHASH".into();
        spec.metadata_override = Some(value);
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());

        // A declared header SHA-1 that does not match the uploaded bytes.
        let spec = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        let staged = fixture.stage(&spec);
        assert!(
            fixture
                .snapshots
                .validate_upload(
                    &staged,
                    spec.host_id,
                    spec.session_id,
                    spec.sequence,
                    spec.revision,
                    spec.cycle_id,
                    "0123456789abcdef0123456789abcdef01234567",
                )
                .is_err()
        );
    }

    #[test]
    fn partial_columns_and_lying_column_digests_are_rejected() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(1, &[(1, 2, 1)]);

        // terrain present but the declared entities member missing.
        let mut spec = fixture
            .upload(1, 1, session)
            .file("level.dat", "level", true)
            .column(ColumnSpec::new(1, 2));
        spec.drop_members
            .push(chunk_path(DIMENSION, "entities", 1, 2).unwrap());
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());

        // A digest that does not describe the uploaded column content.
        let mut column = ColumnSpec::new(1, 2);
        column.digest_override = Some("0".repeat(40));
        let spec = fixture
            .upload(1, 1, session)
            .file("level.dat", "level", true)
            .column(column);
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());

        // Terrain NBT whose coordinates disagree with the declared column.
        let mut spec = fixture
            .upload(1, 1, session)
            .file("level.dat", "level", true)
            .column(ColumnSpec::new(1, 2));
        let wrong = terrain_nbt(7, 7);
        let digest = column_digest(&[
            ("terrain", Some(wrong.as_slice())),
            ("entities", Some(entities_nbt(1, 2).as_slice())),
            ("poi", None),
        ]);
        let mut value = serde_json::to_value(spec.metadata()).unwrap();
        value["chunks"][0]["sha1"] = digest.into();
        spec.metadata_override = Some(value);
        spec.extra_members
            .push((chunk_path(DIMENSION, "terrain", 1, 2).unwrap(), wrong));
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());
        let _ = chunks;
    }

    #[test]
    fn inherited_columns_must_match_the_base_exactly() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(1, &[(1, 2, 1)]);
        let first = fixture
            .upload(1, 1, session)
            .file("level.dat", "level", true)
            .column(ColumnSpec::new(1, 2));
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());

        // Claiming a different digest without uploading the content is a mismatch.
        let mut column = ColumnSpec::new(1, 2).inherited();
        column.digest_override = Some("1".repeat(40));
        let mut second = fixture
            .upload(2, 1, session)
            .file("level.dat", "level", false)
            .column(column);
        second.base = Some(first_receipt.snapshot_id);
        assert!(matches!(
            fixture.submit(&second, &chunks),
            Err(CommitError::Invalid(_))
        ));

        // Changing the presence flags without a payload is a mismatch too.
        let mut column = ColumnSpec::new(1, 2).inherited();
        column.poi = true;
        let mut third = fixture
            .upload(2, 1, session)
            .file("level.dat", "level", false)
            .column(column);
        third.base = Some(first_receipt.snapshot_id);
        assert!(matches!(
            fixture.submit(&third, &chunks),
            Err(CommitError::Invalid(_))
        ));
    }

    // --- sequencing, base CAS and idempotency ---------------------------

    #[test]
    fn retries_are_idempotent_and_conflicting_sequences_are_refused() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(0, &[]);
        let first = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        let receipt = committed(fixture.submit(&first, &chunks).unwrap());

        // The same cycle re-sent byte for byte returns the original receipt.
        let retry = fixture.submit(&first, &chunks).unwrap();
        let CommitOutcome::Idempotent(retried) = retry else {
            panic!("expected an idempotent retry")
        };
        assert_eq!(retried.snapshot_id, receipt.snapshot_id);
        assert_eq!(
            fixture.snapshots.latest_id(fixture.host_id).unwrap(),
            Some(receipt.snapshot_id)
        );

        // Same sequence, different content.
        let mut conflicting = fixture
            .upload(1, 0, session)
            .file("level.dat", "different", true);
        conflicting.base = Some(receipt.snapshot_id);
        assert!(matches!(
            fixture.submit(&conflicting, &chunks),
            Err(CommitError::SequenceConflict)
        ));

        // An older sequence is refused outright.
        let mut stale = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", false)
            .file("a.dat", "a", true);
        stale.base = Some(receipt.snapshot_id);
        assert!(matches!(
            fixture.submit(&stale, &chunks),
            Err(CommitError::SequenceConflict)
        ));
    }

    #[test]
    fn a_stale_base_is_refused_without_replaying_the_candidate() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(0, &[]);
        let first = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());

        let mut second = fixture
            .upload(2, 0, session)
            .file("level.dat", "level", false)
            .file("b.dat", "b", true);
        second.base = Some(first_receipt.snapshot_id);
        committed(fixture.submit(&second, &chunks).unwrap());

        // A third cycle that still points at the first snapshot is rejected.
        let mut third = fixture
            .upload(3, 0, session)
            .file("level.dat", "level", false);
        third.base = Some(first_receipt.snapshot_id);
        assert!(matches!(
            fixture.submit(&third, &chunks),
            Err(CommitError::BaseConflict)
        ));

        // So is a first-cycle-shaped upload that claims there is no base.
        let fresh = fixture
            .upload(4, 0, session)
            .file("level.dat", "level", true);
        assert!(matches!(
            fixture.submit(&fresh, &chunks),
            Err(CommitError::BaseConflict)
        ));
    }

    #[test]
    fn a_roster_that_moves_after_preparation_fails_the_commit() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(1, &[(1, 2, 1)]);
        let spec = fixture
            .upload(1, 1, session)
            .file("level.dat", "level", true)
            .column(ColumnSpec::new(1, 2));
        let staged = fixture.stage(&spec);
        let validated = fixture.validate(&spec, &staged).unwrap();
        let Prepared::Candidate(candidate) = fixture
            .snapshots
            .prepare(fixture.host_id, &validated, &staged, &chunks)
            .unwrap()
        else {
            panic!("expected a candidate")
        };
        // The roster gained a column while the candidate was being rebuilt.
        let moved = roster(2, &[(1, 2, 1), (5, 6, 2)]);
        assert!(matches!(
            fixture
                .snapshots
                .commit(fixture.host_id, *candidate, &moved),
            Err(CommitError::RevisionConflict)
        ));
        assert_eq!(fixture.snapshots.latest_id(fixture.host_id).unwrap(), None);
    }

    #[test]
    fn a_new_session_may_restart_the_sequence() {
        let fixture = Fixture::new();
        let chunks = roster(0, &[]);
        let first = fixture
            .upload(1, 0, Uuid::now_v7())
            .file("level.dat", "level", true);
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());

        let mut restarted = fixture
            .upload(1, 0, Uuid::now_v7())
            .file("level.dat", "level", false);
        restarted.base = Some(first_receipt.snapshot_id);
        let receipt = committed(fixture.submit(&restarted, &chunks).unwrap());
        assert_eq!(receipt.sequence, 1);
        assert_ne!(receipt.session_id, first_receipt.session_id);
    }

    // --- limits, state durability and retention -------------------------

    #[test]
    fn an_oversized_directory_snapshot_leaves_published_state_untouched() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(0, &[]);
        let first = fixture
            .upload(1, 0, session)
            .file("level.dat", &"l".repeat(1024), true);
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());
        let before = fixture.state_bytes();

        let tight = WorldSnapshotStore::new(
            fixture.store.clone(),
            WorldSnapshotLimits {
                max_expanded_bytes: 2500,
                ..WorldSnapshotLimits::default()
            },
        );
        let mut second = fixture
            .upload(2, 0, session)
            .file("level.dat", &"l".repeat(1024), false)
            .file("b.dat", &"b".repeat(1024), true);
        second.base = Some(first_receipt.snapshot_id);
        let staged = fixture.stage(&second);
        let validated = tight
            .validate_upload(
                &staged,
                second.host_id,
                second.session_id,
                second.sequence,
                second.revision,
                second.cycle_id,
                &sha1_file(&staged).unwrap(),
            )
            .unwrap();
        assert!(matches!(
            tight.prepare(fixture.host_id, &validated, &staged, &chunks),
            Err(CommitError::TooLarge(_))
        ));
        assert_eq!(fixture.state_bytes(), before);
        // The abandoned candidate does not linger in the host directory.
        let leftovers = fs::read_dir(
            fixture
                .store
                .host_root(fixture.host_id)
                .join(DIRECTORY_NAME),
        )
        .unwrap()
        .flatten()
        .filter(|entry| {
            entry
                .file_name()
                .to_string_lossy()
                .starts_with(".candidate-")
        })
        .count();
        assert_eq!(leftovers, 0);
    }

    #[test]
    fn an_upload_over_the_limit_is_refused_before_any_rebuild() {
        let fixture = Fixture::with_limits(WorldSnapshotLimits {
            max_upload_bytes: 16,
            ..WorldSnapshotLimits::default()
        });
        let spec = fixture
            .upload(1, 0, Uuid::now_v7())
            .file("level.dat", "level", true);
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());
    }

    #[test]
    fn only_latest_and_previous_stay_addressable() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(0, &[]);
        let mut ids = Vec::new();
        let mut base = None;
        for sequence in 1..=3 {
            let mut spec = fixture.upload(sequence, 0, session).file(
                "level.dat",
                &format!("level-{sequence}"),
                true,
            );
            spec.base = base;
            let receipt = committed(fixture.submit(&spec, &chunks).unwrap());
            base = Some(receipt.snapshot_id);
            ids.push(receipt.snapshot_id);
        }
        assert!(
            fixture
                .snapshots
                .retained_archive(fixture.host_id, ids[0])
                .unwrap()
                .is_none()
        );
        for id in &ids[1..] {
            assert!(
                fixture
                    .snapshots
                    .retained_archive(fixture.host_id, *id)
                    .unwrap()
                    .is_some()
            );
        }
        assert!(
            fixture
                .snapshots
                .manifest(fixture.host_id, ids[0])
                .unwrap()
                .is_none()
        );
        assert!(
            fixture
                .snapshots
                .retained_archive(fixture.host_id, Uuid::now_v7())
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn status_reports_coverage_against_the_current_roster() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let empty = fixture
            .snapshots
            .status(fixture.host_id, &roster(0, &[]))
            .unwrap();
        assert!(empty.latest.is_none());
        assert!(empty.last_upload.is_none());
        assert!(empty.complete);

        let chunks = roster(1, &[(1, 2, 1), (3, 4, 1)]);
        let spec = fixture
            .upload(1, 1, session)
            .file("level.dat", "level", true)
            .column(ColumnSpec::new(1, 2));
        let receipt = committed(fixture.submit(&spec, &chunks).unwrap());
        let status = fixture.snapshots.status(fixture.host_id, &chunks).unwrap();
        assert_eq!(status.stored_columns, 1);
        assert_eq!(status.total_columns, 2);
        assert!(!status.complete);
        assert_eq!(
            status.last_upload.as_ref().map(|value| value.snapshot_id),
            Some(receipt.snapshot_id)
        );

        // Re-adding (1,2) under a new membership revision invalidates stored coverage.
        let rebuilt = roster(2, &[(1, 2, 5)]);
        let status = fixture.snapshots.status(fixture.host_id, &rebuilt).unwrap();
        assert_eq!(status.stored_columns, 0);
        assert_eq!(status.total_columns, 1);
    }

    #[test]
    fn prepared_candidate_pins_base_until_it_is_dropped() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(0, &[]);
        let first = fixture
            .upload(1, 0, session)
            .file("level.dat", "level-1", true);
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());

        let mut pending = fixture
            .upload(2, 0, session)
            .file("level.dat", "level-1", false)
            .file("pending.dat", "pending", true);
        pending.base = Some(first_receipt.snapshot_id);
        let staged = fixture.stage(&pending);
        let validated = fixture.validate(&pending, &staged).unwrap();
        let candidate = match fixture
            .snapshots
            .prepare(fixture.host_id, &validated, &staged, &chunks)
            .unwrap()
        {
            Prepared::Candidate(candidate) => candidate,
            Prepared::Idempotent(_) => panic!("expected a candidate"),
        };

        let mut second = fixture
            .upload(2, 0, session)
            .file("level.dat", "level-1", false)
            .file("second.dat", "second", true);
        second.base = Some(first_receipt.snapshot_id);
        let second_receipt = committed(fixture.submit(&second, &chunks).unwrap());
        let mut third = fixture
            .upload(3, 0, session)
            .file("level.dat", "level-1", false)
            .file("third.dat", "third", true);
        third.base = Some(second_receipt.snapshot_id);
        committed(fixture.submit(&third, &chunks).unwrap());

        let first_archive = fixture
            .store
            .host_root(fixture.host_id)
            .join(DIRECTORY_NAME)
            .join(STORAGE_DIRECTORY)
            .join(SNAPSHOTS_DIRECTORY)
            .join(first_receipt.snapshot_id.to_string());
        assert!(first_archive.is_dir());
        drop(candidate);
        assert!(!first_archive.exists());
    }

    #[test]
    fn status_rejects_corrupted_or_missing_latest_archive() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(0, &[]);
        let first = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        let receipt = committed(fixture.submit(&first, &chunks).unwrap());
        let archive = fixture
            .snapshots
            .retained_archive(fixture.host_id, receipt.snapshot_id)
            .unwrap()
            .unwrap();
        let mut bytes = fs::read(archive.join(METADATA_ENTRY)).unwrap();
        let last = bytes.len() - 1;
        bytes[last] ^= 0xff;
        fs::write(archive.join(METADATA_ENTRY), bytes).unwrap();
        assert!(fixture.snapshots.status(fixture.host_id, &chunks).is_err());
        fs::remove_dir_all(&archive).unwrap();
        assert!(fixture.snapshots.status(fixture.host_id, &chunks).is_err());
    }

    #[test]
    fn world_manifest_requires_level_dat_and_windows_safe_paths() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();

        let spec = fixture
            .upload(1, 0, session)
            .file("Level.dat", "level", true);
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());

        let spec = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true)
            .file("Data", "data", true)
            .file("data/child", "child", true);
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());

        let spec = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true)
            .file("DATA", "data", true)
            .directory("data");
        let staged = fixture.stage(&spec);
        assert!(fixture.validate(&spec, &staged).is_err());
    }

    #[test]
    fn a_corrupted_base_archive_fails_the_cycle_instead_of_propagating() {
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(0, &[]);
        let first = fixture
            .upload(1, 0, session)
            .file("level.dat", "level", true);
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());
        let archive = fixture
            .snapshots
            .retained_archive(fixture.host_id, first_receipt.snapshot_id)
            .unwrap()
            .unwrap();
        let mut bytes = fs::read(archive.join(METADATA_ENTRY)).unwrap();
        let last = bytes.len() - 1;
        bytes[last] ^= 0xff;
        fs::write(archive.join(METADATA_ENTRY), bytes).unwrap();

        let mut second = fixture
            .upload(2, 0, session)
            .file("level.dat", "level", false);
        second.base = Some(first_receipt.snapshot_id);
        assert!(matches!(
            fixture.submit(&second, &chunks),
            Err(CommitError::Storage(_))
        ));
    }

    #[test]
    fn directories_have_exact_payload_sizes_and_no_generated_zip() {
        let fixture = Fixture::new();
        let spec = fixture
            .upload(1, 1, Uuid::now_v7())
            .file("level.dat", "level", true)
            .directory("empty/nested")
            .column(ColumnSpec::new(1, 2));
        let receipt = committed(fixture.submit(&spec, &roster(1, &[(1, 2, 1)])).unwrap());
        let directory = fixture
            .snapshots
            .retained_archive(fixture.host_id, receipt.snapshot_id)
            .unwrap()
            .unwrap();
        assert!(directory.join("world/empty/nested").is_dir());
        let members = fixture.latest_members();
        let bytes: u64 = members
            .iter()
            .map(|name| fs::metadata(directory.join(name)).unwrap().len())
            .sum();
        assert_eq!(receipt.expanded_bytes, bytes);
        assert!(bytes > receipt.world_bytes);
        assert_eq!(
            sha1_file(&directory.join(MANIFEST_ENTRY)).unwrap(),
            receipt.manifest_sha1
        );
        assert_eq!(receipt.api_version, API_VERSION);
        let root = fixture.snapshots.directory(fixture.host_id).unwrap();
        assert!(
            !fs::read_dir(&root)
                .unwrap()
                .flatten()
                .any(|entry| entry.path().extension().is_some_and(|e| e == "zip"))
        );
        assert_eq!(
            fs::read_dir(root.join(STAGING_DIRECTORY)).unwrap().count(),
            0
        );
    }

    #[cfg(unix)]
    #[test]
    fn inherited_read_only_files_share_inodes_and_changes_leave_old_snapshots_intact() {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};
        let fixture = Fixture::new();
        let session = Uuid::now_v7();
        let chunks = roster(0, &[]);
        let first = fixture
            .upload(1, 0, session)
            .file("level.dat", "one", true)
            .file("stay.dat", "stay", true);
        let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());
        let (_, first_dir, _pin) = fixture
            .snapshots
            .pin_retained(fixture.host_id, Some(first_receipt.snapshot_id))
            .unwrap()
            .unwrap();
        fs::set_permissions(
            first_dir.join("world/level.dat"),
            fs::Permissions::from_mode(0o444),
        )
        .unwrap();
        let mut second = fixture
            .upload(2, 0, session)
            .file("level.dat", "one", false)
            .file("stay.dat", "stay", false);
        second.base = Some(first_receipt.snapshot_id);
        let second_receipt = committed(fixture.submit(&second, &chunks).unwrap());
        let second_dir = fixture
            .snapshots
            .retained_archive(fixture.host_id, second_receipt.snapshot_id)
            .unwrap()
            .unwrap();
        assert_eq!(
            fs::metadata(first_dir.join("world/level.dat"))
                .unwrap()
                .ino(),
            fs::metadata(second_dir.join("world/level.dat"))
                .unwrap()
                .ino()
        );
        let mut third = fixture
            .upload(3, 0, session)
            .file("level.dat", "two", true)
            .file("stay.dat", "stay", false);
        third.base = Some(second_receipt.snapshot_id);
        let third_receipt = committed(fixture.submit(&third, &chunks).unwrap());
        let third_dir = fixture
            .snapshots
            .retained_archive(fixture.host_id, third_receipt.snapshot_id)
            .unwrap()
            .unwrap();
        assert_eq!(fs::read(first_dir.join("world/level.dat")).unwrap(), b"one");
        assert_eq!(
            fs::read(second_dir.join("world/level.dat")).unwrap(),
            b"one"
        );
        assert_eq!(fs::read(third_dir.join("world/level.dat")).unwrap(), b"two");
        assert_ne!(
            fs::metadata(first_dir.join("world/level.dat"))
                .unwrap()
                .ino(),
            fs::metadata(third_dir.join("world/level.dat"))
                .unwrap()
                .ino()
        );
        assert_eq!(
            fs::metadata(first_dir.join("world/stay.dat"))
                .unwrap()
                .ino(),
            fs::metadata(third_dir.join("world/stay.dat"))
                .unwrap()
                .ino()
        );
    }

    #[test]
    fn unsupported_hardlinks_copy_without_overwriting_existing_files() {
        let root = tempfile::tempdir().unwrap();
        let source = root.path().join("source");
        let target = root.path().join("target");
        fs::write(&source, b"original").unwrap();
        let unsupported =
            |_: &Path, _: &Path| Err(std::io::Error::from(std::io::ErrorKind::Unsupported));
        assert!(!link_or_copy_with(&source, &target, 8, unsupported).unwrap());
        fs::write(&source, b"modified").unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"original");
        assert!(link_or_copy_with(&source, &target, 8, unsupported).is_err());
        assert_eq!(fs::read(&target).unwrap(), b"original");
        let refused = root.path().join("refused");
        assert!(
            link_or_copy_with(&source, &refused, 8, |_, _| Err(
                std::io::ErrorKind::PermissionDenied.into()
            ))
            .is_err()
        );
        assert!(!refused.exists());
    }

    #[test]
    fn pointer_failure_preserves_old_state_and_post_write_error_is_retryable() {
        for fail_after_write in [false, true] {
            let fixture = Fixture::new();
            let session = Uuid::now_v7();
            let chunks = roster(0, &[]);
            let first = fixture.upload(1, 0, session).file("level.dat", "one", true);
            let first_receipt = committed(fixture.submit(&first, &chunks).unwrap());
            let mut second = fixture.upload(2, 0, session).file("level.dat", "two", true);
            second.base = Some(first_receipt.snapshot_id);
            let staged = fixture.stage(&second);
            let validated = fixture.validate(&second, &staged).unwrap();
            let Prepared::Candidate(candidate) = fixture
                .snapshots
                .prepare(fixture.host_id, &validated, &staged, &chunks)
                .unwrap()
            else {
                panic!("candidate expected")
            };
            let result = fixture.snapshots.commit_with_state_writer(
                fixture.host_id,
                *candidate,
                &chunks,
                |path, bytes| {
                    if fail_after_write {
                        atomic_write(path, bytes)?;
                    }
                    bail!("injected pointer durability failure")
                },
            );
            assert!(matches!(result, Err(CommitError::Storage(_))));
            let status = fixture.snapshots.status(fixture.host_id, &chunks).unwrap();
            assert_eq!(
                status.latest.unwrap().sequence,
                if fail_after_write { 2 } else { 1 }
            );
            assert_eq!(
                fixture.member_bytes("world/level.dat"),
                if fail_after_write { b"two" } else { b"one" }
            );
            if fail_after_write {
                assert!(matches!(
                    fixture.submit(&second, &chunks).unwrap(),
                    CommitOutcome::Idempotent(_)
                ));
            }
            fixture.snapshots.recover().unwrap();
        }
    }

    #[test]
    fn restart_recovers_only_owned_orphans_and_preserves_legacy_storage() {
        let fixture = Fixture::new();
        let chunks = roster(0, &[]);
        let receipt = committed(
            fixture
                .submit(
                    &fixture
                        .upload(1, 0, Uuid::now_v7())
                        .file("level.dat", "level", true),
                    &chunks,
                )
                .unwrap(),
        );
        let directory = fixture.snapshots.directory(fixture.host_id).unwrap();
        let legacy = directory.parent().unwrap();
        fs::write(
            legacy.join("state.json"),
            b"old schema is deliberately unread",
        )
        .unwrap();
        let old_zip = legacy.join(format!("{}.zip", Uuid::now_v7()));
        fs::write(&old_zip, b"legacy bytes").unwrap();
        let orphan = directory
            .join(SNAPSHOTS_DIRECTORY)
            .join(Uuid::now_v7().to_string());
        let staging = directory
            .join(STAGING_DIRECTORY)
            .join(Uuid::now_v7().to_string());
        fs::create_dir(&orphan).unwrap();
        fs::create_dir(&staging).unwrap();
        let unrelated = directory.join(STAGING_DIRECTORY).join("keep-me");
        fs::create_dir(&unrelated).unwrap();
        let restarted = WorldSnapshotStore::new(fixture.store.clone(), fixture.snapshots.limits());
        restarted.recover().unwrap();
        assert_eq!(
            restarted
                .status(fixture.host_id, &chunks)
                .unwrap()
                .latest
                .unwrap()
                .snapshot_id,
            receipt.snapshot_id
        );
        assert!(!orphan.exists());
        for _ in 0..100 {
            if !staging.exists() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        assert!(!staging.exists());
        assert!(unrelated.is_dir());
        assert_eq!(fs::read(old_zip).unwrap(), b"legacy bytes");
        assert_eq!(
            fs::read(legacy.join("state.json")).unwrap(),
            b"old schema is deliberately unread"
        );
    }

    #[test]
    fn incomplete_internal_manifest_is_rejected_even_with_matching_manifest_hash() {
        let fixture = Fixture::new();
        let chunks = roster(0, &[]);
        let receipt = committed(
            fixture
                .submit(
                    &fixture
                        .upload(1, 0, Uuid::now_v7())
                        .file("level.dat", "level", true),
                    &chunks,
                )
                .unwrap(),
        );
        let root = fixture.snapshots.directory(fixture.host_id).unwrap();
        let snapshot = fixture
            .snapshots
            .retained_archive(fixture.host_id, receipt.snapshot_id)
            .unwrap()
            .unwrap();
        let mut manifest: StorageManifest =
            serde_json::from_slice(&fs::read(snapshot.join(MANIFEST_ENTRY)).unwrap()).unwrap();
        manifest.files.retain(|file| file.path != "world/level.dat");
        let bytes = serde_json::to_vec(&manifest).unwrap();
        fs::write(snapshot.join(MANIFEST_ENTRY), &bytes).unwrap();
        let mut state = read_state(&root.join(STATE_NAME)).unwrap();
        state.latest.as_mut().unwrap().manifest_sha1 = sha1_bytes(&bytes);
        state.last_upload.as_mut().unwrap().manifest_sha1 = sha1_bytes(&bytes);
        atomic_write(&root.join(STATE_NAME), &serde_json::to_vec(&state).unwrap()).unwrap();
        assert!(fixture.snapshots.status(fixture.host_id, &chunks).is_err());
        assert!(fixture.snapshots.recover().is_err());
    }

    #[cfg(unix)]
    #[test]
    fn directory_symlinks_and_undeclared_files_are_not_trusted() {
        use std::os::unix::fs::symlink;
        let fixture = Fixture::new();
        let chunks = roster(0, &[]);
        let receipt = committed(
            fixture
                .submit(
                    &fixture
                        .upload(1, 0, Uuid::now_v7())
                        .file("level.dat", "level", true),
                    &chunks,
                )
                .unwrap(),
        );
        let snapshot = fixture
            .snapshots
            .retained_archive(fixture.host_id, receipt.snapshot_id)
            .unwrap()
            .unwrap();
        fs::write(snapshot.join("extra"), b"unexpected").unwrap();
        assert!(fixture.snapshots.status(fixture.host_id, &chunks).is_err());
        fs::rename(snapshot.join("extra"), fixture.work.join("extra")).unwrap();
        fs::rename(snapshot.join("world"), fixture.work.join("world")).unwrap();
        symlink(fixture.work.join("world"), snapshot.join("world")).unwrap();
        assert!(fixture.snapshots.status(fixture.host_id, &chunks).is_err());
    }

    #[test]
    fn materialized_paths_reject_windows_aliases_and_implicit_parent_case_collisions() {
        let fixture = Fixture::new();
        for name in ["CON.dat", "data/file.", "data/file ", "data/a?b"] {
            let spec = fixture
                .upload(1, 0, Uuid::now_v7())
                .file("level.dat", "level", true)
                .file(name, "data", true);
            assert!(fixture.validate(&spec, &fixture.stage(&spec)).is_err());
        }
        let spec = fixture
            .upload(1, 0, Uuid::now_v7())
            .file("level.dat", "level", true)
            .file("Data/first", "a", true)
            .file("data/second", "b", true);
        assert!(fixture.validate(&spec, &fixture.stage(&spec)).is_err());
    }

    // --- NBT reader -----------------------------------------------------

    #[test]
    fn chunk_nbt_parsing_accepts_chunk_documents_and_rejects_malformed_ones() {
        let root = parse_chunk_nbt(&terrain_nbt(-3, 4)).unwrap();
        assert_eq!(root_int(&root, "xPos"), Some(-3));
        assert_eq!(root_int(&root, "zPos"), Some(4));
        assert_eq!(root_int(&root, "missing"), None);
        let root = parse_chunk_nbt(&entities_nbt(1, 2)).unwrap();
        assert_eq!(root_int_array(&root, "Position"), Some(&[1, 2][..]));
        assert!(parse_chunk_nbt(&poi_nbt()).unwrap().is_empty());

        assert!(parse_chunk_nbt(&[]).is_err());
        // A root that is not a compound.
        assert!(parse_chunk_nbt(&[3, 0, 0, 0, 0, 0, 1]).is_err());
        // An unknown tag id.
        assert!(parse_chunk_nbt(&[10, 0, 0, 99, 0, 0, 0]).is_err());
        // A truncated payload.
        assert!(parse_chunk_nbt(&[10, 0, 0, 3, 0, 1, b'a', 0, 0]).is_err());
        // An array whose declared length runs past the buffer.
        assert!(
            parse_chunk_nbt(&[
                10, 0, 0, 11, 0, 1, b'a', 0x7f, 0xff, 0xff, 0xff, 0, 0, 0, 1, 0
            ])
            .is_err()
        );
        // A list whose element count exceeds the configured sequence limit.
        let mut huge_list = vec![10_u8, 0, 0, 9, 0, 1, b'l', 3];
        huge_list.extend_from_slice(&(MAX_NBT_SEQUENCE_LEN as i32 + 1).to_be_bytes());
        huge_list.push(0);
        assert!(parse_chunk_nbt(&huge_list).is_err());
    }
}
