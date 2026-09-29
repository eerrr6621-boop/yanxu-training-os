package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Administrator maintenance of two references in an existing, complete organization configuration. */
public final class OrganizationAccountRelationships {
    private static final String PATH="/api/organization/account-relationships";
    private static final long MAX_ID=9007199254740991L, TTL=300_000L;
    private static final int CAPACITY=128;
    // All access uses the same lock as Auth, configuration publication and business mutations.
    private static final Map<String,Review> REVIEWS=new LinkedHashMap<>();
    private static final List<String> WARNINGS=List.of(
        "保存后，新流程使用新的负责人和 BP；历史记录及已有待办的办理人不会自动改派。",
        "已有普通顺序流程保留原办理人；已有兼任审批流程会重新核对当前关系和证据，关系变化可能使其暂停。");
    private OrganizationAccountRelationships() {}
    private record Route(long accountId,String operation) {}
    private static final class Review {
        final Auth.Session actor;
        final long accountId;
        final String version,configHash,factsHash,leader,bp;
        long expires;
        Review(Auth.Session actor,long id,State state,String facts,String leader,String bp) throws Exception {
            this.actor=actor;accountId=id;version=state.config.version();configHash=hash(OrganizationAccessStore.toMap(state.config));
            factsHash=facts;this.leader=leader;this.bp=bp;expires=System.currentTimeMillis()+TTL;
        }
    }
    private static final class State {
        final Configuration config;
        final Engine engine;
        final Person subject;
        final AccountBinding binding;
        final Map<String,Object> account;
        final Map<String,Person> people=new LinkedHashMap<>();
        final Map<String,Organization> organizations=new HashMap<>();
        final Map<String,RoleRelations> relations=new HashMap<>();
        final Map<String,AccountBinding> bindings=new HashMap<>();
        final Map<Long,Map<String,Object>> users=new HashMap<>();
        State(Configuration config,Map<String,Object> account,long id) throws Exception {
            this.config=config;this.account=account;engine=new Engine(config);
            for(Person p:config.people())people.put(p.personCode(),p);
            for(Organization o:config.organizations())organizations.put(o.organizationCode(),o);
            for(RoleRelations r:config.relations())relations.put(r.roleCode(),r);
            AccountBinding found=null;
            for(AccountBinding b:config.accountBindings()) {
                bindings.put(b.personCode(),b);
                if(b.accountId()==id)found=b;
                Map<String,Object> user=Db.one("SELECT id,username,name,role,status,password FROM users WHERE id=?",b.accountId());
                if(user!=null)users.put(b.accountId(),user);
            }
            binding=found;
            if(binding==null)throw error(409,"该账号尚未绑定正式人员，请先完成正式组织配置");
            subject=people.get(binding.personCode());
            if(subject==null||!engine.validation().valid())throw unavailable();
        }
    }

    public static boolean matches(String path) {return path!=null&&(path.equals(PATH)||path.startsWith(PATH+"/"));}
    public static void preflight(HttpExchange ex)throws Api.ApiException {
        if(!matches(ex.getRequestURI().getPath()))return;
        synchronized(Api.MUTATION_LOCK) {
            noStore(ex);requireAdmin(Auth.get(Api.token(ex)));route(ex);
        }
    }
    public static boolean handle(HttpExchange ex,Auth.Session supplied)throws Exception {
        if(!matches(ex.getRequestURI().getPath()))return false;
        synchronized(Api.MUTATION_LOCK) {
            noStore(ex);
            Auth.Session actual=requireAdmin(Auth.get(Api.token(ex)));
            if(actual!=supplied)throw error(401,"登录会话无效或已失效");
            Route route=route(ex);
            Map<String,Object> result=switch(route.operation()) {
                case "preview" -> preview(actual,route.accountId(),Api.body(ex));
                case "confirm" -> confirm(actual,route.accountId(),Api.body(ex));
                default -> editor(actual,route.accountId());
            };
            // Store.publish may have revoked this actor's own session after a successful commit.
            if(Boolean.TRUE.equals(result.get("sessionInvalidated")))Api.clearSessionCookie(ex);
            Api.ok(ex,result);return true;
        }
    }
    static Map<String,Object> editor(Auth.Session actor,long id)throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            requireAdmin(actor);validId(id);
            try {
                State s=state(id);
                List<Map<String,Object>> leaders=new ArrayList<>(),bps=new ArrayList<>();
                List<String> combined=new ArrayList<>();
                for(Person p:s.config.people().stream().sorted(Comparator.comparing(Person::personCode)).toList()) {
                    boolean leader=eligible(s,p.personCode(),true),bp=eligible(s,p.personCode(),false);
                    if(leader)leaders.add(relation(s,p.personCode(),true));
                    if(bp)bps.add(relation(s,p.personCode(),false));
                    if(leader&&bp&&OrganizationAccessStore.combinedAssignment(s.subject.organizationCode(),p.personCode())!=null)combined.add(p.personCode());
                }
                requireAdmin(actor);
                return map("configuration_version",s.config.version(),"subject",subject(s),"current",pair(s,s.subject.leaderPersonCode(),s.subject.bpPersonCode()),
                    "leader_required",required(s,true),"bp_required",required(s,false),"leader_options",leaders,"bp_options",bps,
                    "combined_person_codes",combined,"warnings",warnings(s));
            }catch(Api.ApiException known){throw known;}catch(Exception failure){throw unavailable();}
        }
    }
    static Map<String,Object> preview(Auth.Session actor,long id,Map<String,Object> body)throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            requireAdmin(actor);validId(id);fields(body,Set.of("expected_version","leader_person_code","bp_person_code"));
            String expected=text(body,"expected_version",false),leader=text(body,"leader_person_code",true),bp=text(body,"bp_person_code",true);
            try {
                State s=state(id);
                if(!s.config.version().equals(expected))throw stale();
                validateSelection(s,leader,bp);
                boolean changed=changed(s,leader,bp);
                String facts=facts(actor,s,leader,bp);
                requireAdmin(actor);sweep();
                if(REVIEWS.size()>=CAPACITY)throw error(409,"待确认的核对记录较多，请稍后重新预览");
                String token;do{token=Auth.randomToken(32);}while(REVIEWS.containsKey(token));
                Review review=new Review(actor,id,s,facts,leader,bp);REVIEWS.put(token,review);
                return map("configuration_version",s.config.version(),"subject",subject(s),"before",pair(s,s.subject.leaderPersonCode(),s.subject.bpPersonCode()),
                    "after",pair(s,leader,bp),"changed",changed,"review_token",token,"expires_at",Instant.ofEpochMilli(review.expires).toString(),
                    "warnings",warnings(s),"workflow_impact",map("historical_assignments_changed",false,"pending_tasks_reassigned",false,"combined_workflows_may_pause",changed));
            }catch(Api.ApiException known){throw known;}catch(Exception failure){throw unavailable();}
        }
    }
    static Map<String,Object> confirm(Auth.Session actor,long id,Map<String,Object> body)throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK) {
            requireAdmin(actor);validId(id);fields(body,Set.of("review_token"));
            String token=text(body,"review_token",false);
            if(!token.matches("[a-f0-9]{64}"))throw error(400,"核对标识格式不正确");
            sweep();Review review=REVIEWS.get(token);
            if(review==null||review.actor!=actor||review.accountId!=id)throw stale();
            try {
                State s=state(id);
                if(!review.version.equals(s.config.version())||!review.configHash.equals(hash(OrganizationAccessStore.toMap(s.config)))
                    ||!review.factsHash.equals(facts(actor,s,review.leader,review.bp)))throw stale();
                validateSelection(s,review.leader,review.bp);
                boolean changed=changed(s,review.leader,review.bp);
                requireAdmin(actor);
                Configuration result=s.config;
                if(changed) {
                    Configuration next=replace(s,"relationships-"+UUID.randomUUID(),review.leader,review.bp);
                    // Single existing transactional CAS; never wrap this in a separate Db.transaction.
                    result=OrganizationAccessStore.publish(actor,s.config.version(),next);
                }
                REVIEWS.remove(token);
                boolean invalidated=Auth.current(actor)==null;
                return map("saved",changed,"unchanged",!changed,"configuration_version",result.version(),"account_id",id,
                    "person_code",s.subject.personCode(),"leader_person_code",review.leader,"bp_person_code",review.bp,"sessionInvalidated",invalidated);
            }catch(Api.ApiException known){REVIEWS.remove(token);throw known;}
            catch(Exception failure){REVIEWS.remove(token);throw unavailable();}
        }
    }

    private static State state(long id)throws Exception {
        Map<String,Object> user=Db.one("SELECT id,username,name,role,status,password FROM users WHERE id=?",id);
        if(user==null)throw error(404,"账号不存在");
        if(!(user.get("username") instanceof String username)||username.isBlank()||!(user.get("status") instanceof Number status)
            ||!(status.doubleValue()==0||status.doubleValue()==1)||(user.get("name")!=null&&!(user.get("name") instanceof String)))throw unavailable();
        Configuration config;
        try{config=OrganizationAccessStore.configuration();}catch(Exception invalid){throw unavailable();}
        if(config==null) {
            if(Db.count("organization_access_config")!=0)throw unavailable();
            throw error(409,"尚未发布正式组织配置，暂不能维护人员关系");
        }
        return new State(config,user,id);
    }
    private static boolean eligible(State s,String code,boolean leader) {
        if(code==null)return false;
        Person p=s.people.get(code);AccountBinding binding=s.bindings.get(code);
        if(p==null||!p.enabled()||!active(s,p.organizationCode())||binding==null||!binding.enabled()||!enabled(s.users.get(binding.accountId())))return false;
        for(String role:s.subject.roleCodes()) {
            RoleRelations relations=s.relations.get(role);
            RelationRule rule=relations==null?null:leader?relations.leader():relations.bp();
            if(rule==null||(!rule.allowSelf()&&s.subject.personCode().equals(code)))return false;
            boolean matches=rule.allowedTargetRoles().stream().filter(p.roleCodes()::contains)
                .anyMatch(targetRole->!rule.targetMustCoverOrganization()||responsibleOrganizations(p,targetRole).contains(s.subject.organizationCode()));
            if(!matches)return false;
        }
        if(!s.engine.authorize("server-relationship-candidate",ignored->OptionalLong.of(binding.accountId()),
            new Resource("approval.review",s.subject.organizationCode()),Action.HANDLE).allowed())return false;
        Configuration candidate=replace(s,s.config.version(),leader?code:s.subject.leaderPersonCode(),leader?s.subject.bpPersonCode():code);
        return OrganizationAccess.validate(candidate).valid();
    }
    private static void validateSelection(State s,String leader,String bp)throws Exception {
        // Mirror Store.publish's global account prerequisites before issuing a review token.
        // A disabled subject is editable when its binding is also disabled; this never activates it.
        for(AccountBinding binding:s.config.accountBindings()) {
            Map<String,Object> account=s.users.get(binding.accountId());
            if(account==null||(binding.enabled()&&!enabled(account)))
                throw error(409,"当前正式配置存在缺失或停用的绑定账号，请先核对绑定状态后重新预览");
        }
        if((leader==null&&required(s,true))||(bp==null&&required(s,false)))throw error(400,"当前岗位要求填写负责人和 BP，请完成必填关系");
        if((leader!=null&&!eligible(s,leader,true))||(bp!=null&&!eligible(s,bp,false)))throw error(400,"所选人员不符合当前岗位、负责范围或审批权限要求");
        if(leader!=null&&leader.equals(bp)&&OrganizationAccessStore.combinedAssignment(s.subject.organizationCode(),leader)==null)
            throw error(400,"负责人和 BP 为同一人时，必须已有该机构的有效兼任证据");
        if(!OrganizationAccess.validate(replace(s,s.config.version(),leader,bp)).valid())throw error(400,"所选关系不符合完整组织配置要求，或形成负责人循环");
    }
    private static Configuration replace(State s,String version,String leader,String bp) {
        Person p=s.subject;
        Person replacement=new Person(p.personCode(),p.organizationCode(),p.responsibleOrganizationCodes(),leader,bp,p.roleCodes(),p.enabled(),p.responsibleOrganizationsByRole());
        List<Person> people=s.config.people().stream().map(person->person.personCode().equals(p.personCode())?replacement:person).toList();
        Configuration c=s.config;
        return new Configuration(version,c.codeRules(),c.roleCodes(),c.organizations(),people,c.relations(),c.accountBindings(),c.grants(),c.combinedApprovals());
    }
    private static boolean required(State s,boolean leader) {return s.subject.roleCodes().stream().anyMatch(role->{RoleRelations r=s.relations.get(role);return leader?r.leader().required():r.bp().required();});}
    private static boolean changed(State s,String leader,String bp){return !Objects.equals(s.subject.leaderPersonCode(),leader)||!Objects.equals(s.subject.bpPersonCode(),bp);}
    private static Map<String,Object> subject(State s) {return map("account_id",s.binding.accountId(),"username",s.account.get("username"),"name",s.account.get("name"),
        "account_enabled",enabled(s.account),"binding_enabled",s.binding.enabled(),"person_code",s.subject.personCode(),"organization_code",s.subject.organizationCode(),
        "person_enabled",s.subject.enabled(),"role_codes",s.subject.roleCodes().stream().sorted().toList());}
    private static Map<String,Object> relation(State s,String code,boolean leader) {
        if(code==null)return null;
        Person person=s.people.get(code);AccountBinding binding=s.bindings.get(code);
        Map<String,Object> user=binding==null?null:s.users.get(binding.accountId());
        return map("person_code",code,"account_id",user==null?null:binding.accountId(),"username",user==null?null:user.get("username"),"name",user==null?null:user.get("name"),
            "role_codes",person==null?List.of():person.roleCodes().stream().sorted().toList(),"eligible",eligible(s,code,leader));
    }
    private static Map<String,Object> pair(State s,String leader,String bp){return map("leader",relation(s,leader,true),"bp",relation(s,bp,false));}
    private static List<String> warnings(State s) {
        List<String> values=new ArrayList<>(WARNINGS);
        if((s.subject.leaderPersonCode()!=null&&!eligible(s,s.subject.leaderPersonCode(),true))||(s.subject.bpPersonCode()!=null&&!eligible(s,s.subject.bpPersonCode(),false)))
            values.add("当前关系中存在已失效的人员，请重新选择符合要求的负责人或 BP。");
        if(!enabled(s.account)||!s.binding.enabled()||!s.subject.enabled()||!active(s,s.subject.organizationCode()))values.add("当前账号、人员、绑定或所属机构存在停用状态；保存关系不会将其启用。");
        return values;
    }
    private static boolean enabled(Map<String,Object> user){return user!=null&&user.get("status") instanceof Number number&&number.doubleValue()==1;}
    private static boolean active(State s,String code) {
        Set<String> visited=new HashSet<>();
        while(code!=null&&!code.isEmpty()) {Organization org=s.organizations.get(code);if(org==null||!org.enabled()||!visited.add(code))return false;code=org.parentOrganizationCode();}
        return true;
    }
    private static String facts(Auth.Session actor,State s,String leader,String bp)throws Exception {
        Map<String,Object> actorUser=Db.one("SELECT id,username,name,role,status,password FROM users WHERE id=?",actor.uid);
        if(actorUser==null)throw error(401,"登录会话无效或已失效");
        Map<String,Object> related=new TreeMap<>();related.put(s.subject.personCode(),personFacts(s,s.subject.personCode()));
        if(leader!=null)related.put(leader,personFacts(s,leader));if(bp!=null)related.put(bp,personFacts(s,bp));
        return hash(map("actor",actorUser,"related",related));
    }
    private static Map<String,Object> personFacts(State s,String code) {
        AccountBinding b=s.bindings.get(code);
        return map("binding",b==null?null:map("accountId",b.accountId(),"personCode",b.personCode(),"enabled",b.enabled()),"user",b==null?null:s.users.get(b.accountId()));
    }
    private static String hash(Object value)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(canonical(value)).getBytes(StandardCharsets.UTF_8)));}
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> source) {Map<String,Object> result=new TreeMap<>();source.forEach((k,v)->result.put((String)k,canonical(v)));return result;}
        if(value instanceof Set<?> source)return source.stream().map(OrganizationAccountRelationships::canonical).sorted(Comparator.comparing(Json::write)).toList();
        if(value instanceof Collection<?> source)return source.stream().map(OrganizationAccountRelationships::canonical).toList();
        return value;
    }
    private static void sweep(){long now=System.currentTimeMillis();REVIEWS.entrySet().removeIf(entry->entry.getValue().expires<=now);}
    private static Auth.Session requireAdmin(Auth.Session supplied)throws Api.ApiException {
        Auth.Session actor=Auth.current(supplied);
        if(actor==null)throw error(401,"未登录或会话已过期，请重新登录");
        if(!Auth.isAdmin(actor))throw error(403,"仅系统管理员可维护人员关系");return actor;
    }
    private static Route route(HttpExchange ex)throws Api.ApiException {
        String path=ex.getRequestURI().getPath();
        String tail=path.startsWith(PATH+"/")?path.substring(PATH.length()+1):"";
        String[] parts=tail.split("/",-1);
        if(parts.length<1||parts.length>2||(parts.length==2&&!Set.of("preview","confirm").contains(parts[1])))throw error(404,"接口不存在");
        if(parts[0].isEmpty())throw error(404,"接口不存在");
        if(!parts[0].matches("[1-9][0-9]{0,15}"))throw error(400,"账号编号格式不正确");
        long id;try{id=Long.parseLong(parts[0]);}catch(NumberFormatException invalid){throw error(400,"账号编号格式不正确");}validId(id);
        if(!path.equals(ex.getRequestURI().getRawPath()))throw error(400,"接口路径格式不正确");
        String operation=parts.length==2?parts[1]:"editor";
        Set<String> methods=operation.equals("editor")?Set.of("GET","HEAD"):Set.of("POST");
        if(!methods.contains(ex.getRequestMethod())) {ex.getResponseHeaders().set("Allow",operation.equals("editor")?"GET, HEAD":"POST");throw error(405,"请求方法不受支持");}
        if(ex.getRequestURI().getRawQuery()!=null)throw error(400,"本接口不接受查询参数");
        return new Route(id,operation);
    }
    private static void validId(long id)throws Api.ApiException{if(id<=0||id>MAX_ID)throw error(400,"账号编号格式不正确");}
    private static void fields(Map<String,Object> body,Set<String> keys)throws Api.ApiException {
        if(body==null||!body.keySet().equals(keys)||body.values().stream().anyMatch(value->value!=null&&!(value instanceof String)))throw error(400,"请求字段缺失、重复或不受支持");
    }
    private static String text(Map<String,Object> body,String key,boolean nullable)throws Api.ApiException {
        Object value=body.get(key);if(value==null&&nullable)return null;
        if(!(value instanceof String text)||text.isBlank()||text.length()>128||!text.equals(text.strip())||text.chars().anyMatch(c->c<32||c==127)||!validSurrogates(text))throw error(400,"请求字段格式不正确");
        return text;
    }
    private static boolean validSurrogates(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    private static void noStore(HttpExchange ex){ex.getResponseHeaders().set("Cache-Control","no-store");}
    private static Api.ApiException stale(){return error(409,"核对记录已失效或内容已变化，请重新预览后确认");}
    private static Api.ApiException unavailable(){return error(503,"暂时无法核实人员关系配置，请稍后重试");}
    private static Api.ApiException error(int status,String message){return new Api.ApiException(status,message);}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}

    /** Strict, bounded flat JSON; unlike the general parser, decoded duplicate keys cannot overwrite. */
    public static Map<String,Object> parseBody(byte[] bytes)throws Api.ApiException {
        if(bytes==null)throw error(400,"请求内容不是有效的 JSON 对象");
        if(bytes.length>4096)throw error(413,"请求内容过大");
        try {
            String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return new FlatParser(decoded).parse();
        }catch(Exception invalid){throw error(400,"请求内容必须为有效的单层 JSON 对象，字段值只能为字符串或 null");}
    }
    private static final class FlatParser {
        final String text;int at;
        FlatParser(String text){this.text=text;}
        Map<String,Object> parse() {
            Map<String,Object> result=new LinkedHashMap<>();white();take('{');white();
            if(peek('}'))at++;else while(true) {
                String key=string();if(result.containsKey(key))throw new IllegalArgumentException();white();take(':');white();
                Object value;if(peek('"'))value=string();else {if(!text.startsWith("null",at))throw new IllegalArgumentException();at+=4;value=null;}
                result.put(key,value);white();if(peek('}')){at++;break;}take(',');white();
            }
            white();if(at!=text.length())throw new IllegalArgumentException();return result;
        }
        String string() {
            take('"');StringBuilder result=new StringBuilder();boolean closed=false;
            while(at<text.length()) {
                char c=text.charAt(at++);if(c=='"'){closed=true;break;}if(c<32)throw new IllegalArgumentException();
                if(c=='\\') {
                    if(at>=text.length())throw new IllegalArgumentException();char escape=text.charAt(at++);
                    c=switch(escape){case '"'->'"';case '\\'->'\\';case '/'->'/';case 'b'->'\b';case 'f'->'\f';case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case 'u'->hex();default->throw new IllegalArgumentException();};
                }
                result.append(c);
            }
            if(!closed||!validSurrogates(result.toString()))throw new IllegalArgumentException();return result.toString();
        }
        char hex(){if(at+4>text.length())throw new IllegalArgumentException();int n=0;for(int i=0;i<4;i++){char c=text.charAt(at++);int v=c<128?Character.digit(c,16):-1;if(v<0)throw new IllegalArgumentException();n=(n<<4)|v;}return (char)n;}
        boolean peek(char c){return at<text.length()&&text.charAt(at)==c;}
        void take(char c){if(!peek(c))throw new IllegalArgumentException();at++;}
        void white(){while(at<text.length()&&" \n\r\t".indexOf(text.charAt(at))>=0)at++;}
    }
}
