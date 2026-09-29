package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** M07 authenticated, bounded, in-memory preview. No DDL, write, upload log or formal-result API. */
public final class SurveySummaryImportsIntegration {
    private SurveySummaryImportsIntegration() {}
    public static final String BASE = "/api/survey-response-imports";
    public static final String RESOURCE = "survey.preview";
    public static final int MAX_BODY_BYTES = 7 * 1024 * 1024;
    public static final int MAX_BASE64_CHARS = ((SurveySummaryImportsResponses.MAX_BYTES + 2) / 3) * 4;
    public static final int MAX_CONCURRENT_PREVIEWS = 2;
    public static final long MAX_REQUEST_MILLIS = 15000;
    private static final long MAX_SAFE_ID = 9007199254740991L;
    private static final int MAX_PROJECTS = 10000;
    private static final String POLICY_VERSION = "M07-DRAFT-20260922-1";
    private static final SurveySummaryImportsResponses.DraftRules RULES = SurveySummaryImportsResponses.DraftRules.proposed();
    private static final Semaphore SLOTS = new Semaphore(MAX_CONCURRENT_PREVIEWS);
    private static final Set<Long> ACTIVE_ACCOUNTS = ConcurrentHashMap.newKeySet();
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();
    private static final Set<String> INPUT_KEYS = Set.of("fileName", "xlsxBase64", "projectId");
    private static final String SOURCE_SQL = "SELECT p.id,p.demand_id AS project_demand_id,p.title,p.status," +
            "a.demand_id,a.data_revision AS accepted_revision,a.team_code,a.actor_code,a.created_at," +
            "w.organization_code,w.data_revision AS current_revision,w.version AS workflow_version,w.draft " +
            "FROM projects p JOIN workflow_acceptances a ON a.project_id=p.id " +
            "JOIN workflow_demands w ON w.demand_id=a.demand_id ";

    public static boolean matches(String path) { return BASE.equals(path) || (path != null && path.startsWith(BASE + "/")); }

    /**
     * Mount BEFORE Api's generic body reader and outside its route-wide MUTATION_LOCK.
     * Stages Api.ok; the host must call its usual flushResponse immediately afterwards.
     */
    public static boolean handle(HttpExchange ex, Auth.Session supplied) throws Exception {
        if (!matches(ex.getRequestURI().getPath())) return false;
        outsideLock();
        try {
            synchronized (Api.MUTATION_LOCK) { httpSession(ex, supplied); }
            if (ex.getRequestURI().getRawQuery() != null) throw failure(400, "本接口不接受查询参数");
            String path = ex.getRequestURI().getPath();
            if (path.equals(BASE + "/config")) {
                method(ex, "GET");
                synchronized (Api.MUTATION_LOCK) {
                    httpSession(ex, supplied);
                    Api.ok(ex, configuration(supplied, new Budget(() -> false)));
                }
                return true;
            }
            boolean formal = path.equals(BASE + "/prepare");
            if (!formal && !path.equals(BASE + "/preview")) throw failure(404, "评分预览接口不存在");
            method(ex, "POST");
            Budget budget = new Budget(() -> false);
            Access start;
            synchronized (Api.MUTATION_LOCK) { start = access(supplied, budget); }
            // Reserve before reading: no upload queue and at most one active upload/parse per account.
            try (Lease lease = reserve(start.account()); Budget ignored = budget) {
                budget.watch(ex);
                Map<String, Object> input = readBody(ex, budget, formal ? 5 : 3);
                if (formal) {
                    SurveySummaryImportsFormal.prepareParsed(supplied, input, budget, result -> { budget.run(); Api.ok(ex, result); });
                    return true;
                }
                Outcome result = evaluate(supplied, start, input, budget);
                synchronized (Api.MUTATION_LOCK) {
                    budget.run();
                    httpSession(ex, supplied);
                    revalidate(supplied, result.snapshot(), budget);
                    Api.ok(ex, result.view());
                }
            }
            return true;
        } catch (Stop stop) { throw stopped(stop); }
        catch (CancellationException cancelled) { throw failure(499, "评分预览已取消"); }
        catch (Api.ApiException safe) { throw safe; }
        catch (Exception unexpected) { throw failure(503, "暂时无法核实或生成评分预览，请稍后重试"); }
    }

    public static Map<String, Object> config(Auth.Session session) throws Exception {
        try {
            synchronized (Api.MUTATION_LOCK) { return configuration(session, new Budget(() -> false)); }
        } catch (Stop stop) { throw stopped(stop); }
        catch (Api.ApiException safe) { throw safe; }
        catch (Exception unexpected) { throw failure(503, "暂时无法核实评分预览范围，请稍后重试"); }
    }

    public static Map<String, Object> preview(Auth.Session session, Map<String, ?> input) throws Exception {
        return preview(session, input, () -> false);
    }

    /** Host/test cancellation signal; no browser rules, grants or histories are accepted. */
    static Map<String, Object> preview(Auth.Session session, Map<String, ?> input, BooleanSupplier cancelled) throws Exception {
        outsideLock();
        try (Budget budget = new Budget(cancelled)) {
            Access start;
            synchronized (Api.MUTATION_LOCK) { start = access(session, budget); }
            try (Lease lease = reserve(start.account())) {
                Outcome result = evaluate(session, start, input, budget);
                synchronized (Api.MUTATION_LOCK) {
                    revalidate(session, result.snapshot(), budget);
                    return result.view();
                }
            }
        } catch (Stop stop) { throw stopped(stop); }
        catch (CancellationException cancelledFailure) { throw failure(499, "评分预览已取消"); }
        catch (Api.ApiException safe) { throw safe; }
        catch (Exception unexpected) { throw failure(503, "暂时无法核实或生成评分预览，请稍后重试"); }
    }

    private record Access(long account, String person, String configVersion, Set<String> organizations) {}
    static record Source(long id, long demand, long revision, long workflowVersion, String org,
            String title, String status, String team, String actor, String acceptedAt) {
        SurveySummaryImports.Project project() { return new SurveySummaryImports.Project(id, Long.toString(id), title); }
    }
    private record Snapshot(Access access, Source source, String policyVersion) {}
    private record Outcome(Snapshot snapshot, Map<String, Object> view) {}

    private static Access access(Auth.Session supplied, Budget budget) throws Exception {
        budget.run();
        Auth.Session current = Auth.current(supplied);
        if (current == null) throw failure(401, "登录会话无效或已失效");
        if (!Db.get().getAutoCommit()) throw failure(409, "评分预览不能在未完成的业务事务中执行");
        OrganizationAccess.Person person = OrganizationAccessStore.person(current);
        OrganizationAccess.Configuration config = OrganizationAccessStore.configuration();
        if (config == null) throw failure(403, "评分预览权限尚未配置");
        Set<String> allowed = new TreeSet<>();
        for (OrganizationAccess.Organization org : config.organizations()) {
            budget.run();
            if (OrganizationAccessStore.authorize(current, RESOURCE, OrganizationAccess.Action.HANDLE, org.organizationCode()).allowed())
                allowed.add(org.organizationCode());
        }
        if (allowed.isEmpty()) throw failure(403, "没有获授权的评分预览项目范围");
        return new Access(current.uid, person.personCode(), config.version(), Set.copyOf(allowed));
    }

    private static Map<String, Object> configuration(Auth.Session supplied, Budget budget) throws Exception {
        Access before = access(supplied, budget);
        List<Map<String, Object>> projects = new ArrayList<>();
        String scopeParameters = String.join(",", Collections.nCopies(before.organizations().size(), "?"));
        List<Map<String, Object>> sourceRows = Db.query(SOURCE_SQL +
                "WHERE w.organization_code IN (" + scopeParameters + ") AND p.demand_id=a.demand_id " +
                "AND a.data_revision=w.data_revision AND w.draft=FALSE ORDER BY p.id LIMIT " + (MAX_PROJECTS + 1),
                before.organizations().toArray());
        if (sourceRows.size() > MAX_PROJECTS) throw failure(503, "可预览项目范围过大，请联系系统维护人员");
        for (Map<String, Object> row : sourceRows) {
            budget.run();
            Source src = validSource(row);
            if (src != null && before.organizations().contains(src.org()))
                projects.add(Map.of("id", src.id(), "key", Long.toString(src.id()), "name", src.title()));
        }
        if (!before.equals(access(supplied, budget))) throw failure(409, "评分预览权限配置已变化，请重新读取");
        return map("ready", true, "synthetic", false, "adapterId", SurveySummaryImportsResponses.ADAPTER_ID,
                "maxBytes", SurveySummaryImportsResponses.MAX_BYTES, "projects", projects, "rulesDraft", RULES.toMap(),
                "policyVersion", POLICY_VERSION, "canCommit", false, "policyConfirmed", false, "historyAvailable", false);
    }

    private static Outcome evaluate(Auth.Session supplied, Access admission, Map<String, ?> input, Budget budget) throws Exception {
        return evaluate(supplied, admission, input, budget, RULES, false);
    }
    private static Outcome evaluate(Auth.Session supplied, Access admission, Map<String, ?> input, Budget budget,
            SurveySummaryImportsResponses.DraftRules rules, boolean fingerprints) throws Exception {
        budget.run();
        if (input == null || !INPUT_KEYS.equals(input.keySet())) throw failure(400, "请求仅接受文件名、评分文件和项目编号");
        long projectId = projectId(input.get("projectId"));
        Snapshot before;
        synchronized (Api.MUTATION_LOCK) {
            Access current = access(supplied, budget);
            if (!admission.equals(current)) throw failure(409, "评分预览权限配置已变化，请重新选择文件");
            Source source = authorizedSource(supplied, projectId, current);
            before = new Snapshot(current, source, POLICY_VERSION);
        }
        // Decode only after project authorization. Filename is validated, never stored, logged or returned.
        Object name = input.get("fileName");
        if (!(name instanceof String fileName) || fileName.isBlank() || fileName.length() > 255
                || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx") || fileName.chars().anyMatch(c -> c < 32 || c == 127))
            throw failure(400, "请选择原平台导出的.xlsx评分文件");
        byte[] bytes = decode(input.get("xlsxBase64"), budget);
        Map<String, Object> result;
        try {
            result = SurveySummaryImportsResponses.preview(bytes, projectId, rules,
                    new SurveySummaryImportsResponses.PreviewContext(true, List.of(before.source().project()), List.of(), false, false), budget);
        } finally { Arrays.fill(bytes, (byte) 0); }
        budget.run();
        // Hashes have no client use until a trusted persisted history exists.
        if (!fingerprints) { result.remove("fileFingerprint"); result.remove("scoreFingerprint"); }
        result.put("historyAvailable", false); result.put("policyVersion", POLICY_VERSION);
        synchronized (Api.MUTATION_LOCK) { revalidate(supplied, before, budget); }
        return new Outcome(before, result);
    }

    private static void revalidate(Auth.Session supplied, Snapshot expected, Budget budget) throws Exception {
        Access current = access(supplied, budget);
        Source source = authorizedSource(supplied, expected.source().id(), current);
        if (!current.equals(expected.access()) || !source.equals(expected.source()) || !POLICY_VERSION.equals(expected.policyVersion()))
            throw failure(409, "项目来源或评分预览配置已变化，请重新生成草案");
        budget.run();
    }

    private static Source authorizedSource(Auth.Session supplied, long id, Access access) throws Exception {
        Source src = trustedSource(id);
        if (src == null || !access.organizations().contains(src.org())
                || !OrganizationAccessStore.authorize(supplied, RESOURCE, OrganizationAccess.Action.HANDLE, src.org()).allowed())
            throw failure(403, "项目没有可核实且获授权的评分预览来源");
        return src;
    }

    static Source trustedSource(long id) throws Exception {
        List<Map<String,Object>> rows = Db.query(SOURCE_SQL + "WHERE p.id=?", id);
        Source src = rows.size() == 1 ? validSource(rows.get(0)) : null;
        if (src == null) throw failure(403, "项目没有可核实且获授权的评分预览来源");
        return src;
    }

    static Map<String,Object> parseForConfirmation(Auth.Session s, Map<String,?> input,
            SurveySummaryImportsResponses.DraftRules rules) throws Exception {
        outsideLock();
        try (Budget budget = new Budget(() -> false)) {
            Access start;
            synchronized(Api.MUTATION_LOCK) { start = access(s,budget); }
            try (Lease lease = reserve(start.account())) { return evaluate(s,start,input,budget,rules,true).view(); }
        } catch (Stop stop) { throw stopped(stop); }
    }

    static Map<String,Object> parseWithBudget(Auth.Session s,Map<String,?> input,
            SurveySummaryImportsResponses.DraftRules rules,Runnable checkpoint) throws Exception {
        if (!(checkpoint instanceof Budget budget)) throw failure(503,"评分预览预算无效");
        Access start; synchronized(Api.MUTATION_LOCK) { start=access(s,budget); }
        return evaluate(s,start,input,budget,rules,true).view();
    }

    private static Source validSource(Map<String, Object> row) {
        try {
            long id = positive(row.get("id")), demand = positive(row.get("demand_id"));
            long revision = positive(row.get("accepted_revision")), version = positive(row.get("workflow_version"));
            if (id > MAX_SAFE_ID || demand != positive(row.get("project_demand_id"))
                    || revision != positive(row.get("current_revision")) || !Boolean.FALSE.equals(row.get("draft"))) return null;
            String org = sourceText(row, "organization_code", 120), title = sourceText(row, "title", 200);
            return new Source(id, demand, revision, version, org, title, sourceText(row, "status", 32),
                    sourceText(row, "team_code", 120), sourceText(row, "actor_code", 120), sourceText(row, "created_at", 40));
        } catch (RuntimeException invalid) { return null; }
    }

    private static String sourceText(Map<String, Object> row, String key, int max) {
        Object value = row.get(key);
        if (!(value instanceof String s) || s.isBlank() || s.length() > max) throw new IllegalArgumentException();
        return s;
    }
    private static long positive(Object value) {
        long n = new BigDecimal(Objects.requireNonNull(value).toString()).longValueExact();
        if (n <= 0) throw new IllegalArgumentException();
        return n;
    }
    private static long projectId(Object value) throws Api.ApiException {
        try {
            if (!(value instanceof Number)) throw new IllegalArgumentException();
            long id = positive(value);
            if (id > MAX_SAFE_ID) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException invalid) { throw failure(400, "请选择有效的项目编号"); }
    }

    private static byte[] decode(Object value, Budget budget) throws Api.ApiException {
        if (!(value instanceof String s) || s.isEmpty()) throw failure(400, "评分文件内容为空或格式不正确");
        if (s.length() > MAX_BASE64_CHARS) throw failure(413, "评分文件超过5 MiB上限");
        if (s.length() % 4 != 0) throw failure(400, "评分文件编码不正确");
        for (int i = 0; i < s.length(); i++) {
            if ((i & 8191) == 0) budget.run();
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/'
                    || (c == '=' && i >= s.length() - 2))) throw failure(400, "评分文件编码不正确");
        }
        byte[] result = null;
        try {
            result = Base64.getDecoder().decode(s);
            if (result.length == 0 || result.length > SurveySummaryImportsResponses.MAX_BYTES) {
                Arrays.fill(result, (byte) 0); throw failure(413, "评分文件超过5 MiB上限或为空");
            }
            budget.run();
            return result;
        } catch (IllegalArgumentException invalid) { throw failure(400, "评分文件编码不正确"); }
        catch (CancellationException stopped) { if (result != null) Arrays.fill(result, (byte) 0); throw stopped; }
    }

    private static Map<String, Object> readBody(HttpExchange ex, Budget budget, int fields) throws Exception {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !"application/json".equalsIgnoreCase(ct.split(";", 2)[0].trim()))
            throw failure(415, "评分预览只接受application/json请求");
        String encoding = ex.getRequestHeaders().getFirst("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw failure(415, "评分预览不接受压缩请求正文");
        String length = ex.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                long n = Long.parseLong(length);
                if (n < 0) throw new NumberFormatException();
                if (n > MAX_BODY_BYTES) throw failure(413, "评分预览请求超过7 MiB上限");
            } catch (NumberFormatException invalid) { throw failure(400, "请求长度无效"); }
        }
        byte[] raw = null;
        try (WipingBuffer out = new WipingBuffer()) {
            InputStream in = ex.getRequestBody();
            byte[] block = new byte[8192];
            try {
                int n;
                while (true) {
                    budget.run();
                    n = in.read(block);
                    budget.run();
                    if (n < 0) break;
                    if (n == 0) continue;
                    if (out.size() > MAX_BODY_BYTES - n) throw failure(413, "评分预览请求超过7 MiB上限");
                    out.write(block, 0, n);
                }
                raw = out.toByteArray();
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
                budget.run();
                flatRequest(json, budget, fields);
                Object parsed = Json.parse(json);
                budget.run();
                if (!(parsed instanceof Map<?, ?>)) throw failure(400, "评分预览请求必须是JSON对象");
                @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) parsed;
                return object;
            } catch (CharacterCodingException | IllegalArgumentException invalid) {
                throw failure(400, "评分预览请求不是有效的JSON");
            } catch (IOException disconnected) {
                budget.run(); throw failure(499, "评分文件上传已中断");
            } finally { Arrays.fill(block, (byte) 0); if (raw != null) Arrays.fill(raw, (byte) 0); }
        }
    }

    /** The upload contract is exactly three scalars; reject object/array expansion before Json allocates it. */
    private static void flatRequest(String json, Budget budget, int fields) throws Api.ApiException {
        boolean quoted = false, escaped = false;
        int opens = 0, commas = 0;
        for (int i = 0; i < json.length(); i++) {
            if ((i & 8191) == 0) budget.run();
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '[' || c == ']' || (c == '{' && ++opens > 1) || (c == ',' && ++commas >= fields))
                throw failure(400, "评分预览请求字段不符合约定");
        }
    }

    private static final class WipingBuffer extends ByteArrayOutputStream {
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
    private static void httpSession(HttpExchange ex, Auth.Session supplied) throws Api.ApiException {
        Auth.Session current = Auth.get(Api.token(ex));
        if (current == null || current != supplied || Auth.current(current) == null) throw failure(401, "登录会话无效或已失效");
    }
    private static void outsideLock() throws Api.ApiException {
        if (Thread.holdsLock(Api.MUTATION_LOCK)) throw failure(503, "评分预览需由独立请求入口接入");
    }
    private static void method(HttpExchange ex, String expected) throws Api.ApiException {
        if (!expected.equals(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Allow", expected); throw failure(405, "请求方法不受支持");
        }
    }
    private static Lease reserve(long account) throws Api.ApiException {
        if (!ACTIVE_ACCOUNTS.add(account)) throw failure(429, "已有评分预览正在处理，请稍后重试");
        if (!SLOTS.tryAcquire()) { ACTIVE_ACCOUNTS.remove(account); throw failure(429, "评分预览正在处理其他请求，请稍后重试"); }
        return new Lease(account);
    }
    private record Lease(long account) implements AutoCloseable {
        @Override public void close() { ACTIVE_ACCOUNTS.remove(account); SLOTS.release(); }
    }
    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(MAX_CONCURRENT_PREVIEWS, task -> {
            Thread thread = new Thread(task, "m07-preview-deadline"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true); return executor;
    }
    private static final class Stop extends CancellationException {
        private static final long serialVersionUID = 1L;
        final boolean timeout;
        Stop(boolean timeout) { super("M07 preview stopped"); this.timeout = timeout; }
    }
    private static final class Budget implements Runnable, AutoCloseable {
        private final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_REQUEST_MILLIS);
        private final BooleanSupplier cancelled;
        // OPEN=0, FINISHED=1, EXPIRED=2: completion and watchdog must have a single winner.
        private final AtomicInteger state = new AtomicInteger();
        private ScheduledFuture<?> alarm;
        Budget(BooleanSupplier cancelled) { this.cancelled = Objects.requireNonNull(cancelled); }
        void watch(HttpExchange ex) {
            alarm = WATCHDOG.schedule(() -> {
                if (state.compareAndSet(0, 2)) ex.close();
            }, Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        @Override public void run() {
            if (state.get() == 2 || System.nanoTime() - deadline >= 0) throw new Stop(true);
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new Stop(false);
            if (state.get() == 2 || System.nanoTime() - deadline >= 0) throw new Stop(true);
        }
        @Override public void close() { state.compareAndSet(0, 1); if (alarm != null) alarm.cancel(false); }
    }
    private static Api.ApiException stopped(Stop stop) { return failure(stop.timeout ? 408 : 499, stop.timeout ? "评分预览超时，请稍后重试" : "评分预览已取消"); }
    private static Api.ApiException failure(int code, String message) { return new Api.ApiException(code, message); }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }
}
