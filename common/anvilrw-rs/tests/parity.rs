//! Compares this crate with the Kotlin `anvilrw` module on the reference data that
//! `RustParityFixtureTest` wrote to `tests/fixtures/kotlin`.

mod common;

use std::collections::HashMap;
use std::fs;

use common::*;
use rdi_anvilrw::region::{self, RewriteOptions};
use rdi_anvilrw::*;
use serde_json::{Value, json};

fn str_or_null(value: &Value) -> Option<&str> {
    value.as_str()
}

fn check_error<T: std::fmt::Debug>(
    kotlin: &Value,
    result: &rdi_anvilrw::Result<T>,
    context: &str,
) -> bool {
    match (kotlin.as_str(), result) {
        (Some(name), Err(error)) => {
            assert!(
                same_error(name, error),
                "{context}: Kotlin failed with {name}, Rust with {} ({error})",
                error.kind_name()
            );
            true
        }
        (Some(name), Ok(value)) => {
            panic!("{context}: Kotlin failed with {name}, Rust succeeded with {value:?}")
        }
        (None, Err(error)) => panic!("{context}: Kotlin succeeded, Rust failed: {error}"),
        (None, Ok(_)) => false,
    }
}

#[test]
fn nbt_patcher_matches_kotlin() {
    let dir = fixtures().join("nbt");
    let mappings = mappings(&fixtures().join("mappings.json"));
    let cases = read_json(&dir.join("cases.json"));
    let cases = cases.as_array().unwrap();
    assert!(cases.len() > 300);
    for case in cases {
        let name = case["name"].as_str().unwrap();
        let input = fs::read(dir.join(format!("{name}.in"))).unwrap();
        let patcher = NbtBytePatcher::new(&mappings[case["mapping"].as_str().unwrap()]).unwrap();
        let result = patcher.patch(&input);
        if check_error(&case["error"], &result, name) {
            assert!(
                NbtBytePatcher::validate(&input).is_err()
                    || case["error"] == "NbtKeyConflictException",
                "{name}"
            );
            continue;
        }
        let result = result.unwrap();
        let expected = fs::read(dir.join(format!("{name}.out"))).unwrap();
        assert_eq!(
            result.bytes(&input),
            expected.as_slice(),
            "{name}: patched bytes"
        );
        assert_eq!(json!(result.changed()), case["changed"], "{name}: changed");
        assert_eq!(
            counts_json(&result.replacements),
            case["replacements"],
            "{name}: replacements"
        );
    }
}

#[test]
fn text_patcher_matches_kotlin() {
    let dir = fixtures().join("text");
    let mappings = mappings(&fixtures().join("mappings.json"));
    for case in read_json(&dir.join("cases.json")).as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let input = fs::read(dir.join(format!("{name}.in"))).unwrap();
        assert_eq!(
            json!(is_strict_utf8(&input)),
            case["strictUtf8"],
            "{name}: strict UTF-8"
        );
        let result = TextUuidPatcher::new(&mappings[case["mapping"].as_str().unwrap()])
            .unwrap()
            .patch(&input);
        if check_error(&case["error"], &result, name) {
            continue;
        }
        let result = result.unwrap();
        let expected = fs::read(dir.join(format!("{name}.out"))).unwrap();
        assert_eq!(
            String::from_utf8_lossy(result.bytes(&input)),
            String::from_utf8_lossy(&expected),
            "{name}: patched text"
        );
        assert_eq!(json!(result.changed()), case["changed"], "{name}: changed");
        assert_eq!(
            counts_json(&result.replacements),
            case["replacements"],
            "{name}: replacements"
        );
        assert_eq!(
            json!(result.most_least_hits),
            case["mostLeastHits"],
            "{name}: Most/Least hits"
        );
    }
}

#[test]
fn path_renamer_matches_kotlin() {
    let mappings = mappings(&fixtures().join("mappings.json"));
    for case in read_json(&fixtures().join("paths.json"))
        .as_array()
        .unwrap()
    {
        let renamer = SavePathRenamer::new(&mappings[case["mapping"].as_str().unwrap()]).unwrap();
        let input = case["input"].as_str().unwrap();
        assert_eq!(
            renamer.rename(input),
            case["output"].as_str().unwrap(),
            "{input} with {}",
            case["mapping"]
        );
    }
}

#[test]
fn dimension_folders_match_kotlin() {
    let root = std::path::absolute("fixture-root").unwrap();
    for case in read_json(&fixtures().join("dimensions.json"))
        .as_array()
        .unwrap()
    {
        let id = case["id"].as_str().unwrap();
        let result = dimension_storage_folder(&root, id);
        if check_error(&case["error"], &result, id) {
            continue;
        }
        let relative: Vec<String> = result
            .unwrap()
            .strip_prefix(&root)
            .unwrap()
            .components()
            .map(|c| c.as_os_str().to_string_lossy().into_owned())
            .collect();
        assert_eq!(
            relative.join("/"),
            case["relative"].as_str().unwrap(),
            "{id}"
        );
    }
}

fn level_json(metadata: &LevelMetadata) -> Value {
    json!({
        "versionName": metadata.version_name,
        "dataVersion": metadata.data_version,
        "serverBrands": metadata.server_brands,
        "hardcore": metadata.hardcore,
        "hardcoreByteOffset": metadata.hardcore_byte_offset,
        "singleplayerUuid": metadata.singleplayer_uuid.map(|u| u.to_string()),
        "fmlModIds": metadata.fml_mod_ids,
        "loaderDetection": metadata.loader_detection().name(),
    })
}

#[test]
fn level_metadata_matches_kotlin() {
    let dir = fixtures().join("level");
    for case in read_json(&dir.join("cases.json")).as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let document =
            NbtDocument::open(fs::read(dir.join(format!("{name}.nbt"))).unwrap()).unwrap();
        let result = document.level_metadata();
        if check_error(&case["error"], &result, name) {
            continue;
        }
        assert_eq!(level_json(&result.unwrap()), case["metadata"], "{name}");
    }
}

#[test]
fn compound_replacement_matches_kotlin() {
    let dir = fixtures().join("replace");
    for case in read_json(&dir.join("cases.json")).as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let input = fs::read(dir.join(format!("{name}.in"))).unwrap();
        let payload = fs::read(dir.join(format!("{name}.payload"))).unwrap();
        let parent: Vec<&str> = case["parent"]
            .as_array()
            .unwrap()
            .iter()
            .map(|p| p.as_str().unwrap())
            .collect();
        let result = NbtDocument::open(input)
            .and_then(|d| d.replace_compound(&parent, case["entry"].as_str().unwrap(), &payload));
        if check_error(&case["error"], &result, name) {
            continue;
        }
        assert_eq!(
            result.unwrap(),
            fs::read(dir.join(format!("{name}.out"))).unwrap(),
            "{name}"
        );
    }
}

fn sync_json(chunk: &SaveSyncChunk) -> Value {
    json!({"dimensionId": chunk.dimension_id, "chunkX": chunk.chunk_x, "chunkZ": chunk.chunk_z, "owner": chunk.owner.to_string()})
}

#[test]
fn sync_chunk_lists_match_kotlin() {
    let dir = fixtures().join("sync");
    for case in read_json(&dir.join("cases.json")).as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let result = parse_sync_chunks(&fs::read(dir.join(format!("{name}.dat"))).unwrap());
        if check_error(&case["error"], &result, name) {
            continue;
        }
        let chunks: Vec<Value> = result.unwrap().iter().map(sync_json).collect();
        assert_eq!(Value::Array(chunks), case["chunks"], "{name}");
    }
}

#[test]
fn region_rewrites_match_kotlin() {
    let dir = fixtures().join("regions");
    let mappings = mappings(&fixtures().join("mappings.json"));
    for case in read_json(&dir.join("cases.json")).as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let source = dir
            .join(name)
            .join("in")
            .join(case["file"].as_str().unwrap());
        let out = tempfile::tempdir().unwrap();
        let target = out.path().join(case["file"].as_str().unwrap());
        let patcher = NbtBytePatcher::new(&mappings[case["mapping"].as_str().unwrap()]).unwrap();
        let transform = |_: i32, _: i32, nbt: &[u8]| Ok(patcher.patch(nbt)?.patched);
        let keep: Option<Vec<(i32, i32)>> = case["keep"].as_array().map(|list| {
            list.iter()
                .map(|p| (p[0].as_i64().unwrap() as i32, p[1].as_i64().unwrap() as i32))
                .collect()
        });
        let filter = keep
            .as_ref()
            .map(|keep| move |x: i32, z: i32| keep.contains(&(x, z)));
        let options = RewriteOptions {
            chunk_limit: case["chunkLimit"].as_u64().unwrap() as usize,
            external_threshold_sectors: case["threshold"].as_u64().unwrap() as usize,
            keep: filter.as_ref().map(|f| f as &region::ChunkFilter<'_>),
            ..RewriteOptions::default()
        };
        let result = region::rewrite(&source, &target, &transform, &options);
        if check_error(&case["error"], &result, name) {
            if let Some(cause) = case["errorCause"].as_str() {
                let error = result.unwrap_err();
                let rust_cause = error
                    .cause()
                    .unwrap_or_else(|| panic!("{name}: Kotlin had cause {cause}, Rust none"));
                assert!(
                    same_error(cause, rust_cause),
                    "{name}: cause {cause} vs {}",
                    rust_cause.kind_name()
                );
            }
            assert!(
                tree(out.path()).is_empty(),
                "{name}: no output or temporary files after a failure"
            );
        } else {
            let result = result.unwrap();
            let got = json!({
                "chunkCount": result.chunk_count,
                "changedChunkCount": result.changed_chunk_count,
                "copiedUnchanged": result.copied_unchanged,
                "externalChunkFiles": result.external_chunk_files,
                "droppedChunkCount": result.dropped_chunk_count,
                "written": result.written,
            });
            assert_eq!(got, case["result"], "{name}: result");
            assert_same_tree(
                &dir.join(name).join("out"),
                out.path(),
                Some(&dir.join(name).join("in")),
                name,
            );
            if result.copied_unchanged {
                for file in tree(out.path()) {
                    assert_eq!(
                        fs::read(out.path().join(&file)).unwrap(),
                        fs::read(dir.join(name).join("in").join(&file)).unwrap(),
                        "{name}: {file} copied"
                    );
                }
            }
        }
        let types = region::compression_types(&source).ok().map(|types| {
            Value::Object(
                types
                    .into_iter()
                    .map(|(k, v)| (k.to_string(), json!(v)))
                    .collect(),
            )
        });
        assert_eq!(
            types.unwrap_or(Value::Null),
            case["compressionTypes"],
            "{name}: compression types"
        );
        let present = region::present_chunks(&source)
            .ok()
            .map(|chunks| json!(chunks.iter().map(|(x, z)| [x, z]).collect::<Vec<_>>()));
        assert_eq!(
            present.unwrap_or(Value::Null),
            case["presentChunks"],
            "{name}: present chunks"
        );
    }
}

fn report_json(report: &RemapReport) -> Value {
    json!({
        "filesScanned": report.files_scanned,
        "filesRewritten": report.files_rewritten,
        "pathsRenamed": report.paths_renamed,
        "replacementsBySource": counts_json(&report.replacements_by_source),
        "possiblyUnmigrated": report.possibly_unmigrated.iter().map(|f| json!({"relativePath": f.relative_path, "reason": f.reason})).collect::<Vec<_>>(),
        "hardcoreSwitchedOff": report.hardcore_switched_off,
        "hostDataFromLevelDat": report.host_data_from_level_dat,
        "syncFilter": report.sync_filter.map(|f| json!({
            "markedChunkCount": f.marked_chunk_count, "presentChunkCount": f.present_chunk_count, "droppedFileCount": f.dropped_file_count,
        })),
        "bytesBefore": report.bytes_before,
    })
}

fn kind_name(kind: SavePlayerKind) -> &'static str {
    match kind {
        SavePlayerKind::Online => "Online",
        SavePlayerKind::Offline => "Offline",
        SavePlayerKind::Rdi => "Rdi",
        SavePlayerKind::Unknown => "Unknown",
    }
}

fn source_name(source: NameHintSource) -> &'static str {
    match source {
        NameHintSource::Mojang => "Mojang",
        NameHintSource::UserCache => "UserCache",
        NameHintSource::FtbTeams => "FtbTeams",
        NameHintSource::FtbQuests => "FtbQuests",
    }
}

fn scan_json(scan: &SaveScanResult) -> Value {
    json!({
        "mcVersionName": scan.mc_version_name,
        "dataVersion": scan.data_version,
        "serverBrands": scan.server_brands,
        "fmlModIds": scan.fml_mod_ids,
        "loaderDetection": scan.loader_detection.name(),
        "isHardcore": scan.is_hardcore,
        "singleplayerHostUuid": scan.singleplayer_host_uuid.map(|u| u.to_string()),
        "players": scan.players.iter().map(|p| json!({
            "uuid": p.uuid.to_string(),
            "kind": kind_name(p.kind),
            "nameHints": p.name_hints.iter().map(|h| json!({"name": h.name, "source": source_name(h.source)})).collect::<Vec<_>>(),
            "verifiedName": p.verified_name,
            "isSingleplayerHost": p.is_singleplayer_host,
            "hasLastPlayed": p.last_played.is_some(),
            "playTimeTicks": p.play_time_ticks,
        })).collect::<Vec<_>>(),
        "syncChunks": scan.sync_chunks.as_ref().map(|chunks| chunks.iter().map(sync_json).collect::<Vec<_>>()),
        "totalBytes": scan.total_bytes,
    })
}

fn marking_json(role: &SaveMarkingPlayer) -> Value {
    json!({
        "uuid": role.player.uuid.to_string(),
        "displayName": role.display_name(),
        "position": role.position.as_ref().map(|p| json!({"dimension": p.dimension, "x": p.x, "y": p.y, "z": p.z})),
    })
}

#[test]
fn save_remaps_scans_and_roles_match_kotlin() {
    let dir = fixtures().join("saves");
    let mappings = mappings(&dir.join("mappings.json"));
    for case in read_json(&dir.join("cases.json")).as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        // Git keeps no empty folders and no modification times, so work on a fresh copy.
        let game = tempfile::tempdir().unwrap();
        copy_tree(&dir.join(name).join("in"), game.path());
        let source = game.path().join("saves").join("world");
        let out = tempfile::tempdir().unwrap();
        let target = out.path().join("out");
        let sync: Option<Vec<SaveSyncChunk>> = case["syncChunks"].as_array().map(|list| {
            list.iter()
                .map(|c| SaveSyncChunk {
                    dimension_id: c["dimensionId"].as_str().unwrap().into(),
                    chunk_x: c["chunkX"].as_i64().unwrap() as i32,
                    chunk_z: c["chunkZ"].as_i64().unwrap() as i32,
                    owner: uuid(c["owner"].as_str().unwrap()),
                })
                .collect()
        });
        let remapper = SaveUuidRemapper::with_limits(
            mappings[case["mapping"].as_str().unwrap()].clone(),
            sync,
            limits::CHUNK_DECOMPRESSED_BYTES,
            2,
        )
        .unwrap();
        let snapshot = SaveSnapshot::take(&source).unwrap();
        let report = remapper.remap_tree(&source, &target, &snapshot, &|_| {}, &never_cancelled);
        if check_error(&case["error"], &report, name) {
            assert!(
                tree(&target).is_empty(),
                "{name}: nothing is left behind after a failure"
            );
        } else {
            let report = report.unwrap();
            assert_eq!(report_json(&report), case["report"], "{name}: report");
            assert_same_tree(&dir.join(name).join("out"), &target, Some(&source), name);
        }
        let scan = scan_save(&source);
        if !check_error(&case["scanError"], &scan, &format!("{name} scan")) {
            assert_eq!(scan_json(&scan.unwrap()), case["scan"], "{name}: scan");
        }
        let roles = marking_players(&source);
        if !check_error(&case["markingError"], &roles, &format!("{name} roles")) {
            let roles: Vec<Value> = roles.unwrap().iter().map(marking_json).collect();
            // Kotlin sorts roles by modification time, which git does not keep: compare as sets.
            let mut got: Vec<String> = roles.iter().map(Value::to_string).collect();
            let mut want: Vec<String> = case["marking"]
                .as_array()
                .unwrap()
                .iter()
                .map(Value::to_string)
                .collect();
            got.sort();
            want.sort();
            assert_eq!(got, want, "{name}: marking roles");
        }
    }
}

#[test]
fn marking_identity_preparation_matches_kotlin() {
    let dir = fixtures().join("saves").join("prepare");
    for case in read_json(&dir.join("cases.json")).as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let work = tempfile::tempdir().unwrap();
        let world = work.path().join("world");
        copy_tree(&dir.join(name).join("in"), &world);
        let selected = str_or_null(&case["selected"]).map(uuid);
        let result = prepare_marking_identity(&world, selected, &work.path().join("backup"));
        if check_error(&case["error"], &result, name) {
            assert_eq!(
                result.unwrap_err().to_string(),
                case["errorMessage"].as_str().unwrap(),
                "{name}: message"
            );
        } else {
            assert_eq!(
                result.unwrap().map(|u| u.to_string()),
                str_or_null(&case["owner"]).map(String::from),
                "{name}: owner"
            );
        }
        assert_same_tree(
            &dir.join(name).join("out"),
            &world,
            Some(&dir.join(name).join("in")),
            &format!("{name} world"),
        );
        let kotlin_backup = dir.join(name).join("backup");
        assert_eq!(
            tree(&kotlin_backup),
            tree(&work.path().join("backup")),
            "{name}: backup files"
        );
        for file in tree(&kotlin_backup) {
            assert_eq!(
                fs::read(kotlin_backup.join(&file)).unwrap(),
                fs::read(work.path().join("backup").join(&file)).unwrap(),
                "{name}: backup {file}"
            );
        }
    }
}

#[test]
fn identity_mapping_examples() {
    // SaveIdentityMappingTest, with players from a scan result.
    let m = uuid("1512ef02-2b79-4494-a4e9-e35cd8e03923");
    let r = uuid("00112233-4455-6677-8899-aabb00000000");
    let steve = offline_uuid("Steve");
    let steve_rdi = uuid("66778899-aabb-ccdd-eeff-001100000000");
    let player = |uuid, ticks| SavePlayer {
        uuid,
        kind: classify(uuid),
        name_hints: Vec::new(),
        verified_name: None,
        is_singleplayer_host: false,
        last_played: None,
        play_time_ticks: ticks,
    };
    let scan = |players: Vec<SavePlayer>| SaveScanResult {
        mc_version_name: Some("1.20.1".into()),
        data_version: Some(3465),
        server_brands: Vec::new(),
        fml_mod_ids: None,
        loader_detection: SaveLoaderDetection::Unknown,
        is_hardcore: false,
        singleplayer_host_uuid: None,
        players,
        sync_chunks: None,
        total_bytes: 0,
    };
    let none = HashMap::new();
    let assign = |pairs: &[(uuid::Uuid, uuid::Uuid)]| {
        pairs
            .iter()
            .copied()
            .collect::<std::collections::BTreeMap<_, _>>()
    };

    let plain = build_identity_mapping(
        &scan(vec![player(m, Some(10)), player(steve, None)]),
        &assign(&[(m, r), (steve, steve_rdi)]),
        &none,
    )
    .unwrap();
    assert_eq!(plain, HashMap::from([(m, r), (steve, steve_rdi)]));

    let longer_other = scan(vec![player(m, Some(500)), player(r, Some(100))]);
    let longer_rdi = scan(vec![player(m, Some(100)), player(r, Some(500))]);
    let unknown = scan(vec![player(m, None), player(r, None)]);
    assert_eq!(
        find_dual_identities(&longer_other, &assign(&[(m, r)]))[0].default_keep,
        DualIdentityKeep::Other
    );
    assert_eq!(
        find_dual_identities(&longer_rdi, &assign(&[(m, r)]))[0].default_keep,
        DualIdentityKeep::Rdi
    );
    assert_eq!(
        find_dual_identities(&unknown, &assign(&[(m, r)]))[0].default_keep,
        DualIdentityKeep::Rdi
    );
    assert_eq!(
        build_identity_mapping(&longer_other, &assign(&[(m, r)]), &none).unwrap(),
        HashMap::from([(m, r), (r, m)])
    );
    assert!(
        build_identity_mapping(&longer_rdi, &assign(&[(m, r)]), &none)
            .unwrap()
            .is_empty()
    );

    let save = scan(vec![
        player(m, Some(500)),
        player(r, Some(100)),
        player(steve, Some(1)),
    ]);
    assert_eq!(
        build_identity_mapping(
            &save,
            &assign(&[(m, r), (steve, steve_rdi)]),
            &HashMap::from([(m, DualIdentityKeep::Rdi)])
        )
        .unwrap(),
        HashMap::from([(steve, steve_rdi)])
    );
    assert_eq!(
        build_identity_mapping(
            &save,
            &assign(&[(m, r)]),
            &HashMap::from([(m, DualIdentityKeep::Other)])
        )
        .unwrap(),
        HashMap::from([(m, r), (r, m)])
    );

    let save = scan(vec![
        player(m, Some(1)),
        player(r, Some(1)),
        player(steve, Some(1)),
    ]);
    for assignments in [
        assign(&[(uuid("12345678-1234-4234-9234-123456789abc"), steve_rdi)]),
        assign(&[(r, steve_rdi)]),
        assign(&[(m, steve)]),
        assign(&[(m, steve_rdi), (steve, steve_rdi)]),
    ] {
        assert!(
            matches!(
                build_identity_mapping(&save, &assignments, &none),
                Err(RemapError::Invalid(_))
            ),
            "{assignments:?}"
        );
    }
    assert!(matches!(
        build_identity_mapping(
            &save,
            &assign(&[(m, r)]),
            &HashMap::from([(steve, DualIdentityKeep::Other)])
        ),
        Err(RemapError::Invalid(_))
    ));
}
