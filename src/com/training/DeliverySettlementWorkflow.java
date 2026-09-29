package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.training.DeliverySettlementIntegration.WorkflowContext;

/** Formal teaching claim workflow. Every transition binds real identities to an immutable fact revision. */
public final class DeliverySettlementWorkflow {
    private DeliverySettlementWorkflow() {}
    private static final String BASE="/api/delivery-settlement/workflow";
    private static final Set<String> OPS=Set.of("save","submit","review","confirm","correct","payment","settings");
    private static final Set<String> STATES=Set.of("DRAFT","SUBMITTED","RETURNED","APPROVED","CONFIRMED","PAID");
    private static final Set<String> CONTROL=Set.of("dispatch_id","expected_version","expected_fact_version","request_id");
    private static final String DEFAULT_VERSION="M05-MONEY-USER-20260923";
    private static final Set<String> EVIDENCE_FIELDS=Set.of("grade","day_type","research_team","appointment","annual_plan","customer_paid","applicability");
    private static final String HISTORY_INVALID="课酬申请记录或历史无法核实，请联系管理员核对";

    /** Schema only: user-approved money semantics are a read-only default, never seeded business records. */
    public static void init() throws SQLException {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit()) throw new SQLException("课酬流程初始化不可嵌套事务");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_fee_claims(dispatch_id BIGINT PRIMARY KEY REFERENCES m05_delivery_facts(dispatch_id),version BIGINT NOT NULL,state VARCHAR(20) NOT NULL,fact_revision BIGINT NOT NULL,payload CLOB NOT NULL)");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_fee_claim_events(dispatch_id BIGINT NOT NULL REFERENCES m05_fee_claims(dispatch_id),version BIGINT NOT NULL,payload CLOB NOT NULL,actor_code VARCHAR(96) NOT NULL,account_id BIGINT NOT NULL,created_at VARCHAR(40) NOT NULL,PRIMARY KEY(dispatch_id,version))");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_execution_settings(org_code VARCHAR(96) NOT NULL,version_code VARCHAR(96) NOT NULL,payload CLOB NOT NULL,actor_code VARCHAR(96) NOT NULL,account_id BIGINT NOT NULL,created_at VARCHAR(40) NOT NULL,PRIMARY KEY(org_code,version_code))");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_execution_settings_heads(org_code VARCHAR(96) PRIMARY KEY,version_code VARCHAR(96) NOT NULL)");
        }
    }

    public static boolean handle(HttpExchange ex,Auth.Session supplied) throws Exception {
        String path=ex.getRequestURI().getPath();
        String op=path.startsWith(BASE+"/")?path.substring(BASE.length()+1):"";
        if(!path.equals(BASE)&&!OPS.contains(op)) return false;
        Auth.Session session=Auth.get(Api.token(ex));
        if(session==null||session!=supplied||Auth.current(session)==null) fail(401,"登录会话无效或已失效");
        if(path.equals(BASE)) {
            method(ex,"GET");
            String raw=ex.getRequestURI().getRawQuery();
            if(raw==null||raw.split("&",-1).length!=1) fail(400,"仅接受一个dispatch_id查询参数");
            Map<String,String> q=Api.query(ex);
            if(q.size()!=1||!q.containsKey("dispatch_id")) fail(400,"仅接受dispatch_id查询参数");
            Api.ok(ex,read(session,integer(q.get("dispatch_id"),false,"dispatch_id")));
        } else {
            method(ex,"POST");
            if(ex.getRequestURI().getRawQuery()!=null) fail(400,"变更请求不接受查询参数");
            Api.ok(ex,mutate(op,session,Api.body(ex)));
        }
        return true;
    }

    public static Map<String,Object> read(Auth.Session session,long id) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(session);
            WorkflowContext ctx=DeliverySettlementIntegration.workflowContext(session,id,null,false);
            return view(session,ctx,checkedClaim(ctx),settings(ctx.organizationCode()));
        }
    }

    /** Direct callers receive the same authentication, exact field whitelist, transaction and CAS boundary as HTTP. */
    public static Map<String,Object> mutate(String op,Auth.Session session,Map<String,Object> input) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit()) fail(409,"课酬流程变更不可嵌套事务");
            try {
                return Db.transaction(()->{
                    if(!OPS.contains(op)) fail(404,"课酬流程操作不存在");
                    OrganizationAccess.Person actor=OrganizationAccessStore.person(session);
                    if(input==null) fail(400,"需要JSON对象");
                    Map<String,Object> body=new LinkedHashMap<>(input);
                    fields(body,allowed(op),required(op));
                    long id=integer(body.get("dispatch_id"),false,"dispatch_id");
                    long expected=integer(body.get("expected_version"),true,"expected_version");
                    long expectedFact=integer(body.get("expected_fact_version"),true,"expected_fact_version");
                    // Current Auth, organization ownership, current VIEW and operation HANDLE precede request lookup.
                    WorkflowContext ctx=DeliverySettlementIntegration.workflowContext(session,id,resource(op),false);
                    String request=code(body.get("request_id"),false);
                    String canonical=canonical(body),operation="wf-"+op;
                    Map<String,Object> old=checkedClaim(ctx),currentSettings=settings(ctx.organizationCode());
                    Map<String,Object> retry=Db.one("SELECT * FROM m05_requests WHERE account_id=? AND request_id=?",session.uid,request);
                    if(retry!=null) {
                        if(!actor.personCode().equals(retry.get("actor_code"))||!operation.equals(retry.get("operation"))
                                ||id!=integer(retry.get("dispatch_id"),false,"dispatch_id")||!canonical.equals(retry.get("payload")))
                            fail(409,"请求编号已用于不同内容");
                        Map<String,Object> response=json(retry.get("response"));
                        response.put("capabilities",capabilities(session,ctx,old,currentSettings,DeliverySettlementIntegration.workflowFinancial(ctx)));
                        return response;
                    }
                    if(expected!=version(old)) fail(409,"课酬申请已更新，请刷新后重试");
                    if(expectedFact!=ctx.factVersion()) fail(409,"授课事实已更新，请刷新后重试");
                    if(op.equals("settings")) {
                        expectedSettings(body,currentSettings);
                        publishSettings(session,ctx,body,actor,session.uid,currentSettings);
                    } else {
                        if(ctx.factVersion()==0) fail(409,"请先保存授课事实");
                        Map<String,Object> next=old==null?new LinkedHashMap<>():copy(old);
                        Map<String,Object> financial=DeliverySettlementIntegration.workflowFinancial(ctx);
                        String state=state(old);
                        if(op.equals("save")) {
                            if(Set.of("SUBMITTED","PAID").contains(state)||Boolean.TRUE.equals(financial.get("paid"))) fail(409,"已提交或已支付的课酬申请不能直接修改");
                            Map<String,Object> claim=claimWithEvidence(session,ctx,object(body.get("claim")),expected);
                            next.put("claim",claim);next.put("submission",null);next.put("review",null);next.put("context",context(ctx));next.put("state","DRAFT");
                        } else {
                            if(old==null) fail(409,"请先保存课酬申请");
                            requireBinding(ctx,old);
                            switch(op) {
                                case "submit" -> {
                                    if(!Set.of("DRAFT","RETURNED").contains(state)) fail(409,"只有草稿或退回的申请可以提交");
                                    ctx=ready(session,ctx,resource(op));
                                    next.put("submission",attestation(actor,session.uid,actionProof(session,ctx,body,"submit",expected,Map.of(),"SUBMISSION","evidence_note","evidence_reference","evidence_code")));
                                    next.put("review",null);next.put("state","SUBMITTED");
                                }
                                case "review" -> {
                                    if(!"SUBMITTED".equals(state)) fail(409,"只有已提交的申请可以审核");
                                    String decision=code(body.get("decision"),false);
                                    if(!Set.of("APPROVE","RETURN").contains(decision)) fail(400,"审核决定必须为APPROVE或RETURN");
                                    String reasonNote=reasonNote(body,"RETURN".equals(decision));
                                    if("APPROVE".equals(decision)) { ctx=ready(session,ctx,resource(op)); eligible(ctx,object(old.get("claim"))); }
                                    Map<String,Object> reviewDetails=new LinkedHashMap<>();reviewDetails.put("decision",decision);reviewDetails.put("reason_note",reasonNote);
                                    String reviewCode=actionProof(session,ctx,body,"review",expected,reviewDetails,"REVIEW","evidence_note","evidence_reference","evidence_code");
                                    if(body.get("reason_code")!=null&&!reviewCode.equals(code(body.get("reason_code"),false))) {
                                        DeliverySettlementEvidence.resolve(code(body.get("reason_code"),false),ctx.projectId(),ctx.organizationCode(),source(ctx),"REVIEW",descriptor(ctx,expected,"review",reviewDetails));
                                        fail(409,"退回依据必须与本次审核记录一致");
                                    }
                                    Map<String,Object> review=attestation(actor,session.uid,reviewCode);
                                    review.put("decision",decision);review.put("reason_code","RETURN".equals(decision)?reviewCode:null);
                                    next.put("review",review);next.put("state","APPROVE".equals(decision)?"APPROVED":"RETURNED");
                                }
                                case "confirm","correct" -> {
                                    if(!"APPROVED".equals(state)) fail(409,"只有通过审核的申请可以确认课酬");
                                    ctx=ready(session,ctx,resource(op));eligible(ctx,object(old.get("claim")));expectedSettings(body,currentSettings);
                                    boolean correcting=op.equals("correct");
                                    String head=optionalHead(financial),reason=null;
                                    if(correcting) {
                                        if(head==null||!head.equals(code(body.get("expected_snapshot_code"),false))) fail(409,"结算快照已变化，请刷新后更正");
                                        if(Boolean.TRUE.equals(financial.get("paid"))) fail(409,"已支付快照须使用支付后更正流程");
                                        if(!Boolean.TRUE.equals(currentSettings.get("allow_unpaid_correction"))) fail(409,"当前执行设置未允许未支付更正");
                                        reason=actionProof(session,ctx,body,"correct",expected,Map.of("expected_snapshot_code",head),"CORRECTION_REASON","reason_note","reason_reference","reason_code");
                                    } else if(head!=null) fail(409,"已有结算快照，请使用更正流程");
                                    Map<String,Object> review=object(old.get("review"));
                                    if(!"APPROVE".equals(review.get("decision"))) fail(409,"课酬申请尚无有效审核结论");
                                    DeliverySettlementIntegration.workflowConfirm(session,ctx,object(old.get("claim")),currentSettings,expected,code(review.get("evidence_code"),false),correcting,reason);
                                    next.put("state","CONFIRMED");
                                }
                                case "payment" -> {
                                    if(!"CONFIRMED".equals(state)) fail(409,"只有已确认的课酬可以登记支付");
                                    String head=code(body.get("expected_snapshot_code"),false);
                                    if(!head.equals(optionalHead(financial))) fail(409,"结算快照已变化，请刷新后登记支付");
                                    String amount=decimalString(body.get("amount"));
                                    String date=date(body.get("payment_date"));
                                    String receipt=actionProof(session,ctx,body,"payment",expected,Map.of("expected_snapshot_code",head,"payment_date",date,"amount",new BigDecimal(amount).stripTrailingZeros().toPlainString()),"PAYMENT","evidence_note","evidence_reference","evidence_code");
                                    DeliverySettlementIntegration.workflowPayment(session,ctx,head,date,amount,receipt);
                                    next.put("state","PAID");
                                }
                                default -> throw new IllegalArgumentException("未知课酬流程操作");
                            }
                        }
                        next.put("version",expected+1);next.put("fact_revision",ctx.factVersion());
                        Map<String,Object> event=attestation(actor,session.uid,null);event.put("operation",op);next.put("last_event",event);
                        persist(ctx,old,next,actor,session.uid);
                    }
                    WorkflowContext after=DeliverySettlementIntegration.workflowContext(session,id,resource(op),false);
                    Map<String,Object> response=view(session,after,checkedClaim(after),settings(after.organizationCode()));
                    Db.exec("INSERT INTO m05_requests(account_id,actor_code,request_id,operation,dispatch_id,payload,response) VALUES(?,?,?,?,?,?,?)",session.uid,actor.personCode(),request,operation,id,canonical,Json.write(response));
                    return response;
                });
            } catch(IllegalArgumentException invalid) { throw new Api.ApiException(400,invalid.getMessage()); }
              catch(IllegalStateException invalid) { throw new Api.ApiException(409,invalid.getMessage()); }
              catch(SQLException conflict) { if("23505".equals(conflict.getSQLState())) throw new Api.ApiException(409,"课酬申请或执行设置已更新，请刷新后重试");throw conflict; }
        }
    }

    private static String source(WorkflowContext ctx) {return "DISPATCH-"+ctx.dispatchId();}
    private static Map<String,Object> descriptor(WorkflowContext ctx,long claimVersion,String operation,Map<String,Object> details) {
        Map<String,Object> descriptor=new LinkedHashMap<>();descriptor.put("dispatch_id",ctx.dispatchId());descriptor.put("fact_version",ctx.factVersion());descriptor.put("claim_version",claimVersion);descriptor.put("operation",operation);descriptor.putAll(details);return descriptor;
    }
    private static String actionProof(Auth.Session session,WorkflowContext ctx,Map<String,Object> body,String operation,long claimVersion,Map<String,Object> details,String kind,String noteField,String referenceField,String codeField) throws Exception {
        Map<String,Object> descriptor=descriptor(ctx,claimVersion,operation,details);
        if(body.get(noteField)!=null) {
            if(body.get(codeField)!=null)fail(400,"说明和已有依据编号不能同时提交");
            Map<String,Object> proof=new LinkedHashMap<>();proof.put("note",body.get(noteField));proof.put("reference",body.get(referenceField));
            return DeliverySettlementEvidence.register(session,ctx.projectId(),ctx.organizationCode(),source(ctx),kind,descriptor,proof);
        }
        if(body.get(referenceField)!=null)fail(400,"填写凭证定位信息时须同时填写说明");
        if(body.get(codeField)==null)fail(400,"请填写本次操作的实际说明和凭证");
        String code=code(body.get(codeField),false);DeliverySettlementEvidence.resolve(code,ctx.projectId(),ctx.organizationCode(),source(ctx),kind,descriptor);return code;
    }
    private static String reasonNote(Map<String,Object> body,boolean returned) throws Exception {
        Object value=body.get("reason_note");if(value==null&&returned)value=body.get("evidence_note");
        if(value==null)return null;
        if(!(value instanceof String text)||text.isBlank()||text.length()>2000)fail(400,"审核原因须为不超过2000字的实际说明");return value.toString().trim();
    }
    private static Map<String,Object> claimWithEvidence(Auth.Session session,WorkflowContext ctx,Map<String,Object> input,long oldVersion) throws Exception {
        Map<String,Object> raw=new LinkedHashMap<>(input);Object supplied=raw.remove("evidence_notes");
        Map<String,Object> notes=supplied==null?Map.of():object(supplied);fields(notes,EVIDENCE_FIELDS,Set.of());
        Map<String,Object> parsed=new LinkedHashMap<>(DeliverySettlementExecution.parseClaim(raw));
        Map<String,Object> references=new LinkedHashMap<>(object(parsed.get("evidence")));
        for(String field:EVIDENCE_FIELDS) {
            String valueField=switch(field){case "appointment"->"appointed_on";case "applicability"->"service_date_applicable";default->field;};
            Map<String,Object> details=new LinkedHashMap<>();details.put("field",field);details.put("value",parsed.get(valueField));
            Map<String,Object> descriptor=descriptor(ctx,oldVersion+1,"save",details);
            if(notes.get(field)!=null) {
                if(references.get(field)!=null)fail(400,"同一字段不能同时提交新说明和已有依据编号");
                references.put(field,DeliverySettlementEvidence.register(session,ctx.projectId(),ctx.organizationCode(),source(ctx),"CLAIM_FIELD",descriptor,notes.get(field)));
            } else if(references.get(field)!=null) {
                String code=code(references.get(field),false);
                Map<String,Object> record=object(DeliverySettlementEvidence.view(List.of(code),ctx.projectId(),ctx.organizationCode()).get(code));
                Map<String,Object> recorded=object(record.get("descriptor"));long registeredVersion=integer(recorded.get("claim_version"),false,"依据申请版本");
                if(registeredVersion>oldVersion)fail(409,"依据不能来自尚未完成的申请版本");
                descriptor.put("claim_version",registeredVersion);
                DeliverySettlementEvidence.resolve(code,ctx.projectId(),ctx.organizationCode(),source(ctx),"CLAIM_FIELD",descriptor);
            }
        }
        parsed.put("evidence",references);return DeliverySettlementExecution.parseClaim(parsed);
    }
    private static Map<String,Object> settingsWithEvidence(Auth.Session session,WorkflowContext ctx,Map<String,Object> input,long claimVersion) throws Exception {
        Map<String,Object> raw=new LinkedHashMap<>(input);Object note=raw.remove("evidence_note"),reference=raw.remove("evidence_reference"),existing=raw.get("evidence_code");
        if(existing==null)raw.put("evidence_code","M05-PROOF-PENDING");
        Map<String,Object> parsed=new LinkedHashMap<>(DeliverySettlementExecution.parseSettings(raw));
        Map<String,Object> details=new LinkedHashMap<>(parsed);details.remove("evidence_code");
        Map<String,Object> body=new LinkedHashMap<>();body.put("evidence_note",note);body.put("evidence_reference",reference);body.put("evidence_code",existing);
        parsed.put("evidence_code",actionProof(session,ctx,body,"settings",claimVersion,details,"SETTINGS","evidence_note","evidence_reference","evidence_code"));
        return DeliverySettlementExecution.parseSettings(parsed);
    }
    private static Map<String,Object> claimEvidenceView(WorkflowContext ctx,Map<String,Object> claim) throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();if(claim==null)return out;
        Map<String,Object> refs=object(object(claim.get("claim")).get("evidence"));
        for(var entry:refs.entrySet()) {
            String code=code(entry.getValue(),false);Map<String,Object> proof=object(DeliverySettlementEvidence.view(List.of(code),ctx.projectId(),ctx.organizationCode()).get(code));
            Map<String,Object> display=new LinkedHashMap<>();for(String field:List.of("evidence_code","note","reference","actor_code","account_id","created_at"))display.put(field,proof.get(field));out.put(entry.getKey(),display);
        }
        return out;
    }
    private static void validateStoredClaimEvidence(WorkflowContext ctx,Map<String,Object> payload,Map<String,Map<String,Object>> checkedEvidence) throws Exception {
        Map<String,Object> claim=object(payload.get("claim")),references=object(claim.get("evidence"));
        for(var entry:references.entrySet()) {
            String field=entry.getKey(),code=code(entry.getValue(),false);
            Map<String,Object> proof=checkedEvidence.get(code);
            if(proof==null) {proof=object(DeliverySettlementEvidence.view(List.of(code),ctx.projectId(),ctx.organizationCode()).get(code));checkedEvidence.put(code,proof);}
            long originalVersion=integer(object(proof.get("descriptor")).get("claim_version"),false,"依据申请版本");
            if(originalVersion>version(payload))fail(409,"依据来自尚未完成的申请版本");
            String valueField=switch(field){case "appointment"->"appointed_on";case "applicability"->"service_date_applicable";default->field;};
            Map<String,Object> descriptor=new LinkedHashMap<>();descriptor.put("dispatch_id",ctx.dispatchId());descriptor.put("fact_version",payload.get("fact_revision"));
            descriptor.put("claim_version",originalVersion);descriptor.put("operation","save");descriptor.put("field",field);descriptor.put("value",claim.get(valueField));
            if(!source(ctx).equals(proof.get("source_code"))||!"CLAIM_FIELD".equals(proof.get("kind"))||!canonical(descriptor).equals(canonical(object(proof.get("descriptor")))))
                fail(409,"冻结申报依据与业务事实、字段内容或版本不一致");
        }
    }
    private static Map<String,Object> proofDisplay(WorkflowContext ctx,Object raw) throws Exception {
        if(raw==null)return null;Map<String,Object> out=new LinkedHashMap<>(object(raw));String code=code(out.get("evidence_code"),false);
        Map<String,Object> proof=object(DeliverySettlementEvidence.view(List.of(code),ctx.projectId(),ctx.organizationCode()).get(code));
        out.put("evidence_note",proof.get("note"));out.put("evidence_reference",proof.get("reference"));
        if(out.containsKey("decision"))out.put("reason_note",object(proof.get("descriptor")).get("reason_note"));return out;
    }

    private static WorkflowContext ready(Auth.Session session,WorkflowContext ctx,String resource) throws Exception {
        WorkflowContext fresh=DeliverySettlementIntegration.workflowContext(session,ctx.dispatchId(),resource,true);
        if(!canonical(context(ctx)).equals(canonical(context(fresh)))) fail(409,"授课事实或来源已变化，请刷新后重试");
        return fresh;
    }
    private static void eligible(WorkflowContext ctx,Map<String,Object> claim) throws Exception {
        List<String> issues=DeliverySettlementExecution.eligibility(DeliverySettlementIntegration.workflowFact(ctx),claim);
        if(!issues.isEmpty()) fail(409,"课酬依据尚不完整或不符合条件："+String.join("；",issues));
    }
    private static void requireBinding(WorkflowContext ctx,Map<String,Object> claim) throws Exception {
        if(integer(claim.get("fact_revision"),false,"fact_revision")!=ctx.factVersion()
                ||!canonical(object(claim.get("context"))).equals(canonical(context(ctx))))
            fail(409,"授课事实或来源已变化，请重新保存、提交和审核课酬申请");
    }
    private static Map<String,Object> context(WorkflowContext ctx) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("dispatch_id",ctx.dispatchId());out.put("project_id",ctx.projectId());out.put("teacher_id",ctx.teacherId());out.put("organization_code",ctx.organizationCode());
        out.put("service_date",ctx.serviceDate()==null?null:ctx.serviceDate().toString());out.put("fact_version",ctx.factVersion());out.put("fact",ctx.factPayload());return out;
    }
    private static Map<String,Object> attestation(OrganizationAccess.Person actor,long account,String evidence) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("actor_code",actor.personCode());out.put("account_id",account);out.put("created_at",Instant.now().toString());
        if(evidence!=null) out.put("evidence_code",evidence);return out;
    }
    private static void persist(WorkflowContext ctx,Map<String,Object> old,Map<String,Object> next,OrganizationAccess.Person actor,long account) throws Exception {
        String payload=Json.write(next);long version=version(next);long revision=integer(next.get("fact_revision"),false,"fact_revision");
        if(payload.length()>64000) fail(400,"课酬申请内容过长");
        if(old==null) Db.exec("INSERT INTO m05_fee_claims(dispatch_id,version,state,fact_revision,payload) VALUES(?,?,?,?,?)",ctx.dispatchId(),version,state(next),revision,payload);
        else cas("UPDATE m05_fee_claims SET version=?,state=?,fact_revision=?,payload=? WHERE dispatch_id=? AND version=?",version,state(next),revision,payload,ctx.dispatchId(),version(old));
        Db.exec("INSERT INTO m05_fee_claim_events(dispatch_id,version,payload,actor_code,account_id,created_at) VALUES(?,?,?,?,?,?)",ctx.dispatchId(),version,payload,actor.personCode(),account,object(next.get("last_event")).get("created_at"));
    }

    /** Validate the complete bounded append-only chain before projecting or mutating its head. */
    private static Map<String,Object> checkedClaim(WorkflowContext ctx) throws Exception {
        Map<String,Object> extent=Db.one("SELECT COUNT(*) AS n,COALESCE(SUM(CHAR_LENGTH(payload)),0) AS chars FROM m05_fee_claim_events WHERE dispatch_id=?",ctx.dispatchId());
        long n=integer(extent.get("n"),true,"事件数"),chars=integer(extent.get("chars"),true,"事件长度");
        if(n>10000||chars>8L*1024*1024) fail(409,"课酬申请历史超过核验容量，请联系管理员核对");
        Map<String,Object> row=Db.one("SELECT * FROM m05_fee_claims WHERE dispatch_id=?",ctx.dispatchId());
        if(row==null) { if(n!=0) fail(409,HISTORY_INVALID);return null; }
        try {
            long head=integer(row.get("version"),false,"version");
            if(n!=head) fail(409,HISTORY_INVALID);
            Map<String,Object> previous=null;Map<String,Map<String,Object>> checkedEvidence=new HashMap<>();
            for(Map<String,Object> event:Db.query("SELECT * FROM m05_fee_claim_events WHERE dispatch_id=? ORDER BY version",ctx.dispatchId())) {
                Map<String,Object> p=json(event.get("payload"));
                long version=integer(event.get("version"),false,"version");
                if(version!=version(previous)+1||version!=version(p)) fail(409,HISTORY_INVALID);
                fields(p,Set.of("version","state","fact_revision","claim","submission","review","context","last_event"),Set.of("version","state","fact_revision","claim","submission","review","context","last_event"));
                if(!STATES.contains(state(p))) fail(409,HISTORY_INVALID);
                Map<String,Object> source=object(p.get("context")),meta=object(p.get("last_event"));
                fields(source,Set.of("dispatch_id","project_id","teacher_id","organization_code","service_date","fact_version","fact"),Set.of("dispatch_id","project_id","teacher_id","organization_code","service_date","fact_version","fact"));
                if(integer(source.get("dispatch_id"),false,"dispatch_id")!=ctx.dispatchId()
                        ||integer(source.get("project_id"),false,"project_id")!=ctx.projectId()
                        ||integer(source.get("teacher_id"),false,"teacher_id")!=ctx.teacherId()
                        ||!ctx.organizationCode().equals(source.get("organization_code"))
                        ||integer(source.get("fact_version"),false,"fact_version")!=integer(p.get("fact_revision"),false,"fact_revision")) fail(409,HISTORY_INVALID);
                Map<String,Object> frozen=object(source.get("fact"));
                if(integer(frozen.get("record_id"),false,"record_id")!=ctx.dispatchId()||integer(frozen.get("revision"),false,"revision")!=integer(p.get("fact_revision"),false,"fact_revision")
                        ||!Objects.equals(source.get("service_date"),frozen.get("service_date"))
                        ||!Objects.equals(source.get("organization_code"),frozen.get("organization_code"))
                        ||!("TEACHER-"+ctx.teacherId()).equals(frozen.get("instructor_code"))) fail(409,HISTORY_INVALID);
                Map<String,Object> factRevision=Db.one("SELECT payload FROM m05_fact_revisions WHERE dispatch_id=? AND revision=?",ctx.dispatchId(),p.get("fact_revision"));
                if(factRevision==null||!canonical(frozen).equals(canonical(json(factRevision.get("payload"))))) fail(409,HISTORY_INVALID);
                DeliverySettlementExecution.parseClaim(object(p.get("claim")));validateStoredClaimEvidence(ctx,p,checkedEvidence);
                fields(meta,Set.of("operation","actor_code","account_id","created_at"),Set.of("operation","actor_code","account_id","created_at"));
                if(!Objects.equals(event.get("actor_code"),code(meta.get("actor_code"),false))
                        ||integer(event.get("account_id"),false,"account_id")!=integer(meta.get("account_id"),false,"account_id")
                        ||!Objects.equals(event.get("created_at"),meta.get("created_at"))) fail(409,HISTORY_INVALID);
                checkedTime(meta.get("created_at"));
                if(previous!=null&&Instant.parse((String)meta.get("created_at")).isBefore(Instant.parse((String)object(previous.get("last_event")).get("created_at")))) fail(409,HISTORY_INVALID);
                validateTransition(previous,p,meta);
                previous=p;
            }
            Map<String,Object> payload=json(row.get("payload"));
            if(previous==null||!canonical(previous).equals(canonical(payload))||!state(payload).equals(row.get("state"))
                    ||integer(payload.get("fact_revision"),false,"fact_revision")!=integer(row.get("fact_revision"),false,"fact_revision")) fail(409,HISTORY_INVALID);
            return payload;
        } catch(Api.ApiException|RuntimeException invalid) { throw new Api.ApiException(409,HISTORY_INVALID); }
    }
    private static void validateTransition(Map<String,Object> previous,Map<String,Object> next,Map<String,Object> meta) throws Exception {
        String operation=code(meta.get("operation"),false),before=state(previous),after=state(next);
        boolean valid=switch(operation) {
            case "save" -> !Set.of("SUBMITTED","PAID").contains(before)&&"DRAFT".equals(after)&&next.get("submission")==null&&next.get("review")==null;
            case "submit" -> previous!=null&&Set.of("DRAFT","RETURNED").contains(before)&&"SUBMITTED".equals(after)&&next.get("review")==null;
            case "review" -> previous!=null&&"SUBMITTED".equals(before)&&Set.of("APPROVED","RETURNED").contains(after);
            case "confirm","correct" -> previous!=null&&"APPROVED".equals(before)&&"CONFIRMED".equals(after);
            case "payment" -> previous!=null&&"CONFIRMED".equals(before)&&"PAID".equals(after);
            default -> false;
        };
        if(!valid) fail(409,HISTORY_INVALID);
        if(!operation.equals("save")) {
            if(!canonical(object(previous.get("claim"))).equals(canonical(object(next.get("claim"))))
                    ||!canonical(object(previous.get("context"))).equals(canonical(object(next.get("context"))))) fail(409,HISTORY_INVALID);
            if(!operation.equals("submit")&&!Objects.equals(canonicalNullable(previous.get("submission")),canonicalNullable(next.get("submission")))) fail(409,HISTORY_INVALID);
            if(!Set.of("submit","review").contains(operation)&&!Objects.equals(canonicalNullable(previous.get("review")),canonicalNullable(next.get("review")))) fail(409,HISTORY_INVALID);
        }
        if(next.get("submission")!=null) checkedAttestation(object(next.get("submission")),false,null);
        if(next.get("review")!=null) checkedAttestation(object(next.get("review")),true,null);
        if(operation.equals("submit")) checkedAttestation(object(next.get("submission")),false,meta);
        if(operation.equals("review")) {
            Map<String,Object> review=object(next.get("review"));checkedAttestation(review,true,meta);
            if(!Objects.equals(review.get("decision"),after.equals("APPROVED")?"APPROVE":"RETURN")) fail(409,HISTORY_INVALID);
        }
    }
    private static void checkedAttestation(Map<String,Object> value,boolean review,Map<String,Object> actor) throws Exception {
        Set<String> keys=review?Set.of("actor_code","account_id","created_at","evidence_code","decision","reason_code"):Set.of("actor_code","account_id","created_at","evidence_code");
        fields(value,keys,keys);code(value.get("actor_code"),false);integer(value.get("account_id"),false,"account_id");code(value.get("evidence_code"),false);checkedTime(value.get("created_at"));
        if(review) { String decision=code(value.get("decision"),false);if(!Set.of("APPROVE","RETURN").contains(decision)) fail(409,HISTORY_INVALID);code(value.get("reason_code"),!decision.equals("RETURN")); }
        if(actor!=null&&(!value.get("actor_code").equals(actor.get("actor_code"))||integer(value.get("account_id"),false,"account_id")!=integer(actor.get("account_id"),false,"account_id")
                ||Instant.parse((String)value.get("created_at")).isAfter(Instant.parse((String)actor.get("created_at"))))) fail(409,HISTORY_INVALID);
    }

    private static Map<String,Object> defaultSettings() {
        Map<String,Object> map=new LinkedHashMap<>();map.put("version",DEFAULT_VERSION);map.put("evidence_code","USER-20260923-PER-LINE-CNY-2-HALF-UP");
        map.put("amount_scale",2);map.put("rounding_mode","HALF_UP");map.put("allow_unpaid_correction",true);return map;
    }
    private static Map<String,Object> settings(String org) throws Exception {
        Map<String,Object> head=Db.one("SELECT version_code FROM m05_execution_settings_heads WHERE org_code=?",org);
        Map<String,Object> extent=Db.one("SELECT COUNT(*) AS n,COALESCE(SUM(CHAR_LENGTH(payload)),0) AS chars FROM m05_execution_settings WHERE org_code=?",org);
        long n=integer(extent.get("n"),true,"设置版本数"),chars=integer(extent.get("chars"),true,"设置长度");
        if(n>10000||chars>8L*1024*1024) fail(409,"课酬执行设置历史超过核验容量");
        if(head==null) { if(n!=0) fail(409,"课酬执行设置历史无法核实");return defaultSettings(); }
        try {
            Map<String,Map<String,Object>> all=new HashMap<>();
            for(Map<String,Object> row:Db.query("SELECT * FROM m05_execution_settings WHERE org_code=?",org)) {
                String version=code(row.get("version_code"),false);
                if(all.put(version,row)!=null) fail(409,"课酬执行设置历史无法核实");
            }
            String cursor=code(head.get("version_code"),false);Set<String> seen=new HashSet<>();Map<String,Object> current=null;
            while(!DEFAULT_VERSION.equals(cursor)) {
                Map<String,Object> row=all.get(cursor);
                if(row==null||!seen.add(cursor)) fail(409,"课酬执行设置历史无法核实");
                code(row.get("actor_code"),false);integer(row.get("account_id"),false,"account_id");checkedTime(row.get("created_at"));
                Map<String,Object> envelope=json(row.get("payload"));fields(envelope,Set.of("settings","previous_version"),Set.of("settings","previous_version"));
                Map<String,Object> config=DeliverySettlementExecution.parseSettings(object(envelope.get("settings")));
                if(!cursor.equals(config.get("version"))) fail(409,"课酬执行设置版本无法核实");
                if(current==null) current=config;
                cursor=code(envelope.get("previous_version"),false);
            }
            if(current==null||seen.size()!=n) fail(409,"课酬执行设置历史无法核实");
            return current;
        } catch(Api.ApiException|RuntimeException invalid) { throw new Api.ApiException(409,"课酬执行设置历史无法核实"); }
    }
    private static void expectedSettings(Map<String,Object> body,Map<String,Object> current) throws Exception {
        if(!Objects.equals(code(body.get("expected_settings_version"),true),current.get("version"))) fail(409,"课酬执行设置已更新，请刷新后重试");
    }
    private static void publishSettings(Auth.Session session,WorkflowContext ctx,Map<String,Object> body,OrganizationAccess.Person actor,long account,Map<String,Object> current) throws Exception {
        Map<String,Object> config=settingsWithEvidence(session,ctx,object(body.get("settings")),integer(body.get("expected_version"),true,"expected_version"));
        String version=code(config.get("version"),false),prior=code(current.get("version"),false);
        if(DEFAULT_VERSION.equals(version)||Db.one("SELECT version_code FROM m05_execution_settings WHERE org_code=? AND version_code=?",ctx.organizationCode(),version)!=null) fail(409,"课酬执行设置版本已使用");
        Map<String,Object> envelope=new LinkedHashMap<>();envelope.put("settings",config);envelope.put("previous_version",prior);
        Db.exec("INSERT INTO m05_execution_settings(org_code,version_code,payload,actor_code,account_id,created_at) VALUES(?,?,?,?,?,?)",ctx.organizationCode(),version,Json.write(envelope),actor.personCode(),account,Instant.now().toString());
        if(DEFAULT_VERSION.equals(prior)) Db.exec("INSERT INTO m05_execution_settings_heads(org_code,version_code) VALUES(?,?)",ctx.organizationCode(),version);
        else cas("UPDATE m05_execution_settings_heads SET version_code=? WHERE org_code=? AND version_code=?",version,ctx.organizationCode(),prior);
    }

    private static Map<String,Object> view(Auth.Session session,WorkflowContext ctx,Map<String,Object> claim,Map<String,Object> settings) throws Exception {
        Map<String,Object> financial=DeliverySettlementIntegration.workflowFinancial(ctx),out=new LinkedHashMap<>();
        out.put("dispatch_id",ctx.dispatchId());out.put("project_id",ctx.projectId());out.put("teacher_id",ctx.teacherId());out.put("organization_code",ctx.organizationCode());
        out.put("service_date",ctx.serviceDate()==null?null:ctx.serviceDate().toString());out.put("fact_version",ctx.factVersion());out.put("version",version(claim));out.put("state",state(claim));
        out.put("claim",claim==null?null:claim.get("claim"));out.put("evidence_notes",claimEvidenceView(ctx,claim));
        out.put("submission",claim==null?null:proofDisplay(ctx,claim.get("submission")));out.put("review",claim==null?null:proofDisplay(ctx,claim.get("review")));
        out.put("claim_fact_version",claim==null?null:claim.get("fact_revision"));out.put("context",claim==null?null:claim.get("context"));
        Map<String,Object> settingsView=new LinkedHashMap<>(settings);
        if(!DEFAULT_VERSION.equals(settings.get("version"))) {
            Map<String,Object> proof=DeliverySettlementEvidence.viewSetting(code(settings.get("evidence_code"),false),ctx.organizationCode());
            settingsView.put("evidence_note",proof.get("note"));settingsView.put("evidence_reference",proof.get("reference"));
        }
        else {settingsView.put("evidence_note","已批准每条金额保留人民币两位小数，按HALF_UP四舍五入后汇总，并保留更正记录");settingsView.put("evidence_reference",null);}
        if(financial.get("payment")!=null)financial.put("payment",proofDisplay(ctx,financial.get("payment")));
        out.put("settings_version",settings.get("version"));out.put("settings",settingsView);out.put("financial",financial);
        out.put("calculation_preview",calculationPreview(ctx,claim,settings));
        out.put("capabilities",capabilities(session,ctx,claim,settings,financial));return out;
    }
    /** A display-only calculation tied to this exact claim and fact; it never creates financial evidence. */
    private static Map<String,Object> calculationPreview(WorkflowContext ctx,Map<String,Object> claim,Map<String,Object> settings) throws Exception {
        if(claim==null||ctx.factVersion()==0) return null;
        try { requireBinding(ctx,claim); } catch(Api.ApiException stale) { return null; }
        DeliverySettlement.Fact fact=DeliverySettlementIntegration.workflowFact(ctx);
        Map<String,Object> contents=object(claim.get("claim"));
        if(!DeliverySettlementExecution.eligibility(fact,contents).isEmpty()) return null;
        DeliverySettlementExecution.Executable execution=DeliverySettlementExecution.build(fact,contents,settings,"M05-PREVIEW-"+ctx.dispatchId()+"-"+version(claim),"M05-PREVIEW-ONLY");
        BigDecimal rate=new BigDecimal(object(execution.configuration().get("rate")).get("unit_rate").toString());
        BigDecimal payable=fact.hours().payable(),raw=rate.multiply(payable);
        Map<String,Object> result=new LinkedHashMap<>();result.put("unit_rate",rate.toPlainString());result.put("payable_hours",payable.toPlainString());
        result.put("unrounded_amount",raw.toPlainString());result.put("amount",raw.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString());
        result.put("currency","CNY");result.put("confirmed",false);result.put("fact_version",ctx.factVersion());result.put("claim_version",version(claim));return result;
    }
    private static Map<String,Object> capabilities(Auth.Session session,WorkflowContext ctx,Map<String,Object> claim,Map<String,Object> settings,Map<String,Object> financial) throws Exception {
        OrganizationAccessStore.person(session);
        Map<String,Object> out=new LinkedHashMap<>(),permissions=new LinkedHashMap<>(),reasons=new LinkedHashMap<>();
        boolean ready=true;String readyReason=null;
        try { ready(session,ctx,null); } catch(Api.ApiException invalid) { if(invalid.code==401||invalid.code==403) throw invalid;ready=false;readyReason=invalid.getMessage(); }
        boolean binding=claim!=null;
        if(binding) { try { requireBinding(ctx,claim); } catch(Api.ApiException invalid) { binding=false; } }
        List<String> missing=claim==null?List.of("请先保存课酬申请"):DeliverySettlementExecution.eligibility(DeliverySettlementIntegration.workflowFact(ctx),object(claim.get("claim")));
        String state=state(claim),head=optionalHead(financial);boolean paid=Boolean.TRUE.equals(financial.get("paid"));
        Map<String,Object> chain=financial.get("chain")==null?null:object(financial.get("chain"));
        boolean adjusted=chain!=null&&!Objects.equals(head,chain.get("current_entry_code"));
        for(String op:List.of("save","submit","review","confirm","correct","payment","settings")) {
            boolean granted=OrganizationAccessStore.authorize(session,resource(op),OrganizationAccess.Action.HANDLE,ctx.organizationCode()).allowed();
            permissions.put(op,granted);List<String> blocked=new ArrayList<>();
            if(!granted) blocked.add("没有该机构范围内的操作权限");
            if(!op.equals("settings")&&ctx.factVersion()==0) blocked.add("请先保存授课事实");
            if(!Set.of("save","settings").contains(op)&&!binding) blocked.add("课酬申请尚未保存或授课事实已变化，请重新保存、提交和审核");
            if(Set.of("submit","confirm","correct").contains(op)&&!ready) blocked.add(readyReason);
            if(Set.of("correct","payment").contains(op)&&adjusted) blocked.add("该课酬已通过调整事项更正，请从调整事项继续办理");
            switch(op) {
                case "save" -> { if(Set.of("SUBMITTED","PAID").contains(state)||paid) blocked.add("已提交或已支付的申请不能直接修改"); }
                case "submit" -> { if(!Set.of("DRAFT","RETURNED").contains(state)) blocked.add("只有草稿或退回申请可以提交"); }
                case "review" -> { if(!"SUBMITTED".equals(state)) blocked.add("只有已提交申请可以审核"); }
                case "confirm" -> { if(!"APPROVED".equals(state)) blocked.add("申请尚未通过审核");if(head!=null) blocked.add("已有快照，请使用更正流程");blocked.addAll(missing); }
                case "correct" -> { if(!"APPROVED".equals(state)) blocked.add("更正后的申请尚未通过审核");if(head==null) blocked.add("尚无可更正的快照");if(paid) blocked.add("已支付快照须使用支付后更正流程");if(!Boolean.TRUE.equals(settings.get("allow_unpaid_correction"))) blocked.add("执行设置未允许未支付更正");blocked.addAll(missing); }
                case "payment" -> {
                    if(!"CONFIRMED".equals(state)||head==null||paid) blocked.add("只有尚未支付的已确认课酬可以登记支付");
                    if(financial.get("balance")!=null&&new BigDecimal(financial.get("balance").toString()).signum()==0) blocked.add("当前没有待付余额");
                }
                default -> { }
            }
            out.put("can_"+op,blocked.isEmpty());reasons.put(op,blocked);
        }
        out.put("can_approve",Boolean.TRUE.equals(out.get("can_review"))&&ready&&missing.isEmpty());
        out.put("permissions",permissions);out.put("reasons",reasons);out.put("eligibility_issues",missing);out.put("fact_binding_current",binding);return out;
    }

    private static Set<String> allowed(String op) {
        Set<String> fields=new HashSet<>(CONTROL);fields.addAll(switch(op) {
            case "save" -> Set.of("claim");case "submit" -> Set.of("evidence_code","evidence_note","evidence_reference");case "review" -> Set.of("decision","evidence_code","evidence_note","evidence_reference","reason_code","reason_note");
            case "confirm" -> Set.of("expected_settings_version");case "correct" -> Set.of("expected_settings_version","expected_snapshot_code","reason_code","reason_note","reason_reference");
            case "payment" -> Set.of("expected_snapshot_code","payment_date","amount","evidence_code","evidence_note","evidence_reference");case "settings" -> Set.of("expected_settings_version","settings");default -> Set.of();
        });return fields;
    }
    private static Set<String> required(String op) {
        Set<String> fields=new HashSet<>(allowed(op));fields.removeAll(Set.of("evidence_code","evidence_note","evidence_reference","reason_code","reason_note","reason_reference"));return fields;
    }
    private static String resource(String op) { return switch(op) { case "save","submit" -> "settlement.submit";case "payment" -> "settlement.pay";case "settings" -> "settlement.configure";default -> "settlement."+op; }; }
    private static String state(Map<String,Object> map) { return map==null?"DRAFT":String.valueOf(map.get("state")); }
    private static long version(Map<String,Object> map) throws Exception { return map==null?0:integer(map.get("version"),false,"version"); }
    private static String optionalHead(Map<String,Object> financial) throws Exception { return code(financial.get("head_snapshot_code"),true); }
    private static void fields(Map<String,Object> value,Set<String> allowed,Set<String> required) throws Exception {
        for(String key:value.keySet()) if(!allowed.contains(key)) fail(400,"不接受字段："+key);
        for(String key:required) if(!value.containsKey(key)) fail(400,"缺少字段："+key);
    }
    private static long integer(Object value,boolean zero,String name) throws Exception {
        String raw;
        if(value instanceof String s&&s.matches("[0-9]{1,16}")) raw=s;
        else if(value instanceof Number n&&Double.isFinite(n.doubleValue())&&n.doubleValue()==Math.rint(n.doubleValue())&&Math.abs(n.doubleValue())<=9007199254740991d) raw=new BigDecimal(n.toString()).toPlainString();
        else { fail(400,name+"须为安全整数");return 0; }
        try { long n=new BigDecimal(raw).longValueExact();if(n<(zero?0:1)||n>9007199254740991L) fail(400,name+"超出有效范围");return n; }
        catch(ArithmeticException invalid) { throw new Api.ApiException(400,name+"须为整数"); }
    }
    private static String code(Object value,boolean optional) throws Exception {
        if(value==null&&optional) return null;
        if(!(value instanceof String s)||!s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")) fail(400,"需要有效的编码或版本依据");return (String)value;
    }
    private static String decimalString(Object value) throws Exception {
        if(!(value instanceof String s)||!s.matches("[0-9]{1,14}(\\.[0-9]{1,8})?")) fail(400,"支付金额须为非负十进制字符串");return (String)value;
    }
    private static String date(Object value) throws Exception {
        if(!(value instanceof String s)||!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) fail(400,"支付日期必须为YYYY-MM-DD");
        try { return java.time.LocalDate.parse((String)value).toString(); } catch(java.time.DateTimeException invalid) { throw new Api.ApiException(400,"支付日期无效"); }
    }
    private static void checkedTime(Object value) throws Exception { if(!(value instanceof String)||((String)value).length()>40||Instant.parse((String)value).isAfter(Instant.now())) fail(409,HISTORY_INVALID); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws Exception { if(!(value instanceof Map<?,?>)) fail(400,"需要JSON对象");return (Map<String,Object>)value; }
    private static Map<String,Object> json(Object value) throws Exception { if(!(value instanceof String)) fail(409,HISTORY_INVALID);return object(Json.parse((String)value)); }
    private static Map<String,Object> copy(Map<String,Object> value) throws Exception { return json(Json.write(value)); }
    private static String canonicalNullable(Object value) { return Json.write(sorted(value)); }
    private static String canonical(Map<String,Object> value) { return canonicalNullable(value); }
    private static Object sorted(Object value) {
        if(value instanceof Map<?,?> map) { Map<String,Object> sorted=new TreeMap<>();map.forEach((k,v)->sorted.put(String.valueOf(k),sorted(v)));return sorted; }
        if(value instanceof List<?> list) return list.stream().map(DeliverySettlementWorkflow::sorted).toList();
        if(value instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros();return value;
    }
    private static void cas(String sql,Object... args) throws Exception {
        try(PreparedStatement statement=Db.get().prepareStatement(sql)) { for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);if(statement.executeUpdate()!=1) fail(409,"记录已更新，请刷新后重试"); }
    }
    private static void method(HttpExchange ex,String method) throws Exception { if(!method.equals(ex.getRequestMethod())) fail(405,"请求方法不支持"); }
    private static void fail(int status,String message) throws Api.ApiException { throw new Api.ApiException(status,message); }
}
