package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** M06 read-only teaching projection. No DDL, synthetic fallbacks, pricing or payment inference. */
public final class ManagementReportsIntegration {
    private ManagementReportsIntegration() {}
    public static final String VERSION = "M06-REVIEWED-TEACHING-1";
    private static final String BASE = "/api/management-reports";
    private static final List<String> HOURS = List.of("estimated", "planned", "actual", "payable");
    private static final Set<String> QUERY = Set.of("start", "end", "date_basis", "organizations");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_SOURCE_ROWS = 10000;

    /** Host mounts after authentication. Unknown routes do not touch authentication. */
    public static boolean handle(HttpExchange ex, Auth.Session supplied) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (!path.equals(BASE) && !path.equals(BASE + "/export") && !path.equals(BASE + "/teaching-export")) return false;
        Auth.Session current = Auth.get(Api.token(ex));
        if (current == null || current != supplied || Auth.current(current) == null)
            throw failure(401, "登录会话无效或已失效");
        if (!"GET".equals(ex.getRequestMethod())) throw failure(405, "仅支持只读查询");
        Map<String,String> query = strictQuery(ex);
        if (path.equals(BASE + "/teaching-export")) {
            byte[] bytes = downloadTeaching(current, query);
            String filename = "reviewed-teaching-" + inputDate(query.get("start")).toString().replace("-", "")
                    + "-" + inputDate(query.get("end")).toString().replace("-", "") + ".xlsx";
            Api.file(ex, bytes, ManagementTeachingWorkbook.CONTENT_TYPE, filename);
        } else if (path.endsWith("/export")) download(current, query);
        else Api.ok(ex, read(current, query));
        return true;
    }

    public static Map<String,Object> read(Auth.Session session, Map<String,?> query) throws Exception {
        return snapshot(session, query, false);
    }

    /** Rebuild and authorize at download time; a browser snapshot/version is never authority. */
    public static byte[] download(Auth.Session session, Map<String,?> query) throws Exception {
        snapshot(session, query, true);
        throw failure(409, "正式月报导出暂不可用：正式课程、讲师编码及课酬币种来源尚未接通");
    }

    /** A fresh, authorized teaching-only file. No caller supplied facts or cached permissions. */
    public static byte[] downloadTeaching(Auth.Session session, Map<String,?> query) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Map<String,Object> before = snapshot(session, query, true);
            byte[] bytes = ManagementTeachingWorkbook.export(before);
            // Encoding stays under the same lock. Re-check Auth, every scope and the complete
            // current projection before release; a session may be revoked outside this lock.
            Map<String,Object> after = snapshot(session, query, true);
            if (!Objects.equals(before.get("snapshot_version"), after.get("snapshot_version")))
                throw failure(409, "统计数据或权限范围已变化，请刷新后重试");
            return bytes;
        }
    }

    /** Api.query collapses duplicate keys. Keep a strict local transport boundary for M06. */
    private static Map<String,String> strictQuery(HttpExchange ex) throws Api.ApiException {
        Map<String,String> result = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return result;
        if (raw.length() > 24000) throw failure(400, "查询参数过长");
        for (String part : raw.split("&", -1)) {
            int separator = part.indexOf('=');
            if (separator <= 0) throw failure(400, "查询参数格式无效");
            try {
                String key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
                if (result.putIfAbsent(key, value) != null) throw failure(400, "查询参数不可重复：" + key);
            } catch (IllegalArgumentException e) { throw failure(400, "查询参数编码无效"); }
        }
        return result;
    }

    private record Selection(LocalDate start, LocalDate end, SortedSet<String> organizations, Object expected) {}

    private static Map<String,Object> snapshot(Auth.Session s, Map<String,?> query, boolean exporting) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            // Db.transaction commits the shared connection. Never nest it inside a caller's transaction.
            if (!Db.get().getAutoCommit()) throw failure(409, "统计查询必须在独立的只读事务中执行");
            return Db.transaction(() -> {
                OrganizationAccessStore.person(s);
                Selection selection = selection(query, exporting);
                OrganizationAccess.Configuration config = OrganizationAccessStore.configuration();
                SortedSet<String> allowed = new TreeSet<>();
                for (OrganizationAccess.Organization org : config.organizations()) {
                    if (OrganizationAccessStore.authorize(s, "reports.read", OrganizationAccess.Action.VIEW,
                            org.organizationCode()).allowed()) allowed.add(org.organizationCode());
                }
                if (allowed.isEmpty()) throw failure(403, "没有可查看统计的机构范围");
                SortedSet<String> selected = selection.organizations().isEmpty() ? allowed : selection.organizations();
                if (!allowed.containsAll(selected)) throw failure(403, "所选机构不在统计查看权限范围内");
                authorize(s, selected, exporting);
                if (exporting && (!(selection.expected() instanceof String value) || !value.matches("[0-9a-f]{64}")))
                    throw failure(400, "导出需要当前统计快照版本");
                Map<String,Object> view = build(selection, selected);
                boolean canExport = true;
                for (String org : selected) canExport &= OrganizationAccessStore.authorize(s, "reports.export", OrganizationAccess.Action.EXPORT, org).allowed();
                view.put("permissions", map("read", true, "export", canExport));
                view.put("teaching_export_available", canExport);
                view.put("available_organizations", new ArrayList<>(allowed));
                view.put("access_version", config.version());
                view.put("snapshot_version", digest(map("account_id", s.uid, "view", view)));
                // Explicit final check covers session revocation, account disablement and current M01 grants.
                authorize(s, selected, exporting);
                if (!config.version().equals(OrganizationAccessStore.configuration().version()))
                    throw failure(409, "统计权限已变化，请重新查询");
                if (exporting && !Objects.equals(selection.expected(), view.get("snapshot_version")))
                    throw failure(409, "统计数据或权限范围已变化，请刷新后重试");
                view.put("generated_at", Instant.now().toString());
                return view;
            });
        }
    }

    private static void authorize(Auth.Session s, Set<String> organizations, boolean exporting) throws Exception {
        OrganizationAccessStore.person(s);
        for (String org : organizations) {
            if (!OrganizationAccessStore.authorize(s, "reports.read", OrganizationAccess.Action.VIEW, org).allowed()
                    || (exporting && !OrganizationAccessStore.authorize(s, "reports.export", OrganizationAccess.Action.EXPORT, org).allowed()))
                throw failure(403, exporting ? "没有所选机构的统计查看与导出权限" : "没有所选机构的统计查看权限");
        }
    }

    private static Selection selection(Map<String,?> query, boolean exporting) throws Exception {
        if (query == null) throw failure(400, "需要查询条件");
        for (String key : query.keySet())
            if (!QUERY.contains(key) && !(exporting && key.equals("snapshot_version")))
                throw failure(400, "不接受查询字段：" + key);
        LocalDate start = inputDate(query.get("start")), end = inputDate(query.get("end"));
        if (start.isAfter(end)) throw failure(400, "开始日期不能晚于结束日期");
        Object basis = query.get("date_basis");
        if (basis != null && !"TEACHING".equals(basis)) {
            if ("PAYMENT".equals(basis)) throw failure(409, "支付统计暂不可用：真实支付登记尚未接通");
            throw failure(400, "日期口径仅接受授课日期或支付日期");
        }
        SortedSet<String> orgs = new TreeSet<>();
        if (query.containsKey("organizations")) {
            if (!(query.get("organizations") instanceof String text) || text.isBlank() || text.length() > 10000)
                throw failure(400, "机构筛选须为编码列表，省略表示当前可查看范围");
            for (String org : ((String) query.get("organizations")).split(",", -1)) {
                if (!validCode(org) || !orgs.add(org)) throw failure(400, "机构编码无效或重复");
            }
        }
        return new Selection(start, end, orgs, exporting ? query.get("snapshot_version") : null);
    }

    private static final String SOURCE = "SELECT d.id AS dispatch_id,d.project_id,d.teacher_id,d.teach_date,d.status AS dispatch_status,d.subject,"
            + "p.id AS source_project_id,p.demand_id AS project_demand_id,p.title AS project_title,t.id AS source_teacher_id,t.name AS teacher_name,"
            + "a.demand_id AS accepted_demand_id,w.organization_code,"
            + "f.project_id AS fact_project_id,f.teacher_id AS fact_teacher_id,f.organization_code AS fact_organization,f.revision,f.payload,"
            + "r.payload AS revision_payload,r.event_type AS revision_event,r.actor_code AS revision_actor "
            + "FROM dispatches d JOIN workflow_acceptances a ON a.project_id=d.project_id "
            + "JOIN workflow_demands w ON w.demand_id=a.demand_id "
            + "LEFT JOIN projects p ON p.id=d.project_id LEFT JOIN teachers t ON t.id=d.teacher_id "
            + "LEFT JOIN m05_delivery_facts f ON f.dispatch_id=d.id "
            + "LEFT JOIN m05_fact_revisions r ON r.dispatch_id=f.dispatch_id AND r.revision=f.revision "
            + "WHERE w.organization_code=? ORDER BY d.id FETCH FIRST 10001 ROWS ONLY";

    private static Map<String,Object> build(Selection selection, SortedSet<String> organizations) throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        List<Map<String,Object>> details = new ArrayList<>(), excluded = new ArrayList<>();
        Set<Long> seen = new HashSet<>(); int sourceCount = 0;
        for (String org : organizations) {
            for (Map<String,Object> row : Db.query(SOURCE, org)) {
                if (++sourceCount > MAX_SOURCE_ROWS) throw failure(409, "当前机构授课来源超过一次查询上限，请缩小机构范围");
                long id = positive(row.get("dispatch_id"));
                if (!seen.add(id)) throw failure(409, "授课来源关联重复，请先核对来源");
                LocalDate day;
                try { day = inputDate(row.get("teach_date")); }
                catch (Api.ApiException e) { exclude(excluded, row, "TEACHING_DATE_UNKNOWN", "授课日期缺失或无效，无法确定月份"); continue; }
                if (!inRange(day, selection)) {
                    // A moved dispatch must not silently erase a reviewed fact from its former month.
                    if (org.equals(row.get("fact_organization")) && row.get("payload") != null) {
                        try {
                            LocalDate saved = inputDate(object(Json.parse(row.get("payload").toString())).get("service_date"));
                            if (inRange(saved, selection) && !saved.equals(day))
                                exclude(excluded, row, "SOURCE_ASSOCIATION_CHANGED", "已存授课日期在本范围内，但当前排课日期已变化");
                        } catch (Api.ApiException | IllegalArgumentException | ClassCastException e) {
                            // Malformed out-of-range sources have no proven membership in this period.
                        }
                    }
                    continue;
                }
                if ("已拒绝".equals(row.get("dispatch_status"))) { exclude(excluded, row, "REJECTED", "排课已拒绝，不计入已核对授课"); continue; }
                if (!"已完成".equals(row.get("dispatch_status"))) { exclude(excluded, row, "NOT_COMPLETED", "排课尚未完成"); continue; }
                if (day.isAfter(today)) { exclude(excluded, row, "FUTURE_TEACHING_DATE", "授课日期尚未到达"); continue; }
                if (row.get("source_project_id") == null || row.get("source_teacher_id") == null
                        || !Objects.equals(row.get("project_demand_id"), row.get("accepted_demand_id"))) {
                    exclude(excluded, row, "SOURCE_ASSOCIATION_CHANGED", "项目、受理需求或讲师关联不完整"); continue;
                }
                if (row.get("payload") == null) { exclude(excluded, row, "FACT_MISSING", "尚未登记可信授课事实"); continue; }
                try {
                    Map<String,Object> fact = object(Json.parse(row.get("payload").toString()));
                    if (!Objects.equals(row.get("project_id"), row.get("fact_project_id"))
                            || !Objects.equals(row.get("teacher_id"), row.get("fact_teacher_id"))
                            || !org.equals(row.get("fact_organization")) || !org.equals(fact.get("organization_code"))
                            || !day.toString().equals(fact.get("service_date")) || positive(fact.get("record_id")) != id
                            || positive(fact.get("revision")) != positive(row.get("revision"))
                            || !("DISPATCH-" + id).equals(fact.get("session_code"))
                            || !("TEACHER-" + positive(row.get("teacher_id"))).equals(fact.get("instructor_code"))) {
                        exclude(excluded, row, "SOURCE_ASSOCIATION_CHANGED", "授课事实与当前关联或日期不一致"); continue;
                    }
                    if (!"CONFIGURED".equals(fact.get("data_mode"))) { exclude(excluded, row, "NON_BUSINESS_FACT", "演示或未配置事实不计入业务统计"); continue; }
                    if (fact.get("verification") == null) { exclude(excluded, row, "NOT_VERIFIED", "授课事实尚未核对"); continue; }
                    Map<String,Object> verification = object(fact.get("verification"));
                    if (!validCode(verification.get("actor_code")) || !validCode(verification.get("evidence_code"))) throw new IllegalArgumentException();
                    Instant checkedAt = Instant.parse((String) verification.get("checked_at"));
                    if (checkedAt.isAfter(Instant.now()) || !"VERIFY".equals(row.get("revision_event"))
                            || !Objects.equals(row.get("revision_actor"), verification.get("actor_code"))
                            || row.get("revision_payload") == null
                            || !canonical(fact).equals(canonical(Json.parse(row.get("revision_payload").toString())))) {
                        exclude(excluded, row, "VERIFICATION_SOURCE_INVALID", "当前核对修订与历史依据不一致"); continue;
                    }
                    Map<String,Object> hours = object(fact.get("hours"));
                    for (String category : HOURS) {
                        if (!hours.containsKey(category)) throw new IllegalArgumentException();
                        quantity(hours.get(category));
                    }
                    if (!consistentConversion(fact, hours)) { exclude(excluded, row, "ACTUAL_EVIDENCE_INVALID", "实际分钟与课时换算依据不完整或不一致"); continue; }
                    Map<String,Object> values = new LinkedHashMap<>();
                    for (String category : HOURS) values.put(category, hours.get(category));
                    details.add(map("dispatch_id", id, "project_id", row.get("project_id"), "teacher_id", row.get("teacher_id"),
                            "organization_code", org, "project_title", row.get("project_title"), "teacher_name", row.get("teacher_name"),
                            "subject", row.get("subject"), "teaching_date", day.toString(), "fact_revision", positive(row.get("revision")),
                            "verified_at", checkedAt.toString(), "hours", values, "fee", null, "currency", null,
                            "course_id", null, "course_code", null, "teacher_code", null));
                } catch (IllegalArgumentException | ClassCastException | NullPointerException | DateTimeException e) {
                    exclude(excluded, row, "FACT_INVALID", "授课事实格式或核对依据无效，需先核对来源");
                }
            }
        }
        Map<String,Object> totals = totals(details);
        Map<String,Object> availability = map("teaching", true, "payment", false, "fees", false, "formal_export", false,
                "reasons", List.of(issue("PAYMENT_NOT_CONNECTED", "真实支付登记尚未接通"),
                        issue("FEES_NOT_CONNECTED", "正式课酬及币种来源尚未接通"),
                        issue("FORMAL_CODES_NOT_CONNECTED", "正式课程、讲师编码映射尚未接通")));
        return map("adapter_version", VERSION, "policy_version", "M06-20260921-1", "date_basis", "TEACHING",
                "start", selection.start().toString(), "end", selection.end().toString(), "server_today", today.toString(),
                "organizations", new ArrayList<>(organizations), "population", "REVIEWED_COMPLETED_DISPATCHES",
                "coverage_notice", "仅覆盖已建立可信机构来源的排课；未迁移旧项目不在本统计覆盖内。四类课时均来自已完成且已核对的同一批记录。",
                "included_count", details.size(), "excluded_count", excluded.size(),
                "undated_excluded_count", excluded.stream().filter(row -> Boolean.TRUE.equals(row.get("date_unknown"))).count(),
                "details", details, "excluded", excluded,
                "totals", totals, "teacher_ranking", rankings(details), "organization_summary", organizationTotals(details),
                "fee", null, "currency", null, "course_count", null, "availability", availability);
    }

    private static boolean consistentConversion(Map<String,Object> fact, Map<String,Object> hours) {
        Map<String,Object> c = object(fact.get("conversion")), dimensions = object(fact.get("dimensions"));
        if (!"CLASS45".equals(dimensions.get("hour_unit")) || hours.get("actual") == null
                || !"READY".equals(c.get("status")) || !(c.get("minutes") instanceof String)
                || !DeliverySettlementHours.CURRENT_VERSION.equals(c.get("rule_version"))
                || !DeliverySettlementHours.USER_EVIDENCE.equals(c.get("evidence_code"))
                || !DeliverySettlementHours.ROUNDING_DECISION.equals(c.get("rounding_decision_code"))
                || !numberEquals(c.get("minutes_per_class_hour"), 45) || !numberEquals(c.get("class_hour_scale"), 2)
                || !"HALF_UP".equals(c.get("class_hour_rounding"))) return false;
        DeliverySettlementHours.Conversion conversion = DeliverySettlementHours.convert(c.get("minutes"), DeliverySettlementHours.currentUserRule());
        BigDecimal stored = quantity(c.get("class_hours"));
        return conversion.status() == DeliverySettlementHours.Status.READY && stored != null
                && conversion.classHours().compareTo(quantity(hours.get("actual"))) == 0
                && conversion.classHours().compareTo(stored) == 0;
    }

    private static Map<String,Object> totals(List<Map<String,Object>> details) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String category : HOURS) {
            BigDecimal sum = BigDecimal.ZERO; int missing = 0;
            for (Map<String,Object> row : details) {
                BigDecimal value = quantity(object(row.get("hours")).get(category));
                if (value == null) missing++; else sum = sum.add(value);
            }
            result.put(category, map("value", missing == 0 ? sum.toPlainString() : null,
                    "known_subtotal", sum.toPlainString(), "missing_records", missing, "complete", missing == 0));
        }
        return result;
    }

    private static List<Map<String,Object>> rankings(List<Map<String,Object>> details) {
        Map<Long,List<Map<String,Object>>> groups = new TreeMap<>();
        for (Map<String,Object> row : details) groups.computeIfAbsent(positive(row.get("teacher_id")), unused -> new ArrayList<>()).add(row);
        List<Map<String,Object>> result = new ArrayList<>();
        for (var entry : groups.entrySet()) result.add(map("teacher_id", entry.getKey(), "teacher_code", null,
                "teacher_name", entry.getValue().get(0).get("teacher_name"), "dispatch_count", entry.getValue().size(), "hours", totals(entry.getValue())));
        result.sort(Comparator.<Map<String,Object>,BigDecimal>comparing(row -> new BigDecimal((String) object(object(row.get("hours")).get("actual")).get("value"))).reversed()
                .thenComparingLong(row -> positive(row.get("teacher_id"))));
        BigDecimal previous = null; int rank = 0, index = 0;
        for (Map<String,Object> row : result) {
            BigDecimal value = new BigDecimal((String) object(object(row.get("hours")).get("actual")).get("value"));
            index++; if (previous == null || value.compareTo(previous) != 0) rank = index;
            row.put("rank", rank); previous = value;
        }
        return result;
    }

    private static List<Map<String,Object>> organizationTotals(List<Map<String,Object>> details) {
        Map<String,List<Map<String,Object>>> groups = new TreeMap<>();
        for (Map<String,Object> row : details) groups.computeIfAbsent((String) row.get("organization_code"), unused -> new ArrayList<>()).add(row);
        List<Map<String,Object>> result = new ArrayList<>();
        for (var entry : groups.entrySet()) result.add(map("organization_code", entry.getKey(), "dispatch_count", entry.getValue().size(), "hours", totals(entry.getValue())));
        return result;
    }

    private static void exclude(List<Map<String,Object>> excluded, Map<String,Object> row, String code, String message) {
        excluded.add(map("dispatch_id", row.get("dispatch_id"), "organization_code", row.get("organization_code"),
                "date_unknown", code.equals("TEACHING_DATE_UNKNOWN"), "code", code, "message", message));
    }
    private static Map<String,Object> issue(String code, String message) { return map("code", code, "message", message); }
    private static boolean validCode(Object v) { return v instanceof String s && s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}"); }
    private static boolean numberEquals(Object v, int expected) {
        return v instanceof Number && new BigDecimal(v.toString()).compareTo(BigDecimal.valueOf(expected)) == 0;
    }
    private static BigDecimal quantity(Object v) {
        if (v != null && !(v instanceof String)) throw new IllegalArgumentException("课时必须为字符串或null");
        return DeliverySettlement.decimalInput(v);
    }
    private static long positive(Object v) {
        if (!(v instanceof Number) && !(v instanceof String)) throw new IllegalArgumentException("无效标识");
        try {
            long n = new BigDecimal(v.toString()).longValueExact();
            if (n <= 0 || n > 9007199254740991L) throw new IllegalArgumentException("无效标识");
            return n;
        } catch (ArithmeticException e) { throw new IllegalArgumentException("无效标识", e); }
    }
    private static LocalDate inputDate(Object v) throws Api.ApiException {
        if (!(v instanceof String s) || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw failure(400, "日期须为YYYY-MM-DD");
        try { return LocalDate.parse((String) v); } catch (DateTimeException e) { throw failure(400, "日期无效"); }
    }
    private static boolean inRange(LocalDate day, Selection selection) {
        return !day.isBefore(selection.start()) && !day.isAfter(selection.end());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object v) {
        if (!(v instanceof Map<?,?>)) throw new IllegalArgumentException("需要对象");
        return (Map<String,Object>) v;
    }
    private static Map<String,Object> map(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    private static Object sorted(Object v) {
        if (v instanceof Map<?,?> m) { Map<String,Object> result = new TreeMap<>(); m.forEach((k,x) -> result.put(k.toString(), sorted(x))); return result; }
        if (v instanceof List<?> l) return l.stream().map(ManagementReportsIntegration::sorted).toList();
        return v;
    }
    private static String canonical(Object v) { return Json.write(sorted(v)); }
    private static String digest(Object v) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(v).getBytes(StandardCharsets.UTF_8)));
    }
    private static Api.ApiException failure(int status, String message) { return new Api.ApiException(status, message); }
}
