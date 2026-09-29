package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Standalone synthetic H2 + real Auth/import schema; no server, mail, or private inputs. */
public final class S01AccountEmailPreparationTest {
    private static int checks;
    private static Auth.Session admin, secondAdmin, viewer, manager;
    private static final Map<Long,String> tokens = new HashMap<>();
    private static final String ROUTE = "/api/account-email-preparation";
    private static final String PASSWORD = "synthetic-email-preparation-only";
    private static final List<String> EMAIL_TABLES = List.of("s01_account_email_heads", "s01_account_email_revisions", "s01_account_email_requests");
    private static final List<String> PROTECTED_TABLES = List.of("users", "organization_account_import_state", "organization_account_import_batches", "organization_account_import_people", "m01_synthetic_guard", "s01_notifications", "workflow_outbox");
    @FunctionalInterface interface Work { void run() throws Exception; }
    static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    static Api.ApiException reject(int code, Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected denial: " + label); }
        catch (Api.ApiException failure) {
            check(failure.code == code, label + " expected=" + code + " actual=" + failure.code);
            return failure;
        }
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> history(Map<String,Object> value) { return (List<Map<String,Object>>)value.get("history"); }
    static long n(Map<String,Object> value, String key) { return ((Number)value.get(key)).longValue(); }
    static String text(Map<String,Object> value, String key) { return Objects.toString(value.get(key), ""); }
    static String uuid() { return UUID.randomUUID().toString(); }
    static Map<String,Object> command(long user, long revision, String request, Object email) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("user_id", user); result.put("expected_revision", revision);
        result.put("request_id", request); result.put("email", email);
        return result;
    }
    static Map<String,Object> get(long user) throws Exception { return NotificationChannelsAccountEmailPreparation.get(admin, user); }
    static Map<String,Object> save(long user, long revision, String request, Object email) throws Exception {
        return NotificationChannelsAccountEmailPreparation.save(admin, command(user, revision, request, email));
    }
    static Auth.Session login(long id) throws Exception {
        String token = Auth.login("synthetic-email-" + id, PASSWORD);
        check(token != null, "synthetic real Auth login " + id);
        tokens.put(id, token); return Auth.get(token);
    }
    static void addImported(long id) throws Exception {
        Db.exec("INSERT INTO users(id,username,password,name,role,status) VALUES(?,?,NULL,?,'viewer',0)", id, "synthetic-email-" + id, "Synthetic pending " + id);
        insertAssociation(id, "person-" + id);
    }
    static void insertAssociation(long id, String person) throws Exception {
        Db.exec("INSERT INTO organization_account_import_people(person_code,source_namespace,source_reference,account_id,batch_key,source_fingerprint,payload,disposition,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                person, "a".repeat(64), "synthetic-ref-" + id, id, "synthetic-batch", "b".repeat(64), "{}", "create_pending", 1L);
    }
    static Map<String,String> snapshot(List<String> tables) throws Exception {
        Map<String,String> result = new TreeMap<>();
        for (String table : tables) {
            List<String> rows = new ArrayList<>();
            for (Map<String,Object> row : Db.query("SELECT * FROM " + table)) rows.add(Json.write(row));
            Collections.sort(rows); result.put(table, rows.toString());
        }
        return result;
    }
    static Map<String,String> emailSnapshot() throws Exception { return snapshot(EMAIL_TABLES); }
    static void state(Map<String,Object> value, long user, long revision, String email, String status, int historyCount) {
        check(n(value, "user_id") == user && n(value, "revision") == revision, "current identity and revision");
        check(Objects.equals(value.get("email"), email), "normalized current email");
        check(status.equals(value.get("status")), "current preparation status");
        check(Boolean.TRUE.equals(value.get("can_save")), "eligible target is saveable");
        check(value.get("duplicate_email") instanceof Boolean, "duplicate indication is only boolean");
        check(history(value).size() == historyCount, "one immutable history entry per change");
        for (Map<String,Object> event : history(value)) {
            check(event.keySet().equals(Set.of("revision", "email", "status", "actor_user_id", "recorded_at")), "history exposes only approved audit fields");
            check(n(event, "revision") > 0 && n(event, "actor_user_id") > 0 && !text(event, "recorded_at").isBlank(), "history has positive revision, actor and timestamp");
        }
    }
    /** Models Api's pre-parsed JSON and deferred response; never opens a socket. */
    static final class Exchange extends HttpExchange {
        final Headers requestHeaders = new Headers(), responseHeaders = new Headers();
        final Map<String,Object> attributes = new HashMap<>();
        final URI uri; final String method;
        InputStream input = new ByteArrayInputStream(new byte[0]);
        OutputStream output = new ByteArrayOutputStream(); int responseCode = -1;
        Exchange(String method, String path, String token, Map<String,Object> body) throws Exception {
            this.method = method; uri = URI.create(path);
            if (token != null) requestHeaders.set("Cookie", "yx_session=" + token);
            if (body != null) {
                requestHeaders.set("Content-Type", "application/json");
                attributes.put(Api.class.getName() + ".body", NotificationChannelsAccountEmailPreparation.parseBody(Json.write(body)));
            }
        }
        Map<String,Object> response() throws Exception {
            Object pending = attributes.get(Api.class.getName() + ".response");
            if (pending == null) throw new AssertionError("Expected deferred API response");
            var field = pending.getClass().getDeclaredField("body"); field.setAccessible(true);
            return map(Json.parse(new String((byte[])field.get(pending), StandardCharsets.UTF_8)));
        }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return input; }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int code,long length) { responseCode = code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name,Object value) { attributes.put(name, value); }
        @Override public void setStreams(InputStream in,OutputStream out) { input = in; output = out; }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
    static Exchange exchange(Auth.Session actor, String method, String path, Map<String,Object> body) throws Exception {
        return new Exchange(method, path, actor == null ? null : tokens.get(actor.uid), body);
    }
    static Map<String,Object> http(Auth.Session actor, String method, String path, Map<String,Object> body) throws Exception {
        Exchange ex = exchange(actor, method, path, body);
        check(NotificationChannelsAccountEmailPreparation.handle(ex, actor), "HTTP route recognized");
        return map(ex.response().get("data"));
    }
    static void unchangedFailure(int code, Map<String,Object> body, String label) throws Exception {
        Map<String,String> before = emailSnapshot();
        Api.ApiException failure = reject(code, () -> NotificationChannelsAccountEmailPreparation.save(admin, body), label);
        String email = Objects.toString(body.get("email"), "");
        if (!email.isBlank()) check(!Objects.toString(failure.getMessage(), "").contains(email), label + " does not echo submitted email");
        check(before.equals(emailSnapshot()), label + " leaves all preparation rows unchanged");
    }
    static void validation() throws Exception {
        List<Object> invalidEmails = Arrays.asList(42L, true, List.of("x@example.test"), Map.of("email", "x@example.test"),
                "bad", "a@localhost", "a@@example.test", ".a@example.test", "a.@example.test", "a..b@example.test",
                "a b@example.test", "a@example..test", "a@-example.test", "a@example-.test", "a@ex_ample.test",
                "\"a\"@example.test", "用户@example.test", "a@例子.test", "a@[127.0.0.1]", "a@example.test.",
                "a@example.test\r\nBcc: private@example.test", "a@example.test\n", "\ta@example.test", "a@example.test\t",
                "a\u0000@example.test", "a@example.test\u007f", "\u00a0a@example.test", "a@example.test\u00a0",
                "a@example.test,private@example.test", "Name <a@example.test>", "<script>@example.test", "a@example.test;DROP TABLE users",
                "x".repeat(65) + "@example.test", "a@" + "x".repeat(64) + ".test",
                "x".repeat(64) + "@" + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63));
        for (int i = 0; i < invalidEmails.size(); i++) unchangedFailure(400, command(110, 0, uuid(), invalidEmails.get(i)), "invalid email " + i);
        for (String key : List.of("user_id", "expected_revision", "request_id", "email")) {
            Map<String,Object> missing = command(110, 0, uuid(), "private-validation@example.test"); missing.remove(key);
            unchangedFailure(400, missing, "missing " + key);
        }
        for (String key : List.of("user_id", "expected_revision")) {
            List<Object> invalid = new ArrayList<>(Arrays.asList("1", true, 1.25, Double.NaN, Double.POSITIVE_INFINITY, null, -1L, Long.MAX_VALUE));
            if (key.equals("user_id")) invalid.add(0L);
            for (Object value : invalid) {
                Map<String,Object> body = command(110, 0, uuid(), "private-validation@example.test"); body.put(key, value);
                unchangedFailure(400, body, "invalid number " + key);
            }
        }
        for (Object value : Arrays.asList("", "request-1", "1-1-1-1-1", " " + uuid(), 42L, true, null)) {
            Map<String,Object> body = command(110, 0, uuid(), "private-validation@example.test"); body.put("request_id", value);
            unchangedFailure(400, body, "invalid request UUID");
        }
        for (String key : List.of("status", "verified", "activate", "role", "password", "actor_user_id", "restore_revision", "send_email")) {
            Map<String,Object> body = command(110, 0, uuid(), "private-validation@example.test"); body.put(key, true);
            unchangedFailure(400, body, "unknown field " + key);
        }
        // All permitted ASCII dot-atom punctuation stays in the local part; only the domain folds case.
        String local = "Az09!#$%&'*+-/=?^_`{|}~";
        state(save(110, 0, uuid(), "  " + local + ".tag@EXAMPLE.TEST  "), 110, 1, local + ".tag@example.test", "PENDING_VERIFICATION", 1);
        String maximum = "x".repeat(64) + "@" + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(61);
        check(maximum.length() == 254, "boundary address length is 254");
        state(save(110, 1, uuid(), maximum), 110, 2, maximum, "PENDING_VERIFICATION", 2);
    }
    static void strictParsing() throws Exception {
        String request = uuid();
        String valid = "{\"user_id\":105,\"expected_revision\":0,\"request_id\":\"" + request + "\",\"email\":\"parser@example.invalid\"}";
        for (String raw : List.of("", " \t\r\n", "\u000b" + valid, valid + "\u000c", valid.replace("105", "01"),
                valid.replace("105", "+105"), valid.replace("105", "1."), valid.replace("105", "1e"),
                valid.replace("105", "NaN"), valid.replace("105", "Infinity"),
                valid.replace("105", "{}"), valid.replace("105", "[]"), valid.replace("105", "true"),
                valid.replace("105", "false"), valid.replace("\"user_id\":105", "\"user_id\":105,\"user_id\":105"),
                valid.replace("\"user_id\":105", "\"user_id\":105,\"\\u0075ser_id\":105"),
                valid.substring(0, valid.length() - 1) + ",}", valid + "{}", "[" + valid + "]")) {
            reject(400, () -> NotificationChannelsAccountEmailPreparation.parseBody(raw), "strict parser rejects malformed or unsupported JSON");
        }
        check(NotificationChannelsAccountEmailPreparation.parseBody("{}").isEmpty(), "empty object parses without inventing fields");
        unchangedFailure(400, NotificationChannelsAccountEmailPreparation.parseBody("{}"), "empty object fails required save fields");
        Map<String,Object> whitespace = NotificationChannelsAccountEmailPreparation.parseBody(" \r\n\t" + valid + "\t\n\r ");
        check(n(whitespace, "user_id") == 105 && whitespace.get("email").equals("parser@example.invalid"), "standard JSON whitespace is accepted");
        for (String number : List.of("1.0000000000000001", "9007199254740991.1", "1e-1")) {
            Map<String,Object> parsed = NotificationChannelsAccountEmailPreparation.parseBody(valid.replace("105", number));
            unchangedFailure(400, parsed, "fractional number keeps full precision before validation");
        }
        Map<String,Object> exact = NotificationChannelsAccountEmailPreparation.parseBody(valid.replace("105", "105e0").replace("\"expected_revision\":0", "\"expected_revision\":0e0"));
        state(NotificationChannelsAccountEmailPreparation.save(admin, exact), 105, 1, "parser@example.invalid", "PENDING_VERIFICATION", 1);
        Map<String,Object> exactOne = NotificationChannelsAccountEmailPreparation.parseBody(valid.replace("105", "105e0").replace("\"expected_revision\":0", "\"expected_revision\":1e0").replace(request, uuid()));
        state(NotificationChannelsAccountEmailPreparation.save(admin, exactOne), 105, 1, "parser@example.invalid", "PENDING_VERIFICATION", 1);
    }
    static void authorizationAndTargets() throws Exception {
        Auth.Session forged = new Auth.Session(); forged.uid = 1; forged.role = "admin";
        for (Auth.Session actor : Arrays.asList(null, forged)) {
            reject(401, () -> NotificationChannelsAccountEmailPreparation.get(actor, 101), "missing/fabricated get session");
            reject(401, () -> NotificationChannelsAccountEmailPreparation.save(actor, command(101, 0, uuid(), "x@example.test")), "missing/fabricated save session");
        }
        for (Auth.Session actor : List.of(viewer, manager)) {
            reject(403, () -> NotificationChannelsAccountEmailPreparation.get(actor, 101), "non-admin get");
            reject(403, () -> NotificationChannelsAccountEmailPreparation.save(actor, command(101, 0, uuid(), "x@example.test")), "non-admin save");
        }
        reject(404, () -> get(99999), "unknown target");
        reject(404, () -> save(99999, 0, uuid(), "x@example.test"), "unknown save target");
        reject(404, () -> get(20), "non-imported target");
        reject(404, () -> save(20, 0, uuid(), "x@example.test"), "non-imported save target");
        for (String assignment : List.of("status=1", "role='manager'", "password='synthetic-hash-marker'")) {
            Db.exec("UPDATE users SET " + assignment + " WHERE id=109");
            reject(409, () -> get(109), "changed target eligibility get " + assignment);
            unchangedFailure(409, command(109, 0, uuid(), "private-target@example.test"), "changed target eligibility save " + assignment);
            Db.exec("UPDATE users SET status=0,role='viewer',password=NULL WHERE id=109");
        }
    }
    static void httpContract() throws Exception {
        check(NotificationChannelsAccountEmailPreparation.matches(ROUTE), "exact endpoint matches");
        check(!NotificationChannelsAccountEmailPreparation.matches("/api/account-email-unrelated"), "unrelated endpoint not matched");
        reject(401, () -> NotificationChannelsAccountEmailPreparation.handle(exchange(null, "GET", ROUTE + "?user_id=101", null), admin), "HTTP missing token ignores supplied admin");
        reject(401, () -> NotificationChannelsAccountEmailPreparation.handle(exchange(secondAdmin, "GET", ROUTE + "?user_id=101", null), admin), "HTTP token must match supplied session");
        Exchange tokenHeader = exchange(null, "GET", ROUTE + "?user_id=101", null);
        tokenHeader.requestHeaders.set("X-Token", tokens.get(1L));
        check(NotificationChannelsAccountEmailPreparation.handle(tokenHeader, admin), "X-Token accepted for matching live session");
        state(map(tokenHeader.response().get("data")), 101, 0, null, "MISSING", 0);
        for (String query : List.of("", "?user_id=0", "?user_id=-1", "?user_id=1.1", "?user_id=101&other=1", "?user_id=101&user_id=102", "?user_id=101&email=private%40example.test")) {
            reject(400, () -> http(admin, "GET", ROUTE + query, null), "invalid GET query " + query);
        }
        for (String method : List.of("PUT", "PATCH", "DELETE")) reject(405, () -> http(admin, method, ROUTE, command(101, 0, uuid(), "x@example.test")), "history cannot be modified by " + method);
        reject(415, () -> http(admin, "POST", ROUTE, null), "POST requires JSON");
        reject(400, () -> http(admin, "POST", ROUTE + "?user_id=102", command(101, 0, uuid(), "x@example.test")), "POST has no query overrides");
        state(http(admin, "GET", ROUTE + "?user_id=101", null), 101, 0, null, "MISSING", 0);
    }
    static void lifecycle() throws Exception {
        String first = uuid();
        state(http(admin, "POST", ROUTE, command(101, 0, first, "  Local.Part@EXAMPLE.TEST  ")), 101, 1, "Local.Part@example.test", "PENDING_VERIFICATION", 1);
        Map<String,String> afterFirst = emailSnapshot();
        state(save(101, 0, first, "Local.Part@example.test"), 101, 1, "Local.Part@example.test", "PENDING_VERIFICATION", 1);
        check(afterFirst.equals(emailSnapshot()), "normalized replay is completely write-free");
        state(save(101, 1, uuid(), "Local.Part@Example.Test"), 101, 1, "Local.Part@example.test", "PENDING_VERIFICATION", 1);
        check(Db.count("s01_account_email_requests") == 2, "same email at current revision records only request");
        unchangedFailure(409, command(101, 0, uuid(), "Local.Part@example.test"), "same-email stale revision is still conflict");
        unchangedFailure(409, command(101, 0, first, "Other@example.test"), "UUID cannot be reused with a different email");
        unchangedFailure(409, command(101, 1, first, "Local.Part@example.test"), "UUID cannot be reused with a different expected revision");
        unchangedFailure(409, command(102, 0, first, "Local.Part@example.test"), "UUID cannot be reused with a different target");
        Map<String,String> beforeOtherActor = emailSnapshot();
        reject(409, () -> NotificationChannelsAccountEmailPreparation.save(secondAdmin, command(101, 0, first, "Local.Part@example.test")), "UUID cannot be reused by another administrator");
        check(beforeOtherActor.equals(emailSnapshot()), "actor collision writes nothing");
        state(save(101, 1, uuid(), "Next@example.test"), 101, 2, "Next@example.test", "PENDING_VERIFICATION", 2);
        Map<String,String> beforeOldReplay = emailSnapshot();
        state(save(101, 0, first, "Local.Part@example.test"), 101, 2, "Next@example.test", "PENDING_VERIFICATION", 2);
        check(beforeOldReplay.equals(emailSnapshot()), "old replay after newer edit neither writes nor restores history");
        state(save(101, 2, uuid(), "  "), 101, 3, null, "MISSING", 3);
        state(save(101, 3, uuid(), null), 101, 3, null, "MISSING", 3);
        state(save(102, 0, uuid(), null), 102, 0, null, "MISSING", 0);
        state(save(102, 0, uuid(), ""), 102, 0, null, "MISSING", 0);
        state(save(101, 3, uuid(), "Dupe@Example.TEST"), 101, 4, "Dupe@example.test", "PENDING_VERIFICATION", 4);
        state(save(102, 0, uuid(), "dupe@example.test"), 102, 1, "dupe@example.test", "PENDING_VERIFICATION", 1);
        Map<String,Object> duplicate = get(101);
        check(Boolean.TRUE.equals(duplicate.get("duplicate_email")), "case-insensitive duplicate flag on first account");
        check(Boolean.TRUE.equals(get(102).get("duplicate_email")), "case-insensitive duplicate flag on second account");
        check(!Json.write(duplicate).contains("synthetic-email-102") && !duplicate.containsKey("duplicates") && !duplicate.containsKey("duplicate_user_id"), "duplicate flag reveals no other account");
        save(102, 1, uuid(), null);
        check(Boolean.FALSE.equals(get(101).get("duplicate_email")), "duplicate check uses only current values, not history");
        check(Boolean.FALSE.equals(get(102).get("duplicate_email")), "missing email has no duplicates");
        Map<String,Object> copy = get(101);
        try { history(copy).get(0).put("email", "mutated-client-copy@example.test"); }
        catch (UnsupportedOperationException expected) { check(true, "returned audit entries are immutable"); }
        check(!Json.write(get(101)).contains("mutated-client-copy"), "changing returned history cannot alter stored audit");
        // Replay still validates current identity and target state.
        Db.exec("UPDATE users SET status=1 WHERE id=101");
        unchangedFailure(409, command(101, 0, first, "Local.Part@example.test"), "old replay rejects enabled target");
        Db.exec("UPDATE users SET status=0 WHERE id=101");
        Db.exec("UPDATE users SET role='viewer' WHERE id=1");
        reject(403, () -> save(101, 0, first, "Local.Part@example.test"), "old replay rechecks downgraded administrator");
        Db.exec("UPDATE users SET role='admin' WHERE id=1");
        Auth.revokeUserSessions(1);
        reject(401, () -> save(101, 0, first, "Local.Part@example.test"), "old replay rejects revoked session");
        admin = login(1);
        state(save(101, 0, first, "Local.Part@example.test"), 101, 4, "Dupe@example.test", "PENDING_VERIFICATION", 4);
    }
    static void associationChanges() throws Exception {
        String request = uuid();
        save(108, 0, request, "association@example.test");
        Map<String,String> before = emailSnapshot();
        Db.exec("UPDATE organization_account_import_people SET source_reference='replacement-ref-108' WHERE account_id=108");
        reject(409, () -> get(108), "replacement import binding cannot read prior account preparation");
        reject(409, () -> save(108, 0, request, "association@example.test"), "replacement binding cannot replay old request");
        reject(409, () -> save(108, 1, uuid(), "replacement@example.test"), "replacement binding cannot overwrite old preparation");
        check(before.equals(emailSnapshot()), "binding changes leave preparation records intact");
        Db.exec("UPDATE organization_account_import_people SET source_reference='synthetic-ref-108' WHERE account_id=108");
    }
    static void concurrentRequests() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1); String request = uuid();
            List<Future<Map<String,Object>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> { start.await(); return save(103, 0, request, "Concurrent@example.test"); }));
            start.countDown();
            for (Future<Map<String,Object>> future : futures) state(future.get(20, TimeUnit.SECONDS), 103, 1, "Concurrent@example.test", "PENDING_VERIFICATION", 1);
            check(Db.query("SELECT * FROM s01_account_email_requests WHERE user_id=103").size() == 1, "concurrent identical requests have one ledger record");
            check(history(get(103)).size() == 1, "concurrent identical requests have one revision");
            CountDownLatch casStart = new CountDownLatch(1);
            List<Future<Integer>> competing = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final String email = "cas-" + i + "@example.test";
                competing.add(pool.submit(() -> {
                    casStart.await();
                    try { save(104, 0, uuid(), email); return 200; }
                    catch (Api.ApiException e) { return e.code; }
                }));
            }
            casStart.countDown();
            List<Integer> outcomes = new ArrayList<>();
            for (Future<Integer> future : competing) outcomes.add(future.get(20, TimeUnit.SECONDS));
            Collections.sort(outcomes);
            check(outcomes.equals(List.of(200, 409)), "competing revision zero requests have one winner and one conflict");
            check(n(get(104), "revision") == 1 && history(get(104)).size() == 1, "CAS loser creates no extra history");
            check(Db.query("SELECT * FROM s01_account_email_requests WHERE user_id=104").size() == 1, "CAS loser leaves no request ledger entry");
        } finally { pool.shutdownNow(); check(pool.awaitTermination(5, TimeUnit.SECONDS), "all test worker threads stop"); }
    }
    static void sqlFailureIsPrivate() throws Exception {
        // A genuine H2 integrity error includes the rejected row in its native message.
        // The boundary must replace it with a stable non-sensitive API error and roll back.
        String privateEmail = "Private-SQL-Failure@example.test";
        Db.exec("ALTER TABLE s01_account_email_heads ADD CONSTRAINT synthetic_email_failure CHECK(email IS NULL OR email <> 'Private-SQL-Failure@example.test')");
        try { unchangedFailure(503, command(107, 0, uuid(), privateEmail), "SQL failure is sanitized and atomic"); }
        finally { Db.exec("ALTER TABLE s01_account_email_heads DROP CONSTRAINT synthetic_email_failure"); }
        state(get(107), 107, 0, null, "MISSING", 0);
    }
    static void auditDeletionGuard() throws Exception {
        Map<String,String> before = emailSnapshot();
        reject(409, () -> NotificationChannelsAccountEmailPreparation.guardAccountDeletion(1), "audit actor with revisions cannot be deleted");
        NotificationChannelsAccountEmailPreparation.guardAccountDeletion(2);
        check(before.equals(emailSnapshot()), "referenced and unreferenced actor deletion checks are read-only");
        state(NotificationChannelsAccountEmailPreparation.save(secondAdmin, command(106, 0, uuid(), null)), 106, 0, null, "MISSING", 0);
        check(Db.query("SELECT * FROM s01_account_email_revisions WHERE actor_user_id=2").isEmpty(), "second actor owns only an empty-value request, no revisions");
        check(Db.query("SELECT * FROM s01_account_email_requests WHERE actor_user_id=2").size() == 1, "empty-value save keeps its request actor reference");
        before = emailSnapshot();
        reject(409, () -> NotificationChannelsAccountEmailPreparation.guardAccountDeletion(2), "request-only audit actor cannot be deleted");
        NotificationChannelsAccountEmailPreparation.guardAccountDeletion(3);
        check(before.equals(emailSnapshot()), "request-only and unreferenced actor deletion checks are read-only");
    }
    static void coldReopen(Path data) throws Exception {
        if (!Files.isRegularFile(data.resolve("training.mv.db"))) throw new IllegalArgumentException("Existing synthetic database required for cold reopen");
        System.setProperty("data.dir", data.toString());
        List<String> allTables = new ArrayList<>(PROTECTED_TABLES); allTables.addAll(EMAIL_TABLES);
        Map<String,String> before = snapshot(allTables);
        admin = login(1); // A separate JVM must establish an entirely new real Auth session.
        state(get(101), 101, 4, "Dupe@example.test", "PENDING_VERIFICATION", 4);
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("email:Local.Part@example.test".getBytes(StandardCharsets.UTF_8)));
        List<Map<String,Object>> requests = Db.query("SELECT request_id FROM s01_account_email_requests WHERE user_id=? AND actor_user_id=? AND expected_revision=? AND email_digest=?", 101L, 1L, 0L, digest);
        check(requests.size() == 1, "cold restart preserves exactly one original request ledger record");
        state(save(101, 0, text(requests.get(0), "request_id"), "  Local.Part@EXAMPLE.TEST  "), 101, 4, "Dupe@example.test", "PENDING_VERIFICATION", 4);
        check(before.equals(snapshot(allTables)), "cold restart login, GET and old replay leave all stored rows unchanged");
        Db.exec("SHUTDOWN");
        System.out.println("S01 account email preparation cold JVM restart: " + checks + " checks passed (new Auth session, durable history, write-free old replay).");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--reopen")) { coldReopen(Path.of(args[1]).toAbsolutePath()); return; }
        if (args.length != 1) throw new IllegalArgumentException("Fresh empty synthetic test directory required");
        Path data = Path.of(args[0]).toAbsolutePath();
        try (var contents = Files.list(data)) { if (contents.findAny().isPresent()) throw new IllegalArgumentException("Test directory must be empty"); }
        System.setProperty("data.dir", data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        String hash = Auth.hash(PASSWORD);
        for (int id = 1; id <= 4; id++) Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)", id, "synthetic-email-" + id, hash, "Synthetic actor " + id, id <= 2 ? "admin" : id == 3 ? "viewer" : "manager");
        Db.exec("INSERT INTO users VALUES(20,'synthetic-unimported',NULL,'Synthetic unimported','viewer',0)");
        OrganizationAccountImport.init();
        for (long id = 101; id <= 110; id++) addImported(id);
        for (String table : List.of("m01_synthetic_guard", "s01_notifications", "workflow_outbox")) {
            Db.exec("CREATE TABLE " + table + "(id INT PRIMARY KEY,payload VARCHAR(100))");
            Db.exec("INSERT INTO " + table + " VALUES(1,'synthetic unchanged sentinel')");
        }
        admin = login(1); secondAdmin = login(2); viewer = login(3); manager = login(4);
        Map<String,String> protectedInitial = snapshot(PROTECTED_TABLES);
        NotificationChannelsAccountEmailPreparation.init(); NotificationChannelsAccountEmailPreparation.init();
        check(Db.count("s01_account_email_heads") == 0 && Db.count("s01_account_email_revisions") == 0 && Db.count("s01_account_email_requests") == 0, "repeat init creates no preparations, revisions or requests");
        Map<String,String> initialEmail = emailSnapshot();
        for (int i = 0; i < 3; i++) state(get(101), 101, 0, null, "MISSING", 0);
        check(initialEmail.equals(emailSnapshot()), "GET never creates an initial head, audit or request");
        authorizationAndTargets(); httpContract(); lifecycle(); validation(); strictParsing(); concurrentRequests(); sqlFailureIsPrivate();
        associationChanges();
        auditDeletionGuard();
        for (String table : PROTECTED_TABLES)
            check(protectedInitial.get(table).equals(snapshot(PROTECTED_TABLES).get(table)), "feature leaves protected table untouched: " + table);
        Map<String,String> persisted = emailSnapshot();
        Map<String,Object> expected = get(101);
        Db.get().close();
        check(expected.equals(get(101)), "history and current state survive reconnect");
        check(persisted.equals(emailSnapshot()), "reconnect and GET do not rewrite preparation tables");
        for (long id : List.of(101L, 102L, 103L, 104L, 107L, 110L)) http(admin, "GET", ROUTE + "?user_id=" + id, null);
        check(persisted.equals(emailSnapshot()), "all HTTP GET responses are write-free after reconnect");
        check(Auth.login("synthetic-email-101", PASSWORD) == null, "prepared imported account remains unable to log in");
        Db.exec("SHUTDOWN");
        System.out.println("S01 account email preparation: " + checks + " checks passed (synthetic H2/Auth; no network/mail/private data).");
    }
}
