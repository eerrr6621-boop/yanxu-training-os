package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Administrator-only contact preparation for imported pending users. Never verifies or activates. */
public final class NotificationChannelsAccountEmailPreparation {
    private static final String BASE="/api/account-email-preparation";
    private static final long MAX=9007199254740991L;
    private static final Set<String> FIELDS=Set.of("user_id","expected_revision","request_id","email");
    private NotificationChannelsAccountEmailPreparation() {}

    /** After users and OrganizationAccountImport.init(), before accepting requests. Empty tables only. */
    public static void init() throws SQLException {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit()) throw new IllegalStateException("邮箱准备建表不能嵌入业务事务");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_account_email_heads (user_id BIGINT PRIMARY KEY REFERENCES organization_account_import_people(account_id),import_person_code VARCHAR(64) NOT NULL REFERENCES organization_account_import_people(person_code),import_binding CHAR(64) NOT NULL,revision BIGINT NOT NULL CHECK(revision>=0),email VARCHAR(254),email_key VARCHAR(254))");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_account_email_revisions (user_id BIGINT NOT NULL REFERENCES s01_account_email_heads(user_id),revision BIGINT NOT NULL CHECK(revision>0),email VARCHAR(254),actor_user_id BIGINT NOT NULL REFERENCES users(id),recorded_at VARCHAR(40) NOT NULL,PRIMARY KEY(user_id,revision))");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_account_email_requests (request_id VARCHAR(36) PRIMARY KEY,actor_user_id BIGINT NOT NULL REFERENCES users(id),user_id BIGINT NOT NULL REFERENCES s01_account_email_heads(user_id),expected_revision BIGINT NOT NULL CHECK(expected_revision>=0),email_digest CHAR(64) NOT NULL,import_binding CHAR(64) NOT NULL,result_revision BIGINT NOT NULL CHECK(result_revision>=0))");
            Db.exec("CREATE INDEX IF NOT EXISTS s01_account_email_duplicates ON s01_account_email_heads(email_key)");
            NotificationChannelsAccountEmailMaintenance.init();
        }
    }

    public static boolean matches(String path) {return path!=null&&(path.equals(BASE)||path.startsWith(BASE+"/"));}

    /** Existing authenticated users/delete host calls before deleting an audit actor. */
    public static void guardAccountDeletion(long user) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            try {
                validId(user);
                NotificationChannelsAccountEmailMaintenance.guardAccountDeletion(user);
                if(Db.one("SELECT user_id FROM s01_account_email_revisions WHERE actor_user_id=? LIMIT 1",user)!=null
                        ||Db.one("SELECT user_id FROM s01_account_email_requests WHERE actor_user_id=? LIMIT 1",user)!=null)
                    fail(409,"该账号已有邮箱登记记录，请停用并保留历史");
            }catch(Api.ApiException e) {throw e;}
            catch(Exception e) {throw unavailable();}
        }
    }

    /** Host calls after its authenticated, size-limited UTF-8 body read, before legacy Double parsing.
     * This narrow object parser retains exact numeric values; it is not a replacement for global Json.
     */
    public static Map<String,Object> parseBody(String text) throws Api.ApiException {
        try {return new Body(text).parse();}
        catch(Exception ignored) {throw new Api.ApiException(400,"邮箱资料请求不是有效的JSON对象");}
    }

    private static final class Body {
        private final String text;private int position;
        Body(String value) {if(value==null||value.length()>1024*1024)throw new IllegalArgumentException();text=value;}
        Map<String,Object> parse() {
            Map<String,Object> values=new LinkedHashMap<>();space();take('{');space();
            if(peek('}')) {position++;space();end();return values;}
            while(true) {
                String field=quoted();if(!FIELDS.contains(field)||values.containsKey(field))throw new IllegalArgumentException();
                space();take(':');space();Object value;
                if(peek('"'))value=quoted();
                else if(text.startsWith("null",position)) {position+=4;value=null;}
                else {
                    int start=position;
                    while(position<text.length()&&"-+0123456789.eE".indexOf(text.charAt(position))>=0)position++;
                    String number=text.substring(start,position);
                    // Bounded numeric lexemes avoid expensive enormous exponents/precision.
                    if(number.length()>64||!number.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,4})?"))throw new IllegalArgumentException();
                    value=new java.math.BigDecimal(number);
                }
                values.put(field,value);space();
                if(peek('}')) {position++;space();end();return values;}
                take(',');space();
            }
        }
        private String quoted() {
            int start=position;take('"');
            while(position<text.length()) {
                char c=text.charAt(position++);
                if(c=='"')return (String)Json.parse(text.substring(start,position));
                if(c<32)throw new IllegalArgumentException();
                if(c=='\\') {
                    if(position>=text.length())throw new IllegalArgumentException();
                    char escape=text.charAt(position++);
                    if(escape=='u') {for(int i=0;i<4;i++)if(position>=text.length()||"0123456789abcdefABCDEF".indexOf(text.charAt(position++))<0)throw new IllegalArgumentException();}
                    else if("\"\\/bfnrt".indexOf(escape)<0)throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException();
        }
        private void space() {while(position<text.length()&&" \t\r\n".indexOf(text.charAt(position))>=0)position++;}
        private void take(char c) {if(!peek(c))throw new IllegalArgumentException();position++;}
        private boolean peek(char c) {return position<text.length()&&text.charAt(position)==c;}
        private void end() {if(position!=text.length())throw new IllegalArgumentException();}
    }

    /** Internal recheck; the host separately authenticates before its bounded JSON body read. */
    private static void requireAdmin(Auth.Session supplied) throws Api.ApiException {
        Auth.Session current=Auth.current(supplied);
        if(current==null) fail(401,"未登录或会话已失效，请重新登录");
        if(!"admin".equals(current.role)) fail(403,"仅系统管理员可维护待核验邮箱资料");
    }

    public static boolean handle(HttpExchange ex,Auth.Session supplied) throws Exception {
        if(!matches(ex.getRequestURI().getPath())) return false;
        synchronized(Api.MUTATION_LOCK) {
            ex.getResponseHeaders().set("Cache-Control","no-store");
            try {
                requireAdmin(supplied);
                if(Auth.get(Api.token(ex))!=supplied) fail(401,"未登录或会话已失效，请重新登录");
                if(!BASE.equals(ex.getRequestURI().getPath())) fail(404,"邮箱资料接口不存在");
                String method=ex.getRequestMethod();
                if(method.equals("GET")) Api.ok(ex,get(supplied,queryUser(ex.getRequestURI().getRawQuery())));
                else if(method.equals("POST")) {
                    if(ex.getRequestURI().getRawQuery()!=null) fail(400,"保存邮箱资料不接受查询参数");
                    String type=ex.getRequestHeaders().getFirst("Content-Type");
                    if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json")) fail(415,"请使用JSON请求");
                    Api.ok(ex,save(supplied,Api.body(ex)));
                } else {ex.getResponseHeaders().set("Allow","GET, POST");fail(405,"请求方法不受支持");}
                return true;
            } catch(Api.ApiException e) {throw e;}
            catch(Exception e) {throw unavailable();} // No SQL/parameter values or cause reach the host logger.
        }
    }

    /** Trusted package callers and isolated tests use the same authentication checks as HTTP. */
    static Map<String,Object> get(Auth.Session supplied,long user) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            try {
                requireAdmin(supplied);validId(user);
                Target target=target(user); return response(target,load(target));
            } catch(Api.ApiException e) {throw e;}
            catch(Exception e) {throw unavailable();}
        }
    }

    static Map<String,Object> save(Auth.Session supplied,Map<String,Object> body) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            try {
                requireAdmin(supplied);
                if(body==null||!body.keySet().equals(FIELDS)) fail(400,"保存请求字段不完整或包含不允许的字段");
                long user=integer(body.get("user_id"),1),expected=integer(body.get("expected_revision"),0);
                String request=requestId(body.get("request_id")),email=email(body.get("email"));
                if(NotificationChannelsAccountEmailMaintenance.isMaintenanceRequest(request)) fail(409,"请求编号已用于不同操作，请刷新后重试");
                String digest=digest(email==null?"null":"email:"+email);
                if(!Db.get().getAutoCommit()) fail(409,"邮箱资料保存不能嵌入其他业务事务");
                return Db.transaction(()->{
                    requireAdmin(supplied);Target target=target(user);State state=load(target);
                    Map<String,Object> old=Db.one("SELECT * FROM s01_account_email_requests WHERE request_id=?",request);
                    if(old!=null) {
                        if(number(old,"actor_user_id")!=supplied.uid||number(old,"user_id")!=user||number(old,"expected_revision")!=expected||!digest.equals(old.get("email_digest"))||!target.binding.equals(old.get("import_binding"))) fail(409,"请求编号已用于不同操作，请刷新后重试");
                        if(number(old,"result_revision")>state.revision) fail(409,"邮箱资料记录不一致，请联系管理员核对");
                        // Replay returns the current state; it cannot restore an older candidate value.
                        return response(target,state);
                    }
                    if(expected!=state.revision) fail(409,"邮箱资料已更新，请刷新后重试");
                    NotificationChannelsAccountEmailMaintenance.rejectExistingDuplicate(email,user);
                    if(!state.exists) Db.exec("INSERT INTO s01_account_email_heads(user_id,import_person_code,import_binding,revision,email,email_key) VALUES(?,?,?,0,NULL,NULL)",user,target.person,target.binding);
                    long next=state.revision;
                    if(!Objects.equals(email,state.email)) {
                        if(next>=MAX) fail(409,"邮箱资料版本需要维护，请联系管理员");
                        next++;
                        Db.exec("INSERT INTO s01_account_email_revisions(user_id,revision,email,actor_user_id,recorded_at) VALUES(?,?,?,?,?)",user,next,email,supplied.uid,Instant.now().toString());
                        try(PreparedStatement ps=Db.get().prepareStatement("UPDATE s01_account_email_heads SET revision=?,email=?,email_key=? WHERE user_id=? AND revision=? AND import_binding=?")) {
                            ps.setLong(1,next);ps.setString(2,email);ps.setString(3,key(email));ps.setLong(4,user);ps.setLong(5,expected);ps.setString(6,target.binding);
                            if(ps.executeUpdate()!=1) fail(409,"邮箱资料已更新，请刷新后重试");
                        }
                    }
                    Db.exec("INSERT INTO s01_account_email_requests(request_id,actor_user_id,user_id,expected_revision,email_digest,import_binding,result_revision) VALUES(?,?,?,?,?,?,?)",request,supplied.uid,user,expected,digest,target.binding,next);
                    // Recheck inside the same host lock/transaction before emitting any saved history.
                    requireAdmin(supplied);Target after=target(user);
                    if(!after.equals(target)) fail(409,"账号准备关系已变化，请刷新后核对");
                    return response(after,load(after));
                });
            } catch(Api.ApiException e) {throw e;}
            catch(Exception e) {throw unavailable();}
        }
    }

    private record Target(long user,String person,String binding) {}
    private record State(boolean exists,long revision,String email,List<Map<String,Object>> history) {}

    /** Server-only password-step snapshot; never expose this record as an HTTP response. */
    record Snapshot(long userId,long revision,String email,String importBinding) {
        @Override public String toString() {return "Snapshot[redacted]";}
    }

    static Snapshot loginSnapshot(long user) throws Api.ApiException {
        return NotificationChannelsAccountEmailMaintenance.loginSnapshot(user);
    }

    static Snapshot importedLoginSnapshot(long user) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            try {
                validId(user);Target target=target(user,true);State state=load(target);
                if(!state.exists||state.revision<=0||state.email==null
                        ||NotificationChannelsAccountEmailMaintenance.duplicate(state.email,user))
                    fail(409,"个人邮箱资料尚不能用于登录核验，请联系管理员核对");
                return new Snapshot(user,state.revision,state.email,target.binding);
            }catch(Api.ApiException|java.time.format.DateTimeParseException e) {throw new Api.ApiException(409,"个人邮箱资料尚不能用于登录核验，请联系管理员核对");}
            catch(Exception e) {throw unavailable();}
        }
    }

    private static Target target(long user) throws Exception {
        return target(user,false);
    }

    private static Target target(long user,boolean login) throws Exception {
        return target(user,login,true);
    }

    private static Target target(long user,boolean login,boolean checkEligibility) throws Exception {
        Map<String,Object> row=Db.one("SELECT i.person_code,i.source_namespace,i.source_reference,i.account_id,i.batch_key,i.source_fingerprint,i.disposition,u.status,u.role,(u.password IS NULL) AS password_empty,(u.password IS NULL OR u.password='') AS password_missing_for_login FROM organization_account_import_people i JOIN users u ON u.id=i.account_id WHERE i.account_id=?",user);
        if(row==null) {fail(404,"账号不存在或不属于导入资料准备范围");return null;}
        NotificationChannelsAccountEmailMaintenance.rejectDualSource(user,true);
        if(checkEligibility&&login) {
            if(number(row,"status")!=1||!Boolean.FALSE.equals(row.get("password_missing_for_login"))) fail(409,"账号当前状态不能用于登录核验");
        } else if(checkEligibility&&(number(row,"status")!=0||!"viewer".equals(row.get("role"))||!Boolean.TRUE.equals(row.get("password_empty")))) fail(409,"账号当前状态不允许维护待核验邮箱资料");
        Map<String,Object> identity=new TreeMap<>();
        for(String name:List.of("person_code","source_namespace","source_reference","batch_key","source_fingerprint","disposition")) identity.put(name,row.get(name));
        identity.put("account_id",user);
        return new Target(user,String.valueOf(row.get("person_code")),digest(Json.write(identity)));
    }

    private static State load(Target target) throws Exception {
        Map<String,Object> head=Db.one("SELECT * FROM s01_account_email_heads WHERE user_id=?",target.user);
        if(head==null)return new State(false,0,null,List.of());
        if(!target.person.equals(head.get("import_person_code"))||!target.binding.equals(head.get("import_binding"))) fail(409,"账号准备关系已变化，请刷新后核对");
        long revision=number(head,"revision");String current=(String)head.get("email");
        if(revision<0||revision>MAX||!Objects.equals(key(current),head.get("email_key"))||!Objects.equals(current,email(current))) fail(409,"邮箱资料记录不一致，请联系管理员核对");
        List<Map<String,Object>> history=new ArrayList<>();long expected=1;String last=null;
        for(Map<String,Object> row:Db.query("SELECT revision,email,actor_user_id,recorded_at FROM s01_account_email_revisions WHERE user_id=? ORDER BY revision",target.user)) {
            String value=(String)row.get("email"),at=String.valueOf(row.get("recorded_at"));
            if(number(row,"revision")!=expected++||Objects.equals(value,last)||!Objects.equals(value,email(value))) fail(409,"邮箱资料历史不一致，请联系管理员核对");
            Instant.parse(at);last=value;
            Map<String,Object> item=new LinkedHashMap<>();item.put("revision",number(row,"revision"));item.put("email",value);item.put("status",status(value));item.put("actor_user_id",number(row,"actor_user_id"));item.put("recorded_at",at);history.add(Collections.unmodifiableMap(item));
        }
        if(expected-1!=revision||!Objects.equals(last,current)) fail(409,"邮箱资料历史不一致，请联系管理员核对");
        auditRequests(target,revision,history);
        return new State(true,revision,current,List.copyOf(history));
    }

    private static void auditRequests(Target target,long revision,List<Map<String,Object>> history) throws Exception {
        Set<Long> changes=new HashSet<>();
        for(Map<String,Object> row:Db.query("SELECT * FROM s01_account_email_requests WHERE user_id=?",target.user)) {
            long before=number(row,"expected_revision"),after=number(row,"result_revision");
            if(before<0||after<before||after>revision||after-before>1||number(row,"actor_user_id")<=0
                    ||!target.binding.equals(row.get("import_binding"))
                    ||!(row.get("request_id") instanceof String id)||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                fail(409,"邮箱资料历史不一致，请联系管理员核对");
            Object value=after==0?null:history.get((int)after-1).get("email");
            if(!digest(value==null?"null":"email:"+value).equals(row.get("email_digest"))) fail(409,"邮箱资料历史不一致，请联系管理员核对");
            if(after>before&&(!changes.add(after)||number(history.get((int)after-1),"actor_user_id")!=number(row,"actor_user_id"))) fail(409,"邮箱资料历史不一致，请联系管理员核对");
        }
        if(changes.size()!=revision) fail(409,"邮箱资料历史不一致，请联系管理员核对");
    }

    /** Trusted maintenance bridge: still reads and appends the original imported history. */
    record ImportedState(long userId,String personCode,String binding,boolean exists,long revision,String email,List<Map<String,Object>> history) {
        @Override public String toString(){return "ImportedState[redacted]";}
    }
    static ImportedState importedForMaintenance(long user) throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK))throw unavailable();
        validId(user);Target target=target(user,false,false);State state=load(target);
        return new ImportedState(user,target.person,target.binding,state.exists,state.revision,state.email,state.history);
    }
    static ImportedState appendMaintainedImported(ImportedState previous,long actor,String request,String email) throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK)||Db.get().getAutoCommit())throw unavailable();
        ImportedState current=importedForMaintenance(previous.userId);
        if(!current.equals(previous))fail(409,"邮箱资料已更新，请刷新后重试");
        long user=current.userId,revision=current.revision;
        if(!current.exists)Db.exec("INSERT INTO s01_account_email_heads(user_id,import_person_code,import_binding,revision,email,email_key) VALUES(?,?,?,0,NULL,NULL)",user,current.personCode,current.binding);
        if(!Objects.equals(email,current.email)) {
            if(revision>=MAX)fail(409,"邮箱资料版本需要维护，请联系管理员");
            revision++;
            Db.exec("INSERT INTO s01_account_email_revisions(user_id,revision,email,actor_user_id,recorded_at) VALUES(?,?,?,?,?)",user,revision,email,actor,Instant.now().toString());
            try(PreparedStatement ps=Db.get().prepareStatement("UPDATE s01_account_email_heads SET revision=?,email=?,email_key=? WHERE user_id=? AND revision=? AND import_binding=?")) {
                ps.setLong(1,revision);ps.setString(2,email);ps.setString(3,key(email));ps.setLong(4,user);ps.setLong(5,current.revision);ps.setString(6,current.binding);
                if(ps.executeUpdate()!=1)fail(409,"邮箱资料已更新，请刷新后重试");
            }
        }
        Db.exec("INSERT INTO s01_account_email_requests(request_id,actor_user_id,user_id,expected_revision,email_digest,import_binding,result_revision) VALUES(?,?,?,?,?,?,?)",
                request,actor,user,current.revision,digest(email==null?"null":"email:"+email),current.binding,revision);
        return importedForMaintenance(user);
    }
    static String normalizeMaintenanceEmail(Object raw) throws Api.ApiException {return email(raw);}

    private static Map<String,Object> response(Target target,State state) throws Exception {
        boolean duplicate=NotificationChannelsAccountEmailMaintenance.duplicate(state.email,target.user);
        Map<String,Object> result=new LinkedHashMap<>();result.put("user_id",target.user);result.put("revision",state.revision);result.put("email",state.email);result.put("status",status(state.email));result.put("history",state.history);result.put("can_save",true);result.put("duplicate_email",duplicate);return result;
    }
    private static String status(String email) {return email==null?"MISSING":"PENDING_VERIFICATION";}
    private static String key(String email) {return email==null?null:email.toLowerCase(Locale.ROOT);}

    private static String email(Object raw) throws Api.ApiException {
        if(raw==null)return null;
        if(!(raw instanceof String)) {fail(400,"候选邮箱格式无效");return null;}
        String value=(String)raw;
        if(value.length()>512) fail(400,"候选邮箱格式无效");
        // Check before trimming: even a trailing CR/LF, tab or invisible character is rejected.
        for(int i=0;i<value.length();i++) if(value.charAt(i)<32||value.charAt(i)>126) fail(400,"候选邮箱格式无效");
        value=value.trim();if(value.isEmpty())return null;
        if(value.length()>254) fail(400,"候选邮箱格式无效");
        int at=value.indexOf('@');
        if(at<1||at!=value.lastIndexOf('@')||at>64) fail(400,"候选邮箱格式无效");
        String local=value.substring(0,at),domain=value.substring(at+1).toLowerCase(Locale.ROOT);
        if(!local.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+")||local.startsWith(".")||local.endsWith(".")||local.contains("..")) fail(400,"候选邮箱格式无效");
        String[] labels=domain.split("\\.",-1);
        if(labels.length<2) fail(400,"候选邮箱格式无效");
        for(String label:labels) if(label.length()>63||!label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) fail(400,"候选邮箱格式无效");
        if(labels[labels.length-1].matches("[0-9]+")) fail(400,"候选邮箱格式无效");
        return local+"@"+domain;
    }
    private static String requestId(Object raw) throws Api.ApiException {
        if(!(raw instanceof String value)||!value.matches("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")) {fail(400,"请求编号格式无效");return null;}
        return ((String)raw).toLowerCase(Locale.ROOT);
    }
    private static long integer(Object raw,long minimum) throws Api.ApiException {
        if(!(raw instanceof Number)) {fail(400,"账号或版本参数格式无效");return 0;}
        try {
            long result=new java.math.BigDecimal(raw.toString()).longValueExact();
            if(result<minimum||result>MAX)throw new ArithmeticException();return result;
        } catch(NumberFormatException|ArithmeticException e) {fail(400,"账号或版本参数格式无效");return 0;}
    }
    private static void validId(long user) throws Api.ApiException {if(user<1||user>MAX)fail(400,"账号参数格式无效");}
    private static long queryUser(String raw) throws Api.ApiException {
        if(raw==null||raw.length()>128) {fail(400,"查询只接受一个账号参数");return 0;}
        try {
            String[] parameters=raw.split("&",-1);
            if(parameters.length!=1)throw new IllegalArgumentException();
            String[] pair=parameters[0].split("=",-1);
            if(pair.length!=2||!URLDecoder.decode(pair[0],StandardCharsets.UTF_8).equals("user_id"))throw new IllegalArgumentException();
            String value=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);
            if(!value.matches("[1-9][0-9]{0,15}"))throw new IllegalArgumentException();
            long id=Long.parseLong(value);validId(id);return id;
        }catch(IllegalArgumentException e) {fail(400,"查询只接受一个有效账号参数");return 0;}
    }
    private static String digest(String value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static long number(Map<String,Object> row,String key) {Object n=row.get(key);return n instanceof Number?((Number)n).longValue():-1;}
    private static Api.ApiException unavailable() {return new Api.ApiException(503,"邮箱资料暂时无法处理，请稍后重试");}
    private static void fail(int code,String message) throws Api.ApiException {throw new Api.ApiException(code,message);}
}
