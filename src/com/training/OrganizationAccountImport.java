package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Administrator-only candidate intake. Never publishes OrganizationAccess or activates login. */
public final class OrganizationAccountImport {
    private static final String BASE = "/api/organization/account-import";
    private static final long MAX_ID = 9007199254740991L;
    private static final long REVIEW_LIFETIME_MS = 2 * 60 * 60 * 1000L;
    private final Config sources;
    private final OrganizationManagementSupplementSource.Config supplementalSources;
    private final String proofSecret = Auth.randomToken(32);
    // Protected by the same lock as Auth, users CRUD and the unique configuration store.
    private final Map<String, Review> reviews = new LinkedHashMap<>();
    private record Review(Auth.Session session, String fingerprint, long revision, String userState,
                          String configurationState, long expires) {}
    private record Choice(String reference, String action, Long accountId, String accountProof) {}
    private record Intake(Map<String,Map<String,Object>> accounts, Map<String,Object> receipt) {}

    /** Internal association, exposed only after the complete source/receipt/intake cross-check. */
    record ReceivedAccount(String sourceReference, long accountId, String personCode, boolean accountEnabled) {
        @Override public String toString() { return "ReceivedAccount[verified account association]"; }
    }
    /** Snapshot already deeply freezes its candidates, evidence and coverage; no raw receipt escapes. */
    record ProvisioningIntake(Snapshot source, long intakeRevision, List<ReceivedAccount> accounts, String receiptFingerprint) {
        ProvisioningIntake {
            source = Objects.requireNonNull(source);
            accounts = List.copyOf(accounts);
            receiptFingerprint = Objects.requireNonNull(receiptFingerprint);
        }
        @Override public String toString() { return "ProvisioningIntake[verified internal preparation]"; }
    }

    /** No initial-provisioning source or configuration escapes the supplemental association API. */
    record SupplementalIntake(String batchKey, String sourceFingerprint, long intakeRevision,
                              List<ReceivedAccount> accounts, String receiptFingerprint) {
        SupplementalIntake { accounts = List.copyOf(accounts); }
        @Override public String toString() { return "SupplementalIntake[verified independent account association]"; }
    }

    /** Construct only in trusted startup code with explicit paths and reviewed digest pins. */
    public OrganizationAccountImport(Config sources) { this(Objects.requireNonNull(sources), null); }

    private OrganizationAccountImport(Config sources, OrganizationManagementSupplementSource.Config supplementalSources) {
        this.sources = sources;
        this.supplementalSources = supplementalSources;
    }

    /** Package-only entry for the dedicated trusted host; never supplied by an HTTP document. */
    static OrganizationAccountImport managementSupplement(OrganizationManagementSupplementSource.Config sources) {
        return new OrganizationAccountImport(null, Objects.requireNonNull(sources));
    }

    /** Call after Db.init/OrganizationAccessStore.init, outside a transaction. Empty metadata only. */
    public static void init() throws SQLException {
        synchronized (Api.MUTATION_LOCK) {
            if (!Db.get().getAutoCommit()) throw new IllegalStateException("账号导入建表不能嵌入事务");
            Db.exec("CREATE TABLE IF NOT EXISTS organization_account_import_state (" +
                    "singleton INT PRIMARY KEY CHECK(singleton=1), revision BIGINT NOT NULL CHECK(revision>=0))");
            Db.exec("CREATE TABLE IF NOT EXISTS organization_account_import_batches (" +
                    "batch_key VARCHAR(128) PRIMARY KEY, source_fingerprint CHAR(64) NOT NULL," +
                    "decision_digest CHAR(64) NOT NULL, receipt CLOB NOT NULL, created_by BIGINT NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            Db.exec("CREATE TABLE IF NOT EXISTS organization_account_import_people (" +
                    "person_code VARCHAR(64) PRIMARY KEY, source_namespace CHAR(64) NOT NULL," +
                    "source_reference VARCHAR(128) NOT NULL, account_id BIGINT NOT NULL UNIQUE REFERENCES users(id)," +
                    "batch_key VARCHAR(128) NOT NULL, source_fingerprint CHAR(64) NOT NULL," +
                    "payload CLOB NOT NULL, disposition VARCHAR(24) NOT NULL, created_by BIGINT NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE(source_namespace,source_reference), UNIQUE(batch_key,source_reference))");
            if (Db.one("SELECT revision FROM organization_account_import_state WHERE singleton=1") == null)
                Db.exec("INSERT INTO organization_account_import_state(singleton,revision) VALUES(1,0)");
        }
    }

    public Map<String, Object> preview(Auth.Session supplied) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session actor = requireAdmin(supplied);
            Snapshot source = source();
            requireAdmin(actor);
            long revision = revision();
            Map<String, Object> batch = existingBatch(source);
            Intake intake = verifiedIntake(source, batch, revision);
            List<Map<String, Object>> resultRows = new ArrayList<>();
            for (Candidate candidate : source.candidates()) {
                Map<String, Object> row = map("reference", candidate.reference(), "name", candidate.name(),
                        "organization", candidate.organization());
                // Private files stay on the server. The browser sees only relevant review fields.
                for (String key : List.of("identities", "approvalEligibility", "combinedDutiesRequiresWorkflowReview", "sourceIdentityEvidence"))
                    row.put(key, candidate.preparation().get(key));
                List<Map<String, Object>> roles = new ArrayList<>();
                for (Map<String, Object> role : roleRows(candidate)) roles.add(map("kind", role.get("kind"),
                        "proposedBranches", role.get("proposedBranches"), "proposedRegions", role.get("proposedRegions"), "canApprove", false));
                row.put("approvalRoles", roles);
                Map<String, Object> stored = intake.accounts.get(candidate.reference());
                row.put("alreadyImported", stored != null);
                row.put("personCode", stored == null ? null : stored.get("person_code"));
                row.put("accountId", stored == null ? null : stored.get("account_id"));
                resultRows.add(row);
            }
            Map<String, Object> response = map("batchKey", source.batchKey(), "sourceFingerprint", source.fingerprint(),
                    "revision", revision, "imported", batch != null, "rows", resultRows,
                    "branchCoverage", coverageProjection(source, intake.accounts),
                    "summary", map("candidates", source.candidates().size(), "historicalExcluded", source.excludedReferences().size(),
                            "approvalPeople", source.candidates().stream().filter(c -> !roleRows(c).isEmpty()).count()),
                    "permissionsPublished", false, "accountsActivated", false);
            if (batch != null) {
                response.put("receipt", intake.receipt);
                response.put("reviewToken", null);
            } else {
                long now = System.currentTimeMillis();
                reviews.entrySet().removeIf(e -> e.getValue().expires < now || Auth.current(e.getValue().session) == null);
                if (reviews.size() >= 256) reviews.remove(reviews.keySet().iterator().next());
                String token = Auth.randomToken(32);
                reviews.put(token, new Review(actor, source.fingerprint(), revision, usersState(), configurationState(), now + REVIEW_LIFETIME_MS));
                response.put("reviewToken", token);
            }
            return response;
        }
    }

    /** Package-only, read-only input for provisioning; never an HTTP DTO or an authorization grant. */
    ProvisioningIntake verifiedProvisioningIntake(Auth.Session supplied) throws Exception {
        if (supplementalSources != null) {
            requireAdmin(supplied);
            throw conflict("补充人员只能追加独立接收记录，不能重新生成初始组织配置");
        }
        return verifiedReceivedIntake(supplied);
    }

    /** Independent receipt association for the supplemental host; does not build a configuration. */
    SupplementalIntake verifiedSupplementalIntake(Auth.Session supplied) throws Exception {
        if (supplementalSources == null) {
            requireAdmin(supplied);
            throw conflict("此来源不是总经理室独立补充批次");
        }
        ProvisioningIntake verified = verifiedReceivedIntake(supplied);
        return new SupplementalIntake(verified.source().batchKey(), verified.source().fingerprint(),
                verified.intakeRevision(), verified.accounts(), verified.receiptFingerprint());
    }

    private ProvisioningIntake verifiedReceivedIntake(Auth.Session supplied) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session actor = requireAdmin(supplied);
            Snapshot source = source();
            requireAdmin(actor);
            long revision = revision();
            Map<String,Object> batch = existingBatch(source);
            Intake intake = verifiedIntake(source, batch, revision);
            if (batch == null) throw conflict("本批账号尚未完整接收，请先完成账号导入核对");
            List<ReceivedAccount> accounts = new ArrayList<>();
            for (Candidate candidate : source.candidates()) {
                Map<String,Object> received = intake.accounts.get(candidate.reference());
                accounts.add(new ReceivedAccount(candidate.reference(), number(received.get("account_id"), false),
                        (String)received.get("person_code"), "RECEIVED_ENABLED".equals(received.get("accountState"))));
            }
            requireAdmin(actor);
            return new ProvisioningIntake(source, revision, accounts, digest(Json.write(batch)));
        }
    }

    /** Cross-check the complete receipt and durable intake before exposing any account association. */
    @SuppressWarnings("unchecked")
    private Intake verifiedIntake(Snapshot source, Map<String,Object> batch, long currentRevision) throws Exception {
        try {
            List<Map<String,Object>> stored = Db.query("SELECT * FROM organization_account_import_people WHERE batch_key=? OR source_namespace=?",
                    source.batchKey(), namespace());
            if (batch == null) {
                intact(stored.isEmpty());
                return new Intake(Map.of(), null);
            }
            Map<String,Object> received = receipt(batch, true);
            intact(received.keySet().equals(Set.of("batchKey","sourceFingerprint","revision","imported","replayed","summary","rows","permissionsPublished","accountsActivated")));
            intact(source.batchKey().equals(received.get("batchKey")) && source.fingerprint().equals(received.get("sourceFingerprint")));
            long receiptRevision = number(received.get("revision"), false);
            intact(receiptRevision <= currentRevision && Boolean.TRUE.equals(received.get("imported"))
                    && Boolean.FALSE.equals(received.get("permissionsPublished")) && Boolean.FALSE.equals(received.get("accountsActivated")));
            intact(batch.get("decision_digest") instanceof String hash && hash.matches("[0-9a-f]{64}"));
            intact(received.get("rows") instanceof List<?> && received.get("summary") instanceof Map<?,?>);
            List<?> receiptRows = (List<?>)received.get("rows");
            intact(receiptRows.size() == source.candidates().size() && stored.size() == source.candidates().size());
            Map<String,Candidate> candidates = new LinkedHashMap<>();
            for (Candidate candidate : source.candidates()) intact(candidates.put(candidate.reference(),candidate)==null);
            Map<String,Map<String,Object>> intakeByRef = new HashMap<>();
            for (Map<String,Object> record : stored) {
                Object ref = record.get("source_reference");
                intact(ref instanceof String && candidates.containsKey(ref) && intakeByRef.put((String)ref,record)==null);
                intact(source.batchKey().equals(record.get("batch_key")) && namespace().equals(record.get("source_namespace"))
                        && source.fingerprint().equals(record.get("source_fingerprint")));
            }
            Map<String,Map<String,Object>> accounts = new LinkedHashMap<>();
            Set<Long> ids = new HashSet<>(); Set<String> people = new HashSet<>(); int created=0, linked=0;
            for (Object raw : receiptRows) {
                intact(raw instanceof Map<?,?>);
                Map<String,Object> row = (Map<String,Object>)raw;
                intact(row.keySet().equals(Set.of("reference","personCode","accountId","action")));
                intact(row.get("reference") instanceof String && candidates.containsKey(row.get("reference")));
                String ref=(String)row.get("reference");
                intact(!accounts.containsKey(ref));
                long accountId = number(row.get("accountId"), false);
                intact(ids.add(accountId) && row.get("personCode") instanceof String code && code.matches("imp_p_[0-9a-f]{32}") && people.add(code));
                Object action=row.get("action");
                intact("CREATE_PENDING".equals(action) || "LINK_EXISTING".equals(action));
                if ("CREATE_PENDING".equals(action)) created++; else linked++;
                Map<String,Object> record=intakeByRef.get(ref);
                intact(record!=null && number(record.get("account_id"),false)==accountId
                        && row.get("personCode").equals(record.get("person_code")) && action.equals(record.get("disposition")));
                // Compare structured JSON, not map iteration order or floating/integer serialization.
                Object expected=Json.parse(Json.write(intakePayload(source,candidates.get(ref),(String)row.get("personCode"),accountId,(String)action)));
                intact(expected.equals(Json.parse(String.valueOf(record.get("payload")))));
                Map<String,Object> current=Db.one("SELECT id,status FROM users WHERE id=?",accountId);
                intact(current!=null && number(current.get("id"),false)==accountId);
                long status=number(current.get("status"),true);intact(status==0 || status==1);
                accounts.put(ref,map("person_code",row.get("personCode"),"account_id",accountId,
                        "accountState",status==0?"RECEIVED_DISABLED":"RECEIVED_ENABLED"));
            }
            Map<String,Object> summary=(Map<String,Object>)received.get("summary");
            intact(summary.keySet().equals(Set.of("candidates","createdPending","linkedExisting","historicalExcluded"))
                    && number(summary.get("candidates"),true)==candidates.size() && number(summary.get("createdPending"),true)==created
                    && number(summary.get("linkedExisting"),true)==linked && number(summary.get("historicalExcluded"),true)==source.excludedReferences().size());
            intact(accounts.keySet().equals(candidates.keySet()));
            return new Intake(accounts,received);
        } catch (Exception invalid) {
            throw conflict("机构覆盖或账号接收关联无法核实，请由管理员检查原批次记录");
        }
    }

    private static void intact(boolean valid) { if (!valid) throw new IllegalArgumentException("Unverified intake"); }

    private static List<Map<String,Object>> coverageProjection(Snapshot source, Map<String,Map<String,Object>> accounts) {
        Map<String,Candidate> candidates = new HashMap<>();
        source.candidates().forEach(candidate -> candidates.put(candidate.reference(),candidate));
        List<Map<String,Object>> result=new ArrayList<>();
        for (BranchCoverage coverage : source.branchCoverage()) {
            result.add(map("branch",coverage.branch(),"region",coverage.region(),
                    "leaderCandidates",coverageCandidates(coverage.leaderReferences(),coverage.branch(),false,candidates,accounts),
                    "bpCandidates",coverageCandidates(coverage.bpReferences(),coverage.branch(),true,candidates,accounts),
                    "combinedCandidates",coverage.combinedReferences(),"routingStatus",coverage.routingStatus(),
                    "defaultHandlerReference",null,"canApprove",false,"readOnly",true,"preparationOnly",true));
        }
        return result;
    }

    private static List<Map<String,Object>> coverageCandidates(List<String> references, String branch, boolean bp,
            Map<String,Candidate> candidates, Map<String,Map<String,Object>> accounts) {
        List<Map<String,Object>> result=new ArrayList<>();
        for (String ref : references) {
            Set<String> kinds=new LinkedHashSet<>();
            for (Map<String,Object> role : roleRows(candidates.get(ref)))
                if (bp=="BP".equals(role.get("kind")) && ((List<?>)role.get("proposedBranches")).contains(branch)) kinds.add((String)role.get("kind"));
            Map<String,Object> received=accounts.get(ref);
            result.add(map("reference",ref,"roleKinds",List.copyOf(kinds),"accountId",received==null?null:received.get("account_id"),
                    "accountState",received==null?"NOT_RECEIVED":received.get("accountState")));
        }
        return result;
    }

    /** Explicit numeric ID lookup only. Never search or merge by name or office staff ID. */
    public Map<String, Object> account(Auth.Session supplied, long accountId) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            requireAdmin(supplied);
            if (supplementalSources != null) {
                source();
                requireAdmin(supplied);
            }
            if (accountId <= 0 || accountId > MAX_ID) throw bad("请输入有效的已有账号编号");
            Map<String, Object> user = user(accountId);
            if (user == null) throw new Api.ApiException(404, "未找到该账号");
            boolean available = available(accountId);
            return map("accountId", accountId, "username", user.get("username"), "name", user.get("name"),
                    "status", user.get("status"), "role", user.get("role"), "available", available,
                    "proof", available ? accountProof(user) : null);
        }
    }

    public Map<String, Object> commit(Auth.Session supplied, Map<String, Object> body) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session actor = requireAdmin(supplied);
            if (!Db.get().getAutoCommit()) throw new IllegalStateException("账号导入不能嵌入其他事务");
            requireKeys(body, Set.of("expectedRevision", "sourceFingerprint", "reviewToken", "decisions"));
            long expectedRevision = number(body.get("expectedRevision"), true);
            String fingerprint = hashText(body.get("sourceFingerprint"));
            String token = hashText(body.get("reviewToken"));
            Snapshot source = source();
            if (!source.fingerprint().equals(fingerprint)) throw conflict("来源已变化，请刷新后重新核对");
            List<Choice> choices = choices(body.get("decisions"), source);
            String decisionDigest = choicesDigest(choices);
            Review reviewed = reviews.get(token);
            if (reviewed == null || reviewed.session != actor || reviewed.expires < System.currentTimeMillis() ||
                    !reviewed.fingerprint.equals(fingerprint)) throw conflict("核对已失效，请刷新批次重新核对");
            try {
                return Db.transaction(() -> {
                    requireAdmin(actor);
                    // Same authorized command replays its committed receipt, even after its CAS advanced.
                    Map<String, Object> existing = existingBatch(source);
                    if (existing != null) {
                        if (!decisionDigest.equals(existing.get("decision_digest"))) throw conflict("本批已导入，不能用不同核对结果重放");
                        Map<String,Object> verifiedReceipt = verifiedIntake(source, existing, revision()).receipt;
                        verifyReplayChoices(choices, verifiedReceipt);
                        return verifiedReceipt;
                    }
                    long currentRevision = revision();
                    if (expectedRevision != currentRevision || reviewed.revision != currentRevision ||
                            !reviewed.userState.equals(usersState()) || !reviewed.configurationState.equals(configurationState()))
                        throw conflict("账号或配置已变化，请刷新后重新核对");
                    for (Choice choice : choices) {
                        if (Db.one("SELECT person_code FROM organization_account_import_people WHERE source_namespace=? AND source_reference=?",
                                namespace(), choice.reference) != null) throw conflict("来源记录已导入，请先核对原批次");
                        if (choice.accountId != null) {
                            Map<String, Object> account = user(choice.accountId);
                            if (account == null || !available(choice.accountId) || !accountProof(account).equals(choice.accountProof))
                                throw conflict("已有账号状态或绑定已变化，请重新核对账号");
                        }
                    }
                    List<Map<String, Object>> imported = new ArrayList<>();
                    int created = 0, linked = 0;
                    Map<String, Candidate> candidateMap = new LinkedHashMap<>();
                    source.candidates().forEach(c -> candidateMap.put(c.reference(), c));
                    for (Choice choice : choices) {
                        Candidate candidate = candidateMap.get(choice.reference);
                        String personCode = "imp_p_" + UUID.randomUUID().toString().replace("-", "");
                        long accountId;
                        if (choice.accountId == null) {
                            String username = "pending_" + UUID.randomUUID().toString().replace("-", "");
                            // No password, shared or random, is ever assigned. Auth.verify(null) cannot activate it.
                            accountId = Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,NULL,?,'viewer',0)",
                                    username, candidate.name());
                            if (accountId <= 0 || accountId > MAX_ID) throw new SQLException("Invalid generated account identifier");
                            created++;
                        } else { accountId = choice.accountId; linked++; }
                        Map<String, Object> payload = intakePayload(source, candidate, personCode, accountId, choice.action);
                        Db.exec("INSERT INTO organization_account_import_people(person_code,source_namespace,source_reference,account_id," +
                                        "batch_key,source_fingerprint,payload,disposition,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                                personCode, namespace(), choice.reference, accountId, source.batchKey(), source.fingerprint(),
                                Json.write(payload), choice.action, actor.uid);
                        imported.add(map("reference", choice.reference, "personCode", personCode, "accountId", accountId, "action", choice.action));
                    }
                    if (currentRevision >= MAX_ID) throw conflict("导入版本需要维护，请稍后重试");
                    long nextRevision = currentRevision + 1;
                    Map<String, Object> result = map("batchKey", source.batchKey(), "sourceFingerprint", source.fingerprint(),
                            "revision", nextRevision, "imported", true, "replayed", false,
                            "summary", map("candidates", imported.size(), "createdPending", created, "linkedExisting", linked,
                                    "historicalExcluded", source.excludedReferences().size()), "rows", imported,
                            "permissionsPublished", false, "accountsActivated", false);
                    Db.exec("INSERT INTO organization_account_import_batches(batch_key,source_fingerprint,decision_digest,receipt,created_by) VALUES(?,?,?,?,?)",
                            source.batchKey(), source.fingerprint(), decisionDigest, Json.write(result), actor.uid);
                    try (PreparedStatement ps = Db.get().prepareStatement("UPDATE organization_account_import_state SET revision=? WHERE singleton=1 AND revision=?")) {
                        ps.setLong(1, nextRevision); ps.setLong(2, currentRevision);
                        if (ps.executeUpdate() != 1) throw conflict("导入状态已变化，请刷新后重试");
                    }
                    requireAdmin(actor);
                    return result;
                });
            } catch (Api.ApiException e) { throw e; }
            catch (SQLException e) {
                if ("23505".equals(e.getSQLState()) || "23503".equals(e.getSQLState())) throw conflict("账号或导入记录已变化，请刷新后重新核对");
                throw new Api.ApiException(500, "本批导入未完成，已撤回本次更改；请刷新核实后重试");
            }
        }
    }

    /** Api.route mounts this before generic CRUD; Api retains JSON limits and response flushing. */
    public boolean handle(HttpExchange exchange, Auth.Session supplied) throws Exception {
        if (supplementalSources != null) return false; // No supplemental browser route or old-host replacement.
        String path = exchange.getRequestURI().getPath();
        if (!path.equals(BASE) && !path.startsWith(BASE + "/")) return false;
        synchronized (Api.MUTATION_LOCK) {
            requireAdmin(supplied);
            if (exchange.getRequestURI().getRawQuery() != null) throw bad("导入接口不接受查询参数");
            Object result;
            if (path.equals(BASE + "/preview")) { method(exchange, "GET"); result = preview(supplied); }
            else if (path.matches(java.util.regex.Pattern.quote(BASE) + "/accounts/[1-9][0-9]{0,15}")) {
                method(exchange, "GET");
                long accountId;
                try { accountId = Long.parseLong(path.substring(path.lastIndexOf('/') + 1)); }
                catch (NumberFormatException e) { throw bad("账号编号无效"); }
                result = account(supplied, accountId);
            } else if (path.equals(BASE + "/commit")) { method(exchange, "POST"); result = commit(supplied, Api.body(exchange)); }
            else throw new Api.ApiException(404, "未找到账号导入入口");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            Api.ok(exchange, result);
            return true;
        }
    }

    /** Host account deletion should use this in its existing mutation transaction for a clear 409. */
    public static boolean hasImportedAccount(long accountId) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            return Db.one("SELECT person_code FROM organization_account_import_people WHERE account_id=?", accountId) != null;
        }
    }

    private Snapshot source() throws Api.ApiException {
        try { return supplementalSources == null ? OrganizationAccountImportSource.load(sources)
                : OrganizationManagementSupplementSource.load(supplementalSources); }
        catch (Exception e) {
            if (supplementalSources != null) reviews.clear();
            throw conflict("可信名单或确认依据未配置、已变化或不一致，请由管理员核对服务器材料");
        }
    }
    private String namespace() { return supplementalSources == null ? sources.candidatePolicy().sha256()
            : OrganizationManagementSupplementSource.namespace(supplementalSources); }
    private static Auth.Session requireAdmin(Auth.Session supplied) throws Api.ApiException {
        Auth.Session current = Auth.current(supplied);
        if (current == null) throw new Api.ApiException(401, "登录会话无效或已失效");
        if (!Auth.isAdmin(current)) throw new Api.ApiException(403, "仅系统管理员可核对和导入账号");
        return current;
    }
    private static long revision() throws Exception {
        Map<String, Object> row = Db.one("SELECT revision FROM organization_account_import_state WHERE singleton=1");
        if (row == null) throw new Api.ApiException(503, "账号导入尚未初始化");
        return number(row.get("revision"), true);
    }
    private Map<String, Object> existingBatch(Snapshot source) throws Exception {
        Map<String, Object> existing = Db.one("SELECT source_fingerprint,decision_digest,receipt FROM organization_account_import_batches WHERE batch_key=?", source.batchKey());
        if (existing != null && !source.fingerprint().equals(existing.get("source_fingerprint")))
            throw conflict("已导入批次的材料版本不同，请先核对，不覆盖原批次");
        return existing;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> receipt(Map<String, Object> batch, boolean replay) throws Api.ApiException {
        Object parsed;
        try { parsed = Json.parse(String.valueOf(batch.get("receipt"))); }
        catch (Exception e) { throw new Api.ApiException(500, "导入回执无法核实，请由管理员检查"); }
        if (!(parsed instanceof Map)) throw new Api.ApiException(500, "导入回执无法核实，请由管理员检查");
        if (!Boolean.FALSE.equals(((Map<?,?>)parsed).get("replayed")))
            throw new Api.ApiException(500, "原始接收回执无法核实，请由管理员检查");
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) parsed);
        result.put("replayed", replay);
        return result;
    }
    private static Map<String, Object> user(long id) throws Exception {
        return Db.one("SELECT id,username,password,name,role,status FROM users WHERE id=?", id);
    }
    private boolean available(long id) throws Exception {
        if (hasImportedAccount(id)) return false;
        OrganizationAccess.Configuration config = OrganizationAccessStore.configuration();
        return config == null || config.accountBindings().stream().noneMatch(b -> b.accountId() == id);
    }
    private String accountProof(Map<String, Object> user) { return digest(proofSecret + Json.write(user)); }
    private static String usersState() throws Exception {
        return digest(Json.write(Db.query("SELECT id,username,password,name,role,status FROM users ORDER BY id")));
    }
    private static String configurationState() throws Exception {
        OrganizationAccess.Configuration config = OrganizationAccessStore.configuration();
        return config == null ? "NONE" : digest(Json.write(OrganizationAccessStore.toMap(config)));
    }
    private static List<Choice> choices(Object raw, Snapshot source) throws Api.ApiException {
        if (!(raw instanceof List<?> items) || items.size() != source.candidates().size()) throw bad("请逐人核对本批全部候选，历史记录不参与导入");
        Set<String> expected = new LinkedHashSet<>(); source.candidates().forEach(c -> expected.add(c.reference()));
        Set<String> seen = new HashSet<>(); Set<Long> accounts = new HashSet<>(); List<Choice> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> values)) throw bad("核对记录格式不正确");
            requireKeys(values, Set.of("reference", "action", "accountId", "accountProof", "reviewed"));
            if (!(values.get("reference") instanceof String reference) || !expected.contains(reference) || !seen.add(reference) || !Boolean.TRUE.equals(values.get("reviewed")))
                throw bad("每名候选必须明确核对一次，不能加入历史或其他人员");
            Object action = values.get("action");
            if ("CREATE_PENDING".equals(action)) {
                if (values.get("accountId") != null || values.get("accountProof") != null) throw bad("新建待启用账号不能同时关联现有账号");
                result.add(new Choice(reference, "CREATE_PENDING", null, null));
            } else if ("LINK_EXISTING".equals(action)) {
                long accountId = number(values.get("accountId"), false);
                String proof = hashText(values.get("accountProof"));
                if (!accounts.add(accountId)) throw bad("不能把多名候选绑定到同一已有账号");
                result.add(new Choice(reference, "LINK_EXISTING", accountId, proof));
            } else throw bad("请明确选择新建待启用账号或关联已有账号");
        }
        result.sort(Comparator.comparing(Choice::reference));
        return List.copyOf(result);
    }
    private static String choicesDigest(List<Choice> choices) {
        return digest(Json.write(choices.stream().map(c -> map("reference", c.reference, "action", c.action,
                "accountId", c.accountId, "accountProof", c.accountProof)).toList()));
    }

    /** A well-shaped durable receipt must still agree with the already-authorized command. */
    @SuppressWarnings("unchecked")
    private static void verifyReplayChoices(List<Choice> choices, Map<String,Object> verifiedReceipt) throws Api.ApiException {
        Map<String,Map<String,Object>> rows = new HashMap<>();
        for (Object raw : (List<?>) verifiedReceipt.get("rows")) {
            Map<String,Object> row = (Map<String,Object>) raw;
            rows.put((String)row.get("reference"), row);
        }
        for (Choice choice : choices) {
            Map<String,Object> row = rows.get(choice.reference);
            if (row == null || !choice.action.equals(row.get("action")) ||
                    (choice.accountId != null && choice.accountId.longValue() != number(row.get("accountId"),false)))
                throw conflict("已接收回执与原核对决定不一致，请核实原批次记录");
        }
    }
    private static Map<String, Object> intakePayload(Snapshot source, Candidate candidate, String personCode, long accountId, String action) {
        Map<String, Set<String>> scopes = new LinkedHashMap<>();
        List<Map<String, Object>> combined = new ArrayList<>();
        for (Map<String, Object> role : roleRows(candidate)) {
            String kind = String.valueOf(role.get("kind"));
            Set<String> branches = scopes.computeIfAbsent(kind, ignored -> new LinkedHashSet<>());
            for (Object branch : (List<?>) role.get("proposedBranches")) branches.add(String.valueOf(branch));
        }
        if (Boolean.TRUE.equals(candidate.preparation().get("combinedDutiesRequiresWorkflowReview"))) {
            Set<String> bp = scopes.getOrDefault("BP", Set.of());
            for (Map<String, Object> role : roleRows(candidate)) if (!"BP".equals(role.get("kind")))
                for (Object branch : (List<?>) role.get("proposedBranches")) if (bp.contains(branch))
                    combined.add(map("organizationDisplayName", branch, "internalPersonCode", personCode,
                            "leaderRole", role.get("kind"), "bpRole", "BP", "evidence", role.get("source"), "workflowReviewRequired", true));
        }
        return map("internalPersonCode", personCode, "accountId", accountId, "sourceReference", candidate.reference(),
                "name", candidate.name(), "organizationDisplayName", candidate.organization(), "identityNamespace", "SERVER_INTERNAL_INTAKE",
                "formalOfficePersonCode", null, "formalOfficeOrganizationCode", null,
                "preparation", candidate.preparation(), "preparedOrganizationsByRole", scopes, "combinedApprovalsPrepared", combined,
                "sourceEvidence", source.evidence(), "sourceFingerprint", source.fingerprint(), "accountDisposition", action,
                "authorizationPublished", false, "bindingPublished", false);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> roleRows(Candidate c) { return (List<Map<String, Object>>) c.preparation().get("approvalRoles"); }
    private static void requireKeys(Map<?, ?> body, Set<String> keys) throws Api.ApiException {
        if (body == null || !body.keySet().equals(keys)) throw bad("只接受本轮核对字段，不接受来源、岗位、邮箱或密码设置");
    }
    private static long number(Object value, boolean zero) throws Api.ApiException {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue() ||
                n.longValue() < (zero ? 0 : 1) || n.longValue() > MAX_ID) throw bad("账号编号或版本无效");
        return n.longValue();
    }
    private static String hashText(Object value) throws Api.ApiException {
        if (!(value instanceof String s) || !s.matches("[a-f0-9]{64}")) throw bad("核对标识无效，请重新读取批次");
        return s;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("摘要功能不可用"); }
    }
    private static void method(HttpExchange exchange, String expected) throws Api.ApiException {
        if (!expected.equals(exchange.getRequestMethod())) { exchange.getResponseHeaders().set("Allow", expected); throw new Api.ApiException(405, "请求方法不受支持"); }
    }
    private static Api.ApiException bad(String message) { return new Api.ApiException(400, message); }
    private static Api.ApiException conflict(String message) { return new Api.ApiException(409, message); }
    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
}
