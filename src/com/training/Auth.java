package com.training;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 登录认证与权限控制 */
public class Auth {
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final SecureRandom RAND = new SecureRandom();
    private static final long EXPIRE = 12L * 3600 * 1000; // 12小时

    public static class Session {
        public long uid;
        public String username;
        public String name;
        public String role;
        public long time = System.currentTimeMillis();
    }

    public static String hash(String pwd) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("tm$" + pwd + "$salt").getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
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
        Map<String, Object> u = Db.one("SELECT * FROM users WHERE username=?", username);
        if (u == null) return null;
        Object st = u.get("status");
        if (st != null && Integer.parseInt(st.toString()) == 0) return null;
        if (!hash(password).equals(String.valueOf(u.get("password")))) return null;

        String token = randomToken(24);

        Session s = new Session();
        s.uid = Long.parseLong(u.get("id").toString());
        s.username = username;
        s.name = String.valueOf(u.get("name"));
        s.role = String.valueOf(u.get("role"));
        SESSIONS.put(token, s);
        return token;
    }

    public static Session get(String token) {
        if (token == null) return null;
        Session s = SESSIONS.get(token);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.time > EXPIRE) {
            SESSIONS.remove(token);
            return null;
        }
        s.time = System.currentTimeMillis();
        return s;
    }

    public static void logout(String token) {
        if (token != null) SESSIONS.remove(token);
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
