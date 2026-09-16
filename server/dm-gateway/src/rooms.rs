use std::{collections::HashMap, net::{IpAddr, SocketAddr}, sync::Arc, time::{Duration, Instant}};

use anyhow::{Context, Result};
use tokio::{io::split, net::{TcpListener, TcpStream}, sync::{mpsc, watch, OwnedSemaphorePermit, Semaphore}, task::JoinSet, time::{self, timeout}};
use uuid::Uuid;

use crate::{protocol::{self, HostMessage, TunnelHandshake}, relay};

const EVENT_CAPACITY: usize = 128;
const CONTROL_CAPACITY: usize = 128;
const MAX_CONNECTIONS: usize = 128;
const PAIRING_TIMEOUT: Duration = Duration::from_secs(10);
const IO_TIMEOUT: Duration = Duration::from_secs(5);
const PING_INTERVAL: Duration = Duration::from_secs(15);
const PONG_TIMEOUT: Duration = Duration::from_secs(45);

pub enum GatewayEvent {
    Register { room: String, stream: TcpStream },
    Attach { session: Uuid, connection: u64, stream: TcpStream },
    Guest { room: String, stream: TcpStream },
    SessionEnded { room: String, session: Uuid },
}

struct Room {
    port: u16,
    active_session: Option<Uuid>,
}

struct SessionHandle {
    tx: mpsc::Sender<SessionCommand>,
}

enum SessionCommand {
    Guest(TcpStream),
    Attach { connection: u64, stream: TcpStream },
    Control(HostMessage),
    IoClosed(String),
}

enum OutboundControl {
    Ready { session: Uuid, port: u16 },
    Open(u64),
    Ping,
}

enum GatewayWake {
    Shutdown(std::io::Result<()>),
    TunnelAccepted(std::io::Result<(TcpStream, SocketAddr)>),
    Event(Option<GatewayEvent>),
    ChildCompleted(Option<std::result::Result<(), tokio::task::JoinError>>),
}

pub async fn run(config: crate::config::ServeConfig) -> Result<()> {
    let tunnel = TcpListener::bind(config.tunnel_listen).await.context("bind tunnel listener")?;
    tracing::info!(address = %config.tunnel_listen, "DM gateway tunnel listener started");
    let mut events = mpsc::channel(EVENT_CAPACITY);
    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let handshake_slots = Arc::new(Semaphore::new(EVENT_CAPACITY));
    let mut tasks = JoinSet::new();
    let mut rooms: HashMap<String, Room> = HashMap::new();
    let mut sessions: HashMap<Uuid, SessionHandle> = HashMap::new();
    let mut next_port = config.game_ports.0;

    loop {
        let wake = tokio::select! {
            signal = tokio::signal::ctrl_c() => GatewayWake::Shutdown(signal),
            accepted = tunnel.accept() => GatewayWake::TunnelAccepted(accepted),
            event = events.1.recv() => GatewayWake::Event(event),
            completed = tasks.join_next(), if !tasks.is_empty() => GatewayWake::ChildCompleted(completed),
        };
        match wake {
            GatewayWake::Shutdown(signal) => {
                if let Err(error) = signal { tracing::warn!(%error, "failed to listen for Ctrl-C"); }
                break;
            }
            GatewayWake::TunnelAccepted(accepted) => match accepted {
                    Ok((stream, peer)) => {
                        let permit = match handshake_slots.clone().try_acquire_owned() {
                            Ok(permit) => permit,
                            Err(_) => {
                                tracing::warn!(%peer, "dropping tunnel connection because handshake capacity is full");
                                continue;
                            }
                        };
                        let tx = events.0.clone();
                        tasks.spawn(async move { tunnel_handshake(stream, peer, tx, permit).await; });
                    }
                    Err(error) => tracing::error!(%error, "accept tunnel connection failed"),
                },
            GatewayWake::Event(event) => {
                let Some(event) = event else { break; };
                handle_event(event, &config, &mut rooms, &mut sessions, &mut next_port, &mut tasks, &events.0, &shutdown_rx).await;
            }
            GatewayWake::ChildCompleted(completed) => {
                if let Some(Err(error)) = completed {
                    tracing::debug!(%error, "gateway child task ended");
                }
            }
        }
    }
    tracing::info!("stopping DM gateway");
    let _ = shutdown_tx.send(true);
    if time::timeout(Duration::from_secs(2), async {
        while tasks.join_next().await.is_some() {}
    }).await.is_err() {
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
    }
    Ok(())
}

async fn tunnel_handshake(mut stream: TcpStream, peer: SocketAddr, tx: mpsc::Sender<GatewayEvent>, _permit: OwnedSemaphorePermit) {
    let handshake = timeout(IO_TIMEOUT, protocol::read_tunnel_handshake(&mut stream)).await;
    match handshake {
        Ok(Ok(TunnelHandshake::Register { room })) => {
            if tx.try_send(GatewayEvent::Register { room, stream }).is_err() {
                tracing::warn!(%peer, "dropping registration because gateway queue is full");
            }
        }
        Ok(Ok(TunnelHandshake::Attach { session, connection })) => {
            if tx.try_send(GatewayEvent::Attach { session, connection, stream }).is_err() {
                tracing::warn!(%peer, %session, connection, "dropping attach because gateway queue is full");
            }
        }
        Ok(Err(error)) => tracing::warn!(%peer, %error, "invalid tunnel handshake"),
        Err(_) => tracing::warn!(%peer, "tunnel handshake timed out"),
    }
}

async fn handle_event(
    event: GatewayEvent,
    config: &crate::config::ServeConfig,
    rooms: &mut HashMap<String, Room>,
    sessions: &mut HashMap<Uuid, SessionHandle>,
    next_port: &mut u16,
    tasks: &mut JoinSet<()>,
    gateway_tx: &mpsc::Sender<GatewayEvent>,
    shutdown_rx: &watch::Receiver<bool>,
) {
    match event {
        GatewayEvent::Register { room, stream } => {
            if rooms.get(&room).and_then(|r| r.active_session).is_some() {
                tracing::info!(%room, "rejected registration for an active room");
                send_status(tasks, stream, protocol::BUSY);
                return;
            }
            let port = if let Some(existing) = rooms.get(&room) {
                existing.port
            } else {
                let (port, listener) = match bind_game_listener(config.game_bind, config.game_ports, next_port) {
                    Ok(Some(value)) => value,
                    Ok(None) => {
                        tracing::error!(%room, "no game port available for room");
                        send_status(tasks, stream, protocol::NO_PORT);
                        return;
                    }
                    Err(error) => {
                        tracing::error!(%room, %error, "failed to allocate a game port");
                        send_status(tasks, stream, protocol::NO_PORT);
                        return;
                    }
                };
                rooms.insert(room.clone(), Room { port, active_session: None });
                let tx = gateway_tx.clone();
                tasks.spawn(game_listener(room.clone(), listener, tx, shutdown_rx.clone()));
                port
            };
            let session = Uuid::now_v7();
            let (tx, rx) = mpsc::channel(CONTROL_CAPACITY);
            let actor_tx = tx.clone();
            rooms.get_mut(&room).expect("room inserted before session creation").active_session = Some(session);
            sessions.insert(session, SessionHandle { tx });
            tracing::info!(%room, %session, port, "host registered");
            tasks.spawn(session_actor(room, session, port, stream, rx, actor_tx, gateway_tx.clone(), shutdown_rx.clone()));
        }
        GatewayEvent::Guest { room, stream } => {
            let Some(session) = rooms.get(&room).and_then(|r| r.active_session) else {
                tracing::debug!(%room, "closing guest for offline room");
                return;
            };
            let Some(handle) = sessions.get(&session) else { return; };
            if handle.tx.try_send(SessionCommand::Guest(stream)).is_err() {
                tracing::warn!(%room, %session, "dropping guest because host queue is full");
            }
        }
        GatewayEvent::Attach { session, connection, stream } => {
            let Some(handle) = sessions.get(&session) else {
                tracing::debug!(%session, connection, "closing attach for unknown session");
                return;
            };
            if handle.tx.try_send(SessionCommand::Attach { connection, stream }).is_err() {
                tracing::warn!(%session, connection, "dropping attach because host queue is full");
            }
        }
        GatewayEvent::SessionEnded { room, session } => {
            sessions.remove(&session);
            if rooms.get(&room).and_then(|r| r.active_session) == Some(session) {
                rooms.get_mut(&room).expect("room exists for session").active_session = None;
            }
            tracing::info!(%room, %session, "host session ended");
        }
    }
}

fn bind_game_listener(bind: IpAddr, range: (u16, u16), next_port: &mut u16) -> Result<Option<(u16, TcpListener)>> {
    let (first, last) = range;
    let start = (*next_port).clamp(first, last);
    let width = u32::from(last) - u32::from(first) + 1;
    for offset in 0..width {
        let port = (u32::from(first) + (u32::from(start) - u32::from(first) + offset) % width) as u16;
        let address = SocketAddr::new(bind, port);
        match std::net::TcpListener::bind(address) {
            Ok(listener) => {
                if let Err(error) = listener.set_nonblocking(true) {
                    return Err(error).with_context(|| format!("set game listener {address} nonblocking"));
                }
                let listener = TcpListener::from_std(listener).with_context(|| format!("adopt game listener {address}"))?;
                *next_port = if port == last { first } else { port + 1 };
                return Ok(Some((port, listener)));
            }
            Err(error) if error.kind() == std::io::ErrorKind::AddrInUse => continue,
            Err(error) => { tracing::warn!(%address, %error, "game port bind failed"); continue; }
        }
    }
    Ok(None)
}

async fn game_listener(room: String, listener: TcpListener, gateway_tx: mpsc::Sender<GatewayEvent>, mut shutdown: watch::Receiver<bool>) {
    tracing::info!(%room, local_addr = ?listener.local_addr().ok(), "game listener started");
    loop {
        tokio::select! {
            accepted = listener.accept() => match accepted {
                Ok((stream, peer)) => {
                    if let Err(error) = stream.set_nodelay(true) {
                        tracing::warn!(%room, %peer, %error, "failed to enable TCP_NODELAY for guest");
                        continue;
                    }
                    if gateway_tx.try_send(GatewayEvent::Guest { room: room.clone(), stream }).is_err() {
                        tracing::warn!(%room, %peer, "dropping guest because gateway queue is full");
                    }
                }
                Err(error) => {
                    tracing::error!(%room, %error, "accept game connection failed");
                    tokio::select! { _ = time::sleep(Duration::from_secs(1)) => {}, _ = shutdown.changed() => break }
                }
            },
            changed = shutdown.changed() => { if changed.is_ok() { break; } }
        }
    }
}

fn send_status(tasks: &mut JoinSet<()>, mut stream: TcpStream, status: u8) {
    tasks.spawn(async move {
        let result = timeout(IO_TIMEOUT, protocol::write_status(&mut stream, status)).await;
        match result {
            Ok(Ok(())) => {}
            Ok(Err(error)) => tracing::debug!(%error, "status response failed"),
            Err(error) => tracing::debug!(%error, "status response timed out"),
        }
    });
}

async fn session_actor(
    room: String,
    session: Uuid,
    port: u16,
    stream: TcpStream,
    mut commands: mpsc::Receiver<SessionCommand>,
    command_tx: mpsc::Sender<SessionCommand>,
    gateway_tx: mpsc::Sender<GatewayEvent>,
    mut shutdown: watch::Receiver<bool>,
) {
    let (mut reader, mut writer) = split(stream);
    let (out_tx, mut out_rx) = mpsc::channel(CONTROL_CAPACITY);
    let mut io_tasks = JoinSet::new();
    let writer_signal = command_tx.clone();
    io_tasks.spawn(async move {
        while let Some(message) = out_rx.recv().await {
            match timeout(IO_TIMEOUT, write_outbound(&mut writer, message)).await {
                Ok(Ok(())) => {}
                Ok(Err(error)) => {
                    let _ = writer_signal.try_send(SessionCommand::IoClosed(error.to_string()));
                    break;
                }
                Err(_) => {
                    let _ = writer_signal.try_send(SessionCommand::IoClosed("control write timed out".to_string()));
                    break;
                }
            }
        }
    });
    let reader_io = command_tx;
    io_tasks.spawn(async move {
        loop {
            match protocol::read_host_message(&mut reader).await {
                Ok(message) => { if reader_io.try_send(SessionCommand::Control(message)).is_err() { break; } }
                Err(error) => { let _ = reader_io.try_send(SessionCommand::IoClosed(error.to_string())); break; }
            }
        }
    });
    let _ = out_tx.try_send(OutboundControl::Ready { session, port });
    let mut pending: HashMap<u64, (TcpStream, Instant)> = HashMap::new();
    let mut relays: JoinSet<Result<(u64, u64)>> = JoinSet::new();
    let mut expiry = time::interval(Duration::from_secs(1));
    let mut ping = time::interval(PING_INTERVAL);
    let mut last_pong = Instant::now();
    let mut next_connection: u64 = 1;
    let mut reason = "control closed".to_string();
    'actor: loop {
        while let Some(result) = relays.try_join_next() {
            match result {
                Ok(Ok((to_host, to_guest))) => tracing::debug!(%room, %session, to_host, to_guest, "guest relay ended"),
                Ok(Err(error)) => tracing::warn!(%room, %session, %error, "guest relay ended with I/O error"),
                Err(error) => tracing::debug!(%room, %session, %error, "guest relay task cancelled"),
            }
        }
        tokio::select! {
            command = commands.recv() => match command {
                Some(SessionCommand::Guest(guest)) => {
                    if pending.len() + relays.len() >= MAX_CONNECTIONS {
                        tracing::warn!(%room, %session, "dropping guest because session capacity is full");
                    } else {
                        let connection = next_connection;
                        next_connection = next_connection.wrapping_add(1);
                        pending.insert(connection, (guest, Instant::now() + PAIRING_TIMEOUT));
                        if out_tx.try_send(OutboundControl::Open(connection)).is_err() {
                            reason = "control queue saturated".to_string();
                            break 'actor;
                        }
                    }
                }
                Some(SessionCommand::Attach { connection, stream }) => {
                    let Some((guest, deadline)) = pending.remove(&connection) else {
                        tracing::debug!(%room, %session, connection, "dropping unknown or late attach");
                        continue;
                    };
                    if deadline <= Instant::now() {
                        tracing::debug!(%room, %session, connection, "dropping attach after pairing deadline");
                    } else if relays.len() >= MAX_CONNECTIONS {
                        tracing::warn!(%room, %session, connection, "dropping attach because relay capacity is full");
                    } else {
                        relays.spawn(async move { relay::relay(guest, stream).await });
                    }
                }
                Some(SessionCommand::Control(message)) => match message {
                    HostMessage::Pong => last_pong = Instant::now(),
                    HostMessage::Failed(connection) => { pending.remove(&connection); tracing::debug!(%room, %session, connection, "host reported connection failure"); }
                },
                Some(SessionCommand::IoClosed(error)) => { reason = error; break 'actor; }
                None => break 'actor,
            },
            _ = expiry.tick() => {
                let now = Instant::now();
                if now.duration_since(last_pong) >= PONG_TIMEOUT {
                    reason = "host heartbeat timed out".to_string();
                    break 'actor;
                }
                pending.retain(|connection, (_, deadline)| {
                    if *deadline <= now { tracing::debug!(%room, %session, connection, "guest pairing timed out"); false } else { true }
                });
            }
            _ = ping.tick() => {
                if last_pong.elapsed() >= PONG_TIMEOUT { reason = "host heartbeat timed out".to_string(); break 'actor; }
                if out_tx.try_send(OutboundControl::Ping).is_err() { reason = "control queue saturated".to_string(); break 'actor; }
            }
            io_result = io_tasks.join_next() => {
                if let Some(result) = io_result {
                    reason = match result {
                        Ok(()) => "control I/O task ended".to_string(),
                        Err(error) => format!("control I/O task failed: {error}"),
                    };
                    break 'actor;
                }
            }
            changed = shutdown.changed() => {
                if changed.is_ok() { reason = "gateway shutting down".to_string(); break 'actor; }
            }
        }
    }
    pending.clear();
    io_tasks.abort_all();
    relays.abort_all();
    while io_tasks.join_next().await.is_some() {}
    while relays.join_next().await.is_some() {}
    let ended = GatewayEvent::SessionEnded { room, session };
    let stopping = *shutdown.borrow();
    if !stopping {
        tokio::select! {
            result = gateway_tx.send(ended) => { if result.is_err() { tracing::debug!(%session, "gateway event loop already stopped"); } }
            changed = shutdown.changed() => { let _ = changed; }
        }
    }
    tracing::debug!(%session, %reason, "session actor stopped");
}

async fn write_outbound<W: tokio::io::AsyncWrite + Unpin>(writer: &mut W, message: OutboundControl) -> Result<()> {
    match message {
        OutboundControl::Ready { session, port } => protocol::write_ready(writer, session, port).await,
        OutboundControl::Open(connection) => protocol::write_connection_opcode(writer, protocol::OPEN, connection).await,
        OutboundControl::Ping => protocol::write_control_opcode(writer, protocol::PING).await,
    }
}
