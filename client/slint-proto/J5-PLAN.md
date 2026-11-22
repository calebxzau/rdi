# J5 plan: start the game from Rust

Drafted 2026-11-22. Status: **all decisions in section 3 settled by the owner on
2026-11-22. Start with the AVIF spike (section 4), then J5a. Each gate stops for the
owner's review.**

Read first: `JVM-REMOVAL-PLAN.md` (direction and phases), `J4-PLAN.md` section 7
(ground rules), `J4C-PLAN.md` (the install this builds on), the root `AGENTS.md`.

## 1. Goal

After J4c the Slint launcher starts its JVM service (`client/ui/ui-service`) for one
reason only: playing. J5 moves that to Rust. When J5 is done nothing in the Slint
launcher calls the service, and J6 can delete it.

Minecraft itself still runs on Java. The package has no bundled Java today: the updater
(`client/updater/src/java_discovery.rs`, `main.rs`) searches the computer for a full
64-bit JDK 25 and offers to download one. Only the launcher's own JVM process goes away.

Service methods that J5 replaces (`ui-service/.../Main.kt`, `PackService.kt`):
`session.set`, `host.start`, `packPlay.open|start|cancel`, `game.subscribe|stop|stopAll|restore`,
`console.subscribe|clear`, `shutdown`.

## 2. What has to be ported

Line counts are the Kotlin main sources today, as a size guide.

| Part | Kotlin source | Lines | Notes |
|---|---|---|---|
| Install Minecraft, loader, libraries, assets | `client/mc-install` | 3199 | About half is server, test-server and local-discovery code that the play flow does not reach. Forge and NeoForge installers run as a Java child process (`McInstallerBootstrapper.kt`), which stays. |
| Build the launch command, start the process | `client/mclaunch` | 2159 | Includes GTNH support (337) and the Kotlin-mod thinning in `GameKotlinRuntime.kt` (369). |
| Play flow | `ui-logic/.../McPlayController.kt`, `McPlayStore.kt`, `LocalPackPlay.kt`, `client-core/.../ModpackService.kt:416` (`startPlay`) | ~650 | The order of steps in `McPlayController.startSession` is the specification. |
| Room mods and RDI core | `HostBaseModSync.kt`, `HostExtraModSync.kt`, `McCoreUpdater.kt`, `UpdateService.kt`, `RemovedModCleanupService.kt` | ~500 | Downloads go through the J4b content store. |
| Game window title and icon | `ui-logic/.../GameWindowService.kt` | 181 | AVIF icons are decoded with FFmpeg today. |
| Local game proxy | `client-core/.../proxy/*`, `LocalMcProxyService.kt` | ~430 | The proxy itself is already Rust: `client/local-proxy-rs` builds as `cdylib` and `rlib`. Kotlin only drives it through JNA and keeps a Netty fallback (`client/local-mc-proxy`, 567 lines). |
| Node routing | `CarrierDetectBridge.kt`, `NodeRefreshCoordinator.kt`, route state in `RServer.kt` | ~250 | Closes the J1 gap "Rust stays on the primary address". |
| Microsoft login for local packs | `client-core/.../account/*`, `PlayerService.microsoftLogin` | ~250 | Kotlin uses the MinecraftAuth library and stores that library's JSON in `msa_sessions.json`. |
| Game recovery after a launcher restart | `ui-service/.../GameRecovery.kt` | 105 | Process id plus start time. |

Three things come from the service JVM's own class path today and need a new source
once that JVM is gone:

- Kotlin runtime jars (five), `zstd-jni`, the mediaproc jars and its native jar
  (`GameKotlinRuntime.resolveRuntimeClasspath`, `RdiZstdRuntime`, `MediaProcGameClasspath`).
- The default Java for the game: `javaExePath` is the service's own `java.exe`.
- Resources read from the jar: `mcmeta/<version>.json`, the installer bootstrapper jar.

## 3. Owner decisions (settled 2026-11-22)

| # | Question | Decision |
|---|---|---|
| D1 | One gate or several? | **Three gates** (section 4). The whole phase is roughly 6000 Kotlin lines, more than any earlier phase. |
| D2 | Which game versions in the first two gates? | **1.20.1 Forge and 1.21.1 NeoForge.** GTNH (1.7.10) and Fabric go to the third gate; until then they keep starting through the service. |
| D3 | Keep the Netty fallback proxy? | **No.** Link `local-proxy-rs` into the launcher as a crate. The fallback exists for the case where the native library fails to load, which cannot happen once it is compiled in. If the proxy fails to start, show the error and do not start the game. |
| D4 | Where do the game's runtime jars come from? | **A `game-runtime` folder with a manifest file**, produced by a new Gradle task from the same dependency versions the service uses. Contents: the five Kotlin jars, `zstd-jni`, the mediaproc jars (its own classes, the JavaCPP and FFmpeg Java API jars, the slim FFmpeg native jar for the platform) and the Forgeguard agent. Rust reads the manifest. A Kotlin test checks the manifest against what `GameKotlinRuntime`, `RdiZstdRuntime` and `MediaProcGameClasspath` resolve today. Packaging the folder is J6. |
| D5 | Which Java starts the game by default? | **Decided by the owner:** when the Slint launcher starts it runs a simple search by directory name, as the updater does today. If no usable Java 25 is found, it tells the player and offers to download and install one automatically. The per-pack Java setting keeps overriding the result. Reuse the updater's search rules and its download address rather than writing new ones. |
| D6 | How to prove the launch command is right? | **A shared fixture.** A Kotlin test writes the full command (tokens masked) for a set of packs; a Rust test must produce the same list. Same method as the J4c mod fixture. |
| D7 | Microsoft sessions: read the MinecraftAuth JSON that Compose saved? | **No.** Rust does the device-code login itself and saves its own record under a separate key in `msa_sessions.json`. A player who used online play in Compose logs in once more in Slint. Parsing the library's private format would break when the library changes. |
| D8 | Window icon when the pack icon is AVIF? | **Try a pure-Rust AV1 decoder first** (spike in section 4). If the spike fails, report to the owner before choosing a native decoder; until then the game's default icon is used, as Kotlin already does when preparing the icon fails. |
| D9 | Reuse files from other launchers' Minecraft folders (`LocalMcArtifactFileReuser`, records in `data.db`)? | **Not in J5.** It only saves download time. Not yet checked: how much the records are used in practice. |
| D10 | Game recovery when the launcher restarts while a game runs? | **Keep it**, in Rust. With the proxy inside the launcher process, closing the launcher ends the room connection, so the launcher must keep refusing to exit while a room game runs, as it does today. |

## 4. Gates

### Spike: AVIF in pure Rust (before J5b, may run beside J5a)

Question to answer: can a pure-Rust AV1 decoder, with no C toolchain, decode the pack
icons the server returns?

- First find out which formats the server returns for pack icons. If none are AVIF,
  record that and stop; the fallback in D8 is then enough.
- Otherwise pick a decoder crate, pin its version, and decode real pack icons. Compare
  the pixels with `FfmpegAvifDecoder` output for the same files.
- Must hold: builds for Windows, Linux x64 and macOS arm64 with the current
  `cargo-zigbuild` setup; no C compiler, NASM or CMake needed.
- Report: crate and version, binary size added, decode time for a typical icon,
  any files that fail.
- If it passes, wire it into `src/images.rs` so the launcher's own pages show AVIF
  icons too, and use it for the window icon in J5b.

### J5a: local packs, offline

Rust installs Minecraft and the loader and starts a local pack (`withRdiCore = false`)
with an offline identity. No proxy, no RDI core, no runtime jars.

- New modules: `src/mc_install/` (version metadata, libraries, natives, assets, loader
  install through the Java child process) and `src/mc_launch/` (manifests, arguments,
  class path, process, output lines).
- Sessions, console lines, stop and force stop, duplicate-version check and the
  running-game guard move to Rust (`McPlayStore`, `RunningGameGuard`).
- The console and game state screens read Rust state instead of `console.state` and
  `game.state` events.
- Rooms and RDI packs still start through the service.

Stops at gate GJ5a.

### J5b: rooms

Everything `McPlayController.startSession` does for `withRdiCore = true`:
`startPlay` (status, version, install check, `host/<id>/start`, play count), base and
extra mod sync, removed-mod cleanup, RDI core, runtime jars (D4), Kotlin-mod thinning,
window title and icon, Forgeguard agent, per-pack launch options, DM arguments,
the proxy in process (D3), node routing, game recovery (D10).

After this gate the service is used only for GTNH, Fabric and Microsoft online play.

Stops at gate GJ5b.

### J5c: the rest

GTNH launch support, Fabric profile install, Microsoft login (D7). Remove the service
calls and the lazy-service code from the Slint launcher; the Kotlin modules stay for
Compose. Stops at gate GJ5c. J6 follows.

## 5. Rules that carry over

- The Compose launcher keeps shipping. Both launchers share `versions`, `libraries`,
  `assets`, the download cache and `data.db`; on-disk layout and "is it installed"
  checks must give the same answer on both sides.
- Rust never creates or migrates `data.db` (J2 D4).
- Play functions take no Slint types, so the planned launcher MCP can call them.
- Failures are logged with their cause and shown to the player; nothing is swallowed.
- Never delete project files; archive under `DEL/`.
- Access tokens never appear in logs (`maskLaunchSecrets`).

## 6. Risks

- **Launch command drift.** One wrong argument can fail in ways that show up only on
  some packs. D6 covers the command; a real start on each supported version covers the rest.
- **Loader install.** Forge and NeoForge processors, the "ready" checks and partial
  installs left by a cancelled run. Compose must accept what Rust installed and the reverse.
- **Kotlin-mod thinning** rewrites mod jars, including nested ones. Needs its own
  fixture comparison against the Kotlin output.
- **Node switching in a running game** is hard to test automatically; the owner's
  real-room check stays necessary.
- **Size.** J5b touches the most player-visible path. Each gate must leave the
  launcher fully usable, with the service as the path for what is not ported yet.

## 7. Not yet checked

- Whether `anvilrw` or any other shared module is reached from the Slint play flow
  (it was not seen in the files read for this draft).
- Which image formats the server returns for pack icons (D8).
- macOS and Linux differences in natives and Java lookup; the first two gates are
  validated on Windows, with a cross-compile check for the other two.
