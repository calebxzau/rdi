# Launcher service protocol v5

Status: U2/U3 accepted. U4a local-pack methods and snapshots are under validation.

Transport is binary stdin/stdout: a four-byte unsigned big-endian byte length,
then one UTF-8 JSON object. Length must be 1..8388608 inclusive. EOF between
frames shuts down the service; EOF within a frame is a protocol error. Files
and images are represented by paths or URLs, never by embedded bytes.

`fixtures/messages.json` is the shared golden corpus read by Rust and Kotlin.
The fixture credentials are inert examples, never a test account.

The first request must be `handshake` with `params.protocolVersion = 5`.
A mismatching version receives `PROTOCOL_VERSION_MISMATCH` and a Chinese
message. No business request may run before the handshake succeeds.

Requests carry a positive integer `id`, string `method`, and object `params`.
Responses carry the same `id` and exactly one of `result` or `error`.
Errors carry `code` and a Chinese player-facing `message`. Events carry
`event` and object `params`, with no `id`.

Implemented methods: `handshake`, `session.set`, `host.start`, `game.subscribe`,
`game.stop`, `game.stopAll`, `game.restore`, `shutdown`, and the play/console methods below.
Login, saved accounts and server data requests run directly in Rust. The old
`login`, `login.accounts`, `login.saved`, `data.get` and `hostList.*` methods are removed.

The service must reserve original stdout before initializing logging or any
business dependencies. All human output goes to a log, never protocol stdout.

Game snapshots expose preparation, running, stop request and exit messages.

The Rust connection has a 30-second handshake timeout and a 60-second business
request timeout. A disconnected generation fails its pending requests and is
never replayed. Login/menu browsing starts no process. First use shares one start:
handshake, `session.set` with identity check, `game.restore` if live records exist,
`game.subscribe`, then the caller's request. Failed starts leave
it stopped for the next action. Unexpected exits show a dismissible error without
signing out. Live game records trigger immediate restart and recovery; otherwise
it remains stopped until first use. The console returns to the pack list after a crash; Rust import/task state survives and queued mutations are discarded. State notifications are conflated
by topic. EOF stops game sessions, closes subscriptions, and exits the JVM so
network library threads cannot hold it open. Closing the UI with a running game
requires confirmation, matching Compose's termination behavior.

`game.state` includes optional non-secret `process` records for live games.
`game.restore` accepts these records after a service restart and checks PID and
process start time before restoring stop/close/duplicate management. Each record
contains id/title/versionId/versionName/mcVersion/modLoader/pid/processStartedAt;
it contains no account, token or launch arguments. Console streams cannot be
reattached. A service crash drops its local proxy connection; a player may need
to reconnect even though the game process remains managed.

`host.start` may supply physical-pixel windowWidth/windowHeight. A duplicate
version returns duplicateVersion/hostId/title; the client asks for confirmation
before retrying with allowDuplicateVersion=true. Ordinary action failures use a
dismissible error dialog and do not trigger reauthentication.

## U4a local packs

Installed packs, directories and options are owned by Rust since J2.
`installedPacks.*` and `packOptions.*` service methods are removed.

- `packPlay.open {versionId}` returns non-secret account-choice defaults.
  `packPlay.start` takes versionId, mode (`Offline`/`Online`), nickname,
  windowWidth/windowHeight in physical pixels, and allowDuplicateVersion.
  It acknowledges immediately; `packPlay.state` reports busy/error/deviceUri,
  duplicateVersion or closed. Duplicate confirmation retries the already prepared
  arguments held in service memory. `packPlay.cancel` cancels preparation/login.
  Online login requires the RDI account's bound msid. Tokens and game accounts
  never cross stdio. Local packs launch with the RDI runtime disabled.
- `console.subscribe {sessionId?}` emits `console.state` for that session, defaulting
  to the latest; it includes session choices and complete bounded trailing lines.
  `console.clear` clears the selected session. Rust handles copy/export/native file
  dialogs. Snapshot text stays within a conservative1MB JSON budget. The full
  session log is not restored after a service crash.

All subscriptions are scoped to the service connection. Screen state subscriptions
sample conflated updates at34ms. Rust serializes play/console commands and discards
replies from disconnected generations. Mutations are never replayed after reconnecting.

## J1 session handover

`shutdown {ifIdle:true}` is an inline proposal. Kotlin returns `{stopped:true}` and
ends the loop only when no game is preparing/alive, no active play job or pending play exists. Otherwise it returns
`{stopped:false}` and keeps reading. Missing/false `ifIdle` stops unconditionally.
Rust proposes after 60 continuous idle seconds, with all related screens/dialogs
closed and no queued/in-flight request. The timer runs while inactive/minimized.
Requests arriving after a proposal wait for its answer and start a new generation
if accepted. Idle stops show no error; Rust task history survives. Service console logs are lost.

Logout stops the process and removes that account from `msa_sessions.json` in Rust
without starting a process. Missing file/account is a no-op. Other accounts and
unknown top-level fields survive. Damaged files stay untouched with an error.
Compose keeps its existing store.

After handshake, `session.set {account,jwt}` and `shutdown` are allowed before
sign-in; all business methods require a successful session handover.
`account` is the server RAccount object with `pwd` replaced by an empty string.
The JWT and any decoding input are never logged. Invalid accounts or blank JWTs
return `登录信息无效`; success returns `{uid}`. Earlier versions are rejected.

Rust loads and atomically updates `local_credentials.json`, preserving unknown fields.
Saved-account selection carries only an ID in Slint. Rust looks up the password
again at submit time. Typed/environment login, retry and auto-login order remain unchanged.
Shared fixtures `local_credentials.json` and `hwspec.json` are read by both languages.

## J4b native import and tasks

Protocol v5 removes `installer.*`, `tasks.*`, `installer.state`, `tasks.state`
and `task.state` from the service. Their JSON shapes remain an internal Rust UI
contract. Task history, scanner, downloader, shared dlc cache, catalog and archive
preview/install run in Rust. Only play and console start the service.
Logout refuses unfinished Rust tasks. Close cancels tasks and waits up to three
seconds for staging cleanup. Rust imports and tasks survive a service crash.
