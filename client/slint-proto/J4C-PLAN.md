# J4c plan: install room modpacks in Rust

Written 2026-11-22 for an agent with no prior context. Status: **approved by the
owner on 2026-11-22. Every recommendation in section 2 is accepted as written.
Implement, then stop at gate GJ4c.**

Read first: `JVM-REMOVAL-PLAN.md` (direction and phases), `J4-PLAN.md` (tasks,
downloader, cache; its section 7 rules apply here too), the root `AGENTS.md`.

## 1. Context

The launcher is moving off the JVM step by step. J4 moved tasks, the downloader,
the download cache and local pack import to Rust. What is still missing before
game launch (J5) is installing a modpack that lives on the RDI server: today the
Slint launcher cannot do it at all.

- Clicking a room whose pack is missing ends in the Kotlin service with
  `请先在Compose启动器中安装该整合包，再返回这里游玩`
  (`client/ui/ui-service/src/main/kotlin/calebxzau/rdi/service/Main.kt:99`).
- The plaza's pack and version pages (`src/plaza.rs`, `ui/plaza.slint`) only browse.

J4c adds the install. It is also the first real, large workload for the J4b
downloader, so its live run doubles as that downloader's acceptance.

A second reason: the planned launcher MCP needs `install_host_modpack`. The
install must therefore be a plain Rust function that the UI and, later, an MCP
tool both call. No Slint type may appear in its signature.

After J4c, playing a room still goes through the Kotlin service (`host.start`),
which applies the RDI core, the room's extra and disabled mods, and installs
Minecraft. Those stay Kotlin until J5.

## 2. Owner decisions (approved 2026-11-22)

The owner accepted every recommendation below; treat the right-hand column as decided.

| # | Question | Decision |
|---|---|---|
| D1 | Full install only, or also incremental update from the file manifests? | **Full install only**, as Compose does. Mods come from the shared cache, so only the client pack archive is downloaded again. Incremental update is a separate feature with an open policy on player-edited config files. |
| D2 | Where can a player start an install? | **Both**: an 安装 button on the plaza version page, and a confirmation when a room is clicked and its pack is missing. |
| D3 | The Compose dialog offers three ways: 让朋友分享我, 直接下载(很慢), 从群文件下载. Which does Slint show? | **Direct download only**, with the 下载此包材质光影 checkbox (off by default, as in Compose). The other two need the shared-pack importer, which is not ported. Drop the "(很慢)" wording, since it is the only way offered. |
| D4 | After an install started from a room finishes, enter the game automatically? | **No**, as in Compose: the player clicks the room again. A long download followed by a game window opening unattended is a surprise. |
| D5 | Install in place like Kotlin, or build in a hidden folder and move it into place at the end? | **Hidden folder, then move.** Kotlin treats a version as installed when `<version dir>/mods` exists, so a cancelled or failed in-place install looks installed to both launchers. Building aside fixes that and keeps a working older copy until the new one is complete. Compose ignores the hidden folder (section 3). |
| D6 | Refresh the node before downloading, as Kotlin's 刷新节点 step does? | **No.** Rust uses the primary address until J5 (J1 decision D3). |
| D7 | Uninstall and reinstall from the 整合包 tab? | **Not in J4c.** They are in UI batch d. The plaza button on an installed version reads 重新安装 and reuses the same function, which costs nothing. |

## 3. Code facts

Kotlin, the behavior to port (`client/ui/client-core/.../service/ModpackService.kt`
unless stated):

- Version directory: `<versions>/<modpackId>_<verName>` (`getVersionDir`, line 44).
- Task key: `modpack-install:<modpackId hex>:<verName>` (line 41). Title:
  `完整下载整合包 <name> <size>` (line 494).
- `startInstallTask2` (line 195): node refresh, then `installVersionTask2`.
- `installVersionTask2` (line 111): `GET modpack/<id>/version/<ver>/client/hash`
  returns the SHA-1, then the archive comes from
  `GET <server>/modpack/<id>/version/<ver>/client` with `Authorization: Bearer <jwt>`
  through the content cache (`clientPackContentRequest`, line 65).
- `createInstallClientZipTasks2` (line 263), six steps with these visible titles:
  准备安装目录 (delete the old directory, never following symbolic links),
  解压客户端整合包, 写入菜单 (`config/fancymenu/options.txt` from the resource
  `client/ui/assets/src/main/resources/overrides/fancymenu-options.txt`),
  补充模组, 补充资源包和光影包, 补充配置 (`writeMinecraftOptions`).
- Mod list: `ModpackModProcessor.processMods` runs only when some mod has
  `clientOnlyOverride` or `clientOverrideReplaced`
  (`common/model/.../service/ModpackModProcessor.kt`); then mods whose side is
  `SERVER` or `UNKNOWN` are dropped (`isClientInstallableMod`, line 500).
- Mod file name: `<fileSlug>_<platform>_<hash>.jar`, where `fileSlug` passes the
  slug through `MOD_FILE_SLUG_ALIASES` (`common/model/.../model/Mod.kt:80`).
- Mod digest by platform: `cf` murmur2, `mr` SHA-1, `github` SHA-1 or SHA-256
  (`ModService.githubUsesSha1`); `Mod.toClientContentRequest` in
  `client/ui/client-store/.../content/ClientContentStore.kt:1119`.
- Mod sources and order (`common/net/.../service/ModService.kt:940-975`): the RDI
  copy `<server>/mod/download/<fileName>` with the Bearer header
  (`RServer.kt:80`), mirror URLs (`ofMirrorUrl`, line 516), official URLs. With
  `preferMirror`: RDI, mirrors, official. Without: official, RDI. CurseForge mods
  add the file's real download URL from the CurseForge API, and CurseForge and
  mirror hosts get the API key header (`CurseForgeService.downloadHeadersFor`).
- Client extras: `materializeClientExtras` (`ClientContentStore.kt:320`) with
  `validateAndMergeClientExtras` (`common/model/.../Content.kt:146`): server-side
  entries are skipped, and an existing regular file at the target is kept as is.
- Archive: tar.zst (zip is also accepted), read by `forEachArchiveEntryStreaming`
  with `MODPACK_ARCHIVE_READ_LIMITS` (2GiB per file, 8GiB total, 100000 entries),
  no links, devices or sparse entries, sizes must match
  (`common/archive/.../ArchiveStreaming.kt`). The client pack is already
  flattened, so extraction uses `layered = false`: paths go through
  `normalizeSafeArchivePath`, duplicates differing only by case are rejected, a
  file may not also be a directory, and no symbolic link may lie on the target
  path (`ClientPackArchive.kt:36`).
- Room start: `startHostPlay` (`HostPlayService.kt`) reads `host/<id>/detail`,
  then `startPlay` (`ModpackService.kt:416`) reads the version and returns
  `Installing`, `NeedInstall` or `Ready`. Installed means
  `<version dir>/mods` is a directory (line 233).
- Compose dialog: `client/ui/src/.../ui/comp/ModpackDownloadMethodDialog.kt`.
- Directory scanning: Kotlin lists only names matching
  `^[0-9a-fA-F]{24}_(.+)$` (line 509); Rust `local_packs::scan` matches the same
  names plus the local-pack prefix. A folder whose name starts with `.` is
  invisible to both.

Rust, what exists:

- `src/tasks.rs`: `Manager::submit(runtime, title, dedupe, work)` returns the
  running task's id when the key is already active; `Context::progress`;
  cancellation.
- `src/content_store.rs`: `Store::materialize(requests, root, cancel, progress)`
  with the shared `<data dir>/dlc` cache. There is no call that hands back a
  cached file without a target, which the client pack needs.
- `src/download.rs`, `src/download_policy.rs`: the J4b downloader with per-URL headers.
- `src/import/installer.rs`: `options(version)` (the `options.txt` text),
  `checked_target`. `src/import/archive.rs:624` shows how import builds mod
  requests and mirrors.
- `src/catalog`: CurseForge and Modrinth access, API key in `transport.rs`,
  `get_files` for batch file lookups, `prefer_mirror`.
- `src/local_packs.rs`: `versions_dir`, `remote_name`, the installed list.
- `src/plaza.rs`: pack detail and version data are already loaded for the plaza pages.
- `Cargo.toml` has `zip` but no tar or zstd crate.

## 4. Design

### 4.1 Module and public surface

New module `src/pack_install/` (name it to match the neighbours). Public surface,
free of UI types:

```rust
pub struct InstallRequest {
    pub modpack_id: String,
    pub version: String,
    pub include_extras: bool,
}
/// Starts the install, or returns the id of the one already running.
pub fn start(deps: &Deps, request: InstallRequest) -> Result<String>;
pub enum State { Missing, Installing(String), Installed }
pub fn state(deps: &Deps, modpack_id: &str, version: &str) -> State;
```

`Deps` carries the API client, the task manager, the content store, the catalog,
the runtime handle and the versions directory, so tests can pass fakes and a
temporary directory. `start` fetches the pack brief and the version itself; a
caller needs only ids. This is the function a future MCP tool calls.

### 4.2 Install steps

One task, key and title as in Kotlin. Visible steps keep Kotlin's titles, minus
the node refresh (D6):

1. **下载客户端整合包.** Read the hash, then fetch the archive through the cache.
   Add `Store::fetch` (or similar): the same single flight, digest check and
   commit as `materialize`, returning the cache path instead of linking to a
   target. If the cache cannot be written, download to a temporary file and
   delete it after extraction, as Kotlin's `use` does.
2. **准备安装目录.** With D5: create `<versions>/.installing-<versionId>-<uuid>`.
   At task start, remove leftovers of the same version older than this run
   (never following symbolic links).
3. **解压客户端整合包.** Add a streaming tar.zst reader with Kotlin's limits and
   rejections, and the extraction rules listed in section 3. Detect zip by magic
   bytes and reuse the `zip` crate for it. Crates: `tar` plus `zstd`; if `zstd`'s
   C build breaks the zigbuild checks for macOS or Linux, use the pure Rust
   `ruzstd` decoder. Progress `解压中 <file>(<done>/<total>)`.
4. **写入菜单.** Embed the Kotlin resource with `include_bytes!` from its path in
   `client/ui/assets`, so there is one copy.
5. **补充模组.** Port `processMods`, the side filter, the slug alias table and the
   file name. Build requests with Kotlin's digest and source rules. Look up the
   CurseForge real download URL in one batch `get_files` call, and only for mods
   that are not already in the cache. Materialize into `mods/`.
6. **补充资源包和光影包** when `include_extras` is set: port validation and
   merging; keep an existing regular file.
7. **补充配置.** Reuse `installer::options`.
8. **Commit (D5).** Take the per-version lock, refuse if a game of this version
   is running (`整合包正在运行，不能安装`; the game list comes from the existing
   `game.state` data), move the old directory aside, rename the new one into
   place, then delete the old one. If the rename fails, move the old one back.

On failure or cancellation, delete the staging folder. The cache keeps what was
downloaded, so a retry resumes cheaply.

The slug lists in `ModpackModProcessor` and `MOD_FILE_SLUG_ALIASES` change over
time. Do not copy them by hand without a guard: have a Kotlin test write a JSON
fixture (processed mod list and file names for a sample input covering every
branch) into `protocol/fixtures/`, checked by both sides, as J2 and J4a did.

### 4.3 Room click

Before sending `host.start` to the Kotlin service, Rust reads `host/<id>/detail`
and calls `state`:

- `Installed`: continue to `host.start` as today.
- `Installing(id)`: notify 整合包正在下载，请等待下载完成后再启动 and open that task.
- `Missing`: show the confirmation (4.4). The Kotlin process is not started.

The Kotlin check stays as a second line of defence; do not change it.

### 4.4 UI

- **Confirmation dialog** (one component, used by both entries): title
  安装整合包, the pack name, version and size, the 下载此包材质光影 checkbox, 取消
  and 安装. On confirm: `start`, close, open the task panel on that task.
- **Plaza version page**: one button whose label follows `state`: 安装, 安装中
  (opens the task), 重新安装. Only for versions whose status is built.
- After a task of this kind succeeds, refresh the installed list
  (`refresh_packs`) so the 整合包 tab and the button label update.
- Follow `ui/components` and the theme; no new control types. Text follows the
  Chinese spacing rule in `AGENTS.md`.

### 4.5 Not changed

Kotlin and Compose code, the protocol version, the server. No `modpackServiceTest`
run is needed because no server-side modpack logic changes; if that turns out
false, run it as `AGENTS.md` requires.

## 5. Steps

1. Section 2 is answered; nothing to ask before starting.
2. Kotlin fixture test for the mod processing and file names; Rust port with tests.
3. tar.zst reader and extraction with tests: limits, link and device entries,
   size mismatch, traversal, case duplicates, file-versus-directory conflict,
   symbolic link on the target path.
4. `Store::fetch` with tests (cache hit, digest mismatch, single flight).
5. Mod and extras request building with a fake transport: source order with and
   without the mirror preference, headers per host, digest per platform.
6. `pack_install::start` and `state` end to end against a local fake server:
   success, cancel in each step, failure leaves no version directory, an older
   install survives a failed reinstall, second `start` returns the same task.
7. Room click pre-check and the dialog; plaza button; list refresh.
8. Validation and gate.

## 6. Validation and gate GJ4c

Automated: `cargo test`, `cargo clippy`, release build, zigbuild checks for macOS
and Linux, the Kotlin fixture test. Report counts and any failure with output.

Live, isolated data directory, through the slint-ui MCP where it reaches:

- Install a real pack from the plaza; compare the resulting directory with one
  installed by Compose for the same version (file list and hashes; list every
  difference).
- Click a room without its pack: dialog, install, click again, reach the game
  through the Kotlin service.
- Cancel mid-download, then retry: no version directory in between, the retry
  reuses the cache.
- Mirror preference on and off. A pack with CurseForge, Modrinth and GitHub mods.
- With the checkbox on, resource and shader packs appear.
- A pack installed by Rust starts from the Compose launcher, and the reverse.
- Record download time for one large pack next to Compose's time for the same
  pack on the same connection. This is the downloader's first real measurement;
  report it without claims beyond the one run.

Stop at **GJ4c** with a gate summary and an owner checklist under
`evidence/jvm-removal/j4c/`. No commit before the owner's review.

## 7. Out of scope

Incremental update, uninstall, pack migration, shared-pack import (朋友分享,
群文件), game launch and everything `host.start` does (J5), the MCP server
itself, changes to Compose.

## 8. Open risk

GJ4b has not been recorded as passed. J4c exercises the same downloader and
cache under real load, so problems found here are fixed here, and the J4b gate
summary should be updated from J4c's live results instead of being repeated.
