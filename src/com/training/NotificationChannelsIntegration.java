package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static com.training.NotificationChannels.*;

/** Durable S01 inbox. Host owns business transactions; no worker, mail or credential access. */
public final class NotificationChannelsIntegration {
    private NotificationChannelsIntegration() {}
    private static final String JOIN = "SELECT n.*,e.* FROM s01_notifications n JOIN s01_notification_events e ON e.event_key=n.event_key ";
    private static final int MAX_ROWS = 10000;

    /** Startup only, after M01 and WorkflowIntegration.init(). H2 DDL must not commit business work. */
    public static void init() throws SQLException {
        synchronized (Api.MUTATION_LOCK) {
            if (!Db.get().getAutoCommit()) throw new IllegalStateException("通知建表不能嵌入业务事务");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_notification_events (event_key VARCHAR(500) PRIMARY KEY REFERENCES workflow_outbox(event_key),event_id VARCHAR(64) NOT NULL UNIQUE,demand_id BIGINT NOT NULL REFERENCES workflow_demands(demand_id),source_payload CLOB NOT NULL,source_hash VARCHAR(64) NOT NULL,organization_code VARCHAR(120) NOT NULL,filler_code VARCHAR(120) NOT NULL,business_moment VARCHAR(32),event_type VARCHAR(32),event_intent VARCHAR(24),event_destination VARCHAR(8),approval_version BIGINT,acceptance_demand BIGINT REFERENCES workflow_acceptances(demand_id),document_revision BIGINT,occurred_at VARCHAR(40) NOT NULL,rule_version VARCHAR(128) NOT NULL,delivery_status VARCHAR(24) NOT NULL,blocked_reason VARCHAR(80) NOT NULL,FOREIGN KEY(demand_id,approval_version) REFERENCES approval_events(workflow_id,version),FOREIGN KEY(demand_id,document_revision) REFERENCES workflow_documents(demand_id,data_revision))");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_notifications (notice_id VARCHAR(64) PRIMARY KEY,event_key VARCHAR(500) NOT NULL REFERENCES s01_notification_events(event_key),account_id BIGINT NOT NULL REFERENCES users(id),person_code VARCHAR(128) NOT NULL,person_organization VARCHAR(128) NOT NULL,configuration_version VARCHAR(128) NOT NULL REFERENCES organization_access_config(version),read_at VARCHAR(40),UNIQUE(event_key,account_id))");
            Db.exec("CREATE INDEX IF NOT EXISTS s01_notifications_owner ON s01_notifications(account_id,event_key)");
        }
    }

    /** Replace the host's outbox INSERT with this call, under its existing lock AND transaction.
     * Inserts the outbox row itself, so an old row can never be captured using today's bindings.
     * All returned recipients/status are the first frozen result; replay never resolves them again.
     */
    public static Map<String,Object> appendOutboxInTransaction(String key,long demand,Map<String,Object> payload) throws Exception {
        requireTransaction();
        if (key==null || key.isBlank() || key.length()>500 || demand<=0 || demand>MAX_WEB_ID || payload==null)
            fail(422,"通知来源标识无效");
        String canonical=canonical(payload);
        Map<String,Object> old=Db.one("SELECT * FROM workflow_outbox WHERE event_key=?",key);
        if (old!=null) {
            if (number(old,"demand_id")!=demand || !canonical(json(string(old,"payload"))).equals(canonical))
                fail(409,"同一通知事件不能更换来源或内容");
            Map<String,Object> frozen=Db.one("SELECT * FROM s01_notification_events WHERE event_key=?",key);
            if (frozen==null) return receipt("BLOCKED","HISTORICAL_BINDING_UNPROVEN",0);
            if (!canonical.equals(string(frozen,"source_payload")) || number(frozen,"demand_id")!=demand)
                fail(409,"通知冻结批次与原事件不一致");
            return receipt(string(frozen,"delivery_status"),string(frozen,"blocked_reason"),noticeCount(key));
        }
        String created=Instant.now().toString();
        Db.exec("INSERT INTO workflow_outbox(event_key,demand_id,payload,status,created_at) VALUES(?,?,?,'PENDING',?)",key,demand,canonical,created);
        Source source=source(key,demand,payload,true);
        ReminderRule rule=source.moment==null?null:approvedRule(source.moment);
        OrganizationAccess.Configuration configuration=OrganizationAccessStore.configuration();
        RecipientBinding recipient=null;
        String reason=rule==null?"UNSUPPORTED_BUSINESS_MOMENT":"";
        if (source.moment==BusinessMoment.BP_APPROVED) reason="TEAM_RECIPIENTS_UNCONFIGURED";
        else if (rule!=null) {
            recipient=resolve(configuration,source.recipient,source.organization);
            if (recipient==null) reason="RECIPIENT_BINDING_OR_PERMISSION_UNPROVEN";
        }
        String eventId=eventId(key);
        String status=reason.isEmpty()?"READY":"BLOCKED";
        Db.exec("INSERT INTO s01_notification_events(event_key,event_id,demand_id,source_payload,source_hash,organization_code,filler_code,business_moment,event_type,event_intent,event_destination,approval_version,acceptance_demand,document_revision,occurred_at,rule_version,delivery_status,blocked_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                key,eventId,demand,canonical,source.hash,source.organization,source.filler,source.moment==null?null:source.moment.name(),rule==null?null:rule.type().name(),rule==null?null:rule.intent().name(),rule==null?null:rule.destination().name(),source.approvalVersion==0?null:source.approvalVersion,source.acceptance?demand:null,source.documentRevision==0?null:source.documentRevision,source.at==null?created:source.at,rule==null?"S01-UNSUPPORTED-v1":rule.version(),status,reason);
        if (recipient!=null && reason.isEmpty())
            Db.exec("INSERT INTO s01_notifications(notice_id,event_key,account_id,person_code,person_organization,configuration_version) VALUES(?,?,?,?,?,?)",
                    noticeId(eventId,Long.toString(recipient.account)),key,recipient.account,recipient.person,recipient.organization,configuration.version());
        // workflow_outbox remains PENDING: this is only an IN_APP projection, not external delivery.
        return receipt(status,reason,reason.isEmpty()?1:0);
    }

    public static boolean handle(HttpExchange ex,Auth.Session supplied) throws Exception {
        String path=ex.getRequestURI().getPath();
        if (!path.equals("/api/notifications") && !path.startsWith("/api/notifications/")) return false;
        synchronized(Api.MUTATION_LOCK) {
            if (Auth.get(Api.token(ex))!=supplied || Auth.current(supplied)==null) fail(401,"登录会话无效或已失效");
            Map<String,String> query=Api.query(ex);
            if (path.equals("/api/notifications") || path.equals("/api/notifications/diagnostics")) {
                method(ex,"GET"); whitelist(query.keySet(),Set.of("offset","limit"));
                int offset=page(query.get("offset"),0,0,Integer.MAX_VALUE),limit=page(query.get("limit"),20,1,100);
                Api.ok(ex,path.endsWith("diagnostics")?diagnostics(supplied,offset,limit):list(supplied,offset,limit)); return true;
            }
            if (!query.isEmpty()) fail(400,"本接口不接受身份、收件人或跳转地址参数");
            if (path.equals("/api/notifications/channels")) {
                method(ex,"GET"); current(supplied);
                Api.ok(ex,Map.of("channels",channelStatus(true,Set.of(Channel.EMAIL)))); return true;
            }
            String[] parts=path.split("/");
            if (parts.length<4 || parts.length>5) fail(404,"通知接口不存在");
            String id=parts[3]; validateId(id);
            if (parts.length==4) { method(ex,"GET"); Api.ok(ex,Map.of("notification",detail(supplied,id))); }
            else if (parts[4].equals("target")) { method(ex,"GET"); Api.ok(ex,Map.of("target",target(supplied,id))); }
            else if (parts[4].equals("read")) {
                method(ex,"POST");
                String ct=ex.getRequestHeaders().getFirst("Content-Type");
                if (ct==null || !ct.split(";",2)[0].trim().equalsIgnoreCase("application/json")) fail(415,"请使用JSON请求");
                if (!Api.body(ex).isEmpty()) fail(400,"已读请求不接受收件人或业务内容");
                Api.ok(ex,read(supplied,id));
            } else fail(404,"通知接口不存在");
            return true;
        }
    }

    public static Map<String,Object> list(Auth.Session session,int offset,int limit) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            pagination(offset,limit); Access access=current(session);
            List<Map<String,Object>> items=new ArrayList<>(); long total=0,unread=0;
            // Scan in bounded chunks: filtering before pagination/counting prevents revoked
            // records from leaking through totals or consuming the caller's requested page.
            for(long scan=0;;scan+=200) {
                List<Map<String,Object>> rows=Db.query(JOIN+"WHERE n.account_id=? ORDER BY CAST(e.occurred_at AS TIMESTAMP WITH TIME ZONE) DESC,n.notice_id DESC LIMIT 200 OFFSET ?",session.uid,scan);
                for (Map<String,Object> row:rows) if (visible(row,access)) {
                    if (row.get("read_at")==null) unread++;
                    if (total>=offset && items.size()<limit) items.add(view(row,access));
                    total++;
                }
                if(rows.size()<200)break;
            }
            return Map.of("items",items,"total",total,"unreadCount",unread,"offset",offset,"limit",limit);
        }
    }

    public static Map<String,Object> detail(Auth.Session session,String id) throws Exception {
        synchronized(Api.MUTATION_LOCK) { Access a=current(session); return view(owned(id,a),a); }
    }

    public static Map<String,Object> read(Auth.Session session,String id) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if (!Db.get().getAutoCommit()) throw new IllegalStateException("已读接口不能嵌入业务事务");
            return Db.transaction(()->{
                Access a=current(session); Map<String,Object> row=owned(id,a);
                if (row.get("read_at")==null) {
                    Instant at=Instant.now(),event=Instant.parse(string(row,"occurred_at")); if(at.isBefore(event)) at=event;
                    Db.exec("UPDATE s01_notifications SET read_at=? WHERE notice_id=? AND account_id=? AND read_at IS NULL",at.toString(),id,session.uid);
                }
                return Map.of("id",id,"readAt",Db.one("SELECT read_at FROM s01_notifications WHERE notice_id=?",id).get("read_at"));
            });
        }
    }

    public static Map<String,Object> target(Auth.Session session,String id) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Access a=current(session); Map<String,Object> row=owned(id,a);
            return NotificationChannels.target(notice(row),Long.toString(session.uid),authority(row,a));
        }
    }

    /** Explicit read-only diagnostics; never consumes/backfills or updates outbox status. */
    public static Map<String,Object> diagnostics(Auth.Session session,int offset,int limit) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            pagination(offset,limit); Access a=current(session); boolean allowed=false;
            for (OrganizationAccess.Organization org:a.configuration.organizations())
                if (authorized(a.configuration,session.uid,"notifications.audit",org.organizationCode()) && authorized(a.configuration,session.uid,"demand.read",org.organizationCode())) {allowed=true;break;}
            if (!allowed) fail(403,"没有通知核查权限");
            List<Map<String,Object>> items=new ArrayList<>(); long total=0;
            for(long scan=0;;scan+=200) {
                List<Map<String,Object>> rows=Db.query("SELECT o.event_key,o.demand_id,o.created_at,e.delivery_status,e.blocked_reason,d.organization_code FROM workflow_outbox o JOIN workflow_demands d ON d.demand_id=o.demand_id LEFT JOIN s01_notification_events e ON e.event_key=o.event_key WHERE e.event_key IS NULL OR e.delivery_status='BLOCKED' ORDER BY CAST(o.created_at AS TIMESTAMP WITH TIME ZONE) DESC,o.event_key DESC LIMIT 200 OFFSET ?",scan);
                for(Map<String,Object> row:rows) if(authorized(a.configuration,session.uid,"notifications.audit",string(row,"organization_code")) && authorized(a.configuration,session.uid,"demand.read",string(row,"organization_code"))) {
                    if(total>=offset && items.size()<limit) items.add(Map.of("eventId",eventId(string(row,"event_key")),"recordId",Long.toString(number(row,"demand_id")),"createdAt",string(row,"created_at"),"status","BLOCKED","reason",row.get("delivery_status")==null?"HISTORICAL_BINDING_UNPROVEN":string(row,"blocked_reason")));
                    total++;
                }
                if(rows.size()<200)break;
            }
            return Map.of("items",items,"total",total,"offset",offset,"limit",limit);
        }
    }

    public static void guardAccountDeletion(long accountId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if (Db.one("SELECT notice_id FROM s01_notifications WHERE account_id=? LIMIT 1",accountId)!=null)
                fail(409,"账号仍被历史通知引用，请停用而非删除");
        }
    }

    private record RecipientBinding(long account,String person,String organization) {}
    private record Source(String organization,String filler,BusinessMoment moment,String recipient,long approvalVersion,long documentRevision,boolean acceptance,String at,String hash) {}
    private static Source source(String key,long id,Map<String,Object> payload,boolean fresh) throws Exception {
        Map<String,Object> demand=Db.one("SELECT * FROM workflow_demands WHERE demand_id=?",id);
        if (demand==null) {fail(409,"通知来源需求不存在");return null;}
        String org=string(demand,"organization_code"),filler=string(demand,"filler_code"),kind=string(payload,"kind"),recipient="",at=null;
        BusinessMoment moment=null;long version=0,revision=0;boolean acceptance=false;
        Map<String,Object> anchor=new LinkedHashMap<>(Map.of("organization",org,"filler",filler,"path",string(demand,"business_path")));
        if (kind.equals("TEAM_ACCEPTED")) {
            Map<String,Object> accepted=Db.one("SELECT * FROM workflow_acceptances WHERE demand_id=?",id);
            if (accepted==null || !key.equals("m02:"+id+":accepted")) fail(409,"承接通知来源不一致");
            Map<String,Object> expected=Map.of("kind","TEAM_ACCEPTED","recipient",filler,"project_id",number(accepted,"project_id"));
            if (!canonical(expected).equals(canonical(payload))) fail(409,"承接通知收件人与原事项不一致");
            anchor.put("acceptance",accepted);recipient=filler;moment=BusinessMoment.TEAM_ACCEPTED;acceptance=true;
            revision=number(accepted,"data_revision");at=string(accepted,"created_at");
        } else if (Set.of("TODO","RETURNED","TEAM_READY").contains(kind)) {
            version=number(payload,"event_version");
            Map<String,Object> stored=Db.one("SELECT * FROM approval_events WHERE workflow_id=? AND version=?",id,version);
            Map<String,Object> workflow=Db.one("SELECT * FROM approval_workflows WHERE id=?",id);
            if (stored==null || workflow==null || fresh && number(workflow,"version")!=version) fail(409,"审批通知须与本次持久化审批事件一致");
            Map<String,Object> event=json(string(stored,"event_json")),state=json(string(workflow,"state_json")),participants=object(state.get("participants"));
            if (!filler.equals(string(participants,"submitterId"))) fail(409,"原填报人与审批记录不一致");
            String action=string(event,"action"),to=string(event,"to"),target="PARTICIPANT";
            if (kind.equals("TODO") && Set.of("SUBMIT","RESUBMIT").contains(action) && to.equals("LEADER_PENDING")) {moment=BusinessMoment.SUBMITTED;recipient=string(participants,"leaderId");}
            else if (kind.equals("TODO") && action.equals("APPROVE") && to.equals("BP_PENDING")) {moment=BusinessMoment.LEADER_APPROVED;recipient=string(participants,"bpId");}
            else if (kind.equals("TEAM_READY") && action.equals("APPROVE") && string(event,"stage").equals("BP") && to.equals("READY_FOR_TEAM")) {moment=BusinessMoment.BP_APPROVED;target="TEAM_QUEUE";}
            else if (kind.equals("TEAM_READY") && action.equals("APPROVE_COMBINED") && to.equals("READY_FOR_TEAM")) {
                WorkflowIntegration.validateCombinedNotice(id,event);
                anchor.put("combinedPolicy",state.get("policy"));
                moment=BusinessMoment.BP_APPROVED;target="TEAM_QUEUE";
            }
            else if (kind.equals("RETURNED") && action.equals("RETURN") && to.equals("RETURNED")) {moment=BusinessMoment.RETURNED;recipient=filler;}
            else if (kind.equals("TODO") && action.equals("REVISE")) {recipient=string(payload,"recipient");}
            else fail(409,"通知类型与原审批事件不一致");
            Map<String,Object> expected=Map.of("kind",kind,"target",target,"recipient",recipient,"event_version",version);
            if(!canonical(expected).equals(canonical(payload)) || !key.equals("m03:"+id+":"+version+":"+kind+":"+target+":"+recipient)) fail(409,"通知岗位与审批事件不一致");
            anchor.put("event",event);anchor.put("participants",participants);anchor.put("policyVersion",state.get("policyVersion"));
            // REVISE is recorded before its new document INSERT in the host; it is not a
            // configured S01 business moment and must not interrupt that approved write.
            revision=moment==null?0:number(event,"dataRevision");at=string(event,"at");
        }
        if(revision>0) {
            Map<String,Object> document=Db.one("SELECT document_json FROM workflow_documents WHERE demand_id=? AND data_revision=?",id,revision);
            if(document==null) fail(409,"通知对应的原资料版本不存在");
            anchor.put("document",json(string(document,"document_json")));
        }
        return new Source(org,filler,moment,recipient,version,revision,acceptance,at,digest(canonical(anchor)));
    }

    private static RecipientBinding resolve(OrganizationAccess.Configuration c,String person,String businessOrg) throws Exception {
        if(c==null || person.isEmpty()) return null;
        List<OrganizationAccess.AccountBinding> bindings=c.accountBindings().stream().filter(b->b.enabled()&&b.personCode().equals(person)).toList();
        if(bindings.size()!=1) return null;
        long uid=bindings.get(0).accountId();
        Map<String,Object> user=Db.one("SELECT status FROM users WHERE id=?",uid);
        OrganizationAccess.Person p=c.people().stream().filter(v->v.personCode().equals(person)).findFirst().orElse(null);
        if(user==null || number(user,"status")!=1 || p==null || !p.enabled() || !authorized(c,uid,"demand.read",businessOrg) || !authorized(c,uid,"notifications.read",businessOrg)) return null;
        return new RecipientBinding(uid,person,p.organizationCode());
    }

    private static final class Access {
        final Auth.Session session;final OrganizationAccess.Person person;final OrganizationAccess.Configuration configuration;
        final Map<String,OrganizationAccess.Configuration> history=new HashMap<>();
        Access(Auth.Session s,OrganizationAccess.Person p,OrganizationAccess.Configuration c) {session=s;person=p;configuration=c;history.put(c.version(),c);}
    }
    private static Access current(Auth.Session session) throws Exception {
        if(Auth.current(session)==null) {fail(401,"登录会话无效或已失效");return null;}
        return new Access(session,OrganizationAccessStore.person(session),OrganizationAccessStore.configuration());
    }
    private static Map<String,Object> owned(String id,Access a) throws Exception {
        validateId(id);Map<String,Object> row=Db.one(JOIN+"WHERE n.notice_id=? AND n.account_id=?",id,a.session.uid);
        if(row==null || !visible(row,a)) fail(404,"通知不存在或当前无权访问");return row;
    }
    private static boolean visible(Map<String,Object> row,Access a) throws Exception {
        if(number(row,"account_id")!=a.session.uid || !string(row,"person_code").equals(a.person.personCode()) || !string(row,"person_organization").equals(a.person.organizationCode())) return false;
        if(!continuous(row,a)) return false;
        Map<String,Object> outbox=Db.one("SELECT demand_id,payload FROM workflow_outbox WHERE event_key=?",string(row,"event_key"));
        if(outbox==null || number(outbox,"demand_id")!=number(row,"demand_id") || !canonical(json(string(outbox,"payload"))).equals(string(row,"source_payload"))) return false;
        try {
            Source source=source(string(row,"event_key"),number(row,"demand_id"),json(string(row,"source_payload")),false);
            return source.hash.equals(string(row,"source_hash")) && source.organization.equals(string(row,"organization_code")) && source.filler.equals(string(row,"filler_code"));
        } catch(Api.ApiException|IllegalArgumentException e) {return false;}
    }
    /** Rebinding/revocation followed by restoration must not resurrect another identity's history. */
    private static boolean continuous(Map<String,Object> row,Access a) throws Exception {
        String version=a.configuration.version(),first=string(row,"configuration_version");Set<String> seen=new HashSet<>();
        while(version!=null && seen.add(version) && seen.size()<=MAX_ROWS) {
            Map<String,Object> saved=Db.one("SELECT payload,previous_version FROM organization_access_config WHERE version=?",version);
            if(saved==null) return false;
            OrganizationAccess.Configuration c=a.history.get(version);
            if(c==null) {c=OrganizationAccessStore.parseConfiguration(Json.parse(string(saved,"payload")));if(!c.version().equals(version)||!OrganizationAccess.validate(c).valid())return false;a.history.put(version,c);}
            RecipientBinding identity=resolve(c,string(row,"person_code"),string(row,"organization_code"));
            if(identity==null || identity.account!=a.session.uid || !identity.organization.equals(string(row,"person_organization"))) return false;
            if(version.equals(first)) return true;
            version=saved.get("previous_version")==null?null:string(saved,"previous_version");
        }
        return false;
    }
    private static boolean authorized(OrganizationAccess.Configuration c,long account,String resource,String org) {
        return new OrganizationAccess.Engine(c).authorize("server-notification-binding",ignored->OptionalLong.of(account),new OrganizationAccess.Resource(resource,org),OrganizationAccess.Action.VIEW).allowed();
    }
    private static Notice notice(Map<String,Object> row) {
        // Type, intent and destination are frozen, not reinterpreted by future rule versions.
        Destination destination=Destination.valueOf(string(row,"event_destination"));
        Event event=new Event(string(row,"event_id"),EventType.valueOf(string(row,"event_type")),number(row,"demand_id"),destination==Destination.M03?Long.toString(number(row,"demand_id")):null,destination,Instant.parse(string(row,"occurred_at")));
        return new Notice(string(row,"notice_id"),event,new Recipient(Long.toString(number(row,"account_id")),Intent.valueOf(string(row,"event_intent"))),string(row,"rule_version"));
    }
    private static Map<String,Object> view(Map<String,Object> row,Access a) throws Exception {
        return NotificationChannels.view(notice(row),Long.toString(a.session.uid),authority(row,a),row.get("read_at")==null?null:Instant.parse(string(row,"read_at")));
    }
    private static Authority authority(Map<String,Object> row,Access a) throws Exception {
        Map<String,Object> detail=WorkflowIntegration.detail(number(row,"demand_id"),a.session),approval=object(detail.get("approval"));
        BusinessMoment moment=BusinessMoment.valueOf(string(row,"business_moment"));
        boolean pending=false;
        if((moment==BusinessMoment.SUBMITTED||moment==BusinessMoment.LEADER_APPROVED) && number(approval,"version")==number(row,"approval_version") && string(approval,"assigneeId").equals(a.person.personCode()))
            pending=enabledAction(approval,"APPROVE")||enabledAction(approval,"APPROVE_COMBINED");
        else if(moment==BusinessMoment.RETURNED && number(approval,"version")==number(row,"approval_version")) pending=enabledAction(approval,"RESUBMIT");
        final ActionState state=pending?ActionState.PENDING:ActionState.RESOLVED;
        return new Authority() {
            public Audience audience(Event event) {throw new UnsupportedOperationException("Frozen recipients only");}
            public boolean active(String user) {return user.equals(Long.toString(a.session.uid));}
            public boolean canView(String user,long id) {return active(user)&&id==number(row,"demand_id");}
            public ActionState actionState(String user,Event event) {return state;}
        };
    }
    private static boolean enabledAction(Map<String,Object> approval,String name) {
        Object actions=approval.get("actions");if(!(actions instanceof List<?> list))return false;
        return list.stream().map(NotificationChannelsIntegration::object).anyMatch(a->name.equals(a.get("action"))&&Boolean.TRUE.equals(a.get("enabled")));
    }
    private static int noticeCount(String key) throws Exception {return (int)number(Db.one("SELECT COUNT(*) AS n FROM s01_notifications WHERE event_key=?",key),"n");}
    private static Map<String,Object> receipt(String status,String reason,int count) {return Map.of("status",status,"reason",reason,"noticeCount",count);}
    private static void requireTransaction() throws SQLException {
        if(!Thread.holdsLock(Api.MUTATION_LOCK)||Db.get().getAutoCommit())throw new IllegalStateException("通知事件必须在宿主业务锁和已有事务内保存");
    }
    private static void pagination(int offset,int limit) throws Api.ApiException {if(offset<0||limit<1||limit>100)fail(400,"通知分页范围无效");}
    private static int page(String value,int fallback,int min,int max) throws Api.ApiException {if(value==null)return fallback;try{if(!value.matches("[0-9]{1,10}"))throw new NumberFormatException();int n=Integer.parseInt(value);if(n<min||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException e){fail(400,"通知分页范围无效");return 0;}}
    private static void validateId(String id) throws Api.ApiException {if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))fail(404,"通知不存在或当前无权访问");}
    private static void whitelist(Set<String> actual,Set<String> allowed) throws Api.ApiException {if(!allowed.containsAll(actual))fail(400,"通知接口包含不允许的参数");}
    private static void method(HttpExchange ex,String method) throws Api.ApiException {if(!ex.getRequestMethod().equals(method)){ex.getResponseHeaders().set("Allow",method);fail(405,"请求方法不受支持");}}
    private static String eventId(String key) {return UUID.nameUUIDFromBytes(("S01-event\n"+key).getBytes(StandardCharsets.UTF_8)).toString();}
    private static String digest(String value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static String canonical(Object value) {return Json.write(ordered(value));}
    private static Object ordered(Object value) {if(value instanceof Map<?,?> m){Map<String,Object> result=new TreeMap<>();for(var e:m.entrySet())result.put(e.getKey().toString(),ordered(e.getValue()));return result;}if(value instanceof List<?> list)return list.stream().map(NotificationChannelsIntegration::ordered).toList();if(value instanceof Number n)return new java.math.BigDecimal(n.toString()).stripTrailingZeros();return value;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();}
    private static Map<String,Object> json(String value) {Object parsed=Json.parse(value);if(!(parsed instanceof Map<?,?>))throw new IllegalArgumentException("通知来源不是对象");return object(parsed);}
    private static String string(Map<String,Object> row,String key) {Object v=row.get(key);return v==null?"":v.toString();}
    private static long number(Map<String,Object> row,String key) {Object v=row.get(key);return v instanceof Number?((Number)v).longValue():0;}
    private static void fail(int status,String message) throws Api.ApiException {throw new Api.ApiException(status,message);}
}
