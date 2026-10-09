# Forge1.20.1物品预览导出

客户端不会自动检查或生成物品图集。玩家进入世界后，在聊天栏手动执行命令开始导出：

```text
/rdi preview export
```
这是客户端命令，支持命令补全，不需要服务器安装RDI或授予命令权限。
输出位于当前游戏目录的`rdi/preview/`。导出时动作栏显示已处理物品数、总数和百分比，每250ms最多更新一次。物品处理完后显示“正在保存图集”，整份导出发布成功才提示完成。完成或失败的结果也会显示在聊天栏。

已有导出文件会跨重启保留，但客户端不会自动检查、复用或更新它们。修改Mod、资源包或语言后，需要手动执行上述命令刷新。导出不会计算资源指纹，也不会自动判断图片是否过时。手动导出请求会等待资源、玩家和世界就绪以及加载画面结束。

## 图片与语言

- 导出所有已注册物品的默认物品堆叠，跳过空气和空堆叠；不枚举组件/NBT变体。
- 图标固定64×64像素、0留白，按物品ID排序后逐行排列。
- 每个物品先画入可复用的64×64离屏画布，成功后在GPU上复制到图集，避免绘制超出格子污染相邻图标。
- 新图集按物品数量分页，每页最大2048×2048，宽高按64像素对齐；末页只保留需要的列和行。分配失败或设备不支持时依次尝试1024、512、256、128、64的页面上限。
- 图片为透明PNG，坐标原点在左上角；页文件依次命名为`0.png`、`1.png`。
- 使用GUI物品外观，不绘制数量和耐久条；动画只保存捕获时的画面。
- 新导出时，只有当前语言为`zh_cn`才保存完整生效语言表，包含已加载的Mod、资源包覆盖和英文回退。
- 某些自定义物品渲染依赖世界或玩家，因此导出请求会等待进入世界。已有导出中失败或空白的图标不会自动重试，需要再次手动执行`/rdi preview export`重新生成。

## 配方

同一次`/rdi preview export`导出配方。导出开始时选择来源：JEI运行时可用则使用JEI；未安装JEI或运行时尚未就绪时，回退到客户端`RecipeManager`。日志和`recipes.json`顶层的`source`明确记录`jei`或`minecraft`，本次导出过程中不会切换来源。JEI随后才就绪时，可再次手动导出。

### JEI来源

通过当前JEI公开API枚举可见分类和配方，使用空焦点建立真实配方布局，然后读取每个槽位的全部候选。包含机器配方及仅在JEI注册的展示配方，不依赖1.20.1不存在的通用Recipe Codec。默认不包含JEI隐藏的分类、配方和机器关联项。

- `categories`以分类ID为键，保存标题、布局宽高和该分类关联的机器/工作台（`catalysts`）。
- `recipes`以`分类ID|配方ID`为键，同一分类内重复ID只保留一条，不同分类分别保存。没有注册ID时生成UUIDv7导出ID，并标记`generatedId: true`；这种ID不保证跨导出稳定。同一分类内重复出现的同一对象只采集一次。
- 每条配方为`kind: "jei"`，包含`category`、可选`recipeId`和`slots`。槽位保留顺序、可选名称、角色（`input/output/catalyst/render_only`）及全部`ingredients`候选，空槽位保留空数组。输出槽的候选也是备选，不应解释为同时产出。
- 物品为`kind: "item"`，保留`id/count`和可选SNBT字符串`nbt`；流体为`kind: "fluid"`，保留`id/amount/unit: "mB"`和可选`nbt`。
- 底层对象属于Minecraft配方时，另存可取得的序列化器`type`、`group/special`、有序合成`width/height`、烹饪`cookTime/experience`。
- 单条布局失败、无法识别的自定义材料类型等写入`failedRecipes`，不会静默丢弃材料。分类查询或分类元数据失败写入`failedSources`；已获取的该分类配方仍继续尝试。完成提示的失败数包含这两类失败。

示例：

```json
{
  "source": "jei",
  "includesHidden": false,
  "categories": {
    "example:mixing": {
      "title": "混合",
      "width": 120,
      "height": 60,
      "catalysts": [{"kind": "item", "id": "example:mixer", "count": 1}]
    }
  },
  "recipes": {
    "example:mixing|example:mixture": {
      "kind": "jei",
      "category": "example:mixing",
      "recipeId": "example:mixture",
      "generatedId": false,
      "slots": [
        {"role": "input", "ingredients": [{"kind": "fluid", "id": "minecraft:water", "amount": 1000, "unit": "mB"}]},
        {"role": "output", "ingredients": [{"kind": "item", "id": "example:mixture", "count": 2, "nbt": "{variant:1b}"}]}
      ]
    }
  },
  "failedRecipes": {},
  "failedSources": {}
}
```

这些是JEI材料槽的展示数据，不是原始数据包JSON。Mod自定义绘图/提示中的耗能、概率、温度、动态规则及不在布局槽位中的隐藏材料不保证导出；不重现整个JEI界面、焦点关联规则或自定义材料图标。图集仍只包含注册物品的默认外观，NBT变体与流体数据不会自动生成额外图标。

分类列表、每个分类的配方发现和单条布局转换分步骤在客户端线程执行，共用每帧最多32步/2ms软预算。分类查询和单个Mod回调本身仍可能超过预算。收集阶段显示分类收集状态，随后显示配方进度。JEI运行时被卸载或替换时取消该次导出，提示失败并保留上一份完整结果；需再次执行命令。

### 原版回退

`source: "minecraft"`沿用原有`recipes/failedRecipes`结构，配方ID作为键。常规合成、熔炼和切石为`shaped/shapeless/cooking/single_item`，保存`inputs/outputs/group/special`，有序合成保留宽高和空格。物品保存`id/count`和可选SNBT字符串`nbt`。其他配方明确记录为不支持。

配方JSON编码和文件写入在后台完成，先写入`.pending/recipes.json`，与图片、语言、manifest整套发布。全局写入失败不会替换上一份完整导出。

## manifest格式

```json
{
  "formatVersion": 1,
  "iconSize": 64,
  "pages": [
    {
      "file": "texture_pages/0.png",
      "width": 2048,
      "height": 2048
    }
  ],
  "languageFile": "lang/zh_cn.json",
  "recipeFile": "recipes.json",
  "items": {
    "minecraft:grass_block": {
      "page": 0,
      "x": 0,
      "y": 0,
      "translationKey": "block.minecraft.grass_block"
    }
  },
  "failedItems": {}
}
```

`page`是`pages`数组下标。每个图标的宽高取全局`iconSize`。
每页宽高可以不同，读取端须使用对应页的实际尺寸。旧的8192×8192导出文件会保留；执行`/rdi preview export`后才会生成新策略的导出。
`recipeFile`指向`recipes.json`；旧导出缺少此字段仍可读取。
`languageFile`在未导出中文语言表时省略；失败物品仅记录在`failedItems`，没有有效图片坐标。
版本、资源包信息、耗时和统计写入日志，不写入manifest。

读取端须将缩放采样限制在图标的64×64源矩形内，避免混入相邻格子的颜色。
仅裁剪屏幕上的显示区域并不能替代源矩形采样约束；不要直接为整张图集生成混合相邻图标的mipmap。

## 完成与失败

每次导出先将图片、语言和`manifest.json`一起写入游戏目录下的`rdi/.pending/`，图片使用固定路径`texture_pages/0.png`、`texture_pages/1.png`，语言使用`lang/zh_cn.json`，不再创建UUID输出目录。
所有文件就绪并通过验证后，先将完整的`rdi/preview/`移到`rdi/.preview-backup/`，再将`.pending`整体移成`preview`。两次目录重命名各自是原子的，但整个切换存在正式目录短暂缺失的窗口；外部读取应避开发布期间。
第二次移动失败时恢复旧目录；若进程在两次移动之间退出，下次导出器初始化时在后台恢复备份（不触发图片导出），手动导出和显式读取也会检查恢复。首次导出没有旧备份，失败时不会发布不完整结果。
单个物品失败允许完成导出；全局渲染、读回或写入失败时保留上一份完整结果。上一次完整结果保留为备份，到下次发布时替换；废弃的`.pending`在下一次生成文件前清空。旧UUID格式仍可读取，下一次成功导出会整体替换旧目录。UUIDv7只用于内部任务标识，防止过期任务发布。

资源重载会取消当前导出，并在资源恢复后继续处理玩家手动请求的未完成导出。空闲时资源重载不会启动导出。退出世界会取消该次登录的待处理请求和导出任务，释放渲染资源；已交给后台的图片由写入线程负责释放，过期任务不能发布manifest。再次进入世界不会自动检查或生成导出。
后台编码和写入当前页时，渲染线程可以继续绘制下一页；下一页画满后，须等后台写入完成才能开始新的读回，后台最多持有1张图片。
OpenGL操作在渲染线程执行，只读回页面的有效矩形。GPU完成后分帧复制像素，每帧最多4MiB，并在复制时完成上下翻转；PNG编码和文件写入在后台执行。
物品绘制和像素复制共用每帧2ms软预算。每批物品共用一份GL状态快照，每个物品结束后恢复该状态。自定义物品渲染、GPU分配和驱动调用仍可能超过预算，实际帧耗时需要游戏内验证。

## 验证

从Windows PowerShell进入本模块后执行：

```powershell
.\gradlew.bat :test --tests 'calebxzau.rdi.mc.client.preview.*' :jar --no-daemon
```

编译和纯逻辑测试不能代替游戏验证。需要实际检查有序合成空格和材料候选、产物数量和NBT、配方ID和不支持配方的失败记录、单条配方失败记录、配方阶段的资源重载与退出取消，以及透明度、方向、普通/立体/染色/自定义物品、
跨页边界和矩形末页、进入世界和重新进入世界时不会自动导出、手动执行命令后等待资源与世界就绪、手动导出期间的F3+T与重复导出请求、退出后的取消和清理，以及实际帧耗时和GPU/本机内存占用。

## Forge适配

客户端通过`RegisterClientCommandsEvent`注册命令，`RenderTickEvent.END`推进任务，登录/退出事件更新世界状态。
`Minecraft.reloadResourcePacks(boolean)`开始时取消旧任务；`ResourceLoadStateTracker.finishReload()`覆盖首次加载、手动重载和恢复重载的成功通知。注册重载监听器本身不代表资源已就绪。
渲染器使用1.20.1的`BufferBuilder`和`PoseStack`，按原版GUI路径处理投影与法线。GL状态恢复同时更新驱动与Minecraft缓存；绘制提交前只在导出作用域内重新绑定离屏画布。
专用`BufferBuilder`的原生缓冲通过Accessor取得最新地址，重建或关闭时释放；这与1.21的`ByteBufferBuilder.close()`不同。

自动化编译和测试不验证OpenGL/Mixin运行效果。实际整合包中的自定义渲染器、Embeddium/Oculus及光影组合仍须游戏内验收。
