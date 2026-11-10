//! Logout parity with Compose; unknown fields and other accounts are preserved.
use anyhow::{Context, Result, ensure};
use serde_json::Value;
use std::{fs, path::Path};

pub fn clear(path: &Path, account_id: &str) -> Result<()> {
    let bytes = match fs::read(path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error).context("读取正版会话失败"),
    };
    // Never attach a JSON parser error: the input contains Microsoft sessions.
    let mut data: Value =
        serde_json::from_slice(&bytes).map_err(|_| anyhow::anyhow!("正版会话文件格式无效"))?;
    ensure!(data.is_object(), "正版会话文件格式无效");
    let Some(accounts) = data.get_mut("accounts") else {
        return Ok(());
    };
    let accounts = accounts.as_object_mut().context("正版会话文件格式无效")?;
    if accounts.remove(account_id).is_none() {
        return Ok(());
    }
    crate::credentials::write_atomic(path, &serde_json::to_vec(&data)?)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn path() -> std::path::PathBuf {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        std::env::temp_dir().join(format!(
            "j3-msa-{}-{}.json",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ))
    }
    #[test]
    fn shared_fixture_preserves_other_accounts_and_unknown_fields() {
        let path = path();
        let fixture = include_str!("../protocol/fixtures/msa_sessions.json");
        fs::write(&path, fixture).unwrap();
        clear(&path, "fake-account-a").unwrap();
        let value: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert!(value["accounts"].get("fake-account-a").is_none());
        assert_eq!(
            value["accounts"]["fake-account-b"]["fake-profile-b"],
            "{\"fake\":\"session-b\"}"
        );
        assert_eq!(value["future"], 7);
        let before = fs::read(&path).unwrap();
        clear(&path, "absent").unwrap();
        assert_eq!(before, fs::read(&path).unwrap());
        fs::remove_file(path).unwrap();
    }
    #[test]
    fn missing_file_and_missing_accounts_write_nothing() {
        let path = path();
        clear(&path, "a").unwrap();
        assert!(!path.exists());
        fs::write(&path, "{\"future\":true}").unwrap();
        clear(&path, "a").unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), "{\"future\":true}");
        fs::remove_file(path).unwrap();
    }
    #[test]
    fn damaged_or_wrong_shape_file_is_untouched() {
        let path = path();
        for input in ["fake-secret not json", "[]", "{\"accounts\":null}"] {
            fs::write(&path, input).unwrap();
            let error = clear(&path, "a").unwrap_err();
            assert!(!format!("{error:#}").contains("fake-secret"));
            assert_eq!(fs::read_to_string(&path).unwrap(), input);
        }
        fs::remove_file(path).unwrap();
    }
}
