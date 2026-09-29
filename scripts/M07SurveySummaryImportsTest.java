package com.training;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.training.SurveySummaryImports.*;

/** Focused M07 checks; does not connect to a database or start the application. */
public final class M07SurveySummaryImportsTest {
    private static int assertions;
    private static final String HEADER = "project_key,summary_key,metric_key,value,unit,sample_count,revision\n";
    private static final String VALID = "DEMO-P001,DEMO-S002,satisfaction_pct,92.50%,PERCENT,40,v1";
    private static Preview check(String data) { return run(HEADER + data, Mapping.canonical(), syntheticContext()); }
    private static Preview run(String csv, Mapping mapping, PreviewContext context) {
        return preview(csv.getBytes(StandardCharsets.UTF_8), new CsvAdapter(), syntheticProfile(), mapping, context);
    }
    private static void expect(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static boolean has(Preview p, String code) { return p.issues().stream().anyMatch(i -> code.equals(i.code())); }
    private static void noErrors(Preview p) { expect(p.issues().stream().noneMatch(i -> "ERROR".equals(i.severity())), "Unexpected errors: " + p.issues()); }
    public static void main(String[] args) {
        Preview normal = check(VALID + "\nDEMO-P002,DEMO-S003,overall_score,4.60,SCORE,25,v1\n");
        noErrors(normal); expect(normal.rows().size() == 2, "Two rows retained");
        expect(normal.rows().get(0).projectId == 9001, "Explicit numeric project id");
        expect(normal.rows().get(0).valueText.equals("92.50%"), "Original value retained");
        expect(normal.rows().get(1).valueText.equals("4.60"), "Score precision retained");
        expect(Boolean.FALSE.equals(normal.toMap().get("canCommit")), "Never writes");
        expect(has(normal, "PREVIEW_ONLY"), "Rule gate always visible");
        expect(has(check(VALID.replace("92.50%", "100.01")), "OUT_OF_RANGE"), "Percent upper bound");
        expect(has(check(VALID.replace("92.50%", "-1")), "OUT_OF_RANGE"), "Percent lower bound");
        expect(has(check(VALID.replace("PERCENT", "SCORE")), "UNIT_MISMATCH"), "Unit mismatch");
        expect(has(check(VALID.replace("satisfaction_pct", "overall_score").replace("PERCENT", "SCORE")), "PERCENT_IN_SCORE"), "No percent in score");
        expect(has(check(VALID.replace("92.50%", "NaN")), "INVALID_NUMBER"), "Reject NaN");
        expect(has(check(VALID.replace("92.50%", "1e2")), "INVALID_NUMBER"), "Do not infer scientific notation");
        expect(has(check(VALID.replace("92.50%", "\"92,5\"")), "INVALID_NUMBER"), "Do not infer locale decimal");
        expect(has(check(VALID.replace("40", "2.5")), "INVALID_SAMPLE_COUNT"), "Reject fractional samples");
        expect(has(check(VALID.replace("40", "-1")), "INVALID_SAMPLE_COUNT"), "Reject negative samples");
        expect(has(check(VALID.replace("40", "2147483648")), "INVALID_SAMPLE_COUNT"), "Reject int overflow");
        noErrors(check(VALID.replace("40", "0")));
        Preview fraction = check(VALID.replace("92.50%", "0.925"));
        noErrors(fraction); expect(fraction.rows().get(0).valueText.equals("0.925"), "No implicit ratio-to-percent conversion");
        expect(has(check(VALID.replace("satisfaction_pct", "unknown")), "UNKNOWN_METRIC"), "Unknown scale blocked");
        expect(has(check(VALID.replace("DEMO-P001", "9001")), "PROJECT_UNAVAILABLE"), "Do not parse project key as id");
        expect(has(check(VALID.replace("DEMO-P001", "合成项目一")), "PROJECT_UNAVAILABLE"), "No name-based link");

        Preview duplicates = check(VALID + "\n" + VALID);
        expect(duplicates.rows().size() == 2, "Keep duplicated rows");
        expect(duplicates.rows().stream().allMatch(r -> r.duplicateStatus.equals("IN_FILE_DUPLICATE")), "Flag both duplicates");
        expect(has(check(VALID + "\n" + VALID.replace("92.50%", "95")), "IN_FILE_CONFLICT"), "In-file value conflict");
        expect(has(check(VALID + "\n" + VALID.replace("40", "41")), "IN_FILE_CONFLICT"), "Sample change is conflict");
        expect(has(check(VALID + "\n" + VALID.replace("v1", "v2")), "IN_FILE_CONFLICT"), "Revision change in same file requires review");
        expect(has(check(VALID.replace("DEMO-S002", "DEMO-S001")), "EXISTING_SAME"), "Existing canonical numerical equality");
        expect(has(check(VALID.replace("DEMO-S002", "DEMO-S001").replace("92.50%", "91")), "EXISTING_CONFLICT"), "Existing value conflict");
        expect(has(check(VALID.replace("DEMO-S002", "DEMO-S001").replace("v1", "v2")), "REVISION_CANDIDATE"), "Revision differs, not auto-replaced");
        Preview sameMetricDifferentProject = check(VALID.replace("DEMO-S002", "DEMO-S001").replace("DEMO-P001", "DEMO-P002"));
        expect(sameMetricDifferentProject.rows().get(0).duplicateStatus.equals("NONE"), "Duplicate key includes project");

        PreviewContext invisible = new PreviewContext(true, List.of(new Project(9002, "DEMO-P002", "合成项目二")), syntheticContext().existingSummaries());
        Preview hidden = run(HEADER + VALID.replace("DEMO-S002", "DEMO-S001"), Mapping.canonical(), invisible);
        expect(has(hidden, "PROJECT_UNAVAILABLE"), "Invisible project rejected");
        expect(hidden.rows().get(0).existingRecordIds.isEmpty(), "No hidden record ids disclosed");
        expect(!hidden.rows().get(0).duplicateChecked, "Invalid project row cannot claim duplicate check success");
        expect(normal.rows().get(0).duplicateChecked, "Valid row was checked for duplicates");
        expect(hidden.rows().get(0).projectId == null && hidden.rows().get(0).projectName.isEmpty(), "No hidden project metadata");
        try { run(HEADER + VALID, Mapping.canonical(), new PreviewContext(false, List.of(), List.of())); throw new AssertionError("Permission bypass"); }
        catch (SecurityException expected) { assertions++; }
        try { run(HEADER + VALID, Mapping.canonical(), null); throw new AssertionError("Anonymous bypass"); }
        catch (SecurityException expected) { assertions++; }
        PreviewContext ambiguous = new PreviewContext(true, List.of(new Project(9001, "DEMO-P001", "A"), new Project(9002, "DEMO-P001", "B")), List.of());
        expect(has(run(HEADER + VALID, Mapping.canonical(), ambiguous), "PROJECT_AMBIGUOUS"), "Ambiguous project code blocked");
        ExistingSummary oldOtherSource = new ExistingSummary(7009, 9001, "OTHER", "DEMO-S002", "satisfaction_pct", new BigDecimal("92.5"), Unit.PERCENT, 40, "v1");
        expect(run(HEADER + VALID, Mapping.canonical(), new PreviewContext(true, syntheticContext().visibleProjects(), List.of(oldOtherSource))).rows().get(0).duplicateStatus.equals("NONE"), "Different source not duplicate");

        Map<String, String> names = new LinkedHashMap<>();
        for (Field field : Field.values()) names.put(field.key, field.key);
        names.put("project_key", "项目编码");
        noErrors(run(HEADER.replace("project_key", "项目编码") + VALID, Mapping.fromNames(names), syntheticContext()));
        names.put("value", "missing");
        expect(has(run(HEADER + VALID, Mapping.fromNames(names), syntheticContext()), "MAPPING_COLUMN_MISSING"), "Missing mapping column");
        names.put("project_key", "project_key"); names.put("value", "unit");
        expect(has(run(HEADER + VALID, Mapping.fromNames(names), syntheticContext()), "MAPPING_REUSED"), "Do not map same column twice");
        expect(has(run(HEADER + VALID, new Mapping(Map.of()), syntheticContext()), "MAPPING_REQUIRED"), "Required mappings");
        Map<Field, String> optional = new EnumMap<>(Mapping.canonical().columns()); optional.remove(Field.REVISION);
        noErrors(run(HEADER.replace(",revision", "") + VALID.replace(",v1", ""), new Mapping(optional), syntheticContext()));
        noErrors(run(HEADER.replace(",revision", "") + VALID.replace(",v1", ""), Mapping.suggested(Arrays.asList("project_key", "summary_key", "metric_key", "value", "unit", "sample_count")), syntheticContext()));

        noErrors(run("\uFEFF" + (HEADER + VALID).replace("\n", "\r\n"), Mapping.canonical(), syntheticContext()));
        noErrors(check(VALID.replace("v1", "\"v,1\"")));
        Preview quoted = check(VALID.replace("v1", "\"v\"\"1\nsecond line\"") + "\n" + VALID.replace("DEMO-S002", "DEMO-S003"));
        noErrors(quoted); expect(quoted.rows().get(0).revision.equals("v\"1\nsecond line"), "Escaped quotes and multiline retained");
        expect(quoted.rows().get(1).line == 4, "Physical source line numbers");
        expect(has(check(VALID + ",extra"), "COLUMN_COUNT"), "Extra column retained as invalid row");
        Preview extraCollision = run(HEADER.replace(",revision", ",revision,[extra_9]") + VALID + ",original,overflow", Mapping.canonical(), syntheticContext());
        expect(extraCollision.rows().get(0).raw.get("[extra_9]").equals("original"), "Extra fields never overwrite named header");
        expect(extraCollision.rows().get(0).rawCells.get(8).equals("overflow"), "Extra field retained positionally");
        expect(has(check(VALID.replace(",v1", "")), "COLUMN_COUNT"), "Short row");
        expect(has(run(HEADER.replace("summary_key", "project_key") + VALID, Mapping.canonical(), syntheticContext()), "DUPLICATE_HEADER"), "Duplicate headers");
        expect(has(check(VALID.replace("v1", "\"unterminated")), "CSV_SYNTAX"), "Unclosed quote");
        expect(has(check(VALID.replace("v1", "bad\"quote")), "CSV_SYNTAX"), "Stray quote");
        expect(has(check(VALID.replace("v1", "\"v1\"x")), "CSV_SYNTAX"), "Text after closing quote");
        expect(has(check(VALID.replace("v1", "x".repeat(MAX_CELL_CHARS + 1))), "CELL_LIMIT"), "Oversized cell");
        expect(has(run("x".repeat(MAX_BYTES + 1), Mapping.canonical(), syntheticContext()), "FILE_LIMIT"), "File size limit");
        expect(has(check((VALID + "\n").repeat(MAX_ROWS + 1)), "ROW_LIMIT"), "Row count limit");
        expect(new CsvAdapter().parse(("h,".repeat(64) + "h").getBytes(StandardCharsets.UTF_8)).issues().stream().anyMatch(i -> i.code().equals("COLUMN_LIMIT")), "Column limit");
        expect(new CsvAdapter().parse(new byte[]{(byte) 0xc3, 0x28}).issues().get(0).code().equals("ENCODING"), "Invalid UTF-8");
        expect(has(check(VALID + "\0"), "BINARY_FILE"), "Binary NUL");
        expect(has(run("", Mapping.canonical(), syntheticContext()), "EMPTY_FILE"), "Empty file");
        expect(has(run(HEADER, Mapping.canonical(), syntheticContext()), "NO_ROWS"), "Header only");
        expect(normal.toMap().get("synthetic").equals(true), "Demo provenance");
        System.out.println("M07 core passed: " + assertions + " assertions; no database access or business data writes.");
    }
}
