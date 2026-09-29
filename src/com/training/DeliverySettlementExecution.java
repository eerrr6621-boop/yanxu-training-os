package com.training;

import static com.training.DeliverySettlement.*;
import static com.training.DeliverySettlementPolicy.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure teaching execution adapter for the adopted policy. The host owns identity, authorization,
 * review, persistence and frozen-history protection. Parsing a claim does not approve that claim.
 * Each case must explicitly establish applicability; there is no inferred global effective date.
 */
public final class DeliverySettlementExecution {
    public static final String EXECUTION_VERSION = DeliverySettlementApprovedPolicy.EXECUTION_VERSION;
    public static final String ADOPTION_EVIDENCE = DeliverySettlementApprovedPolicy.ADOPTION_EVIDENCE;
    public static final String RULE_VERSION_PREFIX = "M05-C7E8BAAA-V1-RULE:";
    public static final String RATE_VERSION_PREFIX = "M05-C7E8BAAA-V1-RATE:";
    public static final String CORRECTION_VERSION_PREFIX = "M05-C7E8BAAA-V1-CORRECTION:";
    public static final int AMOUNT_SCALE = 2;
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
    private static final Set<String> CLAIM_FIELDS = Set.of("grade", "day_type", "research_team",
            "appointed_on", "annual_plan", "customer_paid", "service_date_applicable", "evidence");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("grade", "day_type", "research_team",
            "appointment", "annual_plan", "customer_paid", "applicability");
    private static final Set<String> SETTINGS_FIELDS = Set.of("version", "evidence_code", "amount_scale",
            "rounding_mode", "allow_unpaid_correction");

    private DeliverySettlementExecution() {}

    /** Immutable JSON-ready values. No client fee, rate, identity or approval flags are accepted. */
    public static Map<String, Object> parseClaim(Map<String, Object> input) {
        strict(input, CLAIM_FIELDS, "申报");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("grade", enumText(input.get("grade"), Grade.class, "讲师等级", null));
        result.put("day_type", enumText(input.get("day_type"), DayType.class, "授课日别", null));
        result.put("research_team", enumText(input.get("research_team"), Truth.class, "研发团队身份", "UNKNOWN"));
        result.put("appointed_on", dateText(input.get("appointed_on"), "获聘日期"));
        result.put("annual_plan", enumText(input.get("annual_plan"), Truth.class, "年度计划", "UNKNOWN"));
        result.put("customer_paid", enumText(input.get("customer_paid"), Truth.class, "客户付费", "UNKNOWN"));
        result.put("service_date_applicable", enumText(input.get("service_date_applicable"), Truth.class,
                "本次业务适用性", "UNKNOWN"));
        Object rawEvidence = input.get("evidence");
        Map<String, Object> evidence = rawEvidence == null ? Map.of() : objectMap(rawEvidence, "事实依据");
        strict(evidence, EVIDENCE_FIELDS, "事实依据");
        Map<String, Object> normalizedEvidence = new LinkedHashMap<>();
        for (String key : List.of("grade", "day_type", "research_team", "appointment", "annual_plan", "customer_paid", "applicability"))
            if (evidence.get(key) != null) normalizedEvidence.put(key, technicalCode(evidence.get(key), "事实依据", 96));
        result.put("evidence", immutable(normalizedEvidence));
        return immutable(result);
    }

    /** Reasons are suitable for host display, and never contain client-supplied text. */
    public static List<String> eligibility(Fact fact, Map<String, Object> claim) {
        Objects.requireNonNull(fact, "授课事实不能为空");
        Map<String, Object> c = parseClaim(claim);
        Map<String, Object> evidence = objectMap(c.get("evidence"), "事实依据");
        List<String> reasons = new ArrayList<>();
        if (fact.serviceDate() == null) reasons.add("缺少明确授课日期");
        if (fact.sessionCode() == null) reasons.add("缺少授课场次编码");
        if (fact.instructorCode() == null) reasons.add("缺少讲师编码");
        if (fact.organizationCode() == null) reasons.add("缺少主办单位编码");
        if (fact.hours().actual() == null) reasons.add("缺少已核实的实际课时");
        if (fact.hours().payable() == null) reasons.add("缺少明确计酬课时，不能用实际课时代替");
        if (fact.verification() == null) reasons.add("授课事实尚未核验");
        Truth research = truth(c, "research_team");
        if (research == Truth.UNKNOWN) reasons.add("须核实教学研发团队成员身份");
        else requireEvidence(reasons, evidence, "research_team", "研发团队身份");
        if (research != Truth.YES) {
            if (c.get("grade") == null) reasons.add("缺少已核实的讲师等级");
            else requireEvidence(reasons, evidence, "grade", "讲师等级");
        }
        if (c.get("day_type") == null) reasons.add("须明确工作日、休息日或法定节假日");
        else requireEvidence(reasons, evidence, "day_type", "授课日别");
        String appointed = (String) c.get("appointed_on");
        if (appointed == null) reasons.add("缺少获聘日期");
        else {
            requireEvidence(reasons, evidence, "appointment", "获聘日期");
            if (fact.serviceDate() != null && LocalDate.parse(appointed).isAfter(fact.serviceDate()))
                reasons.add("获聘前开展的授课不符合支付条件");
        }
        Truth annual = truth(c, "annual_plan"), customer = truth(c, "customer_paid");
        if (annual != Truth.UNKNOWN) requireEvidence(reasons, evidence, "annual_plan", "年度培训计划");
        if (customer != Truth.UNKNOWN) requireEvidence(reasons, evidence, "customer_paid", "客户付费");
        if (annual != Truth.YES && customer != Truth.YES)
            reasons.add(annual == Truth.NO && customer == Truth.NO
                    ? "项目既不在年度培训计划内，也非客户付费项目" : "须核实项目属于年度培训计划或客户付费范围");
        Truth applicable = truth(c, "service_date_applicable");
        if (applicable == Truth.UNKNOWN) reasons.add("须逐案核准本次业务适用已批准执行依据");
        else {
            requireEvidence(reasons, evidence, "applicability", "本次业务适用性");
            if (applicable == Truth.NO) reasons.add("本次业务不适用该执行依据");
        }
        return List.copyOf(reasons);
    }

    /** All settings are explicit. The user's adopted money rule is CNY 2dp HALF_UP per line. */
    public static Map<String, Object> parseSettings(Map<String, Object> input) {
        strict(input, SETTINGS_FIELDS, "执行设置");
        if (!input.keySet().equals(SETTINGS_FIELDS)) throw new IllegalArgumentException("执行设置须明确提供全部五个字段");
        String version = technicalCode(input.get("version"), "执行设置版本", 96);
        String evidence = technicalCode(input.get("evidence_code"), "执行设置依据", 96);
        int scale = explicitInteger(input.get("amount_scale"));
        String rounding = enumText(input.get("rounding_mode"), RoundingMode.class, "金额舍入方式", null);
        if (scale != AMOUNT_SCALE || !MONEY_ROUNDING.name().equals(rounding))
            throw new IllegalArgumentException("已批准金额规则为人民币每条明细保留两位并按HALF_UP四舍五入后汇总");
        if (!(input.get("allow_unpaid_correction") instanceof Boolean))
            throw new IllegalArgumentException("未支付更正设置须为明确布尔值");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version); result.put("evidence_code", evidence);
        result.put("amount_scale", scale); result.put("rounding_mode", rounding);
        result.put("allow_unpaid_correction", input.get("allow_unpaid_correction"));
        return immutable(result);
    }

    public record Executable(Fact fact, Map<String, Object> configuration) {
        public Executable { Objects.requireNonNull(fact); configuration = immutable(configuration); }
    }

    /** Host calls only after its authorized review and current-revision checks. No persistence occurs. */
    public static Executable build(Fact original, Map<String, Object> claim, Map<String, Object> settings,
                                   String configurationVersion, String eligibilityEvidence) {
        Objects.requireNonNull(original, "授课事实不能为空");
        Map<String, Object> c = parseClaim(claim), s = parseSettings(settings);
        List<String> reasons = eligibility(original, c);
        if (!reasons.isEmpty()) throw new IllegalArgumentException(String.join("；", reasons));
        if (original.dataMode() != DataMode.CONFIGURED)
            throw new IllegalArgumentException("正式执行须使用已接入的真实业务事实模式");
        String version = technicalCode(configurationVersion, "逐案配置版本", 60);
        if (DeliverySettlementPolicy.CANDIDATE_VERSION.equals(version))
            throw new IllegalArgumentException("候选预览版本不能作为正式逐案配置版本");
        String eligibilityRef = technicalCode(eligibilityEvidence, "逐案核准依据", 96);
        LocalDate from = original.serviceDate();
        LocalDate until;
        try { until = from.plusDays(1); }
        catch (java.time.DateTimeException ex) { throw new IllegalArgumentException("授课日期超出逐案适用区间范围", ex); }
        dateText(from.toString(), "逐案适用起始日期");
        dateText(until.toString(), "逐案适用结束日期");
        Grade grade = c.get("grade") == null ? null : Grade.valueOf((String) c.get("grade"));
        DayType day = DayType.valueOf((String) c.get("day_type"));
        DeliverySettlementApprovedPolicy.Preview preview = DeliverySettlementApprovedPolicy.preview(
                new DeliverySettlementApprovedPolicy.Request(new RateRequest(Activity.TEACHING, grade, day, truth(c, "research_team")),
                        new VerifiedFacts(Truth.YES, truth(c, "annual_plan"), truth(c, "customer_paid"), null, Truth.UNKNOWN),
                        original.hours().payable(), Truth.YES, Truth.YES, Truth.YES, Truth.YES, false, false, null,
                        new DeliverySettlementApprovedPolicy.MoneyRounding(AMOUNT_SCALE, MONEY_ROUNDING,
                                ROUNDING_SCOPE, (String) s.get("evidence_code"))));
        if (preview.status() != DeliverySettlementApprovedPolicy.Status.PREVIEW_READY
                || !preview.issues().isEmpty() || preview.rate() == null || preview.finalAmount() == null)
            throw new IllegalArgumentException("已批准依据未形成可执行的逐案费率及金额");
        Dimensions dimensions = new Dimensions(preview.rate().referenceGrade().name(), day.name(), "TEACHING", "CLASS45");
        Fact fact = new Fact(original.recordId(), original.revision(), original.sessionCode(), original.instructorCode(),
                original.organizationCode(), original.serviceDate(), dimensions, original.hours(), original.verification(), original.dataMode());
        String ruleVersion = technicalCode(RULE_VERSION_PREFIX + version, "规则版本", 96);
        String rateVersion = technicalCode(RATE_VERSION_PREFIX + version, "费率版本", 96);
        Map<String, Object> rule = period(ruleVersion, (String) s.get("evidence_code"), from, until);
        rule.put("date_basis", DATE_BASIS); rule.put("formula", FORMULA); rule.put("currency", "CNY");
        rule.put("amount_scale", AMOUNT_SCALE); rule.put("rounding_mode", MONEY_ROUNDING.name());
        rule.put("rounding_scope", ROUNDING_SCOPE); rule.put("minutes_per_class_hour", MINUTES_PER_CLASS_HOUR);
        Map<String, Object> rate = period(rateVersion, EXECUTION_VERSION, from, until);
        rate.put("rule_version", ruleVersion);
        rate.put("dimensions", Map.of("grade", dimensions.gradeCode(), "time_band", dimensions.timeBandCode(),
                "form", dimensions.formCode(), "hour_unit", dimensions.hourUnitCode()));
        rate.put("unit_rate", preview.rate().unitRate().toPlainString());
        Map<String, Object> correction = null;
        if (Boolean.TRUE.equals(s.get("allow_unpaid_correction"))) {
            correction = period(technicalCode(CORRECTION_VERSION_PREFIX + version, "更正版本", 96),
                    (String) s.get("evidence_code"), from, until);
            correction.put("date_basis", DATE_BASIS); correction.put("method", "REPLACEMENT_DELTA");
        }
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("version", version); configuration.put("publication_evidence", ADOPTION_EVIDENCE);
        configuration.put("eligibility_evidence", eligibilityRef); configuration.put("approval_status", "APPROVED");
        configuration.put("rule", immutable(rule)); configuration.put("rate", immutable(rate));
        configuration.put("correction", correction == null ? null : immutable(correction));
        RuleVersion executableRule = new RuleVersion(ruleVersion, (String) s.get("evidence_code"), from, until,
                DATE_BASIS, FORMULA, "CNY", AMOUNT_SCALE, MONEY_ROUNDING, ROUNDING_SCOPE, MINUTES_PER_CLASS_HOUR, DataMode.CONFIGURED);
        RateVersion executableRate = new RateVersion(rateVersion, EXECUTION_VERSION, ruleVersion, from, until,
                dimensions, preview.rate().unitRate(), DataMode.CONFIGURED);
        Calculation checked = calculate(fact, executableRule, executableRate);
        if (checked.status() != DeliverySettlement.Status.READY || checked.amount().compareTo(preview.finalAmount()) != 0)
            throw new IllegalArgumentException("正式计算与已批准依据的逐案结果不一致");
        return new Executable(fact, immutable(configuration));
    }

    private static Map<String, Object> period(String version, String evidence, LocalDate from, LocalDate until) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", version); map.put("evidence", evidence);
        map.put("effective_from", from.toString()); map.put("effective_until", until.toString());
        return map;
    }
    private static Truth truth(Map<String, Object> c, String key) { return Truth.valueOf((String) c.get(key)); }
    private static void requireEvidence(List<String> reasons, Map<String, Object> evidence, String key, String label) {
        if (evidence.get(key) == null) reasons.add("缺少" + label + "的核实依据编码");
    }
    private static void strict(Map<String, Object> input, Set<String> fields, String label) {
        if (input == null) throw new IllegalArgumentException(label + "须为对象");
        for (Object key : input.keySet()) if (!(key instanceof String) || !fields.contains(key))
            throw new IllegalArgumentException(label + "含有不允许提交的字段");
    }
    private static Map<String, Object> objectMap(Object raw, String label) {
        if (!(raw instanceof Map<?, ?> values)) throw new IllegalArgumentException(label + "须为对象");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException(label + "字段须为字符串");
            copy.put((String) entry.getKey(), entry.getValue());
        }
        return copy;
    }
    private static String technicalCode(Object raw, String label, int limit) {
        if (!(raw instanceof String value) || value.length() > limit || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*"))
            throw new IllegalArgumentException(label + "须为不超过" + limit + "字符的有效技术编码");
        return value;
    }
    private static <E extends Enum<E>> String enumText(Object raw, Class<E> type, String label, String fallback) {
        if (raw == null) return fallback;
        if (!(raw instanceof String value)) throw new IllegalArgumentException(label + "须为规定的枚举字符串");
        try { return Enum.valueOf(type, value).name(); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException(label + "值无效"); }
    }
    private static String dateText(Object raw, String label) {
        if (raw == null) return null;
        if (!(raw instanceof String value) || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException(label + "须为YYYY-MM-DD日期");
        try { return LocalDate.parse(value).toString(); }
        catch (DateTimeParseException ex) { throw new IllegalArgumentException(label + "无效"); }
    }
    private static int explicitInteger(Object raw) {
        // JSON numbers reach some hosts as Double; accept only an exact finite integer in 0..8.
        if (!(raw instanceof Number value)) throw new IllegalArgumentException("金额位数须为明确的0至8整数");
        try {
            BigDecimal decimal = value instanceof BigDecimal d ? d : value instanceof BigInteger i
                    ? new BigDecimal(i) : new BigDecimal(value.toString());
            int result = decimal.intValueExact();
            if (result < 0 || result > 8) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("金额位数须为明确的0至8整数");
        }
    }
    private static Map<String, Object> immutable(Map<String, Object> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
