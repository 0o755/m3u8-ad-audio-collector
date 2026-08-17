/* rules-v1 编解码与断代行为测试，确保旧字段不会被静默接受。 */
package com.fongmi.ad.collector.rules;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class RuleDocumentCodecTest {
    @Test
    public void roundTripPreservesProbeV1Document() {
        RuleDocument source = new RuleDocument(7L, Collections.singletonList(rule("ad-one", 5_000L)));

        String json = RuleDocumentCodec.toJson(source);
        RuleDocument decoded = RuleDocumentCodec.fromJson(json);

        assertEquals(7L, decoded.getRevision());
        assertEquals(1, decoded.getRules().size());
        assertEquals("https://example.com/video.m3u8",
                decoded.getRules().get(0).getTest().getUrl());
        assertTrue(json.contains("\"algorithm\": \"spectral-sequence-v1\""));
    }

    @Test
    public void rejectsLegacyRootFields() {
        String json = RuleDocumentCodec.toJson(new RuleDocument(1L, Collections.emptyList()));
        json = json.replace("\"rules\": [", "\"testUrls\": {},\n  \"rules\": [");

        assertRejected(json, "未知字段");
    }

    @Test
    public void rejectsDuplicateFields() {
        String json = "{\"format\":\"ad-audio-probe-rules\","
                + "\"format\":\"ad-audio-probe-rules\",\"schemaVersion\":1,"
                + "\"revision\":1,\"algorithm\":\"spectral-sequence-v1\",\"rules\":[]}";

        assertRejected(json, "重复");
    }

    @Test
    public void mergeOverwritesSameIdAndIncrementsRevision() {
        RuleDocument local = new RuleDocument(3L, Collections.singletonList(rule("same", 5_000L)));
        RuleDocument incoming = new RuleDocument(9L, Collections.singletonList(rule("same", 6_000L)));

        RuleDocument merged = RuleDocumentMerger.merge(local, incoming);

        assertEquals(10L, merged.getRevision());
        assertEquals(1, merged.getRules().size());
        assertEquals(6_000L, merged.getRules().get(0).getDurationMs());
    }

    private static ProbeRule rule(String id, long durationMs) {
        List<RuleFingerprint> fingerprints = new ArrayList<>();
        List<String> windowHashes = Arrays.asList(
                "00000000", "ffffffff", "12345678", "87654321", "abcdef01", "10fedcba");
        for (int phase : Arrays.asList(0, 64, 128, 192)) {
            int windowCount = (5_000 - phase - 512) / 256 + 1;
            List<String> hashes = new ArrayList<>();
            for (int index = 0; index < windowCount; index++) {
                hashes.add(windowHashes.get(index % windowHashes.size()));
            }
            fingerprints.add(new RuleFingerprint(phase, hashes));
        }
        return new ProbeRule(id, durationMs, 0L, 5_000L, fingerprints,
                new RuleTest("https://example.com/video.m3u8", 12_000L));
    }

    private static void assertRejected(String json, String expected) {
        try {
            RuleDocumentCodec.fromJson(json);
            fail("应拒绝无效规则文档");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }
}
