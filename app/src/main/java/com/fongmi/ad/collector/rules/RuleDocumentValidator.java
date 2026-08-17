/* Probe rules-v1 语义校验器，补足 JSON Schema 无法表达的跨字段与冲突约束。 */
package com.fongmi.ad.collector.rules;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class RuleDocumentValidator {
    private static final long REQUIRED_ANCHOR_DURATION_MS = 5_000L;
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final int[] PHASES = {0, 64, 128, 192};
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{8}");

    private RuleDocumentValidator() {
    }

    public static RuleDocument validate(RuleDocument document) {
        if (document == null) throw new IllegalArgumentException("规则文档不能为空");
        if (document.getRevision() < 1L || document.getRevision() > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("revision 超出 Probe v1 范围");
        }
        if (document.getRules().size() > 1024) throw new IllegalArgumentException("规则数量超过 1024");
        Set<String> ids = new HashSet<>();
        int totalHashes = 0;
        for (ProbeRule rule : document.getRules()) {
            validateRule(rule);
            if (!ids.add(rule.getId())) throw new IllegalArgumentException("规则 ID 重复: " + rule.getId());
            for (RuleFingerprint fingerprint : rule.getFingerprints()) {
                totalHashes += fingerprint.getHashes().size();
                if (totalHashes > 65_536) throw new IllegalArgumentException("指纹总数超过 65536");
            }
        }
        validateConflicts(document.getRules());
        return document;
    }

    private static void validateRule(ProbeRule rule) {
        if (rule == null || rule.getId() == null || !ID.matcher(rule.getId()).matches()) {
            throw new IllegalArgumentException("规则 ID 不符合 Probe v1 约束");
        }
        if (rule.getDurationMs() < REQUIRED_ANCHOR_DURATION_MS
                || rule.getDurationMs() > 600_000L) {
            throw new IllegalArgumentException("规则时长超出范围: " + rule.getId());
        }
        if (rule.getAnchorOffsetMs() < 0L
                || rule.getAnchorDurationMs() != REQUIRED_ANCHOR_DURATION_MS
                || rule.getAnchorOffsetMs() > rule.getDurationMs() - rule.getAnchorDurationMs()) {
            throw new IllegalArgumentException("锚点范围无效: " + rule.getId());
        }
        if (rule.getFingerprints().size() != 4) {
            throw new IllegalArgumentException("指纹相位数量必须为 4: " + rule.getId());
        }
        Map<Integer, RuleFingerprint> byPhase = new HashMap<>();
        for (RuleFingerprint fingerprint : rule.getFingerprints()) {
            if (fingerprint == null || byPhase.put(fingerprint.getPhaseMs(), fingerprint) != null) {
                throw new IllegalArgumentException("指纹相位重复或为空: " + rule.getId());
            }
        }
        for (int phase : PHASES) validateFingerprint(rule, byPhase.get(phase), phase);
        validateTest(rule);
    }

    private static void validateFingerprint(ProbeRule rule, RuleFingerprint fingerprint, int phase) {
        if (fingerprint == null) throw new IllegalArgumentException("缺少指纹相位 " + phase);
        long expected = (rule.getAnchorDurationMs() - phase - 512L) / 256L + 1L;
        if (fingerprint.getHashes().size() != expected || expected < 4L || expected > 64L) {
            throw new IllegalArgumentException("指纹长度与锚点不匹配: " + rule.getId());
        }
        for (String hash : fingerprint.getHashes()) {
            if (hash == null || !HASH.matcher(hash).matches()) {
                throw new IllegalArgumentException("指纹哈希格式无效: " + rule.getId());
            }
        }
        int first = Integer.parseUnsignedInt(fingerprint.getHashes().get(0), 16);
        boolean distinctive = false;
        int limit = Math.min(8, fingerprint.getHashes().size());
        for (int index = 1; index < limit; index++) {
            int value = Integer.parseUnsignedInt(fingerprint.getHashes().get(index), 16);
            if (Integer.bitCount(first ^ value) > 5) {
                distinctive = true;
                break;
            }
        }
        if (!distinctive) throw new IllegalArgumentException("指纹开头区分度不足: " + rule.getId());
    }

    private static void validateTest(ProbeRule rule) {
        RuleTest test = rule.getTest();
        if (test == null) return;
        String url = test.getUrl();
        if (url == null || url.isEmpty() || url.length() > 8192) {
            throw new IllegalArgumentException("测试链接长度无效: " + rule.getId());
        }
        try {
            URI uri = new URI(url).parseServerAuthority();
            String scheme = uri.getScheme();
            if (uri.isOpaque() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("测试链接必须是 HTTP(S): " + rule.getId());
            }
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("测试链接格式无效: " + rule.getId(), error);
        }
        if (test.getAdStartMs() < 0L
                || test.getAdStartMs() > MAX_SAFE_INTEGER - rule.getDurationMs()) {
            throw new IllegalArgumentException("测试起点超出范围: " + rule.getId());
        }
    }

    private static void validateConflicts(List<ProbeRule> rules) {
        for (int left = 0; left < rules.size(); left++) {
            ProbeRule first = rules.get(left);
            List<String> firstMain = phaseZero(first).getHashes();
            for (int right = left + 1; right < rules.size(); right++) {
                ProbeRule second = rules.get(right);
                List<String> secondMain = phaseZero(second).getHashes();
                if (hasPrefixRelation(firstMain, secondMain)
                        && first.getDurationMs() - first.getAnchorOffsetMs()
                        != second.getDurationMs() - second.getAnchorOffsetMs()) {
                    throw new IllegalArgumentException("规则跳转目标冲突: "
                            + first.getId() + " / " + second.getId());
                }
            }
        }
    }

    private static RuleFingerprint phaseZero(ProbeRule rule) {
        for (RuleFingerprint value : rule.getFingerprints()) {
            if (value.getPhaseMs() == 0) return value;
        }
        throw new IllegalArgumentException("规则缺少零相位指纹");
    }

    private static boolean hasPrefixRelation(List<String> first, List<String> second) {
        int limit = Math.min(8, Math.min(first.size(), second.size()));
        for (int index = 0; index < limit; index++) {
            if (!first.get(index).equals(second.get(index))) return false;
        }
        return true;
    }
}
