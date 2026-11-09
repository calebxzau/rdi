# GJ1 owner checklist

D1-D5 approved by the owner on2026-11-09. J2 has not started.

- [x] (Owner confirmed 2026-11-09: joining a room works.) With the monitor on, start a real game from a Slint room and join normally. Confirm the access token supplied by `session.set` works. This is the only check that proves the token: the pack tab reads local data. If the game reports `Failed to find a primary monitor`, stop that check.
- [x] (Owner confirmed 2026-11-09: rooms are playable.) Compare the room list with the Compose launcher: rooms shown as playable there are playable in Slint (plan acceptance item 6). The automated run did not compare them room by room.
- [x] (Owner confirmed 2026-11-09: system proxy works.) If you use a system proxy: enable `系统代理` and confirm Slint can sign in and load rooms through it. A custom proxy is covered by a test; a system proxy could not be tested without changing your Windows settings.
- [ ] Verify both directions using an isolated data directory: Compose reads an account saved by Slint, and Slint reads an account saved by Compose. Shared fixture tests prove format compatibility; they do not replace this interactive check.
- [ ] Review measured hardware collection time and empty fields in gate-summary.md and decide whether they are acceptable.
- [ ] Review GJ1 implementation and acceptance evidence, then authorize the next phase separately.

GU4b0 IME/caret/visual sign-off remains a separate pending owner gate.
