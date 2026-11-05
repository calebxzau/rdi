# Startup optimization gate — 2026-11-05

Default now: device created by `src/gpu_setup.rs`, bundled DXC 1.9.2609.5 with FXC fallback, fixed-parameter haze.
Windows tests (48 + 25), Clippy, fmt on new files and release builds pass. Machine: AMD 780M, scale 2.0.

## Startup (ms, median of rounds 2-6, unfocused probe run, hold 0)

| Case | First frame | First live scene |
|---|---|---|
| Automatic + FXC + full haze (`ab/`, before this work's shader change) | 1167 | 2516 |
| Automatic + FXC + fixed haze | 914 | 2295 |
| Manual + FXC + fixed haze (fallback path) | 760 | 2136 |
| Manual + DXC + fixed haze (default) | 562 | 1636 |

Focused runs: pre-change binary first frame 1169-1252, final binary 563-605.
Measured stages on the default path: device 182, rendering setup 369, glass ready 514, first frame 562.
Haze compile: DXC 133 → 53ms, FXC 418 → 143-151ms. Other runs vary by run batch, so compare within one table.
The first launch after a shader or DLL change is slower: 882 / 2403 was seen when DXC was new to the driver.

## Picture

- Fixed against full haze: 0 differing pixels for FXC and DXC at scale 1, 1.25 and 2.
- FXC against DXC glass: MAE at most 2.7e-05 per 255, max difference 1/255.
- FXC against DXC scene preview: MAE 0.00022, max 1/255.

## Runtime cost of DXC

- Headless preview: 7.49 → 8.29ms mean. Already present in `--scene-p1-only` (+0.86ms), so it is in the base scene pass, not fog or bloom. Root cause not found.
- Focused app, last 30 completion windows, paired rounds: FXC 10.24 / 14.38ms, DXC 12.22 / 15.12ms. Rounds differ by machine state, so read only the pair difference: about +0.7 to +2.0ms.
- 3D busy overlaps (65-70%), and the frame budget is 16.7ms.

## Memory (MiB, focused, 60s, final binary, two rounds)

| Case | WS | Private |
|---|---|---|
| Pre-change | 449 / 455 | 500 / 498 |
| Final, FXC | 476 / 467 | 523 / 514 |
| Final, DXC | 475 / 470 | 514 / 512 |

About +20 WS and +14 private against pre-change. FXC and DXC match, so DXC itself is not the source; the manual device path is the remaining difference and is not attributed further.

## Exit

The first manual build hung about 5s on close and ended with 0xC0000409. Slint's platform kept the device until thread-local destructors at process exit. `gpu_setup` now leaks one reference so the device is never destroyed there. Close: 162-188ms, exit code 0 (`close/` before, `close-fixed/` after).

## Fallback

Missing DLL and an unloadable DLL both run on FXC to a live scene. The fallback for a GPU without Shader Model 6 is covered by decision tests only: no such GPU was available.

## Not done

Early glass compile was not needed: the 0.8s first-frame goal is met. Parallel scene loading and the 30FPS A/B are not started.

## Outcome

Closed 2026-11-05 when the owner asked to proceed with M0. No separate visual run was reported. DXC stays the default. Licensing is not a blocker: `dxcompiler.dll` is under `LICENSE-LLVM.txt` (see `third_party/dxc/README.md`).
