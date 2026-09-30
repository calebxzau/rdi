# 全玩家包内容记录验证

已实现Forge1.20.1服务端所有已入服玩家的S2C自定义载荷和属性更新包内容记录，包含旧客户端、
关闭批处理和关闭网络压缩的连接。登录事件之前的握手及初始化包不在采集范围。
内容自动写入`rdi/packbatch/`，没有`rdi.batch.sample*`参数。

每个文件硬上限128,000,000字节。记录为二进制RDPC格式，按完整Zstd帧压缩，
使用等级3和校验和。全服共用有界队列及后台线程。队列溢出丢弃采集副本并计数，
文件写入或压缩失败停止采集并记录异常，正常关服排空已经接收的记录。

Python工具只读新格式，提供JSON汇总和过滤后的JSONL；支持损坏/未完成文件中
已经完整写出的帧。读取限制帧大小、记录长度、分组数量及连接跟踪数量。

## 已执行验证

| 验证 | 结果 |
| --- | --- |
| Forge服务端`:zstdTest` | 53项，0失败、0错误、0跳过；其中7项新增存储测试 |
| Forge服务端管线/策略定向`:test` | 15项，0失败、0错误、0跳过 |
| Forge服务端Kotlin/Java编译及reobfJar | 通过 |
| Python单元测试 | 15项通过 |
| Kotlin写入→Python读取 | 按fixture manifest核对玩家、连接、序号、类型、频道和载荷，全部一致 |
| IntelliJ文件构建 | 通过 |
| Fabric1.20.1服务器共享源码编译 | Kotlin/Java通过；本次未接入其自动采集生命周期 |
| NeoForge1.21.1服务器共享源码编译 | Kotlin/Java通过；本次未接入其自动采集生命周期 |
| 并发、文件完整性、Python读取独立静态审查 | 最终APPROVE，无遗留发现 |
| 本次相关已跟踪文件diff检查 | 通过 |

Gradle通过Windows IntelliJ MCP启动，顺序运行；Forge使用项目指定Temurin17。
Forge模块没有wrapper，使用已安装的`C:\Users\Public\gradle\bin\gradle.bat`。
Fabric使用模块wrapper，Fabric/NeoForge验证进程使用Windows GraalVM25。

JUnit XML：`server/mc/1.20.1-forge/build/test-results/{zstdTest,test}/`。
执行日志：同模块`build/packbatch-validation-final.log`、`packbatch-fabric.log`、
`packbatch-neoforge.log`。跨语言fixture：同模块`build/packbatch-fixture/`。

存储测试覆盖非破坏性复制、重连归属、缩小阈值下不可压缩数据的多文件硬上限、
有界队列丢样及恢复、低流量在关闭前实际落盘、写入失败及并发关闭。
管线测试覆盖未启用批处理的多连接、压缩开关切换、嵌套编码及失败编码后的归属。

Python依赖为`zstandard`；本次使用0.25.0，临时安装在`/tmp/rdi-packbatch-python`。
复验命令：

```bash
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=/tmp/rdi-packbatch-python python3 -m unittest discover -s mc/common/codec/tools/packbatch -v
```

既有Mixin/Gradle警告和新代码使用JVM monitor的Kotlin建议警告未阻止编译。

## 验证边界

本次未替换部署实例JAR，未执行真实整合包多人长时间运行、TPS/CPU或磁盘吞吐测试。
128MB上限的自动化验证采用同一写入逻辑和较小测试阈值，没有生成真实128MB实机文件。
采集文件表示已编码内容，不保证对端已经收到。目录没有自动清理或总容量限制。

使用说明见[packet-batching-diagnostics.md](packet-batching-diagnostics.md)，
二进制格式见[packbatch-format.md](packbatch-format.md)，
Python入口见[工具README](../mc/common/codec/tools/packbatch/README.md)。

## 后续收窄采集范围

按包类型仅记录`ClientboundCustomPayloadPacket`和`ClientboundUpdateAttributesPacket`。
所有自定义载荷频道均保留；其他类型在复制、入队和分配采集序号之前跳过，
不增加丢样计数。流量汇总及实际网络发送仍使用原有路径。

本次定向重跑`PacketMetricsPipeline20Test`：13项通过，0失败、0错误、0跳过；
Forge编译及reobfJar通过。测试验证目标类型、频道归属、普通包被过滤、
跨连接序号连续、压缩切换以及编码失败后的正确归属。
日志位于`server/mc/1.20.1-forge/build/packbatch-filter-validation.log`。
此前Fabric/NeoForge编译结果属于上面的初始实现验证，本次筛选修改未重跑这些模块。
