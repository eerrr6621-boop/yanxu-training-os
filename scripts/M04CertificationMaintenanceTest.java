package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Certification-only maintenance contract: real Auth/M01, an empty synthetic H2, no server. */
public final class M04CertificationMaintenanceTest {
    private static final String ROOT = "/api/course-catalog/scopes";
    private static final String PASSWORD = "SYNTHETIC-M04-CERTIFICATION-MAINTENANCE-20260923";
    private static int checks;
    private static long scope, otherScope;
    private record Actor(String token, Auth.Session session) {}
    private static Actor admin, manager, reader, colleague, other, denied, unbound;
    @FunctionalInterface private interface Work { void run() throws Exception; }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
    private static void rejects(int status, Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected " + status + ": " + label); }
        catch (Api.ApiException e) { check(e.code == status, label + " (actual " + e.code + ": " + e.getMessage() + ")"); }
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> value, String key) { return (List<Map<String, Object>>) value.get(key); }
    private static Map<String, Object> copy(Map<String, Object> value) { return Json.parseMap(Json.write(value)); }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static String path(long id, String suffix) { return ROOT + "/" + id + suffix; }
    private static Actor login(String name) throws Exception {
        String token = Auth.login("synthetic-certification-" + name, PASSWORD);
        Auth.Session session = Auth.get(token);
        check(token != null && session != null && Auth.current(session) == session, "real live Auth session for " + name);
        return new Actor(token, session);
    }
    private static Configuration identity(String version) {
        Set<String> roles = Set.of("MANAGE", "READ", "BOTH", "NONE");
        RelationRule optional = new RelationRule(false, roles, false, false);
        return new Configuration(version, new CodeRules("[0-9]{3}", "[0-9]{4}", "[A-Z]+"), roles,
                List.of(new Organization("001", null, true), new Organization("002", null, true)),
                List.of(new Person("0001", "001", Set.of(), null, null, Set.of("MANAGE"), true),
                        new Person("0002", "001", Set.of(), null, null, Set.of("READ"), true),
                        new Person("0003", "001", Set.of(), null, null, Set.of("BOTH"), true),
                        new Person("0004", "002", Set.of(), null, null, Set.of("BOTH"), true),
                        new Person("0005", "001", Set.of(), null, null, Set.of("NONE"), true)),
                roles.stream().sorted().map(role -> new RoleRelations(role, optional, optional)).toList(),
                List.of(new AccountBinding(2, "0001", true), new AccountBinding(3, "0002", true),
                        new AccountBinding(4, "0003", true), new AccountBinding(5, "0004", true), new AccountBinding(6, "0005", true)),
                List.of(new Grant("MANAGE_ONLY", "MANAGE", "catalog.manage", Action.HANDLE, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("READ_ONLY", "READ", "catalog.read", Action.VIEW, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("BOTH_READ", "BOTH", "catalog.read", Action.VIEW, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("BOTH_MANAGE", "BOTH", "catalog.manage", Action.HANDLE, Effect.ALLOW, Scope.OWN_ORG, Set.of())));
    }
    private static void setup(Path data) throws Exception {
        if (!Files.isDirectory(data)) throw new IllegalArgumentException("Pass an existing empty temporary directory");
        try (var files = Files.list(data)) { if (files.findAny().isPresent()) throw new IllegalArgumentException("Synthetic data directory must be empty"); }
        System.setProperty("data.dir", data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,teacher_level VARCHAR(120),base_city VARCHAR(120),status VARCHAR(32))");
        String hash = Auth.hash(PASSWORD);
        String[] names = {"admin", "manager", "reader", "colleague", "other", "denied", "unbound"};
        for (int i = 0; i < names.length; i++) Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)", i + 1,
                "synthetic-certification-" + names[i], hash, "SYNTHETIC " + names[i], i == 0 ? "admin" : i == 2 || i == 5 || i == 6 ? "manager" : "viewer");
        Db.exec("INSERT INTO teachers VALUES(101,'L1','合成甲城','在库')");
        Db.exec("INSERT INTO teachers VALUES(102,'L2','合成乙城','在库')");
        Db.exec("INSERT INTO teachers VALUES(103,'L1','合成甲城','在库')");
        Db.exec("INSERT INTO teachers VALUES(999,'L1','合成甲城','在库')");
        OrganizationAccessStore.init(); CourseCatalogIntegration.init();
        admin = login("admin");
        OrganizationAccessStore.publish(admin.session(), null, identity("synthetic-certification-identity-v1"));
        // Publishing real M01 bindings may revoke sessions; all business actors log in afterwards.
        admin = login("admin"); manager = login("manager"); reader = login("reader");
        colleague = login("colleague"); other = login("other"); denied = login("denied"); unbound = login("unbound");
        scope = number(CourseCatalogIntegration.registerScope(manager.session(), "001").get("scope_id"));
        otherScope = number(CourseCatalogIntegration.registerScope(other.session(), "002").get("scope_id"));
        check(Db.count("m04_catalog_batches") == 0 && Db.count("m04_catalog_revisions") == 0 && Db.count("m04_teacher_bindings") == 0,
                "fixture starts with registered zero-version scopes and no catalog data");
    }
    private static Map<String, Object> call(Actor actor, String method, String target, Map<String, Object> body) throws Exception {
        Exchange exchange = new Exchange(method, target, actor == null ? null : actor.token(), body);
        if (!CourseCatalogIntegration.handle(exchange, actor == null ? null : actor.session())) throw new AssertionError("Route not handled: " + target);
        Object response = exchange.getAttribute("com.training.Api.response");
        if (response == null) throw new AssertionError("No Api response: " + target);
        var field = response.getClass().getDeclaredField("body"); field.setAccessible(true);
        Map<String, Object> envelope = Json.parseMap(new String((byte[]) field.get(response), StandardCharsets.UTF_8));
        if (!(envelope.get("data") instanceof Map)) throw new AssertionError("Expected object response: " + envelope);
        return object(envelope.get("data"));
    }
    private static Map<String, Object> manage() throws Exception { return call(manager, "GET", path(scope, "/certification-management"), null); }
    private static long version() throws Exception { return number(Db.one("SELECT version FROM m04_catalog_scopes WHERE scope_id=?", scope).get("version")); }
    private static Map<String, Object> cert(String teacher, String course, String status) {
        return map("teacher_code", teacher, "course_code", course, "status", status, "source_ref", "certified".equals(status) ? "SYNTHETIC-SOURCE-01" : "",
                "valid_from", "", "valid_to", "");
    }
    private static List<Map<String, Object>> courses() {
        return new ArrayList<>(List.of(map("course_code", "001", "course_name", "合成课程甲", "active", true),
                map("course_code", "002", "course_name", "合成停用课程乙", "active", false),
                map("course_code", "003", "course_name", "合成课程丙", "active", true)));
    }
    private static List<Map<String, Object>> teachers() {
        return new ArrayList<>(List.of(map("teacher_code", "0001", "teacher_level", "L1", "city", "合成甲城"),
                map("teacher_code", "0002", "teacher_level", "L2", "city", "合成乙城")));
    }
    private static List<Map<String, Object>> bindings() {
        return new ArrayList<>(List.of(map("teacher_code", "0001", "teacher_id", 101), map("teacher_code", "0002", "teacher_id", 102)));
    }
    private static Map<String, Object> body(long expected, String material, List<Map<String, Object>> proposed) {
        return map("expected_version", expected, "catalog_version", material, "certifications", proposed, "change_comment", "合成认证维护说明 " + material);
    }
    private static Map<String, Object> confirmBody(String batch, long expected) { return map("batch_id", batch, "expected_version", expected, "confirm", true); }
    private static Map<String, Object> confirm(String batch, long expected) throws Exception { return call(manager, "POST", path(scope, "/confirm"), confirmBody(batch, expected)); }
    private static Map<String, Object> state() throws Exception {
        return map("heads", Db.query("SELECT scope_id,version FROM m04_catalog_scopes ORDER BY scope_id"),
                "batches", Db.count("m04_catalog_batches"), "revisions", Db.count("m04_catalog_revisions"),
                "events", Db.count("m04_catalog_events"), "bindings", Db.count("m04_teacher_bindings"));
    }
    private static void rejectedUnchanged(int status, Work work, String label) throws Exception {
        Map<String, Object> before = state(); rejects(status, work, label);
        check(before.equals(state()), label + " leaves publication state unchanged");
    }
    private static void exactManagement(Map<String, Object> result, long id, long expected) {
        check(result.keySet().equals(Set.of("scope_id", "organization_code", "version", "catalog_version", "courses", "teacher_codes", "certifications", "retained_counts", "can_manage")),
                "management exposes exactly the authorized certification maintenance fields");
        check(number(result.get("scope_id")) == id && number(result.get("version")) == expected && Boolean.TRUE.equals(result.get("can_manage")), "management identifies trusted scope and current version");
        check(result.get("teacher_codes") instanceof List<?> codes && codes.stream().allMatch(code -> code instanceof String), "teacher references expose strings only");
        check(rows(result, "courses").stream().allMatch(row -> row.keySet().equals(Set.of("course_code", "course_name", "active"))), "course references retain exactly three fields");
        check(rows(result, "certifications").stream().allMatch(row -> row.keySet().equals(Set.of("teacher_code", "course_code", "status", "source_ref", "valid_from", "valid_to"))), "certification rows retain exactly six fields");
        check(object(result.get("retained_counts")).keySet().equals(Set.of("courses", "teachers", "bindings")), "retained counts exclude mutable certifications");
    }
    private static void previewEnvelope(Map<String, Object> result, long id, long expected) {
        check("m04_preview_v1".equals(result.get("schema_version")) && number(result.get("scope_id")) == id
                        && (id == scope ? "001" : "002").equals(result.get("organization_code")) && number(result.get("current_version")) == expected,
                "certification preview retains standard schema and trusted scope");
    }
    private static void retainedDiffs(Map<String, Object> changes) {
        check(changes.keySet().equals(Set.of("courses", "teachers", "certifications", "bindings")), "preview has four change categories");
        for (String table : List.of("courses", "teachers", "bindings")) {
            Map<String, Object> delta = object(changes.get(table));
            check(delta.keySet().equals(Set.of("added", "updated", "removed"))
                            && delta.values().stream().allMatch(value -> value instanceof List<?> list && list.isEmpty()),
                    "certification maintenance preserves every " + table + " row");
        }
        check(((List<?>) object(changes.get("certifications")).get("removed")).isEmpty(), "certification maintenance never removes an existing pair");
    }
    private static String preview(Actor actor, long id, long expected, String material, List<Map<String, Object>> proposed) throws Exception {
        Map<String, Object> before = state();
        Map<String, Object> result = call(actor, "POST", path(id, "/certifications-preview"), body(expected, material, proposed));
        previewEnvelope(result, id, expected);
        check(Boolean.TRUE.equals(result.get("ready")) && Boolean.TRUE.equals(result.get("confirmation_required"))
                        && result.get("batch_id") instanceof String batch && !batch.isBlank(), "valid certification change freezes a pending batch");
        retainedDiffs(object(result.get("changes")));
        before.put("batches", number(before.get("batches")) + 1);
        check(before.equals(state()), "preview changes only pending batch count");
        return (String) result.get("batch_id");
    }
    private static String preview(String material, List<Map<String, Object>> proposed) throws Exception { return preview(manager, scope, version(), material, proposed); }
    private static Map<String, Object> invalidPreview(Actor actor, long id, Map<String, Object> invalid, String label) throws Exception {
        Map<String, Object> before = state();
        Map<String, Object> result = call(actor, "POST", path(id, "/certifications-preview"), invalid);
        previewEnvelope(result, id, number(invalid.get("expected_version")));
        check(Boolean.FALSE.equals(result.get("ready")) && result.get("batch_id") == null && Boolean.FALSE.equals(result.get("confirmation_required"))
                        && result.containsKey("changes") && result.get("changes") == null && !rows(result, "issues").isEmpty(), label + " has issues without batch or changes");
        check(before.equals(state()), label + " writes nothing");
        return result;
    }
    private static void invalidPreview(Map<String, Object> invalid, String label) throws Exception { invalidPreview(manager, scope, invalid, label); }
    private static void publish(String material, List<Map<String, Object>> proposed) throws Exception {
        long current = version(); confirm(preview(material, proposed), current);
    }
    private static void legacyPublish(Actor actor, long id, long expected, String material, List<Map<String, Object>> catalogCourses,
            List<Map<String, Object>> catalogTeachers, List<Map<String, Object>> catalogCerts, List<Map<String, Object>> explicitBindings) throws Exception {
        Map<String, Object> catalog = map("schema_version", "m04_catalog_v1", "catalog_version", material,
                "courses", catalogCourses, "teachers", catalogTeachers, "certifications", catalogCerts);
        Map<String, Object> result = call(actor, "POST", path(id, "/preview"), map("expected_version", expected, "catalog", catalog,
                "bindings", explicitBindings, "change_comment", "合成旧完整导入兼容性种子"));
        check(Boolean.TRUE.equals(result.get("ready")), "legacy complete import remains available");
        call(actor, "POST", path(id, "/confirm"), confirmBody((String) result.get("batch_id"), expected));
    }
    private static Map<String, Object> query(String course, String day) {
        return map("course_code", course, "as_of", day, "accepted_levels", List.of(), "allowed_cities", List.of());
    }
    private static Map<String, Object> qualify(String course, String day) throws Exception {
        return call(reader, "POST", path(scope, "/qualify"), map("expected_version", version(), "request", query(course, day)));
    }
    private static boolean reason(Map<String, Object> result, String teacher, String code) {
        return rows(result, "gaps").stream().filter(row -> teacher.equals(row.get("teacher_code")))
                .anyMatch(row -> rows(row, "reasons").stream().anyMatch(item -> code.equals(item.get("code"))));
    }
    private static void authorizationAndZeroVersion() throws Exception {
        Map<String, Object> before = state(), empty = manage(); exactManagement(empty, scope, 0);
        check("001".equals(empty.get("organization_code")) && empty.get("catalog_version") == null && rows(empty, "courses").isEmpty()
                        && rows(empty, "certifications").isEmpty() && ((List<?>) empty.get("teacher_codes")).isEmpty()
                        && object(empty.get("retained_counts")).equals(Json.parseMap("{\"courses\":0,\"teachers\":0,\"bindings\":0}")), "version zero returns an explicit empty maintenance view");
        check(call(manager, "HEAD", path(scope, "/certification-management"), null).equals(empty) && before.equals(state()), "GET and HEAD at version zero never create catalog state");
        for (Actor actor : List.of(reader, denied, other, admin, unbound)) {
            rejectedUnchanged(403, () -> call(actor, "GET", path(scope, "/certification-management"), null), "non-manager cannot open certification maintenance");
            rejectedUnchanged(403, () -> call(actor, "POST", path(scope, "/certifications-preview"), body(0, "forbidden", List.of())), "non-manager cannot preview certification changes");
        }
        check(call(colleague, "GET", path(scope, "/certification-management"), null).equals(empty), "combined grant can read management");
        rejects(403, () -> call(manager, "GET", path(scope, ""), null), "manage-only grant does not imply catalog read");
        rejects(403, () -> call(manager, "POST", path(scope, "/qualify"), map("expected_version", 0, "request", query("001", "2026-09-23"))), "manage-only grant does not imply qualification");
        rejects(403, () -> call(manager, "GET", path(otherScope, "/certification-management"), null), "cross-organization management is forbidden");
        rejectedUnchanged(403, () -> call(manager, "POST", path(otherScope, "/certifications-preview"), body(0, "cross-org", List.of())), "cross-organization certification write is forbidden");
        rejects(401, () -> call(null, "GET", path(scope, "/certification-management"), null), "certification management needs a live Auth token");
        rejects(405, () -> call(manager, "POST", path(scope, "/certification-management"), map()), "management only accepts GET/HEAD");
        rejects(405, () -> call(manager, "GET", path(scope, "/certifications-preview"), null), "preview only accepts POST");
        rejects(400, () -> call(manager, "GET", path(scope, "/certification-management?organization_code=002"), null), "ownership query cannot override saved scope");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(0, "zero-no-op", List.of())), "empty version-zero certification update is not a publication");
        invalidPreview(body(0, "zero-reference", List.of(cert("0001", "001", "certified"))), "version-zero scope cannot invent teacher/course references");
    }
    private static void seedCurrentSubset() throws Exception {
        List<Map<String, Object>> historicalTeachers = teachers(); historicalTeachers.add(map("teacher_code", "HISTORICAL", "teacher_level", "L1", "city", "合成甲城"));
        List<Map<String, Object>> historicalBindings = bindings(); historicalBindings.add(map("teacher_code", "HISTORICAL", "teacher_id", 103));
        legacyPublish(manager, scope, 0, "synthetic-legacy-v1", courses(), historicalTeachers, List.of(), historicalBindings);
        List<Map<String, Object>> certificates = List.of(cert("0001", "001", "unknown"), cert("0002", "001", "not_certified"));
        legacyPublish(manager, scope, 1, "synthetic-current-subset-v2", courses(), teachers(), certificates, bindings());
        Map<String, Object> current = manage(); exactManagement(current, scope, 2);
        check(current.get("teacher_codes").equals(List.of("0001", "0002")) && Db.count("m04_teacher_bindings") == 3,
                "teacher choices are the verified current catalog/binding subset, excluding historical permanent bindings");
        check(rows(current, "courses").equals(courses()) && rows(current, "certifications").equals(certificates)
                        && object(current.get("retained_counts")).equals(Json.parseMap("{\"courses\":3,\"teachers\":2,\"bindings\":2}")), "management reads original course/certification rows and counts current bindings only");
        Map<String, Object> result = qualify("001", "2026-09-23");
        check(Boolean.TRUE.equals(result.get("ready")) && rows(result, "eligible").isEmpty()
                        && reason(result, "0001", "CERTIFICATION_UNKNOWN") && reason(result, "0002", "NOT_CERTIFIED"), "unknown and not_certified are explicitly ineligible");
    }
    private static void malformedInputs() throws Exception {
        List<Map<String, Object>> certificates = rows(manage(), "certifications");
        for (String field : List.of("courses", "teachers", "bindings", "catalog", "teacher_codes", "scope_id", "organization_code", "actor_person")) {
            Map<String, Object> invalid = body(2, "extra", certificates); invalid.put(field, List.of());
            rejectedUnchanged(400, () -> call(manager, "POST", path(scope, "/certifications-preview"), invalid), "body rejects client-controlled " + field);
        }
        for (String field : List.of("expected_version", "catalog_version", "certifications", "change_comment")) {
            Map<String, Object> invalid = body(2, "missing", certificates); invalid.remove(field);
            rejectedUnchanged(400, () -> call(manager, "POST", path(scope, "/certifications-preview"), invalid), "body requires " + field);
        }
        for (Object value : Arrays.asList("2", -1, 2.5, 9007199254740992L, null)) {
            Map<String, Object> invalid = body(2, "version", certificates); invalid.put("expected_version", value);
            rejectedUnchanged(400, () -> call(manager, "POST", path(scope, "/certifications-preview"), invalid), "expected version must be a safe nonnegative integer");
        }
        for (Object value : Arrays.asList("", " ", "x".repeat(501), 123, null)) {
            Map<String, Object> invalid = body(2, "comment", certificates); invalid.put("change_comment", value);
            rejectedUnchanged(400, () -> call(manager, "POST", path(scope, "/certifications-preview"), invalid), "change comment must be required bounded text");
        }
        for (Object value : Arrays.asList("", "x".repeat(121), 17, null)) {
            Map<String, Object> invalid = body(2, "material", certificates); invalid.put("catalog_version", value);
            invalidPreview(invalid, "catalog version must satisfy original core rules");
        }
        for (Object value : Arrays.asList("certified-ish", "CERTIFIED", true, null)) {
            Map<String, Object> invalid = copy(body(2, "status", certificates)); rows(invalid, "certifications").get(0).put("status", value);
            invalidPreview(invalid, "unsupported certification state");
        }
        for (Map<String, Object> edit : List.of(map("source_ref", ""), map("source_ref", 123), map("source_ref", "x".repeat(241)),
                map("valid_from", "2026-02-30"), map("valid_to", "2026/12/31"), map("valid_from", "2026-12-31", "valid_to", "2026-01-01"),
                map("teacher_name", "unexpected"))) {
            Map<String, Object> invalid = copy(body(2, "invalid-row", certificates)); Map<String, Object> first = rows(invalid, "certifications").get(0);
            first.put("status", "certified"); first.put("source_ref", "SYNTHETIC-SOURCE"); first.putAll(edit);
            invalidPreview(invalid, "invalid source/date/property is rejected by original core");
        }
        Map<String, Object> missingDate = copy(body(2, "missing-date", certificates)); rows(missingDate, "certifications").get(0).remove("valid_to");
        invalidPreview(missingDate, "every certification requires all six fields");
        for (Map<String, Object> extra : List.of(cert("HISTORICAL", "001", "certified"), cert("UNBOUND", "001", "certified"),
                cert("0001", "MISSING", "certified"), cert("0001", "001", "revoked"))) {
            Map<String, Object> invalid = copy(body(2, "bad-reference", certificates)); rows(invalid, "certifications").add(extra);
            invalidPreview(invalid, "historical, unbound, unknown-course or duplicate certification reference");
        }
        for (Object value : Arrays.asList("not-an-array", 1, null)) {
            Map<String, Object> invalid = body(2, "bad-list", certificates); invalid.put("certifications", value); invalidPreview(invalid, "certifications must be an array");
        }
        Map<String, Object> badRow = copy(body(2, "bad-row", certificates));
        ((List<Object>) badRow.get("certifications")).add("not-an-object"); invalidPreview(badRow, "certification rows must be objects");
    }
    private static void firstChangesAndPreservation() throws Exception {
        Map<String, Object> original = call(reader, "GET", path(scope, ""), null);
        List<Map<String, Object>> changed = rows(manage(), "certifications");
        Map<String, Object> first = changed.get(0); first.put("status", "certified"); first.put("source_ref", "SYNTHETIC-EVIDENCE-A");
        first.put("valid_from", "2026-01-01"); first.put("valid_to", "2026-12-31");
        changed.add(cert("0001", "002", "certified"));
        changed.add(cert("0002", "003", "certified"));
        String batch = preview("synthetic-certification-v3", changed), stale = preview("synthetic-certification-loser-v3", changed);
        Map<String, Object> pending = Db.one("SELECT * FROM m04_catalog_batches WHERE batch_id=?", batch);
        Map<String, Object> payload = Json.parseMap((String) pending.get("payload"));
        Map<String, Object> originalCatalog = object(original.get("catalog"));
        check(number(pending.get("actor_account")) == 2 && "0001".equals(pending.get("actor_person"))
                        && "synthetic-certification-identity-v1".equals(pending.get("identity_version")), "pending certification change records trusted Auth account, person and M01 version");
        check(rows(payload, "courses").equals(rows(originalCatalog, "courses")) && rows(payload, "teachers").equals(rows(originalCatalog, "teachers"))
                        && Json.parse((String) pending.get("bindings")).equals(original.get("bindings")), "server freezes all non-certification catalog data unchanged");
        rejectedUnchanged(403, () -> call(colleague, "POST", path(scope, "/confirm"), confirmBody(batch, 2)), "different authorized account/person cannot confirm the preview");
        rejectedUnchanged(403, () -> call(reader, "POST", path(scope, "/confirm"), confirmBody(batch, 2)), "read-only account cannot confirm");
        rejectedUnchanged(409, () -> confirm(batch, 3), "confirmation must carry original expected version");
        Map<String, Object> saved = confirm(batch, 2);
        check("CONFIRMED".equals(saved.get("status")) && number(saved.get("version")) == 3, "certification-only confirmation advances the catalog once");
        Map<String, Object> before = state(), repeated = confirm(batch, 2);
        check("ALREADY_CONFIRMED".equals(repeated.get("status")) && number(repeated.get("version")) == 3 && before.equals(state()), "same-person confirmation is idempotent");
        rejectedUnchanged(409, () -> confirm(stale, 2), "pending preview loses CAS after another batch publishes");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(2, "stale-new-preview", changed)), "new preview rejects stale expected version");
        Map<String, Object> current = call(reader, "GET", path(scope, ""), null); Map<String, Object> catalog = object(current.get("catalog"));
        check(rows(catalog, "courses").equals(rows(originalCatalog, "courses")) && rows(catalog, "teachers").equals(rows(originalCatalog, "teachers"))
                        && current.get("bindings").equals(original.get("bindings")) && rows(catalog, "certifications").equals(changed), "confirmed revision preserves courses/teachers/bindings and updates only certification evidence");
        Map<String, Object> event = Db.one("SELECT changes FROM m04_catalog_events WHERE scope_id=? AND version=3", scope);
        retainedDiffs(Json.parseMap((String) event.get("changes")));
        for (String day : List.of("2026-01-01", "2026-09-23", "2026-12-31")) {
            Map<String, Object> result = qualify("001", day);
            check(rows(result, "eligible").size() == 1 && number(rows(result, "eligible").get(0).get("teacher_id")) == 101
                            && object(rows(result, "eligible").get(0).get("evidence")).equals(first), "certified source and inclusive validity bounds drive qualification on " + day);
        }
        check(reason(qualify("001", "2025-12-31"), "0001", "NOT_YET_VALID") && reason(qualify("001", "2027-01-01"), "0001", "EXPIRED"), "dates outside evidence bounds remain ineligible");
        Map<String, Object> inactive = qualify("002", "2026-09-23");
        check(Boolean.FALSE.equals(inactive.get("ready")) && rows(inactive, "eligible").isEmpty()
                        && rows(inactive, "issues").stream().anyMatch(issue -> "COURSE_INACTIVE".equals(issue.get("code"))), "inactive course accepts evidence maintenance but rejects qualification");
        Map<String, Object> unspecified = qualify("003", "2026-09-23");
        check(rows(unspecified, "eligible").size() == 1 && rows(unspecified, "issues").stream().anyMatch(issue -> "VALIDITY_UNSPECIFIED".equals(issue.get("code"))),
                "blank validity retains the original explicit warning rather than inventing dates");
    }
    private static void preservationAndNoOps() throws Exception {
        List<Map<String, Object>> current = rows(manage(), "certifications");
        List<Map<String, Object>> removed = rows(copy(map("certifications", current)), "certifications"); removed.remove(0);
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(3, "delete-pair", removed)), "existing teacher/course pair cannot be deleted");
        List<Map<String, Object>> courseReplaced = rows(copy(map("certifications", current)), "certifications"); courseReplaced.get(0).put("course_code", "003");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(3, "replace-course-key", courseReplaced)), "existing pair cannot be moved to another valid course");
        List<Map<String, Object>> teacherReplaced = rows(copy(map("certifications", current)), "certifications"); teacherReplaced.get(2).put("teacher_code", "0002");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(3, "replace-teacher-key", teacherReplaced)), "existing pair cannot be moved to another valid teacher");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(3, "clear-all", List.of())), "existing certifications cannot be cleared wholesale");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(3, "comment-only", current)), "comment/material version alone is not a certification change");
        List<Map<String, Object>> reordered = new ArrayList<>(current); Collections.reverse(reordered);
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(3, "reorder-only", reordered)), "reordering unchanged evidence is not a change");
        List<Map<String, Object>> realChange = rows(copy(map("certifications", current)), "certifications"); realChange.get(0).put("source_ref", "SYNTHETIC-CHANGED");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(3, "synthetic-certification-v3", realChange)), "published catalog material version cannot be reused");
    }
    private static void revokeAndCorrectEvidence() throws Exception {
        List<Map<String, Object>> current = rows(manage(), "certifications");
        current.get(0).put("status", "revoked"); current.get(0).put("source_ref", "SYNTHETIC-REVOCATION-NOTICE");
        publish("synthetic-revocation-v4", current);
        Map<String, Object> revoked = qualify("001", "2026-09-23");
        check(rows(revoked, "eligible").isEmpty() && reason(revoked, "0001", "CERTIFICATION_REVOKED")
                        && rows(manage(), "certifications").size() == 4, "revocation retains the original pair and removes qualification");
        current.get(0).put("status", "certified"); current.get(0).put("source_ref", "SYNTHETIC-CORRECTION-NOTICE");
        current.get(0).put("valid_from", "2026-10-01"); current.get(0).put("valid_to", "2027-09-30");
        publish("synthetic-corrected-future-v5", current);
        check(reason(qualify("001", "2026-09-23"), "0001", "NOT_YET_VALID"), "corrected future certification does not qualify early");
        current.get(0).put("valid_from", "2026-09-01"); current.get(0).put("source_ref", "SYNTHETIC-CORRECTION-FINAL");
        publish("synthetic-corrected-current-v6", current);
        Map<String, Object> corrected = qualify("001", "2026-09-23");
        check(rows(corrected, "eligible").size() == 1 && object(rows(corrected, "eligible").get(0).get("evidence")).equals(current.get(0)),
                "date/source correction restores qualification using the newly confirmed exact evidence");
    }
    private static void conflictsAndCorruption() throws Exception {
        long currentVersion = version(); Map<String, Object> savedView = manage();
        List<Map<String, Object>> changed = rows(copy(savedView), "certifications"); changed.get(0).put("source_ref", "SYNTHETIC-PENDING-FACT-CHECK");
        String pending = preview("synthetic-pending-fact-check", changed);
        for (Map<String, Object> mutation : List.of(map("field", "teacher_level", "value", "L9", "original", "L1"),
                map("field", "base_city", "value", "合成漂移城", "original", "合成甲城"))) {
            String field = (String) mutation.get("field"); Db.exec("UPDATE teachers SET " + field + "=? WHERE id=101", mutation.get("value"));
            try {
                check(manage().equals(savedView), "management reads saved codes/evidence without refreshing changed live " + field);
                rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(currentVersion, "metadata-drift", changed)), "preview refuses retained teacher metadata drift");
                rejectedUnchanged(409, () -> confirm(pending, currentVersion), "confirmation refuses teacher metadata drift");
            } finally { Db.exec("UPDATE teachers SET " + field + "=? WHERE id=101", mutation.get("original")); }
        }
        Db.exec("UPDATE teachers SET status='出库' WHERE id=101");
        try { rejectedUnchanged(409, () -> confirm(pending, currentVersion), "confirmation detects changed teacher status even without catalog metadata drift"); }
        finally { Db.exec("UPDATE teachers SET status='在库' WHERE id=101"); }
        Map<String, Object> original = Db.one("SELECT payload,bindings,catalog_version FROM m04_catalog_revisions WHERE scope_id=? AND version=?", scope, currentVersion);
        Map<String, Object> badCert = Json.parseMap((String) original.get("payload")); rows(badCert, "certifications").get(0).put("source_ref", "");
        Map<String, Object> badCourse = Json.parseMap((String) original.get("payload")); rows(badCourse, "courses").get(0).put("active", "true");
        Map<String, Object> badReference = Json.parseMap((String) original.get("payload")); rows(badReference, "certifications").get(0).put("teacher_code", "MISSING");
        Map<String, Object> badSchema = Json.parseMap((String) original.get("payload")); badSchema.put("schema_version", "unsupported_schema");
        for (Map<String, Object> corruption : List.of(map("field", "payload", "value", "{"), map("field", "payload", "value", Json.write(badCert)),
                map("field", "payload", "value", Json.write(badCourse)), map("field", "payload", "value", Json.write(badReference)),
                map("field", "payload", "value", Json.write(badSchema)),
                map("field", "bindings", "value", Json.write(List.of(map("teacher_code", "0001", "teacher_id", 102), map("teacher_code", "0002", "teacher_id", 101)))),
                map("field", "catalog_version", "value", "CORRUPTED-MATERIAL-VERSION"))) {
            String field = (String) corruption.get("field");
            Db.exec("UPDATE m04_catalog_revisions SET " + field + "=? WHERE scope_id=? AND version=?", corruption.get("value"), scope, currentVersion);
            try {
                rejectedUnchanged(409, () -> manage(), "management refuses damaged saved " + field);
                rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(currentVersion, "corrupt-head", changed)), "preview refuses damaged saved " + field);
            } finally { Db.exec("UPDATE m04_catalog_revisions SET " + field + "=? WHERE scope_id=? AND version=?", original.get(field), scope, currentVersion); }
        }
        Db.exec("UPDATE m04_teacher_bindings SET teacher_id=999 WHERE scope_id=? AND teacher_code='0001'", scope);
        try {
            rejectedUnchanged(409, () -> manage(), "teacher choices fail closed when permanent and current bindings disagree");
            rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/certifications-preview"), body(currentVersion, "corrupt-permanent-binding", changed)), "preview fails closed when permanent and current bindings disagree");
        }
        finally { Db.exec("UPDATE m04_teacher_bindings SET teacher_id=101 WHERE scope_id=? AND teacher_code='0001'", scope); }
        Db.exec("UPDATE m04_catalog_scopes SET version=? WHERE scope_id=?", currentVersion + 100, scope);
        try { rejectedUnchanged(409, () -> manage(), "missing head revision cannot become an empty maintenance form"); }
        finally { Db.exec("UPDATE m04_catalog_scopes SET version=? WHERE scope_id=?", currentVersion, scope); }
        Db.exec("INSERT INTO m04_teacher_bindings(scope_id,teacher_code,teacher_id,created_version) VALUES(?,?,?,?)", otherScope, "0001", 101, 1);
        try { rejectedUnchanged(409, () -> call(other, "GET", path(otherScope, "/certification-management"), null), "version-zero scope with stray saved binding fails closed"); }
        finally { Db.exec("DELETE FROM m04_teacher_bindings WHERE scope_id=? AND teacher_code='0001'", otherScope); }
        check(manage().equals(savedView), "rejected drift/corruption operations never silently repair saved catalog evidence");
    }
    private static void publishedEmptyCoursesAndRowBoundary() throws Exception {
        legacyPublish(other, otherScope, 0, "synthetic-empty-courses-v1", List.of(), teachers(), List.of(), bindings());
        Map<String, Object> empty = call(other, "GET", path(otherScope, "/certification-management"), null); exactManagement(empty, otherScope, 1);
        check(rows(empty, "courses").isEmpty() && rows(empty, "certifications").isEmpty() && empty.get("teacher_codes").equals(List.of("0001", "0002"))
                        && "synthetic-empty-courses-v1".equals(empty.get("catalog_version"))
                        && object(empty.get("retained_counts")).equals(Json.parseMap("{\"courses\":0,\"teachers\":2,\"bindings\":2}")),
                "published empty courses preserve actual version and current teacher/binding choices");
        invalidPreview(other, otherScope, body(1, "empty-course-reference", List.of(cert("0001", "001", "certified"))), "empty course list cannot acquire invented course evidence");
        rejectedUnchanged(409, () -> call(other, "POST", path(otherScope, "/certifications-preview"), body(1, "empty-no-op", List.of())), "published empty evidence cannot create a no-op batch");
        List<Map<String, Object>> manyTeachers = teachers(), manyBindings = bindings(), manyCourses = new ArrayList<>(), manyCerts = new ArrayList<>();
        for (int i = 3; i <= 100; i++) {
            String code = String.format(Locale.ROOT, "T%03d", i); long id = 1000 + i;
            Db.exec("INSERT INTO teachers VALUES(?,'L1','合成甲城','在库')", id);
            manyTeachers.add(map("teacher_code", code, "teacher_level", "L1", "city", "合成甲城")); manyBindings.add(map("teacher_code", code, "teacher_id", id));
        }
        for (int i = 1; i <= 50; i++) manyCourses.add(map("course_code", String.format(Locale.ROOT, "C%03d", i), "course_name", "合成边界课程" + i, "active", true));
        legacyPublish(other, otherScope, 1, "synthetic-boundary-base-v2", manyCourses, manyTeachers, List.of(), manyBindings);
        for (Map<String, Object> teacher : manyTeachers) for (Map<String, Object> course : manyCourses)
            manyCerts.add(cert((String) teacher.get("teacher_code"), (String) course.get("course_code"), "not_certified"));
        check(manyCerts.size() == 5000, "boundary fixture has exactly 5000 distinct current teacher/course pairs");
        String accepted = preview(other, otherScope, 2, "synthetic-exactly-5000", manyCerts);
        check(rows(Json.parseMap((String) Db.one("SELECT payload FROM m04_catalog_batches WHERE batch_id=?", accepted).get("payload")), "certifications").size() == 5000,
                "exactly 5000 certification rows are accepted without truncation");
        List<Map<String, Object>> tooMany = new ArrayList<>(manyCerts); tooMany.add(cert("0001", "C001", "revoked"));
        Map<String, Object> oversized = invalidPreview(other, otherScope, body(2, "synthetic-5001", tooMany), "5001 certification rows exceed the unchanged core limit");
        check(rows(oversized, "issues").stream().anyMatch(issue -> "LIMIT".equals(issue.get("code")) && "certifications".equals(issue.get("field"))),
                "oversized certification array reports the explicit row LIMIT, not merely a duplicate pair");
    }
    private static void currentIdentityRequiredAtConfirmation() throws Exception {
        List<Map<String, Object>> changed = rows(manage(), "certifications"); changed.get(0).put("source_ref", "SYNTHETIC-M01-CHECK");
        long current = version(); String pending = preview("synthetic-before-m01-change", changed);
        OrganizationAccessStore.publish(admin.session(), "synthetic-certification-identity-v1", identity("synthetic-certification-identity-v2"));
        rejectedUnchanged(409, () -> confirm(pending, current), "same authorized person must re-preview after M01 configuration version changes");
        String actorPending = preview("synthetic-before-person-remap", changed);
        Configuration remapped = identity("synthetic-certification-identity-v3");
        List<AccountBinding> remappedBindings = remapped.accountBindings().stream().map(binding -> binding.accountId() == 2
                ? new AccountBinding(2, "0003", true) : binding.accountId() == 4 ? new AccountBinding(4, "0001", true) : binding).toList();
        remapped = new Configuration(remapped.version(), remapped.codeRules(), remapped.roleCodes(), remapped.organizations(), remapped.people(), remapped.relations(), remappedBindings, remapped.grants());
        OrganizationAccessStore.publish(admin.session(), "synthetic-certification-identity-v2", remapped);
        rejectedUnchanged(401, () -> confirm(actorPending, current), "person remapping revokes the original real Auth session");
        manager = login("manager"); colleague = login("colleague");
        check(Auth.current(manager.session()) == manager.session() && "0003".equals(OrganizationAccessStore.person(manager.session()).personCode()), "real live Auth account now maps to a different currently authorized person");
        rejectedUnchanged(403, () -> confirm(actorPending, current), "same account cannot confirm after its personnel identity changes");
        rejectedUnchanged(403, () -> call(colleague, "POST", path(scope, "/confirm"), confirmBody(actorPending, current)), "original person cannot confirm from a different account");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass one new empty temporary directory");
        setup(Path.of(args[0]).toAbsolutePath());
        try {
            List<Map<String, Object>> schema = Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME");
            authorizationAndZeroVersion(); seedCurrentSubset(); malformedInputs(); firstChangesAndPreservation(); preservationAndNoOps();
            revokeAndCorrectEvidence(); conflictsAndCorruption(); publishedEmptyCoursesAndRowBoundary(); currentIdentityRequiredAtConfirmation();
            check(schema.equals(Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME")), "certification maintenance introduces no tables");
            System.out.println("M04CertificationMaintenance: " + checks + " checks passed (real Auth/M01, isolated synthetic H2, no server or production data)");
        } finally { Db.exec("SHUTDOWN"); }
    }
    private static final class Exchange extends HttpExchange {
        private final String method; private final URI uri;
        private final Headers requestHeaders = new Headers(), responseHeaders = new Headers();
        private final Map<String, Object> attributes = new HashMap<>();
        Exchange(String method, String target, String token, Map<String, Object> body) {
            this.method = method; this.uri = URI.create(target);
            if (token != null) requestHeaders.set("X-Token", token);
            requestHeaders.set("Content-Type", "application/json");
            if (body != null) attributes.put("com.training.Api.body", copy(body));
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
