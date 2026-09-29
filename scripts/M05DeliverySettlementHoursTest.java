package com.training;

import static com.training.DeliverySettlementHours.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class M05DeliverySettlementHoursTest {
    private static int passed;
    private static void eq(Object actual, Object expected, String label) {
        if (!Objects.equals(actual, expected)) throw new AssertionError(label + ": " + actual + " != " + expected);
        passed++;
    }
    private static void reject(Class<? extends Throwable> type, Runnable action, String label) {
        try { action.run(); } catch (Throwable e) {
            if (!type.isInstance(e)) throw new AssertionError(label, e); passed++; return;
        }
        throw new AssertionError(label + " accepted");
    }
    private static Conversion convert(String value) { return DeliverySettlementHours.convert(value, currentUserRule()); }
    public static void main(String[] args) {
        String[][] examples = {{"60", "1.33"}, {"45", "1.00"}, {"90", "2.00"}, {"67.5", "1.50"},
                {"0", "0.00"}, {"1", "0.02"}, {"30", "0.67"}, {"0.225", "0.01"},
                {"0.22499999", "0.00"}, {"0.22500001", "0.01"}, {"9007199254740993", "200159983438688.73"},
                {"00060.00", "1.33"},
                {"0.00000001", "0.00"}};
        if (args.length == 1 && args[0].equals("--vectors")) {
            System.out.println(Json.write(java.util.Arrays.stream(examples).map(e -> java.util.Map.of("minutes", e[0], "classHours", convert(e[0]).classHours().toPlainString())).toList())); return;
        }
        for (String[] example : examples) {
            Conversion c = convert(example[0]);
            eq(c.status(), Status.READY, "ready " + example[0]);
            eq(c.classHours().toPlainString(), example[1], "converted " + example[0]);
        }
        eq(convert("60").rounded(), true, "nonterminating quotient records rounding");
        eq(convert("67.5").rounded(), false, "exact quotient");
        eq(convert("60").minutes(), new BigDecimal("60"), "source minutes retained");
        eq(convert("60").rule().roundingDecisionCode(), ROUNDING_DECISION, "implementation decision distinguished");
        eq(convert(null).status(), Status.INCOMPLETE, "unknown minutes incomplete");
        eq(convert(null).classHours(), null, "unknown is not zero");
        eq(convert("999999999999999999999999").status(), Status.INCOMPLETE, "output precision bounded before apply");
        eq(convert("999999999999999999999999").issues(), List.of("CLASS_HOUR_RESULT_OUT_OF_RANGE"), "explicit output range reason");
        eq(DeliverySettlementHours.convert("60", null).status(), Status.NOT_CONFIGURED, "explicit rule needed");
        Rule missingRounding = new Rule("R1", 45, 2, null, "E1", "D1");
        eq(DeliverySettlementHours.convert("60", missingRounding).classHours(), null, "missing rounding no fallback");
        Rule exactOnly = new Rule("R2", 45, 2, RoundingMode.UNNECESSARY, "E1", "D1");
        eq(DeliverySettlementHours.convert("60", exactOnly).status(), Status.INCOMPLETE, "unnecessary rejects repeating");
        eq(DeliverySettlementHours.convert("90", exactOnly).classHours(), new BigDecimal("2.00"), "unnecessary exact");
        for (String bad : List.of("", " 60", "60 ", "+60", "-1", ".5", "1.", "1e2", "NaN", "0.123456789", "1".repeat(25), "0".repeat(41)))
            reject(IllegalArgumentException.class, () -> convert(bad), "bad input " + bad);
        reject(IllegalArgumentException.class, () -> DeliverySettlementHours.convert(60d, currentUserRule()), "no floating inputs");
        reject(IllegalArgumentException.class, () -> new Rule("R1", 0, 2, RoundingMode.HALF_UP, "E1", "D1"), "invalid denominator");
        reject(IllegalArgumentException.class, () -> new Rule("R1", 45, 9, RoundingMode.HALF_UP, "E1", "D1"), "invalid scale");
        var oldHours = new DeliverySettlement.Hours(new BigDecimal("3"), new BigDecimal("2.5"), new BigDecimal("2"), new BigDecimal("1.5"));
        var source = new DeliverySettlement.Fact(501, 1, "DEMO-S1", "DEMO-T1", "DEMO-O1", LocalDate.parse("2099-01-01"),
                new DeliverySettlement.Dimensions("DEMO-G1", "DEMO-DAY", "DEMO-F1", "DEMO-U45"), oldHours,
                new DeliverySettlement.Verification("DEMO-C1", Instant.parse("2099-01-02T00:00:00Z"), "DEMO-E1"), DeliverySettlement.DataMode.SYNTHETIC_DEMO);
        ActualUpdate update = applyToActual(source, convert("60"), 2);
        eq(update.fact().hours().actual(), new BigDecimal("1.33"), "new actual hours");
        eq(update.fact().hours().estimated(), oldHours.estimated(), "estimated preserved");
        eq(update.fact().hours().planned(), oldHours.planned(), "planned preserved");
        eq(update.fact().hours().payable(), oldHours.payable(), "payable not implicitly changed");
        eq(update.fact().verification(), null, "changed actual needs recheck");
        eq(source.hours().actual(), new BigDecimal("2"), "old fact unchanged");
        eq(source.verification().actorCode(), "DEMO-C1", "old verification unchanged");
        eq(update.conversion().minutes(), new BigDecimal("60"), "basis attached to update");
        reject(IllegalArgumentException.class, () -> applyToActual(source, convert("60"), 1), "revision must advance");
        reject(IllegalStateException.class, () -> applyToActual(source, convert(null), 2), "incomplete conversion not applied");
        reject(IllegalStateException.class, () -> applyToActual(source, convert("999999999999999999999999"), 2), "overrange result never READY to apply");
        eq(view(convert("60")).get("class_hours"), "1.33", "wire decimal is string");
        eq(view(convert(null)).get("class_hours"), null, "wire unknown stays null");
        System.out.println("M05 class-hour conversion: " + passed + " assertions passed");
    }
}
