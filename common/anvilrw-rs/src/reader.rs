//! Read-only access to a few values of an untrusted NBT document, by path.

use std::path::Path;

use uuid::Uuid;

use crate::compress;
use crate::error::{IoContext, RemapError, Result};
use crate::fsutil;
use crate::limits::STRICT_NBT_FILE_BYTES;
use crate::nbt::{
    NbtWalker, NoVisitor, decode_modified_utf8, encode_modified_utf8, s4, tag, u2, u8x8,
};

/// Metadata from `level.dat` used by the import prechecks. Values follow Minecraft's lenient
/// `CompoundTag` getters: a missing field or a field of the wrong type reads as absent/false.
#[derive(Debug, Clone, PartialEq)]
pub struct LevelMetadata {
    /// `Data.Version.Name`, e.g. `1.20.1`.
    pub version_name: Option<String>,
    /// `Data.DataVersion`.
    pub data_version: Option<i32>,
    /// `Data.ServerBrands`, a cumulative history of the brands that opened the save.
    pub server_brands: Vec<String>,
    /// `Data.hardcore`.
    pub hardcore: bool,
    /// Absolute offset of the `Data.hardcore` byte, for switching hardcore off in place.
    pub hardcore_byte_offset: Option<usize>,
    /// `Data.Player.UUID`, the singleplayer owner.
    pub singleplayer_uuid: Option<Uuid>,
    /// `ModId`s of the root `fml.LoadingModList`, or `None` when the root has no `fml` compound.
    pub fml_mod_ids: Option<Vec<String>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SaveLoaderDetection {
    Forge,
    NeoForge,
    /// Fabric appears in the world's brand history. The most recent loader is unknown.
    FabricHistory,
    Vanilla,
    Unknown,
    Conflicting,
}

impl SaveLoaderDetection {
    pub fn name(self) -> &'static str {
        match self {
            SaveLoaderDetection::Forge => "Forge",
            SaveLoaderDetection::NeoForge => "NeoForge",
            SaveLoaderDetection::FabricHistory => "FabricHistory",
            SaveLoaderDetection::Vanilla => "Vanilla",
            SaveLoaderDetection::Unknown => "Unknown",
            SaveLoaderDetection::Conflicting => "Conflicting",
        }
    }
}

impl LevelMetadata {
    /// FML records a mod list on save. Minecraft's server brands accumulate across opens.
    pub fn loader_detection(&self) -> SaveLoaderDetection {
        let fml_ids = self.fml_mod_ids.as_deref().unwrap_or_default();
        let forge = fml_ids.iter().any(|id| id == "forge");
        let neo_forge = fml_ids.iter().any(|id| id == "neoforge");
        if forge && neo_forge {
            return SaveLoaderDetection::Conflicting;
        }
        if forge {
            return SaveLoaderDetection::Forge;
        }
        if neo_forge {
            return SaveLoaderDetection::NeoForge;
        }
        let brands: std::collections::BTreeSet<&str> = self
            .server_brands
            .iter()
            .flat_map(|brand| brand.split(','))
            .map(str::trim)
            .filter(|b| !b.is_empty())
            .collect();
        if brands.contains("fabric") {
            let incompatible = ["forge", "neoforge", "quilt"]
                .iter()
                .any(|brand| brands.contains(brand));
            return if self.fml_mod_ids.is_some() || incompatible {
                SaveLoaderDetection::Conflicting
            } else {
                SaveLoaderDetection::FabricHistory
            };
        }
        if self.fml_mod_ids.is_none() && brands.len() == 1 && brands.contains("vanilla") {
            return SaveLoaderDetection::Vanilla;
        }
        SaveLoaderDetection::Unknown
    }
}

/// A complete, structurally validated NBT document.
///
/// Lookups only ever follow structure known to be in bounds. Nothing is allocated from a declared
/// length, and only requested strings are decoded (with Java modified UTF-8 semantics).
#[derive(Debug, Clone)]
pub struct NbtDocument {
    bytes: Vec<u8>,
    root_payload: usize,
}

impl NbtDocument {
    /// Validates `nbt` as a complete document and opens it for lookups.
    pub fn open(nbt: Vec<u8>) -> Result<Self> {
        let root_payload = NbtWalker::new(&nbt).walk_document(&mut NoVisitor)?;
        Ok(NbtDocument {
            bytes: nbt,
            root_payload,
        })
    }

    /// Opens a gzip NBT file from untrusted input: at most [`STRICT_NBT_FILE_BYTES`] compressed and
    /// decompressed, without following links.
    pub fn open_gzip_file(file: &Path) -> Result<Self> {
        let name = file
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        let metadata = std::fs::symlink_metadata(file).ctx(|| file.display().to_string())?;
        if !fsutil::is_plain_file(&metadata) {
            return Err(RemapError::invalid_data(
                file.display().to_string(),
                format!("{} is not a regular file", file.display()),
            ));
        }
        if metadata.len() > STRICT_NBT_FILE_BYTES as u64 {
            return Err(RemapError::LimitExceeded(format!(
                "{name} exceeds {STRICT_NBT_FILE_BYTES} bytes"
            )));
        }
        let compressed =
            compress::read_at_most(fsutil::open_nofollow(file)?, STRICT_NBT_FILE_BYTES, &name)?;
        Self::open(compress::gunzip(&compressed, STRICT_NBT_FILE_BYTES, &name)?)
    }

    /// Reads the [`LevelMetadata`] of a gzip `level.dat` file from untrusted input.
    pub fn read_level_dat(file: &Path) -> Result<LevelMetadata> {
        Self::open_gzip_file(file)?.level_metadata()
    }

    /// Wraps a compound payload as a document with an unnamed root, as `NbtIo.write` does.
    pub fn root_document(compound_payload: &[u8]) -> Vec<u8> {
        let mut document = Vec::with_capacity(compound_payload.len() + 3);
        document.extend_from_slice(&[tag::COMPOUND, 0, 0]);
        document.extend_from_slice(compound_payload);
        document
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub fn into_bytes(self) -> Vec<u8> {
        self.bytes
    }

    pub fn root(&self) -> NbtNode<'_> {
        NbtNode {
            src: &self.bytes,
            kind: tag::COMPOUND,
            payload_offset: self.root_payload,
        }
    }

    /// The exact payload bytes of the compound at `path` (the root for an empty path), or `None` if
    /// it is absent or not a compound.
    pub fn compound_payload(&self, path: &[&str]) -> Option<Vec<u8>> {
        let mut node = Some(self.root());
        for name in path {
            node = node.and_then(|n| n.child(name));
        }
        let compound = node.filter(|n| n.kind == tag::COMPOUND)?;
        Some(self.bytes[compound.payload_offset..compound.payload_end()].to_vec())
    }

    /// Replaces (or adds) one named compound without decoding or re-encoding unrelated tags.
    pub fn replace_compound(
        &self,
        parent_path: &[&str],
        name: &str,
        payload: &[u8],
    ) -> Result<Vec<u8>> {
        Self::open(Self::root_document(payload))?;
        let mut parent = self.root();
        for part in parent_path {
            parent = parent
                .unique_child(part)?
                .ok_or_else(|| RemapError::Invalid(format!("Missing {part}")))?;
        }
        if parent.kind != tag::COMPOUND {
            return Err(RemapError::Invalid("Parent is not a compound".into()));
        }
        let encoded = encode_modified_utf8(name);
        if encoded.len() > 65535 {
            return Err(RemapError::invalid_data(
                "NBT key",
                "encoded string too long",
            ));
        }
        let mut header = vec![tag::COMPOUND];
        header.extend_from_slice(&(encoded.len() as u16).to_be_bytes());
        header.extend_from_slice(&encoded);
        let old = parent.unique_child(name)?;
        if old.is_some_and(|old| old.kind != tag::COMPOUND) {
            return Err(RemapError::Invalid(format!("{name} is not a compound")));
        }
        let start = match old {
            Some(old) => old.payload_offset - header.len(),
            None => parent.payload_end() - 1,
        };
        let end = old.map(|old| old.payload_end()).unwrap_or(start);
        let size = self.bytes.len() - (end - start) + header.len() + payload.len();
        if size > STRICT_NBT_FILE_BYTES {
            return Err(RemapError::Invalid("Edited NBT exceeds size limit".into()));
        }
        let mut result = Vec::with_capacity(size);
        result.extend_from_slice(&self.bytes[..start]);
        result.extend_from_slice(&header);
        result.extend_from_slice(payload);
        result.extend_from_slice(&self.bytes[end..]);
        Self::open(result).map(Self::into_bytes)
    }

    /// Reads the fields of [`LevelMetadata`]. Fails only if a requested string is not valid
    /// modified UTF-8.
    pub fn level_metadata(&self) -> Result<LevelMetadata> {
        let root = self.root();
        let data = root.child("Data").filter(|n| n.kind == tag::COMPOUND);
        let hardcore = data
            .and_then(|d| d.child("hardcore"))
            .filter(|n| n.kind == tag::BYTE);
        let fml = root.child("fml").filter(|n| n.kind == tag::COMPOUND);
        let version_name = match data
            .and_then(|d| d.child("Version"))
            .and_then(|v| v.child("Name"))
        {
            Some(node) => node.string()?,
            None => None,
        };
        let mut server_brands = Vec::new();
        if let Some(elements) = data
            .and_then(|d| d.child("ServerBrands"))
            .and_then(|n| n.list_elements())
        {
            for element in elements {
                server_brands.extend(element.string()?);
            }
        }
        let fml_mod_ids = match fml {
            None => None,
            Some(compound) => {
                let mut ids = Vec::new();
                if let Some(elements) = compound
                    .child("LoadingModList")
                    .and_then(|n| n.list_elements())
                {
                    for element in elements {
                        if let Some(id) = element.child("ModId") {
                            ids.extend(id.string()?);
                        }
                    }
                }
                Some(ids)
            }
        };
        Ok(LevelMetadata {
            version_name,
            data_version: data
                .and_then(|d| d.child("DataVersion"))
                .and_then(|n| n.int()),
            server_brands,
            hardcore: hardcore.and_then(|n| n.byte()).is_some_and(|b| b != 0),
            hardcore_byte_offset: hardcore.map(|n| n.payload_offset),
            singleplayer_uuid: data
                .and_then(|d| d.child("Player"))
                .and_then(|p| p.child("UUID"))
                .and_then(|n| n.uuid()),
            fml_mod_ids,
        })
    }
}

/// A tag inside an [`NbtDocument`].
#[derive(Debug, Clone, Copy)]
pub struct NbtNode<'a> {
    src: &'a [u8],
    pub kind: u8,
    pub payload_offset: usize,
}

const VALIDATED: &str = "the document was validated when it was opened";

impl<'a> NbtNode<'a> {
    fn entries(&self) -> impl Iterator<Item = (&'a [u8], NbtNode<'a>)> + 'a {
        let src = self.src;
        let mut position = if self.kind == tag::COMPOUND {
            Some(self.payload_offset)
        } else {
            None
        };
        std::iter::from_fn(move || {
            let at = position?;
            let kind = src[at];
            if kind == tag::END {
                position = None;
                return None;
            }
            let name_length = u2(src, at + 1);
            let name_offset = at + 3;
            let payload = name_offset + name_length;
            position = Some(
                NbtWalker::new(src)
                    .walk_payload(kind, payload, 0, &mut NoVisitor)
                    .expect(VALIDATED),
            );
            Some((
                &src[name_offset..payload],
                NbtNode {
                    src,
                    kind,
                    payload_offset: payload,
                },
            ))
        })
    }

    /// The first entry named `name` of this compound.
    pub fn child(&self, name: &str) -> Option<NbtNode<'a>> {
        let wanted = encode_modified_utf8(name);
        self.entries()
            .find(|(key, _)| *key == wanted.as_slice())
            .map(|(_, node)| node)
    }

    /// The entry named `name`; fails if the compound has it twice, so identity edits never disagree
    /// with Minecraft about duplicate entries.
    pub fn unique_child(&self, name: &str) -> Result<Option<NbtNode<'a>>> {
        let wanted = encode_modified_utf8(name);
        let mut found = None;
        for (key, node) in self.entries() {
            if key == wanted.as_slice() {
                if found.is_some() {
                    return Err(RemapError::Invalid(format!("Duplicate NBT entry: {name}")));
                }
                found = Some(node);
            }
        }
        Ok(found)
    }

    /// The elements of this list, or `None` if this is not a list.
    pub fn list_elements(&self) -> Option<Vec<NbtNode<'a>>> {
        if self.kind != tag::LIST {
            return None;
        }
        let element = self.src[self.payload_offset];
        let count = s4(self.src, self.payload_offset + 1).max(0) as usize;
        let walker = NbtWalker::new(self.src);
        let mut position = self.payload_offset + 5;
        let mut elements = Vec::with_capacity(count.min(1024));
        for _ in 0..count {
            elements.push(NbtNode {
                src: self.src,
                kind: element,
                payload_offset: position,
            });
            position = walker
                .walk_payload(element, position, 0, &mut NoVisitor)
                .expect(VALIDATED);
        }
        Some(elements)
    }

    pub fn payload_end(&self) -> usize {
        NbtWalker::new(self.src)
            .walk_payload(self.kind, self.payload_offset, 0, &mut NoVisitor)
            .expect(VALIDATED)
    }

    /// The value of a `TAG_String`, or `None` for another type. Fails on invalid modified UTF-8.
    pub fn string(&self) -> Result<Option<String>> {
        if self.kind != tag::STRING {
            return Ok(None);
        }
        let length = u2(self.src, self.payload_offset);
        let start = self.payload_offset + 2;
        decode_modified_utf8(&self.src[start..start + length]).map(Some)
    }

    pub fn int(&self) -> Option<i32> {
        (self.kind == tag::INT).then(|| s4(self.src, self.payload_offset))
    }

    pub fn byte(&self) -> Option<i8> {
        (self.kind == tag::BYTE).then(|| self.src[self.payload_offset] as i8)
    }

    pub fn double(&self) -> Option<f64> {
        (self.kind == tag::DOUBLE).then(|| f64::from_bits(u8x8(self.src, self.payload_offset)))
    }

    /// A UUID stored as `IntArray[4]`, the way Minecraft stores entity UUIDs.
    pub fn uuid(&self) -> Option<Uuid> {
        if self.kind != tag::INT_ARRAY || s4(self.src, self.payload_offset) != 4 {
            return None;
        }
        Some(Uuid::from_u64_pair(
            u8x8(self.src, self.payload_offset + 4),
            u8x8(self.src, self.payload_offset + 12),
        ))
    }
}
