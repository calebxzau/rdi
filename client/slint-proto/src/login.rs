//! Login UI state. Stored passwords never leave the Kotlin service.
use crate::{AppWindow, LoginAccount, api::Client, config::Config};
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
    version: String,
}
enum Event {
    Ready(Result<(Client, Accounts)>),
    Authenticated(Result<Client>),
}
pub struct LoginUi {
    ui: slint::Weak<AppWindow>,
    runtime: tokio::runtime::Handle,
    base: String,
    tx: mpsc::Sender<Event>,
    rx: mpsc::Receiver<Event>,
    client: Option<Client>,
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
            client: None,
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
        self.client = None;
        if let Some(ui) = self.ui.upgrade() {
            ui.set_login_visible(true);
            ui.set_login_busy(true);
            ui.set_login_password("".into());
            ui.set_login_saved_id("".into());
        }
        let tx = self.tx.clone();
        let base = self.base.clone();
        self.runtime.spawn(async move {
            let result = async {
                let client = tokio::task::spawn_blocking(move || Client::new(base)).await??;
                let accounts = serde_json::from_value(
                    client.call("login.accounts", serde_json::json!({})).await?,
                )?;
                Ok((client, accounts))
            }
            .await;
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
        let Some(client) = self.client.clone().filter(|c| !c.has_exited()) else {
            self.start();
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
        self.runtime.spawn(async move {
            let result = if saved.is_empty() {
                client.login(&account, &password).await
            } else {
                client.login_saved(&saved).await
            };
            let _ = tx.send(Event::Authenticated(result));
        });
    }
    pub fn tick(&mut self) -> Option<Client> {
        let ui = self.ui.upgrade()?;
        while let Ok(event) = self.rx.try_recv() {
            self.busy = false;
            ui.set_login_busy(false);
            match event {
                Event::Ready(Ok((client, accounts))) => {
                    ui.set_login_version(accounts.version.into());
                    self.client = Some(client);
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
                    self.client = None;
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
    fn saved_account_golden_response_is_usable_without_credentials() {
        let frames: Vec<serde_json::Value> =
            serde_json::from_str(include_str!("../protocol/fixtures/messages.json")).unwrap();
        let response = frames
            .iter()
            .find(|f| f["id"] == 101 && f.get("result").is_some())
            .unwrap();
        let accounts: Accounts = serde_json::from_value(response["result"].clone()).unwrap();
        assert_eq!(accounts.accounts.len(), 1);
        assert_eq!(accounts.last_id.as_deref(), Some("saved-example"));
        assert_eq!(accounts.auto_login_id, accounts.last_id);
        assert!(!response.to_string().contains("pwd"));
        assert!(!accounts.version.is_empty());
    }
}
