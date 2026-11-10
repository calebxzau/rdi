# Launcher service protocol v4

Status: U2/U3 accepted. U4a local-pack methods and snapshots are under validation.

Transport is binary stdin/stdout: a four-byte unsigned big-endian byte length,
then one UTF-8 JSON object. Length must be 1..8388608 inclusive. EOF between
frames shuts down the service; EOF within a frame is a protocol error. Files
and images are represented by paths or URLs, never by embedded bytes.

`fixtures/messages.json` is the shared golden corpus read by Rust and Kotlin.
The fixture credentials are inert examples, never a test account.

The first request must be `handshake` with `params.protocolVersion = 4`.
A mismatching version receives `PROTOCOL_VERSION_MISMATCH` and a Chinese
message. No business request may run before the handshake succeeds.

Requests carry a positive integer `id`, string `method`, and object `params`.
Responses carry the same `id` and exactly one of `result` or `error`.
Errors carry `code` and a Chinese player-facing `message`. Events carry
`event` and object `params`, with no `id`.

Implemented methods: `handshake`, `session.set`, `host.start`, `game.subscribe`,
`game.stop`, `game.stopAll`, `game.restore`, `shutdown`, and the U4a methods below.
Login, saved accounts and server data requests run directly in Rust. The old
`login`, `login.accounts`, `login.saved`, `data.get` and `hostList.*` methods are removed.

The service must reserve original stdout before initializing logging or any
business dependencies. All human output goes to a log, never protocol stdout.

Game snapshots expose preparation, running, stop request and exit messages.

The Rust connection has a 30-second handshake timeout and a 60-second business
request timeout. A disconnected generation fails its pending requests and is
never replayed. Login/menu browsing starts no process. First use shares one start:
handshake, `session.set` with identity check, `game.restore` if live records exist,
`game.subscribe`, `tasks.subscribe`, then the caller's request. Failed starts leave
it stopped for the next action. Unexpected exits show a dismissible error without
signing out. Live game records trigger immediate restart and recovery; otherwise
it remains stopped until first use. Import/task/console screens return to the pack
list after a crash and queued mutations are discarded. State notifications are conflated
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

- `installer.open`: create a screen-owned Kotlin view model and emit
  `installer.state` snapshots. `installer.close` cancels that screen's scan,
  preview, resolution and subscription; a submitted task continues in the background.
- `installer.action {action,...}` accepts `scan`, `archive`/`directory` with
  `path`, `candidate`/`pickedCandidate`/`create`/`runtime` with `index`,
  `name`/`comment`/`icon` with `value`, `includeSaves`, `optional` with `key`,
  `back`, `dismissCandidates`, `dismissMissing`, and `dismissError`.
  Candidate/runtime indices address the most recent screen snapshot. Archive
  contents expose only display metadata and optional-file keys, not download headers.
  Editor snapshots include sourceDirectory, archivePath and progressFraction;
  runtime labels retain the launcher's Forge/NeoForge/Fabric spelling.
- `installer.install {missingFilesConfirmed}` returns `{runId}` when submitted,
  otherwise the screen snapshot with confirmation or validation error. Optional
  files start unchecked. Missing-file confirmation is never inferred by the UI.
- `packPlay.open {versionId}` returns non-secret account-choice defaults.
  `packPlay.start` takes versionId, mode (`Offline`/`Online`), nickname,
  windowWidth/windowHeight in physical pixels, and allowDuplicateVersion.
  It acknowledges immediately; `packPlay.state` reports busy/error/deviceUri,
  duplicateVersion or closed. Duplicate confirmation retries the already prepared
  arguments held in service memory. `packPlay.cancel` cancels preparation/login.
  Online login requires the RDI account's bound msid. Tokens and game accounts
  never cross stdio. Local packs launch with the RDI runtime disabled.
- `tasks.subscribe` emits `tasks.state` containing full task summaries.
  `tasks.watch {runId}` returns the current task and emits `task.state` snapshots
  with stable hierarchical row keys, progress and terminal/error status. A terminal
  snapshot ends its watcher. Only one detailed task is watched per UI connection.
  Group rows include their default expanded state. Closing while a task is active
  requires confirmation; service shutdown cancels and joins active task controllers.
- `console.subscribe {sessionId?}` emits `console.state` for that session, defaulting
  to the latest; it includes session choices and complete bounded trailing lines.
  `console.clear` clears the selected session. Rust handles copy/export/native file
  dialogs. Snapshot text stays within a conservative1MB JSON budget. The full
  session log is not restored after a service crash.

All subscriptions are scoped to the service connection. Screen state subscriptions
sample conflated updates at34ms; detailed tasks include the terminal state before
ending. Rust serializes U4a UI commands and discards replies from disconnected
generations. Mutations are never replayed after reconnecting.

## J1 session handover

`shutdown {ifIdle:true}` is an inline proposal. Kotlin returns `{stopped:true}` and
ends the loop only when no game is preparing/alive, no unfinished task exists,
and no installer, active play job or pending play exists. Otherwise it returns
`{stopped:false}` and keeps reading. Missing/false `ifIdle` stops unconditionally.
Rust proposes after 60 continuous idle seconds, with all related screens/dialogs
closed and no queued/in-flight request. The timer runs while inactive/minimized.
Requests arriving after a proposal wait for its answer and start a new generation
if accepted. Idle stops show no error; finished tasks and console logs are lost.

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

## J4a Rust import and mixed tasks

Rust owns folder scanning, empty/folder imports and their launcher-lifetime task
history. Opening import or tasks starts no process. Only archive editing/import
uses the existing installer methods (bridge choice a in J4-PLAN.md).
`tasks.cancel {runId}` cancels a Kotlin-owned task; Rust-owned IDs stay local.
Task snapshots add `createdAt` in epoch milliseconds for mixed newest-first order;
Kotlin IDs are not time-ordered. The task screen includes cancel controls for
unfinished tasks. Rust history survives an idle service stop or crash.
Import and task screens no longer block idle stopping, except while the archive
bridge owns a Kotlin editor. Logout also refuses active Rust tasks.
