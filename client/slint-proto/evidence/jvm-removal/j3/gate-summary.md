# GJ3 gate summary

Status 2026-11-10: implemented; Windows automated checks pass except the four
unchanged client-core baseline failures. Isolated release MCP run completed in
`live-1794296628066/`; remaining real-game/online/minimized checks are on the owner
checklist. Stop at GJ3. No commit; no J4.

## Changes

- Rust `LazyService`: stopped/starting/running generations shared by client clones;
  concurrent first calls share their start and its failure. Start order is handshake,
  session identity check, optional game.restore, game.subscribe, tasks.subscribe, action.
- Login/menu, packs/options require no running child. Missing runtime does not block
  sign-in. First play/import uses existing busy indicators and Chinese errors.
- Rust proposes shutdown after60 continuous idle seconds, including inactive windows;
  Kotlin decides inline based on games/preparing/tasks/installer/play/pending state.
  A shutdown excludes requests until its result, so later actions restart if needed.
- Crashes show a dismissible error without reauthentication. Live process records
  trigger immediate restore. Import/task/console return to packs; queued calls are
  discarded, never replayed. Finished history/logs disappear on idle stop (D2).
- Logout stops the child before Rust atomically clears one account's Microsoft sessions.
  Missing file/account writes nothing; damaged files remain untouched with an error;
  other accounts/unknown fields survive. Compose code is unchanged.
- Protocol v3, conditional shutdown fixtures, removal of session.clear, shared
  msa_sessions fixture read by Kotlin's actual MsaSessionStore.

## Automated results (Windows)

| Check | Result |
|---|---|
| cargo test --workspace | bin54 (+3 Microsoft-store tests), lib11 (+6 lazy lifecycle tests), renderer63 pass/1 ignored |
| cargo clippy --workspace --all-targets -- -D warnings | pass |
| cargo build --release | pass; preview-run additionally builds/runs release with MCP |
| ui-service:test | 25 pass:24 baseline minus1 session.clear, plus1 refused shutdown and1 storage parity |
| client-core:test | 238 tests,4 known failures unchanged: LocalModpackScannerTest, ModpackUploadRuntimeGuardTest, ModpackArchiveReaderTest (Fabric expectations), MediaProcGameClasspathTest |
| ui-service:installDist | pass (separate invocation after baseline failure stopped combined Gradle run) |
| cargo-zigbuild check --release aarch64-apple-darwin | pass;2 existing updater registry constants unused warnings |
| cargo-zigbuild check --release x86_64-unknown-linux-gnu | pass; same2 warnings |

IntelliJ MCP endpoint refused connection; Gradle used module-local Windows wrapper.
Logs remain local next to this file. Rust pipe tests cover shared first start/failure,
prelude order, accepted/refused stop, queued action waiting for stop, logout without spawn.
Existing connection test covers no replay on EOF. Kotlin dispatcher tests cover both
shutdown outcomes; installer ownership checks are added to PackServiceTest.

## Measurements

Baseline process spawn to session.set reply:775.57ms. On-demand starts:929ms,
627ms,812ms, all below5s; retain approved60s timer.

At idle menu before: launcher455630848 bytes (434.5MiB), Java153092096 bytes
(146.0MiB). After: launcher485797888 bytes (463.3MiB), no Java child, total
about117.2MiB lower in these samples. These are single working-set samples, not a
controlled memory benchmark; graphics allocation/warmup can vary.

## Isolated MCP live results

All interaction used Slint UI MCP with cargo preview-run; no native mouse scripts.
Data directory is isolated; the owner credential file was never read/modified for the run.
Test credentials came from the designated test-account file; password/JWT-pattern scan of evidence/logs passed (credential stores excluded).

| Check | Result |
|---|---|
| Sign in and wait at menu | real server login/menu200; no Java child |
| Missing runtime command | sign-in/menu succeeded; play displayed Chinese无法准备游玩，请检查启动器安装; no child |
| First task page | starts child; session reply929ms; no reauthentication |
| Stay on task page over60s | child stays; hide page then exits after60s without error (08:04:58 UTC) |
| Use after idle stop | installer opens a new child; session reply627ms |
| Import screen over60s | child stays; create empty1.21.1 NeoForge pack task reaches100%; pack manifest written |
| Leave task screen | child exits silently after60s (08:12:53 UTC) |
| Next task action | starts again (812ms) |
| Kill child with no game | dismissible启动服务意外退出; stays signed in, no automatic restart; next action can start |
| Options without child | new sign-in, advanced options saved `-Dj3.live=true`; SQLite row verified, no Java child |
| Logout with child stopped | no child starts; current account entry removed, other fake account and unknown field retained |
| Pack list refresh | manifest read; tile observed once UI refreshed after background/crash. See limitations below |

## Limits / owner acceptance

No real game join, game-running crash recovery, online Microsoft login or Compose UI
run is claimed. Empty pack creation exercises the import/task path but is not a long
archive download. Focus/minimization require owner checks. Real click-to-room-reply
latency was not measured. Pack-list tile redraw while unfocused must be checked by
the owner (MCP does not focus the native window); list read itself succeeds.

The MCP build emitted debug-info warnings during element traversal despite
preview-run's flags; accessible-role handles and screenshots still allowed testing.
No changes to packaging or J4/J5 implementations.

Final source checks: `git diff --check` passed; no staging/commit. Isolated credential
file is retained only in ignored live data; never include that folder in a commit.

## GJ3 code review, 2026-11-10

Fixed in `src/packs.rs`, then `cargo test --workspace` (bin54, lib11, renderer63) and
clippy `-D warnings` pass again. No live rerun after these two fixes.

- Leaving the import screen before `installer.open` had answered dropped the close.
  Kotlin kept its installer, refused every idle proposal, and the process never
  stopped until the import screen was opened and closed again. A close now follows a
  queued or in-flight open (`installer_opening`).
- A crash sent every screen to the pack list, including the options page (unsaved
  edits lost) and the room tab. Only the import, task and console screens leave now.

Open for the owner, unchanged:

- Clicking a room sets the menu's `loading` flag, so both tile grids are replaced by
  正在加载… until `host.start` answers, on every click and not only the first start.
- If recovery after a crash during a game fails to start, the menu keeps showing the
  game as running and refuses logout until a later action starts the process.
