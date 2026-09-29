package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Course-only maintenance contract: real Auth/M01, an empty synthetic H2, no server. */
public final class M04CourseMaintenanceTest {
    private static final String ROOT = "/api/course-catalog/scopes";
    private static final String PASSWORD = "SYNTHETIC-M04-COURSE-MAINTENANCE-20260923";
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
        String token = Auth.login("synthetic-course-" + name, PASSWORD);
        Auth.Session session = Auth.get(token);
        check(token != null && session != null && Auth.current(session) == session, "real live Auth session for " + name);
        return new Actor(token, session);
    }
    private static Configuration identity() {
        Set<String> roles = Set.of("MANAGE", "READ", "BOTH", "NONE");
        RelationRule optional = new RelationRule(false, roles, false, false);
        return new Configuration("synthetic-course-identity-v1", new CodeRules("[0-9]{3}", "[0-9]{4}", "[A-Z]+"), roles,
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
                "synthetic-course-" + names[i], hash, "SYNTHETIC " + names[i], i == 0 ? "admin" : i == 2 || i == 5 || i == 6 ? "manager" : "viewer");
        Db.exec("INSERT INTO teachers VALUES(101,'L1','合成甲城','在库')");
        Db.exec("INSERT INTO teachers VALUES(102,'L2','合成乙城','在库')");
        OrganizationAccessStore.init(); CourseCatalogIntegration.init();
        admin = login("admin");
        OrganizationAccessStore.publish(admin.session(), null, identity());
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
    private static Map<String, Object> manage() throws Exception { return call(manager, "GET", path(scope, "/course-management"), null); }
    private static List<Map<String, Object>> courses() {
        return new ArrayList<>(List.of(map("course_code", "001", "course_name", "合成课程甲", "active", true),
                map("course_code", "002", "course_name", "合成课程乙", "active", true)));
    }
    private static Map<String, Object> body(long version, String materialVersion, List<Map<String, Object>> courses) {
        return map("expected_version", version, "catalog_version", materialVersion, "courses", courses,
                "change_comment", "合成课程维护说明 " + materialVersion);
    }
    private static Map<String, Object> confirmBody(String batch, long version) { return map("batch_id", batch, "expected_version", version, "confirm", true); }
    private static Map<String, Object> confirm(String batch, long version) throws Exception {
        return call(manager, "POST", path(scope, "/confirm"), confirmBody(batch, version));
    }
    private static Map<String, Object> state() throws Exception {
        return map("version", number(Db.one("SELECT version FROM m04_catalog_scopes WHERE scope_id=?", scope).get("version")),
                "batches", Db.count("m04_catalog_batches"), "revisions", Db.count("m04_catalog_revisions"),
                "events", Db.count("m04_catalog_events"), "bindings", Db.count("m04_teacher_bindings"));
    }
    private static void rejectedUnchanged(int status, Work work, String label) throws Exception {
        Map<String, Object> before = state(); rejects(status, work, label);
        check(before.equals(state()), label + " leaves batch/head/revision/event/binding counts unchanged");
    }
    private static void previewEnvelope(Map<String, Object> result, long expected) {
        check("m04_preview_v1".equals(result.get("schema_version")) && number(result.get("scope_id")) == scope
                        && "001".equals(result.get("organization_code")) && number(result.get("current_version")) == expected,
                "course preview retains standard schema with trusted scope and current version");
    }
    private static void unchangedRetained(Map<String, Object> changes) {
        check(changes.keySet().equals(Set.of("courses", "teachers", "certifications", "bindings")), "preview lists four change categories");
        for (String table : List.of("teachers", "certifications", "bindings")) {
            Map<String, Object> delta = object(changes.get(table));
            check(delta.keySet().equals(Set.of("added", "updated", "removed"))
                            && delta.values().stream().allMatch(value -> value instanceof List<?> list && list.isEmpty()),
                    "course-only changes retain every " + table + " row");
        }
    }
    private static String preview(long expected, String materialVersion, List<Map<String, Object>> proposed) throws Exception {
        Map<String, Object> before = state();
        Map<String, Object> result = call(manager, "POST", path(scope, "/courses-preview"), body(expected, materialVersion, proposed));
        previewEnvelope(result, expected);
        check(Boolean.TRUE.equals(result.get("ready")) && Boolean.TRUE.equals(result.get("confirmation_required"))
                        && result.get("batch_id") instanceof String batch && !batch.isBlank(), "valid course preview creates explicit pending confirmation");
        unchangedRetained(object(result.get("changes")));
        Map<String, Object> after = state(); before.put("batches", number(before.get("batches")) + 1);
        check(before.equals(after), "preview changes only the pending batch count");
        return (String) result.get("batch_id");
    }
    private static void invalidPreview(Map<String, Object> invalid, String label) throws Exception {
        Map<String, Object> before = state();
        Map<String, Object> result = call(manager, "POST", path(scope, "/courses-preview"), invalid);
        previewEnvelope(result, number(invalid.get("expected_version")));
        check(Boolean.FALSE.equals(result.get("ready")) && result.get("batch_id") == null
                        && Boolean.FALSE.equals(result.get("confirmation_required")) && result.containsKey("changes") && result.get("changes") == null
                        && !rows(result, "issues").isEmpty(), label + " has issues and no batch or changes");
        check(before.equals(state()), label + " writes nothing");
    }
    private static void authorizationAndZeroVersion() throws Exception {
        for (Actor actor : List.of(manager, reader, colleague, other)) {
            List<Map<String, Object>> visible = rows(call(actor, "GET", ROOT, null), "scopes");
            boolean isOther = actor == other;
            check(visible.size() == 1 && number(visible.get(0).get("scope_id")) == (isOther ? otherScope : scope), "scope listing hides other organization");
            Map<String, Object> listed = visible.get(0);
            check(Objects.equals(listed.get("can_read"), actor != manager) && Objects.equals(listed.get("can_manage"), actor != reader),
                    "read/manage flags independently reflect real M01 grants");
        }
        check(rows(call(denied, "GET", ROOT, null), "scopes").isEmpty(), "mapped account without grants sees no scope");
        Map<String, Object> empty = manage();
        check(number(empty.get("scope_id")) == scope && "001".equals(empty.get("organization_code")) && number(empty.get("version")) == 0
                        && empty.containsKey("catalog_version") && empty.get("catalog_version") == null && rows(empty, "courses").isEmpty()
                        && Boolean.TRUE.equals(empty.get("can_manage")), "manage-only account reads explicit empty maintenance state");
        check(object(empty.get("retained_counts")).equals(Json.parseMap("{\"teachers\":0,\"certifications\":0,\"bindings\":0}")), "zero-version retained counts are zero");
        check(!empty.containsKey("teachers") && !empty.containsKey("certifications") && !empty.containsKey("bindings") && !empty.containsKey("catalog"),
                "maintenance response exposes courses and retained counts only");
        for (Actor actor : List.of(reader, denied, other, admin, unbound)) {
            rejectedUnchanged(403, () -> call(actor, "GET", path(scope, "/course-management"), null), "non-manager cannot read maintenance form");
            rejectedUnchanged(403, () -> call(actor, "POST", path(scope, "/courses-preview"), body(0, "forbidden", courses())), "non-manager cannot preview maintenance");
        }
        rejects(403, () -> call(manager, "GET", path(scope, ""), null), "manage grant does not grant legacy read");
        rejects(403, () -> call(manager, "POST", path(scope, "/qualify"), map("expected_version", 0, "request", query())), "manage grant does not grant qualification");
        rejects(403, () -> call(manager, "GET", path(otherScope, "/course-management"), null), "manager cannot open another organization");
        rejectedUnchanged(403, () -> call(manager, "POST", path(otherScope, "/courses-preview"), body(0, "cross-org", courses())), "manager cannot preview another organization");
        rejects(401, () -> call(null, "GET", path(scope, "/course-management"), null), "maintenance needs real Auth token");
        rejects(405, () -> call(manager, "POST", path(scope, "/course-management"), map()), "maintenance read method is restricted");
        rejects(405, () -> call(manager, "GET", path(scope, "/courses-preview"), null), "course preview requires POST");
        rejects(400, () -> call(manager, "GET", path(scope, "/course-management?organization_code=002"), null), "management rejects ownership query");
    }
    private static void malformedInputs() throws Exception {
        for (String field : List.of("catalog", "teachers", "certifications", "bindings", "organization_code", "actor_account")) {
            Map<String, Object> invalid = body(0, "extra-field", courses()); invalid.put(field, List.of());
            rejectedUnchanged(400, () -> call(manager, "POST", path(scope, "/courses-preview"), invalid), "course body rejects client " + field);
        }
        for (String field : List.of("expected_version", "catalog_version", "courses", "change_comment")) {
            Map<String, Object> invalid = body(0, "missing-field", courses()); invalid.remove(field);
            rejectedUnchanged(400, () -> call(manager, "POST", path(scope, "/courses-preview"), invalid), "course body requires " + field);
        }
        for (Object value : Arrays.asList("0", -1, 0.5, 9007199254740992L, true, null)) {
            Map<String, Object> invalid = body(0, "bad-version", courses()); invalid.put("expected_version", value);
            rejectedUnchanged(400, () -> call(manager, "POST", path(scope, "/courses-preview"), invalid), "expected version must be a safe integer");
        }
        for (Object value : Arrays.asList("", "   ", "x".repeat(501), 123, null)) {
            Map<String, Object> invalid = body(0, "bad-comment", courses()); invalid.put("change_comment", value);
            rejectedUnchanged(400, () -> call(manager, "POST", path(scope, "/courses-preview"), invalid), "change comment is required plain text");
        }
        Map<String, Object> invalid = body(0, "duplicate", courses()); rows(invalid, "courses").add(copy(rows(invalid, "courses").get(0)));
        invalidPreview(invalid, "duplicate course code");
        invalid = body(0, "unknown-course-field", courses()); rows(invalid, "courses").get(0).put("certified", true);
        invalidPreview(invalid, "unknown course property");
        for (Object value : List.of("true", 1)) {
            invalid = body(0, "bad-active", courses()); rows(invalid, "courses").get(0).put("active", value);
            invalidPreview(invalid, "active must be boolean");
        }
        invalid = body(0, "bad-code", courses()); rows(invalid, "courses").get(0).put("course_code", 1);
        invalidPreview(invalid, "numeric course code cannot lose leading zero");
        invalid = body(0, "bad-name", courses()); rows(invalid, "courses").get(0).put("course_name", "  ");
        invalidPreview(invalid, "blank course name");
        invalid = body(0, "bad-shape", courses()); invalid.put("courses", "not-an-array");
        invalidPreview(invalid, "courses must be an array");
    }
    private static void firstPublicationAndConfirmation() throws Exception {
        String first = preview(0, "synthetic-course-v1", courses()), stale = preview(0, "synthetic-course-v1-loser", courses());
        Map<String, Object> batch = Db.one("SELECT * FROM m04_catalog_batches WHERE batch_id=?", first);
        Map<String, Object> catalog = Json.parseMap((String) batch.get("payload"));
        check(rows(catalog, "teachers").isEmpty() && rows(catalog, "certifications").isEmpty() && "[]".equals(batch.get("bindings")), "first course preview freezes empty retained data");
        rejectedUnchanged(403, () -> call(colleague, "POST", path(scope, "/confirm"), confirmBody(first, 0)), "other authorized preview actor cannot confirm");
        rejectedUnchanged(403, () -> call(reader, "POST", path(scope, "/confirm"), confirmBody(first, 0)), "reader cannot confirm");
        rejectedUnchanged(409, () -> confirm(first, 1), "confirm must match original expected version");
        Map<String, Object> saved = confirm(first, 0);
        check("CONFIRMED".equals(saved.get("status")) && number(saved.get("version")) == 1, "first course-only confirmation publishes version one");
        Map<String, Object> before = state(), again = confirm(first, 0);
        check("ALREADY_CONFIRMED".equals(again.get("status")) && number(again.get("version")) == 1 && before.equals(state()), "duplicate confirmation is idempotent");
        rejectedUnchanged(409, () -> confirm(stale, 0), "second preview loses CAS after first confirmation");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/courses-preview"), body(0, "stale-preview", courses())), "new preview rejects stale expected version");
        Map<String, Object> current = manage();
        check(number(current.get("version")) == 1 && "synthetic-course-v1".equals(current.get("catalog_version")) && rows(current, "courses").equals(courses()), "management reads confirmed courses exactly");
    }
    private static Map<String, Object> query() { return map("course_code", "001", "as_of", "2026-09-23", "accepted_levels", List.of("L1"), "allowed_cities", List.of("合成甲城")); }
    private static Map<String, Object> oldCatalog() {
        return map("schema_version", "m04_catalog_v1", "catalog_version", "synthetic-legacy-v2", "courses", courses(),
                "teachers", List.of(map("teacher_code", "0001", "teacher_level", "L1", "city", "合成甲城"), map("teacher_code", "0002", "teacher_level", "L2", "city", "合成乙城")),
                "certifications", List.of(map("teacher_code", "0001", "course_code", "001", "status", "certified", "source_ref", "SYNTHETIC-SOURCE-01", "valid_from", "2026-01-01", "valid_to", "2026-12-31"),
                        map("teacher_code", "0002", "course_code", "002", "status", "unknown", "source_ref", "", "valid_from", "", "valid_to", "")));
    }
    private static void legacySeedAndRetention() throws Exception {
        Map<String, Object> old = call(manager, "POST", path(scope, "/preview"), map("expected_version", 1, "catalog", oldCatalog(),
                "bindings", List.of(map("teacher_code", "0001", "teacher_id", 101), map("teacher_code", "0002", "teacher_id", 102)), "change_comment", "旧完整导入兼容性合成记录"));
        check(Boolean.TRUE.equals(old.get("ready")) && "m04_preview_v1".equals(old.get("schema_version")), "legacy full preview still accepts coded catalog and bindings");
        confirm((String) old.get("batch_id"), 1);
        Map<String, Object> snapshot = call(reader, "GET", path(scope, ""), null);
        Map<String, Object> previousCatalog = object(snapshot.get("catalog")); Object previousBindings = snapshot.get("bindings");
        Map<String, Object> qualified = call(reader, "POST", path(scope, "/qualify"), map("expected_version", 2, "request", query()));
        check(Boolean.TRUE.equals(qualified.get("ready")) && rows(qualified, "eligible").size() == 1
                        && number(rows(qualified, "eligible").get(0).get("teacher_id")) == 101, "legacy qualification still uses persisted evidence and binding");
        Map<String, Object> maintenance = manage();
        check(object(maintenance.get("retained_counts")).equals(Json.parseMap("{\"teachers\":2,\"certifications\":2,\"bindings\":2}")), "management exposes exact retained row counts");
        List<Map<String, Object>> changed = courses(); changed.get(0).put("course_name", "合成课程甲更名"); changed.get(1).put("active", false);
        changed.add(map("course_code", "003", "course_name", "合成新增课程丙", "active", true));
        String batch = preview(2, "synthetic-course-v3", changed);
        Map<String, Object> pending = Db.one("SELECT payload,bindings FROM m04_catalog_batches WHERE batch_id=?", batch);
        Map<String, Object> pendingCatalog = Json.parseMap((String) pending.get("payload"));
        check(rows(previousCatalog, "teachers").equals(rows(pendingCatalog, "teachers"))
                        && rows(previousCatalog, "certifications").equals(rows(pendingCatalog, "certifications"))
                        && previousBindings.equals(Json.parse((String) pending.get("bindings"))), "server freezes prior teachers, certifications and bindings unchanged");
        confirm(batch, 2);
        snapshot = call(reader, "GET", path(scope, ""), null); Map<String, Object> published = object(snapshot.get("catalog"));
        check(rows(published, "courses").equals(changed) && rows(published, "teachers").equals(rows(previousCatalog, "teachers"))
                        && rows(published, "certifications").equals(rows(previousCatalog, "certifications")) && snapshot.get("bindings").equals(previousBindings),
                "publish renames, deactivates and adds courses while preserving all retained data");
        qualified = call(reader, "POST", path(scope, "/qualify"), map("expected_version", 3, "request", query()));
        check(rows(qualified, "eligible").size() == 1 && number(rows(qualified, "eligible").get(0).get("teacher_id")) == 101, "retained certification still qualifies after course maintenance");
        List<Map<String, Object>> removed = new ArrayList<>(changed); removed.remove(1);
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/courses-preview"), body(3, "deleted-code", removed)), "old course code cannot be deleted even when inactive");
        List<Map<String, Object>> renamed = rows(copy(map("courses", changed)), "courses"); renamed.get(1).put("course_code", "REPLACEMENT");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/courses-preview"), body(3, "replaced-code", renamed)), "old course code cannot be replaced");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/courses-preview"), body(3, "all-deleted", List.of())), "all existing course codes cannot be removed");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/courses-preview"), body(3, "synthetic-course-v3", changed)), "published material version cannot be reused");
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/courses-preview"), body(3, "comment-only", changed)), "new version and comment alone cannot create a batch");
        List<Map<String, Object>> reordered = new ArrayList<>(changed); Collections.reverse(reordered);
        rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/courses-preview"), body(3, "reorder-only", reordered)), "reordering unchanged courses cannot create a batch");
    }
    private static void teacherFactConflicts() throws Exception {
        List<Map<String, Object>> current = rows(manage(), "courses");
        current.get(0).put("course_name", "合成资料冲突测试课程");
        String levelBatch = preview(3, "level-fact-pending", current);
        try {
            Db.exec("UPDATE teachers SET teacher_level='L9' WHERE id=101");
            rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/courses-preview"), body(3, "level-fact-change", current)), "course preview rejects stale retained teacher level");
            rejectedUnchanged(409, () -> confirm(levelBatch, 3), "course confirmation rejects teacher level change since preview");
        } finally { Db.exec("UPDATE teachers SET teacher_level='L1' WHERE id=101"); }
        String cityBatch = preview(3, "city-fact-pending", current);
        try {
            Db.exec("UPDATE teachers SET base_city='合成变化城' WHERE id=102");
            rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/courses-preview"), body(3, "city-fact-change", current)), "course preview rejects stale retained teacher city");
            rejectedUnchanged(409, () -> confirm(cityBatch, 3), "course confirmation rejects teacher city change since preview");
        } finally { Db.exec("UPDATE teachers SET base_city='合成乙城' WHERE id=102"); }
        Map<String, Object> persisted = Json.parseMap((String) Db.one("SELECT payload FROM m04_catalog_revisions WHERE scope_id=? AND version=3", scope).get("payload"));
        check("L1".equals(rows(persisted, "teachers").get(0).get("teacher_level")) && "合成乙城".equals(rows(persisted, "teachers").get(1).get("city")),
                "fact conflicts never silently refresh the frozen catalog");
    }
    private static void corruptSnapshotFailsClosed() throws Exception {
        List<Map<String, Object>> current = rows(manage(), "courses");
        Map<String, Object> original = Db.one("SELECT payload,bindings,catalog_version FROM m04_catalog_revisions WHERE scope_id=? AND version=3", scope);
        Map<String, Object> badCatalog = Json.parseMap((String) original.get("payload")); rows(badCatalog, "courses").get(0).put("active", "true");
        Map<String, Object> badReference = Json.parseMap((String) original.get("payload")); rows(badReference, "certifications").get(0).put("course_code", "MISSING");
        List<Map<String, Object>> badBindings = new ArrayList<>(List.of(map("teacher_code", "0001", "teacher_id", 102), map("teacher_code", "0002", "teacher_id", 101)));
        for (Map<String, Object> corruption : List.of(map("field", "payload", "value", "{"), map("field", "payload", "value", Json.write(badCatalog)),
                map("field", "payload", "value", Json.write(badReference)), map("field", "bindings", "value", Json.write(badBindings)),
                map("field", "catalog_version", "value", "mismatched-material-version"))) {
            String field = (String) corruption.get("field");
            Db.exec("UPDATE m04_catalog_revisions SET " + field + "=? WHERE scope_id=? AND version=3", corruption.get("value"), scope);
            try {
                rejectedUnchanged(409, () -> manage(), "management refuses corrupted saved " + field);
                rejectedUnchanged(409, () -> call(manager, "POST", path(scope, "/courses-preview"), body(3, "corrupt-head", current)), "course preview refuses corrupted saved " + field);
            } finally { Db.exec("UPDATE m04_catalog_revisions SET " + field + "=? WHERE scope_id=? AND version=3", original.get(field), scope); }
        }
        check(number(manage().get("version")) == 3 && Db.count("m04_catalog_revisions") == 3 && Db.count("m04_catalog_events") == 3
                        && Db.count("m04_teacher_bindings") == 2, "restored snapshot is readable and all rejected operations left publication state intact");
    }
    private static void deactivateAndReactivate() throws Exception {
        List<Map<String, Object>> current = rows(manage(), "courses"); current.get(0).put("active", false);
        confirm(preview(3, "synthetic-course-inactive-v4", current), 3);
        Map<String, Object> result = call(reader, "POST", path(scope, "/qualify"), map("expected_version", 4, "request", query()));
        check(Boolean.FALSE.equals(result.get("ready")) && rows(result, "eligible").isEmpty()
                        && rows(result, "issues").stream().anyMatch(issue -> "COURSE_INACTIVE".equals(issue.get("code"))),
                "deactivated course no longer produces qualified candidates");
        current.get(0).put("active", true);
        confirm(preview(4, "synthetic-course-reactivated-v5", current), 4);
        result = call(reader, "POST", path(scope, "/qualify"), map("expected_version", 5, "request", query()));
        check(rows(result, "eligible").size() == 1, "reactivated course qualifies its originally certified teacher");
        Map<String, Object> evidence = object(rows(result, "eligible").get(0).get("evidence"));
        check("SYNTHETIC-SOURCE-01".equals(evidence.get("source_ref")) && "2026-01-01".equals(evidence.get("valid_from"))
                        && "2026-12-31".equals(evidence.get("valid_to")), "reactivation uses the original certification source and validity dates");
    }
    private static void publishedEmptyCoursesRetainTeachers() throws Exception {
        Map<String, Object> catalog = oldCatalog(); catalog.put("courses", List.of()); catalog.put("certifications", List.of());
        catalog.put("catalog_version", "synthetic-empty-courses-v1");
        List<Map<String, Object>> bindings = List.of(map("teacher_code", "0001", "teacher_id", 101), map("teacher_code", "0002", "teacher_id", 102));
        Map<String, Object> old = call(other, "POST", path(otherScope, "/preview"), map("expected_version", 0, "catalog", catalog,
                "bindings", bindings, "change_comment", "合成空课程但保留师资目录"));
        check(Boolean.TRUE.equals(old.get("ready")), "legacy route accepts an empty course list retaining teachers");
        call(other, "POST", path(otherScope, "/confirm"), confirmBody((String) old.get("batch_id"), 0));
        Map<String, Object> legacy = call(other, "GET", path(otherScope, ""), null);
        Map<String, Object> maintenance = call(other, "GET", path(otherScope, "/course-management"), null);
        check("NOT_CONFIGURED".equals(legacy.get("status")) && number(maintenance.get("version")) == 1 && rows(maintenance, "courses").isEmpty()
                        && object(maintenance.get("retained_counts")).equals(Json.parseMap("{\"teachers\":2,\"certifications\":0,\"bindings\":2}")),
                "published empty courses retain their real version and teacher/binding counts");
        Map<String, Object> added = call(other, "POST", path(otherScope, "/courses-preview"), body(1, "synthetic-empty-courses-v2", courses()));
        check(Boolean.TRUE.equals(added.get("ready")), "course can be added to a published empty course list");
        unchangedRetained(object(added.get("changes")));
        Map<String, Object> pending = Db.one("SELECT payload,bindings FROM m04_catalog_batches WHERE batch_id=?", added.get("batch_id"));
        Map<String, Object> pendingCatalog = Json.parseMap((String) pending.get("payload"));
        check(rows(pendingCatalog, "teachers").equals(rows(catalog, "teachers")) && rows(pendingCatalog, "certifications").isEmpty()
                        && Json.write(bindings).equals(pending.get("bindings")), "empty-course status never erases existing teachers or bindings and invents no certification");
        call(other, "POST", path(otherScope, "/confirm"), confirmBody((String) added.get("batch_id"), 1));
        Map<String, Object> saved = call(other, "GET", path(otherScope, "/course-management"), null);
        check(number(saved.get("version")) == 2 && rows(saved, "courses").size() == 2
                        && object(saved.get("retained_counts")).equals(Json.parseMap("{\"teachers\":2,\"certifications\":0,\"bindings\":2}")),
                "empty-course scope publishes additions without changing retained counts");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass one new empty temporary directory");
        setup(Path.of(args[0]).toAbsolutePath());
        try {
            authorizationAndZeroVersion(); malformedInputs(); firstPublicationAndConfirmation(); legacySeedAndRetention();
            teacherFactConflicts(); corruptSnapshotFailsClosed();
            deactivateAndReactivate(); publishedEmptyCoursesRetainTeachers();
            System.out.println("M04CourseMaintenance: " + checks + " checks passed (real Auth/M01, isolated synthetic H2, no server or production data)");
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
