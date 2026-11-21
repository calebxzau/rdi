# JVM removal plan

Written 2026-11-09 for an agent with no prior context. Phase J1 is specified in full. Later phases are only outlined and must be designed before they start. The owner reviews at every gate. This repository copy is the one to keep up to date.

## 1. Direction

The Slint launcher currently runs a headless Kotlin process, `ui-service`, for all business logic. The owner decided on 2026-11-09 to remove that JVM from the launcher step by step, replacing the "Business logic stays in Kotlin" decision in `UI-PORT-PLAN.md`.

**In scope:** the launcher process no longer needs a JVM of its own.

**Not in scope:** Minecraft itself still runs on Java, so the launcher must keep providing a game runtime. `server/master` and `common/*` stay Kotlin. The Compose launcher keeps shipping until the final switch and must keep working.

### Approved order

| Phase | Moves to Rust | Kotlin code today | Status |
|---|---|---|---|
| **J1** | Login and server requests | `LoginService`, `PlayerService.login`, `RServer`, `data.get` | **Implemented; GJ1 review pending** |
| J2 | Read-only local data: installed pack list, pack options | `installedPacks.*`, `packOptions.*`, `ModpackService.getLocalPackDirs` | **Done; GJ2 passed 2026-11-10** |
| J3 | Start the JVM on demand, only for install and game launch | `src/api/mod.rs` | **Done; owner accepted GJ3 2026-11-10** |
| J4 | Tasks, downloads, local pack import | `tasks.*`, `installer.*`, `mod-catalog`, `client-store` | Design approved 2026-11-10; handoff `J4-PLAN.md`; GJ4a passed 2026-11-10; J4b partial implementation and validation recorded; parity work remains; GJ4b not passed |
| J5 | Game launch, local game proxy, Microsoft login, node routing | `mclaunch`, `host.start`, `game.*`, `packPlay.*`, `NodeRefreshCoordinator` | Not designed |
| J6 | Remove `ui-service`, package and switch | Updater and release manifests | Not designed |

J3 gives the first large memory gain: the service used about 250MiB working set in earlier measurements (GU3). Whether `mediaproc` and `anvilrw` are reachable from the Slint flow, and whether the local game proxy can reuse existing Rust code, is not yet checked. Answer both when designing J4 and J5.

**J6 to-do: saved account storage (owner agreed 2026-11-09).** Keep `local_credentials.json` until the Compose launcher is retired, because Compose reads it and Kotlin rejects a `data.db` schema version above its own. After the switch, import it once into `data.db`, passwords included. The owner decided not to use the OS credential manager, so passwords stay readable by anything that can read the data directory, as they are today. Rename the old file only after the import succeeds; if the import fails, keep the file and log the error.

### Relation to the UI port

The UI port in `UI-PORT-PLAN.md` continues after J1. The owner's GU4b0 sign-off (IME, caret, visuals) is still pending and J1 does not replace it. The rest of batch b waits for both GU4b0 and GJ1. From J1 onward, a new screen that only talks to the server calls Rust directly and adds no new service method.

---

## 2. Phase J1: login and server requests

### Goal

Rust signs in, stores saved accounts and makes all RDI server data requests itself. The JVM starts only after sign-in and receives the session from Rust. The player sees no change except a login page that appears without waiting for the JVM.

### Out of scope

- Starting the JVM on demand after login (J3). The service still starts on every sign-in.
- Node routing and `server-entry` (J5).
- Registration, password reset, logout, account switching. They stay stubs or absent, as they are now.
- UI changes. Keep `ui/login.slint` and the login behavior as they are.
- Compose launcher behavior. It keeps its own Kotlin login path.
- Encrypting saved passwords, token refresh, HTTP caching. See "Known gaps".

### Before writing code

Read `AGENTS.md`, `client/ui/AGENTS.md`, sections 1-3 of `UI-PORT-PLAN.md`, `README.md` here and `evidence/ui-port/u4b0/gate-summary.md`.

Then send the owner a short summary in Mandarin covering decisions D1-D5 below and anything you would change, and wait for approval. Then build and stop at gate **GJ1**. Do not start J2.

### Ground rules

- Speak Mandarin with the owner. Do not push. Do not stage or commit: report at the gate, and the reviewer commits after the owner's review.
- Never permanently delete project files. Move a removed file to `DEL/<new-unique-dir>/<original-relative-path>`.
- Text files use LF, except `*.bat` and `*.cmd`.
- User-facing Chinese has no spaces next to numbers or Latin letters. Say 房间, not container.
- Failures must be observable: return `Result`, and either log with the error attached or propagate. No silent fallback.
- **Never write a password or JWT to a log, an event, an error message, a Slint property, evidence or a `Debug` output.** Do not derive `Debug` on any type holding them.
- **Never touch the owner's real `client/ui/run/local_credentials.json` in a test or script.** Use an isolated data directory (see `RDI_DATA_DIR`).
- The repository is public. Test credentials are in `~/.config/rdi/.test_usr_pwd` in WSL (line 1 user, line 2 password). Do not print them.

### Owner decisions to confirm (D1-D5)

| # | Decision | Reason |
|---|---|---|
| D1 | Rust reads and writes `local_credentials.json`, including saved passwords. Saved passwords still never reach Slint properties: the UI keeps using a saved ID, and Rust looks up the password. | Reverses the GU4b0 rule "passwords stay in Kotlin", which cannot hold once Rust signs in. Keeps the part that still matters. |
| D2 | Login sends a best-effort hardware spec collected in Rust. Fields Rust cannot get are empty. If collection fails or takes over 3s, login goes ahead without `spec` and logs a warning. | The server treats `spec` as optional, but without it no `AuthLog` row is written. Windows should stay close to what OSHI reports; macOS and Linux may lose memory, display size and video memory data. |
| D3 | Rust uses the fixed primary server address until J5. | Today the service starts on the same default route and only changes it after a node refresh. See "Known gaps". |
| D4 | The login page no longer starts the JVM. The service starts after sign-in succeeds. | Removes the wait for Java before the login page is usable, and is a first step toward J3. |
| D5 | Protocol version becomes 2. New method `session.set`. `login`, `login.accounts`, `login.saved`, `data.get` and `hostList.*` are removed from the service. | Dead methods would otherwise hide mistakes. Rust and the service always ship together. |

### Code facts

Verified 2026-11-09 on `dev`. Re-check before relying on them.

**Current flow.** `LoginUi::start` (`src/login.rs`) spawns the JVM through `Client::new`, then calls `login.accounts`. `submit` calls `login` or `login.saved`. On success `App::load_menu` (`src/app.rs`) loads data through `Client::get`, which sends `data.get` to the service (`ui-service/.../Main.kt:113`). If the service exits, `App::tick` calls `login()` again, which restarts the login flow.

**Call sites of `Client::get`:** `src/app.rs` (`/player/{uid}/info`, `/host/my`, `/host/list/{page}`, `/host/online-player-ids`) and `src/images.rs` (`/player/unique-skin-list`, `/player/{uid}/info`). `api.http` is already Rust's image client. `src/ui_proof.rs:70` calls `Client::new(base)?.login(...)`.

**Login request.** `POST {base}/player/login` with a JSON body of string values: `usr`, `pwd`, `spec`. `spec` is a JSON document encoded as a string. Kotlin also deflates the request body; plain JSON is fine, because the server reads JSON bodies through `paramNull` and the earlier Rust client (`DEL/u2-rust-api-20261106/client/slint-proto/src/api/mod.rs`, useful as a starting point, but it used `/player/jwt`) posted plain JSON successfully.

**Login response.** The token is in the response header `jwt`. The body is the standard envelope with an `RAccount` in `data`. Use `/player/login`, not `/player/jwt`: only the former returns the account and writes `AuthLog`.

**Envelope** (`common/model/.../Response.kt`): `code` (0 is success), `msg`, `data` (optional). Authenticated requests send `Authorization: Bearer <jwt>`.

**`RAccount`** (`common/model/.../RAccount.kt`): `_id` as a hex string, `name`, `pwd`, `qq` are required; `score`, `msid`, `inviter`, `allowUploadModpack`, `cloth` have defaults. The `jwt` field is `@Transient`.

**Trap: server JSON omits default values.** The server encodes with `serdesJson`, which leaves out fields equal to their default. The service currently re-encodes `/host/my` and `/host/list/*` with `encodeDefaults = true`, so Rust never sees this. `Host.BriefVo.playable` defaults to **true**, but `HostBrief.playable` in `src/api/model.rs` uses `#[serde(default)]`, which gives **false**. Reading the server directly would make rooms look unplayable. The other endpoints are passed through unchanged by the service, so they already see omitted defaults.

**Data directory.** Kotlin uses `user.dir` as `RDIClient.DIR`. Rust sets the service working directory to `client/ui/run` (`src/api/mod.rs:60`). `tools/u4b0-live.ps1` isolates its run with `RDI_SERVICE_COMMAND` and `-Duser.dir`. **Once Rust reads credentials, that script would use the owner's real file unless updated.**

**Proxy.** `DynamicProxySelector` (`common/net/.../RHttpClient.kt:282`) reads `proxyConfig` from `config.toml` in the data directory: `enabled`, `systemProxy`, `host`, `port`, `usr`, `pwd`. A missing section or `enabled = false` means no proxy, even if the system has one. reqwest uses system proxies by default, so **the Rust client must opt out**.

**Version.** `Const.VERSION_NUMBER` comes from the jar manifest, whose value is `app` in `client/ui/gradle/libs.versions.toml` (currently `6.7.9`).

**Session use in Kotlin.** `loggedAccount` supplies the JWT and identity for downloads, uploads and game launch (`MinecraftLauncherFactory.kt:71-73` passes the JWT as the game's access token). Nothing on the service path reads `loggedAccount.pwd`.

**`RDI_PROTO_SERVER`.** Rust passes it as `-Drdi.server.url`, but `RServer.now` only uses that when `rdi.debug` is true, which Rust does not set. So the variable does not move the service today. After J1 it moves Rust only. Do not test sign-in against a local master through this variable unless you also solve this; run acceptance against the official server with the test account, as earlier gates did.

### Design

```text
Before: Slint -> Rust -> stdio -> Kotlin service -> server
After:  Slint -> Rust -> server
                 Rust -> stdio -> Kotlin service (install, launch, local packs)
                         session.set hands over the signed-in account
```

New Rust modules, all under `client/slint-proto/src`:

| File | Responsibility |
|---|---|
| `paths.rs` | `data_dir()`: `RDI_DATA_DIR` if set, otherwise `client/ui/run` as today. The only place that decides this. |
| `api/server.rs` | `ServerApi`: HTTP client, proxy policy, envelope handling, `login`, authenticated `get`. |
| `credentials.rs` | Read, merge and atomically write `local_credentials.json`. |
| `hwspec.rs` | Best-effort hardware spec as JSON. |

`Client` in `src/api/mod.rs` becomes the pairing of a signed-in `ServerApi` and a running `Service`:

```rust
pub struct Client {
    pub http: reqwest::Client,   // same client as ServerApi
    server: ServerApi,           // holds base URL and JWT
    service: Arc<Service>,
    uid: String,
}
```

- `Client::get` calls `ServerApi`, so the call sites in `app.rs` and `images.rs` stay unchanged.
- `Client::call`, `take_events` and `has_exited` still go to the service.
- `Client::login` and `Client::login_saved` are removed.
- New `Client::connect(server, session)`: spawn the service on a blocking thread, handshake, send `session.set`, return the `Client`.

### HTTP behavior

Match these, and test each one.

| Case | Result |
|---|---|
| Connection failure or timeout | `无法连接服务器 请检查网络连接`, with the cause logged using `e.without_url()` |
| Non-2xx with a JSON body that has a non-blank `msg` | That `msg` |
| Other non-2xx | `服务器请求失败: {status} {reason}` |
| 2xx without a JSON `Content-Type` | `服务器响应格式错误: {content type or 无Content-Type}` |
| 2xx with `code != 0` | `msg` |
| `/host/my` or `/host/list/*` with no `data` | Empty list |
| Other request with no `data` when data is required | `服务器未返回数据` |
| Wrong data shape | `服务器数据格式不兼容` |
| Login without `data` | `msg` |
| Login without a non-blank `jwt` header | `登录凭据缺失` |

Client settings: connect timeout 10s, request timeout 60s, user agent `rdi-launcher/<version>`, redirects followed, gzip and deflate responses accepted. Log only path and status, as `tracing::info!(path, status, ...)`.

Proxy policy, as a pure function of the parsed config so it can be unit tested:

- No section or `enabled = false`: `.no_proxy()`.
- `enabled` and `systemProxy`: reqwest's default system proxy behavior.
- `enabled`, not `systemProxy`, `host` non-blank and `port > 0`: `Proxy::all("http://host:port")`, with `basic_auth` if `usr` is non-blank.
- `enabled`, not `systemProxy`, otherwise: `.no_proxy()`.
- Unreadable or invalid `config.toml`: log a warning and use no proxy. **Do not rewrite the file.** Kotlin rewrites it; Rust only reads.
- Kotlin writes absent optional values as a bare `null` (`usr = null`, `proxyConfig = null`), which is not valid TOML. Rust drops those lines before parsing; `protocol/fixtures/config_kotlin.toml` holds the exact format and is checked from both sides.

The same client serves images, so `images.rs` picks up the policy with no further change. Add the `toml` crate for parsing.

### `local_credentials.json`

The Compose launcher and the service read this file too, so its format must stay compatible.

```json
{
  "loginInfos": {
    "<account _id hex>": { "qq": "10001", "name": "玩家", "pwd": "...", "lastLoggedTime": 1700000000000 }
  },
  "lastPlayHost": { "id": "...", "name": "..." },
  "autoLoginDisabled": false
}
```

Kotlin omits default values, so reading must accept: a missing `loginInfos`, `lastPlayHost` or `autoLoginDisabled`, a missing `name` (default `RDI玩家`) and a missing `lastLoggedTime` (default 0).

Rules:

- Keep unknown top-level and per-account fields when rewriting, using `#[serde(flatten)]`.
- Missing or empty file: treat as no accounts. Do not create it until a successful login.
- Listing: sort by `lastLoggedTime` descending. `lastId` is the first entry. `autoLoginId` is the first entry unless `autoLoginDisabled`.
- After a successful login, re-read the file, then set `loginInfos[account._id] = { qq: account.qq, name: account.name, pwd: account.pwd, lastLoggedTime: now_ms }` and `autoLoginDisabled = false`. These are the server's values, as in `PlayerService.login`, not what the player typed.
- If that write fails, login fails with the error shown, as in Kotlin today.
- Write atomically: temp file in the same directory, then rename. Retry the rename up to 3 times, 50ms apart, because the Compose launcher may have the file open on Windows.
- **Corrupt file:** log the error and show an empty history. Typed login still works. On the next write, first rename the file to `local_credentials.json.corrupt-<unix ms>`, then write a fresh one. Do not silently overwrite, unlike Kotlin.
- Saved-account submit re-reads the file and finds the ID. If it is gone: `历史账号已不存在，请重新输入账号和密码`.

### Hardware spec

JSON must decode as `common/model/.../HwSpec.kt`, whose fields are all required, or the server rejects login with `硬件信息格式错误`:

```text
model, os: string
cpu: { name, cores, threads, frequency (Hz) }
gpus: [{ name, vram }]
mems: [{ size, type, speed }]
disk: [{ name, size, model }]
display: [{ name, height, width }]
videoMode: [string]
```

No single crate covers these fields. Use `sysinfo` (0.40.1 when checked) as the base and `wmi` (0.18.4, Windows-only target dependency) for detail. `winit` and `windows` are already in the dependency tree; `wmi` accepts the locked `windows` 0.62. Pin exact versions.

| Field | Windows | macOS and Linux |
|---|---|---|
| `model` | `sysinfo` `Product`, or WMI `Win32_ComputerSystem` (Manufacturer, Model) | `sysinfo` `Product`; may be empty |
| `os` | `sysinfo` | `sysinfo` |
| `cpu` | `sysinfo` | `sysinfo` |
| `gpus` | Name from WMI `Win32_VideoController`, `vram` from DXGI `DedicatedVideoMemory` | Name from `wgpu`, `vram` 0 |
| `mems` | WMI `Win32_PhysicalMemory` (Capacity, SMBIOSMemoryType, Speed) | Empty, or one entry with the `sysinfo` total |
| `disk` | WMI `Win32_DiskDrive` (name, Size, Model) | `sysinfo`, `model` empty |
| `display` | WMI `root\wmi` `WmiMonitorID` (name), `WmiMonitorBasicDisplayParams` (size in cm) | `winit` name, size 0 |
| `videoMode` | Each monitor's current `winit` size and refresh rate, formatted `{width}x{height}@{refresh}`, distinct, as Kotlin's `getDisplayModes`. Not `video_modes()`, which lists every supported mode; the server keeps only the first ten. | Same |

Traps:

- WMI `AdapterRAM` is 32-bit and tops out near 4GB, and `wgpu::AdapterInfo` has no memory field. Use DXGI for `vram`.
- `sysinfo` reports CPU frequency in MHz and `HwSpec` wants Hz. It may also be the current rather than the rated frequency; if WMI `Win32_Processor.MaxClockSpeed` is available, prefer it on Windows.
- `winit` monitor data must be read on the UI thread, through `ui.window().with_winit_window`. Capture it there, then merge it with the blocking-thread data.
- `SMBIOSMemoryType` is a numeric code and `HwSpec.Memory.type` is text such as `DDR4`. Map the common codes and use an empty string for others. `speed` is in Hz.
- The time a WMI query takes has not been measured. Measure it on the owner's machine and report it, because a query over the 3s limit means login goes out without `spec`.

Do not use `heim`, `machine-info` or `edid-rs` (unmaintained), `hardware-query` (very low usage), or `nvml-wrapper` (NVIDIA only).

Use an empty string, 0 or an empty list for anything unavailable. Collect once on a blocking thread, with a 3s timeout, and reuse the result. Compress repeated spaces in the CPU name, as Kotlin does. Tell the owner at the gate which fields end up empty, per platform you tested.

### Protocol change

Set `VERSION = 2` in both `ui-service/.../Protocol.kt` and `src/protocol.rs`. Add:

```text
session.set { account: <RAccount JSON>, jwt: string } -> { uid: string }
```

- Allowed before sign-in. Other business methods stay blocked until it succeeds.
- Rust sends the server's `data` object with `pwd` replaced by an empty string. The key stays because `RAccount` requires it.
- Kotlin decodes with `serdesJson`, sets `account.jwt = jwt`, assigns `loggedAccount`, sets `loggedIn`, and returns the hex ID. Blank `jwt` or an invalid account fails with `登录信息无效`. Do not include the JWT in errors or logs.
- Put this in a small `SessionService` class in `calebxzau.rdi.service`, so it can be tested without the stdio loop.

Remove from the service: `login`, `login.accounts`, `login.saved`, `data.get`, `hostList.*`, and what only they use (`hostList`, `hostPage`, `hostState`, the `LoginService` field). Move `LoginService.kt` and `LoginServiceTest.kt` to `DEL/`. Remove `hostList.state` from the event allowlist in `src/service.rs:56`. Do **not** change `LoginController`, `HostListViewModel`, `PlayerService.login` or `LocalCredentials.read`: Compose uses them.

### Steps

1. **Foundations.** `paths.rs`. In `build.rs`, read `app` from `client/ui/gradle/libs.versions.toml`, emit `cargo:rustc-env=RDI_APP_VERSION`, add `rerun-if-changed`, and fail the build if the value is missing. The service working directory comes from `data_dir()`, which is what sets Kotlin's `user.dir`. A script using `RDI_SERVICE_COMMAND` with `-Duser.dir` must set `RDI_DATA_DIR` to the same path.
2. **`credentials.rs`** with tests. Add `protocol/fixtures/local_credentials.json`, using fake values.
3. **`api/server.rs`** with tests against a local mock server. Add `wiremock` as an exact-pinned dev dependency, or use a small `tokio` listener if it conflicts. No test may reach the real server.
4. **`hwspec.rs`** with a test that the output has every required field, plus `protocol/fixtures/hwspec.json`.
5. **Model fix.** `HostBrief.playable` defaults to `true`. Update `host_optional_and_unknown_status` in `src/api/model.rs`, which asserts the old default. Add a test with the field absent.
6. **Kotlin.** `SessionService`, `session.set`, version 2. Add a public `LocalCredentials.parse(text)` used by `read`, with no behavior change, and a `client-core` test that parses `local_credentials.json`. Add a test that decodes `hwspec.json` with `serdesJson` as `HwSpec`. Update `messages.json` and `ProtocolTest`.
7. **`Client` refactor** as in Design. Update `src/ui_proof.rs`.
8. **`LoginUi`.**
   - `start` reads credentials on a blocking thread and spawns no service.
   - Version comes from `env!("RDI_APP_VERSION")`.
   - `submit` runs: login, then write credentials, then `Client::connect`.
   - Keep the blank check `未填写完整`, single submission, the `RDI_PROTO_USR`/`RDI_PROTO_PWD` override and auto-login order.
   - A service start failure after sign-in stays on the login page and shows the error. The next submit retries.
   - Update the comment at the top of `login.rs` and the golden-response test.
9. **Remove the dead service methods** listed above.
10. **Scripts and docs.**
    - Update `tools/u4b0-live.ps1` to set `RDI_DATA_DIR` to its isolated directory.
    - Create `tools/j1-live.ps1` for the acceptance run.
    - Update the `README.md` environment table (add `RDI_DATA_DIR`, correct `RDI_PROTO_SERVER`), `protocol/README.md` and `PROGRESS.md`.
    - Set the J1 status in this file.

### Validation

Run on Windows through `pwsh.exe`. Do not use the WSL toolchain against `/mnt/c`.

From `client/slint-proto`:

```powershell
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo build --release
```

From `client/ui`, redirecting output to a log. Use IntelliJ MCP first (`executeInShell: true`), with `pwsh.exe` as the fallback:

```powershell
.\gradlew.bat :ui-logic:test :ui-service:test :client-core:test :compileKotlin :ui-service:installDist --no-daemon
```

Baselines before J1: `ui-logic` 15 pass; `ui-service` 21 pass; `client-core` 235 tests with the same 4 known failures (three old Fabric rejection expectations and `MediaProcGameClasspathTest`). Report the new counts and explain every change.

### Acceptance (gate GJ1)

Automated, all required:

1. The commands above pass. `client-core` has no new failures, and Compose still compiles.
2. Rust tests cover every row of the HTTP table, the proxy policy, every credentials rule and `playable` absent.
3. The shared fixtures are read by both Rust and Kotlin tests.

`tools/j1-live.ps1`: release build, real server, real mouse and keyboard, isolated `RDI_DATA_DIR`, no credential environment variables:

4. Empty start shows the login page, and **no `calebxzau.rdi.service.MainKt` process exists** while it is shown.
5. A wrong password shows the server's message and allows a retry.
6. Correct typed credentials plus Enter reach the menu. The service process now exists. The account name, head, my rooms and room list are real, and rooms show as playable where the Compose launcher shows them as playable.
7. The 整合包 tab loads through the service, proving `session.set` worked.
8. Restart signs in automatically from the saved account. Set `autoLoginDisabled` in the isolated file, restart, and confirm the account is prefilled and the login button works.
9. Add `lastPlayHost` and an unknown field to the isolated file before signing in, and confirm both survive.
10. Kill the service on the menu. The launcher returns through the existing login flow and the 整合包 tab works again.
11. The owner's real `client/ui/run/local_credentials.json` has the same timestamp and hash before and after the run.
12. Scan all logs, control files and evidence from the run for the test password and the JWT. Report the result. Do not store the secrets in the scan output.

Owner checklist:

- [ ] A real game started from a room in the Slint launcher joins normally. Game launch uses the JWT from `session.set`. The monitor must be on; if the game exits with `Failed to find a primary monitor`, do not debug the game.
- [ ] The Compose launcher reads the account written by the Slint launcher, and the Slint launcher reads one written by Compose.
- [ ] Decide whether the hardware spec fields left empty are acceptable.

Report for information, not as criteria: time from launch to a usable login page, and UI and service working set on the menu, in release, using the focused protocol from earlier gates.

Write `evidence/jvm-removal/j1/gate-summary.md` and `owner-checklist.md`; `client/slint-proto/.gitignore` keeps only `*.md` from `evidence/`. Then stop. Report in Mandarin what passed, what did not and what was not run.

### Known gaps (accepted for J1)

Record these. Do not fix them in J1.

- **Routing.** Rust stays on the primary address. The service may move to a backup API host after a node refresh during game start (`ModpackService`, `GameRecovery`), so a player on a backup route could reach the two sides through different hosts until J5. The two sides could also disagree on node selection.
- **`RDI_PROTO_SERVER`** moves Rust but not the service, as described in Code facts.
- **Saved passwords** remain plaintext in `local_credentials.json`, as they are today.
- **Token expiry.** There is no refresh, as today. A request after expiry shows the server's error.
- **HTTP cache.** Rust has no equivalent of Kotlin's OkHttp cache. List requests may hit the server more often.

### Gotchas

- `cargo` can reuse a stale `rdi-renderer` build and report `Grid has no field named clip` although the source has it. Touch `crates/rdi-renderer/src/glass/mod.rs` and rebuild.
- The owner may be running `cargo preview-run`, which locks `target/release/rdi-slint-proto.exe`. Ask before stopping it. Edits made from WSL may not trigger its reload until the file's timestamp is updated from Windows.
- Do not compile `client/ui` while the owner's Compose hot reload app is running, and never run master and client Gradle at the same time. Do not run `packageUiRelease`.
- Do not use the Gradle daemon under a WSL `pwsh.exe` pipe: it keeps the pipe open until timeout.
- Kotlin `runBlocking` tests must be declared `: Unit =`, or JUnit skips them.
- `client/ui` tests that reference `calebxzau.rdi.client.lgr` load `AppConfig` at class init. Use a local logger.
- tracing logs contain ANSI codes around `data=`. Strip them before matching with a regex in scripts.
- The root `.gitignore` has `**/bin/`. Check `git add -n` if you add anything under a `bin` directory.

## 3. Phase J2: installed packs and pack options

Designed 2026-11-10. The owner approved decisions D1-D4 below as recommended.

### Goal

Rust lists installed packs, opens a pack's directory, and reads and saves pack options itself. The service still starts at sign-in (J3 changes that), so the player sees no speed or memory gain yet. J2 removes the reason the 整合包 tab needs the service.

### Out of scope

- Import (`installer.*`), tasks, play (`packPlay.*`), console. They stay in Kotlin until J4/J5.
- Creating or migrating `data.db`. Kotlin keeps owning the schema until J6.
- Compose launcher behavior.

### Decisions (approved 2026-11-10)

- **D1** The whole options page moves, including save, although the phase table said read-only.
- **D2** SQLite through `rusqlite` with the `bundled` feature. It compiles SQLite from C, so it adds a second C build to the macOS cross-check that `aws-lc-sys` already blocks.
- **D3** If `POST modpack/infos` fails, local-only packs still show and the tab shows 读取整合包信息失败 with refresh. Kotlin today logs the error and silently hides every RDI pack. RDI packs the server no longer knows are skipped with a log line, as today.
- **D4** Rust never creates or migrates `data.db`. If the file is missing, `application_id` is not `0x524449`, or `user_version` is not 5, the options page shows an error and saving is disabled.

### Code facts

- Kotlin list: `ModpackService.getLocalPackDirs` (`client-core/.../ModpackService.kt`), `scanLocalPackRefs` (`^([0-9a-fA-F]{24})_(.+)$`), `LocalModpackStore.scan` (`local_*` minus `.installing`, metadata in `rdi-local-pack.json`), `localPackCreateTime` (creation time, else last modified), sorted newest first. The versions directory is `<data dir>/mc/versions`.
- Snapshot fields the UI already reads: `PackSnapshots.kt` `packSnapshot`. `mcVersion` and `modLoader` are Kotlin enum names.
- Options: `ModpackOptionViewModel` (draft, `buildRecord`, defaults, `DEFAULT_JDWP_PARAM`), `ModpackLaunchOptions.sq`, `SettingsService.validateMemory`, `validateJdkExecutablePath` and `resolveJavaExecutable` (`PlatformUtils.kt`), `parseModpackJvmParams` (`ModpackJvmArgs.kt`). Game launch rereads the row through `ModpackLaunchOptionsService.loadSnapshot`, so a Rust save is used at the next start.
- `globalJavaPath` is the Java 25 runtime the service runs on. Rust finds the same path through `java_discovery`.
- `PackService.selected()` looks packs up in the list that `installedPacks.*` loaded. Without that call `packPlay.open` fails with 找不到这个整合包.
- `tasks.watch` emits `installedPacks.state` when a task ends.
- `data.db` is opened by Slint, `ui-service` and possibly Compose at once. Kotlin uses the default rollback journal.

### Design

1. `src/local_packs.rs`: scan, `modpack/infos` through the J1 client, merge, sort, and the same JSON shape as `packSnapshot`. Opening a directory uses the path already in the list.
2. `src/pack_options.rs`: open `data.db` per operation with `busy_timeout`, check D4, read, upsert or delete the row exactly as `buildRecord` decides, and port every validation message unchanged. JDK validation runs `java -XshowSettings:properties -version` off the UI thread.
3. `src/packs.rs`: stop calling `installedPacks.*`, `packOptions.*`; refresh the list on a terminal `task.state` and after `installer.install` returns a run.
4. Kotlin `ui-service`: `selected()` finds the pack by `versionId` on demand; remove `installedPacks.*` and `packOptions.*` handlers and their tests; stop the list emission in `tasks.watch`. Update `protocol/fixtures/messages.json`.

### Validation and gate GJ2

- Shared fixtures checked from both sides: JVM parameter cases, memory cases, a versions directory with RDI, local, `.installing` and broken entries.
- `data.db` both ways: a Kotlin test writes rows that Rust reads, and a row Rust writes passes `loadSnapshot`.
- `ui-service` tests for the on-demand lookup.
- Release run with isolated `RDI_DATA_DIR`: list matches Compose; save maximum memory, start a game and confirm `-Xmx`; Compose shows options saved by Slint and the reverse; list refreshes after an install; D3 and D4 errors appear when forced.
- Write `evidence/jvm-removal/j2/gate-summary.md` and `owner-checklist.md`, then stop. Do not start J3.

## 4. Status

- 2026-11-09: owner approved D1-D5. J1 implemented; Windows Rust/Kotlin validation and isolated release acceptance recorded in `evidence/jvm-removal/j1/gate-summary.md`. GJ1 awaits owner checklist/review. No J2 work started. Known routing, plaintext storage, token expiry and HTTP-cache gaps remain accepted and unchanged.
- 2026-11-09: GJ1 review fixes applied and revalidated (`config.toml` null values, system proxy, non-Windows build, temp file cleanup). The owner confirmed joining a room, playable rooms and the system proxy, and directed the commit. Still open: Compose and Slint reading each other's accounts, and the hardware spec fields. J2 has not started.
- 2026-11-10: J2 designed (section 3); owner approved D1-D4 as recommended. Implementation not started.
- 2026-11-10: owner confirmed Compose and Slint read each other's saved accounts. GJ1 has no open checks.
- 2026-11-10: J2 implemented. `ui-service` tests 24/24, Rust 51+5, clippy clean, zigbuild checks for macOS and Linux pass (Linux needed `fontique` `fontconfig-dlopen`). The `packOptions.state` fixture stays in `messages.json` as screenshot data only. Live run pending; see `evidence/jvm-removal/j2/gate-summary.md`.
- 2026-11-10: isolated live run through the slint-ui MCP passed (list, options save and reload, D4, refresh after install, auto sign-in). Found and fixed: tile updates arriving while the window was inactive were dropped (`App::tick`, `refresh_pending`). Owner checks: `evidence/jvm-removal/j2/owner-checklist.md`.
- 2026-11-10: owner confirmed every GJ2 check (list matches Compose, `-Xmx8192M` in game, options shared both ways, 目录, background refresh) and directed the commit. J3 has not started.


## Phase J3: on-demand launcher process

Owner approved D1-D4 on 2026-11-10; implementation authorized via `/tmp/jvm-removal-j3-plan.md`.
Login/menu, installed packs and pack options run without a launcher JVM. First use
shares one process start, session identity check and restore/game/tasks prelude.
Failed requests are never replayed. A crash shows a dismissible error and returns
import/task/console screens to packs; live game records trigger immediate recovery.

- D1: 60 continuous idle seconds; Rust proposes, Kotlin checks games/preparation,
  unfinished tasks, installer, play job and pending play inline before accepting.
  Inactive/minimized windows keep ticking. Startup above5s is reported at GJ3.
- D2: finished task history/console logs disappear on stop. Import/task/console
  screens and the play dialog prevent the timer from starting.
- D3: Rust stops the child and clears only the account's `msa_sessions.json` entry
  at logout. Preserve unknown fields/other accounts; invalid files stay untouched.
  Compose is unchanged. `session.clear` is removed.
- D4: protocol v3 with conditional shutdown replies `{stopped:true/false}`.

Validation and measurements: `evidence/jvm-removal/j3/gate-summary.md`.
Owner acceptance: `evidence/jvm-removal/j3/owner-checklist.md`.
Stop at GJ3 for owner review. Do not commit or start J4.

- 2026-11-10: J3 implemented; automated checks pass, isolated MCP validation done; real-game/online/minimized checks pending. Stop at GJ3 owner review.
- 2026-11-10: review fixed two cases in `src/packs.rs` (close after a pending `installer.open`; a crash leaves only the import, task and console screens). Owner accepted GJ3, including the 正在加载… wait on a room click, and directed the commit. The real-game items on the owner checklist are not recorded as run. J4 design starts next.


## Phase J4: tasks and local pack import

Drafted and approved 2026-11-10. Not implemented. The implementation handoff is
`J4-PLAN.md`; where the two differ, the owner's answers below and `J4-PLAN.md` win.

### What J4 is

After J3 the service starts for four things: the import screen, the task screen,
play, and the console. J4 moves the first two to Rust. After J4 the JVM starts
only for play and the console, which are J5.

What the Slint launcher reaches today, and so what J4 has to replace:

| Kotlin | Lines | Role |
|---|---|---|
| `Task2Manager` through `ClientTaskManager` | small | Task list, progress tree, run ids |
| `LocalModpackScanner` | 170 | Finds packs of other launchers from the Minecraft folders recorded in `data.db` |
| `LocalModpackInstallViewModel` | 419 | Import screen state: candidates, editor, archive preview, optional files |
| `ModpackArchive.kt` | 998 | Reads CurseForge and Modrinth pack archives, validates paths and URLs |
| `ModpackArchiveContentResolver` | 148 | Names and icons for the archive's mods |
| `LocalModpackInstaller` | 329 | Empty pack, copy of a game folder, or archive: staging folder, then one move |
| `mod-catalog` | 3204, a small part used | `getFiles`, `getMods`, `matchFiles`, `resolveDownload`, mirror preference, Chinese names from `mod_catalog.db` |
| `ClientContentStore` | 1206, a part used | Download cache in `<data dir>/dlc`, files named by digest, linked into the pack |
| `FileDownloader` | common/net | Five attempts, several sources, ranged download in 1MiB pieces |

Corrections to the overview row, found while reading the code:

- `mc-install` is not part of this. Import only calls `writeMinecraftOptions`.
  Installing Minecraft and the loader happens when a game starts, so it belongs to J5.
- `pack-proc` is not reached by the Slint import.
- Installing an RDI pack is not in the Slint launcher at all: a room whose pack is
  missing answers 请先在Compose启动器中安装该整合包. See D1.
- Kotlin also submits two startup tasks, the old-cache migration and the download
  cache cleanup. Only Compose runs them; J4 does not port them.

### Design

1. **Tasks in Rust.** A small task manager holds the task list for the launcher's
   lifetime and produces the same `tasks.state` and `task.state` shapes the UI
   adapter in `src/packs.rs` already reads. Import tasks are single leaves with a
   message and a fraction. Task history then survives a service stop, which removes
   half of J3's D2; console logs are still lost until J5.
2. **Import screen state in Rust.** A Rust state machine replaces the view model and
   produces the same `installer.state` shape, so the Slint files and most of
   `packs.rs` stay as they are. The `installer.*` calls become direct function calls.
3. **Scanner.** Reads the recorded Minecraft folders from `data.db`, read only, as J2
   does for pack options. A missing or foreign database gives an empty list, not an
   error. Loader detection follows `detectLocalModLoader`, checked by shared fixtures.
4. **Installer.** Same staging folder, same exclusion lists, same 20GB limit, same
   `rdi-local-pack.json`, same unique-name rule, one final move. A cancelled or failed
   task leaves no folder behind.
5. **Archives.** `zip` crate, with the same fallback for Chinese file names as
   `openChineseZip`. Every path and URL check in `ModpackArchive.kt` is ported with
   its Chinese message. Rejection cases come from shared fixtures read by both sides.
6. **Catalog, the part that import uses.** CurseForge files and projects by id,
   Modrinth files by SHA-1, embedded jars by fingerprint, download resolution, the
   mirror preference from `config.toml` with fallback to the origin. Chinese names
   come from `mod_catalog.db`, opened read only.
7. **Download cache.** Same folder and file names as Kotlin, so a file downloaded by
   either launcher is reused by the other. Digest check before commit, temp file plus
   rename, hard link into the pack with copy as fallback.
8. **Service.** `installer.*`, `tasks.*` and the installer part of the idle check
   leave `ui-service`. Protocol version 5 after J4a version 4. The idle rule loses the import and task
   screens; a running Rust task no longer keeps the JVM alive.
9. **Logout.** Still refused while a task runs, now decided from the Rust task list.

### Owner decisions, 2026-11-10

| | Question | Decision |
|---|---|---|
| D1 | Scope | Only what Slint reaches today. RDI pack install is a later phase, written in Rust from the start. |
| D2 | Gates | Two: **J4a** tasks, scanner, empty pack and game folder copy, no network. **J4b** downloader, download cache, catalog, archives. |
| D3 | `mod-catalog` | Port all of it, not only what import uses. This replaces design item 6. |
| D4 | `mod_catalog.db` | In development read it from `client/ui/mod-catalog/src/main/resources`; shipping is J6. Without it, platform names are shown. |
| D5 | Download cache | Shared with Compose, same folder and format. |
| D6 | Downloader | Port the ranged, parallel download. |
| D7 | Cancel | Add a cancel button to the task screen. |

### Risks

- The archive reader is where a mistake can write outside the pack folder. Path
  checks are ported one to one and tested with hostile archives from shared fixtures.
- Two launchers writing one cache at the same time have no lock between processes,
  today as well. Temp file plus rename keeps each file whole.
- A machine that never ran Compose has no `data.db`, so the scanner finds nothing.
  Picking an archive or a folder still works. Creating the database is J6.
- The CurseForge key is in the Kotlin source as bytes; Rust needs the same key.

### Validation

Shared fixtures for scanner manifests, archive previews and rejections, catalog
answers and installer results, read by Kotlin and Rust. Rust tests, clippy, release
build, cross-checks for macOS and Linux, `ui-service` tests. Live run through the
slint-ui MCP with an isolated data directory: no Java child during any import; empty
pack, folder copy, one CurseForge and one Modrinth archive; a pack imported by Rust
starts in game; Compose lists it and reuses the cache.

- 2026-11-10: J4b Rust downloader/cache/catalog/archive implementation and protocol v5 cleanup added. Validation and remaining acceptance gaps are recorded in `evidence/jvm-removal/j4b/`. GJ4b is not passed; no commit and no J5.
