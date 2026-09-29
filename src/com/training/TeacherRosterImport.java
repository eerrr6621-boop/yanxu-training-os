package com.training;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Trusted roster intake only. Does not publish roles, activate accounts or certify courses. */
public final class TeacherRosterImport {
    private static final long MAX_ID = 9007199254740991L;
    private static final long REVIEW_LIFETIME_MS = 2 * 60 * 60 * 1000L;
    private static final Set<String> REQUEST_KEYS = Set.of("batchKey", "sourceFingerprint", "reviewToken");
    private final Config config;
    // Access only under the shared business lock; never serialize session references.
    private final Map<String,Review> reviews = new LinkedHashMap<>();
    private record Review(Auth.Session session, String batchKey, String fingerprint, String state, long expires) {}
    private record Account(long id, String personCode) {}
    private record Source(Snapshot snapshot, List<TeacherProfile> profiles, Map<String,Account> accounts,
                          Map<String,Object> summary, String profilesDigest, String accountsDigest) {}
    private record Stored(Map<String,Long> teachers, Map<Long,Object> statuses, Map<String,Object> receipt) {}

    public TeacherRosterImport(Config config) { this.config = Objects.requireNonNull(config); }

    /** Empty metadata only; initialize after teachers/users/account-intake tables, outside a transaction. */
    public static void init() throws SQLException {
        synchronized (Api.MUTATION_LOCK) {
            if (!Db.get().getAutoCommit()) throw new IllegalStateException("教师接收建表不能嵌入事务");
            Db.exec("CREATE TABLE IF NOT EXISTS teacher_roster_import_batches (" +
                    "batch_key VARCHAR(128) PRIMARY KEY,source_fingerprint CHAR(64) NOT NULL," +
                    "profiles_digest CHAR(64) NOT NULL,accounts_digest CHAR(64) NOT NULL," +
                    "receipt CLOB NOT NULL,created_by BIGINT NOT NULL,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            Db.exec("CREATE TABLE IF NOT EXISTS teacher_roster_import_people (" +
                    "batch_key VARCHAR(128) NOT NULL REFERENCES teacher_roster_import_batches(batch_key)," +
                    "source_reference VARCHAR(128) NOT NULL,source_fingerprint CHAR(64) NOT NULL," +
                    "account_id BIGINT NOT NULL UNIQUE REFERENCES users(id)," +
                    "person_code VARCHAR(64) NOT NULL UNIQUE REFERENCES organization_account_import_people(person_code)," +
                    "teacher_id BIGINT NOT NULL UNIQUE REFERENCES teachers(id)," +
                    "initial_facts CLOB NOT NULL,initial_digest CHAR(64) NOT NULL,created_by BIGINT NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(batch_key,source_reference))");
        }
    }

    public Map<String,Object> preview(Auth.Session supplied) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session actor = requireAdmin(supplied);
            committed();
            Source source = source(actor);
            Stored stored = stored(source);
            List<Map<String,Object>> rows = new ArrayList<>();
            for (TeacherProfile profile : source.profiles) {
                Long teacher = stored == null ? null : stored.teachers.get(profile.reference());
                rows.add(map("reference",profile.reference(),"name",profile.name(),"organization",profile.organization(),
                        "job",profile.job(),"teacherLevel",profile.teacherLevel(),"accountId",source.accounts.get(profile.reference()).id,
                        "teacherId",teacher,"status",teacher == null ? "待接收" : stored.statuses.get(teacher)));
            }
            String token = null;
            requireAdmin(actor);
            if (stored == null) {
                long now = System.currentTimeMillis();
                reviews.entrySet().removeIf(e -> e.getValue().expires <= now || Auth.current(e.getValue().session) == null);
                if (reviews.size() >= 256) reviews.remove(reviews.keySet().iterator().next());
                token = Auth.randomToken(32);
                reviews.put(token,new Review(actor,source.snapshot.batchKey(),source.snapshot.fingerprint(),state(),now + REVIEW_LIFETIME_MS));
            }
            return map("batchKey",source.snapshot.batchKey(),"sourceFingerprint",source.snapshot.fingerprint(),
                    "imported",stored != null,"reviewToken",token,"rows",rows,"summary",new LinkedHashMap<>(source.summary));
        }
    }

    public Map<String,Object> commit(Auth.Session supplied, Map<String,Object> body) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session actor = requireAdmin(supplied);
            committed();
            if (body == null || !body.keySet().equals(REQUEST_KEYS)) throw bad("教师接收请求只允许批次、来源摘要和核对票据");
            String batch = batchText(body.get("batchKey")), fingerprint = hashText(body.get("sourceFingerprint"));
            String token = body.get("reviewToken") == null ? null : hashText(body.get("reviewToken"));
            Source source = source(actor);
            if (!source.snapshot.batchKey().equals(batch) || !source.snapshot.fingerprint().equals(fingerprint))
                throw conflict("批次或来源版本已变化，请重新预览");
            Stored existing = stored(source);
            // A completed command is read-only; a fresh admin session can replay it after a process restart.
            if (existing != null) {
                requireAdmin(actor);
                return replay(existing.receipt);
            }
            Review review = token == null ? null : reviews.get(token);
            reviewed(actor,source,review);
            try {
                return Db.transaction(() -> {
                    requireAdmin(actor);
                    Source fresh = source(actor); // Re-read every fixed pin and the complete account receipt.
                    if (!fresh.snapshot.batchKey().equals(batch) || !fresh.snapshot.fingerprint().equals(fingerprint))
                        throw conflict("来源已变化，请重新预览");
                    if (stored(fresh) != null) throw conflict("教师接收状态已变化，请重新预览");
                    reviewed(actor,fresh,review);
                    List<Map<String,Object>> receiptRows = new ArrayList<>();
                    Map<String,Long> teacherIds = new LinkedHashMap<>();
                    for (TeacherProfile profile : fresh.profiles) {
                        long id = Db.insert("INSERT INTO teachers(name,org,title,teacher_level,status," +
                                        "gender,field,phone,email,fee_rate,intro,in_date,out_date,base_province,base_city) " +
                                        "VALUES(?,?,?,?,'待完善',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
                                profile.name(),profile.organization(),profile.job(),profile.teacherLevel());
                        if (id <= 0 || id > MAX_ID) throw conflict("无法核实新教师编号，已撤回本批");
                        Account account = fresh.accounts.get(profile.reference());
                        teacherIds.put(profile.reference(),id);
                        receiptRows.add(map("reference",profile.reference(),"accountId",account.id,"teacherId",id));
                    }
                    Map<String,Object> receipt = receipt(fresh,receiptRows,false);
                    Db.exec("INSERT INTO teacher_roster_import_batches(batch_key,source_fingerprint,profiles_digest,accounts_digest,receipt,created_by) VALUES(?,?,?,?,?,?)",
                            batch,fingerprint,fresh.profilesDigest,fresh.accountsDigest,Json.write(receipt),actor.uid);
                    for (TeacherProfile profile : fresh.profiles) {
                        Account account = fresh.accounts.get(profile.reference());
                        long id = teacherIds.get(profile.reference());
                        String initial = Json.write(initial(profile,account,id));
                        Db.exec("INSERT INTO teacher_roster_import_people(batch_key,source_reference,source_fingerprint,account_id,person_code,teacher_id,initial_facts,initial_digest,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                                batch,profile.reference(),fingerprint,account.id,account.personCode,id,initial,digest(initial),actor.uid);
                    }
                    // Files can change without acquiring the database lock. Verify again before the transaction commits.
                    Source last = source(actor);
                    if (!fresh.profilesDigest.equals(last.profilesDigest) || !fresh.accountsDigest.equals(last.accountsDigest))
                        throw conflict("名单或账号关联已变化，已撤回本批");
                    stored(last);
                    requireAdmin(actor);
                    return receipt;
                });
            } catch (Api.ApiException e) { throw e; }
            catch (SQLException e) { throw conflict("本批教师接收未完成，已撤回全部更改；请刷新核实后重试"); }
        }
    }

    /** A clear host deletion guard; the durable foreign key also prevents dangling teacher associations. */
    public static boolean hasImportedTeacher(long teacherId) throws Exception {
        synchronized (Api.MUTATION_LOCK) {
            return Db.one("SELECT teacher_id FROM teacher_roster_import_people WHERE teacher_id=?",teacherId) != null;
        }
    }

    private Source source(Auth.Session actor) throws Exception {
        try {
            Snapshot snapshot = OrganizationAccountImportSource.load(config);
            List<TeacherProfile> profiles = OrganizationAccountImportSource.teacherProfiles(config);
            Map<String,Object> intake = new OrganizationAccountImport(config).preview(actor);
            intact(Boolean.TRUE.equals(intake.get("imported")) && snapshot.batchKey().equals(intake.get("batchKey"))
                    && snapshot.fingerprint().equals(intake.get("sourceFingerprint")));
            List<?> rows = list(intake.get("rows"));
            intact(rows.size() == snapshot.candidates().size());
            Map<String,Candidate> candidates = new LinkedHashMap<>();
            for (Candidate candidate : snapshot.candidates()) intact(candidates.put(candidate.reference(),candidate) == null);
            Map<String,Account> accounts = new LinkedHashMap<>();
            Set<Long> ids = new HashSet<>(); Set<String> persons = new HashSet<>();
            for (Object raw : rows) {
                Map<?,?> row = object(raw);
                Object ref = row.get("reference");
                intact(ref instanceof String && candidates.containsKey(ref) && Boolean.TRUE.equals(row.get("alreadyImported")));
                long id = number(row.get("accountId"));
                intact(ids.add(id) && row.get("personCode") instanceof String person && person.matches("imp_p_[0-9a-f]{32}") && persons.add(person));
                intact(accounts.put((String)ref,new Account(id,(String)row.get("personCode"))) == null);
            }
            intact(accounts.keySet().equals(candidates.keySet()) && !profiles.isEmpty());
            Set<String> teacherRefs = new HashSet<>();
            List<Map<String,Object>> profileFacts = new ArrayList<>(), accountFacts = new ArrayList<>();
            for (Candidate candidate : snapshot.candidates()) {
                Account account = accounts.get(candidate.reference());
                accountFacts.add(map("reference",candidate.reference(),"accountId",account.id,"personCode",account.personCode));
                if (list(candidate.preparation().get("identities")).contains("兼职教师")) teacherRefs.add(candidate.reference());
            }
            Set<String> projected = new HashSet<>();
            for (TeacherProfile profile : profiles) {
                Candidate candidate = candidates.get(profile.reference());
                intact(candidate != null && teacherRefs.contains(profile.reference()) && projected.add(profile.reference())
                        && profile.name().equals(candidate.name()) && profile.organization().equals(candidate.organization()));
                profileFacts.add(map("reference",profile.reference(),"name",profile.name(),"organization",profile.organization(),"job",profile.job(),"teacherLevel",profile.teacherLevel()));
            }
            intact(teacherRefs.equals(projected));
            Map<String,Object> summary = map("teachers",profiles.size(),"excludedLeads",snapshot.candidates().size()-profiles.size(),"historicalExcluded",snapshot.excludedReferences().size());
            requireAdmin(actor);
            return new Source(snapshot,profiles,accounts,summary,digest(Json.write(profileFacts)),digest(Json.write(accountFacts)));
        } catch (Api.ApiException e) {
            if (e.code == 401 || e.code == 403) throw e;
            throw conflict("可信名单或完整账号接收记录无法核实，请由管理员核对原批次");
        } catch (Exception e) { throw conflict("可信名单或完整账号接收记录无法核实，请由管理员核对原批次"); }
    }

    /** Validate complete frozen evidence, not editable current profile values. Never partially repair it. */
    private Stored stored(Source source) throws Exception {
        try {
            List<Map<String,Object>> batches = Db.query("SELECT * FROM teacher_roster_import_batches ORDER BY batch_key");
            List<Map<String,Object>> people = Db.query("SELECT * FROM teacher_roster_import_people ORDER BY source_reference");
            if (batches.isEmpty()) {
                intact(people.isEmpty() && Db.count("teachers") == 0);
                return null;
            }
            intact(batches.size() == 1 && people.size() == source.profiles.size());
            Map<String,Object> batch = batches.get(0);
            intact(source.snapshot.batchKey().equals(batch.get("batch_key")) && source.snapshot.fingerprint().equals(batch.get("source_fingerprint"))
                    && source.profilesDigest.equals(batch.get("profiles_digest")) && source.accountsDigest.equals(batch.get("accounts_digest")));
            long createdBy = number(batch.get("created_by"));
            Map<String,Map<String,Object>> byRef = new HashMap<>();
            for (Map<String,Object> person : people) {
                intact(person.get("source_reference") instanceof String ref && byRef.put(ref,person) == null);
            }
            Set<Long> uniqueTeachers = new HashSet<>(); Set<Long> uniqueAccounts = new HashSet<>();
            Map<String,Long> teachers = new LinkedHashMap<>(); Map<Long,Object> statuses = new HashMap<>();
            List<Map<String,Object>> receiptRows = new ArrayList<>();
            for (TeacherProfile profile : source.profiles) {
                Map<String,Object> row = byRef.get(profile.reference());
                Account account = source.accounts.get(profile.reference());
                intact(row != null && source.snapshot.batchKey().equals(row.get("batch_key")) && source.snapshot.fingerprint().equals(row.get("source_fingerprint"))
                        && account.id == number(row.get("account_id")) && account.personCode.equals(row.get("person_code")) && number(row.get("created_by")) == createdBy);
                long id = number(row.get("teacher_id"));
                intact(uniqueTeachers.add(id) && uniqueAccounts.add(account.id));
                Map<String,Object> current = Db.one("SELECT id,status FROM teachers WHERE id=?",id);
                intact(current != null && id == number(current.get("id")));
                Map<String,Object> initial = initial(profile,account,id);
                intact(digest(Json.write(initial)).equals(row.get("initial_digest"))
                        && normalized(initial).equals(Json.parse(String.valueOf(row.get("initial_facts")))));
                teachers.put(profile.reference(),id); statuses.put(id,current.get("status"));
                receiptRows.add(map("reference",profile.reference(),"accountId",account.id,"teacherId",id));
            }
            Map<String,Object> expected = receipt(source,receiptRows,false);
            intact(normalized(expected).equals(Json.parse(String.valueOf(batch.get("receipt")))));
            return new Stored(teachers,statuses,expected);
        } catch (Exception e) { throw conflict("教师库非空或完整接收回执、来源关联无法核实，请核对原批次；未作合并或修复"); }
    }

    private static Map<String,Object> initial(TeacherProfile profile, Account account, long id) {
        return map("reference",profile.reference(),"accountId",account.id,"personCode",account.personCode,"teacherId",id,
                "name",profile.name(),"org",profile.organization(),"title",profile.job(),"teacher_level",profile.teacherLevel(),"status","待完善",
                "gender",null,"field",null,"phone",null,"email",null,"fee_rate",null,"intro",null,"in_date",null,"out_date",null,"base_province",null,"base_city",null);
    }
    private static Map<String,Object> receipt(Source source, List<Map<String,Object>> rows, boolean replayed) {
        return map("batchKey",source.snapshot.batchKey(),"sourceFingerprint",source.snapshot.fingerprint(),"imported",true,"replayed",replayed,
                "rows",rows,"summary",new LinkedHashMap<>(source.summary));
    }
    private static Map<String,Object> replay(Map<String,Object> value) {
        Map<String,Object> result = Json.parseMap(Json.write(value)); result.put("replayed",true); return result;
    }
    private static void reviewed(Auth.Session actor, Source source, Review review) throws Exception {
        if (review == null || review.session != actor || review.expires <= System.currentTimeMillis()
                || !review.batchKey.equals(source.snapshot.batchKey()) || !review.fingerprint.equals(source.snapshot.fingerprint())
                || !review.state.equals(state())) throw conflict("预览核对已失效或账号/教师状态已变化，请重新预览后确认");
    }
    private static String state() throws Exception {
        return digest(Json.write(map("users",Db.query("SELECT * FROM users ORDER BY id"),"teachers",Db.query("SELECT * FROM teachers ORDER BY id"),
                "accountState",Db.query("SELECT * FROM organization_account_import_state ORDER BY singleton"),
                "accountBatches",Db.query("SELECT * FROM organization_account_import_batches ORDER BY batch_key"),
                "accountPeople",Db.query("SELECT * FROM organization_account_import_people ORDER BY person_code"))));
    }
    private static Auth.Session requireAdmin(Auth.Session supplied) throws Api.ApiException {
        Auth.Session actor = Auth.current(supplied);
        if (actor == null) throw new Api.ApiException(401,"登录会话无效或已失效");
        if (!Auth.isAdmin(actor)) throw new Api.ApiException(403,"仅系统管理员可接收教师主名单");
        return actor;
    }
    private static void committed() throws Exception {
        if (!Db.get().getAutoCommit()) throw conflict("教师接收必须在独立事务中执行，请结束当前事务后重试");
    }
    private static long number(Object value) {
        intact(value instanceof Number); Number n = (Number)value; double d = n.doubleValue();
        intact(Double.isFinite(d) && d == Math.rint(d) && d > 0 && d <= MAX_ID); return n.longValue();
    }
    private static String batchText(Object value) throws Api.ApiException {
        if (!(value instanceof String text) || !text.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw bad("批次标识格式不正确"); return (String)value;
    }
    private static String hashText(Object value) throws Api.ApiException {
        if (!(value instanceof String text) || !text.matches("[0-9a-f]{64}")) throw bad("来源摘要或核对票据格式不正确"); return (String)value;
    }
    private static void intact(boolean valid) { if (!valid) throw new IllegalArgumentException("Unverified teacher intake"); }
    private static Map<?,?> object(Object value) { intact(value instanceof Map<?,?>); return (Map<?,?>)value; }
    private static List<?> list(Object value) { intact(value instanceof List<?>); return (List<?>)value; }
    private static Object normalized(Object value) { return Json.parse(Json.write(value)); }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>(); for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]); return result;
    }
    private static Api.ApiException bad(String message) { return new Api.ApiException(400,message); }
    private static Api.ApiException conflict(String message) { return new Api.ApiException(409,message); }
}
