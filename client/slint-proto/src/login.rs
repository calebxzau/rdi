//! Login UI state. Rust owns saved credentials; saved passwords never reach Slint.
use crate::{
    AppWindow, LoginAccount,
    api::{Client, server::ServerApi},
    config::Config,
    credentials::Credentials,
    hwspec,
};
use anyhow::Result;
use serde::Deserialize;
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{cell::RefCell, rc::Rc, sync::mpsc};

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Account {
    id: String,
    account: String,
    name: String,
}
#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Accounts {
    accounts: Vec<Account>,
    last_id: Option<String>,
    auto_login_id: Option<String>,
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
    pub fn start(&mut self) {
        if self.busy {
            return;
        }
        self.busy = true;

        if let Some(ui) = self.ui.upgrade() {
            ui.set_login_visible(true);
            ui.set_login_busy(true);
            ui.set_login_password("".into());
            ui.set_login_saved_id("".into());
        }
        let tx = self.tx.clone();
        self.runtime.spawn(async move {
            let result = tokio::task::spawn_blocking(|| {
                let credentials = Credentials::load(&crate::credentials::file());
                let entries = credentials.sorted();
                let last_id = entries.first().map(|(id, _)| (*id).clone());
                Ok(Accounts {
                    accounts: entries
                        .into_iter()
                        .map(|(id, info)| Account {
                            id: id.clone(),
                            account: info.qq.clone(),
                            name: info.name.clone().unwrap_or_else(|| "RDI玩家".into()),
                        })
                        .collect(),
                    auto_login_id: if credentials.auto_login_disabled {
                        None
                    } else {
                        last_id.clone()
                    },
                    last_id,
                })
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
        let accounts = credentials
            .sorted()
            .into_iter()
            .map(|(id, info)| Account {
                id: id.clone(),
                account: info.qq.clone(),
                name: info.name.clone().unwrap_or_default(),
            })
            .collect::<Vec<_>>();
        assert_eq!(accounts[0].id, "0123456789abcdef01234567");
        assert_eq!(accounts.len(), 2);
    }
}
