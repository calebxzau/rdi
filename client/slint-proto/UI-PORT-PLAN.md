# UI port plan: Compose launcher to Slint

Written 2026-11-05 for an agent with no prior context. This replaces M2-M4 in `M1-M2-PLAN.md`; M1 is finished and committed. The owner reviews at every gate.
`/tmp` is a tmpfs, so this repository copy is the one to keep up to date.

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

### U1 pass criteria

Each result is **pass, fail or not verified**, with evidence.

| # | Criterion | Requirement |
|---|---|---|
| 1 | Form | Validation, submit and keyboard work; owner confirms IME |
| 2 | List and menu | On the 780M, 60Hz display, focused, in every one of 3 runs each: 1000-row list over the held scene frame, and wheel scrolling on the real menu over the live scene. Long frames (interval over 25ms, a missed vsync) ≤ 1%, delivered FPS ≥ 58, frame CPU p95 ≤ 16.7ms, no steady memory growth |
| 3 | Panel and modal | Alignment within 1 physical pixel at all scales, during animation |
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

For each screen: move logic to `ui-logic`, expose it through the service, write the Slint screen, then test its error, empty and loading states. Each batch is one gate (GU4a-d) with side-by-side Compose and Slint screenshots, and a checklist per screen.

| Batch | Screens |
|---|---|
| **a. Account** | `RegisterScreen`, `ResetPasswordScreen`, `SettingScreen`, `PlayerInfoScreen` |
| **b. Hosts (房间)** | `MenuScreen`, `HostInfoScreen` + `HostInfoPanes` (no file manager), `HostNewCreateScreen`, `HostMembersScreen`, `HostBackendScreen`, `HostWorldScreen`, `HostRollbackScreen`, `HostSaveImportScreen`, `McPlayScreen` |
| **c. Modpacks** | `ModpackPlazaScreen`, `ModpackInfoScreen`, `ModpackVersionInfoScreen`, `ModpackVersionEditScreen`, `ModpackVersionBaseWorldManageScreen`, `ModpackUploadScreen`, `ModpackUploaderManageScreen`, `LocalModpackInstallScreen`, `ModpackLocalAdvanceOptionScreen`, `LocalModpackManager` |
| **d. The rest** | `MailScreen`, `MailDetailScreen`, `FriendsScreen`, `ModCatalogScreen`, `ModCatalogInfoScreen`, `ResourceListScreen`, `ResourceInfoScreen`, `BaseWorldListScreen`, `BaseWorldUploadScreen`, `WardrobeScreen`, `SkinPreviewScreen` |

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
| **GU4a-d** | U4 batches | Side-by-side screenshots, per-screen checklist |
| **GU5** | U5 | Parity sign-off and the packaging plan |

Keep summaries short: numbers, what was verified, what was not, and the decisions needed.

## 10. Decisions reserved for the owner

- Every push.
- Paid NoMac time.
- Dropping a pass criterion or cutting any more features.
- Keeping or replacing Slint if U1 finds a blocker.
- Resuming 3D scene work (see `M1-M2-PLAN.md`, sections 3 and 5, which remain valid for it).
- Switching players to the new launcher.
