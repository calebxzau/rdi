# GJ5a gate summary

Status 2026-11-22: **AVIF spike done (pure-Rust decoder wired into `images.rs`); J5a
implemented; automated checks and an isolated live run on Windows done; owner checks open.**
No commit, no push, nothing staged. Plan: `J5-PLAN.md` (section 3 decisions applied as written;
D8 crate choice changed during the spike, see below).

## AVIF spike (D8)

Details and harness: `avif-spike/results.md`.

- The server does return AVIF: 2 of 65 distinct pack icon URLs (both `i0.hdslb.com`, 8-bit,
  full range). The rest are PNG 29, JPEG 23, WebP 10, GIF 1.
- `rav1d =1.1.0` (BSD-2, assembly off) decoded them, but a fuzz run with truncated and
  bit-flipped files **aborted the process**: a `panic` inside its `extern "C"` API cannot
  unwind. A broken icon from any third-party host would close the launcher. Rejected.
- The owner said during the task that the project is GPLv3 and may use GPL libraries.
  `rav1d-safe =0.6.0` (AGPL-3.0, which GPLv3 §13 allows combining with) passed the same fuzz
  run with 0 panics in 16,985 cases and is faster (10.8 ms vs 34 ms for 672x378).
  Container: `avif-parse =2.1.0` (MPL-2.0).
- Pixels vs FFmpeg with the file's own range: max difference 2 per channel on both server
  icons. Kotlin's `FfmpegAvifDecoder` calls `sws_getContext` without colorspace details, so it
  treats these full-range files as limited range (inferred, not run): mean difference 7-9,
  max 20 against Rust. Rust is the one that matches the file's metadata.
- No C compiler, NASM or CMake step; zigbuild checks for macOS arm64 and Linux x64 pass.
  Release binary +2,991,616 bytes (about 2.85MiB).
- Wired into `src/images.rs` (`src/avif.rs`), so launcher pages show AVIF icons. The game
  window icon stays J5b. **Owner decision to confirm: accept an AGPL-3.0 dependency.**

## J5a changes

New Rust modules (no Slint types in their public functions):

| File | Content |
|---|---|
| `src/mc_launch/manifest.rs` | Mojang/loader manifest types, rules (`rulesAllow` with Java `os.arch` names), arguments, library merge with override keys, class path, version jar candidates, `normalizeLoaderManifest` |
| `src/mc_launch/mod.rs` | `launchInternal` command for packs without the RDI core, `maskLaunchSecrets`, `readJdkReleaseMajor`, launch-time library check, process start with stdout+stderr on one pipe, exit lines |
| `src/mc_install/` | `prepareForLaunch`: loader ready check (`readLoaderLaunchState`, `loaderLibrariesReady`, `loaderInstallProfileReady`), Forge/NeoForge install (installer download, libraries incl. from the installer's `maven/`, Mojmap, `com.bangbang93.ForgeInstaller` as a Java child), client jar, library repair, assets (stubs, numbered-sound links, 16 parallel repairs, failures do not block), source plans with BMCLAPI mirrors and the RDI `/game-lib/maven` last resort; bundled `mcmeta` read from `client/mclaunch` resources and installer helper files from `client/ui/assets` (one copy each) |
| `src/play/` | Sessions (`McPlayStore`/`McGameSession`): admit with duplicate check across Rust and service sessions, console lines (5000 lines, service byte bound), stop/force stop, exit watcher; `OfflineGameAccount`; `EarlyDisplayCleanup`; `LocalModpackStore.update` for the last offline name; the local start following `McPlayController.startSession` for `withRdiCore = false` |
| `src/java_runtime.rs` | D5: background search at launcher start with the updater's candidate code (`#[path]` to `client/updater/src/java_discovery.rs`, now shared with `api::Client`) and its ported validation/order rules and `available_jdks.txt` cache; Windows automatic install with the updater's MSI address |
| `src/play_ui.rs` | Wiring: Rust opens the play dialog for local Forge 1.20.1/NeoForge 1.21.1 packs; offline start in Rust; online, RDI packs, Fabric/GTNH still via the service; merged console; Java install task |
| `src/avif.rs` | AVIF decode (spike) |

Changed: `app.rs` (game bar, running flag and install guard from Rust and service sessions;
stop-all stops both and only calls the service when it has games; service idle check ignores
Rust games; launcher exit force-stops Rust games), `packs.rs` (routing), `pack_options.rs`
(`loadSnapshot`), `api/mod.rs` (account name, bound-Microsoft flag), `ui/app.slint` and
`shell.rs` (缺少Java25 dialog: 自动下载安装), `Cargo.toml` (`md-5 =0.10.6`, `regex =1.13.1`,
`rav1d-safe =0.6.0`, `avif-parse =2.1.0`).

Kotlin: only `client/mclaunch/src/test/.../J5aLaunchCommandFixtureTest.kt` (D6). Fixture
inputs: `protocol/fixtures/j5a/*.json` (the `version.json` of the configured Forge 47.4.29
and NeoForge 21.1.265 installers); output `protocol/fixtures/j5a.json`. Library checksums are
stripped from the inputs so placeholder jars pass the launch-time check; the command does not
depend on them.

## Automated validation (Windows pwsh; IntelliJ not open)

| Check | Result |
|---|---|
| `cargo clippy --all-targets -- -D warnings` | pass (`clippy.log`) |
| `cargo test --workspace` | bin 134 passed / 1 ignored (J4c: 111 + 23 new), lib 11, renderer 62 / 1 ignored (`rust-tests.log`) |
| `cargo build --release` | pass; exe 41,871,360 bytes (J4c 36,343,808; AVIF about +2.99MB, J5a modules about +2.54MB) (`release.log`) |
| `cargo-zigbuild check --release` aarch64-apple-darwin | pass, only the 2 existing warnings from the shared updater file (`cross-macos.log`) |
| `cargo-zigbuild check --release` x86_64-unknown-linux-gnu | pass, same 2 warnings (`cross-linux.log`) |
| Kotlin `J5aLaunchCommandFixtureTest` (`--rerun`, check mode) | pass (`kotlin-fixture.log`); written once with `RDI_WRITE_FIXTURES=1` |

Notable tests: the Rust command equals Kotlin's argument by argument for both fixture cases
(defaults on Forge; per-pack Java 17 without compact headers, 8192MiB, custom JVM args, a
non-ASCII offline name on NeoForge); loader versions are checked against the strings in
`McVersion.kt`; offline UUIDs equal Java's `nameUUIDFromBytes`; rules use `amd64` (Rust's
`x86_64` would have matched the `x86` rule and added `-Xss1M`); Kotlin-layout ready checks;
source plans; asset plan; sessions, duplicate check, stop/force stop, console bounds;
`loadSnapshot` validation; updater probe parsing and scores; AVIF decoding.

## Live run (isolated `live/run`, test account, slint-ui MCP over HTTP)

Run 1 used `live/preview.ps1`, which sets the service command to a path that does not
exist: any attempt to start the service would have failed with 无法准备游玩. None did
(0 such log lines, 0 service processes).

| Check | Result |
|---|---|
| Java search at launcher start | found `C:\Users\Public\graalvm-jdk-25.0.2+10.1` in about 2s (first run showed a `\\?\` prefix from `canonicalize`; fixed, and the second run showed the plain path) |
| Local Forge 1.20.1 pack, never started, empty `mc` folder | play dialog from Rust (离线模式, nickname = account name); Forge installer downloaded and run, libraries, client jar, 895 assets repaired, title screen: `live/forge-1.20.1-title.png` (game window only), console `live/console-forge-first-start.txt` |
| Local NeoForge 1.21.1 pack, never started | same; 107 assets (the rest shared); title screen `live/neoforge-1.21.1-title.png`; `live/console-neoforge-first-start.txt` |
| Both games at once | status bar 单机 - … 运行中 for both; console lists both sessions (`live/console-forge.png`) |
| Duplicate check | starting the running Forge pack again shows 重复启动 (`live/duplicate-check.png`) |
| 终止游戏 (force stop all) | both processes gone, 已强制结束 for both (`live/force-stop.png`) |
| Second start of an installed pack | process started about 1s after the click; console 客户端完整/运行库完整/资源完整, no install (`live/console-forge-second-start.txt`) |
| Normal game exit (window closed) | console ends with 已退出 |
| Token in logs | `Launch Command` lines show `--accessToken ***` (3 of 3) |
| **Kotlin accepts Rust's install** (run 2, `live/preview-service.ps1`, service rebuilt from current source) | room 28257的1.21 (NeoForge 1.21.1) through the service on the Rust-installed folder: 客户端完整, 运行库完整, 资源完整, no 开始自动安装 (`live/console-room-kotlin-on-rust-install.txt`) |
| **Rust accepts Kotlin's install** | Rust's NeoForge loader files moved out (to a scratch folder, not deleted); the room start made Kotlin install NeoForge again (安装成功); then the Rust local NeoForge start showed 完整 for all three, no install (`live/console-room-kotlin-reinstall.txt`, `live/console-neoforge-rust-on-kotlin-install.txt`) |
| Merged game bar | the service room session and Rust sessions show in one status line; 终止游戏 stopped the service game too |

The workspace's `ui-service` install (`client/ui/ui-service/build/install`, built 2026-11-10)
was stale: it still installed NeoForge 21.1.250. It was rebuilt with
`:ui-service:installDist` before run 2; no source change.

The live folder (about 485MB) is kept for the owner's checks; the test credentials file was
moved out of it.

## Differences from Kotlin and the plan

- **D8 crate**: `rav1d-safe` (AGPL-3.0) instead of a permissive crate, after the owner's GPL
  statement; plain `rav1d` aborts on corrupt files.
- **D5 on macOS/Linux**: the updater has no rules there. Rust looks at JAVA_HOME/JDK_HOME,
  PATH, `/usr/lib/jvm/*`, `/Library/Java/JavaVirtualMachines/*/Contents/Home`, `~/.jdks`,
  `~/.sdkman/candidates/java` (new, unverified). The automatic install is Windows only (the
  address is a Windows MSI); elsewhere the player is told to install JDK 25.
- **D5 validation** is a port, not a shared file: `find_best_jdk25`/`test_java_candidate`/
  `path_score` live in the updater's `main.rs` behind `#[cfg(windows)]`. Candidate search is
  the shared file. The cache file is `available_jdks.txt` next to the launcher executable, as
  the updater does.
- The Forge/NeoForge installer runs on the Java 25 found at startup, also when the pack has
  its own Java (Kotlin used the service's Java 25).
- Rust does not rewrite the loader's version JSON on every read as Kotlin does; it applies the
  same normalization in memory. Before running the installer it writes the installer's own
  `version.json` with id/inheritsFrom/jar normalized (the installer then overwrites it).
- Only modern installers (`version.json`) are handled; the legacy `versionInfo`/`json` paths
  of old Forge are for 1.7.10 (J5c).
- Not ported (D9): reusing files from other launchers' folders.
- Downloads use the J4b downloader's retries and source order; McInstall's own 6-attempt loop
  with "prefer fallback after a checksum mismatch" is not copied. Download progress lines are
  one per 8MiB instead of one per callback.
- After a stop, the session keeps 已停止/已强制结束; Kotlin overwrote it with the later
  `MC已结束，退出代码: 1` line. The exit line is still appended to the console.
- No `data.db` means default launch options (Rust never creates it; Kotlin always has one).
- `os.version` on Windows is taken as `10.0` (what Java reports on Windows 10 and 11);
  free memory comes from `sysinfo` available memory instead of Java's `freeMemorySize`.
- The asset index is iterated in key order, not file order; this only changes which of two
  numbered sounds with the same number would be downloaded.
- Microsoft online play of local packs stays on the service (J5c). Rust still blocks a
  duplicate start of a pack it is running.
- A Rust-started game is not recovered after a launcher crash (D10 is J5b); closing the
  launcher normally ends it, as the exit dialog says.
- The service's console is only asked when the service has games, so opening the console
  never starts the service; the service may now idle-stop while a Rust game runs.

## Not verified (owner checklist)

macOS and Linux real starts (cross-checks only); the Compose launcher itself (the service runs
the same `McInstall`/`MinecraftLauncher` code and stood in for it); Forge 1.20.1
cross-acceptance (no 1.20.1 room for this account); the Java-missing dialog and the MSI
install; per-pack Java, memory, JDWP and custom JVM arguments in a real start (fixture only);
stopping during the install; the exit dialog with a Rust game running; AVIF icons on the
plaza page; focus and minimize behaviour (the launcher window was minimized after each game
start in the live run; not changed by J5a as far as the code shows).
