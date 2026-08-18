/* 验证重复草稿只在保存阶段过滤，不影响其作为测试规则继续使用。 */
package com.fongmi.ad.collector.rules;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class RuleDraftSavePlannerTest {
    @Test
    public void filtersSameDetectionRuleOnlyWhenSaving() {
        ProbeRule saved = rule("saved", 15_000L, "11111111");
        ProbeRule repeated = rule("captured-again", 15_000L, "11111111");

        RuleDraftSavePlanner.Result result = RuleDraftSavePlanner.plan(
                new RuleDocument(3L, Collections.singletonList(saved)),
                new RuleDocument(3L, Collections.singletonList(repeated)));

        assertEquals(1, result.getDuplicateCount());
        assertEquals(0, result.getPending().getRules().size());
    }

    @Test
    public void keepsNewRulesWhileReportingDuplicates() {
        ProbeRule saved = rule("saved", 15_000L, "11111111");
        ProbeRule repeated = rule("captured-again", 15_000L, "11111111");
        ProbeRule fresh = rule("fresh", 12_000L, "22222222");

        RuleDraftSavePlanner.Result result = RuleDraftSavePlanner.plan(
                new RuleDocument(5L, Collections.singletonList(saved)),
                new RuleDocument(5L, Arrays.asList(repeated, fresh)));

        assertEquals(1, result.getDuplicateCount());
        assertEquals(1, result.getPending().getRules().size());
        assertEquals("fresh", result.getPending().getRules().get(0).getId());
    }

    @Test
    public void keepsChangedDurationAsAnUpdate() {
        ProbeRule saved = rule("saved", 15_000L, "11111111");
        ProbeRule updated = rule("saved", 15_200L, "11111111");

        RuleDraftSavePlanner.Result result = RuleDraftSavePlanner.plan(
                new RuleDocument(2L, Collections.singletonList(saved)),
                new RuleDocument(2L, Collections.singletonList(updated)));

        assertEquals(0, result.getDuplicateCount());
        assertEquals(1, result.getPending().getRules().size());
    }

    private static ProbeRule rule(String id, long durationMs, String firstHash) {
        return new ProbeRule(id, durationMs, 0L, 5_000L, Arrays.asList(
                fingerprint(0, firstHash), fingerprint(64, firstHash),
                fingerprint(128, firstHash), fingerprint(192, firstHash)),
                new RuleTest("https://example.com/video.m3u8", 342_000L));
    }

    private static RuleFingerprint fingerprint(int phaseMs, String firstHash) {
        int count = phaseMs == 192 ? 17 : 18;
        List<String> hashes = new ArrayList<>();
        hashes.add(firstHash);
        while (hashes.size() < count) {
            hashes.add(hashes.size() % 2 == 0 ? "55555555" : "33333333");
        }
        return new RuleFingerprint(phaseMs, hashes);
    }
}
