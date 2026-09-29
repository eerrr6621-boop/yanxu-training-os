package com.training;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

/** Server-only allowlisted teaching XLSX. No names, source text, money, formulas or external links. */
public final class ManagementTeachingWorkbook {
    private ManagementTeachingWorkbook() {}
    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final List<String> CATEGORIES = List.of("estimated", "planned", "actual", "payable");
    private static final List<String> LABELS = List.of("预计", "计划", "实际", "计酬");
    private static final Map<String,String> REASONS = Map.ofEntries(
            Map.entry("TEACHING_DATE_UNKNOWN", "授课日期缺失或无效，无法确定月份"),
            Map.entry("REJECTED", "排课已拒绝，不计入已核对授课"),
            Map.entry("NOT_COMPLETED", "排课尚未完成"),
            Map.entry("FUTURE_TEACHING_DATE", "授课日期尚未到达"),
            Map.entry("SOURCE_ASSOCIATION_CHANGED", "授课来源关联或日期发生变化，需核对"),
            Map.entry("FACT_MISSING", "尚未登记可信授课事实"),
            Map.entry("NON_BUSINESS_FACT", "演示或未配置事实不计入业务统计"),
            Map.entry("NOT_VERIFIED", "授课事实尚未核对"),
            Map.entry("VERIFICATION_SOURCE_INVALID", "当前核对修订与历史依据不一致"),
            Map.entry("ACTUAL_EVIDENCE_INVALID", "实际分钟与课时换算依据不完整或不一致"),
            Map.entry("FACT_INVALID", "授课事实格式或核对依据无效，需核对来源"));
    private record Cell(String value, boolean numeric, boolean quantity, boolean unknown) {}
    private record Row(List<Cell> cells, int height, int style) {}

    /** Only ManagementReportsIntegration calls this after current Auth/M01 and snapshot CAS. */
    static byte[] export(Map<String,Object> view) {
        if (!Boolean.TRUE.equals(view.get("teaching_export_available"))
                || !Boolean.TRUE.equals(object(view.get("permissions")).get("export"))
                || !"TEACHING".equals(view.get("date_basis")))
            throw new IllegalArgumentException("仅接受已经授权的授课统计快照");
        List<Sheet> sheets = List.of(instructions(view), totals(view), teachers(view), organizations(view), details(view), excluded(view));
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            entry(zip, "_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            StringBuilder book = new StringBuilder(XML).append("<workbook xmlns=\"").append(NS).append("\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView/></bookViews><sheets>");
            StringBuilder rels = new StringBuilder(XML).append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < sheets.size(); i++) {
                int n = i + 1;
                book.append("<sheet name=\"").append(sheets.get(i).name).append("\" sheetId=\"").append(n).append("\" r:id=\"rId").append(n).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(n).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(n).append(".xml\"/>");
                entry(zip, "xl/worksheets/sheet" + n + ".xml", sheets.get(i).xml());
            }
            book.append("</sheets></workbook>");
            rels.append("<Relationship Id=\"rId").append(sheets.size()+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            entry(zip, "xl/workbook.xml", book.toString());
            entry(zip, "xl/_rels/workbook.xml.rels", rels.toString());
            entry(zip, "xl/styles.xml", styles());
            zip.finish(); return bytes.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException("授课统计工作簿生成失败", e); }
    }

    private static Sheet instructions(Map<String,Object> v) {
        Sheet s = start("说明", v, new int[]{26,114}, "项目", "说明或数值");
        s.row(text("文件用途"), text("当前授课事实统计，不是结算或正式编码月报；不包含金额、币种或支付记录。"));
        s.row(text("日期范围"), text(date(v.get("start")) + " 至 " + date(v.get("end")) + "，首尾日均计入；仅按授课日期。"));
        s.row(text("覆盖范围"), text("仅覆盖已建立可信机构来源的排课；未迁移旧项目不在本统计覆盖内。"));
        s.row(text("计入条件"), text("排课已完成、授课已核对、来源关联及当前核对修订一致、实际分钟换算依据有效。"));
        s.row(text("四类课时"), text("预计、计划、实际、计酬分别展示，均来自同一批已完成已核对记录，不能相加。"));
        s.row(text("课时单位"), text("45分钟为1课时。实际课时沿用每条授课分钟÷45后两位HALF_UP结果，再求和；不推定计酬课时。"));
        s.row(text("未知与零值"), text("未知明确显示“未知”；已知小计只是有值记录之和。完整合计为未知时，不得用已知小计冒充完整合计。0为有效值。"));
        s.row(text("排名规则"), text("按实际课时降序，竞争并列1、2、2、4；同值按讲师系统记录ID稳定展示。其他课时不用于本排名。"));
        s.row(text("身份标识"), text("机构为可信机构编码；讲师、项目、排课仅为系统记录ID（非正式编码）。不提供正式人员码、课程码或唯一课程数。"));
        s.row(text("隐私范围"), text("不包含讲师姓名、项目标题、课程或授课自由文本、备注、联系方式、核对人或证据原文。"));
        s.row(text("未计入原因"), text("使用固定服务器原因码与固定文案。日期未知记录单列“日期未知、无法归月”，不将其冒充所选月份记录。"));
        s.row(text("精度与使用"), text("数量最多15位有效数字时为数值，超过则以精确文本保留；ID与机构编码均为文本。无公式、外链或宏，请勿转为浮点后重新求和。"));
        s.row(text("数据来源"), text("同一次服务器授课统计快照：可信受理机构、排课及当前M05已核对事实。只读下载，不修改业务记录。"));
        s.row(text("已核对记录数"), count(v.get("included_count")));
        s.row(text("未计入记录数"), count(v.get("excluded_count")));
        s.row(text("其中日期未知数"), count(v.get("undated_excluded_count")));
        for (Object org : list(v.get("organizations"))) s.row(text("有效机构编码"), text(code(org)));
        s.row(text("统计投影版本"), text(hash(v.get("snapshot_version"))));
        s.row(text("版本含义"), text("当前输出投影版本，用于核对查询与下载的一致性；不是已持久化的原始历史快照。"));
        s.row(text("实现与口径"), text("M06-REVIEWED-TEACHING-1 / M06-20260921-1"));
        return s;
    }

    private static Sheet totals(Map<String,Object> v) {
        Sheet s = start("四类汇总", v, new int[]{16,34,34,18,20}, "课时类别", "完整合计（课时）", "已知小计（课时）", "缺失记录数", "完整性");
        for (int i=0; i<CATEGORIES.size(); i++) {
            List<Cell> cells = new ArrayList<>(List.of(text(LABELS.get(i))));
            addTotal(cells, object(object(v.get("totals")).get(CATEGORIES.get(i)))); s.row(cells);
        }
        return s;
    }

    private static Sheet teachers(Map<String,Object> v) {
        Sheet s = start("讲师排名", v, new int[]{10,25,14,12,34,34,18,18}, "实际课时名次", "讲师系统记录ID\n（非正式编码）", "授课记录数", "课时类别", "完整合计（课时）", "已知小计（课时）", "缺失记录数", "完整性");
        for (Map<String,Object> row : rows(v.get("teacher_ranking"))) for (int i=0; i<CATEGORIES.size(); i++) {
            List<Cell> cells = new ArrayList<>(List.of(count(row.get("rank")), id(row.get("teacher_id")), count(row.get("dispatch_count")), text(LABELS.get(i))));
            addTotal(cells, object(object(row.get("hours")).get(CATEGORIES.get(i)))); s.row(cells);
        }
        return s;
    }

    private static Sheet organizations(Map<String,Object> v) {
        Sheet s = start("机构汇总", v, new int[]{20,14,12,34,34,18,18}, "机构编码", "授课记录数", "课时类别", "完整合计（课时）", "已知小计（课时）", "缺失记录数", "完整性");
        for (Map<String,Object> row : rows(v.get("organization_summary"))) for (int i=0; i<CATEGORIES.size(); i++) {
            List<Cell> cells = new ArrayList<>(List.of(text(code(row.get("organization_code"))), count(row.get("dispatch_count")), text(LABELS.get(i))));
            addTotal(cells, object(object(row.get("hours")).get(CATEGORIES.get(i)))); s.row(cells);
        }
        return s;
    }

    private static Sheet details(Map<String,Object> v) {
        Sheet s = start("已核对明细", v, new int[]{24,24,24,18,15,12,34,34,34,34}, "排课系统记录ID\n（非正式编码）", "项目系统记录ID\n（非正式编码）", "讲师系统记录ID\n（非正式编码）", "机构编码", "授课日期", "事实修订号", "预计（课时）", "计划（课时）", "实际（课时）", "计酬（课时）");
        for (Map<String,Object> row : rows(v.get("details"))) {
            List<Cell> cells = new ArrayList<>(List.of(id(row.get("dispatch_id")), id(row.get("project_id")), id(row.get("teacher_id")), text(code(row.get("organization_code"))), text(date(row.get("teaching_date"))), id(row.get("fact_revision"))));
            for (String category : CATEGORIES) cells.add(quantity(object(row.get("hours")).get(category)));
            s.row(cells);
        }
        return s;
    }

    private static Sheet excluded(Map<String,Object> v) {
        Sheet s = start("未计入原因", v, new int[]{25,18,28,37,68}, "排课系统记录ID\n（非正式编码）", "机构编码", "日期归属", "原因码", "固定说明");
        for (Map<String,Object> row : rows(v.get("excluded"))) {
            String code = (String)row.get("code"), reason = REASONS.get(code);
            if (reason == null) throw new IllegalArgumentException("未支持的服务器排除原因码");
            s.row(id(row.get("dispatch_id")), text(code(row.get("organization_code"))),
                    text(Boolean.TRUE.equals(row.get("date_unknown")) ? "日期未知、无法归月" : "所选日期范围或日期关联异常"), text(code), text(reason));
        }
        return s;
    }

    private static void addTotal(List<Cell> cells, Map<String,Object> total) {
        cells.add(quantity(total.get("value"))); cells.add(quantity(total.get("known_subtotal")));
        cells.add(count(total.get("missing_records"))); cells.add(text(Boolean.TRUE.equals(total.get("complete")) ? "完整" : "存在未知"));
    }
    private static Sheet start(String name, Map<String,Object> v, int[] widths, String... headers) {
        Sheet s = new Sheet(name, widths);
        s.rows.add(new Row(List.of(text(name + " · 已核对授课统计")), 34, 1));
        s.rows.add(new Row(List.of(text(date(v.get("start")) + " 至 " + date(v.get("end")) + " | 授课日期 | 四类课时分别展示 | 非结算月报，不含金额与支付")), 42, 0));
        s.rows.add(new Row(Arrays.stream(headers).map(ManagementTeachingWorkbook::text).toList(), 62, 2));
        return s;
    }

    private static Cell text(String v) { return new Cell(Objects.requireNonNull(v),false,false,false); }
    private static Cell quantity(Object v) {
        if (v == null) return new Cell("未知",false,false,true);
        if (!(v instanceof String raw) || !raw.matches("[0-9]+(?:\\.[0-9]+)?") || raw.length()>48) throw new IllegalArgumentException("课时格式无效");
        BigDecimal value = new BigDecimal(raw);
        // Up to 10,000 valid 24-digit source quantities can yield 28 integer digits.
        // Adding a source with scale 8 retains all fractional digits: allow 36 in aggregates.
        if (value.scale()>8 || value.precision()>36) throw new IllegalArgumentException("课时精度超限");
        BigDecimal normalized = value.signum()==0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        // Excel's 15 significant digit limit also applies to aggregate values and internal IDs.
        return new Cell(raw, normalized.precision()<=15, true, false);
    }
    private static Cell count(Object v) {
        long n = integer(v); if (n<0 || n>100000) throw new IllegalArgumentException("记录数量超限");
        return new Cell(Long.toString(n),true,false,false);
    }
    private static Cell id(Object v) {
        long n=integer(v); if(n<=0 || n>9007199254740991L) throw new IllegalArgumentException("系统记录ID无效");
        return text(Long.toString(n));
    }
    private static long integer(Object v) {
        if (!(v instanceof Number) && !(v instanceof String)) throw new IllegalArgumentException("整数无效");
        return new BigDecimal(v.toString()).longValueExact();
    }
    private static String code(Object v) {
        if (!(v instanceof String s) || !s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")) throw new IllegalArgumentException("机构编码无效");
        return (String)v;
    }
    private static String hash(Object v) {
        if (!(v instanceof String s) || !s.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("统计版本无效");
        return (String)v;
    }
    private static String date(Object v) { return LocalDate.parse((String)v).toString(); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object v) { return (Map<String,Object>)v; }
    private static List<?> list(Object v) { return (List<?>)v; }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object v) { return (List<Map<String,Object>>)v; }

    private static final class Sheet {
        final String name; final int[] widths; final List<Row> rows = new ArrayList<>();
        Sheet(String name,int[] widths) { this.name=name; this.widths=widths; }
        void row(Cell... cells) { row(List.of(cells)); }
        void row(List<Cell> cells) {
            if (rows.size()>=50000 || cells.size()!=widths.length) throw new IllegalArgumentException("工作簿结构超限");
            rows.add(new Row(List.copyOf(cells),name.equals("说明")?48:34,0));
        }
        String xml() {
            String last=column(widths.length);
            StringBuilder b=new StringBuilder(XML).append("<worksheet xmlns=\"").append(NS).append("\"><dimension ref=\"A1:").append(last).append(rows.size()).append("\"/><sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"><pane ySplit=\"3\" topLeftCell=\"A4\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"A4\" sqref=\"A4\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"34\"/><cols>");
            for(int i=0;i<widths.length;i++) b.append("<col min=\"").append(i+1).append("\" max=\"").append(i+1).append("\" width=\"").append(widths[i]).append("\" customWidth=\"1\"/>");
            b.append("</cols><sheetData>");
            for(int i=0;i<rows.size();i++) {
                Row row=rows.get(i); int n=i+1;
                b.append("<row r=\"").append(n).append("\" ht=\"").append(row.height).append("\" customHeight=\"1\">");
                for(int j=0;j<row.cells.size();j++) {
                    Cell c=row.cells.get(j); int style=row.style!=0?row.style:c.unknown?4:c.quantity?c.numeric?5:6:c.numeric?3:0;
                    b.append("<c r=\"").append(column(j+1)).append(n).append("\" s=\"").append(style).append("\" t=\"").append(c.numeric?"n":"inlineStr").append("\">");
                    if(c.numeric) b.append("<v>").append(c.value).append("</v>");
                    else b.append("<is><t xml:space=\"preserve\">").append(escape(c.value)).append("</t></is>");
                    b.append("</c>");
                }
                b.append("</row>");
            }
            b.append("</sheetData>");
            if (!name.equals("说明") && rows.size()>3) b.append("<autoFilter ref=\"A3:").append(last).append(rows.size()).append("\"/>");
            b.append("<mergeCells count=\"2\"><mergeCell ref=\"A1:").append(last).append("1\"/><mergeCell ref=\"A2:").append(last).append("2\"/></mergeCells>");
            return b.append("<pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/></worksheet>").toString();
        }
    }
    private static String column(int n) { StringBuilder b=new StringBuilder(); while(n>0) {n--; b.append((char)('A'+n%26));n/=26;}return b.reverse().toString(); }
    private static String escape(String v) {
        if(v.length()>32767)throw new IllegalArgumentException("单元格文本过长");
        StringBuilder b=new StringBuilder();
        for(int offset=0;offset<v.length();) {
            int c=v.codePointAt(offset);offset+=Character.charCount(c);
            if(!(c==9||c==10||c==13||c>=32&&c<=0xD7FF||c>=0xE000&&c<=0xFFFD||c>=0x10000&&c<=0x10FFFF))throw new IllegalArgumentException("XML字符无效");
            switch(c) {case '&' -> b.append("&amp;");case '<' -> b.append("&lt;");case '>' -> b.append("&gt;");case '"' -> b.append("&quot;");case '\'' -> b.append("&apos;");case 13 -> b.append("&#13;");default -> b.appendCodePoint(c);}
        }
        return b.toString();
    }
    private static void entry(ZipOutputStream zip,String path,String content) throws IOException {
        ZipEntry e=new ZipEntry(path);e.setTimeLocal(LocalDateTime.of(2000,1,1,0,0));zip.putNextEntry(e);zip.write(content.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    private static String contentTypes(int count) {
        StringBuilder b=new StringBuilder(XML).append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for(int i=1;i<=count;i++) b.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return b.append("</Types>").toString();
    }
    private static String styles() {
        return XML+"<styleSheet xmlns=\""+NS+"\"><numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.00######\"/></numFmts>"
                +"<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Microsoft YaHei\"/><color rgb=\"FF243B43\"/></font><font><b/><sz val=\"18\"/><name val=\"Microsoft YaHei\"/><color rgb=\"FF145B61\"/></font><font><b/><sz val=\"11\"/><name val=\"Microsoft YaHei\"/><color rgb=\"FF164B50\"/></font></fonts>"
                +"<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDFF1F0\"/><bgColor indexed=\"64\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFF0D5\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                +"<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                +"<cellXfs count=\"7\">"+xf(0,0,0,false)+xf(1,0,0,false)+xf(2,2,0,false)+xf(0,0,1,true)+xf(0,3,0,false)+xf(0,0,164,true)+xf(0,0,49,true)
                +"</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
    }
    private static String xf(int font,int fill,int format,boolean right) {
        return "<xf numFmtId=\""+format+"\" fontId=\""+font+"\" fillId=\""+fill+"\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment horizontal=\""+(right?"right":"left")+"\" vertical=\"center\" wrapText=\"1\"/></xf>";
    }
}
