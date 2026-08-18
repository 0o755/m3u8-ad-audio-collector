/* 验证重复指纹复用稳定规则 ID，并保留本次采集结果供重复测试。 */
package com.fongmi.ad.collector.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fongmi.ad.collector.rules.ProbeRule;
import com.fongmi.ad.collector.rules.RuleDocument;
import com.fongmi.ad.collector.rules.RuleFingerprint;
import com.fongmi.ad.collector.rules.RuleTest;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuleDraftDeduplicatorTest {
    @Test
    public void repeatedFingerprintUpdatesExistingDraftWithoutGrowingCount() {
        Map<String, ProbeRule> drafts = new LinkedHashMap<>();
        RuleDraftDeduplicator.collect(drafts, RuleDocument.empty(),
                rule("auto-ad-first", 15_000L, "11111111"));

        RuleDraftDeduplicator.Result result = RuleDraftDeduplicator.collect(
                drafts, RuleDocument.empty(), rule("auto-ad-second", 15_200L, "11111111"));

        assertEquals(RuleDraftDeduplicator.Status.UPDATED, result.status());
        assertEquals(1, drafts.size());
        assertEquals("auto-ad-first", result.rule().getId());
        assertEquals(15_200L, result.rule().getDurationMs());
    }

    @Test
    public void identicalSavedRuleRemainsAvailableForTesting() {
        Map<String, ProbeRule> drafts = new LinkedHashMap<>();
        ProbeRule saved = rule("saved-rule", 15_000L, "11111111");

        RuleDraftDeduplicator.Result result = RuleDraftDeduplicator.collect(drafts,
                new RuleDocument(1L, Collections.singletonList(saved)),
                rule("new-id", 15_000L, "11111111"));

        assertEquals(RuleDraftDeduplicator.Status.ALREADY_SAVED, result.status());
        assertEquals(1, drafts.size());
        assertTrue(drafts.containsKey("saved-rule"));
        assertEquals("saved-rule", result.rule().getId());
    }

    @Test
    public void differentFingerprintCreatesAnotherDraft() {
        Map<String, ProbeRule> drafts = new LinkedHashMap<>();
        RuleDraftDeduplicator.collect(drafts, RuleDocument.empty(),
                rule("first", 15_000L, "11111111"));

        RuleDraftDeduplicator.collect(drafts, RuleDocument.empty(),
                rule("second", 15_000L, "22222222"));

        assertEquals(2, drafts.size());
    }

    private static ProbeRule rule(String id, long durationMs, String firstHash) {
        return new ProbeRule(id, durationMs, 0L, 5_000L, Arrays.asList(
                fingerprint(0, firstHash), fingerprint(64, firstHash),
                fingerprint(128, firstHash), fingerprint(192, firstHash)),
                new RuleTest("https://example.com/video.m3u8", 342_000L));
    }

    private static RuleFingerprint fingerprint(int phase, String firstHash) {
        return new RuleFingerprint(phase, Arrays.asList(firstHash, "33333333",
                "55555555", "77777777"));
    }
}
