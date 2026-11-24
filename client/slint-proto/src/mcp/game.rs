//! Requests forwarded to a running game's own MCP server (RMCP, `mc/rmcp`). The game answers
//! JSON-RPC on `http://127.0.0.1:<port>/mcp` without a session header, so `tools/list` and
//! `tools/call` need no `initialize` first. The address comes only from the game's own output
//! (`crate::play::GameAi`).
use anyhow::{Context as _, Result, bail};
use serde_json::{Value, json};
use std::time::Duration;

const TIMEOUT: Duration = Duration::from_secs(30);

/// Sends JSON-RPC requests to games; cheap to clone.
#[derive(Clone)]
pub struct Forwarder {
    /// A client that could not be built makes every game tool fail with the reason.
    http: std::result::Result<reqwest::Client, String>,
}

impl Forwarder {
    /// No system proxy: the game listens on loopback only.
    pub fn new() -> Self {
        let http = reqwest::Client::builder().no_proxy().timeout(TIMEOUT).build().map_err(|error| {
            tracing::error!(%error, "创建游戏连接失败");
            format!("创建游戏连接失败: {error}")
        });
        Self { http }
    }

    /// The game's `result`, or its JSON-RPC error as the error message.
    pub async fn request(&self, url: &str, method: &str, params: Value) -> Result<Value> {
        let body = json!({"jsonrpc": "2.0", "id": 1, "method": method, "params": params});
        let http = self.http.as_ref().map_err(|message| anyhow::anyhow!("{message}"))?;
        let response = http.post(url).json(&body).send().await.map_err(|error| {
            if error.is_timeout() {
                anyhow::anyhow!("游戏30秒内没有回应，可能正在加载或卡住了")
            } else {
                anyhow::anyhow!("无法连接游戏，游戏可能已经关闭: {error}")
            }
        })?;
        let status = response.status();
        let answer: Value = response.json().await.with_context(|| format!("游戏的回应无法读取（HTTP {status}）"))?;
        if let Some(error) = answer.get("error") {
            bail!("游戏返回错误：{}", error["message"].as_str().unwrap_or("未知错误"));
        }
        if !status.is_success() {
            bail!("游戏返回错误：HTTP {status}");
        }
        answer.get("result").cloned().context("游戏的回应缺少结果")
    }
}

impl Default for Forwarder {
    fn default() -> Self {
        Self::new()
    }
}
