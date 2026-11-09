# GU4b0 login

Implementation2026-11-07. Owner review pending; do not begin batch b.

## Delivered

Login screen with account/password fields, saved-account history, password reveal
for typed input, Enter submission, busy state, error dialog, registration choices
and forgot-password stub. Login success opens the existing menu. Home/Back cannot
bypass login. The scene stays held while the login screen is visible.

Kotlin exposes login.accounts and login.saved before sign-in. Account metadata is
sorted by last login; passwords stay in Kotlin and saved-account submissions carry
only the ID. Editing either input drops the saved selection. Existing credential
format and Compose production code remain unchanged. Environment variables remain
an optional developer override; failure returns to the page.

## Automated validation

- Rust: renderer61 + library5 + app27 pass; one existing GPU test ignored.
- cargo clippy --workspace --all-targets -- -D warnings passes.
- ui-logic15 and ui-service21 pass, no skips (JUnit XML).
- client-core235 tests, same4 failures as the fresh baseline: three old Fabric
  rejection expectations and MediaProcGameClasspathTest. Kotlin build/test command
  exits nonzero for these baseline failures; no claim of a fully green core suite.
- Shared Kotlin/Rust golden protocol frames updated. Service tests cover sorted
  metadata without passwords, saved-ID lookup, missing ID, blank input and retry
  after a server error. No new protocol event.

## Evidence and boundaries

See owner-checklist.md. Compose reference captures use a fixture-only copy of the
existing LoginScreen; only initial remembered state and function names differ.
The production Compose source is not modified. The Slint gallery fixture is also
display-only; it is not real authentication evidence.

Initial native automation attempts were invalid (old executable/focus/coordinate
issues). They are not acceptance results. Release runs and final screenshots are
recorded below. Chinese IME/caret remains owner acceptance.

No staging, commit, push, updater switch, server logic or other account screens.

## Windows release flow

Final release build passes. `tools/u4b0-live.ps1` passes with no credential
environment variables, real Windows mouse/keyboard (paste into fields), and the
real Kotlin service/API. It uses an isolated service data directory so the
owner's account history is unchanged:

- Empty startup displays login.
- Wrong password displays the server error and permits retry.
- Correct typed credentials plus Enter reach the menu.
- Restart automatically logs in using the saved account, without environment
  credentials. `live/checks.json`, `live/menu.png`, `live/restart.png`.

These are functional checks, not timing/performance measurements. The native
input test uses clipboard paste, so it does not substitute for IME acceptance.

## Visual review notes

Screenshots compare the same logical1280x720 at scales1/2. The Compose fixture
uses a flat background and no title bar; Slint uses the existing scene/glass and
40px title bar. The login panel is480x520, centered in its content area. Shared
Slint control colors/fonts retain the U3 style, so these are visual review
artifacts, not a claim of pixel identity. Saved passwords intentionally show a
hint instead of being copied into the password field.

## Final GU4b0 review package

- All6 real Windows release checks pass, including saved prefill with auto-login
  disabled and clicking login with the saved selection. `live/checks.json`.
- All14 matched state pairs are available in `comparison/`: empty, prefilled,
  history, history-empty, submitting, error, register at scales1/2. Every Slint
  fixture transition was acknowledged before capture. Native DPI and runtime
  heartbeat confirm1280x720 at scale1 and2560x1440 at scale2.
- Release binary SHA256:
  `A532C5BFD16A82BF3137C131347EB945E2B16E6DE53C85935CD80CE6A9073460`.
- Credential scan of evidence logs/control files found no test password. Stored
  credentials exist only in ignored, isolated service-run credential files;
  they must not be staged or published.
- Owner IME/caret, visual fidelity and GU4b0 sign-off remain pending. The remaining
  batch b screens have not started.

## Owner review follow-up (2026-11-07, reviewer)

Owner approved fixing two visual defects found in review:

1. `SharedDialog` (ui/shared.slint) now sizes to its content (160-480px) instead of a
   fixed 240px, removing the ~100px gap above 「明白」. Callers that set an explicit
   height (missing-files dialog) are unchanged.
2. The history dropdown (ui/login.slint) is anchored under the account field, left
   aligned, at most 280px wide, instead of spanning the field and covering the
   password field at a hard-coded y.

`tools/u4b0-visuals.ps1` gained a `-Tag` parameter. New captures: `fix2-scale1/2`;
pairs: `comparison-fix2/`. Release build, `cargo test --workspace` (61+5+27, 1 ignored)
and clippy pass. Kotlin was not touched and not rerun.

Deferred, not fixed: register dialog extra 「取消」 and unoutlined second choice;
history-empty fixture fills 10001 while Compose is empty; caret at the start of the
prefilled account. Owner IME/caret check and GU4b0 sign-off remain pending.
