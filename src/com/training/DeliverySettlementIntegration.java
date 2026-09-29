package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.training.DeliverySettlement.*;

/** M05 persistence boundary. No seeded tariffs, identity grants, payroll or legacy-fee projection. */
public final class DeliverySettlementIntegration {
    private DeliverySettlementIntegration() {}
    private static final String BASE="/api/delivery-settlement";
    private static final Set<String> OPS=Set.of("save","verify","configure","confirm","correct");
    private static final Set<String> CONTROL=Set.of("dispatch_id","expected_version","request_id");

    /** Call after original tables and WorkflowIntegration.init(), outside any transaction. */
    public static void init() throws SQLException {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit()) throw new SQLException("M05初始化不可嵌套事务");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_delivery_facts(dispatch_id BIGINT PRIMARY KEY REFERENCES dispatches(id),project_id BIGINT NOT NULL REFERENCES projects(id),teacher_id BIGINT NOT NULL REFERENCES teachers(id),organization_code VARCHAR(96) NOT NULL,revision BIGINT NOT NULL,payload CLOB NOT NULL,config_version VARCHAR(96),head_snapshot_code VARCHAR(96))");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_fact_revisions(dispatch_id BIGINT NOT NULL REFERENCES m05_delivery_facts(dispatch_id),revision BIGINT NOT NULL,payload CLOB NOT NULL,event_type VARCHAR(20) NOT NULL,actor_code VARCHAR(96) NOT NULL,account_id BIGINT NOT NULL,created_at VARCHAR(40) NOT NULL,PRIMARY KEY(dispatch_id,revision))");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_policy_versions(kind VARCHAR(16) NOT NULL,version_code VARCHAR(96) NOT NULL,payload CLOB NOT NULL,PRIMARY KEY(kind,version_code))");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_settlement_configs(version_code VARCHAR(96) PRIMARY KEY,dispatch_id BIGINT NOT NULL REFERENCES m05_delivery_facts(dispatch_id),fact_revision BIGINT NOT NULL,previous_version VARCHAR(96) UNIQUE,payload CLOB NOT NULL,actor_code VARCHAR(96) NOT NULL,account_id BIGINT NOT NULL,created_at VARCHAR(40) NOT NULL)");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_settlement_snapshots(id IDENTITY PRIMARY KEY,snapshot_code VARCHAR(96) NOT NULL UNIQUE,dispatch_id BIGINT NOT NULL REFERENCES m05_delivery_facts(dispatch_id),fact_revision BIGINT NOT NULL,previous_snapshot_code VARCHAR(96) UNIQUE,payload CLOB NOT NULL,ledger CLOB NOT NULL,payment_status VARCHAR(16) NOT NULL,payment_date VARCHAR(10),UNIQUE(dispatch_id,fact_revision))");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_requests(account_id BIGINT NOT NULL,actor_code VARCHAR(96) NOT NULL,request_id VARCHAR(96) NOT NULL,operation VARCHAR(24) NOT NULL,dispatch_id BIGINT NOT NULL REFERENCES dispatches(id),payload CLOB NOT NULL,response CLOB NOT NULL,PRIMARY KEY(account_id,request_id))");
            DeliverySettlementLedger.init();
            DeliverySettlementEvidence.init();
            DeliverySettlementWorkflow.init();
            DeliverySettlementCases.init();
            DeliverySettlementCoding.init();
        }
    }

    /** Api supplies its cached bounded JSON body and delays the response until the transaction completes. */
    public static boolean handle(HttpExchange ex,Auth.Session supplied) throws Exception {
        if(DeliverySettlementCoding.handle(ex,supplied)) return true;
        if(DeliverySettlementWorkflow.handle(ex,supplied)) return true;
        if(DeliverySettlementCases.handle(ex,supplied)) return true;
        String path=ex.getRequestURI().getPath();
        String op=path.startsWith(BASE+"/")?path.substring(BASE.length()+1):"";
        if(!path.equals(BASE)&&!OPS.contains(op)&&!Set.of("preview","policy-preview","ledger","history").contains(op)) return false;
        Auth.Session session=Auth.get(Api.token(ex));
        if(session==null||session!=supplied||Auth.current(session)==null) fail(401,"登录会话无效或已失效");
        if(Set.of("configure","confirm","correct").contains(op)) fail(409,"请通过正式课酬申报和核准流程办理，不接受直接提交费率或审批标记");
        if(op.equals("history")) {
            method(ex,"GET"); Map<String,String> q=historyQuery(ex);
            Api.ok(ex,history(session,integer(q.get("dispatch_id"),false,"dispatch_id"),integer(q.get("expected_version"),true,"expected_version"),
                    historyPage(q.getOrDefault("page","1"),"page"),historyPage(q.getOrDefault("page_size","20"),"page_size")));
            return true;
        }
        if(path.equals(BASE)||op.equals("preview")||op.equals("policy-preview")||op.equals("ledger")) {
            method(ex,"GET");
            String rawQuery=ex.getRequestURI().getRawQuery();
            if(rawQuery==null||rawQuery.split("&",-1).length!=1) fail(400,"仅接受一个dispatch_id查询参数");
            Map<String,String> q=Api.query(ex);
            if(q.size()!=1||!q.containsKey("dispatch_id")) fail(400,"仅接受dispatch_id查询参数");
            long id=integer(q.get("dispatch_id"),false,"dispatch_id");
            Object value=path.equals(BASE)?read(session,id):op.equals("preview")?preview(session,id):op.equals("policy-preview")?policyPreview(session,id):ledger(session,id);
            Api.ok(ex,value);
        } else { method(ex,"POST"); Api.ok(ex,mutate(op,session,Api.body(ex))); }
        return true;
    }

    private static final String HISTORY_INVALID="授课修订历史不完整或无法核实，请联系管理员核对";
    private static final int HISTORY_MAX_REVISIONS=10000;
    private static final long HISTORY_MAX_CHARS=8L*1024*1024;
    private static final List<String> HISTORY_CHANGE_FIELDS=List.of("estimated_hours","planned_hours","actual_minutes","actual_hours","payable_hours",
            "verification_status","verification_actor_code","verification_checked_at","verification_evidence_code");

    /** History-only strict query decoder; existing read and mutation query behavior is unchanged. */
    private static Map<String,String> historyQuery(HttpExchange ex) throws Exception {
        String raw=ex.getRequestURI().getRawQuery();
        if(raw==null||raw.length()>2048) fail(400,"修订历史查询参数无效");
        Map<String,String> query=new LinkedHashMap<>();
        for(String pair:raw.split("&",-1)) {
            int equal=pair.indexOf('=');
            if(equal<=0||equal!=pair.lastIndexOf('=')||equal==pair.length()-1) fail(400,"修订历史查询参数无效");
            String key,value;
            try {
                key=java.net.URLDecoder.decode(pair.substring(0,equal),java.nio.charset.StandardCharsets.UTF_8);
                value=java.net.URLDecoder.decode(pair.substring(equal+1),java.nio.charset.StandardCharsets.UTF_8);
            } catch(IllegalArgumentException invalid) { throw new Api.ApiException(400,"修订历史查询参数无效"); }
            if(!Set.of("dispatch_id","expected_version","page","page_size").contains(key)||query.containsKey(key)||value.isEmpty())
                fail(400,"修订历史查询参数未知或重复");
            query.put(key,value);
        }
        if(!query.containsKey("dispatch_id")||!query.containsKey("expected_version")) fail(400,"修订历史查询需要排课和当前版本");
        return query;
    }
    private static int historyPage(String value,String name) throws Exception {
        long n=integer(value,false,name); if(n>Integer.MAX_VALUE) fail(400,"修订历史页码超出有效范围"); return (int)n;
    }

    /**
     * Current permission on every page, then validation of the entire stored chain before projection.
     * No rule lookup, minute conversion, price calculation, write, or present-day identity relabeling.
     */
    public static Map<String,Object> history(Auth.Session session,long dispatchId,long expectedVersion,int page,int pageSize) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(session);
            if(dispatchId<1||dispatchId>9007199254740991L||expectedVersion<0||expectedVersion>9007199254740991L||page<1||pageSize<1||pageSize>50)
                fail(400,"修订历史查询范围无效");
            // Use the same persisted organization anchor before diagnosing dates or teacher links.
            // No revision payload is read before current scope authorization.
            Map<String,Object> anchor=Db.one("SELECT w.organization_code FROM dispatches d JOIN projects p ON p.id=d.project_id JOIN workflow_acceptances a ON a.project_id=p.id JOIN workflow_demands w ON w.demand_id=a.demand_id WHERE d.id=?",dispatchId);
            if(anchor!=null) {
                String org;
                try { org=code(anchor.get("organization_code"),false); }
                catch(Api.ApiException invalid) { throw new Api.ApiException(409,HISTORY_INVALID); }
                if(!OrganizationAccessStore.authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,org).allowed()) fail(403,"没有该机构范围内的操作权限");
            }
            Source src;
            try { src=source(dispatchId); }
            catch(Api.ApiException invalid) {
                if(invalid.code==404&&Db.one("SELECT id FROM dispatches WHERE id=?",dispatchId)==null) throw invalid;
                throw new Api.ApiException(409,HISTORY_INVALID);
            } catch(RuntimeException invalid) { throw new Api.ApiException(409,HISTORY_INVALID); }
            authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,src);
            // Aggregate lengths first, before any CLOB is materialized. Never truncate a chain.
            Map<String,Object> extent=Db.one("SELECT COUNT(*) AS n,MIN(revision) AS first_version,MAX(revision) AS last_version,MAX(CHAR_LENGTH(payload)) AS max_chars,COALESCE(SUM(CHAR_LENGTH(payload)),0) AS all_chars FROM m05_fact_revisions WHERE dispatch_id=?",dispatchId);
            Map<String,Object> headExtent=Db.one("SELECT revision,CHAR_LENGTH(payload) AS chars FROM m05_delivery_facts WHERE dispatch_id=?",dispatchId);
            long total,current,chars,maxChars,headChars,first,last;
            try {
                total=integer(extent.get("n"),true,"修订数"); current=headExtent==null?0:integer(headExtent.get("revision"),false,"事实版本");
                headChars=headExtent==null?0:integer(headExtent.get("chars"),true,"事实长度");
                chars=integer(extent.get("all_chars"),true,"修订长度")+headChars;
                maxChars=total==0?0:integer(extent.get("max_chars"),true,"修订长度");
                first=total==0?0:integer(extent.get("first_version"),false,"首版");
                last=total==0?0:integer(extent.get("last_version"),false,"末版");
            } catch(Api.ApiException|RuntimeException invalid) { throw new Api.ApiException(409,HISTORY_INVALID); }
            if(total>HISTORY_MAX_REVISIONS||chars>HISTORY_MAX_CHARS||maxChars>32000||headChars>32000)
                fail(409,"授课修订历史超过核验容量上限（10000版、单版32000字符、合计8Mi字符），请联系管理员核对");
            if(total!=current||(total>0&&(first!=1||last!=current))) fail(409,HISTORY_INVALID);
            if(expectedVersion!=current) fail(409,"授课记录已更新，请刷新后查看修订历史");
            Map<String,Object> head=Db.one("SELECT * FROM m05_delivery_facts WHERE dispatch_id=?",dispatchId);
            List<Map<String,Object>> rows=Db.query("SELECT dispatch_id,revision,payload,event_type,actor_code,account_id,created_at FROM m05_fact_revisions WHERE dispatch_id=? ORDER BY revision",dispatchId);
            List<Map<String,Object>> validated=new ArrayList<>();
            try {
                if(rows.size()!=total) fail(409,HISTORY_INVALID);
                Instant readAt=Instant.now();
                Map<String,Object> previous=null,previousFields=null;
                for(int index=0;index<rows.size();index++) {
                    Map<String,Object> row=rows.get(index); long version=index+1L;
                    if(integer(row.get("dispatch_id"),false,"排课")!=dispatchId||integer(row.get("revision"),false,"修订")!=version) fail(409,HISTORY_INVALID);
                    Map<String,Object> payload=json(text(row,"payload"));
                    historyFact(src,payload,version,readAt);
                    String event=code(row.get("event_type"),false),actor=code(row.get("actor_code"),false);
                    long account=integer(row.get("account_id"),false,"操作账号");
                    Instant created=historyInstant(row.get("created_at"));
                    if(created.isAfter(readAt)) fail(409,HISTORY_INVALID);
                    Map<String,Object> verification=payload.get("verification")==null?null:map(payload.get("verification"));
                    if("SAVE".equals(event)) {
                        if(verification!=null) fail(409,HISTORY_INVALID);
                    } else if("VERIFY".equals(event)) {
                        if(previous==null||verification==null||!actor.equals(verification.get("actor_code"))
                                ||created.isBefore(historyInstant(verification.get("checked_at")))
                                ||payload.get("conversion")==null) fail(409,HISTORY_INVALID);
                        Map<String,Object> left=new LinkedHashMap<>(previous),right=new LinkedHashMap<>(payload);
                        left.remove("revision");left.remove("verification");right.remove("revision");right.remove("verification");
                        if(!canonical(left).equals(canonical(right))) fail(409,HISTORY_INVALID);
                    } else fail(409,HISTORY_INVALID);
                    Map<String,Object> fields=historyFields(payload),item=new LinkedHashMap<>();
                    item.put("version",version);item.put("event_type",event);item.put("actor_code",actor);item.put("account_id",account);item.put("created_at",row.get("created_at"));
                    for(String name:List.of("estimated_hours","planned_hours","actual_minutes","actual_hours","payable_hours")) item.put(name,fields.get(name));
                    item.put("verification",verification==null?null:new LinkedHashMap<>(verification));
                    List<Map<String,Object>> changes=new ArrayList<>();
                    for(String field:HISTORY_CHANGE_FIELDS) {
                        Object before=previousFields==null?null:previousFields.get(field),after=fields.get(field);
                        if(!Objects.equals(before,after)) {
                            Map<String,Object> change=new LinkedHashMap<>();change.put("field",field);change.put("before",before);change.put("after",after);changes.add(change);
                        }
                    }
                    item.put("changes",changes);item.put("verification_invalidated",previous!=null&&previous.get("verification")!=null&&verification==null);
                    validated.add(item);previous=payload;previousFields=fields;
                }
                if(head!=null) {
                    if(integer(head.get("dispatch_id"),false,"排课")!=src.id()||integer(head.get("project_id"),false,"项目")!=src.project()
                            ||integer(head.get("teacher_id"),false,"讲师")!=src.teacher()||!src.org().equals(head.get("organization_code"))) fail(409,HISTORY_INVALID);
                    Map<String,Object> payload=json(text(head,"payload"));historyFact(src,payload,current,readAt);
                    if(previous==null||!canonical(previous).equals(canonical(payload))) fail(409,HISTORY_INVALID);
                } else if(previous!=null) fail(409,HISTORY_INVALID);
            } catch(Api.ApiException|RuntimeException invalid) { throw new Api.ApiException(409,HISTORY_INVALID); }
            long offset=((long)page-1)*pageSize;
            List<Map<String,Object>> items=new ArrayList<>();
            for(long index=offset;index<total&&index<offset+pageSize;index++) items.add(validated.get((int)(total-1-index)));
            Map<String,Object> out=new LinkedHashMap<>();out.put("dispatch_id",src.id());out.put("project_id",src.project());out.put("teacher_id",src.teacher());out.put("organization_code",src.org());
            out.put("current_version",current);out.put("page",page);out.put("page_size",pageSize);out.put("total",total);out.put("items",items);return out;
        }
    }
    /** Validate storage shape and internal identity only. Historical conversion is never rerun. */
    private static void historyFact(Source src,Map<String,Object> payload,long version,Instant readAt) throws Exception {
        if(!payload.keySet().equals(Set.of("record_id","revision","session_code","instructor_code","organization_code","service_date","dimensions","hours","verification","data_mode","conversion"))) fail(409,HISTORY_INVALID);
        if(!(payload.get("record_id") instanceof Number)||!(payload.get("revision") instanceof Number)) fail(409,HISTORY_INVALID);
        Fact f=fact(payload);
        if(f.recordId()!=src.id()||f.revision()!=version||!Objects.equals(f.serviceDate(),src.date())||f.serviceDate()==null
                ||!("DISPATCH-"+src.id()).equals(f.sessionCode())||!("TEACHER-"+src.teacher()).equals(f.instructorCode())
                ||!src.org().equals(f.organizationCode())||f.dataMode()!=DataMode.CONFIGURED) fail(409,HISTORY_INVALID);
        Map<String,Object> dims=map(payload.get("dimensions"));
        if(!dims.keySet().equals(Set.of("grade","time_band","form","hour_unit"))) fail(409,HISTORY_INVALID);
        Map<String,Object> h=map(payload.get("hours"));
        if(!h.keySet().equals(Set.of("estimated","planned","actual","payable"))) fail(409,HISTORY_INVALID);
        for(Object value:h.values()) historyDecimal(value,true);
        if(payload.get("verification")!=null) {
            Map<String,Object> v=map(payload.get("verification"));
            if(!v.keySet().equals(Set.of("actor_code","checked_at","evidence_code"))) fail(409,HISTORY_INVALID);
            code(v.get("actor_code"),false);code(v.get("evidence_code"),false);
            if(historyInstant(v.get("checked_at")).isAfter(readAt)) fail(409,HISTORY_INVALID);
        }
        if(payload.get("conversion")==null) {
            if(h.get("actual")!=null) fail(409,HISTORY_INVALID);
        } else {
            Map<String,Object> cv=map(payload.get("conversion"));
            if(!cv.keySet().equals(Set.of("status","issues","minutes","class_hours","rounded","rule_version","minutes_per_class_hour","class_hour_scale","class_hour_rounding","evidence_code","rounding_decision_code"))
                    ||!"READY".equals(cv.get("status"))||!(cv.get("issues") instanceof List<?> issues)||!issues.isEmpty()
                    ||!(cv.get("rounded") instanceof Boolean)||h.get("actual")==null) fail(409,HISTORY_INVALID);
            historyDecimal(cv.get("minutes"),false);historyDecimal(cv.get("class_hours"),false);
            if(!Objects.equals(h.get("actual"),cv.get("class_hours"))) fail(409,HISTORY_INVALID);
            code(cv.get("rule_version"),false);code(cv.get("evidence_code"),false);code(cv.get("rounding_decision_code"),false);
            if(!(cv.get("minutes_per_class_hour") instanceof Number)||!(cv.get("class_hour_scale") instanceof Number)
                    ||integer(cv.get("minutes_per_class_hour"),false,"课时单位")>Integer.MAX_VALUE
                    ||integer(cv.get("class_hour_scale"),true,"课时位数")>8) fail(409,HISTORY_INVALID);
            RoundingMode.valueOf(code(cv.get("class_hour_rounding"),false));
        }
    }
    private static void historyDecimal(Object value,boolean nullable) throws Exception {
        if(value==null&&nullable) return;
        if(!(value instanceof String)) fail(409,HISTORY_INVALID);
        if(decimalInput(value)==null) fail(409,HISTORY_INVALID);
    }
    private static Instant historyInstant(Object value) throws Exception {
        if(!(value instanceof String s)||s.length()>40) { fail(409,HISTORY_INVALID); return null; }
        return Instant.parse((String)value);
    }
    private static Map<String,Object> historyFields(Map<String,Object> payload) throws Exception {
        Map<String,Object> fields=new LinkedHashMap<>(),h=map(payload.get("hours"));
        fields.put("estimated_hours",h.get("estimated"));fields.put("planned_hours",h.get("planned"));fields.put("actual_hours",h.get("actual"));fields.put("payable_hours",h.get("payable"));
        fields.put("actual_minutes",payload.get("conversion")==null?null:map(payload.get("conversion")).get("minutes"));
        Map<String,Object> v=payload.get("verification")==null?null:map(payload.get("verification"));
        fields.put("verification_status",v==null?"UNVERIFIED":"VERIFIED");fields.put("verification_actor_code",v==null?null:v.get("actor_code"));
        fields.put("verification_checked_at",v==null?null:v.get("checked_at"));fields.put("verification_evidence_code",v==null?null:v.get("evidence_code"));return fields;
    }

    static Map<String,Object> read(Auth.Session s,long id) throws Exception {
        synchronized(Api.MUTATION_LOCK) { OrganizationAccessStore.person(s); Source source=source(id); authorize(s,"delivery.read",OrganizationAccess.Action.VIEW,source); return detail(source,s); }
    }
    static Map<String,Object> preview(Auth.Session s,long id) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(s); Source source=source(id); authorize(s,"delivery.read",OrganizationAccess.Action.VIEW,source);
            Map<String,Object> row=requiredFact(source); Fact fact=fact(json(text(row,"payload")));
            Map<String,Object> cfg=config(row);
            if(cfg!=null && integer(cfg.get("fact_revision"),false,"fact_revision")!=fact.revision())
                return problemView(fact,"CONFIGURATION_FACT_REVISION_CHANGED");
            return DeliverySettlement.view(calculate(fact,cfg==null?null:rule(map(cfg.get("configuration"))),cfg==null?null:rate(map(cfg.get("configuration")))));
        }
    }
    /** Exact live scope passed to a trusted server-side evidence reader; never reconstructed from HTTP. */
    public record PolicyPreviewContext(long dispatchId,long projectId,long teacherId,String organizationCode,
                                       long factRevision,LocalDate serviceDate) {}
    /**
     * Host-only, synchronous read under the current authorization/source lock. The host must read
     * versioned, verified evidence at service time, not current grade, UI dimensions or booleans.
     * No global provider is installed. Each call must supply its reader again and read current data.
     */
    @FunctionalInterface public interface PolicyEvidenceProvider {
        PolicyEvidence read(PolicyPreviewContext context) throws Exception;
    }
    public record PolicyEvidence(PolicyPreviewContext context,String version,Map<String,String> references,
                                 DeliverySettlementApprovedPolicy.Request request) {
        public PolicyEvidence { references=references==null?Map.of():Map.copyOf(references); }
    }

    /** Approved-policy preview with missing case evidence left unknown; never writes a settlement. */
    public static Map<String,Object> policyPreview(Auth.Session session,long id) throws Exception {
        return policyPreview(session,id,null);
    }
    /**
     * Explicit parent hook for a verified evidence store. This overload is not an HTTP input parser.
     * Stored payable hours, teaching verification and frozen/legacy history always override the reader.
     */
    public static Map<String,Object> policyPreview(Auth.Session session,long id,PolicyEvidenceProvider reader) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(session); Source src=source(id);
            authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,src);
            Map<String,Object> row=checkedFact(src), payload=row==null?null:json(text(row,"payload"));
            Fact f=payload==null?null:fact(payload);
            PolicyPreviewContext context=new PolicyPreviewContext(src.id(),src.project(),src.teacher(),src.org(),f==null?0:f.revision(),src.date());
            PolicyEvidence evidence=reader==null?null:reader.read(context);
            // Even a trusted reader cannot make a response stale by changing its source during a call.
            OrganizationAccessStore.person(session); Source after=source(id);
            authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,after);
            Map<String,Object> afterRow=checkedFact(after);
            if(!src.equals(after)||!Objects.equals(row==null?null:row.get("payload"),afterRow==null?null:afterRow.get("payload")))
                fail(409,"授课事实或来源已变化，请重新读取课酬预览");
            DeliverySettlementApprovedPolicy.Request supplied=evidence==null?null:validatedPolicyEvidence(context,evidence);
            var unknown=DeliverySettlementPolicy.Truth.UNKNOWN;
            var no=DeliverySettlementPolicy.Truth.NO;
            var yes=DeliverySettlementPolicy.Truth.YES;
            var rate=supplied==null?new DeliverySettlementPolicy.RateRequest(DeliverySettlementPolicy.Activity.TEACHING,null,null,unknown):supplied.rateRequest();
            var facts=supplied==null?new DeliverySettlementPolicy.VerifiedFacts(null,null,null,null,null):supplied.facts();
            boolean frozen=Db.one("SELECT id FROM m05_settlement_snapshots WHERE dispatch_id=?",id)!=null;
            boolean legacy=Db.one("SELECT id FROM fees WHERE project_id=? AND teacher_id=?",src.project(),src.teacher())!=null;
            List<Map<String,String>> operational=new ArrayList<>();
            try { deliveryReady(src,false); } catch(Api.ApiException e) { reason(operational,"SOURCE_NOT_READY",e.getMessage()); }
            boolean verified=f!=null&&f.verification()!=null&&actualComplete(payload,f)&&operational.isEmpty();
            var request=new DeliverySettlementApprovedPolicy.Request(rate,facts,f==null?null:f.hours().payable(),
                    supplied==null?unknown:supplied.serviceDateApplicable(),verified?yes:no,
                    supplied==null?unknown:supplied.organizerSubmitted(),supplied==null?unknown:supplied.sharedDeliveryApproved(),
                    frozen||(supplied!=null&&supplied.historicalFrozen()),legacy||(supplied!=null&&supplied.legacyRuleRequired()),
                    supplied==null?null:supplied.development(),supplied==null?null:supplied.moneyRounding());
            Map<String,Object> out=new LinkedHashMap<>(DeliverySettlementApprovedPolicy.view(DeliverySettlementApprovedPolicy.preview(request)));
            if(!operational.isEmpty()) {
                for(String key:List.of("issues","formal_missing_items")) {
                    List<Object> reasons=new ArrayList<>((List<?>)out.get(key)); reasons.addAll(operational); out.put(key,reasons);
                }
                if("PREVIEW_READY".equals(out.get("status"))) out.put("status","CONDITIONAL_PREVIEW");
            }
            out.put("dispatch_id",src.id()); out.put("project_id",src.project()); out.put("teacher_id",src.teacher());
            out.put("organization_code",src.org()); out.put("service_date",dateText(src.date())); out.put("fact_version",context.factRevision());
            out.put("actual_hours",f==null?null:decimalText(f.hours().actual()));
            out.put("actual_minutes",payload==null||payload.get("conversion")==null?null:map(payload.get("conversion")).get("minutes"));
            out.put("teaching_verified",verified); out.put("evidence_version",evidence==null?null:evidence.version());
            out.put("evidence_references",evidence==null?Map.of():evidence.references());
            out.put("preview_only",true); out.put("can_confirm",false);
            return out;
        }
    }
    private static DeliverySettlementApprovedPolicy.Request validatedPolicyEvidence(PolicyPreviewContext context,PolicyEvidence evidence) throws Exception {
        if(!context.equals(evidence.context())) fail(409,"课酬依据与当前排课、讲师、日期或事实版本不一致");
        code(evidence.version(),false);
        var request=evidence.request();
        if(request==null||request.rateRequest()==null||request.rateRequest().activity()!=DeliverySettlementPolicy.Activity.TEACHING)
            fail(409,"排课课酬预览只接受该次授课的核实依据，开发课酬须使用独立依据");
        Map<String,String> refs=evidence.references();
        Set<String> allowed=Set.of("grade","day_type","research_team","appointment","annual_plan","customer_paid","applicability","organizer_application","shared_delivery_approval","development","money_rounding");
        for(var entry:refs.entrySet()) {
            if(!allowed.contains(entry.getKey())) fail(409,"课酬依据包含未知来源类型");
            code(entry.getValue(),false);
        }
        var rate=request.rateRequest(); var facts=request.facts(); var unknown=DeliverySettlementPolicy.Truth.UNKNOWN;
        requirePolicyReference(refs,"grade",rate.instructorOrLeadGrade()!=null);
        requirePolicyReference(refs,"day_type",rate.teachingDayType()!=null);
        requirePolicyReference(refs,"research_team",rate.researchTeamMember()!=unknown);
        if(facts!=null) {
            requirePolicyReference(refs,"appointment",facts.appointedBeforeWork()!=unknown);
            requirePolicyReference(refs,"annual_plan",facts.inAnnualTrainingPlan()!=unknown);
            requirePolicyReference(refs,"customer_paid",facts.customerPaysProject()!=unknown);
        }
        requirePolicyReference(refs,"applicability",request.serviceDateApplicable()!=null&&request.serviceDateApplicable()!=unknown);
        if(request.serviceDateApplicable()==DeliverySettlementPolicy.Truth.YES&&context.serviceDate()==null)
            fail(409,"缺少授课日期，不能认定该次授课适用当前办法");
        requirePolicyReference(refs,"organizer_application",request.organizerSubmitted()!=null&&request.organizerSubmitted()!=unknown);
        requirePolicyReference(refs,"shared_delivery_approval",request.sharedDeliveryApproved()!=null&&request.sharedDeliveryApproved()!=unknown);
        requirePolicyReference(refs,"development",request.development()!=null);
        requirePolicyReference(refs,"money_rounding",request.moneyRounding()!=null);
        return request;
    }
    private static void requirePolicyReference(Map<String,String> refs,String name,boolean required) throws Exception {
        if(required&&!refs.containsKey(name)) fail(409,"课酬事实缺少已核实的来源："+name);
    }

    /** Read frozen M06 entries; no recalculation or guessed payment dates. */
    public static List<Map<String,Object>> ledger(Auth.Session s,long id) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(s); Source source=source(id); authorize(s,"settlement.export",OrganizationAccess.Action.EXPORT,source); requiredFact(source);
            Map<String,Object> unified=DeliverySettlementLedger.head("DISPATCH-"+id);
            if(unified!=null&&!Objects.equals(unified.get("current_entry_code"),requiredFact(source).get("head_snapshot_code"))) fail(409,"该课酬已有独立调整，请使用统一财务账读取完整应付和支付记录");
            List<Map<String,Object>> out=new ArrayList<>();
            for(Map<String,Object> row:Db.query("SELECT * FROM m05_settlement_snapshots WHERE dispatch_id=? ORDER BY id",id)) {
                Map<String,Object> entry=json(text(row,"ledger")); entry.put("id",row.get("id"));
                entry.put("projectId",source.project());
                entry.put("payment_status",row.get("payment_status")); entry.put("payment_date",row.get("payment_date"));
                // A dispatch is a scheduled teaching occurrence, not an invented M04 course identity.
                entry.put("courseId",null); entry.put("courseCode",null);
                if("PAID".equals(text(row,"payment_status"))) {
                    if(row.get("payment_date")==null) fail(409,"支付记录缺少实际支付日期");
                    map(entry.get("dates")).put("PAYMENT",requiredDate(row.get("payment_date")).toString());
                }
                out.add(entry);
            }
            return out;
        }
    }

    /** Package entry also revalidates real Auth, current M01 scope, versions and idempotency. */
    static Map<String,Object> mutate(String op,Auth.Session session,Map<String,Object> input) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit()) fail(409,"M05变更不可嵌套事务");
            try { return Db.transaction(()->{
                if(!OPS.contains(op)) fail(404,"课时结算操作不存在");
                Map<String,Object> b=new LinkedHashMap<>(input); whitelist(b,fields(op));
                OrganizationAccessStore.person(session);
                long id=integer(b.get("dispatch_id"),false,"dispatch_id"); Source src=source(id);
                OrganizationAccess.Person actor=authorize(session,resource(op),OrganizationAccess.Action.HANDLE,src);
                String request=code(b.get("request_id"),false); String payload=canonical(b);
                Map<String,Object> retry=Db.one("SELECT * FROM m05_requests WHERE account_id=? AND request_id=?",session.uid,request);
                if(retry!=null) {
                    if(!actor.personCode().equals(text(retry,"actor_code"))||!op.equals(text(retry,"operation"))||!payload.equals(text(retry,"payload"))) fail(409,"请求编号已用于不同内容");
                    Map<String,Object> current=checkedFact(src); // Re-check associations and current capabilities on replay.
                    Map<String,Object> response=json(text(retry,"response"));
                    response.put("capabilities",capabilities(session,src,current));
                    return response;
                }
                Map<String,Object> old=checkedFact(src); long revision=old==null?0:integer(old.get("revision"),false,"revision");
                if(integer(b.get("expected_version"),true,"expected_version")!=revision) fail(409,"授课记录已更新，请刷新后重试");
                if(op.equals("save")) save(src,old,b,actor,session.uid);
                else {
                    if(old==null) fail(409,"请先保存授课事实");
                    switch(op) {
                        case "verify" -> verify(src,old,b,actor,session.uid);
                        case "configure" -> configure(src,old,b,actor,session.uid);
                        case "confirm","correct" -> settle(src,old,b,actor,session.uid,op);
                        default -> throw new IllegalArgumentException("未知操作");
                    }
                }
                Map<String,Object> response=detail(src,session);
                Db.exec("INSERT INTO m05_requests(account_id,actor_code,request_id,operation,dispatch_id,payload,response) VALUES(?,?,?,?,?,?,?)",session.uid,actor.personCode(),request,op,id,payload,Json.write(response));
                return response;
            }); } catch(IllegalArgumentException e) { throw new Api.ApiException(400,e.getMessage()); }
              catch(IllegalStateException e) { throw new Api.ApiException(409,e.getMessage()); }
        }
    }

    private static void save(Source src,Map<String,Object> old,Map<String,Object> b,OrganizationAccess.Person actor,long account) throws Exception {
        saveReady(src);
        Fact before=old==null?null:fact(json(text(old,"payload")));
        long revision=before==null?1:before.revision()+1;
        Map<String,Object> oldPayload=old==null?new LinkedHashMap<>():json(text(old,"payload"));
        Object conversion=oldPayload.get("conversion"); BigDecimal actual=before==null?null:before.hours().actual();
        if(b.containsKey("actual_minutes")) {
            if(b.get("actual_minutes")==null) { conversion=null; actual=null; }
            else {
                requireDecimalString(b.get("actual_minutes"));
                DeliverySettlementHours.Conversion cv=DeliverySettlementHours.convert(b.get("actual_minutes"),DeliverySettlementHours.currentUserRule());
                if(cv.status()!=DeliverySettlementHours.Status.READY) fail(400,"实际分钟无法折算："+cv.issues());
                actual=cv.classHours(); conversion=DeliverySettlementHours.view(cv);
            }
        }
        Hours h=new Hours(decimalField(b,"estimated_hours",before==null?null:before.hours().estimated()),
                decimalField(b,"planned_hours",before==null?null:before.hours().planned()),actual,
                decimalField(b,"payable_hours",before==null?null:before.hours().payable()));
        Dimensions d=b.containsKey("dimensions")?dimensions(map(b.get("dimensions"))):before==null?new Dimensions(null,null,null,"CLASS45"):before.dimensions();
        Fact updated=new Fact(src.id(),revision,"DISPATCH-"+src.id(),"TEACHER-"+src.teacher(),src.org(),src.date(),d,h,null,DataMode.CONFIGURED);
        Map<String,Object> p=factMap(updated); p.put("conversion",conversion);
        if(old==null) Db.exec("INSERT INTO m05_delivery_facts(dispatch_id,project_id,teacher_id,organization_code,revision,payload) VALUES(?,?,?,?,?,?)",src.id(),src.project(),src.teacher(),src.org(),revision,Json.write(p));
        else cas("UPDATE m05_delivery_facts SET revision=?,payload=? WHERE dispatch_id=? AND revision=?",revision,Json.write(p),src.id(),before.revision());
        revision(src,updated.revision(),p,"SAVE",actor,account);
    }
    private static void verify(Source src,Map<String,Object> old,Map<String,Object> b,OrganizationAccess.Person actor,long account) throws Exception {
        deliveryReady(src,true); Map<String,Object> p=json(text(old,"payload")); Fact f=fact(p);
        if(!actualComplete(p,f)) fail(409,"请先记录完整、有效的实际授课分钟");
        Fact checked=new Fact(f.recordId(),f.revision()+1,f.sessionCode(),f.instructorCode(),f.organizationCode(),f.serviceDate(),f.dimensions(),f.hours(),new Verification(actor.personCode(),Instant.now(),code(b.get("evidence_code"),false)),f.dataMode());
        Map<String,Object> next=factMap(checked); next.put("conversion",p.get("conversion"));
        cas("UPDATE m05_delivery_facts SET revision=?,payload=? WHERE dispatch_id=? AND revision=?",checked.revision(),Json.write(next),src.id(),f.revision());
        revision(src,checked.revision(),next,"VERIFY",actor,account);
    }
    private static void revision(Source src,long version,Map<String,Object> p,String event,OrganizationAccess.Person actor,long account) throws Exception {
        Db.exec("INSERT INTO m05_fact_revisions(dispatch_id,revision,payload,event_type,actor_code,account_id,created_at) VALUES(?,?,?,?,?,?,?)",src.id(),version,Json.write(p),event,actor.personCode(),account,Instant.now().toString());
    }

    private static void configure(Source src,Map<String,Object> old,Map<String,Object> b,OrganizationAccess.Person actor,long account) throws Exception {
        deliveryReady(src,true);
        Map<String,Object> factPayload=json(text(old,"payload")); Fact f=fact(factPayload);
        if(f.verification()==null) fail(409,"授课事实尚未核对或已修改，请先重新核对");
        if(!actualComplete(factPayload,f)) fail(409,"实际授课分钟或课时不完整，不能登记正式配置");
        expectedConfig(old,b);
        Map<String,Object> c=map(b.get("configuration")); validateConfiguration(c);
        Calculation check=calculate(f,rule(c),rate(c));
        if(check.status()==Status.NOT_CONFIGURED) fail(400,"结算配置不完整或不适用："+check.issues());
        String version=code(c.get("version"),false);
        if(Db.one("SELECT version_code FROM m05_settlement_configs WHERE version_code=?",version)!=null) fail(409,"配置版本已使用，不可覆盖");
        freezeVersion("RULE",map(c.get("rule"))); freezeVersion("RATE",map(c.get("rate")));
        if(c.get("correction")!=null) freezeVersion("CORRECTION",map(c.get("correction")));
        Map<String,Object> frozen=new LinkedHashMap<>(); frozen.put("configuration",c); frozen.put("fact_revision",f.revision());
        frozen.put("dispatch_id",src.id()); frozen.put("project_id",src.project()); frozen.put("teacher_id",src.teacher()); frozen.put("organization_code",src.org());
        frozen.put("service_date",dateText(src.date())); frozen.put("dimensions",dimensionsMap(f.dimensions()));
        frozen.put("actor_code",actor.personCode()); frozen.put("account_id",account); frozen.put("created_at",Instant.now().toString());
        Db.exec("INSERT INTO m05_settlement_configs(version_code,dispatch_id,fact_revision,previous_version,payload,actor_code,account_id,created_at) VALUES(?,?,?,?,?,?,?,?)",version,src.id(),f.revision(),old.get("config_version"),Json.write(frozen),actor.personCode(),account,frozen.get("created_at"));
        cas("UPDATE m05_delivery_facts SET config_version=? WHERE dispatch_id=? AND revision=?",version,src.id(),f.revision());
    }
    private static void freezeVersion(String kind,Map<String,Object> value) throws Exception {
        String version=code(value.get("version"),false); String payload=canonical(value);
        Map<String,Object> previous=Db.one("SELECT payload FROM m05_policy_versions WHERE kind=? AND version_code=?",kind,version);
        if(previous==null) Db.exec("INSERT INTO m05_policy_versions(kind,version_code,payload) VALUES(?,?,?)",kind,version,payload);
        else if(!payload.equals(text(previous,"payload"))) fail(409,"同一政策或费率版本不能表示不同内容");
    }
    private static void settle(Source src,Map<String,Object> old,Map<String,Object> b,OrganizationAccess.Person actor,long account,String op) throws Exception {
        deliveryReady(src,false); expectedConfig(old,b);
        if(Db.one("SELECT id FROM fees WHERE project_id=? AND teacher_id=?",src.project(),src.teacher())!=null) fail(409,"存在旧课酬记录，需先核对迁移后才能新确认或更正");
        Map<String,Object> cfg=config(old); if(cfg==null) fail(409,"正式结算规则尚未配置");
        Fact f=fact(json(text(old,"payload")));
        if(integer(cfg.get("fact_revision"),false,"fact_revision")!=f.revision()) fail(409,"授课事实已变化，需重新登记适用依据");
        Map<String,Object> c=map(cfg.get("configuration")); Calculation calc=calculate(f,rule(c),rate(c));
        if(calc.status()!=Status.READY) fail(409,"尚不能正式确认："+calc.issues());
        String previous=text(old,"head_snapshot_code"); Confirmed entry; String snapshot="M05-"+UUID.randomUUID();
        Instant now=Instant.now();
        if(op.equals("confirm")) {
            if(previous!=null) fail(409,"已有结算快照，须通过更正保留历史");
            entry=confirm(calc,f,snapshot,now,new Access(actor.personCode(),Set.of(Permission.CONFIRM),Set.of(src.org())));
        } else {
            if(previous==null||!previous.equals(code(b.get("expected_snapshot_code"),false))) fail(409,"当前结算依据已变化，请刷新");
            List<Map<String,Object>> chain=Db.query("SELECT * FROM m05_settlement_snapshots WHERE dispatch_id=? ORDER BY id",src.id());
            for(Map<String,Object> ancestor:chain) if(!"UNPAID".equals(text(ancestor,"payment_status"))||ancestor.get("payment_date")!=null) fail(409,"已支付或支付状态不明的结算暂不支持更正");
            Confirmed before=restore(chain,previous);
            if(!Objects.equals(before.calculation().fact().serviceDate(),f.serviceDate())) fail(409,"跨授课日更正需要冲回和重记，当前不支持");
            entry=correct(before,calc,f,snapshot,code(b.get("reason_code"),false),correction(c),now,new Access(actor.personCode(),Set.of(Permission.CORRECT),Set.of(src.org())));
        }
        Map<String,Object> p=new LinkedHashMap<>(); p.put("snapshot_code",snapshot); p.put("previous_snapshot_code",previous);
        p.put("fact",json(text(old,"payload"))); p.put("configuration",cfg); p.put("engine_version",entry.calculation().engineVersion());
        p.put("actor_code",entry.actorCode()); p.put("account_id",account); p.put("confirmed_at",entry.confirmedAt().toString()); p.put("reason_code",entry.reasonCode());
        p.put("unrounded_amount",entry.calculation().unroundedAmount().toPlainString()); p.put("amount",entry.calculation().amount().toPlainString()); p.put("settlement_amount",entry.settlementAmount().toPlainString());
        Map<String,Object> ledger=reportingContribution(entry); p.put("ledger",ledger);
        Db.insert("INSERT INTO m05_settlement_snapshots(snapshot_code,dispatch_id,fact_revision,previous_snapshot_code,payload,ledger,payment_status) VALUES(?,?,?,?,?,?,?)",snapshot,src.id(),f.revision(),previous,Json.write(p),Json.write(ledger),"UNPAID");
        cas("UPDATE m05_delivery_facts SET head_snapshot_code=? WHERE dispatch_id=? AND revision=? AND head_snapshot_code IS NOT DISTINCT FROM ?",snapshot,src.id(),f.revision(),previous);
    }
    private static Confirmed restore(List<Map<String,Object>> rows,String head) throws Exception {
        Confirmed previous=null;
        for(Map<String,Object> row:rows) {
            Map<String,Object> p=json(text(row,"payload"));
            if(!Objects.equals(p.get("previous_snapshot_code"),previous==null?null:previous.snapshotCode())) fail(409,"历史结算链不连续");
            Map<String,Object> c=map(map(p.get("configuration")).get("configuration"));
            previous=DeliverySettlement.restoreFrozen(fact(map(p.get("fact"))),rule(c),rate(c),text(p,"engine_version"),text(p,"snapshot_code"),text(p,"actor_code"),Instant.parse(text(p,"confirmed_at")),previous,previous==null?null:correction(c),text(p,"reason_code"),new BigDecimal(text(p,"unrounded_amount")),new BigDecimal(text(p,"amount")),new BigDecimal(text(p,"settlement_amount")));
        }
        if(previous==null||!head.equals(previous.snapshotCode())) fail(409,"历史结算head不一致");
        return previous;
    }

    /** Trusted context for the formal claim workflow; monetary settings are independent of teaching review. */
    public record WorkflowContext(long dispatchId,long projectId,long teacherId,String organizationCode,
            LocalDate serviceDate,long factVersion,Map<String,Object> factPayload,String configVersion,String snapshotCode) {}
    static WorkflowContext workflowContext(Auth.Session session,long id,String resource,boolean ready) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(session);Source src=source(id);authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,src);
            if(resource!=null)authorize(session,resource,OrganizationAccess.Action.HANDLE,src);
            Map<String,Object> row=checkedFact(src);long version=row==null?0:integer(row.get("revision"),false,"事实版本");
            history(session,id,version,1,1);
            Map<String,Object> payload=row==null?null:json(text(row,"payload"));
            if(ready) {deliveryReady(src,false);if(payload==null)fail(409,"请先保存并核对授课事实");Fact f=fact(payload);if(f.verification()==null||!actualComplete(payload,f))fail(409,"授课记录尚未完整核对");}
            return new WorkflowContext(id,src.project(),src.teacher(),src.org(),src.date(),version,payload,row==null?null:text(row,"config_version"),row==null?null:text(row,"head_snapshot_code"));
        }
    }
    static Fact workflowFact(WorkflowContext context) throws Exception {return context.factPayload()==null?null:fact(context.factPayload());}
    static void workflowConfirm(Auth.Session session,WorkflowContext expected,Map<String,Object> claim,Map<String,Object> settings,
            long claimVersion,String reviewEvidence,boolean correcting,String reason) throws Exception {
        if(Db.get().getAutoCommit()||!Thread.holdsLock(Api.MUTATION_LOCK))fail(409,"正式确认必须在业务事务内进行");
        WorkflowContext ctx=workflowContext(session,expected.dispatchId(),correcting?"settlement.correct":"settlement.confirm",true);
        sameWorkflowContext(expected,ctx);Source src=source(ctx.dispatchId());Map<String,Object> old=requiredFact(src);
        if(Db.one("SELECT id FROM fees WHERE project_id=? AND teacher_id=?",ctx.projectId(),ctx.teacherId())!=null)fail(409,"存在旧课酬记录，须先逐笔核对迁移，不能直接套用新办法");
        String configCode="M05-FORMAL-"+UUID.randomUUID();
        DeliverySettlementExecution.Executable execution=DeliverySettlementExecution.build(workflowFact(ctx),claim,settings,configCode,reviewEvidence);
        Map<String,Object> c=execution.configuration();validateConfiguration(c);Fact f=execution.fact();Calculation calc=calculate(f,rule(c),rate(c));
        if(calc.status()!=Status.READY)fail(409,"正式结算尚缺核准事实："+calc.issues());
        String previous=ctx.snapshotCode(),snapshot="M05-"+UUID.randomUUID(),actor=OrganizationAccessStore.person(session).personCode();Instant now=Instant.now();Confirmed confirmed;
        if(!correcting) {
            if(previous!=null||DeliverySettlementLedger.head("DISPATCH-"+ctx.dispatchId())!=null)fail(409,"已有正式课酬，请办理更正");
            confirmed=confirm(calc,f,snapshot,now,new Access(actor,Set.of(Permission.CONFIRM),Set.of(ctx.organizationCode())));
        } else {
            if(previous==null)fail(409,"尚无可以更正的原始课酬");
            Map<String,Object> financial=DeliverySettlementLedger.head("DISPATCH-"+ctx.dispatchId());
            if(financial==null||!previous.equals(financial.get("current_entry_code")))fail(409,"该课酬已通过调整事项更正，请继续从调整事项办理");
            List<Map<String,Object>> chain=Db.query("SELECT * FROM m05_settlement_snapshots WHERE dispatch_id=? ORDER BY id",ctx.dispatchId());
            for(Map<String,Object> ancestor:chain)if(!"UNPAID".equals(ancestor.get("payment_status"))||ancestor.get("payment_date")!=null)fail(409,"付款后的更正请通过调整事项办理，保留原支付记录");
            Confirmed before=restore(chain,previous);
            confirmed=correct(before,calc,f,snapshot,code(reason,false),correction(c),now,new Access(actor,Set.of(Permission.CORRECT),Set.of(ctx.organizationCode())));
        }
        freezeVersion("RULE",map(c.get("rule")));freezeVersion("RATE",map(c.get("rate")));if(c.get("correction")!=null)freezeVersion("CORRECTION",map(c.get("correction")));
        Map<String,Object> frozen=new LinkedHashMap<>();frozen.put("configuration",c);frozen.put("fact_revision",f.revision());frozen.put("dispatch_id",ctx.dispatchId());frozen.put("project_id",ctx.projectId());frozen.put("teacher_id",ctx.teacherId());frozen.put("organization_code",ctx.organizationCode());frozen.put("service_date",ctx.serviceDate().toString());frozen.put("dimensions",dimensionsMap(f.dimensions()));frozen.put("actor_code",actor);frozen.put("account_id",session.uid);frozen.put("created_at",now.toString());frozen.put("claim_revision",claimVersion);frozen.put("approved_claim",claim);frozen.put("settings_version",settings.get("version"));frozen.put("execution_settings",settings);
        Map<String,Object> claimRow=Db.one("SELECT payload FROM m05_fee_claims WHERE dispatch_id=? AND version=?",ctx.dispatchId(),claimVersion);
        if(claimRow==null)fail(409,"核准申报版本不存在");frozen.put("claim_audit",json(text(claimRow,"payload")));
        Db.exec("INSERT INTO m05_settlement_configs(version_code,dispatch_id,fact_revision,previous_version,payload,actor_code,account_id,created_at) VALUES(?,?,?,?,?,?,?,?)",configCode,ctx.dispatchId(),f.revision(),ctx.configVersion(),Json.write(frozen),actor,session.uid,now.toString());
        Map<String,Object> p=new LinkedHashMap<>(),calculationFact=factMap(f);calculationFact.put("conversion",ctx.factPayload().get("conversion"));
        p.put("snapshot_code",snapshot);p.put("previous_snapshot_code",previous);p.put("fact",calculationFact);p.put("source_fact",ctx.factPayload());p.put("configuration",frozen);p.put("engine_version",confirmed.calculation().engineVersion());p.put("actor_code",actor);p.put("account_id",session.uid);p.put("confirmed_at",now.toString());p.put("reason_code",confirmed.reasonCode());p.put("unrounded_amount",calc.unroundedAmount().toPlainString());p.put("amount",calc.amount().toPlainString());p.put("settlement_amount",confirmed.settlementAmount().toPlainString());
        Map<String,Object> contribution=reportingContribution(confirmed);p.put("ledger",contribution);
        Db.exec("INSERT INTO m05_settlement_snapshots(snapshot_code,dispatch_id,fact_revision,previous_snapshot_code,payload,ledger,payment_status) VALUES(?,?,?,?,?,?,?)",snapshot,ctx.dispatchId(),f.revision(),previous,Json.write(p),Json.write(contribution),"UNPAID");
        cas("UPDATE m05_delivery_facts SET config_version=?,head_snapshot_code=? WHERE dispatch_id=? AND revision=? AND config_version IS NOT DISTINCT FROM ? AND head_snapshot_code IS NOT DISTINCT FROM ?",configCode,snapshot,ctx.dispatchId(),f.revision(),ctx.configVersion(),previous);
        Map<String,Object> total=financialTotal(ctx,f,calc,c);
        if(correcting)DeliverySettlementLedger.replace("DISPATCH-"+ctx.dispatchId(),previous,total,snapshot,reviewEvidence);
        else DeliverySettlementLedger.open(ctx.projectId(),ctx.organizationCode(),"DISPATCH-"+ctx.dispatchId(),total,snapshot,"CONFIRMED",reviewEvidence);
    }
    private static Map<String,Object> financialTotal(WorkflowContext ctx,Fact fact,Calculation calc,Map<String,Object> configuration) {
        Map<String,Object> total=new LinkedHashMap<>();total.put("activity","TEACHING");total.put("source_record_id",ctx.dispatchId());total.put("source_code","DISPATCH-"+ctx.dispatchId());total.put("service_date",fact.serviceDate().toString());total.put("organization_code",ctx.organizationCode());total.put("teacher_id",ctx.teacherId());total.put("teacher_code",null);total.put("system_teacher_code",fact.instructorCode());total.put("course_id",null);total.put("course_code",null);total.put("amount",calc.amount().toPlainString());
        Map<String,Object> hours=new LinkedHashMap<>();hours.put("ESTIMATED",decimalText(fact.hours().estimated()));hours.put("PLANNED",decimalText(fact.hours().planned()));hours.put("ACTUAL",decimalText(fact.hours().actual()));hours.put("PAYABLE",decimalText(fact.hours().payable()));total.put("hours",hours);total.put("policy_version",calc.rule().versionCode());total.put("rate_version",calc.rate().versionCode());total.put("payroll_month",YearMonth.from(fact.serviceDate()).plusMonths(1).toString());total.put("source_fact_revision",fact.revision());return total;
    }
    static Map<String,Object> workflowPayment(Auth.Session session,WorkflowContext expected,String expectedHead,String date,String amount,String evidence) throws Exception {
        WorkflowContext ctx=workflowContext(session,expected.dispatchId(),"settlement.pay",true);sameWorkflowContext(expected,ctx);
        if(!Objects.equals(expectedHead,ctx.snapshotCode()))fail(409,"支付依据已更新，请刷新");
        String chain="DISPATCH-"+ctx.dispatchId();Map<String,Object> balance=DeliverySettlementLedger.balance(chain);
        if(balance==null||!expectedHead.equals(balance.get("current_entry_code")))fail(409,"已存在独立调整事项，请从调整事项核对余额");
        if(DeliverySettlementLedger.money(amount).compareTo(DeliverySettlementLedger.money(balance.get("balance")))!=0)fail(409,"本次须核对全部未付税前余额；分次或退款从调整事项办理");
        Map<String,Object> p=DeliverySettlementLedger.payment(chain,expectedHead,date,amount,code(evidence,false),OrganizationAccessStore.person(session).personCode(),session.uid,false);
        Db.exec("UPDATE m05_settlement_snapshots SET payment_status='PAID',payment_date=? WHERE dispatch_id=? AND payment_status='UNPAID' AND payment_date IS NULL",date,ctx.dispatchId());return p;
    }
    static Map<String,Object> workflowFinancial(WorkflowContext ctx) throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();String chain="DISPATCH-"+ctx.dispatchId();Map<String,Object> h=DeliverySettlementLedger.balance(chain);
        out.put("head_snapshot_code",ctx.snapshotCode());out.put("current_entry_code",h==null?null:h.get("current_entry_code"));out.put("amount",h==null?null:h.get("current_amount"));out.put("paid",h!=null&&new BigDecimal(h.get("paid_amount").toString()).signum()!=0);out.put("balance",h==null?null:h.get("balance"));out.put("chain",h);
        List<Map<String,Object>> payments=h==null?List.of():DeliverySettlementLedger.entries("m05_payment_events",chain);out.put("payment",payments.isEmpty()?null:payments.get(payments.size()-1));return out;
    }
    private static void sameWorkflowContext(WorkflowContext a,WorkflowContext b) throws Exception {
        if(a.dispatchId()!=b.dispatchId()||a.projectId()!=b.projectId()||a.teacherId()!=b.teacherId()||a.factVersion()!=b.factVersion()||!Objects.equals(a.organizationCode(),b.organizationCode())||!Objects.equals(a.serviceDate(),b.serviceDate())||!Objects.equals(a.configVersion(),b.configVersion())||!Objects.equals(a.snapshotCode(),b.snapshotCode())||!Objects.equals(a.factPayload()==null?null:canonical(a.factPayload()),b.factPayload()==null?null:canonical(b.factPayload())))fail(409,"授课或结算来源已变化，请刷新");
    }
    /** Only immutable financial events, with independent accrual and payment dates. */
    public static List<Long> financialProjectIds(Auth.Session session,String organizationCode) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(session);String org=code(organizationCode,false);
            if(!OrganizationAccessStore.authorize(session,"reports.read",OrganizationAccess.Action.VIEW,org).allowed())fail(403,"没有本机构财务报表权限");
            String sql="SELECT a.project_id FROM workflow_acceptances a JOIN workflow_demands w ON w.demand_id=a.demand_id WHERE w.organization_code=? UNION SELECT project_id FROM m05_financial_chains WHERE organization_code=?";
            Map<String,Object> count=Db.one("SELECT COUNT(*) AS n FROM ("+sql+") m05_projects",org,org);
            if(integer(count.get("n"),true,"财务项目数")>10000)fail(409,"财务项目超过核验容量，请缩小范围并联系管理员核对");
            List<Long> ids=new ArrayList<>();for(Map<String,Object> row:Db.query(sql+" ORDER BY project_id",org,org))ids.add(integer(row.get("project_id"),false,"项目"));return ids;
        }
    }
    public static Map<String,Object> financialSource(Auth.Session session,long projectId,boolean export) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(session);Map<String,Object> chain=summaryProjectChain(projectId);String org=code(chain.get("organization_code"),false);
            if(!OrganizationAccessStore.authorize(session,"reports.read",OrganizationAccess.Action.VIEW,org).allowed()||(export&&!OrganizationAccessStore.authorize(session,"reports.export",OrganizationAccess.Action.EXPORT,org).allowed()))fail(403,"没有本机构财务报表权限");
            for(Map<String,Object> row:Db.query("SELECT DISTINCT s.dispatch_id FROM m05_settlement_snapshots s JOIN m05_delivery_facts f ON f.dispatch_id=s.dispatch_id WHERE f.project_id=?",projectId))if(DeliverySettlementLedger.head("DISPATCH-"+row.get("dispatch_id"))==null)fail(409,"存在尚未核对接入财务账的历史课酬，不能输出不完整合计");
            Map<String,Object> result=DeliverySettlementLedger.project(projectId,org);
            for(Map<String,Object> stored:Db.query("SELECT DISTINCT organization_code FROM m05_financial_chains WHERE project_id=?",projectId))if(!org.equals(stored.get("organization_code")))fail(409,"项目组织与冻结财务账不一致，请先核对来源迁移");
            return DeliverySettlementCoding.project(projectId,org,result);
        }
    }

    /** Authorized project source for audited coding; this does not grant catalog or confirmation rights. */
    static Map<String,Object> codingProjectSource(Auth.Session session,long projectId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(session);
            Map<String,Object> source=summaryProjectChain(projectId);
            String org=code(source.get("organization_code"),false);
            if(!OrganizationAccessStore.authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,org).allowed())
                fail(403,"没有本机构课酬编码读取权限");
            return new LinkedHashMap<>(source);
        }
    }

    private record Source(long id,long project,long teacher,String org,LocalDate date,String projectStatus,String dispatchStatus,String teacherStatus) {}
    private static Source source(long id) throws Exception {
        Map<String,Object> r=Db.one("SELECT d.id,d.project_id,d.teacher_id,d.teach_date,d.status AS dispatch_status,p.demand_id,p.status AS project_status,t.status AS teacher_status FROM dispatches d JOIN projects p ON p.id=d.project_id JOIN teachers t ON t.id=d.teacher_id WHERE d.id=?",id);
        if(r==null) fail(404,"排课或关联项目、讲师不存在");
        Map<String,Object> w=Db.one("SELECT a.demand_id,w.organization_code FROM workflow_acceptances a JOIN workflow_demands w ON w.demand_id=a.demand_id WHERE a.project_id=?",r.get("project_id"));
        if(w==null) fail(409,"旧项目尚未登记可信组织来源，需显式迁移后接入");
        if(!Objects.equals(String.valueOf(w.get("demand_id")),String.valueOf(r.get("demand_id")))) fail(409,"项目与受理需求关联不一致");
        return new Source(id,integer(r.get("project_id"),false,"project_id"),integer(r.get("teacher_id"),false,"teacher_id"),code(w.get("organization_code"),false),optionalDate(r.get("teach_date")),text(r,"project_status"),text(r,"dispatch_status"),text(r,"teacher_status"));
    }
    private static Map<String,Object> checkedFact(Source src) throws Exception {
        Map<String,Object> row=Db.one("SELECT * FROM m05_delivery_facts WHERE dispatch_id=?",src.id());
        if(row!=null) {
            Fact f=fact(json(text(row,"payload")));
            if(integer(row.get("project_id"),false,"project_id")!=src.project()||integer(row.get("teacher_id"),false,"teacher_id")!=src.teacher()||!src.org().equals(text(row,"organization_code"))||!src.org().equals(f.organizationCode())||!Objects.equals(src.date(),f.serviceDate())||!f.instructorCode().equals("TEACHER-"+src.teacher())||f.recordId()!=src.id()||f.revision()!=integer(row.get("revision"),false,"revision")) fail(409,"授课来源关联或日期已变化，请先核对迁移，不能覆盖历史");
        }
        return row;
    }
    private static Map<String,Object> requiredFact(Source src) throws Exception { Map<String,Object> r=checkedFact(src); if(r==null) fail(409,"请先保存授课事实"); return r; }
    private static Map<String,Object> config(Map<String,Object> row) throws Exception {
        if(row.get("config_version")==null) return null;
        Map<String,Object> cfg=Db.one("SELECT * FROM m05_settlement_configs WHERE version_code=? AND dispatch_id=?",row.get("config_version"),row.get("dispatch_id"));
        if(cfg==null) fail(409,"正式配置关联缺失"); return json(text(cfg,"payload"));
    }
    private static Map<String,Object> detail(Source src,Auth.Session session) throws Exception {
        Map<String,Object> r=checkedFact(src), out=new LinkedHashMap<>();
        out.put("dispatch_id",src.id()); out.put("project_id",src.project()); out.put("teacher_id",src.teacher()); out.put("organization_code",src.org());
        out.put("version",r==null?0:r.get("revision"));
        Map<String,Object> p=r==null?null:json(text(r,"payload")); out.put("fact",p); out.put("conversion",p==null?null:p.get("conversion"));
        out.put("config_version",r==null?null:r.get("config_version")); out.put("configuration",r==null?null:config(r)); out.put("head_snapshot_code",r==null?null:r.get("head_snapshot_code"));
        List<Map<String,Object>> snapshots=new ArrayList<>();
        for(Map<String,Object> row:Db.query("SELECT * FROM m05_settlement_snapshots WHERE dispatch_id=? ORDER BY id",src.id())) {
            Map<String,Object> snapshot=json(text(row,"payload")); snapshot.put("id",row.get("id")); snapshot.put("payment_status",row.get("payment_status")); snapshot.put("payment_date",row.get("payment_date")); snapshots.add(snapshot);
        }
        out.put("snapshots",snapshots); out.put("teacher_code_kind","SYSTEM_TEACHER_ID");
        out.put("capabilities",capabilities(session,src,r)); return out;
    }
    private static OrganizationAccess.Person authorize(Auth.Session s,String resource,OrganizationAccess.Action action,Source source) throws Exception {
        OrganizationAccess.Person actor=OrganizationAccessStore.person(s);
        if(!OrganizationAccessStore.authorize(s,resource,action,source.org()).allowed()) fail(403,"没有该机构范围内的操作权限");
        return actor;
    }
    /** Advisory UI capabilities; mutations still authorize and validate again in the transaction. */
    private static Map<String,Object> capabilities(Auth.Session session,Source src,Map<String,Object> row) throws Exception {
        OrganizationAccessStore.person(session);
        boolean write=OrganizationAccessStore.authorize(session,"delivery.write",OrganizationAccess.Action.HANDLE,src.org()).allowed();
        boolean verify=OrganizationAccessStore.authorize(session,"delivery.verify",OrganizationAccess.Action.HANDLE,src.org()).allowed();
        List<Map<String,String>> saveReasons=new ArrayList<>(),verifyReasons=new ArrayList<>(),completeReasons=new ArrayList<>();
        if(!write) reason(saveReasons,"MISSING_PERMISSION","没有本机构授课记录的保存权限");
        if(!verify) { reason(verifyReasons,"MISSING_PERMISSION","没有本机构授课记录的核对权限"); reason(completeReasons,"MISSING_PERMISSION","没有本机构课程完成的核对权限"); }
        try { saveReady(src); } catch(Api.ApiException e) { reason(saveReasons,"SOURCE_NOT_EDITABLE",e.getMessage()); }
        try { deliveryReady(src,true); } catch(Api.ApiException e) {
            reason(verifyReasons,"SOURCE_NOT_READY",e.getMessage()); reason(completeReasons,"SOURCE_NOT_READY",e.getMessage());
        }
        if(row==null) {
            reason(verifyReasons,"FACT_MISSING","请先保存实际授课记录"); reason(completeReasons,"FACT_MISSING","请先保存并核对实际授课记录");
        } else {
            Map<String,Object> p=json(text(row,"payload")); Fact f=fact(p);
            if(!actualComplete(p,f)) {
                reason(verifyReasons,"ACTUAL_MINUTES_MISSING","请先保存完整、有效的实际授课分钟"); reason(completeReasons,"ACTUAL_MINUTES_MISSING","实际授课分钟尚未完整记录");
            }
            if(f.verification()==null) reason(completeReasons,"TEACHING_NOT_VERIFIED","当前授课记录尚未核对");
        }
        if("已完成".equals(src.dispatchStatus())) reason(completeReasons,"ALREADY_COMPLETED","课程已完成，无需重复标记");
        Map<String,Object> c=new LinkedHashMap<>(); c.put("current_server",true); c.put("fact_version",row==null?0:row.get("revision"));
        c.put("permissions",Map.of("save",write,"verify",verify,"complete",verify));
        c.put("can_save",saveReasons.isEmpty()); c.put("can_verify",verifyReasons.isEmpty()); c.put("can_complete",completeReasons.isEmpty());
        c.put("reasons",Map.of("save",saveReasons,"verify",verifyReasons,"complete",completeReasons)); return c;
    }
    private static void reason(List<Map<String,String>> list,String code,String message) { list.add(Map.of("code",code,"message",message)); }
    private static void saveReady(Source src) throws Exception {
        if(!oneOf(src.projectStatus(),"待启动","进行中")) fail(409,"项目已完成或归档，不能变更授课事实");
        if(src.date()==null) fail(409,"请先在原排课补齐授课日期，再记录授课事实");
        if("已拒绝".equals(src.dispatchStatus())) fail(409,"师资已拒绝的排课不能记录授课事实");
    }
    /** Verify the stored minute evidence and its independent actual-hour projection agree. */
    private static boolean actualComplete(Map<String,Object> payload,Fact fact) {
        if(fact.hours().actual()==null||!(payload.get("conversion") instanceof Map<?,?> c)) return false;
        if(!(c.get("minutes") instanceof String)||!(c.get("class_hours") instanceof String)
                ||!"READY".equals(c.get("status"))||!storedInteger(c.get("minutes_per_class_hour"),45)
                ||!"HALF_UP".equals(c.get("class_hour_rounding"))||!storedInteger(c.get("class_hour_scale"),2)) return false;
        try {
            DeliverySettlementHours.Conversion converted=DeliverySettlementHours.convert(c.get("minutes"),DeliverySettlementHours.currentUserRule());
            return converted.status()==DeliverySettlementHours.Status.READY
                    &&converted.classHours().compareTo(fact.hours().actual())==0
                    &&converted.classHours().compareTo(decimalInput(c.get("class_hours")))==0;
        } catch(IllegalArgumentException|IllegalStateException e) { return false; }
    }
    private static boolean oneOf(String value,String... candidates) { for(String candidate:candidates) if(candidate.equals(value)) return true; return false; }
    private static boolean storedInteger(Object value,int expected) { return value instanceof Number n&&n.doubleValue()==expected; }
    private static void deliveryReady(Source src,boolean edit) throws Exception {
        if(edit&&!oneOf(src.projectStatus(),"待启动","进行中")) fail(409,"项目已完成或归档，不能更改交付核对");
        if(!oneOf(src.projectStatus(),"待启动","进行中","已完成")) fail(409,"项目当前状态不支持结算或交付核对");
        if(!oneOf(src.dispatchStatus(),"已确认","已完成")) fail(409,"只有师资已确认的课程才能核对或结算");
        if(!"在库".equals(src.teacherStatus())) fail(409,"讲师不在库，请先核对授课资格");
        if(src.date()==null||src.date().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) fail(409,"授课日期缺失或尚未到达");
    }
    private static void expectedConfig(Map<String,Object> row,Map<String,Object> b) throws Exception {
        if(!b.containsKey("expected_config_version")||!Objects.equals(row.get("config_version"),b.get("expected_config_version"))) fail(409,"配置版本已变化，请刷新后重试");
    }

    /** Parent must call before legacy dispatch-complete's existing-success shortcut, inside its lock/transaction. */
    public static void requireCompletion(Auth.Session s,long dispatchId,long expectedVersion) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(s); Source src=source(dispatchId); authorize(s,"delivery.verify",OrganizationAccess.Action.HANDLE,src); deliveryReady(src,true);
            Map<String,Object> p=json(text(requiredFact(src),"payload")); Fact f=fact(p);
            if(f.revision()!=expectedVersion||f.verification()==null||!actualComplete(p,f)) fail(409,"授课事实已变化、分钟不完整或尚未核对");
        }
    }
    /** Read-only legacy gate. Call under Api.MUTATION_LOCK before ANY shortcut/write; does not open a transaction. */
    public static void guardLegacyMutation(String mod,long id,Map<String,Object> body) throws Exception {
        if(body==null) body=Map.of();
        synchronized(Api.MUTATION_LOCK) {
            if(mod.equals("fees/calc")) { if(controlled(id)) fail(409,"本项目须按已核对授课事实及正式规则结算，旧费率计算已停用"); return; }
            if(Set.of("fees","fees/delete","fees/pay").contains(mod)) {
                Map<String,Object> old=id>0?Db.one("SELECT project_id FROM fees WHERE id=?",id):null;
                if(old!=null&&controlled(integer(old.get("project_id"),false,"project_id"))) fail(409,"受控项目课酬不能由旧入口写入、删除或支付");
                if(body.containsKey("project_id")&&controlled(integer(body.get("project_id"),false,"project_id"))) fail(409,"受控项目课酬须使用结算快照"); return;
            }
            if(mod.equals("dispatches")||mod.equals("dispatches/delete")) {
                Map<String,Object> old=id>0?Db.one("SELECT * FROM dispatches WHERE id=?",id):null;
                boolean hasFact=id>0&&Db.one("SELECT dispatch_id FROM m05_delivery_facts WHERE dispatch_id=?",id)!=null;
                if(hasFact) {
                    if(mod.endsWith("delete")) fail(409,"已留存授课事实的排课不能删除");
                    for(String key:List.of("project_id","teacher_id","teach_date")) if(body.containsKey(key)&&!sameLegacy(key,body.get(key),old.get(key))) fail(409,"已留存授课事实的关联和日期不能由旧入口修改");
                    if(body.containsKey("status")&&!Objects.equals(body.get("status"),old.get("status"))) fail(409,"已有授课事实的状态需专用流程处理");
                }
                boolean scoped=old!=null&&controlled(integer(old.get("project_id"),false,"project_id"));
                if(body.containsKey("project_id")) scoped|=controlled(integer(body.get("project_id"),false,"project_id"));
                if(scoped&&"已完成".equals(body.get("status"))&&(old==null||!"已完成".equals(old.get("status")))) fail(409,"课程完成须核对当前实际授课事实");
            }
        }
    }
    public static boolean controlled(long projectId) throws SQLException {
        return Db.one("SELECT project_id FROM workflow_acceptances WHERE project_id=?",projectId)!=null||Db.one("SELECT project_id FROM m05_delivery_facts WHERE project_id=?",projectId)!=null;
    }
    /** Exact current reviewed hours for original project summaries; unknown quantities stay null. */
    public static Map<String,Object> projectHours(Auth.Session session,long projectId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(session);
            Map<String,Object> w=Db.one("SELECT a.demand_id,w.organization_code,p.demand_id AS project_demand FROM workflow_acceptances a JOIN workflow_demands w ON w.demand_id=a.demand_id JOIN projects p ON p.id=a.project_id WHERE a.project_id=?",projectId);
            if(w==null) fail(409,"旧项目尚未接入可信组织来源");
            if(!Objects.equals(w.get("demand_id"),w.get("project_demand"))) fail(409,"项目关联不一致");
            if(!OrganizationAccessStore.authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,code(w.get("organization_code"),false)).allowed()) fail(403,"无项目授课查看权限");
            List<Map<String,Object>> rows=Db.query("SELECT id FROM dispatches WHERE project_id=? AND (status IS NULL OR status<>'已拒绝') ORDER BY id",projectId);
            BigDecimal estimated=BigDecimal.ZERO,planned=BigDecimal.ZERO,actual=BigDecimal.ZERO,payable=BigDecimal.ZERO; int reviewed=0, pending=0, unverified=0;
            for(Map<String,Object> d:rows) {
                Source src=source(integer(d.get("id"),false,"id")); Map<String,Object> row=checkedFact(src);
                if(row==null) { estimated=null; planned=null; if("已完成".equals(src.dispatchStatus())) { actual=null; payable=null; unverified++; } else pending++; continue; }
                Fact f=fact(json(text(row,"payload"))); Hours h=f.hours(); estimated=addKnown(estimated,h.estimated()); planned=addKnown(planned,h.planned());
                boolean complete="已完成".equals(src.dispatchStatus())&&f.verification()!=null&&f.serviceDate()!=null&&!f.serviceDate().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")));
                if(complete) { reviewed++; actual=addKnown(actual,h.actual()); payable=addKnown(payable,h.payable()); }
                else if("已完成".equals(src.dispatchStatus())) { unverified++; actual=null; payable=null; } else pending++;
            }
            Map<String,Object> out=new LinkedHashMap<>(); out.put("dispatch_count",rows.size()); out.put("reviewed_completed_count",reviewed); out.put("pending_count",pending); out.put("unverified_completed_count",unverified);
            out.put("estimated",decimalText(estimated)); out.put("planned",decimalText(planned)); out.put("actual",decimalText(actual)); out.put("payable",decimalText(payable));
            out.put("payment_integration","NOT_CONFIGURED"); return out;
        }
    }
    /** M08-only compact read source. Requires independent delivery.read; never projects money or identities. */
    public static Map<String,Object> summaryDeliverySource(Auth.Session session,long projectId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccess.Person actor=OrganizationAccessStore.person(session);
            var cfg=OrganizationAccessStore.configuration();
            if(projectId<=0||projectId>9007199254740991L) fail(400,"项目编号无效");
            Map<String,Object> chain=summaryProjectChain(projectId);
            String org=code(chain.get("organization_code"),false);
            if(!OrganizationAccessStore.authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,org).allowed()) fail(403,"无项目授课查看权限");
            LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai")); Instant now=Instant.now();
            if(Db.one("SELECT f.dispatch_id FROM m05_delivery_facts f LEFT JOIN dispatches d ON d.id=f.dispatch_id WHERE f.project_id=? AND (d.id IS NULL OR d.project_id IS DISTINCT FROM f.project_id) FETCH FIRST 1 ROW ONLY",projectId)!=null) fail(409,"已有授课事实的排课关联缺失或跨项目变化");
            List<Map<String,Object>> rows=Db.query("SELECT d.id,d.project_id,d.teacher_id,d.teach_date,d.status,t.id AS linked_teacher,t.status AS teacher_status FROM dispatches d LEFT JOIN teachers t ON t.id=d.teacher_id WHERE d.project_id=? ORDER BY d.id FETCH FIRST 1001 ROWS ONLY",projectId);
            if(rows.size()>1000) fail(409,"总结授课来源超过1000条，请先核对项目范围");
            BigDecimal estimated=BigDecimal.ZERO,planned=BigDecimal.ZERO,actual=BigDecimal.ZERO,payable=BigDecimal.ZERO;
            int rejected=0,reviewed=0,pending=0,unverified=0,facts=0,missing=0,verified=0;
            long payloadChars=0; List<String> versions=new ArrayList<>();
            for(Map<String,Object> d:rows) {
                long id=integer(d.get("id"),false,"排课编号");
                // Length guard before materializing CLOBs; independent of HTTP input budgets.
                Map<String,Object> limits=Db.one("SELECT CHAR_LENGTH(f.payload) AS fact_chars,CHAR_LENGTH(r.payload) AS revision_chars FROM m05_delivery_facts f LEFT JOIN m05_fact_revisions r ON r.dispatch_id=f.dispatch_id AND r.revision=f.revision WHERE f.dispatch_id=?",id);
                if(limits!=null) {
                    long a=integer(limits.get("fact_chars"),true,"事实长度"),b=limits.get("revision_chars")==null?0:integer(limits.get("revision_chars"),true,"修订长度");
                    payloadChars+=a+b; if(a>32000||b>32000||payloadChars>8L*1024*1024) fail(409,"授课来源超过核验容量上限");
                }
                Map<String,Object> row=Db.one("SELECT f.project_id,f.teacher_id,f.organization_code,f.revision,f.payload,r.payload AS revision_payload,r.event_type,r.actor_code,r.account_id,r.created_at FROM m05_delivery_facts f LEFT JOIN m05_fact_revisions r ON r.dispatch_id=f.dispatch_id AND r.revision=f.revision WHERE f.dispatch_id=?",id);
                Fact fact=null;
                try {
                    if(row!=null) {
                        Map<String,Object> p=json(text(row,"payload")); fact=fact(p);
                        LocalDate date=optionalDate(d.get("teach_date"));
                        if(d.get("linked_teacher")==null||integer(row.get("project_id"),false,"项目")!=projectId||integer(row.get("teacher_id"),false,"讲师")!=integer(d.get("teacher_id"),false,"讲师")
                                ||!org.equals(row.get("organization_code"))||!org.equals(fact.organizationCode())||!Objects.equals(date,fact.serviceDate())
                                ||fact.recordId()!=id||fact.revision()!=integer(row.get("revision"),false,"修订")||!("DISPATCH-"+id).equals(fact.sessionCode())
                                ||!("TEACHER-"+integer(d.get("teacher_id"),false,"讲师")).equals(fact.instructorCode())||fact.dataMode()!=DataMode.CONFIGURED
                                ||fact.dimensions()==null||!"CLASS45".equals(fact.dimensions().hourUnitCode())) fail(409,"授课来源关联或正式事实标记无效");
                        Map<String,Object> h=map(p.get("hours"));
                        if(!h.keySet().equals(Set.of("estimated","planned","actual","payable"))) fail(409,"授课课时字段不完整");
                        for(Object value:h.values()) if(value!=null&&(!(value instanceof String s)||!s.matches("[0-9]{1,24}(?:\\.[0-9]{1,8})?"))) fail(409,"授课课时必须为有效十进制文本");
                        if(row.get("revision_payload")==null||!canonical(p).equals(canonical(json(text(row,"revision_payload"))))) fail(409,"授课事实与保存修订不一致");
                        Map<String,Object> count=Db.one("SELECT COUNT(*) AS n,MAX(revision) AS last_revision FROM m05_fact_revisions WHERE dispatch_id=?",id);
                        if(integer(count.get("n"),false,"修订数")!=fact.revision()||integer(count.get("last_revision"),false,"最新修订")!=fact.revision()) fail(409,"授课事实修订链不完整");
                        code(row.get("actor_code"),false);integer(row.get("account_id"),false,"保存账号");
                        Instant saved=Instant.parse(text(row,"created_at")); if(saved.isAfter(now)) fail(409,"授课修订时间无效");
                        if(fact.hours().actual()!=null&&!summaryConversion(p,fact)) fail(409,"实际课时与分钟换算依据不一致");
                        if(fact.hours().actual()==null&&p.get("conversion")!=null) fail(409,"实际分钟与缺失课时不一致");
                        if(fact.verification()!=null) {
                            Verification v=fact.verification();
                            if(!"VERIFY".equals(row.get("event_type"))||!v.actorCode().equals(row.get("actor_code"))||v.checkedAt().isAfter(now)||saved.isBefore(v.checkedAt())||!summaryConversion(p,fact)) fail(409,"核对记录与可信VERIFY修订不一致");
                        } else if(!"SAVE".equals(row.get("event_type"))) fail(409,"核对状态与授课修订不一致");
                    } else if(Db.one("SELECT revision FROM m05_fact_revisions WHERE dispatch_id=? FETCH FIRST 1 ROW ONLY",id)!=null) fail(409,"授课事实头缺失");
                } catch(Api.ApiException invalid) { throw new Api.ApiException(409,"授课事实、核对修订或换算依据无法核实"); }
                  catch(RuntimeException invalid) { throw new Api.ApiException(409,"授课事实、核对修订或换算依据无法核实"); }
                // Hash a whitelist plus current evidence privately; no per-person values/hashes are returned.
                Map<String,Object> fingerprint=new LinkedHashMap<>();fingerprint.put("dispatch",d);fingerprint.put("fact_revision",row);
                versions.add(summaryDigest(canonical(fingerprint)));
                if("已拒绝".equals(d.get("status"))) {rejected++;continue;}
                if(fact==null) {missing++;estimated=null;planned=null;}
                else {facts++;estimated=addKnown(estimated,fact.hours().estimated());planned=addKnown(planned,fact.hours().planned());if(fact.verification()!=null)verified++;}
                if(!"已完成".equals(d.get("status"))) {pending++;continue;}
                if(fact!=null&&fact.verification()!=null&&fact.serviceDate()!=null&&!fact.serviceDate().isAfter(today)) {
                    reviewed++;actual=addKnown(actual,fact.hours().actual());payable=addKnown(payable,fact.hours().payable());
                } else {unverified++;actual=null;payable=null;}
            }
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("dispatch_count",rows.size()-rejected);value.put("reviewed_completed_count",reviewed);value.put("pending_count",pending);value.put("unverified_completed_count",unverified);
            value.put("rejected_count",rejected);value.put("total_dispatch_count",rows.size());value.put("fact_count",facts);value.put("missing_fact_count",missing);value.put("verified_count",verified);
            value.put("estimated",decimalText(estimated));value.put("planned",decimalText(planned));value.put("actual",decimalText(actual));value.put("payable",decimalText(payable));
            Map<String,Object> fingerprint=new LinkedHashMap<>();fingerprint.put("policy","M08-M05-SOURCE-20260923-1");fingerprint.put("project",chain);fingerprint.put("today",today.toString());fingerprint.put("records",versions);
            Map<String,Object> out=new LinkedHashMap<>();out.put("status","AVAILABLE");out.put("reason",rows.isEmpty()?"NO_DISPATCHES":null);out.put("policy_version","M08-M05-SOURCE-20260923-1");
            out.put("source_version",summaryDigest(canonical(fingerprint)));out.put("as_of_date",today.toString());out.put("value",value);
            out.put("coverage",Map.of("estimated_planned","ALL_EFFECTIVE_DISPATCHES","actual_payable","REVIEWED_COMPLETED_AS_OF_DATE","unknown","NULL_PROPAGATES","hour_unit","CLASS45"));
            if(!actor.equals(OrganizationAccessStore.person(session))||!cfg.equals(OrganizationAccessStore.configuration())
                    ||!OrganizationAccessStore.authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,org).allowed()
                    ||!canonical(chain).equals(canonical(summaryProjectChain(projectId)))||!today.equals(LocalDate.now(ZoneId.of("Asia/Shanghai")))) fail(409,"授课来源或当前权限已变化");
            return out;
        }
    }
    private static Map<String,Object> summaryProjectChain(long project) throws Exception {
        List<Map<String,Object>> rows=Db.query("SELECT p.id,p.demand_id AS project_demand,p.status,a.demand_id,a.data_revision AS accepted_revision,a.team_code,a.actor_code,a.created_at,w.organization_code,w.data_revision,w.version,w.draft FROM projects p JOIN workflow_acceptances a ON a.project_id=p.id JOIN workflow_demands w ON w.demand_id=a.demand_id WHERE p.id=?",project);
        if(rows.size()!=1) {fail(409,"缺少唯一可信项目来源");return null;}
        Map<String,Object> r=rows.get(0);
        if(integer(r.get("project_demand"),false,"需求")!=integer(r.get("demand_id"),false,"受理需求")||integer(r.get("accepted_revision"),false,"受理修订")!=integer(r.get("data_revision"),false,"当前修订")||!Boolean.FALSE.equals(r.get("draft"))) fail(409,"项目受理来源不一致");
        integer(r.get("version"),false,"需求版本");code(r.get("organization_code"),false);code(r.get("team_code"),false);code(r.get("actor_code"),false);Instant.parse(text(r,"created_at"));
        return r;
    }
    private static boolean summaryConversion(Map<String,Object> p,Fact fact) throws Exception {
        if(!actualComplete(p,fact))return false;
        Map<String,Object> c=map(p.get("conversion"));
        return DeliverySettlementHours.CURRENT_VERSION.equals(c.get("rule_version"))&&DeliverySettlementHours.USER_EVIDENCE.equals(c.get("evidence_code"))&&DeliverySettlementHours.ROUNDING_DECISION.equals(c.get("rounding_decision_code"));
    }
    private static String summaryDigest(String value) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    /** Missing remuneration is never zero. The legacy archive gate now checks every actual financial chain. */
    public static void guardLegacyArchive(long projectId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if(!controlled(projectId))return;
            Map<String,Object> chain=summaryProjectChain(projectId);String org=code(chain.get("organization_code"),false);
            for(Map<String,Object> dispatch:Db.query("SELECT id,status FROM dispatches WHERE project_id=? ORDER BY id",projectId)) {
                if("已拒绝".equals(dispatch.get("status")))continue;long id=integer(dispatch.get("id"),false,"排课");
                if(!"已完成".equals(dispatch.get("status")))fail(409,"仍有未完成的授课，不能结清归档");
                Source src=source(id);Map<String,Object> row=requiredFact(src),payload=json(text(row,"payload"));Fact f=fact(payload);
                if(f.verification()==null||!actualComplete(payload,f))fail(409,"仍有未核对的授课，不能结清归档");
                Map<String,Object> financial=DeliverySettlementLedger.head("DISPATCH-"+id);
                if(financial==null)fail(409,"仍有未核准课酬或免付结论的授课，不能把缺少应付单当作零");
                Map<String,Object> total=map(financial.get("total"));
                if(total.get("source_fact_revision")==null||integer(total.get("source_fact_revision"),false,"财务对应授课版本")!=f.revision())fail(409,"授课事实在课酬核准后已变化，请先完成更正核准");
            }
            Map<String,Object> financial=DeliverySettlementLedger.project(projectId,org);
            for(Object value:(List<?>)financial.get("chain_heads"))if(new BigDecimal(map(value).get("balance").toString()).signum()!=0)fail(409,"仍有待付或待退金额，请先核对实际支付后归档");
            DeliverySettlementCases.requireProjectSettled(projectId);
        }
    }

    /** Desktop project overview: known totals are explicit; missing claims do not become zero. */
    public static Map<String,Object> projectSettlement(Auth.Session session,long projectId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            OrganizationAccessStore.person(session);Map<String,Object> chain=summaryProjectChain(projectId);String org=code(chain.get("organization_code"),false);
            if(!OrganizationAccessStore.authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,org).allowed())fail(403,"没有该机构课酬读取权限");
            Map<String,Object> all=DeliverySettlementLedger.project(projectId,org);BigDecimal due=new BigDecimal("0.00"),paid=due;int missing=0;
            for(Object value:(List<?>)all.get("chain_heads")){Map<String,Object> h=map(value);due=due.add(new BigDecimal(h.get("current_amount").toString()));paid=paid.add(new BigDecimal(h.get("paid_amount").toString()));}
            for(Map<String,Object> d:Db.query("SELECT id,status FROM dispatches WHERE project_id=?",projectId))if(!"已拒绝".equals(d.get("status"))&&DeliverySettlementLedger.head("DISPATCH-"+integer(d.get("id"),false,"排课"))==null)missing++;
            boolean pending=false;try{DeliverySettlementCases.requireProjectSettled(projectId);}catch(Api.ApiException blocked){if(blocked.code!=409)throw blocked;pending=true;}
            boolean complete=missing==0&&!pending;
            Map<String,Object> out=new LinkedHashMap<>();out.put("project_id",projectId);out.put("organization_code",org);out.put("payment_integration","CONFIGURED");out.put("pending_cases",pending);out.put("missing_claim_count",missing);out.put("known_confirmed_amount",due.toPlainString());out.put("known_paid_amount",paid.toPlainString());out.put("known_balance",due.subtract(paid).toPlainString());out.put("status",complete?"AVAILABLE":"INCOMPLETE");out.put("confirmed_amount",complete?due.toPlainString():null);out.put("paid_amount",complete?paid.toPlainString():null);out.put("balance",complete?due.subtract(paid).toPlainString():null);
            try{guardLegacyArchive(projectId);out.put("can_archive",true);out.put("archive_reason",null);}catch(Api.ApiException blocked){out.put("can_archive",false);out.put("archive_reason",blocked.getMessage());}return out;
        }
    }

    private static void validateConfiguration(Map<String,Object> c) throws Exception {
        whitelist(c,Set.of("version","publication_evidence","eligibility_evidence","approval_status","rule","rate","correction"));
        for(String k:List.of("version","publication_evidence","eligibility_evidence")) code(c.get(k),false);
        if(!"APPROVED".equals(c.get("approval_status"))) fail(400,"仅可登记经授权核实的正式适用依据");
        if(DeliverySettlementPolicy.CANDIDATE_VERSION.equals(c.get("version"))) fail(400,"征求意见稿版本不能作为正式规则");
        RuleVersion rule=rule(c); RateVersion rate=rate(c);
        if(DeliverySettlementPolicy.CANDIDATE_VERSION.equals(rule.versionCode())||DeliverySettlementPolicy.CANDIDATE_VERSION.equals(rate.versionCode())) fail(400,"候选版本不能用于正式确认");
        if(!Integer.valueOf(45).equals(rule.minutesPerClassHour())||!"CLASS45".equals(rate.dimensions().hourUnitCode())) fail(400,"结算规则必须与45分钟课时单位一致");
        if(c.get("correction")!=null) {
            CorrectionPolicy p=correction(c);
            if(!DATE_BASIS.equals(p.dateBasis())||!"REPLACEMENT_DELTA".equals(p.method())) fail(400,"更正规则暂不支持");
        }
    }
    private static RuleVersion rule(Map<String,Object> c) throws Exception {
        Map<String,Object> r=map(c.get("rule")); whitelist(r,Set.of("version","evidence","effective_from","effective_until","date_basis","formula","currency","amount_scale","rounding_mode","rounding_scope","minutes_per_class_hour"));
        return new RuleVersion(code(r.get("version"),false),code(r.get("evidence"),false),requiredDate(r.get("effective_from")),optionalDate(r.get("effective_until")),code(r.get("date_basis"),false),code(r.get("formula"),false),code(r.get("currency"),false),Math.toIntExact(integer(r.get("amount_scale"),true,"amount_scale")),RoundingMode.valueOf(code(r.get("rounding_mode"),false)),code(r.get("rounding_scope"),false),Math.toIntExact(integer(r.get("minutes_per_class_hour"),false,"minutes_per_class_hour")),DataMode.CONFIGURED);
    }
    private static RateVersion rate(Map<String,Object> c) throws Exception {
        Map<String,Object> r=map(c.get("rate")); whitelist(r,Set.of("version","evidence","rule_version","effective_from","effective_until","dimensions","unit_rate"));
        requireDecimalString(r.get("unit_rate")); if(r.get("unit_rate")==null) fail(400,"缺少明确单价");
        return new RateVersion(code(r.get("version"),false),code(r.get("evidence"),false),code(r.get("rule_version"),false),requiredDate(r.get("effective_from")),optionalDate(r.get("effective_until")),dimensions(map(r.get("dimensions"))),decimalInput(r.get("unit_rate")),DataMode.CONFIGURED);
    }
    private static CorrectionPolicy correction(Map<String,Object> c) throws Exception {
        if(c.get("correction")==null) return null; Map<String,Object> r=map(c.get("correction"));
        whitelist(r,Set.of("version","evidence","effective_from","effective_until","date_basis","method"));
        return new CorrectionPolicy(code(r.get("version"),false),code(r.get("evidence"),false),requiredDate(r.get("effective_from")),optionalDate(r.get("effective_until")),code(r.get("date_basis"),false),code(r.get("method"),false),DataMode.CONFIGURED);
    }
    private static Map<String,Object> factMap(Fact f) {
        Map<String,Object> m=new LinkedHashMap<>(); m.put("record_id",f.recordId()); m.put("revision",f.revision()); m.put("session_code",f.sessionCode()); m.put("instructor_code",f.instructorCode()); m.put("organization_code",f.organizationCode()); m.put("service_date",dateText(f.serviceDate())); m.put("dimensions",dimensionsMap(f.dimensions()));
        Map<String,Object> h=new LinkedHashMap<>(); h.put("estimated",decimalText(f.hours().estimated())); h.put("planned",decimalText(f.hours().planned())); h.put("actual",decimalText(f.hours().actual())); h.put("payable",decimalText(f.hours().payable())); m.put("hours",h);
        m.put("verification",f.verification()==null?null:Map.of("actor_code",f.verification().actorCode(),"checked_at",f.verification().checkedAt().toString(),"evidence_code",f.verification().evidenceCode())); m.put("data_mode",f.dataMode().name()); return m;
    }
    private static Fact fact(Map<String,Object> m) throws Exception {
        Map<String,Object> h=map(m.get("hours")); Map<String,Object> v=m.get("verification")==null?null:map(m.get("verification"));
        return new Fact(integer(m.get("record_id"),false,"record_id"),integer(m.get("revision"),false,"revision"),text(m,"session_code"),text(m,"instructor_code"),text(m,"organization_code"),optionalDate(m.get("service_date")),dimensions(map(m.get("dimensions"))),new Hours(decimalInput(h.get("estimated")),decimalInput(h.get("planned")),decimalInput(h.get("actual")),decimalInput(h.get("payable"))),v==null?null:new Verification(text(v,"actor_code"),Instant.parse(text(v,"checked_at")),text(v,"evidence_code")),DataMode.valueOf(text(m,"data_mode")));
    }
    private static Dimensions dimensions(Map<String,Object> m) throws Exception {
        whitelist(m,Set.of("grade","time_band","form","hour_unit"));
        String unit=code(m.get("hour_unit"),true); if(unit!=null&&!"CLASS45".equals(unit)) fail(400,"课时单位应为CLASS45");
        return new Dimensions(code(m.get("grade"),true),code(m.get("time_band"),true),code(m.get("form"),true),unit);
    }
    private static Map<String,Object> dimensionsMap(Dimensions d) { Map<String,Object> m=new LinkedHashMap<>(); m.put("grade",d.gradeCode()); m.put("time_band",d.timeBandCode()); m.put("form",d.formCode()); m.put("hour_unit",d.hourUnitCode()); return m; }
    private static Map<String,Object> problemView(Fact fact,String issue) { Map<String,Object> v=DeliverySettlement.view(calculate(fact,null,null)); v.put("issues",List.of(issue)); return v; }
    private static BigDecimal decimalField(Map<String,Object> b,String key,BigDecimal previous) throws Exception { if(!b.containsKey(key)) return previous; requireDecimalString(b.get(key)); return decimalInput(b.get(key)); }
    private static void requireDecimalString(Object value) throws Exception { if(value!=null&&!(value instanceof String)) fail(400,"分钟、课时和费率必须以十进制字符串提交"); }
    private static BigDecimal addKnown(BigDecimal a,BigDecimal b) { return a==null||b==null?null:a.add(b); }
    private static String decimalText(BigDecimal n) { return n==null?null:n.toPlainString(); }
    private static String dateText(LocalDate d) { return d==null?null:d.toString(); }
    private static LocalDate requiredDate(Object o) throws Exception { LocalDate date=optionalDate(o); if(date==null) fail(400,"缺少生效日期"); return date; }
    private static LocalDate optionalDate(Object o) throws Exception {
        if(o==null||"".equals(o)) return null;
        if(!(o instanceof String)||!o.toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) fail(400,"日期必须为YYYY-MM-DD");
        try { return LocalDate.parse(o.toString()); } catch(java.time.format.DateTimeParseException ex) { throw new Api.ApiException(400,"日期无效"); }
    }
    private static String code(Object value,boolean optional) throws Exception {
        if(value==null&&optional) return null;
        if(!(value instanceof String)||!value.toString().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")) fail(400,"需要有效的编码或版本依据"); return value.toString();
    }
    private static long integer(Object value,boolean zero,String name) throws Exception {
        String raw;
        if(value instanceof String s&&s.matches("[0-9]{1,16}")) raw=s;
        else if(value instanceof Number n&&Double.isFinite(n.doubleValue())&&n.doubleValue()==Math.rint(n.doubleValue())&&Math.abs(n.doubleValue())<=9007199254740991d) raw=new BigDecimal(n.toString()).toPlainString();
        else { fail(400,name+"须为安全整数"); return 0; }
        try { long id=new BigDecimal(raw).longValueExact(); if(id<(zero?0:1)||id>9007199254740991L) fail(400,name+"超出有效范围"); return id; } catch(ArithmeticException e) { throw new Api.ApiException(400,name+"须为整数"); }
    }
    private static boolean sameLegacy(String key,Object a,Object b) throws Exception { return key.endsWith("_id")?integer(a,false,key)==integer(b,false,key):Objects.equals(a,b); }
    private static String resource(String op) { return switch(op) { case "save" -> "delivery.write"; case "verify" -> "delivery.verify"; default -> "settlement."+op; }; }
    private static Set<String> fields(String op) {
        Set<String> allowed=new HashSet<>(CONTROL); allowed.addAll(switch(op) {
            case "save" -> Set.of("estimated_hours","planned_hours","payable_hours","actual_minutes","dimensions");
            case "verify" -> Set.of("evidence_code"); case "configure" -> Set.of("expected_config_version","configuration");
            case "correct" -> Set.of("expected_config_version","expected_snapshot_code","reason_code"); default -> Set.of("expected_config_version");
        }); return allowed;
    }
    private static void whitelist(Map<String,?> m,Set<String> keys) throws Exception { for(String key:m.keySet()) if(!keys.contains(key)) fail(400,"不接受字段："+key); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object o) throws Exception { if(!(o instanceof Map<?,?>)) fail(400,"需要JSON对象"); return (Map<String,Object>)o; }
    private static Map<String,Object> json(String s) throws Exception { return map(Json.parse(s)); }
    private static String text(Map<String,Object> m,String key) { Object v=m.get(key); return v==null?null:v.toString(); }
    private static Object sorted(Object v) {
        if(v instanceof Map<?,?> m) { Map<String,Object> out=new TreeMap<>(); m.forEach((k,value)->out.put(k.toString(),sorted(value))); return out; }
        if(v instanceof List<?> list) return list.stream().map(DeliverySettlementIntegration::sorted).toList();
        if(v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros();
        return v;
    }
    private static String canonical(Map<String,Object> m) { return Json.write(sorted(m)); }
    private static void cas(String sql,Object... values) throws Exception {
        try(PreparedStatement p=Db.get().prepareStatement(sql)) { for(int i=0;i<values.length;i++) p.setObject(i+1,values[i]); if(p.executeUpdate()!=1) fail(409,"记录已发生变化，请刷新后重试"); }
    }
    private static void method(HttpExchange ex,String method) throws Exception { if(!method.equals(ex.getRequestMethod())) fail(405,"请求方法不支持"); }
    private static void fail(int status,String message) throws Api.ApiException { throw new Api.ApiException(status,message); }
}
