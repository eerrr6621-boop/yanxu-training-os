package com.training;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Real Auth/M01 authorization; synthetic records in one caller-owned empty H2 directory. */
public final class M03ReadAccessBridgeTest {
    private static final String PASSWORD = "SYN-READ-BRIDGE-ONLY-20260923";
    private static final String A = "SYN-A", B = "SYN-B", OFF = "SYN-OFF";
    private static final String WHEN = "2026-09-23T00:00:00Z";
    private static final Set<String> PROJECT_KEYS = Set.of("id", "title", "status", "delivery_controlled", "read_access");
    private static final Set<String> DISPATCH_KEYS = Set.of("id", "project_id", "project_title", "project_status",
            "teacher_id", "teacher_name", "subject", "teach_date", "start_time", "end_time", "venue",
            "confirm_deadline", "material_status", "hours", "status", "sent_at", "confirmed_at", "delivery_controlled", "read_access");
    private static int checks;
    private static Auth.Session admin;
    @FunctionalInterface interface Work { void run() throws Exception; }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void eq(Object expected, Object actual, String label) { check(Objects.equals(expected, actual), label + " expected=" + expected + " actual=" + actual); }
    private static void rejects(int status, Work operation, String label) throws Exception {
        try { operation.run(); throw new AssertionError(label + " did not reject"); }
        catch (Api.ApiException denied) { eq(status, denied.code, label); }
    }
    private static Auth.Session login(int id) throws Exception {
        Auth.Session session = Auth.get(Auth.login("syn-bridge-" + id, PASSWORD));
        if (session == null) throw new AssertionError("Synthetic login failed for account " + id);
        return session;
    }
    private static Grant allow(String role, String resource, Action action) {
        return new Grant("SYN-ALLOW-" + role + "-" + resource, role, resource, action, Effect.ALLOW, Scope.OWN_ORG, Set.of());
    }
    private static Grant deny(String role, String resource) {
        return new Grant("SYN-DENY-" + role + "-" + resource, role, resource, Action.VIEW, Effect.DENY, Scope.OWN_ORG, Set.of());
    }
    private static Configuration fixture(String version, boolean lateDelivery) {
        Set<String> roles = Set.of("FULL", "DELIVERY", "SUMMARY", "SURVEY", "ALLREAD", "DEMANDDENY", "NOREAD", "DELIVERYDENY", "SURVEYDENY", "LATE");
        RelationRule optional = new RelationRule(false, roles, false, false);
        List<RoleRelations> relations = roles.stream().sorted().map(role -> new RoleRelations(role, optional, optional)).toList();
        List<Person> people = new ArrayList<>(); List<AccountBinding> bindings = new ArrayList<>();
        Map<Integer,String> roleFor = Map.ofEntries(Map.entry(2,"FULL"), Map.entry(3,"DELIVERY"), Map.entry(4,"SUMMARY"),
                Map.entry(5,"SURVEY"), Map.entry(6,"ALLREAD"), Map.entry(7,"DEMANDDENY"), Map.entry(8,"NOREAD"),
                Map.entry(9,"DELIVERY"), Map.entry(10,"DELIVERY"), Map.entry(11,"DELIVERY"), Map.entry(12,"DELIVERY"),
                Map.entry(13,"DELIVERYDENY"), Map.entry(14,"SURVEYDENY"), Map.entry(15,"DELIVERY"),
                Map.entry(16,"LATE"), Map.entry(17,"DELIVERY"));
        for (int id = 2; id <= 17; id++) {
            String org = id == 9 ? B : id == 17 ? OFF : A;
            people.add(new Person("SYN-P" + id, org, Set.of(), null, null, Set.of(roleFor.get(id)), id != 10));
            if (id != 11) bindings.add(new AccountBinding(id, "SYN-P" + id, id != 12));
        }
        List<Grant> grants = new ArrayList<>();
        grants.add(allow("FULL", "demand.read", Action.VIEW));
        grants.add(allow("DELIVERY", "delivery.read", Action.VIEW));
        grants.add(allow("DELIVERY", "settlement.submit", Action.HANDLE));
        grants.add(allow("SUMMARY", "summary.read", Action.VIEW));
        grants.add(allow("SURVEY", "survey.read", Action.VIEW));
        for (String role : List.of("ALLREAD", "DEMANDDENY"))
            for (String resource : List.of("delivery.read", "summary.read", "survey.read")) grants.add(allow(role, resource, Action.VIEW));
        grants.add(deny("DEMANDDENY", "demand.read"));
        grants.add(allow("NOREAD", "settlement.submit", Action.HANDLE));
        grants.add(allow("DELIVERYDENY", "delivery.read", Action.VIEW));
        grants.add(deny("DELIVERYDENY", "delivery.read"));
        grants.add(allow("DELIVERYDENY", "summary.read", Action.VIEW));
        grants.add(allow("SURVEYDENY", "delivery.read", Action.VIEW));
        grants.add(allow("SURVEYDENY", "survey.read", Action.VIEW));
        grants.add(deny("SURVEYDENY", "survey.read"));
        if (lateDelivery) grants.add(allow("LATE", "delivery.read", Action.VIEW));
        return new Configuration(version, new CodeRules("SYN-[A-Z]+", "SYN-P[0-9]+", "[A-Z]+"), roles,
                List.of(new Organization(A, null, true), new Organization(B, null, true), new Organization(OFF, null, false)),
                people, relations, bindings, grants);
    }
    private static void publish(Configuration configuration) throws Exception {
        Configuration previous = OrganizationAccessStore.configuration();
        check(OrganizationAccess.validate(configuration).valid(), "synthetic M01 configuration is valid");
        OrganizationAccessStore.publish(admin, previous == null ? null : previous.version(), configuration);
    }

    private static void schema() throws Exception {
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id BIGINT PRIMARY KEY,title VARCHAR(200),amount DECIMAL(12,2))");
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,bid_id BIGINT,title VARCHAR(200),unit VARCHAR(200),owner VARCHAR(64),status VARCHAR(32),hours DOUBLE,amount DECIMAL(12,2),start_date VARCHAR(32),end_date VARCHAR(32),participant_count INT,delivery_mode VARCHAR(32),venue VARCHAR(200),contract_no VARCHAR(64),remark VARCHAR(500))");
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,name VARCHAR(64),phone VARCHAR(32),fee_rate DECIMAL(12,2),status VARCHAR(32))");
        Db.exec("CREATE TABLE dispatches(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,subject VARCHAR(200),teach_date VARCHAR(32),start_time VARCHAR(16),end_time VARCHAR(16),venue VARCHAR(200),confirm_deadline VARCHAR(32),material_status VARCHAR(32),hours DOUBLE,status VARCHAR(32),sent_at VARCHAR(32),confirmed_at VARCHAR(32),msg_log VARCHAR(500),remark VARCHAR(500))");
        Db.exec("CREATE TABLE bids(id BIGINT PRIMARY KEY,demand_id BIGINT,amount DECIMAL(12,2))");
        for (String table : List.of("charges", "fees", "costs", "teacher_evals", "questionnaires"))
            Db.exec("CREATE TABLE " + table + "(id BIGINT PRIMARY KEY,project_id BIGINT,amount DECIMAL(12,2))");
        Db.exec("CREATE TABLE q_sends(id BIGINT PRIMARY KEY,questionnaire_id BIGINT)");
        Db.exec("CREATE TABLE q_responses(id BIGINT PRIMARY KEY,questionnaire_id BIGINT)");
        // An intentionally unconstrained test copy permits duplicate/orphan corruption fixtures.
        // Production uniqueness/FK DDL is not being tested or changed by this isolated fixture.
        Db.exec("CREATE TABLE workflow_acceptances(demand_id BIGINT,project_id BIGINT,team_code VARCHAR(120),actor_code VARCHAR(120),data_revision BIGINT,created_at VARCHAR(40))");
        Db.exec("CREATE TABLE m05_delivery_facts(project_id BIGINT)");
        OrganizationAccessStore.init(); WorkflowIntegration.init();
        String hash = Auth.hash(PASSWORD);
        for (int id = 1; id <= 17; id++) Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)", id, "syn-bridge-" + id, hash, "SYN ACCOUNT " + id, id == 1 ? "admin" : "viewer");
    }
    private static void project(long id, Long demand, String org, boolean accepted) throws Exception {
        if (demand != null) Db.exec("INSERT INTO demands VALUES(?,?,?)", demand, "SYN DEMAND " + demand, new BigDecimal("12345.67"));
        Db.exec("INSERT INTO projects VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, demand, null, "SYN PROJECT " + id,
                "SYN UNIT", "SYN OWNER", "进行中", 4, new BigDecimal(id == 108 ? "800.00" : id == 109 ? "900.00" : "9999.99"),
                "2026-09-20", "2026-09-21", 20, "线下集中", "SYN VENUE", "SYN-CONTRACT-SECRET", "SYN PROJECT PRIVATE FREE TEXT");
        if (org != null) {
            Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct',?,'SYN-P3','{}','{}','SYN-RULE')", demand, org);
            if (accepted) Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'SYN-TEAM','SYN-P3',1,?)", demand, id, WHEN);
        }
        Db.exec("INSERT INTO dispatches VALUES(?,?,901,?,'2026-09-20','09:00','12:00','SYN VENUE','2026-09-19','已就绪',4,'已确认',?,?,'SYN PRIVATE MESSAGE LOG','SYN PRIVATE DISPATCH REMARK')",
                id + 200, id, "SYN CLASS " + id, WHEN, WHEN);
    }
    private static void seed() throws Exception {
        Db.exec("INSERT INTO teachers VALUES(901,'SYN TEACHER','SYN-PRIVATE-PHONE',6543.21,'在库')");
        project(101, 201L, A, true); project(102, 202L, B, true); project(103, 203L, A, false);
        project(104, 204L, A, true); project(105, 205L, A, true); project(106, 206L, A, true);
        project(107, 207L, A, true); project(108, null, null, false); project(109, 209L, null, false);
        project(110, 210L, "SYN-UNKNOWN", true); project(111, 211L, A, true); project(112, 212L, A, true);
        project(113, 213L, A, true); project(114, 214L, OFF, true);
        Db.exec("UPDATE projects SET demand_id=201 WHERE id=104"); // mismatched acceptance, both workflow rows exist.
        Db.exec("UPDATE workflow_acceptances SET data_revision=2 WHERE project_id=105");
        Db.exec("UPDATE workflow_demands SET draft=TRUE WHERE demand_id=206");
        Db.exec("UPDATE projects SET demand_id=NULL WHERE id=107"); // orphan acceptance must not become legacy.
        Db.exec("INSERT INTO workflow_acceptances SELECT demand_id,project_id,team_code,actor_code,data_revision,created_at FROM workflow_acceptances WHERE project_id=111");
        Db.exec("UPDATE workflow_acceptances SET created_at='SYN-INVALID-TIMESTAMP' WHERE project_id=112");
        Db.exec("UPDATE workflow_demands SET version=0 WHERE demand_id=213");
        Db.exec("INSERT INTO bids VALUES(401,201,7777.77)");
        for (String table : List.of("charges", "fees", "costs", "teacher_evals", "questionnaires")) Db.exec("INSERT INTO " + table + " VALUES(501,101,8888.88)");
        Db.exec("INSERT INTO q_sends VALUES(601,501)"); Db.exec("INSERT INTO q_responses VALUES(701,501)");
    }
    private static List<Map<String,Object>> rows(String module, long id) throws Exception {
        List<Map<String,Object>> result = module.equals("projects")
                ? Db.query("SELECT * FROM projects WHERE id=?", id)
                : Db.query("SELECT d.*,p.title AS project_title,p.status AS project_status,t.name AS teacher_name,t.phone AS teacher_phone,t.fee_rate FROM dispatches d JOIN projects p ON p.id=d.project_id LEFT JOIN teachers t ON t.id=d.teacher_id WHERE d.id=?", id);
        for (Map<String,Object> row : result) {
            // Simulates a future server SELECT/decorator adding fields unknown to the bridge.
            row.put("unexpected_future_secret", "SYN FUTURE SECRET");
            row.put("financial", Map.of("amount", "4567.89"));
            row.put("delivery_settlement", Map.of("balance", "3456.78"));
            row.put("read_access", Map.of("write", true));
        }
        return result;
    }
    private static List<Map<String,Object>> listed(String module, long id, Auth.Session actor) throws Exception {
        return WorkflowReadAccessBridge.listRows(module, rows(module, id), actor);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> accessMeta(Map<String,Object> row) { return (Map<String,Object>) row.get("read_access"); }
    private static Map<String,Object> one(String module, long id, Auth.Session actor) throws Exception {
        List<Map<String,Object>> result = listed(module, id, actor); eq(1, result.size(), module + " one visible row " + id); return result.get(0);
    }
    private static void limited(Map<String,Object> row, Set<String> expectedKeys, boolean delivery, boolean summary, boolean feedback, String label) {
        eq(expectedKeys, row.keySet(), label + " exact whitelist");
        eq(Map.of("limited", true, "delivery", delivery, "summary", summary, "feedback", feedback), accessMeta(row), label + " independent read metadata");
        eq(true, row.get("delivery_controlled"), label + " valid managed source is controlled");
        for (String key : List.of("amount", "teacher_phone", "fee_rate", "msg_log", "remark", "financial", "delivery_settlement", "delivery_hours", "workflow_source", "workflow", "unexpected_future_secret", "can_write", "write"))
            check(!row.containsKey(key), label + " excludes " + key);
    }

    private static void projections() throws Exception {
        Auth.Session delivery = login(3);
        check(OrganizationAccessStore.authorize(delivery,"settlement.submit",Action.HANDLE,A).allowed(), "delivery fixture can submit settlement separately");
        check(!OrganizationAccessStore.authorize(delivery,"demand.read",Action.VIEW,A).allowed(), "delivery fixture has no demand read");
        Map<String,Object> project = one("projects",101,delivery), dispatch = one("dispatches",301,delivery);
        limited(project,PROJECT_KEYS,true,false,false,"delivery project"); limited(dispatch,DISPATCH_KEYS,true,false,false,"delivery dispatch");
        eq("SYN PROJECT 101", project.get("title"), "project title preserved"); eq("SYN TEACHER", dispatch.get("teacher_name"), "schedule teacher label preserved");
        eq(4.0, dispatch.get("hours"), "schedule planned hours preserved");
        List<Map<String,Object>> source = rows("projects",101); String sourceBefore = Json.write(source);
        WorkflowReadAccessBridge.listRows("projects",source,delivery); eq(sourceBefore,Json.write(source),"projection does not mutate server query rows");
        limited(one("projects",101,login(4)),PROJECT_KEYS,false,true,false,"summary-only project");
        eq(0,listed("dispatches",301,login(4)).size(),"summary read does not grant schedule read");
        limited(one("projects",101,login(5)),PROJECT_KEYS,false,false,true,"survey-only project");
        eq(0,listed("dispatches",301,login(5)).size(),"survey read does not grant schedule read");
        limited(one("projects",101,login(6)),PROJECT_KEYS,true,true,true,"independent all-read project");
        eq(0,listed("projects",101,login(8)).size(),"settlement submit alone grants no project read");
        eq(0,listed("dispatches",301,login(8)).size(),"settlement submit alone grants no schedule read");
        limited(one("projects",101,login(13)),PROJECT_KEYS,false,true,false,"delivery deny leaves independently allowed summary");
        eq(0,listed("dispatches",301,login(13)).size(),"explicit delivery deny overrides its allow");
        limited(one("projects",101,login(14)),PROJECT_KEYS,true,false,false,"survey deny is not overridden by delivery read");
    }

    private static void denialsAndSources() throws Exception {
        Auth.Session denied = login(7);
        Decision decision = OrganizationAccessStore.authorize(denied,"demand.read",Action.VIEW,A);
        check(decision.status() == Status.DENIED && !decision.matchedRuleIds().isEmpty(), "explicit demand denial has matching policy IDs");
        eq(0,listed("projects",101,denied).size(),"explicit demand deny blocks project fallback");
        eq(0,listed("dispatches",301,denied).size(),"explicit demand deny blocks schedule fallback");
        for (int account : List.of(9,10,11,12,17)) {
            eq(0,listed("projects",101,login(account)).size(),"wrong org/disabled person/unbound/disabled binding account " + account);
            eq(0,listed("dispatches",301,login(account)).size(),"wrong org/disabled identity schedule " + account);
        }
        limited(one("projects",102,login(9)),PROJECT_KEYS,true,false,false,"other org can see its own accepted project");
        eq(0,listed("projects",102,login(3)).size(),"delivery read cannot cross organization");
        Auth.Session delivery = login(3);
        for (long project : List.of(103L,104L,105L,106L,107L,110L,111L,112L,113L,114L)) {
            eq(0,listed("projects",project,delivery).size(),"invalid source/unknown organization project " + project);
            eq(0,listed("dispatches",project+200,delivery).size(),"invalid source/unknown organization schedule " + project);
        }
        Map<String,Object> forgedForeignKey = new LinkedHashMap<>(rows("dispatches",302).get(0)); forgedForeignKey.put("project_id",101L);
        eq(0,WorkflowReadAccessBridge.listRows("dispatches",List.of(forgedForeignKey),delivery).size(),"stored dispatch association defeats substituted project scope");
        Map<String,Object> ghost = new LinkedHashMap<>(rows("projects",101).get(0)); ghost.put("id",999999L);
        eq(0,WorkflowReadAccessBridge.listRows("projects",List.of(ghost),delivery).size(),"nonexistent stored project cannot gain authority from row fields");
        Map<String,Object> stringId = new LinkedHashMap<>(rows("projects",101).get(0)); stringId.put("id","101");
        eq(0,WorkflowReadAccessBridge.listRows("projects",List.of(stringId),delivery).size(),"untrusted string ID is not server numeric ID");
    }

    private static void legacyCompatibility() throws Exception {
        Auth.Session full = login(2);
        for (String module : List.of("projects","dispatches")) {
            long id = module.equals("projects") ? 101 : 301;
            List<Map<String,Object>> expected = IntegrationDeliveryHost.decorateRows(module,WorkflowIntegration.visibleRows(module,rows(module,id),full),full);
            List<Map<String,Object>> actual = listed(module,id,full);
            eq(expected,actual,"demand reader retains exact former final " + module + " response");
            check(actual.get(0).containsKey("unexpected_future_secret"),"full former response is not silently narrowed " + module);
            eq(true,actual.get(0).get("delivery_controlled"),"full former response retains delivery decorator " + module);
        }
        check(one("projects",101,full).containsKey("amount"),"existing demand-read financial field remains compatible");
        Auth.Session noRead = login(8);
        for (long id : List.of(108L,109L)) {
            for (String module : List.of("projects","dispatches")) {
                long record = module.equals("projects") ? id : id+200;
                List<Map<String,Object>> expected = IntegrationDeliveryHost.decorateRows(module,WorkflowIntegration.visibleRows(module,rows(module,record),noRead),noRead);
                eq(expected,listed(module,record,noRead),"genuine old project retains prior behavior " + module + ":" + record);
            }
        }
    }

    private static void existingGatesUnchanged() throws Exception {
        Auth.Session delivery = login(3);
        for (String table : List.of("demands","bids","charges","fees","costs","teacher_evals","questionnaires","q_sends","q_responses")) {
            String sql = table.equals("demands") ? "SELECT * FROM demands WHERE id=201" : "SELECT * FROM " + table;
            eq(0,WorkflowReadAccessBridge.listRows(table,Db.query(sql),delivery).size(),"delivery fallback does not open existing " + table + " reads");
        }
        rejects(403,()->WorkflowIntegration.requireProjectAccess(101,delivery,true),"delivery read does not open project writes");
        rejects(403,()->WorkflowIntegration.requireProjectAccess(101,delivery,false),"delivery list bridge does not open existing project-check gate");
        eq(0,WorkflowIntegration.visibleRows("projects",rows("projects",101),delivery).size(),"old project visibility remains unchanged");
        eq(0,WorkflowIntegration.visibleRows("dispatches",rows("dispatches",301),delivery).size(),"old schedule visibility remains unchanged");
    }
    private static void hiddenFieldFilters() throws Exception {
        Auth.Session delivery=login(3),full=login(2);
        List<Map<String,Object>> titleRows=Db.query("SELECT * FROM projects WHERE id=101 AND (title LIKE ? OR unit LIKE ?)","%PROJECT 101%","%PROJECT 101%");
        eq(1,WorkflowReadAccessBridge.listRows("projects",titleRows,delivery,Map.of("kw","PROJECT 101")).size(),"limited project search keeps a genuine title match");
        List<Map<String,Object>> unitRows=Db.query("SELECT * FROM projects WHERE id=101 AND (title LIKE ? OR unit LIKE ?)","%SYN UNIT%","%SYN UNIT%");
        eq(1,unitRows.size(),"old SQL can match hidden unit without title");
        eq(0,WorkflowReadAccessBridge.listRows("projects",unitRows,delivery,Map.of("kw","SYN UNIT")).size(),"limited search cannot infer hidden unit from a match");
        eq(1,WorkflowReadAccessBridge.listRows("projects",unitRows,full,Map.of("kw","SYN UNIT")).size(),"existing full reader retains old unit search");
        for(String demand:List.of("201","202")) {
            List<Map<String,Object>> matchingRows=Db.query("SELECT * FROM projects WHERE id=101 AND demand_id=?",Long.valueOf(demand));
            eq(0,WorkflowReadAccessBridge.listRows("projects",matchingRows,delivery,Map.of("demand_id",demand)).size(),"limited demand filter discloses no result for " + demand);
            eq(matchingRows.size(),WorkflowReadAccessBridge.listRows("projects",matchingRows,full,Map.of("demand_id",demand)).size(),"full reader retains old demand filtering for " + demand);
        }
        eq(0,WorkflowReadAccessBridge.listRows("projects",rows("projects",101),delivery,Map.of("demand_id","202")).size(),"even mismatching supplied source filter cannot leave a candidate visible");
        for(long id:List.of(103L,104L,105L,106L,111L)) {
            List<Map<String,Object>> expected=IntegrationDeliveryHost.decorateRows("projects",WorkflowIntegration.visibleRows("projects",rows("projects",id),full),full);
            eq(expected,listed("projects",id,full),"full demand reader retains old behavior for damaged accepted source " + id);
        }
    }
    private static BigDecimal oldProjectAmount(Auth.Session actor) throws Exception {
        return new BigDecimal(Db.one("SELECT COALESCE(SUM(amount),0) amount FROM " + WorkflowLegacyAccess.scopedTable("projects",actor)).get("amount").toString());
    }
    private static void grantDoesNotExpandStatistics() throws Exception {
        Auth.Session before = login(16);
        eq(0,listed("projects",101,before).size(),"late reader starts without project candidate");
        BigDecimal amountBefore = oldProjectAmount(before);
        eq(new BigDecimal("11699.99"),amountBefore,"pre-bridge monetary baseline preserves the old legacy/orphan semantics");
        long countBefore = WorkflowLegacyAccess.count("projects",before);
        publish(fixture("SYN-BRIDGE-v2",true));
        Auth.Session after = login(16);
        limited(one("projects",101,after),PROJECT_KEYS,true,false,false,"newly granted delivery candidate");
        eq(amountBefore,oldProjectAmount(after),"adding delivery list access does not expand old monetary statistics");
        eq(countBefore,WorkflowLegacyAccess.count("projects",after),"adding delivery list access does not expand old statistic row scope");
        rejects(403,()->WorkflowIntegration.requireProjectAccess(101,after,false),"new grant leaves check gate closed");
        rejects(403,()->WorkflowIntegration.requireProjectAccess(101,after,true),"new grant leaves write gate closed");
    }
    private static void sessions() throws Exception {
        rejects(401,()->WorkflowReadAccessBridge.listRows("projects",rows("projects",101),null),"null session rejected");
        Auth.Session forged = new Auth.Session(); forged.uid=3; forged.username="syn-bridge-3"; forged.role="admin";
        rejects(401,()->WorkflowReadAccessBridge.listRows("projects",rows("projects",101),forged),"forged matching account session rejected");
        rejects(401,()->WorkflowReadAccessBridge.listRows("charges",Db.query("SELECT * FROM charges"),forged),"other-module legacy branch also rejects forged session");
        Auth.Session tampered = login(3); tampered.uid=2;
        rejects(401,()->WorkflowReadAccessBridge.listRows("projects",rows("projects",101),tampered),"tampered issued uid cannot obtain demand-reader access");
        Auth.Session disabled = login(15); Db.exec("UPDATE users SET status=0 WHERE id=15");
        rejects(401,()->WorkflowReadAccessBridge.listRows("projects",rows("projects",101),disabled),"disabled database account invalidates actual session");
        Db.exec("UPDATE users SET status=1 WHERE id=15");
        String token=Auth.login("syn-bridge-3",PASSWORD);Auth.Session expired=Auth.get(token);Auth.logout(token);
        rejects(401,()->WorkflowReadAccessBridge.listRows("dispatches",rows("dispatches",301),expired),"logged-out real session rejected");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("One fresh empty synthetic data directory required");
        Path data = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(data) || Files.isSymbolicLink(data)) throw new IllegalArgumentException("Existing nonsymlink directory required");
        try (var files = Files.list(data)) { if (files.findAny().isPresent()) throw new IllegalArgumentException("Data directory must be empty"); }
        System.setProperty("data.dir",data.toString()); System.setProperty("login.email.mode","legacy");
        try {
            schema(); seed(); admin=login(1); publish(fixture("SYN-BRIDGE-v1",false));
            projections(); denialsAndSources(); legacyCompatibility(); existingGatesUnchanged(); hiddenFieldFilters(); grantDoesNotExpandStatistics(); sessions();
            System.out.println("M03ReadAccessBridgeTest: " + checks + " checks passed (real Auth/M01; isolated SYN-only H2)");
        } finally { Db.get().close(); }
    }
}
