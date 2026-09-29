package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Persistence and trusted-session adapter for M01. No implicit business grants or new login system. */
public final class OrganizationAccessStore {
    private OrganizationAccessStore() {}
    private static final int MAX_ROWS = 10000;
    private static final long MAX_SAFE_ACCOUNT_ID = 9007199254740991L;

    /** Startup only; call after users exist and outside any transaction. Never seeds a configuration. */
    public static void init() throws SQLException {
        synchronized (Api.MUTATION_LOCK) {
            Db.exec("CREATE TABLE IF NOT EXISTS organization_access_config (" +
                    "id IDENTITY PRIMARY KEY, version VARCHAR(128) NOT NULL UNIQUE," +
                    "active_slot INT UNIQUE CHECK(active_slot IS NULL OR active_slot=1)," +
                    "previous_version VARCHAR(128), payload CLOB NOT NULL," +
                    "created_by BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /** Null means no published configuration. Corrupt persisted data is never treated as a valid grant. */
    public static Configuration configuration() throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Map<String, Object> row = Db.one("SELECT version,payload FROM organization_access_config WHERE active_slot=1");
            if (row == null) return null;
            Configuration c = parseConfiguration(Json.parse(String.valueOf(row.get("payload"))));
            if (!Objects.equals(c.version(), row.get("version")) || !OrganizationAccess.validate(c).valid())
                throw new IllegalStateException("组织权限配置校验失败");
            return c;
        }
    }

    /** Accept only a live Session from Auth; every call refreshes the real user and current mapping. */
    public static Person person(Auth.Session supplied) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session s = current(supplied);
            if (s == null) throw new Api.ApiException(401, "登录会话无效或已失效");
            return boundPerson(s, configuration());
        }
    }

    /** organizationCode MUST come from the stored business record, not a client ownership claim. */
    public static Decision authorize(Auth.Session supplied, String resource, Action action, String organizationCode) {
        synchronized (Api.MUTATION_LOCK) {
            try {
                Auth.Session s = current(supplied);
                Configuration c = configuration();
                return new Engine(c).authorize("server-current-session", ignored -> {
                    Auth.Session refreshed = current(s);
                    return refreshed == null ? OptionalLong.empty() : OptionalLong.of(refreshed.uid);
                }, new Resource(resource, organizationCode), action);
            } catch (Exception e) {
                return new Decision(Status.AUTHENTICATION_UNAVAILABLE, "无法核实当前账号或组织权限配置", "", List.of());
            }
        }
    }

    /** Trusted current duty evidence for M03. Equality of participant IDs alone grants nothing. */
    public static ApprovalWorkflow.CombinedAssignment combinedAssignment(String organizationCode, String approverCode) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Configuration c = configuration();
            if (c == null || !activeOrganization(c, organizationCode)) return null;
            CombinedApprovalAssignment a = c.combinedApprovals().stream().filter(v ->
                    v.organizationCode().equals(organizationCode) && v.personCode().equals(approverCode)).findFirst().orElse(null);
            if (a == null) return null;
            Person p = c.people().stream().filter(v -> v.personCode().equals(approverCode)).findFirst().orElse(null);
            if (p == null || !p.enabled() || !activeOrganization(c, p.organizationCode()) || p.responsibleOrganizationsByRole().isEmpty()) return null;
            AccountBinding b = c.accountBindings().stream().filter(v -> v.personCode().equals(approverCode) && v.enabled()).findFirst().orElse(null);
            if (b == null) return null;
            Map<String, Object> user = Db.one("SELECT status FROM users WHERE id=?", b.accountId());
            if (user == null || !enabled(user)) return null;
            Decision overall = new Engine(c).authorize("server-verified-duty", ignored -> OptionalLong.of(b.accountId()),
                    new Resource("approval.review", organizationCode), Action.HANDLE);
            if (!overall.allowed()) return null; // Includes every explicit deny across the person's roles.
            for (String role : List.of(a.leaderRoleCode(), a.bpRoleCode())) {
                if (!p.roleCodes().contains(role) || !OrganizationAccess.responsibleOrganizations(p, role).contains(organizationCode)) return null;
                boolean allow = false;
                for (Grant g : c.grants()) {
                    if (!g.roleCode().equals(role) || !g.resource().equals("approval.review") || g.action() != Action.HANDLE) continue;
                    boolean covered = switch (g.scope()) {
                        case OWN_ORG -> p.organizationCode().equals(organizationCode);
                        case RESPONSIBLE_ORGS -> OrganizationAccess.responsibleOrganizations(p, role).contains(organizationCode);
                        case NAMED_ORGS -> g.organizationCodes().contains(organizationCode);
                    };
                    if (covered && g.effect() == Effect.DENY) return null;
                    if (covered && g.effect() == Effect.ALLOW) allow = true;
                }
                if (!allow) return null;
            }
            return new ApprovalWorkflow.CombinedAssignment(organizationCode, approverCode, c.version(), a.evidenceRef());
        }
    }

    /** Mount inside Api.route after login and before generic CRUD. Api owns body limits and response flushing. */
    public static boolean handle(HttpExchange ex, Auth.Session supplied) throws Exception {
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/api/organization/config") && !path.equals("/api/organization/me")) return false;
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session s = Auth.get(Api.token(ex));
            if (s == null || s != supplied || current(s) == null)
                throw new Api.ApiException(401, "登录会话无效或已失效");
            if (!Api.query(ex).isEmpty()) throw new Api.ApiException(400, "本接口不接受身份或筛选参数");
            if (path.equals("/api/organization/me")) {
                method(ex, "GET", "HEAD");
                Api.ok(ex, me(s));
                return true;
            }
            requireAdmin(s);
            method(ex, "GET", "HEAD", "POST");
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                Configuration c = configuration();
                Api.ok(ex, map("version", c == null ? null : c.version(), "configuration", c == null ? null : toMap(c)));
                return true;
            }
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if (ct == null || !"application/json".equalsIgnoreCase(ct.split(";", 2)[0].trim()))
                throw new Api.ApiException(415, "写入接口只接受 application/json 请求");
            Map<String, Object> body = object(Api.body(ex), "request", "expectedVersion", "configuration");
            String expected = string(body, "expectedVersion", true, 128);
            Configuration c = parseConfiguration(body.get("configuration"));
            publish(s, expected, c);
            boolean sessionInvalidated = Auth.current(s) == null;
            if (sessionInvalidated) Api.clearSessionCookie(ex);
            Api.ok(ex, map("version", c.version(), "configuration", toMap(c), "sessionInvalidated", sessionInvalidated));
            return true;
        }
    }

    /** Legacy configuration publication; HTTP keeps the exact same validation and transaction path. */
    static Configuration publish(Auth.Session supplied, String expectedVersion, Configuration candidate) throws Exception {
        return publishWithMutation(supplied, expectedVersion, candidate, (actor, previous, next) -> Set.of());
    }

    /**
     * Trusted package host only. Run SQL role updates and durable receipt writes on Db.get(); return
     * all accounts whose credentials changed. Do not commit, change auto-commit, start a transaction,
     * mutate sessions, revalidate Auth after modifying credentials, or change configuration/binding-account existence or status.
     * The host must not perform external side effects: a later configuration write can still fail.
     */
    @FunctionalInterface
    interface PublicationMutation {
        Set<Long> apply(Auth.Session actor, Configuration previous, Configuration candidate) throws Exception;
    }

    /** Db.transaction catches Exception only; carry transaction-work Errors through its rollback path. */
    private static final class PublicationMutationFailure extends Exception {
        final Error failure;
        PublicationMutationFailure(Error failure) { super(failure); this.failure = failure; }
    }

    /** Publish configuration and trusted host SQL writes as one transaction, then revoke sessions. */
    static Configuration publishWithMutation(Auth.Session supplied, String expectedVersion, Configuration candidate,
                                              PublicationMutation mutation) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            requireAdmin(supplied);
            Objects.requireNonNull(mutation, "publication mutation");
            if (expectedVersion != null && (expectedVersion.isBlank() || expectedVersion.length() > 128))
                throw new Api.ApiException(400, "expectedVersion 必须是旧版本或 null");
            // Round-trip the same field whitelist used by HTTP even for trusted callers.
            Configuration c = parseConfiguration(Json.parse(Json.write(toMap(candidate))));
            Validation validation = OrganizationAccess.validate(c);
            if (!validation.valid()) {
                Issue first = validation.issues().get(0);
                throw new Api.ApiException(400, "组织权限配置无效：" + first.path() + "：" + first.message());
            }
            if (!Db.get().getAutoCommit()) throw new IllegalStateException("组织配置发布不能嵌入其他事务");
            Set<Long> revokeAfterCommit = new LinkedHashSet<>();
            Configuration published;
            boolean[] deviceCommitReady = {false};
            try {
                published = Db.transaction(() -> {
                    try {
                        Auth.Session actor = requireAdmin(supplied);
                        Configuration old = configuration();
                        String actual = old == null ? null : old.version();
                        if (!Objects.equals(expectedVersion, actual)) throw new Api.ApiException(409, "组织配置已更新，请重新读取后保存");
                        if (Db.one("SELECT id FROM organization_access_config WHERE version=?", c.version()) != null)
                            throw new Api.ApiException(409, "配置版本已使用，请指定新的版本");
                        for (AccountBinding binding : c.accountBindings()) {
                            Map<String, Object> user = Db.one("SELECT status FROM users WHERE id=?", binding.accountId());
                            if (user == null || (binding.enabled() && !enabled(user)))
                                throw new Api.ApiException(400, "绑定账号不存在，或启用的绑定指向停用账号");
                        }
                        Set<Long> additional = Set.copyOf(Objects.requireNonNull(
                                mutation.apply(actor, old, c), "publication mutation revocations"));
                        for (long accountId : additional) {
                            if (accountId <= 0)
                                throw new IllegalArgumentException("发布回调撤销账号必须是正整数");
                        }
                        revokeAfterCommit.addAll(additional);
                        if (old != null) {
                            try (PreparedStatement ps = Db.get().prepareStatement(
                                    "UPDATE organization_access_config SET active_slot=NULL WHERE active_slot=1 AND version=?")) {
                                ps.setString(1, expectedVersion);
                                if (ps.executeUpdate() != 1) throw new Api.ApiException(409, "组织配置已更新，请重新读取后保存");
                            }
                        }
                        try {
                            Db.exec("INSERT INTO organization_access_config(version,active_slot,previous_version,payload,created_by) VALUES(?,1,?,?,?)",
                                    c.version(), actual, Json.write(toMap(c)), actor.uid);
                        } catch (SQLException e) {
                            if ("23505".equals(e.getSQLState())) throw new Api.ApiException(409, "组织配置已更新，请重新读取后保存");
                            throw e;
                        }
                        revokeAfterCommit.addAll(changedSessionAccounts(old, c));
                        for (long accountId : revokeAfterCommit) TrustedDevices.revokeUserInTransaction(accountId);
                        deviceCommitReady[0] = true;
                        return c;
                    } catch (Error failure) {
                        throw new PublicationMutationFailure(failure);
                    }
                });
            } catch (PublicationMutationFailure failure) {
                for (Throwable suppressed : failure.getSuppressed()) failure.failure.addSuppressed(suppressed);
                throw failure.failure;
            } catch (Exception failure) {
                if (deviceCommitReady[0]) for (long accountId : revokeAfterCommit) {
                    TrustedDevices.blockUser(accountId);
                    Auth.revokeUserSessions(accountId);
                }
                throw failure;
            }
            // Commit first; failed/CAS-conflicting publications must never revoke valid sessions.
            // Keep MUTATION_LOCK until old identities can no longer enter any business route.
            for (long accountId : revokeAfterCommit) Auth.revokeUserSessions(accountId);
            return published;
        }
    }

    /** Version/order-only changes preserve sessions. Shared policy changes conservatively
     * revoke every mapped account; individual binding/person changes revoke only affected ones. */
    static Set<Long> changedSessionAccounts(Configuration before, Configuration after) {
        Map<Long, AccountBinding> previous = new HashMap<>(), next = new HashMap<>();
        Map<String, Person> previousPeople = new HashMap<>(), nextPeople = new HashMap<>();
        if (before != null) {
            for (AccountBinding b : before.accountBindings()) previous.put(b.accountId(), b);
            for (Person p : before.people()) previousPeople.put(p.personCode(), p);
        }
        for (AccountBinding b : after.accountBindings()) next.put(b.accountId(), b);
        for (Person p : after.people()) nextPeople.put(p.personCode(), p);
        Set<Long> accounts = new LinkedHashSet<>(previous.keySet()); accounts.addAll(next.keySet());
        boolean sharedPolicyChanged = before == null
                || !new HashSet<>(before.organizations()).equals(new HashSet<>(after.organizations()))
                || !before.roleCodes().equals(after.roleCodes())
                || !new HashSet<>(before.relations()).equals(new HashSet<>(after.relations()))
                || !new HashSet<>(before.grants()).equals(new HashSet<>(after.grants()))
                || !new HashSet<>(before.combinedApprovals()).equals(new HashSet<>(after.combinedApprovals()));
        if (!sharedPolicyChanged) accounts.removeIf(id -> {
            AccountBinding old = previous.get(id), current = next.get(id);
            return Objects.equals(old, current) && Objects.equals(
                    old == null ? null : previousPeople.get(old.personCode()),
                    current == null ? null : nextPeople.get(current.personCode()));
        });
        return accounts;
    }

    private static Auth.Session current(Auth.Session supplied) throws Exception {
        Auth.Session s = Auth.current(supplied);
        if (s == null || s.uid <= 0) return null;
        Map<String, Object> user = Db.one("SELECT status FROM users WHERE id=?", s.uid);
        return user != null && enabled(user) ? s : null;
    }

    private static boolean enabled(Map<String, Object> user) {
        Object status = user.get("status");
        return status instanceof Number && ((Number) status).intValue() == 1;
    }

    private static Auth.Session requireAdmin(Auth.Session supplied) throws Exception {
        Auth.Session s = current(supplied);
        if (s == null) throw new Api.ApiException(401, "登录会话无效或已失效");
        if (!"admin".equals(s.role)) throw new Api.ApiException(403, "仅系统管理员可维护组织权限配置");
        return s;
    }

    private static Person boundPerson(Auth.Session s, Configuration c) throws Api.ApiException {
        if (c == null) throw new Api.ApiException(403, "组织权限尚未配置");
        AccountBinding binding = c.accountBindings().stream().filter(b -> b.accountId() == s.uid).findFirst().orElse(null);
        if (binding == null || !binding.enabled()) throw new Api.ApiException(403, "账号尚未绑定启用的人员编码");
        Person p = c.people().stream().filter(v -> v.personCode().equals(binding.personCode())).findFirst().orElse(null);
        if (p == null || !p.enabled()) throw new Api.ApiException(403, "绑定人员未启用");
        if (!activeOrganization(c, p.organizationCode())) throw new Api.ApiException(403, "所属机构或其上级机构已停用");
        return p;
    }

    private static boolean activeOrganization(Configuration c, String code) {
        Set<String> visited = new HashSet<>();
        while (code != null && !code.isEmpty()) {
            if (!visited.add(code)) return false;
            String lookup = code;
            Organization org = c.organizations().stream().filter(o -> o.organizationCode().equals(lookup)).findFirst().orElse(null);
            if (org == null || !org.enabled()) return false;
            code = org.parentOrganizationCode();
        }
        return true;
    }

    private static Map<String, Object> me(Auth.Session s) throws Exception {
        Configuration c = configuration();
        Person p = null;
        String status = c == null ? "NOT_CONFIGURED" : "NOT_BOUND";
        try { p = boundPerson(s, c); status = "BOUND"; }
        catch (Api.ApiException e) { /* A valid login may inspect its own missing mapping without a roster leak. */ }
        List<Object> options = new ArrayList<>();
        if (p != null) {
            Set<String> codes = new LinkedHashSet<>();
            codes.add(p.organizationCode());
            codes.addAll(p.responsibleOrganizationCodes());
            Engine engine = new Engine(c);
            for (Organization org : c.organizations()) {
                for (Grant grant : c.grants()) {
                    if (!p.roleCodes().contains(grant.roleCode())) continue;
                    Decision d = engine.authorize("server-current-session", ignored -> OptionalLong.of(s.uid),
                            new Resource(grant.resource(), org.organizationCode()), grant.action());
                    if (d.allowed()) { codes.add(org.organizationCode()); break; }
                }
            }
            for (String code : codes) if (activeOrganization(c, code)) options.add(map("organizationCode", code));
        }
        return map("version", c == null ? null : c.version(), "status", status,
                "person", p == null ? null : personMap(p), "organizations", options);
    }

    private static void method(HttpExchange ex, String... allowed) throws Api.ApiException {
        for (String method : allowed) if (method.equalsIgnoreCase(ex.getRequestMethod())) return;
        ex.getResponseHeaders().set("Allow", String.join(", ", allowed));
        throw new Api.ApiException(405, "请求方法不受支持");
    }

    static Configuration parseConfiguration(Object raw) throws Api.ApiException {
        Map<String, Object> c = extensibleObject(raw, "configuration", "combinedApprovals", "version", "codeRules", "roleCodes", "organizations", "people", "relations", "accountBindings", "grants");
        Map<String, Object> rules = object(c.get("codeRules"), "codeRules", "organizationPattern", "personPattern", "rolePattern");
        CodeRules codeRules = new CodeRules(string(rules, "organizationPattern", false, 256), string(rules, "personPattern", false, 256), string(rules, "rolePattern", false, 256));
        List<Organization> organizations = new ArrayList<>();
        for (Object value : list(c, "organizations")) {
            Map<String, Object> m = extensibleObject(value, "organization", "displayName", "organizationCode", "parentOrganizationCode", "enabled");
            String displayName = null;
            if (m.containsKey("displayName")) {
                if (!(m.get("displayName") instanceof String name) || !OrganizationAccess.validOrganizationDisplayName(name))
                    throw new Api.ApiException(400, "机构显示名称须为不含控制字符的非空文本，长度不超过200");
                displayName = name;
            }
            organizations.add(new Organization(text(m, "organizationCode"), string(m, "parentOrganizationCode", true, 128), bool(m, "enabled"), displayName));
        }
        List<Person> people = new ArrayList<>();
        for (Object value : list(c, "people")) {
            Map<String, Object> m = extensibleObject(value, "person", "responsibleOrganizationsByRole", "personCode", "organizationCode", "responsibleOrganizationCodes", "leaderPersonCode", "bpPersonCode", "roleCodes", "enabled");
            Map<String, Set<String>> scopes = new LinkedHashMap<>();
            if (m.containsKey("responsibleOrganizationsByRole")) {
                if (!(m.get("responsibleOrganizationsByRole") instanceof Map<?, ?> roleScopes) || roleScopes.isEmpty() || roleScopes.size() > MAX_ROWS)
                    throw new Api.ApiException(400, "responsibleOrganizationsByRole 必须是明确岗位范围对象，旧配置可省略此字段");
                for (Map.Entry<?, ?> entry : roleScopes.entrySet()) {
                    if (!(entry.getKey() instanceof String role)) throw new Api.ApiException(400, "岗位范围键必须是字符串");
                    text(map("role", role), "role");
                    scopes.put(role, strings(map("organizations", entry.getValue()), "organizations"));
                }
            }
            people.add(new Person(text(m, "personCode"), text(m, "organizationCode"), strings(m, "responsibleOrganizationCodes"),
                    string(m, "leaderPersonCode", true, 128), string(m, "bpPersonCode", true, 128), strings(m, "roleCodes"), bool(m, "enabled"), scopes));
        }
        List<RoleRelations> relations = new ArrayList<>();
        for (Object value : list(c, "relations")) {
            Map<String, Object> m = object(value, "relation", "roleCode", "leader", "bp");
            relations.add(new RoleRelations(text(m, "roleCode"), relation(m.get("leader")), relation(m.get("bp"))));
        }
        List<AccountBinding> bindings = new ArrayList<>();
        for (Object value : list(c, "accountBindings")) {
            Map<String, Object> m = object(value, "accountBinding", "accountId", "personCode", "enabled");
            Object id = m.get("accountId");
            if (!(id instanceof Number) || !Double.isFinite(((Number) id).doubleValue())
                    || ((Number) id).doubleValue() != Math.rint(((Number) id).doubleValue())
                    || ((Number) id).doubleValue() < 1 || ((Number) id).doubleValue() > MAX_SAFE_ACCOUNT_ID)
                throw new Api.ApiException(400, "accountId 必须是安全范围内的正整数 JSON 数字");
            bindings.add(new AccountBinding(((Number) id).longValue(), text(m, "personCode"), bool(m, "enabled")));
        }
        List<Grant> grants = new ArrayList<>();
        for (Object value : list(c, "grants")) {
            Map<String, Object> m = object(value, "grant", "ruleId", "roleCode", "resource", "action", "effect", "scope", "organizationCodes");
            grants.add(new Grant(text(m, "ruleId"), text(m, "roleCode"), text(m, "resource"),
                    enumeration(Action.class, m, "action"), enumeration(Effect.class, m, "effect"), enumeration(Scope.class, m, "scope"), strings(m, "organizationCodes")));
        }
        List<CombinedApprovalAssignment> combined = new ArrayList<>();
        if (c.containsKey("combinedApprovals")) for (Object value : list(c, "combinedApprovals")) {
            Map<String, Object> m = object(value, "combinedApproval", "organizationCode", "personCode", "leaderRoleCode", "bpRoleCode", "evidenceRef");
            combined.add(new CombinedApprovalAssignment(text(m, "organizationCode"), text(m, "personCode"),
                    text(m, "leaderRoleCode"), text(m, "bpRoleCode"), text(m, "evidenceRef")));
        }
        return new Configuration(text(c, "version"), codeRules, strings(c, "roleCodes"), organizations, people, relations, bindings, grants, combined);
    }

    /** An optional extension must be absent for legacy input; explicit null or extra fields never fall back. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extensibleObject(Object raw, String path, String optional, String... required) throws Api.ApiException {
        if (!(raw instanceof Map)) throw new Api.ApiException(400, path + " 必须是对象");
        Map<String, Object> m = (Map<String, Object>) raw;
        if (!m.containsKey(optional)) return object(raw, path, required);
        String[] keys = Arrays.copyOf(required, required.length + 1); keys[required.length] = optional;
        return object(raw, path, keys);
    }

    private static RelationRule relation(Object raw) throws Api.ApiException {
        Map<String, Object> m = object(raw, "relationRule", "required", "allowedTargetRoles", "targetMustCoverOrganization", "allowSelf");
        return new RelationRule(bool(m, "required"), strings(m, "allowedTargetRoles"), bool(m, "targetMustCoverOrganization"), bool(m, "allowSelf"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, String path, String... keys) throws Api.ApiException {
        if (!(raw instanceof Map)) throw new Api.ApiException(400, path + " 必须是对象");
        Map<String, Object> m = (Map<String, Object>) raw;
        if (!m.keySet().equals(new HashSet<>(Arrays.asList(keys))))
            throw new Api.ApiException(400, path + " 字段缺失或含有不支持的字段");
        return m;
    }
    private static String text(Map<String, Object> m, String k) throws Api.ApiException { return string(m, k, false, 128); }
    private static String string(Map<String, Object> m, String k, boolean nullable, int max) throws Api.ApiException {
        Object v = m.get(k);
        if (nullable && v == null) return null;
        if (!(v instanceof String) || ((String) v).length() > max || ((String) v).isBlank() || !v.equals(((String) v).trim()))
            throw new Api.ApiException(400, k + " 必须是无首尾空白的非空字符串" + (nullable ? "或 null" : ""));
        return (String) v;
    }
    private static boolean bool(Map<String, Object> m, String k) throws Api.ApiException {
        if (!(m.get(k) instanceof Boolean)) throw new Api.ApiException(400, k + " 必须是布尔值");
        return (Boolean) m.get(k);
    }
    private static List<?> list(Map<String, Object> m, String k) throws Api.ApiException {
        if (!(m.get(k) instanceof List) || ((List<?>) m.get(k)).size() > MAX_ROWS)
            throw new Api.ApiException(400, k + " 必须是最多 " + MAX_ROWS + " 项的数组");
        return (List<?>) m.get(k);
    }
    private static Set<String> strings(Map<String, Object> m, String k) throws Api.ApiException {
        Set<String> values = new LinkedHashSet<>();
        for (Object v : list(m, k)) {
            String s = text(map(k, v), k);
            if (!values.add(s)) throw new Api.ApiException(400, k + " 不能有重复值");
        }
        return values;
    }
    private static <E extends Enum<E>> E enumeration(Class<E> type, Map<String, Object> m, String k) throws Api.ApiException {
        try { return Enum.valueOf(type, text(m, k)); }
        catch (IllegalArgumentException e) { throw new Api.ApiException(400, k + " 取值不受支持"); }
    }

    static Map<String, Object> toMap(Configuration c) {
        if (c == null) return null;
        List<Object> orgs = new ArrayList<>(), people = new ArrayList<>(), relations = new ArrayList<>(), bindings = new ArrayList<>(), grants = new ArrayList<>();
        for (Organization o : c.organizations()) {
            Map<String, Object> row = map("organizationCode", o.organizationCode(), "parentOrganizationCode", o.parentOrganizationCode(), "enabled", o.enabled());
            if (o.displayName() != null) row.put("displayName", o.displayName());
            orgs.add(row);
        }
        for (Person p : c.people()) people.add(personMap(p));
        for (RoleRelations r : c.relations()) relations.add(map("roleCode", r.roleCode(), "leader", relationMap(r.leader()), "bp", relationMap(r.bp())));
        for (AccountBinding b : c.accountBindings()) bindings.add(map("accountId", b.accountId(), "personCode", b.personCode(), "enabled", b.enabled()));
        for (Grant g : c.grants()) grants.add(map("ruleId", g.ruleId(), "roleCode", g.roleCode(), "resource", g.resource(), "action", g.action() == null ? null : g.action().name(),
                "effect", g.effect() == null ? null : g.effect().name(), "scope", g.scope() == null ? null : g.scope().name(), "organizationCodes", g.organizationCodes()));
        CodeRules r = c.codeRules();
        Map<String, Object> result = map("version", c.version(), "codeRules", r == null ? null : map("organizationPattern", r.organizationPattern(), "personPattern", r.personPattern(), "rolePattern", r.rolePattern()),
                "roleCodes", c.roleCodes(), "organizations", orgs, "people", people, "relations", relations, "accountBindings", bindings, "grants", grants);
        if (!c.combinedApprovals().isEmpty()) {
            List<Object> assignments = new ArrayList<>();
            for (CombinedApprovalAssignment a : c.combinedApprovals()) assignments.add(map("organizationCode", a.organizationCode(),
                    "personCode", a.personCode(), "leaderRoleCode", a.leaderRoleCode(), "bpRoleCode", a.bpRoleCode(), "evidenceRef", a.evidenceRef()));
            result.put("combinedApprovals", assignments);
        }
        return result;
    }
    private static Map<String, Object> personMap(Person p) {
        Map<String, Object> result = map("personCode", p.personCode(), "organizationCode", p.organizationCode(), "responsibleOrganizationCodes", p.responsibleOrganizationCodes(),
                "leaderPersonCode", p.leaderPersonCode(), "bpPersonCode", p.bpPersonCode(), "roleCodes", p.roleCodes(), "enabled", p.enabled());
        if (!p.responsibleOrganizationsByRole().isEmpty()) result.put("responsibleOrganizationsByRole", p.responsibleOrganizationsByRole());
        return result;
    }
    private static Map<String, Object> relationMap(RelationRule r) {
        return r == null ? null : map("required", r.required(), "allowedTargetRoles", r.allowedTargetRoles(), "targetMustCoverOrganization", r.targetMustCoverOrganization(), "allowSelf", r.allowSelf());
    }
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
