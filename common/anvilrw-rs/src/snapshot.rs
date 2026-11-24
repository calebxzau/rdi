//! A save's files at one moment, and its `session.lock`.

use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use crate::compress;
use crate::error::{IoContext, RemapError, Result};
use crate::fsutil;

pub const SESSION_LOCK: &str = "session.lock";
const COPY_BUFFER: usize = 64 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FileState {
    pub size: u64,
    pub modified: SystemTime,
}

/// The regular files and directories of a save at one moment, used to detect changes while the save
/// is copied or rewritten. Paths are relative and `/`-separated; `session.lock` is never included.
///
/// Only regular files and directories are accepted: a symbolic link, a Windows junction or any other
/// special file fails the snapshot with [`RemapError::SaveLink`]. Nothing is ever followed.
///
/// Known limitation: a program that ignores `session.lock`, rewrites a file at the same size and
/// restores its modification time is not detected.
#[derive(Debug, Clone)]
pub struct SaveSnapshot {
    root: PathBuf,
    files: BTreeMap<String, FileState>,
    directories: BTreeSet<String>,
}

impl SaveSnapshot {
    /// Walks `root` without following links.
    pub fn take(root: &Path) -> Result<Self> {
        let metadata = fs::symlink_metadata(root).ctx(|| root.display().to_string())?;
        if !fsutil::is_plain_dir(&metadata) {
            return Err(RemapError::SaveLink(format!(
                "{} is not a plain directory",
                root.display()
            )));
        }
        let mut snapshot = SaveSnapshot {
            root: root.to_path_buf(),
            files: BTreeMap::new(),
            directories: BTreeSet::new(),
        };
        snapshot.walk(root, "")?;
        Ok(snapshot)
    }

    fn walk(&mut self, directory: &Path, prefix: &str) -> Result<()> {
        for entry in fs::read_dir(directory).ctx(|| directory.display().to_string())? {
            let entry = entry.ctx(|| directory.display().to_string())?;
            let path = entry.path();
            let name = entry.file_name().into_string().map_err(|name| {
                RemapError::invalid_data(
                    directory.display().to_string(),
                    format!("file name {name:?} is not valid Unicode"),
                )
            })?;
            let relative = if prefix.is_empty() {
                name
            } else {
                format!("{prefix}/{name}")
            };
            let metadata = fs::symlink_metadata(&path).ctx(|| path.display().to_string())?;
            if fsutil::is_plain_dir(&metadata) {
                self.directories.insert(relative.clone());
                self.walk(&path, &relative)?;
            } else if fsutil::is_plain_file(&metadata) {
                if relative != SESSION_LOCK {
                    let modified = metadata.modified().ctx(|| path.display().to_string())?;
                    self.files.insert(
                        relative,
                        FileState {
                            size: metadata.len(),
                            modified,
                        },
                    );
                }
            } else {
                return Err(RemapError::SaveLink(format!(
                    "{relative} is a link or special file"
                )));
            }
        }
        Ok(())
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn files(&self) -> &BTreeMap<String, FileState> {
        &self.files
    }

    pub fn directories(&self) -> &BTreeSet<String> {
        &self.directories
    }

    pub fn total_bytes(&self) -> u64 {
        self.files.values().map(|state| state.size).sum()
    }

    /// Walks the root again and fails with [`RemapError::SaveChanged`] if anything differs.
    pub fn verify(&self) -> Result<()> {
        let now = SaveSnapshot::take(&self.root)?;
        if now.directories != self.directories {
            let changed: Vec<_> = self
                .directories
                .symmetric_difference(&now.directories)
                .take(5)
                .collect();
            return Err(RemapError::SaveChanged(format!(
                "A directory was added or removed since the snapshot: {changed:?}"
            )));
        }
        if now.files != self.files {
            let keys: BTreeSet<&String> = self.files.keys().chain(now.files.keys()).collect();
            let changed: Vec<_> = keys
                .into_iter()
                .filter(|k| self.files.get(*k) != now.files.get(*k))
                .take(5)
                .collect();
            return Err(RemapError::SaveChanged(format!(
                "Files changed since the snapshot: {changed:?}"
            )));
        }
        Ok(())
    }

    /// Fails with [`RemapError::SaveChanged`] if `relative_path` is no longer the regular file in
    /// this snapshot.
    pub fn check_unchanged(&self, relative_path: &str) -> Result<()> {
        self.check_file(relative_path).map(|_| ())
    }

    /// Reads `relative_path` in one pass, without following links. The file must still match this
    /// snapshot, and exactly the recorded number of bytes must be read.
    pub fn read_file(&self, relative_path: &str) -> Result<Vec<u8>> {
        let state = self.check_file(relative_path)?;
        if state.size > (i32::MAX - 8) as u64 {
            return Err(RemapError::LimitExceeded(format!(
                "{relative_path} is too large to read at once"
            )));
        }
        let bytes = match compress::read_at_most(
            self.open(relative_path)?,
            state.size as usize,
            relative_path,
        ) {
            Err(RemapError::LimitExceeded(_)) => {
                return Err(RemapError::SaveChanged(format!(
                    "{relative_path} grew while reading"
                )));
            }
            other => other?,
        };
        if bytes.len() as u64 != state.size {
            return Err(RemapError::SaveChanged(format!(
                "{relative_path} changed while reading"
            )));
        }
        Ok(bytes)
    }

    /// Streams `relative_path` to `destination` (which must not exist) without following links,
    /// passing every block read to `observer`. Exactly the recorded number of bytes must be copied.
    pub fn copy_file(
        &self,
        relative_path: &str,
        destination: &Path,
        observer: &mut dyn FnMut(&[u8]),
    ) -> Result<()> {
        let state = self.check_file(relative_path)?;
        let mut input = self.open(relative_path)?;
        let mut output = create_new(destination)?;
        let copied = copy_checked(
            &mut input,
            &mut output,
            relative_path,
            state.size,
            destination,
            observer,
        )?;
        if copied != state.size {
            return Err(RemapError::SaveChanged(format!(
                "{relative_path} changed while copying"
            )));
        }
        Ok(())
    }

    /// Copies every directory and file of this snapshot to the empty directory `target`, keeping
    /// modification times, then walks the root again. Any added, removed, changed or linked entry
    /// fails the copy; the caller removes `target` on failure.
    pub fn copy_to(
        &self,
        target: &Path,
        ensure_active: &dyn Fn() -> Result<()>,
        progress: &mut dyn FnMut(u64),
    ) -> Result<()> {
        fs::create_dir_all(target).ctx(|| target.display().to_string())?;
        for directory in &self.directories {
            let path = fsutil::resolve(target, directory);
            fs::create_dir_all(&path).ctx(|| path.display().to_string())?;
        }
        let mut copied = 0u64;
        for relative_path in self.files.keys() {
            ensure_active()?;
            let state = self.check_file(relative_path)?;
            let destination = fsutil::resolve(target, relative_path);
            if let Some(parent) = destination.parent() {
                fs::create_dir_all(parent).ctx(|| parent.display().to_string())?;
            }
            let mut input = self.open(relative_path)?;
            let written = {
                let mut output = create_new(&destination)?;
                copy_checked(
                    &mut input,
                    &mut output,
                    relative_path,
                    state.size,
                    &destination,
                    &mut |_| {},
                )?
            };
            if written != state.size {
                return Err(RemapError::SaveChanged(format!(
                    "{relative_path} changed while copying"
                )));
            }
            OpenOptions::new()
                .write(true)
                .open(&destination)
                .and_then(|file| file.set_modified(state.modified))
                .ctx(|| destination.display().to_string())?;
            copied += written;
            progress(copied);
        }
        self.verify()
    }

    fn check_file(&self, relative_path: &str) -> Result<FileState> {
        let state = *self.files.get(relative_path).ok_or_else(|| {
            RemapError::SaveChanged(format!("{relative_path} is not in the snapshot"))
        })?;
        let path = fsutil::resolve(&self.root, relative_path);
        let metadata = match fs::symlink_metadata(&path) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Err(RemapError::SaveChanged(format!(
                    "{relative_path} was removed since the snapshot"
                )));
            }
            Err(error) => return Err(RemapError::io(path.display().to_string(), error)),
        };
        if fsutil::is_special(&metadata) {
            return Err(RemapError::SaveLink(format!(
                "{relative_path} became a link or special file"
            )));
        }
        let now = FileState {
            size: metadata.len(),
            modified: metadata.modified().ctx(|| path.display().to_string())?,
        };
        if !metadata.is_file() || now != state {
            return Err(RemapError::SaveChanged(format!(
                "{relative_path} changed since the snapshot"
            )));
        }
        Ok(state)
    }

    fn open(&self, relative_path: &str) -> Result<File> {
        fsutil::open_nofollow(&fsutil::resolve(&self.root, relative_path))
    }
}

fn create_new(path: &Path) -> Result<File> {
    OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .ctx(|| path.display().to_string())
}

fn copy_checked(
    input: &mut File,
    output: &mut File,
    relative_path: &str,
    expected: u64,
    destination: &Path,
    observer: &mut dyn FnMut(&[u8]),
) -> Result<u64> {
    let mut buffer = vec![0u8; COPY_BUFFER];
    let mut copied = 0u64;
    loop {
        let read = input.read(&mut buffer).ctx(|| relative_path.to_string())?;
        if read == 0 {
            break;
        }
        copied += read as u64;
        if copied > expected {
            return Err(RemapError::SaveChanged(format!(
                "{relative_path} grew while copying"
            )));
        }
        observer(&buffer[..read]);
        output
            .write_all(&buffer[..read])
            .ctx(|| destination.display().to_string())?;
    }
    Ok(copied)
}

/// Holds a save's `session.lock` the way Minecraft's `DirectoryLock` does (an exclusive lock on the
/// whole file), so Minecraft cannot open the save while it is copied, and a save that Minecraft has
/// open is detected. The file is created if missing and never truncated or written. Dropping the
/// lock releases it; if the process dies, the OS releases it.
#[derive(Debug)]
pub struct SaveSourceLock {
    world: PathBuf,
    /// Always `Some` until dropped.
    file: Option<File>,
    #[cfg(unix)]
    key: (u64, u64),
}

#[cfg(unix)]
static LOCKED_IN_PROCESS: std::sync::Mutex<BTreeSet<(u64, u64)>> =
    std::sync::Mutex::new(BTreeSet::new());

impl SaveSourceLock {
    pub fn acquire(world: &Path) -> Result<Self> {
        let path = world.join(SESSION_LOCK);
        if let Ok(metadata) = fs::symlink_metadata(&path)
            && !fsutil::is_plain_file(&metadata)
        {
            return Err(RemapError::SaveLink(format!(
                "{} is not a regular file",
                path.display()
            )));
        }
        let file = fsutil::no_follow(OpenOptions::new().write(true).create(true))
            .open(&path)
            .ctx(|| path.display().to_string())?;
        Self::lock(world, file)
    }

    #[cfg(windows)]
    fn lock(world: &Path, file: File) -> Result<Self> {
        match file.try_lock() {
            Ok(()) => Ok(SaveSourceLock {
                world: world.to_path_buf(),
                file: Some(file),
            }),
            Err(std::fs::TryLockError::WouldBlock) => Err(RemapError::SaveInUse {
                message: format!("{} is locked by another program", world.display()),
                cause: None,
            }),
            Err(std::fs::TryLockError::Error(error)) => Err(RemapError::SaveInUse {
                message: format!("{} is locked by another program", world.display()),
                cause: Some(error),
            }),
        }
    }

    /// Java locks with `fcntl` on Unix, which does not see `flock` locks, so this uses `fcntl` too.
    /// `fcntl` locks are per process, so locks held by this process are tracked separately.
    #[cfg(unix)]
    fn lock(world: &Path, file: File) -> Result<Self> {
        use std::os::unix::fs::MetadataExt;
        use std::os::unix::io::AsRawFd;
        let metadata = file.metadata().ctx(|| world.display().to_string())?;
        let key = (metadata.dev(), metadata.ino());
        let mut held = LOCKED_IN_PROCESS
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if held.contains(&key) {
            return Err(RemapError::SaveInUse {
                message: format!("{} is already locked by this process", world.display()),
                cause: None,
            });
        }
        // SAFETY: `flock` is plain data; F_SETLK only reads it.
        let mut request: libc::flock = unsafe { std::mem::zeroed() };
        request.l_type = libc::F_WRLCK as _;
        request.l_whence = libc::SEEK_SET as _;
        request.l_start = 0;
        request.l_len = 0;
        // SAFETY: the descriptor is open for writing and `request` is a valid flock structure.
        if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETLK, &request) } == -1 {
            let error = std::io::Error::last_os_error();
            return Err(RemapError::SaveInUse {
                message: format!("{} is locked by another program", world.display()),
                cause: Some(error),
            });
        }
        held.insert(key);
        Ok(SaveSourceLock {
            world: world.to_path_buf(),
            file: Some(file),
            key,
        })
    }

    pub fn world(&self) -> &Path {
        &self.world
    }
}

impl Drop for SaveSourceLock {
    fn drop(&mut self) {
        // Closing the handle releases the lock (on Windows and for fcntl on Unix).
        drop(self.file.take());
        #[cfg(unix)]
        {
            // Forget the lock only after the descriptor is closed: closing any descriptor of the file
            // drops every fcntl lock this process holds on it, including one taken in between.
            let mut held = LOCKED_IN_PROCESS
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            held.remove(&self.key);
        }
    }
}
