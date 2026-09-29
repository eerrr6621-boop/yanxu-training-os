package com.training;

import static com.training.DeliverySettlement.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Synthetic pure tests: no HTTP, database, persisted authorization or actual payments. */
public final class M05ExecutionTest {
    private static int passed;
    private static void check(boolean result, String label) { if (!result) throw new AssertionError(label); passed++; }
    private static void eq(Object actual, Object expected, String label) { check(Objects.equals(actual, expected), label + ": " + actual); }
    private static void fails(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException expected) { passed++; return; }
        throw new AssertionError(label + " unexpectedly accepted");
    }
    private static void immutable(Runnable action, String label) {
        try { action.run(); } catch (UnsupportedOperationException expected) { passed++; return; }
        throw new AssertionError(label + " unexpectedly mutable");
    }
    private static Map<String, Object> with(Map<String, Object> source, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(source); result.put(key, value); return result;
    }
    private static Map<String, Object> claim() {
        return Map.of("grade", "SENIOR", "day_type", "WORKDAY", "research_team", "NO", "appointed_on", "2026-01-01",
                "annual_plan", "YES", "customer_paid", "UNKNOWN", "service_date_applicable", "YES",
                "evidence", Map.of("grade", "GRADE-1", "day_type", "DAY-1", "research_team", "RESEARCH-1",
                        "appointment", "APPOINTMENT-1", "annual_plan", "ANNUAL-1", "applicability", "CASE-1"));
    }
    private static Map<String, Object> settings() {
        return Map.of("version", "SETTING-1", "evidence_code", "MONEY-DECISION-1", "amount_scale", 2,
                "rounding_mode", "HALF_UP", "allow_unpaid_correction", true);
    }
    private static Fact fact(String payable) {
        return new Fact(1, 3, "SESSION-1", "INSTRUCTOR-1", "ORG-1", LocalDate.parse("2026-09-01"),
                new Dimensions("STALE", "STALE", "STALE", "CLASS45"),
                new Hours(new BigDecimal("3"), new BigDecimal("2"), new BigDecimal("1.33"),
                        payable == null ? null : new BigDecimal(payable)),
                new Verification("REVIEWER-1", Instant.parse("2026-09-02T00:00:00Z"), "VERIFIED-1"), DataMode.CONFIGURED);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static DeliverySettlementExecution.Executable build(Map<String, Object> claim, String hours) {
        return DeliverySettlementExecution.build(fact(hours), claim, settings(), "CASE-CONFIG-1", "REVIEW-1");
    }
    public static void main(String[] args) {
        Map<String, Object> empty = DeliverySettlementExecution.parseClaim(Map.of());
        eq(empty.get("grade"), null, "no inferred grade"); eq(empty.get("day_type"), null, "no calendar inferred day");
        eq(empty.get("research_team"), "UNKNOWN", "no inferred R&D membership");
        eq(empty.get("service_date_applicable"), "UNKNOWN", "case applicability never inferred");
        check(DeliverySettlementExecution.eligibility(fact("1.33"), empty).size() >= 6, "all missing gates surfaced");
        eq(DeliverySettlementExecution.eligibility(fact("1.33"), claim()), List.of(), "eligible reviewed facts");
        for (String unsafe : List.of("unit_rate", "amount", "approved", "actor_code", "service_date", "activity"))
            fails(() -> DeliverySettlementExecution.parseClaim(with(claim(), unsafe, true)), "client " + unsafe);
        fails(() -> DeliverySettlementExecution.parseClaim(with(claim(), "research_team", true)), "boolean membership");
        fails(() -> DeliverySettlementExecution.parseClaim(with(claim(), "grade", "senior")), "unknown grade enum");
        fails(() -> DeliverySettlementExecution.parseClaim(with(claim(), "appointed_on", "2026-02-30")), "invalid date");
        fails(() -> DeliverySettlementExecution.parseClaim(with(claim(), "evidence", Map.of("rate", "X"))), "unknown evidence field");
        fails(() -> DeliverySettlementExecution.parseClaim(with(claim(), "evidence", Map.of("grade", "X".repeat(97)))), "oversized evidence");
        fails(() -> DeliverySettlementExecution.parseClaim(with(claim(), "evidence", Map.of("grade", "<html>"))), "evidence must be technical code");
        check(!DeliverySettlementExecution.eligibility(fact("1"), with(claim(), "appointed_on", "2026-09-02")).isEmpty(), "appointment after service rejected");
        check(!DeliverySettlementExecution.eligibility(fact("1"), with(claim(), "evidence", Map.of())).isEmpty(), "known facts need evidence");
        check(!DeliverySettlementExecution.eligibility(fact(null), claim()).isEmpty(), "actual does not replace payable");
        fails(() -> build(with(claim(), "service_date_applicable", "NO"), "1"), "explicit inapplicability");
        fails(() -> build(with(claim(), "service_date_applicable", "UNKNOWN"), "1"), "unknown applicability");
        fails(() -> build(with(claim(), "annual_plan", "NO"), "1"), "scope not payable or known");
        Map<String, Object> noApplicabilityEvidence = new LinkedHashMap<>(map(claim().get("evidence")));
        noApplicabilityEvidence.remove("applicability");
        fails(() -> build(with(claim(), "evidence", noApplicabilityEvidence), "1"), "no per-case applicability evidence");
        Map<String, Object> parsed = DeliverySettlementExecution.parseClaim(claim());
        immutable(() -> parsed.put("grade", "LECTURER"), "immutable normalized claim");
        immutable(() -> map(parsed.get("evidence")).put("grade", "REPLACED"), "immutable evidence");

        DeliverySettlementExecution.parseSettings(settings());
        for (String key : settings().keySet()) {
            Map<String, Object> missing = new LinkedHashMap<>(settings()); missing.remove(key);
            fails(() -> DeliverySettlementExecution.parseSettings(missing), "explicit setting " + key);
        }
        fails(() -> DeliverySettlementExecution.parseSettings(with(settings(), "effective_from", "2026-09-22")), "global date forbidden");
        fails(() -> DeliverySettlementExecution.parseSettings(with(settings(), "amount_scale", 3)), "scale cannot override decision");
        fails(() -> DeliverySettlementExecution.parseSettings(with(settings(), "amount_scale", 2.5)), "fractional scale");
        fails(() -> DeliverySettlementExecution.parseSettings(with(settings(), "amount_scale", "2")), "text scale");
        fails(() -> DeliverySettlementExecution.parseSettings(with(settings(), "rounding_mode", "DOWN")), "rounding cannot override decision");
        fails(() -> DeliverySettlementExecution.parseSettings(with(settings(), "allow_unpaid_correction", "true")), "typed correction toggle");

        String[] grades = {"LECTURER", "SENIOR", "SPECIAL", "DISTINGUISHED"};
        String[] days = {"WORKDAY", "REST_DAY", "STATUTORY_HOLIDAY"};
        String[][] rates = {{"100", "200", "300"}, {"150", "300", "450"}, {"200", "400", "600"}, {"200", "400", "600"}};
        for (int g = 0; g < grades.length; g++) for (int d = 0; d < days.length; d++) {
            var output = build(with(with(claim(), "grade", grades[g]), "day_type", days[d]), "1.33");
            eq(map(output.configuration().get("rate")).get("unit_rate"), rates[g][d], "server selected table rate");
            eq(output.fact().dimensions().gradeCode(), grades[g], "reviewed grade used");
        }
        Map<String, Object> research = with(with(claim(), "research_team", "YES"), "grade", null);
        var researchOutput = build(research, "1.33");
        eq(researchOutput.fact().dimensions().gradeCode(), "LECTURER", "R&D unknown grade uses lecturer");
        eq(map(researchOutput.configuration().get("rate")).get("unit_rate"), "100", "R&D uses lecturer rate");
        Fact original = fact("1.33335");
        var output = DeliverySettlementExecution.build(original, with(claim(), "grade", "LECTURER"), settings(), "C".repeat(60), "ELIGIBILITY-1");
        eq(output.fact().recordId(), original.recordId(), "record id preserved");
        eq(output.fact().revision(), original.revision(), "revision preserved");
        eq(output.fact().serviceDate(), original.serviceDate(), "service day preserved");
        check(output.fact().hours() == original.hours(), "all original hours retained");
        check(output.fact().verification() == original.verification(), "original verification retained");
        eq(original.dimensions().gradeCode(), "STALE", "original fact unchanged");
        Map<String, Object> config = output.configuration(), rule = map(config.get("rule")), rate = map(config.get("rate"));
        eq(config.keySet(), java.util.Set.of("version", "publication_evidence", "eligibility_evidence", "approval_status", "rule", "rate", "correction"), "strict legacy configuration envelope");
        eq(config.get("publication_evidence"), DeliverySettlementApprovedPolicy.ADOPTION_EVIDENCE, "user adoption evidence retained");
        eq(rate.get("evidence"), DeliverySettlementApprovedPolicy.EXECUTION_VERSION, "adopted execution version retained");
        eq(rule.get("effective_from"), "2026-09-01", "earlier individual service approved without adoption-date inference");
        eq(rule.get("effective_until"), "2026-09-02", "scope limited to approved day");
        eq(rule.get("amount_scale"), 2, "money scale explicit"); eq(rule.get("rounding_mode"), "HALF_UP", "money rounding explicit");
        eq(rule.get("minutes_per_class_hour"), 45, "45 minute class hour");
        eq(map(config.get("correction")).get("method"), "REPLACEMENT_DELTA", "explicit correction method");
        RuleVersion rv = new RuleVersion((String) rule.get("version"), (String) rule.get("evidence"), LocalDate.parse((String) rule.get("effective_from")),
                LocalDate.parse((String) rule.get("effective_until")), DATE_BASIS, FORMULA, "CNY", 2, java.math.RoundingMode.HALF_UP,
                ROUNDING_SCOPE, 45, DataMode.CONFIGURED);
        RateVersion rr = new RateVersion((String) rate.get("version"), (String) rate.get("evidence"), rv.versionCode(),
                rv.effectiveFrom(), rv.effectiveUntil(), output.fact().dimensions(), new BigDecimal((String) rate.get("unit_rate")), DataMode.CONFIGURED);
        eq(calculate(output.fact(), rv, rr).amount().toPlainString(), "133.34", "exact amount with per-line half up");
        immutable(() -> config.put("approval_status", "OTHER"), "immutable executable envelope");
        immutable(() -> rate.put("unit_rate", "999"), "immutable selected rate");
        var noCorrection = DeliverySettlementExecution.build(original, claim(), with(settings(), "allow_unpaid_correction", false), "NO-CORRECTION", "REVIEW-1");
        eq(noCorrection.configuration().get("correction"), null, "explicit correction disabled");
        fails(() -> DeliverySettlementExecution.build(original, claim(), settings(), "C".repeat(61), "REVIEW-1"), "case version length bounded");
        fails(() -> DeliverySettlementExecution.build(original, claim(), settings(), DeliverySettlementPolicy.CANDIDATE_VERSION, "REVIEW-1"), "candidate version cannot identify formal config");
        Fact unverified = new Fact(original.recordId(), original.revision(), original.sessionCode(), original.instructorCode(), original.organizationCode(), original.serviceDate(), original.dimensions(), original.hours(), null, original.dataMode());
        fails(() -> DeliverySettlementExecution.build(unverified, claim(), settings(), "CASE-1", "REVIEW-1"), "verification required");
        Fact dateOverflow = new Fact(original.recordId(), original.revision(), original.sessionCode(), original.instructorCode(), original.organizationCode(), LocalDate.parse("9999-12-31"), original.dimensions(), original.hours(), original.verification(), original.dataMode());
        fails(() -> DeliverySettlementExecution.build(dateOverflow, claim(), settings(), "CASE-1", "REVIEW-1"), "case end date remains old-parser compatible");
        System.out.println("M05 execution pure tests passed: " + passed);
    }
}
