package com.training;

import com.sun.net.httpserver.*;
import com.sun.net.httpserver.Authenticator;
import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real Auth/Api regression with synthetic users and JDK-style context-shared exchange attributes. */
public final class IntegrationApiRequestIsolationTest {
    private static final String BODY = Api.class.getName() + ".body";
    private static final String RESPONSE = Api.class.getName() + ".response";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PASSWORD = "SYNTHETIC-API-ISOLATION-ONLY";
    private static final long TIMEOUT_SECONDS = 15;
    private static int checks, scenarios;
    private static final List<String> failures = new ArrayList<>();
    private static String alphaToken, betaToken;

    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void eq(Object expected, Object actual, String message) {
        check(Objects.equals(expected, actual), message);
    }
    private static void scenario(String name, Work work) {
        scenarios++;
        try { work.run(); System.out.println("PASS " + name); }
        catch (Throwable failure) {
            failures.add(name + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            System.out.println("FAIL " + failures.get(failures.size() - 1));
        }
    }
    private static void await(CountDownLatch latch, String reason) throws InterruptedException {
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("Timed out: " + reason);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) {
        return (Map<String,Object>) value;
    }
    private static Map<String,Object> json(Exchange ex) {
        return map(Json.parse(ex.output.toString(StandardCharsets.UTF_8)));
    }
    private static Map<String,Object> data(Exchange ex) { return map(json(ex).get("data")); }
    private static String loginBody(String username) {
        return Json.write(Map.of("username", username, "password", PASSWORD));
    }
    private static Exchange exchange(Map<String,Object> shared, String method, String path, String token, String body) {
        return new Exchange(shared, method, path, token, body == null ? "" : body);
    }
    private static void response(Exchange ex, int status) {
        eq(status, ex.status, "HTTP status belongs to this request");
        eq(1, ex.sent, "exactly one response header send");
        eq("application/json; charset=utf-8", ex.responseHeaders.getFirst("Content-Type"), "JSON content type");
        eq("no-store", ex.responseHeaders.getFirst("Cache-Control"), "response is not cached");
        eq(null, ex.responseHeaders.getFirst("Content-Disposition"), "JSON cannot inherit an attachment");
        check(ex.networkOutsideLock, "all network sends and writes occur outside MUTATION_LOCK");
        if (!"HEAD".equals(ex.method)) {
            eq((long)ex.output.size(), ex.length, "byte length belongs to this response");
            eq(status == 200 ? 0 : status, ((Number)json(ex).get("code")).intValue(), "JSON status belongs to this request");
        }
    }
    private static void loginIdentity(Exchange ex, String username, long uid) {
        response(ex, 200);
        Map<String,Object> result = data(ex), user = map(result.get("user"));
        eq(username, user.get("username"), "login body keeps its own username");
        eq(uid, ((Number)user.get("uid")).longValue(), "login keeps its own account");
        String token = String.valueOf(result.get("token"));
        check(ex.responseHeaders.getFirst("Set-Cookie").startsWith("yx_session=" + token + ";"), "cookie and JSON refer to the same session");
        Auth.Session session = Auth.get(token);
        check(session != null && session.uid == uid && username.equals(session.username), "returned token authenticates only the requested account");
    }
    private static void meIdentity(Exchange ex, String username, long uid) {
        response(ex, 200);
        eq(username, data(ex).get("username"), "identity response keeps its own username");
        eq(uid, ((Number)data(ex).get("uid")).longValue(), "identity response keeps its own uid");
    }

    private static final class Running {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final Thread thread;
        Running(Exchange exchange, String name) {
            thread = new Thread(() -> {
                try { Api.handle(exchange); }
                catch (Throwable e) { failure.set(e); }
                finally { done.countDown(); }
            }, name);
            thread.setDaemon(true);
            thread.start();
        }
        void finish() throws Exception {
            await(done, "request completion");
            if (failure.get() != null) throw new AssertionError("Request worker failed", failure.get());
        }
    }

    /** Wait for a monitor acquisition, not a guessed sleep: body caching has then finished. */
    private static void waitingForRoute(Running request) throws Exception {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            ThreadInfo info = bean.getThreadInfo(request.thread.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockInfo() != null
                    && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(Api.MUTATION_LOCK)
                    && info.getLockOwnerId() == Thread.currentThread().getId()) return;
            if (request.done.getCount() == 0) throw new AssertionError("Request ended before entering route", request.failure.get());
            Thread.sleep(1);
        }
        throw new AssertionError("Request never reached the route lock after reading its body");
    }

    private static void simultaneousBodies() throws Exception {
        Map<String,Object> shared = new ConcurrentHashMap<>();
        Exchange alpha = exchange(shared, "POST", "/api/login", null, loginBody("isolation-alpha"));
        Exchange beta = exchange(shared, "POST", "/api/login", null, loginBody("isolation-beta"));
        Running a = null, b = null;
        try {
            synchronized (Api.MUTATION_LOCK) {
                a = new Running(alpha, "isolation-body-alpha");
                await(alpha.inputClosed, "alpha body fully read"); waitingForRoute(a);
                b = new Running(beta, "isolation-body-beta");
                await(beta.inputClosed, "beta body fully read"); waitingForRoute(b);
                // Both body caches exist before either route can run. The old Api loses alpha here.
            }
        } finally {
            if (a != null) a.finish();
            if (b != null) b.finish();
        }
        loginIdentity(alpha, "isolation-alpha", 101);
        loginIdentity(beta, "isolation-beta", 102);
        check(!data(alpha).get("token").equals(data(beta).get("token")), "concurrent logins produce separate sessions");
        check(!shared.containsKey(BODY) && !shared.containsKey(RESPONSE), "Api private values never enter shared context attributes");
    }

    private static final class Gate {
        final AtomicBoolean once = new AtomicBoolean();
        final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
        void enter() {
            if (!once.compareAndSet(false, true)) return;
            reached.countDown();
            try { await(release, "release first response after second response completed"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
    private static void interleavedResponses(Exchange first, Exchange second) throws Exception {
        Gate gate = new Gate(); first.flushGate = gate;
        Running a = new Running(first, "isolation-response-first");
        Running b = null;
        try {
            await(gate.reached, "first response is ready outside database lock");
            b = new Running(second, "isolation-response-second");
            b.finish(); // The entire second response must complete while the first transport is paused.
        } finally { gate.release.countDown(); a.finish(); if (b != null) b.finish(); }
        check(first.output.size() > 0 && second.output.size() > 0, "both independently buffered responses were sent");
    }
    private static void successfulResponses() throws Exception {
        Map<String,Object> shared = new ConcurrentHashMap<>();
        Exchange a = exchange(shared, "GET", "/api/me", alphaToken, null);
        Exchange b = exchange(shared, "GET", "/api/me", betaToken, null);
        interleavedResponses(a,b);
        meIdentity(a,"isolation-alpha",101); meIdentity(b,"isolation-beta",102);
    }
    private static void errorResponses(boolean firstIsError) throws Exception {
        Map<String,Object> shared = new ConcurrentHashMap<>();
        Exchange error = exchange(shared,"GET","/api/me",null,null);
        Exchange success = exchange(shared,"GET","/api/me",betaToken,null);
        interleavedResponses(firstIsError ? error : success, firstIsError ? success : error);
        response(error,401); meIdentity(success,"isolation-beta",102);
        check(!json(error).containsKey("data"), "unauthenticated response cannot inherit identity data");
        check(error.responseHeaders.getFirst("Set-Cookie").contains("Max-Age=0"), "401 preserves only its own cookie clearing");
        eq(null,success.responseHeaders.getFirst("Set-Cookie"),"other request does not inherit cookie clearing");
    }
    private static void malformedResponse() throws Exception {
        Map<String,Object> shared = new ConcurrentHashMap<>();
        Exchange bad = exchange(shared,"POST","/api/login",null,"{broken");
        Exchange good = exchange(shared,"GET","/api/me",alphaToken,null);
        interleavedResponses(bad,good);
        response(bad,400); meIdentity(good,"isolation-alpha",101);
        check(!json(bad).containsKey("data"), "malformed body cannot receive another request's successful response");
    }

    private static HttpExchange wrapped(Exchange delegate) throws Exception {
        Constructor<?> constructor = Class.forName("com.training.Api$RequestExchange").getDeclaredConstructor(HttpExchange.class);
        constructor.setAccessible(true);
        return (HttpExchange)constructor.newInstance(delegate);
    }
    private static void flush(HttpExchange exchange) throws Exception {
        Method method = Api.class.getDeclaredMethod("flushResponse",HttpExchange.class); method.setAccessible(true);
        try { method.invoke(null,exchange); }
        catch (InvocationTargetException e) { throw new AssertionError("flush failed",e.getCause()); }
    }
    private static void wrapperDelegation() throws Exception {
        Map<String,Object> shared = new ConcurrentHashMap<>();
        Map<String,Object> poison = Map.of("username","isolation-alpha");
        Object marker = new Object(); shared.put("external.marker",marker); shared.put(BODY,poison); shared.put(RESPONSE,new Object());
        Exchange original = exchange(shared,"POST","/api/login?x=one%20two",alphaToken,"{}");
        HttpExchange local = wrapped(original);
        eq(null,local.getAttribute(BODY),"new request does not inherit a previous body");
        eq(null,local.getAttribute(RESPONSE),"new request does not inherit a previous response");
        local.setAttribute(BODY,Map.of("only","local"));
        eq("local",Api.body(local).get("only"),"Api.body reads the request-local cache");
        check(shared.get(BODY) == poison,"local body write does not overwrite context value");
        check(local.getAttribute("external.marker") == marker,"unrelated attribute reads delegate");
        local.setAttribute("external.new",marker);
        check(shared.get("external.new") == marker,"unrelated attribute writes delegate");
        check(local.getRequestHeaders()==original.requestHeaders && local.getResponseHeaders()==original.responseHeaders,"header objects delegate without copying");
        eq(original.uri,local.getRequestURI(),"URI and query delegate");
        eq(original.method,local.getRequestMethod(),"method delegates");
        eq(original.getProtocol(),local.getProtocol(),"protocol delegates");
        check(local.getHttpContext()==original.context,"context delegates");
        check(local.getPrincipal()==original.principal,"principal delegates");
        eq(original.remote,local.getRemoteAddress(),"remote address delegates");
        eq(original.local,local.getLocalAddress(),"local address delegates");
        check(local.getRequestBody()==original.input && local.getResponseBody()==original.responseBody,"streams delegate");
        InputStream replacementIn=new ByteArrayInputStream(new byte[0]); OutputStream replacementOut=new ByteArrayOutputStream();
        local.setStreams(replacementIn,replacementOut);
        check(original.input==replacementIn && original.responseBody==replacementOut,"setStreams delegates");
        local.sendResponseHeaders(202,-1); eq(202,local.getResponseCode(),"status and sendResponseHeaders delegate");
        local.close(); check(original.closed,"close delegates");
        // Existing module tests still deliberately use Api.body/ok directly on their own fake exchanges.
        Exchange legacy=exchange(new HashMap<>(),"POST","/api/login",null,"{}");
        legacy.setAttribute(BODY,poison); check(Api.body(legacy)==poison,"direct module fake body compatibility");
        Api.ok(legacy,Map.of("synthetic",true)); flush(legacy); response(legacy,200);
        eq(Boolean.TRUE,data(legacy).get("synthetic"),"direct module fake response compatibility");
    }

    private static void binaryAndHead() throws Exception {
        Map<String,Object> shared = new ConcurrentHashMap<>();
        Exchange download=exchange(shared,"GET","/synthetic-download",null,null);
        Exchange denied=exchange(shared,"GET","/synthetic-denied",null,null);
        HttpExchange file=wrapped(download),error=wrapped(denied);
        byte[] bytes=new byte[]{0x50,0x4b,3,4,0,(byte)0xff,10}; byte[] expected=bytes.clone();
        synchronized(Api.MUTATION_LOCK) { Api.file(file,bytes,XLSX,"synthetic_test.xlsx"); Api.err(error,403,"SYNTHETIC DENIED"); }
        bytes[0]=0; // Buffered file must own its bytes.
        flush(error); flush(file);
        response(denied,403);
        eq(200,download.status,"binary status stays with file request");
        check(Arrays.equals(expected,download.output.toByteArray()),"binary body preserved and defensively copied");
        eq(XLSX,download.responseHeaders.getFirst("Content-Type"),"binary MIME type preserved");
        eq("attachment; filename=\"synthetic_test.xlsx\"",download.responseHeaders.getFirst("Content-Disposition"),"attachment belongs only to file request");
        eq("nosniff",download.responseHeaders.getFirst("X-Content-Type-Options"),"download protection header preserved");
        eq((long)expected.length,download.length,"exact binary length"); check(download.networkOutsideLock,"file flushed outside lock");
        Exchange head=exchange(shared,"HEAD","/api/me",alphaToken,null); Api.handle(head); response(head,200);
        eq(-1L,head.length,"HEAD sends no body length"); eq(0,head.output.size(),"HEAD writes no body");
        Exchange missing=exchange(shared,"GET","/synthetic-missing",null,null); flush(wrapped(missing)); response(missing,500);
        check(!json(missing).containsKey("data"),"missing response fails closed instead of reusing file or identity");
    }

    private static void freshAndBadBodies() throws Exception {
        Map<String,Object> shared=new ConcurrentHashMap<>();
        Exchange good=exchange(shared,"POST","/api/login",null,loginBody("isolation-alpha")); Api.handle(good); loginIdentity(good,"isolation-alpha",101);
        Exchange empty=exchange(shared,"POST","/api/login",null,""); Api.handle(empty); response(empty,400);
        Exchange invalid=exchange(shared,"POST","/api/login",null,"[]"); Api.handle(invalid); response(invalid,400);
        Exchange contentType=exchange(shared,"POST","/api/login",null,loginBody("isolation-beta")); contentType.requestHeaders.set("Content-Type","text/plain");
        Api.handle(contentType); response(contentType,415);
        check(!json(empty).containsKey("data") && !json(invalid).containsKey("data"),"empty and invalid bodies never inherit a successful login");
    }

    private static final class Exchange extends HttpExchange {
        final Map<String,Object> attributes;
        final Headers requestHeaders=new Headers(),responseHeaders=new Headers();
        final URI uri; final String method;
        final CountDownLatch inputClosed=new CountDownLatch(1);
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final InetSocketAddress remote=new InetSocketAddress("127.0.0.1",12001),local=new InetSocketAddress("127.0.0.1",12002);
        final HttpPrincipal principal=new HttpPrincipal("synthetic-principal","test-realm");
        final HttpContext context;
        InputStream input; OutputStream responseBody;
        volatile Gate flushGate;
        int status=-1,sent; long length; boolean closed,networkOutsideLock=true;
        Exchange(Map<String,Object> attributes,String method,String path,String token,String body) {
            this.attributes=attributes; this.method=method; this.uri=URI.create(path); this.context=new Context(attributes);
            requestHeaders.set("Host","localhost");
            if("POST".equals(method))requestHeaders.set("Content-Type","application/json");
            if(token!=null)requestHeaders.set("X-Token",token);
            input=new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)) {
                @Override public synchronized int read(byte[] bytes,int off,int len) {
                    if(Thread.holdsLock(Api.MUTATION_LOCK))throw new AssertionError("request body read under database lock");
                    return super.read(bytes,off,len);
                }
                @Override public void close() throws IOException { super.close(); inputClosed.countDown(); }
            };
            responseBody=new OutputStream() {
                @Override public void write(int value) throws IOException { transport(); output.write(value); }
                @Override public void write(byte[] bytes,int off,int len) throws IOException { transport(); output.write(bytes,off,len); }
            };
        }
        private void transport() throws IOException {
            if(Thread.holdsLock(Api.MUTATION_LOCK)){networkOutsideLock=false;throw new IOException("network I/O under database lock");}
        }
        private void gate() { Gate pending=flushGate; if(pending!=null && !Thread.holdsLock(Api.MUTATION_LOCK))pending.enter(); }
        public Headers getRequestHeaders(){return requestHeaders;}
        public Headers getResponseHeaders(){gate();return responseHeaders;}
        public URI getRequestURI(){return uri;}
        public String getRequestMethod(){return method;}
        public HttpContext getHttpContext(){return context;}
        public void close(){closed=true;}
        public InputStream getRequestBody(){return input;}
        public OutputStream getResponseBody(){return responseBody;}
        public void sendResponseHeaders(int code,long size)throws IOException{transport();status=code;length=size;sent++;}
        public InetSocketAddress getRemoteAddress(){return remote;}
        public int getResponseCode(){return status;}
        public InetSocketAddress getLocalAddress(){return local;}
        public String getProtocol(){return "HTTP/1.1";}
        public Object getAttribute(String key){if(RESPONSE.equals(key))gate();return attributes.get(key);}
        public void setAttribute(String key,Object value){if(value==null)attributes.remove(key);else attributes.put(key,value);}
        public void setStreams(InputStream in,OutputStream out){if(in!=null)input=in;if(out!=null)responseBody=out;}
        public HttpPrincipal getPrincipal(){return principal;}
    }
    private static final class Context extends HttpContext {
        final Map<String,Object> attributes; final List<Filter> filters=new ArrayList<>(); HttpHandler handler; Authenticator authenticator;
        Context(Map<String,Object> attributes){this.attributes=attributes;}
        public HttpHandler getHandler(){return handler;}
        public void setHandler(HttpHandler handler){this.handler=handler;}
        public String getPath(){return "/api/";}
        public HttpServer getServer(){return null;}
        public Map<String,Object> getAttributes(){return attributes;}
        public List<Filter> getFilters(){return filters;}
        public Authenticator setAuthenticator(Authenticator value){Authenticator previous=authenticator;authenticator=value;return previous;}
        public Authenticator getAuthenticator(){return authenticator;}
    }

    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("A fresh empty temporary directory is required");
        Path supplied=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isDirectory(supplied)||Files.isSymbolicLink(supplied))throw new IllegalArgumentException("A real empty temporary directory is required");
        Path directory=supplied.toRealPath(),tmp=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if(!directory.startsWith(tmp)&&!directory.startsWith(Path.of("/tmp").toRealPath()))throw new IllegalArgumentException("Synthetic fixture must be inside a temporary directory");
        try(var entries=Files.list(directory)){if(entries.findAny().isPresent())throw new IllegalArgumentException("Fixture directory must be empty");}
        System.setProperty("data.dir",directory.toString());System.setProperty("bootstrap.demo","false");
        try {
            Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
            String hash=Auth.hash(PASSWORD);
            Db.exec("INSERT INTO users VALUES(101,'isolation-alpha',?,'SYNTHETIC ALPHA','viewer',1)",hash);
            Db.exec("INSERT INTO users VALUES(102,'isolation-beta',?,'SYNTHETIC BETA','viewer',1)",hash);
            alphaToken=Auth.login("isolation-alpha",PASSWORD);betaToken=Auth.login("isolation-beta",PASSWORD);
            check(alphaToken!=null&&betaToken!=null,"real Auth sessions from isolated synthetic users");
            scenario("simultaneous cached login bodies",IntegrationApiRequestIsolationTest::simultaneousBodies);
            scenario("successful identity responses interleaved",IntegrationApiRequestIsolationTest::successfulResponses);
            scenario("401 then successful response interleaved",()->errorResponses(true));
            scenario("successful then 401 response interleaved",()->errorResponses(false));
            scenario("malformed body response interleaved",IntegrationApiRequestIsolationTest::malformedResponse);
            scenario("fresh empty invalid and content-type bodies",IntegrationApiRequestIsolationTest::freshAndBadBodies);
            scenario("request-local attributes and complete delegation",IntegrationApiRequestIsolationTest::wrapperDelegation);
            scenario("binary JSON HEAD and missing-response separation",IntegrationApiRequestIsolationTest::binaryAndHead);
        } finally { Db.get().close(); }
        System.out.println("IntegrationApiRequestIsolation: "+checks+" checks, "+scenarios+" scenarios, "+failures.size()+" failures (real Auth/Api; isolated synthetic H2)");
        if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
    }
}
