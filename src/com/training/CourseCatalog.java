package com.training;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Pure coded catalog preview and explicit course qualification. No database, network or ranking. */
public final class CourseCatalog {
    public static final String SCHEMA_VERSION = "m04_catalog_v1";
    private static final int MAX_ROWS = 5000;
    private static final Set<String> CATALOG_FIELDS = Set.of("schema_version", "catalog_version", "courses", "teachers", "certifications");
    private static final Set<String> COURSE_FIELDS = Set.of("course_code", "course_name", "active");
    private static final Set<String> TEACHER_FIELDS = Set.of("teacher_code", "teacher_level", "city");
    private static final Set<String> CERT_FIELDS = Set.of("teacher_code", "course_code", "status", "source_ref", "valid_from", "valid_to");
    private static final Set<String> REQUEST_FIELDS = Set.of("course_code", "as_of", "accepted_levels", "allowed_cities");
    private static final Set<String> STATUSES = Set.of("certified", "not_certified", "unknown", "revoked");

    private CourseCatalog() {}

    /** Validates a whole snapshot atomically. Invalid batches never return accepted data. */
    public static Map<String, Object> preview(Map<String, Object> payload) {
        List<Map<String, Object>> issues = new ArrayList<>();
        Map<String, Object> p = payload == null ? Map.of() : payload;
        fields(p, CATALOG_FIELDS, "catalog", 0, issues);
        if (!SCHEMA_VERSION.equals(p.get("schema_version")))
            error(issues, "catalog", 0, "schema_version", "SCHEMA_VERSION", "目录版本格式不受支持");
        String version = string(p, "catalog_version", false, 120, "catalog", 0, issues);
        List<?> courses = rows(p, "courses", issues);
        List<?> teachers = rows(p, "teachers", issues);
        List<?> certs = rows(p, "certifications", issues);
        Set<String> courseCodes = new HashSet<>(), teacherCodes = new HashSet<>(), pairs = new HashSet<>();

        for (int i = 0; i < courses.size(); i++) {
            Map<String, Object> row = row(courses.get(i), "courses", i + 1, issues);
            if (row == null) continue;
            fields(row, COURSE_FIELDS, "courses", i + 1, issues);
            String code = code(row, "course_code", "courses", i + 1, issues);
            unique(courseCodes, code, "courses", i + 1, "course_code", issues);
            string(row, "course_name", false, 200, "courses", i + 1, issues);
            if (!(row.get("active") instanceof Boolean))
                error(issues, "courses", i + 1, "active", "INVALID_TYPE", "启用状态必须为布尔值");
        }
        for (int i = 0; i < teachers.size(); i++) {
            Map<String, Object> row = row(teachers.get(i), "teachers", i + 1, issues);
            if (row == null) continue;
            fields(row, TEACHER_FIELDS, "teachers", i + 1, issues);
            String code = code(row, "teacher_code", "teachers", i + 1, issues);
            unique(teacherCodes, code, "teachers", i + 1, "teacher_code", issues);
            for (String key : List.of("teacher_level", "city")) {
                String value = string(row, key, true, 120, "teachers", i + 1, issues);
                if ("".equals(value)) warning(issues, "teachers", i + 1, key, "UNKNOWN_METADATA", "该信息尚未提供；有对应筛选限制时不能视为满足");
            }
        }
        for (int i = 0; i < certs.size(); i++) {
            Map<String, Object> row = row(certs.get(i), "certifications", i + 1, issues);
            if (row == null) continue;
            int n = i + 1;
            fields(row, CERT_FIELDS, "certifications", n, issues);
            String tc = code(row, "teacher_code", "certifications", n, issues);
            String cc = code(row, "course_code", "certifications", n, issues);
            if (tc != null && cc != null) unique(pairs, tc + "\u0000" + cc, "certifications", n, "teacher_code,course_code", issues);
            if (tc != null && !teacherCodes.contains(tc)) error(issues, "certifications", n, "teacher_code", "UNKNOWN_REFERENCE", "讲师编码未出现在本批目录");
            if (cc != null && !courseCodes.contains(cc)) error(issues, "certifications", n, "course_code", "UNKNOWN_REFERENCE", "课程编码未出现在本批目录");
            String status = string(row, "status", false, 40, "certifications", n, issues);
            if (status != null && !STATUSES.contains(status)) error(issues, "certifications", n, "status", "INVALID_STATUS", "认证状态不受支持，不能推断为已认证");
            String source = string(row, "source_ref", true, 240, "certifications", n, issues);
            if ("certified".equals(status) && "".equals(source)) error(issues, "certifications", n, "source_ref", "SOURCE_REQUIRED", "已认证记录必须提供认定来源编码");
            LocalDate from = date(row, "valid_from", true, "certifications", n, issues);
            LocalDate to = date(row, "valid_to", true, "certifications", n, issues);
            if (from != null && to != null && from.isAfter(to)) error(issues, "certifications", n, "valid_to", "DATE_ORDER", "有效期结束日早于开始日");
            if ("unknown".equals(status)) warning(issues, "certifications", n, "status", "UNKNOWN_CERTIFICATION", "认证尚未认定，资格筛选不会列为合格");
            if ("certified".equals(status) && ("".equals(row.get("valid_from")) || "".equals(row.get("valid_to"))))
                warning(issues, "certifications", n, "valid_from,valid_to", "VALIDITY_UNSPECIFIED", "有效期边界未完整提供；仅核验已提供的边界，不代表永久有效");
        }
        boolean ready = !hasErrors(issues);
        return map("schema_version", "m04_preview_v1", "catalog_version", version == null ? "" : version,
                "ready", ready, "issues", issues,
                "counts", map("courses", count(p.get("courses")), "teachers", count(p.get("teachers")), "certifications", count(p.get("certifications"))),
                "data", ready ? copy(p) : null);
    }

    /** All teachers are evaluated for one exact course. Input order is retained; never a dispatch ranking. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> qualify(Map<String, Object> payload, Map<String, Object> request) {
        Map<String, Object> pre = preview(payload);
        List<Map<String, Object>> issues = new ArrayList<>((List<Map<String, Object>>) pre.get("issues"));
        Map<String, Object> q = request == null ? Map.of() : request;
        fields(q, REQUEST_FIELDS, "request", 0, issues);
        String courseCode = code(q, "course_code", "request", 0, issues);
        LocalDate asOf = date(q, "as_of", false, "request", 0, issues);
        Set<String> levels = allowlist(q, "accepted_levels", issues);
        Set<String> cities = allowlist(q, "allowed_cities", issues);
        Map<String, Object> result = map("schema_version", "m04_qualification_v1", "ready", false,
                "issues", issues, "catalog_version", pre.get("catalog_version"), "course_code", courseCode == null ? "" : courseCode,
                "as_of", asOf == null ? "" : asOf.toString(), "eligible", new ArrayList<>(), "gaps", new ArrayList<>(), "ranking_status", "not_configured");
        if (hasErrors(issues)) return result;
        Map<String, Object> data = (Map<String, Object>) pre.get("data");
        List<Map<String, Object>> courses = (List<Map<String, Object>>) data.get("courses");
        Map<String, Object> selected = null;
        for (Map<String, Object> row : courses) if (courseCode.equals(row.get("course_code"))) selected = row;
        if (selected == null) error(issues, "request", 0, "course_code", "UNKNOWN_REFERENCE", "本批目录没有该课程编码");
        else if (!Boolean.TRUE.equals(selected.get("active"))) error(issues, "request", 0, "course_code", "COURSE_INACTIVE", "该课程已停用，不能筛选资格");
        if (hasErrors(issues)) return result;
        Map<String, Map<String, Object>> certificationByTeacher = new HashMap<>();
        for (Map<String, Object> cert : (List<Map<String, Object>>) data.get("certifications"))
            if (courseCode.equals(cert.get("course_code"))) certificationByTeacher.put((String) cert.get("teacher_code"), cert);
        List<Map<String, Object>> eligible = (List<Map<String, Object>>) result.get("eligible");
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) result.get("gaps");
        for (Map<String, Object> teacher : (List<Map<String, Object>>) data.get("teachers")) {
            String tc = (String) teacher.get("teacher_code");
            Map<String, Object> cert = certificationByTeacher.get(tc);
            List<Map<String, Object>> reasons = new ArrayList<>();
            if (cert == null) reason(reasons, "CERTIFICATION_MISSING", "没有该具体课程的认证记录");
            else {
                switch ((String) cert.get("status")) {
                    case "unknown": reason(reasons, "CERTIFICATION_UNKNOWN", "该课程认证状态未知"); break;
                    case "not_certified": reason(reasons, "NOT_CERTIFIED", "该课程尚未认证"); break;
                    case "revoked": reason(reasons, "CERTIFICATION_REVOKED", "该课程认证已撤销"); break;
                    case "certified":
                        String from = (String) cert.get("valid_from"), to = (String) cert.get("valid_to");
                        if (!from.isEmpty() && asOf.isBefore(LocalDate.parse(from))) reason(reasons, "NOT_YET_VALID", "该课程认证尚未生效");
                        if (!to.isEmpty() && asOf.isAfter(LocalDate.parse(to))) reason(reasons, "EXPIRED", "该课程认证已过期");
                        break;
                    default: throw new IllegalStateException("preview must validate certification state");
                }
            }
            match(teacher, "teacher_level", levels, "LEVEL_UNKNOWN", "LEVEL_NOT_ALLOWED", "讲师等级", reasons);
            match(teacher, "city", cities, "CITY_UNKNOWN", "CITY_NOT_ALLOWED", "讲师城市", reasons);
            boolean ok = reasons.isEmpty();
            Map<String, Object> entry = map("teacher_code", tc, "teacher_level", teacher.get("teacher_level"), "city", teacher.get("city"),
                    "eligible", ok, "reasons", reasons, "evidence", cert);
            (ok ? eligible : gaps).add(entry);
        }
        result.put("ready", true);
        return result;
    }

    private static void match(Map<String, Object> teacher, String key, Set<String> allowed, String unknown, String mismatch, String label, List<Map<String, Object>> reasons) {
        if (allowed.isEmpty()) return;
        String value = (String) teacher.get(key);
        if (value.isEmpty()) reason(reasons, unknown, label + "未知，不能满足指定限制");
        else if (!allowed.contains(value)) reason(reasons, mismatch, label + "不在本次明确选择的范围");
    }

    private static Set<String> allowlist(Map<String, Object> q, String key, List<Map<String, Object>> issues) {
        Set<String> values = new LinkedHashSet<>();
        Object raw = q.get(key);
        if (!(raw instanceof List<?> list)) {
            error(issues, "request", 0, key, "INVALID_TYPE", "筛选范围必须为字符串数组");
            return values;
        }
        if (list.size() > MAX_ROWS) { error(issues, "request", 0, key, "LIMIT", "筛选项过多"); return values; }
        for (Object v : list) {
            String value = string(Collections.singletonMap(key, v), key, false, 120, "request", 0, issues);
            if (value != null && !values.add(value)) error(issues, "request", 0, key, "DUPLICATE", "筛选项重复");
        }
        return values;
    }

    private static int count(Object v) { return v instanceof List<?> list ? list.size() : 0; }

    private static List<?> rows(Map<String, Object> p, String key, List<Map<String, Object>> issues) {
        Object raw = p.get(key);
        if (!(raw instanceof List<?> list)) { error(issues, "catalog", 0, key, "INVALID_TYPE", "必须为行数组"); return List.of(); }
        if (list.size() > MAX_ROWS) { error(issues, "catalog", 0, key, "LIMIT", "每张表最多5000行，请拆分核对后再导入"); return List.of(); }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Object raw, String table, int n, List<Map<String, Object>> issues) {
        if (!(raw instanceof Map<?, ?>)) { error(issues, table, n, "", "INVALID_TYPE", "每行必须为对象"); return null; }
        return (Map<String, Object>) raw;
    }

    private static void fields(Map<String, Object> row, Set<String> allowed, String table, int n, List<Map<String, Object>> issues) {
        for (Object key : row.keySet()) if (!(key instanceof String) || !allowed.contains(key))
            error(issues, table, n, String.valueOf(key), "UNKNOWN_FIELD", "存在未支持字段；编码导入不接收姓名、电话或履历字段");
    }

    private static String string(Map<String, Object> row, String field, boolean allowEmpty, int max, String table, int n, List<Map<String, Object>> issues) {
        Object raw = row.get(field);
        if (raw == null) { error(issues, table, n, field, "REQUIRED", "缺少必要字段"); return null; }
        if (!(raw instanceof String value)) { error(issues, table, n, field, "INVALID_TYPE", "必须为字符串，编码不接受数值转换"); return null; }
        if (!allowEmpty && value.isEmpty()) { error(issues, table, n, field, "REQUIRED", "不能为空"); return null; }
        boolean edgeSpace = !value.isEmpty() && (space(value.codePointAt(0)) || space(value.codePointBefore(value.length())));
        if (value.length() > max || edgeSpace || value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
            error(issues, table, n, field, "INVALID_TYPE", "文本超长、包含控制字符或首尾空白，请先核对原始数据"); return null;
        }
        return value;
    }

    private static boolean space(int codePoint) { return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint); }

    private static String code(Map<String, Object> row, String field, String table, int n, List<Map<String, Object>> issues) {
        String value = string(row, field, false, 64, table, n, issues);
        if (value != null && !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            error(issues, table, n, field, "INVALID_CODE", "编码须以字母或数字开头，仅含字母、数字、点、下划线或连字符"); return null;
        }
        return value;
    }

    private static LocalDate date(Map<String, Object> row, String field, boolean allowEmpty, String table, int n, List<Map<String, Object>> issues) {
        String value = string(row, field, allowEmpty, 10, table, n, issues);
        if (value == null || value.isEmpty()) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000")) throw new DateTimeParseException("format", value, 0);
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            error(issues, table, n, field, "INVALID_DATE", "必须为有效的 YYYY-MM-DD 日期，年份为0001至9999"); return null;
        }
    }

    private static void unique(Set<String> set, String value, String table, int n, String field, List<Map<String, Object>> issues) {
        if (value != null && !set.add(value)) error(issues, table, n, field, "DUPLICATE", "编码或讲师课程组合重复；整批待核对，不覆盖已有行");
    }
    private static boolean hasErrors(List<Map<String, Object>> issues) { return issues.stream().anyMatch(i -> "error".equals(i.get("severity"))); }
    private static void error(List<Map<String, Object>> issues, String table, int row, String field, String code, String message) { issue(issues, "error", table, row, field, code, message); }
    private static void warning(List<Map<String, Object>> issues, String table, int row, String field, String code, String message) { issue(issues, "warning", table, row, field, code, message); }
    private static void issue(List<Map<String, Object>> issues, String severity, String table, int row, String field, String code, String message) { issues.add(map("severity", severity, "table", table, "row", row, "field", field, "code", code, "message", message)); }
    private static void reason(List<Map<String, Object>> reasons, String code, String message) { reasons.add(map("code", code, "message", message)); }
    private static Object copy(Object value) { return Json.parse(Json.write(value)); }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
