package com.training;

import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Synthetic-only coverage projection and receipt consistency; no private material or working database. */
public class M01BranchCoverageTest {
    private static final String PASS="Synthetic-Coverage-Password";
    private static int checks;
    private static Auth.Session admin;
    private static OrganizationAccountImport service;
    private static Path sources;
    @FunctionalInterface private interface Work {void run() throws Exception;}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toRealPath();check(root.getFileName().toString().startsWith("yanxu-m01-branch-coverage."),"dedicated temporary root");
        Path data=root.resolve("data");sources=root.resolve("sources");Files.createDirectory(data);Files.createDirectory(sources);
        System.setProperty("data.dir",data.toString());System.setProperty("bootstrap.demo","false");System.setProperty("bootstrap.admin.password",PASS);
        try {
            Db.init(); admin=Auth.get(Auth.login("admin",PASS));check(admin!=null,"real administrator session");
            long linked=Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,'viewer',1)","synthetic-link",Auth.hash(PASS),"Different synthetic name");
            Auth.Session viewer=Auth.get(Auth.login("synthetic-link",PASS));
            Config pins=OrganizationAccountImportSourceTest.fixture(sources.resolve("valid"));
            service=new OrganizationAccountImport(pins);
            String before=dbState();
            fails(401,()->service.preview(null),"anonymous denied");fails(403,()->service.preview(viewer),"ordinary role denied");
            Auth.Session forged=new Auth.Session();forged.uid=admin.uid;forged.role="admin";
            fails(401,()->service.preview(forged),"forged identity denied");
            String token=Auth.login("admin",PASS);Auth.Session old=Auth.get(token);Auth.logout(token);
            fails(401,()->service.preview(old),"revoked session denied");
            check(before.equals(dbState()),"denied preview performs zero database writes");
            Map<String,Object> preview=service.preview(admin);
            check(before.equals(dbState()),"unreceived preview performs zero database writes");
            validateCoverage(preview,false);
            Snapshot snapshot=OrganizationAccountImportSource.load(pins);
            mustFail(()->snapshot.branchCoverage().clear(),"snapshot coverage immutable");
            mustFail(()->snapshot.branchCoverage().get(0).leaderReferences().clear(),"nested reference list immutable");
            check(Objects.equals(preview.get("branchCoverage"),service.preview(admin).get("branchCoverage")),"stable source/candidate order across reads");
            List<Map<String,Object>> rows=rows(preview.get("rows"));
            check(rows.size()==7 && rows.stream().allMatch(r->r.get("accountId")==null && r.get("personCode")==null && Boolean.FALSE.equals(r.get("alreadyImported"))),"all unreceived associations null");
            check(row(rows,"teacher-row-3").get("name").equals(row(rows,"teacher-row-4").get("name")),"fixture has ordinary teacher and leader with same name");
            List<Object> choices=new ArrayList<>();
            for(Map<String,Object> row:rows) {
                boolean link=row.get("reference").equals("teacher-row-7");
                choices.add(m("reference",row.get("reference"),"action",link?"LINK_EXISTING":"CREATE_PENDING","accountId",link?linked:null,
                        "accountProof",link?service.account(admin,linked).get("proof"):null,"reviewed",true));
            }
            Map<String,Object> command=m("expectedRevision",preview.get("revision"),"sourceFingerprint",preview.get("sourceFingerprint"),"reviewToken",preview.get("reviewToken"),"decisions",choices);
            Map<String,Object> receipt=service.commit(admin,command);
            check(rows(receipt.get("rows")).size()==7,"old commit protocol unchanged");
            check(Boolean.TRUE.equals(service.commit(admin,command).get("replayed")),"old replay protocol unchanged");
            before=dbState();preview=service.preview(admin);validateCoverage(preview,true);
            check(before.equals(dbState()),"received preview performs zero database writes");
            check(Json.parse(Json.write(receipt.get("rows"))).equals(obj(preview.get("receipt")).get("rows")),"preview preserves fixed receipt rows");
            check(Objects.equals(preview.get("branchCoverage"),new OrganizationAccountImport(pins).preview(admin).get("branchCoverage")),"fresh importer reconstructs same receipt mapping");
            List<Map<String,Object>> coverage=rows(preview.get("branchCoverage"));
            Map<String,Object> branchA=branch(coverage,"SYNTHETIC BRANCH A");
            check(rows(branchA.get("bpCandidates")).get(0).get("accountState").equals("RECEIVED_ENABLED"),"linked enabled user shown only as current account state");
            long pending=((Number)rows(branchA.get("leaderCandidates")).get(0).get("accountId")).longValue();
            Db.exec("UPDATE users SET status=1,name='Renamed synthetic account' WHERE id=?",pending);
            Map<String,Object> enabled=service.preview(admin);
            check(rows(branch(rows(enabled.get("branchCoverage")),"SYNTHETIC BRANCH A").get("leaderCandidates")).get(0).get("accountState").equals("RECEIVED_ENABLED"),"current enabled state observed without name matching");
            check(rows(enabled.get("branchCoverage")).stream().allMatch(r->Boolean.FALSE.equals(r.get("canApprove"))),"enabled does not grant approval");
            Db.exec("UPDATE users SET status=0 WHERE id=?",pending);
            receiptCorruptionChecks();
            recordCorruptionChecks();
            sourceCorruptionChecks();
            before=dbState();validateCoverage(service.preview(admin),true);check(before.equals(dbState()),"final restored preview remains read-only");
            check(OrganizationAccessStore.configuration()==null && Db.count("teachers")==0 && Db.count("demands")==0,"no organization publication or business data");
            System.out.println("M01BranchCoverage: "+checks+" checks passed (synthetic-only, isolated H2; old commit contract preserved).");
        } finally {Db.exec("SHUTDOWN");}
    }
    private static void validateCoverage(Map<String,Object> preview,boolean received)throws Exception {
        List<Map<String,Object>> coverage=rows(preview.get("branchCoverage")), candidates=rows(preview.get("rows"));
        check(coverage.size()==3,"complete three-branch fixture coverage");
        check(coverage.stream().map(r->r.get("branch")).toList().equals(List.of("SYNTHETIC BRANCH A","SYNTHETIC BRANCH B","SYNTHETIC BRANCH C")),"source branch order retained");
        Set<String> seen=new HashSet<>();
        for(Map<String,Object> branch:coverage) {
            check(branch.keySet().equals(Set.of("branch","region","leaderCandidates","bpCandidates","combinedCandidates","routingStatus","defaultHandlerReference","canApprove","readOnly","preparationOnly")),"coverage output whitelist");
            check(seen.add((String)branch.get("branch")) && branch.get("defaultHandlerReference")==null,"no duplicate branch or default handler");
            check(Boolean.FALSE.equals(branch.get("canApprove"))&&Boolean.TRUE.equals(branch.get("readOnly"))&&Boolean.TRUE.equals(branch.get("preparationOnly")),"preparation-only read-only markers");
            Set<String> leaders=new HashSet<>(),bps=new HashSet<>();
            for(String side:List.of("leaderCandidates","bpCandidates")) {
                Set<String> refs=side.equals("leaderCandidates")?leaders:bps;
                for(Map<String,Object> entry:rows(branch.get(side))) {
                    check(entry.keySet().equals(Set.of("reference","roleKinds","accountId","accountState")),"candidate output whitelist");
                    String ref=(String)entry.get("reference");check(refs.add(ref),"unique candidate reference");
                    Map<String,Object> candidate=row(candidates,ref);
                    check(!ref.equals("teacher-row-3"),"same-name ordinary teacher excluded");
                    List<String> expectedKinds=new ArrayList<>();
                    for(Map<String,Object> role:rows(candidate.get("approvalRoles")))if(((List<?>)role.get("proposedBranches")).contains(branch.get("branch")) && side.equals("bpCandidates")=="BP".equals(role.get("kind")))expectedKinds.add((String)role.get("kind"));
                    check(entry.get("roleKinds").equals(expectedKinds)&&!expectedKinds.isEmpty(),"role scopes independent and exact for branch");
                    if(received)check(entry.get("accountId").equals(candidate.get("accountId"))&&Boolean.TRUE.equals(candidate.get("alreadyImported"))&&Set.of("RECEIVED_ENABLED","RECEIVED_DISABLED").contains(entry.get("accountState")),"account mapping agrees with safe rows");
                    else check(entry.get("accountId")==null && entry.get("accountState").equals("NOT_RECEIVED"),"unreceived has no account association");
                }
            }
            Set<String> intersection=new HashSet<>(leaders);intersection.retainAll(bps);
            check(new HashSet<>((List<?>)branch.get("combinedCandidates")).equals(intersection),"combined references are branch-specific role intersection");
        }
        Map<String,Object> a=branch(coverage,"SYNTHETIC BRANCH A"),b=branch(coverage,"SYNTHETIC BRANCH B"),c=branch(coverage,"SYNTHETIC BRANCH C");
        check(refs(a,"leaderCandidates").equals(List.of("teacher-row-4","teacher-row-5","teacher-row-8")),"all three leaders retained in candidate order");
        check(a.get("routingStatus").equals("MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING"),"multi-person routing remains pending");
        check(refs(b,"leaderCandidates").equals(List.of("lead-row-9")) && rows(b.get("leaderCandidates")).get(0).get("roleKinds").equals(List.of("BRANCH_LEAD")),"lead identity preserved");
        check(refs(a,"bpCandidates").equals(List.of("teacher-row-7")) && refs(b,"bpCandidates").equals(List.of("teacher-row-7")),"BP covers only explicitly assigned region");
        check(refs(c,"leaderCandidates").equals(List.of("teacher-row-6"))&&refs(c,"bpCandidates").equals(List.of("teacher-row-6"))&&c.get("combinedCandidates").equals(List.of("teacher-row-6")),"explicit combined duties retained once per side");
        check(((List<?>)a.get("combinedCandidates")).isEmpty()&&((List<?>)b.get("combinedCandidates")).isEmpty(),"combined responsibility not expanded to other branches");
        String json=Json.write(coverage);check(!json.contains("SYNTHETIC PRIVATE")&&!json.contains("000SYN")&&!json.contains(sources.toString())&&!json.contains("sourceEvidence")&&!json.contains("personCode"),"projection contains no raw private identity/evidence");
    }
    private static void receiptCorruptionChecks()throws Exception {
        String original=(String)Db.one("SELECT receipt FROM organization_account_import_batches").get("receipt");
        for(String kind:List.of("missing-row","duplicate-ref","account","person","unknown-ref","action","flags","summary","revision","extra-key","invalid-json")) {
            Map<String,Object> value=obj(Json.parse(original));List<Map<String,Object>> values=rows(value.get("rows"));
            switch(kind) {
                case "missing-row" -> values.remove(0);
                case "duplicate-ref" -> values.get(1).put("reference",values.get(0).get("reference"));
                case "account" -> values.get(0).put("accountId",999999);
                case "person" -> values.get(0).put("personCode","imp_p_"+"0".repeat(32));
                case "unknown-ref" -> values.get(0).put("reference","teacher-row-12");
                case "action" -> values.get(0).put("action","LINK_EXISTING");
                case "flags" -> value.put("permissionsPublished",true);
                case "summary" -> obj(value.get("summary")).put("createdPending",99);
                case "revision" -> value.put("revision",999);
                case "extra-key" -> values.get(0).put("path","private-test-path");
            }
            Db.exec("UPDATE organization_account_import_batches SET receipt=?",kind.equals("invalid-json")?"{private-test-path":Json.write(value));
            String before=dbState();fails(409,()->service.preview(admin),"corrupt receipt: "+kind);check(before.equals(dbState()),"corrupt receipt is not repaired: "+kind);
            Db.exec("UPDATE organization_account_import_batches SET receipt=?",original);
        }
    }
    private static void recordCorruptionChecks()throws Exception {
        Map<String,Object> original=Db.one("SELECT * FROM organization_account_import_people WHERE source_reference='teacher-row-4'");
        for(String field:List.of("source_fingerprint","source_namespace","source_reference","batch_key","disposition","payload")) {
            Object bad=field.equals("payload")?"{\"private-test-path\":true}":field.equals("source_fingerprint")||field.equals("source_namespace")?"0".repeat(64):"broken";
            Db.exec("UPDATE organization_account_import_people SET "+field+"=? WHERE person_code=?",bad,original.get("person_code"));
            String before=dbState();fails(409,()->service.preview(admin),"corrupt intake association: "+field);check(before.equals(dbState()),"bad association is not repaired: "+field);
            Db.exec("UPDATE organization_account_import_people SET "+field+"=? WHERE person_code=?",original.get(field),original.get("person_code"));
        }
        Db.exec("UPDATE users SET status=9 WHERE id=?",original.get("account_id"));fails(409,()->service.preview(admin),"unknown current account status rejected");Db.exec("UPDATE users SET status=0 WHERE id=?",original.get("account_id"));
        Map<String,Object> user=Db.one("SELECT * FROM users WHERE id=?",original.get("account_id"));
        Db.exec("SET REFERENTIAL_INTEGRITY FALSE");
        try {Db.exec("DELETE FROM users WHERE id=?",user.get("id"));fails(409,()->service.preview(admin),"missing current account rejected");}
        finally {Db.exec("INSERT INTO users(id,username,password,name,role,status,created_at) VALUES(?,?,?,?,?,?,?)",user.get("id"),user.get("username"),user.get("password"),user.get("name"),user.get("role"),user.get("status"),user.get("created_at"));Db.exec("SET REFERENTIAL_INTEGRITY TRUE");}
        Map<String,Object> batch=Db.one("SELECT * FROM organization_account_import_batches");
        Db.exec("DELETE FROM organization_account_import_batches");fails(409,()->service.preview(admin),"orphan intake cannot appear as unreceived");
        Db.exec("INSERT INTO organization_account_import_batches(batch_key,source_fingerprint,decision_digest,receipt,created_by,created_at) VALUES(?,?,?,?,?,?)",batch.get("batch_key"),batch.get("source_fingerprint"),batch.get("decision_digest"),batch.get("receipt"),batch.get("created_by"),batch.get("created_at"));
    }
    private static void sourceCorruptionChecks()throws Exception {
        for(String kind:List.of("missing-coverage","duplicate-branch","ordinary-name-match","wrong-region","default-handler","false-combined","expand-role")) {
            var fixture=new OrganizationAccountImportSourceTest.Fixture(sources.resolve(kind));
            List<Map<String,Object>> coverage=rows(fixture.preview.get("branchCoverage"));
            switch(kind) {
                case "missing-coverage" -> fixture.preview.remove("branchCoverage");
                case "duplicate-branch" -> coverage.set(1,coverage.get(0));
                case "ordinary-name-match" -> ((List<Object>)coverage.get(0).get("candidateReferences")).set(0,"teacher-row-3");
                case "wrong-region" -> coverage.get(0).put("region","SYNTHETIC REGION TWO");
                case "default-handler" -> coverage.get(0).put("defaultHandlerReference","teacher-row-4");
                case "false-combined" -> row(rows(fixture.preview.get("rows")),"teacher-row-6").put("combinedDutiesRequiresWorkflowReview",false);
                case "expand-role" -> ((List<Object>)rows(row(rows(fixture.preview.get("rows")),"teacher-row-6").get("approvalRoles")).get(1).get("proposedBranches")).add("SYNTHETIC BRANCH A");
            }
            var invalid=new OrganizationAccountImport(fixture.save());String before=dbState();fails(409,()->invalid.preview(admin),"invalid trusted source: "+kind);check(before.equals(dbState()),"invalid coverage leaves database unchanged: "+kind);
        }
    }
    private static List<String> refs(Map<String,Object> branch,String key){return rows(branch.get(key)).stream().map(r->(String)r.get("reference")).toList();}
    private static Map<String,Object> branch(List<Map<String,Object>> rows,String name){return rows.stream().filter(r->r.get("branch").equals(name)).findFirst().orElseThrow();}
    private static Map<String,Object> row(List<Map<String,Object>> rows,String ref){return rows.stream().filter(r->r.get("reference").equals(ref)).findFirst().orElseThrow();}
    private static void fails(int expected,Work work,String label)throws Exception {try{work.run();throw new AssertionError(label);}catch(Api.ApiException e){check(e.code==expected,label);check(!e.getMessage().contains("private-test-path")&&!e.getMessage().contains("SYNTHETIC PRIVATE")&&!e.getMessage().contains(sources.toString()),label+" sanitized");}}
    private static void mustFail(Work work,String label)throws Exception {try{work.run();throw new AssertionError(label);}catch(UnsupportedOperationException e){check(true,label);}}
    private static String dbState()throws Exception {
        Map<String,Object> tables=new TreeMap<>();for(Map<String,Object> table:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            String name=String.valueOf(table.get("table_name"));List<String> contents=Db.query("SELECT * FROM \""+name+"\"").stream().map(Json::write).sorted().toList();tables.put(name,contents);
        }return Json.write(tables);
    }
    @SuppressWarnings("unchecked")private static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked")private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private static Map<String,Object> m(Object...values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    private static void check(boolean valid,String label){if(!valid)throw new AssertionError(label);checks++;}
}
