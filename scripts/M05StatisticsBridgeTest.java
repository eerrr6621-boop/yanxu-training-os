package com.training;

import static com.training.DeliverySettlement.*;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only M05 -> M06 contract check; owns no M06 file and uses only synthetic facts. */
public final class M05StatisticsBridgeTest {
    public static void main(String[] args) {
        LocalDate date = LocalDate.parse("2099-01-01"); Instant at = Instant.parse("2099-01-02T00:00:00Z");
        Dimensions dims = new Dimensions("DEMO-G1", "DEMO-DAY", "DEMO-ONSITE", "DEMO-CLASS45");
        Verification verification = new Verification("DEMO-CHECK1", at, "DEMO-EVIDENCE1");
        RuleVersion rule = new RuleVersion("DEMO-R1", "DEMO-SOURCE1", date, null, DATE_BASIS, FORMULA,
                "CNY", 2, RoundingMode.HALF_UP, ROUNDING_SCOPE, 45, DataMode.SYNTHETIC_DEMO);
        RateVersion rate = new RateVersion("DEMO-T1", "DEMO-SOURCE2", rule.versionCode(), date, null, dims,
                decimalInput("300"), DataMode.SYNTHETIC_DEMO);
        Fact before = new Fact(1, 1, "DEMO-S1", "DEMO-TCH1", "DEMO-ORG1", date, dims,
                new Hours(decimalInput("3.5"), decimalInput("3"), decimalInput("2.5"), decimalInput("2")), verification, DataMode.SYNTHETIC_DEMO);
        Fact after = new Fact(1, 2, "DEMO-S1", "DEMO-TCH1", "DEMO-ORG1", date, dims,
                new Hours(decimalInput("3.5"), decimalInput("3"), decimalInput("3"), decimalInput("2.5")), verification, DataMode.SYNTHETIC_DEMO);
        Access access = new Access("DEMO-OP1", Set.of(Permission.CONFIRM, Permission.CORRECT), Set.of("DEMO-ORG1"));
        Confirmed original = confirm(calculate(before, rule, rate), before, "DEMO-STL1", at, access);
        Confirmed correction = correct(original, calculate(after, rule, rate), after, "DEMO-STL2", "DEMO-REASON1",
                new CorrectionPolicy("DEMO-C1", "DEMO-CS1", date, null, DATE_BASIS, "REPLACEMENT_DELTA", DataMode.SYNTHETIC_DEMO), at, access);
        Map<String,Object> a = reportingContribution(original), b = reportingContribution(correction);
        a.put("id", "101"); b.put("id", "102");
        for (Map<String,Object> m : List.of(a,b)) { m.put("courseId", "201"); m.put("courseCode", "DEMO-COURSE1"); }
        ManagementReports.Report report = ManagementReports.build(List.of(ManagementReports.factFromMap(a), ManagementReports.factFromMap(b)),
                ManagementReports.agreedDefaultRules("CNY"), new ManagementReports.Filter(date, date, Set.of()),
                new ManagementReports.AccessScope(Set.of("DEMO-ORG1"), true, true));
        if (report.total().fee().compareTo(decimalInput("750")) != 0) throw new AssertionError("original600 + delta150 must equal750");
        if (report.hourTotals().get(ManagementReports.HourBasis.ESTIMATED).hours().compareTo(decimalInput("3.5")) != 0) throw new AssertionError("estimated delta");
        if (report.hourTotals().get(ManagementReports.HourBasis.PLANNED).hours().compareTo(decimalInput("3")) != 0) throw new AssertionError("planned delta");
        if (report.hourTotals().get(ManagementReports.HourBasis.ACTUAL).hours().compareTo(decimalInput("3")) != 0) throw new AssertionError("actual delta");
        if (report.hourTotals().get(ManagementReports.HourBasis.PAYABLE).hours().compareTo(decimalInput("2.5")) != 0) throw new AssertionError("payable delta");
        if (!report.reconciliation().matches()) throw new AssertionError("detail and totals must reconcile");
        System.out.println("M05StatisticsBridgeTest: 6 assertions passed (M06 unchanged; synthetic only)");
    }
}
