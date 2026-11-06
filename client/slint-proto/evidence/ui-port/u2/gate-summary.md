# GU2 — Kotlin service and main flow

2026-11-06. **GU2 passed with owner review on 2026-11-06**, after the concurrency follow-up
below. No U3 work, updater switch or publishing was performed.

## Acceptance

| Check | Result | Evidence |
|---|---|---|
| Real login and room list from Slint through Kotlin | Pass | Real test account, menu loaded; native screenshots `menu-before.png`, `menu-after-restart.png` |
| Start the room from its Slint card and reach in-game | Pass | `u2-game.ps1`; room `28257的1.21`, pack `1.21原版`1.0.0; RMCP player confirmed overworld/survival/20 health; `game-ingame-rmcp.png` |
| Stop and duplicate-version behavior | Pass | Second card click displayed confirmation and kept one game process; cancel worked; stop button terminated the game, including after service recovery |
| Close launcher while game is running | Pass | Native close request kept both UI/game alive and showed confirmation; confirming closed service/game; repeated after service crash recovery |
| Kill service mid-session | Pass, with connection interruption | Menu and in-game cases tested. UI showed reconnect text and restarted the service. Existing game's PID/start time were verified and process management restored. Game disconnected when the local proxy died; RMCP reconnection succeeded. This is not seamless network recovery |
| Compose compilation and runtime | Pass | `final-validation.log`, `compose-runtime-build.log`, `compose-runtime.png`; window `RDI 6.7.9`, normal exit0 |
| Existing focused UI tests | Pass | 41 tests: room list14, resume7, progress5, window icon15; zero skips |
| New Kotlin module tests | Pass | ui-logic6, ui-service6; XML results, `protocol-final.log`; zero skips |
| Existing client-core tests | Baseline failures unchanged | Fresh baseline and final rerun both235 tests/4 failures; `baseline.log`, `final-client-core.log` |
| Rust tests and lint | Pass | Library2 + binary20; `rust-tests.log`; `clippy.log` clean with `-D warnings` |

The four client-core failures are LocalModpackScannerTest,
ModpackUploadRuntimeGuardTest and ModpackArchiveReaderTest (old Fabric rejection
expectations), plus MediaProcGameClasspathTest. They were present before moving
code. They were not hidden, excluded from client-core:test, or changed here.
The unrelated broken `:net:compileTestKotlin` was excluded as recorded in the
handoff. No full root UI test suite was run.

## Measurements

Windows780M, debug Rust build, scale2.0, monitor on. Foreground HWND was checked
for the focused sample. Working set is resident process memory, not a retained
heap measurement; GPU VRAM is not added.

| Process/stage | Result | Boundary |
|---|---|---|
| Kotlin service launch to protocol handshake | Median644ms; samples672/640/644ms | Three standalone launches after database initialization was added; excludes login/network requests |
| Slint first frame | 1017ms | Latest UI run, `ui-stdout.log`; single sample |
| Slint first live scene frame | 5300ms | Same run; single sample, no release-performance comparison |
| Focused Slint working set | 627.14MiB | `final-ui-smoke.txt`, one sample after menu loaded |
| Service working set beside that UI | 251.66MiB | Same sample; measured separately |

No steady-growth or performance-parity claim is made from these short runs.
The first foreground acquisition failed and that measurement was discarded;
the successful run used the existing P5 native focus helper and verified HWND.

## Delivered

- Compose-free ui-logic and ui-service modules. Existing Kotlin packages were
  retained. Login, launch sequence, session/console storage, room list models,
  gateway/ViewModel and game-window preparation are shared with Compose.
- 4-byte big-endian framed JSON, strict UTF-8/8MiB bound, initial handshake,
  full-state subscriptions, page-sized room snapshots and shared golden JSON
  fixtures used by both languages. A developer CLI is provided.
- Protocol stdout is reserved before runtime initialization; other output goes
  to a log. Child game/installer tools use their own PIPE streams. Database and
  launch-option runtime initialization are now explicit in the service.
- Rust uses updater JDK discovery without editing the updater, supports a JSON
  development command override, and performs menu API requests through Kotlin.
  Old direct HTTP client code is archived under DEL/u2-rust-api-20261106/.
- Crash recovery retains only non-secret game process identity in UI memory,
  validates PID plus start time, restores duplicate/stop/close management, and
  restarts the local game connection. Original stdout/console history from a
  dead service cannot be restored.
- Slint exposes running-game state for U3's scene/navigation policy. No new
  navigation or scene-freeze policy was added in U2.

## Local core JAR requested by owner

The first in-game run used the published core. That JAR's RMCP started after
entering the world and lacked screen_* tools. At the owner's explicit request,
subsequent tests used the local1.21.1 build, with startup-time RMCP screen_read
and screen_click; both were exercised and the room entered through screen_click.

Installed and build JAR SHA256:
`240bcf5e4b2f30a82832dd7ec240116970cd2c02f5384f8ae1fd7879fbcbee12`.

`RDI_SERVICE_CORE_JAR` is an explicit development-only override after normal
core synchronization. It preserves a differing existing target under a unique
repository DEL directory before copying, and is idempotent when the JAR already
matches. The final installed test JAR matches the local build. Normal runs do
not enable this override. No core source or public release was changed here.

## Review boundaries

- Installation and remaining launcher screens stay in U4. The owner installed
  this test pack through Compose before in-game validation.
- Runtime testing here is Windows-only. No new Mac/Linux game acceptance claim.
- Process management recovers after service failure; an active game's network
  connection still drops and needs reconnecting. Reconnect was verified.
- Tests left no launcher, service or test game process running. The test pack
  remains installed, with the owner-requested local core JAR.
- The shared updater discovery source has two pre-existing modulo-style lint
  warnings. The importing module locally allows that single stylistic lint;
  the updater source was kept unchanged.

## Owner review follow-up (2026-11-06)

Owner decisions after reviewing this summary:

- **Accepted limitation:** a launcher or service crash disconnects or ends the running
  game. The local game proxy stays inside the service process; no work is planned on this.
- **Requested change, implemented:** requests are handled concurrently on both sides.

What changed:

- Kotlin `RequestDispatcher` (ui-service) reads frames in order and handles each request
  in its own coroutine. All handlers resume on the single `runBlocking` thread, so session
  state is not locked. `handshake` and `shutdown` run inline; shutdown cancels requests
  still in flight without answering them. A failed protocol write still ends the service.
- A request without a positive integer `id` is logged and ignored instead of ending the
  service. A missing method or non-object `params` is answered with an error.
- Rust: `Client` shares `Arc<Service>` with no outer mutex. Before, each call held one
  lock for up to 60s, so requests queued and `take_events`/`has_exited` skipped while a
  request was in flight (no state updates or crash detection during a room start). The
  stream logic moved into a `Connection` that can be tested over OS pipes.
- No start mutex was added, contrary to the proposal. `McPlayStore.admitSession` already
  checks duplicates under the version lock and `synchronized(sessions)`, so two concurrent
  `host.start` calls for one pack cannot start two games; the second reports
  「该整合包已在运行」 through `game.state`. A mutex would not have closed that window,
  because admission runs asynchronously after `host.start` returns.

Validation (`concurrency-gradle.log`, `concurrency-service-smoke.txt`):

- Kotlin: ui-service 11 (new RequestDispatcherTest 5: slow request does not delay a
  later one, handshake ordering, shutdown cancellation without reply, invalid ids ignored,
  error codes/messages) and ui-logic 6; zero failures, zero skips.
- Rust: library 4 (new: slow request does not block a later one; stream end fails a
  waiting request) and binary 20; `cargo clippy --all-targets -D warnings` clean.
- Real service smoke with the test account: handshake, empty-login validation, real login,
  room list (1 room), EOF exit 0. Handshake 808ms and service working set 237.63MiB, from one
  cold sample, not comparable to the median above.
- Not rerun after this change: Slint UI in-game flow, crash recovery, Compose runtime.
  Compose code was not touched.

Owner decision: GU2 approved 2026-11-06. U3 has not started.
