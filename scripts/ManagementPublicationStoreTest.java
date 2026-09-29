package com.training;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.training.OrganizationAccess.*;

/** Real Auth sessions and a fresh three-table H2 database; no production bootstrap or data access. */
public final class ManagementPublicationStoreTest {
    private static final String PASSWORD = "SYNTHETIC-STORE-TRANSACTION-ONLY-20260924";
    private static int checks;
    private static long adminId, affectedId, unchangedId, extraId, outsiderId;
    private static Login admin, affected, unchanged, extra, outsider;
    private static Connection observer;
    private static Configuration baseline;
    private record Login(String token, Auth.Session session) {}
    @FunctionalInterface private interface Work { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Fresh synthetic root is required");
        Path root = Path.of(args[0]).toRealPath();
        if (!root.getFileName().toString().startsWith("yanxu-management-publication-store."))
            throw new IllegalArgumentException("Synthetic root prefix is required");
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) throw new IllegalArgumentException("Fresh data directory is required");
        try (var entries = Files.list(data)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Data directory must be empty");
        }
        System.setProperty("data.dir", data.toString());
        System.setProperty("login.email.mode", "legacy");
        System.setProperty("bootstrap.demo", "false");
        try {
            setup();
            compatibility();
            callbackFailures();
            insertionFailure();
            insertionError();
            validationBeforeCallback();
            externalTransactionRejected();
            successfulAtomicPublication();
            check(Db.get().getAutoCommit(), "autoCommit restored after all scenarios");
            System.out.println("PASS ManagementPublicationStoreTest: " + checks + " checks; real Auth.login; synthetic users/config/receipt only.");
        } finally {
            if (observer != null) observer.close();
            Db.get().close();
        }
    }

    private static void setup() throws Exception {
        Db.exec("CREATE TABLE users(id IDENTITY PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE publication_receipt(id VARCHAR(64) PRIMARY KEY,version VARCHAR(128),actor_id BIGINT)");
        OrganizationAccessStore.init();
        String passwordHash = Auth.hash(PASSWORD);
        adminId = user("synthetic-admin", "admin", passwordHash);
        affectedId = user("synthetic-affected", "viewer", passwordHash);
        unchangedId = user("synthetic-unchanged", "viewer", passwordHash);
        extraId = user("synthetic-extra", "viewer", passwordHash);
        outsiderId = user("synthetic-outsider", "viewer", passwordHash);
        admin = login("synthetic-admin"); affected = login("synthetic-affected");
        unchanged = login("synthetic-unchanged"); extra = login("synthetic-extra"); outsider = login("synthetic-outsider");
        observer = DriverManager.getConnection(Db.get().getMetaData().getURL(), "sa", "");
        check(observer != Db.get(), "independent connection observes only committed state");
    }

    private static void compatibility() throws Exception {
        Configuration initial = config("legacy-v1", true);
        check(initial.equals(OrganizationAccessStore.publish(admin.session(), null, initial)), "legacy publish returns exact normalized configuration");
        check(initial.equals(OrganizationAccessStore.configuration()), "legacy publication persists configuration");
        check(Auth.current(affected.session()) == null && Auth.current(unchanged.session()) == null, "legacy first publication revokes mapped sessions");
        alive(admin, extra, outsider);
        affected = login("synthetic-affected"); unchanged = login("synthetic-unchanged");
        baseline = config("legacy-v2", true);
        OrganizationAccessStore.publish(admin.session(), initial.version(), baseline);
        alive(admin, affected, unchanged, extra, outsider);
        check(count("organization_access_config") == 2 && count("publication_receipt") == 0, "legacy publication creates only configuration rows");
        check("legacy-v1".equals(Db.one("SELECT previous_version FROM organization_access_config WHERE active_slot=1").get("previous_version")), "legacy previous version recorded");
        System.out.println("PASS original publish compatibility and version-only session preservation");
    }

    private static void callbackFailures() throws Exception {
        for (Throwable sentinel : List.of(new SQLException("synthetic callback SQL failure"),
                new IllegalStateException("synthetic callback runtime failure"), new AssertionError("synthetic callback Error"))) {
            int[] calls = {0};
            Throwable actual = failure(() -> OrganizationAccessStore.publishWithMutation(admin.session(), baseline.version(), config("callback-failure", false),
                    (actor, previous, candidate) -> {
                        calls[0]++;
                        pendingWrites(actor, candidate, "callback-failure", true);
                        if (sentinel instanceof Error error) throw error;
                        throw (Exception) sentinel;
                    }));
            check(actual == sentinel, "callback failure is rethrown as its original object");
            check(calls[0] == 1, "failing callback runs exactly once");
            rolledBack("callback-failure");
        }
        for (Set<Long> returned : java.util.Arrays.<Set<Long>>asList(null, Set.of(0L), java.util.Collections.singleton(null))) {
            Throwable actual = failure(() -> OrganizationAccessStore.publishWithMutation(admin.session(), baseline.version(), config("invalid-return", false),
                    (actor, previous, candidate) -> {
                        pendingWrites(actor, candidate, "invalid-return", true);
                        return returned;
                    }));
            check(actual instanceof NullPointerException || actual instanceof IllegalArgumentException, "invalid callback revocation set rejected");
            rolledBack("invalid-return");
        }
        System.out.println("PASS callback SQLException, RuntimeException, Error and invalid result roll back all writes without revoking sessions");
    }

    private static void insertionFailure() throws Exception {
        // This DDL occurs before publication; it cannot commit the callback transaction.
        Db.exec("ALTER TABLE organization_access_config ADD CONSTRAINT synthetic_insert_failure CHECK(version <> 'insert-failure')");
        int[] calls = {0};
        Throwable actual = failure(() -> OrganizationAccessStore.publishWithMutation(admin.session(), baseline.version(), config("insert-failure", false),
                (actor, previous, candidate) -> {
                    calls[0]++;
                    pendingWrites(actor, candidate, "insert-failure", true);
                    return Set.of(extraId);
                }));
        check(actual instanceof SQLException && "23513".equals(((SQLException) actual).getSQLState()), "real H2 check constraint rejects configuration INSERT");
        check(calls[0] == 1, "constraint failure occurs after callback");
        rolledBack("insert-failure");
        System.out.println("PASS later configuration INSERT failure restores roles, receipt, configuration and old active slot");
    }

    private static void insertionError() throws Exception {
        Connection real = Db.get();
        Field connection = Db.class.getDeclaredField("conn");
        connection.setAccessible(true);
        AssertionError sentinel = new AssertionError("synthetic post-callback INSERT Error");
        int[] calls = {0}, insertAttempts = {0};
        // Fault injection only: all database operations except this INSERT delegate to real H2.
        Connection injected = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    Object result;
                    try { result = method.invoke(real, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                    if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql
                            && sql.startsWith("INSERT INTO organization_access_config(")) {
                        java.sql.PreparedStatement statement = (java.sql.PreparedStatement) result;
                        return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class},
                                (statementProxy, operation, values) -> {
                                    if (operation.getName().equals("executeUpdate")) { insertAttempts[0]++; throw sentinel; }
                                    try { return operation.invoke(statement, values); }
                                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                                });
                    }
                    return result;
                });
        Throwable actual;
        connection.set(null, injected);
        try {
            actual = failure(() -> OrganizationAccessStore.publishWithMutation(admin.session(), baseline.version(), config("insert-error", false),
                    (actor, previous, candidate) -> {
                        calls[0]++;
                        pendingWrites(actor, candidate, "insert-error", true);
                        return Set.of(extraId);
                    }));
        } finally { connection.set(null, real); }
        check(actual == sentinel && calls[0] == 1 && insertAttempts[0] == 1, "post-callback INSERT Error propagates original object after one callback");
        rolledBack("insert-error");
        System.out.println("PASS post-callback INSERT Error rolls back real H2 transaction and preserves real sessions");
    }

    private static void validationBeforeCallback() throws Exception {
        int[] calls = {0};
        OrganizationAccessStore.PublicationMutation callback = (actor, previous, candidate) -> { calls[0]++; return Set.of(); };
        reject(409, () -> OrganizationAccessStore.publishWithMutation(admin.session(), "obsolete-version", config("stale", false), callback));
        reject(409, () -> OrganizationAccessStore.publishWithMutation(admin.session(), baseline.version(), config("legacy-v1", false), callback));
        Configuration badBinding = new Configuration("missing-account", baseline.codeRules(), baseline.roleCodes(), baseline.organizations(),
                baseline.people(), baseline.relations(), List.of(new AccountBinding(999999, "P1", true), new AccountBinding(unchangedId, "P2", true)), baseline.grants());
        reject(400, () -> OrganizationAccessStore.publishWithMutation(admin.session(), baseline.version(), badBinding, callback));
        reject(403, () -> OrganizationAccessStore.publishWithMutation(outsider.session(), baseline.version(), config("not-admin", true), callback));
        Auth.Session forged = new Auth.Session(); forged.uid = adminId; forged.role = "admin";
        reject(401, () -> OrganizationAccessStore.publishWithMutation(forged, baseline.version(), config("forged", true), callback));
        check(calls[0] == 0, "CAS, reused version, bad binding and invalid authority all reject before callback");
        rolledBack("stale");
        System.out.println("PASS stale CAS, duplicate version, invalid binding, forged session and non-admin reject before callback");
    }

    private static void externalTransactionRejected() throws Exception {
        int[] calls = {0};
        Db.get().setAutoCommit(false);
        try {
            Db.exec("INSERT INTO publication_receipt(id,version,actor_id) VALUES('outer-marker','outer',?)", adminId);
            Throwable actual = failure(() -> OrganizationAccessStore.publishWithMutation(admin.session(), baseline.version(), config("nested", false),
                    (actor, previous, candidate) -> { calls[0]++; return Set.of(extraId); }));
            check(actual instanceof IllegalStateException, "external transaction rejected");
            check(calls[0] == 0 && !Db.get().getAutoCommit(), "nested rejection preserves outer transaction and skips callback");
            check(count("publication_receipt") == 1 && observedCount("publication_receipt") == 0, "outer marker remains pending and is not committed or rolled back by Store");
        } finally {
            Db.get().rollback();
            Db.get().setAutoCommit(true);
        }
        rolledBack("nested");
        System.out.println("PASS external transaction rejection preserves caller transaction ownership");
    }

    private static void successfulAtomicPublication() throws Exception {
        Login affectedSecond = login("synthetic-affected"), extraSecond = login("synthetic-extra");
        int[] calls = {0};
        Configuration candidate = config("atomic-success", false);
        Configuration published = OrganizationAccessStore.publishWithMutation(admin.session(), baseline.version(), candidate,
                (actor, previous, normalized) -> {
                    calls[0]++;
                    check(actor == admin.session() && previous.equals(baseline) && normalized.equals(candidate), "callback receives real actor and validated old/new configurations");
                    pendingWrites(actor, normalized, "atomic-success", false);
                    check(rawPresent(affectedSecond) && rawPresent(extraSecond), "every related session remains registered before commit");
                    return Set.of(extraId); // P1 is revoked by changedSessionAccounts; this unbound account is additional.
                });
        check(calls[0] == 1 && published.equals(candidate), "success invokes callback once and returns candidate");
        check(candidate.equals(OrganizationAccessStore.configuration()), "new configuration committed");
        check(count("organization_access_config") == 3 && observedCount("organization_access_config") == 3, "exactly one committed version added");
        check(count("publication_receipt") == 1 && observedCount("publication_receipt") == 1, "receipt committed with configuration");
        check("manager".equals(role(extraId)) && "manager".equals(observedRole(extraId)), "role committed on both connections");
        check("viewer".equals(role(affectedId)) && "viewer".equals(role(unchangedId)), "configuration-only affected and unrelated roles retained");
        Map<String,Object> receipt = Db.one("SELECT version,actor_id FROM publication_receipt WHERE id='atomic-success'");
        check(candidate.version().equals(receipt.get("version")) && ((Number) receipt.get("actor_id")).longValue() == adminId, "receipt binds exact version and actor");
        check("legacy-v2".equals(Db.one("SELECT previous_version FROM organization_access_config WHERE active_slot=1").get("previous_version")), "new active version references predecessor");
        check(Db.one("SELECT id FROM organization_access_config WHERE version='legacy-v2' AND active_slot IS NULL") != null, "old active slot cleared after success");
        check(Auth.current(affected.session()) == null && Auth.current(affectedSecond.session()) == null, "all sessions of configuration-affected account revoked");
        check(Auth.current(extra.session()) == null && Auth.current(extraSecond.session()) == null, "all sessions of additional role-change account revoked");
        alive(admin, unchanged, outsider);
        System.out.println("PASS atomic configuration/role/receipt commit; only affected and additional accounts lose all sessions after commit");
    }

    private static void pendingWrites(Auth.Session actor, Configuration candidate, String receipt, boolean changeAffected) throws Exception {
        check(!Db.get().getAutoCommit(), "callback runs inside transaction");
        check(OrganizationAccessStore.configuration().equals(baseline), "configuration SQL has not run when callback begins");
        Db.exec("UPDATE users SET role='manager' WHERE id=?", extraId);
        if (changeAffected) Db.exec("UPDATE users SET role='manager' WHERE id=?", affectedId);
        Db.exec("INSERT INTO publication_receipt(id,version,actor_id) VALUES(?,?,?)", receipt, candidate.version(), actor.uid);
        check(count("publication_receipt") == 1 && "manager".equals(role(extraId)), "callback writes visible inside transaction");
        check(observedCount("publication_receipt") == 0 && "viewer".equals(observedRole(extraId)), "callback writes invisible to independent connection before commit");
        check("legacy-v2".equals(observedActiveVersion()), "independent connection retains old configuration before commit");
        // Read-only observation of Auth's actual session registry; never create/insert/change mock sessions.
        check(rawPresent(admin) && rawPresent(affected) && rawPresent(unchanged) && rawPresent(extra) && rawPresent(outsider), "no session revoked while callback writes are pending");
    }

    private static void rolledBack(String candidateVersion) throws Exception {
        check(Db.get().getAutoCommit(), "autoCommit restored after failure");
        check(baseline.equals(OrganizationAccessStore.configuration()), "failure retains complete previous configuration");
        check(count("organization_access_config") == 2 && count("publication_receipt") == 0, "failure leaves no new configuration or receipt");
        check(Db.one("SELECT id FROM organization_access_config WHERE version=?", candidateVersion) == null, "failed candidate not persisted");
        check("viewer".equals(role(extraId)) && "viewer".equals(role(affectedId)), "failure restores both user roles");
        check("legacy-v2".equals(observedActiveVersion()) && observedCount("publication_receipt") == 0, "independent connection sees previous committed state");
        alive(admin, affected, unchanged, extra, outsider);
    }

    private static Configuration config(String version, boolean affectedEnabled) {
        RelationRule optional = new RelationRule(false, Set.of("R"), false, false);
        return new Configuration(version, new CodeRules("O[0-9]+", "P[0-9]+", "R"), Set.of("R"),
                List.of(new Organization("O1", null, true)),
                List.of(new Person("P1", "O1", Set.of(), null, null, Set.of("R"), true),
                        new Person("P2", "O1", Set.of(), null, null, Set.of("R"), true)),
                List.of(new RoleRelations("R", optional, optional)),
                List.of(new AccountBinding(affectedId, "P1", affectedEnabled), new AccountBinding(unchangedId, "P2", true)), List.of());
    }

    private static long user(String name, String role, String hash) throws Exception {
        return Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)", name, hash, "SYNTHETIC STORE USER", role);
    }
    private static Login login(String name) throws Exception {
        String token = Auth.login(name, PASSWORD);
        Auth.Session session = Auth.get(token);
        check(token != null && session != null, "real Auth.login creates live session");
        return new Login(token, session);
    }
    private static void alive(Login... logins) {
        for (Login login : logins) check(Auth.current(login.session()) == login.session(), "real prior session remains valid");
    }
    private static boolean rawPresent(Login login) throws Exception {
        Field registry = Auth.class.getDeclaredField("SESSIONS");
        registry.setAccessible(true);
        return ((Map<?,?>) registry.get(null)).get(login.token()) == login.session();
    }
    private static long count(String table) throws Exception { return Db.count(table); }
    private static String role(long id) throws Exception { return (String) Db.one("SELECT role FROM users WHERE id=?", id).get("role"); }
    private static long observedCount(String table) throws Exception { return Long.parseLong(observed("SELECT COUNT(*) FROM " + table)); }
    private static String observedRole(long id) throws Exception { return observed("SELECT role FROM users WHERE id=" + id); }
    private static String observedActiveVersion() throws Exception { return observed("SELECT version FROM organization_access_config WHERE active_slot=1"); }
    private static String observed(String sql) throws Exception {
        try (Statement statement = observer.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
    private static Throwable failure(Work work) {
        try { work.run(); } catch (Throwable failure) { return failure; }
        throw new AssertionError("operation must fail");
    }
    private static void reject(int code, Work work) {
        Throwable failure = failure(work);
        check(failure instanceof Api.ApiException && ((Api.ApiException) failure).code == code, "expected rejection status " + code);
    }
    private static void check(boolean passed, String label) {
        checks++;
        if (!passed) throw new AssertionError(label);
    }
}
