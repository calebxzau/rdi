//! Client end of a session: finds a working node, attaches it, and replaces it
//! when it fails or the player asks for another one.

use crate::{
    engine::{
        self, BoxStream, Control, EndReason, Event, REASON_ACK_STALLED, REASON_SILENT, Shared,
        Tuning,
    },
    protocol::{
        ClientHello, HelloStatus, SERVER_HELLO_LEN, ServerHello, TOKEN_LEN, Token, VERSION,
    },
};
use std::{future::Future, io, pin::Pin, sync::Arc, time::Duration};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    sync::{mpsc, watch},
    task::{JoinHandle, JoinSet},
    time::{Instant, sleep, sleep_until, timeout},
};

pub type Dialer = Arc<
    dyn Fn(String) -> Pin<Box<dyn Future<Output = io::Result<BoxStream>> + Send>> + Send + Sync,
>;
pub type EventSink = Arc<dyn Fn(u64, SessionEvent) + Send + Sync>;

#[derive(Clone, Copy, Debug)]
pub struct ClientConfig {
    pub tuning: Tuning,
    /// Time limit for one node's connection plus handshake.
    pub attempt_timeout: Duration,
    /// Wait before another round once every node has failed.
    pub retry_interval: Duration,
    /// A path that still delivers bytes is replaced at most this often.
    pub soft_switch_interval: Duration,
    /// Head start for the preferred node before the others are tried.
    pub stagger: Duration,
}

impl Default for ClientConfig {
    fn default() -> Self {
        Self {
            tuning: Tuning::client(),
            attempt_timeout: Duration::from_secs(2),
            retry_interval: Duration::from_secs(1),
            soft_switch_interval: Duration::from_secs(30),
            stagger: Duration::from_millis(500),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AttachReason {
    Initial,
    Failover,
    Manual,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SessionEvent {
    PathLost {
        reason: &'static str,
    },
    Attached {
        node: String,
        reason: AttachReason,
        /// Time since the path was lost, or since the request for a manual switch.
        elapsed_ms: u64,
    },
    SwitchFailed {
        node: String,
        error: String,
    },
    Ended {
        reason: EndReason,
    },
}

#[derive(Clone, Debug, Default)]
pub struct NodeState {
    pub nodes: Vec<String>,
    pub switch_serial: u64,
    pub switch_target: Option<String>,
}

/// Nodes shared by every session of a local proxy, first entry preferred.
#[derive(Clone)]
pub struct NodeTable {
    sender: watch::Sender<NodeState>,
}

impl NodeTable {
    pub fn new(nodes: Vec<String>) -> Self {
        let (sender, _) = watch::channel(NodeState {
            nodes,
            ..NodeState::default()
        });
        Self { sender }
    }

    pub fn subscribe(&self) -> watch::Receiver<NodeState> {
        self.sender.subscribe()
    }

    pub fn nodes(&self) -> Vec<String> {
        self.sender.borrow().nodes.clone()
    }

    pub fn set_nodes(&self, nodes: Vec<String>) {
        self.sender.send_modify(|state| state.nodes = nodes);
    }

    /// Moves every session to `node`; new sessions will also prefer it.
    pub fn switch_to(&self, node: String) {
        self.sender.send_modify(|state| {
            state.nodes.retain(|existing| existing != &node);
            state.nodes.insert(0, node.clone());
            state.switch_serial += 1;
            state.switch_target = Some(node);
        });
    }
}

enum ProbeError {
    Gone,
    TokenInUse,
    Failed(String),
}

enum AttachOutcome {
    Attached,
    Gone,
    Retry,
}

struct AbortOnDrop(JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

pub fn spawn_client_session<A>(
    app: A,
    session_id: u64,
    nodes: watch::Receiver<NodeState>,
    dialer: Dialer,
    config: ClientConfig,
    sink: EventSink,
) -> JoinHandle<()>
where
    A: AsyncRead + AsyncWrite + Send + Unpin + 'static,
{
    tokio::spawn(run_client_session(
        app, session_id, nodes, dialer, config, sink,
    ))
}

struct Supervisor {
    session_id: u64,
    token: Token,
    shared: Arc<Shared>,
    control: mpsc::Sender<Control>,
    config: ClientConfig,
    dialer: Dialer,
    sink: EventSink,
    generation: u32,
    /// The anchor has confirmed this session at least once.
    established: bool,
    /// A create request may have reached the anchor, so try resuming next.
    assume_created: bool,
    /// A node in the current round does not know this session. Another node may
    /// still reach the anchor that does, so this only counts once the round is over.
    round_gone: bool,
    current: Option<String>,
    /// How many paths have been handed to the engine.
    path_serial: u64,
    lost_at: Option<Instant>,
    manual_at: Option<Instant>,
    last_switch: Instant,
}

impl Supervisor {
    fn resume_flag(&self) -> bool {
        self.established || self.assume_created
    }

    fn emit(&self, event: SessionEvent) {
        (self.sink)(self.session_id, event);
    }

    /// Starts a round of probes; only the first to answer is attached, because
    /// several attaches at once would keep taking the session from each other.
    fn start_round(
        &mut self,
        round: &mut JoinSet<(String, Result<BoxStream, ProbeError>)>,
        nodes: &[String],
    ) {
        self.round_gone = false;
        let mut ordered: Vec<(String, Duration)> = Vec::with_capacity(nodes.len());
        if self.established {
            // The current node goes last and slightly later: it is the one that just failed.
            for node in nodes
                .iter()
                .filter(|node| Some(*node) != self.current.as_ref())
            {
                ordered.push((node.clone(), Duration::ZERO));
            }
            if let Some(current) = nodes
                .iter()
                .find(|node| Some(*node) == self.current.as_ref())
            {
                ordered.push((current.clone(), self.config.stagger));
            }
        } else {
            for (index, node) in nodes.iter().enumerate() {
                ordered.push((node.clone(), self.config.stagger * index as u32));
            }
        }
        for (node, delay) in ordered {
            let dialer = Arc::clone(&self.dialer);
            let token = self.token;
            let resume = self.resume_flag();
            let limit = self.config.attempt_timeout;
            round.spawn(async move {
                sleep(delay).await;
                let result = probe(&dialer, node.clone(), token, resume, limit).await;
                (node, result)
            });
        }
    }

    async fn attach(
        &mut self,
        mut stream: BoxStream,
        node: String,
        reason: AttachReason,
    ) -> AttachOutcome {
        self.generation += 1;
        let hello = ClientHello {
            version: VERSION,
            resume: self.resume_flag(),
            probe: false,
            token: self.token,
            generation: self.generation,
            recv_offset: self.shared.recv_total(),
        };
        let exchange = timeout(self.config.attempt_timeout, async {
            stream.write_all(&hello.encode()).await?;
            read_server_hello(&mut stream).await
        })
        .await;
        let server = match exchange {
            Ok(Ok(server)) => server,
            Ok(Err(_)) | Err(_) => {
                if !self.established {
                    self.assume_created = true;
                }
                return AttachOutcome::Retry;
            }
        };
        match server.status {
            HelloStatus::Ok => {}
            HelloStatus::UnknownSession if self.established => return AttachOutcome::Gone,
            HelloStatus::UnknownSession => {
                self.assume_created = false;
                return AttachOutcome::Retry;
            }
            HelloStatus::TokenInUse => {
                self.assume_created = true;
                return AttachOutcome::Retry;
            }
            _ => return AttachOutcome::Retry,
        }
        let message = Control::AttachClient {
            stream,
            peer_recv: server.recv_offset,
            send_from: server.send_from,
        };
        if self.control.send(message).await.is_err() {
            return AttachOutcome::Gone;
        }
        self.established = true;
        self.path_serial += 1;
        self.current = Some(node.clone());
        self.last_switch = Instant::now();
        let since = match reason {
            AttachReason::Manual => self.manual_at.take(),
            _ => self.lost_at,
        };
        self.lost_at = None;
        self.emit(SessionEvent::Attached {
            node,
            reason,
            elapsed_ms: since.map_or(0, |at| at.elapsed().as_millis() as u64),
        });
        AttachOutcome::Attached
    }
}

async fn probe(
    dialer: &Dialer,
    node: String,
    token: Token,
    resume: bool,
    limit: Duration,
) -> Result<BoxStream, ProbeError> {
    let attempt = async {
        let mut stream = dialer(node).await?;
        let hello = ClientHello {
            version: VERSION,
            resume,
            probe: true,
            token,
            generation: 0,
            recv_offset: 0,
        };
        stream.write_all(&hello.encode()).await?;
        let server = read_server_hello(&mut stream).await?;
        Ok::<_, io::Error>((stream, server.status))
    };
    match timeout(limit, attempt).await {
        Ok(Ok((stream, HelloStatus::Ok))) => Ok(stream),
        Ok(Ok((_, HelloStatus::UnknownSession))) => Err(ProbeError::Gone),
        Ok(Ok((_, HelloStatus::TokenInUse))) => Err(ProbeError::TokenInUse),
        Ok(Ok((_, status))) => Err(ProbeError::Failed(format!("{status:?}"))),
        Ok(Err(error)) => Err(ProbeError::Failed(error.to_string())),
        Err(_) => Err(ProbeError::Failed("timed out".to_string())),
    }
}

async fn read_server_hello(stream: &mut BoxStream) -> io::Result<ServerHello> {
    let mut bytes = [0u8; SERVER_HELLO_LEN];
    stream.read_exact(&mut bytes).await?;
    ServerHello::decode(&bytes).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

fn new_token() -> Token {
    let mut token = [0u8; TOKEN_LEN];
    getrandom::fill(&mut token).expect("system random source unavailable");
    token
}

/// Runs one session until it ends; dropping this future also stops the session.
pub async fn run_client_session<A>(
    app: A,
    session_id: u64,
    mut nodes: watch::Receiver<NodeState>,
    dialer: Dialer,
    config: ClientConfig,
    sink: EventSink,
) where
    A: AsyncRead + AsyncWrite + Send + Unpin + 'static,
{
    let shared = Arc::new(Shared::default());
    let (control_sender, control) = mpsc::channel(8);
    let (event_sender, mut events) = mpsc::unbounded_channel();
    let engine_shared = Arc::clone(&shared);
    let tuning = config.tuning;
    let _engine = AbortOnDrop(tokio::spawn(async move {
        let end_events = event_sender.clone();
        let reason = engine::run(app, control, event_sender, engine_shared, tuning).await;
        let _ = end_events.send(Event::Ended(reason));
    }));

    let mut supervisor = Supervisor {
        session_id,
        token: new_token(),
        shared,
        control: control_sender,
        config,
        dialer,
        sink,
        generation: 0,
        established: false,
        assume_created: false,
        round_gone: false,
        current: None,
        path_serial: 0,
        lost_at: None,
        manual_at: None,
        last_switch: Instant::now(),
    };

    let mut last_serial = nodes.borrow_and_update().switch_serial;
    let mut round = JoinSet::new();
    let mut manual = JoinSet::new();
    let mut retry_at: Option<Instant> = None;
    let mut watching = true;
    let start_nodes = nodes.borrow().nodes.clone();
    if start_nodes.is_empty() {
        retry_at = Some(Instant::now() + config.retry_interval);
    } else {
        supervisor.start_round(&mut round, &start_nodes);
    }

    loop {
        tokio::select! {
            event = events.recv() => match event {
                Some(Event::PathLost { path_serial, hard, reason }) => {
                    if path_serial != supervisor.path_serial {
                        continue;
                    }
                    let soft_too_soon = !hard
                        && supervisor.last_switch.elapsed() < config.soft_switch_interval;
                    if soft_too_soon {
                        continue;
                    }
                    supervisor.emit(SessionEvent::PathLost { reason });
                    // A silent path was already gone for the whole detection window.
                    let detection = match (reason, config.tuning.liveness) {
                        (REASON_SILENT | REASON_ACK_STALLED, Some(liveness)) => liveness.dead_after,
                        _ => Duration::ZERO,
                    };
                    let now = Instant::now();
                    supervisor
                        .lost_at
                        .get_or_insert_with(|| now.checked_sub(detection).unwrap_or(now));
                    if round.is_empty() && retry_at.is_none() {
                        let current_nodes = nodes.borrow().nodes.clone();
                        supervisor.start_round(&mut round, &current_nodes);
                    }
                }
                Some(Event::AnchorAttached { .. }) => {}
                Some(Event::Ended(reason)) => {
                    supervisor.emit(SessionEvent::Ended { reason });
                    return;
                }
                None => return,
            },
            joined = round.join_next(), if !round.is_empty() => {
                let Some(Ok((node, result))) = joined else { continue };
                match result {
                    Ok(stream) => {
                        round.abort_all();
                        round = JoinSet::new();
                        let reason = if supervisor.established {
                            AttachReason::Failover
                        } else {
                            AttachReason::Initial
                        };
                        match supervisor.attach(stream, node, reason).await {
                            AttachOutcome::Attached => retry_at = None,
                            AttachOutcome::Retry => retry_at = Some(Instant::now()),
                            AttachOutcome::Gone => {
                                let _ = supervisor.control.send(Control::Terminate).await;
                            }
                        }
                    }
                    Err(ProbeError::Gone) if supervisor.established => {
                        supervisor.round_gone = true;
                        if round.is_empty() {
                            let _ = supervisor.control.send(Control::Terminate).await;
                        }
                    }
                    Err(ProbeError::Gone) => {
                        supervisor.assume_created = false;
                        if round.is_empty() {
                            retry_at = Some(Instant::now());
                        }
                    }
                    Err(ProbeError::TokenInUse) => {
                        supervisor.assume_created = true;
                        if round.is_empty() {
                            retry_at = Some(Instant::now());
                        }
                    }
                    Err(ProbeError::Failed(_)) => {
                        if round.is_empty() {
                            if supervisor.round_gone {
                                let _ = supervisor.control.send(Control::Terminate).await;
                            } else {
                                retry_at = Some(Instant::now() + config.retry_interval);
                            }
                        }
                    }
                }
            }
            _ = sleep_until_opt(retry_at), if retry_at.is_some() => {
                retry_at = None;
                let current_nodes = nodes.borrow().nodes.clone();
                if current_nodes.is_empty() {
                    retry_at = Some(Instant::now() + config.retry_interval);
                } else {
                    supervisor.start_round(&mut round, &current_nodes);
                }
            }
            changed = nodes.changed(), if watching => {
                if changed.is_err() {
                    watching = false;
                    continue;
                }
                let state = nodes.borrow_and_update().clone();
                if state.switch_serial == last_serial {
                    continue;
                }
                last_serial = state.switch_serial;
                let Some(target) = state.switch_target else { continue };
                if !supervisor.established || supervisor.current.as_ref() == Some(&target) {
                    continue;
                }
                // Make before break: the old path stays in use until this one answers.
                manual.abort_all();
                manual = JoinSet::new();
                supervisor.manual_at = Some(Instant::now());
                let dialer = Arc::clone(&supervisor.dialer);
                let token = supervisor.token;
                let limit = config.attempt_timeout;
                manual.spawn(async move {
                    let result = probe(&dialer, target.clone(), token, true, limit).await;
                    (target, result)
                });
            }
            joined = manual.join_next(), if !manual.is_empty() => {
                let Some(Ok((node, result))) = joined else { continue };
                match result {
                    Ok(stream) => {
                        match supervisor.attach(stream, node.clone(), AttachReason::Manual).await {
                            AttachOutcome::Attached => {
                                round.abort_all();
                                round = JoinSet::new();
                                retry_at = None;
                            }
                            AttachOutcome::Gone => {
                                let _ = supervisor.control.send(Control::Terminate).await;
                            }
                            AttachOutcome::Retry => supervisor.emit(SessionEvent::SwitchFailed {
                                node,
                                error: "handshake failed".to_string(),
                            }),
                        }
                    }
                    // The current path still works, and failing over decides if the session is lost.
                    Err(ProbeError::Gone) => supervisor.emit(SessionEvent::SwitchFailed {
                        node,
                        error: "node does not hold this session".to_string(),
                    }),
                    Err(ProbeError::TokenInUse) => supervisor.emit(SessionEvent::SwitchFailed {
                        node,
                        error: "unexpected session state".to_string(),
                    }),
                    Err(ProbeError::Failed(error)) => {
                        supervisor.emit(SessionEvent::SwitchFailed { node, error });
                    }
                }
            }
        }
    }
}

async fn sleep_until_opt(at: Option<Instant>) {
    if let Some(at) = at {
        sleep_until(at).await;
    }
}
