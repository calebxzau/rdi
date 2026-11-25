//! M4 of `LAUNCHER-MCP-PLAN.md`: modpack upload in Rust, the port of Kotlin
//! `ModpackUploadViewModel`, `ModpackProcessor`, `ModpackUploadService` and `ModpackTestSession`,
//! in the stages of the plan's "M4 design". No Slint types: the launcher MCP is its only caller
//! (the Slint launcher has no upload screen by owner decision).
//!
//! Stage A1: the draft rules, the first look at a pack (`source`) and the server checks before
//! upload (`api`). A2 to A4: the pack readers, the archive builder and the encoders. A5a: the
//! drafts of the MCP tools (`drafts`), which wire the readers. A5b to A5d: publishing
//! (`publish`), the local install (`pack_install::install_local`) and the upload task (`submit`).
pub mod api;
pub mod draft;
pub mod drafts;
pub mod archive;
pub mod jar_meta;
pub mod manifest;
pub mod media;
pub mod mods;
pub mod platform;
pub mod policy;
pub mod prepare;
pub mod publish;
pub mod reader;
pub mod server_pack;
pub mod source;
pub mod submit;
#[cfg(test)]
mod archive_tests;
#[cfg(test)]
mod drafts_tests;
#[cfg(test)]
mod manifest_tests;
#[cfg(test)]
mod prepare_tests;
#[cfg(test)]
mod publish_tests;
#[cfg(test)]
mod reader_tests;
#[cfg(test)]
mod server_pack_tests;
#[cfg(test)]
mod tests;

use anyhow::Result;

/// What `upload_modpack_check` reports.
#[derive(Debug)]
pub struct Check {
    pub summary: source::Summary,
    /// The draft after the pack and the update target filled what the caller left out.
    pub draft: draft::Draft,
    pub update: Option<api::Target>,
    pub problems: Vec<String>,
    /// `None` when problems kept it from running.
    pub preflight: Option<Result<(), String>>,
}

/// The caller's fields; empty ones are filled as the Kotlin page fills them.
#[derive(Clone, Debug, Default)]
pub struct Request {
    pub path: std::path::PathBuf,
    pub draft: draft::Draft,
    pub update_modpack_id: Option<String>,
}

/// `loadClientPack` up to the draft, then `submitUpload` up to the preflight, uploading nothing.
pub async fn check(api: &crate::api::Client, request: Request) -> Result<Check> {
    let update = match &request.update_modpack_id {
        Some(id) => Some(api::target(api, id).await?),
        None => None,
    };
    let path = request.path.clone();
    let summary = tokio::task::spawn_blocking(move || source::summary(&path))
        .await
        .map_err(|error| anyhow::anyhow!("读取整合包失败: {error}"))?
        .map_err(|error| anyhow::anyhow!("读取整合包失败：{error:#}"))?;
    let mut draft = request.draft;
    let fill = |value: &mut String, default: &str| {
        if value.trim().is_empty() {
            *value = default.to_owned();
        }
    };
    match &update {
        // An update keeps the pack's name and fields (`loadClientPack` with a target).
        Some(target) => {
            draft.name = target.name.clone();
            draft.categories = target.categories.clone();
            draft.icon_url = target.icon.clone();
            draft.info = target.info.clone();
        }
        None => fill(&mut draft.name, &summary.name),
    }
    fill(&mut draft.version, &summary.version);
    draft.version = draft::version_name(draft.version.trim());
    draft.name = draft.name.trim().to_owned();
    let (problems, preflight) = validate(api, &draft, &summary, update.as_ref()).await;
    Ok(Check { summary, draft, update, problems, preflight })
}

/// `validateModpackUploadDraft`, then, when nothing is wrong, the icon check of a new pack and
/// `POST modpack/preflight` (`submitUpload` up to the queue).
pub async fn validate(api: &crate::api::Client, draft: &draft::Draft, summary: &source::Summary, update: Option<&api::Target>) -> (Vec<String>, Option<Result<(), String>>) {
    let problems = draft::problems(draft, update.is_some());
    if !problems.is_empty() {
        return (problems, None);
    }
    let checked = async {
        if update.is_none() {
            draft::icon_is_image(&api.http, &draft.icon_url).await.map_err(|error| format!("验证图标链接失败：{error}"))?;
        }
        api::preflight(api, draft, &summary.mc_version, &summary.loader, update.map(|t| t.id.as_str()))
            .await
            .map_err(|error| format!("上传检查失败：{error:#}"))
    };
    (problems, Some(checked.await))
}

/// Form fields an edit changes; `None` keeps a field.
#[derive(Clone, Debug, Default)]
pub struct FormChange {
    pub name: Option<String>,
    pub version: Option<String>,
    pub categories: Option<Vec<String>>,
    pub icon_url: Option<String>,
    pub source_url: Option<String>,
    pub info: Option<String>,
}

impl FormChange {
    pub fn is_empty(&self) -> bool {
        self.name.is_none() && self.version.is_none() && self.categories.is_none() && self.icon_url.is_none() && self.source_url.is_none() && self.info.is_none()
    }

    /// `updateDraft` and `updateVersionName`. A new version keeps the pack's name, categories,
    /// icon and introduction, as the page shows them read only.
    pub fn apply(&self, form: &mut draft::Draft, update: bool) {
        if let Some(version) = &self.version {
            form.version = draft::version_name(version.trim());
        }
        if let Some(source) = &self.source_url {
            form.source_url = source.trim().to_owned();
        }
        if update {
            return;
        }
        if let Some(name) = &self.name {
            form.name = name.trim().to_owned();
        }
        if let Some(categories) = &self.categories {
            form.categories = categories.clone();
        }
        if let Some(icon) = &self.icon_url {
            form.icon_url = icon.trim().to_owned();
        }
        if let Some(info) = &self.info {
            form.info = info.trim().to_owned();
        }
    }
}
