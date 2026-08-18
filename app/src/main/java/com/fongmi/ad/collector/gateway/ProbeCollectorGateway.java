/* CollectorGateway 的 Probe v1 实现：串行协调播放器、检测、采集和规则文件。 */
package com.fongmi.ad.collector.gateway;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.fongmi.ad.collector.rules.AtomicRuleStore;
import com.fongmi.ad.collector.rules.ProbeRule;
import com.fongmi.ad.collector.rules.RuleDocument;
import com.fongmi.ad.collector.rules.RuleDocumentCodec;
import com.fongmi.ad.collector.rules.RuleDocumentMerger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.fongmi.adaudio.probe.AdAudioProbe;
import io.github.fongmi.adaudio.probe.ProbeError;
import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.ProbeListener;
import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.ProbeState;
import io.github.fongmi.adaudio.probe.ProbeStatus;
import io.github.fongmi.adaudio.probe.RuleReplacementResult;
import io.github.fongmi.adaudio.probe.RuleReplacementState;
import io.github.fongmi.adaudio.probe.SkipRequest;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackDiscontinuityReason;
import io.github.fongmi.adaudio.probe.player.ProbePlayerState;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureProgress;
import io.github.fongmi.adaudio.probe.tools.FingerprintRuleDraft;
import io.github.fongmi.adaudio.probe.tools.HlsAdCandidate;
import io.github.fongmi.adaudio.probe.tools.HlsScanResult;
import io.github.fongmi.adaudio.probe.tools.ProbeToolError;
import io.github.fongmi.adaudio.probe.tools.ProbeToolErrorCode;

public final class ProbeCollectorGateway implements CollectorGateway {
    private static final String RULES_URL =
            "https://raw.githubusercontent.com/0o755/m3u8-ad-audio-probe/rules/rules.json";
    private static final Listener NO_OP = new Listener() {
        @Override public void onSnapshot(Operation operation, Snapshot snapshot) { }
        @Override public void onRulesLoaded(Operation operation, RuleDocument document, String path) { }
        @Override public void onDraftReady(Operation operation, ProbeRule rule) { }
        @Override public void onAutomaticCapture(Operation operation,
                                                  AutomaticCaptureProgress progress) { }
        @Override public void onMatch(Operation operation, Match match) { }
        @Override public void onMatchCleared(Operation operation) { }
        @Override public void onFailure(Operation operation, Failure failure) { }
    };

    private final AtomicRuleStore ruleStore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicLong sessionSequence = new AtomicLong();
    private final AtomicLong generationSequence = new AtomicLong();
    private final AtomicLong draftSequence = new AtomicLong();
    private final ProbePlayerHost player;
    private final ProbeToolHost tools;
    private final AdAudioProbe probe;
    private final ProbeSessionGate probeSessions = new ProbeSessionGate();
    private final AutomaticPlaybackFollower automaticPlaybackFollower =
            new AutomaticPlaybackFollower();

    private volatile Listener listener = NO_OP;
    private volatile Operation mediaOperation = new Operation(0L, 0L);
    private volatile Operation rulesOperation = new Operation(0L, 0L);
    private volatile boolean closed;
    private RuleDocument rules = RuleDocument.empty();
    private final Map<String, ProbeRule> drafts = new LinkedHashMap<>();
    private OpenRequest openRequest;
    private ProbeMedia media;
    private long playerSessionId;
    private long captureSessionId;
    private long scanSessionId;
    private boolean automaticCapture;
    private AutomaticCaptureBatch automaticBatch;
    private Match pendingMatch;
    private String testingRuleId;
    private boolean testingAutomaticSkip;
    private RuleReplacementAction replacementAction;
    private Snapshot.State snapshotState = Snapshot.State.READY;
    private String snapshotMessage = "";
    private boolean automaticWorkflowDone;

    public static ProbeCollectorGateway create(Context context) {
        return new ProbeCollectorGateway(context);
    }

    ProbeCollectorGateway(Context context) {
        Context application = context.getApplicationContext();
        ruleStore = new AtomicRuleStore(application);
        player = new ProbePlayerHost(application, control, new PlayerCallbacks());
        tools = new ProbeToolHost(application, control);
        probe = AdAudioProbe.builder(application, RULES_URL)
                .setPlaybackClock(player::getCurrentPositionMs)
                .setHostExecutor(control)
                .setListener(new ProbeCallbacks())
                .build();
        control.scheduleWithFixedDelay(this::emitPlaybackProgress,
                250L, 250L, TimeUnit.MILLISECONDS);
    }

    @Override public void setListener(Listener listener) {
        this.listener = listener == null ? NO_OP : listener;
    }

    @Override public Operation open(OpenRequest request) {
        Operation operation = newMediaOperation(true);
        executeControl(() -> openLinearized(operation, request));
        return operation;
    }

    @Override public Operation seek(long positionMs) {
        if (positionMs < 0L) throw new IllegalArgumentException("跳转位置不能为负数");
        Operation operation = newMediaOperation(false);
        executeControl(() -> {
            if (!isCurrent(operation) || playerSessionId <= 0L) return;
            automaticWorkflowDone = false;
            clearPendingMatch(operation);
            player.seekTo(positionMs);
        });
        return operation;
    }

    @Override public Operation play() {
        Operation operation = mediaOperation;
        executeControl(() -> {
            if (isCurrent(operation) && playerSessionId > 0L) player.play();
        });
        return operation;
    }

    @Override public Operation pause() {
        Operation operation = mediaOperation;
        executeControl(() -> {
            if (isCurrent(operation) && playerSessionId > 0L) player.pause();
        });
        return operation;
    }

    @Override public Operation attachSurface(Surface surface) {
        if (surface == null) throw new IllegalArgumentException("Surface 不能为空");
        Operation operation = mediaOperation;
        executeControl(() -> {
            if (!closed) player.attachSurface(surface);
        });
        return operation;
    }

    @Override public Operation clearSurface(Surface surface, Runnable onCleared) {
        Operation operation = mediaOperation;
        if (surface == null || closed) {
            postCompletion(onCleared);
            return operation;
        }
        executeControl(() -> player.clearSurface(surface, () -> postCompletion(onCleared)));
        return operation;
    }

    @Override public Operation startCapture(CaptureRange range) {
        Operation operation = mediaOperation;
        executeControl(() -> startManualCapture(operation, range));
        return operation;
    }

    @Override public Operation stopCapture() {
        Operation operation = mediaOperation;
        executeControl(() -> {
            if (!isCurrent(operation)) return;
            cancelCaptureFlow();
            emitSnapshot(operation, Snapshot.State.READY, "已停止指纹采集");
        });
        return operation;
    }

    @Override public Operation testRule(String ruleId, boolean automaticSkip) {
        Operation operation = mediaOperation;
        executeControl(() -> beginRuleTest(operation, ruleId, automaticSkip));
        return operation;
    }

    @Override public Operation saveRule(RuleDocument document) {
        Operation operation = newRulesOperation();
        io.execute(() -> {
            try {
                RuleDocument saved = ruleStore.save(document);
                executeControl(() -> applyStoredRules(operation, saved));
            } catch (RuntimeException | java.io.IOException error) {
                emitRulesFailure(operation, Failure.Code.STORAGE_FAILED, true,
                        "保存 RULES.JSON 失败: " + safeMessage(error));
            }
        });
        return operation;
    }

    @Override public Operation merge(RuleDocument incoming) {
        Operation operation = newRulesOperation();
        io.execute(() -> {
            try {
                RuleDocument merged = RuleDocumentMerger.merge(ruleStore.load(), incoming);
                ruleStore.save(merged);
                executeControl(() -> applyStoredRules(operation, merged));
            } catch (RuntimeException | java.io.IOException error) {
                emitRulesFailure(operation, Failure.Code.RULES_INVALID, false,
                        "合并 rules-v1 失败: " + safeMessage(error));
            }
        });
        return operation;
    }

    @Override public Operation merge(RuleSource source) {
        Operation operation = newRulesOperation();
        io.execute(() -> {
            try (InputStream input = source.open();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new java.io.IOException("无法打开所选规则文件");
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > RuleDocumentCodec.MAX_BYTES) {
                        throw new IllegalArgumentException("规则文件超过 4 MiB");
                    }
                    output.write(buffer, 0, read);
                }
                RuleDocument incoming = RuleDocumentCodec.fromBytes(output.toByteArray());
                RuleDocument merged = RuleDocumentMerger.merge(ruleStore.load(), incoming);
                ruleStore.save(merged);
                executeControl(() -> applyStoredRules(operation, merged));
            } catch (RuntimeException | java.io.IOException error) {
                emitRulesFailure(operation, Failure.Code.RULES_INVALID, false,
                        "合并 rules-v1 失败: " + safeMessage(error));
            }
        });
        return operation;
    }

    @Override public Operation loadRules() {
        Operation operation = newRulesOperation();
        io.execute(() -> {
            try {
                RuleDocument loaded = ruleStore.load();
                executeControl(() -> applyStoredRules(operation, loaded));
            } catch (RuntimeException | java.io.IOException error) {
                emitRulesFailure(operation, Failure.Code.RULES_INVALID, false,
                        "读取 RULES.JSON 失败: " + safeMessage(error));
            }
        });
        return operation;
    }

    @Override public Operation scanCandidates() {
        Operation operation = mediaOperation;
        executeControl(() -> {
            if (!isCurrent(operation) || media == null) {
                emitMediaFailure(operation, Failure.Code.INVALID_REQUEST, false,
                        "请先输入并打开 M3U8 链接");
                return;
            }
            startScan(operation, media);
        });
        return operation;
    }

    @Override public Operation scanCandidates(OpenRequest request) {
        Operation operation = newMediaOperation(true);
        executeControl(() -> {
            ProbeMedia opened = openLinearized(operation, request);
            if (opened != null && isCurrent(operation)) startScan(operation, opened);
        });
        return operation;
    }

    @Override public Operation skipPendingMatch() {
        Operation operation = mediaOperation;
        executeControl(() -> {
            if (!isCurrent(operation) || pendingMatch == null) return;
            long target = pendingMatch.getEndMs();
            pendingMatch = null;
            player.seekTo(target);
            emitSnapshot(operation, Snapshot.State.READY, "已手动跳到广告结束位置");
        });
        return operation;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        mediaOperation = new Operation(sessionSequence.incrementAndGet(),
                generationSequence.incrementAndGet());
        try {
            control.execute(() -> {
                tools.close();
                probe.close();
                player.close();
            });
        } catch (RejectedExecutionException ignored) {
            // 重复关闭或执行器退出时资源已进入回收路径。
        }
        control.shutdown();
        io.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
        listener = NO_OP;
    }

    private ProbeMedia openLinearized(Operation operation, OpenRequest request) {
        if (!isCurrent(operation)) return null;
        automaticWorkflowDone = false;
        cancelToolSessions();
        clearPendingMatch(operation);
        testingRuleId = null;
        testingAutomaticSkip = false;
        player.stop();
        playerSessionId = 0L;
        probe.stop();
        probeSessions.block();
        try {
            ProbeMedia opened = ProbeMedia.builder(request.getUrl())
                    .setHeaders(request.getHeaders())
                    .build();
            openRequest = request;
            media = opened;
            long initialPosition = Math.max(0L, request.getStartPositionMs() - 5_000L);
            emitSnapshot(operation, Snapshot.State.OPENING, "正在加载视频...");
            playerSessionId = player.open(opened, initialPosition);
            replaceProbeRules(operation, rules, ReplacementKind.OPEN_MEDIA, null, opened);
            return opened;
        } catch (IllegalArgumentException error) {
            emitMediaFailure(operation, Failure.Code.INVALID_REQUEST, false, safeMessage(error));
        } catch (IllegalStateException error) {
            emitMediaFailure(operation, Failure.Code.INTERNAL, true,
                    "启动播放或检测失败: " + safeMessage(error));
        }
        return null;
    }

    private void startManualCapture(Operation operation, CaptureRange range) {
        if (!isCurrent(operation) || media == null || openRequest == null) {
            emitMediaFailure(operation, Failure.Code.INVALID_REQUEST, false,
                    "请先打开媒体再提取指纹");
            return;
        }
        cancelCaptureFlow();
        automaticWorkflowDone = false;
        automaticCapture = false;
        String ruleId = "ad-" + System.currentTimeMillis() + "-" + draftSequence.incrementAndGet();
        startCapture(operation, ruleId, range);
    }

    private void startCapture(Operation operation, String ruleId, CaptureRange range) {
        try {
            captureSessionId = tools.capture(media, ruleId, range, new CaptureCallbacks());
            emitSnapshot(operation, automaticCapture
                            ? Snapshot.State.SCANNING : Snapshot.State.CAPTURING,
                    "正在提取广告指纹...");
        } catch (IllegalArgumentException error) {
            handleCaptureFailure(operation, new Failure(Failure.Code.INVALID_REQUEST, false,
                    safeMessage(error)));
        } catch (IllegalStateException error) {
            handleCaptureFailure(operation, new Failure(Failure.Code.INTERNAL, true,
                    "无法启动指纹采集: " + safeMessage(error)));
        }
    }

    private void startScan(Operation operation, ProbeMedia scanMedia) {
        cancelCaptureFlow();
        tools.cancelScan();
        automaticBatch = null;
        automaticWorkflowDone = false;
        try {
            scanSessionId = tools.scan(scanMedia, new ScanCallbacks(operation));
            emitAutomaticCapture(operation, AutomaticCaptureProgress.scanning());
            emitSnapshot(operation, Snapshot.State.SCANNING, "正在分析 M3U8 时间线和分片边界...");
        } catch (IllegalArgumentException error) {
            emitMediaFailure(operation, Failure.Code.INVALID_REQUEST, false, safeMessage(error));
        } catch (IllegalStateException error) {
            emitMediaFailure(operation, Failure.Code.INTERNAL, true,
                    "无法启动候选扫描: " + safeMessage(error));
        }
    }

    private void startNextAutomaticCapture(Operation operation) {
        if (!isCurrent(operation) || automaticBatch == null) return;
        while (!automaticBatch.isComplete() && automaticBatch.currentRange() == null) {
            automaticBatch.reject(new Failure(Failure.Code.INVALID_REQUEST, false,
                    "候选广告时长不足 5 秒，无法生成完整指纹"));
        }
        if (automaticBatch.isComplete()) {
            int accepted = automaticBatch.accepted();
            int added = automaticBatch.added();
            int duplicated = automaticBatch.duplicated();
            int total = automaticBatch.size();
            Failure firstFailure = automaticBatch.firstFailure();
            automaticBatch = null;
            automaticCapture = false;
            captureSessionId = 0L;
            if (accepted == 0) {
                if (firstFailure == null) {
                    emitMediaFailure(operation, Failure.Code.UNSUPPORTED_SOURCE, true,
                            "候选广告均未生成有效指纹，请改用手工截取");
                } else {
                    String detail = firstFailure.getMessage();
                    String message = detail == null || detail.isEmpty()
                            ? "候选广告均未生成有效指纹"
                            : "候选广告均未生成有效指纹: " + detail;
                    emitMediaFailure(operation, firstFailure.getCode(),
                            firstFailure.isRetryable(), message);
                }
            } else {
                automaticWorkflowDone = true;
                emitSnapshot(operation, Snapshot.State.READY,
                        automaticCompletionMessage(accepted, total, added, duplicated));
            }
            return;
        }
        HlsAdCandidate candidate = automaticBatch.current();
        CaptureRange range = automaticBatch.currentRange();
        automaticCapture = true;
        // 宿主从候选起点开始，并随后按指纹进度分段快进到候选结束位置。
        automaticPlaybackFollower.begin(range);
        player.seekTo(range.getAdStartMs());
        player.play();
        emitAutomaticCapture(operation, AutomaticCaptureProgress.capturing(range,
                automaticBatch.currentNumber(), automaticBatch.size(), 0));
        emitSnapshot(operation, Snapshot.State.SCANNING,
                "自动采集 " + automaticBatch.currentNumber() + "/" + automaticBatch.size());
        startCapture(operation, candidate.getId(), range);
    }

    private void beginRuleTest(Operation operation, String ruleId, boolean automaticSkip) {
        if (!isCurrent(operation) || media == null || openRequest == null) {
            emitMediaFailure(operation, Failure.Code.INVALID_REQUEST, false,
                    "请先打开测试媒体");
            return;
        }
        ProbeRule rule = drafts.get(ruleId);
        if (rule == null) rule = rules.find(ruleId);
        if (rule == null) {
            emitMediaFailure(operation, Failure.Code.INVALID_REQUEST, false,
                    "找不到待测试规则: " + ruleId);
            return;
        }
        // 每次测试都是独立演示，不能沿用上一次自动采集或手动命中的状态。
        automaticWorkflowDone = false;
        pendingMatch = null;
        try {
            RuleDocument testDocument = selectRuleTestDocument(rules, drafts, ruleId, rule);
            replaceProbeRules(operation, testDocument, ReplacementKind.TEST_ONE, ruleId,
                    null, automaticSkip);
            emitSnapshot(operation, Snapshot.State.TESTING, "正在载入指定规则...");
        } catch (IllegalArgumentException error) {
            emitMediaFailure(operation, Failure.Code.RULES_INVALID, false,
                    "待测试规则无效: " + safeMessage(error));
        }
    }

    private void applyStoredRules(Operation operation, RuleDocument document) {
        if (!isCurrentRules(operation)) return;
        rules = document;
        for (ProbeRule rule : document.getRules()) drafts.remove(rule.getId());
        emitRules(operation, document);
        replaceProbeRules(operation, document, ReplacementKind.APPLY_ALL, null);
    }

    private void replaceProbeRules(Operation operation, RuleDocument document,
                                   ReplacementKind kind, String ruleId) {
        replaceProbeRules(operation, document, kind, ruleId, null);
    }

    private void replaceProbeRules(Operation operation, RuleDocument document,
                                   ReplacementKind kind, String ruleId,
                                   ProbeMedia mediaToOpen) {
        replaceProbeRules(operation, document, kind, ruleId, mediaToOpen, false);
    }

    private void replaceProbeRules(Operation operation, RuleDocument document,
                                   ReplacementKind kind, String ruleId,
                                   ProbeMedia mediaToOpen, boolean automaticSkip) {
        try {
            long requestId = probe.replaceRulesJson(RuleDocumentCodec.toJson(document));
            replacementAction = new RuleReplacementAction(requestId, operation, kind, ruleId,
                    mediaToOpen, operation == rulesOperation, automaticSkip);
        } catch (IllegalArgumentException error) {
            emitReplacementFailure(operation, kind, Failure.Code.RULES_INVALID, false,
                    "Probe 拒绝规则文档: " + safeMessage(error));
        } catch (IllegalStateException error) {
            emitReplacementFailure(operation, kind, Failure.Code.INTERNAL, true,
                    "无法更新 Probe 规则: " + safeMessage(error));
        }
    }

    private void handleRuleReplacement(RuleReplacementResult result) {
        RuleReplacementAction action = replacementAction;
        if (action == null || action.requestId != result.getRequestId()) return;
        replacementAction = null;
        if (action.kind == ReplacementKind.TEST_ONE && !isRuleActionCurrent(action)) {
            replaceProbeRules(mediaOperation, rules, ReplacementKind.APPLY_ALL, null);
            return;
        }
        if (!isRuleActionCurrent(action)) return;
        if (result.getState() == RuleReplacementState.REJECTED) {
            ProbeError error = result.getError();
            emitRuleActionFailure(action, mapProbeError(error.getCode()),
                    error.isRetryable(), error.getMessage());
            return;
        }
        if (result.getState() == RuleReplacementState.SUPERSEDED) {
            emitRuleActionFailure(action, Failure.Code.RULES_INVALID, true,
                    "规则更新请求已被更新版本覆盖");
            return;
        }
        try {
            probeSessions.replace(result.getSessionId());
            if (action.kind == ReplacementKind.OPEN_MEDIA) {
                testingRuleId = null;
                testingAutomaticSkip = false;
                probe.useAllRules();
                probeSessions.replace(probe.open(action.mediaToOpen));
            } else if (action.kind == ReplacementKind.TEST_ONE) {
                testingRuleId = action.ruleId;
                testingAutomaticSkip = action.automaticSkip;
                probeSessions.replace(probe.useRuleForTesting(action.ruleId));
                ProbeRule rule = drafts.get(action.ruleId);
                if (rule == null) rule = rules.find(action.ruleId);
                long start = rule != null && rule.getTest() != null
                        ? rule.getTest().getAdStartMs() : openRequest.getStartPositionMs();
                player.seekTo(Math.max(0L, start - 5_000L));
                player.play();
                emitSnapshot(mediaOperation, Snapshot.State.TESTING, "正在测试规则 " + action.ruleId);
            } else {
                testingRuleId = null;
                testingAutomaticSkip = false;
                long sessionId = probe.useAllRules();
                if (sessionId == 0L && media != null && playerSessionId > 0L) {
                    sessionId = probe.open(media);
                }
                probeSessions.replace(sessionId);
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            emitRuleActionFailure(action, Failure.Code.RULES_INVALID, false,
                    "切换检测规则失败: " + safeMessage(error));
        }
    }

    private void handleSkip(SkipRequest request) {
        Operation operation = mediaOperation;
        if (!isCurrent(operation) || openRequest == null) return;
        if (!probeSessions.accept(request.getSessionId())) return;
        boolean automatic = resolveAutomaticSkip(testingRuleId != null,
                testingAutomaticSkip, openRequest.isAutomaticSkip());
        Match match = new Match(request.getRuleId(), request.getAdStartPositionMs(),
                request.getAdEndPositionMs(), automatic);
        if (match.isAutomatic()) {
            if (!isCurrent(operation)) return;
            player.seekTo(request.getSeekTargetPositionMs());
            emitSnapshot(operation, Snapshot.State.READY, "已自动跳到广告结束位置");
        } else {
            pendingMatch = match;
            emitSnapshot(operation, Snapshot.State.READY, "检测到广告，等待手动跳过");
        }
        emitMatch(operation, match);
        restoreAllRulesAfterTest(operation);
    }

    private void restoreAllRulesAfterTest(Operation operation) {
        if (testingRuleId == null) return;
        testingRuleId = null;
        testingAutomaticSkip = false;
        replaceProbeRules(operation, rules, ReplacementKind.APPLY_ALL, null);
    }

    private void handleProbeStatus(ProbeStatus status) {
        Operation operation = mediaOperation;
        if (!isCurrent(operation) || openRequest == null || status.getSessionId() <= 0L) return;
        boolean accepted = probeSessions.accept(status.getSessionId());
        if (!accepted) return;
        if (automaticWorkflowDone) return;
        if (snapshotState == Snapshot.State.SCANNING
                || snapshotState == Snapshot.State.CAPTURING
                || snapshotState == Snapshot.State.TESTING) return;
        if (status.getState() == ProbeState.PREPARING) {
            emitSnapshot(operation, Snapshot.State.READY, "广告检测初始化中...");
        } else if (status.getState() == ProbeState.ANALYZING) {
            emitSnapshot(operation, Snapshot.State.READY, "广告检测中");
        } else if (status.getState() == ProbeState.LOOKAHEAD_READY) {
            emitSnapshot(operation, Snapshot.State.READY, "广告检测前视已就绪");
        } else if (status.getState() == ProbeState.ENDED) {
            emitSnapshot(operation, Snapshot.State.READY, "广告检测已完成");
        }
    }

    private void handleProbeError(ProbeError error) {
        Operation operation = mediaOperation;
        if (!isCurrent(operation)) return;
        if (error.getSessionId() > 0L && !probeSessions.accept(error.getSessionId())) return;
        emitMediaFailure(operation, mapProbeError(error.getCode()), error.isRetryable(),
                error.getMessage());
    }

    private void handleCaptureFailure(Operation operation, Failure failure) {
        captureSessionId = 0L;
        if (automaticCapture && automaticBatch != null) {
            finishAutomaticPlayback();
            automaticBatch.reject(failure);
            executeControl(() -> startNextAutomaticCapture(operation));
        } else {
            emitMediaFailure(operation, failure.getCode(), failure.isRetryable(),
                    failure.getMessage());
        }
    }

    private void cancelCaptureFlow() {
        tools.cancelCapture();
        captureSessionId = 0L;
        automaticCapture = false;
        automaticBatch = null;
        automaticPlaybackFollower.clear();
    }

    private void followAutomaticPlayback(int percent) {
        long targetMs = automaticPlaybackFollower.advance(percent);
        if (targetMs >= 0L) player.seekTo(targetMs);
    }

    private void finishAutomaticPlayback() {
        long targetMs = automaticPlaybackFollower.finish();
        if (targetMs >= 0L) player.seekTo(targetMs);
    }

    private String automaticCompletionMessage(int accepted, int total, int added,
                                              int duplicated) {
        String prefix = "自动采集完成，已处理 " + accepted + "/" + total + " 个候选，";
        if (added == 0 && duplicated > 0) {
            return prefix + "发现重复规则，可继续测试，保存时会提示重复";
        }
        if (duplicated > 0) {
            return prefix + "新增 " + added + " 条、重复 " + duplicated
                    + " 条，均可继续测试";
        }
        return prefix + "规则草稿 " + drafts.size() + " 条";
    }

    private void cancelToolSessions() {
        cancelCaptureFlow();
        tools.cancelScan();
        scanSessionId = 0L;
    }

    private Operation newMediaOperation(boolean newSession) {
        long session = newSession ? sessionSequence.incrementAndGet() : mediaOperation.getSessionId();
        Operation operation = new Operation(session, generationSequence.incrementAndGet());
        mediaOperation = operation;
        return operation;
    }

    private Operation newRulesOperation() {
        Operation operation = new Operation(mediaOperation.getSessionId(),
                generationSequence.incrementAndGet());
        rulesOperation = operation;
        return operation;
    }

    private boolean isCurrent(Operation operation) {
        Operation current = mediaOperation;
        return !closed && operation.getSessionId() == current.getSessionId()
                && operation.getGeneration() == current.getGeneration();
    }

    private boolean isCurrentRules(Operation operation) {
        Operation current = rulesOperation;
        return !closed && operation.getSessionId() == current.getSessionId()
                && operation.getGeneration() == current.getGeneration();
    }

    private void emitSnapshot(Operation operation, Snapshot.State state, String message) {
        if (!isCurrent(operation)) return;
        snapshotState = state;
        snapshotMessage = message;
        emitCurrent(operation, () -> listener.onSnapshot(operation,
                new Snapshot(state, player.getCurrentPositionMs(), player.getDurationMs(),
                        rules.getRules().size(), player.isPlaying(), message)));
    }

    private void emitPlaybackProgress() {
        Operation operation = mediaOperation;
        if (playerSessionId > 0L && isCurrent(operation)) {
            emitSnapshot(operation, snapshotState, snapshotMessage);
        }
    }

    private void emitRules(Operation operation, RuleDocument document) {
        mainHandler.post(() -> {
            if (isCurrentRules(operation)) {
                listener.onRulesLoaded(operation, document,
                        ruleStore.getTarget().getAbsolutePath());
            }
        });
    }

    private void emitDraft(Operation operation, ProbeRule rule) {
        emitCurrent(operation, () -> listener.onDraftReady(operation, rule));
    }

    private void emitAutomaticCapture(Operation operation, AutomaticCaptureProgress progress) {
        emitCurrent(operation, () -> listener.onAutomaticCapture(operation, progress));
    }

    private void emitMatch(Operation operation, Match match) {
        emitCurrent(operation, () -> listener.onMatch(operation, match));
    }

    private void clearPendingMatch(Operation operation) {
        if (pendingMatch == null) return;
        pendingMatch = null;
        emitCurrent(operation, () -> listener.onMatchCleared(operation));
    }

    private void emitMediaFailure(Operation operation, Failure.Code code,
                                  boolean retryable, String message) {
        emitSnapshot(operation, playerSessionId > 0L
                ? Snapshot.State.READY : Snapshot.State.ERROR, message);
        emitCurrent(operation, () -> listener.onFailure(operation,
                new Failure(code, retryable, message)));
    }

    private void emitRulesFailure(Operation operation, Failure.Code code,
                                  boolean retryable, String message) {
        mainHandler.post(() -> {
            if (isCurrentRules(operation)) {
                listener.onFailure(operation, new Failure(code, retryable, message));
            }
        });
    }

    private void emitCurrent(Operation operation, Runnable callback) {
        mainHandler.post(() -> {
            if (isCurrent(operation)) callback.run();
        });
    }

    private void emitReplacementFailure(Operation operation, ReplacementKind kind,
                                        Failure.Code code, boolean retryable, String message) {
        boolean rulesScoped = kind == ReplacementKind.APPLY_ALL && operation == rulesOperation;
        RuleReplacementAction action = new RuleReplacementAction(0L, operation, kind, null,
                null, rulesScoped, false);
        emitRuleActionFailure(action, code, retryable, message);
    }

    private void emitRuleActionFailure(RuleReplacementAction action, Failure.Code code,
                                       boolean retryable, String message) {
        mainHandler.post(() -> {
            if (isRuleActionCurrent(action)) {
                listener.onFailure(action.operation, new Failure(code, retryable, message));
            }
        });
    }

    private boolean isRuleActionCurrent(RuleReplacementAction action) {
        if (action.kind == ReplacementKind.APPLY_ALL) {
            return action.rulesScoped ? isCurrentRules(action.operation)
                    : !closed && action.operation.getSessionId() == mediaOperation.getSessionId();
        }
        return isCurrent(action.operation);
    }

    private void postCompletion(Runnable completion) {
        if (completion != null) mainHandler.post(completion);
    }

    private void executeControl(Runnable command) {
        if (closed) return;
        try {
            control.execute(command);
        } catch (RejectedExecutionException ignored) {
            // close 与新命令竞争时，新命令按旧代际直接失效。
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    static Failure.Code mapProbeError(ProbeErrorCode code) {
        switch (code) {
            case INVALID_SOURCE: return Failure.Code.INVALID_REQUEST;
            case UNSUPPORTED_SOURCE:
            case LIVE_STREAM_NOT_SUPPORTED:
            case DRM_NOT_SUPPORTED:
            case NO_AUDIO_TRACK:
            case UNSUPPORTED_AUDIO:
            case DECODER_FAILED: return Failure.Code.UNSUPPORTED_SOURCE;
            case SOURCE_IO: return Failure.Code.SOURCE_IO;
            case RULE_PARSE_FAILED:
            case RULE_REVISION_CONFLICT: return Failure.Code.RULES_INVALID;
            case RULE_FETCH_FAILED:
            case RULES_UNAVAILABLE: return Failure.Code.RULES_UNAVAILABLE;
            case TIMELINE_UNRELIABLE: return Failure.Code.TIMELINE_UNRELIABLE;
            case RESOURCE_EXHAUSTED: return Failure.Code.RESOURCE_EXHAUSTED;
            case INTERNAL:
            default: return Failure.Code.INTERNAL;
        }
    }

    static boolean resolveAutomaticSkip(boolean testing, boolean testingMode,
                                        boolean playbackMode) {
        return testing ? testingMode : playbackMode;
    }

    static RuleDocument selectRuleTestDocument(RuleDocument stored,
                                                Map<String, ProbeRule> pendingDrafts,
                                                String ruleId, ProbeRule selected) {
        if (!pendingDrafts.containsKey(ruleId)) return stored;
        // 草稿测试只注入当前规则，避免旧规则的相近指纹干扰测试结果。
        return new RuleDocument(stored.getRevision(), Collections.singletonList(selected));
    }

    static boolean isPendingMatchExpired(Match match, long positionMs) {
        return match != null && positionMs >= match.getEndMs();
    }

    static Failure.Code mapToolError(ProbeToolErrorCode code) {
        switch (code) {
            case INVALID_REQUEST: return Failure.Code.INVALID_REQUEST;
            case UNSUPPORTED_SOURCE:
            case LIVE_STREAM_NOT_SUPPORTED:
            case DRM_NOT_SUPPORTED:
            case NO_AUDIO_TRACK:
            case UNSUPPORTED_AUDIO:
            case DECODER_FAILED: return Failure.Code.UNSUPPORTED_SOURCE;
            case SOURCE_IO: return Failure.Code.SOURCE_IO;
            case TIMELINE_UNRELIABLE: return Failure.Code.TIMELINE_UNRELIABLE;
            case RESOURCE_EXHAUSTED: return Failure.Code.RESOURCE_EXHAUSTED;
            case TIMEOUT: return Failure.Code.TIMEOUT;
            case INTERNAL:
            default: return Failure.Code.INTERNAL;
        }
    }

    private final class PlayerCallbacks implements ProbePlayerHost.Listener {
        @Override public void onStatus(long sessionId, ProbePlayerState state,
                                       long positionMs, long durationMs) {
            if (sessionId != playerSessionId) return;
            Operation operation = mediaOperation;
            if (!isCurrent(operation)) return;
            if (isPendingMatchExpired(pendingMatch, positionMs)) clearPendingMatch(operation);
            if (snapshotState == Snapshot.State.SCANNING
                    || snapshotState == Snapshot.State.CAPTURING) {
                emitPlaybackProgress();
                return;
            }
            if (automaticWorkflowDone) {
                emitPlaybackProgress();
                return;
            }
            if (state == ProbePlayerState.PREPARING) {
                emitSnapshot(operation, Snapshot.State.OPENING, "正在加载视频...");
            } else if (state == ProbePlayerState.BUFFERING) {
                emitSnapshot(operation, Snapshot.State.BUFFERING, "正在缓冲...");
            } else if (state == ProbePlayerState.READY) {
                emitSnapshot(operation, Snapshot.State.READY, "视频已就绪");
            } else if (state == ProbePlayerState.ENDED) {
                emitSnapshot(operation, Snapshot.State.ENDED, "播放结束");
            }
        }

        @Override public void onDiscontinuity(long sessionId, long positionMs,
                ProbePlaybackDiscontinuityReason reason) {
            if (sessionId != playerSessionId || closed) return;
            Operation operation = mediaOperation;
            if (!isCurrent(operation)) return;
            clearPendingMatch(operation);
            // 播放器回退和 HLS 断点仍属同一请求；用户 open/seek 已在入口更新 generation。
            probeSessions.expectNext();
            probe.notifyHostDiscontinuity(positionMs);
            if (snapshotState == Snapshot.State.SCANNING
                    || snapshotState == Snapshot.State.CAPTURING) {
                emitPlaybackProgress();
            } else if (automaticWorkflowDone) {
                emitPlaybackProgress();
            } else {
                emitSnapshot(operation, Snapshot.State.READY, "");
            }
        }

        @Override public void onError(long sessionId, ProbeErrorCode code,
                                      boolean retryable, String message) {
            if (sessionId != playerSessionId) return;
            Operation operation = mediaOperation;
            playerSessionId = 0L;
            emitMediaFailure(operation, mapProbeError(code), retryable, message);
        }
    }

    private final class ProbeCallbacks implements ProbeListener {
        @Override public void onSkipRequested(SkipRequest request) {
            handleSkip(request);
        }

        @Override public void onStatusChanged(ProbeStatus status) {
            handleProbeStatus(status);
        }

        @Override public void onRulesReplaced(RuleReplacementResult result) {
            handleRuleReplacement(result);
        }

        @Override public void onError(ProbeError error) {
            handleProbeError(error);
        }
    }

    private final class CaptureCallbacks implements ProbeToolHost.CaptureListener {
        @Override public void onProgress(FingerprintCaptureProgress progress) {
            if (progress.getSessionId() != captureSessionId) return;
            if (automaticCapture && automaticBatch != null) {
                followAutomaticPlayback(progress.getPercent());
                emitAutomaticCapture(mediaOperation, AutomaticCaptureProgress.capturing(
                        automaticBatch.currentRange(), automaticBatch.currentNumber(),
                        automaticBatch.size(), progress.getPercent()));
            }
            emitSnapshot(mediaOperation, automaticCapture
                            ? Snapshot.State.SCANNING : Snapshot.State.CAPTURING,
                    automaticCapture && automaticBatch != null
                            ? "自动采集 " + automaticBatch.currentNumber() + "/"
                            + automaticBatch.size() + "，指纹 " + progress.getPercent() + "%"
                            : "正在提取广告指纹... " + progress.getPercent() + "%");
        }

        @Override public void onCompleted(long sessionId, FingerprintRuleDraft draft) {
            if (sessionId != captureSessionId) return;
            Operation operation = mediaOperation;
            captureSessionId = 0L;
            try {
                ProbeRule rule = ProbeDraftMapper.toRule(draft);
                RuleDraftDeduplicator.Result result =
                        RuleDraftDeduplicator.collect(drafts, rules, rule);
                // 每次完整采集都可反复测试，是否重复只影响后续保存。
                emitDraft(operation, result.rule());
                if (automaticCapture && automaticBatch != null) {
                    finishAutomaticPlayback();
                    automaticBatch.accept(result.status());
                    startNextAutomaticCapture(operation);
                } else if (!result.isPending()) {
                    emitSnapshot(operation, Snapshot.State.READY,
                            "指纹提取完成，可重复测试；保存时会提示重复规则");
                } else {
                    emitSnapshot(operation, Snapshot.State.READY,
                            "广告指纹提取完成，可先测试，确认后保存");
                }
            } catch (IllegalArgumentException error) {
                handleCaptureFailure(operation, new Failure(Failure.Code.RULES_INVALID, false,
                        "生成的规则草稿无效: " + safeMessage(error)));
            }
        }

        @Override public void onCancelled(long sessionId) {
            if (sessionId == captureSessionId) captureSessionId = 0L;
        }

        @Override public void onError(ProbeToolError error) {
            if (error.getSessionId() != captureSessionId) return;
            handleCaptureFailure(mediaOperation, new Failure(mapToolError(error.getCode()),
                    error.isRetryable(), error.getMessage()));
        }
    }

    private final class ScanCallbacks implements ProbeToolHost.ScanListener {
        private final Operation operation;

        private ScanCallbacks(Operation operation) {
            this.operation = operation;
        }

        @Override public void onCompleted(HlsScanResult result) {
            if (result.getSessionId() != scanSessionId || !isCurrent(operation)) return;
            scanSessionId = 0L;
            if (result.getCandidates().isEmpty()) {
                emitMediaFailure(operation, Failure.Code.UNSUPPORTED_SOURCE, true,
                        "未发现可信的结构型广告区间，请改用手工截取");
                return;
            }
            automaticBatch = new AutomaticCaptureBatch(result);
            startNextAutomaticCapture(operation);
        }

        @Override public void onCancelled(long sessionId) {
            if (sessionId == scanSessionId) scanSessionId = 0L;
        }

        @Override public void onError(ProbeToolError error) {
            if (error.getSessionId() != scanSessionId || !isCurrent(operation)) return;
            scanSessionId = 0L;
            emitMediaFailure(operation, mapToolError(error.getCode()), error.isRetryable(),
                    "自动扫描失败: " + error.getMessage());
        }
    }

    private enum ReplacementKind { OPEN_MEDIA, APPLY_ALL, TEST_ONE }

    private static final class RuleReplacementAction {
        final long requestId;
        final Operation operation;
        final ReplacementKind kind;
        final String ruleId;
        final ProbeMedia mediaToOpen;
        final boolean rulesScoped;
        final boolean automaticSkip;

        RuleReplacementAction(long requestId, Operation operation,
                              ReplacementKind kind, String ruleId, ProbeMedia mediaToOpen,
                              boolean rulesScoped, boolean automaticSkip) {
            this.requestId = requestId;
            this.operation = operation;
            this.kind = kind;
            this.ruleId = ruleId;
            this.mediaToOpen = mediaToOpen;
            this.rulesScoped = rulesScoped;
            this.automaticSkip = automaticSkip;
        }
    }
}
