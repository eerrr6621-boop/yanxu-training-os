package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.h2.api.Trigger;
import static com.training.OrganizationAccess.*;
import static com.training.ManagementPublicationFixture.*;

/** Real Auth/import/publication paths against caller-owned, empty synthetic H2 and source directories. */
public final class ManagementGroupPublicationTest {
    private static final String PASSWORD="SYNTHETIC-PUBLICATION-ONLY-20260924";
    private static final Set<String> TARGET_REFS=Set.of("teacher-row-3","teacher-row-4","teacher-row-5","teacher-row-6","teacher-row-7","teacher-row-9","teacher-row-10","group-main-row-3","group-main-row-10");
    private static int checks;
    private static long adminId,viewerId,managerId;
    private static Auth.Session admin,viewer,affected;
    private static OrganizationAccountImportSource.Config original;
    private static OrganizationManagementSupplementSource.Config supplemental;
    private static Map<String,Object> originalReceipt,supplementReceipt;
    private static Configuration baseline;
    private static Map<String,Map<String,Object>> receivedRows=new LinkedHashMap<>();
    private static Set<Long> targetIds=new HashSet<>();
    private static String preservedIntakes;
    private static ManagementPublicationFixture fixture;
    private interface Work { void run()throws Exception; }
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static void rejects(int code,Work work,String label)throws Exception{try{work.run();throw new AssertionError("Expected rejection: "+label);}catch(Api.ApiException e){check(e.code==code,label+" (actual="+e.code+")");}}
    private static void rejectsAny(Work work,String label)throws Exception{try{work.run();throw new AssertionError("Expected rejection: "+label);}catch(Exception expected){checks++;}}
    private static Map<String,Object> copy(Map<String,Object> value){return o(Json.parse(Json.write(value)));}
    private static long n(Object raw){return((Number)raw).longValue();}
    private static String digest(Object value)throws Exception{return hash(Json.write(value).getBytes(StandardCharsets.UTF_8));}
    private static Map<String,Object> importRequest(Map<String,Object> preview){List<Object> decisions=l();for(Object raw:a(preview.get("rows"))){Map<String,Object> row=o(raw);decisions.add(m("reference",row.get("reference"),"action","CREATE_PENDING","accountId",null,"accountProof",null,"reviewed",true));}return m("expectedRevision",preview.get("revision"),"sourceFingerprint",preview.get("sourceFingerprint"),"reviewToken",preview.get("reviewToken"),"decisions",decisions);}
    private static Map<String,Object> request(Map<String,Object> preview){return m("expectedVersion",preview.get("expectedVersion"),"snapshotFingerprint",preview.get("snapshotFingerprint"),"reviewToken",preview.get("reviewToken"),"reviewed",true);}
    private static Auth.Session login(String username)throws Exception{return Auth.get(Auth.login(username,PASSWORD));}
    private static long user(String username,String role,int status)throws Exception{return Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,?)",username,Auth.hash(PASSWORD),"SYNTHETIC "+username,role,status);}
    private static String code(String kind,String name)throws Exception{return "org_sys_v1_"+hash((kind+"\n"+name).getBytes(StandardCharsets.UTF_8)).substring(0,32);}
    private static OrganizationManagementGroupPublication service(){return new OrganizationManagementGroupPublication(original,supplemental);}
    private static String state()throws Exception{return digest(m("users",Db.query("SELECT * FROM users ORDER BY id"),"configuration",Db.query("SELECT * FROM organization_access_config ORDER BY id"),"publications",Db.query("SELECT * FROM organization_management_group_publications ORDER BY publication_key"),"intakes",intakes()));}
    private static String intakes()throws Exception{return digest(m("batches",Db.query("SELECT * FROM organization_account_import_batches ORDER BY batch_key"),"people",Db.query("SELECT * FROM organization_account_import_people ORDER BY person_code"),"revision",Db.query("SELECT * FROM organization_account_import_state"),"teachers",Db.query("SELECT * FROM teachers ORDER BY id")));}
    private static void unchanged(String before,String label)throws Exception{check(before.equals(state()),label);check(preservedIntakes.equals(intakes()),label+" preserves original/supplement receipts and teachers");}

    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Fresh synthetic data/source paths and scenario required");
        if(!Set.of("main","affected-actor","prepare-http").contains(args[2]))throw new IllegalArgumentException("Unknown synthetic scenario");
        Path data=Path.of(args[0]).toAbsolutePath(),sources=Path.of(args[1]).toAbsolutePath();
        for(Path root:List.of(data,sources)){if(!Files.isDirectory(root))throw new IllegalArgumentException("Existing empty directory required");try(var files=Files.list(root)){if(files.findAny().isPresent())throw new IllegalArgumentException("Only empty synthetic directories accepted");}}
        System.setProperty("data.dir",data.toString());System.setProperty("bootstrap.demo","false");
        try{
            Db.exec("CREATE TABLE users(id IDENTITY PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            adminId=user("synthetic-publication-admin","admin",1);viewerId=user("synthetic-unmapped-viewer","viewer",1);
            Db.init();OrganizationAccessStore.init();OrganizationAccountImport.init();
            long initialUsers=Db.count("users");OrganizationManagementGroupPublication.init();check(Db.count("users")==initialUsers&&Db.count("organization_management_group_publications")==0,"init creates empty metadata only");
            admin=login("synthetic-publication-admin");viewer=login("synthetic-unmapped-viewer");check(admin!=null&&viewer!=null,"real sessions in isolated H2");
            setup(sources);preservedIntakes=intakes();
            if(args[2].equals("prepare-http"))prepareHttp(data,sources);
            else if(args[2].equals("affected-actor"))affectedActor();else{authorizationAndReviews();snapshotAndRollback();publishAndRecover();}
            System.out.println("ManagementGroupPublication "+args[2]+": "+checks+" checks passed (fresh synthetic H2; no shared/production writes)");
        }finally{if(!Db.get().isClosed())Db.exec("SHUTDOWN");}
    }
    private static void setup(Path sources)throws Exception {
        fixture=new ManagementPublicationFixture(sources.resolve("original"));original=fixture.save();var source=OrganizationAccountImportSource.load(original);
        check(source.candidates().size()==9&&source.branchCoverage().size()==37,"real original loader accepts 9 synthetic candidates/37 branches");
        check(source.branchCoverage().stream().map(c->c.region()).distinct().count()==5,"five explicit source regions");
        OrganizationAccountImport intake=new OrganizationAccountImport(original);originalReceipt=intake.commit(admin,importRequest(intake.preview(admin)));
        ManagementSupplementSourceTest.Fixture supplementFixture=new ManagementSupplementSourceTest.Fixture(sources.resolve("supplement"),original,originalReceipt);
        o(a(supplementFixture.projection.get("candidates")).get(0)).put("name","王维明");o(a(supplementFixture.projection.get("candidates")).get(1)).put("name","沈军");
        for(Object raw:o(supplementFixture.decision.get("explicitHomeMapping")).values())o(raw).put("targetOrganizationCode",code("HOME",HQ));
        supplemental=supplementFixture.save();OrganizationManagementSupplementHost supplementHost=new OrganizationManagementSupplementHost(supplemental);supplementReceipt=supplementHost.commit(admin,importRequest(supplementHost.preview(admin)));
        for(Map<String,Object> receipt:List.of(originalReceipt,supplementReceipt))for(Object raw:a(receipt.get("rows"))){Map<String,Object> row=o(raw);receivedRows.put((String)row.get("reference"),row);if(TARGET_REFS.contains(row.get("reference")))targetIds.add(n(row.get("accountId")));}
        managerId=user("manager01","manager",1);targetIds.add(managerId);check(targetIds.size()==10,"ten distinct target account IDs");
        long affectedId=n(receivedRows.get("teacher-row-3").get("accountId"));Db.exec("UPDATE users SET username='synthetic-affected-target',password=?,status=1 WHERE id=?",Auth.hash(PASSWORD),affectedId);
        Db.exec("INSERT INTO teachers(name,org,status) VALUES('SYNTHETIC PREEXISTING TEACHER','SYNTHETIC HOME','在库')");
        baseline=baseline(source);check(OrganizationAccess.validate(baseline).valid(),"valid baseline includes role scopes, relations, DENY and combined duties");
        OrganizationAccessStore.publish(admin,null,baseline);affected=login("synthetic-affected-target");check(affected!=null,"affected account session created after baseline publication");
    }
    /** Export one reusable pre-publication fixture; a separate JVM must start the real Main server. */
    private static void prepareHttp(Path data,Path sources)throws Exception {
        String before=state();
        Map<String,Object> originalPins=m("candidates",pin(original.candidates()),"candidatePolicy",pin(original.candidatePolicy()),
                "roleSource",pin(original.roleSource()),"authorization",pin(original.authorization()),"audit",pin(original.audit()),
                "regionReference",pin(original.regionReference()),"scopeDecision",pin(original.scopeDecision()),"preparedPreview",pin(original.preparedPreview()));
        List<Object> originalFiles=l();for(var file:original.originals())originalFiles.add(pin(file));
        Map<String,Object> supplementalPins=m("groupsOriginal",pin(supplemental.groupsOriginal()),"teacherOriginal",pin(supplemental.teacherOriginal()),
                "projection",pin(supplemental.projection()),"decision",pin(supplemental.decision()),"originalReceipt",pin(supplemental.originalReceipt()));
        var originalManifest=exportJson(sources.resolve("original-server-manifest.json"),m("schema","M01-SERVER-PIN-MANIFEST-v1",
                "batchKey",original.batchKey(),"usage","SYNTHETIC HTTP test fixture only; no real identities or production data","pins",originalPins,"originals",originalFiles));
        var supplementalManifest=exportJson(sources.resolve("supplemental-server-manifest.json"),m("schema","M01-MANAGEMENT-SUPPLEMENT-MANIFEST-v1",
                "batchKey",supplemental.batchKey(),"pins",supplementalPins));
        var loadedOriginal=OrganizationAccountImportHost.readConfig(originalManifest.path().toString(),originalManifest.sha256());
        var loadedSupplement=OrganizationManagementSupplementHttp.readConfig(supplementalManifest.path().toString(),supplementalManifest.sha256());
        check(original.equals(loadedOriginal)&&supplemental.equals(loadedSupplement),"exported strict host manifests preserve both Config records");
        var originalSource=OrganizationAccountImportSource.load(loadedOriginal);var supplementalSource=OrganizationManagementSupplementSource.load(loadedSupplement);
        check(originalSource.candidates().size()==9&&originalSource.branchCoverage().size()==37&&supplementalSource.candidates().size()==2,"exported pins pass both real source loaders");
        check(new OrganizationAccountImport(loadedOriginal).verifiedProvisioningIntake(admin).accounts().size()==9,"exported original config verifies complete already-received intake");
        check(a(new OrganizationManagementSupplementHost(loadedSupplement).received(admin).get("rows")).size()==2,"exported supplemental config verifies independent already-received intake");
        check(Db.count("organization_management_group_publications")==0&&!OrganizationAccessStore.configuration().roleCodes().contains("MANAGEMENT_ADMIN"),"HTTP fixture remains unpublished with no management role");
        Map<String,Object> originalDto=new LinkedHashMap<>(originalPins);originalDto.put("batchKey",original.batchKey());originalDto.put("originals",originalFiles);
        Map<String,Object> supplementalDto=new LinkedHashMap<>(supplementalPins);supplementalDto.put("batchKey",supplemental.batchKey());
        Map<String,Object> properties=m("data.dir",data.toString(),"bootstrap.demo","false","login.email.mode","legacy",
                "account.import.manifest",originalManifest.path().toString(),"account.import.manifest.sha256",originalManifest.sha256(),
                "management.supplement.manifest",supplementalManifest.path().toString(),"management.supplement.manifest.sha256",supplementalManifest.sha256());
        Map<String,Object> counters=m("users",Db.count("users"),"teachers",Db.count("teachers"),"importBatches",Db.count("organization_account_import_batches"),
                "importPeople",Db.count("organization_account_import_people"),"configurationRows",Db.count("organization_access_config"),
                "publicationRows",Db.count("organization_management_group_publications"),"managementTargets",targetIds.size());
        Map<String,Object> description=m("schema","M01-MANAGEMENT-HTTP-SYNTHETIC-FIXTURE-v1","synthetic",true,"dbDir",data.toString(),"sourceDir",sources.toString(),
                "baselineVersion",baseline.version(),"originalConfig",originalDto,"supplementalConfig",supplementalDto,
                "manifests",m("original",pin(originalManifest),"supplemental",pin(supplementalManifest)),"systemProperties",properties,
                "logins",m("admin",loginDescriptor(adminId),"viewer",loginDescriptor(viewerId),"manager",loginDescriptor(managerId),
                        "affected",loginDescriptor(n(receivedRows.get("teacher-row-3").get("accountId")))),
                "targetIds",new ArrayList<>(new TreeSet<>(targetIds)),"receivedRows",receivedRows,"counters",counters);
        var descriptionPin=exportJson(sources.resolve("management-http-fixture.json"),description);
        check(Boolean.TRUE.equals(o(Json.parse(Files.readString(descriptionPin.path()))).get("synthetic")),"HTTP fixture descriptor is parseable and explicitly synthetic");
        unchanged(before,"HTTP fixture export writes source manifests only, without publication or database changes");
        System.out.println("Prepared reusable synthetic HTTP fixture: "+descriptionPin.path());
    }
    private static Map<String,Object> loginDescriptor(long accountId)throws Exception {
        Map<String,Object> user=Db.one("SELECT id,username,role,status FROM users WHERE id=?",accountId);
        return m("accountId",accountId,"username",user.get("username"),"password",PASSWORD,"role",user.get("role"),"status",user.get("status"));
    }
    private static OrganizationAccountImportSource.FilePin exportJson(Path path,Object value)throws Exception {
        byte[] bytes=Json.write(value).getBytes(StandardCharsets.UTF_8);Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
        return new OrganizationAccountImportSource.FilePin(path,hash(bytes));
    }
    private static Configuration baseline(OrganizationAccountImportSource.Snapshot source)throws Exception {
        String root=code("ROOT","SYSTEM_ROOT");List<Organization> orgs=new ArrayList<>();orgs.add(new Organization(root,null,true,"系统组织目录"));orgs.add(new Organization(code("HOME",HQ),root,true,HQ));orgs.add(new Organization(code("HOME",HR),root,true,HR));
        for(String region:REGIONS)orgs.add(new Organization(code("REGION",region),root,true,region));
        for(var branch:fixture.branches.entrySet())orgs.add(new Organization(code("BRANCH",branch.getKey()),code("REGION",branch.getValue()),!branch.getKey().equals("SYNTHETIC BRANCH 36"),branch.getKey()));
        Set<String> roleCodes=new LinkedHashSet<>(List.of("MEMBER","BP","BRANCH_RESPONSIBLE","BRANCH_LEAD"));
        RelationRule optional=new RelationRule(false,roleCodes,false,false);List<RoleRelations> relations=new ArrayList<>();for(String role:roleCodes)relations.add(new RoleRelations(role,optional,optional));
        List<Person> people=new ArrayList<>();List<AccountBinding> bindings=new ArrayList<>();List<CombinedApprovalAssignment> combined=new ArrayList<>();
        for(var candidate:source.candidates()){
            String ref=candidate.reference();Map<String,Object> received=receivedRows.get(ref);String person=(String)received.get("personCode"),home=code(fixture.branches.containsKey(candidate.organization())?"BRANCH":"HOME",candidate.organization());
            Map<String,Set<String>> scopes=new LinkedHashMap<>();scopes.put("MEMBER",Set.of());Set<String> union=new LinkedHashSet<>();
            for(Object raw:a(candidate.preparation().get("approvalRoles"))){Map<String,Object> duty=o(raw);Set<String> destinations=scopes.computeIfAbsent((String)duty.get("kind"),ignored->new LinkedHashSet<>());for(Object name:a(duty.get("proposedBranches"))){String branch=code("BRANCH",(String)name);destinations.add(branch);union.add(branch);}}
            String leader=ref.equals("teacher-row-9")?(String)receivedRows.get("teacher-row-8").get("personCode"):null,bp=ref.equals("teacher-row-9")?(String)receivedRows.get("teacher-row-3").get("personCode"):null;
            people.add(new Person(person,home,union,leader,bp,scopes.keySet(),!ref.equals("teacher-row-4"),scopes));
            bindings.add(new AccountBinding(n(received.get("accountId")),person,ref.equals("teacher-row-3")));
            if(scopes.containsKey("BP")&&scopes.containsKey("BRANCH_RESPONSIBLE"))for(String branch:scopes.get("BP"))if(scopes.get("BRANCH_RESPONSIBLE").contains(branch))combined.add(new CombinedApprovalAssignment(branch,person,"BRANCH_RESPONSIBLE","BP","SYNTHETIC COMBINED "+ref));
        }
        List<Grant> grants=List.of(new Grant("SYNTHETIC_PRESERVED_DENY","MEMBER","demand.read",Action.VIEW,Effect.DENY,Scope.NAMED_ORGS,Set.of(code("BRANCH","烟台分公司"))),new Grant("SYNTHETIC_OLD_BP","BP","summary.review.bp",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        return new Configuration("SYNTHETIC-BEFORE-1",new CodeRules("org_sys_v1_[0-9a-f]{32}","imp_p_[0-9a-f]{32}","[A-Z_]+"),roleCodes,orgs,people,relations,bindings,grants,combined);
    }
    private static void authorizationAndReviews()throws Exception {
        var service=service();String before=state();Auth.Session forged=new Auth.Session();forged.uid=adminId;forged.role="admin";
        rejects(401,()->service.preview(null),"anonymous preview");rejects(401,()->service.preview(forged),"forged known admin session");rejects(403,()->service.preview(viewer),"viewer preview");rejects(401,()->service.commit(forged,Map.of()),"forged commit");rejects(403,()->service.received(viewer),"viewer received");unchanged(before,"auth failures read only");
        Db.exec("UPDATE users SET username='synthetic-missing-manager' WHERE id=?",managerId);before=state();rejects(409,()->service.preview(admin),"exact manager01 missing fails closed");unchanged(before,"missing manager never creates replacement");Db.exec("UPDATE users SET username='manager01' WHERE id=?",managerId);
        Map<String,Object> preview=service.preview(admin);check(Boolean.TRUE.equals(preview.get("ready")),"full ten-member candidate is ready");check(preview.get("configuration") instanceof Map<?,?>&&preview.get("diff") instanceof Map<?,?>,"review contains complete candidate and diff");before=state();Map<String,Object> extra=request(preview);extra.put("users",List.of());rejects(400,()->service.commit(admin,extra),"unknown commit key rejected");unchanged(before,"unknown key cannot publish");
        Map<String,Object> unchecked=request(service.preview(admin));unchecked.put("reviewed",false);rejects(400,()->service.commit(admin,unchecked),"explicit confirmation required");
        Map<String,Object> first=request(service.preview(admin));service.preview(admin);rejects(409,()->service.commit(admin,first),"second preview invalidates first review");
        Map<String,Object> own=request(service.preview(admin));Auth.Session another=login("synthetic-publication-admin");rejects(409,()->service.commit(another,own),"review binds exact live session");
        Map<String,Object> wrong=request(service.preview(admin));wrong.put("expectedVersion","SYNTHETIC-WRONG");rejects(409,()->service.commit(admin,wrong),"expected version must match reviewed version");wrong.put("expectedVersion",baseline.version());rejects(409,()->service.commit(admin,wrong),"failed attempt consumes review");
        Map<String,Object> downgraded=request(service.preview(admin));Db.exec("UPDATE users SET role='viewer' WHERE id=?",adminId);rejects(403,()->service.commit(admin,downgraded),"live administrator downgrade");Db.exec("UPDATE users SET role='admin' WHERE id=?",adminId);rejects(409,()->service.commit(admin,downgraded),"restoring role cannot revive failed review in same session");admin=login("synthetic-publication-admin");
        Map<String,Object> revoked=request(service.preview(admin));Auth.revokeUserSessions(adminId);rejects(401,()->service.commit(admin,revoked),"revoked review session");admin=login("synthetic-publication-admin");
    }
    private static void snapshotAndRollback()throws Exception {
        var service=service();Map<String,Object> request=request(service.preview(admin));Path source=supplemental.projection().path();byte[] bytes=Files.readAllBytes(source);String before=state();
        try{Files.writeString(source,"SYNTHETIC TAMPER");rejects(409,()->service.commit(admin,request),"source changed since preview");}finally{Files.write(source,bytes);}rejects(409,()->service.commit(admin,request),"restored source cannot revive failed review");unchanged(before,"source failure leaves persistent state unchanged");
        Map<String,Object> userRequest=request(service.preview(admin));Db.exec("UPDATE users SET name='SYNTHETIC CHANGED' WHERE id=?",viewerId);before=state();rejects(409,()->service.commit(admin,userRequest),"unrelated live user field invalidates snapshot");unchanged(before,"stale users cannot publish");
        Map<String,Object> intakeRequest=request(service.preview(admin));Db.exec("UPDATE organization_account_import_people SET created_by=? WHERE source_reference='lead-row-9'",viewerId);String intakeChanged=state();rejects(409,()->service.commit(admin,intakeRequest),"nonmember intake metadata invalidates complete snapshot");check(intakeChanged.equals(state()),"intake metadata conflict cannot mutate publication state");Db.exec("UPDATE organization_account_import_people SET created_by=? WHERE source_reference='lead-row-9'",adminId);check(preservedIntakes.equals(intakes()),"synthetic intake metadata restored exactly");
        Map<String,Object> configurationRequest=request(service.preview(admin));Configuration old=OrganizationAccessStore.configuration();Configuration changed=new Configuration("SYNTHETIC-BEFORE-2",old.codeRules(),old.roleCodes(),old.organizations(),old.people(),old.relations(),old.accountBindings(),old.grants(),old.combinedApprovals());OrganizationAccessStore.publish(admin,old.version(),changed);baseline=changed;before=state();rejects(409,()->service.commit(admin,configurationRequest),"concurrent configuration version invalidates snapshot");unchanged(before,"CAS stale preview cannot publish");
        for(String table:List.of("organization_management_group_publications","organization_access_config")){
            var rollbackService=service();Map<String,Object> reviewed=request(rollbackService.preview(admin));before=state();Auth.Session sessionBefore=affected;
            Db.exec("CREATE TRIGGER synthetic_publication_failure BEFORE INSERT ON "+table+" FOR EACH ROW CALL 'com.training.ManagementGroupPublicationTest$FailInsert'");
            try{rejectsAny(()->rollbackService.commit(admin,reviewed),"forced insert failure at "+table);}finally{Db.exec("DROP TRIGGER synthetic_publication_failure");}
            unchanged(before,"forced "+table+" failure rolls back users/config/receipt");check(Auth.current(admin)==admin&&Auth.current(sessionBefore)==sessionBefore,"rollback never revokes affected/unaffected sessions");
        }
    }
    private static void publishAndRecover()throws Exception {
        var service=service();Map<String,Object> preview=service.preview(admin),reviewed=request(preview);Configuration candidate=OrganizationAccessStore.parseConfiguration(preview.get("configuration"));
        Map<Long,Map<String,Object>> usersBefore=new LinkedHashMap<>();for(Map<String,Object> user:Db.query("SELECT * FROM users ORDER BY id"))usersBefore.put(n(user.get("id")),user);
        String before=state();service.received(admin);rejects(403,()->service.preview(viewer),"unrelated viewer cannot inspect authorized review");rejects(401,()->service.preview(null),"anonymous cannot inspect authorized review");unchanged(before,"unpublished received and unauthorized requests are read only");
        Map<String,Object> receipt=service.commit(admin,reviewed);check(Boolean.TRUE.equals(receipt.get("published"))&&Boolean.FALSE.equals(receipt.get("accountsActivated"))&&Boolean.FALSE.equals(receipt.get("passwordsChanged")),"commit publishes without activation or password changes");
        check(Db.count("organization_management_group_publications")==1,"one durable publication receipt");check(preservedIntakes.equals(intakes()),"all intakes/teachers unchanged on successful publication");
        Configuration after=OrganizationAccessStore.configuration();check(Json.write(OrganizationAccessStore.toMap(after)).equals(Json.write(OrganizationAccessStore.toMap(candidate))),"published candidate exactly matches reviewed document");verifyConfiguration(after);
        for(Map<String,Object> user:Db.query("SELECT * FROM users ORDER BY id")){long id=n(user.get("id"));Map<String,Object> expected=new LinkedHashMap<>(usersBefore.get(id));if(targetIds.contains(id))expected.put("role","admin");check(expected.equals(user),"full user row preserved except ten explicitly approved role changes");}
        check(Auth.current(affected)==null,"affected target session revoked after commit");check(Auth.current(admin)==admin&&Auth.current(viewer)==viewer,"unmapped administrator/viewer sessions preserved");rejects(401,()->service.received(affected),"affected stale session cannot read receipt");
        String published=state();Map<String,Object> replay=service.commit(admin,reviewed);check(Boolean.TRUE.equals(replay.get("replayed")),"same live session and exact token replay idempotently");unchanged(published,"same-token replay read only");
        var restarted=service();Auth.Session relogin=login("synthetic-publication-admin");Map<String,Object> recovered=restarted.preview(relogin);check(Boolean.TRUE.equals(recovered.get("published"))&&Boolean.TRUE.equals(recovered.get("replayed"))&&recovered.get("reviewToken")==null,"new service/session preview recovers durable result without review token");check(Boolean.TRUE.equals(restarted.received(relogin).get("published")),"received verifies durable publication");unchanged(published,"restart/relogin recovery read only");
        String savedReceipt=(String)Db.one("SELECT receipt FROM organization_management_group_publications").get("receipt");Db.exec("UPDATE organization_management_group_publications SET receipt='{}'");String tampered=state();rejects(409,()->restarted.received(relogin),"tampered receipt rejected");unchanged(tampered,"receipt verification is read only");Db.exec("UPDATE organization_management_group_publications SET receipt=?",savedReceipt);
        String savedHash=(String)Db.one("SELECT receipt_sha256 FROM organization_management_group_publications").get("receipt_sha256");
        for(String field:List.of("diff","members","beforeUsersFingerprint")){
            Map<String,Object> altered=o(Json.parse(savedReceipt));
            if(field.equals("diff"))o(a(o(altered.get("diff")).get("userRoleChanges")).get(0)).put("roleBefore","admin");
            else if(field.equals("members"))o(a(altered.get("members")).get(0)).put("name","SYNTHETIC WRONG IDENTITY");
            else altered.put(field,"f".repeat(64));
            Db.exec("UPDATE organization_management_group_publications SET receipt=?,receipt_sha256=?",Json.write(altered),digest(canonical(altered)));
            tampered=state();rejects(409,()->restarted.received(relogin),"rehashed receipt "+field+" cannot bypass semantic reconstruction");unchanged(tampered,"rehashed receipt verification remains read only");
            Db.exec("UPDATE organization_management_group_publications SET receipt=?,receipt_sha256=?",savedReceipt,savedHash);
        }
        String priorPayload=(String)Db.one("SELECT payload FROM organization_access_config WHERE version=?",baseline.version()).get("payload");Db.exec("UPDATE organization_access_config SET payload='{}' WHERE version=?",baseline.version());tampered=state();rejects(409,()->restarted.received(relogin),"corrupt historical configuration prevents false recovery");unchanged(tampered,"history verification remains read only");Db.exec("UPDATE organization_access_config SET payload=? WHERE version=?",priorPayload,baseline.version());
        Db.exec("UPDATE users SET role='viewer' WHERE id=?",managerId);tampered=state();rejects(409,()->restarted.preview(relogin),"changed published role prevents false idempotent success");unchanged(tampered,"changed result verification is read only");Db.exec("UPDATE users SET role='admin' WHERE id=?",managerId);
        Path pinned=original.candidates().path();byte[] originalBytes=Files.readAllBytes(pinned);try{Files.writeString(pinned,"SYNTHETIC TAMPER");rejects(409,()->restarted.received(relogin),"published recovery revalidates original pins");}finally{Files.write(pinned,originalBytes);}
        check(Boolean.TRUE.equals(service().received(relogin).get("published")),"restored trusted state remains recoverable with fresh service");
    }
    private static void verifyConfiguration(Configuration after)throws Exception {
        check(after.organizations().equals(baseline.organizations()),"directory, parent hierarchy, enabled flags unchanged");check(after.combinedApprovals().equals(baseline.combinedApprovals()),"old combined-duty evidence unchanged");check(after.grants().containsAll(baseline.grants()),"all prior grants including DENY preserved");check(after.relations().containsAll(baseline.relations()),"prior relation rules preserved");
        check(after.people().size()==baseline.people().size()+3&&after.accountBindings().size()==baseline.accountBindings().size()+3,"only two supplemental identities and manager receive new person/binding rows");
        Map<String,Person> current=new HashMap<>();for(Person person:after.people())current.put(person.personCode(),person);
        Set<String> memberCodes=new HashSet<>();for(String ref:TARGET_REFS)memberCodes.add((String)receivedRows.get(ref).get("personCode"));
        for(Person previous:baseline.people()){Person now=current.get(previous.personCode());boolean member=memberCodes.contains(previous.personCode());check(now.enabled()==previous.enabled()&&Objects.equals(now.leaderPersonCode(),previous.leaderPersonCode())&&Objects.equals(now.bpPersonCode(),previous.bpPersonCode())&&now.organizationCode().equals(previous.organizationCode()),"old person flags, home and leader/BP preserved");for(var scope:previous.responsibleOrganizationsByRole().entrySet())check(scope.getValue().equals(now.responsibleOrganizationsByRole().get(scope.getKey())),"old role-specific scope unchanged");if(!member)check(now.equals(previous),"nonmember person entirely unchanged");}
        for(AccountBinding binding:baseline.accountBindings())check(after.accountBindings().contains(binding),"old enabled/disabled binding preserved exactly");for(AccountBinding binding:after.accountBindings())if(baseline.accountBindings().stream().noneMatch(old->old.accountId()==binding.accountId()))check(!binding.enabled(),"new binding remains disabled");
        List<Grant> grants=after.grants().stream().filter(g->g.roleCode().equals("MANAGEMENT_ADMIN")).toList();Set<String> branchCodes=new HashSet<>();for(String branch:fixture.branches.keySet())branchCodes.add(code("BRANCH",branch));check(grants.size()==29,"exact 29 management grants");for(Grant grant:grants)check(grant.effect()==Effect.ALLOW&&grant.scope()==Scope.NAMED_ORGS&&grant.organizationCodes().equals(branchCodes),"management grant explicit exact 37 branches");check(after.people().stream().filter(person->person.roleCodes().contains("MANAGEMENT_ADMIN")).count()==10,"exact ten people receive management role");
        String credential=Auth.login("synthetic-affected-target",PASSWORD);Engine engine=new Engine(after);SessionVerifier live=token->{Auth.Session session=Auth.get(token);return session==null?OptionalLong.empty():OptionalLong.of(session.uid);};
        check(!engine.authorize(credential,live,new Resource("demand.read",code("BRANCH","烟台分公司")),Action.VIEW).allowed(),"preserved explicit DENY still wins after users.role admin and management ALLOW");
        check(engine.authorize(credential,live,new Resource("demand.read",code("BRANCH","济南分公司")),Action.VIEW).allowed(),"enabled existing binding gains only explicitly allowed management branch access");
    }
    private static void affectedActor()throws Exception {
        long uid=n(receivedRows.get("teacher-row-3").get("accountId"));Db.exec("UPDATE users SET role='admin' WHERE id=?",uid);Auth.revokeUserSessions(uid);affected=login("synthetic-affected-target");var service=service();Map<String,Object> request=request(service.preview(affected));Map<String,Object> receipt=service.commit(affected,request);check(Boolean.TRUE.equals(receipt.get("published")),"target administrator can publish authorized group");check(Auth.current(affected)==null,"publishing target administrator revoked only after successful commit");rejects(401,()->service.commit(affected,request),"revoked actor cannot use completed token");Auth.Session fresh=login("synthetic-affected-target");check(Boolean.TRUE.equals(service().preview(fresh).get("published")),"affected actor relogin recovers durable result");check(preservedIntakes.equals(intakes()),"affected actor publication leaves old intakes untouched");
    }
    public static final class FailInsert implements Trigger {
        public void init(Connection c,String s,String t,String table,boolean before,int type){}
        public void fire(Connection c,Object[] old,Object[] row)throws SQLException{throw new SQLException("SYNTHETIC PUBLICATION ROLLBACK");}
        public void close(){}public void remove(){}
    }
    private static Object canonical(Object raw){
        if(raw instanceof Map<?,?> source){Map<String,Object> out=new TreeMap<>();for(var entry:source.entrySet())out.put((String)entry.getKey(),canonical(entry.getValue()));return out;}
        if(raw instanceof Iterable<?> source){List<Object> out=l();for(Object value:source)out.add(canonical(value));return out;}
        if(raw instanceof Number number)return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        return raw;
    }
}
