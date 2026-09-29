package com.training;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.function.LongSupplier;

/** Persistent, bounded email-proven browser credentials. All entry points are trusted server composition. */
final class TrustedDevices {
    private static final Set<Long> UNRESOLVED = new HashSet<>();
    private static final long MAX_SECONDS = 31_536_000L;
    private static final long MAX_RECORDS = 100_000L;
    private final LongSupplier clock;

    record Policy(long seconds, String origin, boolean local) {
        static Policy read() throws Api.ApiException {
            String age = System.getProperty("login.device.maxAgeSeconds", "");
            String source = System.getProperty("login.device.origin", "");
            if (age.isBlank() || source.isBlank()) return null;
            try {
                if (!age.matches("[1-9][0-9]{0,7}")) throw new IllegalArgumentException();
                long seconds = Long.parseLong(age);
                if (seconds > MAX_SECONDS) throw new IllegalArgumentException();
                URI uri = new URI(source);
                if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")) || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535)
                    throw new IllegalArgumentException();
                String host = uri.getHost().toLowerCase(Locale.ROOT), scheme = uri.getScheme();
                boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host);
                if (!("https".equals(scheme) || "http".equals(scheme) && local)) throw new IllegalArgumentException();
                int port = uri.getPort();
                if (port == ("https".equals(scheme) ? 443 : 80)) port = -1;
                String origin = new URI(scheme, null, host, port, null, null, null).toASCIIString();
                return new Policy(seconds, origin, "http".equals(scheme));
            } catch (Exception ignored) { throw unavailable(); }
        }
    }

    record Login(String sessionToken, String deviceToken, long expiresAt, String deviceId) {
        @Override public String toString() { return "TrustedDeviceLogin[redacted]"; }
    }

    TrustedDevices(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    long now() { return clock.getAsLong(); }

    static void init() throws SQLException {
        synchronized (Api.MUTATION_LOCK) {
            Db.exec("CREATE TABLE IF NOT EXISTS s01_trusted_device_epochs(user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,epoch BIGINT NOT NULL CHECK(epoch>=0))");
            Db.exec("CREATE TABLE IF NOT EXISTS s01_trusted_devices(selector VARCHAR(32) PRIMARY KEY,user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,secret_hash VARCHAR(64) NOT NULL,previous_hash VARCHAR(64),fingerprint VARCHAR(64) NOT NULL,user_epoch BIGINT NOT NULL,created_at BIGINT NOT NULL,last_used_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,revoked BOOLEAN NOT NULL DEFAULT FALSE,expired BOOLEAN NOT NULL DEFAULT FALSE,origin VARCHAR(300) NOT NULL)");
            Db.exec("CREATE INDEX IF NOT EXISTS idx_s01_trusted_devices_user ON s01_trusted_devices(user_id)");
        }
    }

    /** Must participate in the account/email/authorization transaction, never start a nested transaction. */
    static void revokeUserInTransaction(long uid) throws Exception {
        if (!Thread.holdsLock(Api.MUTATION_LOCK) || Db.get().getAutoCommit()) throw new IllegalStateException("Device revocation requires the owning business transaction");
        if (uid <= 0) throw new IllegalArgumentException("Invalid account");
        if (Db.one("SELECT id FROM users WHERE id=?", uid) == null) return; // ON DELETE CASCADE already removed trust.
        Map<String,Object> state = Db.one("SELECT epoch FROM s01_trusted_device_epochs WHERE user_id=?", uid);
        if (state == null) Db.exec("INSERT INTO s01_trusted_device_epochs(user_id,epoch) VALUES(?,1)", uid);
        else {
            long epoch = number(state, "epoch");
            if (epoch == Long.MAX_VALUE) throw unavailable();
            Db.exec("UPDATE s01_trusted_device_epochs SET epoch=? WHERE user_id=?", epoch + 1, uid);
        }
        Db.exec("UPDATE s01_trusted_devices SET revoked=TRUE WHERE user_id=?", uid);
    }

    /** An uncertain commit is not a reason to let the same process resume a device. Recovery requires a checked restart. */
    static void blockUser(long uid) { synchronized (Api.MUTATION_LOCK) { UNRESOLVED.add(uid); } }

    /** Narrow replacement for existing single-account writes, preserving post-commit Auth revocation at their call sites. */
    static <T> T mutation(long uid, boolean revoke, Db.TransactionWork<T> work) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            if (!Db.get().getAutoCommit()) throw new IllegalStateException("Device mutation cannot nest transactions");
            boolean[] ready = {false};
            try {
                return Db.transaction(() -> {
                    T result = work.run();
                    if (revoke) revokeUserInTransaction(uid);
                    ready[0] = true;
                    return result;
                });
            } catch (Exception failure) {
                if (revoke && ready[0]) { blockUser(uid); Auth.revokeUserSessions(uid); }
                throw failure;
            }
        }
    }

    /** Called only after the original one-time email verifier accepted a current credential/snapshot. */
    Login enroll(Auth.Credential credential, NotificationChannelsAccountEmailPreparation.Snapshot snapshot, String oldToken) throws Api.ApiException {
        synchronized (Api.MUTATION_LOCK) {
            String session = null;
            try {
                LoginVerificationHost.requireEmailMode();
                Policy policy = Policy.read();
                if (policy == null) return new Login(Auth.issueVerified(credential, snapshot), null, 0, null);
                if (credential == null || UNRESOLVED.contains(credential.uid())) throw unavailable();
                String fingerprint = currentFingerprint(credential, snapshot);
                requireOwnTransaction();
                long now = now(), expiry = Math.addExact(now, Math.multiplyExact(policy.seconds(), 1000L));
                if (now <= 0) throw unavailable();
                String selector = Auth.randomToken(16), token = selector + "." + Auth.randomToken(32);
                Db.transaction(() -> {
                    revokeTokenInside(oldToken);
                    if (number(Db.one("SELECT COUNT(*) AS count FROM s01_trusted_devices"), "count") >= MAX_RECORDS) throw unavailable();
                    if (number(Db.one("SELECT COUNT(*) AS count FROM s01_trusted_devices WHERE user_id=? AND revoked=FALSE AND expired=FALSE AND expires_at>?", credential.uid(), now), "count") >= 10) throw unavailable();
                    Map<String,Object> state = Db.one("SELECT epoch FROM s01_trusted_device_epochs WHERE user_id=?", credential.uid());
                    if (state == null) { Db.exec("INSERT INTO s01_trusted_device_epochs(user_id,epoch) VALUES(?,0)", credential.uid()); state = Map.of("epoch", 0L); }
                    Db.exec("INSERT INTO s01_trusted_devices(selector,user_id,secret_hash,previous_hash,fingerprint,user_epoch,created_at,last_used_at,expires_at,revoked,origin) VALUES(?,?,?,NULL,?,?,?,?,?,FALSE,?)",
                            selector, credential.uid(), digest(token), fingerprint, number(state,"epoch"), now, now, expiry, policy.origin());
                    if (!policy.equals(Policy.read()) || !fingerprint.equals(currentFingerprint(credential, snapshot))) throw unavailable();
                    return null;
                });
                session = Auth.issueVerified(credential, snapshot);
                Auth.attachTrustedDevice(session, selector);
                return new Login(session, token, expiry, selector);
            } catch (Api.ApiException failure) { if (session != null) Auth.logout(session); throw failure; }
              catch (Exception ignored) { if (session != null) Auth.logout(session); throw unavailable(); }
        }
    }

    Login restore(String token) throws Api.ApiException {
        synchronized (Api.MUTATION_LOCK) {
            String session = null;
            try {
                LoginVerificationHost.requireEmailMode();
                Policy policy = Policy.read();
                if (policy == null || !validToken(token)) throw denied();
                requireOwnTransaction();
                final Auth.Credential[] credential = {null};
                final NotificationChannelsAccountEmailPreparation.Snapshot[] snapshot = {null};
                final String[] replacement = {null};
                final long[] expires = {0};
                String selector = token.substring(0, 32), suppliedHash = digest(token);
                boolean accepted = Db.transaction(() -> {
                    Map<String,Object> device = Db.one("SELECT * FROM s01_trusted_devices WHERE selector=?", selector);
                    if (device == null || Boolean.TRUE.equals(device.get("revoked")) || Boolean.TRUE.equals(device.get("expired"))) return false;
                    long uid = number(device,"user_id");
                    if (UNRESOLVED.contains(uid)) throw unavailable();
                    if (!sameHash(suppliedHash, device.get("secret_hash"))) {
                        if (sameHash(suppliedHash, device.get("previous_hash"))) revokeSelector(selector);
                        return false;
                    }
                    long time = now(), created = number(device,"created_at"), last = number(device,"last_used_at"), expiry = number(device,"expires_at");
                    long effectiveExpiry = Math.min(expiry, Math.addExact(created, Math.multiplyExact(policy.seconds(), 1000L)));
                    Map<String,Object> epoch = Db.one("SELECT epoch FROM s01_trusted_device_epochs WHERE user_id=?", uid);
                    if (created <= 0 || last < created || expiry <= created || time < last || !policy.origin().equals(device.get("origin"))
                            || epoch == null || number(epoch,"epoch") != number(device,"user_epoch")) {
                        revokeSelector(selector); return false;
                    }
                    if (time >= effectiveExpiry) {
                        // Remember observed expiry durably without shortening already-issued short sessions.
                        Db.exec("UPDATE s01_trusted_devices SET expired=TRUE WHERE selector=?", selector); return false;
                    }
                    try {
                        credential[0] = Auth.trustedCredential(uid);
                        snapshot[0] = NotificationChannelsAccountEmailPreparation.loginSnapshot(uid);
                        if (!Objects.equals(device.get("fingerprint"), currentFingerprint(credential[0], snapshot[0]))) { revokeSelector(selector); return false; }
                    } catch (Api.ApiException failure) {
                        if (failure.code >= 500) throw failure;
                        revokeSelector(selector); return false;
                    }
                    replacement[0] = selector + "." + Auth.randomToken(32); expires[0] = effectiveExpiry;
                    Db.exec("UPDATE s01_trusted_devices SET previous_hash=secret_hash,secret_hash=?,last_used_at=?,expires_at=? WHERE selector=?", digest(replacement[0]), time, effectiveExpiry, selector);
                    if (!policy.equals(Policy.read())) throw unavailable();
                    return true;
                });
                if (!accepted) throw denied();
                session = Auth.issueVerified(credential[0], snapshot[0]);
                Auth.attachTrustedDevice(session, selector);
                return new Login(session, replacement[0], expires[0], selector);
            } catch (Api.ApiException failure) { if (session != null) Auth.logout(session); throw failure; }
              catch (Exception ignored) { if (session != null) Auth.logout(session); throw unavailable(); }
        }
    }

    /** Works after restart without a short session. Possession of current/previous secret can revoke, never authenticate. */
    void logout(String token, String sessionToken) throws Api.ApiException {
        synchronized (Api.MUTATION_LOCK) {
            try {
                requireOwnTransaction();
                String bound = Auth.trustedDeviceOf(sessionToken);
                Db.transaction(() -> { revokeTokenInside(token); if (bound != null) revokeSelector(bound); return null; });
            } catch (Exception ignored) { throw unavailable(); }
            finally { Auth.logout(sessionToken); }
        }
    }

    static boolean sessionCurrent(String selector, long uid) {
        synchronized (Api.MUTATION_LOCK) {
            try {
                if (selector == null || UNRESOLVED.contains(uid)) return false;
                Map<String,Object> row = Db.one("SELECT d.revoked,d.user_epoch,e.epoch FROM s01_trusted_devices d JOIN s01_trusted_device_epochs e ON e.user_id=d.user_id WHERE d.selector=? AND d.user_id=?", selector, uid);
                // Natural device expiry controls future restores, not the original short-session lifetime.
                return row != null && Boolean.FALSE.equals(row.get("revoked")) && number(row,"user_epoch") == number(row,"epoch");
            } catch (Exception ignored) { return false; }
        }
    }

    private static void revokeTokenInside(String token) throws Exception {
        if (!validToken(token)) return;
        String selector = token.substring(0,32), hash = digest(token);
        Map<String,Object> row = Db.one("SELECT secret_hash,previous_hash FROM s01_trusted_devices WHERE selector=?", selector);
        if (row != null && (sameHash(hash, row.get("secret_hash")) || sameHash(hash, row.get("previous_hash")))) revokeSelector(selector);
    }
    private static void revokeSelector(String selector) throws SQLException { Db.exec("UPDATE s01_trusted_devices SET revoked=TRUE WHERE selector=?", selector); }
    private static String currentFingerprint(Auth.Credential credential, NotificationChannelsAccountEmailPreparation.Snapshot snapshot) throws Exception {
        if (credential == null || !Auth.credentialCurrent(credential) || snapshot == null || !snapshot.equals(NotificationChannelsAccountEmailPreparation.loginSnapshot(credential.uid()))) throw denied();
        return digest(Json.write(Arrays.asList(credential.uid(), credential.username(), credential.passwordHash(), credential.name(), credential.role(), snapshot.userId(), snapshot.revision(), snapshot.email(), snapshot.importBinding())));
    }
    private static void requireOwnTransaction() throws SQLException {
        if (!Db.get().getAutoCommit()) throw new IllegalStateException("Device operation cannot nest transactions");
    }
    private static long number(Map<String,Object> map, String key) throws SQLException {
        if (map == null || !(map.get(key) instanceof Number)) throw new SQLException("Invalid device state");
        long n = ((Number) map.get(key)).longValue(); if (n < 0) throw new SQLException("Invalid device state"); return n;
    }
    static boolean validToken(String value) { return value != null && value.matches("[a-f0-9]{32}\\.[a-f0-9]{64}"); }
    private static String digest(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static boolean sameHash(String hash, Object stored) {
        return stored instanceof String s && s.matches("[a-f0-9]{64}") && MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII), s.getBytes(StandardCharsets.US_ASCII));
    }
    private static Api.ApiException denied() { return new Api.ApiException(401, "请重新登录并完成邮箱核验"); }
    private static Api.ApiException unavailable() { return new Api.ApiException(503, "登录服务暂不可用，请稍后重试"); }
}
