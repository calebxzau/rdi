# GU4a — local modpacks

2026-11-06. **GU4a passed with owner approval on 2026-11-07**, after the follow-up fixes at the
end of this file. Items 6-9 of the review (visual differences) are deferred; see UI-PORT-PLAN.md.

## Delivered

- Real installed RDI/local pack tab; archive/folder/empty-pack import; advanced
  launch options; offline/Microsoft account-choice dialog; real tasks and console.
- Native asynchronous `rfd` file/folder/save dialogs; directory/browser actions.
  No AWT dialogs in the service. Kotlin retains archive, installation, account,
  persistence and launch rules; two existing view models moved to `ui-logic`.
- New framed methods/events and shared Rust/Kotlin golden corpus. Conflated
  snapshots, bounded console text, terminal task-watch shutdown and connection
  generation rejection. Task shutdown cancels and waits for cleanup.
- Modal scrim covers the title bar; Toast stays above it; empty/failed grids have
  no stale scrollbar thumb. Name sorting stays lexical, matching Compose.
- Compose remains usable and compiles. Local pack metadata format is unchanged.
  Directory action is included; owner-approved extra management actions wait for
  batch d. No quests/configuration-editor entry; info/content retain `stub`.

## Checks

| Check | Result / evidence |
|---|---|
| Rust workspace tests | Renderer61 + library5 + app24 pass;1 existing GPU capture ignored; `rust.log` |
| Clippy / release | Pass, `--workspace --all-targets -- -D warnings`; `clippy.log`, `release.log`, `rust.exit.json` |
| Kotlin logic / service |15 /18 pass, zero failures/errors/skips; JUnit XML and `kotlin.log` |
| Compose compilation | Pass with service distribution build; `kotlin.log` |
| Existing client-core |235 tests, same4 baseline failures; fresh baseline and `final-client-core.log`. Not reported as passing |
| Golden frames | Shared updated corpus passes in both languages |
| Native import | mrpack and directory imported through actual Rust native pickers; copied override/config bytes checked; `live-1/` |
| Options persistence | Changed Forgeguard checkbox persists after leave/reopen and full launcher/service restart; `options-reloaded.png`, `final-live/options-persisted.png` |
| Local launch | Final release reaches Minecraft1.21.1/NeoForge title screen without RDI core; `final-live/offline-title-final.png` |
| RDI launch | Final release starts `1.21原版`1.0.0 from installed tab to its RDI title screen; `final-live/rdi-title-final.png` |
| Duplicate / stop | Local duplicate confirmation/cancel preserves one game; stop used for both test games. No world was entered |
| Empty pack / task | Final build creates empty pack, returns to installed tab and automatically opens real completed task; `final-live/automatic-task.png` |
| Console | Actual124-line local game log, native-save export17781 bytes; `final-live/console-final.png`, `console-export.log` |
| Modal / Toast | Both scales pass title-bar dimming and actual Toast-close click with dialog left open; `modal-scale1/2/exit` |
| Scale screenshots |19 Compose +22 Slint per scale;38 paired comparisons; `comparison-scale1/2/`, `visual-validation.json` |

The known core failures are LocalModpackScannerTest, ModpackUploadRuntimeGuardTest,
ModpackArchiveReaderTest (old Fabric-rejection expectations), and
MediaProcGameClasspathTest. No failures were hidden or changed for this port.

## Review boundaries

- Microsoft launch is **not verified**: the test account has no msid. Its unavailable
  mode is displayed and no binding feature from batch b is added.
- Screenshots for loading/empty/error/optional/missing-file states use explicit
  display fixtures through real components. They demonstrate rendering, not live
  remote failures or successful Microsoft authentication. See `owner-checklist.md`.
- Review builds use the existing glass controls. Their typography, color, spacing
  and task/dialog composition still need owner visual approval against the supplied
  comparisons; pixel-identical parity is not claimed. New-field IME/caret review
  remains an owner check. No Mac/Linux or long-duration stability acceptance.
- `live-1/` is an earlier build, retained for native archive/folder and duplicate
  evidence. `final-live/` repeats both game launches, persistent options, real
  console/export and empty-pack/task behavior using the final Rust release.
- `final-visual-r3-scale1/2/` is the accepted fixture batch: native DPI and physical
  dimensions verified. Earlier visual attempts are superseded; the harness now
  waits for each control acknowledgement. Compose fixtures use a plain background.
- Full Compose launches after the moves were successful. Real installed-tab captures
  are under `compose-menu/`; fixture captures are separate.
- Two additional real installed-tab comparison images are
  `comparison-scale1/installed-live.png` and `comparison-scale2/installed-live.png`.
  They were captured at different times (7 versus8 installed packs), so do not use
  their item count or animated background as a parity assertion. Visible review
  differences include fallback-icon color/version badges and form/dialog spacing;
  owner visual acceptance is still pending.
- No staging, commits, pushes, backend changes, updater/package switch or permanent
  deletion. The four moved originals are archived under ignored
  `DEL/u4a-viewmodels-20261106/`, preserving their original paths.

## Release memory — information only

One Windows authenticated release run with7 real installed packs, live scene,
scale2.0,2560x1440. Five paired samples verify OS foreground plus application
focus/visibility. Medians:

| Process | Working set | Private |
|---|---:|---:|
| Rust UI |504.84MiB |494.05MiB |
| Kotlin service |263.09MiB |309.35MiB |

`final-live/memory.json` preserves every sample. These are short process-memory
observations, not retained heap, VRAM, FPS or evidence of memory growth. No
performance improvement/regression conclusion is drawn against GU3. Startup in
this run: first UI frame667ms, first live scene1819ms; a single launch only.

## Artifacts

Final release executable SHA256:
`B79BC1BDA8C138D559182ACB36EE1A37737D1E8B1CF1C5DE4419635B6150CF8B`.

Final service SHA256:
`BD1CFE767E17AE7AF107D3E6C676A3135A66A22E5303612B10E146B605F15265`.

Final logic SHA256:
`62E3C02E4380938BC373D3452C5B0DF6A2B8354C9E4A78A724707A16FC615016`.

Start from this directory with `target/release/rdi-slint-proto.exe`; the service
distribution is in `../ui/ui-service/build/install/ui-service`. Read
`owner-checklist.md` before signing off. Do not start batch b until owner approval.

## Owner review follow-up (2026-11-07)

Review of the comparison images found five defects; all fixed, uncommitted:

1. Console and task panels are centered with a 24px margin; the stray 「关闭」 below the panel is
   gone (task panel closes with 「隐藏到后台」, console has its own × and refresh in its header);
   console session buttons sit in the header, the selected-line viewer only appears once a line
   is selected, and the last line no longer touches the panel edge. Task list column only when
   there is more than one task.
2. Advanced options: conditional fields use `if` (an invisible element still took 84px in the
   layout), jvm-param placeholder is inside the field, the layout keeps clear of the scrollbar,
   title shows name + version name like Compose (`测试整合包 1.21.1·设置`).
3. Content cards carry Compose's selection state: required = fixed check, optional = check/×
   pair, selected rows highlighted, unselected optional text dimmed; icon mode shows a check
   badge. The ☑/☐ prefix (missing from the font) and 「点击切换」 text are removed.
4. Selected size uses Kotlin `humanFileSize` units (1.0KB, not 0.0MiB).
5. Missing-file dialog title shows the count, items are bulleted, optional files get
   「（可选）」 (Kotlin snapshot), and the page shows the red 「有N个文件无法自动下载，导入时会列出」 line.

Checks: Rust 61 + 5 + 26 pass, 1 ignored (2 new tests: size units, options title); clippy
`-D warnings` pass; ui-service 18 / ui-logic 15 pass; release build and `:ui-service:installDist`
pass. The final one-line console margin change was built in release but tests/clippy were not
rerun after it. Fixture captures `fix2-scale1/2`, pairs `comparison-fix2-scale1/2` (19 each).
Not re-verified live: optional check/× clicks and the 「（可选）」 suffix on a real CurseForge pack.
Release SHA256: `36D529D55C7B51CC8B45556A36C455C7C9F88E30BB2A21E0C5AED38E43633E4F`.
