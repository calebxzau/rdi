//! J5c: GTNH (1.7.10) on Java 25, ported from `client/mclaunch/.../GtnhLaunchSupport.kt`.
//! A GTNH pack carries Prism patches in `<versionDir>/gtnh/patches`; they replace the Forge loader
//! manifest, add LWJGL3 and lwjgl3ify, and start through RetroFuturaBootstrap.
use super::manifest::{Artifact, Host, Library, LibraryDownloads, Manifest, Rule};
use anyhow::{Context, Result, bail};
use serde::Deserialize;
use std::path::{Path, PathBuf};

const RFB_MAIN_CLASS: &str = "com.gtnewhorizons.retrofuturabootstrap.Main";
const FORGE_PATCH: &str = "patches/net.minecraftforge.json";
const LWJGL3_PATCH: &str = "patches/org.lwjgl3.json";
const FORGE_PATCHES_PATCH: &str = "patches/me.eigenraven.lwjgl3ify.forgepatches.json";
const LAUNCH_ARGS_PATCH: &str = "patches/me.eigenraven.lwjgl3ify.launchargs.json";
/// `knownBases` in `mavenRelativePath`.
const KNOWN_BASES: [&str; 3] =
    ["https://libraries.minecraft.net/", "https://maven.minecraftforge.net/", "https://files.prismlauncher.org/maven/"];

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct Patch {
    #[serde(rename = "mainClass")]
    main_class: Option<String>,
    libraries: Vec<PatchLibrary>,
    #[serde(rename = "+tweakers")]
    add_tweakers: Vec<String>,
    #[serde(rename = "+jvmArgs")]
    add_jvm_args: Vec<String>,
}

/// `MavenLibraryRef`: Prism's `url` field is not read, as in Kotlin.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
struct PatchLibrary {
    name: String,
    downloads: LibraryDownloads,
    rules: Vec<Rule>,
    #[serde(rename = "MMC-hint")]
    mmc_hint: Option<String>,
}

impl PatchLibrary {
    fn to_library(&self) -> Library {
        Library {
            name: self.name.clone(),
            downloads: self.downloads.clone(),
            rules: Some(self.rules.clone()).filter(|r| !r.is_empty()),
            ..Default::default()
        }
    }

    /// `allowsCurrentOs`: the last matching rule decides; Prism also names `windows-arm64`,
    /// `osx-arm64` and `linux-arm64`, which match only that platform.
    fn allowed(&self, host: &Host) -> bool {
        if self.rules.is_empty() {
            return true;
        }
        let arm64 = is_arm64(host);
        let mut allowed = false;
        for rule in &self.rules {
            let name = rule.os.as_ref().and_then(|os| os.name.as_deref());
            let matches = match name {
                None => true,
                Some(n) if n.eq_ignore_ascii_case(&host.os_name) => true,
                Some(n) if n.eq_ignore_ascii_case("windows-arm64") => arm64 && host.os_name == "windows",
                Some(n) if n.eq_ignore_ascii_case("osx-arm64") => arm64 && host.os_name == "osx",
                Some(n) if n.eq_ignore_ascii_case("linux-arm64") => arm64 && host.os_name == "linux",
                Some(_) => false,
            };
            if matches {
                allowed = rule.action == "allow";
            }
        }
        allowed
    }

    fn is_lwjgl3ify_forge_patches(&self) -> bool {
        let parts: Vec<&str> = self.name.split(':').collect();
        parts.len() >= 4
            && parts[0] == "com.github.GTNewHorizons"
            && parts[1] == "lwjgl3ify"
            && !parts[2].trim().is_empty()
            && parts[3] == "forgePatches"
    }
}

/// `LibraryOsArch.detectHostOs` arm64 test on Java's `os.arch`.
fn is_arm64(host: &Host) -> bool {
    let arch = host.os_arch.to_lowercase();
    arch.contains("aarch64") || (arch.contains("arm") && arch.contains("64"))
}

fn read_patch(file: &Path) -> Result<Patch> {
    let text = std::fs::read_to_string(file).with_context(|| format!("GTNH Prism patch解析失败: {}", file.display()))?;
    serde_json::from_str(&text).map_err(|error| anyhow::anyhow!("GTNH Prism patch解析失败: {}: {error}", file.display()))
}

fn maven_relative_path(url: &str) -> Option<String> {
    let url = url.trim();
    KNOWN_BASES.iter().find_map(|base| url.strip_prefix(base).filter(|rest| !rest.trim().is_empty()).map(str::to_owned))
}

/// `gtnhLibraryPath`.
fn library_path(library: &PatchLibrary, host: &Host) -> Option<String> {
    if !library.allowed(host) {
        return None;
    }
    let artifact = library.downloads.artifact.as_ref();
    if let Some(path) = artifact.and_then(|a| a.path.clone()).filter(|p| !p.trim().is_empty()) {
        return Some(path);
    }
    if let Some(path) = artifact.and_then(|a| maven_relative_path(&a.url)) {
        return Some(path);
    }
    let parts: Vec<&str> = library.name.split(':').collect();
    if parts.len() < 3 {
        return None;
    }
    let mut path = format!("{}/{}/{}/{}-{}", parts[0].replace('.', "/"), parts[1], parts[2], parts[1], parts[2]);
    if let Some(classifier) = parts.get(3).filter(|c| !c.trim().is_empty()) {
        path.push('-');
        path.push_str(classifier);
    }
    path.push_str(".jar");
    Some(path)
}

fn file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

/// `gtnhLibraryFile`: the pack's own copy wins over the shared libraries folder.
fn library_file(root: &Path, libraries_dir: &Path, library: &PatchLibrary, host: &Host) -> Option<PathBuf> {
    let path = library_path(library, host)?;
    let local = root.join("libraries").join(file_name(&path));
    Some(if local.is_file() { local } else { libraries_dir.join(path) })
}

/// `gtnhLocalLibraryFile`: only libraries Prism marks as shipped with the pack.
fn local_library_file(root: &Path, library: &PatchLibrary, host: &Host) -> Option<PathBuf> {
    if library.mmc_hint.as_deref() != Some("local") || !library.allowed(host) {
        return None;
    }
    let path = library_path(library, host)?;
    Some(root.join("libraries").join(file_name(&path)))
}

struct Lwjgl3ify {
    version: String,
    runtime_mod: Option<PathBuf>,
    forge_patches: PathBuf,
}

fn lwjgl3ify(root: &Path, host: &Host) -> Result<Lwjgl3ify> {
    let patch_file = root.join(FORGE_PATCHES_PATCH);
    let patch = read_patch(&patch_file)?;
    let Some(library) = patch.libraries.iter().find(|l| l.is_lwjgl3ify_forge_patches()) else {
        bail!("GTNH Prism patch缺少lwjgl3ify forgePatches库: {}", patch_file.display());
    };
    let version = library.name.split(':').nth(2).unwrap_or_default().to_owned();
    let forge_patches = local_library_file(root, library, host)
        .unwrap_or_else(|| root.join("libraries").join(format!("lwjgl3ify-{version}-forgePatches.jar")));
    let runtime_mod = Some(root.join("libraries").join(format!("lwjgl3ify-{version}.jar"))).filter(|f| f.is_file());
    Ok(Lwjgl3ify { version, runtime_mod, forge_patches })
}

/// `requireExtensionRoot`: the pack's `gtnh` folder with every patch the launch reads.
pub fn require_root(version_dir: &Path, host: &Host) -> Result<PathBuf> {
    let root = version_dir.join("gtnh");
    let missing: Vec<String> = [FORGE_PATCH, LWJGL3_PATCH, FORGE_PATCHES_PATCH, LAUNCH_ARGS_PATCH]
        .iter()
        .map(|p| root.join(p))
        .filter(|f| !f.is_file())
        .map(|f| f.display().to_string())
        .collect();
    if !missing.is_empty() {
        bail!("整合包缺少GTNH扩展包，请确认{}存在且完整，缺少: {}", root.display(), missing.join(", "));
    }
    let files = lwjgl3ify(&root, host)?;
    if !files.forge_patches.is_file() {
        bail!("整合包缺少GTNH lwjgl3ify {}运行库: {}", files.version, files.forge_patches.display());
    }
    Ok(root)
}

/// `ensureRuntimeMods`: hard-links the lwjgl3ify mod into `mods` once.
pub fn ensure_runtime_mods(version_dir: &Path, root: &Path, host: &Host) -> Result<()> {
    let Some(source) = lwjgl3ify(root, host)?.runtime_mod else {
        return Ok(());
    };
    let target = version_dir.join("mods").join(source.file_name().context("lwjgl3ify文件名无效")?);
    if target.is_file() {
        return Ok(());
    }
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent).with_context(|| format!("创建目录失败: {}", parent.display()))?;
    }
    if std::fs::symlink_metadata(&target).is_ok() {
        std::fs::remove_file(&target).with_context(|| format!("删除旧文件失败: {}", target.display()))?;
    }
    std::fs::hard_link(&source, &target)
        .with_context(|| format!("链接lwjgl3ify运行Mod失败: {} -> {}", source.display(), target.display()))
}

/// `buildLoaderManifest`: legacy arguments plus the Forge tweakers, RFB as the main class.
pub fn loader_manifest(root: &Path, version_id: &str) -> Result<Manifest> {
    let forge = read_patch(&root.join(FORGE_PATCH))?;
    let launch = read_patch(&root.join(LAUNCH_ARGS_PATCH))?;
    let mut arguments: Vec<String> = [
        "--username", "${auth_player_name}",
        "--version", "${version_name}",
        "--gameDir", "${game_directory}",
        "--assetsDir", "${assets_root}",
        "--assetIndex", "${assets_index_name}",
        "--uuid", "${auth_uuid}",
        "--accessToken", "${auth_access_token}",
        "--userProperties", "${user_properties}",
        "--userType", "${user_type}",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    for tweaker in &forge.add_tweakers {
        arguments.push("--tweakClass".into());
        arguments.push(tweaker.clone());
    }
    Ok(Manifest {
        id: version_id.into(),
        main_class: Some(launch.main_class.unwrap_or_else(|| RFB_MAIN_CLASS.into())),
        libraries: forge.libraries.iter().map(PatchLibrary::to_library).collect(),
        minecraft_arguments: Some(arguments.join(" ")),
        ..Default::default()
    })
}

/// `isLegacyLwjgl2Library`: GTNH runs on LWJGL3, so these leave the vanilla list.
pub fn is_legacy_lwjgl2(library: &Library) -> bool {
    let parts: Vec<&str> = library.name.split(':').collect();
    parts.len() >= 2 && parts[0] == "org.lwjgl.lwjgl" && ["lwjgl", "lwjgl_util", "lwjgl-platform"].contains(&parts[1])
}

/// `buildJava25Classpath`: lwjgl3ify first, then the pack's local libraries, LWJGL3 and Forge,
/// then the 1.7.10 client jar.
pub fn classpath(root: &Path, libraries_dir: &Path, versions_dir: &Path, host: &Host) -> Result<Vec<String>> {
    let files = lwjgl3ify(root, host)?;
    let local: Vec<PathBuf> = std::iter::once(files.forge_patches)
        .chain(read_patch(&root.join(FORGE_PATCHES_PATCH))?.libraries.iter().filter_map(|l| local_library_file(root, l, host)))
        .collect();
    let mut global: Vec<PathBuf> = vec![];
    for patch in [LWJGL3_PATCH, FORGE_PATCH] {
        global.extend(read_patch(&root.join(patch))?.libraries.iter().filter_map(|l| library_file(root, libraries_dir, l, host)));
    }
    global.push(versions_dir.join("1.7.10").join("1.7.10.jar"));
    let missing_local: Vec<&PathBuf> = local.iter().filter(|f| !f.is_file()).collect();
    let missing_global: Vec<&PathBuf> = global.iter().filter(|f| !f.is_file()).collect();
    if !missing_local.is_empty() || !missing_global.is_empty() {
        let all: Vec<String> = missing_local.iter().chain(&missing_global).map(|f| f.display().to_string()).collect();
        let mut message = format!("GTNH Java25启动缺少运行库: {}", all.join(", "));
        if !missing_global.is_empty() {
            message.push_str("。请先在MC资源页安装1.7.10并下载GTNH扩展包需要的全局运行库");
        }
        if !missing_local.is_empty() {
            message.push_str(&format!("。请确认GTNH扩展包已完整安装到{}", root.display()));
        }
        bail!("{message}");
    }
    let mut out: Vec<String> = vec![];
    for file in local.into_iter().chain(global) {
        let text = file.to_string_lossy().into_owned();
        if !out.contains(&text) {
            out.push(text);
        }
    }
    Ok(out)
}

/// `+jvmArgs` of the forgePatches patch; launch tokens are replaced by the caller.
pub fn jvm_args(root: &Path) -> Result<Vec<String>> {
    let file = root.join(FORGE_PATCHES_PATCH);
    if !file.is_file() {
        return Ok(vec![]);
    }
    Ok(read_patch(&file)?.add_jvm_args)
}

/// One library or the client jar that the GTNH launch still needs.
#[derive(Clone, Debug, PartialEq)]
pub struct Download {
    pub label: String,
    pub target: PathBuf,
    pub artifact: Artifact,
}

/// `collectGlobalRuntimeDownloadPlans`: shared LWJGL3 and Forge libraries and the 1.7.10 client jar
/// that are missing, without duplicates (paths compared case-insensitively, as Kotlin does).
pub fn downloads(root: &Path, vanilla: &Manifest, libraries_dir: &Path, versions_dir: &Path, host: &Host) -> Result<Vec<Download>> {
    let mut out: Vec<Download> = vec![];
    for patch in [LWJGL3_PATCH, FORGE_PATCH] {
        for library in read_patch(&root.join(patch))?.libraries {
            let Some(resolved) = library_file(root, libraries_dir, &library, host) else { continue };
            if resolved.is_file() {
                continue;
            }
            let Some(mut artifact) = library.to_library().main_artifact() else { continue };
            let Some(path) = library_path(&library, host).or_else(|| artifact.path.clone()) else { continue };
            artifact.path = Some(path.clone());
            out.push(Download { label: library.name.clone(), target: libraries_dir.join(path), artifact });
        }
    }
    let client = versions_dir.join("1.7.10").join("1.7.10.jar");
    if !client.is_file() {
        let artifact = vanilla.downloads.as_ref().and_then(|d| d.client.clone()).context("1.7.10缺少客户端下载信息")?;
        out.push(Download { label: "Minecraft客户端1.7.10".into(), target: client, artifact });
    }
    let mut seen = std::collections::HashSet::new();
    out.retain(|d| seen.insert(d.target.to_string_lossy().to_lowercase()));
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn host(arch: &str) -> Host {
        Host { os_name: "windows".into(), os_arch: arch.into(), os_version: "10.0".into(), path_separator: ";".into() }
    }

    fn library(rules: &str) -> PatchLibrary {
        serde_json::from_str(&format!(r#"{{"name":"a:b:1","rules":{rules}}}"#)).unwrap()
    }

    #[test]
    fn prism_platform_names_match_only_their_architecture() {
        let both = library(r#"[{"action":"allow","os":{"name":"windows"}},{"action":"allow","os":{"name":"windows-arm64"}}]"#);
        let arm_only = library(r#"[{"action":"allow","os":{"name":"windows-arm64"}}]"#);
        let linux = library(r#"[{"action":"allow","os":{"name":"linux"}}]"#);
        assert!(both.allowed(&host("amd64")));
        assert!(!arm_only.allowed(&host("amd64")));
        assert!(arm_only.allowed(&host("aarch64")));
        assert!(!linux.allowed(&host("amd64")));
        assert!(library("[]").allowed(&host("amd64")));
    }

    #[test]
    fn library_paths_follow_kotlin() {
        let h = host("amd64");
        let from_url: PatchLibrary = serde_json::from_str(
            r#"{"name":"org.lwjgl:lwjgl-natives-windows:3.3.3","downloads":{"artifact":{"url":"https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar"}}}"#,
        )
        .unwrap();
        assert_eq!(library_path(&from_url, &h).unwrap(), "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar");
        let from_name: PatchLibrary =
            serde_json::from_str(r#"{"name":"net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10:universal"}"#).unwrap();
        assert_eq!(
            library_path(&from_name, &h).unwrap(),
            "net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"
        );
        let unknown_host: PatchLibrary =
            serde_json::from_str(r#"{"name":"a.b:c:1","downloads":{"artifact":{"url":"https://other.example/a/b/c-1.jar"}}}"#).unwrap();
        assert_eq!(library_path(&unknown_host, &h).unwrap(), "a/b/c/1/c-1.jar");
    }

    #[test]
    fn missing_extension_names_every_missing_patch() {
        let dir = std::env::temp_dir().join(format!("rdi-gtnh-{}", uuid::Uuid::now_v7()));
        std::fs::create_dir_all(dir.join("gtnh/patches")).unwrap();
        std::fs::write(dir.join("gtnh").join(FORGE_PATCH), "{}").unwrap();
        let error = format!("{:#}", require_root(&dir, &host("amd64")).unwrap_err());
        assert!(error.starts_with("整合包缺少GTNH扩展包"), "{error}");
        assert!(error.contains("org.lwjgl3.json") && !error.contains("net.minecraftforge.json"), "{error}");
        std::fs::remove_dir_all(dir).unwrap();
    }
}
