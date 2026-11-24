//! Launcher MCP tests: HTTP checks, protocol, settings and tools against fakes. Nothing here
//! contacts external hosts.
use super::*;
use serde_json::{Value, json};
use std::sync::{Arc, Mutex};
use std::time::Duration;

fn temp() -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rdi-mcp-test-{}", uuid::Uuid::now_v7()));
    std::fs::create_dir_all(&dir).unwrap();
    dir
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
}

impl Live {
    fn start() -> Self {
        let runtime = runtime();
        let (context, _) = context_with_ui();
        let mut controller = Controller::new(temp().join(SETTINGS_FILE), runtime.handle().clone(), context);
        let started = controller.start().unwrap();
        let token = controller.token.clone();
        let http = reqwest::Client::builder().no_proxy().build().unwrap();
        Live { runtime, controller, port: started.port, token, http }
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
