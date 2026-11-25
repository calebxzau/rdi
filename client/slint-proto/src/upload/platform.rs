//! Raw Modrinth and CurseForge requests of the upload, on the catalog transport (mirror race,
//! API key, retries). The Kotlin upload reads platform fields the normalized `catalog` models
//! leave out (CurseForge `gameVersions` and `classId`, Modrinth `client_side`), so these are the
//! ports of `ModrinthService.getVersionsFromHashes` / `getMultipleProjects` and
//! `CurseForgeService.matchFingerprintData` / `getModsInfo` / `getModsInfoIncludingNonMods` /
//! `getModFilesInfo`.
use crate::catalog::models::Platform;
use crate::catalog::transport::Transport;
use crate::pack_install::mods::Side;
use anyhow::{Context, Result};
use reqwest::Method;
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::BTreeMap;
use std::sync::Arc;

#[derive(Clone, Debug, Deserialize)]
pub struct MrFile {
    pub url: String,
    #[serde(default)]
    pub hashes: BTreeMap<String, String>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct MrVersion {
    pub id: String,
    pub project_id: String,
    #[serde(default)]
    pub files: Vec<MrFile>,
}

impl MrVersion {
    /// `versionContainsSha1`.
    pub fn has_sha1(&self, sha1: &str) -> bool {
        self.files.iter().any(|f| f.hashes.iter().any(|(k, v)| k.eq_ignore_ascii_case("sha1") && v.eq_ignore_ascii_case(sha1)))
    }
}

#[derive(Clone, Debug, Deserialize)]
pub struct MrProject {
    pub id: String,
    pub slug: String,
    #[serde(default)]
    pub client_side: Option<String>,
    #[serde(default)]
    pub server_side: Option<String>,
}

impl MrProject {
    /// `ModrinthProject.toModSide`.
    pub fn side(&self) -> Side {
        if self.server_side.as_deref() == Some("unsupported") {
            Side::Client
        } else if self.client_side.as_deref() == Some("unsupported") {
            Side::Server
        } else {
            Side::Both
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
pub struct CfHash {
    #[serde(default)]
    pub value: Option<String>,
    #[serde(default)]
    pub algo: Option<i64>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CfFile {
    #[serde(default)]
    pub id: i64,
    #[serde(default)]
    pub mod_id: i64,
    #[serde(default)]
    pub file_name: Option<String>,
    #[serde(default)]
    pub hashes: Vec<CfHash>,
    #[serde(default)]
    pub download_url: Option<String>,
    #[serde(default)]
    pub game_versions: Vec<String>,
    pub file_fingerprint: i64,
}

impl CfFile {
    /// The SHA-1 (`algo == 1`), trimmed and lower case; empty when the file lists none.
    pub fn sha1(&self) -> String {
        self.hashes.iter().find(|h| h.algo == Some(1)).and_then(|h| h.value.as_deref()).map(|v| v.trim().to_lowercase()).unwrap_or_default()
    }
    /// `List<String>.toCurseForgeModSide` of `gameVersions`.
    pub fn side(&self) -> Option<Side> {
        let has = |wanted: &str| self.game_versions.iter().any(|v| v.trim().eq_ignore_ascii_case(wanted));
        match (has("Client"), has("Server")) {
            (true, true) => Some(Side::Both),
            (true, false) => Some(Side::Client),
            (false, true) => Some(Side::Server),
            (false, false) => None,
        }
    }
    /// `CurseForgeFile.realDownloadUrl`: the given address, else the CDN path built from the id
    /// (first four digits, then the rest as a number) and the form-encoded file name.
    pub fn real_download_url(&self) -> Result<String> {
        if let Some(url) = &self.download_url {
            return Ok(url.clone());
        }
        let id = self.id.to_string();
        let parts = id.get(..4).zip(id.get(4..)).and_then(|(a, b)| Some((a.parse::<i64>().ok()?, b.parse::<i64>().ok()?)));
        let (a, b) = parts.with_context(|| format!("CurseForge文件编号无效: {id}"))?;
        // Kotlin formats a missing name as the text `null`.
        let name = self.file_name.as_deref().map(form_encode).unwrap_or_else(|| "null".into());
        Ok(format!("https://mediafilez.forgecdn.net/files/{a}/{b}/{name}"))
    }
}

/// Java `URLEncoder.encode(text, UTF_8)`: letters, digits and `.-*_` stay, a space is `+`.
fn form_encode(text: &str) -> String {
    let mut out = String::new();
    for b in text.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => out.push(b as char),
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CfMod {
    pub id: i64,
    #[serde(default)]
    pub name: Option<String>,
    pub slug: String,
    #[serde(default)]
    pub class_id: Option<i64>,
}

impl CfMod {
    pub const CLASS_MOD: i64 = 6;
    pub const CLASS_RESOURCE_PACK: i64 = 12;
    pub const CLASS_SHADER_PACK: i64 = 6552;
}

#[derive(Clone, Debug, Deserialize)]
pub struct CfMatch {
    /// The project id.
    #[serde(default)]
    pub id: i64,
    pub file: CfFile,
}

fn parse<T: serde::de::DeserializeOwned>(value: Value, what: &str) -> Result<T> {
    serde_json::from_value(value).with_context(|| format!("{what}返回的数据无法解析"))
}

#[derive(Clone)]
pub struct Platforms {
    transport: Arc<dyn Transport>,
}

impl Platforms {
    pub fn new(transport: Arc<dyn Transport>) -> Self {
        Self { transport }
    }

    async fn post(&self, platform: Platform, path: &str, body: Value, official: bool) -> Result<Value> {
        if official {
            self.transport.request_official(platform, Method::POST, path, vec![], Some(body)).await
        } else {
            self.transport.request(platform, Method::POST, path, vec![], Some(body)).await
        }
    }

    /// `getVersionsFromHashes`: the Modrinth version of each SHA-1, keyed as the platform answers.
    pub async fn mr_versions(&self, sha1: &[String]) -> Result<BTreeMap<String, MrVersion>> {
        if sha1.is_empty() {
            return Ok(BTreeMap::new());
        }
        let found: BTreeMap<String, MrVersion> =
            parse(self.post(Platform::Modrinth, "version_files", json!({"hashes": sha1, "algorithm": "sha1"}), false).await?, "Modrinth")?;
        tracing::info!(matched = found.len(), asked = sha1.len(), "Modrinth文件匹配完成");
        Ok(found)
    }

    /// `getMultipleProjects` by id or slug. Kotlin sends one URL; a long list is split here the
    /// way the catalog splits it, which changes no result.
    pub async fn mr_projects(&self, ids: &[String]) -> Result<Vec<MrProject>> {
        let mut distinct: Vec<String> = Vec::new();
        for id in ids {
            if !distinct.contains(id) {
                distinct.push(id.clone());
            }
        }
        let mut projects = Vec::new();
        for batch in crate::catalog::url_batches(&distinct) {
            let query = vec![("ids".to_owned(), serde_json::to_string(batch)?)];
            let found: Vec<MrProject> = parse(self.transport.request(Platform::Modrinth, Method::GET, "projects", query, None).await?, "Modrinth")?;
            projects.extend(found);
        }
        tracing::info!(found = projects.len(), asked = distinct.len(), "Modrinth项目读取完成");
        Ok(projects)
    }

    /// `matchFingerprintData`: the exact matches of Murmur2 fingerprints (game 432, Minecraft).
    pub async fn cf_fingerprints(&self, fingerprints: &[u32]) -> Result<Vec<CfMatch>> {
        let response = self.post(Platform::CurseForge, "fingerprints/432", json!({"fingerprints": fingerprints}), false).await?;
        parse(response["data"]["exactMatches"].clone(), "CurseForge")
    }

    async fn request_mods(&self, ids: &[i64], official: bool, mods_only: bool) -> Result<Vec<CfMod>> {
        let response = self.post(Platform::CurseForge, "mods", json!({"modIds": ids, "filterPcOnly": mods_only}), official).await?;
        let mods: Vec<CfMod> = parse(response["data"].clone(), "CurseForge")?;
        Ok(if mods_only { mods.into_iter().filter(|m| m.class_id == Some(CfMod::CLASS_MOD)).collect() } else { mods })
    }

    /// `getModsInfo` (`mods_only`) and `getModsInfoIncludingNonMods`: ids the first answer left
    /// out are asked again from the official API.
    pub async fn cf_mods(&self, ids: &[i64], mods_only: bool) -> Result<Vec<CfMod>> {
        if ids.is_empty() {
            return Ok(vec![]);
        }
        let mut asked: Vec<i64> = ids.to_vec();
        if !mods_only {
            let mut distinct = Vec::new();
            for id in asked {
                if !distinct.contains(&id) {
                    distinct.push(id);
                }
            }
            asked = distinct;
        }
        let mut mods = self.request_mods(&asked, false, mods_only).await?;
        let missing: Vec<i64> = asked.iter().copied().filter(|id| !mods.iter().any(|m| m.id == *id)).collect();
        if !missing.is_empty() {
            tracing::warn!(?missing, "CurseForge项目未找到，改问源站");
            mods.extend(self.request_mods(&missing, true, mods_only).await?);
        }
        Ok(mods)
    }

    async fn request_files(&self, ids: &[i64], official: bool) -> Result<Vec<CfFile>> {
        let response = self.post(Platform::CurseForge, "mods/files", json!({"fileIds": ids}), official).await?;
        parse(response["data"].clone(), "CurseForge")
    }

    /// `getModFilesInfo`, with the same second request for missing ids.
    pub async fn cf_files(&self, ids: &[i64]) -> Result<Vec<CfFile>> {
        if ids.is_empty() {
            return Ok(vec![]);
        }
        let mut files = self.request_files(ids, false).await?;
        let missing: Vec<i64> = ids.iter().copied().filter(|id| !files.iter().any(|f| f.id == *id)).collect();
        if !missing.is_empty() {
            tracing::warn!(?missing, "CurseForge文件未找到，改问源站");
            files.extend(self.request_files(&missing, true).await?);
        }
        Ok(files)
    }
}

#[cfg(test)]
pub(super) fn form_encode_for_test(text: &str) -> String {
    form_encode(text)
}
