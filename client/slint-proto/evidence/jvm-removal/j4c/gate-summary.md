# GJ4c gate summary

Status 2026-11-22: **J4c implemented; automated validation passed; isolated live run
done; owner checks open.** No commit, no push. Plan: `J4C-PLAN.md` (all section 2
decisions applied as written).

## Changes

New module `src/pack_install/` (no Slint types in its public surface):

| File | Content |
|---|---|
| `mod.rs` | `InstallRequest`, `Deps`, `State`, `start`, `state`/`state_in`, `Running`, the 7 visible steps, staging folder, commit (D5), options merge |
| `mods.rs` | `processMods`, side filter, slug aliases, `fileName`, digest per platform, Kotlin source order and headers |
| `extras.rs` | `validateClientExtra`, `validateAndMergeClientExtras`, extra requests, no-URL fallback to mod sources |
| `archive.rs` | tar.zst/zip streaming reader with Kotlin limits, port of `BoundedTarInputStream`, `extractClientPackArchiveToDir(layered=false)` |
| `tests.rs` | local fake server end to end, archive rejections, `Store::fetch` |

Other code:

- `content_store.rs`: `Store::fetch` (single flight, digest check, commit, temp fallback
  deleted on drop) and `has_cached`; `materialize` shares the same `obtain` path.
  Download errors now carry the target path in the log chain.
- `tasks.rs`: `submit_steps` (group row + one row per step), `Context::step`,
  `Manager::active`; failure log no longer says 导入 for every task.
- `download.rs`: JSON rejection follows Kotlin exactly (see J4b fix below).
- `api`: `ServerApi::url`, `auth_headers` (token only for this server, marked sensitive),
  `Client::server`. `catalog`: `download_headers_for` (CurseForge key rule for any HTTPS URL).
- UI: `ui/components/install_dialog.slint` (安装整合包, pack name, 版本 · size,
  下载此包材质光影 off by default, 取消/安装); plaza version page button 安装 / 安装中 /
  重新安装 for built versions only; room click checks the pack before `host.start`
  (`app.rs::room_pack`); `game.state` feeds the running-version guard.
- `Cargo.toml`: `tar =0.4.46` (no default features), `zstd =0.13.3` (C library;
  zigbuild checks pass, so `ruzstd` was not needed).
- `protocol/fixtures/j4c.json` + Kotlin `J4cModProcessingFixtureTest` (client-core):
  writes it with `RDI_WRITE_FIXTURES=1`, otherwise checks it. Slug lists are read by
  reflection, so a Kotlin list change fails the Kotlin test, and the regenerated fixture
  fails the Rust test until Rust is updated.

Kotlin/Compose behavior, protocol version and server are unchanged. No server-side
modpack logic changed, so `modpackServiceTest` was not run.

## Automated validation (Windows pwsh; IntelliJ was not open, per owner)

| Check | Result |
|---|---|
| `cargo clippy --all-targets -D warnings` | pass |
| `cargo test --workspace` | bin 111 passed / 1 ignored (baseline 94 + 17 new), lib 11, renderer 62 / 1 ignored |
| `cargo build --release` | pass |
| `cargo-zigbuild check --release` aarch64-apple-darwin | pass, `zstd-sys` compiles; the 2 pre-existing updater warnings |
| `cargo-zigbuild check --release` x86_64-unknown-linux-gnu | pass, same 2 warnings |
| Kotlin `J4cModProcessingFixtureTest` (`--rerun`, check mode) | 1/1 passed |

Logs: `rust-tests.log`, `build-cross.log`, `kotlin-fixture.log`.

New Rust tests cover: fixture lists and 24 processing cases; source order with and
without the mirror preference and headers per host; extras validation/merge/server
filter; install success with Kotlin layout, options merge, fancymenu file, extras with
an existing file kept, one CurseForge lookup only for the mod without URL and not cached;
reinstall from cache replacing the old copy; second `start` returns the running task;
cancel during download; cancel at each of the 7 steps keeps the older install; a failed
install leaves no folder and keeps the older install; running game blocks the commit;
unbuilt version refused; leftover cleanup; tar rejections (links, devices, traversal,
drive paths, case duplicates, file vs directory, truncation, entry and metadata limits,
layer limit), zip with Chinese names, symbolic link on the target path (Windows link
created successfully in this run); fetch cache hit, mismatch and single flight; omitted
Kotlin default fields; the JSON content-type rule.

## Live run (isolated `live/run`, test account, slint-ui MCP over HTTP, RMCP in game)

The window had been minimized; it was restored with `ShowWindow(SW_SHOWNOACTIVATE)` so
the MCP could reach the screens. No native mouse or keyboard input was used.

| Check | Result |
|---|---|
| Plaza → 七七和小翔修仙世界 0.3.377 → 安装, checkbox on | first run **failed** at 补充资源包和光影包: `下载地址返回了JSON` (J4b bug, below). After the fix the retry finished in 15s from cache; 163 jars (152 = installable count + 11 from the pack), 14 resource packs, 12 shader packs, fancymenu and merged options present |
| Plaza button label | 安装 → 安装中 while running → 重新安装 after |
| 整合包 tab after install | refreshed by itself, tile shown |
| Room 28257的1.21 (pack missing) click | confirmation dialog; no Java child started. First confirm **failed**: 服务器数据格式不兼容 (omitted Kotlin defaults, fixed below). After the fix: installed in a few seconds, no automatic game start (D4) |
| Click the room again | passed the check, service started, `host.start`, Minecraft NeoForge loaded with the 进入地图 · 28257的1.21 button; joined the room world through RMCP (overworld, survival), then disconnected and quit |
| Cancel mid-install (修仙传记 297MB) | cancelled during 补充模组: no version folder, no hidden folder, no temp file |
| Retry after cancel | 32s, archive from cache |
| 乌托邦探险之旅 3.5.2 (Fabric, 431 mods, 28 extras, checkbox on, mirror on, cold) | 196s; 391 named jars = installable count, 44 resource packs, 11 shader packs |
| 创造抽屉制作人2 2.3.3 (217 CurseForge mods without URL, mirror **off**) | 32s; 213 jars = installable count |

File manifests (relative path + SHA-1, logs excluded) of four Rust installs are in
`manifests/` for comparison with Compose.

## Problems found and fixed in J4c

1. **J4b downloader, JSON check too broad.** Rust rejected any content type containing
   `json`; Kotlin `rejectJsonDownloadResponse` only rejects `application/json`.
   forgecdn serves some zips as `application/x-amz-json-1.0`, so every CurseForge
   resource/shader pack in 七七 failed. Fixed in `download::json_response` with a test.
   This also affects J4b archive import; the J4b summary has a note.
2. **Omitted defaults.** `POST /modpack/infos` omits `mcVer`/`modloader` when they equal
   Kotlin's defaults (`V211`, `neoforge`); `Brief` now restores them. Test added.
3. **Step rows.** A started step showed 排队中 and the header kept the previous message
   until the first progress report; `Context::step` now marks it 进行中 at once.

## Differences from the plan, following the code

- Kotlin `writeMinecraftOptions` **merges** into the pack's `options.txt`
  (`mergeMinecraftOptions`); `installer::options` only gives the blank-file text. The
  merge is ported; `j4a.json` options cases still match.
- Commit has no row of its own (Kotlin has none); it runs at the end of 补充配置 with
  the message 正在保存.
- Running guard: version ids come from the `process` snapshot of `game.state`
  sessions. A session that is preparing without a process yet blocks every commit,
  because its version is not known.
- PAX: a PAX `size` different from the header size, or a global PAX `size`, is refused
  (Kotlin honours them); the tar crate cannot. Only hostile archives are affected. The
  tar crate also refuses two GNU long names in a row before the 16-layer guard is
  reached; the guard itself is tested on the raw stream.
- Not ported: Kotlin `LocalArtifactReuse` (reusing mod jars found in other launchers'
  folders). Mods come from the shared cache or the network.
- The cache check before the CurseForge lookup only tests that a cache file exists; a
  corrupt cache file falls back to the RDI copy and the mod's own URLs.
- `start` refuses versions whose status is not OK (该版本尚未构建完成，暂不能安装) and
  version names with path characters, because an MCP tool can call it directly.
- Leftover `.installing-<version>-*` / `.replaced-<version>-*` folders older than the
  run are removed at task start; a second launcher installing the same version at the
  same moment could lose its staging folder.
- The Kotlin fixture test replicates the private `isClientInstallableMod` and the
  "process only with override flags" condition from `installVersionTask2`.

## Not run (owner checklist)

Compose comparison of an installed directory, Rust↔Compose cross-start, Compose
download timing on the same connection, a pack with GitHub mods (none of the test
rooms has one), extras checkbox behaviour in Compose, focus/minimize behaviour.
The live data folder still holds the installs (several GB) for the comparison; the
test credentials file was removed from it.
