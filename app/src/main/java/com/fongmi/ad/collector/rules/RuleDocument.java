/* Probe rules-v1 根文档值对象，固定格式与算法，不承载旧版根字段。 */
package com.fongmi.ad.collector.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RuleDocument {
    public static final String FORMAT = "ad-audio-probe-rules";
    public static final int SCHEMA_VERSION = 1;
    public static final String ALGORITHM = "spectral-sequence-v1";

    private final long revision;
    private final List<ProbeRule> rules;

    public RuleDocument(long revision, List<ProbeRule> rules) {
        this.revision = revision;
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
    }

    public static RuleDocument empty() {
        return new RuleDocument(1L, Collections.emptyList());
    }

    public long getRevision() {
        return revision;
    }

    public List<ProbeRule> getRules() {
        return rules;
    }

    public ProbeRule find(String id) {
        for (ProbeRule rule : rules) {
            if (rule.getId().equals(id)) return rule;
        }
        return null;
    }
}
