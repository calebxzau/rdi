//! Wiring of 设置 (U4 batch b2) to the `Settings` Slint global, shaped like `account_ui.rs`.
use crate::{
    AppWindow, Settings,
    api::Client,
    settings::{self, Draft, InvalidPack, Quota},
    tasks::Manager,
};
use anyhow::Result;
use slint::ComponentHandle;
use std::{cell::RefCell, rc::Rc, sync::mpsc};
use tokio::runtime::Handle;

enum Reply {
    Loaded(u64, Draft, i32),
    Saved(u64, Result<()>, bool),
    Quota(u64, Result<Quota>),
    Node(u64, Result<String>),
    Invalid(u64, Result<Vec<InvalidPack>>),
    Cleaned(u64, usize, Vec<String>),
}

pub struct SettingsUi {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    api: Option<Client>,
    tasks: Manager,
    play: crate::play::Sessions,
    primary: String,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    epoch: u64,
    loaded: Option<Draft>,
    total_memory: i32,
    invalid: Vec<InvalidPack>,
    last_screen: i32,
    pending_nav: Vec<&'static str>,
}

fn global(ui: &AppWindow) -> Settings<'_> {
    ui.global::<Settings>()
}

impl SettingsUi {
    pub fn wire(ui: &AppWindow, runtime: Handle, tasks: Manager, play: crate::play::Sessions, primary: String) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            api: None,
            tasks,
            play,
            primary,
            tx,
            rx,
            epoch: 0,
            loaded: None,
            total_memory: 0,
            invalid: Vec::new(),
            last_screen: 0,
            pending_nav: Vec::new(),
        }));
        let weak = Rc::downgrade(&state);
        global(ui).on_action(move |name, value| {
            if let Some(state) = weak.upgrade()
                && let Ok(mut state) = state.try_borrow_mut()
            {
                state.action(name.as_str(), value.as_str());
            }
        });
        state
    }

    pub fn connect(&mut self, api: Option<Client>) {
        self.epoch += 1;
        if api.is_none() {
            // A temporary node choice belongs to this sign-in.
            crate::route::set_manual(false, false);
        }
        self.api = api;
        self.invalid.clear();
        if let Some(ui) = self.ui.upgrade() {
            let g = global(&ui);
            g.set_cache_confirm(false);
            g.set_invalid_count(0);
            g.set_invalid_message("".into());
            g.set_invalid_busy(false);
            g.set_switching(false);
            g.set_saving(false);
        }
    }

    fn draft(ui: &AppWindow) -> Draft {
        let g = global(ui);
        Draft {
            prefer_mod_mirror: g.get_prefer_mod_mirror(),
            prefer_mc_mirror: g.get_prefer_mc_mirror(),
            max_memory_text: g.get_max_memory().trim().to_owned(),
            proxy_enabled: g.get_proxy_mode() != 0,
            proxy_system: g.get_proxy_mode() == 1,
            proxy_host: g.get_proxy_host().to_string(),
            proxy_port_text: g.get_proxy_port().to_string(),
            proxy_usr: g.get_proxy_usr().to_string(),
            proxy_pwd: g.get_proxy_pwd().to_string(),
            solid_window: g.get_solid_window(),
        }
    }

    fn show(ui: &AppWindow, d: &Draft) {
        let g = global(ui);
        g.set_prefer_mod_mirror(d.prefer_mod_mirror);
        g.set_prefer_mc_mirror(d.prefer_mc_mirror);
        g.set_max_memory(d.max_memory_text.clone().into());
        g.set_proxy_mode(if !d.proxy_enabled { 0 } else if d.proxy_system { 1 } else { 2 });
        g.set_proxy_host(d.proxy_host.clone().into());
        g.set_proxy_port(d.proxy_port_text.clone().into());
        g.set_proxy_usr(d.proxy_usr.clone().into());
        g.set_proxy_pwd(d.proxy_pwd.clone().into());
        g.set_solid_window(d.solid_window);
    }

    fn show_route(ui: &AppWindow) {
        global(ui).set_route_line(settings::route_line(&crate::route::state()).into());
    }

    fn refresh_quota(&mut self, ui: &AppWindow) {
        let Some(api) = self.api.clone() else { return };
        let g = global(ui);
        if g.get_quota_loading() {
            return;
        }
        g.set_quota_loading(true);
        if g.get_quota_text().is_empty() {
            g.set_quota_text("读取中".into());
        }
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::Quota(epoch, settings::quota(&api).await));
        });
    }

    fn action(&mut self, name: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        let g = global(&ui);
        match name {
            "open-page" => {
                self.epoch += 1;
                g.set_loading(true);
                g.set_error("".into());
                g.set_category(0);
                g.set_quota_text("".into());
                g.set_quota_failed(false);
                g.set_quota_loading(false);
                Self::show_route(&ui);
                let (tx, epoch) = (self.tx.clone(), self.epoch);
                self.runtime.spawn(async move {
                    let loaded = tokio::task::spawn_blocking(|| (settings::load(&settings::config_path()), crate::pack_options::total_memory_mb())).await;
                    if let Ok((draft, total)) = loaded {
                        let _ = tx.send(Reply::Loaded(epoch, draft, total));
                    }
                });
                self.refresh_quota(&ui);
            }
            "back-nav" => self.pending_nav.push("back"),
            "save" => {
                if g.get_loading() || g.get_saving() {
                    return;
                }
                let draft = Self::draft(&ui);
                if let Err(error) = settings::validate(&draft, self.total_memory) {
                    g.set_error(error.into());
                    return;
                }
                let proxy_changed = self.loaded.as_ref().is_none_or(|l| {
                    (l.proxy_enabled, l.proxy_system, &l.proxy_host, &l.proxy_port_text, &l.proxy_usr, &l.proxy_pwd)
                        != (draft.proxy_enabled, draft.proxy_system, &draft.proxy_host, &draft.proxy_port_text, &draft.proxy_usr, &draft.proxy_pwd)
                });
                g.set_saving(true);
                g.set_error("".into());
                self.loaded = Some(draft.clone());
                let (tx, epoch) = (self.tx.clone(), self.epoch);
                self.runtime.spawn(async move {
                    let result = tokio::task::spawn_blocking(move || settings::save(&settings::config_path(), &draft))
                        .await
                        .unwrap_or_else(|e| Err(e.into()));
                    let _ = tx.send(Reply::Saved(epoch, result, proxy_changed));
                });
            }
            "quota-refresh" => self.refresh_quota(&ui),
            "node" => {
                let Some(api) = self.api.clone() else { return };
                if g.get_switching() {
                    return;
                }
                g.set_switching(true);
                crate::route::set_manual(value == "backup", value == "main");
                let (tx, epoch, endpoint) = (self.tx.clone(), self.epoch, crate::route::Endpoint::new(&self.primary));
                self.runtime.spawn(async move {
                    let result = crate::route::refresh(api.server(), &endpoint)
                        .await
                        .map(|entry| entry.node_name)
                        .map_err(|e| anyhow::anyhow!("备用节点刷新失败：{e:#}"));
                    let _ = tx.send(Reply::Node(epoch, result));
                });
            }
            "cache-clear" => {
                g.set_cache_confirm(false);
                // Compose skips leased entries; here nothing else may run, so nothing holds one.
                if self.tasks.running() {
                    ui.invoke_notify("有任务正在进行，完成后再清除下载缓存".into());
                    return;
                }
                let data = crate::paths::data_dir();
                let versions = crate::local_packs::versions_dir();
                self.tasks.submit(&self.runtime, "清除下载缓存".into(), "client-download-cache-cleanup".into(), move |ctx| {
                    let message = settings::clean_cache(&ctx, &data.join("dlc"), &versions)?;
                    ctx.finish(message);
                    Ok(())
                });
                ui.invoke_notify("清除下载缓存任务已提交，可在任务中查看进度".into());
            }
            "invalid-check" => {
                let Some(api) = self.api.clone() else { return };
                if g.get_invalid_busy() {
                    return;
                }
                g.set_invalid_busy(true);
                let (tx, epoch) = (self.tx.clone(), self.epoch);
                self.runtime.spawn(async move {
                    let result = settings::invalid_packs(&api, crate::local_packs::versions_dir()).await;
                    let _ = tx.send(Reply::Invalid(epoch, result));
                });
            }
            "invalid-cancel" => {
                self.invalid.clear();
                g.set_invalid_count(0);
            }
            "invalid-clean" => {
                let packs = std::mem::take(&mut self.invalid);
                g.set_invalid_count(0);
                if packs.is_empty() {
                    return;
                }
                g.set_invalid_busy(true);
                let (tx, epoch, play) = (self.tx.clone(), self.epoch, self.play.clone());
                self.runtime.spawn(async move {
                    let result = tokio::task::spawn_blocking(move || {
                        let (mut done, mut failures) = (0, Vec::new());
                        for pack in &packs {
                            let result = if play.alive_count(&pack.version_id) > 0 {
                                Err(anyhow::anyhow!("整合包正在运行，不能删除"))
                            } else {
                                settings::trash_pack(pack)
                            };
                            match result {
                                Ok(()) => done += 1,
                                Err(error) => {
                                    tracing::warn!(version = %pack.version_id, error = %format!("{error:#}"), "清理无效整合包失败");
                                    failures.push(format!("{}: {error:#}", pack.version_id));
                                }
                            }
                        }
                        (done, failures)
                    })
                    .await;
                    let (done, failures) = result.unwrap_or_else(|e| (0, vec![format!("{e}")]));
                    let _ = tx.send(Reply::Cleaned(epoch, done, failures));
                });
            }
            "dismiss" => {
                if g.get_invalid_message() != "" {
                    g.set_invalid_message("".into());
                } else if g.get_invalid_count() > 0 {
                    self.action("invalid-cancel", "");
                } else {
                    g.set_cache_confirm(false);
                }
            }
            _ => tracing::warn!(name, "未知的设置操作"),
        }
    }

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        for action in std::mem::take(&mut self.pending_nav) {
            ui.invoke_navigate(action.into());
        }
        let screen = global(&ui).get_screen();
        if screen != self.last_screen {
            self.last_screen = screen;
        }
        while let Ok(reply) = self.rx.try_recv() {
            let g = global(&ui);
            match reply {
                Reply::Loaded(epoch, draft, total) if epoch == self.epoch => {
                    g.set_loading(false);
                    self.total_memory = total;
                    g.set_total_memory(total);
                    Self::show(&ui, &draft);
                    self.loaded = Some(draft);
                }
                Reply::Saved(epoch, result, proxy_changed) if epoch == self.epoch => {
                    g.set_saving(false);
                    match result {
                        Ok(()) => ui.invoke_notify(if proxy_changed { "设置已保存，代理设置重新登录后生效" } else { "设置已保存" }.into()),
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "保存设置失败");
                            g.set_error(format!("保存失败: {error:#}").into());
                        }
                    }
                }
                Reply::Quota(epoch, result) if epoch == self.epoch => {
                    g.set_quota_loading(false);
                    match result {
                        Ok(quota) => {
                            g.set_quota_failed(false);
                            g.set_quota_text(quota.text().into());
                        }
                        Err(error) => {
                            tracing::warn!(error = %format!("{error:#}"), "下载额度读取失败");
                            // A quota already shown stays; only a first failure says so.
                            if g.get_quota_text() == "读取中" || g.get_quota_failed() {
                                g.set_quota_failed(true);
                                g.set_quota_text("读取失败".into());
                            }
                        }
                    }
                }
                Reply::Node(epoch, result) if epoch == self.epoch => {
                    g.set_switching(false);
                    Self::show_route(&ui);
                    match result {
                        Ok(name) => ui.invoke_notify(format!("已切换到{name}，下次进入房间时使用").into()),
                        Err(error) => ui.invoke_notify(format!("{error:#}").into()),
                    }
                }
                Reply::Invalid(epoch, result) if epoch == self.epoch => {
                    g.set_invalid_busy(false);
                    match result {
                        Ok(packs) if packs.is_empty() => {
                            g.set_invalid_failed(false);
                            g.set_invalid_message("没有无效整合包".into());
                        }
                        Ok(packs) => {
                            g.set_invalid_count(packs.len() as i32);
                            self.invalid = packs;
                        }
                        Err(error) => {
                            g.set_invalid_failed(true);
                            g.set_invalid_message(format!("检查无效整合包失败：{error:#}").into());
                        }
                    }
                }
                Reply::Cleaned(epoch, done, failures) if epoch == self.epoch => {
                    g.set_invalid_busy(false);
                    g.set_invalid_failed(!failures.is_empty());
                    g.set_invalid_message(if failures.is_empty() {
                        format!("已清理{done}个无效整合包")
                    } else {
                        format!("清理无效整合包完成：成功{done}个，失败{}个（{}）", failures.len(), failures.join("；"))
                    }.into());
                    if done > 0 {
                        ui.invoke_pack_action("packs.refresh".into(), "".into());
                    }
                }
                _ => {}
            }
        }
    }
}
