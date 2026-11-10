# GJ4a owner checklist

GJ4a passed 2026-11-10. After the review fixes the owner was asked to run the
checks marked "owner" below, and reported them done. The checks marked "GJ4b"
only exercise the temporary archive bridge or are repeated by J4b, so they move
to GJ4b; they were not reported as run.

- [x] 隔离Slint MCP：缺少data.db时可创建空包；任务显示100%，未启动Java子进程。
- [x] 隔离Slint MCP：准备的data.db列出候选，目录导入正确排除logs并遵守存档开关。
- [x] 隔离Slint MCP：复制大文件时取消，显示任务已取消，没有残留整合包或临时目录。
- [x] owner：通过原生文件选择器选取归档，能预览、安装，任务出现在列表里。
- [ ] GJ4b：混合任务列表中取消Kotlin任务（J4b后不再有Kotlin任务）。
- [ ] GJ4b：任务页停留超过一分钟，已完成的Rust任务保留，没有Java子进程（自动测试已覆盖任务记录）。
- [ ] GJ4b：foreign data.db无错误弹窗，创建和目录导入仍正常（自动测试已覆盖扫描）。
- [x] owner：Rust任务运行期间退出登录被拒绝。
- [ ] GJ4b：服务崩溃不影响Rust任务。
- [x] owner：Rust导入的真实整合包能够启动并进入游戏。
- [x] owner：Compose识别Rust导入的整合包，反向也正常；缓存互用在J4b验收。
- [x] owner：取消按钮（停止图标）的样式和列表、详情中的位置可接受。
- [ ] GJ4b：真实大整合包导入、窗口最小化时进度和取消正常。
- [x] GJ4a通过，可以开始J4b。
