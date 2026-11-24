//! Behaviour that the Kotlin reference data cannot capture: file system state, locks, links,
//! cancellation and parallelism.

mod common;

use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, SystemTime};

use common::*;
use rdi_anvilrw::*;

fn sample_save() -> tempfile::TempDir {
    let save = tempfile::tempdir().unwrap();
    let root = save.path();
    fs::write(root.join("level.dat"), [1, 2, 3]).unwrap();
    fs::create_dir_all(root.join("region")).unwrap();
    fs::write(
        root.join("region/r.0.0.mca"),
        (0..10_000).map(|i| i as u8).collect::<Vec<_>>(),
    )
    .unwrap();
    fs::create_dir_all(root.join("data/empty")).unwrap();
    fs::write(root.join("中文.txt"), "玩家").unwrap();
    fs::write(root.join("session.lock"), []).unwrap();
    save
}

#[test]
fn snapshot_lists_files_and_directories_but_not_the_lock() {
    let save = sample_save();
    let snapshot = SaveSnapshot::take(save.path()).unwrap();
    assert_eq!(
        snapshot.files().keys().cloned().collect::<Vec<_>>(),
        ["level.dat", "region/r.0.0.mca", "中文.txt"]
    );
    assert_eq!(
        snapshot.directories().iter().cloned().collect::<Vec<_>>(),
        ["data", "data/empty", "region"]
    );
    assert_eq!(snapshot.total_bytes(), 10_009);
    snapshot.verify().unwrap();
}

#[test]
fn snapshot_copy_reproduces_the_save() {
    let save = sample_save();
    let target = tempfile::tempdir().unwrap();
    let copy = target.path().join("copy");
    let snapshot = SaveSnapshot::take(save.path()).unwrap();
    let mut last = 0;
    snapshot
        .copy_to(&copy, &never_cancelled, &mut |done| last = done)
        .unwrap();
    assert_eq!(last, 10_009);
    assert!(copy.join("data/empty").is_dir());
    assert!(!copy.join("session.lock").exists());
    for (path, state) in snapshot.files() {
        assert_eq!(
            fs::read(save.path().join(path)).unwrap(),
            fs::read(copy.join(path)).unwrap(),
            "{path}"
        );
        assert_eq!(
            fs::metadata(copy.join(path)).unwrap().modified().unwrap(),
            state.modified,
            "{path}"
        );
    }
    let copied = SaveSnapshot::take(&copy).unwrap();
    assert_eq!(snapshot.files(), copied.files());
    assert_eq!(snapshot.directories(), copied.directories());
}

#[test]
fn changes_after_the_snapshot_are_detected() {
    type Change = Box<dyn Fn(&Path)>;
    let changes: Vec<(&str, Change)> = vec![
        (
            "modified",
            Box::new(|p| fs::write(p.join("level.dat"), [9, 9, 9, 9]).unwrap()),
        ),
        (
            "same size, new mtime",
            Box::new(|p| {
                let file = fs::OpenOptions::new()
                    .write(true)
                    .open(p.join("level.dat"))
                    .unwrap();
                file.set_modified(SystemTime::UNIX_EPOCH + Duration::from_secs(1))
                    .unwrap();
            }),
        ),
        (
            "added",
            Box::new(|p| fs::write(p.join("new.dat"), [1]).unwrap()),
        ),
        (
            "removed",
            Box::new(|p| fs::remove_file(p.join("中文.txt")).unwrap()),
        ),
        (
            "directory added",
            Box::new(|p| fs::create_dir_all(p.join("data/other")).unwrap()),
        ),
    ];
    for (name, change) in changes {
        let save = sample_save();
        let snapshot = SaveSnapshot::take(save.path()).unwrap();
        change(save.path());
        assert!(
            matches!(snapshot.verify(), Err(RemapError::SaveChanged(_))),
            "{name}"
        );
        let target = tempfile::tempdir().unwrap();
        assert!(
            matches!(
                snapshot.copy_to(&target.path().join("copy"), &never_cancelled, &mut |_| {}),
                Err(RemapError::SaveChanged(_))
            ),
            "{name}"
        );
    }
}

#[test]
fn read_file_checks_the_snapshot() {
    let save = sample_save();
    let snapshot = SaveSnapshot::take(save.path()).unwrap();
    assert_eq!(snapshot.read_file("level.dat").unwrap(), [1, 2, 3]);
    fs::write(save.path().join("level.dat"), [4, 5, 6, 7]).unwrap();
    assert!(matches!(
        snapshot.read_file("level.dat"),
        Err(RemapError::SaveChanged(_))
    ));
    assert!(matches!(
        snapshot.read_file("missing.dat"),
        Err(RemapError::SaveChanged(_))
    ));
}

#[test]
fn links_and_junctions_are_rejected() {
    let save = sample_save();
    let outside = tempfile::tempdir().unwrap();
    fs::write(outside.path().join("x"), [1]).unwrap();
    let mut checked = 0;
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(outside.path().join("x"), save.path().join("link.dat")).unwrap();
        assert!(
            matches!(
                SaveSnapshot::take(save.path()),
                Err(RemapError::SaveLink(_))
            ),
            "symbolic link"
        );
        fs::remove_file(save.path().join("link.dat")).unwrap();
        std::os::unix::fs::symlink(outside.path(), save.path().join("data/linked-dir")).unwrap();
        assert!(
            matches!(
                SaveSnapshot::take(save.path()),
                Err(RemapError::SaveLink(_))
            ),
            "directory link"
        );
        fs::remove_file(save.path().join("data/linked-dir")).unwrap();
        checked += 2;
    }
    #[cfg(windows)]
    {
        if std::os::windows::fs::symlink_file(
            outside.path().join("x"),
            save.path().join("link.dat"),
        )
        .is_ok()
        {
            assert!(
                matches!(
                    SaveSnapshot::take(save.path()),
                    Err(RemapError::SaveLink(_))
                ),
                "symbolic link"
            );
            fs::remove_file(save.path().join("link.dat")).unwrap();
            checked += 1;
        }
        let junction = save.path().join("data").join("junction");
        let status = std::process::Command::new("cmd")
            .args(["/c", "mklink", "/J"])
            .arg(&junction)
            .arg(outside.path())
            .output()
            .unwrap()
            .status;
        assert!(status.success(), "mklink /J");
        assert!(
            matches!(
                SaveSnapshot::take(save.path()),
                Err(RemapError::SaveLink(_))
            ),
            "junction"
        );
        fs::remove_dir(&junction).unwrap();
        checked += 1;
    }
    SaveSnapshot::take(save.path()).unwrap();
    assert!(checked > 0);
}

#[test]
fn lock_is_exclusive_and_released() {
    let save = tempfile::tempdir().unwrap();
    let lock = SaveSourceLock::acquire(save.path()).unwrap();
    assert!(save.path().join("session.lock").is_file());
    assert!(matches!(
        SaveSourceLock::acquire(save.path()),
        Err(RemapError::SaveInUse { .. })
    ));
    drop(lock);
    drop(SaveSourceLock::acquire(save.path()).unwrap());
    assert_eq!(
        fs::metadata(save.path().join("session.lock"))
            .unwrap()
            .len(),
        0
    );
}

#[test]
fn lock_refuses_a_linked_lock_file() {
    #[cfg(unix)]
    {
        let save = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        fs::write(outside.path().join("target"), []).unwrap();
        std::os::unix::fs::symlink(
            outside.path().join("target"),
            save.path().join("session.lock"),
        )
        .unwrap();
        assert!(matches!(
            SaveSourceLock::acquire(save.path()),
            Err(RemapError::SaveLink(_))
        ));
    }
}

fn pcl() -> uuid::Uuid {
    uuid("00000000-0000-300c-9be5-0017dec2993d")
}

fn pcl_target() -> uuid::Uuid {
    uuid("00112233-4455-6677-8899-aabb00000000")
}

fn chunk_nbt(x: i32, z: i32, owner: Option<uuid::Uuid>) -> Vec<u8> {
    let mut entries = vec![
        ("xPos", Tag::Int(x)),
        ("zPos", Tag::Int(z)),
        ("sign", Tag::String("木牌😀".into())),
    ];
    if let Some(owner) = owner {
        entries.push(("Owner", uuid_ints(owner)));
    }
    document(&compound(entries))
}

fn save_with_regions(regions: i32) -> SyntheticSave {
    let save = SyntheticSave::new().level_dat(Some(player(pcl(), vec![])), false);
    for rx in 0..regions {
        let mut region = TestRegion::new(&save.root.join("region"), rx, 0);
        for i in 0..8usize {
            let x = rx * 32 + i as i32;
            region = region.chunk(i, ZLIB, &chunk_nbt(x, 0, (i % 2 == 0).then(pcl)), false);
        }
        region.write();
    }
    save
}

#[test]
fn changed_source_fails_and_leaves_nothing() {
    let save = SyntheticSave::new()
        .level_dat(None, false)
        .text("config/a.txt", "a");
    let snapshot = SaveSnapshot::take(&save.root).unwrap();
    fs::write(save.root.join("config/a.txt"), "changed").unwrap();
    let out = tempfile::tempdir().unwrap();
    let remapper = SaveUuidRemapper::new(HashMap::from([(pcl(), pcl_target())]), None).unwrap();
    let result = remapper.remap_tree(&save.root, out.path(), &snapshot, &|_| {}, &never_cancelled);
    assert!(
        matches!(result, Err(RemapError::SaveChanged(_))),
        "{result:?}"
    );
    assert!(tree(out.path()).is_empty());
    assert_eq!(fs::read_dir(out.path()).unwrap().count(), 0);
}

#[test]
fn cancellation_stops_and_cleans_the_target() {
    let save = save_with_regions(6);
    let snapshot = SaveSnapshot::take(&save.root).unwrap();
    let out = tempfile::tempdir().unwrap();
    let calls = AtomicUsize::new(0);
    let cancel_after_some = || {
        if calls.fetch_add(1, Ordering::SeqCst) >= 10 {
            Err(RemapError::Cancelled)
        } else {
            Ok(())
        }
    };
    let remapper = SaveUuidRemapper::with_limits(
        HashMap::from([(pcl(), pcl_target())]),
        None,
        limits::CHUNK_DECOMPRESSED_BYTES,
        3,
    )
    .unwrap();
    let result = remapper.remap_tree(
        &save.root,
        out.path(),
        &snapshot,
        &|_| {},
        &cancel_after_some,
    );
    assert!(matches!(result, Err(RemapError::Cancelled)), "{result:?}");
    assert_eq!(
        fs::read_dir(out.path()).unwrap().count(),
        0,
        "the target is emptied"
    );
}

#[test]
fn parallel_and_serial_remaps_agree() {
    let save = save_with_regions(9);
    fs::create_dir_all(save.root.join("empty/dir")).unwrap();
    let mapping = HashMap::from([(pcl(), pcl_target())]);
    let snapshot = SaveSnapshot::take(&save.root).unwrap();
    let serial = tempfile::tempdir().unwrap();
    let parallel = tempfile::tempdir().unwrap();
    let progress_calls = AtomicUsize::new(0);
    let one =
        SaveUuidRemapper::with_limits(mapping.clone(), None, limits::CHUNK_DECOMPRESSED_BYTES, 1)
            .unwrap();
    let many =
        SaveUuidRemapper::with_limits(mapping, None, limits::CHUNK_DECOMPRESSED_BYTES, 4).unwrap();
    let a = one
        .remap_tree(
            &save.root,
            serial.path(),
            &snapshot,
            &|_| {},
            &never_cancelled,
        )
        .unwrap();
    let b = many
        .remap_tree(
            &save.root,
            parallel.path(),
            &snapshot,
            &|p| {
                assert!(p.bytes_done <= p.bytes_total);
                progress_calls.fetch_add(1, Ordering::Relaxed);
            },
            &never_cancelled,
        )
        .unwrap();
    assert_eq!(a, b);
    assert!(progress_calls.load(Ordering::Relaxed) >= snapshot.files().len());
    assert_eq!(a.files_rewritten, 9 + 1, "nine regions and level.dat");
    assert_eq!(a.replacements_by_source[&pcl()], 9 * 4 + 1);
    assert!(
        serial.path().join("empty/dir").is_dir(),
        "empty directories are kept"
    );
    assert_eq!(tree(serial.path()), tree(parallel.path()));
    for file in tree(serial.path()) {
        assert_eq!(
            fs::read(serial.path().join(&file)).unwrap(),
            fs::read(parallel.path().join(&file)).unwrap(),
            "{file}"
        );
    }
    assert!(
        serial
            .path()
            .join(format!("playerdata/{}.dat", pcl_target()))
            .is_file(),
        "the owner's data comes from level.dat"
    );
    let after = SaveSnapshot::take(&save.root).unwrap();
    assert_eq!(
        snapshot.files(),
        after.files(),
        "the source is never modified"
    );
}

#[test]
fn target_must_be_empty() {
    let save = SyntheticSave::new().level_dat(None, false);
    let snapshot = SaveSnapshot::take(&save.root).unwrap();
    let out = tempfile::tempdir().unwrap();
    fs::write(out.path().join("keep.txt"), "mine").unwrap();
    let remapper = SaveUuidRemapper::new(HashMap::new(), None).unwrap();
    let result = remapper.remap_tree(&save.root, out.path(), &snapshot, &|_| {}, &never_cancelled);
    assert!(matches!(result, Err(RemapError::Invalid(_))));
    // Kotlin empties the target on this failure too; Rust only removes what it wrote itself.
    assert_eq!(
        fs::read_to_string(out.path().join("keep.txt")).unwrap(),
        "mine"
    );
}

#[test]
fn missing_level_dat_fails_the_scan() {
    let save = SyntheticSave::new();
    match scan_save(&save.root) {
        Err(RemapError::Io { source, .. }) => {
            assert_eq!(source.kind(), std::io::ErrorKind::NotFound)
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn mappings_must_be_unambiguous() {
    let a = pcl();
    let b = uuid("1512ef02-2b79-4494-a4e9-e35cd8e03923");
    let c = pcl_target();
    for mapping in [
        HashMap::from([(a, c), (b, c)]),
        HashMap::from([(a, b), (b, c)]),
        HashMap::from([(a, a)]),
    ] {
        assert!(
            matches!(NbtBytePatcher::new(&mapping), Err(RemapError::Invalid(_))),
            "{mapping:?}"
        );
    }
    NbtBytePatcher::new(&HashMap::from([(a, b), (b, a)])).unwrap();
}

#[test]
fn parallelism_is_bounded_by_memory_and_cpus() {
    let limit = limits::CHUNK_DECOMPRESSED_BYTES as u64;
    let chunk = limits::CHUNK_DECOMPRESSED_BYTES;
    assert_eq!(limits::region_parallelism(0, 8, chunk), 1);
    assert_eq!(limits::region_parallelism(limit, 8, chunk), 1);
    assert_eq!(limits::region_parallelism(8 * limit, 8, chunk), 4);
    assert_eq!(limits::region_parallelism(8 * limit, 2, chunk), 2);
    assert_eq!(limits::region_parallelism(8 * limit, 0, chunk), 1);
}

#[test]
fn offline_uuids_match_java() {
    // UUID.nameUUIDFromBytes("OfflinePlayer:Steve".toByteArray()) in Java.
    assert_eq!(
        offline_uuid("Steve").to_string(),
        "5627dd98-e6be-3c21-b8a8-e92344183641"
    );
    assert_eq!(classify(offline_uuid("Steve")), SavePlayerKind::Offline);
    assert_eq!(classify(pcl_target()), SavePlayerKind::Rdi);
    assert_eq!(
        classify(uuid("1512ef02-2b79-4494-a4e9-e35cd8e03923")),
        SavePlayerKind::Online
    );
}
