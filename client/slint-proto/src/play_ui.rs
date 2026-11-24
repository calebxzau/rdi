//! J5a/J5c wiring between the pack screens and Rust game sessions (`crate::play`). Local packs
//! start offline in Rust and installed RDI packs start alone in Rust (`play::room::start_solo`);
//! Microsoft online play resolves the bound profile in Rust (`crate::msa_play`). Every game is a
//! Rust session; the launcher starts no other process for play (J5c C4).
use super::{CandidateRow, LogRow, PackUi, sync};
use crate::AppWindow;
use slint::ComponentHandle;
use std::path::PathBuf;

/// The pack whose play dialog Rust opened.
pub(super) struct NativePlay {
    version_id: String,
    name: String,
    mc_version: String,
    loader: String,
    dir: PathBuf,
    /// An installed RDI pack: started at once with the RDI core, no dialog (`rdiPackPlayArgs`).
    rdi: bool,
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
    /// `packPlay.open`: an installed RDI pack starts at once, a local pack opens the play dialog.
    /// A pack that cannot start shows why.
    pub(super) fn open_native_play(&mut self, ui: &AppWindow, version_id: &str) {
        let Some(dir) = self.pack_dirs.get(version_id).cloned() else {
            ui.set_action_error("找不到这个整合包，请刷新整合包列表".into());
            return;
        };
        if crate::local_packs::remote_name(version_id).is_some() {
            self.native_play = Some(NativePlay {
                version_id: version_id.into(),
                name: String::new(),
                mc_version: String::new(),
                loader: String::new(),
                dir,
                rdi: true,
            });
            ui.set_local_play(false);
            self.start_native_play(ui, false);
            return;
        }
        let info: LocalPlayInfo = match std::fs::read_to_string(dir.join("rdi-local-pack.json")).map(|t| serde_json::from_str(&t)) {
            Ok(Ok(info)) => info,
            _ => {
                tracing::warn!(version = version_id, "本地整合包信息无法读取");
                ui.set_action_error("本地整合包信息损坏，无法启动".into());
                return;
            }
        };
        let Some(mc_version) = crate::local_packs::mc_version_name(&info.mc_version) else {
            ui.set_action_error(format!("不支持的MC版本：{}", info.mc_version).into());
            return;
        };
        if let Err(error) = crate::mc_install::meta::runtime(mc_version, &info.mod_loader) {
            ui.set_action_error(error.to_string().into());
            return;
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
            rdi: false,
        });
        ui.set_play_busy(false);
        ui.set_play_error("".into());
        ui.set_play_device_uri("".into());
        ui.set_play_nickname(nickname.clone().into());
        ui.set_play_nonstandard_name(!super::standard_name(&nickname));
        ui.set_play_online(mode == "Online");
        ui.set_play_online_available(online);
        ui.set_local_play(true);
    }

    /// Play from the dialog: offline at once, online after the Microsoft identity is resolved
    /// (`crate::msa_play`), an installed RDI pack alone with the RDI core.
    pub(super) fn start_native_play(&mut self, ui: &AppWindow, allow_duplicate: bool) {
        let Some(native) = &self.native_play else {
            return;
        };
        if native.version_id != ui.get_pack_selected_id().as_str() {
            self.native_play = None;
            return;
        }
        if crate::java_runtime::state() == crate::java_runtime::State::Missing {
            ui.set_play_busy(false);
            ui.set_local_play(false);
            if cfg!(windows) {
                ui.set_java_missing(true);
            } else {
                ui.set_action_error(format!("{}，请安装后重新启动启动器", crate::java_runtime::MISSING_MESSAGE).into());
            }
            return;
        }
        if ui.get_play_online() && !native.rdi {
            // Checked before the login, so a second copy does not ask for one first.
            if !allow_duplicate && self.play.alive_count(&native.version_id) > 0 {
                ui.set_local_play(false);
                ui.set_duplicate_host(format!("pack:{}", native.version_id).into());
                return;
            }
            self.login_online(ui, allow_duplicate);
            return;
        }
        let size = ui.window().size();
        if native.rdi {
            let Some(builder) = self.room_deps(&crate::config::primary_server()) else {
                ui.set_action_error("请先登录".into());
                return;
            };
            let request = crate::play::room::SoloStart {
                version_id: native.version_id.clone(),
                window: (size.width, size.height),
                allow_duplicate,
            };
            let (tx, generation, version_id) = (self.tx.clone(), self.generation, native.version_id.clone());
            self.native_play = None;
            self.runtime.spawn(async move {
                let result = match tokio::task::spawn_blocking(builder).await {
                    Ok(deps) => crate::play::room::start_solo(&deps, request).await,
                    Err(error) => Err(anyhow::anyhow!("准备游玩失败: {error}")),
                };
                let _ = tx.send(super::Reply::SoloStarted(generation, version_id, result));
            });
            return;
        }
        self.start_local_with(ui, None, allow_duplicate);
    }

    /// Kotlin `LocalPlayDialog.confirm` for 正版: resolves the bound profile in the background;
    /// a device-code link comes back as `Reply::PlayLink`, the identity as `Reply::OnlineAccount`.
    fn login_online(&mut self, ui: &AppWindow, allow_duplicate: bool) {
        let (Some(api), Some(native)) = (self.api.as_ref(), self.native_play.as_ref()) else {
            ui.set_play_error("请先登录".into());
            return;
        };
        let Some(msid) = api.msid.clone() else {
            ui.set_play_error("请先在玩家信息里绑定微软账号".into());
            return;
        };
        let uid = match api.uid() {
            Ok(uid) => uid,
            Err(error) => {
                ui.set_play_error(error.to_string().into());
                return;
            }
        };
        ui.set_play_busy(true);
        ui.set_play_error("".into());
        ui.set_play_device_uri("".into());
        let resolver = crate::msa_play::Resolver::new(api.http.clone());
        let (tx, generation, version_id) = (self.tx.clone(), self.generation, native.version_id.clone());
        if let Some(job) = self.online_login.take() {
            job.abort();
        }
        self.online_login = Some(self.runtime.spawn(async move {
            let link_tx = tx.clone();
            let result = resolver
                .resolve(&uid, &msid, move |link| {
                    let _ = link_tx.send(super::Reply::PlayLink(generation, link));
                })
                .await;
            let _ = tx.send(super::Reply::OnlineAccount(generation, version_id, allow_duplicate, result));
        }));
    }

    /// The Microsoft sign-in page for the open dialog; Kotlin opens it at once.
    pub(super) fn play_link(&mut self, uri: &str) {
        if let Some(ui) = self.ui.upgrade() {
            ui.set_play_device_uri(uri.into());
        }
        self.open_url(uri);
    }

    /// The answer of `login_online`; ignored when the dialog was closed meanwhile.
    pub(super) fn online_resolved(&mut self, version_id: &str, allow_duplicate: bool, result: anyhow::Result<crate::mc_launch::Account>) {
        self.online_login = None;
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        if self.native_play.as_ref().map(|n| n.version_id.as_str()) != Some(version_id) {
            return;
        }
        ui.set_play_device_uri("".into());
        match result {
            Ok(account) => self.start_local_with(&ui, Some(account), allow_duplicate),
            Err(error) => {
                tracing::warn!(error = %format!("{error:#}"), version = version_id, "正版登录失败");
                ui.set_play_busy(false);
                ui.set_play_error(format!("正版登录失败：{error}").into());
            }
        }
    }

    /// Starts the open dialog's local pack offline (`online` is `None`) or as the given identity.
    fn start_local_with(&mut self, ui: &AppWindow, online: Option<crate::mc_launch::Account>, allow_duplicate: bool) {
        let Some(native) = &self.native_play else {
            return;
        };
        let Some(deps) = self.play_deps() else {
            ui.set_play_busy(false);
            ui.set_action_error("请先登录".into());
            return;
        };
        let size = ui.window().size();
        let request = crate::play::LocalStart {
            version_id: native.version_id.clone(),
            name: native.name.clone(),
            mc_version: native.mc_version.clone(),
            loader: native.loader.clone(),
            version_dir: native.dir.clone(),
            nickname: ui.get_play_nickname().to_string(),
            online,
            window: (size.width, size.height),
            allow_duplicate,
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
    }

    /// The answer of `start_solo`.
    pub(super) fn solo_started(&mut self, version_id: &str, result: anyhow::Result<crate::play::Admitted>) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        ui.set_play_busy(false);
        match result {
            Ok(crate::play::Admitted::Started(id)) => {
                self.session_id = id;
                self.render_console();
            }
            Ok(crate::play::Admitted::Duplicate) => {
                // The duplicate dialog's 继续 comes back as `play.duplicate` for this pack.
                if let Some(dir) = self.pack_dirs.get(version_id).cloned() {
                    self.native_play = Some(NativePlay {
                        version_id: version_id.into(),
                        name: String::new(),
                        mc_version: String::new(),
                        loader: String::new(),
                        dir,
                        rdi: true,
                    });
                }
                ui.set_duplicate_host(format!("pack:{version_id}").into());
            }
            Err(error) => {
                tracing::warn!(error = %format!("{error:#}"), version = version_id, "启动RDI整合包失败");
                ui.set_action_error(format!("启动游戏失败：{error}").into());
            }
        }
    }

    /// `play.cancel`: closes the dialog and stops a Microsoft sign-in still waiting.
    pub(super) fn cancel_native_play(&mut self) {
        if let Some(job) = self.online_login.take() {
            job.abort();
        }
        self.native_play = None;
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

    /// J5b: what a room start needs. The catalog opens its database, so the caller builds the
    /// deps off the UI thread. Also makes the last room game's end stop the room connection.
    pub fn room_deps(&self, primary: &str) -> Option<impl FnOnce() -> crate::play::room::RoomDeps + Send + 'static> {
        let api = self.api.as_ref()?;
        let (uid, account_name, is_dav) = (api.uid().ok()?, api.account_name.clone(), api.is_dav);
        let install = self.install_deps()?;
        let play = self.play_deps()?;
        let runtime = self.runtime.clone();
        self.play.set_rooms_idle(std::sync::Arc::new(move || {
            runtime.spawn(crate::route::link::stop());
        }));
        let endpoint = crate::route::Endpoint::new(primary);
        Some(move || crate::play::room::RoomDeps {
            play,
            install: install(),
            endpoint,
            runtime_dir: crate::play::runtime::default_dir(),
            tools: crate::paths::data_dir().join("tools"),
            account_name,
            uid,
            is_dav,
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

    /// `console.subscribe`/`console.select`/`console.clear` over the Rust sessions.
    pub(super) fn console_action(&mut self, action: &str, value: &str) {
        if action == "console.select" {
            self.session_id = value.into();
        }
        if !self.play.contains(&self.session_id)
            && let Some(last) = self.play.views().last()
        {
            self.session_id = last.id.clone();
        }
        if action == "console.clear" {
            self.play.clear(&self.session_id);
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
            && let Some(last) = rust.last()
        {
            self.session_id = last.id.clone();
        }
        let rows: Vec<(String, String)> = rust.iter().map(|s| (s.id.clone(), s.title.clone())).collect();
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
        let lines = if self.play.contains(&self.session_id) { self.play.lines(&self.session_id) } else { Vec::new() };
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
