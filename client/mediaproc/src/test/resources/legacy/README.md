# 旧文件兼容样本

`LegacyMediaSampleTest`用这些文件确认：以后换FFmpeg版本或改解码代码，玩家已上传的旧文件仍能读出相同的结果。

**不要重新生成这些文件。** 新版本的输出和它们对不上，就说明旧文件的解码变了，应该先查原因。

## 生成方式

- 日期：2026-11-03
- 平台：Windows x64
- FFmpeg：`org.bytedeco:ffmpeg:8.0.1-1.5.13`，分类名`windows-x86_64-gpl`
- 内容全部由代码合成，不含Minecraft或整合包素材。

| 文件 | 内容 |
|---|---|
| `alpha-gradient-high.avif` | 64×64的ARGB图经`AvifCodec.encodePng`编码，质量`HIGH` |
| `alpha-gradient-low.avif` | 同一张图，质量`LOW` |
| `*.rgba` | 对应AVIF用`AvifCodec.decode`解码后的RGBA字节 |
| `dual-tone.ogg` | 48kHz双声道0.5秒正弦波，幅度0.4，左声道440Hz、右声道660Hz。先用libopus（256kbps）写成Ogg，再经`OggTranscoder.transcodeOgg`转码 |
| `dual-tone.pcm` | `dual-tone.ogg`用`FfmpegPcmDecoder`解码后的PCM，48kHz、双声道、16位小端 |

图片每个像素的取值，`x`、`y`从0到63：

```text
alpha = x * 255 / 63
red   = y * 255 / 63
green = 255 - red
blue  = (x + y) * 255 / 126
```

## 允许的误差

- AVIF每个通道最多差2/255。
- Opus的RMS误差最多为满幅的1%，长度必须完全相同。

x86和ARM的SIMD结果可能有细微差别，所以不要求完全一致。
