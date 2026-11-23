//! J5b: the room connection inside the launcher (`LocalMcProxyService` with the native backend
//! of `GameProxyController`). Room games connect to `127.0.0.1:55667`, or any free port when that
//! one is taken; each game connection becomes a resumable session over the node list, so the
//! node can change underneath. Started by the first room game, stopped when the last one ends.
//! Event lines name nodes, never addresses (`describeLocalProxyEvent`).
use anyhow::{Context, Result};
use rdi_local_proxy::{LocalProxy, LocalProxyConfig};
use rdi_session_resume::EndReason;
use rdi_session_resume::client::{AttachReason, SessionEvent};
use std::sync::{Arc, Mutex, OnceLock};

type Sink = Arc<dyn Fn(String) + Send + Sync>;

struct Running {
    proxy: LocalProxy,
    sink: Arc<Mutex<Sink>>,
}

fn running() -> &'static tokio::sync::Mutex<Option<Running>> {
    static RUNNING: OnceLock<tokio::sync::Mutex<Option<Running>>> = OnceLock::new();
    RUNNING.get_or_init(Default::default)
}

/// The node a running game was last seen on, to correct the route after a failed switch.
fn last_attached() -> &'static Mutex<Option<String>> {
    static LAST: OnceLock<Mutex<Option<String>>> = OnceLock::new();
    LAST.get_or_init(Default::default)
}

/// Starts the connection, or gives a running one the new node list. Returns the address the
/// game connects to. Must run inside the launcher's tokio runtime.
pub async fn start(nodes: Vec<String>, sink: impl Fn(String) + Send + Sync + 'static) -> Result<String> {
    start_on(None, nodes, sink).await
}

/// `start` that first tries `port` instead of 55667: a recovered room game reconnects to the port
/// it was started with.
pub async fn start_on(port: Option<u16>, nodes: Vec<String>, sink: impl Fn(String) + Send + Sync + 'static) -> Result<String> {
    let sink: Sink = Arc::new(sink);
    let mut running = running().lock().await;
    if let Some(current) = running.as_ref() {
        current.proxy.set_nodes(nodes);
        *current.sink.lock().unwrap() = sink;
        return Ok(current.proxy.address().to_string());
    }
    let shared_sink = Arc::new(Mutex::new(sink));
    let events = shared_sink.clone();
    let mut config = LocalProxyConfig { nodes, ..Default::default() };
    if let Some(port) = port {
        config.preferred_port = port;
    }
    let proxy = LocalProxy::start(
        config,
        Arc::new(move |session, event| {
            let line = on_event(session, &event);
            let sink = events.lock().unwrap().clone();
            sink(line);
        }),
    )
    .await
    .context("本地房间连接启动失败")?;
    let address = proxy.address().to_string();
    tracing::info!(%address, "房间连接已启动");
    *running = Some(Running { proxy, sink: shared_sink });
    Ok(address)
}

/// Stops accepting and ends every connection.
pub async fn stop() {
    if let Some(current) = running().lock().await.take() {
        current.proxy.stop().await;
        tracing::info!("房间连接已停止");
    }
}

#[cfg(test)]
pub async fn is_running() -> bool {
    running().lock().await.is_some()
}

fn on_event(session: u64, event: &SessionEvent) -> String {
    match event {
        SessionEvent::Attached { node, .. } => {
            *last_attached().lock().unwrap() = Some(node.clone());
            super::mark_active(node);
        }
        SessionEvent::SwitchFailed { .. } => {
            if let Some(node) = last_attached().lock().unwrap().clone() {
                super::mark_active(&node);
            }
        }
        _ => {}
    }
    // Local diagnostics may name addresses; the console line never does.
    tracing::info!(session, ?event, "房间连接事件");
    let state = super::state();
    format!("[LocalMcProxy] {}", describe(event, &|addr| state.name_of(addr)))
}

/// `describeLocalProxyEvent`.
pub fn describe(event: &SessionEvent, node_name: &dyn Fn(&str) -> String) -> String {
    match event {
        SessionEvent::Attached { node, reason, elapsed_ms } => match reason {
            AttachReason::Initial => format!("已连接节点{}", node_name(node)),
            AttachReason::Failover => format!("已自动切换到节点{}，用时{elapsed_ms}ms", node_name(node)),
            AttachReason::Manual => format!("已切换到节点{}，用时{elapsed_ms}ms", node_name(node)),
        },
        SessionEvent::PathLost { reason } => format!("节点连接中断({reason})，正在切换"),
        SessionEvent::SwitchFailed { node, error } => {
            format!("切换到节点{}失败: {}", node_name(node), error.replace(node.as_str(), &node_name(node)))
        }
        SessionEvent::Ended { reason } => match reason {
            EndReason::Closed => "房间连接已结束".into(),
            EndReason::Expired => "所有节点都无法连接，已断开房间连接".into(),
            EndReason::Gone => "房间连接已失效，请重新进入".into(),
            EndReason::Violation(detail) => format!("房间连接异常，已断开: {detail}"),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_lines_name_nodes_not_addresses() {
        let name = |addr: &str| if addr == "sh.example:1" { "上海".to_string() } else { "默认节点".to_string() };
        let attached = |reason| SessionEvent::Attached { node: "sh.example:1".into(), reason, elapsed_ms: 42 };
        assert_eq!(describe(&attached(AttachReason::Initial), &name), "已连接节点上海");
        assert_eq!(describe(&attached(AttachReason::Failover), &name), "已自动切换到节点上海，用时42ms");
        assert_eq!(describe(&attached(AttachReason::Manual), &name), "已切换到节点上海，用时42ms");
        assert_eq!(describe(&SessionEvent::PathLost { reason: "timeout" }, &name), "节点连接中断(timeout)，正在切换");
        let failed = SessionEvent::SwitchFailed { node: "sh.example:1".into(), error: "connect sh.example:1 refused".into() };
        assert_eq!(describe(&failed, &name), "切换到节点上海失败: connect 上海 refused");
        assert_eq!(describe(&SessionEvent::Ended { reason: EndReason::Expired }, &name), "所有节点都无法连接，已断开房间连接");
        assert_eq!(describe(&SessionEvent::Ended { reason: EndReason::Violation("offset") }, &name), "房间连接异常，已断开: offset");
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn the_connection_starts_once_and_keeps_its_address() {
        let first = start(vec!["127.0.0.1:9".into()], |_| {}).await.unwrap();
        assert!(first.starts_with("127.0.0.1:"), "{first}");
        let second = start(vec!["127.0.0.1:10".into()], |_| {}).await.unwrap();
        assert_eq!(first, second);
        assert!(is_running().await);
        stop().await;
        assert!(!is_running().await);
    }
}
