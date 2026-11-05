# P3 review gate — 2026-10-31

Implemented low valley fog, bilateral HDR composition, mips, bloom atlas and final post. Accepted samplers,
MSAA off,24B packed geometry and1280×720 remain unchanged. No P4/P5/section6 work.

- Windows45 tests, Clippy all targets and release build passed.0/15/45,4x/full diagnostic previews pass.
- Noise volume byte-exact against Kotlin. Matched full RGB MAE1.403/1.388/1.396 per255.
- Left-lake mist MAE~13 before ->~2 after. Review `p3-matched-0.0.png`, other time comparisons and valley crops.
- Preview7.628ms mean /10.481ms max,60.19FPS. Authenticated app glass: `measure/p3-glass.png`.
- Controlled authenticated static/P2/P3 complete:101 hosts/60 modpacks, all observed API200.
- P3 visible60s WS468.91 /private512.35 /GPU total374.00MiB.
- P3-P2 WS+59.328 /allocated+17.250 /GPU+9.047MiB.
- Init reserve167->231MiB adds64MiB block; steady paired P2/P3 reserve215/207MiB, Windows GPU step9.047MiB.
- WS-minus-allocated delta42.078MiB triggered extra CPU/native attribution. See REPORT.md for measured limits.

Detailed data: `summary.json`, `measure`, `cpu`, `cpu-app` and REPORT.md. Stop at P3 for owner visual review.

CPU attribution: the complete tracked app retains only+0.646MiB Rust requests in P3, excluding a retained
noise/decoded-image/mesh explanation. Private delta~64MiB repeats; WS delta falls to29.715MiB. Native
allocation/heap capacity/residency is not split into individual driver objects by these counters. See
REPORT.md for exact limits; no speculative source-data cleanup or out-of-scope optimization was applied.
Final normal executable preview matches the original P3 frame0 byte-exactly.
