# U4 batch c1 plan: create a room, room details, members

Drafted 2026-11-22. Status: **scope and every decision in section 3 settled by the owner
on 2026-11-22. Handed off for implementation. Stops at gate GU4c1.**

Read first: the root `AGENTS.md`, `UI-PORT-PLAN.md` (sections 1, 2 and 7),
`JVM-REMOVAL-PLAN.md` section 1, `J5-PLAN.md` section 5 (rules that carry over).

Written for an agent with no prior context. User-facing text says 房间, never
container or server process.

## 1. Goal

A player who only uses the Slint launcher can open a room of their own and manage who
plays in it. Today 「创建房间」 on the home screen and a right click on a room tile only
write a log line (`ui/app.slint:293`, `src/app.rs:414`).

Everything in this batch is a server request. It is written in Rust against
`src/api` and never starts the `ui-service` JVM.

In scope:

| Slint screen | Compose source | Notes |
|---|---|---|
| Create-room guide dialog | `UnifiedHostList.kt` (`CreateRoomGuideDialog`) | Opened by a new plus button on the home screen (D1) |
| Room form: create | `HostNewCreateScreen.kt`, `HostCreateViewModel.kt` | Entered from a modpack version |
| Room form: edit | same files, `hostId != null` | Entered from 「设置」 on the details page; includes 「更新整合包」 |
| Game rule dialog | `comp/GameRuleModal.kt`, `common/model/.../GameRule.kt` | Used by both form modes |
| Map template dialog | `comp/BaseWorldSelectionModal.kt` | Create mode only |
| Room details | `HostInfoScreen.kt` (`HostInfoScreen`, `HostDeleteDialog`) | Title, pack card, ids, 设置, 删除房间 |
| Members | `HostMembersScreen.kt` (`HostMembersPane`) | A section of the details page, not a screen |

Out of scope, each waits for batch c2 unless noted:

- 模组 (`HostModsScreen`, extra mods, disabled mods), 存档, 后台 (start, stop, restart,
  commands, log), 区块回档, 导入存档.
- 文件: cut for good (`UI-PORT-PLAN.md`, "Cut feature scope").
- Start and stop buttons. In Compose they live on the 后台 page, not on the details page.
  A left click on a room tile keeps starting the room as it does today.
- Host2 rooms: Compose shows 「该房间类型暂不可用」. Slint only ever sees Legacy rooms.
- Any server or `common` change. If one seems necessary, stop and ask.

Entries to pages that are not ported yet are left out, not shown as stubs
(`UI-PORT-PLAN.md`: "drop the entry instead of showing a stub"). The details page
therefore has no 模组/存档/后台 row until c2 adds it.

## 2. Code facts

Server routes (`server/master/.../host/HostRoute.kt`, `PlayerService.kt:147`), all with
the login token:

| Call | Body | Who | Used for |
|---|---|---|---|
| `POST /host/v2` | `Host.CreateDto` | any player (server limit: 最多只可创建3张房间) | create |
| `GET /host/{id}/detail` | | any; `members` is empty for non-members | details, edit form |
| `PUT /host/{id}/options` | `Host.OptionsDto` | admin | save edit |
| `POST /host/{id}/update` | | admin | 更新整合包 |
| `DELETE /host/{id}` | `Host.DeleteDto {deleteWorld}` | owner | delete |
| `GET /host/{id}/members` | | invited member | member list |
| `POST /host/{id}/member/{qq}` | | admin | invite |
| `DELETE /host/{id}/member/{uid}` | | admin | kick |
| `PUT /host/{id}/member/{uid}/role/{ADMIN\|MEMBER}` | | owner | set or remove admin |
| `POST /host/{id}/transfer/{uid}` | | owner | transfer |
| `PUT /host/{id}/quit` | | member | leave |
| `GET /player/by-qq/{qq}` | | any | find a friend, returns `RAccount.Dto {id, name, cloth}` |
| `GET /modpack/{id}/detail` | | any | the version's `baseWorld {id, required}` |
| `GET /baseworld?myOnly=false` | | any | ready map templates |

DTOs are in `common/model/.../Host.kt` (`CreateDto`, `OptionsDto`, `DetailVo`, `Member`,
`DeleteDto`), `Role.kt` (`OWNER 0, ADMIN 1, MEMBER 2, GUEST 10`) and
`calebxzau/.../BaseWorld.kt`. `Host.MAX_MEMBERS` is 20.

Rust today:

- `src/api/server.rs` has `get`, `get_query` and `post` only. PUT and DELETE are missing,
  and so is a POST that sends no body.
- `src/plaza.rs` is the pattern to follow for a browse flow: one controller struct, an
  `action(name, value)` callback, epoch counters to drop stale replies, a channel drained
  on the UI thread.
- `src/plaza.rs` already parses a version's `base_world` and shows the version page
  (`pack-screen == 5`) with an 安装 button; `ui/plaza.slint` (`ModpackDetails`).
- `src/images.rs` `HeadCache` and `GET /player/{uid}/info` give names and heads.
- `src/avif.rs` decodes AVIF. The option icons are AVIF files in
  `client/ui/assets/src/main/resources/assets/icons/` (`difficulty_*`, `gamemode_*`,
  `worldtype_*`).
- `ui/components/shared.slint` has `SharedDialog`, `Toast`, `LoadingState`, `ErrorState`;
  `ui/account_menu.slint` is the pattern for a small anchored menu.
- `src/app.rs` `RoomDetail` and `room_pack` already read `/host/{id}/detail` for play.
- `Client::uid()` and `Client::is_dav` exist.

Kotlin tests that state the form rules:
`client/ui/src/test/.../viewmodel/HostCreateViewModelTest.kt` and `HostCreateFriendsTest.kt`.

## 3. Owner decisions (settled 2026-11-22)

| # | Question | Decision |
|---|---|---|
| D1 | Where does creating start? | **Decided by the owner.** (a) The home screen's 多人房间 tab gets a plus button in the row of round buttons under the room grid (`home-shortcuts` in `ui/app.slint`, today 房间列表 and 衣柜). It opens the three-step guide (创建多人房间: 1.前往整合广场 2.选择喜欢玩的包 3.在最新版本上点创建房间) with 「立刻前往」 to the plaza. (b) The modpack version page gets a 「创建房间」 button beside 安装, enabled when the version status is OK. |
| D2 | Is the edit form (设置, 更新整合包) part of c1? | **Yes.** Same form with a different submit. |
| D3 | Start and stop on the details page? | **No.** They follow Compose and come with 后台 in c2. |
| D4 | Game rule dialog layout | **Same content, plain rows.** Category headers, one row per rule (checkbox or number field, name, id, description), 重置, 保存 with the change list to confirm. Rule data comes from one JSON file written by a Kotlin test from `AllGameRules` (61 rules), so the two launchers cannot drift. |
| D5 | Map template cards | **Name, terrain type and size as text.** `BaseWorldCard` in Compose was not read for this draft; if it shows a picture, report that at the gate instead of building an image pipeline. |
| D6 | Where is the live test run? | **Local master for create, edit and delete.** Creating a room on the real server makes a real room, sends mail and counts toward the three-room limit. Real-server checks are read-only (details and members of existing rooms). If the local master is not running, ask the owner to start it; do not fall back to the real server. |
| D7 | After saving an edit, where does the player land? | **Back on the details page, reloaded.** Compose returns to the room list. |

D1 notes for the implementer:

- Plus glyph `\u{f067}`, accessible label and tooltip 创建房间, same size and press effect
  as its neighbours. The row's width is computed from the button count, and the third
  slot is used only on the 整合包 tab today (导入rdipack2); the 多人房间 tab now has three
  buttons too. Check `pill-press` indices and the glass pill count in `src/app.rs` before
  adding one.
- The 多人房间 tab also reacts to a right click today (`ui/glass_tab_bar.slint`,
  tooltip 右键创建新房间). The owner did not mention it. Assumption: keep it and open the
  same guide. List it in the gate summary so the owner can have it removed.
- The guide's order of buttons and its dismissal follow `CreateRoomGuideDialog`.

## 4. Design

### Rust

New module `src/rooms/`. No Slint types in its public functions, so the planned launcher
MCP can call it (`J5-PLAN.md` section 5; the owner decided on 2026-11-22 that modpack
upload gets no screen and is done by an agent through that MCP, so logic must stay
reachable without UI).

| File | Content |
|---|---|
| `model.rs` | `HostDetail`, `Member`, `Role`, `BaseWorld`, `Friend`, `CreateRequest`, `OptionsRequest`. Ids stay strings. |
| `requests.rs` | One function per row of the route table. Adds `put`, `delete` and body-less `post` to `ServerApi`, with the same envelope and error handling as `get`. |
| `form.rs` | Port of `HostCreateUiState` and the `HostCreateViewModel` rules as a plain state struct with methods that return the next state or a message. No I/O. |
| `game_rules.rs` | Rule table from the fixture; `update_override`, `changed_rules`, integer validation, display values. |
| `permissions.rs` | `is_owner`, `can_manage`, `can_view_members`, and per member `can_kick`, `can_open_menu`, menu entries. |

`src/rooms_ui.rs` holds the wiring, shaped like `src/plaza.rs`: a controller with
`action(name, value)`, epoch counters for the detail load, the member load, the friend
lookup and the template load, and a channel drained on the UI thread.

`src/navigation.rs` gains `RoomDetail` and `RoomForm`. `ui/app.slint` gains a
`room-screen` property (0 none, 1 details, 2 form). `src/shell.rs` routes back and home.

### Form rules to keep exactly

From `HostCreateViewModel.kt`; each line needs a test.

- Default name: `<player>的<first 4 characters of the pack name>`, or `<player>的世界`.
- Defaults: difficulty 3, mode 0, terrain `minecraft:normal`, whitelist on, cheats off.
- Terrain choices 0-3: `minecraft:normal`, `minecraft:flat`, skyblock, custom.
  Skyblock is `skyblockbuilder:custom_skyblock` when the Minecraft minor version is 16 or
  lower, else `skyblockbuilder:skyblock`. Custom needs non-blank text (请输入地形ID).
- Reading an existing room: a terrain containing "skyblock" is choice 2, `minecraft:flat`
  is 1, `minecraft:normal` or blank is 0, anything else is custom.
- Version template binding: loaded from `/modpack/{id}/detail` for the chosen version.
  A binding selects the template source at once. `required` disables every terrain card
  and shows 此版本强制使用指定的地图模板. A bound template missing from the ready list
  gives 此版本指定的地图模板不可用，请联系整合包管理者 (required) or
  此版本推荐的地图模板不可用，请重新选择地图模板 (optional), and blocks submit.
  A choice the player made by hand survives a refresh of the list.
- Friends: at least one before submit (至少邀请1位好友一起玩), at most 19. QQ must be 1-20
  digits (请输入正确的QQ号). Not yourself (不能邀请自己，请添加其他好友), no duplicates
  (已添加这位好友). A newer lookup cancels an older one.
- Create body: `levelType` is the template's when a template is chosen; `baseWorldId` is
  then set; `initialMemberIds` lists the friends.
- Edit body: `levelType` is sent only when the player changed it. `modpackId` and
  `packVer` are never sent.
- Submit is ignored while one is running. Blank name gives 请输入房间名称.
- Messages: create 房间创建中，服务器准备完成后会通过邮箱通知你, then go back; edit
  设置已保存; pack update 已提交更新 after confirming 「将更新房间当前的整合包《name》到最新
  版本。所有修改过的配置都会丢失。」; failures 创建失败: / 保存失败: plus the server message.
- The form footer keeps 注意，一个月没人玩房间就自动删了.

### Details and members rules to keep exactly

From `HostInfoScreen.kt` and `HostMembersScreen.kt`.

- Owner: `ownerId == me` or `is_dav`. Can manage: owner, or my member role is OWNER or
  ADMIN. Can view members: owner, or I am in `members`, or `is_dav`.
- Title bar: room name; pack card that opens the existing Slint pack details page;
  hid, mid, wid as click-to-copy text; 设置 when I can manage; 删除房间 when owner.
- Delete: rooms with `version >= 2` ask the player to type the room name; older rooms
  show the checkbox 同时删除关联存档. On success leave the page and refresh the home tiles.
- Members are sorted by role level. A chip shows head, name and a role badge
  (所有者, 管理员, 成员).
- Menu per member: 转让房间 and 设为管理员/取消管理员 when I am owner and the member is
  not; 踢出成员 when the member is not the owner and (I am owner or the member is below
  admin); 退出房间 on my own chip when I am not the owner. Every entry confirms first,
  with Compose's wording.
- 邀请 (by QQ) when I am admin. Messages: 已发送邀请, 已更新, 已转让, 已踢出.
- After a transfer reload both the room and the members. After leaving, go back and
  refresh the home tiles.
- Non-members see the title bar only. Loading and error states use `LoadingState` and
  `ErrorState` with retry.

### Slint

New `ui/rooms.slint`: `RoomForm`, `RoomDetails`, `GameRuleDialog`, `BaseWorldDialog`,
`MemberMenu`. Reuse `TextField`, `GlassButton`, `CircleIconButton`, `SharedDialog`,
`Toast`, `RScrollbar`. Material 3 look as the other Slint screens; no new control kinds.

Option cards (难度, 模式, 地形) are picture cards with a selected state. Icons are decoded
once from the AVIF files with `src/avif.rs` and passed in as `slint::Image`; one copy of
each file stays in `client/ui/assets`.

The game rule list has 61 rows plus headers. Use a `ListView` or the visibility trick
from `ui/plaza.slint`; check the idle cost with the dialog open.

Chinese text follows the root `AGENTS.md` (no spaces between Chinese and digits or
Latin letters).

## 5. Steps

1. `ServerApi`: `put`, `delete`, body-less `post`, with mock-server tests.
2. `src/rooms/model.rs`, `requests.rs`: request-shape tests against the mock server.
3. Fixtures, written by one new Kotlin test in `client/ui` (write mode behind
   `RDI_WRITE_FIXTURES=1`, check mode otherwise, as `J5aLaunchCommandFixtureTest`):
   `protocol/fixtures/game_rules.json` from `AllGameRules`, and
   `protocol/fixtures/room_requests.json` holding `CreateDto` and `OptionsDto` JSON for a
   few cases. Rust must produce the same JSON.
4. `form.rs`, `game_rules.rs`, `permissions.rs` with tests ported from the two Kotlin
   test files and the rules above.
5. `ui/rooms.slint` and `src/rooms_ui.rs`: details and members first, then the form, then
   the two dialogs.
6. Entry points: the plus button and guide dialog, the version-page button, right click
   on a room tile (`src/app.rs:414`), the room list screen.
7. Validation and gate files.

## 6. Validation and gate GU4c1

Automated, on Windows through `pwsh.exe`:

- `cargo clippy --all-targets -- -D warnings`, `cargo test --workspace`,
  `cargo build --release`.
- `cargo-zigbuild check --release` for `aarch64-apple-darwin` and
  `x86_64-unknown-linux-gnu`.
- The new Kotlin fixture test in check mode. Run Gradle from `client/ui` with
  `gradlew.bat`; do not run it beside another Gradle build.
- No `modpackServiceTest`: no server code changes.

Live, with `cargo preview-run`, an isolated `RDI_DATA_DIR`, the test account and the
slint-ui MCP (see the J5a gate notes for the MCP pitfalls):

- Details of a room I own, one I am a member of, one I am not in.
- Invite, set admin, remove admin, kick, transfer, leave; each refusal message.
- Create with each terrain choice, with a template, with a version that requires a
  template; friend lookup errors; game rules changed, reset and saved.
- Edit and save; 更新整合包; delete for both room generations.
- No `ui-service` process is started by any of the above.
- Create, edit and delete run on the local master only (D6).

Never capture the whole desktop; capture the launcher window only.

Gate files in `evidence/ui-port/u4c1/`: `gate-summary.md` (numbers, verified, not
verified, decisions needed), `owner-checklist.md`, side-by-side Compose and Slint
screenshots of the form, the details page and both dialogs.

Do not stage, commit or push; the reviewer commits after the owner's review.

## 7. Risks

- **Drift from Compose rules.** The form has many small rules; the ported tests and the
  request fixture are the guard.
- **Destructive actions.** Delete, kick and transfer cannot be undone. Each confirms
  first, and live tests use rooms made for the test.
- **Token expiry** is an accepted J1 gap; a 401 here shows the server message like
  everywhere else.
- **Working tree.** J5a is uncommitted and touches `src/app.rs`, `src/shell.rs` and
  `ui/app.slint`. Start c1 after GJ5a is committed, or agree the merge order with the
  J5b work first.

## 8. Not yet checked

- What `BaseWorldCard` shows (D5).
- Whether Slint already has a clipboard helper for the click-to-copy ids.
- Whether the room list screen (`ui/host_list.slint`) has a right-click or needs a
  details button.
- The exact JSON spelling of ids and UUIDs in `CreateDto`; the fixture in step 3 settles it.
