# GU3 — shell and shared components

2026-11-06. **U3 is complete. Stopped at GU3 pending owner review; do not begin U4.**

## Scope

- Rust shell/back stack, Home/Back/Escape/Alt+Left, title-bar account/activity
  actions and shared modal/toast state. Menu tab/scroll/models stay in place.
  Destinations not yet ported still invoke `stub`; no placeholder screens or cut-feature entries.
- Shared buttons/circle actions/toggles, text/password fields, content cards/grid,
  image/head states, loading/empty/error states, dialogs/toasts, both scrollbar
  axes, console and hierarchical long-task progress. `--gallery` reviews four pages.
- Glass panels, menu pills and grid viewports use Slint absolute layout geometry.
  Embedded grids now clip to their own viewport, with a focused renderer test.
  No rounded clip or parent opacity layers were introduced. Models update in place.
- Production scene hold follows navigation and `game-running`: only the menu
  animates; held scenes stay loaded. Rendering policy/camera/scene content are unchanged.
- Split navigation, shell/layout bridging and gallery fixtures out of `app.rs`.
  Existing image per-host limits and service-only API calls remain intact.

## Checks

| Check | Result / evidence |
|---|---|
| Fresh Rust baseline | Library4 + app20 pass before changes; `baseline-rust.log` |
| Workspace tests | Renderer61 + library4 + app23 pass; one existing GPU capture ignored; `tests.log` |
| Clippy and release | `cargo clippy --workspace --all-targets -- -D warnings`, `cargo build --release`; logs and `validation.exit` |
| Gallery at scales1/2 | 1280x720 / 2560x1440 physical, both1280x720 logical; `capture.ps1`, native DPI and app-heartbeat scale/size checks; `finished-scale1/` and `finished-scale2/`,9 captures per scale, exit0 |
| Input/dialog/content/console/task checks | 16 checks pass on Windows; `idle-interaction/checks.json`. Win32 pointer messages, real keyboard/clipboard, Chinese paste. IME composition remains an owner check |
| Shared scrollbar drag | Pass with actual native mouse: vertical offset847.43px, horizontal offset-484.43px; `native-scroll/checks.json`, exit0 |
| Navigation/scene policy | All6 focused phases pass; `shell-settled/phases.json`. Held phases add0 scene frames and stop the clock; live phases add169/177/179 frames over about3s |

The game-running phase explicitly sets a probe-only simulated state; it is not a
new real-game launch acceptance. The production property still comes from the
existing Kotlin `game.state` event. No new protocol events were added.

## Release measurements

Final build check (`final-release-check/summary.json`, matching the SHA256 below):
one authenticated Windows release run,5 paired samples, every sample OS/app focused,
2560x1440 at scale2.0, live scene. Each memory figure is the median of those5 samples.

| Process/stage | Result |
|---|---|
| UI working set / private | 426.96 / 499.97MiB |
| Kotlin service working set / private | 252.84 / 299.94MiB |
| UI first frame / first live scene | 828 / 2246ms, single startup |
| Standalone service handshake | Median644ms; samples686/636/644ms; EOF exit0 in all3 |

Earlier three-run batch (`release-measure/summary.json`, different binary hash)
is retained separately: UI470.52/512.54MiB, service254.23/303.17MiB; first frame986ms,
first scene2844ms, service handshake1173ms. A warm-up run was excluded, then3 runs
with15 paired focus-checked samples. It predates the scrollbar follow-up and
disabled password-eye guard. Do not combine it with the final single-run check.
Debug GU2 values are not a comparison baseline.

These are short process-memory samples, not retained heap, GPU memory, FPS or
long-duration stability measurements. Service handshake excludes login/network.

## Review boundaries

- Console/tasks use gallery fixtures. Actual subscriptions/business behavior wait for U4.
  Sample console exports go only to this evidence directory.
- New fields need owner IME/caret review, plus visual/interaction acceptance via
  `owner-checklist.md`. No new Mac/Linux or actual in-game acceptance claim.
- Kotlin/Gradle, `common`, updater, packaging and deferred scene work were untouched.
  Kotlin tests were not rerun; GU2's4 known client-core failures remain historical baseline only.
- Final real-menu run logged one remote icon decode fallback (`i0.hdslb.com`);
  the menu used its existing fallback. Successful gallery captures/scroll checks
  logged no warnings/errors. Service-stop warnings occurred at launcher shutdown.
- Initial pointer runs lost focus and were discarded. Win32-message-only drag failed; actual native mouse drag passed. An early shell check sampled
  an in-flight transition frame; the settled6-phase rerun above is the evidence.
- No staging, commits, push or permanent deletion. Owner review must precede U4.

## Review build and screenshots

From `client/slint-proto`, run `target/release/rdi-slint-proto.exe --gallery`.
No credentials are needed. Final binary SHA256:
`52A788968B6D6405ECBD4C95B5E744EC066C3038F0B059BEF8A0F7E2BE6A0A49`.

- [Scale1 controls](finished-scale1/buttons-fields.png), [scale2 controls](finished-scale2/buttons-fields.png).
- [Content/images](finished-scale2/content.png), [console/tasks](finished-scale2/console-tasks.png),
  [glass tiles](finished-scale2/glass-tiles.png), [dialog](finished-scale2/dialog.png), [toast](finished-scale2/toast.png).
- Loading/empty/error captures are `content-state-1/2/3.png` in each scale directory.

Final screenshots use the same capture path and measured scale/viewport as the
focused earlier captures, with the diagnostic unfocused-render option to avoid
requiring the owner's desktop focus. They are visual evidence only, not a memory
or performance measurement. Binary hashes are saved in each evidence directory.
The 16 interaction checks and6 navigation phases predate only scrollbar follow-up
and a disabled-eye-button guard; their separate hashes preserve that boundary.

## Owner review follow-up (2026-11-06)

The owner checked IME and window scale changes; both work. Review fixes requested before acceptance:

- `GlassButton` centers its icon and label (`alignment: center`). Before, a button wider than its
  content stretched the icon and label apart (for example 重试 and 加载中). This applies to every
  `GlassButton`, including the menu tab bar and action pills.
- `TextField` draws its label above the outline instead of across the border (`top` 16px when
  there is a label). `reserve-error` (default true) keeps the error line's space; the content
  search field turns it off. The content grid's filter row and list moved down 10px, and its
  sort/view buttons are centered on the outline.

Rust tests (renderer 61 + library 4 + app 23, 1 ignored), clippy and the release build pass
(`tests.log`, `validation.exit`). Gallery recaptured at both scales in `fix2-scale1/` and
`fix2-scale2/` (`fix1-*` is the intermediate capture before the search row alignment).
The menu screen was not recaptured; it needs the test account. Review binary SHA256:
`B2423B7C3A3A29E5251A329C1791D2860CA8EF17AE36FDF12F03C7C3ACB38999`.
