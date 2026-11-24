//! Strict, payload-level reading and writing of Anvil region files (`r.<x>.<z>.mca`).

use std::collections::{BTreeMap, HashMap};
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

use regex::Regex;

use crate::compress::{self, chunk_compression};
use crate::error::{IoContext, RemapError, Result, never_cancelled};
use crate::fsutil;
use crate::limits::{CHUNK_DECOMPRESSED_BYTES, MCC_COMPRESSED_BYTES};
use crate::nbt::s4;

const SECTOR: usize = 4096;
const HEADER: usize = SECTOR * 2;
const CHUNKS: usize = 1024;
const EXTERNAL_FLAG: u8 = 0x80;
/// Minecraft stores a chunk externally from 256 sectors (1 MiB) on.
pub const EXTERNAL_THRESHOLD_SECTORS: usize = 256;

static REGION_NAME: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^r\.(-?[0-9]+)\.(-?[0-9]+)\.mca$").expect("valid regex"));

/// Rewrites the NBT of one chunk: `Ok(None)` keeps it unchanged. It is called for every kept
/// chunk, so it must validate the NBT structurally even when it changes nothing.
pub type ChunkTransform<'a> = dyn Fn(i32, i32, &[u8]) -> Result<Option<Vec<u8>>> + Sync + 'a;

/// Decides from the region header alone whether a chunk is kept. Dropped chunks are never read.
pub type ChunkFilter<'a> = dyn Fn(i32, i32) -> bool + Sync + 'a;

pub struct RewriteOptions<'a> {
    pub chunk_limit: usize,
    pub mcc_limit: u64,
    /// Minecraft's 256 in production; tests lower it.
    pub external_threshold_sectors: usize,
    /// Without a filter every chunk is kept.
    pub keep: Option<&'a ChunkFilter<'a>>,
    /// Called between chunks; returning an error stops the rewrite.
    pub ensure_active: &'a (dyn Fn() -> Result<()> + Sync),
}

impl Default for RewriteOptions<'_> {
    fn default() -> Self {
        RewriteOptions {
            chunk_limit: CHUNK_DECOMPRESSED_BYTES,
            mcc_limit: MCC_COMPRESSED_BYTES,
            external_threshold_sectors: EXTERNAL_THRESHOLD_SECTORS,
            keep: None,
            ensure_active: &never_cancelled,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RegionRewriteResult {
    /// Kept chunks present in the source. Without a filter, every chunk present.
    pub chunk_count: usize,
    pub changed_chunk_count: usize,
    /// True when the region and its external chunk files were copied byte-for-byte.
    pub copied_unchanged: bool,
    /// Names of the `c.<x>.<z>.mcc` files of kept chunks, written next to the target.
    pub external_chunk_files: Vec<String>,
    /// Chunks present in the source that the filter dropped.
    pub dropped_chunk_count: usize,
    /// False when no kept chunk is present; then nothing is written to the target.
    pub written: bool,
}

impl RegionRewriteResult {
    fn not_written(dropped: usize) -> Self {
        RegionRewriteResult {
            chunk_count: 0,
            changed_chunk_count: 0,
            copied_unchanged: false,
            external_chunk_files: Vec::new(),
            dropped_chunk_count: dropped,
            written: false,
        }
    }
}

struct Entry {
    index: usize,
    chunk_x: i32,
    chunk_z: i32,
    sector_offset: usize,
    sector_count: usize,
    timestamp: i32,
    external: bool,
    type_byte: u8,
    /// Length of the inline record (length field + type byte + data) in bytes.
    inline_record_length: usize,
}

impl Entry {
    fn mcc_name(&self) -> String {
        format!("c.{}.{}.mcc", self.chunk_x, self.chunk_z)
    }
}

/// Where a changed chunk's recompressed payload sits in the spill file.
struct Spilled {
    offset: u64,
    length: usize,
}

fn region_coordinates(source: &Path) -> Result<(i32, i32)> {
    let name = source
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let invalid = || RemapError::region(format!("Not a region file name: {name}"));
    let captures = REGION_NAME.captures(&name).ok_or_else(invalid)?;
    let x = captures[1].parse::<i32>().map_err(|_| invalid())?;
    let z = captures[2].parse::<i32>().map_err(|_| invalid())?;
    Ok((x, z))
}

fn read_exactly(file: &mut File, position: u64, length: usize, path: &Path) -> Result<Vec<u8>> {
    let mut buffer = vec![0; length];
    file.seek(SeekFrom::Start(position))
        .ctx(|| path.display().to_string())?;
    file.read_exact(&mut buffer).map_err(|error| {
        if error.kind() == std::io::ErrorKind::UnexpectedEof {
            RemapError::region(format!(
                "{}: unexpected end of file at {position}",
                path.display()
            ))
        } else {
            RemapError::io(path.display().to_string(), error)
        }
    })?;
    Ok(buffer)
}

fn sibling(path: &Path, name: &str) -> PathBuf {
    path.with_file_name(name)
}

fn copy_replacing(source: &Path, target: &Path) -> Result<()> {
    fs::copy(source, target)
        .ctx(|| format!("copy {} to {}", source.display(), target.display()))?;
    Ok(())
}

fn temporary_in(target: &Path, suffix: &str) -> Result<tempfile::NamedTempFile> {
    let directory = target.parent().unwrap_or(Path::new("."));
    let name = target
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    tempfile::Builder::new()
        .prefix(&format!(".{name}."))
        .suffix(suffix)
        .tempfile_in(directory)
        .ctx(|| format!("temporary file in {}", directory.display()))
}

fn write_replacing(target: &Path, bytes: &[u8]) -> Result<()> {
    let mut temporary = temporary_in(target, ".tmp")?;
    temporary
        .write_all(bytes)
        .ctx(|| target.display().to_string())?;
    temporary
        .persist(target)
        .map_err(|error| RemapError::io(target.display().to_string(), error.error))?;
    Ok(())
}

fn sectors_for(bytes: usize) -> usize {
    bytes.div_ceil(SECTOR)
}

/// Reads `source` strictly, passes every kept chunk through `transform`, and writes the result to
/// `target`. External chunk files are written next to `target`. If no kept chunk is present, nothing
/// is written.
///
/// Unlike Minecraft's own reader, nothing is ever replaced with an empty chunk: an invalid location
/// entry, a bad length, an unsupported or unknown compression, a decompression failure, a
/// decompressed size over the limit, a missing external chunk file, or a failing transform all fail
/// the rewrite. Overlapping sectors and trailing partial sectors are tolerated, as Minecraft
/// tolerates them. Unchanged chunks keep their original compressed bytes; changed chunks keep their
/// index, timestamp and compression type. If no chunk changes, the files are copied unchanged.
///
/// With a filter, strictness applies to kept chunks only: a dropped chunk is never read, not even
/// its location entry, and its `.mcc` file is not copied. The header itself must still be complete.
pub fn rewrite(
    source: &Path,
    target: &Path,
    transform: &ChunkTransform<'_>,
    options: &RewriteOptions<'_>,
) -> Result<RegionRewriteResult> {
    let (region_x, region_z) = region_coordinates(source)?;
    let file_size = fs::metadata(source)
        .ctx(|| source.display().to_string())?
        .len();
    if file_size == 0 {
        if options.keep.is_some() {
            return Ok(RegionRewriteResult::not_written(0));
        }
        copy_replacing(source, target)?;
        return Ok(RegionRewriteResult {
            chunk_count: 0,
            changed_chunk_count: 0,
            copied_unchanged: true,
            external_chunk_files: Vec::new(),
            dropped_chunk_count: 0,
            written: true,
        });
    }
    if file_size < HEADER as u64 {
        return Err(RemapError::region(format!(
            "{}: header is truncated ({file_size} bytes)",
            source.display()
        )));
    }

    let mut file = File::open(source).ctx(|| source.display().to_string())?;
    let header = read_exactly(&mut file, 0, HEADER, source)?;
    let (mut entries, dropped) =
        parse_header(&header, file_size, source, region_x, region_z, options.keep)?;
    if entries.is_empty() && options.keep.is_some() {
        return Ok(RegionRewriteResult::not_written(dropped));
    }
    let mut spill = temporary_in(target, ".changed")?;
    let changed = transform_chunks(
        &mut file,
        file_size,
        &mut entries,
        source,
        transform,
        options,
        spill.as_file_mut(),
    )?;
    let externals: Vec<String> = entries
        .iter()
        .filter(|e| e.external)
        .map(Entry::mcc_name)
        .collect();
    if changed.is_empty() && dropped == 0 {
        copy_replacing(source, target)?;
        for name in &externals {
            copy_replacing(&sibling(source, name), &sibling(target, name))?;
        }
        return Ok(RegionRewriteResult {
            chunk_count: entries.len(),
            changed_chunk_count: 0,
            copied_unchanged: true,
            external_chunk_files: externals,
            dropped_chunk_count: 0,
            written: true,
        });
    }
    write_region(
        &mut file,
        &entries,
        &changed,
        spill.as_file_mut(),
        source,
        target,
        options.external_threshold_sectors,
    )?;
    Ok(RegionRewriteResult {
        chunk_count: entries.len(),
        changed_chunk_count: changed.len(),
        copied_unchanged: false,
        external_chunk_files: externals,
        dropped_chunk_count: dropped,
        written: true,
    })
}

/// The chunks a region file contains, from its header alone. Nothing else is read or validated, so
/// this only tells whether a chunk was ever generated.
pub fn present_chunks(source: &Path) -> Result<Vec<(i32, i32)>> {
    let (region_x, region_z) = region_coordinates(source)?;
    if fs::metadata(source)
        .ctx(|| source.display().to_string())?
        .len()
        < HEADER as u64
    {
        return Ok(Vec::new());
    }
    let header = read_exactly(&mut fsutil::open_nofollow(source)?, 0, SECTOR, source)?;
    Ok((0..CHUNKS)
        .filter(|&i| s4(&header, i * 4) != 0)
        .map(|i| {
            (
                region_x * 32 + (i & 31) as i32,
                region_z * 32 + (i >> 5) as i32,
            )
        })
        .collect())
}

/// The compression type of every chunk in a region file (the low 7 bits of each chunk's type
/// byte), read from the header and the 5-byte chunk headers only. Entries that point outside the
/// file are skipped; Minecraft treats such chunks as missing too.
pub fn compression_types(source: &Path) -> Result<BTreeMap<u8, usize>> {
    let mut counts = BTreeMap::new();
    let mut file = fsutil::open_nofollow(source)?;
    let size = file.metadata().ctx(|| source.display().to_string())?.len();
    if size < HEADER as u64 {
        return Ok(counts);
    }
    let header = read_exactly(&mut file, 0, SECTOR, source)?;
    for i in 0..CHUNKS {
        let location = s4(&header, i * 4) as u32;
        if location == 0 {
            continue;
        }
        let sector = (location >> 8) as u64;
        let start = sector * SECTOR as u64;
        if sector < 2 || start + 5 > size {
            continue;
        }
        let kind = read_exactly(&mut file, start + 4, 1, source)?[0] & 0x7f;
        *counts.entry(kind).or_insert(0) += 1;
    }
    Ok(counts)
}

/// Returns the kept entries, validated, and the number of dropped ones, which are not validated.
fn parse_header(
    header: &[u8],
    file_size: u64,
    source: &Path,
    region_x: i32,
    region_z: i32,
    keep: Option<&ChunkFilter<'_>>,
) -> Result<(Vec<Entry>, usize)> {
    let mut entries = Vec::new();
    let mut dropped = 0;
    for i in 0..CHUNKS {
        let location = s4(header, i * 4) as u32;
        if location == 0 {
            continue;
        }
        let chunk_x = region_x * 32 + (i & 31) as i32;
        let chunk_z = region_z * 32 + (i >> 5) as i32;
        if keep.is_some_and(|keep| !keep(chunk_x, chunk_z)) {
            dropped += 1;
            continue;
        }
        let offset = (location >> 8) as usize;
        let count = (location & 0xff) as usize;
        if offset < 2 || count == 0 {
            return Err(RemapError::region(format!(
                "{}: chunk ({chunk_x},{chunk_z}) has an invalid location (sector {offset}, count {count})",
                source.display()
            )));
        }
        if offset as u64 * SECTOR as u64 >= file_size {
            return Err(RemapError::region(format!(
                "{}: chunk ({chunk_x},{chunk_z}) starts past the end of the file",
                source.display()
            )));
        }
        entries.push(Entry {
            index: i,
            chunk_x,
            chunk_z,
            sector_offset: offset,
            sector_count: count,
            timestamp: s4(header, SECTOR + i * 4),
            external: false,
            type_byte: 0,
            inline_record_length: 0,
        });
    }
    Ok((entries, dropped))
}

fn transform_chunks(
    file: &mut File,
    file_size: u64,
    entries: &mut [Entry],
    source: &Path,
    transform: &ChunkTransform<'_>,
    options: &RewriteOptions<'_>,
    spill: &mut File,
) -> Result<HashMap<usize, Spilled>> {
    let mut changed = HashMap::new();
    let mut spill_position = 0u64;
    for entry in entries.iter_mut() {
        (options.ensure_active)()?;
        let place = format!(
            "{}: chunk ({},{})",
            source.display(),
            entry.chunk_x,
            entry.chunk_z
        );
        let compressed =
            read_chunk_payload(file, file_size, entry, source, options.mcc_limit, &place)?;
        let kind = entry.type_byte & 0x7f;
        check_supported(kind, &place)?;
        let nbt = compress::decompress_chunk(kind, &compressed, options.chunk_limit, &place)
            .map_err(|error| RemapError::RegionFormat {
                message: format!("{place}: cannot decompress: {error}"),
                cause: Some(Box::new(error)),
            })?;
        drop(compressed);
        let rewritten =
            transform(entry.chunk_x, entry.chunk_z, &nbt).map_err(|error| match error {
                RemapError::Cancelled => error,
                other => RemapError::RegionFormat {
                    message: format!("{place}: {other}"),
                    cause: Some(Box::new(other)),
                },
            })?;
        if let Some(rewritten) = rewritten {
            let recompressed = compress::compress_chunk(kind, &rewritten)?;
            spill
                .write_all(&recompressed)
                .ctx(|| "region spill file".to_string())?;
            changed.insert(
                entry.index,
                Spilled {
                    offset: spill_position,
                    length: recompressed.len(),
                },
            );
            spill_position += recompressed.len() as u64;
        }
    }
    Ok(changed)
}

/// Reads the compressed data of one chunk, inline or external, and records its header fields.
fn read_chunk_payload(
    file: &mut File,
    file_size: u64,
    entry: &mut Entry,
    source: &Path,
    mcc_limit: u64,
    place: &str,
) -> Result<Vec<u8>> {
    let start = entry.sector_offset as u64 * SECTOR as u64;
    let available = (entry.sector_count as u64 * SECTOR as u64).min(file_size - start) as usize;
    if available < 5 {
        return Err(RemapError::region(format!(
            "{place}: chunk header is truncated"
        )));
    }
    let head = read_exactly(file, start, 5, source)?;
    let length = s4(&head, 0);
    entry.type_byte = head[4];
    if entry.type_byte & EXTERNAL_FLAG != 0 {
        entry.external = true;
        let name = entry.mcc_name();
        let mcc = sibling(source, &name);
        let metadata = fs::symlink_metadata(&mcc)
            .ok()
            .filter(fsutil::is_plain_file);
        let Some(metadata) = metadata else {
            return Err(RemapError::region(format!(
                "{place}: external chunk file {name} is missing"
            )));
        };
        if metadata.len() > mcc_limit {
            return Err(RemapError::LimitExceeded(format!(
                "{place}: {name} exceeds {mcc_limit} bytes"
            )));
        }
        let bytes = compress::read_at_most(fsutil::open_nofollow(&mcc)?, mcc_limit as usize, &name)
            .map_err(|_| RemapError::region(format!("{place}: {name} changed while reading")))?;
        if bytes.len() as u64 != metadata.len() {
            return Err(RemapError::region(format!(
                "{place}: {name} changed while reading"
            )));
        }
        return Ok(bytes);
    }
    if length <= 0 {
        return Err(RemapError::region(format!(
            "{place}: invalid chunk length {length}"
        )));
    }
    if length as u64 + 4 > available as u64 {
        return Err(RemapError::region(format!(
            "{place}: chunk length {length} exceeds its {available} available bytes"
        )));
    }
    entry.inline_record_length = length as usize + 4;
    read_exactly(file, start + 5, length as usize - 1, source)
}

fn check_supported(kind: u8, place: &str) -> Result<()> {
    use chunk_compression::*;
    match kind {
        GZIP | ZLIB | NONE | ZSTD => Ok(()),
        LZ4 => Err(RemapError::region(format!(
            "{place}: LZ4 chunk compression is not supported"
        ))),
        CUSTOM => Err(RemapError::region(format!(
            "{place}: custom chunk compression is not supported"
        ))),
        _ => Err(RemapError::region(format!(
            "{place}: unknown chunk compression type {kind}"
        ))),
    }
}

fn inline_record(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut record = Vec::with_capacity(payload.len() + 5);
    record.extend_from_slice(&(payload.len() as i32 + 1).to_be_bytes());
    record.push(kind);
    record.extend_from_slice(payload);
    record
}

fn external_stub(kind: u8) -> Vec<u8> {
    let mut record = 1i32.to_be_bytes().to_vec();
    record.push(kind | EXTERNAL_FLAG);
    record
}

fn write_region(
    file: &mut File,
    entries: &[Entry],
    changed: &HashMap<usize, Spilled>,
    spill: &mut File,
    source: &Path,
    target: &Path,
    external_threshold_sectors: usize,
) -> Result<()> {
    let mut temporary = temporary_in(target, ".tmp")?;
    let mut header = vec![0u8; HEADER];
    {
        let output = temporary.as_file_mut();
        let mut next_sector = 2usize;
        output
            .seek(SeekFrom::Start(HEADER as u64))
            .ctx(|| target.display().to_string())?;
        let mut writer = std::io::BufWriter::new(&mut *output);
        for entry in entries {
            let kind = entry.type_byte & 0x7f;
            let record = if let Some(spilled) = changed.get(&entry.index) {
                let payload = read_exactly(
                    spill,
                    spilled.offset,
                    spilled.length,
                    Path::new("region spill file"),
                )?;
                let inline_sectors = sectors_for(payload.len() + 5);
                if entry.external || inline_sectors >= external_threshold_sectors {
                    write_replacing(&sibling(target, &entry.mcc_name()), &payload)?;
                    external_stub(kind)
                } else {
                    inline_record(kind, &payload)
                }
            } else if entry.external {
                copy_replacing(
                    &sibling(source, &entry.mcc_name()),
                    &sibling(target, &entry.mcc_name()),
                )?;
                external_stub(kind)
            } else {
                read_exactly(
                    file,
                    entry.sector_offset as u64 * SECTOR as u64,
                    entry.inline_record_length,
                    source,
                )?
            };
            let sectors = sectors_for(record.len());
            if sectors > 255 || next_sector + sectors > 0xFF_FFFF {
                return Err(RemapError::region(format!(
                    "{}: chunk ({},{}) does not fit the region format",
                    target.display(),
                    entry.chunk_x,
                    entry.chunk_z
                )));
            }
            writer
                .write_all(&record)
                .ctx(|| target.display().to_string())?;
            writer
                .write_all(&vec![0; sectors * SECTOR - record.len()])
                .ctx(|| target.display().to_string())?;
            header[entry.index * 4..entry.index * 4 + 4]
                .copy_from_slice(&(((next_sector << 8) | sectors) as u32).to_be_bytes());
            header[SECTOR + entry.index * 4..SECTOR + entry.index * 4 + 4]
                .copy_from_slice(&entry.timestamp.to_be_bytes());
            next_sector += sectors;
        }
        writer.flush().ctx(|| target.display().to_string())?;
    }
    let output = temporary.as_file_mut();
    output
        .seek(SeekFrom::Start(0))
        .ctx(|| target.display().to_string())?;
    output
        .write_all(&header)
        .ctx(|| target.display().to_string())?;
    temporary
        .persist(target)
        .map_err(|error| RemapError::io(target.display().to_string(), error.error))?;
    Ok(())
}
