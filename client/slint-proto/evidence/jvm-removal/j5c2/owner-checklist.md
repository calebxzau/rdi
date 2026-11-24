# GJ5c2 owner checklist: 正版登录 in the Slint launcher

Needs an RDI account bound to a Microsoft profile that owns Minecraft. Use a local pack (the play
dialog of a pack on the 整合包 tab). Online sessions are saved in `<data dir>/msa_game_sessions.json`.

1. **First login.** Choose 正版登录, 开始游戏. The browser opens the Microsoft page with the code
   filled in, and the dialog shows the link. After signing in the game starts with your Minecraft
   name; `msa_game_sessions.json` now has your account.
2. **Saved session.** Close the game, start again with 正版登录: no browser, the game starts at once.
   The dialog remembers 正版登录 for this pack.
3. **Token refresh.** Close the launcher, set `mcTokenExpiresAt` in `msa_game_sessions.json` to a
   past value (for example `1`), start the launcher and play online again: no browser, the game
   starts (the Minecraft token was refreshed with the saved refresh token).
4. **Wrong account.** Optional: sign in with a Microsoft account that is not the bound one (delete
   your entry in the file first). Expected: `正版登录失败：登录的正版账号<名字>与RDI账号绑定的正版账号不一致`,
   the game does not start.
5. **Cancel.** Start 正版登录 with no saved session and close the dialog while the browser page is
   open: no game starts later.
6. **Logout.** Log out of RDI: your account disappears from `msa_game_sessions.json` (and from
   Compose's `msa_sessions.json`).
7. **Compose afterwards.** Compose online play of a local pack still works (it keeps its own file).
