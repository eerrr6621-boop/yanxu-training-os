package com.training;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only candidate extraction of an unpublished consultation draft.
 * This class supplies reference rates and limited applicability checks only: it never creates
 * a DeliverySettlement rule/rate, a READY calculation, a confirmed record, or a payment amount.
 * Appointment/grade/day classification and evidence come from M04 or a trusted host, not guesses.
 */
public final class DeliverySettlementPolicy {
    /** Technical extraction identifier, NOT an official document number or effective policy version. */
    public static final String CANDIDATE_VERSION = "M05-CANDIDATE-SOURCE-C7E8BAAA-V1";
    public static final int MINUTES_PER_CLASS_HOUR = 45;
    public enum Grade { LECTURER, SENIOR, SPECIAL, DISTINGUISHED }
    public enum Activity { TEACHING, SOLO_DEVELOPMENT, JOINT_DEVELOPMENT, LEGACY_UNSETTLED }
    public enum DayType { WORKDAY, REST_DAY, STATUTORY_HOLIDAY }
    public enum Truth { YES, NO, UNKNOWN }
    public enum Applicability {
        LOCAL_CHECKS_PASSED_CANDIDATE_ONLY, INCOMPLETE, NOT_ELIGIBLE,
        UNSUPPORTED, LEGACY_RULE_REQUIRED
    }
    public enum Issue {
        CONSULTATION_DRAFT_ONLY, PUBLICATION_DATE_UNKNOWN,
        GRADE_MISSING, TEACHING_DAY_TYPE_MISSING, RESEARCH_TEAM_MEMBERSHIP_UNKNOWN,
        APPOINTMENT_FACT_UNKNOWN, WORK_BEFORE_APPOINTMENT,
        PROJECT_SCOPE_UNKNOWN, PROJECT_OUTSIDE_PAYABLE_SCOPE,
        REPETITION_PERCENT_UNKNOWN, REPETITION_PERCENT_EXCEEDS_50,
        DEVELOPMENT_PRIOR_GRANT_UNKNOWN, DEVELOPMENT_FEE_ALREADY_GRANTED,
        JOINT_ALLOCATION_UNSUPPORTED, JOINT_RESEARCH_LEAD_RULE_UNRESOLVED,
        LEGACY_RULE_AND_UNSETTLED_ENTITLEMENT_REQUIRED
    }

    public record RateRow(Grade grade, BigDecimal workdayTeaching, BigDecimal restDayTeaching,
                          BigDecimal statutoryHolidayTeaching, BigDecimal soloDevelopment) {}

    public record SourceSnapshot(String fileName, String sha256, boolean consultationDraft,
                                 String candidateVersion, String officialDocumentNumber,
                                 LocalDate publicationDate, int minutesPerClassHour, String rateUnit, List<RateRow> rows) {
        public SourceSnapshot { rows = List.copyOf(rows); }
    }

    private static final SourceSnapshot SOURCE = new SourceSnapshot(
            "金尊公司内部培训师管理办法2.docx",
            "c7e8baaa7ac874b2d8fded1551f82413f62f920bf99efe748aec830450599572",
            true, CANDIDATE_VERSION, null, null, MINUTES_PER_CLASS_HOUR, "元／课时",
            List.of(row(Grade.LECTURER, "100", "200", "300", "100"),
                    row(Grade.SENIOR, "150", "300", "450", "150"),
                    row(Grade.SPECIAL, "200", "400", "600", "200"),
                    row(Grade.DISTINGUISHED, "200", "400", "600", "200")));
    private static final List<Issue> FORMAL_BLOCKERS = List.of(
            Issue.CONSULTATION_DRAFT_ONLY, Issue.PUBLICATION_DATE_UNKNOWN);

    /** For JOINT_DEVELOPMENT the grade and research membership are those of the lead developer. */
    public record RateRequest(Activity activity, Grade instructorOrLeadGrade,
                              DayType teachingDayType, Truth researchTeamMember) {
        public RateRequest {
            Objects.requireNonNull(activity);
            researchTeamMember = known(researchTeamMember);
        }
    }

    /**
     * Already verified host facts. YES means the work was undertaken after appointment; dates are
     * not inferred from the current appointment or acceptance date. Project scope is annual plan OR
     * customer-paid. Repetition is a percentage in [0,100], NOT a fraction in [0,1]. Prior grant must
     * be checked for the development deliverable/version, not for each subsequent use of a course.
     * These facts do not replace development acceptance, approval, or host transaction checks.
     */
    public record VerifiedFacts(Truth appointedBeforeWork, Truth inAnnualTrainingPlan,
                                Truth customerPaysProject, BigDecimal repetitionPercent,
                                Truth developmentFeeAlreadyGranted) {
        public VerifiedFacts {
            appointedBeforeWork = known(appointedBeforeWork);
            inAnnualTrainingPlan = known(inAnnualTrainingPlan);
            customerPaysProject = known(customerPaysProject);
            developmentFeeAlreadyGranted = known(developmentFeeAlreadyGranted);
            if (repetitionPercent != null && (repetitionPercent.signum() < 0
                    || repetitionPercent.compareTo(new BigDecimal("100")) > 0))
                throw new IllegalArgumentException("重复率必须是0至100的百分比");
        }
    }

    /** A unit-rate reference only. For joint development it is the pool's rate, never a member rate. */
    public record CandidateRate(Grade referenceGrade, Activity activity, BigDecimal unitRate,
                                String sourceReference) {
        public String candidateVersion() { return CANDIDATE_VERSION; }
        public boolean canConfirm() { return false; }
    }

    public record RateLookup(CandidateRate candidateRate, List<Issue> issues) {
        public RateLookup { issues = List.copyOf(issues); }
        public boolean canConfirm() { return false; }
        public List<Issue> formalBlockers() { return FORMAL_BLOCKERS; }
    }

    public record Assessment(Applicability applicability, CandidateRate candidateRate, List<Issue> issues) {
        public Assessment { issues = List.copyOf(issues); }
        public boolean canConfirm() { return false; }
        public List<Issue> formalBlockers() { return FORMAL_BLOCKERS; }
    }

    public static SourceSnapshot snapshot() { return SOURCE; }

    /** Raw draft rates with no currency rounding, hour conversion, amount, or inferred eligibility. */
    public static RateLookup lookupCandidateRate(RateRequest request) {
        Objects.requireNonNull(request);
        List<Issue> issues = new ArrayList<>();
        if (request.activity() == Activity.LEGACY_UNSETTLED) {
            issues.add(Issue.LEGACY_RULE_AND_UNSETTLED_ENTITLEMENT_REQUIRED);
            return new RateLookup(null, issues);
        }
        boolean joint = request.activity() == Activity.JOINT_DEVELOPMENT;
        if (joint) issues.add(Issue.JOINT_ALLOCATION_UNSUPPORTED);
        if (request.instructorOrLeadGrade() == null) issues.add(Issue.GRADE_MISSING);
        if (request.researchTeamMember() == Truth.UNKNOWN)
            issues.add(Issue.RESEARCH_TEAM_MEMBERSHIP_UNKNOWN);
        if (joint && request.researchTeamMember() == Truth.YES)
            issues.add(Issue.JOINT_RESEARCH_LEAD_RULE_UNRESOLVED);
        if (request.activity() == Activity.TEACHING && request.teachingDayType() == null)
            issues.add(Issue.TEACHING_DAY_TYPE_MISSING);
        // The joint-allocation warning does not hide the explicitly stated lead-based pool rate.
        if (issues.stream().anyMatch(issue -> issue != Issue.JOINT_ALLOCATION_UNSUPPORTED))
            return new RateLookup(null, issues);
        Grade referenceGrade = request.researchTeamMember() == Truth.YES
                ? Grade.LECTURER : request.instructorOrLeadGrade();
        RateRow rate = SOURCE.rows().stream().filter(r -> r.grade() == referenceGrade).findFirst().orElseThrow();
        BigDecimal unitRate;
        if (request.activity() == Activity.TEACHING) {
            unitRate = switch (request.teachingDayType()) {
                case WORKDAY -> rate.workdayTeaching();
                case REST_DAY -> rate.restDayTeaching();
                case STATUTORY_HOLIDAY -> rate.statutoryHolidayTeaching();
            };
        } else {
            // Development is not linked to rest days or statutory holidays (attachment note 2).
            unitRate = rate.soloDevelopment();
        }
        String reference = joint ? "ATTACHMENT-NOTE-2"
                : request.researchTeamMember() == Truth.YES ? "ATTACHMENT-NOTE-1" : "ATTACHMENT-RATE-TABLE";
        return new RateLookup(new CandidateRate(referenceGrade, request.activity(), unitRate, reference), issues);
    }

    /**
     * Limited candidate gates from attachment payment rules 1, 3 and 6. A passing result is still
     * a draft: it is not a finding that all acceptance/approval/payment requirements have been met.
     * Legacy entitlement is evaluated exclusively under the old policy, never under the new table.
     */
    public static Assessment assess(RateRequest request, VerifiedFacts facts) {
        Objects.requireNonNull(request);
        RateLookup lookup = lookupCandidateRate(request);
        List<Issue> issues = new ArrayList<>(lookup.issues());
        if (request.activity() == Activity.LEGACY_UNSETTLED)
            return new Assessment(Applicability.LEGACY_RULE_REQUIRED, null, issues);
        if (facts == null) facts = new VerifiedFacts(null, null, null, null, null);
        boolean denied = false;
        if (facts.appointedBeforeWork() == Truth.NO) {
            issues.add(Issue.WORK_BEFORE_APPOINTMENT); denied = true;
        } else if (facts.appointedBeforeWork() == Truth.UNKNOWN) {
            issues.add(Issue.APPOINTMENT_FACT_UNKNOWN);
        }
        if (facts.inAnnualTrainingPlan() != Truth.YES && facts.customerPaysProject() != Truth.YES) {
            if (facts.inAnnualTrainingPlan() == Truth.NO && facts.customerPaysProject() == Truth.NO) {
                issues.add(Issue.PROJECT_OUTSIDE_PAYABLE_SCOPE); denied = true;
            } else {
                issues.add(Issue.PROJECT_SCOPE_UNKNOWN);
            }
        }
        if (request.activity() == Activity.SOLO_DEVELOPMENT || request.activity() == Activity.JOINT_DEVELOPMENT) {
            if (facts.repetitionPercent() == null) {
                issues.add(Issue.REPETITION_PERCENT_UNKNOWN);
            } else if (facts.repetitionPercent().compareTo(new BigDecimal("50")) > 0) {
                issues.add(Issue.REPETITION_PERCENT_EXCEEDS_50); denied = true;
            }
            if (facts.developmentFeeAlreadyGranted() == Truth.YES) {
                issues.add(Issue.DEVELOPMENT_FEE_ALREADY_GRANTED); denied = true;
            } else if (facts.developmentFeeAlreadyGranted() == Truth.UNKNOWN) {
                issues.add(Issue.DEVELOPMENT_PRIOR_GRANT_UNKNOWN);
            }
        }
        Applicability applicability = denied ? Applicability.NOT_ELIGIBLE
                : request.activity() == Activity.JOINT_DEVELOPMENT ? Applicability.UNSUPPORTED
                : issues.isEmpty() ? Applicability.LOCAL_CHECKS_PASSED_CANDIDATE_ONLY : Applicability.INCOMPLETE;
        return new Assessment(applicability, lookup.candidateRate(), issues);
    }

    private static RateRow row(Grade grade, String workday, String restDay, String holiday, String development) {
        return new RateRow(grade, new BigDecimal(workday), new BigDecimal(restDay),
                new BigDecimal(holiday), new BigDecimal(development));
    }
    private static Truth known(Truth value) { return value == null ? Truth.UNKNOWN : value; }
    private DeliverySettlementPolicy() {}
}
