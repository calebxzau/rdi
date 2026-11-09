# GJ1: Rust login and server requests

Implementation2026-11-09. Owner approved D1-D5 before edits. GJ1 owner review pending;
J2 has not started. GU4b0 IME/caret/visual sign-off remains independent.

## Delivered

Rust signs in via `/player/login`, handles server envelopes/authenticated reads and
shares its proxy-aware HTTP client with image downloads. Missing room `playable`
defaults to true. The login page reads saved-account metadata without starting Java;
after successful login Rust atomically saves the server account then starts Java and
hands over the sanitized account/JWT through protocol v2 `session.set`.

Saved account JSON remains Compose-compatible and preserves unknown top-level and
per-account fields. Invalid JSON shows empty history, is archived before replacement,
and I/O/write failures remain observable. One `RDI_DATA_DIR` determines both processes'
working data. Kotlin `LocalCredentials.parse` is extracted without changing `read` behavior.

Removed service methods: login/login.accounts/login.saved/data.get/hostList.*.
Original LoginService source and tests are byte-identical to HEAD under
`DEL/j1-login-20261109/client/ui/ui-service/...`. Root `.gitignore` excludes DEL;
the reviewer must explicitly include those archived files if committing the removal.
No files were permanently deleted. Nothing staged, committed or pushed.
The owner authorized stopping the preview for builds; it had already exited when checked.

## Automated Windows validation

- `cargo test --workspace`: renderer61 + library5 + app35 pass;1 pre-existing GPU capture test ignored.
- `cargo clippy --workspace --all-targets -- -D warnings`: pass.
- `cargo build --release`: pass.
- Kotlin ui-logic15/15 and ui-service20/20 pass, no skips. ui-service count21->20:
  archived3 LoginService tests, added2 SessionService tests.
- client-core237 tests,4 failures, no skips: added2 shared fixture tests, both pass.
  Failures match the fresh235-test baseline exactly: LocalModpackScannerTest,
  ModpackUploadRuntimeGuardTest, ModpackArchiveReaderTest (old Fabric rejection
  expectations), MediaProcGameClasspathTest.
- `:compileKotlin` and `:ui-service:installDist` pass. Combined Gradle command exits
  nonzero solely for the same client-core failures; it is not a fully green command.
  Used `--continue` so Compose compile/installDist still ran after baseline failures.
- No IntelliJ MCP available; used Windows pwsh/module-local gradlew.bat. No Compose
  MainKt was running before builds. No WSL Rust/Gradle builds used.
- Rust HTTP tests cover the response table, authorization/version header, connection
  refusal/timeout and proxy branches. Credential tests cover absent/empty/corrupt
  files, missing account/defaults, ordering, server values, field preservation and
  write failure. Kotlin/Rust both consume credentials/hardware/protocol fixtures.

Logs remain ignored locally: `evidence/j1-{baseline,kotlin,rust-test,clippy,release}.log`.

## Release acceptance

`tools/j1-live.ps1` completed successfully using the final release, official server,
native mouse/keyboard (clipboard paste), no credential environment variables and an
isolated `RDI_DATA_DIR`. Final local evidence: `live-1794210864753/`.

- Empty login page had no service process anywhere. Wrong password showed the server
  error and allowed retry. Correct typed input +Enter opened the real account/menu;
  service then existed. Server requests for player info, my rooms, list pages,
  online players and skins returned200; menu loaded96 rooms. Screenshot shows real
  account name/head and an enabled room tile. Exact Compose room-by-room parity was
  not interactively compared and remains part of owner review.
- 整合包 tab loaded without service errors. This proves `session.set` opened the service
  gate, not that the token works: the tab reads local data. Only the real game check proves
  the token. Restart auto-login and auto-login-disabled saved prefill/button passed.
- `lastPlayHost` and an unknown JSON field survived login. Killing the service
  produced a new service process through login; the pack tab worked again.
- Owner credentials SHA256 and timestamp matched before/after. Final evidence,
  controls and service logs scanned for the test password, reference JWT and JWT
  patterns: zero matches. Only the intentionally isolated credential store is
  excluded from that scan. No secrets included in scan output.

Windows hardware collection total300/399/450ms; WMI35/59/80ms across three processes.
Only `gpus[0].vram` was zero (AMD integrated GPU dedicated-memory value); other required
fields were populated. No timeout; `spec` was sent. macOS/Linux were not run.

Release first frame745ms (initial native scale2); login observed usable by5645ms,
which includes the script's fixed5s startup wait and is only an upper bound, not
precise input-readiness latency. Focused menu protocol: one warmup then two
60s visible/30s minimized/10s restored cycles at1280x720 scale1, actual foreground
verified for both measured endpoints. UI working set421.39/421.24MiB; separate
service146.95/179.75MiB. These are samples, exclude VRAM and do not establish a
memory improvement or growth trend.
Earlier attempts are not final acceptance: one native-input run timed out; the next
revealed WMI deserializing into Value was unsupported and a scan sharing violation.
Fixed WMI decoding to maps and closed the app before scanning shared log handles.
A focus-invalid memory sample from that earlier build is excluded.

## Review fixes

The reviewer found these after the numbers above were recorded. All are fixed, uncommitted.

- `config.toml`: Kotlin writes `usr = null` and `proxyConfig = null`, which is not valid TOML.
  Rust rejected the whole file and went direct, including on the owner's real config. The
  earlier live logs show this warning. Rust now drops those lines before parsing.
- System proxy: reqwest was built without `system-proxy`, so only environment variables were
  read. The feature is on; `windows-registry` is now in the dependency tree.
- Non-Windows build: `hwspec.rs` called `InstanceDescriptor::default()`, absent from wgpu30.
- A failed saved-account write now removes its temp file, which holds the password.

Validation after the fixes, on the final release:

- `cargo test --workspace`: renderer61 + library5 + app38 pass,1 GPU test ignored. New tests:
  null values, a request through a proxy using `protocol/fixtures/config_kotlin.toml`, temp cleanup.
- Clippy and `cargo build --release`: pass.
- `J1FixturesTest` 3/3 pass, including the fixture matching Kotlin's encoder output exactly, ignoring surrounding whitespace.
  Full client-core was not rerun: only a test changed in Kotlin, no main code.
- `tools/j1-live.ps1 -SkipMemory` passed, evidence `live-1794230442855/`: all earlier checks plus
  zero `config.toml` warnings. An earlier attempt, `live-1794230304104/`, timed out because the
  window never got keyboard focus and no login request was sent; it ran beside a Linux compile.
  The rerun without that compile passed. Memory was not remeasured.
- Linux: `cargo check --bin rdi-slint-proto` passes on a scratch copy with the Linux toolchain and
  a metadata-only `pkg-config`. The old call fails there, so this covers the fix. This is
  type-checking only; nothing was linked or run on Linux.
- macOS: not compiled. Cross-checking from Windows stops at the `aws-lc-sys` build script.
- A real system proxy was not exercised, because that needs changing Windows settings.

## Accepted J1 gaps

Primary-route Rust and node-routed Kotlin may disagree until J5. RDI_PROTO_SERVER
moves Rust only. Saved passwords remain plaintext; no token refresh or HTTP cache.
macOS/Linux runtime and hardware collection are untested.

## Owner checks

See owner-checklist.md: real game join using the handed-over token, interactive
Compose/Slint account exchange and acceptance of empty hardware fields remain owner checks.
