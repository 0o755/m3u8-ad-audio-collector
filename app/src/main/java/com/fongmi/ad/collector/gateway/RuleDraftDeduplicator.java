/* 规则草稿去重器：按 Probe v1 完整锚点指纹复用稳定 ID，避免重复扫描累加。 */
package com.fongmi.ad.collector.gateway;

import com.fongmi.ad.collector.rules.ProbeRule;
import com.fongmi.ad.collector.rules.RuleDocument;
import com.fongmi.ad.collector.rules.RuleFingerprint;

import java.util.Map;

final class RuleDraftDeduplicator {
    enum Status { ADDED, UPDATED, ALREADY_SAVED }

    static final class Result {
        private final ProbeRule rule;
        private final Status status;

        Result(ProbeRule rule, Status status) {
            this.rule = rule;
            this.status = status;
        }

        ProbeRule rule() {
            return rule;
        }

        Status status() {
            return status;
        }

        boolean isPending() {
            return status != Status.ALREADY_SAVED;
        }
    }

    private RuleDraftDeduplicator() {
    }

    static Result collect(Map<String, ProbeRule> drafts, RuleDocument saved,
                          ProbeRule incoming) {
        ProbeRule existingDraft = findByFingerprint(drafts.values(), incoming);
        if (existingDraft != null) {
            ProbeRule updated = withId(incoming, existingDraft.getId());
            drafts.put(updated.getId(), updated);
            return new Result(updated, Status.UPDATED);
        }

        ProbeRule existingSaved = findByFingerprint(saved.getRules(), incoming);
        if (existingSaved != null) {
            ProbeRule updated = withId(incoming, existingSaved.getId());
            if (sameDetectionRule(existingSaved, updated)) {
                // 重复规则仍保留本次采集的测试链接和位置，保存阶段再提示重复。
                drafts.put(updated.getId(), updated);
                return new Result(updated, Status.ALREADY_SAVED);
            }
            drafts.put(updated.getId(), updated);
            return new Result(updated, Status.UPDATED);
        }

        drafts.put(incoming.getId(), incoming);
        return new Result(incoming, Status.ADDED);
    }

    private static ProbeRule findByFingerprint(Iterable<ProbeRule> rules, ProbeRule target) {
        for (ProbeRule rule : rules) {
            if (sameFingerprint(rule, target)) return rule;
        }
        return null;
    }

    private static boolean sameDetectionRule(ProbeRule first, ProbeRule second) {
        return first.getDurationMs() == second.getDurationMs()
                && sameFingerprint(first, second);
    }

    private static boolean sameFingerprint(ProbeRule first, ProbeRule second) {
        if (first.getAnchorOffsetMs() != second.getAnchorOffsetMs()
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

    private static ProbeRule withId(ProbeRule source, String id) {
        return new ProbeRule(id, source.getDurationMs(), source.getAnchorOffsetMs(),
                source.getAnchorDurationMs(), source.getFingerprints(), source.getTest());
    }
}
