# GJ2 owner checklist

Use the real data directory (`client/ui/run`), so the packs are complete.

- [x] (Owner confirmed 2026-11-10.) 整合包 tab shows the same packs as the Compose launcher on the same server. (On the production server, packs created on a local master are skipped by design, in both launchers.)
- [x] (Owner confirmed 2026-11-10.) 高级: set 内存限制 to 8192 and save, start the pack, and confirm the game runs with `-Xmx8192M` (game log or Task Manager command line). This proves Kotlin reads the row Rust wrote.
- [x] (Owner confirmed 2026-11-10.) Compose 整合包设置 shows what Slint saved; change it in Compose and Slint shows the change.
- [x] (Owner confirmed 2026-11-10.) 目录 opens the pack folder.
- [x] (Owner confirmed 2026-11-10.) Install or create a pack, switch to another window before it finishes, then come back: the new pack appears.
