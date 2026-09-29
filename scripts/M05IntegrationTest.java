package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static com.training.OrganizationAccess.*;
import static com.training.DeliverySettlementPolicy.*;
import static com.training.DeliverySettlementIntegration.PolicyPreviewContext;
import static com.training.DeliverySettlementIntegration.PolicyEvidence;
import static com.training.DeliverySettlementIntegration.PolicyEvidenceProvider;

/** Synthetic identities and caller-owned empty H2 directory only. Never calls Db.init(). */
public final class M05IntegrationTest {
    /** Continue authorization checks with fresh sessions after identity publication. */
    private static void publishAccess(String expected,Configuration configuration) throws Exception {
        OrganizationAccessStore.publish(admin,expected,configuration);
        if(Auth.current(worker)==null){workerToken=Auth.login("synthetic-m05-2",PASSWORD);worker=Auth.get(workerToken);}
        if(Auth.current(outsider)==null)outsider=Auth.get(Auth.login("synthetic-m05-3",PASSWORD));
    }
    private static int checks;
    private static final String PASSWORD = "SYNTHETIC-M05-INTEGRATION-ONLY";
    private static final String DATE = LocalDate.now().minusDays(1).toString();
    private static Auth.Session admin, worker, outsider, unbound;
    private static String adminToken, workerToken;
    private static final Map<String,Object> DIMS = Map.of("grade","LECTURER","time_band","WORKDAY","form","TEACHING","hour_unit","CLASS45");
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void check(boolean value,String message) { checks++; if(!value) throw new AssertionError(message); }
    private static void rejects(int code,Work work,String message) throws Exception {
        try { work.run(); throw new AssertionError("Expected rejection: " + message); }
        catch(Api.ApiException e) { check(e.code==code,message+" status="+e.code+": "+e.getMessage()); }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> snapshots(Map<String,Object> view) { return (List<Map<String,Object>>)view.get("snapshots"); }
    private static Map<String,Object> copy(Map<String,Object> value) { return map(Json.parse(Json.write(value))); }
    // Normalize JSON numeric representation; only the explicitly live capabilities overlay is excluded.
    // Persisted business versions, decimal strings, snapshots and every other response field remain exact.
    private static boolean sameResponse(Map<String,Object> a,Map<String,Object> b) {
        Map<String,Object> left=copy(a),right=copy(b);left.remove("capabilities");right.remove("capabilities");return left.equals(right);
    }
    private static long number(Object value) { return new BigDecimal(value.toString()).longValueExact(); }
    private static long version(Map<String,Object> view) { return number(view.get("version")); }
    private static Map<String,Object> capabilities(Map<String,Object> view,long currentVersion) {
        check(view.get("capabilities") instanceof Map,"response has server capabilities");Map<String,Object> caps=map(view.get("capabilities"));
        check(Boolean.TRUE.equals(caps.get("current_server"))&&number(caps.get("fact_version"))==currentVersion,"capability overlay identifies current server fact revision");
        check(caps.get("permissions") instanceof Map&&caps.get("reasons") instanceof Map,"capabilities separate real permissions from readiness reasons");
        for(String action:List.of("save","verify","complete")) {
            Object permitted=map(caps.get("permissions")).get(action),allowed=caps.get("can_"+action),rawReasons=map(caps.get("reasons")).get(action);
            check(permitted instanceof Boolean&&allowed instanceof Boolean&&rawReasons instanceof List,"typed capability permission decision and reasons for "+action);
            List<?> reasons=(List<?>)rawReasons;check(Boolean.TRUE.equals(allowed)?reasons.isEmpty():!reasons.isEmpty(),"readiness reasons explain exactly blocked capability: "+action);
            for(Object reason:reasons)check(reason instanceof Map&&map(reason).get("code") instanceof String&& !map(reason).get("code").toString().isBlank()&&map(reason).get("message") instanceof String&&!map(reason).get("message").toString().isBlank(),"blocked capability has code and readable explanation: "+action);
        }
        return caps;
    }
    private static void capabilityState(Map<String,Object> view,long currentVersion,boolean save,boolean verify,boolean complete,String label) {
        Map<String,Object> caps=capabilities(view,currentVersion);check(Boolean.valueOf(save).equals(caps.get("can_save"))&&Boolean.valueOf(verify).equals(caps.get("can_verify"))&&Boolean.valueOf(complete).equals(caps.get("can_complete")),label);
    }
    private static void decimal(Object value,String expected,String label) {
        check(value instanceof String,label+" remains decimal text");
        check(new BigDecimal(value.toString()).compareTo(new BigDecimal(expected))==0,label+" expected="+expected+" actual="+value);
    }
    private static Map<String,Object> command(long id,long expected,String request) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("dispatch_id",id);result.put("expected_version",expected);result.put("request_id",request);return result;
    }
    private static Map<String,Object> command(Map<String,Object> view,String request) { return command(number(view.get("dispatch_id")),version(view),request); }
    private static Map<String,Object> call(String operation,Map<String,Object> body) throws Exception { return DeliverySettlementIntegration.mutate(operation,worker,copy(body)); }
    private static Map<String,Object> read(long id) throws Exception { return DeliverySettlementIntegration.read(worker,id); }
    private static Map<String,Object> save(long id,String request,String estimated,String planned,String minutes,String payable) throws Exception {
        Map<String,Object> body=command(read(id),request);body.put("estimated_hours",estimated);body.put("planned_hours",planned);body.put("actual_minutes",minutes);body.put("payable_hours",payable);body.put("dimensions",DIMS);return call("save",body);
    }
    private static Map<String,Object> verify(long id,String request) throws Exception {
        Map<String,Object> body=command(read(id),request);body.put("evidence_code","SYNTHETIC-EVIDENCE-"+request);return call("verify",body);
    }
    private static Map<String,Object> config(String v,String rate) {
        Map<String,Object> rule=new LinkedHashMap<>();rule.put("version","RULE-"+v);rule.put("evidence","SYNTHETIC-RULE-"+v);
        rule.put("effective_from","2000-01-01");rule.put("effective_until",null);rule.put("date_basis","SERVICE_DATE");rule.put("formula","PAYABLE_HOURS_TIMES_RATE");
        rule.put("currency","CNY");rule.put("amount_scale",2);rule.put("rounding_mode","HALF_UP");rule.put("rounding_scope","PER_LINE");rule.put("minutes_per_class_hour",45);
        Map<String,Object> prices=new LinkedHashMap<>();prices.put("version","RATE-"+v);prices.put("evidence","SYNTHETIC-RATE-"+v);prices.put("rule_version","RULE-"+v);
        prices.put("effective_from","2000-01-01");prices.put("effective_until",null);prices.put("dimensions",DIMS);prices.put("unit_rate",rate);
        Map<String,Object> correction=new LinkedHashMap<>();correction.put("version","CORRECTION-"+v);correction.put("evidence","SYNTHETIC-CORRECTION-"+v);
        correction.put("effective_from","2000-01-01");correction.put("effective_until",null);correction.put("date_basis","SERVICE_DATE");correction.put("method","REPLACEMENT_DELTA");
        Map<String,Object> c=new LinkedHashMap<>();c.put("version",v);c.put("publication_evidence","SYNTHETIC-PUBLICATION-"+v);c.put("eligibility_evidence","SYNTHETIC-ELIGIBILITY-"+v);
        c.put("approval_status","APPROVED");c.put("rule",rule);c.put("rate",prices);c.put("correction",correction);return c;
    }
    private static Map<String,Object> configure(long id,String request,String expected,String v,String rate) throws Exception {
        Map<String,Object> body=command(read(id),request);body.put("expected_config_version",expected);body.put("configuration",config(v,rate));return call("configure",body);
    }
    private static Configuration access(String version,Set<String> omitted) {
        RelationRule optional=new RelationRule(false,Set.of("OPERATOR"),false,false);List<Grant> grants=new ArrayList<>();
        for(String resource:List.of("delivery.read","delivery.write","delivery.verify","settlement.configure","settlement.confirm","settlement.correct","settlement.export"))
            if(!omitted.contains(resource)) grants.add(new Grant("GRANT-"+resource,"OPERATOR",resource,resource.equals("delivery.read")?Action.VIEW:resource.equals("settlement.export")?Action.EXPORT:Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        return new Configuration(version,new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),Set.of("OPERATOR"),
            List.of(new Organization("001",null,true),new Organization("002",null,true)),
            List.of(new Person("0002","001",Set.of(),null,null,Set.of("OPERATOR"),true),new Person("0003","002",Set.of(),null,null,Set.of("OPERATOR"),true)),
            List.of(new RoleRelations("OPERATOR",optional,optional)),List.of(new AccountBinding(2,"0002",true),new AccountBinding(3,"0003",true)),grants);
    }
    private static void fixtures(Path data) throws Exception {
        System.setProperty("data.dir",data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id BIGINT PRIMARY KEY,title VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,title VARCHAR(200),status VARCHAR(32),hours DOUBLE)");
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,name VARCHAR(64),status VARCHAR(32),fee_rate DOUBLE)");
        Db.exec("CREATE TABLE dispatches(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,subject VARCHAR(200),teach_date VARCHAR(32),hours DOUBLE,status VARCHAR(32))");
        Db.exec("CREATE TABLE fees(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,hours DOUBLE,rate DOUBLE,amount DOUBLE,status VARCHAR(32))");
        String hash=Auth.hash(PASSWORD);
        for(int i=1;i<=4;i++) Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",i,"synthetic-m05-"+i,hash,"SYNTHETIC M05 "+i,i==1?"admin":i==4?"manager":"viewer");
        adminToken=Auth.login("synthetic-m05-1",PASSWORD);admin=Auth.get(adminToken);
        workerToken=Auth.login("synthetic-m05-2",PASSWORD);worker=Auth.get(workerToken);
        outsider=Auth.get(Auth.login("synthetic-m05-3",PASSWORD));unbound=Auth.get(Auth.login("synthetic-m05-4",PASSWORD));
        check(admin!=null&&worker!=null&&outsider!=null&&unbound!=null,"fixtures use real Auth sessions");
        OrganizationAccessStore.init();WorkflowIntegration.init();NotificationChannelsIntegration.init();
        for(long id:List.of(1L,2L,3L,4L)) Db.exec("INSERT INTO demands VALUES(?,?,?)",id,"SYNTHETIC DEMAND "+id,"团队已受理");
        for(long id:List.of(10L,11L,12L,13L)) Db.exec("INSERT INTO projects VALUES(?,?,?,'进行中',99)",id,id-9,"SYNTHETIC PROJECT "+id);
        Db.exec("INSERT INTO teachers VALUES(20,'SYNTHETIC TEACHER','在库',99999)");Db.exec("INSERT INTO teachers VALUES(21,'SYNTHETIC ALTERNATE','在库',88888)");
        for(long demand:List.of(1L,2L,4L)) {
            Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct',?,'0002','{}','{}','SYNTHETIC-M03')",demand,demand==2?"002":"001");
            Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'001','0002',1,'2026-01-01T00:00:00Z')",demand,demand+9);
        }
        for(long id:List.of(101L,102L,103L,104L,105L,106L,107L,108L,109L)) {
            long project=id==103?11:id==104?12:id==106?13:10;
            Db.exec("INSERT INTO dispatches VALUES(?,?,20,'SYNTHETIC SESSION',?,77,'已确认')",id,project,id==107?LocalDate.now().plusDays(1).toString():DATE);
        }
        Db.exec("INSERT INTO fees VALUES(201,13,20,77,99999,7699923,'待发放')");Db.exec("INSERT INTO fees VALUES(202,12,20,1,100,100,'待发放')");
        check(Db.get().getMetaData().getURL().contains(data.resolve("training").toString()),"H2 uses the explicitly supplied isolated path");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("One fresh empty test directory is required");
        Path data=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isDirectory(data)||Files.isSymbolicLink(data)) throw new IllegalArgumentException("Test directory must already exist and must not be a symlink");
        try(var files=Files.list(data)) { if(files.findAny().isPresent()) throw new IllegalArgumentException("Test directory must be empty"); }
        fixtures(data);DeliverySettlementIntegration.init();DeliverySettlementIntegration.init();
        rejects(403,()->read(101),"missing M01 configuration fails closed");
        publishAccess(null,access("ACCESS-1",Set.of()));
        Map<String,Object> empty=read(101);
        check(version(empty)==0&&empty.get("fact")==null&&empty.get("config_version")==null&&snapshots(empty).isEmpty(),"init seeds neither teaching facts nor policy nor settlement");
        check("001".equals(empty.get("organization_code")),"organization comes from workflow acceptance and demand");
        capabilityState(empty,0,true,false,false,"empty scoped teaching draft can be saved but cannot be verified or completed");
        check("viewer".equals(worker.role)&&map(map(empty.get("capabilities")).get("permissions")).values().stream().allMatch(Boolean.TRUE::equals),"old viewer account receives action permissions solely from current M01 grants");
        rejects(403,()->DeliverySettlementIntegration.read(outsider,101),"cross organization read denied");
        rejects(403,()->DeliverySettlementIntegration.read(admin,101),"old admin does not imply business grant");
        rejects(403,()->DeliverySettlementIntegration.read(unbound,101),"old manager without M01 binding denied");
        rejects(409,()->read(104),"legacy project has no inferred M01 organization");
        Map<String,Object> badNumber=command(101,0,"bad-decimal");badNumber.put("actual_minutes",60);
        rejects(400,()->call("save",badNumber),"JSON numeric hours are not exact decimal input");
        for(String field:List.of("estimated_hours","planned_hours","payable_hours")) {
            Map<String,Object> numeric=command(101,0,"numeric-"+field);numeric.put(field,1.25);
            rejects(400,()->call("save",numeric),"all hour categories require decimal strings: "+field);
        }
        for(String field:List.of("organization_code","teacher_id","project_id","uid","verified","actual_hours")) {
            Map<String,Object> injection=command(101,0,"inject-"+field);injection.put(field,field.equals("organization_code")?"001":1);
            rejects(400,()->call("save",injection),"client authority field rejected: "+field);
        }
        Map<String,Object> initial=command(101,0,"draft-initial");initial.put("estimated_hours","5.00");initial.put("planned_hours","4.00");initial.put("actual_minutes","60");initial.put("payable_hours",null);initial.put("dimensions",DIMS);
        Map<String,Object> first=call("save",initial);
        check(version(first)==1&&number(map(first.get("fact")).get("revision"))==1,"initial fact revision is 1");
        Map<String,Object> hours=map(map(first.get("fact")).get("hours"));decimal(hours.get("actual"),"1.33","60 minutes becomes 1.33 class hours");
        decimal(hours.get("estimated"),"5.00","estimated remains separate");decimal(hours.get("planned"),"4.00","planned remains separate");
        check(hours.get("payable")==null&&first.get("config_version")==null,"draft needs neither payable hours nor pricing configuration");
        capabilityState(first,1,true,true,false,"saved actual minutes permit verification but unreviewed fact cannot complete");
        rejectedConfiguration("configure-before-teaching-verification",409,config("CFG-BEFORE-VERIFICATION","100"));
        Map<String,Object> conversion=map(first.get("conversion"));check("HALF_UP".equals(conversion.get("class_hour_rounding"))&&number(conversion.get("minutes_per_class_hour"))==45,"conversion basis is explicit");
        check("NOT_CONFIGURED".equals(DeliverySettlementIntegration.preview(worker,101).get("status")),"old teacher fee_rate cannot make preview ready");
        Map<String,Object> verified=verify(101,"verify-without-policy");
        check(version(verified)==2&&map(verified.get("fact")).get("verification") instanceof Map,"teaching verification is independent of payable hours and fee policy");
        capabilityState(verified,2,true,true,true,"reviewed actual teaching can complete without payable hours or pricing policy");
        DeliverySettlementIntegration.requireCompletion(worker,101,version(verified));check(true,"completion allowed from verified actual teaching without fee policy");
        rejects(409,()->DeliverySettlementIntegration.requireCompletion(worker,101,1),"completion checks fact version");
        Map<String,Object> replayed=call("save",initial);
        check(sameResponse(first,replayed)&&version(replayed)==1,"idempotent retry returns original revision and payload after later verification");
        capabilityState(replayed,version(read(101)),true,true,true,"old save replay keeps original business result but reports current reviewed capability");
        Map<String,Object> conflict=copy(initial);conflict.put("actual_minutes","90");rejects(409,()->call("save",conflict),"same actor request cannot change payload");
        Map<String,Object> stale=command(101,1,"save-stale");stale.put("actual_minutes","45");rejects(409,()->call("save",stale),"save CAS rejects stale revision");
        Map<String,Object> confirmMissing=command(verified,"confirm-unconfigured");confirmMissing.put("expected_config_version",null);
        rejects(409,()->call("confirm",confirmMissing),"formal settlement requires explicit approved pricing");
        Map<String,Object> edited=save(101,"draft-payable","5.00","4.00","60","1.25");
        check(version(edited)==3&&map(edited.get("fact")).get("verification")==null,"every save advances revision and clears verification");
        capabilityState(edited,3,true,true,false,"dirty save clears completion capability along with teaching verification");
        rejectedConfiguration("configure-after-dirty-fact-save",409,config("CFG-DIRTY-FACT","100"));
        rejects(409,()->DeliverySettlementIntegration.requireCompletion(worker,101,3),"edited teaching needs new verification before completion");
        Map<String,Object> nullEdit=command(edited,"draft-clear");nullEdit.put("actual_minutes",null);nullEdit.put("payable_hours",null);
        Map<String,Object> cleared=call("save",nullEdit);check(map(map(cleared.get("fact")).get("hours")).get("actual")==null&&map(map(cleared.get("fact")).get("hours")).get("payable")==null,"explicit null clears stored hour values");
        capabilityState(cleared,version(cleared),true,false,false,"saved draft without actual minutes has blocked verification and completion with reasons");
        rejectedConfiguration("configure-without-actual-minutes",409,config("CFG-WITHOUT-MINUTES","100"));
        Map<String,Object> missingActual=command(cleared,"verify-no-actual");missingActual.put("evidence_code","SYNTHETIC-MISSING");rejects(409,()->call("verify",missingActual),"cannot verify absent actual teaching");
        save(101,"restore-hours","5.00","4.00","60","1.25");verified=verify(101,"verify-restored");
        verifyGates();concurrentCas();legacyGuards();projectHoursAndBoundaries();transactionRollback();
        Map<String,Object> badConfig=command(read(101),"bad-config");badConfig.put("expected_config_version",null);Map<String,Object> unapproved=config("CFG-BAD","100");unapproved.put("approval_status","DRAFT");badConfig.put("configuration",unapproved);
        rejects(400,()->call("configure",badConfig),"consultation or unapproved rules cannot be published as payable policy");
        Map<String,Object> configured=configure(101,"configure-one",null,"CFG-1","100");
        check("CFG-1".equals(configured.get("config_version")),"explicit configuration persists with version");
        configurationBoundaries();
        Map<String,Object> preview=DeliverySettlementIntegration.preview(worker,101);check("READY".equals(preview.get("status")),"verified actual and explicit payable/configuration produce ready preview");decimal(preview.get("amount"),"125.00","fee uses payable hours and approved rate");
        Map<String,Object> wrongConfig=command(read(101),"confirm-stale-config");wrongConfig.put("expected_config_version","CFG-OLD");rejects(409,()->call("confirm",wrongConfig),"confirmation binds expected policy version");
        Db.exec("INSERT INTO fees VALUES(203,10,20,1,1,1,'待发放')");
        Map<String,Object> blockedLegacy=command(read(101),"confirm-old-fees");blockedLegacy.put("expected_config_version","CFG-1");
        rejects(409,()->call("confirm",blockedLegacy),"existing same-project teacher legacy fees require explicit migration");Db.exec("DELETE FROM fees WHERE id=203");
        Map<String,Object> confirm=command(read(101),"confirm-one");confirm.put("expected_config_version","CFG-1");Map<String,Object> confirmed=call("confirm",confirm);
        check(snapshots(confirmed).size()==1,"one confirmation creates one snapshot");Map<String,Object> original=copy(snapshots(confirmed).get(0));String originalJson=Json.write(original);
        decimal(original.get("amount"),"125.00","snapshot total");decimal(original.get("settlement_amount"),"125.00","original contribution");
        check(original.get("previous_snapshot_code")==null,"original has no predecessor");String firstCode=original.get("snapshot_code").toString();
        check(sameResponse(confirmed,call("confirm",confirm))&&snapshots(read(101)).size()==1,"confirmation retries do not duplicate snapshot");
        Map<String,Object> duplicate=command(read(101),"confirm-another");duplicate.put("expected_config_version","CFG-1");rejects(409,()->call("confirm",duplicate),"second original confirmation is forbidden");
        configure(101,"configure-two","CFG-1","CFG-2","200");
        check(originalJson.equals(Json.write(copy(snapshots(read(101)).get(0)))),"new pricing configuration leaves frozen original snapshot intact");
        Map<String,Object> staleConfig=command(read(101),"stale-config");staleConfig.put("expected_config_version","CFG-1");staleConfig.put("configuration",config("CFG-3","300"));rejects(409,()->call("configure",staleConfig),"configuration CAS rejects stale expected version");
        save(101,"correct-hours","6.00","5.00","90","2.00");
        rejectedConfiguration("configure-changed-fact-before-reverification",409,config("CFG-CHANGED-UNVERIFIED","200"));
        verify(101,"verify-correction");
        check(!"READY".equals(DeliverySettlementIntegration.preview(worker,101).get("status")),"fact revision change invalidates prior applicability configuration");
        configure(101,"configure-revision","CFG-2","CFG-3","200");
        Map<String,Object> correction=command(read(101),"correct-one");correction.put("expected_config_version","CFG-3");correction.put("expected_snapshot_code",firstCode);correction.put("reason_code","SYNTHETIC-AUDITED-CORRECTION");
        Map<String,Object> corrected=call("correct",correction);check(snapshots(corrected).size()==2,"correction appends one snapshot");
        Map<String,Object> replacement=snapshots(corrected).get(1);check(firstCode.equals(replacement.get("previous_snapshot_code")),"correction links exact predecessor");decimal(replacement.get("amount"),"400.00","replacement total");decimal(replacement.get("settlement_amount"),"275.00","correction contributes replacement minus prior amount");
        check(originalJson.equals(Json.write(copy(snapshots(corrected).get(0)))),"correction never mutates original snapshot");
        check(sameResponse(corrected,call("correct",correction))&&snapshots(read(101)).size()==2,"correction retry is exact and adds no duplicate");
        Map<String,Object> staleHead=command(read(101),"correct-stale-head");staleHead.put("expected_config_version","CFG-3");staleHead.put("expected_snapshot_code",firstCode);staleHead.put("reason_code","SYNTHETIC-STALE-HEAD");rejects(409,()->call("correct",staleHead),"correction cannot branch old snapshot lineage");
        reconcile(corrected);paymentAndCorrectionBoundaries();authorityAndDrift(initial);httpChecks();policyPreviewChecks();
        String persisted=Json.write(read(101));Db.get().close();DeliverySettlementIntegration.init();DeliverySettlementIntegration.init();
        check(persisted.equals(Json.write(read(101))),"facts configuration frozen history and lineage survive connection reopen and repeated init");
        check(Db.count("fees")==2,"M05 creates no legacy fee rows or implicit payments");
        Db.exec("SHUTDOWN");System.out.println("M05Integration: "+checks+" checks passed (isolated synthetic H2; no production data)");
    }
    private static void verifyGates() throws Exception {
        save(105,"actual-only",null,null,"45",null);Map<String,Object> actualOnly=verify(105,"verify-actual-only");
        DeliverySettlementIntegration.requireCompletion(worker,105,version(actualOnly));check(true,"actual-only teaching verification satisfies completion without payable/config");
        capabilityState(actualOnly,version(actualOnly),true,true,true,"verification and completion capabilities do not require payable hours");
        incompleteVerifiedMinutes();
        save(107,"future-draft",null,null,"45",null);Map<String,Object> future=command(read(107),"verify-future");future.put("evidence_code","SYNTHETIC-FUTURE");
        rejects(409,()->call("verify",future),"future teaching cannot be verified");
        capabilityState(read(107),version(read(107)),true,false,false,"future teaching is saveable but cannot be verified or completed");
        Map<String,Object> test=command(read(105),"verify-unconfirmed");test.put("evidence_code","SYNTHETIC-GATES");
        Db.exec("UPDATE dispatches SET status='待发送' WHERE id=105");rejects(409,()->call("verify",test),"unconfirmed dispatch cannot be verified");
        rejectedConfiguration(105,"configure-unconfirmed-dispatch",409,config("CFG-UNCONFIRMED","100"));
        capabilityState(read(105),version(actualOnly),true,false,false,"unconfirmed source updates current verification and completion capability");Db.exec("UPDATE dispatches SET status='已确认' WHERE id=105");
        Db.exec("UPDATE projects SET status='已完成' WHERE id=10");rejects(409,()->call("verify",test),"non-active project cannot be verified");
        rejectedConfiguration(105,"configure-finished-project",409,config("CFG-FINISHED-PROJECT","100"));
        capabilityState(read(105),version(actualOnly),false,false,false,"finished project disables edit capabilities while grants remain current");Db.exec("UPDATE projects SET status='进行中' WHERE id=10");
        Db.exec("UPDATE teachers SET status='出库' WHERE id=20");rejects(409,()->call("verify",test),"inactive teacher cannot be verified");
        rejectedConfiguration(105,"configure-inactive-teacher",409,config("CFG-INACTIVE-TEACHER","100"));
        capabilityState(read(105),version(actualOnly),true,false,false,"inactive teacher blocks review capabilities independently of fact save grant");Db.exec("UPDATE teachers SET status='在库' WHERE id=20");
    }
    private static void incompleteVerifiedMinutes() throws Exception {
        String original=Db.one("SELECT payload FROM m05_delivery_facts WHERE dispatch_id=105").get("payload").toString();long current=version(read(105));
        // Synthetic legacy/corrupt stored inputs retain verification to prove each minute/actual prerequisite independently.
        for(String missing:List.of("conversion","minutes","actual")) {
            Map<String,Object> stored=map(Json.parse(original));
            if(missing.equals("conversion"))stored.put("conversion",null);
            else if(missing.equals("minutes"))map(stored.get("conversion")).put("minutes",null);
            else map(stored.get("hours")).put("actual",null);
            Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=105",Json.write(stored));
            try {
                capabilityState(read(105),current,true,false,false,"verified stored fact with missing "+missing+" has no review or completion capability");
                rejectedConfiguration(105,"configure-missing-stored-"+missing,409,config("CFG-MISSING-STORED-"+missing,"100"));
                rejects(409,()->DeliverySettlementIntegration.requireCompletion(worker,105,current),"completion enforces the same missing "+missing+" readiness rule as capabilities");
                Map<String,Object> verify=command(105,current,"verify-missing-stored-"+missing);verify.put("evidence_code","SYNTHETIC-INCOMPLETE-STORED");
                rejects(409,()->call("verify",verify),"verification cannot replace absent stored "+missing+" with prior checked status");
            } finally {Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=105",original);}
        }
    }
    private static void concurrentCas() throws Exception {
        Map<String,Object> draft=save(102,"cas-initial","3","2","45","1");long before=version(draft);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
        try {
            for(int i=0;i<2;i++) { final int candidate=i;results.add(pool.submit(()->{
                start.await();Map<String,Object> body=command(102,before,"cas-"+candidate);body.put("actual_minutes",candidate==0?"60":"90");
                try { call("save",body);return true; } catch(Api.ApiException failure) {if(failure.code!=409)throw failure;return false;}
            })); }
            start.countDown();int wins=0;for(Future<Boolean> result:results) if(result.get())wins++;
            check(wins==1&&version(read(102))==before+1,"simultaneous writers have exactly one CAS winner and one revision");
        } finally {pool.shutdownNow();}
    }
    private static void rejectedConfiguration(String request,int status,Map<String,Object> candidate) throws Exception {
        rejectedConfiguration(101,request,status,candidate);
    }
    private static void rejectedConfiguration(long id,String request,int status,Map<String,Object> candidate) throws Exception {
        Map<String,Object> before=read(id),body=command(before,request);body.put("expected_config_version",before.get("config_version"));body.put("configuration",candidate);
        long configurations=Db.count("m05_settlement_configs"),policies=Db.count("m05_policy_versions"),requests=Db.count("m05_requests");
        rejects(status,()->call("configure",body),request);
        check(sameResponse(before,read(id))&&Db.count("m05_settlement_configs")==configurations&&Db.count("m05_policy_versions")==policies&&Db.count("m05_requests")==requests,"rejected configuration leaves no partial config policy or request rows: "+request);
    }
    private static void configurationBoundaries() throws Exception {
        Map<String,Object> ruleConflict=config("CFG-CONFLICT-RULE","100");
        map(ruleConflict.get("rule")).put("version","RULE-CFG-1");map(ruleConflict.get("rule")).put("evidence","SYNTHETIC-CHANGED-RULE-EVIDENCE");map(ruleConflict.get("rate")).put("rule_version","RULE-CFG-1");
        rejectedConfiguration("same-rule-version-different-content",409,ruleConflict);
        Map<String,Object> rateConflict=config("CFG-CONFLICT-RATE","101");map(rateConflict.get("rate")).put("version","RATE-CFG-1");
        rejectedConfiguration("same-rate-version-different-content",409,rateConflict);
        check(Db.one("SELECT version_code FROM m05_policy_versions WHERE kind='RULE' AND version_code='RULE-CFG-CONFLICT-RATE'")==null,"new rule inserted before a conflicting rate is rolled back atomically");
        for(String level:List.of("configuration","rule","rate")) {
            Map<String,Object> candidate=config("CFG-CANDIDATE-"+level,"100");
            if(level.equals("configuration"))candidate.put("version",DeliverySettlementPolicy.CANDIDATE_VERSION);
            else {map(candidate.get(level)).put("version",DeliverySettlementPolicy.CANDIDATE_VERSION);if(level.equals("rule"))map(candidate.get("rate")).put("rule_version",DeliverySettlementPolicy.CANDIDATE_VERSION);}
            rejectedConfiguration("candidate-draft-version-"+level,400,candidate);
        }
        Map<String,Object> sixty=config("CFG-INVALID-SIXTY","100");map(sixty.get("rule")).put("minutes_per_class_hour",60);rejectedConfiguration("configured-hour-must-remain-45-minutes",400,sixty);
        Map<String,Object> numericRate=config("CFG-NUMERIC-RATE","100");map(numericRate.get("rate")).put("unit_rate",100);rejectedConfiguration("JSON-numeric-rate-is-not-decimal-text",400,numericRate);
    }
    private static Map<String,Object> correctionCommand(long id,String request) throws Exception {
        Map<String,Object> current=read(id),body=command(current,request);body.put("expected_config_version",current.get("config_version"));body.put("expected_snapshot_code",current.get("head_snapshot_code"));body.put("reason_code","SYNTHETIC-PAYMENT-BOUNDARY");return body;
    }
    private static void paymentAndCorrectionBoundaries() throws Exception {
        save(109,"paid-boundary-initial","4","3","60","1");verify(109,"paid-boundary-verify-one");configure(109,"paid-boundary-config-one",null,"CFG-PAY-1","100");
        Map<String,Object> confirm=command(read(109),"paid-boundary-confirm");confirm.put("expected_config_version","CFG-PAY-1");Map<String,Object> base=call("confirm",confirm);
        String root=snapshots(base).get(0).get("snapshot_code").toString();String frozenRoot=Db.one("SELECT payload FROM m05_settlement_snapshots WHERE snapshot_code=?",root).get("payload").toString();
        save(109,"paid-boundary-edit-two","4","3","90","2");verify(109,"paid-boundary-verify-two");configure(109,"paid-boundary-config-two","CFG-PAY-1","CFG-PAY-2","100");
        call("correct",correctionCommand(109,"paid-boundary-correct-two"));
        save(109,"paid-boundary-edit-three","4","3","135","3");verify(109,"paid-boundary-verify-three");
        Map<String,Object> missing=config("CFG-PAY-NO-CORRECTION","100");missing.put("correction",null);
        Map<String,Object> missingBody=command(read(109),"paid-boundary-config-missing");missingBody.put("expected_config_version","CFG-PAY-2");missingBody.put("configuration",missing);call("configure",missingBody);
        Map<String,Object> noCorrection=correctionCommand(109,"missing-correction-rule");rejects(409,()->call("correct",noCorrection),"valid revised teaching still cannot correct without an explicit correction policy");
        check(snapshots(read(109)).size()==2,"missing correction policy appends no settlement or replacement");
        configure(109,"paid-boundary-config-three","CFG-PAY-NO-CORRECTION","CFG-PAY-3","100");
        check("READY".equals(DeliverySettlementIntegration.preview(worker,109).get("status")),"third revision is otherwise ready before payment-boundary tests");
        String head=read(109).get("head_snapshot_code").toString();
        for(String status:List.of("PAID","UNKNOWN")) {
            Db.exec("UPDATE m05_settlement_snapshots SET payment_status=?,payment_date=? WHERE snapshot_code=?",status,status.equals("PAID")?DATE:null,root);
            check("UNPAID".equals(Db.one("SELECT payment_status FROM m05_settlement_snapshots WHERE snapshot_code=?",head).get("payment_status")),"head stays UNPAID while ancestor is "+status);
            Map<String,Object> attempt=correctionCommand(109,"ancestor-"+status);rejects(409,()->call("correct",attempt),"any "+status+" ancestor blocks correction even when head is UNPAID");
            check(snapshots(read(109)).size()==2,"ancestor payment rejection does not append a snapshot");
            Map<String,Object> entry=DeliverySettlementIntegration.ledger(worker,109).get(0);
            check(status.equals(entry.get("payment_status")),"ledger preserves explicit "+status+" payment status");
            if(status.equals("PAID"))check(DATE.equals(map(entry.get("dates")).get("PAYMENT"))&&DATE.equals(entry.get("payment_date")),"only valid actual PAID date is exposed as PAYMENT basis");
            else check(!map(entry.get("dates")).containsKey("PAYMENT")&&entry.get("payment_date")==null,"UNKNOWN is never relabeled UNPAID or assigned a payment date");
        }
        Db.exec("UPDATE m05_settlement_snapshots SET payment_status='UNPAID',payment_date=? WHERE snapshot_code=?",DATE,root);
        Map<String,Object> inconsistent=correctionCommand(109,"unpaid-with-payment-date");rejects(409,()->call("correct",inconsistent),"non-null ancestor payment date blocks correction even with UNPAID status");
        Map<String,Object> unpaid=DeliverySettlementIntegration.ledger(worker,109).get(0);check("UNPAID".equals(unpaid.get("payment_status"))&&DATE.equals(unpaid.get("payment_date"))&&!map(unpaid.get("dates")).containsKey("PAYMENT"),"inconsistent UNPAID date is exposed but never promoted to PAYMENT basis");
        Db.exec("UPDATE m05_settlement_snapshots SET payment_status='PAID',payment_date=NULL WHERE snapshot_code=?",root);rejects(409,()->DeliverySettlementIntegration.ledger(worker,109),"PAID without real payment date cannot be exported");
        Db.exec("UPDATE m05_settlement_snapshots SET payment_date='2026-02-30' WHERE snapshot_code=?",root);rejects(400,()->DeliverySettlementIntegration.ledger(worker,109),"PAID with invalid calendar date cannot invent PAYMENT basis");
        Db.exec("UPDATE m05_settlement_snapshots SET payment_status='UNPAID',payment_date=NULL WHERE snapshot_code=?",root);
        Map<String,Object> validRequest=correctionCommand(109,"paid-boundary-final-valid");
        Db.exec("UPDATE projects SET status='已取消' WHERE id=10");
        rejects(409,()->call("correct",validRequest),"cancelled project blocks an otherwise valid correction request");
        capabilityState(read(109),version(read(109)),false,false,false,"cancelled project immediately disables all fact editing capabilities");
        Db.exec("UPDATE projects SET status='进行中' WHERE id=10");
        Map<String,Object> valid=call("correct",validRequest);
        check(snapshots(valid).size()==3,"same third revision succeeds once every ancestor is demonstrably UNPAID without a payment date");
        decimal(snapshots(valid).get(2).get("settlement_amount"),"100.00","unpaid-chain correction retains exact delta amount");
        check(frozenRoot.equals(Db.one("SELECT payload FROM m05_settlement_snapshots WHERE snapshot_code=?",root).get("payload").toString()),"payment-state simulations and later correction never rewrite original frozen settlement payload");
    }
    private static void projectHoursAndBoundaries() throws Exception {
        Db.exec("UPDATE dispatches SET teach_date=NULL WHERE id=108");
        Map<String,Object> missingDate=command(108,0,"missing-service-date");missingDate.put("actual_minutes","45");
        capabilityState(read(108),0,false,false,false,"missing server service date prevents save verify and complete capability");
        rejects(409,()->call("save",missingDate),"missing server service date blocks teaching save");Db.exec("UPDATE dispatches SET teach_date=? WHERE id=108",DATE);
        Db.exec("UPDATE dispatches SET status='已拒绝' WHERE id=106");
        Map<String,Object> declined=command(106,0,"declined-save");declined.put("actual_minutes","45");
        capabilityState(read(106),0,false,false,false,"declined dispatch cannot expose fact edit actions");
        rejects(409,()->call("save",declined),"declined dispatch cannot acquire teaching facts");
        Map<String,Object> omitted=DeliverySettlementIntegration.projectHours(worker,13);check(number(omitted.get("dispatch_count"))==0,"declined dispatch excluded from project hour counts");
        Db.exec("UPDATE dispatches SET status='已确认' WHERE id=106");
        Map<String,Object> pending=DeliverySettlementIntegration.projectHours(worker,13);
        check(number(pending.get("pending_count"))==1&&number(pending.get("reviewed_completed_count"))==0&&number(pending.get("unverified_completed_count"))==0,"uncompleted dispatch remains explicit pending item");
        check(pending.get("estimated")==null&&pending.get("planned")==null,"legacy hours never fill unknown project estimates or plans");
        Db.exec("UPDATE dispatches SET status='已完成' WHERE id=106");
        Map<String,Object> unverified=DeliverySettlementIntegration.projectHours(worker,13);
        check(number(unverified.get("unverified_completed_count"))==1&&unverified.get("actual")==null&&unverified.get("payable")==null,"legacy completed flag without reviewed M05 fact preserves unknown actual and payable totals");
        Db.exec("UPDATE dispatches SET status='已确认' WHERE id=106");save(106,"project-hours-save","3.50","2.50","60","1.00");Map<String,Object> verified=verify(106,"project-hours-verify");
        Map<String,Object> notCompleted=DeliverySettlementIntegration.projectHours(worker,13);
        check(number(notCompleted.get("pending_count"))==1&&number(notCompleted.get("reviewed_completed_count"))==0,"verified but uncompleted teaching stays pending");decimal(notCompleted.get("actual"),"0","pending actual teaching does not enter completed total");
        DeliverySettlementIntegration.requireCompletion(worker,106,version(verified));Db.exec("UPDATE dispatches SET status='已完成' WHERE id=106");
        capabilityState(read(106),version(verified),true,true,false,"already completed dispatch may retain fact editing/reverification but cannot complete twice");
        Map<String,Object> completedEdit=new LinkedHashMap<>(Map.of("id",106,"project_id",13,"teacher_id",20,"teach_date",DATE,"status","已完成","material_status","已收齐","remark","SYNTHETIC MATERIAL UPDATE"));
        DeliverySettlementIntegration.guardLegacyMutation("dispatches",106,completedEdit);check(true,"completed-to-completed whole-row material and remark edit remains allowed");
        for(String association:List.of("project_id","teacher_id","teach_date")) {
            Map<String,Object> changed=new LinkedHashMap<>(completedEdit);changed.put(association,association.equals("project_id")?10:association.equals("teacher_id")?21:LocalDate.now().toString());
            rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation("dispatches",106,changed),"completed material edit cannot change frozen "+association);
        }
        Map<String,Object> complete=DeliverySettlementIntegration.projectHours(worker,13);
        check(number(complete.get("reviewed_completed_count"))==1&&number(complete.get("pending_count"))==0&&number(complete.get("unverified_completed_count"))==0,"reviewed completion counted exactly once");
        decimal(complete.get("estimated"),"3.50","project estimated total");decimal(complete.get("planned"),"2.50","project planned total");decimal(complete.get("actual"),"1.33","project completed actual total");decimal(complete.get("payable"),"1.00","project completed payable total");
        check("NOT_CONFIGURED".equals(complete.get("payment_integration")),"hour summary does not invent payment integration");
        rejects(409,()->DeliverySettlementIntegration.guardLegacyArchive(13),"old fee arithmetic cannot archive managed project as paid");
        rejects(403,()->DeliverySettlementIntegration.projectHours(outsider,13),"project hour aggregate is organization scoped");
    }
    private static void transactionRollback() throws Exception {
        Map<String,Object> before=save(108,"rollback-initial","2","1","45","1");
        long revisions=Db.count("m05_fact_revisions"),requests=Db.count("m05_requests");
        Map<String,Object> body=command(before,"rollback-save");body.put("actual_minutes","90");
        Db.exec("ALTER TABLE m05_requests ADD CONSTRAINT m05_test_rollback CHECK(request_id<>'rollback-save')");
        try {call("save",body);throw new AssertionError("forced final request-log insert must fail");}
        catch(java.sql.SQLException expected) {check(true,"database failure after fact write reaches caller");}
        finally {Db.exec("ALTER TABLE m05_requests DROP CONSTRAINT m05_test_rollback");}
        check(sameResponse(before,read(108))&&Db.count("m05_fact_revisions")==revisions&&Db.count("m05_requests")==requests,"failed terminal insert rolls back fact revision history and request identity atomically");
        check(version(call("save",body))==version(before)+1,"failed request identity remains available for an actual successful retry");
    }
    private static void legacyGuards() throws Exception {
        for(String route:List.of("fees/calc")) rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation(route,13,Map.of()),"legacy fee calculation blocked before any M05 fact exists");
        for(String route:List.of("fees/pay","fees/delete")) rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation(route,201,Map.of()),"managed fee row cannot use "+route);
        rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation("fees",0,Map.of("project_id",13)),"new fee cannot bypass controlled project");
        rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation("fees",201,Map.of("amount",1)),"managed legacy fee row cannot be edited");
        rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation("fees",202,Map.of("project_id",10)),"legacy fee cannot be moved into managed project");
        rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation("dispatches/delete",101,null),"persisted teaching cannot be deleted by legacy dispatch route");
        rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation("dispatches",101,Map.of("project_id",13)),"dispatch project association locked after M05 fact");
        rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation("dispatches",101,Map.of("teacher_id",21)),"dispatch teacher association locked after M05 fact");
        rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation("dispatches",101,Map.of("teach_date",LocalDate.now().toString())),"dispatch teaching date locked after M05 fact");
        rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation("dispatches",106,Map.of("status","已完成")),"generic completed status cannot bypass teaching verification");
        rejects(409,()->DeliverySettlementIntegration.guardLegacyMutation("dispatches",0,Map.of("project_id",13,"teacher_id",20,"teach_date",DATE,"status","已完成")),"generic new completed dispatch cannot bypass teaching verification");
        DeliverySettlementIntegration.guardLegacyMutation("dispatches",101,Map.of("project_id",10,"teacher_id",20,"teach_date",DATE,"subject","SYNTHETIC SAME LINKS"));check(true,"unchanged associations and unrelated dispatch edits remain usable");
        DeliverySettlementIntegration.guardLegacyMutation("fees/calc",12,Map.of());DeliverySettlementIntegration.guardLegacyMutation("fees",202,Map.of("amount",200));
        DeliverySettlementIntegration.guardLegacyMutation("fees/pay",202,Map.of());DeliverySettlementIntegration.guardLegacyMutation("fees/delete",202,null);check(true,"verified legacy project remains outside M05 control");
    }
    private static void reconcile(Map<String,Object> view) {
        List<ManagementReports.Fact> facts=new ArrayList<>();long id=1;
        for(Map<String,Object> snapshot:snapshots(view)) {
            Map<String,Object> row=copy(map(snapshot.get("ledger")));row.put("id",Long.toString(id++));row.put("courseId","501");row.put("courseCode","SYNTHETIC-COURSE");
            facts.add(ManagementReports.factFromMap(row));
        }
        ManagementReports.Report report=ManagementReports.build(facts,ManagementReports.agreedDefaultRules("CNY"),
            new ManagementReports.Filter(LocalDate.parse(DATE),LocalDate.parse(DATE),Set.of()),new ManagementReports.AccessScope(Set.of("001"),true,true));
        check(report.total().fee().compareTo(new BigDecimal("400.00"))==0,"M06 original plus delta equals latest total without double counting");
        for(var item:Map.of(ManagementReports.HourBasis.ESTIMATED,"6.00",ManagementReports.HourBasis.PLANNED,"5.00",ManagementReports.HourBasis.ACTUAL,"2.00",ManagementReports.HourBasis.PAYABLE,"2.00").entrySet())
            check(report.hourTotals().get(item.getKey()).hours().compareTo(new BigDecimal(item.getValue()))==0,"M06 reconciles corrected "+item.getKey()+" hours");
        check(report.reconciliation().matches()&&report.total().records()==2&&report.total().courses()==1,"M06 detail teacher organization and unique course totals reconcile");
        check("CONFIRMED".equals(map(snapshots(view).get(0).get("ledger")).get("status"))&&"ADJUSTMENT".equals(map(snapshots(view).get(1).get("ledger")).get("status")),"base remains confirmed and correction is adjustment");
    }
    private static void authorityAndDrift(Map<String,Object> originalRequest) throws Exception {
        Map<String,Object> originalReplay=call("save",originalRequest);
        Map<String,Object> cross=command(103,0,"cross-org-save");cross.put("actual_minutes","45");rejects(403,()->call("save",cross),"cross organization write denied");
        Auth.Session forged=new Auth.Session();forged.uid=2;forged.role="admin";rejects(401,()->DeliverySettlementIntegration.read(forged,101),"forged uid cannot authenticate");
        rejects(401,()->DeliverySettlementIntegration.read(null,101),"missing Session denied");
        String previous=OrganizationAccessStore.configuration().version();int sequence=1;
        for(String resource:List.of("delivery.read","delivery.write","delivery.verify","settlement.configure","settlement.confirm","settlement.correct")) {
            String next="ACCESS-REVOKED-"+sequence++;publishAccess(previous,access(next,Set.of(resource)));previous=next;
            if(resource.equals("delivery.read")) rejects(403,()->read(101),"read permission revocation immediate");
            else if(resource.equals("delivery.write")) {
                rejects(403,()->call("save",originalRequest),"cached idempotent replay rechecks revoked write permission");
                Map<String,Object> current=read(101);capabilityState(current,version(current),false,true,true,"revoked write grant immediately disables save capability only");
                check(Boolean.FALSE.equals(map(map(current.get("capabilities")).get("permissions")).get("save")),"save permission flag comes from current M01 rather than legacy account role");
            }
            else {
                Map<String,Object> body=command(read(101),"revoked-"+resource);String operation=resource.substring(resource.indexOf('.')+1);
                body.put("expected_config_version","CFG-3");
                if(operation.equals("verify")) {body.remove("expected_config_version");body.put("evidence_code","SYNTHETIC-REVOKED");}
                if(operation.equals("configure"))body.put("configuration",config("CFG-DENIED","200"));
                if(operation.equals("correct")) {body.put("expected_snapshot_code",read(101).get("head_snapshot_code"));body.put("reason_code","SYNTHETIC-DENIED");}
                rejects(403,()->call(operation,body),"exact M01 action required: "+resource);
                if(resource.equals("delivery.verify")) {
                    Map<String,Object> current=read(101);capabilityState(current,version(current),true,false,false,"revoked verify grant disables both verify and complete capabilities");
                    Map<String,Object> permissions=map(map(current.get("capabilities")).get("permissions"));check(Boolean.FALSE.equals(permissions.get("verify"))&&Boolean.FALSE.equals(permissions.get("complete")),"complete permission derives from current verify grant");
                    Map<String,Object> retry=call("save",originalRequest);
                    check(sameResponse(originalReplay,retry)&&version(retry)==1,"authorized old save replay retains its original durable result under changed unrelated grants");
                    capabilityState(retry,version(current),true,false,false,"old save replay refreshes current fact version and revoked verification capabilities");
                }
            }
        }
        publishAccess(previous,access("ACCESS-RESTORED",Set.of()));
        publishAccess("ACCESS-RESTORED",access("ACCESS-NO-EXPORT",Set.of("settlement.export")));
        rejects(403,()->DeliverySettlementIntegration.ledger(worker,101),"ledger export needs its exact M01 EXPORT grant");
        check(snapshots(read(101)).size()==2,"delivery read remains available when export permission is revoked");
        publishAccess("ACCESS-NO-EXPORT",access("ACCESS-EXPORT-RESTORED",Set.of()));
        List<Map<String,Object>> ledger=DeliverySettlementIntegration.ledger(worker,101);
        check(ledger.size()==2&&ledger.stream().allMatch(row->row.get("courseId")==null&&row.get("courseCode")==null&&!map(row.get("dates")).containsKey("PAYMENT")),"export uses frozen entries and invents neither M04 course identities nor payment dates");
        Map<String,Object> sourceConfig=command(read(101),"configure-source-drift");sourceConfig.put("expected_config_version","CFG-3");sourceConfig.put("configuration",config("CFG-SOURCE-DRIFT","200"));
        Db.exec("UPDATE workflow_demands SET organization_code='002' WHERE demand_id=1");
        rejects(403,()->call("save",originalRequest),"idempotent replay rechecks current server organization scope");
        rejects(403,()->call("configure",sourceConfig),"configuration cannot cross changed server organization scope");
        Db.exec("UPDATE workflow_demands SET organization_code='001' WHERE demand_id=1");
        Db.exec("UPDATE dispatches SET teacher_id=21 WHERE id=101");rejects(409,()->call("save",originalRequest),"idempotent replay refuses teacher association drift");rejects(409,()->call("configure",sourceConfig),"configuration rejects teacher association drift");Db.exec("UPDATE dispatches SET teacher_id=20 WHERE id=101");
        Db.exec("UPDATE dispatches SET project_id=13 WHERE id=101");rejects(409,()->call("save",originalRequest),"idempotent replay refuses project association drift within same org");rejects(409,()->call("configure",sourceConfig),"configuration rejects project association drift");Db.exec("UPDATE dispatches SET project_id=10 WHERE id=101");
        Db.exec("UPDATE dispatches SET teach_date=? WHERE id=101",LocalDate.now().toString());rejects(409,()->call("save",originalRequest),"idempotent replay refuses service date drift");rejects(409,()->call("configure",sourceConfig),"configuration rejects service date drift");Db.exec("UPDATE dispatches SET teach_date=? WHERE id=101",DATE);
        Db.exec("UPDATE projects SET demand_id=4 WHERE id=10");rejects(409,()->read(101),"project demand must still match accepted workflow");rejects(409,()->call("configure",sourceConfig),"configuration rejects changed acceptance source");Db.exec("UPDATE projects SET demand_id=1 WHERE id=10");
        Auth.logout(workerToken);rejects(401,()->call("save",originalRequest),"logout revokes cached Session and replay");
        workerToken=Auth.login("synthetic-m05-2",PASSWORD);worker=Auth.get(workerToken);
        Db.exec("UPDATE users SET status=0 WHERE id=2");rejects(401,()->read(101),"disabling real account immediately denies cached Session");
        Db.exec("UPDATE users SET status=1 WHERE id=2");rejects(401,()->read(101),"reenabling user does not resurrect revoked Session");
        workerToken=Auth.login("synthetic-m05-2",PASSWORD);worker=Auth.get(workerToken);
        check("001".equals(read(101).get("organization_code")),"fresh login restores explicitly granted scoped access");
    }
    private static void httpChecks() throws Exception {
        check(!DeliverySettlementIntegration.handle(new Exchange("GET","/api/not-a-m05-route",null,null),null),"unknown HTTP route falls through without demanding M05 session");
        String route="/api/delivery-settlement?dispatch_id=101";
        rejects(401,()->DeliverySettlementIntegration.handle(new Exchange("GET",route,null,null),worker),"supplied Session cannot replace actual request token");
        rejects(401,()->DeliverySettlementIntegration.handle(new Exchange("GET",route,adminToken,null),worker),"request token and supplied Session must identify the same live login");
        Exchange request=new Exchange("GET",route,workerToken,null);check(DeliverySettlementIntegration.handle(request,worker),"M05 read HTTP route recognized");
        check(response(request).containsKey("data"),"HTTP read writes API JSON envelope");
        rejects(405,()->DeliverySettlementIntegration.handle(new Exchange("DELETE",route,workerToken,null),worker),"read HTTP route rejects unsupported method");
        rejects(400,()->DeliverySettlementIntegration.handle(new Exchange("GET",route+"&organization_code=001",workerToken,null),worker),"HTTP query cannot provide organization authority");
        Map<String,Object> body=command(read(108),"http-save");body.put("actual_minutes","112.50");
        Exchange post=new Exchange("POST","/api/delivery-settlement/save",workerToken,body);
        check(DeliverySettlementIntegration.handle(post,worker),"HTTP save route recognized");
        Map<String,Object> result=map(response(post).get("data"));check(version(result)==number(body.get("expected_version"))+1,"HTTP save uses expected fact revision");
        decimal(map(map(result.get("fact")).get("hours")).get("actual"),"2.50","HTTP save preserves decimal transport and class-hour conversion");
        Exchange repeat=new Exchange("POST","/api/delivery-settlement/save",workerToken,copy(body));DeliverySettlementIntegration.handle(repeat,worker);
        check(sameResponse(result,map(response(repeat).get("data"))),"HTTP mutation replay returns the saved original response");
        Map<String,Object> stale=copy(body);stale.put("request_id","http-save-stale");
        rejects(409,()->DeliverySettlementIntegration.handle(new Exchange("POST","/api/delivery-settlement/save",workerToken,stale),worker),"HTTP mutation cannot skip expected revision CAS");
    }
    private static DeliverySettlementApprovedPolicy.Request policyRequest(Activity activity, Grade grade, Truth teachingVerified) {
        return new DeliverySettlementApprovedPolicy.Request(
                new RateRequest(activity,grade,DayType.REST_DAY,Truth.NO),
                new VerifiedFacts(Truth.YES,Truth.YES,Truth.NO,null,Truth.UNKNOWN),
                new BigDecimal("777.00"),Truth.YES,teachingVerified,Truth.YES,Truth.YES,false,false,null,null);
    }
    private static Map<String,String> policyReferences() {
        Map<String,String> references=new LinkedHashMap<>();
        for(String key:List.of("grade","day_type","research_team","appointment","annual_plan","customer_paid","applicability","organizer_application","shared_delivery_approval"))
            references.put(key,"SYNTHETIC-PROOF-"+key);
        return references;
    }
    private static PolicyEvidence policyEvidence(PolicyPreviewContext context) {
        return new PolicyEvidence(context,"SYNTHETIC-POLICY-EVIDENCE-1",policyReferences(),policyRequest(Activity.TEACHING,Grade.SPECIAL,Truth.YES));
    }
    /** Full contents, not just row counts, detect accidental history rewrites or request/config inserts. */
    private static String policyDatabaseState() throws Exception {
        Map<String,Object> state=new LinkedHashMap<>();
        for(String table:List.of("m05_delivery_facts","m05_fact_revisions","m05_policy_versions","m05_settlement_configs","m05_settlement_snapshots","m05_requests","fees")) {
            String order=switch(table) {
                case "m05_delivery_facts" -> "dispatch_id";
                case "m05_fact_revisions" -> "dispatch_id,revision";
                case "m05_policy_versions" -> "kind,version_code";
                case "m05_settlement_configs" -> "version_code";
                case "m05_requests" -> "account_id,request_id";
                default -> "id";
            };
            state.put(table,Db.query("SELECT * FROM "+table+" ORDER BY "+order));
        }
        return Json.write(state);
    }
    private static void policyPreviewChecks() throws Exception {
        for(long id:List.of(301L,302L))
            Db.exec("INSERT INTO dispatches VALUES(?,10,20,'SYNTHETIC POLICY SESSION',?,77,'已确认')",id,DATE);
        save(301,"policy-fact-no-payable",null,null,"60",null);verify(301,"policy-verify-no-payable");
        save(302,"policy-fact-unverified",null,null,"60","1.25");

        int[] calls={0};
        PolicyEvidenceProvider counted=context->{calls[0]++;return policyEvidence(context);};
        rejects(403,()->DeliverySettlementIntegration.policyPreview(outsider,301,counted),"policy preview checks organization before invoking evidence provider");
        rejects(403,()->DeliverySettlementIntegration.policyPreview(admin,301,counted),"legacy administrator cannot invoke policy provider without M01 business grant");
        rejects(403,()->DeliverySettlementIntegration.policyPreview(unbound,301,counted),"unbound account cannot invoke policy provider");
        rejects(401,()->DeliverySettlementIntegration.policyPreview(null,301,counted),"missing session cannot invoke policy provider");
        Auth.Session forged=new Auth.Session();forged.uid=worker.uid;forged.role="admin";
        rejects(401,()->DeliverySettlementIntegration.policyPreview(forged,301,counted),"forged session cannot invoke policy provider");
        check(calls[0]==0,"all denied identities leave trusted policy provider untouched");
        String previous=OrganizationAccessStore.configuration().version();
        publishAccess(previous,access("ACCESS-POLICY-NO-READ",Set.of("delivery.read")));
        rejects(403,()->DeliverySettlementIntegration.policyPreview(worker,301,counted),"revoked exact delivery.read grant blocks policy provider");
        check(calls[0]==0,"revoked grant is enforced before provider");
        publishAccess("ACCESS-POLICY-NO-READ",access("ACCESS-POLICY-RESTORED",Set.of()));
        Db.exec("UPDATE dispatches SET teacher_id=21 WHERE id=301");
        rejects(409,()->DeliverySettlementIntegration.policyPreview(worker,301,counted),"policy provider cannot receive drifted teaching association");
        Db.exec("UPDATE dispatches SET teacher_id=20 WHERE id=301");
        check(calls[0]==0,"source association is checked before evidence provider");

        String before=policyDatabaseState();
        Map<String,Object> unknown=DeliverySettlementIntegration.policyPreview(worker,301);
        check(number(unknown.get("dispatch_id"))==301&&number(unknown.get("fact_version"))==version(read(301)),"default policy preview binds dispatch and current fact version");
        decimal(unknown.get("actual_minutes"),"60","policy preview preserves actual minutes");
        decimal(unknown.get("actual_hours"),"1.33","policy preview preserves verified fractional class hours");
        check(unknown.get("payable_hours")==null,"verified 1.33 actual hours never fill unknown payable hours");
        Map<String,Object> trusted=DeliverySettlementIntegration.policyPreview(worker,301,counted);
        check(trusted.get("payable_hours")==null,"provider's fabricated 777 payable hours are replaced with server's unknown value");
        DeliverySettlementIntegration.policyPreview(worker,301,counted);
        check(calls[0]==2,"each policy preview re-reads provider; no stale evidence cache");
        Map<String,Object> unverified=DeliverySettlementIntegration.policyPreview(worker,302,counted);
        decimal(unverified.get("payable_hours"),"1.25","preview takes explicit persisted payable hours instead of provider hours or actual hours");
        policyViewAssertions(unknown,trusted,unverified);

        PolicyEvidence[] captured={null};
        DeliverySettlementIntegration.policyPreview(worker,301,context->{captured[0]=policyEvidence(context);return captured[0];});
        PolicyPreviewContext exact=captured[0].context();
        List<PolicyPreviewContext> staleContexts=List.of(
                new PolicyPreviewContext(302,exact.projectId(),exact.teacherId(),exact.organizationCode(),exact.factRevision(),exact.serviceDate()),
                new PolicyPreviewContext(exact.dispatchId(),13,exact.teacherId(),exact.organizationCode(),exact.factRevision(),exact.serviceDate()),
                new PolicyPreviewContext(exact.dispatchId(),exact.projectId(),21,exact.organizationCode(),exact.factRevision(),exact.serviceDate()),
                new PolicyPreviewContext(exact.dispatchId(),exact.projectId(),exact.teacherId(),"002",exact.factRevision(),exact.serviceDate()),
                new PolicyPreviewContext(exact.dispatchId(),exact.projectId(),exact.teacherId(),exact.organizationCode(),exact.factRevision()+1,exact.serviceDate()),
                new PolicyPreviewContext(exact.dispatchId(),exact.projectId(),exact.teacherId(),exact.organizationCode(),exact.factRevision(),exact.serviceDate().minusDays(1)));
        for(PolicyPreviewContext stale:staleContexts)
            rejects(409,()->DeliverySettlementIntegration.policyPreview(worker,301,context->policyEvidence(stale)),"all policy context dimensions are bound to server teaching identity: "+stale);
        for(String missing:policyReferences().keySet()) {
            Map<String,String> incomplete=policyReferences();incomplete.remove(missing);
            rejects(409,()->DeliverySettlementIntegration.policyPreview(worker,301,context->new PolicyEvidence(context,"SYNTHETIC-MISSING-REF",incomplete,policyRequest(Activity.TEACHING,Grade.SPECIAL,Truth.YES))),"known policy fact needs its specific proof: "+missing);
        }
        var roundedRequest=new DeliverySettlementApprovedPolicy.Request(
                new RateRequest(Activity.TEACHING,Grade.SPECIAL,DayType.REST_DAY,Truth.NO),
                new VerifiedFacts(Truth.YES,Truth.YES,Truth.NO,null,Truth.UNKNOWN),null,
                Truth.YES,Truth.YES,Truth.YES,Truth.YES,false,false,null,
                new DeliverySettlementApprovedPolicy.MoneyRounding(2,java.math.RoundingMode.HALF_UP,"PER_LINE","SYNTHETIC-MONEY-BASIS"));
        rejects(409,()->DeliverySettlementIntegration.policyPreview(worker,301,context->new PolicyEvidence(context,"SYNTHETIC-NO-MONEY-PROOF",policyReferences(),roundedRequest)),"money rounding cannot be supplied without its verified host evidence reference");
        var developmentRequest=new DeliverySettlementApprovedPolicy.Request(
                new RateRequest(Activity.TEACHING,Grade.SPECIAL,DayType.REST_DAY,Truth.NO),
                new VerifiedFacts(Truth.YES,Truth.YES,Truth.NO,null,Truth.UNKNOWN),null,
                Truth.YES,Truth.YES,Truth.YES,Truth.YES,false,false,
                new DeliverySettlementApprovedPolicy.DevelopmentEvidence(null,null,null,null,null,null,null,null),null);
        rejects(409,()->DeliverySettlementIntegration.policyPreview(worker,301,context->new PolicyEvidence(context,"SYNTHETIC-NO-DEVELOPMENT-PROOF",policyReferences(),developmentRequest)),"even extraneous typed development facts require a source rather than an unchecked side channel");
        rejects(409,()->DeliverySettlementIntegration.policyPreview(worker,301,context->new PolicyEvidence(context,"SYNTHETIC-DEVELOPMENT-AS-TEACHING",policyReferences(),policyRequest(Activity.SOLO_DEVELOPMENT,Grade.SPECIAL,Truth.YES))),"dispatch policy bridge cannot repurpose teaching as course development");

        Map<String,Object> legacy=DeliverySettlementIntegration.policyPreview(worker,106,context->policyEvidence(context));
        Map<String,Object> frozen=DeliverySettlementIntegration.policyPreview(worker,101,context->policyEvidence(context));
        policyHistoricalAssertions(legacy,frozen);
        check(before.equals(policyDatabaseState()),"all policy previews and rejected providers leave facts, evidence configs, frozen history, requests and legacy fees byte-for-byte unchanged");

        Map<String,Object> payable=save(301,"policy-explicit-payable",null,null,"60","1.25");
        rejects(409,()->DeliverySettlementIntegration.policyPreview(worker,301,context->captured[0]),"fact change invalidates previously captured evidence even with same teacher date and project");
        Map<String,Object> freshUnverified=DeliverySettlementIntegration.policyPreview(worker,301,context->policyEvidence(context));
        check(number(freshUnverified.get("fact_version"))==version(payable),"policy preview refreshes current revision after save");
        check(Boolean.FALSE.equals(freshUnverified.get("teaching_verified"))&&hasPolicyIssue(freshUnverified,"TEACHING_NOT_VERIFIED"),"provider cannot preserve teaching verification after server fact edit");
        verify(301,"policy-verify-payable");
        Map<String,Object> full=DeliverySettlementIntegration.policyPreview(worker,301,context->policyEvidence(context));
        policyMoneyAssertions(full);
        Map<String,Object> conditionalScenarios=DeliverySettlementIntegration.policyPreview(worker,301);
        check(conditionalScenarios.get("rate")==null&&conditionalScenarios.get("raw_amount")==null&&conditionalScenarios.get("final_amount")==null,
                "saved payable hours cannot select a rate or an amount without trusted grade and day evidence");
        decimal(conditionalScenarios.get("payable_hours"),"1.25","unconfigured-evidence scenarios use independently persisted payable hours");
        check(conditionalScenarios.get("scenarios") instanceof List<?> cases&&cases.size()==12,"unknown grade and day still expose all twelve conditional teaching scenarios");
        for(Object raw:(List<?>)conditionalScenarios.get("scenarios")) {
            Map<String,Object> scenario=map(raw);
            check(Boolean.TRUE.equals(scenario.get("conditional")),"scenario arithmetic is expressly conditional rather than selected policy amount");
            decimal(scenario.get("payable_hours"),"1.25","each scenario retains independent payable hours");
            decimal(scenario.get("raw_amount"),new BigDecimal(scenario.get("unit_rate").toString()).multiply(new BigDecimal("1.25")).toPlainString(),"scenario amount is exact rate times payable hours");
            check(scenario.get("final_amount")==null,"conditional scenario never invents final payable amount");
        }
        check(Boolean.FALSE.equals(conditionalScenarios.get("can_confirm")),"conditional rate scenarios never confer settlement authority");
        Map<String,String> roundingRefs=policyReferences();roundingRefs.put("money_rounding","SYNTHETIC-MONEY-ROUNDING-REVIEW");
        Map<String,Object> rounded=DeliverySettlementIntegration.policyPreview(worker,301,context->new PolicyEvidence(context,"SYNTHETIC-MONEY-ROUNDING-1",roundingRefs,roundedRequest));
        decimal(rounded.get("final_amount"),"500.00","independent explicit money rounding produces a final preview amount");
        check(Boolean.FALSE.equals(rounded.get("can_confirm")),"even complete host evidence and money rules remain a read-only preview");
        Db.exec("UPDATE projects SET status='进行中' WHERE id=10");
        rejects(409,()->DeliverySettlementIntegration.policyPreview(worker,301,context->{
            Db.exec("UPDATE projects SET status='已完成' WHERE id=10");return policyEvidence(context);
        }),"provider source change during its read cannot escape final source revalidation");
        Db.exec("UPDATE projects SET status='进行中' WHERE id=10");
        policyHttpChecks();
    }
    // These assertions deliberately inspect product-facing outcomes, not implementation helpers.
    private static void policyViewAssertions(Map<String,Object> unknown,Map<String,Object> trusted,Map<String,Object> unverified) {
        check("INCOMPLETE".equals(unknown.get("status"))&&unknown.get("rate")==null,"client-saved grade/day dimensions never become trusted policy rate");
        check(unknown.get("scenarios") instanceof List<?> rows&&rows.size()==12,"absent evidence shows complete four-grade three-day conditional teaching rate table");
        for(Object raw:(List<?>)unknown.get("scenarios"))check(Boolean.TRUE.equals(map(raw).get("conditional"))&&map(raw).get("raw_amount")==null,"missing evidence scenarios remain conditional and cannot price absent payable hours");
        for(String issue:List.of("GRADE_MISSING","TEACHING_DAY_TYPE_MISSING","RESEARCH_TEAM_MEMBERSHIP_UNKNOWN","APPOINTMENT_FACT_UNKNOWN","PROJECT_SCOPE_UNKNOWN","SERVICE_DATE_APPLICABILITY_UNKNOWN"))
            check(hasPolicyIssue(unknown,issue),"default host preview exposes precise missing case evidence: "+issue);
        check(unknown.get("raw_amount")==null&&unknown.get("final_amount")==null,"unknown facts do not produce payable amount");
        check(Boolean.TRUE.equals(unknown.get("teaching_verified")),"default preview can truthfully expose server teaching verification without filling other evidence");
        check("SYNTHETIC-POLICY-EVIDENCE-1".equals(trusted.get("evidence_version"))&&map(trusted.get("evidence_references")).size()==9,"trusted preview retains version and per-fact provenance");
        decimal(map(trusted.get("rate")).get("unit_rate"),"400","trusted special-grade rest-day evidence overrides unrelated client lecturer/workday dimensions");
        check("SPECIAL".equals(map(trusted.get("rate")).get("declared_grade"))&&"REST_DAY".equals(map(trusted.get("rate")).get("teaching_day_type")),"rate dimensions come from dated host evidence");
        check(hasPolicyIssue(trusted,"PAYABLE_HOURS_MISSING")&&trusted.get("raw_amount")==null,"trusted qualification cannot invent missing payable hours");
        check(Boolean.FALSE.equals(unverified.get("teaching_verified"))&&"CONDITIONAL_PREVIEW".equals(unverified.get("status")),"stored unverified teaching overrides provider's true assertion and leaves only conditional arithmetic");
        decimal(unverified.get("raw_amount"),"500.00","pending teaching verification preserves 1.25 payable hours times trusted 400 rate as conditional preview");
        check(hasPolicyIssue(unverified,"TEACHING_NOT_VERIFIED")&&Boolean.TRUE.equals(unverified.get("raw_amount_is_conditional"))&&unverified.get("final_amount")==null&&Boolean.FALSE.equals(unverified.get("can_confirm")),"unverified teaching retains an explicit pending condition and never finalizes or authorizes its raw amount");
        for(Map<String,Object> preview:List.of(unknown,trusted,unverified)) {
            check(Boolean.FALSE.equals(preview.get("can_confirm"))&&Boolean.TRUE.equals(preview.get("preview_only")),"policy result cannot be interpreted as settlement approval");
            check(Boolean.FALSE.equals(preview.get("creates_configuration"))&&Boolean.FALSE.equals(preview.get("creates_snapshot")),"policy preview explicitly identifies no configuration or snapshot creation");
            Map<String,Object> basis=map(preview.get("execution_basis"));
            check("2026-09-22".equals(basis.get("adoption_date"))&&basis.get("effective_from")==null&&Boolean.FALSE.equals(basis.get("retroactive_application_assumed")),"user adoption records no fabricated publication date or retroactive effect");
        }
    }
    private static void policyHistoricalAssertions(Map<String,Object> legacy,Map<String,Object> frozen) {
        check("HISTORY_PROTECTED".equals(legacy.get("status"))&&hasPolicyIssue(legacy,"LEGACY_RULE_AND_ENTITLEMENT_REQUIRED"),"server legacy fee association overrides provider's false legacy flag");
        check("HISTORY_PROTECTED".equals(frozen.get("status"))&&hasPolicyIssue(frozen,"HISTORICAL_FROZEN_SNAPSHOT_PRESERVED"),"server frozen snapshot overrides provider's false historical flag");
        for(Map<String,Object> view:List.of(legacy,frozen)) {
            for(String amount:List.of("rate","raw_amount","final_amount","pool_raw_amount","pool_final_amount","individual_raw_amount","individual_final_amount"))
                check(view.get(amount)==null,"protected historical record is not repriced: "+amount);
            check(((List<?>)view.get("scenarios")).isEmpty(),"history protection does not suggest new rate scenarios for old entitlements");
        }
    }
    private static void policyMoneyAssertions(Map<String,Object> full) {
        decimal(full.get("actual_hours"),"1.33","completed policy preview retains actual fractional hours");
        decimal(full.get("payable_hours"),"1.25","policy arithmetic uses independently recorded payable hours");
        decimal(map(full.get("rate")).get("unit_rate"),"400","dated trusted grade and day select approved 400 rate");
        decimal(full.get("raw_amount"),"500.00","exact product is visible without guessing money rounding");
        check(full.get("final_amount")==null&&full.get("money_rounding")==null&&hasPolicyIssue(full,"MONEY_ROUNDING_NOT_CONFIGURED"),"class-hour rounding does not silently become money rounding");
        check(Boolean.FALSE.equals(full.get("can_confirm"))&&Boolean.TRUE.equals(full.get("teaching_verified")),"verified payable preview still requires separate formal confirmation");
    }
    private static boolean hasPolicyIssue(Map<String,Object> value,String code) {
        return value.get("issues") instanceof List<?> issues&&issues.stream().anyMatch(issue->code.equals(map(issue).get("code")));
    }
    private static void policyHttpChecks() throws Exception {
        String route="/api/delivery-settlement/policy-preview?dispatch_id=301";
        String before=policyDatabaseState();
        Exchange get=new Exchange("GET",route,workerToken,null);
        check(DeliverySettlementIntegration.handle(get,worker),"policy preview is mounted as existing M05 read route");
        check(number(map(response(get).get("data")).get("dispatch_id"))==301,"policy route returns scoped preview in normal API envelope");
        rejects(405,()->DeliverySettlementIntegration.handle(new Exchange("POST",route,workerToken,Map.of("appointed",true,"certified",true)),worker),"policy route accepts no POST assertion of approved facts");
        rejects(400,()->DeliverySettlementIntegration.handle(new Exchange("GET",route+"&facts=%7B%22appointed%22%3Atrue%7D",workerToken,null),worker),"policy preview query rejects frontend JSON facts");
        rejects(400,()->DeliverySettlementIntegration.handle(new Exchange("GET",route+"&grade=SPECIAL",workerToken,null),worker),"policy preview query cannot override teaching grade");
        rejects(400,()->DeliverySettlementIntegration.handle(new Exchange("GET",route+"&organization_code=001",workerToken,null),worker),"policy preview query cannot override server organization");
        rejects(401,()->DeliverySettlementIntegration.handle(new Exchange("GET",route,null,null),worker),"policy route requires actual request token");
        check(before.equals(policyDatabaseState()),"HTTP policy preview and invalid frontend inputs cause no M05 or legacy data mutation");
    }
    private static Map<String,Object> response(Exchange exchange) throws Exception {
        Object value=exchange.getAttribute("com.training.Api.response");check(value!=null,"handler stages an API response");
        java.lang.reflect.Field field=value.getClass().getDeclaredField("body");field.setAccessible(true);
        return map(Json.parse(new String((byte[])field.get(value),StandardCharsets.UTF_8)));
    }
    private static final class Exchange extends HttpExchange {
        private final String method;private final URI uri;private final Headers request=new Headers(),response=new Headers();private final Map<String,Object> attributes=new HashMap<>();
        Exchange(String method,String path,String token,Map<String,Object> body) {this.method=method;uri=URI.create(path);if(token!=null)request.set("X-Token",token);request.set("Content-Type","application/json");if(body!=null)attributes.put("com.training.Api.body",body);}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}
        public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return InputStream.nullInputStream();}public OutputStream getResponseBody(){return OutputStream.nullOutputStream();}
        public void sendResponseHeaders(int code,long length){}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}public int getResponseCode(){return 0;}
        public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",1);}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String key){return attributes.get(key);}
        public void setAttribute(String key,Object value){attributes.put(key,value);}public void setStreams(InputStream input,OutputStream output){}public HttpPrincipal getPrincipal(){return null;}
    }
}
