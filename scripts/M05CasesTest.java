package com.training;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Synthetic Cases coverage; reuses only the explicit empty-fixture builder, never Db.init(). */
public final class M05CasesTest {
    private static Auth.Session worker,outsider;
    private static int checks,requests;
    private static final String DAY=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(5).toString();
    private static final String NEXT=LocalDate.parse(DAY).plusDays(1).toString();
    private static final String TODAY=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    @FunctionalInterface private interface Work {void run()throws Exception;}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object v){return (List<Map<String,Object>>)v;}
    private static Map<String,Object> copy(Map<String,Object> p){return map(Json.parse(Json.write(p)));}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static long n(Object v){return new BigDecimal(v.toString()).longValueExact();}
    private static void money(Object actual,String expected,String label){check(actual instanceof String&&new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected))==0,label+" actual="+actual);}
    private static void rejects(int status,Work action,String label)throws Exception {try{action.run();throw new AssertionError("Expected rejection: "+label);}catch(Api.ApiException ex){check(ex.code==status,label+" status="+ex.code+" "+ex.getMessage());}}
    private static Object invoke(String name,Class<?>[] types,Object... args)throws Exception {
        Method m=M05FormalWorkflowTest.class.getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(null,args);}catch(InvocationTargetException ex){Throwable cause=ex.getCause();if(cause instanceof Exception e)throw e;throw ex;}
    }
    private static Auth.Session session(String field)throws Exception {Field f=M05FormalWorkflowTest.class.getDeclaredField(field);f.setAccessible(true);return (Auth.Session)f.get(null);}
    private static Map<String,Object> body(String key,long version){Map<String,Object>b=new LinkedHashMap<>();b.put("project_id",10);b.put("case_code",key);b.put("expected_version",version);b.put("request_id","CASE-REQUEST-"+(++requests));return b;}
    private static Map<String,Object> read(String key)throws Exception{return DeliverySettlementCases.read(worker,key);}
    private static Map<String,Object> command(String key)throws Exception{return body(key,n(read(key).get("version")));}
    private static void proof(Map<String,Object>b){b.put("evidence_note","隔离测试：核对实际记录、计酬条件及金额无误。");b.put("evidence_reference","隔离测试资料 第1号");}
    private static Map<String,Object> save(String key,String kind,Map<String,Object>p)throws Exception {Map<String,Object>b=body(key,0);b.put("kind",kind);b.put("payload",p);return DeliverySettlementCases.mutate("save",worker,b);}
    private static Map<String,Object> submit(String key)throws Exception {Map<String,Object>b=command(key);proof(b);return DeliverySettlementCases.mutate("submit",worker,b);}
    private static Map<String,Object> review(String key)throws Exception {Map<String,Object>b=command(key);b.put("decision","APPROVE");proof(b);return DeliverySettlementCases.mutate("review",worker,b);}
    private static Map<String,Object> confirm(String key)throws Exception{return DeliverySettlementCases.mutate("confirm",worker,command(key));}
    private static Map<String,Object> complete(String key,String kind,Map<String,Object>p)throws Exception{save(key,kind,p);submit(key);review(key);return confirm(key);}
    private static String chain(String key)throws Exception{return rows(map(read(key).get("confirmation")).get("chains")).get(0).get("chain_code").toString();}
    private static String head(String chain)throws Exception{return DeliverySettlementLedger.head(chain).get("current_entry_code").toString();}
    private static Map<String,Object> balance(String chain)throws Exception{return DeliverySettlementLedger.balance(chain);}
    private static void pay(String key,String chain,String amount)throws Exception {Map<String,Object>b=command(key);b.put("chain_code",chain);b.put("expected_entry_code",head(chain));b.put("payment_date",TODAY);b.put("amount",amount);proof(b);DeliverySettlementCases.mutate("payment",worker,b);}
    private static Map<String,Object> dev(String code,boolean joint){
        Map<String,Object>p=new LinkedHashMap<>();p.put("activity",joint?"JOINT_DEVELOPMENT":"SOLO_DEVELOPMENT");p.put("service_date",DAY);p.put("deliverable_code",code);p.put("deliverable_version","V1");p.put("lead_teacher_id",20);
        p.put("grade","SENIOR");p.put("research_team","NO");p.put("appointed_on",LocalDate.parse(DAY).minusYears(1).toString());p.put("annual_plan","YES");p.put("customer_paid","UNKNOWN");p.put("service_date_applicable","YES");p.put("payable_hours",joint?"2":"1.33");
        p.put("development_path","CUSTOMER_REQUESTED");p.put("repetition_percent","30");p.put("previous_grant","NO");p.put("customer_written_payment_agreement","YES");p.put("customer_acceptance","YES");p.put("company_approval","YES");
        p.put("completed_on",DAY);p.put("customer_acceptance_on",DAY);p.put("company_approval_on",DAY);
        Map<String,Object>e=new LinkedHashMap<>();for(String k:List.of("grade","research_team","appointment","annual_plan","applicability","payable_hours","repetition","previous_grant","customer_written_payment_agreement","customer_acceptance","company_approval","completion","deliverable"))e.put(k,Map.of("note","隔离测试：已查阅并核实"+k+"。","reference","隔离材料/第1条"));
        if(joint){e.put("allocation","隔离测试：共享交付核准负责人180元、成员120元，合计300元。");p.put("allocations",List.of(Map.of("teacher_id",20,"amount","180.00"),Map.of("teacher_id",21,"amount","120.00")));}
        p.put("evidence_notes",e);return p;
    }
    private static Map<String,Object> migration(String source){
        Map<String,Object>p=new LinkedHashMap<>();p.put("legacy_source_code",source);p.put("legacy_rule_code","原培训课酬管理标准〔2025〕第3号");p.put("service_date",DAY);p.put("teacher_id",20);p.put("amount","250.00");
        p.put("source_note","隔离测试：按原台账逐项核对该笔应付，没有按现行制度重算。");p.put("source_reference","旧台账第3页第2行");p.put("original_approval_note","隔离测试：原审批金额为250元。");p.put("original_approval_reference","原审批单第8号");
        p.put("historical_payment",Map.of("payment_date",DAY,"amount","150.00","note","隔离测试：旧付款回单核实已付150元。","reference","旧银行回单第8号"));return p;
    }
    private static Map<String,Object> adjust(String chain)throws Exception {Map<String,Object>p=new LinkedHashMap<>();p.put("chain_code",chain);p.put("expected_entry_code",head(chain));p.put("reason_note","隔离测试：原记录经复核存在差异，需要更正。");p.put("reason_reference","更正核对单第1号");p.put("evidence_note","隔离测试：逐项核对原始财务依据与本次修正事实。");p.put("evidence_reference","更正材料第1号");return p;}
    public static void main(String[]args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Fresh empty directory required");Path data=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("Fresh empty real directory required");try(var stream=Files.list(data)){if(stream.findAny().isPresent())throw new IllegalArgumentException("Directory must be empty");}
        invoke("fixtures",new Class<?>[]{Path.class},data);DeliverySettlementIntegration.init();
        invoke("publish",new Class<?>[]{String.class,String.class,Set.class},null,"CASES-ACCESS-1",Set.of());worker=session("worker");outsider=session("outsider");
        Db.exec("INSERT INTO teachers VALUES(21,'SYNTHETIC JOINT MEMBER','在库',99999)");
        Map<String,Object> partial=save("CASE-PARTIAL","DEVELOPMENT",Map.of());check("DRAFT".equals(partial.get("state"))&&!((List<?>)partial.get("missing_items")).isEmpty(),"partial draft allowed with concrete missing facts");
        submit("CASE-PARTIAL");rejects(409,()->review("CASE-PARTIAL"),"unknown facts block approval only");
        Map<String,Object> returned=command("CASE-PARTIAL");returned.put("decision","RETURN");returned.put("reason_note","隔离测试：缺少必要成果资格记录，请补充。");proof(returned);DeliverySettlementCases.mutate("review",worker,returned);
        Map<String,Object> fill=command("CASE-PARTIAL");fill.put("kind","DEVELOPMENT");fill.put("payload",dev("DELIVERABLE-PARTIAL",false));DeliverySettlementCases.mutate("save",worker,fill);submit("CASE-PARTIAL");review("CASE-PARTIAL");confirm("CASE-PARTIAL");
        String solo=chain("CASE-PARTIAL");money(balance(solo).get("current_amount"),"199.50","solo uses senior approved table");
        pay("CASE-PARTIAL",solo,"199.50");String oldPayment=Json.write(DeliverySettlementLedger.entries("m05_payment_events",solo));
        Map<String,Object> newDev=dev("DELIVERABLE-PARTIAL",false);newDev.put("payable_hours","1");for(String k:List.of("service_date","completed_on","customer_acceptance_on","company_approval_on"))newDev.put(k,NEXT);
        Map<String,Object> correction=adjust(solo);correction.put("service_date",NEXT);correction.put("development",newDev);
        complete("CASE-SOLO-ADJUST","ADJUSTMENT",correction);money(balance(solo).get("balance"),"-49.50","paid developer change creates refund balance");
        check(DeliverySettlementLedger.entries("m05_financial_entries",solo).size()==3,"cross-day change appends reversal and rebook");check(oldPayment.equals(Json.write(DeliverySettlementLedger.entries("m05_payment_events",solo))),"old actual payment immutable");pay("CASE-SOLO-ADJUST",solo,"-49.50");money(balance(solo).get("balance"),"0","refund settles exact balance");
        complete("CASE-LEGACY","MIGRATION",migration("旧系统A/课酬台账/2025/第8笔"));String legacy=chain("CASE-LEGACY");money(balance(legacy).get("current_amount"),"250","legacy retains explicit original amount");money(balance(legacy).get("paid_amount"),"150","only explicit historical payment imported");
        check(Boolean.TRUE.equals(DeliverySettlementLedger.entries("m05_payment_events",legacy).get(0).get("historical")),"historical cash marked explicitly");
        Map<String,Object> legacyChange=adjust(legacy);legacyChange.put("service_date",NEXT);legacyChange.put("amount","200");legacyChange.put("legacy_rule_code","原培训课酬管理标准〔2025〕第3号");legacyChange.put("source_note","隔离测试：原台账金额应更正为200元。");legacyChange.put("original_approval_note","隔离测试：原制度审批调整依据已经核实。");
        complete("CASE-LEGACY-ADJUST","ADJUSTMENT",legacyChange);money(balance(legacy).get("balance"),"50","legacy correction retains old rule and historical cash");pay("CASE-LEGACY-ADJUST",legacy,"50");money(balance(legacy).get("balance"),"0","legacy remaining payment settled");
        save("CASE-DUPLICATE","MIGRATION",migration("旧系统A/课酬台账/2025/第8笔"));submit("CASE-DUPLICATE");rejects(409,()->review("CASE-DUPLICATE"),"qualified legacy origin cannot repeat");
        complete("CASE-JOINT","DEVELOPMENT",dev("DELIVERABLE-JOINT",true));List<Map<String,Object>> jointHeads=rows(map(read("CASE-JOINT").get("confirmation")).get("chains"));String joint=jointHeads.get(0).get("chain_code").toString();String second=jointHeads.get(1).get("chain_code").toString();
        check(map(map(DeliverySettlementLedger.head(joint).get("total")).get("hours")).get("PAYABLE")==null,"joint shares never duplicate pool payable hours");
        pay("CASE-JOINT",joint,"50");Map<String,Object> cancellation=adjust(joint);cancellation.put("cancellation",true);cancellation.put("expected_entries",Map.of(second,head(second)));complete("CASE-JOINT-CANCEL","ADJUSTMENT",cancellation);
        money(balance(joint).get("current_amount"),"0","joint cancellation zeroes first member");money(balance(second).get("current_amount"),"0","joint cancellation zeroes every member");money(balance(joint).get("balance"),"-50","joint cancellation retains paid amount for refund");pay("CASE-JOINT-CANCEL",joint,"-50");
        Map<String,Object> rd=dev("DELIVERABLE-RD",false);rd.put("research_team","YES");rd.put("grade",null);complete("CASE-RD","DEVELOPMENT",rd);money(balance(chain("CASE-RD")).get("current_amount"),"133","R&D unknown declared grade uses lecturer");
        Map<String,Object> illegal=dev("DELIVERABLE-RD-JOINT",true);illegal.put("research_team","YES");illegal.put("joint_reference_grade","SPECIAL");map(illegal.get("evidence_notes")).put("joint_basis","隔离测试：本案等级衔接依据。");save("CASE-ILLEGAL-JOINT","DEVELOPMENT",illegal);submit("CASE-ILLEGAL-JOINT");rejects(409,()->review("CASE-ILLEGAL-JOINT"),"R&D joint cannot invent third reference grade");
        Map<String,Object> future=migration("旧系统A/未来记录");future.put("service_date",LocalDate.parse(TODAY).plusDays(1).toString());save("CASE-FUTURE","MIGRATION",future);submit("CASE-FUTURE");rejects(409,()->review("CASE-FUTURE"),"future occurrence cannot create legacy opening");
        Map<String,Object> bad=dev("DELIVERABLE-UNREGISTERED",false);bad.remove("evidence_notes");bad.put("evidence",Map.of("grade","FAKE-EVIDENCE"));rejects(409,()->save("CASE-BAD-PROOF","DEVELOPMENT",bad),"arbitrary technical evidence rejected");
        rejects(403,()->DeliverySettlementCases.read(outsider,"CASE-LEGACY"),"other org cannot read case");rejects(403,()->DeliverySettlementCases.options(outsider,10),"other org cannot read choices");
        corruptionCheck();teachingChecks();replayChecks();optionsScopeCheck();additionalBranches();withdrawalChecks();
        check(Db.count("fees")==0,"independent cases never read or generate legacy fee arithmetic");
        Db.exec("SHUTDOWN");System.out.println("M05Cases: "+checks+" checks passed (isolated synthetic H2)");
    }
    private static void corruptionCheck()throws Exception {
        save("CASE-AUDIT","MIGRATION",migration("旧系统A/审计样例"));submit("CASE-AUDIT");review("CASE-AUDIT");
        Map<String,Object> row=Db.one("SELECT payload,version FROM m05_cases WHERE case_code='CASE-AUDIT'");String original=row.get("payload").toString();Map<String,Object> tampered=map(Json.parse(original));Map<String,Object> submission=map(tampered.get("submission")),review=map(tampered.get("review"));
        for(String k:List.of("evidence_code","evidence_kind","evidence_descriptor"))review.put(k,submission.get(k));String bad=Json.write(tampered);
        Db.exec("UPDATE m05_cases SET payload=? WHERE case_code='CASE-AUDIT'",bad);Db.exec("UPDATE m05_case_revisions SET payload=? WHERE case_code='CASE-AUDIT' AND version=?",bad,row.get("version"));
        rejects(409,()->read("CASE-AUDIT"),"stored review proof cannot be replaced with submission proof");
        Db.exec("UPDATE m05_cases SET payload=? WHERE case_code='CASE-AUDIT'",original);Db.exec("UPDATE m05_case_revisions SET payload=? WHERE case_code='CASE-AUDIT' AND version=?",original,row.get("version"));confirm("CASE-AUDIT");
    }
    private static void teachingChecks()throws Exception {
        invoke("verifiedFact",new Class<?>[]{long.class,String.class,String.class},101L,"60","1.33");invoke("readyClaim",new Class<?>[]{long.class,String.class,String.class},101L,"LECTURER","NO");
        Map<String,Object> confirm=map(invoke("settlementBody",new Class<?>[]{long.class,String.class},101L,"confirm"));DeliverySettlementWorkflow.mutate("confirm",worker,confirm);
        Map<String,Object> payment=map(invoke("paymentBody",new Class<?>[]{long.class,String.class},101L,"133.00"));DeliverySettlementWorkflow.mutate("payment",worker,payment);
        String frozen=Db.one("SELECT payload FROM m05_delivery_facts WHERE dispatch_id=101").get("payload").toString();Map<String,Object> p=adjust("DISPATCH-101");p.put("service_date",TODAY);p.put("claim",invoke("claim",new Class<?>[]{String.class,String.class},"LECTURER","NO"));p.put("hours",Map.of("actual_minutes","90","payable","2"));
        complete("CASE-TEACHING-PAID","ADJUSTMENT",p);money(balance("DISPATCH-101").get("balance"),"67","paid teaching correction supplementary balance");check(frozen.equals(Db.one("SELECT payload FROM m05_delivery_facts WHERE dispatch_id=101").get("payload").toString()),"adjustment preserves original teaching fact");pay("CASE-TEACHING-PAID","DISPATCH-101","67");
        invoke("verifiedFact",new Class<?>[]{long.class,String.class,String.class},102L,"60","1.33");Map<String,Object> waiver=new LinkedHashMap<>();waiver.put("dispatch_id",102);waiver.put("reason_note","隔离测试：本次属于不支付课酬范围，已经逐案核准。");waiver.put("evidence_note","隔离测试：实际授课已经核对，明确不形成应付。");
        complete("CASE-WAIVER","WAIVER",waiver);money(balance("DISPATCH-102").get("current_amount"),"0","explicit approved waiver closes no-fee disposition");check(map(DeliverySettlementLedger.head("DISPATCH-102").get("total")).get("source_fact_revision")!=null,"waiver freezes actual source revision");
    }
    private static void replayChecks()throws Exception {
        Map<String,Object>b=body("CASE-REPLAY",0);b.put("kind","DEVELOPMENT");b.put("payload",dev("DELIVERABLE-REPLAY",false));Map<String,Object>a=DeliverySettlementCases.mutate("save",worker,copy(b));long count=Db.count("m05_evidence_records");Map<String,Object>again=DeliverySettlementCases.mutate("save",worker,copy(b));
        check(copy(a).equals(copy(again)),"case exact retry returns original response");check(count==Db.count("m05_evidence_records"),"retry cannot duplicate proof records");
        Map<String,Object>changed=copy(b);map(changed.get("payload")).put("payable_hours","2");rejects(409,()->DeliverySettlementCases.mutate("save",worker,changed),"same request cannot change values");
        Db.exec("UPDATE projects SET status='已归档' WHERE id=10");rejects(409,()->DeliverySettlementCases.mutate("save",worker,copy(b)),"archived project blocks financial replay action");check(read("CASE-REPLAY")!=null,"archived project remains readable");check(Boolean.FALSE.equals(DeliverySettlementCases.options(worker,10).get("can_save")),"options new-case capability is false for archived project");Db.exec("UPDATE projects SET status='进行中' WHERE id=10");
    }
    private static void optionsScopeCheck()throws Exception {
        check(Boolean.TRUE.equals(DeliverySettlementCases.options(worker,10).get("can_save")),"options new-case capability comes from live submit permission");
        check(!rows(DeliverySettlementCases.options(worker,10).get("financial_chains")).isEmpty(),"authorized choices include actual teaching and independent chains");
        check(rows(DeliverySettlementCases.options(worker,10).get("deliverables")).stream().anyMatch(r->"DELIVERABLE-JOINT".equals(r.get("deliverable_code"))),"existing actual deliverable identifiers are selectable without generated substitutes");
        check(rows(DeliverySettlementCases.options(worker,12).get("deliverables")).stream().anyMatch(r->n(r.get("project_id"))==10),"same-organization existing deliverables remain selectable across trusted projects");
        Db.exec("UPDATE workflow_demands SET organization_code='002' WHERE demand_id=1");rejects(409,()->DeliverySettlementCases.options(outsider,10),"source org drift cannot expose frozen prior-org balance");Db.exec("UPDATE workflow_demands SET organization_code='001' WHERE demand_id=1");
    }
    private static void additionalBranches()throws Exception {
        Map<String,Object> rd=dev("DELIVERABLE-RD-JOINT-VALID",true);rd.put("research_team","YES");rd.put("grade",null);rd.put("joint_reference_grade","LECTURER");rd.put("allocations",List.of(Map.of("teacher_id",20,"amount","120"),Map.of("teacher_id",21,"amount","80")));map(rd.get("evidence_notes")).put("joint_basis","隔离测试：本案按研发成员讲师标准核准合作开发总池。");
        complete("CASE-RD-JOINT-VALID","DEVELOPMENT",rd);String primary=chain("CASE-RD-JOINT-VALID");money(balance(primary).get("current_amount"),"120","explicit R&D joint basis and allocations supported");
        Map<String,Object> onlyOne=adjust(primary);onlyOne.put("cancellation",true);save("CASE-JOINT-CANCEL-INCOMPLETE","ADJUSTMENT",onlyOne);submit("CASE-JOINT-CANCEL-INCOMPLETE");rejects(409,()->review("CASE-JOINT-CANCEL-INCOMPLETE"),"joint cancellation cannot omit another member current head");
        Map<String,Object> self=dev("DELIVERABLE-SELF",false);self.put("development_path","SELF_INITIATED");self.put("customer_written_payment_agreement","UNKNOWN");self.put("customer_acceptance","UNKNOWN");self.put("company_need","YES");self.put("annual_review_passed","YES");self.put("annual_review_on",DAY);map(self.get("evidence_notes")).put("company_need","隔离测试：开发符合公司实际需要。");map(self.get("evidence_notes")).put("annual_review_passed","隔离测试：年度评审已经实际通过。");complete("CASE-SELF","DEVELOPMENT",self);money(balance(chain("CASE-SELF")).get("current_amount"),"199.50","self initiated qualified development supported");
        Map<String,Object> badAllocation=dev("DELIVERABLE-BAD-ALLOCATION",true);badAllocation.put("allocations",List.of(Map.of("teacher_id",20,"amount","180"),Map.of("teacher_id",21,"amount","119")));save("CASE-ALLOCATION-SUM","DEVELOPMENT",badAllocation);submit("CASE-ALLOCATION-SUM");rejects(409,()->review("CASE-ALLOCATION-SUM"),"explicit member amounts must reconcile exact pool");
        Map<String,Object> other=body("CASE-CROSS-ORG-DUPLICATE",0);other.put("project_id",11);other.put("kind","MIGRATION");other.put("payload",migration("旧系统A/课酬台账/2025/第8笔"));DeliverySettlementCases.mutate("save",outsider,other);
        Map<String,Object> sub=body("CASE-CROSS-ORG-DUPLICATE",1);sub.put("project_id",11);proof(sub);DeliverySettlementCases.mutate("submit",outsider,sub);
        Map<String,Object> review=body("CASE-CROSS-ORG-DUPLICATE",2);review.put("project_id",11);review.put("decision","APPROVE");proof(review);rejects(409,()->DeliverySettlementCases.mutate("review",outsider,review),"original source uniqueness spans organizations without disclosing original record");
        String rdChain=chain("CASE-RD");long proofBefore=Db.count("m05_evidence_records"),paymentsBefore=Db.count("m05_payment_events");
        rejects(409,()->pay("CASE-RD",rdChain,"134"),"payment cannot exceed outstanding balance");rejects(409,()->pay("CASE-RD",rdChain,"-1"),"refund cannot precede credit balance");
        check(Db.count("m05_evidence_records")==proofBefore&&Db.count("m05_payment_events")==paymentsBefore,"rejected payments rollback proofs and cash events atomically");
        Map<String,Object> amountNumeric=command("CASE-RD");amountNumeric.put("chain_code",rdChain);amountNumeric.put("expected_entry_code",head(rdChain));amountNumeric.put("payment_date",TODAY);amountNumeric.put("amount",1);proof(amountNumeric);rejects(400,()->DeliverySettlementCases.mutate("payment",worker,amountNumeric),"JSON numeric money forbidden");
        Map<String,Object> wrongCasePayload=copy(map(read("CASE-REPLAY").get("payload")));rejects(409,()->save("CASE-WRONG-PROOF-CASE","DEVELOPMENT",wrongCasePayload),"registered facts cannot be copied to another case");
        Map<String,Object> changedProof=command("CASE-REPLAY");changedProof.put("kind","DEVELOPMENT");Map<String,Object> changedPayload=copy(map(read("CASE-REPLAY").get("payload")));changedPayload.put("payable_hours","2");changedProof.put("payload",changedPayload);rejects(409,()->DeliverySettlementCases.mutate("save",worker,changedProof),"registered fact proof cannot silently cover edited values");
        rejects(409,()->DeliverySettlementCases.requireProjectSettled(10),"known unconfirmed drafts block archive");
        save("CASE-HISTORICAL-PAY-GRANT","MIGRATION",migration("旧系统A/权限核对记录"));submit("CASE-HISTORICAL-PAY-GRANT");review("CASE-HISTORICAL-PAY-GRANT");
        invoke("publish",new Class<?>[]{String.class,String.class,Set.class},"CASES-ACCESS-1","CASES-ACCESS-2",Set.of("settlement.pay"));worker=session("worker");outsider=session("outsider");
        rejects(403,()->confirm("CASE-HISTORICAL-PAY-GRANT"),"historical cash import requires explicit current payment permission");
        check(Boolean.FALSE.equals(map(read("CASE-HISTORICAL-PAY-GRANT").get("capabilities")).get("confirm")),"UI does not offer cash posting without payment permission");
        invoke("publish",new Class<?>[]{String.class,String.class,Set.class},"CASES-ACCESS-2","CASES-ACCESS-3",Set.of());worker=session("worker");outsider=session("outsider");confirm("CASE-HISTORICAL-PAY-GRANT");
    }
    private static Map<String,Object> withdrawalBody(String key)throws Exception {Map<String,Object>b=command(key);b.put("reason_note","隔离测试：此事项系误建草稿，经核对予以作废，保留原记录。");b.put("reason_reference","误建事项核对单第1号");return b;}
    private static void withdrawalChecks()throws Exception {
        Map<String,Object> initial=save("CASE-WITHDRAW-DRAFT","DEVELOPMENT",Map.of());
        check(Boolean.TRUE.equals(map(initial.get("capabilities")).get("can_withdraw")),"draft exposes exact can_withdraw capability key");
        Map<String,Object> blank=withdrawalBody("CASE-WITHDRAW-DRAFT");blank.put("reason_note"," ");
        rejects(400,()->DeliverySettlementCases.mutate("withdraw",worker,blank),"withdraw requires actual nonblank reason");
        Map<String,Object> foreign=withdrawalBody("CASE-WITHDRAW-DRAFT");
        rejects(403,()->DeliverySettlementCases.mutate("withdraw",outsider,copy(foreign)),"withdraw cannot cross current organization scope");
        Map<String,Object> stale=withdrawalBody("CASE-WITHDRAW-DRAFT");stale.put("expected_version",0);
        rejects(409,()->DeliverySettlementCases.mutate("withdraw",worker,stale),"withdraw rejects stale case version");
        Map<String,Object> command=withdrawalBody("CASE-WITHDRAW-DRAFT");
        invoke("publish",new Class<?>[]{String.class,String.class,Set.class},"CASES-ACCESS-3","CASES-ACCESS-4",Set.of("settlement.submit"));worker=session("worker");outsider=session("outsider");
        check(Boolean.FALSE.equals(DeliverySettlementCases.options(worker,10).get("can_save")),"options new-case capability rejects current missing submit grant");
        rejects(403,()->DeliverySettlementCases.mutate("withdraw",worker,copy(command)),"withdraw requires current submit HANDLE grant");
        invoke("publish",new Class<?>[]{String.class,String.class,Set.class},"CASES-ACCESS-4","CASES-ACCESS-5",Set.of());worker=session("worker");outsider=session("outsider");
        String accrualBefore=Json.write(Db.query("SELECT entry_code,payload FROM m05_financial_entries ORDER BY entry_code"));
        String cashBefore=Json.write(Db.query("SELECT entry_code,payload FROM m05_payment_events ORDER BY entry_code"));
        Map<String,Object> withdrawn=DeliverySettlementCases.mutate("withdraw",worker,copy(command));
        check("WITHDRAWN".equals(withdrawn.get("state")),"draft becomes terminal WITHDRAWN");
        check(copy(map(initial.get("payload"))).equals(copy(map(withdrawn.get("payload")))),"withdraw preserves complete original claim payload");
        check(rows(withdrawn.get("audit")).size()==2&&rows(withdrawn.get("financial")).isEmpty(),"withdraw appends audit without any financial chain");
        check(withdrawn.get("calculation_preview")==null&&((List<?>)withdrawn.get("missing_items")).isEmpty(),"withdrawn draft has no payable preview or pending missing facts");
        check(map(withdrawn.get("capabilities")).values().stream().allMatch(Boolean.FALSE::equals),"all mutation capabilities disabled at withdrawn terminal state");
        Map<String,Object> attestation=map(withdrawn.get("withdrawal"));String reason=attestation.get("reason_code").toString();Map<String,Object> proof=map(map(withdrawn.get("evidence_records")).get(reason));
        check("0002".equals(attestation.get("actor_code"))&&n(attestation.get("account_id"))==2&&proof.get("note").toString().contains("误建草稿"),"withdraw freezes real actor account time and readable reason");
        long evidenceCount=Db.count("m05_evidence_records");Map<String,Object> replay=DeliverySettlementCases.mutate("withdraw",worker,copy(command));
        check(copy(withdrawn).equals(copy(replay))&&evidenceCount==Db.count("m05_evidence_records"),"withdraw replay is exact and creates no duplicate evidence");
        Map<String,Object> changed=copy(command);changed.put("reason_note","隔离测试：改换同请求编号的原因。");
        rejects(409,()->DeliverySettlementCases.mutate("withdraw",worker,changed),"withdraw request ID cannot be reused with another reason");
        for(String operation:List.of("save","submit","review","confirm","payment","withdraw")) {
            Map<String,Object>b=command("CASE-WITHDRAW-DRAFT");
            if(operation.equals("save")){b.put("kind","DEVELOPMENT");b.put("payload",Map.of());}
            if(operation.equals("submit"))proof(b);
            if(operation.equals("review")){b.put("decision","APPROVE");proof(b);}
            if(operation.equals("payment")){b.put("chain_code",chain("CASE-RD"));b.put("expected_entry_code",head(chain("CASE-RD")));b.put("payment_date",TODAY);b.put("amount","1");proof(b);}
            if(operation.equals("withdraw"))b.put("reason_note","隔离测试：重复作废。");
            rejects(409,()->DeliverySettlementCases.mutate(operation,worker,b),"withdrawn terminal rejects "+operation);
        }
        check(accrualBefore.equals(Json.write(Db.query("SELECT entry_code,payload FROM m05_financial_entries ORDER BY entry_code")))&&cashBefore.equals(Json.write(Db.query("SELECT entry_code,payload FROM m05_payment_events ORDER BY entry_code"))),"withdraw never creates, removes or rewrites accrual or cash events");
        save("CASE-WITHDRAW-RETURN","DEVELOPMENT",Map.of());submit("CASE-WITHDRAW-RETURN");
        rejects(409,()->DeliverySettlementCases.mutate("withdraw",worker,withdrawalBody("CASE-WITHDRAW-RETURN")),"submitted case must be formally returned before withdrawal");
        Map<String,Object> returning=command("CASE-WITHDRAW-RETURN");returning.put("decision","RETURN");returning.put("reason_note","隔离测试：退回确认误建。");proof(returning);DeliverySettlementCases.mutate("review",worker,returning);
        Map<String,Object> returned=read("CASE-WITHDRAW-RETURN");DeliverySettlementCases.mutate("withdraw",worker,withdrawalBody("CASE-WITHDRAW-RETURN"));Map<String,Object> closed=read("CASE-WITHDRAW-RETURN");
        check(copy(map(returned.get("submission"))).equals(copy(map(closed.get("submission"))))&&copy(map(returned.get("review"))).equals(copy(map(closed.get("review")))),"withdrawal of returned case retains submission review and evidence");
        Map<String,Object> row=Db.one("SELECT payload,version FROM m05_cases WHERE case_code='CASE-WITHDRAW-RETURN'");String original=row.get("payload").toString();Map<String,Object> tampered=map(Json.parse(original)),withdrawal=map(tampered.get("withdrawal")),submission=map(tampered.get("submission"));
        for(String field:List.of("evidence_code","evidence_kind","evidence_descriptor"))withdrawal.put(field,submission.get(field));withdrawal.put("reason_code",submission.get("evidence_code"));
        Db.exec("UPDATE m05_cases SET payload=? WHERE case_code='CASE-WITHDRAW-RETURN'",Json.write(tampered));Db.exec("UPDATE m05_case_revisions SET payload=? WHERE case_code='CASE-WITHDRAW-RETURN' AND version=?",Json.write(tampered),row.get("version"));
        rejects(409,()->read("CASE-WITHDRAW-RETURN"),"withdraw history cannot substitute a submission proof");
        Db.exec("UPDATE m05_cases SET payload=? WHERE case_code='CASE-WITHDRAW-RETURN'",original);Db.exec("UPDATE m05_case_revisions SET payload=? WHERE case_code='CASE-WITHDRAW-RETURN' AND version=?",original,row.get("version"));
        save("CASE-WITHDRAW-APPROVED","MIGRATION",migration("旧系统A/已核准不可作废"));submit("CASE-WITHDRAW-APPROVED");review("CASE-WITHDRAW-APPROVED");
        rejects(409,()->DeliverySettlementCases.mutate("withdraw",worker,withdrawalBody("CASE-WITHDRAW-APPROVED")),"approved case cannot be withdrawn");
        rejects(409,()->DeliverySettlementCases.mutate("withdraw",worker,withdrawalBody("CASE-LEGACY")),"confirmed financial case requires adjustment cancellation");
        withdrawalArchiveCheck();
    }
    private static void withdrawalArchiveCheck()throws Exception {
        Db.exec("INSERT INTO demands VALUES(90,'SYNTHETIC WITHDRAW ONLY','团队已受理')");Db.exec("INSERT INTO projects VALUES(90,90,'SYNTHETIC WITHDRAW ONLY','进行中',0)");
        Db.exec("INSERT INTO workflow_demands VALUES(90,1,1,FALSE,'direct','001','0002','{}','{}','SYNTHETIC-M03')");Db.exec("INSERT INTO workflow_acceptances VALUES(90,90,'001','0002',1,'2026-01-01T00:00:00Z')");
        Map<String,Object> save=body("CASE-WITHDRAW-ARCHIVE",0);save.put("project_id",90);save.put("kind","DEVELOPMENT");save.put("payload",Map.of());DeliverySettlementCases.mutate("save",worker,save);
        check(Boolean.TRUE.equals(DeliverySettlementIntegration.projectSettlement(worker,90).get("pending_cases")),"unresolved accidental draft reaches project pending-case card");
        Map<String,Object> withdraw=body("CASE-WITHDRAW-ARCHIVE",1);withdraw.put("project_id",90);withdraw.put("reason_note","隔离测试：误建的空草稿，经核对关闭办理，未产生任何课酬。");DeliverySettlementCases.mutate("withdraw",worker,withdraw);
        DeliverySettlementCases.requireProjectSettled(90);Map<String,Object> summary=DeliverySettlementIntegration.projectSettlement(worker,90);
        check(Boolean.FALSE.equals(summary.get("pending_cases"))&&"AVAILABLE".equals(summary.get("status")),"withdrawn accidental draft no longer blocks integrated project pending-case card");
        check(Db.query("SELECT chain_code FROM m05_financial_chains WHERE project_id=90").isEmpty(),"closing mistaken draft never invents zero financial records");
    }
}
