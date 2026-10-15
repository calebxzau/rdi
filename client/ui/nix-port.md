# RDI客户端Linux/macOS移植计划

> 本文只描述计划，不授权实现。每个阶段开工前按`AGENTS.md`取得批准。

## 1.目标与范围

### 1.1目标平台

| 平台ID | 系统 | 架构 | 背景渲染/皮肤3D渲染 |
|---|---|---|---|
| `windows-x64` | Windows | x64 | 保留（现状不变） |
| `linux-x64` | Linux（X11/XWayland） | x64 | 保留，初始化失败时降级为静态图 |
| `macos-x64` | macOS | Intel | **关闭** |
| `macos-arm64` | macOS | Apple Silicon | **关闭** |

平台ID在服务端路由、构建参数、发布目录、启动脚本中统一使用，不再引入第二套命名。

### 1.2已确认的决定

1. **玩家自行安装JDK25**。启动器只检测并提示，不自动下载、不内置JRE。要求与Windows版一致：主版本号必须是25，且是完整JDK（有`javac`）。
2. **mac/Linux用shell脚本启动和自动更新**，取代Windows上的Rust`installer`和`updater`（`start.exe`）。Rust代码不移植，Windows发布流程保持不变。
3. **macOS不使用`bg-renderer`和`player-model`渲染器**，因此不处理OpenGL4.1降级和GLFW主线程问题。
4. 不做`.app`、`.dmg`、签名和公证；玩家在终端执行脚本启动。

### 1.3完成定义

- 三个新平台都能用`rdi.sh`完成：首次下载UI库 → 启动启动器 → 登录 → 下载整合包 → 启动MC进入房间。
- 再次启动时，脚本能按服务端manifest增量更新`lib/`，失败时用本地版本启动。
- Windows的`start.exe`更新链、发布任务、`/update/ui/*`旧接口行为不变。
- 下文第9节列出的单元测试全部通过；第10节的手动测试矩阵至少每个平台跑通一次。

## 2.现状摘要

### 2.1已经跨平台、无需改动

- **Compose UI、ViewModel、网络、服务端逻辑**：纯JVM代码。
- **MC启动（`mclaunch`/`mc-install`）**：
  - `LibraryOsArch`已有`MAC_X64/MAC_ARM64/LINUX_X64`。
  - manifest OS规则、`${arch}`替换、`.so/.dylib`识别、`unix_args.txt`、`:`classpath分隔符都已处理。
  - GTNH已匹配`osx-arm64`。
- **游戏Java**：`common/misc`的`javaExePath`取当前进程的java，MC直接用玩家的JDK25，不需要额外的Java查找。
- **MC端Mod（`client/mc/*`）**：未发现Windows专属代码。

### 2.2写死Windows的位置

| 类别 | 位置 | 现状 |
|---|---|---|
| Compose/Skiko原生库 | `ui`、`player-model`、`code-editor-ui`、`theme`、`webview2`的`build.gradle.kts` | `compose.desktop.currentOs`，即构建机平台（Windows） |
| LWJGL原生库 | `ui/build.gradle.kts:162`、`bg-renderer:34`、`player-model:35`、`render-core:21` | `natives-windows` |
| zstd-jni | `ui/build.gradle.kts:178-186` | 全局替换为`win_amd64` |
| sqlite-jdbc | `ui/database`、`ui/mod-catalog`的`build.gradle.kts` | 只把`Windows/x86_64/sqlitejdbc.dll`打进资源 |
| FFmpeg（bytedeco） | `mediaproc/build.gradle.kts:35,50,53` | `windows-x86_64-gpl` |
| FFmpeg运行时 | `MediaProcNativeRuntime.kt:12,34`、`MediaProcRuntimeClasspath.kt:12` | 前缀`windows-x86_64-gpl/`，校验`jniavutil.dll` |
| ffmpeg可执行文件 | `MinecraftLauncher.kt:39` | `ffmpeg.exe` |
| 早期画面视频 | `RdiVideoTexture.java:39` | 非Windows直接跳过（可接受） |
| 显示器模式 | `service/DisplayModes.kt` | 通过`GlfwRuntime`初始化GLFW读取 |
| JDK路径校验 | `WindowsUtils.kt:199-233` | 只认`java.exe/javaw.exe`，目录模式只找`bin/java.exe` |
| 目录选择器 | `AwtFileDialogs.kt:160`、`WindowsFolderPicker.kt` | Windows用COM；其他平台走AWT `FileDialog`（Linux上不能选目录） |
| updater自更新 | `Main.kt:168`、`UpdaterUpdater.kt` | 下载并替换`start.exe` |
| 证书库 | `Main.kt:91` | DEBUG时设置`Windows-ROOT` |
| 本地MC扫描 | `LocalMcScanner.kt:34`、`LocalMcDiscovery.kt:44`、`McDirectoryEnumerator.kt:50` | 非Windows返回空或走回退（可接受） |
| 固定盘检测 | `McInstallationValidator.kt:122` | 非Windows返回`false`（可接受） |
| 日志目录 | `ClientDirs.kt:7` | `LOCALAPPDATA`，没有时回退到`~/.rdi/logs`（可接受） |
| 服务端UI发布 | `server/master/.../UpdateService.kt` | 只有一套`releases/{version}.zip|json`，内容是Windows的`lib/` |
| 打包 | `ui/build.gradle.kts` `packageUiRelease`、`makeShipPackZst` | 只产出Windows的`lib/`；调用`C:/Program Files/7-Zip/7z.exe` |

## 3.总体设计

### 3.1运行时目录结构（mac/Linux）

```text
<安装目录>/
  rdi.sh            启动+更新脚本（玩家执行它）
  lib/              UI库，与Windows同名同结构，内容按平台区分
  .rdi-java         记住的JDK25路径（脚本写入，可删）
  .rdi-update.lock/ 更新锁（mkdir原子锁，运行时临时存在）
  其他              启动器运行时生成（data.db、mc/、dlc/……），因为RDIClient.DIR=user.dir
```

脚本必须先`cd`到自身所在目录，再启动Java，保证`user.dir`与Windows语义一致。

### 3.2发布物

- 每个平台只发一个很小的压缩包`rdi-<平台ID>.tar.gz`，里面只有`rdi.sh`和`使用说明.txt`。
- **首次运行**时`lib/`不存在，脚本按manifest下载全部文件，等同于完整安装。
- 不再为mac/Linux打约120MiB的完整包，也不需要`installer`。
- 用`tar.gz`而不是zip，是为了保留`rdi.sh`的可执行位；说明里仍然写`sh rdi.sh`，可执行位丢了也能跑。

### 3.3UI库按平台发布

每个平台的`lib/`由一份独立的版本化release描述（ZIP+manifest+`latest.txt`），和Windows现有格式完全一致，只是放在不同目录：

```text
client-libs/
  releases/                 Windows（旧路径，不动）
  releases-linux-x64/
  releases-macos-x64/
  releases-macos-arm64/
```

每个平台单独存一份ZIP（公共jar会重复存储），换来服务端和脚本逻辑最简单。按约120MiB×3估算，每个版本多占约360MiB；需要时可以再做去重（见第11节）。

## 4.阶段A：公共平台模型（`common`）

1. 在`common/model`的`LibraryOsArch`增加`clientPlatformId: String?`：
   - `WIN_X64→"windows-x64"`、`LINUX_X64→"linux-x64"`、`MAC_X64→"macos-x64"`、`MAC_ARM64→"macos-arm64"`。
   - `WIN_ARM64`和`LINUX_ARM64`为`null`，表示不支持。
2. 增加`LibraryOsArch.fromClientPlatformId(id): LibraryOsArch?`，供服务端校验路由参数。
3. 单元测试：映射双向一致，未知ID返回`null`。

不新建包，沿用现有`calebxzhou.rdi.common.model`。

## 5.阶段B：服务端（`server/master`）

### 5.1新路由（Windows旧路由保持原样）

| 路由 | 返回 |
|---|---|
| `GET /update/ui/p/{platform}/ver` | JSON：最新版本号 |
| `GET /update/ui/p/{platform}/ver.txt` | 纯文本：最新版本号（给shell用） |
| `GET /update/ui/p/{platform}/libs/{version}` | JSON：manifest |
| `GET /update/ui/p/{platform}/libs/{version}.txt` | 纯文本manifest，每行`<sha1> <文件名>`，按文件名排序 |
| `GET /update/ui/p/{platform}/lib/{version}/{name}` | 文件内容（从ZIP里流式读取，与现有`respondUiLibrary`一致） |
| `GET /update/script/{platform}` | `rdi.sh`文件内容 |
| `GET /update/script/{platform}/hash` | 纯文本：`rdi.sh`的sha1 |

提供`.txt`变体的原因：macOS默认没有`jq`，也不保证有`python3`，shell不适合解析JSON。纯文本manifest只需要`read`就能逐行处理。

### 5.2实现要点

- `UpdateService.kt`里的`loadUiRelease/loadLatestUiRelease`增加`releasesDir`参数。Windows传`releases`，其他平台传`releases-{platform}`，复用现有的校验逻辑（版本号正则、manifest文件名、sha1）。
- `{platform}`用`LibraryOsArch.fromClientPlatformId`校验，不认识的返回`RequestError("不支持的平台")`。显式拒绝`windows-x64`，避免两条路径指向同一份数据。
- `rdi.sh`存放在`client-libs/scripts/rdi.sh`（三个平台共用一份脚本；路由仍带`{platform}`，以后要分平台时不用改脚本里的URL）。
- 加速API（`/server-entry?myIp=`）：增加`format=text`参数，只返回api地址的纯文本，空串表示没有加速API。

### 5.3测试

在现有server测试里新增`UpdateServicePlatformTest.kt`：
- 平台目录选择；
- 非法平台；
- 拒绝`windows-x64`；
- `.txt`manifest格式与JSON内容一致；
- 文件名注入（`../`、`/`）被拒绝。

## 6.阶段C：构建与打包（`client/ui`及子模块）

### 6.1目标平台参数

- 新增Gradle属性`-Prdi.targetPlatform=<平台ID>`，默认`windows-x64`。不传参数时，现有所有任务的行为不变。
- 新增脚本插件`client/ui/gradle/target-platform.gradle.kts`，由每个涉及原生库的子模块`apply(from = ...)`。它根据平台ID写入`extra`属性：

| 属性 | windows-x64 | linux-x64 | macos-x64 | macos-arm64 |
|---|---|---|---|---|
| `composeTarget` | `windows_x64` | `linux_x64` | `macos_x64` | `macos_arm64` |
| `lwjglNatives` | `natives-windows` | `natives-linux` | `natives-macos` | `natives-macos-arm64` |
| `zstdClassifier` | `win_amd64` | `linux_amd64` | `darwin_x86_64` | `darwin_aarch64` |
| `sqliteClassifier` | `natives-windows` | `natives-linux` | `natives-mac` | `natives-mac` |
| `sqliteNativePath` | `Windows/x86_64/sqlitejdbc.dll` | `Linux/x86_64/libsqlitejdbc.so` | `Mac/x86_64/libsqlitejdbc.dylib` | `Mac/aarch64/libsqlitejdbc.dylib` |
| `ffmpegClassifier` | `windows-x86_64-gpl` | `linux-x86_64-gpl` | `macosx-x86_64-gpl` | `macosx-arm64-gpl` |

> 实现时要先在Maven Central确认每个classifier真实存在（尤其是zstd-jni和sqlite-jdbc当前版本的mac classifier名称），表中的值以实测为准。

### 6.2子模块改动

1. **`compose.desktop.currentOs`**：在`ui`、`player-model`、`code-editor-ui`、`theme`、`webview2`中改为`compose.desktop.<composeTarget>`。
2. **LWJGL natives**：
   - 在`ui`、`bg-renderer`、`player-model`、`render-core`中改用`lwjglNatives`。
   - mac也照样打包LWJGL natives，因为jar在classpath上不会被加载，只是多几MB。这样避免为mac单独裁剪依赖树。
3. **zstd-jni**：`ui/build.gradle.kts`里的全局替换改用`zstdClassifier`，并更新那条"只发布Windows"的注释。
4. **sqlite**：`ui/database`和`ui/mod-catalog`的`sqliteWindowsX64`配置改名为`sqliteNatives`，`include`路径改用`sqliteNativePath`。`mod-catalog-tools`只在开发机上跑，不改。
5. **FFmpeg**：`mediaproc/build.gradle.kts`的`runtimeOnly`改用`ffmpegClassifier`。`extractTestFfmpegNatives`和`nativePreloadTest`保持Windows（测试只在Windows开发机上跑）。
6. **`webview2`**：目前已经注释掉，不处理；如果以后重新启用，非Windows平台要排除它。

### 6.3发布任务

1. `desktopInstallLibs`、`packageUiRelease`的输出目录加上平台：
   - `build/install/ui/<平台ID>/lib`
   - `build/ui-releases/<平台ID>/<version>/`
   - Windows保留现有路径，或者同步改成带平台的路径，二选一，实施前确认。
2. `packageUiRelease`改用JDK自带的`java.util.zip`以`STORED`方式（等同现在的`-mx=0`）写ZIP，不再依赖`7z.exe`，之后在任何机器上都能出包。`validateUiReleaseArchive`保持不变。
3. `registerCopyTask`增加平台维度：
   - 非Windows平台发布到`client-libs/releases-<平台ID>/`；
   - 新增聚合任务`出core2-nix-local`和`出core2-nix-release`，依次以三个平台ID执行打包和复制。
   - 由于目标平台通过`-P`传入，聚合任务用`GradleBuild`或者在文档里写明分三次执行。实施时按Gradle配置缓存的兼容性选一种。
4. 新增`出rdi-sh-local`和`出rdi-sh-release`：把`client/ui/nix/rdi.sh`复制到`client-libs/scripts/`，并生成sha1。
5. 新增`makeNixShipPack`：为每个平台生成`rdi-<平台ID>.tar.gz`（只含`rdi.sh`和说明），用Gradle的`Tar`任务，`rdi.sh`的文件权限设为`0755`。
6. Windows的`makeShipPackZst`和`出updater-*`不动。

**交叉构建**：以上全是Maven classifier切换和纯JVM编译，在Windows开发机上就能产出三个平台的`lib/`，不需要Mac或Linux构建机。

## 7.阶段D：客户端运行时代码

按模块列出，所有判断统一用`LibraryOsArch.detectHostOs()`，删掉各处手写的`os.name.contains(...)`。

### 7.1`mediaproc`/`mclaunch`（FFmpeg）

- `MediaProcNativeRuntime`：
  - `NATIVE_PREFIX`改为按平台计算（`org/bytedeco/ffmpeg/<ffmpegClassifier>/`）；
  - 完整性检查从`jniavutil.dll`改为按平台的文件名（`jniavutil.dll`、`libjniavutil.so`、`libjniavutil.dylib`）；
  - 解压后对非`.dll`/`.so`/`.dylib`的可执行文件（`ffmpeg`）执行`setExecutable(true)`。
- `MediaProcRuntimeClasspath.nativeJarPattern`：改为匹配当前平台的classifier。
- `MinecraftLauncher.earlyDisplayJvmArgs`：可执行文件名在Windows上是`ffmpeg.exe`，其他平台是`ffmpeg`。
- `RdiVideoTexture.start()`的Windows限制先保留（mac/Linux早期画面没有视频，只有静态画面），作为第11节的后续项。

### 7.2`client/ui`

1. **`DisplayModes.kt`**：改为用AWT `GraphicsEnvironment.getScreenDevices()`和`displayModes`读取（全平台统一，Windows上也换掉）。这样启动器进程里不再有任何非渲染场景去初始化GLFW。输出格式保持`宽x高@刷新率`。
2. **渲染开关**：新增`RenderFeatures`（放在`calebxzau.rdi.client.ui`下），`val available = !LibraryOsArch.detectHostOs().isMac`。
   - `AppBackground.kt`：`available == false`时不调用`BackgroundRenderer.openSession()`，`AppBackgroundImage`直接用已有的`LocalAppBackgroundPainter`静态图。由于`LocalBackgroundRenderer`当前是必填的，要改成可空，或者提供一个空实现的`BackgroundRenderSession`。实施时看哪种对调用方改动更小。
   - Linux上`openSession()`抛异常时同样降级为静态图，并用`logger.warn(e)`记录。
   - `comp/PlayerModel.kt`：`available == false`时渲染2D皮肤正面图（或头像），共4个调用点：`MenuScreen`、`PlayerInfoScreen`、`SkinPreviewScreen`、`PlayerModel`自身。只改组件内部，调用方签名不变。
   - `LibraryOsArch`补一个`isMac`属性。
3. **`WindowsUtils.kt`的JDK校验**：
   - `resolveJavaExecutable`：目录模式按平台找`bin/java`或`bin/java.exe`；在mac上还要接受`.../Contents/Home`，以及用户选中`.jdk`包根目录时自动补上`Contents/Home`。
   - 文件模式接受`java`；去掉"补`.exe`"的分支在非Windows上的作用。
   - 文件名`WindowsUtils.kt`暂不改名（避免无关改动），后续可以拆成`JavaPathUtils.kt`。
4. **目录选择器（`AwtFileDialogs.pickAwtDirectory`）**：
   - Windows：保持COM选择器；
   - macOS：保持现有`apple.awt.fileDialogForDirectories`分支；
   - Linux：改用`JFileChooser`（`DIRECTORIES_ONLY`）。AWT `FileDialog`在GTK下不能选目录。
5. **updater自更新**：`Main.kt:168`处只在Windows上调用`UpdateService.updateUpdater`；非Windows跳过，脚本的自更新由脚本自己负责（见8.6）。
6. **证书库**：`Main.kt:91`只在Windows上设置`Windows-ROOT`；mac/Linux用JDK默认的cacerts。
7. **窗口透明**：Linux默认`WINDOW_TRANSPARENT=false`，避免没有合成器的桌面黑底或花屏。改`Const.kt`的`windowTransparent`默认值：传入平台判断，`-Drdi.window.transparent`仍然优先。
8. **`BaseWorldUploadService.kt:381`**：改用`LibraryOsArch.detectHostOs().isWindows`。macOS的默认文件系统也不区分大小写，可以一并按"Windows或mac时小写化"处理。这会改变mac上的任务键，但mac是新平台，没有历史数据。
9. **打开链接、打开目录**：`WindowsUtils.kt:37,43,139`的`Desktop.browse/open`在部分Linux桌面上不支持。加回退：`Desktop.isSupported`为`false`时，Linux执行`xdg-open`，mac执行`open`。
10. **日志目录**：`ClientDirs.logsDir`在非Windows上已经回退到`~/.rdi/logs`，保持不变。

### 7.3不改、只验证

- `LocalMcScanner`、`LocalMcDiscovery`、`McDirectoryEnumerator`、`WindowsMcFixedVolumeChecker`：非Windows已有安全回退，UI上"导入本地MC"类功能在mac/Linux找不到结果属于预期。实施时要确认UI的空状态文案不会误导玩家。
- `MinecraftManifestArguments`：确认原版manifest里给`osx`的`-XstartOnFirstThread`规则会传给1.20.1 Forge和1.21.1 NeoForge的MC进程（**只给MC进程，启动器自己的JVM绝不能加这个参数**）。

## 8.阶段E：`rdi.sh`

源码放在`client/ui/nix/rdi.sh`，必须是LF换行（参照`AGENTS.md`对`*.sh`的要求），用POSIX `sh`编写，兼容macOS自带的bash 3.2/zsh和Linux的dash。

### 8.1依赖

| 用途 | 首选 | 回退 |
|---|---|---|
| HTTP | `curl` | `wget` |
| sha1 | `sha1sum`（Linux） | `shasum -a 1`（mac自带） |
| 其他 | `mkdir`、`mv`、`rm`、`sed`、`uname` | — |

脚本开头逐个检查，缺失时打印要安装的包名并退出。

### 8.2命令行参数

| 参数 | 作用 | 对应Windows行为 |
|---|---|---|
| （无） | 更新后启动 | 默认 |
| `--no-update` | 跳过更新 | `--no-update` |
| `--debug` | 连本地调试服务器`http://127.0.0.1:65231`，传`-Drdi.debug=true` | `--debug` |
| `--app-logs` | 前台运行并输出日志 | `--app-logs` |
| `--select-java` | 忽略`.rdi-java`重新选择JDK | Shift菜单"重新手动选择Java25" |
| `--solid-window` | 传`-Drdi.window.transparent=false` | Shift菜单"实心窗口" |
| `--jvmArg=<参数>` | 追加JVM参数（可重复） | `--jvmArg=` |

不提供"卸载"：删除整个目录即可，说明文件里写明。

### 8.3流程

1. `cd "$(dirname "$0")"`，取绝对路径作为`ROOT`。
2. **平台识别**：
   - 由`uname -s`和`uname -m`得到平台ID：`Linux/x86_64`→`linux-x64`，`Darwin/x86_64`→`macos-x64`，`Darwin/arm64`→`macos-arm64`；
   - mac上如果`sysctl -n sysctl.proc_translated`为1（Rosetta下运行的shell），按`macos-arm64`处理；
   - 其他组合提示"不支持的系统"并退出。
3. **加锁**：`mkdir .rdi-update.lock`，失败则提示"另一个启动程序正在更新"后退出；用`trap`在`EXIT INT TERM`时删除锁。锁目录里写入PID，下次加锁失败时如果该PID已经不存在，视为陈旧锁并清理。
4. **更新**（除非`--no-update`）：
   1. 选API：非debug时请求`/server-entry?format=text&myIp=`（`myIp`取自`https://ip.3322.net/`，5秒超时，失败就留空）。返回非空就把加速API放在前面，官方`https://rdi.calebxzhou.cn:65331`作为备用。与Windows的`unique_urls`语义一致。
   2. 依次尝试每个API：`GET /update/ui/p/$PLATFORM/ver.txt`，校验版本号符合`^[A-Za-z0-9][A-Za-z0-9._-]*$`；再请求`libs/$VER.txt`，校验每行是40位十六进制sha1加安全文件名（不含`/ \ :`，不是`.`/`..`），并且包含`rdi-ui.jar`。全部API失败时打印警告，用本地版本启动。
   3. **判断是否最新**：本地`lib/`里文件集合与manifest完全一致，且每个文件sha1都匹配，就跳过。不解析jar里的`Implementation-Version`，sha1全匹配已经足够；为了提速，可以把`lib/.version`记录的版本号作为快速判断。
   4. **暂存**：创建`lib.updating-<时间戳>-<PID>/`。对manifest里每个文件：本地`lib/`里同名文件sha1匹配就`cp`过去，否则从`lib/$VER/$NAME`下载，按API顺序失败重试，下载后校验sha1。
   5. **切换**：`mv lib lib.previous-<id>` → `mv lib.updating-<id> lib` → 删除`lib.previous-<id>`。第二步失败时把`lib.previous-<id>`移回来。写入`lib/.version`。
   6. **残留清理**：启动时删除旧的`lib.updating-*`；如果`lib/`不存在但有`lib.previous-*`，先恢复最新的那个。
   7. 下载进度：`curl`用`--progress-bar`，`wget`用`--show-progress`；每个文件前打印`正在更新<文件名>`，文案与Windows版对齐。
5. **校验`lib/`**：没有任何`.jar`时提示"缺少UI库文件，请检查网络后重试"并退出（首次安装时网络不通就会走到这里）。
6. **查找JDK25**（见8.4）。
7. **启动**：

   ```sh
   exec "$JAVA" -Dfile.encoding=UTF-8 $用户JVM参数 \
     -Drdi.debug=$DEBUG [-Drdi.noUpdate=true] [-Drdi.window.transparent=false] \
     -Drdi.updater.islogmode=$APP_LOGS -Drserverurl=$SERVER_URL \
     -cp "lib/*" --enable-native-access=ALL-UNNAMED calebxzau.rdi.client.MainKt
   ```

   - 不传`-Drdi.updater.pid`（只有`UpdaterUpdater`使用它，非Windows不会走到那里）。
   - 非`--app-logs`模式：用`nohup`后台启动，日志重定向到`~/.rdi/logs/launcher-stdout.log`，然后观察3秒，进程提前退出就打印退出码和日志末尾20行。与Windows"3秒内退出"检测一致。
   - `--app-logs`模式：前台`exec`。
   - `$用户JVM参数`用`set --`构造参数数组，避免空格拆分问题。

### 8.4JDK25查找顺序

逐个检查候选，命中第一个合格的就停：

1. 环境变量`RDI_JAVA_HOME`；
2. `.rdi-java`里记住的路径；
3. `JAVA_HOME`；
4. macOS：`/usr/libexec/java_home -v 25`；
5. `PATH`中的`java`（用`command -v java`，再经`readlink`解析出真实的home）；
6. 常见目录：
   - Linux：`/usr/lib/jvm/*`、`/opt/*jdk*`、`~/.sdkman/candidates/java/*`、`~/.jdks/*`；
   - mac：`/Library/Java/JavaVirtualMachines/*/Contents/Home`、`~/Library/Java/JavaVirtualMachines/*/Contents/Home`、`~/.sdkman/candidates/java/*`。

**合格条件**：

- `bin/java`和`bin/javac`都可执行；
- `java -XshowSettings:properties -version 2>&1`中`java.specification.version = 25`；
- `javac -version`输出以`javac 25`开头。

找到后写入`.rdi-java`。找不到时打印：

```text
未找到可用的JDK25。
macOS:  brew install --cask temurin@25    或从 https://adoptium.net 下载 .pkg
Ubuntu/Debian: sudo apt install openjdk-25-jdk
Fedora: sudo dnf install java-25-openjdk-devel
Arch: sudo pacman -S jdk25-openjdk
已安装在其他位置时：RDI_JAVA_HOME=/path/to/jdk sh rdi.sh
```

（包名在实施时逐个核实，国内镜像下载地址可以一并给出。）

### 8.5macOS特殊处理

- 首次运行时执行`xattr -dr com.apple.quarantine "$ROOT" 2>/dev/null || true`，避免浏览器下载的文件带隔离标记。JVM运行时自己解压的dylib不会被打标记。
- 启动器的JVM**不加**`-XstartOnFirstThread`（会和AWT冲突）。

### 8.6脚本自更新

在第4步更新UI库之前执行：

1. 请求`/update/script/$PLATFORM/hash`，与本地`rdi.sh`的sha1比较；
2. 不一致时下载到`rdi.sh.new`，校验sha1，`chmod 755`，再`mv rdi.sh.new rdi.sh`（原子替换，当前进程继续读旧inode，不受影响）；
3. 释放锁后`exec sh "$ROOT/rdi.sh" "$@"`重新执行新脚本；
4. 用环境变量`RDI_SCRIPT_UPDATED=1`防止无限循环。

### 8.7脚本质量

- `shellcheck -s sh rdi.sh`零警告（在WSL里运行，这是外部工具，不是Gradle任务）。
- 所有变量加双引号；`set -eu`；关键函数遇错打印中文提示。
- 文案遵守`AGENTS.md`第12节：中文与数字、英文之间不加空格。

## 9.阶段F：自动化测试

| 测试 | 位置 | 内容 |
|---|---|---|
| `LibraryOsArchTest.kt` | `common/model` | 平台ID映射、`isMac` |
| `UpdateServicePlatformTest.kt` | `server/master` | 5.3所列 |
| `MediaProcNativeRuntimeTest.kt`（扩展） | `mediaproc` | 用伪造的jar覆盖三个平台的前缀、完整性检查文件名、`ffmpeg`可执行位 |
| `MediaProcRuntimeClasspathTest.kt`（扩展） | `mediaproc` | 各平台的classifier匹配 |
| `MinecraftLauncherTest.kt`（扩展） | `mclaunch` | `ffmpeg`/`ffmpeg.exe`按平台选择 |
| `JdkPathValidationTest.kt` | `client/ui` | 用临时目录伪造`bin/java`、`bin/javac`、mac的`Contents/Home`结构；`java -version`的执行通过注入函数替换 |
| `DisplayModesTest.kt` | `client/ui` | 格式化函数（与AWT调用分离后可测） |
| `WindowTransparencyTest`（扩展现有） | `client/ui` | Linux默认不透明，JVM属性优先 |
| `packageUiRelease`自检 | Gradle | 已有ZIP/manifest一致性校验；另外对每个平台检查产物里包含对应的Skiko、LWJGL、sqlite、FFmpeg原生库，并且**不包含**其他平台的 |
| `shellcheck` | `client/ui/nix` | 8.7 |

平台判断全部抽成"传入`LibraryOsArch`"的纯函数，测试在Windows开发机上就能覆盖所有平台分支，不需要在测试里改`os.name`。

## 10.阶段G：手动测试矩阵

| 场景 | linux-x64 | macos-x64 | macos-arm64 |
|---|---|---|---|
| 首次运行：空目录 → 下载`lib/` → 启动 | ☐ | ☐ | ☐ |
| 未装JDK25：提示文案正确 | ☐ | ☐ | ☐ |
| 装了JDK21和JDK25：选中25 | ☐ | ☐ | ☐ |
| 再次启动：无更新时快速跳过 | ☐ | ☐ | ☐ |
| 发新版本后：增量更新 | ☐ | ☐ | ☐ |
| 更新中途断网：用本地版本启动，`lib/`完整 | ☐ | ☐ | ☐ |
| 更新中途`Ctrl+C`：下次启动能恢复 | ☐ | ☐ | ☐ |
| 脚本自更新 | ☐ | ☐ | ☐ |
| 登录、主菜单（mac为静态背景和2D皮肤） | ☐ | ☐ | ☐ |
| Linux：背景渲染正常；GL失败时降级 | ☐ | — | — |
| 目录选择器、打开目录、打开链接 | ☐ | ☐ | ☐ |
| 下载整合包，启动1.20.1 Forge进房间 | ☐ | ☐ | ☐ |
| 启动1.21.1 NeoForge进房间 | ☐ | ☐ | ☐ |
| MC早期加载窗口显示正常（mac重点） | ☐ | ☐ | ☐ |
| AVIF/音频等FFmpeg相关功能 | ☐ | ☐ | ☐ |

**测试环境**：

- linux-x64：可以先在WSL2+WSLg里做冒烟测试（需要在WSL里装Linux版JDK25，运行打好的`lib/`，不在WSL里跑Gradle），再找一台真Linux桌面（建议Ubuntu 24.04 GNOME Wayland，加一个X11桌面）复测。
- macOS：必须有真机。arm64和Intel各一台；如果没有Intel Mac，可以在arm64上用Rosetta跑x64版JDK作为替代，但要注明不是真机结果。

## 11.风险与后续项

| 风险 | 影响 | 缓解 |
|---|---|---|
| MC进程在mac上需要`-XstartOnFirstThread`，早期加载窗口（GLFW）在mac上能否正常工作未验证 | mac上可能进不了游戏 | 阶段G优先测；必要时mac上关闭RDI早期画面，走Forge/NeoForge自带的流程 |
| 某些classifier名称或版本不存在（zstd-jni、sqlite-jdbc的mac包） | 构建失败或运行时找不到原生库 | 6.1先核实；`packageUiRelease`自检会拦住 |
| Linux桌面差异（Wayland、无合成器、缺GTK库） | 窗口、对话框、`Desktop` API异常 | 默认不透明窗口；`JFileChooser`；`xdg-open`回退 |
| Apple Silicon上加载未签名的dylib | 原生库加载失败 | Temurin的JDK允许加载第三方库；Skiko、sqlite、bytedeco的arm64库自带签名或ad-hoc签名；阶段G验证 |
| 每个版本多占约360MiB服务端存储 | 存储成本 | 后续可以改为"共享jar池+各平台manifest"，脚本和接口不用变 |
| 只有shell，没有图形化提示 | 新手不熟悉终端 | 说明文件写清楚一行命令；以后可以加`.desktop`文件和mac的`.command`包装 |

后续可选：

- mac/Linux早期画面视频（放开`RdiVideoTexture`的平台限制，验证`ffmpeg`管道）；
- mac原生标题栏按钮（`WindowChrome`）；
- Linux arm64、Windows arm64；
- 服务端存储去重。

## 12.实施顺序与依赖

```text
A common平台模型
 └─ B 服务端路由 ──────────────┐
 └─ C 构建按平台出包 ──────────┤
 └─ D 客户端运行时代码 ────────┤
                               └─ E rdi.sh（依赖B的接口和C的产物）
                                   └─ F自动化测试（随各阶段一起写）
                                       └─ G手动测试矩阵
```

建议的里程碑：

1. **M1 Linux可玩**：A+B+C+D（去掉mac专项）+E，在WSLg和真Linux上跑通。
2. **M2 macOS可玩**：D里的mac专项（渲染开关、JDK路径、quarantine），在真Mac上跑通。
3. **M3 发布**：`makeNixShipPack`、说明文件、服务端上线`releases-*`和`scripts/`。

## 13.实施前待确认

1. Windows的`desktopInstallLibs`和`packageUiRelease`输出路径是否同步改成带平台的路径（6.3-1）。
2. `PlayerModel`在mac上的替代显示：2D皮肤正面图，还是只显示头像。
3. 服务端是否同意新增`/update/ui/p/*`、`/update/script/*`和`server-entry?format=text`这几个接口（5.1）。
4. 说明文件和发布包放在哪里下载（官网或群文件），决定`使用说明.txt`的措辞。
