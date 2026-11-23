# GJ5b1 owner checklist

Build: `cargo preview-run` from `client/slint-proto`, or `live/preview.ps1` (isolated data folder,
service disabled on purpose). Development runs need `client/ui` `gradlew.bat :ui-service:gameRuntime`
once, which writes `ui-service/build/game-runtime`.

## Rooms in the Slint launcher

- [ ] A NeoForge 1.21.1 room: click the tile, the game starts and joins the room.
- [ ] A Forge 1.20.1 room: same.
- [ ] Console of a room game: the `[RDI]` lines read well (检查房间基础Mod..., RDI核心Mod已同步, ...).
- [ ] A room with disabled base mods and with extra mods; change them and start again.
- [ ] A GTO pack (Forgeguard) starts.
- [ ] 终止游戏 during preparation leaves no half-written file in `mods`.
- [ ] A room and a local pack at the same time: one status line, both stop.
- [ ] A GTNH or Fabric room still starts (through the service, as before).

## Network

- [ ] Node failover: the first node unreachable, the game connects through the next one.
- [ ] Moving between networks during play keeps the game in the world.

## Both launchers

- [ ] The Compose launcher starts the same room afterwards without reinstalling or re-thinning, and the reverse.

## Platforms

- [ ] macOS and Linux: a room game starts.
