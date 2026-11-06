# GU3 owner review

Open `target/release/rdi-slint-proto.exe --gallery` from `client/slint-proto`.
No credentials are needed for the component gallery. This gate does not authorize U4.

1. **Buttons and fields.** Inspect normal/hover/pressed/disabled/focused/loading buttons,
   circle icon actions, toggles, long Chinese labels, error and disabled fields.
   In the room-name field, use your Chinese IME to compose `我的世界朋友房间`;
   check the candidate window follows the caret. Test Tab/Shift+Tab, selection,
   Ctrl+A/C/V and Enter. In the sample password field, check masking and the eye button.
   Automated paste/keyboard checks do not establish IME acceptance.
2. **Dialog and toast.** Open the dialog by its button. Check Tab stays inside,
   Enter activates the focused button, Escape dismisses, and the dimmed page does
   not react. Show a toast; dismiss it or wait five seconds.
3. **Content and images.** Search, change type filters, reverse name order, and
   switch list/icon modes. Inspect normal/loading/empty/error states. Scroll by
   wheel and drag the shared scrollbar. Inspect image loading/empty/error and the
   downloaded Steve head; no account API is required for that public test image.
4. **Console and tasks.** These are explicitly gallery fixtures. Select/copy a log
   line, copy all, export a sample log to `evidence/ui-port/u3`, clear the console,
   inspect severity colors and tail-follow. Collapse/expand the task group and
   inspect queued/running/done/failed/cancelled rows and progress. Real service
   console/task events remain U4 work.
5. **Glass tiles.** Wheel and drag the horizontal scrollbar, hover/press tiles,
   inspect dimmed/status/owner-head states and check no glass leaks beyond the
   left viewport. Check both 1x and 2x captures in `finished-scale1`/`finished-scale2`.
6. **Shell.** Review title drag, minimize/maximize/close and edge/corner resize.
   With test-account environment supplied, Home opens the real menu. Change tab
   and scroll, open Gallery, then Back: menu state should remain. The scene moves
   only on the menu and holds on the gallery/activity panel or while a game runs.
   U4 destinations still call the stub callback; cut features have no entries.

Record GU3 acceptance or requested corrections. Do not proceed to U4 until accepted.
