package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.net.URI;
import java.util.*;

/** Explicit same-origin browser endpoints. Never restore identity from a generic token lookup. */
final class TrustedDeviceHost {
    private static volatile TrustedDevices service = new TrustedDevices(System::currentTimeMillis);
    private static final String SECURE_COOKIE = "__Host-yx_device", LOCAL_COOKIE = "yx_device_local";
    private TrustedDeviceHost() {}

    static void installService(TrustedDevices replacement) { synchronized (Api.MUTATION_LOCK) { service = Objects.requireNonNull(replacement); } }
    static boolean matches(String path) { return "/api/login/device/restore".equals(path) || "/api/logout".equals(path); }

    /** Called under the original verification/issue lock, only after the email code has been consumed. */
    static void completeVerified(HttpExchange ex, NotificationChannelsLoginVerification.Verified verified) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            TrustedDevices.Policy policy = TrustedDevices.Policy.read();
            // Existing non-browser email clients can still sign in; they never acquire persistent browser trust.
            if (policy == null || !sameOrigin(ex, policy)) {
                Api.loginSuccess(ex, Auth.issueVerified(verified.credential(), verified.snapshot()));
                return;
            }
            TrustedDevices current = service;
            String oldCookie;
            try { oldCookie = cookie(ex, policy); }
            catch (Api.ApiException invalid) {
                // A fresh password + one-time email proof can repair unusable browser state; never trust the malformed value.
                clearCookies(ex); oldCookie = null;
            }
            TrustedDevices.Login login = current.enroll(verified.credential(), verified.snapshot(), oldCookie);
            if (login.deviceToken() != null) setCookie(ex, policy, login, current.now());
            Api.loginSuccess(ex, login.sessionToken());
        }
    }

    static void handle(HttpExchange ex) throws Exception {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "POST");
            throw new Api.ApiException(405, "请求方法不受支持");
        }
        if (ex.getRequestURI().getRawQuery() != null) throw new Api.ApiException(400, "登录请求不接受查询参数");
        if (!Api.body(ex).isEmpty()) throw new Api.ApiException(400, "登录请求包含不允许的字段");
        boolean logout = "/api/logout".equals(ex.getRequestURI().getPath());
        synchronized (Api.MUTATION_LOCK) {
            TrustedDevices.Policy policy = TrustedDevices.Policy.read();
            String rawSession = Api.token(ex);
            if (logout) {
                boolean hasDevice = hasCookie(ex);
                // Preserve legacy/X-Token logout when no browser device is supplied.
                if (hasDevice) requireOrigin(ex, policy);
                String device = null;
                if (hasDevice) try { device = cookie(ex, policy); }
                catch (Api.ApiException invalid) { /* Logout may forget malformed state, but can never restore from it. */ }
                Auth.Session session = Auth.get(rawSession);
                if (!hasDevice && session == null) { Api.clearSessionCookie(ex); throw denied(); }
                try {
                    if (hasDevice || Auth.trustedDeviceOf(rawSession) != null) service.logout(device, rawSession);
                    else Auth.logout(rawSession);
                } finally { clearCookies(ex); Api.clearSessionCookie(ex); }
                Api.ok(ex, null); return;
            }
            requireOrigin(ex, policy);
            if (policy == null) { clearCookies(ex); throw denied(); }
            // A second tab can arrive after the first already issued a short session. Do not rotate again.
            if (Auth.get(rawSession) != null) { Api.loginSuccess(ex, rawSession); return; }
            try {
                TrustedDevices current = service;
                TrustedDevices.Login login = current.restore(cookie(ex, policy));
                setCookie(ex, policy, login, current.now());
                Api.loginSuccess(ex, login.sessionToken());
            } catch (Api.ApiException failure) {
                if (failure.code == 401 || failure.code == 400) { clearCookies(ex); Api.clearSessionCookie(ex); }
                throw failure;
            } catch (Exception ignored) { throw new Api.ApiException(503, "登录服务暂不可用，请稍后重试"); }
        }
    }

    private static void requireOrigin(HttpExchange ex, TrustedDevices.Policy policy) throws Api.ApiException {
        if (!sameOrigin(ex, policy)) throw new Api.ApiException(403, "请从系统原页面重新登录");
    }

    private static boolean sameOrigin(HttpExchange ex, TrustedDevices.Policy policy) {
        try {
            List<String> origins = ex.getRequestHeaders().get("Origin"), hosts = ex.getRequestHeaders().get("Host");
            if (origins == null || origins.size() != 1 || hosts == null || hosts.size() != 1) return false;
            String origin = origins.get(0), host = hosts.get(0);
            URI parsed = new URI(origin);
            if (parsed.getHost() == null || parsed.getRawUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null || !parsed.getRawPath().isEmpty()) return false;
            if (!origin.equals(parsed.getScheme() + "://" + parsed.getRawAuthority())) return false;
            boolean local = "http".equals(parsed.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(parsed.getHost());
            if (!("https".equals(parsed.getScheme()) || local)) return false;
            if (local && (ex.getRemoteAddress() == null || ex.getRemoteAddress().getAddress() == null || !ex.getRemoteAddress().getAddress().isLoopbackAddress())) return false;
            if (!host.equalsIgnoreCase(parsed.getRawAuthority())) return false;
            if (policy != null && !origin.equals(policy.origin())) return false;
            String fetchSite = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
            return fetchSite == null || "same-origin".equals(fetchSite);
        } catch (Exception ignored) { return false; }
    }

    private static boolean hasCookie(HttpExchange ex) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return false;
        for (String line : headers) for (String part : line.split(";")) {
            String key = part.trim().split("=", 2)[0];
            if (SECURE_COOKIE.equals(key) || LOCAL_COOKIE.equals(key)) return true;
        }
        return false;
    }

    private static String cookie(HttpExchange ex, TrustedDevices.Policy policy) throws Api.ApiException {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        // Production accepts only the __Host- cookie. A parent-domain-injected local cookie cannot log a browser in.
        boolean local = policy != null ? policy.local() : Optional.ofNullable(ex.getRequestHeaders().getFirst("Origin")).orElse("").startsWith("http://");
        String expected = local ? LOCAL_COOKIE : SECURE_COOKIE;
        String result = null; int count = 0;
        if (headers != null) for (String line : headers) for (String part : line.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (expected.equals(pair[0])) {
                if (++count != 1 || pair.length != 2 || !TrustedDevices.validToken(pair[1])) throw new Api.ApiException(400, "登录设备资料格式无效");
                result = pair[1];
            }
        }
        return result;
    }

    private static void setCookie(HttpExchange ex, TrustedDevices.Policy policy, TrustedDevices.Login login, long now) {
        long seconds = Math.max(0, Math.min(policy.seconds(), (login.expiresAt() - now + 999) / 1000));
        String value = (policy.local() ? LOCAL_COOKIE : SECURE_COOKIE) + "=" + login.deviceToken() + "; Path=/; Max-Age=" + seconds + "; HttpOnly; SameSite=Strict";
        if (!policy.local()) value += "; Secure";
        ex.getResponseHeaders().add("Set-Cookie", value);
    }
    private static void clearCookies(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", SECURE_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict; Secure");
        ex.getResponseHeaders().add("Set-Cookie", LOCAL_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
    }
    private static Api.ApiException denied() { return new Api.ApiException(401, "请重新登录并完成邮箱核验"); }
}
