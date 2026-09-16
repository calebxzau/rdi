use anyhow::{Context, Result};
use std::time::Instant;
use tokio::{io::copy_bidirectional, net::TcpStream};

pub async fn relay(mut left: TcpStream, mut right: TcpStream) -> Result<(u64, u64)> {
    let started = Instant::now();
    left.set_nodelay(true).context("enable TCP_NODELAY on left stream")?;
    right.set_nodelay(true).context("enable TCP_NODELAY on right stream")?;
    let (left_to_right, right_to_left) = copy_bidirectional(&mut left, &mut right).await.context("relay TCP streams")?;
    tracing::debug!(elapsed_ms = started.elapsed().as_millis() as u64, left_to_right, right_to_left, "relay closed");
    Ok((left_to_right, right_to_left))
}
