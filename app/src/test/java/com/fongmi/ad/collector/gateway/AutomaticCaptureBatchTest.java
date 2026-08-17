/* 验证自动采集批次会保留首个具体失败，供界面呈现真实原因。 */
package com.fongmi.ad.collector.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;

import io.github.fongmi.adaudio.probe.tools.HlsAdCandidate;
import io.github.fongmi.adaudio.probe.tools.HlsCandidateOccurrence;
import io.github.fongmi.adaudio.probe.tools.HlsCandidateSignal;
import io.github.fongmi.adaudio.probe.tools.HlsScanResult;

public final class AutomaticCaptureBatchTest {
    @Test
    public void keepsFirstFailureAcrossRejectedCandidates() {
        AutomaticCaptureBatch batch = new AutomaticCaptureBatch(scanResult());
        CollectorGateway.Failure first = new CollectorGateway.Failure(
                CollectorGateway.Failure.Code.UNSUPPORTED_SOURCE, false, "不支持的时间线");
        CollectorGateway.Failure second = new CollectorGateway.Failure(
                CollectorGateway.Failure.Code.SOURCE_IO, true, "读取失败");

        batch.reject(first);
        batch.reject(second);

        assertEquals(0, batch.accepted());
        assertEquals(2, batch.size());
        assertSame(first, batch.firstFailure());
    }

    @Test
    public void mapsCurrentCandidateToCaptureRange() {
        AutomaticCaptureBatch batch = new AutomaticCaptureBatch(scanResult());

        CollectorGateway.CaptureRange range = batch.currentRange();

        assertEquals(1_000L, range.getAdStartMs());
        assertEquals(2_000L, range.getDurationMs());
        assertEquals(0L, range.getAnchorOffsetMs());
        assertEquals(2_000L, range.getAnchorDurationMs());
    }

    private static HlsScanResult scanResult() {
        HlsCandidateOccurrence first = new HlsCandidateOccurrence(1_000L, 3_000L, 1);
        HlsCandidateOccurrence second = new HlsCandidateOccurrence(5_000L, 7_000L, 1);
        HlsAdCandidate firstCandidate = candidate("auto-ad-0000000000000001", first);
        HlsAdCandidate secondCandidate = candidate("auto-ad-0000000000000002", second);
        return new HlsScanResult(1L, "https://example.com/video.m3u8", 10_000L, 2,
                java.util.Arrays.asList(firstCandidate, secondCandidate));
    }

    private static HlsAdCandidate candidate(String id, HlsCandidateOccurrence occurrence) {
        return new HlsAdCandidate(id, occurrence.getDurationMs(), 80,
                EnumSet.of(HlsCandidateSignal.DISCONTINUITY_BEFORE),
                Collections.singletonList(occurrence));
    }
}
