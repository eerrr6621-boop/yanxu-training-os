package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Authenticated administrator maintenance of login-email candidates; never activates or verifies. */
public final class NotificationChannelsAccountEmailMaintenance {
    private static final String BASE="/api/account-email-maintenance",PURPOSE="LOGIN_VERIFICATION";
    private static final long MAX=9007199254740991L;
    private static final Set<String> FIELDS=Set.of("user_id","expected_revision","request_id","email","confirmed_username","purpose");
    private NotificationChannelsAccountEmailMaintenance() {}

    /** Called after the original imported preparation tables exist. Creates empty tables only. */
    public static void init() throws SQLException {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit())throw new IllegalStateException("邮箱维护建表不能嵌入业务事务");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_account_email_maintenance_heads (user_id BIGINT PRIMARY KEY REFERENCES users(id),account_binding CHAR(64) NOT NULL,revision BIGINT NOT NULL CHECK(revision>=0),email VARCHAR(254),email_key VARCHAR(254))");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_account_email_maintenance_revisions (user_id BIGINT NOT NULL REFERENCES s01_account_email_maintenance_heads(user_id),revision BIGINT NOT NULL CHECK(revision>0),email VARCHAR(254),actor_user_id BIGINT NOT NULL REFERENCES users(id),recorded_at VARCHAR(40) NOT NULL,PRIMARY KEY(user_id,revision))");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_account_email_maintenance_requests (request_id VARCHAR(36) PRIMARY KEY,actor_user_id BIGINT NOT NULL REFERENCES users(id),user_id BIGINT NOT NULL REFERENCES users(id),account_kind VARCHAR(16) NOT NULL CHECK(account_kind IN ('IMPORTED','EXISTING')),confirmed_username VARCHAR(64) NOT NULL,purpose VARCHAR(32) NOT NULL CHECK(purpose='LOGIN_VERIFICATION'),expected_revision BIGINT NOT NULL CHECK(expected_revision>=0),email_digest CHAR(64) NOT NULL,confirmation_digest CHAR(64) NOT NULL,account_binding CHAR(64) NOT NULL,result_revision BIGINT NOT NULL CHECK(result_revision>=0),native_user_id BIGINT REFERENCES s01_account_email_maintenance_heads(user_id),import_request_id VARCHAR(36) REFERENCES s01_account_email_requests(request_id),CHECK((account_kind='EXISTING' AND native_user_id IS NOT NULL AND native_user_id=user_id AND import_request_id IS NULL) OR (account_kind='IMPORTED' AND native_user_id IS NULL AND import_request_id IS NOT NULL AND import_request_id=request_id)))");
            Db.exec("CREATE INDEX IF NOT EXISTS s01_account_email_maintenance_duplicates ON s01_account_email_maintenance_heads(email_key)");
        }
    }

    public static boolean matches(String path){return path!=null&&(path.equals(BASE)||path.startsWith(BASE+"/"));}
    public static void guardAccountDeletion(long user) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            try {
                validId(user);
                FirstBindStore.guardAccountDeletion(user);
                if(Db.one("SELECT user_id FROM s01_account_email_maintenance_heads WHERE user_id=?",user)!=null
                        ||Db.one("SELECT user_id FROM s01_account_email_maintenance_requests WHERE actor_user_id=? OR user_id=? LIMIT 1",user,user)!=null
                        ||Db.one("SELECT user_id FROM s01_account_email_maintenance_revisions WHERE actor_user_id=? LIMIT 1",user)!=null)
                    fail(409,"该账号已有邮箱维护记录，请停用并保留历史");
            }catch(Api.ApiException e){throw e;}catch(Exception ignored){throw unavailable();}
        }
    }

    private static void requireAdmin(Auth.Session supplied) throws Api.ApiException {
        Auth.Session current=Auth.current(supplied);
        if(current==null)fail(401,"未登录或会话已失效，请重新登录");
        if(!"admin".equals(current.role))fail(403,"仅系统管理员可维护登录核验邮箱");
    }
    public static boolean handle(HttpExchange ex,Auth.Session supplied) throws Exception {
        if(!matches(ex.getRequestURI().getPath()))return false;
        synchronized(Api.MUTATION_LOCK) {
            ex.getResponseHeaders().set("Cache-Control","no-store");
            try {
                requireAdmin(supplied);
                if(Auth.get(Api.token(ex))!=supplied)fail(401,"未登录或会话已失效，请重新登录");
                if(!BASE.equals(ex.getRequestURI().getPath()))fail(404,"邮箱维护接口不存在");
                if(ex.getRequestMethod().equals("GET"))Api.ok(ex,get(supplied,queryUser(ex.getRequestURI().getRawQuery())));
                else if(ex.getRequestMethod().equals("POST")) {
                    if(ex.getRequestURI().getRawQuery()!=null)fail(400,"保存邮箱资料不接受查询参数");
                    String type=ex.getRequestHeaders().getFirst("Content-Type");
                    if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))fail(415,"请使用JSON请求");
                    Api.ok(ex,save(supplied,Api.body(ex)));
                } else {ex.getResponseHeaders().set("Allow","GET, POST");fail(405,"请求方法不受支持");}
                return true;
            }catch(Api.ApiException e){throw e;}catch(Exception ignored){throw unavailable();}
        }
    }

    private record Target(long user,String username,String name,String kind,String binding,boolean canLogin) {}
    private record State(boolean exists,long revision,String email,List<Map<String,Object>> history) {}

    static Map<String,Object> get(Auth.Session supplied,long user) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            try {requireAdmin(supplied);validId(user);Target target=target(user);return response(target,load(target),false);}
            catch(Api.ApiException e){throw e;}catch(Exception ignored){throw unavailable();}
        }
    }

    static Map<String,Object> save(Auth.Session supplied,Map<String,Object> body) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            try {
                requireAdmin(supplied);
                if(body==null||!body.keySet().equals(FIELDS))fail(400,"维护请求字段不完整或包含不允许的字段");
                long user=integer(body.get("user_id"),1),expected=integer(body.get("expected_revision"),0),actor=supplied.uid;
                String request=requestId(body.get("request_id")),email=normalize(body.get("email"));
                if(!(body.get("confirmed_username") instanceof String)||!validUsername((String)body.get("confirmed_username"))
                        ||!PURPOSE.equals(body.get("purpose")))fail(400,"请明确核对目标账号及登录核验用途");
                String confirmed=(String)body.get("confirmed_username"),payload=emailDigest(email);
                if(!Db.get().getAutoCommit())fail(409,"邮箱维护不能嵌入其他业务事务");
                boolean[] changed={false},readyToCommit={false};Map<String,Object> result;
                try {
                    result=Db.transaction(()->{
                        requireAdmin(supplied);Target target=target(user);State before=load(target);
                        if(!confirmed.equals(target.username))fail(409,"目标账号已变化，请重新核对");
                        Map<String,Object> old=Db.one("SELECT * FROM s01_account_email_maintenance_requests WHERE request_id=?",request);
                        if(old!=null) {
                            if(number(old,"actor_user_id")!=actor||number(old,"user_id")!=user||number(old,"expected_revision")!=expected
                                    ||!confirmed.equals(old.get("confirmed_username"))||!PURPOSE.equals(old.get("purpose"))
                                    ||!target.kind.equals(old.get("account_kind"))||!target.binding.equals(old.get("account_binding"))
                                    ||!payload.equals(old.get("email_digest")))fail(409,"请求编号已用于不同操作，请刷新后重试");
                            return response(target,before,false); // Replays never revoke a newly established session.
                        }
                        if(Db.one("SELECT request_id FROM s01_account_email_requests WHERE request_id=?",request)!=null)
                            fail(409,"请求编号已用于不同操作，请刷新后重试");
                        if(expected!=before.revision)fail(409,"邮箱资料已更新，请刷新后重试");
                        if(duplicate(email,user))fail(409,"该邮箱已关联其他账号，请重新核对");
                        changed[0]=!Objects.equals(email,before.email);
                        long next=before.revision;
                        if(changed[0]) {if(next>=MAX)fail(409,"邮箱资料版本需要维护，请联系管理员");next++;}
                        if(target.kind.equals("IMPORTED")) {
                            var original=NotificationChannelsAccountEmailPreparation.importedForMaintenance(user);
                            if(original.revision()!=before.revision||!Objects.equals(original.email(),before.email))fail(409,"邮箱资料已更新，请刷新后重试");
                            NotificationChannelsAccountEmailPreparation.appendMaintainedImported(original,actor,request,email);
                        } else {
                            if(!before.exists)Db.exec("INSERT INTO s01_account_email_maintenance_heads(user_id,account_binding,revision,email,email_key) VALUES(?,?,0,NULL,NULL)",user,target.binding);
                            if(changed[0]) {
                                Db.exec("INSERT INTO s01_account_email_maintenance_revisions(user_id,revision,email,actor_user_id,recorded_at) VALUES(?,?,?,?,?)",user,next,email,actor,Instant.now().toString());
                                try(PreparedStatement ps=Db.get().prepareStatement("UPDATE s01_account_email_maintenance_heads SET revision=?,email=?,email_key=? WHERE user_id=? AND revision=? AND account_binding=?")) {
                                    ps.setLong(1,next);ps.setString(2,email);ps.setString(3,key(email));ps.setLong(4,user);ps.setLong(5,expected);ps.setString(6,target.binding);
                                    if(ps.executeUpdate()!=1)fail(409,"邮箱资料已更新，请刷新后重试");
                                }
                            }
                        }
                        Db.exec("INSERT INTO s01_account_email_maintenance_requests(request_id,actor_user_id,user_id,account_kind,confirmed_username,purpose,expected_revision,email_digest,confirmation_digest,account_binding,result_revision,native_user_id,import_request_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                request,actor,user,target.kind,confirmed,PURPOSE,expected,payload,declarationDigest(actor,user,target.kind,target.binding,expected,confirmed,request,payload,next),target.binding,next,target.kind.equals("EXISTING")?user:null,target.kind.equals("IMPORTED")?request:null);
                        // No post-write Auth.current: a required-mode administrator may be changing their own snapshot.
                        Target after=target(user);if(!after.equals(target))fail(409,"目标账号已变化，请重新核对");
                        Map<String,Object> response=response(after,load(after),changed[0]&&actor==user);
                        if(changed[0])TrustedDevices.revokeUserInTransaction(user);
                        readyToCommit[0]=true;return response;
                    });
                } catch(Exception failure) {
                    // Db.transaction can throw after commit while restoring auto-commit. Fail closed if commit outcome is uncertain.
                    if(readyToCommit[0]&&changed[0]){TrustedDevices.blockUser(user);Auth.revokeUserSessions(user);}
                    throw failure;
                }
                if(changed[0])Auth.revokeUserSessions(user);
                return result;
            }catch(Api.ApiException e){throw e;}catch(Exception ignored){throw unavailable();}
        }
    }

    private static Target target(long user) throws Exception {
        Map<String,Object> row=Db.one("SELECT username,name,status,(password IS NOT NULL AND password<>'') AS has_password FROM users WHERE id=?",user);
        if(row==null){fail(404,"账号不存在");return null;}
        String username=(String)row.get("username");long status=number(row,"status");
        if(!validUsername(username)||(status!=0&&status!=1))fail(409,"账号资料状态需要核对");
        boolean imported=Db.one("SELECT account_id FROM organization_account_import_people WHERE account_id=?",user)!=null;
        rejectDualSource(user,imported);
        String binding;
        if(imported) {
            try {binding=NotificationChannelsAccountEmailPreparation.importedForMaintenance(user).binding();}
            catch(Api.ApiException|DateTimeParseException damaged){throw historyConflict();}
        } else binding=digest(Json.write(new TreeMap<>(Map.of("kind","EXISTING","user_id",user))));
        return new Target(user,username,(String)row.get("name"),imported?"IMPORTED":"EXISTING",binding,status==1&&Boolean.TRUE.equals(row.get("has_password")));
    }

    private static State load(Target target) throws Exception {
        try {
            State state;
            if(target.kind.equals("IMPORTED")) {
                var imported=NotificationChannelsAccountEmailPreparation.importedForMaintenance(target.user);
                state=new State(imported.exists(),imported.revision(),imported.email(),imported.history());
            } else {
                Map<String,Object> head=Db.one("SELECT * FROM s01_account_email_maintenance_heads WHERE user_id=?",target.user);
                if(head==null)state=new State(false,0,null,List.of());
                else {
                    long revision=number(head,"revision");String email=(String)head.get("email");
                    if(!target.binding.equals(head.get("account_binding"))||revision<0||revision>MAX
                            ||!Objects.equals(email,normalize(email))||!Objects.equals(key(email),head.get("email_key")))throw historyConflict();
                    List<Map<String,Object>> history=new ArrayList<>();long expected=1;String last=null;
                    for(Map<String,Object> row:Db.query("SELECT revision,email,actor_user_id,recorded_at FROM s01_account_email_maintenance_revisions WHERE user_id=? ORDER BY revision",target.user)) {
                        String value=(String)row.get("email"),at=(String)row.get("recorded_at");
                        if(number(row,"revision")!=expected++||number(row,"actor_user_id")<=0||Objects.equals(last,value)||!Objects.equals(value,normalize(value)))throw historyConflict();
                        Instant.parse(at);last=value;Map<String,Object> item=new LinkedHashMap<>();
                        item.put("revision",number(row,"revision"));item.put("email",value);item.put("status",status(value));
                        item.put("actor_user_id",number(row,"actor_user_id"));item.put("recorded_at",at);history.add(Collections.unmodifiableMap(item));
                    }
                    if(expected-1!=revision||!Objects.equals(last,email))throw historyConflict();
                    state=new State(true,revision,email,List.copyOf(history));
                }
            }
            auditRequests(target,state);return state;
        }catch(Api.ApiException|DateTimeParseException damaged){throw historyConflict();}
    }

    private static void auditRequests(Target target,State state) throws Exception {
        Set<Long> changes=new HashSet<>();
        for(Map<String,Object> row:Db.query("SELECT * FROM s01_account_email_maintenance_requests WHERE user_id=?",target.user)) {
            long before=number(row,"expected_revision"),after=number(row,"result_revision");
            if(!state.exists||before<0||after<before||after>state.revision||after-before>1||number(row,"actor_user_id")<=0
                    ||!target.kind.equals(row.get("account_kind"))||!target.binding.equals(row.get("account_binding"))
                    ||!validUsername((String)row.get("confirmed_username"))||!PURPOSE.equals(row.get("purpose"))
                    ||!(row.get("request_id") instanceof String id)||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw historyConflict();
            if(!declarationDigest(number(row,"actor_user_id"),target.user,target.kind,target.binding,before,(String)row.get("confirmed_username"),(String)row.get("request_id"),(String)row.get("email_digest"),after).equals(row.get("confirmation_digest")))throw historyConflict();
            Object value=after==0?null:state.history.get((int)after-1).get("email");
            if(!emailDigest((String)value).equals(row.get("email_digest")))throw historyConflict();
            if(after>before&&(!changes.add(after)||number(state.history.get((int)after-1),"actor_user_id")!=number(row,"actor_user_id")))throw historyConflict();
            if(target.kind.equals("EXISTING")) {
                if(number(row,"native_user_id")!=target.user||row.get("import_request_id")!=null)throw historyConflict();
            } else {
                if(row.get("native_user_id")!=null||!row.get("request_id").equals(row.get("import_request_id")))throw historyConflict();
                Map<String,Object> original=Db.one("SELECT * FROM s01_account_email_requests WHERE request_id=?",row.get("request_id"));
                if(original==null||number(original,"actor_user_id")!=number(row,"actor_user_id")||number(original,"user_id")!=target.user
                        ||number(original,"expected_revision")!=before||number(original,"result_revision")!=after
                        ||!row.get("email_digest").equals(original.get("email_digest"))||!target.binding.equals(original.get("import_binding")))throw historyConflict();
            }
        }
        if(target.kind.equals("EXISTING")&&changes.size()!=state.revision)throw historyConflict();
    }

    /** Server-only identity bridge for the first-bind gate. No Session or public DTO is created. */
    record FirstBindIdentity(long userId,String username,String kind,String binding,boolean canLogin) {
        @Override public String toString(){return "FirstBindIdentity[redacted]";}
    }
    static FirstBindIdentity firstBindIdentity(long user) throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK))throw unavailable();
        validId(user);Target current=target(user);load(current);
        return new FirstBindIdentity(user,current.username,current.kind,current.binding,current.canLogin);
    }
    static FirstBindIdentity firstBindEmpty(long user) throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK))throw unavailable();
        validId(user);Target current=target(user);State before=load(current);
        if(before.revision!=0||before.email!=null||!before.history.isEmpty()
                ||Db.one("SELECT request_id FROM s01_account_email_requests WHERE user_id=? LIMIT 1",user)!=null
                ||Db.one("SELECT request_id FROM s01_account_email_maintenance_requests WHERE user_id=? LIMIT 1",user)!=null
                ||Db.one("SELECT revision FROM s01_account_email_revisions WHERE user_id=? LIMIT 1",user)!=null
                ||Db.one("SELECT revision FROM s01_account_email_maintenance_revisions WHERE user_id=? LIMIT 1",user)!=null)
            throw historyConflict();
        return new FirstBindIdentity(user,current.username,current.kind,current.binding,current.canLogin);
    }

    /** Called only by FirstBindStore after OTP consumption, within its single locked transaction. */
    static NotificationChannelsAccountEmailPreparation.Snapshot appendFirstVerifiedEmail(
            Auth.Credential credential,FirstBindStore.Eligibility eligibility,String email,String request) throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK)||Db.get().getAutoCommit())throw unavailable();
        if(!FirstBindStore.current(credential,eligibility)||email==null||!Objects.equals(email,normalize(email)))
            throw historyConflict();
        long user=credential.uid();Target current=target(user);State before=load(current);
        firstBindEmpty(user);
        request=requestId(request);
        if(duplicate(email,user)
                ||Db.one("SELECT request_id FROM s01_account_email_requests WHERE request_id=?",request)!=null
                ||isMaintenanceRequest(request))throw historyConflict();
        if(current.kind.equals("IMPORTED")) {
            var original=NotificationChannelsAccountEmailPreparation.importedForMaintenance(user);
            NotificationChannelsAccountEmailPreparation.appendMaintainedImported(original,user,request,email);
        } else {
            if(!before.exists)Db.exec("INSERT INTO s01_account_email_maintenance_heads(user_id,account_binding,revision,email,email_key) VALUES(?,?,0,NULL,NULL)",user,current.binding);
            Db.exec("INSERT INTO s01_account_email_maintenance_revisions(user_id,revision,email,actor_user_id,recorded_at) VALUES(?,1,?,?,?)",user,email,user,Instant.now().toString());
            try(PreparedStatement ps=Db.get().prepareStatement("UPDATE s01_account_email_maintenance_heads SET revision=1,email=?,email_key=? WHERE user_id=? AND revision=0 AND email IS NULL AND account_binding=?")) {
                ps.setString(1,email);ps.setString(2,key(email));ps.setLong(3,user);ps.setString(4,current.binding);
                if(ps.executeUpdate()!=1)throw historyConflict();
            }
        }
        String payload=emailDigest(email);
        Db.exec("INSERT INTO s01_account_email_maintenance_requests(request_id,actor_user_id,user_id,account_kind,confirmed_username,purpose,expected_revision,email_digest,confirmation_digest,account_binding,result_revision,native_user_id,import_request_id) VALUES(?,?,?,?,?,?,0,?,?,?,1,?,?)",
                request,user,user,current.kind,current.username,PURPOSE,payload,
                declarationDigest(user,user,current.kind,current.binding,0,current.username,request,payload,1),current.binding,
                current.kind.equals("EXISTING")?user:null,current.kind.equals("IMPORTED")?request:null);
        if(!current.equals(target(user))||!Auth.credentialCurrent(credential))throw historyConflict();
        return loginSnapshot(user);
    }

    static NotificationChannelsAccountEmailPreparation.Snapshot loginSnapshot(long user) throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            try {
                validId(user);Target target=target(user);State state=load(target);
                if(!target.canLogin||!state.exists||state.revision<=0||state.email==null||duplicate(state.email,user))throw historyConflict();
                // The existing Snapshot record remains compatible with Auth and the challenge engine.
                return new NotificationChannelsAccountEmailPreparation.Snapshot(user,state.revision,state.email,
                        target.kind.equals("IMPORTED")?target.binding:"existing:"+target.binding);
            }catch(Api.ApiException|DateTimeParseException damaged){throw new Api.ApiException(409,"个人邮箱资料尚不能用于登录核验，请联系管理员核对");}
            catch(Exception ignored){throw unavailable();}
        }
    }

    static void rejectDualSource(long user,boolean imported) throws Exception {
        String table=imported?"s01_account_email_maintenance_heads":"s01_account_email_heads";
        if(Db.one("SELECT user_id FROM "+table+" WHERE user_id=?",user)!=null)throw historyConflict();
    }
    static boolean duplicate(String email,long user) throws Exception {
        return email!=null&&(Db.one("SELECT user_id FROM s01_account_email_heads WHERE email_key=? AND user_id<>? LIMIT 1",key(email),user)!=null
                ||Db.one("SELECT user_id FROM s01_account_email_maintenance_heads WHERE email_key=? AND user_id<>? LIMIT 1",key(email),user)!=null);
    }
    static void rejectExistingDuplicate(String email,long user) throws Exception {
        if(email!=null&&Db.one("SELECT user_id FROM s01_account_email_maintenance_heads WHERE email_key=? AND user_id<>? LIMIT 1",key(email),user)!=null)
            fail(409,"该邮箱已关联其他账号，请重新核对");
    }
    static boolean isMaintenanceRequest(String request) throws Exception {return Db.one("SELECT request_id FROM s01_account_email_maintenance_requests WHERE request_id=?",request)!=null;}

    private static Map<String,Object> response(Target target,State state,boolean reauthenticate) throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();result.put("user_id",target.user);result.put("username",target.username);result.put("name",target.name);
        result.put("account_kind",target.kind);result.put("revision",state.revision);result.put("email",state.email);result.put("status",status(state.email));
        result.put("history",state.history);result.put("can_save",true);result.put("duplicate_email",duplicate(state.email,target.user));result.put("reauthentication_required",reauthenticate);return result;
    }
    private static String status(String email){return email==null?"MISSING":"PENDING_VERIFICATION";}
    private static String normalize(Object email) throws Api.ApiException {return NotificationChannelsAccountEmailPreparation.normalizeMaintenanceEmail(email);}
    private static String key(String email){return email==null?null:email.toLowerCase(Locale.ROOT);}
    private static String declarationDigest(long actor,long user,String kind,String binding,long expected,String username,String request,String emailDigest,long result) throws Exception {
        return digest(Json.write(new TreeMap<>(Map.of("actor",actor,"user",user,"kind",kind,"binding",binding,"expected",expected,"username",username,"request",request,"email_digest",emailDigest,"purpose",PURPOSE,"result",result))));
    }
    private static String emailDigest(String email) throws Exception{return digest(email==null?"null":"email:"+email);}
    private static String digest(String value) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static boolean validUsername(String value){return value!=null&&!value.isEmpty()&&value.length()<=64&&value.chars().noneMatch(c->Character.isISOControl(c));}
    private static long number(Map<String,Object> row,String key){Object n=row.get(key);return n instanceof Number?((Number)n).longValue():-1;}
    private static void validId(long user) throws Api.ApiException{if(user<1||user>MAX)fail(400,"账号参数格式无效");}
    private static long integer(Object value,long minimum) throws Api.ApiException {
        if(!(value instanceof Number)){fail(400,"账号或版本参数格式无效");return 0;}
        try{long result=new java.math.BigDecimal(value.toString()).longValueExact();if(result<minimum||result>MAX)throw new ArithmeticException();return result;}
        catch(NumberFormatException|ArithmeticException ignored){fail(400,"账号或版本参数格式无效");return 0;}
    }
    private static String requestId(Object raw) throws Api.ApiException {
        if(!(raw instanceof String value)||!value.matches("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")){fail(400,"请求编号格式无效");return null;}
        return ((String)raw).toLowerCase(Locale.ROOT);
    }
    private static long queryUser(String raw) throws Api.ApiException {
        try {
            if(raw==null||raw.length()>128)throw new IllegalArgumentException();String[] pairs=raw.split("&",-1),pair=raw.split("=",-1);
            if(pairs.length!=1||pair.length!=2||!URLDecoder.decode(pair[0],StandardCharsets.UTF_8).equals("user_id"))throw new IllegalArgumentException();
            String value=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);if(!value.matches("[1-9][0-9]{0,15}"))throw new IllegalArgumentException();
            long user=Long.parseLong(value);validId(user);return user;
        }catch(IllegalArgumentException ignored){fail(400,"查询只接受一个有效账号参数");return 0;}
    }
    private static Api.ApiException historyConflict(){return new Api.ApiException(409,"邮箱资料历史或账号关联需要核对，请联系管理员");}
    private static Api.ApiException unavailable(){return new Api.ApiException(503,"邮箱维护暂时不可用，请稍后重试");}
    private static void fail(int code,String message) throws Api.ApiException{throw new Api.ApiException(code,message);}

    public static Map<String,Object> parseBody(String raw) throws Api.ApiException {
        try{return new Body(raw).parse();}catch(Exception ignored){throw new Api.ApiException(400,"邮箱维护请求不是有效的JSON对象");}
    }
    private static final class Body {
        private final String value;private int at;
        Body(String value){if(value==null||value.length()>1024*1024)throw new IllegalArgumentException();this.value=value;}
        Map<String,Object> parse(){
            Map<String,Object> result=new LinkedHashMap<>();space();take('{');space();
            if(peek('}')){at++;space();end();return result;}
            while(true){
                String name=quoted();if(!FIELDS.contains(name)||result.containsKey(name))throw new IllegalArgumentException();space();take(':');space();Object content;
                if(peek('"'))content=quoted();else if(value.startsWith("null",at)){at+=4;content=null;}
                else {int start=at;while(at<value.length()&&"-+0123456789.eE".indexOf(value.charAt(at))>=0)at++;String numeric=value.substring(start,at);
                    if(numeric.length()>64||!numeric.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,4})?"))throw new IllegalArgumentException();content=new java.math.BigDecimal(numeric);}
                result.put(name,content);space();if(peek('}')){at++;space();end();return result;}take(',');space();
            }
        }
        String quoted(){int start=at;take('"');while(at<value.length()){char c=value.charAt(at++);if(c=='"')return (String)Json.parse(value.substring(start,at));if(c<32)throw new IllegalArgumentException();
                if(c=='\\'){if(at>=value.length())throw new IllegalArgumentException();char escape=value.charAt(at++);if(escape=='u'){for(int i=0;i<4;i++)if(at>=value.length()||"0123456789abcdefABCDEF".indexOf(value.charAt(at++))<0)throw new IllegalArgumentException();}else if("\"\\/bfnrt".indexOf(escape)<0)throw new IllegalArgumentException();}}
            throw new IllegalArgumentException();}
        boolean peek(char c){return at<value.length()&&value.charAt(at)==c;}
        void take(char c){if(!peek(c))throw new IllegalArgumentException();at++;}
        void space(){while(at<value.length()&&" \t\r\n".indexOf(value.charAt(at))>=0)at++;}
        void end(){if(at!=value.length())throw new IllegalArgumentException();}
    }
}
