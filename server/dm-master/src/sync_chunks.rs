use crate::{
    model::{SyncChunk, SyncChunkMutationRequest},
    store::HostStore,
};
use anyhow::{Context, Result, bail};
use std::{
    collections::HashSet,
    fs::{self, File, OpenOptions},
    io::Write,
    path::Path,
    sync::atomic::{AtomicU64, Ordering},
};
use uuid::Uuid;

pub const FILE_NAME: &str = "sync-chunks.json";
static TEMP_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(deny_unknown_fields)]
pub struct MembershipRevision {
    pub dimension_id: String,
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub revision: i64,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
#[serde(deny_unknown_fields)]
pub struct SyncChunkSnapshot {
    pub revision: i64,
    pub chunks: Vec<SyncChunk>,
    pub membership_revisions: Vec<MembershipRevision>,
}

impl SyncChunkSnapshot {
    pub fn empty() -> Self {
        Self {
            revision: 0,
            chunks: Vec::new(),
            membership_revisions: Vec::new(),
        }
    }
    pub fn sorted(mut self) -> Self {
        self.chunks.sort_by(|a, b| {
            chunk_key(a)
                .cmp(&chunk_key(b))
                .then(a.owner_id.cmp(&b.owner_id))
        });
        self.membership_revisions.sort_by_key(revision_key);
        self
    }
    pub fn membership_revision(&self, chunk: &SyncChunk) -> Option<i64> {
        self.membership_revisions
            .iter()
            .find(|r| revision_key(r) == chunk_key(chunk))
            .map(|r| r.revision)
    }
}

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub enum MutationOutcome {
    Added,
    AlreadyPresent,
    Removed,
    AlreadyAbsent,
}

#[derive(Debug)]
pub enum MutationError {
    RevisionConflict,
    OwnedByOther,
    TotalLimitReached,
    InvalidRequest(anyhow::Error),
    Storage(anyhow::Error),
}

#[derive(Clone)]
pub struct SyncChunkStore {
    store: HostStore,
}
impl SyncChunkStore {
    pub fn new(store: HostStore) -> Self {
        Self { store }
    }
    pub fn read(&self, host_id: Uuid) -> Result<SyncChunkSnapshot> {
        let root = self.store.host_root(host_id);
        if !root.is_dir() || self.store.get(host_id)?.is_none() {
            bail!("host not found")
        }
        let path = root.join(FILE_NAME);
        let bytes = match fs::read(&path) {
            Ok(bytes) => bytes,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                return Ok(SyncChunkSnapshot::empty());
            }
            Err(e) => return Err(e).with_context(|| format!("read {}", path.display())),
        };
        let snapshot: SyncChunkSnapshot =
            serde_json::from_slice(&bytes).with_context(|| format!("parse {}", path.display()))?;
        validate_snapshot(&snapshot)?;
        Ok(snapshot.sorted())
    }
    pub fn mutate(
        &self,
        host_id: Uuid,
        request: &SyncChunkMutationRequest,
        delete: bool,
        max_total: usize,
    ) -> std::result::Result<(SyncChunkSnapshot, MutationOutcome), MutationError> {
        let mut snapshot = self.read(host_id).map_err(MutationError::Storage)?;
        if request.expected_revision != snapshot.revision {
            return Err(MutationError::RevisionConflict);
        }
        validate_coordinates(&request.chunk).map_err(MutationError::InvalidRequest)?;
        let key = (
            &request.chunk.dimension_id,
            request.chunk.chunk_x,
            request.chunk.chunk_z,
        );
        let existing = snapshot
            .chunks
            .iter()
            .position(|c| (&c.dimension_id, c.chunk_x, c.chunk_z) == key);
        let outcome = if delete {
            match existing {
                None => MutationOutcome::AlreadyAbsent,
                Some(i) if snapshot.chunks[i].owner_id != request.player_id => {
                    return Err(MutationError::OwnedByOther);
                }
                Some(i) => {
                    snapshot.chunks.remove(i);
                    snapshot
                        .membership_revisions
                        .retain(|r| (&r.dimension_id, r.chunk_x, r.chunk_z) != key);
                    MutationOutcome::Removed
                }
            }
        } else {
            match existing {
                Some(i) if snapshot.chunks[i].owner_id == request.player_id => {
                    MutationOutcome::AlreadyPresent
                }
                Some(_) => return Err(MutationError::OwnedByOther),
                None => {
                    if snapshot.chunks.len() >= max_total {
                        return Err(MutationError::TotalLimitReached);
                    }
                    snapshot.chunks.push(SyncChunk {
                        dimension_id: request.chunk.dimension_id.clone(),
                        chunk_x: request.chunk.chunk_x,
                        chunk_z: request.chunk.chunk_z,
                        owner_id: request.player_id,
                    });
                    MutationOutcome::Added
                }
            }
        };
        snapshot.revision = snapshot
            .revision
            .checked_add(1)
            .ok_or_else(|| MutationError::Storage(anyhow::anyhow!("revision overflow")))?;
        if outcome == MutationOutcome::Added {
            snapshot.membership_revisions.push(MembershipRevision {
                dimension_id: request.chunk.dimension_id.clone(),
                chunk_x: request.chunk.chunk_x,
                chunk_z: request.chunk.chunk_z,
                revision: snapshot.revision,
            });
        }
        snapshot = snapshot.sorted();
        self.write(host_id, &snapshot)
            .map_err(MutationError::Storage)?;
        Ok((snapshot, outcome))
    }
    pub fn write(&self, host_id: Uuid, snapshot: &SyncChunkSnapshot) -> Result<()> {
        validate_snapshot(snapshot)?;
        let root = self.store.host_root(host_id);
        if !root.is_dir() || self.store.get(host_id)?.is_none() {
            bail!("host not found")
        }
        atomic_write(&root.join(FILE_NAME), &serde_json::to_vec_pretty(snapshot)?)
    }
}

pub fn validate_coordinates(c: &crate::model::SyncChunkCoordinates) -> Result<()> {
    validate_dimension_id(&c.dimension_id)
}
pub fn validate_dimension_id(value: &str) -> Result<()> {
    let Some((namespace, path)) = value.split_once(':') else {
        bail!("dimensionId must be a resource location")
    };
    if namespace.is_empty()
        || namespace == "."
        || namespace == ".."
        || path.is_empty()
        || value.matches(':').count() != 1
        || !namespace
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "_-.".contains(c))
        || !path
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "_./-".contains(c))
        || path
            .split('/')
            .any(|s| s.is_empty() || s == "." || s == "..")
    {
        bail!("dimensionId must be a resource location")
    }
    Ok(())
}
fn chunk_key(c: &SyncChunk) -> (String, i32, i32) {
    (c.dimension_id.clone(), c.chunk_x, c.chunk_z)
}
fn revision_key(c: &MembershipRevision) -> (String, i32, i32) {
    (c.dimension_id.clone(), c.chunk_x, c.chunk_z)
}
fn validate_snapshot(snapshot: &SyncChunkSnapshot) -> Result<()> {
    if snapshot.revision < 0 {
        bail!("revision cannot be negative")
    }
    let mut keys = HashSet::new();
    for chunk in &snapshot.chunks {
        validate_dimension_id(&chunk.dimension_id)?;
        if !keys.insert(chunk_key(chunk)) {
            bail!("duplicate SyncChunk coordinate")
        }
    }
    let mut revisions = HashSet::new();
    for revision in &snapshot.membership_revisions {
        validate_dimension_id(&revision.dimension_id)?;
        if revision.revision < 0 || revision.revision > snapshot.revision {
            bail!("invalid SyncChunk membership revision")
        }
        if !keys.contains(&revision_key(revision)) || !revisions.insert(revision_key(revision)) {
            bail!("invalid SyncChunk membership revision")
        }
    }
    if revisions.len() != keys.len() {
        bail!("missing SyncChunk membership revision")
    }
    Ok(())
}
fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().context("sync chunk path has no parent")?;
    let temp = parent.join(format!(
        ".{}.tmp-{}-{}",
        FILE_NAME,
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
        fs::rename(&temp, path)?;
        sync_parent(parent)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result
}
fn sync_parent(path: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        File::open(path)?.sync_all()?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{SyncChunkCoordinates, SyncChunkMutationRequest};
    use std::fs;
    use tempfile::tempdir;
    fn fixture() -> (tempfile::TempDir, SyncChunkStore, Uuid) {
        let dir = tempdir().unwrap();
        let store = HostStore::open(dir.path()).unwrap();
        let id = Uuid::now_v7();
        fs::create_dir(store.host_root(id)).unwrap();
        fs::write(
            store.host_root(id).join("host.json"),
            serde_json::json!({"id":id,"name":"host"}).to_string(),
        )
        .unwrap();
        (dir, SyncChunkStore::new(store), id)
    }
    fn req(revision: i64, player: Uuid, x: i32) -> SyncChunkMutationRequest {
        req_in_dimension(revision, player, x, "minecraft:overworld")
    }
    fn req_in_dimension(
        revision: i64,
        player: Uuid,
        x: i32,
        dimension_id: &str,
    ) -> SyncChunkMutationRequest {
        SyncChunkMutationRequest {
            session_id: Uuid::now_v7(),
            expected_revision: revision,
            player_id: player,
            chunk: SyncChunkCoordinates {
                dimension_id: dimension_id.into(),
                chunk_x: x,
                chunk_z: 0,
            },
        }
    }
    #[test]
    fn owner_id_and_incarnation_are_persisted() {
        let (_dir, store, id) = fixture();
        let p = Uuid::now_v7();
        let (s, _) = store.mutate(id, &req(0, p, 1), false, 256).unwrap();
        assert_eq!(s.membership_revisions[0].revision, 1);
        let (s, _) = store.mutate(id, &req(1, p, 1), true, 256).unwrap();
        assert!(s.membership_revisions.is_empty());
        let (s, _) = store.mutate(id, &req(2, p, 1), false, 256).unwrap();
        assert_eq!(s.membership_revisions[0].revision, 3);
        let text = fs::read_to_string(store.store.host_root(id).join(FILE_NAME)).unwrap();
        assert!(text.contains("ownerId"));
        assert!(!text.contains("ownerUuid"));
    }

    #[test]
    fn one_player_can_fill_host_limit_and_duplicate_still_succeeds() {
        let (_dir, store, id) = fixture();
        let player = Uuid::now_v7();
        for x in 0..256 {
            let (snapshot, outcome) = store
                .mutate(id, &req(x, player, x as i32), false, 256)
                .unwrap();
            assert_eq!(snapshot.chunks.len(), x as usize + 1);
            assert_eq!(outcome, MutationOutcome::Added);
        }
        let (snapshot, outcome) = store
            .mutate(id, &req(256, player, 255), false, 256)
            .unwrap();
        assert_eq!(snapshot.revision, 257);
        assert_eq!(outcome, MutationOutcome::AlreadyPresent);
        assert!(matches!(
            store.mutate(id, &req(257, player, 256), false, 256),
            Err(MutationError::TotalLimitReached)
        ));
    }

    #[test]
    fn ownership_and_old_request_fields_are_rejected() {
        let (_dir, store, id) = fixture();
        let owner = Uuid::now_v7();
        let other = Uuid::now_v7();
        store.mutate(id, &req(0, owner, 1), false, 256).unwrap();
        assert!(matches!(
            store.mutate(id, &req(1, other, 1), true, 256),
            Err(MutationError::OwnedByOther)
        ));
        assert!(
            serde_json::from_value::<SyncChunkMutationRequest>(serde_json::json!({
                "sessionId": Uuid::now_v7(),
                "expectedRevision": 0,
                "playerId": owner,
                "chunk": {
                    "dimensionId": "minecraft:overworld",
                    "chunkX": 1,
                    "chunkZ": 1,
                    "sectionY": 0,
                    "ownerUuid": owner
                }
            }))
            .is_err()
        );
    }

    #[test]
    fn same_coordinates_in_different_dimensions_are_distinct_chunks() {
        let (_dir, store, id) = fixture();
        let player = Uuid::now_v7();
        let (overworld, _) = store.mutate(id, &req(0, player, 0), false, 256).unwrap();
        assert_eq!(overworld.chunks.len(), 1);
        let (nether, outcome) = store
            .mutate(
                id,
                &req_in_dimension(1, player, 0, "minecraft:the_nether"),
                false,
                256,
            )
            .unwrap();
        assert_eq!(outcome, MutationOutcome::Added);
        assert_eq!(nether.chunks.len(), 2);
    }
}
