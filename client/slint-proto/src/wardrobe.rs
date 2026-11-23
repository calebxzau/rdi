use crate::{AppWindow, WardrobeItem, api::server::ServerApi, images};
use anyhow::{Context, Result, ensure};
use image::RgbaImage;
use reqwest::{Client, Url};
use serde::Deserialize;
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use std::{cell::RefCell, collections::HashSet, rc::Rc, sync::mpsc, time::Duration};
use tokio::{
    runtime::Handle,
    task::{JoinHandle, JoinSet},
};

const LITTLE_SKIN: &str = "https://littleskin.cn";
// The Compose client fetches up to two 20-item provider pages with a short delay.
const PROVIDER_PAGE_SIZE: usize = 20;
const PAGE_DELAY: Duration = Duration::from_millis(300);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(12);

#[derive(Clone, Default)]
struct Search {
    keyword: String,
    cape: bool,
}

#[derive(Deserialize)]
struct TextureSummary {
    tid: i32,
    name: String,
    likes: i32,
}

#[derive(Deserialize)]
struct ProviderPage {
    current_page: u32,
    last_page: Option<u32>,
    data: Vec<TextureSummary>,
}

impl ProviderPage {
    fn next(&self) -> Option<u32> {
        let has_more = self
            .last_page
            .map_or(self.data.len() >= PROVIDER_PAGE_SIZE, |last| {
                self.current_page < last
            });
        has_more.then(|| self.current_page.checked_add(1)).flatten()
    }
}

struct Page {
    items: Vec<TextureSummary>,
    next: Option<u32>,
}

#[derive(Clone)]
struct LittleSkin {
    http: Client,
    base: Url,
}

impl LittleSkin {
    fn new() -> Result<Self> {
        Ok(Self {
            http: ServerApi::new(LITTLE_SKIN.into())?.http,
            base: Url::parse(LITTLE_SKIN)?,
        })
    }

    async fn fetch(&self, search: &Search, page: u32) -> Result<ProviderPage> {
        let url = self.base.join("/skinlib/list")?;
        let response = self
            .http
            .get(url)
            .query(&[
                ("filter", if search.cape { "cape" } else { "skin" }),
                ("sort", "likes"),
                ("page", &page.to_string()),
                ("keyword", &search.keyword),
            ])
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await
            .context("请求皮肤库失败")?
            .error_for_status()
            .context("皮肤库返回错误")?;
        let result: ProviderPage = response.json().await.context("无法读取皮肤库列表")?;
        ensure!(result.current_page == page, "皮肤库返回了错误页码");
        Ok(result)
    }

    async fn search(&self, search: &Search, page: u32) -> Result<Page> {
        let first = self.fetch(search, page).await?;
        let next = first.next();
        let mut items = first.data;
        let next = if let Some(next) = next {
            tokio::time::sleep(PAGE_DELAY).await;
            let second = self.fetch(search, next).await?;
            let next = second.next();
            items.extend(second.data);
            next
        } else {
            None
        };
        Ok(Page { items, next })
    }

    async fn thumbnail(&self, id: i32) -> Result<RgbaImage> {
        let mut url = self.base.join(&format!("/preview/{id}"))?;
        url.query_pairs_mut()
            .append_pair("height", "150")
            .append_pair("png", "");
        let image = images::download_image(&self.http, url.as_str()).await?;
        tokio::task::spawn_blocking(move || {
            Ok(image.thumbnail(300, 150).to_rgba8())
        })
        .await?
    }
}

enum Event {
    Page(u64, Result<Page>),
    Thumbnail(u64, usize, Result<RgbaImage>),
}

pub struct Wardrobe {
    ui: slint::Weak<AppWindow>,
    runtime: Handle,
    items: Rc<VecModel<WardrobeItem>>,
    client: Option<LittleSkin>,
    search: Option<Search>,
    next_page: Option<u32>,
    epoch: u64,
    seen: HashSet<i32>,
    list_job: Option<JoinHandle<()>>,
    thumbnail_jobs: Vec<JoinHandle<()>>,
    tx: mpsc::Sender<Event>,
    rx: mpsc::Receiver<Event>,
}

impl Wardrobe {
    pub fn wire(ui: &AppWindow, runtime: Handle) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let items = Rc::new(VecModel::default());
        ui.set_wardrobe_items(ModelRc::from(items.clone()));
        let state = Rc::new(RefCell::new(Self {
            ui: ui.as_weak(),
            runtime,
            items,
            client: None,
            search: None,
            next_page: None,
            epoch: 0,
            seen: HashSet::new(),
            list_job: None,
            thumbnail_jobs: Vec::new(),
            tx,
            rx,
        }));
        let weak = Rc::downgrade(&state);
        ui.on_wardrobe_search(move |keyword, cape| {
            if let Some(state) = weak.upgrade() {
                let mut state = state.borrow_mut();
                if let Some(ui) = state.ui.upgrade() {
                    state.search(
                        &ui,
                        Search {
                            keyword: keyword.trim().to_owned(),
                            cape,
                        },
                    );
                }
            }
        });
        let weak = Rc::downgrade(&state);
        ui.on_wardrobe_retry(move || {
            if let Some(state) = weak.upgrade() {
                let mut state = state.borrow_mut();
                if let Some(ui) = state.ui.upgrade() {
                    state.load(&ui);
                }
            }
        });
        state
    }

    pub fn open(&mut self, ui: &AppWindow) {
        if self.search.is_none() {
            self.search(ui, Search::default());
        }
    }

    fn search(&mut self, ui: &AppWindow, search: Search) {
        self.epoch += 1;
        if let Some(job) = self.list_job.take() {
            job.abort();
        }
        for job in self.thumbnail_jobs.drain(..) {
            job.abort();
        }
        self.items.clear();
        self.seen.clear();
        self.search = Some(search);
        self.next_page = Some(1);
        self.load(ui);
    }

    fn load(&mut self, ui: &AppWindow) {
        let (Some(search), Some(page), None) =
            (self.search.clone(), self.next_page, &self.list_job)
        else {
            return;
        };
        if self.client.is_none() {
            match LittleSkin::new() {
                Ok(client) => self.client = Some(client),
                Err(error) => {
                    self.load_failed(ui, error);
                    return;
                }
            }
        }
        let Some(client) = self.client.clone() else {
            return;
        };
        let tx = self.tx.clone();
        let epoch = self.epoch;
        ui.set_wardrobe_error("".into());
        ui.set_wardrobe_loading(true);
        self.list_job = Some(self.runtime.spawn(async move {
            let _ = tx.send(Event::Page(epoch, client.search(&search, page).await));
        }));
    }

    fn load_failed(&self, ui: &AppWindow, error: anyhow::Error) {
        tracing::warn!(error = %format!("{error:#}"), "衣柜列表加载失败");
        ui.set_wardrobe_loading(false);
        ui.set_wardrobe_error(
            if self.items.row_count() == 0 {
                "衣柜加载失败，请重试"
            } else {
                "更多结果加载失败，请重试"
            }
            .into(),
        );
    }

    fn append(&mut self, page: Page) {
        self.next_page = page.next;
        let Some(client) = self.client.clone() else {
            return;
        };
        let mut thumbnails = Vec::new();
        for item in page.items {
            if item.tid <= 0 || !self.seen.insert(item.tid) {
                continue;
            }
            thumbnails.push((self.items.row_count(), item.tid));
            self.items.push(WardrobeItem {
                id: item.tid,
                name: item.name.into(),
                likes: item.likes,
                ..Default::default()
            });
        }
        if thumbnails.is_empty() {
            return;
        }
        let tx = self.tx.clone();
        let epoch = self.epoch;
        self.thumbnail_jobs.push(self.runtime.spawn(async move {
            let mut jobs = JoinSet::new();
            for (row, id) in thumbnails {
                let client = client.clone();
                let tx = tx.clone();
                jobs.spawn(async move {
                    let _ = tx.send(Event::Thumbnail(epoch, row, client.thumbnail(id).await));
                });
            }
            while jobs.join_next().await.is_some() {}
        }));
    }

    pub fn tick(&mut self) {
        let Some(ui) = self.ui.upgrade() else {
            return;
        };
        self.thumbnail_jobs.retain(|job| !job.is_finished());
        while let Ok(event) = self.rx.try_recv() {
            match event {
                Event::Page(epoch, result) if epoch == self.epoch => {
                    self.list_job = None;
                    ui.set_wardrobe_loading(false);
                    match result {
                        Ok(page) => self.append(page),
                        Err(error) => self.load_failed(&ui, error),
                    }
                }
                Event::Thumbnail(epoch, row, result) if epoch == self.epoch => {
                    if let Some(mut item) = self.items.row_data(row) {
                        match result {
                            Ok(image) => {
                                item.thumbnail = images::to_slint(&image);
                                item.image_state = 1;
                            }
                            Err(error) => {
                                tracing::debug!(texture_id = item.id, error = %format!("{error:#}"), "衣柜缩略图加载失败");
                                item.image_state = 3;
                            }
                        }
                        self.items.set_row_data(row, item);
                    }
                }
                _ => {}
            }
        }
        if ui.get_wardrobe_visible()
            && ui.get_wardrobe_near_end()
            && ui.get_wardrobe_error().is_empty()
        {
            self.load(&ui);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };

    async fn provider(responses: Vec<(&'static str, Value)>) -> (LittleSkin, JoinHandle<Vec<Url>>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = Url::parse(&format!("http://{}", listener.local_addr().unwrap())).unwrap();
        let test_base = base.clone();
        let job = tokio::spawn(async move {
            let mut requests = Vec::new();
            for (status, body) in responses {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut bytes = Vec::new();
                while !bytes.ends_with(b"\r\n\r\n") {
                    bytes.push(socket.read_u8().await.unwrap());
                }
                let request = String::from_utf8(bytes).unwrap();
                let target = request
                    .lines()
                    .next()
                    .unwrap()
                    .split_whitespace()
                    .nth(1)
                    .unwrap();
                requests.push(test_base.join(target).unwrap());
                let response = format!(
                    "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.to_string().len()
                );
                socket.write_all(response.as_bytes()).await.unwrap();
            }
            requests
        });
        (
            LittleSkin {
                http: Client::builder().no_proxy().build().unwrap(),
                base,
            },
            job,
        )
    }

    fn texture(id: usize) -> Value {
        json!({"tid": id, "name": format!("皮肤{id}"), "likes": 3})
    }

    #[tokio::test]
    async fn sends_chinese_keyword_and_stops_at_short_second_page() {
        let first: Vec<_> = (1..=PROVIDER_PAGE_SIZE).map(texture).collect();
        let (client, requests) = provider(vec![
            ("200 OK", json!({"current_page": 1, "data": first})),
            ("200 OK", json!({"current_page": 2, "data": [texture(21)]})),
        ])
        .await;
        let page = client
            .search(
                &Search {
                    keyword: "末影人 & 朋友".into(),
                    cape: true,
                },
                1,
            )
            .await
            .unwrap();
        assert_eq!(page.items.len(), 21);
        assert_eq!(page.next, None);
        for (index, request) in requests.await.unwrap().iter().enumerate() {
            assert_eq!(request.path(), "/skinlib/list");
            let query: std::collections::HashMap<_, _> = request.query_pairs().collect();
            assert_eq!(query["filter"], "cape");
            assert_eq!(query["sort"], "likes");
            assert_eq!(query["keyword"], "末影人 & 朋友");
            assert_eq!(query["page"], (index + 1).to_string());
        }
    }

    #[tokio::test]
    async fn distinguishes_provider_failure_from_empty_results() {
        let (client, requests) = provider(vec![
            ("503 Service Unavailable", json!({})),
            (
                "200 OK",
                json!({"current_page": 3, "last_page": 3, "data": []}),
            ),
        ])
        .await;
        assert!(client.search(&Search::default(), 3).await.is_err());
        let page = client.search(&Search::default(), 3).await.unwrap();
        assert!(page.items.is_empty());
        assert_eq!(page.next, None);
        for request in requests.await.unwrap() {
            assert!(
                request
                    .query_pairs()
                    .any(|(key, value)| key == "page" && value == "3")
            );
        }
    }
}
