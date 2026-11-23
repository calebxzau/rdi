//! Wiring of 注册, 忘记密码 and 个人信息 (U4 batch b1) to the `Account` Slint global. Shaped like
//! `rooms_ui.rs`: one `action(name, value)` callback, an epoch for stale replies, and a channel
//! drained on the UI thread. The logic itself is in `account/` and has no Slint types.
use crate::{
    Account, AppWindow,
    account::{
        mail_op,
        msa::{self, MsaProfile},
        requests, validate,
    },
    api::{Client, server::ServerApi},
};
use anyhow::Result;
use serde_json::Value;
use slint::ComponentHandle;
use std::{cell::RefCell, rc::Rc, sync::mpsc};
use tokio::{runtime::Handle, task::JoinHandle};

/// What a finished Microsoft sign-in is for.
#[derive(Clone, Copy, PartialEq)]
enum Purpose {
    Register,
    Reset,
    Bind,
    /// Not a sign-in: the refresh after 修改信息.
    Profile,
}

enum Reply {
    Link(u64, String),
    Msa(u64, Purpose, Result<MsaProfile>),
    Registered(u64, Result<()>),
    Reset(u64, Result<()>),
    Receipt(u64, Result<String>),
    /// The account the server sent after a profile change or a binding.
    Refreshed(u64, Purpose, Result<Value>),
    /// The saved QQ number of an account id.
    Qq(String, String),
}

/// The signed-in player as 个人信息 shows it.
#[derive(Clone, Default)]
struct Me {
    id: String,
    name: String,
    qq: String,
    bound: bool,
}

pub struct AccountUi {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    base: String,
    api: Option<Client>,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    /// Raised whenever a page closes, so late replies are dropped.
    epoch: u64,
    receipt_epoch: u64,
    msa_job: Option<JoinHandle<()>>,
    msa: Option<MsaProfile>,
    /// 「注册」 or 「重置密码」: the title of the receipt result.
    operation: &'static str,
    me: Me,
    last_screen: i32,
    /// Navigation runs from `tick`: the shell may call `dismiss` while it holds its own state.
    pending_nav: Vec<&'static str>,
}

fn account(ui: &AppWindow) -> Account<'_> {
    ui.global::<Account>()
}

fn text(error: &anyhow::Error) -> String {
    format!("{error:#}")
}

impl AccountUi {
    pub fn wire(ui: &AppWindow, runtime: Handle, base: String) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            base,
            api: None,
            tx,
            rx,
            epoch: 0,
            receipt_epoch: 0,
            msa_job: None,
            msa: None,
            operation: "注册",
            me: Me::default(),
            last_screen: 0,
            pending_nav: Vec::new(),
        }));
        let weak = Rc::downgrade(&state);
        account(ui).on_action(move |name, value| {
            if let Some(state) = weak.upgrade()
                && let Ok(mut state) = state.try_borrow_mut()
            {
                state.action(name.as_str(), value.as_str());
            }
        });
        state
    }

    /// A new session, or none after logout.
    pub fn connect(&mut self, api: Option<Client>) {
        self.stop();
        self.me = Me::default();
        if let Some(api) = &api {
            self.me = Me {
                id: api.uid().unwrap_or_default(),
                name: api.account_name.clone(),
                qq: String::new(),
                bound: api.has_msid,
            };
            let (tx, id) = (self.tx.clone(), self.me.id.clone());
            self.runtime.spawn(async move {
                let key = id.clone();
                let qq = tokio::task::spawn_blocking(move || {
                    let credentials = crate::credentials::Credentials::load(&crate::credentials::file());
                    credentials.login_infos.get(&id).map(|info| info.qq.clone()).unwrap_or_default()
                })
                .await
                .unwrap_or_default();
                let _ = tx.send(Reply::Qq(key, qq));
            });
        }
        self.api = api;
        if let Some(ui) = self.ui.upgrade() {
            self.clear(&ui);
            account(&ui).set_page(0);
        }
    }

    /// Cancels a Microsoft sign-in and drops every reply still on its way.
    fn stop(&mut self) {
        self.epoch += 1;
        self.receipt_epoch += 1;
        if let Some(job) = self.msa_job.take() {
            job.abort();
        }
        self.msa = None;
    }

    fn clear(&self, ui: &AppWindow) {
        let g = account(ui);
        g.set_busy(false);
        g.set_error("".into());
        g.set_msa_state(0);
        g.set_msa_link("".into());
        g.set_msa_line("".into());
        g.set_name("".into());
        g.set_qq("".into());
        g.set_pwd("".into());
        g.set_pwd2("".into());
        g.set_reset_mode(0);
        g.set_guide_open(false);
        g.set_guide_code("".into());
        g.set_receipt_open(false);
        g.set_receipt_busy(false);
        g.set_receipt_error("".into());
        g.set_result_open(false);
        g.set_reset_done(false);
        g.set_profile_open(false);
        g.set_profile_busy(false);
        g.set_profile_pwd("".into());
    }

    fn show_me(&self, ui: &AppWindow) {
        let g = account(ui);
        g.set_me_name(self.me.name.clone().into());
        g.set_me_qq(self.me.qq.clone().into());
        g.set_me_id(self.me.id.clone().into());
        g.set_me_bound(self.me.bound);
        g.set_me_head(ui.get_player_head());
    }

    fn action(&mut self, name: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        let g = account(&ui);
        match name {
            "open-register" => {
                self.stop();
                self.clear(&ui);
                self.operation = "注册";
                if value == "msa" {
                    g.set_page(1);
                    self.start_msa(&ui, Purpose::Register);
                } else {
                    g.set_page(2);
                }
            }
            "open-reset" => {
                self.stop();
                self.clear(&ui);
                self.operation = "重置密码";
                g.set_page(3);
            }
            "info-open" => {
                self.stop();
                self.clear(&ui);
                self.show_me(&ui);
            }
            "back-nav" => {
                if g.get_page() != 0 {
                    self.close_page(&ui);
                } else if g.get_screen() == 1 {
                    self.stop();
                    self.pending_nav.push("back");
                }
            }
            "msa-start" => {
                g.set_error("".into());
                self.start_msa(&ui, Purpose::Register);
            }
            "reset-mode" => {
                // Leaving the Microsoft tab abandons a sign-in that is still waiting.
                self.stop();
                g.set_busy(false);
                g.set_msa_state(0);
                g.set_msa_link("".into());
                g.set_error("".into());
                g.set_reset_mode(if value == "1" { 1 } else { 0 });
            }
            "register-submit" => self.submit_register(&ui),
            "reset-submit" => self.submit_reset(&ui),
            "copied" => ui.invoke_notify(format!("{value}已复制到剪贴板").into()),
            "open-mail" => open(mail_op::QQ_MAIL_URL),
            "guide-close" => g.set_guide_open(false),
            "receipt-open" => {
                g.set_receipt_title("".into());
                g.set_receipt_error("".into());
                g.set_receipt_open(true);
            }
            "receipt-cancel" => {
                if !g.get_receipt_busy() {
                    g.set_receipt_open(false);
                }
            }
            "receipt-submit" => self.query_receipt(&ui),
            "result-close" => {
                g.set_result_open(false);
                // ReceiptQueryDialogs `onResultClosed`: a finished reset leads to sign-in.
                if g.get_page() == 3 && g.get_result_text().contains("密码重置完成") {
                    g.set_reset_done(true);
                }
            }
            "reset-done-close" => {
                g.set_reset_done(false);
                self.close_page(&ui);
            }
            "dismiss" => {
                if g.get_result_open() {
                    self.action("result-close", "");
                } else if g.get_reset_done() {
                    self.action("reset-done-close", "");
                } else if g.get_receipt_open() {
                    self.action("receipt-cancel", "");
                } else if g.get_profile_open() {
                    self.action("profile-cancel", "");
                } else {
                    g.set_guide_open(false);
                }
            }
            "bind-start" => {
                g.set_error("".into());
                self.start_msa(&ui, Purpose::Bind);
            }
            "bind-confirm" => self.confirm_bind(&ui),
            "profile-open" => {
                g.set_profile_name(self.me.name.clone().into());
                g.set_profile_pwd("".into());
                g.set_profile_error("".into());
                g.set_profile_open(true);
            }
            "profile-cancel" => {
                if !g.get_profile_busy() {
                    g.set_profile_open(false);
                }
            }
            "profile-submit" => self.submit_profile(&ui),
            _ => tracing::warn!(name, "未知的账号操作"),
        }
    }

    /// Back to the login panel.
    fn close_page(&mut self, ui: &AppWindow) {
        self.stop();
        self.clear(ui);
        account(ui).set_page(0);
    }

    fn start_msa(&mut self, ui: &AppWindow, purpose: Purpose) {
        if let Some(job) = self.msa_job.take() {
            job.abort();
        }
        self.msa = None;
        let g = account(ui);
        g.set_msa_state(1);
        g.set_msa_link("".into());
        // Only 重置密码 waits behind its button; 注册 and 绑定 show the link and stay usable.
        g.set_busy(purpose == Purpose::Reset);
        let Ok(http) = ServerApi::new(self.base.clone()).map(|s| s.http) else {
            g.set_msa_state(0);
            g.set_busy(false);
            g.set_error("创建网络连接失败".into());
            return;
        };
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.msa_job = Some(self.runtime.spawn(async move {
            let link_tx = tx.clone();
            let result = msa::sign_in(&http, move |link| {
                let _ = link_tx.send(Reply::Link(epoch, link));
            })
            .await;
            let _ = tx.send(Reply::Msa(epoch, purpose, result));
        }));
    }

    fn submit_register(&mut self, ui: &AppWindow) {
        let g = account(ui);
        if g.get_busy() {
            return;
        }
        let (name, qq, pwd, pwd2) = (g.get_name().to_string(), g.get_qq().to_string(), g.get_pwd().to_string(), g.get_pwd2().to_string());
        if let Err(error) = validate::register(&name, &qq, &pwd, &pwd2) {
            g.set_error(error.into());
            return;
        }
        g.set_error("".into());
        if g.get_page() == 2 {
            // 注册 with QQ mail: the player mails the code; nothing is sent from here.
            match mail_op::register_code(&name, &qq, &pwd) {
                Ok(code) => self.show_guide(ui, &qq, code),
                Err(error) => g.set_error(text(&error).into()),
            }
            return;
        }
        let Some(msa) = self.msa.clone() else {
            g.set_error("请先登录微软账号".into());
            return;
        };
        g.set_busy(true);
        let (tx, epoch, base) = (self.tx.clone(), self.epoch, self.base.clone());
        self.runtime.spawn(async move {
            let result = async { requests::register(&ServerApi::new(base)?, &name, &qq, &pwd, &msa).await }.await;
            let _ = tx.send(Reply::Registered(epoch, result));
        });
    }

    fn submit_reset(&mut self, ui: &AppWindow) {
        let g = account(ui);
        if g.get_busy() {
            return;
        }
        let (pwd, pwd2) = (g.get_pwd().to_string(), g.get_pwd2().to_string());
        if let Err(error) = validate::new_password(&pwd, &pwd2) {
            g.set_error(error.into());
            return;
        }
        if g.get_reset_mode() == 0 {
            g.set_error("".into());
            self.start_msa(ui, Purpose::Reset);
            return;
        }
        match validate::qq(&g.get_qq()) {
            Ok(qq) => {
                g.set_error("".into());
                match mail_op::reset_code(&qq, &pwd) {
                    Ok(code) => self.show_guide(ui, &qq, code),
                    Err(error) => g.set_error(text(&error).into()),
                }
            }
            Err(error) => g.set_error(error.into()),
        }
    }

    fn show_guide(&self, ui: &AppWindow, qq: &str, code: String) {
        let g = account(ui);
        g.set_guide_title(mail_op::new_title().into());
        g.set_guide_code(code.into());
        g.set_guide_qq(qq.trim().into());
        g.set_guide_open(true);
    }

    fn query_receipt(&mut self, ui: &AppWindow) {
        let g = account(ui);
        if g.get_receipt_busy() {
            return;
        }
        let id = match mail_op::receipt_id(&g.get_receipt_title()) {
            Ok(id) => id,
            Err(error) => {
                g.set_receipt_error(error.into());
                return;
            }
        };
        g.set_receipt_busy(true);
        g.set_receipt_error("".into());
        self.receipt_epoch += 1;
        let (tx, epoch, base) = (self.tx.clone(), self.receipt_epoch, self.base.clone());
        self.runtime.spawn(async move {
            let result = async { requests::receipt(&ServerApi::new(base)?, &id).await }.await;
            let _ = tx.send(Reply::Receipt(epoch, result));
        });
    }

    fn confirm_bind(&mut self, ui: &AppWindow) {
        let g = account(ui);
        let (Some(api), Some(msa)) = (self.api.clone(), self.msa.clone()) else { return };
        if g.get_busy() {
            return;
        }
        g.set_busy(true);
        g.set_error("".into());
        let (tx, epoch, base, uid) = (self.tx.clone(), self.epoch, self.base.clone(), self.me.id.clone());
        self.runtime.spawn(async move {
            let result = async {
                requests::bind(&api, &msa).await.map_err(|e| anyhow::anyhow!("绑定失败：{e:#}，请重试"))?;
                let pwd = tokio::task::spawn_blocking(move || requests::saved_password(&uid)).await??;
                let uid = api.uid()?;
                requests::refresh_saved(&base, &uid, &pwd).await.map_err(|e| anyhow::anyhow!("重新登录失败：{e:#}"))
            }
            .await;
            let _ = tx.send(Reply::Refreshed(epoch, Purpose::Bind, result));
        });
    }

    fn submit_profile(&mut self, ui: &AppWindow) {
        let g = account(ui);
        let Some(api) = self.api.clone() else { return };
        if g.get_profile_busy() {
            return;
        }
        let (name, pwd) = (g.get_profile_name().to_string(), g.get_profile_pwd().to_string());
        if let Err(error) = validate::profile_change(&name, &pwd, &self.me.name) {
            g.set_profile_error(error.into());
            return;
        }
        g.set_profile_busy(true);
        g.set_profile_error("".into());
        let (tx, epoch, base, uid, current) = (self.tx.clone(), self.epoch, self.base.clone(), self.me.id.clone(), self.me.name.clone());
        self.runtime.spawn(async move {
            let result = async {
                let saved = tokio::task::spawn_blocking(move || requests::saved_password(&uid)).await??;
                let name_change = (name != current).then_some(name.as_str());
                let pwd_change = (!pwd.is_empty() && pwd != saved).then_some(pwd.as_str());
                requests::change_profile(&api, name_change, pwd_change).await?;
                let login_pwd = if pwd.is_empty() { saved } else { pwd.clone() };
                requests::refresh_saved(&base, &api.uid()?, &login_pwd).await
            }
            .await;
            let _ = tx.send(Reply::Refreshed(epoch, Purpose::Profile, result.map_err(|e| anyhow::anyhow!("修改失败:{e:#}"))));
        });
    }

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        for action in std::mem::take(&mut self.pending_nav) {
            ui.invoke_navigate(action.into());
        }
        let screen = account(&ui).get_screen();
        if screen != self.last_screen {
            // Leaving 个人信息 abandons a binding sign-in that is still waiting.
            if self.last_screen == 1 {
                self.stop();
                self.clear(&ui);
            }
            self.last_screen = screen;
        }
        while let Ok(reply) = self.rx.try_recv() {
            self.reply(&ui, reply);
        }
    }

    fn reply(&mut self, ui: &AppWindow, reply: Reply) {
        let g = account(ui);
        match reply {
            Reply::Link(epoch, link) if epoch == self.epoch => {
                g.set_msa_link(link.clone().into());
                open(&link);
            }
            Reply::Msa(epoch, purpose, result) if epoch == self.epoch => {
                self.msa_job = None;
                match result {
                    Ok(profile) => {
                        g.set_msa_link("".into());
                        if purpose == Purpose::Reset {
                            self.reset_by_msa(ui, profile);
                            return;
                        }
                        g.set_msa_state(2);
                        g.set_msa_line(match purpose {
                            Purpose::Bind => format!("读取信息成功！昵称：{}，MSID:{}", profile.name, profile.uuid),
                            _ => format!("登录成功！{} · MSID {}", profile.name, profile.uuid),
                        }.into());
                        if purpose == Purpose::Register {
                            g.set_name(profile.name.clone().into());
                        }
                        self.msa = Some(profile);
                    }
                    Err(error) => {
                        g.set_busy(false);
                        g.set_msa_state(0);
                        g.set_error(format!("登录微软MC失败：{}", text(&error)).into());
                    }
                }
            }
            Reply::Registered(epoch, result) if epoch == self.epoch => {
                g.set_busy(false);
                match result {
                    Ok(()) => {
                        self.close_page(ui);
                        ui.invoke_notify("注册成功，请登录".into());
                    }
                    Err(error) => g.set_error(text(&error).into()),
                }
            }
            Reply::Reset(epoch, result) if epoch == self.epoch => {
                g.set_busy(false);
                g.set_msa_state(0);
                match result {
                    Ok(()) => g.set_reset_done(true),
                    Err(error) => g.set_error(text(&error).into()),
                }
            }
            Reply::Receipt(epoch, result) if epoch == self.receipt_epoch => {
                g.set_receipt_busy(false);
                match result {
                    Ok(message) => {
                        g.set_receipt_open(false);
                        g.set_result_title(format!("{}进度", self.operation).into());
                        g.set_result_text(message.into());
                        g.set_result_open(true);
                    }
                    Err(error) => g.set_receipt_error(text(&error).into()),
                }
            }
            Reply::Refreshed(epoch, purpose, result) if epoch == self.epoch => {
                g.set_busy(false);
                g.set_profile_busy(false);
                match result {
                    Ok(saved) => {
                        self.me.name = saved["name"].as_str().unwrap_or(&self.me.name).to_owned();
                        self.me.qq = saved["qq"].as_str().unwrap_or(&self.me.qq).to_owned();
                        self.me.bound = saved["msid"].as_str().is_some_and(|m| !m.trim().is_empty()) || purpose == Purpose::Bind;
                        ui.set_player_name(self.me.name.clone().into());
                        self.show_me(ui);
                        if purpose == Purpose::Bind {
                            self.msa = None;
                            g.set_msa_state(0);
                            ui.invoke_notify("微软账号绑定成功".into());
                        } else {
                            g.set_profile_open(false);
                            ui.invoke_notify("修改成功".into());
                        }
                    }
                    Err(error) if purpose == Purpose::Bind => g.set_error(text(&error).into()),
                    Err(error) => g.set_profile_error(text(&error).into()),
                }
            }
            Reply::Qq(id, qq) if id == self.me.id => {
                self.me.qq = qq;
                if account(ui).get_screen() == 1 {
                    self.show_me(ui);
                }
            }
            _ => {}
        }
    }

    fn reset_by_msa(&mut self, ui: &AppWindow, profile: MsaProfile) {
        let g = account(ui);
        g.set_busy(true);
        let pwd = g.get_pwd().to_string();
        let (tx, epoch, base) = (self.tx.clone(), self.epoch, self.base.clone());
        self.runtime.spawn(async move {
            let result = async { requests::reset_by_msa(&ServerApi::new(base)?, &profile, &pwd).await }.await;
            let _ = tx.send(Reply::Reset(epoch, result));
        });
    }
}

fn open(url: &str) {
    if let Err(error) = webbrowser::open(url) {
        tracing::warn!(%error, "打开浏览器失败");
    }
}
