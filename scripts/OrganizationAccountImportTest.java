package com.training;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.h2.api.Trigger;
import static com.training.OrganizationAccess.*;

/** Integration checks use only explicit synthetic users, caller-owned fixtures and a new temporary H2. */
public final class OrganizationAccountImportTest {
    private static int checks;
    private static final String PASSWORD = "SYNTHETIC-M01-IMPORT-20260923";
    private static final String PREFIX = "/api/organization/account-import";
    private static Auth.Session admin, viewer;
    private static String adminToken, viewerToken;
    private static long adminId, viewerId;
    private static Map<String, Object> committedRequest;
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static void rejects(int code, Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected rejection: " + label); }
        catch (Api.ApiException error) { check(error.code == code, label + " (HTTP " + error.code + ")"); }
    }
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) value.put((String) values[i], values[i + 1]);
        return value;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Object value) { return (List<Map<String, Object>>) value; }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static String string(Object value) { return String.valueOf(value); }
    private static Map<String, Object> copy(Map<String, Object> value) { return object(Json.parse(Json.write(value))); }
    private static long user(String username, String name, String role, int status, String passwordHash) throws Exception {
        return Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,?)", username, passwordHash, name, role, status);
    }
    private static Map<String, Object> allCreate(Map<String, Object> preview) {
        List<Object> decisions = new ArrayList<>();
        for (Map<String, Object> row : rows(preview.get("rows"))) decisions.add(map("reference", row.get("reference"),
                "action", "CREATE_PENDING", "accountId", null, "accountProof", null, "reviewed", true));
        return map("expectedRevision", preview.get("revision"), "sourceFingerprint", preview.get("sourceFingerprint"),
                "reviewToken", preview.get("reviewToken"), "decisions", decisions);
    }
    private static void linkDecision(Map<String, Object> body, int index, Map<String, Object> account) {
        Map<String, Object> decision = rows(body.get("decisions")).get(index);
        decision.put("action", "LINK_EXISTING"); decision.put("accountId", account.get("accountId")); decision.put("accountProof", account.get("proof"));
    }
    private static String databaseState() throws Exception {
        return Json.write(map("users", Db.query("SELECT * FROM users ORDER BY id"),
                "state", Db.query("SELECT * FROM organization_account_import_state ORDER BY singleton"),
                "batches", Db.query("SELECT * FROM organization_account_import_batches ORDER BY batch_key"),
                "people", Db.query("SELECT * FROM organization_account_import_people ORDER BY person_code"),
                "configuration", Db.query("SELECT * FROM organization_access_config ORDER BY id")));
    }
    private static void noMutation(String before, String label) throws Exception { check(before.equals(databaseState()), label); }
    private static Map<String, String> sourceDigests(Path root) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList())
                hashes.put(root.relativize(path).toString(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return hashes;
    }
    private static OrganizationAccountImportSource.Config otherBatch(OrganizationAccountImportSource.Config c, String key) {
        return new OrganizationAccountImportSource.Config(key, c.candidates(), c.candidatePolicy(), c.roleSource(), c.authorization(),
                c.audit(), c.regionReference(), c.scopeDecision(), c.preparedPreview(), c.originals());
    }
    private static Configuration published(String version, long account, boolean enabled) {
        RelationRule optional = new RelationRule(false, Set.of("FILLER"), false, false);
        return new Configuration(version, new CodeRules("O[0-9]+", "P[0-9]+", "[A-Z]+"), Set.of("FILLER"),
                List.of(new Organization("O1", null, true)),
                List.of(new Person("P1", "O1", Set.of(), null, null, Set.of("FILLER"), true)),
                List.of(new RoleRelations("FILLER", optional, optional)), List.of(new AccountBinding(account, "P1", enabled)), List.of());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Fresh data and fixture directories required");
        Path data = Path.of(args[0]).toAbsolutePath(), sources = Path.of(args[1]).toAbsolutePath();
        for (Path path : List.of(data, sources)) try (java.util.stream.Stream<Path> files = Files.list(path)) {
            if (!Files.isDirectory(path) || files.findAny().isPresent()) throw new IllegalArgumentException("Only fresh empty test directories accepted");
        }
        System.setProperty("data.dir", data.toString()); System.setProperty("bootstrap.demo", "false");
        try {
            Db.exec("CREATE TABLE users(id IDENTITY PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            String passwordHash = Auth.hash(PASSWORD);
            adminId = user("synthetic-import-admin", "SYNTHETIC ADMIN", "admin", 1, passwordHash);
            viewerId = user("synthetic-import-viewer", "SYNTHETIC VIEWER", "viewer", 1, passwordHash);
            Db.init(); OrganizationAccessStore.init();
            check(Db.count("users") == 2 && Db.count("teachers") == 0, "startup contains only explicit synthetic users, no demonstration seed");
            OrganizationAccountImport.init(); OrganizationAccountImport.init();
            check(Db.count("users") == 2 && OrganizationAccessStore.configuration() == null, "import init creates no accounts or published config");
            check(Db.count("organization_account_import_people") == 0 && Db.count("organization_account_import_batches") == 0, "import init has no business seed");
            adminToken = Auth.login("synthetic-import-admin", PASSWORD); admin = Auth.get(adminToken);
            viewerToken = Auth.login("synthetic-import-viewer", PASSWORD); viewer = Auth.get(viewerToken);
            check(admin != null && viewer != null, "real Auth.login sessions");
            OrganizationAccountImportSource.Config config = OrganizationAccountImportSourceTest.fixture(sources.resolve("primary"));
            Map<String, String> sourceBefore = sourceDigests(sources);
            OrganizationAccountImport service = new OrganizationAccountImport(config);
            exerciseAuthentication(service, passwordHash);
            exerciseValidationAndRollback(service);
            exerciseLinksAndImport(service, config, sources, passwordHash);
            exerciseHttp(service);
            exerciseSourceFreshness(sources);
            exerciseDifferentRoleScopes(sources);
            exerciseConcurrency(config, sources);
            check(sourceBefore.entrySet().stream().allMatch(entry -> {
                try { return Objects.equals(entry.getValue(), sourceDigests(sources).get(entry.getKey())); }
                catch (Exception error) { throw new RuntimeException(error); }
            }), "all original source inputs remain unchanged");
            System.out.println("OrganizationAccountImport: " + checks + " checks passed (isolated synthetic H2 and ephemeral HTTP)");
        } finally { if (!Db.get().isClosed()) Db.exec("SHUTDOWN"); }
    }

    private static void exerciseAuthentication(OrganizationAccountImport service, String passwordHash) throws Exception {
        String before = databaseState();
        rejects(401, () -> service.preview(null), "anonymous preview denied");
        rejects(403, () -> service.preview(viewer), "viewer preview denied");
        Auth.Session forged = new Auth.Session(); forged.uid = adminId; forged.role = "admin";
        rejects(401, () -> service.preview(forged), "known uid and forged role do not authenticate");
        rejects(401, () -> service.account(forged, viewerId), "forged account lookup denied");
        rejects(401, () -> service.commit(forged, Map.of()), "forged commit denied before inputs inspected");
        rejects(403, () -> service.account(viewer, viewerId), "ordinary user cannot inspect import identities");
        rejects(403, () -> service.commit(viewer, Map.of()), "ordinary user cannot commit");
        long temporary = user("synthetic-temporary-admin", "SYNTHETIC TEMP", "admin", 1, passwordHash);
        String token = Auth.login("synthetic-temporary-admin", PASSWORD); Auth.Session session = Auth.get(token);
        Db.exec("UPDATE users SET role='viewer' WHERE id=?", temporary);
        rejects(403, () -> service.preview(session), "database downgrade overrides cached admin");
        Db.exec("UPDATE users SET role='admin' WHERE id=?", temporary);
        Auth.logout(token);
        rejects(401, () -> service.preview(session), "logged out session stays revoked");
        token = Auth.login("synthetic-temporary-admin", PASSWORD); Auth.Session disabled = Auth.get(token);
        Db.exec("UPDATE users SET status=0 WHERE id=?", temporary);
        rejects(401, () -> service.preview(disabled), "disabled admin rejected immediately");
        Db.exec("UPDATE users SET status=1 WHERE id=?", temporary);
        rejects(401, () -> service.preview(disabled), "reenable does not revive revoked session");
        Auth.Session tampered = Auth.get(Auth.login("synthetic-temporary-admin", PASSWORD)); tampered.uid = adminId;
        rejects(401, () -> service.preview(tampered), "signed identity uid mutation rejected");
        Db.exec("DELETE FROM users WHERE id=?", temporary);
        noMutation(before, "authentication probes did not alter import or user state");
    }

    private static void exerciseValidationAndRollback(OrganizationAccountImport service) throws Exception {
        Map<String, Object> preview = service.preview(admin);
        check(rows(preview.get("rows")).size() == 7, "all seven approved candidates retained");
        check(Boolean.FALSE.equals(preview.get("imported")), "fresh batch not imported");
        check(preview.get("reviewToken") instanceof String && !string(preview.get("reviewToken")).isBlank(), "review token issued only to true admin");
        Map<String, Object> request = allCreate(preview);
        String before = databaseState();
        Map<String, Object> invalid = copy(request); rows(invalid.get("decisions")).get(0).put("reviewed", false);
        rejects(400, () -> service.commit(admin, invalid), "each person must be explicitly reviewed");
        Map<String, Object> missing = copy(request); rows(missing.get("decisions")).remove(0);
        rejects(400, () -> service.commit(admin, missing), "missing-job or missing-ID candidate cannot be omitted");
        Map<String, Object> duplicate = copy(request); rows(duplicate.get("decisions")).set(1, rows(duplicate.get("decisions")).get(0));
        rejects(400, () -> service.commit(admin, duplicate), "duplicate reference cannot replace another candidate");
        Map<String, Object> history = copy(request); rows(history.get("decisions")).get(0).put("reference", "teacher-row-12");
        rejects(400, () -> service.commit(admin, history), "historical excluded person cannot enter import");
        Map<String, Object> uncheckedType = copy(request); rows(uncheckedType.get("decisions")).get(0).put("reviewed", "true");
        rejects(400, () -> service.commit(admin, uncheckedType), "reviewed must be a boolean");
        Map<String, Object> extra = copy(request); extra.put("grantAdmin", true);
        rejects(400, () -> service.commit(admin, extra), "unknown request field cannot add authority");
        Map<String, Object> extraRow = copy(request); rows(extraRow.get("decisions")).get(0).put("role", "admin");
        rejects(400, () -> service.commit(admin, extraRow), "unknown person decision field rejected");
        Map<String, Object> unknownAction = copy(request); rows(unknownAction.get("decisions")).get(0).put("action", "ACTIVATE_ADMIN");
        rejects(400, () -> service.commit(admin, unknownAction), "unsupported activation or privilege action rejected");
        Map<String, Object> badRevision = copy(request); badRevision.put("expectedRevision", 0.5);
        rejects(400, () -> service.commit(admin, badRevision), "fractional revision rejected");
        Map<String, Object> wrongFingerprint = copy(request); wrongFingerprint.put("sourceFingerprint", "0".repeat(64));
        rejects(409, () -> service.commit(admin, wrongFingerprint), "changed fingerprint cannot reuse review");
        Map<String, Object> badToken = copy(request); badToken.put("reviewToken", "a".repeat(64));
        rejects(409, () -> service.commit(admin, badToken), "known ID is not a review token");
        noMutation(before, "all malformed decisions leave entire database intact");
        Auth.Session secondAdmin = Auth.get(Auth.login("synthetic-import-admin", PASSWORD));
        rejects(409, () -> service.commit(secondAdmin, request), "review token is tied to its issuing session");

        // Force an SQL failure after the first inserted pending user, then require a full rollback.
        FailSecondPending.count = 0;
        Db.exec("CREATE TRIGGER synthetic_fail_second BEFORE INSERT ON users FOR EACH ROW CALL 'com.training.OrganizationAccountImportTest$FailSecondPending'");
        before = databaseState();
        try { rejects(500, () -> service.commit(admin, allCreate(service.preview(admin))), "injected second insert fails with sanitized HTTP error"); }
        finally { Db.exec("DROP TRIGGER synthetic_fail_second"); }
        noMutation(before, "SQL failure rolls back first user, mappings, receipt and revision");
        // Change CAS state inside this same transaction after all inserts, forcing the final CAS to fail.
        Db.exec("CREATE TRIGGER synthetic_force_cas AFTER INSERT ON organization_account_import_batches FOR EACH ROW CALL 'com.training.OrganizationAccountImportTest$ForceCasFailure'");
        before = databaseState();
        try { rejects(409, () -> service.commit(admin, allCreate(service.preview(admin))), "final compare-and-swap rejects changed revision"); }
        finally { Db.exec("DROP TRIGGER synthetic_force_cas"); }
        noMutation(before, "final CAS failure rolls back users, receipt, people and injected revision change");
    }

    private static void exerciseLinksAndImport(OrganizationAccountImport service, OrganizationAccountImportSource.Config config, Path sources, String passwordHash) throws Exception {
        long existing = user("synthetic-explicit-existing", "SYNTHETIC EXISTING", "manager", 1, passwordHash);
        Map<String, Object> originalUser = Db.one("SELECT * FROM users WHERE id=?", existing);
        Map<String, Object> preview = service.preview(admin), request = allCreate(preview);
        Map<String, Object> account = service.account(admin, existing);
        check(Boolean.TRUE.equals(account.get("available")), "existing unbound account explicitly selectable");
        check(!account.containsKey("password"), "account proof lookup never returns password hash");
        for (Object badId : List.of("1", 1.5, 9007199254740992d)) {
            Map<String, Object> invalidId = copy(request); linkDecision(invalidId, 0, account); rows(invalidId.get("decisions")).get(0).put("accountId", badId);
            rejects(400, () -> service.commit(admin, invalidId), "existing account requires a positive safe JSON integer");
        }
        Map<String, Object> wrongProof = copy(request); linkDecision(wrongProof, 0, account); rows(wrongProof.get("decisions")).get(0).put("accountProof", "a".repeat(64));
        rejects(409, () -> service.commit(admin, wrongProof), "explicit account proof cannot be guessed");
        Map<String, Object> repeatAccount = copy(request); linkDecision(repeatAccount, 0, account); linkDecision(repeatAccount, 1, account);
        rejects(400, () -> service.commit(admin, repeatAccount), "one existing account cannot absorb two candidates");
        Db.exec("UPDATE users SET name='SYNTHETIC CHANGED' WHERE id=?", existing);
        Map<String, Object> stale = copy(request); linkDecision(stale, 0, account);
        rejects(409, () -> service.commit(admin, stale), "user change invalidates review or account proof");
        Db.exec("UPDATE users SET name=? WHERE id=?", originalUser.get("name"), existing);

        // Any published mapping, including a disabled one, occupies the account.
        Map<String, Object> beforeConfigChange = allCreate(service.preview(admin));
        OrganizationAccessStore.publish(admin, null, published("IMPORT-ACTIVE-BINDING", existing, true));
        rejects(409, () -> service.commit(admin, beforeConfigChange), "configuration change invalidates prior review even when users are unchanged");
        check(Boolean.FALSE.equals(service.account(admin, existing).get("available")), "active published binding blocks link");
        Map<String, Object> activeBinding = allCreate(service.preview(admin)); linkDecision(activeBinding, 0, account);
        rejects(409, () -> service.commit(admin, activeBinding), "active binding cannot be overwritten");
        OrganizationAccessStore.publish(admin, "IMPORT-ACTIVE-BINDING", published("IMPORT-DISABLED-BINDING", existing, false));
        check(Boolean.FALSE.equals(service.account(admin, existing).get("available")), "disabled published binding still occupies account");
        Map<String, Object> disabledBinding = allCreate(service.preview(admin)); linkDecision(disabledBinding, 0, account);
        rejects(409, () -> service.commit(admin, disabledBinding), "disabled binding cannot be overwritten");
        // The isolated test deliberately removes its own temporary published fixture before import.
        Db.exec("DELETE FROM organization_access_config");
        preview = service.preview(admin); request = allCreate(preview); linkDecision(request, 0, service.account(admin, existing));
        long beforeUsers = Db.count("users");
        Map<String, Object> receipt = service.commit(admin, request);
        committedRequest = copy(request);
        check(Boolean.TRUE.equals(receipt.get("imported")) && Boolean.FALSE.equals(receipt.get("replayed")), "batch commits one receipt");
        check(Boolean.FALSE.equals(receipt.get("permissionsPublished")) && Boolean.FALSE.equals(receipt.get("accountsActivated")), "receipt explicitly denies publishing and activation");
        check(number(object(receipt.get("summary")).get("createdPending")) == 6 && number(object(receipt.get("summary")).get("linkedExisting")) == 1, "explicit link plus six pending accounts");
        check(Db.count("users") == beforeUsers + 6 && Db.count("organization_account_import_people") == 7, "all candidates imported exactly once");
        check(Json.write(originalUser).equals(Json.write(Db.one("SELECT * FROM users WHERE id=?", existing))), "linked existing user's credentials, status and role remain unchanged");
        check(OrganizationAccessStore.configuration() == null, "import does not publish business configuration");
        check(OrganizationAccountImport.hasImportedAccount(existing), "host deletion guard sees imported accounts");
        try { Db.exec("DELETE FROM users WHERE id=?", existing); throw new AssertionError("Expected imported account FK protection"); }
        catch (SQLException expected) { check(true, "foreign key protects imported account from old delete API"); }
        int combinedCount = 0;
        for (Map<String, Object> stored : Db.query("SELECT payload FROM organization_account_import_people")) {
            Map<String, Object> payload = object(Json.parse(string(stored.get("payload"))));
            check(Boolean.FALSE.equals(payload.get("authorizationPublished")) && Boolean.FALSE.equals(payload.get("bindingPublished")), "stored preparation is not a published grant or binding");
            Map<String, Object> scopes = object(payload.get("preparedOrganizationsByRole"));
            Map<String, Object> preparation = object(payload.get("preparation"));
            for (Map<String, Object> role : rows(preparation.get("approvalRoles"))) {
                check(new HashSet<>((List<?>) scopes.get(string(role.get("kind")))).equals(new HashSet<>((List<?>) role.get("proposedBranches"))), "each duty retains only its own prepared branch set");
            }
            if (Boolean.TRUE.equals(preparation.get("combinedDutiesRequiresWorkflowReview"))) {
                combinedCount++;
                check(!rows(payload.get("combinedApprovalsPrepared")).isEmpty(), "combined duty evidence survives intake");
                check(rows(payload.get("combinedApprovalsPrepared")).stream().allMatch(row -> Boolean.TRUE.equals(row.get("workflowReviewRequired"))), "combined duty still needs workflow review");
            }
        }
        check(combinedCount == 1, "same BP and branch manager remain one person with separate duties");
        Set<String> personCodes = new HashSet<>(); Set<Long> accountIds = new HashSet<>();
        for (Map<String, Object> row : rows(receipt.get("rows"))) {
            long id = number(row.get("accountId")); String personCode = string(row.get("personCode"));
            check(personCodes.add(personCode) && accountIds.add(id), "same names and missing IDs still have distinct identities");
            check(!personCode.equals(row.get("reference")), "source reference is not assigned as formal person code");
            Map<String, Object> user = Db.one("SELECT * FROM users WHERE id=?", id);
            if (id == existing) continue;
            check(number(user.get("status")) == 0 && user.get("password") == null && "viewer".equals(user.get("role")), "new account is inactive without password and with minimal legacy role");
            check(Auth.login(string(user.get("username")), PASSWORD) == null, "pending account cannot log in");
        }
        String beforeReplay = databaseState();
        Map<String, Object> replay = service.commit(admin, request);
        check(Boolean.TRUE.equals(replay.get("replayed")), "identical request is idempotent replay");
        check(Json.write(copy(receipt).get("rows")).equals(Json.write(replay.get("rows"))), "replay retains stable account and person IDs");
        noMutation(beforeReplay, "replay does not rebuild or change accounts");
        Map<String, Object> pending = rows(receipt.get("rows")).stream().filter(row -> number(row.get("accountId")) != existing).findFirst().orElseThrow();
        Db.exec("UPDATE users SET status=1 WHERE id=?", pending.get("accountId"));
        String changedState = databaseState();
        check(Boolean.TRUE.equals(service.commit(admin, request).get("replayed")), "identical receipt replay survives later account state change");
        noMutation(changedState, "receipt replay never resets later account state");
        Map<String, Object> changedDecisions = copy(request); rows(changedDecisions.get("decisions")).get(0).put("action", "CREATE_PENDING");
        rows(changedDecisions.get("decisions")).get(0).put("accountId", null); rows(changedDecisions.get("decisions")).get(0).put("accountProof", null);
        rejects(409, () -> service.commit(admin, changedDecisions), "different decisions cannot reuse imported batch");
        OrganizationAccountImport other = new OrganizationAccountImport(otherBatch(config, "SYNTHETIC-OTHER-IMPORT"));
        check(Boolean.FALSE.equals(other.account(admin, existing).get("available")), "previous import reserves linked account");
        rejects(409, () -> other.preview(admin), "different batch key cannot clone identical source namespace");
        OrganizationAccountImportSource.Config otherSource = OrganizationAccountImportSourceTest.fixture(sources.resolve("other-link"));
        OrganizationAccountImport changedSourceSameBatch = new OrganizationAccountImport(otherBatch(otherSource, config.batchKey()));
        rejects(409, () -> changedSourceSameBatch.preview(admin), "same batch key with different trusted source refuses replacement");
        OrganizationAccountImport distinct = new OrganizationAccountImport(otherBatch(otherSource, "SYNTHETIC-DISTINCT-IMPORT"));
        Map<String, Object> occupied = allCreate(distinct.preview(admin));
        linkDecision(occupied, 0, map("accountId", existing, "proof", "a".repeat(64)));
        rejects(409, () -> distinct.commit(admin, occupied), "another batch cannot occupy imported account");
        check(number(service.preview(admin).get("revision")) == number(receipt.get("revision")), "revision reflects committed import");
    }

    private static void exerciseConcurrency(OrganizationAccountImportSource.Config config, Path sourceRoot) throws Exception {
        OrganizationAccountImportSource.Config a = OrganizationAccountImportSourceTest.fixture(sourceRoot.resolve("concurrent-a"));
        OrganizationAccountImportSource.Config b = OrganizationAccountImportSourceTest.fixture(sourceRoot.resolve("concurrent-b"));
        OrganizationAccountImport first = new OrganizationAccountImport(otherBatch(a, "SYNTHETIC-CONCURRENT-A"));
        OrganizationAccountImport second = new OrganizationAccountImport(otherBatch(b, "SYNTHETIC-CONCURRENT-B"));
        Map<String, Object> firstRequest = allCreate(first.preview(admin)), secondRequest = allCreate(second.preview(admin));
        check(Objects.equals(firstRequest.get("expectedRevision"), secondRequest.get("expectedRevision")), "two previews share same global revision");
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long beforeUsers = Db.count("users");
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int which = i;
                results.add(pool.submit(() -> { ready.countDown(); start.await(); try {
                    (which == 0 ? first : second).commit(admin, which == 0 ? firstRequest : secondRequest); return 200;
                } catch (Api.ApiException error) { return error.code; } }));
            }
            check(ready.await(5, TimeUnit.SECONDS), "concurrent workers reach barrier"); start.countDown();
            List<Integer> statuses = List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS));
            check(statuses.contains(200) && statuses.contains(409), "global CAS allows exactly one concurrent import");
            check(Db.count("users") == beforeUsers + 7, "losing CAS creates no partial users");
        } finally { start.countDown(); pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS); }
    }

    private static void exerciseSourceFreshness(Path sourceRoot) throws Exception {
        OrganizationAccountImportSource.Config source = OrganizationAccountImportSourceTest.fixture(sourceRoot.resolve("freshness"));
        OrganizationAccountImport service = new OrganizationAccountImport(otherBatch(source, "SYNTHETIC-SOURCE-FRESHNESS"));
        Map<String, Object> body = allCreate(service.preview(admin));
        Path path = source.candidates().path(); byte[] original = Files.readAllBytes(path);
        String before = databaseState();
        try {
            Files.write(path, "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            rejects(409, () -> service.commit(admin, body), "source change after review is rejected at commit");
            rejects(409, () -> service.preview(admin), "changed source cannot be silently used in a new preview");
            noMutation(before, "source mismatch cannot partially create accounts");
        } finally { Files.write(path, original); }
        check(rows(service.preview(admin).get("rows")).size() == 7, "exact original bytes restore valid preparation");
    }

    private static void exerciseDifferentRoleScopes(Path sourceRoot) throws Exception {
        // Add another explicit management decision to the synthetic BP whose region has two branches.
        // It must keep BP={A,B}, BRANCH_RESPONSIBLE={A}, rather than taking the union for both duties.
        OrganizationAccountImportSourceTest.Fixture fixture = new OrganizationAccountImportSourceTest.Fixture(sourceRoot.resolve("different-role-scopes"));
        Map<String, Object> candidate = rows(fixture.batch.get("candidates")).stream().filter(row -> "teacher-row-7".equals(row.get("candidate_reference"))).findFirst().orElseThrow();
        Map<String, Object> bp = rows(fixture.roles.get("bp_approvers")).get(1);
        rows(fixture.auth.get("branchManagementAssignments")).add(map("reference", "teacher-row-7", "organization", "SYNTHETIC BRANCH A", "decisionIndex", 1));
        rows(fixture.scope.get("branch_management_overrides")).add(map("organization", "SYNTHETIC BRANCH A", "manager_name", candidate.get("name"), "source_staff_id", bp.get("staff_id")));
        Map<String, Object> existingCombined = rows(fixture.preview.get("rows")).stream().filter(row -> "teacher-row-6".equals(row.get("reference"))).findFirst().orElseThrow();
        Map<String, Object> additional = copy(rows(existingCombined.get("approvalRoles")).get(1));
        additional.put("source", map("reference", "branch_management_overrides[1]"));
        additional.put("sourceIdentityCheck", "SOURCE_ID_PENDING_CANDIDATE_RETAINED");
        additional.put("proposedBranches", List.of("SYNTHETIC BRANCH A"));
        Map<String, Object> row = rows(fixture.preview.get("rows")).stream().filter(value -> "teacher-row-7".equals(value.get("reference"))).findFirst().orElseThrow();
        rows(row.get("approvalRoles")).add(additional); row.put("combinedDutiesRequiresWorkflowReview", true);
        Map<String, Object> summary = object(fixture.preview.get("summary"));
        summary.put("branchRolePeople", 6); summary.put("preparedRoleAssignments", 8); summary.put("peopleWithCombinedDuties", 2);
        Map<String, Object> coverage = rows(fixture.preview.get("branchCoverage")).stream().filter(value -> "SYNTHETIC BRANCH A".equals(value.get("organization"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") List<Object> refs = (List<Object>) coverage.get("candidateReferences"); refs.add("teacher-row-7");
        OrganizationAccountImport service = new OrganizationAccountImport(otherBatch(fixture.save(), "SYNTHETIC-DIFFERENT-ROLE-SCOPES"));
        Map<String, Object> receipt = service.commit(admin, allCreate(service.preview(admin)));
        Map<String, Object> imported = rows(receipt.get("rows")).stream().filter(value -> "teacher-row-7".equals(value.get("reference"))).findFirst().orElseThrow();
        Map<String, Object> payload = object(Json.parse(string(Db.one("SELECT payload FROM organization_account_import_people WHERE person_code=?", imported.get("personCode")).get("payload"))));
        Map<String, Object> scopes = object(payload.get("preparedOrganizationsByRole"));
        check(new HashSet<>((List<?>) scopes.get("BP")).equals(Set.of("SYNTHETIC BRANCH A", "SYNTHETIC BRANCH B")), "BP retains its confirmed two-branch region");
        check(new HashSet<>((List<?>) scopes.get("BRANCH_RESPONSIBLE")).equals(Set.of("SYNTHETIC BRANCH A")), "branch responsibility does not inherit BP's other branch");
        check(rows(payload.get("combinedApprovalsPrepared")).size() == 1 && "SYNTHETIC BRANCH A".equals(rows(payload.get("combinedApprovalsPrepared")).get(0).get("organizationDisplayName")), "combined approval preparation covers only the common branch");
        check(OrganizationAccessStore.configuration() == null, "separate prepared scopes still publish no permission");
    }

    private static Object apiPrivate(String name, HttpExchange exchange) throws Exception {
        Method method = Api.class.getDeclaredMethod(name, HttpExchange.class); method.setAccessible(true);
        try { return method.invoke(null, exchange); }
        catch (InvocationTargetException error) { if (error.getCause() instanceof Exception cause) throw cause; throw error; }
    }
    private static void exerciseHttp(OrganizationAccountImport service) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                if ("POST".equals(exchange.getRequestMethod())) {
                    apiPrivate("requireJsonContentType", exchange);
                    exchange.setAttribute("com.training.Api.body", apiPrivate("readBodyLimited", exchange));
                }
                synchronized (Api.MUTATION_LOCK) {
                    Auth.Session session = Auth.get(Api.token(exchange));
                    if (!service.handle(exchange, session)) Api.err(exchange, 404, "Test route not found");
                }
            } catch (Api.ApiException error) { Api.err(exchange, error.code, error.getMessage()); }
            catch (Exception error) { Api.err(exchange, 500, "Synthetic test handler failure"); }
            try { apiPrivate("flushResponse", exchange); } catch (Exception error) { exchange.close(); }
        });
        server.start(); int port = server.getAddress().getPort();
        check(port != 63882, "test server does not use production port");
        try {
            check(http(port, "GET", PREFIX + "/preview", null, null, null).status == 401, "HTTP anonymous preview denied");
            check(http(port, "GET", PREFIX + "/preview", viewerToken, null, null).status == 403, "HTTP viewer preview denied");
            HttpResult preview = http(port, "GET", PREFIX + "/preview", adminToken, null, null);
            check(preview.status == 200 && number(preview.body.get("code")) == 0, "HTTP preview uses host envelope");
            check(!Json.write(preview.body).contains("pbkdf2$") && !Json.write(preview.body).contains(adminToken), "HTTP responses do not expose credentials");
            check(http(port, "GET", PREFIX + "/preview?accountId=1", adminToken, null, null).status == 400, "HTTP identity query cannot change caller");
            check(http(port, "GET", PREFIX + "/accounts/" + viewerId, adminToken, null, null).status == 200, "HTTP explicit account lookup succeeds");
            check(http(port, "GET", PREFIX + "/accounts/0", adminToken, null, null).status == 404, "HTTP invalid account id rejected");
            check(http(port, "POST", PREFIX + "/preview", adminToken, "{}", "application/json").status == 405, "HTTP preview method enforced");
            check(http(port, "POST", PREFIX + "/commit", viewerToken, Json.write(committedRequest), "application/json").status == 403, "HTTP viewer cannot use an admin's complete reviewed request");
            HttpResult replay = http(port, "POST", PREFIX + "/commit", adminToken, Json.write(committedRequest), "application/json");
            check(replay.status == 200 && Boolean.TRUE.equals(object(replay.body.get("data")).get("replayed")), "HTTP actual POST parses complete request and replays receipt");
            check(http(port, "POST", PREFIX + "/commit", adminToken, "{}", "text/plain").status == 415, "host JSON content type enforced");
            check(http(port, "POST", PREFIX + "/commit", adminToken, "INVALID", "application/json").status == 400, "host malformed JSON rejected");
            check(http(port, "POST", PREFIX + "/commit", adminToken, "x".repeat(1024 * 1024 + 1), "application/json").status == 413, "host bounded body enforced");
            check(http(port, "GET", "/outside-import", adminToken, null, null).status == 404, "adapter leaves unrelated route unhandled");
            String temporaryToken = Auth.login("synthetic-import-admin", PASSWORD); Auth.logout(temporaryToken);
            check(http(port, "GET", PREFIX + "/preview", temporaryToken, null, null).status == 401, "HTTP logout revokes actual token");
        } finally { server.stop(0); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }
    private record HttpResult(int status, Map<String, Object> body) {}
    private static HttpResult http(int port, String method, String path, String token, String body, String contentType) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(10000); connection.setRequestMethod(method);
        if (token != null) connection.setRequestProperty("Cookie", "yx_session=" + token);
        if (body != null) { connection.setDoOutput(true); connection.setRequestProperty("Content-Type", contentType);
            try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); } }
        try { int status = connection.getResponseCode(); InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            try (input) { return new HttpResult(status, object(Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)))); }
        } finally { connection.disconnect(); }
    }
    public static final class FailSecondPending implements Trigger {
        static int count;
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            if (newRow[2] == null && newRow[5] instanceof Number status && status.intValue() == 0 && ++count == 2)
                throw new SQLException("SYNTHETIC_SECOND_PENDING_INSERT_FAILURE");
        }
    }
    public static final class ForceCasFailure implements Trigger {
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE organization_account_import_state SET revision=revision+10 WHERE singleton=1");
            }
        }
    }
}
