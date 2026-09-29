package com.training;

import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Real store/auth/transactions; only a caller-created empty synthetic H2 directory. */
public final class IntegrationCombinedWorkflowTest {
    static int checks, sequence;
    static final String PASS="SYNTHETIC-COMBINED-WORKFLOW-20260923";
    static Auth.Session admin,filler,reviewer,bp,team;
    static Configuration current;
    interface Work { void run() throws Exception; }
    static void check(boolean value,String label) {checks++;if(!value)throw new AssertionError(label);}
    static void reject(int status,Work work,String label) throws Exception {
        try{work.run();throw new AssertionError("Expected rejection: "+label);}catch(Api.ApiException e){check(e.code==status,label+": "+e.code+" "+e.getMessage());}
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    static long n(Map<String,Object> m,String key){return ((Number)m.get(key)).longValue();}
    static Map<String,Object> call(String op,Auth.Session actor,Map<String,Object> value)throws Exception{return WorkflowIntegration.mutate(op,actor,map(Json.parse(Json.write(value))));}
    static Auth.Session login(int id)throws Exception{return Auth.get(Auth.login(id==1?"admin":"synthetic-combined-"+id,PASS));}
    static void loginAll()throws Exception{admin=login(1);filler=login(2);reviewer=login(3);bp=login(4);team=login(5);}
    static Configuration fixture(boolean combined){
        Set<String> roles=Set.of("FILLER","LEADER","BP","TEAM");
        RelationRule optional=new RelationRule(false,Set.of("LEADER","BP"),false,false);
        List<RoleRelations> relations=new ArrayList<>();for(String role:roles)relations.add(new RoleRelations(role,role.equals("FILLER")?new RelationRule(true,Set.of("LEADER"),true,false):optional,role.equals("FILLER")?new RelationRule(true,Set.of("BP"),true,false):optional));
        List<Grant> grants=new ArrayList<>();for(String role:roles)grants.add(new Grant("read-"+role,role,"demand.read",Action.VIEW,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
        grants.add(new Grant("write","FILLER","demand.write",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("accept","TEAM","demand.accept",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        for(String role:List.of("LEADER","BP"))grants.add(new Grant("review-"+role,role,"approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        grants.add(new Grant("notify-reviewer","LEADER","notifications.read",Action.VIEW,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
        return new Configuration("combined-test-v"+(++sequence),new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),roles,
            List.of(new Organization("001",null,true),new Organization("002",null,true),new Organization("900",null,true)),
            List.of(new Person("P2","001",Set.of(),"P3",combined?"P3":"P4",Set.of("FILLER"),true),
                new Person("P3","002",Set.of("001"),null,null,Set.of("LEADER","BP"),true,Map.of("LEADER",Set.of("001"),"BP",Set.of("001"))),
                new Person("P4","002",Set.of("001"),null,null,Set.of("BP"),true),new Person("P5","900",Set.of("001"),null,null,Set.of("TEAM"),true)),relations,
            List.of(new AccountBinding(2,"P2",true),new AccountBinding(3,"P3",true),new AccountBinding(4,"P4",true),new AccountBinding(5,"P5",true)),grants,
            List.of(new CombinedApprovalAssignment("001","P3","LEADER","BP","SYNTHETIC-COMBINED-EVIDENCE")));
    }
    static Configuration copy(Configuration c,List<Person> people,List<Grant> grants,List<CombinedApprovalAssignment> assignments){return new Configuration("combined-test-v"+(++sequence),c.codeRules(),c.roleCodes(),c.organizations(),people,c.relations(),c.accountBindings(),grants,assignments);}
    static void publish(Configuration c)throws Exception{OrganizationAccessStore.publish(admin,current==null?null:current.version(),c);current=c;loginAll();}
    static Map<String,Object> cmd(Map<String,Object>d,String request){return new LinkedHashMap<>(Map.of("id",d.get("id"),"expected_version",d.get("version"),"request_id",request));}
    static Map<String,Object> action(Map<String,Object>d,String action,String request){Map<String,Object>a=map(d.get("approval"));return new LinkedHashMap<>(Map.of("id",d.get("id"),"action",action,"expectedVersion",a.get("version"),"expectedStage",a.get("stage"),"requestId",request,"comment","SYNTHETIC review"));}
    static Map<String,Object> draft(String request)throws Exception{return call("draft",filler,Map.ofEntries(Map.entry("request_id",request),Map.entry("title","SYNTHETIC COMBINED"),Map.entry("unit","SYNTHETIC CUSTOMER"),Map.entry("business_path","direct"),Map.entry("organization_code","001"),Map.entry("internal_contact_code","P2"),Map.entry("category_text","业务技能"),Map.entry("delivery_mode_text","线上"),Map.entry("period_text","全天"),Map.entry("duration_minutes","60"),Map.entry("participant_count","1"),Map.entry("budget_amount","0"),Map.entry("objectives","SYNTHETIC")));}
    static Map<String,Object> submitted(String key)throws Exception{Map<String,Object>d=draft(key);return call("submit",filler,cmd(d,key+"-submit"));}
    static boolean enabled(Map<String,Object>d,String key){return ((List<?>)map(d.get("approval")).get("actions")).stream().map(IntegrationCombinedWorkflowTest::map).anyMatch(a->key.equals(a.get("action"))&&Boolean.TRUE.equals(a.get("enabled")));}
    static void paused(long id,String label)throws Exception{
        Map<String,Object>d=WorkflowIntegration.detail(id,filler),a=map(d.get("approval"));
        check(Boolean.TRUE.equals(a.get("paused"))&&!((List<?>)a.get("history")).isEmpty(),label+" keeps readable history");
        check(((List<?>)a.get("actions")).stream().map(IntegrationCombinedWorkflowTest::map).noneMatch(v->Boolean.TRUE.equals(v.get("enabled"))),label+" disables actions");
        check(Boolean.FALSE.equals(a.get("can_remind"))&&Boolean.FALSE.equals(map(d.get("actions")).get("accept")),label+" disables reminder and acceptance");
        reject(409,()->call("approval",reviewer,action(d,"APPROVE_COMBINED",label+"-attempt")),label+" blocks new action");
        reject(409,()->call("remind",filler,Map.of("id",id,"expectedVersion",a.get("version"),"requestId",label+"-remind")),label+" blocks reminder");
    }
    static void corruptSnapshot(long id,java.util.function.Consumer<Map<String,Object>> edit,String label)throws Exception{
        String original=String.valueOf(Db.one("SELECT state_json FROM approval_workflows WHERE id=?",id).get("state_json"));
        Map<String,Object> value=map(Json.parse(original));edit.accept(value);Db.exec("UPDATE approval_workflows SET state_json=? WHERE id=?",Json.write(value),id);
        try{reject(409,()->WorkflowIntegration.detail(id,filler),label);}finally{Db.exec("UPDATE approval_workflows SET state_json=? WHERE id=?",original,id);}
    }
    static void legacySnapshot(long id) throws Exception {
        String original=String.valueOf(Db.one("SELECT state_json FROM approval_workflows WHERE id=?",id).get("state_json"));
        List<Map<String,Object>> rows=Db.query("SELECT version,event_json FROM approval_events WHERE workflow_id=?",id);
        Map<String,Object> legacy=map(Json.parse(original));legacy.remove("reviewMode");
        Map<String,Object> policy=map(legacy.get("policy"));policy.remove("reviewMode");policy.remove("combinedAssignment");
        for(Object item:(List<?>)legacy.get("history"))map(item).remove("responsibilities");
        String legacyJson=Json.write(legacy);
        Db.exec("UPDATE approval_workflows SET state_json=? WHERE id=?",legacyJson,id);
        for(Map<String,Object> row:rows){Map<String,Object> event=map(Json.parse(String.valueOf(row.get("event_json"))));event.remove("responsibilities");Db.exec("UPDATE approval_events SET event_json=? WHERE workflow_id=? AND version=?",Json.write(event),id,n(row,"version"));}
        try {
            Map<String,Object> restored=WorkflowIntegration.detail(id,filler);
            check(map(restored.get("approval")).get("reviewMode").equals("SEQUENTIAL"),"real old JSON without every extension restores sequential mode");
            check(restored.get("queue").equals("ready"),"old two-person completed snapshot remains ready");
            check(legacyJson.equals(Db.one("SELECT state_json FROM approval_workflows WHERE id=?",id).get("state_json")),"GET does not migrate or rewrite old snapshot");
        } finally {
            Db.exec("UPDATE approval_workflows SET state_json=? WHERE id=?",original,id);
            for(Map<String,Object> row:rows)Db.exec("UPDATE approval_events SET event_json=? WHERE workflow_id=? AND version=?",row.get("event_json"),id,n(row,"version"));
        }
    }
    public static void main(String[]args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Empty isolated directory required");Path data=Path.of(args[0]).toRealPath();
        if(!data.getFileName().toString().startsWith("yanxu-combined-data-"))throw new IllegalArgumentException("Synthetic temporary directory prefix required");
        try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new IllegalArgumentException("Empty directory required");}
        System.setProperty("data.dir",data.toString());System.setProperty("bootstrap.demo","false");System.setProperty("bootstrap.admin.password",PASS);Db.init();
        for(int i=2;i<=5;i++)Db.exec("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,'viewer',1)","synthetic-combined-"+i,Auth.hash(PASS),"SYNTHETIC "+i);
        loginAll();publish(fixture(true));Configuration original=current;
        Map<String,Object>d=submitted("first");long id=n(d,"id");
        check(map(d.get("approval")).get("reviewMode").equals("SINGLE_EXPLICIT")&&enabled(d,"APPROVE_COMBINED")==false,"filler sees real combined mode without review grant");
        check(enabled(WorkflowIntegration.detail(id,reviewer),"APPROVE_COMBINED"),"explicit viewer reviewer can approve both duties");
        Map<String,Object> inbox=NotificationChannelsIntegration.list(reviewer,0,20);
        check(n(inbox,"total")==1&&Boolean.TRUE.equals(map(((List<?>)inbox.get("items")).get(0)).get("actionable")),"combined submitted notification is actually actionable");
        String frozen=String.valueOf(Db.one("SELECT state_json FROM approval_workflows WHERE id=?",id).get("state_json"));
        publish(copy(current,current.people(),current.grants(),current.combinedApprovals()));
        check(enabled(WorkflowIntegration.detail(id,reviewer),"APPROVE_COMBINED"),"version-only config update does not pause");
        check(frozen.equals(Db.one("SELECT state_json FROM approval_workflows WHERE id=?",id).get("state_json")),"current directory update never rewrites frozen state");
        Map<String,Object> oldApprove=action(d,"APPROVE","implicit");reject(422,()->call("approval",reviewer,oldApprove),"old ordinary button cannot imply both duties");
        Map<String,Object> approve=action(d,"APPROVE_COMBINED","one-approval");d=call("approval",reviewer,approve);
        check(map(d.get("approval")).get("status").equals("READY_FOR_TEAM")&&n(map(d.get("approval")),"version")==2,"one real action reaches team readiness once");
        check(((List<?>)map(d.get("approval")).get("history")).size()==2,"submit plus exactly one approval event");
        check(Db.count("approval_events")==2&&Db.count("workflow_outbox")==2,"one submit and one TEAM_READY event only");
        check(Db.one("SELECT COUNT(*) c FROM s01_notification_events WHERE business_moment='LEADER_APPROVED'").get("c").toString().equals("0"),"no extra BP notification");
        check(Db.one("SELECT COUNT(*) c FROM s01_notification_events WHERE business_moment='BP_APPROVED'").get("c").toString().equals("1"),"exactly one team-ready projection");
        call("approval",reviewer,approve);check(Db.count("approval_events")==2&&Db.count("workflow_outbox")==2,"request replay cannot duplicate event or notification");
        Map<String,Object> finished=d;
        publish(copy(current,current.people(),current.grants(),List.of(new CombinedApprovalAssignment("001","P3","LEADER","BP","SYNTHETIC-OTHER-EVIDENCE"))));
        paused(id,"changed-evidence");Map<String,Object> accept=cmd(finished,"blocked-accept");accept.put("team_code","900");reject(409,()->call("accept",team,accept),"changed evidence blocks acceptance");
        reject(409,()->call("approval",reviewer,approve),"replay checks current evidence");
        publish(copy(original,original.people(),original.grants(),original.combinedApprovals()));
        corruptSnapshot(id,m->map(map(m.get("policy")).get("combinedAssignment")).put("organizationCode","002"),"wrong frozen organization denied");
        corruptSnapshot(id,m->map(m.get("policy")).put("reviewMode","UNKNOWN"),"unknown frozen mode denied");
        corruptSnapshot(id,m->m.put("policyVersion","OTHER-POLICY"),"mismatched policy version denied");
        corruptSnapshot(id,m->map(map(m.get("policy")).get("combinedAssignment")).remove("evidenceRef"),"missing frozen evidence denied");
        corruptSnapshot(id,m->map(((List<?>)m.get("history")).get(1)).put("responsibilities",List.of("LEADER")),"corrupted frozen duties denied");
        String event=String.valueOf(Db.one("SELECT event_json FROM approval_events WHERE workflow_id=? AND version=2",id).get("event_json"));
        Map<String,Object> brokenEvent=map(Json.parse(event));brokenEvent.put("actorId","P4");Db.exec("UPDATE approval_events SET event_json=? WHERE workflow_id=? AND version=2",Json.write(brokenEvent),id);
        reject(409,()->WorkflowIntegration.detail(id,filler),"wrong stored event actor denied");Db.exec("UPDATE approval_events SET event_json=? WHERE workflow_id=? AND version=2",event,id);
        Db.get().close();check(WorkflowIntegration.detail(id,reviewer).get("queue").equals("ready"),"combined snapshot survives fresh H2 connection");
        Map<String,Object> pending=submitted("pending");long pendingId=n(pending,"id");
        publish(copy(original,original.people(),original.grants().stream().filter(g->!g.ruleId().equals("review-LEADER")).toList(),original.combinedApprovals()));paused(pendingId,"missing-one-duty-grant");
        publish(copy(original,original.people(),original.grants(),List.of()));paused(pendingId,"missing-assignment");
        Map<String,Object> noEvidenceDraft=draft("without-evidence");reject(422,()->call("submit",filler,cmd(noEvidenceDraft,"no-evidence-submit")),"same IDs without approved assignment cannot submit");
        Configuration sequential=fixture(false);publish(sequential);paused(pendingId,"changed-filler-relation");
        Map<String,Object> normal=submitted("normal");normal=call("approval",reviewer,action(normal,"APPROVE","normal-lead"));check(map(normal.get("approval")).get("status").equals("BP_PENDING"),"ordinary different people still need BP");normal=call("approval",bp,action(normal,"APPROVE","normal-bp"));check(map(normal.get("approval")).get("status").equals("READY_FOR_TEAM"),"ordinary two-person completion retained");
        legacySnapshot(n(normal,"id"));
        publish(copy(original,original.people(),original.grants(),original.combinedApprovals()));
        Map<String,Object> turn=WorkflowIntegration.detail(pendingId,filler);turn=call("approval",reviewer,action(turn,"RETURN","return"));turn=call("approval",filler,action(turn,"RESUBMIT","resubmit"));check(n(map(turn.get("approval")),"round")==2&&enabled(WorkflowIntegration.detail(pendingId,reviewer),"APPROVE_COMBINED"),"return resubmit restarts combined review");
        Map<String,Object> change=cmd(turn,"change-content");change.put("title","SYNTHETIC CHANGED");change.put("change_comment","SYNTHETIC new version");turn=call("draft",filler,change);check(n(turn,"data_revision")==2&&map(turn.get("approval")).get("status").equals("LEADER_PENDING"),"changed content restarts combined review");
        turn=call("approval",filler,action(turn,"WITHDRAW","withdraw"));turn=call("approval",filler,action(turn,"RESUBMIT","resubmit-withdraw"));check(map(turn.get("approval")).get("status").equals("LEADER_PENDING"),"withdraw resubmit remains combined");
        Db.exec("UPDATE users SET status=0 WHERE id=3");Map<String,Object> blocked=WorkflowIntegration.detail(pendingId,filler);check(Boolean.TRUE.equals(map(blocked.get("approval")).get("paused")),"disabled reviewer account yields readonly history");Db.exec("UPDATE users SET status=1 WHERE id=3");loginAll();
        d=WorkflowIntegration.detail(id,team);Map<String,Object> accepted=cmd(d,"accept-valid");accepted.put("team_code","900");d=call("accept",team,accepted);check(d.get("project_id")!=null,"separate explicit team acceptance succeeds after restored evidence");
        List<Person> selfPeople=new ArrayList<>(original.people());selfPeople.set(0,new Person("P2","001",Set.of("001"),"P2","P2",Set.of("FILLER","LEADER","BP"),true,Map.of("FILLER",Set.of(),"LEADER",Set.of("001"),"BP",Set.of("001"))));
        List<RoleRelations> selfRelations=original.relations().stream().map(r->new RoleRelations(r.roleCode(),new RelationRule(false,Set.of("LEADER"),true,true),new RelationRule(false,Set.of("BP"),true,true))).toList();
        Configuration self=new Configuration("combined-test-v"+(++sequence),original.codeRules(),original.roleCodes(),original.organizations(),selfPeople,selfRelations,original.accountBindings(),original.grants(),List.of(new CombinedApprovalAssignment("001","P2","LEADER","BP","SYNTHETIC-SELF-EVIDENCE")));
        publish(self);Map<String,Object> selfDraft=draft("self");reject(422,()->call("submit",filler,cmd(selfDraft,"self-submit")),"even explicit self duty cannot approve own demand");
        publish(copy(original,original.people(),original.grants(),original.combinedApprovals()));
        Auth.Session forged=new Auth.Session();forged.uid=3;reject(401,()->call("approval",forged,approve),"forged session denied");
        Map<String,Object> oldOutbox=Db.one("SELECT event_key,payload FROM workflow_outbox WHERE demand_id=? AND event_key LIKE ?",id,"%TEAM_READY%");
        String oldKey=String.valueOf(oldOutbox.get("event_key"));
        check(Db.query("SELECT notice_id FROM s01_notifications WHERE event_key=?",oldKey).isEmpty(),"unconfigured team has no fabricated recipients");
        Db.exec("DELETE FROM s01_notification_events WHERE event_key=?",oldKey);
        long frozenCount=Db.count("s01_notification_events"),noticeCount=Db.count("s01_notifications");
        Map<String,Object> historical;
        synchronized(Api.MUTATION_LOCK){historical=Db.transaction(()->NotificationChannelsIntegration.appendOutboxInTransaction(oldKey,id,map(Json.parse(String.valueOf(oldOutbox.get("payload"))))));}
        check(historical.get("status").equals("BLOCKED")&&historical.get("reason").equals("HISTORICAL_BINDING_UNPROVEN"),"combined historical outbox without frozen recipients cannot be backfilled");
        check(Db.count("s01_notification_events")==frozenCount&&Db.count("s01_notifications")==noticeCount,"historical replay writes no new freeze or recipient");
        Db.exec("SHUTDOWN");System.out.println("IntegrationCombinedWorkflow: "+checks+" checks passed (isolated synthetic H2)");
    }
}
