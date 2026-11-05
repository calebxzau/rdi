# P5 native-memory attribution — focused authenticated controls

All accepted visible endpoints have actual Windows foreground focus and matching application focus,
with a live bound scene. Every case loaded100 hosts/60 modpacks; original raw logs/allocator reports and
Rust counters remain under measure/. These controls use one fixed allocation-probe executable and
identical flags, except the explicitly selected stage/appearance path.

## Repeat-baseline limit

P3 repeat versus first baseline: WS-20.891MiB,
private-18.848MiB; placed resources/reserve unchanged, Rust live delta
+0.023MiB. Thus the observed sequential drift in this batch is about
19MiB private/21MiB WS, slightly larger than the historical8–16MiB note. Do not interpret one control's
10–20MiB change as a fully attributable pipeline cost or summable memory saving.

## P3 overhead and stage omissions

P3 versus P2 private delta is85.445MiB with the first baseline or
66.598MiB with the repeat. Placed allocation increases
16.938MiB; Rust live request increase is only
0.645MiB (first baseline). The remaining native/private difference
is therefore roughly49–68MiB in these runs, consistent in scale with the older~46MiB unresolved item,
not a newly proven retained CPU image/mesh/noise buffer.

Every omitted stage was checked absent from pipeline creation logs. All target/noise allocations stay
present. Relative to the first P3 baseline, omission reduces private by~16MiB bloom,34MiB fog,21MiB copy,
36MiB mip,18MiB final. With the repeat baseline, bloom/copy/final are close to zero difference and fog/mip
are~15–17MiB lower, near the observed drift. No single-stage result isolates the entire~46MiB residual,
and these numbers must not be added or used as a promise of optimization savings.

## Creation versus execution

Creating all post pipelines/resources but never executing them gives private452.570MiB,
versus rendered P3503.188..522.035MiB. The difference is
50.617..69.465MiB, larger than baseline drift;
placed resources and heap reserve are exactly the same. Rust live changes by only
0.089..0.066MiB.
Windows GPU total is377.395MiB unrendered versus390.066MiB rendered
(delta12.672MiB). This supports an execution-associated native
commitment/residency/driver cost, rather than shader creation alone or retained Rust source pixels.
These counters cannot divide that cost into native driver objects, compiler allocations, unused heap
pages and resource residency. A native stack/residency trace would be required for that finer claim.
This is the attribution boundary, not evidence that all of the residual is a memory leak.

## Player and account download control

Full account appearance versus local Steve: WS-6.117MiB,
private-0.445MiB, Rust live
+0.107MiB; placed allocation is unchanged. These are within
the measured drift and show no stable additional private cost from the scene skin/cape download/decode.
The control disables only extra scene appearance requests; account/owner heads for the unchanged menu
still use their normal skin downloads. Explicit decoded appearance pixels are consumed after upload.

Steve versus P3 has WS-5.410MiB against the first baseline and
+15.480MiB against the repeat; private likewise spans
-8.742..+10.105MiB. The fixed player GPU payload
is0.087MiB before native alignment; this batch does not isolate the historical+17MiB WS as a stable
pipeline or decoder allocation. Do not label that entire old WS step as retained skin/cape data.

## Scope

P5 measures and reports; no driver/cache/heap or shader optimization, sampler change, Manual device,
30FPS default, or crowd was introduced. Re-run matched controls after section6 changes to see whether
the execution-associated residual and actual busy counters move. Current report keeps OS process,
Windows GPU, wgpu placed allocation/reserve and Rust live counters separate.
