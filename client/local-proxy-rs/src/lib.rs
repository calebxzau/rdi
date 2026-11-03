//! The proxy Minecraft connects to on this machine. Each game connection becomes
//! a resumable session, so the node underneath can change while the game stays connected.

#![deny(unsafe_code)]

#[allow(unsafe_code)]
mod ffi;

use rdi_session_resume::{
    BoxStream,
    client::{ClientConfig, Dialer, EventSink, NodeTable, run_client_session},
};
use std::{
    io,
    net::SocketAddr,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
};
use tokio::{
    net::{TcpListener, TcpStream},
    task::{JoinHandle, JoinSet},
};

#[derive(Clone, Debug)]
pub struct LocalProxyConfig {
    pub bind_host: String,
    pub preferred_port: u16,
    pub nodes: Vec<String>,
    pub client: ClientConfig,
}

impl Default for LocalProxyConfig {
    fn default() -> Self {
        Self {
            bind_host: "127.0.0.1".to_string(),
            preferred_port: 55667,
            nodes: Vec::new(),
            client: ClientConfig::default(),
        }
    }
}

pub struct LocalProxy {
    address: SocketAddr,
    table: NodeTable,
    accept_task: JoinHandle<()>,
}

impl LocalProxy {
    /// Binds the preferred port, or any free port when it is taken.
    pub async fn start(config: LocalProxyConfig, sink: EventSink) -> io::Result<Self> {
        let listener = bind(&config.bind_host, config.preferred_port).await?;
        let address = listener.local_addr()?;
        let table = NodeTable::new(config.nodes);
        let accept_task = tokio::spawn(accept_loop(listener, table.clone(), config.client, sink));
        Ok(Self {
            address,
            table,
            accept_task,
        })
    }

    pub fn address(&self) -> SocketAddr {
        self.address
    }

    pub fn nodes(&self) -> Vec<String> {
        self.table.nodes()
    }

    pub fn set_nodes(&self, nodes: Vec<String>) {
        self.table.set_nodes(nodes);
    }

    /// Moves every session to `node`, keeping each one's current path until it answers.
    pub fn switch_to(&self, node: String) {
        self.table.switch_to(node);
    }

    /// Stops accepting and drops every session.
    pub async fn stop(self) {
        self.accept_task.abort();
        let _ = self.accept_task.await;
    }
}

async fn bind(host: &str, preferred_port: u16) -> io::Result<TcpListener> {
    match TcpListener::bind((host, preferred_port)).await {
        Err(error) if error.kind() == io::ErrorKind::AddrInUse => {
            TcpListener::bind((host, 0)).await
        }
        result => result,
    }
}

async fn accept_loop(
    listener: TcpListener,
    table: NodeTable,
    config: ClientConfig,
    sink: EventSink,
) {
    let next_session = AtomicU64::new(1);
    let dialer = tcp_dialer();
    // Dropping the set when this task stops also stops every session in it.
    let mut sessions = JoinSet::new();
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let Ok((game, _)) = accepted else { continue };
                let _ = game.set_nodelay(true);
                let session = next_session.fetch_add(1, Ordering::Relaxed);
                sessions.spawn(run_client_session(
                    game,
                    session,
                    table.subscribe(),
                    Arc::clone(&dialer),
                    config,
                    Arc::clone(&sink),
                ));
            }
            Some(_) = sessions.join_next(), if !sessions.is_empty() => {}
        }
    }
}

fn tcp_dialer() -> Dialer {
    Arc::new(|address: String| {
        Box::pin(async move {
            let stream = TcpStream::connect(address).await?;
            stream.set_nodelay(true)?;
            Ok(Box::new(stream) as BoxStream)
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use rdi_session_resume::{
        Tuning,
        anchor::Anchor,
        client::{AttachReason, SessionEvent},
    };
    use std::{sync::Mutex, time::Duration};
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        sync::watch,
        time::timeout,
    };

    async fn start_echo_anchor() -> SocketAddr {
        let anchor = Anchor::new(8, Tuning::anchor(), |mut app, _peer| {
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
        });
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            loop {
                let (stream, peer) = listener.accept().await.unwrap();
                let anchor = Arc::clone(&anchor);
                tokio::spawn(async move {
                    let _ = anchor.handle_path(Box::new(stream), peer).await;
                });
            }
        });
        address
    }

    /// A node: plain TCP forwarding that breaks when told to.
    async fn start_node(upstream: SocketAddr) -> (String, watch::Sender<u64>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap().to_string();
        let (kill, kills) = watch::channel(0u64);
        tokio::spawn(async move {
            loop {
                let (mut inbound, _) = listener.accept().await.unwrap();
                let mut kills = kills.clone();
                let generation = *kills.borrow_and_update();
                tokio::spawn(async move {
                    let mut outbound = TcpStream::connect(upstream).await.unwrap();
                    tokio::select! {
                        _ = tokio::io::copy_bidirectional(&mut inbound, &mut outbound) => {}
                        _ = kills.wait_for(|value| *value != generation) => {}
                    }
                });
            }
        });
        (address, kill)
    }

    fn collecting_sink() -> (EventSink, Arc<Mutex<Vec<SessionEvent>>>) {
        let log = Arc::new(Mutex::new(Vec::new()));
        let sink_log = Arc::clone(&log);
        let sink: EventSink = Arc::new(move |_id, event| sink_log.lock().unwrap().push(event));
        (sink, log)
    }

    async fn proxy_with(
        nodes: Vec<String>,
    ) -> (LocalProxy, TcpStream, Arc<Mutex<Vec<SessionEvent>>>) {
        let (sink, log) = collecting_sink();
        let config = LocalProxyConfig {
            preferred_port: 0,
            nodes,
            ..LocalProxyConfig::default()
        };
        let proxy = LocalProxy::start(config, sink).await.unwrap();
        let game = TcpStream::connect(proxy.address()).await.unwrap();
        (proxy, game, log)
    }

    async fn round_trip(game: &mut TcpStream, message: &[u8]) {
        game.write_all(message).await.unwrap();
        let mut echoed = vec![0u8; message.len()];
        timeout(Duration::from_secs(10), game.read_exact(&mut echoed))
            .await
            .expect("echo timed out")
            .unwrap();
        assert_eq!(echoed, message);
    }

    fn attached(log: &Mutex<Vec<SessionEvent>>) -> Vec<(String, AttachReason)> {
        log.lock()
            .unwrap()
            .iter()
            .filter_map(|event| match event {
                SessionEvent::Attached { node, reason, .. } => Some((node.clone(), *reason)),
                _ => None,
            })
            .collect()
    }

    #[tokio::test]
    async fn game_connection_survives_losing_its_node() {
        let anchor = start_echo_anchor().await;
        let (node_a, kill_a) = start_node(anchor).await;
        let (node_b, _kill_b) = start_node(anchor).await;
        let (_proxy, mut game, log) = proxy_with(vec![node_a.clone(), node_b.clone()]).await;

        round_trip(&mut game, b"before").await;
        kill_a.send_modify(|value| *value += 1);
        round_trip(&mut game, b"after").await;

        assert_eq!(
            attached(&log),
            [
                (node_a, AttachReason::Initial),
                (node_b, AttachReason::Failover)
            ]
        );
    }

    #[tokio::test]
    async fn manual_switch_moves_a_running_game() {
        let anchor = start_echo_anchor().await;
        let (node_a, _kill_a) = start_node(anchor).await;
        let (node_b, _kill_b) = start_node(anchor).await;
        let (proxy, mut game, log) = proxy_with(vec![node_a.clone(), node_b.clone()]).await;

        round_trip(&mut game, b"before").await;
        proxy.switch_to(node_b.clone());
        timeout(Duration::from_secs(5), async {
            while attached(&log).len() < 2 {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .expect("switch did not happen");
        round_trip(&mut game, b"after").await;

        assert_eq!(attached(&log)[1], (node_b.clone(), AttachReason::Manual));
        assert_eq!(proxy.nodes()[0], node_b);
    }

    #[tokio::test]
    async fn busy_preferred_port_falls_back_to_a_free_one() {
        let taken = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = taken.local_addr().unwrap().port();
        let (sink, _log) = collecting_sink();
        let config = LocalProxyConfig {
            preferred_port: port,
            ..LocalProxyConfig::default()
        };
        let proxy = LocalProxy::start(config, sink).await.unwrap();
        assert_ne!(proxy.address().port(), port);
        proxy.stop().await;
    }

    #[tokio::test]
    async fn stop_closes_running_games() {
        let anchor = start_echo_anchor().await;
        let (proxy, mut game, _log) = proxy_with(vec![anchor.to_string()]).await;
        round_trip(&mut game, b"hello").await;

        let address = proxy.address();
        proxy.stop().await;

        let mut rest = Vec::new();
        timeout(Duration::from_secs(2), game.read_to_end(&mut rest))
            .await
            .expect("game connection stayed open")
            .ok();
        assert!(TcpStream::connect(address).await.is_err());
    }
}
