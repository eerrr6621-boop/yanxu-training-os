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
        public String username;
        public String name;
        public String role;
        public volatile long time = System.currentTimeMillis();
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

    private static boolean verifyPbkdf2(String pwd, String stored) {
        try {
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 4 || !PASSWORD_PREFIX.equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            // 防止损坏的数据库值触发过低强度或过量计算。
            if (iterations < 100_000 || iterations > 1_000_000) return false;
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            if (salt.length < PASSWORD_SALT_BYTES || salt.length > 32 || expected.length < 24 || expected.length > 64)
                return false;
            byte[] actual = derive(pwd, salt, iterations, expected.length);
            return MessageDigest.isEqual(expected, actual);
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
        cleanupExpiredSessions(System.currentTimeMillis());
        Map<String, Object> u = Db.one("SELECT * FROM users WHERE username=?", username);
        if (u == null) return null;
        Object st = u.get("status");
        if (st != null && Integer.parseInt(st.toString()) == 0) return null;
        String storedPassword = String.valueOf(u.get("password"));
        if (!verify(password, storedPassword)) return null;

        // 历史账号首次成功登录时原子升级，无需强制用户重置密码。
        if (!storedPassword.startsWith(PASSWORD_PREFIX + "$")) {
            Db.exec("UPDATE users SET password=? WHERE id=? AND password=?",
                    hash(password), u.get("id"), storedPassword);
        }

        Session s = new Session();
        s.uid = Long.parseLong(u.get("id").toString());
        s.username = username;
        s.name = String.valueOf(u.get("name"));
        s.role = String.valueOf(u.get("role"));
        String token;
        do { token = randomToken(24); } while (SESSIONS.putIfAbsent(token, s) != null);
        return token;
    }

    public static Session get(String token) {
        long now = System.currentTimeMillis();
        cleanupExpiredSessions(now);
        if (token == null) return null;
        Session s = SESSIONS.get(token);
        if (s == null) return null;
        if (now - s.time > EXPIRE) {
            SESSIONS.remove(token, s);
            return null;
        }
        try {
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
        SESSIONS.entrySet().removeIf(entry -> entry.getValue().uid == uid);
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
