package com.training;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** M07: read-only preview of already aggregated survey exports. No persistence or scoring. */
public final class SurveySummaryImports {
    private SurveySummaryImports() {}
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 5000;
    public static final int MAX_COLUMNS = 64;
    public static final int MAX_CELL_CHARS = 4096;

    public enum Unit { PERCENT, SCORE }
    public enum Field {
        PROJECT_KEY("project_key"), SUMMARY_KEY("summary_key"), METRIC_KEY("metric_key"),
        VALUE("value"), UNIT("unit"), SAMPLE_COUNT("sample_count"), REVISION("revision");
        public final String key;
        Field(String key) { this.key = key; }
    }

    public record MetricSpec(String key, String label, Unit unit, BigDecimal min, BigDecimal max) {
        public MetricSpec {
            required(key, "指标识别值"); required(label, "指标名称");
            Objects.requireNonNull(unit); Objects.requireNonNull(min); Objects.requireNonNull(max);
            if (min.compareTo(max) >= 0) throw new IllegalArgumentException("指标范围须递增");
            if (unit == Unit.PERCENT && (min.signum() != 0 || max.compareTo(new BigDecimal("100")) != 0))
                throw new IllegalArgumentException("百分数范围必须明确为 0..100");
        }
    }

    /** Server-owned interpretation, never constructed from an untrusted request body. */
    public record Profile(String adapterId, String sourceId, boolean synthetic,
                          String sampleCountMeaning, List<MetricSpec> metrics) {
        public Profile {
            required(adapterId, "适配器版本"); required(sourceId, "来源标识");
            required(sampleCountMeaning, "样本数定义"); metrics = List.copyOf(metrics);
            if (metrics.isEmpty()) throw new IllegalArgumentException("必须先定义汇总指标和量表");
            Set<String> keys = new HashSet<>();
            for (MetricSpec metric : metrics)
                if (!keys.add(metric.key())) throw new IllegalArgumentException("指标识别值重复");
        }
    }

    public record Project(long id, String externalKey, String label) {
        public Project {
            if (id <= 0) throw new IllegalArgumentException("项目记录 id 必须为正整数");
            required(externalKey, "项目识别值"); required(label, "项目名称");
        }
    }
    public record ExistingSummary(long recordId, long projectId, String sourceId, String summaryKey,
                                  String metricKey, BigDecimal value, Unit unit, int sampleCount,
                                  String revision) {
        public ExistingSummary {
            if (recordId <= 0 || projectId <= 0 || sampleCount < 0) throw new IllegalArgumentException("历史汇总参数无效");
            required(sourceId, "来源标识"); required(summaryKey, "汇总标识"); required(metricKey, "指标识别值");
            Objects.requireNonNull(value); Objects.requireNonNull(unit);
            revision = revision == null ? "" : revision;
        }
    }

    /** Host derives permission and the visible project catalog from its authenticated session. */
    public record PreviewContext(boolean mayPreview, List<Project> visibleProjects,
                                 List<ExistingSummary> existingSummaries) {
        public PreviewContext {
            visibleProjects = List.copyOf(visibleProjects);
            existingSummaries = List.copyOf(existingSummaries);
        }
    }
    public record Mapping(Map<Field, String> columns) {
        public Mapping { columns = Map.copyOf(columns); }
        public static Mapping canonical() {
            Map<Field, String> columns = new EnumMap<>(Field.class);
            for (Field field : Field.values()) columns.put(field, field.key);
            return new Mapping(columns);
        }
        /** Optional revision is omitted only for a suggested mapping, never for an explicit mapping. */
        public static Mapping suggested(List<String> headers) {
            Map<Field, String> columns = new EnumMap<>(Field.class);
            for (Field field : Field.values()) if (headers.contains(field.key)) columns.put(field, field.key);
            return new Mapping(columns);
        }
        public static Mapping fromNames(Map<String, String> names) {
            Map<Field, String> columns = new EnumMap<>(Field.class);
            for (Map.Entry<String, String> entry : names.entrySet()) {
                Field field = Arrays.stream(Field.values()).filter(f -> f.key.equals(entry.getKey()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("未知映射字段: " + entry.getKey()));
                if (entry.getValue() != null && !entry.getValue().isBlank()) columns.put(field, entry.getValue());
            }
            return new Mapping(columns);
        }
    }
    public record Issue(int line, String field, String code, String message, String severity) {
        public Map<String, Object> toMap() {
            return Map.of("line", line, "field", field, "code", code, "message", message, "severity", severity);
        }
    }
    public record RawRow(int line, List<String> cells) {
        public RawRow { cells = List.copyOf(cells); }
    }
    public record ParsedTable(List<String> headers, List<RawRow> rows, List<Issue> issues) {
        public ParsedTable { headers = List.copyOf(headers); rows = List.copyOf(rows); issues = List.copyOf(issues); }
    }
    /** Extend this boundary after receiving a platform example; do not infer Excel or questionnaire schemas. */
    public interface SummaryAdapter { ParsedTable parse(byte[] bytes); }

    public static final class CsvAdapter implements SummaryAdapter {
        @Override public ParsedTable parse(byte[] bytes) {
            List<Issue> issues = new ArrayList<>();
            if (bytes == null || bytes.length == 0) return failed("EMPTY_FILE", "文件为空");
            if (bytes.length > MAX_BYTES) return failed("FILE_LIMIT", "文件不能超过 2 MiB");
            final String decoded;
            try {
                decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) { return failed("ENCODING", "需要 UTF-8 CSV；其他格式待样例适配"); }
            String csv = decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
            if (csv.indexOf('\0') >= 0) return failed("BINARY_FILE", "文件含二进制内容，当前仅支持 UTF-8 CSV");
            List<RawRow> records = new ArrayList<>();
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false, afterQuote = false, started = false;
            int line = 1, rowLine = 1;
            for (int i = 0; i < csv.length(); i++) {
                char ch = csv.charAt(i);
                if (quoted) {
                    if (ch == '"') {
                        if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cell.append('"'); i++; }
                        else { quoted = false; afterQuote = true; }
                    } else if (ch == '\r' || ch == '\n') {
                        if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                        cell.append('\n'); line++;
                    } else cell.append(ch);
                } else if (ch == ',' || ch == '\r' || ch == '\n') {
                    cells.add(cell.toString()); cell.setLength(0); afterQuote = false; started = true;
                    if (cells.size() > MAX_COLUMNS) return failed("COLUMN_LIMIT", "列数不能超过 64");
                    if (ch != ',') {
                        if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                        records.add(new RawRow(rowLine, cells)); cells = new ArrayList<>();
                        if (records.size() > MAX_ROWS + 1) return failed("ROW_LIMIT", "最多预览 5000 条汇总记录");
                        line++; rowLine = line; started = false;
                    }
                } else if (ch == '"') {
                    if (cell.length() != 0 || afterQuote) return syntax(rowLine, "引号必须位于字段开头");
                    quoted = true; started = true;
                } else {
                    if (afterQuote) return syntax(rowLine, "结束引号后只能接逗号或换行");
                    cell.append(ch); started = true;
                }
                if (cell.length() > MAX_CELL_CHARS) return failed("CELL_LIMIT", "单元格不能超过 4096 字符");
            }
            if (quoted) return syntax(rowLine, "引号未闭合");
            if (started || !cells.isEmpty() || cell.length() > 0) {
                cells.add(cell.toString()); records.add(new RawRow(rowLine, cells));
            }
            if (records.size() > MAX_ROWS + 1) return failed("ROW_LIMIT", "最多预览 5000 条汇总记录");
            if (cells.size() > MAX_COLUMNS) return failed("COLUMN_LIMIT", "列数不能超过 64");
            if (records.isEmpty()) return failed("EMPTY_FILE", "文件为空");
            List<String> headers = records.remove(0).cells().stream().map(String::strip).toList();
            Set<String> unique = new HashSet<>();
            for (String header : headers) {
                if (header.isEmpty()) issues.add(error(1, "header", "EMPTY_HEADER", "表头不能为空"));
                if (!unique.add(header)) issues.add(error(1, "header", "DUPLICATE_HEADER", "表头重复: " + header));
            }
            if (records.isEmpty()) issues.add(error(1, "", "NO_ROWS", "文件只有表头，没有汇总记录"));
            return new ParsedTable(headers, records, issues);
        }
        private static ParsedTable syntax(int line, String message) {
            return new ParsedTable(List.of(), List.of(), List.of(error(line, "", "CSV_SYNTAX", message)));
        }
        private static ParsedTable failed(String code, String message) {
            return new ParsedTable(List.of(), List.of(), List.of(error(0, "", code, message)));
        }
    }

    public static final class PreviewRow {
        public final int line;
        public final Map<String, String> raw;
        public final List<String> rawCells;
        public Long projectId;
        public String projectName = "", summaryKey = "", metricKey = "", valueText = "", revision = "";
        public Unit unit;
        public Integer sampleCount;
        public String duplicateStatus = "NONE";
        public boolean duplicateChecked;
        public final List<Long> existingRecordIds = new ArrayList<>();
        public final List<Issue> issues = new ArrayList<>();
        private BigDecimal value;
        private PreviewRow(int line, Map<String, String> raw, List<String> rawCells) {
            this.line = line; this.raw = Map.copyOf(raw); this.rawCells = List.copyOf(rawCells);
        }
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("line", line); map.put("raw", raw); map.put("rawCells", rawCells);
            map.put("projectId", projectId); map.put("projectName", projectName);
            map.put("summaryKey", summaryKey); map.put("metricKey", metricKey); map.put("valueText", valueText);
            map.put("unit", unit == null ? "" : unit.name()); map.put("sampleCount", sampleCount); map.put("revision", revision);
            map.put("duplicateStatus", duplicateStatus); map.put("existingRecordIds", List.copyOf(existingRecordIds));
            map.put("duplicateChecked", duplicateChecked);
            map.put("issues", issues.stream().map(Issue::toMap).toList()); return map;
        }
    }
    public record Preview(Profile profile, List<String> headers, List<PreviewRow> rows, List<Issue> issues) {
        public Preview { headers = List.copyOf(headers); rows = List.copyOf(rows); issues = List.copyOf(issues); }
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("mode", "PREVIEW_ONLY"); map.put("canCommit", false); map.put("synthetic", profile.synthetic());
            map.put("adapterId", profile.adapterId()); map.put("sampleCountMeaning", profile.sampleCountMeaning());
            map.put("headers", headers); map.put("rows", rows.stream().map(PreviewRow::toMap).toList());
            map.put("issues", issues.stream().map(Issue::toMap).toList());
            map.put("errorCount", issues.stream().filter(i -> "ERROR".equals(i.severity())).count());
            map.put("warningCount", issues.stream().filter(i -> "WARNING".equals(i.severity())).count());
            return map;
        }
    }

    public static Preview preview(byte[] bytes, SummaryAdapter adapter, Profile profile, Mapping mapping,
                                  PreviewContext context) {
        if (context == null || !context.mayPreview()) throw new SecurityException("无汇总导入预览权限");
        Objects.requireNonNull(adapter); Objects.requireNonNull(profile); Objects.requireNonNull(mapping);
        ParsedTable parsed = adapter.parse(bytes);
        List<Issue> issues = new ArrayList<>(parsed.issues());
        issues.add(warning(0, "", "PREVIEW_ONLY", "复核岗位、修正版和覆盖规则待确认；本次仅预览，不入库、不覆盖"));
        List<PreviewRow> rows = new ArrayList<>();
        if (issues.stream().anyMatch(i -> "ERROR".equals(i.severity()))) return new Preview(profile, parsed.headers(), rows, issues);
        Map<Field, Integer> positions = new EnumMap<>(Field.class);
        Set<String> mapped = new HashSet<>();
        for (Field field : Field.values()) {
            String column = mapping.columns().get(field);
            if (column == null || column.isBlank()) {
                if (field != Field.REVISION) issues.add(error(1, field.key, "MAPPING_REQUIRED", "请关联字段: " + field.key));
            } else if (!parsed.headers().contains(column)) {
                issues.add(error(1, field.key, "MAPPING_COLUMN_MISSING", "找不到已关联列: " + column));
            } else if (!mapped.add(column)) {
                issues.add(error(1, field.key, "MAPPING_REUSED", "同一列不能关联多个字段: " + column));
            } else positions.put(field, parsed.headers().indexOf(column));
        }
        if (issues.stream().anyMatch(i -> "ERROR".equals(i.severity()))) return new Preview(profile, parsed.headers(), rows, issues);
        Map<String, List<Project>> projects = new HashMap<>();
        Set<Long> visibleIds = new HashSet<>();
        for (Project project : context.visibleProjects()) {
            projects.computeIfAbsent(project.externalKey(), k -> new ArrayList<>()).add(project); visibleIds.add(project.id());
        }
        Map<String, MetricSpec> metrics = new HashMap<>();
        for (MetricSpec metric : profile.metrics()) metrics.put(metric.key(), metric);
        for (RawRow rawRow : parsed.rows()) {
            Map<String, String> raw = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(rawRow.cells().size(), parsed.headers().size()); i++)
                raw.put(parsed.headers().get(i), rawRow.cells().get(i));
            PreviewRow row = new PreviewRow(rawRow.line(), raw, rawRow.cells()); rows.add(row);
            if (rawRow.cells().size() != parsed.headers().size()) {
                row.issues.add(error(row.line, "", "COLUMN_COUNT", "该行列数与表头不一致")); continue;
            }
            Map<Field, String> values = new EnumMap<>(Field.class);
            for (Field field : Field.values()) {
                String value = positions.containsKey(field) ? rawRow.cells().get(positions.get(field)).strip() : "";
                values.put(field, value);
                if (field != Field.REVISION && value.isEmpty()) row.issues.add(error(row.line, field.key, "REQUIRED", "字段不能为空"));
            }
            row.summaryKey = values.get(Field.SUMMARY_KEY); row.metricKey = values.get(Field.METRIC_KEY);
            row.valueText = values.get(Field.VALUE); row.revision = values.get(Field.REVISION);
            List<Project> candidates = projects.getOrDefault(values.get(Field.PROJECT_KEY), List.of());
            if (candidates.isEmpty()) row.issues.add(error(row.line, "project_key", "PROJECT_UNAVAILABLE", "项目识别值未匹配到当前可见项目"));
            else if (candidates.size() > 1) row.issues.add(error(row.line, "project_key", "PROJECT_AMBIGUOUS", "项目识别值匹配多条记录，需要修正关联"));
            else { row.projectId = candidates.get(0).id(); row.projectName = candidates.get(0).label(); }
            MetricSpec metric = metrics.get(row.metricKey);
            if (metric == null) row.issues.add(error(row.line, "metric_key", "UNKNOWN_METRIC", "指标尚未定义量表，请先完成来源适配"));
            try { row.unit = Unit.valueOf(values.get(Field.UNIT)); }
            catch (IllegalArgumentException e) { row.issues.add(error(row.line, "unit", "UNKNOWN_UNIT", "单位须明确为 PERCENT 或 SCORE")); }
            if (metric != null && row.unit != null && metric.unit() != row.unit)
                row.issues.add(error(row.line, "unit", "UNIT_MISMATCH", "单位与该指标定义不一致，不能混用百分数和分数"));
            String number = row.valueText;
            if (number.endsWith("%")) {
                if (row.unit != Unit.PERCENT) row.issues.add(error(row.line, "value", "PERCENT_IN_SCORE", "分数不能带百分号"));
                number = number.substring(0, number.length() - 1);
            }
            if (number.length() > 64 || !number.matches("[-+]?[0-9]+(?:\\.[0-9]+)?"))
                row.issues.add(error(row.line, "value", "INVALID_NUMBER", "值必须是明确的十进制数；不猜测千分位、区间或缺失符号"));
            else {
                row.value = new BigDecimal(number);
                if (metric != null && (row.value.compareTo(metric.min()) < 0 || row.value.compareTo(metric.max()) > 0))
                    row.issues.add(error(row.line, "value", "OUT_OF_RANGE", "值超出指标范围 " + metric.min().toPlainString() + ".." + metric.max().toPlainString()));
            }
            String count = values.get(Field.SAMPLE_COUNT);
            if (!count.matches("[0-9]{1,10}")) row.issues.add(error(row.line, "sample_count", "INVALID_SAMPLE_COUNT", "样本数必须是非负整数"));
            else {
                try { row.sampleCount = Integer.valueOf(count); }
                catch (NumberFormatException e) { row.issues.add(error(row.line, "sample_count", "INVALID_SAMPLE_COUNT", "样本数超出支持范围")); }
            }
        }
        Map<Key, List<PreviewRow>> groups = new HashMap<>();
        for (PreviewRow row : rows) if (row.issues.isEmpty()) {
            row.duplicateChecked = true;
            groups.computeIfAbsent(key(profile, row), k -> new ArrayList<>()).add(row);
        }
        Map<Key, List<ExistingSummary>> existing = new HashMap<>();
        for (ExistingSummary old : context.existingSummaries()) {
            if (visibleIds.contains(old.projectId()) && profile.sourceId().equals(old.sourceId()))
                existing.computeIfAbsent(new Key(old.projectId(), old.sourceId(), old.summaryKey(), old.metricKey()), k -> new ArrayList<>()).add(old);
        }
        for (Map.Entry<Key, List<PreviewRow>> group : groups.entrySet()) {
            List<PreviewRow> matches = group.getValue();
            boolean conflict = matches.stream().anyMatch(row -> !same(row, matches.get(0)));
            for (PreviewRow row : matches) {
                if (matches.size() > 1) {
                    row.duplicateStatus = conflict ? "IN_FILE_CONFLICT" : "IN_FILE_DUPLICATE";
                    row.issues.add(warning(row.line, "summary_key", row.duplicateStatus,
                            conflict ? "文件内同一汇总指标存在不同内容，须逐条复核" : "文件内同一汇总指标重复，已保留每一行"));
                }
                List<ExistingSummary> previous = existing.getOrDefault(group.getKey(), List.of());
                for (ExistingSummary old : previous) row.existingRecordIds.add(old.recordId());
                if (!previous.isEmpty()) {
                    String status;
                    if (previous.size() > 1) status = "EXISTING_CONFLICT";
                    else {
                        ExistingSummary old = previous.get(0);
                        if (same(row, old)) status = "EXISTING_SAME";
                        else if (!row.revision.isEmpty() && !old.revision().isEmpty() && !row.revision.equals(old.revision())) status = "REVISION_CANDIDATE";
                        else status = "EXISTING_CONFLICT";
                    }
                    if ("NONE".equals(row.duplicateStatus)) row.duplicateStatus = status;
                    String message = switch (status) {
                        case "EXISTING_SAME" -> "已存在相同汇总；不会重复导入";
                        case "REVISION_CANDIDATE" -> "修订标识不同，仅列为修正版候选，不判断新旧、不覆盖";
                        default -> "已存在相同汇总标识但内容冲突，或存在多条历史记录；需要复核";
                    };
                    row.issues.add(warning(row.line, "summary_key", status, message));
                }
            }
        }
        for (PreviewRow row : rows) issues.addAll(row.issues);
        return new Preview(profile, parsed.headers(), rows, issues);
    }

    private record Key(long projectId, String sourceId, String summaryKey, String metricKey) {}
    private static Key key(Profile profile, PreviewRow row) { return new Key(row.projectId, profile.sourceId(), row.summaryKey, row.metricKey); }
    private static boolean same(PreviewRow a, PreviewRow b) {
        return a.value.compareTo(b.value) == 0 && a.unit == b.unit && a.sampleCount.equals(b.sampleCount) && a.revision.equals(b.revision);
    }
    private static boolean same(PreviewRow a, ExistingSummary b) {
        return a.value.compareTo(b.value()) == 0 && a.unit == b.unit() && a.sampleCount == b.sampleCount() && a.revision.equals(b.revision());
    }
    private static Issue error(int line, String field, String code, String message) { return new Issue(line, field, code, message, "ERROR"); }
    private static Issue warning(int line, String field, String code, String message) { return new Issue(line, field, code, message, "WARNING"); }
    private static void required(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) throw new IllegalArgumentException(label + "不能为空或包含首尾空白");
    }

    public static Profile syntheticProfile() {
        return new Profile("synthetic-csv-v1", "DEMO-SURVEY", true, "合成示例：原平台该指标有效样本数；正式定义待确认",
                List.of(new MetricSpec("satisfaction_pct", "合成满意度百分数", Unit.PERCENT, BigDecimal.ZERO, new BigDecimal("100")),
                        new MetricSpec("overall_score", "合成总体评价分数", Unit.SCORE, BigDecimal.ONE, new BigDecimal("5"))));
    }

    public static PreviewContext syntheticContext() {
        return new PreviewContext(true, List.of(new Project(9001, "DEMO-P001", "合成项目 · 服务沟通课程"), new Project(9002, "DEMO-P002", "合成项目 · 项目协作课程")),
                List.of(new ExistingSummary(7001, 9001, "DEMO-SURVEY", "DEMO-S001", "satisfaction_pct", new BigDecimal("92.5"), Unit.PERCENT, 40, "v1"),
                        new ExistingSummary(7002, 9002, "DEMO-SURVEY", "DEMO-S002", "satisfaction_pct", new BigDecimal("89"), Unit.PERCENT, 50, "v1"),
                        new ExistingSummary(7003, 9001, "DEMO-SURVEY", "DEMO-S003", "satisfaction_pct", new BigDecimal("90"), Unit.PERCENT, 80, "v1")));
    }
}
