package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.lang.reflect.Modifier;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.training.OrganizationAccess.*;

/** Real Auth/M01/M04, an empty synthetic H2, and no running HTTP server or external services. */
public final class M04RecommendationQualificationTest {
    private static int checks;
    private static final String PASSWORD = "SYNTHETIC-M04-BRIDGE-20260922";
    private static final List<Long> ELIGIBLE = List.of(107L, 105L, 104L, 106L);
    private static Auth.Session admin, manager, colleague, other;
    private static String managerToken;
    private static long scope, otherScope, version;
    private static String identityVersion;
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    private static void rejects(int expected, Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected HTTP " + expected + ": " + label); }
        catch (Api.ApiException e) { check(e.code == expected, label + " (actual " + e.code + ")"); }
    }
    private static void immutable(Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Mutable server state: " + label); }
        catch (UnsupportedOperationException expected) { check(true, label); }
    }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String)pairs[i], pairs[i + 1]);
        return value;
    }
    private static long number(Object value) { return ((Number)value).longValue(); }
    @SuppressWarnings("unchecked") private static Map<String,Object> cloneMap(Map<String,Object> value) {
        return (Map<String,Object>)Json.parse(Json.write(value));
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> value, String key) {
        return (List<Map<String,Object>>)value.get(key);
    }
    private static List<Long> ids(List<Map<String,Object>> values, String key) {
        return values.stream().map(v -> number(v.get(key))).toList();
    }
    private static Auth.Session login(String name) throws Exception {
        String token = Auth.login("synthetic-" + name, PASSWORD);
        Auth.Session value = Auth.get(token);
        check(value != null, "real login: " + name);
        if (name.equals("manager")) managerToken = token;
        return value;
    }
    private static void loginBusinessAccounts() throws Exception {
        manager = login("manager"); colleague = login("colleague"); other = login("other");
    }
    private static Configuration identity(String v) {
        Set<String> roles = Set.of("MAINTAIN");
        RelationRule optional = new RelationRule(false, roles, false, false);
        return new Configuration(v, new CodeRules("[0-9]{3}", "[0-9]{4}", "[A-Z]+"), roles,
                List.of(new Organization("001", null, true), new Organization("002", null, true)),
                List.of(new Person("0001", "001", Set.of(), null, null, roles, true),
                        new Person("0002", "001", Set.of(), null, null, roles, true),
                        new Person("0003", "002", Set.of(), null, null, roles, true)),
                List.of(new RoleRelations("MAINTAIN", optional, optional)),
                List.of(new AccountBinding(2, "0001", true), new AccountBinding(3, "0002", true), new AccountBinding(4, "0003", true)),
                List.of(new Grant("READ", "MAINTAIN", "catalog.read", Action.VIEW, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("WRITE", "MAINTAIN", "catalog.manage", Action.HANDLE, Effect.ALLOW, Scope.OWN_ORG, Set.of())));
    }
    private static void publishIdentity(Configuration config) throws Exception {
        OrganizationAccessStore.publish(admin, identityVersion, config); identityVersion = config.version();
    }
    private static Map<String,Object> catalog(String v) {
        List<Map<String,Object>> teachers = new ArrayList<>(), certs = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            String code = String.format("%04d", i);
            teachers.add(map("teacher_code", code, "teacher_level", "L1", "city", "合成甲城"));
            if (i == 1) continue;
            certs.add(map("teacher_code", code, "course_code", "001", "status", i == 2 ? "unknown" : i == 3 ? "revoked" : "certified",
                    "source_ref", i <= 3 ? "" : "SYNTHETIC-SOURCE-" + code,
                    "valid_from", "2026-01-01", "valid_to", "2026-12-31"));
        }
        return map("schema_version", "m04_catalog_v1", "catalog_version", v,
                "courses", List.of(map("course_code", "001", "course_name", "合成课程甲", "active", true),
                        map("course_code", "002", "course_name", "没有认证的合成课程", "active", true)),
                "teachers", teachers, "certifications", certs);
    }
    private static List<Map<String,Object>> bindings() {
        List<Map<String,Object>> value = new ArrayList<>();
        for (int i = 1; i <= 7; i++) value.add(map("teacher_code", String.format("%04d", i), "teacher_id", 100L + i));
        return value;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> call(String suffix, Map<String,Object> body) throws Exception {
        Exchange exchange = new Exchange("POST", "/api/course-catalog/scopes/" + scope + suffix, managerToken, body);
        check(CourseCatalogIntegration.handle(exchange, manager), "real M04 route " + suffix);
        Object response = exchange.getAttribute("com.training.Api.response");
        check(response != null, "real Api response recorded");
        var field = response.getClass().getDeclaredField("body"); field.setAccessible(true);
        Map<String,Object> envelope = Json.parseMap(new String((byte[])field.get(response), StandardCharsets.UTF_8));
        return (Map<String,Object>)envelope.get("data");
    }
    private static void publishCatalog() throws Exception {
        Map<String,Object> pre = call("/preview", map("expected_version", version, "catalog", catalog("synthetic-catalog-" + (version + 1)),
                "bindings", bindings(), "change_comment", "独立合成桥接集成测试"));
        check(Boolean.TRUE.equals(pre.get("ready")) && Boolean.TRUE.equals(pre.get("confirmation_required")), "catalog preview requires explicit confirmation");
        Map<String,Object> confirmed = call("/confirm", map("batch_id", pre.get("batch_id"), "expected_version", version, "confirm", true));
        check("CONFIRMED".equals(confirmed.get("status")), "explicit catalog confirmation persisted");
        version++;
    }
    private static Map<String,Object> selection(long selectedScope, long selectedVersion, String course) {
        return map("scope_id", selectedScope, "expected_version", selectedVersion, "course_code", course,
                "as_of", "2026-09-22", "accepted_levels", new ArrayList<>(List.of("L1")), "allowed_cities", new ArrayList<>(List.of("合成甲城")));
    }
    private static Map<String,Object> body() {
        return map("content", "合成旧推荐需求文本", "teacher_req", "合成师资要求", "standard_course", selection(scope, version, "001"));
    }
    private static RecommendationQualification.Capture capture() throws Exception {
        return RecommendationQualification.capture(manager, body());
    }
    private static List<Map<String,Object>> fullPool() throws Exception {
        List<Map<String,Object>> dbRows = Db.query("SELECT id,teacher_level,base_city,status FROM teachers WHERE status='在库' ORDER BY id");
        Map<Long,Map<String,Object>> byId = new LinkedHashMap<>();
        for (Map<String,Object> row : dbRows) byId.put(number(row.get("id")), row);
        List<Map<String,Object>> result = new ArrayList<>();
        for (long id : List.of(101L, 102L, 103L, 107L, 105L, 104L, 106L, 108L)) {
            Map<String,Object> row = byId.remove(id);
            if (row != null) { row.put("score", 1000L - id); row.put("proof", new ArrayList<>(List.of("synthetic profile"))); result.add(row); }
        }
        result.addAll(byId.values());
        return result;
    }
    private static Map<String,Object> scored(long... teacherIds) {
        List<Map<String,Object>> values = new ArrayList<>();
        for (long id : teacherIds) values.add(map("teacher_id", id, "score", 2000L - id, "reason", map("text", "合成分数证据")));
        return map("recommendations", values, "trace", map("mode", "synthetic"), "course_qualification", map("forged", true));
    }
    private static RecommendationQualification.Pool pool(RecommendationQualification.Capture c) throws Exception {
        return RecommendationQualification.filterBeforeScoring(manager, c, fullPool());
    }
    private static void setup(Path data) throws Exception {
        if (!Files.isDirectory(data)) throw new IllegalArgumentException("New empty temporary data directory required");
        try (var files = Files.list(data)) { if (files.findAny().isPresent()) throw new IllegalArgumentException("Directory must be empty"); }
        System.setProperty("data.dir", data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,teacher_level VARCHAR(120),base_city VARCHAR(120),status VARCHAR(32))");
        String hash = Auth.hash(PASSWORD);
        String[] names = {"admin", "manager", "colleague", "other"};
        for (int i = 0; i < names.length; i++) Db.exec("INSERT INTO users VALUES(?,?,?,?,?,?)", i + 1, "synthetic-" + names[i], hash,
                "SYNTHETIC " + names[i], i == 0 ? "admin" : "viewer", 1);
        for (long id = 101; id <= 109; id++) Db.exec("INSERT INTO teachers VALUES(?,?,?,?)", id, "L1", "合成甲城", id == 109 ? "离库" : "在库");
        admin = login("admin"); manager = login("manager");
        Auth.Session beforePublish = manager;
        OrganizationAccessStore.init();
        publishIdentity(identity("synthetic-identity-1"));
        check(Auth.current(beforePublish) == null, "initial real M01 publication revokes mapped prepublication sessions");
        loginBusinessAccounts();
        CourseCatalogIntegration.init();
        scope = number(CourseCatalogIntegration.registerScope(manager, "001").get("scope_id"));
        otherScope = number(CourseCatalogIntegration.registerScope(other, "002").get("scope_id"));
        check(Db.count("m04_catalog_revisions") == 0, "init creates no catalog snapshot");
        publishCatalog();
    }
    private static void constructionAndParsing() throws Exception {
        for (Class<?> type : List.of(RecommendationQualification.Capture.class, RecommendationQualification.Pool.class, RecommendationQualification.Result.class)) {
            check(Modifier.isFinal(type.getModifiers()), type.getSimpleName() + " is final");
            for (var constructor : type.getDeclaredConstructors()) check(Modifier.isPrivate(constructor.getModifiers()), type.getSimpleName() + " cannot be constructed outside bridge");
            check(!Map.class.isAssignableFrom(type), type.getSimpleName() + " is opaque server state, not a client Map");
        }
        var deliver = RecommendationQualification.class.getDeclaredMethod("deliver", Auth.Session.class, RecommendationQualification.Result.class);
        check(Modifier.isStatic(deliver.getModifiers()) && !Modifier.isPublic(deliver.getModifiers()), "delivery requires opaque Result at package boundary");
        rejects(400, () -> RecommendationQualification.capture(manager, null), "null recommendation body rejected");
        rejects(401, () -> RecommendationQualification.capture(null, body()), "null session rejected");
        Auth.Session forged = new Auth.Session(); forged.uid = 2;
        rejects(401, () -> RecommendationQualification.capture(forged, body()), "manually fabricated session rejected");
        for (Object invalid : Arrays.asList(null, map(), "001", List.of(), true))
            rejects(400, () -> RecommendationQualification.capture(manager, map("standard_course", invalid)), "explicit invalid selection never activates legacy fallback");
        for (String missing : List.of("scope_id", "expected_version", "course_code", "as_of", "accepted_levels", "allowed_cities")) {
            Map<String,Object> selected = selection(scope, version, "001"); selected.remove(missing);
            rejects(400, () -> RecommendationQualification.capture(manager, map("standard_course", selected)), "required selection field " + missing);
        }
        for (String key : List.of("scope_id", "expected_version")) {
            for (Object invalid : List.of("1", 1.5, 9007199254740992L, -1L)) {
                Map<String,Object> selected = selection(scope, version, "001"); selected.put(key, invalid);
                rejects(400, () -> RecommendationQualification.capture(manager, map("standard_course", selected)), "strict numeric " + key + " rejects " + invalid);
            }
        }
        Map<String,Object> extra = selection(scope, version, "001"); extra.put("eligible", true);
        rejects(400, () -> RecommendationQualification.capture(manager, map("standard_course", extra)), "unknown nested selection field rejected");
        for (String key : List.of("qualification_context", "eligible", "eligible_teacher_ids", "candidates", "candidate_ids", "teacher_ids", "recommendations", "results", "review_candidates", "review_candidates3", "certified", "catalog", "bindings", "context_id", "_m04_private", "_m04_")) {
            Map<String,Object> injected = body(); injected.put(key, List.of(101L));
            rejects(400, () -> RecommendationQualification.capture(manager, injected), "selected body injection " + key);
            rejects(400, () -> RecommendationQualification.capture(manager, map(key, List.of(101L))), "legacy body injection " + key);
        }
        Map<String,Object> changedBody = body();
        RecommendationQualification.Capture stable = RecommendationQualification.capture(manager, changedBody);
        String before = Json.write(stable.summary()), cache = stable.cacheKey();
        @SuppressWarnings("unchecked") Map<String,Object> selected = (Map<String,Object>)changedBody.get("standard_course");
        selected.put("course_code", "999"); selected.put("accepted_levels", List.of("L999")); changedBody.clear();
        RecommendationQualification.revalidate(manager, stable);
        check(before.equals(Json.write(stable.summary())) && cache.equals(stable.cacheKey()), "caller mutation cannot rewrite captured criteria or cache key");
        check(stable.cacheKey().equals(capture().cacheKey()), "same live session and exact context have stable cache key");
        check(!stable.cacheKey().equals(RecommendationQualification.capture(colleague, body()).cacheKey()), "cache identity separates accounts");
        Auth.Session sameUser = login("manager");
        check(!stable.cacheKey().equals(RecommendationQualification.capture(sameUser, body()).cacheKey()), "cache identity separates same-user sessions");
        for (Auth.Session wrong : List.of(colleague, sameUser)) {
            rejects(403, () -> RecommendationQualification.revalidate(wrong, stable), "capture cannot move to another Session object");
            rejects(403, () -> RecommendationQualification.filterBeforeScoring(wrong, stable, fullPool()), "pool cannot move to another Session object");
        }
        manager = login("manager");
    }
    private static void fullPoolAndCopies() throws Exception {
        RecommendationQualification.Capture c = capture();
        check(c.selected() && "READY".equals(c.status()), "explicit configured course is selected and ready");
        check(c.eligibleTeacherIds().equals(new LinkedHashSet<>(ELIGIBLE)), "only four real certified teacher IDs are eligible");
        immutable(() -> c.eligibleTeacherIds().add(101L), "eligible IDs immutable");
        String savedSummary = Json.write(c.summary());
        Map<String,Object> summary = c.summary();
        try { summary.put("status", "FORGED"); for (Object value : summary.values()) if (value instanceof Map<?,?> m) m.clear(); } catch (UnsupportedOperationException ignored) {}
        check(savedSummary.equals(Json.write(c.summary())), "capture summary is defensively deep copied");
        List<Map<String,Object>> all = fullPool();
        RecommendationQualification.Pool p = RecommendationQualification.filterBeforeScoring(manager, c, all);
        check(p.totalInLibrary() == 8 && p.teachers().size() == 4, "full eight-row library yields four certified candidates beyond initial three");
        check(ids(p.teachers(), "id").equals(ELIGIBLE), "qualification preserves caller order without ranking or top-three truncation");
        check(number(p.teachers().get(0).get("score")) == 893, "qualification preserves original score fields");
        String gaps = Json.write(p.exclusions());
        check(gaps.contains("101") && gaps.contains("102") && gaps.contains("103") && gaps.contains("108"), "coded exclusions identify rejected and unbound database teachers");
        immutable(() -> p.teachers().add(map("id", 101L)), "filtered list immutable");
        immutable(() -> p.teachers().get(0).put("id", 101L), "filtered rows immutable");
        @SuppressWarnings("unchecked") List<Object> proof = (List<Object>)p.teachers().get(0).get("proof");
        immutable(() -> proof.add("forged evidence"), "nested scoring facts immutable");
        @SuppressWarnings("unchecked") List<Object> inputProof = (List<Object>)all.get(3).get("proof");
        inputProof.add("caller changed evidence");
        check(proof.size() == 1, "input nested mutation cannot rewrite captured scoring facts");
        String poolSummary = Json.write(p.summary()), exclusions = Json.write(p.exclusions());
        Map<String,Object> alteredPoolSummary = p.summary(); alteredPoolSummary.clear();
        List<Map<String,Object>> alteredExclusions = p.exclusions();
        try { alteredExclusions.get(0).put("teacher_id", 104L); alteredExclusions.clear(); } catch (UnsupportedOperationException ignored) {}
        check(poolSummary.equals(Json.write(p.summary())) && exclusions.equals(Json.write(p.exclusions())), "pool summary and coded gaps are defensively deep copied");
        all.get(3).put("id", 999L); all.clear();
        check(ids(p.teachers(), "id").equals(ELIGIBLE), "later input list and row mutation cannot change scoring pool");
        rejects(409, () -> RecommendationQualification.filterBeforeScoring(manager, c, fullPool().subList(0, 3)), "initial three rows are not accepted as whole database pool");
        rejects(409, () -> RecommendationQualification.filterBeforeScoring(manager, c, p.teachers()), "prefiltered eligible pool also fails completeness proof");
        List<Map<String,Object>> duplicate = fullPool(); duplicate.set(7, cloneMap(duplicate.get(0)));
        rejects(409, () -> RecommendationQualification.filterBeforeScoring(manager, c, duplicate), "duplicate IDs reject complete-looking pool");
        for (Object fakeId : List.of(999L, 109L)) {
            List<Map<String,Object>> fake = fullPool(); fake.get(7).put("id", fakeId);
            rejects(409, () -> RecommendationQualification.filterBeforeScoring(manager, c, fake), "unknown or out-of-library ID rejected");
        }
        for (Object badId : List.of("108", 108.5, 9007199254740992L)) {
            List<Map<String,Object>> fake = fullPool(); fake.get(7).put("id", badId);
            rejects(400, () -> RecommendationQualification.filterBeforeScoring(manager, c, fake), "malformed pool ID rejected");
        }
        Map<String,Object> ranked = scored(106, 104, 107, 105);
        ranked.put("results", List.of(map("teacher_id", 104L)));
        ranked.put("review_candidates", List.of(map("teacher_id", 105L), map("teacher_id", 107L)));
        ranked.put("review_candidates3", List.of(map("teacher_id", 105L), map("teacher_id", 107L)));
        RecommendationQualification.Result result = RecommendationQualification.complete(manager, p, ranked);
        check(result.cacheKey().equals(c.cacheKey()), "opaque result retains context cache key");
        Map<String,Object> delivered = RecommendationQualification.deliver(manager, result);
        check(ids(rows(delivered, "recommendations"), "teacher_id").equals(List.of(106L, 104L, 107L, 105L)), "scorer ranking and all four output rows survive completion");
        check(delivered.get("course_qualification").equals(cloneMap(p.summary())), "trusted summary replaces forged scored qualification");
        check(number(delivered.get("total_in_library")) == 8, "completion preserves total full-library count");
        String frozen = Json.write(delivered);
        ranked.clear(); rows(delivered, "recommendations").get(0).put("teacher_id", 101L);
        @SuppressWarnings("unchecked") Map<String,Object> deliveredSummary = (Map<String,Object>)delivered.get("course_qualification"); deliveredSummary.clear();
        check(frozen.equals(Json.write(RecommendationQualification.deliver(manager, result))), "result snapshots scores and defensively deep copies every delivery");
        for (String listKey : List.of("recommendations", "results", "review_candidates", "review_candidates3")) {
            rejects(409, () -> RecommendationQualification.complete(manager, p, map(listKey, List.of(map("teacher_id", 101L)))), "uncertified teacher cannot be inserted into " + listKey);
        }
        for (Auth.Session wrong : List.of(colleague, login("manager"))) {
            rejects(403, () -> RecommendationQualification.complete(wrong, p, scored(104)), "complete retains original Session object");
            rejects(403, () -> RecommendationQualification.deliver(wrong, result), "deliver retains original Session object");
        }
        // Restore the existing manager's token pairing for subsequent explicit catalog publication.
        manager = login("manager");
    }
    private static void noFallback() throws Exception {
        RecommendationQualification.Capture legacy = RecommendationQualification.capture(manager, map("content", "合成原有需求", "teacher_req", "现有字段"));
        check(!legacy.selected() && "NOT_SELECTED".equals(legacy.status()) && legacy.eligibleTeacherIds().isEmpty(), "only absent selection activates legacy path without certification claim");
        RecommendationQualification.Pool legacyPool = pool(legacy);
        check(ids(legacyPool.teachers(), "id").equals(ids(fullPool(), "id")), "legacy preserves complete original pool including uncertified teachers");
        Map<String,Object> legacyResult = RecommendationQualification.deliver(manager, RecommendationQualification.complete(manager, legacyPool, scored(101, 102, 103)));
        check(ids(rows(legacyResult, "recommendations"), "teacher_id").equals(List.of(101L, 102L, 103L)), "legacy scoring remains usable");
        for (String course : List.of("999", "002")) {
            RecommendationQualification.Capture c = RecommendationQualification.capture(manager, map("standard_course", selection(scope, version, course)));
            check(c.selected() && (course.equals("999") ? "INVALID_SELECTION" : "NO_ELIGIBLE").equals(c.status()) && c.eligibleTeacherIds().isEmpty(), "unknown course or missing certifications stays selected and empty");
            RecommendationQualification.Pool p = pool(c);
            check(p.teachers().isEmpty() && p.totalInLibrary() == 8, "invalid or uncertified course never falls back to full teacher library");
            rejects(409, () -> RecommendationQualification.complete(manager, p, scored(104)), "empty qualification cannot accept invented fallback result");
            Map<String,Object> empty = scored(); empty.put("eligible_count", 99); empty.put("returned_count", 99); empty.put("review_candidate_count", 99);
            Map<String,Object> emptyDelivery = RecommendationQualification.deliver(manager, RecommendationQualification.complete(manager, p, empty));
            check(rows(emptyDelivery, "recommendations").isEmpty() && rows(emptyDelivery, "results").isEmpty() && rows(emptyDelivery, "review_candidates").isEmpty(), "empty selected result keeps every scored candidate array empty");
            check(number(emptyDelivery.get("eligible_count")) == 0 && number(emptyDelivery.get("returned_count")) == 0
                    && number(emptyDelivery.get("review_candidate_count")) == 0 && emptyDelivery.get("notice") instanceof String, "empty selected result replaces misleading scalar counts with zero and explicit notice");
        }
        RecommendationQualification.Capture unconfigured = RecommendationQualification.capture(other, map("standard_course", selection(otherScope, 0, "001")));
        check(unconfigured.selected() && "NOT_CONFIGURED".equals(unconfigured.status()) && unconfigured.eligibleTeacherIds().isEmpty(), "unconfigured scope has no implicit eligible IDs");
        RecommendationQualification.Pool noCatalog = RecommendationQualification.filterBeforeScoring(other, unconfigured, fullPool());
        check(noCatalog.teachers().isEmpty(), "unconfigured selected catalog blocks scoring pool");
        rejects(409, () -> RecommendationQualification.complete(other, noCatalog, scored(104)), "unconfigured scope cannot complete fallback results");
    }
    private static void changedFactsAndTransactions() throws Exception {
        for (String field : List.of("teacher_level", "base_city")) {
            List<Map<String,Object>> oldPool = fullPool();
            String original = field.equals("teacher_level") ? "L1" : "合成甲城";
            Db.exec("UPDATE teachers SET " + field + "=? WHERE id=104", "SYNTHETIC-BEFORE-CAPTURE");
            try {
                RecommendationQualification.Capture fresh = capture();
                rejects(409, () -> RecommendationQualification.filterBeforeScoring(manager, fresh, oldPool), "query-before-capture stale " + field + " cannot enter scoring pool");
            } finally { Db.exec("UPDATE teachers SET " + field + "=? WHERE id=104", original); }
        }
        for (String field : List.of("teacher_level", "base_city", "status")) {
            RecommendationQualification.Capture c = capture();
            RecommendationQualification.Pool p = pool(c);
            RecommendationQualification.Result result = RecommendationQualification.complete(manager, p, scored(104));
            String old = field.equals("teacher_level") ? "L1" : field.equals("base_city") ? "合成甲城" : "在库";
            Db.exec("UPDATE teachers SET " + field + "=? WHERE id=104", field.equals("status") ? "离库" : "SYNTHETIC-CHANGED");
            try {
                rejects(409, () -> RecommendationQualification.revalidate(manager, c), "current teacher " + field + " invalidates capture");
                rejects(409, () -> RecommendationQualification.complete(manager, p, scored(104)), "current teacher " + field + " invalidates completion");
                rejects(409, () -> RecommendationQualification.deliver(manager, result), "current teacher " + field + " invalidates cached delivery");
                check(!capture().eligibleTeacherIds().contains(104L), "fresh qualification reads changed current " + field);
            } finally { Db.exec("UPDATE teachers SET " + field + "=? WHERE id=104", old); }
        }
        RecommendationQualification.Capture c = capture();
        RecommendationQualification.Pool p = pool(c);
        RecommendationQualification.Result result = RecommendationQualification.complete(manager, p, scored(104));
        Db.get().setAutoCommit(false);
        try {
            for (Work operation : List.<Work>of(() -> capture(), () -> pool(c), () -> RecommendationQualification.revalidate(manager, c),
                    () -> RecommendationQualification.complete(manager, p, scored(104)), () -> RecommendationQualification.deliver(manager, result))) {
                try { operation.run(); throw new AssertionError("Uncommitted connection accepted"); }
                catch (IllegalStateException expected) { check(true, "bridge rejects uncommitted shared connection"); }
            }
        } finally { Db.get().rollback(); Db.get().setAutoCommit(true); }
    }
    private static void versionsAndRevocations() throws Exception {
        RecommendationQualification.Capture c = capture();
        RecommendationQualification.Pool p = pool(c);
        RecommendationQualification.Result result = RecommendationQualification.complete(manager, p, scored(104));
        publishCatalog();
        rejects(409, () -> RecommendationQualification.complete(manager, p, scored(104)), "new catalog version discards in-flight score");
        rejects(409, () -> RecommendationQualification.deliver(manager, result), "new catalog version discards cached result");
        RecommendationQualification.Capture identityCapture = capture();
        RecommendationQualification.Pool identityPool = pool(identityCapture);
        RecommendationQualification.Result identityResult = RecommendationQualification.complete(manager, identityPool, scored(104));
        Auth.Session before = manager;
        publishIdentity(identity("synthetic-identity-2"));
        check(Auth.current(before) == before, "M01 version-only publication preserves real session");
        rejects(409, () -> RecommendationQualification.complete(manager, identityPool, scored(104)), "M01 version change discards in-flight score");
        rejects(409, () -> RecommendationQualification.deliver(manager, identityResult), "M01 version change discards cached result");
        RecommendationQualification.Capture revokedCapture = capture();
        RecommendationQualification.Pool revokedPool = pool(revokedCapture);
        RecommendationQualification.Result revokedResult = RecommendationQualification.complete(manager, revokedPool, scored(104));
        Auth.Session revoked = manager;
        Configuration config = identity("synthetic-identity-3-revoked");
        Configuration noRead = new Configuration(config.version(), config.codeRules(), config.roleCodes(), config.organizations(), config.people(), config.relations(),
                config.accountBindings(), config.grants().stream().filter(g -> !g.ruleId().equals("READ")).toList());
        publishIdentity(noRead);
        check(Auth.current(revoked) == null, "actual M01 grant revocation invalidates previous session");
        rejects(401, () -> RecommendationQualification.complete(revoked, revokedPool, scored(104)), "revocation discards old-session completion");
        rejects(401, () -> RecommendationQualification.deliver(revoked, revokedResult), "revocation discards old-session cached result");
        Auth.Session newSession = login("manager");
        rejects(403, () -> RecommendationQualification.deliver(newSession, revokedResult), "new session cannot inherit pre-revocation opaque result");
        rejects(403, () -> RecommendationQualification.capture(newSession, body()), "fresh session still cannot read revoked catalog");
        publishIdentity(identity("synthetic-identity-4-restored")); loginBusinessAccounts();
        RecommendationQualification.Capture reboundCapture = capture();
        RecommendationQualification.Pool reboundPool = pool(reboundCapture);
        RecommendationQualification.Result reboundResult = RecommendationQualification.complete(manager, reboundPool, scored(104));
        Auth.Session rebound = manager;
        Configuration base = identity("synthetic-identity-5-rebound");
        List<AccountBinding> remapped = base.accountBindings().stream().map(b -> b.accountId() == 2 ? new AccountBinding(2, "0003", true)
                : b.accountId() == 4 ? new AccountBinding(4, "0001", true) : b).toList();
        publishIdentity(new Configuration(base.version(), base.codeRules(), base.roleCodes(), base.organizations(), base.people(), base.relations(), remapped, base.grants()));
        check(Auth.current(rebound) == null, "actual M01 account/person rebinding revokes old session");
        rejects(401, () -> RecommendationQualification.complete(rebound, reboundPool, scored(104)), "rebinding discards old-session completion");
        rejects(401, () -> RecommendationQualification.deliver(rebound, reboundResult), "rebinding discards old-session cached result");
        Auth.Session remappedSession = login("manager");
        rejects(403, () -> RecommendationQualification.deliver(remappedSession, reboundResult), "new remapped session cannot inherit old result");
        rejects(403, () -> RecommendationQualification.capture(remappedSession, body()), "new mapping cannot access old organization scope");
        publishIdentity(identity("synthetic-identity-6-restored")); loginBusinessAccounts();
        RecommendationQualification.Capture logoutCapture = capture();
        RecommendationQualification.Pool logoutPool = pool(logoutCapture);
        RecommendationQualification.Result logoutResult = RecommendationQualification.complete(manager, logoutPool, scored(104));
        Auth.Session loggedOut = manager;
        Auth.logout(managerToken);
        rejects(401, () -> RecommendationQualification.complete(loggedOut, logoutPool, scored(104)), "logout discards completion");
        rejects(401, () -> RecommendationQualification.deliver(loggedOut, logoutResult), "logout discards cached delivery");
        manager = login("manager");
    }
    private static void asynchronousBoundary() throws Exception {
        CountDownLatch scoring = new CountDownLatch(1), finishScoring = new CountDownLatch(1);
        AtomicInteger code = new AtomicInteger();
        Auth.Session actor = manager;
        RecommendationQualification.Capture c = capture();
        try (RecommendationJobs jobs = new RecommendationJobs(System::currentTimeMillis, 10_000, 20_000, 2, 1, false)) {
            Map<String,Object> submitted = jobs.submit(actor.uid, progress -> {
                RecommendationQualification.Pool p = RecommendationQualification.filterBeforeScoring(actor, c, fullPool());
                progress.update("ranking", 0, p.teachers().size()); scoring.countDown();
                if (!finishScoring.await(5, TimeUnit.SECONDS)) throw new AssertionError("test barrier timed out");
                try { return RecommendationQualification.deliver(actor, RecommendationQualification.complete(actor, p, scored(104))); }
                catch (Api.ApiException e) { code.set(e.code); throw e; }
            });
            check(scoring.await(5, TimeUnit.SECONDS), "real bounded RecommendationJobs executor entered scoring after qualification");
            Db.exec("UPDATE teachers SET base_city='SYNTHETIC-ASYNC-CHANGE' WHERE id=104"); finishScoring.countDown();
            Map<String,Object> state = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do { state = jobs.get(actor.uid, String.valueOf(submitted.get("job_id"))); if (Boolean.TRUE.equals(state.get("terminal"))) break; Thread.sleep(10); }
            while (System.nanoTime() < deadline);
            check("failed".equals(state.get("status")) && code.get() == 409 && !state.containsKey("result"), "async changed-facts completion fails closed without retaining a stale job result");
        } finally { finishScoring.countDown(); Db.exec("UPDATE teachers SET base_city='合成甲城' WHERE id=104"); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass a new empty temporary directory");
        // A rejected test directory must never open the default database in cleanup.
        setup(Path.of(args[0]).toAbsolutePath());
        try {
            constructionAndParsing(); fullPoolAndCopies(); noFallback();
            changedFactsAndTransactions(); versionsAndRevocations(); asynchronousBoundary();
            check(Db.count("users") == 4 && Db.count("teachers") == 9, "only explicit synthetic fixture accounts and teachers exist");
            System.out.println("M04RecommendationQualification: " + checks + " checks passed (actual shared sources, real Auth/M01/M04, fresh synthetic H2, no service/network/external credentials)");
        } finally { Db.exec("SHUTDOWN"); }
    }
    private static final class Exchange extends HttpExchange {
        private final String method; private final URI uri;
        private final Headers requestHeaders = new Headers(), responseHeaders = new Headers();
        private final Map<String,Object> attributes = new HashMap<>();
        Exchange(String method, String path, String token, Map<String,Object> body) {
            this.method = method; uri = URI.create(path);
            requestHeaders.set("X-Token", token); requestHeaders.set("Content-Type", "application/json");
            attributes.put("com.training.Api.body", cloneMap(body));
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
