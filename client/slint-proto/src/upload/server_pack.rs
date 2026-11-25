//! The optional server pack of an upload: Kotlin `ModpackProcessor.loadServerPack` with
//! `prepareServerPackDirectory`, `matchServerModsByClientModId`, `collectServerPackExtraFiles`
//! and `fileMatchesModContent`. Its mods join the client list; the rest of the folder goes
//! into the archive under `server/`. Blocking, like `reader`.
use super::jar_meta;
use super::mods as rules;
use super::policy::{self, SaveRegions};
use super::reader::{self, Context, EmbeddedMod, Match, Matches, Progress};
use crate::digests::{Algorithm, hash};
use crate::pack_install::archive::{MODPACK_LIMITS, normalize_safe_path};
use crate::pack_install::mods::{Mod, Side};
use anyhow::{Context as _, Result, bail, ensure};
use std::collections::BTreeSet;
use std::io::Read;
use std::path::{Path, PathBuf};

/// `MEDIA_FILE_EXTENSIONS`: pictures, audio and video stay out of the server files.
const MEDIA_EXTENSIONS: [&str; 31] = [
    "ogg", "wav", "mp3", "flac", "aac", "m4a", "opus", "wma", "mp4", "mov", "avi", "mkv", "webm", "m4v", "mpeg", "mpg", "flv", "wmv", "png", "jpg", "jpeg", "webp",
    "gif", "bmp", "tif", "tiff", "avif", "ico", "svg", "psd", "tga",
];

/// `ServerExtraFile`.
#[derive(Clone, Debug, PartialEq)]
pub struct ServerExtra {
    pub source: PathBuf,
    pub relative: String,
}

/// `LoadedServerPackResult`.
#[derive(Debug)]
pub struct LoadedServer {
    pub mods: Vec<Mod>,
    pub extras: Vec<ServerExtra>,
    pub embedded_mods: Vec<EmbeddedMod>,
    pub contains_mca: bool,
}

/// Java `File.extension`: the text after the name's last dot, lower case.
fn extension(path: &Path) -> String {
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    name.rsplit_once('.').map(|(_, e)| e.to_lowercase()).unwrap_or_default()
}

/// `prepareServerPackDirectory`: a folder is used as it is; a zip is extracted, and its root or
/// exactly one folder below it must hold `mods`.
fn prepare(ctx: &Context, source: &Path) -> Result<PathBuf> {
    if source.is_dir() {
        return Ok(source.to_path_buf());
    }
    ensure!(source.is_file() && extension(source) == "zip", "请选择服务端ZIP文件");
    ctx.cancel.check()?;
    std::fs::create_dir_all(&ctx.work_dir)?;
    let staging = ctx.temp("server-pack-", "");
    std::fs::create_dir_all(&staging)?;
    let extracted = (|| -> Result<PathBuf> {
        (ctx.progress)(Progress::Phase("正在解压服务端ZIP".into()));
        let mut zip = zip::ZipArchive::new(std::fs::File::open(source)?).context("服务端ZIP无法读取")?;
        ensure!(zip.len() <= MODPACK_LIMITS.entries, "服务端ZIP文件数量过多");
        let (mut files, mut directories) = (BTreeSet::new(), BTreeSet::new());
        let mut total = 0u64;
        for index in 0..zip.len() {
            ctx.cancel.check()?;
            let mut entry = zip.by_index(index)?;
            let raw = crate::import::archive::decode_name(entry.name_raw());
            let directory = entry.is_dir();
            let path = normalize_safe_path(&raw)?;
            ensure!(!path.is_empty() || directory, "服务端ZIP包含空文件路径");
            if path.is_empty() {
                continue;
            }
            let key = path.to_lowercase();
            ensure!(!files.contains(&key), "服务端ZIP包含重复文件路径: {raw}");
            if directory {
                directories.insert(key.clone());
            } else {
                ensure!(!directories.contains(&key), "服务端ZIP文件与目录路径冲突: {raw}");
                files.insert(key.clone());
            }
            let mut parent = key.rsplit_once('/').map(|(p, _)| p.to_owned()).unwrap_or_default();
            while !parent.is_empty() {
                ensure!(!files.contains(&parent), "服务端ZIP文件与目录路径冲突: {raw}");
                directories.insert(parent.clone());
                parent = parent.rsplit_once('/').map(|(p, _)| p.to_owned()).unwrap_or_default();
            }
            let target = staging.join(&path);
            if directory {
                std::fs::create_dir_all(&target)?;
                continue;
            }
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut output = std::fs::File::create_new(&target).with_context(|| format!("写入{path}失败"))?;
            let written = std::io::copy(&mut (&mut entry).take(MODPACK_LIMITS.entry + 1), &mut output)?;
            total += written;
            ensure!(written <= MODPACK_LIMITS.entry && total <= MODPACK_LIMITS.total, "服务端ZIP解压后过大");
        }
        let mut candidates: Vec<PathBuf> = vec![staging.clone()];
        for entry in std::fs::read_dir(&staging).context("无法读取解压后的服务端目录")? {
            let entry = entry?;
            if entry.file_type()?.is_dir() {
                candidates.push(entry.path());
            }
        }
        candidates.retain(|c| c.join("mods").is_dir());
        ensure!(!candidates.is_empty(), "服务端ZIP根目录或其下一层文件夹中应有mods文件夹");
        ensure!(candidates.len() == 1, "服务端ZIP中找到多个服务端目录，请只保留一个");
        Ok(candidates.remove(0))
    })();
    extracted.inspect_err(|_| reader::remove_tree(&staging))
}

/// `fileMatchesModContent`: the file has the hash the mod record names, in the record's own
/// algorithm (CurseForge Murmur2, Modrinth SHA-1, GitHub SHA-1 or SHA-256).
fn file_matches(ctx: &Context, file: &Path, m: &Mod) -> bool {
    let wanted = m.hash.trim().to_lowercase();
    let is_hex = |length: usize| wanted.len() == length && wanted.bytes().all(|b| b.is_ascii_hexdigit());
    let algorithm = match m.platform.trim().to_lowercase().as_str() {
        "cf" if wanted.parse::<u64>().is_ok_and(|v| v != 0 && v <= u64::from(u32::MAX)) => Algorithm::Murmur2,
        "mr" if is_hex(40) => Algorithm::Sha1,
        "github" if is_hex(40) => Algorithm::Sha1,
        "github" if is_hex(64) => Algorithm::Sha256,
        _ => return false,
    };
    if !file.is_file() {
        return false;
    }
    let Ok(actual) = hash(file, algorithm, &ctx.cancel) else { return false };
    if algorithm == Algorithm::Murmur2 {
        // The record may write the number with leading zeros.
        wanted.parse::<u64>().ok() == actual.parse::<u64>().ok()
    } else {
        actual.eq_ignore_ascii_case(&wanted)
    }
}

fn primary_mod_id(file: &Path) -> Option<String> {
    jar_meta::read(file).and_then(|m| m.mod_id).filter(|id| !id.trim().is_empty())
}

fn fabric_side(file: &Path) -> Option<Side> {
    jar_meta::read(file).and_then(|m| m.side)
}

/// `buildClientModsByModId`: the client mods whose jar is at hand, by the jar's mod id; the
/// first of two with one id stays.
fn client_mods_by_id(client_mods: &[Mod], sources: &[(Mod, PathBuf)], fabric: bool) -> Vec<(String, Mod)> {
    let mut out: Vec<(String, Mod)> = Vec::new();
    for client in client_mods {
        let Some((_, file)) = sources.iter().find(|(m, f)| m == client && f.is_file()) else { continue };
        let mut local = client.clone();
        if fabric && let Some(side) = fabric_side(file) {
            local.side = side;
        }
        let Some(id) = primary_mod_id(file) else { continue };
        match out.iter().find(|(known, _)| *known == id) {
            Some((_, previous)) if previous != client => {
                tracing::warn!(mod_id = %id, kept = %previous.slug, ignored = %client.slug, "多个客户端mod共用了同一个modId，保留第一个");
            }
            Some(_) => {}
            None => out.push((id, local)),
        }
    }
    out
}

/// `matchServerModsByClientModId`: a server jar with the mod id and the bytes of a client mod
/// is that mod, without asking the platforms.
fn match_by_mod_id(ctx: &Context, files: &[PathBuf], by_id: &[(String, Mod)], fabric: bool) -> Matches {
    if files.is_empty() || by_id.is_empty() {
        return Matches { unmatched: files.to_vec(), ..Default::default() };
    }
    (ctx.progress)(Progress::Phase("平台未识别的服务端mod，按modId与客户端已下载mod对比".into()));
    let mut out = Matches::default();
    let (mut ignored, mut used): (Vec<PathBuf>, Vec<String>) = (Vec::new(), Vec::new());
    for file in files {
        let Some(id) = primary_mod_id(file) else { continue };
        if used.contains(&id) {
            tracing::warn!(mod_id = %id, "服务端目录里有多个jar共用了同一个modId，保留第一个并忽略后续文件");
            ignored.push(file.clone());
            out.matched.push(file.clone());
            continue;
        }
        let Some((_, client)) = by_id.iter().find(|(known, _)| *known == id) else { continue };
        if !file_matches(ctx, file, client) {
            continue;
        }
        used.push(id);
        let side = if fabric { fabric_side(file).unwrap_or(client.side) } else { Side::Both };
        out.mods.push(Match { m: Mod { side, ..client.clone() }, file: file.clone() });
        out.matched.push(file.clone());
    }
    out.unmatched = files.iter().filter(|f| !out.matched.contains(f) && !ignored.contains(f)).cloned().collect();
    out
}

/// `shouldSkipServerExtraDir`: caches, logs, crash reports, libraries, the map mod's data and
/// a Java runtime (a folder holding `bin`, `lib` and `jmods`).
fn skip_dir(root: &Path, dir: &Path) -> bool {
    if dir == root {
        return false;
    }
    let relative = reader::relative(root, dir).to_lowercase();
    let name = dir.file_name().map(|n| n.to_string_lossy().to_lowercase()).unwrap_or_default();
    if name.contains("cache") || name == "logs" || name == "crash-reports" || relative.starts_with("libraries/") || relative.starts_with("xaero/") {
        return true;
    }
    let children: Vec<String> = std::fs::read_dir(dir)
        .map(|entries| entries.flatten().filter(|e| e.file_type().is_ok_and(|t| t.is_dir())).map(|e| e.file_name().to_string_lossy().to_lowercase()).collect())
        .unwrap_or_default();
    ["bin", "lib", "jmods"].iter().all(|wanted| children.iter().any(|c| c == wanted))
}

/// `shouldSkipServerExtraFile`.
fn skip_file(relative: &str, file: &Path) -> bool {
    if policy::is_disabled(relative, false) {
        return true;
    }
    let lower = relative.to_lowercase();
    let name = file.file_name().map(|n| n.to_string_lossy().to_lowercase()).unwrap_or_default();
    let ext = extension(file);
    if lower.starts_with("libraries/") || lower.starts_with("logs/") || lower.starts_with("crash-reports/") {
        return true;
    }
    if name == ".ds_store" || name == "desktop.ini" || ext == "db" || MEDIA_EXTENSIONS.contains(&ext.as_str()) {
        return true;
    }
    !lower.starts_with("mods/") && (ext == "jar" || ext == "exe" || ext.trim().is_empty())
}

/// `collectServerPackExtraFiles`: every file of the server folder that is not a recognised mod
/// and that no rule leaves out.
fn collect_extras(root: &Path, matched: &[PathBuf]) -> Result<(Vec<ServerExtra>, bool)> {
    let canonical = |p: &Path| std::fs::canonicalize(p).unwrap_or_else(|_| p.to_path_buf());
    let matched: Vec<PathBuf> = matched.iter().map(|f| canonical(f)).collect();
    fn visit(root: &Path, dir: &Path, matched: &[PathBuf], files: &mut Vec<ServerExtra>) -> Result<()> {
        if skip_dir(root, dir) {
            tracing::info!(path = %format!("server/{}", reader::relative(root, dir)), "整合包上传跳过服务端目录");
            return Ok(());
        }
        for (path, is_dir) in reader::children(dir)? {
            if is_dir {
                visit(root, &path, matched, files)?;
                continue;
            }
            let relative = reader::relative(root, &path);
            if relative.trim().is_empty() || matched.contains(&std::fs::canonicalize(&path).unwrap_or_else(|_| path.clone())) {
                continue;
            }
            if skip_file(&relative, &path) {
                tracing::info!(path = %format!("server/{relative}"), "整合包上传跳过服务端文件");
                continue;
            }
            files.push(ServerExtra { source: path, relative });
        }
        Ok(())
    }
    let mut files: Vec<ServerExtra> = Vec::new();
    visit(root, root, &matched, &mut files)?;
    let regions = SaveRegions::of(files.iter().map(|f| f.relative.as_str()));
    let mut contains_mca = false;
    for file in &files {
        if regions.is_save_region(&file.relative, false) || reader::archive_has_save_regions(&file.source)? {
            contains_mca = true;
            break;
        }
    }
    Ok((files, contains_mca))
}

/// `loadServerPack`. `client_sources` are the client mods whose jars are at hand (content
/// store or staged), for the mod id comparison.
pub fn load(ctx: &Context, file: &Path, client_mods: &[Mod], client_sources: &[(Mod, PathBuf)], fabric: bool) -> Result<LoadedServer> {
    std::fs::create_dir_all(&ctx.work_dir)?;
    let root = prepare(ctx, file)?;
    if ["spigot.yml", "arclight.conf", "bukkit.yml"].iter().any(|name| root.join(name).is_file()) {
        bail!("请选择正确安装了模组载入器的服务端");
    }
    (ctx.progress)(Progress::Phase("开始读取服务端包".into()));
    let mods_dir = root.join("mods");
    ensure!(mods_dir.is_dir(), "服务端包中应有mods文件夹");
    let jars: Vec<PathBuf> = reader::walk_files(&mods_dir)?.into_iter().filter(|f| extension(f) == "jar").collect();
    let (selected, discarded) = rules::preferred(jars);
    ensure!(!selected.is_empty(), "服务端包的mods文件夹中没有可用的Mod文件");
    let by_id = client_mods_by_id(client_mods, client_sources, fabric);
    let by_mod_id = match_by_mod_id(ctx, &selected, &by_id, fabric);
    let mut found = reader::match_local(ctx, &by_mod_id.unmatched)?;
    if fabric {
        for hit in &mut found.mods {
            if let Some(side) = fabric_side(&hit.file) {
                hit.m.side = side;
            }
        }
    }
    let matches = reader::distinct_matches(found.mods.into_iter().chain(by_mod_id.mods).collect());
    let matched_files: Vec<PathBuf> = found.matched.into_iter().chain(by_mod_id.matched).chain(discarded).collect();
    if !found.unmatched.is_empty() {
        let stem = |f: &PathBuf| f.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
        let preview = found.unmatched.iter().take(5).map(stem).collect::<Vec<_>>().join("、");
        let suffix = if found.unmatched.len() > 5 { format!("等{}个", found.unmatched.len()) } else { String::new() };
        (ctx.progress)(Progress::Warn(format!("服务端目录中有{}个mod未识别，将作为额外服务端文件带上：{preview}{suffix}", found.unmatched.len())));
    }
    let mut mods: Vec<Mod> = matches.iter().map(|hit| hit.m.clone()).collect();
    if !fabric {
        rules::post_process_sides(&mut mods);
    }
    let (extras, contains_mca) = collect_extras(&root, &matched_files)?;
    let embedded_mods = reader::stage_mods(ctx, &matches)?;
    Ok(LoadedServer { mods, extras, embedded_mods, contains_mca })
}

#[cfg(test)]
pub(super) fn rules_for_test(root: &Path, path: &Path, directory: bool) -> bool {
    if directory { skip_dir(root, path) } else { skip_file(&reader::relative(root, path), path) }
}
