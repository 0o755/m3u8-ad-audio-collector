/* 采集页不可变状态，只包含界面需要的数据，不泄漏 Probe 或播放器对象。 */
package com.fongmi.ad.collector.presentation;

import com.fongmi.ad.collector.gateway.CollectorGateway;
import com.fongmi.ad.collector.rules.ProbeRule;
import com.fongmi.ad.collector.rules.RuleDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CollectorUiState {
    private final long positionMs;
    private final long mediaDurationMs;
    private final String status;
    private final String rulePath;
    private final RuleDocument document;
    private final RuleDocument draftDocument;
    private final boolean mediaReady;
    private final boolean playing;
    private final CollectorGateway.Snapshot.State playbackState;
    private final CollectorGateway.AutomaticCaptureProgress automaticCaptureProgress;

    CollectorUiState(long positionMs, long mediaDurationMs, String status, String rulePath,
                     RuleDocument document, RuleDocument draftDocument, boolean mediaReady,
                     boolean playing,
                     CollectorGateway.Snapshot.State playbackState,
                     CollectorGateway.AutomaticCaptureProgress automaticCaptureProgress) {
        this.positionMs = positionMs;
        this.mediaDurationMs = mediaDurationMs;
        this.status = status;
        this.rulePath = rulePath;
        this.document = document;
        this.draftDocument = draftDocument;
        this.mediaReady = mediaReady;
        this.playing = playing;
        this.playbackState = playbackState;
        this.automaticCaptureProgress = automaticCaptureProgress;
    }

    static CollectorUiState initial() {
        return new CollectorUiState(0L, 0L,
                "粘贴链接并播放，设置广告开始位置和时长后提取指纹。",
                "", RuleDocument.empty(), RuleDocument.empty(), false, false,
                CollectorGateway.Snapshot.State.IDLE, null);
    }

    CollectorUiState withPlayback(long positionMs, long durationMs, String status,
                                  boolean ready, boolean playing,
                                  CollectorGateway.Snapshot.State playbackState) {
        CollectorGateway.AutomaticCaptureProgress progress =
                playbackState == CollectorGateway.Snapshot.State.SCANNING
                        ? automaticCaptureProgress : null;
        return new CollectorUiState(positionMs, durationMs, status, rulePath,
                document, draftDocument, ready, playing, playbackState, progress);
    }

    CollectorUiState withRules(RuleDocument document, String path, String status) {
        Map<String, ProbeRule> remaining = new LinkedHashMap<>();
        for (ProbeRule rule : draftDocument.getRules()) {
            if (document.find(rule.getId()) == null) remaining.put(rule.getId(), rule);
        }
        return new CollectorUiState(positionMs, mediaDurationMs, status, path,
                document, new RuleDocument(draftDocument.getRevision(),
                new ArrayList<>(remaining.values())), mediaReady, playing, playbackState,
                automaticCaptureProgress);
    }

    CollectorUiState withDraft(ProbeRule draft, String status) {
        Map<String, ProbeRule> combined = new LinkedHashMap<>();
        for (ProbeRule rule : draftDocument.getRules()) combined.put(rule.getId(), rule);
        combined.put(draft.getId(), draft);
        return new CollectorUiState(positionMs, mediaDurationMs, status, rulePath,
                document, new RuleDocument(draftDocument.getRevision(),
                new ArrayList<>(combined.values())), mediaReady, playing, playbackState,
                automaticCaptureProgress);
    }

    CollectorUiState withStatus(String status) {
        return new CollectorUiState(positionMs, mediaDurationMs, status, rulePath,
                document, draftDocument, mediaReady, playing, playbackState,
                automaticCaptureProgress);
    }

    CollectorUiState withAutomaticCapture(
            CollectorGateway.AutomaticCaptureProgress progress) {
        return new CollectorUiState(positionMs, mediaDurationMs, status, rulePath,
                document, draftDocument, mediaReady, playing, playbackState, progress);
    }

    public long getPositionMs() { return positionMs; }
    public long getMediaDurationMs() { return mediaDurationMs; }
    public String getStatus() { return status; }
    public String getRulePath() { return rulePath; }
    public RuleDocument getDocument() { return document; }
    public ProbeRule getDraft() {
        int size = draftDocument.getRules().size();
        return size == 0 ? null : draftDocument.getRules().get(size - 1);
    }
    public RuleDocument getDraftDocument() { return draftDocument; }
    public boolean isMediaReady() { return mediaReady; }
    public boolean isPlaying() { return playing; }
    public CollectorGateway.Snapshot.State getPlaybackState() { return playbackState; }
    public CollectorGateway.AutomaticCaptureProgress getAutomaticCaptureProgress() {
        return automaticCaptureProgress;
    }
}
