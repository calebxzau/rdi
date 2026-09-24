# dm-master

`dm-master` stores distributed multiplayer hosts and serves the authoritative
Sync Chunks（同步区块）roster and World Snapshot API. The gateway remains the
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
and the former section and FirmChunk route names are invalid.

## World Snapshots (v5)

The production synchronization route is
`PUT /hosts/{hostId}/world-snapshot`. It accepts an `application/zip` body
with `X-DM-Session`, positive `X-DM-Snapshot-Sequence`, nonnegative
`X-DM-Sync-Revision`, UUIDv7 `X-DM-Sync-Cycle`, and lowercase SHA-1 of the
entire upload in `X-DM-SHA1`. The status route is
`GET /hosts/{hostId}/world-snapshot/status`.

Metadata is `formatVersion: 5`, `kind: "world-snapshot"`, and
`captureMode: "memory-and-files"`; `restoreSafe` is always `false`. It records
the host/session, cycle, sequence, roster revision, capture time, all included
WorldData files and directories, and observed chunk summaries. The root
`world/level.dat` file is required. A cycle may contain zero chunks and may
inherit unchanged file/column payloads from its declared base. Master stamps
authoritative membership revisions and publishes one complete immutable directory.
Uploads remain incremental ZIPs; no cumulative ZIP is built during synchronization.

Fixed snapshots can be inspected with
`GET /hosts/{hostId}/world-snapshot/{snapshotId}/manifest`. Snapshot downloads are
not implemented for directory storage: the authenticated
`GET /hosts/{hostId}/world-snapshot/{snapshotId}` route returns HTTP 501 with
`DownloadUnavailable`. World-init downloads are unchanged. Only latest and previous
snapshots are addressable; an in-flight operation can pin an older directory until it
finishes. Unchanged files are hard-linked into a new snapshot, or copied if the
filesystem cannot create a hard link. Changed files always receive new inodes.

Snapshot status, manifest responses, and upload receipts declare `apiVersion: 2`;
`/info` advertises `worldSnapshotApiVersion: 2`. Upload ZIP metadata remains v5.
`manifestSha1` hashes the internal storage manifest and is not a ZIP hash.
`expandedBytes` counts all payload files including `metadata.json`, excluding the
internal `manifest.json`. `uploadSha1` and `uploadZipBytes` still describe the exact
uploaded ZIP. The removed `archiveSha1`, `archiveZipBytes`, and `maxArchiveBytes`
fields must not be synthesized by clients. The old `--world-snapshot-archive-mib`
option is no longer accepted; upload, expanded-content, and per-member limits remain.

The archive derives native paths as
`dimensions/<namespace>/<path>/<terrain|entities|poi>/<x>.<z>.nbt`. Dimension
IDs are resource locations with one colon, safe lowercase namespace/path
characters, and no empty, `.` or `..` components. Expanded, metadata, NBT,
file-count, compressed-upload, SHA-1, CRC, and path checks are enforced.

Membership revisions are created when a chunk is added and removed when it is
deleted. Deleting and re-adding a coordinate therefore invalidates retained
content from the earlier incarnation. Publication uses an atomic state pointer
and preserves the latest and previous directories. Preparation writes and syncs files
before the short publication gate; pointer durability failures are reconciled against
persisted state before any candidate is retired. Recursive cleanup runs outside the
publication/retention locks. Startup recovery validates retained snapshots and removes
only recognized orphan directories owned by the new layout. Missing/corrupt retained
data is a storage failure, never silently treated as an empty snapshot.

Full content validation is retained: this reduces content writes, but not all content
reads. Server logs distinguish `snapshotExpandedBytes`, `linkedBytes`, `copiedBytes`,
`writtenBytes`, and stage durations. Linked bytes are logical reused bytes, not disk
writes. Hard-link fallback is logged.

Persistent data is kept under:

```text
data/
  hosts/<hostId>/host.json
  hosts/<hostId>/world-init.zip
  hosts/<hostId>/sync-chunks.json
  hosts/<hostId>/world-snapshots/directories-v1/state.json
  hosts/<hostId>/world-snapshots/directories-v1/snapshots/<snapshotId>/metadata.json
  hosts/<hostId>/world-snapshots/directories-v1/snapshots/<snapshotId>/manifest.json
  hosts/<hostId>/world-snapshots/directories-v1/snapshots/<snapshotId>/world/...
  hosts/<hostId>/world-snapshots/directories-v1/snapshots/<snapshotId>/chunks/...
  hosts/<hostId>/world-snapshots/directories-v1/staging/<operationId>/
  hosts/<hostId>/world-snapshots/directories-v1/garbage/<operationId>/
  staging/
```

The previous ZIP-based `world-snapshots/state.json` and `<snapshotId>.zip` files
are left untouched and are not migration inputs. The new store starts with no base;
clients upload full WorldData and currently captured columns on their first new cycle.
This never modifies or deletes the local Minecraft save. The directory still contains
v5 snapshot payloads (individual chunk NBT), not a directly playable `.mca` world.

The retired v4 `sync-chunks/snapshot` routes and
`sync-chunk-snapshots/` storage are not read, migrated, or deleted.

Gateway routes are `POST /gateway/heartbeat`, `PUT` and `DELETE
/gateway/hosts/{hostId}`, and `PUT /gateway/sessions`. Presence is in memory;
session checks require a healthy synchronized gateway session.
