/* 验证公开采集草稿能无损转换为采集器自身的 rules-v1 模型。 */
package com.fongmi.ad.collector.gateway;

import static org.junit.Assert.assertEquals;

import com.fongmi.ad.collector.rules.ProbeRule;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.fongmi.adaudio.probe.tools.FingerprintRuleDraft;
import io.github.fongmi.adaudio.probe.tools.FingerprintSequence;

public final class ProbeDraftMapperTest {
    @Test
    public void mapsAllRulesV1Fields() {
        List<FingerprintSequence> fingerprints = Arrays.asList(
                sequence(0, 7), sequence(64, 6),
                sequence(128, 6), sequence(192, 6));
        FingerprintRuleDraft draft = new FingerprintRuleDraft("ad-sample", 30_000L,
                1_000L, 2_048L, fingerprints,
                "https://example.com/video.m3u8", 12_345L);

        ProbeRule rule = ProbeDraftMapper.toRule(draft);

        assertEquals("ad-sample", rule.getId());
        assertEquals(30_000L, rule.getDurationMs());
        assertEquals(1_000L, rule.getAnchorOffsetMs());
        assertEquals(2_048L, rule.getAnchorDurationMs());
        assertEquals(4, rule.getFingerprints().size());
        assertEquals("https://example.com/video.m3u8", rule.getTest().getUrl());
        assertEquals(12_345L, rule.getTest().getAdStartMs());
    }

    private static FingerprintSequence sequence(int phaseMs, int size) {
        List<String> hashes = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            hashes.add(index == 0 ? "00000000" : index == 1 ? "ffffffff" : "12345678");
        }
        return new FingerprintSequence(phaseMs, hashes);
    }
}
