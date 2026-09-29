package com.training;

import static com.training.DeliverySettlementPolicy.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only implementation preview of the user's 2026-09-22 adoption decision.
 * Adoption is neither publication nor an inferred retroactive effective date. All applicability
 * and business facts must come from the trusted host. This class writes no rules or snapshots.
 * The older candidate API remains separate and retains its original source-only semantics.
 */
public final class DeliverySettlementApprovedPolicy {
    public static final String EXECUTION_VERSION = "M05-USER-APPROVED-SOURCE-C7E8BAAA-V1";
    public static final LocalDate ADOPTION_DATE = LocalDate.of(2026, 9, 22);
    /** Local technical reference to the recorded user decision; not a message ID or official document. */
    public static final String ADOPTION_EVIDENCE = "USER-20260922-EXECUTE-MANAGEMENT-MEASURES";
    public static final String ADOPTION_DECISION_REFERENCE =
            "coordination/DECISIONS.md#2026-09-22-用户补充审批身份四类培训与课酬执行依据";
    public enum Status { PREVIEW_READY, CONDITIONAL_PREVIEW, INCOMPLETE, NOT_ELIGIBLE, HISTORY_PROTECTED }
    public enum DevelopmentPath { CUSTOMER_REQUESTED, SELF_INITIATED }

    public record Issue(String code, String message) {
        public Issue { Objects.requireNonNull(code); Objects.requireNonNull(message); }
    }

    /** Currency rounding is independent of the 45-minute / two-decimal class-hour rule. */
    public record MoneyRounding(int scale, RoundingMode mode, String scope, String evidenceCode) {
        public MoneyRounding {
            if (scale < 0 || scale > 8) throw new IllegalArgumentException("金额位数必须为0至8");
            if (mode == null) throw new IllegalArgumentException("金额舍入方式必须明确");
            if (!"PER_LINE".equals(scope)) throw new IllegalArgumentException("仅支持明确批准的PER_LINE金额舍入范围");
            if (evidenceCode == null || !evidenceCode.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}"))
                throw new IllegalArgumentException("金额舍入必须提供不超过96字符的有效技术依据编码");
        }
    }

    /** Allocation belongs to the approved pool, never to another application of the unit rate. */
    public record DevelopmentEvidence(DevelopmentPath path, Truth customerWrittenPaymentAgreement,
                                      Truth customerAcceptance, Truth companyNeed, Truth annualReviewPassed,
                                      Truth companyApproval, BigDecimal individualAllocationPercent,
                                      Truth allocationApproved) {
        public DevelopmentEvidence {
            customerWrittenPaymentAgreement = known(customerWrittenPaymentAgreement);
            customerAcceptance = known(customerAcceptance);
            companyNeed = known(companyNeed); annualReviewPassed = known(annualReviewPassed);
            companyApproval = known(companyApproval); allocationApproved = known(allocationApproved);
            if (individualAllocationPercent != null && (individualAllocationPercent.signum() < 0
                    || individualAllocationPercent.compareTo(new BigDecimal("100")) > 0
                    || individualAllocationPercent.precision() > 24 || individualAllocationPercent.scale() < 0
                    || individualAllocationPercent.scale() > 8))
                throw new IllegalArgumentException("个人分配比例须为0至100的百分比，最多24位有效数字及0至8位小数");
        }
    }

    public record Request(RateRequest rateRequest, VerifiedFacts facts, BigDecimal payableHours,
                          Truth serviceDateApplicable, Truth teachingVerified, Truth organizerSubmitted,
                          Truth sharedDeliveryApproved, boolean historicalFrozen, boolean legacyRuleRequired,
                          DevelopmentEvidence development, MoneyRounding moneyRounding) {
        public Request {
            Objects.requireNonNull(rateRequest, "必须明确业务类型");
            serviceDateApplicable = known(serviceDateApplicable); teachingVerified = known(teachingVerified);
            organizerSubmitted = known(organizerSubmitted); sharedDeliveryApproved = known(sharedDeliveryApproved);
            if (payableHours != null && (payableHours.signum() < 0 || payableHours.precision() > 24
                    || payableHours.scale() < 0 || payableHours.scale() > 8))
                throw new IllegalArgumentException("计酬课时须非负、最多24位有效数字及0至8位小数");
        }
    }

    public record Rate(Grade declaredGrade, Grade referenceGrade, Activity activity, DayType teachingDayType,
                       BigDecimal unitRate, String sourceReference, boolean developmentPool) {}
    /** These are reference cases, not selected rates or payable amounts. */
    public record Scenario(Grade referenceGrade, Activity activity, DayType teachingDayType,
                           BigDecimal unitRate, BigDecimal payableHours, BigDecimal rawAmount, String condition) {
        public boolean conditional() { return true; }
    }
    public record Preview(Status status, Rate rate, BigDecimal payableHours, BigDecimal rawAmount,
                          BigDecimal finalAmount, BigDecimal poolRawAmount, BigDecimal poolFinalAmount,
                          BigDecimal individualRawAmount, BigDecimal individualFinalAmount,
                          List<Issue> issues, List<Issue> formalMissingItems, List<Scenario> scenarios,
                          MoneyRounding moneyRounding) {
        public Preview {
            Objects.requireNonNull(status);
            issues = List.copyOf(issues); formalMissingItems = List.copyOf(formalMissingItems);
            scenarios = List.copyOf(scenarios);
        }
        public boolean canConfirm() { return false; }
        public String executionVersion() { return EXECUTION_VERSION; }
    }

    public static Preview preview(Request request) {
        Objects.requireNonNull(request);
        RateRequest rr = request.rateRequest();
        List<Issue> issues = new ArrayList<>();
        // History is returned before any new rate lookup, multiplication or rounding.
        if (request.historicalFrozen() || request.legacyRuleRequired() || rr.activity() == Activity.LEGACY_UNSETTLED) {
            if (request.historicalFrozen()) add(issues, "HISTORICAL_FROZEN_SNAPSHOT_PRESERVED", "已冻结记录须读取原快照，不按新依据重算");
            if (request.legacyRuleRequired() || rr.activity() == Activity.LEGACY_UNSETTLED)
                add(issues, "LEGACY_RULE_AND_ENTITLEMENT_REQUIRED", "旧制补发须按原规则及原支付资格核实，不套用新费率");
            return result(Status.HISTORY_PROTECTED, null, request, null, null, null, null, null, null,
                    issues, issues, List.of());
        }

        Rate rate = lookup(rr, issues);
        if (request.payableHours() == null)
            add(issues, "PAYABLE_HOURS_MISSING", "缺少明确计酬课时；实际或计划课时不会自动代入");
        boolean denied = requirement(request.serviceDateApplicable(), "SERVICE_DATE_APPLICABILITY_UNKNOWN",
                "须核实本次业务适用用户批准的执行依据", "SERVICE_DATE_NOT_APPLICABLE", "本次业务不适用该执行依据", issues);
        VerifiedFacts facts = request.facts() == null ? new VerifiedFacts(null, null, null, null, null) : request.facts();
        denied |= requirement(facts.appointedBeforeWork(), "APPOINTMENT_FACT_UNKNOWN", "缺少工作开始前已获聘的核实事实",
                "WORK_BEFORE_APPOINTMENT", "获聘前开展的工作不符合支付条件", issues);
        if (facts.inAnnualTrainingPlan() != Truth.YES && facts.customerPaysProject() != Truth.YES) {
            if (facts.inAnnualTrainingPlan() == Truth.NO && facts.customerPaysProject() == Truth.NO) {
                add(issues, "PROJECT_OUTSIDE_PAYABLE_SCOPE", "项目既不在年度培训计划内，也非客户付费项目"); denied = true;
            } else add(issues, "PROJECT_SCOPE_UNKNOWN", "须核实项目属于年度培训计划或客户付费范围");
        }
        boolean joint = rr.activity() == Activity.JOINT_DEVELOPMENT;
        if (rr.activity() == Activity.TEACHING) {
            pending(request.teachingVerified(), "TEACHING_VERIFICATION_UNKNOWN", "授课事实尚缺核验",
                    "TEACHING_NOT_VERIFIED", "授课事实未核验通过，不能形成应付金额", issues);
            pending(request.organizerSubmitted(), "ORGANIZER_SUBMISSION_UNKNOWN", "缺少主办单位申报事实",
                    "ORGANIZER_NOT_SUBMITTED", "主办单位尚未申报，不能形成应付金额", issues);
            pending(request.sharedDeliveryApproved(), "SHARED_DELIVERY_APPROVAL_UNKNOWN", "缺少共享交付部核准事实",
                    "SHARED_DELIVERY_NOT_APPROVED", "共享交付部尚未核准，不能形成应付金额", issues);
        } else {
            if (facts.repetitionPercent() == null) add(issues, "REPETITION_PERCENT_UNKNOWN", "缺少开发成果的重复率核实结果");
            else if (facts.repetitionPercent().compareTo(new BigDecimal("50")) > 0) {
                add(issues, "REPETITION_PERCENT_EXCEEDS_50", "开发成果重复率超过50%，不符合一次性开发课酬条件"); denied = true;
            }
            if (facts.developmentFeeAlreadyGranted() == Truth.YES) {
                add(issues, "DEVELOPMENT_FEE_ALREADY_GRANTED", "该开发成果已获一次性开发课酬，不重复计发"); denied = true;
            } else if (facts.developmentFeeAlreadyGranted() == Truth.UNKNOWN)
                add(issues, "DEVELOPMENT_PRIOR_GRANT_UNKNOWN", "须核实该开发成果或版本此前未支付开发课酬");
            denied |= checkDevelopment(request.development(), issues);
        }
        // Qualification uncertainty permits only expressly conditional arithmetic, never a final payable amount.
        boolean qualificationsIncomplete = !issues.isEmpty();
        BigDecimal product = !denied && rate != null && request.payableHours() != null
                ? request.payableHours().multiply(rate.unitRate()) : null;
        BigDecimal raw = joint ? null : product;
        BigDecimal poolRaw = joint ? product : null;
        BigDecimal individualRaw = null;
        if (joint) {
            DevelopmentEvidence d = request.development();
            if (d == null || d.individualAllocationPercent() == null)
                add(issues, "JOINT_INDIVIDUAL_ALLOCATION_PERCENT_MISSING", "合作开发仅计算负责人等级对应总池；缺少个人分配比例");
            if (d == null || d.allocationApproved() != Truth.YES)
                add(issues, "JOINT_ALLOCATION_APPROVAL_MISSING", "缺少团队协商分配比例经共享交付部核准的事实");
            if (poolRaw != null && d != null && d.individualAllocationPercent() != null && d.allocationApproved() == Truth.YES)
                individualRaw = poolRaw.multiply(d.individualAllocationPercent()).movePointLeft(2);
        }
        if (request.moneyRounding() == null)
            add(issues, "MONEY_ROUNDING_NOT_CONFIGURED", "金额小数位、舍入方式、按行范围及批准依据尚未配置；课时两位规则不替代金额舍入");
        BigDecimal finalAmount = null, poolFinal = null, individualFinal = null;
        if (!qualificationsIncomplete && !denied && request.moneyRounding() != null) {
            finalAmount = rounded(raw, request.moneyRounding(), issues);
            poolFinal = rounded(poolRaw, request.moneyRounding(), issues);
        }
        // Pool rounding does not define how multiple individual shares reconcile their rounding remainder.
        if (individualRaw != null)
            add(issues, "JOINT_ALLOCATION_REMAINDER_RULE_MISSING", "个人份额可作精确预览，正式分配仍缺各成员金额舍入尾差处理规则");
        Status status = denied ? Status.NOT_ELIGIBLE : product == null ? Status.INCOMPLETE
                : qualificationsIncomplete ? Status.CONDITIONAL_PREVIEW : Status.PREVIEW_READY;
        List<Issue> formal = new ArrayList<>(issues);
        add(formal, "FORMAL_RULE_CONFIGURATION_NOT_CREATED", "本接口仅预览，尚未创建带授权和版本依据的正式结算配置");
        add(formal, "FORMAL_SETTLEMENT_CONFIRMATION_REQUIRED", "尚未通过宿主正式确认流程生成结算快照");
        return result(status, rate, request, raw, finalAmount, poolRaw, poolFinal, individualRaw, individualFinal,
                issues, formal, rate == null ? scenarios(rr, request.payableHours(), denied) : List.of());
    }

    private static Preview result(Status status, Rate rate, Request r, BigDecimal raw, BigDecimal fin,
                                  BigDecimal poolRaw, BigDecimal poolFin, BigDecimal individualRaw, BigDecimal individualFin,
                                  List<Issue> issues, List<Issue> formal, List<Scenario> scenarios) {
        return new Preview(status, rate, r.payableHours(), raw, fin, poolRaw, poolFin, individualRaw, individualFin,
                issues, formal, scenarios, r.moneyRounding());
    }

    private static Rate lookup(RateRequest r, List<Issue> issues) {
        boolean researchLecturerStandard = r.researchTeamMember() == Truth.YES
                && (r.activity() == Activity.TEACHING || r.activity() == Activity.SOLO_DEVELOPMENT);
        if (r.instructorOrLeadGrade() == null && !researchLecturerStandard)
            add(issues, "GRADE_MISSING", "缺少已核实的讲师或开发主负责人等级");
        if (r.researchTeamMember() == Truth.UNKNOWN)
            add(issues, "RESEARCH_TEAM_MEMBERSHIP_UNKNOWN", "缺少教学研发团队成员身份的核实事实");
        if (r.activity() == Activity.TEACHING && r.teachingDayType() == null)
            add(issues, "TEACHING_DAY_TYPE_MISSING", "须明确工作日、休息日或法定节假日，不按日历自动猜定");
        if (r.activity() == Activity.JOINT_DEVELOPMENT && r.researchTeamMember() == Truth.YES)
            add(issues, "JOINT_RESEARCH_LEAD_RULE_UNRESOLVED", "教学研发成员作为合作开发负责人时，两条计酬规则的衔接尚未明确");
        if (!issues.isEmpty()) return null;
        Grade effective = r.researchTeamMember() == Truth.YES ? Grade.LECTURER : r.instructorOrLeadGrade();
        RateRow row = snapshot().rows().stream().filter(x -> x.grade() == effective).findFirst().orElseThrow();
        BigDecimal value = r.activity() == Activity.TEACHING ? teachingRate(row, r.teachingDayType()) : row.soloDevelopment();
        return new Rate(r.instructorOrLeadGrade(), effective, r.activity(),
                r.activity() == Activity.TEACHING ? r.teachingDayType() : null, value,
                r.activity() == Activity.JOINT_DEVELOPMENT ? "ATTACHMENT-NOTE-2"
                        : r.researchTeamMember() == Truth.YES ? "ATTACHMENT-NOTE-1" : "ATTACHMENT-RATE-TABLE",
                r.activity() == Activity.JOINT_DEVELOPMENT);
    }

    private static boolean checkDevelopment(DevelopmentEvidence d, List<Issue> issues) {
        if (d == null) d = new DevelopmentEvidence(null, null, null, null, null, null, null, null);
        boolean denied = false;
        if (d.path() == null) add(issues, "DEVELOPMENT_PATH_MISSING", "须明确客户需求开发或自主开发路径");
        else if (d.path() == DevelopmentPath.CUSTOMER_REQUESTED) {
            denied |= requirement(d.customerWrittenPaymentAgreement(), "CUSTOMER_WRITTEN_PAYMENT_AGREEMENT_UNKNOWN",
                    "缺少客户明确提出开发需求并书面同意支付开发费的事实", "CUSTOMER_WRITTEN_PAYMENT_AGREEMENT_ABSENT",
                    "客户未明确需求并书面同意支付开发费", issues);
            denied |= requirement(d.customerAcceptance(), "CUSTOMER_ACCEPTANCE_UNKNOWN", "缺少客户验收事实",
                    "CUSTOMER_ACCEPTANCE_NOT_PASSED", "开发成果尚未通过客户验收", issues);
        } else {
            denied |= requirement(d.companyNeed(), "COMPANY_NEED_UNKNOWN", "缺少自主开发符合公司需要的核实事实",
                    "COMPANY_NEED_NOT_MET", "自主开发不符合公司需要", issues);
            denied |= requirement(d.annualReviewPassed(), "ANNUAL_REVIEW_UNKNOWN", "缺少年末评审通过事实",
                    "ANNUAL_REVIEW_NOT_PASSED", "自主开发尚未通过年末评审", issues);
        }
        denied |= requirement(d.companyApproval(), "DEVELOPMENT_COMPANY_APPROVAL_UNKNOWN", "缺少开发成果公司核准事实",
                "DEVELOPMENT_COMPANY_NOT_APPROVED", "开发成果尚未经公司核准", issues);
        return denied;
    }

    private static boolean requirement(Truth value, String unknownCode, String unknownMessage,
                                       String noCode, String noMessage, List<Issue> issues) {
        if (value == Truth.NO) { add(issues, noCode, noMessage); return true; }
        if (value == Truth.UNKNOWN) add(issues, unknownCode, unknownMessage);
        return false;
    }
    private static void pending(Truth value, String unknownCode, String unknownMessage,
                                String noCode, String noMessage, List<Issue> issues) {
        if (value == Truth.NO) add(issues, noCode, noMessage);
        else if (value == Truth.UNKNOWN) add(issues, unknownCode, unknownMessage);
    }
    private static BigDecimal rounded(BigDecimal value, MoneyRounding rounding, List<Issue> issues) {
        if (value == null) return null;
        try { return value.setScale(rounding.scale(), rounding.mode()); }
        catch (ArithmeticException ex) {
            add(issues, "MONEY_ROUNDING_NECESSARY_BUT_FORBIDDEN", "金额需要舍入，但指定方式为UNNECESSARY，不能形成最终金额");
            return null;
        }
    }
    private static List<Scenario> scenarios(RateRequest r, BigDecimal hours, boolean denied) {
        List<Scenario> result = new ArrayList<>();
        for (RateRow row : snapshot().rows()) {
            boolean joint = r.activity() == Activity.JOINT_DEVELOPMENT;
            // For joint work the research-member crossover has no defined alternative rate.
            if (joint) {
                if (r.instructorOrLeadGrade() != null && row.grade() != r.instructorOrLeadGrade()) continue;
            } else if (r.researchTeamMember() == Truth.YES) {
                if (row.grade() != Grade.LECTURER) continue;
            } else if (r.instructorOrLeadGrade() != null && row.grade() != r.instructorOrLeadGrade()
                    && !(r.researchTeamMember() == Truth.UNKNOWN && row.grade() == Grade.LECTURER)) continue;
            String condition = "条件费率表：仅在适用范围、计酬等级和研发身份核实后选用，不代表本次应付金额";
            boolean canCalculate = !denied && hours != null && !(joint && r.researchTeamMember() != Truth.NO);
            if (r.activity() == Activity.TEACHING) {
                for (DayType day : DayType.values()) {
                    if (r.teachingDayType() != null && day != r.teachingDayType()) continue;
                    BigDecimal rate = teachingRate(row, day);
                    result.add(new Scenario(row.grade(), r.activity(), day, rate, hours,
                            canCalculate ? rate.multiply(hours) : null, condition + "；日别须另经核实"));
                }
            } else result.add(new Scenario(row.grade(), r.activity(), null, row.soloDevelopment(), hours,
                    canCalculate ? row.soloDevelopment().multiply(hours) : null,
                    condition + (r.activity() == Activity.JOINT_DEVELOPMENT ? "；合作开发为总池，研发负责人交叉规则仍须明确" : "")));
        }
        return List.copyOf(result);
    }
    private static BigDecimal teachingRate(RateRow row, DayType day) {
        return switch (day) {
            case WORKDAY -> row.workdayTeaching(); case REST_DAY -> row.restDayTeaching();
            case STATUTORY_HOLIDAY -> row.statutoryHolidayTeaching();
        };
    }

    /** Deeply immutable JSON-ready map. All decimal hours, rates and amounts are strings. */
    public static Map<String, Object> view(Preview p) {
        Objects.requireNonNull(p);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", p.status().name()); m.put("rate", p.rate() == null ? null : rateView(p.rate()));
        m.put("payable_hours", decimal(p.payableHours())); m.put("raw_amount", decimal(p.rawAmount()));
        m.put("final_amount", decimal(p.finalAmount())); m.put("pool_raw_amount", decimal(p.poolRawAmount()));
        m.put("pool_final_amount", decimal(p.poolFinalAmount()));
        m.put("individual_raw_amount", decimal(p.individualRawAmount()));
        m.put("individual_final_amount", decimal(p.individualFinalAmount()));
        m.put("raw_amount_is_conditional", p.status() == Status.CONDITIONAL_PREVIEW);
        boolean developmentPool = p.rate() != null && p.rate().developmentPool()
                || p.scenarios().stream().anyMatch(s -> s.activity() == Activity.JOINT_DEVELOPMENT);
        m.put("amount_scope", p.status() == Status.HISTORY_PROTECTED ? null : developmentPool ? "DEVELOPMENT_POOL" : "INDIVIDUAL");
        m.put("issues", issueView(p.issues())); m.put("formal_missing_items", issueView(p.formalMissingItems()));
        m.put("scenarios", p.scenarios().stream().map(s -> {
            Map<String, Object> v = new LinkedHashMap<>(); v.put("reference_grade", s.referenceGrade().name());
            v.put("activity", s.activity().name()); v.put("teaching_day_type", name(s.teachingDayType()));
            v.put("unit_rate", decimal(s.unitRate())); v.put("payable_hours", decimal(s.payableHours()));
            v.put("raw_amount", decimal(s.rawAmount())); v.put("condition", s.condition());
            v.put("conditional", true); return immutable(v);
        }).toList());
        m.put("money_rounding", roundingView(p.moneyRounding())); m.put("can_confirm", false);
        m.put("preview_only", true); m.put("creates_configuration", false); m.put("creates_snapshot", false);
        m.put("execution_basis", basisView()); return immutable(m);
    }

    public static Map<String, Object> basisView() {
        SourceSnapshot s = snapshot(); Map<String, Object> m = new LinkedHashMap<>();
        m.put("execution_version", EXECUTION_VERSION); m.put("adoption_date", ADOPTION_DATE.toString());
        m.put("adoption_evidence", ADOPTION_EVIDENCE); m.put("adoption_decision", "用户明确按所提供管理办法执行");
        m.put("adoption_decision_reference", ADOPTION_DECISION_REFERENCE);
        m.put("adoption_evidence_is_technical_reference", true);
        m.put("adoption_evidence_description", "本地技术依据编码，仅引用已记录的用户决定，不是用户消息ID或正式公文");
        m.put("source_file_name", s.fileName()); m.put("source_sha256", s.sha256());
        m.put("source_consultation_draft", s.consultationDraft()); m.put("source_candidate_version", s.candidateVersion());
        m.put("source_official_document_number", s.officialDocumentNumber()); m.put("source_publication_date", null);
        m.put("effective_from", null); m.put("adoption_is_publication_date", false);
        m.put("retroactive_application_assumed", false); m.put("service_applicability_requires_host_verification", true);
        m.put("minutes_per_class_hour", MINUTES_PER_CLASS_HOUR); m.put("rate_unit", s.rateUnit());
        m.put("hour_conversion_version", DeliverySettlementHours.CURRENT_VERSION);
        m.put("hour_conversion_evidence", DeliverySettlementHours.USER_EVIDENCE);
        m.put("hour_rounding_determines_money_rounding", false);
        m.put("evaluation_multipliers_apply_to_money", false); return immutable(m);
    }
    private static Map<String, Object> rateView(Rate r) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("declared_grade", name(r.declaredGrade()));
        m.put("reference_grade", name(r.referenceGrade())); m.put("activity", r.activity().name());
        m.put("teaching_day_type", name(r.teachingDayType())); m.put("unit_rate", decimal(r.unitRate()));
        m.put("source_reference", r.sourceReference()); m.put("development_pool", r.developmentPool());
        m.put("rate_unit", "元／课时"); m.put("execution_version", EXECUTION_VERSION); return immutable(m);
    }
    private static Object roundingView(MoneyRounding r) {
        if (r == null) return null;
        return Map.of("scale", r.scale(), "mode", r.mode().name(), "scope", r.scope(), "evidence_code", r.evidenceCode());
    }
    private static List<Map<String, String>> issueView(List<Issue> issues) {
        return issues.stream().map(i -> Map.of("code", i.code(), "message", i.message())).toList();
    }
    private static void add(List<Issue> issues, String code, String message) {
        if (issues.stream().noneMatch(i -> i.code().equals(code))) issues.add(new Issue(code, message));
    }
    private static Map<String, Object> immutable(Map<String, Object> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
    private static Truth known(Truth t) { return t == null ? Truth.UNKNOWN : t; }
    private static String decimal(BigDecimal n) { return n == null ? null : n.toPlainString(); }
    private static String name(Enum<?> e) { return e == null ? null : e.name(); }
    private DeliverySettlementApprovedPolicy() {}
}
