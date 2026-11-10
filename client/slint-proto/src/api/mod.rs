pub mod model;
pub mod server;
use anyhow::{Context, Result, ensure};
use rdi_slint_proto::service::Service;
use serde::de::DeserializeOwned;
use serde_json::Value;
use server::{ServerApi, Session};
use std::{process::Command, sync::Arc, time::Duration};
// Keep the updater source unchanged; its modulo spelling is outside this port.
#[allow(clippy::manual_is_multiple_of)]
#[path = "../../../updater/src/java_discovery.rs"]
mod java_discovery;

#[derive(Clone)]
pub struct Client {
    pub http: reqwest::Client,
    /// Shared without a lock: concurrent calls wait only for their own replies.
    service: Arc<Service>,
    uid: String,
    server: ServerApi,
    /// The Java 25 the service runs on; Kotlin offered the same path as `globalJavaPath`.
    pub java_path: String,
}
impl Client {
    pub async fn connect(server: ServerApi, session: Session) -> Result<Self> {
        tokio::task::spawn_blocking(move || Self::connect_blocking(server, session)).await?
    }
    fn connect_blocking(server: ServerApi, session: Session) -> Result<Self> {
        let uid = session.uid()?.to_owned();
        let java_path;
        let mut command = if let Ok(value) = std::env::var("RDI_SERVICE_COMMAND") {
            let arguments: Vec<String> =
                serde_json::from_str(&value).context("RDI_SERVICE_COMMAND必须是JSON参数数组")?;
            let program = arguments.first().context("启动服务命令为空")?;
            java_path = program.clone();
            let mut command = Command::new(program);
            command.args(&arguments[1..]);
            command
        } else {
            let java = java_discovery::candidate_paths()
                .into_iter()
                .find(|path| {
                    Command::new(path)
                        .arg("-version")
                        .output()
                        .is_ok_and(|output| {
                            output.status.success()
                                && String::from_utf8_lossy(&output.stderr).contains("version \"25")
                        })
                })
                .context("未找到Java25，请安装JDK25或设置RDI_SERVICE_COMMAND")?;
            let lib = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../ui/ui-service/build/install/ui-service/lib/*");
            java_path = java.to_string_lossy().into_owned();
            let mut command = Command::new(java);
            command
                .arg("-Djava.awt.headless=true")
                .arg("-cp")
                .arg(lib)
                .arg("calebxzau.rdi.service.MainKt");
            command
        };
        command
            .env_remove("RDI_PROTO_USR")
            .env_remove("RDI_PROTO_PWD");
        let directory = crate::paths::data_dir();
        std::fs::create_dir_all(&directory).context("创建启动器数据目录失败")?;
        command.current_dir(&directory);
        let log = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(directory.join("ui-service-stderr.log"))?;
        let service = Service::spawn(&mut command, log)?;
        let result = service.request("session.set", session.params(), Duration::from_secs(60))?;
        ensure!(
            result["uid"].as_str() == Some(uid.as_str()),
            "登录身份不一致"
        );
        Ok(Self {
            http: server.http.clone(),
            server,
            service: Arc::new(service),
            uid,
            java_path,
        })
    }
    pub async fn call(&self, method: &str, params: Value) -> Result<Value> {
        let service = self.service.clone();
        let method = method.to_owned();
        tokio::task::spawn_blocking(move || {
            service.request(&method, params, Duration::from_secs(60))
        })
        .await?
    }
    pub fn take_events(&self) -> Vec<Value> {
        self.service.take_events().unwrap_or_else(|error| {
            tracing::warn!(%error, "读取游戏状态失败");
            vec![]
        })
    }
    pub fn has_exited(&self) -> bool {
        self.service.has_exited().unwrap_or_else(|error| {
            tracing::warn!(%error, "检查启动服务失败");
            true
        })
    }
    pub fn uid(&self) -> Result<String> {
        ensure!(!self.uid.is_empty(), "请先登录");
        Ok(self.uid.clone())
    }
    pub async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T> {
        self.server.get(path).await
    }
    pub async fn post<T: DeserializeOwned + Default>(&self, path: &str, body: &Value) -> Result<T> {
        self.server.post(path, body).await
    }
}
