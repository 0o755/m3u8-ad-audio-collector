/* 采集器唯一底层入口：隔离播放器、Probe、采集和规则 I/O 的线程与生命周期。 */
package com.fongmi.ad.collector.gateway;

import android.view.Surface;

import com.fongmi.ad.collector.rules.ProbeRule;
import com.fongmi.ad.collector.rules.RuleDocument;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;
import java.io.InputStream;

public interface CollectorGateway extends AutoCloseable {
    void setListener(Listener listener);

    Operation open(OpenRequest request);

    default Operation open(String url, Map<String, String> headers) {
        return open(new OpenRequest(url, headers, 0L, false));
    }

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

    Operation merge(RuleSource source);

    Operation loadRules();

    Operation scanCandidates();

    Operation scanCandidates(OpenRequest request);

    Operation skipPendingMatch();

    @Override
    void close();

    interface Listener {
        void onSnapshot(Operation operation, Snapshot snapshot);

        void onRulesLoaded(Operation operation, RuleDocument document, String path);

        void onDraftReady(Operation operation, ProbeRule rule);

        void onAutomaticCapture(Operation operation, AutomaticCaptureProgress progress);

        void onMatch(Operation operation, Match match);

        void onFailure(Operation operation, Failure failure);
    }

    @FunctionalInterface
    interface RuleSource {
        InputStream open() throws IOException;
    }

    final class Operation {
        private final long sessionId;
        private final long generation;

        public Operation(long sessionId, long generation) {
            this.sessionId = sessionId;
            this.generation = generation;
        }

        public long getSessionId() {
            return sessionId;
        }

        public long getGeneration() {
            return generation;
        }
    }

    final class OpenRequest {
        private final String url;
        private final Map<String, String> headers;
        private final long startPositionMs;
        private final boolean automaticSkip;

        public OpenRequest(String url, Map<String, String> headers, long startPositionMs,
                           boolean automaticSkip) {
            if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("播放链接不能为空");
            if (startPositionMs < 0L) throw new IllegalArgumentException("播放起点不能为负数");
            this.url = url.trim();
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(
                    headers == null ? Collections.emptyMap() : headers));
            this.startPositionMs = startPositionMs;
            this.automaticSkip = automaticSkip;
        }

        public String getUrl() {
            return url;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public long getStartPositionMs() {
            return startPositionMs;
        }

        public boolean isAutomaticSkip() {
            return automaticSkip;
        }
    }

    final class CaptureRange {
        private final long adStartMs;
        private final long durationMs;
        private final long anchorOffsetMs;
        private final long anchorDurationMs;

        public CaptureRange(long adStartMs, long durationMs, long anchorOffsetMs,
                            long anchorDurationMs) {
            if (adStartMs < 0L || durationMs < 1_000L || anchorOffsetMs < 0L
                    || anchorDurationMs < 2_000L || anchorDurationMs > 5_000L
                    || anchorOffsetMs > durationMs - anchorDurationMs) {
                throw new IllegalArgumentException("采集范围不符合 Probe v1 约束");
            }
            this.adStartMs = adStartMs;
            this.durationMs = durationMs;
            this.anchorOffsetMs = anchorOffsetMs;
            this.anchorDurationMs = anchorDurationMs;
        }

        public long getAdStartMs() { return adStartMs; }
        public long getDurationMs() { return durationMs; }
        public long getAnchorOffsetMs() { return anchorOffsetMs; }
        public long getAnchorDurationMs() { return anchorDurationMs; }
    }

    final class Snapshot {
        public enum State {
            IDLE, OPENING, READY, BUFFERING, SCANNING, CAPTURING, TESTING, ENDED, ERROR, CLOSED
        }

        private final State state;
        private final long positionMs;
        private final long durationMs;
        private final int ruleCount;
        private final String message;

        public Snapshot(State state, long positionMs, long durationMs, int ruleCount, String message) {
            this.state = state;
            this.positionMs = Math.max(0L, positionMs);
            this.durationMs = Math.max(0L, durationMs);
            this.ruleCount = Math.max(0, ruleCount);
            this.message = message == null ? "" : message;
        }

        public State getState() { return state; }
        public long getPositionMs() { return positionMs; }
        public long getDurationMs() { return durationMs; }
        public int getRuleCount() { return ruleCount; }
        public String getMessage() { return message; }
    }

    final class AutomaticCaptureProgress {
        public enum Stage { SCANNING, CAPTURING }

        private final Stage stage;
        private final CaptureRange range;
        private final int current;
        private final int total;
        private final int percent;

        private AutomaticCaptureProgress(Stage stage, CaptureRange range, int current,
                                         int total, int percent) {
            this.stage = stage;
            this.range = range;
            this.current = Math.max(0, current);
            this.total = Math.max(0, total);
            this.percent = Math.max(0, Math.min(100, percent));
        }

        public static AutomaticCaptureProgress scanning() {
            return new AutomaticCaptureProgress(Stage.SCANNING, null, 0, 0, 0);
        }

        public static AutomaticCaptureProgress capturing(CaptureRange range, int current,
                                                          int total, int percent) {
            if (range == null || current < 1 || total < current) {
                throw new IllegalArgumentException("自动采集进度无效");
            }
            return new AutomaticCaptureProgress(Stage.CAPTURING, range, current, total, percent);
        }

        public Stage getStage() { return stage; }
        public CaptureRange getRange() { return range; }
        public int getCurrent() { return current; }
        public int getTotal() { return total; }
        public int getPercent() { return percent; }
    }

    final class Match {
        private final String ruleId;
        private final long startMs;
        private final long endMs;
        private final boolean automatic;

        public Match(String ruleId, long startMs, long endMs, boolean automatic) {
            this.ruleId = ruleId;
            this.startMs = startMs;
            this.endMs = endMs;
            this.automatic = automatic;
        }

        public String getRuleId() { return ruleId; }
        public long getStartMs() { return startMs; }
        public long getEndMs() { return endMs; }
        public boolean isAutomatic() { return automatic; }
    }

    final class Failure {
        public enum Code {
            INVALID_REQUEST, UNSUPPORTED_SOURCE,
            SOURCE_IO, RULES_INVALID, RULES_UNAVAILABLE, STORAGE_FAILED,
            TIMELINE_UNRELIABLE, RESOURCE_EXHAUSTED, TIMEOUT, INTERNAL
        }

        private final Code code;
        private final boolean retryable;
        private final String message;

        public Failure(Code code, boolean retryable, String message) {
            this.code = code;
            this.retryable = retryable;
            this.message = message;
        }

        public Code getCode() { return code; }
        public boolean isRetryable() { return retryable; }
        public String getMessage() { return message; }
    }
}
