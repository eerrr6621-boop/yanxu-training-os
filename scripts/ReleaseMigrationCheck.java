package com.training;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Synthetic additive-migration gate, never a production/copy migration tool.
 * Frozen legacy DDL below comes from Db.java's 15 original CREATE TABLE statements
 * and the six absent region columns recorded in scripts/DbMigrationCheck.java.
 * It also predates teacher_level and every integration table. This is a deliberately
 * constructed compatibility boundary, NOT an attested historical production schema.
 * Every database is created here in a fresh private temporary child directory.
 * Two independent JVMs invoke the supplied release build's Db.init, with no Main,
 * listener, login, importer source, demo bootstrap or real database input.
 */
public final class ReleaseMigrationCheck {
    private static int checks;
    private static String phase = "arguments";
    private static final Set<String> NEW_TABLES = Set.of(
        "ORGANIZATION_ACCESS_CONFIG", "ORGANIZATION_ACCOUNT_IMPORT_STATE",
        "ORGANIZATION_ACCOUNT_IMPORT_BATCHES", "ORGANIZATION_ACCOUNT_IMPORT_PEOPLE",
        "ORGANIZATION_MANAGEMENT_GROUP_PUBLICATIONS",
        "TEACHER_ROSTER_IMPORT_BATCHES", "TEACHER_ROSTER_IMPORT_PEOPLE",
        "S01_ACCOUNT_EMAIL_HEADS", "S01_ACCOUNT_EMAIL_REVISIONS", "S01_ACCOUNT_EMAIL_REQUESTS",
        "S01_ACCOUNT_EMAIL_MAINTENANCE_HEADS", "S01_ACCOUNT_EMAIL_MAINTENANCE_REVISIONS", "S01_ACCOUNT_EMAIL_MAINTENANCE_REQUESTS",
        "WORKFLOW_DEMANDS", "WORKFLOW_DOCUMENTS", "APPROVAL_WORKFLOWS", "APPROVAL_EVENTS",
        "WORKFLOW_BID_RESULTS", "WORKFLOW_ACCEPTANCES", "WORKFLOW_REQUESTS", "APPROVAL_REMINDERS", "WORKFLOW_OUTBOX",
        "S01_NOTIFICATION_EVENTS", "S01_NOTIFICATIONS",
        "M04_CATALOG_SCOPES", "M04_CATALOG_BATCHES", "M04_CATALOG_REVISIONS", "M04_TEACHER_BINDINGS", "M04_CATALOG_EVENTS",
        "M05_DELIVERY_FACTS", "M05_FACT_REVISIONS", "M05_POLICY_VERSIONS", "M05_SETTLEMENT_CONFIGS", "M05_SETTLEMENT_SNAPSHOTS", "M05_REQUESTS",
        "M05_EVIDENCE_RECORDS", "M05_FINANCIAL_CHAINS", "M05_FINANCIAL_ENTRIES", "M05_PAYMENT_EVENTS",
        "M05_FEE_CLAIMS", "M05_FEE_CLAIM_EVENTS", "M05_EXECUTION_SETTINGS", "M05_EXECUTION_SETTINGS_HEADS",
        "M05_CASES", "M05_CASE_REVISIONS", "M05_CASE_REQUESTS",
        "M05_CODING_HEADS", "M05_CODING_REVISIONS", "M05_CODING_PREVIEWS", "M05_CODING_REQUESTS",
        "M07_POLICY_REVISIONS", "M07_POLICY_HEADS", "M07_RESULT_HEADS", "M07_RESULT_EVENTS", "M07_FILE_CLAIMS", "M07_RESULT_REQUESTS",
        "M08_SUMMARY_HEADS", "M08_SUMMARY_REVISIONS", "M08_SUMMARY_REQUESTS", "M08_SUMMARY_PHOTOS",
        "M08_SUMMARY_WORKFLOW_HEADS", "M08_SUMMARY_WORKFLOW_EVENTS", "M08_SUMMARY_WORKFLOW_REQUESTS");
    private static final Map<String, Map<String,Integer>> ADDED = Map.of(
        "TEACHERS", Map.of("BASE_PROVINCE",64,"BASE_CITY",64,"TEACHER_LEVEL",32),
        "DEMANDS", Map.of("TRAINING_PROVINCE",64,"TRAINING_CITY",64,"TRAINING_MODE",16,"TRAINING_PERIOD",16));
    private record Column(String name, int ordinal, int type, String typeName, int size,
            int scale, int nullable, String defaultValue, String identity, String identityBase) {}
    private record Table(List<Column> columns, long rows, String hash, List<String> indexes, List<String> primaryKey, List<String> foreignKeys) {}
    private record Snapshot(SortedMap<String,Table> tables, List<String> constraints) {}
    private static final String[] LEGACY_DDL = {
        "CREATE TABLE users(id IDENTITY PRIMARY KEY, username VARCHAR(64) UNIQUE, password VARCHAR(128),name VARCHAR(64), role VARCHAR(16), status INT DEFAULT 1, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE demands(id IDENTITY PRIMARY KEY, title VARCHAR(200), unit VARCHAR(200), contact VARCHAR(64), phone VARCHAR(32),hours DOUBLE, content CLOB, teacher_req CLOB, expect_date VARCHAR(32), status VARCHAR(16) DEFAULT '待处理',remark CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE bids(id IDENTITY PRIMARY KEY, demand_id BIGINT, amount DOUBLE, proposal CLOB, bid_date VARCHAR(32),status VARCHAR(16) DEFAULT '待评审', review CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE projects(id IDENTITY PRIMARY KEY, demand_id BIGINT, bid_id BIGINT, title VARCHAR(200), unit VARCHAR(200),hours DOUBLE, amount DOUBLE, start_date VARCHAR(32), end_date VARCHAR(32),owner VARCHAR(64), participant_count INT DEFAULT 0, delivery_mode VARCHAR(32), venue VARCHAR(200), contract_no VARCHAR(64),status VARCHAR(16) DEFAULT '进行中', remark CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE teachers(id IDENTITY PRIMARY KEY, name VARCHAR(64), gender VARCHAR(8), org VARCHAR(200), title VARCHAR(64),field VARCHAR(200), phone VARCHAR(32), email VARCHAR(64), fee_rate DOUBLE, intro CLOB,status VARCHAR(16) DEFAULT '在库', in_date VARCHAR(32), out_date VARCHAR(32), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE teacher_evals(id IDENTITY PRIMARY KEY, teacher_id BIGINT, project_id BIGINT, score DOUBLE, comment CLOB,evaluator VARCHAR(64), eval_date VARCHAR(32), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE dispatches(id IDENTITY PRIMARY KEY, project_id BIGINT, teacher_id BIGINT, subject VARCHAR(200), teach_date VARCHAR(32),start_time VARCHAR(16), end_time VARCHAR(16), venue VARCHAR(200), confirm_deadline VARCHAR(32), material_status VARCHAR(32),hours DOUBLE, status VARCHAR(16) DEFAULT '待发送', sent_at VARCHAR(32), confirmed_at VARCHAR(32),msg_log CLOB, remark CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE questionnaires(id IDENTITY PRIMARY KEY, title VARCHAR(200), target VARCHAR(16), project_id BIGINT,questions CLOB, status VARCHAR(16) DEFAULT '草稿', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE q_sends(id IDENTITY PRIMARY KEY, questionnaire_id BIGINT, channel VARCHAR(16) DEFAULT '微信', target_desc VARCHAR(200),send_count INT DEFAULT 0, token VARCHAR(64) UNIQUE, sent_at VARCHAR(32), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE q_responses(id IDENTITY PRIMARY KEY, send_id BIGINT, questionnaire_id BIGINT, respondent VARCHAR(64),answers CLOB, avg_score DOUBLE, submitted_at VARCHAR(32))",
        "CREATE TABLE charges(id IDENTITY PRIMARY KEY, project_id BIGINT, amount DOUBLE, received DOUBLE DEFAULT 0, charge_date VARCHAR(32),status VARCHAR(16) DEFAULT '未收费', invoice VARCHAR(64), remark CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE fees(id IDENTITY PRIMARY KEY, project_id BIGINT, teacher_id BIGINT, hours DOUBLE, rate DOUBLE, amount DOUBLE,status VARCHAR(16) DEFAULT '待发放', pay_date VARCHAR(32), remark CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE costs(id IDENTITY PRIMARY KEY, project_id BIGINT, type VARCHAR(32), amount DOUBLE, cost_date VARCHAR(32),note CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE materials(id IDENTITY PRIMARY KEY, title VARCHAR(200) NOT NULL, category VARCHAR(64), summary CLOB,version VARCHAR(32), file_name VARCHAR(255) NOT NULL, storage_name VARCHAR(64) UNIQUE NOT NULL,content_type VARCHAR(128), file_size BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL,status VARCHAR(16) DEFAULT '上架', download_count BIGINT DEFAULT 0, created_by BIGINT,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE INDEX idx_materials_status_created ON materials(status, created_at)",
        "CREATE TABLE teacher_resumes(id IDENTITY PRIMARY KEY, teacher_id BIGINT NOT NULL, file_name VARCHAR(255) NOT NULL,storage_name VARCHAR(64) UNIQUE NOT NULL, file_size BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL,page_count INT DEFAULT 0, extracted_text CLOB, profile_json CLOB, manual_profile CLOB, profile_source VARCHAR(24),parse_status VARCHAR(24) DEFAULT 'queued', parse_error VARCHAR(500), is_current BOOLEAN DEFAULT FALSE,uploaded_by BIGINT, reviewed_by BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,parsed_at TIMESTAMP, reviewed_at TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,UNIQUE(teacher_id,sha256))",
        "CREATE INDEX idx_teacher_resumes_teacher_current ON teacher_resumes(teacher_id,is_current,created_at)",
        "CREATE INDEX idx_teacher_resumes_status ON teacher_resumes(parse_status,created_at)",
    };

    public static void main(String[] args) {
        Path root = null;
        try {
            if (args.length == 3 && args[0].equals("--initialize-owned")) {
                initializeOwned(Path.of(args[1]), args[2]);
                return;
            }
            require(args.length == 2 && args[0].equals("--work-root"), "USE_CHECK_RELEASE_MIGRATION_SCRIPT");
            Path container = Path.of(args[1]).toRealPath();
            Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            require(container.startsWith(temporary) && Files.isDirectory(container), "TEMPORARY_ROOT_REQUIRED");
            require(!container.toString().matches("(?s).*[;\\r\\n].*"), "UNSAFE_JDBC_PATH");
            Path releaseClasses = Path.of(Db.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            String releaseDigest = classFingerprint(releaseClasses);
            root = Files.createTempDirectory(container, "synthetic-legacy-").toRealPath();
            String marker = UUID.randomUUID().toString();
            Files.writeString(root.resolve("owned.marker"), marker, StandardOpenOption.CREATE_NEW);
            Path data = Files.createDirectory(root.resolve("data"));
            phase = "create_synthetic_legacy";
            createLegacy(data);
            Snapshot before;
            try (Connection c = readOnly(data)) {
                before = snapshot(c, null);
                require(before.tables.size() == 15, "LEGACY_TABLE_COUNT");
                require(before.tables.values().stream().allMatch(t -> t.rows > 0), "ALL_LEGACY_TABLES_HAVE_ROWS");
                require(scalar(c,"SELECT COUNT(*) FROM users") == 2, "SYNTHETIC_ACCOUNTS_EXIST");
                require(scalar(c,"SELECT COUNT(*) FROM users WHERE status=0") == 1, "DISABLED_ACCOUNT_EXISTS");
            }
            Snapshot initialized = null;
            for (int pass=1; pass<=2; pass++) {
                phase = "independent_jvm_init_" + pass;
                runInitializer(root, marker, pass);
                phase = "read_only_verify_" + pass;
                try (Connection c = readOnly(data)) {
                    Snapshot legacyProjection = snapshot(c, before);
                    verifyLegacy(before, legacyProjection, c);
                    verifyAdditions(before, legacyProjection, c);
                    Snapshot current = snapshot(c, null);
                    if (initialized == null) initialized = current;
                    else require(initialized.equals(current), "RESTART_SCHEMA_DATA_INDEX_CONSTRAINT_IDEMPOTENCE");
                }
            }
            require(releaseDigest.equals(classFingerprint(releaseClasses)), "RELEASE_CLASSES_CHANGED_DURING_CHECK");
            long rows = before.tables.values().stream().mapToLong(Table::rows).sum();
            long columns = before.tables.values().stream().mapToLong(t -> t.columns.size()).sum();
            String report = "{\"ok\":true,\"scope\":\"synthetic_legacy_only\",\"productionMigrationVerified\":false,"
                + "\"checks\":" + checks + ",\"legacyTables\":15,\"legacyRows\":" + rows + ",\"legacyColumns\":" + columns
                + ",\"addedNullableColumns\":7,\"newTables\":" + NEW_TABLES.size()
                + ",\"controlRowsCreated\":1,\"independentInitProcesses\":2,\"oldRowsAndAccountHashesUnchanged\":true,"
                + "\"demoCreated\":false,\"newBusinessAndPermissionRows\":0,\"schemaAndDataIdempotent\":true,"
                + "\"mainStartupTested\":false,\"releaseClasses\":" + json(releaseClasses.toString())
                + ",\"releaseClassesSha256\":" + json(releaseDigest) + ",\"dataDirectory\":" + json(data.toString()) + "}";
            Files.writeString(root.resolve("result.json"),report + "\n",StandardOpenOption.CREATE_NEW);
            System.out.println(report);
            System.out.println("evidence=" + root);
        } catch (Exception failure) {
            String code = failure instanceof CheckFailure ? failure.getMessage() : failure.getClass().getSimpleName();
            String report = "{\"ok\":false,\"scope\":\"synthetic_legacy_only\",\"phase\":" + json(phase) + ",\"error\":" + json(code) + "}";
            System.out.println(report);
            if (root != null) {
                try { Files.writeString(root.resolve("failure.json"),report + "\n"); } catch (IOException ignored) {}
                System.out.println("evidence=" + root);
            }
            // Do not print SQL exception messages: a later extended fixture may include private values.
            System.exit(1);
        }
    }

    private static String classFingerprint(Path directory) throws Exception {
        require(Files.isDirectory(directory), "RELEASE_CLASS_DIRECTORY_REQUIRED");
        List<Path> classes;
        try (var paths = Files.walk(directory)) {
            classes = paths.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing(p -> directory.relativize(p).toString())).toList();
        }
        require(!classes.isEmpty(), "RELEASE_CLASSES_EMPTY");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DataOutputStream out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
            for (Path file : classes) {
                byte[] name = directory.relativize(file).toString().getBytes(StandardCharsets.UTF_8);
                out.writeInt(name.length); out.write(name); out.writeLong(Files.size(file));
                try (InputStream in = Files.newInputStream(file)) { in.transferTo(out); }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void createLegacy(Path data) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + data.resolve("training") + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "")) {
            try (Statement st = c.createStatement()) { for (String ddl : LEGACY_DDL) st.execute(ddl); }
            String legacyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("synthetic-legacy-password".getBytes(StandardCharsets.UTF_8)));
            insert(c,"INSERT INTO users(username,password,name,role,status,created_at) VALUES(?,?,?,?,?,?)",
                "migration_legacy",legacyHash,"合成旧账号","viewer",1,"2026-01-02 03:04:05");
            insert(c,"INSERT INTO users(username,password,name,role,status,created_at) VALUES(?,?,?,?,?,?)",
                "migration_disabled",Auth.hash("synthetic-disabled-password"),"合成停用账号","manager",0,"2026-01-03 04:05:06");
            insert(c,"INSERT INTO demands(title,unit,contact,phone,hours,content,teacher_req,expect_date,status,remark) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "合成旧需求","SYNTHETIC",null,"",4.25,"历史正文\n换行与😀","未知保留",null,"已立项","合成备注");
            insert(c,"INSERT INTO demands(title,unit,hours,content,status) VALUES(?,?,?,?,?)","合成空值需求","SYNTHETIC",0.0,"","待处理");
            insert(c,"INSERT INTO bids(demand_id,amount,proposal,bid_date,status,review) VALUES(?,?,?,?,?,?)",1,1200.5,"历史投标", "2026-01-04","已中标",null);
            insert(c,"INSERT INTO projects(demand_id,bid_id,title,unit,hours,amount,status,remark) VALUES(?,?,?,?,?,?,?,?)",
                1,1,"中层干部领导力提升培训班","SYNTHETIC",4.25,1200.5,"进行中","同名也不能自动填演示运营字段");
            insert(c,"INSERT INTO projects(title,unit,hours,amount,owner,participant_count,delivery_mode,venue,contract_no,status) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "合成历史归档项目","SYNTHETIC",0,0,"旧负责人",7,"线下","旧场所","SYNTHETIC-OLD","已归档");
            insert(c,"INSERT INTO teachers(name,gender,org,title,field,phone,email,fee_rate,intro,status,in_date) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                "合成旧讲师",null,"SYNTHETIC","讲师","教学",null,"",123.45,"旧简历\n不能推断地区", "在库","2026-01-01");
            insert(c,"INSERT INTO teachers(name,org,fee_rate,status) VALUES(?,?,?,?)","合成退出讲师","SYNTHETIC",0,"出库");
            insert(c,"INSERT INTO teacher_evals(teacher_id,project_id,score,comment,evaluator,eval_date) VALUES(?,?,?,?,?,?)",1,1,4.5,"合成评价","旧评价人","2026-01-05");
            insert(c,"INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,hours,status,msg_log,remark) VALUES(?,?,?,?,?,?,?,?)",
                1,1,"战略思维与领导力",null,1.5,"待发送","历史日志\n第二行","未知时间保持空");
            insert(c,"INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,hours,status,material_status) VALUES(?,?,?,?,?,?,?,?,?)",
                2,2,"合成旧完成课","2026-01-05","09:00","10:00",1,"已完成","已准备");
            insert(c,"INSERT INTO questionnaires(title,target,project_id,questions,status) VALUES(?,?,?,?,?)","合成旧问卷","学员",1,"[{\"question\":\"合成?\"}]","已发布");
            insert(c,"INSERT INTO q_sends(questionnaire_id,channel,target_desc,send_count,token,sent_at) VALUES(?,?,?,?,?,?)",1,"线下","合成对象",1,"synthetic-token-not-live","2026-01-05");
            insert(c,"INSERT INTO q_responses(send_id,questionnaire_id,respondent,answers,avg_score,submitted_at) VALUES(?,?,?,?,?,?)",1,1,"合成回答者","{\"score\":4}",4.0,"2026-01-06");
            insert(c,"INSERT INTO charges(project_id,amount,received,charge_date,status,invoice,remark) VALUES(?,?,?,?,?,?,?)",1,1200.5,100,"2026-01-06","部分收费","SYNTHETIC-INVOICE",null);
            insert(c,"INSERT INTO fees(project_id,teacher_id,hours,rate,amount,status,pay_date,remark) VALUES(?,?,?,?,?,?,?,?)",2,2,1,0,0,"已发放","2026-01-06","保留旧费用语义");
            insert(c,"INSERT INTO costs(project_id,type,amount,cost_date,note) VALUES(?,?,?,?,?)",1,"差旅费",12.5,"2026-01-06","合成成本");
            insert(c,"INSERT INTO materials(title,category,summary,version,file_name,storage_name,content_type,file_size,sha256,status,download_count,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                "合成资料","旧分类","只有元数据，无真实文件","v1","synthetic.txt","synthetic-material","text/plain",5,"a".repeat(64),"上架",3,1);
            insert(c,"INSERT INTO teacher_resumes(teacher_id,file_name,storage_name,file_size,sha256,page_count,extracted_text,profile_json,manual_profile,profile_source,parse_status,is_current,uploaded_by,reviewed_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                1,"synthetic.pdf","synthetic-resume",10,"b".repeat(64),1,"合成旧简历正文","{\"tag\":\"历史\"}","{\"manual\":true}","manual","processing",true,1,1);
        }
    }

    private static void insert(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i=0;i<values.length;i++) st.setObject(i+1,values[i]);
            st.executeUpdate();
        }
    }

    private static void runInitializer(Path root, String marker, int pass) throws Exception {
        String java = Path.of(System.getProperty("java.home"),"bin","java").toString();
        ProcessBuilder builder = new ProcessBuilder(java,"-Dbootstrap.demo=false","-Dfile.encoding=UTF-8",
            "-cp",System.getProperty("java.class.path"),ReleaseMigrationCheck.class.getName(),"--initialize-owned",root.toString(),marker);
        for (String key : List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS")) builder.environment().remove(key);
        builder.redirectErrorStream(true).redirectOutput(root.resolve("init-" + pass + ".log").toFile());
        Process process = builder.start();
        boolean exited = process.waitFor(45,TimeUnit.SECONDS);
        if (!exited) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); }
        require(exited,"INIT_TIMEOUT_" + pass);
        require(process.exitValue() == 0,"INIT_PROCESS_FAILED_" + pass);
    }

    private static void initializeOwned(Path supplied, String marker) throws Exception {
        Path root = supplied.toRealPath();
        require(root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()),"OWNED_TEMP_REQUIRED");
        require(root.getFileName().toString().startsWith("synthetic-legacy-"),"OWNED_ROOT_REQUIRED");
        require(Files.readString(root.resolve("owned.marker")).equals(marker),"OWNED_MARKER_REQUIRED");
        Path data = root.resolve("data").toRealPath();
        require(data.getParent().equals(root) && Files.isRegularFile(data.resolve("training.mv.db"),LinkOption.NOFOLLOW_LINKS),"OWNED_DATABASE_REQUIRED");
        try (Connection c = readOnly(data)) { require(scalar(c,"SELECT COUNT(*) FROM users") == 2,"PREEXISTING_SYNTHETIC_USERS_REQUIRED"); }
        System.setProperty("data.dir",data.toString());
        System.setProperty("bootstrap.demo","false");
        System.clearProperty("account.import.manifest");
        System.clearProperty("account.import.manifest.sha256");
        PrintStream stdout = System.out, stderr = System.err;
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(quiet); System.setErr(quiet);
            try { Db.init(); }
            finally {
                try (Connection c = Db.get(); Statement st = c.createStatement()) { st.execute("SHUTDOWN"); }
            }
        } finally { System.setOut(stdout); System.setErr(stderr); }
        System.out.println("{\"ok\":true,\"dbInit\":true,\"demo\":false}");
    }

    private static Connection readOnly(Path data) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:h2:" + data.resolve("training") + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE;DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE","sa","");
        c.setReadOnly(true);
        return c;
    }

    private static Snapshot snapshot(Connection c, Snapshot projection) throws Exception {
        SortedMap<String,Table> tables = new TreeMap<>();
        DatabaseMetaData meta = c.getMetaData();
        List<String> names = new ArrayList<>();
        try (ResultSet rs = meta.getTables(null,"PUBLIC","%",new String[]{"TABLE","BASE TABLE"})) {
            while (rs.next()) names.add(rs.getString("TABLE_NAME"));
        }
        for (String name : names) {
            List<Column> columns = new ArrayList<>();
            String pattern = name.replace("_","\\_");
            try (ResultSet rs = meta.getColumns(null,"PUBLIC",pattern,"%")) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME"), identityBase;
                    try (PreparedStatement st = c.prepareStatement("SELECT IDENTITY_BASE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME=? AND COLUMN_NAME=?")) {
                        st.setString(1,name); st.setString(2,col);
                        try (ResultSet identity = st.executeQuery()) { identity.next(); identityBase = identity.getString(1); }
                    }
                    columns.add(new Column(col,rs.getInt("ORDINAL_POSITION"),rs.getInt("DATA_TYPE"),rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),rs.getInt("DECIMAL_DIGITS"),rs.getInt("NULLABLE"),rs.getString("COLUMN_DEF"),rs.getString("IS_AUTOINCREMENT"),identityBase));
                }
            }
            columns.sort(Comparator.comparingInt(Column::ordinal));
            List<Column> selected = projection != null && projection.tables.containsKey(name) ? projection.tables.get(name).columns : columns;
            List<byte[]> rows = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT " + String.join(",",selected.stream().map(col -> quote(col.name)).toList()) + " FROM PUBLIC." + quote(name))) {
                while (rs.next()) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (DataOutputStream out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
                        for (int i=0;i<selected.size();i++) {
                            out.writeInt(selected.get(i).type);
                            String value = rs.getString(i+1);
                            out.writeBoolean(value != null);
                            if (value != null) { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes); }
                        }
                    }
                    rows.add(digest.digest());
                }
            }
            rows.sort(Arrays::compareUnsigned);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            rows.forEach(digest::update);
            List<String> indexes = new ArrayList<>(), primary = new ArrayList<>(), foreign = new ArrayList<>();
            try (ResultSet rs = meta.getIndexInfo(null,"PUBLIC",name,false,false)) {
                while (rs.next()) indexes.add(rs.getString("INDEX_NAME") + "|" + rs.getBoolean("NON_UNIQUE") + "|" + rs.getShort("ORDINAL_POSITION") + "|" + rs.getString("COLUMN_NAME") + "|" + rs.getString("ASC_OR_DESC"));
            }
            try (ResultSet rs = meta.getPrimaryKeys(null,"PUBLIC",name)) {
                while (rs.next()) primary.add(rs.getString("PK_NAME") + "|" + rs.getShort("KEY_SEQ") + "|" + rs.getString("COLUMN_NAME"));
            }
            try (ResultSet rs = meta.getImportedKeys(null,"PUBLIC",name)) {
                while (rs.next()) foreign.add(rs.getString("FK_NAME") + "|" + rs.getShort("KEY_SEQ") + "|" + rs.getString("FKCOLUMN_NAME") + "|" + rs.getString("PKTABLE_NAME") + "|" + rs.getString("PKCOLUMN_NAME") + "|" + rs.getShort("UPDATE_RULE") + "|" + rs.getShort("DELETE_RULE"));
            }
            Collections.sort(indexes); Collections.sort(primary); Collections.sort(foreign);
            tables.put(name,new Table(List.copyOf(columns),rows.size(),HexFormat.of().formatHex(digest.digest()),List.copyOf(indexes),List.copyOf(primary),List.copyOf(foreign)));
        }
        List<String> constraints = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT t.TABLE_NAME,t.CONSTRAINT_NAME,t.CONSTRAINT_TYPE,c.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t LEFT JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS c ON t.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE t.CONSTRAINT_SCHEMA='PUBLIC'")) {
            while (rs.next()) constraints.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4));
        }
        Collections.sort(constraints);
        return new Snapshot(tables,List.copyOf(constraints));
    }

    private static void verifyLegacy(Snapshot before, Snapshot after, Connection c) throws Exception {
        for (var entry : before.tables.entrySet()) {
            String name = entry.getKey(); Table old = entry.getValue(), now = after.tables.get(name);
            require(now != null,"LEGACY_TABLE_MISSING_" + name);
            require(old.rows == now.rows && old.hash.equals(now.hash),"LEGACY_ROWS_CHANGED_" + name);
            Map<String,Column> added = new TreeMap<>();
            now.columns.forEach(col -> added.put(col.name,col));
            for (Column col : old.columns) require(col.equals(added.remove(col.name)),"LEGACY_COLUMN_CHANGED_" + name + "_" + col.name);
            // H2 rebuilds a table when adding columns and may rename its generated PK index.
            // Preserve uniqueness/order/columns and named indexes, rather than the generated suffix.
            require(indexSemantics(old.indexes).equals(indexSemantics(now.indexes)) && old.primaryKey.equals(now.primaryKey) && old.foreignKeys.equals(now.foreignKeys),"LEGACY_INDEX_KEY_CHANGED_" + name);
            Map<String,Integer> expected = ADDED.getOrDefault(name,Map.of());
            require(added.keySet().equals(expected.keySet()),"ADDED_COLUMN_SET_" + name);
            for (Column col : added.values()) {
                require(col.type == Types.VARCHAR && col.size == expected.get(col.name) && col.nullable == DatabaseMetaData.columnNullable && col.defaultValue == null && col.identity.equals("NO"),"ADDED_COLUMN_DEFINITION_" + col.name);
                require(scalar(c,"SELECT COUNT(*) FROM " + quote(name) + " WHERE " + quote(col.name) + " IS NOT NULL") == 0,"INVENTED_HISTORICAL_VALUE_" + col.name);
            }
        }
        require(after.constraints.containsAll(before.constraints),"LEGACY_CONSTRAINT_REMOVED");
        require(scalar(c,"SELECT COUNT(*) FROM projects WHERE id=1 AND owner IS NULL AND venue IS NULL AND contract_no IS NULL AND participant_count=0") == 1,"DEMO_PROJECT_AUTOFILL");
        require(scalar(c,"SELECT COUNT(*) FROM dispatches WHERE id=1 AND start_time IS NULL AND material_status IS NULL") == 1,"DEMO_DISPATCH_AUTOFILL");
        require(scalar(c,"SELECT COUNT(*) FROM users WHERE username IN ('admin','manager','viewer')") == 0,"DEMO_OR_BOOTSTRAP_ACCOUNT_CREATED");
        require(scalar(c,"SELECT COUNT(*) FROM teacher_resumes WHERE parse_status='processing'") == 1,"INIT_CHANGED_RESUME_STATE");
    }

    private static void verifyAdditions(Snapshot before, Snapshot after, Connection c) throws Exception {
        Set<String> added = new TreeSet<>(after.tables.keySet()); added.removeAll(before.tables.keySet());
        require(added.equals(NEW_TABLES),"NEW_INTEGRATION_TABLE_SET");
        for (String table : added) {
            Table data = after.tables.get(table);
            require(data.rows == (table.equals("ORGANIZATION_ACCOUNT_IMPORT_STATE") ? 1 : 0),"UNEXPECTED_NEW_ROWS_" + table);
            require(!data.primaryKey.isEmpty(),"NEW_PRIMARY_KEY_MISSING_" + table);
        }
        require(scalar(c,"SELECT COUNT(*) FROM organization_account_import_state WHERE singleton=1 AND revision=0") == 1,"IMPORT_STATE_NOT_INITIAL");
        require(hasIndex(after,"MATERIALS","IDX_MATERIALS_STATUS_CREATED",List.of("STATUS","CREATED_AT")),"MATERIALS_INDEX");
        require(hasIndex(after,"TEACHER_RESUMES","IDX_TEACHER_RESUMES_TEACHER_CURRENT",List.of("TEACHER_ID","IS_CURRENT","CREATED_AT")),"RESUMES_CURRENT_INDEX");
        require(hasIndex(after,"TEACHER_RESUMES","IDX_TEACHER_RESUMES_STATUS",List.of("PARSE_STATUS","CREATED_AT")),"RESUMES_STATUS_INDEX");
        require(hasIndex(after,"S01_NOTIFICATIONS","S01_NOTIFICATIONS_OWNER",List.of("ACCOUNT_ID","EVENT_KEY")),"NOTIFICATION_OWNER_INDEX");
        require(hasIndex(after,"S01_ACCOUNT_EMAIL_HEADS","S01_ACCOUNT_EMAIL_DUPLICATES",List.of("EMAIL_KEY")),"EMAIL_DUPLICATES_INDEX");
        for (String table : List.of("ORGANIZATION_ACCOUNT_IMPORT_PEOPLE","TEACHER_ROSTER_IMPORT_PEOPLE","WORKFLOW_ACCEPTANCES","M04_TEACHER_BINDINGS","M05_DELIVERY_FACTS","M08_SUMMARY_REQUESTS","S01_NOTIFICATIONS"))
            require(!after.tables.get(table).foreignKeys.isEmpty(),"NEW_FOREIGN_KEY_MISSING_" + table);
    }

    private static List<String> indexSemantics(List<String> indexes) {
        return indexes.stream().map(i -> i.replaceFirst("^PRIMARY_KEY_[0-9A-F]+\\|", "GENERATED_PRIMARY|" )).sorted().toList();
    }

    private static boolean hasIndex(Snapshot s, String table, String name, List<String> columns) {
        List<String> matches = s.tables.get(table).indexes.stream().filter(i -> i.startsWith(name + "|")).toList();
        if (matches.size() != columns.size()) return false;
        for (int i=0;i<columns.size();i++) if (!matches.contains(name + "|true|" + (i+1) + "|" + columns.get(i) + "|A")) return false;
        return true;
    }
    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
    private static String quote(String text) { return "\"" + text.replace("\"","\"\"") + "\""; }
    private static String json(String text) { return "\"" + text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r") + "\""; }
    private static void require(boolean ok, String code) throws CheckFailure { checks++; if (!ok) throw new CheckFailure(code); }
    private static final class CheckFailure extends Exception {
        private static final long serialVersionUID = 1L;
        CheckFailure(String code) { super(code); }
    }
}
