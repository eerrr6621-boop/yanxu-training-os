package com.training;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.training.ManagementReports.*;

/** Offline synthetic fixture reader for cross-language checks. Not a live API or authorization adapter. */
public final class M06ReportsProbe {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("仅接受一个合成演示 JSON 文件路径");
        Map<String, Object> input = Json.parseMap(Files.readString(Path.of(args[0])));
        if (!"SYNTHETIC_DEMO".equals(input.get("dataMode"))) throw new IllegalArgumentException("探针仅供合成演示");
        List<Fact> facts = new ArrayList<>(); Set<String> orgs = new HashSet<>();
        for (Object raw : (List<?>) input.get("facts")) {
            Fact f = factFromMap((Map<String, Object>) raw);
            if (!List.of(f.recordCode(), f.teacherCode(), f.orgCode(), f.courseCode(), f.feeVersion()).stream().allMatch(c -> c.startsWith("DEMO-")))
                throw new IllegalArgumentException("探针只接受 DEMO 编码");
            facts.add(f); orgs.add(f.orgCode());
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object raw : (List<?>) input.get("cases")) {
            Map<String, Object> test = (Map<String, Object>) raw;
            try {
                Rules rules = rulesFromMap((Map<String, Object>) test.get("rules"));
                if (!rules.version().startsWith("DEMO-")) throw new IllegalArgumentException("探针只接受 DEMO 规则");
                Report report = build(facts, rules, filterFromMap((Map<String, Object>) test.get("filter")), new AccessScope(orgs, true, true));
                results.add(Map.of("ok", true, "report", report.toMap()));
            } catch (IllegalArgumentException | SecurityException ex) {
                results.add(Map.of("ok", false, "error", ex.getMessage()));
            }
        }
        System.out.println(Json.write(results));
    }
}
