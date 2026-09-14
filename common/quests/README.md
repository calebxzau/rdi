# Quest data

`:quests`是一个不依赖Minecraft运行环境的任务书读取模块。首版读取
`config/ftbquests/quests`目录，支持Minecraft1.20.1和1.21.1使用的FTB
Quests SNBT方言，包括无逗号换行、注释、数值后缀、NBT数组、旧版`tag`
物品数据和新版`components`物品数据。

```kotlin
val result = FtbQuestBookReader.read(questsDirectory).getOrThrow()
val book = result.book
result.diagnostics.forEach { println("${it.code}: ${it.message}") }
```

模型是不可变的整合包定义快照，不含玩家完成进度，也不会下载图片、解析
Minecraft注册表或执行奖励和图片点击动作。已知的预览字段使用类型化属性，
每个对象的`data`仍保留未建模字段，便于以后支持新的FTB或第三方任务类型。
字段的缺失状态也会保留：例如缺少`size`或`can_repeat`时对应属性为`null`，
由调用方按章节或全局默认值处理；`data`中的原始标量仍是权威来源，类型化
字段只是预览便利值。章节和奖励表文件按`order_index`、文件名稳定排序，默认
章节组用`groupId=null`表示。旧版奖励表条目没有ID时保持`null`，不会仿造游戏
运行时生成ID；较新格式中明确给出的条目ID会参与重复检查。`source`中的版本
字段只是来源元数据，读取行为由文件字段决定。
原始NBT通过`FtbSnbtData`以面向玩家预览的自然JSON进行序列化，复合标签和列表可以
直接导航。Byte、Short、Int和安全范围内的Long输出为JSON数字；超出JavaScript安全
整数范围的Long输出为十进制字符串。有限Float、Double输出为JSON数字，NaN、正负
无穷分别输出为`NaN`、`Infinity`、`-Infinity`字符串。三种NBT数组输出为普通JSON
数组，空数组不保留其NBT数组类型。该格式是单向预览输出，不保证数值类型或数组类型
往返；原本就是字符串的值仍保持字符串。任务标题、描述和语言值保留原始
翻译键；语言文件的字符串、字符串列表及空白行不会自动渲染或合并。图片资源
和外部语言文件仍由后续预览层按需提供。

读取失败（目录、文件、SNBT或必要字段错误）返回失败的`Result`；完整读取后
发现的悬空依赖、奖励表引用及不明确的标签引用保留在模型中，并作为诊断返回。
标签歧义表示预览无法唯一确定目标，不代表游戏一定无法加载这条引用。
读取器不递归展开引用，也不执行循环依赖分析；这使调用方可以展示尽可能完整
的任务书，同时明确指出数据问题。
读取器只读取目录的直接`.snbt`文件，不跟随符号链接；单文件上限32MiB、总量
上限128MiB、文件数及单目录条目数上限10000，以限制意外的大型或异常输入。
重复键、`null`/`end`、不兼容的列表元素、会丢失数值的数组转换及非法转义会
明确失败。数值NBT类型和文本内容会保留，注释与原文件排版不参与序列化。

在仓库中运行聚焦测试：

```text
cd server/master
.\gradlew.bat :quests:test --no-daemon
```

真实整合包测试使用环境变量`RDI_FTB_QUESTS_SAMPLES`，值为以系统路径分隔符
连接的绝对`quests`目录列表；未设置时测试会跳过外部样本。
