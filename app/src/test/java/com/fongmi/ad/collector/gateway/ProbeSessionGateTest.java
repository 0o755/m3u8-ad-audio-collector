/* 验证测试定位切换 Probe 会话后，新回调可接管且旧回调继续失效。 */
package com.fongmi.ad.collector.gateway;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProbeSessionGateTest {
    @Test
    public void adoptsFirstSessionAfterDiscontinuityAndRejectsOthers() {
        ProbeSessionGate gate = new ProbeSessionGate();
        gate.replace(10L);
        assertTrue(gate.accept(10L));

        gate.expectNext();

        assertFalse(gate.accept(10L));
        assertTrue(gate.accept(11L));
        assertFalse(gate.accept(12L));
    }

    @Test
    public void blockedGateRejectsCallbacksUntilExplicitReplacement() {
        ProbeSessionGate gate = new ProbeSessionGate();
        gate.block();

        assertFalse(gate.accept(20L));
        gate.replace(21L);
        assertTrue(gate.accept(21L));
    }
}
