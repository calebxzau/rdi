//! Strict reading of Minecraft saves and replacement of player UUIDs, for importing a singleplayer
//! save into an RDI host. Rust port of the Kotlin `calebxzau.rdi.anvilrw.remap` package.
//!
//! Everything works on untrusted input: decompression and NBT walking are bounded by
//! [`limits`], links and special files are refused, and NBT is patched at byte level (never
//! decoded and re-encoded), so every byte that is not a replaced UUID stays as it was.
//!
//! All I/O is synchronous. Callers on an async runtime run these functions on a blocking thread.

pub mod compress;
pub mod error;
mod fsutil;
pub mod limits;
mod nbt;
pub mod patcher;
pub mod reader;
pub mod region;
pub mod remapper;
pub mod scanner;
pub mod snapshot;
pub mod sync_chunks;
pub mod text;
mod uuid_matcher;

pub use error::{RemapError, Result, never_cancelled};
pub use nbt::tag;
pub use patcher::{NbtBytePatcher, NbtPatchResult};
pub use reader::{LevelMetadata, NbtDocument, NbtNode, SaveLoaderDetection};
pub use region::{RegionRewriteResult, RewriteOptions};
pub use remapper::{
    RemapProgress, RemapReport, SaveUuidRemapper, SyncFilterReport, UnhandledFile,
    chunk_store_dimension,
};
pub use scanner::{
    DualIdentity, DualIdentityKeep, NameHint, NameHintSource, SaveMarkingPlayer, SavePlayer,
    SavePlayerKind, SavePlayerPosition, SaveScanResult, build_identity_mapping, classify,
    find_dual_identities, is_rdi_uuid, marking_players, offline_uuid, prepare_marking_identity,
    scan_save,
};
pub use snapshot::{FileState, SESSION_LOCK, SaveSnapshot, SaveSourceLock};
pub use sync_chunks::{
    SYNC_CHUNKS_MAX_ENTRIES, SYNC_CHUNKS_RELATIVE_PATH, SaveSyncChunk, canonical_dimension_id,
    dimension_storage_folder, parse_sync_chunks,
};
pub use text::{SavePathRenamer, TextPatchResult, TextUuidPatcher, is_strict_utf8};
