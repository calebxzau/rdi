//! The parts of Kotlin `ModService.readModMeta` the upload needs: the primary mod id, its
//! version and, for Fabric, the declared side. NeoForge `META-INF/neoforge.mods.toml` comes
//! before Forge `META-INF/mods.toml`, then `fabric.mod.json`, then legacy `mcmod.info`.
use crate::pack_install::mods::Side;
use serde_json::Value;
use std::io::Read;
use std::path::Path;

const MODS_TOML_PATHS: [&str; 2] = ["META-INF/neoforge.mods.toml", "META-INF/mods.toml"];
const FABRIC_CONFIG: &str = "fabric.mod.json";
const LEGACY_CONFIG: &str = "mcmod.info";
/// `MAX_NESTED_METADATA_BYTES`, applied to every metadata file here.
const MAX_META: u64 = 1024 * 1024;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct JarMeta {
    /// Lower case, as `primaryModId`.
    pub mod_id: Option<String>,
    pub version: Option<String>,
    /// Only Fabric declares one (`environment`).
    pub side: Option<Side>,
}

fn non_blank(text: Option<&str>) -> Option<String> {
    text.map(str::trim).filter(|t| !t.is_empty()).map(str::to_owned)
}

fn entry_text(zip: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Option<String> {
    let entry = zip.by_name(name).ok()?;
    if entry.is_dir() {
        return None;
    }
    let mut bytes = Vec::new();
    entry.take(MAX_META + 1).read_to_end(&mut bytes).ok()?;
    if bytes.len() as u64 > MAX_META {
        return None;
    }
    Some(String::from_utf8_lossy(&bytes).into_owned())
}

/// `[[mods]]` entries with a mod id: the first one is primary.
fn from_mods_toml(raw: &str) -> Option<JarMeta> {
    if raw.trim().is_empty() {
        return None;
    }
    let table: toml::Table = toml::from_str(raw).ok()?;
    let mods = table.get("mods")?.as_array()?;
    let primary = mods.iter().find(|m| m.get("modId").and_then(toml::Value::as_str).is_some_and(|id| !id.trim().is_empty()))?;
    Some(JarMeta {
        mod_id: non_blank(primary.get("modId").and_then(toml::Value::as_str)).map(|id| id.to_lowercase()),
        version: non_blank(primary.get("version").and_then(toml::Value::as_str)),
        side: None,
    })
}

/// `parseFabricModMeta`.
fn from_fabric(raw: &str) -> Option<JarMeta> {
    let root: Value = serde_json::from_str(raw).ok()?;
    let id = non_blank(root.get("id")?.as_str())?.to_lowercase();
    let side = match root.get("environment").and_then(Value::as_str).map(|e| e.trim().to_lowercase()).as_deref() {
        Some("client") => Some(Side::Client),
        Some("server") => Some(Side::Server),
        _ => None,
    };
    Some(JarMeta { mod_id: Some(id), version: non_blank(root.get("version").and_then(Value::as_str)), side })
}

/// `readLegacyForgeModMeta`: `mcmod.info` as an array, or an object with `modList`.
fn from_legacy(raw: &str) -> Option<JarMeta> {
    let value: Value = serde_json::from_str(raw.trim()).ok()?;
    let entries = match &value {
        Value::Array(items) => items.clone(),
        Value::Object(object) => object.get("modList").and_then(Value::as_array).cloned().unwrap_or_default(),
        _ => vec![],
    };
    let primary = entries.iter().find(|e| e.get("modid").and_then(Value::as_str).is_some_and(|id| !id.trim().is_empty()))?;
    Some(JarMeta {
        mod_id: non_blank(primary.get("modid").and_then(Value::as_str)).map(|id| id.to_lowercase()),
        version: non_blank(primary.get("version").and_then(Value::as_str)),
        side: None,
    })
}

/// `JarFile.readModMeta`; `None` when the jar cannot be read or declares nothing.
pub fn read(path: &Path) -> Option<JarMeta> {
    let file = std::fs::File::open(path).ok()?;
    let mut zip = zip::ZipArchive::new(file).ok()?;
    for name in MODS_TOML_PATHS {
        if let Some(raw) = entry_text(&mut zip, name) {
            // The first mods.toml present decides, even when it names no mod.
            if let Some(meta) = from_mods_toml(&raw) {
                return Some(meta);
            }
            break;
        }
    }
    if let Some(raw) = entry_text(&mut zip, FABRIC_CONFIG)
        && let Some(meta) = from_fabric(&raw)
    {
        return Some(meta);
    }
    entry_text(&mut zip, LEGACY_CONFIG).and_then(|raw| from_legacy(&raw))
}

#[cfg(test)]
pub(super) fn parse_for_test(kind: &str, raw: &str) -> Option<JarMeta> {
    match kind {
        "toml" => from_mods_toml(raw),
        "fabric" => from_fabric(raw),
        _ => from_legacy(raw),
    }
}
