#![allow(clippy::manual_async_fn, clippy::result_large_err)]

use crate::{
    config::{Config, WorldSnapshotLimits},
    model::{
        CreatedHostData, HeartbeatData, HostBriefData, HostData, HostSessionRequest, InfoData, OfflineRequest,
        RegistrationRefusal, RegistrationRequest, Response, SessionListRequest, SessionRequest, SpeedTestUploadData, SyncChunkErrorData,
        SyncChunkErrorReason, SyncChunkLimits, SyncChunkMutationRequest, SyncChunkOutcome,
        SyncChunksData, TakeoverData, TakeoverErrorReason, TakeoverOutcome, TakeoverRequest,
    },
    mods::{self, ModFingerprint},
    presence::Presence,
    store::HostStore,
    sync_chunks::{MutationError, MutationOutcome, SyncChunkStore},
    takeover::{CandidateMetrics, ClaimOutcome, ReportError, Takeovers},
    tickets::{self, TicketPlayer},
    world_checkpoints::{self, WorldCheckpointStore},
    world_snapshots::{
        CommitError, CommitOutcome, ErrorReason as WorldSnapshotErrorReason, Metadata, Prepared,
        SnapshotRecord, UploadReceipt, WorldSnapshotStore,
    },
};
use axum::{
    Json, Router,
    body::{Body, to_bytes},
    extract::{DefaultBodyLimit, Extension, FromRequest, Multipart, Path, Request, State},
    http::{HeaderMap, StatusCode, header},
    middleware::{self, Next},
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
const MAX_FINGERPRINT_BYTES: usize = 1024 * 1024;

#[derive(Clone)]
pub struct AppState {
    pub config: Config,
    pub store: HostStore,
    pub presence: Presence,
    upload_slots: Arc<Semaphore>,
    host_upload_slots: Arc<StdMutex<HashMap<Uuid, Arc<Semaphore>>>>,
    pub sync_chunks: SyncChunkStore,
    pub world_snapshots: WorldSnapshotStore,
    pub world_checkpoints: WorldCheckpointStore,
    pub commit_gate: Arc<Mutex<()>>,
    pub takeovers: Takeovers,
}

impl AppState {
    pub fn new(config: Config) -> anyhow::Result<Self> {
        let store = HostStore::open(&config.data_dir)?;
        let world_snapshots = WorldSnapshotStore::new(store.clone(), config.world_snapshot);
        world_snapshots.recover()?;
        let world_checkpoints = WorldCheckpointStore::new(store.clone(), config.world_checkpoint);
        world_checkpoints.recover()?;
        Ok(Self {
            store: store.clone(),
            config,
            presence: Presence::default(),
            upload_slots: Arc::new(Semaphore::new(2)),
            host_upload_slots: Arc::new(StdMutex::new(HashMap::new())),
            sync_chunks: SyncChunkStore::new(store),
            world_snapshots,
            world_checkpoints,
            commit_gate: Arc::new(Mutex::new(())),
            takeovers: Takeovers::default(),
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
    // Anything a player can reach needs a ticket from the RDI master.
    let player_routes = Router::new()
        .route("/hosts", get(list_my_hosts).post(create_host))
        .route("/hosts/{host_id}", get(get_host).delete(delete_host))
        .route("/hosts/{host_id}/mods", get(get_host_mods))
        .route("/hosts/{host_id}/world-init", get(download_world_init))
        .route(
            "/hosts/{host_id}/world-checkpoint/download",
            get(download_world_checkpoint),
        )
        .route(
            "/hosts/{host_id}/candidates/{candidate_id}",
            put(report_candidate),
        )
        .route("/hosts/{host_id}/takeover", post(claim_takeover))
        .route(
            "/hosts/{host_id}/takeover/{claim_id}",
            put(renew_takeover).delete(release_takeover),
        )
        .route("/speedtest/download", get(speed_test_download))
        .route("/speedtest/upload", post(speed_test_upload))
        .route_layer(middleware::from_fn_with_state(state.clone(), require_player));
    // Only a host that passed registration knows its session id, and these routes check it. They
    // need no ticket, so a running room keeps saving while the RDI master is away.
    let session_routes = Router::new()
        .route("/hosts/{host_id}/leaving", post(announce_leaving))
        .route(
            "/hosts/{host_id}/world-checkpoint/status",
            get(world_checkpoint_status),
        )
        .route(
            "/hosts/{host_id}/world-checkpoint/files",
            get(world_checkpoint_files),
        )
        .route(
            "/hosts/{host_id}/world-checkpoint/regions",
            get(world_checkpoint_regions),
        )
        .route(
            "/hosts/{host_id}/world-checkpoint/regions/{*path}",
            get(world_checkpoint_region),
        )
        .route(
            "/hosts/{host_id}/world-checkpoint",
            put(upload_world_checkpoint),
        )
        .route(
            "/hosts/{host_id}/sync-chunks",
            get(get_sync_chunks)
                .put(add_sync_chunk)
                .delete(remove_sync_chunk),
        );
    let gateway_routes = Router::new()
        .route("/gateway/heartbeat", post(gateway_heartbeat))
        .route(
            "/gateway/hosts/{host_id}",
            put(gateway_online).delete(gateway_offline),
        )
        .route(
            "/gateway/hosts/{host_id}/registration",
            post(gateway_registration),
        )
        .route("/gateway/sessions", put(gateway_sync))
        .route_layer(middleware::from_fn_with_state(state.clone(), require_gateway));
    Router::new()
        .route("/info", get(info))
        .merge(player_routes)
        .merge(session_routes)
        .merge(gateway_routes)
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

fn unauthorized() -> HttpResponse {
    let mut response = error("authentication required");
    *response.status_mut() = StatusCode::UNAUTHORIZED;
    response
}

fn bearer_token(headers: &HeaderMap) -> Option<&str> {
    headers
        .get(header::AUTHORIZATION)?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
}

async fn require_player(
    State(state): State<AppState>,
    mut request: Request,
    next: Next,
) -> HttpResponse {
    let player = bearer_token(request.headers()).ok_or(()).and_then(|ticket| {
        state
            .config
            .ticket_keys
            .verify(ticket, tickets::AUDIENCE_API, None)
            .map_err(|_| ())
    });
    match player {
        Ok(player) => {
            request.extensions_mut().insert(player);
            next.run(request).await
        }
        Err(()) => unauthorized(),
    }
}

async fn require_gateway(
    State(state): State<AppState>,
    request: Request,
    next: Next,
) -> HttpResponse {
    let authorized = state
        .config
        .gateway_token
        .as_deref()
        .zip(bearer_token(request.headers()))
        .is_some_and(|(expected, presented)| constant_time_eq(expected, presented));
    if authorized {
        next.run(request).await
    } else {
        unauthorized()
    }
}

/// Takes the same time wherever the values differ, so a guess cannot be refined byte by byte.
fn constant_time_eq(expected: &str, presented: &str) -> bool {
    expected.len() == presented.len()
        && expected
            .bytes()
            .zip(presented.bytes())
            .fold(0_u8, |difference, (a, b)| difference | (a ^ b))
            == 0
}

async fn info(State(state): State<AppState>) -> HttpResponse {
    json(Response::ok(InfoData {
        public_host: state.config.public_host.clone(),
        tunnel_port: state.config.tunnel_port,
        world_checkpoint_api_version: world_checkpoints::API_VERSION,
        world_checkpoint_limits: state.config.world_checkpoint,
        server_time_seconds: tickets::unix_seconds(),
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
    Extension(player): Extension<TicketPlayer>,
    SafeMultipart(mut multipart): SafeMultipart,
) -> HttpResponse {
    let _permit = match state.upload_slots.clone().try_acquire_owned() {
        Ok(permit) => permit,
        Err(_) => return error("too many uploads"),
    };
    // Checked again when the room is published; this saves a player from uploading for nothing.
    let limit_store = state.store.clone();
    let max_rooms = state.config.max_hosts_per_player;
    match tokio::task::spawn_blocking(move || limit_store.check_room_limit(player.id, max_rooms)).await {
        Ok(Ok(())) => {}
        Ok(Err(err)) => return error(err.to_string()),
        Err(_) => return error("cannot read rooms"),
    }
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
    let mut fingerprint = None;
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
        } else if field_name == "modFingerprint" {
            let parsed = match field.text().await {
                Ok(value) if value.len() <= MAX_FINGERPRINT_BYTES => {
                    serde_json::from_str::<ModFingerprint>(&value).map_err(anyhow::Error::from)
                }
                Ok(_) => Err(anyhow::anyhow!("mod fingerprint is too large")),
                Err(err) => Err(anyhow::Error::from(err)),
            };
            match parsed.and_then(|value| value.validate().map(|()| value)) {
                Ok(value) => fingerprint = Some(value),
                Err(_) => {
                    return finish_upload_error(&upload_path, "invalid modFingerprint field").await;
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
    let created = tokio::task::spawn_blocking(move || {
        store.create_from_upload(
            name,
            Some(player.id),
            fingerprint.as_ref(),
            &upload_for_task,
            max_rooms,
        )
    })
    .await;
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

/// Only the rooms the player created: a room ID is enough to join or take over a room, so other
/// players' rooms must not be listed.
async fn list_my_hosts(
    State(state): State<AppState>,
    Extension(player): Extension<TicketPlayer>,
) -> HttpResponse {
    let store = state.store.clone();
    let records = match tokio::task::spawn_blocking(move || store.list_created_by(player.id)).await {
        Ok(Ok(records)) => records,
        Ok(Err(err)) => {
            tracing::error!(error = %format!("{err:#}"), "cannot list rooms");
            return error("cannot read rooms");
        }
        Err(err) => {
            tracing::error!(error = %err, "room listing task failed");
            return error("cannot read rooms");
        }
    };
    let mut rooms = Vec::with_capacity(records.len());
    for record in records {
        let (presence, _) = state.presence.state_for(record.id).await;
        rooms.push(HostBriefData {
            id: record.id,
            name: record.name,
            state: presence,
        });
    }
    json(Response::ok(rooms))
}

/// Deletes an unused room for its creator. `commit_gate` orders this against the host coming
/// online, so a room cannot start being hosted between the check and the move.
async fn delete_host(
    State(state): State<AppState>,
    Extension(player): Extension<TicketPlayer>,
    Path(host_id): Path<String>,
) -> HttpResponse {
    const BUSY: &str = "房间正忙，请稍后再试";
    let Ok(id) = Uuid::parse_str(&host_id) else {
        return error("房间ID格式无效");
    };
    let _gate = state.commit_gate.lock().await;
    let record = match state.store.get(id) {
        Ok(Some(record)) => record,
        Ok(None) => return error("房间不存在"),
        Err(err) => {
            tracing::error!(host = %id, error = %format!("{err:#}"), "cannot read room to delete");
            return error("无法读取房间");
        }
    };
    if record.creator_id != Some(player.id) {
        return error("只有房间创建者才能删除房间");
    }
    match state.presence.state_for(id).await.0 {
        crate::model::PresenceState::Offline => {}
        crate::model::PresenceState::Online | crate::model::PresenceState::Leaving => {
            return error("房间还在运行，房主退出后才能删除");
        }
        crate::model::PresenceState::Unavailable => {
            return error("暂时无法确认房间状态，请稍后再试");
        }
    }
    if state.takeovers.state(id, Instant::now()).is_some() {
        return error("房间正在被接管，请稍后再试");
    }
    let checkpoints = state.world_checkpoints.clone();
    let store = state.store.clone();
    let moved = tokio::task::spawn_blocking(move || {
        checkpoints.try_while_idle(|| store.retire(id))
    })
    .await;
    match moved {
        Ok(Some(Ok(()))) => {}
        Ok(None) => return error(BUSY),
        // On Windows this also happens while someone is still downloading the room's files.
        Ok(Some(Err(err))) => {
            tracing::error!(host = %id, error = %format!("{err:#}"), "cannot delete room");
            return error(BUSY);
        }
        Err(err) => {
            tracing::error!(host = %id, error = %err, "room deletion task failed");
            return error(BUSY);
        }
    }
    state.takeovers.forget(id);
    state
        .host_upload_slots
        .lock()
        .expect("host upload slot registry poisoned")
        .remove(&id);
    tracing::info!(host = %id, player = %player.id, "room deleted");
    json(Response::<()>::empty())
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
    let checkpoints = state.world_checkpoints.clone();
    let host_root = state.store.host_root(id);
    let (checkpoint_sequence, checkpoint_cycle_id, mod_fingerprint) =
        match tokio::task::spawn_blocking(move || {
            Ok::<_, anyhow::Error>((checkpoints.status(id)?, mods::read(&host_root)?))
        })
        .await
        {
            Ok(Ok((status, room_mods))) => (
                status.applied_sequence,
                status.last_upload.map(|receipt| receipt.cycle_id),
                room_mods.map(|room| room.fingerprint.registries.overall),
            ),
            _ => return error("cannot read host"),
        };
    json(Response::ok(HostData {
        id: host.id,
        name: host.name,
        state: presence,
        game_port,
        checkpoint_sequence,
        checkpoint_cycle_id,
        mod_fingerprint,
        takeover: state.takeovers.state(id, Instant::now()),
    }))
}

/// The room's full mod fingerprint, for listing which mods differ; `data` is absent for rooms
/// that have not recorded one yet.
async fn get_host_mods(State(state): State<AppState>, Path(host_id): Path<String>) -> HttpResponse {
    let id = match Uuid::parse_str(&host_id) {
        Ok(id) => id,
        Err(_) => return error("invalid host id"),
    };
    match state.store.get(id) {
        Ok(Some(_)) => {}
        Ok(None) => return error("host not found"),
        Err(_) => return error("cannot read host"),
    }
    let host_root = state.store.host_root(id);
    match tokio::task::spawn_blocking(move || mods::read(&host_root)).await {
        Ok(Ok(Some(room))) => json(Response::ok(room)),
        Ok(Ok(None)) => json(Response::<()>::empty()),
        _ => error("cannot read room mods"),
    }
}

/// The host announces a graceful shutdown before its final checkpoint, so guests it is about to
/// disconnect keep waiting instead of seeing the room online and giving up on taking it over.
async fn announce_leaving(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    headers: HeaderMap,
) -> HttpResponse {
    let id = match Uuid::parse_str(&host_id) {
        Ok(id) => id,
        Err(_) => return error("invalid host id"),
    };
    let session = match header_session(&headers) {
        Ok(session) => session,
        Err(response) => return response,
    };
    if state.presence.leaving(id, session).await {
        json(Response::<()>::empty())
    } else {
        error("host session is unavailable")
    }
}

fn takeover_error(reason: TakeoverErrorReason, message: impl Into<String>) -> HttpResponse {
    json(Response::<()>::error_data(
        message,
        serde_json::json!({"reason": reason}),
    ))
}

fn takeover_host(state: &AppState, host_id: &str) -> Result<Uuid, HttpResponse> {
    let id = Uuid::parse_str(host_id)
        .map_err(|_| takeover_error(TakeoverErrorReason::InvalidRequest, "invalid host id"))?;
    match state.store.get(id) {
        Ok(Some(_)) => Ok(id),
        Ok(None) => Err(takeover_error(
            TakeoverErrorReason::HostNotFound,
            "host not found",
        )),
        Err(_) => Err(takeover_error(
            TakeoverErrorReason::StorageError,
            "cannot read host",
        )),
    }
}

fn duration_ms(value: std::time::Duration) -> u64 {
    u64::try_from(value.as_millis()).unwrap_or(u64::MAX)
}

async fn report_candidate(
    State(state): State<AppState>,
    Extension(player): Extension<TicketPlayer>,
    Path((host_id, candidate_id)): Path<(String, String)>,
    SafeJson(metrics): SafeJson<CandidateMetrics>,
) -> HttpResponse {
    let host = match takeover_host(&state, &host_id) {
        Ok(value) => value,
        Err(response) => return response,
    };
    let Ok(candidate) = Uuid::parse_str(&candidate_id) else {
        return takeover_error(TakeoverErrorReason::InvalidRequest, "invalid candidate id");
    };
    if let Err(message) = metrics.validate() {
        return takeover_error(TakeoverErrorReason::InvalidRequest, message);
    }
    match state
        .takeovers
        .report(host, candidate, player.id, metrics, Instant::now())
    {
        Ok(()) => json(Response::<()>::empty()),
        Err(ReportError::TooManyCandidates) => takeover_error(
            TakeoverErrorReason::TooManyCandidates,
            "too many takeover candidates",
        ),
        Err(ReportError::NotYourCandidate) => takeover_error(
            TakeoverErrorReason::InvalidRequest,
            "candidate id belongs to another player",
        ),
    }
}

async fn claim_takeover(
    State(state): State<AppState>,
    Extension(player): Extension<TicketPlayer>,
    Path(host_id): Path<String>,
    SafeJson(request): SafeJson<TakeoverRequest>,
) -> HttpResponse {
    let host = match takeover_host(&state, &host_id) {
        Ok(value) => value,
        Err(response) => return response,
    };
    match state.presence.state_for(host).await.0 {
        crate::model::PresenceState::Offline => {}
        crate::model::PresenceState::Online => {
            return takeover_error(TakeoverErrorReason::HostOnline, "host is online");
        }
        crate::model::PresenceState::Unavailable => {
            return takeover_error(
                TakeoverErrorReason::GatewayUnavailable,
                "gateway presence is unavailable",
            );
        }
        crate::model::PresenceState::Leaving => {
            return takeover_error(
                TakeoverErrorReason::HostLeaving,
                "host is saving its final checkpoint",
            );
        }
    }
    let checkpoints = state.world_checkpoints.clone();
    let world_init = state.store.world_init(host);
    let host_root = state.store.host_root(host);
    let (world_bytes, room_fingerprint) = match tokio::task::spawn_blocking(move || {
        let bytes = match checkpoints.world_bytes(host)? {
            Some(bytes) => bytes,
            None => std::fs::metadata(world_init)
                .map(|value| value.len())
                .unwrap_or(0),
        };
        let fingerprint = mods::read(&host_root)?.map(|room| room.fingerprint.registries.overall);
        Ok::<_, anyhow::Error>((bytes, fingerprint))
    })
    .await
    {
        Ok(Ok(value)) => value,
        Ok(Err(err)) => return takeover_error(TakeoverErrorReason::StorageError, err.to_string()),
        Err(err) => return takeover_error(TakeoverErrorReason::StorageError, err.to_string()),
    };
    let outcome = state
        .takeovers
        .claim(
            host,
            request.candidate_id,
            player.id,
            world_bytes,
            room_fingerprint.as_deref(),
            Instant::now(),
        );
    let data = match outcome {
        ClaimOutcome::Granted { claim_id, lease } => TakeoverData {
            outcome: TakeoverOutcome::Granted,
            claim_id: Some(claim_id),
            lease_ms: Some(duration_ms(lease)),
            retry_after_ms: None,
        },
        ClaimOutcome::Pending { retry_after } => TakeoverData {
            outcome: TakeoverOutcome::Pending,
            claim_id: None,
            lease_ms: None,
            retry_after_ms: Some(duration_ms(retry_after)),
        },
        ClaimOutcome::Claimed { retry_after } => TakeoverData {
            outcome: TakeoverOutcome::Claimed,
            claim_id: None,
            lease_ms: None,
            retry_after_ms: Some(duration_ms(retry_after)),
        },
        ClaimOutcome::Incompatible => TakeoverData {
            outcome: TakeoverOutcome::Incompatible,
            claim_id: None,
            lease_ms: None,
            retry_after_ms: None,
        },
    };
    json(Response::ok(data))
}

async fn renew_takeover(
    State(state): State<AppState>,
    Extension(player): Extension<TicketPlayer>,
    Path((host_id, claim_id)): Path<(String, String)>,
) -> HttpResponse {
    let host = match takeover_host(&state, &host_id) {
        Ok(value) => value,
        Err(response) => return response,
    };
    let Ok(claim) = Uuid::parse_str(&claim_id) else {
        return takeover_error(TakeoverErrorReason::InvalidRequest, "invalid claim id");
    };
    match state.takeovers.renew(host, claim, player.id, Instant::now()) {
        Some(lease) => json(Response::ok(
            serde_json::json!({"leaseMs": duration_ms(lease)}),
        )),
        None => takeover_error(
            TakeoverErrorReason::ClaimLost,
            "takeover claim is no longer valid",
        ),
    }
}

async fn release_takeover(
    State(state): State<AppState>,
    Extension(player): Extension<TicketPlayer>,
    Path((host_id, claim_id)): Path<(String, String)>,
) -> HttpResponse {
    let host = match takeover_host(&state, &host_id) {
        Ok(value) => value,
        Err(response) => return response,
    };
    let Ok(claim) = Uuid::parse_str(&claim_id) else {
        return takeover_error(TakeoverErrorReason::InvalidRequest, "invalid claim id");
    };
    state.takeovers.release(host, claim, player.id);
    json(Response::<()>::empty())
}

const SPEED_TEST_BYTES: usize = 2 * 1024 * 1024;
const SPEED_TEST_MAX_UPLOAD_BYTES: usize = 4 * 1024 * 1024;

/// Incompressible bytes, so a compressing proxy cannot inflate the measured speed.
fn speed_test_payload() -> &'static [u8] {
    static PAYLOAD: std::sync::OnceLock<Vec<u8>> = std::sync::OnceLock::new();
    PAYLOAD.get_or_init(|| {
        let mut value = 0x9e37_79b9_7f4a_7c15_u64;
        (0..SPEED_TEST_BYTES)
            .map(|_| {
                value ^= value << 13;
                value ^= value >> 7;
                value ^= value << 17;
                (value >> 24) as u8
            })
            .collect()
    })
}

async fn speed_test_download() -> HttpResponse {
    (
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, "application/octet-stream"),
            (header::CACHE_CONTROL, "no-store"),
        ],
        Body::from(speed_test_payload()),
    )
        .into_response()
}

async fn speed_test_upload(request: Request) -> HttpResponse {
    match to_bytes(request.into_body(), SPEED_TEST_MAX_UPLOAD_BYTES).await {
        Ok(bytes) => json(Response::ok(SpeedTestUploadData {
            bytes: bytes.len() as u64,
        })),
        Err(_) => error("speed test upload is too large"),
    }
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
struct WorldCheckpointStatusData {
    api_version: u32,
    last_upload: Option<world_checkpoints::UploadReceipt>,
    applied_sequence: i64,
    baseline_complete: bool,
    limits: crate::config::WorldCheckpointLimits,
}

#[derive(serde::Serialize)]
struct WorldCheckpointFilesData {
    files: Vec<world_checkpoints::WorldFile>,
    directories: Vec<String>,
}

#[derive(serde::Serialize)]
struct WorldCheckpointRegionsData {
    regions: Vec<RegionDigestData>,
}

#[derive(serde::Serialize)]
struct RegionDigestData {
    path: String,
    digest: String,
}

#[derive(serde::Serialize)]
struct RegionSlotData {
    timestamp: i32,
    #[serde(rename = "sectorCount")]
    sector_count: u8,
}

async fn checkpoint_session(
    state: &AppState,
    host_id: Uuid,
    headers: &HeaderMap,
) -> Result<Uuid, HttpResponse> {
    let session = header_session(headers)?;
    if !state.presence.has_active_session(host_id, session).await {
        return Err(world_checkpoint_error(
            world_checkpoints::ErrorReason::SessionUnavailable,
            "host session is unavailable",
        ));
    }
    Ok(session)
}

fn world_checkpoint_error(
    reason: world_checkpoints::ErrorReason,
    message: impl Into<String>,
) -> HttpResponse {
    json(Response::<()>::error_data(
        message,
        serde_json::json!({"reason": reason}),
    ))
}

async fn world_checkpoint_status(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    headers: HeaderMap,
) -> HttpResponse {
    let host_id = match Uuid::parse_str(&host_id) {
        Ok(value) => value,
        Err(_) => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::InvalidRequest,
                "invalid host id",
            );
        }
    };
    if let Err(response) = checkpoint_session(&state, host_id, &headers).await {
        return response;
    }
    let store = state.world_checkpoints.clone();
    // A host that sees an unfinished checkpoint waits instead of uploading, so this is what retries it.
    let status = tokio::task::spawn_blocking(move || {
        if let Err(error) = store.apply_pending(host_id) {
            tracing::error!(host = %host_id, error = %format!("{error:#}"), "world checkpoint is still not applied");
        }
        store.status(host_id)
    });
    match status.await {
        Ok(Ok(status)) => json(Response::ok(WorldCheckpointStatusData {
            api_version: world_checkpoints::API_VERSION,
            last_upload: status.last_upload,
            applied_sequence: status.applied_sequence,
            baseline_complete: status.baseline_complete,
            limits: status.limits,
        })),
        Ok(Err(error)) => world_checkpoint_error(
            world_checkpoints::ErrorReason::StorageError,
            error.to_string(),
        ),
        Err(error) => world_checkpoint_error(
            world_checkpoints::ErrorReason::StorageError,
            error.to_string(),
        ),
    }
}

async fn world_checkpoint_files(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    headers: HeaderMap,
) -> HttpResponse {
    let host_id = match Uuid::parse_str(&host_id) {
        Ok(value) => value,
        Err(_) => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::InvalidRequest,
                "invalid host id",
            );
        }
    };
    if let Err(response) = checkpoint_session(&state, host_id, &headers).await {
        return response;
    }
    let store = state.world_checkpoints.clone();
    match tokio::task::spawn_blocking(move || store.files(host_id)).await {
        Ok(Ok((files, directories))) => json(Response::ok(WorldCheckpointFilesData {
            files,
            directories,
        })),
        Ok(Err(error)) => world_checkpoint_error(
            world_checkpoints::ErrorReason::StorageError,
            error.to_string(),
        ),
        Err(error) => world_checkpoint_error(
            world_checkpoints::ErrorReason::StorageError,
            error.to_string(),
        ),
    }
}

async fn world_checkpoint_regions(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    headers: HeaderMap,
) -> HttpResponse {
    let host_id = match Uuid::parse_str(&host_id) {
        Ok(value) => value,
        Err(_) => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::InvalidRequest,
                "invalid host id",
            );
        }
    };
    if let Err(response) = checkpoint_session(&state, host_id, &headers).await {
        return response;
    }
    let store = state.world_checkpoints.clone();
    match tokio::task::spawn_blocking(move || store.regions(host_id)).await {
        Ok(Ok(regions)) => json(Response::ok(WorldCheckpointRegionsData {
            regions: regions
                .into_iter()
                .map(|(path, digest)| RegionDigestData { path, digest })
                .collect(),
        })),
        Ok(Err(error)) => world_checkpoint_error(
            world_checkpoints::ErrorReason::StorageError,
            error.to_string(),
        ),
        Err(error) => world_checkpoint_error(
            world_checkpoints::ErrorReason::StorageError,
            error.to_string(),
        ),
    }
}

async fn world_checkpoint_region(
    State(state): State<AppState>,
    Path((host_id, path)): Path<(String, String)>,
    headers: HeaderMap,
) -> HttpResponse {
    let host_id = match Uuid::parse_str(&host_id) {
        Ok(value) => value,
        Err(_) => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::InvalidRequest,
                "invalid host id",
            );
        }
    };
    if let Err(response) = checkpoint_session(&state, host_id, &headers).await {
        return response;
    }
    let store = state.world_checkpoints.clone();
    match tokio::task::spawn_blocking(move || store.region_table(host_id, &path)).await {
        Ok(Ok(table)) => json(Response::ok(
            table
                .slots
                .into_iter()
                .map(|slot| RegionSlotData {
                    timestamp: slot.timestamp,
                    sector_count: slot.sector_count,
                })
                .collect::<Vec<_>>(),
        )),
        Ok(Err(error)) => world_checkpoint_error(
            world_checkpoints::ErrorReason::InvalidRequest,
            error.to_string(),
        ),
        Err(error) => world_checkpoint_error(
            world_checkpoints::ErrorReason::StorageError,
            error.to_string(),
        ),
    }
}

async fn upload_world_checkpoint(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    request: Request,
) -> HttpResponse {
    let host_id = match Uuid::parse_str(&host_id) {
        Ok(value) => value,
        Err(_) => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::InvalidRequest,
                "invalid host id",
            );
        }
    };
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
        return world_checkpoint_error(
            world_checkpoints::ErrorReason::InvalidRequest,
            "Content-Type must be application/zip",
        );
    }
    let session = match header_session(request.headers()) {
        Ok(value) => value,
        Err(response) => return response,
    };
    if !state.presence.has_active_session(host_id, session).await {
        return world_checkpoint_error(
            world_checkpoints::ErrorReason::SessionUnavailable,
            "host session is unavailable",
        );
    }
    let _host_permit = match state.host_upload_slot(host_id).try_acquire_owned() {
        Ok(permit) => permit,
        Err(_) => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::Busy,
                "checkpoint upload for this host is busy",
            );
        }
    };
    let _permit = match state.upload_slots.clone().try_acquire_owned() {
        Ok(permit) => permit,
        Err(_) => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::Busy,
                "checkpoint upload is busy",
            );
        }
    };
    let sequence = request
        .headers()
        .get("x-dm-checkpoint-sequence")
        .or_else(|| request.headers().get("x-dm-snapshot-sequence"))
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<i64>().ok())
        .filter(|v| *v > 0);
    let sequence = match sequence {
        Some(value) => value,
        None => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::InvalidRequest,
                "invalid checkpoint sequence",
            );
        }
    };
    let cycle = match request
        .headers()
        .get("x-dm-sync-cycle")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| Uuid::parse_str(v).ok())
    {
        Some(value) if value.get_version_num() == 7 => value,
        Some(_) | None => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::InvalidRequest,
                "invalid cycle id",
            );
        }
    };
    let declared_sha1 = match request
        .headers()
        .get("x-dm-sha1")
        .and_then(|v| v.to_str().ok())
        .filter(|v| {
            v.len() == 40
                && v.bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        }) {
        Some(value) => value.to_owned(),
        None => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::InvalidRequest,
                "invalid X-DM-SHA1",
            );
        }
    };
    let staging = state.store.staging_path().join(format!(
        "world-checkpoint-{}-{}",
        std::process::id(),
        Uuid::now_v7()
    ));
    let mut file = match tokio::fs::File::create(&staging).await {
        Ok(value) => value,
        Err(error) => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::StorageError,
                error.to_string(),
            );
        }
    };
    let mut body = request.into_body();
    let mut hasher = Sha1::new();
    let mut total = 0u64;
    while let Some(frame) = body.frame().await {
        let data = match frame {
            Ok(frame) => match frame.into_data() {
                Ok(data) => data,
                Err(_) => {
                    let _ = tokio::fs::remove_file(&staging).await;
                    return world_checkpoint_error(
                        world_checkpoints::ErrorReason::InvalidRequest,
                        "invalid request body",
                    );
                }
            },
            Err(error) => {
                let _ = tokio::fs::remove_file(&staging).await;
                return world_checkpoint_error(
                    world_checkpoints::ErrorReason::InvalidRequest,
                    error.to_string(),
                );
            }
        };
        total = total.saturating_add(data.len() as u64);
        if total > state.config.world_checkpoint.max_upload_bytes {
            let _ = tokio::fs::remove_file(&staging).await;
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::TooLarge,
                "checkpoint upload is too large",
            );
        }
        hasher.update(&data);
        if let Err(error) = file.write_all(&data).await {
            let _ = tokio::fs::remove_file(&staging).await;
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::StorageError,
                error.to_string(),
            );
        }
    }
    if let Err(error) = file.sync_all().await {
        let _ = tokio::fs::remove_file(&staging).await;
        return world_checkpoint_error(
            world_checkpoints::ErrorReason::StorageError,
            error.to_string(),
        );
    }
    drop(file);
    if hex::encode(hasher.finalize()) != declared_sha1 {
        let _ = tokio::fs::remove_file(&staging).await;
        return world_checkpoint_error(
            world_checkpoints::ErrorReason::InvalidRequest,
            "checkpoint SHA-1 does not match header",
        );
    }
    let result = commit_world_checkpoint(
        &state,
        host_id,
        session,
        staging.clone(),
        sequence,
        cycle,
        declared_sha1,
        total,
    )
    .await;
    let _ = tokio::fs::remove_file(&staging).await;
    match result {
        Ok(receipt) => json(Response::ok(receipt)),
        Err((reason, message)) => world_checkpoint_error(reason, message),
    }
}

type CheckpointFailure = (world_checkpoints::ErrorReason, String);

fn checkpoint_failure(
    error: anyhow::Error,
    otherwise: world_checkpoints::ErrorReason,
) -> CheckpointFailure {
    use world_checkpoints::ErrorReason;
    let message = format!("{error:#}");
    let reason = if message.contains("journal is busy") {
        ErrorReason::Busy
    } else if message.contains("sequence must") {
        ErrorReason::SequenceConflict
    } else if message.contains("host not found") {
        ErrorReason::StorageError
    } else {
        otherwise
    };
    if matches!(reason, ErrorReason::StorageError) {
        return storage_failure(&message);
    }
    (reason, message)
}

/// Storage errors can name server paths, so the details stay in the log.
fn storage_failure(detail: &str) -> CheckpointFailure {
    tracing::error!(error = detail, "world checkpoint storage failed");
    (
        world_checkpoints::ErrorReason::StorageError,
        "world checkpoint storage failed".to_owned(),
    )
}

async fn run_checkpoint_task<T: Send + 'static>(
    otherwise: world_checkpoints::ErrorReason,
    task: impl FnOnce() -> anyhow::Result<T> + Send + 'static,
) -> Result<T, CheckpointFailure> {
    match tokio::task::spawn_blocking(task).await {
        Ok(result) => result.map_err(|error| checkpoint_failure(error, otherwise)),
        Err(error) => Err(storage_failure(&error.to_string())),
    }
}

/// Stores a fully received upload. The session is checked again under `commit_gate`, which host
/// changes also take, so an upload from a replaced host can never change the world after the new
/// host downloaded it. Only the fast durable step runs under that lock: it is shared by all rooms.
#[allow(clippy::too_many_arguments)]
async fn commit_world_checkpoint(
    state: &AppState,
    host: Uuid,
    session: Uuid,
    staged: std::path::PathBuf,
    sequence: i64,
    cycle: Uuid,
    sha1: String,
    bytes: u64,
) -> Result<world_checkpoints::UploadReceipt, CheckpointFailure> {
    use world_checkpoints::ErrorReason;
    let store = state.world_checkpoints.clone();
    let metadata = {
        let (store, staged) = (store.clone(), staged.clone());
        run_checkpoint_task(ErrorReason::InvalidRequest, move || {
            store.validate_upload(host, &staged, session, sequence, cycle)
        })
        .await?
    };
    // A retry of an unfinished checkpoint, or one left over before this upload, is finished first.
    let apply = |store: world_checkpoints::WorldCheckpointStore| {
        run_checkpoint_task(ErrorReason::StorageError, move || store.apply_pending(host))
    };
    apply(store.clone()).await?;
    let mut receipt = {
        let _gate = state.commit_gate.lock().await;
        if !state.presence.has_active_session(host, session).await {
            return Err((
                ErrorReason::SessionUnavailable,
                "host session is unavailable".to_owned(),
            ));
        }
        let store = store.clone();
        run_checkpoint_task(ErrorReason::StorageError, move || {
            store.accept(host, &staged, &metadata, &sha1, bytes)
        })
        .await?
    };
    apply(store).await?;
    receipt.applied_sequence = sequence;
    Ok(receipt)
}

async fn download_world_checkpoint(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
) -> HttpResponse {
    let host_id = match Uuid::parse_str(&host_id) {
        Ok(value) => value,
        Err(_) => {
            return world_checkpoint_error(
                world_checkpoints::ErrorReason::InvalidRequest,
                "invalid host id",
            );
        }
    };
    let store = state.world_checkpoints.clone();
    match tokio::task::spawn_blocking(move || store.download(host_id)).await {
        Ok(Ok(Some(download))) => {
            let mut response = (
                StatusCode::OK,
                [
                    (header::CONTENT_TYPE, "application/zip".to_owned()),
                    (
                        header::HeaderName::from_static("x-dm-checkpoint-sequence"),
                        download.sequence.to_string(),
                    ),
                ],
                Body::from(download.bytes),
            )
                .into_response();
            if let Some(cycle_id) = download.cycle_id {
                response.headers_mut().insert(
                    header::HeaderName::from_static("x-dm-checkpoint-cycle"),
                    header::HeaderValue::from_str(&cycle_id.to_string())
                        .expect("UUID is a valid header value"),
                );
            }
            response
        }
        Ok(Ok(None)) => {
            let mut response = world_checkpoint_error(
                world_checkpoints::ErrorReason::NoCheckpoint,
                "world checkpoint is not ready",
            );
            *response.status_mut() = StatusCode::NOT_FOUND;
            response
        }
        Ok(Err(error)) => {
            tracing::error!(host = %host_id, error = %format!("{error:#}"), "world checkpoint download failed");
            let mut response = world_checkpoint_error(
                world_checkpoints::ErrorReason::StorageError,
                "world checkpoint is temporarily unavailable",
            );
            *response.status_mut() = StatusCode::SERVICE_UNAVAILABLE;
            response
        }
        Err(error) => world_checkpoint_error(
            world_checkpoints::ErrorReason::StorageError,
            error.to_string(),
        ),
    }
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

/// The gateway asks this before starting a host session; storage errors must not read as "no such room".
async fn gateway_registration(
    State(state): State<AppState>,
    Path(host_id): Path<String>,
    SafeJson(request): SafeJson<RegistrationRequest>,
) -> HttpResponse {
    fn storage_failure(message: &str) -> HttpResponse {
        let mut response = error(message);
        *response.status_mut() = StatusCode::INTERNAL_SERVER_ERROR;
        response
    }
    fn refused(refusal: RegistrationRefusal) -> HttpResponse {
        json(Response::ok(serde_json::json!({ "allowed": false, "refusal": refusal })))
    }
    let id = match Uuid::parse_str(&host_id) {
        Ok(id) => id,
        Err(_) => return error("invalid host id"),
    };
    let record = match state.store.get(id) {
        Ok(Some(record)) => record,
        Ok(None) => return error("host not found"),
        Err(_) => return storage_failure("cannot read host"),
    };
    let Ok(player) = state
        .config
        .ticket_keys
        .verify(&request.ticket, tickets::AUDIENCE_TUNNEL, Some(id))
    else {
        return refused(RegistrationRefusal::Ticket);
    };
    if request.claim_id.is_none()
        && record.last_host_id.or(record.creator_id) != Some(player.id)
    {
        return refused(RegistrationRefusal::NotOwner);
    }
    if !state
        .takeovers
        .authorize_registration(id, request.claim_id, player.id, Instant::now())
    {
        return refused(RegistrationRefusal::Claim);
    }
    let store = state.store.clone();
    match tokio::task::spawn_blocking(move || store.set_last_host(id, player.id)).await {
        Ok(Ok(())) => json(Response::ok(serde_json::json!({ "allowed": true }))),
        Ok(Err(err)) => {
            tracing::error!(host = %id, error = %format!("{err:#}"), "cannot record the room's host");
            storage_failure("cannot record host")
        }
        Err(_) => storage_failure("cannot record host"),
    }
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
    // Checked under the gate so that a room deleted just now cannot come online.
    let _gate = state.commit_gate.lock().await;
    if !matches!(state.store.get(id), Ok(Some(_))) {
        return error("host not found");
    }
    let session = SessionRequest {
        host_id: id,
        session_id: request.session_id,
        game_port: request.game_port,
    };
    match state.presence.online(id, session).await {
        Ok(()) => {
            state.takeovers.host_online(id);
            json(Response::<()>::empty())
        }
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
    use crate::tickets::test_support::{TEST_PLAYER, test_key, valid_ticket};

    const TEST_GATEWAY_TOKEN: &str = "test-gateway-token";

    fn with_auth_config(mut state: AppState) -> AppState {
        state.config.ticket_keys.add(test_key().verifying_key());
        state.config.gateway_token = Some(TEST_GATEWAY_TOKEN.to_owned());
        state
    }

    /// The real router, with the credentials a well-behaved caller would send filled in.
    fn authed_router(state: AppState) -> Router {
        router(with_auth_config(state)).layer(axum::middleware::map_request(
            |mut request: Request| async move {
                if !request.headers().contains_key(header::AUTHORIZATION) {
                    let credential = if request.uri().path().starts_with("/gateway/") {
                        TEST_GATEWAY_TOKEN.to_owned()
                    } else {
                        valid_ticket(tickets::AUDIENCE_API, TEST_PLAYER, None)
                    };
                    request.headers_mut().insert(
                        header::AUTHORIZATION,
                        format!("Bearer {credential}").parse().unwrap(),
                    );
                }
                request
            },
        ))
    }

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
        let app = authed_router(AppState::new(config).unwrap());
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
    #[ignore = "v5 world-snapshot route retired in favor of world-checkpoint"]
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
        let app = authed_router(state);
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
        let app = authed_router(AppState::new(config).unwrap());
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

    fn auth_state() -> (tempfile::TempDir, AppState) {
        let dir = tempdir().unwrap();
        let config = Config {
            data_dir: dir.path().to_path_buf(),
            ..Config::default()
        };
        let state = with_auth_config(AppState::new(config).unwrap());
        (dir, state)
    }

    fn room_with_creator(state: &AppState, creator: Uuid) -> Uuid {
        let upload = state.store.staging_path().join(format!("{}.zip", Uuid::now_v7()));
        std::fs::write(&upload, zip_bytes()).unwrap();
        state
            .store
            .create_from_upload("room".into(), Some(creator), None, &upload, usize::MAX)
            .unwrap()
            .id
    }

    async fn status_with(app: &Router, method: &str, uri: &str, credential: Option<&str>) -> StatusCode {
        let mut builder = Request::builder().method(method).uri(uri);
        if let Some(credential) = credential {
            builder = builder.header(header::AUTHORIZATION, format!("Bearer {credential}"));
        }
        app.clone()
            .oneshot(builder.body(Body::empty()).unwrap())
            .await
            .unwrap()
            .status()
    }

    async fn register(app: &Router, room: Uuid, claim: Option<Uuid>, ticket: &str) -> serde_json::Value {
        let request = Request::builder()
            .method("POST")
            .uri(format!("/gateway/hosts/{room}/registration"))
            .header(header::CONTENT_TYPE, "application/json")
            .header(header::AUTHORIZATION, format!("Bearer {TEST_GATEWAY_TOKEN}"))
            .body(Body::from(serde_json::json!({"claimId": claim, "ticket": ticket}).to_string()))
            .unwrap();
        let response = app.clone().oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        serde_json::from_slice::<serde_json::Value>(&bytes).unwrap()["data"].clone()
    }

    #[tokio::test]
    async fn player_routes_need_an_api_ticket() {
        let (_dir, state) = auth_state();
        let room = room_with_creator(&state, TEST_PLAYER);
        let app = router(state);
        let api = valid_ticket(tickets::AUDIENCE_API, TEST_PLAYER, None);
        let tunnel = valid_ticket(tickets::AUDIENCE_TUNNEL, TEST_PLAYER, Some(room));
        let expired = crate::tickets::test_support::ticket(tickets::AUDIENCE_API, TEST_PLAYER, None, 1000);
        let candidate = Uuid::now_v7();
        let claim = Uuid::now_v7();
        for (method, uri) in [
            ("POST", "/hosts".to_owned()),
            ("GET", format!("/hosts/{room}")),
            ("GET", format!("/hosts/{room}/mods")),
            ("GET", format!("/hosts/{room}/world-init")),
            ("GET", format!("/hosts/{room}/world-checkpoint/download")),
            ("PUT", format!("/hosts/{room}/candidates/{candidate}")),
            ("POST", format!("/hosts/{room}/takeover")),
            ("PUT", format!("/hosts/{room}/takeover/{claim}")),
            ("DELETE", format!("/hosts/{room}/takeover/{claim}")),
            ("GET", "/speedtest/download".to_owned()),
            ("POST", "/speedtest/upload".to_owned()),
        ] {
            for bad in [None, Some("garbage"), Some(tunnel.as_str()), Some(expired.as_str()), Some(TEST_GATEWAY_TOKEN)] {
                assert_eq!(status_with(&app, method, &uri, bad).await, StatusCode::UNAUTHORIZED, "{method} {uri}");
            }
        }
        assert_eq!(
            status_with(&app, "GET", &format!("/hosts/{room}"), Some(&api)).await,
            StatusCode::OK
        );
    }

    #[tokio::test]
    async fn gateway_routes_need_the_gateway_credential() {
        let (_dir, state) = auth_state();
        let room = room_with_creator(&state, TEST_PLAYER);
        let app = router(state);
        let api = valid_ticket(tickets::AUDIENCE_API, TEST_PLAYER, None);
        for (method, uri) in [
            ("POST", "/gateway/heartbeat".to_owned()),
            ("PUT", format!("/gateway/hosts/{room}")),
            ("DELETE", format!("/gateway/hosts/{room}")),
            ("POST", format!("/gateway/hosts/{room}/registration")),
            ("PUT", "/gateway/sessions".to_owned()),
        ] {
            for bad in [None, Some("test-gateway-toke"), Some("test-gateway-tokex"), Some(api.as_str())] {
                assert_eq!(status_with(&app, method, &uri, bad).await, StatusCode::UNAUTHORIZED, "{method} {uri}");
            }
        }
        assert_eq!(
            status_with(&app, "POST", "/gateway/heartbeat", Some(TEST_GATEWAY_TOKEN)).await,
            StatusCode::OK
        );
    }

    #[tokio::test]
    async fn nothing_is_open_when_no_credentials_are_configured() {
        let dir = tempdir().unwrap();
        let config = Config {
            data_dir: dir.path().to_path_buf(),
            ..Config::default()
        };
        let app = router(AppState::new(config).unwrap());
        let ticket = valid_ticket(tickets::AUDIENCE_API, TEST_PLAYER, None);
        assert_eq!(status_with(&app, "GET", "/speedtest/download", Some(&ticket)).await, StatusCode::UNAUTHORIZED);
        assert_eq!(status_with(&app, "POST", "/gateway/heartbeat", Some("")).await, StatusCode::UNAUTHORIZED);
        assert_eq!(status_with(&app, "GET", "/info", None).await, StatusCode::OK);
    }

    #[tokio::test]
    async fn info_is_public_and_tells_the_server_time() {
        let (_dir, state) = auth_state();
        let response = router(state)
            .oneshot(Request::builder().uri("/info").body(Body::empty()).unwrap())
            .await
            .unwrap();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        let reported = value["data"]["serverTimeSeconds"].as_i64().unwrap();
        assert!((tickets::unix_seconds() - reported).abs() < 5);
    }

    async fn create_room(app: &Router, ticket: &str, claimed_creator: Option<Uuid>) -> serde_json::Value {
        let (content_type, mut body) = multipart("room", &zip_bytes());
        if let Some(creator) = claimed_creator {
            let field = format!(
                "--dm-test-boundary\r\nContent-Disposition: form-data; name=\"creatorId\"\r\n\r\n{creator}\r\n"
            );
            body.splice(0..0, field.into_bytes());
        }
        let request = Request::builder()
            .method("POST")
            .uri("/hosts")
            .header(header::CONTENT_TYPE, content_type)
            .header(header::AUTHORIZATION, format!("Bearer {ticket}"))
            .body(Body::from(body))
            .unwrap();
        let bytes = app.clone().oneshot(request).await.unwrap().into_body().collect().await.unwrap().to_bytes();
        serde_json::from_slice(&bytes).unwrap()
    }

    async fn player_call(app: &Router, method: &str, uri: &str, ticket: &str) -> serde_json::Value {
        let request = Request::builder()
            .method(method)
            .uri(uri)
            .header(header::AUTHORIZATION, format!("Bearer {ticket}"))
            .body(Body::empty())
            .unwrap();
        let bytes = app.clone().oneshot(request).await.unwrap().into_body().collect().await.unwrap().to_bytes();
        serde_json::from_slice(&bytes).unwrap()
    }

    async fn gateway_ready(state: &AppState) {
        state.presence.heartbeat().await;
        state.presence.sync(vec![]).await.unwrap();
    }

    async fn host_online(state: &AppState, host_id: Uuid) -> Uuid {
        let session_id = Uuid::now_v7();
        state
            .presence
            .online(host_id, SessionRequest { host_id, session_id, game_port: 25565 })
            .await
            .unwrap();
        session_id
    }

    #[tokio::test]
    async fn the_list_shows_only_the_players_own_rooms_newest_first() {
        let (_dir, state) = auth_state();
        gateway_ready(&state).await;
        let older = room_with_creator(&state, TEST_PLAYER);
        let newer = room_with_creator(&state, TEST_PLAYER);
        room_with_creator(&state, Uuid::now_v7());
        host_online(&state, newer).await;
        let app = router(state);

        let ticket = valid_ticket(tickets::AUDIENCE_API, TEST_PLAYER, None);
        let listed = player_call(&app, "GET", "/hosts", &ticket).await;
        assert_eq!(listed["code"], 0, "{listed}");
        let rooms = listed["data"].as_array().unwrap();
        assert_eq!(rooms.len(), 2, "{listed}");
        assert_eq!(rooms[0]["id"], newer.to_string());
        assert_eq!(rooms[0]["state"], "Online");
        assert_eq!(rooms[1]["id"], older.to_string());
        assert_eq!(rooms[1]["state"], "Offline");

        assert_eq!(status_with(&app, "GET", "/hosts", None).await, StatusCode::UNAUTHORIZED);
        let tunnel = valid_ticket(tickets::AUDIENCE_TUNNEL, TEST_PLAYER, Some(older));
        assert_eq!(status_with(&app, "GET", "/hosts", Some(&tunnel)).await, StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn deleting_moves_the_room_away_and_frees_a_room_slot() {
        let (dir, mut state) = auth_state();
        state.config.max_hosts_per_player = 1;
        gateway_ready(&state).await;
        let room = room_with_creator(&state, TEST_PLAYER);
        let app = router(state.clone());
        let ticket = valid_ticket(tickets::AUDIENCE_API, TEST_PLAYER, None);
        assert_eq!(create_room(&app, &ticket, None).await["code"], -1);

        let deleted = player_call(&app, "DELETE", &format!("/hosts/{room}"), &ticket).await;
        assert_eq!(deleted["code"], 0, "{deleted}");

        assert!(state.store.get(room).unwrap().is_none());
        assert!(dir.path().join("deleted-hosts").join(room.to_string()).join("host.json").is_file());
        assert_eq!(player_call(&app, "GET", "/hosts", &ticket).await["data"], serde_json::json!([]));
        let again = player_call(&app, "DELETE", &format!("/hosts/{room}"), &ticket).await;
        assert_eq!(again["msg"], "房间不存在");
        assert_eq!(create_room(&app, &ticket, None).await["code"], 0);
    }

    #[tokio::test]
    async fn only_the_creator_may_delete_a_room() {
        let (_dir, state) = auth_state();
        gateway_ready(&state).await;
        let room = room_with_creator(&state, TEST_PLAYER);
        let app = router(state.clone());
        let uri = format!("/hosts/{room}");

        assert_eq!(status_with(&app, "DELETE", &uri, None).await, StatusCode::UNAUTHORIZED);
        let other = valid_ticket(tickets::AUDIENCE_API, Uuid::now_v7(), None);
        let refused = player_call(&app, "DELETE", &uri, &other).await;
        assert_eq!(refused["msg"], "只有房间创建者才能删除房间");
        assert!(state.store.get(room).unwrap().is_some());
    }

    #[tokio::test]
    async fn a_room_in_use_or_of_unknown_state_cannot_be_deleted() {
        let (_dir, state) = auth_state();
        let room = room_with_creator(&state, TEST_PLAYER);
        let app = router(state.clone());
        let uri = format!("/hosts/{room}");
        let ticket = valid_ticket(tickets::AUDIENCE_API, TEST_PLAYER, None);

        // No gateway heartbeat yet, so nobody knows whether the room is being hosted.
        let unknown = player_call(&app, "DELETE", &uri, &ticket).await;
        assert_eq!(unknown["msg"], "暂时无法确认房间状态，请稍后再试");

        gateway_ready(&state).await;
        let session = host_online(&state, room).await;
        let online = player_call(&app, "DELETE", &uri, &ticket).await;
        assert_eq!(online["msg"], "房间还在运行，房主退出后才能删除");

        assert!(state.presence.leaving(room, session).await);
        let leaving = player_call(&app, "DELETE", &uri, &ticket).await;
        assert_eq!(leaving["msg"], "房间还在运行，房主退出后才能删除");

        state.presence.offline(room, session).await;
        let candidate = Uuid::now_v7();
        state.takeovers.claim(room, candidate, TEST_PLAYER, 0, None, Instant::now());
        let electing = player_call(&app, "DELETE", &uri, &ticket).await;
        assert_eq!(electing["msg"], "房间正在被接管，请稍后再试");

        assert!(state.store.get(room).unwrap().is_some());
    }

    #[tokio::test]
    async fn a_deleted_room_cannot_come_online() {
        let (_dir, state) = auth_state();
        gateway_ready(&state).await;
        let room = room_with_creator(&state, TEST_PLAYER);
        let app = router(state.clone());
        let ticket = valid_ticket(tickets::AUDIENCE_API, TEST_PLAYER, None);
        assert_eq!(player_call(&app, "DELETE", &format!("/hosts/{room}"), &ticket).await["code"], 0);

        let request = Request::builder()
            .method("PUT")
            .uri(format!("/gateway/hosts/{room}"))
            .header(header::AUTHORIZATION, format!("Bearer {TEST_GATEWAY_TOKEN}"))
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(
                serde_json::json!({ "sessionId": Uuid::now_v7(), "gamePort": 25565 }).to_string(),
            ))
            .unwrap();
        let bytes = app.oneshot(request).await.unwrap().into_body().collect().await.unwrap().to_bytes();
        let online: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(online["msg"], "host not found");
    }

    #[tokio::test]
    async fn a_room_belongs_to_the_ticket_holder_whatever_the_upload_claims() {
        let (_dir, state) = auth_state();
        let app = router(state.clone());
        let ticket = valid_ticket(tickets::AUDIENCE_API, TEST_PLAYER, None);
        let created = create_room(&app, &ticket, Some(Uuid::now_v7())).await;
        assert_eq!(created["code"], 0, "{created}");
        let id = Uuid::parse_str(created["data"]["id"].as_str().unwrap()).unwrap();
        assert_eq!(state.store.get(id).unwrap().unwrap().creator_id, Some(TEST_PLAYER));
    }

    #[tokio::test]
    async fn a_player_can_create_only_the_allowed_number_of_rooms() {
        let (_dir, state) = auth_state();
        let app = router(state.clone());
        let ticket = valid_ticket(tickets::AUDIENCE_API, TEST_PLAYER, None);
        for _ in 0..state.config.max_hosts_per_player {
            assert_eq!(create_room(&app, &ticket, None).await["code"], 0);
        }
        let refused = create_room(&app, &ticket, None).await;
        assert_eq!(refused["code"], -1);
        assert_eq!(refused["msg"], "你最多只能创建5个房间");

        let other = valid_ticket(tickets::AUDIENCE_API, Uuid::now_v7(), None);
        assert_eq!(create_room(&app, &other, None).await["code"], 0);
    }

    #[test]
    fn the_room_limit_holds_when_the_room_is_published() {
        let (_dir, state) = auth_state();
        room_with_creator(&state, TEST_PLAYER);
        let upload = state.store.staging_path().join("late.zip");
        std::fs::write(&upload, zip_bytes()).unwrap();
        let refused = state
            .store
            .create_from_upload("late".into(), Some(TEST_PLAYER), None, &upload, 1)
            .unwrap_err();
        assert_eq!(refused.to_string(), "你最多只能创建1个房间");
    }

    #[tokio::test]
    async fn registration_checks_the_ticket_and_who_may_host_without_a_claim() {
        let (_dir, state) = auth_state();
        let creator = TEST_PLAYER;
        let room = room_with_creator(&state, creator);
        let app = router(state.clone());
        let tunnel = |player| valid_ticket(tickets::AUDIENCE_TUNNEL, player, Some(room));
        let stranger = Uuid::now_v7();

        for bad in [
            String::new(),
            valid_ticket(tickets::AUDIENCE_API, creator, None),
            valid_ticket("join", creator, Some(room)),
            valid_ticket(tickets::AUDIENCE_TUNNEL, creator, Some(Uuid::now_v7())),
        ] {
            let refused = register(&app, room, None, &bad).await;
            assert_eq!(refused["allowed"], false);
            assert_eq!(refused["refusal"], "ticket");
        }
        assert_eq!(register(&app, room, None, &tunnel(stranger)).await["refusal"], "notOwner");
        assert_eq!(state.store.get(room).unwrap().unwrap().last_host_id, None);

        assert_eq!(register(&app, room, None, &tunnel(creator)).await["allowed"], true);
        assert_eq!(state.store.get(room).unwrap().unwrap().last_host_id, Some(creator));
    }

    #[tokio::test]
    async fn after_a_takeover_only_the_new_host_may_come_back_without_a_claim() {
        let (_dir, state) = auth_state();
        let creator = TEST_PLAYER;
        let new_host = Uuid::now_v7();
        let room = room_with_creator(&state, creator);
        let app = router(state.clone());
        let tunnel = |player| valid_ticket(tickets::AUDIENCE_TUNNEL, player, Some(room));
        let candidate = Uuid::now_v7();
        let start = Instant::now() - std::time::Duration::from_secs(10);
        state.takeovers.claim(room, candidate, new_host, 0, None, start);
        let ClaimOutcome::Granted { claim_id, .. } =
            state.takeovers.claim(room, candidate, new_host, 0, None, Instant::now())
        else {
            panic!("the only claimant should win");
        };

        assert_eq!(register(&app, room, Some(claim_id), &tunnel(creator)).await["refusal"], "claim");
        assert_eq!(register(&app, room, None, &tunnel(creator)).await["refusal"], "claim");
        assert_eq!(register(&app, room, Some(claim_id), &tunnel(new_host)).await["allowed"], true);
        assert_eq!(state.store.get(room).unwrap().unwrap().last_host_id, Some(new_host));
        state.takeovers.host_online(room);

        assert_eq!(register(&app, room, None, &tunnel(new_host)).await["allowed"], true);
        assert_eq!(register(&app, room, None, &tunnel(creator)).await["refusal"], "notOwner");
    }

    #[tokio::test]
    async fn another_player_cannot_touch_a_claim() {
        let (_dir, state) = auth_state();
        let winner = TEST_PLAYER;
        let room = room_with_creator(&state, winner);
        let app = router(state.clone());
        let candidate = Uuid::now_v7();
        let start = Instant::now() - std::time::Duration::from_secs(10);
        state.takeovers.claim(room, candidate, winner, 0, None, start);
        let ClaimOutcome::Granted { claim_id, .. } =
            state.takeovers.claim(room, candidate, winner, 0, None, Instant::now())
        else {
            panic!("the only claimant should win");
        };
        let other = valid_ticket(tickets::AUDIENCE_API, Uuid::now_v7(), None);
        let uri = format!("/hosts/{room}/takeover/{claim_id}");
        status_with(&app, "DELETE", &uri, Some(&other)).await;
        assert!(state.takeovers.renew(room, claim_id, winner, Instant::now()).is_some());
    }

    #[tokio::test]
    async fn wrong_method_uses_application_response() {
        let dir = tempdir().unwrap();
        let config = Config {
            data_dir: dir.path().to_path_buf(),
            ..Config::default()
        };
        let response = authed_router(AppState::new(config).unwrap())
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
        let app = authed_router(state);
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

    async fn call(
        app: &Router,
        method: &str,
        uri: String,
        body: Option<serde_json::Value>,
    ) -> serde_json::Value {
        let mut builder = Request::builder().method(method).uri(uri);
        if body.is_some() {
            builder = builder.header(header::CONTENT_TYPE, "application/json");
        }
        let request = builder
            .body(body.map_or_else(Body::empty, |value| Body::from(value.to_string())))
            .unwrap();
        let response = app.clone().oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        serde_json::from_slice(&bytes).unwrap()
    }

    #[tokio::test]
    async fn takeover_api_reports_candidates_and_opens_an_election_only_when_offline() {
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
            serde_json::to_vec(&serde_json::json!({"id": host_id, "name": "room", "creatorId": TEST_PLAYER})).unwrap(),
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
        let app = authed_router(state.clone());
        let candidate = Uuid::now_v7();
        let metrics = serde_json::json!({
            "startupMs": 40000,
            "maxMemoryBytes": 8u64 << 30,
            "cpuCores": 8,
            "rttMs": 30,
            "downloadBps": 5000000,
            "uploadBps": 1000000
        });
        let reported = call(
            &app,
            "PUT",
            format!("/hosts/{host_id}/candidates/{candidate}"),
            Some(metrics),
        )
        .await;
        assert_eq!(reported["code"], 0);
        let invalid = call(
            &app,
            "PUT",
            format!("/hosts/{host_id}/candidates/{candidate}"),
            Some(serde_json::json!({"maxMemoryBytes": 1, "cpuCores": 0})),
        )
        .await;
        assert_eq!(invalid["data"]["reason"], "InvalidRequest");

        let claim = serde_json::json!({"candidateId": candidate});
        let online = call(
            &app,
            "POST",
            format!("/hosts/{host_id}/takeover"),
            Some(claim.clone()),
        )
        .await;
        assert_eq!(online["data"]["reason"], "HostOnline");

        let host = call(&app, "GET", format!("/hosts/{host_id}"), None).await;
        assert_eq!(host["data"]["checkpointSequence"], 0);
        assert!(host["data"].get("checkpointCycleId").is_none());
        assert!(host["data"].get("takeover").is_none());

        assert!(state.presence.leaving(host_id, session_id).await);
        let leaving = call(
            &app,
            "POST",
            format!("/hosts/{host_id}/takeover"),
            Some(claim.clone()),
        )
        .await;
        assert_eq!(leaving["data"]["reason"], "HostLeaving");
        let host = call(&app, "GET", format!("/hosts/{host_id}"), None).await;
        assert_eq!(host["data"]["state"], "Leaving");
        assert!(host["data"].get("gamePort").is_none());

        state.presence.offline(host_id, session_id).await;
        let pending = call(
            &app,
            "POST",
            format!("/hosts/{host_id}/takeover"),
            Some(claim),
        )
        .await;
        assert_eq!(pending["data"]["outcome"], "Pending");
        assert!(pending["data"]["retryAfterMs"].as_u64().unwrap() <= 3000);
        let host = call(&app, "GET", format!("/hosts/{host_id}"), None).await;
        assert_eq!(host["data"]["takeover"], "Electing");

        let lost = call(
            &app,
            "PUT",
            format!("/hosts/{host_id}/takeover/{}", Uuid::now_v7()),
            None,
        )
        .await;
        assert_eq!(lost["data"]["reason"], "ClaimLost");

        let returning_owner = call(
            &app,
            "POST",
            format!("/gateway/hosts/{host_id}/registration"),
            Some(serde_json::json!({
                "claimId": null,
                "ticket": valid_ticket(tickets::AUDIENCE_TUNNEL, TEST_PLAYER, Some(host_id)),
            })),
        )
        .await;
        assert_eq!(returning_owner["data"]["allowed"], true);
        let stale_claim = call(
            &app,
            "POST",
            format!("/gateway/hosts/{host_id}/registration"),
            Some(serde_json::json!({
                "claimId": Uuid::now_v7(),
                "ticket": valid_ticket(tickets::AUDIENCE_TUNNEL, TEST_PLAYER, Some(host_id)),
            })),
        )
        .await;
        assert_eq!(stale_claim["data"]["allowed"], false);
        assert_eq!(stale_claim["data"]["refusal"], "claim");
        let unknown_room = call(
            &app,
            "POST",
            format!("/gateway/hosts/{}/registration", Uuid::now_v7()),
            Some(serde_json::json!({})),
        )
        .await;
        assert_ne!(unknown_room["code"], 0);

        let back = call(
            &app,
            "PUT",
            format!("/gateway/hosts/{host_id}"),
            Some(serde_json::json!({"sessionId": Uuid::now_v7(), "gamePort": 26001})),
        )
        .await;
        assert_eq!(back["code"], 0);
        let host = call(&app, "GET", format!("/hosts/{host_id}"), None).await;
        assert!(host["data"].get("takeover").is_none());

        let missing = call(
            &app,
            "POST",
            format!("/hosts/{}/takeover", Uuid::now_v7()),
            Some(serde_json::json!({"candidateId": candidate})),
        )
        .await;
        assert_eq!(missing["data"]["reason"], "HostNotFound");
    }

    /// A room with an active session and a staged checkpoint ready to commit.
    async fn checkpoint_fixture(
        dir: &std::path::Path,
    ) -> (AppState, Uuid, Uuid, Uuid, std::path::PathBuf, String, u64) {
        use std::io::Write;
        let state = AppState::new(Config {
            data_dir: dir.to_path_buf(),
            ..Config::default()
        })
        .unwrap();
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
        let cycle = Uuid::now_v7();
        let world = b"level";
        let metadata = serde_json::json!({
            "formatVersion": world_checkpoints::FORMAT_VERSION,
            "kind": "world-checkpoint",
            "hostId": host_id,
            "sessionId": session_id,
            "cycleId": cycle,
            "sequence": 1,
            "capturedAt": "2026-11-01T00:00:00Z",
            "baselineComplete": true,
            "files": [{"path": "level.dat", "bytes": world.len(), "sha1": hex_sha1(world)}],
            "removedFiles": [],
            "directories": [],
            "chunks": [],
            "clears": [],
        });
        let staged = dir.join("staged.zip");
        let mut output = std::fs::File::create(&staged).unwrap();
        let mut zip = zip::ZipWriter::new(&mut output);
        let options = zip::write::SimpleFileOptions::default();
        zip.start_file("metadata.json", options).unwrap();
        zip.write_all(&serde_json::to_vec(&metadata).unwrap()).unwrap();
        zip.start_file("world/level.dat", options).unwrap();
        zip.write_all(world).unwrap();
        zip.finish().unwrap();
        drop(output);
        let bytes = std::fs::read(&staged).unwrap();
        (state, host_id, session_id, cycle, staged, hex_sha1(&bytes), bytes.len() as u64)
    }

    fn hex_sha1(bytes: &[u8]) -> String {
        Sha1::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
    }

    #[tokio::test]
    async fn a_checkpoint_from_the_active_session_is_applied() {
        let dir = tempdir().unwrap();
        let (state, host, session, cycle, staged, sha1, bytes) = checkpoint_fixture(dir.path()).await;
        let receipt = commit_world_checkpoint(&state, host, session, staged, 1, cycle, sha1, bytes)
            .await
            .unwrap();
        assert_eq!(receipt.applied_sequence, 1);
        assert_eq!(state.world_checkpoints.status(host).unwrap().applied_sequence, 1);
    }

    #[tokio::test]
    async fn an_upload_finishing_after_the_host_was_replaced_changes_nothing() {
        let dir = tempdir().unwrap();
        let (state, host, session, cycle, staged, sha1, bytes) = checkpoint_fixture(dir.path()).await;
        // The upload passed the entry check; the host then went away and a new one came online.
        state.presence.offline(host, session).await;
        state
            .presence
            .online(
                host,
                SessionRequest {
                    host_id: host,
                    session_id: Uuid::now_v7(),
                    game_port: 25566,
                },
            )
            .await
            .unwrap();
        let (reason, _) = commit_world_checkpoint(&state, host, session, staged, 1, cycle, sha1, bytes)
            .await
            .unwrap_err();
        assert!(matches!(reason, world_checkpoints::ErrorReason::SessionUnavailable));
        let status = state.world_checkpoints.status(host).unwrap();
        assert_eq!(status.applied_sequence, 0);
        assert!(status.last_upload.is_none());
        assert!(state.world_checkpoints.download(host).unwrap().is_none());
    }

    #[tokio::test]
    async fn speed_test_routes_serve_and_accept_bounded_payloads() {
        let dir = tempdir().unwrap();
        let config = Config {
            data_dir: dir.path().to_path_buf(),
            ..Config::default()
        };
        let app = authed_router(AppState::new(config).unwrap());
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/speedtest/download")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(bytes.len(), SPEED_TEST_BYTES);
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/speedtest/upload")
                    .body(Body::from(vec![7_u8; 1024]))
                    .unwrap(),
            )
            .await
            .unwrap();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["data"]["bytes"], 1024);
        let response = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/speedtest/upload")
                    .body(Body::from(vec![0_u8; SPEED_TEST_MAX_UPLOAD_BYTES + 1]))
                    .unwrap(),
            )
            .await
            .unwrap();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["code"], -1);
    }
}
