# A3 handoff: wire the Rust save import (anvilrw-rs) into the launcher

2026-11-24. Written by the session that did A0-A2. The owner asked for A3 to be done by another
session. Follow AGENTS.md: explain the plan and get the owner's approval before changing code.

## Where things stand

- `common/anvilrw-rs` (crate `rdi-anvilrw`, commit `c5ccc433`, unpushed) is the Rust port of the Kotlin
  package `calebxzau.rdi.anvilrw.remap`. Read its `README.md` first. Owner accepted gate GA on
  2026-11-24.
- Public API (all synchronous, `Result<_, RemapError>`; run it with `spawn_blocking`):
  - `SaveSourceLock::acquire(world)`: `session.lock`, released on drop.
  - `SaveSnapshot::take(root)`: `files()`, `read_file`, `copy_to(target, ensure_active, progress)`, `verify`.
  - `scan_save(world) -> SaveScanResult` (players, name hints, loader, hardcore, sync chunks).
  - `marking_players(world)`, `prepare_marking_identity(world, selected, backup)`.
  - `find_dual_identities`, `build_identity_mapping(scan, assignments, keep)`.
  - `parse_sync_chunks`, `dimension_storage_folder`, `canonical_dimension_id`.
  - `region::compression_types(path)`, `region::present_chunks(path)`, `chunk_store_dimension(rel)`.
  - `SaveUuidRemapper::new(mapping, Some(sync_chunks))?.remap_tree(source, target, &snapshot, &progress, &ensure_active)`.
  - `NbtDocument::open_gzip_file / read_level_dat`, `NbtNode::{child, unique_child, uuid, string, double, list_elements}`.
  - Errors: `RemapError::kind_name()` matches the Kotlin exception names; `RemapError::Cancelled`
    comes back when `ensure_active` returns it.
- Not ported on purpose: the client services. A3 ports them into `client/slint-proto`.

## A3 scope: port these Kotlin files to Rust (no Slint screens; screens are A4)

| Kotlin (client/ui) | What it does |
|---|---|
| `client-core/.../service/HostSaveImportMarkingService.kt` | Marking copy: records file `save-import-marking.json`, `players`, `findReusable`, `createCopy` (copy into `<pack>/saves/<name>-RDI导入[-n]` via a staging dir outside `saves/`, then `prepare_marking_identity`, atomic move), `checkIdentity`, `checkReadable` (compressions 1/2/3/8 only), `summary`, `delete`, `toPlayerError` messages |
| `client-core/.../service/HostSaveImportService.kt` | `prepare` (scan + Mojang names + `/player/msid-match` + `/player/infos`), import task: remap with the sync list into a temp dir, pack `world.tar.zst`, 2GiB limit, sha1, `precheck`, `create`, chunked part upload, `complete`, poll `status` (3s, 1h), delete the copy on Ready, cancel the server session on failure, always remove the temp dir |
| `client-core/.../service/HostSaveImportApi.kt` | HTTP: `POST /host/{id}/world-import/precheck`, `POST /host/{id}/world-import`, `GET .../{importId}`, `PUT .../{importId}/parts/{index}` (sha1 per part), `POST .../{importId}/complete`, `DELETE .../{importId}`, `GET /player/by-qq/{qq}`, `POST /player/msid-match`, `GET /player/infos?ids=` |
| `src/.../viewmodel/HostSaveImportViewModel.kt` lines ~380-430 | `createCopy` saves dir = room pack `versionDir/saves`; `markingLaunch` |
| DTOs in `common/model` `HostWorldImport.kt` | Request/response shapes for the routes above |

### Marking launch (owner decision, overrides plan v5)

Kotlin `markingLaunch` starts the room's pack **without starting the host** (`startPlay(startHost = false)`),
after `checkIdentity` and `checkReadable`, with:
- account override: name = `selectedPlayerName` if it matches `[A-Za-z0-9_]{1,16}`, else the RDI
  account name; UUID = `record.launchUuid` (`Data.Player.UUID`, else the RDI UUID); token `"0"`;
- extra JVM args `-Drdi.syncChunkMarking=true` and `-Drdi.syncChunkMarkingWorld=<copy folder name>`;
- extra game args `--quickPlaySingleplayer <copy folder name>`;
- title `标记要保留的区块 <room name>`; an exit callback.

The previous Slint session (rdi5-2b) asked that this go through `play::LocalStart` / `mc_launch::Request`
(`online: Option<Account>` exists for the account), not through a separate launch path.
**`mc_launch::Request` has no extra game args yet** (`game_args` is built in `mc_launch/mod.rs`
around line 143-160); add a field there. Errors before the room pack is installed: Kotlin says
`请先进入房间游玩一次，安装好房间整合包后再导入存档` / `房间整合包正在安装，请等待安装完成后再试`.

## Other owner decisions to keep

- Two players instead of a merge: keep R = no change; keep M = full M↔R swap. Default = longer play time.
- Hardcore is switched off on import (the remapper does it). NeoForge hosts are gated by the server.
- A copy opened normally (not in marking mode) must be recreated (`checkIdentity`).
- Copies with chunk compressions other than 1/2/3/8 are refused before launch (`checkReadable`).
- Remap and upload run as a background launcher task (Kotlin `Task2` → Slint `tasks.rs`).
- A non-gzip `playerdata/*.dat` fails the import; the owner said this stays (2026-11-24).
- The Compose launcher and Slint share the data dir: keep `save-import-marking.json` readable by both
  (same JSON field names) until Compose is retired.

## Validation

- Unit tests in slint-proto for the record file, `checkIdentity`, `checkReadable`, `summary`,
  `freeCopyFolder` naming, the upload flow against a fake API, and the marking launch arguments.
- `cd common/anvilrw-rs && cargo test` must stay green if the crate changes; regenerate the Kotlin
  reference data as its README says if Kotlin changes.
- Live test on the local master (recipe in memory `ui-port-rooms-c1`); 1.20.1 Forge only.
- Gradle: IntelliJ MCP first, `pwsh.exe` + module-local `gradlew.bat` as fallback. A bash pipe around
  `pwsh.exe ... gradlew.bat` can hang after the build (the daemon keeps the pipe): redirect with
  `< /dev/null > file`.
