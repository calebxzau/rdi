//! Command-line front end for trying the local proxy without the launcher.
//!
//! `rdi-local-proxy-cli --listen 127.0.0.1:55667 --nodes 127.0.0.1:65241,127.0.0.1:65242`
//! Commands on stdin: `switch <node>`, `nodes <a,b,...>`, `quit`.
//! Session events go to stdout, one per line, prefixed by milliseconds since start.

use rdi_local_proxy::{LocalProxy, LocalProxyConfig};
use rdi_session_resume::client::EventSink;
use std::{net::SocketAddr, sync::Arc, time::Instant};
use tokio::io::{AsyncBufReadExt, BufReader, stdin};

fn argument(name: &str) -> Option<String> {
    let arguments: Vec<String> = std::env::args().collect();
    arguments
        .iter()
        .position(|value| value == name)
        .and_then(|index| arguments.get(index + 1))
        .cloned()
}

fn split_nodes(value: &str) -> Vec<String> {
    value
        .split(',')
        .map(str::trim)
        .filter(|node| !node.is_empty())
        .map(str::to_string)
        .collect()
}

fn main() -> std::io::Result<()> {
    let listen: SocketAddr = argument("--listen")
        .unwrap_or_else(|| "127.0.0.1:55667".to_string())
        .parse()
        .map_err(std::io::Error::other)?;
    let Some(nodes) = argument("--nodes") else {
        eprintln!(
            "usage: rdi-local-proxy-cli [--listen host:port] --nodes host:port[,host:port...]"
        );
        std::process::exit(2);
    };
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()?;
    runtime.block_on(async move {
        let started = Instant::now();
        let sink: EventSink = Arc::new(move |session, event| {
            println!(
                "{} session={session} {event:?}",
                started.elapsed().as_millis()
            );
        });
        let config = LocalProxyConfig {
            bind_host: listen.ip().to_string(),
            preferred_port: listen.port(),
            nodes: split_nodes(&nodes),
            ..LocalProxyConfig::default()
        };
        let proxy = LocalProxy::start(config, sink).await?;
        println!(
            "{} ready {}",
            started.elapsed().as_millis(),
            proxy.address()
        );
        let mut commands = BufReader::new(stdin()).lines();
        while let Some(line) = commands.next_line().await? {
            match line.split_once(' ').unwrap_or((line.as_str(), "")) {
                ("switch", node) => proxy.switch_to(node.trim().to_string()),
                ("nodes", list) => proxy.set_nodes(split_nodes(list)),
                ("quit", _) => break,
                _ => eprintln!("unknown command: {line}"),
            }
        }
        proxy.stop().await;
        Ok(())
    })
}
