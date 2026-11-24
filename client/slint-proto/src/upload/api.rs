//! The server calls of the upload page before any file is sent: `loadUploadedModpacks`,
//! `loadUpdateTarget` and `preflightUpload` (`RdiModpackUploadGateway`).
use super::draft::{Draft, normalize_categories};
use crate::api::Client;
use anyhow::{Context, Result, bail};
use serde::Deserialize;
use serde_json::{Value, json};

/// A pack the player may upload a version to.
#[derive(Clone, Debug, PartialEq)]
pub struct Uploadable {
    pub id: String,
    pub name: String,
    /// Newest first.
    pub versions: Vec<String>,
    pub categories: Vec<String>,
}

#[derive(Deserialize)]
struct PackVersion {
    #[serde(default)]
    time: i64,
    name: String,
}

#[derive(Deserialize)]
struct Pack {
    #[serde(rename = "_id")]
    id: String,
    name: String,
    #[serde(default)]
    versions: Vec<PackVersion>,
    #[serde(default)]
    categories: Vec<String>,
}

/// `GET modpack/uploadable`, the pack with the newest version first.
pub async fn uploadable(api: &Client) -> Result<Vec<Uploadable>> {
    let mut packs: Vec<Pack> = api.get("/modpack/uploadable").await.context("读取可上传整合包失败")?;
    let newest = |p: &Pack| p.versions.iter().map(|v| v.time).max().unwrap_or(0);
    packs.sort_by_key(|p| std::cmp::Reverse(newest(p)));
    Ok(packs
        .into_iter()
        .map(|mut p| {
            p.versions.sort_by_key(|v| std::cmp::Reverse(v.time));
            Uploadable { id: p.id, name: p.name, versions: p.versions.into_iter().map(|v| v.name).collect(), categories: p.categories }
        })
        .collect())
}

/// The pack a new version goes to (`loadUpdateTarget`).
#[derive(Clone, Debug, PartialEq)]
pub struct Target {
    pub id: String,
    pub name: String,
    pub categories: Vec<String>,
    pub icon: String,
    pub info: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Detail {
    name: String,
    #[serde(default)]
    icon: Option<String>,
    #[serde(default)]
    info: Option<String>,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    can_upload_version: bool,
}

/// Pack ids are ObjectIds; anything else never reaches a server path.
pub fn check_pack_id(id: &str) -> Result<()> {
    if id.len() != 24 || !id.bytes().all(|b| b.is_ascii_hexdigit()) {
        bail!("整合包编号无效：{id}");
    }
    Ok(())
}

/// `GET modpack/{id}/detail`; only a pack the player may add versions to.
pub async fn target(api: &Client, id: &str) -> Result<Target> {
    check_pack_id(id)?;
    let detail: Detail = match api.get(&format!("/modpack/{id}/detail")).await {
        Ok(detail) => detail,
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "上传页面更新目标加载失败");
            bail!("无法加载要更新的整合包，请返回后重试");
        }
    };
    if !detail.can_upload_version {
        bail!("没有上传新版本的权限");
    }
    Ok(Target {
        id: id.to_ascii_lowercase(),
        name: detail.name,
        categories: detail.categories,
        icon: detail.icon.unwrap_or_default(),
        info: detail.info.unwrap_or_default(),
    })
}

fn blank_to_null(text: &str) -> Value {
    let text = text.trim();
    if text.is_empty() { Value::Null } else { Value::String(text.to_owned()) }
}

/// `ModpackUploadPreflightDto`.
pub fn preflight_body(draft: &Draft, mc_version: &str, loader: &str, update: Option<&str>) -> Value {
    json!({
        "modpackId": update,
        "name": draft.name,
        "verName": draft.version,
        "mcVer": mc_version,
        "modLoader": loader,
        "iconUrl": blank_to_null(&draft.icon_url),
        "sourceUrl": blank_to_null(&draft.source_url),
        "info": blank_to_null(&draft.info),
        "categories": normalize_categories(&draft.categories),
    })
}

/// `POST modpack/preflight`: the server's own checks of the draft, uploading nothing.
pub async fn preflight(api: &Client, draft: &Draft, mc_version: &str, loader: &str, update: Option<&str>) -> Result<()> {
    let _: Value = api.post("/modpack/preflight", &preflight_body(draft, mc_version, loader, update)).await?;
    Ok(())
}
