//! M4 of `LAUNCHER-MCP-PLAN.md`: modpack upload in Rust, the port of Kotlin
//! `ModpackUploadViewModel`, `ModpackProcessor`, `ModpackUploadService` and `ModpackTestSession`,
//! in the stages of the plan's "M4 design". No Slint types: the launcher MCP is its only caller
//! (the Slint launcher has no upload screen by owner decision).
//!
//! Stage A1: the draft rules, the first look at a pack (`source`) and the server checks before
//! upload (`api`).
pub mod api;
pub mod draft;
// A2 parts 1, 2a and 2b: the pack reader and its pieces; the draft (stage A5) wires them.
#[allow(dead_code)]
pub mod jar_meta;
#[allow(dead_code)]
pub mod manifest;
#[allow(dead_code)]
pub mod mods;
#[allow(dead_code)]
pub mod platform;
#[allow(dead_code)]
pub mod policy;
#[allow(dead_code)]
pub mod prepare;
#[allow(dead_code)]
pub mod reader;
#[allow(dead_code)]
pub mod server_pack;
pub mod source;
#[cfg(test)]
mod manifest_tests;
#[cfg(test)]
mod prepare_tests;
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
    let problems = draft::problems(&draft, update.is_some());
    let preflight = if !problems.is_empty() {
        None
    } else {
        let checked = async {
            if update.is_none() {
                draft::icon_is_image(&api.http, &draft.icon_url).await.map_err(|error| format!("验证图标链接失败：{error}"))?;
            }
            api::preflight(api, &draft, &summary.mc_version, &summary.loader, update.as_ref().map(|t| t.id.as_str()))
                .await
                .map_err(|error| format!("上传检查失败：{error:#}"))
        };
        Some(checked.await)
    };
    Ok(Check { summary, draft, update, problems, preflight })
}
