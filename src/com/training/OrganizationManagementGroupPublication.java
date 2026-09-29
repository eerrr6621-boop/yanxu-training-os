package com.training;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Server-owned, reviewed publication. Sources and identities never come from a browser body. */
public final class OrganizationManagementGroupPublication {
    private static final String KEY = "m01-management-group-v1";
    private static final String SCHEMA = "M01-MANAGEMENT-PUBLICATION-v1";
    private static final long LIFETIME_MS = 2 * 60 * 60 * 1000L;
    private static final Set<String> REQUEST_KEYS = Set.of("expectedVersion", "snapshotFingerprint", "reviewToken", "reviewed");
    private static final Set<String> RECEIPT_KEYS = Set.of("schema", "publicationKey", "expectedVersion", "version",
            "snapshotFingerprint", "sourceFingerprint", "beforeConfigurationFingerprint", "configurationFingerprint",
            "beforeUsersFingerprint", "afterUsersFingerprint", "managerPersonCode", "members", "evidence", "diff",
            "published", "replayed", "accountsActivated", "passwordsChanged", "createdBy", "technicalIdentity");
    private final OrganizationAccountImport original;
    private final OrganizationManagementSupplementHost supplemental;
    private final OrganizationAccountImportSource.Config originalSources;
    private final OrganizationManagementSupplementSource.Config supplementalSources;
    private Review review;
    private Completed completed;

    private record Sources(OrganizationAccountImport.ProvisioningIntake original,
                           Map<String,Object> supplemental, String fingerprint) {}
    private record State(Sources sources, Configuration configuration, List<Map<String,Object>> users,
                         String usersFingerprint, String configurationFingerprint, String fingerprint) {
        @Override public String toString() { return "State[private management publication snapshot]"; }
    }
    private record Review(Auth.Session actor, String token, long expiresAt, State state,
                          String managerCode, OrganizationManagementGroupPlan.Plan plan) {
        @Override public String toString() { return "Review[private management publication review]"; }
    }
    private record Completed(Auth.Session actor, String token, String requestFingerprint, long expiresAt) {
        @Override public String toString() { return "Completed[private management publication review]"; }
    }

    /** Only a trusted host may construct these independently pinned source configurations. */
    public OrganizationManagementGroupPublication(OrganizationAccountImportSource.Config originalSources,
                                                  OrganizationManagementSupplementSource.Config supplementalSources) {
        this.originalSources = Objects.requireNonNull(originalSources);
        this.supplementalSources = Objects.requireNonNull(supplementalSources);
        original = new OrganizationAccountImport(originalSources);
        supplemental = new OrganizationManagementSupplementHost(supplementalSources);
    }

    /** Normal startup only, outside transactions. Creates empty audit metadata; never seeds an identity. */
    public static void init() throws SQLException {
        synchronized (Api.MUTATION_LOCK) {
            if (!Db.get().getAutoCommit()) throw new IllegalStateException("管理组元数据初始化不能嵌入事务");
            Db.exec("CREATE TABLE IF NOT EXISTS organization_management_group_publications (" +
                    "publication_key VARCHAR(128) PRIMARY KEY, version VARCHAR(128) NOT NULL UNIQUE," +
                    "previous_version VARCHAR(128) NOT NULL, receipt CLOB NOT NULL," +
                    "receipt_sha256 VARCHAR(64) NOT NULL, created_by BIGINT NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /** One current review per host instance; preview and receipt reads perform no SQL writes. */
    public Map<String,Object> preview(Auth.Session supplied) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session actor = authenticateReviewOwner(supplied);
            try {
                clearReviews();
                State state = state(actor);
                Map<String,Object> existing = publication();
                if (existing != null) return verifiedReceipt(actor, state, existing);
                String managerCode = "imp_p_" + UUID.randomUUID().toString().replace("-", "");
                String version = "m01-management-" + UUID.randomUUID();
                OrganizationManagementGroupPlan.Plan plan = OrganizationManagementGroupPlan.build(
                        state.configuration(), state.sources().original(), state.sources().supplemental(),
                        state.users(), managerCode, version);
                verifyTechnicalIdentity(plan, managerCode);
                // Sources and complete live rows must still be the exact snapshot that was reviewed.
                if (!state.fingerprint().equals(state(actor).fingerprint())) throw changed();
                String token = Auth.randomToken(32);
                review = new Review(actor, token, System.currentTimeMillis() + LIFETIME_MS, state, managerCode, plan);
                return copy(map("ready", true, "published", false, "expectedVersion", state.configuration().version(),
                        "snapshotFingerprint", state.fingerprint(), "reviewToken", token,
                        "configuration", OrganizationAccessStore.toMap(plan.configuration()),
                        "diff", plan.diff(), "evidence", plan.evidence(),
                        "accountsActivated", false, "passwordsChanged", false));
            } catch (Exception | Error failure) { clearReviews(); throw failure; }
        }
    }

    /** Explicit confirmation accepts only this review's opaque token and its exact snapshot/version. */
    public Map<String,Object> commit(Auth.Session supplied, Map<String,Object> request) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session actor = authenticateReviewOwner(supplied);
            try {
                validateRequest(request);
                if (!Db.get().getAutoCommit()) throw new IllegalStateException("管理组发布不能嵌入其他事务");
                String requestFingerprint = digest(request);
                if (completed != null && completed.actor() == actor && completed.expiresAt() > System.currentTimeMillis()
                        && completed.token().equals(request.get("reviewToken"))
                        && completed.requestFingerprint().equals(requestFingerprint)) {
                    State current = state(actor);
                    Map<String,Object> stored = publication();
                    if (stored == null) throw conflict("已发布回执缺失，请核对当前配置");
                    return verifiedReceipt(actor, current, stored);
                }
                Review checked = review;
                if (checked == null || checked.actor() != actor || checked.expiresAt() <= System.currentTimeMillis()
                        || !checked.token().equals(request.get("reviewToken")))
                    throw conflict("本次核对已失效，请重新预览管理组");
                review = null; // A confirmation attempt consumes the sole review, including failed attempts.
                completed = null;
                if (!checked.state().configuration().version().equals(request.get("expectedVersion"))
                        || !checked.state().fingerprint().equals(request.get("snapshotFingerprint"))) throw changed();
                if (publication() != null) throw conflict("管理组已有发布回执，请重新读取结果");
                State current = state(actor);
                if (!checked.state().fingerprint().equals(current.fingerprint())) throw changed();
                verifyTechnicalIdentity(checked.plan(), checked.managerCode());
                Map<String,Object> receipt = receipt(checked, actor);
                String expectedAfterUsers = projectedUsersDigest(current.users(), checked.plan().members());
                receipt.put("afterUsersFingerprint", expectedAfterUsers);
                OrganizationAccessStore.publishWithMutation(actor, checked.state().configuration().version(),
                        checked.plan().configuration(), (liveActor, previous, candidate) -> {
                    // This is before any SQL mutation. No Auth operations occur after the role updates.
                    State inside = state(liveActor);
                    if (publication() != null || !checked.state().fingerprint().equals(inside.fingerprint())) throw changed();
                    verifyTechnicalIdentity(checked.plan(), checked.managerCode());
                    if (!digest(OrganizationAccessStore.toMap(candidate)).equals(receipt.get("configurationFingerprint")))
                        throw conflict("待发布配置与核对结果不一致");
                    Set<Long> revoke = new LinkedHashSet<>();
                    for (OrganizationManagementGroupPlan.Member member : checked.plan().members()) {
                        Map<String,Object> before = userById(inside.users(), member.accountId());
                        try (PreparedStatement statement = Db.get().prepareStatement(
                                "UPDATE users SET role='admin' WHERE id=? AND role=?")) {
                            statement.setLong(1, member.accountId());
                            statement.setString(2, (String) before.get("role"));
                            if (statement.executeUpdate() != 1) throw changed();
                        }
                        revoke.add(member.accountId());
                    }
                    // The exact all-column projection proves passwords/status/nonmembers were preserved.
                    if (!expectedAfterUsers.equals(digest(users()))) throw changed();
                    // Files can change outside the application lock. Recheck pins without calling
                    // Auth after the role SQL, so a rolled-back transaction cannot revoke sessions.
                    verifyPinnedSources(inside.sources());
                    String serialized = Json.write(receipt);
                    Db.exec("INSERT INTO organization_management_group_publications(" +
                            "publication_key,version,previous_version,receipt,receipt_sha256,created_by) VALUES(?,?,?,?,?,?)",
                            KEY, candidate.version(), previous.version(), serialized, digest(receipt), liveActor.uid);
                    return revoke;
                });
                completed = new Completed(actor, checked.token(), requestFingerprint, checked.expiresAt());
                return copy(receipt);
            } catch (Exception | Error failure) { clearReviews(); throw failure; }
        }
    }

    /** Recovery after restart/relogin. A mismatched live result is never called an idempotent success. */
    public Map<String,Object> received(Auth.Session supplied) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session actor = authenticateReviewOwner(supplied);
            try {
                State current = state(actor);
                Map<String,Object> stored = publication();
                return stored == null ? map("published", false, "ready", false)
                        : verifiedReceipt(actor, current, stored);
            } catch (Exception | Error failure) { clearReviews(); throw failure; }
        }
    }

    private Map<String,Object> verifiedReceipt(Auth.Session actor, State current, Map<String,Object> stored) throws Exception {
        try {
            Map<String,Object> receipt = object(Json.parse((String) stored.get("receipt")));
            intact(receipt.keySet().equals(RECEIPT_KEYS));
            intact(SCHEMA.equals(receipt.get("schema")) && KEY.equals(receipt.get("publicationKey")));
            intact(Boolean.TRUE.equals(receipt.get("published")) && Boolean.FALSE.equals(receipt.get("replayed"))
                    && Boolean.FALSE.equals(receipt.get("accountsActivated")) && Boolean.FALSE.equals(receipt.get("passwordsChanged")));
            intact(digest(receipt).equals(stored.get("receipt_sha256")));
            intact(Objects.equals(stored.get("version"), receipt.get("version"))
                    && Objects.equals(stored.get("previous_version"), receipt.get("expectedVersion"))
                    && integral(receipt.get("createdBy")) == integral(stored.get("created_by")));
            intact(current.sources().fingerprint().equals(receipt.get("sourceFingerprint")));
            intact(current.configuration() != null && current.configuration().version().equals(receipt.get("version")));
            intact(current.configurationFingerprint().equals(receipt.get("configurationFingerprint")));
            intact(current.usersFingerprint().equals(receipt.get("afterUsersFingerprint")));
            Map<String,Object> previous = Db.one("SELECT version,payload FROM organization_access_config WHERE version=? AND active_slot IS NULL",
                    receipt.get("expectedVersion"));
            intact(previous != null);
            Configuration before = OrganizationAccessStore.parseConfiguration(Json.parse((String) previous.get("payload")));
            intact(before.version().equals(previous.get("version")) && OrganizationAccess.validate(before).valid());
            intact(digest(OrganizationAccessStore.toMap(before)).equals(receipt.get("beforeConfigurationFingerprint")));
            List<Map<String,Object>> beforeUsers = restoreReviewedUsers(current.users(), receipt);
            intact(digest(beforeUsers).equals(receipt.get("beforeUsersFingerprint")));
            OrganizationManagementGroupPlan.Plan rebuilt = OrganizationManagementGroupPlan.build(before,
                    current.sources().original(), current.sources().supplemental(), beforeUsers,
                    (String) receipt.get("managerPersonCode"), (String) receipt.get("version"));
            verifyTechnicalIdentity(rebuilt, (String) receipt.get("managerPersonCode"));
            intact(digest(OrganizationAccessStore.toMap(rebuilt.configuration())).equals(receipt.get("configurationFingerprint")));
            intact(digest(memberRows(rebuilt.members())).equals(digest(receipt.get("members"))));
            intact(digest(rebuilt.evidence()).equals(digest(receipt.get("evidence"))));
            intact(digest(rebuilt.diff()).equals(digest(receipt.get("diff"))));
            intact(digest(technicalIdentity(rebuilt, (String) receipt.get("managerPersonCode"))).equals(digest(receipt.get("technicalIdentity"))));
            intact(restoredSnapshotFingerprint(current, receipt).equals(receipt.get("snapshotFingerprint")));
            for (OrganizationManagementGroupPlan.Member member : rebuilt.members())
                intact("admin".equals(userById(current.users(), member.accountId()).get("role")));
            if (!current.fingerprint().equals(state(actor).fingerprint())) throw changed();
            Map<String,Object> result = copy(receipt);
            result.put("replayed", true);
            return result;
        } catch (Exception rejected) {
            if (rejected instanceof Api.ApiException api && (api.code == 401 || api.code == 403)) throw rejected;
            throw conflict("管理组发布回执与当前来源、账号或配置不一致，请核对后处理");
        }
    }

    private static List<Map<String,Object>> restoreReviewedUsers(List<Map<String,Object>> current,
                                                                 Map<String,Object> receipt) throws Exception {
        Map<String,Object> diff = object(receipt.get("diff"));
        intact(diff.get("userRoleChanges") instanceof List<?> && ((List<?>) diff.get("userRoleChanges")).size() == 10);
        Map<Long,Map<String,Object>> changes = new HashMap<>();
        for (Object raw : (List<?>) diff.get("userRoleChanges")) {
            Map<String,Object> change = object(raw);
            intact(change.keySet().equals(Set.of("reference", "accountId", "personCode", "username", "roleBefore", "roleAfter", "statusBefore", "statusAfter")));
            long id = integral(change.get("accountId"));
            intact(changes.put(id, change) == null && Set.of("admin", "manager", "viewer").contains(change.get("roleBefore"))
                    && "admin".equals(change.get("roleAfter")));
            Map<String,Object> actual = userById(current, id);
            intact(Objects.equals(actual.get("username"), change.get("username")) && "admin".equals(actual.get("role"))
                    && digest(actual.get("status")).equals(digest(change.get("statusBefore")))
                    && digest(actual.get("status")).equals(digest(change.get("statusAfter"))));
        }
        Set<Long> memberIds = new HashSet<>();
        intact(receipt.get("members") instanceof List<?> && ((List<?>) receipt.get("members")).size() == 10);
        for (Object raw : (List<?>) receipt.get("members")) intact(memberIds.add(integral(object(raw).get("accountId"))));
        intact(changes.keySet().equals(memberIds));
        List<Map<String,Object>> before = new ArrayList<>();
        for (Map<String,Object> user : current) {
            Map<String,Object> row = new LinkedHashMap<>(user);
            Map<String,Object> change = changes.get(integral(user.get("id")));
            if (change != null) row.put("role", change.get("roleBefore"));
            before.add(row);
        }
        return before;
    }

    private static String restoredSnapshotFingerprint(State current, Map<String,Object> receipt) throws Exception {
        List<Map<String,Object>> oldRows = new ArrayList<>();
        int removed = 0, restored = 0;
        for (Map<String,Object> row : exactRows("SELECT * FROM organization_access_config ORDER BY id")) {
            if (receipt.get("version").equals(row.get("version"))) {
                intact(integral(row.get("active_slot")) == 1 && receipt.get("expectedVersion").equals(row.get("previous_version"))
                        && integral(row.get("created_by")) == integral(receipt.get("createdBy")));
                removed++;
                continue;
            }
            Map<String,Object> previous = new LinkedHashMap<>(row);
            if (receipt.get("expectedVersion").equals(row.get("version"))) {
                intact(row.get("active_slot") == null);
                previous.put("active_slot", 1);
                restored++;
            }
            oldRows.add(previous);
        }
        intact(removed == 1 && restored == 1);
        List<Map<String,Object>> oldPublications = new ArrayList<>();
        for (Map<String,Object> row : exactRows("SELECT * FROM organization_management_group_publications ORDER BY publication_key"))
            if (!KEY.equals(row.get("publication_key"))) oldPublications.add(row);
        return digest(map("source", current.sources().fingerprint(), "users", receipt.get("beforeUsersFingerprint"),
                "configuration", receipt.get("beforeConfigurationFingerprint"), "configurationRows", oldRows,
                "publicationRows", oldPublications, "intakeRows", intakeRows()));
    }

    private State state(Auth.Session actor) throws Exception {
        requireAdmin(actor);
        Sources sources = sources(actor);
        Configuration configuration = OrganizationAccessStore.configuration();
        if (configuration == null) throw conflict("组织初始配置尚未发布，不能发布管理组");
        List<Map<String,Object>> users = users();
        String userHash = digest(users);
        String configHash = digest(OrganizationAccessStore.toMap(configuration));
        String fingerprint = digest(map("source", sources.fingerprint(), "users", userHash,
                "configuration", configHash, "configurationRows", exactRows("SELECT * FROM organization_access_config ORDER BY id"),
                "publicationRows", exactRows("SELECT * FROM organization_management_group_publications ORDER BY publication_key"),
                "intakeRows", intakeRows()));
        requireAdmin(actor);
        return new State(sources, configuration, users, userHash, configHash, fingerprint);
    }

    private Sources sources(Auth.Session actor) throws Exception {
        OrganizationAccountImport.ProvisioningIntake intake = original.verifiedProvisioningIntake(actor);
        Map<String,Object> addition = supplemental.received(actor);
        verifySupplementSource(intake, addition);
        List<Map<String,Object>> oldRows = new ArrayList<>();
        for (OrganizationAccountImport.ReceivedAccount account : intake.accounts())
            oldRows.add(map("reference", account.sourceReference(), "accountId", account.accountId(),
                    "personCode", account.personCode(), "accountEnabled", account.accountEnabled()));
        String fingerprint = digest(map("originalBatch", intake.source().batchKey(),
                "originalSource", intake.source().fingerprint(), "originalReceipt", intake.receiptFingerprint(),
                "intakeRevision", intake.intakeRevision(), "originalAccounts", oldRows, "supplemental", addition));
        return new Sources(intake, addition, fingerprint);
    }

    private void verifyPinnedSources(Sources checked) throws Exception {
        if (!OrganizationAccountImportSource.load(originalSources).fingerprint().equals(checked.original().source().fingerprint()))
            throw changed();
        verifySupplementSource(checked.original(), checked.supplemental());
    }

    /** A receipt pinned on another database cannot supply IDs to this target database. */
    private void verifySupplementSource(OrganizationAccountImport.ProvisioningIntake intake,
                                        Map<String,Object> addition) throws Exception {
        OrganizationAccountImportSource.Snapshot source = OrganizationManagementSupplementSource.load(supplementalSources);
        if (!source.fingerprint().equals(addition.get("sourceFingerprint"))) throw changed();
        Map<String,String> expected = Map.of("group-main-row-3", "王维明", "group-main-row-10", "沈军");
        intact(source.candidates().size() == 2);
        for (OrganizationAccountImportSource.Candidate candidate : source.candidates())
            intact(Objects.equals(expected.get(candidate.reference()), candidate.name()) && "公司总经理室".equals(candidate.organization()));
        // The source loader already validates this pin's path/size/encoding/JSON schema. Read
        // bounded bytes again and verify the independent hash before comparing every association.
        byte[] bytes;
        try (var stream = Files.newInputStream(supplementalSources.originalReceipt().path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = stream.readNBytes(2 * 1024 * 1024 + 1);
        }
        intact(bytes.length <= 2 * 1024 * 1024 && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                .equals(supplementalSources.originalReceipt().sha256()));
        Map<String,Object> receipt = object(Json.parse(new String(bytes, StandardCharsets.UTF_8)));
        intact(intake.source().batchKey().equals(receipt.get("batchKey")) && intake.source().fingerprint().equals(receipt.get("sourceFingerprint")));
        Map<String,Object> persisted = Db.one("SELECT receipt FROM organization_account_import_batches WHERE batch_key=?", intake.source().batchKey());
        intact(persisted != null);
        Map<String,Object> targetReceipt = object(Json.parse((String) persisted.get("receipt")));
        // Reading an exported replay does not change the underlying original operation.
        receipt.put("replayed", false);
        intact(digest(normalizedIntakeReceipt(receipt)).equals(digest(normalizedIntakeReceipt(targetReceipt))));
        Map<String,String> associations = new HashMap<>();
        for (OrganizationAccountImport.ReceivedAccount account : intake.accounts())
            intact(associations.put(account.sourceReference(), account.accountId() + ":" + account.personCode()) == null);
        intact(receipt.get("rows") instanceof List<?> && ((List<?>) receipt.get("rows")).size() == associations.size());
        Set<String> seen = new HashSet<>();
        for (Object raw : (List<?>) receipt.get("rows")) {
            Map<String,Object> row = object(raw);
            String reference = (String) row.get("reference");
            intact(seen.add(reference) && Objects.equals(associations.get(reference), integral(row.get("accountId")) + ":" + row.get("personCode")));
        }
    }

    private static Map<String,Object> normalizedIntakeReceipt(Map<String,Object> receipt) {
        Map<String,Object> result = new LinkedHashMap<>(receipt);
        intact(receipt.get("rows") instanceof List<?>);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (Object raw : (List<?>) receipt.get("rows")) rows.add(object(raw));
        rows.sort(Comparator.comparing(row -> (String) row.get("reference")));
        result.put("rows", rows);
        return result;
    }

    private static Map<String,Object> intakeRows() throws SQLException {
        return map("state", exactRows("SELECT * FROM organization_account_import_state ORDER BY singleton"),
                "batches", exactRows("SELECT * FROM organization_account_import_batches ORDER BY batch_key"),
                "people", exactRows("SELECT * FROM organization_account_import_people ORDER BY person_code"));
    }

    private static void verifyTechnicalIdentity(OrganizationManagementGroupPlan.Plan plan, String code) throws Exception {
        OrganizationManagementGroupPlan.Member manager = plan.members().stream()
                .filter(m -> "system-manager01".equals(m.reference())).findFirst().orElseThrow();
        intact(code.equals(manager.personCode()));
        if (Db.one("SELECT account_id FROM organization_account_import_people WHERE account_id=? OR person_code=?",
                manager.accountId(), code) != null)
            throw conflict("manager01 已关联员工接收身份，不能作为独立技术身份发布");
    }

    private static Map<String,Object> receipt(Review review, Auth.Session actor) throws Exception {
        State state = review.state();
        OrganizationManagementGroupPlan.Plan plan = review.plan();
        return map("schema", SCHEMA, "publicationKey", KEY, "expectedVersion", state.configuration().version(),
                "version", plan.configuration().version(), "snapshotFingerprint", state.fingerprint(),
                "sourceFingerprint", state.sources().fingerprint(),
                "beforeConfigurationFingerprint", state.configurationFingerprint(),
                "configurationFingerprint", digest(OrganizationAccessStore.toMap(plan.configuration())),
                "beforeUsersFingerprint", state.usersFingerprint(), "afterUsersFingerprint", "",
                "managerPersonCode", review.managerCode(), "members", memberRows(plan.members()),
                "evidence", plan.evidence(), "diff", plan.diff(), "published", true, "replayed", false,
                "accountsActivated", false, "passwordsChanged", false, "createdBy", actor.uid,
                "technicalIdentity", technicalIdentity(plan, review.managerCode()));
    }

    private static Map<String,Object> technicalIdentity(OrganizationManagementGroupPlan.Plan plan, String code) {
        OrganizationManagementGroupPlan.Member manager = plan.members().stream()
                .filter(m -> "system-manager01".equals(m.reference())).findFirst().orElseThrow();
        return map("reference", "system-manager01", "accountId", manager.accountId(), "username", "manager01",
                "personCode", code, "organizationCode", manager.organizationCode(),
                "origin", "SERVER_GENERATED_UUID_FOR_EXISTING_TECHNICAL_ACCOUNT", "employeeIdentity", false);
    }

    private static List<Map<String,Object>> memberRows(List<OrganizationManagementGroupPlan.Member> members) {
        List<Map<String,Object>> rows = new ArrayList<>();
        for (OrganizationManagementGroupPlan.Member member : members)
            rows.add(map("reference", member.reference(), "accountId", member.accountId(), "personCode", member.personCode(),
                    "organizationCode", member.organizationCode(), "name", member.name()));
        return rows;
    }

    private static String projectedUsersDigest(List<Map<String,Object>> users,
                                               List<OrganizationManagementGroupPlan.Member> members) throws Exception {
        Set<Long> targets = new HashSet<>();
        for (OrganizationManagementGroupPlan.Member member : members) targets.add(member.accountId());
        List<Map<String,Object>> projected = new ArrayList<>();
        for (Map<String,Object> row : users) {
            Map<String,Object> after = new LinkedHashMap<>(row);
            if (targets.contains(integral(row.get("id")))) after.put("role", "admin");
            projected.add(after);
        }
        return digest(projected);
    }

    private static Map<String,Object> userById(List<Map<String,Object>> users, long id) throws Exception {
        for (Map<String,Object> user : users) if (integral(user.get("id")) == id) return user;
        throw changed();
    }

    private static List<Map<String,Object>> users() throws Exception { return exactRows("SELECT * FROM users ORDER BY id"); }

    /** Preserve every column and timestamp precision; Db.query intentionally truncates timestamps. */
    private static List<Map<String,Object>> exactRows(String constantSql) throws SQLException {
        List<Map<String,Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = Db.get().prepareStatement(constantSql); ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData metadata = rs.getMetaData();
            while (rs.next()) {
                Map<String,Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    Object value = rs.getObject(i);
                    if (value instanceof Clob clob) value = clob.getSubString(1, Math.toIntExact(clob.length()));
                    else if (value instanceof byte[] bytes) value = Base64.getEncoder().encodeToString(bytes);
                    else if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) value = value.toString();
                    row.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String,Object> publication() throws Exception {
        return Db.one("SELECT * FROM organization_management_group_publications WHERE publication_key=?", KEY);
    }
    private static Auth.Session requireAdmin(Auth.Session supplied) throws Api.ApiException {
        Auth.Session actor = Auth.current(supplied);
        if (actor == null) throw new Api.ApiException(401, "登录会话无效或已失效");
        if (!Auth.isAdmin(actor)) throw new Api.ApiException(403, "仅当前系统管理员可核对和发布管理组");
        return actor;
    }
    /** A failed owner session cannot revive its old review after a temporary role restoration.
     * An unrelated unauthenticated reader cannot erase another administrator's active review. */
    private Auth.Session authenticateReviewOwner(Auth.Session supplied) throws Api.ApiException {
        try { return requireAdmin(supplied); }
        catch (Api.ApiException rejected) {
            if ((review != null && review.actor() == supplied) || (completed != null && completed.actor() == supplied))
                clearReviews();
            throw rejected;
        }
    }
    private static void validateRequest(Map<String,Object> request) throws Api.ApiException {
        if (request == null || !request.keySet().equals(REQUEST_KEYS) || !Boolean.TRUE.equals(request.get("reviewed"))
                || !(request.get("expectedVersion") instanceof String version) || version.isBlank() || version.length() > 128
                || !(request.get("snapshotFingerprint") instanceof String hash) || !hash.matches("[0-9a-f]{64}")
                || !(request.get("reviewToken") instanceof String token) || token.isBlank() || token.length() > 256)
            throw new Api.ApiException(400, "须确认本次预览，只提交原版本、快照、核对凭证和 reviewed=true");
    }
    private void clearReviews() { review = null; completed = null; }
    private static Api.ApiException changed() { return conflict("来源、账号或配置已变化，请重新预览管理组"); }
    private static Api.ApiException conflict(String message) { return new Api.ApiException(409, message); }
    private static void intact(boolean condition) { if (!condition) throw new IllegalArgumentException("管理组证据不一致"); }
    private static long integral(Object value) {
        intact(value instanceof Number && ((Number) value).doubleValue() == ((Number) value).longValue()
                && ((Number) value).longValue() > 0 && ((Number) value).longValue() <= 9007199254740991L);
        return ((Number) value).longValue();
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) { intact(value instanceof Map<?,?>); return (Map<String,Object>) value; }
    private static Map<String,Object> copy(Map<String,Object> value) { return object(Json.parse(Json.write(value))); }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }
    private static String digest(Object value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                Json.write(canonical(value)).getBytes(StandardCharsets.UTF_8)));
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?,?> source) {
            Map<String,Object> result = new TreeMap<>();
            for (Map.Entry<?,?> entry : source.entrySet()) result.put((String) entry.getKey(), canonical(entry.getValue()));
            return result;
        }
        if (value instanceof Iterable<?> source) {
            List<Object> result = new ArrayList<>();
            for (Object item : source) result.add(canonical(item));
            return result;
        }
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }
}
