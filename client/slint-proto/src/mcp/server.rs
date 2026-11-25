//! The launcher MCP's HTTP server: `POST /mcp` on 127.0.0.1 with the checks of
//! `LAUNCHER-MCP-PLAN.md` section 5 (token, `Origin`, `Host`, content type, body size).
//! Bodies and the token are never logged.
use http_body_util::{BodyExt, Full, Limited};
use hyper::body::{Bytes, Incoming};
use hyper::header::{self, HeaderMap, HeaderValue};
use hyper::{Method, Request, Response, StatusCode};
use serde_json::Value;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

pub const PATH: &str = "/mcp";
pub const MAX_BODY: usize = 4 * 1024 * 1024;

/// Answers one JSON-RPC body; `None` for a notification.
pub type Handler = Arc<dyn Fn(Bytes) -> Pin<Box<dyn Future<Output = Option<Value>> + Send>> + Send + Sync>;

/// A running server; dropping it or calling `stop` closes the listener and every connection.
pub struct Running {
    stop: tokio::sync::watch::Sender<bool>,
}

impl Running {
    pub fn stop(&self) {
        let _ = self.stop.send(true);
    }
}

impl Drop for Running {
    fn drop(&mut self) {
        self.stop();
    }
}

struct Checks {
    port: u16,
    token: String,
    /// Who sends the authorized requests, for the title bar.
    seen: super::agent::Seen,
}

/// Serves `listener` until stopped. Must be called inside the tokio runtime.
pub fn spawn(listener: tokio::net::TcpListener, port: u16, token: String, seen: super::agent::Seen, handler: Handler) -> Running {
    let (stop, stopped) = tokio::sync::watch::channel(false);
    let checks = Arc::new(Checks { port, token, seen });
    tokio::spawn(async move {
        let mut accept_stop = stopped.clone();
        loop {
            let stream = tokio::select! {
                accepted = listener.accept() => match accepted {
                    Ok((stream, _)) => stream,
                    Err(error) => {
                        tracing::warn!(%error, "AI助手接受连接失败");
                        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                        continue;
                    }
                },
                _ = accept_stop.changed() => break,
            };
            let (checks, handler, mut connection_stop) = (checks.clone(), handler.clone(), stopped.clone());
            tokio::spawn(async move {
                let service = hyper::service::service_fn(move |request| {
                    let (checks, handler) = (checks.clone(), handler.clone());
                    async move { Ok::<_, std::convert::Infallible>(respond(request, &checks, &handler).await) }
                });
                let connection = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), service);
                // Closing the AI assistant also ends kept-alive connections.
                tokio::select! {
                    result = connection => if let Err(error) = result {
                        tracing::debug!(%error, "AI助手连接中断");
                    },
                    _ = connection_stop.changed() => {}
                }
            });
        }
        tracing::info!("AI助手已关闭");
    });
    Running { stop }
}

async fn respond(request: Request<Incoming>, checks: &Checks, handler: &Handler) -> Response<Full<Bytes>> {
    let (parts, body) = request.into_parts();
    if let Some(status) = refuse(&parts.method, parts.uri.path(), &parts.headers, checks) {
        if status != StatusCode::FORBIDDEN && status != StatusCode::UNAUTHORIZED {
            tracing::info!(status = status.as_u16(), method = %parts.method, "AI助手拒绝了请求");
        } else {
            tracing::warn!(status = status.as_u16(), "AI助手拒绝了未授权的请求");
        }
        return plain(status);
    }
    checks.seen.request(parts.headers.get(header::USER_AGENT).and_then(|v| v.to_str().ok()));
    let bytes = match Limited::new(body, MAX_BODY).collect().await {
        Ok(collected) => collected.to_bytes(),
        Err(error) if error.downcast_ref::<http_body_util::LengthLimitError>().is_some() => {
            return plain(StatusCode::PAYLOAD_TOO_LARGE);
        }
        Err(error) => {
            tracing::debug!(%error, "AI助手读取请求失败");
            return plain(StatusCode::BAD_REQUEST);
        }
    };
    match handler(bytes).await {
        Some(answer) => {
            let mut response = Response::new(Full::new(Bytes::from(answer.to_string())));
            response.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json; charset=utf-8"));
            response
        }
        None => {
            let mut response = Response::new(Full::new(Bytes::new()));
            *response.status_mut() = StatusCode::ACCEPTED;
            response
        }
    }
}

/// Every check before the body is read, in order: `Host`, `Origin`, token, path, method,
/// content type and declared length.
fn refuse(method: &Method, path: &str, headers: &HeaderMap, checks: &Checks) -> Option<StatusCode> {
    let text = |name: header::HeaderName| headers.get(name).map(|v| v.to_str().unwrap_or("\u{0}"));
    let host_ok = text(header::HOST).is_some_and(|host| allowed_host(host, checks.port));
    if !host_ok {
        return Some(StatusCode::FORBIDDEN);
    }
    if let Some(origin) = text(header::ORIGIN)
        && !allowed_origin(origin, checks.port)
    {
        return Some(StatusCode::FORBIDDEN);
    }
    let token = text(header::AUTHORIZATION).and_then(|value| {
        let (scheme, token) = value.trim().split_once(' ')?;
        scheme.eq_ignore_ascii_case("bearer").then(|| token.trim())
    });
    if !token.is_some_and(|token| same_secret(token.as_bytes(), checks.token.as_bytes())) {
        return Some(StatusCode::UNAUTHORIZED);
    }
    if path != PATH {
        return Some(StatusCode::NOT_FOUND);
    }
    if method != Method::POST {
        // No SSE stream (GET) and no sessions to end (DELETE).
        return Some(StatusCode::METHOD_NOT_ALLOWED);
    }
    let json = text(header::CONTENT_TYPE).is_some_and(|value| {
        value.split(';').next().is_some_and(|media| media.trim().eq_ignore_ascii_case("application/json"))
    });
    if !json {
        return Some(StatusCode::UNSUPPORTED_MEDIA_TYPE);
    }
    let length = text(header::CONTENT_LENGTH).and_then(|v| v.trim().parse::<u64>().ok());
    if length.is_some_and(|length| length > MAX_BODY as u64) {
        return Some(StatusCode::PAYLOAD_TOO_LARGE);
    }
    None
}

/// `127.0.0.1:<port>` or `localhost:<port>`; anything else may be DNS rebinding.
pub fn allowed_host(host: &str, port: u16) -> bool {
    let host = host.trim().to_ascii_lowercase();
    host == format!("127.0.0.1:{port}") || host == format!("localhost:{port}")
}

/// Agents send no `Origin`; a browser page always does, and only our own address may pass.
pub fn allowed_origin(origin: &str, port: u16) -> bool {
    let origin = origin.trim().to_ascii_lowercase();
    origin == format!("http://127.0.0.1:{port}") || origin == format!("http://localhost:{port}")
}

/// Compares without stopping at the first difference.
pub fn same_secret(given: &[u8], expected: &[u8]) -> bool {
    let mut difference = given.len() ^ expected.len();
    for (index, byte) in expected.iter().enumerate() {
        difference |= usize::from(byte ^ given.get(index).copied().unwrap_or(0));
    }
    difference == 0
}

fn plain(status: StatusCode) -> Response<Full<Bytes>> {
    let message = status.canonical_reason().unwrap_or("error");
    let body = serde_json::json!({"jsonrpc": "2.0", "id": null, "error": {"code": super::protocol::INVALID_REQUEST, "message": message}});
    let mut response = Response::new(Full::new(Bytes::from(body.to_string())));
    *response.status_mut() = status;
    response.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static("application/json; charset=utf-8"));
    if status == StatusCode::UNAUTHORIZED {
        response.headers_mut().insert(header::WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"));
    }
    if status == StatusCode::METHOD_NOT_ALLOWED {
        response.headers_mut().insert(header::ALLOW, HeaderValue::from_static("POST"));
    }
    response
}
