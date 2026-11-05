# P4b plan: golden-valley hill crowd

Approved by the owner on 2026-11-01. The earlier handoff plan in `/tmp` no longer exists, so this is the
source of record for P4b. VRAM does not count toward the ~100MB target (owner decision, same date).

## Scope

Port the Kotlin hill crowd into this prototype only. No Kotlin, Compose, `common` or server changes.
Accepted sampler, resolution, MSAA, packing and 60FPS defaults stay unchanged.

Behaviour to match `HillCrowdSimulation.kt`, `GrasslandCrowdRenderer.kt` and `BackgroundCrowdLoader.kt`:

- Up to 50 skins on the left-slope, summit-lap, right-slope route, with stable time slots.
- Running limbs, riser lift, smooth turns, and a contact shadow in the main pass only.
- Actors are also drawn in the water reflection, with the same clip plane as the account player.
- Skins come from `player/unique-skin-list` and appear incrementally. Steve stands in until the first
  real skin arrives, or stays when the list is empty or fails. A failed skin is logged and skipped.
- Inactive time does not advance the crowd, and one frame advances it by at most 0.1s.

## Design

| Part | Choice |
|---|---|
| Simulation | `src/scene/crowd_sim.rs`, a pure port with no GPU dependency |
| Mesh | Existing player mesh variants, excluding the 36 cape vertices |
| Skins | One 64×64×50 sRGB texture array, about 0.8MiB |
| Per-actor data | 40-byte instance records, 2KB in total, written once per rendered frame |
| Bones | Computed from the stride pitch in `crowd.wgsl` |
| Draws | Grouped by mesh variant, at most 4 draws per pass plus one shadow draw |
| Loading | Global limit of 4 and the existing per-host limit of 3; CPU pixels released after upload |

Diagnostics: `--no-crowd` and its alias `--scene-p4a-only` skip crowd resources and skin requests.

## Accepted differences from Kotlin

- Variant grouping changes the draw order between actors, so overlapping semi-transparent skin pixels
  can differ slightly.
- Per-host download concurrency is 3, because forgecdn resets TLS beyond about 5 parallel connections.

## Gate

Stop for owner review after all of these are recorded in `REPORT.md` and
`evidence/bg-port/p4b/gate-summary.md`:

1. Windows tests, Clippy on all targets and features, and release builds pass.
2. Route, slot and pose data match a Kotlin dump within tolerance.
3. 50-actor frames at 0/15/45s are compared with a matched Kotlin capture.
4. `--scene-p4a-only` frames match the accepted P5 executable.
5. Static, accepted P5 and P4b authenticated memory runs use the strict P5 focused protocol, with API
   results and a glass screenshot.
6. Frame cost and new pipeline creation times are reported.
