package com.training;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.training.OrganizationAccountImportSource.*;

/** Synthetic-only source fixture. No actual accounts, personnel or workbooks. */
public class OrganizationAccountImportSourceTest {
    private static int checks;
    public static Config fixture(Path dir) throws Exception { return new Fixture(dir).save(); }

    public static final class Fixture {
        public final Path dir;
        public final Map<String,Object> batch, cp, roles, auth, audit, regions, scope, preview;
        public final List<FilePin> originals;
        public Fixture(Path directory) throws Exception {
            dir=directory.toAbsolutePath().normalize(); Files.createDirectories(dir);
            FilePin teacher=write("teachers.original","SYNTHETIC TEACHER ORIGINAL"), group=write("groups.original","SYNTHETIC GROUP ORIGINAL"), region=write("regions.original","SYNTHETIC REGION ORIGINAL");
            originals=List.of(teacher,group,region);
            String tp=teacher.path().toString(),gp=group.path().toString();
            String branchA="SYNTHETIC BRANCH A",branchB="SYNTHETIC BRANCH B",branchC="SYNTHETIC BRANCH C",hq="SYNTHETIC HR";
            cp=m("version","SYNTHETIC-CANDIDATE-POLICY-v1","batchVersion","SYNTHETIC-CANDIDATES-v1", "sourceFiles",l(pin(teacher),pin(group)),
                    "teacher",m("path",tp,"sheet","Synthetic Teachers","mainRows",l(3,8),"historyRows",l(12,13)),
                    "lead",m("path",gp,"sheet","Synthetic Leads","additionalRows",l(9)),"dualIdentityReferences",l("teacher-row-8"),
                    "organizationResolutions",l(m("reference","lead-row-9","organization",branchB)),"organizationNameReviewReferences",l());
            List<Object> candidates=l();
            for(int row=3;row<=9;row++){
                boolean isTeacher=row!=9;String ref=(isTeacher?"teacher":"lead")+"-row-"+row;
                String org=row==3||row==9?branchB:row==6||row==7?hq:branchA;
                String name="SYNTHETIC PRIVATE "+(row==3?4:row);
                Map<String,Object> source=m("path",isTeacher?tp:gp,"sheet",isTeacher?"Synthetic Teachers":"Synthetic Leads","range",(isTeacher?"A":"B")+row+":"+(isTeacher?"F":"D")+row,isTeacher?"source_row":"row",row);
                String id=Set.of(4,6,8,9).contains(row)?"000SYN"+row:null;
                candidates.add(m("candidate_reference",ref,"name",name,"organization",org,"source_job",null,"source_teacher_level",null,
                        "identities",row==8?l("兼职教师","分公司牵头人"):isTeacher?l("兼职教师"):l("分公司牵头人"),"source",source,
                        "group_match",id==null?"NOT_FOUND_IN_GROUP_ROSTER":row==9?"LEAD_GROUP_WITH_USER_ORGANIZATION_RESOLUTION":"EXACT_NAME_AND_ORGANIZATION",
                        "group_evidence",id==null?l():l(m("name",name,"organization",org,"source_staff_id",id)),"source_staff_id_candidate",id,
                        "account_id",null,"username",null,"email",null,"business_role_codes",l(),"account_disposition","PREPARE_ACCOUNT_ONLY","identity_binding_verified",false));
            }
            List<Object> history=l();for(int row:List.of(12,13))history.add(m("row",row,"name","SYNTHETIC EXCLUDED "+row,"organization",branchA,"account_disposition","DO_NOT_CREATE","source",m("path",tp,"sheet","Synthetic Teachers","source_row",row,"range","A"+row+":F"+row)));
            batch=m("version",cp.get("batchVersion"),"source_files",cp.get("sourceFiles"),"candidates",candidates,"historical_exclusions",history);
            List<Object> links=l(
                    assignment("teacher-row-4","BRANCH_RESPONSIBLE",20,branchA,branchA), assignment("teacher-row-5","BRANCH_RESPONSIBLE",21,branchA,branchA),
                    assignment("teacher-row-8","BRANCH_LEAD",22,branchA,branchA),assignment("lead-row-9","BRANCH_LEAD",23,branchB,"SYNTHETIC OLD BRANCH"),
                    assignment("teacher-row-6","BP",30,hq,hq),assignment("teacher-row-7","BP",31,hq,hq));
            auth=m("version","SYNTHETIC-APPROVAL-AUTHORIZATION-v2","decision",m("date","SYNTHETIC","reference","SYNTHETIC DECISION","scope","BRANCH_AND_BP_ONLY","ordinaryTeacherApproval",false,"bpScope","USER_CONFIRMED"),
                    "regionSource",pin(region),"branchSource",m("path",gp,"sheet","Synthetic Branch Roles","rows",l(20,23),"leadRows",l(22,23)),
                    "bpSource",m("path",gp,"sheet","Synthetic BP Roles","rows",l(30,31),"organization",hq),"assignments",links,
                    "bpRegionAssignments",l(m("reference","teacher-row-6","region","SYNTHETIC REGION TWO","decisionIndex",0),m("reference","teacher-row-7","region","SYNTHETIC REGION ONE","decisionIndex",1)),
                    "branchManagementAssignments",l(m("reference","teacher-row-6","organization",branchC,"decisionIndex",0)));
            List<Object> branchRoles=l(),bpRoles=l(),auditBranch=l(),auditBp=l(),auditLeads=l();
            for(Object value:links){Map<String,Object>a=o(value),person=find(candidates,"candidate_reference",a.get("reference"));boolean bp="BP".equals(a.get("role"));int row=((Number)a.get("sourceRow")).intValue();
                String sheet=bp?"Synthetic BP Roles":"Synthetic Branch Roles",range="B"+row+":D"+row;
                Object id=person.get("source_staff_id_candidate");if(row==31)id="000SYN7";
                Map<String,Object> origin=m("name",person.get("name"),"staff_id",id,"organization",a.get("organization"),"source_sheet",sheet,"source_range",range,
                        "approval_identity",bp?"HR-BP":"BRANCH_LEAD".equals(a.get("role"))?"分公司牵头人":"分公司负责人组成员");
                Map<String,Object> auditRow=m("row",row,"organization",a.get("sourceOrganization"),"organization_cell","B"+row,"name",person.get("name"),"staff_id",id,"range",range);
                if(bp){origin.put("responsible_region",null);bpRoles.add(origin);auditBp.add(auditRow);}else{origin.put("region","SYNTHETIC REGION ONE");origin.put("source_organization",a.get("sourceOrganization"));origin.put("teacher_source",person.get("source"));branchRoles.add(origin);auditBranch.add(auditRow);if("BRANCH_LEAD".equals(a.get("role")))auditLeads.add(auditRow);}
            }
            roles=m("branch_approvers",branchRoles,"bp_approvers",bpRoles);
            scope=m("bp_region_assignments",l(
                    m("name",o(bpRoles.get(0)).get("name"),"source_staff_id",o(bpRoles.get(0)).get("staff_id"),"region","SYNTHETIC REGION TWO"),
                    m("name",o(bpRoles.get(1)).get("name"),"source_staff_id",o(bpRoles.get(1)).get("staff_id"),"region","SYNTHETIC REGION ONE")),
                    "branch_management_overrides",l(m("organization",branchC,"manager_name",o(bpRoles.get(0)).get("name"),"source_staff_id",o(bpRoles.get(0)).get("staff_id"))));
            regions=m("regions",l(m("display_name","SYNTHETIC REGION ONE","branches",l(branch(branchA,3),branch(branchB,4))),m("display_name","SYNTHETIC REGION TWO","branches",l(branch(branchC,5)))));
            audit=m("audits",l(pin(group),pin(region)),"group_including_leads",auditBranch,"bp_members",auditBp,"lead_difference",auditLeads,
                    "region_branches",l(m("region","SYNTHETIC REGION ONE","branch",branchA,"row",3,"range","A3:B3"),m("region","SYNTHETIC REGION ONE","branch",branchB,"row",4,"range","A4:B4"),m("region","SYNTHETIC REGION TWO","branch",branchC,"row",5,"range","A5:B5")));
            preview=makePreview(candidates,history,links,teacher,group);
        }
        private Map<String,Object> makePreview(List<Object> candidates,List<Object> history,List<Object> links,FilePin teacher,FilePin group){
            Map<String,List<Object>> perCandidate=new LinkedHashMap<>();
            for(Object value:links){Map<String,Object>a=o(value),person=find(candidates,"candidate_reference",a.get("reference"));boolean bp="BP".equals(a.get("role"));int row=((Number)a.get("sourceRow")).intValue();String ref=(String)a.get("reference");
                Map<String,Object> role=m("kind",a.get("role"),"source",m("path",group.path().toString(),"sha256",group.sha256(),"sheet",bp?"Synthetic BP Roles":"Synthetic Branch Roles","range","B"+row+":D"+row,"row",row),
                        "sourceOrganization",a.get("sourceOrganization"),"currentOrganization",a.get("organization"),"organizationResolution",a.get("sourceOrganization").equals(a.get("organization"))?"SOURCE_RETAINED":"USER_CONFIRMED",
                        "sourceIdentityCheck",person.get("source_staff_id_candidate")==null?"SOURCE_ID_PENDING_CANDIDATE_RETAINED":"SOURCE_IDS_AGREE_NOT_ACCOUNT_BINDING",
                        "scopeStatus",bp?"USER_CONFIRMED_REGION":"CORRESPONDING_BRANCH_ONLY","proposedBranches",bp?(row==30?l("SYNTHETIC BRANCH C"):l("SYNTHETIC BRANCH A","SYNTHETIC BRANCH B")):l(a.get("organization")),
                        "proposedRegions",bp?l(row==30?"SYNTHETIC REGION TWO":"SYNTHETIC REGION ONE"):l(),"canApprove",false);
                perCandidate.put(ref,l(role));
            }
            Map<String,Object> manager=m("kind","BRANCH_RESPONSIBLE","source",m("reference","branch_management_overrides[0]"),
                    "sourceOrganization","SYNTHETIC HR","currentOrganization","SYNTHETIC HR","organizationResolution","USER_CONFIRMED_MANAGEMENT_SCOPE_HOME_ORGANIZATION_UNCHANGED",
                    "sourceIdentityCheck","SOURCE_IDS_AGREE_NOT_ACCOUNT_BINDING","scopeStatus","CORRESPONDING_BRANCH_ONLY","proposedBranches",l("SYNTHETIC BRANCH C"),"proposedRegions",l(),"canApprove",false);
            perCandidate.get("teacher-row-6").add(manager);
            List<Object> out=l();
            for(Object value:candidates){Map<String,Object>person=o(value),source=o(person.get("source"));String ref=(String)person.get("candidate_reference");boolean bp=Set.of("teacher-row-6","teacher-row-7").contains(ref);List<Object>pr=perCandidate.getOrDefault(ref,l());Object org=person.get("organization");
                out.add(m("reference",ref,"candidateSource",m("sheet",source.get("sheet"),"range",source.get("range"),"row",source.getOrDefault("source_row",source.get("row"))),
                        "identities",person.get("identities"),"organization",org,"region","SYNTHETIC HR".equals(org)?null:"SYNTHETIC REGION ONE","accountDisposition","PREPARE_ACCOUNT_ONLY",
                        "approvalEligibility",pr.isEmpty()?"NO_APPROVAL_ROLE":bp?"BP_SCOPE_PREPARED":"BRANCH_SCOPE_PREPARED","approvalRoles",pr,"canApprove",false,"combinedDutiesRequiresWorkflowReview",ref.equals("teacher-row-6"),"formalPersonCode",null,"formalOrganizationCode",null,"accountBinding",null));
            }
            List<Object> coverage=l();
            for(Object regionValue:a(audit.get("region_branches"))){Map<String,Object>r=o(regionValue);String branch=(String)r.get("branch");List<Object>refs=l();for(Object rowValue:out){Map<String,Object>rr=o(rowValue);for(Object roleValue:a(rr.get("approvalRoles"))){Map<String,Object>role=o(roleValue);if(!"BP".equals(role.get("kind"))&&a(role.get("proposedBranches")).contains(branch)){refs.add(rr.get("reference"));break;}}}
                coverage.add(m("organization",branch,"region",r.get("region"),"source",m("sheet","Synthetic Regions","range",r.get("range")),"candidateReferences",refs,"status",refs.size()>1?"MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING":"ROLE_EVIDENCE_PREPARED","defaultHandlerReference",null));}
            List<Object> hist=l();for(Object v:history){Map<String,Object>h=o(v);hist.add(m("source",m("sheet","Synthetic Teachers","range","A"+h.get("row")+":F"+h.get("row"),"row",h.get("row")),"disposition","DO_NOT_CREATE"));}
            return m("previewVersion","M01-APPROVAL-ROLE-PREVIEW-v1","policyVersion",auth.get("version"),"scopeValidated",true,"accountScopeValidated",true,"canCreateAccounts",false,"canPublishConfiguration",false,"writesPerformed",false,
                    "summary",m("candidates",7,"sourceBranchRolePeople",4,"branchRolePeople",5,"branchLeads",2,"bpRolePeople",2,"bpScopesPrepared",2,"uniqueApprovalPeople",6,"preparedRoleAssignments",7,"peopleWithCombinedDuties",1,"ordinaryCandidatesWithoutApproval",1,"branchesTotal",3,"branchesCovered",3,"branchesMissingAssignee",0,"branchesWithMultipleIdentities",1,"historicalExcluded",2,"actualApprovalPermissionsAssigned",0),
                    "rows",out,"branchCoverage",coverage,"historicalExclusions",hist,"decision",m("reference","SYNTHETIC DECISION","scope","BRANCH_AND_BP_ONLY","ordinaryTeacherApproval",false,"bpScope","USER_CONFIRMED"),"issues",l(),"accountIssues",l(),
                    "requiredBeforePublication",l("CONFIRM_MULTI_PERSON_ROUTING","HANDLE_SAME_PERSON_BRANCH_BP_WORKFLOW","ASSIGN_FORMAL_PERSON_AND_ORGANIZATION_CODES","RECONCILE_EXISTING_ACCOUNTS","EXPLICIT_ACCOUNT_BINDINGS","SERVER_AUTHORIZATION_VALIDATION_AND_VERSION_CHECK"));
        }
        /** Re-pins a reviewed synthetic mutation to exercise semantic validation beyond hashes. */
        public Config save() throws Exception {
            FilePin batchPin=writeJson("candidates.json",batch),regionPin=writeJson("region-reference.json",regions),rolePin=writeJson("role-source.json",roles),auditPin=writeJson("audit.json",audit),scopePin=writeJson("scope.json",scope);
            cp.put("candidateSha256",batchPin.sha256());cp.put("regionReferenceSha256",regionPin.sha256());FilePin cpPin=writeJson("candidate-policy.json",cp);
            auth.put("candidatePolicySha256",cpPin.sha256());auth.put("roleSourceSha256",rolePin.sha256());auth.put("sourceAuditSha256",auditPin.sha256());auth.put("scopeDecision",pin(scopePin));
            FilePin authPin=writeJson("authorization.json",auth);
            for(Object value:a(preview.get("rows")))for(Object roleValue:a(o(value).get("approvalRoles"))){Map<String,Object>role=o(roleValue);
                if("BP".equals(role.get("kind"))){String ref=(String)o(value).get("reference");role.put("scopeDecisionSource",m("path",scopePin.path().toString(),"sha256",scopePin.sha256(),"reference","bp_region_assignments["+(ref.equals("teacher-row-6")?0:1)+"]"));}
                Map<String,Object>s=o(role.get("source"));if(s!=null&&s.containsKey("reference")){s.put("path",scopePin.path().toString());s.put("sha256",scopePin.sha256());}}
            preview.put("evidence",m("candidateSha256",batchPin.sha256(),"candidatePolicySha256",cpPin.sha256(),"roleSourceSha256",rolePin.sha256(),"sourceAuditSha256",auditPin.sha256(),"regionReferenceSha256",regionPin.sha256(),"authorizationSha256",authPin.sha256(),"sourceFiles",l(pin(originals.get(0)),pin(originals.get(1)),pin(originals.get(2))),"scopeDecision",pin(scopePin),"sourceDigestsVerified",true));
            return new Config("synthetic-import",batchPin,cpPin,rolePin,authPin,auditPin,regionPin,scopePin,writeJson("prepared-preview.json",preview),originals);
        }
        private FilePin writeJson(String name,Object value)throws Exception{return write(name,Json.write(value));}
        private FilePin write(String name,String value)throws Exception{Path p=dir.resolve(name);Files.writeString(p,value,StandardCharsets.UTF_8);return new FilePin(p,hash(p));}
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("m01-source-synthetic-");
        try{
            Fixture f=new Fixture(root.resolve("valid"));Config c=f.save();Snapshot s=OrganizationAccountImportSource.load(c);
            ok(s.candidates().size()==7&&s.excludedReferences().size()==2,"scope");
            ok(s.candidates().stream().filter(x->x.name().equals("SYNTHETIC PRIVATE 4")).count()==2,"same name not merged");
            Candidate combined=s.candidates().stream().filter(x->x.reference().equals("teacher-row-6")).findFirst().orElseThrow();
            ok(a(combined.preparation().get("approvalRoles")).size()==2,"combined roles preserved");
            ok("AVAILABLE".equals(combined.preparation().get("sourceIdentityEvidence")),"available source evidence flag");
            ok("PENDING_IDENTITY_REVIEW".equals(s.candidates().get(0).preparation().get("sourceIdentityEvidence")),"missing identifier retained");
            ok(OrganizationAccountImportSource.load(c).fingerprint().equals(s.fingerprint()),"stable fingerprint");
            mustFail(()->combined.preparation().put("x",true),false,"immutable map");
            mustFail(()->a(combined.preparation().get("approvalRoles")).add(m()),false,"immutable nested list");
            mustFail(()->o(a(combined.preparation().get("approvalRoles")).get(0)).put("x",true),false,"immutable role");
            String safe=Json.write(combined.preparation());ok(!safe.contains("SYNTHETIC PRIVATE")&&!safe.contains("000SYN"),"private fields excluded");
            Files.writeString(c.candidates().path(),"tampered");mustFail(()->OrganizationAccountImportSource.load(c),true,"tampered file");
            f.save();Files.writeString(c.originals().get(0).path(),"changed original");mustFail(()->OrganizationAccountImportSource.load(c),true,"original changed");
            mutation(root,"write-switch",x->x.preview.put("canPublishConfiguration",true));
            mutation(root,"row-approve",x->o(a(x.preview.get("rows")).get(0)).put("canApprove",true));
            mutation(root,"formal-code",x->o(a(x.preview.get("rows")).get(0)).put("formalPersonCode","SYNTHETIC CODE"));
            mutation(root,"drop-row",x->a(x.preview.get("rows")).remove(0));
            mutation(root,"duplicate-ref",x->o(a(x.preview.get("rows")).get(0)).put("reference","teacher-row-4"));
            mutation(root,"flatten-combined",x->a(find(a(x.preview.get("rows")),"reference","teacher-row-6").get("approvalRoles")).remove(1));
            mutation(root,"expand-branch",x->a(o(a(find(a(x.preview.get("rows")),"reference","teacher-row-4").get("approvalRoles")).get(0)).get("proposedBranches")).add("SYNTHETIC BRANCH B"));
            mutation(root,"bp-wrong-region",x->o(a(x.auth.get("bpRegionAssignments")).get(0)).put("region","SYNTHETIC REGION ONE"));
            mutation(root,"history-create",x->o(a(x.batch.get("historical_exclusions")).get(0)).put("account_disposition","PREPARE_ACCOUNT_ONLY"));
            mutation(root,"missing-no-id",x->a(x.batch.get("candidates")).remove(0));
            mutation(root,"source-row-wrong",x->o(o(a(x.batch.get("candidates")).get(0)).get("source")).put("source_row",12));
            mutation(root,"audit-identity",x->o(a(x.audit.get("bp_members")).get(0)).put("staff_id","SYNTHETIC WRONG"));
            mutation(root,"old-policy",x->x.auth.put("version","SYNTHETIC-APPROVAL-AUTHORIZATION-v1"));
            mutation(root,"wrong-scope-decision",x->o(a(x.scope.get("bp_region_assignments")).get(0)).put("source_staff_id","SYNTHETIC WRONG"));
            mutation(root,"ordinary-extra-role",x->a(o(a(x.preview.get("rows")).get(0)).get("approvalRoles")).add(m("kind","BRANCH_RESPONSIBLE")));
            mutation(root,"history-live-identity",x->{Map<String,Object>h=o(a(x.batch.get("historical_exclusions")).get(0)),live=o(a(x.batch.get("candidates")).get(0));h.put("name",live.get("name"));h.put("organization",live.get("organization"));});
            mutation(root,"summary-lies",x->o(x.preview.get("summary")).put("uniqueApprovalPeople",7));
            mutation(root,"default-handler",x->o(a(x.preview.get("branchCoverage")).get(0)).put("defaultHandlerReference","teacher-row-4"));
            mutation(root,"duplicate-source-id",x->{Map<String,Object>person=o(a(x.batch.get("candidates")).get(3));person.put("source_staff_id_candidate","000SYN4");o(a(person.get("group_evidence")).get(0)).put("source_staff_id","000SYN4");});
            Fixture extra=new Fixture(root.resolve("extra"));o(a(extra.preview.get("rows")).get(0)).put("name","SYNTHETIC EXTRA PRIVATE");
            Map<String,Object>prep=OrganizationAccountImportSource.load(extra.save()).candidates().get(0).preparation();ok(!prep.containsKey("name"),"unknown personal field stripped");
            Config valid=fixture(root.resolve("path-tests"));
            mustFail(()->OrganizationAccountImportSource.load(new Config(valid.batchKey(),valid.candidates(),valid.candidates(),valid.roleSource(),valid.authorization(),valid.audit(),valid.regionReference(),valid.scopeDecision(),valid.preparedPreview(),valid.originals())),true,"duplicate path");
            Path hard=root.resolve("hard-link");Files.createLink(hard,valid.candidates().path());
            mustFail(()->OrganizationAccountImportSource.load(new Config(valid.batchKey(),valid.candidates(),new FilePin(hard,valid.candidates().sha256()),valid.roleSource(),valid.authorization(),valid.audit(),valid.regionReference(),valid.scopeDecision(),valid.preparedPreview(),valid.originals())),true,"duplicate inode");
            Path sym=root.resolve("symbolic-link");Files.createSymbolicLink(sym,valid.candidates().path());
            mustFail(()->OrganizationAccountImportSource.load(new Config(valid.batchKey(),new FilePin(sym,valid.candidates().sha256()),valid.candidatePolicy(),valid.roleSource(),valid.authorization(),valid.audit(),valid.regionReference(),valid.scopeDecision(),valid.preparedPreview(),valid.originals())),true,"symbolic source");
            mustFail(()->OrganizationAccountImportSource.load(withPreview(valid,new FilePin(root,"a".repeat(64)))),true,"directory pin");
            mustFail(()->OrganizationAccountImportSource.load(withPreview(valid,new FilePin(root.resolve("missing"),"a".repeat(64)))),true,"missing pin");
            mustFail(()->OrganizationAccountImportSource.load(withPreview(valid,new FilePin(valid.preparedPreview().path(),"a".repeat(64)))),true,"wrong pin hash");
            Path malformed=root.resolve("malformed.json");Files.writeString(malformed,"{\"SYNTHETIC PRIVATE\":");
            mustFail(()->OrganizationAccountImportSource.load(withPreview(valid,new FilePin(malformed,hash(malformed)))),true,"malformed JSON private error");
            Path invalidUtf8=root.resolve("invalid-utf8.json");Files.write(invalidUtf8,new byte[]{(byte)0xc3,0x28});
            mustFail(()->OrganizationAccountImportSource.load(withPreview(valid,new FilePin(invalidUtf8,hash(invalidUtf8)))),true,"malformed UTF8");
            Path duplicate=root.resolve("duplicate-keys.json");Files.writeString(duplicate,"{\"SYNTHETIC PRIVATE\":0,\"SYNTHETIC PRIVATE\":1}");
            mustFail(()->OrganizationAccountImportSource.load(withPreview(valid,new FilePin(duplicate,hash(duplicate)))),true,"duplicate JSON keys");
            Path oversized=root.resolve("oversized.json");try(var file=new java.io.RandomAccessFile(oversized.toFile(),"rw")){file.setLength(8L*1024*1024+1);}
            mustFail(()->OrganizationAccountImportSource.load(withPreview(valid,new FilePin(oversized,"a".repeat(64)))),true,"bounded JSON size");
            mustFail(()->OrganizationAccountImportSource.load(null),true,"null config");
            System.out.println("OrganizationAccountImportSource synthetic checks passed: "+checks);
        }finally{try(var stream=Files.walk(root)){for(Path p:stream.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private interface Attempt{void run()throws Exception;}
    private interface Mutation{void apply(Fixture fixture)throws Exception;}
    private static void mutation(Path root,String label,Mutation mutation)throws Exception{Fixture f=new Fixture(root.resolve(label));mutation.apply(f);Config c=f.save();mustFail(()->OrganizationAccountImportSource.load(c),true,label);}
    private static void mustFail(Attempt a,boolean sanitized,String label)throws Exception{try{a.run();throw new AssertionError(label);}catch(IllegalArgumentException|UnsupportedOperationException e){if(sanitized)ok(e.getMessage().equals("账号导入来源核验失败，请重新核对可信材料。")&&e.getCause()==null,label+" safe error");else checks++;}}
    private static void ok(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static Config withPreview(Config c,FilePin p){return new Config(c.batchKey(),c.candidates(),c.candidatePolicy(),c.roleSource(),c.authorization(),c.audit(),c.regionReference(),c.scopeDecision(),p,c.originals());}
    private static Map<String,Object> assignment(String ref,String kind,int row,String org,String old){return m("reference",ref,"role",kind,"sourceRow",row,"organization",org,"sourceOrganization",old);}
    private static Map<String,Object> branch(String name,int row){return m("display_name",name,"organization_code",null,"region_reference",m("sheet","Synthetic Regions","range","A"+row+":B"+row));}
    private static Map<String,Object> pin(FilePin pin){return m("path",pin.path().toString(),"sha256",pin.sha256());}
    private static String hash(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    private static Map<String,Object> find(List<Object> values,String key,Object value){return values.stream().map(OrganizationAccountImportSourceTest::o).filter(x->Objects.equals(x.get(key),value)).findFirst().orElseThrow();}
    @SuppressWarnings("unchecked") private static Map<String,Object> o(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked") private static List<Object> a(Object v){return (List<Object>)v;}
    private static Map<String,Object> m(Object...values){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
    private static List<Object> l(Object...values){return new ArrayList<>(Arrays.asList(values));}
}
