# 不退图换节点实现计划

状态：第1阶段已完成并达到通过标准（2026-11-02）。第2阶段代码、打包、CI和真实启动器联调已完成（2026-11-03），第3阶段上线待确认。验证结果和尚未验证的项目见`docs/session-resume-validation.md`。

## 1. 目标与边界

玩家手动换节点，或当前节点突然断开时，不退出地图、不重启游戏，连接自动接回。

- 恢复层位于Minecraft协议之下，只传原始字节，不修改Mod，1.7.10到1.21.1都能用。
- 本地代理改用Rust原生库（DLL/`.so`/`.dylib`），由启动器进程加载，随启动器一起退出。

不在范围内：

- DM房间：它直连自己的地址（`DmJoinScreen.kt:121`），不走这条链路。
- 不带RDI核心启动的本地整合包：它不经过本地代理。
- 房间服务器或锚点重启后的会话恢复。
- 启动器崩溃后保持连接。
- 按延迟自动选择最快节点：第一版只在线路断开或玩家手动切换时才换。
- 游戏内切换提示：这需要改Mod，留到以后再做。

## 2. 架构

```text
MC客户端 ⇄ 启动器内本地代理 ⇄ 节点A或节点B（原样转发）⇄ 锚点proxy-rs ⇄ MC服务端
           └───────── 这段可断开并恢复 ─────────┘         └── 这段不断 ──┘
```

| 组件 | 职责 |
|---|---|
| 本地代理 | 每条游戏连接对应一个会话；保存未确认的上行数据；检测线路并切换节点 |
| 节点（云上frp服务端） | 原样转发TCP，不理解恢复协议，不用改动 |
| 锚点proxy-rs | 主机房现有的proxy-rs，与房间同机房；保存会话和未确认的下行数据；连接MC服务端 |

所有节点都经frp到达同一个proxy-rs，所以不增加新的网络跳数，节点本身不用改代码或配置。

### 2.1 端口与新旧共存

恢复连接与现有游戏连接共用proxy-rs现有端口，不用改frp映射、防火墙或`gameAddr`。

proxy-rs用`peek`查看连接开头，不消耗字节：

- 开头是魔数`RDSR`：由本机作为锚点处理。
- 其他情况：完全走现有逻辑，旧版启动器不受影响。

魔数不会和正常握手冲突。`R`（0x52）会被解析成长度82，长度小于128时第二个字节就是包ID，正常握手应为0x00，而`D`是0x44。旧式列表查询以0xFE开头，也不冲突。

如果连接开头的字节分多次到达，以5ms间隔重试`peek`，直到能判断为止，上限沿用现有10秒握手时限。

## 3. 恢复协议（版本1）

整数均为大端序。

### 3.1 建立和恢复

每条TCP连接开始时交换一次，新会话和恢复共用同一格式。

```text
客户端 → 锚点（34字节）
magic[4]="RDSR" | version:u8 | flags:u8（bit0=恢复，bit1=探测）| token[16] | generation:u32 | recv_offset:u64

锚点 → 客户端（26字节）
magic[4]="RDSR" | version:u8 | status:u8 | recv_offset:u64 | send_from:u64 | grace_ms:u32
```

`status`：0成功，1会话不存在，2世代已过期，3版本不支持，4令牌已占用，5会话已满，6偏移量非法。

连接分两步：先发探测（bit1）确认线路端到端可用，探测不改变任何会话状态；只有最先成功的连接才在同一条TCP上发正式请求。否则并行尝试的多个节点会互相抢走会话。

- `token`：客户端用系统安全随机数生成的128位值。它是密钥，不是模型ID，所以不适用UUIDv7规则，也不写入日志；日志用单独的短会话编号。
- `generation`：本会话每次接入递增。锚点拒绝不大于当前值的请求，防止迟到的旧连接抢走会话。接入成功后关闭旧连接。
- `recv_offset`：本端已连续收到的字节数。客户端从锚点报告的位置开始补发。如果报告的位置超过实际发送过的位置，就关闭会话。
- `send_from`：锚点实际开始补发的位置。旧线路上的ACK可能抢先到达，所以它可能大于客户端报告的`recv_offset`。接收方丢弃已收到过的部分。
- 流量控制：接收方把数据交给应用后才发ACK，所以接收方缓冲不会超过对方的发送窗口，读取线路也不会因为应用变慢而停下，探测不受影响。

### 3.2 帧格式

| 类型 | 内容 | 用途 |
|---|---|---|
| `0x01 DATA` | `len:u16`，`payload`（1至16384字节） | 按序传数据，偏移量隐含 |
| `0x02 ACK` | `offset:u64` | 累计确认 |
| `0x03 PING` | `nonce:u32` | 客户端发起探测 |
| `0x04 PONG` | `nonce:u32` | 锚点回应 |
| `0x05 CLOSE` | `final_offset:u64`，`reason:u8` | 数据流结束 |
| `0x06 CLOSE_ACK` | 无 | 确认结束 |

遇到未知类型或长度非法时关闭当前连接，会话可以由新连接恢复。如果连接在`CLOSE_ACK`前断开，恢复后要重发`CLOSE`。这样“房间未启动”这类断开提示不会因线路切换而丢失。

### 3.3 初始参数

以下是验证用的起始值，会根据第4节的结果调整。

| 参数 | 值 | 依据 |
|---|---|---|
| 探测间隔 | 1秒 | |
| 判定线路失效 | 3秒 | 见3.4 |
| 单节点连接加握手时限 | 2秒 | |
| 全部节点失败后的重试间隔 | 1秒 | |
| 目标恢复时间 | ≤8秒 | 为15秒心跳上限留余量 |
| 锚点保留会话 | 20秒 | 从最后收到数据算起 |
| 本地代理等待恢复 | 25秒 | 须小于MC客户端30秒读取超时 |
| 下行窗口 | 1MiB | 写满后暂停读取MC服务端 |
| 上行窗口 | 128KiB | 写满后暂停读取MC客户端 |
| ACK时机 | 每64KiB或100ms | |

时间上限的依据（均已在源码确认）：

- 服务端每15秒发一次心跳，下次检查时上一次仍未回应就踢人，所以最坏情况约15秒（1.21.1 `ServerCommonPacketListenerImpl.java:137`，1.20.1 `ServerGamePacketListenerImpl.java:309`）。
- 客户端30秒读不到数据就超时（1.21.1 `Connection.java:525`，1.20.1 `Connection.java:380`）。

### 3.4 切换规则

线路满足任一条件就判定失效：

1. TCP收到EOF或错误：立刻判定。
2. 3秒内没有收到任何字节。
3. 有未确认数据，且确认位置连续3秒没有前进。

判定后的处理：

- **自动切换：** 同时尝试其余候选节点，第一个握手成功的接管会话。候选节点列表在启动时缓存，所以master不可用时也能切换。
- **手动切换：** 先连上新节点，再关闭旧连接。
- **防抖动：** 如果线路还能收到数据，只是确认变慢，30秒内最多切换一次。慢线路上TCP排队可能导致误判，而误判只造成一次多余切换，不会丢数据。
- **无法恢复：** 超过25秒就关闭游戏连接，MC显示普通断线，玩家可以重新进入。

frp相关注意点：frp服务端可能在隧道还不可用时就接受玩家的TCP连接，所以TCP连上不算成功，必须收到锚点的握手回应。同理，线路是否存活也只看端到端的探测，不看到节点的TCP状态。frp建立工作连接可能增加握手耗时，2秒时限要在真实节点上核对。

## 4. 第1阶段：协议验证（已完成）

本阶段不改启动器、master和Mod。

### 4.1 新增和修改

| 路径 | 内容 |
|---|---|
| `common/session-resume-rs/` | 新crate：编解码、发送缓冲（不依赖IO的状态机）、Tokio会话引擎、锚点会话表、客户端节点切换 |
| `common/session-resume-rs/examples/fault_relay.rs` | 故障中继，支持`kill`、`freeze`、`thaw`、`throttle` |
| `client/local-proxy-rs/` | 新crate：crate类型为`cdylib`和`rlib`，附带命令行程序`rdi-local-proxy-cli` |
| `server/proxy-rs/src/lib.rs` | 见下 |

对`server/proxy-rs/src/lib.rs`的改动：

1. 在`handle_connection`开头加入`peek`分流。
2. 把握手、状态查询和路由逻辑拆成`serve_minecraft<S: AsyncRead + AsyncWrite + Unpin>`。`send_disconnect`和`relay`也改成泛型。
3. 锚点把会话引擎的`tokio::io::duplex`一端交给`serve_minecraft`，路由、状态查询和断开提示都继续复用现有代码。
4. 锚点会话单独计数，上限沿用`max_connections`，避免一个会话和它的线路连接重复占用名额。

依赖保持精简：共享crate只用`tokio`和`getrandom`，本地代理不引入HTTP或TLS。

### 4.2 测试

**单元测试**

- 编解码往返、非法长度、未知帧。
- 魔数判断：分段到达、正常握手、0xFE列表查询。
- 发送缓冲：窗口上限、ACK释放、补发范围、非法偏移。
- 世代检查：过期连接被拒绝，较新连接能接管。

**集成测试（Tokio虚拟时间）**

- 故障类型：立即断开、完全静默、单向静默、限速。
- 随机测试：双向传输64MiB，在随机位置注入多次切换，逐字节比对，失败时输出随机种子。
- 边界行为：
    - 缓冲写满时暂停读取，缓冲释放后恢复。
    - 正常结束时`CLOSE`能送达，断开提示不丢失。
    - 锚点重启后返回“会话不存在”，MC连接随之关闭。
    - 超过保留时间后，会话和后端连接都被回收。

**proxy-rs回归**

- 现有全部测试继续通过。
- 扩展`scripts/smoke_load.py`，增加`--resume`模式：20个会话经2个中继运行，负载期间反复断开中继，并记录RSS。

**真实游戏联调（本机）**

```text
runServer ← 锚点proxy-rs ← fault_relay A、fault_relay B ← rdi-local-proxy-cli ← runClient
```

用`server/proxy-rs/scripts/resume_game_e2e.py`驱动。路由由脚本内的本地桩服务提供，不依赖`--debug`。真实frp留到拿到线上配置后再测。

- 1.20.1 Forge和1.21.1 NeoForge各测试，两种故障各至少20次：节点进程被杀、节点完全静默。
- 负载场景：站立、传送跑图（持续加载区块）、大数据包（`/recipe give`和`/recipe take`反复触发，代替原计划的打开大型界面，因为它不依赖界面操作且数据量更大）。
- 手动切换：运行中用命令行切换节点。

### 4.3 通过标准

| 项目 | 标准 |
|---|---|
| 被踢次数 | 0（累计至少80次故障，含1.20.1跑图） |
| 节点静默的恢复时间 | P95≤8秒，最大值<15秒 |
| 节点进程退出的恢复时间 | P95≤3秒 |
| 数据一致性 | 随机测试全部逐字节一致 |
| 手动切换 | 无明显卡顿；游戏内无断线 |
| 正常退出 | 玩家立刻离开房间，不等待保留时间 |
| 客户端被强杀 | 会话在保留时间内回收 |
| 锚点内存 | 20个会话时，RSS峰值低于基线（6MB）加30MiB，并低于100MB目标 |
| 旧连接路径 | 现有测试和冒烟测试无回归 |

如果切换经常超过15秒，或者1.20.1大量加载区块时无法稳定通过，就停止，退回方案A（Mod内切换加直连）。

本阶段不验证真实公网。公网测试放在第2阶段末尾，因为要等启动器接入后才能用真实账号和房间进入。

### 4.4 macOS构建探查

本阶段顺带验证构建能力，结果记入进度文档。当前这台Windows机器没有安装`aarch64-apple-darwin`目标，也没有Zig。

1. 先试`cargo-zigbuild`在Windows上构建`aarch64-apple-darwin`的`cdylib`。
2. 不行就在NoMac云主机上用rustup构建，并决定第2阶段的发布流程。
3. 在NoMac上用JNA加载，确认能启动。

Linux不能用musl：它的`cdylib`不适合加载进glibc的JVM。要构建`x86_64-unknown-linux-gnu`，glibc要求不超过现有的2.35。

## 5. 第2阶段：DLL接入启动器（已完成）

已确认的决定（2026-11-03）：

- 不做真实节点测试，也不需要线上frp配置。
- 国内玩家的候选节点不包含「国际出口」。
- 用GitHub Actions代替NoMac验证macOS，见5.7。

开工前先读第1阶段的代码和`docs/session-resume-validation.md`。

### 5.1 共享模型（先改）

`common/model/src/main/kotlin/calebxzhou/rdi/common/model/GameNode.kt`：

```kotlin
@Serializable
data class GameNodeEntry(val name: String, val gameAddr: String)

@Serializable
data class ServerEntry(
    val api: String? = null,
    val useBackupNode: Boolean = false,
    val nodeName: String,
    val gameAddr: String,
    val candidates: List<GameNodeEntry> = emptyList(),
    val sessionResume: Boolean = false,
)
```

新字段都有默认值。JSON已设置`ignoreUnknownKeys`（`common/misc/.../SerDes.kt`），新旧版本互相兼容，改完用测试确认。

### 5.2 master

在`server/master/.../service/GameNodeService.kt`：

- `GameNodeConfig`增加`sessionResume: Boolean`，作为总开关。第2阶段默认关闭，第3阶段改为默认开启（见第6节）。
- 候选逻辑写成纯函数，放进新的`GameNodeCandidatePolicy`，和现有`ProxyAccessPolicy`同样风格，时段和地区都作为参数传入，方便测试。

候选规则：

1. 当前选中的节点永远排第一，包括手动选择的备用节点或国际出口。
2. 然后依次是备用节点（`gameBackup`）、主节点（`id=0`）、其余节点，去重。
3. 非高峰时段不返回未被选中的`peekHourOnly`节点。
4. 国内玩家不返回未被选中的`matchOutsideChina`节点。海外和港澳台玩家（命中`matchOutsideChina`规则）照常返回。
5. `id=0`的主节点始终在列表中。用户确认默认节点直连主机房，不经过frp，所以frp全部故障时仍可恢复。上线前核对生产配置里`id=0`确实是这个直连地址。

节点显示名沿用现有格式（节点名-省份运营商）。

测试：新增`GameNodeCandidatePolicyTest`和Gradle任务`gameNodeTest`，写法照`dmTicketTest`。覆盖上面五条规则，以及只配置一个节点的情况。这不是Modpack逻辑，不需要跑`modpackServiceTest`。

### 5.3 DLL接口

新增`client/local-proxy-rs/src/ffi.rs`。全局单实例，内部持有2个工作线程的Tokio运行时。不引入JSON依赖，接口都是简单字符串：

```c
uint32_t rdi_lp_abi_version(void);
// 已在运行时返回现有端口；nodes以换行分隔，第一个优先
int32_t  rdi_lp_start(const char *bind_host, uint16_t preferred_port, const char *nodes, uint16_t *out_port);
int32_t  rdi_lp_set_nodes(const char *nodes);
int32_t  rdi_lp_switch_node(const char *addr);
// 取走事件写入buf，返回字节数；一行一个事件，字段以制表符分隔
int32_t  rdi_lp_poll_events(char *buf, int32_t len);
int32_t  rdi_lp_last_error(char *buf, int32_t len);
// 可重复调用；停止后可再次start
int32_t  rdi_lp_stop(uint32_t timeout_ms);
```

事件行：

```text
attached <session> <node> <initial|failover|manual> <elapsed_ms>
path_lost <session> <reason>
switch_failed <session> <node> <error>
ended <session> <closed|expired|gone|violation> <detail>
```

要求：

- 所有入口用`catch_unwind`，失败返回负数错误码，详情由`rdi_lp_last_error`取得。
- 空指针、非法UTF-8、缓冲区太小都返回错误，不崩溃。缓冲区放不下的事件留到下次，不截断半行。
- 事件队列有上限（512条），满了丢弃最旧的并记一条提示。
- Rust线程不回调JVM。
- `unsafe`只出现在这一层。

测试：在`ffi.rs`里测启动、重复启动、停止后再启动、空指针、小缓冲区、事件上限。

### 5.4 启动器

**`RServer.kt`**

- `ServerRouteState`增加`candidates`和`sessionResume`，由`updateServerEntry`写入。
- 增加`markActiveNode(addr)`：自动切换后，让设置页的当前节点显示实际使用的节点。

**新增`client/ui/client-core/.../calebxzau/rdi/client/proxy/NativeLocalProxy.kt`**

- JNA绑定，`Native.load("rdi_local_proxy", ...)`，由JNA从类路径解压。检查`rdi_lp_abi_version`，不匹配视为加载失败。
- 事件解析写成纯函数并加测试。
- `client-core/build.gradle.kts`补`implementation(libs.jna)`。

**`LocalMcProxyService.kt`（包名不变）**

- `start`和`stop`的对外接口不变，`McPlayScreen`不用改。
- 把两种实现放到内部接口之后，便于用假实现测试。
- 选择规则：`sessionResume`为真且DLL加载成功时用Rust实现，否则用Kotlin实现。加载失败记录日志并附上异常，不影响玩家进房间。
- 候选节点来自`RServer.routeState.candidates`。列表为空时用`currentGameAddr`，并始终把`DEFAULT_GAME_NODE_ADDR`追加到末尾，覆盖无法获取节点信息时的回退情况。
- 开发模式沿用现有端点；增加仅`DEBUG`生效的`-Drdi.sessionResume=true`和`-Drdi.localProxy.nodes=a,b`，用于本机联调。
- 每500ms读取一次事件：写入现有日志，更新当前节点。日志只显示节点名，不显示地址（现有代码也特意隐藏地址）。
- 新增`applyRoute()`：把新的候选列表和选中的节点交给正在运行的代理。Kotlin实现下不做任何事，保持现有“下次连接生效”的行为。

**设置页（`SettingsViewModel`）**

- 换节点成功后调用`applyRoute()`。
- 有游戏运行且用的是Rust实现时，提示“已切换到X，不用退出地图”；否则保持现有提示。
- 文案只说节点和房间，不提代理或锚点。

**退出确认**：已核对，不用改。`Main.kt:215`在有游戏运行时弹确认，按钮是“全部停止并退出”，会同时停止游戏。

### 5.5 打包

新增模块`client/ui/local-proxy-natives/`，并在`client/ui/settings.gradle.kts`注册，写法照`client/ui/sqlite-natives`：

- `client/gradle/target-platform.gradle.kts`增加`cargoTarget`（`x86_64-pc-windows-msvc`、`x86_64-unknown-linux-gnu.2.35`、`aarch64-apple-darwin`）、`jnaResourcePrefix`（`win32-x86-64`、`linux-x86-64`、`darwin-aarch64`）和库文件名。
- 任务`buildLocalProxy`：`windows-x64`用`cargo build`，其他平台用`cargo zigbuild`。输入是两个Rust crate的源码和`Cargo.lock`，以便增量跳过。输出放在模块的`build/`下。工具缺失时报清楚的错误。
- jar名为`rdi-local-proxy-natives-<platform>.jar`，库放在JNA资源路径下。
- `client-core`增加`runtimeOnly(project(":local-proxy-natives"))`。
- 在`client/ui/build.gradle.kts:346`的`uiPlatformNativeMarkers`里登记三个平台的jar名，复用现有的平台校验。
- 发布机需要`rustup target add aarch64-apple-darwin`、Zig 0.14.1和`cargo-zigbuild`。第1阶段探查已确认这套工具能在Windows上构建。
- 确认dylib是否需要去掉符号：现在2.4MB，其他平台只有100至360KB。（第2阶段结果见验证记录，导出接口后三个平台都变大了。）
- 用`checkUiPlatformNatives`校验三个平台的natives。它不写发布目录；`packageUiRelease`会先删除再重建当前版本的发布目录和`latest.txt`，只在真正发版时运行。

Gradle按项目规则运行：先试IntelliJ MCP，不可用再用`pwsh.exe`和模块内的`gradlew.bat`。参考项目记忆里的client-core测试基线。

### 5.6 验证标准

不做真实节点测试。

| 项目 | 标准 |
|---|---|
| Rust测试 | 三个crate和`ffi.rs`全部通过 |
| master | `gameNodeTest`通过 |
| 启动器 | client-core和`SettingsViewModelTest`相关测试通过；回退和选择逻辑有测试 |
| 打包 | 三个平台的manifest都带对应natives，`validateUiPlatformNatives`通过 |
| 退出 | 启动器退出和强杀后进程无残留，DLL文件不再被占用，`start.exe`更新正常 |
| 开关关闭 | 走Kotlin实现，行为和现在一样 |
| DLL加载失败 | 回退到Kotlin实现，玩家能进房间 |
| 本机联调 | 真实启动器进入1.21.1和1.20.1开发房间，走DLL路径，节点被杀和静默各10次不掉线，设置页手动切换不退图，当前节点显示正确 |

本机联调的注意事项：

- 复用`server/proxy-rs/scripts/resume_game_e2e.py`。DLL的事件在启动器日志里，脚本要增加不启动命令行代理的模式，靠命令回复和服务端日志判断是否掉线。
- 1.21.1要先把开发服务端临时改到6371，结束后恢复。
- 1.20.1的开发客户端启动时会先直连服务端，要先踢出再走正式入口。

已接受的风险：恢复时间至今只在本机回环上测过，慢线路上3秒判定和2秒握手时限的表现要到第3阶段才能看到。

### 5.7 GitHub Actions macOS CI

工作流`.github/workflows/macos-local-proxy.yml`已推送并通过，结果见验证记录：

- Windows任务交叉编译macOS版dylib并上传。
- macOS任务跑三个crate的测试和64MiB随机故障测试，加载交叉编译的dylib并检查架构和签名，再跑恢复模式负载测试。
- 探测步骤记录显卡信息并尝试截图。失败不影响结果，用来判断启动器界面截图是否可行。

第2阶段补充的内容（均已完成）：

1. JDK 25通过JNA调用DLL的冒烟测试，在三个平台上都跑：加载、读版本、启动、停止（`client/local-proxy-rs/jna-smoke/JnaSmoke.java`）。
2. 第一次运行后确认`macos-15`在公开仓库是否免费、是否有图形会话。

注意：仓库公开，日志和构件别人也能看到，不能使用真实账号，不能输出节点地址或令牌。

## 6. 第3阶段：上线（第2阶段通过后另行确认）

第3阶段的每一步都会动到线上，开工前先确认。

2026-11-03决定：`gameNode.sessionResume`改为默认开启，所以线上`config.toml`不用加这一项，部署新master后立即生效；要关闭时写`[gameNode]`和`sessionResume = false`，放在第一个`[[gameNode.nodes]]`之前，再重启master。下面的“总开关保持关闭”和“先在测试账号验证”不再适用。master、proxy-rs和启动器由用户手动发布，这个版本发布前没有做真实公网和frp测试。

发布顺序不能颠倒：先升级proxy-rs，再部署master，最后发布启动器。启动器开启恢复后，如果连到旧版proxy-rs，握手得不到回应，玩家进不了房间。

### 6.1 开工前要决定和准备

1. **怎样只对测试账号开启（要决定）：** 现在`gameNode.sessionResume`是全局开关，`-Drdi.sessionResume`又只在`DEBUG`下生效，所以下面第3步的“先在测试账号验证”目前做不到。可选：在master加按账号或按房间的开关；或者在低峰时短暂打开全局开关。开关只影响新启动的游戏，所以后者也能很快撤回。
2. **发布机工具：** `zig`和`cargo-zigbuild`现在只在`%TEMP%\rdi-resume-mac-probe`里，不在`PATH`上，临时目录也可能被清理。`packageUiRelease`现在要构建三个平台的库，发版前要正式安装。
3. **启动器版本号：** 当前`app = "6.7.7"`。`packageUiRelease`会删除并重建当前版本的发布目录，所以必须先升版本号。
4. **master部署：** 线上master要带上Ktor 3.6.1和候选节点代码，默认开关关闭。部署后确认真实房间能连上master（`/host/play`），公开路由`/host/route`和`/host/status`不登录也能访问。
5. **核心Mod：** 本功能不需要发布新Mod。第2阶段只用`出core-local`发布到了本机，没有动线上。

### 6.2 上线步骤

0. 核对生产配置：`id=0`节点的地址是否就是直连入口。启动器内置的`DEFAULT_GAME_NODE_ADDR`端口是65230，而`server/master/run/config.toml`里`id=0`的端口是65235，要确认两者是否都可用。
1. 升级主机房的proxy-rs，让它能接受恢复连接，旧连接不受影响。节点不用改动。
2. 发布带Rust代理的启动器，此时总开关保持关闭。
3. 先在测试账号或测试房间验证，这是第一次经过公网和真实frp，重点看误判切换和恢复耗时。确认后再打开总开关。开关只影响新启动的游戏。
4. 观察切换次数、恢复耗时、被踢次数和回退次数。出问题时关闭开关，退回旧链路。

## 7. 第4阶段：收尾（第3阶段稳定后另行确认）

- 大多数玩家升级后，确定最低启动器版本。
- 把Kotlin版本地代理模块归档到`DEL/<唯一目录>/client/local-mc-proxy/`。流量统计不移植。

## 8. 已知影响和风险

| 项目 | 说明和处理 |
|---|---|
| frp公共故障点 | 所有节点都汇到主机房。如果主机房的出口或frp客户端全部故障，换节点也救不了；有不经过frp的直连入口时，应把它加入候选节点 |
| 同时切换 | 一条frp隧道断开时，这条线路上的玩家会同时切到其他节点。20人规模下问题不大，联调时观察 |
| 令牌安全 | 节点和明文线路能看到令牌，但这些位置本来就能看到并篡改明文流量。本方案只防猜测和旧连接抢占 |
| 单一锚点 | 目前所有房间都在`gameHost`上，锚点和房间处于同一故障范围。以后房间分布到多台机器时，每台都需要锚点 |
| 启动器内崩溃 | DLL崩溃会带上启动器，已接受。通过少用`unsafe`、`catch_unwind`和缓冲上限降低概率 |
| 开发环境锁文件 | 启动器运行时，DLL被占用，无法覆盖。开发时要先关闭启动器 |
| 固定端口 | `mMultiScreen`写死`127.0.0.1:55667`，端口被占用时服务器列表项会连错。这是现有问题，保持现状 |

## 9. 已确认事项

- 国内玩家的候选节点默认不包含「国际出口」。
- 默认节点`RServer.now.ip:65230`直连主机房，不经过frp，会加入候选节点。
- 第2阶段不做真实节点测试，所以不需要线上frp配置。
