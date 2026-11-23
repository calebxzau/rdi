//! Direct server access. Never expose response bodies or authentication headers in diagnostics.
use anyhow::{Context, Result, bail};
use reqwest::{Client, Response};
use serde::{Deserialize, de::DeserializeOwned};
use serde_json::{Value, json};
use std::{path::Path, time::Duration};

#[derive(Clone)]
pub struct ServerApi {
    pub http: Client,
    base: String,
    jwt: Option<String>,
}
pub struct Session {
    pub account: Value,
    jwt: String,
}
impl Session {
    pub fn uid(&self) -> Result<&str> {
        self.account["_id"].as_str().context("服务器数据格式不兼容")
    }
    pub fn params(mut self) -> Value {
        self.account["pwd"] = json!("");
        json!({"account":self.account,"jwt":self.jwt})
    }
}
#[derive(Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ProxyConfig {
    enabled: bool,
    system_proxy: bool,
    host: String,
    port: i64,
    usr: String,
    pwd: String,
}
#[derive(Default, Deserialize)]
struct Config {
    #[serde(rename = "proxyConfig")]
    proxy: Option<ProxyConfig>,
}
#[derive(PartialEq, Eq)]
pub enum ProxyPolicy {
    None,
    System,
    Custom,
}
pub fn proxy_policy(config: Option<&ProxyConfig>) -> ProxyPolicy {
    match config {
        Some(c) if c.enabled && c.system_proxy => ProxyPolicy::System,
        Some(c) if c.enabled && !c.host.trim().is_empty() && c.port > 0 => ProxyPolicy::Custom,
        _ => ProxyPolicy::None,
    }
}
/// Kotlin's TOML encoder writes absent optional values as a bare `null`, which is not valid TOML.
/// A missing key means the same thing here, so those lines are dropped before parsing.
fn parse_config(text: &str) -> std::result::Result<Config, toml::de::Error> {
    let text = text
        .lines()
        .filter(|line| {
            !line
                .split_once('=')
                .is_some_and(|(_, value)| value.trim() == "null")
        })
        .collect::<Vec<_>>()
        .join("\n");
    toml::from_str(&text)
}
fn client(config: Option<&ProxyConfig>) -> Result<Client> {
    let mut builder = Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(60))
        .user_agent(concat!("rdi-launcher/", env!("RDI_APP_VERSION")));
    match proxy_policy(config) {
        ProxyPolicy::None => builder = builder.no_proxy(),
        ProxyPolicy::System => {}
        ProxyPolicy::Custom => {
            let c = config.context("代理设置缺失")?;
            let host = c.host.trim();
            let host = if host.contains(':') && !host.starts_with('[') {
                format!("[{host}]")
            } else {
                host.into()
            };
            let mut proxy = reqwest::Proxy::all(format!("http://{host}:{}", c.port))
                .map_err(|_| anyhow::anyhow!("代理设置无效"))?;
            if !c.usr.trim().is_empty() {
                proxy = proxy.basic_auth(&c.usr, &c.pwd);
            }
            builder = builder.no_proxy().proxy(proxy);
        }
    }
    builder
        .build()
        .map_err(|_| anyhow::anyhow!("创建服务器连接失败"))
}
impl ServerApi {
    pub fn new(base: String) -> Result<Self> {
        Self::from_dir(base, &crate::paths::data_dir())
    }
    pub fn from_dir(base: String, directory: &Path) -> Result<Self> {
        let config = match std::fs::read_to_string(directory.join("config.toml")) {
            Ok(text) => match parse_config(&text) {
                Ok(config) => config,
                Err(_) => {
                    tracing::warn!("config.toml格式无效，禁用代理");
                    Config::default()
                }
            },
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Config::default(),
            Err(error) => {
                tracing::warn!(%error, "读取config.toml失败，禁用代理");
                Config::default()
            }
        };
        Ok(Self {
            http: client(config.proxy.as_ref())?,
            base: base.trim_end_matches('/').into(),
            jwt: None,
        })
    }
    /// A token-holding API for tests against a local server.
    #[cfg(test)]
    pub fn with_token(base: &str, jwt: &str) -> Self {
        Self {
            http: Client::new(),
            base: base.trim_end_matches('/').into(),
            jwt: Some(jwt.into()),
        }
    }
    /// The full URL of a server path such as `/mod/download/x.jar`.
    pub fn url(&self, path: &str) -> String {
        format!("{}{path}", self.base)
    }
    /// `RServer.authHeadersFor`: the login token only for URLs on this server.
    pub fn auth_headers(&self, url: &str) -> Result<reqwest::header::HeaderMap> {
        let mut headers = reqwest::header::HeaderMap::new();
        if url.starts_with(&format!("{}/", self.base))
            && let Some(jwt) = self.jwt.as_deref().filter(|j| !j.trim().is_empty())
        {
            let mut value: reqwest::header::HeaderValue = format!("Bearer {jwt}").parse()?;
            value.set_sensitive(true);
            headers.insert(reqwest::header::AUTHORIZATION, value);
        }
        Ok(headers)
    }
    async fn send(&self, request: reqwest::RequestBuilder, path: &str) -> Result<Value> {
        let response = request.send().await.map_err(connection_error)?;
        let status = response.status();
        tracing::info!(path, status = status.as_u16(), "服务器请求");
        decode(response).await
    }
    pub async fn login(&mut self, usr: &str, pwd: &str, spec: Option<Value>) -> Result<Session> {
        anyhow::ensure!(
            !usr.trim().is_empty() && !pwd.trim().is_empty(),
            "未填写完整"
        );
        let mut body = json!({"usr":usr,"pwd":pwd});
        if let Some(spec) = spec {
            body["spec"] = json!(spec.to_string());
        }
        let response = self
            .http
            .post(format!("{}/player/login", self.base))
            .json(&body)
            .send()
            .await
            .map_err(connection_error)?;
        tracing::info!(
            path = "/player/login",
            status = response.status().as_u16(),
            "服务器请求"
        );
        let jwt = response
            .headers()
            .get("jwt")
            .and_then(|h| h.to_str().ok())
            .filter(|s| !s.trim().is_empty())
            .map(str::to_owned);
        let envelope = decode(response).await?;
        let account = envelope
            .get("data")
            .filter(|v| !v.is_null())
            .context(message(&envelope))?
            .clone();
        for key in ["_id", "qq", "name", "pwd"] {
            anyhow::ensure!(account[key].is_string(), "服务器数据格式不兼容");
        }
        let id = account["_id"].as_str().unwrap_or_default();
        anyhow::ensure!(
            id.len() == 24 && id.bytes().all(|b| b.is_ascii_hexdigit()),
            "服务器数据格式不兼容"
        );
        let jwt = jwt.context("登录凭据缺失")?;
        self.jwt = Some(jwt.clone());
        Ok(Session { account, jwt })
    }
    pub async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T> {
        self.get_query(path, &[]).await
    }
    pub async fn get_query<T: DeserializeOwned>(
        &self,
        path: &str,
        query: &[(&str, String)],
    ) -> Result<T> {
        let jwt = self.jwt.as_deref().context("请先登录")?;
        let envelope = self
            .send(
                self.http
                    .get(format!("{}{path}", self.base))
                    .query(query)
                    .bearer_auth(jwt),
                path,
            )
            .await?;
        let data = match envelope.get("data").filter(|v| !v.is_null()) {
            Some(data) => data.clone(),
            None if path == "/host/my" || path.starts_with("/host/list/") => json!([]),
            None => bail!("服务器未返回数据"),
        };
        serde_json::from_value(data).map_err(|_| anyhow::anyhow!("服务器数据格式不兼容"))
    }
    /// A JSON POST whose `data` may be absent, as Kotlin `response.data.orEmpty()` allows.
    pub async fn post<T: DeserializeOwned + Default>(&self, path: &str, body: &Value) -> Result<T> {
        self.request(reqwest::Method::POST, path, Some(body)).await
    }
    /// A POST without a body, as Kotlin `makeRequest(method = Post)` without `setBody`.
    pub async fn post_empty<T: DeserializeOwned + Default>(&self, path: &str) -> Result<T> {
        self.request(reqwest::Method::POST, path, None).await
    }
    /// A PUT with an optional JSON body; `data` may be absent.
    pub async fn put<T: DeserializeOwned + Default>(&self, path: &str, body: Option<&Value>) -> Result<T> {
        self.request(reqwest::Method::PUT, path, body).await
    }
    /// A DELETE with an optional JSON body; `data` may be absent.
    pub async fn delete<T: DeserializeOwned + Default>(&self, path: &str, body: Option<&Value>) -> Result<T> {
        self.request(reqwest::Method::DELETE, path, body).await
    }
    async fn request<T: DeserializeOwned + Default>(
        &self,
        method: reqwest::Method,
        path: &str,
        body: Option<&Value>,
    ) -> Result<T> {
        let jwt = self.jwt.as_deref().context("请先登录")?;
        let mut request = self
            .http
            .request(method, format!("{}{path}", self.base))
            .bearer_auth(jwt);
        if let Some(body) = body {
            request = request.json(body);
        }
        let envelope = self.send(request, path).await?;
        match envelope.get("data").filter(|v| !v.is_null()) {
            Some(data) => serde_json::from_value(data.clone())
                .map_err(|_| anyhow::anyhow!("服务器数据格式不兼容")),
            None => Ok(T::default()),
        }
    }
}
fn connection_error(error: reqwest::Error) -> anyhow::Error {
    tracing::warn!(error = %error.without_url(), "服务器连接失败");
    anyhow::anyhow!("无法连接服务器 请检查网络连接")
}
fn message(envelope: &Value) -> String {
    envelope["msg"]
        .as_str()
        .unwrap_or("服务器响应格式错误")
        .into()
}
async fn decode(response: Response) -> Result<Value> {
    let status = response.status();
    let content_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|h| h.to_str().ok())
        .unwrap_or("无Content-Type")
        .to_owned();
    let bytes = response.bytes().await.map_err(connection_error)?;
    let parsed = serde_json::from_slice::<Value>(&bytes);
    if !status.is_success() {
        if let Ok(value) = &parsed
            && let Some(msg) = value["msg"].as_str().filter(|s| !s.trim().is_empty())
        {
            bail!("{}", msg);
        }
        bail!(
            "服务器请求失败: {} {}",
            status.as_u16(),
            status.canonical_reason().unwrap_or("")
        );
    }
    let mime = content_type
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    anyhow::ensure!(
        mime == "application/json" || mime.ends_with("+json"),
        "服务器响应格式错误: {content_type}"
    );
    let envelope = parsed.map_err(|_| anyhow::anyhow!("服务器数据格式不兼容"))?;
    let code = envelope["code"].as_i64().context("服务器数据格式不兼容")?;
    if code != 0 {
        bail!("{}", message(&envelope));
    }
    Ok(envelope)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    /// Reads one request, including a body that arrives after the headers.
    pub(crate) async fn read_request(socket: &mut tokio::net::TcpStream) -> String {
        let mut bytes = Vec::new();
        let mut chunk = vec![0; 16384];
        loop {
            let n = socket.read(&mut chunk).await.unwrap();
            bytes.extend_from_slice(&chunk[..n]);
            let text = String::from_utf8_lossy(&bytes).into_owned();
            if let Some(end) = text.find("\r\n\r\n") {
                let length = text[..end]
                    .lines()
                    .find_map(|line| {
                        let (name, value) = line.split_once(':')?;
                        name.eq_ignore_ascii_case("content-length")
                            .then(|| value.trim().parse::<usize>().ok())?
                    })
                    .unwrap_or(0);
                if bytes.len() >= end + 4 + length {
                    return text;
                }
            }
            if n == 0 {
                return text;
            }
        }
    }
    async fn mock(
        status: &str,
        content_type: Option<&str>,
        body: &str,
        jwt: Option<&str>,
    ) -> (ServerApi, tokio::task::JoinHandle<String>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let response = format!(
            "HTTP/1.1 {status}\r\n{}{}Content-Length: {}\r\nConnection: close\r\n\r\n{body}",
            content_type
                .map(|v| format!("Content-Type: {v}\r\n"))
                .unwrap_or_default(),
            jwt.map(|v| format!("jwt: {v}\r\n")).unwrap_or_default(),
            body.len()
        );
        let task = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let request = read_request(&mut socket).await;
            socket.write_all(response.as_bytes()).await.unwrap();
            request
        });
        (
            ServerApi {
                http: client(None).unwrap(),
                base,
                jwt: Some("fake-token".into()),
            },
            task,
        )
    }
    #[tokio::test]
    async fn http_table_and_authorization() {
        for (status, ct, body, path, expected) in [
            (
                "403 Forbidden",
                Some("application/json"),
                r#"{"msg":"拒绝"}"#,
                "/x",
                "拒绝",
            ),
            (
                "502 Bad Gateway",
                None,
                "oops",
                "/x",
                "服务器请求失败: 502 Bad Gateway",
            ),
            (
                "403 Forbidden",
                None,
                r#"{"msg":" "}"#,
                "/x",
                "服务器请求失败: 403 Forbidden",
            ),
            (
                "200 OK",
                None,
                r#"{"code":0}"#,
                "/x",
                "服务器响应格式错误: 无Content-Type",
            ),
            (
                "200 OK",
                Some("text/plain"),
                r#"{"code":0}"#,
                "/x",
                "服务器响应格式错误: text/plain",
            ),
            (
                "200 OK",
                Some("application/json"),
                r#"{"code":1,"msg":"过期"}"#,
                "/x",
                "过期",
            ),
            (
                "200 OK",
                Some("application/json"),
                r#"{"code":0}"#,
                "/x",
                "服务器未返回数据",
            ),
            (
                "200 OK",
                Some("application/json"),
                r#"{"code":0,"data":{}}"#,
                "/host/my",
                "服务器数据格式不兼容",
            ),
            (
                "200 OK",
                Some("application/json"),
                "bad",
                "/x",
                "服务器数据格式不兼容",
            ),
        ] {
            let (api, task) = mock(status, ct, body, None).await;
            assert_eq!(
                api.get::<Vec<Value>>(path).await.unwrap_err().to_string(),
                expected
            );
            let request = task.await.unwrap().to_lowercase();
            assert!(request.contains("authorization: bearer fake-token"));
            assert!(request.contains(concat!(
                "user-agent: rdi-launcher/",
                env!("RDI_APP_VERSION")
            )));
        }
        for path in ["/host/my", "/host/list/0"] {
            let (api, task) = mock("200 OK", Some("application/json"), r#"{"code":0}"#, None).await;
            assert!(api.get::<Vec<Value>>(path).await.unwrap().is_empty());
            task.await.unwrap();
        }
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        drop(listener);
        let api = ServerApi {
            http: client(None).unwrap(),
            base: format!("http://{addr}"),
            jwt: Some("fake".into()),
        };
        assert_eq!(
            api.get::<Value>("/x").await.unwrap_err().to_string(),
            "无法连接服务器 请检查网络连接"
        );
    }
    #[tokio::test]
    async fn login_contract() {
        for (body, jwt, expected) in [
            (r#"{"code":0,"msg":"无账号"}"#, Some("fake"), Some("无账号")),
            (
                r#"{"code":0,"data":{}}"#,
                Some("fake"),
                Some("服务器数据格式不兼容"),
            ),
            (
                r#"{"code":0,"data":{"_id":"0123456789abcdef01234567","qq":"1","name":"玩家","pwd":"fake-server"}}"#,
                None,
                Some("登录凭据缺失"),
            ),
            (
                r#"{"code":0,"data":{"_id":"0123456789abcdef01234567","qq":"1","name":"玩家","pwd":"fake-server"}}"#,
                Some("fake-jwt"),
                None,
            ),
        ] {
            let (mut api, task) = mock("200 OK", Some("application/json"), body, jwt).await;
            let result = api
                .login("typed", "fake-typed", Some(json!({"model":"test"})))
                .await;
            match expected {
                Some(expected) => assert_eq!(result.err().unwrap().to_string(), expected),
                None => {
                    let session = result.unwrap();
                    assert_eq!(session.account["pwd"], "fake-server");
                    assert_eq!(session.params()["account"]["pwd"], "");
                }
            }
            let request = task.await.unwrap();
            assert!(request.starts_with("POST /player/login "));
        }
    }
    #[tokio::test]
    async fn put_delete_and_empty_post_share_the_envelope() {
        let ok = r#"{"code":0}"#;
        let (api, task) = mock("200 OK", Some("application/json"), ok, None).await;
        api.post_empty::<()>("/host/a/update").await.unwrap();
        let request = task.await.unwrap();
        assert!(request.starts_with("POST /host/a/update "));
        assert!(request.to_lowercase().contains("authorization: bearer fake-token"));
        assert!(!request.to_lowercase().contains("content-type"));

        let (api, task) = mock("200 OK", Some("application/json"), ok, None).await;
        api.put::<()>("/host/a/options", Some(&json!({"name":"房间"}))).await.unwrap();
        let request = task.await.unwrap();
        assert!(request.starts_with("PUT /host/a/options "));
        assert!(request.to_lowercase().contains("content-type: application/json"));
        assert!(request.ends_with(r#"{"name":"房间"}"#));

        let (api, task) = mock("200 OK", Some("application/json"), ok, None).await;
        api.put::<()>("/host/a/quit", None).await.unwrap();
        assert!(task.await.unwrap().starts_with("PUT /host/a/quit "));

        let (api, task) = mock("200 OK", Some("application/json"), ok, None).await;
        api.delete::<()>("/host/a", Some(&json!({}))).await.unwrap();
        let request = task.await.unwrap();
        assert!(request.starts_with("DELETE /host/a "));
        assert!(request.ends_with("{}"));

        let (api, task) = mock(
            "200 OK",
            Some("application/json"),
            r#"{"code":-1,"msg":"无权限"}"#,
            None,
        )
        .await;
        let error = api.delete::<()>("/host/a/member/b", None).await.unwrap_err();
        assert_eq!(error.to_string(), "无权限");
        task.await.unwrap();

        let (api, task) =
            mock("200 OK", Some("application/json"), r#"{"code":0,"data":[1]}"#, None).await;
        assert_eq!(api.put::<Vec<i32>>("/x", None).await.unwrap(), [1]);
        task.await.unwrap();
    }
    #[tokio::test]
    async fn timeout_maps_to_connection_message() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move {
            let (_socket, _) = listener.accept().await.unwrap();
            tokio::time::sleep(Duration::from_secs(2)).await;
        });
        let api = ServerApi {
            http: Client::builder()
                .no_proxy()
                .timeout(Duration::from_millis(30))
                .build()
                .unwrap(),
            base,
            jwt: Some("fake".into()),
        };
        assert_eq!(
            api.get::<Value>("/x").await.unwrap_err().to_string(),
            "无法连接服务器 请检查网络连接"
        );
        task.abort();
    }
    #[test]
    fn invalid_config_is_read_only_and_proxy_free() {
        let dir = std::env::temp_dir().join(format!("rdi-config-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("config.toml");
        std::fs::write(&path, "invalid = [").unwrap();
        assert!(ServerApi::from_dir("http://localhost".into(), &dir).is_ok());
        assert_eq!(std::fs::read_to_string(path).unwrap(), "invalid = [");
    }
    #[test]
    fn kotlin_null_values_do_not_invalidate_config() {
        assert!(
            parse_config("preferModMirror = true\nproxyConfig = null\nsolidWindow = false")
                .unwrap()
                .proxy
                .is_none()
        );
        let quoted = parse_config("[proxyConfig]\nusr = \"null\"\npwd = null").unwrap();
        let proxy = quoted.proxy.unwrap();
        assert_eq!(proxy.usr, "null");
        assert_eq!(proxy.pwd, "");
    }
    #[tokio::test]
    async fn kotlin_written_proxy_config_routes_requests_through_proxy() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let dir = std::env::temp_dir().join(format!(
            "rdi-config-kotlin-{}-{port}",
            std::process::id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("config.toml"),
            include_str!("../../protocol/fixtures/config_kotlin.toml")
                .replace("port = 10808", &format!("port = {port}")),
        )
        .unwrap();
        let task = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut bytes = vec![0; 4096];
            let n = socket.read(&mut bytes).await.unwrap();
            socket
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .await
                .unwrap();
            String::from_utf8_lossy(&bytes[..n]).into_owned()
        });
        // The address never resolves, so only a proxied request can reach the listener.
        let api = ServerApi::from_dir("http://rdi.invalid".into(), &dir).unwrap();
        let response = api.http.get("http://rdi.invalid/x").send().await.unwrap();
        assert_eq!(response.status().as_u16(), 200);
        assert!(task.await.unwrap().starts_with("GET http://rdi.invalid/x "));
    }
    #[test]
    fn proxy_policy_all_branches() {
        assert!(proxy_policy(None) == ProxyPolicy::None);
        for (text, expected) in [
            ("", ProxyPolicy::None),
            ("enabled = false\nsystemProxy = true", ProxyPolicy::None),
            ("enabled = true\nsystemProxy = true", ProxyPolicy::System),
            (
                "enabled = true\nhost = 'localhost'\nport = 8080\nusr = 'fake'\npwd = 'fake'",
                ProxyPolicy::Custom,
            ),
            ("enabled = true\nhost = ' '\nport = 8080", ProxyPolicy::None),
            (
                "enabled = true\nhost = 'localhost'\nport = 0",
                ProxyPolicy::None,
            ),
        ] {
            let c = toml::from_str::<ProxyConfig>(text).unwrap();
            assert!(proxy_policy(Some(&c)) == expected);
            assert!(client(Some(&c)).is_ok());
        }
    }
}
