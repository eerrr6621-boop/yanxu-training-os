package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;
import static com.training.SurveySummaryImportsPolicy.*;

/** Append-only, aggregate-only survey review ledger. No implicit policy or reviewer grants. */
public final class SurveySummaryImportsFormal {
    private SurveySummaryImportsFormal() {}
    public static final String BASE="/api/survey-results";
    public static final String SOURCE_FORMAT="M07-REVIEWED-SOURCE-1";
    private static final String FORMAT="M07-LEDGER-1", GENESIS="0".repeat(64);
    private static final int MAX_EVENTS=1000, MAX_BYTES=16*1024*1024, MAX_TICKETS=128;
    private static final long TTL=300000, MAX_ID=9007199254740991L;
    private static final Map<String,Ticket> TICKETS=new LinkedHashMap<>();
    private static final Set<String> EDITABLE=Set.of("待启动","进行中","已完成");
    private static final String APPROVED_POLICY="M07-APPROVED-20260924-V1";
    private static final String MANAGEMENT_ROLE=OrganizationManagementGroupPlan.ROLE;

    public static void init() throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            standalone();
            Db.exec("CREATE TABLE IF NOT EXISTS m07_policy_revisions(organization_code VARCHAR(120) NOT NULL,version_code VARCHAR(96) NOT NULL,payload CLOB NOT NULL,payload_hash VARCHAR(64) NOT NULL,actor_code VARCHAR(120) NOT NULL,account_id BIGINT NOT NULL,config_hash VARCHAR(64) NOT NULL,created_at VARCHAR(40) NOT NULL,PRIMARY KEY(organization_code,version_code))");
            Db.exec("CREATE TABLE IF NOT EXISTS m07_policy_heads(organization_code VARCHAR(120) PRIMARY KEY,version_code VARCHAR(96) NOT NULL,FOREIGN KEY(organization_code,version_code) REFERENCES m07_policy_revisions(organization_code,version_code))");
            Db.exec("CREATE TABLE IF NOT EXISTS m07_result_heads(project_id BIGINT PRIMARY KEY REFERENCES projects(id),demand_id BIGINT NOT NULL REFERENCES workflow_demands(demand_id),organization_code VARCHAR(120) NOT NULL,version BIGINT NOT NULL,head_hash VARCHAR(64) NOT NULL)");
            Db.exec("CREATE TABLE IF NOT EXISTS m07_result_events(project_id BIGINT NOT NULL REFERENCES m07_result_heads(project_id),version BIGINT NOT NULL,event_json CLOB NOT NULL,event_hash VARCHAR(64) NOT NULL,PRIMARY KEY(project_id,version))");
            Db.exec("CREATE TABLE IF NOT EXISTS m07_file_claims(project_id BIGINT NOT NULL,file_hash VARCHAR(64) NOT NULL,policy_hash VARCHAR(64) NOT NULL,import_version BIGINT NOT NULL,PRIMARY KEY(project_id,file_hash,policy_hash),FOREIGN KEY(project_id,import_version) REFERENCES m07_result_events(project_id,version))");
            Db.exec("CREATE TABLE IF NOT EXISTS m07_result_requests(account_id BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,actor_code VARCHAR(120) NOT NULL,project_id BIGINT NOT NULL,payload_hash VARCHAR(64) NOT NULL,result_version BIGINT NOT NULL,PRIMARY KEY(account_id,request_id),FOREIGN KEY(project_id,result_version) REFERENCES m07_result_events(project_id,version))");
        }
    }

    /** Trusted host policy publication; the administrator HTTP entry supplies only the approved fixed policy. */
    public static Map<String,Object> configure(Auth.Session s,String org,String expected,Policy policy) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            standalone(); PersonContext actor=person(s); permission(s,"survey.policy",OrganizationAccess.Action.HANDLE,org);
            if(policy==null||!policy.confirmed())throw error(409,"正式统计口径尚未确认");
            OrganizationAccess.Configuration identity=OrganizationAccessStore.configuration();
            if(!identity.roleCodes().containsAll(policy.reviewerRoles()))throw error(409,"复核岗位尚未存在于可信权限配置");
            String previous=policyVersion(org);
            if(!Objects.equals(previous,expected))throw stale();
            return Db.transaction(()->{
                if(Db.one("SELECT version_code FROM m07_policy_revisions WHERE organization_code=? AND version_code=?",org,policy.version())!=null)throw error(409,"统计口径版本已使用，不能覆写历史");
                Db.exec("INSERT INTO m07_policy_revisions VALUES(?,?,?,?,?,?,?,?)",org,policy.version(),canonical(policy.toMap()),digestPolicy(policy),actor.actor(),actor.account(),actor.config(),Instant.now().toString());
                if(previous==null)Db.exec("INSERT INTO m07_policy_heads VALUES(?,?)",org,policy.version());
                else update("UPDATE m07_policy_heads SET version_code=? WHERE organization_code=? AND version_code=?",policy.version(),org,previous);
                if(!actor.equals(person(s)))throw stale(); permission(s,"survey.policy",OrganizationAccess.Action.HANDLE,org);
                return map("organization_code",org,"policy",policy.toMap(),"policy_hash",digestPolicy(policy));
            });
        }
    }

    /** Administrator-only projection; reads existing named-organization grants and never creates permissions. */
    public static Map<String,Object> policies(Auth.Session s) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            standalone();PersonContext actor=person(s);
            OrganizationAccess.Configuration identity=OrganizationAccessStore.configuration();
            List<Map<String,Object>> organizations=new ArrayList<>();
            for(OrganizationAccess.Organization org:identity.organizations()) {
                if(!org.enabled()||!policyAdministrator(s,identity,org.organizationCode()))continue;
                Policy current=policy(org.organizationCode());boolean matches=approvedPolicy(current);
                organizations.add(map("organization_code",org.organizationCode(),"organization_name",organizationName(org),
                    "current_version",current==null?null:current.version(),"published_at",policyPublishedAt(org.organizationCode(),current),
                    "matches_approved",matches,"can_publish",!matches,
                    "current_rule",current==null?null:map("label",matches?"已采用确认规则":"此前已发布规则","description",policyDescriptions(current))));
            }
            if(organizations.isEmpty())throw error(403,"没有可管理的问卷统计规则范围");
            if(!actor.equals(person(s)))throw stale();
            return map("format","M07_POLICY_WORKSPACE_1","synthetic",false,
                "approved_rule",map("version",APPROVED_POLICY,"label","已确认的满意度统计规则","description",policyDescriptions(approvedPolicy("display"))),
                "organizations",organizations,"publication_context",publicationContext(actor));
        }
    }

    /** Browser may acknowledge a fixed rule for one authorized organization, never provide rules or roles. */
    public static Map<String,Object> publishPolicy(Auth.Session s,Map<String,?> input) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            standalone();PersonContext actor=person(s);
            fields(input,Set.of("organization_code","expected_version","publication_context","acknowledged"));
            String org=text(input.get("organization_code"),120,false);
            OrganizationAccess.Configuration identity=OrganizationAccessStore.configuration();
            if(!policyAdministrator(s,identity,org))throw error(403,"没有该机构的问卷统计规则管理权限");
            if(!publicationContext(actor).equals(token(input.get("publication_context"))))throw stale();
            if(!Boolean.TRUE.equals(input.get("acknowledged")))throw error(400,"请确认已阅读本次统计规则及适用机构");
            String expected=input.get("expected_version")==null?null:text(input.get("expected_version"),96,false);
            Policy current=policy(org);
            if(!Objects.equals(current==null?null:current.version(),expected))throw stale();
            boolean alreadyCurrent=approvedPolicy(current);
            if(!alreadyCurrent) {
                current=approvedPolicy("M07-20260924-"+UUID.randomUUID());
                configure(s,org,expected,current);
            }
            return map("organization_code",org,"current_version",current.version(),
                "published_at",policyPublishedAt(org,current),"already_current",alreadyCurrent,"reload_required",true);
        }
    }

    private static Policy approvedPolicy(String version) {
        return new Policy(version,true,APPROVED_POLICY,true,Set.of(MANAGEMENT_ROLE));
    }
    private static boolean approvedPolicy(Policy policy) {
        return policy!=null&&policy.confirmed()&&policy.zeroValid()&&policy.evidenceRef().equals(APPROVED_POLICY)
            &&policy.reviewerRoles().equals(Set.of(MANAGEMENT_ROLE));
    }
    private static String publicationContext(PersonContext actor) {
        return hash(map("purpose","M07-POLICY-PUBLISH","account",actor.account(),"person",actor.actor(),
            "configuration",actor.config(),"approval",APPROVED_POLICY));
    }
    private static String organizationName(OrganizationAccess.Organization org) {
        return org.displayName()==null?org.organizationCode():org.displayName();
    }
    private static String policyPublishedAt(String org,Policy current) throws Exception {
        if(current==null)return null;
        Map<String,Object>row=Db.one("SELECT created_at FROM m07_policy_revisions WHERE organization_code=? AND version_code=?",org,current.version());
        if(row==null)throw corrupt();return timestamp(row.get("created_at"));
    }
    private static List<String> policyDescriptions(Policy policy) {
        return List.of(policy.zeroValid()?"评分范围为0–10分，0分有效。":"评分范围为0–10分，0分不计入有效评分。",
            "空白及异常评分不计入该题均分，每题按自己的有效评分数计算。",
            "平均分按四舍五入保留两位小数。",
            "每行答卷均保留，不按姓名合并或去重。",
            "同一项目不重复导入相同文件；口径变更后重新计算须走明确修订及复核。",
            policy.reviewerRoles().equals(Set.of(MANAGEMENT_ROLE))?
                "修正版须说明原因，由另一名教研团队或总经理室管理员复核后替换，旧版永久保留。":
                "修正版须说明原因，由此前政策指定的另一名获授权人员复核后替换，旧版永久保留。");
    }
    private static boolean policyAdministrator(Auth.Session s,OrganizationAccess.Configuration identity,String org) {
        OrganizationAccess.Decision decision=OrganizationAccessStore.authorize(s,"survey.policy",OrganizationAccess.Action.HANDLE,org);
        if(!decision.allowed()||identity==null||!identity.version().equals(decision.configurationVersion()))return false;
        // Preserve the approved management group's existing explicit branch scope, including every global DENY.
        return identity.grants().stream().anyMatch(g->decision.matchedRuleIds().contains(g.ruleId())
            &&g.roleCode().equals(MANAGEMENT_ROLE)&&g.resource().equals("survey.policy")
            &&g.action()==OrganizationAccess.Action.HANDLE&&g.effect()==OrganizationAccess.Effect.ALLOW
            &&g.scope()==OrganizationAccess.Scope.NAMED_ORGS&&g.organizationCodes().contains(org));
    }

    private record PersonContext(long account,String actor,String config) {}
    private record Context(PersonContext person, SurveySummaryImportsIntegration.Source source, Policy policy) {}
    private static final class Ticket {
        final Auth.Session session; final Context context; final long expected, replaces; final String head,reason,file,score,summary;
        long expires;
        Ticket(Auth.Session s,Context c,Ledger ledger,long replaces,String reason,String file,String score,Map<String,Object>summary) {
            session=s;context=c;expected=ledger.version;head=ledger.hash;this.replaces=replaces;this.reason=reason;this.file=file;this.score=score;
            this.summary=canonical(summary);expires=System.currentTimeMillis()+TTL;
        }
    }
    private static final class Import {
        final long id,series,replaces,account; final String actor,reason,file,score,at; final Policy policy; final Map<String,Object>summary,source;
        String decision="PENDING_REVIEW",reviewer="",reviewedAt="",reviewReason="";long reviewEvent,reviewAccount;
        Import(Map<String,Object> event,Map<String,Object> p) throws Exception {
            id=id(event.get("version"),false);series=id(p.get("series_id"),false);replaces=id(p.get("replaces_id"),true);
            account=id(event.get("account_id"),false);actor=text(event.get("actor_code"),120,false);at=timestamp(event.get("at"));
            policy=fromMap(p.get("policy"));summary=validateAggregate(p.get("summary"),policy);
            source=object(p.get("source"),Set.of("project_id","demand_id","organization_code","accepted_revision","workflow_version","team_code","actor_code","accepted_at"));
            reason=text(p.get("reason"),500,true);file=digest(p.get("file_hash"));score=optionalDigest(p.get("score_hash"));
        }
    }
    private static final class Ledger {
        long version;String hash=GENESIS;int bytes;final Map<Long,Import> imports=new LinkedHashMap<>();final Map<Long,Long> tips=new LinkedHashMap<>(),active=new LinkedHashMap<>();final Map<Long,String>eventHashes=new HashMap<>();
    }

    /** Upload parsing stays outside the shared lock; only aggregate data enters a short-lived confirmation ticket. */
    public static Map<String,Object> prepare(Auth.Session s,Map<String,?> input) throws Exception {
        return prepareParsed(s,input,null);
    }
    static Map<String,Object> prepareParsed(Auth.Session s,Map<String,?> input,Runnable checkpoint) throws Exception {
        return prepareParsed(s,input,checkpoint,null);
    }
    static Map<String,Object> prepareParsed(Auth.Session s,Map<String,?> input,Runnable checkpoint,
            java.util.function.Consumer<Map<String,Object>> stage) throws Exception {
        if(Thread.holdsLock(Api.MUTATION_LOCK))throw error(503,"正式评分预览需要在业务锁外处理");
        fields(input,Set.of("fileName","xlsxBase64","projectId","replacesId","reason"));
        long project=id(input.get("projectId"),false),replaces=id(input.get("replacesId"),true);String reason=text(input.get("reason"),500,true);
        Context before;long expected;String head;
        synchronized(Api.MUTATION_LOCK) {
            standalone();before=context(s,project,"survey.import",true);requirePolicy(before.policy());
            Ledger ledger=restore(before.source());target(ledger,replaces,reason);expected=ledger.version;head=ledger.hash;
        }
        Map<String,Object>upload=map("fileName",input.get("fileName"),"xlsxBase64",input.get("xlsxBase64"),"projectId",project);
        Map<String,Object>core=checkpoint==null?SurveySummaryImportsIntegration.parseForConfirmation(s,upload,before.policy().rules()):
                SurveySummaryImportsIntegration.parseWithBudget(s,upload,before.policy().rules(),checkpoint);
        synchronized(Api.MUTATION_LOCK) {
            standalone();revalidate(s,before,"survey.import",true);Ledger ledger=restore(before.source());
            if(ledger.version!=expected||!ledger.hash.equals(head))throw stale();target(ledger,replaces,reason);
            if(!"PREVIEW".equals(core.get("state")))return stage(map("state","ERROR","canConfirm",false,"preview",core),stage);
            if(!Boolean.FALSE.equals(core.get("synthetic")))throw error(409,"不能保存合成预览结果");
            Map<String,Object>summary=aggregate(core,before.policy());String file=digest(core.get("fileFingerprint")),score=optionalDigest(core.get("scoreFingerprint"));
            List<Long> exact=ledger.imports.values().stream().filter(i->i.file.equals(file)).map(i->i.id).toList();
            List<Long> candidates=score.isEmpty()?List.of():ledger.imports.values().stream().filter(i->i.score.equals(score)).map(i->i.id).toList();
            boolean recalculate=policyRecalculation(ledger,replaces,file,before.policy());
            if(!exact.isEmpty()&&!recalculate)return stage(map("state","DUPLICATE","canConfirm",false,"summary",summary,"duplicate",map("status","EXACT_FILE","import_ids",exact)),stage);
            sweep(); if(TICKETS.size()>=MAX_TICKETS)throw error(429,"待确认记录较多，请稍后重新预览");
            String token;do{token=Auth.randomToken(32);}while(TICKETS.containsKey(token));
            Ticket ticket=new Ticket(s,before,ledger,replaces,reason,file,score,summary);TICKETS.put(token,ticket);
            return stage(map("state","READY_TO_CONFIRM","canConfirm",true,"policyConfirmed",true,"summary",summary,"policy",before.policy().toMap(),
                    "project_id",project,"expected_version",expected,"replaces_id",replaces,"reason",reason,"preview_token",token,"expires_at",Instant.ofEpochMilli(ticket.expires).toString(),
                    "duplicate",map("status",recalculate?"EXPLICIT_POLICY_RECALCULATION":candidates.isEmpty()?"NONE":"SAME_SCORES_CANDIDATE","import_ids",recalculate?exact:candidates),"review_required",true),stage);
        }
    }

    public static Map<String,Object> confirm(Auth.Session s,Map<String,?> input) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            standalone();fields(input,Set.of("project_id","preview_token","request_id","acknowledged"));
            long project=id(input.get("project_id"),false);String token=token(input.get("preview_token")),request=code(input.get("request_id"));
            if(!Boolean.TRUE.equals(input.get("acknowledged")))throw error(400,"请明确确认本次汇总内容");
            Ticket ticket=TICKETS.get(token);
            // A mismatched session or project cannot consume a legitimate holder's token.
            if(ticket!=null&&(ticket.session!=s||ticket.context.source().id()!=project))throw error(403,"预览凭证不属于当前会话或项目");
            Context now;
            try {now=context(s,project,"survey.import",true);requirePolicy(now.policy());}
            catch(Exception invalid) {if(ticket!=null)TICKETS.remove(token);throw invalid;}
            String requestHash=hash(map("operation","CONFIRM","project_id",project,"token_hash",hash(token),"acknowledged",true));
            Map<String,Object>replay=replay(now,request,requestHash);if(replay!=null)return replay;
            if(ticket==null)throw error(409,"预览凭证已失效，请重新生成");
            if(ticket.expires<System.currentTimeMillis()||!ticket.context.equals(now)){TICKETS.remove(token);throw stale();}
            Ledger ledger;
            try {
                ledger=restore(now.source());
                if(ledger.version!=ticket.expected||!ledger.hash.equals(ticket.head))throw stale();
                target(ledger,ticket.replaces,ticket.reason);
            }catch(Exception invalid){TICKETS.remove(token);throw invalid;}
            if(ledger.imports.values().stream().anyMatch(i->i.file.equals(ticket.file))&&!policyRecalculation(ledger,ticket.replaces,ticket.file,now.policy())){TICKETS.remove(token);throw error(409,"该项目已保存相同文件");}
            long version=ledger.version+1,series=ticket.replaces==0?version:ledger.imports.get(ticket.replaces).series;
            Map<String,Object>payload=map("series_id",series,"replaces_id",ticket.replaces,"reason",ticket.reason,"file_hash",ticket.file,"score_hash",ticket.score,
                    "policy",now.policy().toMap(),"policy_hash",digestPolicy(now.policy()),"summary",Json.parse(ticket.summary),"source",sourceMap(now.source()));
            Map<String,Object>result=Db.transaction(()->{
                append(now,ledger,"IMPORT",payload,requestHash);Db.exec("INSERT INTO m07_file_claims VALUES(?,?,?,?)",project,ticket.file,digestPolicy(now.policy()),version);
                remember(now,request,requestHash,version);revalidate(s,now,"survey.import",true);
                return receipt(project,version,"PENDING_REVIEW",false);
            });
            TICKETS.remove(token);return result;
        }
    }

    public static Map<String,Object> review(Auth.Session s,Map<String,?> input) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            standalone();fields(input,Set.of("project_id","import_id","expected_version","decision","reason","request_id"));
            long project=id(input.get("project_id"),false),importId=id(input.get("import_id"),false),expected=id(input.get("expected_version"),true);
            String decision=text(input.get("decision"),16,false),reason=text(input.get("reason"),500,true),request=code(input.get("request_id"));
            if(!Set.of("APPROVE","REJECT").contains(decision))throw error(400,"请选择通过或退回");
            if(decision.equals("REJECT")&&reason.isBlank())throw error(400,"退回时请填写原因");
            Context ctx=context(s,project,"survey.review",true);requirePolicy(ctx.policy());reviewerRole(s,ctx.policy(),ctx.source().org());
            String requestHash=hash(map("operation","REVIEW","input",input));Map<String,Object>replay=replay(ctx,request,requestHash);if(replay!=null)return replay;
            Ledger ledger=restore(ctx.source());if(ledger.version!=expected)throw stale();
            Import item=ledger.imports.get(importId);if(item==null)throw error(404,"汇总记录不存在");
            if(item.account==ctx.person().account()||item.actor.equals(ctx.person().actor()))throw error(403,"须由另一名获授权人员复核");
            if(!item.decision.equals("PENDING_REVIEW")||!Objects.equals(ledger.tips.get(item.series),item.id))throw error(409,"该记录已复核或已被后续修订");
            if(!canonical(item.source).equals(canonical(sourceMap(ctx.source()))))throw error(409,"受理来源已变化，请重新核对汇总");
            if(decision.equals("APPROVE")&&!digestPolicy(item.policy).equals(digestPolicy(ctx.policy())))throw error(409,"统计口径已变化，请重新生成汇总");
            long version=ledger.version+1;
            return Db.transaction(()->{
                append(ctx,ledger,"REVIEW",map("import_id",importId,"decision",decision,"reason",reason,"policy_hash",digestPolicy(ctx.policy())),requestHash);
                remember(ctx,request,requestHash,version);revalidate(s,ctx,"survey.review",true);reviewerRole(s,ctx.policy(),ctx.source().org());
                return receipt(project,version,decision.equals("APPROVE")?"APPROVED":"REJECTED",false);
            });
        }
    }

    public static Map<String,Object> read(Auth.Session s,long project) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            standalone();Context ctx=context(s,project,"survey.read",false);Ledger ledger=restore(ctx.source());
            List<Map<String,Object>>records=new ArrayList<>();for(Import item:ledger.imports.values())records.add(view(item,ledger));
            Map<String,Object>out=map("project_id",project,"version",ledger.version,"records",records,"policy",ctx.policy()==null?null:ctx.policy().toMap(),
                "capabilities",map("prepare",ctx.policy()!=null&&ctx.policy().confirmed()&&allowed(s,"survey.import",OrganizationAccess.Action.HANDLE,ctx.source().org())&&allowed(s,"survey.preview",OrganizationAccess.Action.HANDLE,ctx.source().org())&&EDITABLE.contains(ctx.source().status()),
                    "review",ctx.policy()!=null&&ctx.policy().confirmed()&&eligibleRole(s,ctx.policy(),ctx.source().org())&&EDITABLE.contains(ctx.source().status())),"synthetic",false);
            revalidate(s,ctx,"survey.read",false);return out;
        }
    }

    public static boolean canReadReviewed(Auth.Session s,String org) {
        synchronized(Api.MUTATION_LOCK) {try{person(s);return allowed(s,"survey.read",OrganizationAccess.Action.VIEW,org);}catch(Exception denied){return false;}}
    }
    /** Also callable within M08's transaction: read-only, never starts or commits a transaction. */
    public static Map<String,Object> captureReviewed(Auth.Session s,long project,String expectedOrg) throws Exception {
        try { synchronized(Api.MUTATION_LOCK) {
            Context ctx=context(s,project,"survey.read",false);
            if(!ctx.source().org().equals(expectedOrg))throw error(403,"总结与评分来源机构不一致");
            Ledger ledger=restore(ctx.source());List<Map<String,Object>>items=new ArrayList<>();String reason="";
            if(ctx.policy()==null||!ctx.policy().confirmed())reason="POLICY_NOT_CONFIRMED";
            else for(long current:ledger.active.values()) {
                Import item=ledger.imports.get(current);
                if(!digestPolicy(item.policy).equals(digestPolicy(ctx.policy()))) {reason="POLICY_CHANGED_REVIEW_REQUIRED";break;}
                if(!canonical(item.source).equals(canonical(sourceMap(ctx.source())))) {reason="SOURCE_CHANGED_REVIEW_REQUIRED";break;}
                items.add(map("import_id",item.id,"series_id",item.series,"revision",item.id,"policy_version",item.policy.version(),"policy_hash",digestPolicy(item.policy),
                    "policy",item.policy.toMap(),"summary",item.summary,"review",map("person_code",item.reviewer,"reviewed_at",item.reviewedAt,"review_event_id",item.reviewEvent),
                    "source_digest",hash(map("source",item.source,"import_event_hash",ledger.eventHashes.get(item.id),"review_event_hash",ledger.eventHashes.get(item.reviewEvent)))));
            }
            if(reason.isEmpty()&&items.isEmpty())reason="NO_REVIEWED_RESULT";
            Map<String,Object>out=map("format",SOURCE_FORMAT,"status",reason.isEmpty()?"AVAILABLE":"UNAVAILABLE","reason",reason,
                "value",reason.isEmpty()?map("project_id",project,"organization_code",expectedOrg,"results",items):null);
            out.put("source_version",hash(out));revalidate(s,ctx,"survey.read",false);return out;
        }} catch(Api.ApiException known) { throw known; }
        catch(Exception unavailable) { throw error(503,"评分汇总来源暂时无法读取，请稍后重试"); }
    }

    /** Shape and checksum validation for stored M08 sources; does not substitute for live permissions/freshness. */
    public static void validateReviewedSnapshot(Object value) throws Exception {
        try { validateSnapshot(value); } catch(Exception invalid) { throw corrupt(); }
    }
    private static void validateSnapshot(Object value) throws Exception {
        Map<String,Object>m=object(value,Set.of("format","status","reason","value","source_version"));
        if(!SOURCE_FORMAT.equals(m.get("format")))throw corrupt();String checksum=digest(m.get("source_version"));Map<String,Object>base=new LinkedHashMap<>(m);base.remove("source_version");if(!checksum.equals(hash(base)))throw corrupt();
        if("UNAVAILABLE".equals(m.get("status"))) {
            if(m.get("value")!=null||!Set.of("POLICY_NOT_CONFIRMED","NO_REVIEWED_RESULT","POLICY_CHANGED_REVIEW_REQUIRED","SOURCE_CHANGED_REVIEW_REQUIRED").contains(m.get("reason")))throw corrupt();return;
        }
        if(!"AVAILABLE".equals(m.get("status"))||!"".equals(m.get("reason")))throw corrupt();
        Map<String,Object>v=object(m.get("value"),Set.of("project_id","organization_code","results"));id(v.get("project_id"),false);text(v.get("organization_code"),120,false);
        if(!(v.get("results")instanceof List<?>list)||list.isEmpty()||list.size()>MAX_EVENTS)throw corrupt();Set<Long>series=new HashSet<>(),imports=new HashSet<>(),reviewEvents=new HashSet<>();
        for(Object raw:list) {
            Map<String,Object>r=object(raw,Set.of("import_id","series_id","revision","policy_version","policy_hash","policy","summary","review","source_digest"));
            long record=id(r.get("import_id"),false),seriesId=id(r.get("series_id"),false);if(record!=id(r.get("revision"),false)||seriesId>record||!series.add(seriesId)||!imports.add(record))throw corrupt();text(r.get("policy_version"),96,false);digest(r.get("policy_hash"));digest(r.get("source_digest"));
            Map<String,Object>review=object(r.get("review"),Set.of("person_code","reviewed_at","review_event_id"));text(review.get("person_code"),120,false);timestamp(review.get("reviewed_at"));long reviewId=id(review.get("review_event_id"),false);if(reviewId<=record||!reviewEvents.add(reviewId))throw corrupt();
            Policy policy=fromMap(r.get("policy"));if(!policy.confirmed()||!policy.version().equals(r.get("policy_version"))||!digestPolicy(policy).equals(r.get("policy_hash")))throw corrupt();validateAggregate(r.get("summary"),policy);
        }
        if(!Collections.disjoint(imports,reviewEvents))throw corrupt();
    }

    public static void guardLegacyProjectMutation(String operation,long project,Map<String,Object>proposed) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Map<String,Object>head=Db.one("SELECT project_id,demand_id FROM m07_result_heads WHERE project_id=?",project);if(head==null)return;
            if(operation.equals("delete"))throw error(409,"项目已有评分汇总历史，不能删除");
            if(operation.equals("update")&&proposed!=null&&proposed.containsKey("demand_id")&&id(proposed.get("demand_id"),false)!=id(head.get("demand_id"),false))throw error(409,"评分汇总项目不能更换受理来源");
            restore(SurveySummaryImportsIntegration.trustedSource(project));
        }
    }

    public static boolean handle(HttpExchange ex,Auth.Session supplied) throws Exception {
        String path=ex.getRequestURI().getPath();if(!path.equals(BASE)&&!path.startsWith(BASE+"/"))return false;
        ex.getResponseHeaders().set("Cache-Control","no-store");
        try { synchronized(Api.MUTATION_LOCK) {
            if(Auth.get(Api.token(ex))!=supplied||Auth.current(supplied)==null)throw error(401,"登录会话无效或已失效");
            Map<String,Object>result;
            if(path.equals(BASE+"/policies")) {
                if(!ex.getRequestMethod().equals("GET"))throw error(405,"请求方法不受支持");
                if(ex.getRequestURI().getRawQuery()!=null)throw error(400,"统计规则列表不接受查询参数");
                result=policies(supplied);
            }else if(path.equals(BASE)) {
                if(!ex.getRequestMethod().equals("GET"))throw error(405,"请求方法不受支持");
                String raw=ex.getRequestURI().getRawQuery();if(raw==null||!raw.matches("project_id=[1-9][0-9]{0,15}"))throw error(400,"请提供唯一项目编号");
                result=read(supplied,Long.parseLong(raw.substring(11)));
            }else{
                if(!ex.getRequestMethod().equals("POST"))throw error(405,"请求方法不受支持");
                if(ex.getRequestURI().getRawQuery()!=null)throw error(400,"操作不接受查询参数");
                if(path.equals(BASE+"/policies/publish")) {
                    String type=ex.getRequestHeaders().getFirst("Content-Type");
                    if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw error(415,"统计规则保存只接受JSON请求");
                }
                result=switch(path.substring(BASE.length())){case "/confirm"->confirm(supplied,Api.body(ex));case "/review"->review(supplied,Api.body(ex));case "/policies/publish"->publishPolicy(supplied,Api.body(ex));default->throw error(404,"评分汇总接口不存在");};
            }
            Api.ok(ex,result);return true;
        }} catch(Api.ApiException known) { throw known; }
        catch(Exception unavailable) { throw error(503,"评分汇总服务暂时不可用，请稍后重试"); }
    }

    private static Ledger restore(SurveySummaryImportsIntegration.Source src) throws Exception {
        Ledger ledger=new Ledger();Map<String,Object>head=Db.one("SELECT * FROM m07_result_heads WHERE project_id=?",src.id());if(head==null)return ledger;
        try {
            if(id(head.get("demand_id"),false)!=src.demand()||!src.org().equals(head.get("organization_code")))throw corrupt();
            List<Map<String,Object>>rows=Db.query("SELECT version,event_json,event_hash FROM m07_result_events WHERE project_id=? ORDER BY version LIMIT ?",src.id(),MAX_EVENTS+1);
            if(rows.size()>MAX_EVENTS||id(head.get("version"),false)!=rows.size())throw corrupt();
            for(Map<String,Object>row:rows) {
                String json=text(row.get("event_json"),MAX_BYTES,false);ledger.bytes+=json.length();if(ledger.bytes>MAX_BYTES)throw corrupt();
                Map<String,Object>event=object(Json.parse(json),Set.of("format","project_id","version","operation","account_id","actor_code","config_hash","at","previous_hash","request_hash","payload"));
                if(!FORMAT.equals(event.get("format"))||id(event.get("project_id"),false)!=src.id()||id(event.get("version"),false)!=ledger.version+1||!ledger.hash.equals(event.get("previous_hash"))||!hash(event).equals(digest(row.get("event_hash"))))throw corrupt();
                if(id(row.get("version"),false)!=ledger.version+1)throw corrupt();text(event.get("actor_code"),120,false);id(event.get("account_id"),false);digest(event.get("config_hash"));digest(event.get("request_hash"));timestamp(event.get("at"));
                apply(ledger,event,src);ledger.version++;ledger.hash=digest(row.get("event_hash"));ledger.eventHashes.put(ledger.version,ledger.hash);
            }
            if(!ledger.hash.equals(digest(head.get("head_hash"))))throw corrupt();
            List<Map<String,Object>>claims=Db.query("SELECT file_hash,policy_hash,import_version FROM m07_file_claims WHERE project_id=?",src.id());
            if(claims.size()!=ledger.imports.size())throw corrupt();Set<Long>claimed=new HashSet<>();for(Map<String,Object>claim:claims){Import item=ledger.imports.get(id(claim.get("import_version"),false));if(item==null||!claimed.add(item.id)||!item.file.equals(claim.get("file_hash"))||!digestPolicy(item.policy).equals(claim.get("policy_hash")))throw corrupt();}
            return ledger;
        }catch(Exception bad){throw corrupt();}
    }
    private static void apply(Ledger ledger,Map<String,Object>event,SurveySummaryImportsIntegration.Source src) throws Exception {
        if("IMPORT".equals(event.get("operation"))) {
            Map<String,Object>p=object(event.get("payload"),Set.of("series_id","replaces_id","reason","file_hash","score_hash","policy","policy_hash","summary","source"));Import item=new Import(event,p);
            if(!item.policy.confirmed()||!digestPolicy(item.policy).equals(p.get("policy_hash")))throw corrupt();
            if(id(item.source.get("project_id"),false)!=src.id()||id(item.source.get("demand_id"),false)!=src.demand()||!src.org().equals(item.source.get("organization_code")))throw corrupt();
            id(item.source.get("accepted_revision"),false);id(item.source.get("workflow_version"),false);text(item.source.get("team_code"),120,false);text(item.source.get("actor_code"),120,false);timestamp(item.source.get("accepted_at"));
            if(ledger.imports.values().stream().anyMatch(i->i.file.equals(item.file))&&!policyRecalculation(ledger,item.replaces,item.file,item.policy))throw corrupt();
            if(item.replaces==0){if(item.series!=item.id||!item.reason.isEmpty())throw corrupt();}
            else {Import previous=ledger.imports.get(item.replaces);if(previous==null||previous.series!=item.series||!Objects.equals(ledger.tips.get(item.series),item.replaces)||item.reason.isBlank())throw corrupt();}
            ledger.imports.put(item.id,item);ledger.tips.put(item.series,item.id);
        }else if("REVIEW".equals(event.get("operation"))) {
            Map<String,Object>p=object(event.get("payload"),Set.of("import_id","decision","reason","policy_hash"));Import item=ledger.imports.get(id(p.get("import_id"),false));
            if(item==null||!item.decision.equals("PENDING_REVIEW")||!Objects.equals(ledger.tips.get(item.series),item.id)||item.account==id(event.get("account_id"),false)||item.actor.equals(event.get("actor_code")))throw corrupt();
            String decision=text(p.get("decision"),16,false),reason=text(p.get("reason"),500,true);digest(p.get("policy_hash"));
            if(!Set.of("APPROVE","REJECT").contains(decision)||decision.equals("REJECT")&&reason.isBlank())throw corrupt();
            if(decision.equals("APPROVE")&&!digestPolicy(item.policy).equals(p.get("policy_hash")))throw corrupt();
            item.decision=decision.equals("APPROVE")?"APPROVED":"REJECTED";item.reviewer=text(event.get("actor_code"),120,false);item.reviewAccount=id(event.get("account_id"),false);item.reviewedAt=timestamp(event.get("at"));item.reviewEvent=id(event.get("version"),false);item.reviewReason=reason;
            if(decision.equals("APPROVE"))ledger.active.put(item.series,item.id);
        }else throw corrupt();
    }
    private static void append(Context ctx,Ledger ledger,String operation,Map<String,Object>payload,String requestHash) throws Exception {
        if(ledger.version>=MAX_EVENTS)throw error(409,"评分汇总历史已达本期上限，请联系维护人员");
        long next=ledger.version+1;Map<String,Object>event=map("format",FORMAT,"project_id",ctx.source().id(),"version",next,"operation",operation,"account_id",ctx.person().account(),"actor_code",ctx.person().actor(),"config_hash",ctx.person().config(),"at",Instant.now().toString(),"previous_hash",ledger.hash,"request_hash",requestHash,"payload",payload);
        String json=canonical(event),hash=hash(event);if(ledger.bytes+json.length()>MAX_BYTES)throw error(409,"评分汇总历史内容超过本期上限");
        if(ledger.version==0)Db.exec("INSERT INTO m07_result_heads VALUES(?,?,?,?,?)",ctx.source().id(),ctx.source().demand(),ctx.source().org(),next,hash);
        else update("UPDATE m07_result_heads SET version=?,head_hash=? WHERE project_id=? AND version=? AND head_hash=?",next,hash,ctx.source().id(),ledger.version,ledger.hash);
        Db.exec("INSERT INTO m07_result_events VALUES(?,?,?,?)",ctx.source().id(),next,json,hash);
    }
    private static void target(Ledger ledger,long replaces,String reason) throws Api.ApiException {
        if(replaces==0){if(!reason.isEmpty())throw error(400,"新批次不填写修订原因");return;}
        Import old=ledger.imports.get(replaces);if(old==null||!Objects.equals(ledger.tips.get(old.series),replaces))throw error(409,"只能基于当前最新记录创建修正版");
        if(reason.isBlank())throw error(400,"修正版必须填写更正原因");
    }
    private static boolean policyRecalculation(Ledger ledger,long replaces,String file,Policy policy) {
        Import old=ledger.imports.get(replaces);String hash=digestPolicy(policy);
        return old!=null&&Objects.equals(ledger.tips.get(old.series),replaces)&&old.file.equals(file)&&!digestPolicy(old.policy).equals(hash)
            &&ledger.imports.values().stream().noneMatch(i->i.file.equals(file)&&digestPolicy(i.policy).equals(hash));
    }
    private static Map<String,Object> view(Import item,Ledger ledger) {
        String status=item.decision;if(item.decision.equals("APPROVED")&&!Objects.equals(ledger.active.get(item.series),item.id))status="SUPERSEDED";
        else if(item.decision.equals("PENDING_REVIEW")&&!Objects.equals(ledger.tips.get(item.series),item.id))status="SUPERSEDED_PENDING";
        return map("import_id",item.id,"series_id",item.series,"replaces_id",item.replaces,"state",status,"current",Objects.equals(ledger.active.get(item.series),item.id),
            "summary",item.summary,"policy",item.policy.toMap(),"importer_code",item.actor,"imported_at",item.at,"reason",item.reason,"review_reason",item.reviewReason,"reviewer_code",item.reviewer,"reviewed_at",item.reviewedAt,"review_event_id",item.reviewEvent);
    }
    private static Map<String,Object> replay(Context ctx,String request,String hash) throws Exception {
        Map<String,Object>row=Db.one("SELECT * FROM m07_result_requests WHERE account_id=? AND request_id=?",ctx.person().account(),request);if(row==null)return null;
        if(!ctx.person().actor().equals(row.get("actor_code"))||id(row.get("project_id"),false)!=ctx.source().id()||!hash.equals(row.get("payload_hash")))throw error(409,"请求编号已用于其他内容");
        Ledger ledger=restore(ctx.source());long version=id(row.get("result_version"),false);if(version>ledger.version)throw corrupt();
        Map<String,Object>eventRow=Db.one("SELECT event_json FROM m07_result_events WHERE project_id=? AND version=?",ctx.source().id(),version);
        if(eventRow==null)throw corrupt();Map<String,Object>event=Json.parseMap((String)eventRow.get("event_json"));
        if(!hash.equals(event.get("request_hash"))||id(event.get("account_id"),false)!=ctx.person().account()||!ctx.person().actor().equals(event.get("actor_code")))throw corrupt();
        return receipt(ctx.source().id(),version,"RECORDED",true);
    }
    private static void remember(Context ctx,String request,String hash,long result) throws Exception {Db.exec("INSERT INTO m07_result_requests VALUES(?,?,?,?,?,?)",ctx.person().account(),request,ctx.person().actor(),ctx.source().id(),hash,result);}
    private static Map<String,Object>receipt(long project,long version,String state,boolean replay){return map("project_id",project,"result_version",version,"state",state,"replayed",replay,"reload_required",true);}
    private static Map<String,Object>stage(Map<String,Object>result,java.util.function.Consumer<Map<String,Object>>stage){if(stage!=null)stage.accept(result);return result;}
    private static Context context(Auth.Session s,long project,String resource,boolean write) throws Exception {
        PersonContext p=person(s);if(project<=0||project>MAX_ID)throw error(400,"项目编号无效");SurveySummaryImportsIntegration.Source src=SurveySummaryImportsIntegration.trustedSource(project);
        permission(s,"survey.read",OrganizationAccess.Action.VIEW,src.org());
        if(!resource.equals("survey.read"))permission(s,resource,OrganizationAccess.Action.HANDLE,src.org());
        if(write&&!EDITABLE.contains(src.status()))throw error(409,"项目当前状态只允许查看评分汇总");
        return new Context(p,src,policy(src.org()));
    }
    private static PersonContext person(Auth.Session s) throws Exception {
        if(Auth.current(s)==null)throw error(401,"登录会话无效或已失效");OrganizationAccess.Person p=OrganizationAccessStore.person(s);
        OrganizationAccess.Configuration c=OrganizationAccessStore.configuration();if(c==null)throw error(403,"组织权限尚未配置");
        return new PersonContext(s.uid,p.personCode(),hash(OrganizationAccessStore.toMap(c)));
    }
    private static void revalidate(Auth.Session s,Context expected,String resource,boolean write) throws Exception {if(!expected.equals(context(s,expected.source().id(),resource,write)))throw stale();}
    private static void permission(Auth.Session s,String resource,OrganizationAccess.Action action,String org)throws Api.ApiException{if(!allowed(s,resource,action,org))throw error(403,"没有该项目的评分汇总操作权限");}
    private static boolean allowed(Auth.Session s,String resource,OrganizationAccess.Action action,String org){return OrganizationAccessStore.authorize(s,resource,action,org).allowed();}
    private static boolean eligibleRole(Auth.Session s,Policy policy,String org)throws Exception{
        OrganizationAccessStore.person(s);
        OrganizationAccess.Decision decision=OrganizationAccessStore.authorize(s,"survey.review",OrganizationAccess.Action.HANDLE,org);
        if(!decision.allowed())return false;
        OrganizationAccess.Configuration c=OrganizationAccessStore.configuration();
        if(c==null||!c.version().equals(decision.configurationVersion()))return false;
        // M01 has already applied every scoped DENY; select an actual matched ALLOW from a policy-approved role.
        return c.grants().stream().anyMatch(g->decision.matchedRuleIds().contains(g.ruleId())&&policy.reviewerRoles().contains(g.roleCode())
            &&g.effect()==OrganizationAccess.Effect.ALLOW&&g.resource().equals("survey.review")&&g.action()==OrganizationAccess.Action.HANDLE);
    }
    private static void reviewerRole(Auth.Session s,Policy policy,String org)throws Exception{if(!eligibleRole(s,policy,org))throw error(403,"当前人员没有以指定复核岗位获授权的项目范围");}
    private static void requirePolicy(Policy p)throws Api.ApiException{if(p==null||!p.confirmed())throw error(409,"该机构尚未启用已确认的统计规则及复核配置");}
    private static String policyVersion(String org)throws Exception{Map<String,Object>row=Db.one("SELECT version_code FROM m07_policy_heads WHERE organization_code=?",org);return row==null?null:text(row.get("version_code"),96,false);}
    private static Policy policy(String org)throws Exception{
        String version=policyVersion(org);if(version==null)return null;
        try{Map<String,Object>row=Db.one("SELECT payload,payload_hash FROM m07_policy_revisions WHERE organization_code=? AND version_code=?",org,version);if(row==null)throw corrupt();Policy policy=fromMap(Json.parse(text(row.get("payload"),20000,false)));if(!policy.version().equals(version)||!digestPolicy(policy).equals(row.get("payload_hash")))throw corrupt();return policy;}catch(Exception bad){throw corrupt();}
    }
    private static Map<String,Object>sourceMap(SurveySummaryImportsIntegration.Source s){return map("project_id",s.id(),"demand_id",s.demand(),"organization_code",s.org(),"accepted_revision",s.revision(),"workflow_version",s.workflowVersion(),"team_code",s.team(),"actor_code",s.actor(),"accepted_at",s.acceptedAt());}
    private static void sweep(){long now=System.currentTimeMillis();TICKETS.entrySet().removeIf(e->e.getValue().expires<now);}
    private static void standalone()throws Exception{if(!Db.get().getAutoCommit())throw error(409,"评分汇总操作不能嵌套业务事务");}
    private static void update(String sql,Object...values)throws Exception{try(PreparedStatement st=Db.get().prepareStatement(sql)){for(int i=0;i<values.length;i++)st.setObject(i+1,values[i]);if(st.executeUpdate()!=1)throw stale();}}
    static Map<String,Object>object(Object raw,Set<String>keys)throws Api.ApiException{if(!(raw instanceof Map<?,?>m)||!m.keySet().equals(keys))throw error(400,"评分汇总数据字段不完整或包含额外字段");@SuppressWarnings("unchecked")Map<String,Object>result=(Map<String,Object>)m;return result;}
    private static void fields(Map<String,?>input,Set<String>keys)throws Api.ApiException{object(input,keys);}
    private static long id(Object raw,boolean zero)throws Api.ApiException{try{if(!(raw instanceof Number))throw new IllegalArgumentException();long n=new BigDecimal(raw.toString()).longValueExact();if(n<(zero?0:1)||n>MAX_ID)throw new IllegalArgumentException();return n;}catch(RuntimeException invalid){throw error(400,"记录编号或版本无效");}}
    private static String text(Object raw,int max,boolean empty)throws Api.ApiException{if(!(raw instanceof String s)||s.length()>max||(!empty&&s.isBlank()))throw error(400,"文本字段格式不正确");return (String)raw;}
    private static String code(Object raw)throws Api.ApiException{String s=text(raw,96,false);if(!s.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*"))throw error(400,"请求或口径编号格式不正确");return s;}
    private static String token(Object raw)throws Api.ApiException{String s=text(raw,64,false);if(!s.matches("[a-f0-9]{64}"))throw error(400,"预览凭证格式不正确");return s;}
    private static String digest(Object raw)throws Api.ApiException{try{return token(raw);}catch(Exception bad){throw corrupt();}}
    private static String optionalDigest(Object raw)throws Api.ApiException{return "".equals(raw)?"":digest(raw);}
    private static String timestamp(Object raw)throws Exception{String s=text(raw,40,false);try{Instant.parse(s);return s;}catch(RuntimeException bad){throw corrupt();}}
    private static Api.ApiException stale(){return error(409,"权限、口径、项目或记录版本已变化，请重新读取和预览");}
    private static Api.ApiException corrupt(){return error(409,"评分汇总历史或口径无法核实，请联系维护人员");}
    private static Api.ApiException error(int code,String msg){return new Api.ApiException(code,msg);}
    static Map<String,Object>map(Object...pairs){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    static String canonical(Object v){return Json.write(normalize(v));}
    private static Object normalize(Object v){if(v instanceof Map<?,?>m){Map<String,Object>out=new TreeMap<>();m.forEach((k,x)->out.put((String)k,normalize(x)));return out;}if(v instanceof Collection<?>c)return c.stream().map(SurveySummaryImportsFormal::normalize).toList();if(v instanceof Number n)return new BigDecimal(n.toString()).stripTrailingZeros();return v;}
    static String hash(Object v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(v).getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException("汇总摘要生成失败");}}
}
