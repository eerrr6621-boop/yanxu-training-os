package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Synthetic transport-policy regression only. No network, production files, teachers or tickets.
 * Tests intentionally report unmet safety contracts rather than treating known defects as passes.
 * Compile with the same Java 17/ecj classpath as scripts/check.sh, then run this main class.
 */
public final class TransportReferencePolicyTest {
    static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    static final String FP = "江苏", FC = "南京", TP = "安徽", TC = "合肥";
    static int passed;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean ok, String label) {
        if (ok) passed++;
        else failures.add(label);
    }
    static void scenario(String label, Runnable body) {
        try { body.run(); }
        catch (Exception | AssertionError problem) {
            failures.add(label + ": " + problem.getClass().getSimpleName() + " " + problem.getMessage());
        }
    }
    static Map<String,Object> base(boolean reverse) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("from_province", reverse ? TP : FP); row.put("from_city", reverse ? TC : FC);
        row.put("to_province", reverse ? FP : TP); row.put("to_city", reverse ? FC : TC);
        row.put("source_url", "https://example.test/gray-transport-policy/" + (reverse ? "return" : "outbound"));
        row.put("source_provider", "【灰度测试】虚构来源");
        row.put("observed_on", DAY.toString()); row.put("source_service_date", DAY.toString());
        row.put("usage_authorized", true); row.put("business_import_allowed", true);
        return row;
    }
    static Map<String,Object> rail(boolean reverse, int minutes) {
        Map<String,Object> row = base(reverse), sample = new LinkedHashMap<>();
        row.put("status", "observed_timetable");
        sample.put("train_no", reverse ? "G90002" : "G90001");
        sample.put("from_station", reverse ? "【灰度测试】乙站" : "【灰度测试】甲站");
        sample.put("to_station", reverse ? "【灰度测试】甲站" : "【灰度测试】乙站");
        sample.put("departure_time", "08:00");
        sample.put("arrival_time", LocalTime.of(8, 0).plusMinutes(minutes).toString());
        sample.put("arrival_day_offset", (8 * 60 + minutes) / 1440);
        sample.put("rail_minutes", minutes); sample.put("station_city_mapping_checked", true);
        row.put("samples", List.of(sample));
        return row;
    }
    static Map<String,Object> audit(boolean reverse, String mode, int minutes) {
        Map<String,Object> row = base(reverse);
        row.put("scope", mode.equals("rail") ? "all_city_stations_and_rail_itineraries" : "all_city_airports_nonstop_services");
        row.put("coverage_complete", true); row.put("minimum_minutes", minutes);
        return row;
    }
    static Map<String,Object> flight(boolean reverse, int minutes) {
        Map<String,Object> row = base(reverse);
        row.put("flight_no", reverse ? "MU9002" : "MU9001");
        row.put("from_airport_code", reverse ? "BBB" : "AAA"); row.put("to_airport_code", reverse ? "AAA" : "BBB");
        row.put("departure_time", "08:00"); row.put("arrival_time", LocalTime.of(8, 0).plusMinutes(minutes).toString());
        row.put("arrival_day_offset", 0); row.put("flight_minutes", minutes);
        row.put("nonstop", true); row.put("stops", 0); row.put("airport_city_mapping_checked", true);
        row.put("service_type", "passenger"); row.put("service_mode", "air");
        row.put("cancelled", false); row.put("timezone", "Asia/Shanghai");
        return row;
    }
    static Map<String,Object> airData(int out, int back) {
        return new LinkedHashMap<>(Map.of("version", "transport-matrix-v1",
            "rail_checks", List.of(audit(false, "rail", 300), audit(true, "rail", 310)),
            "flights", List.of(flight(false, out), flight(true, back))));
    }
    static DispatchPreference pref(String province, String city) {
        return DispatchPreference.from(Map.of("training_province", province, "training_city", city,
            "training_mode", "线下", "training_period", "下午"), null);
    }
    static Map<String,Object> resolve(List<?> rails, Map<?,?> data, LocalDate today) {
        return TravelMatrix.resolve(rails, data, Map.of("base_province", FP, "base_city", FC), pref(TP, TC), today);
    }
    static boolean eligible(Map<String,Object> result, String eligibility) {
        return eligibility.equals(result.get("eligibility"));
    }
    static Map<String,Object> candidate(long id, int score, String level, boolean local, Map<String,Object> route) {
        Map<String,Object> fit = new LinkedHashMap<>();
        fit.put("local_priority", local);
        if (route != null) fit.put("route_reference", route);
        return new LinkedHashMap<>(Map.of("teacher_id", id, "name", "【灰度测试】合成讲师" + id,
            "model_score", score, "recommendation_score", score, "teacher_level", level, "dispatch_fit", fit));
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> result, String field) {
        return result.get(field) instanceof List<?> ? (List<Map<String,Object>>) result.get(field) : List.of();
    }
    static List<Object> ids(Map<String,Object> result) {
        return items(result, "selected").stream().map(row -> row.get("teacher_id")).toList();
    }
    static boolean containsField(Object value, String key, Object expected) {
        if (value instanceof Map<?,?>) {
            Map<?,?> map = (Map<?,?>) value;
            if (map.containsKey(key) && Objects.equals(map.get(key), expected)) return true;
            for (Object child : map.values()) if (containsField(child, key, expected)) return true;
        } else if (value instanceof List<?>) {
            for (Object child : (List<?>) value) if (containsField(child, key, expected)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked") static void railAndAirBoundaries() {
        Map<String,Object> shortRail = resolve(List.of(rail(false, 80), rail(true, 239)), Map.of(), DAY);
        check(eligible(shortRail, "rail"), "B01 independently sourced 80/239 minute directions qualify");
        check(Objects.equals(shortRail.get("return_reference_minutes"), 239), "B02 return duration is not copied from outbound");
        check(Boolean.FALSE.equals(shortRail.get("transport_verified")) && Boolean.FALSE.equals(shortRail.get("travel_date_verified")),
            "B03 city reference is not an actual-trip guarantee");
        check(eligible(resolve(List.of(rail(false, 239)), Map.of(), DAY), "unknown"), "B04 one-way rail remains unknown");
        check(eligible(resolve(List.of(rail(true, 239)), Map.of(), DAY), "unknown"), "B05 return-only rail remains unknown");
        for (int minutes : List.of(240, 241)) for (boolean reverse : List.of(false, true)) {
            List<?> rails = List.of(rail(false, reverse ? 100 : minutes), rail(true, reverse ? minutes : 100));
            check(eligible(resolve(rails, Map.of(), DAY), "unknown"), "B06 strict 240 boundary on either direction: " + minutes + "/" + reverse);
        }
        Map<String,Object> noAudit = airData(100, 100); noAudit.remove("rail_checks");
        check(eligible(resolve(List.of(rail(false, 500), rail(true, 510)), noAudit, DAY), "unknown"),
            "B07 slow samples cannot exclude all rail and enable air");
        check(eligible(resolve(List.of(), airData(179, 179), DAY), "air"), "B08 complete rail checks allow bilateral 179-minute air");
        for (boolean reverse : List.of(false, true)) {
            check(eligible(resolve(List.of(), airData(reverse ? 100 : 180, reverse ? 180 : 100), DAY), "unknown"),
                "B09 180-minute air boundary is strict on either direction: " + reverse);
        }
        Map<String,Object> oneFlight = airData(100, 100); oneFlight.put("flights", List.of(flight(false, 100)));
        check(eligible(resolve(List.of(), oneFlight, DAY), "unknown"), "B10 one-way flight remains unknown");
        Map<String,Object> partial = airData(100, 100), partialAudit = audit(false, "rail", 300);
        partialAudit.put("coverage_complete", false); partial.put("rail_checks", List.of(partialAudit, audit(true, "rail", 300)));
        check(eligible(resolve(List.of(), partial, DAY), "unknown"), "B11 incomplete railway coverage cannot enable air");
        check(eligible(resolve(List.of(rail(false, 100), rail(true, 100)), airData(90, 90), DAY), "rail"),
            "B12 qualifying rail takes priority over a shorter flight");
        Map<String,Object> bad = rail(false, 100);
        ((Map<String,Object>) ((List<?>) bad.get("samples")).get(0)).put("arrival_time", "08:01");
        check(eligible(resolve(List.of(bad, rail(true, 100)), Map.of(), DAY), "unknown"), "B13 clock arithmetic must agree with minutes");
        Map<String,Object> unavailable = RailTravel.unavailable(FC, TC, null);
        check(Boolean.FALSE.equals(unavailable.get("verified")) && unavailable.get("duration_minutes") == null
            && unavailable.get("fare") == null && ((List<?>) unavailable.get("services")).isEmpty(), "B14 missing provider invents no services or times");
    }

    static void ageAndPermission() {
        List<?> rails = List.of(rail(false, 90), rail(true, 100));
        String original = Json.write(rails);
        check(eligible(resolve(rails, Map.of(), DAY.plusDays(30)), "rail"), "A01 existing 30-day inclusive safety boundary remains unchanged");
        Map<String,Object> stale = resolve(rails, Map.of(), DAY.plusDays(31));
        check(eligible(stale, "unknown"), "A02 expired evidence cannot silently become timeless qualified rail");
        check(!eligible(stale, "outside"), "A03 expired evidence is not an exclusion conclusion");
        check(Json.write(rails).equals(original), "A04 expiry does not destroy stored historical source rows");
        check(containsField(stale, "observed_on", DAY.toString()) && containsField(stale, "source_url", base(false).get("source_url")),
            "GAP-A05 pending historical reference must retain original source and checked date for review");
        Map<String,Object> oldService = rail(false, 90); oldService.put("source_service_date", DAY.minusDays(31).toString());
        check(eligible(resolve(List.of(oldService, rail(true, 100)), Map.of(), DAY), "unknown"),
            "A06 observing an old source today cannot reset its timetable validity");
        check(eligible(resolve(List.of(), airData(90, 90), DAY.plusDays(31)), "unknown"), "A07 expired rail exclusion and flight evidence remain unknown");
        Map<String,Object> forbidden = rail(false, 90);
        forbidden.put("research_only", true); forbidden.put("business_import_allowed", false); forbidden.put("usage_authorized", false);
        check(eligible(resolve(List.of(forbidden, rail(true, 100)), Map.of(), DAY), "unknown"),
            "GAP-A08 explicitly noncommercial research rows cannot become production rail references");
        Map<String,Object> nested = new LinkedHashMap<>(Map.of("rail_directions", List.of(forbidden, rail(true, 100))));
        check(eligible(resolve(List.of(), nested, DAY), "unknown"), "GAP-A09 matrix rail_directions cannot bypass the same research permission gate");
        for (Map<String,Object> flag : List.of(Map.<String,Object>of("research_only", true),
            Map.<String,Object>of("business_import_allowed", false), Map.<String,Object>of("usage_authorized", false))) {
            Map<String,Object> deniedRow = rail(false, 90); deniedRow.putAll(flag);
            check(eligible(resolve(List.of(deniedRow, rail(true, 100)), Map.of(), DAY), "unknown"), "A10 each explicit prohibition independently rejects a rail row: " + flag);
            Map<String,Object> deniedBundle = new LinkedHashMap<>(Map.of("rail_directions", List.of(rail(false, 90), rail(true, 100))));
            deniedBundle.putAll(flag);
            check(eligible(resolve(List.of(), deniedBundle, DAY), "unknown"), "A11 a forbidden bundle cannot grant use to otherwise normal rail rows: " + flag);
        }
        Map<String,Object> compatible = rail(false, 90); compatible.remove("usage_authorized"); compatible.remove("business_import_allowed");
        check(eligible(resolve(List.of(compatible, rail(true, 100)), Map.of(), DAY), "rail"), "A12 older reviewed data without new permission fields remains compatible");
    }

    @SuppressWarnings("unchecked") static void sorting() {
        Map<String,Object> shortRail = resolve(List.of(rail(false, 100), rail(true, 110)), Map.of(), DAY);
        Map<String,Object> longerRail = resolve(List.of(rail(false, 220), rail(true, 230)), Map.of(), DAY);
        Map<String,Object> air = resolve(List.of(), airData(100, 100), DAY);
        List<Map<String,Object>> rows = new ArrayList<>(List.of(candidate(1, 60, "特聘讲师", true, null),
            candidate(2, 80, "特聘讲师", false, shortRail), candidate(3, 95, "讲师", false, longerRail),
            candidate(4, 80, "高级讲师", false, shortRail), candidate(5, 80, "特级讲师", false, longerRail),
            candidate(6, 99, "特聘讲师", false, air), candidate(7, 100, "特聘讲师", false, null)));
        Map<String,Object> selected = NearbySelection.select(rows, pref(TP, TC), 3);
        check(ids(selected).equals(List.of(3L, 2L, 5L, 4L)), "S01 admit entire rail range, then model score, then descending level; all cutoff ties stay");
        check(items(selected, "travel_pending").size() == 1, "S02 high score or level cannot rescue unknown transport");
        check(!ids(selected).contains(6L), "S03 higher-scoring air cannot displace a sufficient rail pool");
        selected = NearbySelection.select(List.of(candidate(10, 30, "讲师", false, shortRail),
            candidate(11, 99, "特聘讲师", false, air), candidate(12, 99, "高级讲师", false, air),
            candidate(13, 99, "特级讲师", false, air)), pref(TP, TC), 3);
        check(ids(selected).equals(List.of(10L, 11L, 13L, 12L)), "S04 air fills shortage after rail, retaining all third-score ties in that pool");
        Map<String,Object> fakeDistance = candidate(20, 100, "特聘讲师", false, null);
        ((Map<String,Object>) fakeDistance.get("dispatch_fit")).put("city_priority", Map.of("distance_km", 1, "priority_score", 100));
        check(ids(NearbySelection.select(List.of(fakeDistance), pref(TP, TC), 3)).isEmpty(), "S05 geographic distance is not a rail duration");
        DispatchPreference unknown = pref(TP, "灰度新增城市");
        selected = NearbySelection.select(List.of(candidate(30, 99, "特聘讲师", false, null)), unknown, 3);
        check(ids(selected).isEmpty() && items(selected, "travel_pending").size() == 1,
            "GAP-S06 offline preferred-local unknown destination must stay pending, not disable geographic restrictions");
        DispatchPreference online = DispatchPreference.from(Map.of("training_mode", "线上"), null);
        check(ids(NearbySelection.select(List.of(candidate(40, 90, "讲师", false, null)), online, 3)).equals(List.of(40L)),
            "S07 explicit online lessons need no geographic restriction");
        Map<String,Object> rejectedRail = new LinkedHashMap<>(shortRail);
        rejectedRail.put("eligibility", "unknown");
        check(ids(NearbySelection.select(List.of(candidate(50, 100, "特聘讲师", false, rejectedRail)), pref(TP, TC), 3)).isEmpty(),
            "GAP-S08 explicit unknown eligibility must veto an older rail_time_reference presentation status");
    }

    static void catalog(Path testRoot) throws Exception {
        Path fixture = testRoot.resolve("synthetic-timetable.json");
        List<Map<String,Object>> rails = new ArrayList<>(List.of(rail(false, 90), rail(true, 100)));
        Files.writeString(fixture, Json.write(Map.of("version", "rail-timetable-v1", "directions", rails)));
        System.setProperty("dispatch.timetable.file", fixture.toString());
        int universe = RegionDirectory.CITIES.values().stream().mapToInt(Set::size).sum();
        Map<String,Object> catalog = DispatchPriority.catalog(TP, TC, "", DAY);
        check(Objects.equals(catalog.get("enumerated_count"), universe), "C01 city catalog enumerates the entire directory without teacher or organization roster");
        check(items(catalog, "priorities").stream().anyMatch(row -> FC.equals(row.get("city"))), "C02 a sourced city is admitted without having an enrolled teacher");
        check(Objects.equals(catalog.get("unknown_count"), universe - 2) && Boolean.FALSE.equals(catalog.get("transport_scope_complete")),
            "C03 directory coverage is not evidence coverage; all unreviewed cells remain unknown");
        Map<String,Object> muchLater = DispatchPriority.catalog(TP, TC, "2099-12-31", DAY);
        check(Json.write(catalog.get("priorities")).equals(Json.write(muchLater.get("priorities"))),
            "C04 city-reference selection does not assert service on a chosen distant course date");
        catalog = DispatchPriority.catalog(TP, TC, "", DAY.plusDays(31));
        check(Objects.equals(catalog.get("unknown_count"), universe - 1) && items(catalog, "outside_nearby_routes").isEmpty(),
            "C05 expired rail pair returns to pending instead of outside");
        check(!Objects.equals(catalog.get("reference_checked_on"), DAY.plusDays(31).toString()),
            "GAP-C06 opening catalog today must not manufacture a new source-verification date");
        Set<String> existing = RegionDirectory.CITIES.get(TP);
        try {
            Set<String> expanded = new LinkedHashSet<>(existing); expanded.add("灰度新增城市");
            RegionDirectory.CITIES.put(TP, Collections.unmodifiableSet(expanded));
            catalog = DispatchPriority.catalog(TP, TC, "", DAY);
            check(Objects.equals(catalog.get("enumerated_count"), universe + 1), "C07 expanding directory expands reference universe, not a fixed branch/teacher subset");
            check(items(catalog, "pending_routes").stream().anyMatch(row -> "灰度新增城市".equals(row.get("city"))),
                "C08 a newly registered city remains pending until actual bilateral evidence exists");
        } finally { RegionDirectory.CITIES.put(TP, existing); }
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> original = new LinkedHashMap<>();
        for (String key : List.of("dispatch.timetable.file", "dispatch.transport.file", "dispatch.transport.dir",
            "dispatch.routes.file", "dispatch.organizations.file", "dispatch.city.references.file", "dispatch.city.references.dir", "dispatch.nearby.max.minutes")) {
            original.put(key, System.getProperty(key)); System.setProperty(key, key.endsWith("max.minutes") ? "240" : "");
        }
        Path testRoot = Files.createTempDirectory("yanxu-gray-transport-policy-");
        try {
            scenario("rail and air boundaries", TransportReferencePolicyTest::railAndAirBoundaries);
            scenario("age and permission", TransportReferencePolicyTest::ageAndPermission);
            scenario("sorting", TransportReferencePolicyTest::sorting);
            try { catalog(testRoot); }
            catch (Exception | AssertionError problem) { failures.add("catalog: " + problem); }
        } finally {
            for (Map.Entry<String,String> setting : original.entrySet()) {
                if (setting.getValue() == null) System.clearProperty(setting.getKey());
                else System.setProperty(setting.getKey(), setting.getValue());
            }
            Files.deleteIfExists(testRoot.resolve("synthetic-timetable.json")); Files.deleteIfExists(testRoot);
        }
        System.out.println("Synthetic transport reference policy: " + passed + " passed; " + failures.size() + " failed");
        for (String failure : failures) System.err.println("FAIL " + failure);
        if (!failures.isEmpty()) throw new AssertionError("Unmet transport policy contracts: " + failures.size());
    }
}
