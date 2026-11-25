//! Stage A5d: Kotlin `submitUpload` from the draft checks on, `queueUpload` and
//! `createUploadModpackTask2`: the task `上传并安装整合包 {name} {version}` with the steps 上传整合包
//! (archive, chunked upload, publish), 下载剩余客户端Mod and 本地安装整合包.
//!
//! Stage A6: without a server pack the client and server tests must have passed with the draft's
//! current mods and sides (owner decision E1), unless `RDI_IGNORE_MODPACK_TEST=1` waives them
//! (owner decision E8, Kotlin `rdi.ignoreModpackTest`).
use super::drafts::{self, Draft, Drafts};
use super::publish::{self, HttpPublishApi, NewPack, Run, Target};
use super::{archive, media, prepare};
use crate::pack_install::mods::{Side, process_mods};
use crate::pack_install::{self, Deps, LocalInstall};
use anyhow::{Context as _, Result, bail};
use std::path::{Path, PathBuf};
use std::sync::Arc;

pub const IGNORE_TESTS: &str = "RDI_IGNORE_MODPACK_TEST";
pub const STEPS: [&str; 3] = ["上传整合包", "下载剩余客户端Mod", "本地安装整合包"];

/// `IGNORE_MODPACK_TEST`.
pub fn tests_waived() -> bool {
    std::env::var(IGNORE_TESTS).is_ok_and(|v| matches!(v.trim().to_ascii_lowercase().as_str(), "1" | "true"))
}

/// `modpackUploadTaskKey`.
pub fn task_key(update: Option<&str>, name: &str, version: &str) -> String {
    format!("modpack-upload:{}:{}:{}", update.unwrap_or("new"), name.trim().to_lowercase(), version.trim().to_lowercase())
}

/// `requireModpackUploadRuntime`: `McVersion.supportsRuntime`.
pub fn check_runtime(mc_version: &str, loader: &str) -> Result<()> {
    let supported = match mc_version {
        "V211" => loader == "neoforge",
        "V201" => loader == "forge" || loader == "Fabric",
        _ => false,
    };
    if !supported {
        bail!("已识别为Minecraft{}／{loader}，当前版本暂不支持导入", crate::import::scanner::version_label(mc_version));
    }
    Ok(())
}

/// What the test checks of `submitUpload` look at.
pub struct TestGate {
    pub has_server: bool,
    pub mods: Vec<crate::pack_install::mods::Mod>,
    pub fabric: bool,
}

/// `uploadDisabledReason` (tests running) and the test checks of `submitUpload`: without a server
/// pack both tests must have passed, with the current mods and sides (`testedModsSignature`).
pub fn test_gate(gate: &TestGate, tests: &super::tester::Tests) -> Result<()> {
    use super::testrun::Target;
    if tests.running(Target::Server) {
        bail!("服务端测试进行中");
    }
    if tests.running(Target::Client) {
        bail!("客户端测试进行中");
    }
    if gate.has_server {
        return Ok(());
    }
    if !tests.state(Target::Client).passed_for(&gate.mods, gate.fabric) {
        bail!("请先完成客户端测试并通过");
    }
    if !tests.state(Target::Server).passed_for(&gate.mods, gate.fabric) {
        bail!("请先完成服务端测试并通过");
    }
    Ok(())
}

/// What `upload_modpack` needs beyond the draft.
pub struct Needs {
    pub api: crate::api::Client,
    pub deps: Deps,
    /// The game runtime folder (`play::runtime::default_dir`) and `<data>/tools`.
    pub runtime_dir: PathBuf,
    pub tools: PathBuf,
}

pub enum Started {
    Task(String),
    /// The same pack and version is uploading already (Kotlin's dedupe key).
    Running(String),
}

/// `submitUpload` from the draft checks on, then `queueUpload`.
pub async fn start(drafts: &Drafts, id: &str, needs: Needs) -> Result<Started> {
    let (form, summary, update, gate, tests) = drafts.with(id, |draft| {
        let pack = draft.pack_mut()?;
        let gate = TestGate { has_server: pack.server.is_some(), mods: pack.mods.clone(), fabric: pack.fabric };
        Ok((draft.form.clone(), draft.summary.clone(), draft.update.clone(), gate, draft.tests.clone()))
    })?;
    if form.name.trim().is_empty() {
        bail!("整合包名称不能为空");
    }
    if form.version.trim().is_empty() {
        bail!("版本号不能为空");
    }
    let problems = super::draft::problems(&form, update.is_some());
    if let Some(first) = problems.first() {
        bail!("{first}");
    }
    if !tests_waived() {
        test_gate(&gate, &tests)?;
    }
    let key = task_key(update.as_ref().map(|t| t.id.as_str()), &form.name, &form.version);
    if let Some(running) = needs.deps.tasks.active(&key) {
        return Ok(Started::Running(running));
    }
    check_runtime(&summary.mc_version, &summary.loader)?;
    // `ensureAudioReady` of the page: the encoders must work before anything is sent.
    let (runtime_dir, tools, work) = (needs.runtime_dir.clone(), needs.tools.clone(), drafts.with(id, |d| Ok(d.work_dir.clone()))?);
    let encoders = tokio::task::spawn_blocking(move || load_encoders(&runtime_dir, &tools, &work.join("media"))).await.context("准备音频处理模块失败")??;
    let (problems, preflight) = super::validate(&needs.api, &form, &summary, update.as_ref()).await;
    if let Some(first) = problems.first() {
        bail!("{first}");
    }
    if let Some(Err(message)) = preflight {
        bail!("{message}");
    }
    let draft = drafts.take(id, &form)?;
    Ok(Started::Task(queue(draft, needs, encoders, key)))
}

fn load_encoders(runtime_dir: &Path, tools: &Path, work: &Path) -> Result<media::Encoders> {
    let broken = |error: anyhow::Error| {
        tracing::error!(error = %format!("{error:#}"), "音频处理模块不可用");
        anyhow::anyhow!(media::BROKEN)
    };
    let natives = crate::play::runtime::GameRuntime::load(runtime_dir).and_then(|rt| rt.prepare_media_natives(tools)).map_err(broken)?;
    let encoders = media::Encoders::load(&natives, work).map_err(broken)?;
    encoders.ready()?;
    Ok(encoders)
}

/// `createUploadModpackTask2`, submitted.
fn queue(draft: Draft, needs: Needs, encoders: media::Encoders, key: String) -> String {
    let Needs { api, deps, .. } = needs;
    let title = format!("上传并安装整合包 {} {}", draft.form.name, draft.form.version);
    let (runtime, tasks) = (deps.runtime.clone(), deps.tasks.clone());
    tasks.submit_steps(&runtime, title, key, &STEPS, move |ctx| {
        let work_dir = draft.work_dir.clone();
        let mut built: Option<PathBuf> = None;
        let result = run(&deps, &api, &ctx, &draft, &encoders, &mut built);
        if let Some(file) = &built {
            let _ = std::fs::remove_file(file);
        }
        // The extracted pack, staged files and sound work files go with the draft.
        drop(encoders);
        crate::upload::reader::remove_tree(&work_dir);
        result
    })
}

fn run(deps: &Deps, api: &crate::api::Client, ctx: &crate::tasks::Context, draft: &Draft, encoders: &media::Encoders, built: &mut Option<PathBuf>) -> Result<()> {
    let pack = draft.pack.as_ref().context("整合包尚未读取")?;
    let form = &draft.form;
    let processed = process_mods(&pack.mods, pack.fabric);

    ctx.step(0);
    ctx.progress("开始上传整合包", Some(0.));
    let empty = Vec::new();
    let server_extras = pack.server.as_ref().map(|s| &s.loaded.extras).unwrap_or(&empty);
    let input = archive::Input {
        root: &pack.loaded.source_dir,
        base_name: &form.name,
        server_extras,
        excluded: &pack.loaded.embedded_extras,
        client_extras: &pack.loaded.extras,
    };
    let (archived, _) = drafts::read(deps, ctx, &draft.work_dir, |rctx| archive::build(rctx, encoders, &input));
    let file = archived?;
    *built = Some(file.clone());
    let target = match &draft.update {
        Some(t) => Target::Version { modpack_id: t.id.clone(), version: form.version.clone() },
        None => Target::New,
    };
    let (label, starting) = match &draft.update {
        Some(_) => (format!("版本 {}", form.version), format!("上传新版本 {}...", form.version)),
        None => (form.name.clone(), format!("创建新整合包 {}...", form.name)),
    };
    ctx.progress(starting, None);
    let progress = |message: String, fraction: Option<f32>| ctx.progress(message, fraction);
    let send = Run { runtime: &deps.runtime, cancel: &ctx.cancel, options: Default::default(), progress: &progress, label };
    let publisher = Arc::new(HttpPublishApi::new(api.server().clone()));
    let extras = &pack.loaded.extras;
    let summary = publish::send(&send, &publisher, &file, &target, |upload_id| match &draft.update {
        Some(_) => publish::version_body(upload_id, &processed, extras),
        None => {
            let new = NewPack {
                name: form.name.clone(),
                version: form.version.clone(),
                mc_version: draft.summary.mc_version.clone(),
                loader: draft.summary.loader.clone(),
                icon_url: form.icon_url.clone(),
                source_url: form.source_url.clone(),
                info: form.info.clone(),
                categories: form.categories.clone(),
            };
            publish::new_pack_body(upload_id, &new, &processed, extras)
        }
    })?;
    ctx.progress(summary, Some(1.));
    let modpack_id = match &draft.update {
        Some(t) => t.id.clone(),
        None => publish::find_new_pack(&deps.runtime, publisher.as_ref(), &form.name, &form.version)?,
    };

    ctx.step(1);
    let missing: Vec<_> = processed.iter().filter(|m| m.side != Side::Server && m.side != Side::Unknown).filter(|m| prepare::cached(&deps.store.root, m).is_none()).cloned().collect();
    if missing.is_empty() {
        ctx.progress("客户端Mod已齐全", Some(1.));
    } else {
        drafts::download(deps, ctx, missing)?;
    }

    ctx.step(2);
    pack_install::install_local(
        deps,
        ctx,
        LocalInstall {
            modpack_id,
            version: form.version.clone(),
            mc_version: draft.summary.mc_version.clone(),
            fabric: pack.fabric,
            mods: processed.clone(),
            extras: pack.loaded.extras.clone(),
            archive: file,
            embedded_names: pack.loaded.embedded_names.clone(),
        },
    )?;
    ctx.finish(format!("整合包{} {}已上传并安装", form.name, form.version));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_task_key_is_kotlins() {
        assert_eq!(task_key(None, " Sky Frogs ", "1.8.1-Beta"), "modpack-upload:new:sky frogs:1.8.1-beta");
        assert_eq!(task_key(Some("6610000000000000000000ab"), "包", "2"), "modpack-upload:6610000000000000000000ab:包:2");
    }

    #[test]
    fn only_runtimes_rdi_runs_are_uploaded() {
        for (mc, loader) in [("V211", "neoforge"), ("V201", "forge"), ("V201", "Fabric")] {
            assert!(check_runtime(mc, loader).is_ok(), "{mc} {loader}");
        }
        assert_eq!(check_runtime("V211", "Fabric").unwrap_err().to_string(), "已识别为Minecraft1.21.1／Fabric，当前版本暂不支持导入");
        assert!(check_runtime("V201", "neoforge").is_err());
    }
}
