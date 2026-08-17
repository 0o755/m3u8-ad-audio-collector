/* 验证自动采集播放跟随器会节流中间跳转，并在完成时落到广告结束位置。 */
package com.fongmi.ad.collector.gateway;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AutomaticPlaybackFollowerTest {
    @Test
    public void advancesInStableStepsAndFinishesAtCandidateEnd() {
        AutomaticPlaybackFollower follower = new AutomaticPlaybackFollower();
        follower.begin(new CollectorGateway.CaptureRange(
                342_000L, 15_000L, 0L, 5_000L));

        assertEquals(-1L, follower.advance(5));
        assertEquals(345_000L, follower.advance(20));
        assertEquals(-1L, follower.advance(25));
        assertEquals(348_000L, follower.advance(40));
        assertEquals(-1L, follower.advance(30));
        assertEquals(357_000L, follower.finish());
        assertEquals(-1L, follower.advance(100));
    }

    @Test
    public void fullProgressDoesNotNeedAnExtraFinishSeek() {
        AutomaticPlaybackFollower follower = new AutomaticPlaybackFollower();
        follower.begin(new CollectorGateway.CaptureRange(
                10_000L, 5_000L, 0L, 5_000L));

        assertEquals(15_000L, follower.advance(100));
        assertEquals(-1L, follower.finish());
    }
}
