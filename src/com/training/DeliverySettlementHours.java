package com.training;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact decimal duration conversion. This does not price teaching or choose payable hours. */
public final class DeliverySettlementHours {
    public static final String CURRENT_VERSION = "M05-CLASS45-2DP-V1";
    public static final String USER_EVIDENCE = "USER-20260921-60MIN-1.33CLASS";
    public static final String ROUNDING_DECISION = "IMPLEMENTATION-2DP-HALF-UP";
    public enum Status { READY, NOT_CONFIGURED, INCOMPLETE }

    /** User supplied 45 minutes and 60 -> 1.33; HALF_UP is the explicit implementation choice. */
    public record Rule(String versionCode, Integer minutesPerClassHour, Integer scale,
                       RoundingMode roundingMode, String evidenceCode, String roundingDecisionCode) {
        public Rule {
            optionalCode(versionCode); optionalCode(evidenceCode); optionalCode(roundingDecisionCode);
            if (minutesPerClassHour != null && minutesPerClassHour <= 0)
                throw new IllegalArgumentException("每课时分钟数必须为正整数");
            if (scale != null && (scale < 0 || scale > 8))
                throw new IllegalArgumentException("课时小数位超出当前支持范围0..8");
        }
    }

    public static final class Conversion {
        private final Status status;
        private final BigDecimal minutes;
        private final BigDecimal classHours;
        private final Rule rule;
        private final List<String> issues;
        private Conversion(Status status, BigDecimal minutes, BigDecimal classHours, Rule rule, List<String> issues) {
            this.status = status; this.minutes = minutes; this.classHours = classHours;
            this.rule = rule; this.issues = List.copyOf(issues);
        }
        public Status status() { return status; }
        public BigDecimal minutes() { return minutes; }
        public BigDecimal classHours() { return classHours; }
        public Rule rule() { return rule; }
        public List<String> issues() { return issues; }
        public boolean rounded() {
            return status == Status.READY && classHours.multiply(BigDecimal.valueOf(rule.minutesPerClassHour())).compareTo(minutes) != 0;
        }
    }

    /** Keep this basis alongside the new fact revision. The original fact/snapshot is untouched. */
    public record ActualUpdate(DeliverySettlement.Fact fact, Conversion conversion) {}

    public static Rule currentUserRule() {
        return new Rule(CURRENT_VERSION, 45, 2, RoundingMode.HALF_UP, USER_EVIDENCE, ROUNDING_DECISION);
    }

    public static Conversion convert(Object minutesInput, Rule rule) {
        BigDecimal minutes = DeliverySettlement.decimalInput(minutesInput);
        List<String> configuration = new ArrayList<>();
        if (rule == null) configuration.add("HOUR_CONVERSION_RULE_NOT_CONFIGURED");
        else {
            missing(configuration, rule.versionCode(), "HOUR_CONVERSION_VERSION_NOT_CONFIGURED");
            missing(configuration, rule.minutesPerClassHour(), "CLASS_HOUR_DEFINITION_NOT_CONFIGURED");
            missing(configuration, rule.scale(), "CLASS_HOUR_SCALE_NOT_CONFIGURED");
            missing(configuration, rule.roundingMode(), "CLASS_HOUR_ROUNDING_NOT_CONFIGURED");
            missing(configuration, rule.evidenceCode(), "CLASS_HOUR_EVIDENCE_NOT_CONFIGURED");
            missing(configuration, rule.roundingDecisionCode(), "CLASS_HOUR_ROUNDING_DECISION_NOT_CONFIGURED");
        }
        List<String> issues = new ArrayList<>(configuration);
        missing(issues, minutes, "MINUTES_MISSING");
        if (!issues.isEmpty()) return new Conversion(configuration.isEmpty() ? Status.INCOMPLETE : Status.NOT_CONFIGURED,
                minutes, null, rule, issues);
        try {
            BigDecimal result = minutes.divide(BigDecimal.valueOf(rule.minutesPerClassHour()), rule.scale(), rule.roundingMode());
            if (result.precision() > 24)
                return new Conversion(Status.INCOMPLETE, minutes, null, rule, List.of("CLASS_HOUR_RESULT_OUT_OF_RANGE"));
            return new Conversion(Status.READY, minutes, result, rule, List.of());
        } catch (ArithmeticException e) {
            return new Conversion(Status.INCOMPLETE, minutes, null, rule, List.of("HOUR_ROUNDING_NECESSARY_BUT_FORBIDDEN"));
        }
    }

    /** Explicitly apply to actual hours only. A new revision always requires teaching verification again. */
    public static ActualUpdate applyToActual(DeliverySettlement.Fact source, Conversion conversion, long nextRevision) {
        Objects.requireNonNull(source); Objects.requireNonNull(conversion);
        if (conversion.status() != Status.READY) throw new IllegalStateException("分钟换算尚未完成");
        if (nextRevision <= source.revision()) throw new IllegalArgumentException("实际课时修订号必须递增");
        DeliverySettlement.Hours old = source.hours();
        DeliverySettlement.Hours updated = new DeliverySettlement.Hours(old.estimated(), old.planned(), conversion.classHours(), old.payable());
        DeliverySettlement.Fact fact = new DeliverySettlement.Fact(source.recordId(), nextRevision, source.sessionCode(),
                source.instructorCode(), source.organizationCode(), source.serviceDate(), source.dimensions(), updated,
                null, source.dataMode());
        return new ActualUpdate(fact, conversion);
    }

    public static Map<String,Object> view(Conversion conversion) {
        Objects.requireNonNull(conversion); Map<String,Object> m = new LinkedHashMap<>();
        m.put("status", conversion.status().name()); m.put("issues", conversion.issues());
        m.put("minutes", text(conversion.minutes())); m.put("class_hours", text(conversion.classHours()));
        m.put("rounded", conversion.status() == Status.READY ? conversion.rounded() : null);
        Rule r = conversion.rule();
        m.put("rule_version", r == null ? null : r.versionCode());
        m.put("minutes_per_class_hour", r == null ? null : r.minutesPerClassHour());
        m.put("class_hour_scale", r == null ? null : r.scale());
        m.put("class_hour_rounding", r == null || r.roundingMode() == null ? null : r.roundingMode().name());
        m.put("evidence_code", r == null ? null : r.evidenceCode());
        m.put("rounding_decision_code", r == null ? null : r.roundingDecisionCode());
        return m;
    }
    private static String text(BigDecimal v) { return v == null ? null : v.toPlainString(); }
    private static void missing(List<String> issues, Object value, String code) { if (value == null) issues.add(code); }
    private static void optionalCode(String v) {
        if (v != null && !v.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")) throw new IllegalArgumentException("无效版本或依据编码");
    }
    private DeliverySettlementHours() {}
}
