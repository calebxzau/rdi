//! J5a wiring between the pack screens and Rust game sessions (`crate::play`). Local packs on
//! Forge 1.20.1 and NeoForge 1.21.1 start offline in Rust; Microsoft online play, RDI packs and
//! rooms keep using the service, whose sessions are mirrored here so the console lists both.
use super::{CandidateRow, LogRow, PackUi, sync, text};
use crate::AppWindow;
use serde_json::{Value, json};
use slint::ComponentHandle;
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;

/// The pack whose play dialog Rust opened.
pub(super) struct NativePlay {
    version_id: String,
    name: String,
    mc_version: String,
    loader: String,
    dir: PathBuf,
}

/// Game state the service last reported (`game.state`).
#[derive(Default)]
pub struct ServiceGames {
    pub any_session: bool,
    pub alive_by_version: HashMap<String, usize>,
}

/// `rdi-local-pack.json` fields the play dialog needs.
#[derive(serde::Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct LocalPlayInfo {
    name: String,
    mc_version: String,
    mod_loader: String,
    last_account_mode: Option<String>,
    last_offline_name: Option<String>,
}

impl PackUi {
    /// `packPlay.open` for a local pack Rust can start; `false` leaves it to the service.
    pub(super) fn open_native_play(&mut self, ui: &AppWindow, version_id: &str) -> bool {
        let Some(dir) = self.pack_dirs.get(version_id).cloned() else {
            return false;
        };
        if crate::local_packs::remote_name(version_id).is_some() {
            return false;
        }
        let info: LocalPlayInfo = match std::fs::read_to_string(dir.join("rdi-local-pack.json")).map(|t| serde_json::from_str(&t)) {
            Ok(Ok(info)) => info,
            _ => return false,
        };
        let Some(mc_version) = crate::local_packs::mc_version_name(&info.mc_version) else {
            return false;
        };
        if crate::mc_install::meta::loader(mc_version, &info.mod_loader).is_err() {
            return false;
        }
        let online = self.api.as_ref().is_some_and(|a| a.has_msid);
        let mode = match info.last_account_mode.as_deref() {
            Some("Online") if online => "Online",
            _ => "Offline",
        };
        let nickname = info
            .last_offline_name
            .clone()
            .unwrap_or_else(|| self.api.as_ref().map(|a| a.account_name.clone()).unwrap_or_default());
        self.native_play = Some(NativePlay {
            version_id: version_id.into(),
            name: info.name.clone(),
            mc_version: mc_version.into(),
            loader: info.mod_loader.clone(),
            dir,
        });
        ui.set_play_busy(false);
        ui.set_play_error("".into());
        ui.set_play_device_uri("".into());
        ui.set_play_nickname(nickname.clone().into());
        ui.set_play_nonstandard_name(!super::standard_name(&nickname));
        ui.set_play_online(mode == "Online");
        ui.set_play_online_available(online);
        ui.set_local_play(true);
        true
    }

    /// Play from the dialog. Offline starts in Rust; online goes to the service as before.
    /// Returns `false` when the service must handle it.
    pub(super) fn start_native_play(&mut self, ui: &AppWindow, allow_duplicate: bool) -> bool {
        let Some(native) = &self.native_play else {
            return false;
        };
        if native.version_id != ui.get_pack_selected_id().as_str() {
            self.native_play = None;
            return false;
        }
        let external = self.service_games.alive_by_version.get(&native.version_id).copied().unwrap_or(0);
        if ui.get_play_online() {
            // J5c moves Microsoft play; until then a Rust game of this pack still counts as a duplicate.
            if !allow_duplicate && self.play.alive_count(&native.version_id) > 0 {
                ui.set_local_play(false);
                ui.set_duplicate_host(format!("pack:{}", native.version_id).into());
                return true;
            }
            return false;
        }
        if crate::java_runtime::state() == crate::java_runtime::State::Missing {
            ui.set_play_busy(false);
            ui.set_local_play(false);
            if cfg!(windows) {
                ui.set_java_missing(true);
            } else {
                ui.set_action_error(format!("{}，请安装后重新启动启动器", crate::java_runtime::MISSING_MESSAGE).into());
            }
            return true;
        }
        let Some(deps) = self.play_deps() else {
            ui.set_action_error("请先登录".into());
            return true;
        };
        let size = ui.window().size();
        let request = crate::play::LocalStart {
            version_id: native.version_id.clone(),
            name: native.name.clone(),
            mc_version: native.mc_version.clone(),
            loader: native.loader.clone(),
            version_dir: native.dir.clone(),
            nickname: ui.get_play_nickname().to_string(),
            window: (size.width, size.height),
            allow_duplicate,
            external_alive: external,
        };
        match crate::play::start_local(&deps, request) {
            Ok(crate::play::Admitted::Started(id)) => {
                ui.set_local_play(false);
                ui.set_play_busy(false);
                self.native_play = None;
                self.session_id = id;
                self.render_console();
            }
            Ok(crate::play::Admitted::Duplicate) => {
                ui.set_local_play(false);
                ui.set_play_busy(false);
                ui.set_duplicate_host(format!("pack:{}", ui.get_pack_selected_id()).into());
            }
            Err(error) => {
                tracing::warn!(error = %format!("{error:#}"), "启动整合包失败");
                ui.set_play_busy(false);
                ui.set_play_error(error.to_string().into());
            }
        }
        true
    }

    /// `play.cancel`: a dialog Rust opened needs no service call.
    pub(super) fn cancel_native_play(&mut self) -> bool {
        self.native_play.take().is_some()
    }

    fn play_deps(&self) -> Option<crate::play::Deps> {
        let api = self.api.as_ref()?;
        let data = crate::paths::data_dir();
        let config = data.join("config.toml");
        let server = api.server().clone();
        let headers_server = server.clone();
        Some(crate::play::Deps {
            sessions: self.play.clone(),
            runtime: self.runtime.clone(),
            install: crate::mc_install::Deps {
                downloader: crate::download::Downloader { http: api.http.clone() },
                server_base: Some(server.url("")),
                auth_headers: std::sync::Arc::new(move |url| {
                    headers_server.auth_headers(url).unwrap_or_else(|error| {
                        tracing::warn!(%error, "无法生成下载请求头");
                        Default::default()
                    })
                }),
                prefer_mirror: crate::mc_install::config_bool(&config, "preferMcMirror", true),
                mc_dir: data.join("mc"),
                java: PathBuf::new(),
                host: crate::mc_launch::manifest::Host::current(),
            },
            database: crate::pack_options::database_path(),
            max_memory_mb: crate::mc_install::config_int(&config, "maxMemory", 0),
            launcher_version: env!("RDI_APP_VERSION").into(),
        })
    }

    /// `java.install`: the updater's automatic Java 25 install as a launcher task.
    pub(super) fn install_java(&mut self) {
        let Some(api) = self.api.as_ref() else {
            return;
        };
        let downloader = crate::download::Downloader { http: api.http.clone() };
        let runtime = self.runtime.clone();
        let run = self.native_tasks.submit(&self.runtime, "安装Java25".into(), "java25-install".into(), move |context| {
            let progress = |message: String| context.progress(message, None);
            runtime.block_on(crate::java_runtime::install(&downloader, &context.cancel, &progress)).map(|jdk| {
                context.progress(format!("已安装 {}", jdk.java_home.display()), Some(1.0));
            })
        });
        self.open_task(&run);
    }

    /// The service's `game.state`, kept so Rust can count its sessions per version.
    pub fn service_game_state(&mut self, sessions: &[Value]) {
        let mut by_version = HashMap::new();
        for s in sessions.iter().filter(|s| s["running"] == true || s["preparing"] == true) {
            if let Some(v) = s["process"]["versionId"].as_str() {
                *by_version.entry(v.to_owned()).or_insert(0) += 1;
            }
        }
        self.service_games = ServiceGames { any_session: !sessions.is_empty(), alive_by_version: by_version };
    }

    pub fn clear_service_games(&mut self) {
        self.service_games = ServiceGames::default();
        self.service_sessions.clear();
        self.service_lines.clear();
        self.render_console();
    }

    /// `console.subscribe`/`console.select`/`console.clear` across Rust and service sessions.
    pub(super) fn console_action(&mut self, action: &str, value: &str) {
        if action == "console.select" {
            self.session_id = value.into();
        }
        let rust = self.play.contains(&self.session_id) || (self.session_id.is_empty() && self.service_sessions.is_empty());
        if rust {
            if self.session_id.is_empty()
                && let Some(last) = self.play.views().last()
            {
                self.session_id = last.id.clone();
            }
            if action == "console.clear" {
                self.play.clear(&self.session_id);
            }
            self.render_console();
        }
        // The service keeps its own sessions; it is only asked when it has any, so the console
        // never starts it.
        if self.service_games.any_session || !self.service_sessions.is_empty() {
            let id = if rust { String::new() } else { self.session_id.clone() };
            let method = if action == "console.clear" && !rust { "console.clear" } else { "console.subscribe" };
            self.call(method, json!({"sessionId": id}));
        }
    }

    /// `console.state` from the service.
    pub(super) fn service_console(&mut self, v: &Value) {
        self.service_sessions = super::array(v, "sessions").iter().map(|t| (text(t, "id").to_owned(), text(t, "title").to_owned())).collect();
        let service_id = text(v, "sessionId").to_owned();
        if !self.play.contains(&self.session_id) {
            self.session_id = service_id.clone();
            self.service_lines = super::array(v, "lines").iter().filter_map(Value::as_str).map(str::to_owned).collect();
        }
        self.render_console();
    }

    /// Redraws the console when a Rust session changed.
    pub(super) fn tick_play(&mut self) {
        let revision = self.play.revision();
        if revision != self.play_revision {
            self.play_revision = revision;
            if self.play.contains(&self.session_id) || self.session_id.is_empty() {
                self.render_console();
            }
        }
    }

    pub(super) fn render_console(&mut self) {
        let rust = self.play.views();
        if self.session_id.is_empty()
            && self.service_sessions.is_empty()
            && let Some(last) = rust.last()
        {
            self.session_id = last.id.clone();
        }
        let mut rows: Vec<(String, String)> = self.service_sessions.clone();
        let known: HashSet<String> = rows.iter().map(|r| r.0.clone()).collect();
        rows.extend(rust.iter().filter(|s| !known.contains(&s.id)).map(|s| (s.id.clone(), s.title.clone())));
        sync(
            &self.sessions,
            rows.iter()
                .enumerate()
                .map(|(i, (id, title))| CandidateRow {
                    name: title.as_str().into(),
                    detail: id.as_str().into(),
                    index: i as i32,
                    collapsed: *id == self.session_id,
                    ..Default::default()
                })
                .collect(),
        );
        let lines = if self.play.contains(&self.session_id) { self.play.lines(&self.session_id) } else { self.service_lines.clone() };
        sync(
            &self.logs,
            lines
                .iter()
                .map(|line| LogRow {
                    text: line.as_str().into(),
                    level: if line.contains("ERROR") {
                        2
                    } else if line.contains("WARN") {
                        1
                    } else {
                        0
                    },
                })
                .collect(),
        );
        if let Some(ui) = self.ui.upgrade() {
            ui.set_activity_log_text(lines.join("\n").into());
        }
    }

    /// Rust sessions for the game bar and the running-game guard.
    pub fn rust_sessions(&self) -> Vec<crate::play::SessionView> {
        self.play.views()
    }
}
