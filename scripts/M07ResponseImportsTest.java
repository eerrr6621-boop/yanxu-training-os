package com.training;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Independent, synthetic-only M07 response preview checks. Does not open user spreadsheets. */
public final class M07ResponseImportsTest {
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final SurveySummaryImportsResponses.DraftRules RULES = SurveySummaryImportsResponses.DraftRules.proposed();
    private static final SurveySummaryImports.Project PROJECT = new SurveySummaryImports.Project(7, "0007", "合成项目");
    private static int passed, failed;

    private interface Checked { void run() throws Exception; }
    private static void run(String name, Checked check) {
        try { check.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable ex) { failed++; System.out.println("FAIL " + name + ": " + ex.getClass().getSimpleName() + " " + ex.getMessage()); }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) { require(Objects.equals(expected, actual), "expected=" + expected + " actual=" + actual); }
    private static void security(Checked action) throws Exception {
        try { action.run(); } catch (SecurityException expected) { return; }
        throw new AssertionError("SecurityException required");
    }
    private static CancellationException cancelled(Checked action) throws Exception {
        try { action.run(); } catch (CancellationException expected) { return expected; }
        throw new AssertionError("CancellationException required; a file ERROR result is not cancellation");
    }
    private static SurveySummaryImportsResponses.PreviewContext context(boolean history, List<SurveySummaryImportsResponses.PreviousImport> previous) {
        return new SurveySummaryImportsResponses.PreviewContext(true, List.of(PROJECT), previous, history, true);
    }
    private static Map<String,Object> preview(byte[] file) {
        return SurveySummaryImportsResponses.preview(file, PROJECT.id(), RULES, context(false, List.of()));
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> questions(Map<String,Object> result) {
        return (List<Map<String,Object>>) result.get("questions");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> duplicate(Map<String,Object> result) {
        return (Map<String,Object>) result.get("duplicateCheck");
    }
    @SuppressWarnings("unchecked") private static Set<String> codes(Map<String,Object> result) {
        Set<String> codes = new HashSet<>();
        for (Map<String,Object> issue : (List<Map<String,Object>>) result.get("issues")) codes.add((String) issue.get("code"));
        return codes;
    }
    private static void error(Map<String,Object> result, String... alternatives) {
        equal("ERROR", result.get("state"));
        require(!Collections.disjoint(codes(result), Arrays.asList(alternatives)), "unexpected codes=" + codes(result));
        equal("NOT_CHECKED", duplicate(result).get("status"));
    }
    private static void perQuestionTotals(Map<String,Object> result) {
        int rows = (Integer) result.get("responseRowCount");
        for (Map<String,Object> q : questions(result))
            equal(rows, (Integer) q.get("validCount") + (Integer) q.get("blankCount") + (Integer) q.get("invalidCount"));
    }
    private static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String col(int index) {
        StringBuilder value = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) value.insert(0, (char) ('A' + (n - 1) % 26));
        return value.toString();
    }
    private static String inline(String ref, String text) { return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + escape(text) + "</t></is></c>"; }
    private static String number(String ref, String text) { return "<c r=\"" + ref + "\"><v>" + escape(text) + "</v></c>"; }
    private static String headers() { return headers(SurveySummaryImportsResponses.QUESTION_LABELS); }
    private static String headers(List<String> labels) {
        StringBuilder xml = new StringBuilder("<row r=\"1\">").append(inline("A1", "测试记录标记"));
        for (int q = 0; q < labels.size(); q++) xml.append(inline(col(q + 1) + "1", labels.get(q)));
        return xml.append("</row>").toString();
    }
    private static String data(int row, String firstScore) { return data(row, "合成记录" + row, firstScore, ""); }
    private static String data(int row, String marker, String firstScore, String rowAttrs) {
        StringBuilder xml = new StringBuilder("<row r=\"").append(row).append("\" ").append(rowAttrs).append(">").append(inline("A" + row, marker));
        if (firstScore != null) xml.append(number("B" + row, firstScore));
        for (int q = 2; q <= 10; q++) xml.append(number(col(q) + row, "8"));
        return xml.append("</row>").toString();
    }
    private static String sheet(String rows) { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"" + MAIN + "\"><sheetData>" + rows + "</sheetData></worksheet>"; }
    private static Map<String,String> parts(String worksheet) {
        Map<String,String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/scores.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
        parts.put("_rels/.rels", "<Relationships xmlns=\"" + PACKAGE_REL + "\"><Relationship Id=\"root\" Type=\"" + OFFICE_REL + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        parts.put("xl/workbook.xml", "<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + OFFICE_REL + "\"><sheets><sheet name=\"" + SurveySummaryImportsResponses.SHEET_NAME + "\" sheetId=\"1\" r:id=\"scores\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"" + PACKAGE_REL + "\"><Relationship Id=\"scores\" Type=\"" + OFFICE_REL + "/worksheet\" Target=\"worksheets/scores.xml\"/></Relationships>");
        parts.put("xl/worksheets/scores.xml", worksheet);
        return parts;
    }
    private static void relationship(Map<String,String> parts, String id, String type, String target) {
        parts.compute("xl/_rels/workbook.xml.rels", (key, old) -> old.replace("</Relationships>", "<Relationship Id=\"" + id + "\" Type=\"" + OFFICE_REL + "/" + type + "\" Target=\"" + target + "\"/></Relationships>"));
    }
    private static byte[] zip(Map<String,String> parts) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String,String> item : parts.entrySet()) {
                ZipEntry entry = new ZipEntry(item.getKey()); entry.setTime(0);
                zip.putNextEntry(entry); zip.write(item.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static byte[] file(String rows) throws Exception { return zip(parts(sheet(headers() + rows))); }
    private static Map<String,Object> partPreview(String worksheet) throws Exception { return preview(zip(parts(worksheet))); }
    private static Map<String,Object> styledPreview(String body, String formats, String xfs) throws Exception {
        Map<String,String> parts = parts(sheet(headers() + body));
        relationship(parts, "styles", "styles", "styles.xml");
        parts.put("xl/styles.xml", "<styleSheet xmlns=\"" + MAIN + "\"><numFmts>" + formats + "</numFmts><cellXfs>" + xfs + "</cellXfs></styleSheet>");
        return preview(zip(parts));
    }
    private static void singleScoreProblem(String content, String type, String expected) throws Exception {
        String row = data(2, null).replace("</row>", "<c r=\"B2\" t=\"" + type + "\">" + content + "</c></row>");
        Map<String,Object> result = preview(file(row));
        equal("PREVIEW", result.get("state")); equal(1, questions(result).get(0).get("invalidCount"));
        require(codes(result).contains(expected), "expected " + expected + " got " + codes(result));
        equal(null, questions(result).get(0).get("averageText")); perQuestionTotals(result);
    }

    public static void main(String[] args) throws Exception {
        run("basic ten-question draft output", () -> {
            Map<String,Object> r = preview(file(data(2,"10") + data(3,"8")));
            equal("PREVIEW", r.get("state")); equal("RESPONSE_SUMMARY_PREVIEW", r.get("mode"));
            equal(false, r.get("canCommit")); equal(false, r.get("policyConfirmed")); equal(7L, r.get("projectId"));
            equal(2, r.get("responseRowCount")); equal(10, questions(r).size()); equal("q10", r.get("overallQuestionKey"));
            equal("9.00", questions(r).get(0).get("averageText")); equal("8.00", questions(r).get(9).get("averageText"));
            perQuestionTotals(r);
        });
        run("independent denominator zero blank invalid", () -> {
            Map<String,Object> r = preview(file(data(2,"0") + data(3,"10") + data(4,null) + data(5,"-1") + data(6,"11") + data(7,"not-a-score")));
            Map<String,Object> q=questions(r).get(0); equal(2,q.get("validCount")); equal(1,q.get("blankCount")); equal(3,q.get("invalidCount"));
            equal("10",q.get("sumText")); equal("5.00",q.get("averageText")); equal(6,questions(r).get(1).get("validCount")); perQuestionTotals(r);
        });
        run("exact sum and HALF_UP only for average", () -> {
            Map<String,Object> q=questions(preview(file(data(2,"1")+data(3,"2")+data(4,"2")))).get(0);
            equal("5",q.get("sumText")); equal("1.67",q.get("averageText"));
            q=questions(preview(file(data(2,"1.005")))).get(0); equal("1.005",q.get("sumText")); equal("1.01",q.get("averageText"));
        });
        run("all blank question has null average", () -> {
            Map<String,Object> r=preview(file(data(2,null))); equal(null,questions(r).get(0).get("averageText"));
            equal("0",questions(r).get(0).get("sumText")); require(codes(r).contains("NO_VALID_SCORES"),"missing empty question warning");
        });
        run("record marker with ten blanks remains candidate", () -> {
            Map<String,Object> r=preview(file("<row r=\"2\">"+inline("A2","SYNTHETIC_ID_ONLY")+"</row>"));
            equal(1,r.get("responseRowCount")); for(Map<String,Object> q:questions(r)){equal(1,q.get("blankCount"));equal(null,q.get("averageText"));}
        });
        run("formatting-only rows do not count", () -> {
            Map<String,Object> r=preview(file(data(2,"8")+"<row r=\"3\"><c r=\"A3\"/><c r=\"B3\"/></row>")); equal(1,r.get("responseRowCount"));
        });
        run("hidden and repeated rows remain candidates", () -> {
            Map<String,Object> r=preview(file(data(2,"marker","8","hidden=\"1\"")+data(3,"marker","8",""))); equal(2,r.get("responseRowCount"));
        });
        run("no response rows rejected", () -> error(preview(file("")),"NO_RESPONSE_ROWS"));
        run("authorization before even empty upload", () -> {
            security(() -> { SurveySummaryImportsResponses.preview(null,7,RULES,new SurveySummaryImportsResponses.PreviewContext(false,List.of(PROJECT),List.of(),false,true)); });
        });
        run("invisible project rejected before parse", () -> { security(() -> { SurveySummaryImportsResponses.preview(null,8,RULES,context(false,List.of())); }); });
        run("ambiguous visible project id rejected", () -> {
            security(() -> { SurveySummaryImportsResponses.preview(null,7,RULES,new SurveySummaryImportsResponses.PreviewContext(true,List.of(PROJECT,PROJECT),List.of(),false,true)); });
        });
        run("null context rejected", () -> { security(() -> { SurveySummaryImportsResponses.preview(null,7,RULES,null); }); });
        run("empty file rejected", () -> error(preview(new byte[0]),"FILE_EMPTY"));
        run("compressed upload limit", () -> error(preview(new byte[SurveySummaryImportsResponses.MAX_BYTES+1]),"FILE_TOO_LARGE"));
        run("non ZIP upload rejected", () -> error(preview("NOT_XLSX".getBytes(StandardCharsets.UTF_8)),"INVALID_XLSX","XLSX_STRUCTURE"));
        run("formula cache never used", () -> singleScoreProblem("<f>9</f><v>9</v>","n","FORMULA_SCORE"));
        run("formula without cache still invalid", () -> singleScoreProblem("<f>9</f>","n","FORMULA_SCORE"));
        run("boolean scoring cell rejected", () -> singleScoreProblem("<v>1</v>","b","CELL_ERROR"));
        run("Excel error scoring cell rejected", () -> singleScoreProblem("<v>#DIV/0!</v>","e","CELL_ERROR"));
        run("ISO date cell rejected", () -> singleScoreProblem("<v>2026-09-21</v>","d","DATE_SCORE"));
        run("text percent rejected", () -> singleScoreProblem("<is><t>90%</t></is>","inlineStr","PERCENT_SCORE"));
        run("NaN rejected", () -> singleScoreProblem("<v>NaN</v>","n","INVALID_SCORE"));
        run("localized decimal rejected", () -> singleScoreProblem("<is><t>9,5</t></is>","inlineStr","INVALID_SCORE"));
        run("hostile exponent rejected without arithmetic", () -> singleScoreProblem("<v>1E-2147483647</v>","n","INVALID_SCORE"));
        run("supported-scale limit enforced", () -> singleScoreProblem("<v>1E-13</v>","n","SCORE_PRECISION"));
        run("scientific notation within scale accepted", () -> equal("9.50",questions(preview(file(data(2,"9.5E0")))).get(0).get("averageText")));
        run("style percentage rejected", () -> {
            String row=data(2,"0.9").replace("<c r=\"B2\">","<c r=\"B2\" s=\"1\">");
            Map<String,Object> r=styledPreview(row,"","<xf numFmtId=\"0\"/><xf numFmtId=\"9\"/>"); require(codes(r).contains("PERCENT_SCORE"),"percentage style accepted");
        });
        run("style date rejected", () -> {
            String row=data(2,"9").replace("<c r=\"B2\">","<c r=\"B2\" s=\"1\">");
            Map<String,Object> r=styledPreview(row,"","<xf numFmtId=\"0\"/><xf numFmtId=\"14\"/>"); require(codes(r).contains("DATE_SCORE"),"date style accepted");
        });
        run("custom quoted literal not treated as percent", () -> {
            String row=data(2,"9").replace("<c r=\"B2\">","<c r=\"B2\" s=\"1\">");
            Map<String,Object> r=styledPreview(row,"<numFmt numFmtId=\"164\" formatCode=\"0&amp;quot;%&amp;quot;\"/>".replace("&amp;quot;","&quot;"),"<xf numFmtId=\"0\"/><xf numFmtId=\"164\"/>");
            equal(1,questions(r).get(0).get("validCount"));
        });
        run("custom color format not treated as date", () -> {
            String row=data(2,"9").replace("<c r=\"B2\">","<c r=\"B2\" s=\"1\">");
            Map<String,Object> r=styledPreview(row,"<numFmt numFmtId=\"164\" formatCode=\"[Red]0.00\"/>","<xf numFmtId=\"0\"/><xf numFmtId=\"164\"/>");
            equal(1,questions(r).get(0).get("validCount")); equal("9.00",questions(r).get(0).get("averageText"));
        });
        run("elapsed time style remains date", () -> {
            String row=data(2,"9").replace("<c r=\"B2\">","<c r=\"B2\" s=\"1\">");
            Map<String,Object> r=styledPreview(row,"<numFmt numFmtId=\"164\" formatCode=\"[h]:mm\"/>","<xf numFmtId=\"0\"/><xf numFmtId=\"164\"/>");
            require(codes(r).contains("DATE_SCORE"),"elapsed time style accepted");
        });
        run("question columns may be reordered", () -> {
            List<String> labels=new ArrayList<>(SurveySummaryImportsResponses.QUESTION_LABELS); Collections.swap(labels,0,1);
            Map<String,Object> r=partPreview(sheet(headers(labels)+data(2,"3").replace("<c r=\"C2\"><v>8</v>","<c r=\"C2\"><v>9</v>")));
            equal("9.00",questions(r).get(0).get("averageText")); equal("3.00",questions(r).get(1).get("averageText"));
        });
        run("duplicate header rejected", () -> {
            String header=headers().replace("</row>",inline("L1",SurveySummaryImportsResponses.QUESTION_LABELS.get(0))+"</row>");
            error(partPreview(sheet(header+data(2,"9"))),"DUPLICATE_HEADER");
        });
        run("missing expected header rejected", () -> error(partPreview(sheet(headers().replace(SurveySummaryImportsResponses.QUESTION_LABELS.get(0),"unrelated")+data(2,"9"))),"HEADER_MISMATCH"));
        run("duplicate cell coordinate rejected", () -> error(partPreview(sheet(headers()+data(2,"9").replace("</row>",number("B2","1")+"</row>"))),"XLSX_STRUCTURE"));
        run("duplicate row coordinate rejected", () -> error(preview(file(data(2,"9")+data(2,"8"))),"XLSX_STRUCTURE"));
        run("cell row disagrees with row coordinate rejected", () -> error(partPreview(sheet(headers()+data(2,"9").replace("r=\"B2\"","r=\"B3\""))),"XLSX_STRUCTURE"));
        run("column 65 rejected", () -> error(partPreview(sheet(headers()+data(2,"9").replace("</row>",inline("BM2","extra")+"</row>"))),"XLSX_LIMIT","XLSX_STRUCTURE"));
        run("merged scoring worksheet rejected", () -> error(partPreview(sheet(headers()+data(2,"9")).replace("</worksheet>","<mergeCells><mergeCell ref=\"B2:C2\"/></mergeCells></worksheet>")),"XLSX_STRUCTURE"));
        run("oversized cell rejected", () -> error(preview(file(data(2,"9").replace("合成记录2","x".repeat(4097)))),"XLSX_LIMIT"));
        run("row number beyond configured limit rejected", () -> error(preview(file(data(10002,"9"))),"XLSX_LIMIT","XLSX_STRUCTURE"));
        run("exactly 10000 candidate rows accepted", () -> {
            StringBuilder rows=new StringBuilder(); for(int row=2;row<=10001;row++) rows.append("<row r=\"").append(row).append("\">").append(inline("A"+row,"m")).append("</row>");
            Map<String,Object> r=preview(file(rows.toString())); equal("PREVIEW",r.get("state")); equal(10000,r.get("responseRowCount")); perQuestionTotals(r);
        });
        run("shared strings and phonetic text", () -> {
            Map<String,String> p=parts(sheet(headers()+data(2,"9").replace(number("B2","9"),"<c r=\"B2\" t=\"s\"><v>0</v></c>")));
            relationship(p,"strings","sharedStrings","sharedStrings.xml"); p.put("xl/sharedStrings.xml","<sst xmlns=\""+MAIN+"\"><si><r><t>9</t></r><rPh sb=\"0\" eb=\"1\"><t>PRIVATE_PHONETIC</t></rPh></si></sst>");
            Map<String,Object> r=preview(zip(p)); equal("9.00",questions(r).get(0).get("averageText")); require(!r.toString().contains("PRIVATE_PHONETIC"),"phonetic leaked");
        });
        run("invalid shared string index rejected", () -> {
            Map<String,String> p=parts(sheet(headers()+data(2,"9").replace(number("B2","9"),"<c r=\"B2\" t=\"s\"><v>1</v></c>")));
            relationship(p,"strings","sharedStrings","sharedStrings.xml"); p.put("xl/sharedStrings.xml","<sst xmlns=\""+MAIN+"\"><si><t>9</t></si></sst>");
            error(preview(zip(p)),"XLSX_LIMIT","XLSX_STRUCTURE");
        });
        run("shared string count limit", () -> {
            Map<String,String> p=parts(sheet(headers()+data(2,"9"))); relationship(p,"strings","sharedStrings","sharedStrings.xml");
            p.put("xl/sharedStrings.xml","<sst xmlns=\""+MAIN+"\">"+"<si><t>x</t></si>".repeat(50001)+"</sst>"); error(preview(zip(p)),"XLSX_LIMIT");
        });
        run("missing named worksheet rejected", () -> {
            Map<String,String> p=parts(sheet(headers()+data(2,"9"))); p.compute("xl/workbook.xml",(k,v)->v.replace(SurveySummaryImportsResponses.SHEET_NAME,"Other")); error(preview(zip(p)),"SHEET_MISSING");
        });
        run("duplicate worksheet name rejected", () -> {
            Map<String,String> p=parts(sheet(headers()+data(2,"9"))); p.compute("xl/workbook.xml",(k,v)->v.replace("</sheets>","<sheet name=\""+SurveySummaryImportsResponses.SHEET_NAME+"\" sheetId=\"2\" r:id=\"scores\"/></sheets>")); error(preview(zip(p)),"XLSX_STRUCTURE");
        });
        run("duplicate relationship id rejected", () -> {
            Map<String,String> p=parts(sheet(headers()+data(2,"9"))); relationship(p,"scores","worksheet","worksheets/scores.xml"); error(preview(zip(p)),"XLSX_STRUCTURE");
        });
        run("external workbook relationship rejected", () -> {
            Map<String,String> p=parts(sheet(headers()+data(2,"9"))); p.compute("xl/_rels/workbook.xml.rels",(k,v)->v.replace("Target=\"worksheets/scores.xml\"","Target=\"https://example.invalid/private\" TargetMode=\"External\"")); error(preview(zip(p)),"XLSX_STRUCTURE");
        });
        run("relationship traversal rejected", () -> {
            Map<String,String> p=parts(sheet(headers()+data(2,"9"))); p.compute("xl/_rels/workbook.xml.rels",(k,v)->v.replace("worksheets/scores.xml","../private.xml")); error(preview(zip(p)),"XLSX_STRUCTURE");
        });
        run("ZIP traversal rejected", () -> {Map<String,String> p=parts(sheet(headers()+data(2,"9")));p.put("../private.txt","X");error(preview(zip(p)),"XLSX_STRUCTURE");});
        run("duplicate ZIP entry rejected", () -> {
            Map<String,String> p=parts(sheet(headers()+data(2,"9")));p.put("unused/aaa.xml","one");p.put("unused/bbb.xml","two");
            byte[] bytes=zip(p), from="unused/bbb.xml".getBytes(StandardCharsets.UTF_8), to="unused/aaa.xml".getBytes(StandardCharsets.UTF_8);
            int replaced=0;
            for(int i=0;i<=bytes.length-from.length;i++) {
                boolean match=true;for(int j=0;j<from.length;j++)if(bytes[i+j]!=from[j]){match=false;break;}
                if(match){System.arraycopy(to,0,bytes,i,to.length);replaced++;i+=from.length-1;}
            }
            equal(2,replaced);error(preview(bytes),"XLSX_STRUCTURE");
        });
        run("too many ZIP entries rejected", () -> {Map<String,String> p=parts(sheet(headers()+data(2,"9")));for(int i=0;i<256;i++)p.put("unused/"+i,"x");error(preview(zip(p)),"XLSX_LIMIT");});
        run("unrelated ZIP part consumes expanded budget", () -> {Map<String,String> p=parts(sheet(headers()+data(2,"9")));p.put("unused/large.xml","x".repeat(SurveySummaryImportsResponses.MAX_EXPANDED_BYTES));error(preview(zip(p)),"XLSX_LIMIT");});
        run("DOCTYPE and external entity rejected", () -> {
            String xml=sheet(headers()+data(2,"9")).replace("?><worksheet","?><!DOCTYPE worksheet [<!ENTITY leak SYSTEM 'file:///not-accessed-by-test'>]><worksheet").replace("<v>9</v>","<v>&leak;</v>");
            error(partPreview(xml),"XLSX_STRUCTURE","INVALID_XLSX");
        });
        run("XML depth budget enforced", () -> error(partPreview(sheet(headers()+data(2,"9")).replace("<sheetData>","<wrap>".repeat(33)+"<sheetData>").replace("</sheetData>","</sheetData>"+"</wrap>".repeat(33))),"XLSX_LIMIT","XLSX_STRUCTURE"));
        run("private identity and invalid values never echoed", () -> {
            String secret="SYNTHETIC_PRIVATE_420027";
            Map<String,Object> r=preview(file(data(2,secret,secret,""))); require(!r.toString().contains(secret),"response echoed private value");
            require(!r.containsKey("rows")&&!r.containsKey("raw")&&!r.containsKey("rawCells"),"raw individual responses returned");
        });
        run("history unavailable not reported as checked", () -> equal("NOT_CHECKED",duplicate(preview(file(data(2,"9")))).get("status")));
        run("exact file duplicate detection", () -> {
            byte[] file=file(data(2,"9"));Map<String,Object> first=preview(file);
            var old=new SurveySummaryImportsResponses.PreviousImport(12,7,(String)first.get("fileFingerprint"),(String)first.get("scoreFingerprint"));
            Map<String,Object> r=SurveySummaryImportsResponses.preview(file,7,RULES,context(true,List.of(old)));equal("EXACT_FILE",duplicate(r).get("status"));equal(List.of(12L),duplicate(r).get("recordIds"));
        });
        run("same scores changed identity only candidate", () -> {
            Map<String,Object> first=preview(file(data(2,"A","9","")));
            var old=new SurveySummaryImportsResponses.PreviousImport(12,7,(String)first.get("fileFingerprint"),(String)first.get("scoreFingerprint"));
            Map<String,Object> r=SurveySummaryImportsResponses.preview(file(data(2,"B","9","")),7,RULES,context(true,List.of(old)));equal("SAME_SCORES_CANDIDATE",duplicate(r).get("status"));
        });
        run("unrelated project history not exposed", () -> {
            byte[] file=file(data(2,"9"));Map<String,Object> first=preview(file);
            var old=new SurveySummaryImportsResponses.PreviousImport(98765,8,(String)first.get("fileFingerprint"),(String)first.get("scoreFingerprint"));
            Map<String,Object> r=SurveySummaryImportsResponses.preview(file,7,RULES,context(true,List.of(old)));equal("NONE",duplicate(r).get("status"));equal(List.of(),duplicate(r).get("recordIds"));require(!r.toString().contains("98765"),"hidden history id leaked");
        });
        run("changed score not duplicate", () -> {
            Map<String,Object> first=preview(file(data(2,"9")));var old=new SurveySummaryImportsResponses.PreviousImport(12,7,(String)first.get("fileFingerprint"),(String)first.get("scoreFingerprint"));
            Map<String,Object> r=SurveySummaryImportsResponses.preview(file(data(2,"8")),7,RULES,context(true,List.of(old)));equal("NONE",duplicate(r).get("status"));
        });
        run("invalid scoring disables score-only duplicate candidate", () -> {
            byte[] original=file(data(2,"invalid-one"));Map<String,Object> first=preview(original);equal("",first.get("scoreFingerprint"));
            var old=new SurveySummaryImportsResponses.PreviousImport(12,7,(String)first.get("fileFingerprint"),(String)first.get("scoreFingerprint"));
            Map<String,Object> r=SurveySummaryImportsResponses.preview(file(data(2,"invalid-two")),7,RULES,context(true,List.of(old)));
            equal("",r.get("scoreFingerprint"));equal("NONE",duplicate(r).get("status"));
            r=SurveySummaryImportsResponses.preview(original,7,RULES,context(true,List.of(old)));equal("EXACT_FILE",duplicate(r).get("status"));
        });
        run("diagnostic truncation preserves full counts and sums", () -> {
            StringBuilder rows=new StringBuilder();for(int i=2;i<=2002;i++)rows.append(data(i,"invalid"));
            Map<String,Object> r=preview(file(rows.toString())); equal("PREVIEW",r.get("state"));equal(2001,r.get("responseRowCount"));
            equal(2001,questions(r).get(0).get("invalidCount"));equal(0,questions(r).get(0).get("validCount"));
            equal(2001,questions(r).get(1).get("validCount"));equal("16008",questions(r).get(1).get("sumText"));equal("8.00",questions(r).get(1).get("averageText"));
            require(((List<?>)r.get("issues")).size()<=2000,"diagnostic list exceeded cap");equal(true,r.get("issuesTruncated"));
            equal(2002,((Number)r.get("issueCount")).intValue());perQuestionTotals(r);
        });
        run("server draft range honored", () -> {
            Map<String,Object> r=SurveySummaryImportsResponses.preview(file(data(2,"0")),7,new SurveySummaryImportsResponses.DraftRules(BigDecimal.ONE,BigDecimal.TEN,3),context(false,List.of()));
            equal(1,questions(r).get(0).get("invalidCount"));equal("8.000",questions(r).get(1).get("averageText"));equal(false,r.get("policyConfirmed"));
        });
        run("foreign-namespace row/cell elements rejected", () -> {
            String xml=sheet(headers()+data(2,"9")).replace("<row ","<evil:row xmlns:evil=\"urn:invalid\" ").replace("</row>","</evil:row>");
            error(partPreview(xml),"XLSX_STRUCTURE","HEADER_MISMATCH","NO_RESPONSE_ROWS");
        });
        run("worksheet root and sheetData are required", () -> error(partPreview("<arbitrary xmlns=\""+MAIN+"\">"+headers()+data(2,"9")+"</arbitrary>"),"XLSX_STRUCTURE","HEADER_MISMATCH"));
        run("duplicate value elements rejected", () -> error(partPreview(sheet(headers()+data(2,"1").replace("<c r=\"B2\"><v>1</v>","<c r=\"B2\"><v>1</v><v>0</v>"))),"XLSX_STRUCTURE"));
        run("nested element in numeric value rejected", () -> error(partPreview(sheet(headers()+data(2,"1").replace("<c r=\"B2\"><v>1</v>","<c r=\"B2\"><v>1<unknown/>0</v>"))),"XLSX_STRUCTURE"));
        run("foreign-namespace workbook root rejected", () -> {
            Map<String,String> p=parts(sheet(headers()+data(2,"9")));p.compute("xl/workbook.xml",(k,v)->v.replace("xmlns=\""+MAIN+"\"","xmlns=\"urn:invalid\""));error(preview(zip(p)),"XLSX_STRUCTURE","SHEET_MISSING");
        });
        run("legacy and null-checkpoint APIs preserve initial interruption", () -> {
            byte[] input = file(data(2,"9"));
            try {
                Thread.currentThread().interrupt();
                cancelled(() -> preview(input));
                require(Thread.currentThread().isInterrupted(), "legacy preview cleared interruption");
                cancelled(() -> SurveySummaryImportsResponses.preview(input,7,RULES,context(false,List.of()),null));
                require(Thread.currentThread().isInterrupted(), "null-checkpoint preview cleared interruption");
            } finally { Thread.interrupted(); }
        });
        run("checkpoint cancellation propagates unchanged at multiple processing depths", () -> {
            StringBuilder rows = new StringBuilder();
            for (int row = 2; row <= 51; row++) rows.append(data(row,"9"));
            byte[] input = file(rows.toString());
            for (int stopAt : new int[] {10, 250, 1200}) {
                AtomicInteger calls = new AtomicInteger();
                CancellationException marker = new CancellationException("synthetic cancellation " + stopAt);
                CancellationException caught = cancelled(() -> SurveySummaryImportsResponses.preview(input,7,RULES,context(false,List.of()), () -> {
                    if (calls.incrementAndGet() == stopAt) throw marker;
                }));
                require(caught == marker, "callback cancellation was replaced or swallowed");
                equal(stopAt, calls.get());
            }
        });
        run("checkpoint failure leaves no state on the reused thread", () -> {
            byte[] input = file(data(2,"9"));
            AtomicInteger calls = new AtomicInteger();
            cancelled(() -> SurveySummaryImportsResponses.preview(input,7,RULES,context(false,List.of()), () -> {
                calls.incrementAndGet(); throw new CancellationException("synthetic expired deadline");
            }));
            Map<String,Object> oldApi = preview(input);
            Map<String,Object> newApi = SurveySummaryImportsResponses.preview(input,7,RULES,context(false,List.of()), () -> {});
            equal("PREVIEW", oldApi.get("state")); equal(oldApi, newApi); equal(1, calls.get());
            error(preview("broken synthetic zip".getBytes(StandardCharsets.UTF_8)), "INVALID_XLSX");
        });
        run("checkpoint-triggered interruption cancels even when callback returns", () -> {
            byte[] input = file(data(2,"9"));
            AtomicInteger calls = new AtomicInteger();
            try {
                cancelled(() -> SurveySummaryImportsResponses.preview(input,7,RULES,context(false,List.of()), () -> {
                    if (calls.incrementAndGet() == 20) Thread.currentThread().interrupt();
                }));
                equal(20, calls.get());
                require(Thread.currentThread().isInterrupted(), "callback interruption was cleared");
            } finally { Thread.interrupted(); }
            equal("PREVIEW", preview(input).get("state"));
        });
        System.out.println("M07 RESPONSE CHECKS: passed="+passed+" failed="+failed);
        if(failed!=0) throw new AssertionError(failed+" checks failed");
    }
}
