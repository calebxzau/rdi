# KFF plan: Kotlin comes from Kotlin for Forge

Drafted 2026-11-23. Status: **shelved by the owner on 2026-11-23. The Rust launcher keeps Kotlin on the class path and keeps thinning (as Compose does); nothing in this plan is implemented. Revisit after the Slint switch.** The answers below stay for that time.

Owner answers, 2026-11-23:

- K1: Compose does not change.
- K2: check against the newest KFF on CurseForge/Modrinth. Found 2026-11-23: Forge 1.20.1 is
  **4.12.0** (Kotlin 2.2, `Uuid` without `generateV7`); 4.13.0 (Kotlin 2.4.21) exists only on the
  KFF Maven repository and a mirror, not on CurseForge or Modrinth. NeoForge 1.21.1 is **5.13.0**
  (Kotlin 2.4.21, coroutines 1.11.0, serialization 1.13.0).
- K3: if it runs, it is fine; RMCP failures on old KFF are accepted.
- K4: no launcher check; the server already refuses rooms without KFF.
- K5: no.
- K6: the owner wants no core mod code change. Conflicts found: with KFF 4.12.0 the three
  `Uuid.generateV7()` calls in the 1.20.1 core fail, and every class in `rdi-mediaproc.jar`
  uses the Kotlin runtime, so it cannot stay on the class path without Kotlin. Awaiting the
  owner's choice.

Owner decisions already made (2026-11-23), not open again here:

- A room game no longer gets Kotlin on its class path, and the launcher no longer thins
  Kotlin mods. Kotlin for Forge (KFF) in the room's `mods` supplies the Kotlin runtime.
- zstd-jni **stays** on the class path, and the step that removes mods' own zstd-jni stays
  (`ZSTD-LIB-PLAN.md` remains deferred).
- The RDI core mod keeps Kotlin as a compile-time dependency and does not declare KFF in
  `mods.toml`. No minimum KFF version is enforced for now.

Read first: the root `AGENTS.md`, `J5B-PLAN.md` (sections 2 and 4), `J5-PLAN.md` section 5.
Written for an agent with no prior context. User-facing text says 房间 and 整合包.

## 1. Goal

A Forge 1.20.1 or NeoForge 1.21.1 room game started by the Rust launcher has only zstd-jni,
JavaCPP and FFmpeg on its extra class path. `mods` keeps every Kotlin mod as downloaded.

In scope:

| Part | Where |
|---|---|
| mediaproc classes move into the core mod | `client/mc/1.20.1-forge`, `client/mc/1.21.1-neoforge` |
| Core mod must link against the Kotlin that KFF ships | same builds, plus `mc/chunkcache` |
| A build check that proves it | same builds |
| `game-runtime` folder without Kotlin and without the mediaproc jar | `client/ui/ui-service` |
| Undo earlier thinning; keep only the zstd-jni removal | `src/play/thin.rs` |
| Class path, start order, KFF check | `src/play/runtime.rs`, `src/play/room.rs` |

Out of scope:

- The Compose launcher's own behavior (K1), GTNH, Fabric (they start through the service
  until J5c).
- zstd-jni as a library jar (`ZSTD-LIB-PLAN.md`).
- The server core mods and `server/master`. No server change is needed.
- GJ5b2 (window icon, recovery, removing the room service calls). It follows this plan.

## 2. Code facts

Checked on 2026-11-23 at commit `c59ee6d3`.

- **Rust today.** `play::room` calls `thin::thin_mods` after the mod sync, then puts
  `GameRuntime::classpath()` (5 Kotlin jars, zstd-jni, mediaproc jars) first on the class
  path. `runtime.rs` requires exactly 5 Kotlin jars in `manifest.json` (schema 1).
- **Thinning changes files in `mods`.** A thinned jar replaces the original. The original is
  kept at `<tools>/kotlin-runtime/original-kotlin-mod/<sha1 of original>.jar`, the thin copy at
  `thin-kotlin-mod/v3-<sha1 of original>.jar`, and the file in `mods` is a byte copy of that
  thin copy. Older launchers left `thin-kotlin-mod/v2-*.jar` and `thin-kff/v1-*.jar` with
  originals in `original-kff/`. All of these exist in `client/ui/run/tools/kotlin-runtime`.
- **Compose and Rust share `mods` and the cache.** The room mod sync treats a mod as present
  when a file of its name exists; it does not check the hash. So a thinned KFF stays thinned
  until something restores it.
- **KFF 4.x (Forge 1.20.1)** is one jar with `FMLModType: LIBRARY` and the Kotlin classes
  directly inside (`kotlin/`, `kotlinx/`), plus jarjar entries `kfflang`, `kfflib`, `kffmod`.
  **KFF 5.x (NeoForge 1.21.1)** is `FMLModType: LIBRARY` with Kotlin as nested jarjar jars.
- **Kotlin versions inside KFF**, read from the jars:

  | KFF | Kotlin | coroutines | serialization |
  |---|---|---|---|
  | 4.11.0 | 2.0.x (no `kotlin.uuid`) | 1.8.1 | not read |
  | 4.12.0 | 2.2.x (`Uuid` without `generateV7`) | 1.10.2 | not read |
  | 5.11.0 | 2.3.0 | 1.10.2 | 1.9.0 |
  | 5.12.0 | 2.4.0 | 1.11.0 | 1.11.0 |

- **The core mods are built with Kotlin 2.4.21**, coroutines 1.11.0, serialization 1.11.0.
  Their jars already contain no Kotlin: Kotlin reaches the game only through the class path.
  They contain nested `kotaml`, `okio`, `snakeyaml-engine-kmp`, `urlencoder`, and the classes
  of `c-mc-common`, `rmcp-common`, `rmcp-client`.
- **mediaproc** (`client/mediaproc`, 17 Kotlin files, 121KB jar) is not in the core jar. The
  core mods use it for FFmpeg audio and AVIF textures. It needs JavaCPP and FFmpeg, which are
  Java libraries.
- **Nothing in the core jars loads before the game layer**: no `META-INF/services`; 1.20.1 has
  one JavaScript coremod. Forgeguard is Java.
- **The server core mods already run on KFF's Kotlin.** They do not bundle Kotlin, and
  `HostControlService` refuses to start a modern Forge or NeoForge room without KFF
  (请先为此房间安装kotlinforforge模组才能启动). The shared sources (`chunkcache`, `syncchunk`,
  `codec`) therefore already work this way on the server.
- **The Uuid calls**: `mc/chunkcache/.../ClientChunkDeltaStore.kt:84`,
  `client/mc/1.20.1-forge/.../preview/JeiPreviewRecipeExport.kt:97`,
  `.../preview/PreviewExportStore.kt:30`.
- **YAML** is used only by `mc/rmcp/client/.../mcp/Serdes.kt`.

## 3. Linkage findings

I checked every reference to `kotlin.*` and `kotlinx.*` classes, methods and fields in the
built jars against the classes inside each KFF. Jars: client core 1.20.1 built 2026-11-22,
client core 1.21.1 built 2026-11-21, the server cores, `rdi-mediaproc.jar`.

| Jar | KFF | Result |
|---|---|---|
| client core 1.20.1 | 4.11.0 | **3 classes missing**: `kotlin.uuid.Uuid` (the three files above) and `kotlin.coroutines.jvm.internal.SpillingKt`, needed by nested `snakeyaml-engine-kmp` (`Dump`) |
| client core 1.20.1 | 4.12.0 | **1 method missing**: `Uuid.generateV7()` |
| client core 1.21.1 | 5.11.0, 5.12.0 | clean |
| server core 1.20.1 | 4.11.0 | clean |
| server core 1.21.1 | 5.11.0 | clean |
| mediaproc | 4.11.0, 4.12.0, 5.11.0 | clean |

This check proves that names resolve. It does not prove behavior, for example generated
serializers from 1.11.0 running on an older serialization runtime. Only a real run does.

Consequence: today a Forge 1.20.1 room with KFF 4.11.0 or 4.12.0 would crash in the chunk
delta store without the class path Kotlin. Step 2 removes that.

## 4. Decisions for the owner

| # | Question | Recommendation |
|---|---|---|
| K1 | Does the Compose launcher change too? | **No.** Compose keeps the class path Kotlin and thinning until it is retired. The new core mod works with both. On a machine that uses both launchers, each one converts `mods` back on its next start; that is automatic. Changing a launcher that players use today adds release risk for no player benefit. |
| K2 | Which KFF versions must the core mod link against? | **4.11.0 for Forge 1.20.1 and 5.11.0 for NeoForge 1.21.1**, the oldest versions found in local room data. This is a build check, not a rule for players. Please confirm the oldest KFF in production rooms; I have only looked at this machine. |
| K3 | The YAML library needs Kotlin 2.2 and KFF 4.11.0 has 2.0 | **Test first.** If the in-game control server (RMCP) fails on a 4.11.0 room, pin `snakeyaml-engine-kmp` and `kotaml` to versions built with an older Kotlin. If no such version works, accept that RMCP is unavailable on 4.11.0 rooms and list the nested jar as a known exception in the build check. |
| K4 | A room `mods` folder without a usable KFF | **Stop before starting the game** with 「此房间缺少Kotlin for Forge，无法启动」. The server already refuses such rooms, so this only catches a damaged folder. |
| K5 | Should development runs (`runClient`) also take Kotlin from KFF? | **Not in this plan.** They keep `additionalRuntimeClasspath`. The gate is tested with built jars in real rooms. |
| K6 | One gate or two? | **Two.** GK1: the core mod, which reaches every player through the update check, tested with Compose before you publish it. GK2: the launcher. The launcher change must not ship before the new core mod is published, because it removes the mediaproc jar from the class path. |

## 5. Design

### Core mod

- **mediaproc inside the jar.** Add `from(project(":mediaproc").sourceSets.main.output)` to the
  `jar` task of both client builds, as `rmcp-common` is added today
  (`1.20.1-forge/build.gradle:465`, `1.21.1-neoforge/build.gradle:105`). The package stays
  `calebxzau.rdi.mediaproc`. JavaCPP and FFmpeg are not bundled.
- **Compose compatibility.** Compose still puts `rdi-mediaproc.jar` on the class path. The
  game then has the package twice: in the mod and on the class path. The mod's copy is the
  one the mod sees. This must be shown in a real Compose start (GK1), not assumed.
- **Uuid.** Replace the three `kotlin.uuid.Uuid.generateV7()` calls with a UUIDv7 helper that
  uses only `java.util.UUID` (reuse one from `mc/common` if it exists). Remove the
  `ExperimentalUuidApi` opt-ins.
- **Build check `kotlinLinkageCheck`** in both client builds. Inputs: the built core jar with
  its nested jars, and the KFF jars from K2, resolved from the KFF Maven repository that both
  builds already declare. It reads class files (ASM), collects every reference to `kotlin/`,
  `kotlinx/` and `_COROUTINE/`, and fails when a class or member is missing. A member counts
  as found if the class or a supertype inside the runtime declares it; a supertype outside the
  runtime is reported as unknown, not as an error. `build` depends on it. Known exceptions
  (K3) are a named list in the task.

### `game-runtime` folder (Rust only)

- `manifest.json` schema 2: no `kotlin`; `media` holds JavaCPP, FFmpeg and the native jar,
  without the mediaproc jar. `GameRuntimeExport.kt` drops the jar that holds
  `calebxzau.rdi.mediaproc` classes. `GameRuntimeExportTest` follows.
- `runtime.rs`: `SCHEMA = 2`, no Kotlin field, `classpath()` returns zstd-jni then media.
  A schema 1 folder is refused with the existing 游戏运行库版本不匹配 message.

### `mods` preparation in Rust

`thin.rs` is replaced by two functions without Slint types:

1. **`restore_kotlin_mods(mods, cache)`** undoes earlier thinning.
   - Index: every `thin-kotlin-mod/v*-<sha>.jar` and `thin-kff/v*-<sha>.jar` whose original
     exists in `original-kotlin-mod/<sha>.jar` or `original-kff/<sha>.jar`.
   - Candidates: jars in `mods` that contain `META-INF/jarjar/metadata.json`. A candidate
     whose size equals an indexed thin copy is hashed (SHA-1); on a match the original is
     checked against its name and put back through a temporary file and a rename. Never write
     through the existing file: it can be a hard link into the download cache.
   - A KFF jar that still has no Kotlin inside after that (no `kotlin/Unit.class` and no nested
     `kotlin-stdlib`) is deleted, so the mod sync downloads it again.
   - Other mods that stay thinned because their original is gone are left alone and logged.
     They get Kotlin from KFF.
2. **`dedup_zstd(mods, cache)`** keeps today's rule for one artifact: a nested jar that
   jarjar declares as `com.github.luben:zstd-jni` is removed, at any depth up to 4, with the
   metadata rewritten. No Kotlin artifacts, no stripping of `kotlin/` classes. Cache:
   `<tools>/kotlin-runtime/thin-zstd/z1-<sha1>.jar`. The pass does nothing to a jar that has
   no such entry, so running it twice changes nothing.

Start order in `play::room`: `restore_kotlin_mods`, base mod sync, extra mod sync,
removed-mod cleanup, RDI core, **KFF check** (K4), `dedup_zstd`, media natives, command.
The KFF check requires exactly one KFF file (the three `thedarkcolour` artifacts, as
`inspect` detects today) with Kotlin inside; more than one keeps the message
发现多个Kotlin for Forge文件.

Console lines: `[RDI] 还原Kotlin模组...` only when something is restored.

### Both launchers on one folder

Compose thins whatever is not thin, and stores what it saw as the original. Rust restores a
file that equals a thin copy. A jar that Rust stripped of zstd-jni and Compose then thinned
restores to the zstd-stripped jar, which is correct for both. Covered by a test (step 6).

## 6. Steps

Each step ends with its tests green.

**Stage 1: core mod (stops at GK1)**

1. Baseline: build both client core jars; run the tests of `client/mc/1.20.1-forge` (`:test`,
   Temurin 17 `JAVA_HOME`) and `client/mc/1.21.1-neoforge`; record known failures
   (`DmRegionChangeLogTest` fails at HEAD on 1.21.1).
2. Replace the three `Uuid.generateV7()` calls.
3. Add `kotlinLinkageCheck` with the K2 versions. It must fail before step 2's change is
   built and pass after it, except for the K3 finding.
4. Bundle mediaproc into both jars. Check the jar: `calebxzau/rdi/mediaproc/` present, no
   `org/bytedeco/`, no `kotlin/`.
5. K3: start a Forge 1.20.1 room with KFF 4.11.0 using the new jar and use RMCP once
   (`screen_read`). Act on the result as K3 says.
6. Compose check with the new jar, one Forge 1.20.1 room and one NeoForge 1.21.1 room: the
   game reaches the world, an AVIF texture shows, FFmpeg audio plays.
   **Stop at GK1.** The owner publishes the core mod.

**Stage 2: Rust launcher (stops at GK2)**

7. Fixtures first. The Kotlin fixture test writes `protocol/fixtures/kff-restore/`: a `mods`
   folder thinned by Kotlin, the matching `kotlin-runtime` cache, and the expected restored
   hashes. Inputs are the existing `j5b-thin` jars plus one jar that nests zstd-jni.
8. `game-runtime` schema 2 in Kotlin and Rust.
9. `restore_kotlin_mods` and `dedup_zstd` against the fixtures: restore from `v3`, `v2` and
   `v1` copies; original missing; thin KFF without an original is deleted; a second run
   changes nothing; a zstd-stripped jar thinned by Kotlin restores to the zstd-stripped jar.
   Archive the old thinning test data under `DEL/` only if Kotlin no longer reads it
   (`J5bKotlinThinFixtureTest` still does, so it most likely stays).
10. Launch command: the expected command is the Kotlin fixture `j5b-launch.json` minus the five
    Kotlin jars and the mediaproc jar. The test derives it from the fixture; Kotlin's fixture
    stays as it is.
11. Wire the new order into `play::room`, add the KFF check and its message.
12. Live runs (section 7). **Stop at GK2.**

## 7. Validation and gates

Automated:

- Rust: `cargo test --lib --bins` and `cargo clippy --all-targets -- -D warnings` through
  Windows `pwsh.exe`; `cargo-zigbuild check` for mac and Linux.
- Kotlin: `ui-service` tests, the fixture tests in `client/mclaunch`, both core mod test
  suites, `kotlinLinkageCheck`. Run Gradle on the Windows host from the module folder.
- No Modpack server logic changes, so `modpackServiceTest` is not required.

Live, with the Rust launcher on real rooms (isolate `RDI_DATA_DIR` as in J5b where the test
must not touch the owner's folders):

| Room | Start state of `mods` | Check |
|---|---|---|
| Forge 1.20.1, KFF 4.11.0 | thinned by Compose | restored; world loads; AVIF texture; FFmpeg audio; chunk cache active |
| Forge 1.20.1, KFF 4.12.0 | fresh install | world loads |
| NeoForge 1.21.1, KFF 5.11.0 | thinned by Compose | restored; world loads; AVIF; audio |
| NeoForge 1.21.1, KFF 5.12.0 | fresh install | world loads |
| Any room | then started from Compose, then from Rust again | both start; no file is downloaded again without need |
| Any room, KFF file removed by hand | | downloaded again by the mod sync, or the K4 message |

GK1 passes when steps 1-6 are green and the owner accepts the Compose runs. GK2 passes when
the table above is green and `latest.log` of each run shows no `NoClassDefFoundError` or
`NoSuchMethodError` for `kotlin` or `kotlinx`. Gate files go to
`evidence/jvm-removal/kff/`.

## 8. Risks

- **Old KFF in a production room.** A room with a KFF older than the K2 versions can fail in
  ways the build check does not cover. No minimum is enforced (owner decision), so the first
  sign would be a player's crash log.
- **Behavior on an older runtime.** Linking is checked; behavior is not. Serialization 1.11.0
  generated code on KFF 4.11.0's older runtime is the case to watch in the 4.11.0 run.
- **Other Kotlin mods.** Until now every Kotlin mod ran on the launcher's Kotlin 2.4. After
  this change they run on what KFF and the mods' own nested jars provide, as on any other
  launcher. A mod that only worked because of the newer class path Kotlin would break.
- **Publishing order.** The Rust change before the new core mod gives a game without
  mediaproc. Stage 2 must not reach players first.
- **The core mod is downloaded whole on every change.** This plan changes it once; it grows
  by about 120KB.
- **Core jar in `mods` is a hard link** into the download cache; the restore and zstd steps
  must replace files by rename, as thinning does today.

## 9. Not yet checked

- The oldest KFF version in production rooms (K2).
- Whether the nested Kotlin jars of KFF 5.x sit in a layer that the core mod's mixin plugin
  can see at mixin time. Other Kotlin mods rely on it, and the server core does, but the
  client core has not been run this way.
- JavaCPP callbacks defined in mediaproc (`AvioInput`, `MemoryAvioInput`) when mediaproc is
  in the mod and JavaCPP on the class path. It is the same split the core mod and mediaproc
  have today, one level further in.
- How to test an unpublished core jar from the Rust launcher: `play/core.rs` replaces the
  file when its SHA-1 differs from the server's. Either a local master serving the built jar
  or a development-only override is needed; choose before step 5.
- Serialization version inside KFF 4.11.0 and 4.12.0.
- Fabric and GTNH rooms are untouched by this plan; J5c decides how they get mediaproc.
