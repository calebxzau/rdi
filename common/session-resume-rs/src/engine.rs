//! The session task shared by both ends: it moves bytes between the application
//! stream and whichever network path is currently attached.

use crate::{
    buffer::SendBuffer,
    protocol::{Frame, HelloStatus, MAX_DATA, ProtocolError, ServerHello, VERSION},
};
use std::{
    future::pending,
    io,
    net::SocketAddr,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadHalf, WriteHalf, split},
    sync::mpsc,
    time::{Instant, MissedTickBehavior, interval, timeout},
};

const IO_BUFFER: usize = MAX_DATA;
const FLUSH_TIMEOUT: Duration = Duration::from_secs(1);
const RETAINED_RECV_CAPACITY: usize = 64 * 1024;
const NORMAL_CLOSE: u8 = 0;
pub(crate) const REASON_SILENT: &str = "silent";
pub(crate) const REASON_ACK_STALLED: &str = "ack stalled";

pub trait AsyncStream: AsyncRead + AsyncWrite + Send + Unpin {}
impl<T: AsyncRead + AsyncWrite + Send + Unpin> AsyncStream for T {}
pub type BoxStream = Box<dyn AsyncStream>;

/// Path health checks; only the client probes, the anchor just answers.
#[derive(Clone, Copy, Debug)]
pub struct Liveness {
    pub ping_interval: Duration,
    pub dead_after: Duration,
}

#[derive(Clone, Copy, Debug)]
pub struct Tuning {
    pub send_window: usize,
    pub recv_limit: usize,
    pub ack_bytes: u64,
    pub ack_interval: Duration,
    pub tick: Duration,
    /// How long to hold the session after the last byte from the peer.
    pub expire_after: Duration,
    /// How long a brand-new session may wait for its first path.
    pub first_attach_timeout: Duration,
    pub liveness: Option<Liveness>,
}

impl Tuning {
    pub fn client() -> Self {
        Self {
            send_window: 128 * 1024,
            recv_limit: 1024 * 1024,
            ack_bytes: 64 * 1024,
            ack_interval: Duration::from_millis(100),
            tick: Duration::from_millis(100),
            // Must stay below Minecraft's 30 second client read timeout.
            expire_after: Duration::from_secs(25),
            first_attach_timeout: Duration::from_secs(10),
            liveness: Some(Liveness {
                ping_interval: Duration::from_secs(1),
                dead_after: Duration::from_secs(3),
            }),
        }
    }

    pub fn anchor() -> Self {
        Self {
            send_window: 1024 * 1024,
            recv_limit: 128 * 1024,
            ack_bytes: 64 * 1024,
            ack_interval: Duration::from_millis(100),
            tick: Duration::from_millis(100),
            expire_after: Duration::from_secs(20),
            first_attach_timeout: Duration::from_secs(20),
            liveness: None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EndReason {
    /// The byte stream ended normally on one side and the other side confirmed.
    Closed,
    /// No path came back in time.
    Expired,
    /// The other end no longer knows this session.
    Gone,
    /// A peer broke the offset rules, so the stream can no longer be trusted.
    Violation(&'static str),
}

pub(crate) enum Control {
    AttachAnchor {
        stream: BoxStream,
        generation: u32,
        peer_recv: u64,
        peer: SocketAddr,
    },
    AttachClient {
        stream: BoxStream,
        peer_recv: u64,
        send_from: u64,
    },
    Terminate,
}

#[derive(Debug)]
pub(crate) enum Event {
    /// `path_serial` lets the supervisor ignore news about a path it already replaced.
    PathLost {
        path_serial: u64,
        hard: bool,
        reason: &'static str,
    },
    /// The anchor accepted a connection for this session.
    AnchorAttached {
        peer: SocketAddr,
        silent_ms: u64,
        replay_bytes: u64,
    },
    Ended(EndReason),
}

#[derive(Default)]
pub(crate) struct Shared {
    recv_total: AtomicU64,
}

impl Shared {
    pub(crate) fn recv_total(&self) -> u64 {
        self.recv_total.load(Ordering::Acquire)
    }
}

struct State {
    send: SendBuffer,
    /// Bytes accepted from the path and waiting for the application.
    recv: Vec<u8>,
    recv_position: usize,
    out: Vec<u8>,
    out_position: usize,
    inbound: Vec<u8>,
    /// Stream offset of the next data byte on the current path.
    path_position: u64,
    recv_total: u64,
    delivered: u64,
    last_acked: u64,
    last_ack_at: Instant,
    force_ack: bool,
    generation: u32,
    app_eof: bool,
    app_gone: bool,
    close_sent: bool,
    peer_close: Option<u64>,
    app_shut: bool,
    close_ack_queued: bool,
    attached_once: bool,
    last_recv: Instant,
    last_ping: Instant,
    ping_due: bool,
    last_ack_progress: Instant,
    next_nonce: u32,
    path_serial: u64,
    hard_reported: bool,
    soft_reported: bool,
}

impl State {
    fn new(tuning: &Tuning, now: Instant) -> Self {
        Self {
            send: SendBuffer::new(tuning.send_window),
            recv: Vec::new(),
            recv_position: 0,
            out: Vec::new(),
            out_position: 0,
            inbound: Vec::new(),
            path_position: 0,
            recv_total: 0,
            delivered: 0,
            last_acked: 0,
            last_ack_at: now,
            force_ack: false,
            generation: 0,
            app_eof: false,
            app_gone: false,
            close_sent: false,
            peer_close: None,
            app_shut: false,
            close_ack_queued: false,
            attached_once: false,
            last_recv: now,
            last_ping: now,
            ping_due: false,
            last_ack_progress: now,
            next_nonce: 0,
            path_serial: 0,
            hard_reported: false,
            soft_reported: false,
        }
    }

    fn pending_recv(&self) -> &[u8] {
        &self.recv[self.recv_position..]
    }

    fn pending_out(&self) -> &[u8] {
        &self.out[self.out_position..]
    }

    fn queue(&mut self, frame: Frame<'_>) {
        frame.encode_into(&mut self.out);
    }

    /// Prepares the state for a path that replays from the given positions.
    fn begin_path(&mut self, path_position: u64, now: Instant) {
        self.out.clear();
        self.out_position = 0;
        self.inbound.clear();
        self.path_position = path_position;
        self.close_sent = false;
        self.close_ack_queued = false;
        self.force_ack = true;
        self.attached_once = true;
        self.last_recv = now;
        self.last_ack_progress = now;
        // Probe straight away so a bad replacement path is noticed quickly.
        self.ping_due = true;
        self.path_serial += 1;
        self.hard_reported = false;
        self.soft_reported = false;
    }

    /// Tops up the outgoing bytes, keeping at most about one data frame queued.
    fn fill_out(&mut self) {
        if self.out_position == self.out.len() {
            self.out.clear();
            self.out_position = 0;
        }
        if self.pending_out().len() < MAX_DATA && !self.send.fully_sent() {
            let header = self.out.len();
            self.out.extend_from_slice(&[0, 0, 0]);
            let count = self.send.take_next(MAX_DATA, &mut self.out);
            self.out[header] = 0x01;
            self.out[header + 1..header + 3].copy_from_slice(&(count as u16).to_be_bytes());
        }
        if self.app_eof && self.peer_close.is_none() && !self.close_sent && self.send.fully_sent() {
            let final_offset = self.send.end();
            self.queue(Frame::Close {
                final_offset,
                reason: NORMAL_CLOSE,
            });
            self.close_sent = true;
        }
    }

    fn accept_data(&mut self, payload: &[u8], tuning: &Tuning) -> Result<(), &'static str> {
        let start = self.path_position;
        let end = start + payload.len() as u64;
        self.path_position = end;
        if start > self.recv_total {
            return Err("stream gap after replay");
        }
        if end <= self.recv_total {
            return Ok(());
        }
        let fresh = &payload[(self.recv_total - start) as usize..];
        self.recv_total = end;
        if self.app_gone {
            self.delivered = self.recv_total;
            return Ok(());
        }
        if self.recv_total - self.delivered > tuning.recv_limit as u64 {
            return Err("peer exceeded its send window");
        }
        self.recv.extend_from_slice(fresh);
        Ok(())
    }

    fn mark_written(&mut self, count: usize) {
        self.recv_position += count;
        self.delivered += count as u64;
        if self.recv_position == self.recv.len() {
            self.recv.clear();
            self.recv_position = 0;
            if self.recv.capacity() > RETAINED_RECV_CAPACITY {
                self.recv = Vec::new();
            }
        }
    }

    /// The application can no longer accept bytes, so nothing else is waiting.
    fn mark_app_gone(&mut self) {
        self.app_gone = true;
        self.app_eof = true;
        self.recv = Vec::new();
        self.recv_position = 0;
        self.delivered = self.recv_total;
    }
}

enum Inbound {
    Continue,
    PathError,
    Finished(EndReason),
}

fn process_inbound(state: &mut State, shared: &Shared, tuning: &Tuning, now: Instant) -> Inbound {
    let mut position = 0;
    let bytes = std::mem::take(&mut state.inbound);
    let result = loop {
        let (frame, used) = match Frame::decode(&bytes[position..]) {
            Ok(Some(decoded)) => decoded,
            Ok(None) => break Inbound::Continue,
            Err(ProtocolError(_)) => break Inbound::PathError,
        };
        position += used;
        match frame {
            Frame::Data(payload) => {
                if let Err(reason) = state.accept_data(payload, tuning) {
                    break Inbound::Finished(EndReason::Violation(reason));
                }
                shared.recv_total.store(state.recv_total, Ordering::Release);
            }
            Frame::Ack(offset) => match state.send.ack(offset) {
                Ok(true) => {
                    state.last_ack_progress = now;
                    state.soft_reported = false;
                }
                Ok(false) => {}
                Err(ProtocolError(reason)) => {
                    break Inbound::Finished(EndReason::Violation(reason));
                }
            },
            Frame::Ping(nonce) => state.queue(Frame::Pong(nonce)),
            Frame::Pong(_) => {}
            Frame::Close { final_offset, .. } => {
                if final_offset != state.recv_total {
                    break Inbound::Finished(EndReason::Violation("close offset mismatch"));
                }
                state.peer_close = Some(final_offset);
            }
            Frame::CloseAck => {
                if state.app_eof {
                    break Inbound::Finished(EndReason::Closed);
                }
                break Inbound::PathError;
            }
        }
    };
    state.inbound = bytes;
    state.inbound.drain(..position);
    result
}

async fn read_path(path: &mut Option<ReadHalf<BoxStream>>, buffer: &mut [u8]) -> io::Result<usize> {
    match path {
        Some(reader) => reader.read(buffer).await,
        None => pending().await,
    }
}

async fn write_path(path: &mut Option<WriteHalf<BoxStream>>, bytes: &[u8]) -> io::Result<usize> {
    match path {
        Some(writer) => writer.write(bytes).await,
        None => pending().await,
    }
}

async fn flush_remaining(path: &mut Option<WriteHalf<BoxStream>>, state: &State) {
    if let Some(writer) = path {
        let _ = timeout(FLUSH_TIMEOUT, async {
            writer.write_all(state.pending_out()).await?;
            writer.flush().await
        })
        .await;
    }
}

pub(crate) async fn run<A>(
    app: A,
    mut control: mpsc::Receiver<Control>,
    events: mpsc::UnboundedSender<Event>,
    shared: Arc<Shared>,
    tuning: Tuning,
) -> EndReason
where
    A: AsyncRead + AsyncWrite + Send + Unpin,
{
    let started = Instant::now();
    let (mut app_read, mut app_write) = split(app);
    let mut state = State::new(&tuning, started);
    let mut path_read: Option<ReadHalf<BoxStream>> = None;
    let mut path_write: Option<WriteHalf<BoxStream>> = None;
    let mut app_buffer = vec![0u8; IO_BUFFER];
    let mut path_buffer = vec![0u8; IO_BUFFER];
    let mut tick = interval(tuning.tick);
    tick.set_missed_tick_behavior(MissedTickBehavior::Delay);

    macro_rules! lose_path {
        ($reason:expr) => {{
            path_read = None;
            path_write = None;
            let _ = events.send(Event::PathLost {
                path_serial: state.path_serial,
                hard: true,
                reason: $reason,
            });
        }};
    }

    loop {
        if path_write.is_some() {
            state.fill_out();
        }
        if state.peer_close.is_some() && state.pending_recv().is_empty() {
            if !state.app_shut {
                state.app_shut = true;
                let _ = timeout(FLUSH_TIMEOUT, app_write.shutdown()).await;
                state.mark_app_gone();
            }
            if path_write.is_some() && !state.close_ack_queued {
                state.queue(Frame::CloseAck);
                state.close_ack_queued = true;
            }
            if state.close_ack_queued && path_write.is_some() {
                flush_remaining(&mut path_write, &state).await;
                return EndReason::Closed;
            }
        }
        let can_read_app = !state.app_eof && state.peer_close.is_none() && state.send.free() > 0;
        let app_limit = state.send.free().min(app_buffer.len());

        tokio::select! {
            biased;
            message = control.recv() => match message {
                Some(Control::AttachAnchor { stream, generation, peer_recv, peer }) => {
                    if generation <= state.generation {
                        reject(stream, HelloStatus::StaleGeneration);
                        continue;
                    }
                    let send_from = match state.send.rewind(peer_recv) {
                        Ok(position) => position,
                        Err(ProtocolError(reason)) => {
                            reject(stream, HelloStatus::InvalidOffset);
                            return EndReason::Violation(reason);
                        }
                    };
                    state.generation = generation;
                    let now = Instant::now();
                    let _ = events.send(Event::AnchorAttached {
                        peer,
                        silent_ms: (now - state.last_recv).as_millis() as u64,
                        replay_bytes: state.send.high_water() - send_from,
                    });
                    state.begin_path(state.recv_total, now);
                    let hello = ServerHello {
                        version: VERSION,
                        status: HelloStatus::Ok,
                        recv_offset: state.recv_total,
                        send_from,
                        grace_ms: tuning.expire_after.as_millis() as u32,
                    };
                    state.out.extend_from_slice(&hello.encode());
                    let (reader, writer) = split(stream);
                    path_read = Some(reader);
                    path_write = Some(writer);
                }
                Some(Control::AttachClient { stream, peer_recv, send_from }) => {
                    if send_from > state.recv_total {
                        return EndReason::Violation("replay starts after a stream gap");
                    }
                    if peer_recv < state.send.base() {
                        return EndReason::Violation("peer lost acknowledged bytes");
                    }
                    if let Err(ProtocolError(reason)) = state.send.rewind(peer_recv) {
                        return EndReason::Violation(reason);
                    }
                    state.begin_path(send_from, Instant::now());
                    let (reader, writer) = split(stream);
                    path_read = Some(reader);
                    path_write = Some(writer);
                }
                Some(Control::Terminate) | None => return EndReason::Gone,
            },
            _ = tick.tick() => {
                let now = Instant::now();
                if !state.attached_once && now - started >= tuning.first_attach_timeout {
                    return EndReason::Expired;
                }
                if state.attached_once && now - state.last_recv >= tuning.expire_after {
                    return EndReason::Expired;
                }
                if path_write.is_some() {
                    let due = state.delivered > state.last_acked
                        && now - state.last_ack_at >= tuning.ack_interval;
                    if due || state.force_ack {
                        queue_ack(&mut state, now);
                    }
                    if let Some(liveness) = tuning.liveness {
                        if state.ping_due || now - state.last_ping >= liveness.ping_interval {
                            state.ping_due = false;
                            let nonce = state.next_nonce;
                            state.next_nonce = nonce.wrapping_add(1);
                            state.queue(Frame::Ping(nonce));
                            state.last_ping = now;
                        }
                        if !state.hard_reported && now - state.last_recv >= liveness.dead_after {
                            state.hard_reported = true;
                            let _ = events.send(Event::PathLost {
                                path_serial: state.path_serial,
                                hard: true,
                                reason: REASON_SILENT,
                            });
                        }
                        if state.send.in_flight() == 0 {
                            state.last_ack_progress = now;
                        } else if !state.soft_reported
                            && now - state.last_ack_progress >= liveness.dead_after
                        {
                            state.soft_reported = true;
                            let _ = events.send(Event::PathLost {
                                path_serial: state.path_serial,
                                hard: false,
                                reason: REASON_ACK_STALLED,
                            });
                        }
                    }
                }
            }
            written = app_write.write(state.pending_recv()),
                if !state.pending_recv().is_empty() && !state.app_gone =>
            {
                match written {
                    Ok(0) | Err(_) => state.mark_app_gone(),
                    Ok(count) => {
                        state.mark_written(count);
                        if state.delivered - state.last_acked >= tuning.ack_bytes
                            && path_write.is_some()
                        {
                            queue_ack(&mut state, Instant::now());
                        }
                    }
                }
            }
            read = read_path(&mut path_read, &mut path_buffer), if path_read.is_some() => {
                match read {
                    Ok(0) => lose_path!("closed"),
                    Err(_) => lose_path!("read error"),
                    Ok(count) => {
                        let now = Instant::now();
                        state.last_recv = now;
                        state.hard_reported = false;
                        state.inbound.extend_from_slice(&path_buffer[..count]);
                        match process_inbound(&mut state, &shared, &tuning, now) {
                            Inbound::Continue => {}
                            Inbound::PathError => lose_path!("protocol error"),
                            Inbound::Finished(reason) => {
                                flush_remaining(&mut path_write, &state).await;
                                return reason;
                            }
                        }
                    }
                }
            }
            written = write_path(&mut path_write, state.pending_out()),
                if path_write.is_some() && !state.pending_out().is_empty() =>
            {
                match written {
                    Ok(0) | Err(_) => lose_path!("write error"),
                    Ok(count) => state.out_position += count,
                }
            }
            read = app_read.read(&mut app_buffer[..app_limit]), if can_read_app => {
                match read {
                    Ok(0) | Err(_) => state.app_eof = true,
                    Ok(count) => state.send.push(&app_buffer[..count]),
                }
            }
        }
    }
}

fn queue_ack(state: &mut State, now: Instant) {
    let offset = state.delivered;
    state.queue(Frame::Ack(offset));
    state.last_acked = offset;
    state.last_ack_at = now;
    state.force_ack = false;
}

fn reject(mut stream: BoxStream, status: HelloStatus) {
    tokio::spawn(async move {
        let _ = timeout(
            FLUSH_TIMEOUT,
            stream.write_all(&ServerHello::rejected(status).encode()),
        )
        .await;
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(start_paused = true)]
    async fn replayed_data_is_deduplicated_and_gaps_are_fatal() {
        let tuning = Tuning::anchor();
        let mut state = State::new(&tuning, Instant::now());
        state.accept_data(b"abcd", &tuning).unwrap();
        // A replay overlapping what was already accepted only keeps the fresh tail.
        state.path_position = 2;
        state.accept_data(b"cdef", &tuning).unwrap();
        assert_eq!(state.pending_recv(), b"abcdef");
        assert_eq!(state.recv_total, 6);
        // Entirely old data changes nothing.
        state.path_position = 0;
        state.accept_data(b"ab", &tuning).unwrap();
        assert_eq!(state.recv_total, 6);
        // Data past what was received would leave a hole.
        state.path_position = 7;
        assert!(state.accept_data(b"x", &tuning).is_err());
    }

    #[tokio::test(start_paused = true)]
    async fn peer_cannot_exceed_the_receive_limit() {
        let mut tuning = Tuning::anchor();
        tuning.recv_limit = 3;
        let mut state = State::new(&tuning, Instant::now());
        state.accept_data(b"abc", &tuning).unwrap();
        assert!(state.accept_data(b"d", &tuning).is_err());
    }

    #[tokio::test(start_paused = true)]
    async fn data_arriving_after_the_app_left_is_discarded_but_counted() {
        let tuning = Tuning::anchor();
        let mut state = State::new(&tuning, Instant::now());
        state.accept_data(b"abc", &tuning).unwrap();
        state.mark_app_gone();
        state.accept_data(b"def", &tuning).unwrap();
        assert!(state.pending_recv().is_empty());
        assert_eq!((state.recv_total, state.delivered), (6, 6));
    }

    #[tokio::test(start_paused = true)]
    async fn close_is_only_sent_after_all_data() {
        let tuning = Tuning::client();
        let mut state = State::new(&tuning, Instant::now());
        state.send.push(&vec![1; MAX_DATA + 1]);
        state.app_eof = true;
        state.fill_out();
        assert!(!state.close_sent);
        state.out_position = state.out.len();
        state.fill_out();
        assert!(state.close_sent);
    }
}
