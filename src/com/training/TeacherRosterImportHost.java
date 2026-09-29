package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.sql.SQLException;
import com.training.OrganizationAccountImportSource.Config;

/** Original teacher-page intake; shares the administrator-reviewed account source pins. */
final class TeacherRosterImportHost {
    private static final String BASE = "/api/teacher-roster";
    private static String manifestPath, manifestDigest;
    private static boolean initialized;
    private static TeacherRosterImport importer;
    private TeacherRosterImportHost() {}

    static void init() throws SQLException {
        synchronized (Api.MUTATION_LOCK) {
            TeacherRosterImport.init();
            if (!initialized) {
                manifestPath = System.getProperty(OrganizationAccountImportHost.MANIFEST_PROPERTY);
                manifestDigest = System.getProperty(OrganizationAccountImportHost.SHA256_PROPERTY);
                initialized = true;
            }
        }
    }

    static boolean matches(String path) {
        return path != null && (path.equals(BASE) || path.startsWith(BASE + "/"));
    }

    static void requireAdmin(Auth.Session supplied) throws Api.ApiException {
        Auth.Session current = Auth.current(supplied);
        if (current == null) throw new Api.ApiException(401, "未登录或会话已过期，请重新登录");
        if (!Auth.isAdmin(current)) throw new Api.ApiException(403, "仅系统管理员可接收教师名单");
    }

    static boolean handle(HttpExchange exchange, Auth.Session supplied) throws Exception {
        String path = exchange.getRequestURI().getPath();
        if (!matches(path)) return false;
        synchronized (Api.MUTATION_LOCK) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            requireAdmin(supplied);
            if (exchange.getRequestURI().getRawQuery() != null)
                throw new Api.ApiException(400, "教师名单接收接口不接受查询参数");
            if (!path.equals(BASE + "/preview") && !path.equals(BASE + "/import"))
                throw new Api.ApiException(404, "未找到教师名单接收入口");
            String expected = path.endsWith("/preview") ? "GET" : "POST";
            if (!expected.equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", expected);
                throw new Api.ApiException(405, "请求方式不支持");
            }
            try {
                if (!initialized) throw new IllegalStateException();
                // Reverify even for an existing importer; restoration cannot revive old review tokens.
                Config config = OrganizationAccountImportHost.readConfig(manifestPath, manifestDigest);
                if (importer == null) importer = new TeacherRosterImport(config);
            } catch (Exception invalid) {
                importer = null;
                throw new Api.ApiException(503, "教师名单来源尚未启用或无法核实，请由管理员核对");
            }
            requireAdmin(supplied);
            Api.ok(exchange, expected.equals("GET") ? importer.preview(supplied) : importer.commit(supplied, Api.body(exchange)));
            return true;
        }
    }
}
