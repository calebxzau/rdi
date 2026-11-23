//! QQ mail operations (Compose MailOperationDialogs): the player mails an encrypted request to
//! the bot, then looks up the receipt by the mail title. Encryption is Kotlin `CryptoManager`:
//! AES-256-GCM with the SHA-256 of a fixed phrase as key, IV then ciphertext, Base64.
use aes_gcm::{
    Aes256Gcm, Key,
    aead::{Aead, AeadCore, KeyInit, OsRng, rand_core::RngCore},
};
use anyhow::{Result, anyhow};
use base64::Engine as _;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::sync::atomic::{AtomicU32, Ordering};

pub const QQ_MAIL_URL: &str = "https://mail.qq.com";
const TITLE_PREFIX: &str = "rdi-opr-";

fn cipher() -> Aes256Gcm {
    let key = Sha256::digest("rdiRDIrdi".as_bytes());
    Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key))
}

/// Kotlin `CryptoManager.encrypt`.
pub fn encrypt(plain: &str) -> Result<String> {
    let nonce = Aes256Gcm::generate_nonce(&mut OsRng);
    let encrypted = cipher().encrypt(&nonce, plain.as_bytes()).map_err(|_| anyhow!("生成操作码失败"))?;
    let mut combined = nonce.to_vec();
    combined.extend_from_slice(&encrypted);
    Ok(base64::engine::general_purpose::STANDARD.encode(combined))
}

/// Kotlin `CryptoManager.decrypt`; only tests need it.
#[cfg(test)]
pub fn decrypt(encoded: &str) -> Result<String> {
    const IV_LEN: usize = 12;
    let combined = base64::engine::general_purpose::STANDARD.decode(encoded)?;
    anyhow::ensure!(combined.len() > IV_LEN, "too short");
    let (iv, data) = combined.split_at(IV_LEN);
    let plain = cipher()
        .decrypt(aes_gcm::Nonce::from_slice(iv), data)
        .map_err(|_| anyhow!("decrypt failed"))?;
    Ok(String::from_utf8(plain)?)
}

/// A new BSON ObjectId in hex: seconds, five random bytes and a counter, as `org.bson.types.ObjectId()`.
pub fn object_id() -> String {
    static COUNTER: AtomicU32 = AtomicU32::new(0);
    let seconds = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as u32)
        .unwrap_or_default();
    let mut random = [0u8; 5];
    OsRng.fill_bytes(&mut random);
    let counter = COUNTER.fetch_add(1, Ordering::Relaxed) & 0x00ff_ffff;
    let mut bytes = Vec::with_capacity(12);
    bytes.extend_from_slice(&seconds.to_be_bytes());
    bytes.extend_from_slice(&random);
    bytes.extend_from_slice(&counter.to_be_bytes()[1..]);
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// `newOperationMailTitle`.
pub fn new_title() -> String {
    format!("{TITLE_PREFIX}{}", object_id())
}

/// Kotlin `Request(opr, data).json`; nulls are written, as kotlinx `Json` does by default.
fn request(opr: &str, data: Value) -> Result<String> {
    encrypt(&json!({"opr": opr, "data": data}).to_string())
}

/// `Request("register", RegisterDto(name, qq, pwd, null))`.
pub fn register_code(name: &str, qq: &str, pwd: &str) -> Result<String> {
    request("register", json!({"name": name, "qq": qq, "pwd": pwd, "msa": null}))
}

/// `Request("resetPwd", ResetPasswordByQqMailDto(qq, newPwd))`.
pub fn reset_code(qq: &str, new_pwd: &str) -> Result<String> {
    request("resetPwd", json!({"qq": qq, "newPwd": new_pwd}))
}

/// ReceiptQueryDialogs: the receipt id from a pasted mail title.
pub fn receipt_id(title: &str) -> Result<String, &'static str> {
    let Some(id) = title.trim().strip_prefix(TITLE_PREFIX) else {
        return Err("标题必须以rdi-opr-开头");
    };
    let id = id.trim();
    if id.len() != 24 || !id.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("标题里的回执ID无效");
    }
    Ok(id.to_ascii_lowercase())
}

#[cfg(test)]
mod tests {
    use super::*;
    /// Encrypted by the JDK with Kotlin `CryptoManager.encrypt`'s algorithm and key.
    const FROM_JAVA: &str = "a0ZkUN7Lhn56rWd3mCak1MTOyf/mxqrFgWLiL67A4SimyCubZv3md1Xj5ADaeMXMqBWmafYk+LLGV0K4yIXrHASHLeLvvfqqyNiFnjwe3EYIiHtiuCF5zk0C2RaV1rGWs9WINDbyjMLs4RbgyCK1iLOF";
    #[test]
    fn reads_what_kotlin_wrote() {
        assert_eq!(
            decrypt(FROM_JAVA).unwrap(),
            r#"{"opr":"register","data":{"name":"玩家_01","qq":"12345","pwd":"secret1","msa":null}}"#
        );
    }
    #[test]
    fn codes_round_trip_with_a_fresh_iv() {
        let a = register_code("玩家_01", "12345", "secret1").unwrap();
        let b = register_code("玩家_01", "12345", "secret1").unwrap();
        assert_ne!(a, b, "random IV");
        let value: Value = serde_json::from_str(&decrypt(&a).unwrap()).unwrap();
        assert_eq!(value, json!({"opr":"register","data":{"name":"玩家_01","qq":"12345","pwd":"secret1","msa":null}}));
        let value: Value = serde_json::from_str(&decrypt(&reset_code("12345", "newpass").unwrap()).unwrap()).unwrap();
        assert_eq!(value, json!({"opr":"resetPwd","data":{"qq":"12345","newPwd":"newpass"}}));
    }
    #[test]
    fn titles_and_receipts() {
        let title = new_title();
        assert!(title.starts_with("rdi-opr-") && title.len() == 32);
        assert_ne!(object_id(), object_id());
        assert_eq!(receipt_id(&format!(" {title} ")).unwrap(), &title[8..]);
        assert_eq!(receipt_id("rdi-opr-6650A1B2C3D4E5F601234567").unwrap(), "6650a1b2c3d4e5f601234567");
        assert_eq!(receipt_id("hello").unwrap_err(), "标题必须以rdi-opr-开头");
        assert_eq!(receipt_id("rdi-opr-xyz").unwrap_err(), "标题里的回执ID无效");
    }
}
