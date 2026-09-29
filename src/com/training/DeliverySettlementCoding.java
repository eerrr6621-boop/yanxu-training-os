package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Audited catalog associations for a whole financial chain. Never writes financial events. */
public final class DeliverySettlementCoding {
    private DeliverySettlementCoding() {}
    private static final String BASE="/api/delivery-settlement/coding";
    private static final String HISTORY="课酬编码关联的冻结记录或审计依据无法核实，请先核对";
    private static final int MAX_RECEIPT_CHARS=16384,MAX_LEGACY_RESPONSE_CHARS=9*1024*1024;
    private static final String RECEIPT_SCHEMA="m05_coding_receipt_v1";
    private static final Set<String> CONTEXT_FIELDS=Set.of("chain_code","project_id","organization_code","teacher_id","teacher_name","activity","current_entry_code","version","status");
    private static final Set<String> VIEW_FIELDS=Set.of("chain_code","project_id","organization_code","teacher_id","teacher_name","activity","current_entry_code","version","status","current","history","capabilities");
    private static final Set<String> PREVIEW_RECEIPT_FIELDS=Set.of("schema_version","operation","chain_code","preview_code","preview_digest");
    private static final Set<String> CONFIRM_RECEIPT_FIELDS=Set.of("schema_version","operation","chain_code","version","record_code","record_digest","response_context");
    private static final Set<String> PREVIEW_FIELDS=Set.of("schema_version","preview_code","chain_code","project_id","organization_code","teacher_id","activity","version","expected_entry_code","actor_code","account_id","identity_version","created_at","expires_at","project_source","chain_head_snapshot","chain_head_digest","anchor_entry","anchor_entry_digest","capture","previous_record_code","previous_record_digest","evidence_note","evidence_reference","reason_note","reason_reference");
    private static final Set<String> RECORD_FIELDS=Set.of("schema_version","record_code","version","previous_record_code","previous_record_digest","chain_code","project_id","organization_code","teacher_id","activity","actor_code","account_id","identity_version","confirmed_at","capture","evidence_note","evidence_reference","reason_note","reason_reference","evidence_code","reason_evidence_code","preview_snapshot");
    private record Context(String chain,long project,String org,long teacher,String activity,Map<String,Object> source,Map<String,Object> head,List<Map<String,Object>> entries) {}
    private record History(List<Map<String,Object>> records) {
        long version(){return records.size();}
        Map<String,Object> current(){return records.isEmpty()?null:records.get(records.size()-1);}
    }

    public static void init() throws SQLException {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit())throw new SQLException("编码关联初始化不可嵌套事务");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_coding_heads(chain_code VARCHAR(96) PRIMARY KEY REFERENCES m05_financial_chains(chain_code),project_id BIGINT NOT NULL REFERENCES projects(id),organization_code VARCHAR(96) NOT NULL,version BIGINT NOT NULL,payload CLOB NOT NULL)");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_coding_revisions(chain_code VARCHAR(96) NOT NULL REFERENCES m05_coding_heads(chain_code),version BIGINT NOT NULL,record_code VARCHAR(96) NOT NULL UNIQUE,payload CLOB NOT NULL,actor_code VARCHAR(96) NOT NULL,account_id BIGINT NOT NULL,confirmed_at VARCHAR(40) NOT NULL,PRIMARY KEY(chain_code,version))");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_coding_previews(preview_code VARCHAR(96) PRIMARY KEY,chain_code VARCHAR(96) NOT NULL REFERENCES m05_financial_chains(chain_code),account_id BIGINT NOT NULL,actor_code VARCHAR(96) NOT NULL,created_at VARCHAR(40) NOT NULL,expires_at VARCHAR(40) NOT NULL,expected_version BIGINT NOT NULL,expected_entry_code VARCHAR(96) NOT NULL,payload CLOB NOT NULL,payload_digest VARCHAR(64) NOT NULL,used_record_code VARCHAR(96))");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_coding_requests(account_id BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,actor_code VARCHAR(96) NOT NULL,chain_code VARCHAR(96) NOT NULL REFERENCES m05_financial_chains(chain_code),operation VARCHAR(20) NOT NULL,payload CLOB NOT NULL,response CLOB NOT NULL,response_digest VARCHAR(64) NOT NULL,PRIMARY KEY(account_id,request_id))");
        }
    }

    public static boolean handle(HttpExchange ex,Auth.Session supplied) throws Exception {
        String path=ex.getRequestURI().getPath();
        if(!Set.of(BASE,BASE+"/options",BASE+"/preview",BASE+"/confirm").contains(path))return false;
        Auth.Session session=Auth.get(Api.token(ex));
        if(session==null||session!=supplied||Auth.current(session)==null)fail(401,"登录会话无效或已失效");
        if(path.equals(BASE)||path.endsWith("/options")) {
            method(ex,"GET");Map<String,String> q=Api.query(ex);String raw=ex.getRequestURI().getRawQuery();
            if(raw==null||raw.split("&",-1).length!=q.size())fail(400,"查询参数无效或重复");
            if(path.endsWith("/options")) {
                if(!q.keySet().equals(Set.of("chain_code","scope_id")))fail(400,"请选择账目和课程目录");
                Api.ok(ex,options(session,code(q.get("chain_code")),integer(q.get("scope_id"),false)));
            } else if(q.keySet().equals(Set.of("chain_code")))Api.ok(ex,read(session,code(q.get("chain_code"))));
            else if(q.keySet().equals(Set.of("project_id")))Api.ok(ex,list(session,integer(q.get("project_id"),false)));
            else fail(400,"请选择单个项目或账目");
        } else {
            method(ex,"POST");if(ex.getRequestURI().getRawQuery()!=null)fail(400,"变更请求不接受查询参数");
            Api.ok(ex,mutate(path.substring(BASE.length()+1),session,Api.body(ex)));
        }
        return true;
    }

    public static Map<String,Object> read(Auth.Session session,String chainCode) throws Exception {
        synchronized(Api.MUTATION_LOCK) {Context c=context(session,code(chainCode));return view(session,c,checked(c));}
    }
    public static Map<String,Object> list(Auth.Session session,long projectId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if(projectId<1)fail(400,"项目编号无效");
            Map<String,Object> source=DeliverySettlementIntegration.codingProjectSource(session,projectId);String org=code(source.get("organization_code"));
            List<Map<String,Object>> keys=Db.query("SELECT chain_code,organization_code FROM m05_financial_chains WHERE project_id=? ORDER BY chain_code",projectId);
            if(keys.size()>1000)fail(409,"项目账目超过单次核验容量，请按账目查询");
            List<Map<String,Object>> items=new ArrayList<>();
            for(Map<String,Object> row:keys){if(!org.equals(row.get("organization_code")))fail(409,"项目与冻结账目的机构不一致");Context c=context(session,code(row.get("chain_code")));items.add(view(session,c,checked(c)));}
            return values("project_id",projectId,"organization_code",org,"items",items);
        }
    }
    public static Map<String,Object> options(Auth.Session session,String chainCode,long scopeId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Context c=context(session,code(chainCode));checked(c);
            if(scopeId<1)fail(400,"课程目录编号无效");
            return values("chain_code",c.chain,"project_id",c.project,"teacher_id",c.teacher,"catalog",CourseCatalogIntegration.codingOptions(session,scopeId,c.teacher));
        }
    }

    public static Map<String,Object> mutate(String op,Auth.Session session,Map<String,Object> input) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit())fail(409,"编码关联变更不可嵌套事务");
            if(!Set.of("preview","confirm").contains(op))fail(404,"编码关联操作不存在");
            return Db.transaction(()->{
                OrganizationAccess.Person actor=OrganizationAccessStore.person(session);
                Map<String,Object> body=object(input);requestFields(op,body);
                String chain=code(body.get("chain_code")),request=code(body.get("request_id"));
                long expected=integer(body.get("expected_version"),true);String entry=code(body.get("expected_entry_code"));
                Context c=context(session,chain);History h=checked(c);authorize(session,c,expected>0);
                Map<String,Object> replay=requestRow(session.uid,request);
                if(replay!=null)return replay(op,session,actor.personCode(),body,c,h,replay);
                if(expected!=h.version()||!entry.equals(c.head.get("current_entry_code")))fail(409,"账目或编码关联已变化，请刷新后重新预览");
                Map<String,Object> response=op.equals("preview")?preview(session,actor.personCode(),body,c,h):confirm(session,actor.personCode(),body,c,h);
                Map<String,Object> receipt=receipt(op,response);
                Db.exec("INSERT INTO m05_coding_requests VALUES(?,?,?,?,?,?,?,?)",session.uid,request,actor.personCode(),chain,op,canonical(body),Json.write(receipt),digest(receipt));
                return response;
            });
        }
    }

    private static Map<String,Object> preview(Auth.Session session,String actor,Map<String,Object> b,Context c,History h) throws Exception {
        long scope=integer(b.get("scope_id"),false),catalogVersion=integer(b.get("expected_catalog_version"),true);
        String course=bounded(b.get("course_code"),64,false);
        Map<String,Object> notes=notes(b,h.version()>0),capture=CourseCatalogIntegration.captureCoding(session,scope,catalogVersion,c.teacher,course);
        Instant now=Instant.now();String key="M05-CP-"+UUID.randomUUID();
        Map<String,Object> p=values("schema_version","m05_coding_preview_v1","preview_code",key,"chain_code",c.chain,"project_id",c.project,"organization_code",c.org,"teacher_id",c.teacher,"activity",c.activity,
            "version",h.version(),"expected_entry_code",c.head.get("current_entry_code"),"actor_code",actor,"account_id",session.uid,"identity_version",OrganizationAccessStore.configuration().version(),
            "created_at",now.toString(),"expires_at",now.plusSeconds(900).toString(),"project_source",copy(c.source),"chain_head_snapshot",copy(c.head),"chain_head_digest",digest(c.head),
            "anchor_entry",copy(anchor(c.entries,(String)c.head.get("current_entry_code"))),"anchor_entry_digest",digest(anchor(c.entries,(String)c.head.get("current_entry_code"))),"capture",copy(capture),
            "previous_record_code",h.current()==null?null:h.current().get("record_code"),"previous_record_digest",h.current()==null?null:digest(h.current()));
        p.putAll(notes);if(canonical(p).length()>250000)fail(409,"来源资料超过单次编码核验容量");
        Db.exec("INSERT INTO m05_coding_previews VALUES(?,?,?,?,?,?,?,?,?,?,?)",key,c.chain,session.uid,actor,p.get("created_at"),p.get("expires_at"),h.version(),p.get("expected_entry_code"),Json.write(p),digest(p),null);
        return previewView(p,true);
    }

    private static Map<String,Object> confirm(Auth.Session session,String actor,Map<String,Object> b,Context c,History h) throws Exception {
        String key=code(b.get("preview_code"));Map<String,Object> row=Db.one("SELECT * FROM m05_coding_previews WHERE preview_code=?",key);
        if(row==null)fail(409,"预览不存在，请重新预览");Map<String,Object> p=checkedPreview(row,c);
        if(row.get("used_record_code")!=null)fail(409,"该预览已确认，请刷新关联记录");
        if(!previewCurrent(p,session,actor,c,h))fail(409,"预览已过期或来源、权限、账目发生变化，请重新预览");
        Map<String,Object> frozen=object(p.get("capture"));
        Map<String,Object> fresh=CourseCatalogIntegration.captureCoding(session,integer(frozen.get("catalog_scope_id"),false),integer(frozen.get("catalog_revision"),false),c.teacher,(String)frozen.get("course_code"));
        if(!same(fresh,frozen))fail(409,"课程目录已变化，请重新预览");
        String recordCode="M05-CC-"+UUID.randomUUID();long next=h.version()+1;
        Map<String,Object> r=values("schema_version","m05_coding_record_v1","record_code",recordCode,"version",next,"previous_record_code",p.get("previous_record_code"),"previous_record_digest",p.get("previous_record_digest"),
            "chain_code",c.chain,"project_id",c.project,"organization_code",c.org,"teacher_id",c.teacher,"activity",c.activity,"actor_code",actor,"account_id",session.uid,"identity_version",p.get("identity_version"),
            "confirmed_at",Instant.now().toString(),"capture",copy(frozen),"evidence_note",p.get("evidence_note"),"evidence_reference",p.get("evidence_reference"),"reason_note",p.get("reason_note"),"reason_reference",p.get("reason_reference"),
            "evidence_code",null,"reason_evidence_code",null,"preview_snapshot",copy(p));
        Map<String,Object> descriptor=descriptor(r);
        r.put("evidence_code",DeliverySettlementEvidence.register(session,c.project,c.org,c.chain,"SETTLEMENT-CODING",descriptor,proof(r,"evidence")));
        if(next>1)r.put("reason_evidence_code",DeliverySettlementEvidence.register(session,c.project,c.org,c.chain,"SETTLEMENT-CODING-REASON",descriptor,proof(r,"reason")));
        String payload=Json.write(r);
        if(h.version()==0)Db.exec("INSERT INTO m05_coding_heads VALUES(?,?,?,?,?)",c.chain,c.project,c.org,next,payload);
        else cas("UPDATE m05_coding_heads SET version=?,payload=? WHERE chain_code=? AND version=?",next,payload,c.chain,h.version());
        Db.exec("INSERT INTO m05_coding_revisions VALUES(?,?,?,?,?,?,?)",c.chain,next,recordCode,payload,actor,session.uid,r.get("confirmed_at"));
        cas("UPDATE m05_coding_previews SET used_record_code=? WHERE preview_code=? AND used_record_code IS NULL",recordCode,key);
        return view(session,c,checked(c));
    }

    private static Context context(Auth.Session session,String chain) throws Exception {
        OrganizationAccessStore.person(session);
        Map<String,Object> index=Db.one("SELECT project_id,organization_code FROM m05_financial_chains WHERE chain_code=?",chain);
        if(index==null)fail(404,"已确认账目不存在");long project=integer(index.get("project_id"),false);
        Map<String,Object> source=DeliverySettlementIntegration.codingProjectSource(session,project);
        String org=code(source.get("organization_code"));
        if(!org.equals(index.get("organization_code")))fail(409,"项目与冻结账目的机构不一致");
        return contextRaw(chain,project,org,source);
    }
    private static Context contextRaw(String chain,long project,String org,Map<String,Object> source) throws Exception {
        Map<String,Object> index=Db.one("SELECT project_id,organization_code FROM m05_financial_chains WHERE chain_code=?",chain);
        if(index==null||integer(index.get("project_id"),false)!=project||!org.equals(index.get("organization_code")))fail(409,"冻结账目来源不一致");
        Map<String,Object> head=DeliverySettlementLedger.head(chain);
        if(head==null)fail(409,"缺少已确认账目");Map<String,Object> total=object(head.get("total"));
        long teacher=integer(total.get("teacher_id"),false);String activity=bounded(total.get("activity"),96,false);
        if(!org.equals(total.get("organization_code")))fail(409,"冻结账目机构不一致");
        return new Context(chain,project,org,teacher,activity,source,head,DeliverySettlementLedger.entries("m05_financial_entries",chain));
    }

    /** Validate frozen associations against their original anchors, never against later changing totals. */
    private static History checked(Context c) throws Exception {
        try {
            Map<String,Object> extent=Db.one("SELECT COUNT(*) AS n,COALESCE(SUM(CHAR_LENGTH(payload)),0) AS chars FROM m05_coding_revisions WHERE chain_code=?",c.chain);
            if(integer(extent.get("n"),true)>1000||integer(extent.get("chars"),true)>8L*1024*1024)fail(409,HISTORY);
            Map<String,Object> head=Db.one("SELECT * FROM m05_coding_heads WHERE chain_code=?",c.chain);
            if(head==null){if(integer(extent.get("n"),true)!=0)fail(409,HISTORY);return new History(List.of());}
            if(integer(head.get("project_id"),false)!=c.project||!c.org.equals(head.get("organization_code")))fail(409,HISTORY);
            List<Map<String,Object>> records=new ArrayList<>();Map<String,Object> previous=null;Instant last=null;
            for(Map<String,Object> row:Db.query("SELECT * FROM m05_coding_revisions WHERE chain_code=? ORDER BY version",c.chain)) {
                Map<String,Object> r=json(row.get("payload"));long version=records.size()+1;
                if(!r.keySet().equals(RECORD_FIELDS)||!"m05_coding_record_v1".equals(r.get("schema_version"))||integer(r.get("version"),false)!=version||integer(row.get("version"),false)!=version
                    ||!Objects.equals(row.get("record_code"),r.get("record_code"))||!Objects.equals(row.get("actor_code"),r.get("actor_code"))
                    ||integer(row.get("account_id"),false)!=integer(r.get("account_id"),false)||!Objects.equals(row.get("confirmed_at"),r.get("confirmed_at")))fail(409,HISTORY);
                code(r.get("record_code"));code(r.get("actor_code"));bounded(r.get("identity_version"),500,false);binding(r,c);
                if(!Objects.equals(r.get("previous_record_code"),previous==null?null:previous.get("record_code"))||!Objects.equals(r.get("previous_record_digest"),previous==null?null:digest(previous)))fail(409,HISTORY);
                Instant at=instant(r.get("confirmed_at"));if(at.isAfter(Instant.now())||(last!=null&&at.isBefore(last)))fail(409,HISTORY);
                Map<String,Object> p=object(r.get("preview_snapshot"));
                Map<String,Object> previewRow=Db.one("SELECT * FROM m05_coding_previews WHERE preview_code=?",code(p.get("preview_code")));
                if(previewRow==null||!Objects.equals(previewRow.get("used_record_code"),r.get("record_code"))||!same(p,checkedPreview(previewRow,c)))fail(409,HISTORY);
                if(integer(p.get("version"),true)!=version-1||!Objects.equals(p.get("previous_record_code"),r.get("previous_record_code"))||!Objects.equals(p.get("previous_record_digest"),r.get("previous_record_digest"))
                    ||!Objects.equals(p.get("actor_code"),r.get("actor_code"))||integer(p.get("account_id"),false)!=integer(r.get("account_id"),false)
                    ||!Objects.equals(p.get("identity_version"),r.get("identity_version"))||!same(p.get("capture"),r.get("capture"))
                    ||at.isBefore(instant(p.get("created_at")))||at.isAfter(instant(p.get("expires_at"))))fail(409,HISTORY);
                for(String field:List.of("evidence_note","evidence_reference","reason_note","reason_reference"))if(!Objects.equals(r.get(field),p.get(field)))fail(409,HISTORY);
                if(!same(notes(r,version>1),selectNotes(r)))fail(409,HISTORY);
                validateProof(r,c,"evidence","SETTLEMENT-CODING","evidence_code");
                if(version>1)validateProof(r,c,"reason","SETTLEMENT-CODING-REASON","reason_evidence_code");
                else if(r.get("reason_evidence_code")!=null)fail(409,HISTORY);
                records.add(r);previous=r;last=at;
            }
            if(records.isEmpty()||integer(head.get("version"),false)!=records.size()||!same(json(head.get("payload")),previous))fail(409,HISTORY);
            return new History(records);
        } catch(Api.ApiException bad){if(bad.code==409)throw bad;throw new Api.ApiException(409,HISTORY);}
          catch(RuntimeException bad){throw new Api.ApiException(409,HISTORY);}
    }
    private static Map<String,Object> checkedPreview(Map<String,Object> row,Context c) throws Exception {
        try {
            Map<String,Object> p=json(row.get("payload"));
            if(!p.keySet().equals(PREVIEW_FIELDS)||!"m05_coding_preview_v1".equals(p.get("schema_version"))||!digest(p).equals(row.get("payload_digest")))fail(409,HISTORY);
            for(String key:List.of("preview_code","chain_code","actor_code","created_at","expires_at","expected_entry_code"))if(!Objects.equals(row.get(key),p.get(key)))fail(409,HISTORY);
            if(integer(row.get("account_id"),false)!=integer(p.get("account_id"),false)||integer(row.get("expected_version"),true)!=integer(p.get("version"),true))fail(409,HISTORY);
            code(p.get("preview_code"));code(p.get("actor_code"));bounded(p.get("identity_version"),500,false);binding(p,c);
            Instant created=instant(p.get("created_at")),expiry=instant(p.get("expires_at"));
            if(created.isAfter(Instant.now())||!expiry.equals(created.plusSeconds(900)))fail(409,HISTORY);
            long version=integer(p.get("version"),true);if(version==0){if(p.get("previous_record_code")!=null||p.get("previous_record_digest")!=null)fail(409,HISTORY);}
            else {code(p.get("previous_record_code"));hash(p.get("previous_record_digest"));}
            Map<String,Object> originalHead=object(p.get("chain_head_snapshot")),originalTotal=object(originalHead.get("total")),entry=object(p.get("anchor_entry"));
            if(!Objects.equals(p.get("chain_head_digest"),digest(originalHead))||!Objects.equals(p.get("anchor_entry_digest"),digest(entry))
                ||!Objects.equals(originalHead.get("chain_code"),c.chain)||!Objects.equals(originalHead.get("current_entry_code"),p.get("expected_entry_code"))
                ||integer(originalTotal.get("teacher_id"),false)!=c.teacher||!c.org.equals(originalTotal.get("organization_code"))||!c.activity.equals(originalTotal.get("activity"))
                ||!same(entry,anchor(c.entries,code(p.get("expected_entry_code")))))fail(409,HISTORY);
            Map<String,Object> source=object(p.get("project_source"));
            if(integer(source.get("id"),false)!=c.project||!c.org.equals(source.get("organization_code")))fail(409,HISTORY);
            Map<String,Object> capture=object(p.get("capture"));
            if(integer(capture.get("teacher_id"),false)!=c.teacher||capture.get("course_id")!=null)fail(409,HISTORY);
            CourseCatalogIntegration.validateCapturedCoding(capture);
            if(!same(notes(p,version>0),selectNotes(p)))fail(409,HISTORY);
            return p;
        } catch(Api.ApiException bad){if(bad.code==409)throw bad;throw new Api.ApiException(409,HISTORY);}
          catch(RuntimeException bad){throw new Api.ApiException(409,HISTORY);}
    }
    private static void binding(Map<String,Object> r,Context c) throws Exception {
        if(!c.chain.equals(r.get("chain_code"))||integer(r.get("project_id"),false)!=c.project||!c.org.equals(r.get("organization_code"))
            ||integer(r.get("teacher_id"),false)!=c.teacher||!c.activity.equals(r.get("activity")))fail(409,HISTORY);
    }
    private static void validateProof(Map<String,Object> r,Context c,String prefix,String kind,String key) throws Exception {
        Map<String,Object> e=DeliverySettlementEvidence.resolve(code(r.get(key)),c.project,c.org,c.chain,kind,descriptor(r));
        if(!Objects.equals(e.get("actor_code"),r.get("actor_code"))||integer(e.get("account_id"),false)!=integer(r.get("account_id"),false)
            ||!Objects.equals(e.get("note"),r.get(prefix+"_note"))||!Objects.equals(e.get("reference"),r.get(prefix+"_reference"))
            ||instant(e.get("created_at")).isBefore(instant(object(r.get("preview_snapshot")).get("created_at"))))fail(409,HISTORY);
    }
    /** The immutable evidence anchors the full audit content, not caller-supplied descriptor fields. */
    private static Map<String,Object> descriptor(Map<String,Object> r) throws Exception {
        Map<String,Object> bound=copy(r);bound.remove("evidence_code");bound.remove("reason_evidence_code");
        Map<String,Object> p=object(r.get("preview_snapshot")),capture=object(r.get("capture"));
        return values("action",integer(r.get("version"),false)==1?"LINK":"CORRECT","chain_code",r.get("chain_code"),"coding_version",r.get("version"),"record_code",r.get("record_code"),
            "project_id",r.get("project_id"),"organization_code",r.get("organization_code"),"teacher_id",r.get("teacher_id"),"activity",r.get("activity"),"expected_entry_code",p.get("expected_entry_code"),
            "catalog_scope_id",capture.get("catalog_scope_id"),"catalog_revision",capture.get("catalog_revision"),"teacher_code",capture.get("teacher_code"),"course_code",capture.get("course_code"),
            "actor_code",r.get("actor_code"),"account_id",r.get("account_id"),"identity_version",r.get("identity_version"),"confirmed_at",r.get("confirmed_at"),
            "preview_digest",digest(p),"capture_digest",digest(capture),"audit_digest",digest(bound));
    }

    private static Map<String,Object> view(Auth.Session session,Context c,History h) throws Exception {
        Map<String,Object> teacher=Db.one("SELECT name FROM teachers WHERE id=?",c.teacher);
        if(teacher==null)fail(409,"冻结账目对应的讲师不存在");
        List<Map<String,Object>> old=new ArrayList<>();for(int i=0;i<h.records.size()-1;i++)old.add(copy(h.records.get(i)));
        return values("chain_code",c.chain,"project_id",c.project,"organization_code",c.org,"teacher_id",c.teacher,"teacher_name",teacher.get("name"),"activity",c.activity,
            "current_entry_code",c.head.get("current_entry_code"),"version",h.version(),"status",h.version()==0?"NOT_LINKED":"LINKED","current",h.current()==null?null:copy(h.current()),"history",old,
            "capabilities",capabilities(session,c,h));
    }
    private static Map<String,Object> capabilities(Auth.Session session,Context c,History h) throws Exception {
        boolean writable=!closed((String)c.source.get("status")),confirm=allowed(session,"settlement.confirm",c.org),correct=allowed(session,"settlement.correct",c.org);
        boolean preview=writable&&confirm&&(h.version()==0||correct),ready=false;
        if(preview) {
            Map<String,Object> row=Db.one("SELECT * FROM m05_coding_previews WHERE chain_code=? AND account_id=? AND used_record_code IS NULL ORDER BY created_at DESC LIMIT 1",c.chain,session.uid);
            if(row!=null) {
                Map<String,Object> p=checkedPreview(row,c);
                if(previewCurrent(p,session,OrganizationAccessStore.person(session).personCode(),c,h)) {
                    Map<String,Object> capture=object(p.get("capture"));
                    try {ready=same(capture,CourseCatalogIntegration.captureCoding(session,integer(capture.get("catalog_scope_id"),false),integer(capture.get("catalog_revision"),false),c.teacher,(String)capture.get("course_code")));}
                    catch(Api.ApiException unavailable){if(unavailable.code!=403&&unavailable.code!=409&&unavailable.code!=404)throw unavailable;}
                }
            }
        }
        return values("can_preview",preview,"can_confirm",ready,"can_correct",writable&&confirm&&correct&&h.version()>0);
    }
    private static boolean previewCurrent(Map<String,Object> p,Auth.Session session,String actor,Context c,History h) throws Exception {
        return integer(p.get("account_id"),false)==session.uid&&actor.equals(p.get("actor_code"))&&c.chain.equals(p.get("chain_code"))
            &&integer(p.get("version"),true)==h.version()&&Objects.equals(p.get("expected_entry_code"),c.head.get("current_entry_code"))
            &&Objects.equals(p.get("identity_version"),OrganizationAccessStore.configuration().version())
            &&Objects.equals(p.get("chain_head_digest"),digest(c.head))&&same(p.get("project_source"),c.source)
            &&Objects.equals(p.get("previous_record_code"),h.current()==null?null:h.current().get("record_code"))
            &&Objects.equals(p.get("previous_record_digest"),h.current()==null?null:digest(h.current()))
            &&Instant.now().isBefore(instant(p.get("expires_at")));
    }
    private static Map<String,Object> previewView(Map<String,Object> p,boolean ready) throws Exception {
        Map<String,Object> out=values("preview_code",p.get("preview_code"),"expires_at",p.get("expires_at"),"chain_code",p.get("chain_code"),"version",p.get("version"),
            "expected_entry_code",p.get("expected_entry_code"),"candidate",copy(object(p.get("capture"))),"can_confirm",ready);
        out.putAll(selectNotes(p));return out;
    }
    private static Map<String,Object> replay(String op,Auth.Session session,String actor,Map<String,Object> input,Context c,History h,Map<String,Object> row) throws Exception {
        try {return replayChecked(op,session,actor,input,c,h,row);}
        catch(Api.ApiException invalid){if(invalid.code==400)throw new Api.ApiException(409,HISTORY);throw invalid;}
        catch(RuntimeException invalid){throw new Api.ApiException(409,HISTORY);}
    }
    private static Map<String,Object> replayChecked(String op,Auth.Session session,String actor,Map<String,Object> input,Context c,History h,Map<String,Object> row) throws Exception {
        if(!op.equals(row.get("operation"))||!c.chain.equals(row.get("chain_code"))||!actor.equals(row.get("actor_code"))
            ||!canonical(input).equals(row.get("payload")))fail(409,"同一请求编号已用于不同内容或操作");
        Map<String,Object> saved=requestResponse(row.get("response"),op);
        if(!digest(saved).equals(row.get("response_digest")))fail(409,HISTORY);
        boolean compact=saved.containsKey("schema_version");
        if(compact&&(!RECEIPT_SCHEMA.equals(saved.get("schema_version"))||!op.equals(saved.get("operation"))||!c.chain.equals(saved.get("chain_code"))
            ||!saved.keySet().equals(op.equals("preview")?PREVIEW_RECEIPT_FIELDS:CONFIRM_RECEIPT_FIELDS)))fail(409,HISTORY);
        if(op.equals("preview")) {
            Map<String,Object> stored=Db.one("SELECT * FROM m05_coding_previews WHERE preview_code=?",code(saved.get("preview_code")));
            if(stored==null)fail(409,HISTORY);Map<String,Object> p=checkedPreview(stored,c);
            Map<String,Object> capture=object(p.get("capture"));
            if(integer(p.get("account_id"),false)!=session.uid||!actor.equals(p.get("actor_code"))
                ||integer(p.get("version"),true)!=integer(input.get("expected_version"),true)||!Objects.equals(p.get("expected_entry_code"),input.get("expected_entry_code"))
                ||integer(capture.get("catalog_scope_id"),false)!=integer(input.get("scope_id"),false)||integer(capture.get("catalog_revision"),false)!=integer(input.get("expected_catalog_version"),false)
                ||!Objects.equals(capture.get("course_code"),bounded(input.get("course_code"),64,false))||!same(selectNotes(p),notes(input,integer(p.get("version"),true)>0))
                ||(compact?!Objects.equals(saved.get("preview_digest"),digest(p)):!same(saved,previewView(p,true))))fail(409,HISTORY);
            CourseCatalogIntegration.codingOptions(session,integer(capture.get("catalog_scope_id"),false),c.teacher);
            boolean ready=stored.get("used_record_code")==null&&previewCurrent(p,session,actor,c,h);
            if(ready) {
                try {ready=same(capture,CourseCatalogIntegration.captureCoding(session,integer(capture.get("catalog_scope_id"),false),integer(capture.get("catalog_revision"),false),c.teacher,(String)capture.get("course_code")));}
                catch(Api.ApiException unavailable){if(unavailable.code!=409&&unavailable.code!=404)throw unavailable;ready=false;}
            }
            return previewView(p,ready);
        }
        long version=integer(saved.get("version"),false);
        if(version>h.version()||version!=integer(input.get("expected_version"),true)+1)fail(409,HISTORY);
        Map<String,Object> record=h.records.get((int)version-1),p=object(record.get("preview_snapshot"));
        Map<String,Object> context;
        if(compact) {
            if(!Objects.equals(saved.get("record_code"),record.get("record_code"))||!Objects.equals(saved.get("record_digest"),digest(record)))fail(409,HISTORY);
            context=object(saved.get("response_context"));
        } else {
            if(!saved.keySet().equals(VIEW_FIELDS)||!same(saved.get("current"),record)||!same(saved.get("history"),h.records.subList(0,(int)version-1)))fail(409,HISTORY);
            Map<String,Object> caps=object(saved.get("capabilities"));
            if(!caps.keySet().equals(Set.of("can_preview","can_confirm","can_correct"))||caps.values().stream().anyMatch(value->!(value instanceof Boolean)))fail(409,HISTORY);
            context=responseContext(saved);
        }
        if(!context.keySet().equals(CONTEXT_FIELDS)||!c.chain.equals(context.get("chain_code"))||integer(context.get("project_id"),false)!=c.project
            ||!c.org.equals(context.get("organization_code"))||integer(context.get("teacher_id"),false)!=c.teacher||!c.activity.equals(context.get("activity"))
            ||integer(context.get("version"),false)!=version||!"LINKED".equals(context.get("status"))||!Objects.equals(context.get("current_entry_code"),p.get("expected_entry_code"))
            ||(context.get("teacher_name")!=null&&!(context.get("teacher_name") instanceof String))||canonical(context).length()>MAX_RECEIPT_CHARS
            ||integer(record.get("account_id"),false)!=session.uid||!actor.equals(record.get("actor_code"))
            ||!Objects.equals(p.get("preview_code"),input.get("preview_code"))||!Objects.equals(p.get("expected_entry_code"),input.get("expected_entry_code")))fail(409,HISTORY);
        CourseCatalogIntegration.codingOptions(session,integer(object(record.get("capture")).get("catalog_scope_id"),false),c.teacher);
        Map<String,Object> response=copy(context);List<Map<String,Object>> old=new ArrayList<>();
        for(int i=0;i<version-1;i++)old.add(copy(h.records.get(i)));
        response.put("current",copy(record));response.put("history",old);response.put("capabilities",capabilities(session,c,h));return response;
    }
    /** Request storage stays proportional to the number of operations, not the accumulated history. */
    private static Map<String,Object> receipt(String op,Map<String,Object> response) throws Exception {
        Map<String,Object> receipt;
        if(op.equals("preview")) {
            Map<String,Object> row=Db.one("SELECT payload_digest FROM m05_coding_previews WHERE preview_code=?",response.get("preview_code"));
            if(row==null)fail(409,HISTORY);
            receipt=values("schema_version",RECEIPT_SCHEMA,"operation",op,"chain_code",response.get("chain_code"),"preview_code",response.get("preview_code"),"preview_digest",row.get("payload_digest"));
        } else {
            Map<String,Object> r=object(response.get("current"));
            receipt=values("schema_version",RECEIPT_SCHEMA,"operation",op,"chain_code",response.get("chain_code"),"version",r.get("version"),"record_code",r.get("record_code"),"record_digest",digest(r),"response_context",responseContext(response));
        }
        if(Json.write(receipt).length()>MAX_RECEIPT_CHARS)fail(409,"编码关联回执超过核验容量");
        return receipt;
    }
    private static Map<String,Object> responseContext(Map<String,Object> view) {
        Map<String,Object> context=new LinkedHashMap<>(view);context.remove("current");context.remove("history");context.remove("capabilities");return context;
    }
    private static Map<String,Object> requestRow(long account,String request) throws Exception {
        Map<String,Object> size=Db.one("SELECT operation,CHAR_LENGTH(payload) AS payload_chars,CHAR_LENGTH(response) AS response_chars FROM m05_coding_requests WHERE account_id=? AND request_id=?",account,request);
        if(size==null)return null;
        if(integer(size.get("payload_chars"),true)>250000||integer(size.get("response_chars"),true)>("confirm".equals(size.get("operation"))?MAX_LEGACY_RESPONSE_CHARS:250000))fail(409,HISTORY);
        return Db.one("SELECT * FROM m05_coding_requests WHERE account_id=? AND request_id=?",account,request);
    }
    /** Limits count characters, as CHAR_LENGTH does: only old confirm aggregates may exceed 250k. */
    private static Map<String,Object> requestResponse(Object raw,String op) throws Exception {
        if(!(raw instanceof String text)||text.length()>(op.equals("confirm")?MAX_LEGACY_RESPONSE_CHARS:250000))fail(409,HISTORY);
        Map<String,Object> result=object(Json.parse((String)raw));
        if(result.containsKey("schema_version")&&raw.toString().length()>MAX_RECEIPT_CHARS)fail(409,HISTORY);
        if(!result.containsKey("schema_version")&&op.equals("confirm")&&!result.keySet().equals(VIEW_FIELDS))fail(409,HISTORY);
        return result;
    }
    private static void authorize(Auth.Session session,Context c,boolean correction) throws Exception {
        if(!allowed(session,"settlement.confirm",c.org)||(correction&&!allowed(session,"settlement.correct",c.org)))fail(403,"没有本机构课酬编码确认或更正权限");
        if(closed((String)c.source.get("status")))fail(409,"项目已归档或关闭，请先按项目流程恢复办理");
    }
    private static boolean allowed(Auth.Session s,String resource,String org) throws Exception {return OrganizationAccessStore.authorize(s,resource,OrganizationAccess.Action.HANDLE,org).allowed();}

    /** The caller has authorized and validated the complete project financial source. */
    public static Map<String,Object> project(long projectId,String org,Map<String,Object> rawFinancial) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if(projectId<1||integer(rawFinancial.get("project_id"),false)!=projectId||!Objects.equals(rawFinancial.get("organization_code"),org))fail(409,"财务来源项目或机构不一致");
            if(!same(rawFinancial,DeliverySettlementLedger.project(projectId,org)))fail(409,"财务来源已变化，请重新读取完整来源");
            Map<String,Object> out=copy(rawFinancial);Map<String,Map<String,Object>> linked=new TreeMap<>();
            List<Map<String,Object>> versions=new ArrayList<>(),audits=new ArrayList<>();List<String> uncoded=new ArrayList<>();int chains=0;
            for(Object item:listValue(rawFinancial.get("chain_heads"))) {
                Map<String,Object> head=object(item);String chain=code(head.get("chain_code"));Context c=contextRaw(chain,projectId,org,null);History h=checked(c);chains++;
                if(h.current()==null){uncoded.add(chain);continue;}
                Map<String,Object> r=h.current(),capture=object(r.get("capture"));linked.put(chain,capture);
                versions.add(values("chain_code",chain,"version",r.get("version"),"record_code",r.get("record_code"),"catalog_scope_id",capture.get("catalog_scope_id"),
                    "catalog_revision",capture.get("catalog_revision"),"catalog_version",capture.get("catalog_version"),"catalog_digest",capture.get("catalog_digest"),
                    "teacher_id",r.get("teacher_id"),"teacher_code",capture.get("teacher_code"),"course_code",capture.get("course_code"),"evidence_code",r.get("evidence_code"),"confirmed_at",r.get("confirmed_at")));
                for(Map<String,Object> revision:h.records)audits.add(values("chain_code",chain,"version",revision.get("version"),"record_code",revision.get("record_code"),"digest",digest(revision)));
            }
            for(Object item:listValue(out.get("accrual_entries"))) {
                Map<String,Object> accrual=object(item),capture=linked.get(accrual.get("chain_code"));
                if(capture!=null){accrual.put("teacher_code",capture.get("teacher_code"));accrual.put("course_code",capture.get("course_code"));accrual.put("course_id",null);}
            }
            Map<String,Object> coverage=values("mode","AUDITED_CHAIN_CODING","total_chains",chains,"coded_chains",linked.size(),"uncoded_chains",uncoded);
            out.put("coding_versions",versions);out.put("coding_coverage",coverage);
            out.put("source_version",digest(values("raw_source_version",rawFinancial.get("source_version"),"coding_revisions",audits,"coding_versions",versions,"coding_coverage",coverage)));
            return out;
        }
    }
    public static Map<String,Object> project(Map<String,Object> rawFinancial) throws Exception {return project(integer(rawFinancial.get("project_id"),false),code(rawFinancial.get("organization_code")),rawFinancial);}

    private static void requestFields(String op,Map<String,Object> body) throws Exception {
        Set<String> required=op.equals("preview")?Set.of("chain_code","expected_version","expected_entry_code","scope_id","expected_catalog_version","course_code","evidence_note","request_id"):
            Set.of("chain_code","expected_version","expected_entry_code","preview_code","request_id");
        Set<String> allowed=new HashSet<>(required);if(op.equals("preview"))allowed.addAll(Set.of("evidence_reference","reason_note","reason_reference"));
        if(!allowed.containsAll(body.keySet())||!body.keySet().containsAll(required))fail(400,"编码关联请求缺少必填信息或包含未允许字段");
        for(String key:op.equals("preview")?List.of("expected_version","scope_id","expected_catalog_version"):List.of("expected_version"))
            if(!(body.get(key) instanceof Number))fail(400,"版本和目录编号须为整数");
    }
    private static Map<String,Object> notes(Map<String,Object> body,boolean correction) throws Exception {
        String note=bounded(body.get("evidence_note"),2000,false),reference=bounded(body.get("evidence_reference"),500,true);
        String reason=bounded(body.get("reason_note"),2000,!correction),reasonReference=bounded(body.get("reason_reference"),500,true);
        if(!correction&&(reason!=null||reasonReference!=null))fail(400,"首次关联不填写更正理由");
        return values("evidence_note",note,"evidence_reference",reference,"reason_note",reason,"reason_reference",reasonReference);
    }
    private static Map<String,Object> selectNotes(Map<String,Object> from){return values("evidence_note",from.get("evidence_note"),"evidence_reference",from.get("evidence_reference"),"reason_note",from.get("reason_note"),"reason_reference",from.get("reason_reference"));}
    private static Map<String,Object> proof(Map<String,Object> from,String prefix){return values("note",from.get(prefix+"_note"),"reference",from.get(prefix+"_reference"));}
    private static Map<String,Object> anchor(List<Map<String,Object>> entries,String code) throws Exception {for(Map<String,Object> e:entries)if(code.equals(e.get("entry_code")))return e;fail(409,HISTORY);return null;}
    private static void cas(String sql,Object... args) throws Exception {try(PreparedStatement statement=Db.get().prepareStatement(sql)){for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);if(statement.executeUpdate()!=1)fail(409,"编码关联已被更新，请刷新后重试");}}
    private static boolean closed(String status){return status!=null&&Set.of("archived","closed","cancelled","canceled","ARCHIVED","CLOSED","CANCELLED","CANCELED","已归档","已取消").contains(status);}
    private static void method(HttpExchange ex,String method) throws Exception {if(!method.equals(ex.getRequestMethod()))fail(405,"请求方法不支持");}
    private static String code(Object value) throws Exception {if(!(value instanceof String s)||!s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}"))fail(400,"关联引用编号无效");return (String)value;}
    private static void hash(Object value) throws Exception {if(!(value instanceof String s)||!s.matches("[0-9a-f]{64}"))fail(409,HISTORY);}
    private static String bounded(Object value,int max,boolean nullable) throws Exception {
        if(value==null&&nullable)return null;if(!(value instanceof String s)||s.length()>max||(!nullable&&s.isBlank()))fail(400,"说明或选择项为空或超过长度限制");
        String s=((String)value).trim();return nullable&&s.isEmpty()?null:s;
    }
    private static long integer(Object value,boolean zero) throws Exception {
        try {if(!(value instanceof Number)&&!(value instanceof String))throw new IllegalArgumentException();long n=new BigDecimal(value.toString()).longValueExact();if(n<(zero?0:1)||n>9007199254740991L)throw new IllegalArgumentException();return n;}
        catch(Exception invalid){throw new Api.ApiException(400,"编号或版本须为有效整数");}
    }
    private static Instant instant(Object value) throws Exception {try{if(!(value instanceof String s)||s.length()>40)throw new IllegalArgumentException();return Instant.parse((String)value);}catch(RuntimeException bad){throw new Api.ApiException(409,HISTORY);}}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws Exception {if(!(value instanceof Map<?,?>))fail(400,"请求内容须为对象");return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> listValue(Object value) throws Exception {if(!(value instanceof List<?>))fail(409,HISTORY);return (List<Object>)value;}
    private static Map<String,Object> json(Object value) throws Exception {if(value==null||value.toString().length()>250000)fail(409,HISTORY);return object(Json.parse(value.toString()));}
    private static Map<String,Object> values(Object... pairs){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map;}
    @SuppressWarnings("unchecked") private static Map<String,Object> copy(Map<String,Object> value){return (Map<String,Object>)deepCopy(value);}
    private static Object deepCopy(Object value){if(value instanceof Map<?,?> m){Map<String,Object> out=new LinkedHashMap<>();m.forEach((k,v)->out.put((String)k,deepCopy(v)));return out;}if(value instanceof List<?> list){List<Object> out=new ArrayList<>();for(Object v:list)out.add(deepCopy(v));return out;}return value;}
    private static boolean same(Object a,Object b){return canonical(a).equals(canonical(b));}
    private static String canonical(Object value){return Json.write(sorted(value));}
    private static Object sorted(Object value){if(value instanceof Number n)return new BigDecimal(n.toString()).stripTrailingZeros();if(value instanceof Map<?,?> m){Map<String,Object> out=new TreeMap<>();m.forEach((k,v)->out.put((String)k,sorted(v)));return out;}if(value instanceof List<?> list)return list.stream().map(DeliverySettlementCoding::sorted).toList();return value;}
    private static String digest(Object value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static void fail(int status,String message) throws Api.ApiException {throw new Api.ApiException(status,message);}
}
