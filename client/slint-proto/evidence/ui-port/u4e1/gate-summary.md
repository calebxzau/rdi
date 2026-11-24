# U4e1 gate summary (模组库, 资源包/光影包)

2026-11-24. Owner approved the design and D1-D3 as recommended the same day. Batch e2 (the
「整合包内容」 stub) is folded into e1: the pack menu's 「内容」 is now 模组 / 材质 / 光影.

## What was built

| File | Content |
|---|---|
| `src/content/mod.rs` | `Kind` (模组/资源包/光影包), `Target` (local pack or room), readable changelog, counts, dates, `required_refs`, `to_legacy` (`CatalogMod.toLegacyMod`), room version check |
| `src/content/records.rs` | `<pack>/.rdi/content-installations.json`, same JSON as Compose `LocalContentInstallStore` |
| `src/content/status.rs` | Per-file state (未安装/已安装/可更新/下载中/安装失败/文件缺失/文件已被修改/运行中不可更新), jar health check, Compose task keys |
| `src/content/install.rs` | Staged install under `.rdi/installing`, old file backed up, record written, rollback on any failure |
| `src/content/modrinth.rs` | 资源包/光影包 search, project, versions (Modrinth v2 through the catalog transport, so the mirror race applies) |
| `src/content_ui.rs` | Wiring to the new `Content` global; install tasks in the shared task panel |
| `ui/content.slint` | 模组 list, 模组详情, 资源包/光影包 list and details, install dialog, gallery preview, snackbar |
| `ui/app.slint`, `src/shell.rs`, `src/navigation.rs` | Four screens, pack menu row 模组/材质/光影, pack menu sized to its content |
| `ui/room_pages.slint`, `src/room_pages_mods.rs`, `ui/components/glass_button.slint` | 附加Mod: click → catalog for the room, Shift+click → GitHub dialog (D1); `GlassButton.shift-held` |
| `src/local_packs.rs`, `src/packs.rs` | Installed packs carry MC version and loader (`ModpackLocalDir.mcVersion/modLoader`) |
| `src/tasks.rs`, `src/catalog/mod.rs` | `latest_status(dedupe)`; `Catalog::modrinth_get` |

No server or `common` change; no `modpackServiceTest` needed.

## Left out (as Compose today)

Target choice in the install dialog (both entries fix the target), automatic dependency install
(paused in Compose), Modpack2 targets, sort switch, updating a resource/shader pack after one is
installed (all version buttons stay disabled, as in Compose).

## Deviations

- 资源包/光影包 use Modrinth v2 `project/{id}` and `project/{id}/version`; Compose asks v3. v2 has
  every field these pages show and goes through the existing mirror race unchanged.
- A 1.7.10 pack's 模组 page says 「1.7.10 forge的整合包暂不支持模组库」; Compose's route builder throws.
- 资源包/光影包 downloads use the Modrinth URL only, like Compose (no RDI/mirror copy).

## Validation

| Check | Result |
|---|---|
| `cargo clippy --all-targets` | clean |
| `cargo test --workspace` | 344 + 62 pass, 4 ignored (unchanged); 16 new (`content::tests` 15, `content_ui::tests` 1) |

Live (Windows, `evidence/jvm-removal/j5a/live/run`, real server, slint-ui MCP, release preview):

- Pack menu row 模组/材质/光影; the menu card now fits 取消.
- 模组 list on 「J5a测试 Forge1.20.1」: cards with icons, downloads (亿/万), intros; search 机械动力;
  加载更多 appends.
- JEI details: latest card with changelog + 前置1个 (MezzConfig) in two columns, 显示其他版本 with
  可更新 per file; dependency card opens MezzConfig and back returns to JEI.
- Install JEI: dialog (前置, 更新说明, 安装到整合包), task queued, snackbar 「已加入任务列表 · 查看任务」,
  state 下载中 → 已安装; `mods/jei_mr_<sha1>.jar` and a Compose-shaped record written.
- 光影 list (banners, icons, follows), BSL details (versions, 显示Alpha, gallery, preview dialog),
  install 10.1.8 → `shaderpacks/BSL_v10.1.8.zip`, record `SHADER_PACK`, staging folder removed,
  10.1.8 shows 已安装 and the other versions are disabled.
- GTNH (1.7.10) 模组 shows the unsupported message.

Fixed during the live run: pack menu height, changelog/dependency columns, version chip text
centring, 下载中 only on the version being downloaded, preview resolution 2560×1440.

## Not verified

- Room 附加Mod → catalog → submit (`POST /host/{id}/mods/extra`): the test account manages no room
  on the real server; per D3 this runs on the local master only.
- Shift+click opening the GitHub dialog (MCP clicks carry no modifiers).
- 查看任务 opening the task panel, 运行中不可更新 with a game open, 文件缺失/文件已被修改, a failed
  download's rollback in the UI (covered by unit tests), 复制链接 and 浏览器打开.
- Compose reading the records Slint wrote (same JSON shape; not run side by side).
