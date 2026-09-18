# dm-master

`dm-master` stores immutable distributed-multiplayer hosts and exposes the
HTTP API used by the Minecraft client and `dm-gateway`. It is a standalone
Tokio/Axum service; the gateway remains responsible for the game reverse
proxy.

## Run

```text
cargo run -- --http-listen 0.0.0.0:15566 \
  --data-dir ./data \
  --max-upload-mib 256 \
  --public-host dm.example.com \
  --tunnel-port 25566
```

All options are optional. The defaults are `0.0.0.0:15566`, `./data`, 256 MiB,
no configured public host, and tunnel port `25566`.

## HTTP contract

Application responses always use HTTP 200. JSON responses have this shape:

```json
{"code":0,"msg":"","data":{}}
```

`code` is an `i8`: `0` means success and `-1` means failure. `msg` is always
present. `data` is omitted when it is null. ZIP downloads are the one successful
binary response and use `application/zip`; errors before streaming use the JSON
response shape.

Public routes:

* `GET /info` returns configured `publicHost` when present and `tunnelPort`.
  Clients use the configured master URL's host when `publicHost` is omitted.
* `POST /hosts` accepts multipart fields `name` and `worldInit`. The ZIP is
  streamed to staging, checked as a readable ZIP with safe relative entry paths,
  and then published atomically. The server generates the UUIDv7 only after
  validation. The initialization ZIP is immutable.
* `GET /hosts/{hostId}` returns the host name and `Online`, `Offline`, or
  `Unavailable` state. `gamePort` is omitted unless the host is online.
* `GET /hosts/{hostId}/world-init` streams the immutable ZIP, even while the
  host is offline.

Gateway routes:

* `POST /gateway/heartbeat` returns `{ "syncRequired": boolean }`.
* `PUT /gateway/hosts/{hostId}` accepts `{ "sessionId": UUID, "gamePort": u16 }`.
* `DELETE /gateway/hosts/{hostId}` accepts `{ "sessionId": UUID }`.
* `PUT /gateway/sessions` accepts `{ "sessions": [{ "hostId": UUID,
  "sessionId": UUID, "gamePort": u16 }] }` for recovery synchronization.

The master keeps gateway presence in memory. A gateway heartbeat is expected
every 10 seconds and expires after 30 seconds. A fresh master, or a heartbeat
after expiry, requires a full session synchronization before hosts become
online. Session IDs prevent a delayed offline report from removing a newer
session.

Persistent data is kept under:

```text
data/
  hosts/<hostId>/host.json
  hosts/<hostId>/world-init.zip
  staging/
```

The service does not parse Minecraft NBT and does not require `level.dat`; it
only validates ZIP structure and entry paths.
