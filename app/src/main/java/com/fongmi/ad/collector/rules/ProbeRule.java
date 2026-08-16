/* Probe rules-v1 单条规则，不包含旧启用或待验证状态。 */
package com.fongmi.ad.collector.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ProbeRule {
    private final String id;
    private final long durationMs;
    private final long anchorOffsetMs;
    private final long anchorDurationMs;
    private final List<RuleFingerprint> fingerprints;
    private final RuleTest test;

    public ProbeRule(String id, long durationMs, long anchorOffsetMs, long anchorDurationMs,
                     List<RuleFingerprint> fingerprints, RuleTest test) {
        this.id = id;
        this.durationMs = durationMs;
        this.anchorOffsetMs = anchorOffsetMs;
        this.anchorDurationMs = anchorDurationMs;
        this.fingerprints = Collections.unmodifiableList(new ArrayList<>(fingerprints));
        this.test = test;
    }

    public String getId() {
        return id;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getAnchorOffsetMs() {
        return anchorOffsetMs;
    }

    public long getAnchorDurationMs() {
        return anchorDurationMs;
    }

    public List<RuleFingerprint> getFingerprints() {
        return fingerprints;
    }

    public RuleTest getTest() {
        return test;
    }
}
