//! Minimal Anvil region reader/writer used by world checkpoints.
//!
//! Region payloads are intentionally opaque.  The client has already produced the
//! vanilla `[length][compression version][payload]` representation; the master only
//! validates framing and places the bytes into sectors.

use anyhow::{Context, Result, bail};
use sha1::{Digest, Sha1};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
};

pub const SECTOR_BYTES: usize = 4096;
pub const HEADER_BYTES: usize = SECTOR_BYTES * 2;
pub const ENTRY_COUNT: usize = 1024;

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub struct Slot {
    pub timestamp: i32,
    pub sector_count: u8,
}

impl Default for Slot {
    fn default() -> Self {
        Self {
            timestamp: 0,
            sector_count: 0,
        }
    }
}

#[derive(Debug, Clone)]
pub struct RegionTable {
    pub slots: [Slot; ENTRY_COUNT],
}

impl Default for RegionTable {
    fn default() -> Self {
        Self {
            slots: [Slot::default(); ENTRY_COUNT],
        }
    }
}

impl RegionTable {
    pub fn digest(&self) -> String {
        let mut hasher = Sha1::new();
        for slot in self.slots {
            hasher.update(slot.timestamp.to_be_bytes());
            hasher.update((slot.sector_count as i32).to_be_bytes());
        }
        hex::encode(hasher.finalize())
    }
}

pub fn read_table(path: &Path) -> Result<RegionTable> {
    if !path.is_file() {
        return Ok(RegionTable::default());
    }
    let mut file = File::open(path).with_context(|| format!("open region {}", path.display()))?;
    let len = file.metadata()?.len();
    if len < HEADER_BYTES as u64 {
        bail!("region header is truncated")
    }
    let mut header = [0u8; HEADER_BYTES];
    file.read_exact(&mut header)?;
    let mut table = RegionTable::default();
    for index in 0..ENTRY_COUNT {
        let offset = u32::from_be_bytes(header[index * 4..index * 4 + 4].try_into().unwrap());
        let timestamp = i32::from_be_bytes(
            header[SECTOR_BYTES + index * 4..SECTOR_BYTES + index * 4 + 4]
                .try_into()
                .unwrap(),
        );
        table.slots[index] = Slot {
            timestamp,
            sector_count: (offset & 0xff) as u8,
        };
    }
    Ok(table)
}

pub fn read_entry(path: &Path, index: usize, table: &RegionTable) -> Result<Option<Vec<u8>>> {
    if index >= ENTRY_COUNT {
        bail!("region entry index is out of range")
    }
    let slot = table.slots[index];
    if slot.sector_count == 0 {
        return Ok(None);
    }
    let mut file = File::open(path)?;
    let mut offset = [0u8; 4];
    file.seek(SeekFrom::Start((index * 4) as u64))?;
    file.read_exact(&mut offset)?;
    let offset = u32::from_be_bytes(offset) >> 8;
    if offset < 2 {
        bail!("region entry points into header")
    }
    let mut sector = vec![0u8; slot.sector_count as usize * SECTOR_BYTES];
    file.seek(SeekFrom::Start(offset as u64 * SECTOR_BYTES as u64))?;
    file.read_exact(&mut sector)?;
    if sector.len() < 5 {
        bail!("region entry is truncated")
    }
    let length = u32::from_be_bytes(sector[..4].try_into().unwrap()) as usize;
    if length < 1 || length + 4 > sector.len() {
        bail!("region entry length is invalid")
    }
    let version = sector[4];
    if version == 0 {
        bail!("region entry compression version is invalid")
    }
    if version & 0x80 != 0 {
        let (x, z) = region_coords(path)?;
        let external = path.with_file_name(format!("c.{x}.{z}.mcc"));
        let payload = fs::read(external).context("read external region entry")?;
        let external_length = payload.len() + 1;
        let mut raw = Vec::with_capacity(length + 4);
        raw.extend_from_slice(&(external_length as u32).to_be_bytes());
        raw.push(version & 0x7f);
        raw.extend_from_slice(&payload);
        return Ok(Some(raw));
    }
    Ok(Some(sector[..length + 4].to_vec()))
}

pub fn validate_raw(raw: &[u8]) -> Result<usize> {
    if raw.len() < 5 {
        bail!("region entry is too short")
    }
    let length = u32::from_be_bytes(raw[..4].try_into().unwrap()) as usize;
    if length < 1 || length + 4 != raw.len() {
        bail!("region entry length does not match member")
    }
    if raw[4] == 0 {
        bail!("region entry compression version is invalid")
    }
    Ok((4 + length).div_ceil(SECTOR_BYTES))
}

pub fn write_entry(
    world_root: &Path,
    region: &str,
    index: usize,
    timestamp: i32,
    raw: &[u8],
) -> Result<()> {
    let path = world_root.join(region);
    let sectors = validate_raw(raw)?;
    if index >= ENTRY_COUNT {
        bail!("region entry index is out of range")
    }
    if sectors > 255 {
        return write_external(world_root, &path, index, timestamp, raw);
    }
    let parent = path.parent().context("region path has no parent")?;
    fs::create_dir_all(parent)?;
    let mut file = OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .open(&path)?;
    ensure_header(&mut file)?;
    let mut header = read_header(&mut file)?;
    let old = u32::from_be_bytes(header[index * 4..index * 4 + 4].try_into().unwrap());
    let old_start = (old >> 8) as usize;
    let old_count = (old & 0xff) as usize;
    let file_sectors = file.metadata()?.len().div_ceil(SECTOR_BYTES as u64) as usize;
    let mut used = vec![false; file_sectors.max(2) + sectors + 1];
    used[0] = true;
    used[1] = true;
    for i in 0..ENTRY_COUNT {
        let value = u32::from_be_bytes(header[i * 4..i * 4 + 4].try_into().unwrap());
        let start = (value >> 8) as usize;
        let count = (value & 0xff) as usize;
        if i == index || start == 0 || count == 0 {
            continue;
        }
        if used.len() < start + count {
            used.resize(start + count, false);
        }
        for sector in start..start + count {
            used[sector] = true;
        }
    }
    let start = if old_count >= sectors
        && old_start >= 2
        && old_start + sectors <= used.len()
        && (old_start..old_start + sectors).all(|s| !used[s])
    {
        old_start
    } else {
        let mut candidate = 2;
        loop {
            if used.len() < candidate + sectors {
                used.resize(candidate + sectors, false);
            }
            if (candidate..candidate + sectors).all(|s| !used[s]) {
                break candidate;
            }
            candidate += 1;
        }
    };
    let mut padded = vec![0u8; sectors * SECTOR_BYTES];
    padded[..raw.len()].copy_from_slice(raw);
    file.seek(SeekFrom::Start(start as u64 * SECTOR_BYTES as u64))?;
    file.write_all(&padded)?;
    let offset = ((start as u32) << 8) | sectors as u32;
    header[index * 4..index * 4 + 4].copy_from_slice(&offset.to_be_bytes());
    header[SECTOR_BYTES + index * 4..SECTOR_BYTES + index * 4 + 4]
        .copy_from_slice(&timestamp.to_be_bytes());
    file.seek(SeekFrom::Start(0))?;
    file.write_all(&header)?;
    file.sync_all()?;
    let (x, z) = region_coords(&path)?;
    let _ = fs::remove_file(path.with_file_name(format!("c.{x}.{z}.mcc")));
    Ok(())
}

fn write_external(
    world_root: &Path,
    path: &Path,
    index: usize,
    timestamp: i32,
    raw: &[u8],
) -> Result<()> {
    let parent = path.parent().context("region path has no parent")?;
    fs::create_dir_all(parent)?;
    let mut file = OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .open(path)?;
    ensure_header(&mut file)?;
    let mut header = read_header(&mut file)?;
    let (x, z) = region_coords(path)?;
    let external = path.with_file_name(format!("c.{x}.{z}.mcc"));
    fs::write(&external, &raw[5..])?;
    let old = u32::from_be_bytes(header[index * 4..index * 4 + 4].try_into().unwrap());
    let start = if old >> 8 >= 2 {
        old >> 8
    } else {
        file.metadata()?.len().div_ceil(SECTOR_BYTES as u64) as u32
    };
    let offset = (start << 8) | 1;
    let mut stub = [0u8; SECTOR_BYTES];
    stub[..4].copy_from_slice(&1u32.to_be_bytes());
    stub[4] = raw[4] | 0x80;
    file.seek(SeekFrom::Start(start as u64 * SECTOR_BYTES as u64))?;
    file.write_all(&stub)?;
    header[index * 4..index * 4 + 4].copy_from_slice(&offset.to_be_bytes());
    header[SECTOR_BYTES + index * 4..SECTOR_BYTES + index * 4 + 4]
        .copy_from_slice(&timestamp.to_be_bytes());
    file.seek(SeekFrom::Start(0))?;
    file.write_all(&header)?;
    file.sync_all()?;
    let _ = world_root;
    Ok(())
}

pub fn clear(world_root: &Path, region: &str, index: usize, timestamp: i32) -> Result<()> {
    if index >= ENTRY_COUNT {
        bail!("region entry index is out of range")
    }
    let path = world_root.join(region);
    if !path.is_file() {
        return Ok(());
    }
    let mut file = OpenOptions::new().read(true).write(true).open(&path)?;
    ensure_header(&mut file)?;
    let mut header = read_header(&mut file)?;
    header[index * 4..index * 4 + 4].copy_from_slice(&0u32.to_be_bytes());
    header[SECTOR_BYTES + index * 4..SECTOR_BYTES + index * 4 + 4]
        .copy_from_slice(&timestamp.to_be_bytes());
    file.seek(SeekFrom::Start(0))?;
    file.write_all(&header)?;
    file.sync_all()?;
    let (x, z) = region_coords(&path)?;
    let _ = fs::remove_file(path.with_file_name(format!("c.{x}.{z}.mcc")));
    Ok(())
}

fn ensure_header(file: &mut File) -> Result<()> {
    if file.metadata()?.len() < HEADER_BYTES as u64 {
        file.set_len(HEADER_BYTES as u64)?;
        file.seek(SeekFrom::Start(0))?;
        file.write_all(&vec![0u8; HEADER_BYTES])?;
    }
    Ok(())
}

fn read_header(file: &mut File) -> Result<[u8; HEADER_BYTES]> {
    let mut header = [0u8; HEADER_BYTES];
    file.seek(SeekFrom::Start(0))?;
    file.read_exact(&mut header)?;
    Ok(header)
}

fn region_coords(path: &Path) -> Result<(i32, i32)> {
    let name = path
        .file_name()
        .and_then(|v| v.to_str())
        .context("invalid region file name")?;
    let stem = name
        .strip_prefix("r.")
        .and_then(|v| v.strip_suffix(".mca"))
        .context("invalid region file name")?;
    let mut parts = stem.split('.');
    let x: i32 = parts.next().context("invalid region x")?.parse()?;
    let z: i32 = parts.next().context("invalid region z")?.parse()?;
    Ok((x, z))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn raw(payload: &[u8]) -> Vec<u8> {
        let mut value = Vec::new();
        value.extend_from_slice(&((payload.len() + 1) as u32).to_be_bytes());
        value.push(3);
        value.extend_from_slice(payload);
        value
    }

    #[test]
    fn write_read_clear_round_trip() {
        let dir = tempdir().unwrap();
        let root = dir.path();
        let value = raw(b"abc");
        write_entry(root, "region/r.0.0.mca", 3, 12, &value).unwrap();
        let path = root.join("region/r.0.0.mca");
        let table = read_table(&path).unwrap();
        assert_eq!(
            table.slots[3],
            Slot {
                timestamp: 12,
                sector_count: 1
            }
        );
        assert_eq!(read_entry(&path, 3, &table).unwrap().unwrap(), value);
        clear(root, "region/r.0.0.mca", 3, 13).unwrap();
        assert_eq!(read_table(&path).unwrap().slots[3].sector_count, 0);
    }

    #[test]
    fn digest_is_fixed_width() {
        assert_eq!(RegionTable::default().digest().len(), 40);
    }

    #[test]
    fn oversized_entry_uses_external_file_and_round_trips() {
        let dir = tempdir().unwrap();
        let value = raw(&vec![7u8; 1_100_000]);
        write_entry(dir.path(), "region/r.0.0.mca", 4, 21, &value).unwrap();
        let path = dir.path().join("region/r.0.0.mca");
        let table = read_table(&path).unwrap();
        assert_eq!(table.slots[4].sector_count, 1);
        assert!(dir.path().join("region/c.0.0.mcc").is_file());
        assert_eq!(read_entry(&path, 4, &table).unwrap().unwrap(), value);
    }
}
