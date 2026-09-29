package com.training;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real Api transport, isolated synthetic H2, optional publication source deliberately absent. */
public final class ManagementGroupHttpBoundaryTest {
    private static final String BASE = "/api/organization/management-group";
    private static final String PASSWORD = "SYNTHETIC-HTTP-PUBLICATION-20260924";
    private static int checks;
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    private static String origin, admin, viewer;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static HttpResponse<String> request(String path, String method, String token, byte[] bytes, String type) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(java.time.Duration.ofSeconds(10));
        if (token != null) builder.header("X-Token", token);
        if (type != null) builder.header("Content-Type", type);
        builder.method(method, bytes == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes));
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static void status(String path, String method, String token, byte[] bytes, String type, int expected, String label) throws Exception {
        var result = request(path, method, token, bytes, type);
        check(result.statusCode() == expected, label + " status " + result.statusCode());
        check(result.headers().firstValue("Cache-Control").orElse("").equals("no-store"), label + " private response is not cached");
        check(!result.body().contains("Exception") && !result.body().contains("/Users/") && !result.body().contains(PASSWORD), label + " no internal path/secret");
    }
    private static byte[] json(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]).toAbsolutePath();
        if (!Files.isDirectory(data)) throw new IllegalArgumentException("fresh synthetic data directory required");
        try (var stream = Files.list(data)) { if (stream.findAny().isPresent()) throw new IllegalArgumentException("empty synthetic data directory required"); }
        System.setProperty("data.dir", data.toString());
        System.setProperty("bootstrap.demo", "false");
        System.setProperty("bootstrap.admin.password", PASSWORD);
        System.setProperty("login.email.mode", "legacy");
        for (String key : List.of(OrganizationAccountImportHost.MANIFEST_PROPERTY, OrganizationAccountImportHost.SHA256_PROPERTY,
                OrganizationManagementSupplementHttp.MANIFEST_PROPERTY, OrganizationManagementSupplementHttp.SHA256_PROPERTY)) System.clearProperty(key);
        HttpServer server = null; ExecutorService executor = null;
        try {
            Db.init();
            Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,?)", "synthetic-viewer", Auth.hash(PASSWORD), "SYNTHETIC VIEWER", "viewer", 1);
            admin = Auth.login("admin", PASSWORD);
            viewer = Auth.login("synthetic-viewer", PASSWORD);
            check(admin != null && viewer != null, "actual isolated Auth sessions");
            String before = Json.write(Db.query("SELECT * FROM users ORDER BY id"));
            long configBefore = Db.count("organization_access_config");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/", Api::handle);
            executor = Executors.newFixedThreadPool(2); server.setExecutor(executor); server.start();
            origin = "http://127.0.0.1:" + server.getAddress().getPort();
            status(BASE + "/preview", "GET", null, null, null, 401, "anonymous");
            status(BASE + "/commit", "POST", null, json("{"), "text/plain", 401, "auth before content type/body");
            status(BASE + "/commit", "POST", viewer, new byte[5000], "application/json", 403, "scope before oversized body");
            status(BASE + "/preview", "GET", viewer, null, null, 403, "viewer private preview");
            status(BASE + "/preview", "GET", admin, null, null, 503, "missing configuration fails closed");
            status(BASE + "/received", "GET", admin, null, null, 503, "missing configuration receipt");
            status(BASE + "/commit", "POST", admin, json("{}"), "application/json", 503, "unconfigured commit creates nothing");
            status(BASE + "/preview?x=1", "GET", admin, null, null, 400, "no query payload");
            status(BASE + "/%70review", "GET", admin, null, null, 400, "no encoded path alias");
            status(BASE + "/other", "GET", admin, null, null, 404, "unknown operation");
            status(BASE + "/preview", "POST", admin, json("{}"), "application/json", 405, "method checked before body");
            status(BASE + "/commit", "GET", admin, null, null, 405, "commit cannot use GET");
            status(BASE + "/commit", "POST", admin, json("{}"), "text/plain", 415, "JSON content type");
            status(BASE + "/commit", "POST", admin, new byte[4097], "application/json", 413, "4096 byte boundary");
            status(BASE + "/commit", "POST", admin, new byte[] {(byte)0xff}, "application/json", 400, "strict UTF8");
            status(BASE + "/commit", "POST", admin, json("[]"), "application/json", 400, "object required");
            status(BASE + "/commit", "POST", admin, json("{\"reviewed\":true,\"reviewed\":false}"), "application/json", 400, "duplicate key rejected");
            status(BASE + "/commit", "POST", admin, json("{} trailing"), "application/json", 400, "trailing JSON rejected");
            check(before.equals(Json.write(Db.query("SELECT * FROM users ORDER BY id"))), "all synthetic users untouched by denied requests");
            check(configBefore == Db.count("organization_access_config"), "no configuration published");
            check(Db.count("organization_management_group_publications") == 0, "no successful receipt invented");
            System.out.println("ManagementGroupHttpBoundary: " + checks + " checks passed; real Api transport, synthetic H2 only; no publication or account activation");
        } finally {
            if (server != null) server.stop(0);
            if (executor != null) executor.shutdownNow();
            Db.exec("SHUTDOWN");
        }
    }
}
