/* rules-v1 稳定合并策略：同 ID 覆盖、不同 ID 追加并递增 revision。 */
package com.fongmi.ad.collector.rules;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RuleDocumentMerger {
    private RuleDocumentMerger() {
    }

    public static RuleDocument merge(RuleDocument local, RuleDocument incoming) {
        RuleDocumentValidator.validate(local);
        RuleDocumentValidator.validate(incoming);
        long base = Math.max(local.getRevision(), incoming.getRevision());
        if (base >= RuleDocumentValidator.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("revision 已达到安全整数上限");
        }
        Map<String, ProbeRule> merged = new LinkedHashMap<>();
        for (ProbeRule rule : local.getRules()) merged.put(rule.getId(), rule);
        for (ProbeRule rule : incoming.getRules()) merged.put(rule.getId(), rule);
        return RuleDocumentValidator.validate(
                new RuleDocument(base + 1L, new java.util.ArrayList<>(merged.values())));
    }
}
