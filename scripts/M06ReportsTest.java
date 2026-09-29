package com.training;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.training.ManagementReports.*;

/** Small synthetic-only probe. No database, server, migration or production dependencies. */
public class M06ReportsTest {
    private static int checks;
    private static final AccessScope ACCESS = new AccessScope(Set.of("ORG-1", "ORG-2"), true, true);
    private static final Filter SEPTEMBER = new Filter(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), Set.of());
    private static Rules rules(DateBasis date, HourBasis hours, RankMetric metric, TieRule tie) {
        return new Rules("DEMO-M06-1", date, hours, Set.of(Status.CONFIRMED, Status.ADJUSTMENT), metric, tie, "CNY");
    }
    private static Rules rules() { return rules(DateBasis.TEACHING, HourBasis.ACTUAL, RankMetric.HOURS, TieRule.COMPETITION); }
    private static Fact fact(long id, long courseId, String teacher, String org, String date, String hours, String fee, Status status) {
        Map<DateBasis, LocalDate> dates = new EnumMap<>(DateBasis.class);
        dates.put(DateBasis.TEACHING, LocalDate.parse(date));
        dates.put(DateBasis.CONFIRMATION, LocalDate.parse(date).plusDays(2));
        dates.put(DateBasis.PAYMENT, LocalDate.parse(date).plusMonths(1));
        return new Fact(id, courseId, "REC-" + id, teacher, org, "COURSE-" + courseId, dates,
                Map.of(HourBasis.ACTUAL, new BigDecimal(hours), HourBasis.PAYABLE, new BigDecimal(hours).multiply(new BigDecimal("2")),
                        HourBasis.ESTIMATED, new BigDecimal("4"), HourBasis.PLANNED, new BigDecimal("3")),
                new BigDecimal(fee), "CNY", "DEMO-FEE-OLD", status);
    }
    private static List<Fact> facts() {
        return List.of(fact(1, 101, "T-1", "ORG-1", "2026-09-01", "0.1", "0.10", Status.CONFIRMED),
                fact(2, 101, "T-1", "ORG-1", "2026-09-30", "0.2", "0.20", Status.CONFIRMED),
                fact(3, 102, "T-2", "ORG-2", "2026-09-15", "0.3", "0.30", Status.CONFIRMED),
                fact(4, 103, "T-3", "ORG-1", "2026-09-15", "0.2", "0.20", Status.CONFIRMED),
                fact(5, 104, "T-4", "ORG-1", "2026-09-16", "0.1", "0.10", Status.CONFIRMED),
                fact(6, 101, "T-1", "ORG-1", "2026-09-20", "-0.1", "-0.10", Status.ADJUSTMENT),
                fact(7, 105, "T-1", "ORG-1", "2026-09-20", "100", "100", Status.CANCELLED),
                fact(8, 106, "T-1", "ORG-1", "2026-09-20", "100", "100", Status.SUPERSEDED),
                fact(9, 107, "T-1", "ORG-1", "2026-08-31", "0.5", "0.50", Status.CONFIRMED));
    }
    private static Map<String, Object> wire() {
        return new LinkedHashMap<>(Map.ofEntries(Map.entry("id", "1"), Map.entry("courseId", "101"),
                Map.entry("recordCode", "REC-1"), Map.entry("teacherCode", "T-1"), Map.entry("orgCode", "ORG-1"),
                Map.entry("courseCode", "COURSE-101"), Map.entry("dates", Map.of("TEACHING", "2026-09-01")),
                Map.entry("hours", Map.of("ACTUAL", "0.1")), Map.entry("fee", "0.10"), Map.entry("currency", "CNY"),
                Map.entry("feeVersion", "DEMO-FEE-OLD"), Map.entry("status", "CONFIRMED")));
    }
    private static Fact modified(String key, Object value) { Map<String, Object> m = wire(); m.put(key, value); return factFromMap(m); }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void eq(BigDecimal value, String expected, String label) { check(value.compareTo(new BigDecimal(expected)) == 0, label); }
    private static void fails(Class<? extends RuntimeException> type, Runnable action, String label) {
        checks++;
        try { action.run(); } catch (RuntimeException ex) { if (type.isInstance(ex)) return; throw new AssertionError(label, ex); }
        throw new AssertionError(label + " should reject");
    }
    private static void invalid(Runnable action, String label) { fails(IllegalArgumentException.class, action, label); }
    public static void main(String[] args) {
        Report report = build(facts(), rules(), SEPTEMBER, ACCESS);
        if (args.length == 1 && args[0].equals("--demo")) { System.out.println(Json.write(report.toMap())); return; }
        if (args.length == 1 && args[0].equals("--demo-csv")) { System.out.print(exportCsv(report, ExportKind.DETAIL)); return; }
        eq(report.total().hours(), "0.8", "decimal + signed correction");
        eq(report.total().fee(), "0.8", "historical fees");
        check(report.total().records() == 6 && report.total().courses() == 4, "inclusive dates, unique courses, statuses");
        check(report.reconciliation().matches(), "detail and groups reconcile");
        eq(report.hourTotals().get(HourBasis.ACTUAL).hours(), "0.8", "four-category actual total");
        eq(report.hourTotals().get(HourBasis.PAYABLE).hours(), "1.6", "four-category payable total");
        eq(report.hourTotals().get(HourBasis.PLANNED).hours(), "18", "four-category planned total");
        eq(report.hourTotals().get(HourBasis.ESTIMATED).hours(), "24", "four-category estimated total");
        Report partialHours = build(List.of(factFromMap(wire())), rules(), SEPTEMBER, ACCESS);
        check(partialHours.hourTotals().get(HourBasis.PLANNED).hours() == null && partialHours.hourTotals().get(HourBasis.PLANNED).missingRecords() == 1, "unknown unselected hours not zero");
        check(agreedDefaultRules("CNY").dateBasis() == DateBasis.TEACHING && agreedDefaultRules("CNY").hourBasis() == HourBasis.ACTUAL && agreedDefaultRules("CNY").tieRule() == TieRule.COMPETITION, "agreed default rules");
        check(agreedRules(DateBasis.PAYMENT, HourBasis.PAYABLE, RankMetric.FEE, "CNY").version().equals(AGREED_RULE_VERSION), "agreed payment rules");
        invalid(() -> agreedRules(DateBasis.CONFIRMATION, HourBasis.ACTUAL, RankMetric.HOURS, "CNY"), "unapproved confirmation basis");
        check(report.teachers().stream().map(Group::rank).toList().equals(List.of(1, 2, 2, 4)), "competition ranks");
        check(report.teachers().stream().map(Group::code).toList().equals(List.of("T-2", "T-1", "T-3", "T-4")), "stable code tie break");
        Report dense = build(facts(), rules(DateBasis.TEACHING, HourBasis.ACTUAL, RankMetric.HOURS, TieRule.DENSE), SEPTEMBER, ACCESS);
        check(dense.teachers().stream().map(Group::rank).toList().equals(List.of(1, 2, 2, 3)), "dense ranks");
        Report ordinal = build(facts(), rules(DateBasis.TEACHING, HourBasis.ACTUAL, RankMetric.HOURS, TieRule.ORDINAL), SEPTEMBER, ACCESS);
        check(ordinal.teachers().stream().map(Group::rank).toList().equals(List.of(1, 2, 3, 4)), "ordinal ranks");
        List<Fact> reversed = new ArrayList<>(facts()); Collections.reverse(reversed);
        check(Json.write(report.toMap()).equals(Json.write(build(reversed, rules(), SEPTEMBER, ACCESS).toMap())), "snapshot order independence");
        eq(build(facts(), rules(DateBasis.TEACHING, HourBasis.PAYABLE, RankMetric.HOURS, TieRule.COMPETITION), SEPTEMBER, ACCESS).total().hours(), "1.6", "explicit payable hours");
        eq(build(facts(), rules(DateBasis.TEACHING, HourBasis.ESTIMATED, RankMetric.HOURS, TieRule.COMPETITION), SEPTEMBER, ACCESS).total().hours(), "24", "estimated independent");
        eq(build(facts(), rules(DateBasis.TEACHING, HourBasis.PLANNED, RankMetric.HOURS, TieRule.COMPETITION), SEPTEMBER, ACCESS).total().hours(), "18", "planned independent");
        eq(build(facts(), rules(DateBasis.CONFIRMATION, HourBasis.ACTUAL, RankMetric.HOURS, TieRule.COMPETITION), SEPTEMBER, ACCESS).total().hours(), "1.1", "confirmation crosses month");
        eq(build(facts(), rules(DateBasis.PAYMENT, HourBasis.ACTUAL, RankMetric.HOURS, TieRule.COMPETITION), SEPTEMBER, ACCESS).total().hours(), "0.5", "payment crosses month");
        Rules includeCancelled = new Rules("DEMO-CANCEL", DateBasis.TEACHING, HourBasis.ACTUAL, Set.of(Status.CANCELLED), RankMetric.FEE, TieRule.DENSE, "CNY");
        eq(build(facts(), includeCancelled, SEPTEMBER, ACCESS).total().fee(), "100", "explicit cancellation treatment");
        List<Fact> unusual = List.of(fact(10, 110, "T-1", "ORG-1", "2026-09-01", "1", "999.99", Status.CONFIRMED),
                fact(11, 111, "T-2", "ORG-1", "2026-09-01", "2", "0.01", Status.CONFIRMED));
        Report feeRank = build(unusual, rules(DateBasis.TEACHING, HourBasis.ACTUAL, RankMetric.FEE, TieRule.COMPETITION), SEPTEMBER, ACCESS);
        eq(feeRank.total().fee(), "1000.00", "never reprice historical fee");
        check(feeRank.teachers().get(0).code().equals("T-1"), "rank by fee not hours");
        check(compareExpected(report, new Total(6, 4, new BigDecimal("0.8"), new BigDecimal("0.8"))).get("matches").equals(true), "expected control matches");
        Map<String, Object> mismatch = compareExpected(report, new Total(5, 4, new BigDecimal("0.8"), new BigDecimal("0.7")));
        check(mismatch.get("matches").equals(false) && mismatch.get("feeDifference").equals("0.1"), "expected difference visible");
        Report empty = build(facts(), rules(), SEPTEMBER, new AccessScope(Set.of(), true, true));
        check(empty.total().records() == 0 && empty.reconciliation().matches(), "empty scope no data");
        Report scoped = build(facts(), rules(), SEPTEMBER, new AccessScope(Set.of("ORG-1"), true, false));
        eq(scoped.total().hours(), "0.5", "scope excludes other org");
        check(!Json.write(scoped.toMap()).contains("ORG-2"), "no unauthorized detail disclosure");
        fails(SecurityException.class, () -> exportCsv(scoped, ExportKind.DETAIL), "export permission");
        fails(SecurityException.class, () -> exportManifest(scoped, ExportKind.DETAIL), "manifest export permission");
        fails(SecurityException.class, () -> build(facts(), rules(), SEPTEMBER, new AccessScope(Set.of("ORG-1"), false, true)), "view permission");
        fails(SecurityException.class, () -> build(facts(), rules(), new Filter(SEPTEMBER.start(), SEPTEMBER.end(), Set.of("ORG-2")), new AccessScope(Set.of("ORG-1"), true, true)), "requested org outside permission");
        invalid(() -> build(List.of(facts().get(0), facts().get(0)), rules(), SEPTEMBER, ACCESS), "duplicate id");
        invalid(() -> build(List.of(facts().get(0), modified("id", "2")), rules(), SEPTEMBER, ACCESS), "duplicate record code");
        Map<String, Object> conflict = wire(); conflict.put("id", "2"); conflict.put("recordCode", "REC-2"); conflict.put("courseCode", "COURSE-OTHER");
        invalid(() -> build(List.of(facts().get(0), factFromMap(conflict)), rules(), SEPTEMBER, ACCESS), "course mapping conflict");
        invalid(() -> build(List.of(modified("dates", Map.of())), rules(), SEPTEMBER, ACCESS), "missing chosen date not silently skipped");
        invalid(() -> build(List.of(modified("hours", Map.of("PAYABLE", "1"))), rules(), SEPTEMBER, ACCESS), "missing hours not zero");
        invalid(() -> build(List.of(modified("fee", null)), rules(), SEPTEMBER, ACCESS), "missing fee not zero");
        invalid(() -> build(List.of(modified("feeVersion", null)), rules(), SEPTEMBER, ACCESS), "missing fee version");
        invalid(() -> build(List.of(modified("currency", "USD")), rules(), SEPTEMBER, ACCESS), "mixed currency");
        Map<String, Object> outside = wire(); outside.put("dates", Map.of("TEACHING", "2026-08-01")); outside.put("fee", null);
        check(build(List.of(factFromMap(outside)), rules(), SEPTEMBER, ACCESS).total().records() == 0, "out of period missing fee harmless");
        invalid(() -> modified("fee", 0.1d), "reject numeric money wire");
        invalid(() -> modified("fee", "0.000000001"), "precision beyond contract rejected, not rounded");
        invalid(() -> modified("fee", "1e3"), "no exponent input");
        invalid(() -> modified("recordCode", "=HYPERLINK(test)"), "formula code rejected");
        invalid(() -> modified("teacherCode", "张三"), "name not accepted as code");
        invalid(() -> modified("id", new BigDecimal("1.99999999999999999")), "no decimal id rounding");
        invalid(() -> modified("id", 9007199254740992d), "unsafe numeric id");
        invalid(() -> modified("id", "9223372036854775808"), "id overflow");
        check(modified("id", "9223372036854775807").id() == Long.MAX_VALUE, "full long id string preserves exact value");
        invalid(() -> new Rules("RULE-1", null, HourBasis.ACTUAL, Set.of(Status.CONFIRMED), RankMetric.HOURS, TieRule.COMPETITION, "CNY"), "missing explicit date basis");
        invalid(() -> new Rules("RULE-1", DateBasis.TEACHING, HourBasis.ACTUAL, Set.of(), RankMetric.HOURS, TieRule.COMPETITION, "CNY"), "empty status rule");
        invalid(() -> new Filter(SEPTEMBER.end(), SEPTEMBER.start(), Set.of()), "reversed range");
        invalid(() -> rulesFromMap(Map.of()), "rules from empty map cannot default");
        check(rulesFromMap(cast(report.toMap().get("rules"))).equals(rules()), "rule JSON roundtrip");
        check(filterFromMap(cast(report.toMap().get("filter"))).equals(SEPTEMBER), "filter JSON roundtrip");
        check(factFromMap(Json.parseMap(Json.write(wire()))).fee().compareTo(new BigDecimal("0.1")) == 0, "input JSON precision");
        for (ExportKind kind : ExportKind.values()) {
            String csv = exportCsv(report, kind);
            check(csv.startsWith("\uFEFF\"schema_version\"") && csv.contains("\r\n"), kind + " CSV format");
            check(csv.contains("\"DEMO-M06-1\"") && !csv.contains("courseId") && !csv.contains("teacher_name"), kind + " encoded whitelist");
            check(csv.equals(exportCsv(build(reversed, rules(), SEPTEMBER, ACCESS), kind)), kind + " deterministic CSV");
        }
        String detailCsv = exportCsv(report, ExportKind.DETAIL);
        check(detailCsv.lines().count() == 7 && detailCsv.contains("\"-0.1\""), "export has same six signed detail facts");
        check(exportCsv(empty, ExportKind.DETAIL).lines().count() == 1, "empty export header only");
        Map<String, Object> manifest = exportManifest(empty, ExportKind.DETAIL);
        check(manifest.get("rowCount").equals(0) && cast(manifest.get("rules")).get("version").equals("DEMO-M06-1"), "empty export sidecar preserves rules");
        check(exportManifest(build(facts(), rules(), SEPTEMBER, new AccessScope(Set.of("ORG-1"), true, true)), ExportKind.DETAIL).get("effectiveOrgCodes").equals(List.of("ORG-1")), "manifest records effective scope");
        fails(UnsupportedOperationException.class, () -> report.details().clear(), "immutable snapshot");
        System.out.println("M06 reports: " + checks + " checks passed (synthetic only)");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
}
