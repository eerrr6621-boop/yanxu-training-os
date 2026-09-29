package com.training;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static com.training.ManagementReports.*;

/** Synthetic-only XLSX package and actual-cell checks. Compiles/runs without Excel or a database. */
public final class M06WorkbookTest {
    private static int checks;
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final Filter MONTH = new Filter(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), Set.of());
    private static final AccessScope ACCESS = new AccessScope(Set.of("ORG-001", "ORG-002", "ORG-EMPTY"), true, true);
    private static Fact fact(long id, long course, String code, String teacher, String org, String hours, String fee, Status status, boolean allHours) {
        Map<HourBasis, BigDecimal> values = allHours ? Map.of(HourBasis.ACTUAL, new BigDecimal(hours), HourBasis.ESTIMATED, new BigDecimal("3"), HourBasis.PLANNED, new BigDecimal("2"), HourBasis.PAYABLE, new BigDecimal("1.5")) : Map.of(HourBasis.ACTUAL, new BigDecimal(hours));
        String courseCode = course == 9223372036854775601L ? "COURSE-0001" : course == 9223372036854775602L ? "COURSE-0002" : "COURSE-" + course;
        return new Fact(id, course, code, teacher, org, courseCode,
                Map.of(DateBasis.TEACHING, LocalDate.parse("2026-09-15"), DateBasis.PAYMENT, LocalDate.parse("2026-10-15")), values, new BigDecimal(fee), "CNY", "FEE-00001", status);
    }
    private static List<Fact> facts() {
        return List.of(fact(9223372036854775701L, 9223372036854775601L, "00001234567890123456789", "000001", "ORG-001", "0.1", "12.34", Status.CONFIRMED, true),
                fact(9223372036854775702L, 9223372036854775601L, "REC-02", "000002", "ORG-002", "0.2", "1234567890123456.12345678", Status.CONFIRMED, true),
                fact(9223372036854775703L, 9223372036854775602L, "REC-03", "000001", "ORG-001", "-0.05", "-0.05", Status.ADJUSTMENT, true));
    }
    private static Report report(List<Fact> facts, Filter filter, AccessScope access) { return build(facts, agreedDefaultRules("CNY"), filter, access); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        check(bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K', "real ZIP signature");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                check(entry.getTimeLocal().equals(LocalDateTime.of(2000, 1, 1, 0, 0)), "fixed ZIP timestamp");
                check(!result.containsKey(entry.getName()), "unique ZIP entry");
                result.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }
    private static Element cell(Document doc, String ref) {
        NodeList cells = doc.getElementsByTagNameNS(NS, "c");
        for (int i = 0; i < cells.getLength(); i++) { Element cell = (Element) cells.item(i); if (cell.getAttribute("r").equals(ref)) return cell; }
        throw new AssertionError("Missing cell " + ref);
    }
    private static String value(Element cell) { return cell.getTextContent(); }
    private static Element metric(Document doc, String label) {
        NodeList rows = doc.getElementsByTagNameNS(NS, "row");
        for (int i = 0; i < rows.getLength(); i++) {
            Element row = (Element) rows.item(i); NodeList cells = row.getElementsByTagNameNS(NS, "c");
            if (cells.getLength() >= 2 && cells.item(0).getTextContent().equals(label)) return (Element) cells.item(1);
        }
        throw new AssertionError("Missing metric " + label);
    }
    private static void typed(Element cell, String type, String expected, String label) {
        check(cell.getAttribute("t").equals(type), label + " cell type");
        check(value(cell).equals(expected), label + " exact cell value");
    }
    private static void rejects(Class<? extends RuntimeException> type, Runnable run, String label) {
        checks++;
        try { run.run(); } catch (RuntimeException ex) { if (type.isInstance(ex)) return; throw new AssertionError(label, ex); }
        throw new AssertionError(label + " should reject");
    }
    private static String escape(Method method, String value) {
        try { return (String) method.invoke(null, value); }
        catch (InvocationTargetException ex) { throw (RuntimeException) ex.getCause(); }
        catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
    public static void main(String[] args) throws Exception {
        Report r = report(facts(), MONTH, ACCESS); byte[] bytes = ManagementReportsWorkbook.export(r);
        Map<String, String> zip = unzip(bytes);
        check(zip.size() == 9, "minimal XLSX package with four worksheets");
        for (String xml : zip.values()) parse(xml);
        Document workbook = parse(zip.get("xl/workbook.xml")); NodeList names = workbook.getElementsByTagNameNS(NS, "sheet");
        check(names.getLength() == 4, "four worksheets");
        check(List.of("月度概览", "讲师排名", "机构汇总", "明细对账").equals(java.util.stream.IntStream.range(0, names.getLength()).mapToObj(i -> ((Element) names.item(i)).getAttribute("name")).toList()), "sheet order and Chinese names");
        check(zip.get("[Content_Types].xml").contains("spreadsheetml.sheet.main+xml"), "nonmacro XLSX content type");
        check(!zip.values().stream().anyMatch(s -> s.contains("TargetMode=\"External\"") || s.contains("vbaProject") || s.contains("<f>") || s.contains("<f ")), "no formulas, macros or external links");
        Document overview = parse(zip.get("xl/worksheets/sheet1.xml"));
        Document teachers = parse(zip.get("xl/worksheets/sheet2.xml"));
        Document organizations = parse(zip.get("xl/worksheets/sheet3.xml"));
        Document details = parse(zip.get("xl/worksheets/sheet4.xml"));
        for (int i = 1; i <= 4; i++) {
            Document sheet = parse(zip.get("xl/worksheets/sheet" + i + ".xml"));
            Element pane = (Element) sheet.getElementsByTagNameNS(NS, "pane").item(0);
            check(pane.getAttribute("state").equals("frozen") && pane.getAttribute("ySplit").equals("3"), "title and header frozen sheet " + i);
            check(sheet.getElementsByTagNameNS(NS, "col").getLength() >= 3, "column widths sheet " + i);
            check(cell(sheet, "A1").getAttribute("s").equals("1") && cell(sheet, "A3").getAttribute("s").equals("2"), "title/header style sheet " + i);
            if (i > 1) check(sheet.getElementsByTagNameNS(NS, "autoFilter").getLength() == 1, "data autofilter sheet " + i);
        }
        typed(cell(details, "A4"), "inlineStr", "00001234567890123456789", "leading zeros and long record code");
        typed(cell(details, "B4"), "inlineStr", "000001", "numeric teacher code");
        typed(cell(details, "G4"), "n", "0.1", "ordinary hours");
        typed(cell(details, "H4"), "n", "12.34", "ordinary fee");
        typed(cell(details, "H5"), "inlineStr", "1234567890123456.12345678", "long exact decimal fee");
        typed(cell(details, "H6"), "n", "-0.05", "signed adjustment fee");
        typed(cell(details, "J4"), "inlineStr", "FEE-00001", "original fee version");
        typed(cell(teachers, "A4"), "n", "1", "numeric ranking");
        typed(cell(teachers, "C4"), "n", "1", "numeric count");
        check(cell(details, "G4").getAttribute("s").equals("6") && zip.get("xl/styles.xml").contains("#,##0.00######"), "ordinary decimal format");
        check(((Element) details.getElementsByTagNameNS(NS, "autoFilter").item(0)).getAttribute("ref").equals("A3:J6"), "filter excludes total and reconciliation rows");
        typed(metric(overview, "唯一课程数"), "n", "2", "global distinct courses");
        typed(metric(overview, "记录数"), "n", "3", "selected records");
        typed(metric(overview, "所选类别课时合计"), "n", "0.25", "exact 0.1 + 0.2 - 0.05");
        typed(metric(overview, "已保存课酬合计"), "inlineStr", "1234567890123468.41345678", "aggregate precision retained");
        typed(metric(overview, "有效机构数量"), "n", "3", "manifest effective scope includes empty org");
        check(overview.getDocumentElement().getTextContent().contains("ORG-EMPTY"), "effective scope from manifest");
        typed(metric(overview, "预计课时"), "n", "9", "independent estimated hours");
        typed(metric(overview, "计划课时"), "n", "6", "independent planned hours");
        typed(metric(overview, "实际课时"), "n", "0.25", "independent actual hours");
        typed(metric(overview, "应计酬课时"), "n", "4.5", "independent payable hours");
        check(value(metric(overview, "课时标准")).equals("45分钟/课时") && value(metric(overview, "规则版本")).equals(AGREED_RULE_VERSION), "traceable hour unit and rule version");
        check(value(metric(overview, "对账结果")).equals("一致"), "overview reconciliation result");
        for (String label : List.of("明细课时差额", "明细课酬差额", "讲师课时差额", "讲师课酬差额", "机构课时差额", "机构课酬差额", "讲师记录数差额", "机构记录数差额")) typed(metric(overview, label), "n", "0", label);
        check(value(metric(teachers, "讲师课时差额")).equals("0") && value(metric(organizations, "机构课酬差额")).equals("0") && value(metric(details, "明细课酬差额")).equals("0"), "per-sheet reconciliation values");
        List<String> forbidden = List.of("id", "courseId", "teacher_name", "姓名", "备注", "内部数字ID");
        for (Document sheet : List.of(overview, teachers, organizations, details)) {
            Element header = (Element) sheet.getElementsByTagNameNS(NS, "row").item(2);
            check(forbidden.stream().noneMatch(header.getTextContent()::contains), "sensitive columns not exported");
        }
        check(!zip.values().stream().anyMatch(s -> s.contains("9223372036854775701")), "internal record ID value absent");
        check(!zip.values().stream().anyMatch(s -> s.contains("9223372036854775601")), "internal course ID value absent");
        check(details.getElementsByTagNameNS(NS, "row").item(2).getTextContent().equals("明细编码讲师编码机构编码课程编码授课日期状态实际课时（课时）已保存课酬币种课酬版本"), "detail whitelist is exact");
        check(Arrays.equals(bytes, ManagementReportsWorkbook.export(r)), "byte deterministic repeated export");
        List<Fact> reverse = new ArrayList<>(facts()); Collections.reverse(reverse);
        check(Arrays.equals(bytes, ManagementReportsWorkbook.export(report(reverse, MONTH, ACCESS))), "byte deterministic input order");
        Report partial = report(List.of(fact(4, 104, "REC-04", "T-04", "ORG-001", "1", "2", Status.CONFIRMED, false)), MONTH, ACCESS);
        Document partialOverview = parse(unzip(ManagementReportsWorkbook.export(partial)).get("xl/worksheets/sheet1.xml"));
        typed(metric(partialOverview, "预计课时"), "inlineStr", "待补齐（缺少 1 条）", "missing category is not zero");
        Report precision = report(List.of(fact(11, 111, "P-15", "T-15", "ORG-001", "1", "1234567890123.45", Status.CONFIRMED, true), fact(12, 112, "P-16", "T-16", "ORG-001", "2", "12345678901234.56", Status.CONFIRMED, true)), MONTH, ACCESS);
        Document precisionDetails = parse(unzip(ManagementReportsWorkbook.export(precision)).get("xl/worksheets/sheet4.xml"));
        typed(cell(precisionDetails, "H4"), "n", "1234567890123.45", "15 significant digits numeric boundary");
        typed(cell(precisionDetails, "H5"), "inlineStr", "12345678901234.56", "16 significant digits precise text boundary");
        Report empty = report(List.of(), MONTH, new AccessScope(Set.of(), true, true));
        Map<String, String> emptyZip = unzip(ManagementReportsWorkbook.export(empty));
        Document emptyOverview = parse(emptyZip.get("xl/worksheets/sheet1.xml"));
        typed(metric(emptyOverview, "记录数"), "n", "0", "empty report count");
        check(value(metric(emptyOverview, "规则版本")).equals(AGREED_RULE_VERSION), "empty report preserves rules");
        for (int i = 2; i <= 4; i++) {
            Document sheet = parse(emptyZip.get("xl/worksheets/sheet" + i + ".xml"));
            check(!value(cell(sheet, "A1")).isBlank() && !value(cell(sheet, "A3")).isBlank(), "empty sheet retains title and header");
            check(((Element) sheet.getElementsByTagNameNS(NS, "autoFilter").item(0)).getAttribute("ref").endsWith("3"), "empty data filter only header");
        }
        Report scoped = report(facts(), new Filter(MONTH.start(), MONTH.end(), Set.of("ORG-002")), ACCESS);
        Map<String, String> scopedZip = unzip(ManagementReportsWorkbook.export(scoped));
        check(scopedZip.values().stream().noneMatch(s -> s.contains("ORG-001") || s.contains("ORG-EMPTY")), "selected organization only");
        Report denied = report(facts(), MONTH, new AccessScope(Set.of("ORG-001"), true, false));
        rejects(SecurityException.class, () -> ManagementReportsWorkbook.export(denied), "export permission");
        rejects(SecurityException.class, () -> report(facts(), MONTH, new AccessScope(Set.of("ORG-001"), false, true)), "view permission");
        rejects(SecurityException.class, () -> report(facts(), new Filter(MONTH.start(), MONTH.end(), Set.of("ORG-002")), new AccessScope(Set.of("ORG-001"), true, true)), "outside organization scope");
        rejects(IllegalArgumentException.class, () -> ManagementReportsWorkbook.export(null), "null report");
        Report custom = report(facts(), new Filter(MONTH.start().plusDays(2), MONTH.end(), Set.of()), ACCESS);
        Document customOverview = parse(unzip(ManagementReportsWorkbook.export(custom)).get("xl/worksheets/sheet1.xml"));
        check(value(cell(customOverview, "A1")).contains("区间统计") && value(metric(customOverview, "报表类型")).equals("区间统计"), "custom range title");
        Report payment = build(facts(), agreedRules(DateBasis.PAYMENT, HourBasis.PAYABLE, RankMetric.FEE, "CNY"), new Filter(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), Set.of()), ACCESS);
        Map<String, String> paymentZip = unzip(ManagementReportsWorkbook.export(payment));
        check(value(cell(parse(paymentZip.get("xl/worksheets/sheet4.xml")), "E3")).equals("支付日期"), "selected payment date header");
        check(value(metric(parse(paymentZip.get("xl/worksheets/sheet1.xml")), "所选课时口径")).equals("应计酬课时"), "selected payable hour basis");
        // Public report constructors restrict business codes; test XML escaping defensively at its sole encoder.
        Method encoder = ManagementReportsWorkbook.class.getDeclaredMethod("escape", String.class); encoder.setAccessible(true);
        String special = "0001<&>\"'中文😀\r\n";
        String escaped = escape(encoder, special);
        check(escaped.contains("&amp;") && escaped.contains("&lt;") && escaped.contains("&quot;") && escaped.contains("&apos;"), "XML metacharacters escaped");
        check(parse("<t>" + escaped + "</t>").getDocumentElement().getTextContent().equals(special), "XML exact Unicode and CRLF roundtrip");
        rejects(IllegalArgumentException.class, () -> escape(encoder, "x\u0001"), "invalid XML control char");
        rejects(IllegalArgumentException.class, () -> escape(encoder, "x\uD800"), "unpaired surrogate");
        rejects(IllegalArgumentException.class, () -> escape(encoder, "x".repeat(32768)), "Excel cell string bound");
        if (args.length == 2 && args[0].equals("--write-demo")) Files.write(Path.of(args[1]), bytes);
        System.out.println("M06 workbook: " + checks + " checks passed (synthetic only)");
    }
}
