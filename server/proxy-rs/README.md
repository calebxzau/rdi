# RDI Rust房间代理

`server/proxy`的独立Rust实现，面向约20人并发、进程常驻内存低于100MB的部署目标。内存预算是验证目标，不是硬性内存限制。设计与兼容边界见[PLAN.md](PLAN.md)。

## 构建与启动

在本目录使用稳定版Rust工具链构建，无需Gradle或Java：

```sh
cargo build --release --locked
./target/release/rdi-proxy-rs --master http://127.0.0.1:65231
```

Windows使用`target\release\rdi-proxy-rs.exe`。与原代理同时测试时，使用不同监听端口：

```sh
./target/release/rdi-proxy-rs --listen 127.0.0.1:65232 --master http://127.0.0.1:65231
```

玩家请求的房间端口仍从Minecraft握手读取，实际后端地址和端口由master返回。远程代理必须在master的既有节点配置中获得源IP授权；Rust版不绕过这一检查。

## 配置

命令行优先于环境变量，未配置时使用默认值。`--help`显示当前支持的选项。Rust程序不读取JVM的`-D`参数。

`--listen`使用数字IP地址和端口，IPv6写成`[::]:65230`。`RDI_LISTEN`优先于`RDI_PORT`。工作线程可配置为1至256个，连接上限可配置为1至65535条；这些范围是参数校验范围，不代表所有配置都能满足100MB目标。

| 命令行 | 环境变量 | 默认值 |
| --- | --- | --- |
| `--listen` | `RDI_LISTEN` | `0.0.0.0:65230` |
| 通过`--listen`指定端口 | `RDI_PORT` | `65230` |
| `--master` | `RDI_MASTER` | `http://127.0.0.1:65231` |
| `--max-connections` | `RDI_MAX_CONNECTIONS` | `64` |
| `--workers` | `RDI_WORKERS` | `2` |
| `--debug` | `RDI_DEBUG` | 关闭 |
| `--backend-host` | `RDI_BACKEND_HOST` | `127.0.0.1` |
| `--favicon` | `RDI_FAVICON` | 工作目录中的`favicon.png` |

64条连接的上限包含登录中、游戏中和服务器列表查询中的连接，不是64名玩家的独立名额。达到上限后关闭新连接，已有连接继续工作。调高上限后需要重新验证内存预算。

调试模式仅在握手请求端口为25565时直连`--backend-host:25565`。其他端口仍查询master。通常部署不需要开启调试模式。

favicon是可选文件，只在启动时读取一次，最大64KiB。可以复用原代理的图片：

```sh
./target/release/rdi-proxy-rs --favicon ../proxy/favicon.png
```

日志输出到进程标准流，适合由运行它的服务管理器收集；没有内置按天归档的文件日志。停止进程时会关闭现有玩家连接。

默认日志级别为`info`，可通过`RUST_LOG=debug`开启更详细的诊断日志。`--debug`控制25565直连行为，与日志级别独立；需要覆盖环境变量关闭它时可用`--debug=false`。

## 转发与兼容性

- 沿用master的`/host/route`和`ProxyHostRoute`数据格式。
- 保留房间未启动、启动中和服务不可用的中文提示。
- 服务器列表查询在代理本地处理，ping原样回应。
- 保留握手原始字节，包括Forge主机名扩展。后续采用原始TCP转发，不改写Minecraft压缩、加密或Mod数据。
- 两个方向各使用8KiB复制缓冲。慢连接会暂停对应方向读取，不建立无限数据队列。
- 任一端断开时关闭配对连接；不设置游戏连接空闲踢出时间。
- 状态列表沿用原代理的`players.max=88888`和活动连接计数语义，实际接入限制以`--max-connections`为准。

与旧版本相比，新版增加了连接数量、初始报文大小及等待时间的限制；HTTP重定向和环境代理不启用。master路由没有新增缓存，房间启停仍以每次请求的结果为准。

## 本地验证

本次Linux合成测试：20条连接、10秒负载，峰值RSS约6.06MB；14项Rust测试和独立审查通过。完整条件、二进制摘要及验证边界见[VALIDATION.md](VALIDATION.md)。

```sh
cargo fmt --check
cargo test --locked
cargo clippy --locked --all-targets -- -D warnings
cargo build --release --locked
python3 scripts/smoke_load.py --binary target/release/rdi-proxy-rs --connections 20 --seconds 10 --max-rss-mb 100
```

测试脚本仅启动回环地址上的模拟master、模拟后端和代理进程，不连接真实房间。它检查协议、双向数据完整性、慢连接、连接限制和重新连接，并输出JSON报告。

Linux从`/proc/<pid>/status`测量代理进程RSS和`VmHWM`，100MB按100,000,000字节计算。其他平台若无法测量内存，会明确报告不可用。RSS不包含所有内核TCP缓冲或容器整体用量；若100MB是容器限制，还应在目标机器测量容器内存。

这里的20条合成TCP连接不等同于20名真实Minecraft玩家。最终仍需验证实际整合包的登录、多人传送、区块加载和长时间运行。
