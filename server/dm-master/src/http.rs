#![allow(clippy::manual_async_fn, clippy::result_large_err)]

use crate::{
    config::{Config, WorldSnapshotLimits},
    model::{
        CreatedHostData, HeartbeatData, HostData, HostSessionRequest, InfoData, OfflineRequest,
        Response, SessionListRequest, SessionRequest, SyncChunkErrorData, SyncChunkErrorReason,
        SyncChunkLimits, SyncChunkMutationRequest, SyncChunkOutcome, SyncChunksData,
    },
    presence::Presence,
    store::HostStore,
    sync_chunks::{MutationError, MutationOutcome, SyncChunkStore},
    world_snapshots::{
        CommitError, CommitOutcome, ErrorReason as WorldSnapshotErrorReason, Metadata, Prepared,
        SnapshotRecord, UploadReceipt, WorldSnapshotStore,
    },
};
use axum::{
    Json, Router,
    body::{Body, to_bytes},
    extract::{DefaultBodyLimit, FromRequest, Multipart, Path, Request, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response as HttpResponse},
    routing::{get, post, put},
};
use http_body_util::BodyExt;
use serde::de::DeserializeOwned;
use sha1::{Digest, Sha1};
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex as StdMutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Instant, SystemTime},
};
use tokio::{
    io::AsyncWriteExt,
    sync::{Mutex, Semaphore},
};
use tokio_util::io::ReaderStream;
use uuid::Uuid;

static UPLOAD_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Clone)]
pub struct AppState {
    pub config: Config,
    pub store: HostStore,
    pub presence: Presence,
    upload_slots: Arc<Semaphore>,
    host_upload_slots: Arc<StdMutex<HashMap<Uuid, Arc<Semaphore>>>>,
    pub sync_chunks: SyncChunkStore,
    pub world_snapshots: WorldSnapshotStore,
    pub commit_gate: Arc<Mutex<()>>,
}

impl AppState {
    pub fn new(config: Config) -> anyhow::Result<Self> {
        let store = HostStore::open(&config.data_dir)?;
        let world_snapshots = WorldSnapshotStore::new(store.clone(), config.world_snapshot);
        world_snapshots.recover()?;
        Ok(Self {
            store: store.clone(),
            config,
            presence: Presence::default(),
            upload_slots: Arc::new(Semaphore::new(2)),
            host_upload_slots: Arc::new(StdMutex::new(HashMap::new())),
            sync_chunks: SyncChunkStore::new(store),
            world_snapshots,
            commit_gate: Arc::new(Mutex::new(())),
        })
    }

    fn host_upload_slot(&self, host_id: Uuid) -> Arc<Semaphore> {
        self.host_upload_slots
            .lock()
            .expect("host upload slot registry poisoned")
            .entry(host_id)
            .or_insert_with(|| Arc::new(Semaphore::new(1)))
            .clone()
    }
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/info", get(info))
        .route("/hosts", post(create_host))
        .route("/hosts/{host_id}", get(get_host))
        .route("/hosts/{host_id}/world-init", get(download_world_init))
        .route(
            "/hosts/{host_id}/sync-chunks",
            get(get_sync_chunks)
                .put(add_sync_chunk)
                .delete(remove_sync_chunk),
        )
        .route(
            "/hosts/{host_id}/world-snapshot",
            put(upload_world_snapshot),
        )
        .route(
            "/hosts/{host_id}/world-snapshot/status",
            get(world_snapshot_status),
        )
        .route(
            "/hosts/{host_id}/world-snapshot/{snapshot_id}",
            get(download_world_snapshot),
        )
        .route(
            "/hosts/{host_id}/world-snapshot/{snapshot_id}/manifest",
            get(world_snapshot_manifest),
        )
        .route("/gateway/heartbeat", post(gateway_heartbeat))
        .route(
            "/gateway/hosts/{host_id}",
            put(gateway_online).delete(gateway_offline),
        )
        .route("/gateway/sessions", put(gateway_sync))
        .fallback(|| async { error("route not found") })
        .method_not_allowed_fallback(|| async { error("method not allowed") })
        .layer(DefaultBodyLimit::max(
            usize::try_from(state.config.max_upload_bytes.saturating_add(1024 * 1024))
                .unwrap_or(usize::MAX),
        ))
        .with_state(state)
}

fn json<T: serde::Serialize>(value: Response<T>) -> HttpResponse {
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/json")],
        Json(value),
    )
        .into_response()
}

fn error(message: impl Into<String>) -> HttpResponse {
    json(Response::<()>::error(message))
}

async fn info(State(state): State<AppState>) -> HttpResponse {
    json(Response::ok(InfoData {
        public_host: state.config.public_host.clone(),
        tunnel_port: state.config.tunnel_port,
        world_snapshot_api_version: crate::world_snapshots::API_VERSION,
        world_snapshot_limits: state.config.world_snapshot,
    }))
}

#[derive(Debug)]
struct SafeMultipart(Multipart);

struct SafeJson<T>(T);

struct SyncChunkJson<T>(T);

impl<S, T> FromRequest<S> for SafeJson<T>
where
    S: Send + Sync,
    T: DeserializeOwned + Send,
{
    type Rejection = HttpResponse;

    fn from_request(
        req: Request,
        state: &S,
    ) -> impl std::future::Future<Output = Result<Self, Self::Rejection>> + Send {
        async {
            axum::Json::<T>::from_request(req, state)
                .await
                .map(|Json(value)| Self(value))
                .map_err(|_| error("invalid JSON request"))
        }
    }
}

impl<S, T> FromRequest<S> for SyncChunkJson<T>
where
    S: Send + Sync,
    T: DeserializeOwned + Send,
{
    type Rejection = HttpResponse;

    fn from_request(
        req: Request,
        _state: &S,
    ) -> impl std::future::Future<Output = Result<Self, Self::Rejection>> + Send {
        async {
            match to_bytes(req.into_body(), 16 * 1024).await {
                Ok(bytes) => match serde_json::from_slice::<T>(&bytes) {
                    Ok(value) => Ok(Self(value)),
                    Err(_) => Err(sync_chunk_error(
                        SyncChunkErrorReason::InvalidRequest,
                        "invalid SyncChunk JSON request",
                    )),
                },
                Err(_) => Err(sync_chunk_error(
                    SyncChunkErrorReason::InvalidRequest,
                    "invalid SyncChunk JSON request",
                )),
            }
        }
    }
}

impl<S> FromRequest<S> for SafeMultipart
where
    S: Send + Sync,
{
    type Rejection = HttpResponse;
    fn from_request(
        req: Request,
        state: &S,
    ) -> impl std::future::Future<Output = Result<Self, Self::Rejection>> + Send {
        async {
            Multipart::from_request(req, state)
                .await
                .map(Self)
                .map_err(|_| error("invalid multipart request"))
        }
    }
}

async fn create_host(
    State(state): State<AppState>,
    SafeMultipart(mut multipart): SafeMultipart,
) -> HttpResponse {
    let _permit = match state.upload_slots.clone().try_acquire_owned() {
        Ok(permit) => permit,
        Err(_) => return error("too many uploads"),
    };
    let upload_name = format!(
        "upload-{}-{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map(|value| value.as_nanos())
            .unwrap_or_default(),
        UPLOAD_COUNTER.fetch_add(1, Ordering::Relaxed)
    );
    let upload_path = state.store.staging_path().join(upload_name);
    let mut file = match tokio::fs::File::create(&upload_path).await {
        Ok(file) => file,
        Err(_) => return error("cannot create upload staging file"),
    };
    let mut host_name = None;
    let mut has_world = false;
    let mut total = 0_u64;
    loop {
        let Some(mut field) = (match multipart.next_field().await {
            Ok(field) => field,
            Err(_) => return finish_upload_error(&upload_path, "invalid multipart request").await,
        }) else {
            break;
        };
        let field_name = field.name().unwrap_or_default().to_owned();
        if field_name == "name" {
            match field.text().await {
                Ok(value) if value.len() <= 256 => host_name = Some(value),
                Ok(_) => return finish_upload_error(&upload_path, "host name is too long").await,
                Err(_) => {
                    return finish_upload_error(&upload_path, "invalid host name field").await;
                }
            }
        } else if field_name == "worldInit" {
            has_world = true;
            loop {
                let chunk = match field.chunk().await {
                    Ok(Some(chunk)) => chunk,
                    Ok(None) => break,
                    Err(_) => {
                        return finish_upload_error(&upload_path, "invalid multipart request")
                            .await;
                    }
                };
                total = total.saturating_add(chunk.len() as u64);
                if total > state.config.max_upload_bytes {
                    return finish_upload_error(&upload_path, "world initialization is too large")
                        .await;
                }
                if file.write_all(&chunk).await.is_err() {
                    return finish_upload_error(&upload_path, "cannot write upload").await;
                }
            }
        }
    }
    if host_name.is_none() || !has_world {
        return finish_upload_error(&upload_path, "name and worldInit are required").await;
    }
    if file.flush().await.is_err() {
        return finish_upload_error(&upload_path, "cannot flush upload").await;
    }
    drop(file);
    let store = state.store.clone();
    let name = host_name.unwrap_or_default();
    let upload_for_task = upload_path.clone();
    let created =
        tokio::task::spawn_blocking(move || store.create_from_upload(name, &upload_for_task)).await;
    let result = match created {
        Ok(result) => result,
        Err(_) => Err(anyhow::anyhow!("upload validation task failed")),
    };
    match result {
        Ok(host) => json(Response::ok(CreatedHostData {
            id: host.id,
            name: host.name,
        })),
        Err(err) => {
            let _ = tokio::fs::remove_file(&upload_path).await;
            error(err.to_string())
        }
    }
}

async fn finish_upload_error(path: &std::path::Path, message: &str) -> HttpResponse {
    let _ = tokio::fs::remove_file(path).await;
    error(message)
}

async fn get_host(State(state): State<AppState>, Path(host_id): Path<String>) -> HttpResponse {
    let id = match Uuid::parse_str(&host_id) {
        Ok(id) => id,
        Err(_) => return error("invalid host id"),
    };
    let host = match state.store.get(id) {
        Ok(Some(host)) => host,
        Ok(None) => return error("host not found"),
        Err(_) => return error("cannot read host"),
    };
    let (presence, game_port) = state.presence.state_for(id).await;
    json(Response::ok(HostData {
        id: host.id,
        name: host.name,
        state: presence,
        game_port,
    }))
}

async fn download_world_init(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
) -> HttpResponse {
    let id = match Uuid::parse_str(&host_id) {
        Ok(id) => id,
        Err(_) => return error("invalid host id"),
    };
    if !matches!(state.store.get(id), Ok(Some(_))) {
        return error("host not found");
    }
    let file = match tokio::fs::File::open(state.store.world_init(id)).await {
        Ok(file) => file,
        Err(_) => return error("world initialization is unavailable"),
    };
    let stream = ReaderStream::new(file);
    (
        StatusCode::OK,
        [(header::CONTENT_TYPE, "application/zip")],
        Body::from_stream(stream),
    )
        .into_response()
}

fn sync_chunk_limits(state: &AppState) -> SyncChunkLimits {
    SyncChunkLimits {
        max_total: state.config.sync_chunk_max_total,
    }
}

fn sync_chunk_data(
    state: &AppState,
    snapshot: crate::sync_chunks::SyncChunkSnapshot,
    outcome: Option<SyncChunkOutcome>,
) -> SyncChunksData {
    SyncChunksData {
        revision: snapshot.revision,
        chunks: snapshot.chunks,
        limits: sync_chunk_limits(state),
        outcome,
    }
}

fn sync_chunk_error(reason: SyncChunkErrorReason, message: impl Into<String>) -> HttpResponse {
    json(Response::<SyncChunkErrorData>::error_data(
        message,
        SyncChunkErrorData { reason },
    ))
}

fn header_session(headers: &HeaderMap) -> Result<Uuid, HttpResponse> {
    let value = headers.get("x-dm-session").ok_or_else(|| {
        sync_chunk_error(
            SyncChunkErrorReason::InvalidRequest,
            "X-DM-Session is required",
        )
    })?;
    let value = value.to_str().map_err(|_| {
        sync_chunk_error(SyncChunkErrorReason::InvalidRequest, "invalid X-DM-Session")
    })?;
    Uuid::parse_str(value)
        .map_err(|_| sync_chunk_error(SyncChunkErrorReason::InvalidRequest, "invalid X-DM-Session"))
}

async fn get_sync_chunks(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    headers: HeaderMap,
) -> HttpResponse {
    let host_id = match Uuid::parse_str(&host_id) {
        Ok(value) => value,
        Err(_) => return sync_chunk_error(SyncChunkErrorReason::InvalidRequest, "invalid host id"),
    };
    let session_id = match header_session(&headers) {
        Ok(value) => value,
        Err(response) => return response,
    };
    let gate = state.commit_gate.clone().lock_owned().await;
    if !state.presence.has_active_session(host_id, session_id).await {
        return sync_chunk_error(
            SyncChunkErrorReason::SessionUnavailable,
            "host session is unavailable",
        );
    }
    let sync_chunks = state.sync_chunks.clone();
    let result = tokio::task::spawn_blocking(move || {
        let _gate = gate;
        sync_chunks.read(host_id)
    })
    .await;
    match result {
        Ok(Ok(snapshot)) => json(Response::ok(sync_chunk_data(&state, snapshot, None))),
        Ok(Err(error)) if error.to_string() == "host not found" => {
            sync_chunk_error(SyncChunkErrorReason::HostNotFound, error.to_string())
        }
        Ok(Err(error)) => sync_chunk_error(SyncChunkErrorReason::StorageError, error.to_string()),
        Err(error) => sync_chunk_error(SyncChunkErrorReason::StorageError, error.to_string()),
    }
}

async fn mutate_sync_chunk(
    state: AppState,
    host_id: String,
    request: SyncChunkMutationRequest,
    delete: bool,
) -> HttpResponse {
    let host_id = match Uuid::parse_str(&host_id) {
        Ok(value) => value,
        Err(_) => return sync_chunk_error(SyncChunkErrorReason::InvalidRequest, "invalid host id"),
    };
    if request.expected_revision < 0 {
        return sync_chunk_error(
            SyncChunkErrorReason::InvalidRequest,
            "expectedRevision must be nonnegative",
        );
    }
    let gate = state.commit_gate.clone().lock_owned().await;
    if !state
        .presence
        .has_active_session(host_id, request.session_id)
        .await
    {
        return sync_chunk_error(
            SyncChunkErrorReason::SessionUnavailable,
            "host session is unavailable",
        );
    }
    let sync_chunks = state.sync_chunks.clone();
    let max_total = state.config.sync_chunk_max_total;
    let result = tokio::task::spawn_blocking(move || {
        let _gate = gate;
        sync_chunks.mutate(host_id, &request, delete, max_total)
    })
    .await;
    match result {
        Ok(Ok((snapshot, outcome))) => {
            let outcome = match outcome {
                MutationOutcome::Added => SyncChunkOutcome::Added,
                MutationOutcome::AlreadyPresent => SyncChunkOutcome::AlreadyPresent,
                MutationOutcome::Removed => SyncChunkOutcome::Removed,
                MutationOutcome::AlreadyAbsent => SyncChunkOutcome::AlreadyAbsent,
            };
            json(Response::ok(sync_chunk_data(
                &state,
                snapshot,
                Some(outcome),
            )))
        }
        Ok(Err(MutationError::RevisionConflict)) => sync_chunk_error(
            SyncChunkErrorReason::RevisionConflict,
            "SyncChunk revision conflict",
        ),
        Ok(Err(MutationError::OwnedByOther)) => sync_chunk_error(
            SyncChunkErrorReason::OwnedByOther,
            "SyncChunk is owned by another player",
        ),
        Ok(Err(MutationError::TotalLimitReached)) => sync_chunk_error(
            SyncChunkErrorReason::TotalLimitReached,
            "SyncChunk total limit reached",
        ),
        Ok(Err(MutationError::InvalidRequest(error))) => {
            sync_chunk_error(SyncChunkErrorReason::InvalidRequest, error.to_string())
        }
        Ok(Err(MutationError::Storage(error))) => {
            if error.to_string() == "host not found" {
                sync_chunk_error(SyncChunkErrorReason::HostNotFound, error.to_string())
            } else {
                sync_chunk_error(SyncChunkErrorReason::StorageError, error.to_string())
            }
        }
        Err(error) => sync_chunk_error(SyncChunkErrorReason::StorageError, error.to_string()),
    }
}

async fn add_sync_chunk(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    SyncChunkJson(request): SyncChunkJson<SyncChunkMutationRequest>,
) -> HttpResponse {
    mutate_sync_chunk(state, host_id, request, false).await
}

async fn remove_sync_chunk(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    SyncChunkJson(request): SyncChunkJson<SyncChunkMutationRequest>,
) -> HttpResponse {
    mutate_sync_chunk(state, host_id, request, true).await
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct WorldSnapshotStatusData {
    api_version: u32,
    latest: Option<SnapshotRecord>,
    last_upload: Option<UploadReceipt>,
    stored_columns: usize,
    total_columns: usize,
    complete: bool,
    limits: WorldSnapshotLimits,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct WorldSnapshotManifestData {
    api_version: u32,
    snapshot_id: Uuid,
    manifest: Metadata,
}

fn world_snapshot_error(
    reason: WorldSnapshotErrorReason,
    message: impl Into<String>,
) -> HttpResponse {
    json(Response::<()>::error_data(
        message,
        serde_json::json!({ "reason": reason }),
    ))
}

struct SnapshotHeaders {
    session_id: Uuid,
    sequence: i64,
    revision: i64,
    sha1: String,
    cycle_id: Uuid,
}

fn parse_snapshot_headers(headers: &HeaderMap) -> Result<SnapshotHeaders, HttpResponse> {
    let session_id = header_session(headers)?;
    let sequence = headers
        .get("x-dm-snapshot-sequence")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<i64>().ok())
        .filter(|value| *value > 0)
        .ok_or_else(|| {
            world_snapshot_error(
                WorldSnapshotErrorReason::InvalidRequest,
                "invalid X-DM-Snapshot-Sequence",
            )
        })?;
    let revision = headers
        .get("x-dm-sync-revision")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<i64>().ok())
        .filter(|value| *value >= 0)
        .ok_or_else(|| {
            world_snapshot_error(
                WorldSnapshotErrorReason::InvalidRequest,
                "invalid X-DM-Sync-Revision",
            )
        })?;
    let sha1 = headers
        .get("x-dm-sha1")
        .and_then(|value| value.to_str().ok())
        .filter(|value| {
            value.len() == 40
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
        })
        .map(str::to_owned)
        .ok_or_else(|| {
            world_snapshot_error(
                WorldSnapshotErrorReason::InvalidRequest,
                "invalid X-DM-SHA1",
            )
        })?;
    let cycle_id = headers
        .get("x-dm-sync-cycle")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| Uuid::parse_str(value).ok())
        .filter(|value| value.get_version_num() == 7)
        .ok_or_else(|| {
            world_snapshot_error(
                WorldSnapshotErrorReason::InvalidRequest,
                "invalid X-DM-Sync-Cycle",
            )
        })?;
    Ok(SnapshotHeaders {
        session_id,
        sequence,
        revision,
        sha1,
        cycle_id,
    })
}

/// Per-attempt server-side sizes and durations.
///
/// Durations come from a monotonic clock. Stages can overlap, so `total_ms` is measured
/// independently instead of being the sum of the others; a stage that never ran stays
/// `None` and is printed as `null` rather than `0`.
struct ServerCycleMetrics {
    started: Instant,
    received_bytes: u64,
    upload_zip_bytes: Option<u64>,
    snapshot_expanded_bytes: Option<u64>,
    directory_io: Option<crate::world_snapshots::DirectoryIoStats>,
    receive_ms: Option<u128>,
    validate_ms: Option<u128>,
    merge_ms: Option<u128>,
    commit_ms: Option<u128>,
}

impl ServerCycleMetrics {
    fn new() -> Self {
        Self {
            started: Instant::now(),
            received_bytes: 0,
            upload_zip_bytes: None,
            snapshot_expanded_bytes: None,
            directory_io: None,
            receive_ms: None,
            validate_ms: None,
            merge_ms: None,
            commit_ms: None,
        }
    }

    fn report(&self, host_id: Option<Uuid>, cycle_id: Option<Uuid>, result: &str, stage: &str) {
        tracing::info!(
            "DM_WORLD_SYNC_SERVER cycle={} host={} result={} stage={} receivedBytes={} uploadZipBytes={} snapshotExpandedBytes={} linkedBytes={} copiedBytes={} writtenBytes={} receiveMs={} validateMs={} mergeMs={} commitMs={} totalMs={}",
            option_text(cycle_id),
            option_text(host_id),
            result,
            stage,
            self.received_bytes,
            option_text(self.upload_zip_bytes),
            option_text(self.snapshot_expanded_bytes),
            option_text(self.directory_io.map(|io| io.linked_bytes)),
            option_text(self.directory_io.map(|io| io.copied_bytes)),
            option_text(self.directory_io.map(|io| io.written_bytes)),
            option_text(self.receive_ms),
            option_text(self.validate_ms),
            option_text(self.merge_ms),
            option_text(self.commit_ms),
            self.started.elapsed().as_millis(),
        );
    }
}

fn option_text<T: std::fmt::Display>(value: Option<T>) -> String {
    value.map_or_else(|| "null".to_owned(), |value| value.to_string())
}

/// Everything the commit stage inherits from the (gate-free) preparation stage.
struct PreparedUpload {
    prepared: Prepared,
    upload_zip_bytes: u64,
    validate_ms: u128,
    merge_ms: Option<u128>,
    cleanup: StagingCleanup,
    permit: tokio::sync::OwnedSemaphorePermit,
    host_permit: tokio::sync::OwnedSemaphorePermit,
}

enum PrepareFailure {
    Invalid(String),
    Roster(String),
    Commit(CommitError),
}

struct PreparationFailed {
    failure: PrepareFailure,
    validate_ms: u128,
    merge_ms: Option<u128>,
}

async fn upload_world_snapshot(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    request: Request,
) -> HttpResponse {
    let mut metrics = ServerCycleMetrics::new();
    let host_id = match Uuid::parse_str(&host_id) {
        Ok(value) => value,
        Err(_) => {
            metrics.report(None, None, "Failed", "request");
            return world_snapshot_error(
                WorldSnapshotErrorReason::InvalidRequest,
                "invalid host id",
            );
        }
    };
    let headers = match parse_snapshot_headers(request.headers()) {
        Ok(value) => value,
        Err(response) => {
            metrics.report(Some(host_id), None, "Failed", "request");
            return response;
        }
    };
    let cycle = Some(headers.cycle_id);
    let host = Some(host_id);
    if !request
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| {
            value
                .split(';')
                .next()
                .is_some_and(|value| value.trim().eq_ignore_ascii_case("application/zip"))
        })
    {
        metrics.report(host, cycle, "Failed", "request");
        return world_snapshot_error(
            WorldSnapshotErrorReason::InvalidRequest,
            "Content-Type must be application/zip",
        );
    }
    if !matches!(state.store.get(host_id), Ok(Some(_))) {
        metrics.report(host, cycle, "Failed", "request");
        return world_snapshot_error(WorldSnapshotErrorReason::StorageError, "host not found");
    }
    if !state
        .presence
        .has_active_session(host_id, headers.session_id)
        .await
    {
        metrics.report(host, cycle, "Failed", "session");
        return world_snapshot_error(
            WorldSnapshotErrorReason::SessionUnavailable,
            "host session is unavailable",
        );
    }
    let host_permit = match state.host_upload_slot(host_id).try_acquire_owned() {
        Ok(value) => value,
        Err(_) => {
            metrics.report(host, cycle, "Busy", "admission");
            return world_snapshot_error(
                WorldSnapshotErrorReason::Busy,
                "world snapshot upload for this host is busy",
            );
        }
    };
    let permit = match state.upload_slots.clone().try_acquire_owned() {
        Ok(value) => value,
        Err(_) => {
            drop(host_permit);
            metrics.report(host, cycle, "Busy", "admission");
            return world_snapshot_error(
                WorldSnapshotErrorReason::Busy,
                "world snapshot upload is busy",
            );
        }
    };
    let staging = state.store.staging_path().join(format!(
        "world-snapshot-{}-{}",
        std::process::id(),
        Uuid::now_v7()
    ));
    let staging_cleanup = StagingCleanup::new(staging.clone());
    let mut file = match tokio::fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&staging)
        .await
    {
        Ok(value) => value,
        Err(error) => {
            drop(host_permit);
            metrics.report(host, cycle, "Failed", "receive");
            return world_snapshot_error(WorldSnapshotErrorReason::StorageError, error.to_string());
        }
    };
    let max_upload = state.config.world_snapshot.max_upload_bytes;
    let receive_started = Instant::now();
    let mut hasher = Sha1::new();
    let mut total = 0_u64;
    let mut body = request.into_body();
    let received: Result<(), String> = async {
        while let Some(frame) = body.frame().await {
            let frame = frame.map_err(|error| error.to_string())?;
            let data = frame
                .into_data()
                .map_err(|_| "request body contained non-data frame".to_owned())?;
            total = total.saturating_add(data.len() as u64);
            if total > max_upload {
                return Err("world snapshot upload is too large".into());
            }
            hasher.update(&data);
            file.write_all(&data)
                .await
                .map_err(|error| error.to_string())?;
        }
        file.sync_all().await.map_err(|error| error.to_string())?;
        Ok(())
    }
    .await;
    drop(file);
    metrics.received_bytes = total;
    metrics.receive_ms = Some(receive_started.elapsed().as_millis());
    if let Err(message) = received {
        drop(permit);
        drop(host_permit);
        let too_large = total > max_upload;
        metrics.report(host, cycle, "Failed", "receive");
        return world_snapshot_error(
            if too_large {
                WorldSnapshotErrorReason::TooLarge
            } else {
                WorldSnapshotErrorReason::InvalidRequest
            },
            message,
        );
    }
    if hex::encode(hasher.finalize()) != headers.sha1 {
        drop(permit);
        drop(host_permit);
        metrics.report(host, cycle, "Failed", "receive");
        return world_snapshot_error(
            WorldSnapshotErrorReason::InvalidRequest,
            "world snapshot SHA-1 does not match header",
        );
    }
    let initial_chunks = match state.sync_chunks.read(host_id) {
        Ok(value) => value,
        Err(error) => {
            drop(permit);
            drop(host_permit);
            metrics.report(host, cycle, "Failed", "roster");
            return world_snapshot_error(WorldSnapshotErrorReason::StorageError, error.to_string());
        }
    };
    if initial_chunks.revision != headers.revision {
        drop(permit);
        drop(host_permit);
        metrics.report(host, cycle, "Failed", "roster");
        return world_snapshot_error(
            WorldSnapshotErrorReason::RevisionConflict,
            "SyncChunk revision conflict",
        );
    }

    // Validation and the cumulative rebuild run outside the commit gate so that other
    // rooms keep making presence and roster progress while a large archive is rebuilt.
    let world_snapshots = state.world_snapshots.clone();
    let staged_path = staging.clone();
    let roster_for_prepare = initial_chunks.clone();
    let sha1 = headers.sha1.clone();
    let session_id = headers.session_id;
    let sequence = headers.sequence;
    let revision = headers.revision;
    let cycle_id = headers.cycle_id;
    let prepared = tokio::task::spawn_blocking(move || {
        // Ownership of the staging file and the upload permit moves into this task, so
        // a cancelled HTTP request cannot delete data that is still being read.
        let cleanup = staging_cleanup;
        let permit = permit;
        let host_permit = host_permit;
        let validate_started = Instant::now();
        let validated = world_snapshots.validate_upload(
            &staged_path,
            host_id,
            session_id,
            sequence,
            revision,
            cycle_id,
            &sha1,
        );
        let validated = match validated {
            Ok(value) => value,
            Err(error) => {
                return Err(PreparationFailed {
                    failure: PrepareFailure::Invalid(error.to_string()),
                    validate_ms: validate_started.elapsed().as_millis(),
                    merge_ms: None,
                });
            }
        };
        if let Err(error) = validated.validate_subset(&roster_for_prepare) {
            return Err(PreparationFailed {
                failure: PrepareFailure::Roster(error.to_string()),
                validate_ms: validate_started.elapsed().as_millis(),
                merge_ms: None,
            });
        }
        let validate_ms = validate_started.elapsed().as_millis();
        let upload_zip_bytes = validated.upload_zip_bytes();
        let merge_started = Instant::now();
        match world_snapshots.prepare(host_id, &validated, &staged_path, &roster_for_prepare) {
            Ok(prepared) => Ok(PreparedUpload {
                prepared,
                upload_zip_bytes,
                validate_ms,
                merge_ms: Some(merge_started.elapsed().as_millis()),
                cleanup,
                permit,
                host_permit,
            }),
            Err(error) => Err(PreparationFailed {
                failure: PrepareFailure::Commit(error),
                validate_ms,
                merge_ms: Some(merge_started.elapsed().as_millis()),
            }),
        }
    })
    .await;
    let prepared = match prepared {
        Ok(Ok(value)) => value,
        Ok(Err(failed)) => {
            metrics.validate_ms = Some(failed.validate_ms);
            metrics.merge_ms = failed.merge_ms;
            let (reason, message, stage) = match failed.failure {
                PrepareFailure::Invalid(message) => (
                    WorldSnapshotErrorReason::InvalidRequest,
                    message,
                    "validate",
                ),
                PrepareFailure::Roster(message) => (
                    WorldSnapshotErrorReason::RevisionConflict,
                    message,
                    "roster",
                ),
                PrepareFailure::Commit(error) => {
                    let (reason, message) = commit_error_response(error);
                    (reason, message, "merge")
                }
            };
            metrics.report(host, cycle, "Failed", stage);
            return world_snapshot_error(reason, message);
        }
        Err(error) => {
            metrics.report(host, cycle, "Failed", "validate");
            return world_snapshot_error(WorldSnapshotErrorReason::StorageError, error.to_string());
        }
    };
    metrics.validate_ms = Some(prepared.validate_ms);
    metrics.merge_ms = prepared.merge_ms;
    metrics.upload_zip_bytes = Some(prepared.upload_zip_bytes);

    let candidate = match prepared.prepared {
        Prepared::Idempotent(receipt) => {
            drop(prepared.cleanup);
            drop(prepared.permit);
            drop(prepared.host_permit);
            metrics.snapshot_expanded_bytes = Some(receipt.expanded_bytes);
            metrics.report(host, cycle, "Idempotent", "commit");
            return json(Response::ok(receipt));
        }
        Prepared::Candidate(candidate) => candidate,
    };
    metrics.snapshot_expanded_bytes = Some(candidate.expanded_bytes());
    metrics.directory_io = Some(candidate.io_stats);

    let gate = state.commit_gate.clone().lock_owned().await;
    if !state
        .presence
        .has_active_session(host_id, headers.session_id)
        .await
    {
        metrics.report(host, cycle, "Failed", "commit");
        return world_snapshot_error(
            WorldSnapshotErrorReason::SessionUnavailable,
            "host session is unavailable",
        );
    }
    let current_chunks = match state.sync_chunks.read(host_id) {
        Ok(value) => value,
        Err(error) => {
            metrics.report(host, cycle, "Failed", "commit");
            return world_snapshot_error(WorldSnapshotErrorReason::StorageError, error.to_string());
        }
    };
    if current_chunks.revision != headers.revision {
        metrics.report(host, cycle, "Failed", "commit");
        return world_snapshot_error(
            WorldSnapshotErrorReason::RevisionConflict,
            "SyncChunk revision conflict",
        );
    }
    let world_snapshots = state.world_snapshots.clone();
    let cleanup = prepared.cleanup;
    let permit = prepared.permit;
    let host_permit = prepared.host_permit;
    let commit_started = Instant::now();
    let committed = tokio::task::spawn_blocking(move || {
        let _gate = gate;
        let cleanup = cleanup;
        let permit = permit;
        let host_permit = host_permit;
        let result = world_snapshots.commit(host_id, *candidate, &current_chunks);
        drop(permit);
        drop(host_permit);
        drop(cleanup);
        result
    })
    .await;
    metrics.commit_ms = Some(commit_started.elapsed().as_millis());
    match committed {
        Ok(Ok(CommitOutcome::Committed(receipt))) => {
            metrics.report(host, cycle, "Committed", "commit");
            json(Response::ok(receipt))
        }
        Ok(Ok(CommitOutcome::Idempotent(receipt))) => {
            metrics.report(host, cycle, "Idempotent", "commit");
            json(Response::ok(receipt))
        }
        Ok(Err(error)) => {
            let (reason, message) = commit_error_response(error);
            metrics.report(host, cycle, "Failed", "commit");
            world_snapshot_error(reason, message)
        }
        Err(error) => {
            // The blocking task panicked or was aborted; the commit result is unknown
            // and the client must reconcile against lastUpload before advancing.
            metrics.report(host, cycle, "Unknown", "commit");
            world_snapshot_error(WorldSnapshotErrorReason::StorageError, error.to_string())
        }
    }
}

fn commit_error_response(error: CommitError) -> (WorldSnapshotErrorReason, String) {
    match error {
        CommitError::Invalid(error) => {
            (WorldSnapshotErrorReason::InvalidRequest, error.to_string())
        }
        CommitError::RevisionConflict => (
            WorldSnapshotErrorReason::RevisionConflict,
            "SyncChunk revision conflict".to_owned(),
        ),
        CommitError::SequenceConflict => (
            WorldSnapshotErrorReason::SequenceConflict,
            "world snapshot sequence conflict".to_owned(),
        ),
        CommitError::BaseConflict => (
            WorldSnapshotErrorReason::BaseConflict,
            "world snapshot base is no longer the latest snapshot".to_owned(),
        ),
        CommitError::TooLarge(error) => (WorldSnapshotErrorReason::TooLarge, error.to_string()),
        CommitError::Storage(error) => (WorldSnapshotErrorReason::StorageError, error.to_string()),
    }
}

struct StagingCleanup {
    path: std::path::PathBuf,
}

impl StagingCleanup {
    fn new(path: std::path::PathBuf) -> Self {
        Self { path }
    }
}

impl Drop for StagingCleanup {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

async fn world_snapshot_status(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    headers: HeaderMap,
) -> HttpResponse {
    let host_id = match Uuid::parse_str(&host_id) {
        Ok(value) => value,
        Err(_) => {
            return world_snapshot_error(
                WorldSnapshotErrorReason::InvalidRequest,
                "invalid host id",
            );
        }
    };
    let session = match header_session(&headers) {
        Ok(value) => value,
        Err(response) => return response,
    };
    let gate = state.commit_gate.lock().await;
    if !state.presence.has_active_session(host_id, session).await {
        return world_snapshot_error(
            WorldSnapshotErrorReason::SessionUnavailable,
            "host session is unavailable",
        );
    }
    let chunks = match state.sync_chunks.read(host_id) {
        Ok(value) => value,
        Err(error) => {
            return world_snapshot_error(WorldSnapshotErrorReason::StorageError, error.to_string());
        }
    };
    drop(gate);
    let snapshots = state.world_snapshots.clone();
    let result = tokio::task::spawn_blocking(move || snapshots.status(host_id, &chunks))
        .await
        .unwrap_or_else(|error| Err(error.into()));
    match result {
        Ok(status) => json(Response::ok(WorldSnapshotStatusData {
            api_version: crate::world_snapshots::API_VERSION,
            latest: status.latest,
            last_upload: status.last_upload,
            stored_columns: status.stored_columns,
            total_columns: status.total_columns,
            complete: status.complete,
            limits: state.config.world_snapshot,
        })),
        Err(error) => {
            world_snapshot_error(WorldSnapshotErrorReason::StorageError, error.to_string())
        }
    }
}

async fn world_snapshot_manifest(
    State(state): State<AppState>,
    Path((host_id, snapshot_id)): Path<(String, String)>,
    headers: HeaderMap,
) -> HttpResponse {
    let (host_id, snapshot_id) = match (Uuid::parse_str(&host_id), Uuid::parse_str(&snapshot_id)) {
        (Ok(host), Ok(snapshot)) => (host, snapshot),
        _ => {
            return world_snapshot_error(
                WorldSnapshotErrorReason::InvalidRequest,
                "invalid host or snapshot id",
            );
        }
    };
    let session = match header_session(&headers) {
        Ok(value) => value,
        Err(response) => return response,
    };
    let gate = state.commit_gate.lock().await;
    if !state.presence.has_active_session(host_id, session).await {
        return world_snapshot_error(
            WorldSnapshotErrorReason::SessionUnavailable,
            "host session is unavailable",
        );
    }
    drop(gate);
    let snapshots = state.world_snapshots.clone();
    let result = tokio::task::spawn_blocking(move || snapshots.manifest(host_id, snapshot_id))
        .await
        .unwrap_or_else(|error| Err(error.into()));
    match result {
        Ok(Some(manifest)) => json(Response::ok(WorldSnapshotManifestData {
            api_version: crate::world_snapshots::API_VERSION,
            snapshot_id,
            manifest,
        })),
        Ok(None) => world_snapshot_error(
            WorldSnapshotErrorReason::SnapshotNotFound,
            "no such world snapshot is retained",
        ),
        Err(error) => {
            world_snapshot_error(WorldSnapshotErrorReason::StorageError, error.to_string())
        }
    }
}

async fn download_world_snapshot(
    State(state): State<AppState>,
    Path((host_id, snapshot_id)): Path<(String, String)>,
    headers: HeaderMap,
) -> HttpResponse {
    let (host_id, snapshot_id) = match (Uuid::parse_str(&host_id), Uuid::parse_str(&snapshot_id)) {
        (Ok(host), Ok(snapshot)) => (host, snapshot),
        _ => {
            return world_snapshot_error(
                WorldSnapshotErrorReason::InvalidRequest,
                "invalid host or snapshot id",
            );
        }
    };
    let session = match header_session(&headers) {
        Ok(value) => value,
        Err(response) => return response,
    };
    let gate = state.commit_gate.lock().await;
    if !state.presence.has_active_session(host_id, session).await {
        return world_snapshot_error(
            WorldSnapshotErrorReason::SessionUnavailable,
            "host session is unavailable",
        );
    }
    let _ = (snapshot_id, gate);
    let mut response = world_snapshot_error(
        WorldSnapshotErrorReason::DownloadUnavailable,
        "world snapshot download is unavailable in this API version",
    );
    *response.status_mut() = StatusCode::NOT_IMPLEMENTED;
    response
}

async fn gateway_heartbeat(State(state): State<AppState>) -> HttpResponse {
    let _gate = state.commit_gate.lock().await;
    json(Response::ok(HeartbeatData {
        sync_required: state.presence.heartbeat().await,
    }))
}

async fn gateway_online(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    SafeJson(request): SafeJson<HostSessionRequest>,
) -> HttpResponse {
    let id = match Uuid::parse_str(&host_id) {
        Ok(id) => id,
        Err(_) => return error("invalid host id"),
    };
    if !matches!(state.store.get(id), Ok(Some(_))) {
        return error("host not found");
    }
    let _gate = state.commit_gate.lock().await;
    let session = SessionRequest {
        host_id: id,
        session_id: request.session_id,
        game_port: request.game_port,
    };
    match state.presence.online(id, session).await {
        Ok(()) => json(Response::<()>::empty()),
        Err(message) => error(message),
    }
}

async fn gateway_offline(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    SafeJson(request): SafeJson<OfflineRequest>,
) -> HttpResponse {
    let id = match Uuid::parse_str(&host_id) {
        Ok(id) => id,
        Err(_) => return error("invalid host id"),
    };
    let _gate = state.commit_gate.lock().await;
    state.presence.offline(id, request.session_id).await;
    json(Response::<()>::empty())
}

async fn gateway_sync(
    State(state): State<AppState>,
    SafeJson(request): SafeJson<SessionListRequest>,
) -> HttpResponse {
    let _gate = state.commit_gate.lock().await;
    for session in &request.sessions {
        if !matches!(state.store.get(session.host_id), Ok(Some(_))) {
            return error("host not found");
        }
    }
    match state.presence.sync(request.sessions).await {
        Ok(()) => json(Response::<()>::empty()),
        Err(message) => error(message),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use http_body_util::BodyExt;
    use std::io::Write;
    use tempfile::tempdir;
    use tower::ServiceExt;
    use zip::{ZipWriter, write::SimpleFileOptions};

    fn zip_bytes() -> Vec<u8> {
        let mut bytes = std::io::Cursor::new(Vec::new());
        {
            let mut writer = ZipWriter::new(&mut bytes);
            writer
                .start_file("level.dat", SimpleFileOptions::default())
                .unwrap();
            writer.write_all(b"init").unwrap();
            writer.finish().unwrap();
        }
        bytes.into_inner()
    }

    fn multipart(name: &str, zip: &[u8]) -> (String, Vec<u8>) {
        let boundary = "dm-test-boundary";
        let mut body = Vec::new();
        write!(
            body,
            "--{boundary}\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n{name}\r\n"
        )
        .unwrap();
        write!(body, "--{boundary}\r\nContent-Disposition: form-data; name=\"worldInit\"; filename=\"world.zip\"\r\nContent-Type: application/zip\r\n\r\n").unwrap();
        body.extend_from_slice(zip);
        write!(body, "\r\n--{boundary}--\r\n").unwrap();
        (format!("multipart/form-data; boundary={boundary}"), body)
    }

    fn snapshot_headers(hash: &str) -> HeaderMap {
        let mut headers = HeaderMap::new();
        headers.insert("X-DM-Session", Uuid::now_v7().to_string().parse().unwrap());
        headers.insert("X-DM-Snapshot-Sequence", "1".parse().unwrap());
        headers.insert("X-DM-Sync-Revision", "0".parse().unwrap());
        headers.insert("X-DM-SHA1", hash.parse().unwrap());
        headers.insert(
            "X-DM-Sync-Cycle",
            Uuid::now_v7().to_string().parse().unwrap(),
        );
        headers
    }

    #[test]
    fn snapshot_sha1_header_requires_lowercase_40_hex() {
        let valid = "0123456789abcdef0123456789abcdef01234567";
        assert!(parse_snapshot_headers(&snapshot_headers(valid)).is_ok());

        for invalid in [
            "0123456789abcdef0123456789abcdef0123456",
            "0123456789abcdef0123456789abcdef012345678",
            "0123456789abcdef0123456789abcdef0123456G",
            "0123456789ABCDEF0123456789abcdef01234567",
        ] {
            assert!(parse_snapshot_headers(&snapshot_headers(invalid)).is_err());
        }

        let mut old_header = snapshot_headers(valid);
        old_header.remove("X-DM-SHA1");
        old_header.insert(
            "X-DM-SHA256",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                .parse()
                .unwrap(),
        );
        assert!(parse_snapshot_headers(&old_header).is_err());
    }

    #[test]
    fn snapshot_headers_require_a_uuidv7_sync_cycle() {
        let valid = "0123456789abcdef0123456789abcdef01234567";
        let mut missing = snapshot_headers(valid);
        missing.remove("X-DM-Sync-Cycle");
        assert!(parse_snapshot_headers(&missing).is_err());

        let mut v4 = snapshot_headers(valid);
        v4.insert(
            "X-DM-Sync-Cycle",
            "1c1b1a19-1817-4615-9413-121110090807".parse().unwrap(),
        );
        assert!(parse_snapshot_headers(&v4).is_err());
    }

    #[test]
    fn world_snapshot_admission_is_per_host_and_releases_on_drop() {
        let dir = tempdir().unwrap();
        let state = AppState::new(Config {
            data_dir: dir.path().to_path_buf(),
            ..Config::default()
        })
        .unwrap();
        let host_a = Uuid::now_v7();
        let host_b = Uuid::now_v7();
        let first = state.host_upload_slot(host_a).try_acquire_owned().unwrap();
        assert!(state.host_upload_slot(host_a).try_acquire_owned().is_err());
        let other = state.host_upload_slot(host_b).try_acquire_owned().unwrap();
        drop(other);
        drop(first);
        assert!(state.host_upload_slot(host_a).try_acquire_owned().is_ok());
    }

    #[tokio::test]
    async fn retired_sync_chunk_snapshot_routes_are_gone() {
        let dir = tempdir().unwrap();
        let config = Config {
            data_dir: dir.path().to_path_buf(),
            ..Config::default()
        };
        let app = router(AppState::new(config).unwrap());
        let host_id = Uuid::now_v7();
        for uri in [
            format!("/hosts/{host_id}/sync-chunks/snapshot"),
            format!("/hosts/{host_id}/sync-chunks/snapshot/status"),
        ] {
            let response = app
                .clone()
                .oneshot(Request::builder().uri(&uri).body(Body::empty()).unwrap())
                .await
                .unwrap();
            let bytes = response.into_body().collect().await.unwrap().to_bytes();
            let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
            assert_eq!(value["code"], -1);
            assert_eq!(value["msg"], "route not found");
        }
    }

    #[tokio::test]
    async fn world_snapshot_v5_zero_chunk_router_happy_path_is_idempotent() {
        let dir = tempdir().unwrap();
        let config = Config {
            data_dir: dir.path().to_path_buf(),
            ..Config::default()
        };
        let state = AppState::new(config).unwrap();
        let host_id = Uuid::now_v7();
        std::fs::create_dir(state.store.host_root(host_id)).unwrap();
        std::fs::write(
            state.store.host_root(host_id).join("host.json"),
            serde_json::to_vec(&serde_json::json!({"id": host_id, "name": "room"})).unwrap(),
        )
        .unwrap();

        let session_id = Uuid::now_v7();
        state.presence.heartbeat().await;
        state.presence.sync(vec![]).await.unwrap();
        state
            .presence
            .online(
                host_id,
                SessionRequest {
                    host_id,
                    session_id,
                    game_port: 25565,
                },
            )
            .await
            .unwrap();

        let cycle_id = Uuid::now_v7();
        let world = b"level-data";
        let world_sha1 = hex::encode(Sha1::digest(world));
        let metadata = serde_json::json!({
            "formatVersion": 5,
            "kind": "world-snapshot",
            "hostId": host_id,
            "sessionId": session_id,
            "cycleId": cycle_id,
            "sequence": 1,
            "baseSnapshotId": null,
            "syncChunkRevision": 0,
            "capturedAt": "2026-09-21T00:00:00Z",
            "captureMode": "memory-and-files",
            "restoreSafe": false,
            "world": {
                "excludedFiles": [],
                "excludedDirectories": [],
                "directories": [],
                "files": [{"path": "level.dat", "bytes": world.len(), "sha1": world_sha1}],
            },
            "chunks": [],
        });
        let mut zip = std::io::Cursor::new(Vec::new());
        {
            let mut writer = ZipWriter::new(&mut zip);
            writer
                .start_file("metadata.json", SimpleFileOptions::default())
                .unwrap();
            writer
                .write_all(&serde_json::to_vec(&metadata).unwrap())
                .unwrap();
            writer
                .start_file("world/level.dat", SimpleFileOptions::default())
                .unwrap();
            writer.write_all(world).unwrap();
            writer.finish().unwrap();
        }
        let upload = zip.into_inner();
        let upload_sha1 = hex::encode(Sha1::digest(&upload));
        let upload_uri = format!("/hosts/{host_id}/world-snapshot");
        let put = || {
            Request::builder()
                .method("PUT")
                .uri(&upload_uri)
                .header(header::CONTENT_TYPE, "application/zip")
                .header("X-DM-Session", session_id.to_string())
                .header("X-DM-Snapshot-Sequence", "1")
                .header("X-DM-Sync-Revision", "0")
                .header("X-DM-SHA1", &upload_sha1)
                .header("X-DM-Sync-Cycle", cycle_id.to_string())
                .body(Body::from(upload.clone()))
                .unwrap()
        };
        let app = router(state);
        let response = app.clone().oneshot(put()).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let value: serde_json::Value =
            serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes())
                .unwrap();
        assert_eq!(value["code"], 0);
        assert_eq!(value["data"]["apiVersion"], 2);
        assert!(
            value["data"]["manifestSha1"]
                .as_str()
                .is_some_and(|hash| hash.len() == 40)
        );
        assert!(value["data"]["expandedBytes"].as_u64().unwrap() > world.len() as u64);
        assert!(value["data"].get("archiveSha1").is_none());
        assert!(value["data"].get("archiveZipBytes").is_none());
        assert_eq!(value["data"]["sessionId"], session_id.to_string());
        assert_eq!(value["data"]["sequence"], 1);
        assert_eq!(value["data"]["observedColumns"], 0);
        assert_eq!(value["data"]["storedColumns"], 0);
        assert_eq!(value["data"]["totalColumns"], 0);
        assert!(value["data"]["complete"].as_bool().unwrap());
        let snapshot_id = value["data"]["snapshotId"].as_str().unwrap().to_owned();

        let retry = app.clone().oneshot(put()).await.unwrap();
        let retry_value: serde_json::Value =
            serde_json::from_slice(&retry.into_body().collect().await.unwrap().to_bytes()).unwrap();
        assert_eq!(retry_value["code"], 0);
        assert_eq!(retry_value["data"], value["data"]);

        let status = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/hosts/{host_id}/world-snapshot/status"))
                    .header("X-DM-Session", session_id.to_string())
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let status_value: serde_json::Value =
            serde_json::from_slice(&status.into_body().collect().await.unwrap().to_bytes())
                .unwrap();
        assert_eq!(status_value["code"], 0);
        assert_eq!(status_value["data"]["apiVersion"], 2);
        assert_eq!(status_value["data"]["lastUpload"]["apiVersion"], 2);
        assert!(
            status_value["data"]["limits"]
                .get("maxArchiveBytes")
                .is_none()
        );
        assert_eq!(status_value["data"]["latest"]["snapshotId"], snapshot_id);
        assert_eq!(status_value["data"]["storedColumns"], 0);
        assert!(status_value["data"]["complete"].as_bool().unwrap());

        let manifest = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!(
                        "/hosts/{host_id}/world-snapshot/{snapshot_id}/manifest"
                    ))
                    .header("X-DM-Session", session_id.to_string())
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let manifest_value: serde_json::Value =
            serde_json::from_slice(&manifest.into_body().collect().await.unwrap().to_bytes())
                .unwrap();
        assert_eq!(manifest_value["code"], 0);
        assert_eq!(manifest_value["data"]["apiVersion"], 2);
        assert_eq!(manifest_value["data"]["snapshotId"], snapshot_id);
        assert_eq!(manifest_value["data"]["manifest"]["formatVersion"], 5);
        assert_eq!(
            manifest_value["data"]["manifest"]["world"]["files"][0]["path"],
            "level.dat"
        );

        let download = app
            .oneshot(
                Request::builder()
                    .uri(format!("/hosts/{host_id}/world-snapshot/{snapshot_id}"))
                    .header("X-DM-Session", session_id.to_string())
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(download.status(), StatusCode::NOT_IMPLEMENTED);
        let unavailable: serde_json::Value =
            serde_json::from_slice(&download.into_body().collect().await.unwrap().to_bytes())
                .unwrap();
        assert_eq!(unavailable["code"], -1);
        assert_eq!(unavailable["data"]["reason"], "DownloadUnavailable");
    }

    #[tokio::test]
    async fn create_response_is_http_200_and_omits_null_data_on_error() {
        let dir = tempdir().unwrap();
        let config = Config {
            data_dir: dir.path().to_path_buf(),
            ..Config::default()
        };
        let app = router(AppState::new(config).unwrap());
        let (content_type, body) = multipart("Room", &zip_bytes());
        let request = Request::builder()
            .method("POST")
            .uri("/hosts")
            .header(header::CONTENT_TYPE, content_type)
            .body(Body::from(body))
            .unwrap();
        let response = app.clone().oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["code"], 0);
        assert!(value.get("data").is_some());

        let response = app
            .oneshot(
                Request::builder()
                    .uri("/hosts/nope")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["code"], -1);
        assert!(value.get("data").is_none());
    }

    #[tokio::test]
    async fn wrong_method_uses_application_response() {
        let dir = tempdir().unwrap();
        let config = Config {
            data_dir: dir.path().to_path_buf(),
            ..Config::default()
        };
        let response = router(AppState::new(config).unwrap())
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/info")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["code"], -1);
        assert!(value.get("data").is_none());
    }

    #[tokio::test]
    async fn sync_chunk_api_requires_session_and_returns_revisioned_mutations() {
        let dir = tempdir().unwrap();
        let config = Config {
            data_dir: dir.path().to_path_buf(),
            ..Config::default()
        };
        let state = AppState::new(config).unwrap();
        let host_id = Uuid::now_v7();
        std::fs::create_dir(state.store.host_root(host_id)).unwrap();
        std::fs::write(
            state.store.host_root(host_id).join("host.json"),
            serde_json::to_vec(&serde_json::json!({"id": host_id, "name": "room"})).unwrap(),
        )
        .unwrap();
        let session_id = Uuid::now_v7();
        let player_id = Uuid::now_v7();
        state.presence.heartbeat().await;
        state.presence.sync(vec![]).await.unwrap();
        state
            .presence
            .online(
                host_id,
                SessionRequest {
                    host_id,
                    session_id,
                    game_port: 25565,
                },
            )
            .await
            .unwrap();
        let app = router(state);
        let get_without_session = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/hosts/{host_id}/sync-chunks"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(get_without_session.status(), StatusCode::OK);
        let bytes = get_without_session
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes();
        let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["data"]["reason"], "InvalidRequest");

        let body = serde_json::json!({
            "sessionId": session_id,
            "expectedRevision": 0,
            "playerId": player_id,
            "chunk": {
                "dimensionId": "minecraft:overworld",
                "chunkX": -1,
                "chunkZ": 2
            }
        });
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("PUT")
                    .uri(format!("/hosts/{host_id}/sync-chunks"))
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(body.to_string()))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["code"], 0);
        assert_eq!(value["data"]["revision"], 1);
        assert_eq!(value["data"]["outcome"], "Added");

        let get = app
            .oneshot(
                Request::builder()
                    .uri(format!("/hosts/{host_id}/sync-chunks"))
                    .header("X-DM-Session", session_id.to_string())
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let bytes = get.into_body().collect().await.unwrap().to_bytes();
        let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["data"]["revision"], 1);
        assert_eq!(value["data"]["chunks"][0]["chunkX"], -1);
    }
}
