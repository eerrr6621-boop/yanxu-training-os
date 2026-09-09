package com.training;

import java.io.File;
import java.sql.*;
import java.util.*;

/** H2 嵌入式数据库：连接、建表、种子数据、通用查询 */
public class Db {
    private static Connection conn;

    @FunctionalInterface
    public interface TransactionWork<T> {
        T run() throws Exception;
    }

    public static synchronized Connection get() throws SQLException {
        if (conn == null || conn.isClosed()) {
            String configuredDir = System.getProperty("data.dir", "data").trim();
            File dir = new File(configuredDir.isEmpty() ? "data" : configuredDir);
            if (!dir.exists() && !dir.mkdirs()) throw new SQLException("无法创建数据目录: " + dir.getAbsolutePath());
            String databasePath = new File(dir, "training").getAbsolutePath();
            conn = DriverManager.getConnection("jdbc:h2:" + databasePath + ";DB_CLOSE_DELAY=-1", "sa", "");
            conn.setAutoCommit(true);
        }
        return conn;
    }

    public static void init() throws SQLException {
        try (Statement st = get().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS users(" +
                    "id IDENTITY PRIMARY KEY, username VARCHAR(64) UNIQUE, password VARCHAR(128)," +
                    "name VARCHAR(64), role VARCHAR(16), status INT DEFAULT 1, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS demands(" +
                    "id IDENTITY PRIMARY KEY, title VARCHAR(200), unit VARCHAR(200), contact VARCHAR(64), phone VARCHAR(32)," +
                    "hours DOUBLE, content CLOB, teacher_req CLOB, expect_date VARCHAR(32), status VARCHAR(16) DEFAULT '待处理'," +
                    "remark CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS bids(" +
                    "id IDENTITY PRIMARY KEY, demand_id BIGINT, amount DOUBLE, proposal CLOB, bid_date VARCHAR(32)," +
                    "status VARCHAR(16) DEFAULT '待评审', review CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS projects(" +
                    "id IDENTITY PRIMARY KEY, demand_id BIGINT, bid_id BIGINT, title VARCHAR(200), unit VARCHAR(200)," +
                    "hours DOUBLE, amount DOUBLE, start_date VARCHAR(32), end_date VARCHAR(32)," +
                    "owner VARCHAR(64), participant_count INT DEFAULT 0, delivery_mode VARCHAR(32), venue VARCHAR(200), contract_no VARCHAR(64)," +
                    "status VARCHAR(16) DEFAULT '进行中', remark CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS teachers(" +
                    "id IDENTITY PRIMARY KEY, name VARCHAR(64), gender VARCHAR(8), org VARCHAR(200), title VARCHAR(64)," +
                    "field VARCHAR(200), phone VARCHAR(32), email VARCHAR(64), fee_rate DOUBLE, intro CLOB," +
                    "status VARCHAR(16) DEFAULT '在库', in_date VARCHAR(32), out_date VARCHAR(32), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS teacher_evals(" +
                    "id IDENTITY PRIMARY KEY, teacher_id BIGINT, project_id BIGINT, score DOUBLE, comment CLOB," +
                    "evaluator VARCHAR(64), eval_date VARCHAR(32), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS dispatches(" +
                    "id IDENTITY PRIMARY KEY, project_id BIGINT, teacher_id BIGINT, subject VARCHAR(200), teach_date VARCHAR(32)," +
                    "start_time VARCHAR(16), end_time VARCHAR(16), venue VARCHAR(200), confirm_deadline VARCHAR(32), material_status VARCHAR(32)," +
                    "hours DOUBLE, status VARCHAR(16) DEFAULT '待发送', sent_at VARCHAR(32), confirmed_at VARCHAR(32)," +
                    "msg_log CLOB, remark CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS questionnaires(" +
                    "id IDENTITY PRIMARY KEY, title VARCHAR(200), target VARCHAR(16), project_id BIGINT," +
                    "questions CLOB, status VARCHAR(16) DEFAULT '草稿', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS q_sends(" +
                    "id IDENTITY PRIMARY KEY, questionnaire_id BIGINT, channel VARCHAR(16) DEFAULT '微信', target_desc VARCHAR(200)," +
                    "send_count INT DEFAULT 0, token VARCHAR(64) UNIQUE, sent_at VARCHAR(32), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS q_responses(" +
                    "id IDENTITY PRIMARY KEY, send_id BIGINT, questionnaire_id BIGINT, respondent VARCHAR(64)," +
                    "answers CLOB, avg_score DOUBLE, submitted_at VARCHAR(32))");
            st.execute("CREATE TABLE IF NOT EXISTS charges(" +
                    "id IDENTITY PRIMARY KEY, project_id BIGINT, amount DOUBLE, received DOUBLE DEFAULT 0, charge_date VARCHAR(32)," +
                    "status VARCHAR(16) DEFAULT '未收费', invoice VARCHAR(64), remark CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS fees(" +
                    "id IDENTITY PRIMARY KEY, project_id BIGINT, teacher_id BIGINT, hours DOUBLE, rate DOUBLE, amount DOUBLE," +
                    "status VARCHAR(16) DEFAULT '待发放', pay_date VARCHAR(32), remark CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS costs(" +
                    "id IDENTITY PRIMARY KEY, project_id BIGINT, type VARCHAR(32), amount DOUBLE, cost_date VARCHAR(32)," +
                    "note CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE TABLE IF NOT EXISTS materials(" +
                    "id IDENTITY PRIMARY KEY, title VARCHAR(200) NOT NULL, category VARCHAR(64), summary CLOB," +
                    "version VARCHAR(32), file_name VARCHAR(255) NOT NULL, storage_name VARCHAR(64) UNIQUE NOT NULL," +
                    "content_type VARCHAR(128), file_size BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL," +
                    "status VARCHAR(16) DEFAULT '上架', download_count BIGINT DEFAULT 0, created_by BIGINT," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_materials_status_created ON materials(status, created_at)");
            st.execute("CREATE TABLE IF NOT EXISTS teacher_resumes(" +
                    "id IDENTITY PRIMARY KEY, teacher_id BIGINT NOT NULL, file_name VARCHAR(255) NOT NULL," +
                    "storage_name VARCHAR(64) UNIQUE NOT NULL, file_size BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL," +
                    "page_count INT DEFAULT 0, extracted_text CLOB, profile_json CLOB, manual_profile CLOB, profile_source VARCHAR(24)," +
                    "parse_status VARCHAR(24) DEFAULT 'queued', parse_error VARCHAR(500), is_current BOOLEAN DEFAULT FALSE," +
                    "uploaded_by BIGINT, reviewed_by BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "parsed_at TIMESTAMP, reviewed_at TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE(teacher_id,sha256))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_teacher_resumes_teacher_current " +
                    "ON teacher_resumes(teacher_id,is_current,created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_teacher_resumes_status " +
                    "ON teacher_resumes(parse_status,created_at)");
            st.execute("ALTER TABLE teacher_resumes ADD COLUMN IF NOT EXISTS manual_profile CLOB");
            // 兼容已有本地数据库：以非破坏方式补齐运营字段。
            // 不从单位/简历推断常驻地；历史未知值保留，首次编辑时由管理员补录。
            st.execute("ALTER TABLE teachers ADD COLUMN IF NOT EXISTS base_province VARCHAR(64)");
            st.execute("ALTER TABLE teachers ADD COLUMN IF NOT EXISTS base_city VARCHAR(64)");
            st.execute("ALTER TABLE teachers ADD COLUMN IF NOT EXISTS teacher_level VARCHAR(32)");
            st.execute("ALTER TABLE demands ADD COLUMN IF NOT EXISTS training_province VARCHAR(64)");
            st.execute("ALTER TABLE demands ADD COLUMN IF NOT EXISTS training_city VARCHAR(64)");
            st.execute("ALTER TABLE demands ADD COLUMN IF NOT EXISTS training_mode VARCHAR(16)");
            st.execute("ALTER TABLE demands ADD COLUMN IF NOT EXISTS training_period VARCHAR(16)");
            st.execute("ALTER TABLE projects ADD COLUMN IF NOT EXISTS owner VARCHAR(64)");
            st.execute("ALTER TABLE projects ADD COLUMN IF NOT EXISTS participant_count INT DEFAULT 0");
            st.execute("ALTER TABLE projects ADD COLUMN IF NOT EXISTS delivery_mode VARCHAR(32)");
            st.execute("ALTER TABLE projects ADD COLUMN IF NOT EXISTS venue VARCHAR(200)");
            st.execute("ALTER TABLE projects ADD COLUMN IF NOT EXISTS contract_no VARCHAR(64)");
            st.execute("ALTER TABLE dispatches ADD COLUMN IF NOT EXISTS start_time VARCHAR(16)");
            st.execute("ALTER TABLE dispatches ADD COLUMN IF NOT EXISTS end_time VARCHAR(16)");
            st.execute("ALTER TABLE dispatches ADD COLUMN IF NOT EXISTS venue VARCHAR(200)");
            st.execute("ALTER TABLE dispatches ADD COLUMN IF NOT EXISTS confirm_deadline VARCHAR(32)");
            st.execute("ALTER TABLE dispatches ADD COLUMN IF NOT EXISTS material_status VARCHAR(32)");
            // 只在明确启用的演示环境补填示例值，真实项目即使同名也不自动改写。
            if (Boolean.getBoolean("bootstrap.demo")) {
            st.execute("UPDATE projects SET owner='陈婧', participant_count=42, delivery_mode='线下集中', venue='市干部教育中心 302', contract_no='YX-2026-017' WHERE owner IS NULL AND title='中层干部领导力提升培训班'");
            st.execute("UPDATE projects SET owner='赵明', participant_count=60, delivery_mode='线下集中', venue='某商业银行培训中心 A1', contract_no='YX-2026-021' WHERE owner IS NULL AND title='新员工入职培训（第一期）'");
            st.execute("UPDATE projects SET owner='周航', participant_count=38, delivery_mode='线下集中', venue='机关党校报告厅', contract_no='YX-2026-026' WHERE owner IS NULL AND title='党史学习教育专题培训班'");
            st.execute("UPDATE dispatches SET start_time='09:00', end_time='12:00', venue='市干部教育中心 302', confirm_deadline='2026-08-10', material_status='准备中' WHERE start_time IS NULL AND project_id=1 AND subject='战略思维与领导力'");
            st.execute("UPDATE dispatches SET start_time='14:00', end_time='17:00', venue='市干部教育中心 302', confirm_deadline='2026-08-10', material_status='待准备' WHERE start_time IS NULL AND project_id=1 AND subject='团队建设与绩效管理'");
            st.execute("UPDATE dispatches SET start_time='09:00', end_time='16:30', venue='机关党校报告厅', confirm_deadline='2026-08-04', material_status='待准备' WHERE start_time IS NULL AND project_id=3 AND subject='党史专题辅导'");
            }
        }
        seed();
    }

    private static void seed() throws SQLException {
        if (count("users") > 0) return;
        boolean demo = Boolean.getBoolean("bootstrap.demo");
        String adminPassword = demo ? "admin123" : System.getProperty("bootstrap.admin.password", "").trim();
        boolean generated = false;
        if (!demo && adminPassword.isEmpty()) {
            adminPassword = Auth.randomPassword();
            generated = true;
        }
        if (!demo && adminPassword.length() < 12)
            throw new IllegalStateException("bootstrap.admin.password 至少需要12位");
        exec("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)",
                "admin", Auth.hash(adminPassword), "系统管理员", "admin");
        if (!demo) {
            if (generated)
                System.out.println("首次启动管理员：admin / " + adminPassword + "（请登录后立即修改）");
            return;
        }

        // 本地演示模式才创建体验账号和示例业务数据。
        exec("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)",
                "manager", Auth.hash("manager123"), "业务管理员", "manager");
        exec("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)",
                "viewer", Auth.hash("viewer123"), "查询用户", "viewer");

        exec("INSERT INTO teachers(name,gender,org,title,field,phone,email,fee_rate,intro,status,in_date) VALUES(?,?,?,?,?,?,?,?,?,'在库',?)",
                "王建国", "男", "清华大学经济管理学院", "教授", "企业管理、战略管理", "13800000001", "wangjg@example.com", 3000,
                "长期从事企业战略与组织变革研究，主讲《战略管理》《领导力提升》等课程，培训经验丰富。", "2024-03-01");
        exec("INSERT INTO teachers(name,gender,org,title,field,phone,email,fee_rate,intro,status,in_date) VALUES(?,?,?,?,?,?,?,?,?,'在库',?)",
                "李慧敏", "女", "北京大学光华管理学院", "副教授", "人力资源、绩效管理", "13800000002", "lihm@example.com", 2500,
                "专注人力资源管理与绩效体系设计，服务过多家大型企业内训项目。", "2024-05-12");
        exec("INSERT INTO teachers(name,gender,org,title,field,phone,email,fee_rate,intro,status,in_date) VALUES(?,?,?,?,?,?,?,?,?,'在库',?)",
                "张子昂", "男", "某省党校", "高级讲师", "党史党建、政策法规", "13800000003", "zhangza@example.com", 1800,
                "主讲党史党建、党风廉政建设等专题，授课风格深入浅出。", "2024-06-20");
        exec("INSERT INTO teachers(name,gender,org,title,field,phone,email,fee_rate,intro,status,in_date) VALUES(?,?,?,?,?,?,?,?,?,'在库',?)",
                "陈思远", "男", "自由讲师", "高级经济师", "财务管理、税务筹划", "13800000004", "chensy@example.com", 2200,
                "曾任大型企业财务总监，擅长非财务人员的财务管理培训。", "2025-01-08");
        exec("INSERT INTO teachers(name,gender,org,title,field,phone,email,fee_rate,intro,status,in_date) VALUES(?,?,?,?,?,?,?,?,?,'在库',?)",
                "刘晓芸", "女", "某咨询公司", "资深顾问", "市场营销、客户服务", "13800000005", "liuxy@example.com", 2000,
                "十余年营销咨询经验，案例丰富，互动性强。", "2025-02-15");

        // Synthetic demo locations only, never a migration/backfill for existing teachers.
        exec("UPDATE teachers SET base_province='北京',base_city='北京' WHERE id IN (1,2)");
        exec("UPDATE teachers SET base_province='浙江',base_city='杭州' WHERE id IN (3,5)");
        exec("UPDATE teachers SET base_province='江苏',base_city='南京' WHERE id=4");

        exec("INSERT INTO demands(title,unit,contact,phone,hours,content,teacher_req,expect_date,status,remark) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "中层干部领导力提升培训班", "某市国资委", "周主任", "0571-88000001", 24,
                "围绕中层干部领导力、团队建设、执行力提升开展专题培训，含案例研讨。",
                "要求具有企业管理背景，副高以上职称，有国企培训经验。", "2026-08-15", "已立项",
                "年度重点培训项目");
        exec("INSERT INTO demands(title,unit,contact,phone,hours,content,teacher_req,expect_date,status,remark) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "新员工入职培训（第一期）", "某商业银行人力资源部", "吴经理", "0571-88000002", 16,
                "企业文化、职业素养、合规意识、基础业务知识。",
                "亲和力强，有金融行业培训经验优先。", "2026-09-01", "进行中", "");
        exec("INSERT INTO demands(title,unit,contact,phone,hours,content,teacher_req,expect_date,status,remark) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "党史学习教育专题培训班", "某机关党委", "郑书记", "0571-88000003", 12,
                "党史专题辅导、党风廉政建设教育。",
                "党校系统教师，政治素质过硬。", "2026-08-05", "已投标", "");
        exec("INSERT INTO demands(title,unit,contact,phone,hours,content,teacher_req,expect_date,status,remark) VALUES(?,?,?,?,?,?,?,?,?,?)",
                "财务人员税务筹划专题培训", "某集团公司财务部", "林总监", "0571-88000004", 8,
                "最新税收政策解读、企业税务筹划实务。",
                "要求有实务经验的高级经济师或注册会计师。", "2026-10-20", "待处理", "");

        exec("INSERT INTO bids(demand_id,amount,proposal,bid_date,status,review) VALUES(?,?,?,?,?,?)",
                3, 26000, "拟安排党校高级讲师张子昂授课，含讲义编印与现场教学组织。", "2026-07-20", "已中标", "方案针对性强，价格合理，同意中标。");
        exec("INSERT INTO bids(demand_id,amount,proposal,bid_date,status,review) VALUES(?,?,?,?,?,?)",
                1, 68000, "组合王建国教授与李慧敏副教授联合授课，采用讲授+研讨+测评模式。", "2026-06-10", "已中标", "师资配置优秀，予以中标。");
        exec("INSERT INTO bids(demand_id,amount,proposal,bid_date,status,review) VALUES(?,?,?,?,?,?)",
                2, 32000, "安排刘晓芸顾问主讲职业素养与服务意识，陈思远主讲合规基础。", "2026-07-02", "已中标", "符合需求，同意中标。");

        exec("INSERT INTO projects(demand_id,bid_id,title,unit,hours,amount,start_date,end_date,owner,participant_count,delivery_mode,venue,contract_no,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                1, 2, "中层干部领导力提升培训班", "某市国资委", 24, 68000, "2026-08-15", "2026-08-18", "陈婧", 42, "线下集中", "市干部教育中心 302", "YX-2026-017", "进行中");
        exec("INSERT INTO projects(demand_id,bid_id,title,unit,hours,amount,start_date,end_date,owner,participant_count,delivery_mode,venue,contract_no,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                2, 3, "新员工入职培训（第一期）", "某商业银行人力资源部", 16, 32000, "2026-09-01", "2026-09-02", "赵明", 60, "线下集中", "某商业银行培训中心 A1", "YX-2026-021", "进行中");
        exec("INSERT INTO projects(demand_id,bid_id,title,unit,hours,amount,start_date,end_date,owner,participant_count,delivery_mode,venue,contract_no,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                3, 1, "党史学习教育专题培训班", "某机关党委", 12, 26000, "2026-08-05", "2026-08-06", "周航", 38, "线下集中", "机关党校报告厅", "YX-2026-026", "进行中");

        exec("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,venue,confirm_deadline,material_status,hours,status,sent_at,confirmed_at,msg_log) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                1, 1, "战略思维与领导力", "2026-08-15", "09:00", "12:00", "市干部教育中心 302", "2026-08-10", "准备中", 8, "已确认", "2026-07-25 10:00", "2026-07-26 09:30",
                "【2026-07-25 10:00】已向王建国发送授课邀请：中层干部领导力提升培训班《战略思维与领导力》8课时，2026-08-15授课。\n【2026-07-26 09:30】王建国已确认授课安排。");
        exec("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,venue,confirm_deadline,material_status,hours,status,sent_at,msg_log) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                1, 2, "团队建设与绩效管理", "2026-08-16", "14:00", "17:00", "市干部教育中心 302", "2026-08-10", "待准备", 8, "已发送", "2026-07-25 10:05",
                "【2026-07-25 10:05】已向李慧敏发送授课邀请：中层干部领导力提升培训班《团队建设与绩效管理》8课时，2026-08-16授课。等待确认。");
        exec("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,venue,confirm_deadline,material_status,hours,status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                3, 3, "党史专题辅导", "2026-08-05", "09:00", "16:30", "机关党校报告厅", "2026-08-04", "待准备", 12, "待发送");

        exec("INSERT INTO charges(project_id,amount,received,charge_date,status,invoice,remark) VALUES(?,?,?,?,?,?,?)",
                1, 68000, 34000, "2026-07-28", "部分收费", "INV-2026-001", "合同签订后收取50%预付款");
        exec("INSERT INTO charges(project_id,amount,received,charge_date,status,invoice,remark) VALUES(?,?,?,?,?,?,?)",
                2, 32000, 32000, "2026-07-10", "已结清", "INV-2026-002", "一次性付清");
        exec("INSERT INTO charges(project_id,amount,received,charge_date,status,invoice,remark) VALUES(?,?,?,?,?,?,?)",
                3, 26000, 0, "2026-08-01", "未收费", "", "待开班后收取");

        exec("INSERT INTO fees(project_id,teacher_id,hours,rate,amount,status,pay_date) VALUES(?,?,?,?,?,?,?)",
                1, 1, 8, 3000, 24000, "待发放", "");
        exec("INSERT INTO fees(project_id,teacher_id,hours,rate,amount,status,pay_date) VALUES(?,?,?,?,?,?,?)",
                1, 2, 8, 2500, 20000, "待发放", "");

        exec("INSERT INTO costs(project_id,type,amount,cost_date,note) VALUES(?,?,?,?,?)",
                1, "差旅费", 3600, "2026-07-26", "王建国教授往返高铁及住宿预订");
        exec("INSERT INTO costs(project_id,type,amount,cost_date,note) VALUES(?,?,?,?,?)",
                1, "物料费", 1200, "2026-07-27", "讲义印刷、学员手册、文具");
        exec("INSERT INTO costs(project_id,type,amount,cost_date,note) VALUES(?,?,?,?,?)",
                2, "物料费", 800, "2026-07-11", "新员工培训资料包");

        exec("INSERT INTO teacher_evals(teacher_id,project_id,score,comment,evaluator,eval_date) VALUES(?,?,?,?,?,?)",
                1, 1, 4.8, "课程逻辑清晰，案例贴合实际，学员反响热烈。", "周主任", "2026-07-28");
        exec("INSERT INTO teacher_evals(teacher_id,project_id,score,comment,evaluator,eval_date) VALUES(?,?,?,?,?,?)",
                2, 1, 4.5, "互动设计好，工具方法实用。", "周主任", "2026-07-28");
    }

    public static long count(String table) throws SQLException {
        try (Statement st = get().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public static void exec(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = get().prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        }
    }

    /** 在同一 H2 连接中原子执行一组业务读写；异常时恢复到操作前状态。 */
    public static synchronized <T> T transaction(TransactionWork<T> work) throws Exception {
        Connection c = get();
        boolean previousAutoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        Exception failure = null;
        try {
            T result = work.run();
            c.commit();
            return result;
        } catch (Exception e) {
            failure = e;
            try { c.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
            throw e;
        } finally {
            try { c.setAutoCommit(previousAutoCommit); }
            catch (SQLException restoreError) {
                if (failure != null) failure.addSuppressed(restoreError);
                else throw restoreError;
            }
        }
    }

    /** 插入并返回自增主键 */
    public static long insert(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = get().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, args);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1;
    }

    public static List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = get().prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        Object v = rs.getObject(i);
                        if (v instanceof Timestamp) v = v.toString().substring(0, 19);
                        if (v instanceof Clob) {
                            Clob c = (Clob) v;
                            v = c.getSubString(1, (int) c.length());
                        }
                        row.put(md.getColumnLabel(i).toLowerCase(), v);
                    }
                    list.add(row);
                }
            }
        }
        return list;
    }

    public static Map<String, Object> one(String sql, Object... args) throws SQLException {
        List<Map<String, Object>> l = query(sql, args);
        return l.isEmpty() ? null : l.get(0);
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if ((value instanceof Double && !Double.isFinite((Double) value)) ||
                    (value instanceof Float && !Float.isFinite((Float) value)))
                throw new SQLException("拒绝写入非有限数值");
            ps.setObject(i + 1, args[i]);
        }
    }
}
