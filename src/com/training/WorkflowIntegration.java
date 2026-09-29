package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.training.ApprovalWorkflow.*;

/** Persistent host adapter for M01/M02/M03. No bootstrap identities or automatic grants. */
public final class WorkflowIntegration {
    private WorkflowIntegration() {}
    private static final String POLICY = "M03-BASELINE-20260921-v1";
    private static final String COMBINED_POLICY = "M03-COMBINED-20260922-v1";
    private static final Set<String> FORM = Set.of("title","business_path","organization_code","internal_contact_code","customer_contact_code","category_text","delivery_mode_text","period_text","duration_minutes","participant_count","budget_amount","expected_start_date","expected_end_date","objectives","external_approval_ref","demand_code");
    private static final Set<String> LEGACY = Set.of("unit","contact","phone","content","teacher_req","remark","training_province","training_city");
    private static final Set<String> CONTROL = Set.of("id","expected_version","request_id","change_comment");

    public static void init() throws SQLException {
        Db.exec("CREATE TABLE IF NOT EXISTS workflow_demands(demand_id BIGINT PRIMARY KEY REFERENCES demands(id),version BIGINT NOT NULL,data_revision BIGINT NOT NULL,draft BOOLEAN NOT NULL,business_path VARCHAR(8) NOT NULL,organization_code VARCHAR(120) NOT NULL,filler_code VARCHAR(120) NOT NULL,form_json CLOB NOT NULL,legacy_json CLOB NOT NULL,rule_version VARCHAR(80) NOT NULL)");
        Db.exec("CREATE TABLE IF NOT EXISTS workflow_documents(demand_id BIGINT NOT NULL REFERENCES workflow_demands(demand_id),data_revision BIGINT NOT NULL,document_json CLOB NOT NULL,PRIMARY KEY(demand_id,data_revision))");
        Db.exec("CREATE TABLE IF NOT EXISTS approval_workflows(id BIGINT PRIMARY KEY REFERENCES workflow_demands(demand_id),version BIGINT NOT NULL,state_json CLOB NOT NULL)");
        Db.exec("CREATE TABLE IF NOT EXISTS approval_events(workflow_id BIGINT NOT NULL REFERENCES approval_workflows(id),version BIGINT NOT NULL,request_id VARCHAR(160) NOT NULL,event_json CLOB NOT NULL,PRIMARY KEY(workflow_id,version),UNIQUE(workflow_id,request_id))");
        Db.exec("CREATE TABLE IF NOT EXISTS workflow_bid_results(demand_id BIGINT PRIMARY KEY REFERENCES workflow_demands(demand_id),result VARCHAR(8) NOT NULL,external_approval_ref VARCHAR(500) NOT NULL,result_date VARCHAR(10) NOT NULL,result_note CLOB,actor_code VARCHAR(120) NOT NULL,created_at VARCHAR(40) NOT NULL)");
        Db.exec("CREATE TABLE IF NOT EXISTS workflow_acceptances(demand_id BIGINT PRIMARY KEY REFERENCES workflow_demands(demand_id),project_id BIGINT NOT NULL UNIQUE REFERENCES projects(id),team_code VARCHAR(120) NOT NULL,actor_code VARCHAR(120) NOT NULL,data_revision BIGINT NOT NULL,created_at VARCHAR(40) NOT NULL)");
        Db.exec("CREATE TABLE IF NOT EXISTS workflow_requests(actor_code VARCHAR(120) NOT NULL,request_id VARCHAR(160) NOT NULL,operation VARCHAR(40) NOT NULL,payload CLOB NOT NULL,demand_id BIGINT NOT NULL REFERENCES workflow_demands(demand_id),PRIMARY KEY(actor_code,request_id))");
        Db.exec("CREATE TABLE IF NOT EXISTS approval_reminders(workflow_id BIGINT NOT NULL REFERENCES approval_workflows(id),request_id VARCHAR(160) NOT NULL,event_version BIGINT NOT NULL,actor_code VARCHAR(120) NOT NULL,recipient_code VARCHAR(120) NOT NULL,created_at VARCHAR(40) NOT NULL,PRIMARY KEY(workflow_id,request_id))");
        Db.exec("CREATE TABLE IF NOT EXISTS workflow_outbox(event_key VARCHAR(500) PRIMARY KEY,demand_id BIGINT NOT NULL,payload CLOB NOT NULL,status VARCHAR(24) NOT NULL DEFAULT 'PENDING',created_at VARCHAR(40) NOT NULL)");
    }

    public static boolean handle(HttpExchange ex, Auth.Session session) throws Exception {
        String path=ex.getRequestURI().getPath();
        if(Auth.get(Api.token(ex))!=session) fail(401,"登录会话无效或已失效");
        if (path.equals("/api/workflow/context")) { method(ex,"GET"); Api.ok(ex,context(session)); return true; }
        if (path.equals("/api/demands/workflow")) { method(ex,"GET"); Api.ok(ex,Map.of("workflow",detail(number(Api.query(ex).get("id"),"需求编号"),session))); return true; }
        if (path.equals("/api/approvals/tasks")) { method(ex,"GET"); List<Map<String,Object>> tasks=new ArrayList<>();
            for (Map<String,Object> row:Db.query("SELECT demand_id FROM workflow_demands WHERE draft=FALSE ORDER BY demand_id DESC")) {
                long id=num(row,"demand_id"); if (loadState(id)==null) continue;
                Map<String,Object> d=load(id); if (!allowed(session,"demand.read",OrganizationAccess.Action.VIEW,str(d,"organization_code"))) continue;
                Map<String,Object> v=detail(id,session); Map<String,Object> task=map(v.get("approval")); task.put("workflow",withoutApproval(v)); tasks.add(task);
            }
            Api.ok(ex,Map.of("tasks",tasks)); return true; }
        if (path.matches("/api/approvals/tasks/[0-9]+(?:/(?:actions|remind))?")) {
            String[] parts=path.split("/"); long id=number(parts[4],"审批编号");
            if (parts.length==5) { method(ex,"GET"); Map<String,Object> v=detail(id,session); if(v.get("approval")==null) fail(404,"审批记录不存在"); Map<String,Object> t=map(v.get("approval")); t.put("workflow",withoutApproval(v)); Api.ok(ex,Map.of("task",t)); }
            else { method(ex,"POST"); Map<String,Object> b=new LinkedHashMap<>(Api.body(ex)); b.put("id",id); Map<String,Object> v=mutate(parts[5].equals("remind")?"remind":"approval",session,b); Api.ok(ex,Map.of("workflow",v,"task",v.get("approval"))); }
            return true;
        }
        for(String operation:List.of("draft","submit","accept","bid-result")) if(path.equals("/api/demands/"+operation)) {
            method(ex,"POST"); Api.ok(ex,Map.of("workflow",mutate(operation,session,Api.body(ex)))); return true;
        }
        return false;
    }

    /** Package-visible for isolated integration tests; caller supplies a verified current session. */
    static Map<String,Object> mutate(String operation,Auth.Session session,Map<String,Object> input) throws Exception {
        synchronized(Api.MUTATION_LOCK) { try { return Db.transaction(()->{
            OrganizationAccess.Person actor=OrganizationAccessStore.person(session);
            Map<String,Object> b=new LinkedHashMap<>(input);
            String request=requestId(b,operation);
            String payload=Json.write(new TreeMap<>(b));
            Map<String,Object> previous=Db.one("SELECT * FROM workflow_requests WHERE actor_code=? AND request_id=?",actor.personCode(),request);
            if(previous!=null) {
                if(!operation.equals(str(previous,"operation"))||!payload.equals(str(previous,"payload"))) fail(409,"请求编号已用于不同操作，请刷新后重试");
                long previousId=num(previous,"demand_id");
                Map<String,Object> source=load(previousId);
                State frozen=loadState(previousId);
                if(frozen!=null&&frozen.policy().reviewMode()==ReviewMode.SINGLE_EXPLICIT) {
                    String resource=operation.equals("accept")?"demand.accept":operation.equals("approval")&&Set.of("APPROVE","APPROVE_COMBINED","RETURN").contains(str(b,"action"))?"approval.review":"demand.write";
                    requirePermission(session,resource,OrganizationAccess.Action.HANDLE,str(source,"organization_code"));
                    requireCombinedCurrent(frozen,source);
                }
                return detail(previousId,session);
            }
            long id;
            switch(operation) {
                case "draft": id=saveDraft(session,actor,b,request); break;
                case "submit": id=submitDemand(session,actor,b,request); break;
                case "accept": id=acceptDemand(session,actor,b); break;
                case "bid-result": id=bidResult(session,actor,b); break;
                case "approval": id=applyApproval(session,actor,b,request); break;
                case "remind": id=remindApproval(session,actor,b,request); break;
                default: throw new Api.ApiException(404,"办理接口不存在");
            }
            Db.exec("INSERT INTO workflow_requests(actor_code,request_id,operation,payload,demand_id) VALUES(?,?,?,?,?)",actor.personCode(),request,operation,payload,id);
            return detail(id,session);
        }); } catch(Failure failure) { throw approvalError(failure); } }
    }

    private static long saveDraft(Auth.Session session,OrganizationAccess.Person actor,Map<String,Object> b,String request) throws Exception {
        Set<String> permitted=new HashSet<>(FORM); permitted.addAll(LEGACY); permitted.addAll(CONTROL); permitted.addAll(Set.of("hours","training_mode","training_period","expect_date")); whitelist(b,permitted);
        long id=b.containsKey("id")?number(b.get("id"),"需求编号"):0;
        Map<String,Object> old=id>0?locked(id):null;
        if(old!=null) { requireVersion(old,b); requirePermission(session,"demand.write",OrganizationAccess.Action.HANDLE,str(old,"organization_code")); requireOwner(actor,old); if(accepted(id)) fail(409,"需求已受理，原审批资料已锁定"); }
        Map<String,Object> form=old==null?new LinkedHashMap<>(DemandIntake.initialDraft()):json(str(old,"form_json"));
        Map<String,Object> legacy=old==null?new LinkedHashMap<>():json(str(old,"legacy_json"));
        for(String key:FORM) if(b.containsKey(key)) form.put(key,b.get(key));
        for(String key:LEGACY) if(b.containsKey(key)) legacy.put(key,b.get(key));
        if(b.containsKey("training_mode")) form.put("delivery_mode_text",b.get("training_mode"));
        if(b.containsKey("training_period")) form.put("period_text",b.get("training_period"));
        if(b.containsKey("expect_date")) form.put("expected_start_date",b.get("expect_date"));
        if(b.containsKey("hours")) {
            if(b.containsKey("duration_minutes")) fail(422,"请仅填写课时或分钟中的一种");
            String hours=text(b.get("hours"),"预计课时",120);
            if(!hours.isEmpty()&&!hours.matches("[0-9]+(?:\\.[0-9]+)?")) fail(422,"预计课时须为十进制文本");
            form.put("duration_minutes",hours.isEmpty()?"":new BigDecimal(hours).multiply(BigDecimal.valueOf(45)).toPlainString());
            legacy.put("original_hours",hours);
        }
        if(b.containsKey("duration_minutes")) legacy.remove("original_hours");
        form.putIfAbsent("organization_code",actor.organizationCode());
        String org=text(form.get("organization_code"),"所属机构",120);
        requirePermission(session,"demand.write",OrganizationAccess.Action.HANDLE,org);
        if(old!=null&&!str(old,"organization_code").equals(org)) fail(409,"已保存需求不能直接更换所属机构");
        String filler=old==null?actor.personCode():str(old,"filler_code");
        DemandIntake.Validation valid=DemandIntake.validateDraft(form,filler); validate(valid);
        form=new LinkedHashMap<>(valid.values()); form.remove("filler_code");
        validateLegacy(legacy); String title=text(form.get("title"),"需求名称",200);
        text(form.get("delivery_mode_text"),"授课方式",16); text(form.get("period_text"),"授课时段",16);
        State state=old==null?null:loadState(id);
        if(state!=null) requireCombinedCurrent(state,old);
        if(state!=null&&(state.status()==Status.READY_FOR_TEAM)) fail(409,"审批已完成，不能直接修改原审批资料");
        if(old!=null&&!bool(old,"draft")&&!str(old,"business_path").equals(str(form,"business_path"))) fail(409,"已提交需求不能更换承接路径");
        if(old!=null&&Db.one("SELECT demand_id FROM workflow_bid_results WHERE demand_id=?",id)!=null) fail(409,"已登记结果，原需求资料已锁定");
        long revision=old==null?1:num(old,"data_revision")+1;
        if(old==null) {
            id=Db.insert("INSERT INTO demands(title,unit,contact,phone,hours,content,teacher_req,expect_date,status,remark,training_province,training_city,training_mode,training_period) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",title,str(legacy,"unit"),str(legacy,"contact"),str(legacy,"phone"),legacyHours(form),str(legacy,"content"),str(legacy,"teacher_req"),str(form,"expected_start_date"),"待处理",str(legacy,"remark"),str(legacy,"training_province"),str(legacy,"training_city"),legacyMode(form),legacyPeriod(form));
            Db.exec("INSERT INTO workflow_demands VALUES(?,?,?,?,?,?,?,?,?,?)",id,1,revision,true,str(form,"business_path"),org,filler,Json.write(form),Json.write(legacy),DemandIntake.RULE_VERSION);
        } else {
            update("UPDATE workflow_demands SET version=version+1,data_revision=?,business_path=?,form_json=?,legacy_json=? WHERE demand_id=? AND version=?",revision,str(form,"business_path"),Json.write(form),Json.write(legacy),id,num(old,"version"));
            Db.exec("UPDATE demands SET title=?,unit=?,contact=?,phone=?,hours=?,content=?,teacher_req=?,expect_date=?,remark=?,training_province=?,training_city=?,training_mode=?,training_period=? WHERE id=?",title,str(legacy,"unit"),str(legacy,"contact"),str(legacy,"phone"),legacyHours(form),str(legacy,"content"),str(legacy,"teacher_req"),str(form,"expected_start_date"),str(legacy,"remark"),str(legacy,"training_province"),str(legacy,"training_city"),legacyMode(form),legacyPeriod(form),id);
            if(state!=null&&state.stage()!=Stage.NONE) {
                String comment=text(b.get("change_comment"),"变更说明",2000); if(comment.isBlank()) fail(422,"审批中修改请填写变更说明");
                State after=apply(state,new Command(Action.REVISE,state.version(),state.stage(),request,comment,revision,Instant.now()),access(actor)); saveState(state,after);
            }
        }
        Db.exec("INSERT INTO workflow_documents VALUES(?,?,?)",id,revision,Json.write(Map.of("form",form,"legacy",legacy)));
        return id;
    }

    private static long submitDemand(Auth.Session session,OrganizationAccess.Person actor,Map<String,Object> b,String request) throws Exception {
        whitelist(b,Set.of("id","expected_version","request_id")); long id=number(b.get("id"),"需求编号"); Map<String,Object> row=locked(id); requireVersion(row,b); requireOwner(actor,row);
        requirePermission(session,"demand.write",OrganizationAccess.Action.HANDLE,str(row,"organization_code"));
        if(!bool(row,"draft")) fail(409,"需求已提交，请使用审批重提动作");
        Map<String,Object> form=json(str(row,"form_json")); validate(DemandIntake.validateForSubmission(form,str(row,"filler_code"))); validateContact(form);
        if(str(json(str(row,"legacy_json")),"unit").isBlank()) fail(422,"请填写需求单位");
        if("direct".equals(str(row,"business_path"))) {
            OrganizationAccess.Person filler=personByCode(str(row,"filler_code"));
            Participants participants=new Participants(filler.personCode(),filler.leaderPersonCode(),filler.bpPersonCode());
            requireActive(participants.leaderId()); requireActive(participants.bpId());
            if(participants.submitterId().equals(participants.leaderId())||participants.submitterId().equals(participants.bpId())) fail(422,"填报人不能审批自己的需求，请先核对人员配置");
            Policy policy=Policy.baseline(POLICY);
            if(participants.leaderId().equals(participants.bpId())) {
                CombinedAssignment assignment=OrganizationAccessStore.combinedAssignment(str(row,"organization_code"),participants.leaderId());
                if(assignment==null) fail(422,"同人兼任的职责、机构范围、账号和审批授权尚未完整核实");
                policy=Policy.combinedBaseline(COMBINED_POLICY,assignment);
            }
            State after=ApprovalWorkflow.submit(id,id,str(form,"title"),"DIRECT",participants,policy,num(row,"data_revision"),request,Instant.now(),access(actor),str(row,"organization_code")); saveState(null,after);
        }
        update("UPDATE workflow_demands SET draft=FALSE,version=version+1 WHERE demand_id=? AND version=?",id,num(row,"version")); return id;
    }

    private static long bidResult(Auth.Session session,OrganizationAccess.Person actor,Map<String,Object> b) throws Exception {
        whitelist(b,Set.of("id","expected_version","request_id","result","external_approval_ref","result_date","result_note")); long id=number(b.get("id"),"需求编号"); Map<String,Object> row=locked(id); requireVersion(row,b);
        requirePermission(session,"bid.result",OrganizationAccess.Action.HANDLE,str(row,"organization_code"));
        Map<String,Object> form=json(str(row,"form_json"));
        if(!actor.personCode().equals(str(form,"internal_contact_code"))) fail(403,"仅该需求已授权的内部对接人可登记签报结果");
        Map<String,Object> result=new LinkedHashMap<>(); for(String key:List.of("result","external_approval_ref","result_date","result_note")) if(b.containsKey(key)) result.put(key,b.get(key));
        if(!str(form,"external_approval_ref").equals(str(result,"external_approval_ref"))) fail(422,"结果签报编号必须与该需求保存的原签报一致");
        DemandIntake.Validation valid=DemandIntake.validateBidResult(readiness(row),result,new DemandIntake.Access(actor.personCode(),Set.of(DemandIntake.Capability.REGISTER_BID_RESULT))); validate(valid);
        Db.exec("INSERT INTO workflow_bid_results VALUES(?,?,?,?,?,?,?)",id,str(result,"result"),str(result,"external_approval_ref"),str(result,"result_date"),str(result,"result_note"),actor.personCode(),Instant.now().toString());
        update("UPDATE workflow_demands SET version=version+1 WHERE demand_id=? AND version=?",id,num(row,"version"));
        if("lost".equals(str(result,"result"))) Db.exec("UPDATE demands SET status='已流标' WHERE id=?",id);
        return id;
    }

    private static long acceptDemand(Auth.Session session,OrganizationAccess.Person actor,Map<String,Object> b) throws Exception {
        whitelist(b,Set.of("id","expected_version","request_id","team_code")); long id=number(b.get("id"),"需求编号"); Map<String,Object> row=locked(id); requireVersion(row,b);
        requirePermission(session,"demand.accept",OrganizationAccess.Action.HANDLE,str(row,"organization_code"));
        String team=text(b.get("team_code"),"承接团队",120);
        if(!actor.organizationCode().equals(team)) fail(403,"承接团队须为当前获授权人员所属机构");
        State approval=loadState(id); if("direct".equals(str(row,"business_path"))) { if(approval==null) fail(409,"需求尚未提交审批"); requireCombinedCurrent(approval,row); requireReadyForTeam(approval,num(row,"data_revision")); }
        validate(DemandIntake.validateTeamAcceptance(readiness(row),team,new DemandIntake.Access(actor.personCode(),Set.of(DemandIntake.Capability.ACCEPT_TEAM))));
        Map<String,Object> form=json(str(row,"form_json")), legacy=json(str(row,"legacy_json"));
        int participants;
        try { participants=new BigInteger(str(form,"participant_count")).intValueExact(); }
        catch(ArithmeticException e) { throw new Api.ApiException(422,"参训人数超出原项目可保存范围"); }
        long project=Db.insert("INSERT INTO projects(demand_id,bid_id,title,unit,hours,amount,start_date,end_date,owner,participant_count,delivery_mode,status,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,null,str(form,"title"),str(legacy,"unit"),legacyHours(form),0,str(form,"expected_start_date"),str(form,"expected_end_date"),"",participants,str(form,"delivery_mode_text"),"待启动",str(legacy,"remark"));
        Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,?,?,?,?)",id,project,team,actor.personCode(),num(row,"data_revision"),Instant.now().toString());
        update("UPDATE workflow_demands SET version=version+1 WHERE demand_id=? AND version=?",id,num(row,"version"));
        Db.exec("UPDATE demands SET status='已立项' WHERE id=?",id);
        outbox("m02:"+id+":accepted",id,Map.of("kind","TEAM_ACCEPTED","recipient",str(row,"filler_code"),"project_id",project));
        return id;
    }

    private static long applyApproval(Auth.Session session,OrganizationAccess.Person actor,Map<String,Object> b,String request) throws Exception {
        whitelist(b,Set.of("id","action","expectedVersion","expectedStage","requestId","comment")); long id=number(b.get("id"),"审批编号"); Map<String,Object> row=locked(id);
        State before=loadState(id); if(before==null) fail(404,"审批记录不存在");
        Action action; Stage stage; try { action=Action.valueOf(str(b,"action")); stage=Stage.valueOf(str(b,"expectedStage")); } catch(IllegalArgumentException e) { throw new Api.ApiException(422,"审批动作或节点无效"); }
        if(action==Action.SUBMIT||action==Action.REVISE) fail(422,"请从需求资料入口提交或修改");
        requirePermission(session,action==Action.APPROVE||action==Action.APPROVE_COMBINED||action==Action.RETURN?"approval.review":"demand.write",OrganizationAccess.Action.HANDLE,str(row,"organization_code"));
        requireCombinedCurrent(before,row);
        if(accepted(id)) fail(409,"需求已受理，不能更改审批");
        if(action==Action.RESUBMIT) { Map<String,Object> form=json(str(row,"form_json")); validate(DemandIntake.validateForSubmission(form,str(row,"filler_code"))); validateContact(form); if(str(json(str(row,"legacy_json")),"unit").isBlank()) fail(422,"请填写需求单位"); }
        State after=apply(before,new Command(action,number(b.get("expectedVersion"),"审批版本"),stage,request,text(b.get("comment"),"办理意见",2000),num(row,"data_revision"),Instant.now()),access(actor)); saveState(before,after);
        update("UPDATE workflow_demands SET version=version+1 WHERE demand_id=? AND version=?",id,num(row,"version")); return id;
    }

    private static long remindApproval(Auth.Session session,OrganizationAccess.Person actor,Map<String,Object> b,String request) throws Exception {
        whitelist(b,Set.of("id","expectedVersion","requestId")); long id=number(b.get("id"),"审批编号"); Map<String,Object> row=locked(id); requirePermission(session,"demand.write",OrganizationAccess.Action.HANDLE,str(row,"organization_code")); State state=loadState(id); if(state==null) fail(404,"审批记录不存在");
        requireCombinedCurrent(state,row);
        List<Reminder> ledger=new ArrayList<>(); for(Map<String,Object> r:Db.query("SELECT * FROM approval_reminders WHERE workflow_id=? ORDER BY created_at",id)) ledger.add(new Reminder(id,num(r,"event_version"),str(r,"request_id"),str(r,"actor_code"),str(r,"recipient_code"),Instant.parse(str(r,"created_at"))));
        Reminder r=remind(state,number(b.get("expectedVersion"),"审批版本"),request,Instant.now(),access(actor),ledger);
        Db.exec("INSERT INTO approval_reminders VALUES(?,?,?,?,?,?)",id,r.requestId(),r.eventVersion(),r.actorId(),r.recipientId(),r.at().toString());
        outbox("m03:"+id+":remind:"+request,id,Map.of("kind","REMINDER","recipient",r.recipientId())); return id;
    }

    public static Map<String,Object> context(Auth.Session session) throws Exception {
        OrganizationAccess.Person actor=OrganizationAccessStore.person(session);
        List<Map<String,Object>> organizations=new ArrayList<>(),people=new ArrayList<>(); Set<String> readable=new HashSet<>(); boolean canCreate=false,canAccept=false;
        OrganizationAccess.Configuration config=OrganizationAccessStore.configuration();
        for(OrganizationAccess.Organization org:config.organizations()) {
            boolean read=allowed(session,"demand.read",OrganizationAccess.Action.VIEW,org.organizationCode());
            boolean write=allowed(session,"demand.write",OrganizationAccess.Action.HANDLE,org.organizationCode());
            boolean accept=allowed(session,"demand.accept",OrganizationAccess.Action.HANDLE,org.organizationCode());
            if(!read) continue; readable.add(org.organizationCode()); canCreate|=write; canAccept|=accept;
            organizations.add(Map.of("code",org.organizationCode(),"label",org.organizationCode(),"can_read",true,"can_write",write,"can_accept",accept));
        }
        for(OrganizationAccess.Person p:config.people()) {
            Set<String> covered=new TreeSet<>(p.responsibleOrganizationCodes()); covered.retainAll(readable);
            if(p.enabled()&&(readable.contains(p.organizationCode())||!covered.isEmpty())) people.add(Map.of("code",p.personCode(),"label",p.personCode(),"organization_code",readable.contains(p.organizationCode())?p.organizationCode():"","responsible_organization_codes",covered));
        }
        return Map.of("person",Map.of("person_code",actor.personCode(),"organization_code",actor.organizationCode()),"organizations",organizations,"people",people,"can_create",canCreate,"teams",canAccept?List.of(Map.of("code",actor.organizationCode(),"label",actor.organizationCode())):List.of());
    }

    public static Map<String,Object> detail(long id,Auth.Session session) throws Exception {
        Map<String,Object> row=load(id); String org=str(row,"organization_code");
        requirePermission(session,"demand.read",OrganizationAccess.Action.VIEW,org); OrganizationAccess.Person actor=OrganizationAccessStore.person(session);
        State state=loadState(id); Map<String,Object> form=json(str(row,"form_json")); Map<String,Object> view=new LinkedHashMap<>();
        String combinedProblem=combinedProblem(state,row);
        DemandIntake.Readiness facts=readiness(row); String queue=DemandIntake.queueDisposition(facts); Map<String,Object> acceptance=Db.one("SELECT project_id FROM workflow_acceptances WHERE demand_id=?",id);
        boolean own=actor.personCode().equals(str(row,"filler_code")),write=allowed(session,"demand.write",OrganizationAccess.Action.HANDLE,org);
        boolean editable=combinedProblem.isEmpty()&&own&&write&&!facts.alreadyAccepted()&&(state==null||state.status()!=Status.READY_FOR_TEAM)&&facts.bidResult().isEmpty();
        view.put("id",id); view.put("version",num(row,"version")); view.put("data_revision",num(row,"data_revision")); view.put("draft",bool(row,"draft")); view.put("business_path",str(row,"business_path")); view.put("organization_code",org); view.put("filler_code",str(row,"filler_code")); view.put("form",form); view.put("legacy",json(str(row,"legacy_json"))); view.put("queue",queue); view.put("project_id",acceptance==null?null:num(acceptance,"project_id"));
        view.put("status_label",statusLabel(row,state,queue));
        view.put("approval_blocked_reason",combinedProblem);
        view.put("actions",Map.of("edit",editable,"submit",own&&write&&bool(row,"draft"),"accept",queue.equals("ready")&&allowed(session,"demand.accept",OrganizationAccess.Action.HANDLE,org),"bid_result",queue.equals("waiting_bid_result")&&actor.personCode().equals(str(form,"internal_contact_code"))&&allowed(session,"bid.result",OrganizationAccess.Action.HANDLE,org)));
        Map<String,Object> approval=null;
        if(state!=null) {
            approval=ApprovalWorkflow.view(state,access(actor),num(row,"data_revision"));
            List<Map<String,Object>> actions=new ArrayList<>();
            for(Object raw:(List<?>)approval.get("actions")) {
                Map<String,Object> action=map(raw); String key=str(action,"action"); boolean review=key.equals("APPROVE")||key.equals("APPROVE_COMBINED")||key.equals("RETURN");
                if(!allowed(session,review?"approval.review":"demand.write",OrganizationAccess.Action.HANDLE,org)||facts.alreadyAccepted()) { action.put("enabled",false); action.put("reason","当前账号没有该业务操作权限"); action.put("code","FORBIDDEN"); }
                if(!combinedProblem.isEmpty()) { action.put("enabled",false); action.put("reason",combinedProblem); action.put("code","COMBINED_ASSIGNMENT_CHANGED"); }
                actions.add(action);
            }
            approval.put("actions",actions);
            approval.put("paused",!combinedProblem.isEmpty()); approval.put("blocked_reason",combinedProblem);
            boolean remindable=false; String remindReason="当前账号不能催办该需求";
            if(!combinedProblem.isEmpty()) remindReason=combinedProblem;
            else if(own&&write) try {
                remind(state,state.version(),"availability-"+UUID.randomUUID(),Instant.now(),access(actor),reminderLedger(id));
                remindable=true; remindReason="";
            } catch(Failure failure) { remindReason=failure.getMessage(); }
            approval.put("can_remind",remindable); approval.put("remind",Map.of("enabled",remindable,"reason",remindReason));
        }
        view.put("approval",approval); view.put("bid_result",Db.one("SELECT result,external_approval_ref,result_date,result_note,actor_code FROM workflow_bid_results WHERE demand_id=?",id));
        return view;
    }

    public static boolean managedDemand(long id) throws Exception { return id>0&&Db.one("SELECT demand_id FROM workflow_demands WHERE demand_id=?",id)!=null; }
    public static boolean managesProject(long id) throws Exception { return id>0&&Db.one("SELECT demand_id FROM workflow_acceptances WHERE project_id=?",id)!=null; }
    public static void requireProjectAccess(long projectId,Auth.Session session,boolean write) throws Exception {
        Map<String,Object> managed=Db.one("SELECT d.organization_code FROM workflow_acceptances a JOIN workflow_demands d ON d.demand_id=a.demand_id WHERE a.project_id=?",projectId);
        if(managed!=null) requirePermission(session,write?"demand.write":"demand.read",write?OrganizationAccess.Action.HANDLE:OrganizationAccess.Action.VIEW,str(managed,"organization_code"));
    }

    /** body=null denotes deletion; bids/win and projects/start denote old dedicated actions. */
    public static void guardLegacyMutation(String mod,long id,Map<String,Object> body) throws Exception {
        if(id==0&&(mod.equals("demands")||mod.equals("projects"))) fail(409,mod.equals("demands")?"请通过需求草稿入口新增并提交":"新项目须通过需求团队受理生成");
        if(mod.equals("demands")&&managedDemand(id)) fail(409,"该需求已接入审批，请使用需求草稿或专用办理入口");
        if(mod.equals("bids")||mod.equals("bids/win")) {
            Map<String,Object> bid=id>0?Db.one("SELECT demand_id FROM bids WHERE id=?",id):null;
            if((bid!=null&&managedDemand(num(bid,"demand_id")))||(body!=null&&body.containsKey("demand_id")&&managedDemand(number(body.get("demand_id"),"需求编号")))) fail(409,"新流程投标需求请在需求行登记原签报结果，不能中标自动立项");
        }
        if(mod.equals("projects")||mod.equals("projects/start")) {
            Map<String,Object> p=id>0?Db.one("SELECT demand_id,bid_id FROM projects WHERE id=?",id):null;
            if(p==null) return; long demand=num(p,"demand_id");
            if(managedDemand(demand)) {
                Map<String,Object> acceptance=Db.one("SELECT * FROM workflow_acceptances WHERE demand_id=? AND project_id=?",demand,id);
                if(acceptance==null) fail(409,"项目缺少团队受理来源，不能继续办理");
                if(mod.equals("projects/start")) {
                    Map<String,Object> row=load(demand); if("direct".equals(str(row,"business_path"))) requireReadyForTeam(loadState(demand),num(row,"data_revision"));
                    if(!readiness(row).alreadyAccepted()||!DemandIntake.queueDisposition(readiness(row)).equals("accepted")) fail(409,"项目来源流程不完整");
                } else {
                    if(body==null) fail(409,"已受理项目须保留来源记录，不能删除");
                    for(String field:List.of("demand_id","bid_id")) if(body.containsKey(field)&&optionalNumber(body.get(field))!=num(p,field)) fail(409,"不能更换项目来源");
                }
            } else if(body!=null&&body.containsKey("demand_id")&&managedDemand(optionalNumber(body.get("demand_id")))) fail(409,"不能将历史项目改绑至新流程需求");
        }
    }

    public static List<Map<String,Object>> visibleRows(String mod,List<Map<String,Object>> rows,Auth.Session session) throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> original:rows) {
            Map<String,Object> row=new LinkedHashMap<>(original); long demand=relatedDemand(mod,row);
            if(!managedDemand(demand)) { result.add(row); continue; }
            Map<String,Object> owned=load(demand);
            if(!allowed(session,"demand.read",OrganizationAccess.Action.VIEW,str(owned,"organization_code"))) continue;
            if(mod.equals("demands")) { Map<String,Object> workflow=detail(demand,session); row.put("workflow",workflow); row.put("actions",workflow.get("actions")); }
            if(mod.equals("projects")) {
                Map<String,Object> acceptance=Db.one("SELECT team_code FROM workflow_acceptances WHERE demand_id=? AND project_id=?",demand,num(row,"id"));
                if(acceptance!=null) row.put("workflow_source",Map.of("demand_id",demand,"business_path",str(owned,"business_path"),"organization_code",str(owned,"organization_code"),"team_code",str(acceptance,"team_code"),"can_delete",false));
            }
            result.add(row);
        }
        return result;
    }
    private static long relatedDemand(String mod,Map<String,Object> row) throws Exception {
        if(mod.equals("demands")) return num(row,"id"); if(mod.equals("bids")||mod.equals("projects")) return num(row,"demand_id");
        long project=num(row,"project_id");
        if((mod.equals("q_sends")||mod.equals("q_responses"))&&project==0) { Map<String,Object> q=Db.one("SELECT project_id FROM questionnaires WHERE id=?",num(row,"questionnaire_id")); if(q!=null) project=num(q,"project_id"); }
        if(project>0) { Map<String,Object> p=Db.one("SELECT demand_id FROM projects WHERE id=?",project); return p==null?0:num(p,"demand_id"); }
        return 0;
    }

    private static DemandIntake.Readiness readiness(Map<String,Object> row) throws Exception {
        long id=num(row,"demand_id"); State state=loadState(id); boolean approved=false;
        if(state!=null&&combinedProblem(state,row).isEmpty()) try { requireReadyForTeam(state,num(row,"data_revision")); approved=true; } catch(Failure ignored) { }
        Map<String,Object> result=Db.one("SELECT result FROM workflow_bid_results WHERE demand_id=?",id);
        return new DemandIntake.Readiness(id,str(row,"business_path"),bool(row,"draft"),approved,result==null?"":str(result,"result"),accepted(id));
    }
    private static String statusLabel(Map<String,Object> row,State state,String queue) {
        if(queue.equals("accepted")) return "团队已受理"; if(bool(row,"draft")) return "草稿";
        if(state!=null) return switch(state.status()) { case LEADER_PENDING->state.policy().reviewMode()==ReviewMode.SINGLE_EXPLICIT?"待负责人及BP审批（兼任）":"负责人待审";case BP_PENDING->"BP待审";case RETURNED->"已退回";case WITHDRAWN->"已撤回";case READY_FOR_TEAM->"待团队受理"; };
        return switch(queue) {case "ready"->"中标待受理";case "archived_lost"->"未中标留档";case "waiting_bid_result"->"待登记投标结果";default->"流程资料需核对";};
    }

    private static void saveState(State before,State after) throws Exception {
        Map<String,Object> saved=stateMap(after);
        if(before==null) Db.exec("INSERT INTO approval_workflows VALUES(?,?,?)",after.id(),after.version(),Json.write(saved));
        else update("UPDATE approval_workflows SET version=?,state_json=? WHERE id=? AND version=?",after.version(),Json.write(saved),after.id(),before.version());
        Map<String,Object> event=map(((List<?>)saved.get("history")).get(after.history().size()-1));
        Db.exec("INSERT INTO approval_events VALUES(?,?,?,?)",after.id(),after.version(),after.history().get(after.history().size()-1).requestId(),Json.write(event));
        for(Notice notice:notices(before,after)) outbox(notice.deduplicationKey(),after.businessId(),Map.of("kind",notice.kind().name(),"target",notice.targetType().name(),"recipient",notice.targetId(),"event_version",notice.eventVersion()));
    }
    private static State loadState(long id) throws Exception {
        Map<String,Object> stored=Db.one("SELECT * FROM approval_workflows WHERE id=?",id); if(stored==null) return null;
        try {
        Map<String,Object> m=json(str(stored,"state_json")); List<Map<String,Object>> eventRows=Db.query("SELECT version,request_id,event_json FROM approval_events WHERE workflow_id=? ORDER BY version",id);
        List<Event> history=new ArrayList<>();
        for(Map<String,Object> row:eventRows) {
            Event event=ApprovalWorkflowSnapshots.decodeEvent(json(str(row,"event_json")));
            if(event.version()!=num(row,"version")||!event.requestId().equals(str(row,"request_id"))) fail(409,"审批事件索引与快照不一致");
            history.add(event);
        }
        if(num(stored,"version")!=num(m,"version")) fail(409,"审批快照版本不一致，请联系管理员核对");
        Map<String,Object> p=map(m.get("participants")); Policy rules=ApprovalWorkflowSnapshots.decodePolicy(map(m.get("policy")));
        if(!rules.version().equals(str(m,"policyVersion"))) fail(409,"审批策略版本与冻结策略不一致");
        if(m.containsKey("reviewMode")&&!rules.reviewMode().name().equals(str(m,"reviewMode"))) fail(409,"审批模式与冻结策略不一致");
        List<Event> embedded=new ArrayList<>(); for(Object raw:(List<?>)m.get("history")) embedded.add(ApprovalWorkflowSnapshots.decodeEvent(map(raw)));
        if(!history.equals(embedded)||num(m,"id")!=id||num(m,"businessId")!=id) fail(409,"审批事件与冻结历史不一致");
        State state=new State(id,id,str(m,"title"),new Participants(str(p,"submitterId"),str(p,"leaderId"),str(p,"bpId")),rules,Status.valueOf(str(m,"status")),num(m,"version"),num(m,"dataRevision"),Math.toIntExact(num(m,"round")),bool(m,"leaderApproved"),bool(m,"bpApproved"),Stage.valueOf(str(m,"returnedStage")),str(m,"returnTargetId"),history);
        Map<String,Object> source=load(id);
        if(!state.participants().submitterId().equals(str(source,"filler_code"))||rules.combinedAssignment()!=null&&!rules.combinedAssignment().organizationCode().equals(str(source,"organization_code"))) fail(409,"审批冻结机构或填报来源不一致");
        return state;
        } catch(RuntimeException invalid) { throw new Api.ApiException(409,"审批冻结策略或事件损坏，请联系管理员核对"); }
    }
    private static Map<String,Object> stateMap(State state) {
        Map<String,Object> map=ApprovalWorkflow.view(state,new Access(state.participants().submitterId(),Map.of(),Map.of()),state.dataRevision());
        map.remove("actions"); Map<String,Object> policy=ApprovalWorkflowSnapshots.encodePolicy(state.policy());
        map.put("policy",policy);map.put("leaderApproved",state.leaderApproved());map.put("bpApproved",state.bpApproved());map.put("returnedStage",state.returnedStage().name());map.put("returnTargetId",state.returnTargetId());return map;
    }

    /** Current authorization can pause a frozen flow, but never rewrite its assignment or policy. */
    private static String combinedProblem(State state,Map<String,Object> row) throws Exception {
        if(state==null||state.policy().reviewMode()!=ReviewMode.SINGLE_EXPLICIT) return "";
        CombinedAssignment frozen=state.policy().combinedAssignment();
        OrganizationAccess.Configuration config=OrganizationAccessStore.configuration();
        OrganizationAccess.Person filler=null;
        if(config!=null) for(OrganizationAccess.Person person:config.people()) if(person.personCode().equals(str(row,"filler_code"))) filler=person;
        if(filler==null||!active(filler,config)||!frozen.approverId().equals(filler.leaderPersonCode())||!frozen.approverId().equals(filler.bpPersonCode()))
            return "当前填报人的负责人或BP关系已变化，兼任审批暂停，请核对人员配置";
        CombinedAssignment current=OrganizationAccessStore.combinedAssignment(str(row,"organization_code"),frozen.approverId());
        if(current==null||!current.organizationCode().equals(frozen.organizationCode())||!current.approverId().equals(frozen.approverId())||!current.evidenceRef().equals(frozen.evidenceRef()))
            return "当前兼任职责、机构授权或依据已变化，保留历史并暂停办理";
        return ""; // A directory version alone does not rewrite or invalidate unchanged evidence.
    }
    private static void requireCombinedCurrent(State state,Map<String,Object> row) throws Exception {
        String problem=combinedProblem(state,row); if(!problem.isEmpty()) fail(409,problem);
    }

    /** S01 validates immutable approval evidence without applying today's directory to past notices. */
    static void validateCombinedNotice(long id,Map<String,Object> event) throws Exception {
        State state=loadState(id); Event decoded;
        try { decoded=ApprovalWorkflowSnapshots.decodeEvent(event); } catch(Failure invalid) { throw approvalError(invalid); }
        if(state==null||state.policy().reviewMode()!=ReviewMode.SINGLE_EXPLICIT||decoded.action()!=Action.APPROVE_COMBINED||!state.history().contains(decoded)) fail(409,"兼任审批通知缺少可信冻结依据");
        requireReadyForTeam(state,decoded.dataRevision());
    }

    private static Access access(OrganizationAccess.Person actor) throws Exception {
        Map<String,Activity> activity=new HashMap<>(); OrganizationAccess.Configuration c=OrganizationAccessStore.configuration();
        if(c!=null) for(OrganizationAccess.Person p:c.people()) activity.put(p.personCode(),active(p,c)&&boundAccountActive(p.personCode(),c)?Activity.ACTIVE:Activity.INACTIVE);
        return new Access(actor.personCode(),activity,Map.of());
    }
    private static boolean active(OrganizationAccess.Person p,OrganizationAccess.Configuration c) {
        if(!p.enabled()) return false; String org=p.organizationCode(); Set<String> seen=new HashSet<>();
        while(org!=null&&!org.isEmpty()) { if(!seen.add(org)) return false; OrganizationAccess.Organization found=null; for(OrganizationAccess.Organization o:c.organizations()) if(o.organizationCode().equals(org)) {found=o;break;} if(found==null||!found.enabled()) return false;org=found.parentOrganizationCode(); }
        return true;
    }
    private static OrganizationAccess.Person personByCode(String code) throws Exception {
        OrganizationAccess.Configuration c=OrganizationAccessStore.configuration(); if(c!=null) for(OrganizationAccess.Person p:c.people()) if(p.personCode().equals(code)&&active(p,c)) return p;
        throw new Api.ApiException(422,"相关人员尚未配置或已停用");
    }
    private static void requireActive(String code) throws Exception {
        personByCode(code);
        if(!boundAccountActive(code,OrganizationAccessStore.configuration())) fail(422,"审批人员尚未绑定启用的登录账号，请先完善人员配置");
    }
    private static boolean boundAccountActive(String code,OrganizationAccess.Configuration configuration) throws Exception {
        if(configuration!=null) for(OrganizationAccess.AccountBinding binding:configuration.accountBindings()) if(binding.personCode().equals(code)&&binding.enabled()) {
            Map<String,Object> account=Db.one("SELECT status FROM users WHERE id=?",binding.accountId());
            return account!=null&&num(account,"status")==1;
        }
        return false;
    }
    private static void validateContact(Map<String,Object> form) throws Exception {
        OrganizationAccess.Person p=personByCode(str(form,"internal_contact_code")); String org=str(form,"organization_code"); if(!p.organizationCode().equals(org)&&!p.responsibleOrganizationCodes().contains(org)) fail(422,"内部对接人不属于该机构或未明确负责该机构");
    }
    private static boolean allowed(Auth.Session s,String resource,OrganizationAccess.Action action,String org) throws Exception { return OrganizationAccessStore.authorize(s,resource,action,org).allowed(); }
    private static void requirePermission(Auth.Session s,String resource,OrganizationAccess.Action action,String org) throws Exception { OrganizationAccess.Decision decision=OrganizationAccessStore.authorize(s,resource,action,org); if(!decision.allowed()) fail(403,decision.reason()); }
    private static void requireOwner(OrganizationAccess.Person actor,Map<String,Object> row) throws Exception { if(!actor.personCode().equals(str(row,"filler_code"))) fail(403,"仅实际填报人可修改或提交该需求"); }
    private static Map<String,Object> locked(long id) throws Exception { Map<String,Object> row=Db.one("SELECT * FROM workflow_demands WHERE demand_id=? FOR UPDATE",id); if(row==null) fail(404,"需求流程不存在"); return row; }
    private static Map<String,Object> load(long id) throws Exception { Map<String,Object> row=Db.one("SELECT * FROM workflow_demands WHERE demand_id=?",id); if(row==null) fail(404,"需求流程不存在"); return row; }
    private static boolean accepted(long id) throws Exception { return Db.one("SELECT demand_id FROM workflow_acceptances WHERE demand_id=?",id)!=null; }
    private static void requireVersion(Map<String,Object> row,Map<String,Object> b) throws Exception { if(number(b.get("expected_version"),"需求版本")!=num(row,"version")) fail(409,"需求已更新，请刷新后办理"); }
    private static void update(String sql,Object... args) throws Exception { try(PreparedStatement ps=Db.get().prepareStatement(sql)) { for(int i=0;i<args.length;i++) ps.setObject(i+1,args[i]); if(ps.executeUpdate()!=1) fail(409,"单据已更新，请刷新后办理"); } }
    private static void outbox(String key,long demand,Map<String,Object> payload) throws Exception { NotificationChannelsIntegration.appendOutboxInTransaction(key,demand,payload); }
    private static List<Reminder> reminderLedger(long id) throws Exception {
        List<Reminder> result=new ArrayList<>();
        for(Map<String,Object> r:Db.query("SELECT * FROM approval_reminders WHERE workflow_id=? ORDER BY created_at",id)) result.add(new Reminder(id,num(r,"event_version"),str(r,"request_id"),str(r,"actor_code"),str(r,"recipient_code"),Instant.parse(str(r,"created_at"))));
        return result;
    }
    private static void validate(DemandIntake.Validation v) throws Exception { if(!v.valid()) fail(422,v.errors().get(0).message()); }
    private static void validateLegacy(Map<String,Object> legacy) throws Exception {
        for(String key:LEGACY) if(legacy.containsKey(key)) legacy.put(key,text(legacy.get(key),key,key.equals("unit")?200:key.equals("contact")?64:key.equals("phone")?32:key.startsWith("training_")?64:10000));
        try { DispatchPreference.validateRegion(legacy,"training_province","training_city",false); }
        catch(IllegalArgumentException invalid) { throw new Api.ApiException(400,invalid.getMessage()); }
    }
    private static double legacyHours(Map<String,Object> form) throws Exception { String minutes=str(form,"duration_minutes"); return minutes.isEmpty()?0:finite(new BigDecimal(minutes).divide(BigDecimal.valueOf(45),2,RoundingMode.HALF_UP).toPlainString()); }
    // Old recommendation enums cannot interpret arbitrary M02 labels. Preserve originals in form_json.
    private static String legacyMode(Map<String,Object> form) { String value=str(form,"delivery_mode_text"); return Set.of("","线上","线下","待定").contains(value)?value:"待定"; }
    private static String legacyPeriod(Map<String,Object> form) { String value=str(form,"period_text"); return Set.of("","上午","下午","全天","待定").contains(value)?value:"待定"; }
    private static double finite(String value) throws Exception { if(value.isEmpty()) return 0; double result=new BigDecimal(value).doubleValue(); if(!Double.isFinite(result)) fail(422,"数值超出原系统可显示范围"); return result; }
    private static String requestId(Map<String,Object> b,String op) throws Exception { String key=op.equals("approval")||op.equals("remind")?"requestId":"request_id"; String request=text(b.get(key),"请求编号",160); if(request.isBlank()) fail(422,"缺少请求编号，请刷新后重试"); return request; }
    private static void whitelist(Map<String,Object> body,Set<String> allowed) throws Exception { for(String key:body.keySet()) if(!allowed.contains(key)) fail(422,"该操作不接受字段："+key); }
    private static void method(HttpExchange ex,String method) throws Exception { if(!method.equals(ex.getRequestMethod())&&!(method.equals("GET")&&ex.getRequestMethod().equals("HEAD"))) fail(405,"请求方法不受支持"); }
    private static String text(Object value,String label,int max) throws Exception { if(value==null) return ""; if(!(value instanceof String)) fail(422,label+"须为文本"); String result=((String)value).strip(); if(result.length()>max) fail(422,label+"超过长度限制"); return result; }
    private static long number(Object value,String label) throws Exception { try { BigDecimal n=new BigDecimal(Objects.toString(value,"")); long result=n.longValueExact(); if(result<=0||result>9007199254740991L) throw new ArithmeticException(); return result; } catch(Exception e) { throw new Api.ApiException(422,label+"须为有效正整数"); } }
    private static long optionalNumber(Object value) throws Exception { return value==null||value.toString().isBlank()||value.toString().equals("0")||value.toString().equals("0.0")?0:number(value,"关联编号"); }
    private static long num(Map<String,Object> map,String key) { Object value=map.get(key); return value==null||value.toString().isBlank()?0:new BigDecimal(value.toString()).longValueExact(); }
    private static String str(Map<String,?> map,String key) { return Objects.toString(map.get(key),""); }
    private static boolean bool(Map<String,Object> map,String key) { return Boolean.TRUE.equals(map.get(key))||"true".equalsIgnoreCase(str(map,key)); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return new LinkedHashMap<>((Map<String,Object>)value); }
    private static Map<String,Object> json(String value) { return map(Json.parse(value)); }
    private static Map<String,Object> withoutApproval(Map<String,Object> source) { Map<String,Object> copy=new LinkedHashMap<>(source);copy.remove("approval");return copy; }
    private static Api.ApiException approvalError(Failure failure) { int code=Set.of("FORBIDDEN","POLICY_DENIED").contains(failure.code)?403:Set.of("STALE_VERSION","STALE_STAGE","DUPLICATE_REQUEST","DOCUMENT_CHANGED","APPROVAL_INCOMPLETE","INVALID_TRANSITION").contains(failure.code)?409:422; return new Api.ApiException(code,failure.getMessage()); }
    private static void fail(int code,String message) throws Api.ApiException { throw new Api.ApiException(code,message); }
}
