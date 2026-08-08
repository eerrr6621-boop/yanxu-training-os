package com.training;

import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 培训全流程管理系统 - 启动入口
 * 用法: java -cp "out;lib/h2.jar" com.training.Main [端口]
 * 默认端口 8080，浏览器访问 http://localhost:8080
 */
public class Main {
    private static Path webRoot;
    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("svg", "image/svg+xml");
        MIME.put("ico", "image/x-icon");
        MIME.put("woff2", "font/woff2");
    }

    public static void main(String[] args) throws Exception {
        configureHttpLimits();
        int port = 8080;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }
        // 初始化数据库（自动建表+示例数据）
        Db.init();

        // 定位 web 目录（支持从项目根目录或 jar 同级启动）
        webRoot = findWebRoot();

        String bindAddress = System.getProperty("bind.address", "0.0.0.0");
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(Integer.getInteger("http.workers", 32)));
        server.createContext("/", ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                if (path.startsWith("/api/")) {
                    Api.handle(ex);
                } else {
                    serveStatic(ex, path);
                }
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    byte[] b = ("服务器错误: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(500, b.length);
                    ex.getResponseBody().write(b);
                    ex.getResponseBody().close();
                } catch (Exception ignored) {}
            } finally {
                ex.close();
            }
        });
        server.start();

        System.out.println("=================================================");
        System.out.println("  研序 · 培训运营中心 已启动");
        System.out.println("  监听地址: " + bindAddress + ":" + port);
        System.out.println("  访问地址: http://localhost:" + port);
        if (Boolean.getBoolean("bootstrap.demo")) {
            System.out.println("  本地体验账号: admin / admin123 (系统管理员)");
            System.out.println("           manager / manager123 (业务管理员)");
            System.out.println("           viewer / viewer123 (只读用户)");
        }
        System.out.println("  数据目录: " + new File(System.getProperty("data.dir", "data")).getAbsolutePath());
        System.out.println("  按 Ctrl+C 停止服务");
        System.out.println("=================================================");

        // 尝试自动打开浏览器
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", "http://localhost:" + port).start();
        } catch (Exception ignored) {}
    }

    /** 给 JDK 内置 HTTP 服务器设置可覆盖的安全默认值，避免慢请求长期占用工作线程。 */
    private static void configureHttpLimits() {
        setDefaultProperty("sun.net.httpserver.maxReqTime", "15");
        setDefaultProperty("sun.net.httpserver.maxRspTime", "30");
        setDefaultProperty("sun.net.httpserver.timerMillis", "1000");
        setDefaultProperty("sun.net.httpserver.maxReqHeaderSize", "65536");
        setDefaultProperty("jdk.httpserver.maxConnections", "256");
    }

    private static void setDefaultProperty(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    private static Path findWebRoot() {
        String[] candidates = {"web", "../web", "./training-system/web"};
        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.isDirectory(p)) return p;
        }
        return Paths.get("web");
    }

    private static void serveStatic(com.sun.net.httpserver.HttpExchange ex, String path) throws IOException {
        if (path == null || path.equals("/")) path = "/index.html";
        // 防目录穿越
        if (path.contains("..")) {
            ex.sendResponseHeaders(403, -1);
            return;
        }
        Path file = webRoot.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webRoot.normalize()) || !Files.exists(file) || Files.isDirectory(file)) {
            byte[] b = "404 Not Found".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, b.length);
            ex.getResponseBody().write(b);
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
        if (path.startsWith("/vendor/three-r171/")) {
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        } else {
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
        }
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }
}
