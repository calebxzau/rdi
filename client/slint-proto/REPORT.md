# Slint MenuScreen prototype — report

Current status (2026-10-31): P1 full static scene is implemented with the owner's approved emitted-position
quantization and MSAA-off/24B defaults. Current counts, safety checks, displacement, memory and review
images are in the final section "P1 full static scene". Earlier stop/recommendation sections below
record the history and are superseded where the owner later approved a different choice.

Date: 2026-10-30. Plan: `/tmp/slint-proto-plan.md` (approved). Only this prototype was measured; the Compose
launcher was not (owner's decision). The owner accepts memory taken by the GPU driver, and chose wgpu with DX12
over OpenGL after the comparisons below.

## Environment

- Windows, AMD Radeon 780M (integrated), driver 26.10.1; monitor scale 2.0, window 1280×720 logical (2560×1440 px).
- Rust 1.97.1 (MSVC), Slint 1.18.1 (`backend-winit`, `renderer-femtovg-wgpu`, `unstable-wgpu-30`, `renderer-software`,
  `unstable-winit-030`), wgpu 30 (through Slint), tokio with 2 workers, reqwest 0.13 (rustls).
- Render path: **femtovg on wgpu, backend forced to DX12 on Windows** (`WGPU_BACKEND` overrides). Before each frame
  our wgpu pass draws background + glass into a texture that Slint shows as the window's bottom `Image`.
- The first version used an OpenGL underlay (femtovg + glow); it is archived in `DEL/20261030-slint-proto-gl-to-wgpu/`.

## Status vs. plan acceptance

| Area | Status |
|---|---|
| Login with env credentials, title bar name + head | Done, verified |
| Static background (cover crop) | Done, verified |
| Host grid: real data, icons, owner/online heads, status dot, dimming | Done, verified |
| Paging `host/list/{page}` on scroll, end on empty page | Done, verified (logs show the extra page and the end) |
| 显示/隐藏其他房间, empty-state texts | Done, verified |
| 整合包 tab (stand-in data from `/modpack/search`, real tab scans disk) | Done, verified |
| Tab switch slide + fade, selected-tab glass moves | Done, verified on screenshots |
| Tile glass: blur, refraction, bevel specular, hairline, shadows | Done; DX12 output matches the GL version side by side |
| Hover lift / press shrink (springs) | Done; driven with posted mouse messages (real cursor untouched) |
| Tile click / right click | Logged only (actions out of scope); left click verified in log |
| Title bar drag, minimize, maximize, close | Implemented via winit; **not verified** by hand |
| DPI change between monitors | Implemented (cache keyed by size + scale); **not verified** |
| Linux / macOS | **Not built** |
| Side-by-side with Compose | **Not done** (Compose not run) |

## Memory (release build, `--show-others --all-playable`, ~30 tiles with icons on screen)

Final DX12 build:

| Phase | Working set | Private | GPU dedicated | GPU shared |
|---|---|---|---|---|
| First screen loaded (11s) | 147–152MB | 243–245MB | 112MB | 98MB |
| Idle 60s | 147–151MB | 242–245MB | 112MB | 98MB |
| Minimized 30s | 147–154MB | 210–223MB | 76–92MB | 50–52MB |
| Restored 10–40s | 149–229MB | 241–319MB | 108MB | 99–178MB (varies run to run) |
| After switching to 整合包 (+60s) | 173MB | 267MB | — | — |

Same content, glass disabled (`--no-glass`), idle 70s — the renderer choice alone:

| Renderer | Working set | Private | GPU shared |
|---|---|---|---|
| femtovg + OpenGL | 186MB | 227MB | 283MB |
| femtovg-wgpu + DX12 | 133MB | 195MB | 92MB |

Empty Slint window (`examples/baseline.rs`): software 31.5MB, wgpu-DX12 148MB, wgpu-Vulkan 179MB, OpenGL 172–254MB
working set. The full prototype on the software renderer (no glass) used 48.6MB.

What moved the numbers after switching to DX12 (idle working set 468MB → 151MB):

1. **Tile models are updated in place** (`sync_rows` in `app.rs`). Replacing the `VecModel` on every refresh
   recreated every tile; images made from pixel buffers have no shared texture cache in femtovg, so each refresh
   uploaded every icon again. DX12's allocator keeps the freed memory. (−85MB)
2. **No rounded `clip` and no container `opacity`** in tiles. femtovg renders both through an offscreen layer per
   element; with wgpu that cost ~150MB for one screen of tiles. Icons now get their rounded corners baked in on
   the CPU (`images::round_corners`), and dimming/fading multiplies each leaf's alpha. (−150MB)
3. **Blur in background image space**: the cover crop is a uniform scale, so the three frost textures are
   blurred at the 1334×800 source resolution instead of the window's, and no full-size intermediate exists.
   No visible difference. (−77MB)
4. **Grids no longer fade with `opacity`** during the tab switch (same layer problem, one layer as wide as the
   whole scrollable grid). Switching tabs went from +115MB to +23MB (the 整合包 icons themselves).

## Performance

- Time to first frame: ~1.1–1.2s on DX12 (0.47s on OpenGL); the six pipelines are compiled at startup.
- Binary: 31.2MB (fonts, `bg.png`, wgpu/DX12 and the software renderer embedded).
- No redraws while idle. Process CPU counters on this machine read implausibly low, so CPU was **not measured**.

## Other fixes in this session

- clippy (`-D warnings`) and a head-alpha test were failing; `HeadCache` held a global lock across requests
  (first screen took ~30s), now per-key `OnceCell`; error chains are logged in full.
- `media.forgecdn.net` resets TLS handshakes beyond about five parallel connections from one address (curl and
  Windows' own HTTP stack fail the same way). Downloads are now limited to 3 per host, plus 2 connect retries.
  Three later runs had no failures, but the CDN may simply not have been throttling then.

## Known gaps

- One unexplained exit right after the first frame (no panic message, exit code not captured); it did not
  recur in the next 6 runs.
- Restoring from minimize sometimes adds up to ~80MB (the window briefly reports 314×50 and the size cache is
  rebuilt twice); it varied from +2MB to +80MB between runs.
- A failed icon is cached as "no icon" until restart. Player head caches never evict (8×8 images).

## Slint findings

- No backdrop blur or custom shaders in `.slint`; a wgpu pass rendered into an imported texture
  (`Image::try_from(wgpu::Texture)`) works and keeps the Kotlin shader math nearly 1:1.
- femtovg layers (rounded `clip`, `opacity` on anything with children) are cheap on OpenGL but expensive on
  wgpu/DX12; UI code should avoid them on hot paths.
- In-memory images (`SharedPixelBuffer`) are cached per item, not globally; keep models stable.

## Recommendation

DX12 is the better backend here: lower driver floor and, after the fixes, the prototype idles at ~150MB working
set with the full glass look. The remaining large share is the AMD driver (`amdxc64.dll` alone maps 135MB).
For a full migration, the 3D background (`bg-renderer`) fits the same wgpu pass, and the business logic
(accounts, modpack install, MC launch, tasks) remains the bulk of the work.

## Golden-valley port P0 — 2026-10-30

Approved handoff: `/tmp/slint-bg-port-plan.md`. **P0 review gate only; P1–P5 and the later startup
optimizations have not been implemented.** All source changes are in this prototype. The Kotlin renderer
and launcher were used as read-only references (including a Windows valley preview).

Implemented: deterministic terrain with all four LOD bands, greedy tops, boundary side segmentation,
AO and material strata; untextured albedos averaged in linear light from the bundled block textures;
reference camera, single sun direction, clear golden sky, analytic height atmosphere,4xMSAA HDR resolve,
reference tonemap/saturation,1280×720 RGBA8 output. A worker waits for the first static frame, then builds/uploads/compiles while that
background displays. Live glass shares half/quarter downsample targets and reuses frost targets;
selected-pill frost stays at half resolution. No application readback or per-frame texture allocation.

Validation on Windows/MSVC Rust1.97.1, DX12/Radeon780M:

- `cargo test`:24 passed (9 scene +15 existing), zero failed/ignored. Includes the literal Kotlin noise
  hashes, exact terrain counts, LOD seam area, outward winding, summit, projection depth, clock/performance
  policy, naga validation and uniform/vertex layouts.
- `cargo clippy --all-targets -- -D warnings`:passed. `cargo build --release --examples --bins`:passed.
  Exit files and full logs: `evidence/bg-port/p0-{test,clippy,release}.log/.exit`.
- Terrain counts match the new Kotlin preview exactly:81217 cells,4994 merged tops,36224 AO tops,
  60912 side quads; Rust terrain408520 vertices/612780 indices. The full Kotlin reference (seed123)
  has714386 vertices/1071576 indices, including13173 trees and196 grass tufts that are outside P0.
- Captures at0/15/45s: `p0-rust-*.png`, `p0-kotlin-*.png`, `p0-compare-*.png`. The Kotlin images include
  later passes; these are composition references, not a claim of full-scene parity at P0.
- Real menu/glass screenshots: `p0-authenticated-static-glass.png` and `p0-focused-scene-glass.png`.
  Same test account,100 hosts and59 modpacks loaded, about30 tiles visible; no credentials in artifacts.

### Memory

Paired release processes,2560×1440 pixels, scale2. Units below are **MiB**, not decimal MB. Counters use
process WorkingSet64/PrivateMemorySize64 and Windows GPUProcessMemory dedicated/shared usage. Earlier
unauthenticated runs and the initial scene run that failed to obtain foreground focus are retained as
calibration evidence and excluded. The valid live run logs steady scene completions, pauses for the
30-second minimize interval, resumes without fallback, and exits normally.

| Phase | Static working set | Live working set | Static private | Live private | Static GPU dedicated/shared | Live GPU dedicated/shared |
|---|---:|---:|---:|---:|---:|---:|
| Loaded (~11s) |233.4|416.8|265.8|431.8|49.7 /178.9|67.7 /306.4|
| Foreground60s |233.3|390.5|265.4|437.6|49.7 /178.9|67.9 /309.4|
| Minimized30s |217.4|390.6|212.2|417.5|13.3 /115.1|47.7 /261.6|
| Restored10s |298.2|390.4|326.5|417.6|46.0 /242.9|47.9 /309.4|

Foreground live adds157.2MiB working set,172.2MiB private and148.7MiB GPU counters. Explicit P0 scene
resources account for about75.3MiB:22.6MiB mesh/index buffers +52.7MiB MSAA color/depth, HDR resolve and
output. Rgba16Float is required on Slint's automatic device; switching to Rg11b10Ufloat remains a later
approved optimization. The remaining counter delta includes driver/allocator behavior and source/cache
switching; their contributions have not been isolated. No optimization outside P0 was attempted.
These process counters are snapshots, not a retained-heap attribution or a long-running leak test.

Raw valid samples: `p0-authenticated-static-memory.jsonl`, `p0-focused-scene-memory.jsonl`.
Static restore again increased memory, consistent with the earlier report's resize/cache observation.
The live scene remains allocated while minimized, preserving its last output and frosts as required.

### Frame work and startup

| Measurement | Result | Scope |
|---|---|---|
| Rust P0 paced preview,180 frames |3.097ms average /4.083ms max;60.26FPS cadence|CPU encode + completion wait; no glass, readback or presentation|
| Live app,79 windows of60 completion samples |15.248ms average /34.559ms max|CPU encode + submit-to-callback latency; includes callback dispatch/poll delay, not GPU timestamp duration or presented FPS|
| Kotlin full valley reference,180 frames |9.65ms render/readback +0.78ms copy/publication average;59.82FPS cadence|Complete reference scene and synchronous readback; different work from Rust P0|
| Scene pipeline compilation in live app |terrain32.37ms /sky24.70ms /post14.72ms|Worker thread, shader-module + pipeline creation wall time|
| First static/live-mode frame,3 final startup runs |static median1.517s /scene median1.457s|Static1.662/1.517/1.410s; scene1.495/1.382/1.457s; no consistent regression in this sample|
| First live scene output (final build) |1.754s|Static frame at1.346s; loader started only after the static frame|

The frame completion callback is dispatched when native wgpu is polled/submitted. This explains why the
app's completion latency is near one refresh interval while the waiting headless preview is around3ms;
it is not evidence that the scene itself takes15ms of GPU execution. The app keeps at most one scene
submission outstanding. No performance fallback occurred. Pause-spanning callback waits are discarded
on resume; inactive time does not advance animation, and missed deadlines do not cause catch-up bursts.

Remaining acceptance: owner visual review (especially sky clarity). P1 vegetation/textures/shadows,
P2 water/clouds, P3 fog/bloom, P4 player/crowd and P5 injected failures, memory release on fallback and
resize/DPI stress remain behind their gates. Device-wide loss invalidates Slint/glass too on a shared
device; scene-local validation/allocation/performance failures are scoped. This phase does not claim
that glass can survive physical loss of the shared device.

Final startup revision: the scene worker is created in RenderingSetup but waits on a channel until
AfterRendering of the first static frame; teardown cancels a waiting worker. The memory table and
long-running live work samples above were captured before this initialization-only sequencing fix;
the scene/glass resource set and steady-state render paths are unchanged. The final binary was rebuilt,
all24 tests and clippy passed again, and the final app logged a live frame at1.754s. Final first-frame
startup logs are `p0-startup-{static,scene}-{1,2,3}.log`; the first scene run includes `wgpu_core=debug`.
Automated startup windows that lacked foreground focus are used only for their first static frame,
not for live-frame latency. `p0-pre-gate-startup-*` are rejected calibration data (the earlier helper
left its windows running); they are not included in the final startup result.
The optional `p0-final-scene-memory.jsonl` repeat lost foreground focus after its initial live frames
(its `Focused` field is false). It is also excluded from the foreground memory/frame-work table.
The accepted steady-state run remains `p0-focused-scene-*`, whose activity/completion log spans the
foreground interval and explicitly shows minimize/resume. The final build's short live run verifies
initialization sequencing, not a new memory baseline.

## P0.5 memory

Date:2026-10-30. P0 visuals, frame time and startup were accepted by the owner. This investigation
keeps the default image unchanged and stops before P1. Every saving experiment is behind an explicit
flag; no MSAA or vertex-format choice has been promoted to the default.

### Conditions and the static baseline

The accepted measurements are in `evidence/bg-port/p0.5/controlled/`, with full per-second Windows byte
counters, timestamped wgpu allocator reports, exit codes and window-only PNGs. Same release executable,
account supplied by the owner through child environment variables, `--show-others --all-playable`,100
hosts/59 modpacks, about30 visible tiles,2560×1440 pixels at scale2. Values in this section are **MiB**
(2^20 bytes). Working set/private are process counters; GPU dedicated/shared are WDDM GPUProcessMemory
counters. They must not be added to process memory as independent RAM usage.

Windows refused some background foreground requests. Measurement now selects the actual window title,
not a26×26 auxiliary window, and the controlled batch uses `--scene-memory-probe
--scene-probe-unfocused`. This explicitly permits animation without focus; minimize/hidden state still
pauses it. Actual focus/foreground PID is recorded, not assumed. The normal app's policy is unchanged.
The diagnostic probe also requests1Hz redraws so post-fallback allocator retirement can be observed.
Earlier `fixed/` and top-level calibration samples are retained but excluded from the main comparison.

The pre-port report's147–152MB working set/~98MB GPU shared cannot be reproduced from the surviving
artifacts: there is no corresponding pre-port DX12 binary or raw-byte snapshot with its exact account,
foreground and driver-residency state. The archived renderer is OpenGL and is not a valid DX12 control.
A decimal/binary unit mix would explain only about5%, not the observed80MiB-class difference.
A preserved unmodified P0 binary, measured on the current host, itself reached282.3MiB working set,
272.7MiB private and228.7MiB total GPU usage. This shows that working set/shared residency varies beyond
source changes; it does not establish a source-level cause for the older baseline. That control and
its window screenshot are in `fixed/old-static-*`. Its minimize/restore samples are not the primary
controlled comparison.

Source and allocator checks found **no static-mode scene allocation leak**: `--no-scene` never calls
`scene::loader::spawn`, constructs no scene pipelines/buffers/targets and creates no live-glass chain.
Static snapshots contain no `scene ...` allocation. The static frost path retains the same three
textures as before; horizontal intermediates retire after their one-time blur. No default allocation
bug fix was necessary. The following tables therefore re-baseline both modes under one explicit
condition, instead of treating the old150MB statement as a current target measurement.

### Attribution

`Device::generate_allocator_report()` is implemented by wgpu30's DX12 suballocator and reports each
placed resource, its offset and size, and heap capacity. Payload bytes, native resource allocation,
allocator reserve and Windows GPU counters are different quantities. In the steady default live run:

| Scene resource | Payload MiB | DX12 allocation MiB |
|---|---:|---:|
|408520 vertices ×52B |20.260|20.3125|
|612780 u32 indices |2.338|2.375|
|1280×720 RGBA16F ×4 color |28.125|33.125|
|1280×720 D32 ×4 depth |14.0625|16|
|1280×720 RGBA16F resolved HDR |7.03125|7.5625|
|1280×720 RGBA8 output |3.515625|3.8125|
|256B uniform buffer |0.000244|0.0625|
|**Total scene** |**75.332**|**83.25**|

The approximately7.92MiB difference is measured resource alignment/layout overhead, not another retained
copy of the terrain. These resources live in DX12 heaps whose residency contributes to dedicated/shared
GPU counters; there is no supported per-resource split between those counters. Their division changed
between runs, so total GPU usage is also reported.

Live glass retains3.735MiB of payload (shared half/quarter chain, horizontal intermediates and three
frosts), versus6.107MiB of static frost payload. The allocator shows a net **3.25MiB reduction** after
the source/cache switch. Glass does not account for a large positive live delta. The window-sized
backdrop exists in both modes.

At the60-second endpoints, static/live tracked allocation is61.5/141.5MiB, and tracked reserve is
87/207MiB. Thus:

- Live resources net of the replaced frosts add80MiB tracked allocation (83.25−3.25).
- Unused capacity rises25.5→65.5MiB: **40MiB additional heap slack**.
- The tracked reserve grows120MiB, versus147.07MiB additional Windows GPU usage. The remaining
  **27.07MiB is outside this placed-resource report**. Native swapchain/driver/runtime allocations and
  residency accounting are not separated by this API; it is not proven to be a leak or all pipeline data.

A pipeline-only run retains no terrain or scene targets. Compared with the static control it adds
0.0625MiB tracked allocation (one uniform buffer),16MiB tracked reserve, about35MiB private and2.76MiB
Windows GPU usage. Pipeline construction therefore explains a material CPU/native-process increment,
but it does not by itself explain the entire27MiB GPU residual. The attribution remains explicitly
partial at that boundary, rather than labeling every remaining byte as driver memory.

`create_buffer_init` maps and unmaps a temporary upload resource in wgpu-core. In the staged default
probe, upload-before-wait allocation was106.5625MiB; after empty submit + `device.poll(Wait)` it was
83.8125MiB. The22.75MiB temporary upload allocation disappeared, and reserve fell183→151MiB. Repeated
idle reports do not retain it. The upload buffers are a peak, not a permanent second mesh.
The chunked experiment reuses one1MiB mapped buffer and waits before remapping; its before/after-wait
allocation is80.0625MiB in that run (UI allocations vary slightly), with no large upload staging entry.
It lowers peak staging, but the steady-state Windows usage/reserve remained essentially unchanged.

After minimize/restore, tracked live bytes return to the same value while heap reserve can remain
higher (default live207→252MiB, static87→164MiB). This distinguishes native heap retention from duplicate
live scene objects and explains why restore snapshots are a different baseline from pre-minimize ones.

### Controlled endpoint and experiment tables

Each normal run used a loaded snapshot,60 seconds of live/idle work,30 seconds minimized and10 seconds
restored. `lazy-drop` instead observed terminal release for25 seconds. The real focus state varied under
Windows; every live case used the same explicit unfocused-rendering policy. This controls rendering
activity, not the OS's residency priorities. Consequently the Windows columns are observations from
single runs, not guaranteed savings caused by a flag. The separate resource/allocator evidence is
stronger for attributing byte costs. `foreground60s` is the script's historical endpoint name; it does
not assert OS focus in these controlled runs.

All columns below are MiB. `packed` uses the successful `packed-checked` repeat. The original packed
run was unexpectedly minimized mid-interval (314×50 screenshot) and is rejected, not counted as a saving.

| Case | Working set | Private | GPU dedicated/shared | GPU total | Tracked allocation/reserve |
|---|---:|---:|---:|---:|---:|
|static|197.52|259.50|81.53/128.31|209.84|61.50/87.00|
|live|352.09|430.50|98.12/258.79|356.91|141.50/207.00|
|pipelines|234.94|294.50|80.19/132.41|212.60|61.56/103.00|
|chunk|352.52|429.45|98.19/259.85|358.04|137.75/207.00|
|msaa2|361.34|439.18|98.16/266.79|364.94|113.44/199.00|
|msaa1|349.80|404.12|98.60/221.10|319.70|95.94/159.00|
|packed|392.73|437.84|65.92/302.66|368.58|128.25/223.00|
|best|319.16|367.54|65.27/232.47|297.74|84.06/159.00|

The full loaded/minimized/restored table is in
[`evidence/bg-port/p0.5/all-phases-table.md`](evidence/bg-port/p0.5/all-phases-table.md); exact bytes,
foreground PID, allocation lists and stage timestamps are preserved in the JSONL/logs and `summary.json`.
Static and live60-second working-set deltas here are about154.6MiB, close to P0's157MiB delta even though
absolute working sets shifted. Resource accounting does not show a scene allocation leak in static mode.

| Experiment | Verified resource effect | Paced preview mean/max | Pixel result at0/15/45s |
|---|---|---|---|
| Default4x/52B/u32 |75.33MiB payload;83.25MiB DX12 scene allocations|4.633/8.782ms|Exactly identical to P0 at all3 times|
|2xMSAA only |Scene allocation59.25MiB (−24MiB); reserve/Windows savings not proportional|3.644/6.952ms|Changed edge coverage; mean RGB error0.393–0.419/255|
| MSAA off only |Scene allocation38MiB (−45.25MiB); Windows GPU snapshot−37.21MiB|2.775/6.062ms|Visible aliasing on terrain edges; mean RGB error0.713–0.739/255|
|24B vertices +u16 only |Vertex/index payload22.597→10.519MiB; buffer allocations save12.125MiB|4.571/7.925ms|Maximum RGB error1/255, mean0.0024–0.0025/255|
|1MiB chunked upload |Lower staging peak; steady reserve unchanged at207MiB|4.656/9.387ms|Bit-identical|
| Lazy targets only |No HDR/MSAA/depth/output targets before the first live frame; no steady-state saving|4.692/9.233ms|Bit-identical|
| Packed +MSAA off +lazy |24.58MiB payload;25.875MiB measured scene allocation; Windows GPU snapshot−59.17MiB|2.746/5.147ms|MSAA edge differences remain|

Preview timings are CPU encode plus explicit GPU-completion wait over180 paced frames, after30 warm-up
frames. They exclude glass, readback and presentation. Each experiment has side-by-side PNGs
`compare-<variant>-{0.0,15.0,45.0}.png`, raw previews and numerical `pixel-differences.json`.
2xMSAA required `TEXTURE_ADAPTER_SPECIFIC_FORMAT_FEATURES`; only that flag requests the capability
through Slint's Automatic settings. No Manual device or Rg11b10Ufloat was introduced.

The packed test checks every P0 position and normal exactly and reconstructs every original u32 index
from the u16 batches, retaining408520 vertices/612780 indices. Linear color/AO use f16, preserving HDR
snow above1; this is the source of the at-most1/255 output error. **This24B prototype currently accepts
integer terrain positions only.** P1 grass/house details contain fractional coordinates and need a local
fixed-point or f16 position encoding before this can be chosen as a uniform P1 vertex format. Unsupported
coordinates fail explicitly instead of being rounded silently. These results validate the current
terrain experiment, not unported P1 geometry.

The standalone packed run did **not** lower Windows working set or total GPU usage in its valid repeat,
despite smaller buffers.2xMSAA likewise did not lower those counters in its run. Heap selection/slack,
UI/native allocations and residency state can outweigh the smaller payload. The combined off+packed
case did lower total GPU usage from356.91 to297.74MiB and private from430.50 to367.54MiB. This observation
supports that particular combination as an option, not an additive prediction for arbitrary combinations.

### Delayed allocation and terminal release

In the lazy run, `pipelines-ready` contains only the scene mesh/index/uniform allocations. All four
scene textures first appear at `lazy-targets-first-frame`. Delaying them changes startup allocation
timing; it moves that work onto the first live frame and does not reduce the steady resource set.

The diagnostic terminal fallback removed every `scene ...` allocation by the immediate `terminal-drop`
snapshot. The next periodic report (about1.3s later) shows static glass at61.5MiB tracked allocation.
Windows GPU usage fell about72.3MiB within that first subsequent sample; working set/private fell by
about72MiB by9.8s. At25s, GPU usage was still289.46MiB and tracked reserve143MiB, above the cold-static
209.84MiB GPU/87MiB reserve. Thus object lifetime release works, but56MiB of allocator capacity and
additional native/OS residency remain after fallback. The experiment does not claim all memory is
returned to Windows. This is a terminal session fallback; it does not restart the renderer.

The27.07MiB reconciliation residual above is a difference between two accounting models, not an
independent physical-memory bucket. Pinning it to a particular driver allocation would require a native
allocation trace beyond this experiment. Object liveness and heap-capacity retention are established
by the allocation reports; the untracked native/residency split remains open.

### P3 projection and recommendation

Computed payloads below use the current full Kotlin preview's714386 vertices (about306k more than P0),
approximately1.5 indices per vertex,2048² D32 shadow, pre-water HDR/depth captures,640×360 reflection
with mips/depth, half-res RGBA16F fog,64³ R8 fog noise, mipmapped HDR linear copy, full-size RGBA8 bloom
atlas/final output, and0.75MiB allowance for block/cloud/noise maps. These are **estimates of resources**,
not measured P3 Windows memory. Breakdown and formulas are in `projection.json`/`tools/p05-projection.py`.

| P3 choices | Estimated resource payload | Computed Rg11b10 saving, if later supported |
|---|---:|---:|
| Current4x +52B/u32 |137.67MiB|26.95MiB|
|4x +24B/u16 |116.55MiB|26.95MiB|
|2x +24B/u16 |95.45MiB|19.92MiB|
| MSAA off +24B/u16 |77.88MiB|12.89MiB|

At P0 the native scene allocation is about7.9MiB above payload and live heap slack adds another40MiB
relative to static; P3 will also have padding, heap granularity and untracked native costs. These are
not linear coefficients, so a credible P3 process working-set number cannot be obtained by adding the
payload to today's working set. Rg11b10 numbers are arithmetic only and remain outside P0.5 scope.
Slint already requests `MemoryHints::MemoryUsage`; this was not accidentally the Performance allocator.

Recommendation: **keep the accepted4x visual default unless the owner accepts the AA-off comparison**.
Do not promote chunked uploads or lazy targets as steady-state memory fixes. Packed geometry is a useful
resource-budget direction, but its independent Windows-memory win was not demonstrated and fractional
P1 positions still need support. If the owner prioritizes the measured GPU reduction and accepts the
edge changes, MSAA off +24B is the tested combination with a roughly59MiB total-GPU reduction here.
The next native-memory target should be heap capacity/lifetime behavior (including transient minimized
window sizes), rather than assuming another copy of the mesh is leaking. The27MiB accounting residual
and older150MB baseline remain explicitly unresolved within the limits above.

Validation: Windows/MSVC `cargo test`26 passed, zero failures/ignored; `cargo clippy --all-targets -- -D
warnings` clean; release binary/examples built. All seven final preview variants exited0. Kotlin valley
reference reran successfully at0/15/45s with the same terrain counts; its frames are retained alongside
the Rust evidence. No Kotlin or Compose source was changed. This phase ends at the owner's MSAA and
vertex-format choice. **P1 has not started.**

## 2026-10-31 owner decision and P1 entry

The owner selected MSAA off and24B packed positions. Samples1/packed are now the defaults;4x and52B
are diagnostic overrides (`--scene-msaa=4`, `--scene-full-vertices`).2x is rejected and the corresponding
adapter-specific format-feature request was removed. Lazy targets/chunked upload remain behind flags.
The preceding P0/P0.5 tables are historical measurements, not a description of the new defaults.

Packed positions are now i16 in exact1/16-block units relative to a per-batch origin. The range is
−2048..2047.9375 blocks per axis around that origin; batches also split at the u16 vertex limit.
Encoding checks both grid membership and exact round-trip, without epsilon or implicit rounding.
An origin uniform buffer is uploaded once and selected with dynamic offsets; no per-frame GPU
allocation was added. Color/AO/UV representation is retained from P0.5 to preserve the best image.

The P1 source audit has reached the explicit precision stop condition in the new plan section:
`Every P1 coordinate ... must be exactly representable at 1/16; anything else fails explicitly.`
`If some P1 geometry genuinely needs finer than 1/16, stop and report it ...`.
Kotlin window/lantern vertices and continuously jittered grass vertices do not satisfy this constraint.
The port cannot simultaneously preserve those exact source positions and store them on a1/16 grid.
An explicit geometry-authoring/quantization decision is required before those P1 meshes can be added.
The runtime remains the validated P0 terrain scene with the owner's new defaults while that decision is open.

The audit reproduces196 grass tufts and1568 emitted tuft vertices; **all1568** have an off-grid
coordinate. It exits successfully only when the expected conflict is reproduced. Exact fixtures and
packer errors are in `evidence/bg-port/p1/precision-audit.json` (`examples/p1_precision_audit.rs`):

| Kotlin source | Example world coordinate | Value ×16 | Strict result |
|---|---:|---:|---|
| `BackgroundVillage.kt:108`, front window |Z=−70.94000244|−1135.040039|Rejected|
| `BackgroundVillage.kt:105`, lantern |X=40.15000153|642.400024|Rejected|
| `BackgroundVegetation.kt:222–225,257–274`, grass |X=−2.83891249|−45.422600|Rejected|

These are original source coordinates, not a failure to encode valid1/16 fractions. The encoder tests
cover exact fractional coordinates, both signed range limits, origins far from zero, splitting
spatially separated quads, all408520 P0 vertices and reconstruction of all612780 original indices.
No P1 geometry was partially uploaded or silently coarsened. Tree placement, textures, shadow baking
and other P1 runtime passes are intentionally not implemented past this explicit entry stop.

A concrete follow-up, requiring the owner's coordinate-authoring decision, is to quantize the emitted
P1 geometry explicitly to `round(position *16)/16` in its generator while retaining the strict packer.
The maximum change would be1/32 block per axis; e.g. a0.06 window outset becomes0.0625. Keep terrain,
colors, UVs and procedural identity/placement decisions unchanged, check for degenerate quads, assert
original tree/vertex/index counts and every resulting position round-trip, then complete the P1
visual gate. This would be an explicit modification of the original grass/lantern/window positions,
not an unannounced packing-unit change. It has **not** been applied.

### New-default baseline against retained best (2026-10-31)

Same account via child environment,100 hosts/60 modpacks loaded in all3 runs (the previous day had59
modpacks), about30 visible tiles,2560×1440 at scale2, with the same probe/unfocused policy and10s
loader delay as P0.5. `best` is the preserved **old P0.5 executable**, explicitly passed samples1,
packed and lazy flags. `live` is the **new normal default**, with lazy/chunked flags absent. Static
uses the new executable with `--no-scene`. No P1 content has entered these measurements.
All values are MiB. Diagnostic phase holds intentionally inflate time to live output; they are not a
normal-startup performance measurement.

| Case/phase | Working set | Private | GPU dedicated/shared | GPU total | Allocated/reserved |
|---|---:|---:|---:|---:|---:|
|static/loaded|197.27|261.25|81.54/128.30|209.84|61.19/87.00|
|static/foreground60s|197.06|260.90|81.54/128.30|209.84|61.19/87.00|
|static/minimized30s|200.30|228.15|45.35/82.62|127.96|18.38/52.00|
|static/restored10s|265.42|325.62|77.90/194.43|272.33|61.19/148.00|
|best/loaded|282.68|331.29|66.39/202.49|268.88|83.81/135.00|
|best/foreground60s|301.97|342.62|67.05/212.68|279.73|83.81/135.00|
|best/minimized30s|301.98|322.62|46.79/164.87|211.65|44.50/116.00|
|best/restored10s|317.97|340.62|46.97/212.68|259.65|83.81/132.00|
|live/loaded|305.30|349.37|66.71/207.55|274.27|83.88/135.00|
|live/foreground60s|314.87|356.07|67.05/212.68|279.73|83.88/135.00|
|live/minimized30s|314.87|336.03|46.79/164.87|211.65|44.25/116.00|
|live/restored10s|330.96|354.44|47.04/228.68|275.71|83.88/132.00|

The new default's60-second GPU counter matches its paired best at279.73MiB. Its tracked allocation is
64KiB higher (83.875 vs83.8125MiB), the native allocation for the persistent per-batch origin buffer.
Working set/private were314.87/356.07MiB versus301.97/342.62MiB in the paired best. This roughly13MiB
process difference is an observation, not attributed solely to64KiB of origins; no native-heap
investigation was expanded into P1. These are the baseline values for subsequent phases, replacing
4x/52B and historical working-set comparisons. Reserve growth after restore remains recorded for
P5/section6 only.

At0/15/45 seconds the new default is **pixel-identical** to the saved P0.5 best and to the new build
with the old best flags: decoded RGBA equality, maximum RGB error0. Evidence: `pixel-parity.json` and
`compare-decision-best-*.png`. The new default paced preview averaged2.662ms/max4.486ms; the lazy best
flags run averaged2.326ms/max4.559ms (CPU encode + completion, no glass/readback/presentation).
Diagnostic4x and4x/full-vertex previews both succeeded without adapter-specific texture features.

Windows verification:28 tests passed, clippy all targets clean, release binaries/examples built.
`p1_precision_audit` also exited0, confirming196 tufts/1568 off-grid vertices and the source fixtures;
this is a verified contract conflict, not a successful full P1 renderer. Source changes remain within
`client/slint-proto`; the plan's section5 estimates were updated to the selected~77.9MiB P3 payload
projection. **P1 runtime implementation is paused at the owner's explicit coordinate stop rule.**

## P1 full static scene — 2026-10-31 visual gate

The owner approved quantizing **only emitted vertex positions** with `round(p*16)/16`, keeping the
strict local fixed-point codec and1/16 unit. P1 is implemented under that approval: near/far/distant
oak/birch/spruce forest and hidden faces, four houses with exposed-face subtraction and source AO,
summit grass/boulders, block textures/mips/anisotropy, static shadows, grass sway, sky/sun/atmosphere
and the existing tonemap. Rendering remains single-sample by default;4x and full vertices remain diagnostic.
No Kotlin/Compose source changed, and P2 water/clouds, P3 volumetric fog/bloom and P4 players are not added.

Review artifacts:

- `evidence/bg-port/p1/full/p1-compare-{0.0,15.0,45.0}.png`: new Kotlin full-valley references beside P1.
- `evidence/bg-port/p1/full/measure/live-glass.png`: authenticated in-app scene with the live glass.
- `preview/shadow-depth.png`: native depth capture from the one-time static bake (preview only).
- `mesh-report.json`: exact counts, codec checks, offsets and displacement; source audit example remains
  available but its earlier stop has been resolved by the explicit owner approval.

### Geometry/attribute contract and checks

Generation uses the original positions to make every placement, species/thinning choice, hidden-face
choice, exposed-face cut, UV, color, tint, AO and kind decision. No seed or placement parameter is
rounded. `Builder` changes only `Vertex.position` immediately before storage. The full raw/quantized
scene test compares every other byte of all vertices and the entire original index array.
The packed codec still rejects any off-grid emitted coordinate; all714380 vertices and1071570 indices
round-trip/reconstruct exactly through the24B/u16 batches.

| Count | Rust P1 | Kotlin reference |
|---|---:|---:|
| Trees /detailed trees /grass tufts |13173 /491 /196|13173 /491 /196|
| Terrain cells /greedy tops /AO tops /sides |81217 /4994 /36224 /60912|same|
| Vertices /indices |714380 /1071570|714386 /1071576 including the6/6 expanded water quad|
| Static indexed quads |178595|178595 excluding water|
| u16/local-origin batches |24|Rust codec partition only|

The exact water-adjusted assertions and900k vertex budget run during scene build, not just in tests.
Near-to-far ordering inside vegetation material buckets and vegetation-first material drawing match
Kotlin. Static shadows draw the **same uploaded quantized GpuMesh vertex/index buffers** as the main
pass, with the original non-static grass omission; no alternate unquantized shadow mesh exists.

Safety checks are part of generation and propagate a logged `Result` failure to the static fallback:

- Compound house boundary planes keep strict order after quantization. This preserves every non-zero
  inset/outset, in either direction. All four front window offsets remain positive:0.06→0.0625.
- Every source box and tuft bounding extent is positive after rounding, including lantern dimensions.
- Both triangles of every emitted quad keep non-zero area and their original winding; flips are rejected.
- Negative tests reject thin-part collapse, collapsed/reversed offsets and zero-area faces; a complete
  scene test validates all real geometry and verifies that attributes/indices were not rewritten.

Grass source sizes, centers, colors, UV insets, kind values, time wrapping, frequencies2/3 and sway
amplitudes0.07/0.05 are unchanged. The existing position-dependent phase formula is unchanged; its
input naturally follows the approved base-position deviation. No additional phase parameter is rounded.
Color/AO/UV retain the previously selected packed representation. Only position displacement is the
new approved generator deviation.

### Intentional displacement from Kotlin coordinates

| Metric, blocks | Measured |
|---|---:|
| Changed vertices /all vertices |1904 /714380|
| Maximum absolute X/Y/Z displacement |0.03118089 /0.03105164 /0.03106546|
| Mean absolute X/Y/Z displacement, all vertices |0.00003612 /0.00001798 /0.00003625|
| Maximum Euclidean displacement |0.05091291|
| Mean Euclidean displacement, all vertices |0.00006306|
| Mean Euclidean displacement, moved vertices |0.02366147|

Every per-axis displacement is below the approved1/32=0.03125 bound. The Euclidean maximum can exceed
1/32 because all three axes can move; the contractual bound is per axis. These measurements intentionally
include the many unchanged integer terrain/tree vertices; the moved-only mean is included to avoid
hiding grass/lantern movement in the whole-scene average. This is not bit-for-bit Kotlin geometry.

### GPU path and validation

Source PNGs are referenced with `include_bytes!`, not copied. Textures use Rgba8UnormSrgb; CPU mipmaps
average linear light/alpha and cover odd-sized lantern levels. The shared linear/trilinear sampler
uses anisotropy8 and the scene samples with explicit gradients from continuous source UVs. Leaves/grass
use cutout double-sided pipelines. The2048² D32 bake uses back-face culling and reference polygon bias;
main materials use four comparison taps, world-unit bias and the wgpu0..1/top-left depth/UV conversion.
Reference face shading, AO, translucency, emissive windows/lanterns and analytic atmosphere are retained.
The source sun remains the only light direction and supplies the camera-relative billboard.

Validation on Windows/MSVC Rust1.97.1, DX12/Radeon780M:

- 36 tests passed (21 scene +15 existing), zero failures/ignored; all-target clippy with `-D warnings`
  clean; release app/examples built. Exact logs/exit files are in `evidence/bg-port/p1/full/`.
- Default captures at0/15/45 and the Kotlin valley preview succeeded; code inspection and image review
  confirmed the expected scene composition and clear sky.4x and52B diagnostic previews also succeeded.
- Headless180-frame paced work averaged4.334ms/max6.961ms, cadence60.26FPS. This is CPU encode +GPU
  completion, excluding glass, readback and presentation.4x diagnostic5.696/8.933ms;52B4.841/7.683ms.
- The shadow depth capture contains coverage0.00021558..0.9999979, close to the reference's range.
  The bake shares the render mesh by construction; a depth readback exists only in the development preview.

The references contain water, clouds, fog/bloom and players that are deliberately absent at P1. Visual
review should assess geometry/materials, lighting, shadows, grass motion and sky at this gate; it does
not establish full P3/P4 scene parity. The original AA-off edge change and the measured coordinate
quantization are approved deviations.

### Memory/startup against the selected baseline

The controlled runs use the same supplied account via child environment,100 hosts/60 modpacks,
`--show-others --all-playable`, about30 visible tiles,2560×1440 at scale2. `baseline` is the saved
**pre-content MSAA-off/local-packed default executable**, not the rejected4x/52B baseline. All cases
use the prior diagnostic/unfocused policy, including stage holds, and collect loaded/60s/minimized30s/
restored10s endpoints. The normal focus policy is unchanged. Counters are single-run observations;
process counters and GPU counters are not independent RAM quantities to add together.

| Case/phase | Working set | Private | GPU dedicated/shared | GPU total | Allocated/reserved |
|---|---:|---:|---:|---:|---:|
|static/loaded|197.45|264.22|81.54/128.30|209.84|61.19/87.00|
|static/foreground60s|197.24|263.86|81.54/128.30|209.84|60.88/87.00|
|static/minimized30s|200.61|227.28|45.35/82.62|127.96|18.38/52.00|
|static/restored10s|281.82|344.72|77.96/210.43|288.39|61.19/164.00|
|baseline/loaded|286.38|330.31|66.52/204.49|271.01|83.88/135.00|
|baseline/foreground60s|302.18|340.12|67.05/212.68|279.73|83.88/135.00|
|baseline/minimized30s|302.19|319.78|46.79/164.87|211.65|44.56/116.00|
|baseline/restored10s|318.37|338.54|47.04/228.68|275.71|83.88/132.00|
|live/loaded|378.04|433.68|66.93/279.13|346.07|111.69/207.00|
|live/foreground60s|392.42|432.77|67.40/286.26|353.66|112.00/207.00|
|live/minimized30s|392.43|412.74|47.14/238.45|285.58|72.69/188.00|
|live/restored10s|392.42|412.71|47.32/286.26|333.58|112.00/188.00|

At60s, P1 adds90.24MiB working set,92.65MiB private and73.93MiB total GPU over its paired baseline.
Tracked allocation grows about28.13MiB; tracked capacity grows72MiB. The mesh is18.395MiB packed,
about7.876MiB beyond the terrain-only packed mesh; a16MiB shadow map and material/batch resources are
new. Native layouts and capacity granularity account for additional reservation. This report records
the increase rather than claiming a memory win or starting an unapproved heap optimization.

The static first frame was1.166s in this run (static control1.200s; see raw logs). Pipeline setup stays
on the worker after that frame. The roughly30s diagnostic first-live timestamp includes an intentional
10s delay and6×3s stage holds and must not be called normal startup latency. Per-pipeline compile times
are logged individually: final preview solid53.61ms, double-sided1.49ms (warm native cache), shadow12.74ms,
sky21.76ms, sun17.78ms, post13.15ms; the first cold solid compilation was210.77ms. No promise is made about
normal first-live latency from these held diagnostic runs.

All3 application cases exited normally, have full-window screenshots and no error logs. Minimize/resume activity transitions were observed; the retained-frame/clock policy remains implemented
and unit-tested. No performance fallback occurred. Heap capacity growth
after restore remains an open P5/section6 item as requested and was not investigated further here.

**P1 is complete to its visual review gate. Owner visual acceptance is pending; P2 has not started.**

## P1 visual attribution and corrections — 2026-10-31

The owner accepted geometry/counts/quantization/memory and requested attribution of the dark terrain,
grass-top noise and dark grass tufts before P2. Changes remain inside `client/slint-proto`.
Kotlin/Compose/Gradle reference sources are unchanged. P2 is still unimplemented; **this is another
P1 visual review gate, not a claim of owner visual acceptance.**

Review `evidence/bg-port/p1/visual/near-hill-before-after.png` (full Kotlin / old Rust / corrected Rust),
`near-hill-matched-post.png` (Kotlin with bloom/fog and MSAA disabled / old Rust / corrected Rust),
and `grass-detail-before-after.png` (3x nearest enlargement). All use identical pixel coordinates,
without exposure/color adjustments. `p1-fixed-compare-{0.0,15.0,45.0}.png` contains full-frame pairs
against the retained, original Kotlin preview images.

### Method and brightness attribution

`tools/P1VisualProbe.java` loads the already-built Kotlin classes and original shader strings, creates
the reference GL context through `GlfwRuntime`, and reads the actual pre-post HDR textures and material
mips. Diagnostic shader/program changes occur only inside that disposable process. It renders the
same seed123/camera/time0 without actors, avoiding player coverage in the hill samples. It captures
the original four-sample scene and a single-sample control using the same framebuffer objects.
It separately disables bloom, BSL color processing, saturation alone, vibrance alone, dark
desaturation, and fog composition. Rust's preview captures its RGBA16F HDR separately from final PNGs;
these readbacks are absent from the application.

The explicit hill rectangle is **x400..879, y410..719** in each1280x720 source, with a top-left
origin. Its bounds are printed on the crop. It is a fixed rectangle including plants, cliffs and
some background, rather than an undocumented selection chosen to reproduce the owner's rounded
90/107/52 number. Display luminance below means `0.299R + 0.587G + 0.114B` on0..255 final values;
HDR values below are linear, not display values.

| Same hill rectangle | Mean display RGB |
|---|---:|
| Kotlin full scene/post |88.491 /107.765 /60.700|
| Kotlin P1 scene, no bloom/fog,4xMSAA |89.765 /108.850 /60.252|
| Kotlin P1 scene, no bloom/fog,1xMSAA |89.672 /108.719 /60.262|
| Rust before |74.709 /90.056 /52.089|
| Rust grayscale correction only |88.198 /107.029 /59.772|
| Rust final |89.646 /108.693 /60.498|

The original gap is15.498 display-luminance units,16.04% below the full Kotlin reference in this
rectangle. This ordered decomposition closes exactly; signed percentages refer to that original gap,
so corrections may exceed100% when a later reference effect offsets them:

| Component | Luminance units | Share of original gap |
|---|---:|---:|
| P1 grayscale PNG decoder correction |+14.872|+95.96%|
| P1 sampler/cutout correction after decoder correction |+1.493|+9.63%|
| Remaining matched P1 mean difference |−0.004|−0.03%|
| Accepted4x-vs-off MSAA difference |+0.104|+0.67%|
| P2 water in the background of the rectangle |−0.283|−1.83%|
| P3 fog composition/HDR copy |+0.992|+6.40%|
| P3 bloom |−1.676|−10.81%|

The **net deferred P3 contribution is−0.684 units,−4.41% of the original gap** in this region.
Bloom makes this particular region1.70% darker than its no-bloom control; it does not explain Rust's
original darkness. The fog control substitutes the full scene's raw HDR texture for its fog-composited
input while keeping the same final shader and disabling bloom on both sides. The water term compares
that no-fog/no-bloom full scene against the pre-water scene; it is not counted as P3 fog.

Independent final-post ablations, with all other full-reference stages retained:

| Enabled reference operation versus its neutral control | Change in hill luminance |
|---|---:|
| Effective saturation1.04+0.07 versus1.0 |+1.147%|
| Vibrance1.12 versus1.0 |+0.559%|
| Complete BSL saturation/vibrance block versus bypass |+1.660%|
| Dark desaturation0.25 versus0 |−0.019%|

These are separate counterfactuals, not additive shares. **All three color operations were already
present in Rust `post.wgsl` and remain unchanged; their missing-operation share is0.** Only fog and
bloom remain deferred to P3. No exposure, lighting multiplier or tint compensation was introduced.

### P1 fixes and HDR evidence

`grass_block_top.png` and `stone.png` are grayscale PNGs. Java ImageIO decodes them to
`TYPE_BYTE_GRAY`; the reference's `getRGB()` converts their linear CS_GRAY samples to display sRGB
before GL receives the bytes. Rust's image decoder had expanded the grayscale samples unchanged.
For grass top, the original grayscale mean147.434 becomes199.723 in the actual Kotlin GPU level0.
The corrected Rust decode matches those grayscale upload bytes. Palette/RGB/RGBA textures, including
short grass and its alpha, retain their original bytes. This is a decoder-compatibility conversion
for grayscale inputs, not a second sRGB conversion applied to every texture. GPU color textures
remain `Rgba8UnormSrgb`, authored tint conversion remains unchanged, and final post still performs
the only output linear-to-sRGB conversion.

Actual pre-post samples from the matched single-sample control:

| Pixel x,y | Kotlin linear HDR RGB | Rust before | Rust final |
|---|---|---|---|
|650,455|0.18945 /0.29297 /0.05859|0.10175 /0.15784 /0.03268|0.19067 /0.29590 /0.05939|
|600,617|0.08691 /0.17188 /0.04590|0.04611 /0.09088 /0.02551|0.08704 /0.17224 /0.04666|
|640,617|0.18750 /0.29297 /0.05957|0.09424 /0.14807 /0.03110|0.18848 /0.29639 /0.06033|
|500,565|0.05127 /0.10840 /0.03223|0.02872 /0.06036 /0.01888|0.05151 /0.10907 /0.03235|

Material probes confirm the same upward normal, face-shading and ambient/direct constants. For
600,617, the AO/shadow values are0.875/0.103516 in both renderers; for640,617 the comparison-shadow
result is0.84375 Kotlin versus0.84961 Rust. Kotlin's R11G11B10F probe quantization and Rust's RGBA16F
probe quantization differ. No unsupported lighting/AO/shadow-strength adjustment was made.

### Gradients, mips, noise and grass tufts

Both shaders use derivatives of continuous UVs before `fract`, multiplied by `(1, textureVScale)`.
The lantern's1/3 vertical scale remains intact. At600,617, the measured UV derivative lengths are
Kotlin0.023682/0.026611 versus Rust0.023819/0.026749; at650,455 they are0.031738/0.063477 versus
0.032318/0.063965. There is no missing gradientScale or whole-level mip shift in these samples.
The GL texture query reports trilinear minification, **nearest magnification**, Repeat/Repeat,
anisotropy8. Rust previously used linear magnification and ClampToEdge.

Corrected samplers use Repeat for block textures. Shadow cutouts sample level0 with nearest filtering;
the sun has its own nearest/clamp sampler. Minified color footprints retain native8x anisotropy and
`textureSampleGrad`. WebGPU requires linear filters on its native anisotropic sampler, so magnified
footprints use up to8 nearest level0 taps along the major gradient, with footprint/LOD selection from
the [EXT_texture_filter_anisotropic reference model](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_texture_filter_anisotropic.txt).
This retains nearest magnification without disabling anisotropy globally or introducing an arbitrary
LOD bias. The extension explicitly permits implementation-specific filter schemes; this port does
not claim bit-identical native AMD GL and DX12 anisotropic sampling.

The mip audit compares actual Kotlin GL level0..4 readback against an independent reconstruction of
Rust's linear-light CPU mip generation. Level0 grayscale/palette bytes match exactly; the largest
difference at any later level is1/255, including alpha. Grass top's final1x1 mip is201 Kotlin versus
200 Rust; short-grass alpha is139 versus140. There is no gamma-space averaging or material-wide mip
brightness loss to fix. Raw readbacks and per-level comparisons are retained.

Noise was measured on**31,032 common sunlit grass interior pixels**, excluding tuft/leaf coverage,
shadow boundaries and face seams with a3x3 mask. The metric is four-neighbor Laplacian RMS divided
by mean display luminance, rather than absolute contrast biased by the original darkness:

| Texture metric | Kotlin no-bloom/fog, AA off | Rust before | Rust final |
|---|---:|---:|---:|
| Normalized high-frequency RMS |0.13334|0.16466|0.12236|

The original23.5% excess is removed; final is8.2% smoother by this metric. This is a measured filter
difference, not a claim of exact per-pixel texture equality. Inspect the enlarged grass crop as well.

Both renderers use the same grass tint/upward normal, alpha cutoff0.1, opaque cutout output, and
`pow(max(dot(view,toSun),0),3)*0.45` transmission. The reference does **not** alpha-blend grass over
the background. Linear magnification had mixed transparent black into RGB before forcing the output
opaque, accounting for the dark/thick appearance; the nearest-footprint correction addresses that
without inventing alpha blending or a yellow tint.

Across20,183 pixels classified as grass tufts in both hill controls, transmission is0.006411 Kotlin
versus0.006443 Rust. Their mean display luminance improves from80.134 to84.427 against85.466 in the
matched Kotlin control: residual−1.22%. The tighter summit tuft intersection has2,114 pixels and
residual−2.07%. Hill tuft coverage is21,850 Kotlin versus22,470 Rust pixels; mean sampled alpha is
0.85675 versus0.83235. These remaining cutout/coverage/filter differences are **P1 residuals, not
deferred P3 work**. The approved position quantization and AA mode remain intact; the measurements
do not uniquely attribute every changed edge pixel to a particular driver or quantized vertex.
The whole hill's matched RGB mean differs by−0.03/−0.03/+0.24; mean agreement does not erase those
local residuals.

### Validation and reproduction

- Windows MSVC `cargo test`:37 passed (22 library/scene +15 application),0 failed.
- `cargo clippy --all-targets -- -D warnings` and release app/examples build passed.
- Final release previews at0/15/45s passed, as did4xMSAA and full52B vertex diagnostics.
- The pre-fix HDR diagnostic's PNG differs from the retained original P1 release preview by at
  most1/255 per RGB channel; attribution uses that explicitly identified diagnostic control.
- Final paced preview4.792ms mean /8.181ms max,60.23FPS cadence; includes CPU encode/completion,
  excludes readback/glass/presentation. This is not a matched performance A/B against the older run.
- `live-glass.png` verifies final in-app background and glass. Credentials were absent in this session,
  so it shows the existing retryable login error; authenticated tiles/API were not revalidated.
- No new frame targets, mesh copies, per-frame resource allocations or application readbacks were
  added. New sampler objects and preview-only diagnostics are separate from the accepted memory
  evidence; the full memory measurement was not repeated.

Use `tools/p1-visual-reference.ps1` via the Windows IntelliJ terminal to build the classpath and run
the Java reference probe, then `tools/p1-visual-preview.ps1` for final release captures and
`python tools/p1-visual-summary.py` for crops/data. The init task requires `--no-configuration-cache`
because it accesses Gradle's project model; the initial cache serialization failure was corrected
through the same Gradle entry point. `visual-summary.json` contains raw attribution, ablations,
HDR/gradient samples, mip comparisons, noise and tuft masks/statistics. Before/albedo-only captures
and the original preview executable are retained in the same evidence directory.

**Stopped at the P1 visual gate. No P2 implementation has started.**

## P2 water/clouds — implementation and visual gate evidence,2026-10-31

The owner accepted P1's grayscale decode, Repeat/nearest sampling and remaining tuft residuals
(−1.22% hill /−2.07% summit) as-is, then authorized P2. Those accepted choices are retained.
**The owner accepted P2 visuals and the sampler A/B, including the left-lake difference as deferred
P3 fog. The required authenticated controlled memory/API/glass run is now complete. Stopped at the
P2 gate; P3 has not started.**

All implementation changes remain in `client/slint-proto`. Kotlin/Compose/Gradle reference sources
are unchanged. `p2/controls/p1-accepted.exe` and `p1-accepted-preview.exe` preserve the accepted P1
programs before P2. Historical P1 measurements above remain historical; none is presented as a new
authenticated P2 baseline.

### Result and implementation

`evidence/bg-port/p2/p2-compare-{0.0,15.0,45.0}.png` pairs the final Rust frames with a fresh normal
Kotlin preview, including the reference's P3 fog/bloom and P4 actors. The P2-only comparisons are
`p2-matched-*.png`, with Kotlin's fog/bloom disabled, no actors and true nonmultisampled attachments.
`p2-water-crop-*.png` uses the same x500..1059/y360..464 region, enlarged2x without color changes;
`p2-cloud-crop-0.0.png` compares x180..1019/y0..169. Source frames remain1280x720.

The frame now performs the accepted P1 scene plus clouds, pre-water color/depth capture,
640x360 planar reflection plus clouds, reflection mip generation, water, then existing P1 post.
The new water shader retains the reference four normal layers, wrapped animation/scroll clocks,
depth-rejected refraction, Beer-Lambert absorption, shoreline foam, Schlick reflection and capped
GGX sun highlight. Screen/depth/reflection UVs account for D3D's top-left origin and0..1 depth;
world-space normal/noise UVs are not flipped. Reflection uses the source mirrored eye/target and
downward up vector, clips terrain below water+0.01, and splits the atmospheric path at water entry.
The source's inexpensive constant-density reflected fog stand-in is included in that path; the
main-view noise volume, march/composite and bloom remain P3 work.

Water is a six-vertex procedural quad with the source corner order, exact WATER_LEVEL2.35 and
vertex waves. It is not inserted into the packed static mesh or silently rounded to1/16. The
accepted static714,380 vertices/1,071,570 indices remain unchanged; adding the six procedural
water vertices gives Kotlin's714,386 logical scene vertices. Cloud geometry is separate, as in
Kotlin. The shadow bake still uses the same accepted static mesh and omits water/grass sway.

Clouds retain12-block horizontal cells,4-block layers,0.6*time drift,2048 wrap, camera-relative
offsets, height220 and the exact fancy face/slice geometry. Thinning preserves entire4-connected
cloud components across the texture's tiling edges, using keep0.45 and seed0xC10D. The cloud texture
uses nearest filtering without mips. Both main and reflected scenes use a depth-only prepass,
then alpha-blended color/depth, culling disabled and horizontal fade360..560.

`water.rs` owns persistent capture/reflection resources, input groups and mip passes; `clouds.rs`
owns a fixed-capacity vertex buffer and only uploads a new mesh when the source floor coordinates
change. All targets, mip views/groups and pipeline objects are created on initialization or the
existing optional first-frame lazy path. There is no application readback or per-frame target/view
creation. With default AA off, pre-water depth is copied directly;4xMSAA remains diagnostic and
captures depth sample0 through a depth-writing pass. Reflection stores levels0..4, sufficient for
the shader's maximum3.5 LOD. Water normal/noise maps are linear data with linear-channel mips;
color textures retain accepted sRGB handling. The additional frame parameters share one fixed
buffer with512-byte main/reflection slots.

### Reference and numerical checks

The normal `:bg-renderer:renderPreview` passed through the Windows IntelliJ terminal.
`P2ReferenceProbe.java`, compiled with the existing P1 probe helpers, runs the compiled reference
classes and shader strings in a disposable GL context through `GlfwRuntime`. It captures raw scene
depth, reflection/main HDR, cloud VBO bytes and thinned texture bytes, then renders the P2-only post
control. It does not edit the reference's sources. Controls query/use nonmultisampled renderbuffer
storage rather than relying on a possibly rounded multisample request.

| Elapsed time | Cloud vertices | Lake MAE RGB/255 | Sky/cloud region MAE RGB/255 | Pre-water depth p95 absolute error | P1 retained-path max RGB change/255 |
|---|---:|---:|---:|---:|---:|
|0s|19,734|0.780|0.409|4.77e-7|1|
|15s|19,734|0.668|0.411|2.98e-7|1|
|45s|19,734|0.958|0.399|2.98e-7|0|

Lake metrics use x700..899/y395..424; sky/cloud metrics use x200..999/y10..119. They are fixed
regions, not a claim that every pixel in the lake rectangle is water. Cloud positions, UVs, colors,
normals and vertex order match Kotlin **byte-for-byte at all three times**; the uploaded thinned
cloud texture also matches exactly. The captured cloud count equals the CPU assertion19,734.
Main pre-water depth is finite in0..1, with median absolute depth error5.96e-8 at all three times.
Main and reflection HDR are finite; no NaN or infinity appeared. Correct-orientation reflection
linear-RGB MAE is0.00257..0.00258, versus0.321..0.328 after a vertical flip or0.167..0.177 after a
horizontal flip. This separately checks the reflection readback/UV orientation.

The retained P1-only path uses `--scene-p1-only`; it allocates no P2 targets/clouds. Comparison with
the accepted P1 release PNGs bounds changes to at most1/255, and45s is exact. The accepted tuft
residuals were not retuned. Main-view P3 differences remain visible in the full reference pairs;
the matched P2 controls prevent mist/bloom from being mistaken for water/cloud faults.

### Matched sampler timing, requested carry note1

The earlier4.33→4.79ms observations were not matched runs. A new comparison holds the binary,
grayscale decoding, Repeat addressing, shadow cutouts, scene, resolution, AA/vertex settings and
animation sequence constant. `--scene-legacy-sampler` switches the color shader to native all-linear
sampling using a compile-time constant; default keeps the accepted up-to8-tap nearest-magnification
path. No sampler optimization was performed.

For each P1-only/P2 content case, four independent processes per condition were run in order
linear/nearest/nearest/linear/nearest/linear/linear/nearest. Each process warms30 frames, then
measures180 paced frames at the same deterministic animation times. These are CPU encode plus
submit-to-completion durations, **not GPU timestamps**; they exclude PNG readback, glass and
presentation. `timing.json` retains all individual samples and per-run aggregates.

| Content | Native-linear mean of4 run means | Accepted-nearest mean of4 run means | Difference |
|---|---:|---:|---:|
|P1 only|4.054ms|4.289ms|+0.235ms /+5.80%|
|P2 water/clouds|5.753ms|6.013ms|+0.260ms /+4.52%|

P1 run means range4.007..4.096ms linear versus4.264..4.309ms nearest; P2 ranges5.700..5.813ms
versus5.969..6.069ms. Cadence remains about60.2FPS. The clean batch is
`evidence/bg-port/p2/sampler-ab/clean`; an initial calibration batch overlapped reference capture
and is excluded entirely. No other renderer job was launched during the clean batch. These results
measure the sampler cost under the current matched conditions; they do not retroactively explain
every part of the old unmatched0.46ms difference. The carry note's A/B requirement is now recorded;
the accepted sampler remains the default.

The separate final release preview measured5.946ms mean /8.158ms max,60.13FPS cadence. It is a
single validation run, not another condition in the A/B table.

### Authenticated memory/API baseline, required carry note2 — completed2026-10-31

The owner supplied the test account and authorized `tools/p2-measure-all.ps1`. It completed the
three controlled cases sequentially: current static `--no-scene`, saved accepted P1 executable,
then current P2. Each used the same account, `--show-others --all-playable`,2560x1440 physical
window/scale2, and previous controlled probe policy. All visible samples were actually unfocused;
`--scene-probe-unfocused` kept scene rendering active. This is the accepted diagnostic condition,
not a claim of operating-system foreground focus. It measured60s visible,30s minimized and10s
restored after successful authenticated readiness, with actual sample timings retained in JSONL.

WSL→Windows direct process creation remained unavailable. A tiny Java premain launcher used the
IDE's one-time, nonpersistent environment overrides to spawn only the requested PowerShell script,
then exited before the configured server main could execute. Credentials were inherited through
process environments; neither credential appeared in source/evidence logs or command arguments.
No Windows user/system environment was changed. All measured processes closed normally; none
remained afterward. The helper is `tools/P2MeasureLauncher.java`; it contains no account values.

All3 cases loaded**100 hosts and60 modpacks**. Login/JWT, account info, own/public host lists,
modpack search, online-player polling and visible owner/player info calls returned HTTP200.
There were38/39/39 logged API responses for static/P1/P2, respectively, all200; error and warning
logs were empty. Account/owner avatars and populated room cards/icons appeared in all screenshots.
`measure/p2-glass.png` was inspected alongside `p1-glass.png` and `static-glass.png`: the same cards
and avatars rendered over their respective backgrounds, with glass borders, translucency, backdrop
blur, navigation pill and bottom controls intact. The P2 screenshot includes live clouds through
the glass. No missing-credentials screen, image fallback warnings or scene/performance fallback
occurred. This verifies the rendered room cards and API-loaded modpack dataset; it is not a fresh
manual sweep of every tile callback or title-bar interaction.

All values below are MiB. Working set/private and Windows GPU dedicated/shared counters are separate
accounting models; they must not be added together. Allocated/reserved are wgpu allocator snapshots
at or immediately before each endpoint; short-lived staging may vary between such snapshots.

| Case / phase | Working set | Private | GPU dedicated / shared | GPU total | wgpu allocated / reserved |
|---|---:|---:|---:|---:|---:|
|Static / visible60s|198.23|261.79|81.54 /128.30|209.84|61.188 /87.000|
|Accepted P1 / visible60s|388.90|429.59|67.61 /289.32|356.93|112.000 /207.000|
|P2 / visible60s|417.59|453.83|67.74 /291.38|359.12|129.438 /207.000|
|Static / minimized30s|200.75|228.43|45.35 /82.62|127.96|18.688 /52.000|
|Accepted P1 / minimized30s|388.91|407.81|47.34 /241.51|288.85|72.688 /188.000|
|P2 / minimized30s|417.60|433.80|47.47 /243.57|291.04|90.062 /188.000|
|Static / restored10s|265.64|325.50|77.90 /194.43|272.33|61.188 /148.000|
|Accepted P1 / restored10s|389.03|407.91|47.53 /289.32|336.85|112.000 /188.000|
|P2 / restored10s|417.59|433.77|47.66 /291.38|339.04|129.125 /188.000|

| P2 minus paired accepted P1 | Working set | Private | GPU total | wgpu allocated | wgpu reserved |
|---|---:|---:|---:|---:|---:|
|Visible60s|+28.70|+24.24|+2.20|+17.438|0|
|Minimized30s|+28.69|+25.99|+2.20|+17.375|0|
|Restored10s|+28.56|+25.86|+2.20|+17.125|0|

The new P2 payload is15.384MiB before native padding/internal sampler buffers: pre-water HDR7.031,
pre-water D323.516, reflection HDR/mips2.341, reflection D320.879, cloud vertices0.950,
cloud texture0.250 and normal/noise mip chains0.417. At the visible60s allocator snapshot their
native allocations occupy17.000MiB; new internal sampler-index buffers add0.375MiB, and a0.0625MiB
staging difference produces the measured17.438MiB delta. The restored snapshot has transient
allocation differences and a17.125MiB total delta. Both P1 and P2 fit the same207MiB visible and
188MiB restored reserved heaps. Windows GPU usage grows only2.20MiB in this batch; that difference
must not be interpreted as the size of the new water/cloud resources or subtracted from process
private bytes to invent a native residual. No new driver/heap attribution or optimization was done.

Activity logs show rendering parked while minimized and resumed afterward; no pause-spanning
performance fallback occurred. Static restore still grows reserve87→148MiB and private261.79→325.50MiB,
consistent with the already-deferred restore-capacity issue; this is recorded for P5 without
investigating it now. The script's first-live time contains intentional10s delay/checkpoint holds
and is not normal startup latency. This is one controlled sequential batch, not a statistical
memory confidence interval. The accepted P1 binary was rerun in the same batch, so these paired
values supersede comparisons to the older P1 run's392.42/432.77MiB process counters.

Raw per-second rows, endpoint allocator snapshots, API logs, exit codes and screenshots are in
`evidence/bg-port/p2/measure`. `summary.json` and `gate-summary.md` now report authenticated
validation passed and retain all loaded/visible/minimized/restored endpoints and deltas.

### Validation and handoff

Windows MSVC validation passed41 tests (26 library/scene +15 app), clippy all targets with warnings
denied, and release app/examples build. Final0/15/45s,4xMSAA and full52B vertex diagnostic previews
passed. New tests cover cloud coordinates/reflection height/wrap, toroidal component thinning,
fancy vertex layout/count, linear-data mip behavior and expanded WGSL/Rust uniform layouts.
All existing geometry/quantization tests continue to pass. Kotlin renderPreview passed; source
changes remain confined to the prototype and Git operations remained read-only.

Use `tools/p2-reference.ps1`, `p2-preview.ps1`, `p2-sampler-timing.ps1` and `p2-summary.py` to reproduce
visual/raw/timing evidence. `p2-measure-all.ps1` reproduces the authenticated batch when credentials
are supplied in its process environment. The owner accepted P2 visuals and sampler cost; the
authenticated cards/API/glass and paired memory report are now complete. **Stopped at the P2 gate;
nearest magnification remains the default, no sampler optimization, P3 not started.**


## Golden-valley port P3 — valley fog and complete post (2026-10-31)

The approved P3 now runs the full pre-player pipeline: accepted P2 geometry/sky/clouds -> pre-water
HDR/depth capture -> planar reflection and mips -> water/raw HDR ->640×360 valley-fog march ->
bilateral HDR composition -> linear mips0..8 -> bloom atlas -> bloom/tonemap/saturation ->1280×720 RGBA8.
The reflected dense-core fog stand-in was already present in accepted P2 and is preserved.
No player/crowd, sampler optimization, resolution/MSAA/packing change or section6 startup work is included.
All source/tool/documentation changes are confined to this prototype; Kotlin is a read-only reference.

### Implementation and resource lifetime

`scene/fog.rs` mirrors the Kotlin layer/reach/slab/step/wind/noise logic. Four FBM octaves use explicit
wrapping integer multiplication to match Kotlin Int overflow. The64³ R8 3D texture repeats with linear
filtering on all axes. Wrapped wind uses f64 elapsed time, then f32 uniforms. The fixed jitter converts
D3D pixel positions to GL's bottom-left convention; reconstructed D3D depth remains0..1. Marching stops at
the captured pre-water surface or analytic water plane, intersects only the low slab and uses6..24 steps,
HG g0.35, static-shadow visibility, height shade and atmospheric lighting. Fog never reaches the camera.

`scene/post.rs` owns the pipelines/noise/wind and `Targets` owns persistent fog, linear mip chain and
bloom atlas. Target views and bind groups are created once, including lazy initialization if requested.
The joint bilateral composite uses4 low-resolution samples and relative linear-depth weights. Screen UV,
shadow UV and the GL-layout bloom atlas each convert Y explicitly. Every linear HDR mip is generated by
a fullscreen downsample; only the9 levels sampled by bloom are allocated. The sole sRGB conversion remains
in the final tonemap, with plain RGBA8 output for glass. No history/G-buffer/SSR or application readback.
The FBM float scratch/lattice/output bytes are dropped after upload; no decoded image or noise volume is
stored on `Post`. Default block/cloud/water samplers and geometry remain unchanged.

`--scene-p2-only` preserves the accepted P2 path for regression/attribution. An optional, default-off
`allocation-probe` Cargo feature counts live Rust allocation requests in the app; it changes no renderer
policy and is not the delivered default executable. `examples/p3_memory_probe.rs` isolates creation,
rendering and drop without Slint/API for native/Rust attribution. These counters are not an estimate of
Windows heap capacity, GPU resident memory, or process private bytes.

### Matched images and stage evidence

Fresh Windows `:bg-renderer:renderPreview` passed at0/15/45 with seed123 and golden valley selected.
Original reference frames include players/crowd and4xMSAA. `P3ReferenceProbe.java` additionally uses compiled
Kotlin classes/original shaders in a disposable GL process with no player/crowd and AA off to match the
accepted Rust single-sample baseline. It does not edit reference Kotlin. Both the original and matched
comparisons are preserved. Images were visually inspected: blue sky stays clear; the lake/left shore,
valley banks and distant pooled mist now closely follow Kotlin; the near hill keeps accepted texture/tufts.

The262,144-byte fog volume matches Kotlin byte-for-byte:
`106d5748d42c2283f8a3015b426b97107d18c83074478a24c20c525139b1e9e0` (SHA-256).
Raw HDR, fog RGBA, composite linear HDR and atlas captures are finite at all3 times. Fog alpha stays in0..1.
The P2 diagnostic path matches saved accepted P2 captures within1/255 at every pixel (0/15/45).

|Elapsed|Full image RGB MAE /255|Left lake mist MAE before P3 -> after|Lake MAE before -> after|Valley MAE before -> after|
|---|---:|---:|---:|---:|
|0.0s|1.403|12.945 ->1.992|11.634 ->0.993|10.198 ->1.750|
|15.0s|1.388|13.222 ->1.948|12.085 ->0.827|10.166 ->1.649|
|45.0s|1.396|13.034 ->2.142|10.432 ->1.111|10.551 ->1.862|

Residuals are measured, not claimed pixel parity. Active fog RGBA MAE0.0208..0.0230 and transmittance
MAE0.0273..0.0300 remain across depth/jitter/shadow boundaries. Whole-image raw HDR MAE0.00285..0.00288,
composite HDR0.00653..0.00669; atlas MAE~0.000101..0.000102. The known RGBA16F versus reference R11G11B10F
precision, accepted packed/quantized geometry and nearest/cutout residuals still apply; this phase does
not tune colors or hide those errors. Sky/cloud region final MAE1.13..1.14/255 (P2 against full post~3.7).

Review images: `evidence/bg-port/p3/p3-compare-{0.0,15.0,45.0}.png`,
`p3-matched-*.png`, `p3-valley-crop-*.png`, `p3-sky-crop-0.0.png`.
Stage captures and exact metrics are in `p3/reference`, `p3/final` and `p3/summary.json`.
Authenticated app glass is in `p3/measure/{static,p2,p3}-glass.png`; populated cards/icons/heads, selected
pill, glass refraction/frost/rims and bottom controls were visually checked. API/data loading is validated;
this is not an exhaustive test of tile actions or future P4/P5 behavior.

### Performance and startup

Default DX12/Radeon780M paced preview: mean7.628ms, median7.448ms, p958.931ms, max10.481ms, 60.19FPS across180 frames after30 warm-up frames. The metric includes CPU encode plus GPU completion and excludes glass/readback/presentation. Accepted P2's matched sampler study was~6.0ms; this P3 preview is~1.6ms higher, with no matched optimization claim.

|New pipeline|Shader-module + pipeline creation wall time (ms)|
|---|---:|
|scene valley fog march|105.23|
|scene bilateral fog HDR copy|27.91|
|scene bloom atlas|233.59|
|scene bloom tonemap saturation|45.93|
|scene linear HDR downsample|1.54|

These include WGSL translation, DX12 shader compilation and pipeline creation, not an isolated FXC-only
CPU profile. All run on the loader worker. The bloom atlas is the largest new compile cost; section6
optimizations remain deferred. Live app completion windows (same metric, shared queue; excludes
presentation) averaged8.30ms for accepted P2 and9.77ms for P3; largest sampled window frame27.25ms. No terminal fallback occurred.

Main batch first-frame times were static1325ms, P21669ms, P31187ms. P2/P3 first-live times were31068 /30766ms. These runs deliberately include10s delay and3s diagnostic stage holds; they are not normal
startup latency and do not establish a startup improvement. The first static frame still releases the
worker, so new pipelines stay outside its initialization path.

### Controlled authenticated memory batch

`tools/p3-measure-all.ps1` completed sequential static / saved accepted P2 / current P3 with the owner
test account,2560×1440 scale2, `--show-others --all-playable`,60s visible /30s minimized /10s restored.
The accepted probe-unfocused policy was retained; actual focus was false at all main endpoints and actual
minimized/restored state was recorded. All3 cases loaded101 hosts/60 modpacks with all observed API responses
HTTP200. The previous P2 batch had100 hosts; do not compare separate dates as if the dataset were identical.
Credentials traveled only through temporary IDE process environment overrides; the Java premain launches
the measurement script and exits before the configured backend application's main. No account values in
source, evidence logs, persisted environment settings or command arguments. All measured processes exited0.

All numbers below are MiB. Process counters, Windows dedicated/shared GPU counters, wgpu resource allocation
and reserved heap capacity are different accounting models and are not additive.

|Case / phase|Working set|Private|GPU dedicated / shared|GPU total|wgpu allocated / reserved|
|---|---:|---:|---:|---:|---:|
|static /visible60s|214.17|276.22|81.60 /144.30|225.90|61.438 /103.000|
|p2 /visible60s|409.59|448.55|67.64 /297.32|364.96|129.375 /215.000|
|p3 /visible60s|468.91|512.35|68.67 /305.34|374.00|146.625 /207.000|
|static /minimized30s|201.30|227.37|45.34 /82.62|127.96|18.812 /52.000|
|p2 /minimized30s|409.59|427.71|47.37 /249.51|296.88|90.188 /196.000|
|p3 /minimized30s|468.93|492.32|48.40 /257.52|305.93|107.062 /188.000|
|static /restored10s|282.46|344.04|77.96 /210.43|288.39|61.438 /164.000|
|p2 /restored10s|409.64|427.68|47.56 /297.32|344.88|129.375 /196.000|
|p3 /restored10s|468.94|492.29|48.59 /305.34|353.93|146.625 /188.000|

|P3 minus accepted P2|Working set|Private|GPU total|wgpu allocated|wgpu reserved|WS delta minus allocated delta|
|---|---:|---:|---:|---:|---:|---:|
|foreground60s|+59.328|+63.797|+9.047|+17.250|-8.000|+42.078|
|minimized30s|+59.340|+64.605|+9.047|+16.875|-8.000|+42.465|
|restored10s|+59.301|+64.609|+9.047|+17.250|-8.000|+42.051|

The incremental P3 payload is14.898MiB: half-resolution fog1.758, linear HDR mips9.375, RGBA8 atlas3.516,
noise0.250 and32B wind. These add16.500MiB native texture/wind allocation after layout/alignment:
fog1.9375, linear mips10.4375, atlas3.8125, noise0.250, wind0.0625. Persistent internal sampler buffers
add0.6875MiB; the visible staging difference adds0.0625MiB, closing the measured17.250MiB allocated delta.
The handoff's~30MiB remainder included P2 captures/reflection/maps; it was not all incremental P3 payload.

At initialization `targets-before-pipelines`, P2 reserved167MiB and P3 reserved231MiB: **an extra64MiB
block was created**, as anticipated by the gate. This does not become a64MiB Windows GPU endpoint step.
After live glass and transient allocation retirement, visible P2/P3 reserve is215/207MiB (P3 lower by8MiB),
then196/188MiB after minimize/restore. P3's persistent blocks fit the same two64MiB blocks and a smaller
set of buffer heaps. Windows GPU total rises9.047MiB. Exact block lists are in summary.json and allocator
logs; the accepted historical207MiB P2 reserve must not replace the current paired215MiB measurement.
The static restore reserve103→164MiB remains the known P5/section6 behavior, not a P3 optimization target.

### CPU/native residual attribution

The main batch residual42.078MiB is clearly above the carried~11MiB threshold, so extra attribution was
required. Source review confirms no retained mesh/noise/decoded PNG copies in P3. The independent headless
System-allocator counter found P2/P3 live Rust requests2.394/3.056MiB after renderer construction and
2.404/3.074MiB after1800 frames; the delta is only~0.67MiB. Both peak at73.283MiB during the unchanged mesh
build. After renderer drop, live Rust requests are0.340/0.388MiB; the small remaining wgpu/device bookkeeping
is outside the renderer. The native process counters in `p3/cpu` do not grow by42MiB in isolation.

The complete authenticated app was then rerun with the opt-in Rust allocation counter, using the same
tracked executable with `--scene-p2-only` versus default P3. This is an attribution control, not the primary
performance/memory result above. The counter records requests serviced by Rust's System allocator; native
DX12/driver/OS allocations and allocator free capacity are not included.

|Tracked app visible60s|Working set|Private|Rust live requests|Rust peak requests|wgpu allocated / reserved|
|---|---:|---:|---:|---:|---:|
|p2|401.83|441.53|12.891|84.300|129.375 /207.000|
|p3|431.55|505.86|13.537|84.383|146.625 /215.000|

Paired tracked-app delta: WS+29.715MiB, private+64.328MiB, live Rust requests+0.646MiB, allocated+17.250MiB.
The second pair's private increment64.328MiB closely reproduces the primary63.797MiB, while its WS
increment29.715MiB is smaller than the primary59.328MiB. Its WS-minus-allocated delta12.465MiB is near the
carried~11MiB baseline; the larger primary WS residual is not reproduced as stable live data. Both
instrumented visible endpoints were actually focused, unlike the primary unfocused probe endpoints;
this diagnostic is not substituted for the approved main batch. Exact driver/native heap attribution
would require native allocation stacks, not just the available allocation/OS counters.

This rules out a retained CPU mesh, decoded texture or fog volume as the main residual. The remaining
process difference is in native allocations/committed or resident heap capacity, outside Rust live requests
and wgpu placed resources. Its division among FXC/driver command/pipeline objects, Windows allocator free
capacity and OS residency is not established by these counters; it is not labeled a confirmed leak or a
fully explained shader-cache cost. No source data was dropped speculatively because the upload buffers
already have bounded lifetime. This is an explicit attribution limit for the P3 gate.

### Validation and review boundary

Windows tests passed45 (30 library/scene +15 app), no failures/skips; all-target Clippy with warnings denied
passed, including the optional allocation-probe feature. Release app/examples built;0/15/45 previews,
4xMSAA and full52B diagnostic previews passed, plus saved-P2 image regression. New CPU tests mirror Kotlin
layer/reach/slab/step/wind/noise contracts; naga validates every new WGSL and Rust/WGSL Params/Wind layouts.
Fresh Kotlin preview and reference probe passed. No Git write, destructive removal or Kotlin source edit.

Reproduce with `p3-validate.ps1` / `p3-final-validate.ps1`, `p3-preview.ps1`, `p3-reference.ps1`,
`p3-measure-all.ps1` (owner account in process environment), then `python tools/p3-summary.py`.
The optional `p3-cpu-build.ps1` and `p3-cpu-app-build.ps1` prepare the independent attribution controls.
**Stopped at the P3 gate. P4/player/crowd, P5 policy decisions and section6 optimizations are not started.**


## Golden-valley port P4a — account player (2026-10-31)

P4a adds the account avatar standing on the summit with its back toward the camera. The same player is
drawn in the main pre-water scene and planar reflection, before cloud passes, preserving the accepted
P3 fog/post/glass pipeline and defaults. P4b/hill crowd, section6 optimizations and the P5 native-private
memory attribution are not included. `--scene-p3-only` compiles/allocates no player and preserves the
accepted P3 path. Changes stay inside `client/slint-proto`; Kotlin reference sources are unchanged.

### Player model, animation and appearance contract

`player_geometry.rs` ports PlayerModelCore's44B vertices with exact cuboid face order, UV orientation,
part IDs, classic/slim arms, legacy shared limbs, outer layers and cape. Four mesh variants including
cape geometry are uploaded once on the loader worker; the draw range excludes cape vertices when absent.
Mesh updates are unnecessary when an appearance changes. The model translation is(0.5,84,0.5), Y rotation
pi and scale1.8/32. Head pivot(0,24,0), arm pivots(+/-6 or5.5,22,0) and cape pivot(0,24,-2.1) retain source
Y/X/Z rotation order. Idle pose uses f64 elapsed seconds: head+/−0.3 radians over16s, Minecraft20Hz arm
bob, cape0.12+/−0.05 radians over5s. Animation uses the existing paused/clamped scene clock once per frame.

`player.rs` owns fixed skin64×64 and cape64×32 sRGB nearest/clamp textures, a528B model/bone uniform,
model buffers, bind group and main/reflection pipelines. Both draws use source wrapped diffuse, ambient
and direct light, alpha discard0.05, alpha blending, back-face culling and depth writes. The reference
player shader does not apply the terrain atmosphere/static-shadow functions. Reflection uses the
reflected frame uniforms and water clip; all player vertices are above the clip plane. No new target,
readback, history or per-frame texture allocation. Legacy UVs remain in source pixels (nearest sampling
hits the uploaded top32 rows of the fixed64-row texture).

Account `cloth.skin`, `cloth.cape` and optional `cloth.isSlim` feed an independent worker after menu
login. `images.rs` downloads through the existing3-per-host limiter, byte limits and decoder limits.
Classic Steve is immediately available while requests run; failures log the attached error and retain
Steve or omit a failed cape. Explicit isSlim is preserved; absent isSlim uses the core alpha test
(>=75% of24 arm-edge samples below alpha16). Supported skin width64..2048, divisible by64, square or2:1;
cape2:1. HD pixels normalize to64×64/32 by exact source-grid sampling, matching the crowd normalization
reference; no filtering changes. Old login workers are canceled and obsolete epochs discarded.
The renderer consumes the decoded appearance; CPU pixels and downloaded bytes do not remain in player
GPU state. No unique-skin-list request or crowd simulation.

### Visual/geometry validation

Fresh Windows Kotlin `:bg-renderer:renderPreview` passed at0/15/45. The original images include crowd
and4xMSAA. `P4aReferenceProbe.java` extends the disposable compiled-Kotlin probe with player/no crowd,
AA off, exact mesh and model/bone dumps. No Kotlin edits. The reference and Rust use the same local
Steve/legacy/HD and synthetic checker cape fixtures; those fixtures are evidence only, not product assets.

|Variant|Vertices (no unused cape)|Mesh bytes vs Kotlin|0/15/45 full RGB MAE /255|Player crop MAE /255|
|---|---:|---|---|---|
|steve|432|exact|1.405/1.391/1.398|3.129/3.067/3.920|
|slim-cape|468|exact|1.404/1.390/1.396|2.897/2.876/3.486|
|legacy|252|exact|1.405|3.154|
|hd|432|exact|1.405|3.129|

Model/bone matrices differ by at most2.5183e-6 between Rust/glam and Kotlin/JOML. Player crop(610,375)-
(670,438) includes surrounding grass/lake; its median channel error is1/255. Remaining silhouette/UV
boundary pixels and the existing Rgba16F versus R11G11B10F/packed-terrain residuals are visible in the
nearest-scaled crops; no color/alpha/sampler tuning was introduced to hide them. Head/arms/cape positions,
back-facing composition and texture orientation were visually inspected. HD-normalized Steve output
matches64px Steve byte-for-byte. P3 diagnostic output matches saved accepted P3 within1/255 at0/15/45.
Reflection HDR remains finite and close to the matched reference; exact metrics are in summary.json.
The player is included in the reflection draw with the same pose; the summit terrain can occlude its
mirrored image in this authored composition. This is not a claim that a large player reflection is visible
on the lake from every camera angle.

Review `p4a-matched-{0.0,15.0,45.0}.png`, `p4a-compare-*.png` (original reference with crowd), and
`p4a-player-{steve,slim-cape,legacy,hd}-*.png`. `measure/p4a-glass.png` and the aligned replacement show
authenticated cards/icons/heads and live-scene glass. Normal menu cards cover the center avatar, so the
headless crops are the primary player visibility evidence. The account run loaded a classic skin and
cape successfully (flags slim=false/cape=true). API/card data and glass are validated; this is not a new
exhaustive interaction test of all tile actions.

### Performance and pipeline creation

Paced DX12/Radeon780M preview (180 frames after30 warm-up): mean7.374ms, median7.093ms, p959.085ms, max9.918ms and60.19FPS. It includes CPU encode + submit-to-completion, excludes capture/glass/presentation. The earlier accepted P3 preview7.628ms is a different run; the smaller P4a number is not an optimization or matched speedup.

|Player pipeline|Shader-module + pipeline creation wall time (ms)|
|---|---:|
|scene player main|143.27|
|scene player reflection|1.25|

Both pipelines are created only on the loader worker; these are total creation times including DX12
shader compilation and PSO setup, not isolated FXC-only profiling. Appearance upload never recompiles them.
The first static frame still releases the loader. The memory runs include10s delay and3s stage holds;
first-live~30s is diagnostic startup, not normal launch latency.

### Controlled authenticated memory

`p4a-measure-all.ps1` first ran static / saved accepted P3 / P4a with the owner account and the same flags,
2560×1440 scale2,60s visible /30s minimized /10s restored. The host count changed100→101 between P3 and
P4a. The final link also overlapped that original P4a run, so static/P3/P4a were rechecked under output tags
`static-aligned`, `p3-aligned`, `p4a-aligned` with101 hosts/60 modpacks. Original records remain preserved;
the table uses the aligned records. These are separate sequential process runs, not simultaneous windows.
Actual focus/minimize is recorded; the diagnostic probe allows unfocused rendering while production
focus pause stays unchanged. Credentials exist only in ephemeral process environments; Java premain
launches the measurement script then exits before the configured backend main. No persistent account
settings or account values in source/evidence/command arguments. All observed API responses HTTP200;
no scene/performance/appearance failure. Completed measurement processes exited0.

MiB; process WS/private, Windows dedicated/shared GPU usage and wgpu allocation/reserve are different
accounting models, not additive. Differences under~10MiB are below the owner's known8–16MiB run drift.

|Case / phase|WS|Private|GPU dedicated / shared|GPU total|wgpu allocated / reserved|Actual focused|
|---|---:|---:|---:|---:|---:|---|
|static/visible60s|187.32|286.78|112.25/115.85|228.10|61.500/103.000|True|
|p3/visible60s|421.96|500.55|99.25/290.82|390.07|146.688/223.000|True|
|p4a/visible60s|439.05|507.45|99.25/290.82|390.07|147.125/223.000|True|
|static/minimized30s|171.71|231.63|75.86/52.10|127.96|18.875/52.000|True|
|p3/minimized30s|421.99|479.15|78.98/243.00|321.99|107.438/204.000|True|
|p4a/minimized30s|439.07|487.42|78.98/243.00|321.99|107.812/204.000|False|
|static/restored10s|252.54|348.34|108.48/179.91|288.39|61.500/164.000|True|
|p3/restored10s|422.01|479.12|79.17/290.82|369.99|147.000/204.000|True|
|p4a/restored10s|439.07|487.39|79.17/290.82|369.99|147.125/204.000|False|

|P4a minus accepted P3|WS|Private|GPU total|wgpu allocated|wgpu reserved|WS delta minus allocated delta|Private delta minus allocated delta|
|---|---:|---:|---:|---:|---:|---:|---:|
|foreground60s|+17.082|+6.898|+0.000|+0.438|+0.000|+16.645|+6.461|
|minimized30s|+17.086|+8.270|+0.000|+0.375|+0.000|+16.711|+7.895|
|restored10s|+17.062|+8.270|+0.000|+0.125|+0.000|+16.938|+8.145|

P4a logical persistent payload is0.087387MiB:1,512 vertices×44B,64×64 skin,64×32 cape and528B uniforms. Native placed resources are0.3125MiB (mesh0.125, skin0.0625, cape0.0625, uniforms0.0625), plus0.0625MiB internal player sampler indices. The visible allocated delta is0.4375MiB; native snapshots show another0.0625MiB staging difference. Minimized/restored transient UI buffers change this delta; these are not retained source images. This is not the same as process or GPU counter deltas. CPU skin/cape bytes are dropped after upload; the large P3 native-private residual remains the approved P5 investigation rather than a retained appearance buffer. This phase reports the new residual without speculatively assigning it to a specific driver object. No sampler, allocation strategy or startup optimization.

Aligned live app completion windows averaged10.785ms P3 /11.051ms P4a; max sampled P4a frame33.239ms. These share Slint's queue and exclude presentation. Original overlapped P4a mean was~15ms; it is not the clean comparison. No terminal fallback.

### Validation and review boundary

Windows51 tests passed (34 library/scene +17 app), no failures/skips. Tests cover player variants/winding/UVs,
slim detection/explicit override, HD nearest mapping, invalid sizes, idle bounds/bone pivots, DTO fields,
mocked local HTTP404 skin/cape fallback and existing regressions. naga validates the new WGSL plus frame
and PlayerParams uniform layouts. All-target/all-feature Clippy with warnings denied passed. Release
app/examples built; a link attempt during the first live measurement hit Windows executable locking,
then the post-exit retry passed. Final default-build frame0 equals the original P4a preview byte-exactly.
0/15/45 player/cape, legacy/HD, diagnostic4xMSAA/full52B and preserved-P3 captures passed; fresh Kotlin
preview/probe passed. No Git writes, destructive removals, Kotlin edits or unrequested crowd implementation.

Use `p4a-validate.ps1` / `p4a-final-validate.ps1` (no app process may hold the output executable),
`p4a-preview.ps1`, `p4a-reference.ps1`, `p4a-measure-all.ps1` (credentials in process environment), and
`python tools/p4a-summary.py`. Tagged alignment helpers preserve the drifted originals; full flags/phase
logs and native allocation snapshots are under `evidence/bg-port/p4a`.
**Stopped at the P4a gate. P4b requires the owner's explicit choice; P5 and section6 are not started.**

## Golden-valley port P5 — runtime policy and final report (2026-10-31)

P5 is implemented and the focused Windows validation is complete. The accepted P4a scene remains the
normal default at60FPS/1280×720, with MSAA off and24B packed terrain. P4b/crowd is deferred. No section6
specialization/Manual-device changes or default frame-rate changes were made. Source/tool changes stay in the Slint prototype; superseded artifacts are archived in repository DEL.
Reference Kotlin and Git remain read-only.

### Runtime behavior and actual app validation

The app renders only when focused, visible and not minimized. Inactivity keeps the last scene/frost
textures and does not advance animation. The clock resumes with a new deadline, bounded100ms delta,
and excludes paused time. The measured policy run verifies inactive scene-frame counts/time are exactly
constant for unfocused, hidden and minimized phases, and both progress after refocus/show/restore.
UI rendering/events remain separate from scene frames. The initial hide probe used Slint hide(), which
unregistered its last component and exited the event loop; that failed probe was preserved and corrected
to native winit visibility, then retested. This is a diagnostic correction, not a new product policy.

Actual resize2560×1440→1920×1080→2560×1440 is checked with screenshots. The first Slint-only synthetic
scale event left the winit renderer at a different scale and produced a misaligned/cropped diagnostic
image; it is not accepted DPI evidence. The final policy test injects native WM_DPICHANGED (144/192DPI),
which exercises winit/Slint/renderer/cache updates together. Its screenshots were visually checked at
1.5/2. This is native message injection, not a physical multi-monitor transition claim. The scene's
internal resolution and accepted visual formulas stay unchanged.

`--scene-fail` exercises a real scene-scoped wgpu validation error after180 rendered frames;
`--scene-startup-fail` fails after loader resource/pipeline creation;
`--scene-low-performance-ms=0.001` exercises the real completion monitor with a lower threshold.
All three authenticated application tests retain working static glass/menu, survive resize/minimize/
restore, drop all named scene GPU resource allocations and never restart the terminal scene. The
terminal path detaches the live glass/cache and cancels/discards pending account appearance results.
A shared device-wide loss is a separate limitation: Slint/glass use the same device, so this diagnostic
does not claim they can survive device loss. Normal threshold remains45ms over60 frames after30 warm-up.
`--no-glass` was also checked: UI/API load, no scene loader/pipelines/resources. `--no-scene` is measured
in the final static baseline below.

|Failure control|Post-fallback snapshots without scene allocations|Final allocated MiB at1920×1080|Final reserve MiB|
|---|---:|---:|---:|
|fail|6|40.875|204.000|
|slow|7|40.875|204.000|
|loader-fail|7|40.875|180.000|

The normal failure run first moved tracked allocation from147.063MiB live to61.438MiB static at the
original2560×1440 size; resizing later lowers backdrop/UI allocations. Scene meshes/textures/targets/
bind groups are released, but wgpu/driver heap capacity and shader cache can remain with the shared
Slint device. Reserve staying above a cold static process is not another live scene texture and does
not prove the OS returned all committed/resident pages. Windows GPU usage and process private bytes
are reported separately rather than equated to the resource drop. No allocator/driver optimization.

### Focused authenticated memory protocol

Every accepted visible/restored endpoint is actually focused in both Windows and the application, and not minimized; minimized endpoints
are actually iconic. Failed focus/lock/hide and an icon-network-fallback baseline are preserved, never counted. A native foreground-only check initially disagreed with the app focus; the helper now also checks native keyboard/app focus before accepting endpoints. The final table uses
normal uninstrumented release processes with the owner test account, --show-others --all-playable,
2560×1440 scale2,60s visible/30s minimized/10s restored. Allocation reports are sampled at runtime;
--scene-probe-hold-ms=0 removes artificial stage sleeps. No probe-unfocused override. Credentials travel
only in ephemeral process environments via the IDE premain launcher; the configured server main does
not run. No persistent account data in source/logs/arguments. API response status/data readiness, account
appearance, focus, native memory and screenshots are retained in measure/manifest.json and summary.json.

All memory numbers are MiB. Process WS/private, Windows GPU dedicated/shared, wgpu placed resources and
heap reserve have different accounting definitions and are not additive. Historical8–16MiB run drift limits
small differences; this batch repeated P3 baseline shows18.85MiB private/20.89MiB WS drift. Earlier unfocused P3 and mismatched restored P4a samples are historical, not this table.

|Case/phase|WS|Private|GPU dedicated/shared|GPU total|wgpu allocated/reserved|Focused / minimized|
|---|---:|---:|---:|---:|---:|---|
|final-static/foreground60s|199.30|259.55|81.73/146.43|228.16|61.250/103.000|True / False|
|final-scene/foreground60s|456.21|502.84|68.73/321.34|390.07|147.188/223.000|True / False|
|final-static/minimized30s|183.33|207.50|45.34/82.62|127.96|18.938/52.000|True / True|
|final-scene/minimized30s|456.11|481.75|48.46/273.52|321.99|107.562/204.000|True / True|
|final-static/restored10s|264.39|322.79|77.96/210.43|288.39|61.250/164.000|True / False|
|final-scene/restored10s|455.18|481.75|48.65/321.34|369.99|147.188/204.000|True / False|

|Full scene minus static|WS|Private|GPU total|Allocated|Reserve|WS minus allocated|Private minus allocated|
|---|---:|---:|---:|---:|---:|---:|---:|
|foreground60s|+256.910|+243.289|+161.906|+85.938|+120.000|+170.973|+157.352|
|minimized30s|+272.785|+274.250|+194.031|+88.625|+152.000|+184.160|+185.625|
|restored10s|+190.781|+158.961|+81.594|+85.938|+40.000|+104.844|+73.023|

Both final cases loaded100 hosts/60 modpacks, with40/40 observed API responses, all HTTP200. Full-scene account skin/cape was ready before the visible measurement. The final glass screenshot and runtime fallback screenshots were visually inspected. These are renderer/API/data checks, not exhaustive host/tile-action tests.

Static reserve in this batch changes103→164MiB after minimize/restore; it is measured and left unchanged, as required. The historical103→164MiB value is not substituted when allocator block layouts differ.

### Native-memory attribution controls (authenticated app)

The fixed allocation-probe executable records live Rust requests plus the same native/OS counters.
P3 controls have no player. Each skipped stage truly omits pipeline creation and execution; creation
logs are checked, while target/noise/bind-group allocations remain. Missing-stage images are deliberate
diagnostic controls, not alternate product quality. The create-only variant constructs all five post
pipelines/resources but does not execute them and uses prior tonemap. P2/P3 and Steve/full-player controls
run under identical focused60s endpoints. Rust live requests are not Windows heap capacity/private bytes.

|Control|WS|Private|GPU total|Allocated|Reserve|Rust live requests|
|---|---:|---:|---:|---:|---:|---:|
|ab-p3|477.207|522.035|390.066|146.625|223.000|13.637|
|ab-skip-bloom|454.402|506.195|390.066|146.938|223.000|13.512|
|ab-skip-fog|441.566|487.785|383.543|146.625|223.000|13.444|
|ab-skip-copy|454.844|500.668|390.066|146.625|223.000|13.593|
|ab-skip-mip|438.926|485.762|383.543|146.625|223.000|13.558|
|ab-skip-final|454.797|504.004|390.066|146.938|223.000|13.549|
|ab-unrendered|405.594|452.570|377.395|146.625|223.000|13.571|
|ab-p2|388.094|436.590|368.598|129.688|223.000|12.992|
|ab-steve|471.797|513.293|390.066|147.375|223.000|13.896|
|ab-scene|465.680|512.848|393.340|147.375|223.000|14.003|
|ab-p3-repeat|456.316|503.188|390.066|146.625|223.000|13.660|

|Control delta|WS|Private|Allocated|Reserve|Rust live|Private minus allocated and Rust|
|---|---:|---:|---:|---:|---:|---:|
|skip-bloom-minus-p3|-22.805|-15.840|+0.312|+0.000|-0.125|-16.028|
|skip-fog-minus-p3|-35.641|-34.250|+0.000|+0.000|-0.192|-34.058|
|skip-copy-minus-p3|-22.363|-21.367|+0.000|+0.000|-0.044|-21.323|
|skip-mip-minus-p3|-38.281|-36.273|+0.000|+0.000|-0.078|-36.195|
|skip-final-minus-p3|-22.410|-18.031|+0.312|+0.000|-0.088|-18.256|
|unrendered-minus-p3|-71.613|-69.465|+0.000|+0.000|-0.066|-69.399|
|p3-repeat-minus-p3|-20.891|-18.848|+0.000|+0.000|+0.023|-18.871|
|ab-p3-minus-ab-p2|+89.113|+85.445|+16.938|+0.000|+0.645|+67.863|
|ab-steve-minus-ab-p3|-5.410|-8.742|+0.750|+0.000|+0.259|-9.751|
|ab-scene-minus-ab-steve|-6.117|-0.445|+0.000|+0.000|+0.107|-0.552|

Interpretation and exact limits are recorded in attribution-notes.md beside the raw controls. The table
separates measured associations from inferred causes: a reduced private count after omitting one shader
is not a native stack attribution, and baseline drift/heap block changes must be considered. No source
images/meshes/noise were retained to account for the large process residual; decoded player pixels die
after upload. The Steve control removes additional scene skin/cape requests while existing UI head
requests remain unchanged. This phase reports the memory cost; it does not optimize or speculate that
all residual bytes are a leak. Recheck these controls after the separately scoped section6 changes.

### Frame cost, GPU busy, power and policy proposal

Default headless preview: mean7.710ms/max10.939ms, 60.18FPS. Final app completion windows mean11.084ms/max sampled35.999ms. Both include CPU encode plus submit-to-completion; the app also shares Slint's queue. They exclude presentation and are not pure GPU duration. No sustained45ms fallback in normal controls.

|Runtime phase|Process3D engine utilization mean (%)|
|---|---:|
|focused-live|67.75|
|unfocused|1.12|
|hidden|0.00|
|minimized|0.00|
|refocused|65.80|
|restored|67.67|

Final visible60s static/full-scene3D utilization averages1.40%/66.04% (Windows per-process3D engine counters). Activity transitions can contribute to a phase's first sample; stable hidden/minimized samples are idle. Do not infer watts from this counter or GPU busy from11ms/16.67ms. The queried built-in Windows interfaces expose no GPU-specific watts/temperature; the generic thermal-zone reading cannot be assigned to the GPU or scene. No new sensor tools installed.

The actual busy delta makes a30FPS scene worth proposing for a later visual/power A/B, while retaining
display-rate glass/UI and reusing scene/frost on alternate frames. It should reduce the scene-work
component, but no30FPS utilization/temperature/power benefit was measured here. Leave60FPS and the
live-scene default unchanged pending owner review and section6 data; no power-saving claim or policy
change is hidden in this phase.

### Startup timeline and player compile cache

|Final case|First static/UI frame (ms)|First live scene (ms)|
|---|---:|---:|
|final-static|1202|n/a|
|final-scene|1196|2605|

These use real startup without the old10s delay/3s stage holds, with diagnostic log/snapshot overhead
present in both. Worker compilation is still released after the first static frame. A single pair is
not a statistically established startup regression/improvement.

Normal/reversed headless creation order: main28.204/reflection1.076ms versus reflection28.987/main1.411ms.
The default passes use identical WGSL/layout/entry points/sample count. Local wgpu-hal30 DX12
Device::compile_stage checks ShaderCacheKey(source HLSL, entry, stage, shader model) before FXC/DXC;
reversing the order moves the expensive first compile to reflection. Thus the historical143ms/1.25ms
asymmetry reflects first new shader translation/compilation/PSO work followed by cached shader reuse,
not intrinsically cheaper reflection shading. Earlier terrain/water/post programs already initialized
the compiler, so process-wide compiler startup alone is insufficient. Times include translation/PSO,
not isolated FXC-only profiling. Historical cold timing is preserved; no specialization/cache/manual-
device optimization was done. Per-pipeline app creation times remain in summary.json.

### Validation, artifacts and stop boundary

Windows54 tests (37 library/scene +17 app), all-target/all-feature Clippy and normal/probe release builds
passed after the native-visibility probe correction (validation3). Independent failure rechecks passed.
Default0/15/45 images are byte-exact to accepted P4a; fresh Kotlin reference/probe passed with full RGB
MAE1.405/1.391/1.398 per255. The probe-only source change does not alter normal scene shaders. Final
source/executable identities and screenshot verification are recorded with the gate evidence. No Git
writes or Kotlin source edits; superseded controls were archived under repository DEL preserving paths.

Use p5-visibility-validate.ps1 (or current validation helper), p5-preview.ps1, p5-reference.ps1,
p5-resume.ps1 (ephemeral owner-account environment), p5-summary.py, then p5-report.py. Runtime/measurement
manifests point only at successful actual-state cases; earlier lock/hide/Slint-only-scale probes remain
available and are not accepted results. Review measure/final-scene-r3-glass.png and the policy-native-dpi
screenshots, plus p5-matched-*.png and gate-summary.md.
**Stopped at the completed P5 gate; P4b deferred, section6 and30FPS changes not started.**

## Golden-valley port P4b — hill crowd (2026-11-01)

Scope and design are in `P4B-PLAN.md`; the earlier `/tmp` handoff plan no longer exists. No Kotlin, UI-module, `common` or server sources changed. Accepted sampler, resolution, MSAA, packing and 60FPS defaults are unchanged.

### Implementation

`crowd_sim.rs` ports the route, time slots and poses. `crowd.rs` draws actors with instancing from the existing player mesh, skipping the 36 cape vertices. Skins live in one 64×64×50 sRGB array, and each actor is a 40-byte instance record. `crowd.wgsl` computes limb swing from the stride pitch. The contact shadow has its own small shader and runs in the main pass only.

`images.rs` loads `/player/unique-skin-list` and uses a global limit of 4 plus the existing per-host limit of 3. The 15s per-skin timeout starts after the host permit. Steve stands in until the first real skin arrives. Terminal fallback aborts downloads and discards anything not yet uploaded. `--no-crowd` and its alias `--scene-p4a-only` skip crowd resources and skin requests.

### Geometry, pose and image evidence

Route, slot and visibility data match the Kotlin dump exactly: 93 nodes, headway 1.7308s, cycle 86.54s, 25 of 50 actors visible at 0/15/45s. Maximum pose differences are x 4.0e-7, y 3.7e-6, z 4.7e-7, yaw 1.1e-7, phase 2.1e-7, and shadow position 0. These values are also a unit test, through `src/scene/hill_crowd_reference.json`.

Matched captures use the disposable `P4bReferenceProbe.java`, with AA off and 50 actors. Actor masks use pixels that differ by at least 12/255 from the same renderer's no-crowd frame, measured on the Steve case.

|Time (s)|Full-frame MAE, Steve|Full-frame MAE, mixed skins|Actor IoU|Actor pixels, Kotlin / Rust|MAE in shared actor pixels|Median in shared actor pixels|
|---:|---:|---:|---:|---:|---:|---:|
|0|1.391|1.391|0.970|11363 / 11187|1.037|0|
|15|1.367|1.368|0.980|13480 / 13357|1.016|0|
|45|1.385|1.385|0.971|12240 / 12103|1.268|0|

The P4a full-frame MAE was 1.405/1.391/1.398 with its own reference, so the crowd adds no measurable full-frame error. Rust actor coverage is about 1% smaller than Kotlin's. The mixed fixture covers classic, slim, legacy and HD skins. `--scene-p4a-only` frames match the accepted P5 preview executable exactly at 0/15/45s (0 changed pixels). The 4x MSAA and full-vertex diagnostics also run.

### Frame cost and pipeline creation

Four alternating 180-frame headless runs each, same executable (CPU encode plus submit-to-completion; excludes glass, readback and presentation):

|Case|Run means (ms)|Mean (ms)|Worst frame (ms)|
|---|---|---:|---:|
|`--no-crowd`|7.631, 7.275, 7.437, 7.465|7.452|11.54|
|`--crowd=50`|7.728, 7.381, 7.904, 7.432|7.611|12.25|

The matched difference is +0.16ms, which is within the spread between runs. In the live app, completion-window averages were 6.96–7.16ms without the crowd and 6.85–7.10ms with it, so no live cost is resolved. Windows 3D engine utilization stayed at about 59% in all live scene cases.

Pipeline creation runs on the loader worker. In the two A/B runs, the main pipeline took 19.0–19.4ms, reflection 1.3–1.4ms and contact shadow 7.6–9.3ms, or about 30ms in total. The first preview run measured 62.5/1.9/23.0ms under colder conditions. First-live time was 3.60s with the crowd and 3.59s without it in the A/B runs, and this single comparison is not a startup claim.

### Authenticated memory, strict focused protocol

All seven runs used the owner's test account with 91 hosts and 60 modpacks, and all exited with code 0. Every visible and restored endpoint was focused at both the OS and app layers, and minimized endpoints were iconic. No attempt was rejected. All API responses were 200, there were no WARN or ERROR lines, and each crowd run loaded 50 of 50 skins. The account never appears in a command line, and a scan found the password in no evidence or tool file.

|Visible 60s (MiB)|Working set|Private|GPU dedicated + shared|Allocated|Reserved|
|---|---:|---:|---:|---:|---:|
|Static|199.07|263.47|81.54 + 128.30 = 209.84|61.44|100|
|Accepted P5|438.46|509.36|99.22 + 282.82 = 382.04|147.06|196|
|P4b|419.84|486.40|99.02 + 279.77 = 378.79|148.38|196|

|Same-executable A/B (MiB)|Working set|Private|GPU total|Allocated|Reserved|
|---|---:|---:|---:|---:|---:|
|`--no-crowd`, run 1|417.44|497.93|382.04|147|215|
|Crowd, run 1|407.57|486.31|359.46|148|199|
|`--no-crowd`, run 2|418.59|497.39|382.04|147|215|
|Crowd, run 2|418.19|496.56|382.05|148|215|

|Lifecycle (MiB)|Static WS / private / GPU|P5 WS / private / GPU|P4b WS / private / GPU|
|---|---:|---:|---:|
|Minimized 30s|201.92 / 230.31 / 127.96|438.58 / 489.38 / 313.96|419.10 / 466.18 / 310.71|
|Restored 10s|219.30 / 281.74 / 224.14|438.76 / 489.58 / 361.96|418.41 / 466.24 / 358.71|

Named crowd allocations are 0.9375MiB: skins 851,968B, instances 65,536B and the skin array's internal sampler index buffer 65,536B. That matches the +1MiB allocator change in the A/B runs.

The crowd's memory cost is below what these process counters can resolve. The negative differences against P5 and in A/B run 1 are run drift, not a saving. For example, run 1's lower GPU total comes with a smaller 199MiB reserve, while run 2 is equal to its control. Comparing P4b with static gives working set +220.77MiB, private +222.93MiB and GPU +168.95MiB.

Scene frames did not advance while minimized (3806 to 3806), and 3D utilization was 0%. The crowd only advances inside rendered frames, so paused time is not replayed.

### Fallback

An authenticated `--scene-fail` run hit its validation failure 2.3s after all 50 skins loaded. The six allocator snapshots after the failure contain no crowd allocations, and allocated memory stays near 61MiB. The app stayed open for the full 40s with static glass and exited with code 0. Cancelling a download that is still in flight was not exercised live, because the skins finished first. That path is covered only by code: the job is aborted and the epoch and enabled checks drop late events.

### Validation and limits

Windows passes 48 library and 19 application tests, Clippy with `-D warnings` on all targets and on all targets with all features, and release builds. Fresh Kotlin preview and probe captures passed.

Limits:

- Live skins came from the real server, but pixel comparison used local fixtures.
- Grouping by mesh variant changes the draw order between actors, which could alter overlapping semi-transparent skin pixels. The comparison did not isolate this case.
- Title bar actions, a physical DPI change, Linux, macOS and a Compose side-by-side remain unverified.

Review `evidence/bg-port/p4b/p4b-steve-compare-*.png`, `p4b-actors-*.png`, `mixed-rust-crop-15.png`, `mixed-kotlin-crop-15.png` and `measure/final-crowd-r1-glass.png`. Raw data is in `summary.json`.

**Stopped at the P4b gate for owner review. Section 6 startup work and the 30FPS proposal are not started.**
