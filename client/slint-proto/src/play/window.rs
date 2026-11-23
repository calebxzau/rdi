//! J5b: the room game's window title (`GameWindowService` / `gameWindowTitle`). Lengths count
//! UTF-16 units like Kotlin, and a cut never splits a surrogate pair.
//! `protocol/fixtures/j5b-window.json` (Kotlin `J5bWindowTitleFixtureTest`) guards it.
const LIMIT: usize = 64;
const SEPARATOR: &str = " · ";

fn clean(value: &str) -> String {
    value.chars().map(|c| if c.is_ascii_control() { ' ' } else { c }).collect::<String>().trim().to_owned()
}

fn units(value: &str) -> usize {
    value.encode_utf16().count()
}

fn shorten(value: &str, limit: usize) -> String {
    let utf16: Vec<u16> = value.encode_utf16().collect();
    if utf16.len() <= limit {
        return value.to_owned();
    }
    let mut end = limit.saturating_sub(1);
    if end > 0 && (0xD800..0xDC00).contains(&utf16[end - 1]) {
        end -= 1;
    }
    format!("{}…", String::from_utf16_lossy(&utf16[..end]))
}

/// `gameWindowTitle`.
pub fn title(pack_name: &str, host_name: Option<&str>, version_name: &str) -> String {
    let name = Some(clean(pack_name)).filter(|n| !n.is_empty()).unwrap_or_else(|| "Minecraft".into());
    let version = clean(version_name);
    let pack = if version.is_empty() { name } else { format!("{name} {version}") };
    let Some(host) = host_name.map(clean).filter(|h| !h.is_empty()) else {
        return shorten(&pack, LIMIT);
    };
    let budget = LIMIT - units(SEPARATOR);
    let pack_limit = units(&pack).min((budget / 2).max(budget.saturating_sub(units(&host))));
    format!("{}{SEPARATOR}{}", shorten(&pack, pack_limit), shorten(&host, budget - pack_limit))
}

/// The window title argument (`-Drdi.window.title=...`).
pub fn title_jvm_arg(pack_name: &str, host_name: Option<&str>, version_name: &str) -> String {
    format!("-Drdi.window.title={}", title(pack_name, host_name, version_name))
}

#[cfg(test)]
mod tests {
    use super::*;

    const FIXTURE: &str = include_str!("../../protocol/fixtures/j5b-window.json");

    #[test]
    fn titles_match_the_kotlin_fixture() {
        let cases: Vec<serde_json::Value> = serde_json::from_str(FIXTURE).unwrap();
        assert!(cases.len() >= 9);
        for case in cases {
            let built = title(case["pack"].as_str().unwrap(), case["host"].as_str(), case["version"].as_str().unwrap());
            assert_eq!(built, case["title"].as_str().unwrap(), "{case}");
        }
    }
}
