package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Administrator-only diagnostic of the existing organization gate, never an impersonation or grant. */
public final class OrganizationAccountAccessInspection {
    private static final String PATH="/api/organization/account-access";
    private static final long MAX_ID=9007199254740991L;
    private record Operation(String resource, Action action, String label) {}
    private static final List<Operation> OPERATIONS=List.of(
        new Operation("demand.read",Action.VIEW,"查看需求"), new Operation("demand.write",Action.HANDLE,"填写需求"),
        new Operation("approval.review",Action.HANDLE,"审批审核"), new Operation("demand.accept",Action.HANDLE,"承接需求"),
        new Operation("bid.result",Action.HANDLE,"登记投标结果"), new Operation("catalog.read",Action.VIEW,"查看课程目录"),
        new Operation("catalog.manage",Action.HANDLE,"维护课程目录"), new Operation("delivery.read",Action.VIEW,"查看授课"),
        new Operation("delivery.write",Action.HANDLE,"填写授课"), new Operation("delivery.verify",Action.HANDLE,"核对授课"),
        new Operation("reports.read",Action.VIEW,"查看报表"), new Operation("reports.export",Action.EXPORT,"导出报表"),
        new Operation("survey.preview",Action.HANDLE,"预览评价统计"), new Operation("summary.read",Action.VIEW,"查看总结"),
        new Operation("summary.edit",Action.HANDLE,"填写总结"));
    private OrganizationAccountAccessInspection() {}

    public static boolean matches(String path) {return path!=null&&(path.equals(PATH)||path.startsWith(PATH+"/"));}

    /** Root calls before any request-body read. Unknown child routes authenticate before returning 404. */
    public static void preflight(HttpExchange exchange)throws Api.ApiException {
        if(!matches(exchange.getRequestURI().getPath()))return;
        synchronized(Api.MUTATION_LOCK) {
            noStore(exchange);
            requireAdmin(Auth.get(Api.token(exchange)));
            routeAndMethod(exchange);
        }
    }

    public static boolean handle(HttpExchange exchange,Auth.Session supplied)throws Exception {
        if(!matches(exchange.getRequestURI().getPath()))return false;
        synchronized(Api.MUTATION_LOCK) {
            noStore(exchange);
            Auth.Session actual=requireAdmin(Auth.get(Api.token(exchange)));
            if(actual!=supplied)throw error(401,"登录会话无效或已失效");
            routeAndMethod(exchange);
            Map<String,String> query=parseQuery(exchange.getRequestURI().getRawQuery());
            long target=parseId(query.get("account_id"));
            Api.ok(exchange,inspect(actual,target,query.get("organization_code")));
            return true;
        }
    }

    /** Trusted package helper: the target ID is a diagnostic subject, never an authenticated Session. */
    static Map<String,Object> inspect(Auth.Session actor,long accountId,String organizationCode)throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            Auth.Session current=requireAdmin(actor);
            if(accountId<=0||accountId>MAX_ID)throw error(400,"账号编号格式不正确");
            if(organizationCode!=null&&!validCode(organizationCode))throw error(400,"机构编码格式不正确");
            try {
                Map<String,Object> account=Db.one("SELECT id,username,name,status FROM users WHERE id=?",accountId);
                if(account==null)throw error(404,"账号不存在");
                Object status=account.get("status");
                if(!(status instanceof Number n)||!(n.doubleValue()==0||n.doubleValue()==1)
                        || !(account.get("username") instanceof String username)||username.isBlank()
                        || (account.get("name")!=null&&!(account.get("name") instanceof String)))throw unavailable();
                boolean accountEnabled=((Number)status).intValue()==1;
                Configuration config;
                try {config=OrganizationAccessStore.configuration();}
                catch(Exception invalidConfiguration){throw unavailable();}
                // A history without a current head cannot be mistaken for an unpublished empty store.
                if(config==null&&Db.count("organization_access_config")!=0)throw unavailable();
                Map<String,Organization> organizations=new LinkedHashMap<>();
                if(config!=null)for(Organization org:config.organizations())organizations.put(org.organizationCode(),org);
                if(organizationCode!=null&&!organizations.containsKey(organizationCode))throw error(400,"所选机构不在当前正式配置中");
                AccountBinding binding=config==null?null:config.accountBindings().stream().filter(b->b.accountId()==accountId).findFirst().orElse(null);
                Person person=binding==null?null:config.people().stream().filter(p->p.personCode().equals(binding.personCode())).findFirst().orElse(null);
                if(binding!=null&&person==null)throw unavailable();
                Map<String,Boolean> effective=new HashMap<>();
                List<Map<String,Object>> options=new ArrayList<>();
                for(Organization org:organizations.values())options.add(map("organization_code",org.organizationCode(),"enabled",organizationEnabled(org.organizationCode(),organizations,effective)));
                Map<String,Object> subject=map("account_id",accountId,"username",account.get("username"),"name",account.get("name"),
                    "account_enabled",accountEnabled,"binding_status",config==null?"CONFIG_NOT_PUBLISHED":binding==null?"UNBOUND":binding.enabled()?"BOUND_ENABLED":"BOUND_DISABLED",
                    "person_code",person==null?null:person.personCode(),"organization_code",person==null?null:person.organizationCode(),
                    "person_enabled",person==null?null:person.enabled(),"home_organization_enabled",person==null?null:organizationEnabled(person.organizationCode(),organizations,effective),
                    "role_codes",person==null?List.of():person.roleCodes().stream().sorted().toList());
                List<Map<String,Object>> decisions=new ArrayList<>();
                if(organizationCode!=null) {
                    Engine engine=new Engine(config);
                    if(!engine.validation().valid())throw unavailable();
                    for(Operation op:OPERATIONS) {
                        if(!accountEnabled)decisions.add(map("resource",op.resource(),"action",op.action().name(),"label",op.label(),"allowed",false,
                            "status","ACCOUNT_DISABLED","reason","账号已停用","matched_rule_ids",List.of()));
                        else {
                            // Only the server-verified target ID enters this diagnostic verifier. No Auth Session/token is created.
                            Decision decision=engine.authorize("server-admin-account-inspection",ignored->OptionalLong.of(accountId),new Resource(op.resource(),organizationCode),op.action());
                            decisions.add(map("resource",op.resource(),"action",op.action().name(),"label",op.label(),"allowed",decision.allowed(),
                                "status",decision.status().name(),"reason",decision.reason(),"matched_rule_ids",decision.matchedRuleIds()));
                        }
                    }
                }
                requireAdmin(current);
                return map("read_only",true,"organization_gate_only",true,"configuration_status",config==null?"NOT_CONFIGURED":"CONFIGURED",
                    "configuration_version",config==null?null:config.version(),"subject",subject,"organizations",options,"selected_organization",organizationCode,"decisions",decisions);
            } catch(Api.ApiException known) {throw known;}
            catch(Exception invalid) {throw unavailable();}
        }
    }

    private static Auth.Session requireAdmin(Auth.Session supplied)throws Api.ApiException {
        Auth.Session actor=Auth.current(supplied);
        if(actor==null)throw error(401,"未登录或会话已过期，请重新登录");
        if(!Auth.isAdmin(actor))throw error(403,"仅系统管理员可查看业务权限");
        return actor;
    }
    private static void routeAndMethod(HttpExchange exchange)throws Api.ApiException {
        if(!PATH.equals(exchange.getRequestURI().getPath()))throw error(404,"接口不存在");
        if(!Set.of("GET","HEAD").contains(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow","GET, HEAD");throw error(405,"本接口只支持查看");
        }
    }
    private static void noStore(HttpExchange exchange){exchange.getResponseHeaders().set("Cache-Control","no-store");}
    private static Map<String,String> parseQuery(String raw)throws Api.ApiException {
        if(raw==null||raw.isEmpty()||raw.length()>4096)throw error(400,"查询参数格式不正确");
        Map<String,String> result=new LinkedHashMap<>();
        for(String pair:raw.split("&",-1)) {
            int equals=pair.indexOf('=');if(equals<=0)throw error(400,"查询参数格式不正确");
            String key=decode(pair.substring(0,equals)),value=decode(pair.substring(equals+1));
            if(!Set.of("account_id","organization_code").contains(key)||result.putIfAbsent(key,value)!=null)throw error(400,"查询参数重复或不受支持");
        }
        if(!result.containsKey("account_id"))throw error(400,"缺少账号编号");
        return result;
    }
    private static long parseId(String value)throws Api.ApiException {
        if(value==null||!value.matches("[1-9][0-9]{0,15}"))throw error(400,"账号编号格式不正确");
        try {long result=Long.parseLong(value);if(result>MAX_ID)throw new NumberFormatException();return result;}
        catch(NumberFormatException invalid){throw error(400,"账号编号格式不正确");}
    }
    private static String decode(String raw)throws Api.ApiException {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            for(int i=0;i<raw.length();) {
                char c=raw.charAt(i);
                if(c=='%') {
                    if(i+2>=raw.length())throw new IllegalArgumentException();
                    int high=Character.digit(raw.charAt(i+1),16),low=Character.digit(raw.charAt(i+2),16);
                    if(high<0||low<0)throw new IllegalArgumentException();bytes.write((high<<4)|low);i+=3;
                } else if(c=='+') {bytes.write(' ');i++;}
                else {
                    int point=raw.codePointAt(i);if(point>=0xD800&&point<=0xDFFF)throw new IllegalArgumentException();
                    bytes.write(new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8));i+=Character.charCount(point);
                }
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch(Exception invalid){throw error(400,"查询参数编码不正确");}
    }
    private static boolean validCode(String value){return !value.isBlank()&&value.length()<=128&&value.equals(value.strip())&&value.chars().noneMatch(c->c<32||c==127);}
    /** Display-only ancestor flag; operation decisions always use the unmodified Engine. */
    private static boolean organizationEnabled(String code,Map<String,Organization> organizations,Map<String,Boolean> memo) {
        if(memo.containsKey(code))return memo.get(code);
        List<String> path=new ArrayList<>();Set<String> seen=new HashSet<>();String cursor=code;boolean enabled=true;
        while(cursor!=null&&!cursor.isEmpty()) {
            if(memo.containsKey(cursor)){enabled=memo.get(cursor);break;}
            if(!seen.add(cursor)){enabled=false;break;}
            Organization org=organizations.get(cursor);path.add(cursor);
            if(org==null||!org.enabled()){enabled=false;break;}cursor=org.parentOrganizationCode();
        }
        for(String visited:path)memo.put(visited,enabled);return enabled;
    }
    private static Api.ApiException unavailable(){return error(503,"暂时无法核实账号业务权限，请稍后重试");}
    private static Api.ApiException error(int code,String message){return new Api.ApiException(code,message);}
    private static Map<String,Object> map(Object...values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
}
