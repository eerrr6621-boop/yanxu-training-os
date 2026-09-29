package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Fresh synthetic H2 only: no roster sources, production data, HTTP test routes or exported test hooks. */
public final class M01AccountRelationshipsTest {
    private static final String PASSWORD="Synthetic-Relationships-Only";
    private static final String BASE="/api/organization/account-relationships";
    private static final Map<String,Long> IDS=new HashMap<>();
    private static Auth.Session admin;
    private static String adminToken;
    private static int checks;
    @FunctionalInterface interface Work{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toRealPath();check(root.getFileName().toString().startsWith("yanxu-m01-account-relationships."),"isolated root");
        Path data=root.resolve("data");Files.createDirectory(data);
        System.setProperty("data.dir",data.toString());System.setProperty("bootstrap.demo","false");System.setProperty("bootstrap.admin.password",PASSWORD);System.setProperty("login.email.mode","legacy");
        try {
            Db.init();loginAdmin();String password=Auth.hash(PASSWORD);
            for(String key:List.of("P1","P2","P3","P4","P5","P6","P7","P8","P9","P10","P11","P12","P13","P14","P15","P16","P17","P18","UNBOUND"))
                IDS.put(key,Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,?)","synthetic-"+key,password,"Same synthetic display",key.equals("UNBOUND")?"admin":"viewer",key.equals("P13")?0:1));
            String empty=database();rejects(409,()->editor("P1"),"no formal configuration");rejects(404,()->OrganizationAccountRelationships.editor(admin,99999),"missing account");check(empty.equals(database()),"empty reads no writes");
            String viewerToken=Auth.login("synthetic-P1",PASSWORD);Auth.Session viewer=Auth.get(viewerToken);
            rejects(401,()->OrganizationAccountRelationships.editor(null,-1),"anonymous first");rejects(403,()->OrganizationAccountRelationships.editor(viewer,-1),"viewer first");
            Auth.Session forged=new Auth.Session();forged.uid=admin.uid;forged.role="admin";rejects(401,()->OrganizationAccountRelationships.editor(forged,IDS.get("P1")),"object authenticity");
            routeChecks(viewerToken);parserChecks();
            Configuration configuration=fixture("relationships-test-v1");check(validate(configuration).valid(),"synthetic complete config valid");OrganizationAccessStore.publish(admin,null,configuration);seedDevices();
            editorAndSelectionChecks();saveAndSessionChecks();reviewChecks();rollbackChecks();globalBindingChecks();corruptionChecks();selfActorHandlerCheck();
            check(Db.count("teachers")==0&&Db.count("demands")==0,"business tables remain empty");
            System.out.println("M01AccountRelationships: "+checks+" checks passed (fresh synthetic H2; strict parsing, role scopes, review binding, single-CAS relationship save with exact device revocation).");
        }finally {Db.exec("SHUTDOWN");}
    }
    private static Configuration fixture(String version) {
        Set<String> roles=Set.of("EMP","AUX","LEADER","BP","REVIEWER","SELF","DENYER");
        RelationRule lead=new RelationRule(true,Set.of("LEADER"),true,false),bp=new RelationRule(true,Set.of("BP"),true,false);
        RelationRule optionalLead=new RelationRule(false,Set.of("LEADER"),true,false),optionalBp=new RelationRule(false,Set.of("BP"),true,false);
        List<RoleRelations> rules=new ArrayList<>();for(String role:roles.stream().sorted().toList())rules.add(new RoleRelations(role,
            role.equals("EMP")?lead:role.equals("AUX")?new RelationRule(true,Set.of("REVIEWER"),true,false):role.equals("SELF")?new RelationRule(false,Set.of("SELF"),true,true):optionalLead,
            Set.of("EMP","AUX").contains(role)?bp:optionalBp));
        List<Person> people=List.of(
            person("P1","001",Map.of("EMP",Set.of()),"P2","P3",true),
            person("P2","001",Map.of("LEADER",Set.of("001")),null,null,true),
            person("P3","001",Map.of("BP",Set.of("001")),null,null,true),
            person("P4","001",Map.of("LEADER",Set.of("001")),null,null,true),
            person("P5","002",Map.of("BP",Set.of("002")),null,null,true),
            person("P6","001",Map.of("LEADER",Set.of("001"),"BP",Set.of("002")),null,null,true),
            person("P7","001",Map.of("LEADER",Set.of("001"),"BP",Set.of("001")),null,null,true),
            person("P8","001",Map.of("LEADER",Set.of("001"),"BP",Set.of("001")),null,null,true),
            person("P9","001",Map.of("EMP",Set.of(),"AUX",Set.of()),"P10","P3",true),
            person("P10","001",Map.of("LEADER",Set.of("001"),"REVIEWER",Set.of("001")),null,null,true),
            person("P11","001",Map.of("LEADER",Set.of("001")),null,null,true),
            person("P12","001",Map.of("LEADER",Set.of("001")),"P11",null,true),
            person("P13","001",Map.of("LEADER",Set.of("001")),null,null,true),
            person("P14","001",Map.of("EMP",Set.of()),"P13","P3",true),
            person("P15","001",Map.of("LEADER",Set.of("001")),null,null,false),
            person("P16","003",Map.of("LEADER",Set.of("001")),null,null,true),
            person("P17","001",Map.of("LEADER",Set.of("001"),"DENYER",Set.of("001")),null,null,true),
            person("P18","001",Map.of("SELF",Set.of("001")),null,null,true),
            person("P19","001",Map.of("LEADER",Set.of("001")),null,null,true));
        List<AccountBinding> bindings=new ArrayList<>();for(Person p:people)if(IDS.containsKey(p.personCode()))bindings.add(new AccountBinding(IDS.get(p.personCode()),p.personCode(),!p.personCode().equals("P13")));
        List<Grant> grants=new ArrayList<>();for(String role:List.of("LEADER","BP","REVIEWER","SELF"))grants.add(new Grant(role+"_ALLOW",role,"approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        grants.add(new Grant("GLOBAL_DENY","DENYER","approval.review",Action.HANDLE,Effect.DENY,Scope.RESPONSIBLE_ORGS,Set.of()));
        return new Configuration(version,new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),roles,
            List.of(new Organization("000",null,true),new Organization("001","000",true),new Organization("002","000",true),new Organization("030","000",false),new Organization("003","030",true)),
            people,rules,bindings,grants,List.of(new CombinedApprovalAssignment("001","P7","LEADER","BP","SYNTHETIC-COMBINED-EVIDENCE")));
    }
    private static Person person(String code,String org,Map<String,Set<String>> scopes,String leader,String bp,boolean enabled){Set<String> union=new HashSet<>();scopes.values().forEach(union::addAll);return new Person(code,org,union,leader,bp,scopes.keySet(),enabled,scopes);}
    private static void editorAndSelectionChecks()throws Exception {
        String before=database();Map<String,Object> editor=editor("P1");
        check(editor.keySet().equals(Set.of("configuration_version","subject","current","leader_required","bp_required","leader_options","bp_options","combined_person_codes","warnings")),"editor whitelist");
        check(obj(editor.get("subject")).keySet().equals(Set.of("account_id","username","name","account_enabled","binding_enabled","person_code","organization_code","person_enabled","role_codes")),"subject whitelist");
        check(Boolean.TRUE.equals(editor.get("leader_required"))&&Boolean.TRUE.equals(editor.get("bp_required")),"requirements derived from config");
        check(codes(editor,"leader_options").containsAll(Set.of("P2","P4","P6","P7","P8","P10")),"eligible leaders");
        for(String code:List.of("P1","P3","P13","P15","P16","P17","P19"))check(!codes(editor,"leader_options").contains(code),"exclude unavailable/wrong role/scope/deny/unbound leader");
        check(!codes(editor,"bp_options").contains("P6")&&!codes(editor,"bp_options").contains("P5"),"role scoped coverage excludes cross-role union");
        check(editor.get("combined_person_codes").equals(List.of("P7")),"only explicit valid combined evidence");
        for(Map<String,Object> option:rows(editor.get("leader_options")))check(option.keySet().equals(Set.of("person_code","account_id","username","name","role_codes","eligible"))&&Boolean.TRUE.equals(option.get("eligible")),"relation whitelist");
        check(!Json.write(editor).contains("password")&&!Json.write(editor).contains("SYNTHETIC-COMBINED-EVIDENCE"),"DTO does not expose private facts/proof");
        Map<String,Object> retired=obj(obj(editor("P14").get("current")).get("leader"));check(retired.get("person_code").equals("P13")&&Boolean.FALSE.equals(retired.get("eligible")),"current invalid relation preserved");
        check(codes(editor("P9"),"leader_options").equals(Set.of("P10")),"every subject role intersects eligible target roles");
        check(!codes(editor("P11"),"leader_options").contains("P12"),"cyclic leader omitted");
        rejects(409,()->editor("UNBOUND"),"legacy admin role no implicit formal binding");
        rejects(400,()->preview("P1",null,"P3"),"required leader");rejects(400,()->preview("P1","P2",null),"required bp");
        rejects(400,()->preview("P1","P2","P5"),"out of organization");rejects(400,()->preview("P1","P6","P6"),"scope not flattened");
        rejects(400,()->preview("P1","P8","P8"),"same roles without combined evidence");rejects(400,()->preview("P9","P2","P3"),"multi role intersection");
        rejects(400,()->preview("P11","P12",null),"cycle full config rejected");rejects(400,()->preview("P2","P2",null),"self denied by rule");
        check(Boolean.TRUE.equals(preview("P18","P18",null).get("changed")),"explicit allowSelf retains existing engine semantics");
        Map<String,Object> combined=preview("P1","P7","P7");check(Boolean.TRUE.equals(combined.get("changed")),"existing combined evidence permits pair");
        Map<String,Object> impact=obj(combined.get("workflow_impact"));check(impact.equals(Map.of("historical_assignments_changed",false,"pending_tasks_reassigned",false,"combined_workflows_may_pause",true)),"fixed workflow effects");
        check(((String)combined.get("review_token")).matches("[a-f0-9]{64}")&&java.time.Instant.parse((String)combined.get("expires_at")).isAfter(java.time.Instant.now()),"opaque expiring token");
        check(before.equals(database()),"all editor and preview paths are database read-only");
    }
    private static void saveAndSessionChecks()throws Exception {
        String subjectToken=Auth.login("synthetic-P1",PASSWORD),peerToken=Auth.login("synthetic-P4",PASSWORD),bpToken=Auth.login("synthetic-P3",PASSWORD);
        Configuration before=OrganizationAccessStore.configuration();long rows=Db.count("organization_access_config");
        Map<String,Object> preview=preview("P1","P4","P3");check(before.equals(OrganizationAccessStore.configuration()),"preview no configuration change");
        Map<String,Object> saved=confirm("P1",preview);Configuration after=OrganizationAccessStore.configuration();
        check(saved.keySet().equals(Set.of("saved","unchanged","configuration_version","account_id","person_code","leader_person_code","bp_person_code","sessionInvalidated")),"confirm whitelist");
        check(Boolean.TRUE.equals(saved.get("saved"))&&Boolean.FALSE.equals(saved.get("unchanged"))&&Boolean.FALSE.equals(saved.get("sessionInvalidated")),"save reports committed success");
        check(Db.count("organization_access_config")==rows+1&&!before.version().equals(after.version()),"one unique config version");
        check(before.codeRules().equals(after.codeRules())&&before.roleCodes().equals(after.roleCodes())&&before.organizations().equals(after.organizations())&&before.relations().equals(after.relations())&&before.accountBindings().equals(after.accountBindings())&&before.grants().equals(after.grants())&&before.combinedApprovals().equals(after.combinedApprovals()),"all shared policy/evidence preserved");
        for(Person old:before.people()) {Person next=after.people().stream().filter(p->p.personCode().equals(old.personCode())).findFirst().orElseThrow();
            if(!old.personCode().equals("P1"))check(old.equals(next),"other person unchanged");
            else check(next.equals(new Person(old.personCode(),old.organizationCode(),old.responsibleOrganizationCodes(),"P4","P3",old.roleCodes(),old.enabled(),old.responsibleOrganizationsByRole())),"only two selected refs changed");
        }
        check(Auth.get(subjectToken)==null&&Auth.get(peerToken)!=null&&Auth.get(bpToken)!=null&&Auth.get(adminToken)==admin,"only subject sessions revoked after commit");
        String committed=database();rejects(409,()->confirm("P1",preview),"consumed token replay");check(committed.equals(database()),"replay no write");
        Map<String,Object> noop=preview("P1","P4","P3");check(Boolean.FALSE.equals(noop.get("changed"))&&Boolean.FALSE.equals(obj(noop.get("workflow_impact")).get("combined_workflows_may_pause")),"no-op preview still token with no workflow change");
        Map<String,Object> unchanged=confirm("P1",noop);check(Boolean.FALSE.equals(unchanged.get("saved"))&&Boolean.TRUE.equals(unchanged.get("unchanged"))&&committed.equals(database()),"no-op confirm neither writes nor bumps version");
    }
    private static void reviewChecks()throws Exception {
        Map<String,Object> cross=preview("P1","P2","P3");String otherToken=Auth.login("admin",PASSWORD);Auth.Session other=Auth.get(otherToken);
        rejects(409,()->OrganizationAccountRelationships.confirm(other,IDS.get("P1"),token(cross)),"same uid different session blocked");rejects(409,()->confirm("P9",cross),"cross-target review blocked");Auth.logout(otherToken);
        check(Boolean.TRUE.equals(confirm("P1",cross).get("saved")),"wrong actor and target do not poison owner's token");confirm("P1",preview("P1","P4","P3"));
        Map<String,Object> expired=preview("P1","P2","P3");Object review=reviews().get(expired.get("review_token"));var expires=review.getClass().getDeclaredField("expires");expires.setAccessible(true);expires.setLong(review,0);rejects(409,()->confirm("P1",expired),"expiry without production test hooks");
        for(String who:List.of("ACTOR","P1","P2")) {
            long id=who.equals("ACTOR")?admin.uid:IDS.get(who);String prior=(String)Db.one("SELECT password FROM users WHERE id=?",id).get("password");Map<String,Object> p=preview("P1","P2","P3");
            Db.exec("UPDATE users SET password=? WHERE id=?",Auth.hash("Synthetic-changed"),id);String mutated=database();rejects(409,()->confirm("P1",p),"actor/target/candidate credential change detected");check(mutated.equals(database()),"credential stale review no write");Db.exec("UPDATE users SET password=? WHERE id=?",prior,id);
            rejects(409,()->confirm("P1",p),"restored facts cannot revive observed failed review");check(preview("P1","P2","P3").containsKey("review_token"),"fresh preview usable after restored facts");
        }
        Map<String,Object> renamed=preview("P1","P2","P3");Db.exec("UPDATE users SET name='Synthetic changed display' WHERE id=?",IDS.get("P2"));rejects(409,()->confirm("P1",renamed),"candidate display facts bound");Db.exec("UPDATE users SET name='Same synthetic display' WHERE id=?",IDS.get("P2"));
        Map<String,Object> disabled=preview("P1","P2","P3");Db.exec("UPDATE users SET status=0 WHERE id=?",IDS.get("P2"));rejects(409,()->confirm("P1",disabled),"candidate disabled after preview");Db.exec("UPDATE users SET status=1 WHERE id=?",IDS.get("P2"));
        Map<String,Object> hash=preview("P1","P2","P3");String payload=payload();String modified=payload.replace("LEADER_ALLOW","LEADER_CHANGED");check(!payload.equals(modified),"test changes valid policy same version");Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",modified);check(OrganizationAccessStore.configuration()!=null,"same version changed config remains valid");rejects(409,()->confirm("P1",hash),"full config hash catches same version edits");Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",payload);
        Map<String,Object> stale=preview("P1","P2","P3");confirm("P1",preview("P1","P7","P7"));rejects(409,()->confirm("P1",stale),"published version stales reviews");confirm("P1",preview("P1","P4","P3"));
        rejects(400,()->OrganizationAccountRelationships.preview(admin,IDS.get("P1"),Map.of("expected_version","old","leader_person_code","P2")),"missing field");
        rejects(409,()->OrganizationAccountRelationships.preview(admin,IDS.get("P1"),Map.of("expected_version","old","leader_person_code","P2","bp_person_code","P3")),"expected version bound");
        rejects(400,()->OrganizationAccountRelationships.confirm(admin,IDS.get("P1"),Map.of("review_token","f".repeat(64),"account_id","2")),"confirm refuses identity input");
        reviews().clear();for(int i=0;i<128;i++)preview("P1","P4","P3");check(reviews().size()==128,"review cache bounded");rejects(409,()->preview("P1","P4","P3"),"capacity rejects additional token");reviews().clear();
        String shortToken=Auth.login("admin",PASSWORD);Auth.Session shortActor=Auth.get(shortToken);Map<String,Object> revoked=OrganizationAccountRelationships.preview(shortActor,IDS.get("P1"),body("P2","P3"));Auth.logout(shortToken);rejects(401,()->OrganizationAccountRelationships.confirm(shortActor,IDS.get("P1"),token(revoked)),"revoked actor session cannot confirm");
        long originalUid=admin.uid;admin.uid=IDS.get("P1");rejects(401,()->editor("P1"),"mutated Session uid fails Auth authenticity");admin.uid=originalUid;loginAdmin();
    }
    private static void rollbackChecks()throws Exception {
        String subjectToken=Auth.login("synthetic-P1",PASSWORD);Map<String,Object> review=preview("P1","P2","P3");
        Db.exec("CREATE TRIGGER synthetic_reject_relationship_publish BEFORE INSERT ON organization_access_config FOR EACH ROW CALL 'com.training.M01AccountRelationshipsTest$RejectPublish'");
        try {
            String before=database();rejects(503,()->confirm("P1",review),"failed publication sanitized");check(before.equals(database()),"single Store transaction rolls back head and insert together");
            check(Auth.get(subjectToken)!=null&&Auth.get(adminToken)==admin,"failed publication never revokes sessions");
        }finally {Db.exec("DROP TRIGGER synthetic_reject_relationship_publish");}
        rejects(409,()->confirm("P1",review),"observed publication failure token permanently removed");
        check(Boolean.TRUE.equals(confirm("P1",preview("P1","P2","P3")).get("saved")),"fresh preview can publish after failure");
    }
    public static final class RejectPublish implements org.h2.api.Trigger {
        @Override public void fire(java.sql.Connection connection,Object[] oldRow,Object[] newRow)throws java.sql.SQLException{throw new java.sql.SQLException("Synthetic injected publication failure");}
    }
    private static void globalBindingChecks()throws Exception {
        // Known Store failure must be detected before a review is issued, even for an unrelated user.
        Db.exec("UPDATE users SET status=0 WHERE id=?",IDS.get("P11"));rejects(409,()->preview("P1","P2","P3"),"whole config enabled-binding status prerequisite");Db.exec("UPDATE users SET status=1 WHERE id=?",IDS.get("P11"));
        Map<String,Object> deleted=Db.one("SELECT * FROM users WHERE id=?",IDS.get("P11"));Db.exec("DELETE FROM users WHERE id=?",IDS.get("P11"));rejects(409,()->preview("P1","P2","P3"),"whole config missing account prerequisite");Db.exec("INSERT INTO users(id,username,password,name,role,status,created_at) VALUES(?,?,?,?,?,?,?)",deleted.get("id"),deleted.get("username"),deleted.get("password"),deleted.get("name"),deleted.get("role"),deleted.get("status"),deleted.get("created_at"));
        // A disabled subject with its binding disabled is still maintainable, without activating anything.
        Configuration c=OrganizationAccessStore.configuration();List<AccountBinding> bindings=c.accountBindings().stream().map(b->b.personCode().equals("P1")?new AccountBinding(b.accountId(),b.personCode(),false):b).toList();
        Configuration disabled=new Configuration("relationships-disabled-subject",c.codeRules(),c.roleCodes(),c.organizations(),c.people(),c.relations(),bindings,c.grants(),c.combinedApprovals());OrganizationAccessStore.publish(admin,c.version(),disabled);
        Db.exec("UPDATE users SET status=0 WHERE id=?",IDS.get("P1"));Map<String,Object> subject=obj(editor("P1").get("subject"));check(Boolean.FALSE.equals(subject.get("account_enabled"))&&Boolean.FALSE.equals(subject.get("binding_enabled")),"disabled subject displayed");
        confirm("P1",preview("P1","P2","P3"));check(((Number)Db.one("SELECT status FROM users WHERE id=?",IDS.get("P1")).get("status")).intValue()==0&&!OrganizationAccessStore.configuration().accountBindings().stream().filter(b->b.personCode().equals("P1")).findFirst().orElseThrow().enabled(),"disabled subject edited without activation");
        Db.exec("UPDATE users SET status=1 WHERE id=?",IDS.get("P1"));
        c=OrganizationAccessStore.configuration();bindings=c.accountBindings().stream().map(b->b.personCode().equals("P1")?new AccountBinding(b.accountId(),b.personCode(),true):b).toList();OrganizationAccessStore.publish(admin,c.version(),new Configuration("relationships-restored-subject",c.codeRules(),c.roleCodes(),c.organizations(),c.people(),c.relations(),bindings,c.grants(),c.combinedApprovals()));
    }
    private static void corruptionChecks()throws Exception {
        String original=payload();for(String value:List.of("{}","[]","null","{private-source-secret",original.replace("\"P1\"","\"missing-private-person\""))) {
            Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",value);String before=database();rejects(503,()->editor("P1"),"corrupt persisted config sanitized");check(before.equals(database()),"bad configuration not repaired by editor");Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",original);
        }
        String version=OrganizationAccessStore.configuration().version();Db.exec("UPDATE organization_access_config SET active_slot=NULL WHERE active_slot=1");rejects(503,()->editor("P1"),"history without active head corrupt");Db.exec("UPDATE organization_access_config SET active_slot=1 WHERE version=?",version);
    }
    private static void selfActorHandlerCheck()throws Exception {
        Configuration c=OrganizationAccessStore.configuration();List<Person> people=new ArrayList<>(c.people());people.add(person("P20","001",Map.of("LEADER",Set.of("001")),null,null,true));List<AccountBinding> bindings=new ArrayList<>(c.accountBindings());bindings.add(new AccountBinding(admin.uid,"P20",true));
        OrganizationAccessStore.publish(admin,c.version(),new Configuration("relationships-admin-bound",c.codeRules(),c.roleCodes(),c.organizations(),people,c.relations(),bindings,c.grants(),c.combinedApprovals()));loginAdmin();
        Map<String,Object> review=OrganizationAccountRelationships.preview(admin,admin.uid,body("P2",null));String token=adminToken;Auth.Session actor=admin;
        Exchange ex=new Exchange("POST",BASE+"/"+actor.uid+"/confirm",token);var field=Api.class.getDeclaredField("BODY_ATTRIBUTE");field.setAccessible(true);ex.setAttribute((String)field.get(null),token(review));
        Map<String,Object> before=tables();List<Map<String,Object>> epochs=deviceEpochs(),devices=devices();
        check(OrganizationAccountRelationships.handle(ex,actor),"self edit handler returns successful response");publicationTables(before,epochs,devices,actor.uid);check(Auth.get(token)==null&&Auth.current(actor)==null,"real actor session revoked after own relation save");check(ex.response.getFirst("Set-Cookie")!=null&&ex.response.getFirst("Set-Cookie").contains("Max-Age=0"),"self edit handler clears cookie after success");check(ex.bodyReads==0,"handler uses Api cached map");
        var response=Api.class.getDeclaredField("RESPONSE_ATTRIBUTE");response.setAccessible(true);Object value=ex.getAttribute((String)response.get(null));byte[] bytes=null;int status=0;for(var component:value.getClass().getDeclaredFields()){component.setAccessible(true);Object part=component.get(value);if(part instanceof byte[] data)bytes=data;if(part instanceof Integer code)status=code;}
        check(status==200&&Boolean.TRUE.equals(obj(obj(Json.parse(new String(bytes,StandardCharsets.UTF_8))).get("data")).get("sessionInvalidated")),"commit not changed into postcommit 401");loginAdmin();
    }
    private static void routeChecks(String viewerToken)throws Exception {
        check(OrganizationAccountRelationships.matches(BASE)&&OrganizationAccountRelationships.matches(BASE+"/2/preview")&&!OrganizationAccountRelationships.matches(BASE+"-other")&&!OrganizationAccountRelationships.matches(null),"route namespace");
        for(String method:List.of("GET","HEAD","POST","DELETE")) {
            Exchange absent=new Exchange(method,BASE+"/2/unknown?bad",null);rejects(401,()->OrganizationAccountRelationships.preflight(absent),"anonymous before path/body");check(absent.bodyReads==0,"no anonymous body read");
            Exchange denied=new Exchange(method,BASE+"/2/unknown?bad",viewerToken);rejects(403,()->OrganizationAccountRelationships.preflight(denied),"viewer before path/body");check(denied.bodyReads==0,"no nonadmin body read");
        }
        for(String suffix:List.of("/2/unknown","/2/preview/again","/",""))rejects(404,()->OrganizationAccountRelationships.preflight(new Exchange("GET",BASE+suffix,adminToken)),"unknown child 404 after auth");
        for(String suffix:List.of("/0","/01","/+1","/-1","/1.0","/1e0","/9007199254740992","/%31","/2?","/2?x=y","/2%2fpreview"))rejects(400,()->OrganizationAccountRelationships.preflight(new Exchange("GET",BASE+suffix,adminToken)),"canonical id/path and no query");
        Exchange write=new Exchange("POST",BASE+"/2",adminToken);rejects(405,()->OrganizationAccountRelationships.preflight(write),"editor methods");check("GET, HEAD".equals(write.response.getFirst("Allow"))&&write.bodyReads==0,"method allow and no read");
        Exchange read=new Exchange("GET",BASE+"/2/preview",adminToken);rejects(405,()->OrganizationAccountRelationships.preflight(read),"preview requires POST");check("POST".equals(read.response.getFirst("Allow")),"preview Allow");
        for(String method:List.of("GET","HEAD")){Exchange valid=new Exchange(method,BASE+"/2",adminToken);OrganizationAccountRelationships.preflight(valid);check(valid.bodyReads==0&&"no-store".equals(valid.response.getFirst("Cache-Control")),"preflight valid private read");}
        String secondToken=Auth.login("admin",PASSWORD);Auth.Session second=Auth.get(secondToken);rejects(401,()->OrganizationAccountRelationships.handle(new Exchange("GET",BASE+"/2",adminToken),second),"handler rechecks exact supplied Session");Auth.logout(secondToken);
        check(!OrganizationAccountRelationships.handle(new Exchange("POST","/unrelated",null),null),"unrelated handler false");
    }
    private static void parserChecks()throws Exception {
        Map<String,Object> decoded=OrganizationAccountRelationships.parseBody(" {\n\"a\":null,\"b\":\"中文😀\",\"c\":\"\\uD83D\\uDE00\"}\r\t".getBytes(StandardCharsets.UTF_8));check(decoded.size()==3&&decoded.containsKey("a")&&decoded.get("a")==null&&decoded.get("c").equals("😀"),"strict valid flat JSON decoded");
        for(String bad:List.of("", " ","null","[]","{\"a\":1}","{\"a\":true}","{\"a\":{}}","{\"a\":[]}","{\"a\":null,\"a\":\"b\"}","{\"a\":null,\"\\u0061\":null}","{\"a\":null,}","{\"a\":null}false","{}{}","{\"a\":\"\\uD800\"}","{\"a\":\"\\uDC00\"}","{\"\\uD800\":null}","{\"a\":\"\\x20\"}","{\"a\":\"\n\"}","{\u00a0\"a\":null}","\ufeff{}","{\"a\":nullx}","{\"a\":\"\\uＦＦＦＦ\"}"))rejects(400,()->OrganizationAccountRelationships.parseBody(bad.getBytes(StandardCharsets.UTF_8)),"strict syntax rejection");
        for(byte[] bad:List.of(new byte[]{(byte)0xC0,(byte)0xAF},new byte[]{(byte)0xED,(byte)0xA0,(byte)0x80},new byte[]{(byte)0xFF}))rejects(400,()->OrganizationAccountRelationships.parseBody(bad),"malformed UTF8 rejected");
        rejects(413,()->OrganizationAccountRelationships.parseBody(new byte[4097]),"4KiB ceiling");rejects(400,()->OrganizationAccountRelationships.parseBody(null),"null body rejected");
    }
    private static Map<String,Object> editor(String person)throws Exception{return OrganizationAccountRelationships.editor(admin,IDS.get(person));}
    private static Map<String,Object> preview(String person,String leader,String bp)throws Exception{return OrganizationAccountRelationships.preview(admin,IDS.get(person),body(leader,bp));}
    private static Map<String,Object> confirm(String person,Map<String,Object> review)throws Exception{
        Map<String,Object> before=tables();List<Map<String,Object>> epochs=deviceEpochs(),devices=devices();
        Map<String,Object> result;
        try {result=OrganizationAccountRelationships.confirm(admin,IDS.get(person),token(review));}
        catch(Api.ApiException rejected){check(before.equals(tables()),"rejected confirmation changes no table, including device security state");throw rejected;}
        if(Boolean.TRUE.equals(result.get("saved")))publicationTables(before,epochs,devices,IDS.get(person));
        else check(before.equals(tables()),"unchanged confirmation changes no table, including device security state");
        return result;
    }
    private static List<Map<String,Object>> deviceEpochs()throws Exception{return Db.query("SELECT user_id,epoch FROM s01_trusted_device_epochs ORDER BY user_id");}
    private static List<Map<String,Object>> devices()throws Exception{return Db.query("SELECT * FROM s01_trusted_devices ORDER BY selector");}
    private static List<String> serializedRows(List<Map<String,Object>> rows){return rows.stream().map(Json::write).sorted().toList();}
    private static void seedDevices()throws Exception{
        check(Db.count("s01_trusted_devices")==0,"synthetic devices begin empty");long now=System.currentTimeMillis();
        for(Map<String,Object> user:Db.query("SELECT id FROM users ORDER BY id")){
            long id=((Number)user.get("id")).longValue();Map<String,Object> epoch=Db.one("SELECT epoch FROM s01_trusted_device_epochs WHERE user_id=?",id);
            Db.exec("INSERT INTO s01_trusted_devices(selector,user_id,secret_hash,fingerprint,user_epoch,created_at,last_used_at,expires_at,revoked,expired,origin) VALUES(?,?,?,?,?,?,?,?,FALSE,FALSE,?)",String.format("%032x",id),id,"0".repeat(64),"1".repeat(64),epoch==null?0:epoch.get("epoch"),now,now,now+3600000,"http://127.0.0.1:1");
        }
    }
    private static void publicationTables(Map<String,Object> before,List<Map<String,Object>> epochs,List<Map<String,Object>> devices,long affected)throws Exception{
        List<Map<String,Object>> expectedEpochs=new ArrayList<>(),expectedDevices=new ArrayList<>();boolean found=false;
        for(Map<String,Object> row:epochs){Map<String,Object> next=new LinkedHashMap<>(row);if(((Number)row.get("user_id")).longValue()==affected){next.put("epoch",((Number)row.get("epoch")).longValue()+1);found=true;}expectedEpochs.add(next);}
        check(found,"affected relationship account already has a device epoch");
        for(Map<String,Object> row:devices){Map<String,Object> next=new LinkedHashMap<>(row);if(((Number)row.get("user_id")).longValue()==affected)next.put("revoked",true);expectedDevices.add(next);}
        check(expectedEpochs.equals(deviceEpochs()),"only the affected relationship account device epoch increments exactly once");
        check(expectedDevices.equals(devices()),"only affected devices are revoked; all other device fields and accounts stay unchanged");
        Map<String,Object> after=tables(),expected=new TreeMap<>(before);
        expected.put("ORGANIZATION_ACCESS_CONFIG",after.get("ORGANIZATION_ACCESS_CONFIG"));
        expected.put("S01_TRUSTED_DEVICE_EPOCHS",serializedRows(expectedEpochs));expected.put("S01_TRUSTED_DEVICES",serializedRows(expectedDevices));
        check(expected.equals(after),"publication changes only configuration and exactly checked security rows");
    }
    private static Map<String,Object> body(String leader,String bp)throws Exception{Map<String,Object> result=new LinkedHashMap<>();result.put("expected_version",OrganizationAccessStore.configuration().version());result.put("leader_person_code",leader);result.put("bp_person_code",bp);return result;}
    private static Map<String,Object> token(Map<String,Object> review){return Map.of("review_token",review.get("review_token"));}
    private static void loginAdmin()throws Exception{adminToken=Auth.login("admin",PASSWORD);admin=Auth.get(adminToken);check(admin!=null,"real admin login");}
    private static String payload()throws Exception{return (String)Db.one("SELECT payload FROM organization_access_config WHERE active_slot=1").get("payload");}
    private static Set<String> codes(Map<String,Object> editor,String kind){Set<String> result=new HashSet<>();for(Map<String,Object> row:rows(editor.get(kind)))result.add((String)row.get("person_code"));return result;}
    private static Map<String,Object> tables()throws Exception{Map<String,Object> result=new TreeMap<>();for(Map<String,Object> row:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){String name=(String)row.get("table_name");result.put(name,Db.query("SELECT * FROM \""+name+"\"").stream().map(Json::write).sorted().toList());}return result;}
    private static String database()throws Exception{return Json.write(tables());}
    @SuppressWarnings("unchecked") private static Map<String,Object> reviews()throws Exception{var field=OrganizationAccountRelationships.class.getDeclaredField("REVIEWS");field.setAccessible(true);return (Map<String,Object>)field.get(null);}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
    private static void rejects(int expected,Work work,String label)throws Exception{try{work.run();throw new AssertionError(label);}catch(Api.ApiException rejected){check(rejected.code==expected,label+" status "+rejected.code);check(rejected.getCause()==null&&!rejected.getMessage().contains("private-source-secret")&&!rejected.getMessage().contains("missing-private-person"),label+" fixed safe failure");}}
    private static final class Exchange extends HttpExchange {
        final String method;final URI uri;final Headers request=new Headers(),response=new Headers();final Map<String,Object> attributes=new HashMap<>();int bodyReads;
        Exchange(String method,String path,String token){this.method=method;uri=URI.create(path);if(token!=null)request.set("Cookie","yx_session="+token);}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){bodyReads++;throw new AssertionError("preflight and cached handlers must never read body");}public OutputStream getResponseBody(){return OutputStream.nullOutputStream();}public void sendResponseHeaders(int status,long length){}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",0);}public int getResponseCode(){return 0;}public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",0);}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String key){return attributes.get(key);}public void setAttribute(String key,Object value){attributes.put(key,value);}public void setStreams(InputStream in,OutputStream out){}public HttpPrincipal getPrincipal(){return null;}
    }
}
