package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import java.io.*;
import static com.training.ManagementSettlementAccounting.*;

/** Contract fixtures only; real M05 audit preview/confirm is covered by the separate integration runner. */
public final class M06SettlementCodingContractTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void eq(Object a,Object b,String label){check(Objects.equals(a,b),label+" actual="+a+" expected="+b);}
    static Map<String,Object> copy(Map<String,Object> value){return obj(Json.parse(Json.write(value)));}
    static Query teaching(){return M06SettlementAccountingTest.SEP;}
    static Query payment(){return M06SettlementAccountingTest.query("2026-09-01","2026-09-30",DateBasis.PAYMENT);}
    static Map<String,Object> raw(){Map<String,Object>s=M06SettlementAccountingTest.fixture();for(Map<String,Object> e:rows(s.get("accrual_entries"))){e.put("teacher_code",null);e.put("course_code",null);e.put("course_id",null);}return s;}
    static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new RuntimeException(e);}}
    static Map<String,Object> projected(Map<String,Object> source,long scope){
        Map<String,Object>s=copy(source);Map<String,Map<String,Object>> versions=new TreeMap<>();
        for(Map<String,Object> e:rows(s.get("accrual_entries"))){String teacher="T00"+new java.math.BigDecimal(e.get("teacher_id").toString()).longValueExact(),course="000"+new java.math.BigDecimal(e.get("teacher_id").toString()).longValueExact();
            e.put("teacher_code",teacher);e.put("course_code",course);e.put("course_id",null);
            versions.putIfAbsent(e.get("chain_code").toString(),map("chain_code",e.get("chain_code"),"catalog_scope_id",scope,"teacher_id",e.get("teacher_id"),"teacher_code",teacher,"course_code",course,"version",1,"record_code","CODING-AUDIT-1","catalog_revision",1L,"confirmed_at","2026-09-01T00:00:00Z","catalog_version","CATALOG-1","catalog_digest","a".repeat(64),"evidence_code","PRIVATE-CODING-EVIDENCE"));}
        s.put("coding_versions",new ArrayList<>(versions.values()));s.put("coding_coverage","AUDITED_CHAIN_PROJECTION");s.put("source_version",hash(s));return s;
    }
    static void bad(Consumer<Map<String,Object>> mutate,String label){Map<String,Object>s=projected(raw(),101);mutate.accept(s);try{calculate(List.of(s),teaching(),true);throw new AssertionError("Accepted "+label);}catch(IllegalArgumentException expected){checks++;}}
    static Map<String,Object> other(Map<String,Object> original){
        Map<String,Object> s=copy(original);s.put("project_id",11L);s.put("organization_code","002");
        for(Map<String,Object> e:rows(s.get("accrual_entries"))){e.put("entry_code","X-"+e.get("entry_code"));e.put("chain_code","X-"+e.get("chain_code"));if(e.get("previous_entry_code")!=null)e.put("previous_entry_code","X-"+e.get("previous_entry_code"));}
        for(Map<String,Object> p:rows(s.get("payment_entries"))){p.put("entry_code","X-"+p.get("entry_code"));p.put("chain_code","X-"+p.get("chain_code"));p.put("accrual_entry_codes",((List<?>)p.get("accrual_entry_codes")).stream().map(x->"X-"+x).toList());}
        for(Map<String,Object> h:rows(s.get("chain_heads"))){h.put("chain_code","X-"+h.get("chain_code"));h.put("current_entry_code","X-"+h.get("current_entry_code"));}
        for(Map<String,Object> c:rows(s.get("coding_versions")))c.put("chain_code","X-"+c.get("chain_code"));s.put("source_version",hash(s));return s;
    }
    static void rejectsPair(Map<String,Object>a,Map<String,Object>b,Query q,String label){try{calculate(List.of(a,b),q,true);throw new AssertionError("Accepted "+label);}catch(IllegalArgumentException expected){checks++;}}
    static void inspect(byte[] bytes,String expectedAmount)throws Exception{
        int sheets=0;boolean amount=false,codes=false;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){ZipEntry entry;while((entry=zip.getNextEntry())!=null){String xml=new String(zip.readAllBytes(),StandardCharsets.UTF_8);check(!xml.contains("PRIVATE-")&&!xml.contains("catalog_scope_id")&&!xml.contains("coding_versions"),"audit metadata not leaked to file");check(!xml.contains("<f>")&&!xml.contains("TargetMode=\"External\""),"no formulas or external links");if(entry.getName().startsWith("xl/worksheets/")){sheets++;amount|=xml.contains(expectedAmount);codes|=xml.contains("0001")&&xml.contains("T001");}}}
        eq(sheets,5,"five populated-compatible sheets");check(amount&&codes,"real amount and codes in nonempty workbook");
    }
    public static void main(String[] args)throws Exception {
        Map<String,Object> raw=raw();String untouched=Json.write(raw);Report before=calculate(List.of(raw),teaching(),true);check(!before.exportable(),"uncoded source still blocks export");check(!rows(before.toMap().get("code_gaps")).isEmpty(),"uncoded gaps visible");
        Map<String,Object> s=projected(raw,101);Report report=calculate(List.of(s),teaching(),true);Map<String,Object> view=report.toMap();check(report.exportable(),"formal codes permit nonempty export without numeric course id");eq(obj(view.get("totals")).get("amount"),"220.00","amount unchanged");eq(Json.write(raw),untouched,"projection does not mutate ledger fixture");
        for(Map<String,Object> d:rows(view.get("details"))){eq(d.get("course_id"),null,"never invent numeric course identity");check(d.get("teacher_code")!=null&&d.get("course_code")!=null,"all selected corrections coded");}
        Report cash=calculate(List.of(s),payment(),true);check(cash.exportable(),"cash ref resolves projected codes");eq(obj(cash.toMap().get("totals")).get("amount"),"-20.00","cash net unchanged");
        for(Map<String,Object> d:rows(cash.toMap().get("details"))){eq(d.get("teacher_code"),"T001","payment/refund teacher from source ref");eq(d.get("course_code"),"0001","payment/refund course from source ref");eq(obj(d.get("hours")).get("ACTUAL"),null,"payments never duplicate hours");}
        Report oldMonth=calculate(List.of(s),M06SettlementAccountingTest.query("2026-08-01","2026-08-31",DateBasis.TEACHING),true);check(oldMonth.exportable(),"original, adjustment and reversal all coded");eq(obj(oldMonth.toMap().get("totals")).get("amount"),"0.00","old month cancellation unchanged");
        Map<String,Object> noMeta=copy(s);noMeta.remove("coding_versions");noMeta.remove("coding_coverage");check(calculate(List.of(noMeta),teaching(),true).exportable(),"old DTO compatibility retained");
        Map<String,Object> sameScope=other(s);Report both=calculate(List.of(s,sameScope),teaching(),true);eq(obj(both.toMap().get("totals")).get("amount"),"440.00","same trusted catalog code across projects is not duplicated identity");
        Map<String,Object> otherScope=other(s);for(Map<String,Object> c:rows(otherScope.get("coding_versions")))c.put("catalog_scope_id",202L);rejectsPair(s,otherScope,teaching(),"same course code across distinct scopes");rejectsPair(s,otherScope,payment(),"cash cannot evade cross scope code conflict");rejectsPair(s,otherScope,M06SettlementAccountingTest.query("2025-01-01","2025-01-31",DateBasis.TEACHING),"date filter cannot hide conflicting audit identities");
        bad(x->rows(x.get("coding_versions")).get(0).put("catalog_scope_id",null),"missing real catalog scope");bad(x->rows(x.get("coding_versions")).get(0).put("catalog_scope_id",0),"invalid scope");bad(x->rows(x.get("coding_versions")).get(0).put("catalog_scope_id",1.5),"fractional scope");
        bad(x->rows(x.get("coding_versions")).get(0).put("chain_code","UNKNOWN"),"unrelated coding row");
        bad(x->{@SuppressWarnings("unchecked")List<Object> l=(List<Object>)x.get("coding_versions");l.add(l.get(0));},"duplicate coding revision row");
        bad(x->rows(x.get("coding_versions")).get(0).put("teacher_id",99),"wrong teacher binding");bad(x->rows(x.get("coding_versions")).get(0).put("teacher_code","WRONG"),"wrong teacher code");bad(x->rows(x.get("coding_versions")).get(0).put("course_code","WRONG"),"wrong course code");
        bad(x->rows(x.get("accrual_entries")).get(0).put("course_code",null),"omitted original entry outside selected month");bad(x->rows(x.get("accrual_entries")).get(2).put("teacher_code",null),"omitted reversal entry");bad(x->rows(x.get("accrual_entries")).get(4).put("course_code","OTHER"),"changed correction code");
        Map<String,Object> conflict=other(s);for(Map<String,Object> e:rows(conflict.get("accrual_entries")))e.put("teacher_id",((Number)e.get("teacher_id")).longValue()+10);for(Map<String,Object> h:rows(conflict.get("chain_heads")))h.put("teacher_id",((Number)h.get("teacher_id")).longValue()+10);for(Map<String,Object> c:rows(conflict.get("coding_versions")))c.put("teacher_id",((Number)c.get("teacher_id")).longValue()+10);rejectsPair(s,conflict,teaching(),"same teacher code cannot identify another real teacher");
        Map<String,Object> version=copy(s);rows(version.get("coding_versions")).get(0).put("version",2);rows(version.get("coding_versions")).get(0).put("evidence_code","PRIVATE-RECONFIRMED");version.put("source_version",hash(version));check(!calculate(List.of(version),teaching(),true).toMap().get("source_versions").equals(view.get("source_versions")),"coding-only source revision propagates through report version input");
        byte[] teachingFile=ManagementSettlementWorkbook.export(report,hash(view)),cashFile=ManagementSettlementWorkbook.export(cash,hash(cash.toMap()));inspect(teachingFile,"220.00");inspect(cashFile,"-20.00");
        if(args.length==1){Path output=Path.of(args[0]);Files.createDirectories(output);Files.write(output.resolve("coded-teaching-synthetic.xlsx"),teachingFile);Files.write(output.resolve("coded-payment-synthetic.xlsx"),cashFile);Files.writeString(output.resolve("contract-evidence.json"),Json.write(map("checks",checks,"teaching_amount","220.00","payment_amount","-20.00","course_id",null,"source_before",raw.get("source_version"),"source_after",s.get("source_version"),"source_kind","SYNTHETIC_CONTRACT_FIXTURE")));}
        System.out.println("M06 coding contract compatibility checks passed: "+checks);
    }
}
