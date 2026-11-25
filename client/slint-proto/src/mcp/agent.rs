//! Which agent uses the launcher MCP, for the title bar capsule (owner, 2026-11-25). The name and
//! version come from `initialize`'s `clientInfo`, else from the HTTP User-Agent. HTTP is
//! stateless, so an agent counts as connected while its requests keep coming: `IDLE` after the
//! last one it is gone, and at once when the assistant is turned off.
use serde_json::Value;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

pub const IDLE: Duration = Duration::from_secs(10 * 60);
/// Names longer than this are cut in the capsule.
const MAX_NAME_CHARS: usize = 16;

/// Brands with an icon in `assets/agents`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Brand {
    Claude,
    ChatGpt,
    DeepSeek,
    Doubao,
    Kimi,
    Qwen,
    WorkBuddy,
}

impl Brand {
    /// The order of the menu's icon row.
    pub const ALL: [Brand; 7] = [Brand::ChatGpt, Brand::Claude, Brand::DeepSeek, Brand::Doubao, Brand::Kimi, Brand::Qwen, Brand::WorkBuddy];

    pub fn label(self) -> &'static str {
        match self {
            Brand::Claude => "Claude",
            Brand::ChatGpt => "ChatGPT",
            Brand::DeepSeek => "DeepSeek",
            Brand::Doubao => "豆包",
            Brand::Kimi => "Kimi",
            Brand::Qwen => "通义千问",
            Brand::WorkBuddy => "WorkBuddy",
        }
    }

    /// The AVIF icon.
    pub fn icon(self) -> &'static [u8] {
        match self {
            Brand::Claude => include_bytes!("../../assets/agents/claude.avif"),
            Brand::ChatGpt => include_bytes!("../../assets/agents/chatgpt.avif"),
            Brand::DeepSeek => include_bytes!("../../assets/agents/deepseek.avif"),
            Brand::Doubao => include_bytes!("../../assets/agents/doubao.avif"),
            Brand::Kimi => include_bytes!("../../assets/agents/kimi.avif"),
            Brand::Qwen => include_bytes!("../../assets/agents/qwen.avif"),
            Brand::WorkBuddy => include_bytes!("../../assets/agents/workbuddy.avif"),
        }
    }
}

/// The brand a client name or User-Agent belongs to. The more specific names come first:
/// CodeBuddy and WorkBuddy before anything else, OpenAI's last because "gpt" is short.
pub fn brand_of(text: &str) -> Option<Brand> {
    let text = text.to_lowercase();
    let has = |words: &[&str]| words.iter().any(|w| text.contains(w));
    if has(&["workbuddy", "codebuddy"]) {
        Some(Brand::WorkBuddy)
    } else if has(&["claude", "anthropic"]) {
        Some(Brand::Claude)
    } else if has(&["deepseek"]) {
        Some(Brand::DeepSeek)
    } else if has(&["doubao", "trae"]) {
        Some(Brand::Doubao)
    } else if has(&["kimi", "moonshot"]) {
        Some(Brand::Kimi)
    } else if has(&["qwen", "tongyi", "lingma"]) {
        Some(Brand::Qwen)
    } else if has(&["codex", "openai", "chatgpt", "gpt"]) {
        Some(Brand::ChatGpt)
    } else {
        None
    }
}

/// The connected agent as the title bar shows it.
#[derive(Clone, Debug, PartialEq)]
pub struct Agent {
    /// What the capsule says: the brand, or the client's own name cut to fit.
    pub label: String,
    pub brand: Option<Brand>,
    /// The client name and version, for the menu.
    pub detail: String,
}

#[derive(Default)]
struct Entry {
    client: Option<(String, String)>,
    user_agent: Option<String>,
    last: Option<Instant>,
}

/// Shared by the server, which records requests, and the UI, which reads the agent.
#[derive(Clone, Default)]
pub struct Seen(Arc<Mutex<Entry>>);

impl Seen {
    fn entry(&self) -> std::sync::MutexGuard<'_, Entry> {
        self.0.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// An authorized request arrived.
    pub fn request(&self, user_agent: Option<&str>) {
        self.request_at(user_agent, Instant::now());
    }

    fn request_at(&self, user_agent: Option<&str>, now: Instant) {
        let mut entry = self.entry();
        if let Some(ua) = user_agent.map(str::trim).filter(|ua| !ua.is_empty()) {
            entry.user_agent = Some(ua.to_owned());
        }
        entry.last = Some(now);
    }

    /// `initialize`: a new session of an agent, maybe another one than before.
    pub fn initialize(&self, params: &Value) {
        let info = &params["clientInfo"];
        let name = info["name"].as_str().map(str::trim).filter(|n| !n.is_empty());
        let mut entry = self.entry();
        entry.client = name.map(|n| (n.to_owned(), info["version"].as_str().unwrap_or("").trim().to_owned()));
        entry.last = Some(Instant::now());
    }

    /// The agent unless the assistant has been idle for `IDLE`.
    pub fn current(&self) -> Option<Agent> {
        self.current_at(Instant::now())
    }

    fn current_at(&self, now: Instant) -> Option<Agent> {
        let entry = self.entry();
        if now.duration_since(entry.last?) > IDLE {
            return None;
        }
        let (name, version) = match (&entry.client, &entry.user_agent) {
            (Some((name, version)), _) => (name.clone(), version.clone()),
            // `product/version comment` of a User-Agent.
            (None, Some(ua)) => {
                let product = ua.split_whitespace().next().unwrap_or(ua);
                let (name, version) = product.split_once('/').unwrap_or((product, ""));
                (name.to_owned(), version.to_owned())
            }
            (None, None) => return None,
        };
        let brand = brand_of(&name).or_else(|| entry.user_agent.as_deref().and_then(brand_of));
        let label = match brand {
            Some(brand) => brand.label().to_owned(),
            None if name.chars().count() > MAX_NAME_CHARS => format!("{}…", name.chars().take(MAX_NAME_CHARS - 1).collect::<String>()),
            None => name.clone(),
        };
        let detail = if version.is_empty() { name } else { format!("{name} {version}") };
        Some(Agent { label, brand, detail })
    }

    /// The assistant was turned off.
    pub fn clear(&self) {
        *self.entry() = Entry::default();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn brands_are_found_in_client_names_and_user_agents() {
        for (text, brand) in [
            ("claude-code", Brand::Claude),
            ("Claude Desktop", Brand::Claude),
            ("codex-mcp-client", Brand::ChatGpt),
            ("openai-mcp", Brand::ChatGpt),
            ("deepseek-cli/0.1", Brand::DeepSeek),
            ("Trae", Brand::Doubao),
            ("kimi-cli", Brand::Kimi),
            ("qwen-code", Brand::Qwen),
            ("CodeBuddy", Brand::WorkBuddy),
            ("WorkBuddy/1.2 (Windows)", Brand::WorkBuddy),
        ] {
            assert_eq!(brand_of(text), Some(brand), "{text}");
        }
        assert_eq!(brand_of("cursor-vscode"), None);
        assert_eq!(brand_of("node"), None);
    }

    #[test]
    fn the_client_info_names_the_agent_and_idle_time_ends_it() {
        let seen = Seen::default();
        assert_eq!(seen.current(), None);
        seen.initialize(&json!({"clientInfo": {"name": "claude-code", "version": "2.1.3"}}));
        seen.request(Some("node"));
        let agent = seen.current().unwrap();
        assert_eq!((agent.label.as_str(), agent.brand, agent.detail.as_str()), ("Claude", Some(Brand::Claude), "claude-code 2.1.3"));

        let later = Instant::now() + IDLE + Duration::from_secs(1);
        assert_eq!(seen.current_at(later), None, "idle for too long");
        seen.request_at(Some("node"), later);
        assert!(seen.current_at(later).is_some(), "a new request brings it back");

        seen.clear();
        assert_eq!(seen.current(), None);
    }

    #[test]
    fn without_client_info_the_user_agent_names_it() {
        let seen = Seen::default();
        seen.request(Some("qwen-code/0.0.9 (win32; x64)"));
        let agent = seen.current().unwrap();
        assert_eq!((agent.label.as_str(), agent.detail.as_str()), ("通义千问", "qwen-code 0.0.9"));

        let seen = Seen::default();
        seen.initialize(&json!({"clientInfo": {"name": "a-very-long-unknown-agent-name"}}));
        let agent = seen.current().unwrap();
        assert_eq!(agent.brand, None);
        assert_eq!(agent.label, "a-very-long-unk…");
        assert_eq!(agent.detail, "a-very-long-unknown-agent-name");
    }
}
