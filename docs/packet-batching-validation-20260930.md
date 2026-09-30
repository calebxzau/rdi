# Forge1.20.1选择性包批处理实施与验证

本次按`/tmp/rdi-packet-batching-handoff-20260930T012924Z/HANDOFF.md`的批准范围完成源码实现。工作区写入探针成功。未修改部署实例、未执行Git写操作。

## 最终行为

- Forge1.20.1仅在对端协商了`rdi:batch`时启用S2C批处理；旧端保持既有逐包封套。
- 原版`ClientboundUpdateAttributesPacket`使用下一次全局tick结束或50ms截止时间。
- 经目标整合包字节码确认的L2 Tabs0.3.3消息`l2tabs:main`的单字节discriminator0只更新属性修饰符名称。默认1tick，可通过长窗口开关使用4tick或200ms。
- 未确认消息、其他3个被审计频道、普通包均立即发送，并先排空此前队列。其他L2 Tabs版本不使用该消息白名单。
- 每连接1条有序队列，保留全部原始编码字节和顺序。记录区含各包长度前缀，达到65536字节立即发送；单包回退逐包封套，小型未压缩批次按含外层长度前缀的成本选择封装。
- 客户端在`Connection.channelRead0`接到登录包时同步准备解码，早于主线程登录处理。本地连接和关闭压缩的连接保留回退。
- 缓冲区、原始promise、定时任务和处理器移除路径在Netty线程处理。失败释放所有仍由编码器持有的资源，关闭连接，并拒绝之后的发送。回退多帧不会被原始promise监听器的重入写入打断。
- Forge服务器写入`rdi/packet-traffic_v4.db`：逻辑包、内层发送帧、外层前缀、写入结果、flush原因/等待/成本分开记录。混合批次不归到某个Mod名下；多帧回退只计1次缓冲队列flush和等待时间。
- 默认关闭的选定玩家采样与离线同流比较已接入。采样保留已验证的最大延迟策略，可在正式长窗口开关关闭时离线比较1tick和4tick。内存或事件上限导致不完整样本时，工具拒绝比较。

## 可复查的验证

所有Gradle命令通过Windows上的IntelliJ终端执行，模块内运行。Forge模块当前缺少`gradlew.bat`，使用已安装的`C:\Users\Public\gradle\bin\gradle.bat`；Fabric模块使用自身wrapper。Forge服务器Gradle进程使用项目指定的Temurin17，源码工具链保持模块配置。

| 验证 | 结果 |
| --- | --- |
| Forge1.20.1服务器`zstdTest` | 46项执行，0失败、0错误、0跳过 |
| Forge1.20.1服务器定向`:test` | 36项执行，0失败、0错误、0跳过 |
| Forge1.20.1客户端`RClientBatchingTest` | 4项执行，0失败、0错误、0跳过 |
| Forge1.20.1客户端/服务器Kotlin、Java编译与reobfJar | 通过 |
| Fabric1.20.1服务器Kotlin、Java编译 | 通过，未启用批处理 |
| NeoForge1.21.1服务器Kotlin、Java编译 | 通过，未启用批处理 |
| IntelliJ对RServerBatching的文件构建 | 通过 |
| 协议、资源生命周期、统计持久化独立审查 | 最终源码批准，无遗留审查发现 |
| 本次相关已跟踪文件`git diff --check` | 通过 |

JUnit XML位于各模块`build/test-results/zstdTest`或`build/test-results/test`。汇总保存在`/tmp/rdi-packet-batching-validation-20260930/junit-summary.json`。

46项编码测试涵盖旧封套、畸形帧、混合延迟、持续到包不推迟首包截止、无tick超时、单包与多包65535/65536/65537字节边界、构造及分配失败、第二次scratch分配失败释放前次输出、下游失败、移除/关闭、promise重入顺序、采样界限与同流字节一致性。36项服务器测试涵盖原v3行为、v4SQLite存储/重开/迁移/重试/关服并发入队、白名单与非破坏性读取、编码失败和嵌套编码后的真实归属。4项客户端辅助测试使用真实Forge协商连接属性和真实codec验证登录任务未执行时首批可解码、无能力旧端、本地连接与关闭压缩回退。

客户端辅助测试不等于实际Mixin应用或Minecraft登录实机验收；已生成的refmap包含新增登录钩子的Packet签名。编译中的既有Mixin/Gradle弃用警告和新采样数据类copy可见性警告未阻止上述检查。

## 尚待实机完成

目前没有真实成功编码的逐包载荷/时间/tick样本；旧v3累计数据库没有这些信息，因此未计算真实节省比例。离线回放测试使用构造流验证算法与完整性。

部署新客户端与服务器后，还需在目标整合包执行登录、重连、换维度、战斗、移动速度和属性UI以及新旧端组合检查，采集代表性的`.rdibatch`并运行同流三方案比较。关闭诊断采样后检查TPS/CPU与玩家响应。当前部署实例位于工作区写入范围外，本次未替换运行JAR。

运行开关、采样与回放用法见[packet-batching-diagnostics.md](packet-batching-diagnostics.md)。
