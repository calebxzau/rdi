//! Server calls of 地图模板 (Kotlin `BaseWorldApi`) and the version binding
//! (`ModpackInfoMutation.UpdateVersionBaseWorld`). The envelope is decoded here, as in
//! `save_import::api`, because the part upload needs its `errorCode` to decide on a retry.
use super::BaseWorld;
use crate::api::server::ServerApi;
use crate::save_import::api::decode_envelope;
use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use std::future::Future;
use std::time::Duration;

/// `BaseWorld.CreateDto`.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateDto {
    pub name: String,
    pub level_type: String,
    pub generator_settings: Option<String>,
    pub size: u64,
    pub generated: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Deserialize)]
pub enum UploadStatus {
    #[default]
    Uploading,
    Queued,
    Processing,
    Ready,
    Failed,
}

/// `BaseWorldUploadSessionVo`.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionVo {
    pub id: String,
    pub size: u64,
    pub part_size: u64,
    pub part_count: u64,
    #[serde(default)]
    pub uploaded_parts: Vec<u64>,
    #[serde(default)]
    pub status: UploadStatus,
    #[serde(default)]
    pub error_message: Option<String>,
}

/// What the upload task needs from the network; tests use a fake.
pub trait WorldApi: Send + Sync + 'static {
    fn create(&self, dto: &CreateDto) -> impl Future<Output = Result<BaseWorld>> + Send;
    fn create_upload(&self, world: &str, size: u64, sha1: &str) -> impl Future<Output = Result<SessionVo>> + Send;
    fn upload_part(&self, world: &str, upload: &str, index: u64, bytes: Vec<u8>, sha1: String) -> impl Future<Output = Result<()>> + Send;
    fn complete(&self, world: &str, upload: &str) -> impl Future<Output = Result<SessionVo>> + Send;
    fn delete(&self, world: &str) -> impl Future<Output = Result<()>> + Send;
}

/// A part request has no total limit in Kotlin, only an idle limit; see `save_import::api`.
const PART_TIMEOUT: Duration = Duration::from_secs(600);

/// One URL path segment; UUIDs pass unchanged.
pub fn segment(value: &str) -> String {
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

#[derive(Clone)]
pub struct HttpWorldApi {
    server: ServerApi,
}

impl HttpWorldApi {
    pub fn new(server: ServerApi) -> Self {
        Self { server }
    }

    async fn call<T: DeserializeOwned>(
        &self,
        method: reqwest::Method,
        path: &str,
        json: Option<String>,
        configure: impl FnOnce(reqwest::RequestBuilder) -> reqwest::RequestBuilder,
    ) -> Result<Option<T>> {
        let jwt = self.server.jwt().filter(|j| !j.trim().is_empty()).context("必须登录后才能上传地图模板")?;
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
            anyhow::bail!("服务器暂时无法处理地图模板上传:HTTP{}", status.as_u16());
        }
        decode_envelope(&bytes)
    }

    async fn data<T: DeserializeOwned>(&self, method: reqwest::Method, path: &str, json: Option<String>) -> Result<T> {
        self.call(method, path, json, |r| r).await?.context("服务器响应缺少地图模板数据")
    }

    /// `list`: the player's own templates (`myOnly=true`).
    pub async fn list_mine(&self) -> Result<Vec<BaseWorld>> {
        self.data(reqwest::Method::GET, "/baseworld?myOnly=true", None).await
    }

    /// `listReady`: the released templates anyone may choose.
    pub async fn list_ready(&self) -> Result<Vec<BaseWorld>> {
        self.data(reqwest::Method::GET, "/baseworld?myOnly=false", None).await
    }

    pub async fn rename(&self, world: &str, name: &str) -> Result<BaseWorld> {
        let body = serde_json::json!({ "name": name }).to_string();
        self.data(reqwest::Method::PUT, &format!("/baseworld/{}", segment(world)), Some(body)).await
    }
}

impl WorldApi for HttpWorldApi {
    async fn create(&self, dto: &CreateDto) -> Result<BaseWorld> {
        self.data(reqwest::Method::POST, "/baseworld", Some(serde_json::to_string(dto)?)).await
    }
    async fn create_upload(&self, world: &str, size: u64, sha1: &str) -> Result<SessionVo> {
        let body = serde_json::json!({ "size": size, "sha1": sha1 }).to_string();
        self.data(reqwest::Method::POST, &format!("/baseworld/{}/upload", segment(world)), Some(body)).await
    }
    async fn upload_part(&self, world: &str, upload: &str, index: u64, bytes: Vec<u8>, sha1: String) -> Result<()> {
        let path = format!("/baseworld/{}/upload/{}/parts/{index}", segment(world), segment(upload));
        self.call::<serde_json::Value>(reqwest::Method::PUT, &path, None, |r| {
            r.header("X-Part-SHA1", sha1).header(reqwest::header::CONTENT_TYPE, "application/octet-stream").body(bytes).timeout(PART_TIMEOUT)
        })
        .await
        .map(|_| ())
    }
    async fn complete(&self, world: &str, upload: &str) -> Result<SessionVo> {
        self.data(reqwest::Method::POST, &format!("/baseworld/{}/upload/{}/complete", segment(world), segment(upload)), None).await
    }
    async fn delete(&self, world: &str) -> Result<()> {
        self.call::<serde_json::Value>(reqwest::Method::DELETE, &format!("/baseworld/{}", segment(world)), None, |r| r).await.map(|_| ())
    }
}

/// `Modpack.Version.BaseWorldBinding`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Binding {
    pub id: String,
    #[serde(default)]
    pub required: bool,
}

/// `PUT modpack/{id}/version/{name}/baseworld` with `VersionBaseWorldUpdateDto`; `None` clears it.
pub async fn set_version_binding(api: &crate::api::Client, modpack_id: &str, version: &str, binding: Option<&Binding>) -> Result<()> {
    let path = format!("/modpack/{}/version/{}/baseworld", segment(modpack_id), crate::pack_install::encode_segment(version));
    let body = serde_json::json!({ "baseWorld": binding });
    api.server().put::<serde_json::Value>(&path, Some(&body)).await.map(|_| ())
}
