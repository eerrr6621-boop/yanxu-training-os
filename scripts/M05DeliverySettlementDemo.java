package com.training;

import static com.training.DeliverySettlement.*;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Static synthetic demonstration. Writes only stdout; never reads live records or persists data. */
public final class M05DeliverySettlementDemo {
    public static void main(String[] args) {
        LocalDate start = LocalDate.parse("2099-01-01"), end = LocalDate.parse("2100-01-01");
        Instant time = Instant.parse("2099-01-02T00:00:00Z");
        Dimensions dims = new Dimensions("DEMO-G1", "DEMO-DAY", "DEMO-ONSITE", "DEMO-HOUR");
        Fact fact = new Fact(701, 1, "DEMO-SESSION1", "DEMO-INSTRUCTOR1", "DEMO-ORG1", start, dims,
                new Hours(decimalInput("2"), decimalInput("1.75"), decimalInput("1.5"), decimalInput("1.25")),
                new Verification("DEMO-VERIFY1", time, "DEMO-CHECK-SOURCE1"), DataMode.SYNTHETIC_DEMO);
        RuleVersion rule = new RuleVersion("DEMO-RULE1", "DEMO-RULE-SOURCE1", start, end, DATE_BASIS,
                FORMULA, "CNY", 2, RoundingMode.HALF_UP, ROUNDING_SCOPE, 45, DataMode.SYNTHETIC_DEMO);
        RateVersion rate = new RateVersion("DEMO-RATE1", "DEMO-RATE-SOURCE1", rule.versionCode(), start, end,
                dims, decimalInput("80.004"), DataMode.SYNTHETIC_DEMO);
        Calculation result = calculate(fact, rule, rate);
        if (args.length == 1 && "--csv".equals(args[0])) {
            Access access = new Access("DEMO-OP1", Set.of(Permission.CONFIRM, Permission.EXPORT), Set.of("DEMO-ORG1"));
            Confirmed snapshot = confirm(result, fact, "DEMO-SNAPSHOT1", time.plusSeconds(60), access);
            System.out.print(encodedCsv(List.of(snapshot), access, DataMode.SYNTHETIC_DEMO));
        } else {
            System.out.println(Json.write(view(result)));
            System.out.println(Json.write(view(calculate(fact, null, null))));
        }
    }
}
