package com.training;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Real Auth and M01, generated identities and caller-owned empty H2 only. Never calls Db.init(). */
public final class M05FormalWorkflowTest {
    private static final String PASSWORD="SYNTHETIC-M05-FORMAL-ONLY";
    private static final String DATE=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
    private static final String PAYMENT_DATE=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    private static final String DEFAULT_SETTINGS="M05-MONEY-USER-20260923";
    private static Auth.Session admin,worker,outsider,unbound;
    private static final Map<String,String> INITIAL_PROOFS=new LinkedHashMap<>();
    private static int checks,requests;
    @FunctionalInterface private interface Work {void run()throws Exception;}
    @FunctionalInterface private interface LedgerWork {Map<String,Object> run()throws Exception;}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void rejects(int status,Work work,String label)throws Exception {
        try{work.run();throw new AssertionError("Expected rejection: "+label);}
        catch(Api.ApiException e){check(e.code==status,label+" status="+e.code+" "+e.getMessage());}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return (Map<String,Object>)v;}
    private static Map<String,Object> copy(Map<String,Object> v){return map(Json.parse(Json.write(v)));}
    private static long number(Object v){return new BigDecimal(v.toString()).longValueExact();}
    private static long version(Map<String,Object> v){return number(v.get("version"));}
    private static String request(String prefix){return "SYNTHETIC-"+prefix+"-"+(++requests);}
    private static void decimal(Object v,String expected,String label){
        check(v instanceof String,label+" is exact decimal text");
        check(new BigDecimal(v.toString()).compareTo(new BigDecimal(expected))==0,label+" expected="+expected+" actual="+v);
    }
    private static boolean sameResponse(Map<String,Object> a,Map<String,Object> b){
        Map<String,Object> left=copy(a),right=copy(b);left.remove("capabilities");right.remove("capabilities");return left.equals(right);
    }
    private static Map<String,Object> read(long id)throws Exception{return DeliverySettlementWorkflow.read(worker,id);}
    private static Map<String,Object> command(long id,String code)throws Exception {
        Map<String,Object> view=read(id),b=new LinkedHashMap<>();
        b.put("dispatch_id",id);b.put("expected_version",view.get("version"));
        b.put("expected_fact_version",view.get("fact_version"));b.put("request_id",code);return b;
    }
    private static Map<String,Object> call(String op,Map<String,Object> b)throws Exception{return DeliverySettlementWorkflow.mutate(op,worker,copy(b));}
    private static Map<String,Object> claim(String grade,String research){
        Map<String,Object> c=new LinkedHashMap<>();c.put("grade",grade);c.put("day_type","WORKDAY");c.put("research_team",research);
        c.put("appointed_on",LocalDate.parse(DATE).minusYears(1).toString());c.put("annual_plan","YES");c.put("customer_paid","NO");
        c.put("service_date_applicable","YES");
        Map<String,Object> evidence=new LinkedHashMap<>();
        for(String name:List.of("grade","day_type","research_team","appointment","annual_plan","customer_paid","applicability"))
            evidence.put(name,Map.of("note","仅用于隔离测试：已核对本次业务的 "+name+"，资料内容与申报一致。","reference","隔离测试资料 / 课酬依据登记簿 / "+name+" / 第1条"));
        c.put("evidence_notes",evidence);return c;
    }
    private static void proof(Map<String,Object> body,String kind){
        body.put("evidence_note","仅用于隔离测试：已核对本次"+kind+"的业务内容、金额和相关凭证，记录与当前申请一致。");
        body.put("evidence_reference","隔离测试凭证登记簿 / "+kind+" / 第1条");
    }
    private static Map<String,Object> saveClaim(long id,Map<String,Object> c)throws Exception {
        Map<String,Object>b=command(id,request("CLAIM"));b.put("claim",c);return call("save",b);
    }
    private static Map<String,Object> submit(long id)throws Exception {
        Map<String,Object>b=command(id,request("SUBMIT"));proof(b,"主办单位提交");return call("submit",b);
    }
    private static Map<String,Object> approvalBody(long id)throws Exception {
        Map<String,Object>b=command(id,request("APPROVE"));b.put("decision","APPROVE");proof(b,"共享交付审核");return b;
    }
    private static Map<String,Object> approve(long id)throws Exception{return call("review",approvalBody(id));}
    private static Map<String,Object> readyClaim(long id,String grade,String research)throws Exception {
        saveClaim(id,claim(grade,research));submit(id);return approve(id);
    }
    private static Map<String,Object> financial(long id)throws Exception{return map(read(id).get("financial"));}
    private static Map<String,Object> settlementBody(long id,String op)throws Exception {
        Map<String,Object> v=read(id),b=command(id,request(op));b.put("expected_settings_version",v.get("settings_version"));
        if(op.equals("correct")){b.put("expected_snapshot_code",map(v.get("financial")).get("head_snapshot_code"));b.put("reason_note","隔离测试：重新核对授课分钟和计酬课时后，更正本次税前应付金额。");b.put("reason_reference","隔离测试更正核准记录 / 第1条");}
        return b;
    }
    private static Map<String,Object> paymentBody(long id,String amount)throws Exception {
        Map<String,Object>b=command(id,request("PAYMENT"));b.put("expected_snapshot_code",financial(id).get("head_snapshot_code"));
        b.put("payment_date",PAYMENT_DATE);b.put("amount",amount);proof(b,"银行付款回单");return b;
    }
    private static Map<String,Object> factSave(long id,String minutes,String payable)throws Exception {
        Map<String,Object>v=DeliverySettlementIntegration.read(worker,id),b=new LinkedHashMap<>();
        b.put("dispatch_id",id);b.put("expected_version",v.get("version"));b.put("request_id",request("FACT"));
        b.put("estimated_hours","5.00");b.put("planned_hours","4.00");b.put("actual_minutes",minutes);b.put("payable_hours",payable);
        return DeliverySettlementIntegration.mutate("save",worker,b);
    }
    private static Map<String,Object> factVerify(long id)throws Exception {
        Map<String,Object>v=DeliverySettlementIntegration.read(worker,id),b=new LinkedHashMap<>();
        b.put("dispatch_id",id);b.put("expected_version",v.get("version"));b.put("request_id",request("VERIFY"));b.put("evidence_code","SYNTHETIC-TEACHING-EVIDENCE");
        return DeliverySettlementIntegration.mutate("verify",worker,b);
    }
    private static void verifiedFact(long id,String minutes,String payable)throws Exception{factSave(id,minutes,payable);factVerify(id);}
    private static Configuration access(String version,Set<String> omitted){
        RelationRule optional=new RelationRule(false,Set.of("OPERATOR"),false,false);List<Grant>grants=new ArrayList<>();
        for(String resource:List.of("delivery.read","delivery.write","delivery.verify","settlement.submit","settlement.review","settlement.configure","settlement.confirm","settlement.correct","settlement.pay","settlement.export","reports.read","reports.export")) {
            if(omitted.contains(resource))continue;
            grants.add(new Grant("GRANT-"+resource,"OPERATOR",resource,resource.endsWith(".read")?Action.VIEW:resource.endsWith(".export")?Action.EXPORT:Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        }
        return new Configuration(version,new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),Set.of("OPERATOR"),
                List.of(new Organization("001",null,true),new Organization("002",null,true)),
                List.of(new Person("0002","001",Set.of(),null,null,Set.of("OPERATOR"),true),new Person("0003","002",Set.of(),null,null,Set.of("OPERATOR"),true)),
                List.of(new RoleRelations("OPERATOR",optional,optional)),List.of(new AccountBinding(2,"0002",true),new AccountBinding(3,"0003",true)),grants);
    }
    private static void publish(String expected,String version,Set<String> omitted)throws Exception{
        OrganizationAccessStore.publish(admin,expected,access(version,omitted));
        worker=Auth.get(Auth.login("formal-worker",PASSWORD));outsider=Auth.get(Auth.login("formal-outsider",PASSWORD));
    }
    private static void fixtures(Path data)throws Exception {
        System.setProperty("data.dir",data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id BIGINT PRIMARY KEY,title VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,title VARCHAR(200),status VARCHAR(32),hours DOUBLE)");
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,name VARCHAR(64),status VARCHAR(32),fee_rate DOUBLE)");
        Db.exec("CREATE TABLE dispatches(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,subject VARCHAR(200),teach_date VARCHAR(32),hours DOUBLE,status VARCHAR(32))");
        Db.exec("CREATE TABLE fees(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,hours DOUBLE,rate DOUBLE,amount DOUBLE,status VARCHAR(32))");
        String password=Auth.hash(PASSWORD);
        for(int i=1;i<=4;i++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",i,List.of("formal-admin","formal-worker","formal-outsider","formal-unbound").get(i-1),password,"SYNTHETIC FORMAL "+i,i==1?"admin":i==4?"manager":"viewer");
        admin=Auth.get(Auth.login("formal-admin",PASSWORD));worker=Auth.get(Auth.login("formal-worker",PASSWORD));
        outsider=Auth.get(Auth.login("formal-outsider",PASSWORD));unbound=Auth.get(Auth.login("formal-unbound",PASSWORD));
        check(admin!=null&&worker!=null&&outsider!=null&&unbound!=null,"real authenticated synthetic sessions");
        OrganizationAccessStore.init();WorkflowIntegration.init();NotificationChannelsIntegration.init();
        for(int i=1;i<=3;i++){
            Db.exec("INSERT INTO demands VALUES(?,?,'团队已受理')",i,"SYNTHETIC DEMAND "+i);
            Db.exec("INSERT INTO projects VALUES(?,?,?,'进行中',99)",i+9,i,"SYNTHETIC PROJECT "+i);
            Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct',?,'0002','{}','{}','SYNTHETIC-M03')",i,i==2?"002":"001");
            Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'001','0002',1,'2026-01-01T00:00:00Z')",i,i+9);
        }
        Db.exec("INSERT INTO teachers VALUES(20,'SYNTHETIC TEACHER','在库',99999)");
        for(long id=101;id<=112;id++)Db.exec("INSERT INTO dispatches VALUES(?,?,20,'SYNTHETIC SESSION',?,77,'已确认')",id,id==112?11:10,DATE);
        Db.exec("INSERT INTO dispatches VALUES(113,12,20,'SYNTHETIC SAME-ORG OTHER-PROJECT',?,77,'已确认')",DATE);
        check(Db.get().getMetaData().getURL().contains(data.resolve("training").toString()),"explicit fresh H2 path only");
    }
    public static void main(String[]args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("One fresh empty test directory is required");
        Path data=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("A real empty directory is required");
        try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Test directory must be empty");}
        fixtures(data);DeliverySettlementIntegration.init();DeliverySettlementWorkflow.init();DeliverySettlementWorkflow.init();
        rejects(403,()->read(101),"M01 absent fails closed");publish(null,"FORMAL-ACCESS-1",Set.of());
        Map<String,Object>empty=read(101);
        check(version(empty)==0&&number(empty.get("fact_version"))==0&&"DRAFT".equals(empty.get("state")),"empty workflow does not invent a claim or fact");
        check(DEFAULT_SETTINGS.equals(empty.get("settings_version")),"explicitly approved per-line money policy is the default");
        check(Db.count("m05_execution_settings")==0&&Db.count("m05_fee_claims")==0&&Db.count("m05_settlement_snapshots")==0,
                "approved read-only default seeds no settings publication, business claims or settlement records");
        check("001".equals(empty.get("organization_code")),"organization comes from existing accepted demand");
        rejects(403,()->DeliverySettlementWorkflow.read(outsider,101),"cross-organization read denied");
        rejects(403,()->DeliverySettlementWorkflow.read(admin,101),"legacy admin cannot bypass M01");
        rejects(403,()->DeliverySettlementWorkflow.read(unbound,101),"unbound legacy manager cannot bypass M01");
        rejects(403,()->read(112),"own-organization grant excludes other organization");
        rejects(409,()->saveClaim(101,claim("LECTURER","NO")),"claim cannot invent teaching fact");
        verifiedFact(101,"60","1.33");
        Map<String,Object>fact=DeliverySettlementIntegration.read(worker,101);
        decimal(map(map(fact.get("fact")).get("hours")).get("actual"),"1.33","60 teaching minutes convert once to class hours");
        Map<String,Object>initial=command(101,request("FIRST-CLAIM"));initial.put("claim",claim("LECTURER","NO"));
        rejects(403,()->DeliverySettlementWorkflow.mutate("save",outsider,copy(initial)),"cross-organization mutation denied");
        rejects(403,()->DeliverySettlementWorkflow.mutate("save",admin,copy(initial)),"legacy admin cannot mutate without exact business grant");
        String revokedToken=Auth.login("formal-worker",PASSWORD);Auth.Session revoked=Auth.get(revokedToken);Auth.logout(revokedToken);
        rejects(401,()->DeliverySettlementWorkflow.mutate("save",revoked,copy(initial)),"revoked real Auth session cannot mutate");
        Map<String,Object>first=call("save",initial);
        check(version(first)==1&&"DRAFT".equals(first.get("state")),"first claim is draft version one");
        check(number(first.get("fact_version"))==version(fact),"claim binds exact verified fact revision");
        evidenceAudit(first,initial);
        Map<String,Object>preview=map(first.get("calculation_preview"));
        decimal(preview.get("amount"),"133.00","saved current claim has server-calculated review preview");
        check(Boolean.FALSE.equals(preview.get("confirmed"))&&Db.count("m05_settlement_snapshots")==0,
                "review preview explicitly remains unconfirmed and creates no financial snapshot");
        long proofCount=Db.count("m05_evidence_records");
        check(sameResponse(first,call("save",initial)),"exact claim retry is idempotent");
        check(Db.count("m05_evidence_records")==proofCount,"exact claim retry creates no duplicate evidence records");
        Map<String,Object>changed=copy(initial);map(changed.get("claim")).put("grade","SENIOR");
        rejects(409,()->call("save",changed),"request identifier cannot change claim payload");
        Map<String,Object>stale=command(101,request("STALE-CLAIM"));stale.put("expected_version",0);stale.put("claim",claim("LECTURER","NO"));
        rejects(409,()->call("save",stale),"claim CAS rejects stale version");
        Map<String,Object>staleFact=command(101,request("STALE-FACT"));staleFact.put("expected_fact_version",1);staleFact.put("claim",claim("LECTURER","NO"));
        rejects(409,()->call("save",staleFact),"claim CAS rejects stale fact revision");
        injectedAuthorityFields();
        evidenceReuseBoundaries(first);
        check("SUBMITTED".equals(submit(101).get("state")),"organizer submits current claim");
        Map<String,Object>review=approvalBody(101);
        publish("FORMAL-ACCESS-1","FORMAL-ACCESS-2",Set.of("settlement.review"));
        rejects(403,()->call("review",review),"review requires exact current M01 resource grant");
        publish("FORMAL-ACCESS-2","FORMAL-ACCESS-3",Set.of());
        Map<String,Object>approved=call("review",review);
        check("APPROVED".equals(approved.get("state")),"shared delivery approval is persisted");
        check(sameResponse(first,call("save",initial)),"old retry preserves original result after later approval");
        Map<String,Object>wrongSettings=settlementBody(101,"confirm");wrongSettings.put("expected_settings_version","SYNTHETIC-OLD-SETTINGS");
        rejects(409,()->call("confirm",wrongSettings),"confirmation checks exact money settings version");
        Map<String,Object>confirm=settlementBody(101,"confirm"),confirmed=call("confirm",confirm);
        check("CONFIRMED".equals(confirmed.get("state")),"approved workflow confirms");
        decimal(map(confirmed.get("financial")).get("amount"),"133.00","server policy rate 100 times payable 1.33");
        check(sameResponse(confirmed,call("confirm",confirm)),"confirmation retry returns original snapshot once");
        List<Map<String,Object>> originalLedger=DeliverySettlementIntegration.ledger(worker,101);
        check(originalLedger.size()==1,"one frozen reporting contribution after confirmation");
        decimal(originalLedger.get(0).get("fee"),"133.00","frozen original ledger amount");
        String frozenPayload=Db.one("SELECT payload FROM m05_settlement_snapshots WHERE dispatch_id=101").get("payload").toString();
        rejects(409,()->call("confirm",settlementBody(101,"confirm")),"second original confirmation forbidden");
        rejects(409,()->call("correct",settlementBody(101,"correct")),"same fact revision cannot be corrected");
        researchRate();incompleteApplicabilityAndReturn();staleApproval();settingsChecks();
        check(frozenPayload.equals(Db.one("SELECT payload FROM m05_settlement_snapshots WHERE dispatch_id=101").get("payload").toString()),"later settings and other cases never recalculate original snapshot");
        correctionAndPayment(frozenPayload);
        ledgerAdjustmentChecks();
        financialProjectCoverage();
        unknownHourStateChecks();
        for(var proof:INITIAL_PROOFS.entrySet())check(proof.getValue().equals(Db.one("SELECT payload FROM m05_evidence_records WHERE evidence_code=?",proof.getKey()).get("payload").toString()),
                "registered original evidence remains immutable through correction and payment");
        publish("FORMAL-ACCESS-3","FORMAL-ACCESS-4",Set.of("settlement.submit"));
        rejects(403,()->call("save",initial),"even old successful retry rechecks current grant");
        publish("FORMAL-ACCESS-4","FORMAL-ACCESS-5",Set.of());
        String persisted=Json.write(read(101));Db.get().close();DeliverySettlementIntegration.init();DeliverySettlementWorkflow.init();
        check(persisted.equals(Json.write(read(101))),"workflow payment and frozen history survive reopen and repeat init");
        check(Db.count("fees")==0,"formal workflow creates no legacy fee rows");
        Db.exec("SHUTDOWN");System.out.println("M05FormalWorkflow: "+checks+" checks passed (isolated synthetic H2; no production data)");
    }
    private static void injectedAuthorityFields()throws Exception {
        for(String field:List.of("rate","unit_rate","amount","organization_code","teacher_id","approved","payment_status")){
            Map<String,Object>b=command(101,request("INJECT-"+field));b.put("claim",claim("LECTURER","NO"));b.put(field,"99999");
            rejects(400,()->call("save",b),"caller authority or rate injection rejected: "+field);
        }
        for(String field:List.of("rate","unit_rate","amount","policy_version","approved")){
            Map<String,Object>c=claim("LECTURER","NO");c.put(field,"99999");
            rejects(400,()->saveClaim(101,c),"nested caller rate or approval injection rejected: "+field);
        }
        Map<String,Object>unknown=claim("LECTURER","NO");map(unknown.get("evidence_notes")).remove("grade");unknown.put("evidence",Map.of("grade","SYNTHETIC-UNREGISTERED-PROOF"));
        rejects(409,()->saveClaim(101,unknown),"invented technical evidence code cannot replace an actual registered claim proof");
        Map<String,Object>submit=command(101,request("UNREGISTERED-SUBMIT"));submit.put("evidence_code","SYNTHETIC-UNREGISTERED-PROOF");
        rejects(409,()->call("submit",submit),"invented technical evidence code cannot authorize submission");
    }
    private static void evidenceAudit(Map<String,Object>saved,Map<String,Object>input)throws Exception {
        Map<String,Object>references=map(map(saved.get("claim")).get("evidence")),notes=map(map(input.get("claim")).get("evidence_notes"));
        check(references.size()==7,"server resolves all seven claim proof fields to registered references");
        for(String kind:notes.keySet()){
            Object code=references.get(kind);check(code instanceof String,"registered proof reference is server generated text");
            Map<String,Object>row=Db.one("SELECT * FROM m05_evidence_records WHERE evidence_code=?",code);
            check(row!=null&&number(row.get("project_id"))==10&&"001".equals(row.get("organization_code")),"registered proof retains current project and organization");
            Map<String,Object>payload=map(Json.parse(row.get("payload").toString())),note=map(notes.get(kind));
            check(note.get("note").equals(payload.get("note"))&&note.get("reference").equals(payload.get("reference")),"registered proof stores actual explanatory note and source reference");
            check("0002".equals(payload.get("actor_code"))&&number(payload.get("account_id"))==worker.uid&&payload.get("created_at") instanceof String,
                    "registered proof retains authenticated actor, account and timestamp");
            INITIAL_PROOFS.put(code.toString(),row.get("payload").toString());
        }
    }
    private static Map<String,Object> existingProof(Map<String,Object>claim,String field,String code){
        map(claim.get("evidence_notes")).remove(field);claim.put("evidence",Map.of(field,code));return claim;
    }
    private static void evidenceReuseBoundaries(Map<String,Object>first)throws Exception {
        String grade=map(map(first.get("claim")).get("evidence")).get("grade").toString();long count=Db.count("m05_evidence_records");
        rejects(409,()->saveClaim(101,existingProof(claim("LECTURER","NO"),"day_type",grade)),"registered grade proof cannot be relabeled as teaching day-type proof");
        rejects(409,()->saveClaim(101,existingProof(claim("SENIOR","NO"),"grade",grade)),"registered proof cannot justify a changed grade value");
        Auth.Session authorizedWorker=worker;
        try{
            worker=outsider;verifiedFact(112,"45","1.00");
            rejects(409,()->saveClaim(112,existingProof(claim("LECTURER","NO"),"grade",grade)),"otherwise-authorized second organization cannot reuse another organization's registered proof");
        }finally{worker=authorizedWorker;}
        check(Db.count("m05_evidence_records")==count,"rejected proof reuse rolls back any newly registered notes");
        factSave(101,"60","1.33");factVerify(101);
        rejects(409,()->saveClaim(101,existingProof(claim("LECTURER","NO"),"grade",grade)),"proof for previous fact revision cannot authorize a new revision with matching values");
        saveClaim(101,claim("LECTURER","NO"));
    }
    private static void researchRate()throws Exception {
        verifiedFact(102,"60","1.33");readyClaim(102,"SENIOR","YES");
        Map<String,Object>confirmed=call("confirm",settlementBody(102,"confirm"));
        decimal(map(confirmed.get("financial")).get("amount"),"133.00","research-team senior uses lecturer teaching rate 100, not personal grade rate 150");
    }
    private static void incompleteApplicabilityAndReturn()throws Exception {
        verifiedFact(103,"45","1.00");Map<String,Object>incomplete=claim("LECTURER","NO");
        incomplete.put("service_date_applicable","UNKNOWN");map(incomplete.get("evidence_notes")).remove("applicability");
        check("DRAFT".equals(saveClaim(103,incomplete).get("state")),"incomplete applicability can be saved as draft");
        check(read(103).get("calculation_preview")==null,"missing case applicability does not display a payable preview");
        submit(103);rejects(409,()->approve(103),"approval requires applicable service-date evidence for this case");
        Map<String,Object>returned=command(103,request("RETURN"));returned.put("decision","RETURN");
        rejects(400,()->call("review",returned),"return must supply explanatory evidence or reason");proof(returned,"审核退回");returned.put("reason_note","隔离测试：缺少本次授课适用性资料，退回补充后重新提交。");
        check("RETURNED".equals(call("review",returned).get("state")),"review can return incomplete claim with reason");
        check("DRAFT".equals(saveClaim(103,claim("LECTURER","NO")).get("state")),"returned case can be revised");
        submit(103);check("APPROVED".equals(approve(103).get("state")),"completed applicability evidence allows approval");
    }
    private static void staleApproval()throws Exception {
        verifiedFact(104,"45","1.00");readyClaim(104,"LECTURER","NO");
        Map<String,Object>stale=settlementBody(104,"confirm");factSave(104,"60","1.33");
        check(read(104).get("calculation_preview")==null,"changed facts invalidate displayed preview along with approval");
        rejects(409,()->call("confirm",stale),"editing facts stales exact approved revision");
        rejects(409,()->call("confirm",settlementBody(104,"confirm")),"fresh browser version cannot revive stale approval");
        factVerify(104);readyClaim(104,"LECTURER","NO");
        decimal(map(call("confirm",settlementBody(104,"confirm")).get("financial")).get("amount"),"133.00","new verified facts require a fresh claim and approval");
    }
    private static void settingsChecks()throws Exception {
        long before=version(read(101));Map<String,Object>b=command(101,request("SETTINGS"));
        b.put("expected_settings_version",read(101).get("settings_version"));
        Map<String,Object>settings=new LinkedHashMap<>(Map.of("version","SYNTHETIC-MONEY-2","amount_scale",2,"rounding_mode","HALF_UP","allow_unpaid_correction",true));proof(settings,"金额设置核准");b.put("settings",settings);
        Map<String,Object>invalid=copy(b);map(invalid.get("settings")).put("unit_rate","99999");
        rejects(400,()->call("settings",invalid),"money settings cannot replace server policy rate");
        Map<String,Object>out=call("settings",b);
        check(version(out)==before&&"SYNTHETIC-MONEY-2".equals(out.get("settings_version")),"settings version CAS is independent from claim version");
        Map<String,Object>sameOrganization=read(113);
        check("SYNTHETIC-MONEY-2".equals(sameOrganization.get("settings_version"))&&settings.get("evidence_note").equals(map(sameOrganization.get("settings")).get("evidence_note")),
                "organization-wide settings preserve readable proof when opened from a different authorized project");
        check(sameResponse(out,call("settings",b)),"settings exact retry does not duplicate publication");
        Map<String,Object>stale=copy(b);stale.put("request_id",request("STALE-SETTINGS"));map(stale.get("settings")).put("version","SYNTHETIC-MONEY-3");
        rejects(409,()->call("settings",stale),"settings compare-and-set rejects stale settings version");
    }
    private static void correctionAndPayment(String originalPayload)throws Exception {
        String originalHead=financial(101).get("head_snapshot_code").toString();
        factSave(101,"90","2.00");factVerify(101);readyClaim(101,"LECTURER","NO");
        Map<String,Object>correction=settlementBody(101,"correct"),corrected=call("correct",correction);
        check("CONFIRMED".equals(corrected.get("state")),"approved verified replacement confirms correction");
        decimal(map(corrected.get("financial")).get("amount"),"200.00","replacement total uses new exact payable hours");
        List<Map<String,Object>>ledger=DeliverySettlementIntegration.ledger(worker,101);
        check(ledger.size()==2,"correction appends one frozen ledger entry");
        decimal(ledger.get(0).get("fee"),"133.00","original contribution remains frozen");decimal(ledger.get(1).get("fee"),"67.00","correction contributes only replacement delta");
        check(originalPayload.equals(Db.one("SELECT payload FROM m05_settlement_snapshots WHERE snapshot_code=?",originalHead).get("payload").toString()),"correction does not mutate original snapshot");
        check(sameResponse(corrected,call("correct",correction))&&DeliverySettlementIntegration.ledger(worker,101).size()==2,"correction idempotency preserves single lineage");
        Map<String,Object>wrongHead=settlementBody(101,"correct");wrongHead.put("expected_snapshot_code",originalHead);
        rejects(409,()->call("correct",wrongHead),"correction cannot branch historical head");
        Map<String,Object>wrongAmount=paymentBody(101,"199.99");rejects(409,()->call("payment",wrongAmount),"payment must equal exact gross confirmed total");
        Map<String,Object>numeric=paymentBody(101,"200.00");numeric.put("amount",200);rejects(400,()->call("payment",numeric),"payment amount must be decimal text");
        Map<String,Object>future=paymentBody(101,"200.00");future.put("payment_date",LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString());
        rejects(409,()->call("payment",future),"future payment date cannot be recorded as actual");
        Map<String,Object>missingEvidence=paymentBody(101,"200.00");missingEvidence.remove("evidence_note");rejects(400,()->call("payment",missingEvidence),"payment requires actual receipt evidence note");
        Map<String,Object>wrongEvidence=paymentBody(101,"200.00");wrongEvidence.put("evidence_note",Map.of("code","SYNTHETIC-RECEIPT"));rejects(400,()->call("payment",wrongEvidence),"receipt evidence must be explanatory text");
        Map<String,Object>unregistered=paymentBody(101,"200.00");unregistered.remove("evidence_note");unregistered.remove("evidence_reference");unregistered.put("evidence_code","SYNTHETIC-UNREGISTERED-RECEIPT");
        rejects(409,()->call("payment",unregistered),"invented technical code cannot replace registered bank receipt evidence");
        Map<String,Object>staleHead=paymentBody(101,"200.00");staleHead.put("expected_snapshot_code",originalHead);rejects(409,()->call("payment",staleHead),"payment binds current confirmed snapshot");
        Map<String,Object>payment=paymentBody(101,"200.00"),paid=call("payment",payment);
        check("PAID".equals(paid.get("state"))&&Boolean.TRUE.equals(map(paid.get("financial")).get("paid")),"real payment registration changes workflow to paid");
        check(map(paid.get("financial")).get("payment") instanceof Map,"paid workflow returns persisted payment evidence");
        check(sameResponse(paid,call("payment",payment)),"payment retry does not duplicate payment");
        check(number(Db.one("SELECT COUNT(*) AS n FROM m05_payment_events WHERE chain_code='DISPATCH-101'").get("n"))==1,
                "exact retry produces one immutable payment event");
        rejects(409,()->call("payment",paymentBody(101,"200.00")),"second payment with new request is blocked");
        rejects(409,()->call("correct",settlementBody(101,"correct")),"paid settlement cannot be corrected");
        List<Map<String,Object>>paidLedger=DeliverySettlementIntegration.ledger(worker,101);
        check(paidLedger.size()==2,"payment creates no extra settlement contribution");
        BigDecimal total=BigDecimal.ZERO;for(Map<String,Object>row:paidLedger){
            total=total.add(new BigDecimal(row.get("fee").toString()));
            check("PAID".equals(row.get("payment_status"))&&PAYMENT_DATE.equals(row.get("payment_date")),"actual payment date applies to frozen contribution lineage");
        }
        check(total.compareTo(new BigDecimal("200.00"))==0,"frozen M06 contribution sum reconciles exact paid total");
    }
    private static Map<String,Object> ledgerTransaction(String resource,LedgerWork work)throws Exception {
        synchronized(Api.MUTATION_LOCK){return Db.transaction(()->{
            OrganizationAccessStore.person(worker);
            if(!OrganizationAccessStore.authorize(worker,resource,Action.HANDLE,"001").allowed())throw new Api.ApiException(403,"Synthetic ledger test requires current authorized actor");
            return work.run();
        });}
    }
    private static Map<String,Object> ledgerTotal(String day,String amount){
        Map<String,Object>total=new LinkedHashMap<>();total.put("activity","TEACHING");total.put("source_record_id",105L);total.put("source_fact_revision",null);total.put("source_code","SYNTHETIC-ADJUSTMENT-105");
        total.put("service_date",day);total.put("organization_code","001");total.put("teacher_id",20L);total.put("teacher_code",null);total.put("system_teacher_code","TEACHER-20");
        total.put("course_id",null);total.put("course_code",null);total.put("amount",amount);total.put("hours",Map.of("ESTIMATED","5.00","PLANNED","4.00","ACTUAL","1.00","PAYABLE","1.00"));
        total.put("policy_version","SYNTHETIC-LEDGER-POLICY");total.put("rate_version","SYNTHETIC-LEDGER-RATE");total.put("payroll_month",YearMonth.from(LocalDate.parse(day)).plusMonths(1).toString());return total;
    }
    private static Map<String,Object> ledgerPayment(String chain,String head,String amount)throws Exception {
        return ledgerTransaction("settlement.pay",()->DeliverySettlementLedger.payment(chain,head,PAYMENT_DATE,amount,"SYNTHETIC-ADJUSTMENT-RECEIPT","0002",worker.uid,false));
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private static void ledgerAdjustmentChecks()throws Exception {
        String chain="SYNTHETIC-ADJUSTMENT-105",first="SYNTHETIC-ACCRUAL-105-1",second="SYNTHETIC-ACCRUAL-105-2",third="SYNTHETIC-ACCRUAL-105-3";
        ledgerTransaction("settlement.confirm",()->DeliverySettlementLedger.open(10,"001",chain,ledgerTotal(DATE,"100.00"),first,"CONFIRMED","SYNTHETIC-ADJUSTMENT-APPROVAL"));
        Map<String,Object>firstPayment=ledgerPayment(chain,first,"40.00");
        decimal(DeliverySettlementLedger.balance(chain).get("paid_amount"),"40.00","partial payment is recorded independently");
        decimal(DeliverySettlementLedger.balance(chain).get("balance"),"60.00","partial payment leaves exact remaining balance");
        rejects(409,()->ledgerPayment(chain,first,"60.01"),"partial payment cannot exceed outstanding balance");
        ledgerPayment(chain,first,"60.00");
        decimal(DeliverySettlementLedger.balance(chain).get("balance"),"0.00","partial payments reconcile fully paid balance");
        ledgerTransaction("settlement.correct",()->DeliverySettlementLedger.replace(chain,first,ledgerTotal(DATE,"80.00"),second,"SYNTHETIC-REDUCTION-APPROVAL"));
        decimal(DeliverySettlementLedger.balance(chain).get("current_amount"),"80.00","paid correction freezes replacement entitlement");
        decimal(DeliverySettlementLedger.balance(chain).get("balance"),"-20.00","paid reduction creates explicit refund due");
        check(copy(firstPayment).equals(copy(DeliverySettlementLedger.entries("m05_payment_events",chain).get(0))),"paid correction keeps original cash evidence unchanged");
        rejects(409,()->ledgerPayment(chain,second,"10.00"),"refund balance rejects payment in wrong direction");
        Map<String,Object>refund=ledgerPayment(chain,second,"-20.00");
        check("REFUND".equals(refund.get("kind")),"negative cash movement is an explicit refund event");
        decimal(DeliverySettlementLedger.balance(chain).get("paid_amount"),"80.00","payments minus refund reconcile to reduced entitlement");
        decimal(DeliverySettlementLedger.balance(chain).get("balance"),"0.00","refund settles negative balance without rewriting cash history");
        String priorMonth=LocalDate.parse(DATE).minusMonths(1).toString();
        ledgerTransaction("settlement.correct",()->DeliverySettlementLedger.replace(chain,second,ledgerTotal(priorMonth,"120.00"),third,"SYNTHETIC-CROSS-PERIOD-APPROVAL"));
        List<Map<String,Object>>entries=DeliverySettlementLedger.entries("m05_financial_entries",chain);
        check(entries.size()==4,"cross-period correction appends reversal and rebooking entries");
        check("REVERSAL".equals(entries.get(2).get("kind"))&&"REBOOK".equals(entries.get(3).get("kind")),"cross-period correction has explicit reversal then rebooking");
        decimal(entries.get(2).get("amount"),"-80.00","old period is reversed exactly");decimal(entries.get(3).get("amount"),"120.00","replacement is booked in new service period");
        check(DATE.equals(entries.get(2).get("service_date"))&&priorMonth.equals(entries.get(3).get("service_date")),"reversal and rebooking retain distinct service dates");
        check(entries.get(2).get("correction_group_code")!=null&&entries.get(2).get("correction_group_code").equals(entries.get(3).get("correction_group_code")),"paired cross-period entries carry shared correction group");
        check(entries.get(2).get("entry_code").equals(entries.get(3).get("previous_entry_code")),"rebooking follows exact reversal in append-only lineage");
        decimal(DeliverySettlementLedger.balance(chain).get("balance"),"40.00","rebooking adds only remaining balance after prior refund");
        damagedLedgerChecks(chain,third);
        ledgerPayment(chain,third,"40.00");
        Map<String,Object>source=DeliverySettlementIntegration.financialSource(worker,10,false);
        check("M05-FINANCIAL-1".equals(source.get("schema_version")),"M06 source exposes declared financial contract");
        BigDecimal accrual=BigDecimal.ZERO,cash=BigDecimal.ZERO;int accrualCount=0,cashCount=0;
        for(Map<String,Object>entry:rows(source.get("accrual_entries")))if(chain.equals(entry.get("chain_code"))){accrual=accrual.add(new BigDecimal(entry.get("amount").toString()));accrualCount++;}
        for(Map<String,Object>entry:rows(source.get("payment_entries")))if(chain.equals(entry.get("chain_code"))){cash=cash.add(new BigDecimal(entry.get("amount").toString()));cashCount++;check(PAYMENT_DATE.equals(entry.get("payment_date")),"cash projection preserves actual payment dates across service-period correction");}
        check(accrualCount==4&&cashCount==4&&accrual.compareTo(new BigDecimal("120.00"))==0&&cash.compareTo(accrual)==0,"M06 accrual and cash histories reconcile independently without duplicate gross amount");
        check(source.get("source_version").equals(DeliverySettlementIntegration.financialSource(worker,10,true).get("source_version")),"read and authorized export share frozen financial version");
        Map<String,Object>overview=DeliverySettlementIntegration.projectSettlement(worker,10);
        decimal(overview.get("known_confirmed_amount"),"586.00","project overview sums current entitlements after paid and cross-period corrections");
        decimal(overview.get("known_paid_amount"),"320.00","project overview sums independent cash movements including refund");
        decimal(overview.get("known_balance"),"266.00","project overview shows only actual outstanding ledger balance");
        check("INCOMPLETE".equals(overview.get("status"))&&overview.get("confirmed_amount")==null&&number(overview.get("missing_claim_count"))>0,
                "missing financial claims remain explicit and never become zero payable");
        check(Boolean.FALSE.equals(overview.get("can_archive")),"unfinished teaching and missing claims block archival");
        rejects(409,()->DeliverySettlementIntegration.guardLegacyArchive(10),"legacy archive checks actual teaching and financial completeness");
        rejects(403,()->DeliverySettlementIntegration.financialSource(outsider,10,false),"financial projection retains organization scope");
        rejects(403,()->DeliverySettlementIntegration.financialSource(admin,10,false),"financial projection has no legacy admin bypass");
    }
    private static void corruptPayload(String table,String keyColumn,Object key,Map<String,Object>damage,String chain,String head,String label)throws Exception {
        Map<String,Object>original=Db.one("SELECT payload FROM "+table+" WHERE "+keyColumn+"=?",key);
        try{
            Db.exec("UPDATE "+table+" SET payload=? WHERE "+keyColumn+"=?",Json.write(damage),key);
            rejects(409,()->DeliverySettlementIntegration.financialSource(worker,10,false),label+" fails closed for reporting");
            rejects(409,()->ledgerPayment(chain,head,"1.00"),label+" fails closed before payment");
        }finally{Db.exec("UPDATE "+table+" SET payload=? WHERE "+keyColumn+"=?",original.get("payload").toString(),key);}
    }
    private static void damagedLedgerChecks(String chain,String head)throws Exception {
        Map<String,Object>headRow=Db.one("SELECT payload FROM m05_financial_chains WHERE chain_code=?",chain),badHead=map(Json.parse(headRow.get("payload").toString()));
        badHead.put("current_amount","121.00");corruptPayload("m05_financial_chains","chain_code",chain,badHead,chain,head,"damaged head amount");
        Map<String,Object>last=Db.one("SELECT * FROM m05_financial_entries WHERE entry_code=?",head);
        try{
            Db.exec("UPDATE m05_financial_entries SET sequence_no=99 WHERE entry_code=?",head);
            rejects(409,()->DeliverySettlementIntegration.financialSource(worker,10,false),"gapped accrual sequence fails closed for reporting");
            rejects(409,()->ledgerPayment(chain,head,"1.00"),"gapped accrual sequence fails closed before payment");
        }finally{Db.exec("UPDATE m05_financial_entries SET sequence_no=? WHERE entry_code=?",last.get("sequence_no"),head);}
        try{
            Db.exec("DELETE FROM m05_financial_entries WHERE entry_code=?",head);
            rejects(409,()->DeliverySettlementIntegration.financialSource(worker,10,false),"missing accrual entry fails closed for reporting");
            rejects(409,()->ledgerPayment(chain,head,"1.00"),"missing accrual entry fails closed before payment");
        }finally{Db.exec("INSERT INTO m05_financial_entries VALUES(?,?,?,?)",last.get("entry_code"),last.get("chain_code"),last.get("sequence_no"),last.get("payload").toString());}
        Map<String,Object>payment=Db.one("SELECT entry_code,payload FROM m05_payment_events WHERE chain_code=? AND sequence_no=1",chain);
        Map<String,Object>badChain=map(Json.parse(payment.get("payload").toString()));badChain.put("chain_code","SYNTHETIC-WRONG-CHAIN");
        corruptPayload("m05_payment_events","entry_code",payment.get("entry_code"),badChain,chain,head,"payment chain identity tampering");
        Map<String,Object>badLink=map(Json.parse(payment.get("payload").toString()));badLink.put("accrual_entry_codes",List.of("SYNTHETIC-MISSING-ACCRUAL"));
        corruptPayload("m05_payment_events","entry_code",payment.get("entry_code"),badLink,chain,head,"payment accrual link tampering");
        List<Map<String,Object>>payments=Db.query("SELECT * FROM m05_payment_events WHERE chain_code=? ORDER BY sequence_no",chain);
        for(List<Map<String,Object>>removed:List.of(List.of(payments.get(payments.size()-1)),payments)){
            String label=removed.size()==1?"missing latest payment":"missing all payments";
            try{
                for(Map<String,Object>row:removed)Db.exec("DELETE FROM m05_payment_events WHERE entry_code=?",row.get("entry_code"));
                rejects(409,()->DeliverySettlementIntegration.financialSource(worker,10,false),label+" fails closed for reporting");
                rejects(409,()->ledgerPayment(chain,head,"1.00"),label+" fails closed before payment");
            }finally{
                for(Map<String,Object>row:removed)Db.exec("INSERT INTO m05_payment_events VALUES(?,?,?,?)",row.get("entry_code"),row.get("chain_code"),row.get("sequence_no"),row.get("payload").toString());
            }
        }
        check(DeliverySettlementLedger.entries("m05_payment_events",chain).size()==3,"all rejected damaged-chain payments leave cash history unchanged");
        decimal(DeliverySettlementLedger.balance(chain).get("balance"),"40.00","restoring synthetic corruption preserves exact authorized balance");
    }
    private static void financialProjectCoverage()throws Exception {
        check(DeliverySettlementIntegration.financialProjectIds(worker,"001").equals(List.of(10L,12L)),"authorized financial project inventory covers current accepted projects");
        rejects(403,()->DeliverySettlementIntegration.financialProjectIds(worker,"002"),"financial project inventory rejects cross-organization selection");
        String before=DeliverySettlementIntegration.financialSource(worker,10,false).get("source_version").toString();
        synchronized(Api.MUTATION_LOCK){Db.transaction(()->{
            Map<String,Object>acceptance=Db.one("SELECT * FROM workflow_acceptances WHERE project_id=10");
            try{
                Db.exec("DELETE FROM workflow_acceptances WHERE project_id=10");
                check(DeliverySettlementIntegration.financialProjectIds(worker,"001").contains(10L),"frozen ledger keeps project visible if live acceptance source is missing");
                rejects(409,()->DeliverySettlementIntegration.financialSource(worker,10,false),"missing live acceptance fails explicitly instead of silently dropping financial history");
            }finally{
                Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,?,?,?,?)",acceptance.get("demand_id"),acceptance.get("project_id"),acceptance.get("team_code"),acceptance.get("actor_code"),acceptance.get("data_revision"),acceptance.get("created_at"));
            }
            return null;
        });}
        check(before.equals(DeliverySettlementIntegration.financialSource(worker,10,false).get("source_version")),"restored synthetic acceptance leaves frozen financial projection unchanged");
    }
    private static Map<String,Object> hourStateTotal(String day,String estimated){
        Map<String,Object>total=ledgerTotal(day,"10.00");total.put("source_record_id",113L);total.put("source_code","DISPATCH-113");
        Map<String,Object>hours=new LinkedHashMap<>(map(total.get("hours")));hours.put("ESTIMATED",estimated);total.put("hours",hours);return total;
    }
    private static void damagedHours(String table,String keyColumn,String key,Map<String,Object>damage,String chain,String head,String label)throws Exception {
        String original=Db.one("SELECT payload FROM "+table+" WHERE "+keyColumn+"=?",key).get("payload").toString();
        try{
            Db.exec("UPDATE "+table+" SET payload=? WHERE "+keyColumn+"=?",Json.write(damage),key);
            rejects(409,()->DeliverySettlementLedger.head(chain),label+" fails closed for ledger head");
            rejects(409,()->DeliverySettlementIntegration.financialSource(worker,12,false),label+" fails closed for M06 financial projection");
            rejects(409,()->ledgerPayment(chain,head,"1.00"),label+" fails closed before cash registration");
        }finally{Db.exec("UPDATE "+table+" SET payload=? WHERE "+keyColumn+"=?",original,key);}
    }
    private static void unknownHourStateChecks()throws Exception {
        String chain="SYNTHETIC-HOURS-113",first="SYNTHETIC-HOURS-113-1",second="SYNTHETIC-HOURS-113-2",third="SYNTHETIC-HOURS-113-3",fourth="SYNTHETIC-HOURS-113-4";
        ledgerTransaction("settlement.confirm",()->DeliverySettlementLedger.open(12,"001",chain,hourStateTotal(DATE,null),first,"CONFIRMED","SYNTHETIC-HOUR-STATE-APPROVAL"));
        Map<String,Object>opening=DeliverySettlementLedger.entries("m05_financial_entries",chain).get(0);
        decimal(map(opening.get("hours_before")).get("ESTIMATED"),"0","original entry starts from zero hours");
        check(map(opening.get("hours_after")).get("ESTIMATED")==null&&map(opening.get("hours")).get("ESTIMATED")==null,"original unknown hours remain explicit in after-state and contribution");
        ledgerTransaction("settlement.correct",()->DeliverySettlementLedger.replace(chain,first,hourStateTotal(DATE,"2.00"),second,"SYNTHETIC-HOUR-STATE-APPROVAL"));
        Map<String,Object>known=DeliverySettlementLedger.entries("m05_financial_entries",chain).get(1);
        check(map(known.get("hours_before")).get("ESTIMATED")==null&&map(known.get("hours")).get("ESTIMATED")==null,"unknown-to-known adjustment retains unknown prior value and non-computable signed delta");
        decimal(map(known.get("hours_after")).get("ESTIMATED"),"2.00","unknown-to-known same-day correction records exact known after-state");
        decimal(map(map(DeliverySettlementLedger.head(chain).get("total")).get("hours")).get("ESTIMATED"),"2.00","head matches known after-state even when signed delta is null");
        for(String field:List.of("hours_before","hours_after","hours")){
            Map<String,Object>damaged=copy(known);map(damaged.get(field)).put("ESTIMATED",field.equals("hours_before")?"0.00":field.equals("hours_after")?"3.00":"2.00");
            damagedHours("m05_financial_entries","entry_code",second,damaged,chain,second,"tampered "+field+" on unknown-to-known transition");
        }
        Map<String,Object>badHead=DeliverySettlementLedger.head(chain);map(map(badHead.get("total")).get("hours")).put("ESTIMATED","3.00");
        damagedHours("m05_financial_chains","chain_code",chain,badHead,chain,second,"head hours differ from known after-state");
        ledgerTransaction("settlement.correct",()->DeliverySettlementLedger.replace(chain,second,hourStateTotal(DATE,null),third,"SYNTHETIC-HOUR-STATE-APPROVAL"));
        Map<String,Object>unknown=DeliverySettlementLedger.entries("m05_financial_entries",chain).get(2);
        decimal(map(unknown.get("hours_before")).get("ESTIMATED"),"2.00","known-to-unknown transition preserves exact prior known hours");
        check(map(unknown.get("hours_after")).get("ESTIMATED")==null&&map(unknown.get("hours")).get("ESTIMATED")==null,"known-to-unknown transition records null after-state and null delta");
        check(map(map(DeliverySettlementLedger.head(chain).get("total")).get("hours")).get("ESTIMATED")==null,"head retains explicit unknown after-state");
        Map<String,Object>badUnknownHead=DeliverySettlementLedger.head(chain);map(map(badUnknownHead.get("total")).get("hours")).put("ESTIMATED","0.00");
        damagedHours("m05_financial_chains","chain_code",chain,badUnknownHead,chain,third,"unknown head hours replaced with zero");
        String anotherDay=LocalDate.parse(DATE).minusDays(1).toString();
        ledgerTransaction("settlement.correct",()->DeliverySettlementLedger.replace(chain,third,hourStateTotal(anotherDay,"3.00"),fourth,"SYNTHETIC-HOUR-STATE-APPROVAL"));
        List<Map<String,Object>>entries=DeliverySettlementLedger.entries("m05_financial_entries",chain);Map<String,Object>reverse=entries.get(3),rebook=entries.get(4);
        check("REVERSAL".equals(reverse.get("kind"))&&"REBOOK".equals(rebook.get("kind")),"unknown-hour date move still appends explicit reversal and rebooking");
        check(map(reverse.get("hours_before")).get("ESTIMATED")==null&&map(reverse.get("hours")).get("ESTIMATED")==null,"cross-day reversal preserves unknown prior state and unknown signed delta");
        decimal(map(reverse.get("hours_after")).get("ESTIMATED"),"0","cross-day reversal closes unknown prior hours to explicit zero");
        decimal(map(rebook.get("hours_before")).get("ESTIMATED"),"0","cross-day rebooking begins from explicit zero");
        decimal(map(rebook.get("hours_after")).get("ESTIMATED"),"3.00","cross-day rebooking preserves known new-period hours");
        decimal(map(rebook.get("hours")).get("ESTIMATED"),"3.00","cross-day rebooking contribution uses exact new-period hours");
        Map<String,Object>source=DeliverySettlementIntegration.financialSource(worker,12,false);Set<String>keys=Set.of("ESTIMATED","PLANNED","ACTUAL","PAYABLE");
        check(rows(source.get("accrual_entries")).size()==5,"M06 receives all five explicit hour-state transitions");
        for(Map<String,Object>entry:rows(source.get("accrual_entries")))check(map(entry.get("hours_before")).keySet().equals(keys)&&map(entry.get("hours_after")).keySet().equals(keys),"M06 projection includes all four before and after hour states");
        decimal(map(map(DeliverySettlementLedger.head(chain).get("total")).get("hours")).get("ESTIMATED"),"3.00","final head exactly reconciles known rebooked hours");
    }
}
