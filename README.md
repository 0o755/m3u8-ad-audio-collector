# M3U8 广告音频采集器（Probe v1）

这是独立设计和实现的 Probe v1 音频指纹采集器。界面集中提供媒体预览、区间编辑、
自动扫描、指纹采集、规则测试和规则合并；媒体、采集、匹配与规则读写全部通过
`ad-audio-probe` 公共 API 完成。规则存在即参与匹配，不维护额外启用或待验证状态。

## 当前状态

- 已完成独立 Android 工程、完整 UI、`CollectorGateway`、ViewModel 和回调代际隔离。
- 已完成 Probe rules-v1 严格编解码、语义校验、稳定合并与 `RULES.JSON` 原子文件层。
- 已基于 Probe `815d2f7` 接入 `ProbePlayer`、远端/本地规则检测、结构化错误和
  `SkipRequest`，并对 Probe session 与采集器 generation 做二次校验。
- 已接入 `AudioFingerprintCollector`、`HlsCandidateScanner`、本地规则替换终态和指定
  单规则测试。自动扫描会逐个采集公开候选、回填起止位置并同步可见播放器，候选本身
  不视为已确认广告。
- 指纹始终完整覆盖 5000ms；HLS 的短 PCM 缺口由 Probe 官方适配器有界处理，采集器不
  读取 PCM，也不实现额外补帧或 matcher。
- 线上规则使用 `https://raw.githubusercontent.com/0o755/m3u8-ad-audio-probe/rules/rules.json`。
  当前文档合同合法但规则数为 0，必须由采集器生成并合并真实规则后才可能命中。

详细边界见 [API_CONTRACT.md](API_CONTRACT.md) 和 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 目录关系

源码联调时保持以下同级目录。本工程通过 composite build 将 Maven 坐标替换为 Probe 的
公开 `:probe` 制品，App 不依赖 Probe 子模块或 `internal` 包。

```text
github/
├── m3u8-ad-audio-probe/
└── m3u8-ad-audio-collector/  # 本项目
```

Probe 发布后仍使用同一行依赖：

```kotlin
implementation("io.github.0o755:ad-audio-probe:0.1.0-SNAPSHOT")
```

`0.1.0-SNAPSHOT` 当前只用于源码复合构建或本地 Maven；尚未创建 `0.1.0` tag，也未声明
Maven Central 已发布。

## 构建要求

- JDK 17
- Android SDK 35
- Android Gradle Plugin 8.13.2
- Gradle 8.14.3

构建本工程：

```bash
cd /z/github/m3u8-ad-audio-collector
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

同级源码存在时，复合构建会把该坐标替换为默认 `:probe` 聚合模块；它传递
`:probe-runtime`、`:probe-player`、`:probe-collector-tools`，运行时带官方
`:probe-media3-1-9`。本项目不提供绕过正式聚合依赖图的诊断开关。

## 规则文件

只接受 Probe rules-v1：

```json
{
  "format": "ad-audio-probe-rules",
  "schemaVersion": 1,
  "revision": 1,
  "algorithm": "spectral-sequence-v1",
  "rules": []
}
```

每条规则可包含 `test = { url, adStartMs }`。文件中存在的规则默认全部启用；不读取或生成
`enabled`、待验证状态、根级 `testUrls`、`testPositionsMs`。本地文件显示名固定为
`Download/m3u8-ad-audio/RULES.JSON`。

## Probe 接入

`gateway/ProbePlayerHost`、`ProbeToolHost` 和 `ProbeCollectorGateway` 只调用公开门面；
Activity 和 ViewModel 不直接 import Probe 播放器实现，更不 import Media3/ExoPlayer：

1. open/seek/close 非阻塞并可线性化；切源和 discontinuity 产生新 generation。
2. 播放时钟和 collector-tools 都使用 Probe 提供的真实媒体 PTS。
3. buffering 不清候选，discontinuity 清候选。
4. 跳转前后都校验 session/generation。
5. 本地规则替换必须等待同 requestId 的 `APPLIED`，再进入单规则测试。

当前官方 adapter 只允许 `User-Agent`、`Accept`、`Accept-Language`、`Cache-Control` 和
`Pragma`。Cookie、Authorization、Referer 或自定义 token 源必须使用能逐跳控制重定向的
公开第三方 adapter，不能误用当前官方实现。

## 当前限制

- 不支持直播、DRM、DASH、RTSP 或每次请求动态改变时间轴的 SSAI。
- 线上 rules-v1 当前是空规则集，尚无可验证的真实命中。
- Collector 尚未完成 API 23/35 真机和 Surface 生命周期仪器矩阵；Probe `815d2f7` 已完成
  API 29 的 AAC-TS、fMP4、MP4、纯音频及无音轨样片验证。
