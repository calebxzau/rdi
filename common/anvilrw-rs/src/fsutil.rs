//! File system helpers that never follow links, and lexical path normalization.

use std::fs::{File, Metadata, OpenOptions};
use std::path::{Component, Path, PathBuf};

use crate::error::{IoContext, Result};

/// True for a regular file that is not a link, junction or other reparse point. On Windows this
/// follows Java's `isOther`: any reparse point or device counts as special.
pub(crate) fn is_plain_file(metadata: &Metadata) -> bool {
    metadata.file_type().is_file() && !is_special(metadata)
}

/// True for a directory that is not a link, junction or other reparse point.
pub(crate) fn is_plain_dir(metadata: &Metadata) -> bool {
    metadata.file_type().is_dir() && !is_special(metadata)
}

/// A symbolic link, a Windows reparse point (junction, cloud placeholder…) or a device.
pub(crate) fn is_special(metadata: &Metadata) -> bool {
    if metadata.file_type().is_symlink() {
        return true;
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        const FILE_ATTRIBUTE_DEVICE: u32 = 0x40;
        const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x400;
        if metadata.file_attributes() & (FILE_ATTRIBUTE_DEVICE | FILE_ATTRIBUTE_REPARSE_POINT) != 0
        {
            return true;
        }
    }
    !metadata.file_type().is_file() && !metadata.file_type().is_dir()
}

/// Adds the flag that makes opening a link fail (Unix) or open the link itself (Windows).
pub(crate) fn no_follow(options: &mut OpenOptions) -> &mut OpenOptions {
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW);
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        const FILE_FLAG_OPEN_REPARSE_POINT: u32 = 0x0020_0000;
        options.custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    options
}

/// Opens `path` for reading without following a link.
pub(crate) fn open_nofollow(path: &Path) -> Result<File> {
    no_follow(OpenOptions::new().read(true))
        .open(path)
        .ctx(|| path.display().to_string())
}

/// Removes `.` segments and resolves `..` segments without touching the file system, like Java's
/// `Path.normalize`.
pub(crate) fn normalize(path: &Path) -> PathBuf {
    let mut result = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                if matches!(result.components().next_back(), Some(Component::Normal(_))) {
                    result.pop();
                } else if !matches!(
                    result.components().next_back(),
                    Some(Component::RootDir | Component::Prefix(_))
                ) {
                    result.push("..");
                }
            }
            other => result.push(other.as_os_str()),
        }
    }
    result
}

/// `root` joined with a `/`-separated relative path.
pub(crate) fn resolve(root: &Path, relative: &str) -> PathBuf {
    let mut path = root.to_path_buf();
    for segment in relative.split('/').filter(|s| !s.is_empty()) {
        path.push(segment);
    }
    path
}
