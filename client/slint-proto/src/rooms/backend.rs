//! 后台 (U4 batch c2a): room log, start, stop, restart and server commands, as Compose
//! `HostBackendScreen` and `HostConsolePane`. No Slint types, so the launcher MCP can call it.
use super::requests::segment;
use crate::api::server::ServerApi;
use anyhow::Result;
use std::{collections::VecDeque, time::Duration};

/// `HostRuntimeAction`, with Compose's wording.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RuntimeAction {
    Start,
    Restart,
    Stop,
    ForceStop,
}
impl RuntimeAction {
    pub fn path(self) -> &'static str {
        match self {
            Self::Start => "start",
            Self::Restart => "restart",
            Self::Stop => "stop",
            Self::ForceStop => "force-stop",
        }
    }
    pub fn from_key(key: &str) -> Option<Self> {
        [Self::Start, Self::Restart, Self::Stop, Self::ForceStop].into_iter().find(|a| a.path() == key)
    }
    /// The confirmation title and message; 启动 runs without one.
    pub fn confirm(self) -> Option<(&'static str, &'static str)> {
        match self {
            Self::Start => None,
            Self::Restart => Some(("确认重启", "确定重启该房间吗？")),
            Self::Stop => Some(("确认停止", "确定停止该房间吗？")),
            Self::ForceStop => Some(("确认强制停止", "确定强制停止该房间吗？")),
        }
    }
    pub fn success(self) -> &'static str {
        match self {
            Self::Start => "启动指令已发送",
            Self::Restart => "重启指令已发送",
            Self::Stop => "停止指令已发送",
            Self::ForceStop => "强制停止指令已发送",
        }
    }
    pub fn failure(self) -> &'static str {
        match self {
            Self::Start => "启动失败",
            Self::Restart => "重启失败",
            Self::Stop => "停止失败",
            Self::ForceStop => "强制停止失败",
        }
    }
}

/// The command as sent: trimmed, without the leading `/`. Blank disables 发送.
pub fn normalize_command(text: &str) -> String {
    let trimmed = text.trim();
    trimmed.strip_prefix('/').unwrap_or(trimmed).to_owned()
}

/// `POST /host/{id}/{start|restart|stop|force-stop}`.
pub async fn run(api: &ServerApi, host: &str, action: RuntimeAction) -> Result<()> {
    let _: serde_json::Value = api.post_empty(&format!("/host/{}/{}", segment(host)?, action.path())).await?;
    Ok(())
}

/// `POST /host/{id}/command`; the reply the server waited for, 「OK」 when it is empty.
pub async fn command(api: &ServerApi, host: &str, command: &str) -> Result<String> {
    let body = serde_json::json!({ "command": command });
    let reply: Option<String> = api.post(&format!("/host/{}/command", segment(host)?), &body).await?;
    Ok(reply.filter(|r| !r.trim().is_empty()).unwrap_or_else(|| "OK".into()))
}

/// What the log stream reported.
#[derive(Debug, PartialEq, Eq)]
pub enum LogEvent {
    Line(String),
    /// The server's `error` event (deleted room, not a member); the page stops reading.
    Failed(String),
}

/// `GET /host/{id}/log/stream` until the server closes it (`Ok`), it fails, or `on_event` returns
/// false. Heartbeats come every 15s, so a minute of silence is a dead connection.
pub async fn log_stream(api: &ServerApi, host: &str, mut on_event: impl FnMut(LogEvent) -> bool) -> Result<()> {
    let path = format!("/host/{}/log/stream", segment(host)?);
    api.sse(&path, Duration::from_secs(60), |event| match event.event.as_str() {
        "heartbeat" => true,
        "error" => {
            let text = if event.data.trim().is_empty() { "unknown".into() } else { event.data };
            on_event(LogEvent::Failed(text));
            false
        }
        _ if event.data.trim().is_empty() => true,
        _ => on_event(LogEvent::Line(event.data)),
    })
    .await
    .map(|_| ())
}

/// Compose `ConsoleState`: the newest 5000 lines, multi-line text split into lines.
pub struct ConsoleLines {
    lines: VecDeque<String>,
    max: usize,
    revision: u64,
}
impl Default for ConsoleLines {
    fn default() -> Self {
        Self::with_capacity(5000)
    }
}
impl ConsoleLines {
    pub fn with_capacity(max: usize) -> Self {
        Self { lines: VecDeque::new(), max: max.max(1), revision: 0 }
    }
    pub fn append(&mut self, text: &str) {
        for line in text.split('\n') {
            if self.lines.len() == self.max {
                self.lines.pop_front();
            }
            self.lines.push_back(line.trim_end_matches('\r').to_owned());
        }
        self.revision += 1;
    }
    pub fn clear(&mut self) {
        self.lines.clear();
        self.revision += 1;
    }
    pub fn lines(&self) -> impl Iterator<Item = &String> {
        self.lines.iter()
    }
    /// Changes on every append or clear, so the page redraws only when needed.
    pub fn revision(&self) -> u64 {
        self.revision
    }
}

/// The console colour of a line, as the game console uses: 2 error, 1 warning, 0 normal.
pub fn line_level(line: &str) -> i32 {
    if line.contains("ERROR") {
        2
    } else if line.contains("WARN") {
        1
    } else {
        0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::server::tests::read_request;
    use tokio::io::AsyncWriteExt;

    #[test]
    fn actions_keep_compose_wording() {
        assert_eq!(RuntimeAction::Start.confirm(), None);
        assert_eq!(RuntimeAction::Restart.confirm(), Some(("确认重启", "确定重启该房间吗？")));
        assert_eq!(RuntimeAction::ForceStop.confirm(), Some(("确认强制停止", "确定强制停止该房间吗？")));
        assert_eq!(RuntimeAction::Stop.success(), "停止指令已发送");
        assert_eq!(RuntimeAction::from_key("force-stop"), Some(RuntimeAction::ForceStop));
        assert_eq!(RuntimeAction::from_key("x"), None);
    }

    #[test]
    fn commands_drop_one_leading_slash_and_spaces() {
        assert_eq!(normalize_command("  /say hello "), "say hello");
        assert_eq!(normalize_command("//x"), "/x");
        assert_eq!(normalize_command(" / "), "");
    }

    #[test]
    fn console_keeps_the_newest_lines() {
        let mut c = ConsoleLines::with_capacity(3);
        c.append("first\n\nthird");
        assert_eq!(c.lines().cloned().collect::<Vec<_>>(), ["first", "", "third"]);
        let before = c.revision();
        c.append("4\r\n5");
        assert_eq!(c.lines().cloned().collect::<Vec<_>>(), ["third", "4", "5"]);
        assert!(c.revision() > before);
        c.clear();
        assert_eq!(c.lines().count(), 0);
        assert_eq!(ConsoleLines::default().max, 5000);
        assert_eq!(line_level("[Server thread/ERROR] x"), 2);
        assert_eq!(line_level("[WARN] x"), 1);
        assert_eq!(line_level("ok"), 0);
    }

    async fn serve(response: String) -> (ServerApi, tokio::task::JoinHandle<String>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let request = read_request(&mut socket).await;
            socket.write_all(response.as_bytes()).await.unwrap();
            request
        });
        (ServerApi::with_token(&base, "fake-token"), task)
    }
    fn json(body: &str) -> String {
        format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len())
    }
    const H: &str = "66a000000000000000000001";

    #[tokio::test]
    async fn requests_use_the_kotlin_paths_and_body() {
        for action in [RuntimeAction::Start, RuntimeAction::Restart, RuntimeAction::Stop, RuntimeAction::ForceStop] {
            let (api, task) = serve(json(r#"{"code":0}"#)).await;
            run(&api, H, action).await.unwrap();
            assert!(task.await.unwrap().starts_with(&format!("POST /host/{H}/{} ", action.path())));
        }
        let (api, task) = serve(json(r#"{"code":0,"data":"There are 0 of a max of 20 players online"}"#)).await;
        assert_eq!(command(&api, H, "list").await.unwrap(), "There are 0 of a max of 20 players online");
        let request = task.await.unwrap();
        assert!(request.starts_with(&format!("POST /host/{H}/command ")));
        assert!(request.ends_with(r#"{"command":"list"}"#), "{request}");
        let (api, _task) = serve(json(r#"{"code":0,"data":" "}"#)).await;
        assert_eq!(command(&api, H, "x").await.unwrap(), "OK");
        let (api, _task) = serve(json(r#"{"code":0}"#)).await;
        assert_eq!(command(&api, H, "x").await.unwrap(), "OK");
        let (api, _task) = serve(json(r#"{"code":-1,"msg":"房间未运行"}"#)).await;
        assert_eq!(command(&api, H, "x").await.unwrap_err().to_string(), "房间未运行");
    }

    #[tokio::test]
    async fn log_stream_skips_heartbeats_and_stops_on_error_events() {
        let body = "event: heartbeat\ndata: ping\n\ndata: [12:00:00] [Server thread/INFO]: Done\n\ndata: \n\nevent: error\ndata: 此房间已被删除\n\ndata: late\n\n";
        let (api, task) = serve(format!("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n{body}")).await;
        let mut seen = vec![];
        log_stream(&api, H, |e| {
            seen.push(e);
            true
        })
        .await
        .unwrap();
        assert_eq!(seen, [LogEvent::Line("[12:00:00] [Server thread/INFO]: Done".into()), LogEvent::Failed("此房间已被删除".into())]);
        assert!(task.await.unwrap().starts_with(&format!("GET /host/{H}/log/stream ")));
    }
}
