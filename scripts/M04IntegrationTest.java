package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import static com.training.OrganizationAccess.*;

/** Independent integration contract checks: real Auth, fresh synthetic H2, no HTTP server. */
public final class M04IntegrationTest {
    private static int checks;
    private static final String PASSWORD = "SYNTHETIC-M04-INTEGRATION-20260922";
    private static final String ROOT = "/api/course-catalog/scopes";
    private static Auth.Session admin, manager, reader, other, colleague, unbound;
    private static String adminToken, managerToken, readerToken, otherToken, colleagueToken, unboundToken;
    private static String scope, otherScope;
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static synchronized void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static void rejects(int expected, Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected " + expected + ": " + label); }
        catch (Api.ApiException e) { check(e.code == expected, label + " (actual " + e.code + ")"); }
    }
    private static void nestedRejected(Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Nested transaction accepted: " + label); }
        catch (IllegalStateException expected) { check(true, label); }
    }
    private static void corruptRejected(Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Corrupt persisted data accepted: " + label); }
        catch (IllegalStateException expected) { check(true, label); }
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> value, String key) {
        return (List<Map<String, Object>>) value.get(key);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> cloneMap(Map<String, Object> value) {
        return (Map<String, Object>) Json.parse(Json.write(value));
    }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static Configuration identity(String version) {
        Set<String> roles = Set.of("MAINTAIN", "READER");
        RelationRule optional = new RelationRule(false, roles, false, false);
        return new Configuration(version, new CodeRules("[0-9]{3}", "[0-9]{4}", "[A-Z]+"), roles,
                List.of(new Organization("001", null, true), new Organization("002", null, true)),
                List.of(new Person("0001", "001", Set.of(), null, null, Set.of("MAINTAIN"), true),
                        new Person("0002", "001", Set.of(), null, null, Set.of("READER"), true),
                        new Person("0003", "002", Set.of(), null, null, Set.of("MAINTAIN"), true),
                        new Person("0004", "001", Set.of(), null, null, Set.of("MAINTAIN"), true)),
                List.of(new RoleRelations("MAINTAIN", optional, optional), new RoleRelations("READER", optional, optional)),
                List.of(new AccountBinding(2, "0001", true), new AccountBinding(3, "0002", true),
                        new AccountBinding(4, "0003", true), new AccountBinding(5, "0004", true)),
                List.of(new Grant("MAINTAIN_READ", "MAINTAIN", "catalog.read", Action.VIEW, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("MAINTAIN_WRITE", "MAINTAIN", "catalog.manage", Action.HANDLE, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("READER_READ", "READER", "catalog.read", Action.VIEW, Effect.ALLOW, Scope.OWN_ORG, Set.of())));
    }
    private static Map<String, Object> catalog(String version) {
        return Json.parseMap("""
            {"schema_version":"m04_catalog_v1","catalog_version":"REPLACED",
             "courses":[{"course_code":"001","course_name":"合成课程甲","active":true},
                        {"course_code":"002","course_name":"合成课程乙","active":true}],
             "teachers":[{"teacher_code":"0001","teacher_level":"L1","city":"合成甲城"},
                         {"teacher_code":"0002","teacher_level":"L2","city":"合成乙城"}],
             "certifications":[{"teacher_code":"0001","course_code":"001","status":"certified","source_ref":"SYNTHETIC-SOURCE-01","valid_from":"2026-01-01","valid_to":"2026-12-31"},
                               {"teacher_code":"0002","course_code":"001","status":"unknown","source_ref":"","valid_from":"","valid_to":""}]}
            """.replace("REPLACED", version));
    }
    private static List<Map<String, Object>> bindings() {
        return new ArrayList<>(List.of(map("teacher_code", "0001", "teacher_id", 101), map("teacher_code", "0002", "teacher_id", 102)));
    }
    private static Map<String, Object> request() {
        return map("course_code", "001", "as_of", "2026-09-22", "accepted_levels", List.of(), "allowed_cities", List.of());
    }
    private static Map<String, Object> trusted(Auth.Session session, long expected, Map<String, Object> query) throws Exception {
        return CourseCatalogIntegration.qualification(session, Long.parseLong(scope), expected, query);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> context(Map<String, Object> result) {
        check(result.get("qualification_context") instanceof Map, "trusted qualification contains explicit context");
        return (Map<String, Object>) result.get("qualification_context");
    }
    private static void changedContext(Map<String, Object> before, Map<String, Object> after, String label) {
        check(!Objects.equals(before.get("context_id"), after.get("context_id")), label + " invalidates context");
    }
    private static void changedTeacherFacts(Map<String, Object> before, Map<String, Object> after, String label) {
        changedContext(before, after, label);
        check(!Objects.equals(before.get("teacher_facts_id"), after.get("teacher_facts_id")), label + " changes current teacher facts digest");
        check(Objects.equals(before.get("criteria_id"), after.get("criteria_id")) && Objects.equals(before.get("catalog_snapshot_id"), after.get("catalog_snapshot_id")), label + " does not invent new query or catalog");
    }
    private static Map<String, Object> previewBody(long version, String catalogVersion) {
        return map("expected_version", version, "catalog", catalog(catalogVersion), "bindings", bindings(), "change_comment", "合成测试修改说明 " + catalogVersion);
    }
    private static Map<String, Object> confirmBody(String batch, long version) {
        return map("batch_id", batch, "expected_version", version, "confirm", true);
    }
    private static String url(String suffix) { return ROOT + "/" + scope + suffix; }
    @SuppressWarnings("unchecked") private static Map<String, Object> call(String method, String path, String token, Auth.Session session, Map<String, Object> body) throws Exception {
        Exchange ex = new Exchange(method, path, token, body);
        check(CourseCatalogIntegration.handle(ex, session), "route recognized " + method + " " + path);
        Map<String, Object> envelope = response(ex);
        check(envelope.get("data") instanceof Map, "JSON response has object data");
        return (Map<String, Object>) envelope.get("data");
    }
    private static Map<String, Object> managerCall(String method, String suffix, Map<String, Object> body) throws Exception {
        return call(method, url(suffix), managerToken, manager, body);
    }
    private static String preview(long version, String catalogVersion) throws Exception {
        Map<String, Object> result = managerCall("POST", "/preview", previewBody(version, catalogVersion));
        check(Boolean.TRUE.equals(result.get("ready")), "valid preview ready");
        check(Boolean.TRUE.equals(result.get("confirmation_required")), "preview requires explicit confirmation");
        check(number(result.get("current_version")) == version, "preview reports current version");
        check(result.get("changes") != null, "preview exposes change summary");
        check(result.get("batch_id") instanceof String && !((String) result.get("batch_id")).isBlank(), "server creates batch id");
        return (String) result.get("batch_id");
    }
    private static Map<String, Object> confirm(String batch, long version) throws Exception {
        return managerCall("POST", "/confirm", confirmBody(batch, version));
    }
    private static long version() throws Exception {
        return number(Db.one("SELECT version FROM m04_catalog_scopes WHERE scope_id=?", scope).get("version"));
    }
    private static boolean reason(Map<String, Object> value, String teacher, String reason) {
        return rows(value, "gaps").stream().filter(r -> teacher.equals(r.get("teacher_code")))
                .anyMatch(r -> rows(r, "reasons").stream().anyMatch(v -> reason.equals(v.get("code"))));
    }
    private static void rejectedPreview(Map<String, Object> body, String label) throws Exception {
        long batches = Db.count("m04_catalog_batches"), revisions = Db.count("m04_catalog_revisions"), current = version();
        try {
            Map<String, Object> value = managerCall("POST", "/preview", body);
            check(Boolean.FALSE.equals(value.get("ready")) && value.get("batch_id") == null, label + " is invalid and has no batch");
        } catch (Api.ApiException e) { check(e.code == 400 || e.code == 409, label + " rejected (actual " + e.code + ")"); }
        check(Db.count("m04_catalog_batches") == batches && Db.count("m04_catalog_revisions") == revisions && version() == current,
                label + " leaves persistence unchanged");
    }
    private static void setup(Path data) throws Exception {
        if (!Files.isDirectory(data)) throw new IllegalArgumentException("Fresh temporary data directory required");
        try (var files = Files.list(data)) { if (files.findAny().isPresent()) throw new IllegalArgumentException("Data directory must be empty"); }
        System.setProperty("data.dir", data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,teacher_level VARCHAR(120),base_city VARCHAR(120),status VARCHAR(32))");
        String hash = Auth.hash(PASSWORD);
        String[] names = {"admin", "manager", "reader", "other", "colleague", "unbound", "disabled"};
        String[] legacyRoles = {"admin", "viewer", "manager", "viewer", "viewer", "manager", "admin"};
        for (int i = 0; i < names.length; i++) Db.exec("INSERT INTO users VALUES(?,?,?,?,?,?)", i + 1, "synthetic-" + names[i], hash, "SYNTHETIC " + names[i], legacyRoles[i], i == 6 ? 0 : 1);
        for (int i = 101; i <= 104; i++) Db.exec("INSERT INTO teachers VALUES(?,?,?,?)", i, i == 102 ? "L2" : "L1", i == 102 ? "合成乙城" : "合成甲城", "在库");
        adminToken = Auth.login("synthetic-admin", PASSWORD); admin = Auth.get(adminToken);
        managerToken = Auth.login("synthetic-manager", PASSWORD); manager = Auth.get(managerToken);
        readerToken = Auth.login("synthetic-reader", PASSWORD); reader = Auth.get(readerToken);
        otherToken = Auth.login("synthetic-other", PASSWORD); other = Auth.get(otherToken);
        colleagueToken = Auth.login("synthetic-colleague", PASSWORD); colleague = Auth.get(colleagueToken);
        unboundToken = Auth.login("synthetic-unbound", PASSWORD); unbound = Auth.get(unboundToken);
        check(admin != null && manager != null && reader != null && other != null && colleague != null && unbound != null, "real sessions via Auth.login");
        check(Auth.login("synthetic-disabled", PASSWORD) == null && Auth.login("synthetic-manager", "wrong") == null, "real authentication refuses disabled user and wrong password");
        OrganizationAccessStore.init();
        CourseCatalogIntegration.init(); CourseCatalogIntegration.init();
        check(Db.count("m04_catalog_scopes") == 0 && Db.count("m04_catalog_batches") == 0 && Db.count("m04_catalog_revisions") == 0 && Db.count("m04_teacher_bindings") == 0 && Db.count("m04_catalog_events") == 0, "init is idempotent and creates no catalog/grants");
        rejects(403, () -> CourseCatalogIntegration.registerScope(manager, "001"), "unconfigured identity fails closed");
        rejects(403, () -> CourseCatalogIntegration.qualification(manager, 1, 0, request()), "trusted qualification cannot bypass unconfigured identity");
        OrganizationAccessStore.publish(admin, null, identity("identity-v1"));
        loginPublishedIdentities();
        rejects(403, () -> CourseCatalogIntegration.registerScope(admin, "001"), "legacy admin cannot register without business grant");
        rejects(403, () -> CourseCatalogIntegration.registerScope(reader, "001"), "reader cannot register");
        rejects(403, () -> CourseCatalogIntegration.registerScope(manager, "002"), "register cannot cross organization");
        Map<String, Object> first = CourseCatalogIntegration.registerScope(manager, "001");
        scope = String.valueOf(first.get("scope_id"));
        otherScope = String.valueOf(CourseCatalogIntegration.registerScope(other, "002").get("scope_id"));
        check(number(first.get("version")) == 0 && "001".equals(first.get("organization_code")), "trusted scope registration preserves leading zeros and starts at zero");
        check(!scope.equals(otherScope) && Db.count("m04_catalog_scopes") == 2, "separate trusted scopes");
    }
    private static void trustedQualificationAuthorization() throws Exception {
        var entry = CourseCatalogIntegration.class.getDeclaredMethod("qualification", Auth.Session.class, long.class, long.class, Map.class);
        int access = entry.getModifiers();
        check(java.lang.reflect.Modifier.isStatic(access) && !java.lang.reflect.Modifier.isPublic(access)
                && !java.lang.reflect.Modifier.isProtected(access) && !java.lang.reflect.Modifier.isPrivate(access), "trusted qualification stays package-private and static");
        rejects(401, () -> trusted(null, 0, request()), "trusted qualification requires live Auth session");
        Auth.Session forged = new Auth.Session(); forged.uid = 2; forged.role = "admin";
        rejects(401, () -> trusted(forged, 0, request()), "trusted qualification rejects forged session");
        rejects(403, () -> trusted(admin, 0, request()), "trusted qualification gives legacy admin no implicit permission");
        rejects(403, () -> trusted(unbound, 0, request()), "trusted qualification rejects unbound account");
        rejects(403, () -> trusted(other, 0, request()), "trusted qualification authorizes persisted scope organization");
        rejects(403, () -> CourseCatalogIntegration.qualification(manager, Long.parseLong(otherScope), 0, request()), "trusted qualification cannot read another organization");
        rejects(404, () -> CourseCatalogIntegration.qualification(reader, 999999, 0, request()), "trusted qualification requires existing scope");
        for (long invalid : new long[]{-1, 9007199254740992L}) {
            rejects(400, () -> trusted(reader, invalid, request()), "trusted qualification validates expected version " + invalid);
        }
        rejects(409, () -> trusted(reader, 1, request()), "trusted qualification rejects future expected version");
        Map<String, Object> empty = trusted(reader, 0, request());
        check("NOT_CONFIGURED".equals(empty.get("status")) && Boolean.FALSE.equals(empty.get("ready"))
                && rows(empty, "eligible").isEmpty() && rows(empty, "gaps").isEmpty(), "trusted unconfigured qualification returns no invented candidates");
        Map<String, Object> ctx = context(empty);
        check(number(ctx.get("version")) == 0 && ctx.containsKey("catalog_version") && ctx.get("catalog_version") == null
                && "001".equals(ctx.get("course_code")) && "2026-09-22".equals(ctx.get("as_of")), "unconfigured context preserves explicit request and version zero");
        check(cloneMap(empty).equals(call("POST", url("/qualify"), readerToken, reader,
                map("expected_version", 0, "request", request()))), "HTTP and trusted helper share unconfigured response");
        for (String field : List.of("catalog", "bindings", "candidates", "candidate_pool", "eligible", "teacher_id", "organization_code", "account_id", "person_code", "qualification_context", "expected_version")) {
            Map<String, Object> injected = request(); injected.put(field, List.of("SYNTHETIC-FABRICATED"));
            rejects(400, () -> trusted(reader, 0, injected), "trusted qualification rejects client-supplied " + field);
        }
        Map<String, Object> badDate = request(); badDate.put("as_of", "2026-02-30");
        rejects(400, () -> trusted(reader, 0, badDate), "unconfigured trusted qualification validates date");
        check(Db.count("m04_catalog_batches") == 0 && Db.count("m04_catalog_revisions") == 0 && Db.count("m04_teacher_bindings") == 0,
                "trusted qualification never seeds catalogs, candidates or bindings");
    }
    private static void authorizationAndParsing() throws Exception {
        check(!CourseCatalogIntegration.handle(new Exchange("GET", "/api/unrelated", null, null), null), "unrelated path not claimed");
        rejects(401, () -> call("GET", url(""), null, manager, null), "session argument cannot replace request token");
        rejects(401, () -> call("GET", url(""), managerToken, colleague, null), "token and supplied session must match");
        Auth.Session forged = new Auth.Session(); forged.uid = 2; forged.role = "admin";
        rejects(401, () -> call("GET", url(""), managerToken, forged, null), "forged session rejected");
        rejects(401, () -> CourseCatalogIntegration.registerScope(forged, "001"), "forged trusted-call session rejected");
        for (String[] actor : List.of(new String[]{adminToken, "admin"}, new String[]{unboundToken, "unbound"})) {
            Auth.Session session = actor[1].equals("admin") ? admin : unbound;
            rejects(403, () -> call("GET", url(""), actor[0], session, null), "legacy " + actor[1] + " has no implicit read grant");
            rejects(403, () -> call("POST", url("/preview"), actor[0], session, previewBody(0, "forbidden")), "legacy " + actor[1] + " has no implicit manage grant");
        }
        rejects(403, () -> call("GET", ROOT + "/" + otherScope, managerToken, manager, null), "stored scope ownership blocks cross-org read");
        rejects(403, () -> call("POST", ROOT + "/" + otherScope + "/preview", managerToken, manager, previewBody(0, "cross-org")), "stored scope ownership blocks cross-org write");
        rejects(403, () -> call("POST", url("/preview"), readerToken, reader, previewBody(0, "read-only")), "VIEW grant cannot manage");
        rejects(403, () -> call("GET", url("/history"), readerToken, reader, null), "history requires management grant");
        rejects(403, () -> call("GET", url("/versions/1"), readerToken, reader, null), "historical snapshot requires management grant");
        Map<String, Object> listing = call("GET", ROOT, readerToken, reader, null);
        check(rows(listing, "scopes").size() == 1 && Long.parseLong(scope) == number(rows(listing, "scopes").get(0).get("scope_id")) && !Json.write(listing).contains("synthetic-"), "scope enumeration hides inaccessible scopes and accounts");
        Map<String, Object> empty = call("GET", url(""), readerToken, reader, null);
        check("NOT_CONFIGURED".equals(empty.get("status")) && number(empty.get("version")) == 0, "new scope is explicitly unconfigured");
        Map<String, Object> unconfigured = call("POST", url("/qualify"), readerToken, reader, map("expected_version", 0, "request", request()));
        check("NOT_CONFIGURED".equals(unconfigured.get("status")) && Boolean.FALSE.equals(unconfigured.get("ready")) && rows(unconfigured, "eligible").isEmpty(), "unconfigured qualification never invents candidates");
        Map<String, Object> malformedRequest = request(); malformedRequest.put("as_of", "2026-02-30");
        rejects(400, () -> managerCall("POST", "/qualify", map("expected_version", 0, "request", malformedRequest)), "unconfigured qualification still validates request");
        rejects(405, () -> call("POST", ROOT, managerToken, manager, map("organization_code", "001")), "HTTP cannot create scopes");
        rejects(405, () -> managerCall("GET", "/preview", null), "preview method restricted");
        rejects(400, () -> managerCall("GET", "?organization_code=002", null), "ownership query rejected");
        rejects(400, () -> call("GET", ROOT + "?uid=1", managerToken, manager, null), "scope-list identity query rejected");
        rejects(400, () -> managerCall("POST", "/preview?x=1", previewBody(0, "query")), "preview query rejected");
        for (String query : List.of("limit=0", "limit=-1", "limit=1.5", "limit=101", "limit=9007199254740992", "limit=no", "limit=1&limit=2", "before_version=-1", "before_version=1.5", "before_version=9007199254740992", "uid=1")) {
            rejects(400, () -> managerCall("GET", "/history?" + query, null), "history rejects " + query);
        }
        for (String field : List.of("organization_code", "uid", "actor_person", "certified")) {
            Map<String, Object> invalid = previewBody(0, "bad-extra"); invalid.put(field, "client-supplied");
            rejects(400, () -> managerCall("POST", "/preview", invalid), "preview rejects unknown " + field);
        }
        for (Object value : Arrays.asList("0", -1, 0.5, 9007199254740992L, true, null)) {
            Map<String, Object> invalid = previewBody(0, "bad-version"); invalid.put("expected_version", value);
            rejects(400, () -> managerCall("POST", "/preview", invalid), "strict version " + value);
        }
        for (Object value : Arrays.asList("", "   ", "x".repeat(501), 123, null)) {
            Map<String, Object> invalid = previewBody(0, "bad-comment"); invalid.put("change_comment", value);
            rejects(400, () -> managerCall("POST", "/preview", invalid), "strict change comment");
        }
        Map<String, Object> missing = previewBody(0, "missing"); missing.remove("bindings");
        rejects(400, () -> managerCall("POST", "/preview", missing), "bindings required");
        Exchange plain = new Exchange("POST", url("/preview"), managerToken, previewBody(0, "wrong-content-type"));
        plain.getRequestHeaders().set("Content-Type", "text/plain");
        rejects(415, () -> CourseCatalogIntegration.handle(plain, manager), "JSON content type required");
        check(Db.count("m04_catalog_batches") == 0, "all authorization and request-schema failures create no batches");
    }
    @SuppressWarnings("unchecked") private static void previewValidation() throws Exception {
        for (Object value : List.of("101", 101.5, 9007199254740992L, -1, true)) {
            Map<String, Object> invalid = previewBody(0, "bad-binding-type"); rows(invalid, "bindings").get(0).put("teacher_id", value);
            rejectedPreview(invalid, "binding id exact integer " + value);
        }
        Map<String, Object> invalid = previewBody(0, "missing-teacher"); rows(invalid, "bindings").get(0).put("teacher_id", 999);
        rejectedPreview(invalid, "binding must reference existing teacher");
        invalid = previewBody(0, "duplicate-id"); rows(invalid, "bindings").get(1).put("teacher_id", 101);
        rejectedPreview(invalid, "binding ids must be one-to-one");
        invalid = previewBody(0, "duplicate-code"); rows(invalid, "bindings").get(1).put("teacher_code", "0001");
        rejectedPreview(invalid, "binding codes must be one-to-one");
        invalid = previewBody(0, "unbound-code"); rows(invalid, "bindings").remove(1);
        rejectedPreview(invalid, "all teacher codes explicitly bound");
        invalid = previewBody(0, "unknown-binding-field"); rows(invalid, "bindings").get(0).put("organization_code", "001");
        rejectedPreview(invalid, "binding fields whitelist");
        invalid = previewBody(0, "false-level"); rows((Map<String, Object>) invalid.get("catalog"), "teachers").get(0).put("teacher_level", "L9");
        rejectedPreview(invalid, "import cannot raise stored teacher level");
        invalid = previewBody(0, "false-city"); rows((Map<String, Object>) invalid.get("catalog"), "teachers").get(0).put("city", "冒充城市");
        rejectedPreview(invalid, "import cannot replace stored base city");
        invalid = previewBody(0, "invalid-certification"); rows((Map<String, Object>) invalid.get("catalog"), "certifications").get(0).put("source_ref", "");
        rejectedPreview(invalid, "certification requires source");
        invalid = previewBody(0, "unknown-catalog-field"); ((Map<String, Object>) invalid.get("catalog")).put("eligible", true);
        rejectedPreview(invalid, "catalog cannot supply qualification");
        invalid = previewBody(0, "unknown-teacher-field"); rows((Map<String, Object>) invalid.get("catalog"), "teachers").get(0).put("name", "禁止个人信息");
        rejectedPreview(invalid, "catalog teacher fields whitelist");
    }
    private static void publicationAndAudit() throws Exception {
        String batch = preview(0, "catalog-v1");
        check(version() == 0 && Db.count("m04_catalog_batches") == 1 && Db.count("m04_catalog_revisions") == 0 && Db.count("m04_teacher_bindings") == 0 && Db.count("m04_catalog_events") == 0, "preview writes only pending batch, never active catalog/bindings/audit");
        Map<String, Object> storedBatch = Db.one("SELECT * FROM m04_catalog_batches WHERE batch_id=?", batch);
        check(number(storedBatch.get("actor_account")) == 2 && "0001".equals(storedBatch.get("actor_person")) && "identity-v1".equals(storedBatch.get("identity_version")), "batch records trusted account/person/config");
        rejects(403, () -> call("POST", url("/confirm"), colleagueToken, colleague, confirmBody(batch, 0)), "another authorized manager cannot confirm someone else's preview");
        rejects(403, () -> call("POST", url("/confirm"), readerToken, reader, confirmBody(batch, 0)), "reader cannot confirm");
        rejects(403, () -> call("POST", ROOT + "/" + otherScope + "/confirm", managerToken, manager, confirmBody(batch, 0)), "batch cannot target another organization");
        for (Object value : List.of(false, "true", 1)) {
            Map<String, Object> invalid = confirmBody(batch, 0); invalid.put("confirm", value);
            rejects(400, () -> managerCall("POST", "/confirm", invalid), "confirmation must be literal true");
        }
        Map<String, Object> injected = confirmBody(batch, 0); injected.put("catalog", catalog("replacement"));
        rejects(400, () -> managerCall("POST", "/confirm", injected), "confirm cannot replace preview payload");
        check(version() == 0 && Db.count("m04_catalog_events") == 0, "failed confirmations do not publish");
        Map<String, Object> confirmed = confirm(batch, 0);
        check("CONFIRMED".equals(confirmed.get("status")) && number(confirmed.get("version")) == 1, "explicit confirm publishes first version");
        check(version() == 1 && Db.count("m04_catalog_revisions") == 1 && Db.count("m04_catalog_events") == 1 && Db.count("m04_teacher_bindings") == 2, "snapshot head, revision, bindings and event publish together");
        Map<String, Object> revision = Db.one("SELECT * FROM m04_catalog_revisions WHERE scope_id=? AND version=1", scope);
        Map<String, Object> event = Db.one("SELECT * FROM m04_catalog_events WHERE scope_id=? AND version=1", scope);
        for (Map<String, Object> row : List.of(revision, event)) {
            check(batch.equals(row.get("batch_id")) && number(row.get("actor_account")) == 2 && "0001".equals(row.get("actor_person")) && "identity-v1".equals(row.get("identity_version")), "immutable audit links batch and trusted actor");
            check(row.get("created_at") != null && row.get("changes") != null && String.valueOf(row.get("change_comment")).contains("catalog-v1"), "audit retains time, diff and change comment");
        }
        check("catalog-v1".equals(revision.get("catalog_version")) && String.valueOf(revision.get("payload")).contains("SYNTHETIC-SOURCE-01"), "revision retains coded catalog and evidence source");
        Map<String, Object> again = confirm(batch, 0);
        check("ALREADY_CONFIRMED".equals(again.get("status")) && number(again.get("version")) == 1, "same confirmed batch is idempotent");
        rejects(409, () -> confirm(batch, 1), "idempotence does not excuse wrong original expected version");
        check(Db.count("m04_catalog_revisions") == 1 && Db.count("m04_catalog_events") == 1 && version() == 1, "retries never duplicate revision/event");
        Map<String, Object> snapshot = call("GET", url(""), readerToken, reader, null);
        check(number(snapshot.get("version")) == 1 && Json.write(snapshot).contains("catalog-v1"), "reader sees confirmed current snapshot");
        Map<String, Object> history = managerCall("GET", "/history?limit=20", null);
        check(Json.write(history).contains(batch) && Json.write(history).contains("0001"), "manager history exposes confirmation audit");
        Map<String, Object> historical = managerCall("GET", "/versions/1", null);
        check(number(historical.get("version")) == 1 && Json.write(historical).contains("catalog-v1"), "manager reads exact historical snapshot");
    }
    private static void currentQualification() throws Exception {
        Map<String, Object> query = request();
        Map<String, Object> value = call("POST", url("/qualify"), readerToken, reader, map("expected_version", 1, "request", query));
        check(Boolean.TRUE.equals(value.get("ready")) && rows(value, "eligible").size() == 1 && reason(value, "0002", "CERTIFICATION_UNKNOWN"), "read grant obtains exact-course qualification and explicit gap");
        check("0001".equals(rows(value, "eligible").get(0).get("teacher_code")) && "not_configured".equals(value.get("ranking_status")), "leading-zero codes preserved and no ranking invented");
        rejects(409, () -> managerCall("POST", "/qualify", map("expected_version", 0, "request", request())), "qualification rejects stale catalog version");
        Map<String, Object> injected = map("expected_version", 1, "request", request(), "eligible", List.of("0002"));
        rejects(400, () -> managerCall("POST", "/qualify", injected), "qualification rejects fabricated result");
        Map<String, Object> extra = request(); extra.put("teacher_id", 101);
        rejects(400, () -> managerCall("POST", "/qualify", map("expected_version", 1, "request", extra)), "qualification request whitelist fails closed");
        Db.exec("UPDATE teachers SET teacher_level='L9',base_city='合成丙城' WHERE id=101");
        query.put("accepted_levels", List.of("L1")); query.put("allowed_cities", List.of("合成甲城"));
        value = managerCall("POST", "/qualify", map("expected_version", 1, "request", query));
        check(reason(value, "0001", "LEVEL_NOT_ALLOWED") && reason(value, "0001", "CITY_NOT_ALLOWED") && rows(value, "eligible").isEmpty(), "qualification refreshes current level and city, never stale import facts");
        Db.exec("UPDATE teachers SET teacher_level='L1',base_city='合成甲城',status='出库' WHERE id=101");
        value = managerCall("POST", "/qualify", map("expected_version", 1, "request", request()));
        check(reason(value, "0001", "TEACHER_NOT_ACTIVE") && rows(value, "eligible").isEmpty(), "out-of-library teacher never qualifies");
        Db.exec("UPDATE teachers SET status='在库' WHERE id=101");
        Map<String, Object> otherCourse = request(); otherCourse.put("course_code", "002");
        value = managerCall("POST", "/qualify", map("expected_version", 1, "request", otherCourse));
        check(rows(value, "eligible").isEmpty() && reason(value, "0001", "CERTIFICATION_MISSING"), "another course cannot borrow certification");
        String originalBindings = String.valueOf(Db.one("SELECT bindings FROM m04_catalog_revisions WHERE scope_id=? AND version=1", scope).get("bindings"));
        List<Map<String, Object>> corruptBindings = bindings(); corruptBindings.get(0).put("teacher_id", 103);
        Db.exec("UPDATE m04_catalog_revisions SET bindings=? WHERE scope_id=? AND version=1", Json.write(corruptBindings), scope);
        try {
            corruptRejected(() -> managerCall("GET", "", null), "current snapshot rejects stored mapping inconsistent with permanent binding");
            corruptRejected(() -> managerCall("GET", "/versions/1", null), "historical snapshot rejects stored mapping inconsistent with permanent binding");
            corruptRejected(() -> managerCall("POST", "/qualify", map("expected_version", 1, "request", request())), "qualification fails closed on corrupted persistent mapping");
            corruptRejected(() -> trusted(reader, 1, request()), "trusted qualification fails closed on corrupted persistent mapping");
        } finally { Db.exec("UPDATE m04_catalog_revisions SET bindings=? WHERE scope_id=? AND version=1", originalBindings, scope); }
        check(rows(managerCall("POST", "/qualify", map("expected_version", 1, "request", request())), "eligible").size() == 1, "restored stored mapping qualifies normally");
        check(Db.count("m04_catalog_revisions") == 1 && Db.count("m04_catalog_events") == 1, "qualification has no catalog writes");
    }
    private static void trustedQualificationCurrent() throws Exception {
        long batches = Db.count("m04_catalog_batches"), revisions = Db.count("m04_catalog_revisions"), events = Db.count("m04_catalog_events"), mappingCount = Db.count("m04_teacher_bindings");
        Map<String, Object> query = request(); String originalQuery = Json.write(query);
        Map<String, Object> result = trusted(reader, 1, query), base = context(result);
        check(Json.write(query).equals(originalQuery), "trusted qualification leaves caller request unchanged");
        check("m04_qualification_context_v1".equals(base.get("schema_version")) && number(base.get("scope_id")) == Long.parseLong(scope)
                && number(base.get("version")) == 1 && "catalog-v1".equals(base.get("catalog_version"))
                && "001".equals(base.get("organization_code")) && "identity-v1".equals(base.get("identity_version")), "trusted context identifies current scope, catalog and authorization configuration");
        check(number(base.get("account_id")) == 3 && "0002".equals(base.get("person_code")), "trusted context binds current account to current person");
        check("001".equals(base.get("course_code")) && "2026-09-22".equals(base.get("as_of"))
                && List.of().equals(base.get("accepted_levels")) && List.of().equals(base.get("allowed_cities")), "trusted context records every explicit qualification criterion");
        for (String key : List.of("criteria_id", "catalog_snapshot_id", "teacher_facts_id", "context_id")) {
            check(base.get(key) instanceof String && ((String) base.get(key)).matches("[0-9a-f]{64}"), "trusted context has SHA-256 " + key);
        }
        String visible = Json.write(base);
        check(!visible.contains(readerToken) && !visible.contains(managerToken) && !visible.contains(PASSWORD)
                && !visible.contains("synthetic-reader"), "qualification context exposes no authentication credentials");
        check(cloneMap(result).equals(call("POST", url("/qualify"), readerToken, reader,
                map("expected_version", 1, "request", query))), "HTTP and trusted helper use one qualification result and context path");
        check(number(rows(result, "eligible").get(0).get("teacher_id")) == 101 && reason(result, "0002", "CERTIFICATION_UNKNOWN"), "trusted candidates come from permanent bindings and stored evidence");
        check(Json.write(result).equals(Json.write(trusted(reader, 1, request()))), "identical current qualification is deterministic");
        Map<String, Object> anotherActor = context(trusted(manager, 1, request()));
        changedContext(base, anotherActor, "different authorized requesting person");
        check(number(anotherActor.get("account_id")) == 2 && "0001".equals(anotherActor.get("person_code"))
                && Objects.equals(base.get("criteria_id"), anotherActor.get("criteria_id"))
                && Objects.equals(base.get("teacher_facts_id"), anotherActor.get("teacher_facts_id")), "separate actor context shares only identical factual digests");

        Map<String, Object> ordered = request(); ordered.put("accepted_levels", List.of("L1", "L2")); ordered.put("allowed_cities", List.of("合成甲城", "合成乙城"));
        Map<String, Object> reversed = map("allowed_cities", List.of("合成乙城", "合成甲城"), "accepted_levels", List.of("L2", "L1"), "as_of", "2026-09-22", "course_code", "001");
        Map<String, Object> orderedResult = trusted(reader, 1, ordered), orderedContext = context(orderedResult), reversedResult = trusted(reader, 1, reversed), reversedContext = context(reversedResult);
        check(Objects.equals(orderedContext.get("criteria_id"), reversedContext.get("criteria_id"))
                && Objects.equals(orderedContext.get("context_id"), reversedContext.get("context_id")), "input object and filter order have no effect on trusted context");
        check(new ArrayList<>(new TreeSet<>(List.of("L1", "L2"))).equals(orderedContext.get("accepted_levels"))
                && new ArrayList<>(new TreeSet<>(List.of("合成甲城", "合成乙城"))).equals(orderedContext.get("allowed_cities")), "trusted context emits sorted filter sets");
        for (Map<String, Object> variant : List.of(map("as_of", "2026-09-23"), map("accepted_levels", List.of("L1")), map("allowed_cities", List.of("合成甲城")), map("course_code", "002"))) {
            Map<String, Object> changed = request(); changed.putAll(variant);
            Map<String, Object> changedContext = context(trusted(reader, 1, changed));
            changedContext(base, changedContext, "changed explicit criterion " + variant.keySet());
            check(!Objects.equals(base.get("criteria_id"), changedContext.get("criteria_id"))
                    && Objects.equals(base.get("teacher_facts_id"), changedContext.get("teacher_facts_id")), "criterion change alters criteria digest while retaining current facts");
        }
        for (Map<String, Object> invalid : List.of(map("as_of", "2026-02-30"), map("as_of", ""), map("accepted_levels", List.of("L1", "L1")), map("allowed_cities", List.of(1)))) {
            Map<String, Object> bad = request(); bad.putAll(invalid);
            rejects(400, () -> trusted(reader, 1, bad), "configured trusted qualification rejects malformed criteria " + invalid.keySet());
        }
        rejects(400, () -> trusted(reader, 1, null), "trusted qualification requires explicit request");
        Map<String, Object> missingDate = request(); missingDate.remove("as_of");
        rejects(400, () -> trusted(reader, 1, missingDate), "trusted qualification never supplies an implicit date");

        try {
            Db.exec("UPDATE teachers SET teacher_level='L9' WHERE id=101");
            changedTeacherFacts(base, context(trusted(reader, 1, request())), "current teacher level edit with unchanged catalog version");
            check(reason(trusted(reader, 1, ordered), "0001", "LEVEL_NOT_ALLOWED"), "trusted helper uses live level for qualification");
        } finally { Db.exec("UPDATE teachers SET teacher_level='L1' WHERE id=101"); }
        try {
            Db.exec("UPDATE teachers SET base_city='合成丙城' WHERE id=101");
            changedTeacherFacts(base, context(trusted(reader, 1, request())), "current teacher city edit with unchanged catalog version");
            check(reason(trusted(reader, 1, ordered), "0001", "CITY_NOT_ALLOWED"), "trusted helper uses live city for qualification");
        } finally { Db.exec("UPDATE teachers SET base_city='合成甲城' WHERE id=101"); }
        try {
            Db.exec("UPDATE teachers SET status='出库' WHERE id=101");
            Map<String, Object> inactive = trusted(reader, 1, request());
            changedTeacherFacts(base, context(inactive), "current teacher status edit with unchanged catalog version");
            check(rows(inactive, "eligible").isEmpty() && reason(inactive, "0001", "TEACHER_NOT_ACTIVE"), "trusted helper removes inactive teacher from eligible pool");
            check(cloneMap(inactive).equals(call("POST", url("/qualify"), readerToken, reader,
                    map("expected_version", 1, "request", request()))), "HTTP and helper remain identical after current teacher status change");
        } finally { Db.exec("UPDATE teachers SET status='在库' WHERE id=101"); }
        check(Objects.equals(base.get("context_id"), context(trusted(reader, 1, request())).get("context_id")), "restoring all current facts restores the same semantic context");
        try {
            Db.exec("UPDATE teachers SET teacher_level='L9',base_city='合成无关城',status='出库' WHERE id=104");
            check(Objects.equals(base.get("context_id"), context(trusted(reader, 1, request())).get("context_id")), "unbound teacher cannot affect qualification context or candidate pool");
        } finally { Db.exec("UPDATE teachers SET teacher_level='L1',base_city='合成甲城',status='在库' WHERE id=104"); }

        String originalPayload = String.valueOf(Db.one("SELECT payload FROM m04_catalog_revisions WHERE scope_id=? AND version=1", scope).get("payload"));
        Map<String, Object> alteredPayload = Json.parseMap(originalPayload);
        rows(alteredPayload, "courses").get(1).put("course_name", "合成课程乙材料修订");
        try {
            Db.exec("UPDATE m04_catalog_revisions SET payload=? WHERE scope_id=? AND version=1", Json.write(alteredPayload), scope);
            Map<String, Object> altered = trusted(reader, 1, request()), alteredContext = context(altered);
            changedContext(base, alteredContext, "persisted snapshot edit even when selected course result is unchanged");
            check(!Objects.equals(base.get("catalog_snapshot_id"), alteredContext.get("catalog_snapshot_id"))
                    && Objects.equals(base.get("teacher_facts_id"), alteredContext.get("teacher_facts_id"))
                    && Json.write(rows(result, "eligible")).equals(Json.write(rows(altered, "eligible"))), "context covers full persisted catalog evidence beyond candidate results");
        } finally { Db.exec("UPDATE m04_catalog_revisions SET payload=? WHERE scope_id=? AND version=1", originalPayload, scope); }
        check(Objects.equals(base.get("context_id"), context(trusted(reader, 1, request())).get("context_id")), "restored catalog evidence restores original context");
        check(version() == 1 && Db.count("m04_catalog_batches") == batches && Db.count("m04_catalog_revisions") == revisions
                && Db.count("m04_catalog_events") == events && Db.count("m04_teacher_bindings") == mappingCount, "trusted reads make no catalog, batch, audit or binding writes");
    }
    private static void conflictsAndRollback() throws Exception {
        Map<String, Object> contextV1 = context(trusted(reader, 1, request()));
        String first = preview(1, "catalog-v2-a"), stale = preview(1, "catalog-v2-b");
        check(number(confirm(first, 1).get("version")) == 2, "first of two pending previews wins");
        Map<String, Object> contextV2 = context(trusted(reader, 2, request()));
        changedContext(contextV1, contextV2, "new confirmed catalog revision");
        check(number(contextV2.get("version")) == 2 && "catalog-v2-a".equals(contextV2.get("catalog_version"))
                && !Objects.equals(contextV1.get("catalog_snapshot_id"), contextV2.get("catalog_snapshot_id")), "new trusted context identifies exact published catalog");
        rejects(409, () -> trusted(reader, 1, request()), "trusted qualification cannot use historical version as current");
        rejects(409, () -> confirm(stale, 1), "second pending preview rejects stale head");
        check(version() == 2 && Db.count("m04_catalog_revisions") == 2, "stale confirm cannot append");
        String teacherChanged = preview(2, "teacher-changed");
        Db.exec("UPDATE teachers SET teacher_level='L9' WHERE id=101");
        rejects(409, () -> confirm(teacherChanged, 2), "teacher metadata change invalidates preview");
        Db.exec("UPDATE teachers SET teacher_level='L1' WHERE id=101");
        String statusChanged = preview(2, "status-changed");
        Db.exec("UPDATE teachers SET status='出库' WHERE id=101");
        rejects(409, () -> confirm(statusChanged, 2), "teacher status change invalidates preview");
        Db.exec("UPDATE teachers SET status='在库' WHERE id=101");
        String configurationChanged = preview(2, "identity-changed");
        OrganizationAccessStore.publish(admin, "identity-v1", identity("identity-v2"));
        Map<String, Object> identityV2 = context(trusted(reader, 2, request()));
        changedContext(contextV2, identityV2, "current identity configuration replacement");
        check("identity-v2".equals(identityV2.get("identity_version"))
                && Objects.equals(contextV2.get("criteria_id"), identityV2.get("criteria_id"))
                && Objects.equals(contextV2.get("teacher_facts_id"), identityV2.get("teacher_facts_id")), "context tracks live identity version without inventing teacher or query change");
        rejects(409, () -> confirm(configurationChanged, 2), "identity configuration change invalidates preview even when permissions remain");
        Map<String, Object> withNewTeacher = previewBody(2, "transaction-failure");
        @SuppressWarnings("unchecked") Map<String, Object> expanded = (Map<String, Object>) withNewTeacher.get("catalog");
        rows(expanded, "teachers").add(map("teacher_code", "0004", "teacher_level", "L1", "city", "合成甲城"));
        rows(withNewTeacher, "bindings").add(map("teacher_code", "0004", "teacher_id", 104));
        String failing = (String) managerCall("POST", "/preview", withNewTeacher).get("batch_id");
        long revisions = Db.count("m04_catalog_revisions"), events = Db.count("m04_catalog_events"), mappings = Db.count("m04_teacher_bindings");
        Db.exec("ALTER TABLE m04_catalog_events ADD CONSTRAINT m04_test_failure CHECK(version<>3)");
        try { confirm(failing, 2); throw new AssertionError("Injected event insert must fail"); }
        catch (SQLException expected) { check(true, "injected database failure propagated"); }
        check(version() == 2 && Db.count("m04_catalog_revisions") == revisions && Db.count("m04_catalog_events") == events && Db.count("m04_teacher_bindings") == mappings, "event failure rolls back head, revision, event and mappings");
        check(Db.one("SELECT confirmed_version FROM m04_catalog_batches WHERE batch_id=?", failing).get("confirmed_version") == null, "failed confirmation leaves batch unconfirmed");
        Db.exec("ALTER TABLE m04_catalog_events DROP CONSTRAINT m04_test_failure");
        check(number(confirm(failing, 2).get("version")) == 3, "same pending batch can succeed after rollback cause removed");
        check(Db.count("m04_teacher_bindings") == mappings + 1, "successful retry alone adds new permanent mapping");
        Map<String, Object> page = managerCall("GET", "/history?limit=1&before_version=3", null);
        String pageJson = Json.write(page);
        check(pageJson.contains(first) && !pageJson.contains(failing), "history before-version pagination excludes newer confirmation");
        check(Json.write(managerCall("GET", "/versions/1", null)).contains("catalog-v1"), "old snapshot survives replacement");
    }
    @SuppressWarnings("unchecked") private static void permanentBindingAndTransactions() throws Exception {
        Map<String, Object> removal = previewBody(3, "teacher-removed");
        Map<String, Object> reduced = (Map<String, Object>) removal.get("catalog");
        rows(reduced, "teachers").remove(0); rows(reduced, "certifications").remove(0); rows(removal, "bindings").remove(0);
        Map<String, Object> pending = managerCall("POST", "/preview", removal);
        check(Boolean.TRUE.equals(pending.get("ready")), "whole-snapshot removal may be previewed");
        confirm((String) pending.get("batch_id"), 3);
        check(version() == 4 && Db.count("m04_teacher_bindings") == 3, "removing teacher does not erase permanent mapping");
        Map<String, Object> rebind = previewBody(4, "forbidden-rebind"); rows(rebind, "bindings").get(0).put("teacher_id", 103);
        rejectedPreview(rebind, "removed code cannot be rebound to another existing teacher");
        Map<String, Object> reverse = previewBody(4, "forbidden-reverse-rebind");
        rows((Map<String, Object>) reverse.get("catalog"), "teachers").get(0).put("teacher_code", "0003");
        rows((Map<String, Object>) reverse.get("catalog"), "certifications").get(0).put("teacher_code", "0003");
        rows(reverse, "bindings").get(0).put("teacher_code", "0003");
        rejectedPreview(reverse, "removed teacher id cannot be silently rebound to a different code");
        String restore = preview(4, "teacher-restored");
        check(number(confirm(restore, 4).get("version")) == 5, "same permanent mapping can return in later snapshot");
        String nestedBatch = preview(5, "nested-transaction");
        long beforeBatches = Db.count("m04_catalog_batches");
        Db.get().setAutoCommit(false);
        try {
            Db.exec("UPDATE teachers SET base_city='SYNTHETIC-OUTER-TRANSACTION' WHERE id=104");
            nestedRejected(() -> CourseCatalogIntegration.registerScope(manager, "001"), "trusted registration rejects outer transaction");
            nestedRejected(() -> managerCall("POST", "/preview", previewBody(5, "must-not-nest")), "preview rejects outer transaction");
            nestedRejected(() -> confirm(nestedBatch, 5), "confirm rejects outer transaction");
            check(!Db.get().getAutoCommit() && "SYNTHETIC-OUTER-TRANSACTION".equals(Db.one("SELECT base_city FROM teachers WHERE id=104").get("base_city")), "rejected nested work preserves caller transaction and pending changes");
        } finally { Db.get().rollback(); Db.get().setAutoCommit(true); }
        check(version() == 5 && Db.count("m04_catalog_batches") == beforeBatches, "nested operations do not commit or alter surrounding transaction");
        Db.get().close(); CourseCatalogIntegration.init();
        check(version() == 5 && Db.count("m04_catalog_events") == 5 && Db.count("m04_catalog_revisions") == 5, "persisted catalog survives connection reopen and idempotent init");
        check(Json.write(managerCall("GET", "", null)).contains("teacher-restored"), "reopened current snapshot retained");
        check(Json.write(managerCall("GET", "/versions/1", null)).contains("catalog-v1"), "reopened history retained");
    }
    private static void loginPublishedIdentities() throws Exception {
        // Current-scope checks reauthenticate after configuration revoked old sessions.
        if(Auth.current(manager)==null){managerToken=Auth.login("synthetic-manager",PASSWORD);manager=Auth.get(managerToken);}
        if(Auth.current(reader)==null){readerToken=Auth.login("synthetic-reader",PASSWORD);reader=Auth.get(readerToken);}
        if(Auth.current(other)==null){otherToken=Auth.login("synthetic-other",PASSWORD);other=Auth.get(otherToken);}
        if(Auth.current(colleague)==null){colleagueToken=Auth.login("synthetic-colleague",PASSWORD);colleague=Auth.get(colleagueToken);}
    }
    private static void sessionRevocation() throws Exception {
        Db.exec("UPDATE users SET status=0 WHERE id=2");
        rejects(401, () -> trusted(manager, 6, request()), "trusted qualification immediately denies disabled real user");
        rejects(401, () -> managerCall("GET", "", null), "disabled user immediately loses cached-session access");
        Db.exec("UPDATE users SET status=1 WHERE id=2");
        rejects(401, () -> trusted(manager, 6, request()), "trusted qualification does not revive revoked token after account re-enable");
        rejects(401, () -> managerCall("GET", "", null), "re-enable does not revive revoked token");
        managerToken = Auth.login("synthetic-manager", PASSWORD); manager = Auth.get(managerToken);
        String valid = managerToken;
        Auth.logout(valid);
        rejects(401, () -> trusted(manager, 6, request()), "trusted qualification rejects logged-out real session");
        rejects(401, () -> managerCall("GET", "", null), "logged-out session denied");
        rejects(401, () -> CourseCatalogIntegration.registerScope(manager, "001"), "trusted helper also denies logged-out session");
        managerToken = Auth.login("synthetic-manager", PASSWORD); manager = Auth.get(managerToken);
        manager.uid = 1;
        rejects(401, () -> trusted(manager, 6, request()), "trusted qualification rejects tampered session uid");
        rejects(401, () -> managerCall("GET", "", null), "tampered signed uid denied");
        check(Db.count("users") == 7 && Db.count("teachers") == 4 && Db.count("m04_catalog_scopes") == 2, "only explicit synthetic accounts, teachers and scopes exist");
    }
    private static void trustedQualificationIdentityRevocation() throws Exception {
        Map<String, Object> original = context(trusted(reader, 6, request()));
        Configuration revoked = identity("identity-v3-reader-revoked");
        revoked = new Configuration(revoked.version(), revoked.codeRules(), revoked.roleCodes(), revoked.organizations(), revoked.people(),
                revoked.relations(), revoked.accountBindings(), revoked.grants().stream().filter(g -> !"READER_READ".equals(g.ruleId())).toList());
        OrganizationAccessStore.publish(admin, "identity-v2", revoked);
        check(Auth.current(reader) == null, "business permission publication revokes old real session");
        rejects(401, () -> trusted(reader, 6, request()), "old session denied before refreshed grant checks");
        loginPublishedIdentities();
        rejects(403, () -> trusted(reader, 6, request()), "trusted qualification rechecks revoked read grant on every call");
        rejects(403, () -> call("POST", url("/qualify"), readerToken, reader, map("expected_version", 6, "request", request())), "HTTP qualification rechecks same revoked read grant");
        check(Boolean.TRUE.equals(trusted(manager, 6, request()).get("ready")), "read revocation is limited to affected business role");

        Configuration remapped = identity("identity-v4-account-remapped");
        List<AccountBinding> remappedAccounts = remapped.accountBindings().stream().map(b -> b.accountId() == 2
                ? new AccountBinding(2, "0003", true) : b.accountId() == 4 ? new AccountBinding(4, "0001", true) : b).toList();
        remapped = new Configuration(remapped.version(), remapped.codeRules(), remapped.roleCodes(), remapped.organizations(), remapped.people(),
                remapped.relations(), remappedAccounts, remapped.grants());
        OrganizationAccessStore.publish(admin, revoked.version(), remapped);
        check(Auth.current(manager) == null, "account remapping revokes old Auth session");
        rejects(401, () -> trusted(manager, 6, request()), "old person session cannot inherit new mapping");
        loginPublishedIdentities();
        check("0003".equals(OrganizationAccessStore.person(manager).personCode()), "fresh login resolves published mapping");
        rejects(403, () -> trusted(manager, 6, request()), "trusted qualification denies old organization after account maps to another person");
        rejects(403, () -> call("POST", url("/qualify"), managerToken, manager, map("expected_version", 6, "request", request())), "HTTP qualification also denies remapped account old scope");
        Map<String, Object> newlyAuthorized = CourseCatalogIntegration.qualification(manager, Long.parseLong(otherScope), 0, request());
        Map<String, Object> remappedContext = context(newlyAuthorized);
        check("NOT_CONFIGURED".equals(newlyAuthorized.get("status")) && "002".equals(remappedContext.get("organization_code"))
                && "0003".equals(remappedContext.get("person_code")) && number(remappedContext.get("account_id")) == 2, "remapped session uses fresh person and authorized stored organization");

        OrganizationAccessStore.publish(admin, remapped.version(), identity("identity-v5-restored"));
        loginPublishedIdentities();
        Map<String, Object> restored = context(trusted(reader, 6, request()));
        changedContext(original, restored, "restored permissions under new identity version");
        check(Boolean.TRUE.equals(trusted(manager, 6, request()).get("ready")) && "identity-v5-restored".equals(restored.get("identity_version")), "restored current authorization allows trusted qualification again");
        check(version() == 6 && Db.count("m04_catalog_events") == 6 && Db.count("m04_catalog_revisions") == 6, "identity revocation and remapping never modify catalog history");
    }
    private static void simultaneousConfirmation() throws Exception {
        String first = preview(5, "concurrent-confirm-a"), second = preview(5, "concurrent-confirm-b");
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (String batch : List.of(first, second)) results.add(executor.submit(() -> {
                go.await();
                try { return "CONFIRMED".equals(confirm(batch, 5).get("status")); }
                catch (Api.ApiException e) { if (e.code != 409) throw e; return false; }
            }));
            go.countDown(); int winners = 0;
            for (Future<Boolean> result : results) if (result.get(15, TimeUnit.SECONDS)) winners++;
            check(winners == 1 && version() == 6 && Db.count("m04_catalog_revisions") == 6 && Db.count("m04_catalog_events") == 6, "simultaneous confirmations have exactly one atomic winner");
            check(Db.query("SELECT batch_id FROM m04_catalog_batches WHERE batch_id IN (?,?) AND confirmed_version=6", first, second).size() == 1, "only winning pending batch is marked confirmed");
        } finally { executor.shutdownNow(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass a new empty temporary directory");
        setup(Path.of(args[0]).toAbsolutePath());
        authorizationAndParsing(); trustedQualificationAuthorization(); previewValidation(); publicationAndAudit(); currentQualification(); trustedQualificationCurrent();
        conflictsAndRollback(); permanentBindingAndTransactions(); simultaneousConfirmation(); trustedQualificationIdentityRevocation(); sessionRevocation();
        Db.exec("SHUTDOWN");
        System.out.println("M04Integration: " + checks + " checks passed (real Auth, isolated synthetic H2; no server or production data)");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> response(Exchange ex) throws Exception {
        Object value = ex.getAttribute("com.training.Api.response");
        if (value == null) throw new AssertionError("Handler produced no Api response");
        java.lang.reflect.Field field = value.getClass().getDeclaredField("body"); field.setAccessible(true);
        return (Map<String, Object>) Json.parse(new String((byte[]) field.get(value), StandardCharsets.UTF_8));
    }
    private static final class Exchange extends HttpExchange {
        private final String method; private final URI uri;
        private final Headers requestHeaders = new Headers(), responseHeaders = new Headers();
        private final Map<String, Object> attributes = new HashMap<>();
        Exchange(String method, String path, String token, Map<String, Object> body) {
            this.method = method; this.uri = URI.create(path);
            if (token != null) requestHeaders.set("X-Token", token);
            requestHeaders.set("Content-Type", "application/json");
            if (body != null) attributes.put("com.training.Api.body", cloneMap(body));
        }
        public Headers getRequestHeaders() { return requestHeaders; }
        public Headers getResponseHeaders() { return responseHeaders; }
        public URI getRequestURI() { return uri; }
        public String getRequestMethod() { return method; }
        public HttpContext getHttpContext() { return null; }
        public void close() {}
        public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
        public void sendResponseHeaders(int code, long length) {}
        public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        public int getResponseCode() { return 0; }
        public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        public String getProtocol() { return "HTTP/1.1"; }
        public Object getAttribute(String name) { return attributes.get(name); }
        public void setAttribute(String name, Object value) { attributes.put(name, value); }
        public void setStreams(InputStream in, OutputStream out) {}
        public HttpPrincipal getPrincipal() { return null; }
    }
}
