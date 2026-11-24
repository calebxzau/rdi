//! Mod list rules of the upload: Kotlin `resolveClientPackEntryPath` (common/archive),
//! `distinctUploadMods`, `embeddedModUploadSide`, `markRawManifestModReplacements`,
//! `selectEmbeddedModPathWinners`, `selectPreferredModFiles` with its loose version order, and
//! `ModService.postProcessModSides`.
use crate::pack_install::mods::{Mod, Side};
use std::cmp::Ordering;
use std::path::{Path, PathBuf};

/// A pack path mapped to where the game sees it; priority 2 client overrides, 1 overrides,
/// 0 unprefixed.
#[derive(Clone, Debug, PartialEq)]
pub struct EntryPath {
    pub relative: String,
    pub priority: u8,
}

/// `resolveClientPackEntryPath`.
pub fn client_entry_path(path: &str, allow_unprefixed: bool) -> Option<EntryPath> {
    let normalized = crate::pack_install::archive::normalize_safe_path(path).ok()?;
    if normalized.is_empty() {
        return None;
    }
    let segments: Vec<&str> = normalized.split('/').collect();
    let server = |s: &str| matches!(s.to_lowercase().as_str(), "server" | "server-overrides");
    if server(segments[0]) {
        return None;
    }
    let client = segments.iter().position(|s| *s == "client-overrides");
    let common = segments.iter().position(|s| *s == "overrides");
    let layer = match (client, common) {
        (Some(c), Some(o)) if c < o => Some(c),
        (Some(c), None) => Some(c),
        (_, Some(o)) => Some(o),
        (None, None) => None,
    };
    if let Some(layer) = layer {
        if segments[..layer].iter().any(|s| server(s)) {
            return None;
        }
        let relative = segments[layer + 1..].join("/");
        if relative.is_empty() {
            return None;
        }
        return Some(EntryPath { relative, priority: if segments[layer] == "client-overrides" { 2 } else { 1 } });
    }
    if matches!(segments[0], "manifest.json" | "modrinth.index.json") {
        return None;
    }
    if allow_unprefixed || matches!(segments[0], "gtnh" | "resourcepacks" | "shaderpacks") {
        return Some(EntryPath { relative: normalized, priority: 0 });
    }
    None
}

/// `embeddedModUploadSide`.
pub fn embedded_side(mut m: Mod, client_override: bool) -> Mod {
    if client_override {
        m.side = Side::Client;
        m.client_only_override = true;
    }
    m
}

/// `distinctUploadMods`: one entry per platform, project, file and hash; sides merge.
pub fn distinct(mods: Vec<Mod>) -> Vec<Mod> {
    let mut out: Vec<(String, Mod)> = Vec::new();
    for m in mods {
        let key = format!("{}:{}:{}:{}", m.platform, m.project_id, m.file_id, m.hash);
        match out.iter_mut().find(|(k, _)| *k == key) {
            None => out.push((key, m)),
            Some((_, previous)) => {
                previous.side = match (previous.side, m.side) {
                    (a, b) if a == b => a,
                    (Side::Unknown, b) => b,
                    (a, Side::Unknown) => a,
                    _ => Side::Both,
                };
                previous.client_only_override = previous.client_only_override && m.client_only_override;
                previous.client_override_replaced = previous.client_override_replaced || m.client_override_replaced;
            }
        }
    }
    out.into_iter().map(|(_, m)| m).collect()
}

/// A Modrinth index entry as `markRawManifestModReplacements` reads it.
pub struct IndexEntry {
    pub path: String,
    pub sha1: String,
    pub downloads: Vec<String>,
}

/// `markRawManifestModReplacements`: manifest mods whose path a raw client override replaces.
pub fn mark_raw_replacements(mods: Vec<Mod>, entries: &[IndexEntry], replaced_paths: &[String]) -> Vec<Mod> {
    let replaced: Vec<&IndexEntry> = entries
        .iter()
        .filter(|e| replaced_paths.contains(&e.path.replace('\\', "/").trim_start_matches('/').to_lowercase()))
        .collect();
    mods.into_iter()
        .map(|mut m| {
            let hit = replaced.iter().any(|e| {
                (m.platform == "mr" && m.hash.eq_ignore_ascii_case(&e.sha1)) || e.downloads.iter().any(|d| m.download_urls.contains(d))
            });
            if hit {
                m.client_override_replaced = true;
            }
            m
        })
        .collect()
}

/// `selectEmbeddedModPathWinners`: per game path and override kind, the highest priority.
pub fn path_winners(candidates: Vec<(PathBuf, EntryPath)>) -> Vec<(PathBuf, EntryPath)> {
    // `maxBy` keeps the first of equal priorities.
    let keep: Vec<PathBuf> = candidates
        .iter()
        .filter_map(|c| {
            let group: Vec<&(PathBuf, EntryPath)> = candidates
                .iter()
                .filter(|o| o.1.relative.to_lowercase() == c.1.relative.to_lowercase() && (o.1.priority == 2) == (c.1.priority == 2))
                .collect();
            let top = group.iter().map(|o| o.1.priority).max()?;
            group.iter().find(|o| o.1.priority == top).map(|o| o.0.clone())
        })
        .collect();
    candidates.into_iter().filter(|c| keep.contains(&c.0)).collect()
}

/// `substringBeforeVersionSuffix`.
fn before_version_suffix(name: &str) -> String {
    let re = regex::Regex::new(r"^(.*?)(?:[-_.]?\d.*)$").unwrap();
    match re.captures(name).and_then(|c| c.get(1)) {
        Some(m) => {
            let trimmed = m.as_str().trim_matches(['-', '_', '.']);
            if trimmed.trim().is_empty() { name.to_owned() } else { trimmed.to_owned() }
        }
        None => name.to_owned(),
    }
}

/// `extractVersionFromFileName`.
fn version_from_name(name: &str) -> String {
    let re = regex::Regex::new(r"(?:^|[-_.])(\d[\w.\-+]*)$").unwrap();
    re.captures(name)
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().trim().to_owned())
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| "0".into())
}

/// `compareLooseVersionStrings`.
pub fn compare_loose(left: &str, right: &str) -> Ordering {
    if left == right {
        return Ordering::Equal;
    }
    let tokens = |s: &str| -> Vec<String> {
        s.to_lowercase().split(|c: char| !(c.is_ascii_lowercase() || c.is_ascii_digit())).filter(|t| !t.is_empty()).map(str::to_owned).collect()
    };
    let (l, r) = (tokens(left), tokens(right));
    for index in 0..l.len().max(r.len()) {
        let (a, b) = (l.get(index), r.get(index));
        if a == b {
            continue;
        }
        let (Some(a), Some(b)) = (a, b) else {
            return if a.is_none() { Ordering::Less } else { Ordering::Greater };
        };
        let order = match (a.parse::<i64>(), b.parse::<i64>()) {
            (Ok(x), Ok(y)) => x.cmp(&y),
            (Ok(_), Err(_)) => Ordering::Greater,
            (Err(_), Ok(_)) => Ordering::Less,
            _ => a.cmp(b),
        };
        if order != Ordering::Equal {
            return order;
        }
    }
    // Java `String.compareTo` orders UTF-16 units.
    left.encode_utf16().cmp(right.encode_utf16())
}

fn stem(path: &Path) -> String {
    path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default()
}

/// `embeddedModIdentityKey`.
fn identity_key(path: &Path, meta: Option<&super::jar_meta::JarMeta>) -> String {
    if let Some(id) = meta.and_then(|m| m.mod_id.clone()).filter(|id| !id.is_empty()) {
        return format!("modid:{id}");
    }
    let lower = stem(path).to_lowercase();
    let slug = before_version_suffix(&lower);
    if !slug.trim().is_empty() { format!("slug:{slug}") } else { format!("file:{lower}") }
}

/// `compareEmbeddedModFileVersion`.
fn compare_files(left: &(PathBuf, Option<super::jar_meta::JarMeta>), right: &(PathBuf, Option<super::jar_meta::JarMeta>)) -> Ordering {
    let version = |f: &(PathBuf, Option<super::jar_meta::JarMeta>)| {
        f.1.as_ref().and_then(|m| m.version.clone()).filter(|v| !v.trim().is_empty()).map(|v| v.trim().to_owned()).unwrap_or_else(|| version_from_name(&stem(&f.0)))
    };
    match compare_loose(&version(left), &version(right)) {
        Ordering::Equal => {
            let (a, b) = (stem(&left.0).to_lowercase(), stem(&right.0).to_lowercase());
            a.encode_utf16().cmp(b.encode_utf16())
        }
        order => order,
    }
}

/// `selectPreferredModFiles`: per mod identity the newest file; the rest is discarded.
pub fn preferred(files: Vec<PathBuf>) -> (Vec<PathBuf>, Vec<PathBuf>) {
    if files.len() <= 1 {
        return (files, vec![]);
    }
    let read: Vec<(PathBuf, Option<super::jar_meta::JarMeta>)> = files.into_iter().map(|f| { let m = super::jar_meta::read(&f); (f, m) }).collect();
    let mut groups: Vec<(String, Vec<usize>)> = Vec::new();
    for (index, (path, meta)) in read.iter().enumerate() {
        let key = identity_key(path, meta.as_ref());
        match groups.iter_mut().find(|(k, _)| *k == key) {
            Some((_, members)) => members.push(index),
            None => groups.push((key, vec![index])),
        }
    }
    let mut selected = Vec::new();
    for (_, members) in &groups {
        // `maxWithOrNull` keeps the last of equal elements.
        let mut best = members[0];
        for &m in &members[1..] {
            if compare_files(&read[m], &read[best]) != Ordering::Less {
                best = m;
            }
        }
        selected.push(best);
    }
    let keep: Vec<PathBuf> = selected.iter().map(|i| read[*i].0.clone()).collect();
    let discarded = read.iter().map(|r| r.0.clone()).filter(|p| !keep.contains(p)).collect();
    (keep, discarded)
}

/// `ModService.postProcessModSides`.
pub fn post_process_sides(mods: &mut [Mod]) {
    const FORCE_BOTH: &[&str] = &[
        "loot-beams-refork",
        "particular-reforged",
        "inventory-profiles-next",
        "inventory-tweaks-refoxed",
        "just-enough-resources-jer",
        "radiant-gear",
        "fusion-connected-textures",
        "apothic-attributes",
        "the-twilight-forest",
    ];
    const FORCE_CLIENT: &[&str] = &[
        "status-effect-bars-reforged",
        "mafglib",
        "flighthud-reborn",
        "i18nupdatemod",
        "modern-ui",
        "controllable",
        "reforgedplay-mod",
    ];
    for m in mods {
        let slug = m.slug.trim().to_lowercase();
        if m.client_only_override {
            m.side = Side::Client;
        } else if m.client_override_replaced {
            if m.side == Side::Both {
                m.side = Side::Server;
            }
        } else if slug.starts_with("ftb") || FORCE_BOTH.contains(&slug.as_str()) {
            m.side = Side::Both;
        } else if FORCE_CLIENT.contains(&slug.as_str()) {
            m.side = Side::Client;
        }
    }
}

#[cfg(test)]
pub(super) fn names_for_test(name: &str) -> (String, String) {
    (before_version_suffix(name), version_from_name(name))
}
