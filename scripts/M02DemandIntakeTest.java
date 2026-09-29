package com.training;

import java.util.*;

public final class M02DemandIntakeTest {
    private static int assertions;
    public static void main(String[] args) {
        Map<String, Object> draft = new LinkedHashMap<>();
        ok(DemandIntake.validateDraft(draft, "DEMO-FILLER").valid(), "empty draft saves");
        bad(DemandIntake.validateForSubmission(draft, "DEMO-FILLER"), "title", "required");
        bad(DemandIntake.validateDraft(draft, ""), "filler_code", "session_required");
        Map<String, Object> direct = complete("direct");
        DemandIntake.Validation validated = DemandIntake.validateForSubmission(direct, "DEMO-FILLER");
        ok(validated.valid(), "complete direct without bid valid");
        ok(check(direct, "customer_contact_code", "").valid(), "customer contact may be completed later");
        Map<String, Object> noCustomer = new LinkedHashMap<>(direct);
        noCustomer.remove("customer_contact_code");
        ok(DemandIntake.validateForSubmission(noCustomer, "DEMO-FILLER").valid(), "missing optional customer contact does not block submission");
        bad(check(direct, "internal_contact_code", ""), "internal_contact_code", "required");
        eq(DemandIntake.initialDraft().get("business_path"), "direct", "default direct intake");
        eq(DemandIntake.initialDraft().get("period_text"), "待协调", "unknown period is explicit");
        ok(DemandIntake.validateDraft(DemandIntake.initialDraft(), "DEMO-FILLER").valid(), "initial draft is saveable");
        ok(!DemandIntake.validateForSubmission(DemandIntake.initialDraft(), "DEMO-FILLER").valid(), "defaults cannot bypass submission requirements");
        ok(check(direct, "category_text", "自定义分类").valid(), "suggestions do not constrain business taxonomy");
        eq(validated.values().get("filler_code"), "DEMO-FILLER", "filler from session");
        eq(validated.values().get("internal_contact_code"), "DEMO-INTERNAL", "separate internal contact");
        eq(validated.values().get("customer_contact_code"), "DEMO-CUSTOMER", "separate customer contact");
        ok(!validated.values().containsKey("demand_code"), "no invented formal code");
        bad(check(direct, "filler_code", "SPOOF"), "filler_code", "unsupported_field");
        bad(check(direct, "status", "ready"), "status", "unsupported_field");
        bad(check(direct, "hours", "2"), "hours", "unsupported_field");
        bad(check(direct, "duration_minutes", 90.0), "duration_minutes", "invalid_type");
        for (String bad : List.of("NaN", "Infinity", "1e3", "-1", ".5", "5.", "+2", "1,000", "9".repeat(121)))
            bad(check(direct, "duration_minutes", bad), "duration_minutes", "invalid_number");
        bad(check(direct, "duration_minutes", "0"), "duration_minutes", "out_of_range");
        bad(check(direct, "participant_count", "1.5"), "participant_count", "invalid_number");
        bad(check(direct, "participant_count", "0"), "participant_count", "out_of_range");
        ok(check(direct, "budget_amount", "0").valid(), "zero budget allowed");
        bad(check(direct, "budget_amount", "-0.01"), "budget_amount", "invalid_number");
        ok(check(direct, "duration_minutes", "000.4500").valid(), "original decimal preserved");
        eq(check(direct, "duration_minutes", "000.4500").values().get("duration_minutes"), "000.4500", "no normalization loss");
        eq(check(direct, "duration_minutes", " 000.4500 ").values().get("duration_minutes"), "000.4500", "trim only surrounding whitespace");
        ok(check(direct, "participant_count", "9007199254740993").valid(), "no JS double integer coercion");
        bad(check(direct, "expected_start_date", "2026-02-30"), "expected_start_date", "invalid_date");
        bad(check(direct, "expected_start_date", "2026-9-1"), "expected_start_date", "invalid_date");
        bad(check(direct, "expected_start_date", "0000-01-01"), "expected_start_date", "invalid_date");
        bad(check(direct, "expected_end_date", "2026-01-01"), "expected_end_date", "date_order");
        bad(check(direct, "business_path", "mystery"), "business_path", "invalid_choice");
        bad(check(direct, "external_approval_ref", "DEMO-REF"), "external_approval_ref", "path_conflict");
        Map<String, Object> bid = complete("bid");
        bad(DemandIntake.validateForSubmission(bid, "DEMO-FILLER"), "external_approval_ref", "required");
        bid.put("external_approval_ref", "DEMO-REF");
        ok(DemandIntake.validateForSubmission(bid, "DEMO-FILLER").valid(), "bid with source reference");
        DemandIntake.CourseUnits exact = DemandIntake.courseUnits("67.50");
        eq(exact.numerator(), "3", "fraction numerator"); eq(exact.denominator(), "2", "fraction denominator");
        eq(exact.exactDecimal(), "1.5", "terminating exact units"); eq(exact.originalMinutes(), "67.50", "raw minutes");
        exact = DemandIntake.courseUnits("50");
        eq(exact.numerator(), "10", "repeating numerator"); eq(exact.denominator(), "9", "repeating denominator");
        eq(exact.exactDecimal(), null, "internal exact decimal remains null for repeating result");
        eq(DemandIntake.courseUnits("0.000000000000000000000000000045").exactDecimal(), "0.000000000000000000000000000001", "fine precision exact");
        eq(DemandIntake.courseUnits("9007199254740993").numerator(), "3002399751580331", "large exact input");
        DeliverySettlementHours.Rule basicRule = DeliverySettlementHours.currentUserRule();
        eq(DemandIntake.MINUTES_PER_UNIT, basicRule.minutesPerClassHour(), "M05 minutes definition parity");
        eq(DemandIntake.BASIC_HOURS_SCALE, basicRule.scale(), "M05 scale parity");
        eq(DemandIntake.BASIC_HOURS_RULE_VERSION, basicRule.versionCode(), "M05 rule version parity");
        eq(DemandIntake.BASIC_HOURS_EVIDENCE, basicRule.evidenceCode(), "M05 user evidence parity");
        eq(DemandIntake.BASIC_HOURS_ROUNDING_DECISION, basicRule.roundingDecisionCode(), "M05 implementation choice parity");
        String[][] basicCases = {
            {"60", "1.33"}, {"50", "1.11"}, {"45", "1.00"}, {"090.00", "2.00"}, {"67.50", "1.50"},
            {"0.001", "0.00"}, {"0.22499999", "0.00"}, {"0.225", "0.01"}, {"0.22500001", "0.01"},
            {"44.77499999", "0.99"}, {"44.775", "1.00"}, {"45.22499999", "1.00"}, {"45.225", "1.01"},
            {"67.275", "1.50"}, {"9007199254740993", "200159983438688.73"}
        };
        for (String[] pair : basicCases) {
            DemandIntake.CourseUnits converted = DemandIntake.courseUnits(pair[0]);
            DeliverySettlementHours.Conversion reference = DeliverySettlementHours.convert(pair[0], basicRule);
            eq(reference.status(), DeliverySettlementHours.Status.READY, "M05 reference is ready: " + pair[0]);
            eq(converted.basicClassHours(), pair[1], "expected two-decimal result: " + pair[0]);
            eq(converted.basicClassHours(), reference.classHours().toPlainString(), "M05 arithmetic parity: " + pair[0]);
            eq(converted.originalMinutes(), pair[0], "original minute text retained: " + pair[0]);
            eq(converted.toMap().get("class_hour_rounding"), basicRule.roundingMode().name(), "HALF_UP metadata parity");
        }
        eq(DemandIntake.courseUnits("0.000000000000000000000000000045").basicClassHours(), "0.00", "existing M02 high-precision input range preserved");
        Map<String, Object> basicView = DemandIntake.courseUnits("60").toMap();
        eq(basicView.get("class_hours"), "1.33", "two decimal business display in map");
        eq(basicView.get("rounding_policy"), "HALF_UP", "no obsolete unconfirmed precision flag");
        ok(!basicView.containsKey("payable_hours") && !basicView.containsKey("amount"), "conversion does not create pay facts");
        ok(validated.notices().stream().anyMatch(n -> n.contains("基本课时不等于计酬课时")), "basic and payable hours distinguished");
        eq(DemandIntake.route("direct"), List.of("内部填报", "分公司负责人先审", "BP后审", "培训团队承接"), "approval order");
        ok(DemandIntake.route("bid").get(1).contains("原办公签报"), "external bid process retained");
        DemandIntake.Access denied = new DemandIntake.Access("DEMO-ACTOR", Set.of());
        DemandIntake.Access allowed = new DemandIntake.Access("DEMO-ACTOR", Set.of(DemandIntake.Capability.REGISTER_BID_RESULT, DemandIntake.Capability.ACCEPT_TEAM));
        DemandIntake.Readiness directPending = facts("direct", false, false, "", false);
        DemandIntake.Readiness directReady = facts("direct", false, true, "", false);
        DemandIntake.Readiness bidPending = facts("bid", false, false, "", false);
        DemandIntake.Readiness bidWon = facts("bid", false, false, "won", false);
        DemandIntake.Readiness bidLost = facts("bid", false, false, "lost", false);
        Map<String, Object> result = Map.of("result", "won", "external_approval_ref", "DEMO-REF", "result_date", "2026-09-21");
        bad(DemandIntake.validateBidResult(bidPending, result, denied), "authorization", "forbidden");
        bad(DemandIntake.validateBidResult(directReady, result, allowed), "business_path", "path_conflict");
        bad(DemandIntake.validateBidResult(facts("bid", true, false, "", false), result, allowed), "status", "not_submitted");
        ok(DemandIntake.validateBidResult(bidPending, result, allowed).valid(), "authorized external result valid");
        bad(DemandIntake.validateBidResult(bidWon, result, allowed), "result", "already_recorded");
        bad(DemandIntake.validateBidResult(bidLost, result, allowed), "result", "already_recorded");
        bad(DemandIntake.validateBidResult(bidPending, Map.of("result", "lost", "external_approval_ref", "DEMO-REF", "result_date", "bad"), allowed), "result_date", "invalid_date");
        bad(DemandIntake.validateTeamAcceptance(directReady, "DEMO-TEAM", denied), "authorization", "forbidden");
        bad(DemandIntake.validateTeamAcceptance(directPending, "DEMO-TEAM", allowed), "status", "not_ready");
        bad(DemandIntake.validateTeamAcceptance(bidPending, "DEMO-TEAM", allowed), "status", "not_ready");
        bad(DemandIntake.validateTeamAcceptance(bidLost, "DEMO-TEAM", allowed), "status", "not_ready");
        bad(DemandIntake.validateTeamAcceptance(directReady, "", allowed), "team_code", "required");
        ok(DemandIntake.validateTeamAcceptance(directReady, "DEMO-TEAM", allowed).valid(), "direct approval permits team");
        ok(DemandIntake.validateTeamAcceptance(bidWon, "DEMO-TEAM", allowed).valid(), "winning bid permits team");
        bad(DemandIntake.validateTeamAcceptance(facts("direct", false, true, "", true), "DEMO-TEAM", allowed), "status", "already_accepted");
        eq(DemandIntake.queueDisposition(bidLost), "archived_lost", "lost bid retained");
        eq(DemandIntake.queueDisposition(facts("direct", false, true, "won", false)), "inconsistent", "no fabricated bid on direct");
        eq(DemandIntake.queueDisposition(facts("direct", false, false, "", true)), "inconsistent", "cannot claim accepted without gate");
        eq(DemandIntake.queueDisposition(facts("bid", true, false, "won", false)), "inconsistent", "no result on draft");
        eq(DemandIntake.queueDisposition(null), "inconsistent", "missing evidence fails closed");
        String json = Json.write(validated.toMap());
        ok(json.contains("\"filler_code\":\"DEMO-FILLER\""), "structured JSON envelope");
        ok(Json.write(DemandIntake.courseUnits("50").toMap()).contains("\"exact_decimal\":null"), "null exact decimal serialized");
        try { validated.values().put("title", "mutated"); throw new AssertionError("validation values mutable"); }
        catch (UnsupportedOperationException expected) { assertions++; }
        System.out.println("M02 Java: " + assertions + " assertions passed");
    }

    private static Map<String, Object> complete(String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "合成培训需求"); result.put("business_path", path);
        result.put("organization_code", "DEMO-ORG"); result.put("internal_contact_code", "DEMO-INTERNAL");
        result.put("customer_contact_code", "DEMO-CUSTOMER"); result.put("category_text", "合成分类");
        result.put("delivery_mode_text", "合成方式"); result.put("period_text", "合成时段");
        result.put("duration_minutes", "90"); result.put("participant_count", "12"); result.put("objectives", "合成目标");
        result.put("expected_start_date", "2026-10-01"); return result;
    }
    private static DemandIntake.Validation check(Map<String, Object> base, String key, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(base); changed.put(key, value);
        return DemandIntake.validateForSubmission(changed, "DEMO-FILLER");
    }
    private static DemandIntake.Readiness facts(String path, boolean draft, boolean approved, String bid, boolean accepted) {
        return new DemandIntake.Readiness(1L, path, draft, approved, bid, accepted);
    }
    private static void bad(DemandIntake.Validation result, String field, String code) {
        ok(!result.valid() && result.errors().stream().anyMatch(e -> e.field().equals(field) && e.code().equals(code)), "expected error " + field + ":" + code);
    }
    private static void ok(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void eq(Object actual, Object expected, String message) { ok(Objects.equals(actual, expected), message + ": " + actual + " != " + expected); }
}
