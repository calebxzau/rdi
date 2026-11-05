use crate::api::{
    Client,
    model::{Cloth, PlayerInfo},
};
use anyhow::{Context, Result};
use image::{DynamicImage, RgbaImage, imageops::FilterType};
use rdi_slint_proto::scene::{
    crowd_sim::MAX_PLAYERS,
    player::{Appearance, Skin, normalize},
};
use std::{
    collections::HashMap,
    io::Cursor,
    sync::{Arc, LazyLock},
    time::Duration,
};
use tokio::sync::{Mutex, OnceCell, Semaphore};
pub fn decode(bytes: &[u8]) -> Result<DynamicImage> {
    let format = image::guess_format(bytes).context("无法识别图片格式")?;
    let mut reader = image::ImageReader::with_format(Cursor::new(bytes), format);
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(8192);
    limits.max_image_height = Some(8192);
    limits.max_alloc = Some(128 * 1024 * 1024);
    reader.limits(limits);
    reader.decode().context("图片解码失败")
}
pub fn head(skin: &RgbaImage) -> Result<RgbaImage> {
    anyhow::ensure!(skin.width() >= 64 && skin.height() >= 32, "皮肤尺寸无效");
    let mut face = image::imageops::crop_imm(skin, 8, 8, 8, 8).to_image();
    let hat = image::imageops::crop_imm(skin, 40, 8, 8, 8).to_image();
    image::imageops::overlay(&mut face, &hat, 0, 0);
    // The face layer is opaque in Minecraft; blending the hat must not leave 254-alpha pixels.
    for p in face.pixels_mut() {
        p[3] = 255;
    }
    Ok(face)
}
pub fn steve() -> Result<RgbaImage> {
    head(
        &decode(include_bytes!(
            "../../ui/assets/src/main/resources/assets/skins/steve.png"
        ))?
        .to_rgba8(),
    )
}
pub fn to_slint(image: &RgbaImage) -> slint::Image {
    slint::Image::from_rgba8(
        slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(
            image.as_raw(),
            image.width(),
            image.height(),
        ),
    )
}
/// media.forgecdn.net resets TLS handshakes beyond about five parallel connections from one address
/// (curl behaves the same), so downloads are limited per host and connect errors are retried.
const DOWNLOADS_PER_HOST: usize = 3;
const CONNECT_RETRIES: u32 = 2;
static HOST_LIMITS: LazyLock<std::sync::Mutex<HashMap<String, Arc<Semaphore>>>> =
    LazyLock::new(Default::default);
fn host_limit(url: &str) -> Arc<Semaphore> {
    let host = reqwest::Url::parse(url)
        .ok()
        .and_then(|u| u.host_str().map(str::to_owned))
        .unwrap_or_default();
    let mut limits = HOST_LIMITS.lock().unwrap_or_else(|e| e.into_inner());
    limits
        .entry(host)
        .or_insert_with(|| Arc::new(Semaphore::new(DOWNLOADS_PER_HOST)))
        .clone()
}
pub async fn download(http: &reqwest::Client, url: &str) -> Result<Vec<u8>> {
    let _permit = host_limit(url).acquire_owned().await?;
    fetch(http, url).await
}
async fn fetch(http: &reqwest::Client, url: &str) -> Result<Vec<u8>> {
    let mut attempt = 0;
    let response = loop {
        match http.get(url).send().await {
            Err(e) if (e.is_connect() || e.is_timeout()) && attempt < CONNECT_RETRIES => {
                attempt += 1;
                tracing::debug!(attempt, error = %e.without_url(), "Image request retry");
                tokio::time::sleep(std::time::Duration::from_millis(400 * u64::from(attempt)))
                    .await;
            }
            result => break result,
        }
    };
    let mut r = response
        .map_err(|e| anyhow::Error::new(e.without_url()).context("图片请求失败"))?
        .error_for_status()
        .map_err(|e| anyhow::Error::new(e.without_url()).context("图片HTTP错误"))?;
    let mut bytes = Vec::new();
    while let Some(chunk) = r.chunk().await? {
        anyhow::ensure!(
            bytes.len() + chunk.len() <= 16 * 1024 * 1024,
            "图片超过16MiB"
        );
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}
/// Account appearance is independent of menu loading and uses the shared per-host limiter.
pub async fn background_appearance(
    http: &reqwest::Client,
    cloth: Option<Cloth>,
) -> Result<Appearance> {
    let (skin_url, cape_url, slim) =
        cloth.map_or((None, None, None), |c| (c.skin, c.cape, c.is_slim));
    let skin_url = skin_url.filter(|s| !s.trim().is_empty());
    let cape_url = cape_url.filter(|s| !s.trim().is_empty());
    let skin = async {
        if let Some(url) = skin_url {
            let result = async {
                let bytes = download(http, url.trim()).await?;
                tokio::task::spawn_blocking(move || Skin::new(decode(&bytes)?.to_rgba8(), slim))
                    .await?
            }
            .await;
            match result {
                Ok(skin) => return Ok::<_, anyhow::Error>(skin),
                Err(error) => {
                    tracing::warn!(error=%format!("{error:#}"),"Account player skin failed; using Steve")
                }
            }
        }
        Ok(Appearance::steve()?.skin)
    };
    let cape = async {
        if let Some(url) = cape_url {
            let result = async {
                let bytes = download(http, url.trim()).await?;
                tokio::task::spawn_blocking(move || normalize(decode(&bytes)?.to_rgba8(), true))
                    .await?
            }
            .await;
            match result {
                Ok(cape) => return Some(cape),
                Err(error) => {
                    tracing::warn!(error=%format!("{error:#}"),"Account player cape failed; continuing without cape")
                }
            }
        }
        None
    };
    let (skin, cape) = tokio::join!(skin, cape);
    Ok(Appearance { skin: skin?, cape })
}
const CROWD_DOWNLOADS: usize = 4;
const CROWD_TIMEOUT: Duration = Duration::from_secs(15);
fn crowd_urls(urls: Vec<String>) -> Vec<String> {
    let mut unique: Vec<String> = Vec::with_capacity(urls.len().min(MAX_PLAYERS));
    for url in urls {
        let url = url.trim();
        if unique.len() < MAX_PLAYERS && !url.is_empty() && !unique.iter().any(|u| u == url) {
            unique.push(url.to_owned());
        }
    }
    unique
}
/// The timeout starts after the host permit, so a long queue cannot fail a skin that never started.
async fn crowd_skin(http: &reqwest::Client, url: &str) -> Result<Skin> {
    let bytes = {
        let _permit = host_limit(url).acquire_owned().await?;
        tokio::time::timeout(CROWD_TIMEOUT, fetch(http, url))
            .await
            .context("皮肤下载超时")??
    };
    tokio::task::spawn_blocking(move || Skin::new(decode(&bytes)?.to_rgba8(), None)).await?
}
/// Publishes each skin as it finishes. Failures only leave Steve or the skins that already arrived.
pub async fn background_crowd(api: &Client, mut publish: impl FnMut(String, Skin)) {
    let urls = match tokio::time::timeout(
        CROWD_TIMEOUT,
        api.get::<Vec<String>>("/player/unique-skin-list"),
    )
    .await
    {
        Ok(Ok(urls)) => crowd_urls(urls),
        Ok(Err(error)) => {
            tracing::warn!(error=%format!("{error:#}"),"Crowd skin list failed; using Steve");
            return;
        }
        Err(error) => {
            tracing::warn!(%error,"Crowd skin list timed out; using Steve");
            return;
        }
    };
    tracing::info!(skins = urls.len(), "Crowd skin list loaded");
    let downloads = Arc::new(Semaphore::new(CROWD_DOWNLOADS));
    let mut tasks = tokio::task::JoinSet::new();
    for url in urls {
        let http = api.http.clone();
        let downloads = downloads.clone();
        tasks.spawn(async move {
            let result = async {
                let _permit = downloads.acquire_owned().await?;
                crowd_skin(&http, &url).await
            }
            .await;
            (url, result)
        });
    }
    let (mut loaded, mut failed) = (0, 0);
    while let Some(finished) = tasks.join_next().await {
        match finished {
            Ok((url, Ok(skin))) => {
                loaded += 1;
                publish(url, skin);
            }
            Ok((url, Err(error))) => {
                failed += 1;
                tracing::warn!(%url,error=%format!("{error:#}"),"Crowd skipped a skin");
            }
            Err(error) => {
                failed += 1;
                tracing::error!(%error,"Crowd skin task failed");
            }
        }
    }
    tracing::info!(loaded, failed, "Crowd skins finished");
}
/// Icon corner radius relative to its size: 13dp on the 108dp icon box (TileIconShape in Kotlin).
const ICON_CORNER_RATIO: f32 = 13.0 / 108.0;
/// Downloads an icon and returns it cropped to `size`² with rounded corners baked in, so the tile
/// needs no rounded `clip` (femtovg renders each rounded clip through its own offscreen layer).
pub async fn icon(http: &reqwest::Client, url: &str, size: u32) -> Result<RgbaImage> {
    let bytes = download(http, url).await?;
    tokio::task::spawn_blocking(move || {
        let image = decode(&bytes)?;
        let mut icon = image
            .resize_to_fill(size, size, FilterType::CatmullRom)
            .to_rgba8();
        round_corners(&mut icon, size as f32 * ICON_CORNER_RATIO);
        Ok(icon)
    })
    .await?
}
type Cell = Arc<OnceCell<RgbaImage>>;
/// Fades the corners outside a `radius` rounded rect to transparent, anti-aliased over one pixel.
pub fn round_corners(image: &mut RgbaImage, radius: f32) {
    let (w, h) = (image.width() as f32, image.height() as f32);
    let r = radius.min(w / 2.0).min(h / 2.0);
    for (x, y, p) in image.enumerate_pixels_mut() {
        // Distance from the pixel centre to the nearest corner circle's centre, per axis.
        let dx = (r - (x as f32 + 0.5))
            .max(x as f32 + 0.5 - (w - r))
            .max(0.0);
        let dy = (r - (y as f32 + 0.5))
            .max(y as f32 + 0.5 - (h - r))
            .max(0.0);
        let coverage = (r - (dx * dx + dy * dy).sqrt() + 0.5).clamp(0.0, 1.0);
        if coverage < 1.0 {
            p[3] = (p[3] as f32 * coverage).round() as u8;
        }
    }
}
#[derive(Default)]
struct Heads {
    /// Player id -> head; each id is fetched once even when many tiles ask concurrently.
    heads: HashMap<String, Cell>,
    /// Skin URL -> head; players sharing a skin download it once.
    skins: HashMap<String, Cell>,
}
#[derive(Clone, Default)]
pub struct HeadCache(Arc<Mutex<Heads>>);
impl HeadCache {
    pub async fn get(&self, api: &Client, uid: &str) -> Result<RgbaImage> {
        // The lock only guards the maps; network requests for different players run in parallel.
        let cell = self
            .0
            .lock()
            .await
            .heads
            .entry(uid.into())
            .or_default()
            .clone();
        let head = cell
            .get_or_try_init(|| async {
                let player: PlayerInfo = api.get(&format!("/player/{uid}/info")).await?;
                match player.cloth.and_then(|c| c.skin).filter(|s| !s.is_empty()) {
                    Some(url) => self.skin(api, url).await,
                    None => steve(),
                }
            })
            .await?;
        Ok(head.clone())
    }
    async fn skin(&self, api: &Client, url: String) -> Result<RgbaImage> {
        let cell = self
            .0
            .lock()
            .await
            .skins
            .entry(url.clone())
            .or_default()
            .clone();
        let head = cell
            .get_or_try_init(|| async {
                let bytes = download(&api.http, &url).await?;
                head(&decode(&bytes)?.to_rgba8())
            })
            .await?;
        Ok(head.clone())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn appearance_absence_and_http_failure_keep_steve() {
        use std::io::{Read, Write};
        let http = reqwest::Client::new();
        let missing = background_appearance(&http, None).await.unwrap();
        assert!(!missing.skin.slim && missing.cape.is_none());
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let mock = std::thread::spawn(move || {
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut request = [0u8; 2048];
                let received = stream.read(&mut request).unwrap();
                assert!(received > 0);
                stream
                    .write_all(
                        b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                    )
                    .unwrap();
            }
        });
        let failed = background_appearance(
            &http,
            Some(Cloth {
                skin: Some(format!("{url}/skin")),
                cape: Some(format!("{url}/cape")),
                is_slim: Some(true),
            }),
        )
        .await
        .unwrap();
        mock.join().unwrap();
        assert!(!failed.skin.slim && failed.cape.is_none());
        assert_eq!(failed.skin.pixels, missing.skin.pixels);
    }
    #[test]
    fn crowd_urls_are_trimmed_unique_and_capped() {
        let urls = crowd_urls(vec![
            " https://a/1 ".into(),
            "https://a/1".into(),
            "".into(),
            "  ".into(),
            "https://a/2".into(),
        ]);
        assert_eq!(urls, ["https://a/1", "https://a/2"]);
        let many = crowd_urls((0..80).map(|i| format!("https://a/{i}")).collect());
        assert_eq!(many.len(), MAX_PLAYERS);
        assert_eq!(many[49], "https://a/49");
    }
    #[tokio::test]
    async fn crowd_skin_decodes_and_reports_http_failure() {
        use std::io::{Read, Write};
        let mut png = Vec::new();
        image::RgbaImage::new(128, 64)
            .write_to(&mut Cursor::new(&mut png), image::ImageFormat::Png)
            .unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let mock = std::thread::spawn(move || {
            for status in ["200 OK", "404 Not Found"] {
                let (mut stream, _) = listener.accept().unwrap();
                let mut request = [0u8; 2048];
                assert!(stream.read(&mut request).unwrap() > 0);
                let body: &[u8] = if status.starts_with("200") { &png } else { &[] };
                let head = format!(
                    "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                stream.write_all(head.as_bytes()).unwrap();
                stream.write_all(body).unwrap();
            }
        });
        let http = reqwest::Client::new();
        let skin = crowd_skin(&http, &format!("{url}/hd-legacy")).await.unwrap();
        assert_eq!(skin.pixels.dimensions(), (64, 32));
        assert!(!skin.slim);
        assert!(crowd_skin(&http, &format!("{url}/missing")).await.is_err());
        mock.join().unwrap();
    }
    #[test]
    fn face_and_hat_legacy_and_modern() {
        for height in [32, 64] {
            let mut skin = RgbaImage::new(64, height);
            for y in 8..16 {
                for x in 8..16 {
                    skin.put_pixel(x, y, image::Rgba([200, 0, 0, 255]));
                    skin.put_pixel(x + 32, y, image::Rgba([0, 200, 0, 128]));
                }
            }
            let h = head(&skin).unwrap();
            assert_eq!(h.dimensions(), (8, 8));
            let p = h.get_pixel(0, 0);
            assert!((p[0] as i32 - 99).abs() <= 1);
            assert!((p[1] as i32 - 100).abs() <= 1);
            assert_eq!(p[3], 255);
        }
    }
    #[test]
    fn rounded_corners() {
        let mut img = RgbaImage::from_pixel(20, 20, image::Rgba([10, 20, 30, 255]));
        round_corners(&mut img, 5.0);
        assert_eq!(img.get_pixel(0, 0)[3], 0);
        assert_eq!(img.get_pixel(19, 19)[3], 0);
        assert_eq!(img.get_pixel(10, 0)[3], 255);
        assert_eq!(img.get_pixel(10, 10)[3], 255);
        let edge = img.get_pixel(1, 1)[3];
        assert!(
            edge < 255,
            "corner pixel near the arc is partly covered: {edge}"
        );
        assert_eq!(
            img.get_pixel(0, 0)[0],
            10,
            "colour is untouched, only alpha fades"
        );
    }
    #[test]
    fn sniff_without_extension() {
        let img = steve().unwrap();
        assert_eq!(img.dimensions(), (8, 8));
        assert!(decode(b"broken").is_err());
    }
}
