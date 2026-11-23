//! 导入rdipack2 (home shortcut) and 导出 (installed pack menu), U4 batch b2. Picks files, then runs
//! `rdipack2` as tasks and opens the task panel, as Compose `onOpenTask` does.
use crate::{
    AppWindow,
    api::Client,
    rdipack2::{self, Checks, Identity},
    tasks::Manager,
};
use anyhow::{Result, ensure};
use slint::ComponentHandle;
use std::{cell::RefCell, path::PathBuf, rc::Rc, sync::mpsc};
use tokio::runtime::Handle;

enum Reply {
    Import(Result<Option<(PathBuf, Identity)>>),
    Export(Result<Option<PathBuf>>, String),
}

pub struct Rdipack2Ui {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    tasks: Manager,
    play: crate::play::Sessions,
    api: Option<Client>,
    tx: mpsc::Sender<Reply>,
    rx: mpsc::Receiver<Reply>,
    busy: bool,
    /// Import tasks whose end refreshes the installed pack list.
    watching: Vec<String>,
}

/// `exportRdiModpack2`'s default file name: the pack name with unsafe characters replaced.
fn default_name(pack_name: &str, version: &str) -> String {
    let name = if pack_name.trim().is_empty() { "modpack" } else { pack_name };
    let safe: String = name.chars().map(|c| if "\\/:*?\"<>|".contains(c) { '_' } else { c }).collect();
    format!("{safe}_{version}.rdipack2")
}

/// The server must know this pack and version (`modpack/{id}/brief`, `.../version/{name}`).
fn server_matches(runtime: &Handle, api: &Client, identity: &Identity) -> Result<()> {
    runtime.block_on(async {
        let id = &identity.modpack_id;
        let brief: serde_json::Value = api.get(&format!("/modpack/{id}/brief")).await.map_err(|e| anyhow::anyhow!("未找到整合包信息：{e:#}"))?;
        let path = format!("/modpack/{id}/version/{}", crate::pack_install::encode_segment(&identity.version_name));
        let version: serde_json::Value = api.get(&path).await.map_err(|e| anyhow::anyhow!("未找到整合包版本信息：{e:#}"))?;
        let same = |v: &serde_json::Value| v.as_str().is_some_and(|s| s.eq_ignore_ascii_case(id));
        ensure!(same(&brief["id"]) && same(&version["modpackId"]), "整合包版本身份不匹配");
        Ok(())
    })
}

impl Rdipack2Ui {
    pub fn wire(ui: &AppWindow, runtime: Handle, tasks: Manager, play: crate::play::Sessions) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let state = Rc::new(RefCell::new(Self { ui: ui.as_weak(), runtime, tasks, play, api: None, tx, rx, busy: false, watching: Vec::new() }));
        let weak = Rc::downgrade(&state);
        ui.on_rdipack2(move |action, value| {
            if let Some(state) = weak.upgrade()
                && let Ok(mut state) = state.try_borrow_mut()
            {
                state.action(action.as_str(), value.as_str());
            }
        });
        state
    }

    pub fn connect(&mut self, api: Option<Client>) {
        self.api = api;
    }

    fn action(&mut self, action: &str, value: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        if self.api.is_none() || self.busy {
            return;
        }
        self.busy = true;
        let tx = self.tx.clone();
        match action {
            "import" => {
                self.runtime.spawn(async move {
                    let result = async {
                        let Some(file) = rfd::AsyncFileDialog::new().set_title("选择RDI整合包").add_filter("RDI整合包", &["rdipack2"]).pick_file().await else {
                            return Ok(None);
                        };
                        let path = file.path().to_path_buf();
                        let read = path.clone();
                        let identity = tokio::task::spawn_blocking(move || rdipack2::read_identity(&read)).await??;
                        Ok(Some((path, identity)))
                    }
                    .await;
                    let _ = tx.send(Reply::Import(result));
                });
            }
            "export" => {
                let version_id = value.to_owned();
                let version = version_id.split_once('_').map(|(_, v)| v.to_owned()).unwrap_or_default();
                let name = default_name(&ui.get_pack_name(), &version);
                self.runtime.spawn(async move {
                    let picked = rfd::AsyncFileDialog::new()
                        .set_title("选择整合包保存位置")
                        .set_file_name(&name)
                        .add_filter("RDI整合包", &["rdipack2"])
                        .save_file()
                        .await
                        .map(|f| {
                            let path = f.path().to_path_buf();
                            if path.extension().is_some_and(|e| e.eq_ignore_ascii_case("rdipack2")) { path } else { path.with_extension("rdipack2") }
                        });
                    let _ = tx.send(Reply::Export(Ok(picked), version_id));
                });
            }
            _ => {
                self.busy = false;
                tracing::warn!(action, "未知的整合包归档操作");
            }
        }
    }

    fn open_task(&mut self, ui: &AppWindow, id: String, watch: bool) {
        ui.invoke_navigate("tasks".into());
        ui.invoke_pack_action("tasks.watch".into(), id.clone().into());
        if watch {
            self.watching.push(id);
        }
    }

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else { return };
        while let Ok(reply) = self.rx.try_recv() {
            self.busy = false;
            let Some(api) = self.api.clone() else { continue };
            match reply {
                Reply::Import(Ok(Some((file, identity)))) => {
                    let (runtime, play) = (self.runtime.clone(), self.play.clone());
                    let title = format!("导入整合包 {}", identity.version_name);
                    let dedupe = format!("rdipack2-import:{}:{}", identity.modpack_id, identity.version_name);
                    let id = self.tasks.submit(&self.runtime, title, dedupe, move |ctx| {
                        let matches = |i: &Identity| server_matches(&runtime, &api, i);
                        let running = |v: &str| play.alive_count(v) > 0;
                        let checks = Checks { server_matches: &matches, running: &running };
                        let cache = crate::paths::data_dir().join("dlc");
                        let message = rdipack2::import(&ctx, &file, &identity, &crate::local_packs::versions_dir(), &cache, &checks)?;
                        ctx.finish(message);
                        Ok(())
                    });
                    self.open_task(&ui, id, true);
                }
                Reply::Export(Ok(Some(output)), version_id) => {
                    let pack = crate::local_packs::versions_dir().join(&version_id);
                    let version = version_id.split_once('_').map(|(_, v)| v.to_owned()).unwrap_or_default();
                    let dedupe = format!("rdipack2-export:{version_id}:{}", output.display());
                    let id = self.tasks.submit(&self.runtime, format!("导出整合包 {version}"), dedupe, move |ctx| {
                        rdipack2::export(&ctx, &pack, &version_id, &output)?;
                        ctx.finish(format!("已导出到{}", output.display()));
                        Ok(())
                    });
                    self.open_task(&ui, id, false);
                }
                Reply::Import(Ok(None)) | Reply::Export(Ok(None), _) => {}
                Reply::Import(Err(error)) | Reply::Export(Err(error), _) => {
                    tracing::error!(error = %format!("{error:#}"), "导入rdipack2失败");
                    ui.set_action_error(format!("{error:#}").into());
                }
            }
        }
        let tasks = &self.tasks;
        let before = self.watching.len();
        self.watching.retain(|id| tasks.snapshot(id).is_some_and(|s| s["terminal"] != true));
        if self.watching.len() != before {
            ui.invoke_pack_action("packs.refresh".into(), "".into());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn export_names_follow_compose() {
        assert_eq!(default_name("七七:修仙?", "0.3"), "七七_修仙__0.3.rdipack2");
        assert_eq!(default_name(" ", "1"), "modpack_1.rdipack2");
    }
}
