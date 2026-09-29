package com.training;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent, synthetic-only tests. --evaluate is a JSON-lines bridge for JS conformance. */
public final class M04CourseCatalogTest {
    static int passed;
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); passed++; }
    static Map<String, Object> fixture() {
        return Json.parseMap("""
            {"schema_version":"m04_catalog_v1","catalog_version":"SYNTHETIC-M04-20260921",
             "courses":[{"course_code":"DEMO-C01","course_name":"家庭预算（合成）","active":true},
                        {"course_code":"DEMO-C02","course_name":"风险识别（合成）","active":true}],
             "teachers":[{"teacher_code":"DEMO-T01","teacher_level":"L1","city":"演示甲城"},
                         {"teacher_code":"DEMO-T02","teacher_level":"L2","city":"演示乙城"},
                         {"teacher_code":"DEMO-T03","teacher_level":"","city":""}],
             "certifications":[{"teacher_code":"DEMO-T01","course_code":"DEMO-C01","status":"certified","source_ref":"DEMO-SRC-01","valid_from":"2026-01-01","valid_to":"2026-12-31"},
                               {"teacher_code":"DEMO-T02","course_code":"DEMO-C01","status":"unknown","source_ref":"","valid_from":"","valid_to":""},
                               {"teacher_code":"DEMO-T03","course_code":"DEMO-C02","status":"certified","source_ref":"DEMO-SRC-02","valid_from":"2026-01-01","valid_to":"2026-08-31"}]}
            """);
    }
    static Map<String, Object> request() { return Json.parseMap("""
        {"course_code":"DEMO-C01","as_of":"2026-09-21","accepted_levels":[],"allowed_cities":[]}
        """); }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> rows(Map<String, Object> p, String key) { return (List<Map<String, Object>>) p.get(key); }
    static boolean ready(Map<String, Object> p) { return Boolean.TRUE.equals(p.get("ready")); }
    static boolean issue(Map<String, Object> p, String code) { return rows(p, "issues").stream().anyMatch(i -> code.equals(i.get("code"))); }
    static boolean reason(Map<String, Object> p, String teacher, String code) {
        return rows(p, "gaps").stream().filter(t -> teacher.equals(t.get("teacher_code"))).anyMatch(t -> rows(t, "reasons").stream().anyMatch(r -> code.equals(r.get("code"))));
    }
    static void rejected(Map<String, Object> p, String code) {
        Map<String, Object> preview = CourseCatalog.preview(p);
        check(!ready(preview) && preview.get("data") == null && issue(preview, code), "atomic rejection: " + code);
        Map<String, Object> result = CourseCatalog.qualify(p, request());
        check(!ready(result) && rows(result, "eligible").isEmpty() && rows(result, "gaps").isEmpty(), "bad batch never produces qualification: " + code);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--evaluate".equals(args[0])) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                Map<String, Object> input = Json.parseMap(line);
                Map<String, Object> catalog = (Map<String, Object>) input.get("catalog");
                Map<String, Object> query = (Map<String, Object>) input.get("request");
                System.out.println(Json.write(Map.of("preview", CourseCatalog.preview(catalog), "qualification", CourseCatalog.qualify(catalog, query))));
            }
            return;
        }
        Map<String, Object> p = fixture(), q = request();
        Map<String, Object> preview = CourseCatalog.preview(p), r = CourseCatalog.qualify(p, q);
        check(ready(preview), "synthetic snapshot accepted");
        check(issue(preview, "UNKNOWN_METADATA") && issue(preview, "UNKNOWN_CERTIFICATION"), "unknowns visible, not fabricated");
        check(ready(r) && rows(r, "eligible").size() == 1 && rows(r, "gaps").size() == 2, "one exact course qualification");
        check(reason(r, "DEMO-T02", "CERTIFICATION_UNKNOWN"), "unknown certification never eligible");
        check(reason(r, "DEMO-T03", "CERTIFICATION_MISSING"), "another course never grants this course");
        check("not_configured".equals(r.get("ranking_status")), "no invented rank");
        rows(p, "courses").get(0).put("course_name", "changed after preview");
        check(!"changed after preview".equals(rows((Map<String, Object>) preview.get("data"), "courses").get(0).get("course_name")), "preview defensively snapshots data");
        rows(p, "certifications").get(0).put("status", "revoked");
        check("certified".equals(((Map<String, Object>) rows(r, "eligible").get(0).get("evidence")).get("status")), "qualification evidence is detached");

        for (String status : List.of("unknown", "not_certified", "revoked")) {
            p = fixture(); rows(p, "certifications").get(0).put("status", status);
            r = CourseCatalog.qualify(p, request());
            check(ready(r) && rows(r, "eligible").isEmpty(), status + " never qualifies");
        }
        p = fixture(); rows(p, "certifications").get(0).put("source_ref", ""); rejected(p, "SOURCE_REQUIRED");
        p = fixture(); rows(p, "certifications").get(0).put("status", "approved"); rejected(p, "INVALID_STATUS");
        for (String key : List.of("courses", "teachers", "certifications")) {
            p = fixture(); rows(p, key).add(new LinkedHashMap<>(rows(p, key).get(0))); rejected(p, "DUPLICATE");
        }
        p = fixture(); rows(p, "certifications").get(0).put("teacher_code", "MISSING"); rejected(p, "UNKNOWN_REFERENCE");
        p = fixture(); rows(p, "certifications").get(0).put("course_code", "MISSING"); rejected(p, "UNKNOWN_REFERENCE");
        p = fixture(); rows(p, "teachers").get(0).put("name", "不得接收实名字段"); rejected(p, "UNKNOWN_FIELD");
        p = fixture(); p.put("phone", "unexpected"); rejected(p, "UNKNOWN_FIELD");
        p = fixture(); rows(p, "teachers").get(0).put("teacher_code", 1); rejected(p, "INVALID_TYPE");
        p = fixture(); rows(p, "teachers").get(0).put("teacher_code", "001"); rows(p, "certifications").get(0).put("teacher_code", "001");
        check("001".equals(rows(CourseCatalog.qualify(p, request()), "eligible").get(0).get("teacher_code")), "leading zeros survive, numeric identity not coerced");
        p = fixture(); rows(p, "certifications").get(0).put("teacher_code", "demo-t01"); rejected(p, "UNKNOWN_REFERENCE");
        p = fixture(); rows(p, "courses").get(0).put("active", "true"); rejected(p, "INVALID_TYPE");
        p = fixture(); rows(p, "teachers").get(0).put("teacher_code", "讲师一"); rejected(p, "INVALID_CODE");
        p = fixture(); rows(p, "teachers").get(0).put("teacher_level", "L1\n"); rejected(p, "INVALID_TYPE");
        p = fixture(); rows(p, "teachers").get(0).put("city", " 演示甲城"); rejected(p, "INVALID_TYPE");
        p = fixture(); rows(p, "teachers").get(0).remove("city"); rejected(p, "REQUIRED");
        for (String invisible : List.of("\u00a0", "\u200b", "\ufeff", "\u0085", "SRC\u200b01")) {
            p = fixture(); rows(p, "certifications").get(0).put("source_ref", invisible); rejected(p, "INVALID_TYPE");
        }
        p = fixture(); p.put("catalog_version", "\u3000"); rejected(p, "INVALID_TYPE");

        for (String day : List.of("2026-02-29", "2026-04-31", "2026-13-01", "0000-01-01", "2026-1-01")) {
            p = fixture(); rows(p, "certifications").get(0).put("valid_to", day); rejected(p, "INVALID_DATE");
        }
        p = fixture(); rows(p, "certifications").get(0).put("valid_to", "2025-12-31"); rejected(p, "DATE_ORDER");
        q = request(); q.put("as_of", "2026-01-01"); check(rows(CourseCatalog.qualify(fixture(), q), "eligible").size() == 1, "start inclusive");
        q.put("as_of", "2026-12-31"); check(rows(CourseCatalog.qualify(fixture(), q), "eligible").size() == 1, "end inclusive");
        q.put("as_of", "2025-12-31"); check(reason(CourseCatalog.qualify(fixture(), q), "DEMO-T01", "NOT_YET_VALID"), "future certification blocked");
        q.put("as_of", "2027-01-01"); check(reason(CourseCatalog.qualify(fixture(), q), "DEMO-T01", "EXPIRED"), "expired certification blocked");
        p = fixture(); rows(p, "certifications").get(0).put("valid_to", "");
        check(issue(CourseCatalog.preview(p), "VALIDITY_UNSPECIFIED") && rows(CourseCatalog.qualify(p, request()), "eligible").size() == 1, "unknown date boundary explicit warning, no fabricated validity rule");

        q = request(); q.put("accepted_levels", List.of("L2")); r = CourseCatalog.qualify(fixture(), q);
        check(reason(r, "DEMO-T01", "LEVEL_NOT_ALLOWED") && reason(r, "DEMO-T03", "LEVEL_UNKNOWN"), "exact level filter including unknown");
        q = request(); q.put("allowed_cities", List.of("演示乙城")); r = CourseCatalog.qualify(fixture(), q);
        check(reason(r, "DEMO-T01", "CITY_NOT_ALLOWED") && reason(r, "DEMO-T03", "CITY_UNKNOWN"), "exact city filter including unknown");
        q = request(); q.put("accepted_levels", List.of("L1", "L2")); check(rows(CourseCatalog.qualify(fixture(), q), "eligible").size() == 1, "explicit multi-level allowlist");
        q = request(); q.put("allowed_cities", List.of("演示甲城", "演示甲城")); check(issue(CourseCatalog.qualify(fixture(), q), "DUPLICATE"), "duplicate request restrictions rejected");
        q = request(); q.put("accepted_levels", List.of(3)); check(!ready(CourseCatalog.qualify(fixture(), q)), "numeric restriction blocked");
        q = request(); q.put("as_of", "2026-02-29"); check(!ready(CourseCatalog.qualify(fixture(), q)), "invalid evaluation date blocked");
        q = request(); q.remove("as_of"); check(!ready(CourseCatalog.qualify(fixture(), q)), "no implicit evaluation date");
        q = request(); q.put("rank", "city"); check(!ready(CourseCatalog.qualify(fixture(), q)), "unsupported request blocked");
        q = request(); q.put("course_code", "MISSING"); check(!ready(CourseCatalog.qualify(fixture(), q)), "unknown course blocked");
        p = fixture(); rows(p, "courses").get(0).put("active", false); check(issue(CourseCatalog.qualify(p, request()), "COURSE_INACTIVE"), "inactive course blocked");
        p = fixture(); p.put("schema_version", "v2"); rejected(p, "SCHEMA_VERSION");
        p = fixture(); p.put("courses", "bad shape"); rejected(p, "INVALID_TYPE");
        p = fixture(); p.put("teachers", Arrays.asList((Object) null)); rejected(p, "INVALID_TYPE");
        p = fixture(); p.put("certifications", Collections.nCopies(5001, Map.of())); rejected(p, "LIMIT");
        check(!ready(CourseCatalog.preview(null)) && !ready(CourseCatalog.qualify(null, null)), "null safely fails closed");
        check(!ready(CourseCatalog.qualify(fixture(), null)), "null request safely fails closed");
        p = fixture();
        for (int i = 4; i <= 7; i++) {
            rows(p, "teachers").add(new LinkedHashMap<>(Map.of("teacher_code", "DEMO-T0" + i, "teacher_level", "L1", "city", "演示甲城")));
            Map<String, Object> cert = new LinkedHashMap<>(rows(p, "certifications").get(0)); cert.put("teacher_code", "DEMO-T0" + i); rows(p, "certifications").add(cert);
        }
        r = CourseCatalog.qualify(p, request());
        check(rows(r, "eligible").size() == 5, "no candidate truncation before later dispatch ranking");
        check("DEMO-T04".equals(rows(r, "eligible").get(1).get("teacher_code")), "input order retained without ranking claim");
        System.out.println("M04 CourseCatalog: " + passed + " checks passed; synthetic only, no DB or network");
    }
}
