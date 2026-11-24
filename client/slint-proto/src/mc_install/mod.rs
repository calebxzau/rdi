//! J5a/J5c: installs Minecraft, the loader, libraries and assets before a launch, ported from
//! `client/mc-install` (`McInstall.prepareForLaunch`) and `MinecraftLauncher.prepare` for
//! Forge 1.20.1, NeoForge 1.21.1, Fabric 1.20.1 and GTNH (1.7.10). The on-disk layout and the "is it installed" checks match
//! Kotlin, so Compose accepts what Rust installs and the reverse. The Forge and NeoForge
//! installers still run as a Java child process (`McInstallerBootstrapper.kt`).
//!
//! Not ported (J5 D9): reusing files from other launchers' folders (`LocalArtifactReuse`).
pub mod assets;
pub mod fabric;
pub mod loader;
pub mod meta;
#[cfg(test)]
mod tests;

use crate::download::{Downloader, Source};
use crate::mc_launch::manifest::{Artifact, Host, Library, Manifest};
use crate::tasks::Cancellation;
use anyhow::{Context, Result, bail, ensure};
use sha1::Digest as _;
use std::path::{Path, PathBuf};

pub(crate) use crate::packs::human_file_size;

/// What an install needs; no Slint types, so a launcher MCP tool can call it too.
#[derive(Clone)]
pub struct Deps {
    pub downloader: Downloader,
    /// RDI server base URL for the last-resort library source, and its token for that host only.
    pub server_base: Option<String>,
    pub auth_headers: std::sync::Arc<dyn Fn(&str) -> reqwest::header::HeaderMap + Send + Sync>,
    pub prefer_mirror: bool,
    /// `<data dir>/mc`.
    pub mc_dir: PathBuf,
    /// The Java 25 that runs the Forge/NeoForge installer.
    pub java: PathBuf,
    pub host: Host,
}

impl Deps {
    pub fn versions(&self) -> PathBuf {
        self.mc_dir.join("versions")
    }
    pub fn libraries(&self) -> PathBuf {
        self.mc_dir.join("libraries")
    }
    pub fn assets(&self) -> PathBuf {
        self.mc_dir.join("assets")
    }
    pub fn directories(&self) -> crate::mc_launch::Directories {
        crate::mc_launch::Directories { versions: self.versions(), libraries: self.libraries(), assets: self.assets() }
    }
}

/// A value from Kotlin's `config.toml` (`AppConfig`); Kotlin writes absent values as `null`.
fn config_value(path: &Path, key: &str) -> Option<toml::Value> {
    let text = match std::fs::read_to_string(path) {
        Ok(text) => text,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return None,
        Err(error) => {
            tracing::warn!(%error, key, "读取config.toml失败，使用默认值");
            return None;
        }
    };
    let text: String = text
        .lines()
        .filter(|l| !l.split_once('=').is_some_and(|(_, v)| v.trim() == "null"))
        .collect::<Vec<_>>()
        .join("\n");
    match toml::from_str::<toml::Value>(&text) {
        Ok(v) => v.get(key).cloned(),
        Err(error) => {
            tracing::warn!(%error, key, "config.toml格式无效，使用默认值");
            None
        }
    }
}

/// `AppConfig.preferMcMirror` and the like.
pub fn config_bool(path: &Path, key: &str, default: bool) -> bool {
    config_value(path, key).and_then(|v| v.as_bool()).unwrap_or(default)
}

/// `AppConfig.maxMemory`.
pub fn config_int(path: &Path, key: &str, default: i32) -> i32 {
    config_value(path, key).and_then(|v| v.as_integer()).map(|v| v as i32).unwrap_or(default)
}

pub type Progress<'a> = &'a (dyn Fn(String) + Sync);

pub fn sha1_file(path: &Path) -> Result<String> {
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    let mut hasher = sha1::Sha1::new();
    let mut buffer = vec![0u8; 1 << 16];
    loop {
        let n = file.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
    }
    Ok(hasher.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

/// Kotlin `ZipFile(file).use { it.entries().hasMoreElements() }`.
pub fn opens_as_zip(path: &Path) -> bool {
    std::fs::File::open(path)
        .ok()
        .and_then(|f| zip::ZipArchive::new(f).ok())
        .is_some_and(|z| !z.is_empty())
}

fn sha1_matches(path: &Path, expected: &str) -> bool {
    sha1_file(path).is_ok_and(|actual| actual.eq_ignore_ascii_case(expected.trim()))
}

/// `McInstall.mirrors`: the BMCLAPI equivalent of an official URL, or the URL itself.
pub fn mirror_url(url: &str) -> String {
    const MIRRORS: [(&str, &str); 9] = [
        ("https://maven.neoforged.net/releases", "https://bmclapi2.bangbang93.com/maven"),
        ("https://files.minecraftforge.net/maven", "https://bmclapi2.bangbang93.com/maven"),
        ("http://launchermeta.mojang.com/mc/game/version_manifest.json", "https://bmclapi2.bangbang93.com/mc/game/version_manifest.json"),
        ("http://launchermeta.mojang.com/mc/game/version_manifest_v2.json", "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"),
        ("https://launchermeta.mojang.com", "https://bmclapi2.bangbang93.com"),
        ("https://launcher.mojang.com", "https://bmclapi2.bangbang93.com"),
        ("https://resources.download.minecraft.net", "https://bmclapi2.bangbang93.com/assets"),
        ("https://libraries.minecraft.net", "https://bmclapi2.bangbang93.com/maven"),
        ("https://maven.minecraftforge.net", "https://bmclapi2.bangbang93.com/maven"),
    ];
    for (origin, mirror) in MIRRORS {
        if let Some(suffix) = url.strip_prefix(origin) {
            if suffix.is_empty() {
                return mirror.into();
            }
            return format!("{}/{}", mirror.trim_end_matches('/'), suffix.trim_start_matches('/'));
        }
    }
    url.into()
}

/// `GameLibMaven.rdiSourceUrl`: the RDI server copy of a known Maven library.
pub fn rdi_source_url(server: &str, official: &str, sha1: &str) -> Option<String> {
    const BASES: [&str; 5] = [
        "https://libraries.minecraft.net",
        "https://maven.neoforged.net/releases",
        "https://maven.minecraftforge.net",
        "https://maven.fabricmc.net",
        "https://files.minecraftforge.net/maven",
    ];
    let trimmed = official.trim();
    let trimmed = trimmed.split(['?', '#']).next().unwrap_or("");
    let base = BASES.iter().find(|b| trimmed.starts_with(&format!("{b}/")))?;
    let path = &trimmed[base.len() + 1..];
    let segments: Vec<&str> = path.split('/').collect();
    let segment_ok = |s: &&str| !s.is_empty() && *s != "." && *s != ".." && s.chars().all(|c| c.is_ascii_alphanumeric() || "._+-".contains(c));
    let extension = segments.last()?.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()).unwrap_or_default();
    if segments.len() < 4 || !segments.iter().all(segment_ok) || !["jar", "zip", "lzma"].contains(&extension.as_str()) {
        return None;
    }
    let sha1 = sha1.trim();
    let query = if sha1.len() == 40 && sha1.chars().all(|c| c.is_ascii_hexdigit()) {
        format!("?sha1={}", sha1.to_ascii_lowercase())
    } else {
        String::new()
    };
    Some(format!("{}/game-lib/maven/{path}{query}", server.trim_end_matches('/')))
}

/// `buildDownloadSourcePlan`: mirror first when preferred, the RDI server always last.
pub fn source_plan(deps: &Deps, official: &str, mirror: Option<&str>, sha1: &str) -> Vec<String> {
    let official = official.trim().to_owned();
    let mirror = mirror.map(str::to_owned).unwrap_or_else(|| mirror_url(&official)).trim().to_owned();
    let officials: Vec<String> = [official].into_iter().filter(|u| !u.is_empty()).collect();
    let mirrors: Vec<String> = [mirror].into_iter().filter(|u| !u.is_empty() && !officials.contains(u)).collect();
    let rdi: Vec<String> = deps
        .server_base
        .as_deref()
        .and_then(|s| officials.first().and_then(|o| rdi_source_url(s, o, sha1)))
        .into_iter()
        .collect();
    let mut plan = if deps.prefer_mirror {
        if mirrors.is_empty() { officials } else { [mirrors, officials].concat() }
    } else {
        [officials, mirrors].concat()
    };
    plan.extend(rdi);
    plan
}

/// `downloadVerifiedArtifact` through the J4b downloader: ordered sources, checksum checked.
pub async fn download_verified(
    deps: &Deps,
    urls: &[String],
    target: &Path,
    sha1: &str,
    size: u64,
    cancel: &Cancellation,
    progress: &(dyn Fn(crate::download::Progress) + Sync),
) -> Result<()> {
    ensure!(!urls.is_empty(), "{}下载链接为空", target.file_name().map(|n| n.to_string_lossy()).unwrap_or_default());
    let sources: Vec<Source> = urls.iter().map(|u| Source { url: u.clone(), headers: (deps.auth_headers)(u) }).collect();
    let expected = sha1.trim().to_ascii_lowercase();
    let validate = move |path: &Path| -> Result<()> {
        if !expected.is_empty() {
            let actual = sha1_file(path)?;
            ensure!(actual.eq_ignore_ascii_case(&expected), "校验失败: expected={expected} actual={actual}");
        }
        Ok(())
    };
    deps.downloader
        .download(&sources, target, (size > 0).then_some(size), cancel, &validate, progress)
        .await
        .with_context(|| format!("{}下载失败", target.file_name().map(|n| n.to_string_lossy()).unwrap_or_default()))
}

/// `McInstall.downloadArtifact`: keeps a file whose checksum already matches.
pub async fn download_artifact(deps: &Deps, artifact: &Artifact, target: &Path, cancel: &Cancellation, progress: Progress<'_>, label: &str) -> Result<()> {
    if target.exists() && !artifact.sha1.trim().is_empty() && sha1_matches(target, &artifact.sha1) {
        return Ok(());
    }
    let url = resolve_artifact_url(artifact);
    let plan = source_plan(deps, &url, None, &artifact.sha1);
    let reporter = throttled(label.to_owned(), progress);
    download_verified(deps, &plan, target, &artifact.sha1, artifact.size, cancel, &reporter).await
}

/// `resolveArtifactUrl`: a missing URL comes from the Maven path.
pub fn resolve_artifact_url(artifact: &Artifact) -> String {
    let raw = artifact.url.trim();
    if !raw.is_empty() {
        return raw.into();
    }
    match artifact.path.as_deref().map(str::trim).filter(|p| !p.is_empty()) {
        Some(path) => format!(
            "{}/{}",
            crate::mc_launch::manifest::default_library_base(path).trim_end_matches('/'),
            path.trim_start_matches('/')
        ),
        None => String::new(),
    }
}

/// Download progress as `<label> <done>/<total>`, at most one line per 8MiB.
fn throttled<'a>(label: String, progress: Progress<'a>) -> impl Fn(crate::download::Progress) + Sync + 'a {
    let last = std::sync::atomic::AtomicU64::new(u64::MAX);
    move |p: crate::download::Progress| {
        let bucket = p.bytes / (8 * 1024 * 1024);
        if last.swap(bucket, std::sync::atomic::Ordering::Relaxed) != bucket {
            let total = p.total.filter(|t| *t > 0).map(human_file_size).unwrap_or_else(|| "未知".into());
            progress(format!("{label} {}/{total}", human_file_size(p.bytes)));
        }
    }
}

fn ensure_not_cancelled(cancel: &Cancellation) -> Result<()> {
    if cancel.cancelled() {
        bail!("Minecraft安装已取消");
    }
    Ok(())
}

/// `isValidMinecraftClientJar`.
fn valid_client_jar(target: &Path, artifact: &Artifact) -> bool {
    let Ok(meta) = std::fs::metadata(target) else { return false };
    if !meta.is_file() || meta.len() == 0 || (artifact.size > 0 && meta.len() != artifact.size) {
        return false;
    }
    if artifact.sha1.trim().is_empty() { opens_as_zip(target) } else { sha1_matches(target, &artifact.sha1) }
}

/// `MinecraftLauncher.ensureMinecraftClientJar`.
async fn ensure_client_jar(deps: &Deps, manifest: &Manifest, cancel: &Cancellation, progress: Progress<'_>) -> Result<()> {
    let id = &manifest.id;
    let artifact = manifest.downloads.as_ref().and_then(|d| d.client.clone()).with_context(|| format!("缺少Minecraft客户端JAR下载信息: {id}"))?;
    let target = deps.versions().join(id).join(format!("{id}.jar"));
    let (t, a) = (target.clone(), artifact.clone());
    if tokio::task::spawn_blocking(move || valid_client_jar(&t, &a)).await? {
        progress(format!("Minecraft客户端{id}完整"));
        return Ok(());
    }
    let reason = if target.is_file() { "校验失败" } else { "缺失" };
    progress(format!("Minecraft客户端{id}{reason}，开始修复"));
    if target.exists() {
        std::fs::remove_file(&target).with_context(|| format!("无法替换Minecraft客户端JAR: {}", target.display()))?;
    }
    let url = resolve_artifact_url(&artifact);
    let plan = source_plan(deps, &url, None, &artifact.sha1);
    let reporter = throttled(format!("Minecraft客户端{id}"), progress);
    download_verified(deps, &plan, &target, &artifact.sha1, artifact.size, cancel, &reporter).await?;
    ensure!(valid_client_jar(&target, &artifact), "Minecraft客户端JAR修复失败: {}", target.display());
    progress(format!("Minecraft客户端{id}已就绪"));
    Ok(())
}

/// `MinecraftLaunchLibraryPreparer.ensure`: checks base and loader libraries, repairs broken ones.
async fn ensure_libraries(deps: &Deps, manifest: &Manifest, loader: &Manifest, cancel: &Cancellation, progress: Progress<'_>) -> Result<()> {
    let libraries = deps.libraries();
    let host = deps.host.clone();
    let (m, l, dir) = (manifest.clone(), loader.clone(), libraries.clone());
    let broken: Vec<(Library, Artifact, String, String)> = tokio::task::spawn_blocking(move || {
        crate::mc_launch::launch_artifacts(&m.libraries, &l.libraries, &host)
            .into_iter()
            .filter_map(|(library, artifact, path)| {
                let kind = if library.native_artifact(&host).is_some_and(|n| n == artifact) { "原生库" } else { "运行库" };
                crate::mc_launch::library_issue(&dir.join(&path), &artifact.sha1, kind)
                    .map(|issue| (library.clone(), artifact, path, issue))
            })
            .collect()
    })
    .await?;
    if broken.is_empty() {
        progress("运行库完整".into());
        return Ok(());
    }
    let total = broken.len();
    for (index, (library, artifact, path, issue)) in broken.into_iter().enumerate() {
        ensure_not_cancelled(cancel)?;
        let file = libraries.join(&path);
        let name = file.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        progress(format!("{}: {issue} ({name})，开始修复", library.name));
        progress(format!("修复{}/{total}: {name}", index + 1));
        let mut artifact = artifact;
        artifact.path = Some(path.clone());
        download_artifact(deps, &artifact, &file, cancel, progress, &format!("修复{name}")).await?;
        let kind = if library.native_artifact(&deps.host).is_some_and(|n| n.path.as_deref() == Some(&path)) { "原生库" } else { "运行库" };
        ensure!(crate::mc_launch::library_issue(&file, &artifact.sha1, kind).is_none(), "运行库修复后校验失败: {}", library.name);
    }
    let (m, l, dir, host) = (manifest.clone(), loader.clone(), libraries.clone(), deps.host.clone());
    tokio::task::spawn_blocking(move || crate::mc_launch::validate_libraries(&m, &l, &dir, &host))
        .await?
        .context("运行库修复失败")?;
    progress("运行库修复完成".into());
    Ok(())
}

/// The manifests a launch uses: vanilla, and the loader manifest after normalization.
pub fn launch_manifests(deps: &Deps, mc_version: &str, loader: &str) -> Result<(Manifest, Manifest)> {
    let version = meta::loader(mc_version, loader)?;
    let vanilla = meta::vanilla(mc_version)?;
    let state = loader::read_launch_state(deps, &version)?.with_context(|| format!("{loader}安装后缺少有效启动manifest"))?;
    Ok((vanilla, state.manifest))
}

/// The pack a launch is prepared for.
pub struct Target<'a> {
    pub mc_version: &'a str,
    /// `ModLoader.name`: `forge`, `neoforge` or `Fabric`.
    pub loader: &'a str,
    pub version_id: &'a str,
    pub version_dir: &'a Path,
}

#[derive(Debug)]
/// What `mc_launch::command` needs after the install: the vanilla manifest (for GTNH without
/// LWJGL2), the loader manifest, and the GTNH folder when there is one.
pub struct Launch {
    pub manifest: Manifest,
    pub loader: Manifest,
    pub gtnh_root: Option<PathBuf>,
}

/// `McInstall.prepareForLaunch`: loader, then client jar and libraries, then assets.
///
/// GTNH skips the Forge 1.7.10 installer that Kotlin still runs: its launch reads only the pack's
/// patches, vanilla 1.7.10 and the shared libraries (J5c plan, section 8).
pub async fn prepare_for_launch(deps: &Deps, target: &Target<'_>, cancel: &Cancellation, progress: Progress<'_>) -> Result<Launch> {
    ensure_not_cancelled(cancel)?;
    let launch = match meta::runtime(target.mc_version, target.loader)? {
        meta::Runtime::Installer(version) => {
            loader::ensure(deps, &version, cancel, progress).await?;
            ensure_not_cancelled(cancel)?;
            let (vanilla, loader_manifest) = launch_manifests(deps, target.mc_version, target.loader)?;
            Launch { manifest: vanilla, loader: loader_manifest, gtnh_root: None }
        }
        meta::Runtime::Fabric => {
            let profile = fabric::ensure(deps, cancel, progress).await?;
            Launch { manifest: meta::vanilla(fabric::MC_VERSION)?, loader: profile, gtnh_root: None }
        }
        meta::Runtime::Gtnh => {
            let root = crate::mc_launch::gtnh::require_root(target.version_dir, &deps.host)?;
            let loader_manifest = crate::mc_launch::gtnh::loader_manifest(&root, target.version_id)?;
            let mut vanilla = meta::vanilla("1.7.10")?;
            // Its plan includes the 1.7.10 client jar when that is missing.
            ensure_gtnh_runtime(deps, &root, &vanilla, cancel, progress).await?;
            vanilla.libraries.retain(|l| !crate::mc_launch::gtnh::is_legacy_lwjgl2(l));
            Launch { manifest: vanilla, loader: loader_manifest, gtnh_root: Some(root) }
        }
    };
    ensure_client_jar(deps, &launch.manifest, cancel, progress).await?;
    ensure_libraries(deps, &launch.manifest, &launch.loader, cancel, progress).await?;
    ensure_not_cancelled(cancel)?;
    assets::ensure(deps, &launch.manifest, cancel, progress).await?;
    ensure_not_cancelled(cancel)?;
    Ok(launch)
}

/// `GtnhLaunchSupport.ensureRuntime`: shared LWJGL3 and Forge libraries the pack needs.
async fn ensure_gtnh_runtime(deps: &Deps, root: &Path, vanilla: &Manifest, cancel: &Cancellation, progress: Progress<'_>) -> Result<()> {
    let (r, v, libraries, versions, host) = (root.to_owned(), vanilla.clone(), deps.libraries(), deps.versions(), deps.host.clone());
    let plans = tokio::task::spawn_blocking(move || crate::mc_launch::gtnh::downloads(&r, &v, &libraries, &versions, &host)).await??;
    if plans.is_empty() {
        progress("GTNH运行库已齐全".into());
        return Ok(());
    }
    progress(format!("开始自动下载{}个GTNH运行库", plans.len()));
    let total = plans.len();
    for (index, plan) in plans.into_iter().enumerate() {
        ensure_not_cancelled(cancel)?;
        progress(format!("下载{}/{total}: {}", index + 1, plan.label));
        let last = std::sync::atomic::AtomicI64::new(-1);
        let label = plan.label.clone();
        let report = move |p: crate::download::Progress| {
            let bucket = match p.total.filter(|t| *t > 0) {
                Some(t) => ((p.bytes.min(t) * 100 / t) / 10 * 10) as i64,
                None if p.bytes > 0 => i64::MAX,
                None => 0,
            };
            if last.swap(bucket, std::sync::atomic::Ordering::Relaxed) == bucket {
                return;
            }
            let total = p.total.filter(|t| *t > 0).map(human_file_size).unwrap_or_else(|| "未知".into());
            let suffix = if p.total.is_some_and(|t| t > 0) { format!(" {}%", bucket.min(100)) } else { String::new() };
            progress(format!("{label} {}/{total}{suffix}", human_file_size(p.bytes)));
        };
        let url = resolve_artifact_url(&plan.artifact);
        let sources = source_plan(deps, &url, None, &plan.artifact.sha1);
        download_verified(deps, &sources, &plan.target, &plan.artifact.sha1, plan.artifact.size, cancel, &report).await?;
        progress(format!("{}下载完成", plan.label));
    }
    Ok(())
}
