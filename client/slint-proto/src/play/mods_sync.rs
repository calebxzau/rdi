//! J5b: a room's base and extra mods and the removed-mod cleanup before a room game starts,
//! ported from `HostBaseModSync.kt`, `HostExtraModSync.kt` and `RemovedModCleanupService.kt`.
//! `protocol/fixtures/j5b-sync.json` (Kotlin `J5bRoomModSyncFixtureTest`) guards the rules.
use crate::pack_install::mods::{FORGE_ONLY_REMOVED_SLUGS, Mod, REMOVED_SLUGS, Side};
use anyhow::{Result, ensure};
use std::collections::HashSet;
use std::path::Path;

/// `EXTRA_MOD_PREFIX`: extra mods are stored as `X$_<fileName>`.
pub const EXTRA_MOD_PREFIX: &str = "X$_";

/// `ModpackModProcessor.removedSlugsFor`.
pub fn removed_slugs(fabric: bool) -> Vec<&'static str> {
    REMOVED_SLUGS.iter().copied().filter(|s| !(fabric && FORGE_ONLY_REMOVED_SLUGS.contains(s))).collect()
}

fn removed(m: &Mod, fabric: bool) -> bool {
    removed_slugs(fabric).contains(&m.normalized_slug().as_str())
}

/// `RemovedModCleanupService.matchesRemovedSlug`.
pub fn matches_removed(file_name: &str, fabric: bool) -> bool {
    let name = file_name.to_lowercase();
    let Some(stem) = name.strip_suffix(".jar") else {
        return false;
    };
    removed_slugs(fabric)
        .iter()
        .any(|slug| stem == *slug || stem.starts_with(&format!("{slug}_")) || stem.starts_with(&format!("{slug}-")))
}

/// `RemovedModCleanupService.cleanup`: deletes matching jars (and symlinks) from `mods`, sorted
/// by name; a missing `mods` folder is nothing to do.
pub fn cleanup_removed(version_dir: &Path, fabric: bool) -> Result<Vec<String>> {
    let mods = version_dir.join("mods");
    let meta = match std::fs::symlink_metadata(&mods) {
        Ok(meta) => meta,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
        Err(error) => return Err(error.into()),
    };
    ensure!(meta.is_dir(), "整合包mods路径不是目录: {}", mods.display());
    let mut names = vec![];
    for entry in std::fs::read_dir(&mods)? {
        let entry = entry?;
        let kind = entry.file_type()?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if (kind.is_file() || kind.is_symlink()) && matches_removed(&name, fabric) {
            names.push(name);
        }
    }
    names.sort();
    let mut deleted = vec![];
    for name in names {
        match std::fs::remove_file(mods.join(&name)) {
            Ok(()) => deleted.push(name),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(deleted)
}

fn regular_file(path: &Path) -> bool {
    std::fs::symlink_metadata(path).is_ok_and(|m| m.is_file())
}

fn distinct_by_file_name(mods: impl IntoIterator<Item = Mod>) -> Vec<Mod> {
    let mut seen = HashSet::new();
    mods.into_iter().filter(|m| seen.insert(m.file_name())).collect()
}

/// What a sync deletes from `mods` and which mods it downloads.
#[derive(Debug, Default)]
pub struct Plan {
    pub delete: Vec<String>,
    pub download: Vec<Mod>,
}

/// `buildHostBaseModSyncTask2`: disabled, server-only and unknown-side mods go, the others must
/// be present under any of their file names. Reads which files exist; changes nothing.
pub fn base_plan(mods_dir: &Path, active: &[Mod], disabled: &[Mod], fabric: bool) -> Plan {
    let active: Vec<Mod> = active.iter().filter(|m| !removed(m, fabric)).cloned().collect();
    let disabled = disabled.iter().filter(|m| !removed(m, fabric)).cloned();
    let unwanted = active.iter().filter(|m| matches!(m.side, Side::Server | Side::Unknown)).cloned();
    let remove = distinct_by_file_name(disabled.chain(unwanted));
    let keep = distinct_by_file_name(active.iter().filter(|m| !matches!(m.side, Side::Server | Side::Unknown)).cloned());
    let delete: Vec<String> =
        remove.iter().flat_map(Mod::file_names).filter(|name| mods_dir.join(name).exists()).collect();
    let download = keep
        .into_iter()
        .filter(|m| !m.file_names().iter().any(|n| !delete.contains(n) && regular_file(&mods_dir.join(n))))
        .collect();
    Plan { delete, download }
}

/// `buildHostExtraModSyncTask2`: client-installable extras, stored with the prefix; prefixed files
/// that are no longer wanted go.
pub fn extra_plan(mods_dir: &Path, extra: &[Mod], fabric: bool) -> Result<Plan> {
    let extra = distinct_by_file_name(
        extra.iter().filter(|m| !matches!(m.side, Side::Server | Side::Unknown) && !removed(m, fabric)).cloned(),
    );
    let wanted: HashSet<String> = extra.iter().map(extra_name).collect();
    let mut delete = vec![];
    if mods_dir.is_dir() {
        for entry in std::fs::read_dir(mods_dir)? {
            let name = entry?.file_name().to_string_lossy().into_owned();
            if name.starts_with(EXTRA_MOD_PREFIX) && !wanted.contains(&name) {
                delete.push(name);
            }
        }
    }
    delete.sort();
    let download = extra.into_iter().filter(|m| !regular_file(&mods_dir.join(extra_name(m)))).collect();
    Ok(Plan { delete, download })
}

/// `extraModTargetFileName`.
pub fn extra_name(m: &Mod) -> String {
    format!("{EXTRA_MOD_PREFIX}{}", m.file_name())
}

/// Deletes the planned files; one that is already gone is fine.
pub fn delete(mods_dir: &Path, names: &[String]) -> Result<()> {
    for name in names {
        match std::fs::remove_file(mods_dir.join(name)) {
            Err(error) if error.kind() != std::io::ErrorKind::NotFound => return Err(error.into()),
            _ => {}
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    const FIXTURE: &str = include_str!("../../protocol/fixtures/j5b-sync.json");

    fn temp() -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("rdi-j5b-sync-{}", uuid::Uuid::now_v7()));
        std::fs::create_dir_all(dir.join("mods")).unwrap();
        dir
    }

    fn strings(v: &Value) -> Vec<String> {
        v.as_array().unwrap().iter().map(|s| s.as_str().unwrap().to_owned()).collect()
    }

    fn mods(v: &Value) -> Vec<Mod> {
        serde_json::from_value(v.clone()).unwrap()
    }

    #[test]
    fn rules_match_the_kotlin_fixture() {
        let fixture: Value = serde_json::from_str(FIXTURE).unwrap();
        assert_eq!(fixture["extraModPrefix"], EXTRA_MOD_PREFIX);
        let mut ours = removed_slugs(false);
        ours.sort();
        assert_eq!(strings(&fixture["removedSlugsForge"]), ours);
        for case in fixture["removedNames"].as_array().unwrap() {
            let file = case["file"].as_str().unwrap();
            assert_eq!(matches_removed(file, false), case["forge"].as_bool().unwrap(), "{file}");
            assert_eq!(matches_removed(file, false), case["neoforge"].as_bool().unwrap(), "{file}");
        }
        for case in fixture["cases"].as_array().unwrap() {
            let name = case["name"].as_str().unwrap();
            let fabric = case["loader"] == "Fabric";
            let root = temp();
            let dir = root.join("mods");
            for file in strings(&case["existing"]) {
                std::fs::write(dir.join(file), "x").unwrap();
            }
            let base = base_plan(&dir, &mods(&case["active"]), &mods(&case["disabled"]), fabric);
            assert_eq!(base.delete, strings(&case["baseDeleted"]), "{name}");
            assert_eq!(base.download.iter().map(Mod::file_name).collect::<Vec<_>>(), strings(&case["baseDownload"]), "{name}");
            delete(&dir, &base.delete).unwrap();
            let extra = extra_plan(&dir, &mods(&case["extra"]), fabric).unwrap();
            assert_eq!(extra.delete, strings(&case["extraDeleted"]), "{name}");
            assert_eq!(extra.download.iter().map(extra_name).collect::<Vec<_>>(), strings(&case["extraDownload"]), "{name}");
            std::fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn removed_mods_are_cleaned_from_the_folder() {
        let root = temp();
        assert!(cleanup_removed(&root.join("missing"), false).unwrap().is_empty());
        for name in ["spark-1.0.jar", "jei.jar", "chunky_1.jar", "spark.txt"] {
            std::fs::write(root.join("mods").join(name), "x").unwrap();
        }
        std::fs::create_dir(root.join("mods/spark.jar")).unwrap();
        assert_eq!(cleanup_removed(&root, false).unwrap(), ["chunky_1.jar", "spark-1.0.jar"]);
        assert!(root.join("mods/jei.jar").is_file() && root.join("mods/spark.jar").is_dir());
        let file_root = temp();
        std::fs::remove_dir(file_root.join("mods")).unwrap();
        std::fs::write(file_root.join("mods"), "x").unwrap();
        assert!(cleanup_removed(&file_root, false).unwrap_err().to_string().starts_with("整合包mods路径不是目录"));
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_dir_all(file_root).unwrap();
    }
}
