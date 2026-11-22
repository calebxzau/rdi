# GJ5a owner checklist

Data folder used by the agent: `evidence/jvm-removal/j5a/live/run` (credentials removed;
`live/preview.ps1` breaks the service on purpose, `live/preview-service.ps1` runs it).

## Decisions

- [x] (owner, 2026-11-23) Accept `rav1d-safe =0.6.0` (AGPL-3.0, "AGPL-3.0-only OR commercial") for AVIF icons.
      Plain `rav1d 1.1.0` aborts the launcher on a corrupt AV1 file (`avif-spike/results.md`).
- [ ] Accept the macOS/Linux Java search locations in `src/java_runtime.rs::unix_candidates`
      (the updater has no rules there) and "install JDK 25 yourself" outside Windows.

## Slint launcher, Windows

- [ ] A local Forge 1.20.1 and a local NeoForge 1.21.1 pack that never ran start from the
      整合包 tab in your own data folder; the play dialog opens without 启动中 delay.
- [ ] Console: the [RDI] lines, the game output, 复制/导出/清空, switching between sessions.
- [ ] Rename Java temporarily or run on a machine without JDK 25: 缺少Java25 dialog,
      自动下载安装 → UAC prompt → task 安装Java25 finishes → the pack starts on the next click.
- [ ] Per-pack Java (e.g. a JDK 21), memory, JDWP and custom JVM arguments in the 设置 page
      take effect (`Launch Command` in the launcher log).
- [ ] 终止游戏 while a pack is still installing: the install stops, no half-written loader is
      treated as installed on the next start.
- [ ] Close the launcher with a local pack running: the exit dialog, then the game ends.
- [ ] Online (正版登录) on a local pack still works through the service, and is blocked as a
      duplicate while the same pack runs offline from Rust.
- [ ] A room and a local pack at the same time: one status line, 终止游戏 ends both.
- [ ] AVIF pack icons show on the plaza (two packs use `i0.hdslb.com` AVIF icons).
- [ ] Focus/minimize: in the agent's run the launcher window ended up minimized after a
      game started (no J5a code minimizes it; cause not found).

## Compose and other platforms

- [ ] Compose launcher on a data folder where Rust installed Forge 1.20.1 and NeoForge 1.21.1:
      no reinstall (the agent checked only the service, which runs the same Kotlin code, and
      only for NeoForge 1.21.1).
- [ ] The reverse for Forge 1.20.1: Compose installs, then Slint starts a local 1.20.1 pack
      without 开始自动安装.
- [ ] macOS arm64 and Linux x64: a real start of a local pack (only `cargo-zigbuild check`
      ran).

## Review

- [ ] Diff review (no commit made). New: `src/mc_install/`, `src/mc_launch/`, `src/play/`,
      `src/play_ui.rs`, `src/java_runtime.rs`, `src/avif.rs`, `protocol/fixtures/j5a.json`,
      `protocol/fixtures/j5a/`, `protocol/fixtures/avif/`,
      `client/mclaunch/src/test/kotlin/calebxzau/rdi/mclaunch/J5aLaunchCommandFixtureTest.kt`,
      `evidence/jvm-removal/j5a/`. `J5-PLAN.md` is still untracked.
- [ ] `client/ui/ui-service/build/install` was rebuilt from current source (it was from
      2026-11-10 and installed NeoForge 21.1.250).
