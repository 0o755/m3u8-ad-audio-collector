# Collector API Contract

本文定义采集器 UI 与 Probe v1 运行时之间的稳定边界。实现只能依赖
`io.github.0o755:ad-audio-probe` 的公开 `io.github.fongmi.adaudio.probe` 包；禁止引用
`internal`、反射访问内部类、复制 matcher，App 源码和资源也不得出现 Media3/ExoPlayer 类型。

## 支持范围

- 支持普通有限时长 HLS/M3U8 与 MP4 点播、HTTP(S) 请求头。
- 不支持直播、DRM、DASH、RTSP；收到这类输入必须返回结构化错误并保持 fail-open。
- `open` 使用调用时的 URL、起点与自动跳过模式快照，异步完成后从
  `max(0, startPositionMs - 5000)` 播放。
- 所有公开操作都不得在调用线程执行网络、解码或大文件 JSON I/O。

## CollectorGateway

UI/ViewModel 只面向 `CollectorGateway`。每次 `open` 或切源都会产生新的 `sessionId`；
时间轴重建递增 `generation`。每个异步命令也携带当时的代际，回调只有同时匹配当前
session 和 generation 才能进入 UI。

```java
interface CollectorGateway extends AutoCloseable {
    void setListener(Listener listener);
    Operation open(OpenRequest request);
    Operation seek(long positionMs);
    Operation play();
    Operation pause();
    Operation attachSurface(Surface surface);
    Operation clearSurface(Surface surface, Runnable onCleared);
    Operation startCapture(CaptureRange range);
    Operation stopCapture();
    Operation testRule(String ruleId);
    Operation saveRule(RuleDocument document);
    Operation merge(RuleDocument document);
    Operation loadRules();
    Operation scanCandidates();
    Operation scanCandidates(OpenRequest request);
    Operation skipPendingMatch();
    void close();
}
```

`Operation` 返回命令提交时的 `sessionId` 与 `generation`。`open(url, headers)` 是
`OpenRequest` 的简写语义；请求对象还必须固定 `startPositionMs` 与 `automaticSkip`。

## 请求与回调

- `OpenRequest`：`url`、只读 `headers`、`startPositionMs`、`automaticSkip`。
- `CaptureRange`：广告起点、广告总时长、锚点偏移与锚点时长；锚点必须完整位于广告内。
- `Snapshot`：播放器位置/总时长、加载或工作流状态、规则数量与状态文字。
- `AutomaticCaptureProgress`：扫描/采集阶段、当前候选序号、指纹百分比和候选范围；
  UI 用该范围回填开始/结束，Gateway 同步控制可见播放器分段快进候选画面。
- `Match`：ruleId、广告起止位置、建议跳转位置、是否自动跳过。
- `Failure`：稳定错误码、可重试标志和安全诊断文字。

每个 `Listener` 回调都显式携带 `sessionId` 与 `generation`。`close()` 返回后不得再回调。
监听器由网关切到 Android 主线程；监听器实现不得执行阻塞 I/O。

## 线性化规则

1. `open`、`stopCapture`、切源和 `close` 在网关的单线程控制执行器中排队。
2. 新 `open` 先废弃旧 session，再停止旧探测/播放，最后创建新 session。
3. 手动 seek、播放器时间轴 discontinuity 会递增 generation，并通知 Probe 清除旧候选；
   Collector session 仅在 open/切源时更新。
4. 普通 buffering 不改变 session/generation，也不清除匹配候选。
5. PCM 必须携带解码器真实 PTS；禁止用累计样本数伪造媒体时间轴。
6. 跳转在排队前和真正调用适配器前各校验一次 session/generation；失效请求直接丢弃。
7. `close` 幂等，释放播放器、Probe、执行器和挂起回调。
8. `Surface` 由 UI 持有，必须等待公开播放器的清除完成回调后才能释放。

## Probe Rules v1

文档只接受以下根字段：`format`、`schemaVersion`、`revision`、`algorithm`、`rules`。
固定值分别为 `ad-audio-probe-rules`、`1`、正整数 revision、
`spectral-sequence-v1`。每条规则只接受：

- `id`
- `durationMs`
- `anchorOffsetMs`
- `anchorDurationMs`
- `fingerprints`
- 可选 `test = { url, adStartMs }`

规则存在于数组中即启用。禁止 `enabled`、待验证状态、根级 `testUrls` 或
`testPositionsMs`。列表点击只回填 `test.adStartMs` 和 `durationMs` 推导出的结束位置，
不改 URL、不播放、不改变参与匹配的规则集合。

## 保存与合并

- 文件名为 `RULES.JSON`，上限 4 MiB，严格 UTF-8。
- JSON 解析、校验、序列化与文件 I/O 全部在后台执行器。
- 保存前完整校验 rules-v1；写入同目录临时文件并 fsync，再原子替换主文件。
- 同 ID 的导入规则覆盖本地规则，不同 ID 追加；输出 revision 为
  `max(local.revision, incoming.revision) + 1`，规则顺序稳定。
- 自动或手动采集草稿按锚点配置和四相位完整指纹去重；重复扫描更新稳定 ID 的草稿，
  与已保存检测内容完全相同时不新增待保存项。
- 导入包含任何旧 schema 或未知字段时整份拒绝，绝不静默迁移。

## Probe 公开 API 接线

- `ProbePlayer` 负责可见播放、Surface、时间轴和结构化播放错误。
- `AdAudioProbe` 负责远端/本地 rules-v1、检测状态和 `SkipRequest`。
- `AudioFingerprintCollector` 负责由真实 PTS 生成四相位规则草稿。
- `HlsCandidateScanner` 只分析有限 VOD HLS 清单；候选必须再次 capture 和测试。
- 本地替换严格以 `RuleReplacementResult.requestId` 关联终态；只有 `APPLIED` 后才能调用
  `useRuleForTesting`，保存或合并后恢复 `useAllRules`。

线上 `rules/rules.json` 当前是合法但零规则的 rules-v1。采集器不得伪造命中，也不得把
结构候选当成已验证广告。直播、DRM、DASH、RTSP 和非确定 SSAI 继续明确不支持。
