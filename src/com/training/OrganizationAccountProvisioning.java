package com.training;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.training.OrganizationAccess.*;
import static com.training.OrganizationAccountImportSource.*;

/** Builds an initial, disabled-binding configuration from verified intake. Never publishes or activates. */
public final class OrganizationAccountProvisioning {
    private static final String POLICY="M01-INITIAL-PROVISIONING-v1";
    private static final String ORG_PREFIX="org_sys_v1_";
    private static final long TTL=300_000L;
    private static final int CAPACITY=128;
    private static final Set<String> APPROVAL_ROLES=Set.of("BRANCH_RESPONSIBLE","BRANCH_LEAD","BP");
    private static final Set<String> LEADER_ROLES=Set.of("BRANCH_RESPONSIBLE","BRANCH_LEAD");
    private static final List<String> ROLES=List.of("MEMBER","SUBMITTER","BRANCH_RESPONSIBLE","BRANCH_LEAD","BP");
    private static final List<String> BLOCKED=List.of("HOME_NOT_BRANCH","NO_LEADER","MULTIPLE_LEADERS","NO_BP","MULTIPLE_BPS","SELF_REVIEW","COMBINED_EVIDENCE_UNAVAILABLE");
    private static final List<String> WARNINGS=List.of(
        "此结果仅为初始岗位配置候选，尚未发布业务权限。",
        "所有账号与人员的绑定均保持停用；账号状态不变，后续须统一确认账号和绑定启用。",
        "系统机构标识用于技术关联，不代表正式办公编码；正式办公编码仍待确认。",
        "多人、本人审批或证据不足的路线保持未准备，不自动选择办理人。",
        "普通成员无自动审批权限；仅明确职责及已准备路线对应的最小动作进入本候选配置。");
    private final Map<String,Review> REVIEWS=new LinkedHashMap<>();
    private final OrganizationAccountImport importer;

    public OrganizationAccountProvisioning(OrganizationAccountImport importer){this.importer=Objects.requireNonNull(importer);}

    /** Server-only immutable result. A trusted host must consume it immediately under MUTATION_LOCK. */
    public static final class PreparedConfiguration {
        private final Configuration configuration;
        private final Map<String,Object> confirmation;
        private final String batchKey,sourceFingerprint;
        private final long intakeRevision;
        private PreparedConfiguration(Configuration configuration,Map<String,Object> confirmation,Snapshot source,long revision) {
            this.configuration=configuration;this.confirmation=freezeMap(confirmation);
            batchKey=source.batchKey();sourceFingerprint=source.fingerprint();intakeRevision=revision;
        }
        public Configuration configuration(){return configuration;}
        public Map<String,Object> confirmation(){return confirmation;}
        public String batchKey(){return batchKey;}
        public String sourceFingerprint(){return sourceFingerprint;}
        public long intakeRevision(){return intakeRevision;}
        public String policyVersion(){return POLICY;}
        @Override public String toString(){return "PreparedConfiguration[unpublished server-only candidate]";}
    }
    private static final class Review {
        final Auth.Session actor;
        final String batchKey,sourceFingerprint,version,projectionHash,factsHash,configurationHash;
        final long revision;
        long expires;
        Review(Auth.Session actor,Input input,Build build)throws Exception {
            this.actor=actor;Snapshot source=input.intake.source();batchKey=source.batchKey();sourceFingerprint=source.fingerprint();
            revision=input.intake.intakeRevision();version=build.configuration.version();projectionHash=input.projectionHash;
            factsHash=input.factsHash;configurationHash=hash(OrganizationAccessStore.toMap(build.configuration));expires=System.currentTimeMillis()+TTL;
        }
    }
    private record Input(OrganizationAccountImport.ProvisioningIntake intake,String projectionHash,String factsHash) {}
    private record Build(Configuration configuration,Map<String,Object> dto,int prepared,int blocked) {}
    private record Route(String status,String leader,String bp) {}
    private record Combined(String reference,String leaderRole,CombinedApprovalAssignment assignment) {}
    private record Duty(String kind,List<String> branches,List<String> regions,Map<String,Object> proof,Map<String,Object> scopeProof) {}
    private static final class Member {
        final Candidate candidate;
        final OrganizationAccountImport.ReceivedAccount account;
        final List<Duty> duties=new ArrayList<>();
        final Map<String,Set<String>> scopes=new TreeMap<>();
        Member(Candidate candidate,OrganizationAccountImport.ReceivedAccount account){this.candidate=candidate;this.account=account;scopes.put("MEMBER",Set.of());}
    }
    private static final class Directory {
        final Map<String,String> identities=new LinkedHashMap<>(),branches=new LinkedHashMap<>(),regions=new LinkedHashMap<>(),homes=new LinkedHashMap<>();
        final List<Organization> organizations=new ArrayList<>();
        final List<Map<String,Object>> dto=new ArrayList<>();
        final String root;
        Directory()throws Exception{root=add("ROOT","SYSTEM_ROOT","系统组织目录",null);}
        String add(String kind,String exactName,String displayName,String parent)throws Exception {
            validText(exactName);String identity=kind+"\n"+exactName,code=ORG_PREFIX+hashText(identity).substring(0,32);
            if(identities.putIfAbsent(code,identity)!=null)throw invalidSource();
            organizations.add(new Organization(code,parent,true,displayName));
            dto.add(map("organization_code",code,"display_name",displayName,"kind",kind,"parent_organization_code",parent,"enabled",true,"office_code",null));
            return code;
        }
    }

    public Map<String,Object> preview(Auth.Session supplied)throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Auth.Session actor=requireAdmin(supplied);
            try {
                Input input=input(actor);Build build=build(input.intake,"m01-initial-"+UUID.randomUUID());
                requireAdmin(actor);sweep();
                if(REVIEWS.size()>=CAPACITY)throw conflict("待确认的岗位配置记录较多，请稍后重新预览");
                Review review=new Review(actor,input,build);String token;do{token=Auth.randomToken(32);}while(REVIEWS.containsKey(token));
                Map<String,Object> response=new LinkedHashMap<>(build.dto);response.put("review_token",token);response.put("expires_at",Instant.ofEpochMilli(review.expires).toString());
                Map<String,Object> frozen=freezeMap(response);REVIEWS.put(token,review);return frozen;
            }catch(Api.ApiException known){throw known;}catch(Exception invalid){throw invalidSource();}
        }
    }
    public PreparedConfiguration confirm(Auth.Session supplied,Map<String,Object> body)throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Auth.Session actor=requireAdmin(supplied);
            if(body==null||!body.keySet().equals(Set.of("batch_key","review_token"))
                    ||!(body.get("batch_key") instanceof String batch)||!batch.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    ||!(body.get("review_token") instanceof String token)||!token.matches("[a-f0-9]{64}"))
                throw new Api.ApiException(400,"只接受当前批次和本次核对标识");
            sweep();Review review=REVIEWS.get(token);
            // A caller who does not own this exact session/batch cannot poison a valid review.
            if(review==null||review.actor!=actor||!review.batchKey.equals(batch))throw stale();
            try {
                Input input=input(actor);Snapshot source=input.intake.source();
                if(!review.batchKey.equals(source.batchKey())||!review.sourceFingerprint.equals(source.fingerprint())
                        ||review.revision!=input.intake.intakeRevision()||!review.projectionHash.equals(input.projectionHash)||!review.factsHash.equals(input.factsHash))throw stale();
                Build build=build(input.intake,review.version);
                if(!review.configurationHash.equals(hash(OrganizationAccessStore.toMap(build.configuration))))throw stale();
                requireAdmin(actor);
                Map<String,Object> result=map("prepared",true,"policy_version",POLICY,"batch_key",source.batchKey(),"source_fingerprint",source.fingerprint(),
                    "intake_revision",input.intake.intakeRevision(),"proposed_version",build.configuration.version(),"configuration_valid",true,
                    "permissions_published",false,"accounts_activated",false,"can_operate",false,"account_count",input.intake.accounts().size(),
                    "route_prepared_count",build.prepared,"route_blocked_count",build.blocked,"requires_account_activation",true);
                PreparedConfiguration prepared=new PreparedConfiguration(build.configuration,result,source,input.intake.intakeRevision());
                REVIEWS.remove(token);return prepared;
            }catch(Api.ApiException known){REVIEWS.remove(token);throw known;}
            catch(Exception invalid){REVIEWS.remove(token);throw invalidSource();}
        }
    }

    private Input input(Auth.Session actor)throws Exception {
        noPublishedConfiguration();
        OrganizationAccountImport.ProvisioningIntake intake=importer.verifiedProvisioningIntake(actor);
        // The typed boundary has already checked the entire source and receipt. Capture all current
        // account facts as a separate review pin without using account names to infer associations.
        Map<String,Object> facts=new TreeMap<>();
        Set<Long> ids=new HashSet<>();
        for(OrganizationAccountImport.ReceivedAccount account:intake.accounts()) {
            if(!ids.add(account.accountId()))throw invalidSource();
            Map<String,Object> user=accountFacts(account.accountId());
            if(account.accountEnabled()!=(((Number)user.get("status")).intValue()==1))throw invalidSource();
            facts.put(Long.toString(account.accountId()),user);
        }
        facts.put("actor",accountFacts(actor.uid));
        noPublishedConfiguration();requireAdmin(actor);
        return new Input(intake,hash(projection(intake)),hash(facts));
    }
    private static void noPublishedConfiguration()throws Exception {
        Configuration current;
        try {
            current=OrganizationAccessStore.configuration();
            if(current==null&&Db.count("organization_access_config")!=0)throw unavailable();
        }catch(Exception corrupt){throw unavailable();}
        if(current!=null)throw conflict("已有正式组织配置，初始岗位生成不能覆盖现有配置");
    }
    private static Map<String,Object> accountFacts(long id)throws Exception {
        Map<String,Object> user=Db.one("SELECT id,username,name,role,status,password FROM users WHERE id=?",id);
        if(user==null||!(user.get("id") instanceof Number number)||number.longValue()!=id||!(user.get("status") instanceof Number status)
                ||!(status.doubleValue()==0||status.doubleValue()==1))throw invalidSource();
        for(String key:List.of("username","name","role","password"))if(user.get(key)!=null&&!(user.get(key) instanceof String))throw invalidSource();
        return user;
    }
    private static Map<String,Object> projection(OrganizationAccountImport.ProvisioningIntake intake) {
        Snapshot s=intake.source();List<Object> candidates=new ArrayList<>(),accounts=new ArrayList<>(),coverage=new ArrayList<>();
        for(Candidate c:s.candidates())candidates.add(map("reference",c.reference(),"name",c.name(),"organization",c.organization(),"preparation",c.preparation()));
        for(OrganizationAccountImport.ReceivedAccount a:intake.accounts())accounts.add(map("reference",a.sourceReference(),"account_id",a.accountId(),"person_code",a.personCode(),"enabled",a.accountEnabled()));
        for(BranchCoverage b:s.branchCoverage())coverage.add(map("branch",b.branch(),"region",b.region(),"leaders",b.leaderReferences(),"bps",b.bpReferences(),"combined",b.combinedReferences(),"routing_status",b.routingStatus()));
        return map("policy",POLICY,"batch",s.batchKey(),"fingerprint",s.fingerprint(),"revision",intake.intakeRevision(),"receipt_fingerprint",intake.receiptFingerprint(),"candidates",candidates,"accounts",accounts,
            "coverage",coverage,"excluded",s.excludedReferences(),"evidence",s.evidence());
    }
    private static Build build(OrganizationAccountImport.ProvisioningIntake intake,String version)throws Exception {
        Snapshot source=intake.source();Directory directory=new Directory();
        Map<String,BranchCoverage> coverage=new LinkedHashMap<>();
        for(BranchCoverage branch:source.branchCoverage()) {
            validText(branch.region());validText(branch.branch());
            if(coverage.putIfAbsent(branch.branch(),branch)!=null)throw invalidSource();
            if(!directory.regions.containsKey(branch.region()))directory.regions.put(branch.region(),directory.add("REGION",branch.region(),branch.region(),directory.root));
            directory.branches.put(branch.branch(),directory.add("BRANCH",branch.branch(),branch.branch(),directory.regions.get(branch.region())));
        }
        if(coverage.isEmpty())throw invalidSource();
        Map<String,OrganizationAccountImport.ReceivedAccount> received=new LinkedHashMap<>();Set<String> personCodes=new HashSet<>();Set<Long> accountIds=new HashSet<>();
        for(OrganizationAccountImport.ReceivedAccount a:intake.accounts()) {
            if(a.sourceReference()==null||a.personCode()==null||!a.personCode().matches("imp_p_[0-9a-f]{32}")||a.accountId()<=0||a.accountId()>9007199254740991L
                ||received.putIfAbsent(a.sourceReference(),a)!=null||!personCodes.add(a.personCode())||!accountIds.add(a.accountId()))throw invalidSource();
        }
        Map<String,Member> members=new LinkedHashMap<>();int approvalPeople=0,duties=0,enabledAccounts=0;
        for(Candidate candidate:source.candidates()) {
            OrganizationAccountImport.ReceivedAccount account=received.get(candidate.reference());
            if(account==null||members.containsKey(candidate.reference()))throw invalidSource();
            validText(candidate.organization());validText(candidate.name());
            Member member=new Member(candidate,account);members.put(candidate.reference(),member);
            if(account.accountEnabled())enabledAccounts++;
            if(!directory.branches.containsKey(candidate.organization())&&!directory.homes.containsKey(candidate.organization()))
                directory.homes.put(candidate.organization(),directory.add("HOME",candidate.organization(),candidate.organization(),directory.root));
            List<?> roleRows=list(candidate.preparation().get("approvalRoles"));
            if(!roleRows.isEmpty())approvalPeople++;
            duties+=roleRows.size();
            for(Object value:roleRows) {
                Map<String,Object> role=object(value);String kind=string(role.get("kind"));if(!APPROVAL_ROLES.contains(kind))throw invalidSource();
                List<String> branches=strings(role.get("proposedBranches")),regions=strings(role.get("proposedRegions"));
                if(branches.isEmpty()||!directory.regions.keySet().containsAll(regions))throw invalidSource();
                Set<String> codes=member.scopes.computeIfAbsent(kind,ignored->new TreeSet<>());
                for(String name:branches){String code=directory.branches.get(name);if(code==null)throw invalidSource();codes.add(code);}
                Map<String,Object> proof=object(role.get("source"));
                Map<String,Object> scopeProof=role.containsKey("scopeDecisionSource")?object(role.get("scopeDecisionSource")):Map.of();
                member.duties.add(new Duty(kind,branches,regions,proof,scopeProof));
            }
        }
        if(members.isEmpty()||!members.keySet().equals(received.keySet()))throw invalidSource();
        // Coverage is independently recomputed by Source.load. Recheck its reference links here
        // so this transformation never silently drops a scope or creates an extra route.
        for(BranchCoverage branch:coverage.values()) {
            Set<String> leaders=new LinkedHashSet<>(),bps=new LinkedHashSet<>(),combined=new LinkedHashSet<>();
            for(Member member:members.values()) {
                boolean leader=member.duties.stream().anyMatch(d->LEADER_ROLES.contains(d.kind)&&d.branches.contains(branch.branch()));
                boolean bp=member.duties.stream().anyMatch(d->d.kind.equals("BP")&&d.branches.contains(branch.branch()));
                if(leader)leaders.add(member.candidate.reference());if(bp)bps.add(member.candidate.reference());if(leader&&bp)combined.add(member.candidate.reference());
            }
            if(!uniqueSet(branch.leaderReferences()).equals(leaders)||!uniqueSet(branch.bpReferences()).equals(bps)||!uniqueSet(branch.combinedReferences()).equals(combined))throw invalidSource();
        }
        Map<String,Combined> combined=new LinkedHashMap<>();List<Map<String,Object>> combinedDTO=new ArrayList<>();
        for(BranchCoverage branch:coverage.values()) {
            if(branch.leaderReferences().size()!=1||branch.bpReferences().size()!=1)continue;
            String reference=branch.leaderReferences().get(0);
            if(!reference.equals(branch.bpReferences().get(0))||!branch.combinedReferences().contains(reference))continue;
            Member member=members.get(reference);String organization=directory.branches.get(branch.branch());
            Set<String> leaderKinds=new TreeSet<>();List<Object> leaderProofs=new ArrayList<>(),bpProofs=new ArrayList<>();
            for(Duty duty:member.duties)if(duty.branches.contains(branch.branch())) {
                if(LEADER_ROLES.contains(duty.kind)){leaderKinds.add(duty.kind);leaderProofs.add(map("kind",duty.kind,"source",duty.proof,"scope_source",duty.scopeProof));}
                if(duty.kind.equals("BP"))bpProofs.add(map("kind",duty.kind,"source",duty.proof,"scope_source",duty.scopeProof));
            }
            if(leaderKinds.size()!=1||leaderProofs.isEmpty()||bpProofs.isEmpty()||member.duties.stream().filter(d->d.branches.contains(branch.branch())).anyMatch(d->!proofPresent(d.proof)))continue;
            String leaderRole=leaderKinds.iterator().next();
            String evidence="m01-prepared-evidence-"+hash(map("policy",POLICY,"source_fingerprint",source.fingerprint(),"reference",reference,
                "branch",branch.branch(),"organization_code",organization,"leader_role",leaderRole,"leader_proofs",leaderProofs,"bp_proofs",bpProofs));
            CombinedApprovalAssignment assignment=new CombinedApprovalAssignment(organization,member.account.personCode(),leaderRole,"BP",evidence);
            combined.put(branch.branch(),new Combined(reference,leaderRole,assignment));
            combinedDTO.add(map("organization_code",organization,"organization_name",branch.branch(),"reference",reference,"account_id",member.account.accountId(),
                "leader_role_code",leaderRole,"bp_role_code","BP","evidence_status","VERIFIED_SOURCE"));
        }
        List<Person> people=new ArrayList<>();List<AccountBinding> bindings=new ArrayList<>();List<Map<String,Object>> peopleDTO=new ArrayList<>(),issues=new ArrayList<>();
        Map<String,Integer> blockedCounts=new LinkedHashMap<>();int prepared=0;
        for(Member member:members.values()) {
            String home=directory.branches.getOrDefault(member.candidate.organization(),directory.homes.get(member.candidate.organization()));
            Route route=route(member,coverage,combined);
            if(route.status.equals("ROUTE_PREPARED")){prepared++;member.scopes.put("SUBMITTER",Set.of(home));}
            else {blockedCounts.merge(route.status,1,Integer::sum);issues.add(map("code",route.status,"reference",member.candidate.reference(),"organization_code",home,"message",issueMessage(route.status)));}
            Set<String> union=new TreeSet<>();member.scopes.values().forEach(union::addAll);
            String leader=route.leader==null?null:members.get(route.leader).account.personCode(),bp=route.bp==null?null:members.get(route.bp).account.personCode();
            people.add(new Person(member.account.personCode(),home,union,leader,bp,member.scopes.keySet(),true,member.scopes));
            bindings.add(new AccountBinding(member.account.accountId(),member.account.personCode(),false));
            List<Map<String,Object>> scopesDTO=new ArrayList<>();
            for(var scope:member.scopes.entrySet()) {
                List<String> names=directory.branches.entrySet().stream().filter(e->scope.getValue().contains(e.getValue())).map(Map.Entry::getKey).sorted().toList();
                List<String> codes=names.stream().map(directory.branches::get).toList();
                scopesDTO.add(map("role_code",scope.getKey(),"organization_codes",codes,"organization_names",names));
            }
            peopleDTO.add(map("reference",member.candidate.reference(),"account_id",member.account.accountId(),"name",member.candidate.name(),"organization_code",home,"organization_name",member.candidate.organization(),
                "account_enabled",member.account.accountEnabled(),"binding_enabled",false,"person_enabled",true,"role_codes",member.scopes.keySet().stream().toList(),"role_scopes",scopesDTO,
                "leader_reference",route.leader,"bp_reference",route.bp,"route_status",route.status));
        }
        List<Grant> grants=new ArrayList<>();
        for(String role:List.of("BRANCH_RESPONSIBLE","BRANCH_LEAD","BP")) {
            grants.add(grant(role,"approval.review",Action.HANDLE,Scope.RESPONSIBLE_ORGS));grants.add(grant(role,"demand.read",Action.VIEW,Scope.RESPONSIBLE_ORGS));
        }
        grants.add(grant("SUBMITTER","demand.read",Action.VIEW,Scope.OWN_ORG));grants.add(grant("SUBMITTER","demand.write",Action.HANDLE,Scope.OWN_ORG));
        List<RoleRelations> relations=new ArrayList<>();for(String role:ROLES)relations.add(new RoleRelations(role,
            new RelationRule(role.equals("SUBMITTER"),LEADER_ROLES,true,false),new RelationRule(role.equals("SUBMITTER"),Set.of("BP"),true,false)));
        Configuration configuration=new Configuration(version,new CodeRules("org_sys_v1_[0-9a-f]{32}","imp_p_[0-9a-f]{32}","[A-Z_]+"),new LinkedHashSet<>(ROLES),
            directory.organizations,people,relations,bindings,grants,combined.values().stream().map(Combined::assignment).toList());
        if(!OrganizationAccess.validate(configuration).valid())throw invalidSource();
        Map<String,Object> counts=new LinkedHashMap<>();for(String code:BLOCKED)if(blockedCounts.containsKey(code))counts.put(code,blockedCounts.get(code));
        int blocked=members.size()-prepared;List<Map<String,Object>> grantsDTO=grants.stream().map(g->map("rule_id",g.ruleId(),"role_code",g.roleCode(),"resource",g.resource(),"action",g.action().name(),"effect","ALLOW","scope",g.scope().name())).toList();
        Map<String,Object> dto=map("policy_version",POLICY,"batch_key",source.batchKey(),"source_fingerprint",source.fingerprint(),"intake_revision",intake.intakeRevision(),"proposed_version",version,
            "permissions_published",false,"accounts_activated",false,"can_operate",false,"requires_account_activation",true,"office_codes_status","UNKNOWN",
            "summary",map("account_count",members.size(),"approval_people_count",approvalPeople,"approval_duties_count",duties,"ordinary_people_count",members.size()-approvalPeople,
                "region_count",directory.regions.size(),"branch_count",directory.branches.size(),"home_organization_count",directory.homes.size(),"route_prepared_count",prepared,"route_blocked_count",blocked,
                "route_blocked_counts",counts,"accounts_enabled",enabledAccounts,"accounts_disabled",members.size()-enabledAccounts,"bindings_enabled",0),
            "diff",map("initial_configuration",true,"organizations_added",directory.organizations.size(),"people_added",members.size(),"bindings_added",bindings.size(),"enabled_bindings_added",0,"grants_added",grants.size(),"combined_assignments_added",combined.size()),
            "validation",map("configuration_valid",true),"organizations",directory.dto,"people",peopleDTO,"grants",grantsDTO,"combined_assignments",combinedDTO,"issues",issues,"warnings",WARNINGS);
        return new Build(configuration,freezeMap(dto),prepared,blocked);
    }
    private static Route route(Member member,Map<String,BranchCoverage> coverage,Map<String,Combined> combined) {
        BranchCoverage branch=coverage.get(member.candidate.organization());String status=null;
        if(branch==null)status="HOME_NOT_BRANCH";
        else if(branch.leaderReferences().isEmpty())status="NO_LEADER";
        else if(branch.leaderReferences().size()>1)status="MULTIPLE_LEADERS";
        else if(branch.bpReferences().isEmpty())status="NO_BP";
        else if(branch.bpReferences().size()>1)status="MULTIPLE_BPS";
        else {
            String leader=branch.leaderReferences().get(0),bp=branch.bpReferences().get(0),reference=member.candidate.reference();
            if(reference.equals(leader)||reference.equals(bp))status="SELF_REVIEW";
            else if(leader.equals(bp)&&(!combined.containsKey(branch.branch())||!combined.get(branch.branch()).reference.equals(leader)))status="COMBINED_EVIDENCE_UNAVAILABLE";
            else return new Route("ROUTE_PREPARED",leader,bp);
        }
        return new Route(status,null,null);
    }
    private static Grant grant(String role,String resource,Action action,Scope scope){return new Grant("m01_initial_"+role.toLowerCase(Locale.ROOT)+"_"+resource.replace('.','_'),role,resource,action,Effect.ALLOW,scope,Set.of());}
    private static String issueMessage(String code){return switch(code){
        case "HOME_NOT_BRANCH"->"所属机构不是已确认分公司，暂不生成填报审批路线。";
        case "NO_LEADER"->"所属分公司尚无明确负责人，暂不生成路线。";
        case "MULTIPLE_LEADERS"->"所属分公司有多名负责人，保留全部职责并等待路线选择。";
        case "NO_BP"->"所属分公司尚无明确 BP，暂不生成路线。";
        case "MULTIPLE_BPS"->"所属分公司有多名 BP，暂不生成路线。";
        case "SELF_REVIEW"->"拟办理人包含本人，暂不生成本人审批路线。";
        default->"负责人和 BP 为同一人，现有兼任证据不足以生成路线。";};}
    private static boolean proofPresent(Map<String,Object> proof){return proof.get("sha256") instanceof String hash&&hash.matches("[a-f0-9]{64}")
        &&((proof.get("reference") instanceof String ref&&!ref.isBlank())||(proof.get("sheet") instanceof String sheet&&!sheet.isBlank()&&proof.get("range") instanceof String range&&!range.isBlank()));}
    private static Auth.Session requireAdmin(Auth.Session supplied)throws Api.ApiException {
        Auth.Session actor=Auth.current(supplied);if(actor==null)throw new Api.ApiException(401,"未登录或会话已过期，请重新登录");
        if(!Auth.isAdmin(actor))throw new Api.ApiException(403,"仅系统管理员可准备初始岗位配置");return actor;
    }
    private void sweep(){long now=System.currentTimeMillis();REVIEWS.entrySet().removeIf(e->e.getValue().expires<=now);}
    private static Api.ApiException conflict(String message){return new Api.ApiException(409,message);}
    private static Api.ApiException stale(){return conflict("岗位配置核对已失效或内容已变化，请重新预览后确认");}
    private static Api.ApiException invalidSource(){return conflict("可信来源、完整接收记录或岗位结构无法核实，请由管理员核对");}
    private static Api.ApiException unavailable(){return new Api.ApiException(503,"暂时无法核实正式组织配置，请稍后重试");}
    private static String hashText(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static String hash(Object value)throws Exception{return hashText(Json.write(canonical(value)));}
    private static Object canonical(Object value){
        if(value instanceof Map<?,?> source){Map<String,Object> result=new TreeMap<>();source.forEach((k,v)->result.put((String)k,canonical(v)));return result;}
        if(value instanceof Set<?> source)return source.stream().map(OrganizationAccountProvisioning::canonical).sorted(Comparator.comparing(Json::write)).toList();
        if(value instanceof Collection<?> source)return source.stream().map(OrganizationAccountProvisioning::canonical).toList();return value;
    }
    private static void validText(String value)throws Api.ApiException{if(value==null||value.isBlank())throw invalidSource();}
    private static String string(Object value)throws Api.ApiException{if(!(value instanceof String text)||text.isBlank())throw invalidSource();return text;}
    private static List<?> list(Object value)throws Api.ApiException{if(!(value instanceof List<?> values))throw invalidSource();return values;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value)throws Api.ApiException{if(!(value instanceof Map<?,?>))throw invalidSource();return (Map<String,Object>)value;}
    private static List<String> strings(Object value)throws Api.ApiException{List<String> result=new ArrayList<>();Set<String> seen=new HashSet<>();for(Object item:list(value)){String text=string(item);if(!seen.add(text))throw invalidSource();result.add(text);}return List.copyOf(result);}
    private static Set<String> uniqueSet(List<String> values)throws Api.ApiException{Set<String> result=new LinkedHashSet<>();for(String value:values){validText(value);if(!result.add(value))throw invalidSource();}return result;}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    private static Object freeze(Object value){
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;
        if(value instanceof Map<?,?> source){Map<String,Object> copy=new LinkedHashMap<>();source.forEach((key,item)->copy.put((String)key,freeze(item)));return Collections.unmodifiableMap(copy);}
        if(value instanceof Collection<?> source){List<Object> copy=new ArrayList<>();for(Object item:source)copy.add(freeze(item));return Collections.unmodifiableList(copy);}
        throw new IllegalArgumentException("Unsupported internal projection");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> freezeMap(Map<String,Object> value){return (Map<String,Object>)freeze(value);}
}
