# Launcher service protocol v1

Status: U2 Windows main-flow acceptance recorded; waiting at GU2.

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
