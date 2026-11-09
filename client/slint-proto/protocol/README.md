# Launcher service protocol v1

Status: U2/U3 accepted. U4a local-pack methods and snapshots are under validation.

Transport is binary stdin/stdout: a four-byte unsigned big-endian byte length,
then one UTF-8 JSON object. Length must be 1..8388608 inclusive. EOF between
frames shuts down the service; EOF within a frame is a protocol error. Files
and images are represented by paths or URLs, never by embedded bytes.

`fixtures/messages.json` is the shared golden corpus read by Rust and Kotlin.
The fixture credentials are inert examples, never a test account.

The first request must be `handshake` with `params.protocolVersion = 1`.
A mismatching version receives `PROTOCOL_VERSION_MISMATCH` and a Chinese
message. No business request may run before the handshake succeeds.

Requests carry a positive integer `id`, string `method`, and object `params`.
Responses carry the same `id` and exactly one of `result` or `error`.
Errors carry `code` and a Chinese player-facing `message`. Events carry
`event` and object `params`, with no `id`.

Implemented U2 methods: `handshake`, `login`, `hostList.subscribe`,
`hostList.refresh`, `hostList.loadMore`, `host.start`, `game.subscribe`,
`game.stop`, `game.stopAll`, `game.restore`, `data.get`, and `shutdown`. `data.get` is a temporary allowlisted read adapter for the existing menu, player appearance and crowd; arbitrary API paths are rejected. Subscriptions emit the current snapshot first,
then conflated full snapshots no more frequently than every 34ms. Host lists
must be paged before approaching the frame limit. Terminal task events end
their underlying subscription, including reconnecting SSE streams.

The service must reserve original stdout before initializing logging or any
business dependencies. All human output goes to a log, never protocol stdout.

Room snapshots contain up to 100 entries with `page` and `totalLoaded`. Requests
may select a nonnegative `page`. Game snapshots expose preparation, running,
stop request and exit message without file or console payloads. A missing pack
returns a Chinese error; installation UI remains in the U4 batch.

The Rust connection has a 30-second handshake timeout and a 60-second business
request timeout. A disconnected generation fails its pending requests and is
never replayed. The menu detects process exit, shows a Chinese reconnect message,
and starts a fresh service with a new login. State notifications are conflated
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

- `installedPacks.subscribe` / `installedPacks.refresh`: read the same installed
  RDI and local packs as Compose, in descending installation-time order. Emit
  `installedPacks.state` with loading/error/packs. `versionId` identifies a pack;
  paths supplied by the UI cannot select an arbitrary installed pack.
- `installedPacks.directory {versionId}` returns its path for the Rust UI to
  open in the system file manager. `/modpack/search` is no longer allowlisted.
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
- `packOptions.open {versionId}` emits `packOptions.state` with loading,
  loadFailed, saving, dirty, totalMemoryMb, error, navigateBack and a draft.
  `packOptions.action` accepts `update {draft}`, `reset`,
  `save {returnAfterSave}`, `clearCompleted` and `close`.
  Draft snapshots encode defaults and include globalJavaPath for the existing
  custom-JDK toggle's initial value.
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

## U4b0 login

After handshake, `login.accounts`, `login.saved`, and `login` are allowed before
sign-in. Other business methods still require login. No new events are introduced.

- `login.accounts {}` returns `accounts` sorted by descending `lastLoggedTime`,
  each containing `id`, `account`, `name`, and `lastLoggedTime`; `version` supplies
  the launcher version label. Optional `lastId`
  selects the prefilled account; optional `autoLoginId` requests automatic login.
  Empty history returns an empty array with neither ID. No password is returned.
- `login.saved {id}` resolves credentials only inside Kotlin, then calls the same
  LoginController/PlayerService path as `login {usr,pwd}`. Missing IDs fail with a
  retryable Chinese message. Both login methods return `{loggedIn:true,uid}`.
- Rust displays a saved-password hint, never a copy of the stored password.
  Editing either field clears the saved selection. Password reveal applies only
  to newly typed text. Request/response bodies and credentials are never logged.
- Both development environment variables override history; failed login returns
  to the page. Without both variables the launcher uses history/auto-login.
