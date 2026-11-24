//! Putting one catalog mod or one Modrinth resource/shader pack into a local pack, as Compose
//! `ModCatalogDownloadService.downloadToLocalModpackTask2` and
//! `ModrinthProjectDownloadService.downloadProjectFileTask2`: download into a staging folder
//! under `.rdi/installing`, move the old file aside, move the new one in, write the record, and
//! put everything back if a step fails.
use super::records::{self, ContentType, Record};
use crate::{
    digests::{self, Algorithm},
    tasks::Cancellation,
};
use anyhow::{Context, Result, bail, ensure};
use std::path::{Path, PathBuf};

/// One file to place.
#[derive(Clone, Debug)]
pub struct Placement {
    pub kind: ContentType,
    /// `cf`/`mr` for mods, `modrinth` for packs.
    pub source: String,
    pub project_id: String,
    pub version_id: String,
    pub file_name: String,
    /// The record hash, and how the file is checked against it.
    pub hash: String,
    pub algorithm: Algorithm,
    /// The pack folder: `mods`, `resourcepacks` or `shaderpacks`.
    pub dir: &'static str,
    /// The backup name under `.rdi/installing` (Kotlin differs for mods and packs).
    pub backup: String,
    /// `Mod`, `资源包` or `光影包`, for messages.
    pub label: &'static str,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    /// The record already names this file and the file still matches.
    AlreadyThere,
    Installed,
}

/// `String.safePathSegment`.
pub fn safe_segment(value: &str) -> String {
    value.chars().map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-') { c } else { '_' }).collect()
}

/// `ModrinthProjectVersionFileVo.safeFilename`: the last path part, or a fixed name.
pub fn safe_file_name(name: &str) -> String {
    let last = name.replace('\\', "/").rsplit('/').next().unwrap_or("").to_owned();
    if last.trim().is_empty() { "modrinth-project-file.zip".into() } else { last }
}

fn matches(path: &Path, p: &Placement) -> bool {
    path.is_file() && digests::hash(path, p.algorithm, &Cancellation::default()).is_ok_and(|h| h.eq_ignore_ascii_case(p.hash.trim()))
}

/// Places one file. `running` is whether the pack's game is open; `fetch` must write
/// `<staging>/<file_name>` (the download). Blocking.
pub fn place(pack_dir: &Path, p: &Placement, running: bool, fetch: impl FnOnce(&Path) -> Result<()>) -> Result<Outcome> {
    ensure!(pack_dir.is_dir(), "目标整合包已不存在");
    let existing = records::find(&records::read(pack_dir)?, p.kind, &p.source, &p.project_id).cloned();
    let target_dir = pack_dir.join(p.dir);
    std::fs::create_dir_all(&target_dir)?;
    let target = target_dir.join(&p.file_name);
    if let Some(record) = &existing
        && record.version_id == p.version_id
        && matches(&target_dir.join(&record.file_name), p)
    {
        return Ok(Outcome::AlreadyThere);
    }
    if existing.is_some() && running {
        bail!("当前整合包正在运行，不能更新{}", p.label);
    }
    if target.exists() && existing.as_ref().map(|r| r.file_name.as_str()) != Some(p.file_name.as_str()) {
        bail!("目标文件已存在且不属于RDI管理: {}", p.file_name);
    }
    let work = pack_dir.join(".rdi").join("installing");
    std::fs::create_dir_all(&work)?;
    let staging = work.join(format!("{}-{}", safe_segment(p.dir), uuid::Uuid::now_v7()));
    std::fs::create_dir(&staging)?;
    let result = (|| {
        fetch(&staging)?;
        let staged = staging.join(&p.file_name);
        ensure!(staged.is_file(), "下载的文件不存在: {}", p.file_name);
        swap(pack_dir, &work, &target_dir, &target, &staged, existing.as_ref(), p)
    })();
    if let Err(error) = std::fs::remove_dir_all(&staging)
        && error.kind() != std::io::ErrorKind::NotFound
    {
        tracing::warn!(%error, dir = %staging.display(), "清理安装暂存目录失败");
    }
    result.map(|()| Outcome::Installed)
}

fn swap(pack_dir: &Path, work: &Path, target_dir: &Path, target: &Path, staged: &Path, existing: Option<&Record>, p: &Placement) -> Result<()> {
    let old = existing.map(|r| target_dir.join(&r.file_name)).filter(|f| f.exists());
    let backup = work.join(&p.backup);
    remove_file(&backup)?;
    let mut moved_old: Option<PathBuf> = None;
    let mut placed = false;
    let result = (|| {
        if let Some(old) = &old {
            std::fs::rename(old, &backup).with_context(|| format!("备份旧文件失败: {}", old.display()))?;
            moved_old = Some(old.clone());
        }
        std::fs::rename(staged, target).with_context(|| format!("放入新文件失败: {}", target.display()))?;
        placed = true;
        records::replace(
            pack_dir,
            &[Record {
                kind: p.kind,
                source: p.source.clone(),
                project_id: p.project_id.clone(),
                version_id: p.version_id.clone(),
                file_name: p.file_name.clone(),
                hash: Some(p.hash.clone()),
                installed_at: records::now(),
            }],
        )
    })();
    match result {
        Ok(()) => {
            if let Err(error) = remove_file(&backup) {
                tracing::warn!(%error, "删除旧文件备份失败");
            }
            Ok(())
        }
        Err(error) => {
            if placed && let Err(e) = remove_file(target) {
                tracing::error!(error = %e, "回滚时删除新文件失败");
            }
            if let Some(old) = moved_old
                && let Err(e) = std::fs::rename(&backup, &old)
            {
                tracing::error!(error = %e, file = %old.display(), "回滚时恢复旧文件失败");
            }
            Err(error)
        }
    }
}

fn remove_file(path: &Path) -> Result<()> {
    match std::fs::remove_file(path) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e.into()),
        _ => Ok(()),
    }
}
