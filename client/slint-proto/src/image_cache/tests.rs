use super::*;
use std::{io::Cursor, net::SocketAddr, sync::Mutex as StdMutex};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
};

struct Reply {
    status: &'static str,
    headers: Vec<(&'static str, &'static str)>,
    body: Vec<u8>,
}
fn reply(status: &'static str, headers: &[(&'static str, &'static str)], body: &[u8]) -> Reply {
    Reply { status, headers: headers.to_vec(), body: body.to_vec() }
}

/// Serves `replies` in order on 127.0.0.1 and records each raw request head.
struct Server {
    addr: SocketAddr,
    requests: Arc<StdMutex<Vec<String>>>,
    job: tokio::task::JoinHandle<()>,
}
impl Server {
    async fn start(replies: Vec<Reply>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let requests = Arc::new(StdMutex::new(Vec::new()));
        let seen = requests.clone();
        let job = tokio::spawn(async move {
            for r in replies {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut head = Vec::new();
                while !head.ends_with(b"\r\n\r\n") {
                    head.push(socket.read_u8().await.unwrap());
                }
                seen.lock().unwrap().push(String::from_utf8(head).unwrap().to_ascii_lowercase());
                let mut out = format!("HTTP/1.1 {}\r\nContent-Length: {}\r\nConnection: close\r\n", r.status, r.body.len());
                for (name, value) in &r.headers {
                    out.push_str(&format!("{name}: {value}\r\n"));
                }
                out.push_str("\r\n");
                socket.write_all(out.as_bytes()).await.unwrap();
                socket.write_all(&r.body).await.unwrap();
                socket.shutdown().await.ok();
            }
        });
        Self { addr, requests, job }
    }
    fn url(&self, path: &str) -> String {
        format!("http://{}{path}", self.addr)
    }
    fn count(&self) -> usize {
        self.requests.lock().unwrap().len()
    }
    fn request(&self, index: usize) -> String {
        self.requests.lock().unwrap()[index].clone()
    }
}

fn png(shade: u8) -> Vec<u8> {
    let mut bytes = Vec::new();
    image::RgbaImage::from_pixel(2, 2, image::Rgba([shade, 0, 0, 255]))
        .write_to(&mut Cursor::new(&mut bytes), image::ImageFormat::Png)
        .unwrap();
    bytes
}

fn temp_dir(name: &str) -> PathBuf {
    std::env::temp_dir().join(format!("rdi-image-cache-{name}-{}", uuid::Uuid::now_v7().simple()))
}

fn http() -> reqwest::Client {
    reqwest::Client::builder().no_proxy().build().unwrap()
}

fn cache(dir: &Path) -> Arc<ImageCache> {
    Arc::new(ImageCache::new(dir.to_path_buf(), CAPACITY))
}

const FRESH: &[(&str, &str)] = &[("cache-control", "max-age=3600"), ("etag", "\"a\"")];
const STALE_A: &[(&str, &str)] = &[("cache-control", "max-age=0"), ("etag", "\"a\"")];

#[tokio::test]
async fn fresh_entry_is_served_without_a_request() {
    let dir = temp_dir("fresh");
    let server = Server::start(vec![reply("200 OK", FRESH, &png(1))]).await;
    let c = cache(&dir);
    let url = server.url("/a.png");
    assert_eq!(c.get_with_source(&http(), &url).await.unwrap(), (png(1), Source::Downloaded));
    assert_eq!(c.get_with_source(&http(), &url).await.unwrap(), (png(1), Source::Fresh));
    assert_eq!(server.count(), 1);
    std::fs::remove_dir_all(&dir).ok();
}

#[tokio::test]
async fn stale_entry_revalidates_and_304_keeps_the_body() {
    let dir = temp_dir("revalidate");
    let server = Server::start(vec![reply("200 OK", STALE_A, &png(1)), reply("304 Not Modified", STALE_A, b"")]).await;
    let c = cache(&dir);
    let url = server.url("/a.png");
    c.get(&http(), &url).await.unwrap();
    assert_eq!(c.get_with_source(&http(), &url).await.unwrap(), (png(1), Source::Revalidated));
    assert!(server.request(1).contains("if-none-match: \"a\""), "{}", server.request(1));
    std::fs::remove_dir_all(&dir).ok();
}

#[tokio::test]
async fn changed_image_replaces_the_entry() {
    let dir = temp_dir("replace");
    let server = Server::start(vec![
        reply("200 OK", STALE_A, &png(1)),
        reply("200 OK", &[("cache-control", "max-age=0"), ("etag", "\"b\"")], &png(2)),
        reply("304 Not Modified", &[("etag", "\"b\"")], b""),
    ])
    .await;
    let c = cache(&dir);
    let url = server.url("/a.png");
    c.get(&http(), &url).await.unwrap();
    assert_eq!(c.get_with_source(&http(), &url).await.unwrap(), (png(2), Source::Downloaded));
    assert_eq!(c.get_with_source(&http(), &url).await.unwrap(), (png(2), Source::Revalidated));
    assert!(server.request(2).contains("if-none-match: \"b\""));
    std::fs::remove_dir_all(&dir).ok();
}

#[tokio::test]
async fn no_store_and_non_images_are_not_kept() {
    let dir = temp_dir("nostore");
    let server = Server::start(vec![
        reply("200 OK", &[("cache-control", "no-store")], &png(1)),
        reply("200 OK", &[("cache-control", "no-store")], &png(1)),
        reply("200 OK", FRESH, b"<html>login</html>"),
        reply("200 OK", FRESH, b"<html>login</html>"),
    ])
    .await;
    let c = cache(&dir);
    for path in ["/a.png", "/a.png", "/portal", "/portal"] {
        assert_eq!(c.get_with_source(&http(), &server.url(path)).await.unwrap().1, Source::Downloaded);
    }
    assert_eq!(server.count(), 4);
    assert!(std::fs::read_dir(&dir).map(|d| d.count()).unwrap_or(0) == 0);
    std::fs::remove_dir_all(&dir).ok();
}

#[tokio::test]
async fn network_failure_or_server_error_falls_back_to_the_stale_entry() {
    let dir = temp_dir("offline");
    let server = Server::start(vec![reply("200 OK", STALE_A, &png(1)), reply("503 Service Unavailable", &[], b"")]).await;
    let c = cache(&dir);
    let url = server.url("/a.png");
    c.get(&http(), &url).await.unwrap();
    assert_eq!(c.get_with_source(&http(), &url).await.unwrap(), (png(1), Source::Stale));
    // The server has served its replies and closed the port.
    let missing = server.url("/missing.png");
    server.job.await.unwrap();
    assert_eq!(c.get_with_source(&http(), &url).await.unwrap(), (png(1), Source::Stale));
    // Without an entry the error reaches the caller.
    assert!(c.get(&http(), &missing).await.is_err());
    std::fs::remove_dir_all(&dir).ok();
}

#[tokio::test]
async fn pinned_hosts_stay_fresh_for_15_days_then_revalidate() {
    let dir = temp_dir("pinned");
    let server = Server::start(vec![reply("200 OK", STALE_A, &png(1)), reply("304 Not Modified", STALE_A, b"")]).await;
    let http = reqwest::Client::builder().no_proxy().resolve("cdn.modrinth.com", server.addr).build().unwrap();
    let c = cache(&dir);
    let url = format!("http://cdn.modrinth.com:{}/data/x/icon.png", server.addr.port());
    c.get(&http, &url).await.unwrap();
    // max-age=0 would revalidate, but D2 keeps the icon fresh.
    assert_eq!(c.get_with_source(&http, &url).await.unwrap().1, Source::Fresh);
    assert_eq!(server.count(), 1);
    // 16 days later it is revalidated with the stored ETag.
    let meta_path = dir.join(format!("{}.json", key(&url)));
    let mut meta: Meta = serde_json::from_slice(&std::fs::read(&meta_path).unwrap()).unwrap();
    meta.checked -= 16 * 24 * 3600;
    std::fs::write(&meta_path, serde_json::to_vec(&meta).unwrap()).unwrap();
    assert_eq!(c.get_with_source(&http, &url).await.unwrap(), (png(1), Source::Revalidated));
    assert!(server.request(1).contains("if-none-match: \"a\""));
    std::fs::remove_dir_all(&dir).ok();
}

#[test]
fn pinned_hosts_are_forgecdn_mcmod_and_modrinth() {
    for url in [
        "https://media.forgecdn.net/avatars/1.png",
        "https://edge.forgecdn.net/files/1.png",
        "https://i.mcmod.cn/class/cover/1.jpg@170x115.jpg",
        "https://www.mcmod.cn/pages/1.png",
        "https://cdn.modrinth.com/data/AANobbMI/icon.png",
    ] {
        assert!(pinned(url), "{url}");
    }
    for url in ["https://littleskin.cn/preview/1", "https://evilforgecdn.net/1.png", "https://modrinth.com/x.png", "not a url"] {
        assert!(!pinned(url), "{url}");
    }
}

#[tokio::test]
async fn damaged_entries_are_ignored() {
    let dir = temp_dir("damaged");
    let server = Server::start(vec![
        reply("200 OK", FRESH, &png(1)),
        reply("200 OK", FRESH, &png(2)),
        reply("200 OK", FRESH, &png(3)),
    ])
    .await;
    let c = cache(&dir);
    let url = server.url("/a.png");
    c.get(&http(), &url).await.unwrap();
    let (body, meta) = c.paths(&key(&url));
    std::fs::write(&meta, b"{broken").unwrap();
    assert_eq!(c.get_with_source(&http(), &url).await.unwrap(), (png(2), Source::Downloaded));
    // A truncated body no longer matches the stored size.
    std::fs::write(&body, &png(2)[..10]).unwrap();
    assert_eq!(c.get_with_source(&http(), &url).await.unwrap(), (png(3), Source::Downloaded));
    std::fs::remove_dir_all(&dir).ok();
}

#[tokio::test]
async fn concurrent_requests_for_one_url_download_once() {
    let dir = temp_dir("flight");
    let server = Server::start(vec![reply("200 OK", FRESH, &png(1))]).await;
    let c = cache(&dir);
    let url = server.url("/a.png");
    let client = http();
    let results = futures_join(&c, &client, &url).await;
    assert!(results.iter().all(|b| b == &png(1)));
    assert_eq!(server.count(), 1);
    assert!(c.flights.lock().unwrap().is_empty());
    std::fs::remove_dir_all(&dir).ok();
}
async fn futures_join(c: &Arc<ImageCache>, http: &reqwest::Client, url: &str) -> Vec<Vec<u8>> {
    let (a, b, d) = tokio::join!(c.get(http, url), c.get(http, url), c.get(http, url));
    vec![a.unwrap(), b.unwrap(), d.unwrap()]
}

#[test]
fn eviction_removes_least_recently_used_and_orphans() {
    let dir = temp_dir("evict");
    std::fs::create_dir_all(&dir).unwrap();
    let now = SystemTime::now();
    let entry = |key: &str, size: usize, age_secs: u64| {
        let body = dir.join(format!("{key}.img"));
        std::fs::write(&body, vec![0u8; size]).unwrap();
        std::fs::write(dir.join(format!("{key}.json")), b"{}").unwrap();
        std::fs::File::options().write(true).open(&body).unwrap().set_modified(now - Duration::from_secs(age_secs)).unwrap();
    };
    entry("old", 400, 300);
    entry("mid", 400, 200);
    entry("new", 400, 100);
    // Orphans: an old body without metadata is deleted, a young temp file is kept.
    let orphan = dir.join("orphan.img");
    std::fs::write(&orphan, b"x").unwrap();
    std::fs::File::options().write(true).open(&orphan).unwrap().set_modified(now - Duration::from_secs(120)).unwrap();
    let young_temp = dir.join("k.img.tmp0");
    std::fs::write(&young_temp, b"x").unwrap();

    // 3 x 402 bytes = 1206 > 1000; down to 900 removes only the oldest.
    let total = evict(&dir, 1000, now).unwrap();
    assert_eq!(total, 804);
    assert!(!dir.join("old.img").exists() && !dir.join("old.json").exists());
    assert!(dir.join("mid.img").exists() && dir.join("new.img").exists());
    assert!(!orphan.exists());
    assert!(young_temp.exists());
    // Under the cap nothing more is removed.
    assert_eq!(evict(&dir, 1000, now).unwrap(), 804);
    std::fs::remove_dir_all(&dir).ok();
}

#[test]
fn clear_counts_images_and_bytes() {
    let dir = temp_dir("clear");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("a.img"), [0u8; 10]).unwrap();
    std::fs::write(dir.join("a.json"), [0u8; 5]).unwrap();
    assert_eq!(clear(&dir).unwrap(), (1, 15));
    assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0);
    assert_eq!(clear(&dir.join("missing")).unwrap(), (0, 0));
    std::fs::remove_dir_all(&dir).ok();
}
