use std::collections::{HashSet, VecDeque};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

const MAX_DIRECTORY_DEPTH: usize = 6;
const MAX_DIRECTORIES: usize = 4096;
const MAX_REGISTRY_KEYS: usize = 512;
const MAX_REGISTRY_DEPTH: usize = 5;

/// Returns plausible installed JDK executable paths. Callers still need to run
/// the candidate and verify its Java and javac versions before using it.
pub fn candidate_paths() -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    let mut scanned_directories = 0;

    for variable in ["JAVA_HOME", "JDK_HOME"] {
        if let Some(value) = env::var_os(variable) {
            add_direct_candidate(Path::new(&value), &mut candidates);
        }
    }
    if let Some(path) = env::var_os("PATH") {
        for directory in env::split_paths(&path) {
            add_direct_candidate(&directory, &mut candidates);
        }
    }

    for path in registry_install_paths() {
        add_direct_candidate(&path, &mut candidates);
    }

    for root in known_roots() {
        if root.microsoft_jdks_only {
            scan_named_children(
                &root.path,
                |name| name.to_ascii_lowercase().starts_with("jdk-"),
                &mut scanned_directories,
                &mut candidates,
            );
        } else {
            scan_tree(
                &root.path,
                MAX_DIRECTORY_DEPTH,
                &mut scanned_directories,
                &mut candidates,
            );
        }
    }

    deduplicate(candidates)
}

fn add_direct_candidate(path: &Path, candidates: &mut Vec<PathBuf>) {
    if path
        .file_name()
        .is_some_and(|name| name.eq_ignore_ascii_case("java.exe"))
    {
        add_candidate(path, candidates);
        return;
    }

    if path
        .file_name()
        .is_some_and(|name| name.eq_ignore_ascii_case("bin"))
    {
        add_candidate(&path.join("java.exe"), candidates);
        return;
    }

    add_candidate(&path.join("java.exe"), candidates);
    add_candidate(&path.join("bin").join("java.exe"), candidates);
}

fn add_candidate(java: &Path, candidates: &mut Vec<PathBuf>) {
    let Some(bin) = java.parent() else { return };
    if java.is_file() && bin.join("javac.exe").is_file() && bin.join("javaw.exe").is_file() {
        candidates.push(java.to_path_buf());
    }
}

fn scan_tree(root: &Path, max_depth: usize, visited: &mut usize, candidates: &mut Vec<PathBuf>) {
    let mut pending = VecDeque::from([(root.to_path_buf(), 0usize)]);
    while let Some((directory, depth)) = pending.pop_front() {
        if *visited >= MAX_DIRECTORIES {
            break;
        }
        *visited += 1;
        add_candidate(&directory.join("java.exe"), candidates);
        if depth >= max_depth || !is_scannable_directory(&directory) {
            continue;
        }
        let Ok(entries) = fs::read_dir(&directory) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if pending.len() + *visited < MAX_DIRECTORIES && is_scannable_directory(&path) {
                pending.push_back((path, depth + 1));
            }
        }
    }
}

fn scan_named_children(
    root: &Path,
    matches: impl Fn(&str) -> bool,
    visited: &mut usize,
    candidates: &mut Vec<PathBuf>,
) {
    if !is_scannable_directory(root) || *visited >= MAX_DIRECTORIES {
        return;
    }
    *visited += 1;
    let Ok(entries) = fs::read_dir(root) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        if matches(name) && is_scannable_directory(&path) {
            scan_tree(&path, MAX_DIRECTORY_DEPTH, visited, candidates);
        }
    }
}

#[cfg(windows)]
fn is_scannable_directory(path: &Path) -> bool {
    use std::os::windows::fs::MetadataExt;
    const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x400;
    fs::symlink_metadata(path).is_ok_and(|metadata| {
        metadata.is_dir() && metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT == 0
    })
}

#[cfg(not(windows))]
fn is_scannable_directory(path: &Path) -> bool {
    fs::symlink_metadata(path)
        .is_ok_and(|metadata| metadata.is_dir() && !metadata.file_type().is_symlink())
}

fn deduplicate(paths: Vec<PathBuf>) -> Vec<PathBuf> {
    let mut seen = HashSet::new();
    paths
        .into_iter()
        .filter_map(|path| {
            let canonical = fs::canonicalize(&path).ok()?;
            let key = canonical.to_string_lossy().to_lowercase();
            seen.insert(key).then_some(canonical)
        })
        .collect()
}

struct KnownRoot {
    path: PathBuf,
    microsoft_jdks_only: bool,
}

fn known_roots() -> Vec<KnownRoot> {
    let mut roots = Vec::new();
    for variable in ["ProgramFiles", "ProgramW6432"] {
        if let Some(base) = env::var_os(variable).map(PathBuf::from) {
            for vendor in [
                "Java",
                "Eclipse Adoptium",
                "AdoptOpenJDK",
                "Amazon Corretto",
                "Zulu",
                "BellSoft",
                "Microsoft",
                "Eclipse Foundation",
                "Semeru",
            ] {
                roots.push(KnownRoot {
                    path: base.join(vendor),
                    microsoft_jdks_only: vendor == "Microsoft",
                });
            }
        }
    }

    if let Some(user) = env::var_os("USERPROFILE").map(PathBuf::from) {
        for relative in [".jdks", ".gradle/jdks", ".sdkman/candidates/java"] {
            roots.push(KnownRoot {
                path: user.join(relative),
                microsoft_jdks_only: false,
            });
        }
        roots.push(KnownRoot {
            path: user.join("curseforge/minecraft/Install/runtime"),
            microsoft_jdks_only: false,
        });
    }
    if let Some(appdata) = env::var_os("APPDATA").map(PathBuf::from) {
        for relative in [
            ".minecraft/runtime",
            ".hmcl/java",
            "PrismLauncher/java",
            "ModrinthApp/meta/java_versions",
            "ATLauncher/runtimes/minecraft",
        ] {
            roots.push(KnownRoot {
                path: appdata.join(relative),
                microsoft_jdks_only: false,
            });
        }
    }
    if let Some(local_appdata) = env::var_os("LOCALAPPDATA").map(PathBuf::from) {
        roots.push(KnownRoot {
            path: local_appdata.join(".ftba/bin/runtime"),
            microsoft_jdks_only: false,
        });
    }
    roots
}

#[cfg(windows)]
fn registry_install_paths() -> Vec<PathBuf> {
    use std::os::windows::ffi::OsStringExt;
    use windows_sys::Win32::System::Registry::{
        HKEY, HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE, KEY_READ, KEY_WOW64_32KEY, KEY_WOW64_64KEY,
        REG_EXPAND_SZ, REG_SZ, RegCloseKey, RegEnumKeyExW, RegOpenKeyExW, RegQueryValueExW,
    };

    const REGISTRY_BASES: &[&str] = &[
        r"SOFTWARE\JavaSoft\JDK",
        r"SOFTWARE\JavaSoft\Java Development Kit",
        r"SOFTWARE\Eclipse Adoptium\JDK",
        r"SOFTWARE\AdoptOpenJDK\JDK",
        r"SOFTWARE\Microsoft\JDK",
        r"SOFTWARE\Azul Systems\Zulu",
        r"SOFTWARE\BellSoft\Liberica",
        r"SOFTWARE\Eclipse Foundation\JDK",
        r"SOFTWARE\IBM\Semeru",
    ];
    let accepted_values = ["javahome", "path", "installationpath"];
    let mut paths = Vec::new();
    let mut visited_keys = 0usize;

    fn read_values(key: HKEY, paths: &mut Vec<PathBuf>, accepted_values: &[&str]) {
        for name in accepted_values {
            let name_wide: Vec<u16> = name.encode_utf16().chain(Some(0)).collect();
            let mut value_type = 0u32;
            let mut bytes = 0u32;
            let status = unsafe {
                RegQueryValueExW(
                    key,
                    name_wide.as_ptr(),
                    std::ptr::null(),
                    &mut value_type,
                    std::ptr::null_mut(),
                    &mut bytes,
                )
            };
            if status != 0
                || bytes < 2
                || bytes % 2 != 0
                || bytes > 32768
                || (value_type != REG_SZ && value_type != REG_EXPAND_SZ)
            {
                continue;
            }
            let allocated_bytes = bytes;
            let mut data = vec![0u16; allocated_bytes as usize / 2];
            let status = unsafe {
                RegQueryValueExW(
                    key,
                    name_wide.as_ptr(),
                    std::ptr::null(),
                    &mut value_type,
                    data.as_mut_ptr().cast::<u8>(),
                    &mut bytes,
                )
            };
            if status == 0
                && bytes <= allocated_bytes
                && bytes % 2 == 0
                && (value_type == REG_SZ || value_type == REG_EXPAND_SZ)
            {
                data.truncate(bytes as usize / 2);
                while data.last() == Some(&0) {
                    data.pop();
                }
                if !data.is_empty() {
                    let value = std::ffi::OsString::from_wide(&data);
                    let value = expand_percent_variables(&value);
                    paths.push(PathBuf::from(value));
                }
            }
        }
    }

    fn visit(
        parent: HKEY,
        subkey: &str,
        depth: usize,
        flags: u32,
        visited_keys: &mut usize,
        paths: &mut Vec<PathBuf>,
        accepted_values: &[&str],
    ) {
        if *visited_keys >= MAX_REGISTRY_KEYS {
            return;
        }
        let wide: Vec<u16> = subkey.encode_utf16().chain(Some(0)).collect();
        let mut key: HKEY = std::ptr::null_mut();
        if unsafe { RegOpenKeyExW(parent, wide.as_ptr(), 0, KEY_READ | flags, &mut key) } != 0 {
            return;
        }
        *visited_keys += 1;
        read_values(key, paths, accepted_values);
        if depth < MAX_REGISTRY_DEPTH {
            for index in 0..128u32 {
                if *visited_keys >= MAX_REGISTRY_KEYS {
                    break;
                }
                let mut name = [0u16; 512];
                let mut length = name.len() as u32;
                let status = unsafe {
                    RegEnumKeyExW(
                        key,
                        index,
                        name.as_mut_ptr(),
                        &mut length,
                        std::ptr::null_mut(),
                        std::ptr::null_mut(),
                        std::ptr::null_mut(),
                        std::ptr::null_mut(),
                    )
                };
                if status != 0 {
                    break;
                }
                let child = String::from_utf16_lossy(&name[..length as usize]);
                let child_path = format!("{subkey}\\{child}");
                visit(
                    parent,
                    &child_path,
                    depth + 1,
                    flags,
                    visited_keys,
                    paths,
                    accepted_values,
                );
            }
        }
        unsafe { RegCloseKey(key) };
    }

    let hives = [HKEY_LOCAL_MACHINE, HKEY_CURRENT_USER];
    for hive in hives {
        for flags in [KEY_WOW64_32KEY, KEY_WOW64_64KEY] {
            for base in REGISTRY_BASES {
                visit(
                    hive,
                    base,
                    0,
                    flags,
                    &mut visited_keys,
                    &mut paths,
                    &accepted_values,
                );
            }
        }
    }
    paths
}

#[cfg(windows)]
fn expand_percent_variables(value: &std::ffi::OsStr) -> std::ffi::OsString {
    let value = value.to_string_lossy();
    let mut expanded = String::with_capacity(value.len());
    let mut remaining = value.as_ref();
    while let Some(start) = remaining.find('%') {
        expanded.push_str(&remaining[..start]);
        let after_start = &remaining[start + 1..];
        let Some(end) = after_start.find('%') else {
            expanded.push_str(&remaining[start..]);
            remaining = "";
            break;
        };
        let name = &after_start[..end];
        if let Some(replacement) = env::var_os(name) {
            expanded.push_str(&replacement.to_string_lossy());
        } else {
            expanded.push_str(&remaining[start..start + end + 2]);
        }
        remaining = &after_start[end + 1..];
    }
    expanded.push_str(remaining);
    expanded.into()
}

#[cfg(not(windows))]
fn registry_install_paths() -> Vec<PathBuf> {
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn fixture_root() -> PathBuf {
        let suffix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        env::temp_dir().join(format!(
            "rdi-java-discovery-{}-{suffix}",
            std::process::id()
        ))
    }

    fn create_jdk(home: &Path) -> PathBuf {
        let bin = home.join("bin");
        fs::create_dir_all(&bin).unwrap();
        for file in ["java.exe", "javaw.exe", "javac.exe"] {
            fs::write(bin.join(file), b"fixture").unwrap();
        }
        bin.join("java.exe")
    }

    #[test]
    fn scans_within_depth_and_requires_jdk_tools() {
        let root = fixture_root();
        let expected = create_jdk(&root.join("depth-1").join("depth-2"));
        let too_deep = create_jdk(&root.join("a/b/c/d/e/f/g"));
        let jre_bin = root.join("jre/bin");
        fs::create_dir_all(&jre_bin).unwrap();
        fs::write(jre_bin.join("java.exe"), b"fixture").unwrap();
        fs::write(jre_bin.join("javaw.exe"), b"fixture").unwrap();
        let jre_java = fs::canonicalize(jre_bin.join("java.exe")).unwrap();
        let visited = &mut 0;
        let mut candidates = Vec::new();
        scan_tree(&root, MAX_DIRECTORY_DEPTH, visited, &mut candidates);
        let canonical_expected = fs::canonicalize(expected).unwrap();
        let canonical_deep = fs::canonicalize(too_deep).unwrap();
        assert!(
            candidates
                .iter()
                .any(|path| fs::canonicalize(path).ok().as_ref() == Some(&canonical_expected))
        );
        assert!(
            !candidates
                .iter()
                .any(|path| fs::canonicalize(path).ok().as_ref() == Some(&jre_java))
        );
        assert!(
            !candidates
                .iter()
                .any(|path| fs::canonicalize(path).ok().as_ref() == Some(&canonical_deep))
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn deduplicates_paths_case_insensitively() {
        let root = fixture_root();
        let java = create_jdk(&root);
        let canonical = fs::canonicalize(&java).unwrap();
        assert_eq!(deduplicate(vec![java, canonical.clone()]), vec![canonical]);
        fs::remove_dir_all(root).unwrap();
    }
}
