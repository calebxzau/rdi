//! Stage A6c and A6d: the test runs with real child processes standing in for the game and the
//! server, the server command, and the draft's test checks and upload gate.
use super::drafts::Drafts;
use super::reader::Loaded;
use super::source::{Kind, Summary};
use super::submit::{TestGate, test_gate};
use super::tester::{Proc, Tests, server_command};
use super::testrun::{Status, Target, signature};
use crate::content_store::cache_file;
use crate::pack_install::mods::{Mod, Side};
use crate::test_dir::TestDir;
use std::collections::BTreeMap;
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// A process that runs for half a minute unless stopped.
fn sleeper() -> std::process::Child {
    let mut command = if cfg!(windows) {
        let mut c = Command::new("ping");
        c.args(["-n", "30", "127.0.0.1"]);
        c
    } else {
        let mut c = Command::new("sleep");
        c.arg("30");
        c
    };
    command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn().unwrap()
}

/// A process that ends once it reads a line, as the Fabric server does on `stop`.
fn line_reader() -> std::process::Child {
    let mut command = if cfg!(windows) {
        let mut c = Command::new("cmd");
        c.args(["/c", "set /p x="]);
        c
    } else {
        let mut c = Command::new("sh");
        c.args(["-c", "read x"]);
        c
    };
    command.stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::null()).spawn().unwrap()
}

fn ended(process: &Proc, limit: Duration) -> bool {
    let until = Instant::now() + limit;
    while process.alive() {
        if Instant::now() > until {
            return false;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    true
}

fn mr(slug: &str, n: u8, side: Side) -> Mod {
    Mod {
        platform: "mr".into(),
        project_id: format!("p{n}"),
        slug: slug.into(),
        file_id: format!("f{n}"),
        hash: format!("{n:02x}").repeat(20),
        side,
        download_urls: vec![],
        client_only_override: false,
        client_override_replaced: false,
    }
}

#[test]
fn the_marker_passes_a_run_and_stops_its_game() {
    let tests = Tests::default();
    let run = tests.begin(Target::Client, "sig".into()).unwrap();
    assert_eq!(tests.begin(Target::Client, "sig".into()).unwrap_err().to_string(), "测试客户端已经在运行中");
    let process = Arc::new(Proc::new(sleeper(), false));
    assert!(tests.attach(Target::Client, run, process.clone()));

    tests.line(Target::Client, run + 1, "开始运行客户端测试", "");
    assert_eq!(tests.state(Target::Client).status, Status::Running, "an older or newer run's line is ignored");
    tests.line(Target::Client, run, "[Render thread/INFO]: [CHAT] <Steve> 开始运行客户端测试", "");
    let state = tests.state(Target::Client);
    assert_eq!(state.status, Status::Passed);
    assert_eq!(state.signature.as_deref(), Some("sig"));
    assert!(state.pass_seconds.is_some());
    assert_eq!(state.lines().last().unwrap(), "[RDI] 客户端测试通过");
    // A crash line after the pass changes nothing.
    tests.line(Target::Client, run, "Preparing crash report", "");
    assert_eq!(tests.state(Target::Client).status, Status::Passed);

    assert!(ended(&process, Duration::from_secs(10)), "the game is stopped after the pass");
    let end = tests.end(Target::Client, run, Some(Some(1)), None);
    assert_eq!((end.status, end.error), (Status::Passed, None));
    assert!(!tests.busy());
}

#[test]
fn a_crash_fails_the_run_with_the_exit_code() {
    let tests = Tests::default();
    let run = tests.begin(Target::Server, "sig".into()).unwrap();
    assert!(tests.begin(Target::Client, "sig".into()).is_ok(), "the client test runs alongside");
    let process = Arc::new(Proc::new(sleeper(), false));
    assert!(tests.attach(Target::Server, run, process.clone()));
    tests.line(Target::Server, run, "Error: could not open `libraries/x/win_args.txt'", "缺少测试服务端文件");
    assert_eq!(tests.state(Target::Server).lines().last().unwrap(), "缺少测试服务端文件");
    tests.line(Target::Server, run, "---- Minecraft Crash Report ----", "");
    assert_eq!(tests.state(Target::Server).status, Status::Failed);
    assert!(ended(&process, Duration::from_secs(10)));
    let end = tests.end(Target::Server, run, Some(Some(1)), None);
    assert_eq!((end.status, end.error.as_deref()), (Status::Failed, Some("测试服务器异常退出: 1")));
}

#[test]
fn a_run_that_ends_without_the_marker_is_stopped_and_a_failure_names_its_cause() {
    let tests = Tests::default();
    let run = tests.begin(Target::Client, "sig".into()).unwrap();
    tests.request_stop(Target::Client, run);
    let end = tests.end(Target::Client, run, None, None);
    assert_eq!(end.status, Status::Stopped);
    assert_eq!(end.lines().last().unwrap(), "[RDI] 已发送停止客户端测试指令");

    let run = tests.begin(Target::Client, "sig".into()).unwrap();
    let end = tests.end(Target::Client, run, Some(Some(0)), None);
    assert_eq!((end.status, end.error), (Status::Stopped, None), "the player closed the game");

    let run = tests.begin(Target::Client, "sig".into()).unwrap();
    let end = tests.end(Target::Client, run, None, Some("启动客户端测试失败: 没找到可用的完整版64位JDK25".into()));
    assert_eq!(end.status, Status::Failed);
    assert_eq!(end.error.as_deref(), Some("启动客户端测试失败: 没找到可用的完整版64位JDK25"));
}

#[test]
fn closing_stops_the_processes_and_refuses_new_runs() {
    let tests = Tests::default();
    let run = tests.begin(Target::Server, "sig".into()).unwrap();
    let process = Arc::new(Proc::new(sleeper(), false));
    assert!(tests.attach(Target::Server, run, process.clone()));
    // The run's task would end it; here the run ends once its process is gone.
    let ender = {
        let (tests, process) = (tests.clone(), process.clone());
        std::thread::spawn(move || {
            while process.alive() {
                std::thread::sleep(Duration::from_millis(50));
            }
            tests.end(Target::Server, run, Some(None), None);
        })
    };
    assert!(tests.close(Duration::from_secs(10)));
    ender.join().unwrap();
    assert!(!process.alive());
    assert_eq!(tests.begin(Target::Client, "sig".into()).unwrap_err().to_string(), "整合包测试会话已关闭");
    let late = Arc::new(Proc::new(sleeper(), false));
    assert!(!tests.attach(Target::Server, run, late.clone()), "a process of a closed session is not taken");
    late.stop().unwrap();
}

#[test]
fn a_graceful_stop_sends_stop_on_standard_input() {
    let process = Proc::new(line_reader(), true);
    let started = Instant::now();
    process.stop().unwrap();
    assert!(!process.alive());
    assert!(started.elapsed() < Duration::from_secs(15), "it ended on the stop line, not by the force wait");
}

#[test]
fn the_server_command_follows_launch_server() {
    let root = TestDir::new("rdi-a6c-command");
    let deps = crate::mc_install::Deps {
        downloader: crate::download::Downloader { http: reqwest::Client::new() },
        server_base: None,
        auth_headers: Arc::new(|_| Default::default()),
        prefer_mirror: false,
        mc_dir: root.join("mc"),
        java: "java25".into(),
        host: crate::mc_launch::manifest::Host { os_name: "windows".into(), os_arch: "amd64".into(), os_version: "10".into(), path_separator: ";".into() },
    };
    let dir = root.join("servertest-1");
    std::fs::create_dir_all(&dir).unwrap();
    let forge = crate::mc_install::server::ServerRuntime::resolve("1.20.1", "forge").unwrap();
    assert_eq!(
        server_command(&forge, &deps, &dir).unwrap(),
        [
            "java25",
            "-XX:-UseBASE64Intrinsics",
            "-Xmx6G",
            "-Xms6G",
            "-Dfile.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
            "@libraries/net/minecraftforge/forge/1.20.1-47.4.29/win_args.txt",
            "%*",
            "--nogui"
        ]
    );
    assert_eq!(std::fs::read_to_string(dir.join("eula.txt")).unwrap(), "eula=true");
    let fabric = crate::mc_install::server::ServerRuntime::Fabric;
    assert_eq!(server_command(&fabric, &deps, &dir).unwrap_err().to_string(), "Fabric测试服务端未安装或安装不完整，请先下载测试服务端");
}

fn loaded(loader: &str, mods: Vec<Mod>) -> Loaded {
    Loaded {
        summary: Summary { kind: Kind::Modrinth, name: "测试包".into(), version: "1.0".into(), mc_version: "V201".into(), loader: loader.into() },
        source_dir: "work".into(),
        mods,
        embedded_names: BTreeMap::new(),
        embedded_mods: vec![],
        extras: vec![],
        embedded_extras: vec![],
        contains_mca: false,
    }
}

fn cache_jar(cache: &std::path::Path, m: &Mod) {
    let file = cache_file(cache, &m.digest().unwrap());
    std::fs::create_dir_all(file.parent().unwrap()).unwrap();
    std::fs::write(file, "jar").unwrap();
}

#[test]
fn a_test_needs_every_jar_and_a_test_server_for_its_version() {
    let dir = TestDir::new("rdi-a6d-input");
    let cache = dir.join("cache");
    let drafts = Drafts::new(dir.join("root"));
    let (a, b) = (mr("alpha", 1, Side::Both), mr("beta", 2, Side::Client));
    let id = drafts.insert_read(loaded("forge", vec![a.clone(), b.clone()]), &cache);
    cache_jar(&cache, &a);
    let error = drafts.test_input(&id, Target::Client).err().unwrap().to_string();
    assert_eq!(error, "客户端测试需要先下载缺失Mod1个，请先用upload_modpack_download_mods下载");
    cache_jar(&cache, &b);
    let (input, _) = drafts.test_input(&id, Target::Server).unwrap();
    assert_eq!((input.mc_version.as_str(), input.mc_label.as_str(), input.loader.as_str()), ("1.20.1", "V201", "forge"));
    assert!(input.work.ends_with("tests"));

    let other = drafts.insert_read(loaded("neoforge", vec![]), &cache);
    assert_eq!(drafts.test_input(&other, Target::Server).err().unwrap().to_string(), "不支持1.20.1 neoforge测试服务端");
}

#[test]
fn the_upload_needs_both_tests_passed_with_the_current_sides() {
    let dir = TestDir::new("rdi-a6d-gate");
    let drafts = Drafts::new(dir.join("root"));
    let mods = vec![mr("alpha", 1, Side::Both), mr("beta", 2, Side::Client)];
    let id = drafts.insert_read(loaded("forge", mods.clone()), &dir.join("cache"));
    let tests = drafts.with(&id, |d| Ok(d.tests.clone())).unwrap();
    let gate = |has_server: bool| {
        let mods = drafts.with(&id, |d| Ok(d.pack.as_ref().unwrap().mods.clone())).unwrap();
        test_gate(&TestGate { has_server, mods, fabric: false }, &tests).err().map(|e| e.to_string())
    };
    assert_eq!(gate(false).as_deref(), Some("请先完成客户端测试并通过"));
    assert_eq!(gate(true), None, "a server pack needs no tests");

    for target in [Target::Client, Target::Server] {
        let run = tests.begin(target, signature(&mods)).unwrap();
        assert_eq!(gate(true).as_deref(), Some(if target == Target::Client { "客户端测试进行中" } else { "服务端测试进行中" }));
        let marker = if target == Target::Client { "开始运行客户端测试" } else { "Done (4.2s)! For help" };
        tests.line(target, run, marker, "");
        tests.end(target, run, Some(Some(0)), None);
        if target == Target::Client {
            assert_eq!(gate(false).as_deref(), Some("请先完成服务端测试并通过"));
        }
    }
    assert_eq!(gate(false), None);
    assert_eq!(tests.state(Target::Server).pass_seconds.as_deref(), Some("4.2"));
    let view = drafts.with(&id, |d| Ok(d.view())).unwrap();
    assert_eq!(view["tests"]["client"]["status"], "passed");

    // A side change makes both passes count no more.
    drafts.with(&id, |d| d.set_sides(&[("beta".into(), Side::Both)])).unwrap();
    assert_eq!(gate(false).as_deref(), Some("请先完成客户端测试并通过"));
    assert_eq!(tests.state(Target::Server).status, Status::NotRun);
}

#[test]
fn a_draft_with_a_running_test_neither_expires_nor_uploads() {
    let dir = TestDir::new("rdi-a6d-busy");
    let drafts = Drafts::new(dir.join("root"));
    let id = drafts.insert_read(loaded("forge", vec![]), &dir.join("cache"));
    let (tests, form) = drafts.with(&id, |d| Ok((d.tests.clone(), d.form.clone()))).unwrap();
    let run = tests.begin(Target::Server, "sig".into()).unwrap();
    drafts.age(super::drafts::IDLE + Duration::from_secs(1));
    assert!(drafts.with(&id, |_| Ok(())).is_ok(), "not expired while the test runs");
    assert_eq!(drafts.take(&id, &form).err().unwrap().to_string(), "整合包测试进行中，请等测试结束或取消测试任务后再上传");
    tests.end(Target::Server, run, Some(Some(0)), None);
    assert!(drafts.take(&id, &form).is_ok());
}
