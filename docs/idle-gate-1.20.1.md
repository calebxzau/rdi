# 挂机降流量（Forge1.20.1）

玩家离开电脑时，服务端暂停发送他看不到、听不到的内容，回来时补发当前状态。思路参考BandwidthOptimizer的后台挂机，只实现其中的“后台”一档，没有使用其代码。

## 判定

客户端`RClientIdle`每tick检查：打开暂停菜单或窗口最小化持续2秒，即通过可选通道`rdi:idle`上报“离开”；恢复后立即上报“回来”。只失去焦点而没有暂停（例如在副屏上看着）不算离开。服务端没有该通道时客户端不上报。

## 服务端处理

`mIdleGate`在`ServerGamePacketListenerImpl.send`入口拦截，只处理服务器主线程上、没有发送回调的包：

| 包 | 处理 |
| --- | --- |
| 声音、实体声音、粒子、LevelEvent、方块破坏进度、挥手动画 | 距离玩家16格以外的丢弃；近处的照常发送（例如钓鱼溅水声） |
| 其他实体的移动、转头、速度、传送 | 只记录实体ID；玩家自己、坐骑、同乘者和鱼钩照常发送 |
| 方块更新、区段方块更新、方块实体数据 | 只记录坐标 |

其余包（区块、实体生成/移除、玩家自身状态、背包、聊天、Mod自定义包等）照常发送。记录总数超过32768后不再拦截新的更新。换维度时清空旧维度的记录。

回来时在主线程按当前世界状态补发：同一区段的方块合并为一个区段更新，方块实体发送其更新包，实体发送传送、转头和速度。只补发玩家仍在跟踪的区块；客户端已不认识的实体会忽略这些包。日志记录丢弃和补发的数量。

设置`-Drdi.idle.enabled=false`时服务端忽略上报。

## 已知限制

- 收益未经测量；挂机期间实体元数据、装备、光照和Mod自定义包仍照常发送。
- 挂机期间断线时，客户端区块缓存里缺少被延后的方块变化，下次进服时hash不一致，会回退为完整发送。
- 没有单元测试：逻辑依赖Minecraft世界对象，1.20.1在普通JUnit中初始化注册表会出问题。
- 由于`mc/syncchunk/.../SyncChunkBackupStore.kt:305`的既有编译错误，Mixin未经编译和打包验证，只经过IntelliJ检查（服务端）；未在真实游戏中验证。

## NeoForge1.21.1（2026-10-12）

判定和服务端处理与1.20.1相同，差异如下：

- 通道改为NeoForge可选载荷`rdi:idle`（`mc/common/codec/src/21/.../IdlePayload21.kt`），客户端只在服务端协商了该通道时上报。
- 客户端`RClientIdle`订阅`ClientTickEvent.Post`；服务端`RServerIdle`自行订阅退出事件。
- 1.21的`send`位于`ServerCommonPacketListenerImpl`，`mIdleGate`注入在此，只处理`ServerGamePacketListenerImpl`、没有发送回调的包。
- 移动包和转头包的实体ID通过`AClientboundMoveEntityPacket`、`AClientboundRotateHeadPacket`读取。

只有设置解析有单元测试，未在真实游戏中验证。
