use super::*;

#[test]
fn offline_accounts_match_java_name_uuids() {
    // UUID.nameUUIDFromBytes("OfflinePlayer:Steve") in Java.
    assert_eq!(offline_account(" Steve ").unwrap().uuid, "5627dd98-e6be-3c21-b8a8-e92344183641");
    let a = offline_account("玩家_1").unwrap();
    assert_eq!((a.access_token.as_str(), a.user_type.as_str()), ("0", "legacy"));
    assert_eq!(offline_account("").err().unwrap().to_string(), "请输入游戏昵称");
    assert_eq!(offline_account("a b").err().unwrap().to_string(), "游戏昵称不能包含空格");
    assert_eq!(offline_account("12345678901234567").err().unwrap().to_string(), "游戏昵称最多16个字符");
}

#[test]
fn duplicate_check_counts_rust_and_service_sessions() {
    let s = Sessions::default();
    let first = s.admit("单机 - A", "local_a", false, 0).unwrap();
    assert!(s.admit("单机 - A", "local_a", false, 0).is_none());
    assert!(s.admit("单机 - B", "local_b", false, 1).is_none(), "the service runs it");
    let second = s.admit("单机 - A", "local_a", true, 0).unwrap();
    assert_eq!(s.alive_count("local_a"), 2);
    s.stop(&first, false).unwrap();
    s.stop(&second, true).unwrap();
    assert_eq!(s.alive_count("local_a"), 0);
    let views = s.views();
    assert_eq!(views[0].exit_message.as_deref(), Some("已停止"));
    assert_eq!(views[1].exit_message.as_deref(), Some("已强制结束"));
    assert!(s.stop("missing", false).is_err());
}

#[test]
fn stop_kills_a_running_process_and_attach_after_stop_kills_it_too() {
    let s = Sessions::default();
    let sleeper = || {
        if cfg!(windows) {
            std::process::Command::new("cmd").args(["/c", "ping -n 30 127.0.0.1 >NUL"]).spawn().unwrap()
        } else {
            std::process::Command::new("sleep").arg("30").spawn().unwrap()
        }
    };
    let id = s.admit("t", "v", false, 0).unwrap();
    let child = s.attach(&id, sleeper()).unwrap();
    assert!(s.views()[0].running);
    s.stop(&id, true).unwrap();
    assert!(child.lock().unwrap().wait().is_ok());
    assert_eq!(s.alive_count("v"), 0);
    let late = s.admit("t", "v", false, 0).unwrap();
    s.stop(&late, false).unwrap();
    assert!(s.attach(&late, sleeper()).is_none());
}

#[test]
fn console_lines_are_capped_and_cleared() {
    let s = Sessions::default();
    let id = s.admit("t", "v", false, 0).unwrap();
    for i in 0..(MAX_LOG_LINES + 10) {
        s.append(&id, format!("line {i}"));
    }
    let lines = s.lines(&id);
    assert_eq!(lines.len(), MAX_LOG_LINES);
    assert_eq!(lines[0], "line 10");
    s.clear(&id);
    assert!(s.lines(&id).is_empty());
    let long = bounded_lines(vec!["x".repeat(10_000); 100]);
    assert_eq!(long[0].len(), 4096);
    assert_eq!(long.len(), 1_000_000 / (4096 * 6 + 8));
}

#[test]
fn early_display_cleanup_and_window_arguments() {
    let dir = std::env::temp_dir().join(format!("rdi-j5a-play-{}", uuid::Uuid::now_v7()));
    std::fs::create_dir_all(dir.join("config")).unwrap();
    std::fs::create_dir_all(dir.join("mods")).unwrap();
    std::fs::write(dir.join("config/fml.toml"), "earlyWindowControl = true\n  earlyWindowProvider = \"rdiearlywindow\"\n").unwrap();
    std::fs::write(dir.join("mods/rdi-early-display.jar"), b"x").unwrap();
    clean_early_display(&dir).unwrap();
    assert_eq!(std::fs::read_to_string(dir.join("config/fml.toml")).unwrap(), "earlyWindowControl = true\n  earlyWindowProvider = \"fmlearlywindow\"\n");
    assert!(!dir.join("mods/rdi-early-display.jar").exists());
    clean_early_display(&dir).unwrap();
    std::fs::write(dir.join("rdi-local-pack.json"), r#"{"id":"x","name":"测试","mcVersion":"V211","modLoader":"neoforge","createdAt":1,"source":"Empty","extra":1}"#).unwrap();
    remember_offline_name(&dir, "Steve").unwrap();
    let info: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(dir.join("rdi-local-pack.json")).unwrap()).unwrap();
    assert_eq!((info["lastAccountMode"].as_str(), info["lastOfflineName"].as_str(), info["extra"].as_i64()), (Some("Offline"), Some("Steve"), Some(1)));
    std::fs::remove_dir_all(dir).unwrap();
    assert!(is_window_jvm_arg("-Drdi.window.title=x") && is_window_jvm_arg("-Drdi.window.icon") && !is_window_jvm_arg("-Drdi.windows=1"));
}
