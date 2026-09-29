package com.training;

import java.util.*;

/** Server-only standard-course gate for the existing recommendation pipeline. No routes or ranking. */
final class RecommendationQualification {
    private static final long SAFE = 9007199254740991L;
    private static final String SCHEMA = "m04_recommendation_qualification_v1";
    private static final List<String> CANDIDATE_FIELDS = List.of("recommendations", "results", "review_candidates", "review_candidates3");
    private static final Set<String> SELECTION_FIELDS = Set.of("scope_id", "expected_version", "course_code", "as_of", "accepted_levels", "allowed_cities");
    private static final Set<String> SERVER_FIELDS = Set.of("qualification", "qualification_context", "qualification_summary", "course_qualification",
            "eligible", "eligible_teacher_ids", "eligible_count", "candidates", "candidate_ids", "teacher_ids", "certified", "catalog", "bindings",
            "context_id", "teacher_facts_id", "catalog_snapshot_id", "criteria_id", "_access_version");
    // Non-secret, process-local cache partition; weak keys do not retain expired sessions on their own.
    private static final Map<Auth.Session,String> SESSION_PARTITIONS = new WeakHashMap<>();
    private RecommendationQualification() {}

    /** Only this factory can create a capture. Absence, not null/false/empty, means no standard course selected. */
    static Capture capture(Auth.Session supplied, Map<String,Object> recommendationBody) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            committed();
            Auth.Session actor = authenticated(supplied);
            if (recommendationBody == null) throw error(400,"推荐请求必须为对象");
            for (Object key : recommendationBody.keySet()) {
                if (!(key instanceof String name) || SERVER_FIELDS.contains(name) || CANDIDATE_FIELDS.contains(name) || name.startsWith("_m04_"))
                    throw error(400,"推荐请求不能提供服务端资格、候选或上下文");
            }
            String partition = SESSION_PARTITIONS.computeIfAbsent(actor, ignored -> UUID.randomUUID().toString());
            String identity = identityVersion();
            if (!recommendationBody.containsKey("standard_course"))
                return new Capture(actor,identity,partition,0,0,null,null);
            Map<String,Object> selected = object(recommendationBody.get("standard_course"), SELECTION_FIELDS);
            long scope = integer(selected.get("scope_id"), false), version = integer(selected.get("expected_version"), true);
            Map<String,Object> request = map("course_code",selected.get("course_code"),"as_of",selected.get("as_of"),
                    "accepted_levels",selected.get("accepted_levels"),"allowed_cities",selected.get("allowed_cities"));
            Map<String,Object> qualification = CourseCatalogIntegration.qualification(actor,scope,version,request);
            Map<String,Object> context = context(qualification);
            // Use the trusted helper's normalized conditions, never caller-owned mutable collections.
            Map<String,Object> normalized = map("course_code",context.get("course_code"),"as_of",context.get("as_of"),
                    "accepted_levels",context.get("accepted_levels"),"allowed_cities",context.get("allowed_cities"));
            return new Capture(actor,identity,partition,scope,version,Json.write(normalized),qualification);
        }
    }

    /** Immutable, unforgeable Java object. Keep in server job/cache state; never rehydrate it from JSON. */
    static final class Capture {
        private final Auth.Session owner;
        private final long ownerId,scopeId,version;
        private final String identityVersion,requestJson,qualificationJson,contextId,status,cacheKey;
        private final Set<Long> eligible;
        private Capture(Auth.Session owner,String identity,String partition,long scope,long version,String request,Map<String,Object> qualification) throws Exception {
            this.owner=owner;this.ownerId=owner.uid;this.identityVersion=identity;this.scopeId=scope;this.version=version;this.requestJson=request;
            this.qualificationJson=qualification==null?null:Json.write(qualification);
            this.contextId=qualification==null?"":(String)context(qualification).get("context_id");
            LinkedHashSet<Long> allowed=new LinkedHashSet<>();
            if(qualification!=null) {
                validateQualification(qualification,scope,version,ownerId);
                for(Map<String,Object> row:rows(qualification.get("eligible")))allowed.add(integer(row.get("teacher_id"),false));
            }
            this.eligible=Collections.unmodifiableSet(allowed);
            this.status=qualification==null?"NOT_SELECTED":"NOT_CONFIGURED".equals(qualification.get("status"))?"NOT_CONFIGURED"
                    :!Boolean.TRUE.equals(qualification.get("ready"))||!"READY".equals(qualification.get("status"))?"INVALID_SELECTION"
                    :allowed.isEmpty()?"NO_ELIGIBLE":"READY";
            this.cacheKey=SCHEMA+":"+partition+":"+(qualification==null?"NOT_SELECTED:"+identity:contextId);
        }
        boolean selected(){return qualificationJson!=null;}
        String status(){return status;}
        Set<Long> eligibleTeacherIds(){return eligible;}
        String cacheKey(){return cacheKey;}
        Map<String,Object> summary(){
            Map<String,Object> q=qualificationJson==null?null:Json.parseMap(qualificationJson);
            String message=switch(status) {
                case "NOT_SELECTED" -> "未选择标准课程，未核验课程认证，沿用原推荐规则";
                case "NOT_CONFIGURED" -> "所选目录尚未配置，不能按该课程推荐师资";
                case "INVALID_SELECTION" -> "所选课程当前不能核验，不能回退到无课程条件的推荐";
                case "NO_ELIGIBLE" -> "当前没有满足所选课程条件的已认证在库师资";
                default -> "已核验具体课程资格，后续仍按原评分、交通与并列规则筛选";
            };
            return map("schema_version",SCHEMA,"selected",selected(),"status",status,"scoring_allowed",!selected()||"READY".equals(status),
                    "eligible_count",selected()?eligible.size():null,"qualification_context",q==null?null:q.get("qualification_context"),
                    "gaps",q==null?new ArrayList<>():q.get("gaps"),"issues",q==null?new ArrayList<>():q.getOrDefault("issues",new ArrayList<>()),"message",message);
        }
        @Override public String toString(){return "RecommendationQualification.Capture(server-only)";}
    }

    /** Call immediately after the existing full-library SQL, before profiles, scores or top-three selection. */
    static Pool filterBeforeScoring(Auth.Session actor,Capture capture,List<Map<String,Object>> fullDatabasePool) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            revalidate(actor,capture);
            if(fullDatabasePool==null)throw error(400,"必须提供服务端完整在库师资池");
            LinkedHashSet<Long> actual=new LinkedHashSet<>();
            Map<Long,Map<String,Object>> currentFacts=new HashMap<>();
            for(Map<String,Object> row:Db.query("SELECT id,teacher_level,base_city FROM teachers WHERE status='在库' ORDER BY id")) {
                long id=integer(row.get("id"),false);actual.add(id);currentFacts.put(id,row);
            }
            LinkedHashSet<Long> provided=new LinkedHashSet<>();
            for(Map<String,Object> row:fullDatabasePool) {
                if(row==null)throw error(409,"师资池不完整，请重新读取完整在库名单");
                long id=integer(row.get("id"),false);
                if(!provided.add(id))throw error(409,"师资池存在重复记录，不能作为完整池");
            }
            if(!actual.equals(provided))throw error(409,"师资池已变化或已被截短，必须在完整在库池评分前核验资格");
            for(Map<String,Object> row:fullDatabasePool)for(String field:List.of("teacher_level","base_city"))
                if(!row.containsKey(field)||!Objects.toString(row.get(field),"").equals(Objects.toString(currentFacts.get(integer(row.get("id"),false)).get(field),"")))
                    throw error(409,"完整池的等级或城市已变化，请重新读取师资资料");
            Map<Long,Map<String,Object>> gaps=new HashMap<>();
            if(capture.selected())for(Map<String,Object> gap:rows(Json.parseMap(capture.qualificationJson).get("gaps")))gaps.put(integer(gap.get("teacher_id"),false),gap);
            List<Map<String,Object>> admitted=new ArrayList<>(),excluded=new ArrayList<>();
            LinkedHashSet<Long> admittedIds=new LinkedHashSet<>();
            for(Map<String,Object> teacher:fullDatabasePool) {
                long id=integer(teacher.get("id"),false);
                if(!capture.selected()||capture.eligible.contains(id)) {
                    admitted.add(immutableMap(teacher));admittedIds.add(id);
                } else if(gaps.containsKey(id)) excluded.add(gaps.get(id));
                else excluded.add(map("teacher_id",id,"teacher_code",null,"eligible",false,"reasons",List.of(map("code",
                        "NOT_CONFIGURED".equals(capture.status)?"CATALOG_NOT_CONFIGURED":"INVALID_SELECTION".equals(capture.status)?"COURSE_NOT_AVAILABLE":"CATALOG_TEACHER_MISSING",
                        "message","NOT_CONFIGURED".equals(capture.status)?"所选课程目录尚未配置":"INVALID_SELECTION".equals(capture.status)?"所选课程当前不能核验":"当前目录没有此师资的显式课程资格")),"evidence",null));
            }
            return new Pool(capture,admitted,admittedIds,excluded,actual.size());
        }
    }

    static final class Pool {
        private final Capture capture;
        private final List<Map<String,Object>> teachers;
        private final Set<Long> admittedIds;
        private final String exclusionsJson;
        private final int total;
        private Pool(Capture capture,List<Map<String,Object>> teachers,Set<Long> ids,List<Map<String,Object>> exclusions,int total){
            this.capture=capture;this.teachers=List.copyOf(teachers);this.admittedIds=Set.copyOf(ids);this.exclusionsJson=Json.write(exclusions);this.total=total;
        }
        List<Map<String,Object>> teachers(){return teachers;}
        List<Map<String,Object>> exclusions(){return rows(Json.parse(exclusionsJson));}
        int totalInLibrary(){return total;}
        Map<String,Object> summary(){
            Map<String,Object> value=capture.summary();
            value.put("total_in_library",total);value.put("scoring_pool_count",teachers.size());value.put("pool_exclusions",exclusions());return value;
        }
        @Override public String toString(){return "RecommendationQualification.Pool(server-only)";}
    }

    /** Same original Session is required for every use, including queued/running job reads and cancellation. */
    static void revalidate(Auth.Session supplied,Capture capture) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            committed();
            Auth.Session actor=authenticated(supplied);
            if(capture==null)throw error(400,"缺少服务端课程资格上下文");
            // Check ownership before looking up any stored qualification or returning other users' details.
            if(actor!=capture.owner||actor.uid!=capture.ownerId)throw error(403,"该推荐核验仅属于原登录会话，请重新分析");
            if(!capture.identityVersion.equals(identityVersion()))throw error(409,"业务权限配置已变化，请重新分析推荐");
            if(capture.selected()) {
                Map<String,Object> fresh=CourseCatalogIntegration.qualification(actor,capture.scopeId,capture.version,Json.parseMap(capture.requestJson));
                validateQualification(fresh,capture.scopeId,capture.version,capture.ownerId);
                if(!capture.contextId.equals(context(fresh).get("context_id")))throw error(409,"课程、认证或当前师资事实已变化，已丢弃旧推荐结果");
            }
        }
    }

    /** Complete synchronous/async scoring under the same business lock used to publish the job/cache entry. */
    static Result complete(Auth.Session actor,Pool pool,Map<String,Object> scoredResult) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if(pool==null)throw error(400,"推荐必须先核验完整师资池");
            revalidate(actor,pool.capture);
            if(scoredResult==null)throw error(400,"推荐结果必须为对象");
            for(String key:CANDIDATE_FIELDS) {
                if(!scoredResult.containsKey(key))continue;
                Object raw=scoredResult.get(key);if(!(raw instanceof List<?> list))throw error(409,"推荐候选格式无效，不能发布");
                Set<Long> seen=new HashSet<>();
                for(Object item:list) {
                    if(!(item instanceof Map<?,?> row))throw error(409,"推荐候选格式无效，不能发布");
                    long id=integer(row.get("teacher_id"),false);
                    if(!seen.add(id)||!pool.admittedIds.contains(id))throw error(409,"推荐包含未通过本次完整池核验的师资，不能发布");
                }
            }
            Map<String,Object> copied=copy(scoredResult);
            copied.put("total_in_library",pool.total);
            if(pool.capture.selected()&&!"READY".equals(pool.capture.status)) {
                for(String key:CANDIDATE_FIELDS)if(!key.equals("review_candidates3")||copied.containsKey(key))copied.put(key,List.of());
                for(String key:List.of("eligible_count","returned_count","review_candidate_count"))copied.put(key,0);
                copied.put("notice",pool.capture.summary().get("message"));
            }
            copied.put("course_qualification",pool.summary());
            return new Result(pool.capture,Json.write(copied));
        }
    }

    /** This opaque wrapper must stay in the server job/cache; clients receive only deliver() output. */
    static final class Result {
        private final Capture capture;
        private final String payload;
        private Result(Capture capture,String payload){this.capture=capture;this.payload=payload;}
        String cacheKey(){return capture.cacheKey();}
        @Override public String toString(){return "RecommendationQualification.Result(server-only)";}
    }
    /** Call on every synchronous return, async result fetch and server cache hit. Never accept a JSON substitute. */
    static Map<String,Object> deliver(Auth.Session actor,Result result) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            if(result==null)throw error(400,"缺少服务端已核验推荐结果");
            revalidate(actor,result.capture);
            return Json.parseMap(result.payload);
        }
    }

    private static Auth.Session authenticated(Auth.Session supplied) throws Exception {
        Auth.Session actor=Auth.current(supplied);if(actor==null)throw error(401,"原登录会话无效或已失效，请重新登录并分析");return actor;
    }
    private static void committed() throws Exception {if(!Db.get().getAutoCommit())throw new IllegalStateException("推荐资格不能捕获或发布未提交的事务状态");}
    private static String identityVersion() throws Exception {
        OrganizationAccess.Configuration config=OrganizationAccessStore.configuration();return config==null?"":config.version();
    }
    private static Map<String,Object> context(Map<String,Object> qualification) throws Exception {
        if(!(qualification.get("qualification_context") instanceof Map<?,?> raw))throw new IllegalStateException("可信资格上下文缺失");
        @SuppressWarnings("unchecked") Map<String,Object> value=(Map<String,Object>)raw;
        if(!"m04_qualification_context_v1".equals(value.get("schema_version"))||!(value.get("context_id") instanceof String id)||!id.matches("[0-9a-f]{64}"))throw new IllegalStateException("可信资格上下文无效");
        return value;
    }
    private static void validateQualification(Map<String,Object> q,long scope,long version,long owner) throws Exception {
        Map<String,Object> c=context(q);
        if(integer(c.get("scope_id"),false)!=scope||integer(c.get("version"),true)!=version||integer(c.get("account_id"),false)!=owner
                ||!(q.get("eligible") instanceof List<?>)||!(q.get("gaps") instanceof List<?>))throw new IllegalStateException("可信资格结果身份或目录不一致");
        boolean ready=Boolean.TRUE.equals(q.get("ready"))&&"READY".equals(q.get("status"));
        Set<Long> seen=new HashSet<>();
        for(String key:List.of("eligible","gaps"))for(Map<String,Object> row:rows(q.get(key))) {
            long id=integer(row.get("teacher_id"),false);
            if(!seen.add(id)||!Boolean.valueOf(key.equals("eligible")).equals(row.get("eligible")))throw new IllegalStateException("可信资格候选重复或分类不一致");
        }
        if(!ready&&!rows(q.get("eligible")).isEmpty())throw new IllegalStateException("未配置或无效条件不能返回合格师资");
    }
    private static long integer(Object value,boolean zero) throws Api.ApiException {
        if(!(value instanceof Number n))throw error(400,"空间、版本和师资编号须为安全整数数字");
        double d=n.doubleValue();if(!Double.isFinite(d)||d!=Math.rint(d)||d<(zero?0:1)||d>SAFE)throw error(400,"空间、版本和师资编号须为安全整数数字");return n.longValue();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object raw,Set<String> fields) throws Exception {
        if(!(raw instanceof Map<?,?> m)||!m.keySet().equals(fields))throw error(400,"标准课程选择缺少必填字段或含未知字段");return (Map<String,Object>)m;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private static Map<String,Object> copy(Map<String,Object> value){return Json.parseMap(Json.write(value));}
    private static Map<String,Object> immutableMap(Map<String,Object> source){
        Map<String,Object> copied=new LinkedHashMap<>();
        for(Map.Entry<String,Object> entry:source.entrySet())copied.put(entry.getKey(),immutableValue(entry.getValue()));
        return Collections.unmodifiableMap(copied);
    }
    @SuppressWarnings("unchecked") private static Object immutableValue(Object value){
        if(value instanceof Map<?,?> nested)return immutableMap((Map<String,Object>)nested);
        if(value instanceof List<?> nested){
            List<Object> copied=new ArrayList<>();for(Object item:nested)copied.add(immutableValue(item));return Collections.unmodifiableList(copied);
        }
        return value;
    }
    private static Api.ApiException error(int code,String message){return new Api.ApiException(code,message);}
    private static Map<String,Object> map(Object... pairs){Map<String,Object> value=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
}
