//! J5c: the Fabric 1.20.1 client profile, ported from `ClientLoaderRuntime.kt`,
//! `FabricClientProfile.kt` and the Fabric part of `McInstall.kt`. The profile comes from
//! `meta.fabricmc.net`, every library must live on `https://maven.fabricmc.net` under its Maven path
//! and carry a SHA-1; the profile file is written last, so a present and valid profile with verified
//! libraries means "installed" for Compose and Rust alike.
use super::{Deps, Progress, ensure_not_cancelled, sha1_file, source_plan};
use crate::mc_launch::manifest::{Artifact, Library, Manifest, descriptor_path};
use crate::tasks::Cancellation;
use anyhow::{Context, Result, anyhow, ensure};
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock, Mutex};

pub const MC_VERSION: &str = "1.20.1";
/// `FABRIC_1_20_1_LOADER_VERSION` in `common/model/.../ServerLoaderRuntime.kt`.
pub const LOADER_VERSION: &str = "0.19.5";
pub const PROFILE_ID: &str = "fabric-loader-0.19.5-1.20.1";
pub const PROFILE_URL: &str = "https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.19.5/profile/json";
const MAIN_CLASS: &str = "net.fabricmc.loader.impl.launch.knot.KnotClient";
const MAVEN_HOST: &str = "maven.fabricmc.net";

pub fn profile_file(versions: &Path) -> PathBuf {
    versions.join(PROFILE_ID).join(format!("{PROFILE_ID}.json"))
}

fn is_sha1(value: &str) -> bool {
    value.len() == 40 && value.chars().all(|c| c.is_ascii_hexdigit())
}

fn coordinate_part(value: &str) -> bool {
    !value.is_empty() && value.chars().all(|c| c.is_ascii_alphanumeric() || "_.+-".contains(c))
}

/// `parseCoordinate`: group, artifact and version.
fn coordinate(name: &str) -> Result<(String, String, String)> {
    let (coordinates, extension) = name.split_once('@').unwrap_or((name, "jar"));
    let parts: Vec<&str> = coordinates.split(':').collect();
    ensure!((3..=4).contains(&parts.len()) && parts.iter().all(|p| coordinate_part(p)), "Invalid Maven coordinate: {name}");
    ensure!(coordinate_part(extension), "Invalid Maven extension: {name}");
    Ok((parts[0].into(), parts[1].into(), parts[2].into()))
}

fn safe_maven_path(path: &str) -> bool {
    !path.trim().is_empty()
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.contains(':')
        && path.split('/').all(|s| !s.trim().is_empty() && s != "." && s != "..")
}

/// `mainArtifact` of `client/mclaunch`: an explicit artifact is taken as it is.
fn launch_artifact(library: &Library) -> Option<Artifact> {
    library.downloads.artifact.clone().or_else(|| library.main_artifact())
}

/// `FabricClientProfile.validate`.
pub fn validate(manifest: &Manifest, require_checksums: bool) -> Result<()> {
    ensure!(manifest.id == PROFILE_ID, "Fabric profile id mismatch: {}", manifest.id);
    ensure!(manifest.inherits_from.as_deref() == Some(MC_VERSION), "Fabric profile Minecraft version mismatch");
    ensure!(manifest.jar.as_deref().is_none_or(|j| j == MC_VERSION), "Fabric profile declares an unexpected runtime JAR");
    ensure!(manifest.main_class.as_deref() == Some(MAIN_CLASS), "Unexpected Fabric client main class: {:?}", manifest.main_class);
    let mut by_ga: HashMap<String, String> = HashMap::new();
    let mut coordinates = vec![];
    for library in &manifest.libraries {
        let (group, artifact_id, version) = coordinate(&library.name)?;
        ensure!(
            !(group == "net.minecraftforge" && artifact_id == "forge") && !(group == "net.neoforged" && artifact_id == "neoforge"),
            "Fabric profile contains a competing Forge loader: {}",
            library.name
        );
        let ga = format!("{group}:{artifact_id}");
        let previous = by_ga.entry(ga.clone()).or_insert_with(|| library.name.clone());
        ensure!(*previous == library.name, "Conflicting Maven coordinates for {ga}: {previous} and {}", library.name);
        let artifact = launch_artifact(library).with_context(|| format!("Fabric library has no main artifact: {}", library.name))?;
        if library.downloads.artifact.is_none()
            && let Some(sha1) = &library.sha1
        {
            ensure!(is_sha1(sha1), "Malformed SHA1 for {}", library.name);
        }
        let canonical = descriptor_path(&library.name).with_context(|| format!("Invalid Maven coordinate: {}", library.name))?;
        ensure!(safe_maven_path(&canonical), "Unsafe Fabric library path: {canonical}");
        let path = artifact.path.clone().filter(|p| !p.trim().is_empty()).unwrap_or_else(|| canonical.clone());
        ensure!(safe_maven_path(&path), "Unsafe Fabric library path: {path}");
        ensure!(path == canonical, "Fabric artifact path does not match its Maven coordinate: {}", library.name);
        let url = reqwest::Url::parse(&artifact.url).map_err(|_| anyhow!("Fabric profile library URL must use https://{MAVEN_HOST}: {}", artifact.url))?;
        ensure!(
            url.scheme() == "https"
                && url.host_str() == Some(MAVEN_HOST)
                && url.username().is_empty()
                && url.password().is_none()
                && url.port().is_none(),
            "Fabric profile library URL must use https://{MAVEN_HOST}: {}",
            artifact.url
        );
        ensure!(url.query().is_none() && url.fragment().is_none(), "Fabric profile library URL cannot include query or fragment: {}", artifact.url);
        ensure!(url.path().trim_start_matches('/') == canonical, "Fabric library URL path does not match its Maven coordinate: {}", artifact.url);
        if !artifact.sha1.trim().is_empty() {
            ensure!(is_sha1(&artifact.sha1), "Malformed SHA1 for {}", library.name);
        }
        if require_checksums {
            ensure!(is_sha1(&artifact.sha1), "Missing or malformed SHA1 for {}", library.name);
        }
        coordinates.push((group, artifact_id, version));
    }
    let count = |g: &str, a: &str, v: &str| coordinates.iter().filter(|(cg, ca, cv)| cg == g && ca == a && cv == v).count();
    ensure!(count("net.fabricmc", "fabric-loader", LOADER_VERSION) == 1, "Fabric loader coordinate must occur exactly once");
    ensure!(count("net.fabricmc", "intermediary", MC_VERSION) == 1, "Fabric intermediary coordinate must occur exactly once");
    Ok(())
}

/// `readFabricProfile`: a missing or unreadable profile counts as absent.
pub fn read_profile(versions: &Path) -> Option<(Manifest, Value)> {
    let file = profile_file(versions);
    let text = std::fs::read_to_string(&file).ok()?;
    let parsed = serde_json::from_str::<Value>(&text).and_then(|raw| Ok((serde_json::from_value::<Manifest>(raw.clone())?, raw)));
    match parsed {
        Ok(pair) => Some(pair),
        Err(error) => {
            tracing::warn!(%error, path = %file.display(), "读取Fabric启动清单失败");
            None
        }
    }
}

/// The library file and the artifact it must match; `None` when the library has no main artifact.
fn library_target(library: &Library, libraries: &Path) -> Option<(PathBuf, Artifact)> {
    let artifact = library.main_artifact()?;
    let path = artifact.path.clone().filter(|p| !p.trim().is_empty()).or_else(|| descriptor_path(&library.name))?;
    Some((libraries.join(path), artifact))
}

/// `isVerifiedFabricArtifact`.
fn verified(file: &Path, artifact: &Artifact) -> bool {
    let Ok(meta) = std::fs::metadata(file) else { return false };
    if !meta.is_file() || meta.len() == 0 || artifact.sha1.trim().is_empty() {
        return false;
    }
    if artifact.size > 0 && meta.len() != artifact.size {
        return false;
    }
    match sha1_file(file) {
        Ok(actual) => actual.eq_ignore_ascii_case(artifact.sha1.trim()),
        Err(error) => {
            tracing::warn!(%error, path = %file.display(), "计算Fabric运行库校验值失败");
            false
        }
    }
}

/// `fabricLibrariesReady`.
pub fn libraries_ready(manifest: &Manifest, libraries: &Path, host: &crate::mc_launch::manifest::Host) -> bool {
    manifest
        .libraries
        .iter()
        .filter(|l| l.allowed(host))
        .all(|l| library_target(l, libraries).is_some_and(|(file, artifact)| verified(&file, &artifact)))
}

fn installed(deps: &Deps) -> Option<(Manifest, Value)> {
    let (manifest, raw) = read_profile(&deps.versions())?;
    match validate(&manifest, true) {
        Ok(()) => Some((manifest, raw)),
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "已有Fabric启动清单校验失败，将重新获取官方清单");
            None
        }
    }
}

static INSTALLS: LazyLock<Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>> = LazyLock::new(Default::default);

/// `ensureFabricLaunchLoader`: returns the validated profile with every library verified.
pub async fn ensure(deps: &Deps, cancel: &Cancellation, progress: Progress<'_>) -> Result<Manifest> {
    ensure_not_cancelled(cancel)?;
    let check = |deps: Deps| async move {
        tokio::task::spawn_blocking(move || {
            let (manifest, raw) = installed(&deps)?;
            Some((libraries_ready(&manifest, &deps.libraries(), &deps.host), manifest, raw))
        })
        .await
    };
    let reusable = check(deps.clone()).await?;
    if let Some((true, manifest, _)) = reusable {
        return Ok(manifest);
    }
    let key = format!("{MC_VERSION}|Fabric|{PROFILE_ID}|{LOADER_VERSION}");
    let lock = INSTALLS.lock().unwrap().entry(key).or_default().clone();
    let _guard = match lock.try_lock() {
        Ok(guard) => guard,
        Err(_) => {
            progress("等待相同Fabric客户端安装完成...".into());
            crate::download::cancellable(cancel, async { Ok(lock.lock().await) }).await?
        }
    };
    ensure_not_cancelled(cancel)?;
    // Another caller may have published a complete installation meanwhile.
    let latest = check(deps.clone()).await?;
    if let Some((true, manifest, _)) = latest {
        return Ok(manifest);
    }
    progress(format!("准备Fabric{LOADER_VERSION}客户端..."));
    let reuse = latest.or(reusable).map(|(_, _, raw)| raw);
    install(deps, reuse, cancel, progress).await?;
    ensure_not_cancelled(cancel)?;
    let (manifest, _) = read_profile(&deps.versions()).context("Fabric安装后缺少启动清单")?;
    validate(&manifest, true)?;
    let (m, libraries, host) = (manifest.clone(), deps.libraries(), deps.host.clone());
    ensure!(tokio::task::spawn_blocking(move || libraries_ready(&m, &libraries, &host)).await?, "Fabric安装后运行库校验失败");
    Ok(manifest)
}

/// `environment.fabricMetadataFetcher`: a plain GET that must answer 2xx.
async fn fetch_text(deps: &Deps, url: &str, cancel: &Cancellation) -> Result<String> {
    let http = deps.downloader.http.clone();
    let url = url.to_owned();
    crate::download::cancellable(cancel, async move {
        let response = http.get(&url).send().await.with_context(|| format!("Fabric metadata request failed: {url}"))?;
        let status = response.status();
        ensure!(status.is_success(), "Fabric metadata request failed with HTTP {}: {url}", status.as_u16());
        Ok(response.text().await?)
    })
    .await
}

/// `resolveFabricChecksums`: libraries without a SHA-1 get it from the `.sha1` file beside them,
/// written into `downloads.artifact` as Kotlin does.
async fn fill_checksums(deps: &Deps, raw: &mut Value, cancel: &Cancellation) -> Result<()> {
    let libraries = raw.get_mut("libraries").and_then(Value::as_array_mut).context("Fabric profile缺少libraries")?;
    for entry in libraries {
        ensure_not_cancelled(cancel)?;
        let library: Library = serde_json::from_value(entry.clone())?;
        let mut artifact = launch_artifact(&library).with_context(|| format!("Fabric运行库缺少artifact: {}", library.name))?;
        if artifact.sha1.trim().is_empty() {
            let sidecar = fetch_text(deps, &format!("{}.sha1", artifact.url), cancel).await?;
            let sidecar = sidecar.trim();
            ensure!(is_sha1(sidecar), "Fabric运行库SHA1 sidecar无效: {}", library.name);
            artifact.sha1 = sidecar.to_owned();
        }
        let object = entry.as_object_mut().context("Fabric运行库格式无效")?;
        let downloads = object.entry("downloads").or_insert_with(|| Value::Object(Default::default()));
        let downloads = downloads.as_object_mut().context("Fabric运行库格式无效")?;
        downloads.insert("artifact".into(), serde_json::to_value(&artifact)?);
    }
    Ok(())
}

/// `installFabricProfile`: fetch (or reuse), fill checksums, download and verify each library,
/// then publish the profile.
async fn install(deps: &Deps, reusable: Option<Value>, cancel: &Cancellation, progress: Progress<'_>) -> Result<()> {
    let raw = match reusable {
        Some(raw) => raw,
        None => {
            ensure_not_cancelled(cancel)?;
            let text = fetch_text(deps, PROFILE_URL, cancel).await?;
            ensure_not_cancelled(cancel)?;
            let mut raw: Value = serde_json::from_str(&text).context("Fabric官方清单格式无效")?;
            validate(&serde_json::from_value(raw.clone())?, false)?;
            fill_checksums(deps, &mut raw, cancel).await?;
            raw
        }
    };
    let profile: Manifest = serde_json::from_value(raw.clone())?;
    validate(&profile, true)?;
    let libraries: Vec<&Library> = profile.libraries.iter().filter(|l| l.allowed(&deps.host)).collect();
    let total = libraries.len();
    for (index, library) in libraries.into_iter().enumerate() {
        ensure_not_cancelled(cancel)?;
        let (target, artifact) = library_target(library, &deps.libraries()).with_context(|| format!("Fabric运行库缺少artifact: {}", library.name))?;
        let (t, a) = (target.clone(), artifact.clone());
        if tokio::task::spawn_blocking(move || verified(&t, &a)).await? {
            continue;
        }
        progress(format!("下载Fabric运行库{}/{total}: {}", index + 1, library.name));
        let plan = source_plan(deps, &artifact.url, None, &artifact.sha1);
        let label = format!("下载{}", library.name);
        let report = |p: crate::download::Progress| {
            let total = p.total.filter(|t| *t > 0).map(super::human_file_size).unwrap_or_else(|| "未知".into());
            progress(format!("{label} {}/{total}", super::human_file_size(p.bytes)));
        };
        super::download_verified(deps, &plan, &target, &artifact.sha1, artifact.size, cancel, &report).await?;
        ensure_not_cancelled(cancel)?;
        let (t, a) = (target.clone(), artifact.clone());
        ensure!(tokio::task::spawn_blocking(move || verified(&t, &a)).await?, "Fabric运行库下载后校验失败: {}", library.name);
    }
    ensure_not_cancelled(cancel)?;
    let (p, libraries, host) = (profile.clone(), deps.libraries(), deps.host.clone());
    ensure!(tokio::task::spawn_blocking(move || libraries_ready(&p, &libraries, &host)).await?, "Fabric运行库未全部就绪");
    ensure_not_cancelled(cancel)?;
    publish(&deps.versions(), &raw)
}

/// `publishFabricProfile`: a temporary file renamed into place.
fn publish(versions: &Path, raw: &Value) -> Result<()> {
    let file = profile_file(versions);
    let parent = file.parent().context("Fabric启动清单路径无效")?;
    std::fs::create_dir_all(parent).with_context(|| format!("创建目录失败: {}", parent.display()))?;
    let temp = parent.join(format!("{PROFILE_ID}.json.{}.tmp", uuid::Uuid::now_v7()));
    let result = std::fs::write(&temp, serde_json::to_vec(raw)?)
        .and_then(|()| std::fs::rename(&temp, &file))
        .with_context(|| format!("写入Fabric启动清单失败: {}", file.display()));
    if result.is_err() {
        let _ = std::fs::remove_file(&temp);
    }
    result
}

#[cfg(test)]
pub(crate) fn validate_raw(raw: &Value, require_checksums: bool) -> Result<()> {
    validate(&serde_json::from_value(raw.clone())?, require_checksums)
}
