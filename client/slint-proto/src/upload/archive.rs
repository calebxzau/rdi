//! The upload archive (`.tar.zst`) of a read pack: Kotlin `buildUploadArchiveInternal` with
//! `writeProcessedEntry`, `processNestedZip`, `writeProcessedNestedZipEntry`,
//! `readResourcepackFile`, `readResourcepackEntry`, `verifyMatchedExtraSources` and
//! `findOversizedUnpackedResourcepacks`. The two encoders (PNG to AVIF, OGG to Opus) are behind
//! `Media`; stage A4 supplies them. Blocking, like `reader`.
use super::mods::client_entry_path;
use super::policy::{self, SaveRegions};
use super::reader::{self, Context, EmbeddedExtra, Progress};
use super::server_pack::ServerExtra;
use crate::pack_install::extras::{Content, Kind};
use crate::rdipack2::append;
use anyhow::{Context as _, Result, bail, ensure};
use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};

/// `RESOURCEPACK_MAX_SIZE_BYTES`.
pub const RESOURCEPACK_MAX: u64 = 50 * 1024 * 1024;
/// `MODPACK_UPLOAD_MAX_MB`.
pub const UPLOAD_MAX_MB: u64 = 768;
const EMPTY_MP3: &[u8] = include_bytes!("../../assets/empty.mp3");
pub const EMPTY_OGG: &[u8] = include_bytes!("../../assets/empty.ogg");
/// `PNG_COMPRESSION_THRESHOLD_BYTES`: smaller pictures stay as they are.
pub const PNG_THRESHOLD: usize = 50 * 1024;

/// The encoders of the upload.
pub trait Media: Sync {
    /// `processUploadPng`: a picture over `PNG_THRESHOLD` as AVIF under its unchanged name, or
    /// unchanged when it cannot be encoded.
    fn png(&self, bytes: Vec<u8>, name: &str) -> Vec<u8>;
    /// `processOggBytes`: the sound as Opus; `EMPTY_OGG` when it is longer than 10 s or cannot
    /// be read. An error means the encoder itself is broken, which stops the upload.
    fn ogg(&self, bytes: Vec<u8>, name: &str) -> Result<Vec<u8>>;
}

/// Leaves pictures and sounds as they are, for tests; an upload uses `media::Encoders`.
#[cfg(test)]
pub struct Unprocessed;

#[cfg(test)]
impl Media for Unprocessed {
    fn png(&self, bytes: Vec<u8>, _: &str) -> Vec<u8> {
        bytes
    }
    fn ogg(&self, bytes: Vec<u8>, _: &str) -> Result<Vec<u8>> {
        Ok(bytes)
    }
}

pub struct Input<'a> {
    /// `Loaded::source_dir`.
    pub root: &'a Path,
    pub base_name: &'a str,
    pub server_extras: &'a [ServerExtra],
    /// Recognised packs of the pack: they come from the platforms, so they stay out.
    pub excluded: &'a [EmbeddedExtra],
    pub client_extras: &'a [Content],
}

fn log_skip(relative: &str, directory: bool, reason: &str) {
    tracing::info!(path = relative, kind = if directory { "目录" } else { "文件" }, reason, "整合包上传跳过");
}

fn now_seconds() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn under(relative: &str, roots: &[String]) -> bool {
    roots.iter().any(|root| relative == root || relative.starts_with(&format!("{root}/")))
}

fn ends_with_ignore_case(text: &str, suffix: &str) -> bool {
    text.to_lowercase().ends_with(suffix)
}

fn starts_with_ignore_case(text: &str, prefix: &str) -> bool {
    text.as_bytes().get(..prefix.len()).is_some_and(|p| p.eq_ignore_ascii_case(prefix.as_bytes()))
}

/// The `.tar.zst` being written, with the folders already added.
struct Tar {
    builder: tar::Builder<zstd::stream::write::Encoder<'static, std::fs::File>>,
    dirs: Vec<String>,
}

impl Tar {
    /// `ensureArchiveParents`.
    fn parents(&mut self, path: &str) -> Result<()> {
        let parts: Vec<&str> = path.trim_matches('/').split('/').collect();
        let mut current = String::new();
        for part in &parts[..parts.len().saturating_sub(1)] {
            if part.is_empty() {
                continue;
            }
            current = if current.is_empty() { (*part).to_owned() } else { format!("{current}/{part}") };
            self.dir_once(&current)?;
        }
        Ok(())
    }
    fn dir_once(&mut self, path: &str) -> Result<()> {
        let entry = format!("{path}/");
        if !self.dirs.contains(&entry) {
            append(&mut self.builder, &entry, true, 0, now_seconds(), std::io::empty())?;
            self.dirs.push(entry);
        }
        Ok(())
    }
    /// `addDirectoryEntry`.
    fn directory(&mut self, path: &str) -> Result<()> {
        let path = path.trim_matches('/');
        if path.is_empty() {
            return Ok(());
        }
        self.parents(path)?;
        self.dir_once(path)
    }
    fn file(&mut self, path: &str, bytes: &[u8], time: u64) -> Result<()> {
        let path = path.replace('\\', "/");
        let path = path.trim_matches('/');
        if path.trim().is_empty() {
            return Ok(());
        }
        self.parents(path)?;
        append(&mut self.builder, path, false, bytes.len() as u64, time, bytes)
    }
}

/// The zip a nested archive is rewritten into.
struct NestedZip {
    out: zip::ZipWriter<std::io::Cursor<Vec<u8>>>,
    dirs: Vec<String>,
}

impl NestedZip {
    fn options(time: Option<zip::DateTime>) -> zip::write::SimpleFileOptions {
        let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        match time {
            Some(time) => options.last_modified_time(time),
            None => options,
        }
    }
    fn dir_once(&mut self, path: &str) -> Result<()> {
        let entry = format!("{path}/");
        if !self.dirs.contains(&entry) {
            self.out.add_directory(path, Self::options(None))?;
            self.dirs.push(entry);
        }
        Ok(())
    }
    /// `ensureZipParents`.
    fn parents(&mut self, path: &str) -> Result<()> {
        let parts: Vec<&str> = path.trim_matches('/').split('/').collect();
        let mut current = String::new();
        for part in &parts[..parts.len().saturating_sub(1)] {
            if part.is_empty() {
                continue;
            }
            current = if current.is_empty() { (*part).to_owned() } else { format!("{current}/{part}") };
            self.dir_once(&current)?;
        }
        Ok(())
    }
    /// `addZipDirectoryEntry`.
    fn directory(&mut self, path: &str) -> Result<()> {
        let path = path.trim_matches('/');
        if path.is_empty() {
            return Ok(());
        }
        self.parents(path)?;
        self.dir_once(path)
    }
    fn file(&mut self, path: &str, bytes: &[u8], time: Option<zip::DateTime>) -> Result<()> {
        self.parents(path)?;
        self.out.start_file(path, Self::options(time))?;
        self.out.write_all(bytes)?;
        Ok(())
    }
}

/// Pictures and sounds run through the encoders on up to 8 threads, as
/// `preprocessAssetInputsInParallel`. `read` gives the bytes of input `index`.
fn preprocess(
    ctx: &Context,
    media: &dyn Media,
    names: &[String],
    read: &(dyn Fn(usize) -> Result<Vec<u8>> + Sync),
    progress: &(dyn Fn(usize, usize, &str) + Sync),
) -> Result<BTreeMap<String, Vec<u8>>> {
    if names.is_empty() {
        return Ok(BTreeMap::new());
    }
    let (next, done) = (AtomicUsize::new(0), AtomicUsize::new(0));
    let results: Mutex<BTreeMap<String, Vec<u8>>> = Mutex::new(BTreeMap::new());
    let failure: Mutex<Option<anyhow::Error>> = Mutex::new(None);
    std::thread::scope(|scope| {
        for _ in 0..names.len().min(8) {
            scope.spawn(|| {
                loop {
                    let index = next.fetch_add(1, Ordering::SeqCst);
                    if index >= names.len() || failure.lock().unwrap().is_some() {
                        return;
                    }
                    let name = &names[index];
                    let processed = (|| -> Result<Vec<u8>> {
                        ctx.cancel.check()?;
                        let bytes = read(index)?;
                        if name.to_lowercase().ends_with(".png") { Ok(media.png(bytes, name)) } else { media.ogg(bytes, name) }
                    })();
                    match processed {
                        Ok(bytes) => {
                            results.lock().unwrap().insert(name.clone(), bytes);
                            progress(done.fetch_add(1, Ordering::SeqCst) + 1, names.len(), name);
                        }
                        Err(error) => {
                            failure.lock().unwrap().get_or_insert(error);
                            return;
                        }
                    }
                }
            });
        }
    });
    if let Some(error) = failure.into_inner().unwrap() {
        return Err(error);
    }
    Ok(results.into_inner().unwrap())
}

fn is_asset(lower: &str) -> bool {
    lower.ends_with(".png") || lower.ends_with(".ogg")
}

/// What `readResourcepackFile` and `readResourcepackEntry` share: a file of a resource pack,
/// or `None` when it is over the size limit before or after processing.
fn resourcepack_bytes(media: &dyn Media, lower: &str, name: &str, size: u64, preprocessed: Option<&Vec<u8>>, read: &dyn Fn() -> Result<Vec<u8>>) -> Result<Option<Vec<u8>>> {
    let ogg = lower.ends_with(".ogg");
    if lower.ends_with(".mp3") {
        return Ok(Some(EMPTY_MP3.to_vec()));
    }
    if !ogg && size > RESOURCEPACK_MAX {
        return Ok(None);
    }
    let processed = match preprocessed {
        Some(bytes) => bytes.clone(),
        None => {
            if size > i32::MAX as u64 {
                return Ok(None);
            }
            let raw = read()?;
            if ogg {
                media.ogg(raw, name)?
            } else if lower.ends_with(".png") {
                media.png(raw, name)
            } else {
                raw
            }
        }
    };
    Ok(Some(processed).filter(|bytes| bytes.len() as u64 <= RESOURCEPACK_MAX))
}

/// A picture, sound or other file outside resource packs, as the last branch of
/// `writeProcessedEntry` treats it.
fn plain_bytes(media: &dyn Media, lower: &str, name: &str, preprocessed: Option<&Vec<u8>>, read: &dyn Fn() -> Result<Vec<u8>>) -> Result<Vec<u8>> {
    if let Some(bytes) = preprocessed.filter(|_| is_asset(lower)) {
        return Ok(bytes.clone());
    }
    if lower.ends_with(".png") {
        Ok(media.png(read()?, name))
    } else if lower.ends_with(".ogg") {
        media.ogg(read()?, name)
    } else if lower.ends_with(".mp3") {
        Ok(EMPTY_MP3.to_vec())
    } else {
        read()
    }
}

/// `processNestedZip`: a zip or jar of the pack rewritten entry by entry. `preserve` keeps a
/// resource pack's entries (only MP3 files are emptied); `mca_only` keeps everything but the
/// region files of saves, which become empty.
fn nested_zip(ctx: &Context, media: &dyn Media, file: &Path, preserve: bool, mca_only: bool) -> Result<Vec<u8>> {
    let label = file.display().to_string();
    let source = Mutex::new(zip::ZipArchive::new(std::fs::File::open(file)?).with_context(|| format!("无法读取{label}"))?);
    struct Entry {
        index: usize,
        relative: String,
        directory: bool,
        size: u64,
        time: Option<zip::DateTime>,
    }
    let mut entries: Vec<Entry> = Vec::new();
    let mut raw_names: Vec<(String, bool)> = Vec::new();
    {
        let mut zip = source.lock().unwrap();
        for index in 0..zip.len() {
            let entry = zip.by_index_raw(index)?;
            let name = crate::import::archive::decode_name(entry.name_raw());
            raw_names.push((name.clone(), entry.is_dir()));
            let relative = name.replace('\\', "/").trim_start_matches('/').to_owned();
            entries.push(Entry { index, relative, directory: entry.is_dir(), size: entry.size(), time: entry.last_modified() });
        }
    }
    let regions = SaveRegions::of(raw_names.iter().filter(|e| !e.1).map(|e| e.0.as_str()));
    let jar = file.extension().is_some_and(|e| e.to_string_lossy().eq_ignore_ascii_case("jar"));
    let read = |index: usize| -> Result<Vec<u8>> {
        let mut zip = source.lock().unwrap();
        let mut bytes = Vec::new();
        zip.by_index(index)?.read_to_end(&mut bytes)?;
        Ok(bytes)
    };
    let processed = if preserve || mca_only {
        BTreeMap::new()
    } else {
        let inputs: Vec<&Entry> = entries
            .iter()
            .filter(|e| !e.directory && !e.relative.trim().is_empty())
            .filter(|e| jar || !policy::contains_cache_directory(&e.relative, false))
            .filter(|e| is_asset(&e.relative.to_lowercase()))
            .collect();
        let names: Vec<String> = inputs.iter().map(|e| e.relative.clone()).collect();
        preprocess(ctx, media, &names, &|i| read(inputs[i].index), &|_, _, _| {})?
    };
    let mut out = NestedZip { out: zip::ZipWriter::new(std::io::Cursor::new(Vec::new())), dirs: Vec::new() };
    for entry in &entries {
        ctx.cancel.check()?;
        let relative = entry.relative.as_str();
        if relative.trim().is_empty() {
            continue;
        }
        let lower = relative.to_lowercase();
        let logged = format!("{label}!/{relative}");
        if !preserve && !mca_only && !jar && policy::contains_cache_directory(relative, entry.directory) {
            log_skip(&logged, entry.directory, "目录名包含cache");
            continue;
        }
        // `writeProcessedNestedZipEntry`.
        if regions.is_save_region(relative, entry.directory) {
            out.file(relative, &[], entry.time)?;
            continue;
        }
        if mca_only {
            if entry.directory { out.directory(relative)? } else { out.file(relative, &read(entry.index)?, entry.time)? }
            continue;
        }
        if !preserve && policy::skip_entry(&lower, entry.directory, !jar, &[]) {
            log_skip(&logged, entry.directory, "压缩包内容过滤规则");
            continue;
        }
        if entry.directory {
            out.directory(relative)?;
            continue;
        }
        let raw = || read(entry.index);
        let bytes = if preserve {
            if lower.ends_with(".mp3") { EMPTY_MP3.to_vec() } else { raw()? }
        } else if relative.split('/').next() == Some("resourcepacks") {
            match resourcepack_bytes(media, &lower, relative, entry.size, processed.get(relative), &raw)? {
                Some(bytes) => bytes,
                None => {
                    log_skip(&logged, false, "资源包文件超过大小限制");
                    continue;
                }
            }
        } else {
            plain_bytes(media, &lower, relative, processed.get(relative), &raw)?
        };
        out.file(relative, &bytes, entry.time)?;
    }
    Ok(out.out.finish()?.into_inner())
}

/// `resolveMatchedSourceFile` and `verifyMatchedExtraSources`: a recognised pack must still be
/// the file that was matched.
fn verify_excluded(ctx: &Context, root: &Path, excluded: &[EmbeddedExtra], phase: &str) -> Result<()> {
    for extra in excluded {
        let relative = &extra.source_relative;
        let normalized = relative.replace('\\', "/");
        let drive = normalized.len() >= 2 && normalized.as_bytes()[0].is_ascii_alphabetic() && normalized.as_bytes()[1] == b':';
        ensure!(
            !normalized.trim().is_empty() && !normalized.starts_with('/') && !drive && normalized.split('/').all(|s| !s.trim().is_empty() && s != "." && s != ".."),
            "客户端额外内容源路径无效: {relative}"
        );
        let mut current = root.to_path_buf();
        for segment in normalized.split('/') {
            current.push(segment);
            ensure!(!std::fs::symlink_metadata(&current).is_ok_and(|m| m.file_type().is_symlink()), "客户端额外内容源路径不能包含软链接: {relative}");
        }
        ensure!(current.is_file(), "已匹配客户端额外内容不存在（{phase}）: {relative}");
        ensure!(ctx.sha1(&current)?.eq_ignore_ascii_case(&extra.sha1), "客户端资源文件在{phase}发生变化: {relative}");
    }
    Ok(())
}

/// `findOversizedUnpackedResourcepacks`: unpacked resource packs over the limit, as paths in
/// the pack.
fn oversized_resourcepacks(root: &Path) -> Result<Vec<String>> {
    let mut out = Vec::new();
    for folder in ["resourcepacks", "overrides/resourcepacks", "client-overrides/resourcepacks"] {
        let dir = root.join(folder);
        if !dir.is_dir() {
            continue;
        }
        for (pack, is_dir) in reader::children(&dir)? {
            if !is_dir {
                continue;
            }
            let mut total = 0u64;
            for file in reader::walk_files(&pack)? {
                total += std::fs::metadata(&file)?.len();
            }
            if total > RESOURCEPACK_MAX {
                out.push(reader::relative(root, &pack));
            }
        }
    }
    Ok(out)
}

/// `isResourcepackZipPath`.
fn is_resourcepack_zip(relative: &str) -> bool {
    let normalized = client_entry_path(relative, true).map(|p| p.relative).unwrap_or_else(|| relative.replace('\\', "/").trim_start_matches('/').to_owned());
    if !starts_with_ignore_case(&normalized, "resourcepacks/") {
        return false;
    }
    let pack = normalized["resourcepacks/".len()..].split('/').next().unwrap_or_default();
    ends_with_ignore_case(pack, ".zip")
}

/// The pack's folders and files in `walkTopDown` order; a folder whose path has a `cache`
/// folder is not entered.
fn walk(root: &Path) -> Result<Vec<(PathBuf, String, bool)>> {
    fn visit(root: &Path, dir: &Path, out: &mut Vec<(PathBuf, String, bool)>) -> Result<()> {
        for (path, is_dir) in reader::children(dir)? {
            let relative = reader::relative(root, &path);
            if is_dir {
                if policy::contains_cache_directory(&relative, true) {
                    log_skip(&relative, true, "目录名包含cache");
                    continue;
                }
                out.push((path.clone(), relative, true));
                visit(root, &path, out)?;
            } else {
                out.push((path, relative, false));
            }
        }
        Ok(())
    }
    let mut out = Vec::new();
    visit(root, root, &mut out)?;
    Ok(out)
}

fn modified_seconds(path: &Path) -> u64 {
    std::fs::metadata(path).map(|m| crate::rdipack2::seconds(&m)).unwrap_or(0)
}

fn percent(fraction: f32) -> String {
    format!("{}%", (fraction.clamp(0.0, 1.0) * 100.0) as u32)
}

fn file_name(path: &str) -> &str {
    path.rsplit('/').next().filter(|n| !n.trim().is_empty()).unwrap_or(path)
}

fn write(ctx: &Context, media: &dyn Media, input: &Input, target: &Path) -> Result<()> {
    let root = input.root;
    let entries = walk(root)?;
    let regions = SaveRegions::of(entries.iter().filter(|e| !e.2).map(|e| e.1.as_str()));
    let server_regions = SaveRegions::of(input.server_extras.iter().map(|e| e.relative.as_str()));
    let oversized = oversized_resourcepacks(root)?;
    let all_extras = reader::all_extra_candidates(root)?;
    let effective: Vec<String> = reader::extra_candidates(ctx, root)?.into_iter().map(|c| c.source_relative).collect();
    // Shader settings (`<pack>.zip.txt`): per game path the highest layer wins.
    let mut shader_configs: Vec<(String, u8)> = Vec::new();
    for (_, relative, is_dir) in &entries {
        if *is_dir || !ends_with_ignore_case(relative, ".zip.txt") {
            continue;
        }
        let Some(mapped) = client_entry_path(relative, false) else { continue };
        let key = mapped.relative.to_lowercase();
        match shader_configs.iter_mut().find(|(k, _)| *k == key) {
            Some(slot) => slot.1 = slot.1.max(mapped.priority),
            None => shader_configs.push((key, mapped.priority)),
        }
    }
    let mut shadowed: Vec<String> = all_extras.iter().map(|c| c.source_relative.clone()).filter(|s| !effective.contains(s)).collect();
    for (_, relative, is_dir) in &entries {
        if *is_dir || !ends_with_ignore_case(relative, ".zip.txt") {
            continue;
        }
        let Some(mapped) = client_entry_path(relative, false) else { continue };
        let top = shader_configs.iter().find(|(k, _)| *k == mapped.relative.to_lowercase()).map(|(_, p)| *p).unwrap_or(0);
        if mapped.priority < top && !shadowed.contains(relative) {
            shadowed.push(relative.clone());
        }
    }
    let mut excluded_sources: Vec<String> = input.excluded.iter().map(|e| e.source_relative.clone()).collect();
    excluded_sources.extend(shadowed.iter().cloned());
    verify_excluded(ctx, root, input.excluded, "预处理前")?;
    let mut retained: Vec<String> = Vec::new();
    let shader_txt = input
        .client_extras
        .iter()
        .filter(|c| c.kind == Kind::ShaderPack)
        .filter_map(|c| c.target().ok())
        .chain(input.excluded.iter().filter(|e| e.content.kind == Kind::ShaderPack).map(|e| e.source_relative.clone()));
    for path in shader_txt {
        if let Some(mapped) = client_entry_path(&format!("{path}.txt"), true) {
            let key = mapped.relative.to_lowercase();
            if !retained.contains(&key) {
                retained.push(key);
            }
        }
    }

    ctx.phase("正在预处理整合包资源文件");
    // `isExcludedBeforePreprocess`: what the archive leaves out is not encoded either.
    let assets: Vec<&(PathBuf, String, bool)> = entries
        .iter()
        .filter(|(_, relative, is_dir)| {
            if *is_dir || relative.trim().is_empty() || !is_asset(&relative.to_lowercase()) {
                return false;
            }
            if policy::contains_cache_directory(relative, false) || under(relative, &excluded_sources) || under(relative, &oversized) {
                return false;
            }
            let normalized = client_entry_path(relative, true).map(|p| p.relative).unwrap_or_else(|| relative.replace('\\', "/").trim_start_matches('/').to_owned());
            !starts_with_ignore_case(&normalized, "shaderpacks/")
        })
        .collect();
    let names: Vec<String> = assets.iter().map(|a| a.1.clone()).collect();
    let processed = preprocess(ctx, media, &names, &|i| Ok(std::fs::read(&assets[i].0)?), &|done, total, current| {
        let fraction = if total == 0 { 1.0 } else { done as f32 / total as f32 };
        (ctx.progress)(Progress::Percent(format!("正在预处理资源文件{}({done}/{total}) {}", file_name(current), percent(fraction)), fraction * 0.5));
    })?;

    let file = std::fs::File::create(target)?;
    let mut out = Tar { builder: tar::Builder::new(zstd::stream::write::Encoder::new(file, 9)?), dirs: Vec::new() };
    let total = (entries.len() + input.server_extras.len()).max(1);
    let mut written = 0usize;
    let wrote = |written: usize, relative: &str| {
        let fraction = written as f32 / total as f32;
        (ctx.progress)(Progress::Percent(format!("正在写入整合包文件{}({written}/{total}) {}", file_name(relative), percent(fraction)), 0.5 + fraction * 0.5));
    };
    for (path, relative, is_dir) in &entries {
        ctx.cancel.check()?;
        let (relative, is_dir) = (relative.as_str(), *is_dir);
        if relative.trim().is_empty() {
            continue;
        }
        if input.excluded.iter().any(|e| e.source_relative.replace('\\', "/") == relative) {
            log_skip(relative, is_dir, "已匹配平台内容");
            continue;
        }
        if under(relative, &shadowed) {
            log_skip(relative, is_dir, "已由overrides中的内容覆盖");
            continue;
        }
        if under(relative, &oversized) || (!is_dir && is_resourcepack_zip(relative) && std::fs::metadata(path)?.len() > RESOURCEPACK_MAX) {
            log_skip(relative, is_dir, "资源包超过大小限制");
            continue;
        }
        entry(ctx, media, &mut out, path, relative, is_dir, &processed, &retained, &regions)?;
        written += 1;
        wrote(written, relative);
    }
    for extra in input.server_extras {
        ctx.cancel.check()?;
        let relative = format!("server/{}", extra.relative.trim_start_matches('/'));
        ensure!(extra.source.is_file(), "服务端额外文件不存在: {}", extra.source.display());
        let source_name = extra.source.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        if policy::is_disabled(&relative, false) || policy::is_disabled(&source_name, false) {
            log_skip(&relative, false, "文件已禁用（.disabled）");
            continue;
        }
        if policy::contains_cache_directory(&extra.relative, false) {
            log_skip(&relative, false, "所在目录名包含cache");
            continue;
        }
        let time = modified_seconds(&extra.source);
        if server_regions.is_save_region(&extra.relative, false) {
            out.file(&relative, &[], time)?;
            continue;
        }
        let archive = extra.source.extension().is_some_and(|e| ["zip", "jar"].iter().any(|x| e.to_string_lossy().eq_ignore_ascii_case(x)));
        let bytes = if archive { nested_zip(ctx, media, &extra.source, false, true)? } else { std::fs::read(&extra.source)? };
        out.file(&relative, &bytes, time)?;
        written += 1;
        wrote(written, &relative);
    }
    out.builder.into_inner()?.finish()?.sync_all()?;
    verify_excluded(ctx, root, input.excluded, "打包完成前")
}

/// `writeProcessedEntry`: one folder or file of the pack.
#[allow(clippy::too_many_arguments)]
fn entry(
    ctx: &Context,
    media: &dyn Media,
    out: &mut Tar,
    path: &Path,
    relative: &str,
    is_dir: bool,
    processed: &BTreeMap<String, Vec<u8>>,
    retained: &[String],
    regions: &SaveRegions,
) -> Result<()> {
    let lower = relative.to_lowercase();
    if policy::is_disabled(relative, is_dir) {
        log_skip(relative, is_dir, "文件已禁用（.disabled）");
        return Ok(());
    }
    if policy::skip_entry(&lower, is_dir, true, retained) {
        log_skip(relative, is_dir, "上传过滤规则");
        return Ok(());
    }
    if is_dir {
        return out.directory(relative);
    }
    let time = modified_seconds(path);
    if regions.is_save_region(relative, false) {
        return out.file(relative, &[], time);
    }
    let effective = client_entry_path(relative, true).map(|p| p.relative).unwrap_or_else(|| relative.to_owned());
    let effective_lower = effective.to_lowercase();
    let nested = lower.ends_with(".zip") || lower.ends_with(".jar");
    let read = || -> Result<Vec<u8>> { Ok(std::fs::read(path)?) };
    if effective_lower == "resourcepacks" || effective_lower.starts_with("resourcepacks/") {
        let bytes = if nested {
            Some(nested_zip(ctx, media, path, starts_with_ignore_case(&effective, "resourcepacks/"), false)?).filter(|b| b.len() as u64 <= RESOURCEPACK_MAX)
        } else {
            resourcepack_bytes(media, &lower, relative, std::fs::metadata(path)?.len(), processed.get(relative), &read)?
        };
        return match bytes {
            Some(bytes) => out.file(relative, &bytes, time),
            None => {
                log_skip(relative, false, "资源包文件超过大小限制");
                Ok(())
            }
        };
    }
    let bytes = if nested { nested_zip(ctx, media, path, false, false)? } else { plain_bytes(media, &lower, relative, processed.get(relative), &read)? };
    out.file(relative, &bytes, time)
}

/// `buildUploadArchive`: writes `<name>_<time>.tar.zst` into the work folder and returns it.
/// A failed build leaves no file.
pub fn build(ctx: &Context, media: &dyn Media, input: &Input) -> Result<PathBuf> {
    std::fs::create_dir_all(&ctx.work_dir)?;
    let name = if input.base_name.trim().is_empty() { "modpack" } else { input.base_name };
    let millis = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
    let target = ctx.work_dir.join(format!("{name}_{millis}.tar.zst"));
    let built = write(ctx, media, input, &target).and_then(|()| {
        let size = std::fs::metadata(&target)?.len();
        if size > UPLOAD_MAX_MB * 1024 * 1024 {
            bail!("整合包打包后为{}，超过{UPLOAD_MAX_MB}MB上限，请删除不需要的大文件后重试", human_size(size));
        }
        (ctx.progress)(Progress::Percent("整合包打包完成".into(), 1.0));
        Ok(())
    });
    match built {
        Ok(()) => Ok(target),
        Err(error) => {
            if let Err(cleanup) = std::fs::remove_file(&target)
                && cleanup.kind() != std::io::ErrorKind::NotFound
            {
                tracing::warn!(path = %target.display(), error = %cleanup, "无法清理失败的整合包暂存");
            }
            Err(error)
        }
    }
}

/// Kotlin `humanFileSize`.
pub(super) fn human_size(bytes: u64) -> String {
    if bytes < 1024 {
        return format!("{bytes}B");
    }
    let mut value = bytes as f64 / 1024.0;
    for unit in ["KB", "MB"] {
        if value < 1024.0 {
            return format!("{value:.1}{unit}");
        }
        value /= 1024.0;
    }
    format!("{value:.1}GB")
}

#[cfg(test)]
pub(super) fn human_size_for_test(bytes: u64) -> String {
    human_size(bytes)
}
