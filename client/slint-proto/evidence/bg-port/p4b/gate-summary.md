# P4b gate complete — 2026-11-01

Hill crowd ported. Windows tests (48 + 19), Clippy and release builds pass.

- Poses match Kotlin within 3.7e-6; slots and visibility are exact; 25 of 50 actors visible.
- 50-actor frames: full-frame MAE 1.39/1.37/1.39 per 255; actor IoU 0.970/0.980/0.971.
- `--scene-p4a-only` is pixel-identical to the accepted P5 preview at 0/15/45s.
- Frame cost +0.16ms headless (7.452 → 7.611ms), within run spread. No resolved live cost.
- Crowd pipelines about 30ms on the loader worker.
- Visible 60s (MiB): static 199.07 WS / 263.47 private / 209.84 GPU; P5 438.46/509.36/382.04; P4b 419.84/486.40/378.79.
- Named crowd allocations 0.9375MiB. The memory cost is below process-counter drift; lower P4b values are not a saving.
- Live run: 50 of 50 real skins loaded, all API 200, no WARN or ERROR, all endpoints focused.
- Fallback releases named crowd allocations and keeps static glass. In-flight download cancellation was not exercised live.

Review p4b-steve-compare-*.png, p4b-actors-*.png, mixed-*-crop-15.png and measure/final-crowd-r1-glass.png.
Section 6 and 30FPS are not started.
