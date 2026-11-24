//! Launcher MCP tests: HTTP checks, protocol, settings and tools against fakes. Nothing here
//! contacts external hosts.
use super::*;
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// Removed when dropped, also after a failed assertion.
fn temp() -> crate::test_dir::TestDir {
    crate::test_dir::TestDir::new("rdi-mcp-test")
}

type Make<T> = Arc<Mutex<Option<Box<dyn Fn() -> T + Send>>>>;

/// Answers bridge requests the way `App::tick` would, from a plain thread.
pub(super) struct FakeUi {
    pub account: Arc<Mutex<Option<(crate::api::Client, String)>>>,
    pub room: Make<bridge::RoomKit>,
    pub install: Make<bridge::InstallKit>,
}

pub(super) fn context_with_ui() -> (tools::Context, FakeUi) {
    let (bridge, requests) = bridge::Bridge::new();
    let ui = FakeUi { account: Default::default(), room: Default::default(), install: Default::default() };
    let (account, room, install) = (ui.account.clone(), ui.room.clone(), ui.install.clone());
    std::thread::spawn(move || {
        for request in requests {
            match request {
                bridge::Request::Account(reply) => {
                    let value = account.lock().unwrap().clone().map(|(api, name)| bridge::Account { api, name });
                    let _ = reply.send(value);
                }
                bridge::Request::Room(reply) => {
                    let _ = reply.send(room.lock().unwrap().as_ref().map(|make| make()));
                }
                bridge::Request::Install(reply) => {
                    let _ = reply.send(install.lock().unwrap().as_ref().map(|make| make()));
                }
            }
        }
    });
    let context = tools::Context {
        sessions: crate::play::Sessions::default(),
        tasks: crate::tasks::Manager::default(),
        bridge,
        launcher_version: "9.9.9".into(),
        // Never created here; a test that installs something points it at its own folder.
        versions: std::env::temp_dir().join(format!("rdi-mcp-missing-{}", uuid::Uuid::now_v7())).join("versions"),
        java: || crate::java_runtime::State::Searching,
        game: game::Forwarder::new(),
    };
    (context, ui)
}

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap()
}

struct Live {
    runtime: tokio::runtime::Runtime,
    controller: Controller,
    port: u16,
    token: String,
    http: reqwest::Client,
    /// Last, so the folder goes after the server that saved into it.
    _dir: crate::test_dir::TestDir,
}

impl Live {
    fn start() -> Self {
        let runtime = runtime();
        let (context, _) = context_with_ui();
        let dir = temp();
        let mut controller = Controller::new(dir.join(SETTINGS_FILE), runtime.handle().clone(), context);
        let started = controller.start().unwrap();
        let token = controller.token.clone();
        let http = reqwest::Client::builder().no_proxy().build().unwrap();
        Live { runtime, controller, port: started.port, token, http, _dir: dir }
    }

    fn url(&self) -> String {
        format!("http://127.0.0.1:{}/mcp", self.port)
    }

    /// Sends a request with the right headers, except that `changes` replace or, when empty,
    /// remove them (`RequestBuilder::header` would add a second value instead).
    fn send(&self, body: Vec<u8>, changes: &[(&str, &str)]) -> (u16, String) {
        let mut headers = vec![("authorization".to_owned(), format!("Bearer {}", self.token)), ("content-type".to_owned(), "application/json".to_owned())];
        for (name, value) in changes {
            headers.retain(|(n, _)| n != &name.to_ascii_lowercase());
            if !value.is_empty() {
                headers.push((name.to_ascii_lowercase(), (*value).to_owned()));
            }
        }
        self.runtime.block_on(async {
            let mut request = self.http.post(self.url()).body(body).timeout(Duration::from_secs(5));
            for (name, value) in headers {
                request = request.header(name, value);
            }
            let response = request.send().await.unwrap();
            let status = response.status().as_u16();
            (status, response.text().await.unwrap())
        })
    }

    /// A POST with the right headers plus `tail` (the last headers and the blank line), then
    /// `body`; returns the status line's code.
    fn raw(&self, tail: &str, body: &[u8]) -> u16 {
        use std::io::{Read, Write};
        let mut stream = std::net::TcpStream::connect(("127.0.0.1", self.port)).unwrap();
        stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let head = format!(
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nAuthorization: Bearer {}\r\nContent-Type: application/json\r\n{tail}",
            self.port, self.token
        );
        stream.write_all(head.as_bytes()).unwrap();
        // The server may close while the body is still being sent; the answer is already there.
        let _ = stream.write_all(body);
        let mut answer = Vec::new();
        let mut buffer = [0u8; 1024];
        while !answer.windows(2).any(|w| w == b"\r\n") {
            match stream.read(&mut buffer) {
                Ok(0) | Err(_) => break,
                Ok(n) => answer.extend_from_slice(&buffer[..n]),
            }
        }
        let line = String::from_utf8_lossy(&answer).lines().next().unwrap_or_default().to_owned();
        line.split(' ').nth(1).and_then(|code| code.parse().ok()).unwrap_or(0)
    }

    fn rpc(&self, body: Value) -> Value {
        let (status, text) = self.send(serde_json::to_vec(&body).unwrap(), &[]);
        assert_eq!(status, 200, "{text}");
        serde_json::from_str(&text).unwrap()
    }
}

#[test]
fn token_origin_host_type_and_size_are_checked() {
    let live = Live::start();
    let ping = serde_json::to_vec(&json!({"jsonrpc":"2.0","id":1,"method":"ping"})).unwrap();
    assert_eq!(live.send(ping.clone(), &[]).0, 200);

    assert_eq!(live.send(ping.clone(), &[("Authorization", "")]).0, 401);
    assert_eq!(live.send(ping.clone(), &[("Authorization", "Bearer 00")]).0, 401);
    let wrong = "0".repeat(64);
    assert_eq!(live.send(ping.clone(), &[("Authorization", &format!("Bearer {wrong}"))]).0, 401);
    assert_eq!(live.send(ping.clone(), &[("Authorization", &format!("Basic {}", live.token))]).0, 401);

    assert_eq!(live.send(ping.clone(), &[("Origin", "https://evil.example")]).0, 403);
    assert_eq!(live.send(ping.clone(), &[("Origin", "http://127.0.0.1:1")]).0, 403);
    assert_eq!(live.send(ping.clone(), &[("Origin", &format!("http://localhost:{}", live.port))]).0, 200);
    assert_eq!(live.send(ping.clone(), &[("Origin", "null")]).0, 403);

    assert_eq!(live.send(ping.clone(), &[("Host", "evil.example")]).0, 403);
    assert_eq!(live.send(ping.clone(), &[("Host", &format!("evil.example:{}", live.port))]).0, 403);
    assert_eq!(live.send(ping.clone(), &[("Host", &format!("localhost:{}", live.port))]).0, 200);

    assert_eq!(live.send(ping.clone(), &[("Content-Type", "text/plain")]).0, 415);
    assert_eq!(live.send(ping.clone(), &[("Content-Type", "application/json; charset=utf-8")]).0, 200);

    // The server answers before reading an oversized body, so these go over a raw socket.
    let declared = format!("Content-Length: {}\r\n\r\n", server::MAX_BODY + 10);
    assert_eq!(live.raw(&declared, b""), 413, "declared length over the limit");
    let mut chunked = Vec::new();
    for _ in 0..5 {
        chunked.extend_from_slice(format!("{:x}\r\n", 1024 * 1024).as_bytes());
        chunked.extend(std::iter::repeat_n(b' ', 1024 * 1024));
        chunked.extend_from_slice(b"\r\n");
    }
    chunked.extend_from_slice(b"0\r\n\r\n");
    assert_eq!(live.raw("Transfer-Encoding: chunked\r\n\r\n", &chunked), 413, "streamed body over the limit");

    let bearer = format!("Bearer {}", live.token);
    let (get, other, missing) = live.runtime.block_on(async {
        let get = live.http.get(live.url()).header("Authorization", &bearer).send().await.unwrap();
        let other = live.http.post(format!("http://127.0.0.1:{}/other", live.port)).header("Authorization", &bearer).send().await.unwrap();
        let missing = live.http.post(live.url()).header("Content-Type", "application/json").body("{}").send().await.unwrap();
        (get, other, missing)
    });
    assert_eq!(get.status().as_u16(), 405);
    assert_eq!(get.headers()["allow"], "POST");
    assert_eq!(other.status().as_u16(), 404);
    assert_eq!(missing.status().as_u16(), 401);
    assert_eq!(missing.headers()["www-authenticate"], "Bearer");
}

#[test]
fn initialize_list_unknown_tool_and_notifications() {
    let live = Live::start();
    let init = live.rpc(json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}));
    assert_eq!(init["id"], 1);
    assert_eq!(init["result"]["protocolVersion"], "2025-06-18");
    assert_eq!(init["result"]["capabilities"]["tools"]["listChanged"], false);
    assert_eq!(init["result"]["serverInfo"]["version"], "9.9.9");
    let newest = live.rpc(json!({"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"2099-01-01"}}));
    assert_eq!(newest["id"], "a");
    assert_eq!(newest["result"]["protocolVersion"], protocol::PROTOCOL_VERSION);

    let (status, body) = live.send(br#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#.to_vec(), &[]);
    assert_eq!((status, body.as_str()), (202, ""));

    let list = live.rpc(json!({"jsonrpc":"2.0","id":2,"method":"tools/list"}));
    let names: Vec<&str> = list["result"]["tools"].as_array().unwrap().iter().map(|t| t["name"].as_str().unwrap()).collect();
    assert!(names.contains(&"get_status"), "{names:?}");
    for tool in list["result"]["tools"].as_array().unwrap() {
        assert_eq!(tool["inputSchema"]["type"], "object", "{tool}");
        assert!(!tool["description"].as_str().unwrap().is_empty());
    }

    let unknown = live.rpc(json!({"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"delete_host","arguments":{}}}));
    assert_eq!(unknown["error"]["code"], protocol::INVALID_PARAMS);
    let method = live.rpc(json!({"jsonrpc":"2.0","id":4,"method":"resources/list"}));
    assert_eq!(method["error"]["code"], protocol::METHOD_NOT_FOUND);
    let (status, text) = live.send(b"not json".to_vec(), &[]);
    assert_eq!(status, 200);
    assert_eq!(serde_json::from_str::<Value>(&text).unwrap()["error"]["code"], protocol::PARSE_ERROR);
    let batch = live.rpc(json!([{"jsonrpc":"2.0","id":5,"method":"ping"}]));
    assert_eq!(batch["error"]["code"], protocol::INVALID_REQUEST);
}

#[test]
fn stopping_closes_kept_alive_connections() {
    let mut live = Live::start();
    let ping = serde_json::to_vec(&json!({"jsonrpc":"2.0","id":1,"method":"ping"})).unwrap();
    assert_eq!(live.send(ping.clone(), &[]).0, 200);
    live.controller.stop();
    assert_eq!(live.controller.state(), &State::Stopped);
    assert_eq!(live.controller.prompt(), None);
    std::thread::sleep(Duration::from_millis(100));
    let after = live.runtime.block_on(async {
        live.http
            .post(live.url())
            .header("Authorization", format!("Bearer {}", live.token))
            .header("Content-Type", "application/json")
            .body(ping)
            .timeout(Duration::from_secs(3))
            .send()
            .await
    });
    assert!(after.is_err(), "a stopped assistant must not answer");
}

#[test]
fn prompt_text_matches_the_plan() {
    let token = "ab".repeat(32);
    assert_eq!(
        prompt(51234, &token),
        format!(
            "请把RDI启动器添加为MCP服务器，然后帮我操作它。\n地址：http://127.0.0.1:51234/mcp\n传输方式：Streamable HTTP\n\
请求头：Authorization: Bearer {token}\n如果需要重启客户端才能生效，请告诉我。\n\
连接后先调用get_status。进入游戏后用game_tools查看游戏内可用的操作。\n不要把token发到别处。"
        )
    );
}

#[test]
fn port_and_token_are_saved_and_a_busy_port_is_replaced() {
    let runtime = runtime();
    let dir = temp();
    let file = dir.join(SETTINGS_FILE);
    let (context, _) = context_with_ui();
    let mut first = Controller::new(file.clone(), runtime.handle().clone(), context.clone());
    let started = first.start().unwrap();
    assert!(!started.port_changed, "nothing saved yet, so nothing changed");
    let saved: Saved = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
    assert_eq!(saved.port, started.port);
    assert!(valid_token(&saved.token));
    assert!(first.prompt().unwrap().contains(&saved.token));
    first.stop();
    std::thread::sleep(Duration::from_millis(100));

    let mut again = Controller::new(file.clone(), runtime.handle().clone(), context.clone());
    let restarted = again.start().unwrap();
    assert_eq!(restarted, Started { port: started.port, port_changed: false }, "same address after a restart");
    assert!(again.prompt().unwrap().contains(&saved.token));
    again.stop();
    std::thread::sleep(Duration::from_millis(100));

    let busy = std::net::TcpListener::bind(("127.0.0.1", started.port)).unwrap();
    let mut moved = Controller::new(file.clone(), runtime.handle().clone(), context);
    let fallback = moved.start().unwrap();
    assert!(fallback.port_changed);
    assert_ne!(fallback.port, started.port);
    let resaved: Saved = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
    assert_eq!(resaved, Saved { port: fallback.port, token: saved.token.clone() }, "token kept, new port saved");
    drop(busy);
    moved.stop();
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn broken_settings_get_a_new_token() {
    let runtime = runtime();
    let dir = temp();
    let file = dir.join(SETTINGS_FILE);
    std::fs::write(&file, r#"{"port":0,"token":"short"}"#).unwrap();
    let (context, _) = context_with_ui();
    let mut controller = Controller::new(file.clone(), runtime.handle().clone(), context);
    controller.start().unwrap();
    let saved: Saved = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
    assert!(valid_token(&saved.token));
    controller.stop();
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn secrets_compare_whole_values() {
    assert!(server::same_secret(b"abc", b"abc"));
    assert!(!server::same_secret(b"abd", b"abc"));
    assert!(!server::same_secret(b"ab", b"abc"));
    assert!(!server::same_secret(b"abcd", b"abc"));
    assert!(!server::same_secret(b"", b"abc"));
}

pub(super) fn call(context: &tools::Context, runtime: &tokio::runtime::Runtime, name: &str, arguments: Value) -> (bool, Value) {
    let result = runtime.block_on(tools::call(context, name, &arguments)).unwrap();
    let text = result["content"][0]["text"].as_str().unwrap().to_owned();
    let error = result["isError"] == true;
    (error, if error { Value::String(text) } else { serde_json::from_str(&text).unwrap() })
}

#[test]
fn status_reports_player_games_and_active_tasks() {
    let runtime = runtime();
    let (context, ui) = context_with_ui();
    let (error, status) = call(&context, &runtime, "get_status", json!({}));
    assert!(!error);
    assert_eq!(status["signed_in"], false);
    assert_eq!(status["player"], Value::Null);
    assert_eq!(status["launcher_version"], "9.9.9");

    let server = crate::api::server::ServerApi::with_token("http://127.0.0.1:9", "jwt");
    *ui.account.lock().unwrap() = Some((crate::api::Client::for_test(server, "66a000000000000000000001", "acc"), "玩家甲".into()));
    let local = context.sessions.admit("单机 - A", "local_a", false).unwrap();
    let room = context.sessions.admit_room("游玩 房间", "pack_1", false).unwrap();
    context.sessions.expect_game_tools(&room);
    context.sessions.game_line(&room, "[12:00:01] [Render thread/INFO] [RDI/]: AI MCP URL: http://127.0.0.1:61234/mcp");
    let ended = context.sessions.admit("单机 - B", "local_b", false).unwrap();
    context.sessions.mark_exited(&ended, Some("已停止".into()));
    let task = context.tasks.submit(runtime.handle(), "完整下载整合包 甲".into(), "k".into(), |c| {
        while !c.cancel.cancelled() {
            std::thread::sleep(Duration::from_millis(1));
        }
        c.cancel.check()
    });
    let (_, status) = call(&context, &runtime, "get_status", json!({}));
    assert_eq!(status["signed_in"], true);
    assert_eq!(status["player"], "玩家甲");
    let games = status["games"].as_array().unwrap();
    assert_eq!(games.len(), 2, "ended games are left out: {games:?}");
    assert_eq!(games[0]["session"], local.as_str());
    assert_eq!(games[0]["game_tools"], "unsupported");
    assert_eq!(games[1]["game_tools"], "available");
    assert_eq!(status["active_tasks"][0]["id"], task.as_str());
    assert_eq!(status["active_tasks"][0]["title"], "完整下载整合包 甲");
    let text = serde_json::to_string(&status).unwrap();
    assert!(!text.contains("jwt"), "the login token never reaches the agent");
    context.tasks.shutdown(Duration::from_secs(2));
}

#[test]
fn game_address_is_exact_first_and_cleared_on_exit() {
    use crate::play::{GameAi, game_mcp_url};
    assert_eq!(game_mcp_url("AI MCP URL: http://127.0.0.1:5000/mcp\r").as_deref(), Some("http://127.0.0.1:5000/mcp"));
    assert_eq!(game_mcp_url("[x] [main/INFO]: AI MCP URL: http://127.0.0.1:5000/mcp").as_deref(), Some("http://127.0.0.1:5000/mcp"));
    for bad in [
        "AI MCP URL: http://192.168.1.2:5000/mcp",
        "AI MCP URL: http://localhost:5000/mcp",
        "AI MCP URL: http://127.0.0.1:5000/other",
        "AI MCP URL: http://127.0.0.1:5000/mcp?x=1",
        "AI MCP URL: http://127.0.0.1:99999/mcp",
        "AI MCP URL: http://127.0.0.1:0/mcp",
        "AI MCP URL: http://127.0.0.1:/mcp",
        "AI MCP URL: https://127.0.0.1:5000/mcp",
        "AI MCP URL: http://127.0.0.1:+50/mcp",
        "no marker http://127.0.0.1:5000/mcp",
    ] {
        assert_eq!(game_mcp_url(bad), None, "{bad}");
    }
    let sessions = crate::play::Sessions::default();
    let id = sessions.admit_room("游玩 房间", "pack_1", false).unwrap();
    let ai = |s: &crate::play::Sessions| s.views().into_iter().find(|v| v.id == id).unwrap().ai;
    sessions.game_line(&id, "AI MCP URL: http://127.0.0.1:5000/mcp");
    assert_eq!(ai(&sessions), GameAi::Unsupported, "only games started with the RDI core take an address");
    sessions.expect_game_tools(&id);
    assert_eq!(ai(&sessions), GameAi::Waiting);
    sessions.game_line(&id, "AI MCP URL: http://10.0.0.1:5000/mcp");
    assert_eq!(ai(&sessions), GameAi::Waiting, "a wrong address is ignored");
    sessions.game_line(&id, "AI MCP URL: http://127.0.0.1:5001/mcp");
    sessions.game_line(&id, "[CHAT] <x> AI MCP URL: http://127.0.0.1:6000/mcp");
    assert_eq!(ai(&sessions), GameAi::Ready("http://127.0.0.1:5001/mcp".into()), "a later line cannot redirect");
    assert_eq!(sessions.lines(&id).len(), 4, "every line still reaches the console");
    sessions.mark_exited(&id, None);
    assert_eq!(ai(&sessions), GameAi::Unsupported);
}

// ---- M2: host, task and game tools ----

/// A tiny RDI server: fixed JSON envelopes per path, everything else 404.
struct FakeServer {
    url: String,
    hits: Arc<Mutex<Vec<String>>>,
}

impl FakeServer {
    fn new(routes: Vec<(String, Value)>) -> Self {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let hits = Arc::new(Mutex::new(Vec::new()));
        let seen = hits.clone();
        let routes: std::collections::HashMap<String, Value> = routes.into_iter().collect();
        std::thread::spawn(move || {
            for stream in listener.incoming() {
                let Ok(mut stream) = stream else { continue };
                let (routes, seen) = (routes.clone(), seen.clone());
                std::thread::spawn(move || {
                    stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                    let mut input = Vec::new();
                    let mut buffer = [0u8; 4096];
                    while !input.windows(4).any(|w| w == b"\r\n\r\n") {
                        match stream.read(&mut buffer) {
                            Ok(0) | Err(_) => return,
                            Ok(n) => input.extend_from_slice(&buffer[..n]),
                        }
                    }
                    let text = String::from_utf8_lossy(&input).to_string();
                    let path = text.lines().next().unwrap_or("").split(' ').nth(1).unwrap_or("").split('?').next().unwrap_or("").to_owned();
                    seen.lock().unwrap().push(path.clone());
                    let (status, body) = match routes.get(&path) {
                        Some(data) => (200, json!({"code": 0, "msg": "", "data": data}).to_string()),
                        None => (404, String::new()),
                    };
                    let head = format!("HTTP/1.1 {status} X\r\nContent-Length: {}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n", body.len());
                    let _ = stream.write_all(head.as_bytes());
                    let _ = stream.write_all(body.as_bytes());
                });
            }
        });
        Self { url, hits }
    }

    fn client(&self) -> crate::api::Client {
        let mut server = crate::api::server::ServerApi::with_token(&self.url, "jwt");
        server.http = reqwest::Client::builder().no_proxy().build().unwrap();
        crate::api::Client::for_test(server, "66a000000000000000000001", "acc")
    }
}

const HOST: &str = "66b000000000000000000001";
const PACK: &str = "66c000000000000000000002";

fn host_routes() -> Vec<(String, Value)> {
    vec![
        (
            "/host/my".into(),
            json!([{"_id": HOST, "name": "周末房间", "modpackName": "甲包", "packVer": "1.0", "status": "PLAYABLE",
                "onlinePlayerIds": ["a", "b"]}]),
        ),
        (
            format!("/host/{HOST}/detail"),
            json!({"_id": HOST, "name": "周末房间", "modpack": {"id": PACK, "name": "甲包", "mcVer": "V211", "modloader": "neoforge"},
                "packVer": "1.0", "port": 20001, "extraMods": [], "disabledMods": []}),
        ),
        (format!("/modpack/{PACK}/version/1.0"), json!({"name": "1.0", "totalSize": 1048576})),
    ]
}

fn signed_in(ui: &FakeUi, server: &FakeServer) {
    *ui.account.lock().unwrap() = Some((server.client(), "玩家甲".into()));
    let client = server.client();
    *ui.room.lock().unwrap() = Some(Box::new(move || bridge::RoomKit {
        api: client.clone(),
        tasks: crate::tasks::Manager::default(),
        deps: Box::new(|| -> crate::play::room::RoomDeps { unreachable!("an installed pack is not part of these tests") }),
        window: (1280, 720),
    }));
}

#[test]
fn signed_out_tools_ask_for_sign_in() {
    let runtime = runtime();
    let (context, _) = context_with_ui();
    for (tool, arguments) in [
        ("list_hosts", json!({})),
        ("get_host", json!({"host_id": HOST})),
        ("play_host", json!({"host_id": HOST})),
        ("install_host_modpack", json!({"host_id": HOST})),
        ("list_modpacks", json!({})),
    ] {
        assert_eq!(call(&context, &runtime, tool, arguments), (true, json!(tools::NOT_SIGNED_IN)), "{tool}");
    }
    let (error, message) = call(&context, &runtime, "get_host", json!({"host_id": "../../player/x"}));
    assert!(error && message.as_str().unwrap().starts_with("房间编号无效"), "{message}");
    let (error, message) = call(&context, &runtime, "play_host", json!({}));
    assert_eq!((error, message), (true, json!("缺少参数host_id")));
    let (error, message) = call(&context, &runtime, "play_host", json!({"host_id": HOST, "allow_duplicate": "yes"}));
    assert!(error && message.as_str().unwrap().contains("allow_duplicate"), "{message}");
}

#[test]
fn hosts_and_host_detail_follow_the_server() {
    let runtime = runtime();
    let (mut context, ui) = context_with_ui();
    let dir = temp();
    context.versions = dir.join("versions");
    let server = FakeServer::new(host_routes());
    signed_in(&ui, &server);
    let (error, hosts) = call(&context, &runtime, "list_hosts", json!({}));
    assert!(!error, "{hosts}");
    assert_eq!(hosts["hosts"][0], json!({"host_id": HOST, "name": "周末房间", "modpack": "甲包", "modpack_version": "1.0",
        "status": "playable", "can_play": true, "online_players": 2}));

    let (_, host) = call(&context, &runtime, "get_host", json!({"host_id": HOST}));
    assert_eq!(host["modpack"], json!({"id": PACK, "name": "甲包", "version": "1.0"}));
    assert_eq!(host["modpack_on_this_computer"]["state"], "missing");
    assert_eq!(host["supported"], true);
    std::fs::create_dir_all(context.versions.join(format!("{PACK}_1.0")).join("mods")).unwrap();
    let (_, host) = call(&context, &runtime, "get_host", json!({"host_id": HOST}));
    assert_eq!(host["modpack_on_this_computer"]["state"], "installed");

    let (error, message) = call(&context, &runtime, "get_host", json!({"host_id": "66b0000000000000000000ff"}));
    assert!(error && message.as_str().unwrap().starts_with("获取房间信息失败"), "{message}");
}

#[test]
fn play_host_reports_every_outcome() {
    let runtime = runtime();
    let (mut context, ui) = context_with_ui();
    let server = FakeServer::new(host_routes());
    signed_in(&ui, &server);

    context.java = || crate::java_runtime::State::Missing;
    let (_, java) = call(&context, &runtime, "play_host", json!({"host_id": HOST}));
    assert_eq!(java["result"], "java_missing");
    assert!(server.hits.lock().unwrap().is_empty(), "nothing is asked of the server without Java");

    context.java = || crate::java_runtime::State::Searching;
    let (error, need) = call(&context, &runtime, "play_host", json!({"host_id": HOST}));
    assert!(!error, "{need}");
    assert_eq!((need["result"].as_str(), need["modpack"].as_str(), need["modpack_version"].as_str()), (Some("need_install"), Some("甲包"), Some("1.0")));
    assert_eq!(need["size"], crate::packs::human_file_size(1048576));

    // The room kit carries its own task manager, as the UI's pack screens share theirs.
    let tasks = crate::tasks::Manager::default();
    let running = tasks.submit(runtime.handle(), "完整下载整合包 甲包".into(), crate::pack_install::task_key(PACK, "1.0"), |c| {
        while !c.cancel.cancelled() {
            std::thread::sleep(Duration::from_millis(1));
        }
        c.cancel.check()
    });
    let client = server.client();
    let kit_tasks = tasks.clone();
    *ui.room.lock().unwrap() = Some(Box::new(move || bridge::RoomKit {
        api: client.clone(),
        tasks: kit_tasks.clone(),
        deps: Box::new(|| -> crate::play::room::RoomDeps { unreachable!() }),
        window: (1280, 720),
    }));
    let (_, installing) = call(&context, &runtime, "play_host", json!({"host_id": HOST}));
    assert_eq!(installing, json!({"result": "installing", "task_id": running, "message": "整合包正在下载，请等待下载完成后再启动"}));
    tasks.shutdown(Duration::from_secs(2));

    use crate::play::room::{Entered, PackNeeded};
    assert_eq!(tools::entered_result(Entered::Started("s1".into()))["session"], "s1");
    assert_eq!(tools::entered_result(Entered::Duplicate)["result"], "duplicate");
    let pack = PackNeeded { modpack_id: PACK.into(), version: "2".into(), name: "乙".into(), size: None };
    assert_eq!(tools::entered_result(Entered::NeedInstall(pack))["size"], Value::Null);
}

#[test]
fn game_log_and_stop_pick_the_right_game() {
    let runtime = runtime();
    let (context, _) = context_with_ui();
    let (error, message) = call(&context, &runtime, "get_game_log", json!({}));
    assert_eq!((error, message), (true, json!("还没有启动过游戏")));
    let first = context.sessions.admit("单机 - A", "local_a", false).unwrap();
    for i in 0..600 {
        context.sessions.append(&first, format!("line {i}"));
    }
    let (_, log) = call(&context, &runtime, "get_game_log", json!({}));
    assert_eq!(log["session"], first.as_str());
    assert_eq!(log["lines"].as_array().unwrap().len(), tools::DEFAULT_LOG_TAIL as usize);
    assert_eq!(log["lines"].as_array().unwrap().last().unwrap(), "line 599");
    let (_, log) = call(&context, &runtime, "get_game_log", json!({"tail": 10000}));
    assert_eq!(log["lines"].as_array().unwrap().len(), tools::MAX_LOG_TAIL as usize);
    let (_, log) = call(&context, &runtime, "get_game_log", json!({"tail": 3}));
    assert_eq!(log["lines"], json!(["line 597", "line 598", "line 599"]));
    for bad in [json!(0), json!(-1), json!("5"), json!(2.5)] {
        let (error, _) = call(&context, &runtime, "get_game_log", json!({"tail": bad}));
        assert!(error, "tail {bad}");
    }

    let second = context.sessions.admit("单机 - B", "local_b", false).unwrap();
    let (error, message) = call(&context, &runtime, "get_game_log", json!({}));
    assert!(error && message.as_str().unwrap().contains(&second), "lists the games: {message}");
    let (_, log) = call(&context, &runtime, "get_game_log", json!({"session": second}));
    assert_eq!(log["state"], "starting");
    let (error, message) = call(&context, &runtime, "get_game_log", json!({"session": "nope"}));
    assert_eq!((error, message), (true, json!("找不到这个游戏：nope")));

    let (error, _) = call(&context, &runtime, "stop_game", json!({"session": "nope"}));
    assert!(error);
    let (error, stopped) = call(&context, &runtime, "stop_game", json!({"session": second, "force": true}));
    assert!(!error, "{stopped}");
    assert_eq!(stopped["result"], "stopped");
    let (error, message) = call(&context, &runtime, "stop_game", json!({"session": second}));
    assert_eq!((error, message), (true, json!("这个游戏已经结束")));
    let (_, log) = call(&context, &runtime, "get_game_log", json!({}));
    assert_eq!(log["session"], first.as_str(), "the only game still running");
}

#[test]
fn tasks_can_be_listed_read_and_cancelled() {
    let runtime = runtime();
    let (context, _) = context_with_ui();
    let (error, message) = call(&context, &runtime, "get_task", json!({"id": "nope"}));
    assert_eq!((error, message), (true, json!("找不到这个任务：nope")));
    let (error, _) = call(&context, &runtime, "cancel_task", json!({"id": "nope"}));
    assert!(error);
    let id = context.tasks.submit_steps(runtime.handle(), "完整下载整合包 甲".into(), "k".into(), &["下载", "解压"], |c| {
        c.step(0);
        c.progress("下载中", Some(0.5));
        while !c.cancel.cancelled() {
            std::thread::sleep(Duration::from_millis(1));
        }
        c.cancel.check()
    });
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    while context.tasks.snapshot(&id).unwrap()["message"] != "下载中" && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(5));
    }
    let (_, task) = call(&context, &runtime, "get_task", json!({"id": id}));
    assert_eq!(task["status"], "running");
    assert_eq!(task["steps"], json!([{"title": "下载", "status": "running", "detail": "50%"}, {"title": "解压", "status": "waiting", "detail": ""}]));
    let (_, list) = call(&context, &runtime, "list_tasks", json!({}));
    assert_eq!(list["tasks"][0]["id"], id.as_str());
    let (_, cancelled) = call(&context, &runtime, "cancel_task", json!({"id": id}));
    assert_eq!(cancelled["result"], "cancelling");
    while context.tasks.running() && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(5));
    }
    let (_, task) = call(&context, &runtime, "get_task", json!({"id": id}));
    assert_eq!(task["status"], "cancelled");
    let (error, message) = call(&context, &runtime, "cancel_task", json!({"id": id}));
    assert_eq!((error, message), (true, json!("任务已结束，无法取消")));
}

// ---- M3: game tools ----

/// A stand-in for the game's RMCP: answers `tools/list` and a few `tools/call` names and records
/// whether requests carried a session header.
fn fake_game() -> (String, Arc<Mutex<Vec<Value>>>) {
    use std::io::{Read, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    let seen = Arc::new(Mutex::new(Vec::new()));
    let log = seen.clone();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut stream) = stream else { continue };
            let log = log.clone();
            std::thread::spawn(move || {
                stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                let mut input = Vec::new();
                let mut buffer = [0u8; 4096];
                let body = loop {
                    if let Some(end) = input.windows(4).position(|w| w == b"\r\n\r\n") {
                        let head = String::from_utf8_lossy(&input[..end]).to_ascii_lowercase();
                        let length: usize = head
                            .lines()
                            .find_map(|l| l.strip_prefix("content-length:").map(|v| v.trim().parse().unwrap()))
                            .unwrap_or(0);
                        if input.len() >= end + 4 + length {
                            break serde_json::from_slice::<Value>(&input[end + 4..end + 4 + length]).unwrap();
                        }
                    }
                    match stream.read(&mut buffer) {
                        Ok(0) | Err(_) => return,
                        Ok(n) => input.extend_from_slice(&buffer[..n]),
                    }
                };
                log.lock().unwrap().push(body.clone());
                let result = match (body["method"].as_str(), body["params"]["name"].as_str()) {
                    (Some("tools/list"), _) => json!({"result": {"tools": [{"name": "screen_read", "description": "Read the screen", "inputSchema": {"type": "object"}}]}}),
                    (Some("tools/call"), Some("screenshot")) => json!({"result": {"content": [{"type": "image", "data": "iVBORw0KGgo=", "mimeType": "image/png"}], "isError": false}}),
                    (Some("tools/call"), Some("command")) => json!({"result": {"content": [{"type": "text", "text": "Unknown command"}], "isError": true}}),
                    _ => json!({"error": {"code": -32602, "message": "unknown tool: nope"}}),
                };
                let mut answer = json!({"jsonrpc": "2.0", "id": body["id"]});
                answer.as_object_mut().unwrap().extend(result.as_object().unwrap().clone());
                let text = answer.to_string();
                let head = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n", text.len());
                let _ = stream.write_all(head.as_bytes());
                let _ = stream.write_all(text.as_bytes());
            });
        }
    });
    (url, seen)
}

fn ready_game(sessions: &crate::play::Sessions, title: &str, url: &str) -> String {
    let id = sessions.admit_room(title, &format!("pack_{title}"), false).unwrap();
    sessions.expect_game_tools(&id);
    sessions.game_line(&id, format!("[main/INFO]: AI MCP URL: {url}"));
    id
}

#[test]
fn game_tools_pick_the_game_with_an_address() {
    let runtime = runtime();
    let (context, _) = context_with_ui();
    let (error, message) = call(&context, &runtime, "game_tools", json!({}));
    assert_eq!((error, message), (true, json!("没有正在运行的游戏")));

    let local = context.sessions.admit("单机 - A", "local_a", false).unwrap();
    let (_, message) = call(&context, &runtime, "game_tools", json!({}));
    assert_eq!(message, "这个游戏不支持AI操作");
    let (_, message) = call(&context, &runtime, "game_tools", json!({"session": local}));
    assert_eq!(message, "这个游戏不支持AI操作");

    let starting = context.sessions.admit_room("游玩 甲", "pack_a", false).unwrap();
    context.sessions.expect_game_tools(&starting);
    let (_, message) = call(&context, &runtime, "game_tools", json!({}));
    assert_eq!(message, "游戏还在启动，请稍后再试");

    let (url, seen) = fake_game();
    let one = ready_game(&context.sessions, "乙", &url);
    let (error, tools) = call(&context, &runtime, "game_tools", json!({}));
    assert!(!error, "{tools}");
    assert_eq!(tools["session"], one.as_str());
    assert_eq!(tools["tools"][0]["name"], "screen_read");
    assert_eq!(seen.lock().unwrap()[0]["method"], "tools/list");

    let two = ready_game(&context.sessions, "丙", &url);
    let (error, message) = call(&context, &runtime, "game_tools", json!({}));
    assert!(error && message.as_str().unwrap().contains(&one) && message.as_str().unwrap().contains(&two), "{message}");
    let (error, tools) = call(&context, &runtime, "game_tools", json!({"session": two}));
    assert!(!error && tools["session"] == two.as_str());

    context.sessions.mark_exited(&two, None);
    let (_, message) = call(&context, &runtime, "game_tools", json!({"session": two}));
    assert_eq!(message, "这个游戏已经结束");
    let (_, tools) = call(&context, &runtime, "game_tools", json!({}));
    assert_eq!(tools["session"], one.as_str(), "the ended game no longer counts");
}

#[test]
fn game_call_returns_the_game_result_unchanged() {
    let runtime = runtime();
    let (context, _) = context_with_ui();
    let (url, seen) = fake_game();
    ready_game(&context.sessions, "甲", &url);

    let image = runtime.block_on(tools::call(&context, "game_call", &json!({"tool": "screenshot", "arguments": {"scale": 0.5}}))).unwrap();
    assert_eq!(image, json!({"content": [{"type": "image", "data": "iVBORw0KGgo=", "mimeType": "image/png"}], "isError": false}));
    let request = seen.lock().unwrap().last().cloned().unwrap();
    assert_eq!(request["method"], "tools/call");
    assert_eq!(request["params"], json!({"name": "screenshot", "arguments": {"scale": 0.5}}));

    let failed = runtime.block_on(tools::call(&context, "game_call", &json!({"tool": "command"}))).unwrap();
    assert_eq!(failed["isError"], true);
    assert_eq!(failed["content"][0]["text"], "Unknown command");
    assert_eq!(seen.lock().unwrap().last().unwrap()["params"]["arguments"], json!({}));

    let (error, message) = call(&context, &runtime, "game_call", json!({"tool": "nope"}));
    assert_eq!((error, message), (true, json!("游戏返回错误：unknown tool: nope")));
    let (error, message) = call(&context, &runtime, "game_call", json!({"tool": "screenshot", "arguments": [1]}));
    assert_eq!((error, message), (true, json!("参数arguments必须是对象")));
    let (error, message) = call(&context, &runtime, "game_call", json!({}));
    assert_eq!((error, message), (true, json!("缺少参数tool")));

    // A game that went away: the address now points at a closed port.
    let closed = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
    let (context, _) = context_with_ui();
    ready_game(&context.sessions, "丁", &format!("http://127.0.0.1:{closed}/mcp"));
    let (error, message) = call(&context, &runtime, "game_call", json!({"tool": "screenshot"}));
    assert!(error && message.as_str().unwrap().starts_with("无法连接游戏"), "{message}");
}
