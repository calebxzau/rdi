//! The 模组 page of `RoomPagesUi` (U4 batch c2b): areas, selection (owner decision D2), side
//! toggles and the GitHub dialog (D3). Mod names and icons come from the mod catalog.
use super::{Page, Pending, Reply, RoomPagesUi, pages, text};
use crate::{
    AppWindow, GithubAssetRow, RoomModRow,
    catalog::{Catalog, models::{Platform, ProjectRef}},
    images,
    pack_install::mods::{Mod, Side},
    rooms::{
        github::{self, Release, Repo},
        model::HostDetail,
        mods::{self, Areas},
        permissions::{self, Me},
        requests,
    },
    tasks::Cancellation,
};
use anyhow::Result;
use slint::{ComponentHandle, SharedString, VecModel};
use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
    time::{Duration, Instant},
};

#[derive(Clone, Debug)]
pub(super) struct Card {
    name: String,
    intro: String,
    icon: Option<String>,
}

#[derive(Clone, Copy, Debug)]
pub(super) enum ModsOp {
    Disable,
    Enable,
    Remove,
    Side(Side),
}
impl ModsOp {
    fn failure(self) -> &'static str {
        match self {
            Self::Disable => "停用Mod失败",
            Self::Enable => "启用Mod失败",
            Self::Remove => "删除附加Mod失败",
            Self::Side(_) => "修改附加Mod运行侧失败",
        }
    }
}

pub(super) enum GithubReply {
    Releases(Result<(Repo, Vec<Release>)>),
    Built(Result<Mod>),
    Added(Result<()>),
}

pub(super) struct ModsView {
    areas: Option<Areas>,
    cards: HashMap<String, Card>,
    icons: HashMap<String, slint::Image>,
    requested: HashSet<String>,
    /// `area|key` of the selected rows.
    selected: HashSet<String>,
    busy: bool,
    dirty: bool,
    rendered: Option<Instant>,
    gh_epoch: u64,
    gh_repo: Option<Repo>,
    gh_releases: Vec<Release>,
    gh_selected: Option<String>,
    catalog: Arc<tokio::sync::OnceCell<Arc<Catalog>>>,
    icon_slots: Arc<tokio::sync::Semaphore>,
}
impl Default for ModsView {
    fn default() -> Self {
        Self {
            areas: None,
            cards: HashMap::new(),
            icons: HashMap::new(),
            requested: HashSet::new(),
            selected: HashSet::new(),
            busy: false,
            dirty: false,
            rendered: None,
            gh_epoch: 0,
            gh_repo: None,
            gh_releases: Vec::new(),
            gh_selected: None,
            catalog: Arc::new(tokio::sync::OnceCell::new()),
            icon_slots: Arc::new(tokio::sync::Semaphore::new(6)),
        }
    }
}

fn reference(m: &Mod) -> Option<ProjectRef> {
    let platform = match m.platform.as_str() {
        "mr" => Platform::Modrinth,
        "cf" => Platform::CurseForge,
        _ => return None,
    };
    let id = m.project_id.trim();
    (!id.is_empty()).then(|| ProjectRef { platform, id: id.to_owned() })
}

fn side_of(index: i32) -> Side {
    match index {
        1 => Side::Client,
        2 => Side::Server,
        _ => Side::Both,
    }
}

impl RoomPagesUi {
    pub(super) fn open_mods(&mut self, ui: &AppWindow) {
        self.page = Page::Mods;
        self.mods.areas = None;
        self.mods.selected.clear();
        self.mods.busy = false;
        let g = pages(ui);
        g.set_mods_loaded(false);
        g.set_mods_busy(false);
        g.set_enabled_query("".into());
        self.render_mods(ui);
        self.load_mods(ui);
    }

    pub(super) fn load_mods(&mut self, ui: &AppWindow) {
        let Some(api) = self.api.clone() else { return };
        let g = pages(ui);
        g.set_loading(true);
        g.set_error("".into());
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let result = async {
                let detail = requests::detail(api.server(), &host).await?;
                let version = mods::version_mods(api.server(), &detail.modpack.id, &detail.pack_ver).await?;
                Ok((detail, version))
            }
            .await;
            let _ = tx.send(Reply::Mods(epoch, result));
        });
    }

    pub(super) fn mods_loaded(&mut self, ui: &AppWindow, result: Result<(HostDetail, Vec<Mod>)>) {
        let g = pages(ui);
        g.set_loading(false);
        let (detail, version) = match result {
            Ok(v) => v,
            Err(error) => {
                tracing::warn!(error = %format!("{error:#}"), "房间模组加载失败");
                g.set_error(format!("加载房间信息失败: {}", text(&error, "未知错误")).into());
                return;
            }
        };
        let Some((me, dav)) = self.me() else { return };
        let me = Me { id: &me, is_dav: dav };
        g.set_title(format!("{} - 模组", detail.name).into());
        g.set_can_view(permissions::can_view_members(&detail, me));
        g.set_can_manage(permissions::can_manage(&detail, me));
        g.set_mods_loaded(true);
        let areas = Areas::new(version, detail.disabled_mods.clone(), detail.extra_mods.clone());
        self.detail = Some(detail);
        self.mods.areas = Some(areas);
        self.render_mods(ui);
        self.hydrate(ui);
    }

    /// Names, intros and icons from the catalog (`hydrateToUiModsInBatches`).
    fn hydrate(&mut self, ui: &AppWindow) {
        let Some(areas) = &self.mods.areas else { return };
        let Some(api) = self.api.clone() else { return };
        let all: Vec<Mod> = areas.version.iter().chain(&areas.disabled).chain(&areas.extra).cloned().collect();
        let missing: Vec<Mod> = all.into_iter().filter(|m| !self.mods.cards.contains_key(&mods::key(m))).collect();
        let refs: Vec<ProjectRef> = missing.iter().filter_map(reference).collect::<HashSet<_>>().into_iter().collect();
        if refs.is_empty() {
            return;
        }
        pages(ui).set_mods_hydrating(true);
        let (epoch, tx, cell) = (self.epoch, self.tx.clone(), self.mods.catalog.clone());
        self.runtime.spawn(async move {
            let result = async {
                let catalog = cell
                    .get_or_try_init(|| async {
                        let http = api.http.clone();
                        anyhow::Ok(Arc::new(tokio::task::spawn_blocking(move || Catalog::new(http, &crate::paths::data_dir())).await?))
                    })
                    .await?
                    .clone();
                catalog.get_mods(&refs).await
            }
            .await;
            let mut cards = HashMap::new();
            match result {
                Ok(outcome) => {
                    for m in &missing {
                        if let Some(found) = reference(m).and_then(|r| outcome.value.get(&r)) {
                            let name = found.name_cn.clone().filter(|n| !n.trim().is_empty()).unwrap_or_else(|| found.name.clone());
                            cards.insert(
                                mods::key(m),
                                Card { name: name.trim().to_owned(), intro: found.summary.clone(), icon: found.icons.first().cloned() },
                            );
                        }
                    }
                }
                Err(error) => tracing::warn!(error = %format!("{error:#}"), "加载房间Mod展示信息失败，将使用基础Mod数据"),
            }
            let _ = tx.send(Reply::Cards(epoch, cards));
        });
    }

    pub(super) fn cards_loaded(&mut self, ui: &AppWindow, cards: HashMap<String, Card>) {
        pages(ui).set_mods_hydrating(false);
        let Some(api) = self.api.clone() else { return };
        let size = (36.0 * ui.window().scale_factor()).ceil().max(1.0) as u32;
        for card in cards.values() {
            let Some(url) = card.icon.clone().filter(|u| !u.trim().is_empty()) else { continue };
            if !self.mods.requested.insert(url.clone()) {
                continue;
            }
            let (tx, http, slots) = (self.tx.clone(), api.http.clone(), self.mods.icon_slots.clone());
            self.runtime.spawn(async move {
                let _slot = slots.acquire_owned().await;
                let icon = match images::icon(&http, url.trim(), size).await {
                    Ok(icon) => Some(icon),
                    Err(error) => {
                        tracing::debug!(%error, "Mod图标加载失败");
                        None
                    }
                };
                let _ = tx.send(Reply::ModIcon(url, icon));
            });
        }
        self.mods.cards.extend(cards);
        self.mods.dirty = true;
    }

    pub(super) fn icon_loaded(&mut self, url: String, icon: Option<image::RgbaImage>) {
        if let Some(icon) = icon {
            self.mods.icons.insert(url, images::to_slint(&icon));
            self.mods.dirty = true;
        }
    }

    fn row(&self, m: &Mod, area: &str, side_detail: bool) -> RoomModRow {
        let key = mods::key(m);
        let card = self.mods.cards.get(&key);
        let name = card.map(|c| c.name.clone()).filter(|n| !n.is_empty()).unwrap_or_else(|| mods::display_slug(m));
        let detail = match card {
            _ if side_detail => mods::side_text(m.side).to_owned(),
            Some(c) if !c.intro.trim().is_empty() => c.intro.clone(),
            _ => "暂无简介".to_owned(),
        };
        let icon = card.and_then(|c| c.icon.as_ref()).and_then(|u| self.mods.icons.get(u)).cloned();
        let (client, server) = mods::side_flags(m.side);
        RoomModRow {
            key: key.as_str().into(),
            initial: name.chars().next().map(|c| c.to_uppercase().to_string()).unwrap_or_default().into(),
            name: name.into(),
            detail: detail.into(),
            has_icon: icon.is_some(),
            icon: icon.unwrap_or_default(),
            selected: self.mods.selected.contains(&format!("{area}|{key}")),
            client,
            server,
        }
    }

    pub(super) fn render_mods(&mut self, ui: &AppWindow) {
        self.mods.dirty = false;
        self.mods.rendered = Some(Instant::now());
        let g = pages(ui);
        let Some(areas) = &self.mods.areas else {
            for set in [RoomPages::set_enabled_rows, RoomPages::set_disabled_rows, RoomPages::set_extra_rows] {
                set(&g, VecModel::from_slice(&[]));
            }
            return;
        };
        let query = g.get_enabled_query().trim().to_lowercase();
        let matches = |m: &Mod| {
            if query.is_empty() {
                return true;
            }
            let card = self.mods.cards.get(&mods::key(m));
            [card.map(|c| c.name.as_str()).unwrap_or(""), m.slug.as_str(), m.project_id.as_str()].iter().any(|t| t.to_lowercase().contains(&query))
        };
        let enabled: Vec<RoomModRow> = areas.enabled.iter().filter(|m| matches(m)).map(|m| self.row(m, "enabled", false)).collect();
        let disabled: Vec<RoomModRow> = areas.disabled.iter().map(|m| self.row(m, "disabled", false)).collect();
        let extra: Vec<RoomModRow> = areas.extra.iter().map(|m| self.row(m, "extra", true)).collect();
        let count = |area: &str, list: &[Mod]| list.iter().filter(|m| self.mods.selected.contains(&format!("{area}|{}", mods::key(m)))).count() as i32;
        g.set_enabled_selected(count("enabled", &areas.enabled));
        g.set_disabled_selected(count("disabled", &areas.disabled));
        g.set_extra_selected(count("extra", &areas.extra));
        g.set_enabled_count(areas.enabled.len() as i32);
        g.set_disabled_count(areas.disabled.len() as i32);
        g.set_extra_count(areas.extra.len() as i32);
        g.set_enabled_rows(VecModel::from_slice(&enabled));
        g.set_disabled_rows(VecModel::from_slice(&disabled));
        g.set_extra_rows(VecModel::from_slice(&extra));
    }

    /// Icons arrive one by one; the rows are rebuilt at most every 150ms.
    pub(super) fn mods_tick(&mut self, ui: &AppWindow) {
        if self.page == Page::Mods && self.mods.dirty && self.mods.rendered.is_none_or(|t| t.elapsed() >= Duration::from_millis(150)) {
            self.render_mods(ui);
        }
    }

    fn selected_in(&self, area: &str, list: &[Mod]) -> Vec<Mod> {
        list.iter().filter(|m| self.mods.selected.contains(&format!("{area}|{}", mods::key(m)))).cloned().collect()
    }

    fn run_mods_op<F>(&mut self, ui: &AppWindow, op: ModsOp, call: F)
    where
        F: FnOnce(crate::api::Client, String) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<Vec<Mod>>> + Send>> + Send + 'static,
    {
        let Some(api) = self.api.clone() else { return };
        self.mods.busy = true;
        pages(ui).set_mods_busy(true);
        let (epoch, host, tx) = (self.epoch, self.host.clone(), self.tx.clone());
        self.runtime.spawn(async move {
            let _ = tx.send(Reply::ModsChanged(epoch, op, call(api, host).await));
        });
    }

    /// Handles the 模组 page's actions; false when the action is not one of them.
    pub(super) fn mods_action(&mut self, ui: &AppWindow, action: &str, value: &str) -> bool {
        let g = pages(ui);
        let manage = g.get_can_manage();
        match action {
            "mods-search" => self.render_mods(ui),
            "mod-select" if manage => {
                if !self.mods.selected.remove(value) {
                    self.mods.selected.insert(value.to_owned());
                }
                self.render_mods(ui);
            }
            "mods-disable" | "mods-enable" if manage && !self.mods.busy => {
                let Some(areas) = &self.mods.areas else { return true };
                let disable = action == "mods-disable";
                let targets = if disable { self.selected_in("enabled", &areas.enabled) } else { self.selected_in("disabled", &areas.disabled) };
                if targets.is_empty() {
                    return true;
                }
                let op = if disable { ModsOp::Disable } else { ModsOp::Enable };
                self.run_mods_op(ui, op, move |api, host| Box::pin(async move { mods::change_disabled(api.server(), &host, &targets, disable).await }));
            }
            "mods-remove" if manage && !self.mods.busy => {
                let Some(areas) = &self.mods.areas else { return true };
                let targets = self.selected_in("extra", &areas.extra);
                if targets.is_empty() {
                    return true;
                }
                g.set_confirm_lines(VecModel::from_slice(&[SharedString::from(mods::removal_message(&targets))]));
                g.set_confirm_label("确定".into());
                g.set_confirm_cancel("取消".into());
                g.set_confirm_danger(true);
                self.pending = Some(Pending::RemoveExtra(targets));
                g.set_confirm_title("删除附加Mod".into());
            }
            "mod-side" if manage && !self.mods.busy => {
                let Some((key, which)) = value.rsplit_once('|') else { return true };
                let Some(m) = self.mods.areas.as_ref().and_then(|a| a.extra.iter().find(|m| mods::key(m) == key)).cloned() else { return true };
                let (client, server) = mods::side_flags(m.side);
                let side = if which == "client" { mods::flags_side(!client, server) } else { mods::flags_side(client, !server) };
                if side == m.side {
                    return true;
                }
                if side == Side::Unknown {
                    ui.invoke_notify("至少保留客户端或服务端之一".into());
                    return true;
                }
                g.set_confirm_lines(VecModel::from_slice(&[SharedString::from("更改模组运行侧可能会导致整合包运行异常，确定吗？")]));
                g.set_confirm_label("确定".into());
                g.set_confirm_cancel("取消".into());
                g.set_confirm_danger(false);
                self.pending = Some(Pending::Side(m, side));
                g.set_confirm_title("更改运行侧".into());
            }
            "gh-open" if manage => {
                self.mods.gh_epoch += 1;
                self.mods.gh_repo = None;
                self.mods.gh_releases.clear();
                self.mods.gh_selected = None;
                g.set_gh_url("".into());
                g.set_gh_loaded(false);
                g.set_gh_rows(VecModel::from_slice(&[]));
                g.set_gh_side(0);
                g.set_gh_loading(false);
                g.set_gh_status("".into());
                g.set_gh_error("".into());
                g.set_gh_open(true);
            }
            "gh-cancel" if !g.get_gh_loading() => {
                self.mods.gh_epoch += 1;
                g.set_gh_open(false);
            }
            "gh-reset" if !g.get_gh_loading() => {
                self.mods.gh_repo = None;
                self.mods.gh_releases.clear();
                self.mods.gh_selected = None;
                g.set_gh_loaded(false);
                g.set_gh_error("".into());
                self.render_assets(ui);
            }
            "gh-select" if !g.get_gh_loading() => {
                self.mods.gh_selected = Some(value.to_owned());
                g.set_gh_error("".into());
                self.render_assets(ui);
            }
            "gh-submit" => self.gh_submit(ui),
            _ => return false,
        }
        true
    }

    pub(super) fn confirm_mods(&mut self, ui: &AppWindow, pending: Pending) {
        match pending {
            Pending::RemoveExtra(targets) => {
                let mut ids: Vec<String> = Vec::new();
                for m in &targets {
                    if !ids.contains(&m.project_id) {
                        ids.push(m.project_id.clone());
                    }
                }
                self.run_mods_op(ui, ModsOp::Remove, move |api, host| Box::pin(async move { mods::remove_extra(api.server(), &host, &ids).await }));
            }
            Pending::Side(m, side) => {
                self.run_mods_op(ui, ModsOp::Side(side), move |api, host| {
                    Box::pin(async move { mods::extra_side(api.server(), &host, &m.project_id, side).await })
                });
            }
            _ => {}
        }
    }

    pub(super) fn mods_changed(&mut self, ui: &AppWindow, op: ModsOp, result: Result<Vec<Mod>>) {
        self.mods.busy = false;
        pages(ui).set_mods_busy(false);
        let list = match result {
            Ok(list) => list,
            Err(error) => {
                tracing::warn!(?op, error = %format!("{error:#}"), "房间模组操作失败");
                self.alert(ui, "错误", &format!("{}: {}", op.failure(), text(&error, "未知错误")));
                return;
            }
        };
        let Some(areas) = &self.mods.areas else { return };
        let (areas, message, area) = match op {
            ModsOp::Disable => (areas.with_disabled(list), "已停用Mod，重启房间后生效".to_owned(), "enabled|"),
            ModsOp::Enable => (areas.with_disabled(list), "已恢复Mod，重启房间后生效".to_owned(), "disabled|"),
            ModsOp::Remove => (areas.with_extra(list), "已删除附加Mod".to_owned(), "extra|"),
            ModsOp::Side(side) => (areas.with_extra(list), format!("已修改为{}，重启房间后生效", mods::side_text(side)), ""),
        };
        if !area.is_empty() {
            self.mods.selected.retain(|k| !k.starts_with(area));
        }
        self.mods.areas = Some(areas);
        ui.invoke_notify(message.into());
        self.render_mods(ui);
        self.hydrate(ui);
    }

    fn render_assets(&self, ui: &AppWindow) {
        let mut rows = Vec::new();
        for release in &self.mods.gh_releases {
            rows.push(GithubAssetRow { header: true, label: release.name.as_str().into(), ..Default::default() });
            for asset in &release.assets {
                let key = asset.key();
                rows.push(GithubAssetRow {
                    header: false,
                    label: asset.name.as_str().into(),
                    detail: format!("{} · {}", asset.release_tag, crate::packs::human_file_size(asset.size)).into(),
                    selected: self.mods.gh_selected.as_deref() == Some(key.as_str()),
                    key: key.into(),
                });
            }
        }
        pages(ui).set_gh_rows(VecModel::from_slice(&rows));
    }

    fn gh_submit(&mut self, ui: &AppWindow) {
        let g = pages(ui);
        if g.get_gh_loading() {
            return;
        }
        let Some(api) = self.api.clone() else { return };
        let (epoch, tx) = (self.mods.gh_epoch, self.tx.clone());
        match (&self.mods.gh_repo, self.mods.gh_releases.is_empty()) {
            (Some(repo), false) => {
                let Some(asset) = self
                    .mods
                    .gh_selected
                    .as_ref()
                    .and_then(|k| self.mods.gh_releases.iter().flat_map(|r| &r.assets).find(|a| &a.key() == k))
                    .cloned()
                else {
                    g.set_gh_error("请先选择1个Release文件".into());
                    return;
                };
                let (repo, side) = (repo.clone(), side_of(g.get_gh_side()));
                g.set_gh_loading(true);
                g.set_gh_error("".into());
                g.set_gh_status("准备GitHub Mod...".into());
                self.runtime.spawn(async move {
                    let work = crate::paths::data_dir().join("tmp").join(format!("github-extra-mod-{}", uuid::Uuid::now_v7().simple()));
                    let result = github::build_mod(&api.http, &repo, &asset, side, &work, &Cancellation::default()).await;
                    if let Err(error) = std::fs::remove_dir_all(&work)
                        && error.kind() != std::io::ErrorKind::NotFound
                    {
                        tracing::warn!(%error, path = %work.display(), "清理GitHub Mod临时文件失败");
                    }
                    let _ = tx.send(Reply::Github(epoch, GithubReply::Built(result)));
                });
            }
            _ => {
                let repo = match github::parse_repo(&g.get_gh_url()) {
                    Ok(repo) => repo,
                    Err(error) => {
                        g.set_gh_error(format!("读取GitHub Release失败: {error}").into());
                        return;
                    }
                };
                g.set_gh_loading(true);
                g.set_gh_error("".into());
                g.set_gh_status("读取GitHub Release...".into());
                self.runtime.spawn(async move {
                    let result = github::fetch_releases(&api.http, &repo).await.map(|list| (repo, list));
                    let _ = tx.send(Reply::Github(epoch, GithubReply::Releases(result)));
                });
            }
        }
    }

    pub(super) fn github_reply(&mut self, ui: &AppWindow, epoch: u64, reply: GithubReply) {
        if epoch != self.mods.gh_epoch {
            return;
        }
        let g = pages(ui);
        let fail = |prefix: &str, error: anyhow::Error| {
            tracing::warn!(error = %format!("{error:#}"), "{prefix}");
            g.set_gh_loading(false);
            g.set_gh_status("".into());
            g.set_gh_error(format!("{prefix}: {}", text(&error, "未知错误")).into());
        };
        match reply {
            GithubReply::Releases(Ok((repo, releases))) => {
                g.set_gh_loading(false);
                g.set_gh_status("".into());
                g.set_gh_project(repo.project_id().into());
                self.mods.gh_repo = Some(repo);
                self.mods.gh_releases = releases;
                self.mods.gh_selected = None;
                g.set_gh_loaded(true);
                self.render_assets(ui);
            }
            GithubReply::Releases(Err(error)) => fail("读取GitHub Release失败", error),
            GithubReply::Built(Ok(m)) => {
                let existing: Vec<Mod> = self.mods.areas.as_ref().map(|a| a.extra.iter().chain(&a.version).cloned().collect()).unwrap_or_default();
                if let Err(message) = mods::check_new_extra(&m, &existing) {
                    g.set_gh_loading(false);
                    g.set_gh_status("".into());
                    g.set_gh_error(message.into());
                    return;
                }
                let Some(api) = self.api.clone() else { return };
                let (host, tx) = (self.host.clone(), self.tx.clone());
                self.runtime.spawn(async move {
                    let result = mods::add_extra(api.server(), &host, &[m]).await;
                    let _ = tx.send(Reply::Github(epoch, GithubReply::Added(result)));
                });
            }
            GithubReply::Built(Err(error)) => fail("GitHub Mod处理失败", error),
            GithubReply::Added(Ok(())) => {
                g.set_gh_loading(false);
                g.set_gh_open(false);
                self.mods.gh_epoch += 1;
                ui.invoke_notify("已提交附加Mod添加任务，请在邮件中查看进度".into());
            }
            GithubReply::Added(Err(error)) => fail("添加附加Mod失败", error),
        }
    }
}

use crate::RoomPages;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalog_refs_only_for_catalog_platforms() {
        let m = |platform: &str, id: &str| -> Mod {
            serde_json::from_value(serde_json::json!({"platform":platform,"projectId":id,"slug":"s","fileId":"f","hash":"h"})).unwrap()
        };
        assert_eq!(reference(&m("mr", " AANobbMI ")).unwrap(), ProjectRef { platform: Platform::Modrinth, id: "AANobbMI".into() });
        assert_eq!(reference(&m("cf", "238222")).unwrap().platform, Platform::CurseForge);
        assert!(reference(&m("github", "o/r")).is_none());
        assert!(reference(&m("cf", " ")).is_none());
        assert_eq!(side_of(1), Side::Client);
        assert_eq!(side_of(9), Side::Both);
    }
}
