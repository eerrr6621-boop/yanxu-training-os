package com.training;

import static com.training.DeliverySettlement.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Synthetic frozen persistence fixtures only. Does not open a database or use production records. */
public final class M05IntegrationFrozenTest {
    private static int passed;
    private static final LocalDate DATE = LocalDate.parse("2099-03-01");
    private static final Instant CHECKED = Instant.parse("2099-03-02T00:00:00Z");
    private static final Instant AT = CHECKED.plusSeconds(60);
    private static final Dimensions DIMS = new Dimensions("DEMO-G1", "DEMO-DAY", "DEMO-ONLINE", "DEMO-HOUR");
    private static final Verification VERIFIED = new Verification("DEMO-CHECK", CHECKED, "DEMO-VERIFIED");
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
    private static void eq(Object actual, Object expected, String label) {
        check(Objects.equals(actual, expected), label + ": " + actual);
    }
    private static void failure(Runnable action, String label) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | NullPointerException expected) { passed++; return; }
        throw new AssertionError(label + " accepted");
    }
    private static Fact fact(long record, long revision, String session, String teacher, String org,
                             LocalDate date, Hours hours, Verification verification, DataMode mode) {
        return new Fact(record, revision, session, teacher, org, date, DIMS, hours, verification, mode);
    }
    private static Fact fact(long revision) {
        return fact(71, revision, "DEMO-SESSION", "DEMO-TEACHER", "DEMO-ORG", DATE,
                new Hours(null, null, d("1"), d("1")), VERIFIED, DataMode.SYNTHETIC_DEMO);
    }
    private static RuleVersion rule(String currency, int scale) {
        return new RuleVersion("DEMO-R1", "DEMO-R-SOURCE", DATE.minusDays(1), DATE.plusDays(20),
                DATE_BASIS, FORMULA, currency, scale, RoundingMode.HALF_UP, ROUNDING_SCOPE, 45, DataMode.SYNTHETIC_DEMO);
    }
    private static RateVersion rate(String ruleCode, String unitRate) {
        return new RateVersion("DEMO-T1", "DEMO-T-SOURCE", ruleCode, DATE.minusDays(1), DATE.plusDays(20),
                DIMS, d(unitRate), DataMode.SYNTHETIC_DEMO);
    }
    private static CorrectionPolicy policy() {
        return new CorrectionPolicy("DEMO-P1", "DEMO-P-SOURCE", DATE.minusDays(1), DATE.plusDays(20),
                DATE_BASIS, "REPLACEMENT_DELTA", DataMode.SYNTHETIC_DEMO);
    }
    private static Confirmed original(Fact fact, RuleVersion rule, RateVersion rate,
                                      String engine, String code, String actor, Instant at,
                                      String raw, String amount, String settlement) {
        return restoreFrozen(fact, rule, rate, engine, code, actor, at, null, null, null,
                raw == null ? null : d(raw), amount == null ? null : d(amount), settlement == null ? null : d(settlement));
    }
    private static Confirmed original(String raw, String amount, String settlement) {
        return original(fact(1), rule("CNY", 2), rate("DEMO-R1", "1"), ENGINE_VERSION,
                "DEMO-S1", "DEMO-ACTOR", AT, raw, amount, settlement);
    }
    private static Confirmed correction(Fact fact, RuleVersion rule, RateVersion rate, String snapshot,
                                        Instant at, Confirmed previous, CorrectionPolicy policy,
                                        String reason, String amount, String settlement) {
        return restoreFrozen(fact, rule, rate, ENGINE_VERSION, snapshot, "DEMO-ACTOR", at,
                previous, policy, reason, d("2.119"), d(amount), d(settlement));
    }
    public static void main(String[] args) throws Exception {
        var restoreMethod = DeliverySettlement.class.getDeclaredMethod("restoreFrozen", Fact.class, RuleVersion.class,
                RateVersion.class, String.class, String.class, String.class, Instant.class, Confirmed.class,
                CorrectionPolicy.class, String.class, BigDecimal.class, BigDecimal.class, BigDecimal.class);
        check(!Modifier.isPublic(restoreMethod.getModifiers()) && !Modifier.isProtected(restoreMethod.getModifiers())
                && !Modifier.isPrivate(restoreMethod.getModifiers()) && Modifier.isStatic(restoreMethod.getModifiers()),
                "trusted restore is package-private static");

        Confirmed first = original("44.554", "44.55", "44.55");
        eq(first.calculation().status(), Status.READY, "complete frozen input is ready");
        eq(first.calculation().amount(), d("44.55"), "frozen amount is not multiplied again");
        eq(first.calculation().unroundedAmount(), d("44.554"), "frozen unrounded amount retained");
        eq(calculate(fact(1), rule("CNY", 2), rate("DEMO-R1", "1")).amount(), d("1.00"),
                "current formula would produce different value");
        eq(first.actorCode(), "DEMO-ACTOR", "stored actor preserved without constructing access grants");
        eq(first.confirmedAt(), AT, "stored confirmation time preserved");
        eq(first.calculation().engineVersion(), ENGINE_VERSION, "stored engine retained");
        Confirmed second = correction(fact(2), rule("CNY", 2), rate("DEMO-R1", "1"),
                "DEMO-S2", AT.plusSeconds(1), first, policy(), "DEMO-REASON", "2.11", "-42.44");
        eq(second.calculation().amount(), d("2.11"), "frozen rounding is not performed again");
        eq(second.settlementAmount(), d("-42.44"), "signed frozen delta preserved");
        eq(second.previous(), first, "frozen predecessor retained");
        eq(second.settlementAmount().add(first.settlementAmount()), second.calculation().amount(), "ledger sums to frozen total");

        failure(() -> original(fact(1), rule("CNY", 2), rate("DEMO-R1", "1"), "M05-UNKNOWN",
                "DEMO-S1", "DEMO-ACTOR", AT, "1", "1.00", "1.00"), "unknown engine");
        failure(() -> original(fact(1), rule("CNY", 2), rate("DEMO-R1", "1"), null,
                "DEMO-S1", "DEMO-ACTOR", AT, "1", "1.00", "1.00"), "missing engine");
        failure(() -> original("1", "1.00", "0.99"), "corrupt original settlement");
        failure(() -> original("1", "-1.00", "-1.00"), "negative total");
        failure(() -> original("-1", "1.00", "1.00"), "negative raw");
        failure(() -> original(null, "1.00", "1.00"), "missing raw");
        failure(() -> original("1", null, "1.00"), "missing total");
        failure(() -> original("1", "1.00", null), "missing settlement");
        failure(() -> original("1", "1.0", "1.0"), "amount scale mismatch");
        failure(() -> original("1.00000000000000000", "1.00", "1.00"), "raw scale bound");
        failure(() -> original("9".repeat(49), "1.00", "1.00"), "raw precision bound");
        failure(() -> original("1", "9".repeat(49) + ".00", "9".repeat(49) + ".00"), "amount integer bound");
        failure(() -> original("1", "1.00", "1.000000000"), "settlement scale bound");
        failure(() -> original("1E+2", "1.00", "1.00"), "negative BigDecimal scale");
        failure(() -> original(fact(1), null, rate("DEMO-R1", "1"), ENGINE_VERSION,
                "DEMO-S1", "DEMO-ACTOR", AT, "1", "1.00", "1.00"), "missing rule");
        failure(() -> original(fact(1), rule("CNY", 2), null, ENGINE_VERSION,
                "DEMO-S1", "DEMO-ACTOR", AT, "1", "1.00", "1.00"), "missing rate");
        failure(() -> original(fact(1), rule("CNY", 2), rate("DEMO-WRONG", "1"), ENGINE_VERSION,
                "DEMO-S1", "DEMO-ACTOR", AT, "1", "1.00", "1.00"), "rate rule link");
        failure(() -> original(fact(1), rule("CNY", 2), rate("DEMO-R1", "1"), ENGINE_VERSION,
                "=unsafe", "DEMO-ACTOR", AT, "1", "1.00", "1.00"), "invalid snapshot code");
        failure(() -> original(fact(1), rule("CNY", 2), rate("DEMO-R1", "1"), ENGINE_VERSION,
                "DEMO-S1", "姓名", AT, "1", "1.00", "1.00"), "invalid actor code");
        failure(() -> original(fact(1), rule("CNY", 2), rate("DEMO-R1", "1"), ENGINE_VERSION,
                "DEMO-S1", "DEMO-ACTOR", CHECKED.minusSeconds(1), "1", "1.00", "1.00"), "confirmation before verification");
        Fact unverified = fact(71, 1, "DEMO-SESSION", "DEMO-TEACHER", "DEMO-ORG", DATE,
                fact(1).hours(), null, DataMode.SYNTHETIC_DEMO);
        failure(() -> original(unverified, rule("CNY", 2), rate("DEMO-R1", "1"), ENGINE_VERSION,
                "DEMO-S1", "DEMO-ACTOR", AT, "1", "1.00", "1.00"), "unverified frozen fact");
        Fact expired = fact(71, 1, "DEMO-SESSION", "DEMO-TEACHER", "DEMO-ORG", DATE.plusDays(20),
                fact(1).hours(), VERIFIED, DataMode.SYNTHETIC_DEMO);
        failure(() -> original(expired, rule("CNY", 2), rate("DEMO-R1", "1"), ENGINE_VERSION,
                "DEMO-S1", "DEMO-ACTOR", AT, "1", "1.00", "1.00"), "exclusive effective-until");
        failure(() -> restoreFrozen(fact(1), rule("CNY", 2), rate("DEMO-R1", "1"), ENGINE_VERSION,
                "DEMO-S1", "DEMO-ACTOR", AT, null, policy(), "DEMO-REASON", d("1"), d("1.00"), d("1.00")),
                "original carrying correction metadata");

        failure(() -> correction(fact(2), rule("CNY", 2), rate("DEMO-R1", "1"), "DEMO-S2", AT,
                first, policy(), "DEMO-REASON", "2.11", "42.44"), "corrupt delta sign");
        failure(() -> correction(fact(1), rule("CNY", 2), rate("DEMO-R1", "1"), "DEMO-S2", AT,
                first, policy(), "DEMO-REASON", "2.11", "-42.44"), "non-increasing revision");
        failure(() -> correction(fact(2), rule("USD", 2), rate("DEMO-R1", "1"), "DEMO-S2", AT,
                first, policy(), "DEMO-REASON", "2.11", "-42.44"), "currency mismatch");
        failure(() -> correction(fact(2), rule("CNY", 2), rate("DEMO-R1", "1"), "DEMO-S1", AT,
                first, policy(), "DEMO-REASON", "2.11", "-42.44"), "ancestor snapshot reuse");
        failure(() -> correction(fact(2), rule("CNY", 2), rate("DEMO-R1", "1"), "DEMO-S2", AT.minusSeconds(1),
                first, policy(), "DEMO-REASON", "2.11", "-42.44"), "confirmation predates predecessor");
        failure(() -> correction(fact(2), rule("CNY", 2), rate("DEMO-R1", "1"), "DEMO-S2", AT,
                first, null, "DEMO-REASON", "2.11", "-42.44"), "missing correction policy");
        failure(() -> correction(fact(2), rule("CNY", 2), rate("DEMO-R1", "1"), "DEMO-S2", AT,
                first, policy(), null, "2.11", "-42.44"), "missing correction reason");
        CorrectionPolicy wrongMethod = new CorrectionPolicy("DEMO-P1", "DEMO-P-SOURCE", DATE.minusDays(1), null,
                DATE_BASIS, "UNKNOWN", DataMode.SYNTHETIC_DEMO);
        failure(() -> correction(fact(2), rule("CNY", 2), rate("DEMO-R1", "1"), "DEMO-S2", AT,
                first, wrongMethod, "DEMO-REASON", "2.11", "-42.44"), "unsupported correction policy");
        CorrectionPolicy expiredPolicy = new CorrectionPolicy("DEMO-P1", "DEMO-P-SOURCE", DATE.minusDays(1), DATE,
                DATE_BASIS, "REPLACEMENT_DELTA", DataMode.SYNTHETIC_DEMO);
        failure(() -> correction(fact(2), rule("CNY", 2), rate("DEMO-R1", "1"), "DEMO-S2", AT,
                first, expiredPolicy, "DEMO-REASON", "2.11", "-42.44"), "inapplicable correction policy");
        for (Fact wrong : new Fact[] {
                fact(72, 2, "DEMO-SESSION", "DEMO-TEACHER", "DEMO-ORG", DATE, fact(2).hours(), VERIFIED, DataMode.SYNTHETIC_DEMO),
                fact(71, 2, "DEMO-OTHER", "DEMO-TEACHER", "DEMO-ORG", DATE, fact(2).hours(), VERIFIED, DataMode.SYNTHETIC_DEMO),
                fact(71, 2, "DEMO-SESSION", "DEMO-OTHER", "DEMO-ORG", DATE, fact(2).hours(), VERIFIED, DataMode.SYNTHETIC_DEMO),
                fact(71, 2, "DEMO-SESSION", "DEMO-TEACHER", "DEMO-OTHER", DATE, fact(2).hours(), VERIFIED, DataMode.SYNTHETIC_DEMO),
                fact(71, 2, "DEMO-SESSION", "DEMO-TEACHER", "DEMO-ORG", DATE.plusDays(1), fact(2).hours(), VERIFIED, DataMode.SYNTHETIC_DEMO),
                fact(71, 2, "DEMO-SESSION", "DEMO-TEACHER", "DEMO-ORG", DATE, fact(2).hours(), VERIFIED, DataMode.CONFIGURED) }) {
            failure(() -> correction(wrong, rule("CNY", 2), rate("DEMO-R1", "1"), "DEMO-S2", AT,
                    first, policy(), "DEMO-REASON", "2.11", "-42.44"), "corrupt identity/date/mode association");
        }

        // The input 24/8 limits must not be reused for frozen products or padded monetary values.
        for (String input : new String[] { "999999999999999999999999", "9999999999999999.99999999" }) {
            Fact large = fact(71, 1, "DEMO-SESSION", "DEMO-TEACHER", "DEMO-ORG", DATE,
                    new Hours(null, null, d(input), d(input)), VERIFIED, DataMode.SYNTHETIC_DEMO);
            RuleVersion largeRule = rule("CNY", 8);
            RateVersion largeRate = rate("DEMO-R1", input);
            Calculation calculated = calculate(large, largeRule, largeRate);
            Confirmed restored = restoreFrozen(large, largeRule, largeRate, ENGINE_VERSION, "DEMO-LARGE", "DEMO-ACTOR", AT,
                    null, null, null, calculated.unroundedAmount(), calculated.amount(), calculated.amount());
            eq(restored.calculation().unroundedAmount(), calculated.unroundedAmount(), "48-digit product restored");
            eq(restored.calculation().amount(), calculated.amount(), "full monetary precision restored");
            eq(calculated.unroundedAmount().precision(), 48, "fixture product has 48 significant digits");
            eq(calculated.unroundedAmount().scale(), input.contains(".") ? 16 : 0, "fixture covers 16-place product");
            eq(calculated.amount().precision(), input.contains(".") ? 40 : 56, "fixture covers 56-digit scaled amount");
        }
        System.out.println("M05IntegrationFrozenTest: " + passed + " assertions passed (synthetic data only)");
    }
}
