# UI port plan: Compose launcher to Slint

Written 2026-11-05 for an agent with no prior context. This replaces M2-M4 in `M1-M2-PLAN.md`; M1 is finished and committed. The owner reviews at every gate.
`/tmp` is a tmpfs, so this repository copy is the one to keep up to date.

## Current status (handoff)

Updated 2026-11-06. **GU3 passed. U4 is handed to the next agent: do batch a (local modpacks) only, then stop at GU4a.**

- GU1 passed 2026-11-05 (`evidence/ui-port/u1/gate-summary.md`, commit `c6f21e82`).
- GU2 passed 2026-11-06 (`evidence/ui-port/u2/gate-summary.md`, commit `8a6fe6a9`), after the concurrency follow-up.
- GU3 passed 2026-11-06 (`evidence/ui-port/u3/gate-summary.md`, including "Owner review follow-up"; commit `ce984283`). The owner checked IME and scale changes. Buttons now center their content and field labels sit above the outline.
- Later gates are unchanged except for the new U4 order in section 7. The accepted Mac terrain problem is recorded in section 4.
- `dev` has unpushed commits: `c6f21e82`, the owner's `a1646b01`, `4cbf217e`, `8a6fe6a9`, `636a1295`, `ce984283` and the U4 handoff commit. Do not push without approval.
- Do not stage, commit or push. Report at GU4a; the reviewer commits after the owner's review. `client/ui` and `common` hold a lot of the owner's uncommitted work, some of it only CRLF flips; never overwrite it.

### U4 handoff instructions

Read `AGENTS.md`, `client/ui/AGENTS.md`, sections 1-3 and 7 of this plan, `README.md` here, the GU2 and GU3 gate summaries for their limits, and the U3 scope notes below: the glass, image and service rules there still apply. Run `git status` before touching `client/ui` or `common`. Record a fresh baseline before moving code: Rust `cargo test --workspace --lib --bins`, plus focused Kotlin tests for every module you will touch.

Explain your batch a design to the owner before writing code, in Mandarin: the screens, new service methods and events, and anything you propose to leave out. Build after the owner approves. Then stop at GU4a and do not start batch b.

### U4 owner decisions (2026-11-06)

- **Local modpacks first.** `LocalModpackInstallScreen` moved to the front of U4 as the new batch a. The old batches a-d are now b-e, with gates GU4a-e (sections 7 and 9).
- **No friends screen.** `FriendsScreen` is dropped from the port. The friend system is already archived in Compose: `AppNavigation.kt` has only the comment "Friends navigation is archived with the friend system", and no `FriendsScreen.kt` exists.
- **GU3 review leftovers belong to U4.** Fix them in the batch whose screens need the component, at the latest before GU5:
  - the modal scrim starts at y=40, so the title bar stays undimmed;
  - the toast is drawn under the scrim, so its close button cannot be reached while a dialog is open;
  - `ContentGrid` still shows the `RScrollbar` thumb when the list is empty or failed;
  - names sort as text (更好的村庄1, 10, 13). Check how the Compose screen sorts before changing it;
  - panel title spacing in the gallery.
  Batch a uses dialogs and content lists, so fix the first three there.

### Batch a: local modpacks

Goal: without the Compose UI, a player can import a pack in the Slint launcher, find it in the menu's 整合包 tab, change its advanced options and start it.

- **`LocalModpackInstallScreen`** (430 lines) with `LocalModpackInstallViewModel` (`calebxzau/rdi/client/ui/viewmodel`). It has three sources: a CurseForge zip or Modrinth mrpack archive, a game folder (scanned candidates plus a folder picker), and an empty pack. It also covers archive read progress, the content list (`ContentGrid`), optional files and the missing-file confirmation. Match Compose's behavior and Chinese text; the rules live in the view model, `LocalModpackInstaller` and `LocalModpackScanner`.
- **The real 整合包 tab.** Today the Slint tab is a stand-in fed by `/modpack/search` through `data.get` (`src/app.rs`, around line 431). Replace it with the installed packs that Compose shows through `LocalModpackManager.kt` (995 lines; `rememberLocalModpackManager`, `InstalledTile`). Show both RDI packs and local packs, and the empty text 「还没有整合包，去整合广场看看吧」.
  - The tile's play action starts the pack.
  - Its options action opens `ModpackLocalAdvanceOptionScreen`.
  - Its info and content actions keep calling `stub`; they arrive in batch d.
  - Quests are cut, so they get no entry.
  - `LocalModpackManager.kt` also uses the cut code editor. Per section 1, check whether a player needs it to finish a modpack task. If so, ask the owner.
  - When that tab's data source is gone, remove the `/modpack/search` stand-in from `data.get`'s allowlist.
- **`ModpackLocalAdvanceOptionScreen`** (257 lines).
- **Starting a pack.** RDI packs use the existing McPlay path. Local packs launch without the RDI core (`McPlayArgs.withRdiCore` → `MinecraftLaunchRequest.rdiRuntime`) and first show `LocalPlayDialog`. That dialog offers offline play, with a nickname and a warning for names outside `[A-Za-z0-9_]{3,16}`, or online play through Microsoft device-code login, which needs `RAccount.msid`. Design the service method yourself: either generalize the `host.start` path or add a new one. Duplicate-version confirmation and `game.state` must behave the same as for rooms.
- **Install tasks, console and task panel.** `installer.install` returns a run id; Compose then returns to the 整合包 tab and opens that task. Wire U3's `TaskProgress` and `Console` (`ui/activity.slint`; gallery fixtures today) to real service data:
  - add the events to `protocol/README.md` and `protocol/fixtures/messages.json`, used by both languages;
  - add them to the event allowlist in `src/service.rs` (around line 56). Any other event drops the connection;
  - send conflated snapshots no more than every 34ms, like the existing topics;
  - end the subscription on the terminal task event.
- **File pickers.** Compose uses AWT `FileDialog` (`calebxzau/rdi/client/ui/WindowsUtils.kt`: `pickLocalModpackFile`, `pickLocalDirectory`). In Slint, use the `rfd` crate on the UI side and send the chosen path to the service. Keep Compose's title 「选择客户端安装包」, the Downloads start folder and the zip/mrpack filter. Do not block the Slint event loop, and do not let the service open AWT dialogs.
- **Navigation.** Add a `Screen` variant in `src/navigation.rs` for each ported screen. Back from the install screen returns to the menu with the 整合包 tab selected. After an install starts, return to that tab and open the task panel, matching Compose's `onStarted`.
- **GU2 leftover.** If the NeedInstall message naming 「Compose启动器」 shows up on this flow, fix it here; otherwise it stays in batch d.
- **Not in batch a.** Screens from other batches. Changes to the Compose launcher, which keeps shipping. Changes to the local pack storage format (`versions/local_<uuidv7>` plus `rdi-local-pack.json`).

### GU4a pass criteria

1. Each ported screen and dialog has side-by-side Compose and Slint screenshots at scales 1 and 2, including its loading, empty and error states, and an entry in `evidence/ui-port/u4a/owner-checklist.md`.
2. Logic that lived in Compose screens moves to `ui-logic` (or an existing headless module), with focused unit tests. New service methods and events have `ui-service` tests. The golden fixtures pass in Rust and Kotlin.
3. Real end-to-end run on Windows through the Slint launcher, with the monitor on:
   - import one archive pack (CurseForge zip or mrpack) and one game folder;
   - see both in the 整合包 tab;
   - change one advanced option and check that it persists;
   - start a local pack offline and reach the title screen;
   - start an RDI pack from the tab.
   Run online (Microsoft) local launch only if the test account has an `msid`. Otherwise report it as not verified.
4. Rust tests, `cargo clippy --workspace --all-targets -- -D warnings` and the release build pass. Kotlin baselines are unchanged: `client-core:test` 235 tests with 4 known failures, ui-service and ui-logic all passing.
5. For information only, not a pass criterion: release memory of the menu with the real 整合包 tab, using the focused protocol, UI process and service measured separately.

### U3 scope notes (still apply)

Section 6 is the scope. These notes add the facts and decisions it depends on.

- **Scene freeze (implemented in U3, owner decision 2026-11-05).** Only the menu screen animates the 3D scene. Every other screen holds its last frame, and so does a running game; the scene stays loaded. `scene_frozen` now follows the shell navigation and `game-running`, which `src/app.rs` sets from the service's `game.state` event. The proof-only diagnostic override remains separate. GU1 criterion 2 was measured under this policy (`evidence/ui-port/u1/held-menu-1`, `freeze-1`).
- **Navigation.** Replace `AppNavigation.kt` (`client/ui/src/main/kotlin/calebxzhou/rdi/client/ui/AppNavigation.kt`) with a back stack, without the cut features in section 1. U3 builds the shell only; destinations whose screens arrive in U4 keep calling the existing `stub` callback. Do not build placeholder screens.
- **Current Slint code.** `ui/*.slint` is small (app 162 lines, plus title bar, tab bar, tile grid, tiles, glass button, player head, resize handles, theme). `src/app.rs` (950 lines) holds state, jobs and Slint wiring; split it as the shell grows instead of letting it absorb every screen.
- **Compose sources to match.** `calebxzau/rdi/client/ui/window/WindowChrome.kt`, `GlassTileGrid.kt`, `LiquidGlass.kt`, `Dialogs.kt`, `IconButtons.kt` (`CircleIconButton`, which `AGENTS.md` prefers for icon actions) and `calebxzhou/rdi/client/ui/comp/` (`ContentGrid`, `ContentCard`, `HttpImage`, `RScrollbar`, `PasswordField`/`RPasswordField`, `ToggleButton`, `Console`, `Task2DetailDialog`, `HeadButton`, `IconTileCard`). Match look and behavior, not structure.
- **Glass.** Glass is a wgpu pass drawn below all Slint content (`crates/rdi-renderer/src/glass`). Generalize it so panel rectangles come from Slint layout instead of duplicated hand-placed rectangles. The Slint lessons in section 2 apply: no rounded `clip` or `opacity` on elements with children, update `VecModel`s in place, set both `x` and `y` when placing by hand.
- **Images.** Network images and player heads already exist in `src/images.rs`; keep its per-host connection limit (forgecdn).
- **Gallery.** Behind a flag (for example `--gallery`), show every component with its states: normal, hover, pressed, disabled, focused, loading, empty and error, plus a dialog, a toast and a long Chinese label. Capture it with `tools/capture.ps1` at scales 1 and 2 (`--scale`). Chinese text follows `AGENTS.md` section 12.
- **Console and task progress need service data.** If they get real data in U3, add events to the protocol schema and golden fixtures (`protocol/`) in both languages. The Rust reader in `src/service.rs` treats any event other than `hostList.state` and `game.state` as a broken stream and drops the connection, so update its allowlist with the new events. Gallery-only sample data is fine for U3 if the service side waits for U4.
- **Service rules from U2.** `RequestDispatcher` runs every handler on the single `runBlocking` thread: suspend, or move blocking work into `withContext(Dispatchers.IO)`, never block that thread. On the Rust side call the service only through `api.call` (it uses `spawn_blocking`), never from the UI thread. Classes used by plain JUnit tests must not reference `calebxzau.rdi.client.lgr`; its file also loads `AppConfig` and fails at class init. Use a local `KotlinLogging` logger.
- **Report at GU3, not a criterion.** Remeasure memory and startup on a release build with the focused-window protocol from U1/U2, UI process and service separately. The GU2 numbers came from a debug build and are not comparable with earlier gates.
- **Not U3.** The 「Compose启动器」 install message and game window sizing through the headless AWT fallback belong to U4: the message to batch d (or batch a if it appears on the local modpack flow), the window sizing to batch c. The owner moved `LocalModpackInstallScreen` to the front of U4 on 2026-11-06.

### Validation baselines after U3

Rust after U3: renderer 61 + library 4 + binary 23 pass, 1 GPU capture test ignored. Kotlin was not touched in U3; the U2 numbers stand: `client-core:test` 235 tests with 4 known failures (3 Fabric-rejection tests and `MediaProcGameClasspathTest`); ui-service 11 and ui-logic 6 pass; existing focused Compose tests 41 pass; `cargo clippy --all-targets -- -D warnings` clean. Run Rust tests with `cargo test --lib --bins` through Windows `pwsh.exe`. Record a fresh baseline before moving code.

### U2 results (2026-11-06)

Shared Kotlin logic and the headless service are implemented. Real Slint login,
room list, in-game launch, duplicate/stop/close behavior, menu/in-game service
crash recovery, and Compose runtime were checked on Windows. The owner-installed
pack and owner-requested local core JAR were used. Service crash interrupts the
game connection; reconnecting was verified. There are4 unchanged client-core
baseline failures. See the GU2 gate summary; wait for owner review before U3.

Owner review follow-up (2026-11-06): a launcher/service crash disconnecting or ending the
game is accepted. Requests are now handled concurrently on both sides (Kotlin
`RequestDispatcher`, Rust `Arc<Service>` without an outer lock); requests without a valid
id are ignored. See "Owner review follow-up" in the gate summary. Still open for U3/U4:
the 「Compose启动器」 install message, game window sizing through the headless AWT fallback,
and remeasuring memory/startup on a release build.

### Original U2 preparation notes (historical)

Read `AGENTS.md`, `client/ui/AGENTS.md`, sections 1-3 and 5 of this plan, and section 2 of `M1-M2-PLAN.md`. Then run `git status`: `client/ui` and `common` hold a lot of the owner's uncommitted work, which must not be staged or overwritten.

### Known test baselines for U2

These notes are 2-3 weeks old, so record a fresh baseline on the current tree before moving any code. A failure that is already there is not caused by the move, but it must still be reported.

- Prefer focused `--tests` runs in `client/ui`. A full `:test` was seen hanging in `ModpackVersionBaseWorldManageViewModelTest`, and `ModpackUploaderManageViewModelTest`, `ImageDecodingTest`, `HostTest` and `ModpackTest` failed independently of UI work.
- `HostCreateViewModelTest` had 2 known failures.
- `:client-core:test` had 199 tests with 4 failures: 3 stale Fabric-rejection tests and `MediaProcGameClasspathTest`.
- `:net:compileTestKotlin` does not compile because of `RHttpClientTest.kt`. With that file excluded, 3 `FileDownloaderTest` cases fail on HEAD.
- Non-Unit `runBlocking` test functions are silently skipped. Declare test functions with a Unit return.

### Environment for U2 (still applies to U3)

- `client/ui` Gradle through IntelliJ MCP needs `executeInShell: true` for `.\gradlew.bat`, and MCP truncates long output, so redirect to a log and read that. Do not use the Gradle daemon under a WSL `pwsh.exe` pipe: it holds the pipe open until timeout even though the work succeeded.
- Do not compile `client/ui` while the owner's Compose hot reload app is running; both write `build/classes`.
- If tests throw `NoClassDefFoundError` for a `:mclaunch` class, the jar is stale: run `:mclaunch:jar --rerun` with the tests.
- Game launch tests need the monitor on. If a screen named `WinDisc` is present, or the game exits with `Failed to find a primary monitor`, ask the owner to turn it on. Do not debug the game.
- Mouse-driving tests take over the owner's cursor. Say so before running them.
- `ktorClient` SSE reconnects forever, so a service subscription built on it must stop on a terminal event.
- Keep Compose startup flags in mind: the service has no UI, so do not carry the launcher's `-X` and JBR hot reload settings across without a reason.

### Open items from U1 that U2 must not disturb (historical)

- `src/main.rs` `scene_frozen` is only set by `--ui-proof` today. Hooking it to navigation and a running game is U3 work, but the running-game state U2 exposes must make that possible.
- `src/window.rs` and `ui/resize_handles.slint` hold the window controls, now on the menu as well.
- Slint printed one "long callback/instantiation chain detected" warning in each long run, in every case including Slint only. Not attributed.

## 1. Goal and decisions

Port the launcher UI (`client/ui`, Kotlin + Compose) to Slint on the Rust + wgpu base in `client/slint-proto`. The Compose launcher keeps shipping until the final switch.

| Topic | Owner decision (2026-11-05) |
|---|---|
| Priority | UI port now. The complete 3D scene is not urgent |
| Background | Keep the current golden valley with its default camera. No walking, object picking or world expansion. Those parts of the old plan (M2.1-M2.3, resolution and 30FPS A/B) are on hold. Only the menu screen animates the scene; other screens hold its last frame with the scene kept loaded, and the game running keeps it held too |
| Order | U1 risk check first, then U2-U5 below |
| Cut features | Not ported: code editor, quest viewer, host file manager |
| Business logic | Stays in Kotlin, in a headless child process |
| Communication | stdio, length-prefixed JSON, no port |
| Mac testing | Try GitHub Actions `macos-15` headless first. See section 4 |
| License | GPLv3 project; Slint's GPL option applies. DXC stays the default, with `LICENSE-LLVM.txt` shipped beside `dxcompiler.dll` |
| Memory | VRAM does not count. Report memory; small increases never block work |

### Cut feature scope

Remove these only from the Slint launcher. **Do not change them in the Compose launcher.** When a removed entry leaves a gap, drop the entry instead of showing a stub.

| Feature | Compose code | In the Slint launcher |
|---|---|---|
| Quest viewer | `QuestViewerScreen.kt`, `DebugQuestPreviewDialog.kt`, entries in `MenuScreen.kt`, `Ui2Screens.kt`, `AppNavigation.kt` | No entry |
| Host file manager | `HostFilesScreen.kt`, `HostFileExplorer.kt`, entry in `HostInfoPanes.kt`, `AppNavigation.kt` | No entry |
| Code editor | Used from `HostFileExplorer.kt` and `LocalModpackManager.kt` | Inspect the `LocalModpackManager.kt` use during U4. If a player needs it to finish a modpack task, ask the owner |

The same goes for other complex controls: none are planned. If a screen seems to need one, propose a simple alternative at its gate.

### Out of scope

New features or redesigns, world expansion, deleting the Compose launcher, shipping before U5.

## 2. Ground rules

Everything in section 2 of `M1-M2-PLAN.md` applies: Mandarin with the owner, stop at gates, no permanent deletion (`DEL/<new-unique-dir>/<relative-path>`), explicit `Result` handling, no spaces between Chinese and Latin or digits, player words (房间), explicit-path commits on `dev`, no `Co-Authored-By`, Windows builds through `pwsh.exe`, test account handling, and memory only from focused runs.

Additional rules:

- **Never push without the owner's approval.** The repository is public. Before a push, scan the outgoing diff for credentials and personal details.
- `AGENTS.md` at the repository root and `client/ui/AGENTS.md` apply to Kotlin work. New Kotlin packages use `calebxzau.*`; files moved to another module keep their existing package.
- Gradle: run from `client/ui` through IntelliJ MCP first, otherwise Windows `pwsh.exe` with `gradlew.bat`. Never use WSL Gradle. Never run master and client Gradle in parallel. Do not run `packageUiRelease` to validate; it replaces the release directory.
- `client/ui` and `common` often contain the owner's uncommitted work. Never stage it. Check `git status` before touching a file there.
- Fidelity: match the behavior, the Chinese text and the look of the Compose screen. A screen is not done until its error, empty and loading states work.

### Slint lessons already paid for

- Rounded `clip` or `opacity` on elements with children creates an offscreen layer (about 150MB on a tile screen). Bake corners, and multiply alpha per leaf.
- Do not replace a `VecModel` on refresh. Update it in place. `SharedPixelBuffer` images re-upload.
- Slint has no backdrop blur or custom shaders. Glass draws below all Slint content. Overlapping glass panels cannot refract each other's text.
- `forgecdn` resets TLS beyond about 5 parallel connections; keep the per-host image limit.
- Slint centers an element in its parent when `x` or `y` is omitted. Set both when placing by hand. This put the window's left and top resize handles on its center lines.

## 3. Code facts

Verified on 2026-11-05. Re-check before relying on them.

**Slint side.** Workspace `client/slint-proto`. `crates/rdi-renderer` has no Slint dependency and holds scene, glass and GPU setup. The app in `src/` and `ui/` has the menu screen, `api/` (a small Rust HTTP client for the prototype) and image loading.

**Glass limits.** `glass::Frame` has only `grids` and `pills`, up to 32 uniform slots. `App::frame` (`src/app.rs`) duplicates rectangles that `ui/*.slint` lays out.

**Kotlin side.** Under `client/ui/src/main/kotlin` (both `calebxzhou/rdi/client` and `calebxzau/rdi/client`):

- About 39k lines. 35 `*Screen.kt` files total about 12k lines. Largest non-screen UI: `HostFileExplorer.kt` (cut), `ContentGrid.kt` 1093, `LocalModpackManager.kt` 995, `AppNavigation.kt` 907, `HostInfoPanes.kt` 762, `WindowChrome.kt` 585.
- 19 view model files, 8760 lines. All use `StateFlow`; none import Compose or AWT. Their dependencies sit behind `*Gateway` interfaces and are wired with Koin in `calebxzau/rdi/client/di/AppModule.kt`.
- **Not every screen has a view model.** `LoginScreen.kt` has none, and `McPlayScreen.kt` runs the launch sequence itself through `McPlayStore`. Expect logic inside other screens too. It must move out into testable Kotlin, not be rewritten in Rust.
- `client-core` (11.5k lines) and `client-store` have no Compose imports. Three `client-core` files use `java.awt` or `javax.swing`; check that each still works headless.
- Only one `println` was found in `client/ui/src/main` and `client-core`, but dependencies can print too.

**Startup.** `client/updater/src/main.rs` finds JDK 25 (`java_discovery.rs`) and starts `calebxzau.rdi.client.MainKt`.

## 4. U1: risk check

Goal: prove Slint can carry real launcher screens before porting them. Build the smallest thing that exercises each risk, behind a `--ui-proof` flag. Reuse `src/api` for real calls; the Kotlin service does not exist yet.

### Work

1. **Form and Chinese input.** Port the look of `LoginScreen.kt`: account and masked password fields, validation messages, a submit that really calls `POST /player/jwt`. Add one free-text 房间 name field. Check Tab order, Enter, selection, copy and paste, and IME candidates following the caret in the no-frame transparent window. IME composition cannot be meaningfully automated, so **the owner tests it at the gate**. Supply exact steps.
2. **Long list.** A `ListView` with 1000 rows of host-card-like content and network images. Scripted wheel scrolling for 30 seconds over the live scene.
3. **Modal and panel.** A glass panel opening with a 250-350ms slide and scale, plus a modal dialog with a dimmed backdrop. Add `glass::Panel { rect, corner_radius, tint, opacity }`, drawn with the existing haze and rim pipelines. Slint is the only source of the rectangle, exposed through `out` properties. No duplicated geometry for new panels. Screenshots at scales 1, 1.25 and 2 must align within 1 physical pixel.
4. **Window.** Title bar drag, minimize, maximize, close, resize, DPI change. These have never been verified.
5. **Text.** Mixed Chinese, Latin and icons; long-text wrapping and eliding; selectable text if any Compose screen relies on it.
6. **Measurement.** Make the harness really focused first: every G1 run logged `focused=false`. Report p50, p95, p99 and worst frame for the list and the panel animation.

### Mac and Linux through GitHub Actions

Add `.github/workflows/slint-proto-platforms.yml` with `workflow_dispatch` and a `push` trigger filtered to `client/slint-proto/**` and the workflow itself, on `macos-15` and `ubuntu-24.04`. **Pushing it needs the owner's approval.** There are also unpushed commits on `dev`, so scan the whole outgoing diff.

Evidence for this approach: wgpu's own CI runs GPU tests on `macos-14` arm64. GitHub's documentation says Metal Performance Shaders are unsupported on these runners, and no source confirmed the Metal device on `macos-15`. So **probe first**: print the wgpu adapter info, then build the rest.

Steps:

1. Make `scene_preview` portable. It stops at `anyhow::ensure!(cfg!(windows), ...)` and forces `wgpu::Backends::DX12` (`crates/rdi-renderer/examples/scene_preview.rs:12` and `:68`). Windows keeps DX12; other platforms use the primary backend.
2. Run `cargo test --workspace`, the adapter probe, and `scene_preview` at times 0, 15, 45.
3. Upload PNGs and logs as artifacts. Compare with the Windows output and report the MAE; different GPUs will not match exactly, so look for missing geometry and wrong colors.
4. On Linux install `mesa-vulkan-drivers` and run under `xvfb-run`.
5. Best effort: start the real app window for 20 seconds, take a screenshot with `screencapture` or `xwd`, close it, and record the exit code. This has no login and uses no secrets. Never add account credentials to CI.

| What CI can show | What it cannot show |
|---|---|
| The code compiles and tests pass on each platform | Real GPU performance. Paravirtual and software numbers are not evidence |
| WGSL translates to MSL, and a Metal device can draw the scene | Bugs specific to real Apple GPU drivers |
| The window path starts and exits, if the runner allows it | IME, DPI feel, input feel |

If `macos-15` has no usable Metal adapter, report that, keep compile and tests, and try `macos-14` once. Do not start paid NoMac time without asking. Real-Mac and real-GPU Linux tests remain required before U5.

Owner decision 2026-11-05: wrong background terrain on Mac is acceptable, because Mac has only one or two players. It blocks no gate and is not investigated for now. The launcher UI, IME and game launch on Mac are still tested at U5.

### U1 pass criteria

Each result is **pass, fail or not verified**, with evidence.

| # | Criterion | Requirement |
|---|---|---|
| 1 | Form | Validation, submit and keyboard work; owner confirms IME |
| 2 | List and menu | On the 780M, 60Hz display, focused, in every one of 3 runs each: 1000-row list over the held scene frame, and wheel scrolling on the real menu over the live scene. Long frames (interval over 25ms, a missed vsync) ≤ 1%, delivered FPS ≥ 58, frame CPU p95 ≤ 16.7ms, no steady memory growth |
| 3 | Panel and modal | No visible offset at all scales, during animation (owner replaced the 1 physical pixel limit on 2026-11-05; the limit was never measured) |
| 4 | Window | Every action in item 4 works |
| 5 | Text | Readable at all scales, with no missing glyphs |
| 6 | Platforms | Mac and Linux CI results reported, including what stays unverified |

Owner approved criterion 2 on 2026-11-05, replacing p95 ≤ 16.7ms and p99 ≤ 25ms: frame intervals average 16.67ms at 60Hz, so a p95 limit of 16.7ms fails on normal jitter even when no frame is missed. The new limits were written down before the rerun. On the same day the owner decided that only the menu screen animates the scene; every other screen shows its last frame, and the scene stays loaded. The list is therefore measured over the held frame, and the menu over the live scene, with the same limits.

If a failure is a Slint limit and the workaround would take more than a day, **stop and report** with options. Do not begin replacing Slint.

**Stop at gate GU1.** Write `evidence/ui-port/u1/gate-summary.md`.

## 5. U2: Kotlin service and main flow

Goal: login, host list, start the game, from Slint, through the real Kotlin code.

### Kotlin

1. Add a `ui-logic` module under `client/ui` with no Compose dependency. Move the view models, gateways and store classes the service needs. Packages stay the same. The Compose launcher depends on the module and must keep working. Move only what U2 needs now, and more in later batches.
2. Extract logic from screens: login first, then the `McPlayScreen.kt` launch sequence. Add focused tests with mocked gateways, matching behavior, including stop requests and duplicate-version checks.
3. Add a `ui-service` module, package `calebxzau.rdi.service`, with a headless main.

### Protocol

- Frame: 4-byte big-endian length, then UTF-8 JSON. Reject frames over 8MiB as a protocol error.
- Request `{"id":1,"method":"host.start","params":{}}`. Response `{"id":1,"result":{}}` or `{"id":1,"error":{"code":"...","message":"..."}}`. `message` is Chinese text a player can read.
- Event `{"event":"hostList.state","params":{}}`, with no `id`.
- `subscribe` sends the current state first, then updates. A `StateFlow` sends full snapshots, conflated to at most about 30 per second. Add paging for big lists instead of a large snapshot.
- Files and images never go through the protocol. Send paths or URLs.
- A `protocolVersion` handshake comes first. A mismatch is a clear error.
- Define the schema in one place. Keep golden JSON fixtures that both the Kotlin and Rust tests read, so the two sides cannot drift silently.

### Process rules

- Very early in main, keep the original stdout stream for the protocol and redirect `System.out` to the log, so a library's `println` cannot corrupt a frame. stderr goes to the log file.
- Check that the game process and the other tools `mclaunch` starts never inherit the protocol pipes.
- On stdin EOF the service shuts down. First find out what the Compose launcher does when it closes while the game is running, and keep that behavior.
- Rust restarts a crashed service and shows a plain Chinese message. The 3D scene keeps running. Failed requests surface as errors, not hangs.
- Rust finds Java the way `client/updater/src/java_discovery.rs` does. Add a development override for the service command. Do not change the updater yet.
- A small developer command-line client sends requests and prints responses, for debugging.
- Rust no longer calls the API directly once the service covers it. Move the prototype's direct calls behind the protocol, and archive unused `src/api` code under `DEL/`.

### U2 pass criteria

- A real account logs in, the host list shows, and starting a 房间 reaches in-game on Windows with the monitor on. Check the display first: with the monitor off the game fails to find a primary monitor.
- Kill the service mid-session: a message appears and it recovers. Close the launcher during a game: same behavior as Compose.
- The existing `client-core` and new module tests pass. Note known pre-existing failures instead of hiding them. The Compose launcher still compiles and runs.
- Report service JVM memory and startup time separately from the UI process.

**Stop at gate GU2.**

## 6. U3: shell and shared components

Build these once, in Slint:

- Navigation with a back stack, replacing `AppNavigation.kt` without the cut features.
- Window chrome (`WindowChrome.kt`) and glass tile grid (`GlassTileGrid.kt`).
- Shared components: buttons, icon buttons, text and password fields, cards, `ContentGrid`, dialogs, toasts and errors, scrollbars, loading states, network images (`HttpImage`), player heads, console (`Console.kt`), long-task progress (`Task2DetailDialog`).

Generalize glass so all panels come from layout, instead of duplicated rectangles. Add a small component gallery behind a flag, so the owner can review them together.

**Stop at gate GU3** with gallery screenshots at scales 1 and 2.

## 7. U4: screen batches

For each screen: move logic to `ui-logic`, expose it through the service, write the Slint screen, then test its error, empty and loading states. Each batch is one gate (GU4a-e) with side-by-side Compose and Slint screenshots, and a checklist per screen.

| Batch | Screens |
|---|---|
| **a. Local modpacks** (moved first by the owner, 2026-11-06) | `LocalModpackInstallScreen`, the menu's 整合包 tab from local disk (`LocalModpackManager`), `ModpackLocalAdvanceOptionScreen`, `LocalPlayDialog`, real console and task progress |
| **b. Account** | `RegisterScreen`, `ResetPasswordScreen`, `SettingScreen`, `PlayerInfoScreen` |
| **c. Hosts (房间)** | `MenuScreen`, `HostInfoScreen` + `HostInfoPanes` (no file manager), `HostNewCreateScreen`, `HostMembersScreen`, `HostBackendScreen`, `HostWorldScreen`, `HostRollbackScreen`, `HostSaveImportScreen`, `McPlayScreen` |
| **d. Modpacks** | `ModpackPlazaScreen`, `ModpackInfoScreen`, `ModpackVersionInfoScreen`, `ModpackVersionEditScreen`, `ModpackVersionBaseWorldManageScreen`, `ModpackUploadScreen`, `ModpackUploaderManageScreen` |
| **e. The rest** | `MailScreen`, `MailDetailScreen`, `ModCatalogScreen`, `ModCatalogInfoScreen`, `ResourceListScreen`, `ResourceInfoScreen`, `BaseWorldListScreen`, `BaseWorldUploadScreen`, `WardrobeScreen`, `SkinPreviewScreen` |

`FriendsScreen` is not ported: the friend system is archived in Compose (owner, 2026-11-06).

`WardrobeScreen` and `SkinPreviewScreen` need a 3D avatar view. Build it from `rdi-renderer`'s player mesh as a texture shown through `slint::Image`. Round corners in the shader, not with `clip`.

File pickers need a native dialog; prefer the `rfd` crate on the Rust side, returning a path to the service. Confirm that `ui-service` needs no AWT dialogs.

Modpack changes that touch server-side logic must run `modpackServiceTest` from `server/master`, per the root `AGENTS.md`. The port should not need any.

## 8. U5: switch

- Parity checklist across all screens, signed off by the owner, listing the cut features.
- Real-Mac and real-GPU Linux tests, including IME and game launch.
- Packaging: native binary, `dxcompiler.dll` and `LICENSE-LLVM.txt`, Slint GPL notices, Kotlin service jars per platform in the existing manifests. The updater starts the new launcher.
- Keep a fallback to the Compose launcher for at least one release.
- Only after that does the owner decide what happens to the Compose code. Archive, never delete.

Packaging details get their own plan at this point. **Stop at GU5** before anything reaches players.

## 9. Gates

| Gate | Contains | Owner sees |
|---|---|---|
| **GU1** | U1 | Criteria table, CI results, a build to test IME, window and list |
| **GU2** | U2 | Real game launch, service failure behavior, memory and startup numbers |
| **GU3** | U3 | Component gallery |
| **GU4a-e** | U4 batches | Side-by-side screenshots, per-screen checklist |
| **GU5** | U5 | Parity sign-off and the packaging plan |

Keep summaries short: numbers, what was verified, what was not, and the decisions needed.

## 10. Decisions reserved for the owner

- Every push.
- Paid NoMac time.
- Dropping a pass criterion or cutting any more features.
- Keeping or replacing Slint if U1 finds a blocker.
- Resuming 3D scene work (see `M1-M2-PLAN.md`, sections 3 and 5, which remain valid for it).
- Switching players to the new launcher.
