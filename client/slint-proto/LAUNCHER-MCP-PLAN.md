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
| E1 | Must the upload test run (`modpack-test`, starts the pack once before upload) be ported for M4, or may an agent-driven upload skip it? | Port it last; do not skip silently |
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
