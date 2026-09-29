package com.training;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static com.training.ManagementReports.*;

/** M06 read-only XLSX export. No repricing, formulas, macros, external links or internal IDs. */
public final class ManagementReportsWorkbook {
    private ManagementReportsWorkbook() {}
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    private enum Kind { TEXT, COUNT, DECIMAL }
    private record Cell(String value, Kind kind) {}
    private record Row(List<Cell> cells, int style) {}

    /** Host must also recheck current authorization immediately before returning a download. */
    public static byte[] export(Report report) {
        // Use the authorized manifest rather than inferring the scope from nonempty result rows.
        Map<String, Object> manifest = ManagementReports.exportManifest(report, ExportKind.DETAIL);
        List<Sheet> sheets = List.of(overview(report, manifest), teachers(report), organizations(report), details(report));
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", contentTypes());
            entry(zip, "_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            StringBuilder book = new StringBuilder(XML).append("<workbook xmlns=\"").append(NS).append("\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView/></bookViews><sheets>");
            StringBuilder rels = new StringBuilder(XML).append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < sheets.size(); i++) {
                int number = i + 1;
                // Sheet names are fixed constants; no external input is used as a tag or attribute.
                book.append("<sheet name=\"").append(sheets.get(i).name).append("\" sheetId=\"").append(number).append("\" r:id=\"rId").append(number).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(number).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(number).append(".xml\"/>");
            }
            book.append("</sheets></workbook>");
            rels.append("<Relationship Id=\"rId5\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            entry(zip, "xl/workbook.xml", book.toString());
            entry(zip, "xl/_rels/workbook.xml.rels", rels.toString());
            entry(zip, "xl/styles.xml", styles());
            for (int i = 0; i < sheets.size(); i++) entry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheets.get(i).xml());
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException ex) { throw new UncheckedIOException("月报工作簿生成失败", ex); }
    }

    private static Sheet overview(Report r, Map<String, Object> manifest) {
        Sheet s = start("月度概览", r, new int[] { 28, 48, 84 }, "项目", "数值", "口径说明");
        boolean month = isMonth(r);
        s.metric("报表类型", text(month ? "月报" : "区间统计"), "自定义日期范围不冒称自然月");
        s.metric("规则版本", text(r.rules().version()), "使用当前报表已确认的统计规则");
        s.metric("开始日期", text(r.filter().start().toString()), "含当天");
        s.metric("结束日期", text(r.filter().end().toString()), "含当天");
        s.metric("日期口径", text(dateLabel(r.rules().dateBasis())), r.rules().dateBasis().name());
        s.metric("所选课时口径", text(hourLabel(r.rules().hourBasis())), r.rules().hourBasis().name());
        s.metric("排名指标", text(r.rules().rankMetric() == RankMetric.HOURS ? "所选类别课时" : "已保存课酬"), "按指标降序；同值按讲师编码稳定展示");
        s.metric("并列规则", text(tieLabel(r.rules().tieRule())), r.rules().tieRule().name());
        s.metric("计入状态", text(String.join("、", r.rules().includedStatuses().stream().sorted().map(ManagementReportsWorkbook::statusLabel).toList())), "调整记录保留正负号；未列出的状态不计入");
        s.metric("机构筛选", text(r.filter().orgCodes().isEmpty() ? "当前授权范围" : "所选机构"), "下列有效机构编码来自经权限校验的导出清单");
        Object rawOrgs = manifest.get("effectiveOrgCodes");
        if (!(rawOrgs instanceof List<?> orgs)) throw new IllegalArgumentException("导出清单缺少有效机构范围");
        s.metric("有效机构数量", count(orgs.size()), "包括当前区间内没有入选记录的授权机构");
        if (orgs.isEmpty()) s.metric("有效机构编码", text("（空）"), "无有效机构范围");
        else for (Object code : orgs) s.metric("有效机构编码", text((String) code), "");
        s.metric("币种", text(r.rules().currency()), "单一币种；沿用已保存课酬，不重算费率或换汇");
        s.metric("课时标准", text("45分钟/课时"), "各类课时独立展示，沿用上游课时值，不把不同类别相加");
        for (HourBasis basis : HourBasis.values()) {
            HourTotal value = r.hourTotals().get(basis);
            Cell hours = value.hours() == null ? text("待补齐（缺少 " + value.missingRecords() + " 条）") : decimal(value.hours());
            s.metric(hourLabel(basis), hours, "单位：课时；缺失记录数：" + value.missingRecords());
        }
        s.metric("记录数", count(r.total().records()), "入选明细条数，包含符合状态口径的调整记录");
        s.metric("唯一课程数", count(r.total().courses()), "全体入选记录按课程去重；讲师、机构分组的课程数不可相加");
        s.metric("所选类别课时合计", decimal(r.total().hours()), hourLabel(r.rules().hourBasis()) + "；单位：课时");
        s.metric("已保存课酬合计", decimal(r.total().fee()), r.rules().currency() + "；沿用各明细原课酬版本");
        s.metric("讲师数", count(r.teachers().size()), "入选记录中的不同讲师编码数量");
        s.metric("机构数", count(r.organizations().size()), "入选记录中的不同机构编码数量");
        Reconciliation q = r.reconciliation();
        s.metric("对账结果", text(q.matches() ? "一致" : "存在差额"), "差额 = 对应表的合计 - 整体总计；零表示一致");
        s.metric("明细课时差额", decimal(q.detailHoursDifference()), "明细课时合计 - 整体课时总计");
        s.metric("明细课酬差额", decimal(q.detailFeeDifference()), "明细课酬合计 - 整体课酬总计");
        s.metric("讲师课时差额", decimal(q.teacherHoursDifference()), "讲师分组课时合计 - 整体课时总计");
        s.metric("讲师课酬差额", decimal(q.teacherFeeDifference()), "讲师分组课酬合计 - 整体课酬总计");
        s.metric("机构课时差额", decimal(q.organizationHoursDifference()), "机构分组课时合计 - 整体课时总计");
        s.metric("机构课酬差额", decimal(q.organizationFeeDifference()), "机构分组课酬合计 - 整体课酬总计");
        s.metric("讲师记录数差额", count(q.teacherRecordDifference()), "讲师分组记录数合计 - 整体记录数");
        s.metric("机构记录数差额", count(q.organizationRecordDifference()), "机构分组记录数合计 - 整体记录数");
        s.metric("数值保存方式", text("普通数值可计算；编码与超15位有效数字存为文本"), "不经浮点数转换，不包含公式；所有值来自当前报表快照");
        s.metric("明细隐私范围", text("仅导出业务编码与统计字段"), "不含内部数字ID、姓名或自由备注");
        return s;
    }

    private static Sheet teachers(Report r) {
        Sheet s = start("讲师排名", r, new int[] { 12, 25, 14, 18, 23, 28, 12 }, "名次", "讲师编码", "记录数", "唯一课程数", hourLabel(r.rules().hourBasis()) + "（课时）", "已保存课酬", "币种");
        for (Group g : r.teachers()) s.add(0, count(g.rank()), text(g.code()), count(g.total().records()), count(g.total().courses()), decimal(g.total().hours()), decimal(g.total().fee()), text(r.rules().currency()));
        s.filterEnd = s.rows.size();
        s.blank();
        s.add(3, text("整体总计"), text(""), count(r.total().records()), count(r.total().courses()), decimal(r.total().hours()), decimal(r.total().fee()), text(r.rules().currency()));
        s.difference("讲师课时差额", decimal(r.reconciliation().teacherHoursDifference()));
        s.difference("讲师课酬差额", decimal(r.reconciliation().teacherFeeDifference()));
        s.difference("讲师记录数差额", count(r.reconciliation().teacherRecordDifference()));
        s.note("总计课程数按整体去重，不能把分组课程数相加。差额 = 本表分组合计 - 整体总计；零表示一致。");
        return s;
    }

    private static Sheet organizations(Report r) {
        Sheet s = start("机构汇总", r, new int[] { 25, 14, 18, 23, 28, 12 }, "机构编码", "记录数", "唯一课程数", hourLabel(r.rules().hourBasis()) + "（课时）", "已保存课酬", "币种");
        for (Group g : r.organizations()) s.add(0, text(g.code()), count(g.total().records()), count(g.total().courses()), decimal(g.total().hours()), decimal(g.total().fee()), text(r.rules().currency()));
        s.filterEnd = s.rows.size();
        s.blank();
        s.add(3, text("整体总计"), count(r.total().records()), count(r.total().courses()), decimal(r.total().hours()), decimal(r.total().fee()), text(r.rules().currency()));
        s.difference("机构课时差额", decimal(r.reconciliation().organizationHoursDifference()));
        s.difference("机构课酬差额", decimal(r.reconciliation().organizationFeeDifference()));
        s.difference("机构记录数差额", count(r.reconciliation().organizationRecordDifference()));
        s.note("总计课程数按整体去重，不能把分组课程数相加。差额 = 本表分组合计 - 整体总计；零表示一致。");
        return s;
    }

    private static Sheet details(Report r) {
        Sheet s = start("明细对账", r, new int[] { 25, 25, 25, 25, 17, 20, 23, 28, 12, 26 }, "明细编码", "讲师编码", "机构编码", "课程编码", dateLabel(r.rules().dateBasis()), "状态", hourLabel(r.rules().hourBasis()) + "（课时）", "已保存课酬", "币种", "课酬版本");
        for (Detail d : r.details()) s.add(0, text(d.recordCode()), text(d.teacherCode()), text(d.orgCode()), text(d.courseCode()), text(d.date().toString()), text(statusLabel(d.status())), decimal(d.hours()), decimal(d.fee()), text(d.currency()), text(d.feeVersion()));
        s.filterEnd = s.rows.size();
        s.blank();
        s.add(3, text("整体总计"), text("记录数：" + r.total().records()), text(""), text("唯一课程：" + r.total().courses()), text(""), text(""), decimal(r.total().hours()), decimal(r.total().fee()), text(r.rules().currency()), text(""));
        s.difference("明细课时差额", decimal(r.reconciliation().detailHoursDifference()));
        s.difference("明细课酬差额", decimal(r.reconciliation().detailFeeDifference()));
        s.note("沿用已保存课酬及原课酬版本，调整记录保留正负号。差额 = 本表明细合计 - 整体总计；零表示一致。");
        return s;
    }

    private static Sheet start(String name, Report r, int[] widths, String... headers) {
        Sheet s = new Sheet(name, widths);
        String period = isMonth(r) ? YearMonth.from(r.filter().start()).toString() + " 月报" : "区间统计";
        s.add(1, text(name + " · " + period)); s.mergedRows.add(1);
        s.add(4, text(r.filter().start() + " 至 " + r.filter().end() + "（含首尾） | " + dateLabel(r.rules().dateBasis()) + " | " + hourLabel(r.rules().hourBasis()) + " | " + r.rules().currency())); s.mergedRows.add(2);
        s.add(2, java.util.Arrays.stream(headers).map(ManagementReportsWorkbook::text).toArray(Cell[]::new));
        return s;
    }
    private static boolean isMonth(Report r) { return r.filter().start().getDayOfMonth() == 1 && r.filter().end().equals(YearMonth.from(r.filter().start()).atEndOfMonth()); }
    private static String dateLabel(DateBasis basis) { return switch (basis) { case TEACHING -> "授课日期"; case CONFIRMATION -> "确认日期"; case PAYMENT -> "支付日期"; }; }
    private static String hourLabel(HourBasis basis) { return switch (basis) { case ESTIMATED -> "预计课时"; case PLANNED -> "计划课时"; case ACTUAL -> "实际课时"; case PAYABLE -> "应计酬课时"; }; }
    private static String statusLabel(Status status) { return switch (status) { case CONFIRMED -> "已确认（CONFIRMED）"; case CANCELLED -> "已取消（CANCELLED）"; case SUPERSEDED -> "已替代（SUPERSEDED）"; case ADJUSTMENT -> "调整（ADJUSTMENT）"; }; }
    private static String tieLabel(TieRule rule) { return switch (rule) { case COMPETITION -> "竞赛排名（1、2、2、4）"; case DENSE -> "密集排名（1、2、2、3）"; case ORDINAL -> "顺序排名（1、2、3、4）"; }; }
    private static Cell text(String value) { return new Cell(value, Kind.TEXT); }
    private static Cell count(int value) { return new Cell(Integer.toString(value), Kind.COUNT); }
    private static Cell decimal(BigDecimal value) {
        BigDecimal normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        return new Cell(normalized.toPlainString(), normalized.precision() <= 15 ? Kind.DECIMAL : Kind.TEXT);
    }

    private static final class Sheet {
        final String name;
        final int[] widths;
        final List<Row> rows = new ArrayList<>();
        final List<Integer> mergedRows = new ArrayList<>();
        int filterEnd;
        Sheet(String name, int[] widths) { this.name = name; this.widths = widths; }
        void add(int style, Cell... cells) {
            if (rows.size() >= 1048576) throw new IllegalArgumentException("报表超出单工作表行数上限，请缩小日期或机构范围");
            rows.add(new Row(List.of(cells), style));
        }
        void blank() { add(0); }
        void metric(String label, Cell value, String note) { add(0, text(label), value, text(note)); }
        void difference(String label, Cell value) { add(0, text(label), value); }
        void note(String value) { add(4, text(value)); mergedRows.add(rows.size()); }
        String xml() {
            String lastColumn = column(widths.length);
            StringBuilder out = new StringBuilder(XML).append("<worksheet xmlns=\"").append(NS).append("\"><dimension ref=\"A1:").append(lastColumn).append(rows.size()).append("\"/><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"3\" topLeftCell=\"A4\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"A4\" sqref=\"A4\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"18\"/><cols>");
            for (int i = 0; i < widths.length; i++) out.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(widths[i]).append("\" customWidth=\"1\"/>");
            out.append("</cols><sheetData>");
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i); int number = i + 1;
                out.append("<row r=\"").append(number).append("\"");
                if (number <= 3 || row.style() == 4) out.append(" ht=\"").append(number == 1 ? 30 : 34).append("\" customHeight=\"1\"");
                out.append('>');
                for (int j = 0; j < row.cells().size(); j++) {
                    Cell cell = row.cells().get(j);
                    int style = cell.kind() == Kind.TEXT ? row.style() : cell.kind() == Kind.COUNT ? (row.style() == 3 ? 7 : 5) : (row.style() == 3 ? 8 : 6);
                    out.append("<c r=\"").append(column(j + 1)).append(number).append("\" s=\"").append(style).append("\" t=\"").append(cell.kind() == Kind.TEXT ? "inlineStr" : "n").append("\">");
                    if (cell.kind() == Kind.TEXT) out.append("<is><t xml:space=\"preserve\">").append(escape(cell.value())).append("</t></is>");
                    else out.append("<v>").append(cell.value()).append("</v>");
                    out.append("</c>");
                }
                out.append("</row>");
            }
            out.append("</sheetData>");
            if (filterEnd > 0) out.append("<autoFilter ref=\"A3:").append(lastColumn).append(filterEnd).append("\"/>");
            out.append("<mergeCells count=\"").append(mergedRows.size()).append("\">");
            for (int row : mergedRows) out.append("<mergeCell ref=\"A").append(row).append(':').append(lastColumn).append(row).append("\"/>");
            return out.append("</mergeCells><pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/></worksheet>").toString();
        }
    }

    private static String column(int number) {
        StringBuilder value = new StringBuilder();
        while (number > 0) { number--; value.append((char) ('A' + number % 26)); number /= 26; }
        return value.reverse().toString();
    }
    private static String escape(String value) {
        if (value == null || value.length() > 32767) throw new IllegalArgumentException("单元格文本为空或超出Excel长度上限");
        StringBuilder out = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int c = value.codePointAt(offset); offset += Character.charCount(c);
            if (!(c == 9 || c == 10 || c == 13 || c >= 32 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF)) throw new IllegalArgumentException("单元格包含XML不支持的字符");
            switch (c) { case '&' -> out.append("&amp;"); case '<' -> out.append("&lt;"); case '>' -> out.append("&gt;"); case '"' -> out.append("&quot;"); case '\'' -> out.append("&apos;"); case 13 -> out.append("&#13;"); default -> out.appendCodePoint(c); }
        }
        return out.toString();
    }
    private static void entry(ZipOutputStream zip, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path); entry.setTimeLocal(ZIP_TIME); zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    private static String contentTypes() {
        StringBuilder value = new StringBuilder(XML).append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= 4; i++) value.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return value.append("</Types>").toString();
    }
    private static String styles() {
        return XML + "<styleSheet xmlns=\"" + NS + "\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.00######\"/></numFmts>"
                + "<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Microsoft YaHei\"/><color rgb=\"FF233D40\"/></font><font><b/><sz val=\"17\"/><name val=\"Microsoft YaHei\"/><color rgb=\"FF145B61\"/></font><font><b/><sz val=\"11\"/><name val=\"Microsoft YaHei\"/><color rgb=\"FF164B50\"/></font></fonts>"
                + "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDFF1F0\"/><bgColor indexed=\"64\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF0F6F5\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"9\">" + xf(0, 0, 0, false) + xf(1, 0, 0, false) + xf(2, 2, 0, false) + xf(2, 3, 0, false) + xf(0, 0, 0, false) + xf(0, 0, 1, true) + xf(0, 0, 164, true) + xf(2, 3, 1, true) + xf(2, 3, 164, true)
                + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
    }
    private static String xf(int font, int fill, int format, boolean numeric) {
        return "<xf numFmtId=\"" + format + "\" fontId=\"" + font + "\" fillId=\"" + fill + "\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment horizontal=\"" + (numeric ? "right" : "left") + "\" vertical=\"center\" wrapText=\"1\"/></xf>";
    }
}
