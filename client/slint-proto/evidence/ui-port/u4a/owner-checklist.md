# GU4a owner checklist

Scope: local archive/folder/empty-pack import, installed RDI/local packs, advanced
options, local/RDI launch, real tasks and console. Stop here; batch b is not started.

## Implementation boundaries

- Compose screens remain in production. Two view models and their focused tests
  moved to `ui-logic` with unchanged packages; their original files are preserved
  under `DEL/u4a-viewmodels-20261106/`. Compose DI and screen callers still compile.
- Kotlin keeps scanner/archive/install/launch/account/storage rules. Rust displays
  snapshots, controls navigation, and opens native pickers/browser/folders.
- Both pack types use the existing game controller and duplicate/stop/close rules.
  Local packs disable the RDI runtime and development core override.
- No local storage-format, backend, updater, packaging or scene-content changes.
- Owner-approved: directory action replaces the cut configuration editor. Migration,
  reinstall, sharing, log-pack export and removal wait for batch d. Console-text
  export is implemented here. Info/content actions still invoke `stub`; no quest entry.

## Windows live checks

Evidence in `live-1/` is the first implementation build, before layout/state follow-ups.
It is kept separately from final-build screenshots and checks.

- [x] Native archive picker opens at Downloads with zip/mrpack filter.
- [x] Import `fixtures/u4a-test.mrpack`; task completes and its override text matches.
- [x] Native directory picker imports `fixtures/game-folder`; copied config text matches.
- [x] Both packs appear in the installed tab (initial5, then6, then7 entries).
- [x] Change the imported directory pack's Forgeguard option, save, leave and reopen:
  the checkbox remains selected (`options-reloaded.png`).
- [x] Offline local launch reaches the Minecraft1.21.1 title screen
  (`offline-title.png`); only Minecraft/NeoForge are loaded, no RDI core.
- [x] RDI pack `1.21原版`1.0.0 starts from the tab and reaches its title screen
  (`rdi-title.png`). No world was entered or modified.
- [x] Local duplicate start shows confirmation; cancel preserves the running game.
- [x] Console receives the actual local-launch output (124 lines at the capture).
- [x] Final build repeats offline and RDI title-screen launch (`final-live/`).
- [x] Final build creates an empty pack, returns to the installed tab and opens
  its real task automatically (`final-live/automatic-task.png`).
- [x] Settings still persist after restarting the launcher/service; dirty-back
  confirmation, discard, cancel and save-and-return were exercised.
- [x] Real console text exports through the native save dialog
  (`final-live/console-export.log`,17781 bytes).
- [x] Native DPI changes produce real1280x720/scale1 and2560x1440/scale2 windows.
- [ ] Microsoft local launch: test account has no bound msid. Not verified.

## Visual review

`compose-scale1/` and `compose-scale2/` contain19 screenshots each, using the real
Compose components in the test-only `U4aReference` harness. These are inert display
fixtures, not business or network acceptance. Their physical dimensions are
1280x720 / 2560x1440 with logical1280x720 at both scales.

Review each matching Slint screenshot beside its Compose reference. The reference
background is a plain color; it does not measure or compare background rendering.

Accepted fixture captures are `final-visual-r3-scale1/` and
`final-visual-r3-scale2/`:22 Slint captures each, with19 matching Compose pairs
per scale under `comparison-scale1/2/`. Slint's extra cases are local-login
busy/error and an empty task. No online login is claimed from these fixtures.
`installed-live.png` in each comparison folder adds the real Compose/Slint
installed tab. Their pack counts differ because the empty-pack check occurred
between captures; background animation and online-player rows are also temporal.
The earlier `final-visual*` and `final-visual-r2*` batches are superseded: the
initial harness did not wait for each diagnostic control acknowledgement.

Both scales passed native modal interaction checks (`modal-scale1/2/`): the
title-bar sample pixel dims from RGB146/188/234 to58/75/94, and clicking the
Toast close button dismisses it while the underlying dialog stays open.

- [ ] Import sources, loading, empty and read error.
- [ ] Multiple-version choice; reading preview; directory and empty-pack editors.
- [ ] Content list, no contents, optional selection and missing-file confirmation.
- [ ] Advanced options normal/loading/error; save/discard/cancel interaction.
- [ ] Local-play dialog, nickname warning, disabled Microsoft mode.
- [ ] Console normal/empty; task progress/error/empty and group expansion.
- [ ] Entire title bar dims under a modal; Toast remains above it and closable.
- [ ] Empty/failed content grid has no scrollbar thumb.
- [ ] New fields: Chinese IME, caret/selection and native scale changes.

Name sorting stays lexicographic, matching Compose's `sortContentGridItems`.
Installed packs stay in descending installation-time order. No natural-number sort
was introduced.

## Validation

Fresh baseline: Rust renderer61 + library4 + app23 passed,1 GPU test ignored;
client-core235 tests with the same4 pre-existing failures; existing view-model
focused baseline7 passed. Initial unqualified Gradle `test --tests` also selected
unrelated subproject tests and failed on no matching tests; the corrected `:test`
baseline passed. This is not counted as a product regression.

Final counts, artifact hashes and any remaining limits are recorded in
`gate-summary.md` after validation. Owner sign-off remains pending.
