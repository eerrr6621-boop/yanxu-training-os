package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static com.training.OrganizationAccess.*;

/** Real Auth/M01/Db integration against a fresh caller-owned synthetic H2 only. No server or Db.init. */
public final class M08IntegrationTest {
    private static final String PASSWORD = "SYNTHETIC-M08-ONLY";
    private static Auth.Session admin, worker, peer, other, reader, editOnly, unrelated, unbound;
    private static String workerToken;
    private static int passed, failed, checks, configurationVersion;
    private static Map<String,Object> initialRequest, initialResult;

    @FunctionalInterface interface Work { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void equal(Object expected, Object actual, String label) { check(Objects.equals(expected, actual), label); }
    private static void scenario(String name, Work work) {
        try { work.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable failure) {
            failed++;
            Throwable detail=failure instanceof ExecutionException&&failure.getCause()!=null?failure.getCause():failure;
            String safeDetail=detail instanceof AssertionError?": "+detail.getMessage():detail instanceof Api.ApiException?" status="+((Api.ApiException)detail).code:"";
            StackTraceElement location=detail.getStackTrace().length==0?null:detail.getStackTrace()[0];
            System.out.println("FAIL " + name + ": " + detail.getClass().getSimpleName()+safeDetail+(location==null?"":" at "+location.getFileName()+":"+location.getLineNumber()));
        }
    }
    private static void rejects(int status, Work work, String label) throws Exception {
        checks++;
        try { work.run(); } catch (Api.ApiException expected) {
            if (expected.code == status) return;
            throw new AssertionError(label + " (unexpected status " + expected.code + ")");
        }
        throw new AssertionError(label + " (expected status " + status + ")");
    }
    private static void rejectsSource(Work work,String label) throws Exception {
        checks++;
        try {work.run();}catch(Api.ApiException failure){if(failure.code==403||failure.code==409)return;throw new AssertionError(label+" (unexpected status "+failure.code+")");}
        throw new AssertionError(label+" (expected closed source gate)");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    private static long number(Object value) { return ((Number)value).longValue(); }
    private static long version(Map<String,Object> value) { return number(value.get("version")); }
    private static Map<String,Object> current(Map<String,Object> value) { return map(value.get("current")); }
    private static Map<String,Object> content(Map<String,Object> value) { return map(current(value).get("content")); }
    private static Map<String,Object> copy(Map<String,Object> value) { return map(Json.parse(Json.write(value))); }
    private static Map<String,Object> read(long id) throws Exception { return TrainingSummariesIntegration.read(worker,id); }
    private static Map<String,Object> call(String operation, Map<String,Object> body) throws Exception { return TrainingSummariesIntegration.mutate(operation,worker,body); }
    private static Map<String,Object> command(long id,long expected,String request) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("project_id",id); result.put("expected_version",expected); result.put("request_id",request); return result;
    }
    private static Map<String,Object> content(String text) {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("achievements",text);
        result.put("issues", ""); result.put("nextSteps", "");
        result.put("publicity",Map.of("title","SYNTHETIC TITLE","introduction",text,
            "sections",List.of(Map.of("heading","SYNTHETIC SECTION","body",text)),"photoCaptions",List.of("SYNTHETIC CAPTION")));
        return result;
    }
    private static Map<String,Object> saveBody(long id,long expected,String request,String text) {
        Map<String,Object> result=command(id,expected,request);result.put("content",content(text));return result;
    }
    private static Map<String,Object> save(long id,String request,String text) throws Exception {
        return call("save",saveBody(id,version(read(id)),request,text));
    }
    private static long rows(String table,long project) throws Exception {
        return number(Db.one("SELECT COUNT(*) AS n FROM " + table + " WHERE project_id=?",project).get("n"));
    }
    private static List<Long> counts(long project) throws Exception {
        return List.of(rows("m08_summary_heads",project),rows("m08_summary_revisions",project),rows("m08_summary_requests",project));
    }
    private static void capabilities(Map<String,Object> view,boolean edit) {
        Map<String,Object> caps=map(view.get("capabilities"));
        equal(true,caps.get("read"),"read capability"); equal(edit,caps.get("edit"),"edit capability");
        equal(edit&&view.get("current")!=null,caps.get("refresh_sources"),"refresh capability");
        for(String name:List.of("submit","review","export"))equal(false,caps.get(name),"formal action remains unavailable: "+name);
    }
    private static Configuration configuration(Set<String> flags) {
        Set<String> roles=Set.of("OPERATOR","READER","EDITOR","UNRELATED");
        RelationRule optional=new RelationRule(false,roles,false,false);
        List<Grant> grants=new ArrayList<>();
        if(!flags.contains("no-read"))grants.add(new Grant("OPERATOR-READ","OPERATOR","summary.read",flags.contains("wrong-read")?Action.HANDLE:Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        if(!flags.contains("no-edit"))grants.add(new Grant("OPERATOR-EDIT","OPERATOR","summary.edit",flags.contains("wrong-edit")?Action.VIEW:Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        if(flags.contains("deny-edit"))grants.add(new Grant("OPERATOR-DENY","OPERATOR","summary.edit",Action.HANDLE,Effect.DENY,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("READER-READ","READER","summary.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("EDITOR-EDIT","EDITOR","summary.edit",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("DEMAND-READ","UNRELATED","demand.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("DEMAND-WRITE","UNRELATED","demand.write",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("DELIVERY-READ","UNRELATED","delivery.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        List<RoleRelations> relations=new ArrayList<>();for(String role:roles)relations.add(new RoleRelations(role,optional,optional));
        return new Configuration("M08-TEST-"+(++configurationVersion),new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),roles,
            List.of(new Organization("001",null,!flags.contains("disabled-org")),new Organization("002",null,true)),
            List.of(new Person("0002","001",Set.of(),null,null,Set.of("OPERATOR"),!flags.contains("disabled-person")),
                    new Person("0003","001",Set.of(),null,null,Set.of("OPERATOR"),true),
                    new Person("0004","002",Set.of(),null,null,Set.of("OPERATOR"),true),
                    new Person("0006","001",Set.of(),null,null,Set.of("READER"),true),
                    new Person("0007","001",Set.of(),null,null,Set.of("OPERATOR"),true),
                    new Person("0008","001",Set.of(),null,null,Set.of("EDITOR"),true),
                    new Person("0009","001",Set.of(),null,null,Set.of("UNRELATED"),true)),relations,
            List.of(new AccountBinding(2,flags.contains("rebound")?"0007":"0002",!flags.contains("disabled-binding")),
                    new AccountBinding(3,"0003",true),new AccountBinding(4,"0004",true),new AccountBinding(6,"0006",true),
                    new AccountBinding(8,"0008",true),new AccountBinding(9,"0009",true)),grants);
    }
    private static void publish(String... flags) throws Exception {
        Configuration old=OrganizationAccessStore.configuration();
        OrganizationAccessStore.publish(admin,old==null?null:old.version(),configuration(Set.of(flags)));
        // Check current authorization after explicit reauthentication; revoked references stay invalid.
        if(Auth.current(worker)==null)loginWorker();
        if(Auth.current(peer)==null)peer=login(3);
        if(Auth.current(other)==null)other=login(4);
        if(Auth.current(reader)==null)reader=login(6);
        if(Auth.current(editOnly)==null)editOnly=login(8);
        if(Auth.current(unrelated)==null)unrelated=login(9);
    }
    private static Auth.Session login(int id) throws Exception { return Auth.get(Auth.login("synthetic-m08-"+id,PASSWORD)); }
    private static void loginWorker() throws Exception { workerToken=Auth.login("synthetic-m08-2",PASSWORD);worker=Auth.get(workerToken);check(worker!=null,"real worker session"); }
    private static void fixtures(Path data) throws Exception {
        System.setProperty("data.dir",data.toString());
        // Db.get creates only this explicitly supplied empty database. Never call Db.init or production seed code.
        check(Db.get().getMetaData().getURL().contains(data.resolve("training").toString()),"isolated H2 path");
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,title VARCHAR(200),status VARCHAR(32),start_date VARCHAR(32),end_date VARCHAR(32),participant_count INT)");
        Db.exec("CREATE TABLE workflow_demands(demand_id BIGINT PRIMARY KEY,organization_code VARCHAR(120),data_revision BIGINT,version BIGINT,draft BOOLEAN)");
        // No synthetic uniqueness constraint on project_id: the adapter must detect ambiguous legacy acceptance chains.
        Db.exec("CREATE TABLE workflow_acceptances(demand_id BIGINT PRIMARY KEY,project_id BIGINT,data_revision BIGINT,team_code VARCHAR(120),actor_code VARCHAR(120),created_at VARCHAR(40))");
        String hash=Auth.hash(PASSWORD);
        for(int id:new int[]{1,2,3,4,5,6,8,9})Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",id,"synthetic-m08-"+id,hash,"SYNTHETIC USER",id==1?"admin":id==5?"manager":"viewer");
        for(long id=101;id<=116;id++) {
            long demand=id-90;
            Db.exec("INSERT INTO workflow_demands VALUES(?,?,1,1,FALSE)",demand,id==102?"002":"001");
            Db.exec("INSERT INTO projects VALUES(?,?,?,'进行中','2026-09-14','2026-09-15',24)",id,demand,"SYNTHETIC PROJECT");
            if(id!=103)Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,1,'TEAM-A','0002','2026-09-16T00:00:00Z')",demand,id);
        }
        Db.exec("UPDATE projects SET demand_id=999 WHERE id=104");
        Db.exec("UPDATE workflow_demands SET data_revision=2 WHERE demand_id=15");
        Db.exec("UPDATE workflow_demands SET draft=TRUE WHERE demand_id=16");
        Db.exec("INSERT INTO workflow_demands VALUES(27,'001',1,1,FALSE)");
        Db.exec("INSERT INTO workflow_acceptances VALUES(27,107,1,'TEAM-B','0003','2026-09-16T00:00:00Z')");
        admin=login(1);loginWorker();peer=login(3);other=login(4);unbound=login(5);reader=login(6);editOnly=login(8);unrelated=login(9);
        OrganizationAccessStore.init();TrainingSummariesIntegration.init();TrainingSummariesIntegration.init();
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("One fresh empty synthetic H2 directory is required");
        Path data=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("Directory must exist and not be a symlink");
        try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Directory must be empty");}
        try {
            fixtures(data);
            scenario("missing M01 configuration fails closed",()->rejects(403,()->read(101),"unconfigured read"));
            publish();
            scenario("initialization creates no summary or request rows",()->{
                for(String table:List.of("m08_summary_heads","m08_summary_revisions","m08_summary_requests"))equal(0L,Db.count(table),"no seed rows");
                Map<String,Object> empty=read(101);equal(0L,version(empty),"empty version");equal(null,empty.get("current"),"no invented current revision");
                equal(false,empty.get("synthetic"),"live adapter never labels its data synthetic");equal("001",empty.get("organization_code"),"trusted organization");capabilities(empty,true);
                Map<String,Object> history=TrainingSummariesIntegration.history(worker,101,0,20);equal(0L,number(history.get("total")),"empty history");equal(0,((List<?>)history.get("items")).size(),"empty history items");
            });
            scenario("dedicated permissions and current real sessions",M08IntegrationTest::authorizationBasics);
            scenario("unique accepted current non-draft source is required",()->{
                for(long id:List.of(103L,104L,105L,106L,107L))rejectsSource(()->read(id),"untrusted chain");
            });
            scenario("strict request and nested content whitelist",M08IntegrationTest::requestBoundaries);
            scenario("create save and frozen historical revision",M08IntegrationTest::createAndSave);
            scenario("unavailable upstream is not confirmed empty",M08IntegrationTest::sourceStatus);
            scenario("explicit source refresh preserves authored content",M08IntegrationTest::sourceRefresh);
            scenario("request replay is stable and account scoped",M08IntegrationTest::idempotency);
            scenario("all historical reads and retries reauthorize",M08IntegrationTest::revocation);
            scenario("account rebinding cannot recover previous request",M08IntegrationTest::rebinding);
            scenario("archival preserves history and blocks writes including retries",M08IntegrationTest::archive);
            scenario("legacy delete and association mutation guards",M08IntegrationTest::legacyGuards);
            scenario("concurrent distinct requests have one CAS winner",M08IntegrationTest::concurrentCas);
            scenario("concurrent identical request returns one durable revision",M08IntegrationTest::concurrentIdempotency);
            scenario("failed final request insert rolls back all summary writes",M08IntegrationTest::transactionRollback);
            scenario("history pagination and individual revision checks",M08IntegrationTest::historyBoundaries);
            scenario("HTTP route authentication query and action gates without a listener",M08IntegrationTest::httpBoundaries);
            scenario("connection reopen and repeated init preserve stored history",M08IntegrationTest::reopen);
            scenario("corrupt revisions and head fail closed",M08IntegrationTest::corruption);
            check(Db.count("projects")==16&&Db.count("workflow_demands")==17&&Db.count("workflow_acceptances")==16,"business fixtures retain row counts");
        } finally {
            try { Db.exec("SHUTDOWN"); } catch (SQLException ignored) { /* Only the test-owned H2 is in use. */ }
        }
        System.out.println("M08Integration: "+passed+" scenarios passed, "+failed+" failed; "+checks+" checks (isolated synthetic H2)");
        if(failed!=0)throw new AssertionError("M08 integration scenarios failed");
    }

    private static void authorizationBasics() throws Exception {
        capabilities(TrainingSummariesIntegration.read(reader,101),false);
        check("viewer".equals(worker.role),"writer has no legacy administrative role");
        for(Auth.Session denied:List.of(admin,unbound,editOnly,unrelated)) {
            rejects(403,()->TrainingSummariesIntegration.read(denied,101),"no implicit summary read");
            rejects(403,()->TrainingSummariesIntegration.mutate("save",denied,saveBody(101,0,"no-implicit","SYNTHETIC")),"no implicit summary write");
        }
        rejects(403,()->TrainingSummariesIntegration.mutate("save",reader,saveBody(101,0,"reader-write","SYNTHETIC")),"read cannot write");
        rejects(403,()->read(102),"cross organization read");
        rejects(403,()->call("save",saveBody(102,0,"cross-write","SYNTHETIC")),"cross organization write");
        capabilities(TrainingSummariesIntegration.read(other,102),true);
        Auth.Session fake=new Auth.Session();fake.uid=worker.uid;fake.role="admin";
        rejects(401,()->TrainingSummariesIntegration.read(fake,101),"forged session");
        rejects(401,()->TrainingSummariesIntegration.read(null,101),"missing session");
        for(String flag:List.of("wrong-read","wrong-edit","deny-edit"))try {
            publish(flag);
            rejects(403,()->call("save",saveBody(101,0,"wrong-action","SYNTHETIC")),"exact action required");
        } finally { publish(); }
    }
    private static void requestBoundaries() throws Exception {
        for(Object invalid:List.of(0,-1,1.5,Double.NaN,"101",9007199254740992L)) {
            Map<String,Object> body=saveBody(101,0,"invalid-id","SYNTHETIC");body.put("project_id",invalid);
            rejects(400,()->call("save",body),"strict project number");
        }
        for(Object invalid:List.of(-1,0.5,Double.NaN,"0",9007199254740992L)) {
            Map<String,Object> body=saveBody(101,0,"invalid-version","SYNTHETIC");body.put("expected_version",invalid);
            rejects(400,()->call("save",body),"strict version number");
        }
        for(String field:List.of("organization_code","actor_code","account_id","synthetic","status","sources","capabilities","revision")) {
            Map<String,Object> body=saveBody(101,0,"injection-"+field,"SYNTHETIC");body.put(field,"SYNTHETIC-INJECTION");
            rejects(400,()->call("save",body),"authority injection rejected");
        }
        for(String field:List.of("identityMap","photoUrl","projectId")) {
            Map<String,Object> body=saveBody(101,0,"nested-"+field,"SYNTHETIC");map(body.get("content")).put(field,"SYNTHETIC-INJECTION");
            rejects(400,()->call("save",body),"unknown content field rejected");
        }
        Map<String,Object> missing=command(101,0,"missing-content");rejects(400,()->call("save",missing),"save requires full content");
        Map<String,Object> nullContent=command(101,0,"null-content");nullContent.put("content",null);rejects(400,()->call("save",nullContent),"save rejects null content");
        Map<String,Object> invalidText=saveBody(101,0,"invalid-control","SYNTHETIC");map(invalidText.get("content")).put("achievements","\u0001");rejects(400,()->call("save",invalidText),"invalid Unicode/control rejected");
        for(Object invalid:Arrays.asList(null,"","has space","x".repeat(97),123)) {
            Map<String,Object> body=saveBody(101,0,"invalid-request","SYNTHETIC");body.put("request_id",invalid);rejects(400,()->call("save",body),"bounded string request identity");
        }
        int photoCase=0;
        for(String invalid:List.of("//cdn.example.test/photo.png","blob:synthetic-local-id","data:image/png;base64,AAAA","file:/synthetic/photo.png","C:\\synthetic\\photo.png","photo.jpeg","x".repeat(301),"x".repeat(500000))) {
            Map<String,Object> body=copy(saveBody(101,0,"photo-"+(photoCase++),"SYNTHETIC"));map(map(body.get("content")).get("publicity")).put("photoCaptions",List.of(invalid));
            rejects(400,()->call("save",body),"photo captions reject addresses files and excessive length");
        }
        for(String op:List.of("submit","review","export"))rejects(400,()->call(op,command(101,0,"unsupported-"+op)),"formal action blocked");
        equal(List.of(0L,0L,0L),counts(101),"rejected inputs leave no partial records");
    }
    private static void createAndSave() throws Exception {
        initialRequest=saveBody(101,0,"initial-save","SYNTHETIC ORIGINAL");initialResult=call("save",initialRequest);
        equal(1L,version(initialResult),"created version");equal(1L,number(current(initialResult).get("revision")),"created revision");
        equal(101L,number(initialResult.get("project_id")),"correct project");equal("001",initialResult.get("organization_code"),"persisted organization");
        equal(false,initialResult.get("synthetic"),"real adapter marker");capabilities(initialResult,true);
        String frozen=Json.write(TrainingSummariesIntegration.revision(worker,101,1));
        Map<String,Object> second=save(101,"second-save","SYNTHETIC SECOND");equal(2L,version(second),"saved second version");
        equal(frozen,Json.write(TrainingSummariesIntegration.revision(worker,101,1)),"first revision immutable");
        equal("SYNTHETIC ORIGINAL",content(initialResult).get("achievements"),"original returned map retained");
        Map<String,Object> replace=command(101,2,"replace-content");replace.put("content",Map.of("publicity",Map.of("title","SYNTHETIC REPLACED","introduction","SYNTHETIC REPLACED","sections",List.of(),"photoCaptions",List.of())));
        Map<String,Object> third=call("save",replace);for(String key:List.of("achievements","issues","nextSteps"))equal("",content(third).get(key),"omitted old field is empty on replacement");
        equal(List.of(1L,3L,3L),counts(101),"one head and append-only revisions/requests");
        rejects(409,()->call("save",saveBody(101,1,"stale-save","SYNTHETIC")),"stale CAS");
    }
    private static void sourceStatus() throws Exception {
        Map<String,Object> sources=map(current(read(101)).get("sources"));
        Map<String,Object> feedback=map(sources.get("feedback"));equal("UNAVAILABLE",feedback.get("status"),"M07 unavailable");equal(null,feedback.get("value"),"no fabricated M07 result");
        equal("M07_PREVIEW_ONLY",feedback.get("reason"),"M07 preview limitation");
        for(String field:List.of("policyConfirmed","canCommit","historyAvailable"))equal(false,feedback.get(field),"M07 limitation flags");
        Map<String,Object> delivery=map(sources.get("delivery"));equal("UNAVAILABLE",delivery.get("status"),"M05 not authorized");equal(null,delivery.get("value"),"no inferred delivery hours");equal("DELIVERY_READ_REQUIRED",delivery.get("reason"),"independent M05 permission required");
        Map<String,Object> project=map(sources.get("project")),facts=map(project.get("value")),codes=map(sources.get("codes"));
        equal("AVAILABLE",project.get("status"),"trusted project facts available");
        equal(null,facts.get("project_code"),"unassigned project code remains null");equal(null,facts.get("course_codes"),"unassigned course codes remain null");
        equal("UNASSIGNED",codes.get("project"),"unassigned project code marker");equal("UNASSIGNED",codes.get("courses"),"unassigned course code marker");
        equal(101L,number(facts.get("project_id")),"project source links same project");equal(11L,number(map(project.get("provenance")).get("demand_id")),"accepted demand provenance");
        check(!Json.write(sources).contains("DEMO-"),"no fabricated formal codes");
    }
    private static void sourceRefresh() throws Exception {
        Map<String,Object> before=save(115,"source-initial","SYNTHETIC KEEP CONTENT");String original=Json.write(TrainingSummariesIntegration.revision(worker,115,1));
        Map<String,Object> contentBefore=copy(content(before));String sourcesBefore=Json.write(current(before).get("sources"));
        Db.exec("UPDATE projects SET start_date='2026-09-17',end_date='2026-09-18',participant_count=31 WHERE id=115");
        Map<String,Object> saved=save(115,"save-with-old-source","SYNTHETIC KEEP CONTENT");
        equal(sourcesBefore,Json.write(current(saved).get("sources")),"ordinary save keeps frozen source despite upstream change");equal(true,saved.get("source_changed"),"source drift is visible");
        Map<String,Object> refreshed=call("refresh",command(115,2,"explicit-refresh"));equal(3L,version(refreshed),"refresh increments revision");
        equal(Json.write(contentBefore),Json.write(content(refreshed)),"refresh preserves all content");
        check(!sourcesBefore.equals(Json.write(current(refreshed).get("sources"))),"refresh captures updated project source");
        equal(original,Json.write(TrainingSummariesIntegration.revision(worker,115,1)),"old source snapshot immutable");
        Map<String,Object> injected=command(115,3,"refresh-with-content");injected.put("content",content("SYNTHETIC REPLACE"));rejects(400,()->call("refresh",injected),"refresh cannot replace body");
    }
    private static void idempotency() throws Exception {
        long before=version(read(101));List<Long> rowCounts=counts(101);
        Map<String,Object> retry=call("save",copy(initialRequest));equal(1L,version(retry),"retry returns original version");
        equal(Json.write(current(initialResult)),Json.write(current(retry)),"retry returns original durable revision");equal(rowCounts,counts(101),"retry adds no records");
        equal(true,retry.get("replayed"),"explicit replay flag");equal(before,number(retry.get("latest_version")),"replay carries latest head");equal(true,retry.get("reload_required"),"stale replay requires reread");
        Map<String,Object> different=copy(initialRequest);map(different.get("content")).put("achievements","SYNTHETIC DIFFERENT");rejects(409,()->call("save",different),"request payload reuse rejected");
        Map<String,Object> otherProject=copy(initialRequest);otherProject.put("project_id",114);rejects(409,()->call("save",otherProject),"request cannot move project");
        Map<String,Object> peerRequest=saveBody(101,before,"initial-save","SYNTHETIC PEER");
        Map<String,Object> peerResult=TrainingSummariesIntegration.mutate("save",peer,peerRequest);equal(before+1,version(peerResult),"other account has independent request namespace");
        rejects(409,()->call("refresh",command(101,0,"initial-save")),"request cannot change operation");
    }
    private static void revocation() throws Exception {
        for(String flag:List.of("no-read","disabled-person","disabled-binding","disabled-org"))try {
            publish(flag);
            rejects(403,()->read(101),"revoked current read");rejects(403,()->TrainingSummariesIntegration.history(worker,101,0,20),"revoked history");
            rejects(403,()->TrainingSummariesIntegration.revision(worker,101,1),"revoked individual revision");rejects(403,()->call("save",initialRequest),"retry reauthorizes read");
        } finally { publish(); }
        try { publish("no-edit");capabilities(read(101),false);rejects(403,()->call("save",initialRequest),"retry reauthorizes write"); }
        finally { publish(); }
        rejects(403,()->TrainingSummariesIntegration.history(other,101,0,20),"cross organization history");
        rejects(403,()->TrainingSummariesIntegration.revision(other,101,1),"cross organization revision");
        Auth.Session fake=new Auth.Session();fake.uid=2;fake.role="admin";
        rejects(401,()->TrainingSummariesIntegration.history(fake,101,0,20),"forged history session");
        rejects(401,()->TrainingSummariesIntegration.revision(fake,101,1),"forged revision session");
        Auth.logout(workerToken);rejects(401,()->call("save",initialRequest),"logged out retry");loginWorker();
        Db.exec("UPDATE users SET status=0 WHERE id=2");
        try { rejects(401,()->read(101),"disabled current account");rejects(401,()->TrainingSummariesIntegration.history(worker,101,0,20),"disabled history"); }
        finally { Db.exec("UPDATE users SET status=1 WHERE id=2"); }
        rejects(401,()->read(101),"reenabled account cannot resurrect revoked session");loginWorker();
    }
    private static void rebinding() throws Exception {
        try { publish("rebound");rejects(409,()->call("save",initialRequest),"same account different bound actor cannot replay"); }
        finally { publish(); }
        equal(1L,version(call("save",initialRequest)),"original binding can replay its own saved request");
        Db.exec("UPDATE workflow_demands SET organization_code='002' WHERE demand_id=11");
        try { rejects(403,()->call("save",initialRequest),"source organization drift reevaluates scope"); }
        finally { Db.exec("UPDATE workflow_demands SET organization_code='001' WHERE demand_id=11"); }
        Db.exec("UPDATE projects SET demand_id=23 WHERE id=101");
        try { rejectsSource(()->read(101),"accepted demand linkage drift");rejectsSource(()->call("save",initialRequest),"replay validates linkage"); }
        finally { Db.exec("UPDATE projects SET demand_id=11 WHERE id=101"); }
    }
    private static void archive() throws Exception {
        Map<String,Object> body=saveBody(111,0,"archive-initial","SYNTHETIC ARCHIVE");Map<String,Object> before=call("save",body);
        String frozen=Json.write(TrainingSummariesIntegration.revision(worker,111,1));
        TrainingSummariesIntegration.guardLegacyProjectMutation("archive",111,Map.of("status","已归档"));
        equal("进行中",Db.one("SELECT status FROM projects WHERE id=111").get("status"),"guard itself performs no archive");
        Db.exec("UPDATE projects SET status='已归档' WHERE id=111");
        Map<String,Object> archived=read(111);equal(version(before),version(archived),"archive keeps revision");capabilities(archived,false);
        equal(frozen,Json.write(TrainingSummariesIntegration.revision(worker,111,1)),"archive preserves historical source");
        rejects(409,()->call("save",body),"archived idempotent retry blocked");
        rejects(409,()->call("save",saveBody(111,1,"archive-save","SYNTHETIC CHANGED")),"archived save blocked");
        rejects(409,()->call("refresh",command(111,1,"archive-refresh")),"archived refresh blocked");
        rejects(409,()->TrainingSummariesIntegration.guardLegacyProjectMutation("update",111,Map.of("status","进行中")),"legacy update cannot silently unarchive");
        rejects(409,()->TrainingSummariesIntegration.guardLegacyProjectMutation("update",111,Map.of("title","SYNTHETIC CHANGED")),"archived source is read-only");
    }
    private static void legacyGuards() throws Exception {
        rejects(409,()->TrainingSummariesIntegration.guardLegacyProjectMutation("delete",101,null),"summary referenced project cannot be deleted");
        rejects(409,()->TrainingSummariesIntegration.guardLegacyProjectMutation("update",101,Map.of("demand_id",23)),"summary cannot be rebound to demand");
        TrainingSummariesIntegration.guardLegacyProjectMutation("update",101,Map.of("demand_id",11));
        TrainingSummariesIntegration.guardLegacyProjectMutation("update",101,Map.of("demand_id","11"));
        rejects(409,()->TrainingSummariesIntegration.guardLegacyProjectMutation("update",101,Map.of("demand_id","23")),"legacy textual demand cannot rebind");
        rejects(400,()->TrainingSummariesIntegration.guardLegacyProjectMutation("update",101,Map.of("demand_id","11.5")),"legacy demand cannot truncate fraction");
        TrainingSummariesIntegration.guardLegacyProjectMutation("delete",116,null);
        check(true,"unreferenced deletion and unchanged demand can proceed");
        String before=Json.write(TrainingSummariesIntegration.revision(worker,101,1));
        TrainingSummariesIntegration.guardLegacyProjectMutation("update",101,Map.of("title","SYNTHETIC TITLE EDIT"));
        equal(before,Json.write(TrainingSummariesIntegration.revision(worker,101,1)),"legacy guard never edits stored summary source");
    }
    private static void concurrentCas() throws Exception {
        save(108,"cas-initial","SYNTHETIC INITIAL");
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
        try {
            for(int i=0;i<2;i++) { final int index=i;results.add(executor.submit(()->{
                start.await();try{call("save",saveBody(108,1,"cas-"+index,"SYNTHETIC CONCURRENT "+index));return true;}
                catch(Api.ApiException conflict){if(conflict.code!=409)throw conflict;return false;}
            })); }
            start.countDown();int wins=0;for(Future<Boolean> result:results)if(result.get(20,TimeUnit.SECONDS))wins++;
            equal(1,wins,"exactly one CAS winner");equal(2L,version(read(108)),"one new revision");equal(List.of(1L,2L,2L),counts(108),"CAS loser has no partial rows");
        } finally {executor.shutdownNow();check(executor.awaitTermination(5,TimeUnit.SECONDS),"CAS workers terminate");}
    }
    private static void concurrentIdempotency() throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Map<String,Object>>> results=new ArrayList<>();
        try {
            for(int i=0;i<2;i++)results.add(executor.submit(()->{start.await();return call("save",saveBody(109,0,"same-concurrent-request","SYNTHETIC SAME"));}));
            start.countDown();Map<String,Object> first=results.get(0).get(20,TimeUnit.SECONDS),second=results.get(1).get(20,TimeUnit.SECONDS);
            equal(1L,version(first),"concurrent first result");equal(1L,version(second),"concurrent retry result");
            equal(Json.write(current(first)),Json.write(current(second)),"same durable result");equal(List.of(1L,1L,1L),counts(109),"one durable request identity");
        } finally {executor.shutdownNow();check(executor.awaitTermination(5,TimeUnit.SECONDS),"idempotency workers terminate");}
    }
    private static void transactionRollback() throws Exception {
        Map<String,Object> before=save(110,"rollback-initial","SYNTHETIC INITIAL");List<Long> countBefore=counts(110);
        Map<String,Object> body=saveBody(110,1,"rollback-failure","SYNTHETIC FAILED");
        Db.exec("ALTER TABLE m08_summary_requests ADD CONSTRAINT m08_test_rollback CHECK(request_id<>'rollback-failure')");
        boolean rejected=false;
        try { call("save",body); }
        catch(SQLException expected) { rejected=true; }
        catch(Api.ApiException expected) { check(expected.code>=400,"database error is an unsuccessful response");rejected=true; }
        finally { Db.exec("ALTER TABLE m08_summary_requests DROP CONSTRAINT m08_test_rollback"); }
        check(rejected,"forced final insert failed");equal(countBefore,counts(110),"all partial writes rolled back");
        equal(Json.write(current(before)),Json.write(current(read(110))),"current revision unchanged after rollback");equal(1L,version(read(110)),"head CAS rolled back");
        equal(2L,version(call("save",body)),"failed request id is usable after rollback");check(Db.get().getAutoCommit(),"transaction restores auto commit");
        Map<String,Object> create=saveBody(113,0,"rollback-create","SYNTHETIC FAILED CREATE");
        Db.exec("ALTER TABLE m08_summary_requests ADD CONSTRAINT m08_test_create_rollback CHECK(request_id<>'rollback-create')");
        boolean createRejected=false;
        try {call("save",create);}catch(SQLException expected){createRejected=true;}catch(Api.ApiException expected){createRejected=true;}
        finally {Db.exec("ALTER TABLE m08_summary_requests DROP CONSTRAINT m08_test_create_rollback");}
        check(createRejected,"forced terminal failure on create");equal(List.of(0L,0L,0L),counts(113),"failed creation leaves no head revision or request");
        equal(1L,version(call("save",create)),"creation retry after rollback succeeds");
    }
    private static void historyBoundaries() throws Exception {
        Map<String,Object> history=TrainingSummariesIntegration.history(worker,101,1,2);
        equal(rows("m08_summary_revisions",101),number(history.get("total")),"history total");equal(1L,number(history.get("offset")),"history offset");equal(2L,number(history.get("limit")),"history limit");
        equal(2,((List<?>)history.get("items")).size(),"bounded page");
        Set<Long> revisions=new HashSet<>();for(Object item:(List<?>)history.get("items")) {
            Map<String,Object> metadata=map(item);check(revisions.add(number(metadata.get("revision"))),"unique page revisions");check(!metadata.containsKey("content")&&!metadata.containsKey("content_json"),"history list is metadata only");
        }
        Map<String,Object> beyond=TrainingSummariesIntegration.history(worker,101,1000,20);equal(0,((List<?>)beyond.get("items")).size(),"past end is empty");
        rejects(400,()->TrainingSummariesIntegration.history(worker,101,-1,20),"negative offset");rejects(400,()->TrainingSummariesIntegration.history(worker,101,0,0),"zero limit");
        rejects(400,()->TrainingSummariesIntegration.history(worker,101,0,10001),"unbounded history limit");
        rejects(404,()->TrainingSummariesIntegration.revision(worker,101,9999),"nonexistent revision");
    }
    private static void reopen() throws Exception {
        String head=Json.write(read(101)),history=Json.write(TrainingSummariesIntegration.history(worker,101,0,20)),revision=Json.write(TrainingSummariesIntegration.revision(worker,101,1));
        Db.get().close();TrainingSummariesIntegration.init();TrainingSummariesIntegration.init();
        equal(head,Json.write(read(101)),"head survives reopen");equal(history,Json.write(TrainingSummariesIntegration.history(worker,101,0,20)),"history survives reopen");equal(revision,Json.write(TrainingSummariesIntegration.revision(worker,101,1)),"immutable revision survives reopen");
        equal(1L,version(call("save",initialRequest)),"request replay survives reopen");
    }
    private static void httpBoundaries() throws Exception {
        String base="/api/training-summaries",readRoute=base+"?project_id=101";
        check(TrainingSummariesIntegration.matches(base)&&TrainingSummariesIntegration.matches(base+"/history"),"owned path prefix");
        check(!TrainingSummariesIntegration.matches(base+"-adjacent")&&!TrainingSummariesIntegration.matches("/api/projects"),"adjacent paths excluded");
        check(!TrainingSummariesIntegration.handle(new Exchange("GET","/api/not-m08",null,null),null),"unrelated handler falls through");
        rejects(401,()->TrainingSummariesIntegration.handle(new Exchange("GET",readRoute,null,null),worker),"supplied session cannot replace token");
        String peerToken=Auth.login("synthetic-m08-3",PASSWORD);
        try { rejects(401,()->TrainingSummariesIntegration.handle(new Exchange("GET",readRoute,peerToken,null),worker),"token and supplied session must match"); }
        finally { Auth.logout(peerToken); }
        for(String route:List.of(readRoute,base+"/history?project_id=101&offset=0&limit=2",base+"/revision?project_id=101&revision=1")) {
            Exchange exchange=new Exchange("GET",route,workerToken,null);check(TrainingSummariesIntegration.handle(exchange,worker),"valid GET recognized");
            check(httpResponse(exchange).containsKey("data"),"normal API response envelope");
        }
        for(String query:List.of("project_id=101&project_id=102","project_id=101&organization_code=001","project_id=101&%70roject_id=101"))
            rejects(400,()->TrainingSummariesIntegration.handle(new Exchange("GET",base+"?"+query,workerToken,null),worker),"ambiguous or authority query blocked");
        rejects(405,()->TrainingSummariesIntegration.handle(new Exchange("DELETE",readRoute,workerToken,null),worker),"read method restricted");
        rejects(404,()->TrainingSummariesIntegration.handle(new Exchange("GET",base+"/unknown",workerToken,null),worker),"unknown subroute");
        for(String operation:List.of("submit","review","export"))rejects(400,()->TrainingSummariesIntegration.handle(new Exchange("POST",base+"/"+operation,workerToken,command(101,version(read(101)),"http-"+operation)),worker),"formal action blocked over HTTP");
        Exchange wrongType=new Exchange("POST",base+"/save",workerToken,saveBody(116,0,"http-wrong-type","SYNTHETIC"));wrongType.getRequestHeaders().set("Content-Type","text/plain");
        rejects(415,()->TrainingSummariesIntegration.handle(wrongType,worker),"content type restricted");
        rejects(400,()->TrainingSummariesIntegration.handle(new Exchange("POST",base+"/save?project_id=116",workerToken,saveBody(116,0,"http-query","SYNTHETIC")),worker),"POST does not accept ownership query");
        equal(List.of(0L,0L,0L),counts(116),"rejected HTTP writes leave no rows");
        Exchange saving=new Exchange("POST",base+"/save",workerToken,copy(saveBody(116,0,"http-save","SYNTHETIC HTTP")));
        check(TrainingSummariesIntegration.handle(saving,worker),"valid save handler");equal(1L,version(map(httpResponse(saving).get("data"))),"HTTP saved revision");
        Exchange refreshing=new Exchange("POST",base+"/refresh",workerToken,copy(command(116,1,"http-refresh")));
        check(TrainingSummariesIntegration.handle(refreshing,worker),"valid refresh handler");equal(2L,version(map(httpResponse(refreshing).get("data"))),"HTTP refreshed revision");
        equal(List.of(1L,2L,2L),counts(116),"successful HTTP actions persisted once each");
    }
    private static Map<String,Object> httpResponse(Exchange exchange) throws Exception {
        Object response=exchange.getAttribute("com.training.Api.response");check(response!=null,"response staged without network IO");
        java.lang.reflect.Field field=response.getClass().getDeclaredField("body");field.setAccessible(true);
        return map(Json.parse(new String((byte[])field.get(response),StandardCharsets.UTF_8)));
    }
    private static final class Exchange extends HttpExchange {
        private final String method;private final URI uri;private final Headers request=new Headers(),response=new Headers();private final Map<String,Object> attributes=new HashMap<>();
        Exchange(String method,String path,String token,Map<String,Object> body) {
            this.method=method;uri=URI.create(path);if(token!=null)request.set("X-Token",token);request.set("Content-Type","application/json");
            if(body!=null)attributes.put("com.training.Api.body",body);
        }
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}
        public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return InputStream.nullInputStream();}public OutputStream getResponseBody(){return OutputStream.nullOutputStream();}
        public void sendResponseHeaders(int code,long length){}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}public int getResponseCode(){return 0;}
        public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",1);}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String key){return attributes.get(key);}
        public void setAttribute(String key,Object value){attributes.put(key,value);}public void setStreams(InputStream input,OutputStream output){}public HttpPrincipal getPrincipal(){return null;}
    }
    private static void corruption() throws Exception {
        save(112,"corrupt-initial","SYNTHETIC INTACT");save(112,"corrupt-second","SYNTHETIC INTACT SECOND");
        Map<String,Object> original=Db.one("SELECT * FROM m08_summary_revisions WHERE project_id=112 AND revision=1");
        Db.exec("UPDATE m08_summary_revisions SET content_json='{}' WHERE project_id=112 AND revision=1");
        try {
            rejects(409,()->read(112),"tampered historical body invalidates head");rejects(409,()->TrainingSummariesIntegration.history(worker,112,0,20),"tampered history rejected");
            rejects(409,()->TrainingSummariesIntegration.revision(worker,112,1),"tampered revision rejected");rejects(409,()->save(112,"corrupt-save","SYNTHETIC"),"cannot append to corrupt lineage");
        } finally {Db.exec("UPDATE m08_summary_revisions SET content_json=? WHERE project_id=112 AND revision=1",original.get("content_json"));}
        String hash=String.valueOf(Db.one("SELECT head_hash FROM m08_summary_heads WHERE project_id=112").get("head_hash"));
        Db.exec("UPDATE m08_summary_heads SET head_hash=? WHERE project_id=112","0".repeat(64));
        try {rejects(409,()->read(112),"head hash mismatch");}finally{Db.exec("UPDATE m08_summary_heads SET head_hash=? WHERE project_id=112",hash);}
        equal(2L,version(read(112)),"restored valid chain readable");
    }
}
