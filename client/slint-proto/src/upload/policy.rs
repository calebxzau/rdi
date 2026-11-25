//! Which pack entries the upload leaves out: Kotlin `shouldSkipEntry` with its lists,
//! `containsCacheDirectory`, `isQuestLangEntryDisallowed`, `isExcludedConfigPath`,
//! `isDisabledFile` and `SaveRegionPaths`. The pack reader needs them for the 地图模板 warning;
//! the archive builder (stage A3) applies them to every entry.
use super::mods::client_entry_path;
use std::collections::BTreeSet;

const DISALLOWED_PREFIXES: [&str; 2] = ["shaderpacks/", "kubejs/probe/"];
const DISALLOWED_KEYWORDS: [&str; 3] = ["yes_steve_model", "史蒂夫模型", "touhou_little_maid"];
const QUEST_LANG_PREFIX: &str = "config/ftbquests/quests/lang/";
const ALLOWED_QUEST_LANG: [&str; 2] = ["en_us.snbt", "zh_cn.snbt"];
/// `CONFIG_EXCLUDE_PATH_PREFIX`.
const CONFIG_EXCLUDED: [&str; 1] = ["yes_steve_model/builtin"];

/// A path as the game sees it, lower case: `resolveClientPackEntryPath` with a plain fallback.
pub fn game_path_lower(raw: &str) -> String {
    client_entry_path(raw, true).map(|p| p.relative.to_lowercase()).unwrap_or_else(|| raw.replace('\\', "/").trim_start_matches('/').to_lowercase())
}

/// `containsCacheDirectory`: a folder on the way whose name contains `cache`.
pub fn contains_cache_directory(path: &str, directory: bool) -> bool {
    let normalized = path.replace('\\', "/");
    let normalized = normalized.trim_matches('/');
    if normalized.is_empty() {
        return false;
    }
    let segments: Vec<&str> = normalized.split('/').collect();
    let folders = if directory { &segments[..] } else { &segments[..segments.len() - 1] };
    folders.iter().any(|s| s.to_lowercase().contains("cache"))
}

/// `isExcludedConfigPath`, for a path below `config/`.
pub fn excluded_config(path: &str) -> bool {
    let normalized = path.replace('\\', "/").trim_matches('/').to_lowercase();
    !normalized.is_empty() && CONFIG_EXCLUDED.iter().any(|prefix| normalized == *prefix || normalized.starts_with(&format!("{prefix}/")))
}

/// `isQuestLangEntryDisallowed`: of the quest languages only English and Chinese are kept.
pub fn quest_lang_disallowed(lower: &str, directory: bool) -> bool {
    let Some(rest) = lower.strip_prefix(QUEST_LANG_PREFIX) else { return false };
    if rest.is_empty() {
        return false;
    }
    directory || rest.contains('/') || !ALLOWED_QUEST_LANG.contains(&rest)
}

/// `shouldSkipEntry`. `raw_lower` is the lower case path inside the pack.
pub fn skip_entry(raw_lower: &str, directory: bool, skip_cache: bool, retained_shader_configs: &[String]) -> bool {
    let lower = game_path_lower(raw_lower);
    let retained = retained_shader_configs.iter().any(|p| lower == p.to_lowercase().replace('\\', "/"));
    if DISALLOWED_PREFIXES.iter().any(|p| lower.starts_with(p)) && !retained {
        return true;
    }
    if skip_cache && contains_cache_directory(&lower, directory) {
        return true;
    }
    if lower.strip_prefix("config/").is_some_and(excluded_config) {
        return true;
    }
    DISALLOWED_KEYWORDS.iter().any(|k| lower.contains(k)) || lower.ends_with(".mp4") || lower.ends_with(".mov") || quest_lang_disallowed(&lower, directory)
}

/// `isDisabledFile`: a file whose last extension is `disabled`.
pub fn is_disabled(path: &str, directory: bool) -> bool {
    if directory {
        return false;
    }
    let name = path.rsplit('/').next().unwrap_or(path);
    name.rsplit_once('.').is_some_and(|(_, ext)| ext.eq_ignore_ascii_case("disabled"))
}

/// `SaveRegionPaths`: region files (`.mca`, `.mcc`) of the saves in one tree. A save is a
/// folder holding `level.dat`; anything under an `ftbteambases` folder is never one.
pub struct SaveRegions {
    roots: BTreeSet<String>,
}

impl SaveRegions {
    fn normalize(path: &str) -> String {
        path.replace('\\', "/").trim_matches('/').to_owned()
    }

    /// `paths` are the files of the tree, relative to its root.
    pub fn of<'a>(paths: impl IntoIterator<Item = &'a str>) -> Self {
        let roots = paths
            .into_iter()
            .map(Self::normalize)
            .filter(|p| p.rsplit('/').next().unwrap_or_default().eq_ignore_ascii_case("level.dat"))
            .map(|p| p.rsplit_once('/').map(|(dir, _)| dir.to_lowercase()).unwrap_or_default())
            .collect();
        Self { roots }
    }

    pub fn is_save_region(&self, path: &str, directory: bool) -> bool {
        if directory {
            return false;
        }
        let normalized = Self::normalize(path);
        let lower = normalized.to_lowercase();
        let name = lower.rsplit('/').next().unwrap_or_default();
        if !name.ends_with(".mca") && !name.ends_with(".mcc") {
            return false;
        }
        if normalized.split('/').any(|s| s.eq_ignore_ascii_case("ftbteambases")) {
            return false;
        }
        self.roots.iter().any(|root| root.is_empty() || lower.starts_with(&format!("{root}/")))
    }
}
