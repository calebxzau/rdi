# Forge1.20.1 Krypton压缩兼容修复

## 原因与改动

DeceasedCraft实例`697791fc724409d97de72f85_5.11.18`的日志显示Krypton Reno压缩Mixin启用。实际Krypton jar在`Connection.setupCompression`的HEAD注入可取消回调，默认优先级1000；RDI原先也是1000。若Krypton接管，名为`decompress`的处理器无法识别RDI批次，登录阶段的检查会报`inbound compression decoder is unavailable`。此条件与是否安装L2Tabs无关。

本次将两端实际启用的共享1.20压缩Mixin设为优先级900。Mixin0.8.5后应用的HEAD注入会排在先应用回调之前，因此低于Krypton的1000才能让RDI回调先执行，安装自己的Zstd处理器并取消后续安装逻辑。实际转换探针确认提高到2000反而让Krypton先执行，因此未采用该值。服务器实际使用`mc/20/src/server`中的Mixin；模块本地旧同名Mixin未注册，因此未改动它。

保持现有Zstd帧格式、批处理协商、客户端登录前同步启用解码和处理器不匹配时的拒绝行为。登录日志记录`decompress`与`compress`的实际类名；不匹配异常包含相同信息。服务器日志使用`requested`描述启用请求，避免把请求误记为已生效。

## 验证

2026-09-30，按顺序通过Windows IntelliJ终端运行模块Gradle，Temurin17启动Gradle，模块源码工具链不变。

- 客户端`RClientBatchingTest`：6项通过，0失败/错误/跳过。覆盖陌生处理器诊断、旧端单帧、本地/无压缩连接、首次批次、重复设置和两次新连接。构建及reobfJar通过。
- 服务器`zstdTest`：53项通过，0失败/错误/跳过。
- 服务器`PacketMetricsPipeline20Test`：13项通过，0失败/错误/跳过。构建及reobfJar通过。
- 两端最终jar字节码均确认生效Mixin的`priority=900`。
- 使用实际Mixin0.8.5转换具有相同`setupCompression(int, boolean)`和可取消HEAD注入的合成目标：两种独立配置注册顺序均为RDI执行1次、Krypton回调和原方法执行0次，无关方法保持不变。探针及结果位于`/tmp/rdi-krypton-probe`。这验证了注入顺序，不等于加载完整Krypton或实际Minecraft实例。
- 独立代码审查通过。Gradle日志位于两端模块的`build/krypton-compat-validation.log`，退出码文件为同名`.exit`，JUnitXML位于`build/test-results`。

客户端jar SHA-256：`c8ed967ecd4900c8f7fa569bd5341873aee791765afcf6ecf774911914d23a84`。

服务器jar SHA-256：`2566240873d2a91abebcc1b666dca54860bdf4843ac07e049097312827ac91a5`。

## 实机验收

尚未替换部署实例或运行实际整合包登录。两端更新jar后，保留Krypton，确认登录日志显示RDI的`ZstdCompressionDecoder`和`ZstdCompressionEncoder`，再验证进入世界、重连及首个批次。该验证与单元测试、字节码检查分别记录。
