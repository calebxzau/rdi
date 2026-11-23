# U4c2 gate summary (c2a 后台/存档/回档, c2b 模组)

2026-11-23. Owner settled D1-D5 the same day and asked to implement directly, without a plan file.
c3 (导入存档, Rust rewrite of anvilrw) is not part of this batch.

## What was built

| File | Content |
|---|---|
| `src/api/sse.rs` | Server-sent events reader (Ktor `sse {}`), idle timeout, server error message kept |
| `src/rooms/backend.rs` | 后台: start/restart/stop/force-stop, command, log stream, 5000-line console buffer |
| `src/rooms/world.rs` | 存档: access rules, reset, download status/hints, packing via SSE, download + sha1, tar.zst install into `saves` |
| `src/rooms/rollback.rs` | 回档: DTOs, banner, day groups, texts, request/cancel |
| `src/rooms/mods.rs` | 模组: areas, disable/enable, extra remove/side, add filter, jar mod id |
| `src/rooms/github.rs` | GitHub-only 附加Mod (D3): repo URL, releases, jar download, mod id, sha256 |
| `src/room_pages_ui.rs`, `src/room_pages_mods.rs` | Wiring to the new `RoomPages` global |
| `ui/room_pages.slint` | 后台, 房间存档管理, 回档, 模组 pages and their dialogs |
| `ui/rooms.slint` | Row 模组 / 存档 / 后台 under the members (文件 stays cut) |
| `src/tasks.rs` | `Context::finish` so a task keeps its own success text |

No server or `common` change; no `modpackServiceTest` needed.

## Decisions applied

- D2: no drag; click rows to select, then 停用 / 启用 / 删除.
- D3: only the GitHub part of the advanced dialog. The 附加Mod button opens it directly, because
  Compose's plain click goes to 资源-模组 (batch e, not ported). Compose needs Shift+click.
- D5: download with the pack missing opens the existing install prompt; once installed the download
  starts by itself (also when the pack was already installing).

## Checks

- `cargo clippy --all-targets -- -D warnings` clean; `cargo test --workspace` green
  (227 in the launcher crate).
- Live on the local master with the test account (`cargo preview-run`, isolated `RDI_DATA_DIR`,
  broken service command): details row; 后台 as member (启动 only) and owner (all five buttons),
  log stream, stop confirmation, server refusals, command dialog; 存档 hint, disabled download with
  the server's reason, reset with name gate; 回档 empty state, points list grouped by day,
  confirmation text, request → pending banner, cancel; Escape closes dialogs then pages;
  模组 catalog names/intros/icons, search, disable/enable (Mongo checked), side change and the
  last-side refusal, removal, GitHub releases list, selection check, add (Mongo + server download).
- Layout bugs found live and fixed: page row at the page bottom, rollback list collapsed, usage
  block stretched, side options spread out.

## Not verified

- A full save download (pack → download → extract) and the D5 install chain: the local master has
  no packed save. Unit tests cover sha1, install, folder names; the SSE packing path is covered by
  mock-server tests only.
- Running-room cases: start success, live log lines, command reply, 回档 while running
  (auto-refresh), Done/Failed banners and 撤销这次回档.
- Real server (read-only): not run this time.
- `cargo-zigbuild` mac/linux checks: not run.

## Local state left behind

- Local Mongo `rdi5skypro`: room `u4c2a测试房间` (`6b03e9dc2f7aba7b5193a6ad`) owned by the test
  account, pack 落幕曲 1.5.1, one disabled mod, ModMenu as an extra mod.
- `server/master/run/host2/6b03e9dc2f7aba7b5193a6ad/rdi/backup/`: a hand-written rollback index.
- `evidence/ui-port/u4c2a/live/run/` holds the launcher data of the live run (with saved login).
