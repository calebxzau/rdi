use crate::{
    config::Config,
    model::{
        CreatedHostData, HeartbeatData, HostData, HostSessionRequest, InfoData, OfflineRequest,
        Response, SessionListRequest, SessionRequest,
    },
    presence::Presence,
    store::HostStore,
};
use axum::{
    Json, Router,
    body::Body,
    extract::{DefaultBodyLimit, FromRequest, Multipart, Path, Request, State},
    http::{StatusCode, header},
    response::{IntoResponse, Response as HttpResponse},
    routing::{get, post, put},
};
use serde::de::DeserializeOwned;
use std::{
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::SystemTime,
};
use tokio::{io::AsyncWriteExt, sync::Semaphore};
use tokio_util::io::ReaderStream;
use uuid::Uuid;

static UPLOAD_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Clone)]
pub struct AppState {
    pub config: Config,
    pub store: HostStore,
    pub presence: Presence,
    upload_slots: Arc<Semaphore>,
}

impl AppState {
    pub fn new(config: Config) -> anyhow::Result<Self> {
        Ok(Self {
            store: HostStore::open(&config.data_dir)?,
            config,
            presence: Presence::default(),
            upload_slots: Arc::new(Semaphore::new(2)),
        })
    }
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/info", get(info))
        .route("/hosts", post(create_host))
        .route("/hosts/{host_id}", get(get_host))
        .route("/hosts/{host_id}/world-init", get(download_world_init))
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
    }))
}

#[derive(Debug)]
struct SafeMultipart(Multipart);

struct SafeJson<T>(T);

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

async fn gateway_heartbeat(State(state): State<AppState>) -> HttpResponse {
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
    state.presence.offline(id, request.session_id).await;
    json(Response::<()>::empty())
}

async fn gateway_sync(
    State(state): State<AppState>,
    SafeJson(request): SafeJson<SessionListRequest>,
) -> HttpResponse {
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

    #[tokio::test]
    async fn create_response_is_http_200_and_omits_null_data_on_error() {
        let dir = tempdir().unwrap();
        let mut config = Config::default();
        config.data_dir = dir.path().to_path_buf();
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
        let mut config = Config::default();
        config.data_dir = dir.path().to_path_buf();
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
}
