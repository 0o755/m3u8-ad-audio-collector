/* 将公开 collector-tools 草稿转换为采集器自身的 rules-v1 不可变模型。 */
package com.fongmi.ad.collector.gateway;

import com.fongmi.ad.collector.rules.ProbeRule;
import com.fongmi.ad.collector.rules.RuleFingerprint;
import com.fongmi.ad.collector.rules.RuleTest;

import java.util.ArrayList;
import java.util.List;

import io.github.fongmi.adaudio.probe.tools.FingerprintRuleDraft;
import io.github.fongmi.adaudio.probe.tools.FingerprintSequence;

final class ProbeDraftMapper {
    private ProbeDraftMapper() {
    }

    static ProbeRule toRule(FingerprintRuleDraft draft) {
        List<RuleFingerprint> fingerprints = new ArrayList<>();
        for (FingerprintSequence sequence : draft.getFingerprints()) {
            fingerprints.add(new RuleFingerprint(sequence.getPhaseMs(), sequence.getHashes()));
        }
        return new ProbeRule(draft.getId(), draft.getDurationMs(), draft.getAnchorOffsetMs(),
                draft.getAnchorDurationMs(), fingerprints,
                new RuleTest(draft.getTestUrl(), draft.getTestAdStartMs()));
    }
}
