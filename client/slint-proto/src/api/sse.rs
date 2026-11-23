//! Server-sent events from the RDI server (room log, save packing progress), as Ktor's `sse {}`
//! client reads them. The caller decides when a stream is done; a closed or silent connection ends
//! `ServerApi::sse` so the caller can reconnect or report it.
use super::server::ServerApi;
use anyhow::{Context, Result, bail};
use std::time::Duration;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Event {
    /// Empty for an unnamed event (`message` in the SSE spec).
    pub event: String,
    pub data: String,
}

/// Splits a byte stream into events. Lines end with `\n`, `\r\n` or `\r`; a blank line ends an event.
#[derive(Default)]
pub struct Parser {
    line: Vec<u8>,
    after_cr: bool,
    event: String,
    data: Vec<String>,
}
impl Parser {
    pub fn push(&mut self, bytes: &[u8]) -> Vec<Event> {
        let mut out = Vec::new();
        for &b in bytes {
            if self.after_cr {
                self.after_cr = false;
                if b == b'\n' {
                    continue;
                }
            }
            match b {
                b'\n' => self.end_line(&mut out),
                b'\r' => {
                    self.after_cr = true;
                    self.end_line(&mut out);
                }
                _ => self.line.push(b),
            }
        }
        out
    }
    fn end_line(&mut self, out: &mut Vec<Event>) {
        let line = String::from_utf8_lossy(&std::mem::take(&mut self.line)).into_owned();
        if line.is_empty() {
            // Ktor sends heartbeats as named events with data, so an event needs a name or data.
            if !self.data.is_empty() || !self.event.is_empty() {
                out.push(Event { event: std::mem::take(&mut self.event), data: std::mem::take(&mut self.data).join("\n") });
            }
            return;
        }
        if line.starts_with(':') {
            return;
        }
        let (field, value) = match line.split_once(':') {
            Some((field, value)) => (field, value.strip_prefix(' ').unwrap_or(value)),
            None => (line.as_str(), ""),
        };
        match field {
            "event" => self.event = value.to_owned(),
            "data" => self.data.push(value.to_owned()),
            _ => {}
        }
    }
}

impl ServerApi {
    /// Reads `path` as an event stream until `on_event` returns false (`Ok(true)`), the server
    /// closes it (`Ok(false)`), or nothing arrives for `idle` (an error).
    pub async fn sse(&self, path: &str, idle: Duration, mut on_event: impl FnMut(Event) -> bool) -> Result<bool> {
        let jwt = self.jwt().context("请先登录")?.to_owned();
        let response = self
            .http
            .get(self.url(path))
            .bearer_auth(jwt)
            .header(reqwest::header::ACCEPT, "text/event-stream")
            // The client's 60s limit covers whole requests; a stream lives as long as its page.
            .timeout(Duration::from_secs(7 * 24 * 3600))
            .send()
            .await
            .map_err(|error| {
                tracing::warn!(error = %error.without_url(), path, "服务器事件流连接失败");
                anyhow::anyhow!("无法连接服务器 请检查网络连接")
            })?;
        let status = response.status();
        tracing::info!(path, status = status.as_u16(), "服务器事件流");
        if !status.is_success() {
            let body = response.bytes().await.unwrap_or_default();
            if let Ok(value) = serde_json::from_slice::<serde_json::Value>(&body)
                && let Some(msg) = value["msg"].as_str().filter(|s| !s.trim().is_empty())
            {
                bail!("{msg}");
            }
            bail!("服务器请求失败: {} {}", status.as_u16(), status.canonical_reason().unwrap_or(""));
        }
        let mut response = response;
        let mut parser = Parser::default();
        loop {
            let chunk = match tokio::time::timeout(idle, response.chunk()).await {
                Err(_) => bail!("连接超时"),
                Ok(Err(error)) => {
                    tracing::warn!(error = %error.without_url(), path, "服务器事件流中断");
                    bail!("连接中断");
                }
                Ok(Ok(None)) => return Ok(false),
                Ok(Ok(Some(chunk))) => chunk,
            };
            for event in parser.push(&chunk) {
                if !on_event(event) {
                    return Ok(true);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::server::tests::read_request;
    use tokio::io::AsyncWriteExt;

    fn ev(event: &str, data: &str) -> Event {
        Event { event: event.into(), data: data.into() }
    }

    #[test]
    fn parses_ktor_events_across_chunks_and_line_endings() {
        let mut p = Parser::default();
        assert!(p.push(b"event: heartbeat\r\ndata: ping\r\n").is_empty());
        assert_eq!(p.push(b"\r\n"), vec![ev("heartbeat", "ping")]);
        assert_eq!(p.push(b"data: [12:00] line\n\ndata:a\ndata: b\n\n"), vec![ev("", "[12:00] line"), ev("", "a\nb")]);
        assert_eq!(p.push(b": comment\nretry: 5\nevent: error\ndata: \xe6\xad\xa4"), vec![]);
        assert_eq!(p.push("房间已被删除\r\r".as_bytes()).len(), 1);
        assert_eq!(p.push(b"\n\n"), vec![], "a lone CR then LF is one line end");
        assert_eq!(p.push(b"data: x\r"), vec![]);
        assert_eq!(p.push(b"\n\r\n"), vec![ev("", "x")], "CRLF split across chunks");
    }

    async fn serve(head: &'static str, chunks: Vec<&'static str>) -> (ServerApi, tokio::task::JoinHandle<String>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let request = read_request(&mut socket).await;
            socket.write_all(head.as_bytes()).await.unwrap();
            for chunk in chunks {
                socket.write_all(chunk.as_bytes()).await.unwrap();
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            request
        });
        (ServerApi::with_token(&base, "fake-token"), task)
    }
    const HEAD: &str = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n";

    #[tokio::test]
    async fn stops_when_the_caller_is_done_or_the_server_closes() {
        let (api, task) = serve(HEAD, vec!["event: progress\ndata: 1\n\n", "event: ready\ndata: {}\n\n", "data: late\n\n"]).await;
        let mut seen = vec![];
        let stopped = api
            .sse("/host/x/world-download/b/events", Duration::from_secs(5), |e| {
                seen.push(e.event.clone());
                e.event != "ready"
            })
            .await
            .unwrap();
        assert!(stopped);
        assert_eq!(seen, ["progress", "ready"]);
        let request = task.await.unwrap();
        assert!(request.starts_with("GET /host/x/world-download/b/events "));
        assert!(request.to_lowercase().contains("authorization: bearer fake-token"));
        assert!(request.to_lowercase().contains("accept: text/event-stream"));

        let (api, _task) = serve(HEAD, vec!["data: a\n\n"]).await;
        assert!(!api.sse("/x", Duration::from_secs(5), |_| true).await.unwrap(), "server closed");
    }

    #[tokio::test]
    async fn errors_keep_the_server_message_and_silence_times_out() {
        let (api, _task) = serve(
            "HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: 25\r\nConnection: close\r\n\r\n{\"msg\":\"仅房间成员\"}",
            vec![],
        )
        .await;
        assert_eq!(api.sse("/x", Duration::from_secs(5), |_| true).await.unwrap_err().to_string(), "仅房间成员");

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let _server = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            read_request(&mut socket).await;
            socket.write_all(HEAD.as_bytes()).await.unwrap();
            tokio::time::sleep(Duration::from_secs(5)).await;
        });
        let api = ServerApi::with_token(&base, "fake-token");
        assert_eq!(api.sse("/x", Duration::from_millis(200), |_| true).await.unwrap_err().to_string(), "连接超时");
    }
}
