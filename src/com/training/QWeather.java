package com.training;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/** HTTPS provider receives province/city only. GeoAPI responses are never retained. */
final class QWeather {
    interface Transport { Map<String,Object> get(String path) throws Exception; }
    private final String host, issuer, project, keyId;
    private final PrivateKey key;
    private long quotaHour, lastRequest;
    private int requests;
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> { Thread t = new Thread(runnable,"weather-deadline"); t.setDaemon(true); return t; });
        executor.setRemoveOnCancelPolicy(true); return executor;
    }
    QWeather() throws Exception {
        host = System.getenv("QWEATHER_API_HOST");
        if (!validHost(host)) throw new IOException("Invalid weather host");
        issuer = identifier("QWEATHER_DEVELOPER_ID"); project = identifier("QWEATHER_PROJECT_ID"); keyId = identifier("QWEATHER_KEY_ID");
        String file = System.getenv("QWEATHER_PRIVATE_KEY_PATH");
        if (file == null) throw new IOException("Missing weather credential");
        Path path = Paths.get(file);
        if (!path.isAbsolute() || Files.size(path)>4096) throw new IOException("Invalid weather credential");
        String pem = Files.readString(path, StandardCharsets.US_ASCII).replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }
    static boolean validHost(String host) { return host != null && host.length() <= 253 && host.matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+qweatherapi\\.com"); }
    private static String identifier(String name) throws IOException {
        String value = System.getenv(name); if (value == null || !value.matches("[A-Za-z0-9_-]{5,64}")) throw new IOException("Missing weather identity"); return value;
    }
    Map<String,Object> current(Weather.City city) throws Exception { return current(city, this::request); }
    static Map<String,Object> current(Weather.City city, Transport transport) throws Exception {
        String query = "/geo/v2/city/lookup?location=" + URLEncoder.encode(city.name(), StandardCharsets.UTF_8) + "&adm=" + URLEncoder.encode(city.province(), StandardCharsets.UTF_8) + "&range=cn&number=1&lang=zh";
        Map<String,Object> geo = transport.get(query);
        if (!"200".equals(geo.get("code")) || !(geo.get("location") instanceof List<?> locations) || locations.size()!=1 || !(locations.get(0) instanceof Map<?,?> place)) throw new IOException("Unknown city");
        if (!"中国".equals(place.get("country")) || !city.province().equals(OfflineCities.normalize(String.valueOf(place.get("adm1")))) ||
            !city.name().equals(OfflineCities.canonical(city.province(), String.valueOf(place.get("name"))))) throw new IOException("Ambiguous city");
        double lat = Double.parseDouble(String.valueOf(place.get("lat"))), lon = Double.parseDouble(String.valueOf(place.get("lon")));
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < 3 || lat > 54 || lon < 73 || lon > 136) throw new IOException("Unknown coordinates");
        String weatherPath = String.format(Locale.ROOT, "/weather/v1/current/%.2f/%.2f?lang=zh", lat, lon);
        // geo/place and coordinates are request-local only, never saved or indexed.
        return parseCurrent(transport.get(weatherPath));
    }
    static Map<String,Object> parseCurrent(Map<String,Object> value) throws IOException {
        if (!(value.get("temperature") instanceof Map<?,?> temperature) || !(value.get("condition") instanceof Map<?,?> condition) || !(value.get("metadata") instanceof Map<?,?> metadata)) throw new IOException("Invalid weather");
        if (!(temperature.get("value") instanceof Number number) || !"°C".equals(temperature.get("unit"))) throw new IOException("Invalid temperature");
        double temp = number.doubleValue(); String text = Weather.safeText(condition.get("text"), 24);
        if (!Double.isFinite(temp) || temp < -90 || temp > 65 || text.isEmpty()) throw new IOException("Invalid weather values");
        if (!(metadata.get("attributions") instanceof List<?> attributes) || attributes.isEmpty() || attributes.size() > 5) throw new IOException("Missing attribution");
        List<String> attributions = new ArrayList<>();
        for (Object attribute : attributes) {
            if (!(attribute instanceof String url) || url.length() > 512) throw new IOException("Invalid attribution");
            try { URI link = URI.create(url); if (!"https".equals(link.getScheme()) || link.getHost()==null || link.getUserInfo()!=null || link.getPort()!=-1) throw new IOException("Invalid attribution"); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid attribution"); }
            attributions.add(url);
        }
        // v1 has no observation timestamp. Label fetchedAt as retrieval, not observation time.
        return Map.of("status", "ok", "temperature", temp, "condition", text, "fetchedAt", Instant.now().toString(), "source", "QWeather", "attributions", List.copyOf(attributions));
    }
    private synchronized void quota() throws IOException, InterruptedException {
        long now = System.currentTimeMillis(), hour = now / 3_600_000;
        if (quotaHour != hour) { quotaHour = hour; requests=0; }
        if (requests >= 40) throw new IOException("Weather request limit");
        long delay = 550 - (now-lastRequest); if (delay > 0) Thread.sleep(delay);
        requests++; lastRequest = System.currentTimeMillis();
    }
    private String jwt() throws Exception {
        long now = Instant.now().getEpochSecond(); Base64.Encoder encode = Base64.getUrlEncoder().withoutPadding();
        String header = encode.encodeToString(Json.write(Map.of("alg","EdDSA","kid",keyId)).getBytes(StandardCharsets.UTF_8));
        String payload = encode.encodeToString(Json.write(Map.of("iss",issuer,"sub",project,"iat",now-30,"exp",now+600)).getBytes(StandardCharsets.UTF_8));
        String input = header + "." + payload;
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(key); signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encode.encodeToString(signature.sign());
    }
    private Map<String,Object> request(String path) throws Exception {
        quota();
        if (!(path.startsWith("/geo/v2/city/lookup?") || path.startsWith("/weather/v1/current/"))) throw new IOException("Invalid weather path");
        HttpURLConnection connection = (HttpURLConnection) URI.create("https://" + host + path).toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(1500); connection.setReadTimeout(2000);
        connection.setRequestProperty("Authorization", "Bearer " + jwt()); connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        ScheduledFuture<?> deadline = DEADLINES.schedule(connection::disconnect, 4, TimeUnit.SECONDS);
        try {
            if (connection.getResponseCode()!=200) throw new IOException("Weather unavailable");
            try (InputStream raw=connection.getInputStream(); InputStream input="gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw) {
                byte[] bytes=input.readNBytes(65_537); if(bytes.length>65_536) throw new IOException("Weather response too large");
                return Json.parseMap(new String(bytes, StandardCharsets.UTF_8));
            }
        } finally { deadline.cancel(false); connection.disconnect(); }
    }
}
