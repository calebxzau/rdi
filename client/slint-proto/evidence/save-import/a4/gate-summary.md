# A4 (导入单人存档 screens) — gate GA4 summary

2026-11-24. Owner approved: one batch, one gate; auto-detect the game exit with 「我已退出游戏」 as fallback;
single scrolling page like Compose.

## What changed

- `src/save_import/flow.rs` (+ `flow_tests.rs`): the steps of Kotlin `HostSaveImportViewModel` without I/O
  (owner/v2 check, first precheck from `level.dat`, role choice, reuse, summary gate, mapping suggestions,
  unique targets, dual identities, review/precheck, confirmation lines, cleanup).
- `src/save_import_ui.rs` + `ui/save_import.slint`: the page (`RoomPages.screen == 5`, `Screen::RoomSaveImport`),
  the QQ dialog and the cleanup dialog; one flow per room for the launcher's lifetime; the marking game's end is
  seen through `Sessions::views` and the marks are read automatically.
- Entry: 「上传自己的存档」 on 房间存档管理 for the owner (and dav), as Compose `canImportSave`.
- `src/play/room.rs` `marking_play_identity`: **bug fix found in the live run** — the marking launch put the RDI
  account into `-Drdi.play`, and the RDI core's `mUser` mixin replaced the marking identity with it, so the game
  saved the copy's `Data.Player` as the RDI account and `check_identity` refused the copy. Now `-Drdi.play`
  carries the marking identity. **Kotlin `markingLaunch` has the same bug** (not fixed there).
- Small improvement over Kotlin: a member warning about an RDI player of the save shows that player's name, not
  the bare ObjectId.
- `ui/room_pages.slint`: `PageCard`, `LabeledButton`, `Busy`, `DialogCard`, `DialogTitle` exported for reuse.
- `src/save_import/mod.rs`: the A3 `allow(dead_code)` is gone; two unused DTO fields and one test field carry a
  local allow.

## Checks

- `cargo clippy --workspace --all-targets`: clean. `cargo test --workspace`: 328 + 62 pass (3 + 1 ignored live tests).
- `cargo-zigbuild check --target aarch64-apple-darwin`: ok (two old `never used` warnings in the updater code).
- Owner accepted GA4 on 2026-11-24 and asked for the commit.
- 15 flow tests port the 12 Kotlin `HostSaveImportViewModelTest` cases plus cleanup, labels and warning names.

## Live run (local master, test account 11111, room 「A3导入测试」 6b05268151bb32ac191fe692, pack 1.20原版 v1.0)

Data folder `evidence/save-import/a4/live/run` (game files shared through junctions, `setup.ps1`); launcher
started with `preview.ps1`; driven through the Slint MCP and the in-game RMCP.

| Step | Result |
|---|---|
| 房间存档管理 → 上传自己的存档 | page opens, owner check passes |
| 选择存档文件夹 | native dialog; the owner clicked 选择文件夹 (it ignores posted messages) |
| first precheck + roles | sample save 海上集装箱: 2 roles; davickk478: dev1 (单机存档主人), dev2 |
| 海上集装箱 marking launch | copy made, game starts in marking mode, then **the world does not load**: the save needs mod `blueprint` (biome source `blueprint:modded`) that pack 1.20原版 lacks — test data, not a code fault |
| 终止游戏 | exit seen automatically → summary 「还没有标记任何区块」, 下一步 disabled |
| 清理导入副本 | dialog → copy folder and record removed |
| davickk478, first launch | game played as 11111 → identity check failed after exit (the bug above) |
| relaunched launcher, 继续使用上次标记的副本 | refused with 「副本中的角色身份发生变化，请重新创建副本」, selection kept |
| 用所选角色创建新副本 | `davickk478-RDI导入-2`, game user dev1, LAN button disabled, `/syncchunk add` via RMCP, save and quit → summary 3 chunks (主世界), 1 player outside |
| mapping | dev1 read-only 已是RDI玩家 + 房主; dev2 未知; 按QQ号查找 9900000101 → 11111 with head |
| confirm | 3 lines incl. server member warning; 开始导入 → task 「存档已导入房间A3导入测试」 |
| server world | `playerdata`/`stats`: dev2 `68b314bb-…-b5ed00000001` → `6b030f1c-…-ba1a00000000` (11111); dev1 unchanged; copy `-2` removed after Ready |

## Not covered / open

- The page layout fix for card heights (body height) was made after the run; not seen live.
- Dual-identity chips and 对应到我 were only unit-tested (the live save had no case for them).
- The task list row stayed at 95% next to 已完成 (task panel, not A4).
- A3 `delete` keeps `rdi-save-import-staging/prepare-*/backup` (same as Kotlin).
- The first, identity-broken copy `davickk478-RDI导入` is still in the live data folder and its record file.
- The game shows the role name only when it is a valid game name (Kotlin rule); `单机存档主人` falls back to the
  RDI account name.
