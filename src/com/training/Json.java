package com.training;

import java.util.*;

/**
 * 极简 JSON 解析/序列化工具（零依赖）。
 * 解析结果：Map<String,Object> / List<Object> / String / Double / Boolean / null
 */
public class Json {
    private static final int MAX_NESTING_DEPTH = 100;

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
        if (o instanceof Number) {
            if ((o instanceof Double && !Double.isFinite(((Double) o))) ||
                    (o instanceof Float && !Float.isFinite(((Float) o)))) sb.append("null");
            else sb.append(o);
            return;
        }
        if (o instanceof Boolean) { sb.append(o); return; }
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
        if (s == null) throw new IllegalArgumentException("JSON 不能为空");
        P parser = new P(s);
        Object value = parser.value(0);
        parser.ws();
        if (parser.i != s.length()) throw parser.error("JSON 根值后存在多余内容");
        return value;
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
        if (v instanceof Number) {
            double value = ((Number) v).doubleValue();
            if (!Double.isFinite(value)) throw new IllegalArgumentException(k + " 必须是有限数值");
            return value;
        }
        if (v != null) {
            try {
                double value = Double.parseDouble(v.toString());
                if (!Double.isFinite(value)) throw new IllegalArgumentException(k + " 必须是有限数值");
                return value;
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    public static long lng(Map<String, Object> m, String k) {
        double value = num(m, k);
        if (value != Math.rint(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE)
            throw new IllegalArgumentException(k + " 必须是整数");
        return (long) value;
    }

    private static class P {
        final String s; int i;
        P(String s) { this.s = s; }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "（位置 " + i + "）");
        }

        Object value(int depth) {
            ws();
            if (i >= s.length()) throw error("JSON 值不完整");
            char c = s.charAt(i);
            if (c == '{') return obj(depth + 1);
            if (c == '[') return arr(depth + 1);
            if (c == '"') return str();
            if (c == 't') return literal("true", Boolean.TRUE);
            if (c == 'f') return literal("false", Boolean.FALSE);
            if (c == 'n') return literal("null", null);
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw error("JSON 值格式不正确");
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Map<String, Object> obj(int depth) {
            if (depth > MAX_NESTING_DEPTH) throw error("JSON 嵌套层级过深");
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw error("JSON 对象键必须是字符串");
                String k = str();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw error("JSON 对象键后缺少冒号");
                i++;
                if (m.containsKey(k)) throw error("JSON 对象包含重复键: " + k);
                m.put(k, value(depth));
                ws();
                if (i >= s.length()) throw error("JSON 对象未闭合");
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                if (s.charAt(i) != ',') throw error("JSON 对象成员之间缺少逗号");
                i++;
                ws();
                if (i < s.length() && s.charAt(i) == '}') throw error("JSON 对象不允许尾随逗号");
            }
            return m;
        }

        List<Object> arr(int depth) {
            if (depth > MAX_NESTING_DEPTH) throw error("JSON 嵌套层级过深");
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value(depth));
                ws();
                if (i >= s.length()) throw error("JSON 数组未闭合");
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                if (s.charAt(i) != ',') throw error("JSON 数组元素之间缺少逗号");
                i++;
                ws();
                if (i < s.length() && s.charAt(i) == ']') throw error("JSON 数组不允许尾随逗号");
            }
            return l;
        }

        String str() {
            StringBuilder sb = new StringBuilder();
            if (i >= s.length() || s.charAt(i) != '"') throw error("JSON 字符串缺少引号");
            i++; // opening quote
            boolean closed = false;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') { closed = true; break; }
                if (c < 0x20) throw error("JSON 字符串包含未转义控制字符");
                if (c == '\\') {
                    if (i >= s.length()) throw error("JSON 转义序列不完整");
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
                            if (i + 4 > s.length()) throw error("JSON Unicode 转义不完整");
                            try { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                            catch (NumberFormatException err) { throw error("JSON Unicode 转义格式不正确"); }
                            i += 4;
                            break;
                        default: throw error("JSON 包含未知转义字符");
                    }
                } else sb.append(c);
            }
            if (!closed) throw error("JSON 字符串未闭合");
            return sb.toString();
        }

        Object literal(String expected, Object value) {
            if (!s.startsWith(expected, i)) throw error("JSON 字面量格式不正确");
            i += expected.length();
            return value;
        }

        Double number() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw error("JSON 数字不完整");
            if (s.charAt(i) == '0') {
                i++;
                if (i < s.length() && Character.isDigit(s.charAt(i))) throw error("JSON 数字不允许前导零");
            } else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') {
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            } else throw error("JSON 数字格式不正确");
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                int fractionStart = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                if (fractionStart == i) throw error("JSON 小数部分不完整");
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                int exponentStart = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                if (exponentStart == i) throw error("JSON 指数部分不完整");
            }
            try {
                double value = Double.parseDouble(s.substring(start, i));
                if (!Double.isFinite(value)) throw error("JSON 数字超出有限范围");
                return value;
            } catch (NumberFormatException e) {
                throw error("JSON 数字格式不正确");
            }
        }
    }
}
