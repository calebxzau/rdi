//! U4 batch b2: .rdipack2 import and export (Kotlin RdiPack2ArchiveService). A tar.zst whose
//! first entry is the `<modpack id>_<version name>/` directory, then that version's files.
//! Blocking task bodies; no Slint types.
use crate::{
    digests::{self, Algorithm, Digest},
    tasks::Context,
};
use anyhow::{Context as _, Result, bail, ensure};
use std::{
    collections::HashMap,
    io::{Read, Write},
    path::{Path, PathBuf},
    time::UNIX_EPOCH,
};

const MAX_ENTRIES: u64 = 100_000;
const MAX_BYTES: u64 = 20 * 1024 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq)]
pub struct Identity {
    /// Lowercase hex, as `ObjectId.toHexString`.
    pub modpack_id: String,
    pub version_name: String,
    /// The root directory name as written in the archive.
    pub root_name: String,
}
impl Identity {
    pub fn version_id(&self) -> String {
        format!("{}_{}", self.modpack_id, self.version_name)
    }
}

/// `parseRdiPack2Root`.
pub fn parse_root(raw: &str) -> Result<Identity> {
    let invalid = || anyhow::anyhow!("RDI整合包根目录路径无效：{raw}");
    let root = raw.strip_suffix('/').ok_or_else(invalid)?;
    let (id, version) = root.split_once('_').ok_or_else(invalid)?;
    ensure!(id.len() == 24 && id.bytes().all(|b| b.is_ascii_hexdigit()) && !version.is_empty(), invalid());
    ensure!(
        !version.trim().is_empty()
            && !version.chars().any(char::is_control)
            && !version.contains(['/', '\\', ':'])
            && version != "."
            && version != "..",
        "整合包版本名无效"
    );
    Ok(Identity { modpack_id: id.to_ascii_lowercase(), version_name: version.to_owned(), root_name: root.to_owned() })
}

/// `normalizeRdiPack2PayloadPath`.
fn payload_path(raw: &str, directory: bool) -> Result<String> {
    let value = raw.replace('\\', "/");
    let value = if directory { value.trim_end_matches('/').to_owned() } else { value };
    ensure!(
        !value.trim().is_empty() && !value.starts_with('/') && !value.contains(':') && !value.chars().any(char::is_control),
        "归档路径无效：{raw}"
    );
    ensure!(value.split('/').all(|s| !s.trim().is_empty() && s != "." && s != ".."), "归档路径越界：{raw}");
    Ok(value)
}

/// `RdiPack2CollisionTracker`: Windows and macOS file names ignore case, so two spellings of
/// one path, a duplicate, or a file where a directory goes would clash on disk.
#[derive(Default)]
struct Collisions {
    spellings: HashMap<String, String>,
    entries: HashMap<String, bool>,
}
impl Collisions {
    fn register(&mut self, path: &str, directory: bool) -> Result<()> {
        let mut actual = String::new();
        let mut key = String::new();
        for component in path.split('/') {
            if !actual.is_empty() {
                actual.push('/');
            }
            actual.push_str(component);
            key = actual.to_lowercase();
            if let Some(previous) = self.spellings.get(&key) {
                ensure!(previous == &actual, "归档路径大小写冲突：{path}");
            }
            self.spellings.insert(key.clone(), actual.clone());
        }
        ensure!(!self.entries.contains_key(&key), "归档包含重复路径：{path}");
        ensure!(
            !self.entries.iter().any(|(k, dir)| !dir && key.starts_with(&format!("{k}/"))),
            "归档包含文件/目录冲突：{path}"
        );
        if !directory {
            ensure!(!self.entries.keys().any(|k| k.starts_with(&format!("{key}/"))), "归档包含文件/目录冲突：{path}");
        }
        self.entries.insert(key, directory);
        Ok(())
    }
}

/// `excludedRdiPack2Path`: logs, crash reports, saves, screenshots and log files stay behind.
pub fn excluded(path: &str, directory: bool) -> bool {
    let top = path.split('/').next().unwrap_or_default();
    if ["logs", "crash-reports", "saves", "screenshots", ".mixin.out"].iter().any(|t| top.eq_ignore_ascii_case(t))
        && (directory || path.contains('/'))
    {
        return true;
    }
    if directory {
        return false;
    }
    let name = path.rsplit('/').next().unwrap_or_default().to_ascii_lowercase();
    name.ends_with(".log") || name.ends_with(".log.gz")
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Platform {
    CurseForge,
    Modrinth,
    Github,
    RdiCore,
}

/// `parseLegacyContentName`: `name_cf_<murmur2>.jar`, `_mr_<sha1>`, `_github_<sha1|sha256>`,
/// or the RDI core jar.
fn legacy_name(file_name: &str) -> Option<(Platform, String)> {
    let lower = file_name.to_ascii_lowercase();
    let stem = lower.strip_suffix(".jar").map(|s| &file_name[..s.len()])?;
    let prefix = "rdi-5-mc-client-";
    if stem.len() > prefix.len() && stem[..prefix.len()].eq_ignore_ascii_case(prefix) {
        return Some((Platform::RdiCore, String::new()));
    }
    let stem_lower = stem.to_ascii_lowercase();
    let (index, marker) = ["cf", "mr", "github"]
        .iter()
        .filter_map(|m| stem_lower.rfind(&format!("_{m}_")).filter(|&i| i > 0).map(|i| (i, *m)))
        .max_by_key(|(i, _)| *i)?;
    let hash = &stem[index + marker.len() + 2..];
    if hash.trim().is_empty() {
        return None;
    }
    let platform = match marker {
        "cf" => Platform::CurseForge,
        "mr" => Platform::Modrinth,
        _ => Platform::Github,
    };
    Some((platform, hash.to_owned()))
}

/// `isRdiPack2DirectCacheCandidate`: a named jar directly in `mods/`.
fn cache_candidate(relative: &str) -> Option<(Platform, String)> {
    let name = relative.strip_prefix("mods/")?;
    if name.contains('/') {
        return None;
    }
    legacy_name(name)
}

/// `verifiedDigest`: the digests to cache the jar under, or `None` when it does not match its
/// name (it is then kept in the pack but not cached).
fn verified_digests(path: &Path, platform: Platform, embedded: &str, ctx: &Context) -> Result<Option<Vec<Digest>>> {
    let value = embedded.trim().to_ascii_lowercase();
    let hash = |algorithm| digests::hash(path, algorithm, &ctx.cancel);
    let mut out = match platform {
        Platform::CurseForge => {
            let expected: u32 = value.parse().context("CurseForge文件名摘要无效")?;
            if hash(Algorithm::Murmur2)? != expected.to_string() {
                return Ok(None);
            }
            vec![Digest::new(Algorithm::Murmur2, &expected.to_string())?]
        }
        Platform::Modrinth => {
            ensure!(value.len() == 40 && value.bytes().all(|b| b.is_ascii_hexdigit()), "Modrinth文件名摘要无效");
            if hash(Algorithm::Sha1)? != value {
                return Ok(None);
            }
            vec![Digest::new(Algorithm::Sha1, &value)?]
        }
        Platform::Github => match value.len() {
            40 if hash(Algorithm::Sha1)? == value => vec![Digest::new(Algorithm::Sha1, &value)?],
            64 if hash(Algorithm::Sha256)? == value => vec![Digest::new(Algorithm::Sha256, &value)?],
            40 | 64 => return Ok(None),
            _ => bail!("GitHub文件名摘要无效"),
        },
        Platform::RdiCore => vec![],
    };
    // The cache keeps a SHA-1 entry for every file, as `Store::materialize` does.
    if !out.iter().any(|d| d.algorithm == Algorithm::Sha1) {
        out.push(Digest::new(Algorithm::Sha1, &hash(Algorithm::Sha1)?)?);
    }
    Ok(Some(out))
}

fn limits(entries: u64, bytes: u64) -> Result<()> {
    ensure!(entries <= MAX_ENTRIES, "整合包条目数量超过限制：{MAX_ENTRIES}");
    ensure!(bytes <= MAX_BYTES, "整合包文件总大小超过限制：{MAX_BYTES}");
    Ok(())
}

fn zstd_tar(file: &Path) -> Result<tar::Archive<zstd::stream::read::Decoder<'static, std::io::BufReader<std::fs::File>>>> {
    let file = std::fs::File::open(file).context("无法打开RDI整合包文件")?;
    Ok(tar::Archive::new(zstd::stream::read::Decoder::new(file).context("RDI整合包文件无效")?))
}

fn supported(kind: tar::EntryType) -> bool {
    !(kind.is_symlink() || kind.is_hard_link() || kind.is_character_special() || kind.is_block_special() || kind.is_fifo() || kind.is_gnu_sparse())
}

/// `readRdiPack2Identity`: the root directory, read without visiting the payload.
pub fn read_identity(file: &Path) -> Result<Identity> {
    ensure!(
        file.is_file() && file.to_string_lossy().to_ascii_lowercase().ends_with(".rdipack2"),
        "RDI整合包文件无效"
    );
    let mut archive = zstd_tar(file)?;
    let mut entries = archive.entries().context("RDI整合包文件无效")?;
    let entry = entries.next().context("RDI整合包缺少根目录")?.context("RDI整合包文件无效")?;
    let kind = entry.header().entry_type();
    ensure!(kind.is_dir() && supported(kind), "整合包根目录必须是普通目录");
    parse_root(&String::from_utf8_lossy(&entry.path_bytes()).replace('\\', "/"))
}

/// The server side of an import, checked before any file is written.
pub struct Checks<'a> {
    pub server_matches: &'a dyn Fn(&Identity) -> Result<()>,
    pub running: &'a dyn Fn(&str) -> bool,
}

fn mtime(entry_mtime: u64) -> std::time::SystemTime {
    UNIX_EPOCH + std::time::Duration::from_secs(entry_mtime)
}

/// Blocking task body of `importRdiPack2`: extracts into a staging directory next to the
/// target, caches the named mod jars, then moves the directory into place.
pub fn import(ctx: &Context, file: &Path, identity: &Identity, versions: &Path, cache: &Path, checks: &Checks) -> Result<String> {
    ctx.progress("正在检查整合包", None);
    (checks.server_matches)(identity)?;
    let target = versions.join(identity.version_id());
    ensure!(!target.exists(), "目标整合包已存在");
    ensure!(!(checks.running)(&identity.version_id()), "整合包正在运行，不能导入");
    std::fs::create_dir_all(versions).context("创建整合包目录失败")?;
    let staging = versions.join(format!("rdipack2-stage-{}", uuid::Uuid::now_v7()));
    std::fs::create_dir(&staging).context("创建临时目录失败")?;
    let result = extract(ctx, file, identity, &staging, cache).and_then(|count| {
        ensure!(staging.join("mods").is_dir(), "导入整合包缺少mods目录");
        ensure!(!target.exists(), "目标整合包在导入期间已存在");
        ctx.cancel.check()?;
        std::fs::rename(&staging, &target).context("移动整合包目录失败")?;
        Ok(count)
    });
    match result {
        Ok(count) => Ok(format!("导入完成，共{count}个条目")),
        Err(error) => {
            // Only the staging directory this task created is removed.
            if let Err(cleanup) = std::fs::remove_dir_all(&staging)
                && cleanup.kind() != std::io::ErrorKind::NotFound
            {
                tracing::warn!(error = %cleanup, "清理导入临时目录失败");
            }
            Err(error)
        }
    }
}

fn extract(ctx: &Context, file: &Path, identity: &Identity, staging: &Path, cache: &Path) -> Result<u64> {
    let mut archive = zstd_tar(file)?;
    let mut collisions = Collisions::default();
    let mut recognized: Vec<(PathBuf, Platform, String)> = Vec::new();
    let (mut count, mut payload_entries, mut payload_bytes) = (0u64, 0u64, 0u64);
    let mut first = true;
    let prefix = format!("{}/", identity.root_name);
    for item in archive.entries().context("RDI整合包文件无效")? {
        ctx.cancel.check()?;
        let mut item = item.context("RDI整合包文件无效")?;
        let kind = item.header().entry_type();
        let raw = String::from_utf8_lossy(&item.path_bytes()).replace('\\', "/");
        ensure!(supported(kind), "归档包含不支持的特殊文件：{raw}");
        let directory = kind.is_dir() || raw.ends_with('/');
        let time = item.header().mtime().unwrap_or(0);
        if first {
            first = false;
            ensure!(directory, "整合包根目录必须是归档第一个条目");
            ensure!(&parse_root(&raw)? == identity, "整合包根目录在任务开始前发生变化");
            count += 1;
            continue;
        }
        if directory && raw == prefix {
            continue;
        }
        let relative = raw.strip_prefix(&prefix).with_context(|| format!("归档路径不在根目录内：{raw}"))?;
        let relative = payload_path(relative, directory)?;
        collisions.register(&relative, directory)?;
        let size = if directory { 0 } else { item.size() };
        payload_entries += 1;
        payload_bytes = payload_bytes.checked_add(size).context("整合包文件总大小统计无效")?;
        limits(payload_entries, payload_bytes)?;
        let path = staging.join(&relative);
        ensure!(path.starts_with(staging), "归档路径越界：{relative}");
        if directory {
            std::fs::create_dir_all(&path)?;
        } else {
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut out = std::fs::OpenOptions::new().write(true).create_new(true).open(&path)
                .with_context(|| format!("写入文件失败：{relative}"))?;
            let copied = copy_cancellable(&mut item, &mut out, ctx)?;
            ensure!(copied == size, "归档文件内容不完整");
            let _ = out.set_modified(mtime(time));
            if let Some((platform, hash)) = cache_candidate(&relative) {
                recognized.push((path.clone(), platform, hash));
            }
        }
        count += 1;
        let name = relative.rsplit('/').next().unwrap_or_default();
        ctx.progress(format!("导入 {name}"), None);
    }
    ensure!(!first && count > 0, "RDI整合包缺少根目录");
    std::fs::create_dir_all(staging.join("mods"))?;
    for (index, (path, platform, hash)) in recognized.iter().enumerate() {
        ctx.cancel.check()?;
        ctx.progress(format!("正在登记下载缓存 {}/{}", index + 1, recognized.len()), Some((index + 1) as f32 / recognized.len() as f32));
        match verified_digests(path, *platform, hash, ctx) {
            Ok(Some(digests)) => {
                if let Err(error) = crate::content_store::adopt(cache, path, &digests) {
                    tracing::warn!(error = %format!("{error:#}"), "无法登记下载缓存");
                }
            }
            Ok(None) => tracing::info!(file = %path.display(), "文件与文件名摘要不符，不登记下载缓存"),
            Err(error) => return Err(error),
        }
    }
    Ok(count)
}

fn copy_cancellable(input: &mut dyn Read, output: &mut dyn Write, ctx: &Context) -> Result<u64> {
    let mut buffer = vec![0u8; 1 << 16];
    let mut copied = 0u64;
    loop {
        ctx.cancel.check()?;
        let n = input.read(&mut buffer).context("RDI整合包文件无效")?;
        if n == 0 {
            return Ok(copied);
        }
        output.write_all(&buffer[..n])?;
        copied += n as u64;
    }
}

/// A PAX `path` record, for names ustar cannot hold (long or non-ASCII), as Kotlin's
/// `LONGFILE_POSIX` writes them.
fn pax_record(key: &str, value: &str) -> Vec<u8> {
    let body = format!(" {key}={value}\n");
    let mut length = body.len() + 1;
    while length.to_string().len() + body.len() != length {
        length = length.to_string().len() + body.len();
    }
    format!("{length}{body}").into_bytes()
}

fn set_name(header: &mut tar::Header, name: &[u8]) {
    let field = &mut header.as_old_mut().name;
    field.fill(0);
    field[..name.len()].copy_from_slice(name);
}

fn append<W: Write>(builder: &mut tar::Builder<W>, path: &str, directory: bool, size: u64, time: u64, data: impl Read) -> Result<()> {
    let mut header = tar::Header::new_ustar();
    header.set_entry_type(if directory { tar::EntryType::Directory } else { tar::EntryType::Regular });
    header.set_size(size);
    header.set_mtime(time);
    header.set_mode(if directory { 0o755 } else { 0o644 });
    if path.is_ascii() && path.len() < 100 {
        set_name(&mut header, path.as_bytes());
    } else {
        let record = pax_record("path", path);
        let fallback: String = path.chars().map(|c| if c.is_ascii() && !c.is_control() { c } else { '_' }).take(80).collect();
        let mut pax = tar::Header::new_ustar();
        pax.set_entry_type(tar::EntryType::XHeader);
        pax.set_size(record.len() as u64);
        pax.set_mode(0o644);
        pax.set_mtime(time);
        set_name(&mut pax, format!("PaxHeaders/{}", &fallback[..fallback.len().min(80)]).as_bytes());
        pax.set_cksum();
        builder.append(&pax, record.as_slice())?;
        set_name(&mut header, fallback.as_bytes());
    }
    header.set_cksum();
    builder.append(&header, data)?;
    Ok(())
}

fn seconds(meta: &std::fs::Metadata) -> u64 {
    meta.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0)
}

/// Blocking task body of `exportRdiPack2`: writes a temporary file beside `output`, then
/// replaces it.
pub fn export(ctx: &Context, pack: &Path, version_id: &str, output: &Path) -> Result<()> {
    let root_meta = std::fs::symlink_metadata(pack).context("整合包目录不存在")?;
    ensure!(root_meta.is_dir() && !root_meta.file_type().is_symlink(), "整合包目录不能是符号链接");
    ensure!(!output.starts_with(pack), "导出文件不能位于整合包目录内");
    ctx.progress("正在扫描整合包文件…", None);
    let mut entries: Vec<(String, PathBuf, bool, u64, u64)> = Vec::new();
    let mut stack = vec![pack.to_path_buf()];
    while let Some(dir) = stack.pop() {
        for entry in std::fs::read_dir(&dir).with_context(|| format!("读取目录失败：{}", dir.display()))? {
            ctx.cancel.check()?;
            let entry = entry?;
            let path = entry.path();
            let relative = path.strip_prefix(pack)?.to_string_lossy().replace('\\', "/");
            let meta = std::fs::symlink_metadata(&path)?;
            ensure!(!meta.file_type().is_symlink(), "整合包包含不支持的符号链接：{relative}");
            ensure!(meta.is_dir() || meta.is_file(), "整合包包含不支持的文件：{relative}");
            if excluded(&relative, meta.is_dir()) {
                continue;
            }
            if meta.is_dir() {
                stack.push(path.clone());
            }
            entries.push((relative, path, meta.is_dir(), if meta.is_dir() { 0 } else { meta.len() }, seconds(&meta)));
        }
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));
    let total: u64 = entries.iter().map(|e| e.3).sum();
    limits(entries.len() as u64, total)?;
    let files = entries.iter().filter(|e| !e.2).count();
    let parent = output.parent().context("导出位置无效")?;
    std::fs::create_dir_all(parent)?;
    let name = output.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let temp = parent.join(format!(".{name}.{}.tmp", uuid::Uuid::now_v7()));
    let written = (|| -> Result<()> {
        let file = std::fs::File::create(&temp).context("创建导出文件失败")?;
        let mut builder = tar::Builder::new(zstd::stream::write::Encoder::new(file, 9)?);
        append(&mut builder, &format!("{version_id}/"), true, 0, seconds(&root_meta), std::io::empty())?;
        let (mut done_files, mut done_bytes) = (0usize, 0u64);
        for (relative, path, directory, size, time) in &entries {
            ctx.cancel.check()?;
            let archived = format!("{version_id}/{relative}");
            if *directory {
                append(&mut builder, &format!("{archived}/"), true, 0, *time, std::io::empty())?;
                continue;
            }
            ctx.progress(format!("正在写入 {relative}"), Some(if total == 0 { 0. } else { done_bytes as f32 / total as f32 }));
            let source = std::fs::File::open(path).with_context(|| format!("读取文件失败：{relative}"))?;
            // A file that changes size while being read would corrupt the archive.
            ensure!(source.metadata()?.len() == *size, "文件在导出期间被修改：{relative}");
            append(&mut builder, &archived, false, *size, *time, CancelRead { inner: source, ctx })?;
            done_files += 1;
            done_bytes += size;
        }
        ctx.progress(format!("正在完成写入（{done_files}/{files}）"), Some(1.));
        builder.into_inner()?.finish()?.sync_all()?;
        Ok(())
    })();
    let committed = written.and_then(|()| {
        ctx.cancel.check()?;
        std::fs::rename(&temp, output).context("保存导出文件失败")
    });
    if committed.is_err() {
        let _ = std::fs::remove_file(&temp);
    }
    committed
}

struct CancelRead<'a> {
    inner: std::fs::File,
    ctx: &'a Context,
}
impl Read for CancelRead<'_> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.ctx.cancel.cancelled() {
            return Err(std::io::Error::other("任务已取消"));
        }
        self.inner.read(buf)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roots_and_payload_paths() {
        let id = parse_root("6650A1B2C3D4E5F601234567_版本1.0/").unwrap();
        assert_eq!((id.modpack_id.as_str(), id.version_name.as_str()), ("6650a1b2c3d4e5f601234567", "版本1.0"));
        assert_eq!(id.root_name, "6650A1B2C3D4E5F601234567_版本1.0");
        for bad in ["6650a1b2c3d4e5f601234567_1.0", "xyz_1/", "6650a1b2c3d4e5f601234567_../", "6650a1b2c3d4e5f601234567_a:b/"] {
            assert!(parse_root(bad).is_err(), "{bad}");
        }
        assert_eq!(payload_path("mods\\a.jar", false).unwrap(), "mods/a.jar");
        assert_eq!(payload_path("config/", true).unwrap(), "config");
        for bad in ["/etc", "a/../b", "C:/x", "a//b", "./a"] {
            assert!(payload_path(bad, false).is_err(), "{bad}");
        }
    }
    #[test]
    fn collisions_follow_kotlin() {
        let mut c = Collisions::default();
        c.register("config", true).unwrap();
        c.register("config/a.toml", false).unwrap();
        assert!(c.register("Config/b.toml", false).unwrap_err().to_string().contains("大小写冲突"));
        assert!(c.register("config/a.toml", false).unwrap_err().to_string().contains("重复路径"));
        assert!(c.register("config/a.toml/x", false).unwrap_err().to_string().contains("文件/目录冲突"));
        let mut d = Collisions::default();
        d.register("x/y", false).unwrap();
        assert!(d.register("x", false).unwrap_err().to_string().contains("文件/目录冲突"));
    }
    #[test]
    fn exclusions_and_names() {
        assert!(excluded("logs/latest.log", false) && excluded("saves", true) && excluded("config/a.log", false));
        assert!(!excluded("saves", false) && !excluded("config", true) && !excluded("mods/a.jar", false));
        assert_eq!(legacy_name("jei_cf_123.jar"), Some((Platform::CurseForge, "123".into())));
        assert_eq!(legacy_name("a_mr_x_github_abc.JAR"), Some((Platform::Github, "abc".into())));
        assert_eq!(legacy_name("rdi-5-mc-client-1.20.1-forge.jar"), Some((Platform::RdiCore, String::new())));
        assert_eq!(legacy_name("_cf_1.jar"), None);
        assert_eq!(legacy_name("plain.jar"), None);
        assert!(cache_candidate("mods/a_cf_1.jar").is_some() && cache_candidate("mods/sub/a_cf_1.jar").is_none());
        let record = pax_record("path", "a");
        assert_eq!(record, b"9 path=a\n", "the length counts itself");
        assert_eq!(pax_record("path", &"x".repeat(95)).len(), 105);
    }
    /// Manual interop check: `RDIPACK2_SAMPLE` names an archive Compose wrote, `RDIPACK2_OUT` a
    /// path for an export that other tar readers can then check.
    #[test]
    #[ignore]
    fn interop_with_compose_archives() {
        if let Ok(sample) = std::env::var("RDIPACK2_SAMPLE") {
            let identity = read_identity(Path::new(&sample)).unwrap();
            let mut archive = zstd_tar(Path::new(&sample)).unwrap();
            let (mut entries, mut outside) = (0, 0);
            for item in archive.entries().unwrap() {
                let item = item.unwrap();
                let path = String::from_utf8_lossy(&item.path_bytes()).into_owned();
                entries += 1;
                if !path.starts_with(&identity.root_name) {
                    outside += 1;
                }
            }
            println!("sample root={} entries={entries} outside={outside}", identity.version_id());
            assert_eq!(outside, 0);
        }
        if let Ok(out) = std::env::var("RDIPACK2_OUT") {
            let tasks = crate::tasks::Manager::default();
            let base = std::env::temp_dir().join(format!("rdipack2-out-{}", uuid::Uuid::now_v7()));
            let version_id = "6650a1b2c3d4e5f601234567_版本 1.0";
            let dir = base.join(version_id).join("config").join("很长的中文目录名".repeat(4));
            std::fs::create_dir_all(&dir).unwrap();
            std::fs::write(dir.join("设置.toml"), b"a=1").unwrap();
            export(&context(&tasks), &base.join(version_id), version_id, Path::new(&out)).unwrap();
            std::fs::remove_dir_all(&base).unwrap();
        }
    }
    fn context(tasks: &crate::tasks::Manager) -> Context {
        tasks.test_context()
    }
    #[test]
    fn exports_import_back() {
        let tasks = crate::tasks::Manager::default();
        let ctx = context(&tasks);
        let base = std::env::temp_dir().join(format!("rdipack2-{}", uuid::Uuid::now_v7()));
        let version_id = "6650a1b2c3d4e5f601234567_版本 1.0";
        let pack = base.join("src").join(version_id);
        std::fs::create_dir_all(pack.join("mods")).unwrap();
        std::fs::create_dir_all(pack.join("logs")).unwrap();
        std::fs::create_dir_all(pack.join("config/很长的中文目录名".repeat(4))).unwrap();
        let jar = b"jar bytes";
        let murmur = digests::murmur2(jar);
        std::fs::write(pack.join(format!("mods/jei_cf_{murmur}.jar")), jar).unwrap();
        std::fs::write(pack.join("mods/bad_cf_1.jar"), b"other").unwrap();
        std::fs::write(pack.join("logs/latest.log"), b"log").unwrap();
        std::fs::write(pack.join("config/很长的中文目录名".repeat(4)).join("设置.toml"), b"a=1").unwrap();
        let output = base.join("out.rdipack2");
        export(&ctx, &pack, version_id, &output).unwrap();
        let identity = read_identity(&output).unwrap();
        assert_eq!(identity.version_id(), version_id);
        let versions = base.join("versions");
        let cache = base.join("dlc");
        let ok = |_: &Identity| Ok(());
        let idle = |_: &str| false;
        let checks = Checks { server_matches: &ok, running: &idle };
        import(&ctx, &output, &identity, &versions, &cache, &checks).unwrap();
        let target = versions.join(version_id);
        assert_eq!(std::fs::read(target.join("config/很长的中文目录名".repeat(4)).join("设置.toml")).unwrap(), b"a=1");
        assert!(!target.join("logs").exists(), "logs stay behind");
        assert!(cache.join(format!("{murmur}.murmur2")).is_file());
        assert_eq!(std::fs::read_dir(&cache).unwrap().filter(|e| e.as_ref().unwrap().path().is_file()).count(), 2, "murmur2 and sha1 of the good jar only");
        assert!(target.join("mods/bad_cf_1.jar").is_file(), "a mismatched jar stays in the pack");
        assert!(import(&ctx, &output, &identity, &versions, &cache, &checks).unwrap_err().to_string().contains("已存在"));
        let running = |_: &str| true;
        std::fs::remove_dir_all(&target).unwrap();
        let busy = Checks { server_matches: &ok, running: &running };
        assert!(import(&ctx, &output, &identity, &versions, &cache, &busy).is_err());
        assert_eq!(std::fs::read_dir(&versions).unwrap().count(), 0, "no staging directory is left");
        std::fs::remove_dir_all(&base).unwrap();
    }
}
