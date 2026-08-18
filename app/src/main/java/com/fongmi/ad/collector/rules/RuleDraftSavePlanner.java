/* 规则保存规划器：保留可测试草稿，只在真正保存时过滤已存在的检测规则。 */
package com.fongmi.ad.collector.rules;

import java.util.ArrayList;
import java.util.List;

public final class RuleDraftSavePlanner {
    public static final class Result {
        private final RuleDocument pending;
        private final int duplicateCount;

        private Result(RuleDocument pending, int duplicateCount) {
            this.pending = pending;
            this.duplicateCount = duplicateCount;
        }

        public RuleDocument getPending() {
            return pending;
        }

        public int getDuplicateCount() {
            return duplicateCount;
        }
    }

    private RuleDraftSavePlanner() {
    }

    public static Result plan(RuleDocument saved, RuleDocument drafts) {
        RuleDocumentValidator.validate(saved);
        RuleDocumentValidator.validate(drafts);
        List<ProbeRule> pending = new ArrayList<>();
        int duplicateCount = 0;
        for (ProbeRule draft : drafts.getRules()) {
            if (containsSameDetectionRule(saved, draft)) duplicateCount++;
            else pending.add(draft);
        }
        return new Result(new RuleDocument(saved.getRevision(), pending), duplicateCount);
    }

    private static boolean containsSameDetectionRule(RuleDocument saved, ProbeRule target) {
        for (ProbeRule rule : saved.getRules()) {
            if (sameDetectionRule(rule, target)) return true;
        }
        return false;
    }

    private static boolean sameDetectionRule(ProbeRule first, ProbeRule second) {
        if (first.getDurationMs() != second.getDurationMs()
                || first.getAnchorOffsetMs() != second.getAnchorOffsetMs()
                || first.getAnchorDurationMs() != second.getAnchorDurationMs()
                || first.getFingerprints().size() != second.getFingerprints().size()) {
            return false;
        }
        for (RuleFingerprint fingerprint : first.getFingerprints()) {
            RuleFingerprint other = findPhase(second, fingerprint.getPhaseMs());
            if (other == null || !fingerprint.getHashes().equals(other.getHashes())) return false;
        }
        return true;
    }

    private static RuleFingerprint findPhase(ProbeRule rule, int phaseMs) {
        for (RuleFingerprint fingerprint : rule.getFingerprints()) {
            if (fingerprint.getPhaseMs() == phaseMs) return fingerprint;
        }
        return null;
    }
}
