//! J5b B5: games Rust started survive a launcher crash. `<data>/running_games.json` lists every
//! game process with its identity (process id, start time, Java image name); a launcher started
//! later takes the ones that still match back into its game list (Kotlin `GameRecovery`).
//! A recovered game has no console output and no exit code; stopping it still works.
//! No tokens and no launch arguments are written. Compose does not read this file.
use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

const SCHEMA: u32 = 1;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    pub session_id: String,
    pub title: String,
    pub version_id: String,
    pub pid: u32,
    /// Seconds since the Unix epoch, as the OS reports the process start.
    pub started_at: u64,
    /// A room game, which needs the room connection again.
    pub room: bool,
    /// The local port the room game connects to.
    #[serde(default)]
    pub room_port: Option<u16>,
}

#[derive(Serialize, Deserialize)]
struct Journal {
    schema: u32,
    games: Vec<Entry>,
}

pub fn path() -> PathBuf {
    crate::paths::data_dir().join("running_games.json")
}

/// A missing file is an empty list; a file of another schema is ignored.
pub fn read(path: &Path) -> Result<Vec<Entry>> {
    let text = match std::fs::read_to_string(path) {
        Ok(text) => text,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
        Err(error) => return Err(error).with_context(|| format!("读取运行中的游戏记录失败: {}", path.display())),
    };
    let journal: Journal = serde_json::from_str(&text).with_context(|| format!("运行中的游戏记录格式无效: {}", path.display()))?;
    if journal.schema != SCHEMA {
        tracing::warn!(schema = journal.schema, "运行中的游戏记录版本不同，已忽略");
        return Ok(vec![]);
    }
    Ok(journal.games)
}

/// Writes through a temporary file and a rename, so a crash never leaves half a file.
pub fn write(path: &Path, games: &[Entry]) -> Result<()> {
    let text = serde_json::to_string_pretty(&Journal { schema: SCHEMA, games: games.to_vec() })?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension("json.tmp");
    std::fs::write(&temporary, text)?;
    std::fs::rename(&temporary, path).with_context(|| format!("写入运行中的游戏记录失败: {}", path.display()))
}

/// `java`, `java.exe` or `javaw.exe`, as Kotlin checks it.
pub fn is_java(image: &str) -> bool {
    matches!(image.to_lowercase().as_str(), "java" | "java.exe" | "javaw.exe")
}

/// The process's start time and image name, or `None` when it is gone.
pub fn identity(pid: u32) -> Option<(u64, String)> {
    let mut system = match sysinfo::System::new() {
        Ok(system) => system,
        Err(error) => {
            tracing::warn!(%error, "读取进程信息失败");
            return None;
        }
    };
    let pid = sysinfo::Pid::from_u32(pid);
    system.refresh_processes_specifics(sysinfo::ProcessesToUpdate::Some(&[pid]), true, sysinfo::ProcessRefreshKind::nothing());
    let process = system.process(pid)?;
    Some((process.start_time(), process.name().to_string_lossy().into_owned()))
}

/// The same process as when it was recorded: alive, same start time, a Java image.
pub fn matches(entry: &Entry) -> bool {
    identity(entry.pid).is_some_and(|(started, image)| started == entry.started_at && is_java(&image))
}

/// Ends a recovered process: a request to end on macOS/Linux unless forced, as Java's `destroy`.
pub fn kill(pid: u32, started_at: u64, force: bool) {
    let Ok(mut system) = sysinfo::System::new() else {
        tracing::warn!(pid, "读取进程信息失败，无法结束游戏");
        return;
    };
    let id = sysinfo::Pid::from_u32(pid);
    system.refresh_processes_specifics(sysinfo::ProcessesToUpdate::Some(&[id]), true, sysinfo::ProcessRefreshKind::nothing());
    let Some(process) = system.process(id).filter(|p| p.start_time() == started_at) else {
        return;
    };
    if !force && cfg!(unix) && process.kill_with(sysinfo::Signal::Term) == Some(true) {
        return;
    }
    if !process.kill() {
        tracing::warn!(pid, "结束游戏进程失败");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(pid: u32, started_at: u64) -> Entry {
        Entry {
            session_id: "s1".into(),
            title: "游玩 我的房间".into(),
            version_id: "pack_1.0".into(),
            pid,
            started_at,
            room: true,
            room_port: Some(55667),
        }
    }

    #[test]
    fn journal_round_trips_and_tolerates_missing_or_other_schema() {
        let dir = std::env::temp_dir().join(format!("rdi-running-games-{}", uuid::Uuid::now_v7()));
        let file = dir.join("running_games.json");
        assert!(read(&file).unwrap().is_empty());
        write(&file, &[entry(42, 7)]).unwrap();
        assert_eq!(read(&file).unwrap(), [entry(42, 7)]);
        assert!(!file.with_extension("json.tmp").exists());
        let text = std::fs::read_to_string(&file).unwrap();
        assert!(!text.contains("token") && text.contains("\"startedAt\""));
        std::fs::write(&file, r#"{"schema":9,"games":[]}"#).unwrap();
        assert!(read(&file).unwrap().is_empty());
        std::fs::write(&file, "{").unwrap();
        assert!(read(&file).is_err());
    }

    #[test]
    fn java_images_follow_kotlin() {
        assert!(is_java("java") && is_java("JAVA.EXE") && is_java("javaw.exe"));
        assert!(!is_java("cmd.exe") && !is_java("javaws.exe"));
    }

    #[test]
    fn identity_matches_only_the_same_live_java_process() {
        let (started, image) = identity(std::process::id()).unwrap();
        assert!(started > 0 && !image.is_empty());
        // The test binary is not Java, so even the right start time does not match.
        assert!(!matches(&entry(std::process::id(), started)));
        assert!(!matches(&entry(u32::MAX - 1, started)));
    }
}
