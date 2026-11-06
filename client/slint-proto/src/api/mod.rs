pub mod model;
use anyhow::{Context, Result, ensure};
use rdi_slint_proto::service::Service;
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use std::{
    process::Command,
    sync::Arc,
    time::Duration,
};
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
}
impl Client {
    pub fn new(base: String) -> Result<Self> {
        let mut command = if let Ok(value) = std::env::var("RDI_SERVICE_COMMAND") {
            let arguments: Vec<String> =
                serde_json::from_str(&value).context("RDI_SERVICE_COMMAND必须是JSON参数数组")?;
            let mut command = Command::new(arguments.first().context("启动服务命令为空")?);
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
            let mut command = Command::new(java);
            if std::env::var_os("RDI_PROTO_SERVER").is_some() {
                command.arg(format!("-Drdi.server.url={base}"));
            }
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
        command.current_dir(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../ui/run"));
        let log = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open("ui-service-stderr.log")?;
        let service = Service::spawn(&mut command, log)?;
        Ok(Self {
            http: reqwest::Client::builder()
                .timeout(Duration::from_secs(30))
                .build()?,
            service: Arc::new(service),
            uid: String::new(),
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
    pub async fn login(mut self, usr: &str, pwd: &str) -> Result<Self> {
        let result = self.call("login", json!({"usr":usr,"pwd":pwd})).await?;
        self.uid = result["uid"].as_str().context("登录未返回玩家身份")?.into();
        Ok(self)
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
        serde_json::from_value(self.call("data.get", json!({"path":path})).await?)
            .context("服务器数据格式不兼容")
    }
}
