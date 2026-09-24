use crate::{
    firm_sections::{FirmSectionSnapshot, validate_dimension_id},
    store::{HostStore, validate_entry_path},
};
use anyhow::{Context, Result, bail};
use sha1::{Digest, Sha1};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
use uuid::Uuid;
use zip::ZipArchive;

pub const MAX_EXPANDED_BYTES: u64 = 257 * 1024 * 1024;
pub const MAX_NBT_BYTES: u64 = 64 * 1024 * 1024;
pub const MAX_METADATA_BYTES: u64 = 1024 * 1024;
pub const MAX_FILES: usize = 1024;
const FIRM_CHUNK_FORMAT_VERSION: u32 = 3;

static TEMP_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotRecord {
    pub snapshot_id: Uuid,
    pub session_id: Uuid,
    pub sequence: i64,
    pub firm_section_revision: i64,
    pub sha1: String,
    pub zip_bytes: u64,
    pub columns: usize,
    #[serde(default)]
    pub total_columns: usize,
    #[serde(default)]
    pub complete: bool,
    pub captured_at: String,
    pub stored_at: u64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadReceipt {
    pub snapshot_id: Uuid,
    pub session_id: Uuid,
    pub sequence: i64,
    pub firm_section_revision: i64,
    pub sha1: String,
    pub zip_bytes: u64,
    pub columns: usize,
    pub stored_columns: usize,
    pub total_columns: usize,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Default)]
struct PointerState {
    latest: Option<SnapshotRecord>,
    previous: Option<SnapshotRecord>,
    #[serde(default)]
    last_upload: Option<UploadReceipt>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct Metadata {
    format_version: u32,
    kind: String,
    host_id: Uuid,
    session_id: Uuid,
    sequence: i64,
    firm_section_revision: i64,
    captured_at: String,
    columns: usize,
    total_columns: usize,
    complete: bool,
    restore_safe: bool,
    capture_mode: String,
    chunks: Vec<ChunkMetadata>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct ChunkMetadata {
    dimension_id: String,
    chunk_x: i32,
    chunk_z: i32,
    entities_present: bool,
    poi_present: bool,
    #[serde(default)]
    membership_revision: i64,
    #[serde(default)]
    captured_at: Option<String>,
}

#[derive(Debug, Clone, Copy, serde::Serialize)]
#[serde(rename_all = "PascalCase")]
pub enum ErrorReason {
    InvalidRequest,
    SessionUnavailable,
    RevisionConflict,
    SequenceConflict,
    StorageError,
    TooLarge,
    Busy,
    SnapshotNotFound,
}

#[derive(Clone)]
pub struct FirmChunkStore {
    store: HostStore,
}

#[derive(Debug)]
pub enum CommitOutcome {
    Committed(UploadReceipt),
    Idempotent(UploadReceipt),
}

#[derive(Debug)]
pub enum CommitError {
    Invalid(anyhow::Error),
    RevisionConflict,
    SequenceConflict,
    Storage(anyhow::Error),
}

impl FirmChunkStore {
    pub fn new(store: HostStore) -> Self {
        Self { store }
    }

    pub fn validate_archive(
        &self,
        path: &Path,
        host_id: Uuid,
        session_id: Uuid,
        sequence: i64,
        revision: i64,
        declared_sha1: &str,
        max_upload_bytes: u64,
    ) -> Result<ValidatedArchive> {
        let metadata = validate_archive(path, host_id, session_id, sequence, revision)?;
        let size = fs::metadata(path)?.len();
        if size > max_upload_bytes {
            bail!("compressed snapshot exceeds upload limit")
        }
        let sha1 = sha1_file(path)?;
        if sha1 != declared_sha1 {
            bail!("snapshot SHA-1 does not match header")
        }
        Ok(ValidatedArchive {
            metadata,
            sha1,
            zip_bytes: size,
        })
    }

    pub fn commit(
        &self,
        host_id: Uuid,
        validated: ValidatedArchive,
        staged: PathBuf,
        sections: &FirmSectionSnapshot,
        max_archive_bytes: u64,
    ) -> std::result::Result<CommitOutcome, CommitError> {
        let directory = self.directory(host_id).map_err(CommitError::Storage)?;
        fs::create_dir_all(&directory).map_err(|e| CommitError::Storage(e.into()))?;
        let state_path = directory.join("state.json");
        let state = read_state(&state_path).map_err(CommitError::Storage)?;
        validated
            .validate_subset(sections)
            .map_err(CommitError::Invalid)?;
        if let Some(record) = state.latest.as_ref() {
            validate_persisted_archive(&directory, host_id, record)
                .map_err(CommitError::Storage)?;
            let Some(receipt) = state.last_upload.as_ref() else {
                return Err(CommitError::Storage(anyhow::anyhow!(
                    "latest FirmChunk archive has no lastUpload receipt"
                )));
            };
            if receipt.snapshot_id != record.snapshot_id
                || receipt.session_id != record.session_id
                || receipt.sequence != record.sequence
                || receipt.firm_section_revision != record.firm_section_revision
                || receipt.stored_columns != record.columns
                || receipt.total_columns != record.total_columns
            {
                return Err(CommitError::Storage(anyhow::anyhow!(
                    "lastUpload does not match latest FirmChunk archive"
                )));
            }
        }
        if let Some(existing) = state.last_upload.as_ref() {
            if existing.session_id == validated.metadata.session_id {
                if validated.metadata.sequence < existing.sequence {
                    return Err(CommitError::SequenceConflict);
                }
                if validated.metadata.sequence == existing.sequence {
                    if existing.sha1 == validated.sha1
                        && existing.firm_section_revision
                            == validated.metadata.firm_section_revision
                    {
                        let archive = directory.join(format!("{}.zip", existing.snapshot_id));
                        let mut retry_state = state.clone();
                        retry_state.last_upload = Some(existing.clone());
                        republish_existing(&state_path, &retry_state, &archive)
                            .map_err(CommitError::Storage)?;
                        return Ok(CommitOutcome::Idempotent(existing.clone()));
                    }
                    return Err(CommitError::SequenceConflict);
                }
            }
        }
        let current_columns = distinct_columns(sections);
        let incoming = read_archive(&staged).map_err(CommitError::Invalid)?;
        let old = if let Some(previous) = state.latest.as_ref() {
            let path = directory.join(format!("{}.zip", previous.snapshot_id));
            Some(read_archive(&path).map_err(CommitError::Storage)?)
        } else {
            None
        };
        let merged =
            merge_archives(old.as_ref(), &incoming, sections).map_err(CommitError::Invalid)?;
        let merged_path = directory.join(format!(".merge-{}.zip", Uuid::now_v7()));
        let _merged_cleanup = TempFile::new(merged_path.clone());
        write_archive(&merged_path, &merged).map_err(CommitError::Storage)?;
        let merged_size = fs::metadata(&merged_path)
            .map_err(|error| CommitError::Storage(error.into()))?
            .len();
        if merged_size > max_archive_bytes {
            return Err(CommitError::Invalid(anyhow::anyhow!(
                "cumulative FirmChunk archive exceeds upload limit"
            )));
        }
        let merged_hash = sha1_file(&merged_path).map_err(CommitError::Storage)?;
        let stored_columns = merged.metadata.chunks.len();
        let total_columns = current_columns.len();
        let record = SnapshotRecord {
            snapshot_id: Uuid::now_v7(),
            session_id: validated.metadata.session_id,
            sequence: validated.metadata.sequence,
            firm_section_revision: validated.metadata.firm_section_revision,
            sha1: merged_hash.clone(),
            zip_bytes: merged_size,
            columns: stored_columns,
            total_columns,
            complete: stored_columns == total_columns,
            captured_at: merged.metadata.captured_at.clone(),
            stored_at: now_millis(),
        };
        validate_archive(
            &merged_path,
            host_id,
            record.session_id,
            record.sequence,
            record.firm_section_revision,
        )
        .map_err(CommitError::Invalid)?;
        let receipt = UploadReceipt {
            snapshot_id: record.snapshot_id,
            session_id: validated.metadata.session_id,
            sequence: validated.metadata.sequence,
            firm_section_revision: validated.metadata.firm_section_revision,
            sha1: validated.sha1.clone(),
            zip_bytes: validated.zip_bytes,
            columns: validated.metadata.columns,
            stored_columns,
            total_columns,
        };
        let archive = directory.join(format!("{}.zip", record.snapshot_id));
        let result = self.publish(
            &directory,
            &state_path,
            &state,
            &record,
            &receipt,
            &archive,
            &merged_path,
        );
        if let Err(error) = result {
            cleanup_owned_archive(&archive, &state_path, record.snapshot_id);
            return Err(CommitError::Storage(error));
        }
        let next = PointerState {
            latest: Some(record),
            previous: state.latest,
            last_upload: Some(receipt.clone()),
        };
        cleanup_archives(&directory, &next);
        Ok(CommitOutcome::Committed(receipt))
    }

    fn publish(
        &self,
        directory: &Path,
        state_path: &Path,
        state: &PointerState,
        record: &SnapshotRecord,
        receipt: &UploadReceipt,
        archive: &Path,
        staged: &Path,
    ) -> Result<()> {
        let staged_file = OpenOptions::new().read(true).write(true).open(staged)?;
        staged_file.sync_all()?;
        drop(staged_file);
        rename_replace(staged, archive, false)?;
        sync_parent(directory)?;
        let next = PointerState {
            latest: Some(record.clone()),
            previous: state.latest.clone(),
            last_upload: Some(receipt.clone()),
        };
        let bytes = serde_json::to_vec_pretty(&next)?;
        atomic_write(state_path, &bytes)
    }

    pub fn latest(&self, host_id: Uuid) -> Result<Option<SnapshotRecord>> {
        let path = self.directory(host_id)?.join("state.json");
        Ok(read_state(&path)?.latest)
    }

    pub fn status(&self, host_id: Uuid, sections: &FirmSectionSnapshot) -> Result<ChunkStatus> {
        let directory = self.directory(host_id)?;
        let state = read_state(&directory.join("state.json"))?;
        let Some(snapshot) = state.latest else {
            return Ok(ChunkStatus {
                snapshot: None,
                stored_columns: 0,
                total_columns: distinct_columns(sections).len(),
                complete: distinct_columns(sections).is_empty(),
            });
        };
        let path = directory.join(format!("{}.zip", snapshot.snapshot_id));
        let stored = if path.is_file() {
            read_archive_metadata(&path)?.chunks
        } else {
            Vec::new()
        };
        let valid = valid_chunk_count(&stored, sections);
        let total = distinct_columns(sections).len();
        Ok(ChunkStatus {
            snapshot: Some(snapshot),
            stored_columns: valid,
            total_columns: total,
            complete: valid == total,
        })
    }

    pub fn latest_path(&self, host_id: Uuid) -> Result<Option<PathBuf>> {
        let directory = self.directory(host_id)?;
        let Some(record) = read_state(&directory.join("state.json"))?.latest else {
            return Ok(None);
        };
        let path = directory.join(format!("{}.zip", record.snapshot_id));
        if path.is_file() {
            Ok(Some(path))
        } else {
            Ok(None)
        }
    }

    pub fn latest_path_checked(
        &self,
        host_id: Uuid,
        sections: &FirmSectionSnapshot,
    ) -> Result<Option<PathBuf>> {
        let directory = self.directory(host_id)?;
        let state = read_state(&directory.join("state.json"))?;
        let Some(record) = state.latest else {
            return Ok(None);
        };
        let path = directory.join(format!("{}.zip", record.snapshot_id));
        if !path.is_file() {
            return Ok(None);
        }
        let archive = read_archive(&path)?;
        if valid_chunk_count(&archive.metadata.chunks, sections) != archive.metadata.chunks.len() {
            bail!("FirmChunk archive contains obsolete membership")
        }
        Ok(Some(path))
    }

    fn directory(&self, host_id: Uuid) -> Result<PathBuf> {
        let root = self.store.host_root(host_id);
        if !root.is_dir() || self.store.get(host_id)?.is_none() {
            bail!("host not found")
        }
        Ok(root.join("firm-chunks"))
    }
}

pub struct ValidatedArchive {
    metadata: Metadata,
    sha1: String,
    zip_bytes: u64,
}

#[derive(Debug, Clone)]
pub struct ChunkStatus {
    pub snapshot: Option<SnapshotRecord>,
    pub stored_columns: usize,
    pub total_columns: usize,
    pub complete: bool,
}

#[derive(Debug, Clone)]
struct ArchiveData {
    metadata: Metadata,
    entries: BTreeMap<String, Vec<u8>>,
}

impl ValidatedArchive {
    pub fn columns_match(&self, sections: &FirmSectionSnapshot) -> bool {
        let expected = distinct_columns(sections);
        let actual = self
            .metadata
            .chunks
            .iter()
            .map(|chunk| (chunk.dimension_id.clone(), chunk.chunk_x, chunk.chunk_z))
            .collect::<HashSet<_>>();
        expected == actual
    }

    pub fn validate_subset(&self, sections: &FirmSectionSnapshot) -> Result<()> {
        let expected = distinct_columns(sections);
        let actual = self
            .metadata
            .chunks
            .iter()
            .map(|chunk| (chunk.dimension_id.clone(), chunk.chunk_x, chunk.chunk_z))
            .collect::<HashSet<_>>();
        if actual.is_empty() {
            bail!("v3 FirmChunk archive must contain at least one column")
        }
        if !actual.is_subset(&expected) {
            bail!("FirmChunk archive contains an out-of-roster column")
        }
        if self.metadata.total_columns != expected.len() {
            bail!("FirmChunk totalColumns does not match the authoritative roster")
        }
        Ok(())
    }
}

pub fn stream_sha1_update(hasher: &mut Sha1, bytes: &[u8]) {
    hasher.update(bytes);
}
pub fn finalize_sha1(hasher: Sha1) -> String {
    hex_lower(&hasher.finalize())
}

fn decode_metadata(bytes: &[u8]) -> Result<Metadata> {
    let metadata: Metadata = serde_json::from_slice(bytes).context("invalid metadata.json")?;
    if metadata.format_version != FIRM_CHUNK_FORMAT_VERSION {
        bail!("unsupported FirmChunk metadata format version")
    }
    for chunk in &metadata.chunks {
        validate_dimension_id(&chunk.dimension_id).context("invalid chunk dimensionId")?;
    }
    Ok(metadata)
}

fn validate_archive(
    path: &Path,
    host_id: Uuid,
    session_id: Uuid,
    sequence: i64,
    revision: i64,
) -> Result<Metadata> {
    let file = File::open(path)?;
    let mut archive = ZipArchive::new(file).context("invalid snapshot ZIP")?;
    if archive.len() > MAX_FILES {
        bail!("snapshot contains too many files")
    }
    let mut metadata_bytes = None;
    let mut names = HashSet::new();
    let mut expanded = 0_u64;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).context("invalid ZIP entry")?;
        let name = entry.name().to_owned();
        validate_entry_path(&name)?;
        if entry.is_dir()
            || entry
                .unix_mode()
                .is_some_and(|mode| mode & 0o170000 == 0o120000)
        {
            bail!("snapshot directories and symlinks are not allowed")
        }
        if !names.insert(name.clone()) {
            bail!("duplicate ZIP path")
        }
        let declared = entry.size();
        if declared > MAX_EXPANDED_BYTES {
            bail!("snapshot entry is too large")
        }
        let limit = if name == "metadata.json" {
            MAX_METADATA_BYTES
        } else {
            MAX_NBT_BYTES
        };
        if declared > limit {
            bail!("snapshot entry exceeds size limit")
        }
        let mut metadata_capture = (name == "metadata.json")
            .then(|| Vec::with_capacity(declared.min(1024 * 1024) as usize));
        let actual = read_entry_bounded(&mut entry, limit, metadata_capture.as_mut())
            .context("invalid ZIP entry data")?;
        if actual != declared {
            bail!("truncated ZIP entry")
        }
        expanded = expanded.saturating_add(actual);
        if expanded > MAX_EXPANDED_BYTES {
            bail!("expanded snapshot exceeds limit")
        }
        if name == "metadata.json" {
            metadata_bytes = metadata_capture;
        }
    }
    let bytes = metadata_bytes.context("metadata.json is required")?;
    let metadata = decode_metadata(&bytes)?;
    if metadata.format_version != FIRM_CHUNK_FORMAT_VERSION
        || metadata.kind != "firmchunk-snapshot"
        || metadata.host_id != host_id
        || metadata.session_id != session_id
        || metadata.sequence != sequence
        || metadata.firm_section_revision != revision
        || metadata.restore_safe
        || metadata.capture_mode != "memory"
        || metadata.columns != metadata.chunks.len()
        || metadata.total_columns < metadata.columns
        || metadata.complete != (metadata.columns == metadata.total_columns)
        || !is_iso_timestamp(&metadata.captured_at)
    {
        bail!("snapshot metadata does not match request")
    }
    let mut seen = HashSet::new();
    let mut referenced = HashSet::from([String::from("metadata.json")]);
    for chunk in &metadata.chunks {
        let key = (chunk.dimension_id.clone(), chunk.chunk_x, chunk.chunk_z);
        if !seen.insert(key) {
            bail!("snapshot contains duplicate columns")
        }
        if let Some(captured_at) = &chunk.captured_at {
            if !is_iso_timestamp(captured_at) {
                bail!("invalid chunk capturedAt")
            }
        }
        let terrain = expected_path(&chunk.dimension_id, "terrain", chunk.chunk_x, chunk.chunk_z)?;
        referenced.insert(terrain);
        if chunk.entities_present {
            referenced.insert(expected_path(
                &chunk.dimension_id,
                "entities",
                chunk.chunk_x,
                chunk.chunk_z,
            )?);
        }
        if chunk.poi_present {
            referenced.insert(expected_path(
                &chunk.dimension_id,
                "poi",
                chunk.chunk_x,
                chunk.chunk_z,
            )?);
        }
    }
    if names != referenced {
        bail!("snapshot contains unreferenced or missing files")
    }
    if metadata.chunks.is_empty() {
        bail!("v3 snapshot must contain at least one column")
    }
    Ok(metadata)
}

fn read_archive(path: &Path) -> Result<ArchiveData> {
    let file = File::open(path)?;
    let mut archive = ZipArchive::new(file).context("invalid snapshot ZIP")?;
    if archive.len() > MAX_FILES {
        bail!("snapshot contains too many files")
    }
    let mut entries = BTreeMap::new();
    let mut expanded = 0_u64;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index)?;
        let name = entry.name().to_owned();
        let limit = if name == "metadata.json" {
            MAX_METADATA_BYTES
        } else {
            MAX_NBT_BYTES
        };
        if entry.size() > limit {
            bail!("snapshot entry exceeds size limit")
        }
        let mut bytes = Vec::new();
        read_entry_bounded(&mut entry, limit, Some(&mut bytes))?;
        expanded = expanded.saturating_add(bytes.len() as u64);
        if expanded > MAX_EXPANDED_BYTES {
            bail!("expanded snapshot exceeds limit")
        }
        entries.insert(name, bytes);
    }
    let metadata = decode_metadata(entries.get("metadata.json").context("metadata missing")?)?;
    Ok(ArchiveData { metadata, entries })
}

fn read_archive_metadata(path: &Path) -> Result<Metadata> {
    let file = File::open(path)?;
    let mut archive = ZipArchive::new(file).context("invalid snapshot ZIP")?;
    let mut metadata = None;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index)?;
        if entry.name() == "metadata.json" {
            let mut bytes = Vec::new();
            read_entry_bounded(&mut entry, MAX_METADATA_BYTES, Some(&mut bytes))?;
            metadata = Some(decode_metadata(&bytes)?);
            break;
        }
    }
    metadata.context("metadata missing")
}

fn section_column_revisions(snapshot: &FirmSectionSnapshot) -> HashMap<(String, i32, i32), i64> {
    let explicit: HashMap<_, _> = snapshot
        .column_revisions
        .iter()
        .map(|column| {
            (
                (column.dimension_id.clone(), column.chunk_x, column.chunk_z),
                column.revision,
            )
        })
        .collect();
    distinct_columns(snapshot)
        .into_iter()
        .map(|key| {
            let revision = explicit.get(&key).copied().unwrap_or(0);
            (key, revision)
        })
        .collect()
}

fn valid_chunk_count(chunks: &[ChunkMetadata], sections: &FirmSectionSnapshot) -> usize {
    let revisions = section_column_revisions(sections);
    chunks
        .iter()
        .filter(|chunk| {
            revisions
                .get(&(chunk.dimension_id.clone(), chunk.chunk_x, chunk.chunk_z))
                .is_some_and(|revision| *revision == chunk.membership_revision)
        })
        .count()
}

fn merge_archives(
    previous: Option<&ArchiveData>,
    incoming: &ArchiveData,
    sections: &FirmSectionSnapshot,
) -> Result<ArchiveData> {
    let current = section_column_revisions(sections);
    let incoming_keys: HashSet<_> = incoming
        .metadata
        .chunks
        .iter()
        .map(|chunk| (chunk.dimension_id.clone(), chunk.chunk_x, chunk.chunk_z))
        .collect();
    let mut chunks = BTreeMap::new();
    let mut entries = BTreeMap::new();
    for chunk in &incoming.metadata.chunks {
        let key = (chunk.dimension_id.clone(), chunk.chunk_x, chunk.chunk_z);
        let mut chunk = chunk.clone();
        chunk.membership_revision = current.get(&key).copied().unwrap_or(0);
        if chunk.captured_at.is_none() {
            chunk.captured_at = Some(incoming.metadata.captured_at.clone());
        }
        copy_chunk_entries(&mut entries, &incoming.entries, &chunk)?;
        chunks.insert(key, chunk);
    }
    if let Some(previous) = previous {
        for old in &previous.metadata.chunks {
            let key = (old.dimension_id.clone(), old.chunk_x, old.chunk_z);
            if incoming_keys.contains(&key) {
                continue;
            }
            if current.get(&key).copied() != Some(old.membership_revision) {
                continue;
            }
            let mut chunk = old.clone();
            if chunk.captured_at.is_none() {
                chunk.captured_at = Some(previous.metadata.captured_at.clone());
            }
            copy_chunk_entries(&mut entries, &previous.entries, &chunk)?;
            chunks.insert(key, chunk);
        }
    }
    let mut metadata = incoming.metadata.clone();
    metadata.format_version = FIRM_CHUNK_FORMAT_VERSION;
    metadata.columns = chunks.len();
    metadata.total_columns = current.len();
    metadata.complete = chunks.len() == current.len();
    metadata.chunks = chunks.into_values().collect();
    let metadata_bytes = serde_json::to_vec(&metadata)?;
    if metadata_bytes.len() as u64 > MAX_METADATA_BYTES {
        bail!("cumulative metadata exceeds limit")
    }
    entries.insert("metadata.json".into(), metadata_bytes);
    if entries.len() > MAX_FILES {
        bail!("cumulative snapshot contains too many files")
    }
    if entries.iter().any(|(name, bytes)| {
        bytes.len() as u64
            > if name == "metadata.json" {
                MAX_METADATA_BYTES
            } else {
                MAX_NBT_BYTES
            }
    }) {
        bail!("cumulative snapshot entry exceeds limit")
    }
    if entries.values().map(|v| v.len() as u64).sum::<u64>() > MAX_EXPANDED_BYTES {
        bail!("expanded cumulative snapshot exceeds limit")
    }
    Ok(ArchiveData { metadata, entries })
}

fn copy_chunk_entries(
    target: &mut BTreeMap<String, Vec<u8>>,
    source: &BTreeMap<String, Vec<u8>>,
    chunk: &ChunkMetadata,
) -> Result<()> {
    let terrain_path = expected_path(&chunk.dimension_id, "terrain", chunk.chunk_x, chunk.chunk_z)?;
    target.insert(
        terrain_path.clone(),
        source
            .get(&terrain_path)
            .context("terrain entry missing")?
            .clone(),
    );
    if chunk.entities_present {
        let path = expected_path(
            &chunk.dimension_id,
            "entities",
            chunk.chunk_x,
            chunk.chunk_z,
        )?;
        target.insert(
            path.clone(),
            source.get(&path).context("entities entry missing")?.clone(),
        );
    }
    if chunk.poi_present {
        let path = expected_path(&chunk.dimension_id, "poi", chunk.chunk_x, chunk.chunk_z)?;
        target.insert(
            path.clone(),
            source.get(&path).context("poi entry missing")?.clone(),
        );
    }
    Ok(())
}

fn write_archive(path: &Path, archive: &ArchiveData) -> Result<()> {
    let file = OpenOptions::new().create_new(true).write(true).open(path)?;
    let mut writer = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    for (name, bytes) in &archive.entries {
        writer.start_file(name, options)?;
        writer.write_all(bytes)?;
    }
    let file = writer.finish()?;
    file.sync_all()?;
    Ok(())
}

fn read_entry_bounded<R: Read>(
    reader: &mut R,
    limit: u64,
    mut captured: Option<&mut Vec<u8>>,
) -> Result<u64> {
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let count = reader.read(&mut buffer)?;
        if count == 0 {
            return Ok(total);
        }
        total = total.saturating_add(count as u64);
        if total > limit {
            bail!("snapshot entry exceeds size limit")
        }
        if let Some(bytes) = captured.as_deref_mut() {
            bytes.extend_from_slice(&buffer[..count]);
        }
    }
}

fn distinct_columns(sections: &FirmSectionSnapshot) -> HashSet<(String, i32, i32)> {
    sections
        .sections
        .iter()
        .map(|s| (s.dimension_id.clone(), s.chunk_x, s.chunk_z))
        .collect()
}

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
    let leap = year_is_leap(
        u32::from(bytes[0] - b'0') * 1000
            + u32::from(bytes[1] - b'0') * 100
            + u32::from(bytes[2] - b'0') * 10
            + u32::from(bytes[3] - b'0'),
    );
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

fn year_is_leap(year: u32) -> bool {
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}

fn expected_path(dimension: &str, kind: &str, x: i32, z: i32) -> Result<String> {
    validate_dimension_id(dimension)?;
    let (namespace, path) = dimension
        .split_once(':')
        .context("dimensionId must be a resource location")?;
    Ok(format!("dimensions/{namespace}/{path}/{kind}/{x}.{z}.nbt"))
}

fn read_state(path: &Path) -> Result<PointerState> {
    match fs::read(path) {
        Ok(bytes) => Ok(serde_json::from_slice(&bytes)?),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(PointerState::default()),
        Err(error) => Err(error.into()),
    }
}

fn validate_persisted_archive(
    directory: &Path,
    host_id: Uuid,
    record: &SnapshotRecord,
) -> Result<Metadata> {
    let archive = directory.join(format!("{}.zip", record.snapshot_id));
    if !archive.is_file() {
        bail!("latest FirmChunk archive is missing")
    }
    let metadata = validate_archive(
        &archive,
        host_id,
        record.session_id,
        record.sequence,
        record.firm_section_revision,
    )?;
    let bytes = fs::metadata(&archive)?.len();
    if bytes != record.zip_bytes {
        bail!("latest FirmChunk archive size does not match state")
    }
    if sha1_file(&archive)? != record.sha1 {
        bail!("latest FirmChunk archive hash does not match state")
    }
    if metadata.columns != record.columns {
        bail!("latest FirmChunk archive columns do not match state")
    }
    if metadata.total_columns != record.total_columns || metadata.complete != record.complete {
        bail!("latest FirmChunk archive coverage does not match state")
    }
    Ok(metadata)
}

fn sha1_file(path: &Path) -> Result<String> {
    let mut file = File::open(path)?;
    let mut hasher = Sha1::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
    }
    Ok(hex_lower(&hasher.finalize()))
}

fn hex_lower(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().context("state path has no parent")?;
    let temp = parent.join(format!(
        ".state.tmp-{}-{}",
        Uuid::now_v7(),
        TEMP_COUNTER.fetch_add(1, Ordering::Relaxed)
    ));
    let result = (|| {
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        drop(file);
        rename_replace(&temp, path, true)?;
        sync_parent(parent)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result
}

fn republish_existing(state_path: &Path, state: &PointerState, archive: &Path) -> Result<()> {
    let file = OpenOptions::new().read(true).write(true).open(archive)?;
    file.sync_all()?;
    drop(file);
    atomic_write(state_path, &serde_json::to_vec_pretty(state)?)
}

fn rename_replace(source: &Path, destination: &Path, replace: bool) -> Result<()> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::{
            MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH, MoveFileExW,
        };
        let source: Vec<u16> = source
            .as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let destination: Vec<u16> = destination
            .as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let mut flags = MOVEFILE_WRITE_THROUGH;
        if replace {
            flags |= MOVEFILE_REPLACE_EXISTING;
        }
        if unsafe { MoveFileExW(source.as_ptr(), destination.as_ptr(), flags) } == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        let _ = replace;
        fs::rename(source, destination).map_err(Into::into)
    }
}

fn sync_parent(_path: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        File::open(_path)?.sync_all()?;
    }
    Ok(())
}

fn cleanup_archives(directory: &Path, state: &PointerState) {
    let keep: HashSet<String> = state
        .latest
        .iter()
        .chain(state.previous.iter())
        .map(|r| format!("{}.zip", r.snapshot_id))
        .collect();
    let Ok(entries) = fs::read_dir(directory) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().is_some_and(|e| e == "zip")
            && path.file_name().is_some_and(|n| {
                let name = n.to_string_lossy();
                let stem = name.strip_suffix(".zip").unwrap_or_default();
                Uuid::parse_str(stem).is_ok_and(|id| id.get_version_num() == 7)
                    && !keep.contains(name.as_ref())
            })
        {
            let _ = fs::remove_file(path);
        }
    }
}

fn cleanup_owned_archive(path: &Path, state_path: &Path, id: Uuid) {
    let Ok(state) = read_state(state_path) else {
        return;
    };
    let referenced = state
        .latest
        .as_ref()
        .is_some_and(|record| record.snapshot_id == id)
        || state
            .previous
            .as_ref()
            .is_some_and(|record| record.snapshot_id == id);
    if !referenced {
        let _ = fs::remove_file(path);
    }
}

struct TempFile {
    path: PathBuf,
}

impl TempFile {
    fn new(path: PathBuf) -> Self {
        Self { path }
    }
}

impl Drop for TempFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::firm_sections::ColumnRevision;

    fn sections() -> FirmSectionSnapshot {
        FirmSectionSnapshot {
            revision: 2,
            sections: vec![
                crate::model::FirmSection {
                    dimension_id: "minecraft:overworld".into(),
                    chunk_x: 1,
                    section_y: 0,
                    chunk_z: 2,
                    owner_uuid: Uuid::now_v7(),
                },
                crate::model::FirmSection {
                    dimension_id: "minecraft:overworld".into(),
                    chunk_x: 3,
                    section_y: 0,
                    chunk_z: 4,
                    owner_uuid: Uuid::now_v7(),
                },
            ],
            column_revisions: vec![
                ColumnRevision {
                    dimension_id: "minecraft:overworld".into(),
                    chunk_x: 1,
                    chunk_z: 2,
                    revision: 1,
                },
                ColumnRevision {
                    dimension_id: "minecraft:overworld".into(),
                    chunk_x: 3,
                    chunk_z: 4,
                    revision: 1,
                },
            ],
        }
    }

    fn archive(columns: Vec<((i32, i32), Vec<u8>, Option<Vec<u8>>)>) -> ArchiveData {
        let host_id = Uuid::now_v7();
        let session_id = Uuid::now_v7();
        let mut chunks = Vec::new();
        let mut entries = BTreeMap::new();
        for ((x, z), terrain_bytes, entities_bytes) in columns {
            entries.insert(
                expected_path("minecraft:overworld", "terrain", x, z).unwrap(),
                terrain_bytes,
            );
            let entities_present = if let Some(bytes) = entities_bytes {
                let path = expected_path("minecraft:overworld", "entities", x, z).unwrap();
                entries.insert(path.clone(), bytes);
                true
            } else {
                false
            };
            chunks.push(ChunkMetadata {
                dimension_id: "minecraft:overworld".into(),
                chunk_x: x,
                chunk_z: z,
                entities_present,
                poi_present: false,
                membership_revision: 1,
                captured_at: Some("2026-09-20T00:00:00Z".into()),
            });
        }
        let metadata = Metadata {
            format_version: FIRM_CHUNK_FORMAT_VERSION,
            kind: "firmchunk-snapshot".into(),
            host_id,
            session_id,
            sequence: 1,
            firm_section_revision: 2,
            captured_at: "2026-09-20T00:00:00Z".into(),
            columns: chunks.len(),
            total_columns: 2,
            complete: chunks.len() == 2,
            restore_safe: false,
            capture_mode: "memory".into(),
            chunks,
        };
        entries.insert(
            "metadata.json".into(),
            serde_json::to_vec(&metadata).unwrap(),
        );
        ArchiveData { metadata, entries }
    }

    fn set_identity(
        archive: &mut ArchiveData,
        host_id: Uuid,
        session_id: Uuid,
        sequence: i64,
        revision: i64,
        format_version: u32,
    ) {
        archive.metadata.host_id = host_id;
        archive.metadata.session_id = session_id;
        archive.metadata.sequence = sequence;
        archive.metadata.firm_section_revision = revision;
        archive.metadata.format_version = format_version;
        archive.entries.insert(
            "metadata.json".into(),
            serde_json::to_vec(&archive.metadata).unwrap(),
        );
    }

    fn host_fixture() -> (tempfile::TempDir, HostStore, FirmChunkStore, Uuid) {
        let dir = tempfile::tempdir().unwrap();
        let store = HostStore::open(dir.path()).unwrap();
        let host_id = Uuid::now_v7();
        fs::create_dir(store.host_root(host_id)).unwrap();
        fs::write(
            store.host_root(host_id).join("host.json"),
            serde_json::json!({"id": host_id, "name": "test"}).to_string(),
        )
        .unwrap();
        let firm = FirmChunkStore::new(store.clone());
        (dir, store, firm, host_id)
    }

    fn staged_archive(archive: &ArchiveData, path: &Path) -> (ValidatedArchive, String, u64) {
        write_archive(path, archive).unwrap();
        let bytes = fs::metadata(path).unwrap().len();
        let hash = sha1_file(path).unwrap();
        let metadata = archive.metadata.clone();
        (
            ValidatedArchive {
                metadata,
                sha1: hash.clone(),
                zip_bytes: bytes,
            },
            hash,
            bytes,
        )
    }

    fn rewrite_metadata(archive: &mut ArchiveData) {
        archive.entries.insert(
            "metadata.json".into(),
            serde_json::to_vec(&archive.metadata).unwrap(),
        );
    }

    fn add_poi(archive: &mut ArchiveData, x: i32, z: i32, bytes: Vec<u8>) {
        archive.entries.insert(
            expected_path("minecraft:overworld", "poi", x, z).unwrap(),
            bytes,
        );
        archive
            .metadata
            .chunks
            .iter_mut()
            .find(|chunk| chunk.chunk_x == x && chunk.chunk_z == z)
            .unwrap()
            .poi_present = true;
        rewrite_metadata(archive);
    }

    #[test]
    fn paths_are_stable_and_url_safe() {
        assert_eq!(
            expected_path("minecraft:overworld", "terrain", -1, 2).unwrap(),
            "dimensions/minecraft/overworld/terrain/-1.2.nbt"
        );
        assert_eq!(
            expected_path("mod:worlds/moon.v2", "poi", 3, -4).unwrap(),
            "dimensions/mod/worlds/moon.v2/poi/3.-4.nbt"
        );
    }

    #[test]
    fn nested_dimension_paths_validate_and_unsafe_metadata_is_rejected_by_all_readers() {
        let (dir, _, _, _) = host_fixture();
        let mut nested = archive(vec![((1, 2), b"A".to_vec(), Some(b"E".to_vec()))]);
        let old_terrain = expected_path("minecraft:overworld", "terrain", 1, 2).unwrap();
        let old_entities = expected_path("minecraft:overworld", "entities", 1, 2).unwrap();
        let terrain = nested.entries.remove(&old_terrain).unwrap();
        let entities = nested.entries.remove(&old_entities).unwrap();
        nested.metadata.chunks[0].dimension_id = "mod:worlds/moon.v2".into();
        nested.entries.insert(
            expected_path("mod:worlds/moon.v2", "terrain", 1, 2).unwrap(),
            terrain,
        );
        nested.entries.insert(
            expected_path("mod:worlds/moon.v2", "entities", 1, 2).unwrap(),
            entities,
        );
        rewrite_metadata(&mut nested);
        let nested_path = dir.path().join("nested.zip");
        staged_archive(&nested, &nested_path);
        assert!(
            validate_archive(
                &nested_path,
                nested.metadata.host_id,
                nested.metadata.session_id,
                nested.metadata.sequence,
                nested.metadata.firm_section_revision,
            )
            .is_ok()
        );
        assert!(read_archive(&nested_path).is_ok());
        assert!(read_archive_metadata(&nested_path).is_ok());

        let mut unsafe_dimension = nested.clone();
        unsafe_dimension.metadata.chunks[0].dimension_id = "mod:worlds/../moon".into();
        rewrite_metadata(&mut unsafe_dimension);
        let unsafe_path = dir.path().join("unsafe.zip");
        staged_archive(&unsafe_dimension, &unsafe_path);
        assert!(
            validate_archive(
                &unsafe_path,
                unsafe_dimension.metadata.host_id,
                unsafe_dimension.metadata.session_id,
                unsafe_dimension.metadata.sequence,
                unsafe_dimension.metadata.firm_section_revision,
            )
            .is_err()
        );
        assert!(read_archive(&unsafe_path).is_err());
        assert!(read_archive_metadata(&unsafe_path).is_err());

        let mut base64_path = archive(vec![((1, 2), b"A".to_vec(), None)]);
        let readable = expected_path("minecraft:overworld", "terrain", 1, 2).unwrap();
        let bytes = base64_path.entries.remove(&readable).unwrap();
        base64_path.entries.insert(
            "dimensions/bWluZWNyYWZ0Om92ZXJ3b3JsZA/terrain/1.2.nbt".into(),
            bytes,
        );
        let base64_archive_path = dir.path().join("base64.zip");
        staged_archive(&base64_path, &base64_archive_path);
        assert!(
            validate_archive(
                &base64_archive_path,
                base64_path.metadata.host_id,
                base64_path.metadata.session_id,
                base64_path.metadata.sequence,
                base64_path.metadata.firm_section_revision,
            )
            .is_err()
        );
    }

    #[test]
    fn v3_metadata_serializes_only_presence_flags_and_sha1_known_vector() {
        let archive = archive(vec![((1, 2), b"A".to_vec(), Some(b"E".to_vec()))]);
        let value = serde_json::to_value(&archive.metadata).unwrap();
        let chunk = &value["chunks"][0];
        assert_eq!(chunk["entitiesPresent"], true);
        assert_eq!(chunk["poiPresent"], false);
        for removed in [
            "terrainPath",
            "entitiesPath",
            "poiPath",
            "entitiesAbsent",
            "poiAbsent",
            "sourceMode",
            "gameTime",
        ] {
            assert!(chunk.get(removed).is_none(), "unexpected {removed}");
        }
        assert!(value.get("gameTime").is_none());

        for field in ["entitiesPresent", "poiPresent"] {
            let mut missing = value.clone();
            missing["chunks"][0].as_object_mut().unwrap().remove(field);
            assert!(decode_metadata(&serde_json::to_vec(&missing).unwrap()).is_err());
            for invalid in [
                serde_json::Value::Null,
                serde_json::Value::String("yes".into()),
            ] {
                let mut invalid_value = value.clone();
                invalid_value["chunks"][0][field] = invalid;
                assert!(decode_metadata(&serde_json::to_vec(&invalid_value).unwrap()).is_err());
            }
        }

        let mut hasher = Sha1::new();
        stream_sha1_update(&mut hasher, b"abc");
        assert_eq!(
            finalize_sha1(hasher),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
    }

    #[test]
    fn v3_presence_flags_require_exact_payload_set() {
        let (dir, _, _, _) = host_fixture();
        let valid = archive(vec![((1, 2), b"A".to_vec(), Some(b"E".to_vec()))]);
        let valid_path = dir.path().join("valid.zip");
        let (validated, _, _) = staged_archive(&valid, &valid_path);
        assert!(
            validate_archive(
                &valid_path,
                valid.metadata.host_id,
                valid.metadata.session_id,
                valid.metadata.sequence,
                valid.metadata.firm_section_revision,
            )
            .is_ok()
        );
        assert!(validated.metadata.chunks[0].entities_present);

        let mut poi = valid.clone();
        add_poi(&mut poi, 1, 2, b"P".to_vec());
        let poi_path = dir.path().join("poi.zip");
        staged_archive(&poi, &poi_path);
        assert!(
            validate_archive(
                &poi_path,
                poi.metadata.host_id,
                poi.metadata.session_id,
                poi.metadata.sequence,
                poi.metadata.firm_section_revision,
            )
            .is_ok()
        );

        let mut missing = valid.clone();
        missing
            .entries
            .remove(&expected_path("minecraft:overworld", "entities", 1, 2).unwrap());
        let missing_path = dir.path().join("missing.zip");
        staged_archive(&missing, &missing_path);
        assert!(
            validate_archive(
                &missing_path,
                missing.metadata.host_id,
                missing.metadata.session_id,
                missing.metadata.sequence,
                missing.metadata.firm_section_revision,
            )
            .is_err()
        );

        let mut extra = archive(vec![((1, 2), b"A".to_vec(), None)]);
        extra.entries.insert(
            expected_path("minecraft:overworld", "entities", 1, 2).unwrap(),
            b"E".to_vec(),
        );
        let extra_path = dir.path().join("extra.zip");
        staged_archive(&extra, &extra_path);
        assert!(
            validate_archive(
                &extra_path,
                extra.metadata.host_id,
                extra.metadata.session_id,
                extra.metadata.sequence,
                extra.metadata.firm_section_revision,
            )
            .is_err()
        );
    }

    #[test]
    fn v1_v2_and_unknown_formats_are_rejected_by_all_readers() {
        let (dir, _, _, _) = host_fixture();
        for version in [1, 2, 4] {
            let mut archive = archive(vec![((1, 2), b"A".to_vec(), None)]);
            archive.metadata.format_version = version;
            rewrite_metadata(&mut archive);
            let path = dir.path().join(format!("v{version}.zip"));
            staged_archive(&archive, &path);
            assert!(
                validate_archive(
                    &path,
                    archive.metadata.host_id,
                    archive.metadata.session_id,
                    archive.metadata.sequence,
                    archive.metadata.firm_section_revision,
                )
                .is_err()
            );
            assert!(read_archive(&path).is_err());
            assert!(read_archive_metadata(&path).is_err());
        }
    }

    #[test]
    fn partial_merge_preserves_disjoint_bytes_and_replacement_absence() {
        let sections = sections();
        let first = archive(vec![((1, 2), b"A1".to_vec(), Some(b"EA".to_vec()))]);
        let second = archive(vec![((3, 4), b"B1".to_vec(), Some(b"EB".to_vec()))]);
        let union = merge_archives(None, &first, &sections).unwrap();
        let union = merge_archives(Some(&union), &second, &sections).unwrap();
        assert_eq!(
            union.entries[&expected_path("minecraft:overworld", "terrain", 1, 2).unwrap()],
            b"A1"
        );
        assert_eq!(
            union.entries[&expected_path("minecraft:overworld", "terrain", 3, 4).unwrap()],
            b"B1"
        );

        let replacement = archive(vec![((1, 2), b"A2".to_vec(), None)]);
        let merged = merge_archives(Some(&union), &replacement, &sections).unwrap();
        assert_eq!(
            merged.entries[&expected_path("minecraft:overworld", "terrain", 1, 2).unwrap()],
            b"A2"
        );
        assert!(
            !merged
                .entries
                .contains_key(&expected_path("minecraft:overworld", "entities", 1, 2).unwrap())
        );
        assert!(
            merged
                .entries
                .contains_key(&expected_path("minecraft:overworld", "entities", 3, 4).unwrap())
        );
    }

    #[test]
    fn v3_subset_rejects_empty_and_out_of_roster_columns() {
        let sections = sections();
        let mut incoming = archive(vec![((1, 2), b"A".to_vec(), None)]);
        let validated = ValidatedArchive {
            metadata: incoming.metadata.clone(),
            sha1: String::new(),
            zip_bytes: 0,
        };
        assert!(validated.validate_subset(&sections).is_ok());
        incoming.metadata.total_columns = 1;
        let invalid_total = ValidatedArchive {
            metadata: incoming.metadata.clone(),
            sha1: String::new(),
            zip_bytes: 0,
        };
        assert!(invalid_total.validate_subset(&sections).is_err());
        incoming.metadata.chunks[0].chunk_x = 99;
        let invalid_column = ValidatedArchive {
            metadata: incoming.metadata,
            sha1: String::new(),
            zip_bytes: 0,
        };
        assert!(invalid_column.validate_subset(&sections).is_err());
    }

    #[test]
    fn merge_rejects_metadata_and_file_limits() {
        let sections = sections();
        let mut incoming = archive(vec![((1, 2), b"A".to_vec(), None)]);
        incoming.metadata.chunks[0].captured_at = Some("x".repeat(MAX_METADATA_BYTES as usize));
        assert!(merge_archives(None, &incoming, &sections).is_err());
    }

    #[test]
    fn v3_retry_is_idempotent_and_partial_merge_preserves_omitted_column() {
        let (dir, store, firm, host_id) = host_fixture();
        let mut sections = sections();
        sections.column_revisions.clear();
        let session = Uuid::now_v7();
        let mut first = archive(vec![
            ((1, 2), b"A".to_vec(), None),
            ((3, 4), b"B".to_vec(), None),
        ]);
        set_identity(&mut first, host_id, session, 1, sections.revision, 3);
        let first_path = dir.path().join("first.zip");
        let (validated, _, _) = staged_archive(&first, &first_path);
        firm.commit(
            host_id,
            validated,
            first_path.clone(),
            &sections,
            256 * 1024 * 1024,
        )
        .unwrap();
        let firm_dir = store.host_root(host_id).join("firm-chunks");
        let (validated, _, _) = staged_archive(&first, &dir.path().join("retry.zip"));
        let outcome = firm
            .commit(
                host_id,
                validated,
                dir.path().join("retry.zip"),
                &sections,
                256 * 1024 * 1024,
            )
            .unwrap();
        let CommitOutcome::Idempotent(receipt) = outcome else {
            panic!("v3 retry should be idempotent")
        };
        assert_eq!((receipt.stored_columns, receipt.total_columns), (2, 2));

        let mut partial = archive(vec![((1, 2), b"A2".to_vec(), None)]);
        set_identity(&mut partial, host_id, session, 2, sections.revision, 3);
        let partial_path = dir.path().join("partial.zip");
        let (validated, _, _) = staged_archive(&partial, &partial_path);
        let outcome = firm
            .commit(
                host_id,
                validated,
                partial_path,
                &sections,
                256 * 1024 * 1024,
            )
            .unwrap();
        assert!(matches!(outcome, CommitOutcome::Committed(_)));
        let latest = firm.latest(host_id).unwrap().unwrap();
        let merged = read_archive(&firm_dir.join(format!("{}.zip", latest.snapshot_id))).unwrap();
        assert_eq!(
            merged.entries[&expected_path("minecraft:overworld", "terrain", 3, 4).unwrap()],
            b"B"
        );
    }

    #[test]
    fn commit_failures_and_corrupt_retry_preserve_state() {
        let (dir, store, firm, host_id) = host_fixture();
        let sections = sections();
        let session = Uuid::now_v7();
        let mut first = archive(vec![((1, 2), b"A".to_vec(), None)]);
        set_identity(&mut first, host_id, session, 1, sections.revision, 3);
        let first_path = dir.path().join("first.zip");
        let (validated, _, _) = staged_archive(&first, &first_path);
        firm.commit(
            host_id,
            validated,
            first_path.clone(),
            &sections,
            256 * 1024 * 1024,
        )
        .unwrap();
        let firm_dir = store.host_root(host_id).join("firm-chunks");
        let state_path = firm_dir.join("state.json");
        let before = fs::read(&state_path).unwrap();

        let mut second = archive(vec![((3, 4), b"B".to_vec(), None)]);
        set_identity(&mut second, host_id, session, 2, sections.revision, 3);
        let second_path = dir.path().join("second.zip");
        let (validated, _, _) = staged_archive(&second, &second_path);
        assert!(matches!(
            firm.commit(host_id, validated, second_path, &sections, 1),
            Err(CommitError::Invalid(_))
        ));
        assert_eq!(fs::read(&state_path).unwrap(), before);
        assert!(
            !fs::read_dir(&firm_dir)
                .unwrap()
                .flatten()
                .any(|entry| entry.file_name().to_string_lossy().starts_with(".merge-"))
        );

        let latest = firm.latest(host_id).unwrap().unwrap();
        fs::write(
            firm_dir.join(format!("{}.zip", latest.snapshot_id)),
            b"corrupt",
        )
        .unwrap();
        let (validated, _, _) = staged_archive(&first, &first_path.with_file_name("retry.zip"));
        assert!(matches!(
            firm.commit(
                host_id,
                validated,
                first_path.with_file_name("retry.zip"),
                &sections,
                256 * 1024 * 1024
            ),
            Err(CommitError::Storage(_))
        ));
    }
}
