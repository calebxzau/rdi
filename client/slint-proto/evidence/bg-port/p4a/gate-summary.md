# P4a gate — 2026-10-31

Implemented account player with idle arm/head/cape pose and main/reflection draws; classic Steve fallback,
HD64px UV normalization, classic/slim/legacy variants. Account skin/cape uploaded, CPU pixels released.
No P4b/P5/section6, sampler/packing/MSAA changes or optimization.

-51 tests + all-feature/all-target Clippy + release app/examples pass; final default frame0 byte-exact.
- Model vertex bytes exactly match Kotlin for every fixture; max model/bone error2.5183e-6.
- Steve full-image RGB MAE1.405/1.391/1.398 per255 at0/15/45; player crop3.129/3.067/3.920 (median1).
- Preview7.374ms mean/9.918ms max,60.19FPS.
- Player pipeline creation main143.27ms/reflection1.25ms on worker; includes translation/compilation/PSO.
- Aligned authenticated static/P3/P4a:101 hosts/60 modpacks, API200, no fallback; glass screenshot saved.
- P4a visible60s WS439.05/private507.45/GPU total390.07MiB.
- P4a-P3 WS+17.082/private+6.898/GPU+0.000/allocated+0.438/reserve+0.000MiB.
- WS/private minus allocated delta+16.645/+6.461MiB. Preserve accounting limits/run drift; native P3 investigation remains P5.

Review p4a-matched-0.0.png (also15/45), p4a-player-slim-cape-15.0.png and measure/p4a-aligned-glass.png.
REPORT.md and summary.json preserve complete tables, raw geometry/pose data and original drifted runs.
Stop here for owner review and P4b choice.
