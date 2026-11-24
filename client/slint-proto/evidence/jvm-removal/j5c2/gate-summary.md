# GJ5c2: Microsoft online play from Rust, service client archived

Date: 2026-11-24. Plan: `J5C-PLAN.md` steps 6-8 (C2-C4 as approved). Owner decision 2026-11-24: the
agent implements and tests everything except a real Microsoft sign-in; the owner tests online play
by hand (`owner-checklist.md`). **At the gate, waiting for the owner's online-play check.**

## What changed

| Part | Rust | Kotlin source |
|---|---|---|
| Sign-in pieces | `src/account/msa.rs`: `Endpoints` (replaceable hosts), `sign_in_session` keeps the live.com refresh token and the Minecraft token lifetime, `refresh` (grant `refresh_token`, same Switch title id, C3); binding still uses `sign_in`; `MsaProfile` `Debug` hides the token | MinecraftAuth `JavaAuthManager` |
| Sessions (C2) | `src/msa_play.rs`: `msa_game_sessions.json`, `accounts.<accountId>.<msid>` = `{refreshToken, mcToken, mcTokenExpiresAt, name}`, unknown keys kept, a damaged file renamed `.broken-<ms>`; `Resolver::resolve` uses the saved token while it has more than 1 hour left, else refreshes, else signs in with a device code; a profile other than the bound msid is refused with Kotlin's message | `OnlineGameAccountService`, `MsaSessionStore` |
| Logout | `login.rs` clears the account in both `msa_sessions.json` (Compose) and `msa_game_sessions.json` | `MsaSessionStore.clear` |
| Online local play | `play_ui.rs` `login_online` → `Reply::PlayLink` (link shown in the dialog and opened in the browser) → `Reply::OnlineAccount` → `play::start_local` with `online: Some(account)` (`msa`); `rdi-local-pack.json` gets `lastAccountMode = Online`, the offline name stays; closing the dialog or signing out aborts a waiting sign-in; errors show as `正版登录失败：…` | `LocalPlayDialog.confirm`, `LocalPackPlay.prepare` |
| Service client archived (C4) | `src/service.rs`, `src/lazy_service.rs`, `src/protocol.rs` → `DEL/20261124-j5c-service-client/`; removed: `packPlay.open/start/cancel`, `game.subscribe/stopAll`, service events, idle stop, service session mirror and console merge, `external_alive`, `api::Client` service and its Java command, `Session::params`, `tasks::owns`; `Running` keeps only versions | — |

Not changed: Kotlin `ui-service` (stays until J6; `protocol/fixtures/messages.json` is still read by
its `ProtocolTest`); `RDI_GAME_RUNTIME_DIR`/`ui-service/build/game-runtime` still supplies the game
runtime jars. A pack that cannot start now shows why (missing pack, damaged `rdi-local-pack.json`,
unsupported version) instead of asking the service.

## Validation

| Check | Result |
|---|---|
| `cargo clippy --all-targets` | clean |
| `cargo test --workspace` | 62 + 291 pass (the 11 library tests were the archived service/protocol tests) |
| `cargo build --release` | pass |
| `cargo-zigbuild check --release` macOS arm64 / Linux x64 | pass / pass, only the 2 existing `java_discovery.rs` warnings |
| `grep` for service methods (`packPlay.*`, `host.start`, `game.*`, `.call("`, `LazyService`) | none left |

New tests (`msa_play::tests`, mock live.com/Xbox/Minecraft on one local port): fresh device-code
login saves the session (Switch title id, link `?otc=`); a token with time left uses no network;
an expiring token is refreshed and the new refresh token kept; a refresh without a new refresh
token keeps the old one; a rejected refresh falls back to a device-code login; a session of
another profile is refused (`登录的正版账号Other与RDI账号绑定的正版账号不一致`) and the saved one kept;
a damaged file is set aside and the login saved; a declined login saves nothing; clear keeps
other accounts and unknown keys; msid dashed/undashed. `play::tests`: online play sets
`lastAccountMode = Online` and keeps `lastOfflineName`.

### Live smoke, service gone (Windows, test account, real server)

Data folder `j5a/live/run`. Local Fabric pack offline (`Steve`/`legacy`, no `-Drdi.play`) and the
Fabric room (`/host/.../start`, room connection, RDI core) both started; the game bar showed
`运行中`; 终止游戏 ended each in about 1 s. `ui-service-stderr.log` unchanged since 2026-11-22, so
no service process was started. The test account has no bound Microsoft profile, so 正版登录 was
not available, as expected.

## Open (owner)

`owner-checklist.md`: real Microsoft online play.
