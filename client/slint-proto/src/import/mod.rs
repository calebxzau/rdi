pub mod installer;
pub mod scanner;
pub mod state;

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;
    use std::{
        path::PathBuf,
        time::{Duration, Instant},
    };
    fn fixture() -> Value {
        serde_json::from_str(include_str!("../../protocol/fixtures/j4a.json")).unwrap()
    }
    fn temp() -> PathBuf {
        let path = std::env::temp_dir().join(format!("j4-{}", uuid::Uuid::now_v7()));
        std::fs::create_dir_all(&path).unwrap();
        path
    }
    #[test]
    fn shared_scanner_manifests() {
        let root = temp();
        for case in fixture()["scanner"].as_array().unwrap() {
            let name = case["name"].as_str().unwrap();
            let dir = root.join("versions").join(name);
            std::fs::create_dir_all(dir.join("mods")).unwrap();
            std::fs::write(dir.join("mods/a.JAR"), [1]).unwrap();
            std::fs::write(
                dir.join(format!("{name}.json")),
                case["manifest"].to_string(),
            )
            .unwrap();
            let result = scanner::candidate(&dir);
            if let Some(loader) = case["loader"].as_str() {
                assert_eq!(result.unwrap().mod_loader, loader, "{name}");
            } else {
                assert!(result.is_err(), "{name}");
            }
        }
        let db = root.join("data.db");
        let conn = rusqlite::Connection::open(&db).unwrap();
        conn.execute_batch("PRAGMA application_id=5391433; PRAGMA user_version=5; CREATE TABLE minecraft_installation(path TEXT);").unwrap();
        conn.execute(
            "INSERT INTO minecraft_installation VALUES (?1)",
            [root.to_str().unwrap()],
        )
        .unwrap();
        assert_eq!(scanner::scan(&db, &root.join("managed")).len(), 4);
        assert!(scanner::scan(&db, &root).is_empty());
        conn.execute_batch("PRAGMA user_version=6;").unwrap();
        assert!(scanner::scan(&db, &root.join("managed")).is_empty());
        assert!(scanner::scan(&root.join("missing.db"), &root).is_empty());
        assert!(!root.join("missing.db").exists());
    }
    #[test]
    fn shared_copy_options_and_names() {
        let f = fixture();
        for c in f["copy"].as_array().unwrap() {
            assert_eq!(
                installer::should_copy(
                    c["path"].as_str().unwrap(),
                    "pack",
                    c["includeSaves"].as_bool().unwrap()
                ),
                c["expected"].as_bool().unwrap()
            );
        }
        for (v, text) in f["options"].as_object().unwrap() {
            assert_eq!(installer::options(v), text.as_str().unwrap());
        }
        for c in f["uniqueNames"].as_array().unwrap() {
            assert_eq!(
                installer::unique_name(
                    c["name"].as_str().unwrap(),
                    &c["existing"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .map(|s| s.as_str().unwrap().into())
                        .collect::<Vec<_>>()
                ),
                c["expected"].as_str().unwrap()
            );
        }
        let root = temp();
        assert!(installer::checked_target(&root, std::path::Path::new("../escape")).is_err());
        assert!(installer::checked_target(&root, std::path::Path::new("mods/a.jar")).is_ok());
    }
    fn wait(tasks: &crate::tasks::Manager) {
        let until = Instant::now() + Duration::from_secs(5);
        while tasks.running() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(1));
        }
        assert!(!tasks.running());
    }
    #[test]
    fn installs_copy_saves_options_and_unique_names() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        let tasks = crate::tasks::Manager::default();
        let root = temp();
        let source = root.join("source");
        let versions = root.join("versions");
        for name in [
            "mods/a.jar",
            "saves/world/level.dat",
            "logs/a.txt",
            "config/a.txt",
            "options.txt",
        ] {
            let path = source.join(name);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(path, b"original").unwrap();
        }
        for saves in [false, true] {
            let request = installer::Request {
                source: Some(source.clone()),
                include_saves: saves,
                version: "V201".into(),
                loader: "Fabric".into(),
                name: "Pack".into(),
                comment: String::new(),
                icon: String::new(),
            };
            let versions = versions.clone();
            let id = uuid::Uuid::now_v7().to_string();
            let target = versions.join(format!("local_{id}"));
            let run = tasks.submit(runtime.handle(), "导入".into(), id.clone(), move |c| {
                installer::install(&versions, &id, &request, &c)
            });
            wait(&tasks);
            assert_eq!(tasks.snapshot(&run).unwrap()["status"], 2);
            assert_eq!(target.join("saves/world/level.dat").exists(), saves);
            assert!(!target.join("logs").exists());
            assert_eq!(
                std::fs::read(target.join("options.txt")).unwrap(),
                b"original"
            );
            let info: Value = serde_json::from_str(
                &std::fs::read_to_string(target.join("rdi-local-pack.json")).unwrap(),
            )
            .unwrap();
            assert_eq!(info["name"], if saves { "Pack (2)" } else { "Pack" });
            assert_eq!(info["mcVersion"], "V201");
        }
    }
    #[test]
    fn cancellation_during_copy_removes_staging() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        let tasks = crate::tasks::Manager::default();
        let root = temp();
        let source = root.join("source");
        std::fs::create_dir_all(source.join("mods")).unwrap();
        let file = std::fs::File::create(source.join("mods/large.jar")).unwrap();
        file.set_len(512 * 1024 * 1024).unwrap();
        let request = installer::Request {
            source: Some(source),
            include_saves: false,
            version: "V211".into(),
            loader: "neoforge".into(),
            name: "Cancel".into(),
            comment: String::new(),
            icon: String::new(),
        };
        let versions = root.join("versions");
        let worker_versions = versions.clone();
        let id = uuid::Uuid::now_v7().to_string();
        let staging = versions.join(format!("local_{id}.installing"));
        let target = versions.join(format!("local_{id}"));
        let run = tasks.submit(runtime.handle(), "复制".into(), id.clone(), move |c| {
            installer::install(&worker_versions, &id, &request, &c)
        });
        let until = Instant::now() + Duration::from_secs(5);
        while !staging.join("mods/large.jar").exists() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(1));
        }
        assert!(staging.exists());
        tasks.cancel(&run);
        wait(&tasks);
        assert_eq!(tasks.snapshot(&run).unwrap()["status"], 4);
        assert!(!target.exists());
        assert!(!staging.exists());
    }
    #[test]
    fn editor_validates_and_keeps_runtime_fixed_for_candidates() {
        let mut state = state::State::open();
        state.action("create", "", 0).unwrap();
        state.action("name", "", 0).unwrap();
        assert_eq!(
            state.request().err().unwrap().to_string(),
            "整合包名称不能为空"
        );
        state.action("name", "new", 0).unwrap();
        state.action("icon", "http://example.com/a", 0).unwrap();
        assert_eq!(
            state.request().err().unwrap().to_string(),
            "图标链接必须是HTTPS网络地址"
        );
    }
}
