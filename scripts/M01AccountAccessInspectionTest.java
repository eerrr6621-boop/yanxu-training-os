package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Own fresh synthetic H2 only. Exercises preflight without reading the hostile request body. */
public final class M01AccountAccessInspectionTest {
    private static int checks;
    private static final String PASSWORD="Synthetic-Inspection-Only";
    private static Auth.Session admin;
    private static String adminToken;
    private static long ordinary,dual,disabled,bindingOff,personOff,homeOff,unbound;
    @FunctionalInterface private interface Work {void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toRealPath();check(root.getFileName().toString().startsWith("yanxu-m01-access-inspection."),"private temporary root");
        Path data=root.resolve("data");Files.createDirectory(data);
        System.setProperty("data.dir",data.toString());System.setProperty("bootstrap.demo","false");System.setProperty("bootstrap.admin.password",PASSWORD);System.setProperty("login.email.mode","legacy");
        try {
            Db.init();adminToken=Auth.login("admin",PASSWORD);admin=Auth.get(adminToken);check(admin!=null,"real current admin");
            ordinary=user("ordinary","viewer",1);dual=user("dual","viewer",1);disabled=user("disabled","viewer",0);bindingOff=user("bindingoff","viewer",1);personOff=user("personoff","viewer",1);homeOff=user("homeoff","viewer",1);unbound=user("unbound","manager",1);
            String viewerToken=Auth.login("synthetic-ordinary",PASSWORD);Auth.Session viewer=Auth.get(viewerToken);
            String before=database();Map<String,Object> empty=inspect(ordinary,null);assertDTO(empty,0);
            check(empty.get("configuration_status").equals("NOT_CONFIGURED")&&empty.get("configuration_version")==null,"empty configuration distinct");
            Map<String,Object> subject=obj(empty.get("subject"));check(subject.get("binding_status").equals("CONFIG_NOT_PUBLISHED")&&subject.get("person_code")==null&&subject.get("organization_code")==null&&subject.get("person_enabled")==null&&subject.get("home_organization_enabled")==null&&list(subject.get("role_codes")).isEmpty(),"no guessed roles or codes");
            check(list(empty.get("organizations")).isEmpty()&&empty.get("selected_organization")==null,"no automatic organization");
            rejects(400,()->inspect(ordinary,"001"),"organization rejected with no config");rejects(404,()->inspect(99999,null),"missing account 404");
            rejects(401,()->OrganizationAccountAccessInspection.inspect(null,-1,"bad"),"anonymous before arguments");rejects(403,()->OrganizationAccountAccessInspection.inspect(viewer,-1,"bad"),"ordinary actor before arguments");
            Auth.Session forged=new Auth.Session();forged.uid=admin.uid;forged.role="admin";rejects(401,()->OrganizationAccountAccessInspection.inspect(forged,ordinary,null),"forged session");
            String expiredToken=Auth.login("admin",PASSWORD);Auth.Session expired=Auth.get(expiredToken);expired.time=0;rejects(401,()->OrganizationAccountAccessInspection.inspect(expired,ordinary,null),"expired session");
            check(before.equals(database()),"empty and denied inspection all-table zero writes");
            requestChecks(viewerToken);
            Configuration config=fixture("inspection-v1");check(OrganizationAccess.validate(config).valid(),"explicit synthetic configuration valid");
            OrganizationAccessStore.publish(admin,null,config);
            before=database();
            Map<String,Object> noChoice=inspect(ordinary,null);assertDTO(noChoice,0);check(noChoice.get("selected_organization")==null,"configured still no auto selection");
            check(list(obj(noChoice.get("subject")).get("role_codes")).equals(List.of("EMPLOYEE")),"only configured role names");
            check(!Boolean.TRUE.equals(org(noChoice,"003").get("enabled"))&&Boolean.FALSE.equals(org(noChoice,"030").get("enabled")),"organization options include disabled ancestor effect");
            Map<String,Object> own=inspect(ordinary,"001");assertDTO(own,15);
            decision(own,"demand.read",true,"ALLOWED",List.of("employee-read"));decision(own,"demand.write",true,"ALLOWED",List.of("employee-write"));
            decision(own,"approval.review",false,"NOT_CONFIGURED",List.of());
            Map<String,Object> other=inspect(ordinary,"002");decision(other,"demand.read",false,"DENIED",List.of());
            Map<String,Object> adminTarget=inspect(admin.uid,"001");check(obj(adminTarget.get("subject")).get("binding_status").equals("UNBOUND"),"old admin has no implicit binding");
            check(rows(adminTarget.get("decisions")).stream().noneMatch(d->Boolean.TRUE.equals(d.get("allowed"))),"old admin not business superuser");
            check(list(obj(inspect(unbound,"001").get("subject")).get("role_codes")).isEmpty(),"old manager has no business role mapping");
            Map<String,Object> scopeOne=inspect(dual,"001"),scopeTwo=inspect(dual,"002");
            decision(scopeOne,"approval.review",true,"ALLOWED",List.of("leader-review"));decision(scopeOne,"summary.edit",true,"ALLOWED",List.of("leader-summary"));
            decision(scopeTwo,"approval.review",true,"ALLOWED",List.of("bp-review"));decision(scopeTwo,"summary.edit",false,"DENIED",List.of());
            decision(scopeOne,"reports.read",false,"DENIED",List.of());decision(scopeTwo,"reports.read",true,"ALLOWED",List.of("bp-report"));
            decision(scopeTwo,"reports.export",false,"DENIED",List.of("bp-export-deny"));
            check(list(obj(scopeOne.get("subject")).get("role_codes")).equals(List.of("BP","LEADER")),"multiple roles preserved sorted");
            for(String resource:List.of("catalog.read","catalog.manage","delivery.read","delivery.write","delivery.verify","survey.preview","summary.read","demand.accept","bid.result"))decision(scopeOne,resource,true,"ALLOWED",List.of("all-"+resource));
            Map<String,Object> stopped=inspect(disabled,"001");check(Boolean.FALSE.equals(obj(stopped.get("subject")).get("account_enabled")),"disabled account can be inspected");
            check(rows(stopped.get("decisions")).stream().allMatch(d->d.get("status").equals("ACCOUNT_DISABLED")&&Boolean.FALSE.equals(d.get("allowed"))&&d.get("reason").equals("账号已停用")&&list(d.get("matched_rule_ids")).isEmpty()),"disabled overrides all configured decisions");
            Map<String,Object> disabledBinding=inspect(bindingOff,"001");check(obj(disabledBinding.get("subject")).get("binding_status").equals("BOUND_DISABLED"),"disabled binding exact status");deniedReason(disabledBinding,"账号人员绑定已停用");
            Map<String,Object> disabledPerson=inspect(personOff,"001");check(Boolean.FALSE.equals(obj(disabledPerson.get("subject")).get("person_enabled")),"disabled person flag");deniedReason(disabledPerson,"人员已停用");
            Map<String,Object> disabledHome=inspect(homeOff,"001");check(Boolean.FALSE.equals(obj(disabledHome.get("subject")).get("home_organization_enabled")),"disabled home ancestor flag");deniedReason(disabledHome,"所属机构或其上级机构已停用");
            deniedReason(inspect(ordinary,"003"),"数据所属机构或其上级机构已停用");
            rejects(400,()->inspect(ordinary,"1"),"leading-zero organization not guessed");rejects(400,()->inspect(ordinary," 001"),"organization whitespace rejected");rejects(400,()->inspect(ordinary,"missing"),"unknown organization rejected");
            compareEngine(config);
            check(before.equals(database()),"all configured inspections leave every table unchanged");
            corruptionChecks();
            revokeAndActorChecks(config);
            check(Db.count("teachers")==0&&Db.count("demands")==0,"no business sample data");
            System.out.println("M01AccountAccessInspection: "+checks+" checks passed (synthetic H2; strict preflight/query, unchanged Engine, all-table zero writes).");
        } finally {Db.exec("SHUTDOWN");}
    }
    private static void requestChecks(String viewerToken)throws Exception {
        String base="/api/organization/account-access";String before=database();
        check(OrganizationAccountAccessInspection.matches(base)&&OrganizationAccountAccessInspection.matches(base+"/unknown")&&!OrganizationAccountAccessInspection.matches(base+"-wrong")&&!OrganizationAccountAccessInspection.matches(null),"route namespace precise");
        for(String method:List.of("GET","HEAD","POST","DELETE")) {
            Exchange anonymous=new Exchange(method,base+"/unknown?bad=%FF",null);rejects(401,()->OrganizationAccountAccessInspection.preflight(anonymous),"anonymous auth before route/method/query "+method);check(anonymous.bodyReads==0,"anonymous body untouched");
            Exchange ordinaryRequest=new Exchange(method,base+"/unknown?bad=%FF",viewerToken);rejects(403,()->OrganizationAccountAccessInspection.preflight(ordinaryRequest),"ordinary auth before route/method/query "+method);check(ordinaryRequest.bodyReads==0,"ordinary body untouched");
        }
        Exchange post=new Exchange("POST",base+"?bad=%FF",adminToken);rejects(405,()->OrganizationAccountAccessInspection.preflight(post),"admin POST before body/query");check(post.bodyReads==0&&"GET, HEAD".equals(post.response.getFirst("Allow")),"POST untouched body and Allow");
        Exchange unknown=new Exchange("GET",base+"/unknown?bad=%FF",adminToken);rejects(404,()->OrganizationAccountAccessInspection.preflight(unknown),"unknown child after auth");
        for(String query:List.of("", "account_id=", "account_id=0", "account_id=-1", "account_id=01", "account_id=1.0", "account_id=1e0", "account_id=+1", "account_id=%201", "account_id=9007199254740992", "account_id=99999999999999999", "account_id=1&account_id=2", "account_id=1&%61ccount_id=2", "account_id=1&organization_code=001&%6Frganization_code=002", "account_id=1&role=admin", "account_id=1&", "account_id", "organization_code=001", "account_id=1&&organization_code=001", "account_id=%FF", "account_id=%C0%AF", "account_id=1&organization_code=%ED%A0%80", "account_id=1&organization_code=%F4%90%80%80", "account_id=1&organization_code=", "account_id=1&organization_code=%00", "account_id=1&organization_code="+"x".repeat(129), "account_id=1&%2561ccount_id=2", "account_id="+"1".repeat(4096))) {
            Exchange request=new Exchange("GET",base+"?"+query,adminToken);
            rejects(400,()->OrganizationAccountAccessInspection.handle(request,admin),"strict query rejected");check(request.bodyReads==0,"bad query body untouched");
        }
        Exchange encoded=new Exchange("GET",base+"?%61ccount_id=%31",adminToken);OrganizationAccountAccessInspection.preflight(encoded);check(OrganizationAccountAccessInspection.handle(encoded,admin),"one valid percent decoding accepted");check(encoded.bodyReads==0&&"no-store".equals(encoded.response.getFirst("Cache-Control")),"successful GET no body read and private cache");
        Exchange head=new Exchange("HEAD",base+"?account_id="+ordinary,adminToken);OrganizationAccountAccessInspection.preflight(head);check(OrganizationAccountAccessInspection.handle(head,admin)&&head.bodyReads==0,"HEAD same read-only contract");
        String secondToken=Auth.login("admin",PASSWORD);Auth.Session second=Auth.get(secondToken);
        rejects(401,()->OrganizationAccountAccessInspection.handle(new Exchange("GET",base+"?account_id=1",adminToken),second),"same account different real session cannot replace request actor");Auth.logout(secondToken);
        check(!OrganizationAccountAccessInspection.handle(new Exchange("POST","/unrelated",null),null),"unrelated handler returns false");
        check(before.equals(database()),"all query/preflight checks zero database writes");
    }
    private static Configuration fixture(String version) {
        Set<String> roles=Set.of("EMPLOYEE","LEADER","BP");RelationRule optional=new RelationRule(false,roles,false,true);
        List<Organization> orgs=List.of(new Organization("000",null,true),new Organization("001","000",true),new Organization("002","000",true),new Organization("030","000",false),new Organization("003","030",true));
        List<Person> people=List.of(person("P1","001",Set.of("EMPLOYEE"),Map.of(),true),person("P2","001",Set.of("LEADER","BP"),Map.of("LEADER",Set.of("001"),"BP",Set.of("002")),true),person("P3","001",Set.of("EMPLOYEE"),Map.of(),true),person("P4","001",Set.of("EMPLOYEE"),Map.of(),true),person("P5","001",Set.of("EMPLOYEE"),Map.of(),false),person("P6","003",Set.of("EMPLOYEE"),Map.of(),true));
        List<AccountBinding> bindings=List.of(new AccountBinding(ordinary,"P1",true),new AccountBinding(dual,"P2",true),new AccountBinding(disabled,"P3",false),new AccountBinding(bindingOff,"P4",false),new AccountBinding(personOff,"P5",true),new AccountBinding(homeOff,"P6",true));
        List<Grant> grants=new ArrayList<>(List.of(grant("employee-read","EMPLOYEE","demand.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()),grant("employee-write","EMPLOYEE","demand.write",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()),
            grant("leader-review","LEADER","approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()),grant("bp-review","BP","approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()),grant("leader-summary","LEADER","summary.edit",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()),
            grant("bp-report","BP","reports.read",Action.VIEW,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()),grant("bp-export-allow","BP","reports.export",Action.EXPORT,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()),grant("bp-export-deny","BP","reports.export",Action.EXPORT,Effect.DENY,Scope.NAMED_ORGS,Set.of("002"))));
        for(String resource:List.of("catalog.read","catalog.manage","delivery.read","delivery.write","delivery.verify","survey.preview","summary.read","demand.accept","bid.result"))grants.add(grant("all-"+resource,"LEADER",resource,resource.endsWith(".read")?Action.VIEW:Action.HANDLE,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
        return new Configuration(version,new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),roles,orgs,people,roles.stream().sorted().map(r->new RoleRelations(r,optional,optional)).toList(),bindings,grants);
    }
    private static Person person(String code,String home,Set<String> roles,Map<String,Set<String>> scopes,boolean enabled){Set<String> union=new HashSet<>();scopes.values().forEach(union::addAll);return new Person(code,home,union,null,null,roles,enabled,scopes);}
    private static Grant grant(String id,String role,String resource,Action action,Effect effect,Scope scope,Set<String> orgs){return new Grant(id,role,resource,action,effect,scope,orgs);}
    private static void compareEngine(Configuration config)throws Exception {
        Engine engine=new Engine(config);for(long account:List.of(ordinary,dual,bindingOff,personOff,homeOff,unbound,admin.uid))for(String organization:List.of("001","002","003")) {
            for(Map<String,Object> row:rows(inspect(account,organization).get("decisions"))) {
                Decision expected=engine.authorize("synthetic-comparison",ignored->OptionalLong.of(account),new Resource((String)row.get("resource"),organization),Action.valueOf((String)row.get("action")));
                check(row.get("allowed").equals(expected.allowed())&&row.get("status").equals(expected.status().name())&&row.get("reason").equals(expected.reason())&&row.get("matched_rule_ids").equals(expected.matchedRuleIds()),"diagnostic exactly matches existing Engine");
            }
        }
    }
    private static void corruptionChecks()throws Exception {
        Map<String,Object> original=Db.one("SELECT * FROM organization_access_config WHERE active_slot=1");String payload=(String)original.get("payload");
        for(String value:List.of("{}", "[]", "null", "{private-source-secret", "{\"version\":\"private-source-secret\"}", payload.replace("\"P1\"","\"missing-private-person\""))) {
            Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",value);String before=database();rejects(503,()->inspect(ordinary,"001"),"corrupt config sanitized 503");check(before.equals(database()),"corrupt config no repair");Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",payload);
        }
        Db.exec("UPDATE organization_access_config SET version='mismatch' WHERE active_slot=1");rejects(503,()->inspect(ordinary,"001"),"version mismatch 503");Db.exec("UPDATE organization_access_config SET version=? WHERE active_slot=1",original.get("version"));
        Db.exec("UPDATE organization_access_config SET active_slot=NULL WHERE active_slot=1");rejects(503,()->inspect(ordinary,null),"orphan history not unpublished config");Db.exec("UPDATE organization_access_config SET active_slot=1 WHERE id=?",original.get("id"));
        Db.exec("ALTER TABLE organization_access_config RENAME TO inspection_hidden_config");try{rejects(503,()->inspect(ordinary,null),"missing configuration table sanitized");}finally{Db.exec("ALTER TABLE inspection_hidden_config RENAME TO organization_access_config");}
        Db.exec("UPDATE users SET status=9 WHERE id=?",ordinary);rejects(503,()->inspect(ordinary,null),"invalid target account state");Db.exec("UPDATE users SET status=1 WHERE id=?",ordinary);
        long gone=user("gone","viewer",1);Db.exec("DELETE FROM users WHERE id=?",gone);rejects(404,()->inspect(gone,null),"removed account cannot resolve by name");
    }
    private static void revokeAndActorChecks(Configuration base)throws Exception {
        Configuration revoke=new Configuration("inspection-revoked",base.codeRules(),base.roleCodes(),base.organizations(),base.people(),base.relations(),base.accountBindings(),List.of());
        OrganizationAccessStore.publish(admin,base.version(),revoke);
        String before=database();Map<String,Object> result=inspect(ordinary,"001");check(result.get("configuration_version").equals("inspection-revoked"),"new version read without cache");check(rows(result.get("decisions")).stream().allMatch(r->!Boolean.TRUE.equals(r.get("allowed"))&&r.get("status").equals("NOT_CONFIGURED")),"revoked rules immediately reflected");check(before.equals(database()),"revocation inspection zero writes");
        long actorId=user("temporary-admin","admin",1);String token=Auth.login("synthetic-temporary-admin",PASSWORD);Auth.Session actor=Auth.get(token);
        Db.exec("UPDATE users SET role='viewer' WHERE id=?",actorId);before=database();rejects(403,()->OrganizationAccountAccessInspection.inspect(actor,ordinary,"001"),"current DB role change checked");check(before.equals(database()),"downgraded actor zero writes");
        Db.exec("UPDATE users SET role='admin',status=0 WHERE id=?",actorId);rejects(401,()->OrganizationAccountAccessInspection.inspect(actor,ordinary,"001"),"disabled actor rejected");
    }
    private static Map<String,Object> inspect(long id,String org)throws Exception{return OrganizationAccountAccessInspection.inspect(admin,id,org);}
    private static long user(String name,String role,int status)throws Exception{return Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,?)","synthetic-"+name,Auth.hash(PASSWORD),"Same synthetic name",role,status);}
    private static void assertDTO(Map<String,Object> value,int decisions){check(value.keySet().equals(Set.of("read_only","organization_gate_only","configuration_status","configuration_version","subject","organizations","selected_organization","decisions")),"top-level DTO whitelist");check(Boolean.TRUE.equals(value.get("read_only"))&&Boolean.TRUE.equals(value.get("organization_gate_only")),"diagnostic markers always true");check(obj(value.get("subject")).keySet().equals(Set.of("account_id","username","name","account_enabled","binding_status","person_code","organization_code","person_enabled","home_organization_enabled","role_codes")),"subject whitelist");check(rows(value.get("decisions")).size()==decisions,"bounded decision count");for(Map<String,Object> row:rows(value.get("decisions")))check(row.keySet().equals(Set.of("resource","action","label","allowed","status","reason","matched_rule_ids")),"decision whitelist");check(!Json.write(value).contains("password")&&!Json.write(value).contains("private-source-secret"),"no credential/config source leakage");}
    private static Map<String,Object> org(Map<String,Object> result,String code){return rows(result.get("organizations")).stream().filter(r->r.get("organization_code").equals(code)).findFirst().orElseThrow();}
    private static void decision(Map<String,Object> result,String resource,boolean allowed,String status,List<String> rules){Map<String,Object> row=rows(result.get("decisions")).stream().filter(r->r.get("resource").equals(resource)).findFirst().orElseThrow();check(row.get("allowed").equals(allowed)&&row.get("status").equals(status)&&row.get("matched_rule_ids").equals(rules),resource+" expected gate");}
    private static void deniedReason(Map<String,Object> result,String reason){check(rows(result.get("decisions")).stream().allMatch(r->Boolean.FALSE.equals(r.get("allowed"))&&reason.equals(r.get("reason"))),reason);}
    private static void rejects(int status,Work work,String label)throws Exception{try{work.run();throw new AssertionError(label);}catch(Api.ApiException error){check(error.code==status,label+" HTTP status");check(error.getCause()==null&&!error.getMessage().contains("private-source-secret")&&!error.getMessage().contains("missing-private-person")&&!error.getMessage().contains("payload"),label+" safe error");}}
    private static String database()throws Exception {Map<String,Object> contents=new TreeMap<>();for(Map<String,Object> row:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){String name=(String)row.get("table_name");contents.put(name,Db.query("SELECT * FROM \""+name+"\"").stream().map(Json::write).sorted().toList());}return Json.write(contents);}
    @SuppressWarnings("unchecked")private static Map<String,Object> obj(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked")private static List<Map<String,Object>> rows(Object v){return (List<Map<String,Object>>)v;}
    private static List<?> list(Object v){return (List<?>)v;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static final class Exchange extends HttpExchange {
        final String method;final URI uri;final Headers request=new Headers(),response=new Headers();final Map<String,Object> attributes=new HashMap<>();int bodyReads;
        Exchange(String method,String path,String token){this.method=method;uri=URI.create(path);if(token!=null)request.set("Cookie","yx_session="+token);request.set("Content-Type","text/plain");}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){bodyReads++;throw new AssertionError("Read-only diagnostic must not inspect request body");}public OutputStream getResponseBody(){return OutputStream.nullOutputStream();}public void sendResponseHeaders(int status,long length){}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",0);}public int getResponseCode(){return 0;}public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",0);}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String key){return attributes.get(key);}public void setAttribute(String key,Object value){attributes.put(key,value);}public void setStreams(InputStream in,OutputStream out){}public HttpPrincipal getPrincipal(){return null;}
    }
}
