package com.training;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

/** Server-owned coded settlement XLSX. No identity text, evidence, formulas or external links. */
public final class ManagementSettlementWorkbook {
    private ManagementSettlementWorkbook() {}
    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private record Cell(String value, boolean numeric, boolean quantity, boolean unknown) {}
    private record Row(List<Cell> cells, int height, int style) {}

    static byte[] export(ManagementSettlementAccounting.Report report, String snapshotVersion) {
        if (!report.exportable() || snapshotVersion == null || !snapshotVersion.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("需要已授权、正式编码完整的服务器统计快照");
        Map<String,Object> view=report.toMap(); view.put("snapshot_version",snapshotVersion);
        List<Sheet> sheets=List.of(overview(view),teachers(view),organizations(view),details(view),instructions(view));
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
        } catch (IOException e) { throw new UncheckedIOException("结算月报工作簿生成失败", e); }
    }


    private static Sheet overview(Map<String,Object> v) {
        Sheet s=start("月度概览",v,new int[]{28,35,35,24,22},"指标","净额或完整课时","正向金额或已知课时","冲回金额或缺失数","单位 / 状态");
        Map<String,Object> total=object(v.get("totals"));
        s.row(text(payment(v)?"实付净额":"应计课酬净额"),number(total.get("amount")),number(total.get("positive_amount")),number(total.get("negative_amount")),text("人民币元"));
        for (Map<String,Object> a:rows(v.get("activity_totals"))) s.row(text(activity(a.get("activity"))),number(a.get("amount")),number(a.get("positive_amount")),number(a.get("negative_amount")),text("人民币元"));
        for(String k:ManagementSettlementAccounting.HOURS) {
            Map<String,Object> h=object(object(total.get("hours")).get(k));
            s.row(text(hour(k)),number(h.get("total"),payment(v)),number(h.get("known_subtotal"),payment(v)),payment(v)?text("不归集"):count(h.get("missing_count")),text(payment(v)?"支付日不分摊课时":"课时，分别展示"));
        }
        s.row(text("本期财务分录数"),count(total.get("entry_count")),text(""),text(""),text("条；更正分录分别保留"));
        return s;
    }
    private static Sheet teachers(Map<String,Object> v) {
        Sheet s=start("讲师排名",v,new int[]{10,28,20,20,20,20,24,20},"名次","正式讲师编码（保留历史）","预计课时","计划课时","实际课时","计酬课时",payment(v)?"实付净额（元）":"应计净额（元）","排名值");
        for(Map<String,Object> r:rows(v.get("teachers"))) {
            List<Cell> cells=new ArrayList<>();cells.add(r.get("rank")==null?text("未排名"):count(r.get("rank")));List<String> codes=new ArrayList<>();for(Object code:(List<?>)r.get("teacher_codes"))codes.add(code(code));cells.add(text(String.join(", ",codes)));
            addHours(cells,r,payment(v));cells.add(number(r.get("amount")));cells.add(number(r.get("rank_value")));s.row(cells);
        }
        return s;
    }
    private static Sheet organizations(Map<String,Object> v) {
        Sheet s=start("机构汇总",v,new int[]{28,20,20,20,20,24,16},"机构编码","预计课时","计划课时","实际课时","计酬课时",payment(v)?"实付净额（元）":"应计净额（元）","财务分录数");
        for(Map<String,Object> r:rows(v.get("organizations"))){List<Cell> cells=new ArrayList<>();cells.add(text(code(r.get("organization_code"))));addHours(cells,r,payment(v));cells.add(number(r.get("amount")));cells.add(count(r.get("entry_count")));s.row(cells);}
        return s;
    }
    private static Sheet details(Map<String,Object> v) {
        Sheet s=start("对账明细",v,new int[]{28,28,16,20,22,22,26,26,20,20,20,20,24,50,24,24,24,24,30},"财务分录编码","账链编码",payment(v)?"支付日期":"授课或业务日期","分录类型","业务类型","机构编码","正式讲师编码","正式课程编码","预计课时增减","计划课时增减","实际课时增减","计酬课时增减",payment(v)?"支付或退款（元）":"应计或调整（元）","应计分录引用","调整后预计课时","调整后计划课时","调整后实际课时","调整后计酬课时","课时统计方式");
        for(Map<String,Object> r:rows(v.get("details"))){
            List<Cell> cells=new ArrayList<>(List.of(text(code(r.get("entry_code"))),text(code(r.get("chain_code"))),text(r.get("date").toString()),text(kind(r.get("kind"))),text(activity(r.get("activity"))),text(code(r.get("organization_code"))),text(code(r.get("teacher_code"))),text(code(r.get("course_code")))));
            Map<String,Object> h=object(r.get("hours"));for(String k:ManagementSettlementAccounting.HOURS)cells.add(number(h.get(k),payment(v)));
            cells.add(number(r.get("amount")));List<String> refs=new ArrayList<>();for(Object ref:(List<?>)r.get("accrual_entry_codes"))refs.add(code(ref));cells.add(text(String.join(", ",refs)));
            Map<String,Object> after=object(r.get("hours_after"));for(String k:ManagementSettlementAccounting.HOURS)cells.add(number(after.get(k),payment(v)));
            cells.add(text(payment(v)?"支付不归集":Boolean.TRUE.equals(r.get("hours_counted"))?"本日期净课时，取调整后值":"历史修订，不重复计入"));s.row(cells);
        }
        return s;
    }
    private static Sheet instructions(Map<String,Object> v) {
        Sheet s=start("说明",v,new int[]{26,115},"项目","口径与说明");
        s.row(text("文件用途"),text("人民币正式冻结账统计；供核对，不是银行转账指令或税费凭证。"));
        s.row(text("日期口径"),text(payment(v)?"按支付/退款实际日期归集现金净流，未付款应计不作为已支付。":"授课、开发和旧制迁移按冻结业务日期归属，首尾日期均计入。"));
        s.row(text("四类课时"),text("预计、计划、实际、计酬分别列示，不能相加；每条账链每个业务日期取最后完整课时状态，历史修订不重复相加。未知不填0；支付不归集课时。"));
        s.row(text("金额规则"),text("沿用M05逐笔人民币两位HALF_UP冻结金额后精确加总；本报表不重新定价或舍入。"));
        s.row(text("取消与更正"),text("金额按差额累计；课时按明细“本日期净课时”行的调整后值汇总。未知补齐或撤回以冻结前后状态为据；跨日原日冲回后为0，新日取新状态。"));
        s.row(text("排名"),text(("FEE".equals(v.get("rank_metric"))?"金额":hour(v.get("hour_basis")))+"降序，竞争并列1、2、2、4；未知排名值不参与排名。"));
        s.row(text("类别"),text("授课、独立开发、联合开发与明确批准的旧制迁移全部纳入；开发日期为M05冻结业务日期。"));
        s.row(text("覆盖范围"),text("仅含可信组织来源项目下已经核准并冻结的账；未完成归属及逐笔批准迁移的旧制数据不属于正式账，不从历史费用表推算。"));
        s.row(text("编码与隐私"),text("仅机构及正式讲师/课程编码和不可变财务引用；不含姓名、联系方式、项目标题、证据正文或系统人员ID。"));
        s.row(text("编码来源"),text("正式编码可由另行明确确认的审计关联补齐；关联更正保留独立历史，不改原金额、课时或支付账。"));
        s.row(text("空月份"),text("无财务分录时金额为0；授课口径课时为0，支付口径课时仍为不归集；明细为空。"));
        s.row(text("精度"),text("超过Excel十五位有效数字的值按精确文本保存。无公式、宏或外链；不要将长数值转为浮点后重算。"));
        s.row(text("统计版本"),text(v.get("snapshot_version").toString()));s.row(text("接口版本"),text(ManagementSettlementAccounting.VERSION));
        return s;
    }
    private static void addHours(List<Cell> cells,Map<String,Object>r,boolean payment){for(String k:ManagementSettlementAccounting.HOURS)cells.add(number(object(object(r.get("hours")).get(k)).get("total"),payment));}
    private static boolean payment(Map<String,Object>v){return "PAYMENT".equals(v.get("date_basis"));}
    private static String hour(Object k){return switch(k.toString()){case "ESTIMATED"->"预计课时";case "PLANNED"->"计划课时";case "ACTUAL"->"实际课时";case "PAYABLE"->"计酬课时";default->throw new IllegalArgumentException();};}
    private static String activity(Object k){return switch(k.toString()){case "TEACHING"->"授课";case "SOLO_DEVELOPMENT"->"独立开发";case "JOINT_DEVELOPMENT"->"联合开发";case "LEGACY"->"旧制迁移";default->throw new IllegalArgumentException();};}
    private static String kind(Object k){return switch(k.toString()){case "CONFIRMED"->"核准应计";case "ADJUSTMENT"->"差额调整";case "REVERSAL"->"原日冲回";case "REBOOK"->"新日重记";case "LEGACY_OPENING"->"旧制迁移期初";case "PAYMENT"->"支付";case "REFUND"->"退款";default->throw new IllegalArgumentException();};}
    private static Sheet start(String name,Map<String,Object>v,int[]widths,String...headers){Sheet s=new Sheet(name,widths);s.rows.add(new Row(List.of(text(name+" · 课酬结算月报")),34,1));s.rows.add(new Row(List.of(text(v.get("start")+" 至 "+v.get("end")+" | "+(payment(v)?"支付日期":"授课或业务日期")+" | 人民币元 | 四类课时分别展示")),42,0));s.rows.add(new Row(Arrays.stream(headers).map(ManagementSettlementWorkbook::text).toList(),62,2));return s;}
    private static Cell text(String v){return new Cell(Objects.requireNonNull(v),false,false,false);}
    private static Cell count(Object v){long n=new BigDecimal(v.toString()).longValueExact();if(n<0||n>50000)throw new IllegalArgumentException("记录数无效");return new Cell(Long.toString(n),true,false,false);}
    private static Cell number(Object v){return number(v,false);}
    private static Cell number(Object v,boolean notApplicable){if(v==null)return new Cell(notApplicable?"不归集":"未知",false,false,true);String raw=v.toString();if(!raw.matches("-?[0-9]+(?:\\.[0-9]+)?")||raw.length()>76)throw new IllegalArgumentException("统计数值无效");BigDecimal n=new BigDecimal(raw);if(n.scale()>8||n.precision()>72)throw new IllegalArgumentException("统计精度超限");BigDecimal normalized=n.signum()==0?BigDecimal.ZERO:n.stripTrailingZeros();return new Cell(raw,normalized.precision()<=15,true,false);}
    private static String code(Object v){return ManagementSettlementAccounting.code(v,false);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object v){return (Map<String,Object>)v;}
    private static List<Map<String,Object>> rows(Object v){return ManagementSettlementAccounting.rows(v);}
    private static final class Sheet {
        final String name; final int[] widths; final List<Row> rows = new ArrayList<>();
        Sheet(String name,int[] widths) { this.name=name; this.widths=widths; }
        void row(Cell... cells) { row(List.of(cells)); }
        void row(List<Cell> cells) {
            if (rows.size()>=50010 || cells.size()!=widths.length) throw new IllegalArgumentException("工作簿结构超限");
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
