package com.training;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** M02 pure validation/projection. No database, network, identity lookup or approval engine. */
public final class DemandIntake {
    private DemandIntake() {}

    public static final String RULE_VERSION = "M02-basic-hours-v3-20260922";
    public static final int MINUTES_PER_UNIT = 45;
    public static final int BASIC_HOURS_SCALE = 2;
    // Strictly aligned with DeliverySettlementHours.currentUserRule(); tested together.
    public static final String BASIC_HOURS_RULE_VERSION = "M05-CLASS45-2DP-V1";
    public static final String BASIC_HOURS_EVIDENCE = "USER-20260921-60MIN-1.33CLASS";
    public static final String BASIC_HOURS_ROUNDING_DECISION = "IMPLEMENTATION-2DP-HALF-UP";
    private static final int MAX_NUMBER_LENGTH = 120; // Transport limit, never a rounding rule.
    private static final Map<String, String> REQUIRED = Map.ofEntries(
        Map.entry("title", "需求名称"), Map.entry("business_path", "承接路径"),
        Map.entry("organization_code", "所属组织编码"),
        Map.entry("internal_contact_code", "内部对接人编码"),
        Map.entry("category_text", "授课分类"), Map.entry("delivery_mode_text", "授课方式"),
        Map.entry("period_text", "授课时段"), Map.entry("duration_minutes", "计划分钟数"),
        Map.entry("participant_count", "计划人数"), Map.entry("objectives", "培训目标"));
    private static final Set<String> FIELDS = Set.of(
        "title", "business_path", "organization_code", "internal_contact_code", "customer_contact_code",
        "category_text", "delivery_mode_text", "period_text", "duration_minutes", "participant_count",
        "budget_amount", "expected_start_date", "expected_end_date", "objectives",
        "external_approval_ref", "demand_code");

    public enum Capability { REGISTER_BID_RESULT, ACCEPT_TEAM }

    /** Construct only from a verified server session and M01 scope checks, never request JSON. */
    public record Access(String actorCode, Set<Capability> capabilities) {
        public Access { capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities); }
        boolean allows(Capability capability) {
            return actorCode != null && !actorCode.isBlank() && capabilities.contains(capability);
        }
    }

    public record Issue(String field, String code, String message) {
        public Map<String, Object> toMap() { return Map.of("field", field, "code", code, "message", message); }
    }

    public record Validation(Map<String, String> values, List<Issue> errors, List<String> notices) {
        public Validation {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            errors = List.copyOf(errors);
            notices = List.copyOf(notices);
        }
        public boolean valid() { return errors.isEmpty(); }
        public Map<String, Object> toMap() {
            return Map.of("valid", valid(), "values", values, "errors", errors.stream().map(Issue::toMap).toList(),
                "notices", notices, "rule_version", RULE_VERSION);
        }
    }

    /** Drafts may be incomplete; every supplied value must still be well formed. */
    public static Validation validateDraft(Map<String, ?> input, String authenticatedFillerCode) {
        return validate(input, authenticatedFillerCode, false);
    }

    /** User-authorized starting defaults, editable before submission; not an approval or identity. */
    public static Map<String, String> initialDraft() {
        return Map.of("business_path", "direct", "period_text", "待协调");
    }

    /** User-authorized provisional required fields; customer contact can be completed later. */
    public static Validation validateForSubmission(Map<String, ?> input, String authenticatedFillerCode) {
        return validate(input, authenticatedFillerCode, true);
    }

    private static Validation validate(Map<String, ?> input, String fillerCode, boolean complete) {
        List<Issue> errors = new ArrayList<>();
        Map<String, String> values = read(input, FIELDS, errors);
        if (fillerCode == null || fillerCode.isBlank()) {
            error(errors, "filler_code", "session_required", "实际填报人须由已核验的内部会话提供");
        } else {
            values.put("filler_code", fillerCode.strip());
        }
        if (complete) REQUIRED.forEach((field, label) -> require(values, errors, field, label));
        String path = values.getOrDefault("business_path", "");
        if (!path.isEmpty() && !Set.of("direct", "bid").contains(path))
            error(errors, "business_path", "invalid_choice", "承接路径只能为直接承接或投标");
        if (complete && path.equals("bid")) require(values, errors, "external_approval_ref", "原办公签报编号");
        if (path.equals("direct") && !values.getOrDefault("external_approval_ref", "").isEmpty())
            error(errors, "external_approval_ref", "path_conflict", "直接承接不填写投标签报编号");
        decimal(values, errors, "duration_minutes", true, false);
        decimal(values, errors, "participant_count", true, true);
        decimal(values, errors, "budget_amount", false, false);
        LocalDate start = date(values, errors, "expected_start_date");
        LocalDate end = date(values, errors, "expected_end_date");
        if (start != null && end != null && end.isBefore(start))
            error(errors, "expected_end_date", "date_order", "结束日期不能早于开始日期");
        return new Validation(values, errors, List.of(
            "客户对接人可后补；新需求分类采用亲子财商、养老丰润、服务资格认证、其他类培训，已有历史分类保留。",
            "基本课时按分钟÷45保留两位、四舍五入（HALF_UP），同时保留原始分钟；基本课时不等于计酬课时。",
            "正式组织、人员、团队及编码须由 M01 和宿主核验；本模块不生成正式编码。"));
    }

    /** Existing exact components stay internal; basicClassHours is the two-decimal business display. */
    public record CourseUnits(String originalMinutes, String numerator, String denominator, String exactDecimal) {
        /** Same arithmetic as M05; retains the existing broader M02 input range. Never payable hours. */
        public String basicClassHours() {
            return new BigDecimal(originalMinutes).divide(BigDecimal.valueOf(MINUTES_PER_UNIT),
                BASIC_HOURS_SCALE, RoundingMode.HALF_UP).toPlainString();
        }
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("original_minutes", originalMinutes);
            result.put("minutes_per_unit", MINUTES_PER_UNIT);
            result.put("numerator", numerator);
            result.put("denominator", denominator);
            result.put("exact_decimal", exactDecimal);
            result.put("class_hours", basicClassHours());
            result.put("class_hour_scale", BASIC_HOURS_SCALE);
            result.put("class_hour_rounding", RoundingMode.HALF_UP.name());
            result.put("rounding_policy", RoundingMode.HALF_UP.name());
            result.put("rule_version", BASIC_HOURS_RULE_VERSION);
            result.put("evidence_code", BASIC_HOURS_EVIDENCE);
            result.put("rounding_decision_code", BASIC_HOURS_ROUNDING_DECISION);
            return result;
        }
    }

    public static CourseUnits courseUnits(String minutes) {
        if (minutes == null || minutes.length() > MAX_NUMBER_LENGTH || !minutes.matches("[0-9]+(?:\\.[0-9]+)?"))
            throw new IllegalArgumentException("分钟数必须是普通十进制字符串");
        BigDecimal value = new BigDecimal(minutes);
        if (value.signum() <= 0) throw new IllegalArgumentException("分钟数必须大于零");
        BigInteger numerator = value.unscaledValue();
        BigInteger denominator = BigInteger.TEN.pow(value.scale()).multiply(BigInteger.valueOf(MINUTES_PER_UNIT));
        BigInteger divisor = numerator.gcd(denominator);
        numerator = numerator.divide(divisor);
        denominator = denominator.divide(divisor);
        String exact = null;
        try { exact = value.divide(BigDecimal.valueOf(MINUTES_PER_UNIT)).stripTrailingZeros().toPlainString(); }
        catch (ArithmeticException repeating) { /* Fraction is the complete exact value. */ }
        return new CourseUnits(minutes, numerator.toString(), denominator.toString(), exact);
    }

    /** Display-only route. Approval state and decision events belong exclusively to M03. */
    public static List<String> route(String path) {
        return switch (path == null ? "" : path) {
            case "direct" -> List.of("内部填报", "分公司负责人先审", "BP后审", "培训团队承接");
            case "bid" -> List.of("内部填报", "沿用原办公签报", "登记投标结果", "中标：培训团队承接；未中标：留档");
            default -> throw new IllegalArgumentException("承接路径未选择或无效");
        };
    }

    /** All facts are loaded inside the host mutation lock from persisted rows/M03, never client flags. */
    public record Readiness(long demandId, String businessPath, boolean draft,
                            boolean directApprovedByM03, String bidResult, boolean alreadyAccepted) {}

    public static String queueDisposition(Readiness facts) {
        if (facts == null || facts.demandId() <= 0 || !Set.of("direct", "bid").contains(Objects.toString(facts.businessPath(), "")))
            return "inconsistent";
        String result = Objects.toString(facts.bidResult(), "");
        if ((!result.isEmpty() && !Set.of("won", "lost").contains(result)) ||
            (facts.businessPath().equals("direct") && !result.isEmpty())) return "inconsistent";
        if (facts.draft()) return (facts.alreadyAccepted() || !result.isEmpty()) ? "inconsistent" : "draft";
        boolean ready = facts.businessPath().equals("direct") ? facts.directApprovedByM03() : result.equals("won");
        if (facts.alreadyAccepted()) return ready ? "accepted" : "inconsistent";
        if (ready) return "ready";
        if (facts.businessPath().equals("direct")) return "waiting_approval";
        return result.equals("lost") ? "archived_lost" : "waiting_bid_result";
    }

    /** Register a result from the existing office process; this never makes an approval decision. */
    public static Validation validateBidResult(Readiness facts, Map<String, ?> input, Access access) {
        List<Issue> errors = new ArrayList<>();
        Map<String, String> values = read(input, Set.of("result", "external_approval_ref", "result_date", "result_note"), errors);
        if (access == null || !access.allows(Capability.REGISTER_BID_RESULT))
            error(errors, "authorization", "forbidden", "当前会话未获投标结果登记权限");
        else values.put("recorded_by_code", access.actorCode());
        if (facts == null || facts.demandId() <= 0 || !"bid".equals(facts.businessPath()))
            error(errors, "business_path", "path_conflict", "仅投标需求可登记投标结果，直接承接无需中标记录");
        else if (facts.draft()) error(errors, "status", "not_submitted", "草稿不能登记投标结果");
        else if (facts.alreadyAccepted() || !Objects.toString(facts.bidResult(), "").isEmpty())
            error(errors, "result", "already_recorded", "已登记的结果不能重复登记或直接覆盖");
        require(values, errors, "external_approval_ref", "原办公签报编号");
        require(values, errors, "result_date", "结果确认日期");
        date(values, errors, "result_date");
        if (!Set.of("won", "lost").contains(values.getOrDefault("result", "")))
            error(errors, "result", "invalid_choice", "投标结果须为中标或未中标");
        return new Validation(values, errors, List.of("登记权限及确认岗位待业务配置；须核验签报关联关系。"));
    }

    public static Validation validateTeamAcceptance(Readiness facts, String teamCode, Access access) {
        List<Issue> errors = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        if (access == null || !access.allows(Capability.ACCEPT_TEAM))
            error(errors, "authorization", "forbidden", "当前会话未获团队受理权限");
        else values.put("accepted_by_code", access.actorCode());
        if (teamCode == null || teamCode.isBlank())
            error(errors, "team_code", "required", "须提供经核验的承接团队编码");
        else values.put("team_code", teamCode.strip());
        String disposition = queueDisposition(facts);
        if (!disposition.equals("ready"))
            error(errors, "status", disposition.equals("accepted") ? "already_accepted" : "not_ready",
                disposition.equals("accepted") ? "需求已经受理，不可重复接单" : "审批或投标结果尚不满足团队承接条件");
        return new Validation(values, errors, List.of("宿主须核验受理人对该团队和需求的权限；负责人先审、BP后审由 M03 保证。"));
    }

    private static Map<String, String> read(Map<String, ?> input, Set<String> fields, List<Issue> errors) {
        Map<String, String> values = new LinkedHashMap<>();
        if (input == null) {
            error(errors, "body", "required", "需求内容不能为空");
            return values;
        }
        input.forEach((key, raw) -> {
            if (key == null || !fields.contains(key)) {
                error(errors, Objects.toString(key, "body"), "unsupported_field", "不允许由表单写入该字段");
            } else if (raw == null) values.put(key, "");
            else if (!(raw instanceof String)) {
                error(errors, key, "invalid_type", "字段须使用字符串，数字须保留原始十进制文本");
            } else {
                String value = ((String) raw).strip();
                int limit = key.endsWith("_code") ? 120 : (key.equals("objectives") || key.equals("result_note") ? 4000 : 500);
                if (value.length() > limit) error(errors, key, "too_long", "内容超过字段长度上限 " + limit);
                else values.put(key, value);
            }
        });
        return values;
    }

    private static void decimal(Map<String, String> values, List<Issue> errors, String field, boolean positive, boolean integer) {
        String value = values.getOrDefault(field, "");
        if (value.isEmpty()) return;
        if (value.length() > MAX_NUMBER_LENGTH || !value.matches(integer ? "[0-9]+" : "[0-9]+(?:\\.[0-9]+)?")) {
            error(errors, field, "invalid_number", integer ? "请输入不含小数的正整数" : "请输入不含负号、指数或特殊值的十进制数");
            return;
        }
        if (positive && new BigDecimal(value).signum() <= 0) error(errors, field, "out_of_range", "必须大于零");
    }

    private static LocalDate date(Map<String, String> values, List<Issue> errors, String field) {
        String value = values.getOrDefault(field, "");
        if (value.isEmpty()) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("format", value, 0);
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() == 0) throw new DateTimeParseException("year", value, 0);
            return parsed;
        } catch (DateTimeParseException invalid) {
            error(errors, field, "invalid_date", "请输入有效日期（YYYY-MM-DD）");
            return null;
        }
    }

    private static void require(Map<String, String> values, List<Issue> errors, String field, String label) {
        if (values.getOrDefault(field, "").isEmpty()) error(errors, field, "required", "请填写" + label);
    }
    private static void error(List<Issue> errors, String field, String code, String message) { errors.add(new Issue(field, code, message)); }
}
