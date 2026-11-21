//! A single service generation. Its owner may create a new generation after failure;
//! requests from the failed generation are never replayed (they may mutate rooms).
use crate::protocol;
use anyhow::{Context, Result, bail, ensure};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    fs::File,
    io::{Read, Write},
    process::{Child, Command, Stdio},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc,
    },
    thread,
    time::Duration,
};

type Reply = std::result::Result<Value, String>;
type Pending = Arc<Mutex<HashMap<u64, mpsc::SyncSender<Reply>>>>;

/// The framed request/response stream. Every method takes `&self`; callers on different
/// threads wait for their own replies, so a slow request never blocks a fast one.
struct Connection {
    input: Mutex<Option<Box<dyn Write + Send>>>,
    pending: Pending,
    connected: Arc<AtomicBool>,
    sequence: AtomicU64,
    events: Arc<Mutex<HashMap<String, Value>>>,
}

impl Connection {
    fn start(mut output: impl Read + Send + 'static, input: impl Write + Send + 'static) -> Self {
        let pending: Pending = Arc::new(Mutex::new(HashMap::new()));
        let reader_pending = pending.clone();
        let connected = Arc::new(AtomicBool::new(true));
        let reader_connected = connected.clone();
        // Bound event memory even when the UI stops consuming updates.
        let events = Arc::new(Mutex::new(HashMap::new()));
        let reader_events = events.clone();
        thread::spawn(move || {
            let failure = loop {
                match protocol::read_frame(&mut output) {
                    Ok(Some(message)) => {
                        if let Some(id) = message.get("id").and_then(Value::as_u64) {
                            let sender = match reader_pending.lock() {
                                Ok(mut entries) => entries.remove(&id),
                                Err(_) => break "启动服务状态锁损坏".to_string(),
                            };
                            if let Some(sender) = sender {
                                // Receiver may have timed out; no request remains blocked.
                                let _ = sender.send(Ok(message));
                            }
                        } else if let Some(event) = message.get("event").and_then(Value::as_str) {
                            if !matches!(
                                event,
                                "game.state"
                                    | "packPlay.state"
                                    | "console.state"
                            ) {
                                break "启动服务事件名称无效".into();
                            }
                            match reader_events.lock() {
                                Ok(mut snapshots) => {
                                    snapshots.insert(event.to_owned(), message);
                                }
                                Err(_) => break "启动服务事件锁损坏".into(),
                            }
                        } else {
                            break "启动服务消息缺少请求编号或事件名称".into();
                        }
                    }
                    Ok(None) => break "启动服务已停止，请重新连接".into(),
                    Err(error) => break format!("启动服务连接中断：{error:#}"),
                }
            };
            reader_connected.store(false, Ordering::Release);
            tracing::warn!("{failure}");
            if let Ok(mut entries) = reader_pending.lock() {
                for (_, sender) in entries.drain() {
                    let _ = sender.send(Err(failure.clone()));
                }
            }
        });
        Self {
            input: Mutex::new(Some(Box::new(input))),
            pending,
            connected,
            sequence: AtomicU64::new(1),
            events,
        }
    }

    fn handshake(&self) -> Result<()> {
        let result = self.request(
            "handshake",
            json!({"protocolVersion": protocol::VERSION}),
            Duration::from_secs(30),
        )?;
        ensure!(
            result.get("protocolVersion").and_then(Value::as_u64) == Some(protocol::VERSION.into()),
            "启动服务版本不匹配，请更新启动器"
        );
        Ok(())
    }

    fn request(&self, method: &str, params: Value, timeout: Duration) -> Result<Value> {
        ensure!(params.is_object(), "请求参数必须是对象");
        let id = self.sequence.fetch_add(1, Ordering::Relaxed);
        let (sender, receiver) = mpsc::sync_channel(1);
        {
            let mut pending = self
                .pending
                .lock()
                .map_err(|_| anyhow::anyhow!("启动服务状态锁损坏"))?;
            ensure!(
                self.connected.load(Ordering::Acquire),
                "启动服务已停止，请重新连接"
            );
            pending.insert(id, sender);
        }
        let response = (|| -> Result<Value> {
            {
                // Held only while writing one frame, never while waiting for the reply.
                let mut input = self
                    .input
                    .lock()
                    .map_err(|_| anyhow::anyhow!("启动服务输入锁损坏"))?;
                protocol::write_frame(
                    input.as_mut().context("启动服务已关闭")?,
                    &json!({"id":id,"method":method,"params":params}),
                )?;
            }
            let response = receiver
                .recv_timeout(timeout)
                .context("启动服务未及时响应，请重试")?
                .map_err(anyhow::Error::msg)?;
            if let Some(error) = response.get("error") {
                bail!(
                    "{}",
                    error
                        .get("message")
                        .and_then(Value::as_str)
                        .unwrap_or("操作失败，请重试")
                );
            }
            response
                .get("result")
                .cloned()
                .context("启动服务未返回结果")
        })();
        self.pending
            .lock()
            .map_err(|_| anyhow::anyhow!("启动服务状态锁损坏"))?
            .remove(&id);
        response
    }

    fn take_events(&self) -> Result<Vec<Value>> {
        let mut events = self
            .events
            .lock()
            .map_err(|_| anyhow::anyhow!("启动服务事件锁损坏"))?;
        Ok(events.drain().map(|(_, value)| value).collect())
    }

    /// Closing stdin requests orderly service shutdown.
    fn close_input(&self) {
        match self.input.lock() {
            Ok(mut input) => {
                input.take();
            }
            Err(poisoned) => {
                poisoned.into_inner().take();
            }
        }
    }
}

/// Share it with `Arc`; it needs no outer lock.
pub struct Service {
    child: Mutex<Option<Child>>,
    connection: Connection,
}

impl Service {
    #[cfg(test)]
    pub(crate) fn fake(
        output: impl Read + Send + 'static,
        input: impl Write + Send + 'static,
    ) -> Result<Self> {
        let service = Self {
            child: Mutex::new(None),
            connection: Connection::start(output, input),
        };
        service.connection.handshake()?;
        Ok(service)
    }

    /// The caller chooses Java and the classpath, or supplies the development command.
    /// stderr is a file, never a pipe that can fill and block the service.
    pub fn spawn(command: &mut Command, log: File) -> Result<Self> {
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::from(log))
            .spawn()
            .context("无法启动启动服务")?;
        let input = child.stdin.take().context("启动服务输入不可用")?;
        let output = child.stdout.take().context("启动服务输出不可用")?;
        // Built before the handshake so a failed handshake still stops the child in Drop.
        let service = Self {
            child: Mutex::new(Some(child)),
            connection: Connection::start(output, input),
        };
        service.connection.handshake()?;
        Ok(service)
    }

    /// Ends this generation even when pending callers still hold an Arc.
    pub fn terminate(&self) {
        self.connection.close_input();
        if let Ok(mut child) = self.child.lock()
            && let Some(child) = child.as_mut()
        {
            for _ in 0..40 {
                match child.try_wait() {
                    Ok(Some(_)) => return,
                    Ok(None) => thread::sleep(Duration::from_millis(50)),
                    Err(error) => {
                        tracing::warn!(%error, "检查启动准备结束失败");
                        break;
                    }
                }
            }
            if let Err(error) = child.kill() {
                tracing::warn!(%error, "结束启动准备失败");
            }
            if let Err(error) = child.wait() {
                tracing::warn!(%error, "等待启动准备结束失败");
            }
        }
    }

    pub fn request(&self, method: &str, params: Value, timeout: Duration) -> Result<Value> {
        self.connection.request(method, params, timeout)
    }

    /// Snapshot events are conflated by topic, including the final stopped state.
    pub fn take_events(&self) -> Result<Vec<Value>> {
        self.connection.take_events()
    }

    pub fn has_exited(&self) -> Result<bool> {
        let mut child = self
            .child
            .lock()
            .map_err(|_| anyhow::anyhow!("启动服务进程锁损坏"))?;
        match child.as_mut() {
            Some(child) => child
                .try_wait()
                .map(|status| status.is_some())
                .context("检查启动服务失败"),
            None => Ok(!self.connection.connected.load(Ordering::Acquire)),
        }
    }
}

impl Drop for Service {
    fn drop(&mut self) {
        self.connection.close_input();
        let child = match self.child.get_mut() {
            Ok(child) => child,
            Err(poisoned) => poisoned.into_inner(),
        };
        let Some(child) = child.as_mut() else {
            return;
        };
        for _ in 0..40 {
            match child.try_wait() {
                Ok(Some(_)) => return,
                Ok(None) => thread::sleep(Duration::from_millis(50)),
                Err(error) => {
                    tracing::warn!(%error, "检查启动服务退出失败");
                    break;
                }
            }
        }
        if let Err(error) = child.kill() {
            tracing::warn!(%error, "结束启动服务失败");
        }
        if let Err(error) = child.wait() {
            tracing::warn!(%error, "等待启动服务退出失败");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A fake service on the far side of two OS pipes, like the real stdio streams.
    fn connect() -> (Arc<Connection>, std::io::PipeReader, std::io::PipeWriter) {
        let (client_output, service_writer) = std::io::pipe().unwrap();
        let (service_reader, client_input) = std::io::pipe().unwrap();
        (
            Arc::new(Connection::start(client_output, client_input)),
            service_reader,
            service_writer,
        )
    }

    #[test]
    fn slow_request_does_not_block_a_later_request() {
        let (connection, mut service_reader, mut service_writer) = connect();
        let (fast_done, fast_done_rx) = mpsc::channel::<()>();
        let fake = thread::spawn(move || {
            let slow = protocol::read_frame(&mut service_reader).unwrap().unwrap();
            let fast = protocol::read_frame(&mut service_reader).unwrap().unwrap();
            assert_eq!(slow["method"], "slow");
            protocol::write_frame(
                &mut service_writer,
                &json!({"id": fast["id"], "result": {"name": "fast"}}),
            )
            .unwrap();
            // The slow reply waits until the fast caller has its answer.
            fast_done_rx.recv_timeout(Duration::from_secs(5)).unwrap();
            protocol::write_frame(
                &mut service_writer,
                &json!({"id": slow["id"], "result": {"name": "slow"}}),
            )
            .unwrap();
        });
        let slow = {
            let connection = connection.clone();
            thread::spawn(move || connection.request("slow", json!({}), Duration::from_secs(5)))
        };
        // Make sure the slow request is in flight before the fast one starts.
        while connection.pending.lock().unwrap().is_empty() {
            thread::sleep(Duration::from_millis(5));
        }
        let fast = connection
            .request("fast", json!({}), Duration::from_secs(5))
            .unwrap();
        assert_eq!(fast["name"], "fast");
        fast_done.send(()).unwrap();
        assert_eq!(slow.join().unwrap().unwrap()["name"], "slow");
        fake.join().unwrap();
    }

    #[test]
    fn stream_end_fails_requests_still_waiting() {
        let (connection, mut service_reader, service_writer) = connect();
        let fake = thread::spawn(move || {
            protocol::read_frame(&mut service_reader).unwrap().unwrap();
            drop(service_writer);
        });
        let error = connection
            .request("host.start", json!({}), Duration::from_secs(5))
            .unwrap_err();
        assert!(error.to_string().contains("启动服务已停止"), "{error:#}");
        fake.join().unwrap();
    }

    #[test]
    fn u4_snapshots_are_allowed_and_conflated_by_topic() {
        let (connection, mut reader, mut writer) = connect();
        let fake = thread::spawn(move || {
            for topic in [
                "packPlay.state",
                "console.state",
            ] {
                for revision in 0..2 {
                    protocol::write_frame(
                        &mut writer,
                        &json!({"event":topic,"params":{"revision":revision}}),
                    )
                    .unwrap();
                }
            }
            let barrier = protocol::read_frame(&mut reader).unwrap().unwrap();
            protocol::write_frame(&mut writer, &json!({"id":barrier["id"],"result":{}})).unwrap();
        });
        connection
            .request("barrier", json!({}), Duration::from_secs(5))
            .unwrap();
        let events = connection.events.lock().unwrap();
        assert_eq!(events.len(), 2);
        assert!(
            events
                .values()
                .all(|event| event["params"]["revision"] == 1)
        );
        fake.join().unwrap();
    }
}
