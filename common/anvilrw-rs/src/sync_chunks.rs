//! Dimension folders and the `data/rdi_sync_chunks.dat` list of marked chunks.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

use regex::Regex;
use uuid::Uuid;

use crate::compress;
use crate::error::{RemapError, Result};
use crate::fsutil;
use crate::limits::STRICT_NBT_FILE_BYTES;
use crate::nbt::tag;
use crate::reader::NbtDocument;

const MAX_DIMENSION_ID_LENGTH: usize = 512;

/// Windows device names, which open a device instead of a file even with an extension.
fn is_windows_reserved(name: &str) -> bool {
    matches!(name, "con" | "prn" | "aux" | "nul")
        || ((name.starts_with("com") || name.starts_with("lpt"))
            && name.len() == 4
            && matches!(name.as_bytes()[3], b'1'..=b'9'))
}

/// The folder of `dimension_id` inside the save at `root`, as `DimensionType.getStorageFolder` lays
/// it out: the root for the overworld, `DIM-1` for the nether, `DIM1` for the end, and
/// `dimensions/<namespace>/<path>` for any other dimension.
///
/// Dimension IDs come from untrusted files, and resource location rules alone allow `..` and
/// leading `/` segments. This therefore also rejects empty, `.` and `..` segments, segments ending
/// in `.` (Windows drops the dot), Windows device names, and any result outside `root`.
pub fn dimension_storage_folder(root: &Path, dimension_id: &str) -> Result<PathBuf> {
    let invalid = |reason: &str| {
        Err(RemapError::InvalidDimensionId(format!(
            "Invalid dimension ID \"{dimension_id}\": {reason}"
        )))
    };
    let length = dimension_id.encode_utf16().count();
    if length == 0 || length > MAX_DIMENSION_ID_LENGTH {
        return invalid("length");
    }
    let separator = dimension_id.find(':');
    let namespace = match separator {
        Some(at) if at > 0 => &dimension_id[..at],
        _ => "minecraft",
    };
    let path = match separator {
        Some(at) => &dimension_id[at + 1..],
        None => dimension_id,
    };
    if !namespace
        .chars()
        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "_-.".contains(c))
    {
        return invalid("namespace characters");
    }
    if !path
        .chars()
        .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "_-./".contains(c))
    {
        return invalid("path characters");
    }

    let base = fsutil::normalize(root);
    match (namespace, path) {
        ("minecraft", "overworld") => return Ok(base),
        ("minecraft", "the_nether") => return Ok(base.join("DIM-1")),
        ("minecraft", "the_end") => return Ok(base.join("DIM1")),
        _ => {}
    }
    let dimensions = base.join("dimensions");
    let mut folder = dimensions.clone();
    for segment in std::iter::once(namespace).chain(path.split('/')) {
        if segment.is_empty() {
            return invalid("empty segment");
        }
        if segment == "." || segment == ".." {
            return invalid("relative segment");
        }
        if segment.ends_with('.') {
            return invalid("segment ends with a dot");
        }
        if is_windows_reserved(segment.split('.').next().unwrap_or(segment)) {
            return invalid("reserved name");
        }
        folder.push(segment);
    }
    let folder = fsutil::normalize(&folder);
    if !folder.starts_with(&dimensions) || folder == dimensions {
        return invalid("outside the save");
    }
    Ok(folder)
}

/// One marked chunk. `dimension_id` is canonical: `minecraft:` is added when the namespace is missing.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct SaveSyncChunk {
    pub dimension_id: String,
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub owner: Uuid,
}

pub const SYNC_CHUNKS_RELATIVE_PATH: &str = "data/rdi_sync_chunks.dat";
pub const SYNC_CHUNKS_MAX_ENTRIES: usize = 256;

static DASHED_UUID: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        .expect("valid regex")
});

fn list_error(message: String) -> RemapError {
    RemapError::SyncChunkList {
        message,
        cause: None,
    }
}

/// Reads `data/rdi_sync_chunks.dat` with the same rules as the mod's `SyncChunkSaveFormat.read`:
/// gzip NBT whose `data.chunks` list holds `{dimension, x, z, owner}` compounds. The whole file is
/// rejected if any entry is invalid or duplicated, or there are too many entries. Dimension IDs must
/// also be safe as save folders ([`dimension_storage_folder`]).
pub fn parse_sync_chunks(gzipped: &[u8]) -> Result<Vec<SaveSyncChunk>> {
    const PATH: &str = SYNC_CHUNKS_RELATIVE_PATH;
    if gzipped.len() > STRICT_NBT_FILE_BYTES {
        return Err(RemapError::LimitExceeded(format!(
            "{PATH} exceeds its size limit"
        )));
    }
    let nbt = compress::gunzip(gzipped, STRICT_NBT_FILE_BYTES, PATH)?;
    let document = NbtDocument::open(nbt).map_err(|error| RemapError::SyncChunkList {
        message: format!("{PATH} is not valid NBT"),
        cause: Some(Box::new(error)),
    })?;
    let root = document.root();
    let list = root
        .child("data")
        .and_then(|data| data.child("chunks"))
        .filter(|node| node.kind == tag::LIST)
        .ok_or_else(|| list_error(format!("{PATH} has no data.chunks list")))?;
    let elements = list.list_elements().unwrap_or_default();
    if elements.len() > SYNC_CHUNKS_MAX_ENTRIES {
        return Err(list_error(format!(
            "{PATH} has {} entries, more than {SYNC_CHUNKS_MAX_ENTRIES}",
            elements.len()
        )));
    }
    let mut seen = HashSet::new();
    let mut chunks = Vec::with_capacity(elements.len());
    for (index, element) in elements.iter().enumerate() {
        let invalid = |reason: String| list_error(format!("{PATH} entry {}: {reason}", index + 1));
        if element.kind != tag::COMPOUND {
            return Err(invalid("not a compound".into()));
        }
        let dimension = match element.child("dimension") {
            Some(node) => node.string()?,
            None => None,
        }
        .ok_or_else(|| invalid("missing dimension".into()))?;
        let x = element
            .child("x")
            .and_then(|n| n.int())
            .ok_or_else(|| invalid("missing x".into()))?;
        let z = element
            .child("z")
            .and_then(|n| n.int())
            .ok_or_else(|| invalid("missing z".into()))?;
        let owner_text = match element.child("owner") {
            Some(node) => node.string()?,
            None => None,
        }
        .ok_or_else(|| invalid("missing owner".into()))?;
        if !DASHED_UUID.is_match(&owner_text) {
            return Err(invalid(format!("invalid owner {owner_text}")));
        }
        if let Err(error) = dimension_storage_folder(Path::new("world"), &dimension) {
            return Err(invalid(error.to_string()));
        }
        let canonical = canonical_dimension_id(&dimension);
        if !seen.insert((canonical.clone(), x, z)) {
            return Err(invalid(format!("duplicate chunk {canonical} {x},{z}")));
        }
        let owner = Uuid::parse_str(&owner_text)
            .map_err(|_| invalid(format!("invalid owner {owner_text}")))?;
        chunks.push(SaveSyncChunk {
            dimension_id: canonical,
            chunk_x: x,
            chunk_z: z,
            owner,
        });
    }
    Ok(chunks)
}

/// `overworld` and `:overworld` become `minecraft:overworld`, as Minecraft's resource locations do.
pub fn canonical_dimension_id(dimension_id: &str) -> String {
    match dimension_id.find(':') {
        None => format!("minecraft:{dimension_id}"),
        Some(0) => format!("minecraft{dimension_id}"),
        Some(_) => dimension_id.to_string(),
    }
}
