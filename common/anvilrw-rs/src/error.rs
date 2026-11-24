use std::fmt;
use std::io;

/// A failure of a save import step. The variants follow the exception classes of the Kotlin
/// `calebxzau.rdi.anvilrw.remap` package, so callers can map each one to a player-facing message.
#[derive(Debug)]
pub enum RemapError {
    /// NBT bytes are not a structurally valid document. `offset` is absolute in the input.
    NbtFormat { message: String, offset: usize },
    /// Renaming a compound key would produce two equal keys in the same compound.
    NbtKeyConflict(String),
    /// Data exceeded one of the limits in [`crate::limits`].
    LimitExceeded(String),
    /// A region file or one of its chunks cannot be read strictly.
    RegionFormat {
        message: String,
        cause: Option<Box<RemapError>>,
    },
    /// A file handed to the text patcher is not strictly valid UTF-8, or contains NUL bytes.
    NotUtf8Text(String),
    /// A dimension ID that is not a valid resource location or cannot be used as a save folder.
    InvalidDimensionId(String),
    /// The save is open in Minecraft or another program that holds its `session.lock`.
    SaveInUse {
        message: String,
        cause: Option<io::Error>,
    },
    /// The save contains a symbolic link, junction or other special file.
    SaveLink(String),
    /// The save changed after its snapshot was taken.
    SaveChanged(String),
    /// `data/rdi_sync_chunks.dat` is missing required fields, has invalid entries or too many.
    SyncChunkList {
        message: String,
        cause: Option<Box<RemapError>>,
    },
    /// Renaming player UUIDs in paths would write two files to the same output path.
    PathConflict(String),
    /// A precondition of the call does not hold (Kotlin `require`).
    Invalid(String),
    /// An I/O failure, or data that a decompressor or string decoder rejected.
    Io { context: String, source: io::Error },
    /// The caller's `ensure_active` hook asked to stop.
    Cancelled,
}

impl RemapError {
    pub(crate) fn io(context: impl Into<String>, source: io::Error) -> Self {
        RemapError::Io {
            context: context.into(),
            source,
        }
    }

    pub(crate) fn invalid_data(context: impl Into<String>, message: impl Into<String>) -> Self {
        RemapError::Io {
            context: context.into(),
            source: io::Error::new(io::ErrorKind::InvalidData, message.into()),
        }
    }

    pub(crate) fn region(message: impl Into<String>) -> Self {
        RemapError::RegionFormat {
            message: message.into(),
            cause: None,
        }
    }

    /// The name of the Kotlin exception class this error corresponds to. `Io` has many Kotlin
    /// counterparts (`IOException`, `ZipException`, `EOFException`…) and reports `IOException`.
    pub fn kind_name(&self) -> &'static str {
        match self {
            RemapError::NbtFormat { .. } => "NbtFormatException",
            RemapError::NbtKeyConflict(_) => "NbtKeyConflictException",
            RemapError::LimitExceeded(_) => "RemapLimitExceededException",
            RemapError::RegionFormat { .. } => "RegionFormatException",
            RemapError::NotUtf8Text(_) => "NotUtf8TextException",
            RemapError::InvalidDimensionId(_) => "InvalidDimensionIdException",
            RemapError::SaveInUse { .. } => "SaveInUseException",
            RemapError::SaveLink(_) => "SaveLinkException",
            RemapError::SaveChanged(_) => "SaveChangedException",
            RemapError::SyncChunkList { .. } => "SyncChunkListException",
            RemapError::PathConflict(_) => "PathConflictException",
            RemapError::Invalid(_) => "IllegalArgumentException",
            RemapError::Io { .. } => "IOException",
            RemapError::Cancelled => "CancellationException",
        }
    }

    /// The error this one wraps, if any.
    pub fn cause(&self) -> Option<&RemapError> {
        match self {
            RemapError::RegionFormat { cause, .. } | RemapError::SyncChunkList { cause, .. } => {
                cause.as_deref()
            }
            _ => None,
        }
    }

    pub fn is_cancelled(&self) -> bool {
        matches!(self, RemapError::Cancelled)
    }
}

impl fmt::Display for RemapError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RemapError::NbtFormat { message, offset } => write!(f, "{message} (offset {offset})"),
            RemapError::NbtKeyConflict(message)
            | RemapError::LimitExceeded(message)
            | RemapError::NotUtf8Text(message)
            | RemapError::InvalidDimensionId(message)
            | RemapError::SaveLink(message)
            | RemapError::SaveChanged(message)
            | RemapError::PathConflict(message)
            | RemapError::Invalid(message) => f.write_str(message),
            RemapError::RegionFormat { message, .. }
            | RemapError::SyncChunkList { message, .. } => f.write_str(message),
            RemapError::SaveInUse { message, .. } => f.write_str(message),
            RemapError::Io { context, source } => write!(f, "{context}: {source}"),
            RemapError::Cancelled => f.write_str("cancelled"),
        }
    }
}

impl std::error::Error for RemapError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            RemapError::RegionFormat {
                cause: Some(cause), ..
            }
            | RemapError::SyncChunkList {
                cause: Some(cause), ..
            } => Some(cause.as_ref()),
            RemapError::SaveInUse {
                cause: Some(cause), ..
            } => Some(cause),
            RemapError::Io { source, .. } => Some(source),
            _ => None,
        }
    }
}

pub type Result<T, E = RemapError> = std::result::Result<T, E>;

/// Adds a context string to I/O results.
pub(crate) trait IoContext<T> {
    fn ctx(self, context: impl FnOnce() -> String) -> Result<T>;
}

impl<T> IoContext<T> for io::Result<T> {
    fn ctx(self, context: impl FnOnce() -> String) -> Result<T> {
        self.map_err(|source| RemapError::io(context(), source))
    }
}

/// An `ensure_active` hook that never cancels.
pub fn never_cancelled() -> Result<()> {
    Ok(())
}
