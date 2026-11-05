# GU1 — UI risk proof, 2026-11-05

U1 implementation is ready for review. **GU1 is not a pass yet:** criterion 2 now passes, but physical DPI transitions, strict panel alignment and platform CI remain open. Stopped here; no U2 or old M2 work.

## Build and evidence

Windows AMD Radeon 780M, DX12/DXC, physical 2560x1440, scale 2.0.
Workspace all-feature tests: 60 renderer + 18 app passed, one capture ignored in the normal suite.
All-target/all-feature Clippy -D warnings and release app/examples passed.
Six explicit glass captures (FXC/DXC, scale 1/1.25/2) passed; maximum legacy-pixel delta 1/255,
MAE <= 0.0000072/255. Windows scene captures at 0/15/45 are in windows-final/.

Run target/release/rdi-slint-proto.exe --ui-proof.
The default menu, golden valley, default camera, resolution and FPS policy remain unchanged.
No Compose/Kotlin files were modified. No commit or push was made.

## Criteria

| # | Result | Evidence and remaining work |
|---|---|---|
| 1 Form | not verified in full | Production POST /player/jwt returned 200 and the submit callback reported success; exit 0 (auth-final/). Empty Enter visibly shows 未填写完整 (window-input-double/empty-enter.png). Masked password, busy and error states implemented. Owner confirmed IME works on 2026-11-05. Tab order, selection and clipboard are not separately confirmed. |
| 2 List and menu | **pass** | With only the menu animating the scene: list over the held frame and real menu over the live scene, 3 of 3 runs each. See "Menu-only scene". |
| 3 Panel/modal | not verified to 1px tolerance | 300ms slide/scale, logical rect/radius/alpha exported by Slint and used directly by glass. Captures at 25/50/75/100% and scales 1/1.25/2 inspected visually; no obvious offset. Strict <=1 physical pixel measurement during live animation and keyboard modal behavior remain owner checks. |
| 4 Window | not verified in full | Actual mouse title drag moved 60x50px, title double-click/maximize button reached width 3866; minimize button true; bottom-right resize reached 2413x1363; close button exited 0. Native DPI messages exercised 1/1.25/2 and cache resize. Physical cross-monitor DPI and every resize edge still need manual checks. |
| 5 Text | pass for inspected captures | Mixed Chinese/Latin/Nerd Font icons visible, paragraph wraps, long-name elision and selectable read-only RDID supplied. Inspected scale captures show no missing glyphs. Owner checks live selection/clipboard. |
| 6 Platforms | not verified | Workflow prepared for macos-15 and ubuntu-24.04, with one macos-14 retry when no usable Mac adapter. Never pushed or dispatched. No cross-platform MAE claimed. |

## Focused frame intervals (ms)

Measured wall-clock intervals between Slint AfterRendering callbacks. CPU rendering callbacks are
recorded separately in measurements.json; these are not GPU timestamps or presentation statistics.
All three runs: network_images=2, unfocused_frames=0, scene_unavailable_frames=0, valid=true.
All 126 OS memory samples confirmed the app was foreground.

| Run | List p50 | p95 | p99 | worst | Panel p50 | p95 | p99 | worst |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 16.59 | 18.97 | 21.91 | 26.73 | 16.67 | 18.88 | 21.19 | 39.65 |
| 2 | 16.72 | 20.37 | 23.08 | 44.77 | 16.62 | 18.78 | 21.51 | 33.09 |
| 3 | 16.57 | 19.81 | 23.02 | 26.58 | 16.58 | 19.36 | 23.63 | 38.77 |

WS/private MiB, median seconds 20–28 per round: 462.99/504.17, 475.58/516.73, 475.58/516.73.
No continued growth between the final two short rounds; this does not establish long-duration stability.
The first exploratory batch is retained under focused-final/; acceptance uses focused-images/.
The earlier native test had a script alias error; successful input evidence is window-input-double/.

The table above is the first measurement, kept for reference. Slint also printed one "long callback/instantiation chain detected" warning during each long
harness run; no crash, device loss or glass error occurred, exit 0. The warning is not attributed.

## Menu-only scene

Owner decision: only the menu animates the scene. Other screens hold its last frame, and the scene stays loaded so the menu
resumes at once. Same limits as below. held-menu-1/: 60Hz primary display, window 2560x1440 at scale 2, all samples focused,
all runs valid, exit 0, interleaved order. The menu run is real wheel scrolling over 3018 logical px on the host grid.

| Run | Case | FPS | Long % | Interval p95 | p99 | Worst | CPU p50 | CPU p95 | WS MiB | Private MiB | Result |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | list, held frame | 59.99 | 0.06 | 17.98 | 19.60 | 27.81 | 11.13 | 12.69 | 374 | 410 | pass |
| 4 | list, held frame | 59.97 | 0.11 | 18.13 | 20.60 | 27.91 | 11.51 | 13.25 | 378 | 421 | pass |
| 5 | list, held frame | 60.01 | 0.00 | 17.96 | 19.60 | 24.06 | 11.12 | 12.69 | 370 | 409 | pass |
| 2 | menu, live scene | 60.00 | 0.11 | 18.09 | 20.57 | 29.06 | 6.42 | 7.87 | 481 | 522 | pass |
| 3 | menu, live scene | 59.97 | 0.06 | 18.06 | 20.17 | 32.49 | 6.56 | 8.34 | 474 | 514 | pass |
| 6 | menu, live scene | 60.00 | 0.00 | 18.38 | 20.78 | 23.53 | 6.53 | 8.63 | 472 | 515 | pass |

Hold and resume on one running window (freeze-1/): WS and private barely move when the scene is held, because it stays loaded.

| Phase | CPU, % of one core | Scene frames | Animation clock | WS MiB |
|---|---:|---:|---|---:|
| live | 11.7 | 543 | 6.48 to 15.54 | 458 |
| held | 0.2 | 0 | 17.57, unchanged | 458 |
| held, window resized | 1.2 | 0 | 17.57, unchanged | 458 |
| resumed | 6.9 | 603 | 18.56 to 28.63 | 460 |

- Two captures 10 seconds apart while held are pixel-identical. The clock resumes from where it stopped, with no jump.
- Runs 1, 4 and 5 start held, so their memory is lower than leaving the menu, which keeps about 458 WS.
- Memory is flat after warm-up within each 43-second run. Longer stability is not established.
- The panel phase over a held frame again includes idle gaps between toggles, so its interval numbers are not usable.
- Slint's list cost is unchanged at about 11ms. Only the headroom is new; it has not been profiled.
- The real navigation does not exist yet. U3 must hook the hold to the current screen and to a running game.

## Rerun under the new criterion

Each live run must have long frames (interval over 25ms) ≤ 1%, delivered FPS ≥ 58, frame CPU p95 ≤ 16.7ms.
pacing-60hz-4/: primary display 3840x2160 at 60Hz, window 2560x1440 at scale 2, 780M, focused for every sample,
all 9 runs valid, exit 0. Order was interleaved. List phase only, 30 seconds per run.

| Run | Case | FPS | Long % | Interval p95 | p99 | Worst | CPU p50 | CPU p95 | Scene+glass CPU p50 | Result |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | live | 59.61 | 0.56 | 19.25 | 22.62 | 29.35 | 13.20 | 15.86 | 1.65 | pass |
| 6 | live | 58.02 | **2.30** | 21.54 | 30.45 | 45.38 | 13.47 | **18.36** | 1.67 | **fail** |
| 7 | live | 59.06 | 0.68 | 20.05 | 23.68 | 47.22 | 13.14 | **17.07** | 1.74 | **fail** |
| 2 | static + glass | 59.93 | 0.00 | 18.27 | 20.62 | 24.65 | 11.57 | 13.83 | 0.19 | - |
| 5 | static + glass | 59.92 | 0.22 | 18.44 | 21.69 | 25.99 | 11.63 | 13.86 | 0.23 | - |
| 8 | static + glass | 59.95 | 0.11 | 18.23 | 20.33 | 27.66 | 11.60 | 13.55 | 0.22 | - |
| 3 | Slint only | 59.98 | 0.06 | 18.88 | 20.99 | 25.29 | 11.59 | 13.95 | - | - |
| 4 | Slint only | 59.94 | 0.06 | 18.99 | 21.02 | 27.59 | 11.60 | 14.26 | - | - |
| 9 | Slint only | 60.00 | 0.06 | 18.79 | 20.29 | 26.89 | 11.54 | 13.30 | - | - |

Reading:
- Slint's own list frame costs about 11.6ms of CPU time, about 70% of a 60Hz frame, with no scene or glass.
- Glass over a static background adds about 0.2ms. Both non-scene cases stay under every limit in all 6 runs.
- The live scene adds about 1.6ms at p50 and pushes the tail over the limit. Only the CPU side is timed.
  GPU contention between the scene and the UI may explain the tail, but that is not measured.
- This timing runs from BeforeRendering to AfterRendering, so it may include blocking as well as computation.
  Where the 11.6ms goes inside Slint has not been profiled.
- Same binary, not counted: live runs in pacing-60hz-2 and pacing-60hz-3 had 0.45% / 59.41 FPS / CPU p95 16.69
  and 0.50% / 59.60 FPS / CPU p95 16.33. Those aborted after one run because of a script bug.

Memory, seconds 20-28, WS/private MiB: live 451-472 / 491-513, static 220-221 / 277-278, Slint only 226-227 / 251-255.
Each run grows at most 2MiB after warm-up, but 43 seconds does not establish long-duration stability.

The panel phase is only comparable for live runs (59.75-60.03 FPS, long 0.00-0.84%). Without a scene nothing redraws
between panel toggles, so those intervals include idle gaps (about 31 FPS, p99 about 390ms). That is a harness
artifact, not stutter.

The 1px measurement and the "long callback/instantiation chain" warning remain open. The warning appears in every
case, including Slint only, so the scene does not cause it.

## Design limits and CI

Glass is below all Slint content: the moving panel refracts the scene but cannot refract the list's text.
The modal dims Slint content above glass; the dialog body is intentionally opaque enough for legibility.
No parent opacity or rounded clip was added to the proof panels. Lists update one VecModel in place.

Workflow: .github/workflows/slint-proto-platforms.yml. The macos-14 retry now reads the macos-15 probe from its artifact, because a matrix job output keeps only one leg. Checked by YAML parse only. Adapter probe runs first, tests/builds remain
when no adapter is available, PNG/log artifacts are uploaded, and a best-effort 20s window check records
normal vs forced termination. CI receives no account credentials. The portable preview keeps Windows
DX12 and uses PRIMARY elsewhere. Real Apple/Linux GPU, IME and DPI feel cannot be inferred from CI.

GitHub lists macos-15 as an arm64 M1 runner, but that alone does not prove usable Metal:
https://docs.github.com/en/actions/reference/runners/github-hosted-runners .
No paid Mac time was started. Before any future push, obtain owner approval and scan the entire outgoing
diff, including pre-existing unpushed commits. That push review has not been performed or authorized.

## Owner review

Use owner-checklist.md for exact steps. Required decisions: review the proof, confirm IME/window/form
behavior and review the platform CI results.
The owner replaced criterion 2 on 2026-11-05; the new limits are in UI-PORT-PLAN.md. No criterion was dropped.
