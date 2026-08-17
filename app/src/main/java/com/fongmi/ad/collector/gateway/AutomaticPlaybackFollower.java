/* 自动采集可见播放跟随器：把后台指纹进度映射为节流后的宿主播放位置。 */
package com.fongmi.ad.collector.gateway;

final class AutomaticPlaybackFollower {
    private static final long MIN_SEEK_STEP_MS = 2_000L;

    private CollectorGateway.CaptureRange range;
    private long lastTargetMs = -1L;

    void begin(CollectorGateway.CaptureRange range) {
        if (range == null) throw new IllegalArgumentException("自动播放范围不能为空");
        this.range = range;
        lastTargetMs = range.getAdStartMs();
    }

    long advance(int percent) {
        if (range == null) return -1L;
        int safePercent = Math.max(0, Math.min(100, percent));
        long targetMs = range.getAdStartMs()
                + Math.round(range.getDurationMs() * (safePercent / 100.0));
        long endMs = range.getAdStartMs() + range.getDurationMs();
        targetMs = Math.min(endMs, targetMs);
        if (targetMs <= lastTargetMs) return -1L;
        if (safePercent < 100 && targetMs - lastTargetMs < MIN_SEEK_STEP_MS) return -1L;
        lastTargetMs = targetMs;
        return targetMs;
    }

    long finish() {
        if (range == null) return -1L;
        long endMs = range.getAdStartMs() + range.getDurationMs();
        range = null;
        if (endMs <= lastTargetMs) {
            lastTargetMs = -1L;
            return -1L;
        }
        lastTargetMs = -1L;
        return endMs;
    }

    void clear() {
        range = null;
        lastTargetMs = -1L;
    }
}
