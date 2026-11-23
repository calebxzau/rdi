//! J5b: which node and which server address this player uses, ported from Kotlin
//! `CarrierDetectBridge.refreshNodeSettings`, `RServer.routeState` and `resolveProxyRoute`.
//! The node entry is refreshed at sign-in and before each room start; a failure is only logged
//! and the last state is kept (B3: automatic routing only, no manual switch yet).
//! No Slint types, so the planned launcher MCP can use it.
pub mod link;

use crate::api::server::ServerApi;
use anyhow::{Context, Result, ensure};
use serde::Deserialize;
use std::sync::Mutex;

/// `DEFAULT_GAME_NODE_ADDR`: the built-in node, always the last one tried.
pub const DEFAULT_GAME_NODE_ADDR: &str = "rdi.calebxzhou.cn:65233";
const DEFAULT_NODE_NAME: &str = "默认节点";
const DEFAULT_PRIMARY_HOST: &str = "rdi.calebxzhou.cn";
/// `DEBUG_NODE_PORT`: the node port of a local test master.
const DEBUG_NODE_PORT: u16 = 65230;

#[derive(Clone, Debug, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct GameNode {
    pub name: String,
    pub game_addr: String,
}

/// Kotlin `ServerEntry`.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerEntry {
    #[serde(default)]
    pub api: Option<String>,
    #[serde(default)]
    pub use_backup_node: bool,
    pub node_name: String,
    pub game_addr: String,
    #[serde(default)]
    pub candidates: Vec<GameNode>,
    #[serde(default)]
    pub session_resume: bool,
}

/// Kotlin `ServerRouteState`.
#[derive(Clone, Debug, PartialEq)]
pub struct RouteState {
    pub use_backup_node: bool,
    pub entry_host: String,
    pub node_name: Option<String>,
    pub game_addr: String,
    pub candidates: Vec<GameNode>,
    pub session_resume: bool,
}

impl Default for RouteState {
    fn default() -> Self {
        Self {
            use_backup_node: false,
            entry_host: DEFAULT_PRIMARY_HOST.into(),
            node_name: None,
            game_addr: DEFAULT_GAME_NODE_ADDR.into(),
            candidates: vec![],
            session_resume: false,
        }
    }
}

impl RouteState {
    /// `RServer.updateServerEntry`.
    pub fn apply(&mut self, entry: &ServerEntry) {
        self.use_backup_node = entry.use_backup_node;
        self.entry_host = entry.api.as_deref().map(str::trim).filter(|a| !a.is_empty()).unwrap_or(DEFAULT_PRIMARY_HOST).into();
        self.node_name = Some(entry.node_name.clone()).filter(|n| !n.trim().is_empty());
        self.game_addr = Some(entry.game_addr.trim()).filter(|a| !a.is_empty()).unwrap_or(DEFAULT_GAME_NODE_ADDR).into();
        self.candidates = entry.candidates.clone();
        self.session_resume = entry.session_resume;
    }

    /// `RServer.markActiveNode`: the node a running game actually moved to.
    pub fn mark_active(&mut self, addr: &str) {
        if self.game_addr != addr {
            self.node_name = Some(self.name_of(addr));
            self.game_addr = addr.into();
        }
    }

    /// The node's display name; players never see addresses.
    pub fn name_of(&self, addr: &str) -> String {
        self.candidates.iter().find(|c| c.game_addr == addr).map(|c| c.name.clone()).unwrap_or_else(|| DEFAULT_NODE_NAME.into())
    }

    /// `RServer.now`: the backup address on port 443 when the entry asks for it.
    pub fn server_base(&self, primary: &str) -> String {
        if self.use_backup_node { format!("https://{}:443", self.entry_host) } else { primary.trim_end_matches('/').into() }
    }

    /// `resolveProxyRoute`: the chosen node, the candidates, then the built-in node.
    pub fn proxy_nodes(&self) -> Vec<String> {
        let selected = if self.game_addr.trim().is_empty() { DEFAULT_GAME_NODE_ADDR } else { &self.game_addr };
        let mut nodes: Vec<String> = vec![];
        for node in std::iter::once(selected).chain(self.candidates.iter().map(|c| c.game_addr.as_str())).chain([DEFAULT_GAME_NODE_ADDR]) {
            if !nodes.iter().any(|n| n == node) {
                nodes.push(node.into());
            }
        }
        nodes
    }
}

fn shared() -> &'static Mutex<RouteState> {
    static STATE: std::sync::OnceLock<Mutex<RouteState>> = std::sync::OnceLock::new();
    STATE.get_or_init(Default::default)
}

pub fn state() -> RouteState {
    shared().lock().unwrap().clone()
}

pub fn mark_active(addr: &str) {
    shared().lock().unwrap().mark_active(addr);
}

/// Where the launcher talks to: the official primary address, or a local test master given with
/// `RDI_PROTO_SERVER` (Kotlin's `DEBUG` build, which never switches address and never uses
/// public nodes).
#[derive(Clone, Debug)]
pub struct Endpoint {
    pub primary: String,
    pub debug: bool,
}

impl Endpoint {
    pub fn new(primary: &str) -> Self {
        let primary = primary.trim_end_matches('/').to_owned();
        let debug = std::env::var_os("RDI_PROTO_SERVER").is_some();
        Self { primary, debug }
    }

    /// The nodes the room connection tries, in order. A test master uses `RDI_LOCAL_PROXY_NODES`
    /// (comma separated) or its own host on the debug node port (`resolveDebugProxyRoute`).
    pub fn proxy_nodes(&self, state: &RouteState) -> Vec<String> {
        if !self.debug {
            return state.proxy_nodes();
        }
        let nodes: Vec<String> = std::env::var("RDI_LOCAL_PROXY_NODES")
            .unwrap_or_default()
            .split(',')
            .map(str::trim)
            .filter(|n| !n.is_empty())
            .map(String::from)
            .collect();
        if !nodes.is_empty() {
            return nodes;
        }
        let host = reqwest::Url::parse(&self.primary).ok().and_then(|u| u.host_str().map(str::to_owned)).unwrap_or_else(|| "localhost".into());
        vec![format!("{host}:{DEBUG_NODE_PORT}")]
    }
}

/// Kotlin's `ipv4Regex`, leading zeros refused.
fn valid_ipv4(text: &str) -> bool {
    static RE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    RE.get_or_init(|| regex::Regex::new(r"^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$").unwrap())
        .is_match(text)
}

/// `refreshNodeSettings`: the public IPv4, then `server-entry` on the primary address. Updates
/// the route state and, outside test mode, moves every server request to the address it names.
pub async fn refresh(server: &ServerApi, endpoint: &Endpoint) -> Result<ServerEntry> {
    tracing::info!("刷新节点入口");
    let result = async {
        let ip = server.http.get("http://ip.3322.net/").send().await.context("无法读取ip信息")?.text().await.context("无法读取ip信息")?;
        let ip = ip.trim().to_owned();
        ensure!(valid_ipv4(&ip), "无效IPv4: {ip}");
        let lookup = server.at(&endpoint.primary);
        let entry: ServerEntry = lookup.get_query("/server-entry", &[("myIp", ip)]).await.context("获取游戏节点失败")?;
        Ok(entry)
    }
    .await;
    match result {
        Ok(entry) => {
            let state = {
                let mut state = shared().lock().unwrap();
                state.apply(&entry);
                state.clone()
            };
            if !endpoint.debug {
                server.switch_base(&state.server_base(&endpoint.primary));
            }
            tracing::info!(node = %state.node_name.as_deref().unwrap_or(DEFAULT_NODE_NAME), backup = state.use_backup_node, "节点刷新成功");
            Ok(entry)
        }
        Err(error) => {
            tracing::warn!(error = %format!("{error:#}"), "节点刷新失败，继续使用当前节点");
            Err(error)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(json: serde_json::Value) -> ServerEntry {
        serde_json::from_value(json).unwrap()
    }

    #[test]
    fn entry_updates_the_state_like_kotlin() {
        let mut state = RouteState::default();
        state.apply(&entry(serde_json::json!({
            "api": " backup.example ", "useBackupNode": true, "nodeName": "上海", "gameAddr": " sh.example:1 ",
            "candidates": [{"name": "上海", "gameAddr": "sh.example:1"}, {"name": "广州", "gameAddr": "gz.example:2"}],
            "sessionResume": true,
        })));
        assert_eq!(state.server_base("https://rdi.calebxzhou.cn:65331"), "https://backup.example:443");
        assert_eq!(state.proxy_nodes(), ["sh.example:1", "gz.example:2", DEFAULT_GAME_NODE_ADDR]);
        assert_eq!(state.node_name.as_deref(), Some("上海"));
        state.mark_active("gz.example:2");
        assert_eq!((state.game_addr.as_str(), state.node_name.as_deref()), ("gz.example:2", Some("广州")));
        state.mark_active("other:3");
        assert_eq!(state.node_name.as_deref(), Some(DEFAULT_NODE_NAME));
        assert_eq!(state.name_of("nowhere"), DEFAULT_NODE_NAME);

        state.apply(&entry(serde_json::json!({"nodeName": "", "gameAddr": " "})));
        assert_eq!(state.server_base("https://primary:65331/"), "https://primary:65331");
        assert_eq!((state.entry_host.as_str(), state.node_name.clone()), (DEFAULT_PRIMARY_HOST, None));
        assert_eq!(state.proxy_nodes(), [DEFAULT_GAME_NODE_ADDR]);
    }

    #[test]
    fn test_master_uses_its_own_node() {
        let endpoint = Endpoint { primary: "http://127.0.0.1:65231".into(), debug: true };
        if std::env::var_os("RDI_LOCAL_PROXY_NODES").is_none() {
            assert_eq!(endpoint.proxy_nodes(&RouteState::default()), ["127.0.0.1:65230"]);
        }
        let official = Endpoint { primary: "https://rdi.calebxzhou.cn:65331".into(), debug: false };
        assert_eq!(official.proxy_nodes(&RouteState::default()), [DEFAULT_GAME_NODE_ADDR]);
    }

    #[test]
    fn ipv4_check_matches_the_kotlin_regex() {
        assert!(valid_ipv4("1.2.3.4") && valid_ipv4("255.255.255.255"));
        assert!(!valid_ipv4("256.1.1.1") && !valid_ipv4("1.2.3") && !valid_ipv4("a.b.c.d") && !valid_ipv4("1.2.3.4\n5") && !valid_ipv4("01.2.3.4"));
    }

    #[test]
    fn switching_the_address_moves_every_clone() {
        let api = ServerApi::with_token("https://a.example:65331", "t");
        let clone = api.clone();
        let lookup = api.at("https://primary.example:65331");
        clone.switch_base("https://b.example:443/");
        assert_eq!(api.url("/x"), "https://b.example:443/x");
        assert_eq!(lookup.url("/x"), "https://primary.example:65331/x", "a lookup copy keeps its own address");
    }
}
