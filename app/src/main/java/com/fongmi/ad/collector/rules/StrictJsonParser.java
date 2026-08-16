/* 小型严格 JSON 读取器：拒绝重复字段、宽松数字、控制字符和尾随内容。 */
package com.fongmi.ad.collector.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StrictJsonParser {
    private final String source;
    private int index;

    private StrictJsonParser(String source) {
        this.source = source;
    }

    static Object parse(String source) {
        if (source == null) throw error("JSON 不能为空");
        StrictJsonParser parser = new StrictJsonParser(source);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.index != source.length()) throw parser.fail("JSON 存在尾随内容");
        return value;
    }

    private Object readValue() {
        if (index >= source.length()) throw fail("JSON 意外结束");
        char token = source.charAt(index);
        if (token == '{') return readObject();
        if (token == '[') return readArray();
        if (token == '"') return readString();
        if (token == '-' || token >= '0' && token <= '9') return readInteger();
        if (consume("true")) return Boolean.TRUE;
        if (consume("false")) return Boolean.FALSE;
        if (consume("null")) return null;
        throw fail("JSON 值无效");
    }

    private Map<String, Object> readObject() {
        index++;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (take('}')) return result;
        while (true) {
            skipWhitespace();
            if (index >= source.length() || source.charAt(index) != '"') {
                throw fail("对象字段名必须是字符串");
            }
            String name = readString();
            if (result.containsKey(name)) throw fail("JSON 字段重复: " + name);
            skipWhitespace();
            require(':');
            skipWhitespace();
            result.put(name, readValue());
            skipWhitespace();
            if (take('}')) return result;
            require(',');
        }
    }

    private List<Object> readArray() {
        index++;
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (take(']')) return result;
        while (true) {
            skipWhitespace();
            result.add(readValue());
            skipWhitespace();
            if (take(']')) return result;
            require(',');
        }
    }

    private String readString() {
        index++;
        StringBuilder result = new StringBuilder();
        while (index < source.length()) {
            char value = source.charAt(index++);
            if (value == '"') return result.toString();
            if (value < 0x20) throw fail("字符串包含控制字符");
            if (value != '\\') {
                result.append(value);
                continue;
            }
            if (index >= source.length()) throw fail("字符串转义不完整");
            char escaped = source.charAt(index++);
            switch (escaped) {
                case '"': case '\\': case '/': result.append(escaped); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u': result.append(readUnicode()); break;
                default: throw fail("字符串转义无效");
            }
        }
        throw fail("字符串未结束");
    }

    private char readUnicode() {
        if (index + 4 > source.length()) throw fail("Unicode 转义不完整");
        int value = 0;
        for (int end = index + 4; index < end; index++) {
            int digit = Character.digit(source.charAt(index), 16);
            if (digit < 0) throw fail("Unicode 转义无效");
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private Long readInteger() {
        int start = index;
        if (source.charAt(index) == '-') index++;
        if (index >= source.length()) throw fail("整数无效");
        if (source.charAt(index) == '0') {
            index++;
            if (index < source.length() && Character.isDigit(source.charAt(index))) {
                throw fail("整数不能包含前导零");
            }
        } else {
            if (!isDigitOneToNine(source.charAt(index))) throw fail("整数无效");
            while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
        }
        if (index < source.length()) {
            char suffix = source.charAt(index);
            if (suffix == '.' || suffix == 'e' || suffix == 'E' || suffix == '+') {
                throw fail("只接受普通十进制整数");
            }
        }
        try {
            return Long.parseLong(source.substring(start, index));
        } catch (NumberFormatException error) {
            throw fail("整数超出范围");
        }
    }

    private void skipWhitespace() {
        while (index < source.length()) {
            char value = source.charAt(index);
            if (value != ' ' && value != '\n' && value != '\r' && value != '\t') return;
            index++;
        }
    }

    private boolean take(char expected) {
        if (index >= source.length() || source.charAt(index) != expected) return false;
        index++;
        return true;
    }

    private void require(char expected) {
        if (!take(expected)) throw fail("缺少 '" + expected + "'");
    }

    private boolean consume(String expected) {
        if (!source.regionMatches(index, expected, 0, expected.length())) return false;
        index += expected.length();
        return true;
    }

    private IllegalArgumentException fail(String message) {
        return error(message + "（位置 " + index + "）");
    }

    private static IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message);
    }

    private static boolean isDigitOneToNine(char value) {
        return value >= '1' && value <= '9';
    }
}
