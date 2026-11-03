//! Session registry on the anchor, the end next to the game server. Connections
//! arriving through any node end up here, and the application stream outlives them.

use crate::{
    engine::{self, BoxStream, Control, Event, Shared, Tuning},
    protocol::{CLIENT_HELLO_LEN, ClientHello, HelloStatus, ServerHello, Token, VERSION},
};
use std::{
    collections::HashMap,
    io,
    net::SocketAddr,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt, DuplexStream, duplex},
    sync::mpsc,
    time::timeout,
};

const HELLO_TIMEOUT: Duration = Duration::from_secs(5);
const APP_PIPE_CAPACITY: usize = 16 * 1024;
const CONTROL_QUEUE: usize = 8;
/// A client probes once before attaching, so more than this is not a real client.
const MAX_HELLOS_PER_CONNECTION: usize = 4;

type SessionHandler = dyn Fn(DuplexStream, SocketAddr) + Send + Sync;

pub struct Anchor {
    sessions: Mutex<HashMap<Token, mpsc::Sender<Control>>>,
    max_sessions: usize,
    tuning: Tuning,
    on_session: Box<SessionHandler>,
}

impl Anchor {
    /// `on_session` receives the application end of each new session and must
    /// serve it without blocking, usually by spawning a task.
    pub fn new(
        max_sessions: usize,
        tuning: Tuning,
        on_session: impl Fn(DuplexStream, SocketAddr) + Send + Sync + 'static,
    ) -> Arc<Self> {
        Arc::new(Self {
            sessions: Mutex::new(HashMap::new()),
            max_sessions,
            tuning,
            on_session: Box::new(on_session),
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
                write_status(&mut stream, self.probe_status(&hello)).await?;
                continue;
            }
            let attach = Control::AttachAnchor {
                stream,
                generation: hello.generation,
                peer_recv: hello.recv_offset,
            };
            let outcome = if hello.resume {
                self.resume(&hello.token, attach).await
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

    async fn resume(&self, token: &Token, attach: Control) -> Result<(), (BoxStream, HelloStatus)> {
        let sender = self
            .sessions
            .lock()
            .expect("session map poisoned")
            .get(token)
            .cloned();
        let Some(sender) = sender else {
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
        tokio::spawn(async move {
            // The anchor waits passively, so it has no use for path events.
            let (events, _ignored) = mpsc::unbounded_channel::<Event>();
            let shared = Arc::new(Shared::default());
            engine::run(session_end, control, events, shared, tuning).await;
            registry
                .sessions
                .lock()
                .expect("session map poisoned")
                .remove(&token);
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
