/* 验证自动采集进度在播放刷新期间稳定保留，并在流程结束后及时清除。 */
package com.fongmi.ad.collector.presentation;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.fongmi.ad.collector.gateway.CollectorGateway;

import org.junit.Test;

public final class CollectorUiStateTest {
    @Test
    public void keepsAutomaticProgressDuringScanningSnapshots() {
        CollectorGateway.CaptureRange range = new CollectorGateway.CaptureRange(
                342_000L, 15_132L, 0L, 5_000L);
        CollectorGateway.AutomaticCaptureProgress progress =
                CollectorGateway.AutomaticCaptureProgress.capturing(range, 1, 2, 47);

        CollectorUiState state = CollectorUiState.initial()
                .withAutomaticCapture(progress)
                .withPlayback(343_000L, 1_385_172L, "采集中", false,
                        CollectorGateway.Snapshot.State.SCANNING);

        assertSame(progress, state.getAutomaticCaptureProgress());
    }

    @Test
    public void clearsAutomaticProgressWhenWorkflowFinishes() {
        CollectorGateway.CaptureRange range = new CollectorGateway.CaptureRange(
                342_000L, 15_132L, 0L, 5_000L);
        CollectorGateway.AutomaticCaptureProgress progress =
                CollectorGateway.AutomaticCaptureProgress.capturing(range, 1, 1, 100);

        CollectorUiState state = CollectorUiState.initial()
                .withAutomaticCapture(progress)
                .withPlayback(357_132L, 1_385_172L, "采集完成", true,
                        CollectorGateway.Snapshot.State.READY);

        assertNull(state.getAutomaticCaptureProgress());
    }
}
