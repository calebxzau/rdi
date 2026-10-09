# Forge1.20.1区块缓存

1.21的区块缓存（设计见[CHUNK_CACHE.md](../client/mc/1.21.1-neoforge/CHUNK_CACHE.md)）已移植到Forge1.20.1。协议版本、hash规则、限额、超时、增量捕获和日志与1.21一致。旧的1.20实现已归档到`DEL/old-chunkcache-1.20.1-20261003/`。

## 代码结构

| 目录 | 内容 |
| --- | --- |
| `mc/chunkcache/src/main` | 两版共享：`TerrainHash`、`ChunkTerrainCodec`、限额 |
| `mc/chunkcache/src/client` | 两版共享的客户端缓存：区域文件、增量存储、写入队列、包交接、候选扫描、快照固定 |
| `mc/chunkcache/src/server` | 两版共享的服务端台账、统计、区块段hash缓存 |
| `mc/chunkcache/src/20` | Forge1.20.1消息、`rdi:chunk_cache`通道、`ChunkReuseCodec` |
| `mc/chunkcache/src/21` | NeoForge1.21.1载荷、`ChunkReuseCodec` |

版本差异由各客户端模块的`ChunkCacheCompat`提供：区域文件构造、`NbtAccounter`、NBT超限异常识别。1.20.1的NBT深度上限固定为512，字节配额仍然生效。`ChunkCacheClient`、服务端`ChunkCacheServerService`和Mixin按版本各自实现。

## 与1.21的差异

- 服务端接入点为`ChunkMap.playerLoadedChunk`中的`ServerPlayer.trackChunk`。多个玩家共享的原始区块包不被修改，命中时只替换本玩家发送的包。
- 1.20.1没有区块分批发送和确认。复用失败或超时时，服务端在主线程立即构造并发送完整区块，再发送结束消息；区块已离开视距或已卸载时发送遗忘区块并结束。
- 视距判断使用玩家所在区块与服务器视距（`ChunkMap.isChunkInRange`），候选范围额外加2个区块。
- 1.20.1服务器按自身视距向所有客户端发送区块，与客户端渲染距离无关。因此客户端按`ClientPacketListener.serverChunkRadius`准备候选，并使用与服务端相同的`isChunkInRange(视距+2)`判定（`ChunkCacheViewRange`），不再按渲染距离+2的正方形。1.21客户端不变。
- 通道`rdi:chunk_cache`为可选通道。任一端没有时继续完整发送区块。消息由Forge在客户端主线程按到达顺序处理。
- 客户端在`Minecraft.clearLevel(Screen)`结束缓存会话，相当于1.21的断开连接入口。
- `PlayerList.respawn`在触发重生事件之前就会同步发送新玩家的区块，因此服务端会话记录玩家实例：实例变化（重生）或维度变化时，在发送第一个区块前开始新会话，旧候选不会被复用。重生和换维度事件只做初始化，不再重置。
- 发送区块和每tick维护中的异常都在内部记录并回退为完整发送或重置该玩家会话，不会中断原版区块跟踪或服务器tick。
- 启动参数没有`rdi.host.id`时使用`shared`缓存目录（两版共享代码的修正，此前会在连接时出错）。

## 候选覆盖修正（2026-10-08）

2026-10-07至10-08的实服日志中，4名玩家共362,459次区块发送：无候选直接完整发送270,792次（74.7%），复用90,155次，hash不匹配仅958次（0.26%）。有候选时整块命中率约99%，主要损失在客户端没有提前准备候选。原实现按`min(渲染距离, 服务器视距)+2`准备，渲染距离8、服务器视距10时，移动后进入视野的一圈从未准备。

服务端日志新增`noOfferRevisitFullSends`，它是`noOfferFullSends`的子集：本次服务器运行中已向该玩家发送过的区块（按玩家和维度记录，每名玩家最多262,144个，登出后保留，停服清空）。它与首次发送的差值可区分缓存可挽回的回访和真正的首次探索。新会话的统计窗口从创建时开始计时，不再在第一个tick立即输出。

`ChunkCacheSentChunks`已移到两版共享的`mc/chunkcache/src/server`，1.21服务端同样记录`noOfferRevisitFullSends`，新会话统计窗口也改为从创建时开始。

通过IntelliJ MCP在Windows模块目录、Temurin17执行：服务端`test --tests 'calebxzau.rdi.mc.server.chunkcache.*' --tests 'calebxzau.rdi.mc.chunkcache.*'`共33项通过；客户端`:test`同类过滤共49项通过，`:reobfJar`成功，refmap中`serverChunkRadius`映射为`f_104897_`。尚未在真实游戏中验证命中率变化。

## 上行消息合并（2026-10-08，1.20和1.21共用）

1.21测试数据中上行95%是区块缓存控制消息，候选上报平均只有1到2条一个包。两版客户端改用共享的`ChunkCacheOutbox`：候选和取消在每个客户端tick结束时统一发送，取消先发以便服务端先腾出候选名额；每tick最多一批64条候选，其余留到下一tick。尚未发出的候选被取消时直接在本地释放，不发送消息。协议和服务端不变，新旧版本互通。结果消息仍为一条一个包，合并需要新协议版本。

## 验证（2026-10-03）

通过Windows IntelliJ终端串行执行，Forge模块使用`C:\Users\Public\gradle\bin\gradle.bat`和Temurin17，1.21客户端使用模块wrapper和GraalVM25。

| 模块 | 结果 |
| --- | --- |
| 1.21客户端`:test`区块缓存相关 | 68项通过，共享化前后一致 |
| 1.21服务端`:test`区块缓存相关 | 29项通过 |
| 1.20客户端`:test`区块缓存相关、`reobfJar` | 41项通过 |
| 1.20服务端`test`区块缓存及v20服务端、`reobfJar` | 67项通过 |

两端refmap中的注入目标均解析为正式SRG名称，最终jar包含新的Mixin配置、manifest和AT。1.20新增测试覆盖消息往返、长度限制、服务端到客户端包体上限，以及复用元数据不含区块段和光照、还原后与原包一致。

## 已知限制

- 复用失败后的补发直接发送完整区块，不会再次触发Forge的`ChunkWatchEvent`。若某个Mod在该事件中发送自己的区块数据，失败路径上这些数据可能在客户端尚无区块时到达而丢失。成功路径不受影响。
- 通道版本固定为`chunk-cache-1`，对端缺少通道时回退完整发送；对端版本不同则会拒绝连接。以后升级协议版本时需要改为兼容旧版本。
- 1.21服务器按`clamp(客户端渲染距离, 2, 服务器视距)`跟踪区块，等于客户端的有效渲染距离，因此1.21没有1.20.1那一圈未准备的问题。1.21客户端已于2026-10-08改为与服务端相同的`ChunkTrackingView.isWithinDistance(视距+2)`圆形范围，正方形角落不再被准备后又被拒绝。
- 服务器视距为32时，扫描正方形受上限34限制，预取圈最外侧的轴向尖端不会提前准备。

## 独立审查

独立静态审查对照Forge47.4.20源码核对了Mixin目标、AT、线缆格式、客户端主线程处理顺序、断开和单人游戏、同步补发以及共享化重构（除适配器调用和hostId回退外与原文件逐字节一致）。审查发现的同维度重生会遗留区块空洞的问题已按上述会话规则修复；未运行游戏。

## 尚未验证

没有启动真实游戏。需要同时更新客户端和服务器jar后检查：首次进入、走远再回到已探索区域、改动方块后回访、换维度、重生、断线重连、旧客户端连接、日志中的`Chunk cache minute`与`CHUNK_REUSE_CLIENT`命中率，以及服务器tick耗时。实际节省流量用部署前后的`packet-traffic_v4.db`对照。
