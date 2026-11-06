# U2 handoff — 2026-11-06

**Superseded by [GU2 gate summary](gate-summary.md). U2 is ready for owner review.**
The implementation, real Windows in-game checks and Compose runtime checks are
complete. Stop at GU2; do not begin U3 without owner review.

The notes below preserve the investigation history; their remaining-work list
was resolved by the subsequent checks in the gate summary.

# U2 work in progress — 2026-11-06

**GU2 has not passed.** Implementation and Windows validation are in progress.
No changes were staged, committed, pushed or shipped. Owner changes were kept.

## Implemented

- Registered ui-logic and ui-service; neither depends on Compose.
- Extracted LoginController, McPlayController, McPlayStore, ConsoleState,
  HostListViewModel/gateway, room models/mapping, and GameWindowService.
  Existing packages retained. Compose consumes these through ui-logic.
- Compose observes session revisions; desktop icon encoding retains its existing
  Skia/AVIF path. Headless icon encoding uses ImageIO and existing AVIF decoder.
- Framed stdio protocol, 8MiB bound, shared Kotlin/Rust golden fixtures,
  handshake, subscriptions, paging, request timeouts and EOF shutdown.
- Kotlin stdout/stderr redirect occurs before business initialization. Game
  launcher uses ProcessBuilder PIPE streams, not inherited protocol pipes.
- Rust uses updater JDK discovery, a development JSON command override, and
  Kotlin-backed menu reads/login/start. Previous direct API implementation is
  archived in DEL/u2-rust-api-20261106/ with its original relative path.
- Rust restarts the failed service generation; pending requests fail, mutations
  are not replayed. UI shows a Chinese reconnect message. Scene stays loaded.
- Game state, stop action and close confirmation are exposed in Slint. Late
  process attachment after stop destroys that process; session IDs use UUIDv7.

## Verified

- Fresh baseline: client-core 235 tests, 4 failures: LocalModpackScannerTest,
  ModpackUploadRuntimeGuardTest, ModpackArchiveReaderTest (Fabric assertions),
  and MediaProcGameClasspathTest. baseline.log retains exact failures.
- Fresh UI baseline: HostListViewModelTest, HostListResumeTest,
  McPlayProgressTest passed.
- After extraction: Compose compile passed. 41 focused UI tests passed (14 room
  list + 7 resume + 5 progress + 15 window-icon tests).
- New module XML results: 3 login + 3 session/process + 2 framing tests passed,
  no skipped tests. validation.log and module build/test-results contain evidence.
- Windows Rust library tests: 2 passed; cargo build and cargo check passed.
- Real test-account login, room snapshot subscription and EOF exit0 passed.
- Latest standalone handshake: 251ms, working set182.95MiB at handshake; this is
  not the final focused memory sample.
- Native foreground handle checked in u2-ui-smoke.ps1. Latest focused UI working
  set459.71MiB; its service child266.18MiB. One sample, no memory trend claim.
- Killing service PID116760 restarted a new child, reauthenticated and reloaded
  menu data. Logs show reload approximately3.27s after service EOF. UI exit0.
  Screenshots: menu-before.png and menu-after-restart.png (local ignored files).
- Corrected omitted playable=true default: service encodes host defaults so the
  Rust menu no longer hides the test room.
- Corrected lifecycle distribution collision: metadata-only JetBrains relocation
  jar shares the implementation filename; service explicitly uses AndroidX's
  implementation and excludes the relocation desktop artifact.

## Remaining before GU2 acceptance

- The test account owns room `28257的1.21`, pack `1.21原版`. Real host.start
  returned NeedInstall. Owner was told this exact room/pack and asked to install
  through Compose, then close Compose. Installation UI is U4, not added here.
- Once installed, drive launch from Slint and verify in-game on Windows. Latest
  display check: primary1920x1080, secondary1440x900, no WinDisc display.
- Verify stopping and closing the launcher with a real game. Unit tests and UI
  implementation do not count as runtime acceptance.
- Verify Compose starts/runs after extraction; compile/tests alone are not this.
- Service crash recovery was tested with menu session, not with an active game.
  Active game process ownership/proxy behavior after service crash remains a risk
  requiring examination during real-game validation.
- Final service/UI startup and memory report, and GU2 gate-summary, remain due.

## Operational notes

- IntelliJ open project: C:/Users/calebxzhou/Documents/coding/rdi5/client/ui.
- Do not compile while Compose MainKt is running. ComposeHotReloadMcp servers
  alone are not launcher windows and do not block builds.
- Direct WSL pwsh interop still fails with UtilBindVsockAnyPort. IntelliJ Windows
  terminal can read the test credential file through wsl.exe into memory, never
  print it; clear environment variables after spawning the test process.
- RDI_PROTO_USR/PWD are removed from the Kotlin child's inherited environment;
  login sends them only through framed stdio.
- The partial failed service installation was archived under
  DEL/u2-partial-service-install-20261106/ before retry, not deleted.
