# GJ3 owner checklist

Use complete real packs and a monitor switched on. Implementation stops at GJ3; no J4 or commit.

- [x] 隔离MCP验收：登录后菜单和整合包设置不启动Java子进程；保存-Dj3.live=true并核对SQLite成功。
- [ ] 点击真实房间，出现正在准备，游戏成功进入房间；记录点击到回复耗时。
- [ ] 结束游戏后等待60秒，Java子进程退出且无错误；再次游玩成功。
- [ ] 游戏运行时结束启动器的Java服务进程：出现可关闭错误，自动恢复管理，停止游戏有效；仍在线或能够重新连接。
- [ ] 真实整合包导入期间不退出，完成后列表刷新。
- [ ] 控制台页面停留超过60秒仍保留进程，离开后60秒退出。
- [ ] 窗口最小化期间60秒空闲计时仍有效。
- [ ] 退出RDI后重新登录，真实微软正版在线游玩仍正常；其他RDI账号会话保留。
- [ ] Compose仍能读取Rust清理后的msa_sessions.json并完成正版游玩。

Automated and isolated MCP checks are listed in gate-summary.md. A shared Kotlin fixture
read proves storage compatibility, not real Microsoft authentication acceptance.
- [ ] 打开导入页后立刻返回，60秒后Java子进程退出。
- [ ] 停留在整合包设置页并有未保存修改时结束Java服务进程：留在设置页，修改仍在。
- [ ] 点击房间时整个房间列表换成“正在加载…”，确认是否接受这种等待提示。
