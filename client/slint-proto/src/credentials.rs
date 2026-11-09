//! Compose-compatible saved accounts. Secret-bearing types deliberately have no Debug.
use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    collections::BTreeMap,
    fs,
    path::Path,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

#[derive(Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Credentials {
    #[serde(default)]
    pub login_infos: BTreeMap<String, LoginInfo>,
    #[serde(default)]
    pub auto_login_disabled: bool,
    #[serde(flatten)]
    extra: BTreeMap<String, Value>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LoginInfo {
    pub qq: String,
    #[serde(default = "default_name")]
    pub name: Option<String>,
    pub pwd: String,
    #[serde(default)]
    pub last_logged_time: u64,
    #[serde(flatten)]
    extra: BTreeMap<String, Value>,
}
fn default_name() -> Option<String> {
    Some("RDI玩家".into())
}
pub fn now_ms() -> Result<u64> {
    Ok(SystemTime::now()
        .duration_since(UNIX_EPOCH)?
        .as_millis()
        .try_into()?)
}
fn read(path: &Path) -> Result<Credentials> {
    match fs::read(path) {
        Ok(bytes) if bytes.iter().all(u8::is_ascii_whitespace) => Ok(Credentials::default()),
        Ok(bytes) => {
            serde_json::from_slice(&bytes).map_err(|_| anyhow::anyhow!("历史账号文件格式错误"))
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Credentials::default()),
        Err(e) => Err(e).context("读取历史账号失败"),
    }
}
impl Credentials {
    pub fn load(path: &Path) -> Self {
        read(path).unwrap_or_else(|error| {
            tracing::warn!(%error, "无法读取历史账号，显示空历史");
            Self::default()
        })
    }
    pub fn sorted(&self) -> Vec<(&String, &LoginInfo)> {
        let mut entries = self.login_infos.iter().collect::<Vec<_>>();
        entries.sort_by(|a, b| {
            b.1.last_logged_time
                .cmp(&a.1.last_logged_time)
                .then(a.0.cmp(b.0))
        });
        entries
    }
    pub fn saved(path: &Path, id: &str) -> Result<(String, String)> {
        let mut data = Self::load(path);
        let info = data
            .login_infos
            .remove(id)
            .context("历史账号已不存在，请重新输入账号和密码")?;
        Ok((info.qq, info.pwd))
    }
    pub fn save_account(path: &Path, account: &Value) -> Result<()> {
        let field = |key: &str| account[key].as_str().context("服务器数据格式不兼容");
        let (id, qq, name, pwd) = (field("_id")?, field("qq")?, field("name")?, field("pwd")?);
        let mut data = match read(path) {
            Ok(data) => data,
            Err(error) => {
                // Only malformed JSON is recoverable; I/O failures must not replace the file.
                let bytes = fs::read(path).context("读取历史账号失败")?;
                if serde_json::from_slice::<Credentials>(&bytes).is_ok() {
                    return Err(error);
                }
                tracing::warn!(%error, "归档损坏的历史账号文件");
                let archive =
                    path.with_file_name(format!("local_credentials.json.corrupt-{}", now_ms()?));
                if archive.exists() {
                    bail!("损坏账号归档已存在");
                }
                fs::rename(path, archive).context("归档损坏的历史账号失败")?;
                Self::default()
            }
        };
        let extra = data
            .login_infos
            .remove(id)
            .map(|i| i.extra)
            .unwrap_or_default();
        data.login_infos.insert(
            id.into(),
            LoginInfo {
                qq: qq.into(),
                name: Some(name.into()),
                pwd: pwd.into(),
                last_logged_time: now_ms()?,
                extra,
            },
        );
        data.auto_login_disabled = false;
        write_atomic(path, &serde_json::to_vec(&data)?)
    }
    /// Keeps the saved accounts, as Compose does on logout. A corrupt file stays untouched.
    pub fn disable_auto_login(path: &Path) -> Result<()> {
        let mut data = read(path)?;
        data.auto_login_disabled = true;
        write_atomic(path, &serde_json::to_vec(&data)?)
    }
}
fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().context("历史账号目录无效")?;
    fs::create_dir_all(parent).context("创建账号目录失败")?;
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let temp = parent.join(format!(
        ".local_credentials-{}-{}-{}.tmp",
        std::process::id(),
        now_ms()?,
        NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    ));
    let file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp)
        .context("创建账号临时文件失败")?;
    let result = replace_from_temp(file, &temp, path, bytes);
    // The temp file holds the saved password, so a failed write must not leave it behind.
    if result.is_err()
        && let Err(error) = fs::remove_file(&temp)
    {
        tracing::warn!(%error, "清理账号临时文件失败");
    }
    result
}
fn replace_from_temp(mut file: fs::File, temp: &Path, path: &Path, bytes: &[u8]) -> Result<()> {
    use std::io::Write;
    file.write_all(bytes).context("写入历史账号失败")?;
    file.sync_all().context("保存历史账号失败")?;
    drop(file);
    for attempt in 0..=3 {
        match fs::rename(temp, path) {
            Ok(()) => return Ok(()),
            Err(error) if attempt == 3 => return Err(error).context("替换历史账号文件失败"),
            Err(_) => std::thread::sleep(Duration::from_millis(50)),
        }
    }
    unreachable!()
}
pub fn file() -> std::path::PathBuf {
    crate::paths::data_dir().join("local_credentials.json")
}

#[cfg(test)]
mod tests {
    use super::*;
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    fn isolated() -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!(
            "rdi-j1-{}-{}-{}",
            std::process::id(),
            now_ms().unwrap(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        fs::create_dir_all(&path).unwrap();
        path.join("local_credentials.json")
    }
    #[test]
    fn shared_fixture_defaults_order_and_preservation() {
        let path = isolated();
        fs::write(
            &path,
            include_str!("../protocol/fixtures/local_credentials.json"),
        )
        .unwrap();
        let data = read(&path).unwrap();
        assert_eq!(data.sorted()[0].0, "0123456789abcdef01234567");
        assert_eq!(data.login_infos["older"].name.as_deref(), Some("RDI玩家"));
        Credentials::save_account(&path, &serde_json::json!({"_id":"0123456789abcdef01234567","qq":"20002","name":"新名字","pwd":"fake-server-value"})).unwrap();
        let data = read(&path).unwrap();
        assert!(!data.auto_login_disabled);
        assert_eq!(data.extra["lastPlayHost"]["id"], "fake-room");
        assert_eq!(data.extra["future"], true);
        assert_eq!(
            data.login_infos["0123456789abcdef01234567"].extra["futureAccount"],
            1
        );
        assert_eq!(
            Credentials::saved(&path, "0123456789abcdef01234567")
                .unwrap()
                .1,
            "fake-server-value"
        );
        assert!(
            Credentials::saved(&path, "gone")
                .unwrap_err()
                .to_string()
                .contains("历史账号已不存在")
        );
    }
    #[test]
    fn disabling_auto_login_keeps_accounts_and_never_replaces_a_corrupt_file() {
        let path = isolated();
        fs::write(
            &path,
            include_str!("../protocol/fixtures/local_credentials.json"),
        )
        .unwrap();
        Credentials::disable_auto_login(&path).unwrap();
        let data = read(&path).unwrap();
        assert!(data.auto_login_disabled);
        assert_eq!(data.login_infos.len(), 2);
        assert_eq!(data.extra["lastPlayHost"]["id"], "fake-room");
        fs::write(&path, "broken").unwrap();
        assert!(Credentials::disable_auto_login(&path).is_err());
        assert_eq!(fs::read_to_string(&path).unwrap(), "broken");
    }
    #[test]
    fn failed_replace_leaves_no_temp_file() {
        let target = isolated();
        // A directory cannot be replaced by a file, so every rename attempt fails.
        fs::create_dir(&target).unwrap();
        assert!(write_atomic(&target, b"{}").is_err());
        assert_eq!(fs::read_dir(target.parent().unwrap()).unwrap().count(), 1);
        assert!(target.is_dir());
    }
    #[test]
    fn missing_empty_corrupt_and_failed_write() {
        let path = isolated();
        assert!(Credentials::load(&path).login_infos.is_empty());
        assert!(!path.exists());
        fs::write(&path, "").unwrap();
        assert!(read(&path).unwrap().login_infos.is_empty());
        fs::write(&path, "broken").unwrap();
        assert!(Credentials::load(&path).login_infos.is_empty());
        Credentials::save_account(
            &path,
            &serde_json::json!({"_id":"id","qq":"1","name":"玩家","pwd":"fake"}),
        )
        .unwrap();
        assert!(fs::read_dir(path.parent().unwrap()).unwrap().any(|e| {
            e.unwrap()
                .file_name()
                .to_string_lossy()
                .contains(".corrupt-")
        }));
        let blocked = path.join("child");
        assert!(
            Credentials::save_account(
                &blocked,
                &serde_json::json!({"_id":"id","qq":"1","name":"玩家","pwd":"fake"})
            )
            .is_err()
        );
    }
}
