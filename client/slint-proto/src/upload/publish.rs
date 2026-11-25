//! Stage A5b: sending the built archive and publishing it. Kotlin `ModpackUploadApi` (the HTTP
//! calls), `ModpackChunkedUploader.upload` (session, missing parts, complete, publish, session
//! cancelled on a failure before publishing), the publish halves of `uploadNewModpack` and
//! `uploadNewVersion`, `uploadSummary` and `resolveUploadedModpackId`. The parts go through
//! `save_import::upload`, the port of Kotlin `ChunkedUploader` with the same retry constants.
//! Blocking, like the other upload stages: the launcher task calls it.
use super::archive::human_size;
use crate::api::server::ServerApi;
use crate::download::cancellable;
use crate::pack_install::extras::Content;
use crate::pack_install::mods::Mod;
use crate::save_import::api::decode_envelope;
use crate::save_import::upload;
use crate::tasks::Cancellation;
use anyhow::{Context as _, Result, anyhow, bail, ensure};
use serde::Deserialize;
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use std::future::Future;
use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// `PUBLISH_TIMEOUT_MILLIS`, also for `complete`.
const PUBLISH_TIMEOUT: Duration = Duration::from_secs(60 * 60);
/// `CANCEL_TIMEOUT_MILLIS`.
const CANCEL_TIMEOUT: Duration = Duration::from_secs(10);
/// Kotlin parts have no total limit, only a 60s idle limit; reqwest has no idle limit for
/// bodies, so a part (at most 4MiB) gets a generous total limit, as the save import does.
const PART_TIMEOUT: Duration = Duration::from_secs(600);
pub const UNCONFIRMED: &str = "发布结果尚未确认，请刷新整合包列表查看，勿重复提交";

/// `ModpackUploadSessionVo`.
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
    pub ready: bool,
    #[serde(default = "eight")]
    pub max_parallel_parts: u64,
}

fn eight() -> u64 {
    8
}

/// A pack of `GET /modpack/my`, the fields `resolveUploadedModpackId` reads.
#[derive(Clone, Debug, Deserialize)]
pub struct MyPack {
    #[serde(rename = "_id")]
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub versions: Vec<MyVersion>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct MyVersion {
    pub name: String,
    #[serde(default)]
    pub time: i64,
}

/// The connection failed or timed out: Kotlin's `IOException` and `HttpRequestTimeoutException`,
/// after which a publish may or may not have happened.
#[derive(Debug)]
pub struct Transport(pub String);
impl std::fmt::Display for Transport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for Transport {}

/// What the upload needs from the network; tests use a fake.
pub trait PublishApi: Send + Sync + 'static {
    fn create_session(&self, file_name: &str, size: u64, sha1: &str) -> impl Future<Output = Result<SessionVo>> + Send;
    fn upload_part(&self, upload_id: &str, index: u64, bytes: Vec<u8>, sha1: String) -> impl Future<Output = Result<()>> + Send;
    fn complete(&self, upload_id: &str) -> impl Future<Output = Result<SessionVo>> + Send;
    fn cancel(&self, upload_id: &str) -> impl Future<Output = Result<()>> + Send;
    /// `POST /modpack/from-upload` or `/modpack/{id}/version/{name}/from-upload`.
    fn publish(&self, target: &Target, body: &Value) -> impl Future<Output = Result<()>> + Send;
    fn list_my(&self) -> impl Future<Output = Result<Vec<MyPack>>> + Send;
}

/// Where the upload goes.
#[derive(Clone, Debug, PartialEq)]
pub enum Target {
    New,
    Version { modpack_id: String, version: String },
}

/// `Modpack.CreateWithVersionDto` without its mods.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct NewPack {
    pub name: String,
    pub version: String,
    /// `McVersion` name, `V201` or `V211`.
    pub mc_version: String,
    /// `ModLoader` name.
    pub loader: String,
    pub icon_url: String,
    pub source_url: String,
    pub info: String,
    pub categories: Vec<String>,
}

fn blank_to_null(text: &str) -> Value {
    let text = text.trim();
    if text.is_empty() { Value::Null } else { Value::String(text.to_owned()) }
}

/// `ModpackCreateFromUploadDto`.
pub fn new_pack_body(upload_id: &str, pack: &NewPack, mods: &[Mod], extras: &[Content]) -> Value {
    json!({
        "uploadId": upload_id,
        "modpack": {
            "name": pack.name,
            "mcVer": pack.mc_version,
            "modLoader": pack.loader,
            "verName": pack.version,
            "iconUrl": blank_to_null(&pack.icon_url),
            "sourceUrl": blank_to_null(&pack.source_url),
            "info": blank_to_null(&pack.info),
            "categories": super::draft::normalize_categories(&pack.categories),
            "mods": mods,
            "clientExtras": extras,
        }
    })
}

/// `ModpackVersionCreateFromUploadDto`.
pub fn version_body(upload_id: &str, mods: &[Mod], extras: &[Content]) -> Value {
    json!({"uploadId": upload_id, "mods": mods, "clientExtras": extras})
}

/// `Double.humanSpeed`.
pub fn human_speed(bytes_per_second: f64) -> String {
    if bytes_per_second < 1024.0 {
        return format!("{bytes_per_second:.0}B/s");
    }
    let mut value = bytes_per_second / 1024.0;
    for unit in ["KB/s", "MB/s"] {
        if value < 1024.0 {
            return format!("{value:.1}{unit}");
        }
        value /= 1024.0;
    }
    format!("{value:.1}GB/s")
}

/// `ModpackChunkedUploader.toTask2Progress`: the message and fraction of the task row.
pub fn progress_message(name: &str, done: u64, total: u64, bytes_per_second: f64) -> (String, f32) {
    let percent = (done * 100).checked_div(total).map_or(100, |p| p.min(100));
    let message = format!(
        "正在上传整合包 {name}...\n进度：{percent}% ({}/{})\n速度：{}\n",
        human_size(done),
        human_size(total),
        human_speed(bytes_per_second)
    );
    (message, if total == 0 { 1.0 } else { done as f32 / total as f32 })
}

/// `uploadSummary`.
pub fn summary(total: u64, elapsed: Duration) -> String {
    let seconds = elapsed.as_secs_f64();
    let speed = if seconds <= 0.0 { 0.0 } else { total as f64 / seconds };
    format!("文件大小: {}\n平均速度: {}\n耗时: {seconds:.1}秒\n上传完成，正在继续本地安装\n", human_size(total), human_speed(speed))
}

pub struct Run<'a> {
    pub runtime: &'a tokio::runtime::Handle,
    pub cancel: &'a Cancellation,
    pub options: upload::Options,
    /// The task row: message and fraction.
    pub progress: &'a (dyn Fn(String, Option<f32>) + Sync),
    /// The pack name or `版本 {version}`, as Kotlin labels the progress.
    pub label: String,
}

/// `ModpackChunkedUploader.upload` with the publish of `body(upload_id)`; returns `uploadSummary`.
pub fn send<A: PublishApi>(run: &Run, api: &Arc<A>, file: &Path, target: &Target, body: impl FnOnce(&str) -> Value) -> Result<String> {
    let started = Instant::now();
    let meta = std::fs::symlink_metadata(file).ok().filter(|m| m.is_file() && m.len() > 0).context("上传文件不存在或为空")?;
    let total = meta.len();
    let sha1 = crate::digests::hash(file, crate::digests::Algorithm::Sha1, run.cancel)?;
    let name = file.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let session = run.runtime.block_on(cancellable(run.cancel, api.create_session(&name, total, &sha1)))?;
    let mut publishing = false;
    let result = (|| -> Result<()> {
        run.cancel.check()?;
        let completed = if session.ready {
            let (message, fraction) = progress_message(&run.label, total, total, 0.0);
            (run.progress)(message, Some(fraction));
            session.clone()
        } else {
            ensure!(session.max_parallel_parts > 0, "服务器返回的分片并发数无效");
            parts(run, api, file, &session, total)?;
            run.cancel.check()?;
            let completed = run.runtime.block_on(cancellable(run.cancel, with_timeout(PUBLISH_TIMEOUT, api.complete(&session.id))))?;
            ensure!(completed.ready, "服务器未确认整合包上传完成");
            completed
        };
        run.cancel.check()?;
        publishing = true;
        let body = body(&completed.id);
        match run.runtime.block_on(cancellable(run.cancel, with_timeout(PUBLISH_TIMEOUT, api.publish(target, &body)))) {
            Ok(()) => Ok(()),
            Err(error) if run.cancel.cancelled() => {
                (run.progress)(UNCONFIRMED.into(), None);
                Err(error)
            }
            Err(error) if error.downcast_ref::<Transport>().is_some() => Err(error.context(UNCONFIRMED)),
            Err(error) => Err(error),
        }
    })();
    if let Err(error) = &result
        && !publishing
    {
        // Not cancellable: the session must go even when the task was cancelled.
        if let Err(cleanup) = run.runtime.block_on(with_timeout(CANCEL_TIMEOUT, api.cancel(&session.id))) {
            tracing::error!(error = %format!("{cleanup:#}"), cause = %format!("{error:#}"), "清理整合包上传会话失败");
        }
    }
    result.map(|()| summary(total, started.elapsed()))
}

async fn with_timeout<T>(limit: Duration, future: impl Future<Output = Result<T>>) -> Result<T> {
    tokio::time::timeout(limit, future).await.map_err(|_| anyhow::Error::new(Transport("服务器响应超时".into())))?
}

/// The parts the session still misses, with Kotlin's progress (the speed counts this run only).
fn parts<A: PublishApi>(run: &Run, api: &Arc<A>, file: &Path, session: &SessionVo, total: u64) -> Result<()> {
    let descriptor = upload::Descriptor { size: session.size, part_size: session.part_size, part_count: session.part_count, uploaded_parts: session.uploaded_parts.clone() };
    let mut options = run.options.clone();
    options.parallelism = options.parallelism.min(session.max_parallel_parts as usize).max(1);
    let acknowledged: u64 = {
        let set: std::collections::BTreeSet<u64> = session.uploaded_parts.iter().copied().collect();
        set.iter().map(|i| session.part_size.min(session.size.saturating_sub(i * session.part_size))).sum()
    };
    let started = Instant::now();
    // The uploader's callback must be 'static, so it reports through a channel read next to it.
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<u64>();
    let (part_api, id) = (api.clone(), session.id.clone());
    let report = |done: u64| {
        let elapsed = started.elapsed().as_secs_f64().max(0.001);
        let (message, fraction) = progress_message(&run.label, done, total, done.saturating_sub(acknowledged) as f64 / elapsed);
        (run.progress)(message, Some(fraction));
    };
    run.runtime.block_on(cancellable(run.cancel, async {
        let uploading = upload::upload(
            file,
            &descriptor,
            &options,
            move |index, bytes, sha1| {
                let (api, id) = (part_api.clone(), id.clone());
                async move { api.upload_part(&id, index, bytes, sha1).await }
            },
            move |done, _| {
                let _ = tx.send(done);
            },
        );
        tokio::pin!(uploading);
        loop {
            tokio::select! {
                result = &mut uploading => {
                    while let Ok(done) = rx.try_recv() {
                        report(done);
                    }
                    return result;
                }
                Some(done) = rx.recv() => report(done),
            }
        }
    }))
}

/// `resolveUploadedModpackId` for a new pack: the newest of the player's packs with this name
/// and version.
pub fn find_new_pack<A: PublishApi>(runtime: &tokio::runtime::Handle, api: &A, name: &str, version: &str) -> Result<String> {
    let mine = runtime.block_on(api.list_my()).map_err(|error| {
        tracing::warn!(error = %format!("{error:#}"), "读取我的整合包失败");
        anyhow!("上传成功，但未能定位到刚创建的整合包，请刷新列表查看")
    })?;
    mine.into_iter()
        .filter(|p| p.name == name && p.versions.iter().any(|v| v.name == version))
        .max_by_key(|p| p.versions.iter().map(|v| v.time).max().unwrap_or(0))
        .map(|p| p.id)
        .context("上传成功，但未能定位到刚创建的整合包")
}

/// Kotlin `HttpModpackUploadApi`.
pub struct HttpPublishApi {
    server: ServerApi,
}

impl HttpPublishApi {
    pub fn new(server: ServerApi) -> Self {
        Self { server }
    }

    async fn call<T: DeserializeOwned>(&self, method: reqwest::Method, path: &str, json: Option<String>, configure: impl FnOnce(reqwest::RequestBuilder) -> reqwest::RequestBuilder) -> Result<Option<T>> {
        let jwt = self.server.jwt().filter(|j| !j.trim().is_empty()).context("必须登录后才能上传整合包")?;
        let put = method == reqwest::Method::PUT;
        let mut request = self.server.http.request(method, self.server.url(path)).bearer_auth(jwt.trim());
        if let Some(json) = json {
            request = request.header(reqwest::header::CONTENT_TYPE, "application/json").body(json);
        }
        let unreachable = |error: reqwest::Error| {
            tracing::warn!(error = %error.without_url(), "服务器连接失败");
            anyhow::Error::new(Transport("无法连接服务器 请检查网络连接".into()))
        };
        let response = configure(request).send().await.map_err(unreachable)?;
        let status = response.status();
        tracing::info!(path = path.split('?').next().unwrap_or(path), status = status.as_u16(), "服务器请求");
        let bytes = response.bytes().await.map_err(unreachable)?;
        if put && status.as_u16() >= 500 {
            bail!("服务器暂时无法处理整合包分片上传:HTTP{}", status.as_u16());
        }
        decode_envelope(&bytes)
    }

    async fn data<T: DeserializeOwned>(&self, method: reqwest::Method, path: &str, json: Option<String>) -> Result<T> {
        self.call(method, path, json, |r| r).await?.context("服务器响应缺少整合包上传数据")
    }
}

/// One URL path segment, as Kotlin `urlEncoded` leaves ObjectIds and UUIDs.
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

impl PublishApi for HttpPublishApi {
    async fn create_session(&self, file_name: &str, size: u64, sha1: &str) -> Result<SessionVo> {
        let body = json!({"fileName": file_name, "size": size, "sha1": sha1}).to_string();
        self.data(reqwest::Method::POST, "/modpack/upload-sessions", Some(body)).await
    }
    async fn upload_part(&self, upload_id: &str, index: u64, bytes: Vec<u8>, sha1: String) -> Result<()> {
        let path = format!("/modpack/upload-sessions/{}/parts/{index}", segment(upload_id));
        self.call::<Value>(reqwest::Method::PUT, &path, None, |r| {
            r.header("X-Part-SHA1", sha1).header(reqwest::header::CONTENT_TYPE, "application/octet-stream").body(bytes).timeout(PART_TIMEOUT)
        })
        .await
        .map(drop)
    }
    async fn complete(&self, upload_id: &str) -> Result<SessionVo> {
        let path = format!("/modpack/upload-sessions/{}/complete", segment(upload_id));
        self.call(reqwest::Method::POST, &path, None, |r| r.timeout(PUBLISH_TIMEOUT)).await?.context("服务器响应缺少整合包上传数据")
    }
    async fn cancel(&self, upload_id: &str) -> Result<()> {
        let path = format!("/modpack/upload-sessions/{}", segment(upload_id));
        self.call::<Value>(reqwest::Method::DELETE, &path, None, |r| r.timeout(CANCEL_TIMEOUT)).await.map(drop)
    }
    async fn publish(&self, target: &Target, body: &Value) -> Result<()> {
        let path = match target {
            Target::New => "/modpack/from-upload".to_owned(),
            Target::Version { modpack_id, version } => format!("/modpack/{}/version/{}/from-upload", segment(modpack_id), segment(version)),
        };
        self.call::<Value>(reqwest::Method::POST, &path, Some(body.to_string()), |r| r.timeout(PUBLISH_TIMEOUT)).await.map(drop)
    }
    async fn list_my(&self) -> Result<Vec<MyPack>> {
        self.data(reqwest::Method::GET, "/modpack/my", None).await
    }
}
