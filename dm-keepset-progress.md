# DM 流量与存储优化：进度记录（2026-10-27，2026-10-30更新）

> 本次会话的进度存档，之后继续时从这里接着做。
> 工作区里原本就有很多文件处于修改状态，以本文的"改动清单"为准，不要用 `git status` 判断哪些是这次改的。
> 代码都没有提交。

## 1. 目标与已确认的决策

目标：节省 **DM 房主的上行带宽** 和 **dm-master 的存储**。

用户已确认的决策：
- 同步区块以外的世界状态丢了可以接受，换房主后那些地方会重新生成。
- 存档点只保存 **同步区块 + 每个玩家最后位置周围的3×3区块**。用户选了"带上玩家周边区块"，没选"接管时把玩家传送到安全位置"。
- 访客连接开启 zstd 流式压缩，**窗口32MiB**（用户的选择，我原本建议16MiB）。压缩在集成服务器和访客客户端之间端到端进行，网关继续原样转发。
- 一起修 `.mcc` 外部区块文件的命名 bug。
- 差量上传（用上一版区块作为 zstd 字典）暂缓，等测出数据再决定。
- 方案已批准：阶段0（测基线）→ 阶段1（只同步保留区块）→ 阶段2（zstd 流式）。

## 2. 已完成的改动

### 阶段0：统计
- `server/dm-gateway/src/relay.rs`：`Counted<S>` 包装器统计双向字节数，`TrafficLog` 在连接结束时（正常结束、出错或被取消都会）打 INFO 日志 `guest relay traffic ... to_host to_guest elapsed_ms`；`relay()` 新增 `RelayLabel` 参数。新增测试 `relay_counts_both_directions`。
- `server/dm-gateway/src/rooms.rs`：调用 `relay()` 时传入 `RelayLabel { host, session, connection }`。
- `DmWorldCheckpointService.kt`：存档点上传成功后打 INFO 日志："DM世界checkpoint已上传：序列N，区块，清除，文件，删除文件，字节，耗时"。

### 阶段1：存档点只同步保留区块（客户端 `client/mc/1.21.1-neoforge`）
- 新文件 `dm/DmCheckpointKeepSet.kt`：计算保留区块集合（同步区块 + 每个玩家的3×3区块）；region 路径与 维度+区块坐标 互相换算。只处理 `region`、`entities`、`poi` 三类目录，维度目录支持主世界（根目录）、`DIM-1`、`DIM1`、`dimensions/<ns>/<path>`。其他目录里的 `.mca`（模组自己的存储）照旧整份同步。
- 新文件 `dm/DmCheckpointReconcile.kt`：纯函数 `plan()`，决定每个存档点上传哪些区块、清除哪些：
  - 变更记录里在保留集合内的写入优先；
  - 每个保留区块的槽位都和 master 比较 `(timestamp, sectors)`，不一致就从磁盘读取；读取时槽位正好在变化（返回 null）的，留到下个存档点，**不会清除**；
  - 本地已经没有的保留区块发清除；
  - master 上不在保留集合里的区块发清除；
  - 无法识别的 region 文件按1024个槽位全量对比。
- 新文件 `dm/DmPlayerPositions.kt`：托管开始时扫描 `playerdata/*.dat` 得到离线玩家的位置；玩家退出时更新；每个存档点开始时用在线玩家的当前位置覆盖。
- `dm/DmWorldCheckpointService.kt`：
  - 每个存档点开始时（服务器线程）用 `SyncChunkService.backupSnapshot(owner, hostId, sessionId)` 计算保留集合；**拿不到同步区块名单时推迟这次存档点，不发任何清除**（状态显示"世界同步等待同步区块列表"）；
  - 调用 `DmRegionChangeLog.setKeep(keep)`；
  - 不再每次扫描所有 region 文件（`DmRegionScanner.scan` 不再使用）；
  - 按 region 缓存 master 的槽位表 `remoteTables`，只重新拉取摘要变了的；
  - 单个区块读取失败时记 warn 日志并跳过，不会让整个存档点失败；
  - 新增 `onPlayerLoggedOut`。
- `dm/DmRegionChangeLog.kt`：新增 `setKeep` 和 `wants`，`recordWrite`/`recordClear` 只记录保留区块，`activate`/`discard` 时清空保留集合。
- `mixin/mDmRegionFile.java`：在 HEAD 先判断 `wants`，保留集合以外的写入不再复制字节。
- `dm/DmClientEvents.kt`：新增 `onPlayerLoggedOut`，以及阶段2用的 `onPlayerLoggedIn`。
- `dm/DmHostService.kt`：每次托管第一次连上网关时，在聊天栏提示一次"房间只保存同步区块和玩家附近的区域，换房主后其他地方会重新生成"（`keepNoticeShown`）。
- `.mcc` 修复（客户端）：`DmRegionScanner.externalChunkName(region, index)` 改为按**区块坐标**命名 `c.<chunkX>.<chunkZ>.mcc`，之前错误地用了 region 坐标。

### 阶段1：dm-master
- `server/dm-master/src/anvil.rs`：
  - 新增 `external_path(region, index)`，按区块坐标命名，`read_entry`、`write_entry`、`write_external`、`clear` 四处都改用它。这是 `.mcc` 修复：旧代码会导致超大区块下载后读不出来，还可能误删别的区块的 `.mcc` 文件。
  - 新增 `compact(path) -> Compaction {Empty, Rewritten, Unchanged}`：空闲扇区超过一半、且数据扇区不少于16个时重写压缩，保留时间戳。
  - 新增测试 `external_files_are_named_after_the_chunk` 和 `compaction_reclaims_cleared_sectors_and_keeps_entries`；把旧测试的期望名改为 `c.4.0.mcc`。
- `server/dm-master/src/world_checkpoints.rs` 的 `apply_journal`：对本次写过或清除过的 region 执行 `compact`，变空的 region 直接删除。这一步失败只记 warn，不影响提交。上传协议没有改。

### 阶段2：zstd 流式
- 新文件 `mc/common/codec/src/21/kotlin/calebxzau/rdi/mc/zstdcodec/v21/ZstdStreamActivation21.kt`：从 `RServerZstdStream` 抽出来的共享启用逻辑。
- `server/mc/1.21.1-neoforge/.../network/RServerZstdStream.kt`：改为调用上面的共享代码，行为不变。
- 客户端 `DmClientEvents.onPlayerLoggedIn`：只对集成服务器上的玩家调用，本机内存连接会被 `decide()` 排除。窗口默认 `windowLog=25`（32MiB），可以用 `-Drdi.zstream.enabled=false` 关闭。

### 测试
- `DmCheckpointKeepSetTest`（4）、`DmCheckpointReconcileTest`（6）、`DmPlayerPositionsTest`（2）、`DmRegionScannerTest` 新增 `.mcc` 命名用例、`DmRegionChangeLogTest`（用 `activateKeepingSlotOne` 设置保留集合，新增过滤用例）。
- 结果：1.21.1客户端 `:test` **478/478**（之前在 HEAD 上就失败的 `DmRegionChangeLogTest` 现在也通过）；1.21.1独立服务器 mod `test` **380/380**；dm-master `cargo test` **66 通过**（另有1个原本就 ignored）；dm-gateway `cargo test` **2 通过**。

## 3. 实测数据

测试房间"流量基线测试" `01a178e7-57c6-72b1-b73f-affa90a891d4`，世界 `client/mc/1.21.1-neoforge/run/saves/DM基线测试N`（复制自正常地形的 `新的世界 (3)`）。

### 存档点
| 场景 | 旧代码 | 新代码 |
|---|---|---|
| 建房，第一个存档点 | 36.8MB（7462个区块） | — |
| 升级后的第一个存档点 | — | 182KB，上传12个区块，**清除35809个** |
| 空闲时每个存档点 | 8.5MB（加载范围内1358个区块被重存） | 19–31KB |
| 跑图时每个存档点（每10秒 `/tp` 160格） | 104–140MB，平均约580KB/s | 60–82KB |
| master 上这个房间 | 296MB | 108–188KB |

存档点间隔会自动调整到120–240秒。

### 访客流量（网关的 `to_guest`，跑图场景，几乎全是区块数据包）
| 轮次 | 流式 | 进服位置 | 时长 | 发给访客 |
|---|---|---|---|---|
| 1 | 开 | 出生点 | 187秒 | 28.66MB |
| 2 | 关 | x=1834 | 181秒 | 31.11MB |
| 3 | 开 | x=1834 | 171秒 | 31.99MB |

**结论：在区块数据为主的场景里，流式没有效果**（+2.8%，可视为误差）。推测原因：
- 两种模式都是 zstd 3级，没有开启长距离匹配（LDM），远处的重复内容找不到；
- 流式每个包都 flush 一次；
- 集成服务器上没有开 BATCH 扩展；
- 区块包本身就大，跨包能复用的内容少。

### 补测（2026-10-27晚）：往返和挂机
新测试房间"流式测试" `01a17d20-58fd-745f-b5ef-76b3148bd692`，世界 `run/saves/DM流式测试`（复制自 `新的世界 (3)`）。两人都是旁观模式。

| 轮次 | 场景 | 流式 | 时长 | 发给访客 |
|---|---|---|---|---|
| 1 | 往返：每15秒在相距320格的两点间传送，共12次 | 开 | 224秒 | 40.85MB |
| 3 | 往返（同上） | 关 | 199秒 | 41.67MB |
| 2 | 原地挂机，96格内47个生物 | 开 | 187秒 | 4.99MB |
| 4 | 原地挂机，96格内41个生物 | 关 | 186秒 | 4.97MB |

第1轮比第3轮多挂了约25秒。按挂机速率扣掉这部分后，往返场景流式约省3.5%；挂机场景两者相同（第2轮还多了47条误发的聊天消息）。
**结论：在DM里，流式压缩在三种场景下都看不出效果。**
- 往返：每到一个点约发3.4MB压缩数据（未压缩估计10MB以上）。回到同一个点之前，中间又隔了一整趟，32MiB窗口很可能已经够不着上一次的区块数据。
- 挂机：一次连接的流量主要是进服时的那一批区块，之后稳定在每秒10KB左右，小数据包本来就少，流式能省的有限。
- 访客流量的大头始终是"第一次发、或者重新发的区块"，靠压缩窗口解决不了，要靠区块缓存（访客本地已经有的区块不再发送）。

### 移植网络优化 阶段1：集成服务器不发光照数组（2026-10-27，用户已批准）
- 新文件 `client/mc/1.21.1-neoforge/src/main/java/calebxzhou/rdi/mc/client/mixin/mFullBrightLightPacket.java`（从独立服务器 mod 复制，已加入 `rdi.mixins.json` 的 `mixins`）。用户确认所有玩家都装了 RDI，所以没有限定只在 DM 托管时启用。
- 1.21.1客户端 `:test` 478/478 通过；运行时日志确认它在 Server thread 上应用到了 `ClientboundLightUpdatePacketData`；B 的截图光照正常。
- 实测（流式开，网关日志 `server/dm-gateway/logs/`）：往返 36.05MB / 201秒（之前流式开40.85MB、流式关41.67MB，约少12%）；挂机 4.34MB / 177秒（之前4.99MB，约少13%，不过这次 B 在 y=75，之前在 y=100）。
- 后续阶段（区块缓存、pcache、BATCH/pktref/zlibtail、离开时暂停）还没开始，每个阶段都要单独批准。

### 移植网络优化 阶段2：区块缓存（2026-10-27，用户已批准"核心共享、mixin复制"和"DM按房间ID单独缓存"）
- 共享：`ChunkCacheServerService.kt`、`ChunkCacheServerEvents.kt` 从独立服务器 mod 移到 `mc/chunkcache/src/21Server/kotlin`（包名不变）；新接口 `mc/chunkcache/src/server/java/.../ChunkCachePendingChunks.java` 代替独立服务器 mod 的 `APlayerChunkSender`；`ensureContext` 跳过本机内存连接（房主自己）。
- 独立服务器 mod：`build.gradle` 加 `21Server` 源码目录；`mChunkCacheWarmupSender` 实现 `ChunkCachePendingChunks`；`APlayerChunkSender` 移到 `DEL/20261027-dm-chunkcache-p2/` 并从 `rdi.mixins.json` 去掉。
- 客户端 mod：`build.gradle` 加 `src/server/{java,kotlin}`、`src/21Server/kotlin`；复制4个 mixin 到 `calebxzau.rdi.mc.client.mixin`（`rdi.snapshot.mixins.json` 的 `mixins`）；`ChunkCacheClientNetwork` 的5个发往服务器的处理接到 `ChunkCacheServerService`；`RdiChunkCache.beginConnection(roomId)` 新重载，`mChunkCacheConnection` 用 `DmParticipantSession.expectedHostId(data.ip)` 让 DM 访客的缓存放在 `rdi/hosts/<DM房间ID>/`。
- 测试：1.21.1客户端 `:test` 478/478，1.21.1独立服务器 `test` 380/380，1.20.1独立服务器和客户端 `compileJava` 通过。
- 实测（流式开 + 不发光照 + 区块缓存）：
  - 空缓存往返：25.12MB / 206秒（阶段1是36.05MB，少30%）。服务器复用了2155个区块，0次失败；房主 `/tick query` 平均25.5ms，p99 47.8ms（没有不开缓存时的对照数据）。
  - 有缓存后重新进服挂机：1.37MB / 182秒（阶段1是4.34MB，少68%）。预热8秒内报上来600个区块，复用595个，只有9个发了完整区块，结束时没有被压住的区块。
  - 访客缓存目录 `run2/rdi/hosts/01a17d20-…` 已生成（7.9MB）。双方日志都没有区块缓存相关的警告或错误。
  - 没有测：换房主后缓存是否继续命中（需要3个客户端）。按设计缓存目录只和房间ID有关，与房主是谁无关。
- 观察到的问题（独立服务器上也一样，不是 DM 特有的）：往返时同一会话里"回到去过的地方"的复用率逐分钟下降（复用908→753→440，没报上来就发完整区块的 66→1261→1574），客户端统计 too_late 也在增加。传送后服务器发区块比客户端从磁盘准备候选快（预热结束后客户端最多同时准备8个）。以前的预热方案里就把"移动/传送时也压住区块"列为以后再做。
- 环境：在客户端运行时跑 `:test`，两个开发客户端在测试进行中（22:14:51）正常退出了，原因不明，世界已保存。

### 移植网络优化 阶段3：持久化数据包 pcache（2026-10-27，用户已批准，包括共享 `PacketRecordPipeline`）
- 共享（新目录 `mc/common/codec/src/21Server/kotlin`，包名 `calebxzhou.rdi.mc.server.network` 不变，两个 mod 都编译）：`PacketRecordPipeline`、`PacketBatchPolicy21`、`PersistentPacketKinds21`、`RServerPersistentPackets` 从独立服务器 mod 移过来。
  - `PacketRecordPipeline` 不再直接调用 `PacketMetrics`：新增 internal 的 `uncompressedObserver` 回调，由独立服务器 mod 的 `PacketMetrics` 在 init 时注册（批量统计开启时才记录，和原来的判断一样）；`packetIdentity` 移到这里，直接从包类型和负载频道构造，结果不变。
  - `RServerPersistentPackets.onAnswer` 改为公开；`register` 只给独立服务器 mod 用。
- 独立服务器 mod：`build.gradle` 加源码目录；`PacketMetrics` 加 init 注册回调，删掉移走的 `packetIdentity`。
- 客户端 mod：`build.gradle` 加源码目录；新 mixin `mPacketRecordConnection`（只在 SERVERBOUND、非本机连接上安装管线）、`mPacketRecordEncoder`（编码后登记）、`mPersistentPacketTask`（复制），登记在 `rdi.snapshot.mixins.json`；`RClientPersistentPackets` 的 Answer 接到 `RServerPersistentPackets::onAnswer`，pcache 目录改用 `RdiChunkCache.currentHostId()`（和区块缓存同一个目录名；新增的这个方法在 `RdiChunkCache`）。
  - 第一版用 `Minecraft.currentServer` 取地址，结果存到了 `shared`：1.21.1 的 `getCurrentServer()` 来自游戏阶段的连接，配置阶段是 null。
- 测试：1.21.1客户端 `:test` 478/478；独立服务器 `test` 380/380（`PacketRecordPipeline` 6、`PacketCapturePipeline` 8、`PacketMetricsV4` 2、`PersistentPackets21` 3、`PacketBatchPolicy21` 2 都重新编译并通过）。
- 实测（区块缓存也已命中，原地挂机3分钟）：
  - 第一次进 DM 房间：房主日志 "negotiated; the client holds nothing yet"，0.97MB；访客把两张表存进 `run2/rdi/hosts/01a17d20-…/pcache/`。
  - 重新进服：房主日志 "the client holds 1:59e05…, 2:3d54…"，**0.71MB**（少0.26MB，约26%）。两张表原始大小是配方表 262KB、标签表 45KB；省下的量比两张表压缩后的大小多一些，多出的部分应该是挂机流量本身的波动。
  - 双方日志都没有相关的警告或错误。
- 观察：同一个世界、同样的模组，两次托管之间标签表的哈希变了（7e55… 21066字节 → 3d54… 21130字节），所以房主重启后，访客拿到的是增量而不是引用（房主每种表保留4个旧版本作为增量基准）。独立服务器上也一样，不是这次改出来的。
- 环境：1.21.1客户端 `:test` 的工作目录是 `run/`，会滚动替换正在运行的 A 的 `run/logs/latest.log`。跑完测试后要重启 A 再看日志。

### 移植网络优化 阶段4（BATCH、pktref、zlibtail）+ 阶段5（离开时暂停）（2026-10-28，用户已批准）
- 共享：`RServerBatching`、`RServerPacketRefs`、`RServerZlibTail`、`RServerIdle` 从独立服务器 mod 移到 `mc/common/codec/src/21Server/kotlin`（包名不变）。新接口 `mc/common/codec/src/21Server/java/.../IdleEntityPacket.java` 代替独立服务器 mod 的 `AClientboundMoveEntityPacket`、`AClientboundRotateHeadPacket` 访问器；`RServerIdle.onReport` 改为公开，`register` 只给独立服务器 mod 用。
- 独立服务器 mod：新 mixin 类 `mIdleMoveEntityPacket`、`mIdleRotateHeadPacket` 实现 `IdleEntityPacket`；两个访问器移到 `DEL/20261028-dm-netopt-p5/`，`rdi.mixins.json` 相应更新；`build.gradle` 加 `21Server/java`。
- 客户端 mod：`build.gradle` 加 `21Server/java`；复制 `mIdleGate`、`mIdleMoveEntityPacket`、`mIdleRotateHeadPacket`（`rdi.snapshot.mixins.json`）；`DmClientEvents.onPlayerLoggedIn` 在流式之后依次启用批量、数据包引用、zlibtail（和独立服务器的顺序一样），`onServerTickPost` 在集成服务器上每 tick 结束刷出批量缓冲；`RClientIdle` 的上报接到 `RServerIdle.onReport`。
- 测试：1.21.1客户端 `:test` 478/478；独立服务器 `test` 380/380（`RServerPacketRefsTest`、`RServerIdleTest`、批量和 zlibtail 相关测试都重新编译并通过）。独立服务器 mod 的新 mixin 没有在独立服务器上实际启动验证过，客户端里完全相同的副本已经在运行时应用成功。
- 实测（区块缓存和 pcache 都已命中，重新进服后原地挂机3分钟）：
  - 房主日志：`Player dev2 attribute and entity batching active`、`packet references active, slots=256, maxEntryBytes=1024`、`zlib tails active, namespaces=[wonder_shop_story_core]`。
  - 阶段4（B 不暂停，期间没有被判为离开）：**0.645MB**（阶段3是0.711MB，约少9%）。
  - 阶段5（B 打开暂停菜单3分钟）：**0.318MB**（比阶段4再少51%）。房主日志："stepped away" → "is back; dropped 0 and held 33441 updates, restored 76 block, 0 block entity and 51 entity states"。
  - 注意：B 的窗口失去焦点时游戏会自动打开暂停菜单，同样会被当作离开；之前几轮测试里 B 有一部分时间可能处于这种状态（那时还没有阶段5，所以不影响那些数据）。

### 手动接管离线房间（2026-10-28，用户已批准）
- `DmTakeoverService`：新增 `beginManual(hostId, config, parent)`，生成新的候选ID，跳过"等房主离线"，直接打开接管界面并进入现有的申请流程；新增 `canStartManually(host)`（master 报告 Offline 时才允许）；重连时回到加入界面的上一级。文件加了 `@file:OptIn(ExperimentalUuidApi)`，和同目录其他文件一样。
- `DmJoinScreen`：房间离线时提示"房主当前离线，可以接管房间，由你来当房主"，显示「接管房间」按钮（改房间ID或重新查询时隐藏），"返回"下移一行。
- 顺手修了 `DmJoinScreen`、`DmTakeoverScreen` 文字发糊：1.21.1 的 `Screen.render()` 会先画模糊背景，原来的写法先画文字再调用 `super.render()`，文字被一起模糊了。改成先 `super.render()` 再画文字。`DmHostCreationScreen` 有同样的写法，没改。
- 测试：1.21.1客户端 `:test` 479/479（新增 `theJoinScreenOffersATakeoverOnlyForAnOfflineHost`）。master 不需要改。
- 实测（房间"流式测试"，A 退出后房间离线）：
  1. B 在加入界面看到「接管房间」，点击后13秒开始托管（00:37:39 → 00:37:52），网关注册新会话，B 上传了存档点序列156（新的 cycle `01a17e3a…`）。
  2. B 退出托管（DM 会话在 00:39:05 结束；B 本地卡在"保存世界中"，是下面说的 dev2 账号问题，进程被我结束）。
  3. A 打开自己的旧存档：被拒绝，聊天栏提示"房间已由其他玩家接管，这个世界已过期。请通过「加入DM房间」进入"。
  4. A 在加入界面点「接管房间」，约10秒开始托管，用的是 `run/rdi/dm/01a17d20…/world`，关联的 cycle 是 B 上传的 `01a17e3a…`。截图确认文字清晰。
- dev2 名字问题的原因：`client/mc/1.21.1-neoforge/build.gradle` 里 client2 的 `rdi.play` 写的玩家ID是 `68b314bb-adaf-52dd-ab96-e5ed00000000`（davickk 是 `…b5ed…`，只差一位），本地 MongoDB 的 `account` 集合里没有这个ID，也没有叫 dev2 的玩家。master 的 `YggdrasilService.getProfile` 查不到就返回 `GameProfile.getDefault`，名字是 `"RDI" + ID前5位` = `RDI68b31`。所以 B 当房主时不被认作世界主人，没有命令权限，"保存并退出"也会卡住。

### 汇总：重新进服后原地挂机3分钟，访客收到的流量
| 阶段 | 流量 | 说明 |
|---|---|---|
| 最初（流式开） | 4.99MB | y=100 |
| 1 不发光照 | 4.34MB | y=75 |
| 2 区块缓存 | 1.37MB | 有缓存后重新进服 |
| 3 pcache | 0.71MB | 同一会话里 pcache 为空时是0.97MB |
| 4 批量/引用/zlibtail | 0.645MB | |
| 5 离开时暂停 | 0.318MB | 只在访客暂停或最小化时生效 |
往返场景（每15秒传送320格）：最初约41MB → 阶段1 36.05MB → 阶段2 25.12MB；阶段3–5对区块为主的场景影响不大，没有重测。各轮之间生物数量、进服高度略有不同，单轮数据有几十KB的波动。

### 方块同步和接管
A 托管，把 (63,63) 设为同步区块，放好方块并等存档点上传后退出；B 约15秒后接管。B 接管后打开的世界 `run2/rdi/dm/<hostId>/world` 里：
- 同步区块 (63,63) 的青金石块：**在**；
- A 脚下 (125,125) 的绿宝石块：**在**；
- 相邻区块 (62,62) 的钻石块：区块不存在；
- 名单外远处 (187,187) 的金块：region 文件不存在。

和预期一致。验证方式是解压世界文件里的区块数据（脚本 `/tmp/claude-1000/-mnt-c-Users-calebxzhou-Documents-coding-rdi5/6bde509e-f511-4b13-b0f3-7b5e11edfe94/scratchpad/tools/chunkcheck.py`，已随旧会话清掉），因为 B 在游戏里没有命令权限，见第5节。

### 创建房间界面文字发糊（2026-10-28，用户已批准）
- `DmHostCreationScreen.render` 改成先 `super.render()`，再画标题、提示、进度条和房间ID，去掉手动调用的 `renderBackground`（`Screen.render` 自己会先画背景）。和 `DmJoinScreen`、`DmTakeoverScreen` 的写法一样。
- 1.21.1客户端 `:test` 479/479。用 `/dm create` 建房时截图，文字清晰，背景模糊正常。

### 差量上传测量（2026-10-28，没有改代码）
- 场景：`run/saves/DM基地测试`（复制自 `新的世界 (3)`），建房"基地测试" `01a1805e-32ec-7129-ac00-1e7dd4b4d153`。用命令在 y=200 的平台上搭了4×4区块的基地，每个区块有32个点着的熔炉（生铁+煤炭块）、一段循环传圆石的漏斗环、三排小麦和4只动物（一共64只）。16个区块都加入了同步区块，A 站在中间（3×3也在基地里），没有访客。
- 存档点稳定在每60秒一次，每次上传32个区块（region 16个，entities 15–16个），165KB左右。间隔的规则：默认60秒；主线程停顿超过50ms就翻倍，最多300秒；连续3次停顿都低就减半，最少60秒。之前跑图时间隔变长是因为停顿大。
- 方法：每次上传后复制 master 上的 `world-checkpoint/world`，逐个区块对比相邻两份（脚本 `/tmp/claude-1000/-mnt-c-Users-calebxzhou-Documents-coding-rdi5/6a4b4052-008a-4726-8aa7-80088394dff8/scratchpad/tools/deltacheck.py`）。5组对比的结果很一致：

| 每个存档点的区块数据 | 字节 | 和现在相比 |
|---|---|---|
| 现在（region 里的 zlib 数据原样上传） | 151KB | 1.00 |
| 解压后改用 zstd 3级 | 164KB | 1.085 |
| 以上一版为参照的 zstd 差量（`--patch-from`） | 9.4–12.7KB | 0.06–0.08 |

  每个区块解压后的 NBT 约88KB，两次存档点之间真正变化的只有熔炉进度、漏斗冷却和物品数量、动物位置这些。差量后每个 region 区块约0.4–0.6KB，每个 entities 区块约0.2KB。
- 换算：这个16区块的基地让房主上行约2.5KB/s，差量后约0.2KB/s。按同步区块上限256个、全部在运转估算，约40KB/s，差量后约3KB/s。作为对比，网络优化后一个挂机访客约3.6KB/s（0.645MB/180秒）。
- 结论：
  - 只把 zlib 换成 zstd 反而大8%，不做。
  - 差量能省约92%。基地小时省下的绝对量不大；基地大（上百个运转中的区块）时，存档点上传会超过所有挂机访客的流量加起来，值得做。做不做等用户决定。
- 如果要做，要点：
  - 客户端为每个保留区块记住 master 最后确认的版本（存 zlib 原样即可，256个区块约2.4MB，用的时候再解压）。有基准的区块上传 `{基准sha1, zstd差量}`，没有基准、或者 master 说基准对不上时发全量。换房主后，下载下来的存档点就是基准。
  - master 读出自己 region 里的旧版本，核对 sha1，解压、套用差量，再用 zlib 压回去写入 region，这样下载出去的世界仍然是标准的 Anvil 格式。上传格式要升版本，旧客户端照常发全量。

### 独立服务器回归冒烟测试（2026-10-28晚，没有改代码）
阶段2–5把代码从独立服务器 mod 移到共享目录，这次在独立服务器上实际启动验证。启动脚本是 `server/mc/<版本>/build/smoke-runServer.ps1` 和 `client/mc/<版本>/build/smoke-runClient.ps1`，日志在同目录的 `smoke-*.log`。
- **1.21.1**（`runServer` + `runClient`，A 快速加入 `localhost:6370`）：
  - 服务器启动没有新的警告，只有 ars nouveau 的 mixin 目标找不到，原来就有；
  - `mIdleGate`、`mIdleMoveEntityPacket`、`mIdleRotateHeadPacket`、`mChunkCacheSender`、`mChunkCacheWarmupSender`、`mPersistentPacketTask`、`mFullBrightLightPacket` 都已应用；
  - 日志里依次出现：pcache 协商（客户端持有两种表）、Zstd stream windowLog=25、batching、packet references、zlib tails；
  - 区块缓存：预热 offered=558、heldAtEnd=1374；第一分钟复用3639个，全部确认，mismatches=4，clientFailure=0。10-26改动前同一个世界是复用3601、mismatches=2，基本一致；
  - 离开时暂停：打开暂停菜单后2秒打出 "stepped away"，关闭后打出 "is back; dropped 0 and held 6704 updates"；
  - `/tick query` 平均12.2ms，p99 16.7ms；
  - 在相距320格的两点间往返传送4次：那一分钟复用5142个、mismatches=611、clientFailure=0。mismatches 是"缓存里的区块和服务器上的不一致"，会改发完整区块。处理哈希比对的 `ChunkCacheReuseSender` 这次没有改，之前没有同样场景的对照数据；
  - 进服后两边都没有相关的 WARN/ERROR。客户端的 EMI 配方报错从10-12起每次进服都有，和这次改动无关；
  - 用 `/stop` 关闭，世界正常保存。
- **1.20.1**（JDK17，`runServer` + `runClient`，端口1450）：受影响的只有共享的 `RdiChunkCache`（新增重载）和新接口文件。pcache 命中，批量、流式、数据包引用都已启用；区块缓存预热 offered=1157，第一分钟复用3536个，全部确认，clientFailure=0；进服后服务器没有 WARN/ERROR。
- 结论：**独立服务器没有发现回归**。

### dev2/dev3 账号和三客户端接管选举（2026-10-28晚，用户已批准）
- 本地 MongoDB `rdi5skypro.account` 补了两条记录（用 scratchpad 里的 Node mongodb 驱动写入，机器上没有 mongosh）：
  - dev2：`_id=68b314bbadaf52ddab96e5ed`，对应 UUID `68b314bb-adaf-52dd-ab96-e5ed00000000`；
  - dev3：`_id=68b314bbadaf52ddab96e6ed`，对应 UUID `68b314bb-adaf-52dd-ab96-e6ed00000000`；
  - 两条都是开发库里的测试账号，默认皮肤。
  - 注意：玩家 UUID 的最后4字节必须是0（`UUID.toObjectId`），所以第三个账号不能用 `…e5ed00000001`。
  - master 的 `/session/minecraft/profile/<uuid>` 已经返回 dev2 和 dev3。PG 镜像表会在 master 下次启动扫描时补上。
- `client/mc/1.21.1-neoforge/build.gradle` 新增 `client3`（`run3/`，dev3），`client3LegacyClasspath` 加进 javacv 的两处配置，idea 排除 `run3`；模块 `.gitignore` 加 `run3`。`run3/options.txt` 复制自 `run2`（中文，跳过欢迎界面和多人游戏警告）。
- 三个客户端同时开内存不够（机器29.7GB，A 默认堆时占4GB）。启动脚本 `build/dm-base-runClient.ps1`、`dm-b-runClient2.ps1`、`dm-c-runClient3.ps1` 加了 `$env:JDK_JAVA_OPTIONS='-Xmx2G'`，日志里能看到 "Picked up JDK_JAVA_OPTIONS"。2GB 时上报的 maxMemoryBytes=2147483648，低于4GiB，B 和 C 都不算"合格"。
- 过程（房间"基地测试" `01a1805e…`）：
  1. A 打开 `DM基地测试` 托管；B、C 从「加入DM房间」进房，都上报了指标。B：下载55MB/s，启动25.8秒；C：下载6.6MB/s，启动27.4秒。
  2. 把 B、C 传送到 A 的基地，等存档点131上传。存档点会先保存玩家数据（playerdata 的修改时间和上传时间一致）。
  3. 22:28:45 A 保存并退出。B、C 相隔17ms 发现断线。22:29:00 B 打出 "Took over DM host"（15秒）；22:29:01 C 自动连上 B 的新房间。按排序规则 B 应该当选，结果一致。master 和客户端都不记录 claim，所以"两人都在3秒窗口内申请"只能从时间上推断。
  4. C 回到传送后的位置 (-225.5, 202.5, -803.5)。B 当房主后 C 的流式、批量、引用、zlibtail、pcache 都正常启用。
  5. **dev2 修复确认**：B 当房主后 `/tick query` 能执行，"保存并退出"约2秒回到标题画面，以前会卡住。B 的 tick 平均39ms，p99 110ms，是在内存很紧时测的。
- **问题1：换房主后访客的区块缓存基本命中不了。** C 在新房主那里第一分钟 normalAttempts=609，mismatches=601，复用只有8个。
  - **原因（2026-10-28晚查明）：同一个区块重新生成，结果并不完全一样。** 换房主后，新房主只有存档点保留的区块（同步区块 + 每个玩家周围3×3），其余区块都要重新生成。区块缓存的哈希（`ChunkTerrainCodec.sectionHash`）只算每个 section 的方块状态 ID 和生物群系 ID，不算光照和方块实体。整个区块只要错一个方块就算不一致，改发完整区块。
  - 证据：用 scratchpad 脚本 `tools/chunkdiff.py`、`tools/sectionstats.py`（读 Anvil + NBT，逐方块对比，纯 Python 无依赖；scratchpad 会随会话清掉，需要时重写）对比同一个区块：
    - 存档点保留的同步区块（基地的 -14,-51、-15,-50），新旧世界完全一样；
    - 重新生成的区块，生物群系全部一样，但方块都有差异，主要是：
      - 矿石和石头变体：煤矿↔石头，闪长岩/花岗岩/安山岩团块，Create 的 crimsite/veridium；
      - 幽匿块：深板岩↔幽匿块；
      - 树叶的 distance 属性，以及跨区块的树冠；
      - 岩浆和水的流动状态。
    - 用当前同一套模组重新生成两次（A 和 B 各自接管时的世界 `run/rdi/dm/01a1805e…/world`、`run2/rdi/dm/01a1805e…/world`），结果也不一样。InhabitedTime=0 的区块也会差几个方块，所以和9月用旧版模组生成无关。
    - 基地周围16×16个区块（`run/saves/DM基地测试` 对比 `run2/…/world`）：整个区块相同的只有13/256（5%），基本就是保留区块，和实测的复用8/609一致。单个 section 相同的占71%；非纯色 section 中相同的占52%；方块数据量里约53%在相同的 section 里。
  - 机制：矿石、石头团块、树、幽匿块这些装饰会跨出所在区块，写进相邻区块，最后谁覆盖谁取决于相邻区块装饰的先后顺序，而这个顺序取决于玩家的移动路线和生成线程的调度。另外，区块生成后的 tick（液体流动、树叶更新）也会改变方块。同一个种子只能保证地形骨架和生物群系一样。
  - 影响：
    - 每次换房主后，访客第一次进入时，保留区块以外的区域基本都要重新下载一次。新区块会写进访客缓存，之后再进同一个房主的房间就能命中。
    - 对玩家来说，同步区块以外重新生成的地方，矿石位置、石头团块、树的细节会和以前不一样，不是原样生成回来。
  - 可选方向：
    1. 接受现状（建议）：换房主不常发生，每次只多下载一次。
    2. 区块缓存改成按 section 复用，只重发变了的 section。换房主后能挽回约一半的数据量；平时一个区块改了一个方块（建造、熔炉、作物）也不用整个区块重发。要改区块缓存协议，客户端和独立服务器都要动，要先出方案。
    3. 存档点保留更多区块：和省上行带宽、省存储的目标冲突，而且范围之外照样会重新生成。
  - 另外，独立服务器往返传送时出现的611个不一致（见上一节）没有换房主，原因还不清楚。如果做方向2，可以顺便查一下。
- **问题2：在房间里，同一个玩家当房主和当访客时是两个不同的角色。** 访客进 DM 房间用的是离线 UUID（dev2=`4431b0a7…`，dev3=`dd11d560…`，按 `OfflinePlayer:<名字>` 生成），房主用的是 RDI 账号 UUID。B 接管后的世界里同时有 `4431b0a7….dat`（当访客时的角色）和新建的 `68b314bb-…-e5ed….dat`。结果是 B 当房主后从出生点 (-99.5, 65, -250.5) 开始，背包也是空的；原房主 davickk 以后用访客身份进来也会是新角色。master 也查不到离线 UUID，所以访客皮肤注入时名字变成 `RDI4431b`。
  - 原因：集成服务器开着正版验证。访客没有 Mojang 会话，验证失败后，原版对单人游戏服务器有个兜底（`ServerLoginPacketListenerImpl` 第201–203行，日志 "Failed to verify username but will let them in anyway!"），会用离线 UUID 放行。独立服务器 mod 有 `mServerLoginPacketListener`，用 hello 包里的 profileId 作为身份；客户端 mod 里一直没有这个 mixin。
  - **已修（2026-10-28晚，用户已批准）**，改动在客户端 mod 的 `mixin/mProtocol.java` 和 `rdi.mixins.json`：
    - 新增 `mServerLoginPacketListener`：
      - `handleHello` HEAD 记下访客提交的 `GameProfile(profileId, name)`；
      - `startClientVerification` HEAD 用 `@ModifyVariable` 把参数换成这个 profile。离线、验证成功、验证失败兜底三条路径都会经过这里。第一版改在 `handleHello` TAIL，会被验证线程覆盖，所以没用；
      - 本机内存连接（房主自己）不替换，保留带皮肤的单人 profile；
      - 访客提交的 profileId 和房主相同时，拒绝登录，提示"这个账号正在当房主，不能再以访客身份进入"；
      - 登录超时600→2400 tick，和独立服务器一样。
    - `mServerboundHelloPacket` 新增读取端的名字长度上限16→64，对应独立服务器的 `mAllowChineseNameLogin`。
  - 测试：1.21.1客户端 `:test` 479/479。实测（房间"基地测试"，2个客户端，2GB 内存）：
    1. A 手动接管后托管，B 以访客进房：身份是 `68b314bb-…-e5ed`，皮肤注入找到 dev2；出现在 B 上次当房主时的位置 (-99.5, 65, -250.5)。**房主→访客角色接上了。**
    2. 给 B 7个钻石，传送到 (-240, 203, -780)，等存档点140上传。A 退出，B 12秒接管，出现在 (-239.5, 201, -779.5)，`/clear dev2 minecraft:diamond 0` 找到7个。**访客→房主角色接上了。**
    3. A（原房主）以访客进 B 的房间：身份是 `…b5ed`，出现在 A 当房主时的位置。
  - 没有在游戏里测：用房主自己的账号以访客身份进入（拒绝路径），需要两个客户端用同一个账号。
  - 旧测试世界里按离线 UUID 存的玩家数据（`4431b0a7…`、`dd11d560…`）作废，不迁移（DM 还没上线）。
  - **集成服务器改为离线模式**（2026-10-28晚，用户决定：不需要 Minecraft 自带的连接加密，以后还要支持局域网部署）：同一文件新增 `mIntegratedServerOffline`，在 `IntegratedServer.publishServer` 开头调用 `setUsesAuthentication(false)`，登记在 `rdi.mixins.json` 的 `client` 里。DM 托管和"对局域网开放"都要先调用 `publishServer`，所以两种情况都会生效。访客走离线分支，再由 `startClientVerification` 替换成 RDI 身份。
    - 测试：`:test` 479/479。实测时日志里不再有 "Failed to verify username"；B 仍是 `…e5ed`，出现在上次当房主时的位置。进房时间（Connecting → logged in）从2.9秒降到1.9秒。
    - 没有实测原版的"对局域网开放"，它走的是同一个 `publishServer`。

### 当选者接管途中被关掉（2026-10-29，没有改代码）
房间"基地测试"，三个客户端（2GB内存）。A 手动接管后托管，B、C 从「加入DM房间」进房并上报指标（B 启动20.3秒、下载3.0MB/s；C 启动21.8秒、下载15.5MB/s）。监视脚本一发现 `rdi/dm/world-init-*.zip` 就强制结束对应的游戏进程。
| 时间 | 事件 |
|---|---|
| 12:23:40.5 | A 保存并退出 |
| 12:23:44.1 | B 当选，世界下载并解压完成（写了 `complete` 标记） |
| 12:23:45.2 | B 在打开世界途中被强制结束，没有释放申请 |
| 12:23:45–12:24:44 | master 一直是 `Claimed`；C 显示"其他玩家正在接管房间，稍后自动重连" |
| 12:24:44 | B 的租约到期（当选后60秒），C 重新申请，状态变成 `Electing` |
| 12:24:47 | 3秒选举窗口结束，C 当选 |
| 12:24:56.7 | C 开始托管，出现在自己上次的位置 (-85.5, 66, -260.5) |
| 12:25:57 | C 上传存档点144（新 cycle `01a185e8…`），66KB，494毫秒 |
- 结论：**当选者中途消失后，其他人能自动接上**。从房主退出到新房主开好约76秒，其中60秒是在等租约过期。C 的日志里没有 DM 相关的警告或错误。
- 世界很小，本地下载不到1秒，B 实际是在"打开世界"阶段被结束，不是在传 zip 的途中。对 master 来说两者一样（当选者拿着租约消失），要严格测"传输途中"需要给下载限速。
- 当选看的是"预计下载时间+启动时间"，世界小时主要比启动时间，所以 B 赢了 C。
- 租约缩短（2026-10-29，用户已批准）：dm-master `takeover.rs` 的 `CLAIM_LEASE` 60→30秒，测试里的续约时间点改为 `CLAIM_LEASE / 2`；客户端 `DmTakeoverService.RENEW_INTERVAL_MILLIS` 20→10秒，`DmHttpClient.renewTakeover` 请求超时30→10秒（一个卡住的续约请求不会把租约耗完）。dm-master `cargo test` 66通过（1个原本就 ignored），1.21.1客户端 `:test` 479/479。重启 master 后重测（这次 C 当选，启动28.3秒对 B 的33.6秒）：12:42:31.6 A 退出 → 12:42:38.6 C 在打开世界前被结束 → 12:43:05 C 的租约到期 → 12:43:06 B 申请 → 12:43:09 B 当选 → 12:43:19 B 开始托管，出现在自己上次的位置 → 12:44:20 B 上传存档点150。**从房主退出到新房主开好约47秒**（之前76秒）。
- 重启方式：RustRover MCP 只打开了 dm-gateway 项目，没有 dm-master 的运行配置；在它的终端里执行 `cargo run` 时要等用户在 IDE 里确认，超时了。现在的 master 是用隐藏的 `Start-Process cargo run` 启动的，输出在 `server/dm-master/dm-master-run.log`、`dm-master-run.err.log`，按天的日志照常写在 `logs/`。

### Mod一致性检查（2026-10-29，用户已批准）
用户的决定：比对注册表，不比jar的sha1；整合包升级时允许确认"按我现在的Mod更新房间"（方案b），并记录创建者和更新者的玩家ID；旧房间等第一个新存档点再记录指纹；版本不同时加入只提示，选举和最终检查要拦。
- 指纹：按命名空间对"注册表名 条目ID"排序后算SHA-1，总哈希是对排好序的 `命名空间=哈希` 各行再算SHA-1（客户端和master用同一组测试向量核对）。`registries` 是NeoForge会同步的静态注册表（`doesSync()`），和NeoForge进房检查的范围一致；`world` 是集成服务器的 `WORLDGEN`、`DIMENSIONS` 两层数据包注册表；`labels` 只用来显示Mod名称。
- dm-master：新文件 `src/mods.rs`（`ModFingerprint`、房间目录下的 `mods.json`，带 `updatedBy`）；`HostRecord.creatorId`；建房时可选的multipart字段 `creatorId`、`modFingerprint`；存档点 `metadata.json` 可选字段 `modFingerprint`、`hostPlayerId`，提交时更新 `mods.json`；`CandidateMetrics.registryFingerprint`；`claim()` 对指纹不一致的候选人返回 `Incompatible`；`GET /hosts/{id}` 增加 `modFingerprint`（静态总哈希），新增 `GET /hosts/{id}/mods`。
- 客户端：新文件 `DmModFingerprint.kt`（纯逻辑＋`DmModFingerprints` 读游戏注册表；静态部分在第一次进标题画面时算好并缓存，因为连上服务器后同步注册表会被换成服务器的）、`DmModUpdateScreen.kt`；建房和存档点带上指纹；上报指标带静态指纹；`DmTakeoverService` 处理 `Incompatible`，新增 `isAutomatic`；`DmHostService.start` 在过期检查之后比对完整指纹：自动接管时不一致就停止托管并释放资格，其他情况弹出确认界面；`DmJoinScreen` 在房间在线时，缺Mod禁止加入、版本不同要点第二次、多装的Mod放行；房间离线时照样可以接管，并提示需要确认更新。
- 实测中发现并修复：最初的写法里，Mod不一致的人在加入界面看不到「接管房间」按钮，整合包整体升级后房间会没人能接管，方案(b)也走不到。改为离线房间照样可以接管，打开世界后再确认。
- 测试：dm-master `cargo test` 71通过（1个原本就 ignored）；1.21.1客户端 `:test` 485/485（新增 `DmModFingerprintTest` 6个）。
- 实测（房间"基地测试"，三个客户端；测试用的Mod来自 rdi5ship：带方块的 `cobblestone-generator`，只有数据包的 `soul-bound-enchantment`）：
  1. 旧房间第一次存档点（序列152）后记录了指纹：静态部分9个命名空间（含jei、brigadier），世界部分3个，`updatedBy` 是A。
  2. B装 cobblestone-generator，静态指纹 `dbb550…`，能进A的房间；C装 soulbound，静态指纹和房间一样（`98458a…`），说明只有数据包的Mod不影响静态部分。
  3. A退出：B的申请被拒，界面提示"你的Mod和房间不一致，不能接管房间。多出：Cobblestone Generator …"；C当选，但最终检查发现"多出：Soulbound 1.0.1"，停止托管并释放资格，master的序号一直是155，没有被上传。
  4. A也装上 cobblestone-generator：加入界面提示可以接管、但需要确认更新；打开世界后弹出确认界面，确认后开始托管，存档点156把房间指纹改成 `dbb550…`（含cobblestone_generator），`updatedBy` 是A。之后B正常进房，C被拦下，提示"你的整合包缺少房间需要的Mod，无法加入。缺少：Cobblestone Generator …"。
- 没测：版本不同的情况（需要同一个Mod的两个版本，要点第二次「加入/重连」）；房间在线时取消更新的路径。
- 现场：房间"基地测试"现在要求装 cobblestone-generator。测试用的jar还在 `run/mods`、`run2/mods`（cobblestone-generator）和 `run3/mods`（soulbound）里，等客户端关掉后再移到 `DEL/`。

### 房主退出时补传最终存档点（2026-10-29，用户已批准）
- 问题：原来房主一断开（`LoggingOut`）就关隧道、停存档点服务，比服务器真正存盘还早，上一个存档点之后的改动都会丢。如果只是把隧道留着，访客被踢出时又会看到房间"在线"，按 `afterDisconnect` 的规则直接放弃接管。所以第1项（补传最终存档点）和第4项（退出时主动通知master）要一起做。
- dm-master：`presence.rs` 新增 `leaving`，记录"会话还在、但正在离开"的房间，隧道断开、有新会话上线或者网关重新同步时会相应清除；`PresenceState::Leaving`（不能加入、不能接管，存档点照常接受）；新接口 `POST /hosts/{id}/leaving`（带 `X-DM-Session`）；`Leaving` 时申请接管返回 `HostLeaving`。
- 客户端：`LoggingOut` 时如果是已连上网关的房主，调用 `DmHostService.beginLeaving()`，保留会话；`ServerStoppingEvent` 时通知master"正在离开"，并按在线玩家的位置算好保留区块（`prepareFinal`）；`ServerStoppedEvent` 时区块已经全部存盘，先等正在上传的常规存档点传完，再同步跑一次 `runJob`（`runFinal`，总时限20秒），然后 `finishLeaving` 关闭隧道。访客等待房主离线的时限从20秒改为60秒。
- 实测中发现并修复：`Minecraft.disconnect` 会在触发 `LoggingOut` 之前把 `singleplayerServer` 设成null，第一版用它来判断，结果走回了老流程。改为判断 `owner.isShutdown`。
- 测试：dm-master `cargo test` 72通过（新增"正在离开"状态的测试，接管API测试里补了 `HostLeaving`）；1.21.1客户端 `:test` 485/485（`DmTakeoverServiceTest` 补了 `Leaving` 的情况）。
- 实测（房间"基地测试"，A当房主，B当访客；每次都在常规存档点刚传完时用命令放一个方块，然后立刻保存并退出）：
  | | 修复前（14:26） | 修复后（14:36） |
  |---|---|---|
  | 房主点退出 → 回到标题画面 | 5.0秒 | 5.2秒 |
  | 最终存档点 | 没有 | 序列164，176KB，345毫秒 |
  | 房主点退出 → B开始托管 | 12秒 | 17秒 |
  | B的世界里有没有那个方块 | 没有（钻石块丢了） | 有（金块在） |
  多出来的约5秒是服务器存盘（约4秒）加上传，这段时间在B接管之前完成。原来存盘和接管是同时进行的，接管用的是旧数据。
- 没测：最终存档点超时（20秒）的路径；房主崩溃（这种情况本来就补传不了）。
- 发现但没改：`DmClientEvents.onClientTick` 上没有 `@SubscribeEvent`，`DmHostService.onTick()` 一直不会执行（原来就是这样）。

### P1.1：上传失败后序号无法恢复（2026-10-30，用户已批准）

- 对应[DM_issues.md的问题1](DM_issues.md)。**代码修改已完成，编译、测试和实机验证未完成。**
- 原因：客户端在上传成功前推进序号；一次未提交的上传失败后，后续序号持续超出master要求。
- 新增客户端`dm/DmCheckpointUploadState.kt`：每个会话保留一个待确认原ZIP及其序号、cycle、SHA-1；先查询master，确认已提交则核对session、cycle、哈希、长度及实际应用序号，未提交则重试原包。查询失败或状态不匹配时保留原包并报错，不推进序号。
- `DmHttpClient.kt`：解析状态接口已有的`uploadSha1`和`uploadZipBytes`字段，服务端协议没有改动。
- `DmWorldCheckpointService.kt`：先解决待确认上传，再采集新存档点；打包失败恢复已取出的区块变更。上传包保留到master应用完成且本地关联写入成功。取消或替换会话时，旧任务结束后才清理其暂存目录；旧任务不能把变更合回新会话。暂存目录创建失败也会释放运行中任务状态。
- 退出补传：先解决旧上传，再生成最终存档点，继续使用原20秒总时限。
- 日志变化：成功日志改为`DM世界checkpoint已确认`，包含序号、区块、清除、文件、删除文件和字节数；任务总耗时单独记录在DEBUG日志中。
- 已新增`DmCheckpointUploadStateTest.kt`的13项用例，**未运行**。用例覆盖上传中断与连续失败、响应丢失、查询失败、关联写入失败、未应用或不匹配的回执、取消、打包失败恢复、最终存档顺序和新会话起始序号。
- 验证：`git diff --check`通过。IntelliJ构建请求未启动，提示`client/mc/1.21.1-neoforge`不是已打开的项目，当前只有`server/master`。用户随后明确要求不测试、只更新进度，因此没有运行Gradle回退、测试或实机验证；不能记为构建或测试通过。
- 范围与限制：本次只修改客户端P1.1及其文档。待确认上传状态属于当前进程内的托管会话，不是可跨进程重启恢复的持久队列。master未应用完journal时客户端会报错并停止推进，P1.2本身仍待修复；P1.3及其他问题同样未处理。

### P1.1验证、P2.5和P2.6：接管可靠性（2026-11-01，用户已批准）

- P1.1：编译和客户端`:test`通过（498项，含13项新用例），此前记录的"未运行"到此结束。
- [DM_issues.md的问题6](DM_issues.md)：`DmTakeoverService.afterDisconnect(host, elapsedMillis)`，断线后查到`Online`时最多再等30秒。依据：访客约30秒超时，网关45秒才判定房主掉线，离线上报失败要等10秒后重试。
- [DM_issues.md的问题5](DM_issues.md)：
  - dm-master：`Takeovers::authorize_registration`和`POST /gateway/hosts/{id}/registration`。有当选者时只放行匹配的claim并续满租约；没有当选者时拒绝任何claim、放行不带claim的注册。读房间失败返回HTTP 500，网关按"master不可用"处理。
  - dm-gateway：角色`3`（hostId + claimId）、状态`10`（CLAIM_REFUSED），用上面的接口取代原来的房间存在检查。
  - 客户端：`DmHostTunnel`第一次`READY`前带claim，之后重连不带。`DmTakeoverService`打开世界前收到`ClaimLost`就丢弃下载并重新申请；打开世界后由网关决定（master在托管开始时也会清除claim）。进入世界后被拒绝：自动保存退出（用户决定），回到接管界面重新申请。下载不能中途取消，要等当前下载结束才丢弃。
- 测试：dm-master `cargo test` 75通过，dm-gateway 6通过，客户端`:test` 501/501。
- 集成测试（新版二进制 + 真实TCP/HTTP）：21项全部通过。
- 实机（A、B两个客户端，房间"基地测试"的副本，新版master和网关另起在WSL的15567/25567，经可注入故障的代理访问）：
  1. B只是自己超时断线，房间在线：等30.5秒后放弃，没有误接管。
  2. A退出，丢弃"正在离开"通知并延迟离线上报：B断线后约23秒一直查到`Online`，变成`Offline`后接管（21:11:36断线 → 21:12:15托管），A的最终存档点也传上去了。
  3. 当选的A进入世界后被拒绝：21:15:23被拒 → 21:15:25退出并重新申请 → 21:15:33重新托管。
  4. B下载世界时收到`ClaimLost`：丢弃下载并重新申请，21:50:55托管成功。
  5. 重启网关：B重连时不带claim，恢复托管，存档点继续上传（序列326）。
- 没有测到：真正崩溃（结束进程被权限规则拦下，场景2用丢弃通知代替）；三人同时竞争（C的`run3`缺cobblestone-generator，被加入检查拦下，这轮让C退出了）。
- **部署注意：**新版客户端带claim注册时，旧网关会拒绝角色`3`；新版网关依赖master的新接口。三端要一起更新。本机的`dm-master`（PID 81460）和`dm-gateway`（PID 59660）还是10月29日的旧版，停进程被权限规则拦下，要用户重启后才能用新代码。

### P1.2、P1.3和下载回退（2026-11-01，用户已批准，鉴权之后再做）

- dm-master `world_checkpoints.rs`：`commit`拆成`validate_upload`、`accept`、`apply_pending`。存档点的回执如实记录`applied_sequence`；`download`先补写，失败返回503；状态接口也会重试补写，因为客户端看到未写完时只查询、不重传；`recover`按房间隔离错误。文件写入失败时清理临时文件。
- dm-master `http.rs`：新增`commit_world_checkpoint`，在`commit_gate`内重新检查会话后再落盘，写入世界放在锁外。存储错误的细节只写日志，返回给客户端的是通用信息。
- 客户端`DmWorldCache`：只有`NoCheckpoint`才退回初始世界。
- 测试：dm-master `cargo test` 86通过（1个原本就ignored），客户端`:test` 502/502。dm-gateway没改。
- 实机（A、B，WSL里的测试master和网关，把测试数据里的`level.dat`换成目录，让写世界真的失败）：
  1. 托管中写盘失败：存档点报错，master显示`last 328 applied 327`；解除后序列328自动补写，序列329正常。
  2. A退出，最终存档点停在`last 330 applied 329`；master重启时只记录这个房间的错误，照常启动。B手动接管被拒绝，本地世界没变，资格已释放，提示“接管失败：房间的世界暂时无法下载，请稍后再试”。解除后B接管成功，世界包含序列330。
  3. 自动选举：B退出，A当选后下载被拒绝，本地世界和归档都没变，房间保持离线。
- 没有实机验证：P1.3（旧上传在最后关头被拒绝），实机很难卡住这个时间窗口，只有单元测试。
- 部署注意：本机`dm-master`（2026-11-01 21:56启动）不含这些修改，需要用户重启。新版客户端和旧版master可以配合。
- 发现但未解决：访客掉线，见[DM_issues.md的问题8](DM_issues.md)。

### 鉴权（2026-11-02，用户已批准）

用户的决定：复用RDI账号，不新建账号系统；游戏读`user.accessToken`；不支持本地游玩模式；用公钥验票；拿到房间ID的已登录玩家都能进房和接管；每人最多建5个房间；不要求绑定微软账号；`hs_err`里的token不处理；密码哈希以后再做。

- 票据：RDI master的`POST /player/dm-ticket`（`auth-jwt`保护）签发Ed25519票据，格式是`base64url(JSON).base64url(签名)`。`api`30分钟，`tunnel`和`join`60秒且绑定房间，`join`只能用一次。三种用途不能混用。
- master：`calebxzau.rdi.server.service.dmticket.DmTicketIssuer`，`config.toml`的`[dmTicket]`（`privateKey`是base64的PKCS#8）。Gradle任务`dmTicketTest`。
- dm-master：`tickets.rs`；路由分三组。玩家路由要`api`票据，会话路由沿用`X-DM-Session`（房主运行中不依赖RDI master），`/gateway/*`要`DM_GATEWAY_TOKEN`。`creatorId`取自票据，上传里的字段被忽略。接管资格和候选人都绑定玩家。`host.json`新增`lastHostId`：没有接管资格时，只有上次的房主能直接注册（从未托管过的房间是创建者）。缺公钥或网关凭证时拒绝启动，开发环境也一样。
- 网关：隧道注册多带“2字节长度+`tunnel`票据”，转交dm-master验证；新状态`TICKET_REFUSED`(11)、`NOT_OWNER`(12)。
- 客户端：`DmTickets`（`api`票据用到一半就刷新，刷新失败时继续用未过期的旧票据）、`DmJoinGate`（房主验票，用`/info`的`serverTimeSeconds`校正时钟）、hello包末尾带`join`票据（只在连接DM房间时带）。`DmJoinGate.BUILT_IN_KEYS`现在是空的，**正式发版前要写入正式公钥**。开发环境用`-Drdi.dm.devJwt`、`-Drdi.dm.ticketPublicKey`，只在`rdi.debug`时生效。
- 启动器：日志里的`--accessToken`值改为`***`。
- 测试：master `dmTicketTest` 5项，dm-master `cargo test` 103项（1个原本就ignored），dm-gateway 9项，1.21.1客户端`:test` 516项，全部通过。三端用同一个测试票据核对格式和签名。
- 实机（Windows上的真实RDI master、dm-master、网关，dev2当B、dev3当C，房间“鉴权测试”）：
  - 签票接口：无JWT和错误JWT返回401，用途错误或缺房间被拒，票据能通过openssl验签。
  - dm-master：不带票据的玩家路由和`/gateway/*`都返回401，带网关凭证返回200。
  - 建房、C带票据进房，身份正确，mixin在record上工作正常。
  - 用原始登录包攻击房主：不带票据冒充、乱写票据、改payload、`tunnel`或`api`票据当`join`、别的房间的票据都被拒绝；房主账号当访客被拒；hello自称davickk但票据是dev3时，登录身份是dev3；同一张票据重放被拒。
  - 自动接管：B保存退出后约23秒C开始托管，身份、位置、7颗钻石都在，`lastHostId`变成C。
  - 旧房主打开旧存档：被拒绝。这次显示的是“DM房间正在使用”，当时房间在线；“世界已过期”提示这轮没有触发。B以访客进房，位置正确。
  - 隧道注册（房间离线）：别的玩家不带资格→`NOT_OWNER`；错误票据、`join`票据、别的房间的票据→`TICKET_REFUSED`；伪造资格→`CLAIM_REFUSED`；上次的房主→`READY`。房间在线时→`BUSY`。
  - 手动接管：B在加入界面点「接管房间」，12秒开始托管。
  - dm-master和网关日志里没有票据。
- 没有实机验证：RDI master宕机时的表现（用户的进程，这轮没有停）；JWT过期的提示；房主时钟不准；三人同时竞争接管（davickk的本地密码不是123123，没有A）；5个房间上限（只有dm-master路由测试）。接管的当选者需要`tunnel`票据才能注册，所以RDI master宕机时只能重试到恢复，这一点和方案里的说法不同。
- 现场：`server/master/run/config.toml`加了开发用`[dmTicket]`；本机dm-master和网关由环境变量`DM_TICKET_PUBLIC_KEYS`、`DM_GATEWAY_TOKEN`启动，以后重启都要带上。`run/mods`、`run2/mods`、`run3/mods`里的测试Mod已移到`DEL/20261102-dm-auth-test-mods/`，所以房间“基地测试”现在会提示Mod不一致。`run*/options.txt`主音量设为0。

## 4. 待办（2026-11-01更新）

已结束：
- 流式压缩：用户决定保持默认开启（2026-10-27）。LDM 不建议再试：之前对1.20.1真实房间的离线回放显示，开 LDM 反而更差。
- 网络优化移植到 DM：阶段1–5全部完成，见第3节。
- `DmHostCreationScreen` 文字发糊：已修，见第3节。
- 独立服务器回归冒烟测试（1.21.1 + 1.20.1）：通过，见第3节。

鉴权之后的下一步（2026-11-02，先做这些）：
- **等用户决定：要不要加删除房间。** 现在房间没法删除，5个房间的上限是终身的。建议只允许创建者删除离线房间并归档数据，还要加“我的房间”列表界面。
- **正式公钥已写入（2026-11-02）：**`DmJoinGate.BUILT_IN_KEYS`是`mTa7w4O1Al1wF2Cc8ZrYja2MkVdSTiyJA2LPEhJLWWg=`，私钥由用户保管。还没有配到线上：线上RDI master的`[dmTicket]`、线上dm-master的`DM_TICKET_PUBLIC_KEYS`和`DM_GATEWAY_TOKEN`都要用户部署时配置。本机开发环境仍用开发密钥，不受影响。第一把密钥的内容曾进入一次Claude对话，用户已重新生成，旧公钥`UHjKZA9U…`作废，不要再配置。
- **重新生成密钥的方法：**私钥写进RDI master的`[dmTicket]`，公钥写进`DmJoinGate.BUILT_IN_KEYS`和`DM_TICKET_PUBLIC_KEYS`；再给dm-master和网关配相同的`DM_GATEWAY_TOKEN`。命令：`openssl genpkey -algorithm ed25519 -outform DER -out dm-ticket.der`；`base64 -w0 dm-ticket.der`得到私钥；`openssl pkey -inform DER -in dm-ticket.der -pubout -outform DER | tail -c 32 | base64`得到公钥。
- 公网还缺：限流、流式下载、TLS。没有实机验证的项目见第3节“鉴权”。
- **本机环境恢复方法：**这次的公钥、网关凭证和开发用JWT都放在会话临时目录，会话结束后会消失，`build/dm-auth-b.ps1`、`build/dm-auth-c.ps1`里的路径也会失效。私钥还在`server/master/run/config.toml`：把它base64解码成DER后用上面的第3条命令重新算出公钥。网关凭证可以重新生成，dm-master和网关用同一个值。JWT用`POST /player/jwt`（`usr=dev2`或`dev3`，`pwd=123123`）重新获取。
- **RDI master：**用户已重启到新代码。dm-master和网关是从WSL用`Start-Process cargo run`启动的；用户终止旧进程后，这次启动的是新代码。

还没做：
1. **差量上传做不做**：已测完，能省约92%，大基地时有意义。等用户决定，做的话要先出方案（要点见第3节"差量上传测量"）。
2. **区块缓存**：同一次连接里传送回到去过的地方，复用率逐分钟下降（独立服务器也一样）。要做"移动/传送时也压住区块"，范围较大，要先出方案。
3. ~~dev2 账号~~：已补，见第3节，B 当房主时有权限，"保存并退出"也不会卡住。
4. ~~三客户端测试~~：选举已测过。换房主后访客的区块缓存**基本命中不了**（601/609不一致）。原因已查明：重新生成的区块和原来的不完全一样，见第3节"问题1"。建议接受现状；"按 section 复用"可以作为区块缓存的改进以后单独评估，等用户决定。
5. ~~访客和房主身份不一致~~：已修，集成服务器也已改为离线模式，见第3节"问题2"。
6. 1.20.1 没有 DM，这次的改动只涉及 1.21.1。
7. **上线前**：
   - 代码都没有提交，提交前建议跑一次 `/code-review`。
   - dm-master 和 dm-gateway 要重新部署，客户端 mod 要发新版。
   - 旧房间升级后的第一个存档点会把 master 上的全量世界清理到只剩保留区块，这一步不可逆，部署前要提醒玩家。

## 5. 环境和操作经验

- dm-master 和 dm-gateway 由用户在 IDE 里用 `cargo run` 启动。从2026-10-27起，日志同时写到 `server/dm-gateway/logs/dm-gateway.<UTC日期>.log` 和 `server/dm-master/logs/dm-master.<UTC日期>.log`，可以直接读取（网关的 `guest relay traffic` 也在里面）。**我停掉用户的网关进程时被权限规则拦下**，RustRover MCP 里只有 `Run dm-gateway` 配置，重新运行会超时（大概在等确认框），需要重启时请用户操作。
- 2026-10-29起：用户关掉了 RustRover，dm-master 和 dm-gateway 改用隐藏的 `Start-Process cargo run`（从 WSL 调 pwsh 时命令不会返回，用 `timeout 30` 包一下，进程照常起来）。控制台输出在各自目录的 `dm-*-run.log`、`dm-*-run.err.log`，按天的日志照常写在 `logs/`。
- 后台启动（2026-11-01）：`build/dm-*.ps1` 带 `-Drdi.dev.window=background`，窗口放在所有显示器右边、不抢焦点，照常渲染，截图和RMCP点击都正常。要看窗口时用 Win+Shift+方向键把它移回来，或者去掉这个参数。没有最小化模式：最小化启动时 vanilla `Window` 会拿到1×1帧缓冲，加载界面也会卡住，恢复窗口才继续。
- 2026-11-03起自定义早期窗口已移除：`runClient*`检测到 `JDK_JAVA_OPTIONS` 带这个参数时，会把该运行目录的 `earlyWindowControl` 改成 `false`，由 `mDevWindow` mixin 把游戏窗口放到屏幕外；不带时改回 `true`，用FML默认加载窗口。`run*/options.txt` 的 `pauseOnLostFocus` 仍要保持 `false`。
- 启动开发客户端：写一个 `.ps1` 文件（`build/dm-base-runClient.ps1` 跑 `runClient`，`build/dm-b-runClient2.ps1` 跑 `runClient2`），再用 `pwsh Start-Process -WindowStyle Hidden -File` 启动，避免嵌套 pwsh 的引号问题。关闭流式的那一版是 `build/dm-a-nostream.ps1`，用 `$env:JDK_JAVA_OPTIONS='-Drdi.zstream.enabled=false'`，Gradle 会把它传给游戏进程。
- 结束 A/B 进程：A 的命令行里含 `\clientRunVmArgs.txt`，B 的含 client2 的参数文件。
- RMCP 调用的帮助脚本：旧会话的 scratchpad 已经被清掉，2026-10-28 重写在 `/tmp/claude-1000/-mnt-c-Users-calebxzhou-Documents-coding-rdi5/6a4b4052-008a-4726-8aa7-80088394dff8/scratchpad/mcp.sh`：`mcp PORT TOOL JSON`、`cmd PORT "/命令" [waitMs]`、`shot PORT 输出.png`。WSL 是 mirrored 网络，直接用 curl 访问 Windows 的 127.0.0.1。A 的端口是10342，B 是14438，以日志里的 `AI MCP URL` 为准。scratchpad 会随会话清掉，需要时重写。
- 世界列表的 `screen_read` 只显示存档里的世界名（复制出来的都叫"新的世界"），要截图才能看到文件夹名。在世界里用 `/dm create <名字>` 建房，`/dm chunk add` 把脚下的区块加入同步区块。
- 复制世界做测试时，**一定要把副本里的 `rdi/host.json` 移走**，否则打开时会自动去托管原来的房间。
- 开发环境的账号问题：dev2 的 UUID 曾被后端解析成名字 `RDI68b31`。2026-10-28晚已在本地库补上 dev2 和 dev3 的账号，问题已解决。
- 第三个客户端：`runClient3`（`run3/`，dev3），启动脚本 `build/dm-c-runClient3.ps1`，游戏内 MCP 端口9063（以日志为准）。三个客户端同时开时要用 `-Xmx2G`（已写进三个 dm-*.ps1）。
- 结束开发客户端或服务器：直接 `Stop-Process` 结束 java 进程会被权限规则拦下。改用游戏内操作：服务器用 MCP `command /stop`；客户端用 `screen_click` 点「退出游戏」。访客要先主动「断开连接」，再关房主，否则会触发接管。
- 客户端日志在午夜会滚动，前一天的内容在 `run/logs/2026-10-26-1.log.gz`。

## 6. 测试遗留（还没清理）
- 存档 `client/mc/1.21.1-neoforge/run/saves/DM基线测试`（超平坦，没用上）和 `DM基线测试N`（已关联测试房间）。
- `run/saves/DM基地测试`（2026-10-28，已关联测试房间"基地测试" `01a1805e-32ec-7129-ac00-1e7dd4b4d153`，里面有用命令搭的基地）。
- 从副本里移走的 `host.json` 原来放在 scratchpad，已经随会话清掉，不需要恢复（指向的是原世界的房间）。
- dm-master 上的测试房间 `01a178e7-57c6-72b1-b73f-affa90a891d4`、`01a17d20-58fd-745f-b5ef-76b3148bd692`、`01a1805e-32ec-7129-ac00-1e7dd4b4d153`。
- 启动脚本 `client/mc/1.21.1-neoforge/build/dm-*.ps1`，以及构建日志 `build/*.log`。
- 2026-10-28晚新增：`server/mc/{1.21.1-neoforge,1.20.1-forge}/build/smoke-runServer.ps1`、`client/mc/{1.21.1-neoforge,1.20.1-forge}/build/smoke-runClient.ps1`，以及它们的 `smoke-*.log`；`client/mc/1.21.1-neoforge/run3/`；B 接管后的世界 `run2/rdi/dm/01a1805e…/world`（"基地测试"现在由 B 的存档点为准，A 的 `DM基地测试` 已过期）。
- 本地 MongoDB 的 dev2、dev3 账号是有意保留的，不是遗留物。
- 2026-10-28写本文时，开发客户端 A 还开着，在托管"基地测试"（每60秒往本地 master 传一个存档点），B 没开。
