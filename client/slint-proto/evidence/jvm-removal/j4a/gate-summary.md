# GJ4a gate summary

Status 2026-11-10: **GJ4a passed.** The owner reported the owner checks done
after the review fixes below; see `owner-checklist.md` for which checks were
moved to GJ4b. Automated checks pass except the four unchanged client-core
failures. No J4b or J5 yet.

## Changes

Rust owns launcher-lifetime tasks, recorded-folder scanning, the import state
machine, empty-pack creation and folder import. Blocking work uses spawn_blocking;
screen results carry a generation. Progress renders at most once per 34ms.
Task IDs and pack IDs use UUIDv7. Dedupe, hierarchical snapshot rows, cancel,
Chinese failure reporting, unique-name commit and bounded shutdown are implemented.
Cancellation checks run during traversal and each 64KiB copy chunk. Cleanup finishes
before a Rust task becomes terminal. Existing copied options.txt is retained.

Bridge choice 4.8(a): Rust owns the screen; archive selection opens the existing
Kotlin editor and mirrors it until back/close/submission. Archive tasks remain in
Kotlin. Lists merge by createdAt (Kotlin task IDs are UUIDv4), watch/cancel routes
by ownership. Protocol v4 adds tasks.cancel and createdAt. Rust task history
survives a child crash or idle stop. Import/task screens do not block idle stop;
an active Kotlin archive editor and console still do. Logout checks the task
manager directly, including the interval before the next sampled UI update.

No Compose implementation was changed. data.db is opened read only; missing,
foreign or incompatible files log a warning and return no candidates.

## Automated validation on Windows

| Check | Result |
|---|---|
| cargo test --workspace | bin 61 pass (+7); lib 11; renderer 63 pass/1 ignored |
| cargo clippy --workspace --all-targets -- -D warnings | pass after final direct logout guard |
| cargo build --release | pass; cargo preview-run rebuilt release with MCP and ran |
| ui-service:test | 27 pass (+cancel handler, +shared hierarchical snapshot) |
| client-core:test | 241 tests (+3 shared import fixture tests), same 4 failures |
| ui-service:installDist | pass in separate invocation after client-core failure |
| cargo-zigbuild check --release --target aarch64-apple-darwin | pass; two existing updater registry dead-code warnings |
| cargo-zigbuild check --release --target x86_64-unknown-linux-gnu | pass; same warnings |

IntelliJ MCP was unavailable (connection refused on port 64342); Gradle used the
Windows module wrapper. Initial WSL interop was denied; Windows commands succeeded
with sandbox escalation. Read JUnit XML: no NO-SOURCE or skipped result counted.
Rust/Kotlin shared j4a.json checks manifests/rejections, exclusions, options output,
unique names and hierarchical task snapshot fields. Float snapshots compare
numeric f32 values; their decimal JSON spelling differs from Kotlin's Float.

The scanner and archive-reader baseline failures expect Fabric 1.20.1 to be
rejected, but current supportsRuntime allows it. Rust follows current Kotlin
implementation and the new parity fixtures, including both Knot entrypoints.
The other known failures are ModpackUploadRuntimeGuardTest and
MediaProcGameClasspathTest. No baseline tests were rewritten.

## Isolated release MCP run

Run folder: ignored live/run. Started with cargo preview-run; dedicated test
credentials supplied in environment, isolated RDI_DATA_DIR and Kotlin user.dir.
Interaction used Slint MCP over Windows HTTP; no native mouse/keyboard scripts.

| Check | Result |
|---|---|
| Login/menu | successful test-account login; no child started |
| Missing data.db import | no candidate/error dialog; create remained usable |
| Create empty 1.21.1 NeoForge | task showed 安装完成 · 100%; correct local manifest |
| Prepared schema 5 data.db | fixture candidate listed from external/versions/fixture |
| Folder import without saves | mods/fake.jar present; saves and logs absent |
| Folder import with saves | level.dat present; logs absent; collision name fixture (2) |
| Cancel 8GiB sparse-file copy | task showed 任务已取消; no fourth pack or .installing folder |
| Child/process sample | launcher PID 33664, Java children 0, working set 495697920 bytes |
| Archive selection | MCP clicked 选取...; native file dialog cannot be accessed by Slint MCP; preview/install/cancel not validated |

The working set is one sample, not a benchmark. No timing comparison or game
acceptance is claimed. Automated tests also cover a 512MiB mid-copy cancel, database
version/missing/managed-folder rejection, path escape and copied options retention.

## Remaining validation

Archive bridge/mixed Kotlin task list/cancel needs an interactive file selection
or a suitable MCP-accessible input mechanism. Finished-history after an idle minute,
foreign-database UI, direct logout during active copy, and service crash while a
Rust task runs were not exercised live. Native file-dialog selection cannot be
replaced by mouse scripts under the approved plan. Owner checklist also retains
actual game play, Compose interchange, real large packs, button appearance and
minimized behavior. Do not pass GJ4a or start J4b from the automated results alone.

Final source check: git diff --check passed. Final Rust test/clippy rerun passed
after the direct logout guard. Release/live and cross checks preceded that small
guard change; those binaries were not rebuilt afterward. The isolated preview
remains open at its native archive file dialog for owner review.

## Review fixes 2026-11-10

After review, before owner acceptance:

- The cancel button uses the stop glyph (`\u{f04d}`) in the task detail and the
  task list. The × glyph stays for 隐藏到后台 and close. With a cancel button the
  detail title is 40px narrower, so a long title no longer runs under it.
- A panicking import worker now ends its task as failed with 任务意外中断, instead
  of staying running and blocking logout and exit. The commit lock and the task
  list tolerate a poisoned mutex. New test `panicking_worker_fails_its_task`.
- New code in `packs.rs`, `app.rs` and `tasks.rs` was expanded to the rustfmt
  layout; existing code was not reformatted. The unused `committed` flag in the
  installer was removed. No behavior changed apart from the two items above.

Rerun on Windows: `cargo test --workspace` bin 62 pass, lib 11, renderer 63 pass/1
ignored; `cargo clippy --workspace --all-targets -- -D warnings` passes. The
release binary was not rebuilt (the isolated preview holds it), so the open
preview still shows the old × glyph; rebuild after closing it.
