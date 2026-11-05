"""Write the P5 gate report only after strict runtime and measurement summaries pass."""
from pathlib import Path
import json
R=Path(__file__).resolve().parent.parent
E=R/'evidence/bg-port/p5'
s=json.loads((E/'summary.json').read_text())
assert s['runtime']['status']=='passed' and s['measurements']['status']=='passed'
assert (E/'native-dpi-complete.exit').read_text().strip()=='0'
c=s['measurements']['cases'];d=s['measurements']['deltas'];p=c['final-scene'];baseline=c['final-static'];t=s['preview_timing']
assert (p['hosts'],p['modpacks'])==(baseline['hosts'],baseline['modpacks'])
text='''## Golden-valley port P5 — runtime policy and final report (2026-10-31)

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
'''
for name in ['fail','slow','loader-fail']:
 v=s['runtime']['cases'][name]['phases']['released_resources']
 text+=f"|{name}|{v['snapshots']}|{v['last_allocated_mib']:.3f}|{v['last_reserved_mib']:.3f}|\n"
text+='''
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
'''
for phase in ['foreground60s','minimized30s','restored10s']:
 for name in ['final-static','final-scene']:
  v=c[name]['endpoints'][phase]
  text+=f"|{name}/{phase}|{v['WorkingSet']:.2f}|{v['Private']:.2f}|{v['Dedicated']:.2f}/{v['Shared']:.2f}|{v['gpu_total_mib']:.2f}|{v['allocated_mib']:.3f}/{v['reserved_mib']:.3f}|{v['Focused']} / {v['ActualMinimized']}|\n"
text+='\n|Full scene minus static|WS|Private|GPU total|Allocated|Reserve|WS minus allocated|Private minus allocated|\n|---|---:|---:|---:|---:|---:|---:|---:|\n'
for phase in ['foreground60s','minimized30s','restored10s']:
 v=d[f'final-scene-minus-static-{phase}']
 text+='|'+phase+'|'+'|'.join(f"{v[k]:+.3f}" for k in ['WorkingSet','Private','gpu_total_mib','allocated_mib','reserved_mib','ws_minus_allocated_mib','private_minus_allocated_mib'])+'|\n'
text+=f"\nBoth final cases loaded{p['hosts']} hosts/{p['modpacks']} modpacks, with{baseline['api_responses']}/{p['api_responses']} observed API responses, all HTTP200. Full-scene account skin/cape was ready before the visible measurement. The final glass screenshot and runtime fallback screenshots were visually inspected. These are renderer/API/data checks, not exhaustive host/tile-action tests.\n"
a,b=baseline['endpoints']['foreground60s'],baseline['endpoints']['restored10s']
text+=f"\nStatic reserve in this batch changes{a['reserved_mib']:.0f}→{b['reserved_mib']:.0f}MiB after minimize/restore; it is measured and left unchanged, as required. The historical103→164MiB value is not substituted when allocator block layouts differ.\n"
text+='''
### Native-memory attribution controls (authenticated app)

The fixed allocation-probe executable records live Rust requests plus the same native/OS counters.
P3 controls have no player. Each skipped stage truly omits pipeline creation and execution; creation
logs are checked, while target/noise/bind-group allocations remain. Missing-stage images are deliberate
diagnostic controls, not alternate product quality. The create-only variant constructs all five post
pipelines/resources but does not execute them and uses prior tonemap. P2/P3 and Steve/full-player controls
run under identical focused60s endpoints. Rust live requests are not Windows heap capacity/private bytes.

|Control|WS|Private|GPU total|Allocated|Reserve|Rust live requests|
|---|---:|---:|---:|---:|---:|---:|
'''
for name in ['ab-p3','ab-skip-bloom','ab-skip-fog','ab-skip-copy','ab-skip-mip','ab-skip-final','ab-unrendered','ab-p2','ab-steve','ab-scene','ab-p3-repeat']:
 v=c[name]['endpoints']['foreground60s']
 text+='|'+name+'|'+'|'.join(f'{v[k]:.3f}' for k in ['WorkingSet','Private','gpu_total_mib','allocated_mib','reserved_mib','rust_live_mib'])+'|\n'
text+='\n|Control delta|WS|Private|Allocated|Reserve|Rust live|Private minus allocated and Rust|\n|---|---:|---:|---:|---:|---:|---:|\n'
for key in ['skip-bloom-minus-p3','skip-fog-minus-p3','skip-copy-minus-p3','skip-mip-minus-p3','skip-final-minus-p3','unrendered-minus-p3','p3-repeat-minus-p3','ab-p3-minus-ab-p2','ab-steve-minus-ab-p3','ab-scene-minus-ab-steve']:
 v=d[key]
 text+='|'+key+'|'+'|'.join(f'{v[k]:+.3f}' for k in ['WorkingSet','Private','allocated_mib','reserved_mib','rust_live_mib','private_minus_allocated_and_rust_mib'])+'|\n'
text+='''
Interpretation and exact limits are recorded in attribution-notes.md beside the raw controls. The table
separates measured associations from inferred causes: a reduced private count after omitting one shader
is not a native stack attribution, and baseline drift/heap block changes must be considered. No source
images/meshes/noise were retained to account for the large process residual; decoded player pixels die
after upload. The Steve control removes additional scene skin/cape requests while existing UI head
requests remain unchanged. This phase reports the memory cost; it does not optimize or speculate that
all residual bytes are a leak. Recheck these controls after the separately scoped section6 changes.

### Frame cost, GPU busy, power and policy proposal

'''
text+=f"Default headless preview: mean{t['mean_ms']:.3f}ms/max{t['max_ms']:.3f}ms, {t['cadence_fps']:.2f}FPS. Final app completion windows mean{p['completion_mean_ms']:.3f}ms/max sampled{p['completion_max_ms']:.3f}ms. Both include CPU encode plus submit-to-completion; the app also shares Slint's queue. They exclude presentation and are not pure GPU duration. No sustained45ms fallback in normal controls.\n\n"
text+='|Runtime phase|Process3D engine utilization mean (%)|\n|---|---:|\n'
for phase in ['focused-live','unfocused','hidden','minimized','refocused','restored']:
 v=s['runtime']['cases']['policy']['phases'][phase];text+=f"|{phase}|{v['mean_3d_percent']:.2f}|\n"
text+=f"\nFinal visible60s static/full-scene3D utilization averages{baseline['focused_3d_mean_percent']:.2f}%/{p['focused_3d_mean_percent']:.2f}% (Windows per-process3D engine counters). Activity transitions can contribute to a phase's first sample; stable hidden/minimized samples are idle. Do not infer watts from this counter or GPU busy from11ms/16.67ms. The queried built-in Windows interfaces expose no GPU-specific watts/temperature; the generic thermal-zone reading cannot be assigned to the GPU or scene. No new sensor tools installed.\n"
text+='''
The actual busy delta makes a30FPS scene worth proposing for a later visual/power A/B, while retaining
display-rate glass/UI and reusing scene/frost on alternate frames. It should reduce the scene-work
component, but no30FPS utilization/temperature/power benefit was measured here. Leave60FPS and the
live-scene default unchanged pending owner review and section6 data; no power-saving claim or policy
change is hidden in this phase.

### Startup timeline and player compile cache

|Final case|First static/UI frame (ms)|First live scene (ms)|
|---|---:|---:|
'''
for name in ['final-static','final-scene']:
 v=c[name]['startup_ms'];text+=f"|{name}|{v.get('First frame','n/a')}|{v.get('First live scene frame','n/a')}|\n"
text+='''
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
available and are not accepted results. Review measure/final-scene-r1-glass.png and the policy-native-dpi
screenshots, plus p5-matched-*.png and gate-summary.md.
**Stopped at the completed P5 gate; P4b deferred, section6 and30FPS changes not started.**
'''
text=text.replace('measure/final-scene-r1-glass.png',f"measure/{p['prefix']}-glass.png")
report=R/'REPORT.md';old=report.read_text();report.write_text(old.split('## Golden-valley port P5')[0]+text)
a=p['endpoints']['foreground60s'];v=d['final-scene-minus-static-foreground60s']
(E/'gate-summary.md').write_text(f'''# P5 gate complete — 2026-10-31

Runtime focus/hide/minimize/resume, resize/native DPI message, validation/loader/low-performance terminal
fallback, static glass continuity, no restart and release of named scene GPU allocations passed.
54 tests, Clippy and release builds pass. Default0/15/45 pixels unchanged from accepted P4a.

Focused authenticated static/full scene and all P3 per-pipeline/created-unrendered/P2/Steve/account
controls complete. Full scene visible60s WS{a['WorkingSet']:.2f}/private{a['Private']:.2f}/GPU{a['gpu_total_mib']:.2f}MiB,
allocated{a['allocated_mib']:.3f}/reserved{a['reserved_mib']:.0f}MiB. Full-static WS{v['WorkingSet']:+.2f}, private{v['Private']:+.2f},
GPU{v['gpu_total_mib']:+.2f},allocated{v['allocated_mib']:+.3f}MiB. All final visible/restored endpoints actually focused.
See summary.json and attribution-notes.md for native residual/control deltas, repeat-baseline drift and limits.

Preview{t['mean_ms']:.3f}ms mean/{t['max_ms']:.3f}ms max; live completion-window mean{p['completion_mean_ms']:.3f}ms.
Actual3D busy/pause counters support proposing a later30FPS scene A/B, with no default change or watts claim.
Player compile-order reversal confirms first-compile vs device shader-cache reuse.

Inspect final scene glass and policy-native-dpi screenshots; native DPI injection is not a physical
multi-monitor test. Named allocations disappear on fallback; unused device heap reserve/cache may remain.
P4b remains deferred; stop at P5 before section6/default decisions.
''')
print('P5 report and gate regenerated from successful evidence')
