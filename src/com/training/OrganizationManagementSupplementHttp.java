package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccountImportSource.FilePin;
import static com.training.OrganizationManagementSupplementSource.Config;

/** Thin optional HTTP adapter. All sources are server-pinned; intake owns its transaction. */
public final class OrganizationManagementSupplementHttp {
    public static final String MANIFEST_PROPERTY = "management.supplement.manifest";
    public static final String SHA256_PROPERTY = "management.supplement.manifest.sha256";
    public static final int BODY_LIMIT = 4096;
    private static final String BASE = "/api/organization/management-supplement";
    private static final String UNAVAILABLE = "补充账号接收未启用或服务器来源无法核实，请由管理员核对";
    private static final int MANIFEST_LIMIT = 64 * 1024;
    private static final long MAX_ID = 9007199254740991L;
    private static final Set<String> PIN_NAMES = Set.of("groupsOriginal", "teacherOriginal", "projection", "decision", "originalReceipt");
    // Guarded by the same lock as Auth, users, the original intake and organization publication.
    private static boolean initialized;
    private static String manifestPath, manifestDigest;
    private static OrganizationManagementSupplementHost host;
    private record Operation(String name, long accountId) {}
    private OrganizationManagementSupplementHttp() {}

    /** Capture startup options once. No files, schema, users or configuration are opened/changed. */
    public static void init() {
        synchronized (Api.MUTATION_LOCK) {
            if (!initialized) {
                manifestPath = System.getProperty(MANIFEST_PROPERTY);
                manifestDigest = System.getProperty(SHA256_PROPERTY);
                initialized = true;
            }
        }
    }

    public static boolean matches(String path) {
        return path != null && (path.equals(BASE) || path.startsWith(BASE + "/"));
    }

    /** Api invokes this before inspecting content type or reading any POST bytes. */
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
            Operation operation = operation(exchange);
            OrganizationManagementSupplementHost current = current();
            try {
                Map<String,Object> result = switch (operation.name()) {
                    case "preview" -> current.preview(actor);
                    case "account" -> current.account(actor, operation.accountId());
                    default -> current.commit(actor, Api.body(exchange));
                };
                Api.ok(exchange, result);
                return true;
            } catch (Api.ApiException known) {
                // A source can change again between the adapter check and the intake check.
                // Conservatively discard reviews after any conflict/server failure, never reviving them.
                if (known.code == 409 || known.code >= 500) host = null;
                throw known;
            } catch (Exception invalid) {
                host = null;
                throw unavailable();
            }
        }
    }

    /** The safe association projection is for trusted configuration composition, never an HTTP route. */
    static Map<String,Object> received(Auth.Session supplied) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session actor = Auth.current(supplied);
            if (actor == null) throw error(401, "登录会话无效或已失效");
            if (!Auth.isAdmin(actor)) throw error(403, "仅系统管理员可核对和接收补充账号");
            try { return current().received(actor); }
            catch (Api.ApiException known) { host = null; throw known; }
            catch (Exception invalid) { host = null; throw unavailable(); }
        }
    }

    /** Always revalidate manifest AND all five sources, including on explicit account lookup. */
    private static OrganizationManagementSupplementHost current() throws Api.ApiException {
        try {
            check(initialized);
            Config config = readConfig(manifestPath, manifestDigest);
            OrganizationManagementSupplementSource.load(config);
            if (host == null) host = new OrganizationManagementSupplementHost(config);
            return host;
        } catch (Exception invalid) {
            host = null;
            throw unavailable();
        }
    }

    /** Api calls this with its bounded raw bytes; nested decisions retain their native JSON types. */
    public static Map<String,Object> parseBody(byte[] bytes) throws Api.ApiException {
        if (bytes == null) throw error(400, "请求内容必须为有效的 JSON 对象");
        if (bytes.length > BODY_LIMIT) throw error(413, "请求内容过大");
        try {
            Object parsed = Json.parse(decode(bytes));
            if (!(parsed instanceof Map<?,?>)) throw new IllegalArgumentException();
            @SuppressWarnings("unchecked") Map<String,Object> body = (Map<String,Object>) parsed;
            return body;
        } catch (Exception invalid) { throw error(400, "请求内容必须为有效的 JSON 对象"); }
    }

    /** Package-visible for synthetic parser tests; no HTTP payload can choose this configuration. */
    static Config readConfig(String configuredPath, String expectedDigest) throws Exception {
        try {
            Path path = absolutePath(configuredPath);
            check(expectedDigest != null && expectedDigest.matches("[0-9a-f]{64}"));
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            check(before.isRegularFile() && before.size() > 0 && before.size() <= MANIFEST_LIMIT);
            byte[] bytes;
            try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MANIFEST_LIMIT + 1);
            }
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            check(bytes.length == before.size() && bytes.length <= MANIFEST_LIMIT && after.isRegularFile()
                    && after.size() == before.size() && Objects.equals(before.fileKey(), after.fileKey())
                    && before.lastModifiedTime().equals(after.lastModifiedTime()));
            check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expectedDigest));
            Object parsed = Json.parse(decode(bytes));
            check(parsed instanceof Map<?,?>);
            Object schema = ((Map<?,?>) parsed).get("schema");
            boolean relocated = "M01-MANAGEMENT-SUPPLEMENT-MANIFEST-v2".equals(schema);
            check(relocated || "M01-MANAGEMENT-SUPPLEMENT-MANIFEST-v1".equals(schema));
            Map<String,Object> value = object(parsed, relocated
                    ? Set.of("schema", "batchKey", "pins", "materialRoot")
                    : Set.of("schema", "batchKey", "pins"));
            Path materialRoot = relocated ? TrustedSourceFilePins.materialRoot(value.get("materialRoot")) : null;
            check(value.get("batchKey") instanceof String batch && batch.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"));
            Map<String,Object> pins = object(value.get("pins"), PIN_NAMES);
            Map<String,FilePin> named = new HashMap<>();
            List<Path> distinct = new ArrayList<>(); distinct.add(path);
            Set<Path> logical = new HashSet<>();
            for (String name : PIN_NAMES) {
                FilePin pin = pin(pins.get(name), materialRoot);
                check(logical.add(pin.logicalPath()));
                for (Path other : distinct) check(!pin.path().equals(other) && !Files.isSameFile(pin.path(), other));
                distinct.add(pin.path()); named.put(name, pin);
            }
            return new Config((String)value.get("batchKey"), named.get("groupsOriginal"), named.get("teacherOriginal"),
                    named.get("projection"), named.get("decision"), named.get("originalReceipt"));
        } catch (Exception invalid) { throw new IllegalArgumentException(UNAVAILABLE); }
    }

    private static Operation operation(HttpExchange exchange) throws Api.ApiException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals(exchange.getRequestURI().getRawPath())) throw error(400, "接口路径格式不正确");
        if (exchange.getRequestURI().getRawQuery() != null) throw error(400, "补充接收接口不接受查询参数");
        Operation operation;
        if (path.equals(BASE + "/preview")) operation = new Operation("preview", 0);
        else if (path.equals(BASE + "/commit")) operation = new Operation("commit", 0);
        else if (path.startsWith(BASE + "/accounts/")) {
            String raw = path.substring((BASE + "/accounts/").length());
            if (!raw.matches("[1-9][0-9]{0,15}")) throw error(400, "账号编号格式不正确");
            long id = Long.parseLong(raw);
            if (id > MAX_ID) throw error(400, "账号编号格式不正确");
            operation = new Operation("account", id);
        } else throw error(404, "未找到补充账号接收入口");
        String method = operation.name().equals("commit") ? "POST" : "GET";
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            throw error(405, "请求方法不受支持");
        }
        return operation;
    }

    private static Auth.Session requireAdmin(HttpExchange exchange, Auth.Session supplied) throws Api.ApiException {
        Auth.Session actor = Auth.current(supplied);
        if (actor == null) { Api.clearSessionCookie(exchange); throw error(401, "未登录或会话已过期，请重新登录"); }
        if (!Auth.isAdmin(actor)) throw error(403, "仅系统管理员可核对和接收补充账号");
        return actor;
    }
    private static FilePin pin(Object raw, Path materialRoot) throws Exception {
        if (materialRoot != null) return TrustedSourceFilePins.pin(raw, materialRoot);
        Map<String,Object> pin = object(raw, Set.of("path", "sha256"));
        check(pin.get("path") instanceof String && pin.get("sha256") instanceof String digest && digest.matches("[0-9a-f]{64}"));
        return new FilePin(absolutePath((String)pin.get("path")), (String)pin.get("sha256"));
    }
    private static Path absolutePath(String raw) {
        check(raw != null && !raw.isBlank() && raw.equals(raw.trim()) && raw.length() <= 4096
                && raw.chars().noneMatch(c -> c < 32 || c == 127));
        Path path = Path.of(raw);
        check(path.isAbsolute() && path.equals(path.normalize()));
        return path;
    }
    private static String decode(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object raw, Set<String> keys) {
        check(raw instanceof Map<?,?> && ((Map<?,?>)raw).keySet().equals(keys));
        return (Map<String,Object>) raw;
    }
    private static void check(boolean valid) { if (!valid) throw new IllegalArgumentException(UNAVAILABLE); }
    private static void noStore(HttpExchange exchange) { exchange.getResponseHeaders().set("Cache-Control", "no-store"); }
    private static Api.ApiException unavailable() { return error(503, UNAVAILABLE); }
    private static Api.ApiException error(int code, String message) { return new Api.ApiException(code, message); }
}
