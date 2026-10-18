# dm-master

`dm-master` stores distributed multiplayer hosts and serves the authoritative
Sync Chunks（同步区块）roster and World Checkpoint API. The gateway remains the
game reverse proxy.

## Run

```text
cargo run -- --http-listen 0.0.0.0:15566 \
  --data-dir ./data \
  --max-upload-mib 256 \
  --sync-chunk-max-total 256 \
  --public-host dm.example.com \
  --tunnel-port 25566
```

The default host limit is 256 unique chunks across all dimensions. There is no
per-player limit. The limit must be greater than zero.

## HTTP contract

Application responses use HTTP 200 with `{"code":0,"msg":"","data":{}}` on
success and `{"code":-1,"msg":"...","data":{...}}` on failure. ZIP downloads
are the successful binary exception and use `application/zip`.

Public routes are `GET /info`, `POST /hosts`, `GET /hosts/{hostId}`, and
`GET /hosts/{hostId}/world-init`. Host creation accepts multipart `name` and
`worldInit`, validates the ZIP, and writes an empty `sync-chunks.json` roster.

The active DM session uses `X-DM-Session: UUID` for roster and snapshot reads.
`PUT` and `DELETE /hosts/{hostId}/sync-chunks` accept:

```json
{
  "sessionId": "UUID",
  "expectedRevision": 0,
  "playerId": "UUID",
  "chunk": { "dimensionId": "minecraft:overworld", "chunkX": 0, "chunkZ": 0 }
}
```

`GET /hosts/{hostId}/sync-chunks` returns the sorted authoritative roster:
`revision`, `chunks`, and `limits: { "maxTotal": 256 }`. Every accepted
mutation increments `revision`, including no-op mutations. Results report
`Added`, `AlreadyPresent`, `Removed`, or `AlreadyAbsent`. A chunk is identified
by dimension, X, and Z; one player may fill the entire host limit, only its
owner may delete it, and another player cannot claim it. Duplicate adds remain
successful at capacity. The persisted chunk schema uses `ownerId`.

Only the active routes above are accepted. Section-shaped requests, `ownerUuid`,
and the former section-based routes are invalid.

## World Checkpoints (v6)

The production synchronization route is `PUT /hosts/{hostId}/world-checkpoint`. It
uses `X-DM-Session`, positive `X-DM-Checkpoint-Sequence` (the old snapshot header
is accepted during rollout), UUIDv7 `X-DM-Sync-Cycle`, and lowercase SHA-1 in
`X-DM-SHA1`. `/info` advertises `worldCheckpointApiVersion: 3`.

Each checkpoint is created after vanilla `saveEverything` and contains changed
non-region files under `world/` plus opaque vanilla Anvil entries under
`regions/<regionPath>/<index>`. Region members are stored without recompression.
`metadata.json` is format6 `world-checkpoint` data and includes file hashes,
removed files, region timestamps, sector counts, and a `baselineComplete` flag.
The master applies the upload through a journal and keeps a directly playable
world under `world-checkpoint/world/`. A download is available at
`GET /hosts/{hostId}/world-checkpoint/download` once the baseline is complete.

Session-authenticated inspection routes are:

- `GET /hosts/{hostId}/world-checkpoint/status`
- `GET /hosts/{hostId}/world-checkpoint/files`
- `GET /hosts/{hostId}/world-checkpoint/regions`
- `GET /hosts/{hostId}/world-checkpoint/regions/{regionPath}`

The limits are published in status and can be changed with `--max-upload-mib`,
`--max-world-mib` (default8192), and `--max-journal-mib` (default1024). Region
compression bytes are opaque to the master; the client only taps vanilla
`RegionFile` writes. If the host exits abnormally, the playable world can lag by
about one minute, with small item duplication or loss windows. A second copy of
the same world must not host concurrently; takeover and lineage fencing remain
out of scope.

Persistent checkpoint data is kept under:

```text
data/hosts/<hostId>/world-checkpoint/
  state.json
  world/
  journal/<sequence>.zip
  staging/
```

The former `world-snapshot` routes are retired and return the application
unknown-route response. Existing v5 data is left untouched; the first checkpoint
for an existing room performs a full baseline reconciliation.

Gateway routes are `POST /gateway/heartbeat`, `PUT` and `DELETE
/gateway/hosts/{hostId}`, and `PUT /gateway/sessions`. Presence is in memory;
session checks require a healthy synchronized gateway session.
