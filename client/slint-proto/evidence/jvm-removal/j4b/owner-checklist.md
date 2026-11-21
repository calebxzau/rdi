# GJ4b owner checklist

Status: implementation incomplete. GJ4b is not reached or passed. Do not start J5 or commit without owner direction.

## Verified automatically or through isolated MCP

- [x] Protocol v5 removes Kotlin installer/tasks methods and events; lazy prelude only subscribes game state.
- [x] ui-service tests reject removed methods;21 tests pass.
- [x] Kotlin-generated valid/hostile archive paths and UTF-8/GBK names agree with Rust.
- [x] Local server exercises1MiB ranges, failed source, validation fallback, ETag resume and cancel cleanup.
- [x] Digest aliases, single flight and conflicting existing targets tested.
- [x] Missing Chinese index leaves fake-platform import usable; bundled identity queries are read only.
- [x] Isolated release MCP opens import and creates an empty pack with no Java child.
- [x] Finished Rust task remains visible after more than1minute, with no Java child (carried from GJ4a).

## Implementation completion before acceptance

- [ ] Finish scoped FileDownloader behavior parity, including adaptive scheduling/cooldowns and retry classes.
- [ ] Complete scoped cache/catalog test port; review search paging and platform failure corner cases.
- [x] Kotlin-generated bad URL/SHA-1/duplicate-target archives and actual-adapter catalog request assertions added.
- [ ] Verify foreground pack-list refresh after a completed import; initial background MCP run required restart to show the tile.

## Live acceptance

- [ ] Import a real CurseForge archive and a real Modrinth archive; no Java child at any point, hashes correct, overrides applied.
- [ ] Unavailable files show the confirmation list and import continues without those files.
- [ ] Only selected optional files are installed.
- [ ] Reimport uses complete cache entries without downloading.
- [ ] Cancel during a real download: no pack/staging/partial output; cache contains only complete entries.
- [ ] Mirror preference on/off and mirror unreachable use the expected fallback.
- [ ] Record ranged and single-stream large-file times.
- [ ] Record large-import working set and import time against the Kotlin J3 path.
- [ ] Foreign data.db shows no error dialog and create/folder import still works (carried from GJ4a).
- [ ] Service crash leaves a running Rust import/task unaffected (carried from GJ4a).
- [ ] No Kotlin tasks remain (replaces the carried Kotlin-task cancel check).

## Owner-only checks

- [ ] Rust-imported pack starts and enters a playable world.
- [ ] Compose lists the Rust pack and reuses Rust cache; Rust likewise reuses Compose cache.
- [ ] Real large pack and minimized-window progress/cancel behavior (carried from GJ4a).
- [ ] Cancel button appearance remains acceptable.
- [ ] GJ4b approved only after remaining implementation and validation items above are resolved.
