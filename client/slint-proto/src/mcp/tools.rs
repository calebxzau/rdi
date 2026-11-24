//! The launcher's MCP tools. Results are JSON text; failures are tool results with `isError`
//! and a Chinese message the agent can show the player. No tool returns the login token, the
//! MCP token, passwords or Microsoft tokens, and there is no generic HTTP or file tool.
use super::bridge::Bridge;
use super::protocol::{self, RpcError};
use crate::play::room::Entered;
use anyhow::{Context as _, Result, bail};
use serde_json::{Value, json};

pub const NOT_SIGNED_IN: &str = "请先在启动器登录";
pub const DEFAULT_LOG_TAIL: u64 = 100;
pub const MAX_LOG_TAIL: u64 = 500;

/// What the tools reach; every handle is `Send + Sync`.
#[derive(Clone)]
pub struct Context {
    pub sessions: crate::play::Sessions,
    pub tasks: crate::tasks::Manager,
    pub bridge: Bridge,
    pub launcher_version: String,
    /// Where installed packs live (`local_packs::versions_dir`).
    pub versions: std::path::PathBuf,
    /// The Java 25 search state (`java_runtime::state`); tests pass their own.
    pub java: fn() -> crate::java_runtime::State,
}

struct Tool {
    name: &'static str,
    description: &'static str,
    schema: fn() -> Value,
}

fn no_arguments() -> Value {
    json!({"type": "object", "properties": {}, "additionalProperties": false})
}
fn host_id_schema() -> Value {
    json!({"type": "object", "properties": {"host_id": {"type": "string", "description": "The host (房间) id from list_hosts."}},
        "required": ["host_id"], "additionalProperties": false})
}
fn play_host_schema() -> Value {
    json!({"type": "object", "properties": {
        "host_id": {"type": "string", "description": "The host (房间) id from list_hosts."},
        "allow_duplicate": {"type": "boolean", "description": "Start although the same modpack already runs. Only after the player agreed: two copies can conflict over saves and configs."}},
        "required": ["host_id"], "additionalProperties": false})
}
fn task_id_schema() -> Value {
    json!({"type": "object", "properties": {"id": {"type": "string", "description": "The task id."}},
        "required": ["id"], "additionalProperties": false})
}
fn game_log_schema() -> Value {
    json!({"type": "object", "properties": {
        "session": {"type": "string", "description": "The game session id from get_status. May be left out when one game runs."},
        "tail": {"type": "integer", "minimum": 1, "maximum": MAX_LOG_TAIL, "description": "How many of the newest lines; default 100."}},
        "additionalProperties": false})
}
fn stop_game_schema() -> Value {
    json!({"type": "object", "properties": {
        "session": {"type": "string", "description": "The game session id from get_status."},
        "force": {"type": "boolean", "description": "Kill the game at once instead of asking it to close."}},
        "required": ["session"], "additionalProperties": false})
}

const TOOLS: &[Tool] = &[
    Tool {
        name: "get_status",
        description: "Launcher status: version, the signed-in player, running games (游戏) with whether their in-game tools are available, and active launcher tasks (任务). Call this first.",
        schema: no_arguments,
    },
    Tool {
        name: "list_hosts",
        description: "The player's hosts (房间): the ones they own or joined, with modpack (整合包) name and version and whether the host can be played.",
        schema: no_arguments,
    },
    Tool {
        name: "get_host",
        description: "Details of one host (房间): its modpack (整合包) and version and whether that modpack is installed, being installed or missing on this computer.",
        schema: host_id_schema,
    },
    Tool {
        name: "play_host",
        description: "Enter a host (房间): starts the host and the game. Result `started` gives the game session id; `need_install` means the modpack must be installed first (install_host_modpack, after asking the player: it can be a large download); `installing` gives the running install task; `duplicate` means the same modpack already runs (call again with allow_duplicate only if the player agrees); `java_missing` means Java 25 is missing and the player must install it from the launcher.",
        schema: play_host_schema,
    },
    Tool {
        name: "install_host_modpack",
        description: "Start installing a host's modpack (整合包). Returns a task id; follow it with get_task. Call play_host again once it is done.",
        schema: host_id_schema,
    },
    Tool {
        name: "list_modpacks",
        description: "Modpacks (整合包) installed on this computer, read only.",
        schema: no_arguments,
    },
    Tool {
        name: "list_tasks",
        description: "Launcher tasks (任务) such as modpack installs, newest first, with status and progress.",
        schema: no_arguments,
    },
    Tool {
        name: "get_task",
        description: "One launcher task (任务) with its steps and progress.",
        schema: task_id_schema,
    },
    Tool {
        name: "cancel_task",
        description: "Cancel a running launcher task (任务).",
        schema: task_id_schema,
    },
    Tool {
        name: "get_game_log",
        description: "The newest lines of a game's console output (游戏日志), at most 500.",
        schema: game_log_schema,
    },
    Tool {
        name: "stop_game",
        description: "Stop a running game (游戏). Unsaved progress in a single-player world can be lost; ask the player first.",
        schema: stop_game_schema,
    },
];

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
        "list_hosts" => list_hosts(context).await,
        "get_host" => get_host(context, arguments).await,
        "play_host" => play_host(context, arguments).await,
        "install_host_modpack" => install_host_modpack(context, arguments).await,
        "list_modpacks" => list_modpacks(context).await,
        "list_tasks" => Ok(json!({"tasks": context.tasks.list().iter().map(task_summary).collect::<Vec<_>>()})),
        "get_task" => get_task(context, arguments),
        "cancel_task" => cancel_task(context, arguments),
        "get_game_log" => get_game_log(context, arguments),
        "stop_game" => stop_game(context, arguments),
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

fn string_arg<'a>(arguments: &'a Value, name: &str) -> Result<&'a str> {
    match arguments.get(name) {
        Some(Value::String(value)) if !value.trim().is_empty() => Ok(value.trim()),
        Some(Value::String(_)) | None | Some(Value::Null) => bail!("缺少参数{name}"),
        Some(_) => bail!("参数{name}必须是字符串"),
    }
}

fn optional_string<'a>(arguments: &'a Value, name: &str) -> Result<Option<&'a str>> {
    match arguments.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(value)) if value.trim().is_empty() => Ok(None),
        Some(Value::String(value)) => Ok(Some(value.trim())),
        Some(_) => bail!("参数{name}必须是字符串"),
    }
}

fn bool_arg(arguments: &Value, name: &str) -> Result<bool> {
    match arguments.get(name) {
        None | Some(Value::Null) => Ok(false),
        Some(Value::Bool(value)) => Ok(*value),
        Some(_) => bail!("参数{name}必须是true或false"),
    }
}

/// Host ids are ObjectIds; anything else never reaches a server path.
fn host_id(arguments: &Value) -> Result<&str> {
    let id = string_arg(arguments, "host_id")?;
    if id.len() != 24 || !id.bytes().all(|b| b.is_ascii_hexdigit()) {
        bail!("房间编号无效：{id}");
    }
    Ok(id)
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

fn game_state(view: &crate::play::SessionView) -> &'static str {
    if view.preparing {
        "starting"
    } else if view.running {
        "running"
    } else {
        "ended"
    }
}

/// The games that are starting or running.
pub fn running_games(sessions: &crate::play::Sessions) -> Vec<Value> {
    sessions
        .views()
        .into_iter()
        .filter(|s| s.preparing || s.running)
        .map(|s| json!({"session": s.id, "title": s.title, "state": game_state(&s), "game_tools": game_tools_state(&s.ai)}))
        .collect()
}

/// The game a tool works on: the given session, or the only one `wanted` accepts.
pub fn pick_session(sessions: &crate::play::Sessions, given: Option<&str>, wanted: impl Fn(&crate::play::SessionView) -> bool) -> Result<crate::play::SessionView> {
    let views = sessions.views();
    let list = |views: &[&crate::play::SessionView]| {
        views.iter().map(|v| format!("{}（{}）", v.id, v.title)).collect::<Vec<_>>().join("、")
    };
    if let Some(id) = given {
        return views.iter().find(|v| v.id == id).cloned().context(format!("找不到这个游戏：{id}"));
    }
    let candidates: Vec<&crate::play::SessionView> = views.iter().filter(|v| wanted(v)).collect();
    match candidates.as_slice() {
        [one] => Ok((*one).clone()),
        [] => bail!("没有正在运行的游戏"),
        many => bail!("有多个游戏，请用session参数指定其中一个：{}", list(many)),
    }
}

async fn signed_in(context: &Context) -> Result<super::bridge::Account> {
    context.bridge.account().await?.context(NOT_SIGNED_IN)
}

async fn get_status(context: &Context) -> Result<Value> {
    let account = context.bridge.account().await?;
    let tasks: Vec<Value> = context.tasks.list().iter().filter(|t| t["terminal"] != true).map(task_summary).collect();
    Ok(json!({
        "launcher_version": context.launcher_version,
        "signed_in": account.is_some(),
        "player": account.map(|a| a.name),
        "games": running_games(&context.sessions),
        "active_tasks": tasks,
    }))
}

async fn list_hosts(context: &Context) -> Result<Value> {
    let account = signed_in(context).await?;
    let hosts: Vec<crate::api::model::HostBrief> = account.api.get("/host/my").await.context("读取我的房间失败")?;
    let hosts: Vec<Value> = hosts
        .into_iter()
        .map(|h| {
            json!({
                "host_id": h.id,
                "name": h.name,
                "modpack": h.modpack_name,
                "modpack_version": h.pack_ver,
                "status": match h.status {
                    crate::api::model::HostStatus::Playable => "playable",
                    crate::api::model::HostStatus::Started => "started",
                    crate::api::model::HostStatus::Stopped => "stopped",
                    crate::api::model::HostStatus::Other => "unknown",
                },
                "can_play": h.playable,
                "online_players": h.online_player_ids.len(),
            })
        })
        .collect();
    Ok(json!({"hosts": hosts}))
}

async fn get_host(context: &Context, arguments: &Value) -> Result<Value> {
    let id = host_id(arguments)?;
    let account = signed_in(context).await?;
    let detail: crate::play::room::Detail = account.api.get(&format!("/host/{id}/detail")).await.context("获取房间信息失败")?;
    let installed = match crate::pack_install::state_in(&context.tasks, &context.versions, &detail.modpack.id, &detail.pack_ver) {
        crate::pack_install::State::Installed => json!({"state": "installed"}),
        crate::pack_install::State::Installing(task) => json!({"state": "installing", "task_id": task}),
        crate::pack_install::State::Missing => json!({"state": "missing"}),
    };
    Ok(json!({
        "host_id": detail.id,
        "name": detail.name,
        "modpack": {"id": detail.modpack.id, "name": detail.modpack.name, "version": detail.pack_ver},
        "modpack_on_this_computer": installed,
        "extra_mods": detail.extra_mods.len(),
        "disabled_mods": detail.disabled_mods.len(),
        "supported": crate::play::room::supported(&detail.modpack).is_some(),
    }))
}

/// The answer of `play_host` for each way entering can end.
pub fn entered_result(entered: Entered) -> Value {
    match entered {
        Entered::Started(session) => json!({"result": "started", "session": session,
            "next": "The game is starting; it can take minutes. Follow it with get_status or get_game_log; game_tools works once game_tools is available."}),
        Entered::Duplicate => json!({"result": "duplicate",
            "message": "该整合包已在运行。再次启动可能造成存档、配置、日志或Mod缓存冲突。"}),
        Entered::NeedInstall(pack) => json!({"result": "need_install", "modpack": pack.name, "modpack_version": pack.version,
            "size": pack.size.map(crate::packs::human_file_size),
            "next": "Ask the player, then call install_host_modpack and play_host again when its task is done."}),
        Entered::Installing(task) => json!({"result": "installing", "task_id": task,
            "message": "整合包正在下载，请等待下载完成后再启动"}),
        Entered::JavaMissing => json!({"result": "java_missing",
            "message": format!("{}，请在启动器里进入房间，按提示自动下载安装", crate::java_runtime::MISSING_MESSAGE)}),
    }
}

async fn play_host(context: &Context, arguments: &Value) -> Result<Value> {
    let id = host_id(arguments)?.to_owned();
    let allow_duplicate = bool_arg(arguments, "allow_duplicate")?;
    let kit = context.bridge.room().await?.context(NOT_SIGNED_IN)?;
    let request = crate::play::room::RoomStart { host_id: id, window: kit.window, allow_duplicate };
    let entered = crate::play::room::enter(&kit.api, &kit.tasks, &context.versions, (context.java)(), kit.deps, request).await?;
    Ok(entered_result(entered))
}

async fn install_host_modpack(context: &Context, arguments: &Value) -> Result<Value> {
    let id = host_id(arguments)?.to_owned();
    let kit = context.bridge.install().await?.context(NOT_SIGNED_IN)?;
    let deps = tokio::task::spawn_blocking(kit.deps).await.context("准备安装失败")?;
    let check = crate::play::room::pack_check(&kit.api, &id, &deps.tasks, &deps.versions).await?;
    let pack = match check {
        crate::play::room::PackCheck::Installed => return Ok(json!({"result": "installed", "next": "Call play_host."})),
        crate::play::room::PackCheck::Installing(task) => return Ok(json!({"result": "installing", "task_id": task})),
        crate::play::room::PackCheck::Missing(pack) => pack,
    };
    let request = crate::pack_install::InstallRequest { modpack_id: pack.modpack_id, version: pack.version.clone(), include_extras: false };
    let task = tokio::task::spawn_blocking(move || crate::pack_install::start(&deps, request)).await.context("开始安装整合包失败")??;
    Ok(json!({"result": "started", "task_id": task, "modpack": pack.name, "modpack_version": pack.version,
        "next": "Follow the task with get_task; call play_host when it is done."}))
}

async fn list_modpacks(context: &Context) -> Result<Value> {
    let account = signed_in(context).await?;
    let list = crate::local_packs::load(&account.api).await;
    let packs: Vec<Value> = list
        .packs
        .iter()
        .map(|p| json!({"id": p.id, "name": p.name, "version": p.version, "kind": if p.local { "local" } else { "rdi" }}))
        .collect();
    Ok(json!({"modpacks": packs, "error": (!list.error.is_empty()).then_some(list.error)}))
}

fn task_snapshot(context: &Context, arguments: &Value) -> Result<(String, Value)> {
    let id = string_arg(arguments, "id")?;
    let task = context.tasks.snapshot(id).context(format!("找不到这个任务：{id}"))?;
    Ok((id.to_owned(), task))
}

fn get_task(context: &Context, arguments: &Value) -> Result<Value> {
    let (_, task) = task_snapshot(context, arguments)?;
    let mut summary = task_summary(&task);
    let steps: Vec<Value> = task["rows"]
        .as_array()
        .map(|rows| {
            rows.iter()
                .filter(|r| r["depth"] == 1)
                .map(|r| {
                    let status = match r["status"].as_u64() {
                        Some(1) => "running",
                        Some(2) => "done",
                        Some(3) => "failed",
                        Some(4) => "cancelled",
                        _ => "waiting",
                    };
                    json!({"title": r["title"], "status": status, "detail": r["detail"]})
                })
                .collect()
        })
        .unwrap_or_default();
    summary["steps"] = json!(steps);
    Ok(summary)
}

fn cancel_task(context: &Context, arguments: &Value) -> Result<Value> {
    let (id, task) = task_snapshot(context, arguments)?;
    if task["terminal"] == true {
        bail!("任务已结束，无法取消");
    }
    context.tasks.cancel(&id);
    Ok(json!({"result": "cancelling", "task_id": id}))
}

fn get_game_log(context: &Context, arguments: &Value) -> Result<Value> {
    let given = optional_string(arguments, "session")?;
    let tail = match arguments.get("tail") {
        None | Some(Value::Null) => DEFAULT_LOG_TAIL,
        Some(value) => value.as_u64().filter(|t| *t >= 1).context("参数tail必须是正整数")?.min(MAX_LOG_TAIL),
    };
    let alive = |v: &crate::play::SessionView| v.preparing || v.running;
    let view = match pick_session(&context.sessions, given, alive) {
        Err(_) if given.is_none() && !context.sessions.views().iter().any(alive) => {
            context.sessions.views().pop().context("还没有启动过游戏")?
        }
        other => other?,
    };
    let lines = context.sessions.lines(&view.id);
    let skip = lines.len().saturating_sub(tail as usize);
    Ok(json!({"session": view.id, "title": view.title, "state": game_state(&view), "total_lines": lines.len(),
        "lines": &lines[skip..]}))
}

fn stop_game(context: &Context, arguments: &Value) -> Result<Value> {
    let id = string_arg(arguments, "session")?;
    let force = bool_arg(arguments, "force")?;
    let view = pick_session(&context.sessions, Some(id), |_| true)?;
    if !(view.preparing || view.running) {
        bail!("这个游戏已经结束");
    }
    context.sessions.stop(&view.id, force)?;
    Ok(json!({"result": "stopped", "session": view.id, "force": force}))
}
