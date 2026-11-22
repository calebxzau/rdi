//! Mojang/loader version manifests and the rule evaluation of `client/mclaunch`
//! (`MojangApi.kt`, `MinecraftManifestArguments.kt`, `MinecraftLibraries.kt`).
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Manifest {
    pub id: String,
    pub main_class: Option<String>,
    pub downloads: Option<VersionDownloads>,
    pub asset_index: Option<AssetIndex>,
    pub assets: Option<String>,
    pub libraries: Vec<Library>,
    pub arguments: Arguments,
    pub minecraft_arguments: Option<String>,
    pub inherits_from: Option<String>,
    pub jar: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(default)]
pub struct Arguments {
    pub game: Vec<Value>,
    pub jvm: Vec<Value>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(default)]
pub struct VersionDownloads {
    pub client: Option<Artifact>,
    pub client_mappings: Option<Artifact>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
#[serde(default)]
pub struct Artifact {
    pub sha1: String,
    pub size: u64,
    pub url: String,
    pub path: Option<String>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AssetIndex {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(default)]
pub struct Library {
    pub name: String,
    pub downloads: LibraryDownloads,
    pub rules: Option<Vec<Rule>>,
    pub natives: Option<BTreeMap<String, String>>,
    pub url: Option<String>,
    pub checksums: Vec<String>,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(default)]
pub struct LibraryDownloads {
    pub artifact: Option<Artifact>,
    pub classifiers: Option<BTreeMap<String, Artifact>>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Rule {
    pub action: String,
    #[serde(default)]
    pub features: Option<BTreeMap<String, bool>>,
    #[serde(default)]
    pub os: Option<RuleOs>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(default)]
pub struct RuleOs {
    pub name: Option<String>,
    pub arch: Option<String>,
    pub version: Option<String>,
}

/// What the rules are matched against: Java's `os.name` family, `os.arch` and `os.version`.
#[derive(Clone, Debug, PartialEq)]
pub struct Host {
    /// `LibraryOsArch.ruleOsName`: `windows`, `osx` or `linux`.
    pub os_name: String,
    /// Java `os.arch`, lower case (`amd64`, `aarch64`).
    pub os_arch: String,
    pub os_version: String,
    pub path_separator: String,
}

impl Host {
    pub fn current() -> Self {
        let os_name = if cfg!(windows) {
            "windows"
        } else if cfg!(target_os = "macos") {
            "osx"
        } else {
            "linux"
        };
        // Java reports amd64 on Windows and Linux; macOS Intel JDKs report x86_64.
        let os_arch = match std::env::consts::ARCH {
            "x86_64" if cfg!(target_os = "macos") => "x86_64",
            "x86_64" => "amd64",
            "x86" => "x86",
            other => other,
        };
        // JDK 25 runs on Windows 10 and later, which Java reports as 10.0.
        let os_version = if cfg!(windows) {
            "10.0".to_owned()
        } else if cfg!(target_os = "macos") {
            sysinfo::System::os_version().unwrap_or_default()
        } else {
            sysinfo::System::kernel_version().unwrap_or_default()
        };
        Self {
            os_name: os_name.into(),
            os_arch: os_arch.into(),
            os_version,
            path_separator: if cfg!(windows) { ";" } else { ":" }.into(),
        }
    }

    /// `hostNativeArch` in Kotlin: `64` for 64-bit `os.arch` values.
    pub fn native_arch(&self) -> &'static str {
        let raw = &self.os_arch;
        if raw.contains("64") || raw.contains("amd64") || raw.contains("x86_64") || raw.contains("aarch64") {
            "64"
        } else {
            "32"
        }
    }
}

/// Kotlin `rulesAllow`: the last matching rule decides; no rule list allows. The launcher has no
/// features, so a rule that needs one never matches.
pub fn rules_allow(rules: Option<&[Rule]>, host: &Host) -> bool {
    let Some(rules) = rules.filter(|r| !r.is_empty()) else {
        return true;
    };
    let mut allowed = false;
    for rule in rules {
        if rule_matches(rule, host) {
            allowed = rule.action == "allow";
        }
    }
    allowed
}

fn rule_matches(rule: &Rule, host: &Host) -> bool {
    if let Some(os) = &rule.os {
        if let Some(name) = &os.name
            && !host.os_name.eq_ignore_ascii_case(name)
        {
            return false;
        }
        if let Some(arch) = &os.arch
            && !host.os_arch.contains(&arch.to_lowercase())
        {
            return false;
        }
        if let Some(spec) = &os.version {
            let matches = match regex::Regex::new(spec) {
                Ok(re) => re.is_match(&host.os_version),
                Err(_) => host.os_version.to_lowercase().contains(&spec.to_lowercase()),
            };
            if !matches {
                return false;
            }
        }
    }
    rule.features.as_ref().is_none_or(|f| f.is_empty())
}

/// Kotlin `resolveArgumentList`.
pub fn resolve_arguments(source: &[Value], host: &Host) -> Vec<String> {
    let mut args = vec![];
    for element in source {
        match element {
            Value::String(s) => args.push(s.clone()),
            Value::Object(o) => {
                let rules: Option<Vec<Rule>> = o.get("rules").and_then(|r| serde_json::from_value(r.clone()).ok());
                if !rules_allow(rules.as_deref(), host) {
                    continue;
                }
                match o.get("value") {
                    Some(Value::String(s)) => args.push(s.clone()),
                    Some(Value::Array(a)) => args.extend(a.iter().filter_map(|v| v.as_str().map(str::to_owned))),
                    _ => {}
                }
            }
            _ => {}
        }
    }
    args
}

impl Manifest {
    pub fn game_arguments(&self, host: &Host) -> Vec<String> {
        let modern = resolve_arguments(&self.arguments.game, host);
        if !modern.is_empty() {
            return modern;
        }
        self.minecraft_arguments
            .as_deref()
            .map(|a| a.split_whitespace().map(str::to_owned).collect())
            .unwrap_or_default()
    }

    pub fn jvm_arguments(&self, host: &Host) -> Vec<String> {
        resolve_arguments(&self.arguments.jvm, host)
    }

    /// `resolveLaunchGameArguments`.
    pub fn launch_game_arguments(&self, loader: &Manifest, host: &Host) -> Vec<String> {
        if loader.minecraft_arguments.as_deref().is_some_and(|a| !a.trim().is_empty()) {
            loader.game_arguments(host)
        } else {
            let mut args = self.game_arguments(host);
            args.extend(loader.game_arguments(host));
            args
        }
    }

    /// `isBootstrapModuleLaunch`.
    pub fn is_bootstrap_module_launch(&self, host: &Host) -> bool {
        if self.main_class.as_deref() != Some("cpw.mods.bootstraplauncher.BootstrapLauncher") {
            return false;
        }
        let jvm = self.jvm_arguments(host);
        jvm.iter().any(|a| a == "-p") && jvm.iter().any(|a| a == "ALL-MODULE-PATH")
    }
}

fn not_blank(value: &Option<String>) -> Option<&str> {
    value.as_deref().filter(|v| !v.trim().is_empty())
}

/// `descriptorToLibraryPath`.
pub fn descriptor_path(descriptor: &str) -> Option<String> {
    let (coordinates, extension) = match descriptor.split_once('@') {
        Some((c, e)) if !e.trim().is_empty() => (c, e),
        Some((c, _)) => (c, "jar"),
        None => (descriptor, "jar"),
    };
    let parts: Vec<&str> = coordinates.split(':').collect();
    if parts.len() < 3 {
        return None;
    }
    let classifier = parts.get(3).filter(|c| !c.trim().is_empty());
    let mut file = format!("{}-{}", parts[1], parts[2]);
    if let Some(c) = classifier {
        file.push('-');
        file.push_str(c);
    }
    Some(format!("{}/{}/{}/{file}.{extension}", parts[0].replace('.', "/"), parts[1], parts[2]))
}

/// Default Maven repository for a library path without a URL.
pub fn default_library_base(path: &str) -> &'static str {
    if path.starts_with("net/neoforged/") {
        "https://maven.neoforged.net/releases"
    } else if path.starts_with("net/minecraftforge/") || path.starts_with("cpw/mods/") {
        "https://maven.minecraftforge.net"
    } else {
        "https://libraries.minecraft.net"
    }
}

impl Library {
    pub fn allowed(&self, host: &Host) -> bool {
        rules_allow(self.rules.as_deref(), host)
    }

    /// McInstall's `mainArtifact`: checksum and size fall back to the library's own fields.
    pub fn main_artifact(&self) -> Option<Artifact> {
        if let Some(a) = &self.downloads.artifact {
            let mut a = a.clone();
            if a.sha1.trim().is_empty() {
                a.sha1 = self.sha1.clone().or_else(|| self.checksums.first().cloned()).unwrap_or_default();
            }
            if a.size == 0 {
                a.size = self.size.unwrap_or(0);
            }
            return Some(a);
        }
        if self.downloads.classifiers.as_ref().is_some_and(|c| !c.is_empty())
            || self.natives.as_ref().is_some_and(|n| !n.is_empty())
            || self.name.trim().is_empty()
        {
            return None;
        }
        let path = descriptor_path(&self.name)?;
        let base = not_blank(&self.url).map(str::to_owned).unwrap_or_else(|| default_library_base(&path).into());
        Some(Artifact {
            sha1: self.sha1.clone().or_else(|| self.checksums.first().cloned()).unwrap_or_default(),
            size: self.size.unwrap_or(0),
            url: format!("{}/{}", base.trim_end_matches('/'), path.trim_start_matches('/')),
            path: Some(path),
        })
    }

    pub fn native_artifact(&self, host: &Host) -> Option<Artifact> {
        let key = self.natives.as_ref()?.get(&host.os_name)?.replace("${arch}", host.native_arch());
        self.downloads.classifiers.as_ref()?.get(&key).cloned()
    }
}

/// Libraries whose loader copy replaces Mojang's on the class path (`classpathOverrideArtifacts`).
const OVERRIDE_ARTIFACTS: &[&str] = &[
    "com.google.code.gson:gson",
    "com.google.guava:guava",
    "commons-codec:commons-codec",
    "commons-io:commons-io",
    "commons-logging:commons-logging",
    "it.unimi.dsi:fastutil",
    "net.java.dev.jna:jna",
    "net.java.jinput:jinput",
    "net.sf.jopt-simple:jopt-simple",
    "org.apache.commons:commons-compress",
    "org.apache.commons:commons-lang3",
    "org.apache.httpcomponents:httpclient",
    "org.apache.httpcomponents:httpcore",
    "org.apache.logging.log4j:log4j-api",
    "org.apache.logging.log4j:log4j-core",
    "org.apache.logging.log4j:log4j-slf4j18-impl",
    "org.apache.logging.log4j:log4j-slf4j2-impl",
    "org.slf4j:slf4j-api",
];

fn override_key(library: &Library) -> Option<String> {
    let mut parts = library.name.split(':');
    let key = format!("{}:{}", parts.next()?, parts.next()?);
    OVERRIDE_ARTIFACTS.contains(&key.as_str()).then_some(key)
}

/// Kotlin `mergeLibraries`/the merge inside `buildMinecraftClasspath`: base libraries in order,
/// a loader copy replacing an overridable one, then the remaining loader libraries.
pub fn merge_libraries<'a>(base: &'a [Library], overrides: &'a [Library], host: &Host) -> Vec<&'a Library> {
    let overrides: Vec<&Library> = overrides.iter().filter(|l| l.allowed(host)).collect();
    let by_key: BTreeMap<String, &Library> =
        overrides.iter().filter_map(|l| override_key(l).map(|k| (k, *l))).collect();
    let mut used = std::collections::BTreeSet::new();
    let mut merged = vec![];
    for library in base.iter().filter(|l| l.allowed(host)) {
        match override_key(library) {
            Some(key) => match by_key.get(&key) {
                Some(o) => {
                    used.insert(key);
                    merged.push(*o);
                }
                None => merged.push(library),
            },
            None => merged.push(library),
        }
    }
    merged.extend(overrides.into_iter().filter(|l| override_key(l).is_none_or(|k| !used.contains(&k))));
    merged
}

/// `buildMinecraftClasspath`: existing files only, first occurrence wins.
pub fn classpath(base: &[Library], overrides: &[Library], libraries_dir: &Path, host: &Host) -> Vec<PathBuf> {
    let mut seen = std::collections::HashSet::new();
    let mut files: Vec<PathBuf> = merge_libraries(base, overrides, host)
        .into_iter()
        .filter_map(|l| {
            let path = l.main_artifact().and_then(|a| a.path.filter(|p| !p.trim().is_empty())).or_else(|| descriptor_path(&l.name))?;
            Some(libraries_dir.join(path))
        })
        .filter(|f| f.exists())
        .filter(|f| seen.insert(f.clone()))
        .collect();
    let name = |f: &PathBuf, prefix: &str| f.file_name().is_some_and(|n| n.to_string_lossy().starts_with(prefix));
    if files.iter().any(|f| name(f, "log4j-slf4j18-impl-")) && !files.iter().any(|f| name(f, "slf4j-api-")) {
        for v in ["1.8.0-beta4", "2.0.1", "2.0.9"] {
            let f = libraries_dir.join(format!("org/slf4j/slf4j-api/{v}/slf4j-api-{v}.jar"));
            if f.exists() {
                files.push(f);
                break;
            }
        }
    }
    files
}

/// `resolveLaunchVersionJarCandidates`.
pub fn version_jar_candidates(
    manifest: &Manifest,
    loader: &Manifest,
    version_dir: &Path,
    versions_dir: &Path,
    version_id: &str,
) -> Vec<PathBuf> {
    let mut names: Vec<&str> = vec![version_id];
    for n in [
        Some(loader.id.as_str()).filter(|s| !s.trim().is_empty()),
        not_blank(&loader.jar),
        not_blank(&loader.inherits_from),
        not_blank(&manifest.jar),
        Some(manifest.id.as_str()).filter(|s| !s.trim().is_empty()),
    ]
    .into_iter()
    .flatten()
    {
        if !names.contains(&n) {
            names.push(n);
        }
    }
    let mut out: Vec<PathBuf> = vec![];
    for name in names {
        for candidate in [version_dir.join(format!("{name}.jar")), versions_dir.join(name).join(format!("{name}.jar"))] {
            if !out.contains(&candidate) {
                out.push(candidate);
            }
        }
    }
    out
}

/// `normalizeLoaderManifest` with the configured directory name as id.
pub fn normalize_loader(mut manifest: Manifest, dir_name: &str, mc_version: &str) -> Manifest {
    manifest.id = dir_name.into();
    if not_blank(&manifest.inherits_from).is_none() {
        manifest.inherits_from = Some(mc_version.into());
    }
    if not_blank(&manifest.jar).is_none() {
        manifest.jar = Some(mc_version.into());
    }
    manifest
}

pub(crate) fn first_not_blank<'a>(values: &[&'a Option<String>]) -> Option<&'a str> {
    values.iter().find_map(|v| not_blank(v))
}
