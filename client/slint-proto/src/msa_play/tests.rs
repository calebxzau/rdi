use super::*;
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

const MSID: &str = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
const NOW: i64 = 1_800_000_000_000;
const HOUR: i64 = 3_600_000;

fn fixed_now() -> i64 {
    NOW
}

type Routes = Arc<Mutex<HashMap<&'static str, VecDeque<String>>>>;

/// live.com, Xbox and Minecraft on one local port; each path answers from its queue and the
/// last answer repeats. Every request's path and body are recorded.
struct Mock {
    base: String,
    routes: Routes,
    seen: Arc<Mutex<Vec<(String, String)>>>,
}

impl Mock {
    async fn start() -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let routes: Routes = Arc::default();
        let seen: Arc<Mutex<Vec<(String, String)>>> = Arc::default();
        let (r, s) = (routes.clone(), seen.clone());
        tokio::spawn(async move {
            loop {
                let Ok((mut socket, _)) = listener.accept().await else { return };
                let (routes, seen) = (r.clone(), s.clone());
                tokio::spawn(async move {
                    let (path, body) = read_request(&mut socket).await;
                    seen.lock().unwrap().push((path.clone(), body));
                    let reply = {
                        let mut routes = routes.lock().unwrap();
                        let queue = routes.iter_mut().find(|(p, _)| **p == path).map(|(_, q)| q);
                        match queue {
                            Some(q) if q.len() > 1 => q.pop_front().unwrap(),
                            Some(q) => q.front().cloned().unwrap_or_default(),
                            None => "{}".into(),
                        }
                    };
                    let response = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{reply}",
                        reply.len()
                    );
                    let _ = socket.write_all(response.as_bytes()).await;
                });
            }
        });
        Self { base, routes, seen }
    }

    fn on(&self, path: &'static str, replies: &[&str]) {
        self.routes.lock().unwrap().insert(path, replies.iter().map(|r| r.to_string()).collect());
    }

    /// A Microsoft account whose Minecraft profile is `id`/`name`.
    fn account(&self, id: &str, name: &str, mc_token: &str) {
        self.on("/user/authenticate", &[r#"{"Token":"xbl","DisplayClaims":{"xui":[{"uhs":"hash"}]}}"#]);
        self.on("/xsts/authorize", &[r#"{"Token":"xsts"}"#]);
        let login = format!(r#"{{"access_token":"{mc_token}","expires_in":86400}}"#);
        self.on("/authentication/login_with_xbox", &[login.as_str()]);
        let profile = format!(r#"{{"id":"{id}","name":"{name}"}}"#);
        self.on("/minecraft/profile", &[profile.as_str()]);
    }

    fn device_code(&self) {
        self.on(
            "/oauth20_connect.srf",
            &[r#"{"device_code":"dc","user_code":"UC","verification_uri":"https://www.microsoft.com/link","interval":1,"expires_in":60}"#],
        );
    }

    fn paths(&self) -> Vec<String> {
        self.seen.lock().unwrap().iter().map(|(p, _)| p.clone()).collect()
    }

    fn bodies(&self, path: &str) -> Vec<String> {
        self.seen.lock().unwrap().iter().filter(|(p, _)| p == path).map(|(_, b)| b.clone()).collect()
    }

    fn resolver(&self, file: &Path) -> Resolver {
        let base = self.base.clone();
        Resolver {
            http: reqwest::Client::builder().no_proxy().build().unwrap(),
            endpoints: Endpoints { live: base.clone(), xbox_user: base.clone(), xsts: base.clone(), minecraft: base },
            path: file.to_owned(),
            now_ms: fixed_now,
        }
    }
}

async fn read_request(socket: &mut tokio::net::TcpStream) -> (String, String) {
    let mut bytes = Vec::new();
    let mut buffer = [0u8; 4096];
    loop {
        let n = socket.read(&mut buffer).await.unwrap_or(0);
        bytes.extend_from_slice(&buffer[..n]);
        let text = String::from_utf8_lossy(&bytes).to_string();
        if let Some(end) = text.find("\r\n\r\n") {
            let length = text[..end]
                .lines()
                .find_map(|l| l.to_ascii_lowercase().strip_prefix("content-length:").map(|v| v.trim().parse::<usize>().unwrap_or(0)))
                .unwrap_or(0);
            if bytes.len() >= end + 4 + length || n == 0 {
                let target = text.split_whitespace().nth(1).unwrap_or_default();
                let path = target.split('?').next().unwrap_or_default().to_owned();
                return (path, text[end + 4..].to_owned());
            }
        }
        if n == 0 {
            return (String::new(), String::new());
        }
    }
}

fn temp_file() -> PathBuf {
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let dir = std::env::temp_dir().join(format!(
        "j5c-msa-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    ));
    std::fs::create_dir_all(&dir).unwrap();
    dir.join(FILE_NAME)
}

fn saved(expires_at: i64) -> Saved {
    Saved { refresh_token: "r-old".into(), mc_token: "mc-old".into(), mc_token_expires_at: expires_at, name: "Notch".into() }
}

#[tokio::test]
async fn fresh_login_saves_the_session() {
    let mock = Mock::start().await;
    mock.device_code();
    mock.on("/oauth20_token.srf", &[r#"{"error":"authorization_pending"}"#, r#"{"access_token":"live","refresh_token":"r-1"}"#]);
    mock.account("069A79F444E94726A5BEFCA90E38AAF5", "Notch", "mc-1");
    let file = temp_file();
    let mut link = String::new();
    let account = mock.resolver(&file).resolve("acc", MSID, |l| link = l).await.unwrap();
    assert_eq!(link, "https://www.microsoft.com/link?otc=UC");
    assert_eq!((account.name.as_str(), account.uuid.as_str(), account.access_token.as_str(), account.user_type.as_str()), ("Notch", MSID, "mc-1", "msa"));
    let stored = find(&file, "acc", MSID).unwrap().unwrap();
    assert!(stored == Saved { refresh_token: "r-1".into(), mc_token: "mc-1".into(), mc_token_expires_at: NOW + 86_400_000, name: "Notch".into() });
    // The Switch title id with the device-code grant, as binding uses.
    let polls = mock.bodies("/oauth20_token.srf");
    assert!(polls.iter().all(|b| b.contains("client_id=00000000441cc96b") && b.contains("device_code=dc")));
}

#[tokio::test]
async fn a_token_with_time_left_needs_no_network() {
    let mock = Mock::start().await;
    let file = temp_file();
    save(&file, "acc", MSID, saved(NOW + 2 * HOUR), NOW).unwrap();
    let account = mock.resolver(&file).resolve("acc", "069a79f444e94726a5befca90e38aaf5", |_| panic!("no login")).await.unwrap();
    assert_eq!((account.access_token.as_str(), account.uuid.as_str()), ("mc-old", MSID));
    assert!(mock.paths().is_empty());
}

#[tokio::test]
async fn an_expiring_token_is_refreshed_and_the_new_refresh_token_kept() {
    let mock = Mock::start().await;
    mock.on("/oauth20_token.srf", &[r#"{"access_token":"live","refresh_token":"r-new"}"#]);
    mock.account("069a79f444e94726a5befca90e38aaf5", "Notch", "mc-2");
    let file = temp_file();
    save(&file, "acc", MSID, saved(NOW + HOUR / 2), NOW).unwrap();
    let account = mock.resolver(&file).resolve("acc", MSID, |_| panic!("no login")).await.unwrap();
    assert_eq!(account.access_token, "mc-2");
    let body = &mock.bodies("/oauth20_token.srf")[0];
    assert!(body.contains("grant_type=refresh_token") && body.contains("refresh_token=r-old"));
    assert!(!mock.paths().contains(&"/oauth20_connect.srf".to_string()));
    let stored = find(&file, "acc", MSID).unwrap().unwrap();
    assert_eq!((stored.refresh_token.as_str(), stored.mc_token.as_str(), stored.mc_token_expires_at), ("r-new", "mc-2", NOW + 86_400_000));
}

#[tokio::test]
async fn a_refresh_without_a_new_refresh_token_keeps_the_old_one() {
    let mock = Mock::start().await;
    mock.on("/oauth20_token.srf", &[r#"{"access_token":"live"}"#]);
    mock.account(MSID, "Notch", "mc-2");
    let file = temp_file();
    save(&file, "acc", MSID, saved(NOW - HOUR), NOW).unwrap();
    mock.resolver(&file).resolve("acc", MSID, |_| panic!("no login")).await.unwrap();
    assert_eq!(find(&file, "acc", MSID).unwrap().unwrap().refresh_token, "r-old");
}

#[tokio::test]
async fn a_rejected_refresh_falls_back_to_a_new_login() {
    let mock = Mock::start().await;
    mock.device_code();
    mock.on("/oauth20_token.srf", &[r#"{"error":"invalid_grant"}"#, r#"{"access_token":"live","refresh_token":"r-3"}"#]);
    mock.account(MSID, "Notch", "mc-3");
    let file = temp_file();
    save(&file, "acc", MSID, saved(NOW - HOUR), NOW).unwrap();
    let mut asked = false;
    let account = mock.resolver(&file).resolve("acc", MSID, |_| asked = true).await.unwrap();
    assert!(asked);
    assert_eq!(account.access_token, "mc-3");
    assert_eq!(find(&file, "acc", MSID).unwrap().unwrap().refresh_token, "r-3");
}

#[tokio::test]
async fn a_saved_session_of_another_profile_is_not_used() {
    let mock = Mock::start().await;
    mock.device_code();
    mock.on("/oauth20_token.srf", &[r#"{"access_token":"live","refresh_token":"r-x"}"#]);
    mock.account("11111111222233334444555555555555", "Other", "mc-x");
    let file = temp_file();
    save(&file, "acc", MSID, saved(NOW - HOUR), NOW).unwrap();
    let mut asked = false;
    let error = mock.resolver(&file).resolve("acc", MSID, |_| asked = true).await.err().expect("the resolve must fail");
    // The refresh gave another profile, so a login was asked for; it was the other profile again.
    assert!(asked);
    assert_eq!(error.to_string(), "登录的正版账号Other与RDI账号绑定的正版账号不一致");
    assert!(find(&file, "acc", MSID).unwrap().unwrap() == saved(NOW - HOUR));
}

#[tokio::test]
async fn a_damaged_file_is_set_aside_and_the_login_saved() {
    let mock = Mock::start().await;
    mock.device_code();
    mock.on("/oauth20_token.srf", &[r#"{"access_token":"live","refresh_token":"r-4"}"#]);
    mock.account(MSID, "Notch", "mc-4");
    let file = temp_file();
    std::fs::write(&file, "{not json").unwrap();
    mock.resolver(&file).resolve("acc", MSID, |_| {}).await.unwrap();
    assert_eq!(find(&file, "acc", MSID).unwrap().unwrap().mc_token, "mc-4");
    let backup = file.with_file_name(format!("{FILE_NAME}.broken-{NOW}"));
    assert_eq!(std::fs::read_to_string(backup).unwrap(), "{not json");
}

#[tokio::test]
async fn a_failed_login_reports_the_reason_and_saves_nothing() {
    let mock = Mock::start().await;
    mock.device_code();
    mock.on("/oauth20_token.srf", &[r#"{"error":"authorization_declined"}"#]);
    let file = temp_file();
    let error = mock.resolver(&file).resolve("acc", MSID, |_| {}).await.err().expect("the resolve must fail");
    assert_eq!(error.to_string(), "已在浏览器中取消登录");
    assert!(!file.exists());
}

#[test]
fn clear_removes_one_account_and_keeps_the_rest() {
    let file = temp_file();
    std::fs::write(&file, r#"{"accounts":{"a":{"m":{"refreshToken":"r","mcToken":"t","mcTokenExpiresAt":1,"name":"A"}},"b":{"m":{"refreshToken":"r","mcToken":"t","mcTokenExpiresAt":1,"name":"B"}}},"future":7}"#).unwrap();
    clear(&file, "a").unwrap();
    let value: Value = serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
    assert!(value["accounts"].get("a").is_none());
    assert_eq!(value["accounts"]["b"]["m"]["name"], "B");
    assert_eq!(value["future"], 7);
    let before = std::fs::read(&file).unwrap();
    clear(&file, "absent").unwrap();
    assert_eq!(before, std::fs::read(&file).unwrap());
    let missing = temp_file();
    clear(&missing, "a").unwrap();
    assert!(!missing.exists());
}

#[test]
fn msids_are_compared_dashed_and_lowercase() {
    assert_eq!(normalize_msid("069A79F444E94726A5BEFCA90E38AAF5").unwrap(), MSID);
    assert_eq!(normalize_msid(MSID).unwrap(), MSID);
    assert!(normalize_msid("nope").is_err());
}
