package com.training;

import java.util.*;

/**
 * 极简 JSON 解析/序列化工具（零依赖）。
 * 解析结果：Map<String,Object> / List<Object> / String / Double / Boolean / null
 */
public class Json {

    // ---------- 序列化 ----------
    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { escape(sb, (String) o); return; }
        if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                escape(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object it : (Iterable<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, it);
            }
            sb.append(']');
            return;
        }
        escape(sb, o.toString());
    }

    private static void escape(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------- 解析 ----------
    public static Object parse(String s) {
        return new P(s).value();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String s) {
        Object o = parse(s);
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    public static double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v != null) {
            try { return Double.parseDouble(v.toString()); } catch (Exception ignored) {}
        }
        return 0;
    }

    public static long lng(Map<String, Object> m, String k) {
        return (long) num(m, k);
    }

    private static class P {
        final String s; int i;
        P(String s) { this.s = s; }

        Object value() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') { i += 4; return null; }
            return num();
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws();
                String k = str();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }

        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (i < s.length()) {
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return l;
        }

        String str() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }

        Boolean bool() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            i += 5; return Boolean.FALSE;
        }

        Double num() {
            int st = i;
            while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            try { return Double.parseDouble(s.substring(st, i)); }
            catch (Exception e) { return 0d; }
        }
    }
}
