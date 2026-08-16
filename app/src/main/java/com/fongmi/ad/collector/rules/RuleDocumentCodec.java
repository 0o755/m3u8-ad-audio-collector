/* Probe rules-v1 严格编解码器，只接受合同字段并输出稳定 JSON。 */
package com.fongmi.ad.collector.rules;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RuleDocumentCodec {
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    private RuleDocumentCodec() {
    }

    public static RuleDocument fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("规则文件大小无效");
        }
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xef
                && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf ? 3 : 0;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return fromJson(decoded.toString());
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("规则文件不是严格 UTF-8", error);
        }
    }

    public static RuleDocument fromJson(String json) {
        Map<String, Object> root = object(StrictJsonParser.parse(json), "根节点");
        requireFields(root, "根节点", set("format", "schemaVersion", "revision", "algorithm", "rules"));
        requireString(root, "format", RuleDocument.FORMAT);
        requireLong(root, "schemaVersion", RuleDocument.SCHEMA_VERSION);
        requireString(root, "algorithm", RuleDocument.ALGORITHM);
        long revision = number(root, "revision");
        List<ProbeRule> rules = new ArrayList<>();
        for (Object item : array(root.get("rules"), "rules")) rules.add(readRule(item));
        return RuleDocumentValidator.validate(new RuleDocument(revision, rules));
    }

    public static String toJson(RuleDocument document) {
        RuleDocumentValidator.validate(document);
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"format\": \"").append(RuleDocument.FORMAT)
                .append("\",\n  \"schemaVersion\": 1,\n  \"revision\": ")
                .append(document.getRevision()).append(",\n  \"algorithm\": \"")
                .append(RuleDocument.ALGORITHM).append("\",\n  \"rules\": [");
        for (int index = 0; index < document.getRules().size(); index++) {
            if (index > 0) json.append(',');
            appendRule(json, document.getRules().get(index));
        }
        json.append("\n  ]\n}\n");
        if (json.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("规则文件超过 4 MiB");
        }
        return json.toString();
    }

    private static ProbeRule readRule(Object value) {
        Map<String, Object> object = object(value, "rule");
        Set<String> allowed = set("id", "durationMs", "anchorOffsetMs", "anchorDurationMs",
                "fingerprints", "test");
        requireAllowedAndPresent(object, "rule", allowed,
                set("id", "durationMs", "anchorOffsetMs", "anchorDurationMs", "fingerprints"));
        List<RuleFingerprint> fingerprints = new ArrayList<>();
        for (Object item : array(object.get("fingerprints"), "fingerprints")) {
            Map<String, Object> fingerprint = object(item, "fingerprint");
            requireFields(fingerprint, "fingerprint", set("phaseMs", "hashes"));
            List<String> hashes = new ArrayList<>();
            for (Object hash : array(fingerprint.get("hashes"), "hashes")) {
                if (!(hash instanceof String)) throw new IllegalArgumentException("hash 必须是字符串");
                hashes.add((String) hash);
            }
            long phase = number(fingerprint, "phaseMs");
            if (phase < Integer.MIN_VALUE || phase > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("phaseMs 超出整数范围");
            }
            fingerprints.add(new RuleFingerprint((int) phase, hashes));
        }
        RuleTest test = object.containsKey("test") ? readTest(object.get("test")) : null;
        return new ProbeRule(string(object, "id"), number(object, "durationMs"),
                number(object, "anchorOffsetMs"), number(object, "anchorDurationMs"),
                fingerprints, test);
    }

    private static RuleTest readTest(Object value) {
        Map<String, Object> test = object(value, "test");
        requireFields(test, "test", set("url", "adStartMs"));
        return new RuleTest(string(test, "url"), number(test, "adStartMs"));
    }

    private static void appendRule(StringBuilder json, ProbeRule rule) {
        json.append("\n    {\n      \"id\": ");
        appendString(json, rule.getId());
        json.append(",\n      \"durationMs\": ").append(rule.getDurationMs())
                .append(",\n      \"anchorOffsetMs\": ").append(rule.getAnchorOffsetMs())
                .append(",\n      \"anchorDurationMs\": ").append(rule.getAnchorDurationMs());
        if (rule.getTest() != null) {
            json.append(",\n      \"test\": {\n        \"url\": ");
            appendString(json, rule.getTest().getUrl());
            json.append(",\n        \"adStartMs\": ").append(rule.getTest().getAdStartMs())
                    .append("\n      }");
        }
        json.append(",\n      \"fingerprints\": [");
        for (int index = 0; index < rule.getFingerprints().size(); index++) {
            RuleFingerprint fingerprint = rule.getFingerprints().get(index);
            if (index > 0) json.append(',');
            json.append("\n        { \"phaseMs\": ").append(fingerprint.getPhaseMs())
                    .append(", \"hashes\": [");
            for (int hash = 0; hash < fingerprint.getHashes().size(); hash++) {
                if (hash > 0) json.append(", ");
                appendString(json, fingerprint.getHashes().get(hash));
            }
            json.append("] }");
        }
        json.append("\n      ]\n    }");
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': json.append("\\\""); break;
                case '\\': json.append("\\\\"); break;
                case '\b': json.append("\\b"); break;
                case '\f': json.append("\\f"); break;
                case '\n': json.append("\\n"); break;
                case '\r': json.append("\\r"); break;
                case '\t': json.append("\\t"); break;
                default:
                    if (character < 0x20) json.append(String.format("\\u%04x", (int) character));
                    else json.append(character);
            }
        }
        json.append('"');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map)) throw new IllegalArgumentException(label + " 必须是对象");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String label) {
        if (!(value instanceof List)) throw new IllegalArgumentException(label + " 必须是数组");
        return (List<Object>) value;
    }

    private static String string(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof String)) throw new IllegalArgumentException(name + " 必须是字符串");
        return (String) value;
    }

    private static long number(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof Long)) throw new IllegalArgumentException(name + " 必须是整数");
        return (Long) value;
    }

    private static void requireString(Map<String, Object> object, String name, String expected) {
        if (!expected.equals(string(object, name))) throw new IllegalArgumentException(name + " 不受支持");
    }

    private static void requireLong(Map<String, Object> object, String name, long expected) {
        if (number(object, name) != expected) throw new IllegalArgumentException(name + " 不受支持");
    }

    private static void requireFields(Map<String, Object> object, String label, Set<String> fields) {
        requireAllowedAndPresent(object, label, fields, fields);
    }

    private static void requireAllowedAndPresent(Map<String, Object> object, String label,
                                                 Set<String> allowed, Set<String> required) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw new IllegalArgumentException(label + " 包含未知字段: " + key);
        }
        for (String key : required) {
            if (!object.containsKey(key)) throw new IllegalArgumentException(label + " 缺少字段: " + key);
        }
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
