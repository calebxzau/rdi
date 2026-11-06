//! Deterministic gallery fixtures and presentation actions; no launcher business logic.
use crate::{AppWindow, ContentRow, LogRow, TaskRow, TileData, images};
use anyhow::{Context, Result};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use std::{
    cell::RefCell,
    rc::Rc,
    sync::mpsc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::task::JoinHandle;

enum Event {
    Image(Result<image::RgbaImage>),
    Export(Result<std::path::PathBuf>),
}
pub struct Gallery {
    ui: slint::Weak<AppWindow>,
    items: Rc<VecModel<ContentRow>>,
    source: Vec<ContentRow>,
    logs: Rc<VecModel<LogRow>>,
    tasks: Rc<VecModel<TaskRow>>,
    expanded: bool,
    filter: (String, i32, bool),
    runtime: tokio::runtime::Handle,
    tx: mpsc::Sender<Event>,
    rx: mpsc::Receiver<Event>,
    image_job: Option<JoinHandle<()>>,
    tiles: Rc<VecModel<TileData>>,
}
impl Gallery {
    pub fn wire(ui: &AppWindow, runtime: tokio::runtime::Handle) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let tiles = Rc::new(VecModel::from(
            (0..30)
                .map(|i| TileData {
                    name: format!("朋友的房间{} · Minecraft1.21.1超长中文名称", i + 1).into(),
                    initial: "房".into(),
                    status: i % 3,
                    dimmed: i % 4 == 1,
                    ..Default::default()
                })
                .collect::<Vec<_>>(),
        ));
        ui.set_gallery_tiles(ModelRc::from(tiles.clone()));
        let items = Rc::new(VecModel::default());
        let logs = Rc::new(VecModel::from(
            (0..60)
                .map(|i| {
                    let level = i % 3;
                    LogRow {
                        text: format!(
                            "[12:34:{:02}] [{}] {}",
                            i % 60,
                            ["INFO", "WARN", "ERROR"][level as usize],
                            [
                                "已加载Minecraft1.21.1，等待朋友加入",
                                "可选资源缺失，继续加载",
                                "资源下载失败，请稍后重试"
                            ][level as usize]
                        )
                        .into(),
                        level,
                    }
                })
                .collect::<Vec<_>>(),
        ));
        let tasks = Rc::new(VecModel::default());
        ui.set_gallery_items(ModelRc::from(items.clone()));
        ui.set_gallery_logs(ModelRc::from(logs.clone()));
        ui.set_gallery_log_text(
            logs.iter()
                .map(|r| r.text.to_string())
                .collect::<Vec<_>>()
                .join("\n")
                .into(),
        );
        ui.set_gallery_tasks(ModelRc::from(tasks.clone()));
        let source = (0..24)
            .map(|i| ContentRow {
                name: format!(
                    "{}{}",
                    ["更好的村庄", "高清资源包", "柔和光影"][i % 3],
                    i + 1
                )
                .into(),
                version: "1.21.1".into(),
                detail:
                    "与朋友一起探索Minecraft世界。这段较长的中文说明会换行，并保持版本文字可见。"
                        .into(),
                kind: ["Mod", "资源包", "光影"][i % 3].into(),
                image_state: 0,
                ..Default::default()
            })
            .collect();
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            items,
            source,
            logs,
            tasks,
            expanded: true,
            filter: (String::new(), 0, false),
            runtime,
            tx,
            rx,
            image_job: None,
            tiles,
        }));
        state.borrow_mut().refresh();
        state.borrow().refresh_tasks();
        if ui.get_gallery_enabled() {
            let mut s = state.borrow_mut();
            let tx = s.tx.clone();
            s.image_job = Some(s.runtime.spawn(async move {
                let result = async {
                    let http = reqwest::Client::builder().timeout(Duration::from_secs(20)).build()?;
                    let bytes = images::download(&http,"https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/textures/entity/player/wide/steve.png").await?;
                    images::head(&images::decode(&bytes)?.to_rgba8())
                }.await;
                let _ = tx.send(Event::Image(result));
            }));
        }
        let weak = Rc::downgrade(&state);
        ui.on_gallery_filter(move |q, k, d| {
            if let Some(s) = weak.upgrade() {
                let mut s = s.borrow_mut();
                s.filter = (q.to_string(), k, d);
                s.refresh();
            }
        });
        let weak = Rc::downgrade(&state);
        ui.on_select_log(move |index| {
            if let Some(s) = weak.upgrade() {
                let s = s.borrow();
                if let (Some(ui), Some(row)) = (s.ui.upgrade(), s.logs.row_data(index as usize)) {
                    ui.set_gallery_selected_log(row.text);
                }
            }
        });
        let weak = Rc::downgrade(&state);
        ui.on_toggle_task(move |index| {
            if index == 0
                && let Some(s) = weak.upgrade()
            {
                let mut s = s.borrow_mut();
                s.expanded = !s.expanded;
                s.refresh_tasks();
            }
        });
        let weak = Rc::downgrade(&state);
        ui.on_console_action(move |action| {
            if let Some(s) = weak.upgrade() {
                s.borrow_mut().console_action(&action);
            }
        });
        state
    }
    fn refresh(&mut self) {
        let empty = self
            .ui
            .upgrade()
            .is_some_and(|u| u.get_gallery_content_state() == 2);
        let (q, k, d) = &self.filter;
        let names = self
            .source
            .iter()
            .map(|r| (r.name.to_string(), r.kind.to_string()))
            .collect::<Vec<_>>();
        let rows = if empty {
            vec![]
        } else {
            filtered_indices(&names, q, *k, *d)
                .into_iter()
                .map(|i| self.source[i].clone())
                .collect()
        };
        sync(&self.items, rows);
    }
    fn refresh_tasks(&self) {
        let titles = [
            "下载游戏文件",
            "检查Java",
            "下载游戏核心",
            "可选资源",
            "准备启动",
            "取消的任务",
        ];
        let details = [
            "8/12个 · 24MiB/40MiB",
            "Java25已就绪",
            "24MiB/40MiB · 2MiB/s",
            "下载失败，请稍后重试",
            "等待下载完成",
            "已取消",
        ];
        let statuses = [1, 2, 1, 3, 0, 4];
        let rows = (0..if self.expanded { 6 } else { 1 })
            .map(|i| TaskRow {
                title: titles[i].into(),
                detail: details[i].into(),
                depth: if i == 0 { 0 } else { 1 },
                group: i == 0,
                expanded: self.expanded,
                status: statuses[i],
                progress: [0.6, 1., 0.6, -1., -1., -1.][i],
            })
            .collect();
        sync(&self.tasks, rows);
    }
    fn console_action(&mut self, action: &str) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        match action {
            "clear" => {
                self.logs.clear();
                ui.set_gallery_log_text("".into());
                ui.set_gallery_selected_log("".into());
            }
            "copy" => ui.invoke_notify("日志已复制".into()),
            "export" => {
                let text = self
                    .logs
                    .iter()
                    .map(|r| r.text.to_string())
                    .collect::<Vec<_>>()
                    .join("\n");
                let tx = self.tx.clone();
                self.runtime.spawn(async move {
                    let result = tokio::task::spawn_blocking(move || -> Result<_> {
                        let timestamp = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
                        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                            .join("evidence/ui-port/u3");
                        std::fs::create_dir_all(&dir)?;
                        let path = dir.join(format!("gallery-console-{timestamp}.log"));
                        use std::io::Write;
                        let mut file = std::fs::OpenOptions::new()
                            .write(true)
                            .create_new(true)
                            .open(&path)
                            .context("无法导出日志")?;
                        file.write_all(text.as_bytes())?;
                        Ok(path)
                    })
                    .await
                    .context("日志导出任务失败")
                    .and_then(|r| r);
                    let _ = tx.send(Event::Export(result));
                });
            }
            _ => tracing::error!(action, "Unknown console action"),
        }
    }
    pub fn tick(&mut self) {
        while let Ok(event) = self.rx.try_recv() {
            match event {
                Event::Image(result) => {
                    let (icon, state) = match result {
                        Ok(image) => {
                            tracing::info!("Gallery network image ready");
                            (images::to_slint(&image), 1)
                        }
                        Err(error) => {
                            tracing::error!(?error, "Gallery image failed");
                            (slint::Image::default(), 3)
                        }
                    };
                    for row in &mut self.source {
                        row.icon = icon.clone();
                        row.image_state = state;
                    }
                    if let Some(ui) = self.ui.upgrade() {
                        ui.set_gallery_image(icon.clone());
                        ui.set_gallery_image_state(state);
                    }
                    for i in 0..self.tiles.row_count() {
                        if let Some(mut row) = self.tiles.row_data(i) {
                            row.has_owner = true;
                            row.owner = icon.clone();
                            self.tiles.set_row_data(i, row);
                        }
                    }
                    self.refresh();
                }
                Event::Export(result) => {
                    if let Some(ui) = self.ui.upgrade() {
                        match result {
                            Ok(path) => {
                                tracing::info!(?path, "Gallery log exported");
                                ui.invoke_notify("样例日志已导出到画廊验收目录".into());
                            }
                            Err(error) => {
                                tracing::error!(?error, "Gallery export failed");
                                ui.set_action_error(format!("日志导出失败：{error:#}").into());
                            }
                        }
                    }
                }
            }
        }
    }
}
fn filtered_indices(
    names: &[(String, String)],
    query: &str,
    kind: i32,
    descending: bool,
) -> Vec<usize> {
    let q = query.trim().to_lowercase();
    let mut result: Vec<_> = names
        .iter()
        .enumerate()
        .filter(|(_, (name, k))| {
            name.to_lowercase().contains(&q)
                && (kind == 0
                    || *k
                        == ["", "Mod", "资源包", "光影"]
                            .get(kind as usize)
                            .copied()
                            .unwrap_or(""))
        })
        .map(|(i, _)| i)
        .collect();
    result.sort_by(|&a, &b| {
        if descending {
            names[b].0.cmp(&names[a].0)
        } else {
            names[a].0.cmp(&names[b].0)
        }
    });
    result
}
fn sync<T: Clone + PartialEq + 'static>(model: &VecModel<T>, rows: Vec<T>) {
    let len = rows.len();
    for (i, row) in rows.into_iter().enumerate() {
        if i >= model.row_count() {
            model.push(row);
        } else if model.row_data(i).as_ref() != Some(&row) {
            model.set_row_data(i, row);
        }
    }
    while model.row_count() > len {
        model.remove(model.row_count() - 1);
    }
}
impl Drop for Gallery {
    fn drop(&mut self) {
        if let Some(job) = self.image_job.take() {
            job.abort();
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn content_search_type_and_order_compose() {
        let rows = vec![
            ("Alpha村庄".into(), "Mod".into()),
            ("Beta光影".into(), "光影".into()),
            ("Alpha材质".into(), "资源包".into()),
        ];
        assert_eq!(filtered_indices(&rows, " ALPHA ", 0, false), vec![2, 0]);
        assert_eq!(filtered_indices(&rows, "alpha", 2, false), vec![2]);
        assert_eq!(filtered_indices(&rows, "", 0, true), vec![1, 0, 2]);
        assert!(filtered_indices(&rows, "不存在", 0, false).is_empty());
    }
}
