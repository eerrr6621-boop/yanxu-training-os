package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.training.DeliverySettlementPolicy.*;

/** Explicitly reviewed independent development, legacy opening, adjustment and no-fee cases. */
public final class DeliverySettlementCases {
    private DeliverySettlementCases() {}
    private static final String BASE="/api/delivery-settlement/cases";
    private static final Set<String> OPS=Set.of("save","submit","review","confirm","payment","withdraw");
    private static final Set<String> KINDS=Set.of("DEVELOPMENT","MIGRATION","ADJUSTMENT","WAIVER");
    private static final Set<String> STATES=Set.of("DRAFT","SUBMITTED","RETURNED","APPROVED","CONFIRMED","WITHDRAWN");
    private static final Set<String> DEV_FIELDS=Set.of("activity","service_date","deliverable_code","deliverable_version","lead_teacher_id","grade","research_team","appointed_on","annual_plan","customer_paid","service_date_applicable","evidence","payable_hours","development_path","repetition_percent","previous_grant","customer_written_payment_agreement","customer_acceptance","company_need","annual_review_passed","company_approval","allocations","joint_reference_grade","completed_on","customer_acceptance_on","annual_review_on","company_approval_on");
    private static final Set<String> DEV_EVIDENCE=Set.of("grade","research_team","appointment","annual_plan","customer_paid","applicability","payable_hours","repetition","previous_grant","customer_written_payment_agreement","customer_acceptance","company_need","annual_review_passed","company_approval","allocation","joint_basis","completion","deliverable");
    private static final Set<String> MIGRATION_FIELDS=Set.of("legacy_source_code","legacy_rule_code","service_date","teacher_id","amount","hours","source_evidence","original_approval_evidence","historical_payment");
    private static final Set<String> ADJUST_FIELDS=Set.of("chain_code","expected_entry_code","reason_code","service_date","claim","hours","evidence_code","cancellation","amount","legacy_hours","legacy_rule_code","source_evidence","original_approval_evidence","development","expected_entries");
    private static final Set<String> HOURS=Set.of("ESTIMATED","PLANNED","ACTUAL","PAYABLE");
    private static final String HISTORY_ERROR="独立课酬事项的版本或审计依据不一致，请核对后再办理";

    /** Schema only. Origin identifiers are globally unique, including across organizations. */
    public static void init() throws SQLException {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit())throw new SQLException("独立课酬事项初始化不可嵌套事务");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_cases(case_code VARCHAR(96) PRIMARY KEY,project_id BIGINT NOT NULL REFERENCES projects(id),organization_code VARCHAR(96) NOT NULL,version BIGINT NOT NULL,state VARCHAR(20) NOT NULL,kind VARCHAR(20) NOT NULL,confirmed_origin VARCHAR(96) UNIQUE,payload CLOB NOT NULL)");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_case_revisions(case_code VARCHAR(96) NOT NULL REFERENCES m05_cases(case_code),version BIGINT NOT NULL,payload CLOB NOT NULL,actor_code VARCHAR(96) NOT NULL,account_id BIGINT NOT NULL,created_at VARCHAR(40) NOT NULL,PRIMARY KEY(case_code,version))");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_case_requests(account_id BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,actor_code VARCHAR(96) NOT NULL,case_code VARCHAR(96) NOT NULL REFERENCES m05_cases(case_code),operation VARCHAR(20) NOT NULL,payload CLOB NOT NULL,response CLOB NOT NULL,PRIMARY KEY(account_id,request_id))");
        }
    }
    public static boolean handle(HttpExchange ex,Auth.Session supplied) throws Exception {
        String path=ex.getRequestURI().getPath(),op=path.startsWith(BASE+"/")?path.substring(BASE.length()+1):"";
        if(!path.equals(BASE)&&!OPS.contains(op))return false;
        Auth.Session session=Auth.get(Api.token(ex));
        if(session==null||session!=supplied||Auth.current(session)==null)fail(401,"登录会话无效或已失效");
        if(path.equals(BASE)) {
            method(ex,"GET");String raw=ex.getRequestURI().getRawQuery();Map<String,String> q=Api.query(ex);
            if(raw!=null&&raw.split("&",-1).length==2&&q.size()==2&&q.containsKey("project_id")&&"options".equals(q.get("view"))) {Api.ok(ex,options(session,integer(q.get("project_id"),false)));return true;}
            if(raw==null||raw.split("&",-1).length!=1||q.size()!=1)fail(400,"仅接受单个case_code或project_id查询参数");
            if(q.containsKey("case_code"))Api.ok(ex,read(session,code(q.get("case_code"),false)));
            else if(q.containsKey("project_id"))Api.ok(ex,list(session,integer(q.get("project_id"),false)));
            else fail(400,"仅接受case_code或project_id查询参数");
        } else {
            method(ex,"POST");if(ex.getRequestURI().getRawQuery()!=null)fail(400,"变更请求不接受查询参数");
            Api.ok(ex,mutate(op,session,Api.body(ex)));
        }
        return true;
    }
    public static Map<String,Object> read(Auth.Session session,String caseCode) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(session);String key=code(caseCode,false);
            Map<String,Object> row=Db.one("SELECT project_id FROM m05_cases WHERE case_code=?",key);
            if(row==null)fail(404,"独立课酬事项不存在");
            Map<String,Object> source=source(session,integer(row.get("project_id"),false),null);
            Map<String,Object> stored=checked(key);binding(source,stored);return view(session,source,stored);
        }
    }
    public static Map<String,Object> list(Auth.Session session,long project) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Map<String,Object> context=source(session,project,null);
            if(integer(Db.one("SELECT COUNT(*) AS n FROM m05_cases WHERE project_id=?",project).get("n"),true)>1000)fail(409,"事项数量超过单次核验容量，请按事项编码查询");
            List<Map<String,Object>> items=new ArrayList<>();
            for(Map<String,Object> key:Db.query("SELECT case_code FROM m05_cases WHERE project_id=? ORDER BY case_code",project)) {
                Map<String,Object> c=checked(text(key,"case_code"));binding(context,c);items.add(view(session,context,c));
            }
            return Map.of("project_id",project,"organization_code",context.get("organization_code"),"items",items);
        }
    }
    /** Source-bound choices only; unrelated teacher directory access remains with its own module. */
    public static Map<String,Object> options(Auth.Session session,long project) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Map<String,Object> context=source(session,project,null);
            List<Map<String,Object>> dispatches=Db.query("SELECT d.id AS dispatch_id,d.teacher_id,t.name AS teacher_name,d.subject,d.teach_date,d.status FROM dispatches d JOIN teachers t ON t.id=d.teacher_id WHERE d.project_id=? ORDER BY d.id",project);
            if(dispatches.size()>1000)fail(409,"项目授课数量超过单次选择容量");List<Map<String,Object>> chains=new ArrayList<>();
            for(Map<String,Object> row:Db.query("SELECT chain_code,organization_code FROM m05_financial_chains WHERE project_id=? ORDER BY chain_code",project)) {
                if(chains.size()>=1000)fail(409,"项目财务链超过单次选择容量");
                if(!Objects.equals(row.get("organization_code"),context.get("organization_code")))fail(409,"项目财务链组织来源不一致");
                Map<String,Object> balance=DeliverySettlementLedger.balance(text(row,"chain_code"));chains.add(balance);
            }
            if(integer(Db.one("SELECT COUNT(*) AS n FROM m05_cases WHERE organization_code=? AND kind='DEVELOPMENT'",context.get("organization_code")).get("n"),true)>1000)fail(409,"已有开发成果事项超过单次选择容量，请先缩小办理范围");
            List<Map<String,Object>> deliverables=new ArrayList<>();
            for(Map<String,Object> key:Db.query("SELECT case_code,project_id FROM m05_cases WHERE organization_code=? AND kind='DEVELOPMENT' ORDER BY project_id,case_code",context.get("organization_code"))) {
                Map<String,Object> origin=source(session,integer(key.get("project_id"),false),null),c=checked(text(key,"case_code"));
                if(!Objects.equals(origin.get("organization_code"),context.get("organization_code")))fail(409,"已有成果事项的组织来源已变化");binding(origin,c);
                Map<String,Object> payload=map(c.get("payload"));if(payload.get("deliverable_code")!=null&&payload.get("deliverable_version")!=null)deliverables.add(Map.of("case_code",c.get("case_code"),"project_id",c.get("project_id"),"deliverable_code",payload.get("deliverable_code"),"deliverable_version",payload.get("deliverable_version"),"state",c.get("state")));
            }
            Map<String,Object> projectRow=Db.one("SELECT status FROM projects WHERE id=?",project);
            boolean canSave=projectRow!=null&&!closed(text(projectRow,"status"))&&OrganizationAccessStore.authorize(session,"settlement.submit",OrganizationAccess.Action.HANDLE,text(context,"organization_code")).allowed();
            return Map.of("project_id",project,"organization_code",context.get("organization_code"),"dispatches",dispatches,"financial_chains",chains,"deliverables",deliverables,"can_save",canSave);
        }
    }
    /** Read-only archive gate; authorization is owned by the invoking project operation. */
    public static void requireProjectSettled(long project) throws Exception {
        for(Map<String,Object> row:Db.query("SELECT case_code FROM m05_cases WHERE project_id=?",project)) {
            Map<String,Object> c=checked(text(row,"case_code"));
            if(!Set.of("CONFIRMED","WITHDRAWN").contains(c.get("state")))fail(409,"项目仍有未完成核准的独立课酬事项");
        }
    }
    public static Map<String,Object> mutate(String op,Auth.Session session,Map<String,Object> input) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit())fail(409,"独立课酬事项变更不可嵌套事务");
            try {
                return Db.transaction(()->{
                    if(!OPS.contains(op))fail(404,"独立课酬操作不存在");
                    OrganizationAccess.Person actor=OrganizationAccessStore.person(session);
                    Map<String,Object> body=map(input);requestFields(op,body);
                    long project=integer(body.get("project_id"),false),expected=integer(body.get("expected_version"),true);
                    String key=code(body.get("case_code"),false),request=code(body.get("request_id"),false);
                    Map<String,Object> context=source(session,project,resource(op)),old=checked(key);
                    if(old!=null)binding(context,old);
                    String kind=op.equals("save")?enumValue(body.get("kind"),KINDS,false):old==null?null:text(old,"kind");
                    if("confirm".equals(op)&&"ADJUSTMENT".equals(kind))authorize(session,"settlement.correct",OrganizationAccess.Action.HANDLE,text(context,"organization_code"));
                    if("confirm".equals(op)&&"MIGRATION".equals(kind)&&map(old.get("payload")).get("historical_payment")!=null)
                        authorize(session,"settlement.pay",OrganizationAccess.Action.HANDLE,text(context,"organization_code"));
                    String canonical=canonical(body);
                    // Current real identity, permissions and source ownership precede replay lookup.
                    Map<String,Object> retry=Db.one("SELECT * FROM m05_case_requests WHERE account_id=? AND request_id=?",session.uid,request);
                    if(retry!=null) {
                        if(!Objects.equals(retry.get("actor_code"),actor.personCode())||!Objects.equals(retry.get("case_code"),key)||!Objects.equals(retry.get("operation"),op)||!Objects.equals(retry.get("payload"),canonical))fail(409,"请求编号已用于不同内容");
                        Map<String,Object> response=json(retry.get("response"));response.put("capabilities",capabilities(session,context,old));return response;
                    }
                    if(version(old)!=expected)fail(409,"事项版本已变化，请刷新后重试");
                    Map<String,Object> next=old==null?new LinkedHashMap<>():copy(old);
                    next.put("case_code",key);next.put("project_id",project);next.put("organization_code",context.get("organization_code"));
                    next.put("version",expected+1);next.put("context",context);
                    Map<String,Object> event=attestation(actor,session.uid,null);event.put("operation",op);
                    switch(op) {
                        case "save" -> {
                            if(old!=null&&Set.of("SUBMITTED","CONFIRMED","WITHDRAWN").contains(text(old,"state")))fail(409,"已提交、已确认或已作废的事项不能直接编辑");
                            if(old!=null&&!kind.equals(old.get("kind")))fail(409,"事项类型不能变更，请建立新事项");
                            next.put("kind",kind);next.put("state","DRAFT");next.put("payload",preparePayload(session,context,key,kind,map(body.get("payload"))));
                            next.put("submission",null);next.put("review",null);next.put("confirmation",null);next.put("withdrawal",null);next.put("confirmed_origin",null);
                        }
                        case "submit" -> {
                            requireState(old,Set.of("DRAFT","RETURNED"));
                            next.put("state","SUBMITTED");next.put("submission",actionAttestation(session,context,old,op,body,actor));next.put("review",null);
                        }
                        case "review" -> {
                            requireState(old,Set.of("SUBMITTED"));String decision=enumValue(body.get("decision"),Set.of("APPROVE","RETURN"),false);
                            if("RETURN".equals(decision)||body.get("reason_note")!=null||body.get("reason_code")!=null)body.put("reason_code",reviewReason(session,context,old,body));
                            Map<String,Object> review=actionAttestation(session,context,old,op,body,actor);review.put("decision",decision);review.put("approved_case_version",expected+1);
                            review.put("reason_code",code(body.get("reason_code"),!decision.equals("RETURN")));
                            if("APPROVE".equals(decision)) {
                                Plan plan=evaluate(session,context,next,review);ready(plan);review.put("approved_plan",plan.snapshot());next.put("state","APPROVED");
                            } else {review.put("approved_plan",null);next.put("state","RETURNED");}
                            next.put("review",review);
                        }
                        case "confirm" -> {
                            requireState(old,Set.of("APPROVED"));Map<String,Object> review=map(old.get("review"));
                            if(!"APPROVE".equals(review.get("decision")))fail(409,"事项尚无有效核准结论");
                            Plan plan=evaluate(session,context,old,review);ready(plan);
                            if(!canonical(plan.snapshot()).equals(canonical(map(review.get("approved_plan")))))fail(409,"核准依据已变化，请重新申报审核");
                            Map<String,Object> confirmation=attestation(actor,session.uid,code(review.get("evidence_code"),false));
                            confirmation.put("evidence_descriptor",review.get("evidence_descriptor"));confirmation.put("evidence_kind","CASE-review");
                            List<Map<String,Object>> chains=new ArrayList<>();
                            for(int i=0;i<plan.totals.size();i++) {
                                String chain=plan.chains.get(i),entry="M05-CASE-ENTRY-"+UUID.randomUUID();
                                Map<String,Object> h=plan.expected.get(chain)==null
                                        ?DeliverySettlementLedger.open(project,text(context,"organization_code"),chain,plan.totals.get(i),entry,"MIGRATION".equals(kind)?"LEGACY_OPENING":"CONFIRMED",code(review.get("evidence_code"),false))
                                        :DeliverySettlementLedger.replace(chain,plan.expected.get(chain),plan.totals.get(i),entry,code(review.get("evidence_code"),false));
                                chains.add(h);
                                if(plan.historicalPayment!=null) {
                                    Map<String,Object> p=plan.historicalPayment;
                                    DeliverySettlementLedger.payment(chain,entry,text(p,"payment_date"),text(p,"amount"),text(p,"evidence_code"),actor.personCode(),session.uid,true);
                                }
                            }
                            confirmation.put("chains",chains);confirmation.put("plan",plan.snapshot());next.put("confirmation",confirmation);
                            next.put("confirmed_origin",plan.origin);next.put("state","CONFIRMED");
                        }
                        case "payment" -> {
                            requireState(old,Set.of("CONFIRMED"));String chain=code(body.get("chain_code"),false),head=code(body.get("expected_entry_code"),false);
                            if(!linkedChains(old).contains(chain))fail(409,"该财务链不属于此事项");chain(session,context,chain,head);
                            Map<String,Object> paymentProof=actionAttestation(session,context,old,op,body,actor);
                            Map<String,Object> p=DeliverySettlementLedger.payment(chain,head,date(body.get("payment_date"),false),signedMoney(body.get("amount")),text(paymentProof,"evidence_code"),actor.personCode(),session.uid,false);
                            event.put("payment_proof",paymentProof);
                            event.put("payment_entry",p);next.put("state","CONFIRMED");
                        }
                        case "withdraw" -> {
                            requireState(old,Set.of("DRAFT","RETURNED"));
                            Object reason=proof(body,"reason_note","reason_reference");if(reason==null)fail(400,"作废事项必须填写实际原因");
                            Map<String,Object> descriptor=actionDescriptor(old,"withdraw",Map.of());
                            String evidence=DeliverySettlementEvidence.register(session,project,text(context,"organization_code"),key,"CASE-withdraw",descriptor,reason);
                            Map<String,Object> withdrawal=attestation(actor,session.uid,evidence);withdrawal.put("reason_code",evidence);
                            withdrawal.put("evidence_kind","CASE-withdraw");withdrawal.put("evidence_descriptor",descriptor);
                            next.put("withdrawal",withdrawal);next.put("state","WITHDRAWN");
                        }
                        default -> throw new IllegalStateException("未知操作");
                    }
                    next.put("last_event",event);persist(old,next,actor,session.uid);
                    Map<String,Object> response=view(session,context,checked(key));
                    Db.exec("INSERT INTO m05_case_requests VALUES(?,?,?,?,?,?,?)",session.uid,request,actor.personCode(),key,op,canonical,Json.write(response));return response;
                });
            } catch(IllegalArgumentException invalid) {throw new Api.ApiException(400,invalid.getMessage());}
            catch(SQLException conflict) {if("23505".equals(conflict.getSQLState()))throw new Api.ApiException(409,"事项、成果版本或旧账来源已登记，请刷新核对，不能重复计发");throw conflict;}
        }
    }
    private static final class Plan {
        final List<Map<String,Object>> totals=new ArrayList<>();final List<String> chains=new ArrayList<>(),issues=new ArrayList<>();
        final Map<String,String> expected=new LinkedHashMap<>();final Map<String,Object> basis=new LinkedHashMap<>();
        String origin;Map<String,Object> historicalPayment;
        Map<String,Object> snapshot() {Map<String,Object> s=new LinkedHashMap<>();s.put("totals",totals);s.put("chain_codes",chains);s.put("expected_entries",expected);s.put("origin_key",origin);s.put("historical_payment",historicalPayment);s.put("basis",basis);return s;}
    }
    private static Plan evaluate(Auth.Session session,Map<String,Object> context,Map<String,Object> row,Map<String,Object> review) throws Exception {
        Plan plan=new Plan();String kind=text(row,"kind");Map<String,Object> p=map(row.get("payload"));
        bindPayload(null,context,text(row,"case_code"),kind,p,Map.of(),false);
        if(p.get("service_date")!=null&&LocalDate.parse(text(p,"service_date")).isAfter(today()))plan.issues.add("不能核准未来尚未发生的业务日期");
        switch(kind) {
            case "DEVELOPMENT" -> development(context,row,p,plan);
            case "MIGRATION" -> migration(context,row,p,plan);
            case "ADJUSTMENT" -> adjustment(session,context,row,p,review,plan);
            case "WAIVER" -> waiver(session,context,row,p,plan);
            default -> fail(409,"事项类型无效");
        }
        if(plan.origin!=null&&Db.one("SELECT case_code FROM m05_cases WHERE confirmed_origin=? AND case_code<>?",plan.origin,row.get("case_code"))!=null)plan.issues.add("该成果版本或旧账来源已经确认，不能重复计发");
        return plan;
    }
    private static void development(Map<String,Object> context,Map<String,Object> row,Map<String,Object> p,Plan plan) throws Exception {
        Map<String,Object> ev=map(p.get("evidence"));List<String> errors=plan.issues;
        for(String key:List.of("activity","service_date","deliverable_code","deliverable_version","lead_teacher_id","payable_hours","completed_on"))need(p,key,key,errors);
        requiredEvidence(ev,"deliverable","成果版本",errors);requiredEvidence(ev,"payable_hours","开发计酬课时",errors);requiredEvidence(ev,"completion","成果完成",errors);
        if(p.get("lead_teacher_id")!=null)teacher(p.get("lead_teacher_id"),errors);
        String appointed=(String)p.get("appointed_on"),service=(String)p.get("service_date");
        if(appointed==null)errors.add("缺少工作开展前的获聘日期");else {requiredEvidence(ev,"appointment","获聘日期",errors);if(service!=null&&LocalDate.parse(appointed).isAfter(LocalDate.parse(service)))errors.add("获聘前开展的开发不符合支付条件");}
        Truth research=truth(p,"research_team"),annual=truth(p,"annual_plan"),customer=truth(p,"customer_paid"),applicable=truth(p,"service_date_applicable");
        for(String key:List.of("research_team","annual_plan","customer_paid"))if(truth(p,key)!=Truth.UNKNOWN)requiredEvidence(ev,key,key,errors);
        if(applicable==Truth.YES)requiredEvidence(ev,"applicability","本次业务适用性",errors);
        if(research!=Truth.YES&&p.get("grade")!=null)requiredEvidence(ev,"grade","负责人等级",errors);
        for(String key:List.of("customer_written_payment_agreement","customer_acceptance","company_need","annual_review_passed","company_approval","previous_grant"))if(truth(p,key)!=Truth.UNKNOWN)requiredEvidence(ev,key,key,errors);
        if(p.get("repetition_percent")!=null)requiredEvidence(ev,"repetition","成果重复率",errors);
        if(p.get("completed_on")!=null)milestone(p,"completed_on",service,"成果完成",errors);
        String path=(String)p.get("development_path");
        if("CUSTOMER_REQUESTED".equals(path)&&truth(p,"customer_acceptance")==Truth.YES)milestone(p,"customer_acceptance_on",(String)p.get("completed_on"),"客户验收",errors);
        if("SELF_INITIATED".equals(path)&&truth(p,"annual_review_passed")==Truth.YES)milestone(p,"annual_review_on",(String)p.get("completed_on"),"年度评审",errors);
        if(truth(p,"company_approval")==Truth.YES)milestone(p,"company_approval_on","CUSTOMER_REQUESTED".equals(path)?(String)p.get("customer_acceptance_on"):(String)p.get("annual_review_on"),"公司核准",errors);
        boolean joint="JOINT_DEVELOPMENT".equals(p.get("activity"));Grade grade=p.get("grade")==null?null:Grade.valueOf((String)p.get("grade"));Truth effectiveResearch=research;
        if(joint&&research==Truth.YES&&p.get("joint_reference_grade")!=null&&ev.get("joint_basis")!=null) {
            if(!"LECTURER".equals(p.get("joint_reference_grade"))&&!Objects.equals(p.get("joint_reference_grade"),p.get("grade")))
                errors.add("本案合作开发计酬等级只能为讲师标准或已核实的主负责人本人等级");
            if(!"LECTURER".equals(p.get("joint_reference_grade")))requiredEvidence(ev,"grade","已核实的主负责人本人等级",errors);
            grade=Grade.valueOf((String)p.get("joint_reference_grade"));effectiveResearch=Truth.NO;
            plan.basis.put("joint_reference_grade",grade.name());plan.basis.put("joint_basis",ev.get("joint_basis"));
            plan.basis.put("joint_basis_description","本案经逐案核准的研发负责人合作开发计酬等级依据，不作为通用费率规则");
        }
        if(!joint&&p.get("joint_reference_grade")!=null)errors.add("单独开发不接受合作开发专用等级依据");
        if(p.get("activity")==null)return;
        var preview=DeliverySettlementApprovedPolicy.preview(new DeliverySettlementApprovedPolicy.Request(
                new RateRequest(Activity.valueOf((String)p.get("activity")),grade,null,effectiveResearch),
                new VerifiedFacts(appointed==null||service==null?Truth.UNKNOWN:LocalDate.parse(appointed).isAfter(LocalDate.parse(service))?Truth.NO:Truth.YES,annual,customer,decimal(p.get("repetition_percent"),true),truth(p,"previous_grant")),
                decimal(p.get("payable_hours"),true),applicable,Truth.YES,Truth.YES,Truth.YES,false,false,
                new DeliverySettlementApprovedPolicy.DevelopmentEvidence(path==null?null:DeliverySettlementApprovedPolicy.DevelopmentPath.valueOf(path),truth(p,"customer_written_payment_agreement"),truth(p,"customer_acceptance"),truth(p,"company_need"),truth(p,"annual_review_passed"),truth(p,"company_approval"),null,joint?Truth.YES:Truth.UNKNOWN),
                new DeliverySettlementApprovedPolicy.MoneyRounding(2,RoundingMode.HALF_UP,"PER_LINE","USER-20260923-MONEY-LINE-2DP-HALF-UP")));
        for(var issue:preview.issues())if(!Set.of("JOINT_INDIVIDUAL_ALLOCATION_PERCENT_MISSING","JOINT_ALLOCATION_APPROVAL_MISSING","JOINT_ALLOCATION_REMAINDER_RULE_MISSING").contains(issue.code()))errors.add(issue.message());
        BigDecimal pool=joint?preview.poolFinalAmount():preview.finalAmount();
        List<Map<String,Object>> allocations=objectList(p.get("allocations"));
        if(joint) {
            requiredEvidence(ev,"allocation","合作开发各成员明确金额分配",errors);
            if(allocations.isEmpty())errors.add("合作开发须明确每位成员经核准的人民币分配金额");
            BigDecimal sum=new BigDecimal("0.00");Set<Long> seen=new HashSet<>();
            for(Map<String,Object> a:allocations) {long id=integer(a.get("teacher_id"),false);if(!seen.add(id))errors.add("合作开发成员不能重复");teacher(id,errors);sum=sum.add(nonnegativeMoney(a.get("amount")));}
            if(p.get("lead_teacher_id")!=null&&!seen.contains(integer(p.get("lead_teacher_id"),false)))errors.add("合作开发分配须明确包含主负责人");
            if(pool!=null&&sum.compareTo(pool)!=0)errors.add("各成员明确分配金额之和必须等于按负责人等级计算并舍入后的总池");
        } else if(!allocations.isEmpty())errors.add("单独开发不接受多人分配金额");
        if(pool==null){if(errors.isEmpty())errors.add("开发金额尚不能核准");return;}
        if(service==null||p.get("lead_teacher_id")==null||p.get("deliverable_code")==null||p.get("deliverable_version")==null)return;
        plan.origin="DEV-"+digest(canonical(List.of(p.get("deliverable_code"),p.get("deliverable_version"))));
        plan.basis.put("execution_version",DeliverySettlementApprovedPolicy.EXECUTION_VERSION);
        plan.basis.put("unit_rate",preview.rate()==null?null:preview.rate().unitRate().toPlainString());plan.basis.put("raw_pool_amount",joint?plain(preview.poolRawAmount()):plain(preview.rawAmount()));plan.basis.put("rounded_pool_amount",pool.toPlainString());
        if(joint)for(Map<String,Object> allocation:allocations) {
            long teacher=integer(allocation.get("teacher_id"),false);
            plan.totals.add(total(context,row,teacher,service,(String)p.get("activity"),nonnegativeMoney(allocation.get("amount")).toPlainString(),emptyHours(),DeliverySettlementApprovedPolicy.EXECUTION_VERSION,DeliverySettlementApprovedPolicy.EXECUTION_VERSION));plan.chains.add(caseChain(text(row,"case_code"),teacher));
        } else {
            long teacher=integer(p.get("lead_teacher_id"),false);Map<String,Object> h=emptyHours();h.put("PAYABLE",p.get("payable_hours"));
            plan.totals.add(total(context,row,teacher,service,(String)p.get("activity"),pool.toPlainString(),h,DeliverySettlementApprovedPolicy.EXECUTION_VERSION,DeliverySettlementApprovedPolicy.EXECUTION_VERSION));plan.chains.add(caseChain(text(row,"case_code"),teacher));
        }
    }
    private static void migration(Map<String,Object> context,Map<String,Object> row,Map<String,Object> p,Plan plan) throws Exception {
        for(String key:List.of("legacy_source_code","legacy_rule_code","service_date","teacher_id","amount","source_evidence","original_approval_evidence"))need(p,key,key,plan.issues);
        if(p.get("teacher_id")!=null)teacher(p.get("teacher_id"),plan.issues);
        if(!plan.issues.isEmpty())return;
        BigDecimal amount=nonnegativeMoney(p.get("amount"));long teacher=integer(p.get("teacher_id"),false);
        plan.origin="LEGACY-"+digest(text(p,"legacy_source_code"));plan.basis.put("legacy_rule_code",p.get("legacy_rule_code"));plan.basis.put("source_evidence",p.get("source_evidence"));plan.basis.put("original_approval_evidence",p.get("original_approval_evidence"));
        plan.totals.add(total(context,row,teacher,text(p,"service_date"),"LEGACY",amount.toPlainString(),map(p.get("hours")),legacyRule(text(p,"legacy_rule_code")),legacyRule(text(p,"legacy_rule_code"))));plan.chains.add(caseChain(text(row,"case_code"),teacher));
        if(p.get("historical_payment")!=null) {
            Map<String,Object> payment=map(p.get("historical_payment"));BigDecimal paid=nonnegativeMoney(payment.get("amount"));
            need(payment,"evidence_code","历史实付说明和凭证",plan.issues);
            if(paid.signum()<=0||paid.compareTo(amount)>0)plan.issues.add("历史实付金额须大于零且不得超过原应付金额");
            if(LocalDate.parse(text(payment,"payment_date")).isAfter(today()))plan.issues.add("历史实付日期不能为未来日期");plan.historicalPayment=payment;
        }
    }
    private static void adjustment(Auth.Session session,Map<String,Object> context,Map<String,Object> row,Map<String,Object> p,Map<String,Object> review,Plan plan) throws Exception {
        for(String key:List.of("chain_code","expected_entry_code","reason_code","evidence_code"))need(p,key,key,plan.issues);
        if(!plan.issues.isEmpty())return;
        String chainCode=text(p,"chain_code"),expected=text(p,"expected_entry_code");Map<String,Object> h=chain(session,context,chainCode,expected),before=map(h.get("total"));
        if(Boolean.TRUE.equals(p.get("cancellation"))) {
            if("JOINT_DEVELOPMENT".equals(before.get("activity"))) {
                Map<String,Object> original=checked(text(before,"source_code"));
                if(original==null||!"DEVELOPMENT".equals(original.get("kind"))||!"CONFIRMED".equals(original.get("state")))fail(409,"缺少合作开发原始事项绑定");binding(context,original);
                List<String> group=linkedChains(original);Map<String,Object> expectedEntries=map(p.get("expected_entries"));
                for(String member:group) {
                    String e=member.equals(chainCode)?expected:(String)expectedEntries.get(member);
                    if(e==null){plan.issues.add("合作开发整组取消须明确全部成员财务链的当前依据");continue;}
                    if(expectedEntries.containsKey(member)&&!Objects.equals(expectedEntries.get(member),e))plan.issues.add("合作开发取消的当前依据不一致");
                    Map<String,Object> memberHead=chain(session,context,member,e),cancelled=copy(map(memberHead.get("total")));
                    cancelled.put("amount","0.00");Map<String,Object> hours=emptyHours();for(String k:HOURS)if(map(cancelled.get("hours")).get(k)!=null)hours.put(k,"0");cancelled.put("hours",hours);
                    plan.totals.add(cancelled);plan.chains.add(member);plan.expected.put(member,e);
                }
                for(String member:expectedEntries.keySet())if(!group.contains(member))plan.issues.add("不能提交无关合作开发财务链");
                plan.basis.put("cancellation",true);plan.basis.put("reason_code",p.get("reason_code"));plan.basis.put("scope","ALL_JOINT_RECIPIENTS");return;
            }
            Map<String,Object> cancelled=copy(before);cancelled.put("amount","0.00");Map<String,Object> hours=emptyHours();for(String k:HOURS)if(map(before.get("hours")).get(k)!=null)hours.put(k,"0");cancelled.put("hours",hours);
            if("TEACHING".equals(before.get("activity")))cancelled.put("source_fact_revision",currentTeaching(session,context,before).factVersion());
            plan.totals.add(cancelled);plan.chains.add(chainCode);plan.expected.put(chainCode,expected);plan.basis.put("cancellation",true);plan.basis.put("reason_code",p.get("reason_code"));return;
        }
        need(p,"service_date","更正后业务日期",plan.issues);String activity=text(before,"activity");
        if("TEACHING".equals(activity)) {
            if(p.get("claim")==null)plan.issues.add("缺少更正后的授课计酬申报");if(p.get("hours")==null)plan.issues.add("缺少更正后的授课课时");if(!plan.issues.isEmpty())return;
            var source=currentTeaching(session,context,before);Map<String,Object> hours=map(p.get("hours"));BigDecimal actual=null;
            if(hours.get("actual_minutes")!=null)actual=DeliverySettlementHours.convert(text(hours,"actual_minutes"),DeliverySettlementHours.currentUserRule()).classHours();
            String actor=review==null?"PENDING-REVIEW":text(review,"actor_code"),evidence=review==null?text(p,"evidence_code"):text(review,"evidence_code");Instant at=review==null?Instant.now():Instant.parse(text(review,"created_at"));
            DeliverySettlement.Fact fact=new DeliverySettlement.Fact(integer(before.get("source_record_id"),false),review==null?version(row):integer(review.get("approved_case_version"),false),text(before,"source_code"),text(before,"system_teacher_code"),text(context,"organization_code"),LocalDate.parse(text(p,"service_date")),null,
                    new DeliverySettlement.Hours(decimal(hours.get("estimated"),true),decimal(hours.get("planned"),true),actual,decimal(hours.get("payable"),true)),new DeliverySettlement.Verification(actor,at,evidence),DeliverySettlement.DataMode.CONFIGURED);
            plan.issues.addAll(DeliverySettlementExecution.eligibility(fact,map(p.get("claim"))));if(!plan.issues.isEmpty())return;
            var executable=DeliverySettlementExecution.build(fact,map(p.get("claim")),executionSettings(),"M05-CASE-"+digest(text(row,"case_code")+canonical(p)).substring(0,32),evidence);
            Map<String,Object> config=executable.configuration(),rate=map(config.get("rate"));BigDecimal amount=executable.fact().hours().payable().multiply(new BigDecimal(text(rate,"unit_rate"))).setScale(2,RoundingMode.HALF_UP);
            Map<String,Object> adjusted=copy(before);adjusted.put("service_date",p.get("service_date"));adjusted.put("payroll_month",payroll(text(p,"service_date")));adjusted.put("amount",amount.toPlainString());adjusted.put("source_fact_revision",source.factVersion());
            Map<String,Object> adjustedHours=emptyHours();adjustedHours.put("ESTIMATED",plain(fact.hours().estimated()));adjustedHours.put("PLANNED",plain(fact.hours().planned()));adjustedHours.put("ACTUAL",plain(actual));adjustedHours.put("PAYABLE",plain(fact.hours().payable()));adjusted.put("hours",adjustedHours);
            adjusted.put("policy_version",map(config.get("rule")).get("version"));adjusted.put("rate_version",rate.get("version"));
            plan.basis.put("configuration",config);plan.basis.put("source_fact",source.factPayload());plan.totals.add(adjusted);plan.chains.add(chainCode);plan.expected.put(chainCode,expected);
        } else if("LEGACY".equals(activity)) {
            for(String key:List.of("amount","legacy_rule_code","source_evidence","original_approval_evidence"))need(p,key,key,plan.issues);
            if(p.get("legacy_rule_code")!=null&&!Objects.equals(legacyRule(text(p,"legacy_rule_code")),before.get("policy_version")))plan.issues.add("旧制更正须保留原旧制规则标识，不能套用新费率");if(!plan.issues.isEmpty())return;
            Map<String,Object> adjusted=copy(before);adjusted.put("service_date",p.get("service_date"));adjusted.put("payroll_month",payroll(text(p,"service_date")));adjusted.put("amount",nonnegativeMoney(p.get("amount")).toPlainString());adjusted.put("hours",p.get("legacy_hours")==null?emptyHours():p.get("legacy_hours"));
            plan.totals.add(adjusted);plan.chains.add(chainCode);plan.expected.put(chainCode,expected);
            plan.basis.put("legacy_rule_code",p.get("legacy_rule_code"));plan.basis.put("source_evidence",p.get("source_evidence"));plan.basis.put("original_approval_evidence",p.get("original_approval_evidence"));
        } else if(Set.of("SOLO_DEVELOPMENT","JOINT_DEVELOPMENT").contains(activity)) {
            if(p.get("development")==null){plan.issues.add("开发更正须重新提交完整成果资格及明确分配依据");return;}
            Map<String,Object> development=map(p.get("development"));if(!Objects.equals(development.get("service_date"),p.get("service_date")))plan.issues.add("更正日期须与完整开发依据一致");
            Map<String,Object> original=checked(text(before,"source_code"));
            if(original==null||!"DEVELOPMENT".equals(original.get("kind"))||!"CONFIRMED".equals(original.get("state")))fail(409,"缺少开发原始事项绑定");binding(context,original);
            Map<String,Object> originalPayload=map(original.get("payload"));
            for(String key:List.of("activity","deliverable_code","deliverable_version","lead_teacher_id"))if(!Objects.equals(canonical(originalPayload.get(key)),canonical(development.get(key))))plan.issues.add("开发更正不能转移成果版本、活动或主负责人");
            Plan revised=new Plan();development(context,original,development,revised);plan.issues.addAll(revised.issues);if(!plan.issues.isEmpty())return;
            List<String> originalChains=linkedChains(original);if(!new HashSet<>(originalChains).equals(new HashSet<>(revised.chains)))plan.issues.add("合作开发更正须保留原成员集合，并逐成员明确金额");
            Map<String,Object> expectedEntries=p.get("expected_entries")==null?Map.of():map(p.get("expected_entries"));
            for(int i=0;i<revised.chains.size();i++) {
                String c=revised.chains.get(i),e=c.equals(chainCode)?expected:(String)expectedEntries.get(c);
                if(e==null){plan.issues.add("缺少合作开发关联财务链的当前依据编码");continue;}
                if(expectedEntries.containsKey(c)&&!Objects.equals(expectedEntries.get(c),e))plan.issues.add("合作开发依据编码不一致");
                Map<String,Object> current=chain(session,context,c,e),currentTotal=map(current.get("total")),replacement=revised.totals.get(i);
                for(String key:List.of("source_record_id","source_fact_revision","source_code","organization_code","teacher_id","teacher_code","system_teacher_code","course_id","course_code"))replacement.put(key,currentTotal.get(key));
                plan.chains.add(c);plan.totals.add(replacement);plan.expected.put(c,e);
            }
            for(String key:expectedEntries.keySet())if(!originalChains.contains(key))plan.issues.add("不得提交无关开发财务链依据");plan.basis.putAll(revised.basis);
        } else plan.issues.add("该酬金类型尚无可核准的更正依据");
    }
    private static DeliverySettlementIntegration.WorkflowContext currentTeaching(Auth.Session session,Map<String,Object> context,Map<String,Object> total) throws Exception {
        long dispatch=integer(total.get("source_record_id"),false);
        if(!("DISPATCH-"+dispatch).equals(total.get("source_code")))fail(409,"授课财务链来源编码不一致");
        var ctx=DeliverySettlementIntegration.workflowContext(session,dispatch,null,true);
        if(ctx.projectId()!=integer(context.get("project_id"),false)||!ctx.organizationCode().equals(context.get("organization_code"))||ctx.teacherId()!=integer(total.get("teacher_id"),false))fail(409,"授课财务来源绑定已变化");return ctx;
    }
    private static void waiver(Auth.Session session,Map<String,Object> context,Map<String,Object> row,Map<String,Object> p,Plan plan) throws Exception {
        for(String key:List.of("dispatch_id","reason_code","evidence_code"))need(p,key,key,plan.issues);if(!plan.issues.isEmpty())return;
        long id=integer(p.get("dispatch_id"),false);var ctx=DeliverySettlementIntegration.workflowContext(session,id,null,true);
        if(ctx.projectId()!=integer(context.get("project_id"),false)||!ctx.organizationCode().equals(context.get("organization_code")))fail(403,"免课酬授课不属于本项目和组织");
        if(DeliverySettlementLedger.head("DISPATCH-"+id)!=null){plan.issues.add("该授课已有财务依据，须办理更正，不能重复免课酬");return;}
        DeliverySettlement.Fact f=DeliverySettlementIntegration.workflowFact(ctx);Map<String,Object> h=emptyHours();h.put("ESTIMATED",plain(f.hours().estimated()));h.put("PLANNED",plain(f.hours().planned()));h.put("ACTUAL",plain(f.hours().actual()));h.put("PAYABLE","0");
        Map<String,Object> t=total(context,row,ctx.teacherId(),ctx.serviceDate().toString(),"TEACHING","0.00",h,"M05-CASE-NO-FEE","M05-CASE-NO-FEE");t.put("source_record_id",id);t.put("source_code","DISPATCH-"+id);t.put("source_fact_revision",ctx.factVersion());
        plan.totals.add(t);plan.chains.add("DISPATCH-"+id);plan.origin="WAIVER-DISPATCH-"+id;plan.basis.put("fact_version",ctx.factVersion());plan.basis.put("source_fact",ctx.factPayload());plan.basis.put("reason_code",p.get("reason_code"));
    }
    private static Map<String,Object> parsePayload(String kind,Map<String,Object> p) throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();
        if("DEVELOPMENT".equals(kind)) {
            whitelist(p,DEV_FIELDS);out.put("activity",enumValue(p.get("activity"),Set.of("SOLO_DEVELOPMENT","JOINT_DEVELOPMENT"),true));
            for(String k:List.of("service_date","appointed_on","completed_on","customer_acceptance_on","annual_review_on","company_approval_on"))out.put(k,date(p.get(k),true));
            for(String k:List.of("deliverable_code","deliverable_version"))out.put(k,code(p.get(k),true));
            out.put("lead_teacher_id",p.get("lead_teacher_id")==null?null:integer(p.get("lead_teacher_id"),false));
            for(String k:List.of("grade","joint_reference_grade"))out.put(k,enumValue(p.get(k),Set.of("LECTURER","SENIOR","SPECIAL","DISTINGUISHED"),true));
            for(String k:List.of("research_team","annual_plan","customer_paid","service_date_applicable","previous_grant","customer_written_payment_agreement","customer_acceptance","company_need","annual_review_passed","company_approval"))out.put(k,p.get(k)==null?"UNKNOWN":enumValue(p.get(k),Set.of("YES","NO","UNKNOWN"),false));
            out.put("development_path",enumValue(p.get("development_path"),Set.of("CUSTOMER_REQUESTED","SELF_INITIATED"),true));
            out.put("payable_hours",plain(decimal(p.get("payable_hours"),true)));BigDecimal repetition=decimal(p.get("repetition_percent"),true);if(repetition!=null&&repetition.compareTo(new BigDecimal("100"))>0)fail(400,"重复率须为0至100百分比");out.put("repetition_percent",plain(repetition));
            Map<String,Object> e=p.get("evidence")==null?Map.of():map(p.get("evidence"));whitelist(e,DEV_EVIDENCE);Map<String,Object> refs=new LinkedHashMap<>();for(String k:e.keySet())if(e.get(k)!=null)refs.put(k,code(e.get(k),false));out.put("evidence",refs);
            List<Map<String,Object>> allocations=new ArrayList<>();for(Map<String,Object> a:objectList(p.get("allocations"))) {exact(a,Set.of("teacher_id","amount"));allocations.add(Map.of("teacher_id",integer(a.get("teacher_id"),false),"amount",nonnegativeMoney(a.get("amount")).toPlainString()));}if(allocations.size()>100)fail(400,"合作开发成员超过单事项容量");out.put("allocations",allocations);
        } else if("MIGRATION".equals(kind)) {
            whitelist(p,MIGRATION_FIELDS);for(String k:List.of("source_evidence","original_approval_evidence"))out.put(k,code(p.get(k),true));out.put("legacy_source_code",businessReference(p.get("legacy_source_code"),300));out.put("legacy_rule_code",businessReference(p.get("legacy_rule_code"),200));out.put("service_date",date(p.get("service_date"),true));out.put("teacher_id",p.get("teacher_id")==null?null:integer(p.get("teacher_id"),false));out.put("amount",p.get("amount")==null?null:nonnegativeMoney(p.get("amount")).toPlainString());out.put("hours",hours(p.get("hours")));out.put("historical_payment",null);
            if(p.get("historical_payment")!=null) {Map<String,Object> payment=map(p.get("historical_payment"));whitelist(payment,Set.of("payment_date","amount","evidence_code"));Map<String,Object> normalized=new LinkedHashMap<>();normalized.put("payment_date",date(payment.get("payment_date"),false));normalized.put("amount",nonnegativeMoney(payment.get("amount")).toPlainString());normalized.put("evidence_code",code(payment.get("evidence_code"),true));out.put("historical_payment",normalized);}
        } else if("ADJUSTMENT".equals(kind)) {
            whitelist(p,ADJUST_FIELDS);for(String k:List.of("chain_code","expected_entry_code","reason_code","evidence_code","source_evidence","original_approval_evidence"))out.put(k,code(p.get(k),true));out.put("legacy_rule_code",businessReference(p.get("legacy_rule_code"),200));out.put("service_date",date(p.get("service_date"),true));
            if(p.get("cancellation")!=null&&!(p.get("cancellation") instanceof Boolean))fail(400,"冲销标志须为布尔值");out.put("cancellation",Boolean.TRUE.equals(p.get("cancellation")));out.put("claim",p.get("claim")==null?null:DeliverySettlementExecution.parseClaim(map(p.get("claim"))));out.put("hours",null);
            if(p.get("hours")!=null) {Map<String,Object> h=map(p.get("hours"));whitelist(h,Set.of("estimated","planned","actual_minutes","payable"));Map<String,Object> normalized=new LinkedHashMap<>();for(String k:List.of("estimated","planned","actual_minutes","payable"))normalized.put(k,plain(decimal(h.get(k),true)));out.put("hours",normalized);}
            out.put("amount",p.get("amount")==null?null:nonnegativeMoney(p.get("amount")).toPlainString());out.put("legacy_hours",p.get("legacy_hours")==null?null:hours(p.get("legacy_hours")));out.put("development",p.get("development")==null?null:parsePayload("DEVELOPMENT",map(p.get("development"))));
            Map<String,Object> entries=p.get("expected_entries")==null?Map.of():map(p.get("expected_entries"));Map<String,Object> normalized=new LinkedHashMap<>();for(String key:entries.keySet())normalized.put(code(key,false),code(entries.get(key),false));if(normalized.size()>100)fail(400,"关联财务链超过容量");out.put("expected_entries",normalized);
        } else if("WAIVER".equals(kind)) {
            whitelist(p,Set.of("dispatch_id","reason_code","evidence_code"));out.put("dispatch_id",p.get("dispatch_id")==null?null:integer(p.get("dispatch_id"),false));out.put("reason_code",code(p.get("reason_code"),true));out.put("evidence_code",code(p.get("evidence_code"),true));
        } else fail(400,"事项类型无效");
        return out;
    }
    private static Map<String,Object> preparePayload(Auth.Session session,Map<String,Object> context,String source,String kind,Map<String,Object> raw) throws Exception {
        proofFields(kind,raw);Map<String,Object> p=parsePayload(kind,map(stripProofs(raw)));
        bindPayload(session,context,source,kind,p,raw,true);return p;
    }
    private static void proofFields(String kind,Map<String,Object> raw) throws Exception {
        Set<String> fields=new HashSet<>(switch(kind){case "DEVELOPMENT"->DEV_FIELDS;case "MIGRATION"->MIGRATION_FIELDS;case "ADJUSTMENT"->ADJUST_FIELDS;default->Set.of("dispatch_id","reason_code","evidence_code");});
        fields.addAll(switch(kind){case "DEVELOPMENT"->Set.of("evidence_notes");case "MIGRATION"->Set.of("source_note","source_reference","original_approval_note","original_approval_reference");case "ADJUSTMENT"->Set.of("evidence_note","evidence_reference","reason_note","reason_reference","source_note","source_reference","original_approval_note","original_approval_reference");default->Set.of("evidence_note","evidence_reference","reason_note","reason_reference");});
        whitelist(raw,fields);
        if(raw.get("evidence_notes")!=null)whitelist(map(raw.get("evidence_notes")),DEV_EVIDENCE);
        if("ADJUSTMENT".equals(kind)&&raw.get("claim")!=null) {
            Map<String,Object> claim=map(raw.get("claim"));whitelist(claim,Set.of("grade","day_type","research_team","appointed_on","annual_plan","customer_paid","service_date_applicable","evidence","evidence_notes"));
            if(claim.get("evidence_notes")!=null)whitelist(map(claim.get("evidence_notes")),Set.of("grade","day_type","research_team","appointment","annual_plan","customer_paid","applicability"));
        }
        if("ADJUSTMENT".equals(kind)&&raw.get("development")!=null)proofFields("DEVELOPMENT",map(raw.get("development")));
        if("MIGRATION".equals(kind)&&raw.get("historical_payment")!=null)whitelist(map(raw.get("historical_payment")),Set.of("payment_date","amount","evidence_code","note","reference"));
    }
    private static Object stripProofs(Object raw) throws Exception {
        if(raw instanceof Map<?,?>){Map<String,Object> out=new LinkedHashMap<>();for(var e:map(raw).entrySet())if(!Set.of("evidence_notes","evidence_note","evidence_reference","reason_note","reason_reference","source_note","source_reference","original_approval_note","original_approval_reference","note","reference").contains(e.getKey()))out.put(e.getKey(),stripProofs(e.getValue()));return out;}
        if(raw instanceof List<?> list){List<Object> out=new ArrayList<>();for(Object v:list)out.add(stripProofs(v));return out;}return raw;
    }
    private static Object semantic(Object raw) throws Exception {
        if(raw instanceof Map<?,?>){Map<String,Object> out=new LinkedHashMap<>();for(var e:map(raw).entrySet())if(!Set.of("evidence","evidence_code","reason_code","source_evidence","original_approval_evidence").contains(e.getKey()))out.put(e.getKey(),semantic(e.getValue()));return out;}
        if(raw instanceof List<?> list){List<Object> out=new ArrayList<>();for(Object v:list)out.add(semantic(v));return out;}return raw;
    }
    private static void bindPayload(Auth.Session session,Map<String,Object> context,String source,String kind,Map<String,Object> p,Map<String,Object> raw,boolean register) throws Exception {
        Map<String,Object> subject=new LinkedHashMap<>();subject.put("case_kind",kind);subject.put("source",context);subject.put("values",semantic(p));
        if("DEVELOPMENT".equals(kind))bindEvidenceMap(session,context,source,"development",p,raw,subject,register);
        else if("MIGRATION".equals(kind)) {
            bindSingle(session,context,source,"legacy-source",p,"source_evidence",proof(raw,"source_note","source_reference"),subject,register);
            bindSingle(session,context,source,"legacy-approval",p,"original_approval_evidence",proof(raw,"original_approval_note","original_approval_reference"),subject,register);
            if(p.get("historical_payment")!=null){Map<String,Object> payment=map(p.get("historical_payment")),original=raw.get("historical_payment")==null?Map.of():map(raw.get("historical_payment"));bindSingle(session,context,source,"historical-payment",payment,"evidence_code",proof(original,"note","reference"),subject,register);p.put("historical_payment",payment);}
        } else {
            bindSingle(session,context,source,"reason",p,"reason_code",proof(raw,"reason_note","reason_reference"),subject,register);
            bindSingle(session,context,source,kind.toLowerCase(Locale.ROOT),p,"evidence_code",proof(raw,"evidence_note","evidence_reference"),subject,register);
            if("ADJUSTMENT".equals(kind)) {
                if(p.get("claim")!=null){Map<String,Object> claim=map(p.get("claim")),original=raw.get("claim")==null?Map.of():map(raw.get("claim"));bindEvidenceMap(session,context,source,"teaching",claim,original,subject,register);p.put("claim",claim);}
                if(p.get("development")!=null){Map<String,Object> dev=map(p.get("development")),original=raw.get("development")==null?Map.of():map(raw.get("development"));bindEvidenceMap(session,context,source,"development",dev,original,subject,register);p.put("development",dev);}
                bindSingle(session,context,source,"legacy-source",p,"source_evidence",proof(raw,"source_note","source_reference"),subject,register);
                bindSingle(session,context,source,"legacy-approval",p,"original_approval_evidence",proof(raw,"original_approval_note","original_approval_reference"),subject,register);
            }
        }
    }
    private static void bindEvidenceMap(Auth.Session session,Map<String,Object> context,String source,String prefix,Map<String,Object> p,Map<String,Object> raw,Map<String,Object> subject,boolean register) throws Exception {
        Map<String,Object> refs=map(p.get("evidence")),notes=raw.get("evidence_notes")==null?Map.of():map(raw.get("evidence_notes"));Set<String> keys=new LinkedHashSet<>(refs.keySet());keys.addAll(notes.keySet());
        for(String field:keys)bindSingle(session,context,source,prefix+"-"+field,refs,field,notes.get(field),subject,register);p.put("evidence",refs);
    }
    private static void bindSingle(Auth.Session session,Map<String,Object> context,String source,String field,Map<String,Object> p,String key,Object proof,Map<String,Object> subject,boolean register) throws Exception {
        if(p.get(key)==null&&proof==null)return;
        Map<String,Object> descriptor=new LinkedHashMap<>(subject);descriptor.put("field",field);String kind="CASE-FIELD-"+field;
        long project=integer(context.get("project_id"),false);String org=text(context,"organization_code");
        if(register&&proof!=null)p.put(key,DeliverySettlementEvidence.register(session,project,org,source,kind,descriptor,proof));
        else DeliverySettlementEvidence.resolve(code(p.get(key),false),project,org,source,kind,descriptor);
    }
    private static Object proof(Map<String,Object> input,String note,String reference) throws Exception {
        if(input.get(note)==null){if(input.get(reference)!=null)fail(400,"填写凭证定位时也须填写实际依据说明");return null;}
        Map<String,Object> p=new LinkedHashMap<>();p.put("note",input.get(note));p.put("reference",input.get(reference));return p;
    }
    private static Map<String,Object> actionAttestation(Auth.Session session,Map<String,Object> context,Map<String,Object> row,String action,Map<String,Object> body,OrganizationAccess.Person actor) throws Exception {
        Map<String,Object> descriptor=actionDescriptor(row,action,body);
        String kind="CASE-"+action;long project=integer(context.get("project_id"),false);String org=text(context,"organization_code"),source=text(row,"case_code");Object proof=proof(body,"evidence_note","evidence_reference");String evidence;
        if(proof!=null)evidence=DeliverySettlementEvidence.register(session,project,org,source,kind,descriptor,proof);
        else {evidence=code(body.get("evidence_code"),false);DeliverySettlementEvidence.resolve(evidence,project,org,source,kind,descriptor);}
        Map<String,Object> a=attestation(actor,session.uid,evidence);a.put("evidence_kind",kind);a.put("evidence_descriptor",descriptor);return a;
    }
    private static Map<String,Object> actionDescriptor(Map<String,Object> row,String action,Map<String,Object> fields) throws Exception {
        Map<String,Object> d=new LinkedHashMap<>();d.put("case_code",row.get("case_code"));d.put("case_version",version(row));d.put("action",action);d.put("payload_digest",digest(canonical(row.get("payload"))));
        if("review".equals(action)) {d.put("decision",fields.get("decision"));if(fields.get("reason_code")!=null)d.put("reason_code",fields.get("reason_code"));}
        if("payment".equals(action)) {d.put("chain_code",code(fields.get("chain_code"),false));d.put("expected_entry_code",code(fields.get("expected_entry_code"),false));d.put("payment_date",date(fields.get("payment_date"),false));d.put("amount",signedMoney(fields.get("amount")));}
        return d;
    }
    private static String reviewReason(Auth.Session session,Map<String,Object> context,Map<String,Object> row,Map<String,Object> body) throws Exception {
        Map<String,Object> descriptor=Map.of("case_code",row.get("case_code"),"case_version",version(row),"payload_digest",digest(canonical(row.get("payload"))),"decision",body.get("decision"));
        Object note=proof(body,"reason_note","reason_reference");String source=text(row,"case_code"),org=text(context,"organization_code");long project=integer(context.get("project_id"),false);
        if(note!=null)return DeliverySettlementEvidence.register(session,project,org,source,"CASE-RETURN-REASON",descriptor,note);
        String existing=code(body.get("reason_code"),false);DeliverySettlementEvidence.resolve(existing,project,org,source,"CASE-RETURN-REASON",descriptor);return existing;
    }
    private static Map<String,Object> source(Auth.Session session,long project,String resource) throws Exception {
        OrganizationAccessStore.person(session);
        List<Map<String,Object>> rows=Db.query("SELECT p.id,p.status,p.demand_id AS project_demand,a.demand_id,a.data_revision AS accepted_revision,a.team_code,a.actor_code,a.created_at,w.organization_code,w.data_revision,w.version,w.draft FROM projects p JOIN workflow_acceptances a ON a.project_id=p.id JOIN workflow_demands w ON w.demand_id=a.demand_id WHERE p.id=?",project);
        if(rows.size()!=1)fail(409,"项目缺少唯一可信组织受理来源");Map<String,Object> r=rows.get(0);
        if(integer(r.get("project_demand"),false)!=integer(r.get("demand_id"),false)||integer(r.get("accepted_revision"),false)!=integer(r.get("data_revision"),false)||!Boolean.FALSE.equals(r.get("draft")))fail(409,"项目需求与受理来源不一致");
        String org=code(r.get("organization_code"),false);authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,org);if(resource!=null)authorize(session,resource,OrganizationAccess.Action.HANDLE,org);
        if(resource!=null&&closed(text(r,"status")))fail(409,"项目已经归档或关闭，须先经授权重新开启后办理财务变更");
        Map<String,Object> c=new LinkedHashMap<>();c.put("project_id",project);c.put("organization_code",org);c.put("demand_id",integer(r.get("demand_id"),false));c.put("data_revision",integer(r.get("data_revision"),false));c.put("workflow_version",integer(r.get("version"),false));c.put("team_code",code(r.get("team_code"),false));c.put("accepted_by",code(r.get("actor_code"),false));c.put("accepted_at",Instant.parse(text(r,"created_at")).toString());return c;
    }
    private static Map<String,Object> chain(Auth.Session session,Map<String,Object> context,String chainCode,String expected) throws Exception {
        Map<String,Object> row=Db.one("SELECT project_id,organization_code FROM m05_financial_chains WHERE chain_code=?",chainCode);if(row==null)fail(409,"更正或支付所依据的财务链不存在");
        if(integer(row.get("project_id"),false)!=integer(context.get("project_id"),false)||!Objects.equals(row.get("organization_code"),context.get("organization_code")))fail(403,"财务链不属于本项目和机构");
        authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,text(context,"organization_code"));
        Map<String,Object> h=DeliverySettlementLedger.head(chainCode);if(h==null||!Objects.equals(expected,h.get("current_entry_code")))fail(409,"财务依据已更新，请刷新后重新申报审核");
        if(!Objects.equals(map(h.get("total")).get("organization_code"),context.get("organization_code")))fail(409,"财务链组织依据不一致");return h;
    }
    private static void binding(Map<String,Object> context,Map<String,Object> stored) throws Exception {if(stored==null||!canonical(context).equals(canonical(map(stored.get("context")))))fail(409,"事项项目、机构或受理来源已变化，请核对后重新建立事项");}
    private static void authorize(Auth.Session s,String resource,OrganizationAccess.Action action,String org) throws Exception {if(!OrganizationAccessStore.authorize(s,resource,action,org).allowed())fail(403,"没有本机构办理本操作的权限");}
    private static void persist(Map<String,Object> old,Map<String,Object> next,OrganizationAccess.Person actor,long account) throws Exception {
        String payload=Json.write(next);if(payload.length()>160000)fail(400,"独立事项内容超过单次容量");
        if(old==null)Db.exec("INSERT INTO m05_cases VALUES(?,?,?,?,?,?,?,?)",next.get("case_code"),next.get("project_id"),next.get("organization_code"),version(next),next.get("state"),next.get("kind"),next.get("confirmed_origin"),payload);
        else cas("UPDATE m05_cases SET version=?,state=?,confirmed_origin=?,payload=? WHERE case_code=? AND version=?",version(next),next.get("state"),next.get("confirmed_origin"),payload,next.get("case_code"),version(old));
        Db.exec("INSERT INTO m05_case_revisions VALUES(?,?,?,?,?,?)",next.get("case_code"),version(next),payload,actor.personCode(),account,map(next.get("last_event")).get("created_at"));
    }
    private static Map<String,Object> checked(String code) throws Exception {
        Map<String,Object> row=Db.one("SELECT * FROM m05_cases WHERE case_code=?",code);if(row==null)return null;
        Map<String,Object> extent=Db.one("SELECT COUNT(*) AS n,COALESCE(SUM(CHAR_LENGTH(payload)),0) AS chars FROM m05_case_revisions WHERE case_code=?",code);
        if(integer(extent.get("n"),true)>10000||integer(extent.get("chars"),true)>16L*1024*1024)fail(409,"事项审计历史超过核验容量");
        try {
            Map<String,Object> prior=null;
            for(Map<String,Object> revision:Db.query("SELECT * FROM m05_case_revisions WHERE case_code=? ORDER BY version",code)) {
                Map<String,Object> p=json(revision.get("payload")),event=map(p.get("last_event"));
                if(version(p)!=version(prior)+1||version(p)!=integer(revision.get("version"),false)||!code.equals(p.get("case_code"))||!STATES.contains(p.get("state"))||!KINDS.contains(p.get("kind")))fail(409,HISTORY_ERROR);
                if(integer(p.get("project_id"),false)!=integer(row.get("project_id"),false)||!Objects.equals(p.get("organization_code"),row.get("organization_code"))||!Objects.equals(p.get("kind"),row.get("kind")))fail(409,HISTORY_ERROR);
                if(!Objects.equals(event.get("actor_code"),revision.get("actor_code"))||integer(event.get("account_id"),false)!=integer(revision.get("account_id"),false)||!Objects.equals(event.get("created_at"),revision.get("created_at")))fail(409,HISTORY_ERROR);
                code(event.get("actor_code"),false);Instant at=Instant.parse(text(event,"created_at"));if(prior!=null&&at.isBefore(Instant.parse(text(map(prior.get("last_event")),"created_at"))))fail(409,HISTORY_ERROR);
                if(!canonical(parsePayload(text(p,"kind"),map(p.get("payload")))).equals(canonical(p.get("payload"))))fail(409,HISTORY_ERROR);
                bindPayload(null,map(p.get("context")),code,text(p,"kind"),map(p.get("payload")),Map.of(),false);
                transition(prior,p);validateEventEvidence(prior,p,event);prior=p;
            }
            Map<String,Object> payload=json(row.get("payload"));
            if(prior==null||version(prior)!=integer(row.get("version"),false)||!Objects.equals(payload.get("state"),row.get("state"))||!Objects.equals(payload.get("confirmed_origin"),row.get("confirmed_origin"))||!canonical(prior).equals(canonical(payload)))fail(409,HISTORY_ERROR);
            return payload;
        } catch(Api.ApiException|RuntimeException invalid) {throw new Api.ApiException(409,HISTORY_ERROR);}
    }
    private static void transition(Map<String,Object> prior,Map<String,Object> next) throws Exception {
        String op=text(map(next.get("last_event")),"operation"),before=prior==null?"NONE":text(prior,"state"),after=text(next,"state");
        boolean valid=switch(op) {
            case "save" -> Set.of("NONE","DRAFT","RETURNED","APPROVED").contains(before)&&"DRAFT".equals(after)&&next.get("submission")==null&&next.get("review")==null&&next.get("confirmation")==null;
            case "submit" -> Set.of("DRAFT","RETURNED").contains(before)&&"SUBMITTED".equals(after)&&next.get("submission")!=null&&next.get("review")==null;
            case "review" -> "SUBMITTED".equals(before)&&Set.of("APPROVED","RETURNED").contains(after)&&next.get("review")!=null;
            case "confirm" -> "APPROVED".equals(before)&&"CONFIRMED".equals(after)&&next.get("confirmation")!=null;
            case "payment" -> "CONFIRMED".equals(before)&&"CONFIRMED".equals(after)&&map(next.get("last_event")).get("payment_entry")!=null;
            case "withdraw" -> Set.of("DRAFT","RETURNED").contains(before)&&"WITHDRAWN".equals(after)&&next.get("withdrawal")!=null&&next.get("confirmation")==null;
            default -> false;
        };
        if(!valid)fail(409,HISTORY_ERROR);
        if(!"WITHDRAWN".equals(after)&&next.get("withdrawal")!=null)fail(409,HISTORY_ERROR);
        if("WITHDRAWN".equals(after)&&next.get("confirmed_origin")!=null)fail(409,HISTORY_ERROR);
        if(prior!=null) {
            if(!canonical(prior.get("context")).equals(canonical(next.get("context"))))fail(409,HISTORY_ERROR);
            if(!op.equals("save")&&!canonical(prior.get("payload")).equals(canonical(next.get("payload"))))fail(409,HISTORY_ERROR);
            if(!Set.of("save","submit").contains(op)&&!canonical(prior.get("submission")).equals(canonical(next.get("submission"))))fail(409,HISTORY_ERROR);
            if(!Set.of("save","submit","review").contains(op)&&!canonical(prior.get("review")).equals(canonical(next.get("review"))))fail(409,HISTORY_ERROR);
            if(op.equals("payment")&&!canonical(prior.get("confirmation")).equals(canonical(next.get("confirmation"))))fail(409,HISTORY_ERROR);
        }
        for(String key:List.of("submission","review","confirmation","withdrawal"))if(next.get(key)!=null) {Map<String,Object> a=map(next.get(key));code(a.get("actor_code"),false);integer(a.get("account_id"),false);Instant.parse(text(a,"created_at"));code(a.get("evidence_code"),false);}
        if("WITHDRAWN".equals(after)&&!Objects.equals(map(next.get("withdrawal")).get("reason_code"),map(next.get("withdrawal")).get("evidence_code")))fail(409,HISTORY_ERROR);
        if("APPROVED".equals(after)&&!"APPROVE".equals(map(next.get("review")).get("decision")))fail(409,HISTORY_ERROR);
        if("RETURNED".equals(after)&&(!"RETURN".equals(map(next.get("review")).get("decision"))||map(next.get("review")).get("reason_code")==null))fail(409,HISTORY_ERROR);
    }
    private static Map<String,Object> view(Auth.Session session,Map<String,Object> context,Map<String,Object> row) throws Exception {
        Map<String,Object> out=copy(row);out.remove("context");out.remove("confirmed_origin");List<String> missing=new ArrayList<>();out.put("calculation_preview",null);
        if(!Set.of("CONFIRMED","WITHDRAWN").contains(row.get("state"))) {
            try {Plan plan=evaluate(session,context,row,row.get("review")==null?null:map(row.get("review")));missing.addAll(plan.issues);Map<String,Object> preview=new LinkedHashMap<>();preview.put("confirmed",false);preview.put("conditional",!plan.issues.isEmpty());preview.put("totals",plan.totals);preview.put("basis",plan.basis);preview.put("issues",plan.issues);out.put("calculation_preview",preview);}
            catch(Api.ApiException unavailable){missing.add(unavailable.getMessage());}
        }
        out.put("missing_items",missing);List<Map<String,Object>> financial=new ArrayList<>();
        for(String chain:linkedChains(row)){Map<String,Object> h=DeliverySettlementLedger.balance(chain);if(h==null)fail(409,HISTORY_ERROR);financial.add(Map.of("chain",h,"accrual_entries",DeliverySettlementLedger.entries("m05_financial_entries",chain),"payment_entries",DeliverySettlementLedger.entries("m05_payment_events",chain)));}
        out.put("financial",financial);out.put("capabilities",capabilities(session,context,row));out.put("audit",Db.query("SELECT version,actor_code,account_id,created_at FROM m05_case_revisions WHERE case_code=? ORDER BY version",row.get("case_code")));
        Set<String> references=new LinkedHashSet<>();collectEvidence(row,references);collectEvidence(financial,references);
        Map<String,Object> readable=new LinkedHashMap<>();List<String> ordered=new ArrayList<>(references);
        for(int i=0;i<ordered.size();i+=100)readable.putAll(DeliverySettlementEvidence.view(ordered.subList(i,Math.min(ordered.size(),i+100)),integer(context.get("project_id"),false),text(context,"organization_code")));
        out.put("evidence_records",readable);return out;
    }
    private static void validateEventEvidence(Map<String,Object> previous,Map<String,Object> row,Map<String,Object> event) throws Exception {
        String operation=text(event,"operation");if("save".equals(operation))return;
        String slot=switch(operation){case "submit"->"submission";case "review"->"review";case "confirm"->"confirmation";case "withdraw"->"withdrawal";default->"payment_proof";};
        Map<String,Object> a=operation.equals("payment")?map(event.get(slot)):map(row.get(slot));
        if(!Objects.equals(a.get("actor_code"),event.get("actor_code"))||integer(a.get("account_id"),false)!=integer(event.get("account_id"),false))fail(409,HISTORY_ERROR);
        Map<String,Object> proofAuthor=a,fields=a;String proofOperation=operation;Map<String,Object> descriptor;
        if(operation.equals("confirm")) {
            Map<String,Object> review=map(row.get("review"));proofAuthor=review;proofOperation="review";
            for(String key:List.of("evidence_code","evidence_kind","evidence_descriptor"))if(!canonical(a.get(key)).equals(canonical(review.get(key))))fail(409,HISTORY_ERROR);
            descriptor=map(review.get("evidence_descriptor"));
        } else {
            if(operation.equals("payment")) {
                Map<String,Object> payment=map(event.get("payment_entry"));fields=new LinkedHashMap<>();
                fields.put("chain_code",payment.get("chain_code"));Object entries=payment.get("accrual_entry_codes");
                if(!(entries instanceof List<?> list)||list.size()!=1)fail(409,HISTORY_ERROR);
                fields.put("expected_entry_code",((List<?>)entries).get(0));fields.put("payment_date",payment.get("payment_date"));fields.put("amount",payment.get("amount"));
                if(!Objects.equals(payment.get("evidence_code"),a.get("evidence_code"))||!Objects.equals(payment.get("actor_code"),a.get("actor_code"))||integer(payment.get("account_id"),false)!=integer(a.get("account_id"),false))fail(409,HISTORY_ERROR);
            }
            descriptor=actionDescriptor(previous,operation,fields);
        }
        String kind="CASE-"+proofOperation;
        if(!kind.equals(a.get("evidence_kind"))||!canonical(descriptor).equals(canonical(a.get("evidence_descriptor"))))fail(409,HISTORY_ERROR);
        Map<String,Object> registered=DeliverySettlementEvidence.resolve(code(a.get("evidence_code"),false),integer(row.get("project_id"),false),text(row,"organization_code"),text(row,"case_code"),kind,descriptor);
        if(!Objects.equals(registered.get("actor_code"),proofAuthor.get("actor_code"))||integer(registered.get("account_id"),false)!=integer(proofAuthor.get("account_id"),false)
                ||Instant.parse(text(registered,"created_at")).isAfter(Instant.parse(text(proofAuthor,"created_at"))))fail(409,HISTORY_ERROR);
        if(operation.equals("review")&&a.get("reason_code")!=null) {
            Map<String,Object> reasonDescriptor=Map.of("case_code",row.get("case_code"),"case_version",version(previous),"payload_digest",digest(canonical(row.get("payload"))),"decision",a.get("decision"));
            Map<String,Object> reason=DeliverySettlementEvidence.resolve(text(a,"reason_code"),integer(row.get("project_id"),false),text(row,"organization_code"),text(row,"case_code"),"CASE-RETURN-REASON",reasonDescriptor);
            if(!Objects.equals(reason.get("actor_code"),a.get("actor_code"))||integer(reason.get("account_id"),false)!=integer(a.get("account_id"),false))fail(409,HISTORY_ERROR);
        }
    }
    private static void collectEvidence(Object raw,Set<String> references) throws Exception {
        if(raw instanceof Map<?,?>)for(var e:map(raw).entrySet()) {
            if(Set.of("evidence_code","reason_code","source_evidence","original_approval_evidence").contains(e.getKey())&&e.getValue()!=null&&e.getValue().toString().startsWith("M05-EV-"))references.add(code(e.getValue(),false));
            else if(e.getKey().equals("evidence")&&e.getValue() instanceof Map<?,?>) {for(Object value:map(e.getValue()).values())if(value!=null)references.add(code(value,false));}
            else if(!Set.of("evidence_descriptor","context","approved_plan","plan").contains(e.getKey()))collectEvidence(e.getValue(),references);
        } else if(raw instanceof List<?> list)for(Object value:list)collectEvidence(value,references);
    }
    private static Map<String,Object> capabilities(Auth.Session session,Map<String,Object> context,Map<String,Object> row) throws Exception {
        String state=row==null?"NONE":text(row,"state"),kind=row==null?null:text(row,"kind"),org=text(context,"organization_code");Map<String,Object> out=new LinkedHashMap<>();
        Map<String,Object> project=Db.one("SELECT status FROM projects WHERE id=?",context.get("project_id"));boolean writable=project!=null&&!closed(text(project,"status"));
        for(String op:List.of("save","submit","review","confirm","payment")) {
            boolean stateAllowed=switch(op){case "save"->Set.of("NONE","DRAFT","RETURNED","APPROVED").contains(state);case "submit"->Set.of("DRAFT","RETURNED").contains(state);case "review"->"SUBMITTED".equals(state);case "confirm"->"APPROVED".equals(state);default->"CONFIRMED".equals(state);};
            boolean grant=OrganizationAccessStore.authorize(session,resource(op),OrganizationAccess.Action.HANDLE,org).allowed();
            if(op.equals("confirm")&&"ADJUSTMENT".equals(kind))grant&=OrganizationAccessStore.authorize(session,"settlement.correct",OrganizationAccess.Action.HANDLE,org).allowed();
            if(op.equals("confirm")&&"MIGRATION".equals(kind)&&map(row.get("payload")).get("historical_payment")!=null)grant&=OrganizationAccessStore.authorize(session,"settlement.pay",OrganizationAccess.Action.HANDLE,org).allowed();
            out.put(op,writable&&stateAllowed&&grant);
        }
        out.put("can_withdraw",writable&&Set.of("DRAFT","RETURNED").contains(state)&&OrganizationAccessStore.authorize(session,"settlement.submit",OrganizationAccess.Action.HANDLE,org).allowed());
        return out;
    }
    private static List<String> linkedChains(Map<String,Object> row) throws Exception {if(row==null||row.get("confirmation")==null)return List.of();List<String> out=new ArrayList<>();for(Map<String,Object> h:objectList(map(row.get("confirmation")).get("chains")))out.add(code(h.get("chain_code"),false));return out;}
    private static Map<String,Object> total(Map<String,Object> context,Map<String,Object> row,long teacher,String service,String activity,String amount,Map<String,Object> hours,String policy,String rate) {
        Map<String,Object> t=new LinkedHashMap<>();t.put("activity",activity);t.put("source_record_id",null);t.put("source_fact_revision",null);t.put("source_code",row.get("case_code"));t.put("service_date",service);t.put("organization_code",context.get("organization_code"));t.put("teacher_id",teacher);t.put("teacher_code",null);t.put("system_teacher_code","TEACHER-"+teacher);t.put("course_id",null);t.put("course_code",null);t.put("amount",amount);t.put("hours",hours);t.put("policy_version",policy);t.put("rate_version",rate);t.put("payroll_month",payroll(service));return t;
    }
    private static String payroll(String service){return YearMonth.from(LocalDate.parse(service)).plusMonths(1).toString();}
    private static boolean closed(String status){return status!=null&&Set.of("archived","closed","cancelled","canceled","ARCHIVED","CLOSED","CANCELLED","CANCELED","已归档","已取消").contains(status);}
    private static Map<String,Object> executionSettings(){return Map.of("version","M05-MONEY-USER-20260923","evidence_code","USER-20260923-MONEY-LINE-2DP-HALF-UP","amount_scale",2,"rounding_mode","HALF_UP","allow_unpaid_correction",true);}
    private static String caseChain(String key,long teacher){return "CASE-"+digest(key).substring(0,40)+"-T-"+teacher;}
    private static void milestone(Map<String,Object> p,String key,String earliest,String label,List<String> issues) throws Exception {if(p.get(key)==null){issues.add("缺少"+label+"的实际日期");return;}LocalDate day=LocalDate.parse(text(p,key));if(day.isAfter(today()))issues.add(label+"日期不能为未来日期");if(earliest!=null&&day.isBefore(LocalDate.parse(earliest)))issues.add(label+"日期不能早于前序实际里程碑");}
    private static LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
    private static void teacher(Object id,List<String> issues) throws Exception {if(Db.one("SELECT id FROM teachers WHERE id=?",integer(id,false))==null)issues.add("开发或迁移的讲师不存在");}
    private static void need(Map<String,Object> p,String key,String label,List<String> issues){if(p.get(key)==null)issues.add("缺少"+label(label));}
    private static void requiredEvidence(Map<String,Object> e,String key,String label,List<String> issues){if(e.get(key)==null)issues.add("缺少"+label(label)+"的核实说明和凭证");}
    private static String label(String key){return switch(key){case "activity"->"开发方式";case "service_date"->"业务发生日期";case "deliverable_code"->"开发成果编号";case "deliverable_version"->"成果版本";case "lead_teacher_id"->"主负责人";case "payable_hours"->"计酬课时";case "completed_on"->"完成日期";case "legacy_source_code"->"原记录编号";case "legacy_rule_code"->"原课酬标准";case "teacher_id"->"讲师";case "amount"->"原应付金额";case "source_evidence"->"原始台账依据";case "original_approval_evidence"->"原审批依据";case "chain_code"->"财务事项";case "expected_entry_code"->"当前财务依据";case "reason_code"->"办理原因说明";case "evidence_code"->"本次办理依据";case "dispatch_id"->"授课记录";case "research_team"->"研发团队身份";case "annual_plan"->"年度培训计划";case "customer_paid"->"客户付费";case "customer_written_payment_agreement"->"客户书面付款约定";case "customer_acceptance"->"客户验收";case "company_need"->"公司开发需要";case "annual_review_passed"->"年度评审";case "company_approval"->"公司核准";case "previous_grant"->"开发课酬此前未计发";default->key;};}
    private static void ready(Plan p) throws Exception {if(!p.issues.isEmpty())fail(409,"事项尚不能核准："+String.join("；",new LinkedHashSet<>(p.issues)));if(p.totals.isEmpty()||p.totals.size()!=p.chains.size())fail(409,"事项缺少完整财务依据");}
    private static Truth truth(Map<String,Object> p,String key){return Truth.valueOf((String)p.get(key));}
    private static Map<String,Object> attestation(OrganizationAccess.Person actor,long account,String evidence){Map<String,Object> a=new LinkedHashMap<>();a.put("actor_code",actor.personCode());a.put("account_id",account);a.put("created_at",Instant.now().toString());if(evidence!=null)a.put("evidence_code",evidence);return a;}
    private static void requireState(Map<String,Object> row,Set<String> states) throws Exception {if(row==null||!states.contains(row.get("state")))fail(409,"事项当前状态不允许此操作");}
    private static String resource(String op){return switch(op){case "save","submit","withdraw"->"settlement.submit";case "review"->"settlement.review";case "payment"->"settlement.pay";default->"settlement.confirm";};}
    private static void requestFields(String op,Map<String,Object> b) throws Exception {
        Set<String> base=new HashSet<>(Set.of("project_id","case_code","expected_version","request_id")),required=new HashSet<>(base);
        Set<String> extra=switch(op){case "save"->Set.of("kind","payload");case "submit"->Set.of("evidence_code","evidence_note","evidence_reference");case "review"->Set.of("decision","evidence_code","evidence_note","evidence_reference","reason_code","reason_note","reason_reference");case "payment"->Set.of("chain_code","expected_entry_code","payment_date","amount","evidence_code","evidence_note","evidence_reference");case "withdraw"->Set.of("reason_note","reason_reference");default->Set.of();};base.addAll(extra);required.addAll(extra);required.removeAll(Set.of("evidence_code","evidence_note","evidence_reference","reason_code","reason_note","reason_reference"));if(op.equals("withdraw"))required.add("reason_note");whitelist(b,base);if(!b.keySet().containsAll(required))fail(400,"请求缺少必需控制字段");
    }
    private static Map<String,Object> emptyHours(){Map<String,Object> h=new LinkedHashMap<>();for(String k:List.of("ESTIMATED","PLANNED","ACTUAL","PAYABLE"))h.put(k,null);return h;}
    private static Map<String,Object> hours(Object raw) throws Exception {Map<String,Object> out=emptyHours();if(raw==null)return out;Map<String,Object> h=map(raw);whitelist(h,HOURS);for(String k:HOURS)out.put(k,plain(decimal(h.get(k),true)));return out;}
    private static BigDecimal decimal(Object raw,boolean optional) throws Exception {if(raw==null&&optional)return null;if(!(raw instanceof String))fail(400,"课时与百分比须为明确十进制字符串");return DeliverySettlement.decimalInput(raw);}
    private static String plain(BigDecimal value){return value==null?null:value.toPlainString();}
    private static BigDecimal nonnegativeMoney(Object raw) throws Exception {BigDecimal amount=DeliverySettlementLedger.money(raw);if(amount.signum()<0)fail(400,"应付或分配金额不能为负");return amount;}
    private static String signedMoney(Object raw) throws Exception {return DeliverySettlementLedger.money(raw).toPlainString();}
    private static String businessReference(Object value,int max) throws Exception {if(value==null)return null;if(!(value instanceof String s)||s.isBlank()||s.length()>max||s.chars().anyMatch(c->c<32))fail(400,"来源或原规则标识须为清晰的实际文字或业务编号");return value.toString().trim();}
    private static String legacyRule(String reference){return "LEGACY-RULE-"+digest(reference);}
    private static String date(Object raw,boolean optional) throws Exception {if(raw==null&&optional)return null;if(!(raw instanceof String s)||!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))fail(400,"日期须为YYYY-MM-DD");try{return LocalDate.parse(raw.toString()).toString();}catch(DateTimeException bad){throw new Api.ApiException(400,"日期无效");}}
    private static String code(Object raw,boolean optional) throws Exception {if(raw==null&&optional)return null;if(!(raw instanceof String s)||!s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}"))fail(400,"需要不超过96字符的有效技术编码");return raw.toString();}
    private static String enumValue(Object raw,Set<String> values,boolean optional) throws Exception {if(raw==null&&optional)return null;if(!(raw instanceof String)||!values.contains(raw))fail(400,"枚举字段值无效");return raw.toString();}
    private static long integer(Object raw,boolean zero) throws Exception {try {if(!(raw instanceof Number)&&!(raw instanceof String s&&s.matches("[0-9]{1,16}")))throw new NumberFormatException();BigDecimal value=new BigDecimal(raw.toString());long n=value.longValueExact();if(n<(zero?0:1)||n>9007199254740991L)throw new NumberFormatException();return n;}catch(ArithmeticException|NumberFormatException bad){throw new Api.ApiException(400,"编号或版本须为安全整数");}}
    private static long version(Map<String,Object> p) throws Exception {return p==null?0:integer(p.get("version"),false);}
    private static String text(Map<String,Object> p,String key){Object value=p.get(key);return value==null?null:value.toString();}
    private static void method(HttpExchange ex,String wanted) throws Exception {if(!wanted.equalsIgnoreCase(ex.getRequestMethod()))fail(405,"请求方法不支持");}
    private static void whitelist(Map<String,Object> m,Set<String> allowed) throws Exception {for(String key:m.keySet())if(!allowed.contains(key))fail(400,"存在不接受的字段");}
    private static void exact(Map<String,Object> m,Set<String> fields) throws Exception {whitelist(m,fields);if(!m.keySet().equals(fields))fail(400,"对象缺少必需字段");}
    private static Map<String,Object> map(Object raw) throws Exception {if(!(raw instanceof Map<?,?>))fail(400,"需要JSON对象");Map<String,Object> out=new LinkedHashMap<>();for(var e:((Map<?,?>)raw).entrySet()){if(!(e.getKey() instanceof String))fail(400,"字段须为字符串");out.put((String)e.getKey(),e.getValue());}return out;}
    private static List<Map<String,Object>> objectList(Object raw) throws Exception {if(raw==null)return List.of();if(!(raw instanceof List<?>))fail(400,"需要对象数组");List<Map<String,Object>> out=new ArrayList<>();for(Object o:(List<?>)raw)out.add(map(o));return out;}
    private static Map<String,Object> json(Object raw) throws Exception {return map(Json.parse(raw.toString()));}
    private static Map<String,Object> copy(Map<String,Object> p) throws Exception {return json(Json.write(p));}
    private static String canonical(Object raw) throws Exception {return Json.write(sorted(raw));}
    private static Object sorted(Object raw) throws Exception {if(raw instanceof Map<?,?>){Map<String,Object> sorted=new TreeMap<>();for(var e:map(raw).entrySet())sorted.put(e.getKey(),sorted(e.getValue()));return sorted;}if(raw instanceof List<?> l){List<Object> out=new ArrayList<>();for(Object v:l)out.add(sorted(v));return out;}if(raw instanceof Number n)return new BigDecimal(n.toString()).stripTrailingZeros();return raw;}
    private static String digest(String s){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static void cas(String sql,Object... values) throws Exception {try(PreparedStatement p=Db.get().prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);if(p.executeUpdate()!=1)fail(409,"事项版本已变化");}}
    private static void fail(int status,String message) throws Api.ApiException {throw new Api.ApiException(status,message);}
}
