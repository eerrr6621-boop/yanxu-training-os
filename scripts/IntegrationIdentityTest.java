package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.training.OrganizationAccess.*;

/** Only synthetic users and a fresh caller-owned temporary H2 directory. */
public final class IntegrationIdentityTest {
    private static int checks;
    private static final String PASSWORD = "SYNTHETIC-IDENTITY-20260922";
    private static Auth.Session admin, employee, unbound;
    private static String adminToken, employeeToken;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void rejects(int status, Work work, String message) throws Exception {
        try { work.run(); throw new AssertionError("Expected rejection: " + message); }
        catch (Api.ApiException e) { check(e.code == status, message + " (status " + e.code + ")"); }
    }
    private static Configuration fixture(String version) {
        RelationRule optional = new RelationRule(false, Set.of("FILLER"), false, false);
        return new Configuration(version, new CodeRules("[0-9]{3}", "[0-9]{4}", "[A-Z]+"), Set.of("FILLER"),
                List.of(new Organization("001", null, true), new Organization("002", null, true)),
                List.of(new Person("0001", "001", Set.of(), null, null, Set.of("FILLER"), true),
                        new Person("0002", "002", Set.of(), null, null, Set.of("FILLER"), true)),
                List.of(new RoleRelations("FILLER", optional, optional)), List.of(new AccountBinding(2, "0001", true)),
                List.of(new Grant("READ", "FILLER", "demand.read", Action.VIEW, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("WRITE", "FILLER", "demand.write", Action.HANDLE, Effect.ALLOW, Scope.OWN_ORG, Set.of())));
    }
    private static Configuration bindings(String version, List<AccountBinding> bindings) {
        Configuration c = fixture(version);
        return new Configuration(c.version(), c.codeRules(), c.roleCodes(), c.organizations(), c.people(), c.relations(), bindings, c.grants());
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> configMap(Configuration c) {
        return (Map<String, Object>) Json.parse(Json.write(OrganizationAccessStore.toMap(c)));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Fresh temporary data directory required");
        Path data = Path.of(args[0]).toAbsolutePath();
        if (!Files.isDirectory(data) || Files.list(data).findAny().isPresent()) throw new IllegalArgumentException("Data directory must be new and empty");
        System.setProperty("data.dir", data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        String hash = Auth.hash(PASSWORD);
        Db.exec("INSERT INTO users VALUES(1,'synthetic-admin',?,'TEST ADMIN','admin',1)", hash);
        Db.exec("INSERT INTO users VALUES(2,'synthetic-employee',?,'TEST EMPLOYEE','viewer',1)", hash);
        Db.exec("INSERT INTO users VALUES(3,'synthetic-unbound',?,'TEST UNBOUND','manager',1)", hash);
        Db.exec("INSERT INTO users VALUES(4,'synthetic-disabled',?,'TEST DISABLED','viewer',0)", hash);
        adminToken = Auth.login("synthetic-admin", PASSWORD); admin = Auth.get(adminToken);
        employeeToken = Auth.login("synthetic-employee", PASSWORD); employee = Auth.get(employeeToken);
        unbound = Auth.get(Auth.login("synthetic-unbound", PASSWORD));
        check(admin != null && employee != null && unbound != null, "real Auth sessions");
        OrganizationAccessStore.init(); OrganizationAccessStore.init();
        check(OrganizationAccessStore.configuration() == null, "init does not seed grants");
        rejects(403, () -> OrganizationAccessStore.person(employee), "missing configuration");
        check(!OrganizationAccessStore.authorize(employee, "demand.write", Action.HANDLE, "001").allowed(), "no config fails closed");
        OrganizationAccessStore.publish(admin, null, fixture("test-v1"));
        check(Auth.current(employee) == null, "initial binding revokes old employee session");
        employeeToken = Auth.login("synthetic-employee", PASSWORD); employee = Auth.get(employeeToken);
        check("test-v1".equals(OrganizationAccessStore.configuration().version()), "published version reload");
        check(Db.count("organization_access_config") == 1, "one history row");
        check("0001".equals(OrganizationAccessStore.person(employee).personCode()), "leading-zero person code");
        check("001".equals(OrganizationAccessStore.person(employee).organizationCode()), "leading-zero organization code");
        check(OrganizationAccessStore.authorize(employee, "demand.write", Action.HANDLE, "001").allowed(), "explicit grant works for old viewer role");
        check(!OrganizationAccessStore.authorize(employee, "demand.write", Action.HANDLE, "002").allowed(), "cross organization denied");
        check(!OrganizationAccessStore.authorize(unbound, "demand.write", Action.HANDLE, "001").allowed(), "old manager is not implicit role binding");
        check(!OrganizationAccessStore.authorize(admin, "demand.write", Action.HANDLE, "001").allowed(), "old admin is not business grant");
        rejects(403, () -> OrganizationAccessStore.person(unbound), "unbound person rejected");
        rejects(403, () -> OrganizationAccessStore.publish(employee, "test-v1", fixture("not-published")), "non-admin cannot publish");
        rejects(409, () -> OrganizationAccessStore.publish(admin, null, fixture("wrong-expected")), "stale first-create rejected");
        rejects(409, () -> OrganizationAccessStore.publish(admin, "test-v1", fixture("test-v1")), "version cannot be reused");
        rejects(400, () -> OrganizationAccessStore.publish(admin, "test-v1", bindings("missing-user", List.of(new AccountBinding(999, "0001", true)))), "missing bound account rejected");
        rejects(400, () -> OrganizationAccessStore.publish(admin, "test-v1", bindings("disabled-user", List.of(new AccountBinding(4, "0001", true)))), "enabled binding needs active user");
        Map<String, Object> invalid = configMap(fixture("invalid")); invalid.put("unknown", true);
        rejects(400, () -> OrganizationAccessStore.parseConfiguration(invalid), "unknown config field rejected");
        Map<String, Object> invalidBool = configMap(fixture("invalid-bool"));
        ((Map<String, Object>) ((List<?>) invalidBool.get("organizations")).get(0)).put("enabled", "true");
        rejects(400, () -> OrganizationAccessStore.parseConfiguration(invalidBool), "boolean strings rejected");
        Map<String, Object> invalidId = configMap(fixture("invalid-id"));
        Map<String, Object> bindingRow = (Map<String, Object>) ((List<?>) invalidId.get("accountBindings")).get(0);
        bindingRow.put("accountId", 2.5);
        rejects(400, () -> OrganizationAccessStore.parseConfiguration(invalidId), "fractional account id rejected");
        bindingRow.put("accountId", 9007199254740992d);
        rejects(400, () -> OrganizationAccessStore.parseConfiguration(invalidId), "unsafe JSON integer rejected");
        bindingRow.put("accountId", "2");
        rejects(400, () -> OrganizationAccessStore.parseConfiguration(invalidId), "account id is not a person code string");
        Map<String, Object> invalidRole = configMap(fixture("invalid-role")); invalidRole.put("roleCodes", List.of("FILLER", "FILLER"));
        rejects(400, () -> OrganizationAccessStore.parseConfiguration(invalidRole), "duplicate set value rejected");
        Configuration c = fixture("bad-core");
        Configuration badCore = new Configuration(c.version(), c.codeRules(), c.roleCodes(), c.organizations(), c.people(), c.relations(),
                List.of(new AccountBinding(2, "9999", true)), c.grants());
        rejects(400, () -> OrganizationAccessStore.publish(admin, "test-v1", badCore), "full core validation prevents dangling binding");
        check("test-v1".equals(OrganizationAccessStore.configuration().version()) && Db.count("organization_access_config") == 1, "all invalid candidates leave active and history intact");
        OrganizationAccessStore.publish(admin, "test-v1", fixture("test-v2"));
        check(Db.count("organization_access_config") == 2, "history retained");
        check("test-v1".equals(Db.one("SELECT previous_version FROM organization_access_config WHERE active_slot=1").get("previous_version")), "history lineage persisted");
        check(Db.query("SELECT id FROM organization_access_config WHERE active_slot=1").size() == 1, "exactly one active version");
        rejects(409, () -> OrganizationAccessStore.publish(admin, "test-v1", fixture("test-v3-stale")), "stale update denied");
        // A transaction failure after deactivating the head must restore it and leave no phantom history.
        Db.exec("ALTER TABLE organization_access_config ADD CONSTRAINT identity_test_reject CHECK(version<>'forced-failure')");
        try { OrganizationAccessStore.publish(admin, "test-v2", fixture("forced-failure")); throw new AssertionError("must fail"); }
        catch (java.sql.SQLException expected) { check(true, "database failure propagated"); }
        check("test-v2".equals(OrganizationAccessStore.configuration().version()) && Db.count("organization_access_config") == 2, "failed insert rolled back head and history");
        Db.exec("ALTER TABLE organization_access_config DROP CONSTRAINT identity_test_reject");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (String v : List.of("concurrent-a", "concurrent-b")) results.add(pool.submit(() -> {
            go.await();
            try { OrganizationAccessStore.publish(admin, "test-v2", fixture(v)); return true; }
            catch (Api.ApiException e) { if (e.code != 409) throw e; return false; }
        }));
        go.countDown(); int wins = 0;
        for (Future<Boolean> r : results) if (r.get()) wins++;
        pool.shutdown(); check(wins == 1 && Db.count("organization_access_config") == 3, "concurrent CAS has exactly one winner");
        String currentVersion = OrganizationAccessStore.configuration().version();
        Exchange me = new Exchange("GET", "/api/organization/me", employeeToken, null);
        check(OrganizationAccessStore.handle(me, employee), "me recognized");
        Map<String, Object> meData = (Map<String, Object>) response(me).get("data");
        check("BOUND".equals(meData.get("status")), "me bound status");
        check(!Json.write(meData).contains("0002") && !Json.write(meData).contains("accountBindings") && !Json.write(meData).contains("TEST ADMIN"), "me does not leak roster or account list");
        check(((List<?>) meData.get("organizations")).size() == 1, "me minimal org options");
        Exchange meUnbound = new Exchange("GET", "/api/organization/me", null, null);
        rejects(401, () -> OrganizationAccessStore.handle(meUnbound, employee), "session argument cannot bypass request token");
        rejects(403, () -> OrganizationAccessStore.handle(new Exchange("GET", "/api/organization/config", employeeToken, null), employee), "config GET admin only");
        rejects(405, () -> OrganizationAccessStore.handle(new Exchange("DELETE", "/api/organization/config", adminToken, null), admin), "method restriction");
        rejects(400, () -> OrganizationAccessStore.handle(new Exchange("GET", "/api/organization/me?accountId=1", employeeToken, null), employee), "me rejects identity query");
        Map<String, Object> request = new LinkedHashMap<>(); request.put("expectedVersion", currentVersion); request.put("configuration", configMap(fixture("http-v4")));
        Exchange post = new Exchange("POST", "/api/organization/config", adminToken, request);
        check(OrganizationAccessStore.handle(post, admin), "POST route saves config");
        check("http-v4".equals(((Map<?, ?>) response(post).get("data")).get("version")), "POST response uses camelCase envelope");
        Map<String, Object> injected = new LinkedHashMap<>(request); injected.put("uid", 1);
        rejects(400, () -> OrganizationAccessStore.handle(new Exchange("POST", "/api/organization/config", adminToken, injected), admin), "request uid rejected");
        Exchange getConfig = new Exchange("GET", "/api/organization/config", adminToken, null);
        OrganizationAccessStore.handle(getConfig, admin);
        check(((Map<?, ?>) response(getConfig).get("data")).get("configuration") instanceof Map, "GET config serializes JSON object, not record toString");
        Auth.Session forged = new Auth.Session(); forged.uid = 2; forged.role = "admin";
        rejects(401, () -> OrganizationAccessStore.person(forged), "forged Session uid denied");
        check(!OrganizationAccessStore.authorize(forged, "demand.write", Action.HANDLE, "001").allowed(), "forged Session authorize denied");
        Db.exec("UPDATE users SET status=0 WHERE id=2");
        check(!OrganizationAccessStore.authorize(employee, "demand.write", Action.HANDLE, "001").allowed(), "user disable immediately denies cached Session");
        rejects(401, () -> OrganizationAccessStore.person(employee), "disabled user person denied");
        Db.exec("UPDATE users SET status=1 WHERE id=2");
        check(!OrganizationAccessStore.authorize(employee, "demand.write", Action.HANDLE, "001").allowed(), "re-enable does not revive revoked Session");
        employeeToken = Auth.login("synthetic-employee", PASSWORD); employee = Auth.get(employeeToken);
        OrganizationAccessStore.publish(admin, "http-v4", bindings("disabled-binding", List.of(new AccountBinding(2, "0001", false))));
        check(Auth.current(employee) == null, "published identity change revokes old session");
        employeeToken = Auth.login("synthetic-employee", PASSWORD); employee = Auth.get(employeeToken);
        rejects(403, () -> OrganizationAccessStore.person(employee), "disabled binding immediately denies person");
        check(!OrganizationAccessStore.authorize(employee, "demand.write", Action.HANDLE, "001").allowed(), "disabled binding authorize denied");
        Configuration enabledFixture = fixture("disabled-person");
        Person old = enabledFixture.people().get(0);
        Person disabled = new Person(old.personCode(), old.organizationCode(), old.responsibleOrganizationCodes(), null, null, old.roleCodes(), false);
        Configuration disabledPerson = new Configuration(enabledFixture.version(), enabledFixture.codeRules(), enabledFixture.roleCodes(), enabledFixture.organizations(),
                List.of(disabled, enabledFixture.people().get(1)), enabledFixture.relations(), enabledFixture.accountBindings(), enabledFixture.grants());
        OrganizationAccessStore.publish(admin, "disabled-binding", disabledPerson);
        check(Auth.current(employee) == null, "published identity change revokes old session");
        employeeToken = Auth.login("synthetic-employee", PASSWORD); employee = Auth.get(employeeToken);
        rejects(403, () -> OrganizationAccessStore.person(employee), "disabled person immediately denied");
        OrganizationAccessStore.publish(admin, "disabled-person", fixture("enabled-again"));
        check(Auth.current(employee) == null, "published identity change revokes old session");
        employeeToken = Auth.login("synthetic-employee", PASSWORD); employee = Auth.get(employeeToken);
        Auth.logout(employeeToken);
        check(!OrganizationAccessStore.authorize(employee, "demand.write", Action.HANDLE, "001").allowed(), "logged out cached Session denied");
        rejects(401, () -> OrganizationAccessStore.person(employee), "logged out person rejected");
        Auth.Session tampered = Auth.get(Auth.login("synthetic-employee", PASSWORD)); tampered.uid = 1;
        rejects(401, () -> OrganizationAccessStore.person(tampered), "mutated signed uid rejected");
        Db.exec("UPDATE users SET role='viewer' WHERE id=1");
        rejects(403, () -> OrganizationAccessStore.publish(admin, "enabled-again", fixture("must-not-publish")), "admin downgrade immediately denied");
        Db.get().close();
        check("enabled-again".equals(OrganizationAccessStore.configuration().version()), "persisted config survives connection reopen");
        Db.exec("SHUTDOWN");
        System.out.println("IntegrationIdentity: " + checks + " checks passed (isolated synthetic H2)");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> response(Exchange ex) throws Exception {
        Object r = ex.getAttribute("com.training.Api.response");
        java.lang.reflect.Field f = r.getClass().getDeclaredField("body"); f.setAccessible(true);
        return (Map<String, Object>) Json.parse(new String((byte[]) f.get(r), StandardCharsets.UTF_8));
    }
    private static final class Exchange extends HttpExchange {
        private final String method; private final URI uri;
        private final Headers request = new Headers(), response = new Headers();
        private final Map<String, Object> attrs = new HashMap<>();
        Exchange(String method, String path, String token, Map<String, Object> body) {
            this.method = method; this.uri = URI.create(path);
            if (token != null) request.set("X-Token", token);
            request.set("Content-Type", "application/json");
            if (body != null) attrs.put("com.training.Api.body", body);
        }
        public Headers getRequestHeaders() { return request; }
        public Headers getResponseHeaders() { return response; }
        public URI getRequestURI() { return uri; }
        public String getRequestMethod() { return method; }
        public HttpContext getHttpContext() { return null; }
        public void close() {}
        public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
        public void sendResponseHeaders(int code, long length) {}
        public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        public int getResponseCode() { return 0; }
        public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        public String getProtocol() { return "HTTP/1.1"; }
        public Object getAttribute(String name) { return attrs.get(name); }
        public void setAttribute(String name, Object value) { attrs.put(name, value); }
        public void setStreams(InputStream in, OutputStream out) {}
        public HttpPrincipal getPrincipal() { return null; }
    }
}
