# RDI客户端Linux/macOS移植计划

> 本文只描述计划，不授权实现。每个阶段开工前按`AGENTS.md`取得批准。

## 1.目标与范围

### 1.1目标平台

| 平台ID | 系统 | 架构 | 背景渲染/皮肤3D渲染 |
|---|---|---|---|
| `windows-x64` | Windows | x64 | 保留（现状不变） |
| `linux-x64` | Linux（X11/XWayland） | x64 | 保留，初始化失败时降级为静态图 |
| `macos-arm64` | macOS | Apple Silicon | **关闭** |

只支持这三个平台。Intel Mac、Linux arm64、Windows arm64不支持，`rdi.sh`遇到时提示并退出。

**最低系统版本**（由FFmpeg原生库决定，见6.1）：

- Linux：glibc 2.35及以上，即Ubuntu 22.04+、Debian 12+、Fedora 36+、Arch等滚动发行版；需要桌面环境常见的`libGL`、`libX11`、`libfontconfig`、`libxcb`、`libasound`、`libpulse`。
- macOS：15.0（Sequoia）及以上。所有Apple Silicon Mac都能升级到15，所以这个要求只需提示玩家升级系统。

平台ID在服务端路由、构建参数、发布manifest、启动脚本中统一使用，不再引入第二套命名。

### 1.2已确认的决定

1. **玩家自行安装JDK25**。启动器只检测并提示，不自动下载、不内置JRE。要求与Windows版一致：主版本号必须是25，且是完整JDK（有`javac`）。
2. **mac/Linux用shell脚本启动和自动更新**，取代Windows上的Rust`installer`和`updater`（`start.exe`）。Rust代码不移植，Windows发布流程保持不变。
3. **macOS不使用`bg-renderer`和`player-model`渲染器**，因此不处理OpenGL4.1降级和GLFW主线程问题。
4. 不做`.app`、`.dmg`、签名和公证；玩家在终端执行脚本启动。
5. **UI库发布格式**：三个平台共用一个版本号和一个文件池（ZIP），每个平台一份manifest，按版本分目录放在新目录`client-libs/ui/`下。`start.exe`不读服务器文件，只调接口，所以`/update/ui/*`旧接口改为读`windows-x64.json`后，Rust代码不用改（见3.3）。
6. **Apple Silicon只接受arm64版JDK25**。x64版JDK在Rosetta下运行时会加载x64原生库，而我们只发布arm64的，所以必须拒绝（见8.4）。

### 1.3完成定义

- 两个新平台都能用`rdi.sh`完成：首次下载UI库 → 启动启动器 → 登录 → 下载整合包 → 启动MC进入房间。
- 再次启动时，脚本能按服务端manifest增量更新`lib/`，失败时用本地版本启动。
- Windows的`start.exe`更新链、发布任务、`/update/ui/*`旧接口行为不变。
- 下文第9节列出的单元测试全部通过；第10节的手动测试矩阵至少每个平台跑通一次。

## 2.现状摘要

### 2.1已经跨平台、无需改动

- **Compose UI、ViewModel、网络、服务端逻辑**：纯JVM代码。
- **MC启动（`mclaunch`/`mc-install`）**：
  - `LibraryOsArch`已有`MAC_ARM64/LINUX_X64`。
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
| sqlite-jdbc | `ui/database`、`ui/mod-catalog`的`build.gradle.kts` | 把`Windows/x86_64/sqlitejdbc.dll`直接打进`rdi-database.jar`和`rdi-mod-catalog.jar`，导致这两个jar内容随平台变化 |
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
| 打包 | `ui/build.gradle.kts` `packageUiRelease`、`makeShipPackZst` | 只产出Windows的`lib/`；调用`C:/Program Files/7-Zip/7z.exe`；`validateUiReleaseArchive`要求ZIP文件列表与manifest完全相等 |

### 2.3现有`lib/`的构成（6.6.2，Windows）

共189个文件，128MB。和平台有关的只有：

| 文件 | 大小 |
|---|---|
| `ffmpeg-…-windows-x86_64-gpl.jar` | 34MB |
| `skiko-awt-runtime-windows-x64-….jar` | 10.6MB |
| `zstd-jni-…-win_amd64.jar` | 0.5MB |
| `lwjgl-*-natives-windows.jar`×3 | 0.4MB |
| `rdi-database.jar`、`rdi-mod-catalog.jar`里的`sqlitejdbc.dll` | 约1MB |

其余约80MB在所有平台上完全相同（`jna`自带全平台原生库，不用区分）。

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

- 每个平台发一个完整包`rdi-<平台ID>.tar.gz`（2026-10-16改为完整包）：`rdi/rdi.sh`、`rdi/使用说明.txt`和`rdi/lib/`。Linux约130MB，mac约150MB。
- `lib/`取自生成发布manifest的同一份`build/install/ui/<平台ID>/lib`，所以首次运行直接提示"UI库已是最新"；之后的版本只下载有变化的文件。完整包可以放群文件或网盘，不占服务器带宽，和Windows的`makeShipPackZst`思路一致。
- 脚本仍然保留下载完整`lib/`的能力（`lib/`缺失或被删时自动补齐）。不需要`installer`。
- 用`tar.gz`而不是zip，是为了保留`rdi.sh`的可执行位；说明里仍然写`sh rdi.sh`，可执行位丢了也能跑。

### 3.3UI库发布格式：一个版本、一个文件池、每个平台一份manifest

```text
client-libs/ui/
  latest.txt                     三个平台共用一个版本号
  {version}/
    release.zip                  去重后的全部文件（公共jar只存一份）
    windows-x64.json
    linux-x64.json
    macos-arm64.json
```

- 用新目录`client-libs/ui/`，不复用旧的`client-libs/releases/`：新旧服务端对文件位置的理解不同，如果共用一个`latest.txt`，切换期间总有一边读不到文件。旧目录原样保留作为归档。
- manifest文件名就是平台ID，服务端直接用`{platform}.json`拼路径，不需要映射。
- 每份manifest仍是`文件名→sha1`，格式与旧的`{version}.json`完全相同；每个文件名都必须在`release.zip`里。
- 发布时先复制整个`{version}/`目录，最后写`latest.txt`。
- **同名文件在所有平台上sha1必须相同**，打包时检查，不一致就失败。平台相关的文件靠文件名区分（`skiko-awt-runtime-linux-x64-….jar`、`rdi-sqlite-natives-macos-arm64.jar`等）。
- 一个版本号同时发布三个平台，避免"只发了Windows，mac/Linux玩家用旧UI连新服务端"。
- 旧版`start.exe`的接口不变，服务端改为从`{version}/windows-x64.json`读取manifest；服务端只允许下载manifest里列出的文件，所以ZIP里多出的Linux/mac文件对它不可见。
- 存储：每个版本约80MB公共文件+3×47MB平台文件≈220MB（各平台分开存一份ZIP时约384MB）。玩家只下载自己平台manifest里的文件，下载量不变。

## 4.阶段A：公共平台模型（`common`）

1. 在`common/model`的`LibraryOsArch`增加`clientPlatformId: String?`：
   - `WIN_X64→"windows-x64"`、`LINUX_X64→"linux-x64"`、`MAC_ARM64→"macos-arm64"`。
   - `MAC_X64`、`WIN_ARM64`和`LINUX_ARM64`为`null`，表示不支持。
2. 增加`LibraryOsArch.fromClientPlatformId(id): LibraryOsArch?`，供服务端校验路由参数。
3. 单元测试：映射双向一致，未知ID返回`null`。

不新建包，沿用现有`calebxzhou.rdi.common.model`。

## 5.阶段B：服务端（`server/master`）

### 5.1新路由（Windows旧路由保持原样）

| 路由 | 返回 |
|---|---|
| `GET /update/ui/p/{platform}/ver` | JSON：最新版本号；加`?format=text`返回纯文本 |
| `GET /update/ui/p/{platform}/libs/{version}` | JSON：manifest；加`?format=text`返回纯文本，每行`<sha1> <文件名>`，按文件名排序 |
| `GET /update/ui/p/{platform}/lib/{version}/{name}` | 文件内容（从ZIP里流式读取，与现有`respondUiLibrary`一致） |
| `GET /update/script/{platform}` | `rdi.sh`文件内容 |
| `GET /update/script/{platform}/hash` | 纯文本：`rdi.sh`的sha1 |

提供`format=text`的原因：macOS默认没有`jq`，也不保证有`python3`，shell不适合解析JSON。纯文本manifest只需要`read`就能逐行处理。原计划的`ver.txt`、`{version}.txt`路径会和`{version}`参数冲突（版本号本身允许带`.`），所以改用查询参数（M1已实现）。

### 5.2实现要点

- `UpdateService.kt`改为读`client-libs/ui/`：`loadUiRelease/loadLatestUiRelease`增加`platform`参数，读`{version}/release.zip`和`{version}/{platform}.json`。旧接口固定传`windows-x64`。版本号正则、manifest校验、`respondUiLibrary`全部复用。
- 某个平台的manifest不存在时返回`RequestError("该平台暂无此版本")`。
- `{platform}`用`LibraryOsArch.fromClientPlatformId`校验，不认识的返回`RequestError("不支持的平台")`。新路由显式拒绝`windows-x64`，Windows只走旧路由。
- `rdi.sh`存放在`client-libs/scripts/rdi.sh`（Linux和mac共用一份脚本；路由仍带`{platform}`，以后要分平台时不用改脚本里的URL）。
- 加速API（`/server-entry?myIp=`）：增加`format=text`参数，只返回api地址的纯文本，空串表示没有加速API。

### 5.3测试

在现有server测试里新增`UpdateServicePlatformTest.kt`：
- 新目录结构的读取，旧接口读`windows-x64.json`；
- 同一个ZIP下，A平台manifest里没有的文件，用A平台路由下载时被拒绝；
- 某平台manifest缺失；
- 非法平台、`macos-x64`；
- 新路由拒绝`windows-x64`；
- `.txt`manifest格式与JSON内容一致；
- 文件名注入（`../`、`/`）被拒绝。

## 6.阶段C：构建与打包（`client/ui`及子模块）

### 6.1目标平台参数

- 新增Gradle属性`-Prdi.targetPlatform=<平台ID>`，默认`windows-x64`。不传参数时，现有所有任务的行为不变。
- 新增脚本插件`client/gradle/target-platform.gradle.kts`（M1已实现），由每个涉及原生库的子模块`apply(from = ...)`。放在`client/`下，是因为`client/mc/*`的MC构建也引用了`mediaproc`；它们不传参数，默认就是`windows-x64`。插件根据平台ID写入`extra`属性（Compose直接用`desktop-jvm-<平台ID>`，平台ID和Compose的命名一致）：

| 属性 | windows-x64 | linux-x64 | macos-arm64 |
|---|---|---|---|
| `composeTarget` | `windows_x64` | `linux_x64` | `macos_arm64` |
| `lwjglNatives` | `natives-windows` | `natives-linux` | `natives-macos-arm64` |
| `zstdClassifier` | `win_amd64` | `linux_amd64` | `darwin_aarch64` |
| `sqliteClassifier` | `natives-windows` | `natives-linux` | `natives-mac` |
| `sqliteNativePath` | `Windows/x86_64/sqlitejdbc.dll` | `Linux/x86_64/libsqlitejdbc.so` | `Mac/aarch64/libsqlitejdbc.dylib` |
| `ffmpegClassifier` | `windows-x86_64-gpl` | `linux-x86_64-gpl` | `macosx-arm64-gpl` |

> **已核实（2026-10-16，Maven Central）**：表中所有classifier在当前版本都存在。sqlite-jdbc 3.53.4.0、zstd-jni 1.5.7-23（注意是`darwin_aarch64`，没有`darwin_arm64`）、ffmpeg 8.0.1-1.5.13、skiko 0.150.1、`desktop-jvm-*` 1.12.1、LWJGL 3.4.3。
>
> 原生库内容：
> - FFmpeg的Linux包里是`libjniavutil.so`、`libavutil.so.60`这类带版本号的文件名，mac包里是`libjniavutil.dylib`、`libavutil.60.dylib`；`ffmpeg`、`ffprobe`都没有扩展名。
> - sqlite原生库路径是`Linux/x86_64/libsqlitejdbc.so`和`Mac/aarch64/libsqlitejdbc.dylib`。Linux包里还有`Linux-Musl`版本，我们不带，所以Alpine这类musl系统不支持。
> - `skiko-awt-runtime-macos-arm64`同时带arm64和x64两个dylib，有18MB。
> - `jna-jpms`已经自带`linux-x86-64`和`darwin-aarch64`的原生库，不用处理。
> - Windows现在没有带`javacpp-windows-x86_64.jar`也能正常运行FFmpeg，Linux和mac按同样方式处理，不额外引入javacpp原生包。
>
> 最低系统版本（解析原生库得到）：
>
> | 库 | Linux | macOS |
> |---|---|---|
> | FFmpeg | **glibc 2.35** | **macOS 15.0** |
> | Skiko | glibc 2.17 | macOS 11.0 |
> | sqlite | 无要求 | macOS 11.0 |
>
> FFmpeg决定了最低系统版本，而且不能当成可选功能：整合包处理时的音频转码，以及MC里的声音解码都要用到它。见1.1节的系统要求。

### 6.2子模块改动

1. **`compose.desktop.currentOs`**：在`ui`、`player-model`、`code-editor-ui`、`theme`、`webview2`中改为`compose.desktop.<composeTarget>`。
2. **LWJGL natives**：
   - 在`ui`、`bg-renderer`、`player-model`、`render-core`中改用`lwjglNatives`。
   - mac也照样打包LWJGL natives，因为jar在classpath上不会被加载，只是多几MB。这样避免为mac单独裁剪依赖树。
3. **zstd-jni**：`ui/build.gradle.kts`里的全局替换改用`zstdClassifier`，并更新那条"只发布Windows"的注释。
4. **sqlite原生库移出模块jar**：
   - `rdi-database.jar`和`rdi-mod-catalog.jar`不再打包`sqlitejdbc.dll`，这样它们在三个平台上内容一致。
   - 新模块`client/ui/sqlite-natives`（M0已完成）：它的jar只包含`org/sqlite/native/<sqliteNativePath>`，命名为`rdi-sqlite-natives-<平台ID>.jar`。sqlite-jdbc按classpath资源路径查找原生库，换成独立jar不影响加载，`SqliteNativesJarTest`已验证。
   - `database`和`mod-catalog`都改为`runtimeOnly(project(":sqlite-natives"))`；`mod-catalog-tools`通过`mod-catalog`间接拿到，不用改。
5. **FFmpeg**：`mediaproc/build.gradle.kts`的`runtimeOnly`改用`ffmpegClassifier`。`extractTestFfmpegNatives`和`nativePreloadTest`保持Windows（测试只在Windows开发机上跑）。
6. **`webview2`**：目前已经注释掉，不处理；如果以后重新启用，非Windows平台要排除它。
7. **可复现jar**：Gradle 9默认就不保留时间戳、按固定顺序打包，不用额外配置。M0已验证：用`--rerun-tasks`重新构建全部76个任务，190个文件的sha1和`release.zip`都完全一致。第三方jar来自Maven缓存，本来就一致。

### 6.3发布任务

1. **按平台暂存**：`desktopInstallLibs`的输出目录加上平台：`build/install/ui/<平台ID>/lib`。不传`-P`时就是`windows-x64`，开发时`hotRun`等任务不受影响。
2. **合并出包**：`packageUiRelease`改为聚合任务：
   1. 用三个`GradleBuild`子任务，分别以`-Prdi.targetPlatform=<平台ID>`执行`desktopInstallLibs`；
   2. 检查三个`lib/`里的`rdi-ui.jar`的`Implementation-Version`都等于`appVersion`；
   3. 计算每个平台的`文件名→sha1`，**同名文件sha1不同就失败**，并在错误里列出文件名（通常说明某个jar不可复现，或者原生库还打在模块jar里）；
   4. 用JDK自带的`java.util.zip`以`STORED`方式（等同现在的`-mx=0`）把所有文件的并集写进`build/ui-releases/{version}/release.zip`，不再依赖`7z.exe`；
   5. 在同一目录写`windows-x64.json`、`linux-x64.json`、`macos-arm64.json`；
   6. `validateUiReleaseArchive`改为：ZIP条目等于三份manifest的并集，每份manifest的sha1与ZIP内容一致；
   7. 全部通过后才写`latest.txt`。
3. **复制到服务端**：`registerCopyTask`把`{version}/`整个目录复制到`client-libs/ui/{version}/`，完成后再复制`latest.txt`；`出core2-local`和`出core2-release`的名字和用法不变，只是每次都会出三个平台。
4. 新增`出rdi-sh-local`和`出rdi-sh-release`：检查LF换行后，把`client/ui/nix/rdi.sh`复制到`client-libs/scripts/`。sha1由服务端在请求时计算，不另存文件。`client/ui/nix/.gitattributes`固定`*.sh`为LF。
5. 新增`makeNixShipPack`：为每个非Windows平台生成`build/nix-ship/rdi-<平台ID>.tar.gz`，里面是`rdi/rdi.sh`（`0755`）、`rdi/使用说明.txt`和`rdi/lib/`（依赖`packageUiRelease`），解压后得到一个干净的`rdi`目录。
6. 打包时Linux的`lib/`由嵌套构建`desktopInstallLibs-linux-x64`产出；它和外层构建共用build目录，所以排在外层`desktopInstallLibs`之后、逐个执行（并行会争用编译产物）。
6. Windows的`makeShipPackZst`和`出updater-*`不动（`makeShipPackZst`读的是`Documents/rdi5ship`，不受影响）。

**交叉构建**：以上全是Maven classifier切换和纯JVM编译，在Windows开发机上就能产出三个平台的`lib/`，不需要Mac或Linux构建机。代价是每次发布要多跑两次`desktopInstallLibs`（编译结果有缓存，主要是依赖解析和复制）。

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
   - Linux不用改：`BackgroundRenderSession`和`PlayerModelRenderSession`本来就在渲染线程里初始化，失败时通过`failure`状态降级（背景换静态图，玩家模型显示失败提示），不会崩溃（M1确认）。
   - `comp/PlayerModel.kt`（M2）：`available == false`时显示头像，共4个调用点：`MenuScreen`、`PlayerInfoScreen`、`SkinPreviewScreen`、`PlayerModel`自身。只改组件内部，调用方签名不变。
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
7. **窗口透明**（M1已实现）：Linux在有合成器时用透明窗口，没有时用不透明窗口，避免黑底；`-Drdi.window.transparent`仍然优先。
   - 不能用AWT的`isWindowTranslucencySupported`判断：JDK在X11上只检查ARGB visual，没有合成器时也返回`true`（已查JBR21源码）。
   - `LinuxCompositor`：设置了`WAYLAND_DISPLAY`就认为有合成器；否则用JNA查X11的`_NET_WM_CM_S<屏幕号>`选择区有没有占有者（EWMH约定）。检测失败按没有处理并记日志。
   - 系统标题栏和透明解绑：`WindowChrome`自己画标题栏和缩放边框，只有Windows的不透明窗口保留系统标题栏，Linux和mac即使不透明也不显示系统标题栏。
   - Ubuntu（GNOME）上透明窗口已验证正常。
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
| 环境变量`RDI_DEBUG_URL` | 改`--debug`连接的服务器（测试用）：更新走这个地址；客户端通过`-Drdi.server.url`拿到主机名，API和游戏代理都连这台机器，并加`-Drdi.noHttps=true`走HTTP（调试证书只覆盖127.0.0.1）。没加`--debug`时打印警告 | 无 |
| `--app-logs` | 前台运行并输出日志 | `--app-logs` |
| `--select-java` | 忽略`.rdi-java`重新选择JDK | Shift菜单"重新手动选择Java25" |
| `--solid-window` | 传`-Drdi.window.transparent=false` | Shift菜单"实心窗口" |
| `--jvmArg=<参数>` | 追加JVM参数（可重复） | `--jvmArg=` |

不提供"卸载"：删除整个目录即可，说明文件里写明。

### 8.3流程

1. `cd "$(dirname "$0")"`，取绝对路径作为`ROOT`。
2. **平台识别**：
   - 由`uname -s`和`uname -m`得到平台ID：`Linux/x86_64`→`linux-x64`，`Darwin/arm64`→`macos-arm64`；
   - `Darwin/x86_64`时检查`sysctl -n sysctl.proc_translated`：为1说明终端本身跑在Rosetta下，仍按`macos-arm64`处理；否则提示"不支持Intel芯片的Mac"并退出；
   - 其他组合提示"不支持的系统"并退出。
   - **系统版本检查**：mac用`sw_vers -productVersion`，主版本号小于15时提示"需要macOS 15或更高版本，请先升级系统"并退出。Linux用`getconf GNU_LIBC_VERSION`，glibc低于2.35时提示"系统版本过旧，需要Ubuntu 22.04、Debian 12或更新的发行版"并退出；命令失败（非glibc系统）时提示不支持。
3. **加锁**：`mkdir .rdi-update.lock`，失败则提示"另一个启动程序正在更新"后退出；用`trap`在`EXIT INT TERM`时删除锁。锁目录里写入PID，下次加锁失败时如果该PID已经不存在，视为陈旧锁并清理。
4. **更新**（除非`--no-update`）：
   1. 选API：非debug时请求`/server-entry?format=text&myIp=`（`myIp`取自`https://ip.3322.net/`，5秒超时，失败就留空）。返回非空就把加速API放在前面，官方`https://rdi.calebxzhou.cn:65331`作为备用。与Windows的`unique_urls`语义一致。
   2. 依次尝试每个API：`GET /update/ui/p/$PLATFORM/ver?format=text`，校验版本号符合`^[A-Za-z0-9][A-Za-z0-9._-]*$`；再请求`libs/$VER?format=text`，校验每行是40位十六进制sha1加安全文件名（不含`/ \ :`，不是`.`/`..`），并且包含`rdi-ui.jar`。全部API失败时打印警告，用本地版本启动。
   3. **判断是否最新**：把本地`lib/`每个文件的`<sha1> <文件名>`排序后与manifest逐字节比较，一致就跳过。不解析jar里的`Implementation-Version`，也不写`.version`文件。
   4. **暂存**：创建`lib.updating-<时间戳>-<PID>/`。对manifest里每个文件：本地`lib/`里同名文件sha1匹配就`cp`过去，否则从`lib/$VER/$NAME`下载，按API顺序失败重试，下载后校验sha1。
   5. **切换**：`mv lib lib.previous-<id>` → `mv lib.updating-<id> lib` → 删除`lib.previous-<id>`。第二步失败时把`lib.previous-<id>`移回来。
   6. **残留清理**：启动时删除旧的`lib.updating-*`；如果`lib/`不存在但有`lib.previous-*`，先恢复最新的那个。
   7. 下载进度：`curl`用`--progress-bar`，`wget`用`--show-progress`；每个文件前打印`正在更新<文件名>`，文案与Windows版对齐。
5. **校验`lib/`**：没有任何`.jar`时提示"缺少UI库文件，请检查网络后重试"并退出（首次安装时网络不通就会走到这里）。
6. **查找JDK25**（见8.4）。
7. **启动**：

   ```sh
   exec "$JAVA" -Dfile.encoding=UTF-8 $用户JVM参数 \
     -Drdi.debug=$DEBUG [-Drdi.noUpdate=true] [-Drdi.window.transparent=false] \
     -Drdi.updater.islogmode=$APP_LOGS -Drdi.server.url=$SERVER_URL \
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
- `javac -version`输出以`javac 25`开头；
- `os.arch`与平台一致：`linux-x64`要求`amd64`，`macos-arm64`要求`aarch64`。在Apple Silicon上装了x64版JDK25时，跳过该候选并记下来；最后都没找到合格的，就提示"找到的JDK25是x64版，请安装arm64版（Apple Silicon）"。

`.rdi-java`里记住的路径每次启动也按上面的条件重新检查，不合格就丢弃。找到后写入`.rdi-java`。找不到时打印：

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
| `LibraryOsArchTest.kt` | `common/model` | 平台ID映射（`MAC_X64`为`null`）、`isMac` |
| `SqliteNativesJarTest.kt` | `client/ui/database` | 只把`rdi-sqlite-natives-<平台ID>.jar`放在classpath上时，sqlite-jdbc能加载原生库并打开内存数据库（在Windows开发机上测`windows-x64`） |
| `packageUiRelease`合并自检 | Gradle | 同名文件sha1冲突时失败；ZIP条目等于三份manifest的并集；每份manifest只含本平台的原生库 |
| `UpdateServicePlatformTest.kt` | `server/master` | 5.3所列 |
| `MediaProcNativeRuntimeTest.kt`（扩展） | `mediaproc` | 用伪造的jar覆盖三个平台的前缀、完整性检查文件名、`ffmpeg`可执行位 |
| `MediaProcRuntimeClasspathTest.kt`（扩展） | `mediaproc` | 各平台的classifier匹配 |
| `MinecraftLauncherTest.kt`（扩展） | `mclaunch` | `ffmpeg`/`ffmpeg.exe`按平台选择 |
| `JdkPathValidationTest.kt` | `client/ui` | 用临时目录伪造`bin/java`、`bin/javac`、mac的`Contents/Home`结构；`java -version`的执行通过注入函数替换 |
| `DisplayModesTest.kt` | `client/ui` | 格式化函数（与AWT调用分离后可测） |
| `WindowTransparencyTest`（扩展现有） | `client/ui` | Linux默认不透明，JVM属性优先 |
| `packageUiRelease`平台自检 | Gradle | 每个平台的manifest包含对应的Skiko、LWJGL、sqlite、FFmpeg、zstd原生库，并且**不包含**其他平台的 |
| `shellcheck` | `client/ui/nix` | 8.7 |

平台判断全部抽成"传入`LibraryOsArch`"的纯函数，测试在Windows开发机上就能覆盖所有平台分支，不需要在测试里改`os.name`。

## 10.阶段G：手动测试矩阵

| 场景 | windows-x64 | linux-x64 | macos-arm64 |
|---|---|---|---|
| 旧版`start.exe`从新发布格式更新并启动 | ☐ | — | — |
| 首次运行：空目录 → 下载`lib/` → 启动 | — | ☐ | ☐ |
| 未装JDK25：提示文案正确 | — | ☐ | ☐ |
| 装了JDK21和JDK25：选中25 | — | ☐ | ☐ |
| 只装了x64版JDK25：提示安装arm64版 | — | — | ☐ |
| 系统版本过低（glibc<2.35、macOS<15）：提示升级 | — | ☐ | ☐ |
| Rosetta终端里运行`rdi.sh`：仍识别为`macos-arm64` | — | — | ☐ |
| 再次启动：无更新时快速跳过 | — | ☐ | ☐ |
| 发新版本后：增量更新 | — | ☐ | ☐ |
| 更新中途断网：用本地版本启动，`lib/`完整 | — | ☐ | ☐ |
| 更新中途`Ctrl+C`：下次启动能恢复 | — | ☐ | ☐ |
| 脚本自更新 | — | ☐ | ☐ |
| 登录、主菜单（mac为静态背景和2D皮肤） | ☐ | ☐ | ☐ |
| Linux：背景渲染正常；GL失败时降级 | — | ☐ | — |
| 目录选择器、打开目录、打开链接 | ☐ | ☐ | ☐ |
| 下载整合包，启动1.20.1 Forge进房间 | ☐ | ☐ | ☐ |
| 启动1.21.1 NeoForge进房间 | ☐ | ☐ | ☐ |
| MC早期加载窗口显示正常（mac重点） | ☐ | ☐ | ☐ |
| AVIF/音频等FFmpeg相关功能 | ☐ | ☐ | ☐ |
| 本地数据库（sqlite）读写正常 | ☐ | ☐ | ☐ |

Windows列用于回归：确认`DisplayModes`、sqlite原生库拆分、可复现jar等公共改动没有破坏现有功能。

**测试环境**：

- linux-x64：可以先在WSL2+WSLg里做冒烟测试（需要在WSL里装Linux版JDK25，运行打好的`lib/`，不在WSL里跑Gradle），再找一台真Linux桌面（建议Ubuntu 24.04 GNOME Wayland，加一个X11桌面）复测。
- macos-arm64：必须有一台Apple Silicon真机。

## 11.风险与后续项

| 风险 | 影响 | 缓解 |
|---|---|---|
| MC进程在mac上需要`-XstartOnFirstThread`，早期加载窗口（GLFW）在mac上能否正常工作未验证 | mac上可能进不了游戏 | 阶段G优先测；必要时mac上关闭RDI早期画面，走Forge/NeoForge自带的流程 |
| 某个RDI jar不可复现（例如构建时写入时间或路径） | 合并出包时同名sha1冲突，发布失败 | Gradle 9默认可复现；冲突时错误信息列出文件名，逐个修 |
| 玩家在Apple Silicon上装了x64版JDK | 加载x64原生库失败 | 8.4检查`os.arch`并提示 |
| Linux最小化安装缺少`libpulse`、`libasound`、`libxcb`；`FfmpegNativePreloader`把`avdevice`当成必须加载的库 | 加载FFmpeg失败，整合包音频转码和游戏声音都受影响 | 阶段G在干净的Ubuntu桌面上验证；如果确实缺库，在说明文件里列出要装的包，或者评估能否把`avdevice`改成可选 |
| bytedeco以后升级时可能再次提高最低系统版本 | mac或Linux玩家突然无法使用 | 升级FFmpeg时重新解析原生库，确认最低系统版本，并同步修改`rdi.sh`的检查 |
| Linux桌面差异（Wayland、无合成器、缺GTK库） | 窗口、对话框、`Desktop` API异常 | 默认不透明窗口；`JFileChooser`；`xdg-open`回退 |
| Apple Silicon上加载未签名的dylib | 原生库加载失败 | Temurin的JDK允许加载第三方库；Skiko、sqlite、bytedeco的arm64库自带签名或ad-hoc签名；阶段G验证 |
| 只有shell，没有图形化提示 | 新手不熟悉终端 | 说明文件写清楚一行命令；以后可以加`.desktop`文件和mac的`.command`包装 |

后续可选：

- mac/Linux早期画面视频（放开`RdiVideoTexture`的平台限制，验证`ffmpeg`管道）；
- mac原生标题栏按钮（`WindowChrome`）；
- Linux arm64、Windows arm64、Intel Mac（加平台ID和一份manifest即可，发布格式不用改）；
- 旧版本ZIP的清理策略。

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

0. **M0 Windows发布格式迁移**（2026-10-16完成，旧版`start.exe`端到端验证通过）：C里的sqlite拆分、可复现jar、合并出包（先只出`windows-x64`一份manifest），加上服务端改读`client-libs/ui/`。上线顺序：先把新格式版本复制到`client-libs/ui/`，再部署新服务端，然后用旧版`start.exe`验证能正常更新。这一步对玩家无感，可以先单独上线。
1. **M1 Linux可玩**：A+B+C+D（去掉mac专项）+E，在WSLg和真Linux上跑通。（2026-10-16代码完成：单元测试通过；WSL里用假服务端验证了`rdi.sh`的首次安装、增量更新、下载失败保留旧库、中断恢复、更新锁、脚本自更新，并在WSLg里启动了客户端。WSLg没有可用的GL上下文，背景渲染按设计降级为静态图。待真Linux桌面手动测试。）
2. **M2 macOS可玩**：D里的mac专项（渲染开关、JDK路径和架构检查、quarantine），在Apple Silicon真机上跑通。
3. **M3 发布**：`makeNixShipPack`、说明文件、服务端上线`{version}.*.json`和`scripts/`。

## 13.实施前待确认

1. ~~Windows的`desktopInstallLibs`和`packageUiRelease`输出路径~~：已定，`desktopInstallLibs`按平台分目录；发布到`client-libs/ui/{version}/`，manifest以平台ID命名。
2. ~~`PlayerModel`在mac上的替代显示~~：已定（2026-10-16），只显示头像。
3. 服务端是否同意新增`/update/ui/p/*`、`/update/script/*`和`server-entry?format=text`这几个接口（5.1）。
4. ~~发布包下载渠道~~：已定，由开发者手动发给玩家；M0/M1由开发者手动上线。
