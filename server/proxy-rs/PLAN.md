# Rust房间代理实现方案

目标是为约20名同时在线的玩家提供独立代理，以Release进程RSS低于100MB作为验证目标。这个目标需要在指定负载下测量，不等同于对任意连接数、操作系统或流量的内存保证。

## 范围与兼容性

- 新增独立Cargo项目；保留`server/proxy`，master和客户端不需要更换协议。
- Minecraft握手中携带的端口仍然决定房间，向master查询`GET /host/route?port=...`。
- 只有`PLAYABLE`房间可以连接；保留原代理的中文状态提示、登录断开包、状态查询和ping回应。
- 完整转发原始握手，包含Forge的NUL分隔扩展；不改写服务器地址、协议版本或请求端口。
- 保留调试模式下请求端口25565直连指定后端的行为。
- 保留服务器列表的版本名称、描述、在线连接数、地址掩码和兼容Java算法的样本UUID。这里的UUID是既有协议兼容值，并非新增业务实体ID。

## 内存与连接模型

1. Tokio默认使用2个工作线程，阻塞线程池最多2个线程，复用一个HTTP客户端。
2. 默认最多64条客户端连接，包含等待握手、查询路由、服务器列表查询和游戏连接。接入前取得许可，超额连接直接关闭，不创建无限等待任务。
3. 初始握手最多4096字节，完整读取时限10秒。仅读取该帧，避免丢失紧随其后的Login Start数据。
4. master查询总时限3秒，响应体上限64KiB；后端连接（含DNS）时限5秒。
5. 路由查询期间不继续读取客户端数据，让TCP施加背压，不维护待转发数据列表。
6. 后端连接成功后写入原始握手，释放握手缓冲，使用每方向8KiB缓冲转发原始字节流。
7. 每次完整写出已读数据后才继续读取。任一方向EOF或错误即关闭两端，沿用原代理的断线语义；不设置会踢出挂机玩家的游戏连接空闲超时。
8. 连接许可、状态记录与套接字均随任务结束释放。每轮接受连接前先清理已完成任务，避免快速反复连接时完成记录积压；退出时取消并回收现有任务。

## 有意收紧的边界

- 合法握手状态限定为状态查询、登录和转服（1、2、3）；拒绝损坏的VarInt、越界字符串、超大握手和多余字段。
- 服务器列表请求也有读写时限，favicon最多读取64KiB。
- master地址只接受HTTP(S)，拒绝用户信息、query和fragment；支持路径前缀。HTTP客户端不采用环境代理、不跟随重定向、不缓存房间路由、不重试。
- master HTTP错误、缺失或损坏的路由均使用现有通用错误提示。正常响应继续兼容未知JSON字段。
- 登录成功路由后不再解析完整Minecraft帧，因此不会等待大数据包全部到齐，也不检查后续数据的Minecraft格式。后端负责其协议验证；压缩、加密和Mod数据原样转发。

## 验证与交付

- Rust测试覆盖协议边界、原始握手保留、状态查询、错误包、路由异常和资源回收。
- 运行格式检查、测试、Clippy和Release构建。
- 使用本地模拟master和后端执行端到端测试：分片握手、合并发送、中文错误、ping、连接上限、重新连接和背压。
- 使用20条真实TCP连接执行合成负载，测量独立Release代理进程的RSS与峰值；代理以外的测试进程内存单独计算。
- 独立审查兼容性、输入边界、取消和断线时的资源回收。
- 真实Minecraft客户端、Mod握手和生产部署由后续运行验证；合成TCP测试不替代这些检查。

## 当前实现的参考

- `../proxy/src/main/kotlin/calebxzhou/rdi/prox/DynamicProxyFrontendHandler.kt`
- `../proxy/src/main/kotlin/calebxzhou/rdi/prox/ProxyRouteResolver.kt`
- `../proxy/src/main/kotlin/calebxzhou/rdi/prox/ProxyFlowControl.kt`
- `../../common/model/src/main/kotlin/calebxzhou/rdi/common/model/ProxyHostRoute.kt`
- `../../common/model/src/main/kotlin/calebxzhou/rdi/common/model/HostStatus.kt`
