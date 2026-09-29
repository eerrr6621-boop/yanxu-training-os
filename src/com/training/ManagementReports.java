package com.training;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** M06: read-only reporting over a caller-supplied, authorized fact snapshot. No pricing or DB access. */
public final class ManagementReports {
    private ManagementReports() {}
    public static final String AGREED_RULE_VERSION = "M06-20260921-1";
    public enum DateBasis { TEACHING, CONFIRMATION, PAYMENT }
    public enum HourBasis { ESTIMATED, PLANNED, ACTUAL, PAYABLE }
    public enum Status { CONFIRMED, CANCELLED, SUPERSEDED, ADJUSTMENT }
    public enum RankMetric { HOURS, FEE }
    public enum TieRule { COMPETITION, DENSE, ORDINAL }
    public enum ExportKind { DETAIL, TEACHER, ORGANIZATION }

    /** 2026-09-21 user chose teaching/payment dates and all hour categories, delegating remaining defaults. */
    public static Rules agreedRules(DateBasis dateBasis, HourBasis hourBasis, RankMetric rankMetric, String currency) {
        require(dateBasis == DateBasis.TEACHING || dateBasis == DateBasis.PAYMENT, "当前已确认口径支持授课日期或支付日期");
        return new Rules(AGREED_RULE_VERSION, dateBasis, hourBasis,
                Set.of(Status.CONFIRMED, Status.ADJUSTMENT), rankMetric, TieRule.COMPETITION, currency);
    }
    public static Rules agreedDefaultRules(String currency) { return agreedRules(DateBasis.TEACHING, HourBasis.ACTUAL, RankMetric.HOURS, currency); }

    public record Fact(long id, long courseId, String recordCode, String teacherCode,
            String orgCode, String courseCode, Map<DateBasis, LocalDate> dates,
            Map<HourBasis, BigDecimal> hours, BigDecimal fee, String currency,
            String feeVersion, Status status) {
        public Fact {
            require(id > 0 && courseId > 0, "业务记录 id 必须为正整数");
            code(recordCode, "明细编码"); code(teacherCode, "讲师编码");
            code(orgCode, "机构编码"); code(courseCode, "课程编码");
            require(status != null, "记录状态未提供");
            require(dates != null && hours != null, "日期及课时字段未提供");
            dates = Map.copyOf(dates); hours = Map.copyOf(hours);
            hours.values().forEach(ManagementReports::finiteDecimal);
            if (fee != null) finiteDecimal(fee);
            if (currency != null) require(currency.matches("[A-Z]{3}"), "币种必须为三个大写字母");
            if (feeVersion != null) code(feeVersion, "原课酬版本");
        }
    }

    /** All metric rules are mandatory. Version must refer to an explicit, agreed upstream policy. */
    public record Rules(String version, DateBasis dateBasis, HourBasis hourBasis,
            Set<Status> includedStatuses, RankMetric rankMetric, TieRule tieRule, String currency) {
        public Rules {
            code(version, "统计规则版本");
            require(dateBasis != null && hourBasis != null && rankMetric != null && tieRule != null,
                    "统计日期、课时类别、排名指标及并列规则必须显式提供");
            require(includedStatuses != null && !includedStatuses.isEmpty(), "计入状态必须显式提供");
            includedStatuses = Set.copyOf(includedStatuses);
            require(currency != null && currency.matches("[A-Z]{3}"), "统计币种必须显式提供");
        }
    }

    public record Filter(LocalDate start, LocalDate end, Set<String> orgCodes) {
        public Filter {
            require(start != null && end != null && !end.isBefore(start), "起止日期缺失或顺序错误");
            require(orgCodes != null, "机构筛选未提供；空集合表示当前授权范围");
            orgCodes.forEach(c -> code(c, "筛选机构编码"));
            orgCodes = Set.copyOf(orgCodes);
        }
    }

    /** Trusted server input; never construct from request JSON. Empty scope grants no data. */
    public record AccessScope(Set<String> orgCodes, boolean canView, boolean canExport) {
        public AccessScope {
            require(orgCodes != null, "服务端授权范围未提供");
            orgCodes.forEach(c -> code(c, "授权机构编码"));
            orgCodes = Set.copyOf(orgCodes);
        }
    }

    public record Detail(long id, long courseId, String recordCode, String teacherCode,
            String orgCode, String courseCode, LocalDate date, BigDecimal hours, BigDecimal fee,
            String currency, String feeVersion, Status status) {}
    public record Total(int records, int courses, BigDecimal hours, BigDecimal fee) {}
    public record HourTotal(BigDecimal hours, int missingRecords) {}
    public record Group(String code, int rank, Total total) {}
    public record Reconciliation(BigDecimal detailHoursDifference, BigDecimal detailFeeDifference,
            BigDecimal teacherHoursDifference, BigDecimal teacherFeeDifference,
            BigDecimal organizationHoursDifference, BigDecimal organizationFeeDifference,
            int teacherRecordDifference, int organizationRecordDifference) {
        public boolean matches() {
            return List.of(detailHoursDifference, detailFeeDifference, teacherHoursDifference,
                    teacherFeeDifference, organizationHoursDifference, organizationFeeDifference)
                    .stream().allMatch(v -> v.signum() == 0)
                    && teacherRecordDifference == 0 && organizationRecordDifference == 0;
        }
    }

    public static final class Report {
        private final Rules rules;
        private final Filter filter;
        private final AccessScope access;
        private final List<Detail> details;
        private final List<Group> teachers;
        private final List<Group> organizations;
        private final Total total;
        private final Map<HourBasis, HourTotal> hourTotals;
        private final Reconciliation reconciliation;
        private Report(Rules rules, Filter filter, AccessScope access, List<Detail> details,
                List<Group> teachers, List<Group> organizations, Total total, Map<HourBasis, HourTotal> hourTotals, Reconciliation reconciliation) {
            this.rules = rules; this.filter = filter; this.access = access;
            this.details = List.copyOf(details); this.teachers = List.copyOf(teachers);
            this.organizations = List.copyOf(organizations); this.total = total;
            this.hourTotals = Map.copyOf(hourTotals);
            this.reconciliation = reconciliation;
        }
        public Rules rules() { return rules; }
        public Filter filter() { return filter; }
        public List<Detail> details() { return details; }
        public List<Group> teachers() { return teachers; }
        public List<Group> organizations() { return organizations; }
        public Total total() { return total; }
        public Map<HourBasis, HourTotal> hourTotals() { return hourTotals; }
        public Reconciliation reconciliation() { return reconciliation; }
        public boolean canExport() { return access.canExport(); }
        public Map<String, Object> toMap() {
            return map("schemaVersion", "M06-1", "rules", rulesMap(rules),
                    "filter", map("start", filter.start().toString(), "end", filter.end().toString(),
                            "orgCodes", filter.orgCodes().stream().sorted().toList()),
                    "total", totalMap(total), "hourTotals", hourTotalsMap(hourTotals), "details", details.stream().map(ManagementReports::detailMap).toList(),
                    "teachers", teachers.stream().map(ManagementReports::groupMap).toList(),
                    "organizations", organizations.stream().map(ManagementReports::groupMap).toList(),
                    "reconciliation", map("matches", reconciliation.matches(),
                            "detailHoursDifference", decimal(reconciliation.detailHoursDifference()),
                            "detailFeeDifference", decimal(reconciliation.detailFeeDifference()),
                            "teacherHoursDifference", decimal(reconciliation.teacherHoursDifference()),
                            "teacherFeeDifference", decimal(reconciliation.teacherFeeDifference()),
                            "organizationHoursDifference", decimal(reconciliation.organizationHoursDifference()),
                            "organizationFeeDifference", decimal(reconciliation.organizationFeeDifference()),
                            "teacherRecordDifference", reconciliation.teacherRecordDifference(),
                            "organizationRecordDifference", reconciliation.organizationRecordDifference()),
                    "canExport", canExport());
        }
    }

    public static Report build(List<Fact> snapshot, Rules rules, Filter filter, AccessScope access) {
        require(snapshot != null && rules != null && filter != null && access != null, "明细、规则、筛选及授权均须提供");
        if (!access.canView()) throw new SecurityException("当前账号无统计查看权限");
        if (!access.orgCodes().containsAll(filter.orgCodes())) throw new SecurityException("筛选机构超出授权范围");
        Set<Long> ids = new HashSet<>(); Set<String> codes = new HashSet<>();
        Map<Long, String> courseCodes = new HashMap<>(); Map<String, Long> courseIds = new HashMap<>();
        List<Detail> details = new ArrayList<>();
        Map<HourBasis, BigDecimal> hourSums = new EnumMap<>(HourBasis.class);
        Map<HourBasis, Integer> missingHours = new EnumMap<>(HourBasis.class);
        for (HourBasis basis : HourBasis.values()) { hourSums.put(basis, BigDecimal.ZERO); missingHours.put(basis, 0); }
        for (Fact f : snapshot) {
            require(f != null, "明细包含空记录");
            if (!access.orgCodes().contains(f.orgCode())) continue;
            if (!filter.orgCodes().isEmpty() && !filter.orgCodes().contains(f.orgCode())) continue;
            require(ids.add(f.id()) && codes.add(f.recordCode()), "输入存在重复业务记录或明细编码，请先核对来源");
            String oldCode = courseCodes.putIfAbsent(f.courseId(), f.courseCode());
            Long oldId = courseIds.putIfAbsent(f.courseCode(), f.courseId());
            require((oldCode == null || oldCode.equals(f.courseCode())) && (oldId == null || oldId == f.courseId()),
                    "课程 id 与编码对应关系冲突");
            if (!rules.includedStatuses().contains(f.status())) continue;
            LocalDate date = f.dates().get(rules.dateBasis());
            require(date != null, "计入状态的明细缺少所选统计日期，无法判断是否在范围内");
            if (date.isBefore(filter.start()) || date.isAfter(filter.end())) continue;
            BigDecimal hours = f.hours().get(rules.hourBasis());
            require(hours != null, "入选明细缺少所选类别课时");
            require(f.fee() != null && f.feeVersion() != null, "入选明细缺少已保存课酬或原课酬版本");
            require(rules.currency().equals(f.currency()), "入选明细币种与统计口径不一致，不允许混币累加");
            for (HourBasis basis : HourBasis.values()) {
                BigDecimal value = f.hours().get(basis);
                if (value == null) missingHours.put(basis, missingHours.get(basis) + 1);
                else hourSums.put(basis, hourSums.get(basis).add(value));
            }
            details.add(new Detail(f.id(), f.courseId(), f.recordCode(), f.teacherCode(), f.orgCode(),
                    f.courseCode(), date, hours, f.fee(), f.currency(), f.feeVersion(), f.status()));
        }
        details.sort(Comparator.comparing(Detail::date).thenComparing(Detail::recordCode).thenComparingLong(Detail::id));
        Total total = summarize(details);
        List<Group> teachers = groups(details, true, rules);
        List<Group> organizations = groups(details, false, rules);
        Total detailTotal = summarize(details);
        Reconciliation reconciliation = new Reconciliation(
                detailTotal.hours().subtract(total.hours()), detailTotal.fee().subtract(total.fee()),
                sumGroups(teachers, true).subtract(total.hours()), sumGroups(teachers, false).subtract(total.fee()),
                sumGroups(organizations, true).subtract(total.hours()), sumGroups(organizations, false).subtract(total.fee()),
                teachers.stream().mapToInt(g -> g.total().records()).sum() - total.records(),
                organizations.stream().mapToInt(g -> g.total().records()).sum() - total.records());
        require(reconciliation.matches(), "汇总与明细未对平，停止输出");
        Map<HourBasis, HourTotal> hourTotals = new EnumMap<>(HourBasis.class);
        for (HourBasis basis : HourBasis.values()) hourTotals.put(basis,
                new HourTotal(missingHours.get(basis) == 0 ? hourSums.get(basis) : null, missingHours.get(basis)));
        return new Report(rules, filter, access, details, teachers, organizations, total, hourTotals, reconciliation);
    }

    /** Compare a user-supplied known correct control total; positive difference means computed > expected. */
    public static Map<String, Object> compareExpected(Report report, Total expected) {
        require(report != null && expected != null && expected.hours() != null && expected.fee() != null,
                "请显式提供正确汇总的记录数、唯一课程数、课时和课酬");
        int records = report.total.records() - expected.records(), courses = report.total.courses() - expected.courses();
        BigDecimal hours = report.total.hours().subtract(expected.hours()), fee = report.total.fee().subtract(expected.fee());
        return map("matches", records == 0 && courses == 0 && hours.signum() == 0 && fee.signum() == 0,
                "recordsDifference", records, "coursesDifference", courses,
                "hoursDifference", decimal(hours), "feeDifference", decimal(fee));
    }

    private static Total summarize(List<Detail> details) {
        Set<Long> courses = new HashSet<>(); BigDecimal hours = BigDecimal.ZERO, fee = BigDecimal.ZERO;
        for (Detail d : details) { courses.add(d.courseId()); hours = hours.add(d.hours()); fee = fee.add(d.fee()); }
        return new Total(details.size(), courses.size(), hours, fee);
    }
    private static List<Group> groups(List<Detail> details, boolean teacher, Rules rules) {
        Map<String, List<Detail>> buckets = new TreeMap<>();
        for (Detail d : details) buckets.computeIfAbsent(teacher ? d.teacherCode() : d.orgCode(), key -> new ArrayList<>()).add(d);
        List<Group> groups = new ArrayList<>();
        buckets.forEach((code, items) -> groups.add(new Group(code, 0, summarize(items))));
        if (!teacher) return List.copyOf(groups);
        groups.sort(Comparator.<Group, BigDecimal>comparing(g -> metric(g, rules.rankMetric())).reversed().thenComparing(Group::code));
        List<Group> ranked = new ArrayList<>(); BigDecimal previous = null; int dense = 0, competition = 0;
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i); BigDecimal value = metric(group, rules.rankMetric());
            if (previous == null || previous.compareTo(value) != 0) { dense++; competition = i + 1; }
            int rank = switch (rules.tieRule()) { case COMPETITION -> competition; case DENSE -> dense; case ORDINAL -> i + 1; };
            ranked.add(new Group(group.code(), rank, group.total())); previous = value;
        }
        return List.copyOf(ranked);
    }
    private static BigDecimal metric(Group group, RankMetric metric) { return metric == RankMetric.HOURS ? group.total().hours() : group.total().fee(); }
    private static BigDecimal sumGroups(List<Group> groups, boolean hours) {
        return groups.stream().map(g -> hours ? g.total().hours() : g.total().fee()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Download sidecar, required even for empty CSVs so filters/rules remain traceable. */
    public static Map<String, Object> exportManifest(Report report, ExportKind kind) {
        require(report != null && kind != null, "报表及导出类型未提供");
        if (!report.canExport()) throw new SecurityException("当前账号无统计导出权限");
        return map("schemaVersion", "M06-1", "exportKind", kind.name(), "rules", rulesMap(report.rules),
                "start", report.filter.start().toString(), "end", report.filter.end().toString(),
                "effectiveOrgCodes", (report.filter.orgCodes().isEmpty() ? report.access.orgCodes() : report.filter.orgCodes()).stream().sorted().toList(),
                "rowCount", switch (kind) { case DETAIL -> report.details.size(); case TEACHER -> report.teachers.size(); case ORGANIZATION -> report.organizations.size(); },
                "total", totalMap(report.total), "hourTotals", hourTotalsMap(report.hourTotals), "reconciled", report.reconciliation.matches(),
                "decimalFormat", "EXACT_DECIMAL_STRING", "courseCountMetric", "DISTINCT_COURSE_ID_NON_ADDITIVE");
    }

    /** UTF-8 BOM + RFC4180 CSV. Host must recheck live permissions before returning a download. */
    public static String exportCsv(Report report, ExportKind kind) {
        require(report != null && kind != null, "报表及导出类型未提供");
        if (!report.canExport()) throw new SecurityException("当前账号无统计导出权限");
        List<String> headers = new ArrayList<>(List.of("schema_version", "rule_version", "date_basis", "hour_basis",
                "included_statuses", "rank_metric", "tie_rule", "start", "end", "org_filter", "currency"));
        headers.addAll(switch (kind) {
            case DETAIL -> List.of("record_code", "teacher_code", "org_code", "course_code", "stat_date", "status", "hours", "fee", "fee_version");
            case TEACHER -> List.of("teacher_code", "rank", "record_count", "unique_course_count", "hours", "fee");
            case ORGANIZATION -> List.of("org_code", "record_count", "unique_course_count", "hours", "fee");
        });
        StringBuilder csv = new StringBuilder("\uFEFF"); csvLine(csv, headers);
        List<String> metadata = List.of("M06-1", report.rules.version(), report.rules.dateBasis().name(), report.rules.hourBasis().name(),
                String.join("|", report.rules.includedStatuses().stream().map(Enum::name).sorted().toList()),
                report.rules.rankMetric().name(), report.rules.tieRule().name(), report.filter.start().toString(), report.filter.end().toString(),
                String.join("|", (report.filter.orgCodes().isEmpty() ? report.access.orgCodes() : report.filter.orgCodes()).stream().sorted().toList()),
                report.rules.currency());
        if (kind == ExportKind.DETAIL) {
            for (Detail d : report.details) {
                List<String> row = new ArrayList<>(metadata);
                row.addAll(List.of(d.recordCode(), d.teacherCode(), d.orgCode(), d.courseCode(), d.date().toString(), d.status().name(),
                        decimal(d.hours()), decimal(d.fee()), d.feeVersion())); csvLine(csv, row);
            }
        } else {
            for (Group g : kind == ExportKind.TEACHER ? report.teachers : report.organizations) {
                List<String> row = new ArrayList<>(metadata); row.add(g.code());
                if (kind == ExportKind.TEACHER) row.add(Integer.toString(g.rank()));
                row.addAll(List.of(Integer.toString(g.total().records()), Integer.toString(g.total().courses()),
                        decimal(g.total().hours()), decimal(g.total().fee()))); csvLine(csv, row);
            }
        }
        return csv.toString();
    }
    private static void csvLine(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i != 0) out.append(','); out.append('"').append(cells.get(i).replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    /** Decimal facts must be JSON strings: existing Json parser reads numeric tokens as Double. */
    public static Fact factFromMap(Map<String, Object> input) {
        require(input != null, "明细未提供");
        Map<String, Object> rawDates = object(input.get("dates"), "dates");
        Map<DateBasis, LocalDate> dates = new EnumMap<>(DateBasis.class);
        for (DateBasis basis : DateBasis.values()) if (rawDates.get(basis.name()) != null)
            dates.put(basis, LocalDate.parse(string(rawDates.get(basis.name()), "日期")));
        Map<String, Object> rawHours = object(input.get("hours"), "hours");
        Map<HourBasis, BigDecimal> hours = new EnumMap<>(HourBasis.class);
        for (HourBasis basis : HourBasis.values()) if (rawHours.get(basis.name()) != null)
            hours.put(basis, decimalInput(rawHours.get(basis.name())));
        return new Fact(positiveId(input.get("id")), positiveId(input.get("courseId")),
                string(input.get("recordCode"), "recordCode"), string(input.get("teacherCode"), "teacherCode"),
                string(input.get("orgCode"), "orgCode"), string(input.get("courseCode"), "courseCode"), dates, hours,
                input.get("fee") == null ? null : decimalInput(input.get("fee")),
                nullableString(input.get("currency")), nullableString(input.get("feeVersion")),
                Status.valueOf(string(input.get("status"), "status")));
    }
    public static Rules rulesFromMap(Map<String, Object> input) {
        require(input != null, "正式统计规则未配置");
        Set<Status> statuses = new HashSet<>();
        for (Object value : array(input.get("includedStatuses"), "includedStatuses")) statuses.add(Status.valueOf(string(value, "状态")));
        return new Rules(string(input.get("version"), "version"), DateBasis.valueOf(string(input.get("dateBasis"), "dateBasis")),
                HourBasis.valueOf(string(input.get("hourBasis"), "hourBasis")), statuses,
                RankMetric.valueOf(string(input.get("rankMetric"), "rankMetric")), TieRule.valueOf(string(input.get("tieRule"), "tieRule")),
                string(input.get("currency"), "currency"));
    }
    public static Filter filterFromMap(Map<String, Object> input) {
        require(input != null, "统计筛选未提供"); Set<String> orgs = new HashSet<>();
        for (Object value : array(input.get("orgCodes"), "orgCodes")) orgs.add(string(value, "机构编码"));
        return new Filter(LocalDate.parse(string(input.get("start"), "start")), LocalDate.parse(string(input.get("end"), "end")), orgs);
    }
    private static Map<String, Object> rulesMap(Rules r) {
        return map("version", r.version(), "dateBasis", r.dateBasis().name(), "hourBasis", r.hourBasis().name(),
                "includedStatuses", r.includedStatuses().stream().map(Enum::name).sorted().toList(),
                "rankMetric", r.rankMetric().name(), "tieRule", r.tieRule().name(), "currency", r.currency());
    }
    private static Map<String, Object> detailMap(Detail d) {
        return map("id", Long.toString(d.id()), "courseId", Long.toString(d.courseId()), "recordCode", d.recordCode(),
                "teacherCode", d.teacherCode(), "orgCode", d.orgCode(), "courseCode", d.courseCode(), "date", d.date().toString(),
                "hours", decimal(d.hours()), "fee", decimal(d.fee()), "currency", d.currency(), "feeVersion", d.feeVersion(), "status", d.status().name());
    }
    private static Map<String, Object> totalMap(Total t) {
        return map("records", t.records(), "courses", t.courses(), "hours", decimal(t.hours()), "fee", decimal(t.fee()));
    }
    private static Map<String, Object> hourTotalsMap(Map<HourBasis, HourTotal> hourTotals) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (HourBasis basis : HourBasis.values()) {
            HourTotal total = hourTotals.get(basis);
            result.put(basis.name(), map("hours", total.hours() == null ? null : decimal(total.hours()), "missingRecords", total.missingRecords()));
        }
        return Collections.unmodifiableMap(result);
    }
    private static Map<String, Object> groupMap(Group g) { return map("code", g.code(), "rank", g.rank(), "total", totalMap(g.total())); }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(result);
    }
    private static String decimal(BigDecimal value) { return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString(); }
    private static BigDecimal decimalInput(Object value) {
        require(value instanceof String && ((String) value).matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"), "课时和课酬必须为十进制字符串");
        BigDecimal result = new BigDecimal((String) value); finiteDecimal(result); return result;
    }
    private static void finiteDecimal(BigDecimal value) {
        require(value != null && value.scale() >= 0 && value.scale() <= 8 && value.precision() <= 24, "数值最多 24 位、8 位小数；不得隐式舍入");
    }
    private static long positiveId(Object value) {
        if (value instanceof String s) {
            require(s.matches("[1-9][0-9]{0,18}"), "业务 id 必须为正整数字符串或安全整数");
            try { return Long.parseLong(s); } catch (NumberFormatException ex) { throw new IllegalArgumentException("业务 id 超出范围"); }
        }
        if (value instanceof Number n) {
            try {
                long id = new BigDecimal(n.toString()).longValueExact();
                require(id > 0 && id <= 9007199254740991L, "业务 id 必须为安全正整数");
                return id;
            } catch (ArithmeticException | NumberFormatException ex) {
                throw new IllegalArgumentException("业务 id 必须为安全正整数");
            }
        }
        throw new IllegalArgumentException("业务 id 缺失或类型错误");
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) {
        require(value instanceof Map, field + " 必须为对象"); return (Map<String, Object>) value;
    }
    private static List<?> array(Object value, String field) { require(value instanceof List, field + " 必须显式提供数组"); return (List<?>) value; }
    private static String string(Object value, String field) { require(value instanceof String && !((String) value).isBlank(), field + " 必须显式提供字符串"); return (String) value; }
    private static String nullableString(Object value) { return value == null ? null : string(value, "字段"); }
    private static void code(String value, String field) {
        require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}"), field + " 必须为已分配编码（字母数字及 _ . : -，最长 96 位）");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
