package com.training;

import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.h2.api.Trigger;
import com.training.OrganizationAccountImportSource.Config;

/** Runs only on caller-owned, empty temporary directories and synthetic source pins; no HTTP server. */
public final class M04TeacherRosterImportTest {
    private static final String PASSWORD = "SYNTHETIC-M04-ROSTER-20260923";
    private static final String BATCHES = "teacher_roster_import_batches";
    private static final String PEOPLE = "teacher_roster_import_people";
    private static final Set<String> WRITABLE = Set.of("teachers", BATCHES, PEOPLE);
    private static final Set<String> PREVIEW_KEYS = Set.of("batchKey", "sourceFingerprint", "imported", "reviewToken", "rows", "summary");
    private static final Set<String> PREVIEW_ROW_KEYS = Set.of("reference", "name", "organization", "job", "teacherLevel", "accountId", "teacherId", "status");
    private static final Set<String> RECEIPT_KEYS = Set.of("batchKey", "sourceFingerprint", "imported", "replayed", "rows", "summary");
    private static final Set<String> RECEIPT_ROW_KEYS = Set.of("reference", "accountId", "teacherId");
    private static Auth.Session admin, viewer, manager;
    private static String adminToken;
    private static long adminId;
    private static int checks;
    @FunctionalInterface private interface Work { void run() throws Exception; }
    @FunctionalInterface private interface Change { void apply(Map<String,Object> value) throws Exception; }
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static void rejects(int code, Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected rejection: " + label); }
        catch (Api.ApiException e) {
            check(e.code == code, label + " (actual HTTP " + e.code + ")");
            check(e.getCause() == null && !String.valueOf(e.getMessage()).contains("SYNTHETIC PRIVATE"), label + " has a sanitized error");
        }
    }
    private static void sqlRejects(Work work, String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected database rejection: " + label); }
        catch (SQLException expected) { check(true, label); }
    }
    private static Map<String,Object> map(Object... entries) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<entries.length;i+=2) result.put((String)entries[i],entries[i+1]);
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value) { return (List<Map<String,Object>>)value; }
    private static long number(Object value) { return ((Number)value).longValue(); }
    private static Map<String,Object> copy(Map<String,Object> value) { return object(Json.parse(Json.write(value))); }
    private static Map<String,Object> request(Map<String,Object> preview) {
        return map("batchKey",preview.get("batchKey"),"sourceFingerprint",preview.get("sourceFingerprint"),"reviewToken",preview.get("reviewToken"));
    }
    private static Map<String,Object> allCreate(Map<String,Object> preview) {
        List<Object> decisions = new ArrayList<>();
        for (Map<String,Object> row:rows(preview.get("rows"))) decisions.add(map("reference",row.get("reference"),"action","CREATE_PENDING","accountId",null,"accountProof",null,"reviewed",true));
        return map("expectedRevision",preview.get("revision"),"sourceFingerprint",preview.get("sourceFingerprint"),"reviewToken",preview.get("reviewToken"),"decisions",decisions);
    }
    private static Map<String,Object> byReference(List<Map<String,Object>> values, Object ref) {
        return values.stream().filter(v -> Objects.equals(v.get("reference"),ref)).findFirst().orElseThrow();
    }
    private static Set<String> tables() throws Exception {
        Set<String> names = new TreeSet<>();
        for (Map<String,Object> row:Db.query("SELECT table_name FROM information_schema.tables WHERE table_schema='PUBLIC' AND table_type='BASE TABLE'"))
            names.add(String.valueOf(row.get("table_name")).toLowerCase(Locale.ROOT));
        return names;
    }
    /** Compare complete row contents for every application table, not just row counts. */
    private static String state(Set<String> excluded) throws Exception {
        Map<String,Object> result = new TreeMap<>();
        for (String name:tables()) if (!excluded.contains(name)) {
            List<String> content = new ArrayList<>();
            for (Map<String,Object> row:Db.query("SELECT * FROM " + name)) content.add(Json.write(row));
            Collections.sort(content); result.put(name,content);
        }
        return Json.write(result);
    }
    private static String state() throws Exception { return state(Set.of()); }
    private static void unchanged(String before, String label) throws Exception { check(before.equals(state()),label); }
    private static Map<String,String> digests(Path root) throws Exception {
        Map<String,String> result = new TreeMap<>();
        try (var files=Files.walk(root)) {
            for (Path p:files.filter(Files::isRegularFile).toList()) result.put(root.relativize(p).toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
        }
        return result;
    }
    private static long addUser(String username, String role, String hash) throws Exception {
        return Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)",username,hash,"SYNTHETIC " + role,role);
    }
    private static Config fixture(Path directory) throws Exception {
        OrganizationAccountImportSourceTest.Fixture fixture = new OrganizationAccountImportSourceTest.Fixture(directory);
        String[] levels = {"特级讲师","高级讲师","讲师","讲师","高级讲师","特级讲师"};
        int i=0;
        for (Map<String,Object> person:rows(fixture.batch.get("candidates"))) {
            if (!String.valueOf(person.get("candidate_reference")).startsWith("teacher-row-")) continue;
            person.put("source_job",i == 0 ? null : "SYNTHETIC JOB " + i);
            person.put("source_teacher_level",levels[i++]);
        }
        return fixture.save();
    }
    private static void summary(Map<String,Object> value, String label) {
        Map<String,Object> counts=object(value.get("summary"));
        check(counts.keySet().equals(Set.of("teachers","excludedLeads","historicalExcluded")),label + " summary has only reviewed counts");
        check(number(counts.get("teachers")) == 6 && number(counts.get("excludedLeads")) == 1 && number(counts.get("historicalExcluded")) == 2,label + " exact synthetic scope");
    }
    private static void previewShape(Map<String,Object> preview, boolean imported, Map<String,Object> accounts) {
        check(preview.keySet().equals(PREVIEW_KEYS),"preview whitelist");
        check(Boolean.valueOf(imported).equals(preview.get("imported")),"preview imported flag");
        check(rows(preview.get("rows")).size() == 6,"only six active teacher rows, including dual identity");
        check(imported ? preview.get("reviewToken") == null : preview.get("reviewToken") instanceof String && !String.valueOf(preview.get("reviewToken")).isBlank(),"preview token state");
        summary(preview,"preview");
        Set<Object> seen = new HashSet<>();
        for (Map<String,Object> row:rows(preview.get("rows"))) {
            check(row.keySet().equals(PREVIEW_ROW_KEYS),"preview row exact whitelist");
            check(seen.add(row.get("reference")) && String.valueOf(row.get("reference")).matches("teacher-row-[3-8]"),"preserve unique approved source reference");
            check(number(row.get("accountId")) == number(byReference(rows(accounts.get("rows")),row.get("reference")).get("accountId")),"account binding comes from verified M01 intake");
            check(imported ? row.get("teacherId") instanceof Number : row.get("teacherId") == null && "待接收".equals(row.get("status")),"preview current teacher binding/status");
        }
        check(rows(preview.get("rows")).stream().filter(r -> "SYNTHETIC PRIVATE 4".equals(r.get("name"))).count() == 2,"same name in different source rows is not merged");
        check(byReference(rows(preview.get("rows")),"teacher-row-3").get("job") == null,"missing source job remains null");
        check(seen.contains("teacher-row-8") && !seen.contains("lead-row-9") && !seen.contains("teacher-row-12"),"dual identity retained; lead-only and historical rows excluded");
        String encoded=Json.write(preview);
        for (String hidden:List.of("source_staff_id","source_namespace","sourceFiles","password","personCode","permissionsPublished","canApprove","candidateSource","SYNTHETIC EXCLUDED","000SYN",".original",".json"))
            check(!encoded.contains(hidden),"preview does not expose " + hidden);
    }
    private static void receiptShape(Map<String,Object> value, boolean replayed) {
        check(value.keySet().equals(RECEIPT_KEYS),"receipt whitelist");
        check(Boolean.TRUE.equals(value.get("imported")) && Boolean.valueOf(replayed).equals(value.get("replayed")),"receipt import/replay flags");
        check(rows(value.get("rows")).size() == 6,"receipt covers exact teacher scope");
        summary(value,"receipt");
        Set<Object> refs=new HashSet<>(); Set<Long> accounts=new HashSet<>(),teachers=new HashSet<>();
        for (Map<String,Object> row:rows(value.get("rows"))) {
            check(row.keySet().equals(RECEIPT_ROW_KEYS),"receipt row whitelist");
            check(refs.add(row.get("reference")) && accounts.add(number(row.get("accountId"))) && teachers.add(number(row.get("teacherId"))),"receipt contains one-to-one references, accounts and teachers");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Fresh empty data and synthetic fixture directories required");
        Path data=Path.of(args[0]).toAbsolutePath().normalize(), sources=Path.of(args[1]).toAbsolutePath().normalize();
        check(!data.equals(sources) && !data.startsWith(sources) && !sources.startsWith(data),"data and synthetic pins are distinct");
        for (Path p:List.of(data,sources)) {
            if (!Files.isDirectory(p) || Files.isSymbolicLink(p)) throw new IllegalArgumentException("Only fresh regular directories accepted");
            try(var files=Files.list(p)) { if(files.findAny().isPresent()) throw new IllegalArgumentException("Only fresh empty directories accepted"); }
        }
        System.setProperty("data.dir",data.toString()); System.setProperty("bootstrap.demo","false");
        System.clearProperty("account.import.manifest"); System.clearProperty("account.import.manifest.sha256");
        try {
            Db.exec("CREATE TABLE users(id IDENTITY PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            String hash=Auth.hash(PASSWORD);
            adminId=addUser("synthetic-roster-admin","admin",hash);
            addUser("synthetic-roster-viewer","viewer",hash); addUser("synthetic-roster-manager","manager",hash);
            Db.exec("CREATE TABLE teachers(id IDENTITY PRIMARY KEY,name VARCHAR(64),gender VARCHAR(8),org VARCHAR(200),title VARCHAR(64),field VARCHAR(200),phone VARCHAR(32),email VARCHAR(64),fee_rate DOUBLE,intro CLOB,status VARCHAR(16) DEFAULT '在库',in_date VARCHAR(32),out_date VARCHAR(32),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,base_province VARCHAR(64),base_city VARCHAR(64),teacher_level VARCHAR(32))");
            OrganizationAccountImport.init();
            Set<String> initialTables=tables(); String beforeInit=state();
            TeacherRosterImport.init(); TeacherRosterImport.init();
            Set<String> added=tables(); added.removeAll(initialTables);
            check(added.equals(Set.of(BATCHES,PEOPLE)),"init adds exactly the two intake metadata tables");
            check(Db.count(BATCHES)==0 && Db.count(PEOPLE)==0,"init and repeated init create empty metadata only");
            check(beforeInit.equals(state(Set.of(BATCHES,PEOPLE))),"init changes no existing application row");
            Db.init();
            check(Db.count("users")==3 && Db.count("teachers")==0,"only explicit synthetic users, no demonstration seed");
            adminToken=Auth.login("synthetic-roster-admin",PASSWORD); admin=Auth.get(adminToken);
            viewer=Auth.get(Auth.login("synthetic-roster-viewer",PASSWORD)); manager=Auth.get(Auth.login("synthetic-roster-manager",PASSWORD));
            Config config=fixture(sources.resolve("reviewed")); Map<String,String> sourceBefore=digests(sources);
            TeacherRosterImport service=new TeacherRosterImport(config);
            authentication(service,hash);
            String empty=state();
            rejects(409,()->service.preview(admin),"teacher preview requires all M01 accounts to be received");
            unchanged(empty,"missing account intake produces no writes");
            OrganizationAccountImport intake=new OrganizationAccountImport(config);
            Map<String,Object> accounts=intake.commit(admin,allCreate(intake.preview(admin)));
            check(rows(accounts.get("rows")).size()==7 && Db.count("users")==10,"actual account importer accepts all seven candidates first");
            String untouched=state(WRITABLE);
            Map<String,Object> preview=service.preview(admin); previewShape(preview,false,accounts);
            check(untouched.equals(state(WRITABLE)),"preview leaves every non-teacher table unchanged");
            strictBody(service,preview);
            tokenAndFreshness(service,config);
            accountIntakeCorruption(service,config);
            rollback(service);
            Map<String,Object> finalPreview=service.preview(admin), command=request(finalPreview);
            String beforeCommit=state(WRITABLE);
            Map<String,Object> receipt=service.commit(admin,command); receiptShape(receipt,false);
            check(beforeCommit.equals(state(WRITABLE)),"commit only writes teachers and its own two receipt tables");
            check(Db.count("teachers")==6 && Db.count(BATCHES)==1 && Db.count(PEOPLE)==6,"atomic import creates exact six teachers and one receipt");
            verifyTeachers(finalPreview,receipt);
            previewShape(service.preview(admin),true,accounts);
            replayAndLegalEdits(service,config,command,receipt);
            receiptCorruption(service,config,command);
            constraints(receipt);
            check(untouched.equals(state(WRITABLE)),"all teacher operations preserve every other application table byte-for-byte at row level");
            check(sourceBefore.equals(digests(sources)),"all independently pinned synthetic source files remain unchanged");
            Auth.logout(adminToken);
            String beforeLogout=state();
            rejects(401,()->service.commit(admin,command),"logged-out original session cannot replay a durable receipt");
            rejects(401,()->service.preview(admin),"logged-out original session cannot preview imported data");
            unchanged(beforeLogout,"revoked session performs no writes");
            System.out.println("M04TeacherRosterImport: " + checks + " checks passed (synthetic files and isolated H2; no server)");
        } finally { if (!Db.get().isClosed()) Db.exec("SHUTDOWN"); }
    }

    private static void authentication(TeacherRosterImport service,String hash) throws Exception {
        String before=state();
        rejects(401,()->service.preview(null),"anonymous preview denied");
        rejects(401,()->service.commit(null,null),"anonymous commit denied before body parsing");
        rejects(403,()->service.preview(viewer),"viewer preview denied");
        rejects(403,()->service.commit(viewer,Map.of()),"viewer commit denied");
        rejects(403,()->service.preview(manager),"manager preview denied");
        rejects(403,()->service.commit(manager,Map.of()),"manager commit denied");
        Auth.Session forged=new Auth.Session(); forged.uid=adminId; forged.role="admin";
        rejects(401,()->service.preview(forged),"copied administrator uid does not authenticate");
        rejects(401,()->service.commit(forged,Map.of()),"forged commit session denied");
        long id=addUser("synthetic-roster-temporary","admin",hash);
        String token=Auth.login("synthetic-roster-temporary",PASSWORD); Auth.Session temporary=Auth.get(token);
        Db.exec("UPDATE users SET role='viewer' WHERE id=?",id);
        rejects(403,()->service.preview(temporary),"live database downgrade overrides cached admin role");
        Db.exec("UPDATE users SET role='admin' WHERE id=?",id); Auth.logout(token);
        rejects(401,()->service.preview(temporary),"logout invalidates a retained Session object");
        Auth.Session disabled=Auth.get(Auth.login("synthetic-roster-temporary",PASSWORD));
        Db.exec("UPDATE users SET status=0 WHERE id=?",id);
        rejects(401,()->service.preview(disabled),"disabled administrator denied immediately");
        Db.exec("UPDATE users SET status=1 WHERE id=?",id);
        rejects(401,()->service.preview(disabled),"reenabling account does not revive revoked session");
        Auth.Session tampered=Auth.get(Auth.login("synthetic-roster-temporary",PASSWORD)); tampered.uid=adminId;
        rejects(401,()->service.preview(tampered),"changing a real session's uid is rejected");
        Db.exec("DELETE FROM users WHERE id=?",id);
        unchanged(before,"authentication probes leave all application rows intact");
    }

    private static void strictBody(TeacherRosterImport service,Map<String,Object> preview) throws Exception {
        Map<String,Object> body=request(preview); String before=state();
        rejects(400,()->service.commit(admin,null),"null commit body rejected");
        rejects(400,()->service.commit(admin,Map.of()),"empty commit body rejected");
        for(String key:body.keySet()) {
            Map<String,Object> missing=copy(body); missing.remove(key);
            rejects(400,()->service.commit(admin,missing),"missing " + key);
            for(Object invalid:Arrays.asList(null,42,true,List.of(),map(),"")) {
                if (key.equals("reviewToken") && invalid == null) continue;
                Map<String,Object> wrong=copy(body); wrong.put(key,invalid);
                rejects(400,()->service.commit(admin,wrong),"strict scalar type for " + key);
            }
        }
        for(String key:List.of("rows","sourcePath","accountId","teacherId","teacherCode","roles","certifications","reviewed","status","grantAdmin")) {
            Map<String,Object> extra=copy(body); extra.put(key,true);
            rejects(400,()->service.commit(admin,extra),"unknown commit field " + key);
        }
        Map<String,Object> wrongBatch=copy(body); wrongBatch.put("batchKey","synthetic-other-batch");
        rejects(409,()->service.commit(admin,wrongBatch),"valid but different batch rejected");
        Map<String,Object> wrongFingerprint=copy(body); wrongFingerprint.put("sourceFingerprint","0".repeat(64));
        rejects(409,()->service.commit(admin,wrongFingerprint),"different source fingerprint rejected");
        Map<String,Object> wrongToken=copy(body); wrongToken.put("reviewToken","0".repeat(64));
        rejects(409,()->service.commit(admin,wrongToken),"guessed review token rejected");
        Map<String,Object> absentToken=copy(body); absentToken.put("reviewToken",null);
        rejects(409,()->service.commit(admin,absentToken),"first intake needs an issued review token");
        unchanged(before,"strict body failures leave every row unchanged");
    }

    private static void tokenAndFreshness(TeacherRosterImport service,Config config) throws Exception {
        Map<String,Object> original=request(service.preview(admin)); String before=state();
        Auth.Session another=Auth.get(Auth.login("synthetic-roster-admin",PASSWORD));
        rejects(409,()->service.commit(another,original),"same administrator's different session cannot reuse review");
        expireReview(service,String.valueOf(original.get("reviewToken")));
        rejects(409,()->service.commit(admin,original),"expired review token rejected");
        unchanged(before,"session and expiry failures have no side effects");
        Map<String,Object> userChanged=request(service.preview(admin));
        Db.exec("UPDATE users SET name='SYNTHETIC TEMPORARY CHANGE' WHERE id=?",adminId);
        String changed=state();
        rejects(409,()->service.commit(admin,userChanged),"user changes after preview invalidate review");
        unchanged(changed,"stale-user rejection preserves new state");
        Db.exec("UPDATE users SET name='SYNTHETIC admin' WHERE id=?",adminId);
        Map<String,Object> teachersChanged=request(service.preview(admin));
        long outsider=Db.insert("INSERT INTO teachers(name,org,status) VALUES('SYNTHETIC PREEXISTING','SYNTHETIC ORG','待完善')");
        String occupied=state();
        rejects(409,()->service.preview(admin),"nonempty teacher library blocks first intake");
        rejects(409,()->service.commit(admin,teachersChanged),"teacher insertion after preview invalidates commit");
        unchanged(occupied,"first-batch conflict never merges or overwrites an existing teacher");
        Db.exec("DELETE FROM teachers WHERE id=?",outsider);
        Map<String,Object> sourceChanged=request(service.preview(admin));
        byte[] originalBytes=Files.readAllBytes(config.candidates().path());
        try {
            Files.writeString(config.candidates().path(),"SYNTHETIC TAMPERED SOURCE");
            rejects(409,()->service.preview(admin),"changed pinned source rejected on preview");
            rejects(409,()->service.commit(admin,sourceChanged),"changed pinned source rejected on commit");
        } finally { Files.write(config.candidates().path(),originalBytes); }
        unchanged(before,"source review failures preserve database rows");
    }

    /** Expire only the test's in-memory review without sleeping or changing production clocks. */
    @SuppressWarnings("unchecked") private static void expireReview(TeacherRosterImport service,String token) throws Exception {
        for (Field field:service.getClass().getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true); Map<Object,Object> reviews=(Map<Object,Object>)field.get(service);
            if (reviews == null || !reviews.containsKey(token)) continue;
            Object review=reviews.get(token); Class<?> type=review.getClass();
            if (type.isRecord()) {
                RecordComponent[] parts=type.getRecordComponents(); Class<?>[] parameterTypes=new Class<?>[parts.length]; Object[] values=new Object[parts.length]; boolean replaced=false;
                for(int i=0;i<parts.length;i++) {
                    parameterTypes[i]=parts[i].getType(); Method getter=parts[i].getAccessor(); getter.setAccessible(true); values[i]=getter.invoke(review);
                    if(parts[i].getName().toLowerCase(Locale.ROOT).contains("expir")) { values[i]=0L; replaced=true; }
                }
                check(replaced,"review stores a bounded expiry"); Constructor<?> constructor=type.getDeclaredConstructor(parameterTypes); constructor.setAccessible(true);
                reviews.put(token,constructor.newInstance(values)); return;
            }
            for(Field expiry:type.getDeclaredFields()) if(expiry.getName().toLowerCase(Locale.ROOT).contains("expir")) {
                expiry.setAccessible(true); expiry.setLong(review,0L); check(true,"review stores a bounded expiry"); return;
            }
        }
        throw new AssertionError("No review expiry found for synthetic issued token");
    }

    private static void accountIntakeCorruption(TeacherRosterImport service,Config config) throws Exception {
        Map<String,Object> command=request(service.preview(admin));
        String original=String.valueOf(Db.one("SELECT receipt FROM organization_account_import_batches WHERE batch_key=?",config.batchKey()).get("receipt"));
        Map<String,Object> incomplete=object(Json.parse(original)); rows(incomplete.get("rows")).removeIf(r->"lead-row-9".equals(r.get("reference")));
        try {
            Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key=?",Json.write(incomplete),config.batchKey());
            String damaged=state();
            rejects(409,()->service.preview(admin),"missing lead-only row makes the complete M01 receipt unverified");
            rejects(409,()->service.commit(admin,command),"commit rechecks entire M01 receipt including excluded lead");
            unchanged(damaged,"broken account receipt never causes partial teachers");
        } finally { Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key=?",original,config.batchKey()); }
        Map<String,Object> person=Db.one("SELECT * FROM organization_account_import_people WHERE source_reference='lead-row-9'");
        try {
            Db.exec("UPDATE organization_account_import_people SET payload='{}' WHERE source_reference='lead-row-9'");
            rejects(409,()->service.preview(admin),"lead-only account payload participates in complete intake verification");
            rejects(409,()->service.commit(admin,command),"commit refuses a damaged excluded-lead account payload");
        } finally { Db.exec("UPDATE organization_account_import_people SET payload=? WHERE source_reference='lead-row-9'",person.get("payload")); }
    }

    private static void rollback(TeacherRosterImport service) throws Exception {
        FailSecondTeacher.count=0;
        Db.exec("CREATE TRIGGER synthetic_roster_fail_second BEFORE INSERT ON teachers FOR EACH ROW CALL 'com.training.M04TeacherRosterImportTest$FailSecondTeacher'");
        String before=state();
        try { rejects(409,()->service.commit(admin,request(service.preview(admin))),"second teacher insert fails atomically"); }
        finally { Db.exec("DROP TRIGGER synthetic_roster_fail_second"); }
        check(FailSecondTeacher.count==2,"injected error occurred after the first teacher insert");
        unchanged(before,"mid-teacher failure rolls back teachers, associations and receipt");
        Db.exec("CREATE TRIGGER synthetic_roster_fail_receipt BEFORE INSERT ON " + BATCHES + " FOR EACH ROW CALL 'com.training.M04TeacherRosterImportTest$FailReceipt'");
        before=state();
        try { rejects(409,()->service.commit(admin,request(service.preview(admin))),"receipt insert failure is atomic"); }
        finally { Db.exec("DROP TRIGGER synthetic_roster_fail_receipt"); }
        unchanged(before,"receipt failure rolls back all teacher and relation writes");
    }

    private static void verifyTeachers(Map<String,Object> preview,Map<String,Object> receipt) throws Exception {
        for(Map<String,Object> row:rows(receipt.get("rows"))) {
            Map<String,Object> expected=byReference(rows(preview.get("rows")),row.get("reference"));
            Map<String,Object> teacher=Db.one("SELECT * FROM teachers WHERE id=?",row.get("teacherId"));
            check(teacher != null && Objects.equals(teacher.get("name"),expected.get("name")) && Objects.equals(teacher.get("org"),expected.get("organization")),"teacher exact source name and organization");
            check(Objects.equals(teacher.get("title"),expected.get("job")) && Objects.equals(teacher.get("teacher_level"),expected.get("teacherLevel")),"teacher job and reviewed source level preserved");
            check("待完善".equals(teacher.get("status")),"received teacher remains pending completion");
            for(String key:List.of("gender","field","phone","email","fee_rate","intro","in_date","out_date","base_province","base_city")) check(teacher.get(key)==null,"unprovided teacher " + key + " remains null");
            check(Db.one("SELECT role,status FROM users WHERE id=?",row.get("accountId")).equals(map("role","viewer","status",0)),"teacher creation does not activate or promote received account");
        }
        Set<String> levels=new HashSet<>(); for(Map<String,Object> row:Db.query("SELECT teacher_level FROM teachers")) levels.add(String.valueOf(row.get("teacher_level")));
        check(levels.equals(Set.of("讲师","高级讲师","特级讲师")),"all three reviewed source teacher levels preserved");
        check(Db.count("m04_teacher_bindings")==0,"teacher intake assigns no M04 formal code or certification binding");
    }

    private static void replayAndLegalEdits(TeacherRosterImport service,Config config,Map<String,Object> command,Map<String,Object> original) throws Exception {
        String before=state();
        Map<String,Object> replay=service.commit(admin,command); receiptShape(replay,true);
        check(Json.parse(Json.write(replay.get("rows"))).equals(Json.parse(Json.write(original.get("rows")))),"replay returns stable teacher/account identifiers");
        unchanged(before,"same-command replay creates no rows");
        TeacherRosterImport another=new TeacherRosterImport(config);
        receiptShape(another.commit(admin,command),true);
        unchanged(before,"new service instance replays durable receipt without an in-memory preview");
        Db.get().close(); Db.get(); TeacherRosterImport.init();
        receiptShape(new TeacherRosterImport(config).commit(admin,command),true);
        unchanged(before,"database reconnect preserves same valid token's durable replay");
        Auth.Session anotherSession=Auth.get(Auth.login("synthetic-roster-admin",PASSWORD));
        receiptShape(another.commit(anotherSession,command),true);
        Map<String,Object> noToken=copy(command); noToken.put("reviewToken",null);
        receiptShape(new TeacherRosterImport(config).commit(anotherSession,noToken),true);
        unchanged(before,"current administrator can replay verified durable receipt with old or null token");
        long id=number(rows(original.get("rows")).get(0).get("teacherId"));
        Db.exec("UPDATE teachers SET name='SYNTHETIC EDITED TEACHER',org='SYNTHETIC EDITED ORGANIZATION',title='SYNTHETIC EDITED JOB',teacher_level='讲师',base_province='SYNTHETIC PROVINCE',base_city='SYNTHETIC CITY',status='在库' WHERE id=?",id);
        String edited=state();
        Map<String,Object> imported=new TeacherRosterImport(config).preview(admin);
        check(Boolean.TRUE.equals(imported.get("imported")),"legal later teacher edits retain original intake receipt");
        Map<String,Object> previewRow=byReference(rows(imported.get("rows")),rows(original.get("rows")).get(0).get("reference"));
        check("在库".equals(previewRow.get("status")) && !"SYNTHETIC EDITED TEACHER".equals(previewRow.get("name")),"imported preview shows current status alongside frozen source identity");
        Map<String,Object> replayAfterEdit=new TeacherRosterImport(config).commit(admin,command); receiptShape(replayAfterEdit,true);
        check(Json.parse(Json.write(replayAfterEdit.get("rows"))).equals(Json.parse(Json.write(original.get("rows")))),"receipt remains the original identity mapping after teacher edits");
        unchanged(edited,"imported preview and replay never overwrite later teacher edits");
    }

    private static void receiptCorruption(TeacherRosterImport service,Config config,Map<String,Object> command) throws Exception {
        corruptReceipt(service,config,command,"missing row",v->rows(v.get("rows")).remove(0));
        corruptReceipt(service,config,command,"duplicate reference",v->rows(v.get("rows")).set(1,copy(rows(v.get("rows")).get(0))));
        corruptReceipt(service,config,command,"forged source reference",v->rows(v.get("rows")).get(0).put("reference","lead-row-9"));
        corruptReceipt(service,config,command,"wrong account association",v->rows(v.get("rows")).get(0).put("accountId",adminId));
        corruptReceipt(service,config,command,"wrong teacher association",v->rows(v.get("rows")).get(0).put("teacherId",999999));
        corruptReceipt(service,config,command,"fractional teacher id",v->rows(v.get("rows")).get(0).put("teacherId",1.5));
        corruptReceipt(service,config,command,"extra private row data",v->rows(v.get("rows")).get(0).put("password","SYNTHETIC PRIVATE"));
        corruptReceipt(service,config,command,"unapproved top-level key",v->v.put("permissionsPublished",true));
        corruptReceipt(service,config,command,"wrong summary",v->object(v.get("summary")).put("teachers",99));
        corruptReceipt(service,config,command,"wrong source fingerprint",v->v.put("sourceFingerprint","f".repeat(64)));
        corruptReceipt(service,config,command,"wrong batch",v->v.put("batchKey","synthetic-forged"));
        corruptReceipt(service,config,command,"false import flag",v->v.put("imported",false));
        String original=String.valueOf(Db.one("SELECT receipt FROM " + BATCHES + " WHERE batch_key=?",config.batchKey()).get("receipt"));
        try {
            Db.exec("UPDATE " + BATCHES + " SET receipt='{' WHERE batch_key=?",config.batchKey());
            rejects(409,()->service.preview(admin),"malformed receipt JSON fails closed");
            rejects(409,()->new TeacherRosterImport(config).commit(admin,command),"malformed receipt cannot replay across instances");
        } finally { Db.exec("UPDATE " + BATCHES + " SET receipt=? WHERE batch_key=?",original,config.batchKey()); }
        Map<String,Object> person=Db.one("SELECT * FROM " + PEOPLE + " WHERE source_reference='teacher-row-3'");
        try {
            Db.exec("UPDATE " + PEOPLE + " SET source_fingerprint=? WHERE source_reference='teacher-row-3'","e".repeat(64));
            rejects(409,()->service.preview(admin),"receipt-to-person source fingerprint must agree");
            rejects(409,()->new TeacherRosterImport(config).commit(admin,command),"replay validates all durable person rows");
        } finally { Db.exec("UPDATE " + PEOPLE + " SET source_fingerprint=? WHERE source_reference='teacher-row-3'",person.get("source_fingerprint")); }
        // Every account row remains a prerequisite even after the teacher receipt was persisted.
        accountIntakeCorruption(service,config);
    }
    private static void corruptReceipt(TeacherRosterImport service,Config config,Map<String,Object> command,String label,Change change) throws Exception {
        String original=String.valueOf(Db.one("SELECT receipt FROM " + BATCHES + " WHERE batch_key=?",config.batchKey()).get("receipt"));
        Map<String,Object> corrupted=object(Json.parse(original)); change.apply(corrupted);
        try {
            Db.exec("UPDATE " + BATCHES + " SET receipt=? WHERE batch_key=?",Json.write(corrupted),config.batchKey());
            String damaged=state();
            rejects(409,()->service.preview(admin),"preview detects receipt " + label);
            rejects(409,()->new TeacherRosterImport(config).commit(admin,command),"durable replay detects receipt " + label);
            unchanged(damaged,"damaged receipt rejection is read-only: " + label);
        } finally { Db.exec("UPDATE " + BATCHES + " SET receipt=? WHERE batch_key=?",original,config.batchKey()); }
    }
    private static void constraints(Map<String,Object> receipt) throws Exception {
        Map<String,Object> first=rows(receipt.get("rows")).get(0), second=rows(receipt.get("rows")).get(1);
        String before=state();
        sqlRejects(()->Db.exec("UPDATE " + PEOPLE + " SET teacher_id=? WHERE source_reference=?",first.get("teacherId"),second.get("reference")),"teacher relation has database-enforced uniqueness");
        sqlRejects(()->Db.exec("UPDATE " + PEOPLE + " SET account_id=? WHERE source_reference=?",first.get("accountId"),second.get("reference")),"account relation has database-enforced uniqueness");
        sqlRejects(()->Db.exec("DELETE FROM teachers WHERE id=?",first.get("teacherId")),"imported teacher cannot be deleted from under its receipt");
        sqlRejects(()->Db.exec("DELETE FROM users WHERE id=?",first.get("accountId")),"imported account cannot be deleted from under its receipt");
        Set<String> foreignKeys=new HashSet<>();
        try(ResultSet keys=Db.get().getMetaData().getImportedKeys(null,"PUBLIC",PEOPLE.toUpperCase(Locale.ROOT))) {
            while(keys.next()) foreignKeys.add(keys.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT)+":"+keys.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT));
        }
        check(foreignKeys.contains("account_id:users") && foreignKeys.contains("teacher_id:teachers"),"both teacher and account links carry real database foreign keys");
        unchanged(before,"failed duplicate and delete operations preserve all mappings");
    }
    public static final class FailSecondTeacher implements Trigger {
        static int count;
        public void fire(Connection connection,Object[] oldRow,Object[] newRow) throws SQLException {
            if(++count==2) throw new SQLException("SYNTHETIC PRIVATE second-teacher failure");
        }
    }
    public static final class FailReceipt implements Trigger {
        public void fire(Connection connection,Object[] oldRow,Object[] newRow) throws SQLException {
            throw new SQLException("SYNTHETIC PRIVATE teacher-receipt failure");
        }
    }
}
