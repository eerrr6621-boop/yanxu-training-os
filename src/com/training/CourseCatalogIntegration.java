package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** M04 persistence adapter. Api owns request limits, response flushing and the host route mount. */
public final class CourseCatalogIntegration {
    private static final String ROOT = "/api/course-catalog/scopes";
    private static final long SAFE = 9007199254740991L;
    private CourseCatalogIntegration() {}

    /** Empty schema only. Call after users/teachers exist, outside any transaction. */
    public static void init() throws SQLException {
        synchronized (Api.MUTATION_LOCK) {
            if (!Db.get().getAutoCommit()) throw new SQLException("目录初始化不能嵌入其他事务");
            Db.exec("CREATE TABLE IF NOT EXISTS m04_catalog_scopes(scope_id IDENTITY PRIMARY KEY," +
                    "organization_code VARCHAR(128) NOT NULL UNIQUE,version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0)," +
                    "actor_account BIGINT NOT NULL,actor_person VARCHAR(128) NOT NULL,identity_version VARCHAR(128) NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            Db.exec("CREATE TABLE IF NOT EXISTS m04_catalog_batches(batch_id VARCHAR(36) PRIMARY KEY," +
                    "scope_id BIGINT NOT NULL REFERENCES m04_catalog_scopes(scope_id),expected_version BIGINT NOT NULL," +
                    "payload CLOB NOT NULL,bindings CLOB NOT NULL,teacher_facts CLOB NOT NULL," +
                    "actor_account BIGINT NOT NULL,actor_person VARCHAR(128) NOT NULL,identity_version VARCHAR(128) NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,change_comment VARCHAR(500) NOT NULL,confirmed_version BIGINT)");
            Db.exec("CREATE TABLE IF NOT EXISTS m04_catalog_revisions(scope_id BIGINT NOT NULL REFERENCES m04_catalog_scopes(scope_id)," +
                    "version BIGINT NOT NULL,catalog_version VARCHAR(120) NOT NULL,payload CLOB NOT NULL,bindings CLOB NOT NULL," +
                    "batch_id VARCHAR(36) NOT NULL UNIQUE REFERENCES m04_catalog_batches(batch_id)," +
                    "actor_account BIGINT NOT NULL,actor_person VARCHAR(128) NOT NULL,identity_version VARCHAR(128) NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,change_comment VARCHAR(500) NOT NULL,changes CLOB NOT NULL," +
                    "PRIMARY KEY(scope_id,version),UNIQUE(scope_id,catalog_version))");
            Db.exec("CREATE TABLE IF NOT EXISTS m04_teacher_bindings(scope_id BIGINT NOT NULL REFERENCES m04_catalog_scopes(scope_id)," +
                    "teacher_code VARCHAR(64) NOT NULL,teacher_id BIGINT NOT NULL REFERENCES teachers(id),created_version BIGINT NOT NULL," +
                    "PRIMARY KEY(scope_id,teacher_code),UNIQUE(scope_id,teacher_id))");
            Db.exec("CREATE TABLE IF NOT EXISTS m04_catalog_events(scope_id BIGINT NOT NULL,version BIGINT NOT NULL," +
                    "batch_id VARCHAR(36) NOT NULL UNIQUE,actor_account BIGINT NOT NULL,actor_person VARCHAR(128) NOT NULL," +
                    "identity_version VARCHAR(128) NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "change_comment VARCHAR(500) NOT NULL,changes CLOB NOT NULL,PRIMARY KEY(scope_id,version)," +
                    "FOREIGN KEY(scope_id,version) REFERENCES m04_catalog_revisions(scope_id,version))");
        }
    }

    /** Trusted host provisioning only; no HTTP ownership endpoint or implicit/global ownership. */
    static Map<String,Object> registerScope(Auth.Session s, String organizationCode) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            return transaction(() -> {
                Person person = OrganizationAccessStore.person(s);
                Configuration config = OrganizationAccessStore.configuration();
                Organization org = config.organizations().stream().filter(o -> o.organizationCode().equals(organizationCode))
                        .findFirst().orElseThrow(() -> error(400,"目录所属机构不存在"));
                permission(s, org.organizationCode(), true);
                Map<String,Object> old = Db.one("SELECT scope_id,organization_code,version FROM m04_catalog_scopes WHERE organization_code=?",org.organizationCode());
                if (old != null) return old;
                long id = Db.insert("INSERT INTO m04_catalog_scopes(organization_code,actor_account,actor_person,identity_version) VALUES(?,?,?,?)",
                        org.organizationCode(),s.uid,person.personCode(),config.version());
                return map("scope_id",id,"organization_code",org.organizationCode(),"version",0L);
            });
        }
    }

    public static boolean handle(HttpExchange ex, Auth.Session supplied) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (!path.equals(ROOT) && !path.startsWith(ROOT+"/")) return false;
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session s = Auth.get(Api.token(ex));
            if (s == null || s != supplied || Auth.current(s) == null) throw error(401,"登录会话无效或已失效");
            OrganizationAccessStore.person(s);
            String suffix = path.substring(ROOT.length());
            if (suffix.isEmpty()) {
                method(ex,"GET","HEAD"); query(ex,false);
                List<Map<String,Object>> scopes = new ArrayList<>();
                for (Map<String,Object> row : Db.query("SELECT scope_id,organization_code,version FROM m04_catalog_scopes ORDER BY scope_id")) {
                    boolean read=OrganizationAccessStore.authorize(s,"catalog.read",Action.VIEW,(String)row.get("organization_code")).allowed();
                    boolean manage=OrganizationAccessStore.authorize(s,"catalog.manage",Action.HANDLE,(String)row.get("organization_code")).allowed();
                    if(read||manage) {row.put("can_read",read);row.put("can_manage",manage);scopes.add(row);}
                }
                Api.ok(ex,map("status",scopes.isEmpty()?"NOT_CONFIGURED":"READY","scopes",scopes));
                return true;
            }
            String[] parts = suffix.substring(1).split("/",-1);
            long id = pathInteger(parts[0],"scope_id",false);
            String action = parts.length==1 ? "read" : parts[1];
            if (!(parts.length==1 || parts.length==2 && Set.of("preview","confirm","qualify","history","course-management","courses-preview","certification-management","certifications-preview").contains(action)
                    || parts.length==3 && action.equals("versions"))) throw error(404,"目录接口不存在");
            boolean write = Set.of("preview","confirm","qualify","courses-preview","certifications-preview").contains(action);
            if(write) method(ex,"POST"); else method(ex,"GET","HEAD");
            Map<String,String> q = query(ex,action.equals("history"));
            if(write) {
                String ct=ex.getRequestHeaders().getFirst("Content-Type");
                if(ct==null || !"application/json".equalsIgnoreCase(ct.split(";",2)[0].trim())) throw error(415,"接口只接受 application/json");
            }
            boolean manage = !action.equals("read") && !action.equals("qualify");
            Map<String,Object> scope = scope(s,id,manage);
            Object response;
            switch(action) {
                case "read": response=currentSnapshot(scope); break;
                case "preview": response=preview(s,id,Api.body(ex)); break;
                case "course-management": response=courseManagement(scope); break;
                case "courses-preview": response=coursesPreview(s,id,Api.body(ex)); break;
                case "certification-management": response=certificationManagement(scope); break;
                case "certifications-preview": response=certificationsPreview(s,id,Api.body(ex)); break;
                case "confirm": response=confirm(s,id,Api.body(ex)); break;
                case "qualify": {
                    Map<String,Object> b=object(Api.body(ex),"expected_version","request");
                    response=qualification(s,id,integer(b.get("expected_version"),"expected_version",true),anyObject(b.get("request"),"request"));
                    break;
                }
                case "history": response=history(scope,q); break;
                case "versions": response=revisionSnapshot(scope,pathInteger(parts[2],"version",false)); break;
                default: throw error(404,"目录接口不存在");
            }
            Api.ok(ex,response);
            return true;
        }
    }

    /** The new maintenance read never returns teacher identities or silently repairs a stored snapshot. */
    private static Map<String,Object> courseManagement(Map<String,Object> scope) throws Exception {
        Map<String,Object> snapshot=maintenanceSnapshot(scope);
        Map<String,Object> catalog=number(scope,"version")==0?emptyCatalog():anyObject(snapshot.get("catalog"),"catalog");
        return map("scope_id",scope.get("scope_id"),"organization_code",scope.get("organization_code"),"version",scope.get("version"),
                "catalog_version",number(scope,"version")==0?null:catalog.get("catalog_version"),"courses",catalog.get("courses"),
                "retained_counts",map("teachers",rows(catalog.get("teachers")).size(),"certifications",rows(catalog.get("certifications")).size(),
                        "bindings",rows(snapshot.get("bindings")).size()),"can_manage",true);
    }

    /** Only current snapshot teachers are eligible for certification maintenance, not historical bindings. */
    private static Map<String,Object> certificationManagement(Map<String,Object> scope) throws Exception {
        Map<String,Object> snapshot=maintenanceSnapshot(scope);
        Map<String,Object> catalog=number(scope,"version")==0?emptyCatalog():anyObject(snapshot.get("catalog"),"catalog");
        List<String> codes=new ArrayList<>();
        for(Map<String,Object> teacher:rows(catalog.get("teachers")))codes.add((String)teacher.get("teacher_code"));
        return map("scope_id",scope.get("scope_id"),"organization_code",scope.get("organization_code"),"version",scope.get("version"),
                "catalog_version",number(scope,"version")==0?null:catalog.get("catalog_version"),"courses",catalog.get("courses"),
                "teacher_codes",codes,"certifications",catalog.get("certifications"),
                "retained_counts",map("courses",rows(catalog.get("courses")).size(),"teachers",codes.size(),
                        "bindings",rows(snapshot.get("bindings")).size()),"can_manage",true);
    }

    private static Map<String,Object> maintenanceSnapshot(Map<String,Object> scope) throws Exception {
        try {
            // Only an actual version-zero scope has no catalog. A published empty course list may retain teachers.
            if(number(scope,"version")==0 && (Db.one("SELECT version FROM m04_catalog_revisions WHERE scope_id=?",scope.get("scope_id"))!=null
                    || Db.one("SELECT teacher_id FROM m04_teacher_bindings WHERE scope_id=?",scope.get("scope_id"))!=null))
                throw new IllegalStateException("Unexpected stored state for empty scope");
            return currentSnapshot(scope);
        } catch(Exception invalid) {throw error(409,"当前课程目录或已保存绑定无法核实，请先核对原目录；未清空或修复资料");}
    }

    /** Client edits courses only. Teacher metadata, certifications and bindings come from the same locked version. */
    private static Map<String,Object> coursesPreview(Auth.Session s,long id,Map<String,Object> input) throws Exception {
        Map<String,Object> b=object(input,"expected_version","catalog_version","courses","change_comment");
        long expected=integer(b.get("expected_version"),"expected_version",true);
        Map<String,Object> head=scope(s,id,true);version(head,expected);
        Map<String,Object> snapshot=maintenanceSnapshot(head);
        Map<String,Object> old=expected==0?emptyCatalog():anyObject(snapshot.get("catalog"),"catalog");
        // Detect removal even where retaining certifications would make the proposed catalog invalid.
        if(b.get("courses") instanceof List<?> proposed) {
            Set<String> codes=new HashSet<>();
            for(Object raw:proposed)if(raw instanceof Map<?,?> row && row.get("course_code") instanceof String code)codes.add(code);
            for(Map<String,Object> course:rows(old.get("courses")))if(!codes.contains(course.get("course_code")))
                throw error(409,"已有课程编码必须保留，不能删除或换码；不再使用的课程请停用");
        }
        Map<String,Object> whole=map("schema_version",CourseCatalog.SCHEMA_VERSION,"catalog_version",b.get("catalog_version"),
                "courses",b.get("courses"),"teachers",old.get("teachers"),"certifications",old.get("certifications"));
        Map<String,Object> result;
        try {
            result=preview(s,id,map("expected_version",expected,"catalog",whole,"bindings",snapshot.get("bindings"),
                    "change_comment",b.get("change_comment")),true);
        } catch(Api.ApiException e) {
            if(e.code==409 && e.getMessage().equals("导入等级或城市与现有师资档案不一致，请重新核对"))
                throw error(409,"已保存目录的等级或城市与当前师资档案不一致，请先核对师资依据；本次课程维护不会自动刷新师资资料");
            throw e;
        }
        result.putIfAbsent("changes",null);
        result.put("scope_id",id);result.put("organization_code",head.get("organization_code"));return result;
    }

    private static void onlyCourseChanges(Map<String,Object> changes) throws Exception {
        for(String table:List.of("teachers","certifications","bindings")) {
            Map<String,Object> part=anyObject(changes.get(table),"changes");
            for(String key:List.of("added","updated","removed"))if(!(part.get(key) instanceof List<?> items)||!items.isEmpty())
                throw error(409,"纯课程预览意外包含师资、认证或绑定变化，未保存预览");
        }
        Map<String,Object> courses=anyObject(changes.get("courses"),"changes");
        if(!(courses.get("removed") instanceof List<?> removed)||!removed.isEmpty())throw error(409,"已有课程编码必须保留，请通过停用处理");
        if(!(courses.get("added") instanceof List<?> added)||!(courses.get("updated") instanceof List<?> updated))throw error(409,"课程改动无法核实，未保存预览");
        if(added.isEmpty()&&updated.isEmpty())throw error(409,"尚无课程新增、名称或启停变化，请修改后再保存预览");
    }

    /** Courses, teacher metadata and fixed bindings are copied from the same locked published snapshot. */
    private static Map<String,Object> certificationsPreview(Auth.Session s,long id,Map<String,Object> input) throws Exception {
        Map<String,Object> b=object(input,"expected_version","catalog_version","certifications","change_comment");
        long expected=integer(b.get("expected_version"),"expected_version",true);
        Map<String,Object> head=scope(s,id,true);version(head,expected);
        Map<String,Object> snapshot=maintenanceSnapshot(head);
        Map<String,Object> old=expected==0?emptyCatalog():anyObject(snapshot.get("catalog"),"catalog");
        // Preserve every published pair even when a changed key would also fail reference validation.
        if(b.get("certifications") instanceof List<?> proposed) {
            Set<List<String>> pairs=new HashSet<>();
            for(Object raw:proposed)if(raw instanceof Map<?,?> row && row.get("teacher_code") instanceof String teacher
                    && row.get("course_code") instanceof String course)pairs.add(List.of(teacher,course));
            for(Map<String,Object> certification:rows(old.get("certifications")))
                if(!pairs.contains(List.of((String)certification.get("teacher_code"),(String)certification.get("course_code"))))
                    throw error(409,"已有讲师与课程认证记录必须保留，不能删除或换码；撤销认证请使用 revoked 状态");
        }
        Map<String,Object> whole=map("schema_version",CourseCatalog.SCHEMA_VERSION,"catalog_version",b.get("catalog_version"),
                "courses",old.get("courses"),"teachers",old.get("teachers"),"certifications",b.get("certifications"));
        Map<String,Object> result;
        try {
            result=preview(s,id,map("expected_version",expected,"catalog",whole,"bindings",snapshot.get("bindings"),
                    "change_comment",b.get("change_comment")),"certifications");
        } catch(Api.ApiException e) {
            if(e.code==409 && e.getMessage().equals("导入等级或城市与现有师资档案不一致，请重新核对"))
                throw error(409,"已保存目录的等级或城市与当前师资档案不一致，请先核对师资依据；本次认证维护不会自动刷新师资资料");
            throw e;
        }
        result.putIfAbsent("changes",null);
        result.put("scope_id",id);result.put("organization_code",head.get("organization_code"));return result;
    }

    private static void onlyCertificationChanges(Map<String,Object> changes) throws Exception {
        for(String table:List.of("courses","teachers","bindings")) {
            if(!(changes.get(table) instanceof Map<?,?> part))throw error(409,"认证差异无法核实，未保存预览");
            for(String key:List.of("added","updated","removed"))if(!(part.get(key) instanceof List<?> items)||!items.isEmpty())
                throw error(409,"认证预览意外包含课程、师资或绑定变化，未保存预览");
        }
        if(!(changes.get("certifications") instanceof Map<?,?> certifications))throw error(409,"认证差异无法核实，未保存预览");
        if(!(certifications.get("removed") instanceof List<?> removed)||!removed.isEmpty())
            throw error(409,"已有讲师与课程认证记录必须保留，撤销认证请使用 revoked 状态");
        if(!(certifications.get("added") instanceof List<?> added)||!(certifications.get("updated") instanceof List<?> updated))
            throw error(409,"认证差异无法核实，未保存预览");
        if(added.isEmpty()&&updated.isEmpty())throw error(409,"尚无认证新增或字段变化，请修改后再保存预览");
    }

    private static Map<String,Object> preview(Auth.Session s,long id,Map<String,Object> input) throws Exception {
        return preview(s,id,input,false);
    }
    private static Map<String,Object> preview(Auth.Session s,long id,Map<String,Object> input,boolean coursesOnly) throws Exception {
        return preview(s,id,input,coursesOnly?"courses":null);
    }
    private static Map<String,Object> preview(Auth.Session s,long id,Map<String,Object> input,String maintenance) throws Exception {
        Map<String,Object> b=object(input,"expected_version","catalog","bindings","change_comment");
        long expected=integer(b.get("expected_version"),"expected_version",true);
        String comment=string(b.get("change_comment"),"change_comment",500);
        Map<String,Object> catalog=anyObject(b.get("catalog"),"catalog");
        Map<String,Object> result=CourseCatalog.preview(catalog);
        Map<String,Object> head=scope(s,id,true);
        version(head,expected);
        result.put("current_version",expected);
        result.put("batch_id",null);
        result.put("confirmation_required",false);
        // Validate binding shape even when the catalog itself is invalid; no invalid batch is stored.
        List<Map<String,Object>> bindings=parseBindings(b.get("bindings"));
        if (!Boolean.TRUE.equals(result.get("ready"))) return result;
        catalog=anyObject(result.get("data"),"catalog");
        final Map<String,Object> accepted=catalog;
        return transaction(() -> {
            Map<String,Object> current=scope(s,id,true); version(current,expected);
            Person actor=OrganizationAccessStore.person(s);
            Configuration config=OrganizationAccessStore.configuration();
            unusedCatalogVersion(id,(String)accepted.get("catalog_version"));
            List<Map<String,Object>> facts=validateBindings(id,accepted,bindings);
            String batch=UUID.randomUUID().toString();
            Map<String,Object> changes=changes(current,accepted,bindings);
            if("courses".equals(maintenance))onlyCourseChanges(changes);
            else if("certifications".equals(maintenance))onlyCertificationChanges(changes);
            Db.exec("INSERT INTO m04_catalog_batches(batch_id,scope_id,expected_version,payload,bindings,teacher_facts,actor_account,actor_person,identity_version,change_comment) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    batch,id,expected,Json.write(accepted),Json.write(bindings),Json.write(facts),s.uid,actor.personCode(),config.version(),comment);
            result.put("batch_id",batch);result.put("changes",changes);result.put("confirmation_required",true);
            return result;
        });
    }

    private static Map<String,Object> confirm(Auth.Session s,long id,Map<String,Object> input) throws Exception {
        Map<String,Object> b=object(input,"batch_id","expected_version","confirm");
        long expected=integer(b.get("expected_version"),"expected_version",true);
        String batchId=string(b.get("batch_id"),"batch_id",36);
        if (!batchId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw error(400,"batch_id 格式无效");
        if (!Boolean.TRUE.equals(b.get("confirm"))) throw error(400,"必须显式确认预览批次");
        return transaction(() -> {
            Map<String,Object> head=scope(s,id,true);
            Person actor=OrganizationAccessStore.person(s);
            Configuration config=OrganizationAccessStore.configuration();
            Map<String,Object> batch=Db.one("SELECT * FROM m04_catalog_batches WHERE batch_id=? AND scope_id=?",batchId,id);
            if(batch==null) throw error(404,"预览批次不存在");
            if(number(batch,"actor_account")!=s.uid || !actor.personCode().equals(batch.get("actor_person"))) throw error(403,"仅原预览人员可确认此批次");
            if(number(batch,"expected_version")!=expected) throw error(409,"确认版本与原预览不一致");
            if(batch.get("confirmed_version")!=null) return map("status","ALREADY_CONFIRMED","scope_id",id,"version",number(batch,"confirmed_version"),"current_version",number(head,"version"),"batch_id",batchId);
            version(head,expected);
            if(!config.version().equals(batch.get("identity_version"))) throw error(409,"组织权限配置已变化，请重新预览");
            Map<String,Object> catalog=storedCatalog(batch);
            List<Map<String,Object>> bindings=parseBindings(Json.parse((String)batch.get("bindings")));
            List<Map<String,Object>> facts=validateBindings(id,catalog,bindings);
            if(!Json.write(facts).equals(batch.get("teacher_facts"))) throw error(409,"师资档案已变化，请重新预览");
            unusedCatalogVersion(id,(String)catalog.get("catalog_version"));
            long next=expected+1;
            if(next>SAFE) throw error(409,"目录版本已达安全整数上限");
            Map<String,Object> diff=changes(head,catalog,bindings);
            try(PreparedStatement ps=Db.get().prepareStatement("UPDATE m04_catalog_scopes SET version=? WHERE scope_id=? AND version=?")) {
                ps.setLong(1,next);ps.setLong(2,id);ps.setLong(3,expected);
                if(ps.executeUpdate()!=1) throw error(409,"目录版本已变化，请重新预览");
            }
            Db.exec("INSERT INTO m04_catalog_revisions(scope_id,version,catalog_version,payload,bindings,batch_id,actor_account,actor_person,identity_version,change_comment,changes) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    id,next,catalog.get("catalog_version"),batch.get("payload"),batch.get("bindings"),batchId,s.uid,actor.personCode(),config.version(),batch.get("change_comment"),Json.write(diff));
            for(Map<String,Object> binding:bindings) if(Db.one("SELECT teacher_id FROM m04_teacher_bindings WHERE scope_id=? AND teacher_code=?",id,binding.get("teacher_code"))==null)
                Db.exec("INSERT INTO m04_teacher_bindings(scope_id,teacher_code,teacher_id,created_version) VALUES(?,?,?,?)",id,binding.get("teacher_code"),binding.get("teacher_id"),next);
            Db.exec("INSERT INTO m04_catalog_events(scope_id,version,batch_id,actor_account,actor_person,identity_version,change_comment,changes) VALUES(?,?,?,?,?,?,?,?)",
                    id,next,batchId,s.uid,actor.personCode(),config.version(),batch.get("change_comment"),Json.write(diff));
            Db.exec("UPDATE m04_catalog_batches SET confirmed_version=? WHERE batch_id=?",next,batchId);
            return map("status","CONFIRMED","scope_id",id,"version",next,"current_version",next,"batch_id",batchId,"changes",diff);
        });
    }

    /**
     * Trusted package entry for recommendation. Never accepts a caller-supplied candidate pool.
     * Each call reauthenticates and reads the current scoped snapshot plus current teacher facts.
     * Recompute after asynchronous scoring and compare context_id before reusing its candidates;
     * the digest is a change identifier, never a permission token or a substitute for this call.
     */
    static Map<String,Object> qualification(Auth.Session supplied,long scopeId,long expectedVersion,Map<String,Object> input) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session actorSession=Auth.current(supplied);
            if(actorSession==null)throw error(401,"登录会话无效或已失效");
            Person actor=OrganizationAccessStore.person(actorSession);
            long id=integer(scopeId,"scope_id",false),expected=integer(expectedVersion,"expected_version",true);
            Map<String,Object> savedScope=scope(actorSession,id,false);version(savedScope,expected);
            Map<String,Object> raw=object(input,"course_code","as_of","accepted_levels","allowed_cities");
            Map<String,Object> validation=CourseCatalog.qualify(emptyCatalog(),raw);
            for(Map<String,Object> issue:rows(validation.get("issues")))
                if("error".equals(issue.get("severity"))&&!"UNKNOWN_REFERENCE".equals(issue.get("code")))
                    throw error(400,"资格查询参数无效："+issue.get("message"));
            Map<String,Object> request=map("course_code",raw.get("course_code"),"as_of",raw.get("as_of"),
                    "accepted_levels",sortedStrings(raw.get("accepted_levels")),"allowed_cities",sortedStrings(raw.get("allowed_cities")));
            QualificationSnapshot snapshot=qualifySnapshot(savedScope,expected,request);
            Map<String,Object> result=snapshot.result();
            Map<String,Object> context=map("schema_version","m04_qualification_context_v1",
                    "scope_id",id,"organization_code",savedScope.get("organization_code"),"version",expected,
                    "catalog_version",result.get("catalog_version"),"identity_version",OrganizationAccessStore.configuration().version(),
                    "account_id",actorSession.uid,"person_code",actor.personCode(),
                    "course_code",request.get("course_code"),"as_of",request.get("as_of"),
                    "accepted_levels",request.get("accepted_levels"),"allowed_cities",request.get("allowed_cities"),
                    "criteria_id",fingerprint(request),"catalog_snapshot_id",snapshot.catalogSnapshotId(),
                    "teacher_facts_id",fingerprint(snapshot.teacherFacts()));
            context.put("context_id",fingerprint(context));result.put("qualification_context",context);
            return result;
        }
    }

    /** Code association only: the host separately authorizes the project and records the user's selection. */
    static Map<String,Object> codingOptions(Auth.Session supplied,long scopeId,long teacherId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Auth.Session s=Auth.current(supplied);if(s==null)throw error(401,"登录会话无效或已失效");
            OrganizationAccessStore.person(s);
            long id=integer(scopeId,"scope_id",false),tid=integer(teacherId,"teacher_id",false);
            Map<String,Object> head=scope(s,id,false);long v=number(head,"version");
            try {
                Map<String,Object> snapshot=maintenanceSnapshot(head);
                Map<String,Object> catalog=v==0?null:anyObject(snapshot.get("catalog"),"catalog");
                if(v>0)codingSnapshot(id,v);
                Map<String,Object> binding=codingBinding(id,tid,v);
                boolean present=catalog!=null&&binding!=null&&codingRow(rows(catalog.get("teachers")),"teacher_code",binding.get("teacher_code"))!=null;
                List<Map<String,Object>> courses=catalog==null?List.of():rows(catalog.get("courses"));
                String status=v==0||courses.isEmpty()?"NOT_CONFIGURED":binding==null?"TEACHER_UNBOUND":!present?"TEACHER_NOT_IN_CATALOG":"READY";
                return map("schema_version","m04_coding_options_v1","catalog_scope_id",id,
                        "catalog_organization_code",head.get("organization_code"),"catalog_revision",v,
                        "catalog_version",catalog==null?null:catalog.get("catalog_version"),
                        "catalog_digest",fingerprint(map("catalog",catalog,"bindings",snapshot.get("bindings"))),
                        "status",status,"can_capture",status.equals("READY"),"teacher_id",tid,
                        "teacher_code",binding==null?null:binding.get("teacher_code"),"teacher_binding",binding,"courses",courses);
            } catch(Exception invalid) {throw error(409,"课程目录或固定讲师编码绑定无法核实，请先核对原目录");}
        }
    }

    /** Reads only the currently authorized published revision; no qualification or code inference. */
    static Map<String,Object> captureCoding(Auth.Session supplied,long scopeId,long expectedVersion,long teacherId,String courseCode) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Auth.Session s=Auth.current(supplied);if(s==null)throw error(401,"登录会话无效或已失效");
            OrganizationAccessStore.person(s);
            long id=integer(scopeId,"scope_id",false),v=integer(expectedVersion,"expected_version",false),tid=integer(teacherId,"teacher_id",false);
            String code=string(courseCode,"course_code",64);
            if(!code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw error(400,"课程编码格式无效");
            Map<String,Object> head=scope(s,id,false);version(head,v);
            try {return codingCapture(head,v,tid,code);}
            catch(Exception invalid) {throw error(409,"所选课程目录、课程编码或固定讲师编码绑定无法核实，请重新读取选项");}
        }
    }

    /** Host-authorized historical reads only. The saved revision remains valid after current catalog/grant changes. */
    static void validateCapturedCoding(Map<String,Object> capture) throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK))throw new IllegalStateException("历史编码校验必须持有业务锁");
        try {
            Map<String,Object> c=object(capture,"schema_version","catalog_scope_id","catalog_organization_code","catalog_revision",
                    "catalog_version","catalog_digest","teacher_id","teacher_code","course_code","course_id",
                    "teacher_binding","catalog_teacher","course","revision_audit");
            long id=integer(c.get("catalog_scope_id"),"catalog_scope_id",false),v=integer(c.get("catalog_revision"),"catalog_revision",false);
            long tid=integer(c.get("teacher_id"),"teacher_id",false);
            Map<String,Object> head=Db.one("SELECT scope_id,organization_code,version FROM m04_catalog_scopes WHERE scope_id=?",id);
            if(head==null||number(head,"version")<v)throw new IllegalStateException("Missing historical catalog scope");
            Map<String,Object> expected=codingCapture(head,v,tid,string(c.get("course_code"),"course_code",64));
            if(!Json.write(canonical(codingNumbers(c))).equals(Json.write(canonical(codingNumbers(expected)))))
                throw new IllegalStateException("Captured catalog association changed");
        } catch(Exception invalid) {throw error(409,"历史课程或讲师编码依据无法核实，未改写原账目");}
    }

    private record CodingSnapshot(Map<String,Object> revision,Map<String,Object> catalog,List<Map<String,Object>> bindings) {}

    private static CodingSnapshot codingSnapshot(long id,long v) throws Exception {
        Map<String,Object> r=revision(id,v),catalog=storedCatalog(r);
        List<Map<String,Object>> bindings=parseBindings(Json.parse((String)r.get("bindings")));
        storedBindingIds(id,catalog,bindings);
        integer(r.get("actor_account"),"actor_account",false);
        for(String field:List.of("actor_person","identity_version","created_at","change_comment"))string(r.get(field),field,500);
        String batchId=string(r.get("batch_id"),"batch_id",36);
        if(!batchId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalStateException("Invalid catalog batch");
        Map<String,Object> batch=Db.one("SELECT * FROM m04_catalog_batches WHERE scope_id=? AND batch_id=?",id,batchId);
        Map<String,Object> event=Db.one("SELECT * FROM m04_catalog_events WHERE scope_id=? AND version=?",id,v);
        if(batch==null||event==null||integer(batch.get("expected_version"),"expected_version",true)!=v-1
                ||integer(batch.get("confirmed_version"),"confirmed_version",false)!=v||!Objects.equals(event.get("batch_id"),batchId))
            throw new IllegalStateException("Incomplete catalog publication audit");
        for(String field:List.of("actor_account","actor_person","identity_version","change_comment"))
            if(!Objects.equals(r.get(field),batch.get(field))||!Objects.equals(r.get(field),event.get(field)))
                throw new IllegalStateException("Catalog publication audit changed");
        if(!Objects.equals(r.get("changes"),event.get("changes"))
                ||!fingerprint(map("catalog",catalog,"bindings",bindings)).equals(fingerprint(map("catalog",storedCatalog(batch),"bindings",parseBindings(Json.parse((String)batch.get("bindings")))))))
            throw new IllegalStateException("Catalog publication content changed");
        return new CodingSnapshot(r,catalog,bindings);
    }

    private static Map<String,Object> codingBinding(long id,long tid,long v) throws Exception {
        Map<String,Object> row=Db.one("SELECT teacher_code,teacher_id,created_version FROM m04_teacher_bindings WHERE scope_id=? AND teacher_id=?",id,tid);
        if(row==null)return null;
        long created=integer(row.get("created_version"),"created_version",false);
        if(created>v)throw new IllegalStateException("Teacher binding was created after selected revision");
        String code=string(row.get("teacher_code"),"teacher_code",64);
        if(!code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw new IllegalStateException("Invalid fixed teacher code");
        CodingSnapshot origin=codingSnapshot(id,created);
        Map<String,Object> original=codingRow(origin.bindings(),"teacher_code",code);
        if(original==null||number(original,"teacher_id")!=tid)throw new IllegalStateException("Missing original explicit teacher binding");
        return map("teacher_code",code,"teacher_id",tid,"created_version",created);
    }

    private static Map<String,Object> codingCapture(Map<String,Object> head,long v,long tid,String code) throws Exception {
        long id=number(head,"scope_id");CodingSnapshot snapshot=codingSnapshot(id,v);
        Map<String,Object> binding=codingBinding(id,tid,v);
        if(binding==null)throw new IllegalStateException("Teacher has no fixed catalog binding");
        Map<String,Object> declared=codingRow(snapshot.bindings(),"teacher_code",binding.get("teacher_code"));
        Map<String,Object> teacher=codingRow(rows(snapshot.catalog().get("teachers")),"teacher_code",binding.get("teacher_code"));
        Map<String,Object> course=codingRow(rows(snapshot.catalog().get("courses")),"course_code",code);
        if(declared==null||number(declared,"teacher_id")!=tid||teacher==null||course==null)throw new IllegalStateException("Code absent from selected catalog revision");
        Map<String,Object> r=snapshot.revision();
        return map("schema_version","m04_coding_capture_v1","catalog_scope_id",id,"catalog_organization_code",head.get("organization_code"),
                "catalog_revision",v,"catalog_version",snapshot.catalog().get("catalog_version"),
                "catalog_digest",fingerprint(map("catalog",snapshot.catalog(),"bindings",snapshot.bindings())),
                "teacher_id",tid,"teacher_code",binding.get("teacher_code"),"course_code",code,"course_id",null,
                "teacher_binding",binding,"catalog_teacher",teacher,"course",course,
                "revision_audit",map("batch_id",r.get("batch_id"),"actor_account",r.get("actor_account"),"actor_person",r.get("actor_person"),
                        "identity_version",r.get("identity_version"),"created_at",r.get("created_at"),"change_comment",r.get("change_comment")));
    }

    private static Map<String,Object> codingRow(List<Map<String,Object>> rows,String key,Object value) {
        for(Map<String,Object> row:rows)if(Objects.equals(row.get(key),value))return row;return null;
    }

    // JSON storage may parse whole numbers as doubles. Compare their exact safe-integer meaning.
    private static Object codingNumbers(Object value) throws Exception {
        if(value instanceof Number)return integer(value,"catalog integer",false);
        if(value instanceof Map<?,?> source) {
            Map<String,Object> result=new LinkedHashMap<>();
            for(Map.Entry<?,?> entry:source.entrySet())result.put((String)entry.getKey(),codingNumbers(entry.getValue()));return result;
        }
        if(value instanceof List<?> source) {List<Object> result=new ArrayList<>();for(Object item:source)result.add(codingNumbers(item));return result;}
        return value;
    }

    private record QualificationSnapshot(Map<String,Object> result,List<Map<String,Object>> teacherFacts,String catalogSnapshotId) {}

    private static QualificationSnapshot qualifySnapshot(Map<String,Object> scope,long expected,Map<String,Object> request) throws Exception {
        if(expected==0) return new QualificationSnapshot(
                map("status","NOT_CONFIGURED","scope_id",scope.get("scope_id"),"version",0L,"catalog_version",null,
                    "course_code",request.get("course_code"),"as_of",request.get("as_of"),"ready",false,
                    "eligible",List.of(),"gaps",List.of(),"ranking_status","not_configured","metadata_source","current_teacher_record"),
                List.of(),fingerprint(map("catalog",null,"bindings",List.of())));
        Map<String,Object> revision=revision(number(scope,"scope_id"),expected);
        Map<String,Object> catalog=storedCatalog(revision);
        List<Map<String,Object>> bindings=parseBindings(Json.parse((String)revision.get("bindings")));
        Map<String,Long> ids=storedBindingIds(number(scope,"scope_id"),catalog,bindings);
        String snapshotId=fingerprint(map("catalog",catalog,"bindings",bindings));
        Map<String,Map<String,Object>> facts=new HashMap<>();
        List<Map<String,Object>> factRows=new ArrayList<>();
        for(Map<String,Object> teacher:rows(catalog.get("teachers"))) {
            String code=(String)teacher.get("teacher_code");
            Map<String,Object> row=teacher(ids.get(code));facts.put(code,row);
            factRows.add(map("teacher_code",code,"teacher_id",ids.get(code),"record_present",row!=null,
                    "teacher_level",row==null?null:str(row,"teacher_level"),"city",row==null?null:str(row,"base_city"),"status",row==null?null:str(row,"status")));
            teacher.put("teacher_level",row==null?"":str(row,"teacher_level"));
            teacher.put("city",row==null?"":str(row,"base_city"));
        }
        Map<String,Object> result=CourseCatalog.qualify(catalog,request);
        List<Map<String,Object>> eligible=new ArrayList<>(),gaps=new ArrayList<>();
        List<Map<String,Object>> all=new ArrayList<>(rows(result.get("eligible")));all.addAll(rows(result.get("gaps")));
        // Restore catalog order, independent of which reason first excluded the teacher.
        Map<String,Map<String,Object>> byCode=new HashMap<>();for(Map<String,Object> row:all)byCode.put((String)row.get("teacher_code"),row);
        for(Map<String,Object> t:rows(catalog.get("teachers"))) {
            String code=(String)t.get("teacher_code");Map<String,Object> entry=byCode.get(code);if(entry==null)continue;
            entry.put("teacher_id",ids.get(code));Map<String,Object> fact=facts.get(code);
            if(fact==null || !"在库".equals(fact.get("status"))) {
                rows(entry.get("reasons")).add(map("code",fact==null?"TEACHER_RECORD_MISSING":"TEACHER_NOT_ACTIVE","message",fact==null?"绑定师资档案不存在":"绑定师资当前不在库"));
                entry.put("eligible",false);
            }
            (Boolean.TRUE.equals(entry.get("eligible"))?eligible:gaps).add(entry);
        }
        result.put("eligible",eligible);result.put("gaps",gaps);
        result.put("scope_id",scope.get("scope_id"));result.put("version",expected);
        result.put("status",rows(catalog.get("courses")).isEmpty()?"NOT_CONFIGURED":Boolean.TRUE.equals(result.get("ready"))?"READY":"INVALID_REQUEST");
        result.put("metadata_source","current_teacher_record");
        factRows.sort(Comparator.comparing(row -> (String)row.get("teacher_code")));
        return new QualificationSnapshot(result,factRows,snapshotId);
    }

    @SuppressWarnings("unchecked") private static List<String> sortedStrings(Object raw) {
        List<String> values=new ArrayList<>((List<String>)raw);Collections.sort(values);return values;
    }
    private static String fingerprint(Object value) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(Json.write(canonical(value)).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> source) {
            Map<String,Object> result=new TreeMap<>();
            source.forEach((key,item)->result.put((String)key,canonical(item)));return result;
        }
        if(value instanceof List<?> list) {List<Object> result=new ArrayList<>();for(Object item:list)result.add(canonical(item));return result;}
        return value;
    }

    private static List<Map<String,Object>> validateBindings(long scopeId,Map<String,Object> catalog,List<Map<String,Object>> bindings) throws Exception {
        Map<String,Long> ids=bindingIds(catalog,bindings);
        Map<String,Long> established=new HashMap<>();Map<Long,String> reverse=new HashMap<>();
        for(Map<String,Object> row:Db.query("SELECT teacher_code,teacher_id FROM m04_teacher_bindings WHERE scope_id=?",scopeId)) {
            established.put((String)row.get("teacher_code"),number(row,"teacher_id"));reverse.put(number(row,"teacher_id"),(String)row.get("teacher_code"));
        }
        List<Map<String,Object>> facts=new ArrayList<>();
        for(Map<String,Object> t:rows(catalog.get("teachers"))) {
            String code=(String)t.get("teacher_code");long id=ids.get(code);
            if(established.containsKey(code)&&established.get(code)!=id || reverse.containsKey(id)&&!code.equals(reverse.get(id))) throw error(409,"讲师编码与档案已固定绑定，不能通过导入改绑");
            Map<String,Object> row=teacher(id);if(row==null)throw error(400,"绑定的师资档案不存在");
            if(!Objects.equals(t.get("teacher_level"),str(row,"teacher_level")) || !Objects.equals(t.get("city"),str(row,"base_city"))) throw error(409,"导入等级或城市与现有师资档案不一致，请重新核对");
            facts.add(map("teacher_code",code,"teacher_id",id,"teacher_level",str(row,"teacher_level"),"city",str(row,"base_city"),"status",str(row,"status")));
        }
        return facts;
    }

    private static List<Map<String,Object>> parseBindings(Object raw) throws Exception {
        if(!(raw instanceof List<?> list)||list.size()>5000)throw error(400,"bindings 必须是最多5000行的数组");
        List<Map<String,Object>> result=new ArrayList<>();Set<String> codes=new HashSet<>();Set<Long> ids=new HashSet<>();
        for(Object item:list) {
            Map<String,Object> b=object(item,"teacher_code","teacher_id");
            String code=string(b.get("teacher_code"),"teacher_code",64);
            if(!code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw error(400,"讲师编码格式无效");
            long id=integer(b.get("teacher_id"),"teacher_id",false);
            if(!codes.add(code)||!ids.add(id))throw error(400,"讲师编码与档案必须一一绑定且不能重复");
            result.add(map("teacher_code",code,"teacher_id",id));
        }
        result.sort(Comparator.comparing(row -> (String)row.get("teacher_code")));return result;
    }
    private static Map<String,Long> bindingIds(Map<String,Object> catalog,List<Map<String,Object>> bindings) throws Exception {
        Map<String,Long> ids=new HashMap<>();for(Map<String,Object> b:bindings)ids.put((String)b.get("teacher_code"),number(b,"teacher_id"));
        Set<String> codes=new HashSet<>();for(Map<String,Object> t:rows(catalog.get("teachers")))codes.add((String)t.get("teacher_code"));
        if(!codes.equals(ids.keySet()))throw error(400,"本批每个讲师编码必须且只能有一个显式档案绑定");return ids;
    }
    private static Map<String,Long> storedBindingIds(long scopeId,Map<String,Object> catalog,List<Map<String,Object>> bindings) throws Exception {
        Map<String,Long> ids=bindingIds(catalog,bindings),saved=new HashMap<>();
        for(Map<String,Object> b:Db.query("SELECT teacher_code,teacher_id FROM m04_teacher_bindings WHERE scope_id=?",scopeId)) saved.put((String)b.get("teacher_code"),number(b,"teacher_id"));
        for(Map.Entry<String,Long> e:ids.entrySet()) if(!Objects.equals(saved.get(e.getKey()),e.getValue())) throw new IllegalStateException("持久化讲师绑定校验失败");
        return ids;
    }
    private static Map<String,Object> teacher(long id) throws SQLException {return Db.one("SELECT id,teacher_level,base_city,status FROM teachers WHERE id=?",id);}
    private static void unusedCatalogVersion(long id,String v) throws Exception {
        if(Db.one("SELECT version FROM m04_catalog_revisions WHERE scope_id=? AND catalog_version=?",id,v)!=null)throw error(409,"目录材料版本已使用，须提供新版本");
    }
    private static Map<String,Object> scope(Auth.Session s,long id,boolean manage) throws Exception {
        OrganizationAccessStore.person(s);
        Map<String,Object> row=Db.one("SELECT scope_id,organization_code,version FROM m04_catalog_scopes WHERE scope_id=?",id);
        if(row==null)throw error(404,"目录范围未配置");permission(s,(String)row.get("organization_code"),manage);return row;
    }
    private static void permission(Auth.Session s,String org,boolean manage) throws Exception {
        OrganizationAccessStore.person(s);
        Decision d=OrganizationAccessStore.authorize(s,manage?"catalog.manage":"catalog.read",manage?Action.HANDLE:Action.VIEW,org);
        if(!d.allowed())throw error(403,"目录权限未配置或不在授权机构范围："+d.reason());
    }
    private static void version(Map<String,Object> scope,long expected) throws Exception {if(number(scope,"version")!=expected)throw error(409,"目录版本已变化，请重新读取并预览");}
    private static Map<String,Object> currentSnapshot(Map<String,Object> scope) throws Exception {
        if(number(scope,"version")==0)return map("status","NOT_CONFIGURED","scope_id",scope.get("scope_id"),"organization_code",scope.get("organization_code"),"version",0L,"catalog",null,"bindings",List.of());
        return revisionSnapshot(scope,number(scope,"version"));
    }
    private static Map<String,Object> revisionSnapshot(Map<String,Object> scope,long v) throws Exception {
        Map<String,Object> r=revision(number(scope,"scope_id"),v);Map<String,Object> c=storedCatalog(r);
        List<Map<String,Object>> bindings=parseBindings(Json.parse((String)r.get("bindings")));storedBindingIds(number(scope,"scope_id"),c,bindings);
        return map("status",rows(c.get("courses")).isEmpty()?"NOT_CONFIGURED":"READY","scope_id",scope.get("scope_id"),"organization_code",scope.get("organization_code"),"version",v,"catalog",c,"bindings",bindings);
    }
    private static Map<String,Object> revision(long id,long v) throws Exception {
        Map<String,Object> r=Db.one("SELECT * FROM m04_catalog_revisions WHERE scope_id=? AND version=?",id,v);
        if(r==null)throw error(404,"目录版本不存在");return r;
    }
    private static Map<String,Object> storedCatalog(Map<String,Object> row) throws Exception {
        Map<String,Object> p=anyObject(Json.parse((String)row.get("payload")),"stored catalog");
        Map<String,Object> pre=CourseCatalog.preview(p);
        if(!Boolean.TRUE.equals(pre.get("ready")) || row.containsKey("catalog_version")&&!Objects.equals(row.get("catalog_version"),p.get("catalog_version")))throw new IllegalStateException("持久化目录校验失败");
        return anyObject(pre.get("data"),"catalog");
    }
    private static Map<String,Object> history(Map<String,Object> scope,Map<String,String> q) throws Exception {
        long limit=q.containsKey("limit")?pathInteger(q.get("limit"),"limit",false):20;
        if(limit>100)throw error(400,"limit 最大为100");
        long before=q.containsKey("before_version")?pathInteger(q.get("before_version"),"before_version",false):SAFE+1;
        List<Map<String,Object>> events=Db.query("SELECT version,batch_id,actor_account,actor_person,identity_version,created_at,change_comment,changes FROM m04_catalog_events WHERE scope_id=? AND version<? ORDER BY version DESC LIMIT ?",scope.get("scope_id"),before,limit+1);
        boolean more=events.size()>limit;if(more)events.remove(events.size()-1);
        for(Map<String,Object> event:events)event.put("changes",Json.parse((String)event.get("changes")));
        return map("scope_id",scope.get("scope_id"),"current_version",scope.get("version"),"events",events,"next_before_version",more?number(events.get(events.size()-1),"version"):null);
    }
    private static Map<String,Object> changes(Map<String,Object> head,Map<String,Object> catalog,List<Map<String,Object>> bindings) throws Exception {
        Map<String,Object> previous=emptyCatalog();List<Map<String,Object>> oldBindings=List.of();
        if(number(head,"version")>0) {
            Map<String,Object> r=revision(number(head,"scope_id"),number(head,"version"));previous=storedCatalog(r);oldBindings=parseBindings(Json.parse((String)r.get("bindings")));
        }
        Map<String,Object> diff=new LinkedHashMap<>();
        for(String table:List.of("courses","teachers","certifications"))diff.put(table,diff(rows(previous.get(table)),rows(catalog.get(table)),table));
        diff.put("bindings",diff(oldBindings,bindings,"bindings"));return diff;
    }
    private static Map<String,Object> diff(List<Map<String,Object>> oldRows,List<Map<String,Object>> newRows,String table) {
        Map<String,Map<String,Object>> old=new LinkedHashMap<>(),now=new LinkedHashMap<>();
        for(Map<String,Object> r:oldRows)old.put(rowKey(r,table),r);for(Map<String,Object> r:newRows)now.put(rowKey(r,table),r);
        List<String> added=new ArrayList<>(),updated=new ArrayList<>(),removed=new ArrayList<>();
        for(String key:now.keySet())if(!old.containsKey(key))added.add(key);else if(!old.get(key).equals(now.get(key)))updated.add(key);
        for(String key:old.keySet())if(!now.containsKey(key))removed.add(key);
        return map("added",added,"updated",updated,"removed",removed);
    }
    private static String rowKey(Map<String,Object> row,String table) {return table.equals("courses")?(String)row.get("course_code"):table.equals("certifications")?row.get("teacher_code")+"/"+row.get("course_code"):(String)row.get("teacher_code");}
    private static Map<String,Object> emptyCatalog() {return map("schema_version",CourseCatalog.SCHEMA_VERSION,"catalog_version","unconfigured","courses",List.of(),"teachers",List.of(),"certifications",List.of());}
    private static <T> T transaction(Db.TransactionWork<T> work) throws Exception {
        if(!Db.get().getAutoCommit())throw new IllegalStateException("目录操作不能嵌入其他事务");
        try{return Db.transaction(work);}catch(SQLException e){if("23505".equals(e.getSQLState()))throw error(409,"目录版本或绑定发生冲突，请重新预览");throw e;}
    }
    private static Map<String,String> query(HttpExchange ex,boolean history) throws Exception {
        String raw=ex.getRequestURI().getRawQuery();Map<String,String> q=new LinkedHashMap<>();
        if(raw==null)return q;if(raw.isEmpty()||!history)throw error(400,"本接口不接受查询参数");
        for(String pair:raw.split("&",-1)) {
            String[] kv=pair.split("=",-1);if(kv.length!=2)throw error(400,"查询参数格式无效");
            String key,value;try{key=URLDecoder.decode(kv[0],StandardCharsets.UTF_8);value=URLDecoder.decode(kv[1],StandardCharsets.UTF_8);}catch(IllegalArgumentException e){throw error(400,"查询参数编码无效");}
            if(!Set.of("limit","before_version").contains(key)||q.putIfAbsent(key,value)!=null)throw error(400,"未知或重复查询参数");
        }
        return q;
    }
    private static void method(HttpExchange ex,String... allowed) throws Exception {
        for(String s:allowed)if(s.equals(ex.getRequestMethod()))return;
        ex.getResponseHeaders().set("Allow",String.join(", ",allowed));throw error(405,"请求方法不受支持");
    }
    private static long pathInteger(String value,String field,boolean zero) throws Exception {
        if(value==null||!value.matches(zero?"0|[1-9][0-9]*":"[1-9][0-9]*")||value.length()>16)throw error(400,field+" 必须为规范安全整数");
        try{long v=Long.parseLong(value);if(v>SAFE)throw error(400,field+" 超出安全整数范围");return v;}catch(NumberFormatException e){throw error(400,field+" 无效");}
    }
    private static long integer(Object value,String field,boolean zero) throws Exception {
        if(!(value instanceof Number n))throw error(400,field+" 必须为安全整数");double d=n.doubleValue();
        if(!Double.isFinite(d)||d!=Math.rint(d)||d<(zero?0:1)||d>SAFE)throw error(400,field+" 必须为安全整数");return n.longValue();
    }
    private static String string(Object value,String field,int max) throws Exception {
        if(!(value instanceof String s)||s.isBlank()||s.length()>max||!s.equals(s.strip())||s.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.isSpaceChar(c)&&!Character.isWhitespace(c)))throw error(400,field+" 格式无效");return s;
    }
    private static Map<String,Object> object(Object raw,String... fields) throws Exception {
        Map<String,Object> result=anyObject(raw,"request");if(!result.keySet().equals(Set.of(fields)))throw error(400,"请求包含未知字段或缺少必填字段");return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> anyObject(Object raw,String label) throws Exception {
        if(!(raw instanceof Map<?,?> m)||m.keySet().stream().anyMatch(k->!(k instanceof String)))throw error(400,label+" 必须为对象");return (Map<String,Object>)raw;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value) {return (List<Map<String,Object>>)value;}
    private static long number(Map<String,Object> row,String field) {return ((Number)row.get(field)).longValue();}
    private static String str(Map<String,Object> row,String field) {Object v=row.get(field);return v==null?"":v.toString();}
    private static Api.ApiException error(int status,String message) {return new Api.ApiException(status,message);}
    private static Map<String,Object> map(Object... kv) {Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
}
