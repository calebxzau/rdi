//! Mod names, intros and icons from the mod catalog for mod card lists (Compose
//! `hydrateToUiModsInBatches`). Shared by a room's 模组 page and the plaza version page; both
//! fall back to the mod's slug when the catalog does not know it.
use crate::{
    api::Client,
    catalog::{
        Catalog,
        models::{Platform, ProjectRef},
    },
    images,
    pack_install::mods::Mod,
    rooms::mods,
};
use std::{
    collections::{HashMap, HashSet},
    future::Future,
    sync::Arc,
};

#[derive(Clone, Debug)]
pub struct Card {
    pub name: String,
    pub intro: String,
    pub icon: Option<String>,
}

/// Catalog cards by `mods::key`, and their decoded icons by URL.
pub struct ModCards {
    cards: HashMap<String, Card>,
    icons: HashMap<String, slint::Image>,
    requested: HashSet<String>,
    catalog: Arc<tokio::sync::OnceCell<Arc<Catalog>>>,
    icon_slots: Arc<tokio::sync::Semaphore>,
}
impl Default for ModCards {
    fn default() -> Self {
        Self {
            cards: HashMap::new(),
            icons: HashMap::new(),
            requested: HashSet::new(),
            catalog: Arc::new(tokio::sync::OnceCell::new()),
            icon_slots: Arc::new(tokio::sync::Semaphore::new(6)),
        }
    }
}

/// The catalog project of a CurseForge or Modrinth mod; GitHub and other mods have none.
pub fn reference(m: &Mod) -> Option<ProjectRef> {
    let platform = match m.platform.as_str() {
        "mr" => Platform::Modrinth,
        "cf" => Platform::CurseForge,
        _ => return None,
    };
    let id = m.project_id.trim();
    (!id.is_empty()).then(|| ProjectRef { platform, id: id.to_owned() })
}

/// The first letter of a name, upper-cased, for the icon placeholder.
pub fn initial(name: &str) -> String {
    name.chars().next().map(|c| c.to_uppercase().to_string()).unwrap_or_default()
}

impl ModCards {
    /// A catalog lookup for the mods that have no card yet; `None` when none of them is in the
    /// catalog. A failed lookup is logged and yields no cards, so the slugs stay visible.
    pub fn lookup<'a>(
        &self,
        api: &Client,
        list: impl IntoIterator<Item = &'a Mod>,
    ) -> Option<impl Future<Output = HashMap<String, Card>> + Send + 'static> {
        let missing: Vec<Mod> = list.into_iter().filter(|m| !self.cards.contains_key(&mods::key(m))).cloned().collect();
        let refs: Vec<ProjectRef> = missing.iter().filter_map(reference).collect::<HashSet<_>>().into_iter().collect();
        if refs.is_empty() {
            return None;
        }
        let (cell, http) = (self.catalog.clone(), api.http.clone());
        Some(async move {
            let result = async {
                let catalog = cell
                    .get_or_try_init(|| async {
                        anyhow::Ok(Arc::new(
                            tokio::task::spawn_blocking(move || Catalog::new(http, &crate::paths::data_dir())).await?,
                        ))
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
                Err(error) => tracing::warn!(error = %format!("{error:#}"), "加载Mod展示信息失败，将使用基础Mod数据"),
            }
            cards
        })
    }

    /// Stores new cards and returns one download per icon URL not requested before. Each
    /// download yields the URL and the icon, or `None` when it failed (logged at debug).
    pub fn add_cards(
        &mut self,
        api: &Client,
        size: u32,
        cards: HashMap<String, Card>,
    ) -> Vec<impl Future<Output = (String, Option<image::RgbaImage>)> + Send + 'static> {
        let mut jobs = Vec::new();
        for card in cards.values() {
            let Some(url) = card.icon.clone().filter(|u| !u.trim().is_empty()) else { continue };
            if !self.requested.insert(url.clone()) {
                continue;
            }
            let (http, slots) = (api.http.clone(), self.icon_slots.clone());
            jobs.push(async move {
                let _slot = slots.acquire_owned().await;
                let icon = match images::icon(&http, url.trim(), size).await {
                    Ok(icon) => Some(icon),
                    Err(error) => {
                        tracing::debug!(%error, "Mod图标加载失败");
                        None
                    }
                };
                (url, icon)
            });
        }
        self.cards.extend(cards);
        jobs
    }

    /// Stores a downloaded icon; true when the rows need rebuilding.
    pub fn add_icon(&mut self, url: String, icon: Option<image::RgbaImage>) -> bool {
        let Some(icon) = icon else { return false };
        self.icons.insert(url, images::to_slint(&icon));
        true
    }

    pub fn card(&self, m: &Mod) -> Option<&Card> {
        self.cards.get(&mods::key(m))
    }

    /// The catalog name, else the slug (`displaySlugOrProject`).
    pub fn name(&self, m: &Mod) -> String {
        self.card(m).map(|c| c.name.clone()).filter(|n| !n.is_empty()).unwrap_or_else(|| mods::display_slug(m))
    }

    /// The catalog intro, else 「暂无简介」.
    pub fn intro(&self, m: &Mod) -> String {
        self.card(m).map(|c| c.intro.trim()).filter(|i| !i.is_empty()).unwrap_or("暂无简介").to_owned()
    }

    pub fn icon(&self, m: &Mod) -> Option<slint::Image> {
        self.card(m).and_then(|c| c.icon.as_ref()).and_then(|u| self.icons.get(u)).cloned()
    }

    /// Search by catalog name, slug or project id; `query` is already trimmed and lower-cased,
    /// and an empty one matches all.
    pub fn matches(&self, m: &Mod, query: &str) -> bool {
        if query.is_empty() {
            return true;
        }
        let name = self.card(m).map(|c| c.name.as_str()).unwrap_or("");
        [name, m.slug.as_str(), m.project_id.as_str()].iter().any(|t| t.to_lowercase().contains(query))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(platform: &str, id: &str) -> Mod {
        serde_json::from_value(serde_json::json!({"platform":platform,"projectId":id,"slug":"s","fileId":"f","hash":"h"})).unwrap()
    }

    #[test]
    fn catalog_refs_only_for_catalog_platforms() {
        assert_eq!(reference(&m("mr", " AANobbMI ")).unwrap(), ProjectRef { platform: Platform::Modrinth, id: "AANobbMI".into() });
        assert_eq!(reference(&m("cf", "238222")).unwrap().platform, Platform::CurseForge);
        assert!(reference(&m("github", "o/r")).is_none());
        assert!(reference(&m("cf", " ")).is_none());
    }

    #[test]
    fn falls_back_to_slug_and_matches_name_slug_or_project() {
        let mut cards = ModCards::default();
        let jei = m("cf", "238222");
        assert_eq!(cards.name(&jei), "s");
        assert_eq!(cards.intro(&jei), "暂无简介");
        cards.cards.insert(mods::key(&jei), Card { name: "JEI物品管理器".into(), intro: " ".into(), icon: None });
        assert_eq!(cards.name(&jei), "JEI物品管理器");
        assert_eq!(cards.intro(&jei), "暂无简介");
        for query in ["", "jei", "238222", "s"] {
            assert!(cards.matches(&jei, query), "{query}");
        }
        assert!(!cards.matches(&jei, "create"));
        assert_eq!(initial("jei"), "J");
        assert_eq!(initial(""), "");
    }
}
