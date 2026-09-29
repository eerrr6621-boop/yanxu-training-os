package com.training;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** 登录认证与权限控制 */
public class Auth {
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Long, Long> CREDENTIAL_GENERATIONS = new HashMap<>();
    private static final SecureRandom RAND = new SecureRandom();
    private static final long EXPIRE = 12L * 3600 * 1000; // 12小时
    private static final long SESSION_SWEEP_INTERVAL = 60L * 1000;
    private static final AtomicLong LAST_SESSION_SWEEP = new AtomicLong();

    private static final String PASSWORD_PREFIX = "pbkdf2";
    private static final String PASSWORD_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_SALT_BYTES = 16;
    private static final int PASSWORD_HASH_BYTES = 32;

    public static class Session {
        public long uid;
        private long issuedUid;
        public String username;
        public String name;
        public String role;
        public volatile long time = System.currentTimeMillis();
        private volatile String trustedDeviceId;
        private Credential verifiedCredential;
        private NotificationChannelsAccountEmailPreparation.Snapshot verifiedEmail;
    }

    /** Server-only password proof. It grants no session and never leaves the authentication host. */
    record Credential(long uid, String username, String passwordHash, String name, String role, long generation) {
        Credential(long uid, String username, String passwordHash, String name, String role) {
            this(uid, username, passwordHash, name, role, Auth.generation(uid));
        }
        @Override public String toString() { return "Credential[redacted]"; }
    }

    private static long generation(long uid) {
        synchronized (Api.MUTATION_LOCK) { return CREDENTIAL_GENERATIONS.computeIfAbsent(uid, ignored -> 0L); }
    }

    /**
     * 产生用于数据库存储的独立随机盐 PBKDF2 密码哈希。
     * 编码长度约 80 字符，可直接放入现有 VARCHAR(128) 字段。
     */
    public static String hash(String pwd) {
        byte[] salt = new byte[PASSWORD_SALT_BYTES];
        RAND.nextBytes(salt);
        byte[] derived = derive(pwd, salt, PASSWORD_ITERATIONS, PASSWORD_HASH_BYTES);
        return PASSWORD_PREFIX + "$" + PASSWORD_ITERATIONS + "$" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    /** 验证新 PBKDF2 编码或历史固定盐 SHA-256 编码。 */
    public static boolean verify(String pwd, String stored) {
        if (pwd == null || stored == null) return false;
        if (stored.startsWith(PASSWORD_PREFIX + "$")) return verifyPbkdf2(pwd, stored);
        if (stored.matches("(?i)[0-9a-f]{64}")) {
            byte[] expected = legacyHash(pwd).getBytes(StandardCharsets.US_ASCII);
            byte[] actual = stored.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, actual);
        }
        return false;
    }

    /** Check stored-password structure without deriving a key or proving any plaintext. */
    static boolean validStoredPassword(String stored) {
        if (stored == null) return false;
        if (stored.startsWith(PASSWORD_PREFIX + "$")) return parsePbkdf2(stored) != null;
        return stored.matches("(?i)[0-9a-f]{64}");
    }

    private record Pbkdf2Password(int iterations, byte[] salt, byte[] expected) {}

    private static Pbkdf2Password parsePbkdf2(String stored) {
        try {
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 4 || !PASSWORD_PREFIX.equals(parts[0])) return null;
            int iterations = Integer.parseInt(parts[1]);
            // 防止损坏的数据库值触发过低强度或过量计算。
            if (iterations < 100_000 || iterations > 1_000_000) return null;
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            if (salt.length < PASSWORD_SALT_BYTES || salt.length > 32 || expected.length < 24 || expected.length > 64)
                return null;
            return new Pbkdf2Password(iterations, salt, expected);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean verifyPbkdf2(String pwd, String stored) {
        try {
            Pbkdf2Password parsed = parsePbkdf2(stored);
            if (parsed == null) return false;
            byte[] actual = derive(pwd, parsed.salt(), parsed.iterations(), parsed.expected().length);
            return MessageDigest.isEqual(parsed.expected(), actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(String pwd, byte[] salt, int iterations, int bytes) {
        char[] password = (pwd == null ? "" : pwd).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bytes * 8);
        try {
            return SecretKeyFactory.getInstance(PASSWORD_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希算法不可用", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(password, '\0');
        }
    }

    private static String legacyHash(String pwd) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("tm$" + pwd + "$salt").getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("历史密码哈希算法不可用", e);
        }
    }

    /** 产生不可猜测的十六进制令牌。 */
    public static String randomToken(int bytes) {
        byte[] data = new byte[Math.max(16, bytes)];
        RAND.nextBytes(data);
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte value : data) sb.append(String.format("%02x", value));
        return sb.toString();
    }

    /** 用于首次启动的高强度随机密码。 */
    public static String randomPassword() {
        byte[] data = new byte[18];
        RAND.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static String login(String username, String password) throws Exception {
        LoginVerificationHost.requireLegacy();
        synchronized (Api.MUTATION_LOCK) {
            Credential credential = checkedCredential(username, password);
            return credential == null || !FirstBindStore.allowsSession(credential.uid()) ? null : issue(credential, null);
        }
    }

    static Credential checkedCredential(String username, String password) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
        Map<String, Object> u = Db.one("SELECT * FROM users WHERE username=?", username);
        if (u == null) return null;
        if (!"1".equals(String.valueOf(u.get("status")))) return null;
        String storedPassword = String.valueOf(u.get("password"));
        if (!verify(password, storedPassword)) return null;

        // 历史账号首次成功登录时原子升级，无需强制用户重置密码。
        if (!storedPassword.startsWith(PASSWORD_PREFIX + "$")) {
            String upgraded = hash(password);
            Db.exec("UPDATE users SET password=? WHERE id=? AND password=?",
                    upgraded, u.get("id"), storedPassword);
            storedPassword = upgraded;
        }
        Credential result = new Credential(Long.parseLong(u.get("id").toString()),
                String.valueOf(u.get("username")), storedPassword, (String) u.get("name"),
                (String) u.get("role"));
        return credentialCurrent(result) ? result : null;
        }
    }

    /** Trusted-device host only: this is current identity data, not a password proof. */
    static Credential trustedCredential(long uid) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Map<String,Object> row = Db.one("SELECT * FROM users WHERE id=?", uid);
            if (row == null || !"1".equals(String.valueOf(row.get("status"))) || !(row.get("password") instanceof String password) || !validStoredPassword(password))
                throw new Api.ApiException(401, "请重新登录并完成邮箱核验");
            return new Credential(uid, (String) row.get("username"), password, (String) row.get("name"), (String) row.get("role"));
        }
    }

    static void attachTrustedDevice(String token, String selector) throws Api.ApiException {
        synchronized (Api.MUTATION_LOCK) {
            Session session = get(token);
            if (session == null || !TrustedDevices.sessionCurrent(selector, session.uid))
                throw new Api.ApiException(401, "请重新登录并完成邮箱核验");
            session.trustedDeviceId = selector;
        }
    }

    static String trustedDeviceOf(String token) {
        synchronized (Api.MUTATION_LOCK) {
            Session session = get(token);
            return session == null ? null : session.trustedDeviceId;
        }
    }

    static boolean credentialCurrent(Credential credential) {
        if (credential == null) return false;
        synchronized (Api.MUTATION_LOCK) {
            try {
                Map<String, Object> user = Db.one("SELECT username,password,name,role,status FROM users WHERE id=?", credential.uid());
                return user != null && "1".equals(String.valueOf(user.get("status")))
                        && credential.generation() == generation(credential.uid())
                        && Objects.equals(credential.username(), user.get("username"))
                        && Objects.equals(credential.passwordHash(), user.get("password"))
                        && Objects.equals(credential.name(), user.get("name"))
                        && Objects.equals(credential.role(), user.get("role"));
            } catch (Exception unavailable) { return false; }
        }
    }

    static String issueVerified(Credential credential, NotificationChannelsAccountEmailPreparation.Snapshot candidate) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            LoginVerificationHost.requireEmailMode();
            boolean matches = false;
            try {
                matches = credentialCurrent(credential) && candidate != null
                        && candidate.equals(NotificationChannelsAccountEmailPreparation.loginSnapshot(credential.uid()));
            } catch (Exception ignored) { /* A stale or unavailable identity cannot grant a session. */ }
            if (!matches)
                throw new Api.ApiException(401, "登录核验已失效，请重新登录");
            return issue(credential, candidate);
        }
    }

    private static String issue(Credential credential, NotificationChannelsAccountEmailPreparation.Snapshot candidate) throws Exception {
        if (!FirstBindStore.allowsSession(credential.uid()))
            throw new Api.ApiException(401, "请先完成首次邮箱绑定");
        cleanupExpiredSessions(System.currentTimeMillis());
        Session s = new Session();
        s.uid = credential.uid();
        s.issuedUid = s.uid;
        s.username = credential.username();
        s.name = String.valueOf(credential.name());
        s.role = String.valueOf(credential.role());
        s.verifiedCredential = candidate == null ? null : credential;
        s.verifiedEmail = candidate;
        String token;
        do { token = randomToken(24); } while (SESSIONS.putIfAbsent(token, s) != null);
        return token;
    }

    public static Session get(String token) {
        synchronized (Api.MUTATION_LOCK) {
            long now = System.currentTimeMillis();
            cleanupExpiredSessions(now);
            if (token == null) return null;
            Session s = SESSIONS.get(token);
            if (s == null) return null;
            if (!LoginVerificationHost.validMode()) {
                SESSIONS.remove(token, s);
                return null;
            }
            if (s.verifiedEmail == null && !LoginVerificationHost.acceptsUnverifiedSessions()) {
                SESSIONS.remove(token, s);
                return null;
            }
            if (s.uid != s.issuedUid || s.issuedUid <= 0) {
                SESSIONS.remove(token, s);
                return null;
            }
            if (now - s.time > EXPIRE) {
                SESSIONS.remove(token, s);
                return null;
            }
            if (s.trustedDeviceId != null && !TrustedDevices.sessionCurrent(s.trustedDeviceId, s.uid)) {
                SESSIONS.remove(token, s);
                return null;
            }
            try {
                if (!FirstBindStore.allowsSession(s.uid)) {
                    SESSIONS.remove(token, s);
                    return null;
                }
                if (s.verifiedEmail != null && (!credentialCurrent(s.verifiedCredential)
                        || !s.verifiedEmail.equals(NotificationChannelsAccountEmailPreparation.loginSnapshot(s.uid)))) {
                    SESSIONS.remove(token, s);
                    return null;
                }
                Map<String, Object> user = Db.one("SELECT username,name,role,status FROM users WHERE id=?", s.uid);
                if (user == null || Integer.parseInt(String.valueOf(user.get("status"))) != 1) {
                    SESSIONS.remove(token);
                    return null;
                }
                // 每次请求都以用户表为准，降权、改名与停用无需等待会话自然过期。
                s.username = String.valueOf(user.get("username"));
                s.name = String.valueOf(user.get("name"));
                s.role = String.valueOf(user.get("role"));
            } catch (Exception e) {
                // 身份状态无法核实时安全失败，避免继续沿用缓存权限。
                SESSIONS.remove(token);
                return null;
            }
            s.time = now;
            return s;
        }
    }

    /** Revalidate an internally supplied session; a uid alone never proves authentication. */
    public static Session current(Session candidate) {
        if (candidate == null) return null;
        for (Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            if (entry.getValue() == candidate)
                return get(entry.getKey()) == candidate ? candidate : null;
        }
        return null;
    }

    public static void logout(String token) {
        cleanupExpiredSessions(System.currentTimeMillis());
        if (token != null) SESSIONS.remove(token);
    }

    /** 会话清理最多每分钟扫描一次，避免每个 API 请求都做 O(n) 遍历。 */
    private static void cleanupExpiredSessions(long now) {
        long previous = LAST_SESSION_SWEEP.get();
        if (now - previous < SESSION_SWEEP_INTERVAL || !LAST_SESSION_SWEEP.compareAndSet(previous, now)) return;
        SESSIONS.entrySet().removeIf(entry -> now - entry.getValue().time > EXPIRE);
    }

    /** 用户被停用、降权、删除或改密后，立即撤销全部既有会话。 */
    public static void revokeUserSessions(long uid) {
        synchronized (Api.MUTATION_LOCK) {
            SESSIONS.entrySet().removeIf(entry -> entry.getValue().uid == uid);
            // Preserve revocation even when account fields are later restored to their old values.
            CREDENTIAL_GENERATIONS.computeIfPresent(uid, (key, value) -> value + 1);
        }
    }

    /** 是否有写权限（业务模块） */
    public static boolean canWrite(Session s) {
        return s != null && ("admin".equals(s.role) || "manager".equals(s.role));
    }

    /** 是否系统管理员（用户管理） */
    public static boolean isAdmin(Session s) {
        return s != null && "admin".equals(s.role);
    }
}
