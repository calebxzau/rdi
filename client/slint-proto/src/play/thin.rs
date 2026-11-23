//! J5b: Kotlin-mod thinning, ported from `client/mclaunch` `GameKotlinRuntime.kt`. A room game gets
//! Kotlin, coroutines, serialization and zstd-jni on its class path, so mods that bundle them
//! (through jarjar or shaded classes) are rewritten without them. Both launchers share the cache
//! under `<tools>/kotlin-runtime` (schema `v3`) and accept each other's thin jars, because a thin
//! jar is recognised by its content, not by its bytes. `protocol/fixtures/j5b-thin.json` (Kotlin
//! `J5bKotlinThinFixtureTest`) guards the result.
use anyhow::{Context, Result, ensure};
use serde_json::{Map, Value};
use std::collections::{BTreeMap, HashSet};
use std::io::{Cursor, Read, Seek, Write};
use std::path::{Path, PathBuf};

const CACHE_SCHEMA: &str = "v3";
const MAX_NESTED_DEPTH: usize = 4;
const JARJAR_METADATA: &str = "META-INF/jarjar/metadata.json";
const KFF_GROUP: &str = "thedarkcolour";
const KFF_ARTIFACTS: [&str; 3] = ["kfflang", "kfflib", "kffmod"];
/// `providedRuntimeArtifacts`, as `group:artifact`.
const PROVIDED: [&str; 11] = [
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains.kotlin:kotlin-reflect",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-coroutines-jdk8",
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
    ":kotlinx.serialization.core",
    ":kotlinx.serialization.json",
    "com.github.luben:zstd-jni",
];

/// One jar's entries in archive order, without directories; the first of duplicate names wins.
struct Jar {
    names: Vec<String>,
    entries: BTreeMap<String, Vec<u8>>,
}

impl Jar {
    fn read<R: Read + Seek>(reader: R) -> Result<Self> {
        let mut zip = zip::ZipArchive::new(reader)?;
        let mut names = vec![];
        let mut entries = BTreeMap::new();
        for index in 0..zip.len() {
            let mut entry = zip.by_index(index)?;
            if entry.is_dir() {
                continue;
            }
            let name = entry.name().to_owned();
            if entries.contains_key(&name) {
                continue;
            }
            let mut bytes = Vec::with_capacity(entry.size() as usize);
            entry.read_to_end(&mut bytes)?;
            names.push(name.clone());
            entries.insert(name, bytes);
        }
        Ok(Self { names, entries })
    }
}

#[derive(Clone, Debug)]
struct Dependency {
    path: String,
    group: String,
    artifact: String,
}

impl Dependency {
    fn provided(&self) -> bool {
        PROVIDED.contains(&format!("{}:{}", self.group, self.artifact).as_str())
    }
}

fn dependencies(metadata: &Map<String, Value>) -> Vec<Dependency> {
    let Some(Value::Array(jars)) = metadata.get("jars") else {
        return vec![];
    };
    jars.iter()
        .filter_map(|jar| {
            let jar = jar.as_object()?;
            let identifier = jar.get("identifier")?.as_object()?;
            let path = jar.get("path")?.as_str()?.to_owned();
            let text = |key: &str| identifier.get(key).and_then(Value::as_str).unwrap_or_default().to_owned();
            Some(Dependency { path, group: text("group"), artifact: text("artifact") })
        })
        .collect()
}

/// What one jar level needs (`JarPlan`).
struct Plan {
    metadata: Option<Map<String, Value>>,
    removed: HashSet<String>,
    strip_shaded: bool,
    nested: BTreeMap<String, (Jar, Plan)>,
}

impl Plan {
    fn needs_normalization(&self) -> bool {
        !self.removed.is_empty() || self.strip_shaded || !self.nested.is_empty()
    }
}

fn shaded(name: &str) -> bool {
    let n = name.replace('\\', "/");
    n.starts_with("kotlin/") || n.starts_with("kotlinx/") || n.starts_with("_COROUTINE/")
}

fn signature(name: &str) -> bool {
    let n = name.replace('\\', "/");
    if !n.starts_with("META-INF/") {
        return false;
    }
    let file = n.rsplit('/').next().unwrap_or_default();
    let extension = file.rsplit_once('.').map(|(_, e)| e.to_uppercase()).unwrap_or_default();
    matches!(extension.as_str(), "SF" | "RSA" | "DSA")
}

/// `planJar`: `None` when the jar must be left alone. Nested jars only lose what jarjar declares.
fn plan(jar: &Jar, top_level: bool, depth: usize) -> Result<Option<Plan>> {
    let metadata = match jar.entries.get(JARJAR_METADATA) {
        Some(bytes) => Some(match serde_json::from_slice::<Value>(bytes)? {
            Value::Object(map) => map,
            _ => anyhow::bail!("jarjar元数据不是对象"),
        }),
        None => None,
    };
    if top_level && metadata.is_none() {
        return Ok(None);
    }
    let deps = metadata.as_ref().map(dependencies).unwrap_or_default();
    if deps.iter().any(|d| d.provided() && !jar.entries.contains_key(&d.path)) {
        return Ok(None);
    }
    let mut nested = BTreeMap::new();
    if depth < MAX_NESTED_DEPTH {
        for d in deps.iter().filter(|d| !d.provided()) {
            let Some(bytes) = jar.entries.get(&d.path) else {
                continue;
            };
            let inner = match Jar::read(Cursor::new(bytes.as_slice())) {
                Ok(inner) => inner,
                Err(error) => {
                    tracing::warn!(%error, path = %d.path, "无法读取嵌套jar，跳过Kotlin运行库检查");
                    continue;
                }
            };
            if let Some(p) = plan(&inner, false, depth + 1)?
                && p.needs_normalization()
            {
                nested.insert(d.path.clone(), (inner, p));
            }
        }
    }
    Ok(Some(Plan {
        removed: deps.iter().filter(|d| d.provided()).map(|d| d.path.clone()).collect(),
        strip_shaded: top_level && jar.names.iter().any(|n| shaded(n)),
        metadata,
        nested,
    }))
}

fn keep(name: &str, plan: &Plan) -> bool {
    if name == "META-INF/MANIFEST.MF" {
        return true;
    }
    !(name == JARJAR_METADATA || plan.removed.contains(name) || signature(name) || (plan.strip_shaded && shaded(name)))
}

/// `writeThinJar`: kept entries in order, then the metadata without the removed jars.
fn write_thin<W: Write + Seek>(jar: &Jar, plan: &Plan, out: W) -> Result<W> {
    let mut zip = zip::ZipWriter::new(out);
    let options = zip::write::SimpleFileOptions::default();
    for name in jar.names.iter().filter(|n| keep(n, plan)) {
        zip.start_file(name.as_str(), options)?;
        match plan.nested.get(name) {
            Some((inner, inner_plan)) => zip.write_all(&write_thin(inner, inner_plan, Cursor::new(vec![]))?.into_inner())?,
            None => zip.write_all(&jar.entries[name])?,
        }
    }
    if let Some(metadata) = &plan.metadata {
        let mut filtered = Map::new();
        for (key, value) in metadata {
            if key == "jars" {
                let originals = value.as_array().cloned().unwrap_or_default();
                let jars: Vec<Value> = dependencies(metadata)
                    .iter()
                    .filter(|d| !plan.removed.contains(&d.path))
                    .filter_map(|d| originals.iter().find(|j| j.get("path").and_then(Value::as_str) == Some(d.path.as_str())).cloned())
                    .collect();
                filtered.insert(key.clone(), Value::Array(jars));
            } else {
                filtered.insert(key.clone(), value.clone());
            }
        }
        zip.start_file(JARJAR_METADATA, options)?;
        zip.write_all(serde_json::to_string(&Value::Object(filtered))?.as_bytes())?;
    }
    Ok(zip.finish()?)
}

struct Archive {
    file: PathBuf,
    jar: Jar,
    plan: Plan,
    kff: bool,
}

fn inspect(file: &Path) -> Option<Archive> {
    let jar = Jar::read(std::fs::File::open(file).ok()?).ok()?;
    let plan = plan(&jar, true, 0).ok()??;
    if !plan.needs_normalization() {
        return None;
    }
    // `isKff`: exactly the three Kotlin for Forge artifacts, each once.
    let kff: Vec<String> = plan
        .metadata
        .as_ref()
        .map(dependencies)
        .unwrap_or_default()
        .into_iter()
        .filter(|d| d.group == KFF_GROUP && KFF_ARTIFACTS.contains(&d.artifact.as_str()))
        .map(|d| d.artifact)
        .collect();
    let distinct: HashSet<&String> = kff.iter().collect();
    let is_kff = kff.len() == KFF_ARTIFACTS.len() && distinct.len() == KFF_ARTIFACTS.len();
    Some(Archive { file: file.to_owned(), kff: is_kff, jar, plan })
}

/// `isThinKotlinMod`.
fn is_thin(file: &Path) -> bool {
    let Ok(Ok(jar)) = std::fs::File::open(file).map(Jar::read) else {
        return false;
    };
    match plan(&jar, true, 0) {
        Ok(Some(p)) => {
            !p.needs_normalization()
                && p.metadata.as_ref().map(dependencies).unwrap_or_default().iter().all(|d| jar.entries.contains_key(&d.path))
        }
        _ => false,
    }
}

fn move_over(source: &Path, target: &Path) -> Result<()> {
    std::fs::rename(source, target).with_context(|| format!("替换文件失败: {}", target.display()))
}

fn temp_beside(dir: &Path, prefix: &str) -> PathBuf {
    dir.join(format!("{prefix}-{}.tmp", uuid::Uuid::now_v7()))
}

fn normalize(archive: &Archive, cache_root: &Path) -> Result<()> {
    let sha = crate::mc_install::sha1_file(&archive.file)?.to_lowercase();
    let thin_root = cache_root.join("thin-kotlin-mod");
    let original_root = cache_root.join("original-kotlin-mod");
    std::fs::create_dir_all(&thin_root)?;
    std::fs::create_dir_all(&original_root)?;
    let normalized = thin_root.join(format!("{CACHE_SCHEMA}-{sha}.jar"));
    let original = original_root.join(format!("{sha}.jar"));
    if !original.is_file() || !crate::mc_install::sha1_file(&original).is_ok_and(|s| s.eq_ignore_ascii_case(&sha)) {
        std::fs::copy(&archive.file, &original)?;
    }
    if !normalized.is_file() || !is_thin(&normalized) {
        let name = normalized.file_name().unwrap().to_string_lossy().into_owned();
        let temporary = temp_beside(&thin_root, &name);
        let result = (|| -> Result<()> {
            write_thin(&archive.jar, &archive.plan, std::fs::File::create(&temporary)?)?;
            ensure!(is_thin(&temporary), "生成的thin Kotlin Mod校验失败: {}", temporary.display());
            move_over(&temporary, &normalized)
        })();
        let _ = std::fs::remove_file(&temporary);
        result?;
    }
    let parent = archive.file.parent().with_context(|| format!("Kotlin Mod文件缺少父目录: {}", archive.file.display()))?;
    let name = archive.file.file_name().unwrap().to_string_lossy().into_owned();
    let replacement = temp_beside(parent, &format!("{name}.rdi-kotlin"));
    let result = std::fs::copy(&normalized, &replacement).map_err(anyhow::Error::from).and_then(|_| move_over(&replacement, &archive.file));
    let _ = std::fs::remove_file(&replacement);
    result
}

/// `GameKotlinRuntime.prepare` without the class path part: thins every Kotlin mod in `mods`.
/// More than one Kotlin for Forge file is refused before anything changes.
pub fn thin_mods(mods_dir: &Path, cache_root: &Path) -> Result<()> {
    if !mods_dir.is_dir() {
        return Ok(());
    }
    let mut files: Vec<PathBuf> = std::fs::read_dir(mods_dir)?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.is_file() && p.extension().is_some_and(|e| e.eq_ignore_ascii_case("jar")))
        .collect();
    files.sort();
    let archives: Vec<Archive> = files.iter().filter_map(|f| inspect(f)).collect();
    let kff: Vec<String> = archives.iter().filter(|a| a.kff).map(|a| a.file.file_name().unwrap().to_string_lossy().into_owned()).collect();
    ensure!(kff.len() <= 1, "发现多个Kotlin for Forge文件: {}", kff.join(", "));
    for archive in &archives {
        normalize(archive, cache_root)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const FIXTURE: &str = include_str!("../../protocol/fixtures/j5b-thin.json");

    fn describe(bytes: &[u8]) -> Value {
        let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).unwrap();
        let mut out = vec![];
        for index in 0..zip.len() {
            let mut entry = zip.by_index(index).unwrap();
            if entry.is_dir() {
                continue;
            }
            let name = entry.name().to_owned();
            let mut content = vec![];
            entry.read_to_end(&mut content).unwrap();
            let mut item = Map::new();
            item.insert("name".into(), name.clone().into());
            if name == JARJAR_METADATA {
                item.insert("json".into(), serde_json::from_slice(&content).unwrap());
            } else if name.ends_with(".jar") {
                item.insert("jar".into(), describe(&content));
            } else {
                use sha1::Digest as _;
                let hash: String = sha1::Sha1::digest(&content).iter().map(|b| format!("{b:02x}")).collect();
                item.insert("sha1".into(), hash.into());
            }
            out.push(Value::Object(item));
        }
        Value::Array(out)
    }

    fn temp() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("rdi-j5b-thin-{}", uuid::Uuid::now_v7()));
        std::fs::create_dir_all(dir.join("mods")).unwrap();
        dir
    }

    #[test]
    fn thinned_mods_match_the_kotlin_fixture() {
        let fixture: Value = serde_json::from_str(FIXTURE).unwrap();
        assert_eq!(fixture["cacheSchema"], CACHE_SCHEMA);
        let inputs = Path::new(env!("CARGO_MANIFEST_DIR")).join("protocol/fixtures/j5b-thin");
        let root = temp();
        let mods = root.join("mods");
        let cache = root.join("kotlin-runtime");
        let cases = fixture["mods"].as_array().unwrap();
        for case in cases {
            let name = case["name"].as_str().unwrap();
            std::fs::copy(inputs.join(name), mods.join(name)).unwrap();
        }
        thin_mods(&mods, &cache).unwrap();
        let first: Vec<Vec<u8>> = cases.iter().map(|c| std::fs::read(mods.join(c["name"].as_str().unwrap())).unwrap()).collect();
        thin_mods(&mods, &cache).unwrap();
        for (case, before) in cases.iter().zip(&first) {
            let name = case["name"].as_str().unwrap();
            let input = std::fs::read(inputs.join(name)).unwrap();
            let output = std::fs::read(mods.join(name)).unwrap();
            assert_eq!(&output, before, "second pass changed {name}");
            assert_eq!(input != output, case["changed"].as_bool().unwrap(), "{name}");
            if case["changed"] == true {
                assert_eq!(describe(&output), case["entries"], "{name}");
                assert!(cache.join(case["thinCache"].as_str().unwrap()).is_file(), "{name}");
                assert_eq!(std::fs::read(cache.join(case["originalCache"].as_str().unwrap())).unwrap(), input, "{name}");
                assert!(is_thin(&mods.join(name)));
            }
        }
        // Two Kotlin for Forge files: refused, nothing changed.
        let two = root.join("two");
        std::fs::create_dir_all(&two).unwrap();
        std::fs::copy(inputs.join("kff-5.jar"), two.join("kff-b.jar")).unwrap();
        std::fs::copy(inputs.join("kff-5.jar"), two.join("kff-a.jar")).unwrap();
        assert_eq!(thin_mods(&two, &root.join("cache2")).unwrap_err().to_string(), "发现多个Kotlin for Forge文件: kff-a.jar, kff-b.jar");
        assert_eq!(std::fs::read(two.join("kff-a.jar")).unwrap(), std::fs::read(inputs.join("kff-5.jar")).unwrap());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn a_thin_jar_is_left_alone() {
        // A thin jar is recognised by content; Kotlin's own check uses the same plan, so neither
        // launcher rewrites what the other produced.
        let root = temp();
        let inputs = Path::new(env!("CARGO_MANIFEST_DIR")).join("protocol/fixtures/j5b-thin");
        let mods = root.join("mods");
        std::fs::copy(inputs.join("kotlin-mod.jar"), mods.join("kotlin-mod.jar")).unwrap();
        thin_mods(&mods, &root.join("cache")).unwrap();
        let thin = std::fs::read(mods.join("kotlin-mod.jar")).unwrap();
        let other = root.join("other");
        std::fs::create_dir_all(&other).unwrap();
        std::fs::write(other.join("kotlin-mod.jar"), &thin).unwrap();
        thin_mods(&other, &root.join("cache-other")).unwrap();
        assert_eq!(std::fs::read(other.join("kotlin-mod.jar")).unwrap(), thin);
        assert!(!root.join("cache-other").exists(), "nothing to thin, no cache written");
        std::fs::remove_dir_all(root).unwrap();
    }
}
