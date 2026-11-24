//! The install state a catalog file shows for a local pack (`updateInstallStatuses`,
//! `refreshLocalInstallState`) and the task keys Compose dedupes with.
use super::records::{self, ContentType, Record};
use crate::{
    catalog::models::{File, Platform},
    digests::{self, Algorithm},
    tasks::Cancellation,
};
use std::{
    collections::{HashMap, HashSet},
    path::Path,
};

pub const NOT_INSTALLED: &str = "未安装";
pub const INSTALLED: &str = "已安装";
pub const UPDATABLE: &str = "可更新";
pub const DOWNLOADING: &str = "下载中";
pub const FAILED: &str = "安装失败";
pub const MISSING: &str = "文件缺失";
pub const MODIFIED: &str = "文件已被修改";
pub const RUNNING: &str = "运行中不可更新";

/// `catalog-mod-local:<versionId>:<projectId>`.
pub fn mod_task_key(version_id: &str, project_id: &str) -> String {
    format!("catalog-mod-local:{version_id}:{project_id}")
}
/// `modrinth-project-file:<dir>:<versionId>:<projectId>`.
pub fn resource_task_key(dir: &str, version_id: &str, project_id: &str) -> String {
    format!("modrinth-project-file:{dir}:{version_id}:{project_id}")
}

/// What the newest task with a file's key says (`localModTaskStatus`): queued or running is
/// 下载中, failed or cancelled is 安装失败, done says nothing.
pub fn task_status(status: Option<u8>) -> Option<&'static str> {
    match status? {
        0 | 1 => Some(DOWNLOADING),
        3 | 4 => Some(FAILED),
        _ => None,
    }
}

/// `cf` or `mr`, the record source of a catalog platform.
pub fn source(platform: Platform) -> &'static str {
    match platform {
        Platform::CurseForge => "cf",
        Platform::Modrinth => "mr",
    }
}

/// `modStatus`: 未安装, a health problem, 已安装 for this very file, else 可更新.
pub fn record_status(file: &File, records: &[Record], health: &HashMap<String, &'static str>) -> &'static str {
    let Some(record) = records::find(records, ContentType::Mod, source(file.reference.platform), &file.project.id) else {
        return NOT_INSTALLED;
    };
    if let Some(problem) = health.get(&record.project_key()) {
        problem
    } else if record.version_id == file.reference.id {
        INSTALLED
    } else {
        UPDATABLE
    }
}

/// One file's line: the task first, then the records; a managed mod of a running pack that is
/// not this exact file says 运行中不可更新.
pub fn file_status(file: &File, task: Option<u8>, records: &[Record], health: &HashMap<String, &'static str>, running: bool) -> &'static str {
    let base = task_status(task).unwrap_or_else(|| record_status(file, records, health));
    let managed = records::find(records, ContentType::Mod, source(file.reference.platform), &file.project.id).is_some();
    if running && managed && base != INSTALLED { RUNNING } else { base }
}

/// The 安装 button and the file cards stay clickable unless the file is being downloaded, is
/// already there or cannot change while the game runs.
pub fn installable(status: &str) -> bool {
    !matches!(status, DOWNLOADING | INSTALLED | RUNNING)
}

/// The health of the mod records of these projects: 文件缺失 when the jar is gone, 文件已被修改
/// when its hash no longer matches the record. Blocking.
pub fn health(pack_dir: &Path, records: &[Record], projects: &HashSet<String>) -> HashMap<String, &'static str> {
    let cancel = Cancellation::default();
    let mods = pack_dir.join("mods");
    records
        .iter()
        .filter(|r| r.kind == ContentType::Mod && projects.contains(&r.project_id))
        .filter_map(|r| {
            let file = mods.join(&r.file_name);
            let problem = if !file.is_file() {
                Some(MISSING)
            } else {
                let algorithm = match r.source.as_str() {
                    "mr" => Some(Algorithm::Sha1),
                    "cf" => Some(Algorithm::Murmur2),
                    _ => None,
                };
                match (algorithm, r.hash.as_deref()) {
                    (Some(algorithm), Some(expected)) => match digests::hash(&file, algorithm, &cancel) {
                        Ok(actual) if actual.eq_ignore_ascii_case(expected.trim()) => None,
                        Ok(_) => Some(MODIFIED),
                        Err(error) => {
                            tracing::warn!(%error, file = %file.display(), "校验已安装Mod失败");
                            Some(MODIFIED)
                        }
                    },
                    _ => None,
                }
            };
            problem.map(|p| (r.project_key(), p))
        })
        .collect()
}
