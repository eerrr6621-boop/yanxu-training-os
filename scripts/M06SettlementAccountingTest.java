package com.training;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import java.io.*;
import javax.xml.parsers.DocumentBuilderFactory;
import static com.training.ManagementSettlementAccounting.*;

/** Synthetic frozen ledgers only: no DB, credentials, personal material or bank actions. */
public final class M06SettlementAccountingTest {
    static int checks;
    static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    static void eq(Object a,Object b,String s){check(Objects.equals(a,b),s+" actual="+a+" expected="+b);}
    static Map<String,Object> cloneMap(Map<String,Object>m){return obj(Json.parse(Json.write(m)));}
    static Map<String,Object> hours(String v){return map("ESTIMATED",v,"PLANNED",v,"ACTUAL",v,"PAYABLE",v);}
    static Map<String,Object> entry(String code,String chain,String previous,String kind,String date,long teacher,String amount,String h,String group,String activity){return map("entry_code",code,"chain_code",chain,"previous_entry_code",previous,"kind",kind,"activity",activity,"source_record_id",1L,"source_code","SOURCE-1","service_date",date,"teacher_id",teacher,"teacher_code","T00"+teacher,"system_teacher_code","PRIVATE-SYSTEM-ID","course_id",teacher+100,"course_code","C00"+teacher,"amount",amount,"hours",hours(h),"hours_before",hours("0"),"hours_after",hours(h),"currency","CNY","data_mode","CONFIGURED","policy_version","POLICY-1","rate_version","RATE-1","evidence_code","PRIVATE-EVIDENCE","confirmed_at","2026-01-01T00:00:00Z","correction_group_code",group,"private_name","PRIVATE-NAME");}
    static Map<String,Object> head(String chain,String current,String date,String amount,String paid,String activity,long teacher){return map("chain_code",chain,"current_entry_code",current,"current_service_date",date,"current_amount",amount,"paid_amount",paid,"balance",new BigDecimal(amount).subtract(new BigDecimal(paid)).toPlainString(),"activity",activity,"teacher_id",teacher);}
    static Map<String,Object> payment(String code,String ref,String kind,String day,String amount){return map("entry_code",code,"chain_code","A","accrual_entry_codes",List.of(ref),"kind",kind,"payment_date",day,"amount",amount,"currency","CNY","evidence_code","PRIVATE-PAYMENT-EVIDENCE","recorded_at",day+"T10:00:00Z","historical",false);}
    static Map<String,Object> fixture(){
        List<Map<String,Object>> e=new ArrayList<>(),p=new ArrayList<>(),heads=new ArrayList<>();
        e.add(entry("A1","A",null,"CONFIRMED","2026-08-10",1,"100.00","1.5",null,"TEACHING"));
        e.add(entry("A2","A","A1","ADJUSTMENT","2026-08-10",1,"20.00","0.5",null,"TEACHING"));
        e.add(entry("A3","A","A2","REVERSAL","2026-08-10",1,"-120.00","-2", "CROSS-1","TEACHING"));
        e.add(entry("A4","A","A3","REBOOK","2026-09-05",1,"150.00","3", "CROSS-1","TEACHING"));
        e.add(entry("A5","A","A4","ADJUSTMENT","2026-09-05",1,"-70.00","-1",null,"TEACHING"));
        e.get(1).put("hours_before",hours("1.5"));e.get(1).put("hours_after",hours("2"));
        e.get(2).put("hours_before",hours("2"));e.get(2).put("hours_after",hours("0"));
        e.get(4).put("hours_before",hours("3"));e.get(4).put("hours_after",hours("2"));
        String[] when={"2026-08-11T00:00:00Z","2026-08-21T00:00:00Z","2026-09-06T00:00:00Z","2026-09-06T00:00:00Z","2026-09-11T00:00:00Z"};for(int i=0;i<when.length;i++)e.get(i).put("confirmed_at",when[i]);
        p.add(payment("P1","A1","PAYMENT","2026-08-20","100.00"));p.add(payment("P2","A4","PAYMENT","2026-09-10","50.00"));p.add(payment("P3","A5","REFUND","2026-09-12","-70.00"));
        heads.add(head("A","A5","2026-09-05","80.00","80.00","TEACHING",1));
        String[] amounts={"60.00","30.00","40.00","10.00"},hs={"2","1",null,"0"},activities={"TEACHING","TEACHING","SOLO_DEVELOPMENT","LEGACY"};
        for(int i=0;i<4;i++){String chain="B"+i;e.add(entry(chain+"-1",chain,null,i==3?"LEGACY_OPENING":"CONFIRMED","2026-09-06",i+2,amounts[i],hs[i],null,activities[i]));heads.add(head(chain,chain+"-1","2026-09-06",amounts[i],"0.00",activities[i],i+2));}
        return map("schema_version","M05-FINANCIAL-1","project_id",10L,"organization_code","001","source_version","a".repeat(64),"accrual_entries",e,"payment_entries",p,"chain_heads",heads);
    }
    static Query query(String start,String end,DateBasis basis){return new Query(LocalDate.parse(start),LocalDate.parse(end),basis,basis==DateBasis.PAYMENT?RankMetric.FEE:RankMetric.HOURS,"ACTUAL");}
    static final Query SEP=query("2026-09-01","2026-09-30",DateBasis.TEACHING);
    static Report calc(Map<String,Object> f,Query q){return calculate(List.of(f),q,true);}
    static Map<String,Object> total(Report r){return obj(r.toMap().get("totals"));}
    static void rejects(Consumer<Map<String,Object>>change,String label){Map<String,Object>f=fixture();change.accept(f);try{calc(f,SEP);throw new AssertionError("Accepted "+label);}catch(IllegalArgumentException good){checks++;}}
    static Map<String,Object> e(Map<String,Object>f,int n){return rows(f.get("accrual_entries")).get(n);}
    static Map<String,Object> h(Map<String,Object>f,int n){return rows(f.get("chain_heads")).get(n);}
    static Map<String,Object> p(Map<String,Object>f,int n){return rows(f.get("payment_entries")).get(n);}
    public static void main(String[]args)throws Exception{
        Report sep=calc(fixture(),SEP);Map<String,Object>v=sep.toMap();
        eq(total(sep).get("amount"),"220.00","September accrual sums corrected net plus development and legacy");
        eq(total(sep).get("positive_amount"),"290.00","positive postings");eq(total(sep).get("negative_amount"),"-70.00","negative postings");
        eq(obj(obj(total(sep).get("hours")).get("ACTUAL")).get("total"),null,"unknown remains unknown");eq(obj(obj(total(sep).get("hours")).get("ACTUAL")).get("known_subtotal"),"5","known hours subtotal");
        List<Map<String,Object>> teachers=rows(v.get("teachers"));eq(teachers.get(0).get("rank"),1,"tie first");eq(teachers.get(1).get("rank"),1,"tie second");eq(teachers.get(2).get("rank"),3,"competition skip");eq(teachers.get(4).get("rank"),null,"unknown unranked");
        eq(teachers.get(0).get("teacher_code"),"T001","stable tie order");
        eq(total(calc(fixture(),query("2026-08-01","2026-08-31",DateBasis.TEACHING))).get("amount"),"0.00","prior month fully reversed");
        eq(obj(obj(total(calc(fixture(),query("2026-08-01","2026-08-31",DateBasis.TEACHING))).get("hours")).get("ACTUAL")).get("total"),"0","old month hours reversed");
        Report paid=calc(fixture(),query("2026-09-01","2026-09-30",DateBasis.PAYMENT));eq(total(paid).get("amount"),"-20.00","payment refund cash only");eq(total(paid).get("entry_count"),2,"two actual cash entries");
        for(String k:HOURS){Map<String,Object>x=obj(obj(total(paid).get("hours")).get(k));eq(x.get("total"),null,"cash has no hours "+k);eq(x.get("known_subtotal"),null,"cash no hours subtotal "+k);}
        eq(rows(paid.toMap().get("teachers")).get(0).get("teacher_code"),"T001","cash identity from referenced accrual");
        Report empty=calc(fixture(),query("2025-01-01","2025-01-31",DateBasis.TEACHING));eq(total(empty).get("amount"),"0.00","empty amount zero");eq(rows(empty.toMap().get("details")).size(),0,"empty rows");
        Report emptyPay=calc(fixture(),query("2025-01-01","2025-01-31",DateBasis.PAYMENT));eq(obj(obj(total(emptyPay).get("hours")).get("ACTUAL")).get("total"),null,"empty payment still no hours");
        Map<String,Object> missing=fixture();e(missing,5).put("course_code",null);Report incomplete=calc(missing,SEP);check(!incomplete.exportable(),"missing formal code blocks export");eq(rows(incomplete.toMap().get("code_gaps")).size(),1,"visible missing code");eq(total(incomplete).get("amount"),"220.00","missing code does not zero money");
        try{ManagementSettlementWorkbook.export(incomplete,"a".repeat(64));throw new AssertionError("missing code exported");}catch(IllegalArgumentException expected){checks++;}
        check(!calculate(List.of(fixture()),SEP,false).exportable(),"no permission blocks export");
        obj(v.get("totals")).put("amount","999.00");rows(v.get("details")).clear();eq(total(sep).get("amount"),"220.00","report immune to returned map mutation");check(!rows(sep.toMap().get("details")).isEmpty(),"nested report immutable");
        Map<String,Object> f=fixture();Report fixed=calc(f,SEP);e(f,5).put("amount","999.00");eq(total(fixed).get("amount"),"220.00","report immune to source mutation");
        rejects(x->x.put("schema_version","DEMO"),"demo schema");rejects(x->e(x,0).put("data_mode","DEMO"),"demo data");rejects(x->e(x,0).put("currency","USD"),"currency");rejects(x->e(x,0).put("amount","100.005"),"do not reround");rejects(x->e(x,0).put("amount",100),"no numeric money");rejects(x->e(x,0).put("amount","1e2"),"no exponent");rejects(x->e(x,0).put("kind","CURRENT"),"invalid kind");
        rejects(x->e(x,1).put("entry_code","A1"),"duplicate id");rejects(x->e(x,1).put("previous_entry_code","MISSING"),"broken chain");rejects(x->e(x,2).put("previous_entry_code","A1"),"fork");rejects(x->e(x,0).put("previous_entry_code","A5"),"cycle");rejects(x->e(x,4).put("previous_entry_code",null),"multiple roots");rejects(x->e(x,3).put("correction_group_code","WRONG"),"unpaired rebook");rejects(x->e(x,2).put("amount","-119.99"),"partial reversal");rejects(x->obj(e(x,2).get("hours")).put("ACTUAL","-1"),"partial hour reversal");
        rejects(x->e(x,4).put("service_date","2026-08-10"),"crossdate bare adjustment");rejects(x->h(x,0).put("current_amount","81.00"),"head amount");rejects(x->h(x,0).put("paid_amount","81.00"),"head payment");rejects(x->h(x,0).put("balance","1.00"),"head balance");rejects(x->h(x,0).put("current_entry_code","A4"),"head latest");rejects(x->h(x,0).put("current_service_date","2026-08-10"),"head date");
        rejects(x->p(x,0).put("accrual_entry_codes",List.of("B0-1")),"crosschain cash ref");rejects(x->p(x,0).put("accrual_entry_codes",List.of()),"cash no ref");rejects(x->p(x,0).put("accrual_entry_codes",List.of("A1","A1")),"cash duplicate ref");rejects(x->p(x,0).put("kind","REFUND"),"refund sign");rejects(x->p(x,0).put("amount","0.00"),"zero cash");rejects(x->p(x,0).put("historical",null),"cash history evidence");
        rejects(x->e(x,0).put("service_date","2026-02-30"),"bad date");rejects(x->e(x,0).put("teacher_id",1.5),"fractional id");rejects(x->e(x,5).put("teacher_code","T001"),"formal code conflict");rejects(x->obj(e(x,0).get("hours")).remove("ACTUAL"),"missing hour kind");rejects(x->obj(e(x,0).get("hours")).put("ACTUAL","1.123456789"),"hour precision");
        try{calculate(List.of(fixture(),fixture()),SEP,true);throw new AssertionError("duplicate project");}catch(IllegalArgumentException expected){checks++;}
        try{new Query(SEP.start(),SEP.end(),DateBasis.PAYMENT,RankMetric.HOURS,"ACTUAL");throw new AssertionError("cash hours ranking");}catch(IllegalArgumentException expected){checks++;}
        Map<String,Object> renamed=fixture();e(renamed,4).put("teacher_code","T001-NEW");e(renamed,4).put("course_code","C001-NEW");
        Report renameReport=calc(renamed,SEP);check(renameReport.exportable(),"approved code correction does not erase original formal codes");
        eq(rows(renameReport.toMap().get("teachers")).get(0).get("teacher_codes"),List.of("T001","T001-NEW"),"historical codes retained without invented current mapping");
        ManagementSettlementWorkbook.export(renameReport,"a".repeat(64));checks++;
        rejects(x->e(x,0).put("correction_group_code","ORPHAN"),"root orphan group");rejects(x->e(x,3).put("service_date","2026-08-10"),"same date rebook");
        rejects(x->e(x,0).remove("hours_after"),"missing complete hour state");
        rejects(x->obj(e(x,1).get("hours_before")).put("ACTUAL",null),"before state must match previous after including null");
        rejects(x->obj(e(x,1).get("hours_after")).put("ACTUAL","3"),"after and signed delta must agree");
        rejects(x->obj(e(x,2).get("hours_after")).put("ACTUAL",null),"reversal must reach explicit zero");
        Map<String,Object> transitions=fixture();e(transitions,0).put("hours",hours(null));e(transitions,0).put("hours_after",hours(null));
        e(transitions,1).put("hours",hours(null));e(transitions,1).put("hours_before",hours(null));
        Report resolved=calc(transitions,query("2026-08-10","2026-08-10",DateBasis.TEACHING));
        eq(obj(obj(total(resolved).get("hours")).get("ACTUAL")).get("total"),"0","unknown origin and later recovery fully reversed is known zero");
        eq(rows(resolved.toMap().get("details")).stream().filter(r->Boolean.TRUE.equals(r.get("hours_counted"))).count(),1L,"one final complete state per chain and date");
        Map<String,Object> recoveredSource=cloneMap(transitions);
        @SuppressWarnings("unchecked") List<Map<String,Object>> recoveredEntries=(List<Map<String,Object>>)recoveredSource.get("accrual_entries");
        recoveredEntries.removeIf(r->Set.of("A3","A4","A5").contains(r.get("entry_code")));
        recoveredSource.put("payment_entries",new ArrayList<>());
        @SuppressWarnings("unchecked") List<Map<String,Object>> recoveredHeads=(List<Map<String,Object>>)recoveredSource.get("chain_heads");
        recoveredHeads.set(0,head("A","A2","2026-08-10","120.00","0.00","TEACHING",1));
        Report recovered=calc(recoveredSource,query("2026-08-10","2026-08-10",DateBasis.TEACHING));
        eq(obj(obj(total(recovered).get("hours")).get("ACTUAL")).get("total"),"2","unknown to known final full state determines monthly total");
        eq(rows(recovered.toMap().get("details")).get(1).get("hours_counted"),true,"latest revision contributes hours");
        eq(obj(rows(recovered.toMap().get("details")).get(1).get("hours")).get("ACTUAL"),null,"unknown delta never converted to zero");
        Map<String,Object> unknown=fixture();e(unknown,4).put("hours",hours(null));e(unknown,4).put("hours_after",hours(null));
        Report uncertain=calc(unknown,SEP);eq(obj(obj(total(uncertain).get("hours")).get("ACTUAL")).get("total"),null,"known to unknown remains unknown");
        eq(obj(obj(total(uncertain).get("hours")).get("ACTUAL")).get("known_subtotal"),"3","superseded known hours are not counted as known subtotal");
        // Exact frozen sum proves M06 does not reprice or aggregate-then-round.
        Map<String,Object> cents=fixture();e(cents,5).put("amount","1.01");h(cents,1).put("current_amount","1.01");h(cents,1).put("balance","1.01");e(cents,6).put("amount","1.01");h(cents,2).put("current_amount","1.01");h(cents,2).put("balance","1.01");eq(total(calc(cents,SEP)).get("amount"),"132.02","sum two individually rounded 1.01");
        // Long exact values remain text in XLSX, leading zero codes survive, and all private fields are absent.
        Map<String,Object> longf=fixture();String longAmount="123456789012345678901234.56";e(longf,5).put("amount",longAmount);h(longf,1).put("current_amount",longAmount);h(longf,1).put("balance",longAmount);e(longf,5).put("teacher_code","0002");
        Report longReport=calc(longf,SEP);byte[] bytes=ManagementSettlementWorkbook.export(longReport,"a".repeat(64));byte[] cashBytes=ManagementSettlementWorkbook.export(paid,"a".repeat(64));
        inspect(bytes,longAmount);inspect(cashBytes,null);
        if(args.length>0){Path dir=Path.of(args[0]);Files.createDirectories(dir);Files.write(dir.resolve("settlement-synthetic.xlsx"),bytes);Files.write(dir.resolve("hours-correction-synthetic.xlsx"),ManagementSettlementWorkbook.export(recovered,"a".repeat(64)));Files.write(dir.resolve("month-synthetic.xlsx"),ManagementSettlementWorkbook.export(sep,"a".repeat(64)));Files.write(dir.resolve("payment-synthetic.xlsx"),cashBytes);Files.write(dir.resolve("empty-synthetic.xlsx"),ManagementSettlementWorkbook.export(empty,"a".repeat(64)));}
        System.out.println("M06 settlement accounting and workbook checks passed: "+checks);
    }
    static void inspect(byte[]bytes,String longAmount)throws Exception{
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);int sheets=0;boolean precise=false;
        try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){ZipEntry e;while((e=z.getNextEntry())!=null){String xml=new String(z.readAllBytes(),StandardCharsets.UTF_8);check(!xml.contains("PRIVATE-"),"privacy "+e.getName());check(!xml.contains("<f>")&&!xml.contains("TargetMode=\"External\"")&&!xml.contains("vbaProject"),"no formula/external/macro "+e.getName());f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));if(e.getName().startsWith("xl/worksheets/")){sheets++;check(xml.contains("state=\"frozen\""),"freeze headers");if(longAmount!=null&&xml.contains(longAmount))precise=true;}}}
        eq(sheets,5,"five sheets");if(longAmount!=null)check(precise,"long number preserved exactly");
    }
}
