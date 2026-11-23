# GU4c1 gate summary

Status 2026-11-23: **implemented as `U4C1-PLAN.md` plus the owner's follow-up decisions;
automated checks pass; live checks done on the real server (read-only) and on a local master
(all writes).** No commit, no push, nothing staged. Built on top of the uncommitted J5a work
without reverting or reformatting it.

## Owner decisions after the first report (2026-11-22)

1. The agent starts the local master itself (done: `server/master` `gradlew.bat run`, after the
   owner started PostgreSQL; test accounts inserted into the local Mongo as the owner said).
2. Right click on the 多人房间 tab no longer opens anything (removed from `glass_tab_bar.slint`
   and `app.slint`).
3. Plain members no longer see 踢出成员 (unlike Compose): kicking needs the owner or an admin
   (`permissions.rs::can_kick`). Screenshots are not kept in the repository: they were moved to
   `DEL/u4c1-screens-20261123/` (ignored by Git) after testing.

## What was built

New, no Slint types (callable by the planned launcher MCP):

| File | Content |
|---|---|
| `src/rooms/model.rs` | `HostDetail`, `Member`, `Role`, `RoomPack`, `BaseWorld`, `BaseWorldBinding`, `Friend`, `CreateRequest`, `OptionsRequest`, `DeleteRequest`; serde defaults for every Kotlin default the server omits |
| `src/rooms/requests.rs` | One function per route of the plan's table; ids are checked before they go into a path; QQ is percent-encoded like `encodeURLPathPart` |
| `src/rooms/form.rs` | `HostCreateViewModel` rules as `FormState` (create/edit, terrain, template binding, friends, submit, 更新整合包); generations drop stale template and friend replies |
| `src/rooms/game_rules.rs` | Rule table from the fixture; `GameRuleModal` draft logic (override, integer field, reset, change list, display values) |
| `src/rooms/permissions.rs` | `is_owner`, `can_manage`, `can_view_members`, member pane (`can_kick`, menu entries, confirm wording), sort by role level |
| `src/rooms_ui.rs` | Controller on the `Rooms` Slint global (plaza pattern: `action`, epochs, channel); navigation deferred to `tick`; AVIF option icons decoded once with `src/avif.rs` from `client/ui/assets` |
| `ui/rooms.slint` | `Rooms` global, `RoomDetails`, `RoomForm`, `RoomDialogs` (guide, confirm, alert, delete, invite, friend, custom terrain, game rules + save confirm, map templates, member menu) |
| `client/ui/src/test/.../HostRequestFixtureTest.kt` | Writes/checks `protocol/fixtures/game_rules.json` and `room_requests.json` |

Wiring in shared files (kept small): `api/server.rs` (`put`, `delete`, `post_empty`, a test
reader that waits for the body), `navigation.rs` (`RoomDetail`, `RoomForm`), `shell.rs`
(routes, Rooms screen index, Escape dismisses room dialogs, no menu glass under room pages),
`app.rs` (controller, right click on a room tile, room list ids, `/host/my` reload after
create/delete/leave), `plaza.rs` (`pack`, `create-room`), `main.rs` (modules), `ui/app.slint`
(plus button, guide, screens, dialog layer, focus), `ui/plaza.slint` (创建房间 on the version
page), `ui/host_list.slint` (row click opens details), `ui/glass_tab_bar.slint` (right click removed).

## Automated validation (Windows pwsh; IntelliJ MCP not connected)

| Check | Result | Log |
|---|---|---|
| `cargo clippy --all-targets -- -D warnings` | pass | `logs/u4c1-clippy.log` |
| `cargo test --workspace` | 62 + 11 + 164 pass, 2 ignored (existing GPU tests); 29 new `rooms::` tests + 1 new `api::server` test | `logs/u4c1-test.log` |
| `cargo build --release` | pass; the later Slint/permission changes built in release through `cargo preview-run` | `logs/u4c1-release.log` |
| `cargo-zigbuild check --release` aarch64-apple-darwin / x86_64-unknown-linux-gnu | pass / pass; same 2 existing `java_runtime.rs` dead-code warnings as J5a | `logs/u4c1-zig-*.log` |
| Kotlin `HostRequestFixtureTest` (client/ui, check mode) | 2 tests, 0 failures | `logs/kotlin-fixture-check.log` |
| `modpackServiceTest` | not run: no server change | |

## Live checks (cargo preview-run, isolated `live/run`, Slint MCP over HTTP)

`ui-service` was never started in any run: the service command points to a missing file and no
log has a start attempt. Logs: `live/preview-run1.log`, `preview-run2.log` (real server),
`preview-run3-local.log` (local master).

Real server, read-only (test account): guide and 立刻前往; create form (defaults, icons, custom
terrain error, 16 templates, friend errors, game rules dialog: toggle, invalid integer, blank =
default, confirmation list, reset) without submitting; details as owner / member / outsider;
member menu and kick confirmation (cancelled); delete dialog name gate (cancelled); edit form
and 更新整合包 confirmation (cancelled); reload after 返回 (D7); pack card; hid copy; right click
on a home tile and a click in 房间列表.

Local master (`http://127.0.0.1:65231`; test account plus two friend accounts inserted into the
local Mongo), every write checked in the UI and in Mongo:

- Create: 超平坦 with a friend → 成功 dialog, back on the version page, tile on the home grid;
  stored `minecraft:flat`, 困难, 生存, whitelist, version 2, members OWNER + MEMBER.
- Create with a template and a changed rule on a pack that already had my room → server
  refusal 创建失败: 同一个整合包只能创建一张房间.
- Create on a version bound to a **required** template (binding set in the local Mongo for the
  test, removed afterwards): 此版本强制使用指定的地图模板, cards locked, 模板·567 preselected;
  stored `baseWorldId` = the template and its level type `gfsd:j`.
- Friend lookup: own QQ 不能邀请自己，请添加其他好友; same friend twice 已添加这位好友.
- Members: invite (已发送邀请), unknown QQ (无此账号), already a member (该用户已是成员), set admin
  and remove admin (已更新), kick (已踢出), transfer (owner badge moves, 删除房间 disappears;
  the server makes the old owner an admin), leave refused with two members (房间至少需要保留2名
  成员，无法退出房间), leave with three → back, the tile disappears.
- Edit: 简单 + 火焰蔓延 off → 设置已保存, back on the reloaded details; stored difficulty 1 and
  `doFireTick=false`; unchanged custom terrain not sent. 更新整合包 → 已提交更新.
- Delete a version-2 room by typing its name, and a version-1 room (version set in Mongo for the
  test) with 同时删除关联存档; both gone.
- Plain member (owner decision 3): own chip offers only 退出房间; other chips open nothing.

Bugs found and fixed during the live runs: translucent dialog card; Escape swallowed by the
hidden shared dialog; menu grid glass under room pages; elided checkbox labels; one-line rule
names; overlapping 请输入整数; focus left on a hidden page after 返回.

Observed, not changed: the home grid only refreshes while the window is active (existing
design), so a left room disappears once the window is focused or the tab is switched. The
launcher window was found minimized once during the run; nothing in this batch minimizes it
(J5a noted the same).

## Not verified

- Compose side-by-side screenshots: not taken (the Compose launcher would use the owner's own
  `client/ui/run` data and account).
- Loading and error states of the details page were not forced live (unit-level only).
- Idle cost of the rule dialog was not measured (it uses `ListView`, so only visible rows exist).

## Findings (plan section 8 and checks)

- `AllGameRules` has **60** rules, not 61. Both tests assert 60.
- `BaseWorldCard` shows the name and the owner's head and name, no picture. Changed at the
  owner's review (below): Slint now shows the author too.
- Slint had no clipboard helper; a hidden read-only `TextInput` with `select-all()` + `copy()`
  does it, no new crate.
- `ui/host_list.slint` had no click; a row click now opens details (as Compose's room list).
- JSON ids: ObjectIds are 24-hex strings, UUIDs plain strings on the wire; `DeleteDto(false)`
  is sent as `{}` (`serdesJson` does not encode defaults). In Mongo, UUIDs are BSON binary.
- Kept from Compose: the friend add button uses Compose's glyph `\u{f012c}` (a check mark in this
  font); 自定义 is prefilled with the current terrain.

## Local state left behind

- Local master still running (`server/master` Gradle `run`, log `server/master/build/u4c1-master.log`).
- Local Mongo `rdi5skypro`: three test accounts (test account with QQ 9900000101, u4c1好友甲
  9900000102, u4c1好友乙 9900000103) and the room 11111的1.21 now owned by u4c1好友甲. The
  temporary template binding on 1.20原版 1.0 was removed again.

## Owner review (2026-11-23)

- **GU4c1 passed.** Owner decisions on the checklist: map template cards show the author like
  Compose; the local test accounts and the room 11111的1.21 stay in the local Mongo.
- Author on template cards: `RoomWorld` has `owner-name`/`owner-head` instead of `detail`; the
  author is fetched through the same `/player/{uid}/info` cache as members (「…」 while loading,
  「未知玩家」 on failure). `BaseWorld.ownerId` is a UUID while the route takes an ObjectId, so it is
  converted as Kotlin `UUID.toObjectId` does (first 12 bytes, last 4 must be zero); the first live
  try showed 「未知玩家」 because of this. Live on the local master: both templates show davickk
  with the head. clippy and `cargo test --workspace` pass (3 new tests).
