# GJ4c owner checklist

Data folder used by the agent: `evidence/jvm-removal/j4c/live/run` (credentials removed).
Manifests of the agent's installs: `evidence/jvm-removal/j4c/manifests/*.sha1`.

## Compare with Compose

- [ ] Install 七七和小翔修仙世界 0.3.377 with 下载此包材质光影 on in Compose (same data
      folder or your own), then compare with the Rust install:
      `cd <versions>/6afd06bf49c3be56b79bcb42_0.3.377; find . -type f ! -path './logs/*' -print0 | sort -z | xargs -0 sha1sum | diff - <manifest>`.
      Expected differences only in files the game writes after a start.
- [ ] A pack installed by the Slint launcher starts from the Compose launcher.
- [ ] A pack installed by Compose shows 重新安装 on the Slint plaza page and its room
      starts from Slint without the install dialog.
- [ ] Download time of one large pack in Compose on the same connection (agent run,
      cold cache, mirror on: 乌托邦探险之旅 3.5.2 in 196s; 修仙传记 retry from cache 32s).

## Slint launcher

- [ ] Plaza version page: 安装 → dialog (pack name, 版本 · size, checkbox off) → task panel
      opens on the task; button shows 安装中, then 重新安装.
- [ ] Room whose pack is missing: dialog; after the install the game does not start by
      itself; clicking the room again enters the game.
- [ ] Clicking that room while it installs: toast 整合包正在下载，请等待下载完成后再启动
      and the task panel opens on the install.
- [ ] Cancel during a download, then install again: no pack appears in between, the
      second run is faster.
- [ ] Mirror preference on and off (config 优先使用镜像).
- [ ] A pack with GitHub mods (none of the test rooms had one).
- [ ] With 下载此包材质光影 on, resource and shader packs appear in game.
- [ ] 重新安装 while that version's game is running: 整合包正在运行，不能安装, and the
      running copy is untouched.
- [ ] Task panel step rows read well (7 steps under the title, 进行中 when a step starts).
- [ ] Focus and minimized-window behaviour while an install runs.

## Review

- [ ] Diff review (no commit made). New files: `src/pack_install/`,
      `ui/components/install_dialog.slint`, `protocol/fixtures/j4c.json`,
      `client/ui/client-core/src/test/kotlin/calebxzau/rdi/client/service/J4cModProcessingFixtureTest.kt`,
      `evidence/jvm-removal/j4c/`.
- [ ] Accept the downloader JSON fix as the J4b follow-up.
