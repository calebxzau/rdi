# bg-renderer Agent Notes

## Purpose

This module renders the launcher's lightweight Minecraft-style animated background. It does not start Minecraft or reuse Minecraft client rendering code. It bundles a deliberately minimal, approved subset of Minecraft 1.21.1 vanilla PNG resources for its own standalone OpenGL materials; the packaged renderer has no Minecraft runtime or JAR dependency. Cloud geometry is a port of the local Minecraft 1.21.1 `LevelRenderer.renderClouds` pass.

The Gradle project name is `:bg-renderer`. The consuming Gradle root is `client/ui`; `client/ui/settings.gradle.kts` maps the project to `../bg-renderer`.

## Module Boundary

- `:bg-renderer` owns the fixed scene, shaders, render thread, off-screen framebuffer, frame conversion, animation timing, and performance detection.
- `:render-core` owns the process-wide GLFW lease and synchronized hidden-window creation/destruction.
- `:ui` owns Compose lifecycle, navigation/window activation policy, and the static `assets/bg.avif` fallback.
- `:player-model` is separate, but it uses the same `:render-core` GLFW lifecycle.
- Do not make `:bg-renderer` depend on the main `:ui` project or on `:player-model`.
- Do not call `glfwInit`, `glfwTerminate`, `glfwCreateWindow`, or `glfwDestroyWindow` directly from this module. Use `GlfwRuntime`.

## Golden Valley Scene (in progress)

The scene recreates a reference composition: a player standing on a grassy summit seen from behind, a stepped dirt cliff on the left, a valley with a lake, two broken ridge lines, and a snow-capped range under a golden-hour sky. It replaced the former harbor/boat scene (2026-10-14; the old code is in git history). Agreed phases:

1. Done (2026-10-14): harbor/boat removed; golden sky, low sun, lighting and camera; heightmap terrain, snowy range and lake; analytic atmospheric fog.
2. Done (2026-10-14): volumetric valley fog layer, plus a clear sky (crisper blue, about half the vanilla clouds) at the user's request.
3. Done (2026-10-14): oak/birch/spruce forest, a four-house hamlet with lit windows and door lanterns, swaying grass tufts on the summit, and the player's idle motion.
4. Reference pass (approved 2026-10-14; output stays 720p, clouds stay as they are):
   - A. Done (2026-10-14): sun moved ahead-right so its glow and the golden band show at the right edge; warmer/stronger direct light, cooler ambient with Minecraft-like face shading, leaf/grass translucency, lighter haze, brighter valley fog, a little more tonemap contrast and vibrance.
   - B. Done (2026-10-14): eight pointed peaks laid out after the reference (main horn right of center, a tall peak at the left edge), snow down the side faces, rock outcrops and a rock band, green coarse steps on the forested foot, brighter snow/darker rock, haze that thins faster with height.
   - C. Done (2026-10-14): a broad lake (with a river from the left) moved behind the hamlet and in front of the ridges, the valley floor dipping toward it, a treeless shore, thinner near forest with trimmed round crowns, pointed far spruces, lower/denser valley fog thinned over open water, and far-tree hidden-face culling (mesh 847k -> ~585k vertices).
   - D. Done (2026-10-14): the user asked for a higher hill and camera: the summit, camera, peaks and clouds were raised together (summit 24 -> 42, clouds 128 -> 148, peaks +18), so the range and clouds keep their framing while the valley and lake open up below; a small terraced summit with risers facing the camera, two loose blocks and taller tufts; leafy trees on the near high ground. The left plateau cliff built in this phase was then replaced by low forested left woods at the user's request. No separate framing tree: the lowered flank forest already frames the bottom corners.
   - E. Pending: test/notes refresh and performance checks.

This is procedural Minecraft-style scenery, not a world or chunk loader.

## Current Implementation

Main files:

- `BackgroundScene.kt`: materials, camera orbit, fixed `SunState`, cloud coordinates, water animation, golden-hour colors, and the CPU mirror of the atmosphere integral (`atmosphereOpticalDepth`).
- `BackgroundTerrain.kt`: deterministic hash value noise (`TerrainNoise`), the fixed layout height function `terrainHeight`, nested level-of-detail bands (`TerrainGrid`), materials/strata, view-wedge culling, the terrain mesher, and the shadow bounds.
- `SceneMeshBuilder.kt`: primitive float/int lists and the indexed per-material scene mesh builder; `faceUv`.
- `BackgroundRenderer.kt`: public session API, render thread, `BackgroundFramePipeline` (all GL objects and the per-frame pass order), `SceneGlRenderer`, 4xMSAA framebuffer, shaders, and the performance monitor. Frames are published through the three-slot `FrameSlotPool` in `BackgroundFramePainter.kt`.
- `ValleyFog.kt`: valley fog constants and CPU mirrors (layer profile, reach, slab segment, step count, wind offsets), the tileable 3D noise volume, `ValleyFogRenderer` (half-resolution march target), and the fog GLSL.
- `BackgroundVegetation.kt`: deterministic tree placement (`placeTrees`), tree shapes (`emitTree`), grass tufts, and `buildVegetationMesh`.
- `BackgroundVillage.kt`: `VILLAGE_HOUSES`, foundation pads flattened into the terrain, the meadow in front of the hamlet, and `buildVillageMesh`.
- `StaticShadow.kt`: the orthographic static shadow frame and the AABB corner-AO helper (used by the houses). `StaticSurface.kt`: exposed-face rectangle subtraction between boxes (used by the houses).
- `src/test/.../BackgroundPreview.kt`: dev-only GPU preview (see Validation).

`BackgroundRenderer.openSession()` returns a `BackgroundRenderSession` exposing `frame`, `failure`, `lowPerformance`, `setActive(Boolean)`, `setPlayerAppearance`, and `close()`. The renderer uses a hidden OpenGL 4.5 core context on the daemon thread `rdi-background-renderer`, renders a fixed `1280x720` frame at `20FPS`, synchronously reads BGRA pixels, and publishes a lightweight `BackgroundFramePainter` token. `AppBackgroundImage` draws the token directly; it must not wrap each frame in `BitmapPainter`.

### Terrain

- One block is one world unit; column tops are integer heights. `WATER_LEVEL=2.35`; the summit is `HILL_TOP_Y=84` (set by the user for a high, wide view on 2026-10-14) with the player standing on column (0, 0); a test keeps every column near the summit (x -120..40, z -120..12) at or below it.
- `terrainHeight` is a fixed artistic layout (fixed `TERRAIN_SEED`), not seeded per session: valley floor, low right hills, two ridge bands with gaps behind the lake (z -252 and -290), eight warped `MOUNTAIN_PEAKS` (x, z, apex, radius, ridge phase) combined by `max`: each is a concave horn profile `(1-d)^1.6` whose footprint swells along 3/5-fold spur directions, plus ridged crags scaled by altitude, over noise foothills and a jagged back rim (sized for the high, wide view on 2026-10-14: apexes 161..211, radii 130..169, so the main horn tops out near 208 at about 15% of the frame height and the range fills about a fifth of it; summits softly compressed above 196 so they stay below the clouds; the range fades in from z -250 to -360 so its broad foot does not end in a wall), low forested left woods (a rounded hill `LEFT_WOODS_HEIGHT=14` above the valley floor around (-88, -88), radii 52x40, `leftWoodsDistance`; it replaced the former ledged plateau cliff at the user's request on 2026-10-14), a carved lake and river, and the terraced summit (`summitDrop`: flat within `SUMMIT_FLAT_RADIUS=2.5`, then 0.85 per unit for 6 units, then a 1.3 flank; the distance is stretched 1.25x in front and 0.9x behind with a low, slow wobble so terrace edges stay continuous; it steps down toward the camera, so the bottom of the frame shows grass-block sides). `lakeDistance` (shared by terrain, trees and tests) is the normalized distance to the lake ellipse (center (70, -185), radii 125x40, noisy shore) or to the river that winds in from the left along z≈-176 and joins its west end; open water below ~0.6, shore near 1. The valley floor dips up to `LAKE_APPROACH_DEPTH=3.5` within `lakeDistance` 3 (never below `WATER_LEVEL+1.2` outside the lake), so the forest in front sits low enough for the water to show over its crowns. The Python composition prototype used to design it matched `TerrainNoise.hash01` bit-for-bit; that parity is locked by a test.
- `TERRAIN_BANDS` are nested rectangles with 1-, 2-, 4- and 8-block cells (the 8-block ring, ±1024 x and to z -1024, fills the wide view out to the dissolved horizon). Each band edge is a multiple of the next band's cell size, so cells never straddle bands; the finest band containing a column owns it. Cell heights are sampled once at cell centers and rounded to a multiple of the cell size, so distant terrain forms chunky cubes instead of thin contour stripes.
- Cells outside a 66° horizontal wedge from behind the camera are skipped (plus a 40-block strip on the sun side for shadow casters), and so are cells farther than `TERRAIN_MAX_DISTANCE` (`WORLD_FADE_END`+40) from the camera. The wedge must cover the horizontal FOV (58° at FOV 84) plus the camera swing with margin.
- Top faces: cells whose four corner AO values are all 1 are greedy-merged into rectangles by (height, material); other tops are emitted per cell with Minecraft-style corner AO (`0.14` per level) and an AO-aware diagonal flip. Grass tops carry a gentle low-frequency tint.
- Side faces walk each cell edge one block at a time and emit a face down to each lower neighbor, so steps across band edges stay crack-free. Faces are split into strata by depth below the column top: grass (`GRASS_SIDE` 1, `DIRT` to `nearDirtDepth` 3..5 in the 1-block band (5 elsewhere), then `STONE`; in the 4-block band `GRASS_TOP` for one cell of height, `DIRT` 2, then `STONE`, because distant steps are seen almost only from the side and grass-block sides read as dirt stripes), snow (`SNOW` down `SNOW_SIDE_DEPTH=4`, then `STONE`, since the range above eye level shows only side faces), sand and gravel. The bottom vertices of the lowest segment get AO `0.86`.
- Materials: `<=0` gravel, below water sand; everything nearer than `HIGH_RANGE_START_Z=-215` (the summit, however high) is grass; beyond it, snow above `snowLine` (92±8) unless steeper than three cells or on a `rockOutcrop`; a `ROCK_BAND_HEIGHT=8` stone band just below the snow; stone for steep columns from `MOUNTAIN_ROCK_HEIGHT=84`; otherwise grass. `mountainTint` brightens snow (`SNOW_BRIGHTNESS=2.1`, it is mostly seen in shade with the sun ahead-right) and darkens mountain rock (`0.55`, stone columns from 84 up) so the snow reads brighter than the sky behind it; lower cliff and ridge stone gets `CLIFF_ROCK_TINT=0.8` so sunlit cliff faces do not glare.
- The static scene mesh (terrain, village, forest, grass, water) is built once into one VBO and one EBO with contiguous material batches (empty batches are skipped when drawing). Keep it below `SCENE_VERTEX_BUDGET=900_000`; it currently has about 714k vertices (~13.2k trees, ~490 detailed) with the wide view and the bigger range. The vertex layout is exactly `position3, normal3, color3, kind1, uv2, ao1` (`SCENE_VERTEX_FLOATS=13`); kind `0` is static geometry, kind `1` the single six-vertex water quad, and kind `2` (`FOLIAGE_SWAY_KIND`) the swaying tips of grass tufts.
- UVs repeat once per block; side faces use `-y` so the image's top row (the grass fringe) sits at the top of each block. Block textures are mipmapped with up to 8x anisotropy, and the scene shader samples with `textureGrad` on the continuous UV so mip selection does not jump at block seams.

### Forest, Village and Grass

- `placeTrees` puts one jittered candidate in each 5-block cell inside the view wedge and thins it with a low-frequency noise into woods and clearings. No trees in or at the lake, within `LAKE_SHORE_CLEARANCE=1.3` (`lakeDistance`) of it, above `TREE_LINE=76` beyond the lake (below the rock band), on steep columns, within `SUMMIT_CLEARING_RADIUS=15` of the player, near the houses, or in the meadow in front of the hamlet. Near (detailed) candidates are thinned by another `NEAR_TREE_THINNING=0.4`, so crowns stand apart with grass between them. A test checks that over a quarter of the lake's columns are seen from the camera past terrain and canopies. Candidates span x -900..900, z -760..20. The tree line applies only beyond the lake. Species: beyond the lake (`z < HIGH_RANGE_START_Z`) spruce from 34 and mixed 26..34; otherwise oak with ~25% birch at any height, so the summit flanks and the left woods stay leafy. Inside the left woods the clearing noise is ignored, so they stay one continuous forest (near-tree thinning still applies). Trunks: oak 4-6, birch 5-7, spruce 6-9.
- Trees within `TREE_DETAIL_DISTANCE=150` of the camera get Minecraft shapes with trimmed corners (oak/birch: a 5x5 cross of two overlapping boxes for two layers, a 3x3 layer and a plus on top; spruce: alternating rings and a tip); farther oaks are one 5x5x3 cross and farther spruces a narrow 3x3 body under a 1x1 tip, so distant stands read as pointed conifers. Trunks have no top/bottom faces. Each tree has a small brightness tint.
- Trees from `TREE_DISTANT_DISTANCE=420` are a few pixels wide: half of them are skipped and the rest are one canopy box without a trunk (minus hidden faces).
- Far trees are most of the mesh, so `farTreeHiddenFaces` drops the faces neither the camera nor its water reflection (same x/z) ever sees: -Z (every far tree is in front of the camera), the x side facing away from the eye's swing range, and undersides at or below the lowest eye height. The shadow bake culls back faces, and the sun (ahead-right) still sees each kept canopy top, so far trees still cast shadows; a test checks every dropped face against the whole camera track.
- Leaf and grass materials are `doubleSided` alpha-cutout: their batches are drawn with culling disabled, and back-facing horizontal leaf normals are flipped (`uDoubleSided`) so inner leaves seen through holes are lit like Minecraft's fancy leaves. The scene shader outputs alpha 1 because all scene materials are opaque or cut out.
- Draw order: vegetation batches are drawn before terrain (and trees are emitted near-to-far), so canopies depth-reject the ground and slopes behind them. This halved the forest's cost on the 780M.
- Grass tufts: two crossed quads with explicit inset UVs and an upward normal on summit/flank columns (within 12 below `HILL_TOP_Y`, never the player's column or a boulder; `GRASS_TUFT_CHANCE=0.35`, sizes 0.6..1.0). `SUMMIT_BOULDERS` puts a cobblestone block right of the player and a stone block to the left on lower terraces (five faces each, `CLIFF_ROCK_TINT`), clear of the camera-to-player sight line. Their tips (kind 2) sway in the vertex shader with frequencies 2 and 3 rad/s of `uFoliageTime` (the wrapped water time), both whole cycles of the 10π wrap. Grass casts no shadows (the shadow shader discards kind ≠ 0).
- `VILLAGE_HOUSES` (four houses, fronts facing +Z toward the camera, all in the 1-block band) stand on pads: `terrainHeight` = `flattenVillagePads(baseTerrainHeight)`, where each column blends only toward its nearest house (footprint plus a one-block apron is exactly flat). Each house: cobblestone foundation, oak-log corners (listed before the walls so coplanar wall faces are cut), oak-plank walls, a spruce door, four protruding glowstone windows (emissive), a stepped spruce gable roof and a door lantern, meshed with `exposedStaticFaces` and box-corner AO.

### Camera, Sun and Sky

- The camera orbits `CAMERA_ORBIT_RADIUS=13.5` behind the player at `CAMERA_HEIGHT_ABOVE_PLAYER=6.5`, swinging ±`0.06` rad over `60s`, always looking away from the orbit center with `-16°` pitch. FOV `84`, near `0.25`, far `1000` (pitch, FOV and summit height chosen by the user on 2026-10-14). The player stands a little below the frame center at roughly 46px tall, over a wide view of the valley.
- `SunState` is the single source for the sky glow, terrain/cloud/player lighting, water highlight, and the shadow map: azimuth `72°` (ahead and to the right, about 20° beyond the right edge of the frame), elevation `20°`. Camera-facing faces are therefore in shade (a back-lit golden-hour look) and shadows fall toward the camera. The sun billboard is placed `SUN_BILLBOARD_DISTANCE=600` from each pass eye toward the sun, so it behaves like an object at infinity; it stays off-screen by design and only its sky glow shows.
- `GOLDEN_SKY_FUNCTIONS` holds the display-space `goldenSkyDisplayColor`: a clear saturated blue zenith, a clean pale horizon that turns warm toward the sun azimuth, and a golden band (`pow(alignment, 3)`, below `height 0.45`) blended toward `(1.00, 0.76, 0.46)` on the sun side, plus a wide glow lobe around the sun that reaches into the right edge of the frame. The user wants the sky very clear; keep haze out of the sky itself. The sky pass dithers in display space and converts to linear exactly once; water reuses it for fallback reflections.
- Lighting: terrain ambient `(0.33, 0.40, 0.56)` scaled by Minecraft-like face shading `0.68+0.32*normal.y` and AO, plus warm direct `(1.70, 1.18, 0.66)` times shadow. Double-sided leaves and grass add a translucency term `pow(max(dot(view, toSun), 0), 3) * 0.45` to the direct light (shadowed too), so canopies toward the sun glow. The player uses the same colors with wrapped diffuse and its own `0.78+0.22*normal.y`.
- The player's idle pose (`BackgroundScene.playerIdlePose`) follows Minecraft's `HumanoidModel` arm bob, slowly turns the head ±0.3 rad over 16s, and stirs the cape; bones rotate about the shoulder (y=22), neck (y=24) and cape top pivots.

### Atmosphere

- `ATMOSPHERE_FUNCTIONS` is a closed-form exponential height fog (`ATMOSPHERE_DENSITY=0.0026`, falloff `1/40` above `WATER_LEVEL`, so summits keep contrast over a hazy valley floor) applied in the terrain and water shaders; `atmosphereOpticalDepth` in Kotlin mirrors it and is checked against numeric integration. The haze color shifts from a light blue `(0.58, 0.66, 0.82)` to a soft warm `(0.88, 0.76, 0.66)` only close to the sun azimuth (`alignment^6`), so the mid-ground on the sun side does not turn sepia.
- `applyAtmosphere` also dissolves terrain and water into the sky color of their own direction (`goldenSkyDisplayColor`) between `WORLD_FADE_START=650` and `WORLD_FADE_END=950`, so with the high, wide view the edge of the world and the far-plane cut never show; the outer terrain band and the water plane reach past it.
- The planar reflection pass fogs only the part of the path above the water (`waterEntryPoint`), and the water pass adds the eye-to-surface part, so reflected terrain is not fogged twice.
- Clouds use no atmosphere; they fade to transparent between `CLOUD_FADE_START=360` and `CLOUD_FADE_END=560` horizontal units, which must stay inside the mesh coverage on the -X/-Z side.

### Valley Fog

- A soft horizontal slab: dense core from `VALLEY_FOG_BOTTOM=2` to `valleyFogTop(z)` (`10`, rising `0.05` per block past z=-160 up to +14 so fog pools at the foot of the range), with ±`3` soft edges, low enough that crowns poke out of it. `valleyFogReach` keeps the area in front of the summit clear (no fog for z > -50, full from z=-120). The whole slab stays below the camera.
- Density `0.075` times the profile times noise banks, times `VALLEY_FOG_OVER_LAKE=0.3` toward the middle of the lake ellipse (`smoothstep(0.6, 1.0, ...)`), so the water shows through while its shores stay misty: two lookups into a CPU-built tileable 64³ value-noise FBM (`buildValleyFogNoise`, R8, repeat, linear), a coarse one stretched along x into strips and a detail one, shaped by `smoothstep(0.40, 0.72, ...)`. Wind offsets are wrapped to `[0,1)` on the CPU (`valleyFogWindOffsets`) so precision holds for any uptime.
- `ValleyFogRenderer` marches at `640x360` into an `RGBA16F` target (rgb in-scattered light, a transmittance). Each pixel reads the captured pre-water depth, ends at the analytic water plane, intersects the slab, and takes 6..24 steps (~6 blocks each) with a fixed interleaved-gradient jitter (no history buffers). Light is a light blue-gray ambient `(0.30, 0.34, 0.44)` plus warm sun `(0.52, 0.38, 0.22)` through a Henyey-Greenstein phase (g=0.35), sun visibility from the static shadow map, a height shade, and the terrain's atmosphere haze for distant fog.
- `HDR_COPY_FRAGMENT_SHADER` composites it before Bloom: `scene * transmittance + inscatter`, with a joint bilateral upsample (4 low-resolution texels, bilinear weights damped by linear-depth difference) so fog does not bleed across silhouettes.
- The planar reflection uses `applyReflectedValleyFog`, a noise-free constant-density stand-in (`VALLEY_FOG_REFLECTION_COVERAGE`) over the reflected path above the water, applied before the atmosphere.

### Clouds and Water

- Clouds follow Minecraft 1.21.1: 12-block cells (`CLOUD_HORIZONTAL_SCALE`), 4-block layers, `0.6*time` drift, a 2048 wrap, camera-relative `+0.33` offsets, and the `-6..6` tile grid; `CLOUD_HEIGHT=220` (a multiple of the 4-block layer, so the layer splits like Minecraft's) keeps them above the highest peak; with the camera at about 90 they sit about 130 above it, close to the original look. The bundled texture uses one nearest-filtered sample. For a clear sky, `thinCloudCover` removes whole clouds (4-connected covered texels, connected across the tiling edges) at upload, keeping `CLOUD_COVER_KEEP_FRACTION=0.45` by a seeded hash; the vanilla texture has ~1195 separate clouds, the largest under 5% of the cover. Cloud shading mixes cool sky light with warm light toward the sun. Both cloud passes disable culling, do a depth-only prepass, then a color+depth pass, and restore culling/color writes/depth writes.
- Water keeps the Complementary-inspired shader: deep-blue tint, `BASE_WATER_OPACITY=0.65`, four normal layers, depth-rejected refraction with Beer-Lambert absorption and shoreline foam, a half-resolution mipmapped planar reflection with Schlick Fresnel, and a capped GGX sun highlight. `waterVertexOffset` is the CPU mirror of the vertex wave. The water quad is `WATER_EXTENT=1100` (past the dissolved horizon) and counter-clockwise from above.

### Shadows

One `2048x2048` static depth map is baked in `initialize()` from `terrainShadowBounds` (the near valley, extended toward the sun so low-sun casters are kept), with the light eye `400` units toward the sun. Main and reflection terrain passes sample it with four hardware-filtered compare taps; the depth bias is given in world units and divided by `uShadowDepthRange`. Outside the frame everything is lit. The bake snapshots and restores all mutated GL state.

## Required Invariants

- Each frame (in `BackgroundFramePipeline.render`) follows `MSAA bind -> pre-water scene -> HDR color/depth capture -> 640x360 planar reflection -> MSAA rebind -> reflection mipmaps -> water -> raw HDR resolve -> 640x360 valley fog march -> HDR linear copy with fog composite -> mipmaps -> Bloom atlas -> Bloom/Tonemap/saturation -> final RGBA8 READ framebuffer -> BGRA glReadPixels`. Call `pixels.clear()` immediately before every `glReadPixels`.
- The MSAA framebuffer has 4x `GL_R11F_G11F_B10F` color and `DEPTH_COMPONENT24`; scene capture, reflection and raw resolve use the same alpha-less HDR format; the final post target is `GL_RGBA8`. Release all GL objects on the render thread.
- Color contract: authored colors are display-space and converted with the exact sRGB transfer function; `TextureEncoding.SrgbColor` for block, sun, cloud, skin and cape PNGs and `LinearData` for the water normal/noise maps. Do not pre-transform bytes or enable `GL_FRAMEBUFFER_SRGB`. The final tonemap performs the sole linear-to-sRGB conversion.
- `SunState` stays the only sun source; do not add a second sun or animate it independently.
- Terrain must stay deterministic and crack-free across band edges, keep the summit flat around the player and the camera-to-player sight line clear, keep peaks below `CLOUD_HEIGHT`, and keep outward winding (back-face culling is on).
- Frame publication uses exactly three reusable Skia `Bitmap`/Compose `ImageBitmap` pairs per session; no per-frame `makeRaster`, `toComposeImageBitmap`, or extra width×height×4 allocation. A `DISPLAYED` slot stays reserved across draws.
- `active=false` parks the render thread without clearing the last frame. A terminal renderer failure is logged with its exception and leaves the UI on `bg.avif`. A low-performance decision is terminal for the session. OpenGL resources, the hidden window and the GLFW lease are released when the render loop exits.
- This renderer intentionally does not implement SSR, G-buffers, history-frame sampling, or voxel ray reflections.

## Activation and Fallback

- `client/ui/src/main/kotlin/calebxzau/rdi/client/ui/AppBackground.kt` opens one renderer session for the application window and shows `bg.avif` before the first frame, after a renderer failure, or after low-performance fallback. A paused renderer keeps its last frame.
- `client/ui/src/main/kotlin/calebxzau/rdi/client/Main.kt` activates rendering only when the window is focused (`LocalWindowInfo.isWindowFocused`) and not minimized, and the route is `MenuRoute` or `LoginRoute`.
- The static fallback `bg.avif` still shows the old scene; replacing it with a capture of the new scene is a UI-side decision.

Do not move route, window, Compose theme, or fallback-resource policy into `:bg-renderer`.

## Low-Performance Policy

`FramePerformanceMonitor` measures the complete frame path (render, resolve, readback, conversion, publication): warm-up `30` frames, sample window `60` frames, fallback when the average exceeds `45ms`. Keep it resistant to isolated spikes, with pure-logic tests for fast frames, isolated spikes, and sustained slow frames.

## Shared GLFW Lifecycle

`client/render-core/src/main/kotlin/calebxzau/rdi/render/GlfwRuntime.kt` coordinates GLFW across `:bg-renderer`, `:player-model`, and UI display-mode discovery. Window creation/destruction is synchronized because GLFW hints are process-global. Acquire a lease before creating a hidden window, destroy the window before closing the lease, and release the context on its own render thread. The home screen can run the background and player-model renderers at the same time.

## Resources

Bundled vanilla textures were extracted from `client/mc/1.21.1-neoforge/build/moddev/artifacts/neoforge-21.1.250-client-extra-aka-minecraft-resources.jar`: `grass_block_top`, `grass_block_side`, `dirt`, `stone`, `snow`, `sand`, `gravel`, `oak_planks`, `oak_log`, `oak_log_top` (currently unused), `birch_log`, `spruce_log`, `oak_leaves`, `birch_leaves`, `spruce_leaves`, `short_grass`, `spruce_planks`, `cobblestone`, `glowstone`, first-frame `lantern`, `environment/sun`, and `environment/clouds`. The user accepted responsibility for redistributing them. Leaves and short grass are grayscale and tinted with Minecraft's plains/birch/spruce foliage colors. The removed cactus textures were archived under `DEL/bg-renderer-golden-valley-phase1-20261014/`. `grass_block_top` is grayscale and tinted by the material color; `grass_block_side` already carries its green fringe.

## Validation

Run Gradle on Windows from `client/ui` (IntelliJ MCP `execute_terminal_command` with `executeInShell=true`, or `pwsh.exe`). Do not use the WSL Gradle environment.

```powershell
.\gradlew.bat :render-core:test :bg-renderer:test :player-model:test :compileKotlin
```

`:bg-renderer:renderPreview` is a dev-only GPU check (needs a desktop GPU, not part of `test`): it renders the real pipeline into `client/bg-renderer/build/bg-preview/frame-*.png`, writes `shadow-depth.png`, and prints scene build/initialize times, vertex counts, average/max frame time over 60 frames, and a shadow-contrast probe (CPU-classified shadowed vs lit grass tops). `-PpreviewTimes=0,15,45` selects the written frames' elapsed seconds.

Last verified 2026-10-14 on an AMD Radeon 780M (integrated): all `:render-core`, `:bg-renderer` (85 tests), `:player-model` tests and `client/ui :compileKotlin` passed. The preview built the scene in ~0.4-0.9s, initialized GL (uploads, shadow bake, fog noise) in ~0.5-1s, and rendered frames in ~5.5-7ms average (fallback threshold 45ms) with ~663k scene vertices (2026-10-14 reference pass A/B: ~5.5-5.8ms with ~668k; after C: ~5.2-5.7ms with ~585k and 87 tests; after D: ~5.3-6.4ms with ~592k and 88 tests; wide view (summit 84, FOV 84): ~6.0ms with ~677k and 89 tests; bigger range: ~7.3ms (max ~12ms) with ~714k). Without the vegetation-first draw order the forest cost ~11ms. The shadow probe showed shadowed grass tops darker than lit ones and the overlay matched the dark strips below terrace steps. The user ran the phase-1 scene in the launcher and confirmed it works; long-running native memory is still unverified.

## Known Tradeoffs

- Readback is synchronous `glReadPixels`; 4xMSAA resolves every frame.
- The reflection is a 640x360 mipmapped HDR texture regenerated per frame.
- Low-sun shadows on top faces are subtle because direct light on tops is small at 16° elevation.
- Distant bands are coarse cubes; where no trees grow, mid-distance ridges show dirt strata stripes.
- Alpha-cutout leaves disable early depth writes for their own fragments; weaker integrated GPUs (roughly a quarter of a 780M) may approach the 45ms fallback with the full forest.
- The framebuffer and output are fixed at `1280x720`.
