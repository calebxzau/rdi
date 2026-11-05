# M1 + M2 implementation plan: renderer crate and 3D main-frame validation

**Status 2026-11-05: M1 is done. M2 is on hold because the owner put the UI port first; follow `UI-PORT-PLAN.md`. Keep this file as the reference for later 3D work.**

Written 2026-11-05 for an agent with no prior context. The owner reviews at every gate listed here.
`/tmp` is a tmpfs and earlier plans there were lost, so the copy at
`client/slint-proto/M1-M2-PLAN.md` is the one to keep up to date.

## 1. Context

`client/ui` is the Minecraft launcher: Kotlin + Compose, about 60k lines. Its background is a 3D scene
drawn by LWJGL/OpenGL and read back with `glReadPixels` every frame. `client/slint-proto` is a standalone
Rust prototype of the menu screen: Slint 1.18.1 for UI, wgpu 30.0.1 on DX12 for the scene and glass.

The owner decided on 2026-11-05 that **3D is the launcher's main frame**. Compose should not be the
long-term compositor. Target architecture:

- Rust + wgpu native program owns the window, the GPU device and every frame.
- A renderer library with no UI framework dependency.
- Slint as the UI layer, only if it passes M2. If it fails, only the UI layer is replaced.
- Kotlin business logic stays, as a headless child process over stdio (length-prefixed JSON). That is M3.

Milestones: M0 (done: startup gate closed, crate in git), **M1 renderer crate**, **M2 validation**,
M3 Kotlin service and real game launch, M4 screen migration. This plan covers M1 and M2 only.

### Owner decisions that bind this plan

| Topic | Decision |
|---|---|
| 3D interaction | Look around, walk, click objects. No character gameplay and no physics |
| Scene content | Hand-made scenes plus objects driven by player data (hosts as buildings, friends as characters). No real Minecraft world loading |
| Complex controls | Not planned. Forms, text, lists and buttons are enough |
| License | Project is GPLv3; Slint's GPL option applies |
| DXC | Stays the default. `dxcompiler.dll` is under `LICENSE-LLVM.txt`; `LICENSE-MS.txt` covers only `dxil.dll`, which is not shipped |
| Memory | VRAM does not count toward any target. Report memory, but small increases never block work |
| Startup | Accepted baseline: first frame 562ms, first live scene 1636ms |

### Out of scope

M3/M4 work, Kotlin or Gradle changes, anything in `client/ui`, real game launching, real Minecraft
worlds, physics, code editor or web view controls, shipping or packaging.

## 2. Ground rules

- Talk to the owner in Mandarin. Write code, this plan and evidence notes in English.
- Stop at each gate (section 7) and wait for the owner's answer. Fixes inside the approved scope need no new approval; a material change in scope or confirmed behavior does.
- Never permanently delete project files. Move them to `DEL/<new-unique-dir>/<original-relative-path>` under the repository root. `git mv` inside the crate is fine.
- Fallible operations return `anyhow::Result`. Log failures with the error attached; never swallow them. Reduce `.ok()` on failures that should be observable.
- User-facing Chinese has no space between Chinese and Latin letters or digits (`Mod数量8个`). Use player words: 房间, not container or server process.
- Git: commit freely on `dev`, staging explicit paths, never `git add -A`. Never add a `Co-Authored-By` trailer. Do not push. Read the `git add -n` preview: root `.gitignore` has `**/bin/`. Check `git diff --cached --stat` for whole-file line-ending rewrites.
- `evidence/` is ignored except `*.md`, so generated PNG, logs and JSON stay local. Each gate needs a `gate-summary.md`.
- Update `PROGRESS.md` at each gate, and `README.md` when commands or flags change. State plainly what was not verified.

### Building and running

The repository is at `C:\Users\calebxzhou\Documents\coding\rdi5` (`/mnt/c/Users/calebxzhou/Documents/coding/rdi5` in WSL).

- Build and run Windows binaries through `pwsh.exe -NoProfile -Command "..."` from WSL. Never build against `/mnt/c` with the WSL toolchain.
- Do not use the Gradle daemon under a WSL pwsh pipe. It holds the pipe open until the timeout.
- Test account: `~/.config/rdi/.test_usr_pwd` in WSL, line 1 user, line 2 password. Read into `RDI_PROTO_USR` and `RDI_PROTO_PWD`, pass with `WSLENV`, and never print them.
- Machine: AMD 780M, scale 2.0. The monitor must be on and the desktop unlocked. With the monitor off Windows falls back to a 640x480 display, which makes results invalid.
- Measure memory only in focused runs. Focused and unfocused runs of the same binary differ by about 47MiB WS.
- Compare numbers only within one batch, using medians, and discard the first run after a shader or DLL change. First launches are much slower (882 / 2403ms seen).

### Read first

`README.md`, the 2026-11-05 entry in `PROGRESS.md`, `evidence/bg-port/startup/gate-summary.md`, `evidence/bg-port/p5/gate-summary.md`, then the code named below.

## 3. Current code facts

Verified on 2026-11-05. Re-check line numbers before relying on them.

**Crate layout.** One package, `rdi-slint-proto`. `src/lib.rs` exposes only `scene` and the optional `allocation_probe`. `glass`, `gpu_setup`, `app`, `window` and `hosts` live in the binary (`src/main.rs`).

**Slint coupling in rendering code:**

- `use slint::wgpu_30::wgpu` in `src/scene/{mod,clouds,crowd,diagnostics,loader,player,post,textures,upload,water}.rs`, `src/glass/mod.rs`, `src/gpu_setup.rs` and the examples.
- `glass::Renderer::render` returns `Option<slint::Image>`, made at `src/glass/mod.rs:529`.
- `src/glass/mod.rs:3` imports grid constants from `crate::hosts`; lines 390 and 434 call `rdi_slint_proto::scene::diagnostics`.
- `src/gpu_setup.rs` returns Slint's `WGPUConfiguration`. The `std::mem::forget` at line 130 is a workaround for Slint keeping the device until thread-local destructors. Without it, closing hangs 5s and ends with `0xC0000409`. It must stay.
- `src/scene/diagnostics.rs:13` calls `crate::allocation_probe::snapshot`.
- Relative `include_bytes!` paths in `glass/mod.rs:268`, `scene/{clouds,player,water,textures}.rs` and `examples/scene_preview.rs` reach `assets/`, `../bg-renderer` and `../ui/assets`.

**Frame flow** (`src/main.rs:97-240`, Slint's rendering notifier): `RenderingSetup` creates the glass renderer and spawns `scene::loader`. `BeforeRendering` calls `scene.render(seconds)`, which returns `Ok(false)` while a frame is in flight, then `glass.render(frame, changed)` into the backdrop texture shown by the bottom `Image` in `ui/app.slint:50`. Slint then draws on top. A 16ms `slint::Timer` requests redraws. Losing focus or minimizing pauses the scene (`src/window.rs`).

**Glass geometry is duplicated.** `App::frame` (`src/app.rs:753`) rebuilds in Rust the rectangles that `ui/*.slint` lays out. `glass::Frame` has only `grids` and `pills`, and a frame has at most 32 uniform slots.

**Camera is a function of time.** `state::camera_pose(seconds)` (`src/scene/scene.rs:28`) is read by `Params::for_pass`, `camera()` and `clouds::coordinates` (`src/scene/mod.rs:1002`). No code feeds input into it.

**The world is a one-way diorama. This is the largest M2 risk.**

- `terrain::BANDS` all end at `max_z: 40`, while the camera starts at `(0.5, 90.5, 14)`.
- `terrain::visible` (`src/scene/terrain.rs:262`) culls at build time to a fan in front of the fixed camera. `vegetation::trees` uses it too.
- `Grid` is dropped after the shadow frame is computed (`src/scene/mod.rs:342`). `Grid::at` is available for walking heights if kept.
- The shadow map is baked once, over x -172..320 and z -240..32.

**Fixed resolution.** `state::WIDTH/HEIGHT` is 1280x720, used in `scene/mod.rs:83-88`, `post.rs:312-331`, `water.rs:340-352` and `examples/scene_preview.rs`. Reflection is hard-coded 640x360 in `water.rs:293-303` and `scene.rs:102-103`. Projection is fixed at 16:9 and 84 degrees. At window scale 2 the main view is upscaled.

**Player.** Fixed world transform at `(0.5, 84, 0.5)` in `player_geometry::Params::at` (`src/scene/player_geometry.rs:260`), idle pose only. The existing camera is already third-person behind this player. The crowd (`crowd.rs`, `crowd_sim.rs`) has walking poses and a skin array.

**Slint lessons already paid for:**

- Rounded `clip` and `opacity` on elements with children each create an offscreen layer (about 150MB on a tile screen). Bake corners on the CPU or in a shader, and multiply alpha per leaf.
- Never replace a `VecModel` on refresh. `SharedPixelBuffer` images re-upload.
- Slint has no backdrop blur and no custom shaders. Glass is drawn below all Slint content.
- wgpu error scopes belong to a thread. Scene errors must not reach Slint's handler (see `scene::checked`).
- `forgecdn` resets TLS beyond about 5 parallel connections; keep the per-host download limit.

## 4. M1: renderer crate

Goal: extract rendering into a crate with no Slint dependency, with **no behavior change**.

### Target layout

```text
client/slint-proto/
  Cargo.toml                 workspace root + app package rdi-slint-proto
  build.rs, ui/, src/        app: main, app, window, hosts, images, api, config, runtime_probe, anim
  third_party/dxc/           stays with the app
  crates/rdi-renderer/
    Cargo.toml
    src/lib.rs
    src/gpu.rs               device creation from src/gpu_setup.rs
    src/scene/               moved from src/scene
    src/glass/               moved from src/glass
    src/allocation_probe.rs  moved, feature-gated
    examples/                scene_preview, p1_mesh_report, p1_precision_audit, p3_memory_probe
```

`examples/baseline.rs` uses Slint, so it stays with the app.

### Steps

**M1.0 References before any change.** Build the current commit and save under `evidence/m1/before/`:

- `scene_preview` PNGs at times 0, 15, 45 with fixed Steve and crowd.
- `glass::tests::capture_fixed_frame` for FXC and DXC at scales 1, 1.25, 2 (see `tools/startup-final.ps1`).
- Test counts, startup medians (rounds 2-6, `--scene-probe-hold-ms=0`) and close time.

Run the preview and glass captures twice to learn the noise floor. Do not assume they are deterministic. Keep the binary.

**M1.1 Workspace.** Add `[workspace]` and `crates/rdi-renderer`, and move files with `git mv`. Depend on `wgpu = "=30.0.1"` directly with the backends and shader features the examples need. Inspect Slint's feature set with `cargo tree -e features -i wgpu`. The app and renderer must resolve to one wgpu, because the device created by the renderer goes into `WGPUConfiguration::Manual`. Fix the relative `include_bytes!` paths without moving assets.

**M1.2 Remove Slint from rendering.**

- Replace `slint::wgpu_30::wgpu` with `wgpu`.
- `glass::Renderer::render` returns the new backdrop as `Option<wgpu::Texture>`. The app converts it with `slint::Image::try_from` and keeps the existing error message.
- Pass `TILE`, `PITCH`, `PADDING`, `ROWS` and `HEIGHT` as a `GridMetrics` value. The app owns the values.
- Replace `rdi_slint_proto::scene::diagnostics` with crate paths. App feature `allocation-probe` forwards to `rdi-renderer/allocation-probe`; the `#[global_allocator]` stays in the app.

**M1.3 Device creation.** Move adapter selection, the compiler attempts and their tests to `gpu.rs`, returning `GpuContext { instance, adapter, device, queue }` as `Result`. Inputs are explicit values, not `WGPUSettings`. The DXC path is a parameter. The app builds them from `WGPUSettings::default()`, wraps the result in `WGPUConfiguration::Manual`, and keeps the `mem::forget` with its comment and the Automatic fallback. Confirm logged adapter, compiler and timing are unchanged.

**M1.4 Camera input.** Add `CameraPose { eye, target }` and `FrameInput { seconds, camera }`. Change `Renderer::render` to take `FrameInput`, and thread the pose through `Params::for_pass`, `pass_params` and the cloud coordinates. The app passes `state::camera_pose(seconds)`. Add a unit test that default-pose `Params` equal the old values byte for byte.

**M1.5 Docs and helpers.** Document the public API in `crates/rdi-renderer/README.md`. Update the crate README commands and add `tools/m1-*.ps1`. Leave old `tools/p*.ps1` alone; they are historical records.

### M1 acceptance

| Check | Requirement |
|---|---|
| Dependency | `cargo tree -p rdi-renderer -e normal,build` has no `slint`; the workspace has one `wgpu` |
| Glass pixels | Same result as before for FXC and DXC at all three scales, within the measured noise floor |
| Scene pixels | Same, at times 0, 15, 45 |
| Tests | Previous tests still pass (last recorded 48 + 25), plus new tests; Clippy clean on new files |
| Startup | First frame and live scene medians within 5% of the same-batch "before" binary, or explained |
| Exit | Close under 300ms, exit code 0, no hang |
| Fallback | Missing DLL and unloadable DLL still reach a live scene on FXC |
| Memory | Reported only |

Then write `evidence/m1/gate-summary.md` and **stop for review (G1)**.

## 5. M2: 3D main-frame validation

Goal: decide whether Slint can be the UI layer over an interactive 3D main frame. Build the smallest real thing that exercises each risk. Use placeholder art. Do not polish. Do not implement real launcher features.

Keep rendering code in `rdi-renderer` and pure logic testable without a window. New Slint work goes in `ui/`, enabled by default in a separate `AppMode::MainFrame` screen reached with `--main-frame`, so the old menu remains for comparison.

### M2.1 Coverage audit (do this first)

Extend `scene_preview` with `--camera=eye:target` poses. Sweep yaw ±15/30/45/60/90 and walking positions around the hill, the lake shore and the village. Find where terrain, trees or water visibly end. Record this in `evidence/m2/coverage/`.

Default scope: a bounded diorama with clamped yaw and pitch and a `WalkArea` the geometry fully supports. If this leaves less than ±45 degrees of yaw, make the `visible()` culling a build option, measure the mesh, memory and frame cost of widening it, and report. Do not add terrain beyond `max_z: 40` without the owner's approval.

### M2.2 Camera rig and walking

New `camera.rs` with no window dependency, unit tested. Third-person orbit around the avatar. Controls:

- Drag on empty space: yaw and pitch. Use pointer deltas; no cursor capture.
- Wheel on empty space: zoom, clamped.
- WASD: walk relative to the view.
- No input: the existing gentle sway resumes smoothly.

Walking uses a compact height map kept from `Grid` for bands 0-1. Block water (height ≤ `WATER_LEVEL`), steps too steep to climb, the village houses' boxes and the edge of `WalkArea`. Trees do not collide. Give `player_geometry::Params` a world position, heading and walk phase, and port the walk pose from `crowd_sim`.

Tests: clamping, collision against each blocker, deterministic paths for a fixed input script, and no pose containing NaN.

### M2.3 Data-driven objects and picking

```rust
pub struct SceneObject { pub id: String, pub kind: ObjectKind, pub position: Vec3, pub bounds: Aabb, pub label: String }
```

- `Renderer::set_objects` accepts up to 64 objects. Placement is derived from a stable hash of `id` onto safe cells, so an object stays put between refreshes.
- Hosts from the existing `api`/`hosts` modules appear as an instanced placeholder marker. Friends reuse the crowd skin array, or use the same marker if that costs more than a day; say which.
- Picking is a CPU ray from `inverse_vp` against AABBs, nearest hit. No GPU readback.
- Hovered objects are highlighted. Labels are Slint `Text` placed from a world-to-screen projection, hidden behind the camera and off screen.
- A click is a press and release within 5 logical pixels. Beyond that it is a drag.
- Clicking a host opens its panel (M2.5). Clicking a friend logs a stub action.

### M2.4 Input routing

A full-window `FocusScope` + `TouchArea` above the backdrop `Image` and below all panels forwards to the camera rig. Panels sit above it, so Slint's own hit testing does the routing. Cover this matrix with a checklist in the gate summary:

| Case | Expected |
|---|---|
| Click, drag or wheel on a panel | Goes to the UI; camera does not move |
| Drag starting on empty space, released over a panel | Stays a camera drag until release |
| Drag starting on a panel, released over empty space | Does not move the camera or click the scene |
| WASD while a text field has focus | Types text; avatar does not move |
| Esc or click on empty space while a text field has focus | Focus returns to the scene |
| Key held while the window loses focus | Avatar stops; no stuck key on return |
| Title bar drag, minimize, maximize, close | Works; scene pauses and resumes correctly |

### M2.5 Moving glass panels

Add `glass::Panel { rect, corner_radius, tint, opacity }` to `Frame`, drawn with the existing haze and rim pipelines. A host panel opens and closes with a 250-350ms slide and scale, and the glass tracks it live.

Make the Slint layout the only source of the rectangle: expose it through `out` properties read in `App::frame`, as `tab-position` already is, instead of duplicating the geometry. Do not put `opacity` or rounded `clip` on the panel parent.

Verify with screenshots at scales 1, 1.25 and 2: the glass edge and Slint content align within 1 physical pixel during and after the animation. Slot exhaustion must be a clear error, and the 32-slot limit should be raised if the screen needs it.

Known limitation to confirm and report: glass is drawn before all Slint content, so overlapping panels cannot refract each other's text.

### M2.6 3D views in a scrolling list

A list of 30 rows, each with a 96x96 logical avatar preview rendered by wgpu, shown through `slint::Image::try_from(wgpu::Texture)`.

Add `PreviewRenderer`, a separate pipeline targeting `Rgba8Unorm` that reuses the player mesh and skins. Pool textures for visible rows plus two. Render a preview once, re-render only when its skin changes, and animate only the hovered row. Corners are rounded in the shader, not with a Slint `clip`.

Test: scripted wheel scrolling for 30 seconds over the live scene. Report frame times, GPU memory growth, and whether reassigning an `Image` triggers re-uploads or layer allocation.

### M2.7 Form and Chinese input

A login form with account and password fields (masked), validation messages and a submit button that really calls `POST /player/jwt`, plus one free-text field for a 房间 name.

Check: Tab order, Enter to submit, selection, copy and paste, Chinese IME candidates following the caret in this no-frame transparent window, and text under glass staying readable. Windows IME composition cannot be meaningfully automated, so **the owner tests it at the gate**. Supply exact steps.

### M2.8 Resolution, frame rate and budget

Replace `WIDTH/HEIGHT` constants with a `RenderSize` chosen at creation, including the reflection size and projection aspect. The default stays 1280x720, and M1's pixel comparison must still pass at that default.

Add a `--main-frame-script` mode that plays a fixed 60s sequence: walk, orbit, hover, open and close a panel, scroll the list. It logs per-frame CPU time, GPU completion time (`perf` already measures completion), UI frame count, and p50/p95/p99 and the worst frame.

First make the measurement harness run with a really focused window. Every G1 run logged `focused=false`, and the scene pauses without focus unless the probe overrides it, so check the runtime snapshot and reject any round that is not focused.

Compare on the 780M, focused, three rounds each:

- Scene at 1280x720, 1600x900 and 1920x1080.
- Scene at 60 and 30 FPS, with UI still at 60 (A/B pending since P5).
- FXC against DXC, to re-check the 0.7-2ms DXC cost under interaction.

Recommend a default and draft a tier table. Do not build a settings UI.

### M2.9 Windows and platform checks

| Check | How | If it cannot be done |
|---|---|---|
| Title bar drag, min, max, close; DPI change; resize | Real run on Windows | Report as not verified |
| Dual-GPU adapter choice; non-SM6 GPU | Real run if hardware exists | Report as not verified; do not claim from decision tests |
| The early exit seen once on 2026-10-30 | Watch for it across all runs; log exit codes | State how many runs were clean |
| Linux Vulkan | Copy to the WSL native filesystem with sibling assets, build there, run under WSLg | WSLg proves compile and start only, not performance. Say so |
| mac arm64 Metal | Build only unless the owner provides a real Mac | The NoMac VM has no GPU and costs money: ask before starting it. Report as blocked, not as passed |

`gpu_setup` is Windows-only by design. Other platforms use Slint's Automatic path, so check that the shaders, the startup path and exit work there.

## 6. M2 pass criteria

Slint is accepted only if all of these hold on the owner's Windows machine. Each result in the report is **pass, fail or not verified**, with evidence.

| # | Criterion | Requirement |
|---|---|---|
| 1 | 3D interaction | Look, walk and click work. A clicked host opens its panel |
| 2 | Input routing | Every row of the M2.4 matrix behaves as expected |
| 3 | Views in a list | 30-row scroll holds the frame budget with no layer memory growth over time |
| 4 | Moving glass | Alignment within 1 physical pixel at all three scales, during animation |
| 5 | Chinese input | Owner confirms IME and form behavior |
| 6 | Frame budget | On the 780M at the recommended settings: scripted run p95 ≤ 16.7ms, p99 ≤ 25ms, no repeating stall |
| 7 | Stability | 30 minutes of the scripted loop with no crash, device loss or steady memory growth; clean exit |
| 8 | Linux and mac | Runs on real GPUs, or explicitly reported as still open for the owner to decide |

Thresholds in row 6 are starting values. If M2.8 shows they cannot be met for a reason that lies in the renderer rather than Slint, report that separately. It does not count against Slint.

### If a criterion fails

Classify the failure as a Slint limit, a renderer problem or a test problem. If it is a Slint limit and the workaround would take more than a day, **stop and report** with evidence and options. Do not start replacing the UI layer: that decision belongs to the owner.

## 7. Gates

| Gate | Contains | Owner sees |
|---|---|---|
| **G1** | M1 complete | `evidence/m1/gate-summary.md`, pixel and startup comparison |
| **G2a** | M2.1-M2.3 | Coverage previews, proposed walk area, a build to walk around and click in |
| **G2b** | M2.4-M2.7 | Routing checklist, alignment screenshots, list measurements, IME test steps |
| **G2c** | M2.8-M2.9 + section 6 | `evidence/m2/gate-summary.md`: result per criterion, recommended render size and frame rate, and a recommendation on keeping Slint |

Report the G2a coverage result early if it changes the scope. Keep summaries short: numbers, what was verified, what was not, and the decisions needed.

## 8. Decisions reserved for the owner

- Any terrain or world expansion beyond the current diorama.
- Using paid NoMac time.
- Dropping or relaxing a section 6 criterion.
- Keeping or replacing Slint, and starting M3.
