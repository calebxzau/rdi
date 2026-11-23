# J5b plan: start room games from Rust

Drafted 2026-11-23. Status: **approved by the owner on 2026-11-23; B1-B6 as recommended.**
Owner confirmed for B2 that every production node runs the Rust `proxy-rs`, so the Netty
fallback is not needed. Implementation stops at GJ5b1 (after step 7).

**2026-11-23: steps 1-7 implemented, stopped at GJ5b1**, uncommitted. Results and what was not
verified: `evidence/jvm-removal/j5b/gate-summary.md`, owner checklist beside it. Section 8 items
settled: `local-proxy-rs` shares the launcher's tokio runtime; `neoForgeLoggingJvmArgs` and the
Forgeguard path are ported (the agent comes from the `game-runtime` folder, built from
`:forgeguard`); `EXTRA_MOD_PREFIX` and the removed slugs are in `j5b-sync.json`;
`state_in` and `isVersionReadyToLaunch` both only check that `mods` is a folder.

**GJ5b1 accepted by the owner on 2026-11-23** and committed.

**Owner direction, 2026-11-23 (not started; the next conversation researches the concrete plan):**
drop the Kotlin runtime on the game's class path and the Kotlin-mod thinning. The RDI core mod
keeps Kotlin as a compile-time dependency only and does not declare Kotlin for Forge in
`mods.toml`; every room must have Kotlin for Forge, which the server already enforces
(`HostControlService`, 请先为此房间安装kotlinforforge模组才能启动), and KFF supplies the Kotlin
runtime (KFF 5.12.0 ships stdlib/reflect 2.4.0, coroutines 1.11.0, serialization). Decisions:
no minimum KFF version for now; keep a small step that removes mods' own zstd-jni. Open for that
plan: mediaproc is Kotlin and cannot stay on the class path once Kotlin leaves it, so it moves into
the core mod; javacpp/ffmpeg may stay on the class path.

Read first: the root `AGENTS.md`, `J5-PLAN.md` (sections 3 and 5 still apply),
`JVM-REMOVAL-PLAN.md` section 1, `evidence/jvm-removal/j5a/gate-summary.md`.

Written for an agent with no prior context. User-facing text says 房间 and 节点,
never proxy, container or service.

## 1. Goal

A left click on a room tile starts the game without the `ui-service` JVM. Today
`src/app.rs` (`play_host`, and the duplicate-start path beside it) calls the service
method `host.start`; everything else on that path is already Rust (room pack check and
install from J4c, Minecraft and loader install and the process from J5a).

After this gate the service is started only for GTNH, Fabric and Microsoft online play
(J5c).

In scope, for Forge 1.20.1 and NeoForge 1.21.1 (J5 D2):

| Part | Kotlin source (the specification) | Lines |
|---|---|---|
| Start flow | `McPlayController.startSession` with `withRdiCore = true`; `Host.DetailVo.startPlay` (`ModpackService.kt:416`) | ~280 |
| Base and extra mod sync | `HostBaseModSync.kt`, `HostExtraModSync.kt` | 216 |
| Removed-mod cleanup | `RemovedModCleanupService.kt` | 60 |
| RDI core mod | `McCoreUpdater.kt`, `UpdateService.prepareMcCore` | ~200 |
| Runtime jars on the class path | `GameKotlinRuntime.resolveRuntimeClasspath`, `RdiZstdRuntime`, `MediaProcGameClasspath`, `MediaProcNativeRuntime` | ~150 |
| Kotlin-mod thinning | `GameKotlinRuntime.kt` (the rest) | ~300 |
| Extra JVM arguments | `MinecraftLauncher.kt:197-375` (`rdiRuntime` branches), `neoForgeLoggingJvmArgs`, `dmGameJvmArgs`, Forgeguard agent | ~80 |
| Window title and icon | `GameWindowService.kt` | 181 |
| Room connection | `LocalMcProxyService.kt`, `GameProxyController.kt`, `NativeLocalProxy.kt`; the crate `client/local-proxy-rs` | ~430 |
| Node routing | `CarrierDetectBridge.kt`, `NodeRefreshCoordinator.kt`, `ServerRouteState` in `RServer.kt` | ~150 |
| Game recovery | `ui-service/.../GameRecovery.kt` | 105 |

Out of scope:

- GTNH, Fabric, Microsoft online play: J5c.
- Save import marking mode (`startPlay(startHost = false)`, the `account` override,
  `extraJvmArgs`, `extraGameArgs`, `cleanup`): it belongs to 导入存档 in UI batch c2.
- A manual node switch. Compose has it on the settings page
  (`SettingsViewModel.kt:122`), which Slint does not have yet (UI batch b). See B3.
- Packaging the `game-runtime` folder: J6.
- Any server or `common` change. If one seems necessary, stop and ask.

## 2. Code facts

Checked on 2026-11-23 at commit `3f0999a1`.

- **Order of steps** in `startSession`: load launch options; base mod sync; extra mod
  sync; removed-mod cleanup; RDI core; `prepareForLaunch`; early-display cleanup; window
  title and icon; build JVM arguments (custom arguments, JDWP, Forgeguard, start the room
  connection, `-Drdi.play`, `-Drdi.host.id`, DM); build the command; start the process.
  `stopRequested` is checked between every two steps.
- **`startPlay`** before that: refresh the node entry (a failure is only logged);
  `GET host/<id>/status`; `GET modpack/<id>/version/<ver>`; install check;
  `POST host/<id>/start`; build `playArg`; `POST modpack/<id>/play` (failure ignored).
- **`playArg`** is six lines: server URL, game address, room name, room port, player
  UUID, player name. Line 2 is replaced with the address the local connection listens on,
  then the whole text goes to the game as `-Drdi.play=<base64>`.
- **Base mods**: active = version mods minus the room's disabled mods, client side only
  (`SERVER` and `UNKNOWN` excluded), minus the loader's removed slugs. Disabled,
  server-only and unknown-side files are deleted from `mods`. A mod counts as present if
  any of its `fileNames` exists as a regular file (no hash check).
- **Extra mods** are stored with the prefix `EXTRA_MOD_PREFIX`; files with that prefix
  that are no longer expected are deleted.
- **RDI core**: `GET update/mc/<mcVer>-<loaderSlug>/hash` gives a SHA-1; the file
  `mods/rdi-5-mc-client-<slug>.jar` is replaced only if its SHA-1 differs; the download is
  `update/mc/<slug>` with the bearer token, through the content store. Two starts of the
  same slug share one check.
- **Runtime jars**: five Kotlin jars, `zstd-jni`, the mediaproc jars. All of them are
  found today through the service JVM's own class path. The mediaproc native jar is
  unpacked to `<tools>/mediaproc-natives` and four `-Dorg.bytedeco.javacpp.*` arguments
  are added.
- **Thinning** rewrites Kotlin mods in `mods` (nested jars up to depth 4, cache schema
  `v3` under `<tools>/kotlin-runtime`) so they use the class-path runtime. More than one
  Kotlin for Forge file is an error.
- **Room connection**: `local-proxy-rs` exposes `LocalProxy::start(config, sink)`,
  `address`, `set_nodes`, `switch_to`, `stop`. It listens on `127.0.0.1:55667`, or any
  free port when that one is taken. Kotlin gives it the node list
  `[selected] + candidates + default node`, without duplicates.
- **Kotlin uses this crate only when the server's `sessionResume` flag is true.**
  Otherwise it uses the Netty proxy `client/local-mc-proxy`, which J5 D3 drops. See B2.
- **Node entry**: public IPv4 from `http://ip.3322.net/`, then `GET server-entry?myIp=…`
  on the primary address. The reply (`ServerEntry`) holds the node name, game address,
  candidate nodes, `sessionResume`, and `useBackupNode` + `api`. With `useBackupNode`
  Kotlin sends all later requests to `https://<api>:443`. Rust always uses the address
  from the configuration (J1 known gap).
- **Recovery** in Kotlin: process id, start time and the image name (`java`, `java.exe`,
  `javaw.exe`) must match. A recovered game has no console output and no exit code; stop
  still works. The room connection is started again afterwards.
- **Rust today**: `src/play` holds sessions, console lines, stop, the duplicate check and
  `start_local`; `src/mc_launch::command` builds the command and is checked against
  Kotlin by `protocol/fixtures/j5a.json`; `src/pack_install/mods.rs` has the `Mod` model,
  side filter and download sources; `src/content_store.rs` has `materialize` and `fetch`;
  `src/avif.rs` decodes AVIF; `room_pack` in `src/app.rs` does the install check.

## 3. Decisions for the owner

| # | Question | Recommendation |
|---|---|---|
| B1 | One gate or two? | **Two stops.** GJ5b1: a room game starts and connects from Rust (steps 1-7 in section 5). GJ5b2: window icon, recovery, removing the room calls to the service (steps 8-10). The first stop is the risky part and is worth testing on its own in a real room. |
| B2 | What if the server says `sessionResume = false`? | **Always use `local-proxy-rs`, as D3 says, and log the flag.** Memory of the 2026-11-03 rollout says the flag is on by default and the anchor `proxy-rs` runs on the nodes. Not verified: whether every production node accepts this connection type. If any node does not, a player routed there cannot connect from Slint. Please confirm that all nodes run the new `proxy-rs`. |
| B3 | How much node routing? | **Automatic only.** Refresh the node entry at sign-in and before each room start; follow `useBackupNode` for server requests (closes the J1 gap); give the candidates to the room connection so it fails over by itself. The manual switch waits for the Slint settings page. |
| B4 | Where does the `game-runtime` folder live during development? | **`client/ui/ui-service/build/game-runtime`**, written by a new Gradle task `:ui-service:gameRuntime` (D4), with `RDI_GAME_RUNTIME_DIR` to override. A missing folder gives a clear error naming the task. The Forgeguard agent is copied into it from where Compose reads it today (`<launcher>/lib/`). |
| B5 | Where are running games recorded for recovery? | **`<data>/running_games.json`**, written when a process starts and when it ends: session id, title, version id, process id, start time, room flag. No tokens or launch arguments. Read once at start-up. Compose does not read it. |
| B6 | Exit while a room game runs | **Unchanged from today**: the exit dialog warns and ends the games (D10). Recovery then only matters after a launcher crash; the game has lost its room connection by then, and recovery lets the player stop it or reconnect once the connection is started again on the same port. |

## 4. Design

Play code takes no Slint types (J5 section 5), so the planned launcher MCP can call it.

```
src/play/room.rs        start flow for a room: the Rust form of startPlay + startSession
src/play/mods_sync.rs   base mods, extra mods, removed-mod cleanup
src/play/core.rs        RDI core check and replace, shared per slug
src/play/runtime.rs     game-runtime manifest, class path, media natives
src/play/thin.rs        Kotlin-mod thinning
src/play/window.rs      window title, icon.png
src/play/recovery.rs    running_games.json, process identity
src/route/mod.rs        node entry, route state, server address switch
src/route/link.rs       owns the LocalProxy; start, stop, events as console lines
```

- `mc_launch::Request` gains `rdi_runtime` with the extra class path and JVM arguments;
  `command` stays the single place that orders arguments.
- `play::Sessions` gains a room flag per session. The room connection stops when the
  last room session ends (`markSessionExited`, `stopSession` in Kotlin).
- `local-proxy-rs` becomes a path dependency (`rlib`). Its events are turned into the
  same player-facing lines as `describeLocalProxyEvent`, which name nodes and never
  addresses.
- `api::ServerApi` gets a switchable base address behind a lock; `route` sets it.
- `app.rs` `play_host` and the duplicate path call `play::room::start` instead of
  `host.start`. The window size arguments keep their meaning.
- The session's console lines keep the Kotlin wording (`[RDI] 检查房间基础Mod...` and so
  on), so the console page needs no change.

## 5. Steps

Each step ends with its tests green before the next one starts.

1. **Fixtures first (D6).** Extend the Kotlin fixture test to write `protocol/fixtures/j5b.json`:
   full room launch commands (tokens masked) for a Forge 1.20.1 and a NeoForge 1.21.1
   pack, each with and without a Kotlin mod and with Forgeguard; `playArg` cases;
   base/extra mod sync plans for a set of `mods` folders; window titles; removed-slug
   matches. A second fixture holds thinning inputs and the expected output jars' entry
   lists and hashes.
2. **`game-runtime` folder (D4, B4).** Gradle task, manifest, the Kotlin test that
   compares the manifest with what the three Kotlin resolvers return today. Rust reader
   and the media native unpack.
3. **Mod sync, cleanup, RDI core** against the fixtures and a mock server.
4. **Thinning** against its fixture. Compose must accept a pack Rust thinned and the
   reverse (same cache schema `v3`).
5. **Launch command** with `rdi_runtime` against `j5b.json`.
6. **Node entry and room connection** (B2, B3): route state, address switch, the linked
   proxy, event lines.
7. **Start flow** (`play::room::start`), wired into `play_host`. **Stop at GJ5b1.**
8. **Window title and icon**, AVIF through `src/avif.rs`.
9. **Recovery** (B5).
10. Remove `host.start` and the room parts of `game.*` from the Slint side; the service
    code itself stays for Compose and for J5c. **Stop at GJ5b2.**

## 6. Validation

- `cargo clippy`, `cargo test --workspace`, release build, `cargo-zigbuild check` for
  macOS arm64 and Linux x64, the Kotlin fixture tests.
- Live on Windows with the service disabled (`evidence/jvm-removal/j5a/live/preview.ps1`
  breaks it on purpose), test account, isolated data folder:
  - a 1.20.1 Forge room and a 1.21.1 NeoForge room reach the world and stay connected;
  - a room with disabled base mods and with extra mods; changing them and starting again;
  - a room whose pack has a Kotlin mod;
  - an outdated RDI core file is replaced, a current one is left alone;
  - the first node unreachable: the game connects through the next one;
  - 终止游戏 during each preparation step leaves no half-written file;
  - a second start of the same room: the duplicate dialog;
  - a room and a local pack together: one status line, both stop;
  - launcher killed while a room game runs, then started again: the game is listed and
    can be stopped;
  - the Compose launcher starts the same room afterwards without reinstalling or
    re-thinning, and the reverse.
- Local master for anything that writes besides `host/<id>/start` and the play count.
- Not possible for the agent: node switching inside a running game on real nodes, macOS
  and Linux real starts. These go on the owner checklist.

## 7. Risks

- **Room connection without the Netty fallback** (B2). A node that only speaks the old
  way cannot be reached.
- **Thinning** is the most intricate port: a wrong jar breaks the game only for packs
  with Kotlin mods, and both launchers share the result.
- **Server address switch** touches every request the launcher makes. A wrong switch
  breaks sign-in, not just play. It gets its own tests and can be put behind the
  existing configuration override.
- **Runtime folder drift**: the manifest test fails when the Kotlin side changes a
  dependency, which is the intent.
- **Token handling**: `-Drdi.play` carries no token, the RDI core download does. Nothing
  with a token is logged (`mask_secrets`).

## 8. Not yet checked

- The event types `local-proxy-rs` reports and whether it needs its own tokio runtime or
  can share the launcher's.
- What `neoForgeLoggingJvmArgs` adds, and whether `mcpPort` in `McPlayArgs` is set for
  rooms.
- How the service's `host.start` handler uses the window size and reports
  `NeedInstall` / `Installing` back.
- Where the Forgeguard agent comes from in a development checkout.
- `EXTRA_MOD_PREFIX`, the removed-slug lists and `Modpack.supportForgeGuard`: values to
  copy, with a fixture so they cannot drift.
- Whether Rust's `pack_install::state_in` gives the same answer as
  `ModpackService.isVersionReadyToLaunch(version, activeBaseMods)` for a room with
  disabled mods.
