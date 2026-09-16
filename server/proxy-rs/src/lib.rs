//! Small, bounded-memory Minecraft TCP proxy.

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    io::Read,
    net::{IpAddr, SocketAddr},
    path::Path,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::{OwnedSemaphorePermit, Semaphore},
    task::JoinSet,
    time::{Instant, timeout, timeout_at},
};
use tracing::{debug, info, warn};

const HANDSHAKE_LIMIT: usize = 4096;
const STATUS_LIMIT: usize = 4096;
const MAX_BODY_VARINT_BYTES: usize = 3;
const VARINT_BYTES: usize = 5;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const PHASE_TIMEOUT: Duration = Duration::from_secs(10);
const ROUTE_TIMEOUT: Duration = Duration::from_secs(3);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const COPY_BUFFER_SIZE: usize = 8192;

#[derive(Clone, Debug)]
pub struct Config {
    pub listen: SocketAddr,
    pub master: String,
    pub max_connections: usize,
    pub workers: usize,
    pub debug: bool,
    pub backend_host: String,
    pub favicon: Option<String>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            listen: "0.0.0.0:65200".parse().expect("literal socket address"),
            master: "http://127.0.0.1:65231".to_string(),
            max_connections: 64,
            workers: 2,
            debug: false,
            backend_host: "127.0.0.1".to_string(),
            favicon: Some("favicon.png".to_string()),
        }
    }
}

impl Config {
    pub fn from_args_env(args: impl IntoIterator<Item = String>) -> Result<Option<Self>> {
        let arg_values: Vec<String> = args.into_iter().collect();
        if arg_values.iter().any(|arg| arg == "--help" || arg == "-h") {
            return Ok(None);
        }
        let has_cli = |name: &str| {
            arg_values
                .iter()
                .any(|arg| arg == name || arg.starts_with(&format!("{name}=")))
        };
        let mut config = Self::default();
        let mut values: HashMap<String, String> = std::env::vars()
            .filter(|(key, _)| key.starts_with("RDI_"))
            .collect();
        if !has_cli("--listen")
            && let Some(value) = values.remove("RDI_LISTEN")
        {
            config.listen = value.parse().context("RDI_LISTEN must be host:port")?;
        } else if !has_cli("--listen")
            && let Some(port) = values.remove("RDI_PORT")
        {
            let port: u16 = port.parse().context("RDI_PORT must be a valid port")?;
            config.listen.set_port(port);
        }
        if !has_cli("--master")
            && let Some(value) = values.remove("RDI_MASTER")
        {
            config.master = value;
        }
        if !has_cli("--max-connections")
            && let Some(value) = values.remove("RDI_MAX_CONNECTIONS")
        {
            config.max_connections = value
                .parse()
                .context("RDI_MAX_CONNECTIONS must be positive")?;
        }
        if !has_cli("--workers")
            && let Some(value) = values.remove("RDI_WORKERS")
        {
            config.workers = value.parse().context("RDI_WORKERS must be positive")?;
        }
        if !has_cli("--debug")
            && let Some(value) = values.remove("RDI_DEBUG")
        {
            config.debug = parse_bool(&value)?;
        }
        if !has_cli("--backend-host")
            && let Some(value) = values.remove("RDI_BACKEND_HOST")
        {
            config.backend_host = value;
        }
        if !has_cli("--favicon")
            && let Some(value) = values.remove("RDI_FAVICON")
        {
            config.favicon = Some(value);
        }

        let mut args = arg_values.into_iter();
        let _program = args.next();
        while let Some(arg) = args.next() {
            let (key, inline) = arg
                .split_once('=')
                .map_or((arg.as_str(), None), |(k, v)| (k, Some(v)));
            if key == "--help" || key == "-h" {
                return Ok(None);
            }
            let mut value = || -> Result<String> {
                inline
                    .map(str::to_string)
                    .or_else(|| args.next())
                    .ok_or_else(|| anyhow!("missing value for {key}"))
            };
            match key {
                "--listen" => {
                    config.listen = value()?.parse().context("--listen must be host:port")?
                }
                "--master" => config.master = value()?,
                "--max-connections" => {
                    config.max_connections = value()?
                        .parse()
                        .context("--max-connections must be positive")?
                }
                "--workers" => {
                    config.workers = value()?.parse().context("--workers must be positive")?
                }
                "--backend-host" => config.backend_host = value()?,
                "--favicon" => config.favicon = Some(value()?),
                "--debug" => config.debug = inline.map(parse_bool).transpose()?.unwrap_or(true),
                _ => bail!("unknown argument: {arg}"),
            }
        }
        if config.workers == 0
            || config.workers > 256
            || config.max_connections == 0
            || config.max_connections > 65535
        {
            bail!("workers must be 1..256 and max-connections must be 1..65535");
        }
        validate_master(&config.master)?;
        if config.backend_host.trim().is_empty() {
            bail!("backend-host must not be blank");
        }
        Ok(Some(config))
    }

    pub fn usage() -> &'static str {
        "Usage: rdi-proxy-rs [--listen HOST:PORT] [--master URL] [--max-connections N] [--workers N] [--debug] [--backend-host HOST] [--favicon PATH]"
    }
}

fn parse_bool(value: &str) -> Result<bool> {
    match value.to_ascii_lowercase().as_str() {
        "1" | "true" | "yes" | "on" => Ok(true),
        "0" | "false" | "no" | "off" => Ok(false),
        _ => bail!("invalid boolean value: {value}"),
    }
}

fn validate_master(value: &str) -> Result<()> {
    let url = reqwest::Url::parse(value).context("master must be an HTTP(S) URL")?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || url.username() != ""
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        bail!("master must be an HTTP(S) URL without userinfo, query, or fragment");
    }
    Ok(())
}

fn route_url(master: &str, port: u16) -> Result<reqwest::Url> {
    let mut url = reqwest::Url::parse(master)?;
    let mut path = url.path().trim_end_matches('/').to_string();
    path.push_str("/host/route");
    url.set_path(&path);
    url.query_pairs_mut().append_pair("port", &port.to_string());
    Ok(url)
}

#[derive(Clone)]
pub struct RouteClient {
    client: reqwest::Client,
    master: String,
}

impl RouteClient {
    pub fn new(master: String) -> Result<Self> {
        validate_master(&master)?;
        let client = reqwest::Client::builder()
            .use_rustls_tls()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .pool_max_idle_per_host(2)
            .build()?;
        Ok(Self { client, master })
    }

    pub async fn resolve(&self, port: u16) -> Result<RouteData> {
        self.resolve_with_timeout(port, ROUTE_TIMEOUT).await
    }

    async fn resolve_with_timeout(&self, port: u16, limit: Duration) -> Result<RouteData> {
        let url = route_url(&self.master, port)?;
        timeout(limit, async {
            let response = self.client.get(url).send().await?;
            if !response.status().is_success() {
                bail!("route returned HTTP {}", response.status());
            }
            let mut body = Vec::new();
            let mut stream = response;
            while let Some(chunk) = stream.chunk().await? {
                if body.len().saturating_add(chunk.len()) > 64 * 1024 {
                    bail!("route response is too large");
                }
                body.extend_from_slice(&chunk);
            }
            let envelope: RouteEnvelope =
                serde_json::from_slice(&body).context("malformed route response")?;
            envelope
                .data
                .ok_or_else(|| anyhow!("route response has no data"))
        })
        .await
        .context("route request timed out")?
    }
}

#[derive(Debug, Deserialize)]
pub struct RouteEnvelope {
    pub code: i32,
    pub msg: String,
    pub data: Option<RouteData>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct RouteData {
    pub status: HostStatus,
    #[serde(rename = "backendHost")]
    pub backend_host: String,
    #[serde(rename = "backendPort")]
    pub backend_port: u16,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "UPPERCASE")]
pub enum HostStatus {
    PLAYABLE,
    STOPPED,
    STARTED,
    PAUSED,
    UNKNOWN,
}

impl HostStatus {
    fn reason(&self) -> &'static str {
        match self {
            Self::STOPPED => "房间未启动，请前往房间后台，点击启动按钮",
            Self::STARTED => "房间启动中，请稍等",
            Self::PLAYABLE => "",
            Self::PAUSED | Self::UNKNOWN => "无法连接房间，请稍后再试",
        }
    }
}

#[derive(Default)]
pub struct ActiveConnections {
    inner: Mutex<HashMap<u64, SocketAddr>>,
    next: std::sync::atomic::AtomicU64,
}

impl ActiveConnections {
    fn enter(self: &Arc<Self>, peer: SocketAddr) -> ActiveGuard {
        let id = self.next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.inner
            .lock()
            .expect("active map poisoned")
            .insert(id, peer);
        ActiveGuard {
            owner: Arc::clone(self),
            id,
        }
    }
    fn peers(&self) -> Vec<SocketAddr> {
        self.inner
            .lock()
            .expect("active map poisoned")
            .values()
            .copied()
            .collect()
    }
}

pub struct ActiveGuard {
    owner: Arc<ActiveConnections>,
    id: u64,
}
impl Drop for ActiveGuard {
    fn drop(&mut self) {
        self.owner
            .inner
            .lock()
            .expect("active map poisoned")
            .remove(&self.id);
    }
}

pub fn load_favicon(path: &Path) -> Option<String> {
    let Ok(metadata) = std::fs::metadata(path) else {
        debug!(path = ?path, "favicon unavailable");
        return None;
    };
    if !metadata.is_file() || metadata.len() > 64 * 1024 {
        warn!(path = ?path, "favicon unavailable or larger than 64KiB");
        return None;
    }
    let Ok(mut file) = std::fs::File::open(path) else {
        debug!(path = ?path, "favicon unavailable");
        return None;
    };
    let mut bytes = Vec::with_capacity(metadata.len() as usize);
    if file
        .by_ref()
        .take(64 * 1024 + 1)
        .read_to_end(&mut bytes)
        .is_err()
    {
        warn!(path = ?path, "failed to read favicon");
        return None;
    }
    if bytes.len() > 64 * 1024 {
        warn!(path = ?path, "favicon is larger than 64KiB");
        None
    } else {
        Some(format!(
            "data:image/png;base64,{}",
            base64::engine::general_purpose::STANDARD.encode(bytes)
        ))
    }
}

fn configure_socket(stream: &TcpStream) -> Result<()> {
    stream.set_nodelay(true)?;
    socket2::SockRef::from(stream).set_keepalive(true)?;
    Ok(())
}

#[derive(Serialize)]
struct StatusResponse<'a> {
    version: VersionResponse<'a>,
    players: PlayersResponse,
    description: DescriptionResponse<'a>,
    #[serde(skip_serializing_if = "Option::is_none")]
    favicon: Option<&'a str>,
}
#[derive(Serialize)]
struct VersionResponse<'a> {
    name: &'a str,
    protocol: i32,
}
#[derive(Serialize)]
struct PlayersResponse {
    max: u32,
    online: usize,
    sample: Vec<Sample>,
}
#[derive(Serialize)]
struct DescriptionResponse<'a> {
    text: &'a str,
}
#[derive(Serialize)]
struct Sample {
    name: String,
    id: String,
}

fn status_json(protocol: i32, peers: &[SocketAddr], favicon: Option<&str>) -> Result<Vec<u8>> {
    let sample = peers
        .iter()
        .map(|peer| Sample {
            name: format_peer(*peer),
            id: java_name_uuid(&format!("{}:{}", java_ip_string(peer.ip()), peer.port())),
        })
        .collect();
    Ok(serde_json::to_vec(&StatusResponse {
        version: VersionResponse {
            name: "RDI Proxy",
            protocol,
        },
        players: PlayersResponse {
            max: 88888,
            online: peers.len(),
            sample,
        },
        description: DescriptionResponse {
            text: "RDI Universal Proxy Server",
        },
        favicon,
    })?)
}

fn format_peer(peer: SocketAddr) -> String {
    match peer.ip() {
        IpAddr::V4(ip) => format!("{}.*.*.*:{}", ip.octets()[0], peer.port()),
        IpAddr::V6(ip) => format!("{}:***:{}", ip.segments()[0], peer.port()),
    }
}

fn java_ip_string(ip: IpAddr) -> String {
    match ip {
        IpAddr::V4(ip) => ip.to_string(),
        IpAddr::V6(ip) => ip
            .segments()
            .iter()
            .map(|segment| format!("{segment:x}"))
            .collect::<Vec<_>>()
            .join(":"),
    }
}

fn java_name_uuid(input: &str) -> String {
    let digest = md5::compute(input.as_bytes());
    let mut bytes = digest.0;
    bytes[6] = (bytes[6] & 0x0f) | 0x30;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0],
        bytes[1],
        bytes[2],
        bytes[3],
        bytes[4],
        bytes[5],
        bytes[6],
        bytes[7],
        bytes[8],
        bytes[9],
        bytes[10],
        bytes[11],
        bytes[12],
        bytes[13],
        bytes[14],
        bytes[15]
    )
}

#[derive(Debug)]
pub struct Handshake {
    pub protocol: i32,
    pub port: u16,
    pub next_state: i32,
    pub raw: Vec<u8>,
}

pub async fn read_frame<S: AsyncRead + Unpin>(
    stream: &mut S,
    max_body: usize,
    phase: Duration,
) -> Result<Vec<u8>> {
    let deadline = Instant::now() + phase;
    let mut prefix = Vec::with_capacity(3);
    let body_len = loop {
        if prefix.len() == MAX_BODY_VARINT_BYTES {
            bail!("frame length VarInt exceeds 3 bytes");
        }
        let mut one = [0u8; 1];
        timeout_at(deadline, stream.read_exact(&mut one))
            .await
            .context("frame length timed out")??;
        prefix.push(one[0]);
        if one[0] & 0x80 == 0 {
            break parse_varint_bytes(&prefix)?.0 as usize;
        }
    };
    if body_len > max_body {
        bail!("frame body exceeds limit");
    }
    let mut body = vec![0u8; body_len];
    timeout_at(deadline, stream.read_exact(&mut body))
        .await
        .context("frame body timed out")??;
    prefix.extend_from_slice(&body);
    Ok(prefix)
}

fn parse_varint_bytes(bytes: &[u8]) -> Result<(i32, usize)> {
    let mut result = 0i32;
    for (index, &byte) in bytes.iter().enumerate() {
        if index >= VARINT_BYTES {
            bail!("VarInt exceeds 5 bytes");
        }
        let value = (byte & 0x7f) as i32;
        if index == 4 && (byte & 0x80 != 0 || value > 0x0f) {
            bail!("VarInt overflow");
        }
        result |= value << (index * 7);
        if byte & 0x80 == 0 {
            return Ok((result, index + 1));
        }
    }
    bail!("truncated VarInt")
}

struct Cursor<'a> {
    bytes: &'a [u8],
    pos: usize,
}
impl<'a> Cursor<'a> {
    fn varint(&mut self) -> Result<i32> {
        let (value, used) = parse_varint_bytes(&self.bytes[self.pos..])?;
        self.pos += used;
        Ok(value)
    }
    fn take(&mut self, count: usize) -> Result<&'a [u8]> {
        let end = self
            .pos
            .checked_add(count)
            .ok_or_else(|| anyhow!("packet overflow"))?;
        let out = self
            .bytes
            .get(self.pos..end)
            .ok_or_else(|| anyhow!("truncated packet"))?;
        self.pos = end;
        Ok(out)
    }
    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }
}

pub fn parse_handshake(raw: Vec<u8>) -> Result<Handshake> {
    let (length, prefix) = parse_varint_bytes(&raw)?;
    if length < 0 || raw.len() != prefix + length as usize {
        bail!("invalid frame length");
    }
    let mut cursor = Cursor {
        bytes: &raw[prefix..],
        pos: 0,
    };
    if cursor.varint()? != 0 {
        bail!("not a handshake packet");
    }
    let protocol = cursor.varint()?;
    let hostname_len = cursor.varint()?;
    if hostname_len < 0 {
        bail!("invalid hostname length");
    }
    let hostname = cursor.take(hostname_len as usize)?;
    std::str::from_utf8(hostname).context("hostname is not UTF-8")?;
    let port = cursor.u16()?;
    let next_state = cursor.varint()?;
    if !matches!(next_state, 1..=3) || cursor.pos != cursor.bytes.len() {
        bail!("invalid handshake fields");
    }
    Ok(Handshake {
        protocol,
        port,
        next_state,
        raw,
    })
}

fn packet_id(frame: &[u8]) -> Result<(i32, &[u8])> {
    let (length, prefix) = parse_varint_bytes(frame)?;
    if length < 0 || frame.len() != prefix + length as usize {
        bail!("invalid frame");
    }
    let mut cursor = Cursor {
        bytes: &frame[prefix..],
        pos: 0,
    };
    let id = cursor.varint()?;
    Ok((id, &cursor.bytes[cursor.pos..]))
}

fn encode_frame(body: &[u8]) -> Vec<u8> {
    let mut out = encode_varint(body.len() as i32);
    out.extend_from_slice(body);
    out
}
fn encode_varint(mut value: i32) -> Vec<u8> {
    let mut out = Vec::new();
    loop {
        let mut byte = (value as u8) & 0x7f;
        value = ((value as u32) >> 7) as i32;
        if value != 0 {
            byte |= 0x80;
        }
        out.push(byte);
        if value == 0 {
            return out;
        }
    }
}

async fn write_frame<S: AsyncWrite + Unpin>(
    stream: &mut S,
    body: &[u8],
    phase: Duration,
) -> Result<()> {
    timeout(phase, stream.write_all(&encode_frame(body)))
        .await
        .context("write timed out")??;
    Ok(())
}

async fn send_disconnect(stream: &mut TcpStream, reason: &str) -> Result<()> {
    let json = serde_json::to_vec(&serde_json::json!({"text": reason}))?;
    let mut body = encode_varint(0);
    body.extend(encode_varint(json.len() as i32));
    body.extend(json);
    write_frame(stream, &body, PHASE_TIMEOUT).await
}

async fn relay(client: TcpStream, backend: TcpStream) -> Result<()> {
    let (mut client_read, mut client_write) = client.into_split();
    let (mut backend_read, mut backend_write) = backend.into_split();
    let mut c2b = [0u8; COPY_BUFFER_SIZE];
    let mut b2c = [0u8; COPY_BUFFER_SIZE];
    tokio::select! {
        result = copy_direction(&mut client_read, &mut backend_write, &mut c2b) => result,
        result = copy_direction(&mut backend_read, &mut client_write, &mut b2c) => result,
    }
}

async fn copy_direction<R: AsyncRead + Unpin, W: AsyncWrite + Unpin>(
    reader: &mut R,
    writer: &mut W,
    buffer: &mut [u8; COPY_BUFFER_SIZE],
) -> Result<()> {
    loop {
        let count = reader.read(buffer).await.context("relay read failed")?;
        if count == 0 {
            return Ok(());
        }
        writer
            .write_all(&buffer[..count])
            .await
            .context("relay write failed")?;
    }
}

async fn handle_connection(
    mut client: TcpStream,
    peer: SocketAddr,
    config: Arc<Config>,
    routes: RouteClient,
    active: Arc<ActiveConnections>,
    _permit: OwnedSemaphorePermit,
    favicon: Option<Arc<String>>,
) -> Result<()> {
    configure_socket(&client)?;
    let _guard = active.enter(peer);
    let frame = timeout(
        HANDSHAKE_TIMEOUT,
        read_frame(&mut client, HANDSHAKE_LIMIT, HANDSHAKE_TIMEOUT),
    )
    .await
    .context("handshake timed out")??;
    let handshake = parse_handshake(frame).context("invalid handshake")?;
    if handshake.next_state == 1 {
        loop {
            let request = read_frame(&mut client, STATUS_LIMIT, PHASE_TIMEOUT).await?;
            let (id, payload) = packet_id(&request)?;
            if id == 0 && payload.is_empty() {
                let body_json = status_json(
                    handshake.protocol,
                    &active.peers(),
                    favicon.as_deref().map(String::as_str),
                )
                .context("status serialization failed")?;
                let mut body = encode_varint(0);
                body.extend(encode_varint(body_json.len() as i32));
                body.extend(body_json);
                write_frame(&mut client, &body, PHASE_TIMEOUT).await?;
            } else if id == 1 && payload.len() == 8 {
                let mut body = encode_varint(1);
                body.extend_from_slice(payload);
                write_frame(&mut client, &body, PHASE_TIMEOUT).await?;
                return Ok(());
            } else {
                bail!("malformed status packet");
            }
        }
    }
    let (backend_host, backend_port) = if config.debug && handshake.port == 25565 {
        (config.backend_host.clone(), 25565)
    } else {
        let route = match routes.resolve(handshake.port).await {
            Ok(route) => route,
            Err(error) => {
                warn!(%peer, ?error, "route lookup failed");
                send_disconnect(&mut client, "房间服务暂时不可用，请稍后重试").await?;
                return Ok(());
            }
        };
        if route.backend_host.trim().is_empty() || route.backend_port == 0 {
            send_disconnect(&mut client, "房间服务暂时不可用，请稍后重试").await?;
            return Ok(());
        }
        match route.status {
            HostStatus::PLAYABLE => (route.backend_host, route.backend_port),
            status => {
                send_disconnect(&mut client, status.reason()).await?;
                return Ok(());
            }
        }
    };
    let mut backend = timeout(
        CONNECT_TIMEOUT,
        TcpStream::connect((backend_host.as_str(), backend_port)),
    )
    .await
    .context("backend connection timed out")??;
    configure_socket(&backend)?;
    timeout(CONNECT_TIMEOUT, backend.write_all(&handshake.raw))
        .await
        .context("handshake write timed out")??;
    drop(handshake);
    relay(client, backend).await
}

fn drain_finished_tasks(tasks: &mut JoinSet<()>) -> usize {
    let mut drained = 0;
    while let Some(joined) = tasks.try_join_next() {
        drained += 1;
        if let Err(error) = joined {
            warn!(?error, "connection task ended");
        }
    }
    drained
}

pub async fn run(config: Config) -> Result<()> {
    let routes = RouteClient::new(config.master.clone())?;
    let favicon = config
        .favicon
        .as_deref()
        .and_then(|path| load_favicon(Path::new(path)))
        .map(Arc::new);
    let listener = TcpListener::bind(config.listen)
        .await
        .context("bind proxy listener")?;
    let semaphore = Arc::new(Semaphore::new(config.max_connections));
    let active = Arc::new(ActiveConnections::default());
    let config = Arc::new(config);
    let mut tasks = JoinSet::new();
    let shutdown = shutdown_signal();
    tokio::pin!(shutdown);
    let mut accept_error = None;
    info!(address = %listener.local_addr()?, "proxy listening");
    loop {
        drain_finished_tasks(&mut tasks);
        tokio::select! {
            accepted = listener.accept() => {
                let (client, peer) = match accepted {
                    Ok(value) => value,
                    Err(error) => { warn!(?error, "accept failed"); accept_error = Some(error); break; }
                };
                let Ok(permit) = Arc::clone(&semaphore).try_acquire_owned() else { drop(client); continue; };
                let config = Arc::clone(&config); let routes = routes.clone(); let active = Arc::clone(&active); let favicon = favicon.clone();
                tasks.spawn(async move { if let Err(error) = handle_connection(client, peer, config, routes, active, permit, favicon).await { warn!(%peer, ?error, "connection closed with error"); } });
            }
            Some(joined) = tasks.join_next(), if !tasks.is_empty() => { if let Err(error) = joined { warn!(?error, "connection task ended"); } }
            _ = &mut shutdown => { info!("shutdown requested"); break; }
        }
    }
    tasks.abort_all();
    while tasks.join_next().await.is_some() {}
    if let Some(error) = accept_error {
        Err(error.into())
    } else {
        Ok(())
    }
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("install SIGTERM handler");
        tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = term.recv() => {} }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::{
        io::{AsyncWriteExt, duplex},
        net::TcpListener,
    };

    fn route_json(data: Option<&str>) -> String {
        format!(r#"{{"code":0,"msg":"","data":{}}}"#, data.unwrap_or("null"))
    }

    fn route_json_with_code(code: i32, data: &str) -> String {
        format!(r#"{{"code":{code},"msg":"route message","data":{data}}}"#)
    }

    async fn mock_http(body: Vec<u8>, status: u16, drip: bool) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut request = [0u8; 1024];
            let _ = socket.read(&mut request).await;
            let headers = format!(
                "HTTP/1.1 {status} Test\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            socket.write_all(headers.as_bytes()).await.unwrap();
            if drip {
                for chunk in body.chunks(8) {
                    socket.write_all(chunk).await.unwrap();
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            } else {
                socket.write_all(&body).await.unwrap();
            }
        });
        format!("http://{address}")
    }

    fn playable_data() -> &'static str {
        r#"{"status":"PLAYABLE","backendHost":"127.0.0.1","backendPort":25565}"#
    }

    #[test]
    fn parses_forge_handshake_and_preserves_raw() {
        let mut body = encode_varint(0);
        body.extend(encode_varint(767));
        let hostname = b"example\0FML\0x";
        body.extend(encode_varint(hostname.len() as i32));
        body.extend(hostname);
        body.extend(25565u16.to_be_bytes());
        body.extend(encode_varint(2));
        let raw = encode_frame(&body);
        let parsed = parse_handshake(raw.clone()).unwrap();
        assert_eq!(parsed.port, 25565);
        assert_eq!(parsed.raw, raw);
    }

    #[test]
    fn accepts_negative_protocol_and_rejects_trailing_data() {
        let mut body = encode_varint(0);
        body.extend(encode_varint(-1));
        body.extend(encode_varint(0));
        body.extend(25565u16.to_be_bytes());
        body.extend(encode_varint(2));
        assert!(parse_handshake(encode_frame(&body)).is_ok());
        body.push(1);
        assert!(parse_handshake(encode_frame(&body)).is_err());
    }

    #[test]
    fn rejects_handshake_overflow_truncation_invalid_utf8_and_state() {
        assert!(parse_varint_bytes(&[0x80, 0x80, 0x80, 0x80, 0x10]).is_err());
        assert!(parse_handshake(vec![0x02, 0x00]).is_err());

        let mut invalid_utf8 = encode_varint(0);
        invalid_utf8.extend(encode_varint(767));
        invalid_utf8.extend(encode_varint(1));
        invalid_utf8.push(0xff);
        invalid_utf8.extend(25565u16.to_be_bytes());
        invalid_utf8.extend(encode_varint(2));
        assert!(parse_handshake(encode_frame(&invalid_utf8)).is_err());

        let mut bad_state = encode_varint(0);
        bad_state.extend(encode_varint(767));
        bad_state.extend(encode_varint(0));
        bad_state.extend(25565u16.to_be_bytes());
        bad_state.extend(encode_varint(4));
        assert!(parse_handshake(encode_frame(&bad_state)).is_err());

        let mut body = encode_varint(0);
        body.extend(encode_varint(767));
        body.extend(encode_varint(0));
        body.extend(25565u16.to_be_bytes());
        body.extend(encode_varint(3));
        let mut noncanonical = vec![(body.len() as u8) | 0x80, 0x00];
        noncanonical.extend_from_slice(&body);
        assert_eq!(parse_handshake(noncanonical).unwrap().next_state, 3);
    }

    #[tokio::test]
    async fn fragmented_frame_and_limit() {
        let (mut writer, mut reader) = duplex(16);
        let task = tokio::spawn(async move {
            writer.write_all(&[0x82, 0x00]).await.unwrap();
            writer.write_all(&[1, 2]).await.unwrap();
        });
        assert_eq!(
            read_frame(&mut reader, 4096, Duration::from_secs(1))
                .await
                .unwrap(),
            vec![0x82, 0x00, 1, 2]
        );
        task.await.unwrap();
        let (mut writer, mut reader) = duplex(16);
        let task = tokio::spawn(async move {
            writer.write_all(&[0x81, 0x20]).await.unwrap();
        });
        assert!(
            read_frame(&mut reader, 1, Duration::from_secs(1))
                .await
                .is_err()
        );
        task.await.unwrap();
    }

    #[test]
    fn java_uuid_and_status_shape() {
        assert_eq!(
            java_name_uuid("127.0.0.1:1234"),
            "93454abf-54cf-365c-b03f-8e3c695562bf"
        );
        let json = status_json(767, &["127.0.0.1:1".parse().unwrap()], None).unwrap();
        let value: serde_json::Value = serde_json::from_slice(&json).unwrap();
        assert_eq!(value["players"]["online"], 1);
    }

    #[test]
    fn route_url_preserves_master_path_prefix() {
        let url = route_url("http://localhost:1234/api/", 25565).unwrap();
        assert_eq!(
            url.as_str(),
            "http://localhost:1234/api/host/route?port=25565"
        );
    }

    #[test]
    fn config_rejects_unbounded_connection_settings() {
        let args = vec!["proxy".into(), "--max-connections".into(), "65536".into()];
        assert!(Config::from_args_env(args).is_err());
    }

    #[tokio::test]
    async fn route_success_missing_data_and_http_error() {
        let success_master =
            mock_http(route_json(Some(playable_data())).into_bytes(), 200, false).await;
        let route = RouteClient::new(success_master)
            .unwrap()
            .resolve(25565)
            .await
            .unwrap();
        assert_eq!(route.status, HostStatus::PLAYABLE);

        let nonzero_master = mock_http(
            route_json_with_code(9, playable_data()).into_bytes(),
            200,
            false,
        )
        .await;
        assert_eq!(
            RouteClient::new(nonzero_master)
                .unwrap()
                .resolve(25565)
                .await
                .unwrap()
                .backend_port,
            25565
        );

        let missing_master = mock_http(route_json(None).into_bytes(), 200, false).await;
        assert!(
            RouteClient::new(missing_master)
                .unwrap()
                .resolve(25565)
                .await
                .is_err()
        );

        let error_master =
            mock_http(route_json(Some(playable_data())).into_bytes(), 500, false).await;
        assert!(
            RouteClient::new(error_master)
                .unwrap()
                .resolve(25565)
                .await
                .is_err()
        );
    }

    #[tokio::test]
    async fn route_body_limit_and_total_deadline() {
        let oversized = mock_http(vec![b'x'; 64 * 1024 + 1], 200, false).await;
        let oversized_error = RouteClient::new(oversized)
            .unwrap()
            .resolve(25565)
            .await
            .unwrap_err();
        assert!(oversized_error.to_string().contains("too large"));

        let drip = mock_http(route_json(Some(playable_data())).into_bytes(), 200, true).await;
        let client = RouteClient::new(drip).unwrap();
        let result = client
            .resolve_with_timeout(25565, Duration::from_millis(25))
            .await;
        assert!(result.unwrap_err().to_string().contains("timed out"));
    }

    #[test]
    fn known_unknown_host_state_is_supported_but_arbitrary_state_is_rejected() {
        let unknown: RouteEnvelope = serde_json::from_str(&route_json(Some(
            r#"{"status":"UNKNOWN","backendHost":"127.0.0.1","backendPort":25565}"#,
        )))
        .unwrap();
        assert_eq!(unknown.data.unwrap().status, HostStatus::UNKNOWN);
        assert!(
            serde_json::from_str::<RouteEnvelope>(&route_json(Some(
                r#"{"status":"SOMETHING_ELSE","backendHost":"127.0.0.1","backendPort":25565}"#,
            )))
            .is_err()
        );
    }

    #[tokio::test]
    async fn status_session_returns_status_and_ping_with_many_samples() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let active = Arc::new(ActiveConnections::default());
        let existing: Vec<_> = (0..13)
            .map(|index| active.enter(format!("127.0.0.1:{}", 2000 + index).parse().unwrap()))
            .collect();
        let semaphore = Arc::new(Semaphore::new(1));
        let permit = semaphore.clone().try_acquire_owned().unwrap();
        let config = Arc::new(Config {
            listen: address,
            master: "http://127.0.0.1:1".into(),
            ..Config::default()
        });
        let routes = RouteClient::new(config.master.clone()).unwrap();
        let server = tokio::spawn(async move {
            let (stream, peer) = listener.accept().await.unwrap();
            handle_connection(stream, peer, config, routes, active, permit, None).await
        });
        let mut client = TcpStream::connect(address).await.unwrap();
        let mut handshake = encode_varint(0);
        handshake.extend(encode_varint(-1));
        handshake.extend(encode_varint(0));
        handshake.extend(25565u16.to_be_bytes());
        handshake.extend(encode_varint(1));
        client.write_all(&encode_frame(&handshake)).await.unwrap();
        client
            .write_all(&encode_frame(&encode_varint(0)))
            .await
            .unwrap();
        let response = read_frame(&mut client, STATUS_LIMIT, PHASE_TIMEOUT)
            .await
            .unwrap();
        let (_, payload) = packet_id(&response).unwrap();
        let mut cursor = Cursor {
            bytes: payload,
            pos: 0,
        };
        let json_len = cursor.varint().unwrap() as usize;
        let value: serde_json::Value =
            serde_json::from_slice(cursor.take(json_len).unwrap()).unwrap();
        assert_eq!(value["version"]["protocol"], -1);
        assert_eq!(value["players"]["sample"].as_array().unwrap().len(), 14);

        let mut ping = encode_varint(1);
        ping.extend(1u64.to_be_bytes());
        client.write_all(&encode_frame(&ping)).await.unwrap();
        let response = read_frame(&mut client, STATUS_LIMIT, PHASE_TIMEOUT)
            .await
            .unwrap();
        assert_eq!(packet_id(&response).unwrap().0, 1);
        drop(client);
        assert!(server.await.unwrap().is_ok());
        drop(existing);
    }

    #[tokio::test]
    async fn fixed_relay_copies_exact_bytes_until_eof() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let backend = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let backend_address = backend.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (client, _) = listener.accept().await.unwrap();
            let backend_stream = TcpStream::connect(backend_address).await.unwrap();
            relay(client, backend_stream).await
        });
        let backend_server = tokio::spawn(async move {
            let (mut backend, _) = backend.accept().await.unwrap();
            let mut bytes = Vec::new();
            backend.read_to_end(&mut bytes).await.unwrap();
            bytes
        });
        let mut client = TcpStream::connect(address).await.unwrap();
        let data = vec![0x5a; COPY_BUFFER_SIZE * 2 + 17];
        client.write_all(&data).await.unwrap();
        client.shutdown().await.unwrap();
        let _ = client.read_to_end(&mut Vec::new()).await;
        assert!(server.await.unwrap().is_ok());
        assert_eq!(backend_server.await.unwrap(), data);
    }

    #[tokio::test]
    async fn active_guard_and_permit_cleanup_on_cancellation() {
        let active = Arc::new(ActiveConnections::default());
        let semaphore = Arc::new(Semaphore::new(1));
        let permit = semaphore.clone().try_acquire_owned().unwrap();
        let task_active = active.clone();
        let task = tokio::spawn(async move {
            let _guard = task_active.enter("127.0.0.1:2000".parse().unwrap());
            let _permit = permit;
            std::future::pending::<()>().await;
        });
        tokio::task::yield_now().await;
        assert_eq!(active.peers().len(), 1);
        task.abort();
        assert!(task.await.unwrap_err().is_cancelled());
        assert!(active.peers().is_empty());
        assert_eq!(semaphore.available_permits(), 1);
    }

    #[tokio::test]
    async fn finished_task_records_are_reaped_under_churn() {
        let mut tasks = JoinSet::new();
        const TASKS: usize = 256;
        for _ in 0..TASKS {
            tasks.spawn(async {});
        }
        let mut drained = 0;
        while drained < TASKS {
            tokio::task::yield_now().await;
            drained += drain_finished_tasks(&mut tasks);
        }
        assert!(tasks.is_empty());
        assert_eq!(drained, TASKS);
    }
}
