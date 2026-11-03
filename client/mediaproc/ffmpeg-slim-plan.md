# mediaproc瘦身计划（待实施）

状态：阶段1实施中（2026-11-03），工作流为`mediaproc-tests.yml`。三个平台都通过后再确认阶段2的改动清单。

## 决定

- 不用Rust重写`mediaproc`，也不单独带libaom、libopus。保留FFmpeg，只重新编译一个精简版，把这些库静态链接进去。
- 先去掉JavaCV，再做精简FFmpeg。
- Linux和macOS的解码在GitHub Actions上直接测`mediaproc`，不启动游戏。
- 2026-11-03已移除自定义启动画面，不再需要`ffmpeg.exe`和滤镜。

## 依据

测试环境：Ryzen 7 7840HS，游戏外的普通JVM，样本来自本机的真实整合包。

| 项目 | 实测 |
|---|---|
| FFmpeg加载 | 约0.8秒，工作集增加约60MiB |
| AVIF小贴图（≤128px） | 每张约4～5ms |
| 样本里最大的AVIF | 约40ms |
| 全部64张AVIF样本 | 顺序0.3～0.4秒，并发约0.16秒 |
| Opus（600个真实文件） | 中位3.5ms，p95为12ms，最长45ms |

- 268个模组的整合包启动约62秒，上面这些加起来不到2%，所以Rust重写在性能上没有明显空间。
- 只有超过50KB的PNG才转AVIF。真实包的模组jar里最多49个AVIF，PNG有几万个。
- 2516个真实模组jar都没有自带JavaCPP或OpenCV（另有412个失效链接未扫描，内嵌jar只按文件名判断）。
- 本机34份崩溃报告都没提到JavaCPP或FFmpeg，3份`hs_err`也不是崩在FFmpeg里。
- 兼容性上的麻烦主要来自JavaCV：它的`module-info`依赖OpenCV、Tesseract等12个无关模块，只能靠`extraJavaModuleInfo`改写。
- 现有大小：Windows的natives jar为34MB（解压约98MB），其中`avcodec-62.dll`为64MB。JNI胶水共3.35MB，`javacpp`、`javacv`和FFmpeg的Java API共1.23MB。

## 阶段1：测试基线（先做）

目的：用现在的bytedeco FFmpeg在三个平台跑通，之后再失败就能定位到具体改动。阶段2的重构也因此能在三个平台上验证。

1. 去掉`FfmpegAvifDecoderTest`、`FfmpegPcmDecoderTest`、`OggTranscoderTest`里的`assumeWindowsX64()`。现在这些用例在非Windows上会被跳过，CI显示通过但没有实际测试。
2. `extractTestFfmpegNatives`改用`ffmpegClassifier`，不再写死Windows的`.dll`，要处理`.so*`和`.dylib`。
3. 新增GitHub Actions工作流，矩阵为`windows-latest`、`ubuntu-24.04`、`macos-15`，跑`:mediaproc:test`和`:mediaproc:nativePreloadTest`，并检查没有被跳过的用例。
   - 这个Gradle工程还没在CI上跑过，第一次可能要修环境问题。
   - 用`-Prdi.targetPlatform=<platform>`选择平台。
4. 增加旧文件兼容样本：用现版FFmpeg生成带透明通道的AVIF和Opus，各平台解码后和参考结果对比。
   - 允许小误差，x86和ARM的SIMD结果可能不完全相同。
   - 样本要自己生成。仓库是公开的，不能放Minecraft或整合包素材。

## 阶段2：去掉JavaCV

| 位置 | 改动 |
|---|---|
| `FfmpegPcmDecoder`（游戏内） | 去掉`FFmpegFrameGrabber`，改为直接调FFmpeg API，写法和`FfmpegAvifDecoder`一致 |
| `OggTranscoder`（启动器） | 去掉`FFmpegFrameGrabber`和`FFmpegFrameRecorder`，改为直接调FFmpeg API |
| `FfmpegAvifNative`、`MediaProcRuntimeClasspath` | 删除对JavaCV的引用 |
| `FfmpegPcmDecoderTest`、`OggTranscoderTest` | 测试样本不再用JavaCV生成 |
| `FfmpegNativePreloader` | 测试确认后，从`FFMPEG_CLASSES`移除`avfilter`和`avdevice` |
| `.github/workflows/mediaproc-tests.yml` | 去掉安装`libpulse0`等系统库的步骤。`avdevice`依赖这些库，缺了整个FFmpeg都加载不了；不装也能通过，才说明这个依赖真的没了 |
| `client/mediaproc`、`client/ui`、`1.20.1-forge`、`1.21.1-neoforge`、`1.20.1-fabric`的构建配置 | 移除JavaCV依赖和`extraJavaModuleInfo`里的改写 |
| `MediaProcGameClasspathTest` | 更新预期，修掉过期断言 |

`MediaProcGameClasspathTest`现在卡在`startsWith("mediaproc")`这条断言上（实际jar名是`rdi-mediaproc.jar`），后面检查`requires`的断言一直没有跑到。

必须保持的行为：

- **转码输出：** Opus、双声道、24kHz、48kbps，`vbr=on`，`application=audio`。
- **时长上限：** 超过10秒返回`TooLong`。
- **游戏内解码：** 现有测试预期48kHz输出；`readAll`默认上限32MiB。
- **帧大小：** `FFmpegFrameRecorder`现在替我们做重采样和分帧。改用直接API后，要自己用swresample和音频FIFO凑够libopus要求的帧长。这是这一阶段最容易出错的地方。

顺带做：在`MinecraftLauncher`和开发运行配置里加`-Dorg.bytedeco.javacpp.maxPhysicalBytes=0`。否则内存占用超过`Xmx`的4倍时，`Pointer`会反复`System.gc()`并`sleep`，最后抛`OutOfMemoryError`。

预期收益：不再有OpenCV这些无关依赖，不再依赖`java.desktop`，日志里没有每次打开声音时的`rejected some options`，大小再省约1MB。

## 阶段3：精简FFmpeg

先只做Windows x64，实测大小后再做其他平台。预计jar从34MB降到4～6MB，这是估算。

1. 编译FFmpeg 8.0.1，LGPL配置，第三方库全部静态链接：
   ```
   --disable-everything --disable-programs --disable-doc --disable-network --disable-autodetect
   --enable-shared --disable-static
   --enable-libdav1d --enable-libaom --enable-libopus
   --enable-decoder=libdav1d,opus,vorbis --enable-encoder=libaom_av1,libopus
   --enable-demuxer=ogg,mov --enable-muxer=ogg,avif
   --enable-parser=av1,opus,vorbis --enable-protocol=file
   --enable-bsf=av1_metadata
   --enable-swscale --enable-swresample
   ```
   - libaom只编编码器（`CONFIG_AV1_DECODER=0`），解码交给dav1d。确认`FfmpegAvifEncoder`用的`crf`、`cpu-used`、`still-picture`、`usage=allintra`、`row-mt`都还可用。
   - `av1_metadata`是凭记忆加的（写AVIF时mov封装可能用到），第一次编译先验证，也要确认还缺不缺其他比特流过滤器。
   - 阶段2完成后，确认`jniavformat`等胶水不依赖avfilter和avdevice，就可以`--disable-avfilter --disable-avdevice`，并从jar里去掉对应文件。否则保留空壳库。
2. 继续用bytedeco的`jni*`胶水和Java API，只替换`av*`、`sw*`动态库。版本号和库文件名必须一致（`avcodec-62`、`avutil-60`、`avformat-62`、`swscale-9`、`swresample-6`）。
   - 打包前对比新旧库的导出符号（`objdump -p`、`nm -D`、`nm -gU`），确认胶水需要的符号都在。
   - 检查JavaCPP presets的`cppbuild.sh`对FFmpeg打的补丁，评估是否要同样打上。
3. 打包成`rdi-ffmpeg-natives-<platform>.jar`，内部路径保持`org/bytedeco/ffmpeg/<classifier>/`。
   - 把`mediaproc/build.gradle.kts`里的`runtimeOnly("org.bytedeco:ffmpeg:...:$ffmpegClassifier")`换成新jar。
   - 分类名里的`-gpl`要决定是保留还是改掉。改的话要同步`target-platform.gradle.kts`、`FfmpegNativePlatform`、`uiPlatformNativeMarkers`，以及`1.20.1-forge`、`1.21.1-neoforge`构建配置里写死的`windows-x86_64-gpl`。
   - 确认`MediaProcNativeRuntime`能识别新jar并重新解压，老玩家的缓存不会造成混用。
4. 再做Linux x64和macOS arm64，放到GitHub Actions上编译。

各平台注意点：

- **Linux：** 在`ubuntu-22.04`上编（glibc 2.35，和`macos-local-proxy.yml`的目标一致），在22.04和24.04上都测。rpath要用`$ORIGIN`。
- **macOS：** install name要和胶水的期望一致。`install_name_tool`会让签名失效，改完要重新做ad-hoc签名，并用`codesign -dv`检查。
- **Windows：** 用MSYS2/mingw，确认DLL不依赖额外的运行库。

## CI检查项

| 步骤 | 内容 | 能发现什么 |
|---|---|---|
| 库检查 | `ldd`、`otool -L`看依赖，对比胶水需要的符号，macOS查`codesign` | 缺符号、依赖系统库、签名失效 |
| 解码测试 | 三个平台跑`:mediaproc:test`和`nativePreloadTest` | AV1和Opus解码、Vorbis转码、AVIF编码、按游戏方式加载 |
| 旧文件兼容 | 解码阶段1的样本并对比 | 玩家已上传的旧文件读不出来 |

CI不跑游戏，原因：

- 解码不需要显示器和GPU。`AvifNativeImageAdapter`和`FfmpegAudioStream`这层各平台是同一份代码。
- macOS runner是`Apple M1 (Virtual)`，有桌面会话但显卡信息为空。NoMac的同类虚拟机上GLFW要靠`swgl`才能开窗，runner上还没试过。
- runner很可能没有音频设备，听不到实际效果。
- Linux以后可以选加Xvfb冒烟测试（`mc-runtime-test`支持1.20.1 Forge和1.21.1 NeoForge），它走HeadlessMC，不经过我们的启动器。

公开仓库的限制：不用真实账号，不打印节点地址，不把Minecraft文件上传成artifact。

## 发布前手动验证

每个阶段合入发布前各做一轮：

- Windows本机和macOS（NoMac）进世界。Linux找一台有显示和声卡的真机或虚拟机。
- 1.20.1 Forge和1.21.1 NeoForge都验证：AVIF贴图（含Effekseer贴图适配）显示正常，Opus声音正常。
- 启动器真实上传一个含大PNG和OGG的整合包。
- 首次安装和更新都能正确下载新的natives jar。

## 待定问题

- 精简版jar放在哪里（仓库外的Maven、GitHub Release，还是提交到仓库）。
- LGPL的合规做法：动态链接已满足，还要确定FFmpeg源码和配置的提供方式。

## Rust方案（暂不做）

把dav1d、libaom、libopus静态链接成一个Rust原生库，通过JNI加载（`NativeLocalProxy`有用JNA加载Rust库的先例）。

- **能得到：** 不再需要JavaCPP和JavaCV，只有一个原生文件，macOS签名更简单，比精简FFmpeg再省2～4MB（估算）。
- **得不到：** 解码速度基本不变；这些库仍是C代码，原生崩溃风险还在，JNI边界是新的风险点。
- **代价：** 重写约1750行，三个平台交叉编译C依赖，自己补重采样、Vorbis解码、AVIF容器解析和YUV转换，并保证读得了旧文件。

以下情况再考虑：精简版和JNI胶水对不上、macOS动态库链难以维护，或玩家报了JavaCPP相关问题。
