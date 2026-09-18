use crate::model::HostRecord;
use anyhow::{Context, Result, bail};
use std::{
    fs,
    io::{self, Read},
    path::{Path, PathBuf},
};
use uuid::Uuid;

#[derive(Clone)]
pub struct HostStore {
    root: PathBuf,
    hosts: PathBuf,
    staging: PathBuf,
}

impl HostStore {
    pub fn open(root: impl Into<PathBuf>) -> Result<Self> {
        let root = root.into();
        let hosts = root.join("hosts");
        let staging = root.join("staging");
        fs::create_dir_all(&hosts).with_context(|| format!("create {}", hosts.display()))?;
        fs::create_dir_all(&staging).with_context(|| format!("create {}", staging.display()))?;
        Ok(Self {
            root,
            hosts,
            staging,
        })
    }

    pub fn staging_path(&self) -> &Path {
        &self.staging
    }

    pub fn create_from_upload(&self, name: String, upload: &Path) -> Result<HostRecord> {
        validate_name(&name)?;
        validate_zip(upload)?;
        let id = Uuid::now_v7();
        let stage = self.staging.join(format!("host-{id}"));
        fs::create_dir(&stage)?;
        let result = (|| {
            fs::rename(upload, stage.join("world-init.zip"))?;
            let record = HostRecord { id, name };
            fs::write(stage.join("host.json"), serde_json::to_vec_pretty(&record)?)?;
            let target = self.hosts.join(id.to_string());
            fs::rename(&stage, target)?;
            Ok(record)
        })();
        if result.is_err() {
            let _ = fs::remove_dir_all(&stage);
        }
        result
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
        let host = store.create_from_upload("test".into(), &upload).unwrap();
        assert!(host.id.get_version_num() == 7);
        assert_eq!(store.get(host.id).unwrap().unwrap().name, "test");
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
