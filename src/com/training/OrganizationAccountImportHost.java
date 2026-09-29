package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Optional server-pinned import host. No source configuration is accepted over HTTP. */
public final class OrganizationAccountImportHost {
    public static final String MANIFEST_PROPERTY = "account.import.manifest";
    public static final String SHA256_PROPERTY = "account.import.manifest.sha256";
    private static final String BASE = "/api/organization/account-import";
    private static final String UNAVAILABLE = "账号导入未启用或服务器来源配置无法核实，请由管理员核对";
    private static final int MANIFEST_LIMIT = 64 * 1024;
    private static final Set<String> PIN_NAMES = Set.of("candidates", "candidatePolicy", "roleSource", "authorization",
            "audit", "regionReference", "scopeDecision", "preparedPreview");
    // Accessed only under the application's shared mutation lock; one importer per process.
    private static boolean initialized;
    private static String manifestPath, manifestDigest;
    private static OrganizationAccountImport importer;
    private OrganizationAccountImportHost() {}

    /** Empty metadata only. Capture startup options once; do not open a source or create a person. */
    public static void init() throws SQLException {
        synchronized (Api.MUTATION_LOCK) {
            OrganizationAccountImport.init();
            if (!initialized) {
                manifestPath = System.getProperty(MANIFEST_PROPERTY);
                manifestDigest = System.getProperty(SHA256_PROPERTY);
                initialized = true;
            }
        }
    }

    static boolean matches(String path) { return path != null && (path.equals(BASE) || path.startsWith(BASE + "/")); }

    /** Also called before the bounded POST read, so private-route errors authenticate first. */
    static void requireAdmin(Auth.Session supplied) throws Api.ApiException {
        Auth.Session current = Auth.current(supplied);
        if (current == null) throw new Api.ApiException(401, "未登录或会话已过期，请重新登录");
        if (!Auth.isAdmin(current)) throw new Api.ApiException(403, "仅系统管理员可核对和导入账号");
    }

    static boolean handle(HttpExchange exchange, Auth.Session supplied) throws Exception {
        if (!matches(exchange.getRequestURI().getPath())) return false;
        synchronized (Api.MUTATION_LOCK) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            requireAdmin(supplied);
            if (exchange.getRequestURI().getRawQuery() != null)
                throw new Api.ApiException(400, "导入接口不接受查询参数");
            OrganizationAccountImport current = trustedImporter();
            requireAdmin(supplied);
            return current.handle(exchange, supplied);
        }
    }

    /** Trusted host reuse only; never accepts a source path or configuration from a browser. */
    static OrganizationAccountImport trustedImporter() throws Api.ApiException {
        synchronized (Api.MUTATION_LOCK) {
            try {
                if (!initialized) throw invalid();
                // Re-read the independently pinned manifest for every request, including account lookup.
                Config config = readConfig(manifestPath, manifestDigest);
                if (importer == null) {
                    OrganizationAccountImportSource.load(config);
                    importer = new OrganizationAccountImport(config);
                }
                return importer;
            } catch (Exception rejected) {
                importer = null; // A restored file must not revive previous in-memory review tokens.
                throw new Api.ApiException(503, UNAVAILABLE);
            }
        }
    }

    /** Strict, bounded startup manifest parser; package-private for synthetic contract checks. */
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
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Object parsed = Json.parse(text);
            check(parsed instanceof Map<?,?>);
            Object schema = ((Map<?,?>) parsed).get("schema");
            boolean relocated = "M01-SERVER-PIN-MANIFEST-v2".equals(schema);
            check(relocated || "M01-SERVER-PIN-MANIFEST-v1".equals(schema));
            Map<String,Object> value = object(parsed, relocated
                    ? Set.of("schema", "batchKey", "usage", "pins", "originals", "materialRoot")
                    : Set.of("schema", "batchKey", "usage", "pins", "originals"));
            Path materialRoot = relocated ? TrustedSourceFilePins.materialRoot(value.get("materialRoot")) : null;
            check(value.get("batchKey") instanceof String batch && batch.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"));
            check(value.get("usage") instanceof String usage && !usage.isBlank() && usage.length() <= 4096);
            Map<String,Object> pins = object(value.get("pins"), PIN_NAMES);
            check(value.get("originals") instanceof List<?> originals && originals.size() == 3);
            List<FilePin> originalPins = new ArrayList<>();
            for (Object pin : (List<?>) value.get("originals")) originalPins.add(pin(pin, materialRoot));
            Map<String,FilePin> named = new HashMap<>();
            Set<Path> distinct = new HashSet<>(); distinct.add(path);
            Set<Path> logical = new HashSet<>();
            for (String key : PIN_NAMES) named.put(key, pin(pins.get(key), materialRoot));
            for (FilePin pin : named.values()) check(distinct.add(pin.path()) && logical.add(pin.logicalPath()));
            for (FilePin pin : originalPins) check(distinct.add(pin.path()) && logical.add(pin.logicalPath()));
            return new Config((String)value.get("batchKey"), named.get("candidates"), named.get("candidatePolicy"),
                    named.get("roleSource"), named.get("authorization"), named.get("audit"), named.get("regionReference"),
                    named.get("scopeDecision"), named.get("preparedPreview"), originalPins);
        } catch (Exception rejected) { throw invalid(); }
    }
    private static FilePin pin(Object value, Path materialRoot) throws Exception {
        if (materialRoot != null) return TrustedSourceFilePins.pin(value, materialRoot);
        Map<String,Object> pin = object(value, Set.of("path", "sha256"));
        check(pin.get("sha256") instanceof String hash && hash.matches("[0-9a-f]{64}"));
        check(pin.get("path") instanceof String);
        return new FilePin(absolutePath((String)pin.get("path")), (String)pin.get("sha256"));
    }
    private static Path absolutePath(String raw) {
        check(raw != null && !raw.isBlank() && raw.equals(raw.trim()) && raw.length() <= 4096
                && raw.chars().noneMatch(c -> c < 32 || c == 127));
        Path path = Path.of(raw);
        check(path.isAbsolute() && path.equals(path.normalize()));
        return path;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object raw, Set<String> keys) {
        check(raw instanceof Map<?,?> && ((Map<?,?>)raw).keySet().equals(keys));
        return (Map<String,Object>)raw;
    }
    private static void check(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException(UNAVAILABLE); }
}
