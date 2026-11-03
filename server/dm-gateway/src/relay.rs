use anyhow::{Context, Result};
use std::{
    io,
    pin::Pin,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    task::{Context as TaskContext, Poll},
    time::Instant,
};
use tokio::{
    io::{AsyncRead, AsyncWrite, ReadBuf, copy_bidirectional},
    net::TcpStream,
};
use uuid::Uuid;

/// Identifies one guest relay in the traffic log.
#[derive(Debug, Clone, Copy)]
pub struct RelayLabel {
    pub host: Uuid,
    pub session: Uuid,
    pub connection: u64,
}

/// Relays a guest stream to the host data stream; returns (guest to host, host to guest) bytes.
pub async fn relay(guest: TcpStream, host: TcpStream, label: RelayLabel) -> Result<(u64, u64)> {
    guest
        .set_nodelay(true)
        .context("enable TCP_NODELAY on guest stream")?;
    host.set_nodelay(true)
        .context("enable TCP_NODELAY on host stream")?;
    let traffic = TrafficLog::new(label);
    let mut guest = Counted::new(guest, traffic.to_host.clone());
    let mut host = Counted::new(host, traffic.to_guest.clone());
    copy_bidirectional(&mut guest, &mut host)
        .await
        .context("relay TCP streams")
}

/// Logs the bytes relayed so far when the relay ends, including on error or cancellation.
struct TrafficLog {
    label: RelayLabel,
    started: Instant,
    to_host: Arc<AtomicU64>,
    to_guest: Arc<AtomicU64>,
}

impl TrafficLog {
    fn new(label: RelayLabel) -> Self {
        Self {
            label,
            started: Instant::now(),
            to_host: Arc::new(AtomicU64::new(0)),
            to_guest: Arc::new(AtomicU64::new(0)),
        }
    }
}

impl Drop for TrafficLog {
    fn drop(&mut self) {
        tracing::info!(
            host = %self.label.host,
            session = %self.label.session,
            connection = self.label.connection,
            elapsed_ms = self.started.elapsed().as_millis() as u64,
            to_host = self.to_host.load(Ordering::Relaxed),
            to_guest = self.to_guest.load(Ordering::Relaxed),
            "guest relay traffic"
        );
    }
}

/// Counts the bytes read from the wrapped stream; writes pass through.
struct Counted<S> {
    inner: S,
    read: Arc<AtomicU64>,
}

impl<S> Counted<S> {
    fn new(inner: S, read: Arc<AtomicU64>) -> Self {
        Self { inner, read }
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for Counted<S> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let before = buf.filled().len();
        let result = Pin::new(&mut this.inner).poll_read(cx, buf);
        if let Poll::Ready(Ok(())) = result {
            this.read
                .fetch_add((buf.filled().len() - before) as u64, Ordering::Relaxed);
        }
        result
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for Counted<S> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().inner).poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }

    fn poll_write_vectored(
        self: Pin<&mut Self>,
        cx: &mut TaskContext<'_>,
        bufs: &[io::IoSlice<'_>],
    ) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().inner).poll_write_vectored(cx, bufs)
    }

    fn is_write_vectored(&self) -> bool {
        self.inner.is_write_vectored()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };

    async fn pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let (client, accepted) = tokio::join!(TcpStream::connect(address), listener.accept());
        (client.unwrap(), accepted.unwrap().0)
    }

    #[tokio::test]
    async fn relay_counts_both_directions() {
        let (mut guest_end, guest) = pair().await;
        let (host, mut host_end) = pair().await;
        let label = RelayLabel {
            host: Uuid::now_v7(),
            session: Uuid::now_v7(),
            connection: 1,
        };
        let task = tokio::spawn(relay(guest, host, label));

        guest_end.write_all(b"hello").await.unwrap();
        let mut received = [0u8; 5];
        host_end.read_exact(&mut received).await.unwrap();
        assert_eq!(&received, b"hello");
        host_end.write_all(b"world!!").await.unwrap();
        let mut reply = [0u8; 7];
        guest_end.read_exact(&mut reply).await.unwrap();
        assert_eq!(&reply, b"world!!");

        guest_end.shutdown().await.unwrap();
        host_end.shutdown().await.unwrap();
        assert_eq!(task.await.unwrap().unwrap(), (5, 7));
    }
}
