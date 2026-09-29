package com.training;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccess.*;
import static com.training.OrganizationAccountImportSource.*;

/**
 * Pure, package-private transform of freshly verified server projections. The caller must hold
 * MUTATION_LOCK, authenticate liveAdmin, revalidate both intakes and the complete live users
 * projection, and check manager's identity against all import associations. No browser identity,
 * database access, account creation, password mutation, activation or publication occurs here.
 */
final class OrganizationManagementGroupPlan {
    private OrganizationManagementGroupPlan() {}

    static final String ROLE = "MANAGEMENT_ADMIN";
    private static final long MAX_ID = 9007199254740991L;
    private static final List<String> OLD_REFS = List.of("teacher-row-3", "teacher-row-4", "teacher-row-5",
            "teacher-row-6", "teacher-row-7", "teacher-row-9", "teacher-row-10");
    private static final List<String> OLD_NAMES = List.of("毛巨策", "赵向草", "张靖", "王旭东", "梁运华", "童雨晴", "郑冬浩");
    private static final List<String> NEW_REFS = List.of("group-main-row-3", "group-main-row-10");
    private static final List<String> NEW_NAMES = List.of("王维明", "沈军");
    private static final String MANAGER_REF = "system-manager01";
    private static final List<ResourceAction> ACTIONS = List.of(
            new ResourceAction("demand.read", Action.VIEW), new ResourceAction("demand.write", Action.HANDLE),
            new ResourceAction("bid.result", Action.HANDLE), new ResourceAction("demand.accept", Action.HANDLE),
            new ResourceAction("approval.review", Action.HANDLE), new ResourceAction("catalog.read", Action.VIEW),
            new ResourceAction("catalog.manage", Action.HANDLE), new ResourceAction("delivery.read", Action.VIEW),
            new ResourceAction("delivery.write", Action.HANDLE), new ResourceAction("delivery.verify", Action.HANDLE),
            new ResourceAction("settlement.submit", Action.HANDLE), new ResourceAction("settlement.review", Action.HANDLE),
            new ResourceAction("settlement.confirm", Action.HANDLE), new ResourceAction("settlement.correct", Action.HANDLE),
            new ResourceAction("settlement.pay", Action.HANDLE), new ResourceAction("settlement.configure", Action.HANDLE),
            new ResourceAction("settlement.export", Action.EXPORT), new ResourceAction("survey.preview", Action.HANDLE),
            new ResourceAction("survey.read", Action.VIEW), new ResourceAction("survey.import", Action.HANDLE),
            new ResourceAction("survey.review", Action.HANDLE), new ResourceAction("survey.policy", Action.HANDLE),
            new ResourceAction("summary.read", Action.VIEW), new ResourceAction("summary.edit", Action.HANDLE),
            new ResourceAction("summary.review.branch", Action.HANDLE), new ResourceAction("summary.review.bp", Action.HANDLE),
            new ResourceAction("summary.export", Action.EXPORT), new ResourceAction("reports.read", Action.VIEW),
            new ResourceAction("reports.export", Action.EXPORT));

    private record ResourceAction(String resource, Action action) {}
    record Member(String reference, long accountId, String personCode, String organizationCode, String name) {}
    record Plan(Configuration configuration, List<Member> members, Map<String,Object> diff, Map<String,Object> evidence) {
        Plan { members = List.copyOf(members); diff = freezeMap(diff); evidence = freezeMap(evidence); }
    }

    static Plan build(Configuration base, OrganizationAccountImport.ProvisioningIntake original,
                      Map<String,Object> supplementReceived, List<Map<String,Object>> users,
                      String managerPersonCode, String candidateVersion) throws Exception {
        require(base != null && OrganizationAccess.validate(base).valid(), "当前组织配置不完整，不能发布管理组");
        require(validText(candidateVersion, 128) && !candidateVersion.equals(base.version()), "管理组候选版本须与当前版本不同");
        require(!base.roleCodes().contains(ROLE), "管理组岗位已存在，不能重复发布");
        require(original != null && original.intakeRevision() > 0 && digestText(original.receiptFingerprint()), "原批次接收证据不完整");
        Snapshot source = original.source();
        require(validText(source.batchKey(), 128) && digestText(source.fingerprint()), "原批次来源证据不完整");
        require(users != null, "无法核实当前账号，不能发布管理组");

        Map<String,Organization> organizations = new LinkedHashMap<>();
        for (Organization row : base.organizations()) organizations.put(row.organizationCode(), row);
        Map<String,Person> people = new LinkedHashMap<>();
        for (Person row : base.people()) people.put(row.personCode(), row);
        Map<Long,AccountBinding> bindings = new LinkedHashMap<>();
        Set<String> boundPeople = new HashSet<>();
        for (AccountBinding row : base.accountBindings()) { bindings.put(row.accountId(), row); boundPeople.add(row.personCode()); }

        String root = organizationCode("ROOT", "SYSTEM_ROOT");
        organization(organizations, root, null, "系统组织目录");
        String office = organizationCode("HOME", "公司总经理室");
        String hr = organizationCode("HOME", "人力资源部");
        organization(organizations, office, root, "公司总经理室");
        organization(organizations, hr, root, "人力资源部");
        Set<String> branches = new LinkedHashSet<>(), regions = new LinkedHashSet<>(), branchNames = new HashSet<>();
        List<Map<String,Object>> branchEvidence = new ArrayList<>();
        Map<String,String> regionByBranch = new HashMap<>();
        for (BranchCoverage row : source.branchCoverage()) {
            require(validText(row.branch(), 200) && validText(row.region(), 200) && branchNames.add(row.branch()), "分公司来源重复或不完整");
            String region = organizationCode("REGION", row.region());
            String branch = organizationCode("BRANCH", row.branch());
            organization(organizations, region, root, row.region());
            Organization existing = organization(organizations, branch, region, row.branch());
            require(branches.add(branch), "分公司技术标识冲突");
            regions.add(region); regionByBranch.put(row.branch(), row.region());
            branchEvidence.add(map("branch", row.branch(), "organizationCode", branch, "region", row.region(),
                    "parentOrganizationCode", region, "enabled", existing.enabled()));
        }
        require(branches.size() == 37 && regions.size() == 5, "管理组范围必须为可信来源中的37家分公司及5个区域");
        require("东区".equals(regionByBranch.get("烟台分公司")) && "东区".equals(regionByBranch.get("济南分公司")), "烟台和济南必须保留在东区");

        Map<String,Candidate> candidates = new LinkedHashMap<>();
        for (Candidate row : source.candidates()) require(row != null && candidates.putIfAbsent(row.reference(), row) == null, "原候选引用重复");
        Map<String,OrganizationAccountImport.ReceivedAccount> receipts = new LinkedHashMap<>();
        Set<Long> originalAccounts = new HashSet<>();
        Set<String> originalCodes = new HashSet<>();
        for (OrganizationAccountImport.ReceivedAccount row : original.accounts()) {
            require(row != null && row.accountId() > 0 && row.accountId() <= MAX_ID && validPerson(base, row.personCode())
                    && receipts.putIfAbsent(row.sourceReference(), row) == null && originalAccounts.add(row.accountId())
                    && originalCodes.add(row.personCode()), "原接收引用、账号与人员标识必须一一对应");
        }
        require(candidates.keySet().equals(receipts.keySet()) && Collections.disjoint(candidates.keySet(), source.excludedReferences()), "原候选与已接收引用不一致或包含排除人员");
        List<Member> members = new ArrayList<>();
        List<Map<String,Object>> oldEvidence = new ArrayList<>();
        for (int i = 0; i < OLD_REFS.size(); i++) {
            String reference = OLD_REFS.get(i), homeName = i < 5 ? "公司总经理室" : "人力资源部", home = i < 5 ? office : hr;
            Candidate candidate = candidates.get(reference);
            OrganizationAccountImport.ReceivedAccount receipt = receipts.get(reference);
            require(candidate != null && receipt != null && OLD_NAMES.get(i).equals(candidate.name())
                    && homeName.equals(candidate.organization()), "原七人候选的精确引用、姓名或归属不一致");
            Person person = people.get(receipt.personCode()); AccountBinding binding = bindings.get(receipt.accountId());
            require(person != null && binding != null && person.personCode().equals(binding.personCode())
                    && home.equals(person.organizationCode()), "原七人已接收身份与当前账号绑定、人员归属不一致");
            require(person.responsibleOrganizationsByRole().keySet().equals(person.roleCodes()), "目标人员旧岗位范围须逐岗位明确，不能推断旧范围");
            Member member = new Member(reference, receipt.accountId(), receipt.personCode(), home, candidate.name());
            members.add(member);
            oldEvidence.add(map("identity", memberMap(member), "sourceName", candidate.name(), "sourceOrganization", candidate.organization(),
                    "bindingEnabled", binding.enabled(), "personEnabled", person.enabled(), "candidateReceiptBindingHomeMatched", true));
        }

        require(supplementReceived != null && supplementReceived.keySet().equals(Set.of("batchKey", "sourceFingerprint", "intakeRevision",
                "receiptFingerprint", "rows", "permissionsPublished", "accountsActivated")), "补充接收证据结构不完整");
        String supplementBatch = string(supplementReceived.get("batchKey"));
        require(!source.batchKey().equals(supplementBatch) && digestText(supplementReceived.get("sourceFingerprint"))
                && digestText(supplementReceived.get("receiptFingerprint")) && positiveId(supplementReceived.get("intakeRevision")) > 0
                && Boolean.FALSE.equals(supplementReceived.get("permissionsPublished"))
                && Boolean.FALSE.equals(supplementReceived.get("accountsActivated")), "补充批次必须保留独立可信回执");
        Map<String,Map<String,Object>> supplemental = new LinkedHashMap<>();
        for (Object value : list(supplementReceived.get("rows"))) {
            Map<String,Object> row = object(value); String reference = string(row.get("reference"));
            require(row.keySet().equals(Set.of("reference", "accountId", "personCode", "accountEnabled"))
                    && row.get("accountEnabled") instanceof Boolean && supplemental.putIfAbsent(reference, row) == null, "补充接收行结构或引用不一致");
        }
        require(supplemental.keySet().equals(new HashSet<>(NEW_REFS)), "补充接收必须恰为王维明、沈军两条固定来源引用");
        List<Map<String,Object>> newEvidence = new ArrayList<>();
        for (int i = 0; i < NEW_REFS.size(); i++) {
            String reference = NEW_REFS.get(i); Map<String,Object> row = supplemental.get(reference);
            long accountId = positiveId(row.get("accountId")); String code = string(row.get("personCode"));
            require(validPerson(base, code) && !people.containsKey(code) && !boundPeople.contains(code)
                    && !bindings.containsKey(accountId) && !originalAccounts.contains(accountId) && !originalCodes.contains(code), "补充人员已与现有配置或原批次身份冲突，不能合并收养");
            Member member = new Member(reference, accountId, code, office, NEW_NAMES.get(i)); members.add(member);
            newEvidence.add(map("identity", memberMap(member), "verifiedHost", "OrganizationManagementSupplementHost.received",
                    "homeMapping", "公司总经理室", "intakeAccountEnabled", row.get("accountEnabled"), "newBindingEnabled", false));
        }

        Map<Long,Map<String,Object>> usersById = new LinkedHashMap<>(); List<Map<String,Object>> managerAccounts = new ArrayList<>();
        for (Map<String,Object> user : users) {
            require(user != null, "当前账号投影不完整"); long id = positiveId(user.get("id"));
            require(usersById.putIfAbsent(id, user) == null, "当前账号投影存在重复账号");
            if ("manager01".equals(user.get("username"))) managerAccounts.add(user);
        }
        require(managerAccounts.size() == 1, "当前必须存在唯一且独立的 manager01 账号；不能替代或创建，不能发布管理组");
        long managerId = positiveId(managerAccounts.get(0).get("id"));
        require(!bindings.containsKey(managerId), "manager01 已有人员绑定；本版缺少独立技术身份依据，不能收养现有绑定或发布");
        require(validPerson(base, managerPersonCode) && !people.containsKey(managerPersonCode) && !boundPeople.contains(managerPersonCode)
                && !originalCodes.contains(managerPersonCode) && !originalAccounts.contains(managerId)
                && !organizations.containsKey(managerPersonCode) && !base.roleCodes().contains(managerPersonCode), "manager01 的新技术人员标识或独立账号与已有身份冲突");
        members.add(new Member(MANAGER_REF, managerId, managerPersonCode, root, "manager01"));
        Set<Long> memberAccounts = new HashSet<>(); Set<String> memberCodes = new HashSet<>();
        for (Member member : members) require(memberAccounts.add(member.accountId()) && memberCodes.add(member.personCode()), "管理组必须是10个独立账号与人员身份，不能替代或合并");
        require(members.size() == 10, "管理组必须恰为10个独立身份");

        RoleRelations memberRelation = base.relations().stream().filter(row -> "MEMBER".equals(row.roleCode())).findFirst().orElse(null);
        require(memberRelation != null && safeRelation(memberRelation.leader()) && safeRelation(memberRelation.bp()), "现有MEMBER关系策略须为可选、不可本人，不能自动变更关系规则");
        Set<String> roles = new LinkedHashSet<>(base.roleCodes()); roles.add(ROLE);
        List<RoleRelations> relations = new ArrayList<>(base.relations());
        relations.add(new RoleRelations(ROLE, memberRelation.leader(), memberRelation.bp()));
        Map<String,Person> updated = new LinkedHashMap<>(people);
        List<AccountBinding> candidateBindings = new ArrayList<>(base.accountBindings());
        List<Map<String,Object>> personChanges = new ArrayList<>(), userRoleChanges = new ArrayList<>(), newPeople = new ArrayList<>(), newBindings = new ArrayList<>();
        for (Member member : members) {
            Map<String,Object> user = usersById.get(member.accountId());
            require(user != null && validText(user.get("username"), 200) && user.get("role") instanceof String
                    && Set.of("viewer", "manager", "admin").contains(user.get("role")), "目标人员当前账号或角色无法核实");
            long status = status(user.get("status"));
            userRoleChanges.add(map("reference", member.reference(), "accountId", member.accountId(), "personCode", member.personCode(),
                    "username", user.get("username"), "roleBefore", user.get("role"), "roleAfter", "admin", "statusBefore", status, "statusAfter", status));
            Person before = people.get(member.personCode());
            Set<String> personRoles = new LinkedHashSet<>(before == null ? Set.of("MEMBER") : before.roleCodes()); personRoles.add(ROLE);
            Map<String,Set<String>> scopes = new LinkedHashMap<>(before == null ? Map.of("MEMBER", Set.of()) : before.responsibleOrganizationsByRole()); scopes.put(ROLE, branches);
            Set<String> union = new LinkedHashSet<>(before == null ? Set.of() : before.responsibleOrganizationCodes()); union.addAll(branches);
            Person after = new Person(member.personCode(), member.organizationCode(), union, before == null ? null : before.leaderPersonCode(),
                    before == null ? null : before.bpPersonCode(), personRoles, before == null || before.enabled(), scopes);
            updated.put(member.personCode(), after);
            personChanges.add(map("identity", memberMap(member), "before", before == null ? null : personMap(before), "after", personMap(after)));
            if (before == null) {
                AccountBinding addition = new AccountBinding(member.accountId(), member.personCode(), false); candidateBindings.add(addition);
                newPeople.add(personMap(after)); newBindings.add(bindingMap(addition));
            } else {
                require(Objects.equals(before.leaderPersonCode(), after.leaderPersonCode()) && Objects.equals(before.bpPersonCode(), after.bpPersonCode())
                        && before.enabled() == after.enabled() && before.organizationCode().equals(after.organizationCode())
                        && before.responsibleOrganizationsByRole().entrySet().stream().allMatch(entry -> entry.getValue().equals(after.responsibleOrganizationsByRole().get(entry.getKey()))), "旧人员关系、状态或岗位范围发生变化");
            }
        }
        List<Grant> grants = new ArrayList<>(base.grants()); Set<String> grantIds = new HashSet<>();
        for (Grant grant : grants) grantIds.add(grant.ruleId());
        List<Map<String,Object>> grantAdditions = new ArrayList<>();
        for (int i = 0; i < ACTIONS.size(); i++) {
            ResourceAction pair = ACTIONS.get(i); String ruleId = String.format(Locale.ROOT, "m01_management_admin_%02d", i + 1);
            require(grantIds.add(ruleId), "管理组权限规则标识冲突，不能覆盖现有规则");
            Grant grant = new Grant(ruleId, ROLE, pair.resource(), pair.action(), Effect.ALLOW, Scope.NAMED_ORGS, branches); grants.add(grant);
            grantAdditions.add(map("ruleId", ruleId, "roleCode", ROLE, "resource", pair.resource(), "action", pair.action().name(),
                    "effect", "ALLOW", "scope", "NAMED_ORGS", "organizationCodes", branches));
        }
        Configuration candidate = new Configuration(candidateVersion, base.codeRules(), roles, base.organizations(), new ArrayList<>(updated.values()),
                relations, candidateBindings, grants, base.combinedApprovals());
        require(OrganizationAccess.validate(candidate).valid(), "管理组候选未通过正常组织权限校验，不能发布");
        require(newPeople.size() == 3 && newBindings.size() == 3 && grantAdditions.size() == 29, "管理组新增范围与固定方案不一致");
        for (Person before : base.people()) if (!memberCodes.contains(before.personCode())) require(before.equals(updated.get(before.personCode())), "非目标人员发生变化");

        Map<String,Object> evidence = map("policy", "M01-MANAGEMENT-GROUP-v1", "original", map("batchKey", source.batchKey(),
                "sourceFingerprint", source.fingerprint(), "receiptFingerprint", original.receiptFingerprint(), "intakeRevision", original.intakeRevision(),
                "oldSeven", oldEvidence), "supplement", map("batchKey", supplementBatch, "sourceFingerprint", supplementReceived.get("sourceFingerprint"),
                "receiptFingerprint", supplementReceived.get("receiptFingerprint"), "intakeRevision", supplementReceived.get("intakeRevision"), "newTwo", newEvidence),
                "manager", map("reference", MANAGER_REF, "accountId", managerId, "verifiedUsername", "manager01", "personCode", managerPersonCode,
                        "organizationCode", root, "identityKind", "SERVER_GENERATED_TECHNICAL_PERSON", "employeeIdentity", false, "existingBindingAdopted", false),
                "branchCoverage", branchEvidence, "exclusions", map("liuYanIncluded", false, "excludedSourceReferences", source.excludedReferences()));
        Map<String,Object> diff = map("baseVersion", base.version(), "candidateVersion", candidateVersion,
                "baseConfigurationFingerprint", hash(OrganizationAccessStore.toMap(base)), "candidateConfigurationFingerprint", hash(OrganizationAccessStore.toMap(candidate)),
                "members", members.stream().map(OrganizationManagementGroupPlan::memberMap).toList(), "userRoleChanges", userRoleChanges,
                "personChanges", personChanges, "addedPeople", newPeople, "addedAccountBindings", newBindings, "addedRoleCodes", List.of(ROLE),
                "addedGrants", grantAdditions, "branchCount", branches.size(), "regionCount", regions.size(), "resourceActionCount", ACTIONS.size(),
                "preserved", map("oldRoleScopes", true, "leaderAndBp", true, "combinedApprovals", base.combinedApprovals().equals(candidate.combinedApprovals()),
                        "organizationsAndEnabledFlags", base.organizations().equals(candidate.organizations()), "oldBindingsAndEnabledFlags", base.accountBindings().equals(candidate.accountBindings().subList(0, base.accountBindings().size())),
                        "oldGrantsIncludingDeny", base.grants().equals(candidate.grants().subList(0, base.grants().size())), "otherPeople", true,
                        "userPasswords", true, "userStatus", true), "published", false, "accountsActivated", false, "evidenceFingerprint", hash(evidence));
        return new Plan(candidate, members, diff, evidence);
    }

    private static Organization organization(Map<String,Organization> all, String code, String parent, String name) throws Api.ApiException {
        Organization row = all.get(code);
        require(row != null && Objects.equals(parent, row.parentOrganizationCode()) && name.equals(row.displayName()), "可信来源的机构标识、上级或名称与当前配置不一致");
        return row;
    }
    // Exactly OrganizationAccountProvisioning.Directory's identity hashing; enabled is never rewritten.
    private static String organizationCode(String kind, String name) throws Exception { return "org_sys_v1_" + hashText(kind + "\n" + name).substring(0, 32); }
    private static boolean safeRelation(RelationRule rule) { return rule != null && !rule.required() && !rule.allowSelf() && !rule.allowedTargetRoles().isEmpty(); }
    private static boolean validPerson(Configuration base, String code) { return validText(code, 128) && code.matches(base.codeRules().personPattern()); }
    private static boolean validText(Object value, int max) { return value instanceof String text && !text.isBlank() && text.equals(text.trim()) && text.length() <= max && text.codePoints().noneMatch(Character::isISOControl); }
    private static boolean digestText(Object value) { return value instanceof String text && text.matches("[a-f0-9]{64}"); }
    private static String string(Object value) throws Api.ApiException { require(validText(value, 256), "可信接收文本不完整"); return (String)value; }
    private static long positiveId(Object value) throws Api.ApiException {
        require(value instanceof Number, "可信账号或接收版本不是正整数"); Number n = (Number)value;
        require(Double.isFinite(n.doubleValue()) && n.doubleValue() == n.longValue() && n.longValue() > 0 && n.longValue() <= MAX_ID, "可信账号或接收版本不是正整数"); return n.longValue();
    }
    private static long status(Object value) throws Api.ApiException {
        require(value instanceof Number && (((Number)value).doubleValue() == 0 || ((Number)value).doubleValue() == 1), "目标账号状态无法核实"); return ((Number)value).longValue();
    }
    private static List<?> list(Object value) throws Api.ApiException { require(value instanceof List<?>, "可信接收行必须为列表"); return (List<?>)value; }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws Api.ApiException { require(value instanceof Map<?,?>, "可信接收行必须为对象"); return (Map<String,Object>)value; }
    private static void require(boolean condition, String message) throws Api.ApiException { if (!condition) throw new Api.ApiException(409, message); }
    private static Map<String,Object> memberMap(Member member) { return map("reference", member.reference(), "name", member.name(), "accountId", member.accountId(), "personCode", member.personCode(), "organizationCode", member.organizationCode()); }
    private static Map<String,Object> bindingMap(AccountBinding row) { return map("accountId", row.accountId(), "personCode", row.personCode(), "enabled", row.enabled()); }
    private static Map<String,Object> personMap(Person row) { return map("personCode", row.personCode(), "organizationCode", row.organizationCode(), "roleCodes", row.roleCodes(),
            "responsibleOrganizationsByRole", row.responsibleOrganizationsByRole(), "responsibleOrganizationCodes", row.responsibleOrganizationCodes(), "leaderPersonCode", row.leaderPersonCode(), "bpPersonCode", row.bpPersonCode(), "enabled", row.enabled()); }
    private static Map<String,Object> map(Object... values) { Map<String,Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) result.put((String)values[i], values[i + 1]); return result; }
    private static String hashText(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    private static String hash(Object value) throws Exception { return hashText(Json.write(canonical(value))); }
    private static Object canonical(Object value) {
        if (value instanceof Map<?,?> source) { Map<String,Object> out = new TreeMap<>(); source.forEach((key, row) -> out.put((String)key, canonical(row))); return out; }
        if (value instanceof Iterable<?> source) { List<Object> out = new ArrayList<>(); for (Object row : source) out.add(canonical(row)); return out; }
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros(); return value;
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?,?> source) { Map<String,Object> out = new LinkedHashMap<>(); source.forEach((key, row) -> out.put((String)key, freeze(row))); return Collections.unmodifiableMap(out); }
        if (value instanceof Collection<?> source) { List<Object> out = new ArrayList<>(); for (Object row : source) out.add(freeze(row)); return Collections.unmodifiableList(out); } return value;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> freezeMap(Map<String,Object> value) { return (Map<String,Object>)freeze(value); }
}
