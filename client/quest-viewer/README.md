# 任务书预览器

独立的Compose Desktop子模块，通过启动器`client/ui`构建。只读访问整合包目录，
复用`common/quests`和`/rdi preview export`生成的物品素材，无需启动Minecraft。

```kotlin
QuestViewer(modpackDir = Path.of("C:/packs/example"), modifier = Modifier.fillMaxSize()) { expanded, onClick ->
    CircleIconButton(
        icon = if (expanded) "\uDB85\uDF8D" else "\uDB80\uDEFD",
        label = if (expanded) "收起详细字段" else "查看详细字段",
        showText = false,
        onClick = onClick,
    )
}
```

启动器入口：整合包瓷砖菜单→任务书。返回导航和详细字段按钮样式由启动器负责。

## 输入

- `config/ftbquests/quests/`：任务书定义，使用现有读取器和中文翻译回退规则。
- `rdi/preview/manifest.json`：现有formatVersion=1、64px物品图标索引。
- 图集和语言文件：按manifest中的相对路径读取，兼容旧版带generation目录的路径。

缺失任务书或必要数据错误会显示失败页面。缺失或损坏的素材不会阻止浏览任务，
会显示读取提示和占位图；图集解码错误带异常写日志。刷新会重新读取任务书并替换素材缓存。
不会执行奖励、图片点击动作、命令或脚本，也不会修改任务书和导出文件。

## 功能与边界

- 章节组、章节浏览；按标题、描述、物品和ID搜索任务。
- 按原始坐标绘制任务、任务链接和依赖线，支持拖动、光标位置缩放、适应窗口。
- 目标、奖励、奖励表和跨章节前置跳转；含糊标签保留多个候选，不伪造唯一依赖线。
- 继承章节尺寸、任务书/章节形状、章节依赖线隐藏和重复任务设置。
- `checkmark`目标默认使用随模块打包的FTB Library原始`accept_gray.png`；未指定图标的任务继承目标图标，无需导出物品素材。
- `xp_levels`奖励默认使用物品图集中的附魔之瓶；`random`奖励默认使用随模块打包的FTB Library原始`dice.png`，包括关联奖励表的随机奖励。显式配置的图标优先。
- 物品图集按页缓存，约64MiB/16页；单张旧版超大图集可以超出缓存字节预算。
- 任务文本支持基础颜色/样式码、翻译键和基本JSON文本组件；未识别的内容保留为文本。
- 宽窗口同时显示任务树和详情；窄窗口通过“返回任务树”在二者间切换。

预览展示整本任务书，不模拟玩家进度、解锁条件或隐藏任务可见性。
物品外观来自默认ItemStack，不能还原所有NBT/components变体、实体或流体图标。
导出器仅在游戏语言为zh_cn时写出语言文件，缺少物品名称时显示物品ID。
circle、square、diamond使用简化矢量形状，其他形状回退为圆形并提示；不复刻游戏主题皮肤。
当前物品导出不包含章节装饰图片，暂不绘制这些图片。recipes.json不参与任务预览。

## 验证

在Windows的`client/ui`目录运行：

```powershell
.\gradlew.bat :quest-viewer:test compileKotlin --no-daemon --console=plain
```

聚焦测试包含实际图集像素裁剪、缓存、缺失/损坏素材降级、路径限制、中文任务读取、
目标引用、跨章节任务链接、标签歧义、默认值继承、坐标命中及光标位置缩放。
测试通过不代表已完成启动器视觉和鼠标交互验收。

可设置`RDI_QUEST_VIEWER_SAMPLES`为以系统路径分隔符连接的整合包根目录列表，
额外验证本地真实任务书及每个章节的任务图；未设置时只跳过此项外部样例测试。
