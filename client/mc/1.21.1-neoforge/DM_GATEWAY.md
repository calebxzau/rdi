# DM房间

Minecraft1.21.1 NeoForge客户端Mod负责创建和托管DM房间。`dm-master`保存永久房间与世界初始化资料，[dm-gateway](../../../server/dm-gateway/README.md)负责反向TCP转发。

## 配置

启动Minecraft时设置master的HTTP地址：

```text
-Drdi.dm.server=http://dm.example.com:8080
```

属性缺失、为空或格式无效时，不注册`/dm`命令，也不显示DM加入入口。客户端通过master的`GET /info`取得gateway的公网主机和隧道端口。

## 创建和托管

进入自己的单人世界后执行：

```text
/dm create 我的房间
```

创建进度界面会使用原版机制暂停尚未对外开放的单人世界。客户端在服务器线程保存世界后，将世界目录中除`.mca`、`.mcc`、`session.lock`、`rdi/host.json`及旧版FirmSection元数据`data/rdi_firm_sections.dat`和`data/rdi_firm_sections.dat_old`以外的文件打包。导出副本的`level.dat`和`level.dat_old`会移除`Data.Player`，原存档不会被修改。已经通过局域网开放的世界不会进入原版暂停状态，因此暂不能用此命令创建房间。

上传成功后，master生成永久UUIDv7房间ID。客户端将ID写入当前世界的`rdi/host.json`，在同一个世界中恢复游玩并建立隧道。以后进入这个世界会自动托管，退出世界会停止托管；无需`/dm host`或`/dm stop`命令。

```text
/dm status
```

用于查看当前房间ID和连接状态。网关或网络暂时中断时会后台重试；房间不存在、正在被另一个房主托管或没有可用游戏端口时停止重试并显示原因。

## 加入房间

配置有效时，多人游戏界面显示“加入DM房间”。输入房间ID后，客户端会：

1. 从master查询房间。
2. 下载或复用世界初始化资料。
3. 将资料保存在`<游戏目录>/rdi/dm/<hostId>/world/`，但不启动本地IGS，也不加入普通单人世界列表。
4. 再次查询当前游戏端口，然后通过gateway连接房主。

房主离线时仍可准备初始化资料，之后使用“加入/重连”再次查询。玩家也可手动重新下载；被替换的旧副本会移动到`<游戏目录>/DEL/rdi/dm/`。

## 同步区块

DM房间内的内置服务端支持`/dm chunk add`、`/dm chunk del`和`/dm chunk list [page]`，按维度和区块坐标管理同步区块。区块列表和`revision`保存在`dm-master`的`data/hosts/<hostId>/sync-chunks.json`中，房主加载成功后才能修改；修改得到master确认后才更新当前列表并通知玩家。访客接收房主发送的区块坐标显示资料，无需直接访问master。

每次修改携带当前房主会话及`expectedRevision`。版本冲突后重新加载列表，不用新版本自动重放旧操作；请求超时后先核对服务器状态，结果未明确时暂停后续修改。网络断开时保留已确认的同步区块显示，恢复后重新加载。退出世界不会上传本地旧FirmSection标记文件，也不会保留离线操作队列。

## 当前范围

当前实现保存世界初始化资料、同步区块列表，并在原版增量保存后每分钟上传一次完整世界checkpoint。checkpoint由`mca`/`mcc`之外的文件和原版`RegionFile`写入组成，master保存可直接打开的世界；加入房间时优先下载已完成的checkpoint，没有checkpoint时回退到world-init。

房主异常退出时，世界可能回到约1分钟前，物品和方块实体存在小时间窗的重复或丢失。原版保存不会等待Mod自行执行的后台文件写入完成。备用IGS、自动接管、房主迁移、Mod兼容性校验和账户认证仍不在当前能力范围内。会话检查用于拒绝失效房主请求，不代替账户认证。

隧道协议只解析DMG1头和控制消息；Minecraft握手、压缩及加密后的数据均原样转发。真实双机联机和游戏内创建/重新打开流程仍需单独验证。
