# GJ5c1: GTNH, Fabric and RDI packs played alone start from Rust

Date: 2026-11-24. Plan: `J5C-PLAN.md` (approved 2026-11-24, C1-C4 as recommended). Steps 1-4 done;
GTNH and Fabric rooms and the Compose cross-check passed live on 2026-11-24. **At the gate.**

## What changed

| Part | Rust | Kotlin source |
|---|---|---|
| GTNH launch | `src/mc_launch/gtnh.rs`, `Request.gtnh_root` | `GtnhLaunchSupport.kt`, GTNH branches of `MinecraftLauncher` |
| Fabric install | `src/mc_install/fabric.rs` | `ClientLoaderRuntime.kt`, `FabricClientProfile.kt`, Fabric part of `McInstall.kt` |
| Runtime per version | `mc_install::meta::runtime`, `prepare_for_launch(Target) -> Launch`, `play::runtime::Selection` | `RdiRuntimeSelection`, `needsForgeRuntime`, `resolveMediaRuntime` |
| Rooms | `play::room::supported` covers all four; `RoomOutcome::Service` and the `host.start` fallback removed | `startPlay` |
| RDI pack played alone (scope addition) | `play::room::start_solo`, `play_ui.rs` | `rdiPackPlayArgs` + `startSession` |

Kotlin side: `mclaunch/.../J5cLaunchCommandFixtureTest.kt` writes `protocol/fixtures/j5c-launch.json`.

## Validation

| Check | Result |
|---|---|
| `cargo clippy --all-targets` | clean |
| `cargo test --workspace` | 62 + 11 + 281 pass, 3 ignored as before |
| `cargo-zigbuild check --release` macOS arm64 / Linux x64 | pass / pass, only the 2 existing `java_discovery.rs` warnings (`logs/`) |
| Kotlin J5b + J5c fixture tests, check mode | pass |
| GTNH and Fabric commands, room and local (`j5c-launch.json`) | all 4 cases, every argument |
| Fabric profile rules | 17 cases equal Kotlin's |

### Live, real server, service disabled (`live/preview.ps1`)

Data folder `j5a/live/run` with copies of `69eaf0a3…_1.0.0` (GTNH) and `6b0127b5…_3.5.2` (Fabric)
from `rdi5ship`; the owner's folders were not touched.

- **Fabric, RDI pack alone** (乌托邦探险之旅): Fabric profile fetched and written, 8 libraries
  verified, RDI core `1.20.1-fabric` synced, game reached the title screen in 91 s with
  `rdi 5-mc-client-1.20.1-fabric` loaded; 终止游戏 ended it in about 4 s.
- **Fabric, local pack offline** (hard-linked copy without the RDI core): `Steve`/`legacy`, no
  runtime jars and no `-Drdi.play`; title screen in 77 s; 终止游戏 works.
- **GTNH, RDI pack alone**: 1.7.10 client, Forge universal and the Scala/LWJGL3 libraries downloaded,
  lwjgl3ify linked into `mods`, RFB, coremods and mixins loaded; then `glfwGetVideoMode` NPE because
  the Windows session was disconnected (`quser`: `Disc`), the known no-monitor failure. Not a code
  failure, but GTNH in game is unverified.

### Rooms, real server, 2026-11-24 (test account, rooms made by the owner)

Same data folder and broken service command; log `live/preview.log` (run 3).

- **GTNH room** (RDI公6, 格雷新境界GTNH 1.0.0): second start downloaded nothing (only the
  `/update/mc/1.7.10-forge/hash` check), game process up within 1 s of the click. The first retry
  crashed again at `glfwGetVideoMode`: GLFW saw no monitor yet over RDP; a GLFW probe then saw one
  (`Generic Non-PnP Monitor 600x1252`) and the next start loaded 320 mods with RDI 1.7.10. The owner
  joined by hand: room connection attached to `rdi.calebxzhou.cn:65233`, FML handshake and id
  remapping done. The 1.7.10 RDI mod reads only the player identity from `rdi.play` and does not join
  by itself; the command equals Kotlin's, so Compose behaves the same.
- **Fabric room** (davickk的乌托邦探, 乌托邦探险之旅 3.5.2, room was stopped): `/host/.../start` 200,
  no downloads, KnotClient with `rdi-5-mc-client-1.20.1-fabric.jar`, `-Drdi.play` and
  `-Drdi.host.id`; title screen 74 s after start (596 mods, `RDI Fabric client initialized for
  127.0.0.1:55667`); the owner joined by hand and reached the overworld; RDI packet cache offered
  kept join packets.

### Compose cross-check, 2026-11-24

The real Compose launcher (`client/ui :hotRun`) on the same data folder through a Gradle init script
kept outside the repo: working dir = the test folder, test account via `rdi.account`,
`rdi.server.url` = production (needed because hotRun runs with `rdi.debug=true`). Installed files
compared by path, size and mtime before and after each start (Fabric files also by sha1).

| Direction | Result |
|---|---|
| Compose starts GTNH room installed by Rust | Starts, 320 mods loaded. Compose adds its own Forge 1.7.10 version (`1.7.10-Forge10.13.4.1614-1.7.10.json`, forge jar, launchwrapper, asm-all, commons-lang3; about 6 s, once) because Rust skips the Forge installer as settled in the plan; pack, `gtnh/` patches, 1.7.10 client and LWJGL untouched. |
| Compose starts Fabric room installed by Rust | No downloads, no installed file changed; title screen, RDI core loaded. |
| Rust starts Fabric after Compose installed the loader | Rust's Fabric profile and `net/fabricmc` libraries archived to `DEL/20261124-j5c-reverse-fabric/`; Compose fetched the profile and 3 libraries; Rust then started with no download, sha1 and mtime of all 4 files unchanged, title screen in 72 s. |
| Rust starts GTNH after Compose | No downloads, 320 mods loaded; no installed file changed. |

Only game-written files changed between runs (JNA temp DLLs in `versions/1.20.1/natives`, OpenSecurity
sounds, Carpenter's Blocks cache). Compose's room connection could not attach in this setup: in debug
mode it uses `DEBUG_HOST` with the debug node port instead of the production node; unrelated to J5c.

## Open

None for GJ5c1. Not covered: macOS/Linux real starts (zigbuild checks only); 终止游戏 during a
GTNH/Fabric download.
