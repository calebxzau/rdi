//! JSON-RPC 2.0 and the MCP methods the launcher answers. A port of the game's
//! `standard/JsonRpc.kt` and `standard/McpProtocol.kt`, reduced to tools.
use serde_json::{Value, json};

/// The newest protocol version; also what the game's RMCP answers.
pub const PROTOCOL_VERSION: &str = "2025-11-25";
/// Versions whose tool calls look the same as ours. A client asking for one of them gets it back,
/// as the MCP lifecycle requires; any other request gets `PROTOCOL_VERSION`.
const COMPATIBLE_VERSIONS: [&str; 4] = [PROTOCOL_VERSION, "2025-06-18", "2025-03-26", "2024-11-05"];

pub const PARSE_ERROR: i64 = -32700;
pub const INVALID_REQUEST: i64 = -32600;
pub const METHOD_NOT_FOUND: i64 = -32601;
pub const INVALID_PARAMS: i64 = -32602;

#[derive(Debug, PartialEq)]
pub struct Request {
    /// `None` for a notification, which gets no answer.
    pub id: Option<Value>,
    pub method: String,
    pub params: Value,
}

#[derive(Debug, PartialEq)]
pub struct RpcError {
    pub code: i64,
    pub message: String,
}

impl RpcError {
    pub fn new(code: i64, message: impl Into<String>) -> Self {
        Self { code, message: message.into() }
    }
    pub fn invalid_params(message: impl Into<String>) -> Self {
        Self::new(INVALID_PARAMS, message)
    }
}

/// `JsonRpc.parseRequest`. Batches are not part of the 2025 protocol and are refused.
pub fn parse(body: &[u8]) -> Result<Request, RpcError> {
    let root: Value = serde_json::from_slice(body).map_err(|_| RpcError::new(PARSE_ERROR, "parse error"))?;
    let Value::Object(mut root) = root else {
        return Err(RpcError::new(INVALID_REQUEST, "request must be an object"));
    };
    if root.get("jsonrpc").and_then(Value::as_str) != Some("2.0") {
        return Err(RpcError::new(INVALID_REQUEST, "jsonrpc must be 2.0"));
    }
    let method = root
        .get("method")
        .and_then(Value::as_str)
        .ok_or_else(|| RpcError::new(INVALID_REQUEST, "missing method"))?
        .to_owned();
    let params = match root.remove("params") {
        None | Some(Value::Null) => json!({}),
        Some(params @ Value::Object(_)) => params,
        Some(_) => return Err(RpcError::invalid_params("params must be object")),
    };
    Ok(Request { id: root.remove("id"), method, params })
}

pub fn success(id: Option<Value>, result: Value) -> Value {
    json!({"jsonrpc": "2.0", "id": id.unwrap_or(Value::Null), "result": result})
}

pub fn error(id: Option<Value>, code: i64, message: &str) -> Value {
    json!({"jsonrpc": "2.0", "id": id.unwrap_or(Value::Null), "error": {"code": code, "message": message}})
}

/// The answer to `initialize`.
pub fn initialize(params: &Value, version: &str) -> Value {
    let requested = params.get("protocolVersion").and_then(Value::as_str).unwrap_or_default();
    let protocol = COMPATIBLE_VERSIONS.iter().find(|v| **v == requested).copied().unwrap_or(PROTOCOL_VERSION);
    json!({
        "protocolVersion": protocol,
        "capabilities": {"tools": {"listChanged": false}},
        "serverInfo": {
            "name": "rdi-launcher",
            "title": "RDI启动器",
            "description": "Operate the RDI Minecraft launcher and the games it started.",
            "version": version,
        },
        "instructions": INSTRUCTIONS,
    })
}

const INSTRUCTIONS: &str = "Tools of the RDI Minecraft launcher, acting as the signed-in player. \
Call get_status first. A host (房间) is a multiplayer world of the player and friends; a modpack (整合包) \
is the mod set a host plays. play_host enters a host and starts the game. Once a game runs, game_tools \
lists what can be done inside it and game_call runs one of those tools. Talk to the player in Chinese \
and use the words 房间, 整合包 and 游戏.";

/// A tool result as MCP expects it: text content, optionally marked as an error.
pub fn text_result(text: impl Into<String>, is_error: bool) -> Value {
    json!({"content": [{"type": "text", "text": text.into()}], "isError": is_error})
}

/// A tool result whose text is pretty JSON.
pub fn json_result(value: &Value) -> Value {
    text_result(serde_json::to_string_pretty(value).unwrap_or_else(|_| value.to_string()), false)
}
