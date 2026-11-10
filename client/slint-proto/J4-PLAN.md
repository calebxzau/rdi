# J4 implementation plan: tasks and local pack import in Rust

Written 2026-11-10 for an agent with no prior context. The owner approved this
design and decisions D1-D7 on 2026-11-10. J4 has two parts with one gate each.
Implement J4a, validate it, write its gate files, and stop at gate GJ4a for the
owner. Start J4b only after the owner passes GJ4a; then stop at GJ4b. Do not start J5.

## 1. Context

The repository is `C:\Users\calebxzhou\Documents\coding\rdi5`
(`/mnt/c/Users/calebxzhou/Documents/coding/rdi5` in WSL). Read these first:

- `AGENTS.md` at the repository root. Its rules apply to everything below.
- `client/slint-proto/JVM-REMOVAL-PLAN.md`: the overall plan. J1, J2 and J3 are
  done and committed. "Phase J4" there is the approved design this file expands.
- `client/slint-proto/protocol/README.md`: the stdio protocol, version 3.
- `client/slint-proto/evidence/jvm-removal/j3/gate-summary.md` and
  `owner-checklist.md`: the format the gate files must follow.

The Slint launcher (`client/slint-proto`, Rust) runs a headless Kotlin process,
`ui-service` (`client/ui/ui-service`), for work Rust has not taken over. Since J3
that process starts on demand and stops after 60 idle seconds. It starts for the
import screen, the task screen, play and the console.

**Goal of J4:** the import screen and the task screen need no JVM. After J4 the
service starts only for play and the console (J5).

The Compose launcher keeps shipping and is not changed. Files both launchers
touch must stay compatible in both directions: `rdi-local-pack.json`, the
download cache, `data.db`, `config.toml`.

## 2. Approved decisions

- **D1** Scope is what the Slint launcher reaches today: tasks and local pack
  import. Installing RDI packs for rooms is not part of J4.
- **D2** Two gates. J4a has no network code. J4b has all of it.
- **D3** Port the whole `mod-catalog` module, including the parts no Slint screen
  uses yet (search, details, file lists, updates, dependencies, changelog, local
  file matching).
- **D4** `mod_catalog.db` is read in development from
  `client/ui/mod-catalog/src/main/resources/mod_catalog.db`, read only, never
  copied or modified. Shipping it is J6. If the file is missing or unreadable the
  catalog works without Chinese names, as Kotlin's `UnavailableIdentityIndex` does.
- **D5** The download cache is `<data dir>/dlc`, in Kotlin's format, shared with Compose.
- **D6** Port the ranged, parallel download of `FileDownloader`.
- **D7** The task screen gets a cancel button.

## 3. Code facts

Rust, in `client/slint-proto`:

- `src/packs.rs`: the UI adapter. It sends `installer.*` and `tasks.*` through
  `call`/`pump` (one request at a time) and reads `installer.state`,
  `tasks.state` and `task.state` in `event`. `service_idle`, `service_lost` and
  `installer_opening` belong to the J3 lifecycle. The pack list refresh after a
  finished task is `refresh_after_task`.
- `src/app.rs`: the idle rule in `tick` (`eligible`), the logout guard, and the
  crash path that calls `service_lost`.
- `src/lazy_service.rs`: the on-demand service. Its start prelude sends
  `tasks.subscribe`.
- `src/local_packs.rs`: J2's reader of installed packs and `rdi-local-pack.json`.
- `src/pack_options.rs`: J2's read-only use of `data.db` through `rusqlite`. It
  never creates or migrates the database and refuses a foreign `application_id`
  or a `user_version` other than 5. Follow the same rule.
- `src/credentials.rs`: `write_atomic`.
- `src/api/server.rs`: the `reqwest` client with the system proxy.
- `ui/app.slint` line 307 (`task-panel`) and `ui/activity.slint`: the task screen.
- `protocol/fixtures/`: fixtures read by both Rust and Kotlin tests.

Kotlin:

| File | Role |
|---|---|
| `common/model/src/main/kotlin/calebxzhou/rdi/common/model/Task2.kt`, `.../service/Task2Manager.kt` | Task tree, progress, statuses, run ids, cancel |
| `client/ui/ui-service/.../PackSnapshots.kt` | `installerSnapshot` and `taskSnapshot`: the exact JSON the UI adapter reads |
| `client/ui/ui-service/.../PackService.kt` | The `installer.*` and `tasks.*` handlers and their action names |
| `client/ui/ui-logic/.../viewmodel/LocalModpackInstallViewModel.kt` | Import screen state machine |
| `client/ui/client-core/.../service/LocalModpackScanner.kt` | Candidates from recorded Minecraft folders; `detectLocalModLoader` |
| `client/ui/database/.../MinecraftInstallationStore.kt`, `MinecraftInstallation.sq` | The table the scanner lists |
| `client/ui/client-core/.../service/LocalModpackInstaller.kt` | Staging folder, exclusion lists, 20GB limit, commit |
| `client/ui/client-core/.../service/LocalModpackStore.kt` | `rdi-local-pack.json`, `uniqueName`, folder names |
| `client/mc-install/.../MinecraftOptions.kt` | `writeMinecraftOptions` |
| `client/ui/client-core/.../service/ModpackArchive.kt` | CurseForge and Modrinth archive reader, 998 lines |
| `client/ui/client-core/.../service/ModpackArchiveContentResolver.kt` | Names and icons for archive mods |
| `client/ui/mod-catalog/src/main/kotlin/...` | The catalog, 3204 lines; tests in `src/test`, 2224 lines |
| `client/ui/client-store/.../content/ClientContentStore.kt` | Download cache, 1206 lines; `ClientContentStoreTest.kt`, 967 lines |
| `common/net/.../FileDownloader.kt` | Downloader, 1596 lines |
| `openChineseZip` in `common` | Zip reading with a fallback for Chinese file names |

Facts found while designing:

- Import does not install Minecraft or a loader. It only calls
  `writeMinecraftOptions`. `mc-install` proper runs at game start and is J5.
- `ClientTaskManager.submit` has two more callers, the old-cache migration and the
  cache cleanup. Only Compose runs them. Do not port `ClientContentMigrator` or
  `DownloadCacheCleanupService`.
- `ClientContentStore` locks are inside one process. Two launchers can write the
  cache at once; each file is safe because it is written to a temporary file,
  checked, then renamed.
- The CurseForge API key is in `ModCatalog.kt` as a byte array. Rust uses the same key.
- `preferModMirror` is in `config.toml`. Kotlin writes bare `null` values there;
  J1's reader already drops those lines.

## 4. Design

### 4.1 Shapes stay, transport goes

The Slint files and the rendering half of `packs.rs` already read three JSON
shapes. Rust keeps producing exactly those shapes, now from Rust state instead of
service events. `installer.*` and `tasks.*` actions become function calls into
new modules. Nothing about them goes through `call`/`pump` or `LazyService` after J4.

Suggested modules (adjust names to the code you find):

- `src/tasks.rs`: task manager.
- `src/import/`: `scanner.rs`, `state.rs` (the view model), `installer.rs`,
  `options_txt.rs`, and in J4b `archive.rs`, `resolver.rs`.
- J4b: `src/download.rs`, `src/content_store.rs`, `src/catalog/`.

Blocking file work runs on `spawn_blocking`; results return to the UI thread
through the existing channel pattern in `packs.rs`. Each open of the import screen
gets a generation number so late results of a closed screen are dropped.

### 4.2 Tasks (J4a)

- A task has a run id (UUIDv7), a title, a status, a tree of rows with message
  and fraction per path, and an error message. Statuses and their ordinals follow
  `Task2Status`, because the UI reads `status` as a number.
- Import tasks are single leaves. Build the tree type anyway where `taskSnapshot`
  needs it (group rows, `depth`, `expanded`), so J5 can add grouped tasks without
  changing the shape. Do not port `Task2.Group`/`Sequence` execution.
- A dedupe key refuses a second task with the same key while the first is unfinished.
- The list lives for the launcher's lifetime, not across restarts. Updates to the
  UI are limited to about 30 per second per topic, as `sample(34)` does.
- **Cancel (D7).** Each task has a cancellation token. Every loop that copies,
  extracts or downloads checks it. A cancelled import removes its staging folder
  and leaves no pack. The message is 任务已取消. The task screen gets one
  `CircleIconButton`-style cancel button on a running task, in the detail view and
  in the list row; follow the existing buttons in `ui/activity.slint`. No
  confirmation dialog. A finished task shows no cancel button.
- Closing the launcher with a running task cancels it and waits for the cleanup,
  bounded to a few seconds.

### 4.3 Scanner (J4a)

- Read the recorded Minecraft folders from `data.db`, read only, with the J2
  rules. A missing database, a foreign one or a wrong version gives an empty
  candidate list and a log line, not an error on screen.
- Skip the launcher's own Minecraft folder. Port `scanCandidate` exactly: the
  manifest name rule, the game folder choice (version folder, then installation
  root, whichever has a jar directly in `mods`), `clientVersion` then
  `inheritsFrom` then `id`, supported versions, `detectLocalModLoader` with all
  its error cases. Sort and deduplicate as Kotlin does.
- `scanDirectory` for a folder the player picked: a `.minecraft`, one version
  folder, or neither (then the player picks the version in the editor).

### 4.4 Import screen state (J4a, archive parts in J4b)

Port `LocalModpackInstallViewModel` as a plain state machine with the action
names `PackService.handle` accepts: `scan`, `archive`, `directory`, `candidate`,
`pickedCandidate`, `create`, `name`, `comment`, `icon`, `runtime`,
`includeSaves`, `optional`, `back`, `dismissCandidates`, `dismissMissing`,
`dismissError`, `install`. `install` returns the run id or the state with a
validation error or the missing-files confirmation, as today.

In J4a the `archive` action is still served by Kotlin; see 4.8.

### 4.5 Installer (J4a; archive source in J4b)

Port `LocalModpackInstaller` exactly:

- Validation and its Chinese messages, including the HTTPS rule for the icon link.
- Staging folder name and target name from `LocalModpackStore`; remove a stale
  staging folder first.
- `shouldCopyGameFile` with both exclusion lists, the `.log`/`.jar` rule and the
  saves switch. Symbolic links are not followed.
- `writeMinecraftOptions`, unless a copied game folder brought its own `options.txt`.
- Commit under one lock: unique name among existing local packs, write
  `rdi-local-pack.json` (same fields and encoding J2 reads), one move.
- On failure or cancel: staging folder and target are removed.
- Every target path is checked to stay inside the pack folder (`checkedTarget`).

### 4.6 Downloader, cache, catalog (J4b)

**Downloader (D6).** Port `FileDownloader`'s behavior: several sources tried in
order, five attempts, a size probe, ranged download in 1MiB work-queue pieces
over several connections when the server allows ranges, a fallback to one stream
when it does not or when the ranged result fails validation, per-URL headers,
progress with bytes, total and speed, cancellation. Read the Kotlin file and its
tests for the rules on retries and range support; do not invent different ones.
Use the launcher's `reqwest` client so the system proxy applies.

**Cache (D5).** Port the parts of `ClientContentStore` that `materialize` uses:
cache entries at `<dlc>/<digest>.<suffix>` with Kotlin's suffixes and digest
normalization, lookup by any given digest, single flight per digest, download to
a temporary file, digest check, commit by rename, aliases for the other digests,
hard link into the target with copy as fallback, reuse of an existing correct
target, the symbolic link and path checks, and the three limits of 32. SHA-1,
SHA-256/512 as Kotlin uses them, and the CurseForge murmur2 with its whitespace
rule. Skip the migration functions.

**Catalog (D3).** Port the whole module: models, `DefaultModCatalog`, the
CurseForge and Modrinth adapters, the HTTP transport with the mirror preference
and fallback, the memory cache with its policy, the identity index, local file
hashing, metadata lookup, and the error types with their Chinese messages. The
identity index opens `mod_catalog.db` read only (D4); read `ModCatalog.sq` for the
queries. Keep the public surface close to `ModCatalog.kt` so later screens can
use it. Port the unit tests that do not need the network
(`CatalogDomainTest`, `MemoryCatalogCacheTest`, `PlatformAdapterTest`,
`BundledCatalogDatabaseTest`) with a fake transport. `RealCatalogSmokeTest` stays
an ignored test.

### 4.7 Archives (J4b)

Port `ModpackArchive.kt` and `ModpackArchiveContentResolver.kt`:

- Zip reading with the Chinese file name fallback of `openChineseZip`.
- Manifest discovery, CurseForge `manifest.json` and Modrinth `modrinth.index.json`,
  overrides, embedded jars, optional files, unavailable files, the read stages
  and their progress text.
- Every check, with its Chinese message: `safeTarget`, `safeFileName`,
  `validUrl`, `encodeIllegalUrlChars`, `validSha1`, target conflicts, catalog
  target validation, supported version and loader.
- The archive must be unchanged between preview and install (size and modified time).
- Name and icon resolution in two stages (URL hints, embedded hashes), cached per
  archive identity as the view model does.
- Install: required files plus chosen optional files through the cache, then
  overrides with the 20GB limit and per-file temporary names.

### 4.8 The service during J4a and after J4b

J4a keeps the JVM for one thing in import: reading an archive. Choose the smaller
of these and state the choice in the gate summary:

- (a) The Rust state machine owns the screen. For the `archive` action only, it
  drives the Kotlin installer as today and mirrors its `installer.state` into the
  Rust state until the editor closes or the install is submitted. The submitted
  archive task then still runs in Kotlin and appears through `tasks.state`.
- (b) Leave `installer.*` in Kotlin entirely for J4a and move only tasks, the
  scanner and the two non-archive sources behind a switch in `packs.rs`.

Either way, in J4a the task list shows Rust tasks and Kotlin tasks together:
merge the service's `tasks.state` rows with the Rust list, newest first, and
route `tasks.watch` and cancel by which side owns the run id. Cancel for a Kotlin
task needs a new `tasks.cancel {runId}` method (`ClientTaskManager.cancel`).

After J4b remove from `ui-service`: every `installer.*` handler, `tasks.*`,
`task.state`, the installer in `isIdle`, `stopTasks`, `PackServiceDependencies`'
installer and catalog, and their tests. Remove `tasks.subscribe` from the
`LazyService` prelude and the import and task screens from the idle rule and from
`service_lost`. The protocol version goes to 4 at GJ4a if J4a adds
`tasks.cancel`, and to the next number at GJ4b when the methods are removed;
update `protocol/README.md` and `messages.json` both times.

Logout stays refused while any task runs, now including Rust tasks.

## 5. Steps

### J4a

1. Fixtures first. Add shared fixtures under `protocol/fixtures/`, each written
   or checked by a Kotlin test so both sides agree: scanner manifests with the
   expected candidate or rejection, `shouldCopyGameFile` cases, `options.txt`
   output per Minecraft version, `uniqueName` cases, `installerSnapshot` and
   `taskSnapshot` examples. Fixtures hold fake data only.
2. Task manager with tests: statuses, dedupe, cancel, snapshot shape.
3. Scanner with tests against the fixtures and a temporary `data.db`.
4. Installer for the empty and game folder sources, with tests: exclusions,
   saves switch, cancel mid-copy leaves nothing, name collision, path escape.
5. State machine and the `packs.rs` wiring, per the choice in 4.8.
6. Cancel button, and the merged task list.
7. Kotlin: `tasks.cancel` if needed, tests.
8. Docs: `protocol/README.md`, `README.md`, `PROGRESS.md`, status lines in
   `JVM-REMOVAL-PLAN.md`.
9. Validate (section 6), write the gate files in `evidence/jvm-removal/j4a/`, stop.

### J4b

1. Fixtures: hostile and valid archives built by a Kotlin test (path escapes,
   absolute paths, bad URLs, bad SHA-1, conflicts, Chinese names in both
   encodings), catalog request and answer pairs, digest cases including murmur2,
   cache layout examples.
2. Downloader with tests against a local test server: ranges, no ranges, a
   failing first source, a wrong digest, cancel, resume of attempts.
3. Cache with tests ported from `ClientContentStoreTest` for the parts in scope.
4. Catalog with its ported tests.
5. Archive reader and resolver with tests.
6. Archive source in the installer; remove the J4a bridge.
7. Remove the Kotlin side and bump the protocol, per 4.8.
8. Docs, validation, gate files in `evidence/jvm-removal/j4b/`, stop.

## 6. Validation and gates

Run builds on Windows through `pwsh.exe` or IntelliJ MCP, never with the WSL
toolchain. There is no root `gradlew`; use the wrapper in `client/ui`. If the
owner has `cargo preview-run` open it locks `target/release/rdi-slint-proto.exe`
and its JVM holds the jars under `ui-service/build/install`; ask the owner to
close it before `installDist` or a release build.

From `client/slint-proto`:

```powershell
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo build --release
```

From `client/ui`:

```powershell
.\gradlew.bat :ui-service:test :client-core:test :ui-service:installDist --no-daemon
```

Baselines at the J3 commit: Rust bin 54, lib 11, renderer 63 with 1 ignored;
`ui-service` 25; `client-core` has 4 known failures (`LocalModpackScannerTest`,
`ModpackUploadRuntimeGuardTest`, `ModpackArchiveReaderTest` with old Fabric
expectations, `MediaProcGameClasspathTest`). Report new counts and explain every
difference. Note that two of the known failures are in classes J4 ports: read
why they fail before using those tests as a reference, and say in the gate
summary which behavior you followed. Also run `cargo-zigbuild check --release`
for `aarch64-apple-darwin` and `x86_64-unknown-linux-gnu`.

Live run: release build, isolated `RDI_DATA_DIR`, driven through the slint-ui MCP
with `cargo preview-run`, not native mouse scripts. Follow the J3 gate summary
for the setup. Record each result.

GJ4a:

| Check | Expected |
|---|---|
| Open the import screen, wait | No `java` child. Candidates from a prepared `data.db` are listed |
| Create an empty pack | No `java` child. Task reaches 100%, the pack list refreshes, `rdi-local-pack.json` is correct |
| Import a prepared game folder, with and without saves | Excluded files absent, saves follow the switch |
| Cancel a large folder copy | Task shows 任务已取消, no pack folder and no staging folder remain |
| Open the task screen after an idle minute | The finished tasks are still listed; no `java` child |
| Pick an archive | Works as before J4a through the service; its task appears in the same list and can be cancelled |
| Missing or foreign `data.db` | Empty candidate list, no error dialog; create and folder import still work |
| Log out while a Rust task runs | Refused with the existing message |

GJ4b:

| Check | Expected |
|---|---|
| Import one CurseForge and one Modrinth archive | No `java` child at any point. Files match their SHA-1; overrides applied |
| Archive with unavailable files | Confirmation dialog lists them; import continues without them |
| Optional files | Only the chosen ones are installed |
| Second import of the same archive | Files come from the cache; no download |
| Cancel during download | No pack, no staging folder, no partial file in the pack; cache holds only complete files |
| Mirror preference on and off, mirror unreachable | Falls back to the origin |
| A large file | Ranged download is used; record time against a single stream |
| Hostile archives from the fixtures | Each is refused with its Chinese message; nothing is written outside the staging folder |
| Without `mod_catalog.db` | Import works with platform names |
| Measure | Launcher working set during a large import, and import time against the Kotlin path at the J3 commit |

GJ4a passed on 2026-11-10 with some checks moved to GJ4b. Add the items
marked "GJ4b" in `evidence/jvm-removal/j4a/owner-checklist.md` to the GJ4b
owner checklist; the Kotlin-task cancel item is replaced by the removal of
Kotlin tasks.

Write `gate-summary.md` (what changed, automated results, live results,
measurements, the 4.8 choice, anything not covered) and `owner-checklist.md` for
each gate. Owner-only checks: a pack imported by Rust starts and plays; Compose
lists that pack and reuses the cache, and the reverse; the cancel button's look;
a real large pack; behavior with the window minimized. Leave the isolated
live-run folder untracked.

## 7. Rules and gotchas

- Do not commit. The owner reviews at each gate and directs the commit. Never
  add a `Co-Authored-By` trailer.
- Never delete project files; move removed files to
  `DEL/<new directory>/<original relative path>` under the repository root.
- Player-facing text is Chinese, without spaces between Chinese and Latin text
  or digits, and without implementation words (Java, JVM, service process). Reuse
  Kotlin's messages word for word where a check is ported.
- New model ids are UUIDv7.
- Kotlin string templates that touch surrounding text use braces. Failures are
  logged with the error or propagated, never swallowed. The same holds in Rust:
  no silent `ok()` on a failure the player should see.
- LF line endings. Do not commit line-ending-only changes. Do not reformat files
  you are not changing; J3 mixed formatting changes into its diff and that made
  the review slower.
- New crates: pin exact versions as `Cargo.toml` does, prefer pure Rust, and
  check that the macOS and Linux cross-checks still pass. Expected: `zip`,
  `sha1`, `sha2`, `encoding_rs`, `uuid` with v7, `tokio-util` for cancellation.
- Never log URLs with credentials, request headers, the JWT or the CurseForge key.
- Never write `data.db` or `mod_catalog.db`.
- Do not replay requests from a dead service generation (J3 rule, still valid).
- MCP clicks do not focus the window; anything about focus or minimizing goes on
  the owner checklist. Do not run live checks during a heavy compile.
- With the owner's system proxy at `127.0.0.1:10808` switched off, the launcher
  cannot connect; that is existing behavior.

## 8. Out of scope

- Installing or updating RDI packs, and any new screen (D1).
- Installing Minecraft or loaders, game start, the console, Microsoft login (J5).
- The old-cache migration and the cache cleanup service.
- Creating or migrating `data.db`; shipping `mod_catalog.db` (J6).
- Changing the Compose launcher.
- Keeping the task list across launcher restarts.
