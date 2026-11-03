# 旧文件兼容样本

`LegacyMediaSampleTest`用这些文件确认：以后换FFmpeg版本或改解码代码，玩家已上传的旧文件仍能读出相同的结果。

**不要重新生成这些文件。** 新版本的输出和它们对不上，就说明行为变了，应该先查原因。

## 生成方式

- 日期：2026-11-03
- 平台：Windows x64
- FFmpeg：`org.bytedeco:ffmpeg:8.0.1-1.5.13`，分类名`windows-x86_64-gpl`，经JavaCV 1.5.13
- 内容全部由代码合成，不含Minecraft或整合包素材。

### 图片

| 文件 | 内容 |
|---|---|
| `alpha-gradient-high.avif` | 64×64的ARGB图经`AvifCodec.encodePng`编码，质量`HIGH` |
| `alpha-gradient-low.avif` | 同一张图，质量`LOW` |
| `*.rgba` | 对应AVIF用`AvifCodec.decode`解码后的RGBA字节 |

每个像素的取值，`x`、`y`从0到63：

```text
alpha = x * 255 / 63
red   = y * 255 / 63
green = 255 - red
blue  = (x + y) * 255 / 126
```

### 音频

音频都是幅度0.4的正弦波，每个声道的频率不同，这样声道接错时对比会失败。

| 文件 | 内容 |
|---|---|
| `dual-tone.ogg` | 48kHz双声道0.5秒，440Hz、660Hz。先用libopus（256kbps）写成Ogg，再经`OggTranscoder.transcodeOgg`转码，和玩家上传的文件相同 |
| `mono-tone.ogg` | 48kHz单声道0.25秒，440Hz，libopus 96kbps |
| `surround-tone.ogg` | 48kHz六声道0.25秒，依次440、660、880、110、550、770Hz，libopus 256kbps |
| `vorbis-tone.ogg` | 44.1kHz双声道0.25秒，440Hz、660Hz，libvorbis 128kbps |
| `silence-11s.ogg` | 24kHz双声道11秒静音，libopus 64kbps，用来测时长上限 |
| `<名字>.pcm` | 对应Ogg用`FfmpegPcmDecoder`解码的结果 |
| `<名字>.transcoded.pcm` | 对应Ogg经`OggTranscoder.transcodeOgg`转码后，再用`FfmpegPcmDecoder`解码的结果。`mono-vorbis`的输入是`audio/mono-vorbis-44100.ogg.b64` |

PCM都是48kHz、16位小端。除`mono-tone.pcm`是单声道外，其余都是双声道。

## 允许的误差

x86和ARM的SIMD结果可能有细微差别，所以不要求完全一致。

| 对比 | 允许范围 |
|---|---|
| AVIF解码 | 每个通道最多差2/255 |
| Opus解码 | RMS误差最多为满幅的1%，长度必须完全相同 |
| 转码后的结果 | 长度最多差960帧（48kHz下的一个Opus帧），每个声道音量最多差5%，波形相关度至少0.95 |

转码是有损编码，换一种实现后字节和波形细节都可能变，所以转码只比玩家能听出来的部分。
