# 进服大包持久缓存（Forge1.20.1）

配方表（`ClientboundUpdateRecipesPacket`）和标签表（`ClientboundUpdateTagsPacket`）在每次进服时都完整下发，大整合包里有几MB。同一房间两次进服之间它们很少变化，所以客户端把它们按房间存在磁盘上，下次进服时服务端只发引用或差异。思路参考BandwidthOptimizer的配方同步，没有使用其代码；与它不同，这里不扣留任何包。

## 协商

1. Forge登录协商阶段（`PlayerNegotiationEvent`，压缩开启之前），服务端发送登录查询：通道`rdi:pcache`，事务ID`0x52444950`，正文为1字节版本号。登录等待回答，最多10秒。
2. 客户端`mPersistentPacketQuery`在Forge处理之前截获查询，在后台线程读取并用SHA-1校验本房间保存的表（每类最新2份），把会话挂到连接上，再回答`PersistentPacketOffer`：版本、条数、每条（类型1字节+SHA-1 20字节）。
3. 服务端`mPersistentPacketQuery`在Forge之前截获回答。没有正文表示客户端不支持（旧客户端或原版客户端都会这样回答），之后照常发送。
4. 设置`-Drdi.pcache.enabled=false`时服务端不再询问。

## 帧格式（版本1）

沿用压缩信封开头的VarInt，`-4`此前未被使用：

| 帧 | 内容 |
| --- | --- |
| FULL(1) | `-4`、1、类型、SHA-1、一个普通压缩信封（Zstd流运行时同样走流） |
| HIT(2) | `-4`、2、类型、SHA-1 |
| DELTA(3) | `-4`、3、类型、基准SHA-1、目标SHA-1、VarInt长度、以基准为原始内容字典的独立Zstd帧 |

客户端还原后校验SHA-1，不一致即断开。小于4096字节的包照常发送；这个下限大于包引用表的最大条目，两者不会交叠。两端在连接期间按同样的规则保留每类最新2份，服务端只会引用客户端仍持有的版本。

## 存储

- 客户端：`rdi/hosts/<房间ID>/pcache/<类型>/<sha1>.bin`，无房间ID时用`shared`；每类保留3份，比上报多1份。
- 服务端：`rdi/pcache/<类型>/<sha1>.bin`，每类保留4份作为差异基准；内存中保留最近4份及4个差异结果。
- 文件为Zstd压缩的原始包字节，读取时校验SHA-1，损坏的文件会被删除，不再上报。

## 已知限制

- 只省下载量和下载时间。客户端解析配方、JEI重建索引的时间不变。
- 差异压缩在连接的Netty线程上执行，几MB的表约需数十毫秒；同一对版本只计算一次。
- `/reload`后重新下发的表同样走这条路径。

## 验证（2026-10-11）

- 服务端与客户端1.20.1 `zstdTest`各133项通过（含新增`PersistentPacketTest`11项）：首次进服FULL、命中HIT、内容变化DELTA（比FULL小20倍以上）、基准缺失回退FULL、Zstd流中的FULL、小包与未标记包、无会话、哈希不符/未持有时拒绝、两端淘汰一致、磁盘存储排序/淘汰/损坏删除、协商正文的编解码。
- 两端`compileKotlin`除未跟踪的`mc/syncchunk/.../SyncChunkBackupStore.kt:305`两处既有错误外没有其他错误；该错误阻断了`compileJava`、Mixin编译和打包，Mixin仅经过IntelliJ检查（服务端）。
- 未在真实游戏中验证。

## NeoForge1.21.1（2026-10-12）

帧格式、存储和保留规则与1.20.1相同，只有协商改到配置阶段：

1. 1.21没有Forge登录查询。服务端`mPersistentPacketTask`在`ServerConfigurationPacketListenerImpl.runConfiguration`调用`ConfigurationInitialization.configureEarlyTasks`之前插入配置任务；NeoForge事件注册的任务排在`SynchronizeRegistriesTask`（发送标签表）之后，来不及。
2. 客户端协商了可选配置载荷`rdi:pcache`时，任务发送查询（正文同1.20.1），最多等10秒；没有该通道、内存连接或`-Drdi.pcache.enabled=false`时不询问。
3. 客户端`RClientPersistentPackets`在后台读取并校验本房间的表，先把会话挂到解码器上，再用`rdi:pcache_answer`回答`PersistentPacketOffer`。服务端挂上会话后结束任务，之后才发送标签表。
4. `PacketRecordPipeline`按包类型标记：配置阶段（以及`/reload`后）的`CLIENTBOUND_UPDATE_TAGS`为标签，进服时的`CLIENTBOUND_UPDATE_RECIPES`为配方。

载荷定义在`mc/common/codec/src/21/.../PersistentPacketPayloads21.kt`。NeoForge的注册表同步包未纳入。只经过单元测试和编译，未在真实游戏中验证。
