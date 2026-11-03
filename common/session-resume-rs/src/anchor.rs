//! Session registry on the anchor, the end next to the game server. Connections
//! arriving through any node end up here, and the application stream outlives them.

use crate::{
    engine::{self, BoxStream, Control, EndReason, Event, Shared, Tuning},
    protocol::{CLIENT_HELLO_LEN, ClientHello, HelloStatus, ServerHello, Token, VERSION},
};
use std::{
    collections::HashMap,
    io,
    net::SocketAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt, DuplexStream, duplex},
    sync::mpsc,
    time::{Instant, timeout},
};

const HELLO_TIMEOUT: Duration = Duration::from_secs(5);
const APP_PIPE_CAPACITY: usize = 16 * 1024;
const CONTROL_QUEUE: usize = 8;
/// A client probes once before attaching, so more than this is not a real client.
const MAX_HELLOS_PER_CONNECTION: usize = 4;

type SessionHandler = dyn Fn(DuplexStream, SocketAddr) + Send + Sync;
type EventHandler = dyn Fn(AnchorEvent) + Send + Sync;

/// What happened to a session. Sessions go by a short number that is only unique
/// within this process, so these are safe to log: the token is never included.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AnchorEvent {
    /// A session accepted its first connection.
    Started { session: u64, peer: SocketAddr },
    /// A later connection took the session over, so the player changed path.
    Resumed {
        session: u64,
        peer: SocketAddr,
        /// Time since the last byte from the player, which is about the length of the outage.
        silent_ms: u64,
        /// Bytes already sent once that go out again on the new path.
        replay_bytes: u64,
    },
    /// A connection asked to resume a session this process does not hold.
    UnknownSession { peer: SocketAddr },
    Ended {
        session: u64,
        reason: EndReason,
        resumes: u32,
        lasted_ms: u64,
    },
}

pub struct Anchor {
    sessions: Mutex<HashMap<Token, mpsc::Sender<Control>>>,
    max_sessions: usize,
    tuning: Tuning,
    on_session: Box<SessionHandler>,
    on_event: Box<EventHandler>,
    next_session: AtomicU64,
}

impl Anchor {
    /// `on_session` receives the application end of each new session and must
    /// serve it without blocking, usually by spawning a task.
    pub fn new(
        max_sessions: usize,
        tuning: Tuning,
        on_session: impl Fn(DuplexStream, SocketAddr) + Send + Sync + 'static,
    ) -> Arc<Self> {
        Self::with_events(max_sessions, tuning, on_session, |_event| {})
    }

    /// Like `new`, and also reports each session's lifecycle to `on_event`, which must not block.
    pub fn with_events(
        max_sessions: usize,
        tuning: Tuning,
        on_session: impl Fn(DuplexStream, SocketAddr) + Send + Sync + 'static,
        on_event: impl Fn(AnchorEvent) + Send + Sync + 'static,
    ) -> Arc<Self> {
        Arc::new(Self {
            sessions: Mutex::new(HashMap::new()),
            max_sessions,
            tuning,
            on_session: Box::new(on_session),
            on_event: Box::new(on_event),
            next_session: AtomicU64::new(1),
        })
    }

    pub fn session_count(&self) -> usize {
        self.sessions.lock().expect("session map poisoned").len()
    }

    /// Runs the hello exchange on a new connection and hands it to its session.
    pub async fn handle_path(
        self: &Arc<Self>,
        mut stream: BoxStream,
        peer: SocketAddr,
    ) -> io::Result<()> {
        for _ in 0..MAX_HELLOS_PER_CONNECTION {
            let mut bytes = [0u8; CLIENT_HELLO_LEN];
            timeout(HELLO_TIMEOUT, stream.read_exact(&mut bytes)).await??;
            let hello = ClientHello::decode(&bytes)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
            if hello.version != VERSION {
                return reply(&mut stream, HelloStatus::UnsupportedVersion).await;
            }
            if hello.probe {
                let status = self.probe_status(&hello);
                if status == HelloStatus::UnknownSession {
                    (self.on_event)(AnchorEvent::UnknownSession { peer });
                }
                write_status(&mut stream, status).await?;
                continue;
            }
            let attach = Control::AttachAnchor {
                stream,
                generation: hello.generation,
                peer_recv: hello.recv_offset,
                peer,
            };
            let outcome = if hello.resume {
                self.resume(&hello.token, attach, peer).await
            } else {
                self.create(hello.token, attach, peer).await
            };
            return match outcome {
                Ok(()) => Ok(()),
                Err((mut stream, status)) => reply(&mut stream, status).await,
            };
        }
        Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "too many hellos on one connection",
        ))
    }

    fn probe_status(&self, hello: &ClientHello) -> HelloStatus {
        let sessions = self.sessions.lock().expect("session map poisoned");
        match (hello.resume, sessions.contains_key(&hello.token)) {
            (true, true) => HelloStatus::Ok,
            (true, false) => HelloStatus::UnknownSession,
            (false, true) => HelloStatus::TokenInUse,
            (false, false) if sessions.len() >= self.max_sessions => HelloStatus::Full,
            (false, false) => HelloStatus::Ok,
        }
    }

    async fn resume(
        &self,
        token: &Token,
        attach: Control,
        peer: SocketAddr,
    ) -> Result<(), (BoxStream, HelloStatus)> {
        let sender = self
            .sessions
            .lock()
            .expect("session map poisoned")
            .get(token)
            .cloned();
        let Some(sender) = sender else {
            (self.on_event)(AnchorEvent::UnknownSession { peer });
            return Err((stream_of(attach), HelloStatus::UnknownSession));
        };
        sender
            .send(attach)
            .await
            .map_err(|failed| (stream_of(failed.0), HelloStatus::UnknownSession))
    }

    async fn create(
        self: &Arc<Self>,
        token: Token,
        attach: Control,
        peer: SocketAddr,
    ) -> Result<(), (BoxStream, HelloStatus)> {
        let (control_sender, control) = mpsc::channel(CONTROL_QUEUE);
        {
            let mut sessions = self.sessions.lock().expect("session map poisoned");
            if sessions.contains_key(&token) {
                return Err((stream_of(attach), HelloStatus::TokenInUse));
            }
            if sessions.len() >= self.max_sessions {
                return Err((stream_of(attach), HelloStatus::Full));
            }
            sessions.insert(token, control_sender.clone());
        }
        let (session_end, app_end) = duplex(APP_PIPE_CAPACITY);
        let registry = Arc::clone(self);
        let tuning = self.tuning;
        let session = self.next_session.fetch_add(1, Ordering::Relaxed);
        tokio::spawn(async move {
            let (events, mut engine_events) = mpsc::unbounded_channel::<Event>();
            let shared = Arc::new(Shared::default());
            let started = Instant::now();
            let mut attached = false;
            let mut resumes = 0;
            // The anchor waits passively, so only accepted connections matter here, not lost paths.
            let mut report = |event: Event| {
                let Event::AnchorAttached {
                    peer,
                    silent_ms,
                    replay_bytes,
                } = event
                else {
                    return;
                };
                if attached {
                    resumes += 1;
                    (registry.on_event)(AnchorEvent::Resumed {
                        session,
                        peer,
                        silent_ms,
                        replay_bytes,
                    });
                } else {
                    attached = true;
                    (registry.on_event)(AnchorEvent::Started { session, peer });
                }
            };
            let run = engine::run(session_end, control, events, shared, tuning);
            tokio::pin!(run);
            let reason = loop {
                tokio::select! {
                    reason = &mut run => break reason,
                    Some(event) = engine_events.recv() => report(event),
                }
            };
            while let Ok(event) = engine_events.try_recv() {
                report(event);
            }
            registry
                .sessions
                .lock()
                .expect("session map poisoned")
                .remove(&token);
            (registry.on_event)(AnchorEvent::Ended {
                session,
                reason,
                resumes,
                lasted_ms: started.elapsed().as_millis() as u64,
            });
        });
        (self.on_session)(app_end, peer);
        control_sender
            .send(attach)
            .await
            .map_err(|failed| (stream_of(failed.0), HelloStatus::UnknownSession))
    }
}

fn stream_of(attach: Control) -> BoxStream {
    match attach {
        Control::AttachAnchor { stream, .. } | Control::AttachClient { stream, .. } => stream,
        Control::Terminate => unreachable!("only attach messages carry a connection"),
    }
}

async fn write_status(stream: &mut BoxStream, status: HelloStatus) -> io::Result<()> {
    let hello = ServerHello::rejected(status);
    timeout(HELLO_TIMEOUT, stream.write_all(&hello.encode())).await??;
    Ok(())
}

async fn reply(stream: &mut BoxStream, status: HelloStatus) -> io::Result<()> {
    write_status(stream, status).await?;
    let _ = stream.flush().await;
    Ok(())
}
