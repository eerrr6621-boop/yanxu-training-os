package com.training;

import static com.training.DeliverySettlement.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** All data are synthetic. No database or network. */
public final class M05DeliverySettlementTest {
    private static int passed;
    private static final LocalDate START = LocalDate.parse("2099-01-01");
    private static final LocalDate END = LocalDate.parse("2100-01-01");
    private static final Instant CHECKED = Instant.parse("2099-01-02T00:00:00Z");
    private static final Instant CONFIRMED = CHECKED.plusSeconds(60);
    private static final Dimensions DIMS = new Dimensions("DEMO-G1", "DEMO-DAY", "DEMO-ONSITE", "DEMO-HOUR");
    private static final Access ALL = new Access("DEMO-OP1", Set.of(Permission.CONFIRM, Permission.EXPORT, Permission.CORRECT), Set.of("DEMO-ORG1"));
    private static BigDecimal d(String n) { return decimalInput(n); }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); passed++; }
    private static void eq(Object actual, Object expected, String label) { check(java.util.Objects.equals(actual, expected), label + ": " + actual); }
    private static void failure(Class<? extends Throwable> type, Runnable action, String label) {
        try { action.run(); } catch (Throwable e) { check(type.isInstance(e), label + " wrong exception " + e); return; }
        throw new AssertionError(label + " accepted");
    }
    private static Hours hours(String payable) { return new Hours(d("2"), d("1.75"), d("1.5"), d(payable)); }
    private static Fact fact(Hours hours, long revision, Verification verified, LocalDate date, Dimensions dims) {
        return new Fact(701, revision, "DEMO-SESSION1", "DEMO-INSTRUCTOR1", "DEMO-ORG1", date, dims, hours, verified, DataMode.SYNTHETIC_DEMO);
    }
    private static Fact fact() { return fact(hours("1.25"), 1, new Verification("DEMO-CHECK1", CHECKED, "DEMO-EVIDENCE1"), START, DIMS); }
    private static RuleVersion rule(String version, RoundingMode rounding) {
        return new RuleVersion(version, "DEMO-RULE-SOURCE", START, END, DATE_BASIS, FORMULA, "CNY", 2, rounding, ROUNDING_SCOPE, 45, DataMode.SYNTHETIC_DEMO);
    }
    private static RateVersion rate(String version, String rule, String amount) {
        return new RateVersion(version, "DEMO-RATE-SOURCE", rule, START, END, DIMS, d(amount), DataMode.SYNTHETIC_DEMO);
    }
    private static Calculation calc(Fact f) { return calculate(f, rule("DEMO-R1", RoundingMode.HALF_UP), rate("DEMO-T1", "DEMO-R1", "80.004")); }
    private static CorrectionPolicy policy() { return new CorrectionPolicy("DEMO-CORR1", "DEMO-CORR-SOURCE", START, END, DATE_BASIS, "REPLACEMENT_DELTA", DataMode.SYNTHETIC_DEMO); }
    public static void main(String[] args) {
        Fact f = fact(); Calculation c = calc(f);
        eq(c.status(), Status.READY, "complete calculation");
        eq(c.unroundedAmount().toPlainString(), "100.00500", "exact product");
        eq(c.amount().toPlainString(), "100.01", "half up rounding");
        eq(f.hours().estimated(), d("2"), "estimated independent");
        eq(f.hours().planned(), d("1.75"), "planned independent");
        eq(f.hours().actual(), d("1.5"), "actual independent");
        eq(f.hours().payable(), d("1.25"), "payable independent");
        Calculation even = calculate(f, rule("DEMO-R2", RoundingMode.HALF_EVEN), rate("DEMO-T2", "DEMO-R2", "80.004"));
        eq(even.amount().toPlainString(), "100.00", "half even explicit");
        Calculation exact = calculate(f, rule("DEMO-R3", RoundingMode.UNNECESSARY), rate("DEMO-T3", "DEMO-R3", "80.004"));
        eq(exact.status(), Status.INCOMPLETE, "unnecessary rejects inexact"); eq(exact.amount(), null, "no fabricated rounded amount");
        eq(calculate(f, rule("DEMO-R3", RoundingMode.UNNECESSARY), rate("DEMO-T3", "DEMO-R3", "80")).amount(), d("100.00"), "unnecessary accepts exact");
        Fact small = fact(new Hours(null, null, d("0.1"), d("0.1")), 1, f.verification(), START, DIMS);
        eq(calculate(small, rule("DEMO-R1", RoundingMode.HALF_UP), rate("DEMO-T1", "DEMO-R1", "0.2")).amount(), d("0.02"), "decimal 0.1 times 0.2");
        Fact large = fact(new Hours(null, null, d("9007199254740993"), d("9007199254740993")), 1, f.verification(), START, DIMS);
        eq(calculate(large, rule("DEMO-R1", RoundingMode.HALF_UP), rate("DEMO-T1", "DEMO-R1", "1")).amount().toPlainString(), "9007199254740993.00", "beyond double safe integer");
        eq(calc(fact(hours(null), 1, f.verification(), START, DIMS)).status(), Status.INCOMPLETE, "no actual to payable fallback");
        eq(calc(fact(hours(null), 1, f.verification(), START, DIMS)).amount(), null, "unknown never zero");
        eq(calc(fact(new Hours(null, null, null, d("1")), 1, f.verification(), START, DIMS)).status(), Status.INCOMPLETE, "actual required for verification");
        eq(calc(fact(hours("0"), 1, f.verification(), START, DIMS)).amount(), d("0.00"), "explicit zero payable allowed");
        eq(calculate(f, rule("DEMO-R1", RoundingMode.HALF_UP), rate("DEMO-T1", "DEMO-R1", "0")).amount(), d("0.00"), "explicit configured zero rate allowed");
        eq(calculate(f, null, null).status(), Status.NOT_CONFIGURED, "missing configuration");
        eq(calculate(f, null, null).amount(), null, "missing not zero");
        check(calculate(f, rule("DEMO-R1", null), rate("DEMO-T1", "DEMO-R1", "80")).issues().contains("ROUNDING_NOT_CONFIGURED"), "rounding required");
        RuleVersion noUnit = new RuleVersion("DEMO-R1", "DEMO-RULE-SOURCE", START, END, DATE_BASIS, FORMULA, "CNY", 2, RoundingMode.HALF_UP, ROUNDING_SCOPE, null, DataMode.SYNTHETIC_DEMO);
        check(calculate(f, noUnit, rate("DEMO-T1", "DEMO-R1", "80")).issues().contains("CLASS_HOUR_DEFINITION_NOT_CONFIGURED"), "class hour definition explicit");
        check(calculate(f, rule("DEMO-R1", RoundingMode.HALF_UP), rate("DEMO-T1", "DEMO-R1", null)).issues().contains("UNIT_RATE_NOT_CONFIGURED"), "rate required");
        check(calculate(f, rule("DEMO-R1", RoundingMode.HALF_UP), rate("DEMO-T1", "DEMO-R2", "80")).issues().contains("RATE_RULE_VERSION_MISMATCH"), "rate rule link");
        eq(calc(fact(hours("1.25"), 1, f.verification(), START.minusDays(1), DIMS)).status(), Status.NOT_CONFIGURED, "before effective date");
        eq(calc(fact(hours("1.25"), 1, f.verification(), END, DIMS)).status(), Status.NOT_CONFIGURED, "exclusive end date");
        eq(calc(fact(hours("1.25"), 1, f.verification(), END.minusDays(1), DIMS)).status(), Status.READY, "last effective day");
        eq(calc(fact(hours("1.25"), 1, f.verification(), null, DIMS)).status(), Status.INCOMPLETE, "unknown service date");
        Dimensions other = new Dimensions("DEMO-G2", "DEMO-DAY", "DEMO-ONSITE", "DEMO-HOUR");
        check(calc(fact(hours("1.25"), 1, f.verification(), START, other)).issues().contains("RATE_DIMENSIONS_MISMATCH"), "no grade fallback");
        check(calc(fact(hours("1.25"), 1, f.verification(), START, new Dimensions("DEMO-G1", "DEMO-NIGHT", "DEMO-ONSITE", "DEMO-HOUR"))).issues().contains("RATE_DIMENSIONS_MISMATCH"), "no time fallback");
        check(calc(fact(hours("1.25"), 1, f.verification(), START, new Dimensions("DEMO-G1", "DEMO-DAY", "DEMO-ONLINE", "DEMO-HOUR"))).issues().contains("RATE_DIMENSIONS_MISMATCH"), "no form fallback");
        check(calc(fact(hours("1.25"), 1, f.verification(), START, new Dimensions("DEMO-G1", "DEMO-DAY", "DEMO-ONSITE", "DEMO-MINUTE"))).issues().contains("RATE_DIMENSIONS_MISMATCH"), "no unit conversion guessed");
        failure(IllegalArgumentException.class, () -> decimalInput(0.1d), "reject floating JSON");
        failure(IllegalArgumentException.class, () -> decimalInput(1L), "reject numeric JSON");
        failure(IllegalArgumentException.class, () -> decimalInput("-1"), "negative amount");
        failure(IllegalArgumentException.class, () -> decimalInput("1e2"), "exponent notation");
        failure(IllegalArgumentException.class, () -> decimalInput("NaN"), "nonfinite");
        failure(IllegalArgumentException.class, () -> decimalInput("0.123456789"), "scale guard never rounds input");
        failure(IllegalArgumentException.class, () -> decimalInput("9".repeat(1000)), "input length bound");
        eq(decimalInput(new BigDecimal("0.00000001")), d("0.00000001"), "typed tiny decimal");
        eq(decimalInput(new BigDecimal("0.00000000")), d("0.00000000"), "typed scaled zero");
        failure(IllegalArgumentException.class, () -> new Hours(null, null, null, new BigDecimal("-1")), "typed negative hours");
        failure(IllegalArgumentException.class, () -> new Dimensions("=evil", "DAY", "ONLINE", "HOUR"), "formula-like codes rejected");
        failure(IllegalArgumentException.class, () -> new Dimensions("姓名", "DAY", "ONLINE", "HOUR"), "name not accepted as code");
        failure(IllegalArgumentException.class, () -> new RuleVersion("R1", "E1", END, START, DATE_BASIS, FORMULA, "CNY", 2, RoundingMode.HALF_UP, ROUNDING_SCOPE, 45, DataMode.SYNTHETIC_DEMO), "bad dates");
        Fact live = new Fact(701, 1, f.sessionCode(), f.instructorCode(), f.organizationCode(), START, DIMS, f.hours(), f.verification(), DataMode.CONFIGURED);
        eq(calc(live).status(), Status.NOT_CONFIGURED, "synthetic rate cannot configure live");
        Fact unchecked = fact(f.hours(), 1, null, START, DIMS);
        check(calc(unchecked).issues().contains("TEACHING_NOT_VERIFIED"), "pending verification explicit");
        failure(IllegalStateException.class, () -> confirm(calc(unchecked), unchecked, "DEMO-S0", CONFIRMED, ALL), "cannot confirm unverified");
        Access outsider = new Access("DEMO-OTHER", ALL.grants(), Set.of("DEMO-ORG2"));
        Access noGrants = new Access("DEMO-OP1", Set.of(), ALL.organizationCodes());
        failure(SecurityException.class, () -> confirm(c, f, "DEMO-S1", CONFIRMED, outsider), "cross organization confirmation");
        failure(SecurityException.class, () -> confirm(c, f, "DEMO-S1", CONFIRMED, noGrants), "missing confirmation grant");
        failure(IllegalStateException.class, () -> confirm(c, fact(hours("2"), 2, f.verification(), START, DIMS), "DEMO-S1", CONFIRMED, ALL), "stale revision");
        failure(IllegalArgumentException.class, () -> confirm(c, f, "DEMO-S1", CHECKED.minusSeconds(1), ALL), "chronology");
        Confirmed first = confirm(c, f, "DEMO-S1", CONFIRMED, ALL);
        calculate(f, rule("DEMO-R2", RoundingMode.HALF_EVEN), rate("DEMO-T2", "DEMO-R2", "999"));
        eq(first.calculation().amount(), d("100.01"), "new rules never recompute snapshot");
        eq(first.calculation().rule().versionCode(), "DEMO-R1", "original rule retained");
        eq(first.calculation().rate().unitRate(), d("80.004"), "original rate retained");
        eq(first.calculation().engineVersion(), ENGINE_VERSION, "engine version frozen");
        eq(first.calculation().rule().minutesPerClassHour(), 45, "class hour definition frozen");
        Fact revisedFact = fact(hours("1"), 2, new Verification("DEMO-CHECK2", CONFIRMED.plusSeconds(1), "DEMO-EVIDENCE2"), START, DIMS);
        Calculation revised = calc(revisedFact); Instant nextTime = CONFIRMED.plusSeconds(60);
        failure(IllegalStateException.class, () -> correct(first, revised, revisedFact, "DEMO-S2", "DEMO-REASON1", null, nextTime, ALL), "correction rule required");
        failure(SecurityException.class, () -> correct(first, revised, revisedFact, "DEMO-S2", "DEMO-REASON1", policy(), nextTime, outsider), "correction scope enforced");
        failure(IllegalArgumentException.class, () -> correct(first, c, f, "DEMO-S2", "DEMO-REASON1", policy(), nextTime, ALL), "revision must advance");
        failure(IllegalArgumentException.class, () -> correct(first, revised, revisedFact, "DEMO-S1", "DEMO-REASON1", policy(), nextTime, ALL), "duplicate snapshot code");
        Confirmed second = correct(first, revised, revisedFact, "DEMO-S2", "DEMO-REASON1", policy(), nextTime, ALL);
        eq(second.settlementAmount().toPlainString(), "-20.01", "signed correction delta");
        eq(first.calculation().amount().toPlainString(), "100.01", "correction does not alter original");
        eq(second.previous(), first, "correction trace");
        eq(first.settlementAmount().add(second.settlementAmount()), second.calculation().amount(), "no double counting");
        eq(reportingContribution(first).get("status"), "CONFIRMED", "M06 base remains posted");
        eq(reportingContribution(second).get("status"), "ADJUSTMENT", "M06 signed adjustment");
        eq(reportingContribution(second).get("fee"), "-20.01", "M06 fee uses delta");
        @SuppressWarnings("unchecked") java.util.Map<String,Object> deltaHours = (java.util.Map<String,Object>) reportingContribution(second).get("hours");
        eq(deltaHours.get("ESTIMATED"), "0", "M06 estimated unchanged delta zero");
        eq(deltaHours.get("ACTUAL"), "0.0", "M06 actual unchanged delta zero");
        eq(deltaHours.get("PAYABLE"), "-0.25", "M06 payable independent delta");
        Fact movedFact = fact(hours("1"), 3, revisedFact.verification(), START.plusDays(1), DIMS);
        Confirmed moved = correct(second, calc(movedFact), movedFact, "DEMO-MOVED", "DEMO-REASON3", policy(), nextTime, ALL);
        failure(IllegalStateException.class, () -> reportingContribution(moved), "date move never silently misbuckets stats");
        String csv = encodedCsv(List.of(first, second), ALL, DataMode.SYNTHETIC_DEMO);
        check(csv.contains("\"CORRECTION_DELTA\"") && csv.contains("\"-20.01\""), "delta export");
        check(csv.contains("DEMO-R1") && csv.contains("HALF_UP") && csv.contains("100.00500"), "audit basis exported");
        check(csv.startsWith("\"data_mode\"") && csv.contains("SYNTHETIC_DEMO"), "demo marker in export");
        check(csv.contains("\"minutes_per_class_hour\"") && csv.contains("\"45\""), "class hour definition exported");
        eq(csv.split("\r\n").length, 3, "header plus two rows");
        check(!csv.contains("name") && !csv.contains("姓名") && !csv.contains("phone"), "encoded schema only");
        failure(SecurityException.class, () -> encodedCsv(List.of(first), outsider, DataMode.SYNTHETIC_DEMO), "cross organization export");
        failure(SecurityException.class, () -> encodedCsv(List.of(), noGrants, DataMode.SYNTHETIC_DEMO), "empty export permission");
        failure(IllegalArgumentException.class, () -> encodedCsv(List.of(first), ALL, DataMode.CONFIGURED), "cannot mislabel demo export");
        failure(IllegalArgumentException.class, () -> encodedCsv(List.of(first, first), ALL, DataMode.SYNTHETIC_DEMO), "duplicate export rows");
        Confirmed duplicateOriginal = confirm(revised, revisedFact, "DEMO-S3", nextTime, ALL);
        failure(IllegalArgumentException.class, () -> encodedCsv(List.of(first, duplicateOriginal), ALL, DataMode.SYNTHETIC_DEMO), "duplicate originals same record");
        Fact thirdFact = fact(hours("0.5"), 3, revisedFact.verification(), START, DIMS);
        Confirmed fork = correct(first, calc(thirdFact), thirdFact, "DEMO-S4", "DEMO-REASON2", policy(), nextTime, ALL);
        failure(IllegalArgumentException.class, () -> encodedCsv(List.of(second, fork), ALL, DataMode.SYNTHETIC_DEMO), "forked correction export");
        Fact fourthFact = fact(hours("0.4"), 4, revisedFact.verification(), START, DIMS);
        Fact fifthFact = fact(hours("0.3"), 5, revisedFact.verification(), START, DIMS);
        Confirmed branchA = correct(second, calc(fourthFact), fourthFact, "DEMO-S5", "DEMO-REASON2", policy(), nextTime, ALL);
        Confirmed branchB = correct(fork, calc(fifthFact), fifthFact, "DEMO-S6", "DEMO-REASON2", policy(), nextTime, ALL);
        failure(IllegalArgumentException.class, () -> encodedCsv(List.of(branchA, branchB), ALL, DataMode.SYNTHETIC_DEMO), "hidden ancestor fork");
        Confirmed anotherRootChild = correct(duplicateOriginal, calc(thirdFact), thirdFact, "DEMO-S7", "DEMO-REASON2", policy(), nextTime, ALL);
        failure(IllegalArgumentException.class, () -> encodedCsv(List.of(first, anotherRootChild), ALL, DataMode.SYNTHETIC_DEMO), "hidden duplicate original");
        String json = Json.write(view(c));
        check(json.contains("\"amount\":\"100.01\"") && json.contains("\"payable\":\"1.25\""), "decimal strings in JSON");
        check(Json.write(view(calculate(f, null, null))).contains("\"amount\":null"), "missing JSON remains null");
        System.out.println("M05DeliverySettlementTest: " + passed + " assertions passed (synthetic data only)");
    }
}
