package com.training;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.training.OrganizationAccess.*;

/** Runs only against a fresh caller-created test directory, with synthetic authenticated users. */
public final class IntegrationWorkflowTest {
    private static int checks;
    private static Auth.Session admin,filler,leader,bp,team,outsider;
    @FunctionalInterface interface Work { void run() throws Exception; }
    static void check(boolean value,String label) { checks++; if(!value) throw new AssertionError(label); }
    static void reject(int code,Work work,String label) throws Exception { try {work.run();throw new AssertionError("Expected denial: "+label);} catch(Api.ApiException failure) {check(failure.code==code,label+" status="+failure.code+" "+failure.getMessage());} }
    @SuppressWarnings("unchecked") static Map<String,Object> body(Map<String,Object> b) {return (Map<String,Object>)Json.parse(Json.write(b));}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object b) {return (Map<String,Object>)b;}
    static long n(Map<String,Object> b,String key) {return ((Number)b.get(key)).longValue();}
    static Map<String,Object> call(String op,Auth.Session actor,Map<String,Object> b) throws Exception {return WorkflowIntegration.mutate(op,actor,body(b));}
    static Map<String,Object> command(Map<String,Object> d,String request) {return new LinkedHashMap<>(Map.of("id",d.get("id"),"expected_version",d.get("version"),"request_id",request));}
    static Map<String,Object> approve(Map<String,Object> d,String action,String request) {Map<String,Object> a=map(d.get("approval"));return new LinkedHashMap<>(Map.of("id",d.get("id"),"action",action,"expectedVersion",a.get("version"),"expectedStage",a.get("stage"),"requestId",request,"comment","合成验证意见"));}
    static Map<String,Object> full(String request,String path) {Map<String,Object>b=new LinkedHashMap<>();b.put("request_id",request);b.put("title","合成需求");b.put("business_path",path);b.put("organization_code","001");b.put("internal_contact_code","P2");b.put("category_text","业务技能");b.put("delivery_mode_text","线上");b.put("period_text","待协调");b.put("duration_minutes","67.50");b.put("participant_count","12");b.put("budget_amount","1234.50");b.put("objectives","合成目标");b.put("unit","合成客户");b.put("contact","合成联系人");b.put("phone","00000000");b.put("content","主要内容");b.put("teacher_req","原师资要求");b.put("remark","原备注");if(path.equals("bid"))b.put("external_approval_ref","SYNTHETIC-OFFICE-001");return b;}
    static Configuration config() {
        RelationRule optional=new RelationRule(false,Set.of("LEADER","BP"),false,false);
        List<Grant> grants=new ArrayList<>();
        for(String role:List.of("FILLER","LEADER","BP","TEAM")) grants.add(new Grant("read-"+role,role,"demand.read",Action.VIEW,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
        grants.add(new Grant("write-filler","FILLER","demand.write",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("write-team","TEAM","demand.write",Action.HANDLE,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
        for(String role:List.of("LEADER","BP"))grants.add(new Grant("review-"+role,role,"approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        grants.add(new Grant("accept","TEAM","demand.accept",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        grants.add(new Grant("bid","FILLER","bid.result",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("outread","OUT","demand.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        List<RoleRelations> relations=new ArrayList<>();for(String role:List.of("LEADER","BP","TEAM","OUT"))relations.add(new RoleRelations(role,optional,optional));
        relations.add(new RoleRelations("FILLER",new RelationRule(true,Set.of("LEADER"),true,false),new RelationRule(true,Set.of("BP"),true,false)));
        return new Configuration("synthetic-workflow-v1",new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),Set.of("FILLER","LEADER","BP","TEAM","OUT"),
            List.of(new Organization("001",null,true),new Organization("002",null,true),new Organization("900",null,true),new Organization("999",null,true)),
            List.of(new Person("P2","001",Set.of(),"P3","P4",Set.of("FILLER"),true),new Person("P3","002",Set.of("001"),null,null,Set.of("LEADER"),true),new Person("P4","002",Set.of("001"),null,null,Set.of("BP"),true),new Person("P5","900",Set.of("001"),null,null,Set.of("TEAM"),true),new Person("P6","999",Set.of(),null,null,Set.of("OUT"),true)),relations,
            List.of(new AccountBinding(2,"P2",true),new AccountBinding(3,"P3",true),new AccountBinding(4,"P4",true),new AccountBinding(5,"P5",true),new AccountBinding(6,"P6",true)),grants);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Fresh empty test directory required");Path data=Path.of(args[0]);try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Test directory must be empty");}System.setProperty("data.dir",data.toAbsolutePath().toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64),password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id IDENTITY PRIMARY KEY,title VARCHAR(200),unit VARCHAR(200),contact VARCHAR(64),phone VARCHAR(32),hours DOUBLE,content CLOB,teacher_req CLOB,expect_date VARCHAR(32),status VARCHAR(16),remark CLOB,training_province VARCHAR(64),training_city VARCHAR(64),training_mode VARCHAR(500),training_period VARCHAR(500))");
        Db.exec("CREATE TABLE projects(id IDENTITY PRIMARY KEY,demand_id BIGINT,bid_id BIGINT,title VARCHAR(200),unit VARCHAR(200),hours DOUBLE,amount DOUBLE,start_date VARCHAR(32),end_date VARCHAR(32),owner VARCHAR(64),participant_count INT,delivery_mode VARCHAR(500),status VARCHAR(16),remark CLOB)");
        Db.exec("CREATE TABLE bids(id IDENTITY PRIMARY KEY,demand_id BIGINT)");
        Db.exec("CREATE TABLE questionnaires(id IDENTITY PRIMARY KEY,project_id BIGINT)");
        String synthetic="synthetic-workflow-password";String hash=Auth.hash(synthetic);Auth.Session[] actors=new Auth.Session[6];
        for(int i=1;i<=6;i++){Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",i,"synthetic-"+i,hash,"SYNTHETIC "+i,i==1?"admin":"viewer");actors[i-1]=Auth.get(Auth.login("synthetic-"+i,synthetic));}
        admin=actors[0];filler=actors[1];leader=actors[2];bp=actors[3];team=actors[4];outsider=actors[5];
        OrganizationAccessStore.init();WorkflowIntegration.init();WorkflowIntegration.init();NotificationChannelsIntegration.init();
        reject(403,()->call("draft",admin,full("admin-denied","direct")),"old admin grants nothing");
        OrganizationAccessStore.publish(admin,null,config());
        // Configuration publication revokes mapped identities; later assertions use fresh logins.
        filler=Auth.get(Auth.login("synthetic-2",synthetic));leader=Auth.get(Auth.login("synthetic-3",synthetic));
        bp=Auth.get(Auth.login("synthetic-4",synthetic));team=Auth.get(Auth.login("synthetic-5",synthetic));outsider=Auth.get(Auth.login("synthetic-6",synthetic));
        Map<String,Object> missingUnit=full("missing-unit","direct");missingUnit.put("unit","");Map<String,Object> unitDraft=call("draft",filler,missingUnit);
        reject(422,()->call("submit",filler,command(unitDraft,"submit-no-unit")),"original demand unit remains required");
        Map<String,Object> hoursForm=full("hours-form","direct");hoursForm.remove("duration_minutes");hoursForm.put("hours","1.00");Map<String,Object> hoursDraft=call("draft",filler,hoursForm);
        check(map(hoursDraft.get("legacy")).get("original_hours").equals("1.00"),"original course-unit input preserved");
        Map<String,Object> minutesEdit=command(hoursDraft,"switch-minutes");minutesEdit.put("duration_minutes","60");hoursDraft=call("draft",filler,minutesEdit);
        check(!map(hoursDraft.get("legacy")).containsKey("original_hours"),"switching minutes clears stale unit input");
        check(((Number)Db.one("SELECT hours FROM demands WHERE id=?",n(hoursDraft,"id")).get("hours")).doubleValue()==1.33,"confirmed two-place course-unit projection");
        Map<String,Object> regional=full("regional","direct");regional.put("training_province","浙江省");regional.put("training_city","杭州市");Map<String,Object> regionalDraft=call("draft",filler,regional);
        check(map(regionalDraft.get("legacy")).get("training_city").equals("杭州"),"original city canonicalization retained");
        Map<String,Object> partial=command(regionalDraft,"regional-partial");partial.put("title","仅修改标题");regionalDraft=call("draft",filler,partial);
        check(map(regionalDraft.get("legacy")).get("training_city").equals("杭州"),"partial draft edit retains canonical city");
        Map<String,Object> badRegion=command(regionalDraft,"regional-invalid");badRegion.put("training_city","北京");reject(400,()->call("draft",filler,badRegion),"invalid original city rejected");
        Map<String,Object> labels=full("custom-labels","direct");labels.put("delivery_mode_text","混合");labels.put("period_text","晚间协调");Map<String,Object> labelDraft=call("draft",filler,labels);
        check(map(labelDraft.get("form")).get("delivery_mode_text").equals("混合")&&map(labelDraft.get("form")).get("period_text").equals("晚间协调"),"custom labels retained exactly in authoritative form");
        Map<String,Object> projected=Db.one("SELECT training_mode,training_period FROM demands WHERE id=?",n(labelDraft,"id"));check(projected.get("training_mode").equals("待定")&&projected.get("training_period").equals("待定"),"old recommendation projection does not guess custom labels");
        Map<String,Object> unboundApproval=call("draft",filler,full("inactive-reviewer","direct"));Db.exec("UPDATE users SET status=0 WHERE id=3");
        reject(422,()->call("submit",filler,command(unboundApproval,"inactive-submit")),"inactive approver login blocks submission");Db.exec("UPDATE users SET status=1 WHERE id=3");
        Map<String,Object> ctx=WorkflowIntegration.context(team);check(((List<?>)ctx.get("teams")).size()==1,"explicit own team option");
        Map<String,Object> empty=call("draft",filler,Map.of("request_id","empty","title","草稿"));
        reject(422,()->call("submit",filler,command(empty,"empty-submit")),"incomplete draft rejects submission");
        check(n(WorkflowIntegration.detail(n(empty,"id"),filler),"version")==1,"failed submit rolls back");
        Map<String,Object> d=call("draft",filler,full("draft-1","direct"));long id=n(d,"id");
        check(map(d.get("form")).get("duration_minutes").equals("67.50"),"exact minutes text retained");check(map(d.get("legacy")).get("teacher_req").equals("原师资要求"),"original fields retained");
        check(n(call("draft",filler,full("draft-1","direct")),"id")==id,"initial create idempotent");
        Map<String,Object> wrong=full("draft-1","direct");wrong.put("title","不同内容");reject(409,()->call("draft",filler,wrong),"request reused with different data denied");
        reject(403,()->WorkflowIntegration.detail(id,outsider),"cross organization detail denied");
        reject(409,()->WorkflowIntegration.guardLegacyMutation("demands",0,Map.of()),"new generic demand blocked");
        reject(409,()->WorkflowIntegration.guardLegacyMutation("projects",0,Map.of()),"new generic project blocked");
        reject(409,()->WorkflowIntegration.guardLegacyMutation("demands",id,null),"workflow demand delete blocked");
        d=call("submit",filler,command(d,"submit-1"));check(map(d.get("approval")).get("status").equals("LEADER_PENDING"),"submitted to leader");
        Map<String,Object> submitted=d;
        reject(403,()->call("approval",bp,approve(submitted,"APPROVE","bp-early")),"BP cannot skip leader");
        reject(403,()->call("approval",filler,approve(submitted,"APPROVE","self")),"self review denied");
        Map<String,Object> early=command(d,"early-accept");early.put("team_code","900");reject(409,()->call("accept",team,early),"cannot accept before approvals");
        Map<String,Object> lead=approve(d,"APPROVE","lead-1");d=call("approval",leader,lead);
        check(map(d.get("approval")).get("status").equals("BP_PENDING"),"leader advances to BP");long eventCount=Db.count("approval_events");call("approval",leader,lead);check(Db.count("approval_events")==eventCount,"repeat approval has no second event");
        Map<String,Object> stale=new LinkedHashMap<>(lead);stale.put("requestId","stale-lead");reject(409,()->call("approval",leader,stale),"stale approval version denied");
        d=call("approval",bp,approve(d,"APPROVE","bp-1"));check(d.get("queue").equals("ready"),"two approvals ready for team");
        Map<String,Object> accept=command(d,"accept-1");accept.put("team_code","900");d=call("accept",team,accept);long project=n(d,"project_id");check(d.get("queue").equals("accepted"),"team accepted");
        call("accept",team,accept);check(Db.count("projects")==1,"acceptance replay no duplicate project");
        Map<String,Object> storedProject=Db.one("SELECT * FROM projects WHERE id=?",project);check(storedProject.get("bid_id")==null,"direct project has no fake bid");check(n(storedProject,"participant_count")==12,"participant count projected");check(n(storedProject,"amount")==0,"budget is not a contract amount");
        WorkflowIntegration.guardLegacyMutation("projects/start",project,Map.of());check(true,"legitimate project start allowed");
        reject(403,()->WorkflowIntegration.requireProjectAccess(project,outsider,false),"child project data denied cross scope");
        reject(409,()->WorkflowIntegration.guardLegacyMutation("projects",project,null),"managed project deletion blocked");
        Db.exec("INSERT INTO questionnaires(project_id) VALUES(?)",project);check(WorkflowIntegration.visibleRows("q_sends",List.of(Map.of("id",1,"questionnaire_id",1)),outsider).isEmpty(),"questionnaire child scope enforced");
        check(WorkflowIntegration.visibleRows("demands",Db.query("SELECT * FROM demands"),outsider).isEmpty(),"list does not leak new demands");
        Map<String,Object> edit=command(d,"edit-after");edit.put("title","禁止修改");reject(409,()->call("draft",filler,edit),"accepted source immutable");
        check(Db.count("workflow_outbox")==4,"submit leader BP acceptance outbox plans persisted");
        Map<String,Object> bid=call("draft",filler,full("bid-draft","bid"));bid=call("submit",filler,command(bid,"bid-submit"));check(bid.get("approval")==null,"bid stays outside direct approval");
        Map<String,Object> result=command(bid,"bid-result");result.put("result","won");result.put("external_approval_ref","SYNTHETIC-OFFICE-001");result.put("result_date","2026-09-22");bid=call("bid-result",filler,result);check(bid.get("queue").equals("ready"),"external result waits for acceptance");check(Db.count("projects")==1,"bid result does not auto-create project");
        Map<String,Object> bidAccepted=command(bid,"bid-accept");bidAccepted.put("team_code","900");call("accept",team,bidAccepted);check(Db.count("projects")==2,"bid accepted separately");
        Map<String,Object> revision=call("draft",filler,full("revision-draft","direct"));revision=call("submit",filler,command(revision,"revision-submit"));revision=call("approval",leader,approve(revision,"APPROVE","revision-lead"));
        Map<String,Object> change=command(revision,"revision-edit");change.put("title","变更目标");change.put("change_comment","内容变更需重审");revision=call("draft",filler,change);check(map(revision.get("approval")).get("status").equals("LEADER_PENDING"),"changed material restarts leader");check(n(revision,"data_revision")==2,"material revision incremented");
        revision=call("approval",leader,approve(revision,"RETURN","revision-return"));revision=call("approval",filler,approve(revision,"RESUBMIT","revision-resubmit"));check(map(revision.get("approval")).get("status").equals("LEADER_PENDING"),"returned resubmit starts leader");
        Map<String,Object> reminder=Map.of("id",revision.get("id"),"expectedVersion",map(revision.get("approval")).get("version"),"requestId","too-early");reject(422,()->call("remind",filler,reminder),"early reminder denied");
        Map<String,Object> concurrent=revision;ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> runs=new ArrayList<>();
        for(int i=0;i<2;i++){String req="race-"+i;runs.add(workers.submit(()->{start.await();try{call("approval",leader,approve(concurrent,"APPROVE",req));return true;}catch(Api.ApiException e){if(e.code!=409)throw e;return false;}}));}start.countDown();int winners=0;for(Future<Boolean> run:runs)if(run.get())winners++;workers.shutdown();check(winners==1,"concurrent old version has one winner");
        Db.get().close();check(WorkflowIntegration.detail(id,filler).get("queue").equals("accepted"),"workflow and history survive reconnect");
        Auth.Session forged=new Auth.Session();forged.uid=2;reject(401,()->call("draft",forged,full("forged","direct")),"forged session denied");
        Db.exec("SHUTDOWN");System.out.println("IntegrationWorkflow: "+checks+" checks passed (isolated synthetic H2)");
    }
}
