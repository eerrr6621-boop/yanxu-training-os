package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.api.Trigger;
import static com.training.OrganizationAccess.*;

/** Synthetic accounts/materials only. Requires caller-owned empty temporary H2/source directories. */
public final class ManagementSupplementIntakeTest {
    private static int checks;
    private static final String PASSWORD="SYNTHETIC-SUPPLEMENT-ONLY-20260923";
    private static Auth.Session admin,viewer;
    private static long adminId,viewerId;
    private static OrganizationAccountImportSource.Config oldConfig;
    private static Map<String,Object> oldReceipt;
    private static OrganizationAccountImport oldImporter;
    private static Map<String,Object> oldRequest;
    private static Auth.Session oldActor;
    private static String preservedOld;
    private interface Work { void run() throws Exception; }
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void rejects(int code,Work work,String label)throws Exception{
        try{work.run();throw new AssertionError("Expected rejection: "+label);}
        catch(Api.ApiException error){check(error.code==code,label+" (actual="+error.code+")");}
    }
    private static Map<String,Object> m(Object...values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    @SuppressWarnings("unchecked") private static Map<String,Object> o(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private static long n(Object value){return ((Number)value).longValue();}
    private static Map<String,Object> copy(Map<String,Object> value){return o(Json.parse(Json.write(value)));}
    private static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static Map<String,Object> createRequest(Map<String,Object> preview){
        List<Object> choices=new ArrayList<>();
        for(Map<String,Object> row:rows(preview.get("rows")))choices.add(m("reference",row.get("reference"),"action","CREATE_PENDING","accountId",null,"accountProof",null,"reviewed",true));
        return m("expectedRevision",preview.get("revision"),"sourceFingerprint",preview.get("sourceFingerprint"),"reviewToken",preview.get("reviewToken"),"decisions",choices);
    }
    private static long user(String username,String role,int status)throws Exception{return Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,?)",username,Auth.hash(PASSWORD),"SYNTHETIC "+username,role,status);}
    private static String allState()throws Exception{return hash(Json.write(m("users",Db.query("SELECT * FROM users ORDER BY id"),"revision",Db.query("SELECT * FROM organization_account_import_state"),"batches",Db.query("SELECT * FROM organization_account_import_batches ORDER BY batch_key"),"people",Db.query("SELECT * FROM organization_account_import_people ORDER BY person_code"),"config",Db.query("SELECT * FROM organization_access_config ORDER BY id"),"teachers",Db.query("SELECT * FROM teachers ORDER BY id"),"teacherReceipts",Db.query("SELECT * FROM teacher_roster_import_batches ORDER BY batch_key"))));}
    private static String oldState()throws Exception{return hash(Json.write(m("batch",Db.query("SELECT * FROM organization_account_import_batches WHERE batch_key=?",oldConfig.batchKey()),"people",Db.query("SELECT * FROM organization_account_import_people WHERE batch_key=? ORDER BY source_reference",oldConfig.batchKey()),"users",Db.query("SELECT u.* FROM users u JOIN organization_account_import_people p ON p.account_id=u.id WHERE p.batch_key=? ORDER BY u.id",oldConfig.batchKey()),"teachers",Db.query("SELECT * FROM teachers ORDER BY id"),"teacherReceipts",Db.query("SELECT * FROM teacher_roster_import_batches ORDER BY batch_key"),"teacherPeople",Db.query("SELECT * FROM teacher_roster_import_people ORDER BY teacher_id"))));}
    private static String configurationState()throws Exception{return Json.write(Db.query("SELECT * FROM organization_access_config ORDER BY id"));}
    private static void unchanged(String before,String label)throws Exception{check(before.equals(allState()),label);check(preservedOld.equals(oldState()),label+" retains old intake/teachers");}
    private static Configuration published(String version){
        RelationRule optional=new RelationRule(false,Set.of("FILLER"),false,false);
        return new Configuration(version,new CodeRules("O[0-9]+","P[0-9]+","[A-Z]+"),Set.of("FILLER"),List.of(new Organization("O1",null,true)),List.of(new Person("P1","O1",Set.of(),null,null,Set.of("FILLER"),true)),List.of(new RoleRelations("FILLER",optional,optional)),List.of(new AccountBinding(viewerId,"P1",false)),List.of());
    }

    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Fresh empty synthetic data/source directories required");
        Path data=Path.of(args[0]).toAbsolutePath(),sources=Path.of(args[1]).toAbsolutePath();
        for(Path dir:List.of(data,sources)){if(!Files.isDirectory(dir))throw new IllegalArgumentException("Missing temporary directory");try(var files=Files.list(dir)){if(files.findAny().isPresent())throw new IllegalArgumentException("Only empty temporary directories accepted");}}
        System.setProperty("data.dir",data.toString());System.setProperty("bootstrap.demo","false");
        try{
            Db.exec("CREATE TABLE users(id IDENTITY PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            adminId=user("synthetic-supplement-admin","admin",1);viewerId=user("synthetic-supplement-viewer","viewer",1);
            Db.init();OrganizationAccessStore.init();OrganizationAccountImport.init();TeacherRosterImport.init();
            admin=Auth.get(Auth.login("synthetic-supplement-admin",PASSWORD));viewer=Auth.get(Auth.login("synthetic-supplement-viewer",PASSWORD));
            check(admin!=null&&viewer!=null,"real Auth.login in isolated H2");
            prepareOldData(sources.resolve("legacy"));
            OrganizationAccessStore.publish(admin,null,published("SYNTHETIC-CONFIG-1"));
            viewer=Auth.get(Auth.login("synthetic-supplement-viewer",PASSWORD));
            check(viewer!=null,"fresh real viewer session after formal-binding publication revokes old session");
            preservedOld=oldState();
            // Source fixture adapter is deliberately independent of the production source documents.
            ManagementSupplementSourceTest.Fixture fixture=new ManagementSupplementSourceTest.Fixture(sources.resolve("supplement"),oldConfig,oldReceipt);
            OrganizationManagementSupplementSource.Config config=fixture.save();
            String before=allState();OrganizationManagementSupplementHost host=new OrganizationManagementSupplementHost(config);
            unchanged(before,"trusted host constructor does not initialize data or mutate config");
            authentication(host);
            validateAndRollback(host);
            staleSnapshots(host);
            changedSourceAndRestoredToken(host,config);
            receiveAndReplay(host,config,sources);
            check(preservedOld.equals(oldState()),"legacy receipt/intake/users/181 teachers/teacher receipt unchanged across suite");
            System.out.println("ManagementSupplementIntake: "+checks+" checks passed (synthetic temporary H2; no HTTP or production data)");
        }finally{if(!Db.get().isClosed())Db.exec("SHUTDOWN");}
    }

    private static void prepareOldData(Path path)throws Exception{
        OrganizationAccountImportSourceTest.Fixture fixture=new OrganizationAccountImportSourceTest.Fixture(path);
        o(fixture.cp.get("teacher")).put("historyRows",List.of(12,20));
        List<Map<String,Object>> histories=new ArrayList<>(),projections=new ArrayList<>();
        String teacherPath=fixture.originals.get(0).path().toString();
        for(int row=12;row<=20;row++){
            histories.add(m("row",row,"name","SYNTHETIC EXCLUDED "+row,"organization","SYNTHETIC BRANCH A","account_disposition","DO_NOT_CREATE","source",m("path",teacherPath,"sheet","Synthetic Teachers","source_row",row,"range","A"+row+":F"+row)));
            projections.add(m("source",m("sheet","Synthetic Teachers","range","A"+row+":F"+row,"row",row),"disposition","DO_NOT_CREATE"));
        }
        fixture.batch.put("historical_exclusions",histories);fixture.preview.put("historicalExclusions",projections);o(fixture.preview.get("summary")).put("historicalExcluded",9);
        oldConfig=fixture.save();oldImporter=new OrganizationAccountImport(oldConfig);oldActor=admin;
        oldRequest=createRequest(oldImporter.preview(admin));oldReceipt=oldImporter.commit(admin,oldRequest);
        check(n(o(oldReceipt.get("summary")).get("candidates"))==7,"legacy synthetic fixture uses actual source/import pipeline");
        check(n(o(oldReceipt.get("summary")).get("historicalExcluded"))==9,"legacy nine synthetic history rows excluded");
        for(int i=1;i<=181;i++)Db.exec("INSERT INTO teachers(name,org,title,status) VALUES(?,?,?,'在库')","SYNTHETIC TEACHER "+i,"SYNTHETIC TEACHER ORG","SYNTHETIC TITLE");
        // Explicitly simulated existing teacher receipt: this test never claims to import 181 profiles.
        Db.exec("INSERT INTO teacher_roster_import_batches(batch_key,source_fingerprint,profiles_digest,accounts_digest,receipt,created_by) VALUES(?,?,?,?,?,?)","synthetic-teachers-existing-181","a".repeat(64),"b".repeat(64),"c".repeat(64),Json.write(m("synthetic",true,"teachers",181,"legacyAccountBatch",oldConfig.batchKey())),adminId);
        check(Db.count("teachers")==181,"181 synthetic preexisting teacher rows prepared");
    }

    private static void authentication(OrganizationManagementSupplementHost host)throws Exception{
        String before=allState();Auth.Session forged=new Auth.Session();forged.uid=adminId;forged.role="admin";
        rejects(401,()->host.preview(null),"anonymous preview rejected");rejects(403,()->host.preview(viewer),"viewer preview rejected");
        rejects(401,()->host.preview(forged),"forged known account identity rejected");rejects(401,()->host.account(forged,viewerId),"forged lookup rejected");
        rejects(401,()->host.commit(forged,Map.of()),"forged commit rejected");rejects(401,()->host.received(forged),"forged received projection rejected");
        rejects(403,()->host.received(viewer),"viewer cannot read private receipt projection");rejects(409,()->host.received(admin),"unreceived batch cannot prove mapping");
        unchanged(before,"auth checks leave data unchanged");
    }

    private static void validateAndRollback(OrganizationManagementSupplementHost host)throws Exception{
        Map<String,Object> preview=host.preview(admin),request=createRequest(preview);String before=allState();
        check(rows(preview.get("rows")).size()==2,"only two supplemental candidates");
        check(n(o(preview.get("summary")).get("historicalExcluded"))==9,"nine historical exclusions retained");
        check(rows(preview.get("branchCoverage")).isEmpty(),"management supplement has no invented branch coverage");
        check(n(o(preview.get("summary")).get("approvalPeople"))==0,"account intake grants no approval roles");
        for(Map<String,Object> row:rows(preview.get("rows"))){check(rows(row.get("approvalRoles")).isEmpty(),"no inferred approval role");check(!row.containsKey("staff_id")&&!row.containsKey("source_staff_id"),"preview contains no employee ID");}
        Map<String,Object> unchecked=copy(request);rows(unchecked.get("decisions")).get(0).put("reviewed",false);rejects(400,()->host.commit(admin,unchecked),"per-person review mandatory");
        Map<String,Object> missing=copy(request);rows(missing.get("decisions")).remove(1);rejects(400,()->host.commit(admin,missing),"candidate cannot be omitted");
        Map<String,Object> oldRef=copy(request);rows(oldRef.get("decisions")).get(0).put("reference","teacher-row-3");rejects(400,()->host.commit(admin,oldRef),"old reference cannot replace supplemental candidate");
        Map<String,Object> extra=copy(request);rows(extra.get("decisions")).add(m("reference","teacher-row-3","action","CREATE_PENDING","accountId",null,"accountProof",null,"reviewed",true));rejects(400,()->host.commit(admin,extra),"old candidate cannot be appended to supplement");
        Map<String,Object> history=copy(request);rows(history.get("decisions")).get(0).put("reference","teacher-row-12");rejects(400,()->host.commit(admin,history),"historical reference cannot enter candidate decisions");
        Map<String,Object> duplicate=copy(request);rows(duplicate.get("decisions")).set(1,rows(duplicate.get("decisions")).get(0));rejects(400,()->host.commit(admin,duplicate),"duplicate source ref rejected");
        Map<String,Object> privilege=copy(request);rows(privilege.get("decisions")).get(0).put("role","admin");rejects(400,()->host.commit(admin,privilege),"decision cannot assign admin");
        Map<String,Object> activation=copy(request);activation.put("accountsActivated",true);rejects(400,()->host.commit(admin,activation),"unknown activation field rejected");
        Auth.Session second=Auth.get(Auth.login("synthetic-supplement-admin",PASSWORD));rejects(409,()->host.commit(second,request),"review bound to exact authenticated session");
        check(Boolean.FALSE.equals(host.account(admin,viewerId).get("available")),"disabled formal binding still reserves account");
        long legacyId=n(rows(oldReceipt.get("rows")).get(0).get("accountId"));check(Boolean.FALSE.equals(host.account(admin,legacyId).get("available")),"legacy intake account cannot be rebound");
        unchanged(before,"input validation cannot mutate users or config");
        FailSecondPending.count=0;Db.exec("CREATE TRIGGER synthetic_supplement_second BEFORE INSERT ON users FOR EACH ROW CALL 'com.training.ManagementSupplementIntakeTest$FailSecondPending'");
        try{rejects(500,()->host.commit(admin,createRequest(host.preview(admin))),"second pending insert forced failure");}finally{Db.exec("DROP TRIGGER synthetic_supplement_second");}
        unchanged(before,"second insert failure rolls back user/intake/receipt/revision");
        Db.exec("CREATE TRIGGER synthetic_supplement_cas AFTER INSERT ON organization_account_import_batches FOR EACH ROW CALL 'com.training.ManagementSupplementIntakeTest$ForceCasFailure'");
        try{rejects(409,()->host.commit(admin,createRequest(host.preview(admin))),"final revision CAS forced failure");}finally{Db.exec("DROP TRIGGER synthetic_supplement_cas");}
        unchanged(before,"CAS failure rolls back entire transaction");
    }

    private static void staleSnapshots(OrganizationManagementSupplementHost host)throws Exception{
        Map<String,Object> request=createRequest(host.preview(admin));Db.exec("UPDATE users SET name='SYNTHETIC CHANGED VIEWER' WHERE id=?",viewerId);String before=allState();
        rejects(409,()->host.commit(admin,request),"users changed after preview invalidates snapshot");unchanged(before,"stale users cannot commit");
        Map<String,Object> configRequest=createRequest(host.preview(admin));OrganizationAccessStore.publish(admin,"SYNTHETIC-CONFIG-1",published("SYNTHETIC-CONFIG-2"));before=allState();
        rejects(409,()->host.commit(admin,configRequest),"configuration changed after preview invalidates snapshot");unchanged(before,"stale config cannot commit");
        Map<String,Object> revoked=createRequest(host.preview(admin));Db.exec("UPDATE users SET role='viewer' WHERE id=?",adminId);before=allState();
        rejects(403,()->host.commit(admin,revoked),"admin revoked before commit");unchanged(before,"revoked admin cannot mutate");Db.exec("UPDATE users SET role='admin' WHERE id=?",adminId);
        admin=Auth.get(Auth.login("synthetic-supplement-admin",PASSWORD));
    }

    private static void changedSourceAndRestoredToken(OrganizationManagementSupplementHost host,OrganizationManagementSupplementSource.Config config)throws Exception{
        for(String component:List.of("groupsOriginal","teacherOriginal","projection","decision","originalReceipt")){
            Map<String,Object> request=createRequest(host.preview(admin));
            var pin=(OrganizationAccountImportSource.FilePin)config.getClass().getMethod(component).invoke(config);
            Path path=pin.path();byte[] original=Files.readAllBytes(path);String before=allState();
            try{
                Files.writeString(path,"SYNTHETIC TAMPERED SOURCE");
                rejects(409,()->host.account(admin,viewerId),"modified "+component+" rejects account lookup and proof");
                rejects(409,()->host.preview(admin),"modified "+component+" rejects preview");
                rejects(409,()->host.commit(admin,request),"modified "+component+" rejects commit");
                rejects(409,()->host.received(admin),"modified "+component+" rejects received proof");
            }finally{Files.write(path,original);}
            rejects(409,()->host.commit(admin,request),"restoring "+component+" cannot revive review token");
            unchanged(before,"source failure and restoration do not change data: "+component);
        }
    }

    private static void receiveAndReplay(OrganizationManagementSupplementHost host,OrganizationManagementSupplementSource.Config config,Path sources)throws Exception{
        String configBefore=configurationState();long usersBefore=Db.count("users"),revisionBefore=n(Db.one("SELECT revision FROM organization_account_import_state WHERE singleton=1").get("revision"));
        Map<String,Object> preview=host.preview(admin),request=createRequest(preview),receipt=host.commit(admin,request);
        check(Boolean.FALSE.equals(receipt.get("permissionsPublished"))&&Boolean.FALSE.equals(receipt.get("accountsActivated")),"receipt never publishes permission or activates accounts");
        check(n(o(receipt.get("summary")).get("createdPending"))==2&&n(o(receipt.get("summary")).get("linkedExisting"))==0,"exactly two pending users created");
        check(Db.count("users")==usersBefore+2,"exact user count increment");check(n(receipt.get("revision"))==revisionBefore+1,"global revision advances once");
        Set<Long> ids=new HashSet<>();Set<String> codes=new HashSet<>(),names=new HashSet<>();
        for(Map<String,Object> row:rows(receipt.get("rows"))){long id=n(row.get("accountId"));Map<String,Object> user=Db.one("SELECT * FROM users WHERE id=?",id);ids.add(id);codes.add((String)row.get("personCode"));names.add((String)user.get("username"));check(user.get("password")==null,"new password SQL NULL");check(n(user.get("status"))==0&&"viewer".equals(user.get("role")),"new status zero / viewer");check(String.valueOf(user.get("username")).matches("pending_[0-9a-f]{32}"),"random pending username");check(String.valueOf(row.get("personCode")).matches("imp_p_[0-9a-f]{32}"),"random internal person code");check(Auth.login(String.valueOf(user.get("username")),PASSWORD)==null,"pending account cannot authenticate");}
        check(ids.size()==2&&codes.size()==2&&names.size()==2,"two distinct account IDs and random identifiers");check(configBefore.equals(configurationState()),"published configuration and bindings remain byte-for-byte unchanged");
        String committed=allState();Map<String,Object> replay=host.commit(admin,request);check(Boolean.TRUE.equals(replay.get("replayed")),"same command replays receipt");unchanged(committed,"idempotent replay no writes");
        Map<String,Object> altered=copy(request);Map<String,Object> first=rows(altered.get("decisions")).get(0);first.put("action","LINK_EXISTING");first.put("accountId",viewerId);first.put("accountProof","a".repeat(64));rejects(409,()->host.commit(admin,altered),"same batch cannot replay different decisions");
        Map<String,Object> received=host.received(admin);check(rows(received.get("rows")).size()==2,"received exposes two verified mappings");check(received.get("receiptFingerprint") instanceof String s&&s.matches("[0-9a-f]{64}"),"received carries verified receipt digest");
        for(Map<String,Object> row:rows(received.get("rows"))){check(Boolean.FALSE.equals(row.get("accountEnabled")),"received reflects disabled new account");check(row.keySet().equals(Set.of("reference","accountId","personCode","accountEnabled")),"received mapping whitelist");}
        check(Boolean.FALSE.equals(received.get("permissionsPublished"))&&Boolean.FALSE.equals(received.get("accountsActivated")),"received remains mapping proof only");
        OrganizationAccountImport engine=OrganizationAccountImport.managementSupplement(config);rejects(409,()->engine.verifiedProvisioningIntake(admin),"supplement cannot use initial full-configuration provisioning");
        // Rebuild only the batch key using the canonical record constructor: source pins/namespace stay fixed.
        OrganizationManagementSupplementSource.Config renamed=withBatch(config,"synthetic-supplement-clone");
        check(OrganizationManagementSupplementSource.namespace(config).equals(OrganizationManagementSupplementSource.namespace(renamed)),"namespace independent from batch key");
        OrganizationManagementSupplementHost clone=new OrganizationManagementSupplementHost(renamed);rejects(409,()->clone.preview(admin),"same namespace rows in another batch cannot be cloned");
        String code=(String)rows(receipt.get("rows")).get(0).get("personCode"),payload=(String)Db.one("SELECT payload FROM organization_account_import_people WHERE person_code=?",code).get("payload");
        try{Db.exec("UPDATE organization_account_import_people SET payload='{}' WHERE person_code=?",code);rejects(409,()->host.received(admin),"tampered intake cannot prove mappings");rejects(409,()->host.commit(admin,request),"replay revalidates durable intake");}finally{Db.exec("UPDATE organization_account_import_people SET payload=? WHERE person_code=?",payload,code);}
        String storedReceipt=(String)Db.one("SELECT receipt FROM organization_account_import_batches WHERE batch_key=?",config.batchKey()).get("receipt");
        for(Object badReplay:Arrays.asList(null,"false",Boolean.TRUE)){
            Map<String,Object> malformed=o(Json.parse(storedReceipt));if(badReplay==null)malformed.remove("replayed");else malformed.put("replayed",badReplay);
            try{
                Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key=?",Json.write(malformed),config.batchKey());
                rejects(409,()->host.preview(admin),"durable receipt replayed marker rejects preview: "+badReplay);
                rejects(409,()->host.received(admin),"durable receipt replayed marker rejects received proof: "+badReplay);
                rejects(409,()->host.commit(admin,request),"durable receipt replayed marker rejects replay: "+badReplay);
            }finally{Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key=?",storedReceipt,config.batchKey());}
        }
        check(Boolean.TRUE.equals(host.commit(admin,request).get("replayed")),"restored receipt replays without creating accounts");
        // A coherent forged receipt/payload still cannot replace the action authorized by the original command.
        Map<String,Object> forgedReceipt=o(Json.parse(storedReceipt)),forgedPayload=o(Json.parse(payload));
        rows(forgedReceipt.get("rows")).stream().filter(x->code.equals(x.get("personCode"))).findFirst().orElseThrow().put("action","LINK_EXISTING");
        o(forgedReceipt.get("summary")).put("createdPending",1);o(forgedReceipt.get("summary")).put("linkedExisting",1);forgedPayload.put("accountDisposition","LINK_EXISTING");
        try{
            Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key=?",Json.write(forgedReceipt),config.batchKey());
            Db.exec("UPDATE organization_account_import_people SET disposition='LINK_EXISTING',payload=? WHERE person_code=?",Json.write(forgedPayload),code);
            rejects(409,()->host.commit(admin,request),"coherent durable action tamper cannot replace original reviewed action on replay");
        }finally{
            Db.exec("UPDATE organization_account_import_people SET disposition='CREATE_PENDING',payload=? WHERE person_code=?",payload,code);
            Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key=?",storedReceipt,config.batchKey());
        }
        unchanged(committed,"all receipt and clone probes preserve final data");
        linkExistingAndReplay(sources);
        String advanced=allState();long currentRevision=n(Db.one("SELECT revision FROM organization_account_import_state WHERE singleton=1").get("revision"));
        check(currentRevision>n(receipt.get("revision")),"independent supplemental batch advanced global revision");
        Map<String,Object> replayAfterAdvance=host.commit(admin,request);
        check(Boolean.TRUE.equals(replayAfterAdvance.get("replayed"))&&n(replayAfterAdvance.get("revision"))==n(receipt.get("revision")),"original supplemental command replays original receipt after another batch");
        unchanged(advanced,"supplemental replay after later revision performs no writes");
        legacyReplayChecks();
    }

    private static void linkExistingAndReplay(Path sources)throws Exception{
        ManagementSupplementSourceTest.Fixture fixture=new ManagementSupplementSourceTest.Fixture(sources.resolve("supplement-linked"),oldConfig,oldReceipt);
        fixture.batchKey="synthetic-management-supplement-linked";
        OrganizationManagementSupplementHost host=new OrganizationManagementSupplementHost(fixture.save());
        long accountId=user("synthetic-existing-link","viewer",1);Map<String,Object> userBefore=Db.one("SELECT * FROM users WHERE id=?",accountId);
        String configBefore=configurationState();long usersBefore=Db.count("users");
        Map<String,Object> request=createRequest(host.preview(admin)),lookup=host.account(admin,accountId);
        check(Boolean.TRUE.equals(lookup.get("available"))&&lookup.get("proof") instanceof String,"explicit existing-account lookup returns proof");
        Map<String,Object> decision=rows(request.get("decisions")).get(0);decision.put("action","LINK_EXISTING");decision.put("accountId",accountId);decision.put("accountProof",lookup.get("proof"));
        String before=allState();Map<String,Object> badProof=copy(request);rows(badProof.get("decisions")).get(0).put("accountProof","0".repeat(64));
        rejects(409,()->host.commit(admin,badProof),"unverified existing-account proof rejected");unchanged(before,"bad existing-account proof no writes");
        Map<String,Object> duplicate=copy(request);Map<String,Object> second=rows(duplicate.get("decisions")).get(1);second.put("action","LINK_EXISTING");second.put("accountId",accountId);second.put("accountProof",lookup.get("proof"));
        rejects(400,()->host.commit(admin,duplicate),"two candidates cannot link same existing account");unchanged(before,"duplicate existing link no writes");
        Map<String,Object> bound=copy(request);rows(bound.get("decisions")).get(0).put("accountId",viewerId);rows(bound.get("decisions")).get(0).put("accountProof","0".repeat(64));
        rejects(409,()->host.commit(admin,bound),"disabled formal binding still blocks existing-account commit");unchanged(before,"occupied binding commit no writes");
        Map<String,Object> receipt=host.commit(admin,request);
        check(n(o(receipt.get("summary")).get("createdPending"))==1&&n(o(receipt.get("summary")).get("linkedExisting"))==1,"mixed existing/new receipt counts exact");
        check(Db.count("users")==usersBefore+1,"link-existing creates only the other pending user");
        check(Json.write(userBefore).equals(Json.write(Db.one("SELECT * FROM users WHERE id=?",accountId))),"existing username/password/name/role/status/timestamp preserved");
        check(configBefore.equals(configurationState()),"link-existing leaves published config and formal bindings unchanged");
        check(Auth.login("synthetic-existing-link",PASSWORD)!=null,"existing credential remains valid");
        String reference=(String)decision.get("reference");Map<String,Object> received=rows(host.received(admin).get("rows")).stream().filter(x->reference.equals(x.get("reference"))).findFirst().orElseThrow();
        check(n(received.get("accountId"))==accountId&&Boolean.TRUE.equals(received.get("accountEnabled")),"received proof retains explicitly selected enabled account");
        before=allState();check(Boolean.TRUE.equals(host.commit(admin,request).get("replayed")),"mixed link-existing command replays");unchanged(before,"mixed link-existing replay no writes");
        Map<String,Object> changed=copy(request);Map<String,Object> changedDecision=rows(changed.get("decisions")).get(0);changedDecision.put("action","CREATE_PENDING");changedDecision.put("accountId",null);changedDecision.put("accountProof",null);
        rejects(409,()->host.commit(admin,changed),"linked-existing batch rejects changed decisions");unchanged(before,"changed mixed-batch replay no writes");
    }

    private static void legacyReplayChecks()throws Exception{
        String before=allState();Map<String,Object> replay=oldImporter.commit(oldActor,oldRequest);
        check(Boolean.TRUE.equals(replay.get("replayed"))&&n(replay.get("revision"))==n(oldReceipt.get("revision")),"legacy command remains compatible after supplemental revisions");
        unchanged(before,"legacy replay no writes");
        String code=(String)rows(oldReceipt.get("rows")).get(0).get("personCode");
        String payload=(String)Db.one("SELECT payload FROM organization_account_import_people WHERE person_code=?",code).get("payload");
        try{Db.exec("UPDATE organization_account_import_people SET payload='{}' WHERE person_code=?",code);rejects(409,()->oldImporter.commit(oldActor,oldRequest),"legacy replay rejects corrupted durable payload");}
        finally{Db.exec("UPDATE organization_account_import_people SET payload=? WHERE person_code=?",payload,code);}
        unchanged(before,"legacy corruption probe restored exact prior state");
    }

    private static OrganizationManagementSupplementSource.Config withBatch(OrganizationManagementSupplementSource.Config config,String batch)throws Exception{
        var components=config.getClass().getRecordComponents();Class<?>[] types=new Class<?>[components.length];Object[] values=new Object[components.length];
        for(int i=0;i<components.length;i++){types[i]=components[i].getType();values[i]=components[i].getName().equals("batchKey")?batch:components[i].getAccessor().invoke(config);}
        return (OrganizationManagementSupplementSource.Config)config.getClass().getDeclaredConstructor(types).newInstance(values);
    }
    public static final class FailSecondPending implements Trigger{static int count;public void fire(Connection connection,Object[] oldRow,Object[] newRow)throws SQLException{if(newRow[2]==null&&newRow[5] instanceof Number status&&status.intValue()==0&&++count==2)throw new SQLException("SYNTHETIC_SECOND_PENDING_FAILURE");}}
    public static final class ForceCasFailure implements Trigger{public void fire(Connection connection,Object[] oldRow,Object[] newRow)throws SQLException{try(Statement statement=connection.createStatement()){statement.executeUpdate("UPDATE organization_account_import_state SET revision=revision+10 WHERE singleton=1");}}}
}
