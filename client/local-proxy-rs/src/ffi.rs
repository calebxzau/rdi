//! C interface loaded into the launcher process. One proxy per process.
//!
//! Every entry point returns a negative code on failure, with details in `rdi_lp_last_error`,
//! and never lets a panic cross into the JVM. Rust threads never call back into the JVM;
//! the launcher polls for events instead. Strings are NUL-terminated UTF-8 on the way in,
//! and raw UTF-8 with a returned byte count on the way out.

use crate::{LocalProxy, LocalProxyConfig};
use rdi_session_resume::{
    EndReason,
    client::{AttachReason, EventSink, SessionEvent},
};
use std::{
    collections::VecDeque,
    ffi::{CStr, c_char},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{Arc, Mutex, MutexGuard, PoisonError},
    time::Duration,
};
use tokio::runtime::Runtime;

const ABI_VERSION: u32 = 1;
const WORKER_THREADS: usize = 2;
const MAX_QUEUED_EVENTS: usize = 512;

const OK: i32 = 0;
const ERR_NULL_POINTER: i32 = -1;
const ERR_INVALID_UTF8: i32 = -2;
const ERR_BUFFER_TOO_SMALL: i32 = -3;
const ERR_NOT_RUNNING: i32 = -4;
const ERR_INVALID_ARGUMENT: i32 = -5;
const ERR_IO: i32 = -6;
const ERR_PANIC: i32 = -7;

struct Failure {
    code: i32,
    message: String,
}

impl Failure {
    fn new(code: i32, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    fn null_pointer(name: &str) -> Self {
        Self::new(ERR_NULL_POINTER, format!("{name} is null"))
    }

    fn not_running() -> Self {
        Self::new(ERR_NOT_RUNNING, "local proxy is not running")
    }
}

struct Running {
    runtime: Runtime,
    proxy: LocalProxy,
    port: u16,
}

impl Running {
    fn start(host: &str, preferred_port: u16, nodes: Vec<String>) -> Result<Self, Failure> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(WORKER_THREADS)
            .thread_name("rdi-local-proxy")
            .enable_all()
            .build()
            .map_err(|error| Failure::new(ERR_IO, format!("runtime: {error}")))?;
        let config = LocalProxyConfig {
            bind_host: host.to_string(),
            preferred_port,
            nodes,
            ..LocalProxyConfig::default()
        };
        let sink: EventSink =
            Arc::new(|session, event| lock(&EVENTS).push(format_event(session, &event)));
        let proxy = runtime
            .block_on(LocalProxy::start(config, sink))
            .map_err(|error| Failure::new(ERR_IO, format!("bind: {error}")))?;
        let port = proxy.address().port();
        Ok(Self {
            runtime,
            proxy,
            port,
        })
    }
}

struct EventQueue {
    lines: VecDeque<String>,
    dropped: u64,
}

impl EventQueue {
    const fn new() -> Self {
        Self {
            lines: VecDeque::new(),
            dropped: 0,
        }
    }

    fn push(&mut self, line: String) {
        if self.lines.len() >= MAX_QUEUED_EVENTS {
            self.lines.pop_front();
            self.dropped += 1;
        }
        self.lines.push_back(line);
    }

    /// Moves whole lines into `out`. A line that does not fit stays queued for the next call.
    fn drain_into(&mut self, out: &mut [u8]) -> Result<usize, Failure> {
        if self.dropped > 0 {
            self.lines.push_front(format!("dropped\t{}", self.dropped));
            self.dropped = 0;
        }
        let mut written = 0;
        while let Some(line) = self.lines.front() {
            let end = written + line.len() + 1;
            if end > out.len() {
                if written == 0 {
                    return Err(Failure::new(
                        ERR_BUFFER_TOO_SMALL,
                        format!("next event needs {} bytes", line.len() + 1),
                    ));
                }
                break;
            }
            out[written..end - 1].copy_from_slice(line.as_bytes());
            out[end - 1] = b'\n';
            written = end;
            self.lines.pop_front();
        }
        Ok(written)
    }
}

static RUNNING: Mutex<Option<Running>> = Mutex::new(None);
static EVENTS: Mutex<EventQueue> = Mutex::new(EventQueue::new());
static LAST_ERROR: Mutex<String> = Mutex::new(String::new());

/// A panic elsewhere must not stop the launcher from stopping or reading errors.
fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

fn guarded(body: impl FnOnce() -> Result<i32, Failure>) -> i32 {
    let failure = match catch_unwind(AssertUnwindSafe(body)) {
        Ok(Ok(value)) => return value,
        Ok(Err(failure)) => failure,
        Err(payload) => {
            let detail = payload
                .downcast_ref::<&str>()
                .map(|text| text.to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown".to_string());
            Failure::new(ERR_PANIC, format!("panic: {detail}"))
        }
    };
    *lock(&LAST_ERROR) = failure.message;
    failure.code
}

fn field(value: &str) -> String {
    value.replace(['\t', '\n', '\r'], " ")
}

fn format_event(session: u64, event: &SessionEvent) -> String {
    match event {
        SessionEvent::Attached {
            node,
            reason,
            elapsed_ms,
        } => {
            let reason = match reason {
                AttachReason::Initial => "initial",
                AttachReason::Failover => "failover",
                AttachReason::Manual => "manual",
            };
            format!("attached\t{session}\t{}\t{reason}\t{elapsed_ms}", field(node))
        }
        SessionEvent::PathLost { reason } => format!("path_lost\t{session}\t{}", field(reason)),
        SessionEvent::SwitchFailed { node, error } => {
            format!("switch_failed\t{session}\t{}\t{}", field(node), field(error))
        }
        SessionEvent::Ended { reason } => {
            let (kind, detail) = match reason {
                EndReason::Closed => ("closed", ""),
                EndReason::Expired => ("expired", ""),
                EndReason::Gone => ("gone", ""),
                EndReason::Violation(detail) => ("violation", *detail),
            };
            format!("ended\t{session}\t{kind}\t{}", field(detail))
        }
    }
}

/// # Safety
/// `pointer` is null or points to a NUL-terminated string that outlives the call.
unsafe fn read_str<'a>(pointer: *const c_char, name: &str) -> Result<&'a str, Failure> {
    if pointer.is_null() {
        return Err(Failure::null_pointer(name));
    }
    unsafe { CStr::from_ptr(pointer) }
        .to_str()
        .map_err(|_| Failure::new(ERR_INVALID_UTF8, format!("{name} is not UTF-8")))
}

/// # Safety
/// Same as [`read_str`].
unsafe fn read_nodes(pointer: *const c_char) -> Result<Vec<String>, Failure> {
    let nodes: Vec<String> = unsafe { read_str(pointer, "nodes") }?
        .lines()
        .map(str::trim)
        .filter(|node| !node.is_empty())
        .map(str::to_string)
        .collect();
    if nodes.is_empty() {
        return Err(Failure::new(ERR_INVALID_ARGUMENT, "nodes is empty"));
    }
    Ok(nodes)
}

/// # Safety
/// `buffer` is null or valid for writing `length` bytes for the duration of the call.
unsafe fn write_buffer<'a>(buffer: *mut c_char, length: i32) -> Result<&'a mut [u8], Failure> {
    if buffer.is_null() {
        return Err(Failure::null_pointer("buffer"));
    }
    if length <= 0 {
        return Err(Failure::new(ERR_BUFFER_TOO_SMALL, "buffer has no room"));
    }
    Ok(unsafe { std::slice::from_raw_parts_mut(buffer.cast::<u8>(), length as usize) })
}

#[unsafe(no_mangle)]
pub extern "C" fn rdi_lp_abi_version() -> u32 {
    ABI_VERSION
}

/// Starts the proxy, or updates the nodes and reports the existing port when it already runs.
/// `nodes` is separated by newlines, first entry preferred.
///
/// # Safety
/// `bind_host` and `nodes` are null or NUL-terminated strings; `out_port` is null or writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rdi_lp_start(
    bind_host: *const c_char,
    preferred_port: u16,
    nodes: *const c_char,
    out_port: *mut u16,
) -> i32 {
    guarded(|| {
        let host = unsafe { read_str(bind_host, "bind_host") }?;
        let nodes = unsafe { read_nodes(nodes) }?;
        if out_port.is_null() {
            return Err(Failure::null_pointer("out_port"));
        }
        let mut running = lock(&RUNNING);
        let port = match running.as_ref() {
            Some(current) => {
                current.proxy.set_nodes(nodes);
                current.port
            }
            None => {
                let started = Running::start(host, preferred_port, nodes)?;
                let port = started.port;
                // Events of an earlier run describe connections that no longer exist.
                *lock(&EVENTS) = EventQueue::new();
                *running = Some(started);
                port
            }
        };
        unsafe { out_port.write_unaligned(port) };
        Ok(OK)
    })
}

/// # Safety
/// `nodes` is null or a NUL-terminated string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rdi_lp_set_nodes(nodes: *const c_char) -> i32 {
    guarded(|| {
        let nodes = unsafe { read_nodes(nodes) }?;
        let running = lock(&RUNNING);
        let current = running.as_ref().ok_or_else(Failure::not_running)?;
        current.proxy.set_nodes(nodes);
        Ok(OK)
    })
}

/// Moves every running game to `address`. Sessions already on it stay as they are.
///
/// # Safety
/// `address` is null or a NUL-terminated string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rdi_lp_switch_node(address: *const c_char) -> i32 {
    guarded(|| {
        let address = unsafe { read_str(address, "address") }?.trim();
        if address.is_empty() {
            return Err(Failure::new(ERR_INVALID_ARGUMENT, "address is empty"));
        }
        let running = lock(&RUNNING);
        let current = running.as_ref().ok_or_else(Failure::not_running)?;
        current.proxy.switch_to(address.to_string());
        Ok(OK)
    })
}

/// Moves queued events into `buffer` and returns the byte count, 0 when none are queued.
/// Each event is one line of tab-separated fields; a line never gets cut in half.
///
/// # Safety
/// `buffer` is null or valid for writing `length` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rdi_lp_poll_events(buffer: *mut c_char, length: i32) -> i32 {
    guarded(|| {
        let out = unsafe { write_buffer(buffer, length) }?;
        let written = lock(&EVENTS).drain_into(out)?;
        Ok(written as i32)
    })
}

/// Copies the most recent error into `buffer` and returns the byte count, 0 when there is none.
/// The error stays available if `buffer` is too small.
///
/// # Safety
/// `buffer` is null or valid for writing `length` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rdi_lp_last_error(buffer: *mut c_char, length: i32) -> i32 {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if buffer.is_null() {
            return ERR_NULL_POINTER;
        }
        let Ok(out) = (unsafe { write_buffer(buffer, length) }) else {
            return ERR_BUFFER_TOO_SMALL;
        };
        let error = lock(&LAST_ERROR);
        if error.len() > out.len() {
            return ERR_BUFFER_TOO_SMALL;
        }
        out[..error.len()].copy_from_slice(error.as_bytes());
        error.len() as i32
    }));
    result.unwrap_or(ERR_PANIC)
}

/// Stops the proxy and closes every game connection. Safe to repeat; `rdi_lp_start` may follow.
#[unsafe(no_mangle)]
pub extern "C" fn rdi_lp_stop(timeout_ms: u32) -> i32 {
    guarded(|| {
        let mut running = lock(&RUNNING);
        if let Some(Running { runtime, proxy, .. }) = running.take() {
            runtime.block_on(proxy.stop());
            runtime.shutdown_timeout(Duration::from_millis(u64::from(timeout_ms)));
        }
        Ok(OK)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use rdi_session_resume::{Tuning, anchor::Anchor};
    use std::{
        ffi::CString,
        io::{ErrorKind, Read, Write},
        net::{SocketAddr, TcpStream},
        ptr,
        time::Instant,
    };
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /// The interface drives one global proxy, so these tests take turns.
    static SERIAL: Mutex<()> = Mutex::new(());

    fn exclusive() -> MutexGuard<'static, ()> {
        let guard = lock(&SERIAL);
        assert_eq!(rdi_lp_stop(1_000), OK);
        *lock(&EVENTS) = EventQueue::new();
        guard
    }

    fn start(preferred_port: u16, nodes: &str) -> Result<u16, i32> {
        let host = CString::new("127.0.0.1").unwrap();
        let nodes = CString::new(nodes).unwrap();
        let mut port = 0u16;
        let code = unsafe { rdi_lp_start(host.as_ptr(), preferred_port, nodes.as_ptr(), &mut port) };
        if code == OK { Ok(port) } else { Err(code) }
    }

    fn last_error() -> String {
        let mut buffer = vec![0u8; 512];
        let count = unsafe { rdi_lp_last_error(buffer.as_mut_ptr().cast(), 512) };
        assert!(count >= 0);
        String::from_utf8_lossy(&buffer[..count as usize]).into_owned()
    }

    fn poll_lines() -> Vec<String> {
        let mut buffer = vec![0u8; 64 * 1024];
        let count = unsafe { rdi_lp_poll_events(buffer.as_mut_ptr().cast(), 64 * 1024) };
        assert!(count >= 0, "poll failed: {count}");
        String::from_utf8(buffer[..count as usize].to_vec())
            .unwrap()
            .lines()
            .map(str::to_string)
            .collect()
    }

    /// Echoes every session, on its own runtime because the interface must not run inside one.
    fn echo_anchor() -> (Runtime, SocketAddr) {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_all()
            .build()
            .unwrap();
        let address = runtime.block_on(async {
            let anchor = Anchor::new(8, Tuning::anchor(), |mut app, _peer| {
                tokio::spawn(async move {
                    let mut buffer = vec![0u8; 4096];
                    while let Ok(count) = app.read(&mut buffer).await {
                        if count == 0 || app.write_all(&buffer[..count]).await.is_err() {
                            return;
                        }
                    }
                });
            });
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            tokio::spawn(async move {
                while let Ok((stream, peer)) = listener.accept().await {
                    let anchor = Arc::clone(&anchor);
                    tokio::spawn(async move {
                        let _ = anchor.handle_path(Box::new(stream), peer).await;
                    });
                }
            });
            address
        });
        (runtime, address)
    }

    fn wait_for_event(prefix: &str) -> String {
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline {
            if let Some(line) = poll_lines().into_iter().find(|line| line.starts_with(prefix)) {
                return line;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        panic!("no {prefix} event");
    }

    fn connect_echoing(port: u16) -> TcpStream {
        let mut game = TcpStream::connect(("127.0.0.1", port)).unwrap();
        game.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
        game.write_all(b"hello").unwrap();
        let mut echoed = [0u8; 5];
        game.read_exact(&mut echoed).unwrap();
        assert_eq!(&echoed, b"hello");
        game
    }

    #[test]
    fn abi_version_is_stable() {
        assert_eq!(rdi_lp_abi_version(), 1);
    }

    #[test]
    fn starting_twice_keeps_the_port_and_updates_nodes() {
        let _guard = exclusive();
        let port = start(0, "127.0.0.1:1").unwrap();
        assert_eq!(start(0, "127.0.0.1:2\n127.0.0.1:3").unwrap(), port);
        let running = lock(&RUNNING);
        assert_eq!(
            running.as_ref().unwrap().proxy.nodes(),
            ["127.0.0.1:2", "127.0.0.1:3"]
        );
        drop(running);
        assert_eq!(rdi_lp_stop(1_000), OK);
    }

    #[test]
    fn stopping_frees_the_port_for_a_new_start() {
        let _guard = exclusive();
        let port = start(0, "127.0.0.1:1").unwrap();
        assert_eq!(rdi_lp_stop(1_000), OK);
        assert_eq!(rdi_lp_stop(1_000), OK);
        assert!(TcpStream::connect(("127.0.0.1", port)).is_err());
        assert_eq!(start(port, "127.0.0.1:1").unwrap(), port);
        assert_eq!(rdi_lp_stop(1_000), OK);
    }

    #[test]
    fn null_pointers_are_refused() {
        let _guard = exclusive();
        let text = CString::new("127.0.0.1:1").unwrap();
        let mut port = 0u16;
        unsafe {
            assert_eq!(
                rdi_lp_start(ptr::null(), 0, text.as_ptr(), &mut port),
                ERR_NULL_POINTER
            );
            assert_eq!(last_error(), "bind_host is null");
            assert_eq!(
                rdi_lp_start(text.as_ptr(), 0, ptr::null(), &mut port),
                ERR_NULL_POINTER
            );
            assert_eq!(
                rdi_lp_start(text.as_ptr(), 0, text.as_ptr(), ptr::null_mut()),
                ERR_NULL_POINTER
            );
            assert_eq!(rdi_lp_set_nodes(ptr::null()), ERR_NULL_POINTER);
            assert_eq!(rdi_lp_switch_node(ptr::null()), ERR_NULL_POINTER);
            assert_eq!(rdi_lp_poll_events(ptr::null_mut(), 16), ERR_NULL_POINTER);
            assert_eq!(rdi_lp_last_error(ptr::null_mut(), 16), ERR_NULL_POINTER);
        }
        assert!(lock(&RUNNING).is_none());
    }

    #[test]
    fn invalid_text_is_refused() {
        let _guard = exclusive();
        let host = CString::new("127.0.0.1").unwrap();
        let bad = CString::new(vec![0xFFu8, 0xFE]).unwrap();
        let blank = CString::new(" \n ").unwrap();
        let mut port = 0u16;
        unsafe {
            assert_eq!(
                rdi_lp_start(host.as_ptr(), 0, bad.as_ptr(), &mut port),
                ERR_INVALID_UTF8
            );
            assert_eq!(
                rdi_lp_start(host.as_ptr(), 0, blank.as_ptr(), &mut port),
                ERR_INVALID_ARGUMENT
            );
            assert_eq!(rdi_lp_switch_node(blank.as_ptr()), ERR_INVALID_ARGUMENT);
        }
    }

    #[test]
    fn commands_need_a_running_proxy() {
        let _guard = exclusive();
        let node = CString::new("127.0.0.1:1").unwrap();
        unsafe {
            assert_eq!(rdi_lp_set_nodes(node.as_ptr()), ERR_NOT_RUNNING);
            assert_eq!(rdi_lp_switch_node(node.as_ptr()), ERR_NOT_RUNNING);
        }
        assert_eq!(last_error(), "local proxy is not running");
    }

    #[test]
    fn small_buffers_keep_events_whole() {
        let mut queue = EventQueue::new();
        queue.push("first".to_string());
        queue.push("second".to_string());

        let mut tiny = [0u8; 3];
        let failure = queue.drain_into(&mut tiny).err().unwrap();
        assert_eq!(failure.code, ERR_BUFFER_TOO_SMALL);
        assert_eq!(queue.lines.len(), 2);

        let mut one_line = [0u8; 8];
        assert_eq!(queue.drain_into(&mut one_line).ok(), Some(6));
        assert_eq!(&one_line[..6], b"first\n");

        let mut rest = [0u8; 32];
        assert_eq!(queue.drain_into(&mut rest).ok(), Some(7));
        assert_eq!(&rest[..7], b"second\n");
        assert_eq!(queue.drain_into(&mut rest).ok(), Some(0));
    }

    #[test]
    fn zero_length_buffer_is_refused_with_events_kept() {
        let _guard = exclusive();
        lock(&EVENTS).push("kept".to_string());
        let mut byte = 0 as c_char;
        assert_eq!(
            unsafe { rdi_lp_poll_events(&mut byte, 0) },
            ERR_BUFFER_TOO_SMALL
        );
        assert_eq!(poll_lines(), ["kept"]);
    }

    #[test]
    fn last_error_survives_a_small_buffer() {
        let _guard = exclusive();
        assert_eq!(unsafe { rdi_lp_set_nodes(ptr::null()) }, ERR_NULL_POINTER);
        let mut tiny = [0 as c_char; 2];
        assert_eq!(
            unsafe { rdi_lp_last_error(tiny.as_mut_ptr(), 2) },
            ERR_BUFFER_TOO_SMALL
        );
        assert_eq!(last_error(), "nodes is null");
    }

    #[test]
    fn full_queue_drops_oldest_and_says_so() {
        let mut queue = EventQueue::new();
        for index in 0..MAX_QUEUED_EVENTS + 3 {
            queue.push(format!("event{index}"));
        }
        let mut out = vec![0u8; 64 * 1024];
        let count = queue.drain_into(&mut out).ok().unwrap();
        let text = std::str::from_utf8(&out[..count]).unwrap();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines[0], "dropped\t3");
        assert_eq!(lines[1], "event3");
        assert_eq!(lines.len(), MAX_QUEUED_EVENTS + 1);
    }

    #[test]
    fn events_have_tab_separated_fields() {
        let line = format_event(
            7,
            &SessionEvent::Attached {
                node: "a:1".to_string(),
                reason: AttachReason::Failover,
                elapsed_ms: 12,
            },
        );
        assert_eq!(line, "attached\t7\ta:1\tfailover\t12");
        assert_eq!(
            format_event(7, &SessionEvent::PathLost { reason: "silent" }),
            "path_lost\t7\tsilent"
        );
        assert_eq!(
            format_event(
                7,
                &SessionEvent::SwitchFailed {
                    node: "a:1".to_string(),
                    error: "bad\tline\nbreak".to_string(),
                }
            ),
            "switch_failed\t7\ta:1\tbad line break"
        );
        assert_eq!(
            format_event(
                7,
                &SessionEvent::Ended {
                    reason: EndReason::Violation("offset")
                }
            ),
            "ended\t7\tviolation\toffset"
        );
        assert_eq!(
            format_event(
                7,
                &SessionEvent::Ended {
                    reason: EndReason::Closed
                }
            ),
            "ended\t7\tclosed\t"
        );
    }

    #[test]
    fn game_traffic_flows_and_reports_events() {
        let _guard = exclusive();
        let (_runtime, anchor) = echo_anchor();
        let node = anchor.to_string();
        let port = start(0, &node).unwrap();

        let _game = connect_echoing(port);

        let attached = wait_for_event("attached\t");
        let fields: Vec<&str> = attached.split('\t').collect();
        assert_eq!(fields[2], node);
        assert_eq!(fields[3], "initial");
        assert_eq!(rdi_lp_stop(2_000), OK);
    }

    #[test]
    fn stopping_closes_running_games() {
        let _guard = exclusive();
        let (_runtime, anchor) = echo_anchor();
        let port = start(0, &anchor.to_string()).unwrap();
        let mut game = connect_echoing(port);

        assert_eq!(rdi_lp_stop(2_000), OK);

        // A reset also counts as closed; only running into the read timeout means it stayed open.
        let mut rest = Vec::new();
        if let Err(error) = game.read_to_end(&mut rest) {
            assert!(
                !matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut),
                "game connection stayed open"
            );
        }
    }
}
