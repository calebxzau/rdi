use crate::{model::HostRecord, mods::{self, ModFingerprint}};
use anyhow::{Context, Result, bail};
use std::{
    fs,
    io::{self, Read},
    path::{Path, PathBuf},
};
use std::sync::{Arc, Mutex};
use uuid::Uuid;

#[derive(Clone)]
pub struct HostStore {
    root: PathBuf,
    hosts: PathBuf,
    staging: PathBuf,
    /// Deleted rooms, kept here so that a mistake can be undone by hand.
    deleted: PathBuf,
    create_lock: Arc<Mutex<()>>,
}

impl HostStore {
    pub fn open(root: impl Into<PathBuf>) -> Result<Self> {
        let root = root.into();
        let hosts = root.join("hosts");
        let staging = root.join("staging");
        let deleted = root.join("deleted-hosts");
        fs::create_dir_all(&hosts).with_context(|| format!("create {}", hosts.display()))?;
        fs::create_dir_all(&staging).with_context(|| format!("create {}", staging.display()))?;
        fs::create_dir_all(&deleted).with_context(|| format!("create {}", deleted.display()))?;
        Ok(Self {
            root,
            hosts,
            staging,
            deleted,
            create_lock: Arc::default(),
        })
    }

    pub fn staging_path(&self) -> &Path {
        &self.staging
    }

    pub fn create_from_upload(
        &self,
        name: String,
        creator_id: Option<Uuid>,
        fingerprint: Option<&ModFingerprint>,
        upload: &Path,
        max_per_creator: usize,
    ) -> Result<HostRecord> {
        validate_name(&name)?;
        validate_zip(upload)?;
        // Held until the room is published, so parallel uploads cannot pass the limit together.
        let _create = self.create_lock.lock().expect("room creation lock poisoned");
        if let Some(creator) = creator_id {
            self.check_room_limit(creator, max_per_creator)?;
        }
        let id = Uuid::now_v7();
        let stage = self.staging.join(format!("host-{id}"));
        fs::create_dir(&stage)?;
        let result = (|| {
            fs::rename(upload, stage.join("world-init.zip"))?;
            let record = HostRecord {
                id,
                name,
                creator_id,
                last_host_id: None,
            };
            fs::write(stage.join("host.json"), serde_json::to_vec_pretty(&record)?)?;
            if let Some(fingerprint) = fingerprint {
                mods::record(&stage, fingerprint, creator_id)?;
            }
            fs::write(
                stage.join("sync-chunks.json"),
                b"{\n  \"revision\": 0,\n  \"chunks\": [],\n  \"membershipRevisions\": []\n}\n",
            )?;
            let target = self.hosts.join(id.to_string());
            fs::rename(&stage, target)?;
            Ok(record)
        })();
        if result.is_err() {
            let _ = fs::remove_dir_all(&stage);
        }
        result
    }

    /// Fails with a message for the player once they have created as many rooms as allowed.
    pub fn check_room_limit(&self, creator: Uuid, max_per_creator: usize) -> Result<()> {
        let mut created = 0;
        for entry in fs::read_dir(&self.hosts)? {
            let record = entry?.path().join("host.json");
            if record.is_file() && read_record(&record)?.creator_id == Some(creator) {
                created += 1;
            }
        }
        if created >= max_per_creator {
            anyhow::bail!("你最多只能创建{max_per_creator}个房间");
        }
        Ok(())
    }

    /// The rooms this player created, newest first. A damaged room is skipped so the rest still show.
    pub fn list_created_by(&self, creator: Uuid) -> Result<Vec<HostRecord>> {
        let mut rooms = Vec::new();
        for entry in fs::read_dir(&self.hosts)? {
            let path = entry?.path().join("host.json");
            if !path.is_file() {
                continue;
            }
            match read_record(&path) {
                Ok(record) if record.creator_id == Some(creator) => rooms.push(record),
                Ok(_) => {}
                Err(error) => tracing::error!(
                    path = %path.display(),
                    error = %format!("{error:#}"),
                    "cannot read room record"
                ),
            }
        }
        rooms.sort_by(|a, b| b.id.cmp(&a.id));
        Ok(rooms)
    }

    /// Moves the room out of `hosts` in one step: it stops existing for every route and stops
    /// counting toward the room limit, while its data stays in `deleted-hosts`.
    pub fn retire(&self, id: Uuid) -> Result<()> {
        let _write = self.create_lock.lock().expect("room creation lock poisoned");
        let target = self.deleted.join(id.to_string());
        fs::rename(self.host_root(id), &target)
            .with_context(|| format!("move room to {}", target.display()))
    }

    /// Remembers who hosts the room now; only they may come back without a takeover.
    pub fn set_last_host(&self, id: Uuid, player: Uuid) -> Result<()> {
        let _write = self.create_lock.lock().expect("room creation lock poisoned");
        let path = self.hosts.join(id.to_string()).join("host.json");
        let mut record = read_record(&path)?;
        if record.last_host_id == Some(player) {
            return Ok(());
        }
        record.last_host_id = Some(player);
        let temp = path.with_extension(format!("json.{}", Uuid::now_v7()));
        fs::write(&temp, serde_json::to_vec_pretty(&record)?)?;
        fs::rename(&temp, &path).inspect_err(|_| {
            let _ = fs::remove_file(&temp);
        })?;
        Ok(())
    }

    pub fn get(&self, id: Uuid) -> Result<Option<HostRecord>> {
        let path = self.hosts.join(id.to_string()).join("host.json");
        if !path.exists() {
            return Ok(None);
        }
        Ok(Some(serde_json::from_slice(&fs::read(path)?)?))
    }

    pub fn world_init(&self, id: Uuid) -> PathBuf {
        self.hosts.join(id.to_string()).join("world-init.zip")
    }

    pub fn host_root(&self, id: Uuid) -> PathBuf {
        self.hosts.join(id.to_string())
    }

    pub fn root(&self) -> &Path {
        &self.root
    }
}

pub fn validate_zip(path: &Path) -> Result<()> {
    let file = fs::File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut archive = zip::ZipArchive::new(file).context("invalid ZIP archive")?;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).context("invalid ZIP entry")?;
        validate_entry_path(entry.name())?;
        io::copy(&mut entry, &mut io::sink()).context("invalid ZIP entry data")?;
    }
    Ok(())
}

pub fn validate_entry_path(name: &str) -> Result<()> {
    if name.is_empty() || name.contains('\0') || name.contains('\\') {
        bail!("invalid ZIP entry path")
    }
    let path = Path::new(name);
    if path.is_absolute()
        || path.components().any(|component| {
            matches!(
                component,
                std::path::Component::ParentDir | std::path::Component::Prefix(_)
            )
        })
    {
        bail!("invalid ZIP entry path: {name}")
    }
    Ok(())
}

fn validate_name(name: &str) -> Result<()> {
    if name.trim().is_empty() {
        bail!("host name cannot be empty")
    }
    if name.len() > 256 {
        bail!("host name is too long")
    }
    Ok(())
}

#[allow(dead_code)]
fn read_record(path: &Path) -> Result<HostRecord> {
    let mut bytes = Vec::new();
    fs::File::open(path)?.read_to_end(&mut bytes)?;
    Ok(serde_json::from_slice(&bytes)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::tempdir;
    use zip::{ZipWriter, write::SimpleFileOptions};

    fn zip_file(path: &Path, entry: &str) {
        let file = fs::File::create(path).unwrap();
        let mut writer = ZipWriter::new(file);
        writer
            .start_file(entry, SimpleFileOptions::default())
            .unwrap();
        writer.write_all(b"world data").unwrap();
        writer.finish().unwrap();
    }

    #[test]
    fn publishes_and_reopens_host() {
        let dir = tempdir().unwrap();
        let store = HostStore::open(dir.path()).unwrap();
        let upload = dir.path().join("upload.zip");
        zip_file(&upload, "level.dat");
        let creator = Uuid::now_v7();
        let fingerprint = crate::mods::tests::fingerprint(&[("minecraft", "a")]);
        let host = store
            .create_from_upload("test".into(), Some(creator), Some(&fingerprint), &upload, 5)
            .unwrap();
        assert!(host.id.get_version_num() == 7);
        assert_eq!(store.get(host.id).unwrap().unwrap().name, "test");
        assert_eq!(store.get(host.id).unwrap().unwrap().creator_id, Some(creator));
        let room = mods::read(&store.host_root(host.id)).unwrap().unwrap();
        assert_eq!(room.fingerprint, fingerprint);
        assert_eq!(room.updated_by, Some(creator));
        let reopened = HostStore::open(dir.path()).unwrap();
        assert_eq!(reopened.get(host.id).unwrap().unwrap().id, host.id);
        assert!(reopened.world_init(host.id).is_file());
    }

    #[test]
    fn rejects_traversal_and_backslash_paths() {
        assert!(validate_entry_path("../outside").is_err());
        assert!(validate_entry_path("folder\\outside").is_err());
        assert!(validate_entry_path("world/level.dat").is_ok());
    }

    #[test]
    fn invalid_zip_is_rejected() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("bad.zip");
        fs::write(&path, b"not a zip").unwrap();
        assert!(validate_zip(&path).is_err());
    }
}
