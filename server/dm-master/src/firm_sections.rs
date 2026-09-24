use crate::{
    model::{FirmSection, FirmSectionCoordinates, FirmSectionMutationRequest},
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

pub const FILE_NAME: &str = "firm-sections.json";

static TEMP_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct FirmSectionSnapshot {
    pub revision: i64,
    pub sections: Vec<FirmSection>,
    #[serde(default)]
    pub column_revisions: Vec<ColumnRevision>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Eq, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ColumnRevision {
    pub dimension_id: String,
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub revision: i64,
}

impl FirmSectionSnapshot {
    pub fn empty() -> Self {
        Self {
            revision: 0,
            sections: Vec::new(),
            column_revisions: Vec::new(),
        }
    }

    pub fn sorted(mut self) -> Self {
        sort_sections(&mut self.sections);
        sort_column_revisions(&mut self.column_revisions);
        self
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
    PlayerLimitReached,
    TotalLimitReached,
    InvalidRequest(anyhow::Error),
    Storage(anyhow::Error),
}

#[derive(Clone)]
pub struct FirmSectionStore {
    store: HostStore,
}

impl FirmSectionStore {
    pub fn new(store: HostStore) -> Self {
        Self { store }
    }

    pub fn read(&self, host_id: Uuid) -> Result<FirmSectionSnapshot> {
        let host_root = self.store.host_root(host_id);
        if !host_root.is_dir() || self.store.get(host_id)?.is_none() {
            bail!("host not found")
        }
        let path = host_root.join(FILE_NAME);
        let bytes = match fs::read(&path) {
            Ok(bytes) => bytes,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(FirmSectionSnapshot::empty());
            }
            Err(error) => return Err(error).with_context(|| format!("read {}", path.display())),
        };
        let snapshot: FirmSectionSnapshot =
            serde_json::from_slice(&bytes).with_context(|| format!("parse {}", path.display()))?;
        validate_snapshot(&snapshot)?;
        Ok(snapshot.sorted())
    }

    pub fn mutate(
        &self,
        host_id: Uuid,
        request: &FirmSectionMutationRequest,
        delete: bool,
        max_total: usize,
        max_person: usize,
    ) -> std::result::Result<(FirmSectionSnapshot, MutationOutcome), MutationError> {
        let mut snapshot = self.read(host_id).map_err(MutationError::Storage)?;
        let prior_columns = distinct_columns(&snapshot);
        let mut old_columns = column_revision_map(&snapshot);
        for column in prior_columns {
            old_columns.entry(column).or_insert(0);
        }
        if request.expected_revision != snapshot.revision {
            return Err(MutationError::RevisionConflict);
        }
        let coordinates =
            validate_coordinates(&request.section).map_err(MutationError::InvalidRequest)?;
        let existing = snapshot
            .sections
            .iter()
            .position(|section| section_key(section) == coordinates);
        let outcome = if delete {
            match existing {
                None => MutationOutcome::AlreadyAbsent,
                Some(index) if snapshot.sections[index].owner_uuid != request.player_id => {
                    return Err(MutationError::OwnedByOther);
                }
                Some(index) => {
                    snapshot.sections.remove(index);
                    MutationOutcome::Removed
                }
            }
        } else {
            match existing {
                Some(index) if snapshot.sections[index].owner_uuid == request.player_id => {
                    MutationOutcome::AlreadyPresent
                }
                Some(_) => return Err(MutationError::OwnedByOther),
                None => {
                    if snapshot.sections.len() >= max_total {
                        return Err(MutationError::TotalLimitReached);
                    }
                    if max_person > 0
                        && snapshot
                            .sections
                            .iter()
                            .filter(|section| section.owner_uuid == request.player_id)
                            .count()
                            >= max_person
                    {
                        return Err(MutationError::PlayerLimitReached);
                    }
                    snapshot.sections.push(FirmSection {
                        dimension_id: request.section.dimension_id.clone(),
                        chunk_x: request.section.chunk_x,
                        section_y: request.section.section_y,
                        chunk_z: request.section.chunk_z,
                        owner_uuid: request.player_id,
                    });
                    MutationOutcome::Added
                }
            }
        };
        snapshot.revision = snapshot
            .revision
            .checked_add(1)
            .ok_or_else(|| MutationError::Storage(anyhow::anyhow!("revision overflow")))?;
        let current_columns = distinct_columns(&snapshot);
        snapshot.column_revisions = current_columns
            .into_iter()
            .map(|(dimension_id, chunk_x, chunk_z)| ColumnRevision {
                revision: old_columns
                    .get(&(dimension_id.clone(), chunk_x, chunk_z))
                    .copied()
                    .unwrap_or(snapshot.revision),
                dimension_id,
                chunk_x,
                chunk_z,
            })
            .collect();
        snapshot = snapshot.sorted();
        self.write(host_id, &snapshot)
            .map_err(MutationError::Storage)?;
        Ok((snapshot, outcome))
    }

    pub fn write(&self, host_id: Uuid, snapshot: &FirmSectionSnapshot) -> Result<()> {
        validate_snapshot(snapshot)?;
        let host_root = self.store.host_root(host_id);
        if !host_root.is_dir() || self.store.get(host_id)?.is_none() {
            bail!("host not found")
        }
        let path = host_root.join(FILE_NAME);
        let bytes = serde_json::to_vec_pretty(snapshot)?;
        atomic_write(&path, &bytes)
    }
}

pub fn validate_coordinates(
    coordinates: &FirmSectionCoordinates,
) -> Result<(String, i32, i32, i32)> {
    validate_dimension_id(&coordinates.dimension_id)?;
    Ok((
        coordinates.dimension_id.clone(),
        coordinates.chunk_x,
        coordinates.section_y,
        coordinates.chunk_z,
    ))
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
        || !namespace.chars().all(|character| {
            character.is_ascii_lowercase()
                || character.is_ascii_digit()
                || "_-.".contains(character)
        })
        || !path.chars().all(|character| {
            character.is_ascii_lowercase()
                || character.is_ascii_digit()
                || "_./-".contains(character)
        })
        || path
            .split('/')
            .any(|segment| segment.is_empty() || segment == "." || segment == "..")
    {
        bail!("dimensionId must be a resource location")
    }
    Ok(())
}

fn section_key(section: &FirmSection) -> (String, i32, i32, i32) {
    (
        section.dimension_id.clone(),
        section.chunk_x,
        section.section_y,
        section.chunk_z,
    )
}

fn sort_sections(sections: &mut [FirmSection]) {
    sections.sort_by(|left, right| {
        section_key(left)
            .cmp(&section_key(right))
            .then_with(|| left.owner_uuid.cmp(&right.owner_uuid))
    });
}

fn sort_column_revisions(revisions: &mut [ColumnRevision]) {
    revisions.sort_by(|left, right| {
        (left.dimension_id.as_str(), left.chunk_x, left.chunk_z).cmp(&(
            right.dimension_id.as_str(),
            right.chunk_x,
            right.chunk_z,
        ))
    });
}

fn distinct_columns(snapshot: &FirmSectionSnapshot) -> HashSet<(String, i32, i32)> {
    snapshot
        .sections
        .iter()
        .map(|section| {
            (
                section.dimension_id.clone(),
                section.chunk_x,
                section.chunk_z,
            )
        })
        .collect()
}

fn column_revision_map(
    snapshot: &FirmSectionSnapshot,
) -> std::collections::HashMap<(String, i32, i32), i64> {
    snapshot
        .column_revisions
        .iter()
        .map(|column| {
            (
                (column.dimension_id.clone(), column.chunk_x, column.chunk_z),
                column.revision,
            )
        })
        .collect()
}

fn validate_snapshot(snapshot: &FirmSectionSnapshot) -> Result<()> {
    if snapshot.revision < 0 {
        bail!("revision cannot be negative")
    }
    let mut keys = HashSet::with_capacity(snapshot.sections.len());
    for section in &snapshot.sections {
        validate_dimension_id(&section.dimension_id)?;
        if !keys.insert(section_key(section)) {
            bail!("duplicate FirmSection coordinate")
        }
    }
    let columns = distinct_columns(snapshot);
    let mut revisions = HashSet::with_capacity(snapshot.column_revisions.len());
    for column in &snapshot.column_revisions {
        validate_dimension_id(&column.dimension_id)?;
        if column.revision < 0 || column.revision > snapshot.revision {
            bail!("invalid FirmChunk column revision")
        }
        if !columns.contains(&(column.dimension_id.clone(), column.chunk_x, column.chunk_z)) {
            bail!("FirmChunk column revision is outside the section roster")
        }
        if !revisions.insert((column.dimension_id.clone(), column.chunk_x, column.chunk_z)) {
            bail!("duplicate FirmChunk column revision")
        }
    }
    Ok(())
}

fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().context("FirmSection path has no parent")?;
    let temp = parent.join(format!(
        ".{}.tmp-{}-{}-{}",
        FILE_NAME,
        std::process::id(),
        Uuid::now_v7(),
        TEMP_COUNTER.fetch_add(1, Ordering::Relaxed)
    ));
    let mut created = false;
    let result = (|| {
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temp)
            .with_context(|| format!("create {}", temp.display()))?;
        created = true;
        file.write_all(bytes)
            .with_context(|| format!("write {}", temp.display()))?;
        file.sync_all()
            .with_context(|| format!("sync {}", temp.display()))?;
        drop(file);
        fs::rename(&temp, path).with_context(|| format!("publish {}", path.display()))?;
        sync_parent(parent)
    })();
    if result.is_err() && created {
        let _ = fs::remove_file(&temp);
    }
    result
}

fn sync_parent(path: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        File::open(path)
            .with_context(|| format!("open {} for sync", path.display()))?
            .sync_all()
            .with_context(|| format!("sync {}", path.display()))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{model::FirmSectionMutationRequest, store::HostStore};
    use std::fs;
    use tempfile::tempdir;

    fn host_store() -> (tempfile::TempDir, HostStore, Uuid) {
        let dir = tempdir().unwrap();
        let store = HostStore::open(dir.path()).unwrap();
        let host_id = Uuid::now_v7();
        fs::create_dir(store.host_root(host_id)).unwrap();
        fs::write(
            store.host_root(host_id).join("host.json"),
            serde_json::json!({"id": host_id, "name": "test"}).to_string(),
        )
        .unwrap();
        (dir, store, host_id)
    }

    fn request(
        dimension: &str,
        expected_revision: i64,
        player_id: Uuid,
    ) -> FirmSectionMutationRequest {
        FirmSectionMutationRequest {
            session_id: Uuid::now_v7(),
            expected_revision,
            player_id,
            section: FirmSectionCoordinates {
                dimension_id: dimension.into(),
                chunk_x: 1,
                section_y: 2,
                chunk_z: 3,
            },
        }
    }

    #[test]
    fn missing_file_is_empty_and_mutations_are_atomic() {
        let (_dir, store, host_id) = host_store();
        let firm = FirmSectionStore::new(store);
        let player = Uuid::now_v7();
        assert_eq!(firm.read(host_id).unwrap().revision, 0);
        let request = request("minecraft:overworld", 0, player);
        let (snapshot, outcome) = firm.mutate(host_id, &request, false, 256, 0).unwrap();
        assert_eq!(snapshot.revision, 1);
        assert_eq!(outcome, MutationOutcome::Added);
        assert_eq!(firm.read(host_id).unwrap().sections.len(), 1);
    }

    #[test]
    fn malformed_file_is_not_reset() {
        let (_dir, store, host_id) = host_store();
        fs::write(store.host_root(host_id).join(FILE_NAME), b"broken").unwrap();
        assert!(FirmSectionStore::new(store).read(host_id).is_err());
    }

    #[test]
    fn no_ops_increment_revision_and_ownership_is_enforced() {
        let (_dir, store, host_id) = host_store();
        let firm = FirmSectionStore::new(store);
        let owner = Uuid::now_v7();
        let other = Uuid::now_v7();
        let add = request("minecraft:overworld", 0, owner);
        let (snapshot, outcome) = firm.mutate(host_id, &add, false, 256, 0).unwrap();
        assert_eq!((snapshot.revision, outcome), (1, MutationOutcome::Added));
        let duplicate = request("minecraft:overworld", 1, owner);
        let (snapshot, outcome) = firm.mutate(host_id, &duplicate, false, 256, 0).unwrap();
        assert_eq!(
            (snapshot.revision, outcome),
            (2, MutationOutcome::AlreadyPresent)
        );
        let denied = request("minecraft:overworld", 2, other);
        assert!(matches!(
            firm.mutate(host_id, &denied, true, 256, 0),
            Err(MutationError::OwnedByOther)
        ));
        let remove = request("minecraft:overworld", 2, owner);
        let (snapshot, outcome) = firm.mutate(host_id, &remove, true, 256, 0).unwrap();
        assert_eq!((snapshot.revision, outcome), (3, MutationOutcome::Removed));
        let absent = request("minecraft:overworld", 3, owner);
        let (snapshot, outcome) = firm.mutate(host_id, &absent, true, 256, 0).unwrap();
        assert_eq!(
            (snapshot.revision, outcome),
            (4, MutationOutcome::AlreadyAbsent)
        );
    }

    #[test]
    fn limits_only_reject_additions() {
        let (_dir, store, host_id) = host_store();
        let firm = FirmSectionStore::new(store);
        let owner = Uuid::now_v7();
        let add = request("minecraft:overworld", 0, owner);
        firm.mutate(host_id, &add, false, 1, 1).unwrap();
        let second = request("minecraft:the_nether", 1, owner);
        assert!(matches!(
            firm.mutate(host_id, &second, false, 1, 1),
            Err(MutationError::TotalLimitReached)
        ));
        let remove = request("minecraft:overworld", 1, owner);
        assert!(firm.mutate(host_id, &remove, true, 1, 1).is_ok());
    }

    #[test]
    fn dimensions_are_resource_locations() {
        assert!(validate_dimension_id("minecraft:overworld").is_ok());
        assert!(validate_dimension_id("mod:worlds/moon").is_ok());
        assert!(validate_dimension_id("mod:moon.v2/a..b").is_ok());
        assert!(validate_dimension_id("Minecraft:Overworld").is_err());
        assert!(validate_dimension_id("minecraft").is_err());

        for invalid in [
            ".:overworld",
            "..:overworld",
            "mod:.",
            "mod:..",
            "mod:worlds/.",
            "mod:worlds/..",
            "mod:/worlds",
            "mod:worlds/",
            "mod:worlds//moon",
            "mod:worlds\\moon",
            "mod:worlds/Moon",
            "mod:worlds/世界",
            "mod:worlds:moon",
            "modworlds/moon",
            ":worlds/moon",
        ] {
            assert!(
                validate_dimension_id(invalid).is_err(),
                "unexpectedly accepted {invalid}"
            );
        }
    }

    #[test]
    fn column_membership_revision_survives_section_mutations() {
        let (_dir, store, host_id) = host_store();
        let firm = FirmSectionStore::new(store);
        let owner = Uuid::now_v7();
        let first = request("minecraft:overworld", 0, owner);
        let (snapshot, _) = firm.mutate(host_id, &first, false, 256, 0).unwrap();
        assert_eq!(snapshot.column_revisions[0].revision, 1);

        let duplicate = request("minecraft:overworld", 1, owner);
        let (snapshot, _) = firm.mutate(host_id, &duplicate, false, 256, 0).unwrap();
        assert_eq!(snapshot.column_revisions[0].revision, 1);

        let mut second = request("minecraft:overworld", 2, owner);
        second.section.section_y = 4;
        let (snapshot, _) = firm.mutate(host_id, &second, false, 256, 0).unwrap();
        assert_eq!(snapshot.column_revisions[0].revision, 1);

        let mut remove_first = first.clone();
        remove_first.expected_revision = 3;
        let (snapshot, _) = firm.mutate(host_id, &remove_first, true, 256, 0).unwrap();
        let mut remove_second = second.clone();
        remove_second.expected_revision = 4;
        assert_eq!(snapshot.column_revisions[0].revision, 1);
        let (snapshot, _) = firm.mutate(host_id, &remove_second, true, 256, 0).unwrap();
        assert!(snapshot.column_revisions.is_empty());
    }
}
