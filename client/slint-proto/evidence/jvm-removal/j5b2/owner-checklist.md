# GJ5b2 owner checklist

Build: `cargo preview-run` from `client/slint-proto`, or `live/preview.ps1` (isolated data folder,
service disabled on purpose).

## Window icon

- [ ] A room whose pack has an icon: the game window shows the pack icon.
- [ ] A room whose pack icon is AVIF: same.
- [ ] Compose starts the same room afterwards and keeps the icon (no new download, no `DEL/window-icon-*`).

## Recovery after a launcher crash

- [ ] A room game running, launcher ended from Task Manager, launcher opened again: the game is in
      the game bar, 终止游戏 works.
- [ ] Same, then 进入地图 from the game's menu: it joins the room again.
- [ ] Same with a local pack game.
- [ ] Normal exit with a game running still asks and ends the game (unchanged).

## Other rooms

- [ ] A GTNH or Fabric room still starts (through the service, as before).
