package com.training;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** Source key for login quotas; forwarding is trusted only across an explicitly enabled loopback proxy. */
final class LoginRequestPeer {
    private LoginRequestPeer() {}

    static String remote(HttpExchange exchange) throws Api.ApiException {
        if (exchange == null) throw unavailable();
        return resolve(exchange.getRemoteAddress(), exchange.getRequestHeaders(),
                System.getProperty("login.proxy.loopback"));
    }

    static String resolve(InetSocketAddress socket, Headers headers, String mode) throws Api.ApiException {
        boolean proxy = "true".equals(mode);
        if (mode != null && !"false".equals(mode) && !proxy) throw unavailable();
        if (socket == null || socket.getAddress() == null) throw unavailable();
        InetAddress peer = socket.getAddress();
        // Preserve the original direct deployment behavior, without consulting forwarded values.
        if (!proxy) return peer.getHostAddress();
        byte[] address = peer.getAddress();
        if (!loopback(address)) return canonical(address);
        List<String> real = headers == null ? null : headers.get("X-Real-IP");
        if (real == null || real.size() != 1) throw unavailable();
        return canonical(literal(real.get(0)));
    }

    private static boolean loopback(byte[] bytes) {
        if (bytes.length == 4) return (bytes[0] & 255) == 127;
        if (bytes.length != 16) return false;
        if (mapped(bytes)) return (bytes[12] & 255) == 127;
        for (int i = 0; i < 15; i++) if (bytes[i] != 0) return false;
        return bytes[15] == 1;
    }

    private static byte[] literal(String value) throws Api.ApiException {
        if (value == null || value.isEmpty() || value.length() > 45) throw unavailable();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F') || c == ':' || c == '.')) throw unavailable();
        }
        if (value.indexOf(':') < 0) return ipv4(value);
        int compressed = value.indexOf("::");
        if (compressed != value.lastIndexOf("::")) throw unavailable();
        List<Integer> left, right;
        if (compressed < 0) {
            left = groups(value, true); right = List.of();
            if (left.size() != 8) throw unavailable();
        } else {
            left = groups(value.substring(0, compressed), false);
            right = groups(value.substring(compressed + 2), true);
            if (left.size() + right.size() >= 8) throw unavailable();
        }
        byte[] bytes = new byte[16];
        for (int i = 0; i < left.size(); i++) put(bytes, i, left.get(i));
        for (int i = 0; i < right.size(); i++) put(bytes, 8 - right.size() + i, right.get(i));
        return bytes;
    }

    private static List<Integer> groups(String value, boolean dottedTail) throws Api.ApiException {
        List<Integer> words = new ArrayList<>(8);
        if (value.isEmpty()) return words;
        String[] parts = value.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.indexOf('.') >= 0) {
                if (!dottedTail || i != parts.length - 1) throw unavailable();
                byte[] four = ipv4(part);
                words.add(((four[0] & 255) << 8) | (four[1] & 255));
                words.add(((four[2] & 255) << 8) | (four[3] & 255));
            } else {
                if (part.isEmpty() || part.length() > 4) throw unavailable();
                int word = 0;
                for (int j = 0; j < part.length(); j++) {
                    char c = part.charAt(j);
                    int digit = c >= '0' && c <= '9' ? c - '0' :
                            c >= 'a' && c <= 'f' ? c - 'a' + 10 :
                            c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
                    if (digit < 0) throw unavailable();
                    word = (word << 4) | digit;
                }
                words.add(word);
            }
            if (words.size() > 8) throw unavailable();
        }
        return words;
    }

    private static byte[] ipv4(String value) throws Api.ApiException {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw unavailable();
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) throw unavailable();
            int octet = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') throw unavailable();
                octet = octet * 10 + c - '0';
            }
            if (octet > 255) throw unavailable();
            bytes[i] = (byte) octet;
        }
        return bytes;
    }

    private static void put(byte[] bytes, int index, int word) {
        bytes[index * 2] = (byte) (word >>> 8); bytes[index * 2 + 1] = (byte) word;
    }

    private static boolean mapped(byte[] bytes) {
        if (bytes.length != 16) return false;
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return (bytes[10] & 255) == 255 && (bytes[11] & 255) == 255;
    }

    private static String canonical(byte[] bytes) throws Api.ApiException {
        if (bytes.length == 4 || mapped(bytes)) {
            int start = bytes.length - 4;
            return (bytes[start] & 255) + "." + (bytes[start + 1] & 255) + "."
                    + (bytes[start + 2] & 255) + "." + (bytes[start + 3] & 255);
        }
        if (bytes.length != 16) throw unavailable();
        int[] words = new int[8];
        for (int i = 0; i < 8; i++) words[i] = ((bytes[2 * i] & 255) << 8) | (bytes[2 * i + 1] & 255);
        int best = -1, length = 1;
        for (int i = 0; i < 8;) {
            if (words[i] != 0) { i++; continue; }
            int end = i + 1;
            while (end < 8 && words[end] == 0) end++;
            if (end - i > length) { best = i; length = end - i; }
            i = end;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8;) {
            if (i == best) { text.append("::"); i += length; continue; }
            if (text.length() > 0 && text.charAt(text.length() - 1) != ':') text.append(':');
            text.append(Integer.toHexString(words[i++]));
        }
        return text.toString();
    }

    private static Api.ApiException unavailable() {
        return new Api.ApiException(503, "暂时无法核对登录请求来源");
    }
}
