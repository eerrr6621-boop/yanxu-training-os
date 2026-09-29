package com.training;

import static com.training.DeliverySettlementPolicy.*;
import static com.training.DeliverySettlementApprovedPolicy.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Synthetic policy facts only. No production rules, snapshots, HTTP or money transfers. */
public final class M05ApprovedPolicyTest {
    private static int passed;
    private static final MoneyRounding MONEY = new MoneyRounding(2, RoundingMode.HALF_UP, "PER_LINE", "SYNTHETIC-MONEY-2DP");
    private static final VerifiedFacts ELIGIBLE = new VerifiedFacts(Truth.YES, Truth.YES, Truth.NO, bd("50"), Truth.NO);
    private static final DevelopmentEvidence CUSTOMER = new DevelopmentEvidence(DevelopmentPath.CUSTOMER_REQUESTED,
            Truth.YES, Truth.YES, null, null, Truth.YES, null, null);
    private static final DevelopmentEvidence SELF = new DevelopmentEvidence(DevelopmentPath.SELF_INITIATED,
            null, null, Truth.YES, Truth.YES, Truth.YES, null, null);
    private static BigDecimal bd(String s) { return s == null ? null : new BigDecimal(s); }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label); passed++;
    }
    private static void eq(Object actual, Object expected, String label) {
        check(Objects.equals(actual, expected), label + ": " + actual);
    }
    private static void amount(BigDecimal actual, String expected, String label) {
        check(expected == null ? actual == null : actual != null && actual.compareTo(bd(expected)) == 0, label + ": " + actual);
    }
    private static void failure(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException ex) { passed++; return; }
        throw new AssertionError(label + " accepted");
    }
    private static void immutable(Runnable action, String label) {
        try { action.run(); } catch (UnsupportedOperationException ex) { passed++; return; }
        throw new AssertionError(label + " mutable");
    }
    private static Request request(Activity activity, Grade grade, DayType day, Truth research, String hours,
                                   VerifiedFacts facts, DevelopmentEvidence dev, MoneyRounding money) {
        return new Request(new RateRequest(activity, grade, day, research), facts, bd(hours),
                Truth.YES, Truth.YES, Truth.YES, Truth.YES, false, false, dev, money);
    }
    private static Request teaching(String hours, MoneyRounding money) {
        return request(Activity.TEACHING, Grade.LECTURER, DayType.WORKDAY, Truth.NO, hours, ELIGIBLE, null, money);
    }
    private static Request changeFacts(Request r, VerifiedFacts f) {
        return new Request(r.rateRequest(), f, r.payableHours(), r.serviceDateApplicable(), r.teachingVerified(),
                r.organizerSubmitted(), r.sharedDeliveryApproved(), r.historicalFrozen(), r.legacyRuleRequired(), r.development(), r.moneyRounding());
    }
    private static Request withGates(Request r, Truth applicable, Truth verified, Truth organizer, Truth approved,
                                     boolean frozen, boolean legacy) {
        return new Request(r.rateRequest(), r.facts(), r.payableHours(), applicable, verified, organizer, approved,
                frozen, legacy, r.development(), r.moneyRounding());
    }
    private static boolean issue(Preview p, String code) { return p.issues().stream().anyMatch(i -> code.equals(i.code())); }
    private static void noAmounts(Preview p, String label) {
        check(p.rawAmount() == null && p.finalAmount() == null && p.poolRawAmount() == null
                && p.poolFinalAmount() == null && p.individualRawAmount() == null && p.individualFinalAmount() == null, label);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Map<String, Object> basis = basisView();
        eq(basis.get("source_file_name"), "金尊公司内部培训师管理办法2.docx", "source filename retained");
        eq(basis.get("source_sha256"), "c7e8baaa7ac874b2d8fded1551f82413f62f920bf99efe748aec830450599572", "source digest retained");
        eq(basis.get("source_consultation_draft"), true, "draft is source fact");
        eq(basis.get("source_publication_date"), null, "no fabricated publication date");
        eq(basis.get("source_official_document_number"), null, "no fabricated document number");
        eq(basis.get("adoption_date"), "2026-09-22", "explicit user decision date");
        eq(basis.get("adoption_decision_reference"), ADOPTION_DECISION_REFERENCE, "recorded decision reference retained");
        eq(basis.get("adoption_evidence_is_technical_reference"), true, "evidence code not fabricated message ID");
        check(basis.get("adoption_evidence_description").toString().contains("不是用户消息ID或正式公文"), "technical reference explained");
        eq(basis.get("effective_from"), null, "adoption is not effective-from inference");
        eq(basis.get("retroactive_application_assumed"), false, "no retroactive assumption");
        eq(basis.get("hour_rounding_determines_money_rounding"), false, "hour and money rounding separate");
        eq(basis.get("evaluation_multipliers_apply_to_money"), false, "evaluation weights excluded from money");
        check(!EXECUTION_VERSION.equals(CANDIDATE_VERSION), "separate approved and candidate version");
        String[][] expected = {{"100", "200", "300", "100"}, {"150", "300", "450", "150"},
                {"200", "400", "600", "200"}, {"200", "400", "600", "200"}};
        int cells = 0;
        for (int g = 0; g < Grade.values().length; g++) {
            Grade grade = Grade.values()[g];
            for (int d = 0; d < DayType.values().length; d++) {
                Preview p = preview(request(Activity.TEACHING, grade, DayType.values()[d], Truth.NO, "1", ELIGIBLE, null, MONEY));
                amount(p.rate().unitRate(), expected[g][d], "teaching rate " + grade + DayType.values()[d]);
                amount(p.rawAmount(), expected[g][d], "exact teaching amount");
                eq(p.status(), Status.PREVIEW_READY, "teaching eligible");
                eq(p.rate().referenceGrade(), grade, "declared grade used"); cells++;
            }
            Preview p = preview(request(Activity.SOLO_DEVELOPMENT, grade, DayType.STATUTORY_HOLIDAY, Truth.NO,
                    "1", ELIGIBLE, CUSTOMER, MONEY));
            amount(p.rate().unitRate(), expected[g][3], "development table cell " + grade); cells++;
            amount(p.rawAmount(), expected[g][3], "development no holiday, x5 or x3 multiplier");
            eq(p.rate().teachingDayType(), null, "development day not a price dimension");
            eq(p.status(), Status.PREVIEW_READY, "customer development passes");
        }
        eq(cells, 16, "all sixteen source table cells checked");

        DeliverySettlementHours.Conversion sixty = DeliverySettlementHours.convert("60", DeliverySettlementHours.currentUserRule());
        eq(sixty.classHours().toPlainString(), "1.33", "confirmed sixty minute conversion");
        Preview rawSixty = preview(teaching(sixty.classHours().toPlainString(), null));
        amount(rawSixty.rawAmount(), "133", "60min -> explicit 1.33 payable x 100 exact");
        eq(view(rawSixty).get("raw_amount"), "133.00", "exact string amount preserves decimal arithmetic");
        eq(rawSixty.finalAmount(), null, "money rounding never inferred from two-place hours");
        check(issue(rawSixty, "MONEY_ROUNDING_NOT_CONFIGURED"), "precise money rule missing item");
        eq(rawSixty.status(), Status.PREVIEW_READY, "draft facts no global block");
        check(rawSixty.issues().stream().noneMatch(i -> i.code().equals("CONSULTATION_DRAFT_ONLY")
                || i.code().equals("PUBLICATION_DATE_UNKNOWN")), "old source blockers not adopted");
        check(rawSixty.formalMissingItems().stream().anyMatch(i -> i.code().equals("FORMAL_RULE_CONFIGURATION_NOT_CREATED")),
                "formal configuration missing is explicit");
        check(!rawSixty.canConfirm(), "preview cannot confirm");
        Preview rounded = preview(teaching("1.33335", MONEY));
        amount(rounded.rawAmount(), "133.335", "raw amount not pre-rounded");
        eq(rounded.finalAmount().toPlainString(), "133.34", "explicit money rounding only");
        Preview unnecessary = preview(teaching("1.33335", new MoneyRounding(2, RoundingMode.UNNECESSARY, "PER_LINE", "SYNTHETIC")));
        amount(unnecessary.rawAmount(), "133.335", "raw survives money rounding conflict");
        eq(unnecessary.finalAmount(), null, "forbidden rounding no final");
        check(issue(unnecessary, "MONEY_ROUNDING_NECESSARY_BUT_FORBIDDEN"), "rounding conflict precise");
        failure(() -> new MoneyRounding(2, null, "PER_LINE", "SYNTHETIC"), "money mode required");
        failure(() -> new MoneyRounding(2, RoundingMode.HALF_UP, "TOTAL", "SYNTHETIC"), "aggregate rounding cannot guess");
        failure(() -> new MoneyRounding(2, RoundingMode.HALF_UP, null, "SYNTHETIC"), "money scope required");
        failure(() -> new MoneyRounding(2, RoundingMode.HALF_UP, "PER_LINE", " "), "money evidence required");
        failure(() -> new MoneyRounding(2, RoundingMode.HALF_UP, "PER_LINE", "X".repeat(97)), "money evidence length bounded");
        failure(() -> new MoneyRounding(2, RoundingMode.HALF_UP, "PER_LINE", "<untrusted>"), "money evidence technology code required");
        failure(() -> new MoneyRounding(-1, RoundingMode.HALF_UP, "PER_LINE", "SYNTHETIC"), "negative scale rejected");
        failure(() -> teaching("-0.1", MONEY), "negative payable hours rejected");
        failure(() -> teaching("0.000000001", MONEY), "payable scale bounded");
        failure(() -> teaching("1234567890123456789012345", MONEY), "payable precision bounded");
        failure(() -> teaching("1E+99", MONEY), "negative scale exponent rejected");

        for (Grade grade : Grade.values()) {
            for (int d = 0; d < DayType.values().length; d++) {
                Preview p = preview(request(Activity.TEACHING, grade, DayType.values()[d], Truth.YES, "1", ELIGIBLE, null, MONEY));
                amount(p.rate().unitRate(), expected[0][d], "research teaching lecturer rate " + grade + DayType.values()[d]);
                eq(p.rate().referenceGrade(), Grade.LECTURER, "research reference grade lecturer");
                eq(p.rate().declaredGrade(), grade, "research appointment grade preserved");
            }
            Preview p = preview(request(Activity.SOLO_DEVELOPMENT, grade, null, Truth.YES, "2", ELIGIBLE, SELF, MONEY));
            amount(p.rawAmount(), "200", "research solo lecturer rate x2");
            eq(p.rate().referenceGrade(), Grade.LECTURER, "solo research lecturer reference");
        }
        Request ungradedResearchTeaching = request(Activity.TEACHING, null, DayType.WORKDAY, Truth.YES,
                "1.33", ELIGIBLE, null, MONEY);
        Preview ungradedTeaching = preview(ungradedResearchTeaching);
        eq(ungradedTeaching.rate().declaredGrade(), null, "ungraded research appointment grade stays unknown");
        eq(ungradedTeaching.rate().referenceGrade(), Grade.LECTURER, "known research membership determines lecturer rate");
        amount(ungradedTeaching.rawAmount(), "133", "ungraded research teaching calculates explicit standard");
        check(!issue(ungradedTeaching, "GRADE_MISSING"), "irrelevant appointment grade does not block research teaching");
        Preview ungradedSolo = preview(request(Activity.SOLO_DEVELOPMENT, null, null, Truth.YES,
                "2", ELIGIBLE, CUSTOMER, MONEY));
        eq(ungradedSolo.rate().declaredGrade(), null, "ungraded research solo appointment grade stays unknown");
        eq(ungradedSolo.rate().referenceGrade(), Grade.LECTURER, "ungraded research solo uses lecturer standard");
        amount(ungradedSolo.rawAmount(), "200", "ungraded research solo exact amount");
        check(!issue(ungradedSolo, "GRADE_MISSING"), "irrelevant appointment grade does not block research solo");
        Preview researchBeforeAppointment = preview(changeFacts(ungradedResearchTeaching,
                new VerifiedFacts(Truth.NO, Truth.YES, Truth.NO, null, null)));
        eq(researchBeforeAppointment.status(), Status.NOT_ELIGIBLE, "research rate override never bypasses appointment gate");
        noAmounts(researchBeforeAppointment, "research membership not payment eligibility proof");

        Preview missingHours = preview(teaching(null, MONEY));
        eq(missingHours.status(), Status.INCOMPLETE, "missing payable hours incomplete");
        noAmounts(missingHours, "never fill payable from planned/actual");
        check(issue(missingHours, "PAYABLE_HOURS_MISSING"), "payable missing explicit");
        for (Request r : List.of(
                request(Activity.TEACHING, null, DayType.WORKDAY, Truth.NO, "1", ELIGIBLE, null, MONEY),
                request(Activity.TEACHING, Grade.SPECIAL, null, Truth.NO, "1", ELIGIBLE, null, MONEY),
                request(Activity.TEACHING, Grade.SPECIAL, DayType.WORKDAY, Truth.UNKNOWN, "1", ELIGIBLE, null, MONEY))) {
            Preview p = preview(r); eq(p.rate(), null, "missing rate fact no choice"); noAmounts(p, "no guessed rate amount");
            check(!p.scenarios().isEmpty() && p.scenarios().stream().allMatch(Scenario::conditional), "reference scenarios labeled conditional");
            check(p.scenarios().stream().allMatch(s -> s.rawAmount() != null && s.payableHours() != null), "conditional scenarios provide exact possible arithmetic");
            check(((List<Map<String, Object>>) view(p).get("scenarios")).stream().allMatch(s -> Boolean.TRUE.equals(s.get("conditional"))),
                    "scenario JSON explicitly conditional");
        }
        Request base = teaching("1.33", MONEY);
        Preview dayScenarios = preview(request(Activity.TEACHING, Grade.SPECIAL, null, Truth.NO, "1.33", ELIGIBLE, null, null));
        eq(dayScenarios.scenarios().size(), 3, "known grade limits day scenarios to three");
        check(dayScenarios.scenarios().stream().allMatch(s -> s.referenceGrade() == Grade.SPECIAL), "known grade scenarios contain no unrelated grade");
        Preview researchScenarios = preview(request(Activity.TEACHING, Grade.SPECIAL, DayType.WORKDAY, Truth.UNKNOWN,
                "1.33", ELIGIBLE, null, null));
        eq(researchScenarios.scenarios().size(), 2, "research unknown has declared and lecturer alternatives only");
        check(researchScenarios.scenarios().stream().allMatch(s -> s.teachingDayType() == DayType.WORKDAY
                && (s.referenceGrade() == Grade.SPECIAL || s.referenceGrade() == Grade.LECTURER)), "scenario known day retained");
        Preview deniedScenarios = preview(changeFacts(request(Activity.TEACHING, null, null, Truth.UNKNOWN,
                "1.33", ELIGIBLE, null, null), new VerifiedFacts(Truth.NO, Truth.YES, Truth.NO, null, null)));
        check(deniedScenarios.scenarios().stream().allMatch(s -> s.rawAmount() == null), "entity ineligibility prevents scenario amounts too");
        Preview uncertain = preview(withGates(base, Truth.UNKNOWN, Truth.YES, Truth.YES, Truth.YES, false, false));
        eq(uncertain.status(), Status.CONDITIONAL_PREVIEW, "applicability not inferred from adoption date");
        amount(uncertain.rawAmount(), "133", "uncertain applicability retains conditional arithmetic");
        eq(uncertain.finalAmount(), null, "conditional arithmetic not final payable");
        eq(view(uncertain).get("raw_amount_is_conditional"), true, "conditional amount flag visible");
        for (Request r : List.of(
                withGates(base, Truth.NO, Truth.YES, Truth.YES, Truth.YES, false, false),
                changeFacts(base, new VerifiedFacts(Truth.NO, Truth.YES, Truth.NO, null, null)),
                changeFacts(base, new VerifiedFacts(Truth.YES, Truth.NO, Truth.NO, null, null)))) {
            Preview p = preview(r); eq(p.status(), Status.NOT_ELIGIBLE, "known failed eligibility gate");
            noAmounts(p, "ineligible work no payable-looking product");
        }
        for (Request r : List.of(
                withGates(base, Truth.YES, Truth.NO, Truth.YES, Truth.YES, false, false),
                withGates(base, Truth.YES, Truth.YES, Truth.NO, Truth.YES, false, false),
                withGates(base, Truth.YES, Truth.YES, Truth.YES, Truth.NO, false, false))) {
            Preview p = preview(r); eq(p.status(), Status.CONDITIONAL_PREVIEW, "unfinished teaching workflow is pending");
            amount(p.rawAmount(), "133", "unfinished workflow preserves conditional raw");
            eq(p.finalAmount(), null, "unfinished workflow blocks final payable");
            check(!p.canConfirm(), "unfinished workflow cannot confirm");
        }
        for (Request r : List.of(
                withGates(base, Truth.YES, Truth.YES, Truth.YES, Truth.YES, true, false),
                withGates(base, Truth.YES, Truth.YES, Truth.YES, Truth.YES, false, true),
                request(Activity.LEGACY_UNSETTLED, Grade.SPECIAL, null, Truth.NO, "9", ELIGIBLE, null, MONEY))) {
            Preview p = preview(r); eq(p.status(), Status.HISTORY_PROTECTED, "history protected first");
            eq(p.rate(), null, "history no new rate"); noAmounts(p, "history no recalculation");
            check(p.scenarios().isEmpty(), "history no tempting new-rate scenarios");
        }
        Preview qualifyingOr = preview(changeFacts(base, new VerifiedFacts(Truth.YES, Truth.NO, Truth.YES, null, null)));
        amount(qualifyingOr.rawAmount(), "133", "customer pays qualifies project OR");
        Preview irrelevantDevGates = preview(changeFacts(base, new VerifiedFacts(Truth.YES, Truth.YES, Truth.NO, bd("100"), Truth.YES)));
        amount(irrelevantDevGates.rawAmount(), "133", "development repetition/grant never block teaching");

        Request solo = request(Activity.SOLO_DEVELOPMENT, Grade.SPECIAL, DayType.REST_DAY, Truth.NO, "2", ELIGIBLE, CUSTOMER, MONEY);
        eq(preview(solo).status(), Status.PREVIEW_READY, "exactly 50 percent qualifies");
        Preview over = preview(changeFacts(solo, new VerifiedFacts(Truth.YES, Truth.YES, Truth.NO, bd("50.00000001"), Truth.NO)));
        eq(over.status(), Status.NOT_ELIGIBLE, "strictly above 50 denied"); noAmounts(over, "overlap excess no money");
        Preview paid = preview(changeFacts(solo, new VerifiedFacts(Truth.YES, Truth.YES, Truth.NO, bd("0"), Truth.YES)));
        eq(paid.status(), Status.NOT_ELIGIBLE, "one-time development payment"); noAmounts(paid, "paid deliverable no repeat money");
        Preview priorUnknown = preview(changeFacts(solo, new VerifiedFacts(Truth.YES, Truth.YES, Truth.NO, bd("0"), Truth.UNKNOWN)));
        eq(priorUnknown.status(), Status.CONDITIONAL_PREVIEW, "previous grant must be verified");
        eq(priorUnknown.finalAmount(), null, "prior grant unknown no final amount");
        Preview repetitionUnknown = preview(changeFacts(solo, new VerifiedFacts(Truth.YES, Truth.YES, Truth.NO, null, Truth.NO)));
        check(issue(repetitionUnknown, "REPETITION_PERCENT_UNKNOWN"), "unknown repetition not assumed zero");
        List<DevelopmentEvidence> failedPaths = List.of(
                new DevelopmentEvidence(DevelopmentPath.CUSTOMER_REQUESTED, Truth.NO, Truth.YES, null, null, Truth.YES, null, null),
                new DevelopmentEvidence(DevelopmentPath.CUSTOMER_REQUESTED, Truth.YES, Truth.NO, null, null, Truth.YES, null, null),
                new DevelopmentEvidence(DevelopmentPath.CUSTOMER_REQUESTED, Truth.YES, Truth.YES, null, null, Truth.NO, null, null),
                new DevelopmentEvidence(DevelopmentPath.SELF_INITIATED, null, null, Truth.NO, Truth.YES, Truth.YES, null, null),
                new DevelopmentEvidence(DevelopmentPath.SELF_INITIATED, null, null, Truth.YES, Truth.NO, Truth.YES, null, null),
                new DevelopmentEvidence(DevelopmentPath.SELF_INITIATED, null, null, Truth.YES, Truth.YES, Truth.NO, null, null));
        for (DevelopmentEvidence dev : failedPaths) {
            Preview p = preview(request(Activity.SOLO_DEVELOPMENT, Grade.SPECIAL, null, Truth.NO, "2", ELIGIBLE, dev, MONEY));
            eq(p.status(), Status.NOT_ELIGIBLE, "customer/self path negative evidence"); noAmounts(p, "failed development path no money");
        }
        Preview noPath = preview(request(Activity.SOLO_DEVELOPMENT, Grade.SPECIAL, null, Truth.NO, "2", ELIGIBLE, null, MONEY));
        eq(noPath.status(), Status.CONDITIONAL_PREVIEW, "missing development evidence conditional");
        check(issue(noPath, "DEVELOPMENT_PATH_MISSING"), "path missing explicit");

        Preview joint = preview(request(Activity.JOINT_DEVELOPMENT, Grade.SENIOR, DayType.STATUTORY_HOLIDAY,
                Truth.NO, "3", ELIGIBLE, CUSTOMER, null));
        amount(joint.poolRawAmount(), "450", "joint pool lead grade x hours only");
        eq(joint.rawAmount(), null, "pool cannot masquerade as individual amount");
        eq(joint.individualRawAmount(), null, "unknown share not equally divided");
        eq(joint.poolFinalAmount(), null, "unknown pool money rounding keeps raw only");
        check(issue(joint, "JOINT_INDIVIDUAL_ALLOCATION_PERCENT_MISSING"), "unknown share missing item");
        Preview jointRounded = preview(request(Activity.JOINT_DEVELOPMENT, Grade.SENIOR, null, Truth.NO,
                "3", ELIGIBLE, CUSTOMER, MONEY));
        amount(jointRounded.poolFinalAmount(), "450", "pool may round without guessing shares");
        eq(jointRounded.individualFinalAmount(), null, "individual not invented from rounded pool");
        DevelopmentEvidence allocated = new DevelopmentEvidence(DevelopmentPath.CUSTOMER_REQUESTED,
                Truth.YES, Truth.YES, null, null, Truth.YES, bd("30"), Truth.YES);
        Preview individual = preview(request(Activity.JOINT_DEVELOPMENT, Grade.SENIOR, null, Truth.NO,
                "3", ELIGIBLE, allocated, MONEY));
        amount(individual.poolRawAmount(), "450", "allocation does not multiply whole pool per member");
        amount(individual.individualRawAmount(), "135", "known approved percentage applied exactly once");
        eq(individual.individualFinalAmount(), null, "distribution rounding remainder not invented");
        check(issue(individual, "JOINT_ALLOCATION_REMAINDER_RULE_MISSING"), "allocation remainder formal missing");
        Preview allocationWithoutRounding = preview(request(Activity.JOINT_DEVELOPMENT, Grade.SENIOR, null, Truth.NO,
                "3", ELIGIBLE, allocated, null));
        check(issue(allocationWithoutRounding, "MONEY_ROUNDING_NOT_CONFIGURED")
                && issue(allocationWithoutRounding, "JOINT_ALLOCATION_REMAINDER_RULE_MISSING"), "all independent money missing items reported together");
        Preview allocationWithQualificationUnknown = preview(withGates(request(Activity.JOINT_DEVELOPMENT, Grade.SENIOR,
                null, Truth.NO, "3", ELIGIBLE, allocated, null), Truth.UNKNOWN, Truth.YES, Truth.YES, Truth.YES, false, false));
        amount(allocationWithQualificationUnknown.individualRawAmount(), "135", "qualification unknown retains conditional allocated raw");
        check(issue(allocationWithQualificationUnknown, "SERVICE_DATE_APPLICABILITY_UNKNOWN")
                && issue(allocationWithQualificationUnknown, "JOINT_ALLOCATION_REMAINDER_RULE_MISSING"), "qualification uncertainty does not hide allocation remainder");
        Preview researchLead = preview(request(Activity.JOINT_DEVELOPMENT, Grade.SPECIAL, null, Truth.YES,
                "3", ELIGIBLE, CUSTOMER, MONEY));
        noAmounts(researchLead, "research lead cross-rule unresolved no pool guess");
        check(researchLead.scenarios().stream().allMatch(s -> s.rawAmount() == null), "research joint crossover never guesses scenario amounts");
        check(issue(researchLead, "JOINT_RESEARCH_LEAD_RULE_UNRESOLVED"), "research joint crossover explicit");
        eq(view(researchLead).get("amount_scope"), "DEVELOPMENT_POOL", "unknown joint rate still describes a development pool");
        Preview ungradedJoint = preview(request(Activity.JOINT_DEVELOPMENT, null, null, Truth.YES,
                "3", ELIGIBLE, CUSTOMER, MONEY));
        check(issue(ungradedJoint, "GRADE_MISSING") && issue(ungradedJoint, "JOINT_RESEARCH_LEAD_RULE_UNRESOLVED"),
                "ungraded joint lead keeps both unresolved requirements");
        eq(view(ungradedJoint).get("amount_scope"), "DEVELOPMENT_POOL", "ungraded joint pool never labeled individual");
        noAmounts(ungradedJoint, "ungraded research joint has no guessed money");
        failure(() -> new DevelopmentEvidence(DevelopmentPath.CUSTOMER_REQUESTED, null, null, null, null, null, bd("100.01"), null),
                "allocation percent above 100 rejected");
        failure(() -> new DevelopmentEvidence(DevelopmentPath.CUSTOMER_REQUESTED, null, null, null, null, null, bd("-1"), null),
                "negative allocation rejected");
        failure(() -> new DevelopmentEvidence(DevelopmentPath.CUSTOMER_REQUESTED, null, null, null, null, null, bd("0.000000001"), null),
                "allocation scale bounded");
        failure(() -> new DevelopmentEvidence(DevelopmentPath.CUSTOMER_REQUESTED, null, null, null, null, null, bd("99.12345678901234567890123"), null),
                "allocation precision bounded");
        failure(() -> new DevelopmentEvidence(DevelopmentPath.CUSTOMER_REQUESTED, null, null, null, null, null, bd("1E+2"), null),
                "allocation negative decimal scale rejected");

        Map<String, Object> json = view(rawSixty);
        eq(json.get("can_confirm"), false, "JSON cannot confirm");
        eq(json.get("creates_configuration"), false, "JSON declares no formal configuration write");
        eq(json.get("creates_snapshot"), false, "JSON declares no snapshot write");
        Map<String, Object> jsonRate = (Map<String, Object>) json.get("rate");
        check(jsonRate.get("unit_rate") instanceof String && json.get("payable_hours") instanceof String
                && json.get("raw_amount") instanceof String, "JSON financial values are strings");
        List<Map<String, String>> jsonIssues = (List<Map<String, String>>) json.get("issues");
        check(jsonIssues.stream().allMatch(i -> i.containsKey("code") && i.containsKey("message")), "localized issue shape");
        immutable(() -> json.put("raw_amount", "999"), "preview view");
        immutable(() -> jsonRate.put("unit_rate", "999"), "nested rate view");
        immutable(() -> basis.put("adoption_date", "1900-01-01"), "execution basis view");
        immutable(() -> rawSixty.issues().clear(), "preview issues");
        immutable(() -> rawSixty.formalMissingItems().clear(), "formal missing items");
        immutable(() -> jsonIssues.get(0).put("code", "CHANGED"), "nested issue view");
        check(!DeliverySettlementPolicy.lookupCandidateRate(base.rateRequest()).canConfirm(), "old candidate API unchanged");
        check(DeliverySettlementPolicy.lookupCandidateRate(base.rateRequest()).formalBlockers().size() == 2,
                "old source-only blockers retained only in old API");
        System.out.println("M05 approved policy preview: " + passed + " assertions passed (synthetic facts; no configurations or snapshots created)");
    }
    private M05ApprovedPolicyTest() {}
}
