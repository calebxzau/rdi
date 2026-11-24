//! Remaps a real save and compares the result with Kotlin's remapped copy of the same save.
//!
//! Runs only when these are set, so it never depends on local files in CI:
//! - `RDI_SAMPLE_SAVE`: the save folder (only read).
//! - `RDI_SAMPLE_KOTLIN_OUT`: the copy written by Kotlin's
//!   `SampleSaveVerificationTest.writesARemappedCopy` with the same mapping.
//! - `RDI_SAMPLE_SAVE_MAPPING` (optional): `source=target,source=target`, as in Kotlin.
//!
//! Run in release mode: `cargo test --release --test sample_save -- --nocapture`.

mod common;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

use common::*;
use rdi_anvilrw::*;

#[test]
fn sample_save_matches_kotlin() {
    let (Some(save), Some(kotlin)) = (std::env::var_os("RDI_SAMPLE_SAVE"), std::env::var_os("RDI_SAMPLE_KOTLIN_OUT")) else {
        eprintln!("RDI_SAMPLE_SAVE or RDI_SAMPLE_KOTLIN_OUT is not set; nothing to compare");
        return;
    };
    let save = PathBuf::from(save);
    let kotlin = PathBuf::from(kotlin);
    let mapping: HashMap<_, _> = match std::env::var("RDI_SAMPLE_SAVE_MAPPING") {
        Ok(text) => text
            .split(',')
            .map(|pair| {
                let (source, target) = pair.split_once('=').expect("source=target");
                (uuid(source.trim()), uuid(target.trim()))
            })
            .collect(),
        Err(_) => HashMap::from([
            (uuid("00000000-0000-300c-9be5-0017dec2993d"), uuid("00112233-4455-6677-8899-aabb00000000")),
            (uuid("1512ef02-2b79-4494-a4e9-e35cd8e03923"), uuid("66778899-aabb-ccdd-eeff-001100000000")),
        ]),
    };

    let scan = scan_save(&save).unwrap();
    println!(
        "scan: version={:?} loader={} hardcore={} host={:?} players={} sync={:?}",
        scan.mc_version_name,
        scan.loader_detection.name(),
        scan.is_hardcore,
        scan.singleplayer_host_uuid,
        scan.players.len(),
        scan.sync_chunks.as_ref().map(Vec::len)
    );

    let out = tempfile::tempdir().unwrap();
    let target = out.path().join("out");
    let snapshot = SaveSnapshot::take(&save).unwrap();
    let progress_calls = AtomicU64::new(0);
    let started = Instant::now();
    let report = SaveUuidRemapper::new(mapping, None)
        .unwrap()
        .remap_tree(&save, &target, &snapshot, &|_| { progress_calls.fetch_add(1, Ordering::Relaxed); }, &never_cancelled)
        .unwrap();
    let seconds = started.elapsed().as_secs_f64();
    println!(
        "rust: files={} rewritten={} renamed={} replacements={:?} hardcoreOff={} hostData={} bytes {}->{} seconds={seconds:.1} parallelism={}",
        report.files_scanned,
        report.files_rewritten,
        report.paths_renamed,
        report.replacements_by_source,
        report.hardcore_switched_off,
        report.host_data_from_level_dat,
        report.bytes_before,
        report.bytes_after,
        limits::default_region_parallelism(),
    );
    for file in &report.possibly_unmigrated {
        println!("possibly unmigrated: {} ({})", file.relative_path, file.reason);
    }
    assert!(report.replacements_by_source.values().sum::<usize>() > 0);

    let started = Instant::now();
    assert_same_tree(&kotlin, &target, Some(&save), "sample");
    println!("compared with Kotlin in {:.1}s: same files, chunks and contents", started.elapsed().as_secs_f64());
}
