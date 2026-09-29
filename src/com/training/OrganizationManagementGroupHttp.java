package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.util.Map;

/** Optional HTTP boundary for one reviewed, atomic management-group publication. */
public final class OrganizationManagementGroupHttp {
    public static final int BODY_LIMIT = 4096;
    private static final String BASE = "/api/organization/management-group";
    private static final String UNAVAILABLE = "管理组配置来源尚未启用或无法核实，请由管理员核对";
    private static boolean initialized;
    private static String originalPath, originalDigest, supplementPath, supplementDigest;
    private static OrganizationManagementGroupPublication publication;
    private OrganizationManagementGroupHttp() {}

    /** Empty audit metadata only; startup never loads a roster or publishes roles. */
    public static void init() throws java.sql.SQLException {
        synchronized (Api.MUTATION_LOCK) {
            OrganizationManagementGroupPublication.init();
            if (!initialized) {
                originalPath = System.getProperty(OrganizationAccountImportHost.MANIFEST_PROPERTY);
                originalDigest = System.getProperty(OrganizationAccountImportHost.SHA256_PROPERTY);
                supplementPath = System.getProperty(OrganizationManagementSupplementHttp.MANIFEST_PROPERTY);
                supplementDigest = System.getProperty(OrganizationManagementSupplementHttp.SHA256_PROPERTY);
                initialized = true;
            }
        }
    }

    public static boolean matches(String path) {
        return path != null && (path.equals(BASE) || path.startsWith(BASE + "/"));
    }

    /** Called by Api before content-type inspection and bounded POST-body reading. */
    public static void preflight(HttpExchange exchange) throws Api.ApiException {
        if (!matches(exchange.getRequestURI().getPath())) return;
        synchronized (Api.MUTATION_LOCK) {
            noStore(exchange);
            requireAdmin(exchange, Auth.get(Api.token(exchange)));
            operation(exchange);
        }
    }

    public static boolean handle(HttpExchange exchange, Auth.Session supplied) throws Exception {
        if (!matches(exchange.getRequestURI().getPath())) return false;
        synchronized (Api.MUTATION_LOCK) {
            noStore(exchange);
            Auth.Session actor = requireAdmin(exchange, Auth.get(Api.token(exchange)));
            if (actor != supplied) throw error(401, "登录会话无效或已失效");
            String operation = operation(exchange);
            OrganizationManagementGroupPublication current = current();
            try {
                Map<String,Object> result = switch (operation) {
                    case "preview" -> current.preview(actor);
                    case "received" -> current.received(actor);
                    default -> current.commit(actor, Api.body(exchange));
                };
                if (operation.equals("commit")) {
                    boolean sessionInvalidated = Auth.current(actor) == null;
                    if (sessionInvalidated) Api.clearSessionCookie(exchange);
                    Map<String,Object> response = new java.util.LinkedHashMap<>(result);
                    response.put("sessionInvalidated", sessionInvalidated);
                    Api.ok(exchange, response);
                } else Api.ok(exchange, result);
                return true;
            } catch (Api.ApiException known) {
                if (known.code == 409 || known.code >= 500) publication = null;
                throw known;
            } catch (Exception invalid) {
                publication = null;
                throw unavailable();
            }
        }
    }

    /** No HTTP input can choose paths or source identities. Failed pins discard all old reviews. */
    private static OrganizationManagementGroupPublication current() throws Api.ApiException {
        try {
            if (!initialized) throw new IllegalStateException();
            var original = OrganizationAccountImportHost.readConfig(originalPath, originalDigest);
            var supplement = OrganizationManagementSupplementHttp.readConfig(supplementPath, supplementDigest);
            OrganizationAccountImportSource.load(original);
            OrganizationManagementSupplementSource.load(supplement);
            if (publication == null) publication = new OrganizationManagementGroupPublication(original, supplement);
            return publication;
        } catch (Exception invalid) {
            publication = null;
            throw unavailable();
        }
    }

    /** Strict UTF-8/object parser reused from the equally bounded, established intake boundary. */
    public static Map<String,Object> parseBody(byte[] bytes) throws Api.ApiException {
        return OrganizationManagementSupplementHttp.parseBody(bytes);
    }

    private static String operation(HttpExchange exchange) throws Api.ApiException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals(exchange.getRequestURI().getRawPath())) throw error(400, "接口路径格式不正确");
        if (exchange.getRequestURI().getRawQuery() != null) throw error(400, "管理组配置接口不接受查询参数");
        String operation;
        if (path.equals(BASE + "/preview")) operation = "preview";
        else if (path.equals(BASE + "/received")) operation = "received";
        else if (path.equals(BASE + "/commit")) operation = "commit";
        else throw error(404, "未找到管理组配置入口");
        String method = operation.equals("commit") ? "POST" : "GET";
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            throw error(405, "请求方法不受支持");
        }
        return operation;
    }

    private static Auth.Session requireAdmin(HttpExchange exchange, Auth.Session supplied) throws Api.ApiException {
        Auth.Session actor = Auth.current(supplied);
        if (actor == null) {
            Api.clearSessionCookie(exchange);
            throw error(401, "未登录或会话已过期，请重新登录");
        }
        if (!Auth.isAdmin(actor)) throw error(403, "仅系统管理员可核对和配置管理组");
        return actor;
    }
    private static void noStore(HttpExchange exchange) { exchange.getResponseHeaders().set("Cache-Control", "no-store"); }
    private static Api.ApiException unavailable() { return error(503, UNAVAILABLE); }
    private static Api.ApiException error(int status, String message) { return new Api.ApiException(status, message); }
}
