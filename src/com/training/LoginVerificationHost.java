package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.time.Instant;
import java.net.URI;
import java.util.*;

/** The sole HTTP login host. Email verification never activates an account or assigns a role. */
final class LoginVerificationHost {
    private static volatile NotificationChannelsLoginVerification service =
            new NotificationChannelsLoginVerification(System::currentTimeMillis, null);

    private LoginVerificationHost() {}

    /** Trusted startup composition only; there is no HTTP configuration or sender bypass. */
    static void installService(NotificationChannelsLoginVerification replacement) {
        synchronized (Api.MUTATION_LOCK) { service = Objects.requireNonNull(replacement); }
    }

    static boolean matches(String path) {
        return "/api/login".equals(path) || (path != null && path.startsWith("/api/login/"));
    }

    static boolean acceptsUnverifiedSessions() {
        return "legacy".equals(System.getProperty("login.email.mode", "legacy"));
    }

    static boolean validMode() {
        return Set.of("legacy", "required").contains(System.getProperty("login.email.mode", "legacy"));
    }

    static void requireEmailMode() throws Api.ApiException {
        if (!"required".equals(System.getProperty("login.email.mode", "legacy")))
            throw new Api.ApiException(503, "邮箱核验尚未启用或配置已变化");
    }

    static void requireLegacy() throws Api.ApiException {
        if (!acceptsUnverifiedSessions())
            throw new Api.ApiException(503, "请通过邮箱核验流程登录");
    }

    private static boolean required() throws Api.ApiException {
        String mode = System.getProperty("login.email.mode", "legacy");
        if ("legacy".equals(mode)) return false;
        if ("required".equals(mode)) return true;
        throw new Api.ApiException(503, "登录核验暂未配置完成");
    }

    static void handle(HttpExchange ex) throws Exception {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", "POST");
            throw new Api.ApiException(405, "请求方法不受支持");
        }
        if (ex.getRequestURI().getRawQuery() != null)
            throw new Api.ApiException(400, "登录请求不接受查询参数");
        String path = ex.getRequestURI().getPath();
        if (!Set.of("/api/login", "/api/login/email/verify", "/api/login/email/resend", "/api/login/email/cancel", "/api/login/email/bind/send", "/api/login/email/bind/verify", "/api/login/email/bind/resend", "/api/login/email/bind/cancel").contains(path))
            throw new Api.ApiException(404, "登录接口不存在");
        Map<String, Object> body = Api.body(ex);
        NotificationChannelsLoginVerification current = service;
        try {
            if (path.equals("/api/login")) {
                fields(body, Set.of("username", "password"));
                String username = text(body, "username", 200), password = text(body, "password", 1024);
                if (!required()) {
                    synchronized (Api.MUTATION_LOCK) {
                        String token = Auth.login(username, password);
                        if (token == null) throw new Api.ApiException(401, "用户名或密码错误（或账号已停用）");
                        Api.loginSuccess(ex, token);
                    }
                } else {
                    String remote = remote(ex);
                    current.preflight(remote);
                    Auth.Credential credential = Auth.checkedCredential(username, password);
                    if (credential == null) throw new Api.ApiException(401, "用户名或密码错误（或账号已停用）");
                    NotificationChannelsLoginVerification.Enrollment enrollment=current.enroll(credential);
                    if(enrollment!=null) {
                        try {requireBindingOrigin(ex);}
                        catch(Api.ApiException rejected){current.cancelEnrollment(enrollment.enrollmentId());throw rejected;}
                        synchronized(Api.MUTATION_LOCK) {
                            requireEmailMode();requireCurrent(current);Api.clearSessionCookie(ex);
                            Api.ok(ex,Map.of("status","EMAIL_BIND_REQUIRED","enrollment_id",enrollment.enrollmentId(),
                                    "expires_at",Instant.ofEpochMilli(enrollment.expiresAt()).toString()));
                        }
                    } else {
                        // begin reserves under the shared lock, sends outside it, then revalidates.
                        challenge(ex, current, current.begin(credential, remote));
                    }
                }
                return;
            }
            if(path.startsWith("/api/login/email/bind/")) {
                requireBindingOrigin(ex);
                if(path.endsWith("/cancel")) {
                    fields(body,Set.of("enrollment_id"));current.cancelEnrollment(id(body,"enrollment_id"));Api.ok(ex,null);return;
                }
                requireEmailMode();
                if(path.endsWith("/send")) {
                    fields(body,Set.of("enrollment_id","email"));
                    bindingChallenge(ex,current,current.beginFirst(id(body,"enrollment_id"),text(body,"email",254),remote(ex)));
                } else if(path.endsWith("/resend")) {
                    fields(body,Set.of("challenge_id"));bindingChallenge(ex,current,current.resendFirst(id(body,"challenge_id"),remote(ex)));
                } else {
                    fields(body,Set.of("challenge_id","code"));String id=id(body,"challenge_id"),code=text(body,"code",32);
                    synchronized(Api.MUTATION_LOCK) {
                        requireCurrent(current);requireEmailMode();
                        NotificationChannelsLoginVerification.Verified result=current.verifyFirst(id,code);
                        TrustedDeviceHost.completeVerified(ex,result);
                    }
                }
                return;
            }
            boolean verify = path.endsWith("/verify");
            fields(body, verify ? Set.of("challenge_id", "code") : Set.of("challenge_id"));
            String id = text(body, "challenge_id", 64);
            if (!id.matches("[a-f0-9]{64}")) throw new Api.ApiException(400, "登录核验请求格式无效");
            if (path.endsWith("/cancel")) { current.cancel(id); Api.ok(ex, null); return; }
            if (!required()) throw new Api.ApiException(503, "邮箱核验尚未启用");
            if (verify) {
                String code = text(body, "code", 32);
                synchronized (Api.MUTATION_LOCK) {
                    if (current != service) throw new Api.ApiException(401, "登录核验已失效，请重新登录");
                    NotificationChannelsLoginVerification.Verified result = current.verify(id, code);
                    TrustedDeviceHost.completeVerified(ex, result);
                }
            } else challenge(ex, current, current.resend(id, remote(ex)));
        } catch (Api.ApiException error) {
            if (!path.endsWith("/cancel")) Api.clearSessionCookie(ex);
            throw error;
        } catch (Exception unavailable) {
            if (!path.endsWith("/cancel")) Api.clearSessionCookie(ex);
            // Never attach SQL, passwords, candidates, challenges or transport exceptions to logs.
            throw new Api.ApiException(503, "登录核验暂时不可用，请稍后重试");
        }
    }

    private static String remote(HttpExchange ex) throws Api.ApiException {
        return LoginRequestPeer.remote(ex);
    }

    private static void challenge(HttpExchange ex, NotificationChannelsLoginVerification source,
                                  NotificationChannelsLoginVerification.Challenge challenge) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            requireEmailMode();
            if (source != service) throw new Api.ApiException(401, "登录核验已失效，请重新登录");
            Api.clearSessionCookie(ex);
            Api.ok(ex, Map.of("status", "EMAIL_REQUIRED", "challenge_id", challenge.challengeId(),
                    "masked_email", challenge.maskedEmail(), "expires_at", Instant.ofEpochMilli(challenge.expiresAt()).toString(),
                    "resend_after", Instant.ofEpochMilli(challenge.resendAfter()).toString()));
        }
    }

    private static void bindingChallenge(HttpExchange ex,NotificationChannelsLoginVerification source,
                                         NotificationChannelsLoginVerification.Challenge challenge) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            requireEmailMode();requireCurrent(source);Api.clearSessionCookie(ex);
            Api.ok(ex,Map.of("status","EMAIL_BIND_CODE_REQUIRED","challenge_id",challenge.challengeId(),
                    "masked_email",challenge.maskedEmail(),"expires_at",Instant.ofEpochMilli(challenge.expiresAt()).toString(),
                    "resend_after",Instant.ofEpochMilli(challenge.resendAfter()).toString()));
        }
    }
    private static void requireCurrent(NotificationChannelsLoginVerification source) throws Api.ApiException {
        if(source!=service)throw new Api.ApiException(401,"登录核验已失效，请重新登录");
    }
    private static String id(Map<String,Object> body,String field) throws Api.ApiException {
        String value=text(body,field,64);
        if(!value.matches("[a-f0-9]{64}"))throw new Api.ApiException(400,"登录核验请求格式无效");return value;
    }
    /** Browser-origin defense, never a replacement for password/capability verification. Proxy headers are not trusted. */
    private static void requireBindingOrigin(HttpExchange ex) throws Api.ApiException {
        boolean valid=false;
        try {
            List<String> origins=ex.getRequestHeaders().get("Origin"),hosts=ex.getRequestHeaders().get("Host");
            if(origins!=null&&origins.size()==1&&hosts!=null&&hosts.size()==1) {
                String origin=origins.get(0);URI parsed=new URI(origin);
                boolean local="http".equals(parsed.getScheme())&&Set.of("localhost","127.0.0.1","[::1]","::1").contains(parsed.getHost());
                boolean scheme="https".equals(parsed.getScheme())||local&&ex.getRemoteAddress()!=null
                        &&ex.getRemoteAddress().getAddress()!=null&&ex.getRemoteAddress().getAddress().isLoopbackAddress();
                List<String> sites=ex.getRequestHeaders().get("Sec-Fetch-Site");
                valid=parsed.getHost()!=null&&scheme&&parsed.getRawUserInfo()==null&&parsed.getRawQuery()==null
                        &&parsed.getRawFragment()==null&&parsed.getRawPath().isEmpty()
                        &&origin.equals(parsed.getScheme()+"://"+parsed.getRawAuthority())
                        &&hosts.get(0).equalsIgnoreCase(parsed.getRawAuthority())
                        &&(sites==null||sites.size()==1&&"same-origin".equals(sites.get(0)));
            }
        }catch(Exception ignored){}
        if(!valid)throw new Api.ApiException(403,"请从系统原页面重新登录");
    }

    private static void fields(Map<String,Object> body, Set<String> expected) throws Api.ApiException {
        if (!body.keySet().equals(expected)) throw new Api.ApiException(400, "登录请求字段不完整或包含不允许的字段");
    }

    private static String text(Map<String,Object> body, String name, int max) throws Api.ApiException {
        Object raw = body.get(name);
        if (!(raw instanceof String value) || value.isEmpty() || value.length() > max)
            throw new Api.ApiException(400, "登录核验请求格式无效");
        return (String) raw;
    }

    /** Authentication JSON is a small, flat string object; duplicates never overwrite a proof. */
    static Map<String,Object> parseBody(String raw) throws Api.ApiException {
        try { return new Body(raw).parse(); }
        catch (Exception invalid) { throw new Api.ApiException(400, "登录请求不是有效的JSON对象"); }
    }

    private static final class Body {
        private final String value;
        private int at;
        Body(String value) {
            if (value == null || value.length() > 4096) throw new IllegalArgumentException();
            this.value = value;
        }
        Map<String,Object> parse() {
            Map<String,Object> result = new LinkedHashMap<>();
            space(); take('{'); space();
            if (!peek('}')) while (true) {
                String key = quoted(); space(); take(':'); space(); String content = quoted();
                if (result.putIfAbsent(key, content) != null) throw new IllegalArgumentException();
                space(); if (peek('}')) break; take(','); space();
            }
            take('}'); space(); if (at != value.length()) throw new IllegalArgumentException();
            return result;
        }
        String quoted() {
            int start = at; take('"');
            while (at < value.length()) {
                char c = value.charAt(at++);
                if (c == '"') return (String) Json.parse(value.substring(start, at));
                if (c < 32) throw new IllegalArgumentException();
                if (c == '\\') {
                    if (at >= value.length()) throw new IllegalArgumentException();
                    char escape = value.charAt(at++);
                    if (escape == 'u') {
                        for (int i = 0; i < 4; i++)
                            if (at >= value.length() || "0123456789abcdefABCDEF".indexOf(value.charAt(at++)) < 0) throw new IllegalArgumentException();
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException();
        }
        boolean peek(char c) { return at < value.length() && value.charAt(at) == c; }
        void take(char c) { if (!peek(c)) throw new IllegalArgumentException(); at++; }
        void space() { while (at < value.length() && " \t\r\n".indexOf(value.charAt(at)) >= 0) at++; }
    }
}
