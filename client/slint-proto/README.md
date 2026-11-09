# rdi-slint-proto

Rust + Slint rebuild of the launcher's main menu (`MenuScreen`), used to judge visual parity and memory against
the Compose launcher. The Rust UI remains outside Gradle and releases; U2 uses a headless Kotlin child service built by the client/ui Gradle project.

## Build and run (Windows, MSVC toolchain)

```powershell
cd C:\Users\calebxzhou\Documents\coding\rdi5\client\ui
.\gradlew.bat :ui-service:installDist --no-daemon
cd ..\slint-proto
cargo build --release
$env:RDI_PROTO_USR = '<account>'
$env:RDI_PROTO_PWD = '<password>'
.\target\release\rdi-slint-proto.exe --show-others
```

From WSL, wrap the same commands in `pwsh.exe -NoProfile -Command "..."`; don't build against `/mnt/c` with the
WSL toolchain.

Without both account variables, the launcher opens the login page and uses saved-account
prefill/auto-login settings. Rust reads saved passwords from the Compose-compatible account file; they never reach Slint properties.

### Environment

| Variable | Meaning |
|---|---|
| `RDI_PROTO_USR`, `RDI_PROTO_PWD` | Account used by Rust for direct server login (optional development override; never commit them) |
| `RDI_PROTO_SERVER` | Rust API base URL override; the Kotlin service still uses its default route until J5 |
| `RDI_DATA_DIR` | Launcher data directory for both Rust and the Kotlin service; defaults to `client/ui/run`. Isolated scripts must set this alongside any `-Duser.dir` override |
| `RDI_SERVICE_COMMAND` | Development override: JSON array of executable and arguments, without shell evaluation |
| `WGPU_BACKEND` | Overrides the forced DX12 backend on Windows (`vulkan`, `gl`), for comparisons only |
| `WGPU_DX12_COMPILER` | `fxc` or `dxc` overrides the default shader compiler (the `dxcompiler.dll` beside the exe, then FXC), for comparisons only |

### Flags

| Flag | Meaning |
|---|---|
| `--gallery` | Start the U3 shared-component gallery without an account; Home opens the real menu if credentials are supplied |
| `--show-others` | Start with 显示其他房间 on |
| `--all-playable` | Treat every host as playable (mimics the special account override; no dimming) |
| `--solid-window` | Opaque window instead of a transparent one |
| `--scale <f>` | Override the DPI scale factor (0.5–4) |
| `--renderer <name>` | Slint renderer, default `femtovg-wgpu` (DX12 on Windows); `software` runs without glass, for memory comparison only |
| `--no-scene` | Keep the static background (paired memory/startup comparison) |
| `--gpu-setup=manual\|automatic` | Who creates the GPU device: the app (default) or Slint, for startup comparison |
| `--no-glass` | Skip the glass/background pass, to measure the renderer with real content only |
| `--log <filter>` | `tracing` filter, default `info` (e.g. `info,rdi_slint_proto=debug`) |

## Assets

- `assets/bg.png` is converted once from the Compose launcher's static background:
  ```powershell
  C:\Users\calebxzhou\Documents\ffmpeg\bin\ffmpeg.exe -i ..\ui\assets\src\main\resources\assets\bg.avif assets\bg.png
  ```
- Fonts, the Steve skin and the logo are referenced from `client/ui/assets` by relative path, not copied.

## Layout

- `ui/*.slint` — window, title bar, tab bar, tile grid, tiles, buttons.
- `src/navigation.rs`, `src/shell.rs` — back stack, shared window state and layout-to-glass bridge.
- `src/packs.rs`, `ui/packs.slint` — U4a local import, installed packs, advanced
  options, account-choice dialog, tasks and console. Business state comes from
  the Kotlin service; native pickers and folder/browser actions run on the Rust side.
- `src/gallery.rs`, `ui/gallery.slint` — deterministic component fixtures and
  interactive review. The title-bar console/tasks use real service data in U4a;
  gallery console/tasks intentionally retain their samples.
- `src/api` — direct Rust login/server requests, proxy policy, envelope handling and typed DTOs. `src/hosts.rs` — paging/merge/filter and grid constants.
- `src/images.rs` — icon download/decode/resize, player heads. `src/app.rs` — state, jobs, Slint wiring.
- `crates/rdi-renderer/src/glass` — wgpu pass run before Slint renders each frame. It draws the cover-cropped background and all
  glass into one texture that Slint shows behind the UI (`backdrop` in `app.slint`). WGSL ports:
  `grid.wgsl` (`GLASS_TILE_GRID_SKSL`), `pill.wgsl` + `liquid.wgsl` (`LIQUID_GLASS_SKSL`, selected tab),
  `haze.wgsl` + `rim.wgsl` (Haze glass for the tab bar and buttons). Blurs are computed in background image
  space. `cargo test` validates every shader with naga and checks the Rust uniform layouts against them.
  The earlier OpenGL version is archived under `DEL/20261030-slint-proto-gl-to-wgpu/`.
- `crates/rdi-renderer/src/scene` — golden-valley P4a: accepted deterministic terrain/forest/village/grass, source
  textures and2048² shadows, clear sky/sun, fancy clouds and planar water/reflection. Adds64³ R8
  valley-fog noise,640×360 march, bilateral HDR composition, linear mip chain, bloom atlas and
  final tonemap/saturation. Includes the account player with idle arm/head/cape motion in both
  main/reflection passes; crowd remains P4b. The worker starts after the first static
  frame; output remains1280×720, with no runtime CPU readback or per-frame target allocation.
- Mesh generation preserves placements, seeds, colors, UVs, AO and sway constants. It rounds only emitted
  positions to1/16, validates non-zero/same-sign part offsets, part extents, face area/winding, and
  uploads one mesh shared by the shadow bake and main pass. The24B local fixed-point packer stays strict.
- Live glass shares half/quarter downsample targets; its existing pause/fallback policy is preserved.
- `examples/scene_preview.rs` — Windows/DX12 captures at0/15/45, shadow-depth PNG and paced timings:
  `cargo run --release -p rdi-renderer --example scene_preview -- evidence/bg-port/p4a/preview 0 15 45`.
- `examples/p1_mesh_report.rs` — count, strict codec and displacement audit without account/network access.
  `examples/p1_precision_audit.rs` retains the original off-grid source examples that motivated approval.
- `tools/p3-validate.ps1`, `p3-preview.ps1`, `p3-reference.ps1` — Windows validation, phase diagnostics
  and matched Kotlin capture. `p3-summary.py` makes side-by-side PNGs and stage/error tables.
  `p3-measure-all.ps1` runs authenticated static / saved accepted P2 / P3 under the same controlled
  policy (60s visible /30s minimized /10s restored). The launch helper passes credentials only in
  process environments. Earlier phase helpers and controls remain available.
- `examples/baseline.rs` — empty Slint window for measuring the renderer/driver floor:
  `cargo run --release --example baseline -- femtovg-wgpu` (or `software`; set `WGPU_BACKEND=dx12`).
- `tools/` — PowerShell helpers for build/validate/screenshot capture. `evidence/` — logs and screenshots.

See `REPORT.md` for results and `PROGRESS.md` for the work log.

## Background port review gates

Plan: `/tmp/slint-bg-port-plan.md` (no longer available; P4b's plan is `P4B-PLAN.md`). The owner accepted P0, selected MSAA off +24B packed vertices, and explicitly approved emitted-position quantization on2026-10-31. P1 visuals/tuft residuals and P2 water/cloud visuals/sampler timing are accepted. The P2 authenticated memory/API/glass run is complete. P3 implementation, matched visual validation and authenticated memory/CPU attribution are complete; P4a is accepted; P4b is deferred. P5 runtime/native-memory/focused validation is complete; stopped at the P5 gate for owner review.
Reference images use Kotlin's golden-valley preview, seed123, times0/15/45 (the preview defaults to grassland
unless `RDI_BG_PREVIEW_SCENE=valley`). `evidence/bg-port/p0-compare-*.png` put that complete reference beside
P0; differences due to later passes are expected. The sky palette and terrain geometry already share the
reference formulas. Device-wide loss also invalidates Slint/glass on this shared device; scene-local validation,
allocation and performance errors are scoped and can fall back independently.

## P0.5 memory experiments

Defaults are MSAA off,24B local i16 fixed-point vertices at1/16 block with u16 indices, and eager scene targets. Lazy targets/chunked uploads remain opt-in. These controls apply to the app and (except window policy/drop) the scene preview:

| Flag | Experiment |
|---|---|
| `--scene-msaa=1` / `--scene-msaa=4` | Normal single-sample rendering / diagnostic4xMSAA;2x is rejected and requests no adapter-specific features |
| `--scene-packed` | Default24B vertices: local i16 positions in exact1/16 units, snorm8 normals, f16 linear color/AO/UV; per-batch origins and u16 indices |
| `--scene-full-vertices` | Diagnostic52B/u32 layout |
| `--scene-chunk-upload` | Upload through one reusable1MiB mapped staging buffer, waiting before reuse |
| `--scene-lazy-targets` | Allocate HDR/depth/MSAA/output on the first actual scene frame |
| `--scene-p3-only` | Diagnostic P3 without any player pipeline/model/appearance job; preserves accepted P3 |
| `--scene-p2-only` | Diagnostic accepted P2 pipeline without main-view fog/bloom; used to verify preservation |
| `--scene-pipelines-only` | Compile scene pipelines/uniform layouts, retain no terrain/HDR targets (material bindings and a1×1 shadow placeholder remain) |
| `--scene-memory-probe` | Allocation reports and short worker-stage holds; requests1Hz diagnostic redraws even after fallback |
| `--scene-probe-unfocused` | With the memory probe only, allow rendering without focus; minimized/hidden windows still pause |
| `--scene-delay-ms=10000` | Hold scene setup to capture a loaded static baseline first |
| `--scene-drop-after=15` | Terminally drop scene resources15s after its first live frame and restore static glass |

`tools/p05-validate.ps1`, `p05-preview-all.ps1` and `p05-measure-all.ps1` produce the evidence in
`evidence/bg-port/p0.5`. The measurement batch inherits credentials from its process environment.
`tools/p05-summary.py` combines byte counters and allocator reports. Windows foreground restrictions
make forced-focus automation unreliable; the controlled diagnostic batch explicitly allows unfocused
rendering and records the real foreground process. The normal application keeps its focus policy.
The P0.5 measurement tables remain historical. `tools/p1-decision-validate.ps1`, `p1-decision-previews.ps1`, and `p1-decision-measure.ps1` validate and re-baseline the selected defaults against a retained P0.5 best executable. These preserve the earlier decision evidence; the coordinate stop has since been resolved by approval. Use `tools/p1-full-*` for current P1 verification.

The local position packer accepts exact1/16 coordinates and fails explicitly otherwise; it never uses f16 positions or silently rounds. `cargo run --release -p rdi-renderer --example p1_precision_audit` reproduces the off-grid Kotlin grass/window/lantern inputs. The owner has approved rounding emitted P1 positions to1/16 with at most1/32 per-axis displacement. Placement/attributes remain unrounded; all safety checks fail explicitly. See `p1_mesh_report` and REPORT.md for measured deviation.

P1 brightness/texture follow-up evidence is in `evidence/bg-port/p1/visual`: same-region before/after
crops, matched post/AA controls and `visual-summary.json`. `tools/p1-visual-reference.ps1` uses the
compiled Kotlin reference without editing it; `p1-visual-preview.ps1` captures the release renderer;
`p1-visual-summary.py` produces crops and attribution (numpy/Pillow). Preview-only `--capture-hdr`,
`--capture-only` and `--material-probe=0..5` support linear HDR, albedo, tint, AO/shadow, gradient and
tuft/transmission inspection. These readbacks do not run in the application. P1 visual approval
was subsequently accepted by the owner; those artifacts remain the accepted P1 baseline.

P2 adds pre-water HDR/depth capture,640x360 planar reflection with five mip levels, the reference
water shader and fancy clouds. No main-view volumetric fog/bloom or players are added. Use
`tools/p2-reference.ps1`, `p2-preview.ps1` and `p2-summary.py` for the gate's reference images, raw
depth/reflection checks and comparisons in `evidence/bg-port/p2`.

`--scene-p1-only` retains a diagnostic P1-only scene; `--scene-legacy-sampler` replaces only the
accepted nearest-magnification color path with native linear sampling for matched timing.
Neither changes the default. `tools/p2-sampler-timing.ps1` runs a counterbalanced A/B with four
independent processes per condition for both P1 and P2. The sampler is not optimized here.

`tools/p2-measure-all.ps1` requires the owner's test credentials in `RDI_PROTO_USR`/`RDI_PROTO_PWD`,
inherited from the process or Windows user environment. It refuses an unauthenticated substitute.
It pairs current static/P2 modes with the saved accepted P1 executable, recording visible60s,
minimized30s and restored10s counters, login/API logs and glass screenshots. Credentials are never
written to source, command arguments or logs. The2026-10-31 run completed with the owner's test
account: all3 cases loaded100 hosts/60 modpacks, API responses200 and no errors/warnings. See
`evidence/bg-port/p2/measure/p2-glass.png`, `summary.json`, `gate-summary.md` and REPORT.md for the
new baseline. P2's60s working set/private memory is417.59/453.83MiB, versus accepted P1
388.90/429.59MiB in the same batch. Those are the historical P2 gate results. P3 results and its review gate are below.


## P3 review gate

P3 fog/post is complete, with45 Windows tests, all-target/all-feature Clippy and normal release builds
passing. Review `evidence/bg-port/p3/p3-matched-0.0.png` (also15/45s and original-reference pairs),
`p3-valley-crop-*.png` and `measure/p3-glass.png`. The fog volume matches Kotlin byte-for-byte.
Matched full-image RGB MAE is~1.4/255; paced preview7.63ms mean/10.48ms max at60.19FPS.

The authenticated static/accepted-P2/P3 batch loaded101 hosts/60 modpacks, all observed API200.
P3 visible60s WS468.91MiB /private512.35MiB /GPU total374.00MiB. Paired P3-P2:
+59.33WS /+63.80private /+9.05GPU /+17.25wgpu allocated. Initialization adds a64MiB heap block;
steady visible reserve is207MiB P3 versus215MiB P2 in this batch. Do not substitute historical reserve
values or equate Windows GPU counters with wgpu resource bytes.

The residual triggered independent headless and authenticated app allocation counters. P3 adds only
~0.65MiB live Rust requests in the complete app; retained noise/decoded images are not the cause of the
large process delta. A second pair reproduces~64MiB private growth but a smaller~30MiB WS step, leaving
native allocation/heap capacity/residency explicitly unresolved at that boundary. REPORT.md and
`evidence/bg-port/p3/summary.json` preserve complete tables, limits, pipeline timings and reproduction.
`cargo build --release --features allocation-probe` enables counters only for attribution; the delivered
normal executable has them disabled. P4/player/crowd, P5 and startup optimizations remain later gates.


## P4a player

The scene starts with classic Steve while the account skin/cape load independently of menu/scene startup.
`cloth.isSlim` is honored (otherwise alpha detection); HD skins/capes use the64px source UV grid with
nearest sampling. Supported skins are square or2:1, width64..2048 divisible by64; capes are2:1. Invalid
skin/downloads use Steve; failed cape loads continue without cape. The existing per-host download limit
applies. Successful upload consumes decoded pixels; four fixed model variants avoid rebuilding geometry
or pipelines when appearance changes. Login retries cancel old tasks and discard obsolete results.

The model uses the shared core's cuboids, outer layer, classic/slim/legacy UVs, f64 idle pose and bone
pivots. Player rendering writes depth, alpha-blends, clips the reflection at water and uses the reference
wrapped diffuse light. Source player shading does not apply the terrain shadow/atmosphere functions.
Pipeline creation stays on the loader worker; the existing post/glass/pause behavior remains in place.

`scene_preview` defaults to Steve. Optional diagnostic inputs:
`--player-skin=<path>`, `--player-slim` / `--player-classic`, `--player-cape=<path>` and `--dump-player`.
`--scene-p3-only` preserves the accepted no-player P3 for comparisons. `tools/p4a-preview.ps1` captures
Steve0/15/45, slim/cape, legacy, HD,4x/full diagnostics and P3 preservation. `p4a-reference.ps1` reruns
Kotlin and extends the disposable reference probe with player/no crowd plus mesh/bone dumps.
`p4a-summary.py` builds review images and complete geometry/visual/memory tables in `evidence/bg-port/p4a`.
The `p4a-measure-all.ps1` batch pins the current release executable and uses the existing test-account
process environment for static / saved accepted P3 / P4a (60s visible,30s minimized,10s restored).

P4a gate completed on2026-10-31:51 tests, all-target/all-feature Clippy and release app/examples pass.
Model vertex bytes match Kotlin exactly; model/bone max error2.5183e-6. Steve full-image RGB MAE is
1.405/1.391/1.398 per255 at0/15/45; preview7.374ms mean/9.918ms max,60.19FPS. Review
`evidence/bg-port/p4a/p4a-player-slim-cape-15.0.png`, `p4a-matched-*.png` and
`measure/p4a-aligned-glass.png`. Final default-build frame0 matches the original P4a capture exactly.

Aligned static/P3/P4a runs loaded101 hosts/60 modpacks, all observed API200. P4a visible60s WS439.05MiB,
private507.45MiB, Windows GPU total390.07MiB, wgpu147.125MiB allocated/223MiB reserved. Paired P4a-P3:
+17.08WS/+6.90private/+0.44allocated, unchanged reserved/GPU total. WS/private minus allocated deltas
+16.64/+6.46MiB; preserve the known8–16MiB run drift/accounting limits. Original100-host and overlapped
runs remain in evidence, aligned101-host replacements form the reported table. The P3 native private
residual remains P5, and P4b requires the owner's explicit choice. REPORT.md and p4a/summary.json contain
full per-phase counters, pipeline timings, API/appearance flags and limits. **Stopped at P4a.**


## P5 runtime diagnostics and completed gate

Defaults stay60FPS,45ms performance threshold,30-frame warm-up and60-frame windows. Inactive scene time
never advances; the last scene texture/glass stays visible. Terminal scene errors detach glass from the
scene, drop renderer/loader state, cancel pending appearance loads and prohibit reload during the session.
Scene-local validation is scoped; a device-wide loss still affects Slint/glass on their shared device.
The new diagnostics are default off and preserve accepted P4a images byte-for-byte.

|Flag|Diagnostic behavior|
|---|---|
|`--scene-fail`|Inject an actual scene-scoped wgpu validation error after180 scene frames|
|`--scene-startup-fail`|Fail loader initialization after resource/pipeline creation|
|`--scene-low-performance-ms=0.001`|Lower the measured completion-window threshold; normal threshold45ms|
|`--scene-steve-only`|Keep local Steve; skip scene-specific account skin/cape requests (menu heads unchanged)|
|`--scene-skip-post=fog/copy/mip/bloom/final`|Omit creation and execution of one P3 pipeline, preserve targets; diagnostic visuals differ|
|`--scene-post-create-only`|Create all P3 pipelines/resources but never execute them; use prior tonemap|
|`--scene-player-reflection-first`|Reverse creation order to test per-device shader cache reuse|
|`--scene-probe-hold-ms=0`|With memory probe, remove diagnostic stage sleeps for real startup timing|

`--scene-memory-probe` records allocator/Rust requests (allocation-probe build), activity and frame counts.
Only with this probe, `RDI_PROTO_CONTROL` may point at a local JSON command file `{id,action,value}`:
`focus`, `hide`, `show` or synthetic Slint `scale` events. Normal runs never poll a control file.
`tools/p5-runtime.ps1` exercises focus/minimize/hide/resume, resize, synthetic DPI, validation/loader/slow
fallback and no-glass. Synthetic scale dispatch is not a physical monitor-DPI transition test.

From P5 onward, `p5-measure.ps1` requires actual focus/visibility at60s visible and10s restored and actual
minimize at30s. It never enables probe-unfocused. `p5-measure-all.ps1` runs authenticated P3 per-pipeline
A/B (bloom first), created/unrendered, P2, Steve-only and full-scene controls with a fixed allocation-probe
binary, then final static/full-scene with the normal binary. GPU3D engine counters are sampled directly;
completion latency is not GPU utilization or power. No GPU-specific watts/temperature sensor is exposed
by the existing Windows interfaces; a generic thermal zone cannot be attributed to the scene.

`p5-final-validate.ps1` builds/checks normal and probe binaries, pinned under `evidence/bg-port/p5/controls`.
`p5-preview.ps1`, `p5-reference.ps1`, `p5-summary.py` produce visual and timing evidence. Reversed player
creation shows the first identical shader costs~28–29ms and the second~1–1.4ms; wgpu30 DX12 caches compiled
HLSL by source/entry/stage/model. Historical143ms is a cold/environment-dependent first compile, not an
intrinsically more complex main-view shader.

P5 live verification completed after desktop unlock. The first lock/hide and Slint-only scale tests were
preserved as invalid/superseded evidence. Native visibility keeps Slint's component registered; the final
DPI test injects WM_DPICHANGED144/192 through winit and Slint together, with screenshots checked. It is
not a physical monitor transition test. The lower-level Slint-only scale command does not coordinate
the winit renderer and must not be used as DPI acceptance evidence.

Every accepted visible/restored memory endpoint has both Windows foreground and application focus;
the helper obtains actual keyboard focus and verifies the app heartbeat too. A static restored case
with mismatched app focus and a later icon-network-fallback case were excluded and rerun. The final
normal release pair loaded100 hosts/60 modpacks, all observed API200, no warnings/fallback. Full scene:
WS456.21MiB/private502.84MiB/GPU390.07MiB; static199.30/259.55/228.16MiB. Full-scene tracked allocation
147.188MiB/reserve223MiB. Complete per-phase tables and separate accounting are in REPORT.md.

All54 Windows tests, all-target/all-feature Clippy, release app/examples and probe build pass. Default
0/15/45 pixels equal accepted P4a; final delivery frame0 also matches exactly. Focus/hide/minimize pause,
resume, resize/native-DPI, actual validation/loader/low-performance terminal fallback and no-glass pass.
After fallback no named scene resource allocations remain; unused device heap reserve/cache can remain.
The scene does not restart. Glass/menu and screenshots remain valid after fallback/restoration.

Authenticated per-pipeline and created-but-unrendered A/B found execution-associated native/private
cost~51..69MiB with unchanged placed resources/reserve and tiny live-Rust difference. Single-stage
private deltas overlap~19MiB repeated-baseline drift; no single shader is proven to own the residual.
Account-versus-Steve downloads show no stable positive private delta. See p5/attribution-notes.md and
summary.json for controls and exact attribution limits; no driver object/leak claim or optimization.

Live completion windows average11.084ms, max sampled35.999ms. Actual per-process3D utilization is~66%
visible full-scene versus~1.4% static; stable hidden/minimized scene phases are0%. No GPU watts/temperature
sensor is available through existing interfaces. A30FPS scene is proposed for later A/B only;60FPS/live
defaults stay unchanged. Startup first UI frames static1202ms/full1196ms; first live2605ms (probe overhead,
no artificial stage delay). No statistical startup improvement claimed.

Reproduce via p5-resume.ps1 / p5-runtime-all.ps1 and p5-measure-all.ps1 under the test-account process
environment; valid case manifests skip repeats, new tags preserve rejected attempts. Native-DPI recheck
uses p5-dpi-check.ps1. Then run p5-summary.py and p5-report.py. Review
`evidence/bg-port/p5/measure/final-scene-r3-glass.png`, runtime/policy-native-dpi-r1-scale1.5.png,
p5-matched-*.png and gate-summary.md. **P5 complete, stopped for owner review; no section6/P4b work.**

## P4b hill crowd

Plan: `P4B-PLAN.md`. The crowd is on by default: up to 50 skins from `/player/unique-skin-list`, with Steve as the stand-in. `--no-crowd` (alias `--scene-p4a-only`) skips its resources and requests. In `scene_preview`, use `--crowd=N`, `--crowd-skins=<dir>` and `--dump-crowd`.

Reproduce from this directory on Windows, with the test account only in the process environment for the last two scripts:

```text
tools/p4b-reference.ps1      Kotlin preview and matched probe captures
tools/p4b-validate.ps1       tests, Clippy, release
tools/p4b-preview.ps1        Rust captures, then tools/p4b-compare.py
tools/p4b-timing.ps1         matched frame timing
tools/p4b-measure-all.ps1    authenticated focused memory batch
tools/p4b-fallback.ps1       terminal fallback resource check
```

Results are in `REPORT.md` and `evidence/bg-port/p4b/gate-summary.md`. **P4b complete, stopped for owner review.**

## M1 workspace and review gate

The app depends on the UI-independent [rdi-renderer](crates/rdi-renderer/README.md) crate.
Renderer examples now belong to that package; use `-p rdi-renderer` when running them.
The `allocation-probe` app feature forwards to the renderer; the app owns the global allocator.
Historical `tools/p*.ps1` remain unchanged.

`tools/m1-reference.ps1 before|after` saves release binaries, tests, repeated scene/glass captures,
six startup runs and clean-close measurements in `evidence/m1/<phase>`.
Run `before` only on the unmodified source. It intentionally fails on build/capture errors.
`tools/m1-validate.ps1` runs workspace tests and Clippy for both feature sets and records dependency trees.
Gate results are in `evidence/m1/gate-summary.md`. M2 requires owner review at G1.

`tools/m1-runtime.ps1` runs a counterbalanced authenticated before/after startup batch and missing/
unloadable DXC cases. It reads the existing test account from the Debian WSL UNC path into process
environment variables, without writing or logging credentials. `tools/m1-compare.py` (Pillow/NumPy)
compares repeated captures and writes local `evidence/m1/comparison.json`.

## Menu frame pacing

`--frame-stats` logs the time between rendered frames every five seconds while the scene animates.
`--scene-pacer-ms=N` (1 to 16, default 4) sets how often the scene asks for a frame; 16 brings back
the occasional double-length frame. `tools/menu-pacing.ps1` compares pacers on every display and
reports long frames and CPU. It signs in with the test account in a temporary data directory, and
takes the foreground for the whole run.

These are UI callback intervals, not displayed frames, so they cannot show a stale picture: the
rounded window's cached layer once froze the background while they still read 60fps. Compare the
default size with a maximized window by eye as well.

## U1 UI risk check (GU1)

Run `target/release/rdi-slint-proto.exe --ui-proof` for the login form, 1000-row virtualized room list,
layout-driven glass panel, modal and mixed-text examples over the unchanged valley.
Login calls POST /player/jwt; account/password are not saved. Network image loading has loading/error/retry states.
`--ui-proof-script` waits for a live scene and five seconds of focus, then scripts 30s wheel scrolling
and 10s panel animation. It logs p50/p95/p99/worst frame intervals and CPU render work separately.
Unfocused or missing-scene frames invalidate the run. These are UI callback intervals, not GPU timestamps.

- `tools/u1-validate.ps1`: workspace tests, all-target/all-feature Clippy, release app/examples.
- `tools/u1-runtime.ps1 -Tag <new-tag>`: three focused runs, memory samples and native DPI/window captures.
- `tools/u1-auth.ps1 -Tag <new-tag>`: real login through the proof submit callback using the existing
  local test-account file, never copying credentials into commands or logs.
- `tools/u1-platform-compare.py <Windows-dir> <platform-artifact-dir>`: RGB MAE for 0/15/45 captures.
- `.github/workflows/slint-proto-platforms.yml`: adapter-first macOS/Linux CI; requires owner approval
  before pushing. No credentials are supplied to CI.

[GU1 manual checks](evidence/ui-port/u1/owner-checklist.md) include Windows Chinese IME and physical-monitor DPI.
U1 replaces the old M2 work; walking, picking, world expansion and resolution/FPS changes remain on hold.

## U2 Kotlin service development

Build `:ui-service:installDist` from `client/ui` with the Windows wrapper before
running the Rust UI. The default command discovers JDK25 using the updater's
candidate search and launches `calebxzau.rdi.service.MainKt` with the development
service classpath. It uses `client/ui/run` as its data directory. The updater is
unchanged. Shipping paths and cross-platform packaging are U5 work.

`RDI_SERVICE_COMMAND` accepts a JSON argument array; e.g. an absolute Java path,
`-cp`, a classpath, and the main class. Credentials are sent in the login request,
not passed on the child environment or command line. Images still download on
the Rust side. API requests run through the Kotlin service.

The protocol is documented in `protocol/README.md`. `tools/service-client.py`
is an interactive framed JSON client. `tools/u2-service-smoke.ps1` checks the
real service, login when credentials are supplied, and EOF shutdown. Its
`-LaunchGame` option requires an installed playable test room; it closes the
service (and therefore the test game) when done. `tools/u2-ui-smoke.ps1` brings
the UI foreground, captures it, kills its service child, verifies restart and
menu recovery, then closes the UI. These are development checks, not shipping
commands. Never print or record test credentials.

Service logs: `client/ui/run/logs/ui-service.log`; native/JVM stderr is captured
in `ui-service-stderr.log` under the Rust application's working directory.
Missing packs must currently be installed through Compose (installation UI is
U4). Windows main-flow acceptance is recorded in `evidence/ui-port/u2/gate-summary.md`; stop at GU2 pending owner review.

`tools/u2-game.ps1 -Action Start -LocalCore` starts the test UI and sends a click
on its first visible room card. `-LocalCore` explicitly sets RDI_SERVICE_CORE_JAR
to the local1.21.1 NeoForge build; the headless service applies this after normal
core synchronization. A different existing JAR is archived under a unique
repository DEL path before copying. This is a development override, never a
release/updater change. Omit it for normal core synchronization.

`tools/u2-compose.gradle` supplies the development runtime classpath task;
`tools/u2-compose.ps1` checks the existing Compose launcher without packaging a
release. The game-run helper's Capture/Duplicate/CancelDialog/Stop/CloseRequest/
ConfirmClose actions support the GU2 Windows checks. RMCP screen tools were used
for game UI control once the owner-requested local core was installed.

Service crash recovery retains process identity (PID plus creation time) in the
Rust UI and restores management after restarting Kotlin. The local game
connection drops on a crash and may require reconnecting; console history in the
dead service is not restored.

## U3 component review (GU3)

Run `target/release/rdi-slint-proto.exe --gallery`. No account is needed. Review
buttons/fields, content/images, console/tasks and glass tiles using the four tabs.
Home returns to the real menu; the back stack preserves menu tab/scroll state.
Only the menu animates the loaded scene, and a running game holds it too.
U4 destinations still invoke the existing stub callback; no placeholder screens were added.

Windows validation: `./tools/u3-validate.ps1`. Capture each gallery page and its
loading/empty/error states with `./tools/u3-gallery.ps1 -Scale 1` and
`./tools/u3-gallery.ps1 -Scale 2` (choose a new `-Tag` on reruns). These use
`capture.ps1`, `--scale` and native DPI messages, verifying actual scale/size first.
`u3-interact.ps1` exercises Win32 clicks, native mouse scrollbar drags, keyboard and the sample clipboard;
`u3-shell.ps1` checks focus, navigation and scene hold/resume (the game state is
explicitly simulated, not an in-game acceptance test). `u3-measure.ps1` measures
release UI/service memory separately with the real test account; its first run is
warm-up. Keep the monitor on, desktop unlocked and application foreground.

See `evidence/ui-port/u3/gate-summary.md` and `evidence/ui-port/u3/owner-checklist.md` for results and
remaining owner checks. Stop at GU3 until the owner accepts the gallery.
