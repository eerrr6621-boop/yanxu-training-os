package com.training;

import static com.training.DeliverySettlementPolicy.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Synthetic facts only; checks the candidate extraction without database, network or shared output. */
public final class M05DeliverySettlementPolicyTest {
    private static int passed;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
    private static void eq(Object actual, Object expected, String label) {
        check(Objects.equals(actual, expected), label + ": " + actual);
    }
    private static void failure(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException ex) { passed++; return; }
        throw new AssertionError(label + " accepted");
    }
    private static RateRequest request(Activity activity, Grade grade, DayType day, Truth research) {
        return new RateRequest(activity, grade, day, research);
    }
    private static VerifiedFacts facts(Truth appointed, Truth plan, Truth customer, String repetition, Truth prior) {
        return new VerifiedFacts(appointed, plan, customer,
                repetition == null ? null : new BigDecimal(repetition), prior);
    }
    private static String rate(RateRequest request) {
        return lookupCandidateRate(request).candidateRate().unitRate().toPlainString();
    }
    public static void main(String[] args) {
        SourceSnapshot source = snapshot();
        eq(source.fileName(), "金尊公司内部培训师管理办法2.docx", "source filename");
        eq(source.sha256(), "c7e8baaa7ac874b2d8fded1551f82413f62f920bf99efe748aec830450599572", "source digest");
        check(source.consultationDraft(), "draft marked");
        eq(source.publicationDate(), null, "no invented release date");
        eq(source.officialDocumentNumber(), null, "technical version not official number");
        eq(source.minutesPerClassHour(), 45, "45 minute class hour");
        eq(source.rateUnit(), "元／课时", "source rate unit");
        eq(source.rows().size(), 4, "four grades");
        String[][] expected = {{"100", "200", "300", "100"}, {"150", "300", "450", "150"},
                {"200", "400", "600", "200"}, {"200", "400", "600", "200"}};
        for (int i = 0; i < Grade.values().length; i++) {
            Grade grade = Grade.values()[i]; RateRow row = source.rows().get(i);
            eq(List.of(row.workdayTeaching().toPlainString(), row.restDayTeaching().toPlainString(),
                    row.statutoryHolidayTeaching().toPlainString(), row.soloDevelopment().toPlainString()),
                    List.of(expected[i]), "source row " + grade);
            for (int d = 0; d < DayType.values().length; d++) {
                DayType day = DayType.values()[d];
                eq(rate(request(Activity.TEACHING, grade, day, Truth.NO)), expected[i][d], "teaching rate " + grade + day);
                eq(rate(request(Activity.TEACHING, grade, day, Truth.YES)), expected[0][d], "research override " + grade + day);
                eq(rate(request(Activity.SOLO_DEVELOPMENT, grade, day, Truth.NO)), expected[i][3], "development ignores calendar " + grade + day);
            }
            eq(rate(request(Activity.SOLO_DEVELOPMENT, grade, null, Truth.YES)), "100", "research independent development " + grade);
        }
        RateRequest teaching = request(Activity.TEACHING, Grade.SPECIAL, DayType.WORKDAY, Truth.NO);
        RateRequest solo = request(Activity.SOLO_DEVELOPMENT, Grade.SPECIAL, null, Truth.NO);
        VerifiedFacts eligible = facts(Truth.YES, Truth.YES, Truth.NO, "50", Truth.NO);
        Assessment local = assess(teaching, eligible);
        eq(local.applicability(), Applicability.LOCAL_CHECKS_PASSED_CANDIDATE_ONLY, "local gates only");
        check(!local.canConfirm() && !local.candidateRate().canConfirm() && !lookupCandidateRate(teaching).canConfirm(), "all candidate paths unconfirmable");
        eq(local.formalBlockers(), List.of(Issue.CONSULTATION_DRAFT_ONLY, Issue.PUBLICATION_DATE_UNKNOWN), "draft and date block formal use");
        eq(assess(teaching, facts(Truth.NO, Truth.YES, Truth.YES, null, null)).applicability(), Applicability.NOT_ELIGIBLE, "preappointment work denied");
        eq(assess(teaching, facts(Truth.UNKNOWN, Truth.YES, Truth.NO, null, null)).applicability(), Applicability.INCOMPLETE, "unknown appointment not false or true");
        eq(assess(teaching, facts(Truth.YES, Truth.NO, Truth.YES, null, null)).applicability(), Applicability.LOCAL_CHECKS_PASSED_CANDIDATE_ONLY, "customer paid OR annual plan");
        eq(assess(teaching, facts(Truth.YES, Truth.YES, Truth.UNKNOWN, null, null)).applicability(), Applicability.LOCAL_CHECKS_PASSED_CANDIDATE_ONLY, "known annual plan satisfies OR");
        eq(assess(teaching, facts(Truth.YES, Truth.NO, Truth.NO, null, null)).applicability(), Applicability.NOT_ELIGIBLE, "outside both project scopes");
        eq(assess(teaching, facts(Truth.YES, Truth.NO, Truth.UNKNOWN, null, null)).applicability(), Applicability.INCOMPLETE, "unknown project scope");
        eq(assess(teaching, null).applicability(), Applicability.INCOMPLETE, "missing host facts fail closed");
        eq(assess(solo, eligible).applicability(), Applicability.LOCAL_CHECKS_PASSED_CANDIDATE_ONLY, "exactly 50 percent accepted");
        eq(assess(solo, facts(Truth.YES, Truth.YES, Truth.NO, "50.00000001", Truth.NO)).applicability(), Applicability.NOT_ELIGIBLE, "above 50 percent denied");
        check(assess(solo, facts(Truth.YES, Truth.YES, Truth.NO, null, Truth.NO)).issues().contains(Issue.REPETITION_PERCENT_UNKNOWN), "unknown repetition not zero");
        check(assess(solo, facts(Truth.YES, Truth.YES, Truth.NO, "0", Truth.YES)).issues().contains(Issue.DEVELOPMENT_FEE_ALREADY_GRANTED), "one payment per development");
        eq(assess(solo, facts(Truth.YES, Truth.YES, Truth.NO, "0", Truth.UNKNOWN)).applicability(), Applicability.INCOMPLETE, "prior grant must be checked");
        eq(assess(teaching, facts(Truth.YES, Truth.YES, Truth.NO, "100", Truth.YES)).applicability(), Applicability.LOCAL_CHECKS_PASSED_CANDIDATE_ONLY, "development gates do not suppress teaching");
        failure(() -> facts(Truth.YES, Truth.YES, Truth.NO, "-0.01", Truth.NO), "negative repetition");
        failure(() -> facts(Truth.YES, Truth.YES, Truth.NO, "100.01", Truth.NO), "repetition above 100");
        RateRequest joint = request(Activity.JOINT_DEVELOPMENT, Grade.SENIOR, DayType.STATUTORY_HOLIDAY, Truth.NO);
        eq(rate(joint), "150", "joint pool uses lead grade without holiday multiplier");
        eq(assess(joint, eligible).applicability(), Applicability.UNSUPPORTED, "joint allocation not implemented");
        check(lookupCandidateRate(joint).issues().contains(Issue.JOINT_ALLOCATION_UNSUPPORTED), "joint lookup warns before amount calculation");
        RateRequest jointResearch = request(Activity.JOINT_DEVELOPMENT, Grade.SPECIAL, null, Truth.YES);
        eq(lookupCandidateRate(jointResearch).candidateRate(), null, "no guessed research lead joint rate");
        check(lookupCandidateRate(jointResearch).issues().contains(Issue.JOINT_RESEARCH_LEAD_RULE_UNRESOLVED), "research lead ambiguity explicit");
        RateRequest missingMembership = request(Activity.TEACHING, Grade.SPECIAL, DayType.WORKDAY, null);
        eq(lookupCandidateRate(missingMembership).candidateRate(), null, "no membership assumption");
        eq(lookupCandidateRate(request(Activity.TEACHING, null, DayType.WORKDAY, Truth.NO)).candidateRate(), null, "no default grade");
        eq(lookupCandidateRate(request(Activity.TEACHING, Grade.SPECIAL, null, Truth.NO)).candidateRate(), null, "no weekday inference");
        RateRequest legacy = request(Activity.LEGACY_UNSETTLED, null, null, null);
        Assessment old = assess(legacy, facts(Truth.NO, Truth.NO, Truth.NO, null, null));
        eq(old.applicability(), Applicability.LEGACY_RULE_REQUIRED, "old entitlement evaluated under old rules only");
        eq(old.candidateRate(), null, "new rates never used for legacy payment");
        eq(old.issues(), List.of(Issue.LEGACY_RULE_AND_UNSETTLED_ENTITLEMENT_REQUIRED), "old rules and unsettled proof required");
        check(!old.canConfirm(), "legacy reminder cannot confirm payment");
        try { source.rows().clear(); throw new AssertionError("mutable snapshot"); }
        catch (UnsupportedOperationException expectedException) { passed++; }
        System.out.println("M05 candidate policy: " + passed + " assertions passed (synthetic facts; no final amounts)");
    }
}
