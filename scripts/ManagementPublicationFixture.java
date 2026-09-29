package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Fresh synthetic pins only. Approved display labels exercise exact membership checks, not real identities. */
public final class ManagementPublicationFixture {
    static final String HQ="公司总经理室", HR="人力资源部", HQ_CODE="SYNTHETIC_HQ", HR_CODE="SYNTHETIC_HR";
    static final List<String> REGIONS=List.of("东区","南区","西区","北区","中区");
    static final Map<Integer,String> NAMES=Map.of(3,"毛巨策",4,"赵向草",5,"张靖",6,"王旭东",7,"梁运华",9,"童雨晴",10,"郑冬浩");
    final Path dir;
    final List<FilePin> originals;
    final Map<String,Object> batch,cp,roles,auth,audit,regions,scope,preview;
    final LinkedHashMap<String,String> branches=new LinkedHashMap<>();
    final Map<String,Map<String,Object>> people=new LinkedHashMap<>();
    final Map<String,List<Object>> preparedRoles=new LinkedHashMap<>();
    ManagementPublicationFixture(Path root)throws Exception {
        dir=root.toAbsolutePath().normalize();Files.createDirectories(dir);
        FilePin teacher=write("teachers.original","SYNTHETIC TEACHER ORIGINAL"),group=write("groups.original","SYNTHETIC GROUP ORIGINAL"),region=write("regions.original","SYNTHETIC REGION ORIGINAL");
        originals=List.of(teacher,group,region);
        for(int i=0;i<37;i++)branches.put(i==0?"烟台分公司":i==1?"济南分公司":"SYNTHETIC BRANCH "+i,i<2?"东区":REGIONS.get(i%5));
        List<String> branchNames=new ArrayList<>(branches.keySet());
        List<Object> candidates=l(),historical=l(),historyPreview=l();
        for(int row=3;row<=10;row++)candidates.add(candidate("teacher",row,NAMES.getOrDefault(row,"SYNTHETIC NONMEMBER TEACHER"),row<=7?HQ:row==8?branchNames.get(0):HR,teacher));
        candidates.add(candidate("lead",9,"SYNTHETIC NONMEMBER LEAD",branchNames.get(1),group));
        for(Object raw:candidates){Map<String,Object> person=o(raw);people.put((String)person.get("candidate_reference"),person);}
        for(int row=185;row<=193;row++){
            historical.add(m("row",row,"name","SYNTHETIC HISTORY "+row,"organization","SYNTHETIC HISTORY HOME","account_disposition","DO_NOT_CREATE","source",m("path",teacher.path().toString(),"sheet","Synthetic Teachers","source_row",row,"range","A"+row+":F"+row)));
            historyPreview.add(m("source",m("sheet","Synthetic Teachers","row",row,"range","A"+row+":F"+row),"disposition","DO_NOT_CREATE"));
        }
        cp=m("version","SYNTHETIC-CANDIDATE-POLICY-v1","batchVersion","SYNTHETIC-CANDIDATES-v1","sourceFiles",l(pin(teacher),pin(group)),"teacher",m("path",teacher.path().toString(),"sheet","Synthetic Teachers","mainRows",l(3,10),"historyRows",l(185,193)),"lead",m("path",group.path().toString(),"sheet","Synthetic Leads","additionalRows",l(9)),"dualIdentityReferences",l("teacher-row-8"),"organizationResolutions",l(),"organizationNameReviewReferences",l());
        batch=m("version",cp.get("batchVersion"),"source_files",cp.get("sourceFiles"),"candidates",candidates,"historical_exclusions",historical);
        List<Object> assignments=l(),branchRoles=l(),bpRoles=l(),auditBranch=l(),auditBp=l(),auditLeads=l(),bpAssignments=l(),bpDecisions=l(),managers=l(),managerDecisions=l();
        for(int i=0;i<2;i++)addAssignment(i==0?"teacher-row-8":"lead-row-9","BRANCH_LEAD",20+i,branchNames.get(i),group,assignments,branchRoles,auditBranch,auditLeads);
        for(int i=0;i<5;i++){
            String ref="teacher-row-"+(i+3),regionName=REGIONS.get(i);Map<String,Object> person=people.get(ref);
            addAssignment(ref,"BP",30+i,HQ,group,assignments,bpRoles,auditBp,l());
            bpAssignments.add(m("reference",ref,"region",regionName,"decisionIndex",i));
            bpDecisions.add(m("name",person.get("name"),"source_staff_id",person.get("source_staff_id_candidate"),"region",regionName));
            Map<String,Object> role=o(preparedRoles.get(ref).get(0));role.put("scopeStatus","USER_CONFIRMED_REGION");role.put("proposedRegions",l(regionName));
            role.put("proposedBranches",new ArrayList<>(branches.entrySet().stream().filter(e->e.getValue().equals(regionName)).map(Map.Entry::getKey).toList()));
        }
        int override=0;
        for(var branch:branches.entrySet()){
            String ref="teacher-row-"+(3+REGIONS.indexOf(branch.getValue()));Map<String,Object> person=people.get(ref);
            managers.add(m("reference",ref,"organization",branch.getKey(),"decisionIndex",override));
            managerDecisions.add(m("organization",branch.getKey(),"manager_name",person.get("name"),"source_staff_id",person.get("source_staff_id_candidate")));
            preparedRoles.get(ref).add(m("kind","BRANCH_RESPONSIBLE","source",m("reference","branch_management_overrides["+override+"]"),"sourceOrganization",HQ,"currentOrganization",HQ,"organizationResolution","USER_CONFIRMED_MANAGEMENT_SCOPE_HOME_ORGANIZATION_UNCHANGED","sourceIdentityCheck","SOURCE_IDS_AGREE_NOT_ACCOUNT_BINDING","scopeStatus","CORRESPONDING_BRANCH_ONLY","proposedBranches",l(branch.getKey()),"proposedRegions",l(),"canApprove",false));override++;
        }
        auth=m("version","SYNTHETIC-APPROVAL-AUTHORIZATION-v2","decision",m("date","SYNTHETIC","reference","SYNTHETIC DECISION","scope","BRANCH_AND_BP_ONLY","ordinaryTeacherApproval",false,"bpScope","USER_CONFIRMED"),"regionSource",pin(region),"branchSource",m("path",group.path().toString(),"sheet","Synthetic Branch Roles","rows",l(20,21),"leadRows",l(20,21)),"bpSource",m("path",group.path().toString(),"sheet","Synthetic BP Roles","rows",l(30,34),"organization",HQ),"assignments",assignments,"bpRegionAssignments",bpAssignments,"branchManagementAssignments",managers);
        roles=m("branch_approvers",branchRoles,"bp_approvers",bpRoles);scope=m("bp_region_assignments",bpDecisions,"branch_management_overrides",managerDecisions);
        List<Object> regionRows=l(),auditRegions=l(),coverage=l();
        for(String regionName:REGIONS){List<Object> regionBranches=l();int row=3;
            for(var branch:branches.entrySet()){
                if(branch.getValue().equals(regionName))regionBranches.add(m("display_name",branch.getKey(),"organization_code",null,"region_reference",m("sheet","Synthetic Regions","range","A"+row+":B"+row)));
                row++;
            }regionRows.add(m("display_name",regionName,"branches",regionBranches));
        }
        int row=3;for(var branch:branches.entrySet()){
            auditRegions.add(m("region",branch.getValue(),"branch",branch.getKey(),"row",row,"range","A"+row+":B"+row));
            List<Object> references=l();for(var entry:preparedRoles.entrySet())for(Object raw:entry.getValue()){Map<String,Object> r=o(raw);if(!r.get("kind").equals("BP")&&a(r.get("proposedBranches")).contains(branch.getKey())){references.add(entry.getKey());break;}}
            coverage.add(m("organization",branch.getKey(),"region",branch.getValue(),"source",m("sheet","Synthetic Regions","range","A"+row+":B"+row),"candidateReferences",references,"status",references.size()>1?"MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING":"ROLE_EVIDENCE_PREPARED","defaultHandlerReference",null));row++;
        }
        regions=m("regions",regionRows);audit=m("audits",l(pin(group),pin(region)),"group_including_leads",auditBranch,"bp_members",auditBp,"lead_difference",auditLeads,"region_branches",auditRegions);
        List<Object> out=l();for(var entry:people.entrySet()){
            String ref=entry.getKey();Map<String,Object> person=entry.getValue(),source=o(person.get("source"));List<Object> pr=preparedRoles.getOrDefault(ref,l());boolean bp=pr.stream().map(ManagementPublicationFixture::o).anyMatch(r->"BP".equals(r.get("kind"))),branch=pr.stream().map(ManagementPublicationFixture::o).anyMatch(r->!"BP".equals(r.get("kind")));
            out.add(m("reference",ref,"candidateSource",m("sheet",source.get("sheet"),"range",source.get("range"),"row",source.getOrDefault("source_row",source.get("row"))),"identities",person.get("identities"),"organization",person.get("organization"),"region",branches.get(person.get("organization")),"accountDisposition","PREPARE_ACCOUNT_ONLY","approvalEligibility",pr.isEmpty()?"NO_APPROVAL_ROLE":bp?"BP_SCOPE_PREPARED":"BRANCH_SCOPE_PREPARED","approvalRoles",pr,"canApprove",false,"combinedDutiesRequiresWorkflowReview",bp&&branch,"formalPersonCode",null,"formalOrganizationCode",null,"accountBinding",null));
        }
        preview=m("previewVersion","M01-APPROVAL-ROLE-PREVIEW-v1","policyVersion",auth.get("version"),"scopeValidated",true,"accountScopeValidated",true,"canCreateAccounts",false,"canPublishConfiguration",false,"writesPerformed",false,"summary",m("candidates",9,"sourceBranchRolePeople",2,"branchRolePeople",7,"branchLeads",2,"bpRolePeople",5,"bpScopesPrepared",5,"uniqueApprovalPeople",7,"preparedRoleAssignments",44,"peopleWithCombinedDuties",5,"ordinaryCandidatesWithoutApproval",2,"branchesTotal",37,"branchesCovered",37,"branchesMissingAssignee",0,"branchesWithMultipleIdentities",2,"historicalExcluded",9,"actualApprovalPermissionsAssigned",0),"rows",out,"branchCoverage",coverage,"historicalExclusions",historyPreview,"decision",m("reference","SYNTHETIC DECISION","scope","BRANCH_AND_BP_ONLY","ordinaryTeacherApproval",false,"bpScope","USER_CONFIRMED"),"issues",l(),"accountIssues",l(),"requiredBeforePublication",l("CONFIRM_MULTI_PERSON_ROUTING","HANDLE_SAME_PERSON_BRANCH_BP_WORKFLOW","ASSIGN_FORMAL_PERSON_AND_ORGANIZATION_CODES","RECONCILE_EXISTING_ACCOUNTS","EXPLICIT_ACCOUNT_BINDINGS","SERVER_AUTHORIZATION_VALIDATION_AND_VERSION_CHECK"));
    }
    private Map<String,Object> candidate(String kind,int row,String name,String org,FilePin file){
        boolean teacher=kind.equals("teacher");String ref=kind+"-row-"+row,id="SYNTHETIC_"+kind+"_"+row;
        return m("candidate_reference",ref,"name",name,"organization",org,"source_job",null,"source_teacher_level",null,"identities",teacher?(row==8?l("兼职教师","分公司牵头人"):l("兼职教师")):l("分公司牵头人"),"source",m("path",file.path().toString(),"sheet",teacher?"Synthetic Teachers":"Synthetic Leads","range",(teacher?"A":"B")+row+":"+(teacher?"F":"D")+row,teacher?"source_row":"row",row),"group_match","EXACT_NAME_AND_ORGANIZATION","group_evidence",l(m("name",name,"organization",org,"source_staff_id",id)),"source_staff_id_candidate",id,"account_id",null,"username",null,"email",null,"business_role_codes",l(),"account_disposition","PREPARE_ACCOUNT_ONLY","identity_binding_verified",false);
    }
    private void addAssignment(String ref,String kind,int row,String org,FilePin group,List<Object> assignments,List<Object> roleRows,List<Object> auditRows,List<Object> leads){
        boolean bp=kind.equals("BP");Map<String,Object> person=people.get(ref);String sheet=bp?"Synthetic BP Roles":"Synthetic Branch Roles",range="B"+row+":D"+row;
        assignments.add(m("reference",ref,"role",kind,"sourceRow",row,"organization",org,"sourceOrganization",org));
        Map<String,Object> origin=m("name",person.get("name"),"staff_id",person.get("source_staff_id_candidate"),"organization",org,"source_sheet",sheet,"source_range",range,"approval_identity",bp?"HR-BP":"分公司牵头人");
        if(bp)origin.put("responsible_region",null);else{origin.put("region",branches.get(org));origin.put("source_organization",org);origin.put("teacher_source",person.get("source"));}
        roleRows.add(origin);Map<String,Object> auditRow=m("row",row,"organization",org,"organization_cell","B"+row,"name",person.get("name"),"staff_id",person.get("source_staff_id_candidate"),"range",range);auditRows.add(auditRow);if(!bp)leads.add(auditRow);
        preparedRoles.put(ref,l(m("kind",kind,"source",m("path",group.path().toString(),"sha256",group.sha256(),"sheet",sheet,"range",range,"row",row),"sourceOrganization",org,"currentOrganization",org,"organizationResolution","SOURCE_RETAINED","sourceIdentityCheck","SOURCE_IDS_AGREE_NOT_ACCOUNT_BINDING","scopeStatus",bp?"USER_CONFIRMED_REGION":"CORRESPONDING_BRANCH_ONLY","proposedBranches",bp?l():l(org),"proposedRegions",l(),"canApprove",false)));
    }
    Config save()throws Exception {
        FilePin candidate=writeJson("candidates.json",batch),region=writeJson("regions.json",regions),role=writeJson("roles.json",roles),auditPin=writeJson("audit.json",audit),scopePin=writeJson("scope.json",scope);
        cp.put("candidateSha256",candidate.sha256());cp.put("regionReferenceSha256",region.sha256());FilePin policy=writeJson("policy.json",cp);
        auth.put("candidatePolicySha256",policy.sha256());auth.put("roleSourceSha256",role.sha256());auth.put("sourceAuditSha256",auditPin.sha256());auth.put("scopeDecision",pin(scopePin));FilePin authorization=writeJson("authorization.json",auth);
        for(Object raw:a(preview.get("rows"))){Map<String,Object> person=o(raw);for(Object r:a(person.get("approvalRoles"))){Map<String,Object> duty=o(r),source=o(duty.get("source"));if(duty.get("kind").equals("BP")){int index=Integer.parseInt(((String)person.get("reference")).substring("teacher-row-".length()))-3;duty.put("scopeDecisionSource",m("path",scopePin.path().toString(),"sha256",scopePin.sha256(),"reference","bp_region_assignments["+index+"]"));}if(source.containsKey("reference")){source.put("path",scopePin.path().toString());source.put("sha256",scopePin.sha256());}}}
        preview.put("evidence",m("candidateSha256",candidate.sha256(),"candidatePolicySha256",policy.sha256(),"roleSourceSha256",role.sha256(),"sourceAuditSha256",auditPin.sha256(),"regionReferenceSha256",region.sha256(),"authorizationSha256",authorization.sha256(),"sourceFiles",l(pin(originals.get(0)),pin(originals.get(1)),pin(originals.get(2))),"scopeDecision",pin(scopePin),"sourceDigestsVerified",true));
        return new Config("synthetic-management-original",candidate,policy,role,authorization,auditPin,region,scopePin,writeJson("preview.json",preview),originals);
    }
    private FilePin writeJson(String name,Object value)throws Exception{return write(name,Json.write(value));}
    private FilePin write(String name,String value)throws Exception{Path path=dir.resolve(name);Files.writeString(path,value,StandardCharsets.UTF_8);return new FilePin(path,hash(Files.readAllBytes(path)));}
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static Map<String,Object> pin(FilePin pin){return m("path",pin.path().toString(),"sha256",pin.sha256());}
    @SuppressWarnings("unchecked") static Map<String,Object> o(Object raw){return(Map<String,Object>)raw;}
    @SuppressWarnings("unchecked") static List<Object> a(Object raw){return(List<Object>)raw;}
    static Map<String,Object> m(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    static List<Object> l(Object... values){return new ArrayList<>(Arrays.asList(values));}
    public static void main(String[] args)throws Exception{Path root=Path.of(args[0]);if(!Files.isDirectory(root))throw new IllegalArgumentException("Existing fresh synthetic directory required");try(var paths=Files.list(root)){if(paths.findAny().isPresent())throw new IllegalArgumentException("Empty synthetic directory required");}var source=OrganizationAccountImportSource.load(new ManagementPublicationFixture(root).save());System.out.println("ManagementPublicationFixture: "+source.candidates().size()+" synthetic source candidates, "+source.branchCoverage().size()+" branches");}
}
