/* 验证规则测试使用点击测试时的跳过模式，不沿用旧播放请求的模式。 */
package com.fongmi.ad.collector.gateway;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
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

public final class RuleTestModeTest {
    @Test
    public void testModeOverridesPlaybackSnapshot() {
        assertTrue(ProbeCollectorGateway.resolveAutomaticSkip(true, true, false));
        assertFalse(ProbeCollectorGateway.resolveAutomaticSkip(true, false, true));
    }

    @Test
    public void normalDetectionKeepsPlaybackSnapshot() {
        assertTrue(ProbeCollectorGateway.resolveAutomaticSkip(false, false, true));
        assertFalse(ProbeCollectorGateway.resolveAutomaticSkip(false, true, false));
    }

    @Test
    public void pendingDraftTestDoesNotMergeConflictingStoredRules() {
        ProbeRule storedRule = rule("stored", 15_000L, "11111111");
        ProbeRule draft = rule("draft", 15_400L, "11111111");
        RuleDocument stored = new RuleDocument(3L, Collections.singletonList(storedRule));
        Map<String, ProbeRule> drafts = new LinkedHashMap<>();
        drafts.put(draft.getId(), draft);

        RuleDocument selected = ProbeCollectorGateway.selectRuleTestDocument(
                stored, drafts, draft.getId(), draft);

        assertEquals(3L, selected.getRevision());
        assertEquals(1, selected.getRules().size());
        assertSame(draft, selected.getRules().get(0));
    }

    @Test
    public void storedRuleTestKeepsStoredDocument() {
        ProbeRule storedRule = rule("stored", 15_000L, "11111111");
        RuleDocument stored = new RuleDocument(3L, Collections.singletonList(storedRule));

        assertSame(stored, ProbeCollectorGateway.selectRuleTestDocument(
                stored, Collections.emptyMap(), storedRule.getId(), storedRule));
    }

    @Test
    public void pendingManualMatchExpiresAtAdEnd() {
        CollectorGateway.Match match = new CollectorGateway.Match(
                "draft", 342_000L, 357_370L, false);

        assertFalse(ProbeCollectorGateway.isPendingMatchExpired(match, 357_369L));
        assertTrue(ProbeCollectorGateway.isPendingMatchExpired(match, 357_370L));
    }

    private static ProbeRule rule(String id, long durationMs, String firstHash) {
        RuleFingerprint fingerprint = new RuleFingerprint(0, Arrays.asList(
                firstHash, "33333333", "55555555", "77777777"));
        return new ProbeRule(id, durationMs, 0L, 5_000L,
                Arrays.asList(fingerprint, fingerprint, fingerprint, fingerprint),
                new RuleTest("https://example.com/video.m3u8", 342_000L));
    }
}
