# GU1 owner checks

Build: from client/slint-proto on Windows, run:
```powershell
.\target\release\rdi-slint-proto.exe --ui-proof
```
No environment credentials are needed. The form calls the real API only when submitted.
The default menu and camera remain unchanged when --ui-proof is absent.

## Form and Chinese IME

1. Click RDID/QQ号. Press Tab and Shift+Tab between account/password/login and the room-name field.
2. Submit empty fields: expect 未填写完整. Fill your account and password; password must be masked.
   Press Enter in either login field: expect 登录中.., then 登录成功 or an observable error.
   Repeated Enter while busy must not start another request.
3. Enter 房间名称 using Windows Pinyin: type "pengyou de fangjian", change a candidate, select text,
   move the caret to the middle, and resume composition. The candidate window must follow the caret.
4. Repeat while maximized and after moving the window to a screen with another DPI scale.
5. Select/copy/paste Chinese and Latin in account and room-name fields; select/copy the read-only RDID.
   Password clipboard behavior follows the platform password control; do not include credentials in screenshots.

## Panels, text and window controls

1. Open/close the room panel. Check the glass follows all edges during its 300ms slide and scale.
   At scale 1, 1.25 and 2 check corners/edges against the content (within 1 physical pixel).
2. Open the dialog. Try clicking/scrolling/tabbing into the controls behind it; they must stay blocked.
   Confirm the dialog and return to editing. Check text remains readable over the dimmed background.
3. Inspect Chinese, Latin, Nerd Font icons, the wrapping paragraph and the elided long name.
   Scroll the left form if the window is short. Confirm no missing glyph boxes.
4. Drag the title bar; double-click it to maximize/restore. Use minimize/maximize/close buttons.
   Drag each edge and the bottom-right corner to resize. Restore after minimizing.
5. Move between physical monitors with different DPI scales. This requires real hardware;
   injected WM_DPICHANGED captures cannot establish how the transition feels.

## Focused list measurement

Keep the monitor on and the desktop unlocked. Click "开始30秒滚动与动画检查".
Leave the app in the foreground for the full 40 seconds. It scrolls the actual 1000-row ListView
for 30 seconds and toggles the panel for 10 seconds. An unfocused or missing-scene frame invalidates
the run; the logs explicitly report both counts. Run three times after warm-up.
Use tools/u1-runtime.ps1 -Tag <new-tag> for foreground acquisition, logs, memory and screenshots.

## Platforms

The workflow is prepared but cannot run until the owner authorizes a push.
No CI account secrets are used. A successful CI screenshot does not verify IME, DPI feel, performance
or real Apple/Linux GPU drivers. Review each runner's adapter log and window-result.json.
