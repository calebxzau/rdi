# Launcher MCP plan: one MCP address for the launcher and the game

Drafted 2026-11-25. Status: **approved by the owner on 2026-11-25; implement M1 to M4 without
stopping between them.** Replaces the Compose plan `client/ui/launcher-mcp-plan.md` (archived
under `DEL/20261125-launcher-mcp-compose-plan/`).

Read first: the root `AGENTS.md`, `UI-PORT-PLAN.md` section 2 (ground rules) and "Slint lessons
already paid for", `J5-PLAN.md` section 5 (play functions), `J4C-PLAN.md` (pack install).

Written for an agent with no prior context. User-facing text says 房间, 整合包 and 游戏, never
service, proxy, container or port. English for 房间 in code and tool names is `host`.

## 1. Goal

A player clicks one icon in the launcher title bar. The launcher starts an MCP server and copies
a prompt. The player pastes the prompt into a local agent (Claude Code, Cursor and similar), and
the agent can then:

- see the player's hosts, modpacks, tasks and running games,
- enter a host, installing its modpack first when needed,
- operate the running game through the in-game RMCP tools (`screen_*`, `command`, screenshot
  and the rest),
- upload a modpack (M4). The Slint launcher has no upload screen by owner decision
  (2026-11-22, `UI-PORT-PLAN.md` section 7), so this tool is the only way to upload after the
  switch.

The player configures the agent **once**: the launcher address and token stay the same across
restarts, and game tools are reached through the launcher, whatever port the game picked.

## 2. Owner decisions (2026-11-25)

| # | Decision |
|---|---|
| D1 | **No confirmation dialogs for now.** Clicking the icon is the player's consent. Because of this, tools that destroy data or affect other players (delete a host, kick or transfer, delete a modpack) are **not** part of M1 to M4. Do not add them without asking the owner. |
| D2 | **The in-game RMCP stays as it is**: loopback only, no token, no Origin check (the 2026-10-26 decision stands). Do not change `mc/rmcp` or the game mods in this plan. |
| D3 | **M4 is a release gate.** The Slint launcher does not replace Compose until modpack upload works through the MCP. |
| D4 | The launcher MCP itself does check a Bearer token, `Origin` and `Host` (section 5). It holds the player's account, so it does not copy RMCP's open access. |

## 3. Code facts

Checked on 2026-11-25 at commit `bc81ec5d`.

### Launcher (Rust, `client/slint-proto`)

- The UI-free modules were written for this: `play` (`play/mod.rs`, `play/room.rs`), `rooms`
  (`rooms/requests.rs`, `rooms/backend.rs`), `pack_install`, `route`, `save_import`, `account`,
  `mc_install`. Their headers say "no Slint types, so the launcher MCP can call it".
- Entering a host from the UI is `App::start_room` (`src/app.rs`, near line 800): Java check,
  `room_pack` (is the pack installed), build `RoomDeps` off the UI thread through
  `PackUi::room_deps` (`src/play_ui.rs:330`), then `play::room::start`. Results are
  `Admitted::Started(session id)` or `Admitted::Duplicate`.
- Pack state: `pack_install::state(deps, modpack_id, version)` returns `Missing`,
  `Installing(run id)` or `Installed`; `pack_install::start` returns the task run id. Both carry
  a comment that the MCP tool will call them.
- Tasks: `tasks::Manager::list`, `snapshot(id)`, `cancel(id)`.
- Games: `play::Sessions` holds every game: `views()`, `lines(id)`, `stop(id, force)`. Game
  output reaches it line by line in `spawn_and_watch` (`src/play/mod.rs`, near line 652).
- My hosts: `api.get("/host/my")` (`src/app.rs`, near line 1109); details:
  `rooms::requests::detail`.
- Background work talks to the UI thread through `App`'s `mpsc` `Event` channel, drained in
  `App::tick`. `App` is `Rc<RefCell<..>>` and must not be touched from tokio threads.
- No HTTP server exists yet. `hyper 1.12.0`, `hyper-util 0.1.21`, `http-body-util 0.1.5` and
  `httparse` are already in `Cargo.lock` through `reqwest`; tokio already has `net`.
- Clipboard: Slint has no Rust clipboard API. The project copies through a hidden read-only
  `TextInput` and `copy()` (`ui/account.slint:57-64`, `ui/plaza.slint:61`).
- Title bar: `ui/title_bar.slint:32-33` holds the 控制台 and 任务 `GlassButton`s; the new icon
  goes next to them.
- `cargo preview-run` (`.cargo/config.toml`) builds with Slint's own `slint/mcp` feature on port
  55566. That is a developer tool for inspecting the UI tree. It is **not** the player MCP and
  must not ship; do not confuse the two or reuse its port.

### Game (Kotlin, `mc/rmcp`, read only for this plan)

- `StandardMcpServer.kt`: fusionauth HTTP server on `127.0.0.1`, `POST /mcp` only, JSON
  responses (no SSE), `Mcp-Session-Id` header that may be empty on requests. A request without
  that header is accepted, so the launcher can send `tools/list` and `tools/call` without
  `initialize`.
- Port: saved in `<version dir>/standard_mcp_port.txt`, with a random port when the saved one is
  busy. Do not rely on the file: two games of one version share it.
- At startup the game logs `AI MCP URL: http://127.0.0.1:<port>/mcp` (1.21.1
  `RDIMain.kt:91`, 1.20.1 Forge `RDIMain.kt:56`, 1.20.1 Fabric `RDIFabricClient.kt:23`). The
  launcher already receives this line in `spawn_and_watch`.
- Only games started with the RDI core have RMCP. Local packs (CurseForge, Modrinth and so on)
  and GTNH 1.7.10 do not.
- Protocol reference to port: `standard/JsonRpc.kt` (85 lines) and `standard/McpProtocol.kt`
  (127 lines), protocol version `2025-11-25`.

### Modpack upload (Kotlin today, no Rust yet)

| Kotlin source | Lines | Role |
|---|---|---|
| `client/ui/.../viewmodel/ModpackUploadViewModel.kt` | 1749 | Draft validation, preflight, client and server pack preparation, mod side resolution, the test run, submit |
| `client/pack-proc` (6 files) | 2561 | `ModpackProcessor`: load a local pack, build the upload archive |
| `client/modpack-test` (2 files) | 907 | `ModpackTestSession`: starts the pack to prove it loads (`CLIENT_TEST_SUCCESS_MARKER`) |
| `client/ui/client-core/.../ModpackUploadService.kt`, `ModpackUploadApi.kt`, `ModpackChunkedUploader.kt`, `ParallelModpackUploader.kt` | 1078 | Chunked parallel upload, new modpack or new version |

About 6,300 lines. This is the largest part of the plan.

## 4. Design

New module `src/mcp/` (existing crate, so no new package rule applies):

| File | Content |
|---|---|
| `mod.rs` | `Controller`: start, stop, state (`Stopped`, `Running { port }`, `Failed(message)`), saved port and token, prompt text. No Slint types. |
| `server.rs` | hyper HTTP/1 server on `127.0.0.1`. `POST /mcp` only; the checks of section 5. |
| `protocol.rs` | JSON-RPC 2.0 parsing and errors; `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`. A port of the Kotlin files named above. Notifications answer `202` with no body. `GET /mcp` answers `405` (no SSE stream). |
| `tools.rs` | Tool table: name, description, JSON schema, handler. Launcher tools. |
| `game.rs` | Game MCP addresses per session and request forwarding. |
| `bridge.rs` | Requests from tool handlers to the UI thread, answered through a `tokio::sync::oneshot`. |
| `tests.rs` | Unit tests. |

`src/mcp_ui.rs` and `ui/` changes hold the icon, the menu and the copy step.

Dependencies: enable `hyper` (`server`, `http1`), `hyper-util` (`tokio`) and `http-body-util`
with the versions already locked. No `axum`, no MCP SDK crate: the protocol surface used here
is small, and the Kotlin implementation is the reference. Pin versions the way `Cargo.toml`
already does.

### Reaching launcher state

Tool handlers run on the tokio runtime. Anything owned by `App` (signed-in `Client`, room deps
builder, window size) is requested through `bridge.rs`: the handler sends a request with a
`oneshot::Sender` over the `App` event channel, `App::tick` answers it. Check whether `tick`
still runs while the window is unfocused or minimized (`paused`, `refresh_pending`); an agent
works while the player looks at another window, so bridge requests must be served then too.
Shared handles that are already `Send + Sync + Clone` (`play::Sessions`, `tasks::Manager`) go
straight into the tool context.

Do not duplicate `App::start_room`. Move its sequence (Java check, pack state, deps, start) into
a UI-free function that returns an enum, and call it from both the UI and `play_host`.

### Game tools

- When a game line contains `AI MCP URL: `, store the address on that session in
  `play::Sessions`. Accept it only if it is exactly `http://127.0.0.1:<port>/mcp`, and only the
  **first** match per session: later lines can come from chat or mods and must not redirect the
  launcher. Clear it when the session ends.
- Two fixed tools instead of merging the game's tools into `tools/list`:
  - `game_tools { session? }`: forwards `tools/list` and returns the game's tool names,
    descriptions and input schemas.
  - `game_call { tool, arguments, session? }`: forwards `tools/call` and returns the game's
    `result` unchanged, including image content and `isError`.
  Reason: most agents read `tools/list` once when they connect, the game starts later, there is
  no SSE stream to announce changes, and 1.20.1 and 1.21.1 have different tools.
- `session` may be omitted when exactly one running game has an address. Otherwise return an
  error that lists the sessions (id, title). A game with no address returns
  "这个游戏不支持AI操作" (local packs, GTNH) or "游戏还在启动，请稍后再试".
- Forward with a 30 s timeout through a client that uses **no system proxy**.

### Launcher tools

Results are JSON text content. Errors are tool results with `isError: true` and a Chinese
message the agent can show the player. Not signed in: "请先在启动器登录".

| Milestone | Tool | Does |
|---|---|---|
| M1 | `get_status` | Launcher version, signed-in player name, running games (id, title, state, whether game tools are available), active tasks |
| M2 | `list_hosts` | The player's hosts (`/host/my`) |
| M2 | `get_host { host_id }` | Host details, including modpack name and version and whether it is installed |
| M2 | `play_host { host_id, allow_duplicate? }` | Starts the host and the game. Returns one of: `started` with the session id, `duplicate` (the same pack already runs; call again with `allow_duplicate`), `need_install` (pack name and version), `installing` with the task id, `java_missing`, or an error for an unsupported pack |
| M2 | `install_host_modpack { host_id }` | Starts the pack install; returns the task id |
| M2 | `list_modpacks` | Installed modpacks, read only |
| M2 | `list_tasks`, `get_task { id }`, `cancel_task { id }` | Launcher tasks and progress |
| M2 | `get_game_log { session?, tail? }` | Last lines of a game's console; default 100, maximum 500 |
| M2 | `stop_game { session, force? }` | Stops a game |
| M3 | `game_tools`, `game_call` | See above |
| M4 | upload tools | See section 6, M4 |

Tool descriptions are in English with the Chinese player term in brackets where it helps the
agent talk to the player, for example "host (房间)". Never return the JWT, the MCP token,
passwords or Microsoft tokens. There is no generic HTTP or file tool.

### Player experience

Icon: a `GlassButton` beside 控制台 and 任务 in `ui/title_bar.slint`, with a green dot while
running. Pick a glyph that exists in the bundled icon font and check it in a screenshot.

| State | Click |
|---|---|
| Stopped | Start, copy the prompt, show "AI助手已开启，提示词已复制" |
| Running | Menu: status line, 复制提示词, 关闭AI助手 |
| Failed | Show the reason; copy nothing |

- Stopped again after every launcher restart; the player clicks to start it.
- Port and token are saved in a file of their own under `paths::data_dir()` and reused. If the
  saved port is busy, pick a new one, save it and tell the player the prompt changed.
- Copying uses the hidden `TextInput` technique. The copy must happen after the server is up,
  because the prompt contains the port.
- The token is shown nowhere in the UI except inside the copied prompt.

Prompt template (fill in port and token):

```text
请把RDI启动器添加为MCP服务器，然后帮我操作它。
地址：http://127.0.0.1:{port}/mcp
传输方式：Streamable HTTP
请求头：Authorization: Bearer {token}
如果需要重启客户端才能生效，请告诉我。
连接后先调用get_status。进入游戏后用game_tools查看游戏内可用的操作。
不要把token发到别处。
```

## 5. Security

- Bind `127.0.0.1` only.
- Every request needs `Authorization: Bearer <token>`. The token is 32 random bytes from the OS
  random source, hex encoded. Compare in constant time. Wrong or missing: `401`.
- Reject any request whose `Origin` header is present and is not `http://127.0.0.1:<port>` or
  `http://localhost:<port>`: `403`. Agents send no `Origin`; browsers always do.
- Reject a `Host` header other than `127.0.0.1:<port>` or `localhost:<port>` (DNS rebinding).
- Require `Content-Type: application/json`. Limit the body to 4 MiB.
- Never log the token or request bodies that may hold it. Follow the existing log masking.
- No CORS headers are sent.
- Master's own permission checks stay the only authority for what the account may do.

## 6. Milestones

Commit after each milestone (stage explicit paths, no co-author trailer). Do not stop for
review between milestones. Stop only at GM4, or earlier when a decision in section 8 blocks.

### M1: server, protocol, icon

1. `mcp::protocol` and `mcp::server` with the section 5 checks; `get_status`.
2. `Controller`: saved port and token, start and stop, prompt text.
3. Title bar icon, menu, copy.
4. Tests: missing and wrong token, bad `Origin`, bad `Host`, wrong content type, oversized
   body, `initialize`, `tools/list`, unknown tool, notification returns `202`, prompt text,
   port fallback when the saved port is busy.
5. Live: start through the slint-ui MCP with `cargo preview-run` and an isolated `RDI_DATA_DIR`,
   then call the player MCP over HTTP (`pwsh.exe` `Invoke-WebRequest` from WSL) and, if
   possible, add it to a real Claude Code with the copied prompt.

### M2: host and task tools

1. Extract the UI-free host start from `App::start_room`; the UI keeps its behavior.
2. `bridge.rs`, then the M2 tools in the table.
3. Tests with a fake server API and fake task manager: every `play_host` outcome, not signed
   in, tail limits, unknown ids.
4. Live against the local master with the test account (recipes in `U4C1-PLAN.md` and
   `J4C-PLAN.md`): list, install, enter a host, read the log, stop. Entering the game needs the
   monitor on.

### M3: game tools

1. Address capture in `play::Sessions`, with the first-match and exact-format rules.
2. `game_tools` and `game_call`.
3. Tests: address parsing (valid, other host, other path, second line ignored, cleared on exit),
   session selection (none, one, many), forwarding against a local fake MCP server including an
   image result and a game-side error.
4. Live on 1.21.1 and 1.20.1: `game_tools`, `screen_read`, a screenshot and a harmless
   `command` through the launcher address.

### M4: modpack upload (release gate, D3)

Start by reading the four Kotlin sources in section 3 and add a "M4 design" section to this
file: the steps of the Kotlin flow in order, which become Rust, and the tool shapes. Then
implement. Guidance:

- The Kotlin order of steps is the specification, as in J5. Port behavior, not class layout.
  Do not fix Kotlin bugs on the way; note them in the plan.
- Split into at least: reading and validating a local pack folder or archive, preflight against
  master, building the upload archive, chunked upload with resume and retry, and a launcher
  task that reports progress through `tasks::Manager`.
- Expected tools: `upload_modpack_check { path, ... }` (validate and preflight, no upload),
  `upload_modpack { path, name, version, categories, info, icon_url?, source_url?,
  update_modpack_id? }` returning a task id, and `list_my_modpacks` (uploaded by the player).
  The agent follows progress with `get_task`. Adjust the shapes to what the Kotlin flow needs.
- The upload archive must be byte-compatible with what master accepts today. Compare a Rust
  archive with a Kotlin one for the same pack (file list and manifest), following the Compose
  cross-check recipe in `J5C-PLAN.md`.
- Server-side modpack logic should not change. If it must, run `modpackServiceTest` from
  `server/master` as the root `AGENTS.md` requires.
- The test run (`client/modpack-test`) is the open question E1 in section 8. Until the owner
  answers, port it last and keep the upload usable behind the same rule Kotlin applies
  (`IGNORE_MODPACK_TEST` in `common/misc/.../Const.kt` shows when Kotlin skips it).

GM4: report to the owner with the test numbers, what was verified live and what was not, and a
short recipe to try it with a real agent.

### M4 design

Written 2026-11-25 after reading the Kotlin sources of section 3. Owner decisions the same day:
port everything to Rust (E4), and the client and server tests are required before an upload,
as in Compose (E1).

**The Kotlin flow, in order** (`ModpackUploadViewModel`, `RdiModpackUploadGateway`,
`ModpackProcessor`, `ModpackUploadService`, `ModpackChunkedUploader`, `ModpackTestSession`):

1. Page start: `ensureAudioReady` (an Opus canary through `OggTranscoder`; failure blocks the
   upload with 音频处理模块损坏，请更新客户端). Update mode: `GET modpack/{id}/detail`, refuse
   without `canUploadVersion`; the draft takes the pack's name, categories, icon and info.
2. Read the client pack (`loadLocalModpack`): structure preflight (CF `manifest.json` or MR
   `modrinth.index.json`; CF needs `overrides`), copy or extract into a work folder, read the
   manifest (name, version or `1.0`, MC version, loader; only 1.20.1 and 1.21.1), collect embedded
   jars (`overrides/mods`, client overrides), identify them (CurseForge fingerprint, Modrinth
   SHA-1), mark client overrides and raw client replacements, build the mod list (MR index with CF
   slug mapping through the catalog; CF manifest), `postProcessModSides` (not Fabric), stage
   embedded mod sources, match embedded resource and shader packs, merge client extras, flag
   `.mca` files (地图模板 warning). Then put embedded mods and extras into the content store,
   default unknown CurseForge sides, read Fabric sides from jars, `processUploadMods`
   (`pack_install::mods` already has it). Java check for the MC version.
3. Optional server pack (`loadServerPack`): merge server mods into the client list, server
   extra files.
4. Tests (required, E1): missing mods are downloaded first; the client test starts the pack and
   waits for `开始运行客户端测试`; the server test installs a test server once and waits for its
   ready line. Skipped only with a server pack, `rdi.ignoreModpackTest` or debug mode.
5. Submit: draft checks (name, version without spaces, info 10 to 100 code points, icon address
   on an allowed domain and an image, 1 to 4 categories), then `POST modpack/preflight`.
6. Task `上传并安装整合包 {name} {version}`, key `modpack-upload:{id|new}:{name}:{version}`
   (lower case), three steps:
   - 上传整合包: build the `.tar.zst` (cache folders out, `.disabled` out, matched platform
     content out, resource packs over 50MB out, region files kept empty, OGG to Opus or an
     empty sound when longer than 10 s, PNG recompressed, nested zip and jar rewritten, shader
     config rules, quest language rule, server extras under `server/`, 768MB cap), then the
     chunked upload: SHA-1, `POST /modpack/upload-sessions`, parts with `X-Part-SHA1` (up to 8
     at once, retries), `POST .../complete`, publish (`POST /modpack/from-upload` or
     `/modpack/{id}/version/{ver}/from-upload`), session cancelled on failure before publishing,
     new pack id from `GET /modpack/my`.
   - 下载剩余客户端Mod.
   - 本地安装整合包 from the built archive.

**What becomes Rust, in stages** (one commit each; the M4 gate is GM4):

| Stage | Content | Reuses |
|---|---|---|
| A1 | `src/upload/`: draft rules, categories, icon checks, preflight, `list_my_modpacks`, update target; reading a pack's manifest summary; tool `upload_modpack_check` | `import::archive` loader and version recognition, `api::Client` |
| A2 | Full pack reading: extraction into a work folder, embedded mod and extra identification, mod list, sides, content store commit, server pack merge | `catalog`, `content_store`, `digests`, `pack_install::mods` |
| A3 | Archive builder without audio: every Kotlin rule above; a Kotlin fixture test compares file lists | `pack_install::archive` (reader), `rdipack2` tar writer |
| A4 | OGG to Opus with the 10 s rule and the canary | to be chosen in A4: the game's media natives or a bundled encoder |
| A5 | Chunked upload, publish, pack id, missing mods, local install; tool `upload_modpack` returning a task | `save_import::upload` part uploader, `pack_install` install steps |
| A6 | Client and server tests; tool `upload_modpack` refuses until both pass | `play`, `mc_install` |

**Tool shapes.**

- `list_my_modpacks`: the packs the player may upload a version to (`GET modpack/uploadable`),
  newest first: id, name, versions.
- `upload_modpack_check { path, name?, version?, categories?, info?, icon_url?, source_url?,
  update_modpack_id? }`: reads the pack at `path` (a CurseForge or Modrinth zip or folder),
  returns what was recognised (name, version, MC version and loader, mods once A2 lands,
  warnings), the draft problems in Kotlin's words, and the preflight result when the draft is
  complete. Uploads nothing.
- `upload_modpack { path, name, version, categories, info, icon_url, source_url?,
  update_modpack_id? }`: runs the tests and the upload as launcher tasks and returns the task id
  (A5, A6).
- Category values are the Kotlin enum names (`LARGE`, `TECH`, ...); the tool description lists
  them with their Chinese labels.

Kotlin bugs found on the way are noted here and not fixed.

## 7. Validation rules

- `cargo test` from `client/slint-proto`; WSL native test recipe and known false failures are in
  `UI-PORT-PLAN.md` and the CI notes. `cargo clippy` clean for new code.
- Drive the launcher UI through the slint-ui MCP with `cargo preview-run`, never with native
  mouse scripts. Isolate `RDI_DATA_DIR` and remove it afterwards (it holds a test password).
- Never read the clipboard to check the copied prompt: read the hidden input's text through the
  slint-ui MCP instead.
- Do not take desktop screenshots; use the slint-ui MCP screenshot.
- Report results as they are: failing tests with their output, skipped steps as skipped.

## 8. Open questions (ask the owner when reached; do not block earlier milestones)

| # | Question | Default until answered |
|---|---|---|
| E1 | Must the upload test run (`modpack-test`, starts the pack once before upload) be ported for M4, or may an agent-driven upload skip it? | **Answered 2026-11-25: the tests are required**, as in Compose: the client and the server test must pass unless `rdi.ignoreModpackTest` or debug mode is on |
| E4 | Port the whole upload flow to Rust, or keep pack reading, archive building and tests in a headless Kotlin helper? | **Answered 2026-11-25: port everything to Rust** (owner picked the recommended option) |
| E5 | Which encoders for the upload archive? Kotlin needs two: Opus (24 kHz, 48 kbps, stereo) and AV1 for PNG files over 50KB (`PngCompression.kt` writes AVIF under the unchanged `.png` name, so other mods need no code change). | **Answered 2026-11-25: the game's slim FFmpeg natives** with a small C shim built in `client/mediaproc/ffmpeg/build-natives.sh` (flat functions, called through `libloading`); same encoders and settings as Kotlin. Needs a new natives release |
| E6 | One `upload_modpack` call, or a draft with several steps? Compose is interactive (mod sides, missing mods, test again after a failure, optional server pack). | **Answered 2026-11-25: a draft.** `upload_modpack_check` returns a `draft_id`; tools set mod sides, start the client and server tests (tasks), take an optional `server_pack_path`; `upload_modpack { draft_id, ... }` submits. Test results expire when the mod list changes (Kotlin `testedModsSignature`) |
| E7 | Stage order | **Answered 2026-11-25: A2, A3, A5, A4, A6.** Upload and publish are proven against the local master before the encoders; `upload_modpack` is not registered until A4 and A6 are done. A6 also needs the test server install (`McInstall` `--installServer`, Fabric server runtime), which Rust `mc_install` does not have |
| E2 | Are confirmation dialogs wanted before destructive tools are added later? | Destructive tools stay out (D1) |
| E3 | Should a stdio bridge exist for agents without HTTP MCP support? | No |

## 9. Progress

- 2026-11-25: plan written and approved; nothing implemented.
- 2026-11-25 M1 done: `src/mcp/` (`protocol`, `server`, `bridge`, `tools` with `get_status`,
  `Controller` in `mod.rs`), `src/mcp_ui.rs`, title bar icon (`\u{ee0d}` robot, green dot while on),
  `ui/mcp_menu.slint`. Settings file `<data dir>/launcher_mcp.json`. The host start sequence moved
  out of `App::start_room` into `play::room::enter` (returns `Entered`), and the game address
  capture (`Sessions::game_line`, `GameAi`, first valid `AI MCP URL:` line of a game started with
  the RDI core; GTNH and local packs stay `Unsupported`) landed with M1 because `get_status`
  reports it. `initialize` echoes a requested protocol version among 2025-11-25, 2025-06-18,
  2025-03-26 and 2024-11-05 (the Kotlin RMCP always answers 2025-11-25).
  Tests: 9 `mcp::` tests; workspace 66 + 373 pass on Windows; clippy has no new warnings (one
  existing "items after a test module" in `app.rs`). Live (isolated J5a data folder, local
  master, slint-ui MCP): click starts it and the hidden input holds the prompt; `initialize`,
  notification 202, `get_status` signed in over `pwsh` HTTP; wrong token 401, foreign `Origin`
  403; requests are served while the window is minimized; 关闭AI助手 refuses connections; turning
  it on again reuses the port and token; the token is not in the log. Not done: adding it to a
  real agent with the copied prompt (left for GM4).
- 2026-11-25 M2 code done: `list_hosts`, `get_host`, `play_host` (through `play::room::enter`),
  `install_host_modpack` (`pack_check`, then `pack_install::start` without extras, as Compose's
  default), `list_modpacks`, `list_tasks`, `get_task`, `cancel_task`, `get_game_log` (default 100,
  at most 500 lines; without `session` the only running game, else the newest one when none runs),
  `stop_game`. Host ids must be 24 hex digits before they reach a server path. Argument errors are
  tool results (`isError`), so the agent can correct itself; unknown tools stay protocol errors.
  14 `mcp::` tests pass; clippy has no new warnings. Live check runs together with M3 (one
  release build).
- 2026-11-25 M2 live (local master, test account 11111, isolated J5a data folder): `list_hosts`
  (3 hosts), `list_modpacks`, `get_host` (missing, then installed), `play_host` → `need_install`,
  `install_host_modpack` → task done in seconds, `list_tasks`; the installed packs then appear in
  the 整合包 tab. `play_host` after the install returned master's own refusal
  `启动房间失败: 请先为此房间安装kotlinforforge模组才能启动` as a tool error for both local test
  hosts, so a host game was not started through `play_host` (master rule, not changed here).
- 2026-11-25 M3 done: `src/mcp/game.rs` (`Forwarder`, no system proxy, 30 s timeout),
  `game_tools` and `game_call` in `tools.rs`; `game_call` returns the game's result unchanged.
  Sessions started with the RDI core are marked as waiting for the address when they are
  admitted (`room::spawn_run`), so `get_status` says `starting` instead of `unsupported` while a
  game is prepared. 16 `mcp::` tests; launcher 380 tests pass; clippy has no new warnings.
  Live, games started alone from the 整合包 tab (RDI core, since the hosts are refused by master):
  1.21.1 NeoForge and 1.20.1 Forge both reached `game_tools: available`; 30 game tools listed on
  each; `screen_read` and `screenshot` (PNG passed through) on both; on 1.20.1 `screen_click`
  created a world and `command` `/time query day` ran in it (cheats off, so the game answered
  未知或不完整的命令, returned as is); `get_game_log` and `stop_game` on both. The live run used
  the release build from before the `starting` change; that change is covered by tests only.
- 2026-11-25 environment notes: after a reboot, Windows refused to bind any loopback port from
  about 55000 to 56000 (netstat showed no owner, not in `excludedportrange`), so the Slint
  developer MCP could not use 55566; the live run used `SLINT_MCP_PORT=60066`
  (`evidence/launcher-mcp/live/run-built.ps1`). The room connection's 55667 is in that range too
  (it falls back to a free port). The launcher MCP's saved port 10503 was not affected.
- 2026-11-25 test temp folders: tests now remove their temp folders (`src/test_dir.rs`, commit
  8d5594e2), requested by another session for the owner.
- 2026-11-25 M4 design written (section 6, "M4 design"); owner decisions E1 (tests required) and
  E4 (port everything to Rust).
- 2026-11-25 M4 stage A1 done: `src/upload/` (`draft`: categories, field rules, icon address and
  image check; `source`: manifest look of a CurseForge or Modrinth zip or folder with Kotlin's
  messages; `api`: `modpack/uploadable`, update target, `modpack/preflight`), tools
  `list_my_modpacks` and `upload_modpack_check`. Loader recognition is shared with local import
  (`import::archive::recognize_loader`, behavior unchanged). Launcher tests 385 pass (5 new
  upload tests); clippy has no new warnings. The owner's sample pack
  (七七和小翔修仙世界0.3.377v2.0.zip) reads as Modrinth, 1.20.1 Forge, version 1.0.0 (ignored test
  `real_pack_summary`). Not done live: the tools against the local master (needs a release build).
  Differences noted: Kotlin parses the icon address with `java.net.URI`; Rust uses `Url`, so an
  address Java accepts but `Url` rejects is 链接无效 here. Next: A2 (full pack reading).
- 2026-11-25 M4 stage A2 part 1: `upload::jar_meta` (Kotlin `readModMeta`: neoforge.mods.toml,
  mods.toml, fabric.mod.json, mcmod.info; mod id, version, Fabric side) and `upload::mods`
  (`resolveClientPackEntryPath`, `distinctUploadMods`, `embeddedModUploadSide`,
  `markRawManifestModReplacements`, `selectEmbeddedModPathWinners`, `selectPreferredModFiles` with
  the loose version order, `postProcessModSides`), 4 more tests. Not wired yet (marked
  `allow(dead_code)` until the reader lands). Next in A2: the Modrinth and CurseForge manifest to
  mod and extra lists (`ModrinthService.loadParsedModpack`/`resolveManifestEntries`,
  `CurseForgeService.mapManifestEntriesToMods`/`...Contents`), embedded jar identification
  (`matchLocalModFiles` through `catalog::match_local_files`), embedded extras, server pack.
- 2026-11-25 handoff for A2 part 2 (session stopped at a committed checkpoint, 74192f15):
  the Kotlin upload works on raw platform data, while `crate::catalog` returns normalized
  models. Port through raw requests on the catalog transport (mirror and API key handling come
  with it): Modrinth `POST version_files` (sha1) and `GET projects?ids=`; CurseForge
  fingerprint match, `POST mods` (with and without the Mod class filter, `getModsInfo` vs
  `getModsInfoIncludingNonMods`) and `POST mods/files`. Then, in Kotlin order:
  `ModrinthService.loadParsedModpack` + `resolveManifestEntries` (manifest path rules,
  `manifestContentType`, download URL space encoding, CurseForge fallback through
  `/files/<a>/<b>/` ids, Fabric env sides, `appendExtra`), `CurseForgeService.mapManifestEntriesToMods`
  (side from `gameVersions`, else the Modrinth project of the file's SHA-1, else UNKNOWN) and
  `resolveManifestEntriesToContents`, `getInfosFromHash` for embedded jars (Modrinth first, then
  CurseForge, `serverModMergeKey`), `matchEmbeddedClientExtras` / `mergeEmbeddedClientExtras`
  (`pack_install::extras` has `validateAndMergeClientExtras`), staging into the content store,
  `containsExcludedMcaFiles` (`SaveRegionPaths`), and `loadServerPack`. Compare the resulting
  mod and extra lists with Kotlin on the owner's sample pack before A3.
- 2026-11-25 owner decisions E5 (encoders: the game's FFmpeg natives with a C shim), E6 (draft
  tools) and E7 (stage order A2, A3, A5, A4, A6) recorded in section 8. Two things the design
  above understated: the PNG rule writes AVIF (a second encoder), and the server test needs the
  test server install, which Rust `mc_install` lacks.
- 2026-11-25 M4 stage A2 part 2a: `upload::platform` (raw requests on the catalog transport:
  Modrinth `version_files` and `projects`, CurseForge `fingerprints/432`, `mods` with and without
  the mod class filter, `mods/files`; ids a first answer leaves out are asked again through the
  new `Transport::request_official`) and `upload::manifest` (`loadParsedModpack` with
  `resolveManifestEntries`, `mapManifestEntriesToMods`, `resolveManifestEntriesToContents`,
  catalog slug map). 7 new tests; launcher tests 396 pass, 6 ignored; clippy has no new warnings.
  Live (ignored test `real_pack_mods`, mirror preferred, no slug map): the owner's sample pack
  reads 180 entries as 161 mods (127 Modrinth, 34 through the CurseForge fallback; 13 with an
  unknown side) and 19 extras in 16 s. The live run found a panic on Chinese paths in
  `content_kind`, fixed with a test. Differences noted: Modrinth `projects` id lists are split
  as the catalog splits them (Kotlin sends one URL); with the mirror preferred the first request
  is the catalog's mirror and official race, not Kotlin's mirror then official. Still not wired
  (`allow(dead_code)`). Next, A2 part 2b: the reader (`loadLocalModpack`: copy or extract into
  a work folder, strict manifest parse with `manifest::Index` / `CfManifest`, embedded jars
  through `matchLocalModFiles` and `getInfosFromHash`, client override marking, staging,
  `matchEmbeddedClientExtras` / `mergeEmbeddedClientExtras`, `containsExcludedMcaFiles`), then
  `loadServerPack`, then the comparison with Kotlin on the sample pack.
- 2026-11-25 M4 stage A2 part 2b: `upload::reader` (`loadLocalModpack`: copy or extract into
  the work folder, strict manifest parse, embedded jars through Modrinth then CurseForge,
  client override marking, staging under content store names, embedded resource and shader
  packs, the 地图模板 flag) and `upload::policy` (`shouldSkipEntry` with its lists,
  `SaveRegionPaths`, `isDisabledFile`; stage A3 reuses them). The reader is blocking and takes
  a runtime handle for the platform requests, so callers use `spawn_blocking`. 3 new tests;
  launcher tests 399 pass, 7 ignored; clippy has no new warnings. Live (ignored test
  `real_pack_read`, the launcher's catalog for transport and slug map): the sample pack reads
  in 20 s as 161 mods (93 both, 53 client, 9 server, 6 unknown), 19 extras, 11 embedded jars
  that neither platform knows (they stay in the pack copy), no save regions.
  `RDI_UPLOAD_DUMP=<file>` makes that test write the lists as JSON for the Kotlin comparison.
  Differences noted: the structure and manifest checks run before the extraction (Kotlin
  reports manifest errors after it, same words); folders are walked in name order and links are
  skipped (Kotlin follows `listFiles` order and links); only zip archives are read (Kotlin also
  takes `.tar.zst`). Still not wired. Next: `loadServerPack` (A2 part 2c), the gateway steps of
  `prepareClientPack` (content store commit, unknown CurseForge sides, Fabric sides,
  `processUploadMods`, Java check), then the Kotlin comparison on the sample pack (a small
  Kotlin test in `client/pack-proc` that dumps `LoadedLocalModpack` as JSON).
- 2026-11-25 M4 stage A2 part 2c: `upload::server_pack` (`loadServerPack`: a folder read in
  place or a zip extracted with the modpack limits and the duplicate and conflict checks, the
  Bukkit refusal, newest jar per mod, client mods recognised by mod id and bytes before the
  platforms are asked, the server file rules, the 地图模板 flag, staging). Folder walks now follow
  `walkTopDown` (a folder's entries by name without regard to case, folders entered where they
  stand). 2 new tests; launcher tests 401 pass, 7 ignored; clippy has no new warnings. Kept as
  in Kotlin: a zip refused after its extraction stays in the work folder until the upload's
  cleanup. Not wired yet. Left of A2: the gateway steps of `prepareClientPack` and
  `prepareServerPack` in `ModpackUploadViewModel.kt` (content store commit of staged jars and
  extras, default sides for unknown CurseForge mods, Fabric sides from jars,
  `processUploadMods`, the Java check, merging server mods into the client list), then the
  comparison with Kotlin on the sample pack: run the ignored test `real_pack_read` with
  `RDI_UPLOAD_DUMP=<file>` and compare with a dump of `LoadedLocalModpack` from a small Kotlin
  test in `client/pack-proc`.
- 2026-11-25 M4 stage A2 part 2d: `upload::prepare` (the gateway steps: recognised jars and
  packs committed to the content store after a hash check, unknown CurseForge sides default to
  both, Fabric sides from cached jars, `processUploadMods`, `findMissingMods`,
  `mergeClientAndServerMods` with `mergeAsBoth`, `prepareServerPack`). The Compose page keeps no
  local file per mod, so a mod's jar is the cache file of its digest (not hashed again when
  read). 3 new tests; launcher tests 404 pass, 8 ignored; clippy has no new warnings. Live, real
  packs from the owner's Downloads: CurseForge `Sky Frogs-1.8.1` (1.21.1 NeoForge, 157 mods),
  `The-Winter-Rescue-0.6.0-rc8` (1.20.1 Forge, 60 mods), `After The Nukes` (29 mods, 2 resource
  packs, save regions found); the Fabric pair `Alex 2.0 dev 6.1` client and server (ignored test
  `real_server_pack` with `RDI_UPLOAD_SERVER_SAMPLE`): 307 embedded jars, 301 recognised and
  committed, server pack 211 mods of which only 17 needed the platforms (the rest matched a
  client mod by mod id and bytes), 7 unknown jars kept as server files, 304 mods merged, 52 s.
  Not ported: the Java check (`validateRuntime`), which belongs to the tests of stage A6.
  Live-run helper: `%TEMP%\rdi-m4-live.ps1 -Packs "a.zip|b.zip" [-Server s.zip] [-Test name]`
  (sets the console to UTF-8, one log and dump per pack). A full `cargo test` through WSL
  `pwsh.exe` may finish and still not return (see the J5c note); read the log file.
  Left of A2: the comparison with Kotlin.
- 2026-11-25 M4 stage A2 compared with Kotlin: `client/pack-proc`
  `UploadPackDumpTest` (does nothing without `RDI_UPLOAD_SAMPLE`, pack paths separated by `|`,
  and `RDI_UPLOAD_DUMP`, a folder for `kotlin-<n>.json`) dumps `LoadedLocalModpack`; run from
  `client/ui` with `.\gradlew.bat :pack-proc:cleanTest :pack-proc:test --tests
  "calebxzau.rdi.client.packproc.UploadPackDumpTest"` (2 min 45 s for four packs; IntelliJ MCP
  did not connect, so Windows `pwsh.exe` was used). Helper scripts in `%TEMP%`:
  `rdi-m4-kotlin.ps1 -Packs`, `rdi-m4-live.ps1 -Packs`, `rdi-m4-diff.py <temp> 1 2 3 4`.
  Result for the Modrinth sample pack (161 mods, 19 extras), CurseForge `Sky Frogs-1.8.1`
  (157 mods), `After The Nukes` (29 mods, 2 extras, save regions) and the Fabric `Alex 2.0 dev
  6.1` (301 embedded mods, 49 embedded packs): mods (order, sides, addresses, marks), extras,
  embedded names and the 地图模板 flag are equal. The first run differed on the Alex pack in two
  ways: folder order (fixed: entries are sorted as NTFS lists them, by the upper case name, so
  `[` and `_` come after letters; test added), and the Modrinth version id of two jars whose
  SHA-1 belongs to two versions of one project, where the mirror and the official API answer
  differently (not a code difference; equal on the second run). Launcher tests 405 pass,
  8 ignored; clippy has no new warnings. **Stage A2 is done** except its wiring into the draft
  tools (E6), which comes with stage A5. Next: stage A3, the archive builder
  (`buildUploadArchiveInternal`, `writeProcessedEntry`, `processNestedZip`,
  `readResourcepackEntry`, the shader config and shadowed source rules), with the encoders
  behind a trait until A4; `rdipack2::append` is the tar writer and `upload::policy` has the
  skip rules. Compare file lists with Kotlin through a second dump test.
- 2026-11-25 M4 stage A3 done: `upload::archive` (`buildUploadArchiveInternal`: the walk
  without `cache` folders, shadowed lower layers of packs and shader settings, recognised packs
  left out after a SHA-1 check before and after, oversized resource packs, `.disabled` files,
  the skip rules, empty region files of saves, nested zips and jars rewritten with
  `processNestedZip` in its three modes, MP3 files replaced by the bundled empty one, server
  files under `server/`, the 768MB limit; pictures and sounds on up to 8 threads). The two
  encoders are the trait `archive::Media`; `archive::Unprocessed` leaves the bytes alone until
  stage A4 and must not be used for an upload. `rdipack2::append` writes the tar entries.
  2 new tests; launcher tests 407 pass, 8 ignored; clippy has no new warnings. Compared with
  Kotlin (`UploadPackDumpTest` and `real_pack_read` with `RDI_UPLOAD_ARCHIVE=1`, script
  `rdi-m4-diff-archive.py`; the helper scripts read `RDI_M4_ARCHIVE` through `WSLENV`) on the
  Modrinth sample pack (493 entries, 17 nested archives), `Sky Frogs-1.8.1` (288) and
  `After The Nukes` (295, 3 nested): the entry paths and their order are equal, every file that
  is not a picture, a sound or a nested archive has the same size, and the nested archives hold
  the same names. Pictures and sounds differ as expected: Kotlin's 18 of the sample pack take
  0.58MB, unencoded 29MB. Differences noted: the tar headers are ustar with PAX path records
  (Kotlin: commons-compress POSIX mode), nested zips are compressed by another deflate
  implementation, and folders get the time of the build as in Kotlin. Not compared: a pack with
  a server pack or with recognised embedded packs (covered by tests with fakes only).
  Next: stage A5 (chunked upload, publish, missing mods, local install, the draft tools of E6)
  against the local master, and in parallel stage A4's C shim in
  `client/mediaproc/ffmpeg/build-natives.sh`, which ends in a new natives release.
- 2026-11-25 **handoff** (the owner stopped this session during stage A4; another session
  continues). State:
  - Owner decision the same day: the agent itself runs
    `client/mediaproc/ffmpeg/publish-natives.sh <run id>` for the natives release with the shim
    (「你来跑吧」). This covers that release only. Pushing `dev` is not covered: it holds other
    unpushed work.
  - Committed on `dev`, unpushed: A2 and A3 (05dbea89, 00267068, 27a74e52, a2624278, a1e90b89,
    445ad629) and the shim bec0694a: `client/mediaproc/ffmpeg/rdimedia.c` (`rdi_media_abi`,
    `rdi_media_free`, `rdi_ogg_to_opus` by file paths, `rdi_rgba_to_avif` in memory; the Kotlin
    encoders call for call), built and packed by `build-natives.sh` as `rdimedia.dll` /
    `librdimedia.so` / `librdimedia.dylib` beside the FFmpeg libraries, jar revision `rdi2`,
    checked by `check-natives.py`, and called on each platform in CI by `test-shim.py`.
  - Pushed: branch `natives-rdimedia` (5a2e602a), which is `origin/dev` plus only the shim
    commit, made with a temporary index (`git read-tree origin/dev`, `git update-index
    --cacheinfo` for the files under `client/mediaproc/ffmpeg` and the workflow, `git
    commit-tree`). It started `ffmpeg-natives` run 45536278803, still building when this was
    written. If it succeeds: `publish-natives.sh 45536278803` (prerelease
    `ffmpeg-natives-8.0.1-rdi2`), then the new version and the hashes of its `SHA256SUMS` go
    into `client/gradle/ffmpeg-natives.gradle.kts`. If it fails: fix on `dev`, build the branch
    again the same way, push, and use the new run.
  - Local check of the shim before the push: compiled with the Windows zig 0.14.1 against the
    libraries of the cached `rdi1` jars and FFmpeg 8.0.1 headers, for all three platforms,
    without warnings; `test-shim.py` passes on Windows and, natively in WSL, on Linux (AVIF
    with and without alpha, Opus through paths outside ASCII, too long, bad input). The builds
    are in `%TEMP%\rdi-m4-shim\{win,linux,mac}`; `win` is a complete natives folder for
    `RDI_MEDIA_NATIVES`.
  - **Not committed and not yet compiled**: `src/upload/media.rs` (`media::Encoders`: loads the
    FFmpeg libraries in dependency order and `rdimedia` through `libloading`, implements
    `archive::Media`, `ready()` is `ensureAudioReady`), `libloading = "=0.8.9"` in `Cargo.toml`,
    `pub mod media` in `src/upload/mod.rs`. The first test run was stopped while compiling.
    Run `cargo test --bin rdi-slint-proto upload::media -- --include-ignored --nocapture` with
    `RDI_MEDIA_NATIVES=%TEMP%\rdi-m4-shim\win`, fix what it shows, run clippy, commit.
  - Next, in order: (1) the item above; (2) make the ignored test `real_pack_read` use
    `media::Encoders` when `RDI_MEDIA_NATIVES` is set and compare the sizes of pictures and
    sounds with the Kotlin dumps (`rdi-m4-diff-archive.py`; AVIF output is the same on Windows
    and Linux, Ogg serial numbers are random, so sounds will be near, not equal); known
    differences to expect: pictures taller than 720 pixels are shrunk with Catmull-Rom on
    straight alpha (Kotlin: Java2D bicubic on premultiplied alpha), grey PNG files are not
    brightened as Java's `getRGB` does; (3) the release and the Gradle version; the launcher
    gets the natives folder from `play::runtime::Runtime::prepare_media_natives`, and natives
    without `rdimedia` are refused with 音频处理模块损坏，请更新客户端; (4) stage A5: chunked
    upload, publish, missing mods, local install and the draft tools of E6 against the local
    master (Kotlin `ModpackUploadService.kt`, `ModpackUploadApi.kt`,
    `ModpackChunkedUploader.kt`, `ParallelModpackUploader.kt`; Rust `save_import::upload`),
    with `upload_modpack` unregistered until A6; (5) stage A6: the tests and the test server
    install.
  - Environment: a full `cargo test` or Gradle run through WSL `pwsh.exe` can finish and not
    return, so wrap it in `timeout` and read the log file; IntelliJ MCP did not connect in this
    session, so Gradle ran through `pwsh.exe` and `client/ui\gradlew.bat`; the local master
    (`server/master` `run`, started 01:32) was left running; `AGENTS.md` has an uncommitted
    change that is not from this work; the helper scripts are in `%TEMP%` (`rdi-m4-live.ps1`,
    `rdi-m4-kotlin.ps1`, `rdi-m4-diff.py`, `rdi-m4-diff-archive.py`), with the Kotlin dumps in
    `%TEMP%\rdi-m4-kotlin`.
