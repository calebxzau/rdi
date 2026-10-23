# Minecraft公共codec

本目录按源码范围供各Minecraft模块引用，不单独构建。

| 源码目录 | 职责 | 引用方 |
| --- | --- | --- |
| `src/main/kotlin` | `zstdcodec`包：网络压缩及VarInt接口 | 现有客户端和服务端 |
| `src/netty41/kotlin` | Netty4.1适配层，与`src/main/kotlin`配套引用 | 1.20.1 Forge/Fabric、1.21.1 NeoForge客户端和服务端 |
| `src/netty40/kotlin` | Netty4.0.10适配层，与`src/main/kotlin`配套引用 | 1.7.10 Forge客户端和服务端 |
| `src/1710/kotlin` | 无Minecraft依赖的1.7.10登录切换、VarInt、流设置及可选区块测量 | 1.7.10 Forge客户端和服务端 |
| `src/netty40Test/kotlin` | Netty4.0.10引用计数、压缩往返及流式多帧测试 | 1.7.10 Forge客户端测试源集 |
| `src/test/kotlin` | 网络codec测试 | 现有网络codec测试源集 |
| `src/server/java` | `regioncodec.RegionZstdStreams`：区块存档压缩流、压缩池、校验和、失败中止及资源释放 | 1.20.1 Forge/Fabric、1.21.1 NeoForge服务端 |
| `src/serverTest/kotlin` | 不依赖Minecraft的区块codec测试 | 上述3个服务端的测试源集 |

`src/main`只能使用Netty4.0.10与4.1共有的API；版本差异通过`NettyCompat`处理，每个源码集只选择一个适配层，并由1.7.10编译检查兼容性。

1.7.10在原始登录成功包S02之后开启双向Zstd压缩，客户端在转交S02给FML之前准备好压缩与流接收；服务端默认开启Zstd流，可通过`rdi.zstream.enabled=false`关闭流并保留独立压缩帧。`rdi.zstream.windowLog`默认25，限制在20至25。原始单包上限32MiB，压缩载荷上限33MiB；1.20.1和1.21.1继续使用8MiB原始/2MiB压缩默认上限。1.7.10批处理、包引用、持久包及zlib尾部扩展未启用。

客户端的Zstd运行库由RDI启动器提供，GTNH继续使用自己的Forgelin；服务端打包未重定位的zstd-jni及Linux amd64原生库。两端初始化执行原生库检查并记录类加载器。

1.7.10没有版本标记或协商：旧客户端、原版客户端、非RDI服务器及由RDI客户端开放的LAN房间不支持此协议组合，可能以解码错误断开。单人游戏的LocalChannel保持不压缩。

实机测量可在服务端设置`-Drdi.zstd1710.chunkMetrics=true`，逐包记录S21/S26的编码后原始字节、Zstd载荷字节和外层帧字节；默认关闭。它不改变区块编码，记录的帧大小不包含TCP/IP开销。

区块核心只依赖JDK和`zstd-jni`，保留ID8、等级7、64KiB缓冲区及原有池容量。它没有Minecraft注册、NBT、Mixin或loader依赖。

各版本的`RegionZstdCodec`负责`RegionFileVersion`注册，并把Minecraft的`FastBufferedInputStream`作为读取缓冲策略传入公共核心。校验包装保持在缓冲流外层，关闭时排空解压数据以校验帧尾。版本适配类转发输出、失败中止与关闭接口，因此现有Mixin及停服回调仍走同一条路径。

注册接口、默认codec选择Mixin、AT/AW和生命周期事件保留在各版本中。网络与存档codec分别维护压缩状态；客户端引用`src/main`及对应的Netty适配层，不会引入`src/server`的存档压缩池。

## 抽取验证（2026-09-28）

| 服务端 | 公共流测试 | Minecraft适配测试 | 编译及打包 |
| --- | --- | --- | --- |
| 1.20.1 Fabric | 14项通过 | 3项通过 | 常规编译、`remapJar`通过 |
| 1.20.1 Forge | 14项通过 | 3项通过 | 临时Java25配置下编译通过；测试排除`reobfJar` |
| 1.21.1 NeoForge | 14项通过 | 2项通过 | 常规编译、`jar`通过 |

上述JUnit XML均为0失败、0错误、0跳过。公共测试覆盖压缩往返、旧ID8、校验和与截断、缓冲包装失败的资源释放、流式写入、失败中止、并发及池关闭；版本测试覆盖Minecraft NBT及序列化失败处理，1.20另含真实region文件与`.mcc`往返。

Fabric真实Knot/Mixin探针在抽取后重新通过，确认默认`RegionFileStorage`路径的ID8写入、超大区块、失败保留原记录及关闭后重新读取。Fabric与NeoForge产物均含唯一公共核心，旧`RegionZstdCodec`嵌套实现已不在产物中。

Forge已有的JVM17/25依赖冲突及重混淆工具不支持Java25字节码的问题未修改；本次没有重新验证Forge最终发布产物，也未启动Forge/NeoForge完整服务器或执行Linux验收。
