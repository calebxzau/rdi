//! Kotlin `forEachArchiveEntryStreaming` with `MODPACK_ARCHIVE_READ_LIMITS`, and
//! `extractClientPackArchiveToDir`, flat for the client pack master builds and layered for an
//! upload archive installed where it was built.
use crate::tasks::Cancellation;
use anyhow::{Result, bail, ensure};
use std::{
    collections::HashMap,
    io::{BufReader, Read, Write},
    path::Path,
};

#[derive(Clone, Copy)]
pub struct Limits {
    pub entry: u64,
    pub total: u64,
    pub entries: usize,
}
pub const MODPACK_LIMITS: Limits = Limits {
    entry: 2 * 1024 * 1024 * 1024,
    total: 8 * 1024 * 1024 * 1024,
    entries: 100_000,
};
const MAX_METADATA_ENTRY_BYTES: u64 = 1024 * 1024;
const MAX_METADATA_TOTAL_BYTES: u64 = 64 * 1024 * 1024;
const MAX_CONSECUTIVE_METADATA: u32 = 16;
const MAX_METADATA_HEADERS: u32 = 100_000;

pub struct Entry {
    pub path: String,
    pub directory: bool,
    pub size: u64,
}

enum Format {
    Zip,
    TarZst,
}
fn format(path: &Path) -> Result<Format> {
    let mut head = [0u8; 4];
    let mut file = std::fs::File::open(path)?;
    let mut read = 0;
    while read < 4 {
        let n = file.read(&mut head[read..])?;
        if n == 0 {
            break;
        }
        read += n;
    }
    // Cache files are named by digest, so Kotlin's `.tar.zst` name fallback never applies.
    Ok(if read == 4 && head == [0x28, 0xB5, 0x2F, 0xFD] {
        Format::TarZst
    } else {
        Format::Zip
    })
}

fn parse_octal_or_binary(field: &[u8]) -> Result<u64> {
    if field.first().is_some_and(|b| b & 0x80 != 0) {
        ensure!(field[0] & 0x40 == 0, "整合包TAR条目大小不正确");
        let mut value: u64 = u64::from(field[0] & 0x7f);
        for &b in &field[1..] {
            value = value
                .checked_mul(256)
                .and_then(|v| v.checked_add(u64::from(b)))
                .ok_or_else(|| anyhow::anyhow!("整合包TAR条目大小不正确"))?;
        }
        return Ok(value);
    }
    let text: String = field
        .iter()
        .take_while(|b| **b != 0)
        .map(|b| *b as char)
        .collect();
    let text = text.trim();
    if text.is_empty() {
        return Ok(0);
    }
    u64::from_str_radix(text, 8).map_err(|_| anyhow::anyhow!("整合包TAR条目大小不正确"))
}

/// Port of Kotlin `BoundedTarInputStream`: checks raw headers before the tar reader buffers
/// GNU or PAX records, so metadata cannot grow without bound.
struct BoundedTar<R> {
    source: R,
    limits: Limits,
    cancel: Cancellation,
    header: [u8; 512],
    header_offset: usize,
    body_remaining: u64,
    declared_total: u64,
    metadata_total: u64,
    consecutive_metadata: u32,
    headers: usize,
    metadata_headers: u32,
    pax: Option<(Vec<u8>, usize)>,
    global: bool,
    next_pax_size: Option<u64>,
}
fn invalid(message: impl Into<String>) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, message.into())
}
impl<R: Read> BoundedTar<R> {
    fn new(source: R, limits: Limits, cancel: Cancellation) -> Self {
        Self {
            source,
            limits,
            cancel,
            header: [0; 512],
            header_offset: 512,
            body_remaining: 0,
            declared_total: 0,
            metadata_total: 0,
            consecutive_metadata: 0,
            headers: 0,
            metadata_headers: 0,
            pax: None,
            global: false,
            next_pax_size: None,
        }
    }
    fn load_header(&mut self) -> Result<bool> {
        let mut loaded = 0;
        while loaded < 512 {
            let n = self.source.read(&mut self.header[loaded..])?;
            if n == 0 {
                ensure!(loaded == 0, "整合包TAR文件头被截断");
                return Ok(false);
            }
            loaded += n;
        }
        self.header_offset = 0;
        if self.header.iter().all(|b| *b == 0) {
            return Ok(true);
        }
        let kind = self.header[156];
        let metadata = matches!(kind, b'L' | b'K' | b'x' | b'g' | b'X');
        let header_size = parse_octal_or_binary(&self.header[124..136])?;
        let size = if metadata {
            header_size
        } else {
            let pax = self.next_pax_size.take();
            // Our tar reader applies only a local PAX size; anything else would desynchronize it.
            ensure!(
                pax.is_none_or(|p| p == header_size),
                "整合包TAR条目大小不正确"
            );
            header_size
        };
        if metadata {
            ensure!(
                self.metadata_headers < MAX_METADATA_HEADERS,
                "整合包TAR文件头元数据条目过多"
            );
            self.metadata_headers += 1;
            ensure!(size <= MAX_METADATA_ENTRY_BYTES, "整合包TAR文件头元数据过大");
            ensure!(
                size <= MAX_METADATA_TOTAL_BYTES - self.metadata_total,
                "整合包TAR文件头元数据总量过大"
            );
            self.metadata_total += size;
            self.consecutive_metadata += 1;
            ensure!(
                self.consecutive_metadata <= MAX_CONSECUTIVE_METADATA,
                "整合包TAR文件头元数据层数过多"
            );
            if matches!(kind, b'x' | b'g' | b'X') {
                self.global = kind == b'g';
                self.pax = Some((vec![0; size as usize], 0));
                if size == 0 {
                    self.finish_pax(self.global)?;
                }
            }
        } else {
            ensure!(
                self.headers < self.limits.entries,
                "整合包文件数量超过{}个限制",
                self.limits.entries
            );
            self.headers += 1;
            self.consecutive_metadata = 0;
            let name = String::from_utf8_lossy(
                &self.header[..100]
                    .iter()
                    .copied()
                    .take_while(|b| *b != 0)
                    .collect::<Vec<_>>(),
            )
            .into_owned();
            ensure!(
                !matches!(kind, b'1' | b'2' | b'3' | b'4' | b'6' | b'S'),
                "整合包包含不支持的文件类型: {name}"
            );
            ensure!(
                size <= self.limits.entry,
                "整合包单文件解压大小超过{}字节限制: {name}",
                self.limits.entry
            );
            ensure!(
                size <= self.limits.total - self.declared_total,
                "整合包总解压大小超过{}字节限制",
                self.limits.total
            );
            self.declared_total += size;
        }
        self.body_remaining = size.div_ceil(512) * 512;
        Ok(true)
    }
    fn finish_pax(&mut self, global: bool) -> Result<()> {
        let Some((bytes, _)) = self.pax.take() else {
            return Ok(());
        };
        let mut offset = 0;
        while offset < bytes.len() {
            let space = bytes[offset..]
                .iter()
                .position(|b| *b == b' ')
                .map(|p| p + offset)
                .ok_or_else(|| anyhow::anyhow!("整合包PAX文件头不完整"))?;
            let length: usize = std::str::from_utf8(&bytes[offset..space])
                .ok()
                .and_then(|s| s.parse().ok())
                .ok_or_else(|| anyhow::anyhow!("整合包PAX文件头长度不正确"))?;
            ensure!(
                length > space - offset + 1 && length <= bytes.len() - offset,
                "整合包PAX文件头长度不正确"
            );
            let record = String::from_utf8_lossy(&bytes[space + 1..offset + length - 1]);
            let (key, value) = record.split_once('=').unwrap_or((&record, ""));
            ensure!(
                !key.starts_with("GNU.sparse") && key != "SCHILY.realsize",
                "整合包不支持稀疏文件"
            );
            if key == "size" {
                let size: u64 = value
                    .parse()
                    .map_err(|_| anyhow::anyhow!("整合包PAX文件大小不正确"))?;
                ensure!(
                    size <= self.limits.entry,
                    "整合包单文件解压大小超过{}字节限制",
                    self.limits.entry
                );
                // Kotlin honours a global PAX size; our tar reader cannot, so refuse it.
                ensure!(!global, "整合包TAR条目大小不正确");
                self.next_pax_size = Some(size);
            }
            offset += length;
        }
        Ok(())
    }
    fn step(&mut self, out: &mut [u8]) -> Result<usize> {
        if out.is_empty() {
            return Ok(0);
        }
        self.cancel.check()?;
        loop {
            if self.header_offset < 512 {
                let n = out.len().min(512 - self.header_offset);
                out[..n].copy_from_slice(&self.header[self.header_offset..self.header_offset + n]);
                self.header_offset += n;
                return Ok(n);
            }
            if self.body_remaining > 0 {
                let want = (out.len() as u64).min(self.body_remaining) as usize;
                let n = self.source.read(&mut out[..want])?;
                ensure!(n > 0, "整合包TAR文件内容被截断");
                let global = self.global;
                if let Some((payload, filled)) = &mut self.pax {
                    let copied = n.min(payload.len() - *filled);
                    payload[*filled..*filled + copied].copy_from_slice(&out[..copied]);
                    *filled += copied;
                    if *filled == payload.len() {
                        self.finish_pax(global)?;
                    }
                }
                self.body_remaining -= n as u64;
                return Ok(n);
            }
            if !self.load_header()? {
                return Ok(0);
            }
        }
    }
}
impl<R: Read> Read for BoundedTar<R> {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        self.step(out).map_err(|e| invalid(format!("{e:#}")))
    }
}

#[cfg(test)]
pub fn bounded_for_test<R: Read>(source: R, limits: Limits, cancel: Cancellation) -> impl Read {
    BoundedTar::new(source, limits, cancel)
}

/// Visits every entry in order. A callback may read or skip the payload; skipped bytes still count.
pub fn for_each(
    path: &Path,
    limits: Limits,
    cancel: &Cancellation,
    on_entry: &mut dyn FnMut(&Entry, &mut dyn Read) -> Result<()>,
) -> Result<()> {
    let mut count = 0usize;
    let mut declared = 0u64;
    let mut actual = 0u64;
    let mut visit = |entry: Entry, source: &mut dyn Read| -> Result<()> {
        cancel.check()?;
        ensure!(
            count < limits.entries,
            "整合包文件数量超过{}个限制",
            limits.entries
        );
        count += 1;
        ensure!(
            entry.size <= limits.entry,
            "整合包单文件解压大小超过{}字节限制: {}",
            limits.entry,
            entry.path
        );
        ensure!(
            !entry.directory || entry.size == 0,
            "整合包目录条目大小不正确: {}",
            entry.path
        );
        ensure!(
            entry.size <= limits.total - declared,
            "整合包总解压大小超过{}字节限制",
            limits.total
        );
        declared += entry.size;
        let mut counted = Counted {
            source,
            size: entry.size,
            read: 0,
            total: &mut actual,
            limit: limits.total,
            cancel,
            path: &entry.path,
        };
        on_entry(&entry, &mut counted)?;
        std::io::copy(&mut counted, &mut std::io::sink()).map_err(unwrap_io)?;
        ensure!(counted.read == entry.size, "整合包文件被截断: {}", entry.path);
        Ok(())
    };
    match format(path)? {
        Format::Zip => {
            let mut zip = zip::ZipArchive::new(std::fs::File::open(path)?)?;
            for i in 0..zip.len() {
                let mut file = zip.by_index(i)?;
                let name = crate::import::archive::decode_name(file.name_raw());
                let entry = Entry {
                    directory: file.is_dir() || name.ends_with('/'),
                    size: file.size(),
                    path: name,
                };
                visit(entry, &mut file)?;
            }
        }
        Format::TarZst => {
            let decoder = zstd::stream::read::Decoder::with_buffer(BufReader::new(
                std::fs::File::open(path)?,
            ))?;
            let bounded = BoundedTar::new(decoder, limits, cancel.clone());
            let mut archive = tar::Archive::new(bounded);
            for item in archive.entries().map_err(unwrap_io)? {
                let mut item = item.map_err(unwrap_io)?;
                let kind = item.header().entry_type();
                let name = String::from_utf8_lossy(&item.path_bytes()).into_owned();
                ensure!(
                    !(kind.is_symlink()
                        || kind.is_hard_link()
                        || kind.is_character_special()
                        || kind.is_block_special()
                        || kind.is_fifo()
                        || kind.is_gnu_sparse()),
                    "整合包包含不支持的文件类型: {name}"
                );
                let entry = Entry {
                    directory: kind.is_dir() || name.ends_with('/'),
                    size: item.size(),
                    path: name,
                };
                visit(entry, &mut item)?;
            }
        }
    }
    Ok(())
}

/// Keeps the Chinese message from an inner check instead of an io wrapper.
fn unwrap_io(error: std::io::Error) -> anyhow::Error {
    if error.kind() == std::io::ErrorKind::InvalidData
        && let Some(inner) = error.get_ref()
    {
        return anyhow::anyhow!("{inner}");
    }
    error.into()
}

struct Counted<'a> {
    source: &'a mut dyn Read,
    size: u64,
    read: u64,
    total: &'a mut u64,
    limit: u64,
    cancel: &'a Cancellation,
    path: &'a str,
}
impl Read for Counted<'_> {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        if self.cancel.cancelled() {
            return Err(invalid("任务已取消"));
        }
        let n = self.source.read(out)?;
        if n > 0 {
            if n as u64 > self.size - self.read {
                return Err(invalid(format!("整合包文件实际大小与声明不一致: {}", self.path)));
            }
            if n as u64 > self.limit - *self.total {
                return Err(invalid(format!("整合包总解压大小超过{}字节限制", self.limit)));
            }
            self.read += n as u64;
            *self.total += n as u64;
        }
        Ok(n)
    }
}

/// Kotlin `normalizeSafeArchivePath`.
pub fn normalize_safe_path(raw: &str) -> Result<String> {
    let path = raw.replace('\\', "/");
    let drive = path.len() >= 2 && path.as_bytes()[0].is_ascii_alphabetic() && path.as_bytes()[1] == b':';
    ensure!(
        !path.starts_with('/') && !drive && !path.contains('\0'),
        "非法整合包文件路径: {raw}"
    );
    let segments: Vec<_> = path.split('/').filter(|s| !s.is_empty() && *s != ".").collect();
    ensure!(
        segments.iter().all(|s| *s != ".." && !s.contains(':')),
        "非法整合包文件路径: {raw}"
    );
    Ok(segments.join("/"))
}

/// `extractClientPackArchiveToDir` with `layered = false`. Two passes like Kotlin: select, then write.
pub fn extract(
    archive: &Path,
    target: &Path,
    cancel: &Cancellation,
    progress: &dyn Fn(usize, usize, &str),
) -> Result<()> {
    extract_with(archive, target, cancel, progress, &|path| Ok(Some((normalize_safe_path(path)?, 0))))
}

/// `extractClientPackArchiveToDir` with `layered = true`, for an upload archive installed on the
/// uploader's computer: `client-overrides` over `overrides`, `server/` and the manifests left out
/// (`resolveClientPackEntryPath`).
pub fn extract_layered(
    archive: &Path,
    target: &Path,
    cancel: &Cancellation,
    progress: &dyn Fn(usize, usize, &str),
) -> Result<()> {
    extract_with(archive, target, cancel, progress, &|path| {
        Ok(crate::upload::mods::client_entry_path(path, false).map(|e| (e.relative, e.priority)))
    })
}

/// A file's path in the pack folder and its layer, or `None` to leave the file out.
type EntryMap<'a> = dyn Fn(&str) -> Result<Option<(String, u8)>> + 'a;

/// Picks every file's path and layer with `map`; of two files on one path (case ignored) the
/// higher layer wins, two on the same layer are an error.
fn extract_with(
    archive: &Path,
    target: &Path,
    cancel: &Cancellation,
    progress: &dyn Fn(usize, usize, &str),
    map: &EntryMap,
) -> Result<()> {
    let mut selected: HashMap<String, (usize, String, u8)> = HashMap::new();
    let mut index = 0usize;
    for_each(archive, MODPACK_LIMITS, cancel, &mut |entry, _| {
        let i = index;
        index += 1;
        if entry.directory {
            return Ok(());
        }
        let Some((path, priority)) = map(&entry.path)? else {
            return Ok(());
        };
        let key = path.to_lowercase();
        if let Some(old) = selected.get(&key) {
            ensure!(old.2 != priority, "客户端整合包包含重复文件路径: {path}");
            if old.2 > priority {
                return Ok(());
            }
        }
        selected.insert(key, (i, path, priority));
        Ok(())
    })?;
    for key in selected.keys() {
        let mut parent = key.as_str();
        while let Some((p, _)) = parent.rsplit_once('/') {
            ensure!(
                !selected.contains_key(p),
                "客户端整合包文件与目录路径冲突: {}",
                selected[key].1
            );
            parent = p;
        }
    }
    let by_index: HashMap<usize, String> = selected.values().map(|(i, path, _)| (*i, path.clone())).collect();
    let total = selected.len().max(1);
    let root = std::path::absolute(target)?;
    ensure!(
        !std::fs::symlink_metadata(&root)?.file_type().is_symlink(),
        "客户端整合包目标目录是符号链接: {}",
        root.display()
    );
    let mut index = 0usize;
    let mut done = 0usize;
    for_each(archive, MODPACK_LIMITS, cancel, &mut |_, input| {
        let i = index;
        index += 1;
        let Some(relative) = by_index.get(&i) else {
            return Ok(());
        };
        ensure!(!relative.is_empty(), "非法客户端整合包路径: {relative}");
        let mut current = root.clone();
        for part in relative.split('/') {
            current.push(part);
            if let Ok(m) = std::fs::symlink_metadata(&current) {
                ensure!(
                    !m.file_type().is_symlink(),
                    "客户端整合包目标包含符号链接: {}",
                    current.display()
                );
            }
        }
        std::fs::create_dir_all(current.parent().unwrap())?;
        let mut out = std::fs::File::create(&current)?;
        let mut buffer = vec![0u8; 128 * 1024];
        loop {
            let n = match input.read(&mut buffer) {
                Ok(n) => n,
                Err(e) => return Err(unwrap_io(e)),
            };
            if n == 0 {
                break;
            }
            out.write_all(&buffer[..n])?;
        }
        done += 1;
        progress(done, total, relative);
        Ok(())
    })?;
    if index == 0 && !selected.is_empty() {
        bail!("客户端整合包读取失败");
    }
    Ok(())
}
