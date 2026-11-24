//! Copies a save into an empty directory while replacing mapped player UUIDs.

use std::borrow::Cow;
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{LazyLock, Mutex};

use regex::Regex;
use uuid::Uuid;

use crate::compress;
use crate::error::{IoContext, RemapError, Result};
use crate::fsutil;
use crate::limits::{
    CHUNK_DECOMPRESSED_BYTES, OTHER_NBT_FILE_BYTES, STRICT_NBT_FILE_BYTES, TEXT_FILE_BYTES,
    default_region_parallelism,
};
use crate::patcher::NbtBytePatcher;
use crate::reader::NbtDocument;
use crate::region::{self, RegionRewriteResult, RewriteOptions};
use crate::snapshot::SaveSnapshot;
use crate::sync_chunks::SaveSyncChunk;
use crate::text::{RawUuidScanner, SavePathRenamer, TextUuidPatcher, is_strict_utf8};
use crate::uuid_matcher::UuidMatcher;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RemapProgress {
    pub bytes_done: u64,
    pub bytes_total: u64,
}

/// A file copied unchanged although it may hold player data that was not migrated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UnhandledFile {
    pub relative_path: String,
    pub reason: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SyncFilterReport {
    /// Chunks in the sync list.
    pub marked_chunk_count: usize,
    /// Marked chunks that exist in the `region/` stores of the save.
    pub present_chunk_count: usize,
    /// Region, entity and POI files skipped or emptied because they hold no marked chunk.
    pub dropped_file_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemapReport {
    pub files_scanned: usize,
    pub files_rewritten: usize,
    pub paths_renamed: usize,
    pub replacements_by_source: HashMap<Uuid, usize>,
    pub possibly_unmigrated: Vec<UnhandledFile>,
    /// The save was hardcore; the flag is now off in `level.dat` and `level.dat_old`.
    pub hardcore_switched_off: bool,
    /// The singleplayer owner's playerdata was written from `Data.Player`.
    pub host_data_from_level_dat: bool,
    /// `None` when no sync chunk list was given.
    pub sync_filter: Option<SyncFilterReport>,
    pub bytes_before: u64,
    pub bytes_after: u64,
}

const REASON_TOO_LARGE: &str = "文件过大，未检查";
const REASON_UNKNOWN_FORMAT: &str = "未识别的文件格式";
const REASON_NOT_UTF8: &str = "非UTF-8文本，未自动迁移";
const REASON_MOST_LEAST: &str = "含有无法自动迁移的玩家ID写法";

static STRICT_NBT: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^(?:level\.dat(?:_old)?|playerdata/[^/]+\.dat(?:_old)?)$").expect("valid regex")
});
static MCC_NAME: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^c\.-?[0-9]+\.-?[0-9]+\.mcc$").expect("valid regex"));
const TEXT_EXTENSIONS: &[&str] = &[
    "snbt",
    "json",
    "json5",
    "toml",
    "cfg",
    "txt",
    "properties",
    "save",
    "yml",
    "yaml",
];
const CHUNK_STORES: &[&str] = &["region", "entities", "poi"];

fn pack_chunk(x: i32, z: i32) -> u64 {
    ((x as u32 as u64) << 32) | z as u32 as u64
}

/// The canonical dimension of a file directly inside a chunk store, or `None`: `region/…` is the
/// overworld, `DIM-1/…` the nether, `DIM1/…` the end, `dimensions/<ns>/<path>/…` other dimensions.
pub fn chunk_store_dimension(relative_path: &str) -> Option<String> {
    let segments: Vec<&str> = relative_path.split('/').collect();
    if segments.len() < 2 || !CHUNK_STORES.contains(&segments[segments.len() - 2]) {
        return None;
    }
    let folder = &segments[..segments.len() - 2];
    match folder {
        [] => Some("minecraft:overworld".into()),
        ["DIM-1"] => Some("minecraft:the_nether".into()),
        ["DIM1"] => Some("minecraft:the_end".into()),
        ["dimensions", namespace, path @ ..] if !path.is_empty() => {
            Some(format!("{namespace}:{}", path.join("/")))
        }
        _ => None,
    }
}

/// Copies a save into an empty directory while replacing mapped player UUIDs.
///
/// The source is only read, through [`SaveSnapshot`], and is verified again at the end. Every
/// replacement preserves length except in text int arrays, so all other bytes stay as they were.
///
/// - Region files are rewritten strictly by [`region::rewrite`], in parallel within a memory budget.
/// - `level.dat`, `level.dat_old` and playerdata are strict gzip NBT: any problem fails the import.
/// - Other NBT and strictly valid UTF-8 text are patched when they can be read; otherwise they are
///   copied unchanged, and listed in [`RemapReport::possibly_unmigrated`] when a mapped UUID was
///   seen or the file could not be checked.
/// - Path segments that are a mapped UUID are renamed. Two outputs with the same path fail.
/// - The singleplayer owner's playerdata is written from `Data.Player`.
/// - Hardcore is switched off.
/// - With sync chunks, only marked chunks of each dimension's `region/`, `entities/` and `poi/`
///   stores are kept; other chunks are never read and will regenerate.
///
/// On failure or cancellation, everything written below the target is removed.
pub struct SaveUuidRemapper {
    mapping: HashMap<Uuid, Uuid>,
    sync_chunks: Option<Vec<SaveSyncChunk>>,
    chunk_limit: usize,
    parallelism: usize,
    matcher: UuidMatcher,
    patcher: NbtBytePatcher,
    text_patcher: TextUuidPatcher,
    renamer: SavePathRenamer,
    kept_by_dimension: Option<HashMap<String, HashSet<u64>>>,
}

impl SaveUuidRemapper {
    pub fn new(
        mapping: HashMap<Uuid, Uuid>,
        sync_chunks: Option<Vec<SaveSyncChunk>>,
    ) -> Result<Self> {
        Self::with_limits(
            mapping,
            sync_chunks,
            CHUNK_DECOMPRESSED_BYTES,
            default_region_parallelism(),
        )
    }

    pub fn with_limits(
        mapping: HashMap<Uuid, Uuid>,
        sync_chunks: Option<Vec<SaveSyncChunk>>,
        chunk_limit: usize,
        parallelism: usize,
    ) -> Result<Self> {
        let kept_by_dimension = sync_chunks.as_ref().map(|chunks| {
            let mut kept: HashMap<String, HashSet<u64>> = HashMap::new();
            for chunk in chunks {
                kept.entry(chunk.dimension_id.clone())
                    .or_default()
                    .insert(pack_chunk(chunk.chunk_x, chunk.chunk_z));
            }
            kept
        });
        Ok(SaveUuidRemapper {
            matcher: UuidMatcher::new(&mapping)?,
            patcher: NbtBytePatcher::new(&mapping)?,
            text_patcher: TextUuidPatcher::new(&mapping)?,
            renamer: SavePathRenamer::new(&mapping)?,
            mapping,
            sync_chunks,
            chunk_limit,
            parallelism: parallelism.max(1),
            kept_by_dimension,
        })
    }

    /// Copies `source` (described by `snapshot`) into `target`, which must be empty or missing.
    /// `progress` may be called from several threads; `ensure_active` stops the work when it fails.
    pub fn remap_tree(
        &self,
        source: &Path,
        target: &Path,
        snapshot: &SaveSnapshot,
        progress: &(dyn Fn(RemapProgress) + Sync),
        ensure_active: &(dyn Fn() -> Result<()> + Sync),
    ) -> Result<RemapReport> {
        let run = Run {
            remapper: self,
            source,
            target,
            snapshot,
            progress: Mutex::new(progress),
            ensure_active,
            replacements: Mutex::new(HashMap::new()),
            unhandled: Mutex::new(Vec::new()),
            bytes_done: AtomicU64::new(0),
            files_rewritten: AtomicUsize::new(0),
            hardcore_switched_off: AtomicBool::new(false),
            writing: AtomicBool::new(false),
        };
        let result = run.execute();
        // Only clean what this call wrote: a target that was not empty is left alone.
        if result.is_err() && run.writing.load(Ordering::Relaxed) {
            clean_target(target);
        }
        result
    }
}

/// Removes everything below `target`, keeping `target` itself. Failures are logged to stderr,
/// because the caller is already reporting the original error.
fn clean_target(target: &Path) {
    let Ok(entries) = fs::read_dir(target) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let removed = match fs::symlink_metadata(&path) {
            Ok(metadata) if metadata.is_dir() => fs::remove_dir_all(&path),
            Ok(_) => fs::remove_file(&path),
            Err(error) => Err(error),
        };
        if let Err(error) = removed {
            eprintln!("rdi-anvilrw: failed to clean {}: {error}", path.display());
        }
    }
}

/// State of one `remap_tree` call.
struct Run<'a> {
    remapper: &'a SaveUuidRemapper,
    source: &'a Path,
    target: &'a Path,
    snapshot: &'a SaveSnapshot,
    progress: Mutex<&'a (dyn Fn(RemapProgress) + Sync)>,
    ensure_active: &'a (dyn Fn() -> Result<()> + Sync),
    replacements: Mutex<HashMap<Uuid, usize>>,
    unhandled: Mutex<Vec<UnhandledFile>>,
    bytes_done: AtomicU64,
    files_rewritten: AtomicUsize,
    hardcore_switched_off: AtomicBool,
    /// Set once the target is known to be empty and this call starts writing into it.
    writing: AtomicBool,
}

impl Run<'_> {
    fn execute(&self) -> Result<RemapReport> {
        if self.snapshot.root() != self.source {
            return Err(RemapError::Invalid(format!(
                "The snapshot was taken of {}, not {}",
                self.snapshot.root().display(),
                self.source.display()
            )));
        }
        fs::create_dir_all(self.target).ctx(|| self.target.display().to_string())?;
        if fs::read_dir(self.target)
            .ctx(|| self.target.display().to_string())?
            .next()
            .is_some()
        {
            return Err(RemapError::Invalid(format!(
                "{} is not empty",
                self.target.display()
            )));
        }
        self.writing.store(true, Ordering::Relaxed);

        let outputs = self.plan_outputs()?;
        for directory in self.snapshot.directories() {
            let path = fsutil::resolve(self.target, &self.remapper.renamer.rename(directory));
            fs::create_dir_all(&path).ctx(|| path.display().to_string())?;
        }

        let region_files: Vec<&String> = self
            .snapshot
            .files()
            .keys()
            .filter(|path| path.ends_with(".mca"))
            .collect();
        let (owned_externals, region_stats) = self.rewrite_regions(&region_files, &outputs)?;
        for (path, state) in self.snapshot.files() {
            (self.ensure_active)()?;
            if path.ends_with(".mca") {
                continue;
            }
            if !owned_externals.contains(path) && !self.is_chunk_store_external(path) {
                self.process_file(path, &fsutil::resolve(self.target, &outputs[path]))?;
            }
            self.advance(state.size);
        }
        let host_data = self.write_host_player_data()?;
        self.snapshot.verify()?;

        let mut unhandled = std::mem::take(&mut *self.unhandled.lock().expect("not poisoned"));
        unhandled.sort_by(|a, b| a.relative_path.cmp(&b.relative_path));
        Ok(RemapReport {
            files_scanned: self.snapshot.files().len(),
            files_rewritten: self.files_rewritten.load(Ordering::Relaxed),
            paths_renamed: outputs.iter().filter(|(from, to)| from != to).count(),
            replacements_by_source: std::mem::take(
                &mut *self.replacements.lock().expect("not poisoned"),
            ),
            possibly_unmigrated: unhandled,
            hardcore_switched_off: self.hardcore_switched_off.load(Ordering::Relaxed),
            host_data_from_level_dat: host_data,
            sync_filter: self
                .remapper
                .sync_chunks
                .as_ref()
                .map(|chunks| SyncFilterReport {
                    marked_chunk_count: chunks.len(),
                    present_chunk_count: region_stats.present_chunks,
                    dropped_file_count: region_stats.dropped_files,
                }),
            bytes_before: self.snapshot.total_bytes(),
            bytes_after: directory_size(self.target)?,
        })
    }

    /// Output path of every source file; two sources may not share one (case-insensitively).
    fn plan_outputs(&self) -> Result<BTreeMap<String, String>> {
        let outputs: BTreeMap<String, String> = self
            .snapshot
            .files()
            .keys()
            .map(|path| (path.clone(), self.remapper.renamer.rename(path)))
            .collect();
        let mut seen: HashMap<String, &String> = HashMap::new();
        for (from, to) in &outputs {
            if let Some(other) = seen.get(&to.to_lowercase()) {
                return Err(RemapError::PathConflict(format!(
                    "{other} and {from} would both be written to {to}"
                )));
            }
            seen.insert(to.to_lowercase(), from);
        }
        Ok(outputs)
    }

    /// Rewrites every region file; returns the `.mcc` files the region handler took care of.
    fn rewrite_regions(
        &self,
        region_files: &[&String],
        outputs: &BTreeMap<String, String>,
    ) -> Result<(HashSet<String>, RegionStats)> {
        let mut stats = RegionStats::default();
        let mut tasks: Vec<(&String, Option<&HashSet<u64>>)> = Vec::new();
        let empty = HashSet::new();
        for &path in region_files {
            let kept = chunk_store_dimension(path).and_then(|dimension| {
                self.remapper
                    .kept_by_dimension
                    .as_ref()
                    .map(|kept| kept.get(&dimension).unwrap_or(&empty))
            });
            if kept.is_some_and(HashSet::is_empty) {
                stats.dropped_files += 1;
                self.advance(self.snapshot.files()[path].size);
                continue;
            }
            tasks.push((path, kept));
        }

        let next = AtomicUsize::new(0);
        let failed = AtomicBool::new(false);
        let results: Vec<Mutex<Option<Result<RegionRewriteResult>>>> =
            tasks.iter().map(|_| Mutex::new(None)).collect();
        std::thread::scope(|scope| {
            for _ in 0..self.remapper.parallelism.min(tasks.len()) {
                std::thread::Builder::new()
                    .stack_size(8 * 1024 * 1024)
                    .spawn_scoped(scope, || {
                        loop {
                            if failed.load(Ordering::Relaxed) {
                                return;
                            }
                            let index = next.fetch_add(1, Ordering::Relaxed);
                            let Some(&(path, kept)) = tasks.get(index) else {
                                return;
                            };
                            let result = self.rewrite_region(path, kept, &outputs[path]);
                            if result.is_err() {
                                failed.store(true, Ordering::Relaxed);
                            }
                            *results[index].lock().expect("not poisoned") = Some(result);
                        }
                    })
                    .expect("spawning a region worker");
            }
        });

        let mut owned = HashSet::new();
        for ((path, _), result) in tasks.iter().zip(results) {
            // Tasks after a failure may not have run; the first failure in order is reported.
            let Some(result) = result.into_inner().expect("not poisoned") else {
                continue;
            };
            let result = result?;
            let directory = path.rsplit_once('/').map(|(dir, _)| dir).unwrap_or("");
            for name in &result.external_chunk_files {
                owned.insert(if directory.is_empty() {
                    name.clone()
                } else {
                    format!("{directory}/{name}")
                });
            }
            if !result.copied_unchanged && result.written {
                self.files_rewritten.fetch_add(1, Ordering::Relaxed);
            }
            if !result.written {
                stats.dropped_files += 1;
            }
            if chunk_store_dimension(path).is_some()
                && directory.rsplit('/').next() == Some("region")
            {
                stats.present_chunks += result.chunk_count;
            }
        }
        if failed.load(Ordering::Relaxed) {
            return Err(RemapError::Invalid(
                "a region rewrite failed without a result".into(),
            ));
        }
        Ok((owned, stats))
    }

    fn rewrite_region(
        &self,
        path: &str,
        kept: Option<&HashSet<u64>>,
        output: &str,
    ) -> Result<RegionRewriteResult> {
        (self.ensure_active)()?;
        self.snapshot.check_unchanged(path)?;
        let filter = kept.map(|set| move |x: i32, z: i32| set.contains(&pack_chunk(x, z)));
        let transform = |_x: i32, _z: i32, nbt: &[u8]| -> Result<Option<Vec<u8>>> {
            let result = self.remapper.patcher.patch(nbt)?;
            self.count(&result.replacements);
            Ok(result.patched)
        };
        let options = RewriteOptions {
            chunk_limit: self.remapper.chunk_limit,
            keep: filter.as_ref().map(|f| f as &region::ChunkFilter<'_>),
            ensure_active: self.ensure_active,
            ..RewriteOptions::default()
        };
        let result = region::rewrite(
            &fsutil::resolve(self.source, path),
            &fsutil::resolve(self.target, output),
            &transform,
            &options,
        )?;
        self.advance(self.snapshot.files()[path].size);
        Ok(result)
    }

    /// `.mcc` files in chunk stores are written by the region handler for kept chunks only.
    fn is_chunk_store_external(&self, path: &str) -> bool {
        self.remapper.kept_by_dimension.is_some()
            && MCC_NAME.is_match(path.rsplit('/').next().unwrap_or(path))
            && chunk_store_dimension(path).is_some()
    }

    fn process_file(&self, path: &str, output: &Path) -> Result<()> {
        let size = self.snapshot.files()[path].size;
        if STRICT_NBT.is_match(path) {
            self.process_strict_nbt(path, output, size)
        } else if size > OTHER_NBT_FILE_BYTES as u64 {
            self.copy_unchecked(path, output, REASON_TOO_LARGE)
        } else {
            self.process_other(path, output)
        }
    }

    fn process_strict_nbt(&self, path: &str, output: &Path, size: u64) -> Result<()> {
        if size > STRICT_NBT_FILE_BYTES as u64 {
            return Err(RemapError::LimitExceeded(format!(
                "{path} exceeds {STRICT_NBT_FILE_BYTES} bytes"
            )));
        }
        let original = self.snapshot.read_file(path)?;
        let nbt = compress::gunzip(&original, STRICT_NBT_FILE_BYTES, path)?;
        let patched = self.remapper.patcher.patch(&nbt)?;
        self.count(&patched.replacements);
        let mut bytes = patched.patched;
        if path == "level.dat" || path == "level.dat_old" {
            let metadata = NbtDocument::open(nbt.clone())?.level_metadata()?;
            if let (true, Some(offset)) = (metadata.hardcore, metadata.hardcore_byte_offset) {
                bytes.get_or_insert_with(|| nbt.clone())[offset] = 0;
                self.hardcore_switched_off.store(true, Ordering::Relaxed);
            }
        }
        match bytes {
            Some(changed) => self.write(output, &compress::gzip(&changed), true),
            None => self.write(output, &original, false),
        }
    }

    fn process_other(&self, path: &str, output: &Path) -> Result<()> {
        let original = self.snapshot.read_file(path)?;
        if original.len() >= 2 && original[0] == 0x1F && original[1] == 0x8B {
            let content = match compress::gunzip(&original, OTHER_NBT_FILE_BYTES, path) {
                Ok(content) => content,
                Err(RemapError::LimitExceeded(_)) => {
                    return self.copy_with_reason(path, output, &original, REASON_TOO_LARGE);
                }
                Err(RemapError::Io { .. }) => {
                    return self.copy_scanned(
                        path,
                        output,
                        &original,
                        &original,
                        REASON_UNKNOWN_FORMAT,
                    );
                }
                Err(other) => return Err(other),
            };
            return match self.rewrite_content(path, &content)? {
                None => self.copy_scanned(path, output, &original, &content, REASON_UNKNOWN_FORMAT),
                Some(Cow::Owned(rewritten)) => {
                    self.write(output, &compress::gzip(&rewritten), true)
                }
                Some(Cow::Borrowed(_)) => self.write(output, &original, false),
            };
        }
        match self.rewrite_content(path, &original)? {
            Some(Cow::Owned(rewritten)) => self.write(output, &rewritten, true),
            Some(Cow::Borrowed(_)) => self.write(output, &original, false),
            None => {
                let extension = path
                    .rsplit_once('.')
                    .map(|(_, ext)| ext.to_lowercase())
                    .unwrap_or_default();
                let reason = if TEXT_EXTENSIONS.contains(&extension.as_str()) {
                    REASON_NOT_UTF8
                } else {
                    REASON_UNKNOWN_FORMAT
                };
                self.copy_scanned(path, output, &original, &original, reason)
            }
        }
    }

    /// Patches structurally valid NBT or strictly valid UTF-8 text. Returns the content itself when
    /// nothing changed, or `None` when the content is neither.
    fn rewrite_content<'c>(&self, path: &str, content: &'c [u8]) -> Result<Option<Cow<'c, [u8]>>> {
        if content.first() == Some(&0x0A) {
            // A failure only means the content is not NBT; the other formats are tried next.
            if let Ok(patched) = self.remapper.patcher.patch(content) {
                self.count(&patched.replacements);
                return Ok(Some(
                    patched
                        .patched
                        .map(Cow::Owned)
                        .unwrap_or(Cow::Borrowed(content)),
                ));
            }
        }
        if !is_strict_utf8(content) {
            return Ok(None);
        }
        if content.len() > TEXT_FILE_BYTES {
            self.unhandled(path, REASON_TOO_LARGE);
            return Ok(Some(Cow::Borrowed(content)));
        }
        let patched = self.remapper.text_patcher.patch(content)?;
        self.count(&patched.replacements);
        if patched.most_least_hits > 0 {
            self.unhandled(path, REASON_MOST_LEAST);
        }
        Ok(Some(
            patched
                .patched
                .map(Cow::Owned)
                .unwrap_or(Cow::Borrowed(content)),
        ))
    }

    fn copy_scanned(
        &self,
        path: &str,
        output: &Path,
        original: &[u8],
        content: &[u8],
        reason: &str,
    ) -> Result<()> {
        if RawUuidScanner::new(&self.remapper.matcher).scan(content) {
            self.unhandled(path, reason);
        }
        fs::write(output, original).ctx(|| output.display().to_string())
    }

    fn copy_with_reason(
        &self,
        path: &str,
        output: &Path,
        original: &[u8],
        reason: &str,
    ) -> Result<()> {
        self.unhandled(path, reason);
        fs::write(output, original).ctx(|| output.display().to_string())
    }

    fn copy_unchecked(&self, path: &str, output: &Path, reason: &str) -> Result<()> {
        self.unhandled(path, reason);
        self.snapshot.copy_file(path, output, &mut |_| {})
    }

    fn write(&self, output: &Path, bytes: &[u8], changed: bool) -> Result<()> {
        fs::write(output, bytes).ctx(|| output.display().to_string())?;
        if changed {
            self.files_rewritten.fetch_add(1, Ordering::Relaxed);
        }
        Ok(())
    }

    /// Minecraft loads the singleplayer owner from `Data.Player`, so that copy overwrites the
    /// owner's playerdata file, under the mapped UUID if the owner is mapped.
    fn write_host_player_data(&self) -> Result<bool> {
        if !self.snapshot.files().contains_key("level.dat") {
            return Ok(false);
        }
        let level = compress::gunzip(
            &self.snapshot.read_file("level.dat")?,
            STRICT_NBT_FILE_BYTES,
            "level.dat",
        )?;
        let document = NbtDocument::open(level)?;
        let Some(host) = document.level_metadata()?.singleplayer_uuid else {
            return Ok(false);
        };
        let Some(payload) = document.compound_payload(&["Data", "Player"]) else {
            return Ok(false);
        };
        let player = NbtDocument::root_document(&payload);
        let patched = self.remapper.patcher.patch(&player)?;
        let directory = self.target.join("playerdata");
        fs::create_dir_all(&directory).ctx(|| directory.display().to_string())?;
        let output = directory.join(format!(
            "{}.dat",
            self.remapper.mapping.get(&host).unwrap_or(&host)
        ));
        fs::write(&output, compress::gzip(patched.bytes(&player)))
            .ctx(|| output.display().to_string())?;
        Ok(true)
    }

    fn count(&self, found: &HashMap<Uuid, usize>) {
        if found.is_empty() {
            return;
        }
        let mut replacements = self.replacements.lock().expect("not poisoned");
        for (uuid, n) in found {
            *replacements.entry(*uuid).or_insert(0) += n;
        }
    }

    fn unhandled(&self, path: &str, reason: &str) {
        self.unhandled
            .lock()
            .expect("not poisoned")
            .push(UnhandledFile {
                relative_path: path.to_string(),
                reason: reason.to_string(),
            });
    }

    fn advance(&self, bytes: u64) {
        let done = self.bytes_done.fetch_add(bytes, Ordering::Relaxed) + bytes;
        let progress = self.progress.lock().expect("not poisoned");
        progress(RemapProgress {
            bytes_done: done,
            bytes_total: self.snapshot.total_bytes(),
        });
    }
}

#[derive(Default)]
struct RegionStats {
    present_chunks: usize,
    dropped_files: usize,
}

fn directory_size(path: &Path) -> Result<u64> {
    let mut total = 0;
    let mut pending: Vec<PathBuf> = vec![path.to_path_buf()];
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(&directory).ctx(|| directory.display().to_string())? {
            let entry = entry.ctx(|| directory.display().to_string())?;
            let metadata =
                fs::symlink_metadata(entry.path()).ctx(|| entry.path().display().to_string())?;
            if metadata.is_dir() {
                pending.push(entry.path());
            } else if metadata.is_file() {
                total += metadata.len();
            }
        }
    }
    Ok(total)
}
