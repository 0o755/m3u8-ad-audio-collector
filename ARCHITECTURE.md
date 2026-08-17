# Collector Architecture

本文描述全新 Probe v1 采集器的模块边界。项目不兼容旧采集 APK、旧 SDK 或 v3 规则。

## 分层

```text
Android Activity / Views
          |
          v
CollectorViewModel  -- 纯 UI 状态、请求快照、旧回调过滤
          |
          v
CollectorGateway  -- 唯一媒体/采集/测试/规则入口
          |
          +--> ProbeCollectorGateway  -- 会话、规则替换与跳转协调
          +--> ProbePlayerHost        -- 公开 ProbePlayer / Surface
          +--> ProbeToolHost          -- 公开 capture / HLS scanner
          +--> RuleDocumentStore       -- rules-v1 严格解析与原子文件
          +--> MainThreadDispatcher    -- UI 回调串行投递
```

UI 层只渲染状态和提交意图。Activity 不持有播放器、PCM、matcher 或规则解析器；
ViewModel 不依赖 Android View，也不识别 Probe 的内部实现类型。

## 包结构

- `ui`：Activity、时间输入控件、对话框和覆盖提示。
- `presentation`：`CollectorViewModel`、不可变 `CollectorUiState`。
- `gateway`：`CollectorGateway` 合同及 Probe 公共 API 适配实现。
- `rules`：rules-v1 值对象、严格 codec、合并策略与原子存储。

## 会话状态机

```text
IDLE -> OPENING -> READY <-> BUFFERING -> ENDED
          |          |
          |          +-> CAPTURING -> DRAFT_READY -> TESTING -> VERIFIED
          +-------------------------------------------------------> ERROR
任意非 CLOSED 状态 --close--> CLOSED
```

状态转换由网关控制执行器串行提交。网络/解码/JSON 使用后台执行器；网关只通过主线程
dispatcher 发布不可变快照。短暂 buffering 保持候选；显式 seek、切源或时间轴
discontinuity 创建新 generation 并清空候选。

自动扫描确定候选后，Gateway 同时发布结构化采集进度、将宿主播放器定位到候选起点并
按后台采集百分比分段快进，完成时落到候选结束位置。ViewModel 只把候选范围映射为
开始/结束输入和按钮进度，不接触播放器对象；自动采集期间禁止用户拖动进度，避免
手动 seek 使当前扫描代际失效。

## 打开与跳转时序

1. ViewModel 在点击“播放”时快照 URL、开始位置和自动跳过开关。
2. 网关生成新 session/generation，废弃旧回调。
3. Probe 播放适配器异步打开同一媒体，并定位到 `start - 5s`。
4. Probe detector 使用适配器时间轴和真实 PTS 独立分析。
5. 命中时若快照为自动跳过，网关二次校验后控制可见宿主播放器跳到广告结束位置，
   并发布明确完成状态；否则在视频最上层显示“广告中”和“跳过广告”按钮。
6. 用户点击提示按钮时再次校验 session/generation，控制同一可见播放器跳转并关闭提示。

自动跳过模式属于播放请求快照；播放途中勾选框变化只影响下一次播放请求，避免一个会话
内策略漂移。点击“测试规则”是独立请求，会重新快照当时的开关状态；测试定位产生的
Probe 新 session 由会话门闩接管，防止命中被旧 sessionId 校验误丢弃。

## UI 兼容策略

界面复用参考 APK 的尺寸、顺序、文案和交互入口。原 `PlayerView` 改成 190dp
`TextureView`，自持有 `Surface` 只通过 Gateway 交给 `ProbePlayer`；销毁时等待
`clearSurface(..., onCleared)` 完成后才释放。App 不声明 Media3 依赖。

## 规则生命周期

本地唯一真相为公共 Download 目录中的 `RULES.JSON`。启动、从文件管理器返回、保存和
合并后都在后台重载。内存始终持有完整不可变 `RuleDocument`；统计只展示“规则 N”。
保存草稿或合并外部文档后整体原子替换，不维护额外 activation 数据库或 SharedPreferences。

## 故障策略

- 不支持媒体、规则错误和工具错误均显示明确状态，但不伪造成功。
- 匹配冲突、时间轴不可信或 generation 失效时 fail-open，不 seek。
- 文件替换失败保留旧主文件并清理本次临时文件。
- Activity 销毁只调用 ViewModel/gateway `close()`，不按进程名清理共享进程。

## Probe 公共边界

Probe `fb0a83e` 的默认聚合模块提供 runtime、player、collector-tools，并通过
ServiceLoader 使用官方 Media3 1.9.2 adapter。采集器只调用公开门面，不接触 adapter PCM、
matcher、Claim 或任何 `internal` 类型。第三方实现只能通过公开音频/播放 SPI Builder 注入。
