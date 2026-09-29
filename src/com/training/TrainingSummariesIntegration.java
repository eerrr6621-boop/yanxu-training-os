package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.training.TrainingSummaries.*;

/** Versioned M08 summaries with explicitly configured review and export. No implicit grants; immutable project photos are versioned with content. */
public final class TrainingSummariesIntegration {
    private TrainingSummariesIntegration() {}
    private static final String BASE = "/api/training-summaries";
    private static final String FORMAT = "M08-DRAFT-20260922-1";
    private static final String GENESIS = "0".repeat(64);
    private static final long MAX_SAFE = 9007199254740991L;
    private static final int MAX_REVISIONS = 1000;
    private static final long MAX_HISTORY_CHARS = 16L * 1024 * 1024;
    private static final int MAX_SOURCE_JSON = 256000;
    private static final int MAX_CONTENT_JSON = 1200000; // escaped control text can be larger than normal prose
    private static final String SOURCE_SQL = "SELECT p.id,p.demand_id AS project_demand_id,p.status,p.start_date,p.end_date,p.participant_count," +
            "a.demand_id,a.data_revision AS accepted_revision,a.team_code,a.actor_code AS accepted_by,a.created_at AS accepted_at," +
            "w.organization_code,w.data_revision AS current_revision,w.version AS workflow_version,w.draft " +
            "FROM projects p JOIN workflow_acceptances a ON a.project_id=p.id JOIN workflow_demands w ON w.demand_id=a.demand_id WHERE p.id=?";

    /** Startup only, after original tables and WorkflowIntegration.init. Creates empty tables; no backfill. */
    public static void init() throws SQLException {
        synchronized (Api.MUTATION_LOCK) {
            if (!Db.get().getAutoCommit()) throw new SQLException("总结初始化不能嵌入事务");
            Db.exec("CREATE TABLE IF NOT EXISTS m08_summary_heads(project_id BIGINT PRIMARY KEY REFERENCES projects(id),demand_id BIGINT NOT NULL REFERENCES workflow_demands(demand_id),organization_code VARCHAR(120) NOT NULL,version BIGINT NOT NULL CHECK(version>0),head_hash VARCHAR(64) NOT NULL)");
            Db.exec("CREATE TABLE IF NOT EXISTS m08_summary_revisions(project_id BIGINT NOT NULL REFERENCES m08_summary_heads(project_id),revision BIGINT NOT NULL CHECK(revision>0),operation VARCHAR(24) NOT NULL,actor_code VARCHAR(120) NOT NULL,account_id BIGINT NOT NULL,config_version VARCHAR(128) NOT NULL,saved_at VARCHAR(40) NOT NULL,content_json CLOB NOT NULL,sources_json CLOB NOT NULL,previous_hash VARCHAR(64) NOT NULL,revision_hash VARCHAR(64) NOT NULL,PRIMARY KEY(project_id,revision))");
            Db.exec("CREATE TABLE IF NOT EXISTS m08_summary_requests(account_id BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,actor_code VARCHAR(120) NOT NULL,project_id BIGINT NOT NULL,operation VARCHAR(24) NOT NULL,payload_hash VARCHAR(64) NOT NULL,result_revision BIGINT NOT NULL,PRIMARY KEY(account_id,request_id),FOREIGN KEY(project_id,result_revision) REFERENCES m08_summary_revisions(project_id,revision))");
            TrainingSummariesWorkflow.init();
            TrainingSummariesPhotos.init();
        }
    }

    public static boolean matches(String path) { return BASE.equals(path) || (path != null && path.startsWith(BASE + "/")); }

    /** Mount in the normal authenticated Api route; retain its bounded JSON reader and delayed response flush. */
    public static boolean handle(HttpExchange ex, Auth.Session supplied) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (!matches(path)) return false;
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session session = Auth.get(Api.token(ex));
            if (session == null || session != supplied || Auth.current(session) != session) fail(401, "登录会话无效或已失效");
            String op = path.equals(BASE) ? "" : path.substring(BASE.length() + 1);
            if (op.equals("photos/upload")) {
                method(ex,"POST"); query(ex,Set.of());
                String type=ex.getRequestHeaders().getFirst("Content-Type");
                if(type==null||!"application/json".equalsIgnoreCase(type.split(";",2)[0].trim()))fail(415,"照片上传仅接受application/json");
                Api.ok(ex,TrainingSummariesPhotos.upload(session,Api.body(ex)));return true;
            }
            if (op.equals("photos/content")) {
                method(ex,"GET");Set<String> keys=Set.of("project_id","photo_id","revision");Map<String,String> q=query(ex,keys);
                if(!q.keySet().equals(keys))fail(400,"读取照片须指定项目、照片和总结版本");
                var photo=TrainingSummariesPhotos.read(session,queryInteger(q.get("project_id"),false),q.get("photo_id"),queryInteger(q.get("revision"),true));
                Api.file(ex,photo.bytes(),photo.contentType(),photo.filename());return true;
            }
            if (op.equals("workflow") || op.equals("workflow-history")) {
                method(ex, "GET");
                Map<String,String> q=query(ex,op.equals("workflow")?Set.of("project_id"):Set.of("project_id","offset","limit"));
                long project=queryInteger(q.get("project_id"),false);
                Api.ok(ex,op.equals("workflow")?TrainingSummariesWorkflow.read(session,project):TrainingSummariesWorkflow.history(session,project,(int)queryBound(q.getOrDefault("offset","0"),0,2000),(int)queryBound(q.getOrDefault("limit","20"),1,100)));
                return true;
            }
            if (op.equals("comparison")) {
                method(ex, "GET");
                Set<String> keys = Set.of("project_id", "from_revision", "to_revision");
                Map<String, String> q = query(ex, keys);
                if (!q.keySet().equals(keys)) fail(400, "版本对照须提供project_id、from_revision和to_revision");
                Api.ok(ex, comparison(session, queryInteger(q.get("project_id"), false),
                        queryInteger(q.get("from_revision"), true), queryInteger(q.get("to_revision"), false)));
                return true;
            }
            if (Set.of("", "history", "revision").contains(op)) {
                method(ex, "GET");
                Map<String, String> q = query(ex, op.equals("history") ? Set.of("project_id", "offset", "limit") : op.equals("revision") ? Set.of("project_id", "revision") : Set.of("project_id"));
                long project = queryInteger(q.get("project_id"), false);
                Object result = op.equals("history") ? history(session, project, (int) queryBound(q.getOrDefault("offset", "0"), 0, MAX_REVISIONS), (int) queryBound(q.getOrDefault("limit", "20"), 1, 100))
                        : op.equals("revision") ? revision(session, project, queryInteger(q.get("revision"), false)) : read(session, project);
                Api.ok(ex, result);
            } else if (Set.of("save", "refresh", "submit", "review", "export").contains(op)) {
                method(ex, "POST"); query(ex, Set.of());
                String ct = ex.getRequestHeaders().getFirst("Content-Type");
                if (ct == null || !"application/json".equalsIgnoreCase(ct.split(";", 2)[0].trim())) fail(415, "仅接受 application/json 请求");
                if(op.equals("export")) {
                    TrainingSummariesWorkflow.Download download=TrainingSummariesWorkflow.export(session,Api.body(ex));
                    Api.file(ex,download.bytes(),TrainingSummariesWorkflow.DOCX,download.filename());
                } else Api.ok(ex, mutate(op, session, Api.body(ex)));
            } else fail(404, "总结接口不存在");
            return true;
        }
    }

    public static Map<String, Object> read(Auth.Session session, long projectId) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            noNestedTransaction();
            Context context = context(session, projectId, false);
            Head head = head(context.source());
            Stored current = head == null ? null : stored(head, head.version());
            revalidate(session, context, false);
            return response(session, context, head, current);
        }
    }

    public static Map<String, Object> history(Auth.Session session, long projectId, int offset, int limit) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            noNestedTransaction();
            Context context = context(session, projectId, false);
            if (offset < 0 || offset > MAX_REVISIONS || limit < 1 || limit > 100) fail(400, "历史分页参数无效");
            Head head = head(context.source());
            List<Map<String, Object>> items = new ArrayList<>();
            if (head != null) {
                long high = head.version() - offset;
                for (long revision = high; revision > 0 && items.size() < limit; revision--) items.add(metadata(stored(head, revision)));
            }
            revalidate(session, context, false);
            return map("project_id", projectId, "items", items, "total", head == null ? 0L : head.version(), "offset", offset, "limit", limit, "synthetic", false);
        }
    }

    public static Map<String, Object> revision(Auth.Session session, long projectId, long revision) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            noNestedTransaction();
            Context context = context(session, projectId, false);
            Head head = head(context.source());
            if (head == null || revision <= 0 || revision > head.version()) fail(404, "总结版本不存在");
            Stored record = stored(head, revision);
            revalidate(session, context, false);
            return revisionView(record, TrainingSummariesDeliverySource.canRead(session, context.source().org()), TrainingSummariesFeedbackSource.canRead(session, context.source().org()));
        }
    }

    /** Two explicitly saved revisions, projected under one current permission decision; no backend diff. */
    public static Map<String, Object> comparison(Auth.Session session, long projectId, long fromRevision, long toRevision) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            noNestedTransaction();
            if (fromRevision < 0 || toRevision <= fromRevision || toRevision > MAX_SAFE)
                fail(400, "对照版本须为安全整数且满足0<=from_revision<to_revision");
            Context context = context(session, projectId, false);
            Head head = head(context.source());
            if (head == null || toRevision > head.version()) fail(404, "总结版本不存在");
            Stored before = fromRevision == 0 ? null : stored(head, fromRevision);
            Stored after = stored(head, toRevision);
            revalidate(session, context, false);
            boolean deliveryVisible = TrainingSummariesDeliverySource.canRead(session, context.source().org());
            boolean feedbackVisible = TrainingSummariesFeedbackSource.canRead(session, context.source().org());
            return map("project_id", projectId, "from_revision", fromRevision, "to_revision", toRevision,
                    "latest_version", head.version(), "before", before == null ? null : revisionView(before, deliveryVisible, feedbackVisible),
                    "after", revisionView(after, deliveryVisible, feedbackVisible), "read_only", true, "synthetic", false, "draft_only", true);
        }
    }

    /** Full content replacement on save. Refresh retains the saved content and appends a source snapshot. */
    public static Map<String, Object> mutate(String operation, Auth.Session session, Map<String, Object> input) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            noNestedTransaction();
            OrganizationAccessStore.person(session);
            if (Set.of("submit", "review").contains(operation)) return TrainingSummariesWorkflow.mutate(operation,session,input);
            if (operation.equals("export")) fail(400,"Word导出须使用专用文件响应接口");
            if (!Set.of("save", "refresh").contains(operation)) fail(404, "总结操作不存在");
            Map<String, Object> body = object(input, operation.equals("save") ? Set.of("project_id", "expected_version", "request_id", "content", "photos") : Set.of("project_id", "expected_version", "request_id"));
            long projectId = number(body.get("project_id"), false), expected = number(body.get("expected_version"), true);
            String requestId = requestId(body.get("request_id"));
            Content content = operation.equals("save") ? parseContent(body.get("content")) : null;
            Map<String,Object> payload=map("operation",operation,"project_id",projectId,"expected_version",expected,"content",content==null?null:contentMap(content));
            if(body.containsKey("photos"))payload.put("photos",body.get("photos"));
            String payloadHash=hash(canonical(payload));
            try {
                return Db.transaction(() -> {
                    Context context = context(session, projectId, true); // before every idempotency replay
                    Head before = head(context.source());
                    Map<String, Object> retry = Db.one("SELECT * FROM m08_summary_requests WHERE account_id=? AND request_id=?", context.account(), requestId);
                    if (retry != null) {
                        if (!context.actor().equals(retry.get("actor_code")) || projectId != number(retry.get("project_id"), false)
                                || !operation.equals(retry.get("operation")) || !payloadHash.equals(retry.get("payload_hash"))) fail(409, "请求编号已用于不同内容或人员绑定");
                        if (before == null) corrupt();
                        Stored result = stored(before, number(retry.get("result_revision"), false));
                        revalidate(session, context, true);
                        Map<String, Object> replay = response(session, context, before, result);
                        replay.put("replayed", true);
                        return replay;
                    }
                    TrainingSummariesWorkflow.guardEdit(workflowSnapshot(context,before));
                    long actual = before == null ? 0 : before.version();
                    if (expected != actual) fail(409, "总结已更新，请保留编辑并重新读取版本");
                    if (actual >= MAX_REVISIONS) fail(409, "总结修订数量达到当前技术上限，请联系管理员处理");
                    Stored old = before == null ? null : stored(before, actual);
                    if (operation.equals("refresh") && old == null) fail(409, "请先保存草稿，再刷新业务来源");
                    // A content save preserves its original source snapshot. Only explicit refresh replaces it.
                    String sources = old == null ? context.source().json() : operation.equals("refresh") ? refreshedSources(session, context, old) : old.sourcesJson();
                    Content next = content == null ? old.content() : content;
                    List<TrainingSummariesPhotos.Ref> photos=body.containsKey("photos")?TrainingSummariesPhotos.resolve(projectId,context.source().demand(),context.source().org(),body.get("photos")):old==null?List.of():old.photos();
                    if (historyChars(projectId) + canonical(contentMap(next,photos)).length() + sources.length() > MAX_HISTORY_CHARS) fail(409, "总结历史正文达到当前容量上限，请联系管理员处理");
                    long version = actual + 1;
                    Stored added = makeStored(projectId, version, actual == 0 ? "CREATE" : operation.equals("refresh") ? "REFRESH_SOURCES" : "SAVE",
                            context, next, photos, sources, before == null ? GENESIS : before.hash());
                    if (before == null) {
                        Db.exec("INSERT INTO m08_summary_heads(project_id,demand_id,organization_code,version,head_hash) VALUES(?,?,?,?,?)",
                                projectId, context.source().demand(), context.source().org(), version, added.hash());
                    } else {
                        try (PreparedStatement statement = Db.get().prepareStatement("UPDATE m08_summary_heads SET version=?,head_hash=? WHERE project_id=? AND version=? AND head_hash=?")) {
                            statement.setLong(1, version); statement.setString(2, added.hash()); statement.setLong(3, projectId); statement.setLong(4, actual); statement.setString(5, before.hash());
                            if (statement.executeUpdate() != 1) fail(409, "总结已更新，请重新读取后保存");
                        }
                    }
                    Db.exec("INSERT INTO m08_summary_revisions(project_id,revision,operation,actor_code,account_id,config_version,saved_at,content_json,sources_json,previous_hash,revision_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            projectId, version, added.operation(), added.actor(), added.account(), added.config(), added.savedAt(), canonical(contentMap(next,photos)), sources, added.previousHash(), added.hash());
                    revalidate(session, context, true);
                    Db.exec("INSERT INTO m08_summary_requests(account_id,request_id,actor_code,project_id,operation,payload_hash,result_revision) VALUES(?,?,?,?,?,?,?)",
                            context.account(), requestId, context.actor(), projectId, operation, payloadHash, version);
                    revalidate(session, context, true);
                    return response(session, context, new Head(projectId, context.source().demand(), context.source().org(), version, added.hash()), added);
                });
            } catch (SQLException conflict) {
                if ("23505".equals(conflict.getSQLState()) || "40001".equals(conflict.getSQLState())) fail(409, "总结并发更新冲突，请重试原请求或刷新版本");
                throw conflict;
            }
        }
    }

    record WorkflowSnapshot(long project,String organization,String projectStatus,long revision,String author,String revisionHash,
            Content content,List<TrainingSummariesPhotos.Ref> photos,String sourcesJson,String currentSourcesJson,String actor,long account,String configurationVersion) {}
    record PhotoAccess(WorkflowSnapshot snapshot,long demand) {}
    static PhotoAccess photoAccess(Auth.Session session,long project,boolean write)throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK))throw new IllegalStateException("照片访问需要业务锁");
        Context context=context(session,project,write);Head head=head(context.source());WorkflowSnapshot snapshot=workflowSnapshot(context,head);
        if(write)TrainingSummariesWorkflow.guardEdit(snapshot);revalidate(session,context,write);return new PhotoAccess(snapshot,context.source().demand());
    }
    static List<TrainingSummariesPhotos.Ref> photoRevision(Auth.Session session,long project,long revision)throws Exception {
        Context context=context(session,project,false);Head head=head(context.source());
        if(head==null||revision<=0||revision>head.version())fail(404,"总结版本不存在");
        List<TrainingSummariesPhotos.Ref> refs=stored(head,revision).photos();revalidate(session,context,false);return refs;
    }
    static WorkflowSnapshot workflowSnapshot(Auth.Session session,long project) throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK))throw new IllegalStateException("总结流程须持有业务锁");
        Context context=context(session,project,false);Head head=head(context.source());
        WorkflowSnapshot snapshot=workflowSnapshot(context,head);revalidate(session,context,false);return snapshot;
    }
    private static WorkflowSnapshot workflowSnapshot(Context context,Head head)throws Exception {
        Stored saved=head==null?null:stored(head,head.version());
        return new WorkflowSnapshot(context.source().project(),context.source().org(),context.source().status(),saved==null?0:saved.revision(),saved==null?null:saved.actor(),saved==null?null:saved.hash(),saved==null?null:saved.content(),saved==null?List.of():saved.photos(),saved==null?context.source().json():saved.sourcesJson(),context.source().json(),context.actor(),context.account(),context.config());
    }
    static void requireWorkflowSources(Auth.Session session,WorkflowSnapshot snapshot)throws Exception {
        if(!TrainingSummariesDeliverySource.canRead(session,snapshot.organization()))fail(403,"提交、通过复核及导出需要本项目授课数据查看权限");
        if(!TrainingSummariesFeedbackSource.connected())fail(409,"正式问卷汇总来源尚未连接");
        if(!TrainingSummariesFeedbackSource.canRead(session,snapshot.organization()))fail(403,"提交、通过复核及导出需要本项目问卷汇总查看权限");
        Map<String,Object> stored=object(Json.parse(snapshot.sourcesJson()),Set.of("format","project","codes","feedback","delivery","source_version"));
        Map<String,Object> current=object(Json.parse(snapshot.currentSourcesJson()),Set.of("format","project","codes","feedback","delivery","source_version"));
        if(!canonical(workflowSources(stored)).equals(canonical(workflowSources(current))))fail(409,"业务来源已有变化，请退回或保存草稿并显式刷新来源后重新提交");
    }
    private static Map<String,Object> workflowSources(Map<String,Object> sources)throws Exception {
        Map<String,Object> facts=object(Json.parse(canonical(sources.get("project"))),Set.of("status","value","provenance","unavailable_fields"));
        Map<String,Object> values=object(facts.get("value"),Set.of("project_id","organization_code","project_code","course_codes","start_date","end_date","participant_count","project_status"));
        values.remove("project_status");facts.put("value",values);
        Map<String,Object> delivery=object(Json.parse(canonical(sources.get("delivery"))),Set.of("status","reason","policy_version","source_version","as_of_date","value","coverage"));
        delivery.remove("as_of_date");delivery.remove("source_version");
        return map("project",facts,"delivery",delivery,"feedback",sources.get("feedback"));
    }
    static String canonicalForWorkflow(Object value){return canonical(value);}
    private static Map<String,Object> projectSources(String json,boolean delivery,boolean feedback){return TrainingSummariesFeedbackSource.project(TrainingSummariesDeliverySource.project(json,delivery),feedback);}

    private record Source(long project, long demand, String org, String status, String json) {}
    private record Context(long account, String actor, String config, Source source) {}
    private record Head(long project, long demand, String org, long version, String hash) {}
    private record Stored(long project, long revision, String operation, String actor, long account, String config,
            String savedAt, Content content, List<TrainingSummariesPhotos.Ref> photos, String sourcesJson, String previousHash, String hash) {}

    private static Context context(Auth.Session session, long project, boolean write) throws Exception {
        OrganizationAccess.Person person = OrganizationAccessStore.person(session);
        if (project <= 0 || project > MAX_SAFE) fail(400, "项目编号无效");
        Source source = source(project);
        require(session, OrganizationAccess.SummaryPermission.READ, source.org());
        if (write) {
            require(session, OrganizationAccess.SummaryPermission.EDIT, source.org());
            if (!editable(source.status())) fail(409, "项目已归档或当前状态只允许查看总结");
        }
        OrganizationAccess.Configuration config = OrganizationAccessStore.configuration();
        if (config == null) fail(403, "组织权限尚未配置");
        Map<String, Object> sources = object(Json.parse(source.json()), Set.of("format", "project", "codes", "feedback", "delivery", "source_version"));
        sources.put("delivery", TrainingSummariesDeliverySource.capture(session, project, source.org()));
        sources.put("feedback", TrainingSummariesFeedbackSource.capture(session, project, source.org()));
        source = new Source(source.project(), source.demand(), source.org(), source.status(), sourceJson(sources));
        return new Context(session.uid, person.personCode(), config.version(), source);
    }

    private static void require(Auth.Session session, OrganizationAccess.SummaryPermission permission, String org) throws Exception {
        if (!OrganizationAccessStore.authorize(session, permission.resource(), permission.action(), org).allowed()) fail(403, "没有该项目机构范围内的总结操作权限");
    }

    private static void revalidate(Auth.Session session, Context expected, boolean write) throws Exception {
        Context now = context(session, expected.source().project(), write);
        if (!expected.equals(now)) fail(409, "账号绑定、权限配置或项目来源已变化，请重新读取");
    }

    private static boolean editable(String status) { return Set.of("待启动", "进行中", "已完成").contains(status); }

    /** Only M02 accepted project facts are available here; no raw surveys or guessed M05 figures. */
    private static Source source(long project) throws Exception {
        List<Map<String, Object>> rows = Db.query(SOURCE_SQL, project);
        if (rows.size() != 1) fail(403, "项目缺少唯一且可核实的受理来源");
        Map<String, Object> row = rows.get(0);
        try {
            long demand = number(row.get("demand_id"), false), accepted = number(row.get("accepted_revision"), false), workflow = number(row.get("workflow_version"), false);
            if (demand != number(row.get("project_demand_id"), false) || accepted != number(row.get("current_revision"), false) || !Boolean.FALSE.equals(row.get("draft"))) fail(403, "项目受理来源不一致或尚未生效");
            String org = requiredSourceText(row.get("organization_code"), 120), status = requiredSourceText(row.get("status"), 32);
            String team = requiredSourceText(row.get("team_code"), 120), by = requiredSourceText(row.get("accepted_by"), 120), at = requiredSourceText(row.get("accepted_at"), 40);
            Instant.parse(at);
            List<String> unavailable = new ArrayList<>();
            String start = sourceDate(row.get("start_date"), "start_date", unavailable), end = sourceDate(row.get("end_date"), "end_date", unavailable);
            if (start != null && end != null && start.compareTo(end) > 0) { unavailable.add("date_range"); start = null; end = null; }
            Long participants = null;
            try { if (row.get("participant_count") != null) participants = number(row.get("participant_count"), true); } catch (Api.ApiException invalid) { /* marked unavailable below */ }
            if (participants == null) unavailable.add("participant_count");
            Map<String, Object> provenance = map("demand_id", demand, "accepted_revision", accepted, "workflow_version", workflow, "team_code", team, "accepted_by", by, "accepted_at", at);
            Map<String, Object> facts = map("project_id", project, "organization_code", org, "project_code", null, "course_codes", null,
                    "start_date", start, "end_date", end, "participant_count", participants, "project_status", status);
            Map<String, Object> sources = map("format", FORMAT,
                    "project", map("status", "AVAILABLE", "value", facts, "provenance", provenance, "unavailable_fields", unavailable),
                    "codes", map("project", "UNASSIGNED", "courses", "UNASSIGNED"),
                    "feedback", map("status", "UNAVAILABLE", "reason", "M07_PREVIEW_ONLY", "value", null, "policyConfirmed", false, "canCommit", false, "historyAvailable", false),
                    "delivery", map("status", "UNAVAILABLE", "reason", "M05_SNAPSHOT_NOT_CONNECTED", "value", null));
            sources.put("source_version", hash(canonical(sources)));
            return new Source(project, demand, org, status, canonical(sources));
        } catch (Api.ApiException invalid) {
            if (invalid.code == 403) throw invalid;
            throw new Api.ApiException(409, "项目来源字段不可核实，请先核对业务资料");
        } catch (RuntimeException invalid) { throw new Api.ApiException(409, "项目来源字段不可核实，请先核对业务资料"); }
    }

    private static String sourceDate(Object value, String field, List<String> unavailable) {
        try { if (value instanceof String s && s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return LocalDate.parse(s).toString(); }
        catch (RuntimeException ignored) { }
        unavailable.add(field); return null;
    }

    /** Validate every historical payload as well as the append-only chain; never silently skip corruption. */
    private static Head head(Source source) throws Exception {
        Map<String, Object> row = Db.one("SELECT * FROM m08_summary_heads WHERE project_id=?", source.project());
        if (row == null) return null;
        try {
            long version = number(row.get("version"), false), demand = number(row.get("demand_id"), false);
            String org = requiredSourceText(row.get("organization_code"), 120), headHash = digest(row.get("head_hash"));
            if (version > MAX_REVISIONS || demand != source.demand() || !org.equals(source.org()) || historyChars(source.project()) > MAX_HISTORY_CHARS) corrupt();
            Head checked = new Head(source.project(), demand, org, version, headHash);
            List<Map<String, Object>> chain = Db.query("SELECT revision,previous_hash,revision_hash FROM m08_summary_revisions WHERE project_id=? ORDER BY revision LIMIT ?", source.project(), MAX_REVISIONS + 1);
            if (chain.size() != version) corrupt();
            String previous = GENESIS;
            for (int i = 0; i < chain.size(); i++) {
                Map<String, Object> item = chain.get(i);
                if (number(item.get("revision"), false) != i + 1 || !previous.equals(digest(item.get("previous_hash")))) corrupt();
                previous = digest(item.get("revision_hash"));
                stored(checked, i + 1);
            }
            if (!previous.equals(headHash)) corrupt();
            return checked;
        } catch (Api.ApiException invalid) { if(invalid.code==503)throw invalid;throw new Api.ApiException(409,"总结持久化版本链校验失败，请保留记录并核对"); } catch(RuntimeException invalid) {throw new Api.ApiException(409,"总结持久化版本链校验失败，请保留记录并核对");}
    }

    private static long historyChars(long project) throws Exception {
        Map<String, Object> size = Db.one("SELECT COALESCE(SUM(CHAR_LENGTH(content_json)+CHAR_LENGTH(sources_json)),0) AS amount FROM m08_summary_revisions WHERE project_id=?", project);
        return number(size.get("amount"), true);
    }

    private static Stored stored(Head head, long revision) throws Exception {
        Map<String, Object> row = Db.one("SELECT * FROM m08_summary_revisions WHERE project_id=? AND revision=?", head.project(), revision);
        if (row == null) { corrupt(); throw new AssertionError(); }
        try {
            String operation = requiredSourceText(row.get("operation"), 24);
            if (!(revision == 1 ? operation.equals("CREATE") : Set.of("SAVE", "REFRESH_SOURCES").contains(operation))) corrupt();
            String contentJson = requiredSourceText(row.get("content_json"), MAX_CONTENT_JSON), sourceJson = requiredSourceText(row.get("sources_json"), MAX_SOURCE_JSON);
            Map<String,Object> encoded=object(Json.parse(contentJson),Set.of("achievements","issues","nextSteps","publicity","photos"));
            Object photoInput=encoded.remove("photos");Content content=parseContent(encoded);
            List<TrainingSummariesPhotos.Ref> photos=TrainingSummariesPhotos.parseStored(head.project(),head.demand(),head.org(),photoInput);
            if (!canonical(contentMap(content,photos)).equals(contentJson)) corrupt();
            validateStoredSources(sourceJson, head);
            String at = requiredSourceText(row.get("saved_at"), 40); Instant.parse(at);
            Stored result = new Stored(head.project(), revision, operation, requiredSourceText(row.get("actor_code"), 120), number(row.get("account_id"), false), requiredSourceText(row.get("config_version"), 128),
                    at, content, photos, sourceJson, digest(row.get("previous_hash")), digest(row.get("revision_hash")));
            if (!recordHash(result).equals(result.hash())) corrupt();
            return result;
        } catch (Api.ApiException invalid) { if(invalid.code==503)throw invalid;throw new Api.ApiException(409,"总结正文或来源快照校验失败，请保留记录并核对"); } catch(RuntimeException invalid) {throw new Api.ApiException(409,"总结正文或来源快照校验失败，请保留记录并核对");}
    }

    private static void validateStoredSources(String json, Head head) throws Exception {
        Map<String, Object> sources = object(Json.parse(json), Set.of("format", "project", "codes", "feedback", "delivery", "source_version"));
        if (!FORMAT.equals(sources.get("format")) || !canonical(sources).equals(json)) corrupt();
        String sourceVersion = digest(sources.get("source_version"));
        Map<String, Object> hashed = new LinkedHashMap<>(sources); hashed.remove("source_version");
        if (!sourceVersion.equals(hash(canonical(hashed)))) corrupt();
        Map<String, Object> project = object(sources.get("project"), Set.of("status", "value", "provenance", "unavailable_fields"));
        Map<String, Object> facts = object(project.get("value"), Set.of("project_id", "organization_code", "project_code", "course_codes", "start_date", "end_date", "participant_count", "project_status"));
        Map<String, Object> provenance = object(project.get("provenance"), Set.of("demand_id", "accepted_revision", "workflow_version", "team_code", "accepted_by", "accepted_at"));
        if (!"AVAILABLE".equals(project.get("status")) || number(facts.get("project_id"), false) != head.project() || number(provenance.get("demand_id"), false) != head.demand() || !head.org().equals(facts.get("organization_code")) || facts.get("project_code") != null || facts.get("course_codes") != null) corrupt();
        if (!canonical(sources.get("codes")).equals(canonical(map("project", "UNASSIGNED", "courses", "UNASSIGNED")))) corrupt();
        TrainingSummariesFeedbackSource.validate(sources.get("feedback"));
        if(sources.get("feedback") instanceof Map<?,?> feedback && "AVAILABLE".equals(feedback.get("status"))) {
            if(!(feedback.get("value") instanceof Map<?,?> value) || number(value.get("project_id"),false)!=head.project() || !head.org().equals(value.get("organization_code")))corrupt();
        }
        TrainingSummariesDeliverySource.validate(sources.get("delivery"));
    }

    private static String sourceJson(Map<String, Object> sources) throws Api.ApiException {
        sources.remove("source_version"); sources.put("source_version", hash(canonical(sources)));
        String json = canonical(sources); if (json.length() > MAX_SOURCE_JSON) fail(409, "总结来源快照超过容量上限"); return json;
    }
    private static String refreshedSources(Auth.Session session, Context context, Stored old) throws Exception {
        Map<String, Object> sources = object(Json.parse(context.source().json()), Set.of("format", "project", "codes", "feedback", "delivery", "source_version"));
        Map<String, Object> saved = object(Json.parse(old.sourcesJson()), Set.of("format", "project", "codes", "feedback", "delivery", "source_version"));
        if (!TrainingSummariesDeliverySource.canRead(session, context.source().org())) sources.put("delivery", saved.get("delivery"));
        if (!TrainingSummariesFeedbackSource.canRead(session, context.source().org())) sources.put("feedback", saved.get("feedback"));
        return sourceJson(sources);
    }

    private static Stored makeStored(long project, long revision, String operation, Context context, Content content, List<TrainingSummariesPhotos.Ref> photos, String sources, String previous) throws Exception {
        Stored blank = new Stored(project, revision, operation, context.actor(), context.account(), context.config(), Instant.now().toString(), content, photos, sources, previous, "");
        return new Stored(project, revision, operation, blank.actor(), blank.account(), blank.config(), blank.savedAt(), content, photos, sources, previous, recordHash(blank));
    }

    private static String recordHash(Stored stored) throws Exception {
        return hash(canonical(map("format", FORMAT, "project_id", stored.project(), "revision", stored.revision(), "operation", stored.operation(), "actor_code", stored.actor(), "account_id", stored.account(),
                "config_version", stored.config(), "saved_at", stored.savedAt(), "content", contentMap(stored.content(),stored.photos()), "sources", Json.parse(stored.sourcesJson()), "previous_hash", stored.previousHash())));
    }

    private static Map<String, Object> response(Auth.Session session, Context context, Head head, Stored current) throws Exception {
        String sources = current == null ? context.source().json() : current.sourcesJson();
        boolean deliveryVisible = TrainingSummariesDeliverySource.canRead(session, context.source().org());
        boolean feedbackVisible=TrainingSummariesFeedbackSource.canRead(session, context.source().org());
        Map<String,Object> workflow=TrainingSummariesWorkflow.describe(session,workflowSnapshot(context,head));
        Map<String,Object> capabilities=object(workflow.get("capabilities"),Set.of("edit","refresh_sources","submit","review","review_role","export"));
        capabilities.put("read",true);
        Map<String, Object> projected = projectSources(sources, deliveryVisible, feedbackVisible);
        boolean changed = !canonical(projected).equals(canonical(projectSources(context.source().json(), deliveryVisible, feedbackVisible)));
        return map("project_id", context.source().project(), "organization_code", context.source().org(), "project_status", context.source().status(),
                "version", current == null ? 0L : current.revision(), "revision", current == null ? 0L : current.revision(), "latest_version", head == null ? 0L : head.version(),
                "current", current == null ? null : revisionView(current, deliveryVisible, feedbackVisible), "sources", projected, "source_changed", changed,
                "capabilities", capabilities, "workflow", workflow,
                "replayed", false, "reload_required", head != null && current != null && current.revision() != head.version(),
                "synthetic", false, "draft_only", false, "photo_policy", TrainingSummariesPhotos.POLICY, "format", FORMAT);
    }

    private static Map<String, Object> metadata(Stored stored) {
        return map("revision", stored.revision(), "operation", stored.operation(), "actor_code", stored.actor(), "saved_at", stored.savedAt(), "status", "DRAFT");
    }

    private static Map<String, Object> revisionView(Stored stored, boolean deliveryVisible, boolean feedbackVisible) {
        Map<String, Object> out = metadata(stored);
        out.put("project_id", stored.project()); out.put("content", contentMap(stored.content())); out.put("photos",TrainingSummariesPhotos.view(stored.photos())); out.put("sources", projectSources(stored.sourcesJson(), deliveryVisible, feedbackVisible)); out.put("synthetic", false); out.put("read_only", true);
        return out;
    }

    /** Reference guard only; host keeps its existing authorization, M05 archive gate and transaction. */
    public static void guardLegacyProjectMutation(String operation, long projectId, Map<String, Object> proposed) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            if (!Set.of("delete", "archive", "update").contains(operation)) fail(400, "未知项目引用保护操作");
            if(TrainingSummariesPhotos.hasProject(projectId)){
                Source current=source(projectId);TrainingSummariesPhotos.guardProject(operation,projectId,current.demand(),current.org(),operation.equals("update")&&proposed!=null&&proposed.containsKey("demand_id")?legacyId(proposed.get("demand_id")):null);
            }
            Map<String, Object> row = Db.one("SELECT * FROM m08_summary_heads WHERE project_id=?", projectId);
            if (row == null) return;
            if (operation.equals("delete")) fail(409, "项目已有培训总结及历史修订，不能删除");
            Source source = source(projectId); Head head = head(source); stored(head, head.version());
            TrainingSummariesWorkflow.verifyHistory(operation,projectId,head.version());
            if (operation.equals("update") && !editable(source.status())) fail(409, "项目已归档或只读，不能通过旧入口解封总结");
            if (operation.equals("update") && proposed != null && proposed.containsKey("demand_id") && legacyId(proposed.get("demand_id")) != head.demand()) fail(409, "项目已有培训总结，不能更换受理来源");
            // Archive itself is allowed by M08: immutable drafts remain, subsequent writes are rejected.
            // This does not approve archive or bypass Workflow/M05/legacy lifecycle gates.
        }
    }

    private static Content parseContent(Object value) throws Api.ApiException {
        try {
            Map<String, Object> fields = object(value, Set.of("achievements", "issues", "nextSteps", "publicity"));
            Publicity publicity = null;
            if (fields.get("publicity") != null) {
                Map<String, Object> p = object(fields.get("publicity"), Set.of("title", "introduction", "sections", "photoCaptions"));
                List<?> sections = list(p.getOrDefault("sections", List.of()), 12), photos = list(p.getOrDefault("photoCaptions", List.of()), 6);
                List<PublicitySection> paragraphs = new ArrayList<>(); List<String> captions = new ArrayList<>();
                for (Object section : sections) { Map<String, Object> row = object(section, Set.of("heading", "body")); paragraphs.add(new PublicitySection(text(row.get("heading")), text(row.get("body")))); }
                for (Object photo : photos) {
                    String caption = text(photo);
                    if (caption.length() > 300) fail(400, "照片图注超过长度限制");
                    String lower = caption.toLowerCase(Locale.ROOT).strip();
                    if (lower.contains("://") || lower.contains("//") || lower.contains("\\") || lower.startsWith("/") || lower.startsWith("./") || lower.startsWith("../") || lower.startsWith("~/")
                            || lower.matches("(?s).*\\b(?:file|data|blob|javascript|mailto|tel):.*") || lower.contains("www.")
                            || lower.matches("(?s).*\\.(?:jpg|jpeg|png|gif|webp|heic|tiff|bmp)(?:[?/#\\s].*)?")) fail(400, "照片位置只接受文字图注，不接受地址或文件");
                    captions.add(caption);
                }
                publicity = new Publicity(text(p.get("title")), text(p.get("introduction")), paragraphs, captions);
            }
            return new Content(text(fields.get("achievements")), text(fields.get("issues")), text(fields.get("nextSteps")), publicity);
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new Api.ApiException(400, "总结正文的字段、长度或字符无效"); }
    }

    private static Map<String, Object> contentMap(Content content) {
        Map<String, Object> out = map("achievements", content.achievements(), "issues", content.issues(), "nextSteps", content.nextSteps());
        if (content.publicity() != null) {
            Publicity p = content.publicity();
            out.put("publicity", map("title", p.title(), "introduction", p.introduction(), "sections", p.sections().stream().map(s -> map("heading", s.heading(), "body", s.body())).toList(), "photoCaptions", p.photoCaptions()));
        }
        return out;
    }

    private static Map<String,Object> contentMap(Content content,List<TrainingSummariesPhotos.Ref> photos){
        Map<String,Object> value=contentMap(content);if(!photos.isEmpty())value.put("photos",TrainingSummariesPhotos.storedView(photos));return value;
    }

    private static Map<String, Object> object(Object value, Set<String> permitted) throws Api.ApiException {
        if (!(value instanceof Map<?, ?>)) { fail(400, "请求对象格式无效"); throw new AssertionError(); }
        Map<?, ?> source = (Map<?, ?>) value; Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> item : source.entrySet()) {
            if (!(item.getKey() instanceof String key) || !permitted.contains(key)) fail(400, "请求包含不支持的字段");
            out.put((String) item.getKey(), item.getValue());
        }
        return out;
    }

    private static List<?> list(Object value, int limit) throws Api.ApiException { if (!(value instanceof List<?> v) || v.size() > limit) { fail(400, "总结分段或照片位置数量无效"); throw new AssertionError(); } return (List<?>) value; }
    private static String text(Object value) throws Api.ApiException { if (value == null) return ""; if (!(value instanceof String)) fail(400, "正文只接受文本"); return (String) value; }
    private static String requestId(Object value) throws Api.ApiException { if (!(value instanceof String s) || !s.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}")) { fail(400, "request_id 格式无效"); throw new AssertionError(); } return (String) value; }
    private static String requiredSourceText(Object value, int max) throws Api.ApiException { if (!(value instanceof String s) || s.isBlank() || s.length() > max) { fail(409, "已存来源字段无效"); throw new AssertionError(); } return (String) value; }
    private static String digest(Object value) throws Api.ApiException { String digest = requiredSourceText(value, 64); if (!digest.matches("[a-f0-9]{64}")) corrupt(); return digest; }
    private static long number(Object value, boolean zero) throws Api.ApiException {
        if (!(value instanceof Number)) { fail(400, "编号及版本须为安全整数"); throw new AssertionError(); }
        try { long n = new BigDecimal(value.toString()).longValueExact(); if (n < (zero ? 0 : 1) || n > MAX_SAFE) throw new ArithmeticException(); return n; }
        catch (RuntimeException invalid) { throw new Api.ApiException(400, "编号及版本须为安全整数"); }
    }
    private static long legacyId(Object value) throws Api.ApiException { return value instanceof String s ? queryInteger(s, false) : number(value, false); }
    private static long queryInteger(String value, boolean zero) throws Api.ApiException { if (value == null || !value.matches("[0-9]{1,16}")) { fail(400, "查询编号及版本格式无效"); throw new AssertionError(); } return number(new BigDecimal(value), zero); }
    private static long queryBound(String value, int min, int max) throws Api.ApiException { long number = queryInteger(value, min == 0); if (number < min || number > max) fail(400, "分页范围无效"); return number; }
    private static Map<String, String> query(HttpExchange exchange, Set<String> allowed) throws Api.ApiException {
        Map<String, String> result = Api.query(exchange);
        String raw = exchange.getRequestURI().getRawQuery();
        // Api.query folds repeated keys. Reject them rather than accepting ambiguous ownership/version parameters.
        int count = raw == null || raw.isEmpty() ? 0 : raw.split("&", -1).length;
        if (result.size() != count || !allowed.containsAll(result.keySet())) fail(400, "查询参数无效或重复");
        return result;
    }
    private static void method(HttpExchange exchange, String method) throws Api.ApiException { if (!method.equalsIgnoreCase(exchange.getRequestMethod())) fail(405, "请求方法不支持"); }
    private static void noNestedTransaction() throws Exception { if (!Db.get().getAutoCommit()) fail(409, "总结接口不能嵌入未完成事务"); }
    private static Map<String, Object> map(Object... values) { Map<String, Object> out = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) out.put((String) values[i], values[i + 1]); return out; }
    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> input) { Map<String, Object> sorted = new TreeMap<>(); input.forEach((k, v) -> sorted.put((String) k, normalized(v))); return Json.write(sorted); }
        return Json.write(normalized(value));
    }
    private static Object normalized(Object value) { if (value instanceof Map<?, ?> input) { Map<String, Object> sorted = new TreeMap<>(); input.forEach((k, v) -> sorted.put((String) k, normalized(v))); return sorted; } if (value instanceof List<?> list) return list.stream().map(TrainingSummariesIntegration::normalized).toList(); if (value instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros(); return value; }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception impossible) { throw new IllegalStateException("摘要算法不可用", impossible); } }
    private static void corrupt() throws Api.ApiException { fail(409, "总结版本或来源校验失败"); }
    private static void fail(int status, String message) throws Api.ApiException { throw new Api.ApiException(status, message); }
}
