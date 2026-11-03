//! A simulated network of nodes in front of an in-process anchor.

#![allow(dead_code)]

use rdi_session_resume::{
    BoxStream, Tuning,
    anchor::Anchor,
    client::{ClientConfig, Dialer, EventSink, NodeTable, SessionEvent, spawn_client_session},
};
use std::{
    collections::HashMap,
    io,
    net::SocketAddr,
    sync::{Arc, Mutex},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt, DuplexStream, duplex},
    sync::watch,
};

const LINK_BUFFER: usize = 64 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Faults {
    /// New connections are refused, as when the node is switched off.
    pub refuse: bool,
    /// Bytes from the client are held back, while connections are still accepted.
    pub freeze_up: bool,
    /// Bytes towards the client are held back, while connections are still accepted.
    pub freeze_down: bool,
    /// Bumped to break every existing connection.
    pub kills: u64,
}

impl Faults {
    pub const HEALTHY: Self = Self {
        refuse: false,
        freeze_up: false,
        freeze_down: false,
        kills: 0,
    };
}

pub struct Network {
    nodes: Mutex<HashMap<String, watch::Sender<Faults>>>,
    anchor: Mutex<Arc<Anchor>>,
}

impl Network {
    pub fn new(names: &[&str], anchor: Arc<Anchor>) -> Arc<Self> {
        let nodes = names
            .iter()
            .map(|name| (name.to_string(), watch::channel(Faults::HEALTHY).0))
            .collect();
        Arc::new(Self {
            nodes: Mutex::new(nodes),
            anchor: Mutex::new(anchor),
        })
    }

    pub fn replace_anchor(&self, anchor: Arc<Anchor>) {
        *self.anchor.lock().unwrap() = anchor;
    }

    fn update(&self, node: &str, change: impl FnOnce(&mut Faults)) {
        self.nodes.lock().unwrap()[node].send_modify(change);
    }

    /// Breaks every connection through the node and refuses new ones.
    pub fn kill(&self, node: &str) {
        self.update(node, |faults| {
            faults.kills += 1;
            faults.refuse = true;
        });
    }

    /// The node keeps accepting connections but forwards nothing.
    pub fn freeze(&self, node: &str) {
        self.update(node, |faults| {
            faults.freeze_up = true;
            faults.freeze_down = true;
        });
    }

    pub fn freeze_up(&self, node: &str) {
        self.update(node, |faults| faults.freeze_up = true);
    }

    pub fn heal(&self, node: &str) {
        self.update(node, |faults| {
            faults.refuse = false;
            faults.freeze_up = false;
            faults.freeze_down = false;
        });
    }

    pub fn dialer(self: &Arc<Self>) -> Dialer {
        let network = Arc::clone(self);
        Arc::new(move |node: String| {
            let network = Arc::clone(&network);
            Box::pin(async move { network.dial(&node) })
        })
    }

    fn dial(&self, node: &str) -> io::Result<BoxStream> {
        let faults = self.nodes.lock().unwrap()[node].subscribe();
        if faults.borrow().refuse {
            return Err(io::Error::new(io::ErrorKind::ConnectionRefused, "refused"));
        }
        let (client_end, link_client) = duplex(LINK_BUFFER);
        let (link_anchor, anchor_end) = duplex(LINK_BUFFER);
        let anchor = Arc::clone(&self.anchor.lock().unwrap());
        tokio::spawn(async move {
            let peer: SocketAddr = "127.0.0.1:1".parse().unwrap();
            let _ = anchor.handle_path(Box::new(anchor_end), peer).await;
        });
        tokio::spawn(forward_link(link_client, link_anchor, faults));
        Ok(Box::new(client_end))
    }
}

async fn forward_link(client: DuplexStream, anchor: DuplexStream, faults: watch::Receiver<Faults>) {
    let kills = faults.borrow().kills;
    let (client_read, client_write) = tokio::io::split(client);
    let (anchor_read, anchor_write) = tokio::io::split(anchor);
    let mut kill_watch = faults.clone();
    tokio::select! {
        _ = pump(client_read, anchor_write, faults.clone(), |f| f.freeze_up) => {}
        _ = pump(anchor_read, client_write, faults.clone(), |f| f.freeze_down) => {}
        _ = kill_watch.wait_for(|f| f.kills != kills) => {}
    }
}

async fn pump<R, W>(
    mut reader: R,
    mut writer: W,
    mut faults: watch::Receiver<Faults>,
    frozen: impl Fn(&Faults) -> bool,
) where
    R: tokio::io::AsyncRead + Unpin,
    W: tokio::io::AsyncWrite + Unpin,
{
    let mut buffer = vec![0u8; 16 * 1024];
    loop {
        let count = match reader.read(&mut buffer).await {
            Ok(0) | Err(_) => return,
            Ok(count) => count,
        };
        // Bytes caught by a freeze stay in the link until it is healed or broken.
        if faults.wait_for(|state| !frozen(state)).await.is_err() {
            return;
        }
        if writer.write_all(&buffer[..count]).await.is_err() {
            return;
        }
    }
}

#[derive(Clone, Default)]
pub struct EventLog(Arc<Mutex<Vec<SessionEvent>>>);

impl EventLog {
    pub fn sink(&self) -> EventSink {
        let log = self.clone();
        Arc::new(move |_session, event| log.0.lock().unwrap().push(event))
    }

    pub fn all(&self) -> Vec<SessionEvent> {
        self.0.lock().unwrap().clone()
    }

    pub fn attached_nodes(&self) -> Vec<String> {
        self.all()
            .into_iter()
            .filter_map(|event| match event {
                SessionEvent::Attached { node, .. } => Some(node),
                _ => None,
            })
            .collect()
    }

    /// The most recent time from losing a path to being attached again.
    pub fn last_elapsed_ms(&self) -> Option<u64> {
        self.all().into_iter().rev().find_map(|event| match event {
            SessionEvent::Attached { elapsed_ms, .. } => Some(elapsed_ms),
            _ => None,
        })
    }
}

pub struct ClientHandle {
    pub app: DuplexStream,
    pub table: NodeTable,
    pub log: EventLog,
}

pub fn connect(network: &Arc<Network>, nodes: &[&str]) -> ClientHandle {
    connect_with(network, nodes, ClientConfig::default())
}

pub fn connect_with(network: &Arc<Network>, nodes: &[&str], config: ClientConfig) -> ClientHandle {
    let (app, session_end) = duplex(16 * 1024);
    let table = NodeTable::new(nodes.iter().map(|node| node.to_string()).collect());
    let log = EventLog::default();
    spawn_client_session(
        session_end,
        1,
        table.subscribe(),
        network.dialer(),
        config,
        log.sink(),
    );
    ClientHandle { app, table, log }
}

/// An anchor whose sessions simply echo what they receive.
pub fn echo_anchor() -> Arc<Anchor> {
    Anchor::new(64, Tuning::anchor(), |mut app, _peer| {
        tokio::spawn(async move {
            let mut buffer = vec![0u8; 16 * 1024];
            loop {
                let count = match app.read(&mut buffer).await {
                    Ok(0) | Err(_) => return,
                    Ok(count) => count,
                };
                if app.write_all(&buffer[..count]).await.is_err() {
                    return;
                }
            }
        });
    })
}

/// An anchor that hands the application end of each session to the test.
pub fn manual_anchor() -> (
    Arc<Anchor>,
    tokio::sync::mpsc::UnboundedReceiver<DuplexStream>,
) {
    let (sender, receiver) = tokio::sync::mpsc::unbounded_channel();
    let anchor = Anchor::new(64, Tuning::anchor(), move |app, _peer| {
        let _ = sender.send(app);
    });
    (anchor, receiver)
}

/// A small deterministic generator, so a failing seed can be replayed.
pub struct Rng(pub u64);

impl Rng {
    pub fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    pub fn below(&mut self, limit: u64) -> u64 {
        self.next() % limit
    }
}

pub fn pattern_byte(seed: u64, offset: u64) -> u8 {
    (offset.wrapping_mul(2_654_435_761).wrapping_add(seed) >> 13) as u8 ^ offset as u8
}
