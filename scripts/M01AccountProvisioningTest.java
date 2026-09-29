package com.training;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.h2.api.Trigger;
import static com.training.OrganizationAccess.*;
import com.training.OrganizationAccountImportSource.Config;
import com.training.OrganizationAccountImportSourceTest.Fixture;

/** Only caller-owned synthetic files and a fresh temporary H2; no service or actual personnel inputs. */
public final class M01AccountProvisioningTest {
    private static final String PASSWORD="SYNTHETIC-PROVISIONING-20260923", POLICY="M01-INITIAL-PROVISIONING-v1";
    private static final String A="SYNTHETIC BRANCH A", B="SYNTHETIC BRANCH B", C="SYNTHETIC BRANCH C", HQ="SYNTHETIC HR";
    private static int checks;
    private static long adminId,linkedId;
    private static Auth.Session admin,viewer;
    private static String adminToken,hash;
    private static Path root;
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    @FunctionalInterface private interface Change{void run()throws Exception;}
    private record Batch(Config source,OrganizationAccountImport importer,OrganizationAccountProvisioning service,
                         Map<String,Object> request,Map<String,Object> receipt){}

    public static final class WriteGuard implements Trigger {
        static boolean enabled; static long attempts;
        @Override public void fire(Connection connection,Object[] oldRow,Object[] newRow)throws SQLException {
            if(enabled){attempts++;throw new SQLException("Synthetic read-only assertion", "45000");}
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Fresh synthetic root required");
        root=Path.of(args[0]).toAbsolutePath().normalize();
        if(!root.getFileName().toString().startsWith("yanxu-m01-account-provisioning.")||!Files.isDirectory(root))
            throw new IllegalArgumentException("Synthetic root prefix required");
        for(String child:List.of("data","sources")){
            Path path=root.resolve(child);Files.createDirectories(path);
            try(var files=Files.list(path)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty test directories required");}
        }
        System.setProperty("data.dir",root.resolve("data").toString());System.setProperty("bootstrap.demo","false");
        System.setProperty("login.email.mode","legacy");
        try {
            Db.exec("CREATE TABLE users(id IDENTITY PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            hash=Auth.hash(PASSWORD);
            adminId=Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)","synthetic-provision-admin",hash,"SYNTHETIC ADMIN","admin");
            linkedId=Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)","synthetic-linked",hash,"SYNTHETIC DIFFERENT ACCOUNT NAME","viewer");
            Db.init();OrganizationAccessStore.init();OrganizationAccountImport.init();installGuards();
            adminToken=Auth.login("synthetic-provision-admin",PASSWORD);admin=Auth.get(adminToken);
            viewer=Auth.get(Auth.login("synthetic-linked",PASSWORD));
            check(admin!=null&&viewer!=null&&Db.count("users")==2,"real sessions, no demonstration users");
            Config source=OrganizationAccountImportSourceTest.fixture(root.resolve("sources/base"));
            OrganizationAccountImport importer=new OrganizationAccountImport(source);
            OrganizationAccountProvisioning service=new OrganizationAccountProvisioning(importer);
            reject(409,()->service.preview(admin),"unreceived batch blocked");
            Batch batch=receive(source,importer,service,true);
            Map<String,Object> originalImport=read(()->importer.preview(admin),"old import preview before provisioning");
            Map<String,Object> preview=read(()->service.preview(admin),"baseline preview");
            validateDto(batch,preview);
            var prepared=read(()->service.confirm(admin,body(preview)),"baseline confirm");
            validateCandidate(batch,preview,prepared);
            reject(409,()->service.confirm(admin,body(preview)),"successful token consumed");
            check(originalImport.equals(read(()->importer.preview(admin),"old import preview after provisioning")),"existing preview and receipt stay compatible");
            Map<String,Object> replay=read(()->importer.commit(admin,batch.request()),"old commit replay stays read-only");
            check(Boolean.TRUE.equals(replay.get("replayed"))&&Json.parse(Json.write(replay.get("rows"))).equals(Json.parse(Json.write(batch.receipt().get("rows")))),"old commit replay keeps identities");
            authentication(batch);
            tokenChanges(batch,prepared.configuration());
            capacity(batch);
            combinedRoute();
            // Publishing is exercised only by this trusted synthetic test, never by the product generator.
            testHostPublish(batch,prepared.configuration());
            check(Auth.current(admin)==admin,"preparation never revokes unrelated administrator session");
            System.out.println("M01 account provisioning synthetic checks passed: "+checks);
        } finally {WriteGuard.enabled=false;try{Db.get().close();}catch(Exception ignored){}}
    }

    private static Batch receive(Config source,OrganizationAccountImport importer,OrganizationAccountProvisioning service,boolean link)throws Exception {
        Map<String,Object> p=importer.preview(admin);List<Object> choices=new ArrayList<>();
        for(Map<String,Object> row:rows(p.get("rows")))choices.add(map("reference",row.get("reference"),"action","CREATE_PENDING","accountId",null,"accountProof",null,"reviewed",true));
        if(link){Map<String,Object> account=importer.account(admin,linkedId),choice=object(choices.get(0));
            choice.put("action","LINK_EXISTING");choice.put("accountId",account.get("accountId"));choice.put("accountProof",account.get("proof"));}
        Map<String,Object> request=map("expectedRevision",p.get("revision"),"sourceFingerprint",p.get("sourceFingerprint"),"reviewToken",p.get("reviewToken"),"decisions",choices);
        Map<String,Object> receipt=importer.commit(admin,request);
        check(number(object(receipt.get("summary")).get("candidates"))==7,"all seven synthetic candidates received");
        check(rows(receipt.get("rows")).stream().map(r->r.get("personCode")).distinct().count()==7,"same name across institutions not merged");
        return new Batch(source,importer,service,request,receipt);
    }

    private static void validateDto(Batch batch,Map<String,Object> p)throws Exception {
        keys(p,"policy_version batch_key source_fingerprint intake_revision proposed_version review_token expires_at permissions_published accounts_activated can_operate requires_account_activation office_codes_status summary diff validation organizations people grants combined_assignments issues warnings","preview whitelist");
        check(POLICY.equals(p.get("policy_version"))&&batch.source().batchKey().equals(p.get("batch_key")),"fixed policy and server batch");
        check(Boolean.FALSE.equals(p.get("permissions_published"))&&Boolean.FALSE.equals(p.get("accounts_activated"))&&Boolean.FALSE.equals(p.get("can_operate")),"preparation cannot operate");
        check(Boolean.TRUE.equals(p.get("requires_account_activation"))&&"UNKNOWN".equals(p.get("office_codes_status")),"activation and office codes remain pending");
        check(((String)p.get("review_token")).matches("[0-9a-f]{64}"),"random token shape");
        long remaining=java.time.Instant.parse((String)p.get("expires_at")).toEpochMilli()-System.currentTimeMillis();
        check(remaining>0&&remaining<=300_000,"five minute token lifetime");
        Map<String,Object> summary=object(p.get("summary"));
        keys(summary,"account_count approval_people_count approval_duties_count ordinary_people_count region_count branch_count home_organization_count route_prepared_count route_blocked_count route_blocked_counts accounts_enabled accounts_disabled bindings_enabled","summary whitelist");
        for(var entry:Map.of("account_count",7,"approval_people_count",6,"approval_duties_count",7,"ordinary_people_count",1,"region_count",2,"branch_count",3,"home_organization_count",1,"route_prepared_count",1,"route_blocked_count",6,"accounts_enabled",1).entrySet())check(number(summary.get(entry.getKey()))==entry.getValue(),"summary "+entry.getKey());
        check(number(summary.get("accounts_disabled"))==6&&number(summary.get("bindings_enabled"))==0,"disabled intake reflected");
        check(object(summary.get("route_blocked_counts")).keySet().equals(Set.of("MULTIPLE_LEADERS","HOME_NOT_BRANCH","SELF_REVIEW")),"only actual route reasons counted");
        check(number(object(summary.get("route_blocked_counts")).get("MULTIPLE_LEADERS"))==3&&number(object(summary.get("route_blocked_counts")).get("HOME_NOT_BRANCH"))==2&&number(object(summary.get("route_blocked_counts")).get("SELF_REVIEW"))==1,"route reason counts");
        Map<String,Object> diff=object(p.get("diff"));keys(diff,"initial_configuration organizations_added people_added bindings_added enabled_bindings_added grants_added combined_assignments_added","diff whitelist");
        check(Boolean.TRUE.equals(diff.get("initial_configuration"))&&number(diff.get("organizations_added"))==7&&number(diff.get("people_added"))==7&&number(diff.get("bindings_added"))==7&&number(diff.get("enabled_bindings_added"))==0&&number(diff.get("grants_added"))==8&&number(diff.get("combined_assignments_added"))==1,"initial diff precise");
        check(object(p.get("validation")).equals(map("configuration_valid",true)),"complete validation reported");
        List<Map<String,Object>> orgs=rows(p.get("organizations"));check(orgs.size()==7,"root regions branches and extra home retained");
        Set<String> ids=new HashSet<>();
        for(Map<String,Object> org:orgs){keys(org,"organization_code display_name kind parent_organization_code enabled office_code","organization whitelist");String kind=(String)org.get("kind"),name=kind.equals("ROOT")?"SYSTEM_ROOT":(String)org.get("display_name");
            check(orgCode(kind,name).equals(org.get("organization_code"))&&ids.add((String)org.get("organization_code")),"deterministic technical organization identifier");
            check(org.get("office_code")==null&&Boolean.TRUE.equals(org.get("enabled")),"no invented office code");
            Object parent=org.get("parent_organization_code");check(kind.equals("ROOT")?parent==null:parent!=null,"organization parent present");}
        for(Map<String,Object> org:orgs){String kind=(String)org.get("kind");if(kind.equals("HOME")||kind.equals("REGION"))check(orgCode("ROOT","SYSTEM_ROOT").equals(org.get("parent_organization_code")),"home and region direct root");
            if(kind.equals("BRANCH"))check(orgCode("REGION",C.equals(org.get("display_name"))?"SYNTHETIC REGION TWO":"SYNTHETIC REGION ONE").equals(org.get("parent_organization_code")),"branch retains confirmed region parent");}
        var intake=read(()->batch.importer().verifiedProvisioningIntake(admin),"typed projection read-only");
        Map<String,OrganizationAccountImport.ReceivedAccount> received=new HashMap<>();intake.accounts().forEach(r->received.put(r.sourceReference(),r));
        Map<String,OrganizationAccountImportSource.Candidate> source=new HashMap<>();intake.source().candidates().forEach(c->source.put(c.reference(),c));
        List<Map<String,Object>> people=rows(p.get("people"));check(people.size()==7,"all received people retained");
        for(Map<String,Object> person:people){keys(person,"reference account_id name organization_code organization_name account_enabled binding_enabled person_enabled role_codes role_scopes leader_reference bp_reference route_status","person whitelist");String ref=(String)person.get("reference");
            check(received.containsKey(ref)&&number(person.get("account_id"))==received.get(ref).accountId(),"identity comes from receipt");
            check(source.get(ref).name().equals(person.get("name"))&&source.get(ref).organization().equals(person.get("organization_name")),"source identity kept despite account display name");
            check(Boolean.FALSE.equals(person.get("binding_enabled"))&&Boolean.TRUE.equals(person.get("person_enabled")),"person valid but binding disabled");
            for(Map<String,Object> scope:rows(person.get("role_scopes")))keys(scope,"role_code organization_codes organization_names","role scope whitelist");
            if(!"ROUTE_PREPARED".equals(person.get("route_status")))check(person.get("leader_reference")==null&&person.get("bp_reference")==null,"blocked routes have no partial pair");}
        check("ROUTE_PREPARED".equals(find(people,"reference","teacher-row-3").get("route_status")),"unique nonself branch prepared");
        check("lead-row-9".equals(find(people,"reference","teacher-row-3").get("leader_reference"))&&"teacher-row-7".equals(find(people,"reference","teacher-row-3").get("bp_reference")),"explicit sole route mapped");
        for(String ref:List.of("teacher-row-4","teacher-row-5","teacher-row-8"))check("MULTIPLE_LEADERS".equals(find(people,"reference",ref).get("route_status")),"multiple leaders never first-selected");
        for(String ref:List.of("teacher-row-6","teacher-row-7"))check("HOME_NOT_BRANCH".equals(find(people,"reference",ref).get("route_status")),"BP home not moved to scope");
        check("SELF_REVIEW".equals(find(people,"reference","lead-row-9").get("route_status")),"sole leader does not approve own request");
        for(Map<String,Object> grant:rows(p.get("grants")))keys(grant,"rule_id role_code resource action effect scope","grant whitelist");
        for(Map<String,Object> combined:rows(p.get("combined_assignments"))){keys(combined,"organization_code organization_name reference account_id leader_role_code bp_role_code evidence_status","combined whitelist");check("VERIFIED_SOURCE".equals(combined.get("evidence_status")),"combined proof reported as verified source only");}
        check(rows(p.get("issues")).size()==6,"one issue per blocked route");for(Map<String,Object> issue:rows(p.get("issues")))keys(issue,"code reference organization_code message","issue whitelist");
        privacy(batch,p);
    }

    private static void validateCandidate(Batch batch,Map<String,Object> preview,OrganizationAccountProvisioning.PreparedConfiguration prepared)throws Exception {
        Configuration c=prepared.configuration();Map<String,Object> confirmation=prepared.confirmation();
        keys(confirmation,"prepared policy_version batch_key source_fingerprint intake_revision proposed_version configuration_valid permissions_published accounts_activated can_operate account_count route_prepared_count route_blocked_count requires_account_activation","confirmation whitelist");
        check(Boolean.TRUE.equals(confirmation.get("prepared"))&&Boolean.TRUE.equals(confirmation.get("configuration_valid"))&&Boolean.FALSE.equals(confirmation.get("can_operate"))&&Boolean.FALSE.equals(confirmation.get("permissions_published"))&&Boolean.FALSE.equals(confirmation.get("accounts_activated")),"confirmation is not publication");
        check(prepared.batchKey().equals(preview.get("batch_key"))&&prepared.sourceFingerprint().equals(preview.get("source_fingerprint"))&&prepared.intakeRevision()==number(preview.get("intake_revision"))&&POLICY.equals(prepared.policyVersion())&&c.version().equals(preview.get("proposed_version")),"prepared accessors match reviewed snapshot");
        check(OrganizationAccess.validate(c).valid(),"whole configuration valid despite blocked routes");
        check(c.roleCodes().equals(Set.of("MEMBER","SUBMITTER","BRANCH_RESPONSIBLE","BRANCH_LEAD","BP")),"fixed business role dictionary");
        Map<String,Map<String,Object>> received=new HashMap<>();for(Map<String,Object> r:rows(batch.receipt().get("rows")))received.put((String)r.get("reference"),r);
        Map<String,Person> persons=new HashMap<>();for(Person p:c.people())persons.put(p.personCode(),p);
        check(persons.keySet().equals(new HashSet<>(received.values().stream().map(r->(String)r.get("personCode")).toList())),"only original imp_p identities used");
        check(c.accountBindings().size()==7&&c.accountBindings().stream().noneMatch(AccountBinding::enabled),"all original account bindings disabled");
        for(Map<String,Object> r:received.values())check(c.accountBindings().stream().anyMatch(b->b.accountId()==number(r.get("accountId"))&&b.personCode().equals(r.get("personCode"))),"receipt account mapping unchanged");
        for(Person p:c.people()){check(p.personCode().matches("imp_p_[0-9a-f]{32}")&&p.enabled()&&p.roleCodes().contains("MEMBER"),"stable received person with member role");
            check(p.responsibleOrganizationsByRole().keySet().equals(p.roleCodes()),"every role explicitly scoped");
            check(p.responsibleOrganizationsByRole().get("MEMBER").isEmpty(),"member has no responsible scope");Set<String> union=new HashSet<>();p.responsibleOrganizationsByRole().values().forEach(union::addAll);check(union.equals(p.responsibleOrganizationCodes()),"union is validation metadata only");}
        Person ordinary=byReference(received,persons,"teacher-row-3"),bp=byReference(received,persons,"teacher-row-7"),combined=byReference(received,persons,"teacher-row-6");
        check(ordinary.roleCodes().equals(Set.of("MEMBER","SUBMITTER")),"ordinary person has no approval role");
        check(ordinary.organizationCode().equals(orgCode("BRANCH",B))&&ordinary.leaderPersonCode().equals(received.get("lead-row-9").get("personCode"))&&ordinary.bpPersonCode().equals(bp.personCode()),"prepared route translated only via receipt");
        check(bp.organizationCode().equals(orgCode("HOME",HQ))&&combined.organizationCode().equals(bp.organizationCode()),"BP and combined home retained");
        check(responsibleOrganizations(bp,"BP").equals(Set.of(orgCode("BRANCH",A),orgCode("BRANCH",B)))&&responsibleOrganizations(combined,"BP").equals(Set.of(orgCode("BRANCH",C)))&&responsibleOrganizations(combined,"BRANCH_RESPONSIBLE").equals(Set.of(orgCode("BRANCH",C))),"per-role source scopes exact");
        check(responsibleOrganizations(ordinary,"SUBMITTER").equals(Set.of(orgCode("BRANCH",B))),"submitter scope explicitly own branch");
        for(String ref:List.of("teacher-row-4","teacher-row-5","teacher-row-8","lead-row-9")){Person branch=byReference(received,persons,ref);String role=ref.equals("teacher-row-8")||ref.equals("lead-row-9")?"BRANCH_LEAD":"BRANCH_RESPONSIBLE";
            check(responsibleOrganizations(branch,role).equals(Set.of(orgCode("BRANCH",ref.equals("lead-row-9")?B:A))),"branch duty never expands to full region");}
        for(Person p:c.people())if(!p.personCode().equals(ordinary.personCode()))check(!p.roleCodes().contains("SUBMITTER")&&p.leaderPersonCode()==null&&p.bpPersonCode()==null,"blocked person isolated without invalidating catalog");
        for(RoleRelations r:c.relations()){check(r.leader().required()==r.roleCode().equals("SUBMITTER")&&r.bp().required()==r.roleCode().equals("SUBMITTER"),"required only for prepared submitter");
            check(!r.leader().allowSelf()&&!r.bp().allowSelf()&&r.leader().targetMustCoverOrganization()&&r.bp().targetMustCoverOrganization(),"uniform nonself matching scope relation rules");
            check(r.leader().allowedTargetRoles().equals(Set.of("BRANCH_RESPONSIBLE","BRANCH_LEAD"))&&r.bp().allowedTargetRoles().equals(Set.of("BP")),"relation role whitelists exact");}
        Set<String> expectedGrants=new HashSet<>();for(String role:List.of("BP","BRANCH_LEAD","BRANCH_RESPONSIBLE")){expectedGrants.add(role+":approval.review:HANDLE:RESPONSIBLE_ORGS");expectedGrants.add(role+":demand.read:VIEW:RESPONSIBLE_ORGS");}
        expectedGrants.add("SUBMITTER:demand.read:VIEW:OWN_ORG");expectedGrants.add("SUBMITTER:demand.write:HANDLE:OWN_ORG");
        Set<String> actualGrants=new HashSet<>();for(Grant g:c.grants()){check(g.effect()==Effect.ALLOW&&g.organizationCodes().isEmpty()&&!g.roleCode().equals("MEMBER"),"no extra DENY or named grants");actualGrants.add(g.roleCode()+":"+g.resource()+":"+g.action()+":"+g.scope());}
        check(actualGrants.equals(expectedGrants)&&c.grants().size()==8,"only frozen resource action grants");
        check(c.combinedApprovals().size()==1,"one source combined proof");CombinedApprovalAssignment duty=c.combinedApprovals().get(0);
        check(duty.personCode().equals(combined.personCode())&&duty.organizationCode().equals(orgCode("BRANCH",C))&&duty.leaderRoleCode().equals("BRANCH_RESPONSIBLE")&&duty.bpRoleCode().equals("BP"),"combined proof separates two role duties");
        check(duty.evidenceRef()!=null&&!duty.evidenceRef().isBlank()&&duty.evidenceRef().length()<=128&&!duty.evidenceRef().contains(root.toString()),"bounded opaque combined proof");
        Engine disabled=new Engine(c);for(AccountBinding b:c.accountBindings())for(String org:List.of(A,B,C))check(!decision(disabled,b.accountId(),"approval.review",Action.HANDLE,org).allowed(),"prepared binding cannot approve");
        Configuration enabled=new Configuration(c.version(),c.codeRules(),c.roleCodes(),c.organizations(),c.people(),c.relations(),c.accountBindings().stream().map(b->new AccountBinding(b.accountId(),b.personCode(),true)).toList(),c.grants(),c.combinedApprovals());
        Engine model=new Engine(enabled);long ordinaryId=number(received.get("teacher-row-3").get("accountId")),bpId=number(received.get("teacher-row-7").get("accountId"));
        check(decision(model,ordinaryId,"demand.write",Action.HANDLE,B).allowed()&&!decision(model,ordinaryId,"demand.write",Action.HANDLE,A).allowed(),"submitter writes own institution only");
        check(!decision(model,ordinaryId,"approval.review",Action.HANDLE,B).allowed(),"ordinary has no approval even after hypothetical activation");
        check(decision(model,bpId,"approval.review",Action.HANDLE,A).allowed()&&decision(model,bpId,"approval.review",Action.HANDLE,B).allowed()&&!decision(model,bpId,"approval.review",Action.HANDLE,C).allowed(),"member does not deny explicit BP grant, BP does not cross region");
        check(!decision(model,bpId,"demand.write",Action.HANDLE,A).allowed(),"BP scope never implies cross-organization submission");
        for(String resource:List.of("demand.accept","bid.result","summary.export","training.record","management.report"))check(!decision(model,bpId,resource,Action.HANDLE,A).allowed(),"unrequested resource denied "+resource);
        immutable(()->{c.people().clear();return null;},"immutable people");immutable(()->{c.accountBindings().clear();return null;},"immutable bindings");immutable(()->{ordinary.roleCodes().add("ADMIN");return null;},"immutable person roles");immutable(()->{ordinary.responsibleOrganizationsByRole().put("ADMIN",Set.of());return null;},"immutable role scope map");
        check(Modifier.isFinal(prepared.getClass().getModifiers())&&Arrays.stream(prepared.getClass().getDeclaredConstructors()).allMatch(k->Modifier.isPrivate(k.getModifiers())),"prepared capability has no public constructor");
        immutable(()->{confirmation.put("permissions_published",true);return null;},"immutable confirmation");
        immutable(()->{preview.put("can_operate",true);return null;},"immutable preview");
        immutable(()->{rows(preview.get("people")).get(0).put("binding_enabled",true);return null;},"immutable nested preview");
        check(OrganizationAccessStore.configuration()==null,"confirm never publishes");privacy(batch,confirmation);
    }

    private static void authentication(Batch b)throws Exception {
        Auth.Session forged=new Auth.Session();forged.uid=adminId;forged.role="admin";
        reject(401,()->b.service().preview(null),"anonymous preview");reject(401,()->b.service().preview(forged),"forged admin preview");reject(403,()->b.service().preview(viewer),"viewer preview");
        Map<String,Object> p=read(()->b.service().preview(admin),"ownership preview");Map<String,Object> body=body(p);
        reject(401,()->b.service().confirm(forged,body),"forged confirm");reject(403,()->b.service().confirm(viewer,body),"viewer confirm");
        Auth.Session second=Auth.get(Auth.login("synthetic-provision-admin",PASSWORD));
        reject(409,()->b.service().confirm(second,body),"same user different Session cannot share token");
        OrganizationAccountProvisioning otherInstance=new OrganizationAccountProvisioning(new OrganizationAccountImport(b.source()));
        reject(409,()->otherInstance.confirm(admin,body),"same batch recreated host cannot reuse old instance token");
        reject(409,()->b.service().confirm(admin,map("batch_key","synthetic-other-batch","review_token",p.get("review_token"))),"wrong batch cannot select source");
        read(()->b.service().confirm(admin,body),"wrong owners did not consume original token");
        reject(400,()->b.service().confirm(admin,map("batch_key",b.source().batchKey(),"review_token","bad")),"malformed token");
        reject(400,()->b.service().confirm(admin,map("batch_key",b.source().batchKey(),"review_token","a".repeat(64),"configuration",map())),"extra configuration denied");
        reject(400,()->b.service().confirm(admin,map("review_token","a".repeat(64))),"missing batch denied");
        reject(409,()->b.service().confirm(admin,map("batch_key",b.source().batchKey(),"review_token","a".repeat(64))),"unknown token");
        Map<String,Object> exp=read(()->b.service().preview(admin),"expiration preview");expire(b.service(),(String)exp.get("review_token"));reject(409,()->b.service().confirm(admin,body(exp)),"expired token");
        String logoutToken=Auth.login("synthetic-provision-admin",PASSWORD);Auth.Session logout=Auth.get(logoutToken);Map<String,Object> lp=read(()->b.service().preview(logout),"logout preview");Auth.logout(logoutToken);
        reject(401,()->b.service().confirm(logout,body(lp)),"logged-out session rejected");
        check(Auth.current(admin)==admin,"ownership errors never revoke administrator");
    }

    private static void tokenChanges(Batch b,Configuration candidate)throws Exception {
        Map<String,Object> ordinary=find(rows(b.receipt().get("rows")),"reference","teacher-row-3");long id=number(ordinary.get("accountId"));
        changed(b,409,()->Db.exec("UPDATE users SET name=? WHERE id=?","SYNTHETIC CHANGED",id),()->Db.exec("UPDATE users SET name=? WHERE id=?","SYNTHETIC DIFFERENT ACCOUNT NAME",id),"current account name");
        changed(b,409,()->Db.exec("UPDATE users SET status=0 WHERE id=?",id),()->Db.exec("UPDATE users SET status=1 WHERE id=?",id),"current account status");
        changed(b,409,()->Db.exec("UPDATE users SET password=? WHERE id=?","SYNTHETIC DIFFERENT HASH",adminId),()->Db.exec("UPDATE users SET password=? WHERE id=?",hash,adminId),"administrator password");
        byte[] sourceBytes=Files.readAllBytes(b.source().candidates().path());
        changed(b,409,()->Files.writeString(b.source().candidates().path(),"SYNTHETIC PRIVATE INVALID SOURCE"),()->Files.write(b.source().candidates().path(),sourceBytes),"source digest");
        byte[] originalBytes=Files.readAllBytes(b.source().originals().get(0).path());
        changed(b,409,()->Files.writeString(b.source().originals().get(0).path(),"SYNTHETIC CHANGED ORIGINAL"),()->Files.write(b.source().originals().get(0).path(),originalBytes),"original source digest");
        String receipt=(String)Db.one("SELECT receipt FROM organization_account_import_batches WHERE batch_key=?",b.source().batchKey()).get("receipt");
        changed(b,409,()->Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key=?","{\"SYNTHETIC PRIVATE RAW\":",b.source().batchKey()),()->Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key=?",receipt,b.source().batchKey()),"receipt corruption");
        String decisionDigest=(String)Db.one("SELECT decision_digest FROM organization_account_import_batches WHERE batch_key=?",b.source().batchKey()).get("decision_digest");
        String otherDigest=decisionDigest.equals("b".repeat(64))?"c".repeat(64):"b".repeat(64);
        changed(b,409,()->Db.exec("UPDATE organization_account_import_batches SET decision_digest=? WHERE batch_key=?",otherDigest,b.source().batchKey()),()->Db.exec("UPDATE organization_account_import_batches SET decision_digest=? WHERE batch_key=?",decisionDigest,b.source().batchKey()),"valid decision digest change");
        String personCode=(String)ordinary.get("personCode"),payload=(String)Db.one("SELECT payload FROM organization_account_import_people WHERE person_code=?",personCode).get("payload");
        changed(b,409,()->Db.exec("UPDATE organization_account_import_people SET payload=? WHERE person_code=?","{}",personCode),()->Db.exec("UPDATE organization_account_import_people SET payload=? WHERE person_code=?",payload,personCode),"received payload");
        String fingerprint=(String)Db.one("SELECT source_fingerprint FROM organization_account_import_people WHERE person_code=?",personCode).get("source_fingerprint");
        changed(b,409,()->Db.exec("UPDATE organization_account_import_people SET source_fingerprint=? WHERE person_code=?","b".repeat(64),personCode),()->Db.exec("UPDATE organization_account_import_people SET source_fingerprint=? WHERE person_code=?",fingerprint,personCode),"received source association");
        changedValidReceipt(b);
        long revision=number(Db.one("SELECT revision FROM organization_account_import_state WHERE singleton=1").get("revision"));
        changed(b,409,()->Db.exec("UPDATE organization_account_import_state SET revision=revision+1 WHERE singleton=1"),()->Db.exec("UPDATE organization_account_import_state SET revision=? WHERE singleton=1",revision),"intake revision");
        changed(b,503,()->Db.exec("INSERT INTO organization_access_config(version,active_slot,previous_version,payload,created_by) VALUES(?,1,NULL,?,?)","synthetic-corrupt","{\"SYNTHETIC PRIVATE CONFIG\":",adminId),()->Db.exec("DELETE FROM organization_access_config WHERE version=?","synthetic-corrupt"),"corrupt active config");
        changed(b,503,()->Db.exec("INSERT INTO organization_access_config(version,active_slot,previous_version,payload,created_by) VALUES(?,NULL,NULL,?,?)","synthetic-orphan",Json.write(OrganizationAccessStore.toMap(candidate)),adminId),()->Db.exec("DELETE FROM organization_access_config WHERE version=?","synthetic-orphan"),"history without active head");
        Map<String,Object> p=read(()->b.service().preview(admin),"existing config conflict preview");
        publishInitial(candidate,"existing configuration conflict setup");
        reject(409,()->b.service().preview(admin),"existing config not overwritten by preview");
        reject(409,()->b.service().confirm(admin,body(p)),"existing config rejects confirmation");
        Db.exec("DELETE FROM organization_access_config");
        reject(409,()->b.service().confirm(admin,body(p)),"removed existing config does not resurrect old token");
        read(()->b.service().confirm(admin,body(b.service().preview(admin))),"fresh preview works after config restoration");
    }
    private static void changed(Batch batch,int status,Change change,Change restore,String label)throws Exception {
        Map<String,Object> p=read(()->batch.service().preview(admin),label+" preview");
        change.run();try{reject(status,()->batch.service().confirm(admin,body(p)),label+" rejects changed facts");}finally{restore.run();}
        reject(409,()->batch.service().confirm(admin,body(p)),label+" restored facts cannot resurrect token");
        read(()->batch.service().confirm(admin,body(batch.service().preview(admin))),label+" fresh preview recovers");
    }
    private static void changedValidReceipt(Batch b)throws Exception {
        Map<String,Object> stored=Db.one("SELECT receipt,decision_digest FROM organization_account_import_batches WHERE batch_key=?",b.source().batchKey());
        String original=(String)stored.get("receipt"),digest=(String)stored.get("decision_digest");
        Map<String,Object> altered=object(Json.parse(original)),row=find(rows(altered.get("rows")),"reference","teacher-row-4");String code=(String)row.get("personCode");
        String payload=(String)Db.one("SELECT payload FROM organization_account_import_people WHERE person_code=?",code).get("payload");
        Map<String,Object> changedPayload=object(Json.parse(payload));row.put("action","LINK_EXISTING");changedPayload.put("accountDisposition","LINK_EXISTING");
        Map<String,Object> summary=object(altered.get("summary"));summary.put("createdPending",number(summary.get("createdPending"))-1);summary.put("linkedExisting",number(summary.get("linkedExisting"))+1);
        changed(b,409,()->{
            Db.exec("UPDATE organization_account_import_batches SET receipt=?,decision_digest=? WHERE batch_key=?",Json.write(altered),"b".repeat(64),b.source().batchKey());
            Db.exec("UPDATE organization_account_import_people SET disposition=?,payload=? WHERE person_code=?","LINK_EXISTING",Json.write(changedPayload),code);
            read(()->b.importer().verifiedProvisioningIntake(admin),"alternate internally consistent receipt validates");
        },()->{
            Db.exec("UPDATE organization_account_import_batches SET receipt=?,decision_digest=? WHERE batch_key=?",original,digest,b.source().batchKey());
            Db.exec("UPDATE organization_account_import_people SET disposition=?,payload=? WHERE person_code=?","CREATE_PENDING",payload,code);
        },"valid receipt disposition change");
    }
    private static void capacity(Batch batch)throws Exception {
        expireAll(batch.service());for(int i=0;i<128;i++)read(()->batch.service().preview(admin),"bounded preview "+i);
        reject(409,()->batch.service().preview(admin),"capacity cannot exceed 128 records");
        expireAll(batch.service());read(()->batch.service().confirm(admin,body(batch.service().preview(admin))),"capacity recovers after expiry");
    }
    private static void combinedRoute()throws Exception {
        Fixture f=new Fixture(root.resolve("sources/combined-route"));
        find(rows(f.batch.get("candidates")),"candidate_reference","teacher-row-3").put("organization",C);
        Map<String,Object> pr=find(rows(f.preview.get("rows")),"reference","teacher-row-3");pr.put("organization",C);pr.put("region","SYNTHETIC REGION TWO");
        Config saved=f.save();Config source=new Config("synthetic-combined-route",saved.candidates(),saved.candidatePolicy(),saved.roleSource(),saved.authorization(),saved.audit(),saved.regionReference(),saved.scopeDecision(),saved.preparedPreview(),saved.originals());
        OrganizationAccountImport importer=new OrganizationAccountImport(source);Batch b=receive(source,importer,new OrganizationAccountProvisioning(importer),false);
        Map<String,Object> p=read(()->b.service().preview(admin),"combined route preview");Map<String,Object> ordinary=find(rows(p.get("people")),"reference","teacher-row-3");
        check("ROUTE_PREPARED".equals(ordinary.get("route_status"))&&"teacher-row-6".equals(ordinary.get("leader_reference"))&&"teacher-row-6".equals(ordinary.get("bp_reference")),"explicit same-person branch and BP prepares ordinary route");
        var prepared=read(()->b.service().confirm(admin,body(p)),"combined route confirm");
        check(OrganizationAccess.validate(prepared.configuration()).valid()&&prepared.configuration().combinedApprovals().size()==1,"combined employee configuration fully validates");
        check(rows(p.get("organizations")).stream().allMatch(o->o.get("organization_code").equals(orgCode((String)o.get("kind"),"ROOT".equals(o.get("kind"))?"SYSTEM_ROOT":(String)o.get("display_name")))),"technical ids stable across source paths and batch keys");
        Map<String,Object> again=read(()->b.service().preview(admin),"combined reproducibility preview");var next=read(()->b.service().confirm(admin,body(again)),"combined reproducibility confirm");
        check(prepared.configuration().combinedApprovals().equals(next.configuration().combinedApprovals()),"combined proof reproducible for unchanged sources");
    }
    private static void testHostPublish(Batch batch,Configuration candidate)throws Exception {
        check(OrganizationAccessStore.configuration()==null,"all preparation still unpublished");
        String users=Json.write(Db.query("SELECT * FROM users ORDER BY id"));
        publishInitial(candidate,"trusted host initial publication");
        Configuration published=OrganizationAccessStore.configuration();check(published!=null&&published.equals(candidate),"trusted host can publish stopped bindings through unique Store");
        check(users.equals(Json.write(Db.query("SELECT * FROM users ORDER BY id"))),"host publication still does not activate or modify users");
        Engine engine=new Engine(published);for(AccountBinding binding:published.accountBindings())for(Grant grant:published.grants())for(Organization org:published.organizations())check(!engine.authorize("synthetic verified identity",ignored->OptionalLong.of(binding.accountId()),new Resource(grant.resource(),org.organizationCode()),grant.action()).allowed(),"published disabled binding blocks every configured capability");
        reject(409,()->batch.service().preview(admin),"generator cannot replace host-published candidate");
    }

    private static void installGuards()throws Exception {int i=0;for(String table:tables())Db.exec("CREATE TRIGGER provisioning_no_write_"+(i++)+" BEFORE INSERT, UPDATE, DELETE ON \""+table+"\" FOR EACH ROW CALL 'com.training.M01AccountProvisioningTest$WriteGuard'");}
    private static List<String> tables()throws Exception {List<String> out=new ArrayList<>();try(ResultSet rs=Db.get().getMetaData().getTables(null,"PUBLIC","%",new String[]{"TABLE"})){while(rs.next())out.add(rs.getString("TABLE_NAME"));}Collections.sort(out);return out;}
    private static Map<String,Object> databaseSnapshot()throws Exception {Map<String,Object> snapshot=new TreeMap<>();for(String table:tables()){List<String> values=new ArrayList<>();for(Map<String,Object> row:Db.query("SELECT * FROM \""+table+"\""))values.add(Json.write(new TreeMap<>(row)));Collections.sort(values);snapshot.put(table,values);}return snapshot;}
    private static String database()throws Exception {return Json.write(databaseSnapshot());}
    private static void publishInitial(Configuration candidate,String label)throws Exception {
        check(OrganizationAccessStore.configuration()==null,label+" starts without a configuration");
        Map<String,Object> expected=databaseSnapshot();Map<Long,Long> epochs=new TreeMap<>();
        for(var row:Db.query("SELECT user_id,epoch FROM s01_trusted_device_epochs ORDER BY user_id"))epochs.put(number(row.get("user_id")),number(row.get("epoch")));
        Set<Long> affected=new TreeSet<>();for(AccountBinding binding:candidate.accountBindings())affected.add(binding.accountId());
        check(affected.size()==7&&!affected.contains(adminId),label+" revokes exactly the seven received identities, excluding the unrelated administrator");
        for(long id:affected)epochs.merge(id,1L,Long::sum);
        List<String> expectedEpochRows=new ArrayList<>();for(var entry:epochs.entrySet())expectedEpochRows.add(Json.write(new TreeMap<>(Map.of("user_id",entry.getKey(),"epoch",entry.getValue()))));Collections.sort(expectedEpochRows);
        expected.put("S01_TRUSTED_DEVICE_EPOCHS",expectedEpochRows);
        OrganizationAccessStore.publish(admin,null,candidate);
        Map<String,Object> after=databaseSnapshot();
        check(expectedEpochRows.equals(after.get("S01_TRUSTED_DEVICE_EPOCHS")),label+" creates epoch one or increments exactly once only for affected accounts");
        check(Db.count("organization_access_config")==1,label+" writes exactly one configuration revision");
        expected.put("ORGANIZATION_ACCESS_CONFIG",after.get("ORGANIZATION_ACCESS_CONFIG"));
        check(expected.equals(after),label+" preserves every other table including trusted devices, accounts and intake receipts");
    }
    private static <T>T read(Work<T> work,String label)throws Exception {String before=database();long attempted=WriteGuard.attempts;WriteGuard.enabled=true;try{return work.run();}finally{WriteGuard.enabled=false;check(WriteGuard.attempts==attempted,label+" attempted no INSERT/UPDATE/DELETE");check(before.equals(database()),label+" all database tables unchanged");}}
    private static void reject(int status,Work<?> action,String label)throws Exception {read(()->{try{action.run();throw new AssertionError(label+" must reject");}catch(Api.ApiException e){check(e.code==status,label+" status expected "+status+" got "+e.code);check(e.getCause()==null&&!e.getMessage().contains("SYNTHETIC PRIVATE")&&!e.getMessage().contains(root.toString()),label+" fixed private-safe error");}return null;},label);}
    private static void privacy(Batch b,Object dto)throws Exception {String json=Json.write(dto);for(String forbidden:List.of(root.toString(),"imp_p_","000SYN","password","sourceEvidence","source_staff_id","candidateSource","sha256","preparedOrganizationsByRole"))check(!json.contains(forbidden),"DTO excludes "+forbidden.replace(root.toString(),"private path"));for(var pin:List.of(b.source().candidates(),b.source().candidatePolicy(),b.source().roleSource(),b.source().authorization(),b.source().audit(),b.source().regionReference(),b.source().scopeDecision(),b.source().preparedPreview()))check(!json.contains(pin.sha256()),"DTO excludes raw source pin digest");}
    private static Decision decision(Engine engine,long account,String resource,Action action,String branch){return engine.authorize("synthetic verified identity",ignored->OptionalLong.of(account),new Resource(resource,orgCode("BRANCH",branch)),action);}
    private static Person byReference(Map<String,Map<String,Object>> received,Map<String,Person> people,String ref){return people.get(received.get(ref).get("personCode"));}
    private static String orgCode(String kind,String exact){try{return "org_sys_v1_"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((kind+"\n"+exact).getBytes(StandardCharsets.UTF_8))).substring(0,32);}catch(Exception e){throw new AssertionError(e);}}
    private static void immutable(Work<?> work,String label)throws Exception {try{work.run();throw new AssertionError(label);}catch(UnsupportedOperationException expected){check(true,label);}}
    @SuppressWarnings("unchecked")private static Map<String,Object> reviews(OrganizationAccountProvisioning service)throws Exception{Field field=OrganizationAccountProvisioning.class.getDeclaredField("REVIEWS");field.setAccessible(true);return (Map<String,Object>)field.get(service);}
    private static void expire(OrganizationAccountProvisioning service,String token)throws Exception{Object review=reviews(service).get(token);check(review!=null,"test expiration targets issued token");Field field=review.getClass().getDeclaredField("expires");field.setAccessible(true);field.setLong(review,0);}
    private static void expireAll(OrganizationAccountProvisioning service)throws Exception{for(String token:new ArrayList<>(reviews(service).keySet()))expire(service,token);}
    private static Map<String,Object> body(Map<String,Object> p){return map("batch_key",p.get("batch_key"),"review_token",p.get("review_token"));}
    private static void keys(Map<String,Object> value,String fields,String label){check(value.keySet().equals(Set.of(fields.split(" "))),label);}
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static long number(Object value){if(!(value instanceof Number number))throw new AssertionError("Expected numeric synthetic field");return number.longValue();}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked")private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private static Map<String,Object> find(List<Map<String,Object>> values,String key,Object value){return values.stream().filter(v->Objects.equals(v.get(key),value)).findFirst().orElseThrow(()->new AssertionError("Synthetic fixture reference missing"));}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
}
