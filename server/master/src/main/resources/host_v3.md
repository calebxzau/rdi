# 分布式房间设计资料

旧的子区块同步与房主迁移草案已归档至[历史设计](../../../../../DEL/firmsection-design-20260927-01/server/master/src/main/resources/host_v3.md)。当前实现使用整列同步区块和世界快照。

当前行为及接口以以下文档为准：

- [客户端房间与同步命令](../../../../../client/mc/1.21.1-neoforge/DM_GATEWAY.md)
- [dm-master接口和存储](../../../../dm-master/README.md)

区块名单、快照捕获和房主迁移是不同能力；现有快照不能直接视为可安全接管的完整运行状态。


old:

read game source, i have an idea about minecraft network-efficient multiplaying:
1. player1(p1 as abbr) sets "firm sections" for a singleplayer world, then upload the world metadata & firm sections to rdi server, created a virtual "host".
2. afterwards, when p1 changes data of any part of firm section, the changed part will be updated on server
3. new player2(p2 as abbr) wanna join p1's world, he must have the same game registry with p1.(by hash checking instead
   of full compare)
   then he generate a new world with p1's settings, and download firmsections override corresponding parts of the
   new world
   4.after firmsections are downloaded,p2 now opens his world and his integrated server(igs for abbr following)
   will be started
   5.p2's igs's ticking should be paused. p2 will be actually connect to p1's igs.
   6.now p3 p4 p5....p10 joins, do the same. create world with the host's metadata, sync firm sections, open himselves' world, pause ticks, then connect to p1's igs.
7. when p1(the world host) close his game / disconnected from rdi,select a random player which may be p3 as new hoster
   p2..p10 except p3 will connect to p3's igs, and p3's igs  will immediately unpause and start ticking
8. if player3 close or disconnect, do previous step again, choose new host , start his tick, and proxy connect his
   igs.
   gimme advice, and tell me the cons and pros
   Native phrasing: Please review Minecraft’s source code and evaluate my idea for bandwidth-efficient multiplayer using local world replicas, paused integrated servers, and automatic host migration. All world changes must survive a host switch. What are the pros, cons, and improvements you’d recommend?

这个方向可行，但需要把“节省区块传输”和“房主迁移”分别设计。前者有明确的优化空间；后者需要完整的服务器状态复制，不能仅靠同步firm sections、暂停和恢复IGS完成。

结合你补充的“所有变化都要保留”，我建议采用：**1个当前房主、1个备用房主、RDI负责协调和持久化，其他玩家正常联机。**不必让每个人都启动备用IGS。

以下结论主要依据本地Minecraft1.21.1/NeoForge21.1.250源码。

1. **提前生成世界，不会自动减少原版联机流量。**

   服务端的`PlayerChunkSender`仍会发送完整区块与光照包；客户端通过`ClientChunkCache.replaceWithPacketData()`建立远程世界中的区块。这个流程不会查询玩家的单人存档，判断哪些地形已经存在。[服务端发送源码](/mnt/c/Users/calebxzhou/Documents/coding/rdi5/client/mc/1.21.1-neoforge/build/moddev/artifacts/neoforge-21.1.250-sources/net/minecraft/server/network/PlayerChunkSender.java:76)、[客户端接收源码](/mnt/c/Users/calebxzhou/Documents/coding/rdi5/client/mc/1.21.1-neoforge/build/moddev/artifacts/neoforge-21.1.250-sources/net/minecraft/client/multiplayer/ClientChunkCache.java:104)

   要实现你的带宽收益，需要新增协议：客户端声明持有某个区块或子区块的某个版本，服务端核对内容哈希，匹配后只发送差异；缺失、过期或生成结果不一致时，回退到完整数据。

   本地生成可以提供初始地形，但应按需生成。所有玩家先生成大片世界，会增加加入等待时间、CPU负担和磁盘占用。

2. **firm sections可以决定同步优先级，但不能决定保留范围。**

   当前RDI的firm-section数据包只发送维度和坐标；设置标记时，会保存整个所在区块。这还没有实现后续变化的持续复制。[标记数据包](/mnt/c/Users/calebxzhou/Documents/coding/rdi5/server/mc/1.21.1-neoforge/src/main/kotlin/calebxzhou/rdi/mc/server/network/RFirmSectionsPayload.kt:11)、[保存入口](/mnt/c/Users/calebxzhou/Documents/coding/rdi5/server/mc/1.21.1-neoforge/src/main/kotlin/calebxzhou/rdi/mc/server/firmsection/FirmSectionService.kt:87)

   保留所有变化，至少需要覆盖下面这些状态：

   | 状态 | 为什么不能只同步子区块方块 |
         |---|---|
   | 方块、机器和箱子 | 机器内部库存、能量、加工进度可能没有发给客户端 |
   | 生物、掉落物、载具 | 有独立的实体存储，还会跨区块移动 |
   | 玩家 | 背包、末影箱、位置、经验、进度等单独保存 |
   | 世界运行状态 | 方块/流体计划更新、时间、天气、村民兴趣点等 |
   | 模组数据 | 任务、队伍、领地、跨区块网络等可能存于世界级数据或自定义文件 |

   源码中，区块网络包使用方块实体的`getUpdateTag()`，存档使用完整保存数据；两者可以包含不同字段。普通玩家收到的联机数据因此不足以构造接管副本。[区块存档源码](/mnt/c/Users/calebxzhou/Documents/coding/rdi5/client/mc/1.21.1-neoforge/build/moddev/artifacts/neoforge-21.1.250-sources/net/minecraft/world/level/chunk/storage/ChunkSerializer.java:287)、[NeoForge同步说明](https://docs.neoforged.net/docs/1.21.1/blockentities/#syncing)

   另外，16×16×16的子区块不是完整的存档边界。传输可以按子区块压缩，但恢复时必须同时处理关联实体、计划更新、光照和其他数据。

3. **“自己的IGS暂停，同时连接别人的IGS”需要改生命周期。**

   原版`Minecraft.disconnect()`会停止自己的集成服务器。IGS的暂停状态又直接依赖客户端暂停状态，并且暂停时跳过正常服务器tick，其中包含连接维护等工作。它没有现成的“独立备用服务器模式”。[断开连接源码](/mnt/c/Users/calebxzhou/Documents/coding/rdi5/client/mc/1.21.1-neoforge/build/moddev/artifacts/neoforge-21.1.250-sources/net/minecraft/client/Minecraft.java:2138)、[IGS暂停源码](/mnt/c/Users/calebxzhou/Documents/coding/rdi5/client/mc/1.21.1-neoforge/build/moddev/artifacts/neoforge-21.1.250-sources/net/minecraft/client/server/IntegratedServer.java:94)

   即使绕过这些限制，暂停的IGS仍然持有旧的内存对象。下载新存档覆盖磁盘，不会自动更新已加载的区块、机器和玩家；之后保存时，还可能用旧内存覆盖新数据。

   接管时也要处理单人房主的特殊玩家数据。原版对房主有特殊加载路径，不能直接让p3继承p1的单人存档身份。[玩家加载源码](/mnt/c/Users/calebxzhou/Documents/coding/rdi5/client/mc/1.21.1-neoforge/build/moddev/artifacts/neoforge-21.1.250-sources/net/minecraft/server/players/PlayerList.java:334)

   我更倾向先采用备用存档，接管时启动服务器。后续确实需要缩短切换时间，再实现能安全接收状态更新的热备服务器。独立后台服务器进程也值得考虑：生命周期更容易管理，代价是额外内存和启动时间。

4. **哈希检查值得做，但“注册表相同”还不够。**

   相同的注册名称，不代表相同的模组实现、配置或世界生成结果。检查对象应包含Minecraft/加载器版本、相关模组文件、服务端配置、脚本、数据包及其加载顺序、种子和各维度生成设置。

   动态注册表要在世界数据加载完成后检查完整内容；如果直接复用网络中的数字ID，还需要保证ID映射一致。NeoForge明确允许注册表的网络编码省略服务端字段，因此普通握手收到的注册表数据也未必足以重建服务器。[NeoForge注册表说明](https://docs.neoforged.net/docs/1.21.1/concepts/registries/#datapack-registries)

   哈希可以降低比较时的网络开销，但它只证明被纳入哈希的内容一致。本地生成的地形仍应经过实际内容校验，特别是经历过版本升级的老存档。

5. **正常退出与突然断线，需要不同的保证。**

   正常退出可以做完整交接：

   `停止接受新操作 → 完成一致保存 → 上传剩余变化 → 备用端确认 → 转交房主权 → 玩家重连`

   突然掉电则不同。假设p1刚把物品从箱子放进背包，还没把这次变化发出去就掉电，p3无法凭空恢复它。分别备份箱子和背包，还可能组合出物品重复或消失的状态。

   如果“所有变化”也包括突然掉电前最后一刻的变化，就需要让相关状态作为同一批次，在远端可靠保存后才视为提交成功。网络延迟会进入提交路径，远端不可用时也必须限制继续推进。这对任意整合包尤其困难：通用存档快照不等于完整的运行中内存快照。

   切换连接同样需要重新建立游戏会话。原版的服务器转移也是断开后重新连接；代理换一个目标地址，并不会自动迁移实体ID、打开的菜单、模组会话等状态。[服务器转移源码](/mnt/c/Users/calebxzhou/Documents/coding/rdi5/client/mc/1.21.1-neoforge/build/moddev/artifacts/neoforge-21.1.250-sources/net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl.java:280)

你这个方案的主要优缺点，我会这样判断：

| 方面 | 优点 | 代价或限制 |
|---|---|---|
| RDI运行成本 | 世界tick放到玩家电脑，减少云端算力需求 | 房主电脑性能、上行带宽影响所有人 |
| 地形传输 | 校验后的本地缓存/生成结果可减少重复传输 | 必须新增协议，收益需要实测 |
| 房主退出 | 其他玩家有机会继续游戏 | 需要状态交接和重连，不能直接承诺无缝 |
| 数据安全 | 远端副本能降低单机故障损失 | 异步备份存在丢失窗口，严格保留增加延迟 |
| 模组支持 | 可复用模组已有存档机制 | 热迁移还涉及隐藏状态、异步任务和自定义存储 |
| 玩家资源 | 备用机器可以分担接管职责 | 每人都开IGS会重复占用内存，完整备份也有信任成本 |

尤其是带宽：额外复制一份增量给9名备用玩家，分发量大致就是给1名备用玩家的9倍。通过RDI转发可以降低p1的直接上传压力，但流量会转移到RDI。最终是否省流量，取决于省下的区块传输能否覆盖新增的备份流量。

我建议把你的流程调整成下面这样：

1. p1创建房间；RDI保存兼容性清单、完整初始检查点和房主身份。firm sections作为优先预取区域。
2. p2等玩家正常连接。区块缓存命中后跳过重复内容，未命中时下载。
3. 从愿意接管、性能和网络合适的玩家中选择1名备用房主，持续同步所有范围内的状态变化。只有追上已提交版本的玩家才有资格接管。
4. RDI为每届房主分配递增版本号和有期限的权限。旧房主失去权限后必须停止推进和提交，防止网络分区时出现两个房主。
5. 正常退出先完成交接，再重连。异常断线只能从已经可靠复制的状态恢复；若要求没有任何已提交变化丢失，就必须实现相应的同步提交机制。
6. 先验证正常交接的数据正确性，再做故障恢复、区块传输优化和热备提速。

我最看好的是“区块内容复用＋玩家电脑托管＋受控房主交接”。最需要重新设计的是“每个人保留暂停IGS，随机挑一个立即恢复”：副本是否完整、状态是否一致、谁有权继续运行，比恢复tick本身重要得多。

本次是源码分析，未修改代码，也未进行多人运行验证。


Native phrasing: If I accept data loss in non-firm sections, would the mechanism be simpler?

**会，备份和恢复会明显简单一些，传输量也可能大幅降低。**但IGS生命周期、玩家重连和房主选举的难度基本不变。

你的世界可以明确采用这套规则：

| 数据 | 接管后的处理 |
|---|---|
| firm区域 | 恢复保存的方块、机器、实体及关联状态 |
| 非firm区域 | 重新生成，允许之前的变化消失 |
| 玩家和世界级数据 | 仍然保存，包括背包、任务、队伍、时间等 |

这样不用持续复制所有探索区域的变化。不过，还有几个关键边界。

1. **接受非firm区域重置，也意味着接受资源刷新。**

   例如玩家从非firm区域的天然箱子拿走钻石，背包被保留，而箱子在接管后重新生成，就能再次拿取。矿石也一样。

   这是这套玩法自然产生的结果。如果还要防止这些情况，就得保存额外的消耗记录，复杂度又会上升。

2. **跨边界的系统仍然需要规则。**

   一台机器在firm区域内，连接的管道或另一半结构在外，接管后可能断开。实体跨边界移动，也需要按同一个检查点决定保留位置。

   世界级模组数据不能简单按空间裁切；NeoForge的`SavedData`本身就可以保存跨维度数据。[官方说明](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)

3. **如果实际保存整个chunk，会进一步简化。**

   当前设置firm section时，代码已经调用了整个所在区块的保存。[现有实现](/mnt/c/Users/calebxzhou/Documents/coding/rdi5/server/mc/1.21.1-neoforge/src/main/kotlin/calebxzhou/rdi/mc/server/firmsection/FirmSectionService.kt:87)

   如果你接受“一个子区块被标记，就保留所在整列chunk”，可以更贴近原版存档结构。代价是同列未标记的子区块也会保留。

   如果必须严格只保留16×16×16范围，就仍需裁切方块实体、计划更新等数据，并处理光照和边界关系。

4. **firm区域的突然断线零丢失，仍然是独立难题。**

   非firm数据允许丢失，不会让尚未上传的firm数据自动安全。定期检查点仍可能丢掉最后一段进度；要保证已经确认的变化不丢，仍需远端可靠保存后再确认。

我的建议是：第一版做**“持久基地＋可重置野外”**，先验证手动交接。同步同一个检查点下的持久区域、玩家和世界级数据，接管时重新启动服务器，并确保非firm区域不会读到备用机器上的旧副本。

这会比“保留全世界所有变化”容易落地。区块缓存省流量可以随后加入；缩小备份范围本身不会减少原版联机的区块发送。