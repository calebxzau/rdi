//! A node stand-in for integration runs: forwards TCP and breaks on command.
//!
//! `fault_relay --listen 127.0.0.1:65241 --upstream 127.0.0.1:65230`, then on stdin:
//! `kill`, `freeze`, `heal`, `throttle <KiB per second>`, `stats`, `quit`.

use std::{
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader, stdin},
    net::{TcpListener, TcpStream},
    sync::watch,
    time::sleep,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Faults {
    refuse: bool,
    frozen: bool,
    kills: u64,
    throttle_kib: u64,
}

async fn pump<R, W>(
    mut reader: R,
    mut writer: W,
    mut faults: watch::Receiver<Faults>,
    forwarded: Arc<AtomicU64>,
) where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut buffer = vec![0u8; 16 * 1024];
    loop {
        let count = match reader.read(&mut buffer).await {
            Ok(0) | Err(_) => return,
            Ok(count) => count,
        };
        // Stay silent without closing anything, as a dead route does.
        if faults.wait_for(|state| !state.frozen).await.is_err() {
            return;
        }
        let throttle = faults.borrow().throttle_kib;
        if throttle > 0 {
            sleep(Duration::from_secs_f64(
                count as f64 / (throttle as f64 * 1024.0),
            ))
            .await;
        }
        if writer.write_all(&buffer[..count]).await.is_err() {
            return;
        }
        forwarded.fetch_add(count as u64, Ordering::Relaxed);
    }
}

async fn forward(
    inbound: TcpStream,
    upstream: String,
    faults: watch::Receiver<Faults>,
    up: Arc<AtomicU64>,
    down: Arc<AtomicU64>,
) {
    let kills = faults.borrow().kills;
    let mut kill_watch = faults.clone();
    let Ok(outbound) = TcpStream::connect(upstream).await else {
        return;
    };
    let _ = inbound.set_nodelay(true);
    let _ = outbound.set_nodelay(true);
    let (inbound_read, inbound_write) = inbound.into_split();
    let (outbound_read, outbound_write) = outbound.into_split();
    tokio::select! {
        _ = pump(inbound_read, outbound_write, faults.clone(), up) => {}
        _ = pump(outbound_read, inbound_write, faults.clone(), down) => {}
        _ = kill_watch.wait_for(|state| state.kills != kills) => {}
    }
}

fn argument(name: &str) -> String {
    let arguments: Vec<String> = std::env::args().collect();
    arguments
        .iter()
        .position(|value| value == name)
        .and_then(|index| arguments.get(index + 1))
        .cloned()
        .unwrap_or_else(|| panic!("missing {name}"))
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> std::io::Result<()> {
    let upstream = argument("--upstream");
    let listener = TcpListener::bind(argument("--listen")).await?;
    let (faults, watcher) = watch::channel(Faults {
        refuse: false,
        frozen: false,
        kills: 0,
        throttle_kib: 0,
    });
    let up = Arc::new(AtomicU64::new(0));
    let down = Arc::new(AtomicU64::new(0));
    println!("ready {}", listener.local_addr()?);
    let mut commands = BufReader::new(stdin()).lines();
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let (inbound, _) = accepted?;
                if watcher.borrow().refuse {
                    continue;
                }
                tokio::spawn(forward(
                    inbound,
                    upstream.clone(),
                    watcher.clone(),
                    Arc::clone(&up),
                    Arc::clone(&down),
                ));
            }
            line = commands.next_line() => {
                let Some(line) = line? else { return Ok(()) };
                let mut words = line.split_whitespace();
                match words.next() {
                    Some("kill") => faults.send_modify(|state| {
                        state.kills += 1;
                        state.refuse = true;
                    }),
                    Some("freeze") => faults.send_modify(|state| state.frozen = true),
                    Some("heal") => faults.send_modify(|state| {
                        state.refuse = false;
                        state.frozen = false;
                        state.throttle_kib = 0;
                    }),
                    Some("throttle") => {
                        let rate = words.next().and_then(|value| value.parse().ok()).unwrap_or(0);
                        faults.send_modify(|state| state.throttle_kib = rate);
                    }
                    Some("stats") => println!(
                        "stats up={} down={}",
                        up.load(Ordering::Relaxed),
                        down.load(Ordering::Relaxed)
                    ),
                    Some("quit") => return Ok(()),
                    _ => eprintln!("unknown command: {line}"),
                }
                println!("ok {line}");
            }
        }
    }
}
