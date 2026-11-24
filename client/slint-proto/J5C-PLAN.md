# J5c plan: GTNH, Fabric and Microsoft online play from Rust

Drafted 2026-11-23. Status: **approved by the owner on 2026-11-24, C1-C4 as recommended; C5: GTNH
test pack is `rdi5ship/mc/versions/69eaf0a3cae162f1a7e8448a_1.0.0`.** Steps 1-4 implemented
2026-11-24 (see "Progress" at the end); live checks for GJ5c1 under way.

Read first: the root `AGENTS.md`, `J5-PLAN.md` (sections 3 and 5 still apply),
`J5B-PLAN.md` (the room flow this extends), `JVM-REMOVAL-PLAN.md` section 1.

Written for an agent with no prior context. User-facing text says 房间, 整合包 and 正版登录,
never service, proxy or container.

## 1. Goal

After J5b the Slint launcher still starts the `ui-service` JVM in three cases:

| Case | Where Slint hands off today |
|---|---|
| GTNH (1.7.10 Forge) room or local pack | `play::room::supported` returns `None` → `host.start`; `mc_install::meta::loader` rejects 1.7.10 → `packPlay.start` |
| Fabric 1.20.1 room or local pack | same two checks |
| Microsoft online play of a local pack | `play_ui.rs` `start_native_play` returns `false` when 正版 is selected → `packPlay.start` |
| An installed RDI pack played alone from the 整合包 tab (found 2026-11-24) | `open_native_play` returns `false` for RDI packs → `packPlay.open`, then `packPlay.start` → Kotlin `rdiPackPlayArgs` |

After J5c none of them does. The Slint launcher then makes no service call at all
(`packPlay.start|cancel`, `host.start`, `game.subscribe|stopAll`), and the Rust client
code for the service (`src/service.rs`, `src/lazy_service.rs`, `src/protocol.rs`, the
mirrored service sessions in `play`) is archived under `DEL/`. The Kotlin `ui-service`
module stays for J6, because the `:ui-service:gameRuntime` task lives there.

Out of scope: J6 (packaging, removing the Kotlin module, importing
`local_credentials.json`), the manual node switch, reuse of other launchers' files (J5 D9).

## 2. Code facts

Line counts are Kotlin main sources, as a size guide.

### GTNH

| Kotlin source | Lines | What it does |
|---|---|---|
| `mclaunch/.../GtnhLaunchSupport.kt` | 337 | Reads Prism patches from `<versionDir>/gtnh/patches/*.json`; builds the loader manifest (main class `com.gtnewhorizons.retrofuturabootstrap.Main`, `--tweakClass` list, legacy `minecraftArguments`); class path = lwjgl3ify forgePatches jar + extension-local libraries + global libraries + `versions/1.7.10/1.7.10.jar`; downloads missing global libraries; hard-links the lwjgl3ify runtime mod into `mods`; extra JVM arguments from the forgePatches patch |
| `MinecraftLauncher.kt` (GTNH branches, ~60) | | Drops LWJGL2 libraries from the vanilla manifest; natives from the global natives dir; strips any `-cp` from manifest JVM arguments; Kotlin runtime **off** (Forgelin supplies it), zstd **on** (`RdiRuntimeSelection`) |

- The bundled `mcmeta/1.7.10.json` and `assets-index/1.7.10.json` already exist in
  `client/mclaunch/src/main/resources`; Rust `mc_install::meta` includes only 1.20.1/1.21.1 today.
- `local_packs.rs` already maps `V071` to `1.7.10`.
- RDI core slug `1.7.10-forge` uses the same `play::core` code as the others.

### Fabric

| Kotlin source | Lines | What it does |
|---|---|---|
| `ClientLoaderRuntime.kt` | 53 | Fabric is 1.20.1 only; profile id `fabric-loader-0.19.5-1.20.1`; profile URL on `meta.fabricmc.net` |
| `FabricClientProfile.kt` | 96 | Validates the profile: id, `inheritsFrom`, main class `KnotClient`, one loader and one intermediary coordinate, no Forge/NeoForge, every library URL on `https://maven.fabricmc.net` with a path matching its coordinate, sha1 present |
| `McInstall.kt` Fabric part | ~200 | Reuses a valid saved profile; else fetches it, fills missing sha1 from `<url>.sha1`, downloads and verifies each library, then writes `versions/<id>/<id>.json` last; one install per profile at a time |

- No Kotlin runtime and no thinning for Fabric (`needsForgeRuntime = false`); zstd on.
- Removed-mod slugs for Fabric are already in `play::mods_sync` (`FORGE_ONLY_REMOVED_SLUGS`).

### Microsoft online play

| Kotlin source | Lines | What it does |
|---|---|---|
| `client-core/.../account/GameAccountService.kt` | 120 | Restores the saved session for (RDI account, bound msid), refreshes it, checks the profile id equals the msid, else device-code login; returns name, uuid, token, `msa` |
| `MsaSessionStore.kt` | 88 | `msa_sessions.json`: `accounts.<accountId>.<msid>` = MinecraftAuth JSON; a damaged file is renamed `.broken-<ms>` |

- Rust already has the device-code sign-in (`src/account/msa.rs`, Switch title id
  `00000000441cc96b`, used for binding). It discards the Microsoft refresh token and saves nothing.
- The play dialog already has the device-code UI (`ui/packs.slint` `device-uri`, 打开微软登录页面).
- J5 D7 settled: Rust does its own login and keeps its own record; Compose sessions are not read.
- **Found while drafting:** Kotlin's store decodes with `ignoreUnknownKeys` and rewrites the whole
  file from its data class, so any extra key Rust adds to `msa_sessions.json` is deleted the next
  time Compose saves a session. See decision C2.

## 3. Decisions for the owner

| # | Question | Recommendation |
|---|---|---|
| C1 | One gate or two? | **Two stops.** GJ5c1: GTNH and Fabric start from Rust, rooms and local packs (steps 1-5). GJ5c2: Microsoft online play and removing the service client from Slint (steps 6-8). |
| C2 | Where does Rust keep Microsoft sessions? D7 said "a separate key in `msa_sessions.json`", but Compose deletes unknown keys on save. | **A separate file, `msa_game_sessions.json`**, same folder, same layout idea: `accounts.<accountId>.<msid>` = `{refreshToken, mcToken, mcTokenExpiresAt, name}`. Plain text like today. RDI logout clears both files. The alternative, teaching Kotlin to keep unknown keys, changes Compose for no player gain. |
| C3 | Which Microsoft client id for play? | **Reuse the Switch id already used for binding**, so the same login works for players under 18. Keep the Microsoft refresh token; refresh the Minecraft token when it has under 1 hour left. A failed refresh falls back to a new device-code login, as Kotlin does. |
| C4 | What is archived after J5c? | **The Rust service client only**: `service.rs`, `lazy_service.rs`, `protocol.rs`, the protocol fixture tests that only exercise it, and the service-session mirror in `play`. The Kotlin `ui-service` module stays until J6. |
| C5 | GTNH test pack | **Please name one GTNH room (or a local GTNH pack folder) the agent may use for live tests.** Without it GTNH is verified only by the fixture. |

## 4. Design

Play code takes no Slint types (J5 section 5).

```
src/mc_install/meta.rs     1.7.10 metadata; LOADERS gains Fabric 1.20.1 (profile, not installer)
src/mc_install/fabric.rs   profile fetch, sha1 sidecars, validation, library download, publish last
src/mc_launch/gtnh.rs      Prism patches → loader manifest, class path, JVM args, runtime download plan
src/play/runtime.rs        selection per version: kotlin only 1.20.1/1.21.1 Forge-family, zstd for all three
src/account/msa.rs         keep the refresh token; refresh flow
src/msa_play.rs            msa_game_sessions.json store + resolve(accountId, msid) like OnlineGameAccountService
```

- `play::room::supported` and `mc_install::meta::loader` accept `1.7.10 forge` and `1.20.1 fabric`.
- `mc_launch::Request` gains an optional GTNH root; `command` stays the one place that orders arguments.
- Fabric install uses the J4b content store and the same per-key install lock as the loader installer.
- GTNH runtime libraries download through the content store with the same progress lines
  (`开始自动下载N个GTNH运行库`, `下载i/N: <name>`).
- Missing GTNH extension keeps Kotlin's message (`整合包缺少GTNH扩展包…`), shown in the console.
- Online play: `play_ui.rs` resolves the account first (device code into the existing dialog),
  then calls `play::start_local` with `user_type = msa`. Tokens never reach logs, events or Slint.

## 5. Steps

Each step ends with its tests green before the next one starts.

1. **Fixtures first (D6).** Extend the Kotlin fixture test to write `protocol/fixtures/j5c-launch.json`:
   full commands (tokens masked) for a GTNH pack (from a fixture `gtnh/` tree) and a Fabric
   1.20.1 pack, each as room (RDI core, zstd, `-Drdi.play`) and as local pack; Fabric profile
   validation cases (good, wrong host, path mismatch, Forge inside, missing sha1).
2. **Fabric install** (`mc_install/fabric.rs`) against a mock server. Compose must accept what
   Rust installed and the reverse (same `versions/<id>/<id>.json`, same library paths).
3. **GTNH launch support** (`mc_launch/gtnh.rs`) against the fixture.
4. **Runtime selection and start flow**: rooms and local packs for both, wired into
   `play_host` and `start_native_play`. `RoomOutcome::Service` goes away.
5. Live checks, then **stop at GJ5c1**.
6. **Microsoft sessions** (C2, C3): store, refresh, msid check, logout clears; mock live.com,
   Xbox and Minecraft endpoints in tests.
7. **Online local play** through the existing play dialog.
8. **Archive the service client** (C4) under `DEL/<archive>/client/slint-proto/...`;
   `cargo test` and the release build still pass with no JVM on `PATH` besides the game Java.
   **Stop at GJ5c2.**

## 6. Validation

- `cargo clippy`, `cargo test --workspace`, release build, `cargo-zigbuild check` for macOS arm64
  and Linux x64, the Kotlin fixture tests.
- Live on Windows with the service disabled (`evidence/jvm-removal/j5a/live/preview.ps1`),
  test account, isolated data folder:
  - GJ5c1: a GTNH room and a Fabric room reach the world and stay connected; a local GTNH
    pack and a local Fabric pack start offline; first start downloads the GTNH libraries and
    the Fabric profile, the second start downloads nothing; 终止游戏 during each download
    leaves no half-written file; Compose starts the same packs afterwards without reinstalling,
    and the reverse.
  - GJ5c2: online play of a local pack with a fresh login, then again without a login
    (saved session), then after the Minecraft token expired (forced by editing the expiry);
    a Microsoft account that is not the bound one is refused with Kotlin's message;
    logging out of RDI removes the session; Compose online play still works afterwards.
- Not possible for the agent: real Microsoft login (needs the owner's account and browser),
  macOS and Linux real starts. These go on the owner checklist.

## 7. Risks

- **GTNH command drift.** Its class path and argument order differ most from the modern path;
  the fixture covers the command, a real start covers the rest.
- **Fabric profile trust.** The profile comes from the network; the validation rules are the
  safety net and are ported one to one with tests per rule.
- **Microsoft tokens on disk.** Plain text, as today; never logged, never in `Debug`.
- **Archiving the service client** removes the fallback for anything this plan missed. Step 8
  greps for every remaining service method before moving files.

## 8. Not yet checked

- Whether a GTNH start needs the Forge 1.7.10 installer output at all, or only the patches,
  vanilla 1.7.10 and the extension; Kotlin's `prepareForLaunch` still runs the Forge install.
- Whether J4c pack install and `pack_install::state_in` already handle GTNH and Fabric room packs.
- Where the GTNH extension (`<versionDir>/gtnh/`) comes from for a room pack, and whether
  `RemovedModCleanupService` or the base-mod sync touch it.
- Whether the live.com refresh with the Switch title id returns a new refresh token each time
  (then the file must be rewritten on every refresh).
- What the window title/icon step does for 1.7.10 (the RDI 1.7.10 mod may read different properties).

## Progress (2026-11-24)

Scope addition found while implementing: playing an installed RDI pack alone (`rdiPackPlayArgs`:
RDI core, room connection, `-Drdi.play` room `test` port 55555, no room mods, no host start, no
host id) also went to the service. It is now `play::room::start_solo` and belongs to GJ5c1, since
C4 removes every service call.

Done, uncommitted:

| Step | Rust | Checked by |
|---|---|---|
| 1 Fixtures | `protocol/fixtures/j5c-launch.json`, `j5c/gtnh/patches` (real pack), `j5c/fabric-loader-0.19.5-1.20.1.json` | Kotlin `mclaunch/.../J5cLaunchCommandFixtureTest.kt` |
| 2 Fabric install | `src/mc_install/fabric.rs` (validate, sha1 sidecars, verified download, publish last, per-profile lock) | 17 profile validation cases equal Kotlin's; reuse of a Compose-installed profile; broken library noticed; cancel |
| 3 GTNH | `src/mc_launch/gtnh.rs` (patches, Prism OS names, class path, JVM args, runtime downloads, lwjgl3ify hard link) | GTNH room and local commands equal Kotlin's, every argument |
| 4 Start flow | `mc_install::meta::runtime`, `prepare_for_launch(Target) -> Launch`, `play::runtime::Selection`, `room::start_solo`; `RoomOutcome::Service` removed | Fabric room/local commands equal Kotlin's; selection per version and loader |

Settled from section 8:
- GTNH does not need the Forge 1.7.10 installer: Kotlin only checks that its manifest exists and
  then replaces it with the patch-built one. Rust skips the install.
- The GTNH extension (`<versionDir>/gtnh/`) is part of the pack contents.
- Runtimes per room game: Kotlin jars only on 1.20.1/1.21.1 Forge-family (none for Fabric, none for
  GTNH, which has Forgelin), zstd-jni on all three, media only on 1.20.1/1.21.1.
- Fabric libraries download from `maven.fabricmc.net` with the RDI server copy as last source
  (Kotlin has no fallback there).

## Progress (2026-11-24, part 2)

GJ5c1 accepted and committed (4064d78a). Steps 6-8 implemented, uncommitted; at GJ5c2. Owner
decision: the owner tests real Microsoft online play by hand (`evidence/jvm-removal/j5c2/owner-checklist.md`).

| Step | Rust | Checked by |
|---|---|---|
| 6 Sessions | `account/msa.rs` (`Endpoints`, `sign_in_session`, `refresh`), `msa_play.rs` (`msa_game_sessions.json`, `Resolver`), logout clears both files | mock live.com/Xbox/Minecraft tests in `msa_play/tests.rs` |
| 7 Online play | `play_ui.rs` `login_online` → `Reply::PlayLink`/`OnlineAccount` → `play::start_local` with `online` | `play::tests` (mode/offline name), live smoke of offline and room starts |
| 8 Archive | `service.rs`, `lazy_service.rs`, `protocol.rs` in `DEL/20261124-j5c-service-client/`; every service call, event and mirror removed | grep, clippy, tests, release, zigbuild |

Settled from section 8: live.com may return a new refresh token on refresh; Rust saves it when
present and keeps the old one otherwise.
