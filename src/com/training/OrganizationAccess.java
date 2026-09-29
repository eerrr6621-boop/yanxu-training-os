package com.training;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * M01: immutable, explicit configuration and fail-closed organization authorization.
 * This class neither authenticates a personnel code nor changes Auth, users or the database.
 * SessionVerifier and Configuration must be supplied by trusted server code, never request JSON.
 */
public final class OrganizationAccess {
    private OrganizationAccess() {}

    public enum Action { VIEW, HANDLE, EXPORT }
    public enum Scope { OWN_ORG, RESPONSIBLE_ORGS, NAMED_ORGS }
    public enum Effect { ALLOW, DENY }
    public enum Status { ALLOWED, DENIED, NOT_CONFIGURED, INVALID_CONFIGURATION,
        UNAUTHENTICATED, AUTHENTICATION_UNAVAILABLE }

    /** M08 integration keys; these describe operations, never assign roles or review order. */
    public enum SummaryPermission {
        READ("summary.read", Action.VIEW),
        EDIT("summary.edit", Action.HANDLE),
        REVIEW_BRANCH("summary.review.branch", Action.HANDLE),
        REVIEW_BP("summary.review.bp", Action.HANDLE),
        EXPORT("summary.export", Action.EXPORT);

        private final String resource;
        private final Action action;
        SummaryPermission(String resource, Action action) { this.resource = resource; this.action = action; }
        public String resource() { return resource; }
        public Action action() { return action; }
    }

    public record CodeRules(String organizationPattern, String personPattern, String rolePattern) {}
    /** displayName is optional display metadata; identity and authorization always use organizationCode. */
    public record Organization(String organizationCode, String parentOrganizationCode, boolean enabled, String displayName) {
        public Organization(String organizationCode, String parentOrganizationCode, boolean enabled) {
            this(organizationCode, parentOrganizationCode, enabled, null);
        }
    }
    public record Person(String personCode, String organizationCode, Set<String> responsibleOrganizationCodes,
                         String leaderPersonCode, String bpPersonCode, Set<String> roleCodes, boolean enabled,
                         Map<String, Set<String>> responsibleOrganizationsByRole) {
        public Person(String personCode, String organizationCode, Set<String> responsibleOrganizationCodes,
                      String leaderPersonCode, String bpPersonCode, Set<String> roleCodes, boolean enabled) {
            this(personCode, organizationCode, responsibleOrganizationCodes, leaderPersonCode, bpPersonCode,
                    roleCodes, enabled, Map.of());
        }
        public Person {
            responsibleOrganizationCodes = frozenSet(responsibleOrganizationCodes);
            roleCodes = frozenSet(roleCodes);
            Map<String, Set<String>> scopes = new LinkedHashMap<>();
            Objects.requireNonNull(responsibleOrganizationsByRole, "Explicit role scopes cannot be null");
            responsibleOrganizationsByRole.forEach((role, orgs) -> scopes.put(
                    Objects.requireNonNull(role, "Role scope key cannot be null"),
                    frozenSet(Objects.requireNonNull(orgs, "Role scope organizations cannot be null"))));
            responsibleOrganizationsByRole = Collections.unmodifiableMap(scopes);
        }
    }
    /** Explicit, administrator-published duty evidence, never inferred from equal person IDs. */
    public record CombinedApprovalAssignment(String organizationCode, String personCode,
                                             String leaderRoleCode, String bpRoleCode, String evidenceRef) {}
    /** Each role must explicitly state both relation rules, including optional relationships. */
    public record RelationRule(boolean required, Set<String> allowedTargetRoles,
                               boolean targetMustCoverOrganization, boolean allowSelf) {
        public RelationRule { allowedTargetRoles = frozenSet(allowedTargetRoles); }
    }
    public record RoleRelations(String roleCode, RelationRule leader, RelationRule bp) {}
    public record AccountBinding(long accountId, String personCode, boolean enabled) {}
    /** No wildcard resources or automatic descendant coverage. Resource keys match exactly. */
    public record Grant(String ruleId, String roleCode, String resource, Action action,
                        Effect effect, Scope scope, Set<String> organizationCodes) {
        public Grant { organizationCodes = frozenSet(organizationCodes); }
    }
    public record Configuration(String version, CodeRules codeRules, Set<String> roleCodes,
                                List<Organization> organizations, List<Person> people,
                                List<RoleRelations> relations, List<AccountBinding> accountBindings,
                                List<Grant> grants, List<CombinedApprovalAssignment> combinedApprovals) {
        public Configuration(String version, CodeRules codeRules, Set<String> roleCodes,
                             List<Organization> organizations, List<Person> people,
                             List<RoleRelations> relations, List<AccountBinding> accountBindings, List<Grant> grants) {
            this(version, codeRules, roleCodes, organizations, people, relations, accountBindings, grants, List.of());
        }
        public Configuration {
            roleCodes = frozenSet(roleCodes);
            organizations = frozenList(organizations);
            people = frozenList(people);
            relations = frozenList(relations);
            accountBindings = frozenList(accountBindings);
            grants = frozenList(grants);
            combinedApprovals = frozenList(Objects.requireNonNull(combinedApprovals, "Explicit combined duties cannot be null"));
        }
    }
    public record Issue(String code, String path, String message) {}
    public record Validation(List<Issue> issues) {
        public Validation { issues = List.copyOf(issues); }
        public boolean valid() { return issues.isEmpty(); }
        public boolean missingConfiguration() {
            return issues.stream().anyMatch(i -> i.code().equals("NOT_CONFIGURED"));
        }
    }
    /** organizationCode comes from the stored business row, not an unverified client claim. */
    public record Resource(String resource, String organizationCode) {}
    public record Decision(Status status, String reason, String configurationVersion, List<String> matchedRuleIds) {
        public Decision { matchedRuleIds = List.copyOf(matchedRuleIds); }
        public boolean allowed() { return status == Status.ALLOWED; }
        public Map<String, Object> toMap() {
            return Map.of("allowed", allowed(), "status", status.name(), "reason", reason,
                    "configuration_version", configurationVersion, "matched_rule_ids", matchedRuleIds);
        }
    }
    /**
     * Trust boundary: resolve with existing Auth.get(credential) on EVERY request.
     * Do not implement as person-code lookup, request accountId, or a client authenticated flag.
     * The host with S01 must complete first/new-device email verification or validate a remembered device
     * before issuing a session. A verified email alone is not an account binding or role grant.
     */
    @FunctionalInterface public interface SessionVerifier {
        OptionalLong authenticatedAccountId(String sessionCredential) throws Exception;
    }

    /** Null preserves a legacy unnamed entry; a supplied name is kept exactly as provided. */
    static boolean validOrganizationDisplayName(String name) {
        return name == null || (!name.isBlank() && name.length() <= 200
                && name.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029));
    }

    public static Validation validate(Configuration config) {
        List<Issue> issues = new ArrayList<>();
        if (config == null) {
            issue(issues, "NOT_CONFIGURED", "configuration", "尚未提供组织权限配置");
            return new Validation(issues);
        }
        if (!key(config.version())) issue(issues, "NOT_CONFIGURED", "version", "需要明确的材料/规则版本");
        CodeRules rules = config.codeRules();
        Pattern orgPattern = pattern(rules == null ? null : rules.organizationPattern(), "codeRules.organizationPattern", issues);
        Pattern personPattern = pattern(rules == null ? null : rules.personPattern(), "codeRules.personPattern", issues);
        Pattern rolePattern = pattern(rules == null ? null : rules.rolePattern(), "codeRules.rolePattern", issues);
        if (config.roleCodes().isEmpty()) issue(issues, "NOT_CONFIGURED", "roleCodes", "角色字典未配置");
        for (String role : config.roleCodes()) checkCode(role, rolePattern, "roleCodes", issues);

        Map<String, Organization> organizations = new LinkedHashMap<>();
        if (config.organizations().isEmpty()) issue(issues, "NOT_CONFIGURED", "organizations", "机构目录未配置");
        for (int i = 0; i < config.organizations().size(); i++) {
            Organization org = config.organizations().get(i);
            String path = "organizations[" + i + "]";
            if (org == null) { issue(issues, "INVALID_ROW", path, "机构行不能为空"); continue; }
            checkCode(org.organizationCode(), orgPattern, path + ".organizationCode", issues);
            if (!validOrganizationDisplayName(org.displayName()))
                issue(issues, "INVALID_DISPLAY_NAME", path + ".displayName", "机构显示名称须为不含控制字符的非空文本，长度不超过200");
            if (organizations.putIfAbsent(org.organizationCode(), org) != null)
                issue(issues, "DUPLICATE_CODE", path, "机构编码重复");
        }
        for (Organization org : organizations.values()) {
            if (absent(org.parentOrganizationCode())) continue;
            checkCode(org.parentOrganizationCode(), orgPattern, "organizations.parentOrganizationCode", issues);
            if (!organizations.containsKey(org.parentOrganizationCode()))
                issue(issues, "UNKNOWN_ORGANIZATION", "organizations." + org.organizationCode(), "上级机构不存在");
        }
        checkCycles(organizations.keySet(), code -> organizations.containsKey(code)
                ? organizations.get(code).parentOrganizationCode() : null, false, "organizations", issues);

        Map<String, Person> people = new LinkedHashMap<>();
        if (config.people().isEmpty()) issue(issues, "NOT_CONFIGURED", "people", "人员目录未配置");
        for (int i = 0; i < config.people().size(); i++) {
            Person person = config.people().get(i);
            String path = "people[" + i + "]";
            if (person == null) { issue(issues, "INVALID_ROW", path, "人员行不能为空"); continue; }
            checkCode(person.personCode(), personPattern, path + ".personCode", issues);
            if (people.putIfAbsent(person.personCode(), person) != null)
                issue(issues, "DUPLICATE_CODE", path, "人员编码重复");
            checkOrganization(person.organizationCode(), organizations, path + ".organizationCode", issues);
            for (String org : person.responsibleOrganizationCodes())
                checkOrganization(org, organizations, path + ".responsibleOrganizationCodes", issues);
            if (person.roleCodes().isEmpty()) issue(issues, "NOT_CONFIGURED", path + ".roleCodes", "人员岗位未配置");
            for (String role : person.roleCodes()) if (!config.roleCodes().contains(role))
                issue(issues, "UNKNOWN_ROLE", path + ".roleCodes", "人员岗位不在角色字典中");
            if (!person.responsibleOrganizationsByRole().isEmpty()) {
                if (!person.responsibleOrganizationsByRole().keySet().equals(person.roleCodes()))
                    issue(issues, "INCOMPLETE_ROLE_SCOPES", path, "分岗位负责范围须明确列出每个岗位，包括空范围");
                Set<String> union = new HashSet<>();
                for (Map.Entry<String, Set<String>> scope : person.responsibleOrganizationsByRole().entrySet()) {
                    if (!person.roleCodes().contains(scope.getKey()))
                        issue(issues, "UNKNOWN_ROLE", path, "负责范围包含未持有岗位");
                    for (String org : scope.getValue()) checkOrganization(org, organizations, path, issues);
                    union.addAll(scope.getValue());
                }
                if (!union.equals(person.responsibleOrganizationCodes()))
                    issue(issues, "ROLE_SCOPE_MISMATCH", path, "总体负责机构必须与各岗位范围的并集一致");
            }
        }

        Map<String, RoleRelations> relations = new HashMap<>();
        for (RoleRelations relation : config.relations()) {
            if (relation == null) { issue(issues, "INVALID_ROW", "relations", "关系规则不能为空"); continue; }
            String path = "relations." + relation.roleCode();
            if (!config.roleCodes().contains(relation.roleCode())) issue(issues, "UNKNOWN_ROLE", path, "关系规则的岗位不存在");
            if (relations.putIfAbsent(relation.roleCode(), relation) != null) issue(issues, "DUPLICATE_RULE", path, "岗位关系规则重复");
            checkRelationRule(relation.leader(), config.roleCodes(), path + ".leader", issues);
            checkRelationRule(relation.bp(), config.roleCodes(), path + ".bp", issues);
        }
        for (String role : config.roleCodes()) if (!relations.containsKey(role))
            issue(issues, "NOT_CONFIGURED", "relations." + role, "岗位的负责人/BP规则未配置");
        for (Person person : people.values()) {
            String path = "people." + person.personCode();
            checkPersonReference(person.leaderPersonCode(), personPattern, people, path + ".leaderPersonCode", issues);
            checkPersonReference(person.bpPersonCode(), personPattern, people, path + ".bpPersonCode", issues);
            for (String role : person.roleCodes()) {
                RoleRelations relation = relations.get(role);
                if (relation == null) continue;
                checkRelation(person, person.leaderPersonCode(), relation.leader(), people, organizations, path + ".leaderPersonCode", issues);
                checkRelation(person, person.bpPersonCode(), relation.bp(), people, organizations, path + ".bpPersonCode", issues);
            }
        }
        checkCycles(people.keySet(), code -> people.containsKey(code) ? people.get(code).leaderPersonCode() : null,
                true, "people.leaderPersonCode", issues);

        Set<Long> accounts = new HashSet<>();
        Set<String> boundPeople = new HashSet<>();
        if (config.accountBindings().isEmpty()) issue(issues, "NOT_CONFIGURED", "accountBindings", "账号与人员编码绑定未配置");
        for (AccountBinding binding : config.accountBindings()) {
            if (binding == null) { issue(issues, "INVALID_ROW", "accountBindings", "绑定行不能为空"); continue; }
            if (binding.accountId() <= 0) issue(issues, "INVALID_ACCOUNT", "accountBindings", "账号ID须为已有账号的正整数ID");
            if (!accounts.add(binding.accountId()) || !boundPeople.add(binding.personCode()))
                issue(issues, "DUPLICATE_BINDING", "accountBindings", "账号和人员编码须一一绑定");
            if (!people.containsKey(binding.personCode())) issue(issues, "UNKNOWN_PERSON", "accountBindings", "绑定人员不存在");
        }

        Set<String> ruleIds = new HashSet<>();
        for (Grant grant : config.grants()) {
            if (grant == null) { issue(issues, "INVALID_ROW", "grants", "权限规则不能为空"); continue; }
            String path = "grants." + grant.ruleId();
            if (!key(grant.ruleId()) || !ruleIds.add(grant.ruleId())) issue(issues, "INVALID_RULE_ID", path, "权限规则ID为空或重复");
            if (!config.roleCodes().contains(grant.roleCode())) issue(issues, "UNKNOWN_ROLE", path, "权限岗位不存在");
            if (!key(grant.resource()) || grant.resource().contains("*")) issue(issues, "INVALID_RESOURCE", path, "资源须为明确键，不支持通配符");
            if (grant.action() == null || grant.effect() == null || grant.scope() == null)
                issue(issues, "NOT_CONFIGURED", path, "权限动作、允许/拒绝或机构范围未配置");
            if (grant.scope() == Scope.NAMED_ORGS && grant.organizationCodes().isEmpty())
                issue(issues, "NOT_CONFIGURED", path, "指定机构范围为空");
            if (grant.scope() != Scope.NAMED_ORGS && !grant.organizationCodes().isEmpty())
                issue(issues, "INVALID_SCOPE", path, "非指定机构范围不能携带机构列表");
            for (String org : grant.organizationCodes()) checkOrganization(org, organizations, path, issues);
        }
        Set<String> combinedOrganizations = new HashSet<>();
        for (CombinedApprovalAssignment a : config.combinedApprovals()) {
            String path = "combinedApprovals";
            if (a == null) { issue(issues, "INVALID_ROW", path, "兼任依据不能为空"); continue; }
            checkOrganization(a.organizationCode(), organizations, path, issues);
            if (!combinedOrganizations.add(a.organizationCode()))
                issue(issues, "DUPLICATE_ASSIGNMENT", path, "同一机构的兼任依据不能重复");
            Person p = people.get(a.personCode());
            if (p == null) { issue(issues, "UNKNOWN_PERSON", path, "兼任人员不存在"); continue; }
            if (!key(a.evidenceRef()) || a.evidenceRef().length() > 128)
                issue(issues, "MISSING_EVIDENCE", path, "需要明确兼任依据引用");
            if (!key(a.leaderRoleCode()) || !key(a.bpRoleCode()) || Objects.equals(a.leaderRoleCode(), a.bpRoleCode())
                    || !p.roleCodes().contains(a.leaderRoleCode()) || !p.roleCodes().contains(a.bpRoleCode()))
                issue(issues, "DUTY_ROLE_MISMATCH", path, "负责人和BP须对应本人两个明确岗位");
            if (p.responsibleOrganizationsByRole().isEmpty()
                    || !responsibleOrganizations(p, a.leaderRoleCode()).contains(a.organizationCode())
                    || !responsibleOrganizations(p, a.bpRoleCode()).contains(a.organizationCode()))
                issue(issues, "COMBINED_SCOPE_MISMATCH", path, "两项职责须各自明确覆盖本机构");
        }
        return new Validation(issues);
    }

    /** Snapshot validation is done once; a changed configuration requires a new Engine. */
    public static final class Engine {
        private final Configuration config;
        private final Validation validation;
        private final Map<String, Organization> organizations = new HashMap<>();
        private final Map<String, Person> people = new HashMap<>();
        private final Map<Long, AccountBinding> bindings = new HashMap<>();

        public Engine(Configuration config) {
            this.config = config;
            this.validation = validate(config);
            if (validation.valid()) {
                config.organizations().forEach(org -> organizations.put(org.organizationCode(), org));
                config.people().forEach(person -> people.put(person.personCode(), person));
                config.accountBindings().forEach(binding -> bindings.put(binding.accountId(), binding));
            }
        }
        public Validation validation() { return validation; }

        /**
         * Organization-level gate only. M08 must additionally check authoritative project
         * assignments and workflow state before constructing its Actor capabilities.
         * Existing training.record grants and verified email do not grant summary operations.
         */
        public Decision authorizeSummary(String sessionCredential, SessionVerifier verifier,
                                         SummaryPermission permission, String storedOrganizationCode) {
            return authorize(sessionCredential, verifier,
                    permission == null ? null : new Resource(permission.resource(), storedOrganizationCode),
                    permission == null ? null : permission.action());
        }

        public Decision authorize(String sessionCredential, SessionVerifier verifier, Resource resource, Action action) {
            if (sessionCredential == null || sessionCredential.isBlank() || verifier == null)
                return decision(Status.UNAUTHENTICATED, "未验证登录会话", List.of());
            OptionalLong account;
            try { account = verifier.authenticatedAccountId(sessionCredential); }
            catch (Exception e) { return decision(Status.AUTHENTICATION_UNAVAILABLE, "无法核实登录状态", List.of()); }
            if (account == null || account.isEmpty() || account.getAsLong() <= 0)
                return decision(Status.UNAUTHENTICATED, "登录会话无效或已失效", List.of());
            if (!validation.valid()) return decision(validation.missingConfiguration() ? Status.NOT_CONFIGURED
                    : Status.INVALID_CONFIGURATION, "组织权限配置尚未通过校验", List.of());
            AccountBinding binding = bindings.get(account.getAsLong());
            if (binding == null) return decision(Status.NOT_CONFIGURED, "登录账号尚未绑定人员编码", List.of());
            if (!binding.enabled()) return decision(Status.DENIED, "账号人员绑定已停用", List.of());
            Person person = people.get(binding.personCode());
            if (!person.enabled()) return decision(Status.DENIED, "人员已停用", List.of());
            if (!activeOrganization(person.organizationCode(), organizations))
                return decision(Status.DENIED, "所属机构或其上级机构已停用", List.of());
            if (resource == null || !key(resource.resource()) || action == null || !key(resource.organizationCode()))
                return decision(Status.NOT_CONFIGURED, "资源动作或数据所属机构未配置", List.of());
            if (!organizations.containsKey(resource.organizationCode()))
                return decision(Status.NOT_CONFIGURED, "数据所属机构不在配置中", List.of());
            if (!activeOrganization(resource.organizationCode(), organizations))
                return decision(Status.DENIED, "数据所属机构或其上级机构已停用", List.of());
            List<String> allows = new ArrayList<>(), denies = new ArrayList<>();
            boolean ruleExists = false;
            for (Grant grant : config.grants()) {
                if (!person.roleCodes().contains(grant.roleCode()) || !grant.resource().equals(resource.resource()) || grant.action() != action) continue;
                ruleExists = true;
                boolean inScope = switch (grant.scope()) {
                    case OWN_ORG -> person.organizationCode().equals(resource.organizationCode());
                    case RESPONSIBLE_ORGS -> responsibleOrganizations(person, grant.roleCode()).contains(resource.organizationCode());
                    case NAMED_ORGS -> grant.organizationCodes().contains(resource.organizationCode());
                };
                if (inScope) (grant.effect() == Effect.DENY ? denies : allows).add(grant.ruleId());
            }
            if (!denies.isEmpty()) return decision(Status.DENIED, "命中显式禁止规则", denies);
            if (!allows.isEmpty()) return decision(Status.ALLOWED, "命中显式允许规则", allows);
            return decision(ruleExists ? Status.DENIED : Status.NOT_CONFIGURED,
                    ruleExists ? "不在已授权机构范围内" : "该岗位、资源与动作的权限未配置", List.of());
        }
        private Decision decision(Status status, String reason, List<String> ruleIds) {
            return new Decision(status, reason, config == null || config.version() == null ? "" : config.version(), ruleIds);
        }
    }

    private static void checkRelationRule(RelationRule rule, Set<String> roles, String path, List<Issue> issues) {
        if (rule == null) { issue(issues, "NOT_CONFIGURED", path, "关系规则未配置，不能推断可选"); return; }
        if (rule.allowedTargetRoles().isEmpty()) issue(issues, "NOT_CONFIGURED", path, "关系目标的岗位范围未配置");
        for (String role : rule.allowedTargetRoles()) if (!roles.contains(role)) issue(issues, "UNKNOWN_ROLE", path, "关系目标岗位不存在");
    }
    private static void checkRelation(Person source, String targetCode, RelationRule rule, Map<String, Person> people,
                                      Map<String, Organization> organizations, String path, List<Issue> issues) {
        if (rule == null) return;
        if (absent(targetCode)) {
            if (rule.required()) issue(issues, "MISSING_RELATION", path, "该岗位必须配置此关系");
            return;
        }
        Person target = people.get(targetCode);
        if (target == null) return; // Reference issue is recorded separately.
        if (Objects.equals(source.personCode(), targetCode) && !rule.allowSelf()) issue(issues, "SELF_RELATION", path, "配置不允许关联本人");
        if (Collections.disjoint(target.roleCodes(), rule.allowedTargetRoles())) issue(issues, "RELATION_ROLE_MISMATCH", path, "关联人员岗位不符");
        if (source.enabled() && (!target.enabled() || !activeOrganization(target.organizationCode(), organizations)))
            issue(issues, "INACTIVE_RELATION", path, "启用人员关联的负责人/BP不可用");
        if (rule.targetMustCoverOrganization() && rule.allowedTargetRoles().stream()
                .filter(target.roleCodes()::contains)
                .noneMatch(role -> responsibleOrganizations(target, role).contains(source.organizationCode())))
            issue(issues, "RELATION_OUT_OF_SCOPE", path, "关联人员未明确负责该所属机构");
    }
    /** Legacy snapshots retain their original range; explicit scopes never fall back to the union. */
    public static Set<String> responsibleOrganizations(Person person, String roleCode) {
        if (person == null || !person.roleCodes().contains(roleCode)) return Set.of();
        return person.responsibleOrganizationsByRole().isEmpty() ? person.responsibleOrganizationCodes()
                : person.responsibleOrganizationsByRole().getOrDefault(roleCode, Set.of());
    }
    private static void checkPersonReference(String value, Pattern pattern, Map<String, Person> people, String path, List<Issue> issues) {
        if (absent(value)) return;
        checkCode(value, pattern, path, issues);
        if (!people.containsKey(value)) issue(issues, "UNKNOWN_PERSON", path, "关联人员不存在");
    }
    private static void checkOrganization(String value, Map<String, Organization> organizations, String path, List<Issue> issues) {
        if (!key(value) || !organizations.containsKey(value)) issue(issues, "UNKNOWN_ORGANIZATION", path, "机构编码不存在或为空");
    }
    private static boolean activeOrganization(String code, Map<String, Organization> organizations) {
        Set<String> visited = new HashSet<>();
        while (!absent(code)) {
            Organization org = organizations.get(code);
            if (org == null || !org.enabled() || !visited.add(code)) return false;
            code = org.parentOrganizationCode();
        }
        return true;
    }
    private static void checkCycles(Set<String> codes, java.util.function.Function<String, String> parent,
                                    boolean skipSelf, String path, List<Issue> issues) {
        Set<String> completed = new HashSet<>();
        for (String code : codes) {
            Set<String> visited = new HashSet<>();
            String current = code;
            while (!absent(current) && codes.contains(current) && !completed.contains(current)) {
                if (!visited.add(current)) { issue(issues, "RELATION_CYCLE", path, "存在循环关系"); break; }
                String next = parent.apply(current);
                if (skipSelf && Objects.equals(current, next)) break; // Explicit self rule checked separately.
                current = next;
            }
            completed.addAll(visited);
        }
    }
    private static Pattern pattern(String value, String path, List<Issue> issues) {
        if (value == null || value.isBlank()) { issue(issues, "NOT_CONFIGURED", path, "编码规则未配置"); return null; }
        try { return Pattern.compile(value); }
        catch (PatternSyntaxException e) { issue(issues, "INVALID_PATTERN", path, "编码正则表达式无效"); return null; }
    }
    private static void checkCode(String value, Pattern pattern, String path, List<Issue> issues) {
        if (!key(value) || (pattern != null && !pattern.matcher(value).matches()))
            issue(issues, "INVALID_CODE", path, "编码为空、含首尾空格、过长或不符合已配置格式");
    }
    private static boolean key(String value) { return value != null && !value.isBlank() && value.length() <= 128 && value.equals(value.strip()); }
    private static boolean absent(String value) { return value == null || value.isEmpty(); }
    private static void issue(List<Issue> issues, String code, String path, String message) { issues.add(new Issue(code, path, message)); }
    private static <T> Set<T> frozenSet(Set<T> value) {
        return Collections.unmodifiableSet(value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value));
    }
    private static <T> List<T> frozenList(List<T> value) { return value == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(value)); }
}
