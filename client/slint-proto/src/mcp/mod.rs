//! The launcher MCP (`LAUNCHER-MCP-PLAN.md`): a local MCP server a player's agent uses to operate
//! the launcher and, through it, the games it started. No Slint types here; `crate::mcp_ui` holds
//! the title bar icon and the copy step.
pub mod bridge;
pub mod game;
pub mod protocol;
pub mod server;
pub mod tools;
#[cfg(test)]
pub(crate) mod tests;

use anyhow::{Context as _, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::path::{Path, PathBuf};

/// The saved address and token, so the agent is configured once.
pub const SETTINGS_FILE: &str = "launcher_mcp.json";

#[derive(Clone, Debug, PartialEq)]
pub enum State {
    Stopped,
    Running { port: u16 },
    Failed(String),
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
struct Saved {
    port: u16,
    token: String,
}

/// What a successful start tells the player.
#[derive(Debug, PartialEq)]
pub struct Started {
    pub port: u16,
    /// The saved port was busy, so the prompt differs from the one copied before.
    pub port_changed: bool,
}

/// Starts and stops the server; lives on the UI thread.
pub struct Controller {
    settings: PathBuf,
    runtime: tokio::runtime::Handle,
    context: tools::Context,
    state: State,
    token: String,
    running: Option<server::Running>,
}

impl Controller {
    pub fn new(settings: PathBuf, runtime: tokio::runtime::Handle, context: tools::Context) -> Self {
        Self { settings, runtime, context, state: State::Stopped, token: String::new(), running: None }
    }

    pub fn state(&self) -> &State {
        &self.state
    }

    /// The text to copy while running.
    pub fn prompt(&self) -> Option<String> {
        match self.state {
            State::Running { port } => Some(prompt(port, &self.token)),
            _ => None,
        }
    }

    /// Starts the server on the saved port, or on a new one when that port is busy.
    pub fn start(&mut self) -> Result<Started> {
        if let State::Running { port } = self.state {
            return Ok(Started { port, port_changed: false });
        }
        match self.try_start() {
            Ok(started) => {
                self.state = State::Running { port: started.port };
                tracing::info!(port = started.port, changed = started.port_changed, "AI助手已开启");
                Ok(started)
            }
            Err(error) => {
                tracing::error!(error = %format!("{error:#}"), "AI助手开启失败");
                self.state = State::Failed(format!("{error:#}"));
                Err(error)
            }
        }
    }

    fn try_start(&mut self) -> Result<Started> {
        let saved = load(&self.settings);
        let token = match &saved {
            Some(saved) if valid_token(&saved.token) => saved.token.clone(),
            _ => new_token()?,
        };
        let listener = saved
            .as_ref()
            .filter(|s| s.port != 0)
            .and_then(|s| match std::net::TcpListener::bind(("127.0.0.1", s.port)) {
                Ok(listener) => Some(listener),
                Err(error) => {
                    tracing::warn!(port = s.port, %error, "AI助手原来的端口不可用，改用新端口");
                    None
                }
            });
        let port_changed = saved.is_some() && listener.is_none();
        let listener = match listener {
            Some(listener) => listener,
            None => std::net::TcpListener::bind(("127.0.0.1", 0)).context("AI助手无法开始监听")?,
        };
        let port = listener.local_addr().context("AI助手无法开始监听")?.port();
        let fresh = Saved { port, token: token.clone() };
        if saved.as_ref() != Some(&fresh) {
            save(&self.settings, &fresh)?;
        }
        listener.set_nonblocking(true).context("AI助手无法开始监听")?;
        let _guard = self.runtime.enter();
        let listener = tokio::net::TcpListener::from_std(listener).context("AI助手无法开始监听")?;
        let context = self.context.clone();
        let handler: server::Handler = std::sync::Arc::new(move |body| {
            let context = context.clone();
            Box::pin(async move { handle(&context, &body).await })
        });
        self.running = Some(server::spawn(listener, port, token.clone(), handler));
        self.token = token;
        Ok(Started { port, port_changed })
    }

    pub fn stop(&mut self) {
        if let Some(running) = self.running.take() {
            running.stop();
        }
        self.token.clear();
        self.state = State::Stopped;
    }
}

/// One JSON-RPC body; `None` for a notification.
pub async fn handle(context: &tools::Context, body: &[u8]) -> Option<Value> {
    let request = match protocol::parse(body) {
        Ok(request) => request,
        Err(error) => return Some(protocol::error(None, error.code, &error.message)),
    };
    let notification = request.id.is_none();
    let result = match request.method.as_str() {
        "initialize" => Ok(protocol::initialize(&request.params, &context.launcher_version)),
        "ping" => Ok(serde_json::json!({})),
        "tools/list" => Ok(tools::list()),
        "tools/call" => match request.params.get("name").and_then(Value::as_str) {
            Some(name) => {
                let arguments = request.params.get("arguments").cloned().unwrap_or_else(|| serde_json::json!({}));
                let arguments = if arguments.is_null() { serde_json::json!({}) } else { arguments };
                tools::call(context, name, &arguments).await
            }
            None => Err(protocol::RpcError::invalid_params("missing parameter name")),
        },
        method if method.starts_with("notifications/") => return None,
        method => Err(protocol::RpcError::new(protocol::METHOD_NOT_FOUND, format!("method not found: {method}"))),
    };
    if notification {
        return None;
    }
    Some(match result {
        Ok(result) => protocol::success(request.id, result),
        Err(error) => protocol::error(request.id, error.code, &error.message),
    })
}

/// The text the player pastes into the agent.
pub fn prompt(port: u16, token: &str) -> String {
    format!(
        "请把RDI启动器添加为MCP服务器，然后帮我操作它。\n\
地址：http://127.0.0.1:{port}/mcp\n\
传输方式：Streamable HTTP\n\
请求头：Authorization: Bearer {token}\n\
如果需要重启客户端才能生效，请告诉我。\n\
连接后先调用get_status。进入游戏后用game_tools查看游戏内可用的操作。\n\
不要把token发到别处。"
    )
}

pub fn settings_path() -> PathBuf {
    crate::paths::data_dir().join(SETTINGS_FILE)
}

fn load(path: &Path) -> Option<Saved> {
    match std::fs::read_to_string(path) {
        Ok(text) => match serde_json::from_str(&text) {
            Ok(saved) => Some(saved),
            Err(error) => {
                tracing::warn!(%error, "AI助手设置无法读取，重新生成");
                None
            }
        },
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
        Err(error) => {
            tracing::warn!(%error, "AI助手设置无法读取，重新生成");
            None
        }
    }
}

fn save(path: &Path, saved: &Saved) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).context("保存AI助手设置失败")?;
    }
    let temporary = path.with_extension("json.tmp");
    std::fs::write(&temporary, serde_json::to_vec_pretty(saved)?).context("保存AI助手设置失败")?;
    std::fs::rename(&temporary, path).context("保存AI助手设置失败")?;
    Ok(())
}

/// 32 bytes from the OS random source, hex encoded.
fn new_token() -> Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).map_err(|error| anyhow::anyhow!("生成AI助手密钥失败: {error}"))?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

fn valid_token(token: &str) -> bool {
    token.len() == 64 && token.bytes().all(|b| b.is_ascii_hexdigit())
}
