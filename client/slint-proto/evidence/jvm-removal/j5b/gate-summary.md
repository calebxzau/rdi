# GJ5b1: room games start from Rust

Date: 2026-11-23. Plan: `J5B-PLAN.md` (approved 2026-11-23, B1-B6 as recommended; the owner
confirmed every production node runs the Rust `proxy-rs`). Steps 1-7 done; stopped at GJ5b1.
**Accepted by the owner on 2026-11-23 and committed.**

## What changed

A left click on a Forge 1.20.1 or NeoForge 1.21.1 room tile, and 继续启动 in the duplicate dialog,
now start the game in Rust. GTNH and Fabric rooms still go to the service's `host.start` (J5c).

| Part | Rust | Kotlin source |
|---|---|---|
| Start flow | `src/play/room.rs` | `startPlay`, `McPlayController.startSession` |
| Base/extra mod sync, removed-mod cleanup | `src/play/mods_sync.rs` (+ `pack_install::mod_download_requests`) | `HostBaseModSync.kt`, `HostExtraModSync.kt`, `RemovedModCleanupService.kt` |
| RDI core | `src/play/core.rs` | `McCoreUpdater`, `UpdateService.prepareMcCore` |
| Runtime jars, media natives | `src/play/runtime.rs` | `GameKotlinRuntime`, `RdiZstdRuntime`, `MediaProcGameClasspath`, `MediaProcNativeRuntime` |
| Kotlin-mod thinning | `src/play/thin.rs` | `GameKotlinRuntime.kt` |
| Window title | `src/play/window.rs` | `gameWindowTitle` |
| Launch command | `src/mc_launch` (`Request.rdi`, `neoforge_logging_jvm_args`) | `MinecraftLauncher.launchInternal` (`rdiRuntime`) |
| Node entry, server address switch | `src/route/mod.rs`, `ServerApi::switch_base`/`at` | `refreshNodeSettings`, `RServer.routeState`/`now` |
| Room connection | `src/route/link.rs` (crate `rdi-local-proxy`, linked in) | `LocalMcProxyService`, `GameProxyController` |
| UI wiring | `app.rs` `start_room`, `play_ui.rs` `room_deps`; node refresh at sign-in | `host.start` handler |

Sessions (`src/play/mod.rs`) gained a room flag: the room connection stops when the last room game
ends, as `stopLocalMcProxy` does.

Kotlin side (`client/ui`, `client/mclaunch`): four fixture tests and the `game-runtime` export.

| File | Purpose |
|---|---|
| `mclaunch/.../J5bLaunchCommandFixtureTest.kt` | `j5b-launch.json`: full room commands, 1.20.1 Forge (Forgeguard) and 1.21.1 NeoForge (player's own logging argument) |
| `mclaunch/.../J5bKotlinThinFixtureTest.kt` | `j5b-thin/` inputs + `j5b-thin.json`: thinning result per jar, second pass unchanged, two KFF refused |
| `client-core/.../J5bRoomModSyncFixtureTest.kt` | `j5b-sync.json`: extra prefix, removed-name matches, base/extra delete and download plans |
| `ui-logic/.../J5bWindowTitleFixtureTest.kt` | `j5b-window.json`: nine title cases incl. surrogate pairs and long names |
| `ui-service/.../GameRuntimeExport.kt`, `GameRuntimeExportTest.kt`, `build.gradle.kts` task `:ui-service:gameRuntime` | `build/game-runtime/` with `manifest.json` (5 Kotlin jars, zstd-jni, 4 mediaproc jars, `forgeguard.jar`) |

## Validation

| Check | Result |
|---|---|
| `cargo clippy --workspace --all-targets` | clean |
| `cargo test --workspace` | 187 + 62 + 11 pass, 1 GPU test ignored as before (21 new tests) |
| `cargo-zigbuild check --release` aarch64-apple-darwin / x86_64-unknown-linux-gnu | pass / pass, only the 2 existing `java_discovery.rs` warnings (`logs/zig-*.log`) |
| Kotlin fixture tests (write, then check mode with `--rerun`) | 4/4 pass |
| `GameRuntimeExportTest`, `:ui-service:gameRuntime` | pass; configuration cache clean |
| Room command equals Kotlin's (`j5b-launch.json`) | both cases, every argument |
| Thinning equals Kotlin's (`j5b-thin.json`) | all five jars; a thin jar is never rewritten |

Gradle ran through Windows `pwsh.exe` and `client/ui/gradlew.bat`: the IntelliJ MCP failed to connect
in this session.

### Live, real server, service disabled

`live/preview.ps1` reuses the J5a data folder (room pack of 28257的1.21 already installed, NeoForge
1.21.1) and breaks the service command on purpose.

- Sign-in: node entry refreshed (node 广西电信, no backup address).
- Room tile 28257的1.21 (NeoForge 1.21.1, Kotlin for Forge + Sodium): node refresh, detail, status,
  version, `host/<id>/start`, play count, room connection on `127.0.0.1:55667`, game started; no
  service process.
- In game: RDI read the room arguments (已登录RDI用户：28257, title button 进入地图 · 28257的1.21);
  Kotlin for Forge loaded on the runtime class path (it had been thinned by Kotlin earlier, and
  Rust left it alone); FFmpeg decoder active. Joined through RMCP: connected to 127.0.0.1:55667,
  453 chunks received, launcher logged the node attach.
- Clicking the room while it runs: 重复启动 dialog.
- 终止游戏: process ended, room connection stopped (`房间连接已停止`, port closed).
- RDI core changed on disk: replaced with the server's file on the next start (SHA-1 back to
  `f652c199…`), no staging file left. The test appended bytes to the jar, which is a hard link of
  the cache entry, so the cache was damaged too; the store noticed the SHA-1 mismatch and
  downloaded it again.
- Not by design: I closed the launcher with `Stop-Process` while a game ran, which left that game
  running without the launcher (GJ5b2 recovery case). I ended that test game myself.

## Differences from Kotlin (deliberate)

- Forgeguard: Kotlin adds the agent argument in literal quotes, which Java's `ProcessBuilder` strips
  on Windows; Rust quotes arguments itself, so it passes `-javaagent:<path>` without them.
- Console progress lines of the mod and core downloads are simpler (one line per 8MiB and the end)
  than Kotlin's task progress text; the step lines (`[RDI] 检查房间基础Mod...` and so on) are the same.
- The window icon is not set yet (step 8, GJ5b2); the game shows its default icon, as Kotlin does
  when preparing the icon fails.
- With `RDI_PROTO_SERVER` (local master) Rust behaves like Kotlin's debug build: node lookup on that
  server, no address switch, room nodes from `RDI_LOCAL_PROXY_NODES` or `<host>:65230`.

## Not verified

- A Forge 1.20.1 room live (no 1.20.1 room pack in the test data; the command is checked by fixture).
- Disabled base mods and extra mods live (writes to the room: local master; covered by fixture).
- Failover to the next node, node switch while in game, the backup server address.
- The Compose launcher starting the same room after Rust (and the reverse) on one data folder.
- A GTO pack with Forgeguard; macOS and Linux real starts.

## Decisions for the owner

None new. GJ5b2 (window icon, recovery, removing the room calls to the service) waits for your
review of this gate.
