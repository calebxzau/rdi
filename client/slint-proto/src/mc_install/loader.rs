//! Forge/NeoForge install: `McInstall.ensureDesktopLaunchLoader` and `downloadLoaderTask2`.
use super::meta::{self, LoaderVersion};
use super::{Deps, Progress, ensure_not_cancelled, opens_as_zip, sha1_matches};
use crate::mc_launch::manifest::{Artifact, Library, Manifest, descriptor_path, normalize_loader};
use crate::tasks::Cancellation;
use anyhow::{Context, Result, bail, ensure};
use serde::Deserialize;
use std::collections::{BTreeMap, HashMap};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock, Mutex};

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct InstallProfile {
    /// Where `--installServer` looks for the vanilla server jar (`{LIBRARY_DIR}`, `{MINECRAFT_VERSION}`).
    #[serde(rename = "serverJarPath")]
    pub server_jar_path: Option<String>,
    pub libraries: Vec<Library>,
    pub data: BTreeMap<String, InstallData>,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct InstallData {
    pub client: Option<String>,
    pub server: Option<String>,
}

pub struct LaunchState {
    pub manifest: Manifest,
    pub profile: InstallProfile,
}

pub fn installer_path(deps: &Deps, version: &LoaderVersion) -> PathBuf {
    deps.mc_dir.join(format!("{}-{}-installer.jar", version.mc_version, version.loader))
}

fn version_json(deps: &Deps, version: &LoaderVersion) -> PathBuf {
    deps.versions().join(version.dir_name).join(format!("{}.json", version.dir_name))
}

pub(super) fn read_entry(installer: &Path, name: &str) -> Result<Option<String>> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(installer)?)?;
    let Ok(mut entry) = zip.by_name(name) else {
        return Ok(None);
    };
    let mut text = String::new();
    entry.read_to_string(&mut text)?;
    Ok(Some(text))
}

/// `readLoaderLaunchState`: the installer's own `version.json`, normalized, is the launch manifest.
/// Unlike Kotlin, Rust does not rewrite the version JSON; Kotlin rewrites it on its next read.
pub fn read_launch_state(deps: &Deps, version: &LoaderVersion) -> Result<Option<LaunchState>> {
    let json = version_json(deps, version);
    let Ok(existing) = std::fs::read_to_string(&json) else {
        return Ok(None);
    };
    let Ok(existing) = serde_json::from_str::<Manifest>(&existing) else {
        tracing::warn!(path = %json.display(), "加载器启动清单无法解析");
        return Ok(None);
    };
    let installer = installer_path(deps, version);
    if !installer.is_file() {
        return Ok(None);
    }
    let profile = match read_entry(&installer, "install_profile.json").ok().flatten().map(|t| serde_json::from_str::<InstallProfile>(&t)) {
        Some(Ok(p)) => p,
        _ => return Ok(None),
    };
    let from_installer = read_entry(&installer, "version.json").ok().flatten().and_then(|t| serde_json::from_str::<Manifest>(&t).ok());
    let manifest = normalize_loader(from_installer.unwrap_or(existing), version.dir_name, version.mc_version);
    Ok(Some(LaunchState { manifest, profile }))
}

/// `loaderLibrariesReady`.
pub fn libraries_ready(deps: &Deps, libraries: &[Library]) -> bool {
    let dir = deps.libraries();
    libraries.iter().filter(|l| l.allowed(&deps.host)).all(|library| {
        let artifacts: Vec<Artifact> = library.main_artifact().into_iter().chain(library.native_artifact(&deps.host)).collect();
        artifacts.iter().all(|a| {
            let Some(path) = a.path.clone().filter(|p| !p.trim().is_empty()).or_else(|| descriptor_path(&library.name)) else {
                return false;
            };
            let file = dir.join(path);
            let ok = std::fs::metadata(&file).is_ok_and(|m| m.is_file() && m.len() > 0);
            ok && if a.sha1.trim().is_empty() { opens_as_zip(&file) } else { sha1_matches(&file, &a.sha1) }
        })
    })
}

pub(super) fn bracketed(raw: Option<&str>) -> Option<String> {
    let t = raw?.trim();
    t.strip_prefix('[')?.strip_suffix(']').map(|s| s.trim().to_owned()).filter(|s| !s.is_empty())
}

/// `loaderInstallProfileReady`: the universal jar and the processor outputs exist.
pub fn profile_ready(deps: &Deps, profile: &InstallProfile) -> bool {
    let universal: Vec<Library> = profile
        .libraries
        .iter()
        .filter(|l| l.name.split('@').next().unwrap_or("").split(':').nth(3).is_some_and(|c| c.eq_ignore_ascii_case("universal")))
        .cloned()
        .collect();
    if !libraries_ready(deps, &universal) {
        return false;
    }
    ["MC_SRG", "PATCHED"]
        .iter()
        .filter_map(|k| bracketed(profile.data.get(*k).and_then(|d| d.client.as_deref())))
        .filter_map(|d| descriptor_path(&d))
        .all(|p| {
            let f = deps.libraries().join(p);
            std::fs::metadata(&f).is_ok_and(|m| m.is_file() && m.len() > 0) && opens_as_zip(&f)
        })
}

fn ready(deps: &Deps, version: &LoaderVersion) -> bool {
    match read_launch_state(deps, version) {
        Ok(Some(state)) => libraries_ready(deps, &state.manifest.libraries) && profile_ready(deps, &state.profile),
        _ => false,
    }
}

/// One install per loader at a time, across all sessions (`McInstall.loaderInstalls`).
static INSTALLS: LazyLock<Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>> = LazyLock::new(Default::default);

/// The lock of one loader's installs; the test server install takes it too, since both installers
/// write the shared `libraries`.
pub(super) fn install_lock(version: &LoaderVersion) -> Arc<tokio::sync::Mutex<()>> {
    let key = format!("{}|{}", version.mc_version, version.loader);
    INSTALLS.lock().unwrap().entry(key).or_default().clone()
}

/// `ensureDesktopLaunchLoader`.
pub async fn ensure(deps: &Deps, version: &LoaderVersion, cancel: &Cancellation, progress: Progress<'_>) -> Result<()> {
    ensure_not_cancelled(cancel)?;
    let (d, v) = (deps.clone(), *version);
    if tokio::task::spawn_blocking(move || ready(&d, &v)).await? {
        return Ok(());
    }
    let lock = install_lock(version);
    let guard = match lock.try_lock() {
        Ok(guard) => guard,
        Err(_) => {
            progress(format!("等待相同{}安装完成...", version.loader));
            crate::download::cancellable(cancel, async { Ok(lock.lock().await) }).await?
        }
    };
    let (d, v) = (deps.clone(), *version);
    if !tokio::task::spawn_blocking(move || ready(&d, &v)).await? {
        progress(format!("开始自动安装{}...", version.loader));
        install(deps, version, cancel, progress).await?;
    }
    drop(guard);
    let (d, v) = (deps.clone(), *version);
    let state = tokio::task::spawn_blocking(move || read_launch_state(&d, &v))
        .await??
        .with_context(|| format!("{}安装后缺少有效启动manifest", version.loader))?;
    let (d, s) = (deps.clone(), state);
    let ok = tokio::task::spawn_blocking(move || libraries_ready(&d, &s.manifest.libraries) && profile_ready(&d, &s.profile)).await?;
    ensure!(ok, "{}安装后运行库仍不完整", version.loader);
    Ok(())
}

fn library_key(library: &Library) -> String {
    let path = library.main_artifact().and_then(|a| a.path).unwrap_or_default();
    let classifiers = library.downloads.classifiers.as_ref().map(|c| c.keys().cloned().collect::<Vec<_>>().join(";")).unwrap_or_default();
    format!("{}|{path}|{classifiers}", library.name)
}

/// `prepareInstallerTask2`: `launcher_profiles.json`, the bootstrapper and the verified installer.
/// Returns the bootstrapper and the installer.
pub(super) async fn ensure_installer(deps: &Deps, version: &LoaderVersion, cancel: &Cancellation, progress: Progress<'_>) -> Result<(PathBuf, PathBuf)> {
    let loader = version.loader;
    std::fs::create_dir_all(&deps.mc_dir)?;
    std::fs::write(deps.mc_dir.join("launcher_profiles.json"), meta::LAUNCHER_PROFILES)?;
    let booter = deps.mc_dir.join("forge-install-bootstrapper.jar");
    std::fs::write(&booter, meta::INSTALL_BOOTSTRAPPER)?;
    let installer = installer_path(deps, version);
    let (i, s) = (installer.clone(), version.installer_sha1);
    if tokio::task::spawn_blocking(move || i.exists() && sha1_matches(&i, s)).await? {
        progress("安装器已存在".into());
    } else {
        progress(format!("下载{loader} 安装器"));
        let plan = super::source_plan(deps, version.installer_url, None, version.installer_sha1);
        let reporter = super::throttled(format!("{loader}安装器"), progress);
        super::download_verified(deps, &plan, &installer, version.installer_sha1, 0, cancel, &reporter)
            .await
            .context("安装器下载失败")?;
    }
    Ok((booter, installer))
}

/// `downloadLoaderTask2`: installer, parse, libraries, Mojmap, then the installer process.
async fn install(deps: &Deps, version: &LoaderVersion, cancel: &Cancellation, progress: Progress<'_>) -> Result<()> {
    // 下载安装器
    let (booter, installer) = ensure_installer(deps, version, cancel, progress).await?;
    ensure_not_cancelled(cancel)?;
    // 解析安装器
    let profile_text = read_entry(&installer, "install_profile.json")?.context("安装器中缺少 install_profile.json")?;
    let profile: InstallProfile = serde_json::from_str(&profile_text).context("安装器install_profile.json无效")?;
    let raw_text = read_entry(&installer, "version.json")?.context("安装器中既没有version.json，也没有可用的legacy versionInfo/json")?;
    let mut raw: serde_json::Value = serde_json::from_str(&raw_text).context("安装器version.json无效")?;
    let manifest = normalize_loader(serde_json::from_value(raw.clone())?, version.dir_name, version.mc_version);
    if let Some(o) = raw.as_object_mut() {
        o.insert("id".into(), version.dir_name.into());
        o.insert("inheritsFrom".into(), manifest.inherits_from.clone().into());
        o.insert("jar".into(), manifest.jar.clone().into());
    }
    let json = version_json(deps, version);
    std::fs::create_dir_all(json.parent().unwrap())?;
    std::fs::write(&json, serde_json::to_string(&raw)?)?;
    progress("解析完成".into());
    // 下载依赖
    let mut seen = std::collections::HashSet::new();
    let libraries: Vec<Library> = profile
        .libraries
        .iter()
        .chain(&manifest.libraries)
        .filter(|l| seen.insert(library_key(l)))
        .filter(|l| l.allowed(&deps.host))
        .cloned()
        .collect();
    let total = libraries.len();
    progress(format!("下载{total}个运行库"));
    for library in &libraries {
        ensure_not_cancelled(cancel)?;
        if let Some(artifact) = library.main_artifact() {
            let path = artifact.path.clone().filter(|p| !p.trim().is_empty()).or_else(|| descriptor_path(&library.name)).with_context(|| format!("运行库{}缺少库路径", library.name))?;
            library_artifact(deps, &artifact, &deps.libraries().join(path), Some(&installer), cancel, progress).await?;
        }
        if let Some(native) = library.native_artifact(&deps.host)
            && let Some(path) = native.path.clone()
        {
            library_artifact(deps, &native, &deps.libraries().join(path), Some(&installer), cancel, progress).await?;
        }
    }
    // 下载客户端Mojmap
    let vanilla = meta::vanilla(version.mc_version)?;
    if let (Some(descriptor), Some(mappings)) = (
        bracketed(profile.data.get("MOJMAPS").and_then(|d| d.client.as_deref())),
        vanilla.downloads.as_ref().and_then(|d| d.client_mappings.clone()),
    ) {
        let target = deps.libraries().join(descriptor_path(&descriptor).context("Mojmap坐标无效")?);
        super::download_artifact(deps, &mappings, &target, cancel, progress, "客户端").await?;
        progress("客户端 Mojmap已完成".into());
    }
    ensure_not_cancelled(cancel)?;
    // 运行安装器
    run_installer(deps, &booter, &installer, cancel, progress).await
}

/// `downloadLibraryArtifact`: an artifact without URL is first taken from the installer's `maven/`.
async fn library_artifact(deps: &Deps, artifact: &Artifact, target: &Path, installer: Option<&Path>, cancel: &Cancellation, progress: Progress<'_>) -> Result<()> {
    if target.exists() && (artifact.sha1.trim().is_empty() || sha1_matches(target, &artifact.sha1)) {
        return Ok(());
    }
    std::fs::create_dir_all(target.parent().context("运行库路径无效")?)?;
    if artifact.url.trim().is_empty()
        && let (Some(installer), Some(path)) = (installer, artifact.path.as_deref())
        && extract_from_installer(installer, path.trim_start_matches('/'), target).unwrap_or(false)
    {
        if artifact.sha1.trim().is_empty() || sha1_matches(target, &artifact.sha1) {
            return Ok(());
        }
        std::fs::remove_file(target)?;
    }
    let url = super::resolve_artifact_url(artifact);
    ensure!(!url.is_empty(), "{} 下载链接为空", target.file_name().map(|n| n.to_string_lossy()).unwrap_or_default());
    let plan = super::source_plan(deps, &url, None, &artifact.sha1);
    let name = target.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let reporter = super::throttled(name, progress);
    super::download_verified(deps, &plan, target, &artifact.sha1, artifact.size, cancel, &reporter).await
}

fn extract_from_installer(installer: &Path, path: &str, target: &Path) -> Result<bool> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(installer)?)?;
    let name = if zip.index_for_name(&format!("maven/{path}")).is_some() {
        format!("maven/{path}")
    } else if zip.index_for_name(path).is_some() {
        path.to_owned()
    } else {
        return Ok(false);
    };
    let mut entry = zip.by_name(&name)?;
    let mut out = std::fs::File::create(target)?;
    std::io::copy(&mut entry, &mut out)?;
    Ok(true)
}

/// `runInstallerBootstrapper`: `java -cp <bootstrapper><sep><installer> com.bangbang93.ForgeInstaller <mc dir>`.
async fn run_installer(deps: &Deps, booter: &Path, installer: &Path, cancel: &Cancellation, progress: Progress<'_>) -> Result<()> {
    let classpath = format!("{}{}{}", booter.display(), deps.host.path_separator, installer.display());
    let command: Vec<String> = vec![
        deps.java.to_string_lossy().into_owned(),
        "-cp".into(),
        classpath,
        "com.bangbang93.ForgeInstaller".into(),
        deps.mc_dir.to_string_lossy().into_owned(),
    ];
    match run_tool(&command, &deps.mc_dir, "无法启动mod载入器安装程序", cancel, None, progress).await? {
        Some(0) => {
            progress("安装成功".into());
            Ok(())
        }
        code => bail!("mod载入器安装失败: {}", code.unwrap_or(-1)),
    }
}

/// Runs an installer process in `dir`, every output line to `progress`, and returns its exit code.
/// `start_error` names a failed start; a cancel kills it with 「Minecraft安装已取消」, and so does
/// `timeout` with its message.
pub(super) async fn run_tool(
    command: &[String],
    dir: &Path,
    start_error: &str,
    cancel: &Cancellation,
    timeout: Option<(std::time::Duration, &str)>,
    progress: Progress<'_>,
) -> Result<Option<i32>> {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
    let mut started = crate::mc_launch::spawn(command, dir, move |line| {
        let _ = tx.send(line);
    })
    .context(start_error.to_owned())?;
    let deadline = timeout.map(|(t, message)| (std::time::Instant::now() + t, message));
    // An interval, not a fresh sleep per line, so steady output cannot starve the checks.
    let mut tick = tokio::time::interval(std::time::Duration::from_millis(200));
    loop {
        tokio::select! {
            line = rx.recv() => match line {
                Some(line) if !line.is_empty() => {
                    tracing::info!(target: "installer", "{line}");
                    progress(line);
                }
                _ => break,
            },
            _ = tick.tick() => {
                let expired = deadline.filter(|(at, _)| std::time::Instant::now() >= *at).map(|(_, m)| m);
                if cancel.cancelled() || expired.is_some() {
                    if let Err(error) = started.child.kill() {
                        tracing::warn!(%error, "结束安装程序失败");
                    }
                    let _ = started.child.wait();
                    match expired {
                        Some(message) if !cancel.cancelled() => bail!("{message}"),
                        _ => bail!("Minecraft安装已取消"),
                    }
                }
            }
        }
    }
    let status = tokio::task::spawn_blocking(move || started.child.wait()).await??;
    Ok(status.code())
}
