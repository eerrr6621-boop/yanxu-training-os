package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Optional local IP lookup + city-only weather. Never part of Api.MUTATION_LOCK. */
final class Weather {
    record City(String province, String name) {}
    interface Locator { City find(String ip) throws Exception; }
    interface Provider { Map<String,Object> current(City city) throws Exception; }
    record Entry(Map<String,Object> value, long expires) {}
    private static final boolean TRUST = "true".equals(System.getenv("YANXU_WEATHER_TRUST_LOOPBACK_PROXY")) &&
            Set.of("127.0.0.1", "::1").contains(System.getProperty("bind.address", "0.0.0.0"));
    private static final Weather SERVICE = configured();
    private final Locator locator;
    private final Provider provider;
    private final Map<City, Entry> cities = new HashMap<>();
    private final Set<City> pending = new HashSet<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> { Thread t = new Thread(runnable, "weather-background"); t.setDaemon(true); return t; });
    Weather(Locator locator, Provider provider) { this.locator = locator; this.provider = provider; worker.allowCoreThreadTimeOut(true); }
    private static Weather configured() {
        if ("true".equals(System.getenv("YANXU_WEATHER_ENABLED"))) try {
            QWeather upstream = new QWeather();
            OfflineCities local = new OfflineCities();
            Runtime.getRuntime().addShutdownHook(new Thread(local::close, "weather-locator-close"));
            return new Weather(local::find, upstream::current);
        } catch (Exception ignored) { System.err.println("Weather configuration unavailable; primary services remain enabled."); }
        return new Weather(null, null);
    }
    static Map<String,Object> status(String status) { return Map.of("status", status); }
    static void handle(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "private, no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (!"GET".equals(ex.getRequestMethod())) { ex.getResponseHeaders().set("Allow", "GET"); ex.sendResponseHeaders(405, -1); return; }
        // Public login-page widget. No browser overrides; provider has a hard server-wide budget.
        if (ex.getRequestURI().getRawQuery() != null) { ex.sendResponseHeaders(400, -1); return; }
        String ip = clientIp(ex.getRemoteAddress().getAddress(), ex.getRequestHeaders().get("X-Real-IP"), TRUST);
        byte[] body = Json.write(SERVICE.lookup(ip)).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, body.length); ex.getResponseBody().write(body);
    }
    static String clientIp(InetAddress peer, List<String> forwarded, boolean trust) {
        String raw = peer.getHostAddress();
        if (peer.isLoopbackAddress() && trust) {
            if (forwarded == null || forwarded.size() != 1) return "";
            raw = forwarded.get(0);
        }
        return publicIp(raw);
    }
    /** Numeric literals only: no DNS lookup of caller-supplied hostnames. */
    static String publicIp(String raw) {
        if (raw == null || raw.length() > 45 || raw.contains("%")) return "";
        try {
            if (raw.contains(":")) {
                if (!raw.matches("[0-9a-fA-F:]+")) return "";
                InetAddress ip = InetAddress.getByName(raw); byte[] b = ip.getAddress();
                if (b.length != 16 || (b[0] & 0xe0) != 0x20 ||
                    ((b[0]&255)==0x20 && (b[1]&255)==0x01 && ((b[2]&255)<2 || ((b[2]&255)==0x0d && (b[3]&255)==0xb8))) ||
                    ((b[0]&255)==0x20 && (b[1]&255)==0x02) || ((b[0]&255)==0x3f && (b[1]&255)==0xff)) return "";
                return ip.getHostAddress();
            }
            if (!raw.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return "";
            int[] a = Arrays.stream(raw.split("\\.")).mapToInt(Integer::parseInt).toArray();
            for (int n : a) if (n > 255) return "";
            if (a[0]==0 || a[0]==10 || a[0]==127 || a[0]>=224 || (a[0]==100 && a[1]>=64 && a[1]<=127) ||
                (a[0]==169 && a[1]==254) || (a[0]==172 && a[1]>=16 && a[1]<=31) ||
                (a[0]==192 && (a[1]==168 || a[1]==0 || (a[1]==88 && a[2]==99))) ||
                (a[0]==198 && (a[1]==18 || a[1]==19 || (a[1]==51 && a[2]==100))) ||
                (a[0]==203 && a[1]==0 && a[2]==113)) return "";
            return raw;
        } catch (Exception ignored) { return ""; }
    }
    Map<String,Object> lookup(String ip) {
        if (locator == null || provider == null) return status("not_configured");
        ip = publicIp(ip);
        if (ip.isEmpty()) return status("location_unavailable");
        City city;
        try { city = locator.find(ip); } catch (Exception ignored) { return status("location_unavailable"); }
        if (city == null || safeText(city.province, 30).isEmpty() || safeText(city.name, 30).isEmpty()) return status("location_unavailable");
        synchronized (this) {
            long now = System.currentTimeMillis();
            cities.entrySet().removeIf(e -> e.getValue().expires < now);
            Entry cached = cities.get(city);
            if (cached != null) return cached.value;
            if (pending.contains(city)) return status("loading");
            if (cities.size() + pending.size() >= 256 || pending.size() >= 17) return status("unavailable");
            pending.add(city);
            try { worker.execute(() -> load(city)); }
            catch (RejectedExecutionException ignored) { pending.remove(city); return status("unavailable"); }
            return status("loading");
        }
    }
    private void load(City city) {
        Map<String,Object> result = status("unavailable");
        try {
            result = new LinkedHashMap<>(provider.current(city));
            result.put("city", city.name); result.put("district", ""); result.put("accuracy", "network_estimate");
        } catch (Exception ignored) { /* No keys, city lookup responses or IPs in logs. */ }
        synchronized (this) {
            cities.put(city, new Entry(Collections.unmodifiableMap(result), System.currentTimeMillis() + ("ok".equals(result.get("status")) ? 1_200_000 : 60_000)));
            pending.remove(city);
        }
    }
    static String safeText(Object value, int max) {
        if (!(value instanceof String s) || s.length() > max || !s.matches("[\\p{L}\\p{N} .:()（）+\\-]*")) return "";
        return s.trim();
    }
    void close() { worker.shutdownNow(); }
}
