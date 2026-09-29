package com.training;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.stream.*;

/** M07 explicit draft statistics from the supplied internal questionnaire export shape. No persistence. */
public final class SurveySummaryImportsResponses {
    private SurveySummaryImportsResponses() {}
    public static final String ADAPTER_ID = "internal-survey-xlsx-v1";
    public static final String SHEET_NAME = "表格题1-培训满意度调研";
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_EXPANDED_BYTES = 32 * 1024 * 1024;
    public static final int MAX_ROWS = 10000;
    public static final int MAX_COLUMNS = 64;
    public static final int MAX_SCORE_SCALE = 12;
    private static final int MAX_CELL_CHARS = 4096;
    private static final int MAX_VISIBLE_ISSUES = 2000;
    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    public static final List<String> QUESTION_LABELS = List.of(
        "讲师着装专业得体（满分10分）", "讲师仪容仪表规范（满分10分）",
        "课程内容匹配需求，对技能提升有帮助（满分10分）", "课程时长适中，授课进度合理（满分10分）",
        "课程目标清晰、逻辑通顺，利于系统学习（满分10分）", "教学方式丰富多样，易于融入学习（满分10分）",
        "课程教材匹配度高，有助于掌握学习内容（满分10分）", "讲师积极答疑，有效引导课堂参与（满分10分）",
        "课堂互动氛围良好，讲师能够有效引导学员参与讨论（满分10分）", "本次课程总体满意度（满分10分）");

    /** All rules remain draft. Hosts may choose a stricter lower bound, but may not exceed the source's 10-point ceiling. */
    public record DraftRules(BigDecimal minScore, BigDecimal maxScore, int displayScale, boolean excludeZero) {
        public DraftRules(BigDecimal minScore, BigDecimal maxScore, int displayScale) {
            this(minScore, maxScore, displayScale, false);
        }
        public DraftRules {
            Objects.requireNonNull(minScore); Objects.requireNonNull(maxScore);
            if (!bounded(minScore) || !bounded(maxScore) || minScore.signum() < 0 || minScore.compareTo(maxScore) >= 0
                    || maxScore.compareTo(BigDecimal.TEN) > 0 || displayScale < 0 || displayScale > 6)
                throw new IllegalArgumentException("草案评分范围或显示精度无效");
        }
        public static DraftRules proposed() { return new DraftRules(BigDecimal.ZERO, BigDecimal.TEN, 2); }
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>(Map.of("id", "draft-per-question-v1", "label", "统计口径草案", "minScore", minScore.toPlainString(),
                    "maxScore", maxScore.toPlainString(), "confirmed", false,
                    "description", "每题独立统计有效数值；空白不记零；状态、隐藏行暂不筛选；记录不去重；均值显示"
                            + displayScale + "位并四舍五入，口径待确认"));
            if (excludeZero) out.put("excludeZero", true);
            return out;
        }
    }
    public record PreviousImport(long id, long projectId, String fileFingerprint, String scoreFingerprint) {
        public PreviousImport {
            if (id <= 0 || projectId <= 0) throw new IllegalArgumentException("历史导入 id 无效");
            Objects.requireNonNull(fileFingerprint); Objects.requireNonNull(scoreFingerprint);
        }
    }
    /** Created only by the authenticated host, never from the upload request body. */
    public record PreviewContext(boolean mayPreview, List<SurveySummaryImports.Project> visibleProjects,
            List<PreviousImport> previousImports, boolean historyAvailable, boolean synthetic) {
        public PreviewContext { visibleProjects = List.copyOf(visibleProjects); previousImports = List.copyOf(previousImports); }
    }
    private record Problem(int row, String column, String code, String message, String severity) {
        Map<String, Object> toMap() { return Map.of("row", row, "column", column, "code", code, "message", message, "severity", severity); }
    }
    private static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;
        Invalid(String code) { super(code); this.code = code; }
    }
    private static Invalid invalid(String code) { return new Invalid(code); }
    private static String message(String code) {
        return switch (code) {
            case "FILE_EMPTY" -> "文件为空";
            case "FILE_TOO_LARGE" -> "文件不能超过5 MiB";
            case "XLSX_LIMIT" -> "文件超出工作表、行列、文本或解压大小限制";
            case "SHEET_MISSING" -> "未找到约定的评分工作表，请核对原平台导出类型";
            case "HEADER_MISMATCH" -> "评分题目与样例结构不一致，需复核来源格式";
            case "DUPLICATE_HEADER" -> "评分表存在重复题头，无法明确对应题目";
            case "NO_RESPONSE_ROWS" -> "评分表没有候选答卷记录";
            case "XLSX_STRUCTURE" -> "Excel结构存在冲突或不支持的内容，请重新导出";
            case "INVALID_SCORE" -> "该评分不是明确的十进制数值，未纳入平均分";
            case "OUT_OF_RANGE" -> "该评分超出当前草案范围，未纳入平均分";
            case "FORMULA_SCORE" -> "该评分格包含公式，未采用其缓存结果";
            case "PERCENT_SCORE" -> "该评分格为百分数格式，不能直接作为10分制评分";
            case "DATE_SCORE" -> "该评分格为日期或时间，未作为分数统计";
            case "SCORE_PRECISION" -> "该评分的数值精度或指数超出支持范围";
            case "CELL_ERROR" -> "该评分格包含Excel错误或不支持的数据类型";
            case "NO_VALID_SCORES" -> "该题没有可用于当前草案的有效评分，均值留空";
            default -> "无法读取该Excel文件，请使用原平台导出的.xlsx文件";
        };
    }
    private static final class Question {
        final int number, col;
        int valid, blank, invalid;
        BigDecimal sum = BigDecimal.ZERO;
        Question(int number, int col) { this.number = number; this.col = col; }
        Map<String, Object> toMap(DraftRules rules) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", "q" + (number + 1)); out.put("label", QUESTION_LABELS.get(number)); out.put("column", column(col));
            out.put("validCount", valid); out.put("blankCount", blank); out.put("invalidCount", invalid);
            out.put("sumText", sum.toPlainString());
            out.put("averageText", valid == 0 ? null : sum.divide(BigDecimal.valueOf(valid), rules.displayScale(), RoundingMode.HALF_UP).toPlainString());
            return out;
        }
    }
    private record Cell(String text, String type, int style, boolean formula) {}
    private static final class Stats {
        int rows;
        final List<Question> questions = new ArrayList<>();
        final List<Problem> issues = new ArrayList<>();
        int issueCount;
        final MessageDigest scoreDigest = digest();
        void add(Problem problem) {
            issueCount++;
            if (issues.size() < MAX_VISIBLE_ISSUES) issues.add(problem);
        }
    }

    public static Map<String, Object> preview(byte[] bytes, long projectId, DraftRules rules, PreviewContext context) {
        return preview(bytes, projectId, rules, context, null);
    }

    /**
     * Runs a host cancellation/deadline check synchronously on the calling thread.
     * The callback may throw CancellationException (including a host subclass), which propagates
     * unchanged. A null callback still checks thread interruption and never clears the interrupt flag.
     * No callback or cancellation state is retained after this invocation.
     */
    public static Map<String, Object> preview(byte[] bytes, long projectId, DraftRules rules,
            PreviewContext context, Runnable checkpoint) {
        checkCancelled(checkpoint);
        if (context == null || !context.mayPreview()) throw new SecurityException("无评分汇总预览权限");
        List<SurveySummaryImports.Project> matches = new ArrayList<>();
        for (SurveySummaryImports.Project project : context.visibleProjects()) {
            checkCancelled(checkpoint);
            if (project.id() == projectId) matches.add(project);
        }
        if (matches.size() != 1) throw new SecurityException("未匹配到唯一的当前可见项目");
        Objects.requireNonNull(rules);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "RESPONSE_SUMMARY_PREVIEW"); out.put("adapterId", ADAPTER_ID); out.put("synthetic", context.synthetic());
        out.put("canCommit", false); out.put("policyConfirmed", false); out.put("rulesDraft", rules.toMap());
        out.put("projectId", projectId); out.put("projectName", matches.get(0).label()); out.put("overallQuestionKey", "q10");
        out.put("warnings", List.of("当前为统计草案，不能作为已确认的正式结果。",
                "答卷记录按非空行计数，尚未按人员去重；每题有效评分数各自作为分母。",
                "包含十题全空但有记录标记的行；忽略纯空白或仅格式化的行。",
                "状态和隐藏行暂不筛选；不自动删除重复记录。",
                "总体满意度仅对应问卷本身的总体满意度题，不汇总十题、不转百分比。"));
        try {
            if (bytes == null || bytes.length == 0) throw invalid("FILE_EMPTY");
            if (bytes.length > MAX_BYTES) throw invalid("FILE_TOO_LARGE");
            Map<String, byte[]> parts = unzip(bytes, checkpoint);
            checkCancelled(checkpoint);
            Map<String, Relation> relations = relations(requiredPart(parts, "xl/_rels/workbook.xml.rels"), checkpoint);
            checkCancelled(checkpoint);
            String sheetPath = sheetPath(requiredPart(parts, "xl/workbook.xml"), relations, checkpoint);
            checkCancelled(checkpoint);
            List<String> shared = sharedStrings(optionalPart(parts, relationPath(relations, "/sharedStrings")), checkpoint);
            checkCancelled(checkpoint);
            List<String> styles = styles(optionalPart(parts, relationPath(relations, "/styles")), checkpoint);
            checkCancelled(checkpoint);
            Stats stats = readScores(requiredPart(parts, sheetPath), shared, styles, rules, checkpoint);
            checkCancelled(checkpoint);
            if (stats.rows == 0) throw invalid("NO_RESPONSE_ROWS");
            for (Question question : stats.questions) if (question.valid == 0)
                stats.add(new Problem(0, column(question.col), "NO_VALID_SCORES", message("NO_VALID_SCORES"), "WARNING"));
            String fileHash = fingerprint(bytes, checkpoint);
            checkCancelled(checkpoint);
            // Invalid cells may contain arbitrary personal text; never hash that text or infer equality from error categories.
            String scoreHash = stats.questions.stream().anyMatch(q -> q.invalid > 0) ? "" : hex(stats.scoreDigest.digest());
            out.put("state", "PREVIEW"); out.put("responseRowCount", stats.rows);
            out.put("questions", stats.questions.stream().map(q -> q.toMap(rules)).toList());
            out.put("issues", stats.issues.stream().map(Problem::toMap).toList());
            out.put("issueCount", stats.issueCount); out.put("issuesTruncated", stats.issueCount > stats.issues.size());
            out.put("fileFingerprint", fileHash); out.put("scoreFingerprint", scoreHash);
            out.put("duplicateCheck", duplicate(projectId, fileHash, scoreHash, context, checkpoint));
        } catch (Invalid e) {
            checkCancelled(checkpoint);
            out.put("state", "ERROR"); out.put("responseRowCount", 0); out.put("questions", List.of());
            out.put("issues", List.of(new Problem(0, "", e.code, message(e.code), "ERROR").toMap()));
            out.put("issueCount", 1); out.put("issuesTruncated", false);
            out.put("duplicateCheck", Map.of("status", "NOT_CHECKED", "recordIds", List.of(), "message", "文件需修正后检查重复"));
        }
        checkCancelled(checkpoint);
        return out;
    }
    private static Map<String, Object> duplicate(long projectId, String file, String scores, PreviewContext context, Runnable checkpoint) {
        checkCancelled(checkpoint);
        if (!context.historyAvailable()) return Map.of("status", "NOT_CHECKED", "recordIds", List.of(), "message", "历史记录尚未接入，未检查已导入重复");
        List<Long> exact = new ArrayList<>(), same = new ArrayList<>();
        for (PreviousImport old : context.previousImports()) {
            checkCancelled(checkpoint);
            if (old.projectId() == projectId) {
                if (old.fileFingerprint().equals(file)) exact.add(old.id());
                else if (!scores.isEmpty() && old.scoreFingerprint().equals(scores)) same.add(old.id());
            }
        }
        if (!exact.isEmpty()) return Map.of("status", "EXACT_FILE", "recordIds", exact, "message", "同项目已有内容完全相同的文件；仅提示，不写入");
        if (!same.isEmpty()) return Map.of("status", "SAME_SCORES_CANDIDATE", "recordIds", same, "message", "评分部分相同，可能重复；不能据此认定同一批答卷");
        return Map.of("status", "NONE", "recordIds", List.of(), "message", "在当前可见历史记录中未发现相同文件或评分部分");
    }

    private static Map<String, byte[]> unzip(byte[] bytes, Runnable checkpoint) {
        checkCancelled(checkpoint);
        Map<String, byte[]> parts = new HashMap<>();
        int total = 0, entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while (true) {
                checkCancelled(checkpoint);
                entry = zip.getNextEntry();
                checkCancelled(checkpoint);
                if (entry == null) break;
                if (++entries > 256) throw invalid("XLSX_LIMIT");
                String name = entry.getName();
                if (name.length() > 256 || name.startsWith("/") || name.contains("\\") || name.contains("..") || name.indexOf(':') >= 0
                        || parts.containsKey(name) || name.toLowerCase(Locale.ROOT).endsWith(".bin") || name.contains("externalLinks/"))
                    throw invalid("XLSX_STRUCTURE");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                int n;
                while (true) {
                    checkCancelled(checkpoint);
                    n = zip.read(buffer);
                    checkCancelled(checkpoint);
                    if (n == -1) break;
                    total += n;
                    if (total > MAX_EXPANDED_BYTES) throw invalid("XLSX_LIMIT");
                    data.write(buffer, 0, n);
                }
                parts.put(name, data.toByteArray());
                zip.closeEntry();
            }
        } catch (IOException | IllegalArgumentException e) { checkCancelled(checkpoint); throw invalid("INVALID_XLSX"); }
        if (parts.isEmpty()) throw invalid("INVALID_XLSX");
        return parts;
    }
    private static byte[] requiredPart(Map<String, byte[]> parts, String path) {
        byte[] data = parts.get(path);
        if (data == null) throw invalid("XLSX_STRUCTURE");
        return data;
    }
    private static byte[] optionalPart(Map<String, byte[]> parts, String path) {
        return path == null ? null : requiredPart(parts, path);
    }
    private record Relation(String path, String type) {}
    private static Map<String, Relation> relations(byte[] xml, Runnable checkpoint) {
        Map<String, Relation> result = new HashMap<>();
        try (Cursor c = new Cursor(xml, "Relationships", "http://schemas.openxmlformats.org/package/2006/relationships", checkpoint)) {
            while (c.next()) if (c.start("Relationship")) {
                String id = c.attr("Id"), target = c.attr("Target"), type = c.attr("Type");
                if (id.isEmpty() || target.isEmpty() || type.isEmpty() || "External".equalsIgnoreCase(c.attr("TargetMode"))
                        || target.startsWith("/") || target.contains("..") || target.contains("\\") || target.contains(":")
                        || result.putIfAbsent(id, new Relation("xl/" + target, type)) != null) throw invalid("XLSX_STRUCTURE");
            }
        }
        return result;
    }
    private static String relationPath(Map<String, Relation> relations, String suffix) {
        List<Relation> values = relations.values().stream().filter(r -> r.type().equals(REL_NS + suffix)).toList();
        if (values.size() > 1) throw invalid("XLSX_STRUCTURE");
        return values.isEmpty() ? null : values.get(0).path();
    }
    private static String sheetPath(byte[] xml, Map<String, Relation> relations, Runnable checkpoint) {
        String path = null;
        Set<String> names = new HashSet<>();
        try (Cursor c = new Cursor(xml, "workbook", checkpoint)) {
            while (c.next()) if (c.start("sheet")) {
                String name = c.attr("name");
                if (!names.add(name)) throw invalid("XLSX_STRUCTURE");
                if (SHEET_NAME.equals(name)) {
                    Relation relation = relations.get(c.reader.getAttributeValue(REL_NS, "id"));
                    if (relation == null || !relation.type().equals(REL_NS + "/worksheet")) throw invalid("XLSX_STRUCTURE");
                    path = relation.path();
                }
            }
        }
        if (path == null) throw invalid("SHEET_MISSING");
        return path;
    }
    private static List<String> sharedStrings(byte[] xml, Runnable checkpoint) {
        checkCancelled(checkpoint);
        if (xml == null) return List.of();
        List<String> strings = new ArrayList<>();
        StringBuilder item = null;
        int phonetic = 0, total = 0;
        boolean inText = false;
        try (Cursor c = new Cursor(xml, "sst", checkpoint)) {
            while (c.next()) {
                if (c.start("si")) { if (item != null) throw invalid("XLSX_STRUCTURE"); item = new StringBuilder(); }
                else if (c.start("rPh")) phonetic++;
                else if (c.end("rPh")) phonetic--;
                else if (c.start("t")) inText = true;
                else if (c.end("t")) inText = false;
                else if (c.text() && inText && item != null && phonetic == 0) {
                    item.append(c.reader.getText());
                    if (item.length() > MAX_CELL_CHARS) throw invalid("XLSX_LIMIT");
                } else if (c.end("si")) {
                    if (item == null) throw invalid("XLSX_STRUCTURE");
                    total += item.length();
                    if (strings.size() >= 50000 || total > 16 * 1024 * 1024) throw invalid("XLSX_LIMIT");
                    strings.add(item.toString()); item = null;
                }
            }
        }
        return strings;
    }
    private static List<String> styles(byte[] xml, Runnable checkpoint) {
        checkCancelled(checkpoint);
        if (xml == null) return List.of("NUMBER");
        Map<Integer, String> custom = new HashMap<>();
        List<String> result = new ArrayList<>();
        boolean inXfs = false;
        try (Cursor c = new Cursor(xml, "styleSheet", checkpoint)) {
            while (c.next()) {
                if (c.start("numFmt")) {
                    int id = integer(c.attr("numFmtId"), 0, 65535);
                    if (custom.putIfAbsent(id, c.attr("formatCode")) != null) throw invalid("XLSX_STRUCTURE");
                } else if (c.start("cellXfs")) inXfs = true;
                else if (c.end("cellXfs")) inXfs = false;
                else if (inXfs && c.start("xf")) {
                    int id = integer(c.attr("numFmtId").isEmpty() ? "0" : c.attr("numFmtId"), 0, 65535);
                    String format = custom.getOrDefault(id, "").replaceAll("\"[^\"]*\"", "").replaceAll("\\\\.", "")
                            .replaceAll("\\[(?![hmsHMS]+\\])[^\\]]*\\]", "");
                    String kind = id == 9 || id == 10 || format.contains("%") ? "PERCENT" :
                            (id >= 14 && id <= 22) || (id >= 27 && id <= 36) || (id >= 45 && id <= 47)
                                    || (id >= 50 && id <= 58) || format.toLowerCase(Locale.ROOT).matches(".*[ymdhs].*") ? "DATE" : "NUMBER";
                    result.add(kind);
                    if (result.size() > 4096) throw invalid("XLSX_LIMIT");
                }
            }
        }
        if (result.isEmpty()) throw invalid("XLSX_STRUCTURE");
        return result;
    }

    private static Stats readScores(byte[] xml, List<String> shared, List<String> styles, DraftRules rules, Runnable checkpoint) {
        checkCancelled(checkpoint);
        Stats stats = new Stats();
        Map<Integer, Cell> cells = new HashMap<>();
        Set<Integer> seenColumns = new HashSet<>();
        int row = 0, lastRow = 0, col = -1, style = 0;
        String type = "";
        boolean inRow = false, inCell = false, formula = false, hasMarker = false, inValue = false, inText = false;
        boolean sheetDataSeen = false, valueSeen = false, inlineSeen = false, inInline = false;
        int phonetic = 0;
        StringBuilder content = new StringBuilder();
        try (Cursor c = new Cursor(xml, "worksheet", checkpoint)) {
            while (c.next()) {
                if (c.start("mergeCell")) throw invalid("XLSX_STRUCTURE");
                if (c.start("sheetData")) {
                    if (sheetDataSeen || !"worksheet".equals(c.parent)) throw invalid("XLSX_STRUCTURE");
                    sheetDataSeen = true;
                }
                if (c.start("row")) {
                    checkCancelled(checkpoint);
                    if (inRow || !sheetDataSeen || !"sheetData".equals(c.parent)) throw invalid("XLSX_STRUCTURE");
                    row = integer(c.attr("r"), 1, MAX_ROWS + 1);
                    if (row <= lastRow) throw invalid("XLSX_STRUCTURE");
                    if (lastRow == 0 && row != 1) throw invalid("HEADER_MISMATCH");
                    lastRow = row; inRow = true; hasMarker = false; cells.clear(); seenColumns.clear();
                } else if (c.start("c")) {
                    if (!inRow || inCell || !"row".equals(c.parent)) throw invalid("XLSX_STRUCTURE");
                    String ref = c.attr("r");
                    if (!ref.matches("[A-Z]{1,3}[1-9][0-9]{0,6}")) throw invalid("XLSX_STRUCTURE");
                    int at = 0; col = 0;
                    while (at < ref.length() && Character.isLetter(ref.charAt(at))) col = col * 26 + ref.charAt(at++) - 'A' + 1;
                    col--;
                    if (col >= MAX_COLUMNS) throw invalid("XLSX_LIMIT");
                    if (integer(ref.substring(at), 1, MAX_ROWS + 1) != row || !seenColumns.add(col)) throw invalid("XLSX_STRUCTURE");
                    type = c.attr("t"); style = integer(c.attr("s").isEmpty() ? "0" : c.attr("s"), 0, styles.size() - 1);
                    formula = false; inCell = true; valueSeen = false; inlineSeen = false; inInline = false; content.setLength(0);
                } else if (c.start("f")) {
                    if (!inCell || !"c".equals(c.parent) || formula) throw invalid("XLSX_STRUCTURE");
                    formula = true;
                } else if (c.start("is")) {
                    if (!inCell || !"c".equals(c.parent) || inlineSeen || valueSeen || !"inlineStr".equals(type)) throw invalid("XLSX_STRUCTURE");
                    inlineSeen = true; inInline = true;
                } else if (c.end("is")) {
                    inInline = false;
                } else if (c.start("v")) {
                    if (!inCell || !"c".equals(c.parent) || valueSeen || inlineSeen || "inlineStr".equals(type)) throw invalid("XLSX_STRUCTURE");
                    inValue = true; valueSeen = true;
                }
                else if (c.end("v")) inValue = false;
                else if (c.start("t")) {
                    if (!inCell || !inInline || !("is".equals(c.parent) || "r".equals(c.parent) || "rPh".equals(c.parent))) throw invalid("XLSX_STRUCTURE");
                    inText = true;
                }
                else if (c.end("t")) inText = false;
                else if (c.start("rPh")) phonetic++;
                else if (c.end("rPh")) phonetic--;
                else if (inCell && c.text() && (inValue || (inText && phonetic == 0))) {
                    content.append(c.reader.getText());
                    if (content.length() > MAX_CELL_CHARS) throw invalid("XLSX_LIMIT");
                } else if (c.end("c")) {
                    if (!inCell) throw invalid("XLSX_STRUCTURE");
                    String value = content.toString();
                    if ("s".equals(type)) value = shared.get(integer(value, 0, shared.size() - 1));
                    hasMarker |= !value.isBlank() || formula;
                    final int cellCol = col;
                    if (row == 1 || stats.questions.stream().anyMatch(q -> q.col == cellCol)) cells.put(col, new Cell(value, type, style, formula));
                    inCell = false;
                } else if (c.end("row")) {
                    checkCancelled(checkpoint);
                    if (!inRow || inCell) throw invalid("XLSX_STRUCTURE");
                    if (row == 1) headers(cells, stats, checkpoint);
                    else if (hasMarker) {
                        if (++stats.rows > MAX_ROWS) throw invalid("XLSX_LIMIT");
                        scoreRow(row, cells, styles, rules, stats, checkpoint);
                    }
                    inRow = false;
                }
            }
        }
        if (!sheetDataSeen || stats.questions.size() != 10) throw invalid("HEADER_MISMATCH");
        return stats;
    }
    private static void headers(Map<Integer, Cell> cells, Stats stats, Runnable checkpoint) {
        checkCancelled(checkpoint);
        Map<String, Integer> titles = new HashMap<>();
        for (Map.Entry<Integer, Cell> entry : cells.entrySet()) {
            checkCancelled(checkpoint);
            Cell cell = entry.getValue();
            if (cell.formula()) throw invalid("HEADER_MISMATCH");
            String value = cell.text().strip();
            if (!value.isEmpty() && titles.putIfAbsent(value, entry.getKey()) != null) throw invalid("DUPLICATE_HEADER");
        }
        for (int q = 0; q < QUESTION_LABELS.size(); q++) {
            Integer column = titles.get(QUESTION_LABELS.get(q));
            if (column == null) throw invalid("HEADER_MISMATCH");
            stats.questions.add(new Question(q, column));
        }
    }
    private static void scoreRow(int row, Map<Integer, Cell> cells, List<String> styles, DraftRules rules, Stats stats, Runnable checkpoint) {
        checkCancelled(checkpoint);
        for (Question question : stats.questions) {
            checkCancelled(checkpoint);
            Cell cell = cells.get(question.col);
            String value = cell == null ? "" : cell.text().strip();
            String problem = null;
            if (cell != null && cell.formula()) problem = "FORMULA_SCORE";
            else if (cell == null || value.isEmpty()) { question.blank++; feed(stats.scoreDigest, "B"); continue; }
            else if ("e".equals(cell.type()) || "b".equals(cell.type())) problem = "CELL_ERROR";
            else if ("d".equals(cell.type()) || "DATE".equals(styles.get(cell.style()))) problem = "DATE_SCORE";
            else if ("PERCENT".equals(styles.get(cell.style())) || value.contains("%") || value.contains("％")) problem = "PERCENT_SCORE";
            else if (!Set.of("", "n", "s", "str", "inlineStr").contains(cell.type())) problem = "CELL_ERROR";
            else if (value.length() > 64) problem = "SCORE_PRECISION";
            else if (!value.matches("[+-]?[0-9]+(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]{1,3})?")) problem = "INVALID_SCORE";
            BigDecimal number = null;
            if (problem == null) {
                try { number = new BigDecimal(value); }
                catch (NumberFormatException e) { problem = "INVALID_SCORE"; }
                if (number != null && !bounded(number)) problem = "SCORE_PRECISION";
                if (problem == null && (number.compareTo(rules.minScore()) < 0 || number.compareTo(rules.maxScore()) > 0)) problem = "OUT_OF_RANGE";
                if (problem == null && rules.excludeZero() && number.signum() == 0) problem = "OUT_OF_RANGE";
            }
            if (problem != null) {
                question.invalid++;
                stats.add(new Problem(row, column(question.col), problem, message(problem), "ERROR"));
                // Do not include cell text or identities in errors or returned fingerprints.
                feed(stats.scoreDigest, "I:" + problem);
            } else {
                question.valid++; question.sum = question.sum.add(number);
                feed(stats.scoreDigest, "N:" + number.stripTrailingZeros().toPlainString());
            }
        }
        feed(stats.scoreDigest, "ROW");
    }
    private static boolean bounded(BigDecimal value) { return value.precision() <= 32 && value.scale() >= -8 && value.scale() <= MAX_SCORE_SCALE; }
    private static int integer(String value, int min, int max) {
        if (value == null || !value.matches("[0-9]{1,9}")) throw invalid("XLSX_STRUCTURE");
        int number;
        try { number = Integer.parseInt(value); } catch (NumberFormatException e) { throw invalid("XLSX_STRUCTURE"); }
        if (number < min) throw invalid("XLSX_STRUCTURE");
        if (number > max) throw invalid("XLSX_LIMIT");
        return number;
    }
    private static String column(int index) {
        StringBuilder out = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) out.insert(0, (char) ('A' + (n - 1) % 26));
        return out.toString();
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static void checkCancelled(Runnable checkpoint) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("评分汇总预览已取消");
        if (checkpoint != null) checkpoint.run();
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("评分汇总预览已取消");
    }
    private static String fingerprint(byte[] bytes, Runnable checkpoint) {
        MessageDigest digest = digest();
        for (int offset = 0; offset < bytes.length; offset += 8192) {
            checkCancelled(checkpoint);
            digest.update(bytes, offset, Math.min(8192, bytes.length - offset));
        }
        checkCancelled(checkpoint);
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static void feed(MessageDigest digest, String text) { digest.update(text.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); }

    /** Harden each local parser; never change process-wide XML settings. */
    private static final class Cursor implements AutoCloseable {
        final XMLStreamReader reader;
        final String root, namespace;
        final Runnable checkpoint;
        final Deque<String> elements = new ArrayDeque<>();
        String parent = "";
        int depth;
        Cursor(byte[] xml, String root, Runnable checkpoint) { this(xml, root, MAIN_NS, checkpoint); }
        Cursor(byte[] xml, String root, String namespace, Runnable checkpoint) {
            this.root = root; this.namespace = namespace; this.checkpoint = checkpoint;
            checkCancelled(checkpoint);
            try {
                XMLInputFactory factory = XMLInputFactory.newFactory();
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
                factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setXMLResolver((a, b, c, d) -> { throw new XMLStreamException("External reference blocked"); });
                reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            } catch (XMLStreamException | IllegalArgumentException e) { checkCancelled(checkpoint); throw invalid("INVALID_XLSX"); }
        }
        boolean next() {
            checkCancelled(checkpoint);
            try {
                boolean hasNext = reader.hasNext();
                checkCancelled(checkpoint);
                if (!hasNext) return false;
                int event = reader.next();
                checkCancelled(checkpoint);
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw invalid("XLSX_STRUCTURE");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (!namespace.equals(reader.getNamespaceURI()) || (depth == 0 && !root.equals(reader.getLocalName()))) throw invalid("XLSX_STRUCTURE");
                    parent = elements.isEmpty() ? "" : elements.peek();
                    if (Set.of("v", "t", "f").contains(parent)) throw invalid("XLSX_STRUCTURE");
                    elements.push(reader.getLocalName());
                    if (++depth > 32 || reader.getAttributeCount() > 32) throw invalid("XLSX_LIMIT");
                    for (int i = 0; i < reader.getAttributeCount(); i++) if (reader.getAttributeValue(i).length() > MAX_CELL_CHARS) throw invalid("XLSX_LIMIT");
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--; elements.pop(); parent = elements.isEmpty() ? "" : elements.peek();
                }
                return true;
            } catch (XMLStreamException e) { checkCancelled(checkpoint); throw invalid("INVALID_XLSX"); }
        }
        boolean start(String name) { return reader.getEventType() == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals(name); }
        boolean end(String name) { return reader.getEventType() == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals(name); }
        boolean text() { return reader.getEventType() == XMLStreamConstants.CHARACTERS || reader.getEventType() == XMLStreamConstants.CDATA; }
        String attr(String name) { String value = reader.getAttributeValue(null, name); return value == null ? "" : value; }
        @Override public void close() { try { reader.close(); } catch (XMLStreamException ignored) {} }
    }
}
