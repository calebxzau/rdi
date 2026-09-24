//! Shared durable-publish helpers for generated snapshot data.
//!
//! These primitives were extracted from the retired v4 sync-chunk snapshot module so that
//! `world_snapshots` owns the only production snapshot store while still using one
//! cross-platform atomic-publish implementation.

use anyhow::{Context, Result};
use sha1::{Digest, Sha1};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
use uuid::Uuid;

static TEMP_COUNTER: AtomicU64 = AtomicU64::new(0);

/// Streams `path` through SHA-1 without buffering the whole file.
pub fn sha1_file(path: &Path) -> Result<String> {
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

pub fn hex_lower(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

/// Writes `bytes` to a sibling temporary file and renames it over `path`.
pub fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
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

/// Forces the already written file to durable storage and renames it into place.
pub fn publish_file(staged: &Path, destination: &Path) -> Result<()> {
    let file = OpenOptions::new().read(true).write(true).open(staged)?;
    file.sync_all()?;
    drop(file);
    rename_replace(staged, destination, false)?;
    if let Some(parent) = destination.parent() {
        sync_parent(parent)?;
    }
    Ok(())
}

/// Re-forces an already published file before its pointer state is rewritten.
pub fn resync_file(path: &Path) -> Result<()> {
    let file = OpenOptions::new().read(true).write(true).open(path)?;
    file.sync_all()?;
    Ok(())
}

pub fn rename_replace(source: &Path, destination: &Path, replace: bool) -> Result<()> {
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

pub fn sync_parent(_path: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        File::open(_path)?.sync_all()?;
    }
    Ok(())
}

/// Best-effort removal of a temporary file that is dropped with its owning work item.
pub struct TempFile {
    path: PathBuf,
}

/// Best-effort owned directory used while preparing a snapshot. It is removed only when
/// the preparation owner is dropped; once released the published directory owns it.
pub struct TempDirectory {
    path: PathBuf,
}

impl TempDirectory {
    pub fn new(path: PathBuf) -> Result<Self> {
        fs::create_dir(&path)?;
        Ok(Self { path })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn release(mut self) -> PathBuf {
        let path = std::mem::take(&mut self.path);
        std::mem::forget(self);
        path
    }
}

impl Drop for TempDirectory {
    fn drop(&mut self) {
        if !self.path.as_os_str().is_empty() {
            remove_directory_later(std::mem::take(&mut self.path));
        }
    }
}

/// Recursively flushes directory entries. New files were synced by their writers;
/// inherited files are already durable and must never be reopened for writing.
pub fn sync_directory_tree(path: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.is_dir() {
        for entry in fs::read_dir(path)? {
            sync_directory_tree(&entry?.path())?;
        }
        sync_parent(path)?;
    } else if !metadata.is_file() {
        anyhow::bail!("snapshot contains a symlink or special file");
    }
    Ok(())
}

/// Owns cleanup independently of HTTP cancellation and never traverses a tree under the
/// commit/retention locks. A failed spawn leaves an orphan for startup recovery.
pub fn remove_directory_later(path: PathBuf) {
    let failure_path = path.clone();
    if let Err(error) = std::thread::Builder::new()
        .name("snapshot-cleanup".into())
        .spawn(move || {
            if let Err(error) = fs::remove_dir_all(&path) {
                if error.kind() != std::io::ErrorKind::NotFound {
                    tracing::warn!(%error, path = %path.display(), "snapshot cleanup failed");
                }
            }
        })
    {
        tracing::warn!(%error, path = %failure_path.display(), "snapshot cleanup could not start");
    }
}

pub fn publish_directory(staged: &Path, destination: &Path) -> Result<()> {
    if destination.try_exists()? {
        anyhow::bail!("snapshot destination already exists");
    }
    rename_replace(staged, destination, false)?;
    if let Some(parent) = destination.parent() {
        sync_parent(parent)?;
    }
    if let Some(parent) = staged.parent() {
        sync_parent(parent)?;
    }
    Ok(())
}

impl TempFile {
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Gives up ownership once the file has been renamed into its published location.
    pub fn release(mut self) -> PathBuf {
        let path = std::mem::take(&mut self.path);
        std::mem::forget(self);
        path
    }
}

impl Drop for TempFile {
    fn drop(&mut self) {
        if self.path.as_os_str().is_empty() {
            return;
        }
        let _ = fs::remove_file(&self.path);
    }
}

/// Reads a bounded number of bytes from `reader`, optionally capturing them.
pub fn read_bounded<R: Read>(
    reader: &mut R,
    limit: u64,
    mut captured: Option<&mut Vec<u8>>,
    hasher: Option<&mut Sha1>,
) -> Result<u64> {
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    let mut hasher = hasher;
    loop {
        let count = reader.read(&mut buffer)?;
        if count == 0 {
            return Ok(total);
        }
        total = total.saturating_add(count as u64);
        if total > limit {
            anyhow::bail!("snapshot entry exceeds size limit")
        }
        if let Some(bytes) = captured.as_deref_mut() {
            bytes.extend_from_slice(&buffer[..count]);
        }
        if let Some(hasher) = hasher.as_deref_mut() {
            hasher.update(&buffer[..count]);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn atomic_write_replaces_existing_state() {
        let directory = tempdir().unwrap();
        let path = directory.path().join("state.json");
        atomic_write(&path, b"first").unwrap();
        atomic_write(&path, b"second").unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"second");
        let leftovers = fs::read_dir(directory.path())
            .unwrap()
            .flatten()
            .filter(|entry| {
                entry
                    .file_name()
                    .to_string_lossy()
                    .starts_with(".state.tmp-")
            })
            .count();
        assert_eq!(leftovers, 0);
    }

    #[test]
    fn released_temp_file_is_not_removed() {
        let directory = tempdir().unwrap();
        let path = directory.path().join("candidate.zip");
        fs::write(&path, b"data").unwrap();
        {
            let temp = TempFile::new(path.clone());
            let released = temp.release();
            assert_eq!(released, path);
        }
        assert!(path.is_file());

        let other = directory.path().join("dropped.zip");
        fs::write(&other, b"data").unwrap();
        drop(TempFile::new(other.clone()));
        assert!(!other.exists());
    }

    #[test]
    fn read_bounded_hashes_and_rejects_oversized_entries() {
        let mut hasher = Sha1::new();
        let mut captured = Vec::new();
        let mut source = &b"hello"[..];
        let total = read_bounded(&mut source, 16, Some(&mut captured), Some(&mut hasher)).unwrap();
        assert_eq!(total, 5);
        assert_eq!(captured, b"hello");
        assert_eq!(
            hex_lower(&hasher.finalize()),
            "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
        );

        let mut oversized = &b"hello"[..];
        assert!(read_bounded(&mut oversized, 2, None, None).is_err());
    }
}
