# 1.20.1服务端区块Zstd

Forge与Fabric共用`src/server`中的`RegionZstdCodec`适配类和3个Region Mixin。压缩核心位于`mc/common/codec/src/server`的`RegionZstdStreams`，同时供1.21.1 NeoForge服务端使用。

- 默认保存使用ID8、Zstd等级7和校验和；采用固定64KiB缓冲区及压缩上下文池。
- 兼容读取原版ID1–3和旧ID8。旧记录在再次保存时转换，未触及的记录保持原样。
- 接入`RegionFile`默认构造器，覆盖使用原版存储路径的地形、实体和POI。
- 普通记录使用`0x08`；超大记录沿用原版外部文件机制，使用`0x88`及`.mcc`。
- 保留原版NBT序列化及region提交。序列化或压缩失败时中止输出，避免提交不完整数据。
- 在服务器完成保存并停止后关闭压缩池。
- 存档包含ID8后，需要支持该codec的服务端或工具读取。此变更不改变联机协议。

1.20没有`RegionFileVersion.getSelected()`，因此使用默认构造器中的`VERSION_DEFLATE`读取点选择ID8。显式传入codec的构造器仍使用调用方指定的codec。Forge通过Access Transformer开放注册接口，Fabric通过Access Widener开放同一接口。

## 验证记录（2026-09-28）

公共流测试位于`mc/common/codec/src/serverTest/kotlin/calebxzau/rdi/mc/regioncodec/RegionZstdStreamsTest.kt`；Minecraft适配及region文件测试位于`src/serverTest/kotlin/calebxzau/rdi/mc/v20/server/region/RegionZstdCodecTest.kt`。

| 检查 | 结果 |
| --- | --- |
| Fabric常规编译、`remapJar` | 通过，产物包含3个Mixin及映射后的Access Widener |
| Fabric focused测试 | 公共流14项、Minecraft适配3项，0失败、0错误、0跳过 |
| Fabric真实Knot/Mixin加载器下的region探针 | 通过，覆盖首次注册、默认构造器、普通/超大记录、失败保留原记录及关闭后重新打开 |
| Forge常规编译 | 受已有JVM17调用方与JVM25共享依赖冲突阻塞 |
| Forge临时Java25配置下的编译及refmap生成 | 通过 |
| Forge临时Java25配置下的focused测试 | 排除`reobfJar`后公共流14项、Minecraft适配3项通过，0失败、0错误、0跳过 |
| Forge最终打包 | 未通过：重混淆工具报告`Unsupported class file major version 69` |

Fabric测试命令，在`server/mc/1.20.1-fabric`执行：

```powershell
.\gradlew.bat :test --tests calebxzau.rdi.mc.regioncodec.RegionZstdStreamsTest --tests calebxzau.rdi.mc.v20.server.region.RegionZstdCodecTest :remapJar --no-daemon --console=plain
```

Fabric运行时探针及生成的临时region文件保留在该模块的`build/region-validation`下。探针实际加载生产Mixin，分别验证`region`、`entities`、`poi`目录中的`0x08`、`0x88`、失败中止和重新打开读取；没有启动完整游戏服务器。

Forge的临时验证配置位于其模块`build/region-validation/java25.init.gradle`，只对验证运行覆盖根模块工具链；未修改项目的Java目标配置。Forge完整ModLauncher运行、完整服务器保存/重启、Linux生产环境及性能实测仍未验证。
