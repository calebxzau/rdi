//! Stage A6a of the launcher MCP plan: the local test server that the upload's server test
//! starts, ported from `McInstall.downloadTestServerTask2`, `isTestServerInstalled`,
//! `installFabricTestServer`, `findFabricServerRuntime`, `stageFabricServerRuntime` and
//! `resolveServerRuntime` (`common/model/.../ServerLoaderRuntime.kt`).
//!
//! Forge 1.20.1 and NeoForge 1.21.1: the vanilla server jar and the server Mojmap are put where
//! the installer looks for them, then `java -jar <installer> --installServer <mc dir>` installs
//! into the shared `mc` folder; the server is there once the loader's args file exists.
//! Fabric 1.20.1: the Fabric installer writes a server runtime into a cache folder, recorded with
//! a SHA-1 file list and a ready file; every test copies it into its own folder.
//!
//! Differences from Kotlin: the symbolic link checks start at the `mc` folder (or the test
//! folder), not at the file system root, because Rust counts Windows junctions as links and Java
//! does not; the parse step reads the installer only and does not rewrite the loader's version
//! JSON. Kotlin leaves a failed Fabric staging folder behind, and so does Rust (it is never
//! taken, since it has no ready file).
use super::loader::{self, InstallProfile};
use super::meta::{self, LoaderVersion};
use super::{Deps, Progress, ensure_not_cancelled, sha1_file, sha1_matches};
use crate::mc_launch::manifest::descriptor_path;
use crate::tasks::Cancellation;
use anyhow::{Context, Result, bail, ensure};
use std::path::{Path, PathBuf};
use std::time::Duration;

pub const FABRIC_INSTALLER_VERSION: &str = "1.1.2";
pub const FABRIC_INSTALLER_URL: &str = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar";
pub const FABRIC_INSTALLER_SHA1: &str = "74685b5a0178c4dabd4be741a276d35c59b5ec96";
const FABRIC_INSTALLER_TIMEOUT: Duration = Duration::from_secs(20 * 60);
pub const FABRIC_SERVER_LAUNCHER_JAR: &str = "fabric-server-launch.jar";
pub const FABRIC_SERVER_JAR: &str = "server.jar";
const FABRIC_LAUNCHER_PROPERTIES: &str = "fabric-server-launcher.properties";
const FILES_MANIFEST: &str = ".rdi-runtime-files";
const READY_FILE: &str = ".rdi-runtime-ready";

fn fabric_loader_jar() -> String {
    let v = super::fabric::LOADER_VERSION;
    format!("libraries/net/fabricmc/fabric-loader/{v}/fabric-loader-{v}.jar")
}

/// `ServerLoaderRuntime`: how a version's test server is installed and started.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum ServerRuntime {
    /// Forge 1.20.1 or NeoForge 1.21.1, started from the installer's args file.
    Installer(LoaderVersion),
    /// Fabric 1.20.1 with loader `fabric::LOADER_VERSION`, started from its launcher jar.
    Fabric,
}

impl ServerRuntime {
    /// `McVersion.resolveServerRuntime`: `None` for a version without a test server.
    pub fn resolve(mc_version: &str, loader: &str) -> Option<Self> {
        match meta::runtime(mc_version, loader).ok()? {
            meta::Runtime::Installer(version) => Some(Self::Installer(version)),
            meta::Runtime::Fabric => Some(Self::Fabric),
            meta::Runtime::Gtnh => None,
        }
    }

    pub fn mc_version(&self) -> &'static str {
        match self {
            Self::Installer(v) => v.mc_version,
            Self::Fabric => super::fabric::MC_VERSION,
        }
    }

    /// `ModLoader` as Kotlin prints it: `forge`, `neoforge`, `Fabric`.
    pub fn loader_name(&self) -> &'static str {
        match self {
            Self::Installer(v) => v.loader,
            Self::Fabric => "Fabric",
        }
    }

    /// `ModLoader.Version.id`: `1.20.1-47.4.29`, `21.1.265`; the Fabric loader version.
    pub fn loader_version(&self) -> String {
        match self {
            Self::Installer(v) => v.dir_name.replace(&format!("{}-", v.loader), ""),
            Self::Fabric => super::fabric::LOADER_VERSION.into(),
        }
    }

    /// `serverArgsPath` without the `@libraries/`: the args file under the libraries folder.
    pub fn args_file(&self, unix: bool) -> Option<String> {
        let Self::Installer(v) = self else { return None };
        let group = if v.loader == "neoforge" { "net/neoforged/neoforge" } else { "net/minecraftforge/forge" };
        Some(format!("{group}/{}/{}_args.txt", self.loader_version(), if unix { "unix" } else { "win" }))
    }

    /// The title of `downloadTestServerTask2`; the upload's server test shows it while installing.
    pub fn task_title(&self) -> String {
        format!("下载测试服务端 {} {}", self.mc_version(), self.loader_name())
    }

    /// The step titles of `downloadTestServerTask2`.
    pub fn steps(&self) -> Vec<String> {
        match self {
            Self::Installer(v) => {
                let loader = v.loader;
                vec![format!("下载{loader} 安装器"), "解析安装器".into(), format!("下载{loader}服务端"), "下载服务端Mojmap".into(), "运行安装器服务端".into()]
            }
            Self::Fabric => vec!["下载Fabric安装器".into(), "安装Fabric测试服务端".into()],
        }
    }
}

/// Whether the host takes the `unix_args.txt` of a loader server.
pub fn unix(deps: &Deps) -> bool {
    deps.host.os_name != "windows"
}

/// `isTestServerInstalled`: a quick presence check; the install task still verifies contents.
/// Blocking: the Fabric check hashes the cached runtime.
pub fn installed(deps: &Deps, runtime: &ServerRuntime) -> bool {
    match runtime.args_file(unix(deps)) {
        Some(args) => deps.libraries().join(args).is_file(),
        None => find_fabric_runtime(deps).is_some(),
    }
}

/// `downloadTestServerTask2` as one call: `step(i)` starts visible step `i` of [`ServerRuntime::steps`].
pub async fn install(deps: &Deps, runtime: &ServerRuntime, cancel: &Cancellation, step: &(dyn Fn(usize) + Sync), progress: Progress<'_>) -> Result<()> {
    match runtime {
        ServerRuntime::Installer(version) => install_loader_server(deps, version, cancel, step, progress).await,
        ServerRuntime::Fabric => {
            step(0);
            ensure_fabric_installer(deps, cancel, progress).await?;
            ensure_not_cancelled(cancel)?;
            step(1);
            install_fabric_runtime(deps, cancel, progress).await
        }
    }
}

/// Where `--installServer` expects the vanilla server jar (`downloadServerTask2`).
pub fn server_jar_target(deps: &Deps, profile: &InstallProfile, mc_version: &str) -> PathBuf {
    match profile.server_jar_path.as_deref().map(str::trim).filter(|p| !p.is_empty()) {
        Some(path) => PathBuf::from(path.replace("{LIBRARY_DIR}", &deps.libraries().to_string_lossy()).replace("{MINECRAFT_VERSION}", mc_version)),
        None => deps.mc_dir.join(format!("minecraft_server.{mc_version}.jar")),
    }
}

async fn install_loader_server(deps: &Deps, version: &LoaderVersion, cancel: &Cancellation, step: &(dyn Fn(usize) + Sync), progress: Progress<'_>) -> Result<()> {
    let lock = loader::install_lock(version);
    let _guard = match lock.try_lock() {
        Ok(guard) => guard,
        Err(_) => {
            progress(format!("等待相同{}安装完成...", version.loader));
            crate::download::cancellable(cancel, async { Ok(lock.lock().await) }).await?
        }
    };
    let mc = version.mc_version;

    step(0);
    let (_, installer) = loader::ensure_installer(deps, version, cancel, progress).await?;
    ensure_not_cancelled(cancel)?;

    step(1);
    let text = loader::read_entry(&installer, "install_profile.json")?.context("安装器中缺少 install_profile.json")?;
    let profile: InstallProfile = serde_json::from_str(&text).context("安装器install_profile.json无效")?;
    progress("解析完成".into());

    step(2);
    let vanilla = meta::vanilla(mc)?;
    let downloads = vanilla.downloads.unwrap_or_default();
    match &downloads.server {
        None => progress("缺少服务端下载信息".into()),
        Some(server) => {
            let target = server_jar_target(deps, &profile, mc);
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)?;
            }
            progress("开始下载...".into());
            let (t, sha) = (target.clone(), server.sha1.clone());
            if !tokio::task::spawn_blocking(move || t.exists() && sha1_matches(&t, &sha)).await? {
                let mirror = format!("https://bmclapi2.bangbang93.com/version/{mc}/server");
                let plan = super::source_plan(deps, &server.url, Some(&mirror), &server.sha1);
                let reporter = super::throttled(format!("服务端核心 {mc}"), progress);
                super::download_verified(deps, &plan, &target, &server.sha1, server.size, cancel, &reporter).await?;
            }
            progress("下载完成".into());
        }
    }
    ensure_not_cancelled(cancel)?;

    step(3);
    let descriptor = loader::bracketed(profile.data.get("MOJMAPS").and_then(|d| d.server.as_deref()));
    match (descriptor, &downloads.server_mappings) {
        (Some(descriptor), Some(mappings)) => {
            let target = deps.libraries().join(descriptor_path(&descriptor).context("Mojmap坐标无效")?);
            super::download_artifact(deps, mappings, &target, cancel, progress, "服务端").await?;
            progress("服务端 Mojmap已完成".into());
        }
        _ => progress("无需下载".into()),
    }
    ensure_not_cancelled(cancel)?;

    step(4);
    let command: Vec<String> = vec![
        deps.java.to_string_lossy().into_owned(),
        "-jar".into(),
        installer.to_string_lossy().into_owned(),
        "--installServer".into(),
        deps.mc_dir.to_string_lossy().into_owned(),
    ];
    match loader::run_tool(&command, &deps.mc_dir, "无法启动mod载入器安装程序", cancel, None, progress).await? {
        Some(0) => {
            progress("安装成功".into());
            Ok(())
        }
        code => bail!("mod载入器安装失败 {}", code.unwrap_or(-1)),
    }
}

fn fabric_installer_path(deps: &Deps) -> PathBuf {
    deps.mc_dir.join(format!("fabric-installer-{FABRIC_INSTALLER_VERSION}.jar"))
}

/// `ensureFabricServerInstaller`.
async fn ensure_fabric_installer(deps: &Deps, cancel: &Cancellation, progress: Progress<'_>) -> Result<PathBuf> {
    let installer = fabric_installer_path(deps);
    let i = installer.clone();
    if tokio::task::spawn_blocking(move || i.is_file() && sha1_matches(&i, FABRIC_INSTALLER_SHA1)).await? {
        progress("Fabric安装器已存在".into());
        return Ok(installer);
    }
    std::fs::create_dir_all(&deps.mc_dir)?;
    progress("开始下载Fabric安装器".into());
    let plan = super::source_plan(deps, FABRIC_INSTALLER_URL, None, FABRIC_INSTALLER_SHA1);
    let reporter = super::throttled("Fabric安装器".into(), progress);
    super::download_verified(deps, &plan, &installer, FABRIC_INSTALLER_SHA1, 0, cancel, &reporter).await?;
    let i = installer.clone();
    ensure!(tokio::task::spawn_blocking(move || i.is_file() && sha1_matches(&i, FABRIC_INSTALLER_SHA1)).await?, "Fabric安装器下载校验失败");
    progress("Fabric安装器下载完成".into());
    Ok(installer)
}

/// `fabricServerRuntimeCacheDir`.
fn fabric_cache_dir(deps: &Deps) -> PathBuf {
    deps.mc_dir.join("server-runtimes").join(super::fabric::MC_VERSION).join(format!("fabric-{}", super::fabric::LOADER_VERSION))
}

/// `installFabricTestServer`.
async fn install_fabric_runtime(deps: &Deps, cancel: &Cancellation, progress: Progress<'_>) -> Result<()> {
    let d = deps.clone();
    if tokio::task::spawn_blocking(move || find_fabric_runtime(&d)).await?.is_some() {
        progress("Fabric测试服务端已存在".into());
        return Ok(());
    }
    let installer = fabric_installer_path(deps);
    let i = installer.clone();
    ensure!(
        tokio::task::spawn_blocking(move || i.is_file() && sha1_matches(&i, FABRIC_INSTALLER_SHA1)).await?,
        "Fabric安装器缺失或校验失败，请重新下载"
    );
    let cache = fabric_cache_dir(deps);
    ensure_dir_without_links(&deps.mc_dir, &cache)?;
    let staging = cache.join(format!("runtime-{}", uuid::Uuid::now_v7().simple()));
    std::fs::create_dir(&staging).with_context(|| format!("无法创建Fabric服务端安装目录: {}", staging.display()))?;
    let command: Vec<String> = vec![
        deps.java.to_string_lossy().into_owned(),
        "-jar".into(),
        installer.to_string_lossy().into_owned(),
        "server".into(),
        "-dir".into(),
        staging.to_string_lossy().into_owned(),
        "-mcversion".into(),
        super::fabric::MC_VERSION.into(),
        "-loader".into(),
        super::fabric::LOADER_VERSION.into(),
        "-downloadMinecraft".into(),
    ];
    progress("正在安装Fabric测试服务端".into());
    let code = loader::run_tool(&command, &staging, "无法启动Fabric安装器", cancel, Some((FABRIC_INSTALLER_TIMEOUT, "Fabric服务端安装超时")), progress).await?;
    ensure!(code == Some(0), "Fabric服务端安装失败: {}", code.unwrap_or(-1));
    let s = staging.clone();
    tokio::task::spawn_blocking(move || finish_fabric_runtime(&s)).await??;
    progress("Fabric测试服务端已就绪".into());
    Ok(())
}

/// The checks after the Fabric installer, then the file list and the ready file.
fn finish_fabric_runtime(staging: &Path) -> Result<()> {
    ensure!(staging.join(FABRIC_SERVER_LAUNCHER_JAR).is_file(), "Fabric服务端launcher缺失");
    ensure!(staging.join(FABRIC_SERVER_JAR).is_file(), "Minecraft服务端JAR缺失");
    ensure!(staging.join(fabric_loader_jar()).is_file(), "Fabric Loader运行库缺失");
    write_files_manifest(staging)?;
    std::fs::write(staging.join(READY_FILE), ready_text(staging)?)?;
    ensure!(complete_fabric_runtime(staging), "Fabric服务端安装产物校验失败");
    Ok(())
}

/// The ready file's text: versions and the SHA-1 of the three main files.
fn ready_text(dir: &Path) -> Result<String> {
    Ok([
        super::fabric::MC_VERSION.to_owned(),
        "Fabric".into(),
        super::fabric::LOADER_VERSION.into(),
        sha1_file(&dir.join(FABRIC_SERVER_LAUNCHER_JAR))?,
        sha1_file(&dir.join(FABRIC_SERVER_JAR))?,
        sha1_file(&dir.join(fabric_loader_jar()))?,
    ]
    .join("\n"))
}

fn link_or_missing(path: &Path) -> Option<std::fs::Metadata> {
    std::fs::symlink_metadata(path).ok().filter(|m| !m.file_type().is_symlink())
}

/// `hasSafeDirectoryChain`, from `base` down: every folder from `base` to `dir` exists and none is
/// a link.
fn safe_chain(base: &Path, dir: &Path) -> bool {
    let Ok(rest) = dir.strip_prefix(base) else { return false };
    let mut current = base.to_path_buf();
    if !link_or_missing(&current).is_some_and(|m| m.is_dir()) {
        return false;
    }
    for part in rest.components() {
        current.push(part);
        if !link_or_missing(&current).is_some_and(|m| m.is_dir()) {
            return false;
        }
    }
    true
}

/// `ensureDirectoryWithoutSymlink`, from `base` down: creates the missing folders, refuses links.
fn ensure_dir_without_links(base: &Path, dir: &Path) -> Result<()> {
    let rest = dir.strip_prefix(base).with_context(|| format!("Fabric服务端运行目录无效: {}", dir.display()))?;
    std::fs::create_dir_all(base)?;
    let mut current = base.to_path_buf();
    for path in std::iter::once(None).chain(rest.components().map(Some)) {
        if let Some(part) = path {
            current.push(part);
        }
        match std::fs::symlink_metadata(&current) {
            Ok(meta) => ensure!(!meta.file_type().is_symlink() && meta.is_dir(), "Fabric服务端运行目录不能是符号链接或非目录: {}", current.display()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => std::fs::create_dir(&current)?,
            Err(error) => return Err(error.into()),
        }
    }
    Ok(())
}

/// `hasNoSymlinkBelowRoot`: no link on the way from `root` to `relative`, and every step but the
/// last is a folder.
fn no_links_below(root: &Path, relative: &str) -> bool {
    let parts: Vec<&str> = relative.split('/').collect();
    let mut current = root.to_path_buf();
    for (index, part) in parts.iter().enumerate() {
        current.push(part);
        let Ok(meta) = std::fs::symlink_metadata(&current) else {
            // A missing last step is fine for a target that is about to be written.
            return index == parts.len() - 1;
        };
        if meta.file_type().is_symlink() || (index < parts.len() - 1 && !meta.is_dir()) {
            return false;
        }
    }
    true
}

/// `isManagedFabricRuntimePath`: the launcher, the server jar, the launcher properties and jars
/// under `libraries/`, as a plain relative path with `/`.
pub fn managed_path(relative: &str) -> bool {
    let plain = !relative.is_empty()
        && !relative.starts_with('/')
        && !relative.contains('\\')
        && !relative.contains(':')
        && relative.split('/').all(|s| !s.is_empty() && s != "." && s != "..");
    if !plain {
        return false;
    }
    [FABRIC_SERVER_LAUNCHER_JAR, FABRIC_SERVER_JAR, FABRIC_LAUNCHER_PROPERTIES].contains(&relative)
        || (relative.starts_with("libraries/") && relative.rsplit('/').next().is_some_and(|name| name.to_ascii_lowercase().ends_with(".jar")))
}

fn is_sha1(value: &str) -> bool {
    value.len() == 40 && value.chars().all(|c| c.is_ascii_hexdigit())
}

fn required_files() -> [String; 3] {
    [FABRIC_SERVER_LAUNCHER_JAR.into(), FABRIC_SERVER_JAR.into(), fabric_loader_jar()]
}

/// `writeFabricRuntimeFilesManifest`: `<path>\t<sha1>` per managed file, sorted.
fn write_files_manifest(root: &Path) -> Result<()> {
    fn walk(root: &Path, dir: &Path, out: &mut Vec<String>) -> Result<()> {
        for entry in std::fs::read_dir(dir)? {
            let entry = entry?;
            let path = entry.path();
            let relative = path.strip_prefix(root).unwrap_or(&path).to_string_lossy().replace('\\', "/");
            let kind = entry.file_type()?;
            ensure!(!kind.is_symlink(), "Fabric安装器生成了不支持的符号链接: {relative}");
            if kind.is_dir() {
                walk(root, &path, out)?;
            } else if kind.is_file() && managed_path(&relative) {
                out.push(format!("{relative}\t{}", sha1_file(&path)?));
            }
        }
        Ok(())
    }
    let mut entries = Vec::new();
    walk(root, root, &mut entries)?;
    let names: Vec<&str> = entries.iter().map(|e| e.split('\t').next().unwrap_or("")).collect();
    ensure!(required_files().iter().all(|r| names.contains(&r.as_str())), "Fabric服务端运行文件清单缺少必要文件");
    entries.sort();
    std::fs::write(root.join(FILES_MANIFEST), entries.join("\n"))?;
    Ok(())
}

/// `readFabricRuntimeFilesManifest`.
fn read_files_manifest(root: &Path) -> Result<Vec<(String, String)>> {
    let manifest = root.join(FILES_MANIFEST);
    ensure!(link_or_missing(&manifest).is_some_and(|m| m.is_file()), "Fabric服务端运行文件清单缺失或无效");
    let text = std::fs::read_to_string(&manifest)?;
    let mut entries = Vec::new();
    for line in text.lines() {
        let (relative, sha1) = line.split_once('\t').filter(|(r, _)| !r.is_empty()).context("Fabric服务端运行文件清单格式无效")?;
        ensure!(managed_path(relative) && is_sha1(sha1), "Fabric服务端运行文件清单条目无效");
        ensure!(no_links_below(root, relative), "Fabric服务端缓存路径包含符号链接: {relative}");
        entries.push((relative.to_owned(), sha1.to_owned()));
    }
    let mut names: Vec<&str> = entries.iter().map(|(r, _)| r.as_str()).collect();
    names.sort_unstable();
    names.dedup();
    ensure!(!entries.is_empty() && names.len() == entries.len(), "Fabric服务端运行文件清单无效");
    ensure!(required_files().iter().all(|r| names.contains(&r.as_str())), "Fabric服务端运行文件清单缺少必要文件");
    Ok(entries)
}

/// `isCompleteFabricServerRuntime`: the ready file matches and every listed file hashes right.
fn complete_fabric_runtime(dir: &Path) -> bool {
    let ready = dir.join(READY_FILE);
    if !link_or_missing(&ready).is_some_and(|m| m.is_file()) {
        return false;
    }
    let regular = |relative: &str| no_links_below(dir, relative) && link_or_missing(&dir.join(relative)).is_some_and(|m| m.is_file());
    if !required_files().iter().all(|r| regular(r)) {
        return false;
    }
    let Ok(expected) = ready_text(dir) else { return false };
    if std::fs::read_to_string(&ready).ok().as_deref() != Some(expected.as_str()) {
        return false;
    }
    match read_files_manifest(dir) {
        Ok(entries) => entries.iter().all(|(relative, sha1)| regular(relative) && sha1_matches(&dir.join(relative), sha1)),
        Err(_) => false,
    }
}

/// `findFabricServerRuntime`: the newest complete runtime in the cache. Blocking.
pub fn find_fabric_runtime(deps: &Deps) -> Option<PathBuf> {
    let cache = fabric_cache_dir(deps);
    if !safe_chain(&deps.mc_dir, &cache) {
        return None;
    }
    let mut candidates: Vec<(std::time::SystemTime, PathBuf)> = std::fs::read_dir(&cache)
        .ok()?
        .filter_map(|e| e.ok())
        .filter(|e| e.file_name().to_string_lossy().starts_with("runtime-"))
        .filter(|e| e.file_type().is_ok_and(|t| t.is_dir() && !t.is_symlink()))
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .collect();
    candidates.sort_by_key(|c| std::cmp::Reverse(c.0));
    candidates.into_iter().map(|(_, path)| path).find(|path| complete_fabric_runtime(path))
}

/// `stageFabricServerRuntime`: copies the cached runtime's listed files into `work_dir`, skipping
/// files that are already equal, each written through a checked temporary file. Blocking.
pub fn stage_fabric_runtime(deps: &Deps, work_dir: &Path) -> Result<()> {
    let source_root = find_fabric_runtime(deps).context("Fabric测试服务端未安装或安装不完整，请先下载测试服务端")?;
    std::fs::create_dir_all(work_dir)?;
    ensure!(link_or_missing(work_dir).is_some_and(|m| m.is_dir()), "Fabric服务端运行目录不能是符号链接或非目录: {}", work_dir.display());
    for (relative, sha1) in read_files_manifest(&source_root)? {
        let source = source_root.join(&relative);
        ensure!(no_links_below(&source_root, &relative), "Fabric服务端运行源路径包含符号链接: {relative}");
        ensure!(link_or_missing(&source).is_some_and(|m| m.is_file()), "Fabric服务端运行源文件不是普通文件: {relative}");
        ensure!(sha1_matches(&source, &sha1), "Fabric服务端缓存文件校验失败: {relative}");
        let target = work_dir.join(&relative);
        let parent = target.parent().context("Fabric服务端运行文件路径无效")?.to_path_buf();
        ensure_dir_without_links(work_dir, &parent)?;
        ensure!(no_links_below(work_dir, &relative), "Fabric服务端运行文件目标不能是符号链接: {relative}");
        match std::fs::symlink_metadata(&target) {
            Ok(meta) if meta.is_file() => {
                let same = std::fs::metadata(&source).is_ok_and(|s| s.len() == meta.len()) && sha1_matches(&target, &sha1);
                if same {
                    continue;
                }
            }
            Ok(_) => bail!("Fabric服务端运行文件目标不是普通文件: {relative}"),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
        let name = target.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let temporary = parent.join(format!("{name}.rdi-{}.tmp", uuid::Uuid::now_v7().simple()));
        let result = (|| -> Result<()> {
            std::fs::copy(&source, &temporary)?;
            let same_size = std::fs::metadata(&temporary)?.len() == std::fs::metadata(&source)?.len();
            ensure!(same_size && sha1_matches(&temporary, &sha1), "Fabric服务端运行文件暂存校验失败: {relative}");
            ensure!(no_links_below(work_dir, &relative), "Fabric服务端运行目标路径包含符号链接: {relative}");
            std::fs::rename(&temporary, &target)?;
            Ok(())
        })();
        if temporary.exists() {
            let _ = std::fs::remove_file(&temporary);
        }
        result?;
    }
    Ok(())
}

#[cfg(test)]
pub(super) fn finish_for_test(staging: &Path) -> Result<()> {
    finish_fabric_runtime(staging)
}
