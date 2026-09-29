package com.training;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** M05 pure calculation boundary. No database, default tariffs, identity lookup or HTTP routes. */
public final class DeliverySettlement {
    public static final String ENGINE_VERSION = "M05-HOURLY-1";
    public static final String FORMULA = "PAYABLE_HOURS_TIMES_RATE";
    public static final String DATE_BASIS = "SERVICE_DATE";
    public static final String ROUNDING_SCOPE = "PER_LINE";

    public enum DataMode { SYNTHETIC_DEMO, CONFIGURED }
    public enum Status { READY, NOT_CONFIGURED, INCOMPLETE }
    public enum Permission { CONFIRM, CORRECT, EXPORT }

    /** Unknown is null. In particular, actual hours never populate payable hours. */
    public record Hours(BigDecimal estimated, BigDecimal planned, BigDecimal actual, BigDecimal payable) {
        public Hours {
            decimal(estimated, "estimated"); decimal(planned, "planned");
            decimal(actual, "actual"); decimal(payable, "payable");
        }
    }

    public record Dimensions(String gradeCode, String timeBandCode, String formCode, String hourUnitCode) {
        public Dimensions {
            codeIfPresent(gradeCode); codeIfPresent(timeBandCode);
            codeIfPresent(formCode); codeIfPresent(hourUnitCode);
        }
        boolean complete() { return gradeCode != null && timeBandCode != null && formCode != null && hourUnitCode != null; }
    }

    /** The host must create this from an authorized teaching verification, never from a client flag. */
    public record Verification(String actorCode, Instant checkedAt, String evidenceCode) {
        public Verification { code(actorCode); Objects.requireNonNull(checkedAt); code(evidenceCode); }
    }

    public record Fact(long recordId, long revision, String sessionCode, String instructorCode,
                       String organizationCode, LocalDate serviceDate, Dimensions dimensions,
                       Hours hours, Verification verification, DataMode dataMode) {
        public Fact {
            if (recordId <= 0 || revision <= 0) throw new IllegalArgumentException("记录ID与修订号必须为正整数");
            codeIfPresent(sessionCode); codeIfPresent(instructorCode); codeIfPresent(organizationCode);
            Objects.requireNonNull(hours); Objects.requireNonNull(dataMode);
        }
    }

    /** Both dates apply to the explicitly selected SERVICE_DATE basis; until is exclusive. */
    public record RuleVersion(String versionCode, String evidenceCode, LocalDate effectiveFrom,
                              LocalDate effectiveUntil, String dateBasis, String formula,
                              String currency, Integer amountScale, RoundingMode roundingMode,
                              String roundingScope, Integer minutesPerClassHour, DataMode dataMode) {
        public RuleVersion {
            codeIfPresent(versionCode); codeIfPresent(evidenceCode); codeIfPresent(currency);
            period(effectiveFrom, effectiveUntil);
            if (amountScale != null && (amountScale < 0 || amountScale > 8))
                throw new IllegalArgumentException("金额小数位超出当前技术范围0..8");
            if (minutesPerClassHour != null && minutesPerClassHour <= 0)
                throw new IllegalArgumentException("每课时分钟数必须为正整数");
        }
    }

    public record RateVersion(String versionCode, String evidenceCode, String ruleVersionCode,
                              LocalDate effectiveFrom, LocalDate effectiveUntil, Dimensions dimensions,
                              BigDecimal unitRate, DataMode dataMode) {
        public RateVersion {
            codeIfPresent(versionCode); codeIfPresent(evidenceCode); codeIfPresent(ruleVersionCode);
            period(effectiveFrom, effectiveUntil); decimal(unitRate, "unitRate");
        }
    }

    /** Optional correction capability, enabled only by an explicitly supplied approved policy. */
    public record CorrectionPolicy(String versionCode, String evidenceCode, LocalDate effectiveFrom,
                                   LocalDate effectiveUntil, String dateBasis, String method, DataMode dataMode) {
        public CorrectionPolicy { codeIfPresent(versionCode); codeIfPresent(evidenceCode); period(effectiveFrom, effectiveUntil); }
    }

    /** Trusted host context. Do not bind these grants or scopes from request JSON. Empty means denied. */
    public record Access(String actorCode, Set<Permission> grants, Set<String> organizationCodes) {
        public Access {
            code(actorCode); grants = Set.copyOf(grants); organizationCodes = Set.copyOf(organizationCodes);
            organizationCodes.forEach(DeliverySettlement::code);
        }
    }

    public static final class Calculation {
        private final String engineVersion;
        private final Fact fact;
        private final RuleVersion rule;
        private final RateVersion rate;
        private final Status status;
        private final List<String> issues;
        private final BigDecimal unroundedAmount;
        private final BigDecimal amount;
        private Calculation(Fact fact, RuleVersion rule, RateVersion rate, Status status, List<String> issues,
                            BigDecimal unroundedAmount, BigDecimal amount) {
            this.engineVersion = ENGINE_VERSION;
            this.fact = fact; this.rule = rule; this.rate = rate; this.status = status;
            this.issues = List.copyOf(issues); this.unroundedAmount = unroundedAmount; this.amount = amount;
        }
        public Fact fact() { return fact; }
        public String engineVersion() { return engineVersion; }
        public RuleVersion rule() { return rule; }
        public RateVersion rate() { return rate; }
        public Status status() { return status; }
        public List<String> issues() { return issues; }
        public BigDecimal unroundedAmount() { return unroundedAmount; }
        public BigDecimal amount() { return amount; }
    }

    /** Private construction freezes all inputs; a correction links a new snapshot without mutating this one. */
    public static final class Confirmed {
        private final String snapshotCode;
        private final Calculation calculation;
        private final String actorCode;
        private final Instant confirmedAt;
        private final Confirmed previous;
        private final CorrectionPolicy correctionPolicy;
        private final String reasonCode;
        private final BigDecimal settlementAmount;
        private Confirmed(String snapshotCode, Calculation calculation, String actorCode, Instant confirmedAt,
                          Confirmed previous, CorrectionPolicy correctionPolicy, String reasonCode) {
            this(snapshotCode, calculation, actorCode, confirmedAt, previous, correctionPolicy, reasonCode,
                    previous == null ? calculation.amount() : calculation.amount().subtract(previous.calculation.amount()));
        }
        private Confirmed(String snapshotCode, Calculation calculation, String actorCode, Instant confirmedAt,
                          Confirmed previous, CorrectionPolicy correctionPolicy, String reasonCode,
                          BigDecimal settlementAmount) {
            this.snapshotCode = code(snapshotCode); this.calculation = calculation; this.actorCode = actorCode;
            this.confirmedAt = Objects.requireNonNull(confirmedAt); this.previous = previous;
            this.correctionPolicy = correctionPolicy; this.reasonCode = reasonCode;
            this.settlementAmount = settlementAmount;
        }
        public String snapshotCode() { return snapshotCode; }
        public Calculation calculation() { return calculation; }
        public String actorCode() { return actorCode; }
        public Instant confirmedAt() { return confirmedAt; }
        public Confirmed previous() { return previous; }
        public CorrectionPolicy correctionPolicy() { return correctionPolicy; }
        public String reasonCode() { return reasonCode; }
        /** Original amount for first entry; signed delta for a correction. */
        public BigDecimal settlementAmount() { return settlementAmount; }
    }

    /** Decimal request values must be JSON strings. Json.num / Double are intentionally rejected. */
    public static BigDecimal decimalInput(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal number) { decimal(number, "decimal"); return number; }
        if (!(value instanceof String))
            throw new IllegalArgumentException("十进制数必须传字符串，不接受浮点JSON数字");
        String s = value.toString();
        if (s.length() > 40 || !s.matches("[0-9]+(?:\\.[0-9]+)?"))
            throw new IllegalArgumentException("无效十进制数");
        BigDecimal result = new BigDecimal(s);
        decimal(result, "decimal");
        return result;
    }

    public static Calculation calculate(Fact fact, RuleVersion rule, RateVersion rate) {
        Calculation incomplete = incompleteInputs(fact, rule, rate);
        if (incomplete != null) return incomplete;
        BigDecimal raw = fact.hours().payable().multiply(rate.unitRate());
        try {
            return new Calculation(fact, rule, rate, Status.READY, List.of(), raw,
                    raw.setScale(rule.amountScale(), rule.roundingMode()));
        } catch (ArithmeticException ex) {
            return new Calculation(fact, rule, rate, Status.INCOMPLETE,
                    List.of("ROUNDING_NECESSARY_BUT_FORBIDDEN"), raw, null);
        }
    }

    /** Checks frozen or current inputs without pricing or rounding; null means structurally complete. */
    private static Calculation incompleteInputs(Fact fact, RuleVersion rule, RateVersion rate) {
        Objects.requireNonNull(fact);
        List<String> config = new ArrayList<>();
        if (rule == null) config.add("RULE_NOT_CONFIGURED");
        else {
            missing(config, rule.versionCode(), "RULE_VERSION_NOT_CONFIGURED");
            missing(config, rule.evidenceCode(), "RULE_EVIDENCE_NOT_CONFIGURED");
            missing(config, rule.effectiveFrom(), "RULE_EFFECTIVE_DATE_NOT_CONFIGURED");
            missing(config, rule.currency(), "CURRENCY_NOT_CONFIGURED");
            missing(config, rule.amountScale(), "AMOUNT_SCALE_NOT_CONFIGURED");
            missing(config, rule.roundingMode(), "ROUNDING_NOT_CONFIGURED");
            missing(config, rule.minutesPerClassHour(), "CLASS_HOUR_DEFINITION_NOT_CONFIGURED");
            if (!DATE_BASIS.equals(rule.dateBasis())) config.add("DATE_BASIS_NOT_CONFIGURED_OR_UNSUPPORTED");
            if (!FORMULA.equals(rule.formula())) config.add("FORMULA_NOT_CONFIGURED_OR_UNSUPPORTED");
            if (!ROUNDING_SCOPE.equals(rule.roundingScope())) config.add("ROUNDING_SCOPE_NOT_CONFIGURED_OR_UNSUPPORTED");
            if (rule.dataMode() != fact.dataMode()) config.add("RULE_DATA_MODE_MISMATCH");
            if (fact.serviceDate() != null && rule.effectiveFrom() != null && !active(fact.serviceDate(), rule.effectiveFrom(), rule.effectiveUntil()))
                config.add("RULE_NOT_EFFECTIVE");
        }
        if (rate == null) config.add("RATE_NOT_CONFIGURED");
        else {
            missing(config, rate.versionCode(), "RATE_VERSION_NOT_CONFIGURED");
            missing(config, rate.evidenceCode(), "RATE_EVIDENCE_NOT_CONFIGURED");
            missing(config, rate.ruleVersionCode(), "RATE_RULE_LINK_NOT_CONFIGURED");
            missing(config, rate.unitRate(), "UNIT_RATE_NOT_CONFIGURED");
            missing(config, rate.effectiveFrom(), "RATE_EFFECTIVE_DATE_NOT_CONFIGURED");
            if (rate.dimensions() == null || !rate.dimensions().complete()) config.add("RATE_DIMENSIONS_NOT_CONFIGURED");
            if (rate.dataMode() != fact.dataMode()) config.add("RATE_DATA_MODE_MISMATCH");
            if (rule != null && !Objects.equals(rate.ruleVersionCode(), rule.versionCode())) config.add("RATE_RULE_VERSION_MISMATCH");
            if (fact.dimensions() != null && fact.dimensions().complete() && !fact.dimensions().equals(rate.dimensions())) config.add("RATE_DIMENSIONS_MISMATCH");
            if (fact.serviceDate() != null && rate.effectiveFrom() != null && !active(fact.serviceDate(), rate.effectiveFrom(), rate.effectiveUntil()))
                config.add("RATE_NOT_EFFECTIVE");
        }
        List<String> issues = new ArrayList<>(config);
        missing(issues, fact.serviceDate(), "SERVICE_DATE_MISSING");
        missing(issues, fact.hours().payable(), "PAYABLE_HOURS_MISSING");
        missing(issues, fact.hours().actual(), "ACTUAL_HOURS_MISSING");
        missing(issues, fact.verification(), "TEACHING_NOT_VERIFIED");
        missing(issues, fact.sessionCode(), "SESSION_CODE_MISSING");
        missing(issues, fact.instructorCode(), "INSTRUCTOR_CODE_MISSING");
        missing(issues, fact.organizationCode(), "ORGANIZATION_CODE_MISSING");
        if (fact.dimensions() == null || !fact.dimensions().complete()) issues.add("FACT_DIMENSIONS_MISSING");
        if (!issues.isEmpty()) return new Calculation(fact, rule, rate,
                config.isEmpty() ? Status.INCOMPLETE : Status.NOT_CONFIGURED, issues, null, null);
        return null;
    }

    public static Confirmed confirm(Calculation result, Fact currentFact, String snapshotCode,
                                    Instant at, Access access) {
        ready(result, currentFact); authorized(access, Permission.CONFIRM, currentFact.organizationCode());
        chronology(result, at);
        return new Confirmed(snapshotCode, result, access.actorCode(), at, null, null, null);
    }

    /** Host must atomically compare previous with the persisted current head and insert a unique revision. */
    public static Confirmed correct(Confirmed previous, Calculation revised, Fact currentFact,
                                    String snapshotCode, String reasonCode, CorrectionPolicy policy,
                                    Instant at, Access access) {
        Objects.requireNonNull(previous); ready(revised, currentFact);
        authorized(access, Permission.CORRECT, currentFact.organizationCode());
        correctionBasis(previous, revised, currentFact, snapshotCode, reasonCode, policy, at);
        return new Confirmed(snapshotCode, revised, access.actorCode(), at, previous, policy, reasonCode);
    }

    private static void correctionBasis(Confirmed previous, Calculation revised, Fact currentFact,
                                        String snapshotCode, String reasonCode, CorrectionPolicy policy, Instant at) {
        Fact old = previous.calculation.fact();
        if (old.recordId() != currentFact.recordId() || old.revision() >= currentFact.revision()
                || !Objects.equals(old.sessionCode(), currentFact.sessionCode())
                || !Objects.equals(old.instructorCode(), currentFact.instructorCode())
                || !Objects.equals(old.organizationCode(), currentFact.organizationCode())
                || old.dataMode() != currentFact.dataMode())
            throw new IllegalArgumentException("更正必须属于同一授课记录、编码与数据模式，且修订号递增");
        if (!previous.calculation.rule().currency().equals(revised.rule().currency()))
            throw new IllegalArgumentException("更正不支持跨币种差额");
        if (policy == null || policy.versionCode() == null || policy.evidenceCode() == null
                || policy.effectiveFrom() == null || !DATE_BASIS.equals(policy.dateBasis())
                || !"REPLACEMENT_DELTA".equals(policy.method()) || policy.dataMode() != currentFact.dataMode()
                || !active(currentFact.serviceDate(), policy.effectiveFrom(), policy.effectiveUntil()))
            throw new IllegalStateException("更正规则未配置、不适用或不受支持");
        for (Confirmed ancestor = previous; ancestor != null; ancestor = ancestor.previous)
            if (ancestor.snapshotCode.equals(snapshotCode)) throw new IllegalArgumentException("更正快照编码重复");
        code(reasonCode); chronology(revised, at);
        if (at.isBefore(previous.confirmedAt)) throw new IllegalArgumentException("更正确认时间早于原确认");
    }

    /**
     * Trusted persistence adapters only: restores a complete, already-authorized immutable snapshot.
     * This package-private method is not an HTTP/request boundary and grants no authority to confirm
     * or correct a record. The adapter must supply only stored frozen fields and enforce access itself.
     * No current policy lookup, multiplication or rounding is performed. Unknown engines are rejected;
     * historical reads for other engines must return their stored snapshot JSON without using this method.
     */
    static Confirmed restoreFrozen(Fact fact, RuleVersion rule, RateVersion rate, String engineVersion,
                                   String snapshotCode, String actorCode, Instant at, Confirmed previous,
                                   CorrectionPolicy policy, String reasonCode, BigDecimal unroundedAmount,
                                   BigDecimal amount, BigDecimal settlementAmount) {
        if (!ENGINE_VERSION.equals(engineVersion)) throw new IllegalArgumentException("不支持恢复该冻结计算引擎版本");
        code(snapshotCode); code(actorCode);
        Calculation incomplete = incompleteInputs(fact, rule, rate);
        if (incomplete != null) throw new IllegalStateException("冻结结算依据不完整或不适用: " + incomplete.issues());
        frozenDecimal(unroundedAmount, "unroundedAmount", 16, false);
        if (unroundedAmount.precision() > 48)
            throw new IllegalArgumentException("unroundedAmount 超出48位有效数字技术范围");
        frozenDecimal(amount, "amount", 8, false);
        frozenDecimal(settlementAmount, "settlementAmount", 8, true);
        if (amount.scale() != rule.amountScale()) throw new IllegalArgumentException("冻结金额小数位与冻结规则不一致");
        Calculation frozen = new Calculation(fact, rule, rate, Status.READY, List.of(), unroundedAmount, amount);
        chronology(frozen, at);
        if (previous == null) {
            if (policy != null || reasonCode != null)
                throw new IllegalArgumentException("原始结算不应携带更正规则或原因");
        } else {
            correctionBasis(previous, frozen, fact, snapshotCode, reasonCode, policy, at);
            for (Confirmed ancestor = previous; ancestor != null; ancestor = ancestor.previous)
                if (!fact.serviceDate().equals(ancestor.calculation.fact().serviceDate()))
                    throw new IllegalArgumentException("冻结单差额更正链必须使用同一授课日期");
        }
        BigDecimal expected = previous == null ? amount : amount.subtract(previous.calculation.amount());
        if (settlementAmount.compareTo(expected) != 0)
            throw new IllegalArgumentException("冻结结算差额与原始金额或前次结算不一致");
        return new Confirmed(snapshotCode, frozen, actorCode, at, previous, policy, reasonCode, settlementAmount);
    }

    /** JSON-friendly output. All decimal quantities are strings, and absent values remain null. */
    public static Map<String, Object> view(Calculation result) {
        Map<String, Object> m = new LinkedHashMap<>(); Fact f = result.fact();
        m.put("status", result.status().name()); m.put("issues", result.issues());
        m.put("data_mode", f.dataMode().name()); m.put("engine_version", result.engineVersion());
        m.put("record_id", f.recordId()); m.put("revision", f.revision());
        m.put("session_code", f.sessionCode()); m.put("instructor_code", f.instructorCode());
        m.put("organization_code", f.organizationCode()); m.put("service_date", f.serviceDate());
        Map<String, Object> hours = new LinkedHashMap<>();
        hours.put("estimated", str(f.hours().estimated())); hours.put("planned", str(f.hours().planned()));
        hours.put("actual", str(f.hours().actual())); hours.put("payable", str(f.hours().payable())); m.put("hours", hours);
        m.put("unrounded_amount", str(result.unroundedAmount())); m.put("amount", str(result.amount()));
        m.put("rule_version", result.rule() == null ? null : result.rule().versionCode());
        m.put("rate_version", result.rate() == null ? null : result.rate().versionCode());
        m.put("currency", result.rule() == null ? null : result.rule().currency());
        m.put("minutes_per_class_hour", result.rule() == null ? null : result.rule().minutesPerClassHour());
        return m;
    }

    /** Signed ledger contribution for M06. Host supplies unique persistent entry ID, course ID and payment date.
     * Historical snapshots are immutable CONFIRMED base entries; only the new difference is ADJUSTMENT.
     * Never mark the base entry SUPERSEDED while also summing this difference.
     */
    public static Map<String, Object> reportingContribution(Confirmed entry) {
        Objects.requireNonNull(entry);
        Fact current = entry.calculation.fact();
        Fact before = entry.previous == null ? null : entry.previous.calculation.fact();
        if (before != null && !Objects.equals(before.serviceDate(), current.serviceDate()))
            throw new IllegalStateException("跨授课日期更正需按原日期冲回与新日期重记；单差额适配尚未支持");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceRecordId", current.recordId());
        m.put("recordCode", entry.snapshotCode());
        m.put("teacherCode", current.instructorCode());
        m.put("orgCode", current.organizationCode());
        m.put("dates", Map.of("TEACHING", current.serviceDate().toString()));
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("ESTIMATED", str(difference(current.hours().estimated(), before == null ? null : before.hours().estimated(), before != null)));
        h.put("PLANNED", str(difference(current.hours().planned(), before == null ? null : before.hours().planned(), before != null)));
        h.put("ACTUAL", str(difference(current.hours().actual(), before == null ? null : before.hours().actual(), before != null)));
        h.put("PAYABLE", str(difference(current.hours().payable(), before == null ? null : before.hours().payable(), before != null)));
        m.put("hours", h); m.put("fee", str(entry.settlementAmount()));
        m.put("currency", entry.calculation.rule().currency());
        m.put("feeVersion", entry.calculation.rule().versionCode());
        m.put("rateVersion", entry.calculation.rate().versionCode());
        m.put("status", before == null ? "CONFIRMED" : "ADJUSTMENT");
        m.put("dataMode", current.dataMode().name());
        return m;
    }

    private static BigDecimal difference(BigDecimal current, BigDecimal before, boolean correction) {
        if (!correction) return current;
        return current == null || before == null ? null : current.subtract(before);
    }

    /** One original entry plus signed correction deltas. No names, free text, or decoding maps. */
    public static String encodedCsv(List<Confirmed> entries, Access access, DataMode mode) {
        Objects.requireNonNull(entries); Objects.requireNonNull(mode);
        // Require permission even for an empty export.
        if (access == null || !access.grants().contains(Permission.EXPORT)) throw new SecurityException("无导出权限");
        Set<String> codes = new HashSet<>(); Set<Long> originals = new HashSet<>();
        Set<String> replaced = new HashSet<>(); Set<String> revisions = new HashSet<>();
        Map<String, Confirmed> lineage = new LinkedHashMap<>();
        Map<String, String> children = new LinkedHashMap<>();
        Map<Long, String> roots = new LinkedHashMap<>();
        for (Confirmed entry : entries) {
            Fact f = entry.calculation.fact(); authorized(access, Permission.EXPORT, f.organizationCode());
            if (f.dataMode() != mode) throw new IllegalArgumentException("导出数据模式不匹配");
            if (!codes.add(entry.snapshotCode) || !revisions.add(f.recordId() + ":" + f.revision()))
                throw new IllegalArgumentException("重复结算记录");
            if (entry.previous == null && !originals.add(f.recordId())) throw new IllegalArgumentException("同一授课记录重复原始结算");
            if (entry.previous != null && !replaced.add(entry.previous.snapshotCode)) throw new IllegalArgumentException("更正链分叉");
            for (Confirmed ancestor = entry; ancestor != null; ancestor = ancestor.previous) {
                Confirmed seen = lineage.putIfAbsent(ancestor.snapshotCode, ancestor);
                if (seen != null && seen != ancestor) throw new IllegalArgumentException("同一快照编码对应不同对象，需统一从持久层重建");
                if (ancestor.previous == null) {
                    String root = roots.putIfAbsent(f.recordId(), ancestor.snapshotCode);
                    if (root != null && !root.equals(ancestor.snapshotCode)) throw new IllegalArgumentException("同一记录有多个原始结算");
                } else {
                    String child = children.putIfAbsent(ancestor.previous.snapshotCode, ancestor.snapshotCode);
                    if (child != null && !child.equals(ancestor.snapshotCode)) throw new IllegalArgumentException("祖先更正链分叉");
                }
            }
        }
        StringBuilder csv = new StringBuilder();
        row(csv, "data_mode", "entry_type", "snapshot_code", "replaces_snapshot_code", "record_id", "revision",
                "session_code", "instructor_code", "organization_code", "service_date", "grade_code", "time_band_code", "form_code", "hour_unit_code",
                "estimated_hours", "planned_hours", "actual_hours", "payable_hours", "rule_version", "rate_version", "engine_version",
                "rule_evidence_code", "rate_evidence_code", "rule_effective_from", "rule_effective_until", "rate_effective_from", "rate_effective_until",
                "date_basis", "formula", "rounding_scope", "amount_scale", "rounding_mode", "currency", "minutes_per_class_hour", "unit_rate", "unrounded_amount",
                "confirmed_total_amount", "settlement_amount", "verified_by_code", "verified_at", "verification_evidence_code",
                "confirmed_by_code", "confirmed_at", "correction_policy_version", "correction_evidence_code", "reason_code");
        for (Confirmed e : entries) {
            Calculation c = e.calculation; Fact f = c.fact(); RuleVersion r = c.rule(); RateVersion t = c.rate();
            Dimensions d = f.dimensions(); Verification v = f.verification();
            row(csv, mode.name(), e.previous == null ? "ORIGINAL" : "CORRECTION_DELTA", e.snapshotCode,
                    e.previous == null ? null : e.previous.snapshotCode, f.recordId(), f.revision(), f.sessionCode(), f.instructorCode(),
                    f.organizationCode(), f.serviceDate(), d.gradeCode(), d.timeBandCode(), d.formCode(), d.hourUnitCode(),
                    str(f.hours().estimated()), str(f.hours().planned()), str(f.hours().actual()), str(f.hours().payable()),
                    r.versionCode(), t.versionCode(), c.engineVersion(), r.evidenceCode(), t.evidenceCode(), r.effectiveFrom(), r.effectiveUntil(),
                    t.effectiveFrom(), t.effectiveUntil(), r.dateBasis(), r.formula(), r.roundingScope(), r.amountScale(), r.roundingMode(),
                    r.currency(), r.minutesPerClassHour(), str(t.unitRate()), str(c.unroundedAmount()), str(c.amount()), str(e.settlementAmount),
                    v.actorCode(), v.checkedAt(), v.evidenceCode(), e.actorCode, e.confirmedAt,
                    e.correctionPolicy == null ? null : e.correctionPolicy.versionCode(),
                    e.correctionPolicy == null ? null : e.correctionPolicy.evidenceCode(), e.reasonCode);
        }
        return csv.toString();
    }

    private static void ready(Calculation result, Fact currentFact) {
        Objects.requireNonNull(result); Objects.requireNonNull(currentFact);
        if (result.status() != Status.READY) throw new IllegalStateException("结果未就绪: " + result.issues());
        if (!result.fact().equals(currentFact)) throw new IllegalStateException("课时或核对依据已变更，请重新计算");
    }
    private static void chronology(Calculation result, Instant at) {
        Objects.requireNonNull(at);
        if (at.isBefore(result.fact().verification().checkedAt())) throw new IllegalArgumentException("确认时间早于授课核对");
    }
    private static void authorized(Access access, Permission permission, String org) {
        if (access == null || !access.grants().contains(permission) || !access.organizationCodes().contains(org))
            throw new SecurityException("无操作权限或组织超出授权范围");
    }
    private static void missing(List<String> issues, Object value, String issue) { if (value == null) issues.add(issue); }
    private static boolean active(LocalDate date, LocalDate from, LocalDate until) {
        return !date.isBefore(from) && (until == null || date.isBefore(until));
    }
    private static void period(LocalDate from, LocalDate until) {
        if (from != null && until != null && !from.isBefore(until)) throw new IllegalArgumentException("生效区间必须为左闭右开且非空");
    }
    private static void decimal(BigDecimal v, String field) {
        if (v != null && (v.signum() < 0 || v.precision() > 24 || v.scale() < 0 || v.scale() > 8))
            throw new IllegalArgumentException(field + " 必须非负，且在24位有效数字、8位小数技术范围内");
    }
    private static void frozenDecimal(BigDecimal value, String field, int maxScale, boolean signed) {
        // A product of two 24-digit inputs can have 48 integer digits. Adding an amount scale
        // can raise BigDecimal precision to 56, so the request-input 24/8 guard does not apply.
        if (value == null || (!signed && value.signum() < 0) || value.scale() < 0
                || value.scale() > maxScale || value.precision() - value.scale() > 48)
            throw new IllegalArgumentException(field + " 缺失或超出冻结金额技术范围");
    }
    private static String code(String value) {
        // Transport safety constraint only, not an allocation scheme for formal organization/person codes.
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}"))
            throw new IllegalArgumentException("缺少安全编码，或编码超出当前ASCII传输格式");
        return value;
    }
    private static void codeIfPresent(String value) { if (value != null) code(value); }
    private static String str(BigDecimal v) { return v == null ? null : v.toPlainString(); }
    private static void row(StringBuilder out, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            String s = values[i] == null ? "" : values[i].toString();
            // Dynamic text is limited to safe codes/enums/dates/decimal literals before reaching here.
            out.append('"').append(s.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }
    private DeliverySettlement() {}
}
