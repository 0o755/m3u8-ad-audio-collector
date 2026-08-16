/* Probe v1 单相位指纹值对象，保持相位与哈希序列不可变。 */
package com.fongmi.ad.collector.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RuleFingerprint {
    private final int phaseMs;
    private final List<String> hashes;

    public RuleFingerprint(int phaseMs, List<String> hashes) {
        this.phaseMs = phaseMs;
        this.hashes = Collections.unmodifiableList(new ArrayList<>(hashes));
    }

    public int getPhaseMs() {
        return phaseMs;
    }

    public List<String> getHashes() {
        return hashes;
    }
}
