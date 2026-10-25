package com.questjournal.core.json;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, strict, dependency-free JSON reader/writer used by the whole mod (quest packs, player progress,
 * network payloads). Values are plain Java objects:
 * {@code Map<String,Object>} (insertion ordered), {@code List<Object>}, {@code String}, {@code Long},
 * {@code Double}, {@code Boolean} and {@code null}.
 *
 * <p>Why not Gson? A hand-rolled reader lets us (a) enforce a nesting limit so hostile files cannot cause a
 * StackOverflowError, (b) reject duplicate keys, (c) report line/column, and (d) keep the quest logic free of
 * Minecraft/Gson classes so it can be unit-tested without a running game.
 *
 * <p>Comments ({@code //} and {@code /* *}{@code /}) are accepted so hand-written quest files can be documented.
 */
public final class Json {
    public static final int DEFAULT_MAX_DEPTH = 64;

    private Json() {
    }

    /** Wrapper for already-serialised JSON text that must be spliced into output unchanged. */
    public static final class Raw {
        public final String json;

        public Raw(String json) {
            this.json = json;
        }
    }

    public static Object parse(String text) throws JsonException {
        return parse(text, DEFAULT_MAX_DEPTH);
    }

    public static Object parse(String text, int maxDepth) throws JsonException {
        if (text == null) {
            throw new JsonException("No input", 1, 1);
        }
        Parser p = new Parser(text, text.startsWith("\uFEFF") ? 1 : 0, maxDepth);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos < text.length()) {
            throw p.error("Unexpected content after the end of the JSON value");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    // ------------------------------------------------------------------ writer

    public static String write(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value, pretty, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, boolean pretty, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("NaN/Infinity cannot be written as JSON");
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d); // keep 64 as 64, not 64.0
            } else {
                sb.append(d);
            }
        } else if (v instanceof Raw) {
            sb.append(((Raw) v).json);
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, pretty, indent + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                writeValue(sb, e.getValue(), pretty, indent + 1);
            }
            newline(sb, pretty, indent);
            sb.append('}');
        } else if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, pretty, indent + 1);
                writeValue(sb, o, pretty, indent + 1);
            }
            newline(sb, pretty, indent);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value type: " + v.getClass().getName());
        }
    }

    private static void newline(StringBuilder sb, boolean pretty, int indent) {
        if (pretty) {
            sb.append('\n');
            for (int i = 0; i < indent; i++) {
                sb.append("  ");
            }
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        final String s;
        int pos;
        final int maxDepth;

        Parser(String s, int pos, int maxDepth) {
            this.s = s;
            this.pos = pos;
            this.maxDepth = maxDepth;
        }

        JsonException error(String msg) {
            int line = 1;
            int col = 1;
            for (int i = 0; i < pos && i < s.length(); i++) {
                if (s.charAt(i) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new JsonException(msg, line, col);
        }

        void skipWs() throws JsonException {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else if (c == '/' && pos + 1 < s.length() && s.charAt(pos + 1) == '/') {
                    pos += 2;
                    while (pos < s.length() && s.charAt(pos) != '\n') {
                        pos++;
                    }
                } else if (c == '/' && pos + 1 < s.length() && s.charAt(pos + 1) == '*') {
                    int end = s.indexOf("*/", pos + 2);
                    if (end < 0) {
                        throw error("Unterminated comment");
                    }
                    pos = end + 2;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) throws JsonException {
            if (pos >= s.length()) {
                throw error("Unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': word("true"); return Boolean.TRUE;
                case 'f': word("false"); return Boolean.FALSE;
                case 'n': word("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("Unexpected character '" + c + "'");
            }
        }

        void word(String w) throws JsonException {
            if (!s.startsWith(w, pos)) {
                throw error("Expected '" + w + "'");
            }
            pos += w.length();
        }

        Map<String, Object> object(int depth) throws JsonException {
            if (depth >= maxDepth) {
                throw error("Nesting is too deep (limit " + maxDepth + ")");
            }
            pos++; // {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw error("Expected a string key");
                }
                String key = string();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw error("Expected ':' after key \"" + key + "\"");
                }
                pos++;
                skipWs();
                Object v = value(depth + 1);
                if (map.containsKey(key)) {
                    throw error("Duplicate key \"" + key + "\"");
                }
                map.put(key, v);
                skipWs();
                if (pos >= s.length()) {
                    throw error("Unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    pos--;
                    throw error("Expected ',' or '}'");
                }
            }
        }

        List<Object> array(int depth) throws JsonException {
            if (depth >= maxDepth) {
                throw error("Nesting is too deep (limit " + maxDepth + ")");
            }
            pos++; // [
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value(depth + 1));
                skipWs();
                if (pos >= s.length()) {
                    throw error("Unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    pos--;
                    throw error("Expected ',' or ']'");
                }
            }
        }

        String string() throws JsonException {
            pos++; // opening quote
            StringBuilder sb = null;
            int begin = pos;
            while (true) {
                if (pos >= s.length()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    String r = sb == null ? s.substring(begin, pos) : sb.append(s, begin, pos).toString();
                    pos++;
                    return r;
                }
                if (c < 0x20) {
                    throw error("Control character inside string");
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(s, begin, pos);
                    pos++;
                    if (pos >= s.length()) {
                        throw error("Unterminated escape sequence");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw error("Incomplete \\u escape");
                            }
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                int d = Character.digit(s.charAt(pos + i), 16);
                                if (d < 0) {
                                    throw error("Invalid \\u escape");
                                }
                                code = code * 16 + d;
                            }
                            sb.append((char) code);
                            pos += 4;
                            break;
                        default:
                            pos--;
                            throw error("Invalid escape '\\" + e + "'");
                    }
                    begin = pos;
                } else {
                    pos++;
                }
            }
        }

        Object number() throws JsonException {
            int st = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= s.length()) {
                throw error("Invalid number");
            }
            char c = s.charAt(pos);
            if (c == '0') {
                pos++;
                if (pos < s.length() && isDigit(s.charAt(pos))) {
                    throw error("Leading zeros are not allowed");
                }
            } else if (c >= '1' && c <= '9') {
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            } else {
                throw error("Invalid number");
            }
            boolean integral = true;
            if (pos < s.length() && s.charAt(pos) == '.') {
                integral = false;
                pos++;
                int d = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos == d) {
                    throw error("Digits expected after the decimal point");
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                integral = false;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                int d = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos == d) {
                    throw error("Digits expected in exponent");
                }
            }
            String t = s.substring(st, pos);
            if (integral) {
                if (t.length() <= 18) {
                    return Long.valueOf(t);
                }
                BigInteger bi = new BigInteger(t);
                if (bi.bitLength() < 64) {
                    return Long.valueOf(bi.longValue());
                }
            }
            double d = Double.parseDouble(t);
            if (Double.isInfinite(d)) {
                throw error("Number is out of range");
            }
            return Double.valueOf(d);
        }

        static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
