//! Login UI state. Rust owns saved credentials; saved passwords never reach Slint.
use crate::{
    AppWindow, LoginAccount,
    api::{Client, server::ServerApi},
    config::Config,
    credentials::Credentials,
    hwspec,
};
use anyhow::Result;
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{cell::RefCell, rc::Rc, sync::mpsc};

#[derive(Clone)]
struct Account {
    id: String,
    account: String,
    name: String,
}
#[derive(Default)]
struct Accounts {
    accounts: Vec<Account>,
    last_id: Option<String>,
    auto_login_id: Option<String>,
    /// The player just logged out, so nothing may sign in by itself, even if saving that failed.
    signed_out: bool,
    notice: Option<String>,
}
impl Accounts {
    fn of(credentials: &Credentials, signed_out: bool) -> Self {
        let entries = credentials.sorted();
        let last_id = entries.first().map(|(id, _)| (*id).clone());
        Self {
            accounts: entries
                .into_iter()
                .map(|(id, info)| Account {
                    id: id.clone(),
                    account: info.qq.clone(),
                    name: info.name.clone().unwrap_or_else(|| "RDI玩家".into()),
                })
                .collect(),
            auto_login_id: if credentials.auto_login_disabled || signed_out {
                None
            } else {
                last_id.clone()
            },
            last_id,
            signed_out,
            notice: None,
        }
    }
}
enum Event {
    Ready(Result<Accounts>),
    Authenticated(Result<Client>),
}
pub struct LoginUi {
    ui: slint::Weak<AppWindow>,
    runtime: tokio::runtime::Handle,
    base: String,
    tx: mpsc::Sender<Event>,
    rx: mpsc::Receiver<Event>,

    accounts: Vec<Account>,
    model: Rc<VecModel<LoginAccount>>,
    busy: bool,
    authenticated: Option<Client>,
}
impl LoginUi {
    pub fn wire(
        ui: &AppWindow,
        runtime: tokio::runtime::Handle,
        base: String,
    ) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let model = Rc::new(VecModel::default());
        ui.set_login_accounts(ModelRc::from(model.clone()));
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            base,
            tx,
            rx,

            accounts: vec![],
            model,
            busy: false,
            authenticated: None,
        }));
        let weak = Rc::downgrade(&state);
        ui.on_login_submit(move || {
            if let Some(s) = weak.upgrade() {
                s.borrow_mut().submit();
            }
        });
        let weak = Rc::downgrade(&state);
        ui.on_login_select(move |id| {
            if let Some(s) = weak.upgrade() {
                s.borrow().select(id.as_str());
            }
        });
        state
    }
    fn show(&mut self) {
        self.busy = true;
        if let Some(ui) = self.ui.upgrade() {
            ui.set_login_visible(true);
            ui.set_login_busy(true);
            ui.set_login_password("".into());
            ui.set_login_saved_id("".into());
        }
    }
    pub fn start(&mut self) {
        if self.busy {
            return;
        }
        self.show();
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = tokio::task::spawn_blocking(|| {
                Ok(Accounts::of(
                    &Credentials::load(&crate::credentials::file()),
                    false,
                ))
            })
            .await
            .unwrap_or_else(|error| Err(error.into()));
            let _ = tx.send(Event::Ready(result));
        });
    }
    pub fn sign_out(&mut self, api: Client) {
        self.show();
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = tokio::task::spawn_blocking(move || {
                // Compose's sessions and Rust's own (J5c C2) are both forgotten.
                let clear_notice = api
                    .uid()
                    .and_then(|uid| {
                        crate::msa_sessions::clear(
                            &crate::paths::data_dir().join("msa_sessions.json"),
                            &uid,
                        )?;
                        crate::msa_play::clear(&crate::msa_play::path(), &uid)
                    })
                    .err()
                    .map(|error| {
                        tracing::error!(error=%format!("{error:#}"), "清除正版会话失败");
                        "清除正版登录状态失败，请稍后重试".to_owned()
                    });
                drop(api);
                let file = crate::credentials::file();
                let notice = Credentials::disable_auto_login(&file).err().map(|error| {
                    tracing::error!(error=%format!("{error:#}"), "保存退出状态失败");
                    "保存退出状态失败，下次启动可能会自动登录".to_owned()
                });
                let mut accounts = Accounts::of(&Credentials::load(&file), true);
                accounts.notice = clear_notice.or(notice);
                Ok(accounts)
            })
            .await
            .unwrap_or_else(|error| Err(error.into()));
            let _ = tx.send(Event::Ready(result));
        });
    }
    fn select(&self, id: &str) {
        if self.busy {
            return;
        }
        if let Some(account) = self.accounts.iter().find(|a| a.id == id)
            && let Some(ui) = self.ui.upgrade()
        {
            ui.set_login_account(account.account.clone().into());
            ui.set_login_password("".into());
            ui.set_login_saved_id(account.id.clone().into());
        }
    }
    fn submit(&mut self) {
        if self.busy {
            return;
        }
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        let saved = ui.get_login_saved_id().to_string();
        let account = ui.get_login_account().to_string();
        let password = ui.get_login_password().to_string();
        if saved.is_empty() && (account.trim().is_empty() || password.trim().is_empty()) {
            ui.set_action_error("未填写完整".into());
            return;
        }
        self.busy = true;
        ui.set_login_busy(true);
        let tx = self.tx.clone();
        let base = self.base.clone();
        let monitors = hwspec::monitors(&ui);
        self.runtime.spawn(async move {
            let result = async {
                let (account, password) = if saved.is_empty() {
                    (account, password)
                } else {
                    tokio::task::spawn_blocking(move || {
                        Credentials::saved(&crate::credentials::file(), &saved)
                    })
                    .await??
                };
                let mut server = ServerApi::new(base)?;
                let session = server
                    .login(&account, &password, hwspec::get(monitors).await)
                    .await?;
                let account = session.account.clone();
                tokio::task::spawn_blocking(move || {
                    Credentials::save_account(&crate::credentials::file(), &account)
                })
                .await??;
                Client::connect(server, session).await
            }
            .await;
            let _ = tx.send(Event::Authenticated(result));
        });
    }
    pub fn tick(&mut self) -> Option<Client> {
        let ui = self.ui.upgrade()?;
        while let Ok(event) = self.rx.try_recv() {
            self.busy = false;
            ui.set_login_busy(false);
            match event {
                Event::Ready(Ok(accounts)) => {
                    ui.set_login_version(env!("RDI_APP_VERSION").into());
                    self.model.set_vec(
                        accounts
                            .accounts
                            .iter()
                            .map(|a| LoginAccount {
                                id: a.id.clone().into(),
                                label: format!("{} ({})", a.name, a.account).into(),
                            })
                            .collect::<Vec<_>>(),
                    );
                    self.accounts = accounts.accounts;
                    if let Some(id) = accounts.last_id {
                        self.select(&id);
                    }
                    if let Some(notice) = accounts.notice {
                        ui.set_action_error(notice.into());
                    }
                    if accounts.signed_out {
                        continue;
                    }
                    if let Ok((account, password)) = Config::credentials() {
                        ui.set_login_account(account.into());
                        ui.set_login_password(password.into());
                        ui.set_login_saved_id("".into());
                        self.submit();
                    } else if let Some(id) = accounts.auto_login_id {
                        self.select(&id);
                        self.submit();
                    }
                }
                Event::Authenticated(Ok(client)) => {
                    ui.set_login_password("".into());
                    ui.set_login_saved_id("".into());
                    ui.set_login_visible(false);

                    self.authenticated = Some(client);
                }
                Event::Ready(Err(error)) | Event::Authenticated(Err(error)) => {
                    ui.set_login_visible(true);
                    ui.set_action_error(format!("{error:#}").into());
                }
            }
        }
        self.authenticated.take()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn history_metadata_contains_no_passwords() {
        let credentials: Credentials =
            serde_json::from_str(include_str!("../protocol/fixtures/local_credentials.json"))
                .unwrap();
        let accounts = Accounts::of(&credentials, false).accounts;
        assert_eq!(accounts[0].id, "0123456789abcdef01234567");
        assert_eq!(accounts.len(), 2);
    }
    #[test]
    fn logging_out_never_signs_in_by_itself() {
        let mut credentials: Credentials =
            serde_json::from_str(include_str!("../protocol/fixtures/local_credentials.json"))
                .unwrap();
        credentials.auto_login_disabled = false;
        assert!(Accounts::of(&credentials, false).auto_login_id.is_some());
        let signed_out = Accounts::of(&credentials, true);
        assert!(signed_out.auto_login_id.is_none() && signed_out.signed_out);
        assert_eq!(
            signed_out.last_id.as_deref(),
            Some("0123456789abcdef01234567")
        );
        credentials.auto_login_disabled = true;
        assert!(Accounts::of(&credentials, false).auto_login_id.is_none());
    }
}
