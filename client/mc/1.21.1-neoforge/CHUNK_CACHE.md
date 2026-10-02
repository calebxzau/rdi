# mc21客户端区块缓存

所有多人服务器连接（RDI加入按钮、服务器列表、直接连接和LAN加入）自动保存服务器发来的完整区块和后续方块、方块实体、生物群系更新；单人游戏不启用。所有服务器统一使用固定hostId`shared`，不按服务器地址或房间ID分目录。两端支持区块缓存协议时，客户端提前准备本地地形并上报SHA-1；服务器确认地形相同后省略方块及生物群系数据，仍发送当前高度图、方块实体同步NBT和光照。缓存不持有活跃LevelChunk或方块实体；恢复后的标准区块包仍交给原版处理器。

基础区块位于`rdi/shared/world/dimensions/<命名空间>/<维度路径>/r.x.z.mca`，增量位于并列的`rdi/shared/world/deltas/<命名空间>/<维度路径>/r.x.z.mca`。例如`minecraft:overworld`使用`dimensions/minecraft/overworld/`和`deltas/minecraft/overworld/`。分离根目录避免自定义维度路径和增量目录冲突。不同服务器的相同维度、相同区块坐标会覆盖缓存；缓存地形只有在服务器确认hash一致后才会复用。原来按hostId划分的目录不读取、不迁移，也不删除。

两类区域文件均使用MCA/ID8、Zstd等级7、校验和及共享流式压缩池；超大压缩记录沿用`.mcc`。新基础记录是`RDCB`版本1封套，包含UUIDv7会话标识、基础序号和原`RDCH`版本1载荷；增量记录使用`RDCD`版本1。它们不是原版ChunkSerializer存档。读取新格式不会迁移旧RDCH裸记录。

完整区块包保持服务器已经编码的section字节，并保存高度图和独立方块实体同步NBT。所有支持的更新包，包括bundle内包，在网络线程、原版调度到主线程之前复制可变数据。主线程只按原版包应用顺序绑定维度、游戏时间和序号，再提交有界FIFO；不调用LevelChunkSection.write或方块实体getUpdateTag。

后台写入器按区块合并普通方块的最新状态和最新整区块生物群系字节。涉及方块实体的位置保留有序的方块状态与NBT更新，避免把Mod局部更新误当完整状态，也保留拆除后重新放置的过程。新的完整包建立新基础世代并使先前增量失效；增量记录只用于匹配的会话标识和基础序号。基础和增量分开写入时，即使进程在两次提交之间退出，旧增量也不会应用到新基础。

增量约每250ms批量写入，断开后后台正常排空。重连时，新会话先接收有界输入，等旧写入器关闭后再启动文件所有者。退出最多等待2秒；超时或异常退出可能丢失尚未落盘的缓存更新。写入拒绝或复制失败会记录异常并暂停本次缓存，避免丢掉中间增量后继续提交。

完整包和增量包的网络交接与后台输入都受数量、字节及单包上限约束。完整包与增量包交接队列各最多4096项、256MiB，后台writer输入队列最多4096项、256MiB。后台只保留有界的增量状态；淘汰前落盘，重新访问时加载对应增量，基础字节不常驻。无法安全合并的方块实体历史仍可能增长，达到上限时暂停缓存。缓存并非全进程内存上限，原版网络包、编码和压缩缓冲另计。

独立读取接口`ClientChunkDeltaStore.read(key)`返回基础载荷、最终方块状态、最新生物群系字节和有序方块实体重放数据。地形复用改用`readTerrain(key)`，共用基础及增量解析校验，但不展开、排序或深复制方块实体历史。基础记录中的高度图/NBT仍会解析，返回的基础Snapshot仍持有这些元数据；这一步不减少基础解压或NBT解析成本。`Combined.decodeSections(biomes)`使用调用方当前生物群系注册表解码并应用方块及生物群系变化；不构造LevelChunk。读取命令通过现有`ClientChunkDeltaWriter`排队，同一个文件所有者处理读写。复用地形时只取合并后的section，重新计算section计数；高度图、方块实体和光照均使用服务器本次发送的内容，不重放旧方块实体NBT。

数字调色板和方块状态ID依赖原注册表映射；不保存Mod清单或注册表指纹，时间与序号不证明服务器数据仍新鲜。仅记录所支持的原版服务器更新包，自定义Mod载荷产生的状态不在此格式覆盖范围内。

日志`CHUNK_CACHE`区分基础捕获、增量提交、网络复制和主线程绑定耗时，并报告后台输入、处理、拒绝、丢弃和批量写入统计。

## 地形复用协议

协议版本为`chunk-cache-1`，6个可选PLAY通道分别是`rdi:chunk_cache_context`、`offer`、`reuse`、`retire`、`cancel`和`result`，后三段名称均带相同`chunk_cache_`前缀。服务器只在全部通道可用时发送上下文；客户端还必须具有当前多人连接的有效缓存会话。旧端不发候选时继续接收完整区块。

SHA-1只保存在内存，不修改RDCB/RDCD磁盘格式。两端共用`TerrainHash`：固定RDTH标识、规则版本、最低section坐标、section数量，然后从下到上写入每个section的4096个方块状态ID和64个生物群系ID。轴顺序为Y、Z、X，X变化最快，所有整数采用4字节大端。调色板排列、光照、方块实体、时间和缓存序号不参与摘要。

客户端读取基础与增量后，在独立后台线程解码、计算hash并生成准备恢复的section字节；使用当前注册表，解码失败视为未命中。候选快照在上报后固定保存，不能先淘汰再读取其他版本。每端候选/结果表上限128项，每批上报64项；客户端已准备字节上限64MiB，跨会话正在准备的任务最多2项。单section集合2MiB、复用元数据900KiB，候选有效期10秒。空间不足时停止准备新候选。上报完成前的区块仍正常完整发送，服务器不等待读盘。

客户端以有效视距加2个区块的范围准备尚未加载地形。服务器允许当前跟踪范围附近的候选，但只有正常`PlayerChunkSender`发送队列真正发送该区块时才比较hash，上报和补发均不会触发任意区块生成。Hash基于本次原区块包的section内容计算。命中后仅替换NeoForge bundle中的区块成员，保持辅助光源包、成员顺序、批次计数和区块事件。

`ChunkReuseCodec`保留标准包的坐标、高度图、方块实体及光照序列化字节，移除section载荷。客户端拼入已验证的section字节，再通过标准包codec还原，预先登记基础捕获并调用`handleLevelChunkWithLight`。因此继续使用原版区块加载、NeoForge加载事件和光照处理。还原后的新基础按当前会话序号先入写入队列，后续增量不会继续绑定旧基础。

候选撤销时，客户端保留快照直到服务器`retire`确认。已发出的复用操作必须先等待结果，取消消息不能越过这项操作释放快照。无法恢复时发送关联epoch、编号、坐标及hash的失败结果；服务器仅对已签发操作补发当前已就绪区块。补发期间客户端丢弃该区块早于新完整快照的标准增量，混合生物群系包保留其他区块内容。完成完整发送后才确认结束；退出跟踪范围或无法补发时显式发送卸载与结束消息。断线、重生和维度切换使旧上下文失效。

客户端`CHUNK_REUSE_CLIENT`每30秒记录本维度上下文内累计的确认命中、恢复失败、完整补发完成、准备尝试、缓存缺失/不兼容、准备完成过晚/离开范围、候选发布/过期及固定快照状态。`CHUNK_REUSE_CLIENT_TIME`区分成功准备的后台section解码＋hash＋编码、主线程标准包恢复、基础捕获及原版应用；总量和最大值累计至上下文重置。`prepare_failures`包括读取或后台准备异常；`prepare_success_ms`只统计成功准备，不含失败准备耗时。FIFO写入器的`Stats`另外记录实际读取次数、缺失/失败、读取排队及存储读取耗时，总耗时和最大值均在完成future前采集。

服务器每1200tick按玩家缓存会话汇总正常发送尝试、无候选/不匹配/异常回退、复用发出、及时确认成功、失败/超时和补发排队/完成/放弃事件，并区分section复制＋解码、纯hash、元数据编码及替换流程耗时。每tick替换耗时是单玩家会话口径，排除原版完整包构造、后续网络编码/压缩和真正socket发送，不代表整个服务器tick。发送及补发计数按现有发送钩子接受的事件记录，跨统计窗口的确认可能对应前一个窗口发出的包。原始section及`reuseMetadataBytes`是未压缩载荷计数，不是节省流量。

两端共用的`ChunkTerrainCodec.hash`只计算现有SHA-1，不修改section计数或构造重新编码的section数组。客户端`prepare`仍需修正计数并生成待恢复字节；服务端比较改为调用纯hash。

逐格hash通过`TerrainIdReader.get(Int, Int, Int, Int): Int`调用，JVM签名为`(IIII)I`，避免泛型函数回调逐格装箱。客户端候选的坐标和ID索引使用fastutil的primitive集合。候选坐标保存在`LongArray`，按距离及原Z/X遍历顺序排序；游戏tick或视图变化重启扫描，后台准备完成后沿用游标发布候选并补充准备名额，不重新执行过期取消、修复超时和日志维护。完成处理始终排入主线程任务队列，避免已经失败的future触发内联递归；旧上下文的回调仍由会话检查拒绝。最多2个准备任务、10秒重试及候选过期时间、取消后等待服务器确认释放快照的规则保持现有行为。

本功能只复用方块及生物群系。后续出站处理器或其他Mod若在该接入点之后改写地形，需要专项兼容验证；本次focused测试不能证明全部整合包兼容。

## 验证

### 所有多人连接共用缓存

2026-10-01通过IntelliJ MCP在Windows客户端模块目录执行：

```powershell
./gradlew.bat :compileJava :compileKotlin :test --tests 'calebxzau.rdi.mc.chunkcache.*' --tests 'calebxzau.rdi.mc.client.chunkcache.*' --no-daemon --console=plain
```

客户端Kotlin/Java编译通过；JUnit XML确认55项测试、零失败/错误/跳过。静态检查确认通用ConnectScreen.connect入口自动创建shared缓存会话，两个RDI加入按钮不再登记hostId。现有RenderType可见性及依赖弃用警告仍存在。尚未进行真实游戏的服务器列表、直接连接、LAN加入、跨服务器切换和快速重连验收。

2026-10-01通过IntelliJ MCP在Windows模块目录执行：

```powershell
./gradlew.bat compileJava compileKotlin :test --tests 'calebxzau.rdi.mc.client.chunkcache.*' --no-daemon --console=plain
```

增量缓存改动后执行上述模块内编译及focused测试，JUnit XML确认38个测试，零失败、错误、跳过：原有基础编解码/交接9个、RegionFile5个、旧队列7个、生命周期3个；新增增量包捕获5个、增量存储4个、FIFO批量写入器5个。

新增覆盖原包NBT/字节修改不影响待写数据、批量方块更新的坐标与单区块分组、生物群系多区块字节所有权、基础与增量重开、真实方块/生物群系解码、方块实体拆除重建及局部NBT顺序、新基础世代失效旧增量、有界LRU重载、错误合并不落盘、启动前有界排队、启动前取消、队列满不淘汰中间更新，以及后台批量flush和关闭。

独立审查通过。尚未启动真实Minecraft验证本次新增Mixin、bundle更新包、快速重连、维度切换和实机帧时间。上述测试使用独立原版注册表fixture，不等于游戏加载或FPS验收。

### 地形复用验证

2026-10-01在Windows通过IntelliJ MCP执行客户端模块wrapper：

```powershell
./gradlew.bat :compileJava :test --tests 'calebxzau.rdi.mc.chunkcache.*' --tests 'calebxzau.rdi.mc.client.chunkcache.*' --no-daemon --console=plain
```

JUnit XML确认客户端55项测试、零失败/错误/跳过，包含原缓存38项、共享hash/section/元数据/协议10项、读取命令4项、固定快照2项和恢复基础持久化1项。

服务器模块没有本地wrapper，IDE使用LOCAL Gradle。通过Windows MCP在服务器目录使用已安装的Gradle9.6.1：

```powershell
gradle.bat :compileJava :test --tests 'calebxzau.rdi.mc.chunkcache.*' --tests 'calebxzau.rdi.mc.server.chunkcache.*' -x :createMinecraftArtifacts -x :s-mc-common:jar -x :rmcp-common:jar -x :rmcp-server:jar --no-daemon --console=plain
```

上述任务完成Kotlin/Java源码编译和服务器18项focused测试，JUnit XML零失败/错误/跳过：共享10项、候选生命周期6项、补发结束/放弃2项。普通构建先后被Windows锁住的游戏资源Jar、s-mc-common Jar、rmcp-common Jar阻挡，因此复用本次未修改的既有依赖产物；这不是完整产物重建验收。

独立审查发现的候选接收、128项取消、交叉消息、补发卸载和失败构造残留状态问题已修正。额外使用实际NeoForge21.1.250字节码确认Mixin目标为`ServerGamePacketListenerImpl.send(Packet)`。尚未启动真实游戏验证Mixin加载、两端握手、旧端回退、移动时补发和真实带宽/CPU/帧时间。

## 历史合成编码测量

临时基准位于`build/chunkcache-benchmark`，使用24个section，包含空气、实心和混合方块及3种生物群系。每条路径预热100次，测量150次×5轮，取中位数；分配使用ThreadMXBean。最后一次执行命令：

```powershell
./gradlew.bat -I build/chunkcache-benchmark/chunkcache-benchmark.init.gradle :test --tests 'calebxzau.rdi.mc.client.chunkcache.*' chunkCacheEncodingBenchmark --no-daemon --no-configuration-cache --console=plain
```

| 路径 | 中位耗时/次 | Java分配/次 | 输出字节 |
| --- | ---: | ---: | ---: |
| 原地形方块/生物群系NBT编码＋无压缩NBT序列化 | 716064ns | 718515B | 22812B |
| 新LevelChunkSection直接写入拥有的byte[] | 33472ns | 33616B | 33536B |
| 已编码section字节复制核心 | 2912ns | 33552B | 33536B |

旧路径包含实际旧代码中位于后台的NBT序列化；这不是旧主线程耗时的精确重现。新包复制测量不含高度图/方块实体复制、限额检查和入队。全部测量不含光照、Zstd、磁盘、渲染或实机帧时间，不能据此宣称FPS提升或磁盘节省。

## 性能对比验收

真实游戏对比应分别比较旧/新实现，以及启用/停用地形复用（两者均继续保存缓存）。每轮从相同服务器存档和客户端缓存副本开始，固定Mod、视距、压缩设置、路线和玩家数量，分开记录冷/热文件缓存及JIT条件。测试缓存副本应由游戏关闭后的原始数据建立，不能在测量途中复制活跃RegionFile。

服务端`rdi/packet-traffic_v3.db`按包及自定义载荷通道累计压缩帧内容；测量前后在统计队列排空后取差值，包含完整区块、复用包、上报、确认、取消及补发，并核对丢样计数。该边界包含内层压缩封套，不包含外层长度VarInt、TCP/IP开销，不能标为网卡流量。日志用来观察CPU和及时上报比例；不能直接由原始section字节推算净流量。

合成对比脚本位于`build/chunkcache-performance/`：使用相同24个section比较旧服务端`decode＋prepare`和新`decode＋hash`；比较无历史及256条方块实体NBT历史情况下的`read`与`readTerrain`。交替测量顺序、预热后取多轮中位数，并用ThreadMXBean记录Java分配。它不测网络、真实游戏加载或FPS；读取用真实基础Zstd记录及内存保留的增量历史，不等于冷磁盘读取。

### 本轮编译、测试与合成结果

2026-10-01通过Windows IntelliJ MCP串行执行客户端模块wrapper及服务器模块安装的Gradle，普通任务未排除依赖构建：

```powershell
# client/mc/1.21.1-neoforge
.\gradlew.bat :compileJava :test --tests 'calebxzau.rdi.mc.chunkcache.*' --tests 'calebxzau.rdi.mc.client.chunkcache.*' --no-daemon --console=plain
# server/mc/1.21.1-neoforge（该模块没有wrapper）
gradle.bat :compileJava :test --tests 'calebxzau.rdi.mc.chunkcache.*' --tests 'calebxzau.rdi.mc.server.chunkcache.*' --no-daemon --console=plain
```

两端Kotlin/Java编译成功；JUnit XML确认客户端58项、服务器21项，零失败、错误和跳过。包含哈希不修改section计数、摘要范围、地形投影与完整读取的一致性/重开/新基础世代/数据隔离、混合读取的FIFO/容量/失败/启动关闭/丢弃，以及服务端结果计数与同tick跨日志窗口聚合。独立源码审查通过；没有实机Mixin、握手、重连、帧时间或净流量验收。

合成任务：

```powershell
.\gradlew.bat -I build/chunkcache-performance/chunkcache-performance.init.gradle chunkCachePerformanceBenchmark --no-daemon --no-configuration-cache --console=plain
```

每条路径预热200次，测量100次×7轮，两路径交替先后顺序，取中位数。24个section的编码字节33536；方块实体历史fixture为256条更新，每条含4096字节数组。增量历史保留在内存，基础每次从真实Zstd区域记录读取。每轮原始值保存在`build/chunkcache-performance/results-*.txt`。

| 路径 | 耗时中位数/次 | Java分配/次 |
| --- | ---: | ---: |
| 旧服务端比较：复制＋解码＋prepare | 1339908ns | 149312B |
| 新服务端比较：复制＋解码＋hash | 1192157ns | 110800B |
| 无历史完整读取 | 116155ns | 254576B |
| 无历史地形读取 | 116940ns | 254456B |
| 256条NBT历史完整读取 | 295430ns | 1365267B |
| 256条NBT历史地形读取 | 155707ns | 252296B |

此fixture的服务端比较耗时减少约11%、分配减少约26%；带历史读取耗时减少约47%、分配减少约82%。无历史读取耗时没有明确改善。测量不包含新日志统计开销、原版区块包构造、网络传输、服务器tick或渲染；不能据此承诺TPS/FPS或净带宽改善。

### 2026-10-02：primitive hash与候选调度

通过Windows IntelliJ MCP先完成客户端文件构建，再串行执行：

```powershell
# client/mc/1.21.1-neoforge
.\gradlew.bat :compileJava :compileKotlin :test --tests 'calebxzau.rdi.mc.chunkcache.*' --tests 'calebxzau.rdi.mc.client.chunkcache.*' --no-daemon --console=plain
# server/mc/1.21.1-neoforge（该模块没有wrapper，本轮PATH中的Gradle为9.2.1）
gradle.bat :compileJava :compileKotlin :test --tests 'calebxzau.rdi.mc.chunkcache.*' --tests 'calebxzau.rdi.mc.server.chunkcache.*' --no-daemon --console=plain
```

两端Kotlin/Java编译成功；JUnit XML确认客户端64项、服务端23项，零失败、错误或跳过。新增6项回归覆盖负最低section、多section及大于127的ID对应固定摘要，primitive接口签名，候选稳定排序/继续扫描/下一tick重启/移动后替换，以及128个同hash坐标（含0和负坐标）的固定快照与取消释放顺序。

`javap`确认hash循环调用`TerrainIdReader.get:(IIII)I`且无`Integer.valueOf`；候选扫描直接调用`LongOpenHashSet.contains:(J)Z`、`Long2ObjectOpenHashMap.containsKey:(J)Z`和`Long2LongOpenHashMap.get:(J)J`，pin查找也使用primitive参数。日志参数及网络消息的`List<Long>`仍会按需装箱。

独立源码审查未发现正确性或生命周期缺陷；异步调度整体尚无立即失败future、上下文替换和排队完成回调组合的集成回归测试。现有测试及源码审查不代替真实游戏重连、传送、帧时间和新JFR验收。

本轮另用修改前保存的`TerrainHash`和`ChunkTerrainCodec`源码，仅改名为`*Before`，与当前实现做同进程合成对比。脚本及源码副本在`build/chunkcache-primitives/`，临时source set只由init脚本启用：

```powershell
.\gradlew.bat -I build/chunkcache-primitives/chunkcache-primitives.init.gradle chunkCachePrimitivesBenchmark --no-daemon --no-configuration-cache --console=plain
```

同一24个section包含空气、实心、混合方块和3种生物群系，编码33536B。先验证旧/新直接hash及解码后hash都为`729bd3f4037aeb408c40a68ea1d6c54d7015df72`，再预热200次，交替顺序测量100次×7轮，取中位数。`ThreadMXBean`记录线程分配字节。

| 路径 | 旧耗时/次 | 新耗时/次 | 旧/新分配字节/次 |
| --- | ---: | ---: | ---: |
| 纯hash | 1177524ns | 1183642ns | 17104 / 17104 |
| 复制＋解码＋hash | 1259603ns | 1243600ns | 110800 / 110800 |

原始各轮值保存在`build/chunkcache-primitives/results-1790909663425.txt`。这个充分预热的fixture没有显示明确hash加速或分配减少；旧回调装箱可能被JIT消除，但本次没有单独验证这一原因。primitive字节码消除了对泛型装箱优化的依赖，是否消除原JFR中的高分配仍需实机复录确认。合成测量未覆盖候选调度、网络、服务器tick或渲染。
