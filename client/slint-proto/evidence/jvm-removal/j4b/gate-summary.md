# GJ4b gate summary

Status 2026-11-10: **J4b partially implemented; GJ4b not reached/passed.** No commit, no J5.
This records implemented code and the actual verification boundary, not an owner
acceptance claim. Some planned parity and live checks remain open below.

## Changes

Rust now owns archive import as well as the J4a sources/tasks. New modules:
`download.rs`, `content_store.rs`, `digests.rs`, `catalog/`, `download_policy.rs`, `import/archive.rs`
and `import/resolver.rs`. The UI keeps the existing installer/task JSON shapes.
Screen generations discard stale archive results, cancellation covers network
waits, and successful resolution is cached by path/size/modified time.

Downloader uses ordered sources, five attempts, metadata/range probes, a16-worker
1MiB queue, single-stream fallback, per-source headers, bytes/size/speed and
identified same-source resume. Cache uses Compose's digest.suffix layout in dlc,
SHA-1/SHA-256/Murmur2 (SHA-512 additionally available), digest coordination,
32 network/verify/copy limits, verification before rename, aliases and hard links.
Catalog exposes metadata, search, details, files, matching, updates, dependencies,
changelog and download resolution, with both platform adapters and TTL caches.
Chinese identity data is opened read only at the approved development path.
Archives support CurseForge/Modrinth, UTF-8/GBK entry names, optional/unavailable
files, layered overrides, unchanged-archive checks, safe paths and20GB extraction.

J4a choice(a) is removed. Protocol v5 removes service installer/tasks methods,
events, task stop/idle dependencies and the lazy tasks.subscribe prelude. Play
and console remain in Kotlin; Compose implementation remains intact.
Original changed service sources/tests are preserved under
`DEL/j4b-20261110-service-import/`; the obsolete temporary validation script is
preserved under `DEL/j4b-20261110-validation-script/`. DEL is ignored by Git.

## Automated validation

All builds used Windows PowerShell/Cargo or the client/ui wrapper. Project IDE
MCP at127.0.0.1:64522 refused connection; this is why Gradle used the fallback.
WSL Windows interop needed sandbox escalation. No WSL project build was used.

| Check | Result |
|---|---|
| Rust workspace tests | bin87 passed/1 ignored, lib11 passed, renderer63 passed/1 ignored; final source rerun passed |
| Clippy all targets, -D warnings | passed after cache and catalog adjustments |
| Windows release | passed before last adapter URL fallback; final rebuild still running at handoff |
| Kotlin J4bArchiveParityTest |3 passed; generated the shared real ZIP corpus |
| Kotlin J4bCatalogParityTest |2 passed; actual adapters check shared request/response pairs |
| ui-service:test |21 passed,0 skipped; reduced from27 by removing6 installer/task tests |
| client-core:test |244 tests, same4 baseline failures; +3 J4b parity tests |
| ui-service:installDist |passed separately after combined task hit baseline failures |
| macOS ARM64 zigbuild check |passed with2 pre-existing updater registry dead-code warnings |
| Linux x86_64 zigbuild check |passed with the same2 warnings |

The first combined Gradle run also found a missing getLocalPackDir extension
import in the service dependency rewrite. Fixed it, then ui-service tests and
installDist passed. No baseline tests were rewritten. Rust follows current Kotlin
runtime eligibility (including Fabric1.20.1), rather than the obsolete scanner/
archive test expectation that Fabric be refused. Other baseline failures remain
ModpackUploadRuntimeGuardTest and MediaProcGameClasspathTest.

Shared j4b.json plus Kotlin-built ZIPs cover valid/hostile paths, UTF-8/GBK names,
SHA-1 and Murmur2 whitespace, layout and fake catalog responses. Rust local HTTP
checks cover ranges, no-range/cache path, failed first source, ranged validation
failure to single stream, ETag resume, cancellation/partial cleanup, digest aliases,
existing-target conflict and same-digest single flight. Additional tests cover
read-only bundled identity queries, archive override install, changed archive
rejection, optional exclusion, task cancellation and overlapping file lookups.
RealCatalogSmoke remains ignored and contains a real API query when explicitly run.

One test-server issue was diagnosed: Windows accepted sockets inherited
nonblocking mode, causing false IncompleteMessage failures. The test server now
sets accepted sockets to blocking mode; actual1MiB Range requests are asserted.

## Isolated release Slint MCP run

Started with cargo preview-run and isolated RDI_DATA_DIR at ignored `live/run`.
Only the previous designated isolated test-account credential store was reused;
no owner data directory was read or written. All UI interaction was Slint MCP,
no native mouse/keyboard automation. Preview processes created here were closed
through MCP to release the executable lock.

| Check | Result |
|---|---|
| Sign in, open import |real login succeeded; import rendered with no Java child |
| Create empty NeoForge1.21.1 |task reached 安装完成 · 100%; manifest has UUIDv7, V211/neoforge, source Empty |
| Finished task history after more than1minute |same completed task visible; no Java child |
| Pack persistence across restart |restart list has one tile (label initial1); manifest remains readable |
| Immediate list refresh |first background MCP run still showed empty-list text after completion; restart shows the pack. Foreground refresh needs owner recheck; not claimed passed |
| Archive import through native picker |not exercised; MCP cannot select in the native file dialog |

Single working-set samples: launcher PID96220,545894400 bytes on import,
547991552 after task completion, Java children0 at both samples. These are not
large-import measurements or a controlled memory benchmark. No Rust-vs-Kotlin
import timing or range-vs-single timing was measured.

## Remaining scope and validation gaps

- Full test-for-test port of CatalogDomainTest/PlatformAdapterTest and all scoped
  ClientContentStoreTest/FileDownloaderTest cases is not complete. Existing tests
  prove only the cases listed above; no full behavioral parity claim.
- Adaptive EWMA/active-chunk scoring and URL/host cooldowns have now been added
  with a focused scheduler test. Retry validation classification, range retry budgets,
  dynamic mirror preference, DTO shape fallback, local-first search buffers,
  channel paging and cache failure fallback were subsequently added. Exhaustive
  parity review is still incomplete. Do not treat the downloader as a complete FileDownloader port yet.
- Catalog cross-platform search paging/ranking, failure propagation and details/
  dependency corner cases need comprehensive ported-test review. API coverage
  alone does not establish D3 parity.
- Real CurseForge/Modrinth download/import, missing-file confirmation, selecting
  optional files, mirror on/off/unreachable, cache interchange with Compose,
  cancellation during a live pack download and service crash during a Rust task
  were not exercised in the UI.
- No J3 Kotlin baseline import-time comparison, large-file range timing, or
  large-import working set. Native minimized/focus behavior remains owner-only.

Do not pass GJ4b based on the current automated results. See owner-checklist.md.

Final source review added4 catalog domain tests (file channel paging/cache,
dependency cycles/requirements, cross-page search uniqueness, details/changelog/
release-to-beta updates). Cache tests also cover Murmur-only requests creating
SHA-1 aliases and verified temporary fallback when the cache is unwritable.
Shared catalog requests were checked by Kotlin adapters: Modrinth omits the
serialized default algorithm field; fixture and Rust request now match.
The full original Kotlin test suites have not been ported one-for-one.

Final scope accounting: Rust bin87 passing is +25 versus the accepted J4a62.
There is1 new ignored external API test; renderer retains its1 ignored GPU capture.
Kotlin client-core244 is +3 from J4a241; service21 is -6 from J4a27.
The original service snapshots/tests remain preserved under DEL before removal.

Handoff boundary: implementation is partial and must be continued within J4b.
The remaining implementation checklist is not an owner-only acceptance gate.
Do not start J5. All changes are unstaged/uncommitted. Final Rust tests/Clippy
pass after the last adapter change; cross checks passed with the existing2
warnings. The latest release rebuild must be checked before claiming final
binary parity with source. The isolated preview was closed through MCP.

## J4c follow-up (2026-11-22)

J4c's live run found that the downloader rejected any content type containing `json`;
Kotlin only rejects `application/json`. forgecdn serves some zips as
`application/x-amz-json-1.0`, so those downloads failed. Fixed in
`download::json_response`, with a test. The same downloader then installed four real
room packs (up to 431 mods, 297MB archives) in the J4c live run; see
`../j4c/gate-summary.md`.
