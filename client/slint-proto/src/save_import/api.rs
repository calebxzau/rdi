//! Server calls of the save import, Kotlin `HostSaveImportApi`, and the DTOs of
//! `common/model` `HostWorldImport.kt`. The envelope is decoded here rather than by
//! `ServerApi`, because the part upload needs the envelope's `errorCode` to decide on a retry.
use crate::api::server::ServerApi;
use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use std::collections::HashSet;
use std::future::Future;
use std::time::Duration;
use uuid::Uuid;

/// `HostWorldImportPrecheckDto`.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PrecheckDto {
    /// `Data.Version.Name` of the save, such as `1.20.1`.
    pub mc_version_name: String,
    /// `forge`, `neoforge` or `fabric`, as `ModLoader` serializes.
    pub loader: String,
    /// ObjectIds of the accounts that will become members.
    pub member_player_ids: Vec<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PrecheckVo {
    #[serde(default)]
    pub member_warnings: Vec<MemberWarning>,
    /// Game rules set on the host that the import clears in favour of the save's own.
    #[serde(default)]
    pub game_rule_override_count: i32,
}

/// A player who cannot be added as a member; the import still succeeds without them.
#[derive(Clone, Debug, PartialEq, Eq, Hash, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MemberWarning {
    pub player_id: String,
    pub reason: String,
}

/// `HostWorldImportCreateDto`.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateDto {
    pub size: u64,
    pub sha1: String,
    pub mc_version_name: String,
    pub loader: String,
    pub member_player_ids: Vec<String>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Deserialize)]
pub enum ImportStatus {
    #[default]
    Uploading,
    Queued,
    Processing,
    Ready,
    Failed,
}

/// `HostWorldImportSessionVo`.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionVo {
    pub id: String,
    pub size: u64,
    pub part_size: u64,
    pub part_count: u64,
    #[serde(default)]
    pub uploaded_parts: Vec<u64>,
    // The DTO's remaining fields; the import does not use them yet.
    #[serde(default)]
    #[allow(dead_code)]
    pub expires_at: i64,
    #[serde(default)]
    pub status: ImportStatus,
    #[serde(default)]
    pub error_message: Option<String>,
    #[serde(default)]
    #[allow(dead_code)]
    pub member_warnings: Vec<MemberWarning>,
}

/// A save player UUID that is the Microsoft profile bound to an RDI account.
#[derive(Clone, Debug, PartialEq, Deserialize)]
pub struct MsidMatchVo {
    pub msid: String,
    /// The account's ObjectId.
    pub id: String,
    pub name: String,
}

/// `RAccount.Dto`, the fields the import uses.
#[derive(Clone, Debug, PartialEq, Deserialize)]
pub struct AccountDto {
    pub id: String,
    pub name: String,
}

/// The server answered with an error envelope (Kotlin `RequestError` with its `errorCode`).
#[derive(Debug)]
pub struct Rejected {
    pub message: String,
    pub error_code: Option<String>,
}
impl std::fmt::Display for Rejected {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}
impl std::error::Error for Rejected {}

/// What the import needs from the network; tests use a fake.
pub trait ImportApi: Send + Sync + 'static {
    fn precheck(&self, host: &str, dto: &PrecheckDto) -> impl Future<Output = Result<PrecheckVo>> + Send;
    fn create(&self, host: &str, dto: &CreateDto) -> impl Future<Output = Result<SessionVo>> + Send;
    fn status(&self, host: &str, import_id: &str) -> impl Future<Output = Result<SessionVo>> + Send;
    fn upload_part(&self, host: &str, import_id: &str, index: u64, bytes: Vec<u8>, sha1: String) -> impl Future<Output = Result<()>> + Send;
    fn complete(&self, host: &str, import_id: &str) -> impl Future<Output = Result<SessionVo>> + Send;
    fn cancel(&self, host: &str, import_id: &str) -> impl Future<Output = Result<()>> + Send;
    /// The account bound to `qq`, or a `Rejected("无此账号")`.
    fn find_by_qq(&self, qq: &str) -> impl Future<Output = Result<AccountDto>> + Send;
    /// Accounts whose bound Microsoft profile is one of `uuids`.
    fn msid_match(&self, uuids: &[Uuid]) -> impl Future<Output = Result<Vec<MsidMatchVo>>> + Send;
    /// Which of the ObjectIds `ids` are existing accounts.
    fn existing_accounts(&self, ids: &[String]) -> impl Future<Output = Result<HashSet<String>>> + Send;
    /// The current Mojang name of an online player (Kotlin `MojangApi.getProfile`).
    fn mojang_name(&self, uuid: Uuid) -> impl Future<Output = Result<String>> + Send;
}

/// A part request has no total limit in Kotlin, only a 60s idle limit. reqwest has no idle limit
/// for request bodies, so a part (at most 4MiB) gets a generous total limit instead.
const PART_TIMEOUT: Duration = Duration::from_secs(600);
const MOJANG_PROFILE: &str = "https://sessionserver.mojang.com/session/minecraft/profile/";

pub struct HttpImportApi {
    server: ServerApi,
    mojang_base: String,
}

impl HttpImportApi {
    pub fn new(server: ServerApi) -> Self {
        Self { server, mojang_base: MOJANG_PROFILE.into() }
    }

    async fn call<T: DeserializeOwned>(&self, method: reqwest::Method, path: &str, json: Option<String>, configure: impl FnOnce(reqwest::RequestBuilder) -> reqwest::RequestBuilder) -> Result<Option<T>> {
        let jwt = self.server.jwt().filter(|j| !j.trim().is_empty()).context("必须登录后才能导入存档")?;
        let mut request = self.server.http.request(method, self.server.url(path)).bearer_auth(jwt.trim());
        if let Some(json) = json {
            request = request.header(reqwest::header::CONTENT_TYPE, "application/json").body(json);
        }
        let response = configure(request).send().await.map_err(|error| {
            tracing::warn!(error = %error.without_url(), "服务器连接失败");
            anyhow!("无法连接服务器 请检查网络连接")
        })?;
        let status = response.status();
        tracing::info!(path = path.split('?').next().unwrap_or(path), status = status.as_u16(), "服务器请求");
        let bytes = response.bytes().await.map_err(|_| anyhow!("无法连接服务器 请检查网络连接"))?;
        if status.as_u16() >= 500 {
            anyhow::bail!("服务器暂时无法处理存档导入:HTTP{}", status.as_u16());
        }
        decode_envelope(&bytes)
    }

    async fn data<T: DeserializeOwned>(&self, method: reqwest::Method, path: &str, json: Option<String>) -> Result<T> {
        self.call(method, path, json, |r| r).await?.context("服务器响应缺少数据")
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Envelope<T> {
    code: i64,
    #[serde(default)]
    msg: String,
    data: Option<T>,
    #[serde(default)]
    error_code: Option<String>,
}

/// Kotlin `rdiResponse`: a non-zero code is a `Rejected` with the server's message and code.
pub fn decode_envelope<T: DeserializeOwned>(bytes: &[u8]) -> Result<Option<T>> {
    let envelope: Envelope<serde_json::Value> = serde_json::from_slice(bytes).map_err(|_| anyhow!("服务器数据格式不兼容"))?;
    if envelope.code != 0 {
        return Err(Rejected { message: envelope.msg, error_code: envelope.error_code }.into());
    }
    match envelope.data.filter(|d| !d.is_null()) {
        Some(data) => Ok(Some(serde_json::from_value(data).map_err(|_| anyhow!("服务器数据格式不兼容"))?)),
        None => Ok(None),
    }
}

/// One URL path segment; ObjectIds and UUIDs pass unchanged.
fn segment(value: &str) -> String {
    let mut out = String::new();
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~".contains(&byte) {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

impl ImportApi for HttpImportApi {
    async fn precheck(&self, host: &str, dto: &PrecheckDto) -> Result<PrecheckVo> {
        self.data(reqwest::Method::POST, &format!("/host/{}/world-import/precheck", segment(host)), Some(serde_json::to_string(dto)?)).await
    }
    async fn create(&self, host: &str, dto: &CreateDto) -> Result<SessionVo> {
        self.data(reqwest::Method::POST, &format!("/host/{}/world-import", segment(host)), Some(serde_json::to_string(dto)?)).await
    }
    async fn status(&self, host: &str, import_id: &str) -> Result<SessionVo> {
        self.data(reqwest::Method::GET, &format!("/host/{}/world-import/{}", segment(host), segment(import_id)), None).await
    }
    async fn upload_part(&self, host: &str, import_id: &str, index: u64, bytes: Vec<u8>, sha1: String) -> Result<()> {
        let path = format!("/host/{}/world-import/{}/parts/{index}", segment(host), segment(import_id));
        self.call::<serde_json::Value>(reqwest::Method::PUT, &path, None, |r| {
            r.header("X-Part-SHA1", sha1).header(reqwest::header::CONTENT_TYPE, "application/octet-stream").body(bytes).timeout(PART_TIMEOUT)
        })
        .await
        .map(|_| ())
    }
    async fn complete(&self, host: &str, import_id: &str) -> Result<SessionVo> {
        self.data(reqwest::Method::POST, &format!("/host/{}/world-import/{}/complete", segment(host), segment(import_id)), None).await
    }
    async fn cancel(&self, host: &str, import_id: &str) -> Result<()> {
        let path = format!("/host/{}/world-import/{}", segment(host), segment(import_id));
        self.call::<serde_json::Value>(reqwest::Method::DELETE, &path, None, |r| r).await.map(|_| ())
    }
    async fn find_by_qq(&self, qq: &str) -> Result<AccountDto> {
        self.data(reqwest::Method::GET, &format!("/player/by-qq/{}", segment(qq)), None).await
    }
    async fn msid_match(&self, uuids: &[Uuid]) -> Result<Vec<MsidMatchVo>> {
        if uuids.is_empty() {
            return Ok(vec![]);
        }
        let body: Vec<String> = uuids.iter().map(Uuid::to_string).collect();
        self.data(reqwest::Method::POST, "/player/msid-match", Some(serde_json::to_string(&body)?)).await
    }
    async fn existing_accounts(&self, ids: &[String]) -> Result<HashSet<String>> {
        if ids.is_empty() {
            return Ok(HashSet::new());
        }
        let joined = ids.join("\n");
        let infos: Vec<AccountDto> = self
            .call(reqwest::Method::GET, "/player/infos", None, |r| r.query(&[("ids", joined)]))
            .await?
            .context("服务器响应缺少数据")?;
        let wanted: HashSet<&str> = ids.iter().map(String::as_str).collect();
        Ok(infos.into_iter().map(|a| a.id).filter(|id| wanted.contains(id.as_str())).collect())
    }
    async fn mojang_name(&self, uuid: Uuid) -> Result<String> {
        #[derive(Deserialize)]
        struct Profile {
            name: String,
        }
        let url = format!("{}{}", self.mojang_base, uuid.simple());
        let response = self.server.http.get(url).send().await.map_err(|e| anyhow!("查询正版玩家名失败: {}", e.without_url()))?;
        anyhow::ensure!(response.status().is_success(), "查询正版玩家名失败: HTTP{}", response.status().as_u16());
        Ok(response.json::<Profile>().await.map_err(|_| anyhow!("正版玩家信息格式无效"))?.name)
    }
}
