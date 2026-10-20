# FFmpeg瘦身计划（待实施）

状态：方案已讨论（2026-10-19），尚未批准实施。开始前需与用户确认两点：

1. 是否加入`vorbis`解码器（建议加入）。
2. 是否按下面的“方案A”执行，先只做Windows x64并实测大小。

## 现状

- `ffmpeg-8.0.1-1.5.13-windows-x86_64-gpl.jar`压缩34MB，解压98MB。
- `avcodec-62.dll`就有64MB（x264/x265/vpx/svt-av1等全量编解码器），`avformat` 12MB，`avfilter` 9MB，`avutil` 6.4MB，另含不用的`ffmpeg.exe`、`ffprobe.exe`。
- JavaCPP的JNI胶水（`jni*.dll`）约3.4MB，`ffmpeg-8.0.1-1.5.13.jar`（Java API）270KB，`javacpp`/`javacv`共约1MB。

## 实际用到的组件（来自`mediaproc`源码）

| 用途 | 位置 | 需要的FFmpeg组件 |
|---|---|---|
| PNG→AVIF编码（`FfmpegAvifEncoder`） | 启动器 | `libaom-av1`编码器、`avif` muxer、swscale |
| AVIF解码（`FfmpegAvifDecoder`，`AV_CODEC_ID_AV1`） | 启动器+游戏内 | AV1解码器（用`libdav1d`）、`mov` demuxer、swscale |
| OGG→Opus转码（`OggTranscoder`，输出`libopus`） | 启动器 | `ogg` demuxer/muxer、`libopus`编码器、swresample、输入解码器（`vorbis`/`opus`） |
| 游戏内Opus播放（`FfmpegPcmDecoder`） | 游戏内 | `ogg` demuxer、`opus`解码器、swresample |

注意：整合包里原有的OGG大多是Vorbis，转码前要先解码，所以除了AV1和Opus，还需要`vorbis`解码器（约100KB）。

## 方案A（推荐）：只重编FFmpeg本体，保留JavaCPP

1. 编译FFmpeg 8.0.1，使用LGPL配置，第三方库全部静态链接，不额外带DLL：
   ```
   --disable-everything --disable-programs --disable-doc --disable-network --disable-autodetect
   --enable-shared --disable-static
   --enable-libdav1d --enable-libaom --enable-libopus
   --enable-decoder=libdav1d,opus,vorbis --enable-encoder=libaom_av1,libopus
   --enable-demuxer=ogg,mov --enable-muxer=ogg,avif
   --enable-parser=av1,opus,vorbis --enable-protocol=file
   --enable-swscale --enable-swresample
   ```
   - libaom只编编码器（`CONFIG_AV1_DECODER=0`），解码由dav1d负责。确认`FfmpegAvifEncoder`现用的libaom参数（allintra/cpu-used）在该构建下仍可用。
   - avfilter、avdevice仍要编出来（不启用任何组件），因为JavaCPP的JNI胶水要链接它们。
2. 继续使用bytedeco官方的`jni*.dll`和Java API，只替换`av*`/`sw*`动态库。版本号和库文件名必须一致（`avcodec-62`、`avutil-60`、`avformat-62`、`avfilter-11`、`avdevice-62`、`swscale-9`、`swresample-6`）。
   - 打包前对比新旧库的导出符号表（`objdump -p`/`nm -D`/`nm -gU`），确认JNI胶水引用的符号都在。
   - 检查JavaCPP presets的`cppbuild.sh`对FFmpeg打的补丁，评估自编版本是否需要同样的补丁。
3. 打包成自有jar，例如`rdi-ffmpeg-natives-<platform>.jar`，保持内部路径`org/bytedeco/ffmpeg/<classifier>/`不变，这样`FfmpegNativePreloader`、`MediaProcRuntimeClasspath`、`MediaProcGameClasspath`和游戏内加载都不用改。最后把`mediaproc/build.gradle.kts`中的`runtimeOnly("org.bytedeco:ffmpeg:...:$ffmpegClassifier")`换成新jar。还要检查classifier后缀`-gpl`：改为LGPL构建后，要么保留原目录名，要么同步修改`FfmpegNativePlatform`和`uiPlatformNativeMarkers`（`client/ui/build.gradle.kts`）。
4. 三个平台：windows-x64（MSYS2/mingw）、linux-x64（WSL）、macos-arm64（NoMac虚拟机，见nomac-mac-testing记忆）。之后可改用GitHub Actions。

附带好处：去掉x264/x265等GPL组件后，许可从GPL降为LGPL+BSD。

预计大小（每个平台）：avcodec（含静态dav1d、aom编码器、opus）约6–9MB解压，加上avformat/avutil/sw*约1.5MB、jni胶水约3.4MB，jar压缩后约4–6MB（原34MB）。

## 验证

- `mediaproc`测试：`nativePreloadTest`、AVIF编码→解码往返、OGG转码。
- 启动器真实上传一个含大PNG和OGG的整合包。
- 1.20.1 Forge与1.21.1 NeoForge进游戏：AVIF贴图（含Effekseer贴图适配）显示正常，声音播放正常。
- 首次安装和更新：新的natives jar能被manifest正确识别并下载。

## 方案B（暂不推荐）：彻底去掉FFmpeg/JavaCPP

- 音频改用纯Java库：Concentus（Opus编解码）+ JOrbis（Vorbis解码）。
- 图片改用libavif（内含dav1d+aom），通过JNA调用（Minecraft自带JNA）。
- 比方案A再省约4MB（jni胶水+javacpp），但要重写`mediaproc`（约1750行）并重新验证游戏内适配，工作量大得多。
