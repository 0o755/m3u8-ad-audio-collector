/* Probe 会话门闩：在宿主时间轴跳转后接管新会话，并继续拒绝旧会话回调。 */
package com.fongmi.ad.collector.gateway;

final class ProbeSessionGate {
    private static final long BLOCKED = -1L;

    private long sessionId;
    private long rejectedThrough;

    void replace(long sessionId) {
        this.sessionId = Math.max(0L, sessionId);
    }

    void expectNext() {
        rejectedThrough = Math.max(rejectedThrough, sessionId);
        sessionId = 0L;
    }

    void block() {
        sessionId = BLOCKED;
    }

    boolean accept(long callbackSessionId) {
        if (callbackSessionId <= 0L || sessionId == BLOCKED) return false;
        if (sessionId == 0L) {
            if (callbackSessionId <= rejectedThrough) return false;
            sessionId = callbackSessionId;
        }
        return sessionId == callbackSessionId;
    }
}
