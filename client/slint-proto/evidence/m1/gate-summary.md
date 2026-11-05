# G1 — M1 renderer extraction, 2026-11-05

Implementation complete; stopped for owner review. M2 has not started.

## Change

Workspace member `crates/rdi-renderer` owns scene, glass, allocation diagnostics, device creation and
four headless examples. The application owns Slint texture conversion, grid metrics, GPU settings,
DXC location, Automatic fallback and the existing device shutdown lifetime workaround.
Explicit CameraPose/FrameInput reaches main/reflection uniforms and cloud coordinates.
The default input retains the original camera. No shaders, resolution, animation or scene content changed.

## Verification

| Check | Result |
|---|---|
| Windows compile | All targets and features passed |
| Tests | Default and all features: 59 renderer + 16 app passed, 1 GPU capture ignored in normal suite; capture explicitly passed 12 times |
| Clippy | Workspace/all targets, default and all features, -D warnings passed |
| Dependencies | Renderer normal/build tree has no Slint; workspace resolves one wgpu 30.0.1 |
| Camera | Old implementation retained as test oracle: uniform bytes equal at 0/15/45/-10/123.456s, main and reflection; explicit camera tested |
| Shaders | All 30 moved WGSL sources unchanged |
| Pixels | 28 before/after comparisons: 0 differing pixels, MAE 0, maximum 0 |
| Repeat noise | All repeated before scene/glass captures identical |
| DXC fallback | Missing and unloadable DLL reached live scene with FXC; close 196/172ms, exit 0 |
| Exit | Final authenticated batch: all 14 processes exited 0; new app 6 closes 172–186ms |

Pixels cover FXC and DXC glass at scales 1/1.25/2, repeated twice; fixed Steve plus 50 Steve crowd
at 0/15/45s and shadow depth for both compilers, repeated twice. Assets stayed in place.
Local PNGs/logs/binaries are in `before/` and `after/`; numeric comparison is `comparison.json`.

## Startup

Final authenticated counterbalanced batch, six rounds per binary; medians from rounds 2–6.
AMD 780M, physical window 2560x1440, scale 2, unlocked visible desktop. Probe allowed unfocused
rendering, hold 0, fixed Steve. Both binaries received the same account environment.

| Median ms | Before | After | Change |
|---|---:|---:|---:|
| First frame | 564 | 575 | +1.95% |
| First live scene | 1622 | 1637 | +0.92% |

Both are within 5%. Adapter/compiler path remained AMD 780M/DXC. Login/host API responses were 200.
Final runtime logs are under `runtime/`, including close requests, exit codes and DLL fallback.
An earlier exploratory batch overlapped compilation; it was superseded by this sequential batch.
The unauthenticated startup runs under before/after are not the acceptance startup figures.

## Limits and memory

Focused memory is **not verified**: runtime snapshots show focused=false in both conditions.
Exploratory 7s WS/private medians were 410.71/456.52 MiB before and 411.35/455.25 MiB after;
these are unfocused observations, not a focused-memory comparison or steady-state result.
No memory threshold blocks M1. No non-SM6 GPU was available; compiler decision tests do not
prove that hardware path. No Linux/mac, interactive camera, IME or M2 work was performed.

Helpers: `tools/m1-reference.ps1`, `m1-validate.ps1`, `m1-runtime.ps1`, `m1-compare.py`.
Historical p*.ps1 scripts remain untouched. Files were relocated with filesystem moves, preserving
contents; no project files were permanently deleted.

## Outcome

Owner passed G1 on 2026-11-05. M1 is committed, not pushed. Focused memory remains not verified; M2.8 must fix the focused measurement first.
