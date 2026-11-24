//! The launcher's MCP tools. Results are JSON text; failures are tool results with `isError`
//! and a Chinese message the agent can show the player. No tool returns the login token, the
//! MCP token, passwords or Microsoft tokens, and there is no generic HTTP or file tool.
use super::bridge::Bridge;
use super::protocol::{self, RpcError};
use serde_json::{Value, json};

#[allow(dead_code)] // M2 tools
pub const NOT_SIGNED_IN: &str = "请先在启动器登录";

/// What the tools reach; every handle is `Send + Sync`.
#[derive(Clone)]
pub struct Context {
    pub sessions: crate::play::Sessions,
    pub tasks: crate::tasks::Manager,
    pub bridge: Bridge,
    pub launcher_version: String,
}

struct Tool {
    name: &'static str,
    description: &'static str,
    schema: fn() -> Value,
}

fn no_arguments() -> Value {
    json!({"type": "object", "properties": {}, "additionalProperties": false})
}

const TOOLS: &[Tool] = &[Tool {
    name: "get_status",
    description: "Launcher status: version, the signed-in player, running games (游戏) with whether their in-game tools are available, and active launcher tasks (任务). Call this first.",
    schema: no_arguments,
}];

/// The `tools/list` result.
pub fn list() -> Value {
    let tools: Vec<Value> = TOOLS
        .iter()
        .map(|t| json!({"name": t.name, "description": t.description, "inputSchema": (t.schema)()}))
        .collect();
    json!({"tools": tools})
}

/// The `tools/call` result. An unknown tool is a protocol error, as in the game's RMCP.
pub async fn call(context: &Context, name: &str, arguments: &Value) -> Result<Value, RpcError> {
    if !TOOLS.iter().any(|t| t.name == name) {
        return Err(RpcError::invalid_params(format!("unknown tool: {name}")));
    }
    if !arguments.is_object() {
        return Err(RpcError::invalid_params("arguments must be object"));
    }
    tracing::info!(tool = name, "AI助手调用工具");
    let result = match name {
        "get_status" => get_status(context).await,
        _ => unreachable!("listed tool without a handler"),
    };
    Ok(match result {
        Ok(value) => protocol::json_result(&value),
        Err(error) => {
            tracing::info!(tool = name, error = %format!("{error:#}"), "AI助手工具返回错误");
            protocol::text_result(format!("{error:#}"), true)
        }
    })
}

/// A task snapshot reduced to what an agent needs.
pub fn task_summary(task: &Value) -> Value {
    let status = match task["status"].as_u64() {
        Some(0) => "queued",
        Some(1) => "running",
        Some(2) => "done",
        Some(3) => "failed",
        Some(4) => "cancelled",
        _ => "unknown",
    };
    let fraction = task["fraction"].as_f64().filter(|f| *f >= 0.0);
    json!({
        "id": task["runId"],
        "title": task["title"],
        "status": status,
        "message": task["message"],
        "progress_percent": fraction.map(|f| (f.clamp(0.0, 1.0) * 100.0).round() as u32),
        "error": task["error"].as_str().filter(|e| !e.is_empty()),
    })
}

fn game_tools_state(ai: &crate::play::GameAi) -> &'static str {
    match ai {
        crate::play::GameAi::Ready(_) => "available",
        crate::play::GameAi::Waiting => "starting",
        crate::play::GameAi::Recovered => "unavailable_recovered",
        crate::play::GameAi::Unsupported => "unsupported",
    }
}

/// The games that are starting or running.
pub fn running_games(sessions: &crate::play::Sessions) -> Vec<Value> {
    sessions
        .views()
        .into_iter()
        .filter(|s| s.preparing || s.running)
        .map(|s| {
            json!({
                "session": s.id,
                "title": s.title,
                "state": if s.preparing { "starting" } else { "running" },
                "game_tools": game_tools_state(&s.ai),
            })
        })
        .collect()
}

async fn get_status(context: &Context) -> anyhow::Result<Value> {
    let account = context.bridge.account().await?;
    let tasks: Vec<Value> = context
        .tasks
        .list()
        .iter()
        .filter(|t| t["terminal"] != true)
        .map(task_summary)
        .collect();
    Ok(json!({
        "launcher_version": context.launcher_version,
        "signed_in": account.is_some(),
        "player": account.map(|a| a.name),
        "games": running_games(&context.sessions),
        "active_tasks": tasks,
    }))
}
