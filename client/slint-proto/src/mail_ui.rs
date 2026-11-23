//! Wiring of 信箱 (U4 batch b2) to the `Inbox` Slint global, shaped like `account_ui.rs`.
use crate::{
    AppWindow, Inbox, MailRow,
    api::Client,
    mail::{self, Brief},
};
use anyhow::Result;
use slint::{ComponentHandle, Model, VecModel};
use std::{cell::RefCell, collections::HashSet, rc::Rc, sync::mpsc};
use tokio::runtime::Handle;

enum Reply {
    List(u64, Result<Vec<Brief>>),
    Detail(u64, Result<mail::Mail>),
    Deleted(u64, bool, Result<()>),
}

pub struct MailUi {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    api: Option<Client>,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    epoch: u64,
    detail_epoch: u64,
    mails: Vec<Brief>,
    selected: HashSet<String>,
    rows: Rc<VecModel<MailRow>>,
    /// The mail open in the detail panel.
    open: Option<String>,
    last_screen: i32,
    pending_nav: Vec<&'static str>,
}

fn inbox(ui: &AppWindow) -> Inbox<'_> {
    ui.global::<Inbox>()
}

impl MailUi {
    pub fn wire(ui: &AppWindow, runtime: Handle) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let rows = Rc::new(VecModel::default());
        inbox(ui).set_rows(rows.clone().into());
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            api: None,
            tx,
            rx,
            epoch: 0,
            detail_epoch: 0,
            mails: Vec::new(),
            selected: HashSet::new(),
            rows,
            open: None,
            last_screen: 0,
            pending_nav: Vec::new(),
        }));
        let weak = Rc::downgrade(&state);
        inbox(ui).on_action(move |name, value| {
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
        self.detail_epoch += 1;
        self.api = api;
        self.mails.clear();
        self.selected.clear();
        self.open = None;
        if let Some(ui) = self.ui.upgrade() {
            self.reset(&ui);
        }
    }

    fn reset(&self, ui: &AppWindow) {
        let g = inbox(ui);
        self.rows.clear();
        g.set_selected_count(0);
        g.set_error("".into());
        g.set_notice("".into());
        g.set_loading(false);
        g.set_deleting(false);
        g.set_confirm_open(false);
        g.set_detail_open(false);
    }

    fn render(&self, ui: &AppWindow) {
        let (offset, now) = (mail::local_offset(), chrono::Utc::now().timestamp());
        let rows: Vec<MailRow> = self
            .mails
            .iter()
            .map(|m| MailRow {
                id: m.id.clone().into(),
                title: m.title.clone().into(),
                intro: m.intro.replace(['\r', '\n'], " ").into(),
                sender: m.sender_name.clone().into(),
                time: mail::friendly(mail::id_seconds(&m.id), now, offset).into(),
                unread: m.unread,
                selected: self.selected.contains(&m.id),
            })
            .collect();
        // Row by row, so the list keeps its scroll position when one row changes.
        if rows.len() == self.rows.row_count() {
            for (i, row) in rows.into_iter().enumerate() {
                if self.rows.row_data(i).as_ref() != Some(&row) {
                    self.rows.set_row_data(i, row);
                }
            }
        } else {
            self.rows.set_vec(rows);
        }
        inbox(ui).set_selected_count(self.selected.len() as i32);
    }

    fn load(&mut self, ui: &AppWindow) {
        let Some(api) = self.api.clone() else { return };
        self.epoch += 1;
        let g = inbox(ui);
        g.set_loading(true);
        g.set_error("".into());
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::List(epoch, mail::list(&api).await));
        });
    }

    fn load_detail(&mut self, ui: &AppWindow, id: &str) {
        let Some(api) = self.api.clone() else { return };
        self.detail_epoch += 1;
        self.open = Some(id.to_owned());
        let g = inbox(ui);
        let brief = self.mails.iter().find(|m| m.id == id);
        g.set_detail_title(brief.map(|m| m.title.clone()).unwrap_or_default().into());
        g.set_detail_sender(brief.map(|m| m.sender_name.clone()).unwrap_or_default().into());
        g.set_detail_time(mail::full_time(mail::id_seconds(id), mail::local_offset()).into());
        g.set_detail_content("".into());
        g.set_detail_error("".into());
        g.set_detail_loading(true);
        g.set_detail_open(true);
        let (tx, epoch, id) = (self.tx.clone(), self.detail_epoch, id.to_owned());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::Detail(epoch, mail::detail(&api, &id).await));
        });
    }

    fn delete(&mut self, ui: &AppWindow, ids: Vec<String>, single: bool) {
        let Some(api) = self.api.clone() else { return };
        let g = inbox(ui);
        if g.get_deleting() {
            return;
        }
        g.set_deleting(true);
        g.set_notice("".into());
        let (tx, epoch) = (self.tx.clone(), self.epoch);
        self.runtime.spawn(async move {
            let result = if single { mail::delete(&api, &ids[0]).await } else { mail::delete_many(&api, &ids).await };
            let _ = tx.send(Reply::Deleted(epoch, single, result));
        });
    }

    fn action(&mut self, name: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        let g = inbox(&ui);
        match name {
            "open-page" => {
                self.mails.clear();
                self.selected.clear();
                self.reset(&ui);
                self.load(&ui);
            }
            "reload" => self.load(&ui),
            "back-nav" => self.pending_nav.push("back"),
            "toggle" => {
                if !self.selected.remove(value) {
                    self.selected.insert(value.to_owned());
                }
                g.set_notice("".into());
                self.render(&ui);
            }
            "select-all" => {
                self.selected = if value == "1" { self.mails.iter().map(|m| m.id.clone()).collect() } else { HashSet::new() };
                g.set_notice("".into());
                self.render(&ui);
            }
            "delete-selected" => {
                if self.selected.is_empty() {
                    g.set_notice("请选择至少一封邮件".into());
                } else {
                    g.set_confirm_open(true);
                }
            }
            "delete-confirm" => {
                g.set_confirm_open(false);
                let ids: Vec<String> = self.mails.iter().filter(|m| self.selected.contains(&m.id)).map(|m| m.id.clone()).collect();
                self.delete(&ui, ids, false);
            }
            "delete-cancel" => g.set_confirm_open(false),
            "open" => self.load_detail(&ui, value),
            "detail-reload" => {
                if let Some(id) = self.open.clone() {
                    self.load_detail(&ui, &id);
                }
            }
            "delete-one" => {
                if let Some(id) = self.open.clone() {
                    self.delete(&ui, vec![id], true);
                }
            }
            "detail-close" => {
                self.detail_epoch += 1;
                self.open = None;
                g.set_detail_open(false);
            }
            "dismiss" => {
                if g.get_confirm_open() {
                    g.set_confirm_open(false);
                } else {
                    self.action("detail-close", "");
                }
            }
            _ => tracing::warn!(name, "未知的信箱操作"),
        }
    }

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        for action in std::mem::take(&mut self.pending_nav) {
            ui.invoke_navigate(action.into());
        }
        let screen = inbox(&ui).get_screen();
        if screen != self.last_screen {
            if self.last_screen == 1 {
                self.epoch += 1;
                self.detail_epoch += 1;
                self.open = None;
                self.reset(&ui);
            }
            self.last_screen = screen;
        }
        while let Ok(reply) = self.rx.try_recv() {
            let g = inbox(&ui);
            match reply {
                Reply::List(epoch, result) if epoch == self.epoch => {
                    g.set_loading(false);
                    match result {
                        Ok(mails) => {
                            // Selections of mails that are gone are dropped, as Compose does.
                            self.selected.retain(|id| mails.iter().any(|m| &m.id == id));
                            self.mails = mails;
                            self.render(&ui);
                        }
                        Err(error) => {
                            self.mails.clear();
                            self.selected.clear();
                            self.render(&ui);
                            g.set_error(format!("加载信箱失败：{error:#}").into());
                        }
                    }
                }
                Reply::Detail(epoch, result) if epoch == self.detail_epoch => {
                    g.set_detail_loading(false);
                    match result {
                        Ok(mail) => {
                            g.set_detail_title(mail.title.clone().into());
                            g.set_detail_content(mail::body(&mail.content).into());
                            // Opening a mail reads it on the server.
                            if let Some(brief) = self.mails.iter_mut().find(|m| m.id == mail.id) {
                                brief.unread = false;
                            }
                            self.render(&ui);
                        }
                        Err(error) => g.set_detail_error(format!("加载邮件失败：{error:#}").into()),
                    }
                }
                Reply::Deleted(epoch, single, result) if epoch == self.epoch => {
                    g.set_deleting(false);
                    match result {
                        Ok(()) => {
                            if single {
                                self.action("detail-close", "");
                            } else {
                                self.selected.clear();
                            }
                            self.load(&ui);
                        }
                        Err(error) if single => g.set_detail_error(format!("删除失败：{error:#}").into()),
                        Err(error) => g.set_notice(format!("删除失败：{error:#}").into()),
                    }
                }
                _ => {}
            }
        }
    }
}
