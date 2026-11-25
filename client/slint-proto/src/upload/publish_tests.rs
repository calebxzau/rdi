//! Stage A5b: sending and publishing against a fake server.
use super::publish::{self, MyPack, MyVersion, NewPack, PublishApi, Run, SessionVo, Target, Transport, UNCONFIRMED};
use crate::pack_install::extras::{Content, Kind, Platform, Side as ContentSide};
use crate::pack_install::mods::{Mod, Side};
use crate::save_import::api::Rejected;
use crate::save_import::upload::Options;
use crate::tasks::Cancellation;
use crate::test_dir::TestDir;
use anyhow::{Result, anyhow};
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[derive(Default)]
struct Fake {
    /// The session `create_session` answers; its sizes are filled from the request.
    part_size: u64,
    uploaded: Vec<u64>,
    ready: bool,
    complete_ready: bool,
    /// Parts that fail once with a retryable error.
    flaky: Mutex<BTreeSet<u64>>,
    /// A part the server refuses for good.
    refused: Option<u64>,
    publish_error: Mutex<Option<anyhow::Error>>,
    sent: Mutex<Vec<(u64, Vec<u8>, String)>>,
    calls: Mutex<Vec<String>>,
    published: Mutex<Option<(Target, Value)>>,
    mine: Vec<MyPack>,
}

impl Fake {
    fn log(&self, call: impl Into<String>) {
        self.calls.lock().unwrap().push(call.into());
    }
    fn calls(&self) -> Vec<String> {
        self.calls.lock().unwrap().clone()
    }
}

impl PublishApi for Fake {
    async fn create_session(&self, file_name: &str, size: u64, sha1: &str) -> Result<SessionVo> {
        self.log(format!("create {file_name} {size} {sha1}"));
        Ok(SessionVo { id: "u1".into(), size, part_size: self.part_size, part_count: (size - 1) / self.part_size + 1, uploaded_parts: self.uploaded.clone(), ready: self.ready, max_parallel_parts: 8 })
    }
    async fn upload_part(&self, upload_id: &str, index: u64, bytes: Vec<u8>, sha1: String) -> Result<()> {
        assert_eq!(upload_id, "u1");
        if self.refused == Some(index) {
            return Err(Rejected { message: "分片校验失败".into(), error_code: None }.into());
        }
        if self.flaky.lock().unwrap().remove(&index) {
            return Err(anyhow!("连接中断"));
        }
        self.sent.lock().unwrap().push((index, bytes, sha1));
        Ok(())
    }
    async fn complete(&self, upload_id: &str) -> Result<SessionVo> {
        self.log(format!("complete {upload_id}"));
        Ok(SessionVo { id: upload_id.into(), size: 0, part_size: 1, part_count: 1, uploaded_parts: vec![], ready: self.complete_ready, max_parallel_parts: 8 })
    }
    async fn cancel(&self, upload_id: &str) -> Result<()> {
        self.log(format!("cancel {upload_id}"));
        Ok(())
    }
    async fn publish(&self, target: &Target, body: &Value) -> Result<()> {
        self.log("publish");
        if let Some(error) = self.publish_error.lock().unwrap().take() {
            return Err(error);
        }
        *self.published.lock().unwrap() = Some((target.clone(), body.clone()));
        Ok(())
    }
    async fn list_my(&self) -> Result<Vec<MyPack>> {
        Ok(self.mine.clone())
    }
}

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap()
}

fn options() -> Options {
    Options { parallelism: 2, retries: 2, retry_delay: Duration::from_millis(1), max_retry_delay: Duration::from_millis(2) }
}

struct Setup {
    runtime: tokio::runtime::Runtime,
    dir: TestDir,
    messages: Mutex<Vec<(String, Option<f32>)>>,
}

impl Setup {
    fn new() -> Self {
        let dir = TestDir::new("rdi-upload-publish");
        std::fs::write(dir.join("pack_1.tar.zst"), b"0123456789").unwrap();
        Self { runtime: runtime(), dir, messages: Mutex::new(vec![]) }
    }

    fn send(&self, api: &Arc<Fake>, cancel: &Cancellation) -> Result<String> {
        let progress = |message: String, fraction: Option<f32>| self.messages.lock().unwrap().push((message, fraction));
        let run = Run { runtime: self.runtime.handle(), cancel, options: options(), progress: &progress, label: "测试包".into() };
        publish::send(&run, api, &self.dir.join("pack_1.tar.zst"), &Target::New, |id| json!({"uploadId": id}))
    }
}

const SHA1_OF_FILE: &str = "87acec17cd9dcd20a716cc2cf67417b71c8a7016";

#[test]
fn every_missing_part_is_sent_then_completed_and_published() {
    let setup = Setup::new();
    let api = Arc::new(Fake { part_size: 4, complete_ready: true, flaky: Mutex::new([1].into()), ..Default::default() });
    let summary = setup.send(&api, &Cancellation::default()).unwrap();
    assert_eq!(api.calls(), [format!("create pack_1.tar.zst 10 {SHA1_OF_FILE}"), "complete u1".into(), "publish".into()]);
    let mut sent = api.sent.lock().unwrap().clone();
    sent.sort();
    let parts: Vec<(u64, &[u8])> = sent.iter().map(|(i, b, _)| (*i, b.as_slice())).collect();
    assert_eq!(parts, [(0, b"0123".as_slice()), (1, b"4567".as_slice()), (2, b"89".as_slice())], "part 1 was retried");
    assert_eq!(sent[2].2, "16b06bd9b738835e2d134fe8d596e9ab0086a985", "X-Part-SHA1 is the part's own SHA-1");
    assert_eq!(api.published.lock().unwrap().as_ref().unwrap().1, json!({"uploadId": "u1"}));
    assert!(summary.starts_with("文件大小: 10B\n平均速度: ") && summary.ends_with("上传完成，正在继续本地安装\n"), "{summary}");
    let messages = setup.messages.lock().unwrap();
    let last = messages.last().unwrap();
    assert!(last.0.starts_with("正在上传整合包 测试包...\n进度：100% (10B/10B)\n速度："), "{}", last.0);
    assert_eq!(last.1, Some(1.0));
}

#[test]
fn a_resumed_session_sends_only_the_parts_it_misses() {
    let setup = Setup::new();
    let api = Arc::new(Fake { part_size: 4, uploaded: vec![0, 2], complete_ready: true, ..Default::default() });
    setup.send(&api, &Cancellation::default()).unwrap();
    let sent: Vec<u64> = api.sent.lock().unwrap().iter().map(|(i, _, _)| *i).collect();
    assert_eq!(sent, [1]);
    let messages = setup.messages.lock().unwrap();
    assert!(messages[0].0.contains("进度：60% (6B/10B)"), "the parts the server holds count first: {}", messages[0].0);
}

#[test]
fn a_ready_session_is_published_without_parts() {
    let setup = Setup::new();
    let api = Arc::new(Fake { part_size: 4, ready: true, ..Default::default() });
    setup.send(&api, &Cancellation::default()).unwrap();
    assert_eq!(api.calls().len(), 2);
    assert_eq!(api.calls()[1], "publish");
    assert!(api.sent.lock().unwrap().is_empty());
}

#[test]
fn a_failure_before_publishing_cancels_the_session() {
    let setup = Setup::new();
    let api = Arc::new(Fake { part_size: 4, complete_ready: false, ..Default::default() });
    let error = setup.send(&api, &Cancellation::default()).unwrap_err().to_string();
    assert_eq!(error, "服务器未确认整合包上传完成");
    assert_eq!(api.calls().last().unwrap(), "cancel u1");

    // A refused part is final: no retry, and the session goes.
    let api = Arc::new(Fake { part_size: 4, complete_ready: true, refused: Some(2), ..Default::default() });
    assert_eq!(setup.send(&api, &Cancellation::default()).unwrap_err().to_string(), "分片校验失败");
    assert_eq!(api.calls().last().unwrap(), "cancel u1");
    assert!(!api.calls().iter().any(|c| c == "publish"));
}

#[test]
fn a_lost_publish_answer_is_unconfirmed_and_keeps_the_session() {
    let setup = Setup::new();
    let api = Arc::new(Fake { part_size: 4, complete_ready: true, ..Default::default() });
    *api.publish_error.lock().unwrap() = Some(anyhow::Error::new(Transport("无法连接服务器 请检查网络连接".into())));
    let error = setup.send(&api, &Cancellation::default()).unwrap_err();
    assert_eq!(error.to_string(), UNCONFIRMED);
    assert!(!api.calls().iter().any(|c| c.starts_with("cancel")));

    // The server refusing the publish is its answer, shown as it is.
    let api = Arc::new(Fake { part_size: 4, complete_ready: true, ..Default::default() });
    *api.publish_error.lock().unwrap() = Some(Rejected { message: "整合包名称已存在".into(), error_code: None }.into());
    assert_eq!(setup.send(&api, &Cancellation::default()).unwrap_err().to_string(), "整合包名称已存在");
    assert!(!api.calls().iter().any(|c| c.starts_with("cancel")));
}

#[test]
fn the_new_pack_is_the_newest_with_its_name_and_version() {
    let runtime = runtime();
    let pack = |id: &str, name: &str, versions: &[(&str, i64)]| MyPack {
        id: id.into(),
        name: name.into(),
        versions: versions.iter().map(|(n, t)| MyVersion { name: (*n).into(), time: *t }).collect(),
    };
    let api = Fake { mine: vec![pack("a", "包", &[("1.0", 5)]), pack("b", "包", &[("1.0", 9), ("0.9", 1)]), pack("c", "别的包", &[("1.0", 99)])], ..Default::default() };
    assert_eq!(publish::find_new_pack(runtime.handle(), &api, "包", "1.0").unwrap(), "b");
    assert_eq!(publish::find_new_pack(runtime.handle(), &api, "包", "2.0").unwrap_err().to_string(), "上传成功，但未能定位到刚创建的整合包");
}

#[test]
fn the_publish_bodies_are_kotlin_dtos() {
    let m = Mod {
        platform: "mr".into(),
        project_id: "AANobbMI".into(),
        slug: "sodium".into(),
        file_id: "f1".into(),
        hash: "a".repeat(40),
        side: Side::Client,
        download_urls: vec!["https://cdn.modrinth.com/x.jar".into()],
        client_only_override: false,
        client_override_replaced: false,
    };
    let extra = Content {
        platform: Platform::Modrinth,
        kind: Kind::ResPack,
        project_id: "p".into(),
        file_id: "v".into(),
        slug: "faithful".into(),
        hash: "b".repeat(40),
        path: None,
        side: ContentSide::Client,
        required: true,
        download_urls: vec![],
    };
    let pack = NewPack {
        name: "包".into(),
        version: "1.0".into(),
        mc_version: "V201".into(),
        loader: "forge".into(),
        icon_url: " ".into(),
        source_url: "https://example.com/p".into(),
        info: "简介".into(),
        categories: vec!["TECH".into(), "TECH".into(), "MAGIC".into()],
    };
    let body = publish::new_pack_body("u1", &pack, std::slice::from_ref(&m), std::slice::from_ref(&extra));
    assert_eq!(
        body,
        json!({"uploadId": "u1", "modpack": {"name": "包", "mcVer": "V201", "modLoader": "forge", "verName": "1.0", "iconUrl": null,
            "sourceUrl": "https://example.com/p", "info": "简介", "categories": ["TECH", "MAGIC"],
            "mods": [{"platform": "mr", "projectId": "AANobbMI", "slug": "sodium", "fileId": "f1", "hash": "a".repeat(40), "side": "CLIENT",
                "downloadUrls": ["https://cdn.modrinth.com/x.jar"], "clientOnlyOverride": false, "clientOverrideReplaced": false}],
            "clientExtras": [{"platform": "Modrinth", "type": "ResPack", "projectId": "p", "fileId": "v", "slug": "faithful", "hash": "b".repeat(40),
                "path": null, "side": "Client", "required": true, "downloadUrls": []}]}})
    );
    assert_eq!(publish::version_body("u2", &[], &[]), json!({"uploadId": "u2", "mods": [], "clientExtras": []}));
}

#[test]
fn speeds_and_sizes_read_as_in_kotlin() {
    assert_eq!(publish::human_speed(512.4), "512B/s");
    assert_eq!(publish::human_speed(1536.0), "1.5KB/s");
    assert_eq!(publish::human_speed(5.0 * 1024.0 * 1024.0), "5.0MB/s");
    let (message, fraction) = publish::progress_message("版本 1.0", 0, 0, 0.0);
    assert_eq!(message, "正在上传整合包 版本 1.0...\n进度：100% (0B/0B)\n速度：0B/s\n");
    assert_eq!(fraction, 1.0);
}
