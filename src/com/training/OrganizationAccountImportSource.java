package com.training;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Server-created pins only. No path in a JSON document is ever opened. */
public final class OrganizationAccountImportSource {
    private OrganizationAccountImportSource() {}
    private static final String ERROR = "账号导入来源核验失败，请重新核对可信材料。";
    private static final int JSON_LIMIT = 8 * 1024 * 1024;
    private static final int ORIGINAL_LIMIT = 32 * 1024 * 1024;
    private static final int MAX_ROWS = 10000;

    /** Physical reads use path; source evidence and identity use the immutable logicalPath. */
    public record FilePin(Path path, String sha256, Path logicalPath) {
        public FilePin(Path path, String sha256) { this(path, sha256, path); }
    }
    public record Config(String batchKey, FilePin candidates, FilePin candidatePolicy,
                         FilePin roleSource, FilePin authorization, FilePin audit,
                         FilePin regionReference, FilePin scopeDecision, FilePin preparedPreview,
                         List<FilePin> originals) {
        public Config { originals = originals == null ? null : Collections.unmodifiableList(new ArrayList<>(originals)); }
    }
    public record Candidate(String reference, String name, String organization, Map<String,Object> preparation) {
        public Candidate { preparation = immutableMap(preparation); }
        @Override public String toString() { return "Candidate[private source identity]"; }
    }
    /** Explicit private projection for teacher-profile import; never part of account preparation. */
    public record TeacherProfile(String reference, String name, String organization, String job, String teacherLevel) {
        @Override public String toString() { return "TeacherProfile[verified private teacher source]"; }
    }
    /** Safe references only; no raw source rows or identity evidence in branch coverage. */
    public record BranchCoverage(String branch, String region, List<String> leaderReferences,
                                 List<String> bpReferences, List<String> combinedReferences, String routingStatus) {
        public BranchCoverage {
            leaderReferences = List.copyOf(leaderReferences);
            bpReferences = List.copyOf(bpReferences);
            combinedReferences = List.copyOf(combinedReferences);
        }
    }
    public record Snapshot(String batchKey, String fingerprint, List<Candidate> candidates,
                           List<String> excludedReferences, Map<String,Object> evidence,
                           List<BranchCoverage> branchCoverage) {
        public Snapshot {
            candidates = List.copyOf(candidates);
            excludedReferences = List.copyOf(excludedReferences);
            evidence = immutableMap(evidence);
            branchCoverage = List.copyOf(branchCoverage);
        }
        @Override public String toString() { return "Snapshot[verified account preparation]"; }
    }

    /** Invoke again for every preview and commit; callers must not cache authorization. */
    public static Snapshot load(Config config) throws Exception {
        try { return verifiedLoad(config); }
        catch (Exception e) { throw new IllegalArgumentException(ERROR); }
    }

    /** Revalidate all pins before projecting the teacher rows from the same pinned candidate source. */
    public static List<TeacherProfile> teacherProfiles(Config config) throws Exception {
        try {
            Snapshot verified = load(config);
            Map<String,Object> batch = parse(readPinned(config.candidates(), JSON_LIMIT));
            Map<String,Object> policy = parse(readPinned(config.candidatePolicy(), JSON_LIMIT));
            Set<Integer> mainRows = range(obj(policy.get("teacher")).get("mainRows"));
            Set<String> expectedReferences = new LinkedHashSet<>();
            for (int row : mainRows) expectedReferences.add("teacher-row-" + row);
            Set<String> seenReferences = new LinkedHashSet<>();
            List<Object> sourceRows = list(batch.get("candidates"));
            check(sourceRows.size() == verified.candidates().size());
            List<TeacherProfile> profiles = new ArrayList<>();
            for (int i = 0; i < sourceRows.size(); i++) {
                Map<String,Object> source = obj(sourceRows.get(i));
                Candidate candidate = verified.candidates().get(i);
                String reference = str(source, "candidate_reference");
                eq(reference, candidate.reference());
                eq(source.get("name"), candidate.name());
                eq(source.get("organization"), candidate.organization());
                Set<String> identities = strings(source.get("identities"));
                eq(identities, strings(candidate.preparation().get("identities")));
                boolean teacher = identities.contains("兼职教师");
                eq(teacher, expectedReferences.contains(reference));
                if (!teacher) continue;
                check(seenReferences.add(reference));
                Map<String,Object> originalSource = obj(source.get("source"));
                Map<String,Object> verifiedSource = obj(candidate.preparation().get("candidateSource"));
                int row = integer(verifiedSource.get("row"));
                check(mainRows.contains(row));
                eq(reference, "teacher-row-" + row);
                eq(originalSource.get("source_row"), row);
                for (String key : List.of("sheet", "range")) eq(originalSource.get(key), verifiedSource.get(key));
                String name = teacherProfileText(source.get("name"), 64, false);
                String organization = teacherProfileText(source.get("organization"), 200, false);
                String job = teacherProfileText(source.get("source_job"), 64, true);
                String level = teacherProfileText(source.get("source_teacher_level"), 32, false);
                check(teacherProfileLevel(level));
                profiles.add(new TeacherProfile(reference, name, organization, job, level));
            }
            eq(seenReferences, expectedReferences);
            return List.copyOf(profiles);
        } catch (Exception e) { throw invalid(); }
    }

    private static String teacherProfileText(Object value, int limit, boolean optional) {
        if (value == null) { check(optional); return null; }
        check(value instanceof String);
        String text = (String)value;
        check(text.length() <= limit && (optional || !text.isEmpty()));
        if (text.isEmpty()) return text;
        check(!teacherProfileWhitespace(text.codePointAt(0)) && !teacherProfileWhitespace(text.codePointBefore(text.length())));
        text.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            check(!Character.isISOControl(codePoint) && type != Character.FORMAT && type != Character.SURROGATE &&
                    type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR);
        });
        return text;
    }

    private static boolean teacherProfileWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean teacherProfileLevel(String level) {
        return Set.of("讲师", "高级讲师", "特级讲师").contains(level);
    }

    private static Snapshot verifiedLoad(Config c) throws Exception {
        check(c != null && c.batchKey() != null && c.batchKey().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"));
        check(c.originals() != null && c.originals().size() == 3);
        LinkedHashMap<String,FilePin> pins = new LinkedHashMap<>();
        pins.put("candidates", c.candidates()); pins.put("candidatePolicy", c.candidatePolicy());
        pins.put("roleSource", c.roleSource()); pins.put("authorization", c.authorization());
        pins.put("audit", c.audit()); pins.put("regionReference", c.regionReference());
        pins.put("scopeDecision", c.scopeDecision()); pins.put("preparedPreview", c.preparedPreview());
        List<FilePin> originals = new ArrayList<>(c.originals());
        for (FilePin pin : originals) validPin(pin);
        originals.sort(Comparator.comparing(pin -> normalized(pin.logicalPath()).toString()));
        for (int i = 0; i < originals.size(); i++) pins.put("original" + i, originals.get(i));
        Set<Path> logical = new HashSet<>(), lexical = new HashSet<>(), real = new HashSet<>();
        List<Path> paths = new ArrayList<>();
        for (FilePin pin : pins.values()) {
            validPin(pin); Path p = normalized(pin.path());
            check(logical.add(normalized(pin.logicalPath())) && lexical.add(p) && real.add(p.toRealPath()));
            for (Path prior : paths) check(!Files.isSameFile(prior, p));
            paths.add(p);
        }
        LinkedHashMap<String,byte[]> bytes = new LinkedHashMap<>();
        long total = 0;
        for (var entry : pins.entrySet()) {
            byte[] value = readPinned(entry.getValue(), entry.getKey().startsWith("original") ? ORIGINAL_LIMIT : JSON_LIMIT);
            total += value.length; check(total <= 96L * 1024 * 1024); bytes.put(entry.getKey(), value);
        }
        Map<String,Object> batch = parse(bytes.get("candidates")), cp = parse(bytes.get("candidatePolicy"));
        Map<String,Object> roles = parse(bytes.get("roleSource")), auth = parse(bytes.get("authorization"));
        Map<String,Object> audit = parse(bytes.get("audit")), regions = parse(bytes.get("regionReference"));
        Map<String,Object> scope = parse(bytes.get("scopeDecision")), preview = parse(bytes.get("preparedPreview"));
        eq(cp.get("candidateSha256"), c.candidates().sha256());
        eq(cp.get("regionReferenceSha256"), c.regionReference().sha256());
        eq(auth.get("candidatePolicySha256"), c.candidatePolicy().sha256());
        eq(auth.get("roleSourceSha256"), c.roleSource().sha256());
        eq(auth.get("sourceAuditSha256"), c.audit().sha256());
        pinReference(obj(auth.get("scopeDecision")), c.scopeDecision());
        Map<String,Object> decision = obj(auth.get("decision"));
        check(str(auth, "version").endsWith("-v2"));
        eq(decision.get("scope"), "BRANCH_AND_BP_ONLY"); eq(decision.get("ordinaryTeacherApproval"), false);
        eq(decision.get("bpScope"), "USER_CONFIRMED"); str(decision, "reference");
        Map<String,Object> evidence = obj(preview.get("evidence"));
        for (String key : List.of("candidates", "candidatePolicy", "roleSource", "audit", "regionReference", "authorization")) {
            String field = key.equals("candidates") ? "candidateSha256" : key.equals("audit") ? "sourceAuditSha256" : key + "Sha256";
            eq(evidence.get(field), pins.get(key).sha256());
        }
        eq(evidence.get("sourceDigestsVerified"), true);
        pinReference(obj(evidence.get("scopeDecision")), c.scopeDecision());
        verifyOriginalSet(list(cp.get("sourceFiles")), c.originals(), 2);
        verifyOriginalSet(list(batch.get("source_files")), c.originals(), 2);
        eq(batch.get("source_files"), cp.get("sourceFiles"));
        verifyOriginalSet(list(evidence.get("sourceFiles")), c.originals(), 3);
        Map<String,Object> teacher = obj(cp.get("teacher")), lead = obj(cp.get("lead"));
        FilePin teacherPin = findOriginal(str(teacher,"path"), c.originals());
        FilePin groupPin = findOriginal(str(lead,"path"), c.originals());
        FilePin regionPin = findOriginal(str(obj(auth.get("regionSource")),"path"), c.originals());
        pinReference(obj(auth.get("regionSource")), regionPin);
        check(!normalized(teacherPin.logicalPath()).equals(normalized(groupPin.logicalPath())) &&
                !normalized(regionPin.logicalPath()).equals(normalized(groupPin.logicalPath())) &&
                !normalized(regionPin.logicalPath()).equals(normalized(teacherPin.logicalPath())));
        for (FilePin p : List.of(teacherPin,groupPin)) check(hasPin(list(cp.get("sourceFiles")),p));
        List<Object> audits = list(audit.get("audits")); check(audits.size() == 2);
        check(hasPin(audits, groupPin) && hasPin(audits,regionPin));

        LinkedHashMap<String,String> regionByBranch = new LinkedHashMap<>();
        LinkedHashMap<String,Map<String,Object>> regionSources = new LinkedHashMap<>();
        Set<String> regionNames = new LinkedHashSet<>();
        for (Object regionValue : list(regions.get("regions"))) {
            Map<String,Object> region = obj(regionValue); String regionName = str(region,"display_name");
            check(regionNames.add(regionName));
            for (Object branchValue : list(region.get("branches"))) {
                Map<String,Object> branch = obj(branchValue); String name = str(branch,"display_name");
                check(regionByBranch.putIfAbsent(name,regionName) == null);
                Map<String,Object> source = obj(branch.get("region_reference"));
                str(source,"sheet"); String range = str(source,"range"); check(range.matches("A([1-9][0-9]*):B\\1"));
                regionSources.put(name, map("sheet",source.get("sheet"),"range",range));
                if (source.containsKey("path")) pinReference(source,regionPin);
                check(branch.get("organization_code") == null);
            }
        }
        check(!regionByBranch.isEmpty() && regionByBranch.size() <= MAX_ROWS);
        check(!Boolean.TRUE.equals(regions.get("formal_organization_codes_assigned")) && !Boolean.TRUE.equals(regions.get("login_accounts_or_grants_created")));
        if (regions.containsKey("source_versions")) {
            Map<String,Object> versions = obj(regions.get("source_versions"));
            eq(versions.get("primary_region_sha256"), regionPin.sha256()); eq(versions.get("roster_sha256"),groupPin.sha256());
        }
        verifyRegionAudit(audit,regionByBranch,regionSources);

        Set<Integer> mainRows = range(teacher.get("mainRows")), historyRows = range(teacher.get("historyRows"));
        check(Collections.disjoint(mainRows,historyRows)); Set<Integer> leadRows = integers(lead.get("additionalRows"));
        LinkedHashMap<String,Map<String,Object>> definitions = new LinkedHashMap<>();
        for (int row : mainRows) definitions.put("teacher-row-" + row, sourceDefinition(teacher,row,true));
        for (int row : leadRows) definitions.put("lead-row-" + row, sourceDefinition(lead,row,false));
        check(definitions.size() <= MAX_ROWS);
        Set<String> dual = strings(cp.get("dualIdentityReferences"));
        check(dual.stream().allMatch(r -> definitions.containsKey(r) && r.startsWith("teacher-row-")));
        Set<String> nameReviews = strings(cp.get("organizationNameReviewReferences")); check(definitions.keySet().containsAll(nameReviews));
        Map<String,String> resolutions = new HashMap<>();
        for (Object value : list(cp.get("organizationResolutions"))) {
            Map<String,Object> r = obj(value); String ref = str(r,"reference");
            check(definitions.containsKey(ref) && resolutions.putIfAbsent(ref,str(r,"organization")) == null);
        }
        eq(batch.get("version"), str(cp,"batchVersion"));
        LinkedHashMap<String,Map<String,Object>> byRef = new LinkedHashMap<>(); Set<String> sourceIds = new HashSet<>();
        Set<List<String>> identities = new HashSet<>();
        for (Object value : list(batch.get("candidates"))) {
            Map<String,Object> candidate = obj(value); String ref = str(candidate,"candidate_reference");
            check(definitions.containsKey(ref) && byRef.putIfAbsent(ref,candidate) == null);
            String name = str(candidate,"name"), org = str(candidate,"organization"); check(identities.add(List.of(name,org)));
            Map<String,Object> definition = definitions.get(ref), source = obj(candidate.get("source"));
            sourceMatch(source,definition,ref.startsWith("teacher-row-"));
            eq(candidate.get("account_disposition"),"PREPARE_ACCOUNT_ONLY");
            for (String field : List.of("account_id","username","email")) nullField(candidate,field);
            eq(candidate.get("identity_binding_verified"),false); check(list(candidate.get("business_role_codes")).isEmpty());
            Set<String> expectedIdentity = ref.startsWith("teacher-row-") ? new LinkedHashSet<>(List.of("兼职教师")) : new LinkedHashSet<>();
            if (!ref.startsWith("teacher-row-") || dual.contains(ref)) expectedIdentity.add("分公司牵头人");
            eq(strings(candidate.get("identities")),expectedIdentity);
            if (resolutions.containsKey(ref)) eq(org,resolutions.get(ref));
            verifyCandidateId(candidate,sourceIds,resolutions.containsKey(ref));
        }
        eq(byRef.keySet(),definitions.keySet());
        List<String> excluded = new ArrayList<>(); Set<Integer> seenHistory = new HashSet<>();
        for (Object value : list(batch.get("historical_exclusions"))) {
            Map<String,Object> h = obj(value); int row = integer(h.get("row")); check(historyRows.contains(row) && seenHistory.add(row));
            sourceMatch(obj(h.get("source")), sourceDefinition(teacher,row,true),true);
            eq(h.get("account_disposition"),"DO_NOT_CREATE");
            if (h.get("name") instanceof String && h.get("organization") instanceof String) check(!identities.contains(List.of(str(h,"name"),str(h,"organization"))));
            String ref = "teacher-row-" + row; check(!byRef.containsKey(ref)); excluded.add(ref);
        }
        eq(seenHistory,historyRows);

        Map<String,Object> branchSource = obj(auth.get("branchSource")), bpSource = obj(auth.get("bpSource"));
        pathEq(str(branchSource,"path"),groupPin.logicalPath()); pathEq(str(bpSource,"path"),groupPin.logicalPath());
        Set<Integer> branchSourceRows = range(branchSource.get("rows")), bpSourceRows = range(bpSource.get("rows"));
        Set<Integer> branchLeadRows = integers(branchSource.get("leadRows")); check(branchSourceRows.containsAll(branchLeadRows));
        List<Object> branchApprovers = list(roles.get("branch_approvers")), bpApprovers = list(roles.get("bp_approvers"));
        check(branchApprovers.size() == branchSourceRows.size() && bpApprovers.size() == bpSourceRows.size());
        Map<String,List<Map<String,Object>>> expectedRoles = new HashMap<>();
        Map<String,Map<String,Object>> bpOriginals = new HashMap<>();
        Set<Integer> assignedBranchRows = new HashSet<>(), assignedBpRows = new HashSet<>(); Set<String> assignedRefs = new HashSet<>();
        for (Object value : list(auth.get("assignments"))) {
            Map<String,Object> assignment = obj(value); String ref = str(assignment,"reference"), kind = str(assignment,"role");
            check(assignedRefs.add(ref) && byRef.containsKey(ref)); boolean bp = kind.equals("BP");
            int row = integer(assignment.get("sourceRow"));
            check((bp ? bpSourceRows : branchSourceRows).contains(row) && (bp ? assignedBpRows : assignedBranchRows).add(row));
            String expectedKind = bp ? "BP" : branchLeadRows.contains(row) ? "BRANCH_LEAD" : "BRANCH_RESPONSIBLE"; eq(kind,expectedKind);
            Map<String,Object> sourcePolicy = bp ? bpSource : branchSource;
            String sheet = str(sourcePolicy,"sheet"), sourceRange = "B"+row+":D"+row;
            Map<String,Object> origin = singleBySource(bp ? bpApprovers : branchApprovers,sheet,sourceRange);
            Map<String,Object> candidate = byRef.get(ref); String org = str(assignment,"organization"), sourceOrg = str(assignment,"sourceOrganization");
            eq(origin.get("name"),candidate.get("name")); eq(org,candidate.get("organization"));
            eq(origin.get("approval_identity"),bp ? "HR-BP" : kind.equals("BRANCH_LEAD") ? "分公司牵头人" : "分公司负责人组成员");
            String originalId = optionalId(origin.get("staff_id")), candidateId = optionalId(candidate.get("source_staff_id_candidate"));
            if (candidateId != null && originalId != null) eq(candidateId,originalId);
            verifyRoleAudit(audit,bp,kind,row,origin,sourceOrg,sourceRange);
            if (bp) {
                eq(org,str(bpSource,"organization")); eq(sourceOrg,org); nullField(origin,"responsible_region");
                if (origin.containsKey("organization")) eq(origin.get("organization"),org);
                bpOriginals.put(ref,origin);
            } else {
                check(regionByBranch.containsKey(org)); eq(origin.get("organization"),org); eq(origin.get("region"),regionByBranch.get(org));
                eq(origin.get("source_organization"),sourceOrg);
                if (!sourceOrg.equals(org)) eq(resolutions.get(ref),org);
                Map<String,Object> oldSource = obj(origin.get("teacher_source")), cs = obj(candidate.get("source"));
                for (String key : List.of("path","sheet","range")) eq(oldSource.get(key),cs.get(key));
            }
            Map<String,Object> role = map("kind",kind,"source",map("path",str(sourcePolicy,"path"),"sha256",groupPin.sha256(),"sheet",sheet,"range",sourceRange,"row",row),
                    "sourceOrganization",sourceOrg,"currentOrganization",org,"organizationResolution",sourceOrg.equals(org)?"SOURCE_RETAINED":"USER_CONFIRMED",
                    "sourceIdentityCheck",candidateId!=null&&originalId!=null?"SOURCE_IDS_AGREE_NOT_ACCOUNT_BINDING":"SOURCE_ID_PENDING_CANDIDATE_RETAINED",
                    "scopeStatus",bp?"PENDING_BP_REGION_EVIDENCE":"CORRESPONDING_BRANCH_ONLY","proposedBranches",bp?new ArrayList<>():List.of(org),
                    "proposedRegions",new ArrayList<>(),"canApprove",false);
            expectedRoles.put(ref,new ArrayList<>(List.of(role)));
        }
        eq(assignedBranchRows,branchSourceRows); eq(assignedBpRows,bpSourceRows);
        prepareScopes(auth,scope,byRef,expectedRoles,bpOriginals,regionByBranch,regionNames,c.scopeDecision());
        verifyPreview(preview,auth,byRef,definitions,expectedRoles,regionByBranch,regionSources,historyRows,teacher);

        List<Candidate> output = new ArrayList<>();
        Map<String,Map<String,Object>> previewRows = new HashMap<>();
        for (Object value : list(preview.get("rows"))) { Map<String,Object> row = obj(value); previewRows.put(str(row,"reference"),row); }
        for (var entry : byRef.entrySet()) {
            Map<String,Object> candidate = entry.getValue();
            Map<String,Object> preparation = safePreparation(previewRows.get(entry.getKey()));
            preparation.put("sourceIdentityEvidence",candidate.get("source_staff_id_candidate") != null ? "AVAILABLE" : "PENDING_IDENTITY_REVIEW");
            output.add(new Candidate(entry.getKey(),str(candidate,"name"),str(candidate,"organization"),preparation));
        }
        LinkedHashMap<String,Object> safeEvidence = new LinkedHashMap<>();
        StringBuilder fingerprint = new StringBuilder(c.batchKey()).append('\n');
        for (var e : pins.entrySet()) {
            // Recheck after parsing so no changed source can escape through a cached snapshot.
            readPinned(e.getValue(),e.getKey().startsWith("original")?ORIGINAL_LIMIT:JSON_LIMIT);
            fingerprint.append(e.getKey()).append(':').append(e.getValue().sha256()).append('\n');
            safeEvidence.put(e.getKey()+"Sha256",e.getValue().sha256());
        }
        safeEvidence.put("policyVersion",auth.get("version")); safeEvidence.put("scopeValidated",true);
        safeEvidence.put("actualApprovalPermissionsAssigned",0);
        List<BranchCoverage> coverage = new ArrayList<>();
        for (Object value : list(preview.get("branchCoverage"))) {
            Map<String,Object> row = obj(value); String branch = str(row,"organization");
            List<String> leaders = new ArrayList<>(), bps = new ArrayList<>(), combined = new ArrayList<>();
            // Iterate trusted candidate references, never names or source-row order to pick a handler.
            for (String ref : byRef.keySet()) {
                List<Map<String,Object>> duties = expectedRoles.getOrDefault(ref,List.of());
                boolean leader = duties.stream().anyMatch(role -> !"BP".equals(role.get("kind")) && list(role.get("proposedBranches")).contains(branch));
                boolean bp = duties.stream().anyMatch(role -> "BP".equals(role.get("kind")) && list(role.get("proposedBranches")).contains(branch));
                if (leader) leaders.add(ref);
                if (bp) bps.add(ref);
                if (leader && bp) {
                    eq(previewRows.get(ref).get("combinedDutiesRequiresWorkflowReview"),true);
                    combined.add(ref);
                }
            }
            eq(new HashSet<>(leaders),strings(row.get("candidateReferences")));
            check(!leaders.isEmpty() && bps.size()==1);
            coverage.add(new BranchCoverage(branch,str(row,"region"),leaders,bps,combined,str(row,"status")));
        }
        check(!coverage.isEmpty());
        return new Snapshot(c.batchKey(),digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8)),output,excluded,safeEvidence,coverage);
    }

    private static void prepareScopes(Map<String,Object> auth, Map<String,Object> scope,
            Map<String,Map<String,Object>> candidates, Map<String,List<Map<String,Object>>> roles,
            Map<String,Map<String,Object>> originals, Map<String,String> regions, Set<String> regionNames, FilePin scopePin) {
        List<Object> links = list(auth.get("bpRegionAssignments")), decisions = list(scope.get("bp_region_assignments"));
        check(links.size() == originals.size() && links.size() == decisions.size());
        Set<String> refs = new HashSet<>(), assignedRegions = new HashSet<>(); Set<Integer> indices = new HashSet<>();
        Map<String,String> bpIds = new HashMap<>();
        for (Object value : links) {
            Map<String,Object> link = obj(value); String ref = str(link,"reference"), region = str(link,"region"); int index = integer(link.get("decisionIndex"));
            check(originals.containsKey(ref) && refs.add(ref) && assignedRegions.add(region) && regionNames.contains(region) && indices.add(index) && index<decisions.size());
            Map<String,Object> decision = obj(decisions.get(index)), candidate = candidates.get(ref), origin = originals.get(ref);
            eq(decision.get("name"),candidate.get("name")); eq(decision.get("region"),region);
            String id = str(decision,"source_staff_id"); check(id.equals(id.trim()));
            if (candidate.get("source_staff_id_candidate") != null) eq(id,candidate.get("source_staff_id_candidate"));
            if (origin.get("staff_id") != null) eq(id,origin.get("staff_id"));
            bpIds.put(ref,id);
            Map<String,Object> role = roles.get(ref).get(0);
            role.put("scopeStatus","USER_CONFIRMED_REGION"); role.put("proposedRegions",List.of(region));
            role.put("proposedBranches",regions.entrySet().stream().filter(e->e.getValue().equals(region)).map(Map.Entry::getKey).toList());
            role.put("scopeDecisionSource",map("path",str(obj(auth.get("scopeDecision")),"path"),"sha256",scopePin.sha256(),"reference","bp_region_assignments["+index+"]"));
        }
        eq(refs,originals.keySet()); eq(assignedRegions,regionNames);
        List<Object> managers = list(auth.get("branchManagementAssignments")), sourceManagers = list(scope.get("branch_management_overrides"));
        check(managers.size() == sourceManagers.size()); Set<Integer> managerIndices = new HashSet<>(); Set<List<String>> pairs = new HashSet<>();
        for (Object value : managers) {
            Map<String,Object> link = obj(value); String ref = str(link,"reference"), branch = str(link,"organization"); int index = integer(link.get("decisionIndex"));
            check(originals.containsKey(ref) && regions.containsKey(branch) && managerIndices.add(index) && index<sourceManagers.size() && pairs.add(List.of(ref,branch)));
            Map<String,Object> decision = obj(sourceManagers.get(index)), candidate = candidates.get(ref);
            eq(decision.get("manager_name"),candidate.get("name")); eq(decision.get("organization"),branch); eq(decision.get("source_staff_id"),bpIds.get(ref));
            check(list(roles.get(ref).get(0).get("proposedBranches")).contains(branch));
            roles.get(ref).add(map("kind","BRANCH_RESPONSIBLE","source",map("path",str(obj(auth.get("scopeDecision")),"path"),"sha256",scopePin.sha256(),"reference","branch_management_overrides["+index+"]"),
                    "sourceOrganization",candidate.get("organization"),"currentOrganization",candidate.get("organization"),"organizationResolution","USER_CONFIRMED_MANAGEMENT_SCOPE_HOME_ORGANIZATION_UNCHANGED",
                    "sourceIdentityCheck",candidate.get("source_staff_id_candidate")!=null?"SOURCE_IDS_AGREE_NOT_ACCOUNT_BINDING":"SOURCE_ID_PENDING_CANDIDATE_RETAINED",
                    "scopeStatus","CORRESPONDING_BRANCH_ONLY","proposedBranches",List.of(branch),"proposedRegions",List.of(),"canApprove",false));
        }
    }

    private static void verifyPreview(Map<String,Object> preview,Map<String,Object> auth,Map<String,Map<String,Object>> candidates,
            Map<String,Map<String,Object>> definitions,Map<String,List<Map<String,Object>>> roles,Map<String,String> regions,
            Map<String,Map<String,Object>> regionSources,Set<Integer> historyRows,Map<String,Object> teacher) {
        eq(preview.get("previewVersion"),"M01-APPROVAL-ROLE-PREVIEW-v1"); eq(preview.get("policyVersion"),auth.get("version"));
        for (String key : List.of("scopeValidated","accountScopeValidated")) eq(preview.get(key),true);
        for (String key : List.of("canCreateAccounts","canPublishConfiguration","writesPerformed")) eq(preview.get(key),false);
        check(list(preview.get("issues")).isEmpty() && list(preview.get("accountIssues")).isEmpty());
        Set<String> seen = new HashSet<>(); int branchPeople=0,bps=0,roleCount=0,combined=0,approvalPeople=0;
        for (Object value : list(preview.get("rows"))) {
            Map<String,Object> row=obj(value);String ref=str(row,"reference");check(candidates.containsKey(ref)&&seen.add(ref));
            Map<String,Object> candidate=candidates.get(ref),definition=definitions.get(ref);
            eq(row.get("organization"),candidate.get("organization")); eq(row.get("region"),regions.get(candidate.get("organization")));
            eq(strings(row.get("identities")),strings(candidate.get("identities"))); eq(row.get("accountDisposition"),"PREPARE_ACCOUNT_ONLY");
            Map<String,Object> cs=obj(row.get("candidateSource"));
            for(String key:List.of("sheet","range","row")) eq(cs.get(key),definition.get(key));
            eq(row.get("canApprove"),false); for(String key:List.of("formalPersonCode","formalOrganizationCode","accountBinding")) nullField(row,key);
            List<Map<String,Object>> expected=roles.getOrDefault(ref,List.of()); List<Object> actual=list(row.get("approvalRoles")); check(actual.size()==expected.size());
            for(int i=0;i<expected.size();i++) subsetEqual(obj(actual.get(i)),expected.get(i));
            boolean bp=expected.stream().anyMatch(r->"BP".equals(r.get("kind"))),branch=expected.stream().anyMatch(r->!"BP".equals(r.get("kind")));
            eq(row.get("combinedDutiesRequiresWorkflowReview"),bp&&branch);
            eq(row.get("approvalEligibility"),expected.isEmpty()?"NO_APPROVAL_ROLE":bp?"BP_SCOPE_PREPARED":"BRANCH_SCOPE_PREPARED");
            if(branch)branchPeople++;if(bp)bps++;if(bp&&branch)combined++;if(!expected.isEmpty())approvalPeople++;roleCount+=expected.size();
        }
        eq(seen,candidates.keySet()); Set<String> covered=new HashSet<>(); int multi=0,missing=0;
        for(Object value:list(preview.get("branchCoverage"))){
            Map<String,Object> row=obj(value);String branch=str(row,"organization");check(regions.containsKey(branch)&&covered.add(branch));
            eq(row.get("region"),regions.get(branch)); subsetEqual(obj(row.get("source")),regionSources.get(branch));
            Set<String> expected=new HashSet<>();
            for(var entry:roles.entrySet())if(entry.getValue().stream().anyMatch(r->!"BP".equals(r.get("kind"))&&list(r.get("proposedBranches")).contains(branch)))expected.add(entry.getKey());
            eq(strings(row.get("candidateReferences")),expected);nullField(row,"defaultHandlerReference");
            eq(row.get("status"),expected.isEmpty()?"CURRENT_ASSIGNEE_MISSING":expected.size()>1?"MULTIPLE_IDENTITIES_RETAINED_ROUTING_PENDING":"ROLE_EVIDENCE_PREPARED");
            if(expected.isEmpty())missing++;if(expected.size()>1)multi++;
        }
        eq(covered,regions.keySet()); check(missing==0);
        Set<Integer> history=new HashSet<>();
        for(Object value:list(preview.get("historicalExclusions"))){Map<String,Object> h=obj(value),s=obj(h.get("source"));int row=integer(s.get("row"));check(history.add(row));eq(s.get("sheet"),teacher.get("sheet"));eq(s.get("range"),"A"+row+":F"+row);eq(h.get("disposition"),"DO_NOT_CREATE");}
        eq(history,historyRows);
        Map<String,Object> summary=obj(preview.get("summary"));
        Map<String,Object> expectedSummary=map("candidates",candidates.size(),"sourceBranchRolePeople",range(obj(auth.get("branchSource")).get("rows")).size(),
                "branchRolePeople",branchPeople,"branchLeads",integers(obj(auth.get("branchSource")).get("leadRows")).size(),"bpRolePeople",bps,"bpScopesPrepared",bps,
                "uniqueApprovalPeople",approvalPeople,"preparedRoleAssignments",roleCount,"peopleWithCombinedDuties",combined,"ordinaryCandidatesWithoutApproval",candidates.size()-approvalPeople,
                "branchesTotal",regions.size(),"branchesCovered",regions.size()-missing,"branchesMissingAssignee",missing,"branchesWithMultipleIdentities",multi,"historicalExcluded",historyRows.size(),"actualApprovalPermissionsAssigned",0);
        subsetEqual(summary,expectedSummary);
        Map<String,Object> decision=obj(auth.get("decision"));subsetEqual(obj(preview.get("decision")),map("reference",decision.get("reference"),"scope","BRANCH_AND_BP_ONLY","ordinaryTeacherApproval",false,"bpScope","USER_CONFIRMED"));
        Set<String> required=new HashSet<>(List.of("CONFIRM_MULTI_PERSON_ROUTING","ASSIGN_FORMAL_PERSON_AND_ORGANIZATION_CODES","RECONCILE_EXISTING_ACCOUNTS","EXPLICIT_ACCOUNT_BINDINGS","SERVER_AUTHORIZATION_VALIDATION_AND_VERSION_CHECK"));
        if(combined>0)required.add("HANDLE_SAME_PERSON_BRANCH_BP_WORKFLOW");eq(strings(preview.get("requiredBeforePublication")),required);
    }

    private static void verifyCandidateId(Map<String,Object> c,Set<String> used,boolean resolved){
        String id=optionalId(c.get("source_staff_id_candidate")),match=str(c,"group_match");
        if(Set.of("EXACT_NAME_AND_ORGANIZATION","LEAD_GROUP_AND_GENERAL_ROSTER","LEAD_GROUP_WITH_USER_ORGANIZATION_RESOLUTION").contains(match)){
            check(id!=null&&used.add(id));boolean found=false;
            for(Object value:list(c.get("group_evidence"))){Map<String,Object> e=obj(value);if(Objects.equals(e.get("name"),c.get("name"))&&Objects.equals(e.get("organization"),c.get("organization"))&&Objects.equals(e.get("source_staff_id"),id))found=true;}
            check(found);if(match.equals("LEAD_GROUP_WITH_USER_ORGANIZATION_RESOLUTION"))check(resolved);
        }else{check(Set.of("NAME_ONLY_ORGANIZATION_DIFFERS","NOT_FOUND_IN_GROUP_ROSTER").contains(match)&&id==null);}
    }
    private static void verifyRoleAudit(Map<String,Object> audit,boolean bp,String kind,int row,Map<String,Object> origin,String org,String range){
        String key=bp?"bp_members":"group_including_leads";Map<String,Object> record=singleRow(list(audit.get(key)),row);
        eq(record.get("name"),origin.get("name"));eq(record.get("organization"),org);eq(record.get("range"),range);
        // Audit readers may represent numeric Excel cells as numbers; identifiers remain strings in the role source.
        eq(auditId(record.get("staff_id")),optionalId(origin.get("staff_id")));
        if(!bp&&kind.equals("BRANCH_LEAD")){Map<String,Object> diff=singleRow(list(audit.get("lead_difference")),row);eq(diff.get("name"),origin.get("name"));eq(auditId(diff.get("staff_id")),optionalId(origin.get("staff_id")));}
    }
    private static void verifyRegionAudit(Map<String,Object> audit,Map<String,String> regions,Map<String,Map<String,Object>> sources){
        Set<String> seen=new HashSet<>();for(Object value:list(audit.get("region_branches"))){Map<String,Object> r=obj(value);String name=str(r,"branch");check(regions.containsKey(name)&&seen.add(name));eq(r.get("region"),regions.get(name));eq(r.get("range"),sources.get(name).get("range"));}eq(seen,regions.keySet());
    }
    private static String auditId(Object value){if(value==null)return null;if(value instanceof String)return optionalId(value);check(value instanceof Number);double n=((Number)value).doubleValue();check(Double.isFinite(n)&&n==Math.rint(n)&&n>=0&&n<=9007199254740991L);return Long.toString((long)n);}
    private static Map<String,Object> singleRow(List<Object> values,int row){Map<String,Object> found=null;for(Object value:values){Map<String,Object> r=obj(value);if(integer(r.get("row"))==row){check(found==null);found=r;}}check(found!=null);return found;}
    private static Map<String,Object> singleBySource(List<Object> values,String sheet,String range){Map<String,Object> found=null;for(Object value:values){Map<String,Object> r=obj(value);if(sheet.equals(r.get("source_sheet"))&&range.equals(r.get("source_range"))){check(found==null);found=r;}}check(found!=null);return found;}
    private static Map<String,Object> sourceDefinition(Map<String,Object> policy,int row,boolean teacher){return map("path",str(policy,"path"),"sheet",str(policy,"sheet"),"row",row,"range",(teacher?"A":"B")+row+":"+(teacher?"F":"D")+row);}
    private static void sourceMatch(Map<String,Object> source,Map<String,Object> expected,boolean teacher){for(String k:List.of("path","sheet","range"))eq(source.get(k),expected.get(k));eq(source.get(teacher?"source_row":"row"),expected.get("row"));}
    private static Map<String,Object> safePreparation(Map<String,Object> row){
        Map<String,Object> out=pick(row,"reference","identities","organization","region","accountDisposition","approvalEligibility","canApprove","combinedDutiesRequiresWorkflowReview","formalPersonCode","formalOrganizationCode","accountBinding");
        out.put("candidateSource",pick(obj(row.get("candidateSource")),"sheet","range","row"));List<Object> roles=new ArrayList<>();
        for(Object v:list(row.get("approvalRoles"))){Map<String,Object> r=obj(v);Map<String,Object> safe=pick(r,"kind","sourceOrganization","currentOrganization","organizationResolution","sourceIdentityCheck","scopeStatus","proposedBranches","proposedRegions","canApprove");safe.put("source",safeSource(obj(r.get("source"))));if(r.containsKey("scopeDecisionSource"))safe.put("scopeDecisionSource",safeSource(obj(r.get("scopeDecisionSource"))));roles.add(safe);}
        out.put("approvalRoles",roles);return out;
    }
    private static Map<String,Object> safeSource(Map<String,Object> source){return pick(source,"sha256","sheet","range","row","reference");}
    private static Map<String,Object> pick(Map<String,Object> value,String...keys){Map<String,Object> out=new LinkedHashMap<>();for(String k:keys)if(value.containsKey(k))out.put(k,value.get(k));return out;}
    private static void subsetEqual(Map<String,Object> actual,Map<String,Object> expected){for(var e:expected.entrySet()){check(actual.containsKey(e.getKey()));Object a=actual.get(e.getKey()),b=e.getValue();if(b instanceof Map<?,?>)subsetEqual(obj(a),obj(b));else eq(a,b);}}
    private static void verifyOriginalSet(List<Object> values,List<FilePin> originals,int size){check(values.size()==size);Set<Path> seen=new HashSet<>();for(Object value:values){Map<String,Object> source=obj(value);FilePin p=findOriginal(str(source,"path"),originals);check(seen.add(normalized(p.logicalPath())));pinReference(source,p);}}
    private static boolean hasPin(List<Object> values,FilePin pin){int count=0;for(Object v:values){Map<String,Object> m=obj(v);if(m.get("path") instanceof String&&normalized(Path.of((String)m.get("path"))).equals(normalized(pin.logicalPath()))&&pin.sha256().equals(m.get("sha256")))count++;}return count==1;}
    private static FilePin findOriginal(String path,List<FilePin> originals){for(FilePin p:originals)if(normalized(Path.of(path)).equals(normalized(p.logicalPath())))return p;throw invalid();}
    private static void pinReference(Map<String,Object> ref,FilePin pin){pathEq(str(ref,"path"),pin.logicalPath());eq(ref.get("sha256"),pin.sha256());}
    private static void pathEq(String raw,Path expected){check(Path.of(raw).isAbsolute());eq(normalized(Path.of(raw)),normalized(expected));}
    private static Path normalized(Path p){return p.toAbsolutePath().normalize();}
    private static void validPin(FilePin pin){check(pin!=null&&pin.path()!=null&&pin.path().isAbsolute()&&pin.logicalPath()!=null&&pin.logicalPath().isAbsolute()&&pin.sha256()!=null&&pin.sha256().matches("[0-9a-f]{64}"));}
    private static byte[] readPinned(FilePin pin,int limit)throws Exception{
        Path p=normalized(pin.path());check(!Files.isSymbolicLink(p)&&Files.isReadable(p));
        BasicFileAttributes before=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        check(before.isRegularFile()&&before.size()>0&&before.size()<=limit);
        byte[] bytes;try(InputStream in=Files.newInputStream(p,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(limit+1);}
        BasicFileAttributes after=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        check(bytes.length<=limit&&bytes.length==before.size()&&after.isRegularFile()&&after.size()==before.size()&&Objects.equals(before.fileKey(),after.fileKey())&&before.lastModifiedTime().equals(after.lastModifiedTime()));
        eq(digest(bytes),pin.sha256());return bytes;
    }
    private static String digest(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static Map<String,Object> parse(byte[] bytes)throws Exception{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();return obj(Json.parse(text));}
    private static String optionalId(Object value){if(value==null)return null;check(value instanceof String);String s=(String)value;check(!s.isBlank()&&s.equals(s.trim())&&s.length()<=256);return s;}
    private static String str(Map<String,Object> map,String key){Object v=map.get(key);check(v instanceof String&&!((String)v).isBlank()&&((String)v).length()<=4096);return (String)v;}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object v){check(v instanceof Map<?,?>);return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked") private static List<Object> list(Object v){check(v instanceof List<?> && ((List<?>)v).size()<=MAX_ROWS);return (List<Object>)v;}
    private static int integer(Object v){check(v instanceof Number);double n=((Number)v).doubleValue();check(Double.isFinite(n)&&n>=0&&n<=1000000&&n==Math.rint(n));return (int)n;}
    private static Set<Integer> integers(Object v){Set<Integer>s=new LinkedHashSet<>();for(Object x:list(v)){int n=integer(x);check(n>0&&s.add(n));}return s;}
    private static Set<Integer> range(Object v){List<Object>a=list(v);check(a.size()==2);int first=integer(a.get(0)),last=integer(a.get(1));check(first>0&&last>=first&&last-first<MAX_ROWS);Set<Integer>s=new LinkedHashSet<>();for(int i=first;i<=last;i++)s.add(i);return s;}
    private static Set<String> strings(Object v){Set<String>s=new LinkedHashSet<>();for(Object x:list(v)){check(x instanceof String&&!((String)x).isBlank()&&s.add((String)x));}return s;}
    private static void nullField(Map<String,Object> m,String k){check(m.containsKey(k)&&m.get(k)==null);}
    private static void eq(Object a,Object b){if(a instanceof Number&&b instanceof Number){check(Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0);return;}if(a instanceof List<?>al&&b instanceof List<?>bl){check(al.size()==bl.size());for(int i=0;i<al.size();i++)eq(al.get(i),bl.get(i));return;}check(Objects.equals(a,b));}
    private static Map<String,Object> map(Object...values){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
    private static void check(boolean value){if(!value)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException(ERROR);}
    @SuppressWarnings("unchecked") private static Object immutable(Object value){if(value==null||value instanceof String||value instanceof Boolean||value instanceof Number)return value;if(value instanceof Map<?,?>){Map<String,Object>m=new LinkedHashMap<>();for(var e:((Map<String,Object>)value).entrySet())m.put(e.getKey(),immutable(e.getValue()));return Collections.unmodifiableMap(m);}if(value instanceof List<?>){List<Object>list=new ArrayList<>();for(Object x:(List<?>)value)list.add(immutable(x));return Collections.unmodifiableList(list);}throw invalid();}
    @SuppressWarnings("unchecked") private static Map<String,Object> immutableMap(Map<String,Object> map){check(map!=null);return (Map<String,Object>)immutable(map);}
}
