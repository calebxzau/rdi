# 验证记录

日期：2026-09-16。验证对象为本目录的Rust实现，没有修改或启动现有Kotlin代理，也没有连接真实master或Minecraft房间。

## 构建和审查

- 平台：Linux x86_64，WSL2内核`6.18.33.2-microsoft-standard-WSL2`。
- 工具链：`rustc 1.95.0 (59807616e 2026-04-14)`。
- `cargo fmt --check`通过。
- `cargo test --locked`通过：14项测试。
- `cargo clippy --locked --all-targets -- -D warnings`通过。
- `cargo build --release --locked`通过。
- 独立代码审查通过。审查中补齐了`UNKNOWN`房间状态，并修复了完成任务记录在快速反复连接时可能积压的问题。
- Release二进制大小：3,524,504字节。
- 本次Linux二进制SHA-256：`75b513a1ddc1104dfcbaa4b6659fccf1142382b2b8e373a72faab0707b56ed3a`。

## 本地端到端和内存测试

执行命令（在本目录，二进制路径按实际构建位置替换）：

```sh
python3 scripts/smoke_load.py --binary target/release/rdi-proxy-rs --connections 20 --seconds 10 --max-rss-mb 100
```

使用本地模拟HTTP master和TCP后端。20条连接全部完成数据校验，负载阶段设置为10秒；脚本还包含启动、路由失败和超时等独立阶段。脚本将接入上限设为24以测试超额拒绝和释放后的恢复，程序部署默认值仍为64。

| 指标 | 实测结果 |
| --- | --- |
| 启动后空闲RSS | 4920KiB，约5.04MB |
| 采样峰值RSS | 5916KiB，约6.06MB |
| 内核记录的峰值VmHWM | 5916KiB，约6.06MB |
| 负载结束后的RSS | 5916KiB |
| 完成的连接数 | 20 |
| 逐字节校验的接收数据 | 47,748,637字节 |
| 慢客户端的后端初始发送量 | 2,090,000字节 |
| 慢客户端暂停读取时其他连接继续传输 | 通过 |
| 低于100,000,000字节的进程内存目标 | 本次测试通过 |

端到端检查包含：

- 协议版本`-1`的本地状态查询、活动连接样本和同一连接上的ping。
- 房间停止、启动中、暂停，以及路由缺失、格式错误和超时的中文断开提示。
- 后端连接失败后关闭客户端。
- 达到连接上限时拒绝额外连接，释放名额后完成新的状态查询和ping。
- 分片发送握手、握手后立即附带数据、Forge主机名扩展，以及非最短但有效的VarInt长度编码的原始字节保留。
- 后端主动发送数据、客户端数据回传、慢客户端和其他连接并发传输。

最终脚本报告：

```json
{"ok":true,"synthetic":true,"protocol":"Minecraft handshake/status/ping plus TCP relay","connections":20,"seconds":10.0,"admission_cap":24,"phases":["startup","status_ping","route_statuses_and_failures","backend_connect_failure","admission_cap_and_recovery","bidirectional_load_and_slow_reader"],"load":{"completed_connections":20,"total_bytes":47748637,"slow_reader_payload_bytes":2090000,"other_connection_progressed_during_slow_reader":true},"memory":{"measurement_available":true,"idle_rss_kb":4920,"peak_rss_kb":5916,"peak_hwm_kb":5916,"after_rss_kb":5916,"after_hwm_kb":5916,"max_rss_mb":100.0,"memory_pass":true}}
```

## 验证边界

RSS和VmHWM来自代理进程自己的`/proc/<pid>/status`，不包含测试进程，也不等同于容器总内存或全部内核TCP缓冲。本次结果不是长期内存上限保证，也不是对其他并发数或工作线程配置的保证。

尚未进行真实Minecraft/Forge/NeoForge客户端登录、实际整合包多人传送、长时间运行、真实master授权、HTTPS master连接或Windows构建验证。现有Kotlin版本保留，可供后续实际游戏对照。
