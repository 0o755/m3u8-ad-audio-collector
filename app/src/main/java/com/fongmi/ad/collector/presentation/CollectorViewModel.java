/* 采集页状态机：快照用户请求并只通过 CollectorGateway 驱动底层。 */
package com.fongmi.ad.collector.presentation;

import android.view.Surface;

import com.fongmi.ad.collector.gateway.CollectorGateway;
import com.fongmi.ad.collector.rules.ProbeRule;
import com.fongmi.ad.collector.rules.RuleDocument;
import com.fongmi.ad.collector.rules.RuleDocumentMerger;
import com.fongmi.ad.collector.rules.RuleDraftSavePlanner;

import java.util.Collections;

public final class CollectorViewModel implements CollectorGateway.Listener, AutoCloseable {
    public enum SaveDraftResult { NO_DRAFT, DUPLICATE, SAVING, SAVING_WITH_DUPLICATES }

    public interface Observer {
        void onState(CollectorUiState state);
        void onMatch(CollectorGateway.Match match);
        void onMatchCleared();
        void onFailure(CollectorGateway.Failure failure);
    }

    private final CollectorGateway gateway;
    private CollectorUiState state = CollectorUiState.initial();
    private Observer observer;
    private String currentUrl = "";
    private long previewEndMs = -1L;

    public CollectorViewModel(CollectorGateway gateway) {
        this.gateway = gateway;
        gateway.setListener(this);
        gateway.loadRules();
    }

    public void observe(Observer observer) {
        this.observer = observer;
        if (observer != null) observer.onState(state);
    }

    public void play(String url, long startPositionMs, boolean automaticSkip) {
        currentUrl = url == null ? "" : url.trim();
        try {
            gateway.open(new CollectorGateway.OpenRequest(currentUrl, Collections.emptyMap(),
                    startPositionMs, automaticSkip));
        } catch (IllegalArgumentException error) {
            update(state.withStatus(error.getMessage()));
        }
    }

    public void scan(String url, boolean automaticSkip) {
        currentUrl = url == null ? "" : url.trim();
        try {
            gateway.scanCandidates(new CollectorGateway.OpenRequest(currentUrl,
                    Collections.emptyMap(), 0L, automaticSkip));
        } catch (IllegalArgumentException error) {
            update(state.withStatus(error.getMessage()));
        }
    }

    public void seek(long positionMs) {
        previewEndMs = -1L;
        gateway.seek(Math.max(0L, positionMs));
    }

    public void seekBy(long offsetMs) {
        long target = state.getPositionMs() + offsetMs;
        if (target < 0L) target = 0L;
        if (state.getMediaDurationMs() > 0L) {
            target = Math.min(target, state.getMediaDurationMs());
        }
        seek(target);
    }

    public void togglePlayback() {
        previewEndMs = -1L;
        if (state.isPlaying()) gateway.pause();
        else gateway.play();
    }

    public void previewPosition(long positionMs) {
        previewEndMs = -1L;
        gateway.seek(Math.max(0L, positionMs));
        gateway.play();
    }

    public void previewDuration(long startMs, long endMs) {
        previewEndMs = Math.max(startMs, endMs);
        gateway.seek(Math.max(0L, startMs));
        gateway.play();
    }

    public void attachVideoSurface(Surface surface) {
        gateway.attachSurface(surface);
    }

    public void clearVideoSurface(Surface surface, Runnable onCleared) {
        gateway.clearSurface(surface, onCleared);
    }

    public void capture(long startMs, long durationMs) {
        try {
            gateway.startCapture(new CollectorGateway.CaptureRange(startMs, durationMs,
                    0L, CollectorGateway.CaptureRange.REQUIRED_ANCHOR_DURATION_MS));
        } catch (IllegalArgumentException error) {
            update(state.withStatus(error.getMessage()));
        }
    }

    public void testDraft(boolean automaticSkip) {
        if (state.getDraft() != null) {
            gateway.testRule(state.getDraft().getId(), automaticSkip);
        }
    }

    public SaveDraftResult saveDraft() {
        ProbeRule draft = state.getDraft();
        if (draft == null) return SaveDraftResult.NO_DRAFT;
        try {
            RuleDraftSavePlanner.Result plan = RuleDraftSavePlanner.plan(
                    state.getDocument(), state.getDraftDocument());
            if (plan.getPending().getRules().isEmpty()) {
                update(state.withStatus("规则已存在，无需重复保存"));
                return SaveDraftResult.DUPLICATE;
            }
            gateway.saveRule(RuleDocumentMerger.merge(state.getDocument(),
                    plan.getPending()));
            return plan.getDuplicateCount() > 0
                    ? SaveDraftResult.SAVING_WITH_DUPLICATES : SaveDraftResult.SAVING;
        } catch (IllegalArgumentException error) {
            update(state.withStatus("规则无法保存: " + error.getMessage()));
            return SaveDraftResult.NO_DRAFT;
        }
    }

    public void merge(CollectorGateway.RuleSource source) {
        gateway.merge(source);
    }

    public void reloadRules() {
        gateway.loadRules();
    }

    public void skipPendingMatch() {
        gateway.skipPendingMatch();
    }

    public CollectorUiState getState() {
        return state;
    }

    @Override
    public void onSnapshot(CollectorGateway.Operation operation,
                           CollectorGateway.Snapshot snapshot) {
        if (previewEndMs >= 0L && snapshot.getPositionMs() >= previewEndMs - 100L) {
            previewEndMs = -1L;
            gateway.pause();
        }
        boolean ready = snapshot.getState() == CollectorGateway.Snapshot.State.READY
                || snapshot.getState() == CollectorGateway.Snapshot.State.BUFFERING;
        update(state.withPlayback(snapshot.getPositionMs(), snapshot.getDurationMs(),
                snapshot.getMessage(), ready, snapshot.isPlaying(), snapshot.getState()));
    }

    @Override
    public void onRulesLoaded(CollectorGateway.Operation operation, RuleDocument document,
                              String path) {
        update(state.withRules(document, path, "已加载 " + document.getRules().size() + " 条规则"));
    }

    @Override
    public void onDraftReady(CollectorGateway.Operation operation, ProbeRule rule) {
        update(state.withDraft(rule, "指纹提取完成，可先测试，确认后保存"));
    }

    @Override
    public void onAutomaticCapture(CollectorGateway.Operation operation,
                                   CollectorGateway.AutomaticCaptureProgress progress) {
        update(state.withAutomaticCapture(progress));
    }

    @Override
    public void onMatch(CollectorGateway.Operation operation, CollectorGateway.Match match) {
        if (observer != null) observer.onMatch(match);
        if (match.isAutomatic()) update(state.withStatus("已自动跳到广告结束位置"));
        else update(state.withStatus("发现广告，可手动跳过"));
    }

    @Override
    public void onMatchCleared(CollectorGateway.Operation operation) {
        if (observer != null) observer.onMatchCleared();
    }

    @Override
    public void onFailure(CollectorGateway.Operation operation, CollectorGateway.Failure failure) {
        update(state.withStatus(failure.getMessage()));
        if (observer != null) observer.onFailure(failure);
    }

    private void update(CollectorUiState next) {
        state = next;
        if (observer != null) observer.onState(next);
    }

    @Override
    public void close() {
        gateway.close();
    }
}
