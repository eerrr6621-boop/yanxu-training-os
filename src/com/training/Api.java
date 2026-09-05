package com.training;

import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** REST API 路由与全部业务模块逻辑 */
public class Api {

    /**
     * 业务处理共享同一把数据库锁，保证状态校验与更新原子化，并保护单一 H2 连接。
     * 请求体会在进入这把锁之前限量读取，慢客户端不会占住整个系统的业务锁。
     */
    static final Object MUTATION_LOCK = new Object();
    private static final String BODY_ATTRIBUTE = Api.class.getName() + ".body";
    private static final String RESPONSE_ATTRIBUTE = Api.class.getName() + ".response";
    private static final String SESSION_COOKIE = "yx_session";
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final class ApiResponse {
        final int httpCode;
        final byte[] body;
        ApiResponse(int httpCode, byte[] body) { this.httpCode = httpCode; this.body = body; }
    }

    // 各模块允许写入的字段
    private static final Map<String, String[]> FIELDS = new LinkedHashMap<>();
    private static final Set<String> PROJECT_CHILD_MODULES = new HashSet<>(Arrays.asList(
            "teacher_evals", "dispatches", "questionnaires", "charges", "fees", "costs"));
    private static final Set<String> NUMERIC_FIELDS = new HashSet<>(Arrays.asList(
            "hours", "amount", "received", "fee_rate", "score", "rate", "demand_id", "project_id",
            "teacher_id", "bid_id", "send_count", "participant_count"));
    static {
        FIELDS.put("demands", new String[]{"title","unit","contact","phone","hours","content","teacher_req","expect_date","status","remark"});
        FIELDS.put("bids", new String[]{"demand_id","amount","proposal","bid_date","status","review"});
        FIELDS.put("projects", new String[]{"demand_id","bid_id","title","unit","hours","amount","start_date","end_date","owner","participant_count","delivery_mode","venue","contract_no","status","remark"});
        FIELDS.put("teachers", new String[]{"name","gender","org","title","field","phone","email","fee_rate","intro","status","in_date","out_date"});
        FIELDS.put("teacher_evals", new String[]{"teacher_id","project_id","score","comment","evaluator","eval_date"});
        FIELDS.put("dispatches", new String[]{"project_id","teacher_id","subject","teach_date","start_time","end_time","venue","confirm_deadline","material_status","hours","status","sent_at","confirmed_at","msg_log","remark"});
        FIELDS.put("questionnaires", new String[]{"title","target","project_id","questions","status"});
        FIELDS.put("charges", new String[]{"project_id","amount","received","charge_date","status","invoice","remark"});
        FIELDS.put("fees", new String[]{"project_id","teacher_id","hours","rate","amount","status","pay_date","remark"});
        FIELDS.put("costs", new String[]{"project_id","type","amount","cost_date","note"});
        FIELDS.put("users", new String[]{"username","name","role","status"});
    }

    public static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            String method = ex.getRequestMethod();
            if ("POST".equalsIgnoreCase(method)) {
                requireJsonContentType(ex);
                ex.setAttribute(BODY_ATTRIBUTE, readBodyLimited(ex));
            }
            synchronized (MUTATION_LOCK) { route(ex, path); }
        } catch (ApiException e) {
            err(ex, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            err(ex, 400, e.getMessage() == null ? "请求参数格式不正确" : e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            err(ex, 500, "服务器暂时无法处理请求，请稍后重试");
        }
        flushResponse(ex);
    }

    private static void route(HttpExchange ex, String path) throws Exception {
        // ---------- 无需登录 ----------
        if (path.equals("/api/login")) { requireMethod(ex, "POST"); login(ex); return; }
        if (path.equals("/api/q/pub")) { requireMethod(ex, "GET", "HEAD"); qPub(ex); return; }
        if (path.equals("/api/q/answer")) { requireMethod(ex, "POST"); qAnswer(ex); return; }

        // ---------- 需登录 ----------
        Auth.Session s = Auth.get(token(ex));
        if (s == null) { clearSessionCookie(ex); err(ex, 401, "未登录或会话已过期，请重新登录"); return; }

        if (path.equals("/api/logout")) { requireMethod(ex, "POST"); Auth.logout(token(ex)); clearSessionCookie(ex); ok(ex, null); return; }
        if (path.equals("/api/me")) { requireMethod(ex, "GET", "HEAD"); ok(ex, me(s)); return; }
        if (path.equals("/api/password")) { requireMethod(ex, "POST"); changePwd(ex, s); return; }

        if (path.startsWith("/api/stats/")) {
            if (path.equals("/api/stats/overview")) { requireMethod(ex, "GET", "HEAD"); statsOverview(ex); return; }
            if (path.equals("/api/stats/report")) { requireMethod(ex, "GET", "HEAD"); statsReport(ex); return; }
            if (path.equals("/api/stats/q")) { requireMethod(ex, "GET", "HEAD"); qStats(ex); return; }
        }

        if (path.startsWith("/api/users")) {
            if (!Auth.isAdmin(s)) { err(ex, 403, "无权限：仅系统管理员可管理用户"); return; }
            if (path.equals("/api/users/resetpwd")) { requireMethod(ex, "POST"); resetPwd(ex); return; }
            // 其余 /api/users 请求继续走下方通用 CRUD
        }

        // 工作流动作
        if (path.equals("/api/bids/win")) { requireMethod(ex, "POST"); requireWrite(s); bidWin(ex); return; }
        if (path.equals("/api/projects/check")) { requireMethod(ex, "GET", "HEAD"); projectTransitionCheck(ex); return; }
        if (path.equals("/api/projects/start")) { requireMethod(ex, "POST"); requireWrite(s); projectStart(ex); return; }
        if (path.equals("/api/projects/complete")) { requireMethod(ex, "POST"); requireWrite(s); projectTransition(ex, "complete"); return; }
        if (path.equals("/api/projects/archive")) { requireMethod(ex, "POST"); requireWrite(s); projectTransition(ex, "archive"); return; }
        if (path.equals("/api/dispatches/send")) { requireMethod(ex, "POST"); requireWrite(s); dispatchSend(ex); return; }
        if (path.equals("/api/dispatches/confirm")) { requireMethod(ex, "POST"); requireWrite(s); dispatchConfirm(ex); return; }
        if (path.equals("/api/dispatches/complete")) { requireMethod(ex, "POST"); requireWrite(s); dispatchComplete(ex); return; }
        if (path.equals("/api/q/publish")) { requireMethod(ex, "POST"); requireWrite(s); qStatus(ex, "已发布"); return; }
        if (path.equals("/api/q/close")) { requireMethod(ex, "POST"); requireWrite(s); qStatus(ex, "已关闭"); return; }
        if (path.equals("/api/q/send")) { requireMethod(ex, "POST"); requireWrite(s); qSend(ex); return; }
        if (path.equals("/api/fees/calc")) { requireMethod(ex, "POST"); requireWrite(s); feeCalc(ex); return; }
        if (path.equals("/api/fees/pay")) { requireMethod(ex, "POST"); requireWrite(s); feePay(ex); return; }
        if (path.equals("/api/charges/receive")) { requireMethod(ex, "POST"); requireWrite(s); chargeReceive(ex); return; }
        if (path.equals("/api/teachers/checkout")) { requireMethod(ex, "POST"); requireWrite(s); teacherOut(ex, "出库"); return; }
        if (path.equals("/api/teachers/checkin")) { requireMethod(ex, "POST"); requireWrite(s); teacherOut(ex, "在库"); return; }

        // 通用模块 CRUD：/api/{module}  /api/{module}/delete
        if (path.startsWith("/api/")) {
            String rest = path.substring(5);
            String mod = rest;
            boolean del = false;
            if (rest.endsWith("/delete")) { mod = rest.substring(0, rest.length() - 7); del = true; }
            if (FIELDS.containsKey(mod)) {
                if ("users".equals(mod) && !Auth.isAdmin(s)) { err(ex, 403, "无权限"); return; }
                if (del) { requireMethod(ex, "POST"); requireWrite(s); delete(ex, mod, s); return; }
                if ("GET".equalsIgnoreCase(ex.getRequestMethod()) || "HEAD".equalsIgnoreCase(ex.getRequestMethod())) { list(ex, mod); return; }
                requireMethod(ex, "POST");
                requireWrite(s);
                save(ex, mod, s); return;
            }
        }
        err(ex, 404, "接口不存在: " + path);
    }

    // ================= 登录/用户 =================

    private static void login(HttpExchange ex) throws Exception {
        Map<String, Object> b = body(ex);
        String token = Auth.login(Json.str(b, "username"), Json.str(b, "password"));
        if (token == null) { clearSessionCookie(ex); err(ex, 401, "用户名或密码错误（或账号已停用）"); return; }
        setSessionCookie(ex, token);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("token", token);
        r.put("user", me(Auth.get(token)));
        ok(ex, r);
    }

    private static Map<String, Object> me(Auth.Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uid", s.uid);
        m.put("username", s.username);
        m.put("name", s.name);
        m.put("role", s.role);
        m.put("roleName", "admin".equals(s.role) ? "系统管理员" : "manager".equals(s.role) ? "业务管理员" : "只读用户");
        return m;
    }

    private static void changePwd(HttpExchange ex, Auth.Session s) throws Exception {
        Map<String, Object> b = body(ex);
        String oldP = Json.str(b, "old"), newP = Json.str(b, "new");
        if (newP.length() < 8) throw new ApiException(400, "新密码长度至少8位");
        Map<String, Object> u = Db.one("SELECT * FROM users WHERE id=?", s.uid);
        if (u == null || !Auth.verify(oldP, String.valueOf(u.get("password"))))
            throw new ApiException(400, "原密码错误");
        Db.exec("UPDATE users SET password=? WHERE id=?", Auth.hash(newP), s.uid);
        Auth.revokeUserSessions(s.uid);
        clearSessionCookie(ex);
        ok(ex, "密码修改成功");
    }

    private static void resetPwd(HttpExchange ex) throws Exception {
        Map<String, Object> b = body(ex);
        String newP = Json.str(b, "password");
        if (newP.length() < 8) throw new ApiException(400, "新密码长度至少8位");
        long id = Json.lng(b, "id");
        if (Db.one("SELECT id FROM users WHERE id=?", id) == null) throw new ApiException(404, "用户不存在");
        Db.exec("UPDATE users SET password=? WHERE id=?", Auth.hash(newP), id);
        Auth.revokeUserSessions(id);
        ok(ex, "密码已重置");
    }

    // ================= 通用 CRUD =================

    private static String listSql(String mod) {
        switch (mod) {
            case "users": return "SELECT id,username,name,role,status,created_at FROM users";
            case "bids": return "SELECT b.*, d.title AS demand_title, d.unit AS demand_unit FROM bids b LEFT JOIN demands d ON d.id=b.demand_id";
            case "dispatches": return "SELECT p2.*, p.title AS project_title, p.status AS project_status, t.name AS teacher_name, t.phone AS teacher_phone FROM dispatches p2 LEFT JOIN projects p ON p.id=p2.project_id LEFT JOIN teachers t ON t.id=p2.teacher_id";
            case "teacher_evals": return "SELECT e.*, t.name AS teacher_name, p.title AS project_title, p.status AS project_status FROM teacher_evals e LEFT JOIN teachers t ON t.id=e.teacher_id LEFT JOIN projects p ON p.id=e.project_id";
            case "fees": return "SELECT f.*, p.title AS project_title, p.status AS project_status, t.name AS teacher_name FROM fees f LEFT JOIN projects p ON p.id=f.project_id LEFT JOIN teachers t ON t.id=f.teacher_id";
            case "charges": return "SELECT c.*, p.title AS project_title, p.unit AS project_unit, p.status AS project_status FROM charges c LEFT JOIN projects p ON p.id=c.project_id";
            case "costs": return "SELECT c.*, p.title AS project_title, p.status AS project_status FROM costs c LEFT JOIN projects p ON p.id=c.project_id";
            case "questionnaires": return "SELECT q.*, p.title AS project_title, " +
                    "(SELECT COALESCE(SUM(s.send_count),0) FROM q_sends s WHERE s.questionnaire_id=q.id) AS send_total, " +
                    "(SELECT COUNT(*) FROM q_responses r WHERE r.questionnaire_id=q.id) AS recv_total, " +
                    "p.status AS project_status FROM questionnaires q LEFT JOIN projects p ON p.id=q.project_id";
            case "q_sends": return "SELECT s.*, q.title AS q_title, (SELECT COUNT(*) FROM q_responses r WHERE r.send_id=s.id) AS recv_count FROM q_sends s LEFT JOIN questionnaires q ON q.id=s.questionnaire_id";
            default: return "SELECT * FROM " + mod;
        }
    }

    private static long positiveQueryId(String raw, String label) throws ApiException {
        if (raw == null || raw.trim().isEmpty()) throw new ApiException(400, label + "不能为空");
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(400, label + "格式不正确");
        }
    }

    private static void list(HttpExchange ex, String mod) throws Exception {
        Map<String, String> q = query(ex);
        StringBuilder sql = new StringBuilder(listSql(mod));
        List<Object> args = new ArrayList<>();
        String alias = mod;
        if ("bids".equals(mod)) alias = "b";
        else if ("dispatches".equals(mod)) alias = "p2";
        else if ("teacher_evals".equals(mod)) alias = "e";
        else if ("fees".equals(mod)) alias = "f";
        else if ("charges".equals(mod) || "costs".equals(mod)) alias = "c";
        else if ("questionnaires".equals(mod)) alias = "q";

        List<String> conds = new ArrayList<>();
        String kw = q.get("kw");
        if (kw != null && !kw.isEmpty()) {
            String like = "%" + kw + "%";
            switch (mod) {
                case "demands": conds.add("(title LIKE ? OR unit LIKE ? OR contact LIKE ?)"); args.add(like); args.add(like); args.add(like); break;
                case "teachers": conds.add("(name LIKE ? OR org LIKE ? OR field LIKE ?)"); args.add(like); args.add(like); args.add(like); break;
                case "projects": conds.add("(title LIKE ? OR unit LIKE ?)"); args.add(like); args.add(like); break;
                case "questionnaires": conds.add("q.title LIKE ?"); args.add(like); break;
                default: break;
            }
        }
        if (q.get("status") != null && !q.get("status").isEmpty()) {
            conds.add(alias + ".status=?"); args.add(q.get("status"));
        }
        if (q.get("project_id") != null && !q.get("project_id").isEmpty()) {
            conds.add(alias + ".project_id=?"); args.add(positiveQueryId(q.get("project_id"), "项目编号"));
        }
        if (q.get("teacher_id") != null && !q.get("teacher_id").isEmpty()) {
            conds.add(alias + ".teacher_id=?"); args.add(positiveQueryId(q.get("teacher_id"), "师资编号"));
        }
        if (q.get("demand_id") != null && !q.get("demand_id").isEmpty()) {
            conds.add(alias + ".demand_id=?"); args.add(positiveQueryId(q.get("demand_id"), "需求编号"));
        }
        if (q.get("questionnaire_id") != null && !q.get("questionnaire_id").isEmpty()) {
            conds.add(alias + ".questionnaire_id=?"); args.add(positiveQueryId(q.get("questionnaire_id"), "问卷编号"));
        }
        if (!conds.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", conds));
        sql.append(" ORDER BY ").append(alias).append(".id DESC");
        ok(ex, Db.query(sql.toString(), args.toArray()));
    }

    private static Map<String, Object> requireProject(long projectId) throws Exception {
        if (projectId <= 0) throw new ApiException(400, "请选择有效的培训项目");
        Map<String, Object> project = Db.one("SELECT * FROM projects WHERE id=?", projectId);
        if (project == null) throw new ApiException(400, "关联的培训项目不存在");
        return project;
    }

    private static Map<String, Object> requireProjectNotArchived(long projectId) throws Exception {
        Map<String, Object> project = requireProject(projectId);
        if ("已归档".equals(str(project, "status")))
            throw new ApiException(400, "项目已归档，不能再执行该操作");
        return project;
    }

    private static void requireDeliveryProjectOpen(long projectId) throws Exception {
        Map<String, Object> project = requireProject(projectId);
        if (!("待启动".equals(str(project, "status")) || "进行中".equals(str(project, "status"))))
            throw new ApiException(400, "项目已完成或归档，不能再变更授课安排");
    }

    private static void requireTeacherInLibrary(Map<String, Object> dispatch) throws ApiException {
        if (!"在库".equals(str(dispatch, "teacher_status")))
            throw new ApiException(400, "该师资已出库，不能继续推进授课调度");
    }

    private static boolean fieldChanged(Map<String, Object> existing, Map<String, Object> body, String field) {
        if (NUMERIC_FIELDS.contains(field))
            return Math.abs(dbl(existing, field) - Json.num(body, field)) > 0.001;
        return !str(existing, field).equals(Json.str(body, field));
    }

    private static void requireUnchanged(Map<String, Object> existing, Map<String, Object> body,
                                         String message, String... fields) throws ApiException {
        for (String field : fields)
            if (fieldChanged(existing, body, field)) throw new ApiException(400, message);
    }

    /** 通用编辑只保留需求原状态；尚未立项的需求可由人工明确结束为“已流标”。 */
    private static String demandStatusForSave(Map<String, Object> existing, Map<String, Object> body) throws Exception {
        String requested = Json.str(body, "status").trim();
        if (existing == null) {
            if (requested.isEmpty() || "待处理".equals(requested)) return "待处理";
            throw new ApiException(400, "新需求只能从待处理状态开始");
        }
        String current = str(existing, "status");
        if (requested.isEmpty() || current.equals(requested)) return current;
        if ("已流标".equals(requested) && ("待处理".equals(current) || "已投标".equals(current))) {
            long projects = lng(Db.one("SELECT COUNT(*) c FROM projects WHERE demand_id=?", existing.get("id")), "c");
            if (projects > 0) throw new ApiException(400, "该需求已经形成项目，不能再标记为已流标");
            return requested;
        }
        throw new ApiException(400, "需求状态必须通过投标、立项、启动和完成流程推进");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> validateQuestions(String raw) throws ApiException {
        Object parsed;
        try { parsed = Json.parse(raw); }
        catch (Exception e) { throw new ApiException(400, "问卷题目格式不正确"); }
        if (!(parsed instanceof List) || ((List<?>) parsed).isEmpty())
            throw new ApiException(400, "问卷至少需要一道题目");
        List<?> source = (List<?>) parsed;
        if (source.size() > 100) throw new ApiException(400, "单份问卷最多100道题");
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Object item : source) {
            if (!(item instanceof Map)) throw new ApiException(400, "问卷题目格式不正确");
            Map<String, Object> question = (Map<String, Object>) item;
            String type = str(question, "type"), title = str(question, "title").trim();
            if (!("score".equals(type) || "single".equals(type) || "text".equals(type)) || title.isEmpty())
                throw new ApiException(400, "每道题都必须包含有效题型和题目标题");
            if (title.length() > 300) throw new ApiException(400, "单道题的标题最多300个字符");
            if ("single".equals(type)) {
                Object options = question.get("options");
                if (!(options instanceof List) || ((List<?>) options).size() < 2 || ((List<?>) options).size() > 50)
                    throw new ApiException(400, "单选题至少需要两个选项");
                Set<String> unique = new HashSet<>();
                for (Object option : (List<?>) options) {
                    String value = option == null ? "" : option.toString().trim();
                    if (value.isEmpty() || value.length() > 200 || !unique.add(value))
                        throw new ApiException(400, "单选题选项不能为空、过长或重复");
                }
            }
            questions.add(question);
        }
        return questions;
    }

    /**
     * 普通保存只能改业务资料，不能改写已经被发送、收款、发放或归档确认过的事实。
     * 这样项目状态与支撑该状态的交付/财务记录始终一致。
     */
    private static void validateSave(String mod, Map<String, Object> body, long id,
                                     Map<String, Object> existing) throws Exception {
        if (id > 0 && existing == null) throw new ApiException(404, "要修改的记录不存在或已被删除");

        if ("demands".equals(mod)) demandStatusForSave(existing, body);

        if ("projects".equals(mod) && existing != null) {
            requireUnchanged(existing, body, "项目来源需求和中标记录不能通过普通编辑重新绑定",
                    "demand_id", "bid_id");
            String status = str(existing, "status");
            if ("已归档".equals(status))
                throw new ApiException(400, "项目已归档，业务与财务资料均为只读；如需更正请先走线下复核流程");
            if ("已完成".equals(status))
                requireUnchanged(existing, body, "项目已完成，不能再修改计划课时、合同金额或交付日期",
                        "hours", "amount", "start_date", "end_date");
        }
        if ("projects".equals(mod) && existing == null) {
            long demandId = Json.lng(body, "demand_id"), bidId = Json.lng(body, "bid_id");
            if (demandId < 0 || bidId < 0)
                throw new ApiException(400, "项目来源需求与中标记录不能使用负数编号");
            if ((demandId > 0) != (bidId > 0))
                throw new ApiException(400, "项目来源需求与中标记录必须同时提供");
            if (bidId > 0) {
                Map<String, Object> demand = Db.one("SELECT id,status FROM demands WHERE id=?", demandId);
                if (demand == null) throw new ApiException(400, "关联的培训需求不存在");
                Map<String, Object> bid = Db.one("SELECT id,demand_id,status FROM bids WHERE id=?", bidId);
                if (bid == null) throw new ApiException(400, "关联的中标记录不存在");
                if (demandId <= 0 || lng(bid, "demand_id") != demandId)
                    throw new ApiException(400, "中标记录与培训需求不匹配");
                if (!"已中标".equals(str(bid, "status")))
                    throw new ApiException(400, "只有已中标记录才能作为项目来源");
                if (!"已立项".equals(str(demand, "status")))
                    throw new ApiException(400, "只有已立项需求才能作为新项目来源");
                if (Db.one("SELECT id FROM projects WHERE demand_id=? OR bid_id=?", demandId, bidId) != null)
                    throw new ApiException(400, "该需求或中标记录已经形成项目");
            }
        }

        if (PROJECT_CHILD_MODULES.contains(mod)) {
            if (existing != null) {
                Map<String, Object> originalProject = requireProject(lng(existing, "project_id"));
                if ("已归档".equals(str(originalProject, "status")))
                    throw new ApiException(400, "项目已归档，相关业务记录不能再修改");
                if ("dispatches".equals(mod) && "已完成".equals(str(originalProject, "status")))
                    throw new ApiException(400, "项目已完成交付，授课记录已锁定，不能再修改");
            }
            Map<String, Object> targetProject = requireProject(Json.lng(body, "project_id"));
            String projectStatus = str(targetProject, "status");
            if ("已归档".equals(projectStatus))
                throw new ApiException(400, "项目已归档，不能新增、移动或修改相关业务记录");
            if ("dispatches".equals(mod) && "已完成".equals(projectStatus))
                throw new ApiException(400, existing == null ? "项目已完成交付，不能再新增排课" : "项目已完成交付，授课记录已锁定，不能再修改");
        }

        if ("dispatches".equals(mod) && existing != null &&
                !("待发送".equals(str(existing, "status")) || "已拒绝".equals(str(existing, "status"))))
            requireUnchanged(existing, body, "授课安排已发送或确认，不能修改讲师、日期、课时和授课内容；如需变更请重新调度",
                    "project_id", "teacher_id", "subject", "teach_date", "start_time", "end_time",
                    "venue", "confirm_deadline", "hours");

        if ("charges".equals(mod) && existing != null && dbl(existing, "received") > 0.005)
            requireUnchanged(existing, body, "该应收已有收款记录，不能再修改所属项目或应收金额",
                    "project_id", "amount");

        if ("fees".equals(mod) && existing != null && "已发放".equals(str(existing, "status")))
            requireUnchanged(existing, body, "该笔课酬已发放，讲师、课时、标准和金额不能再修改",
                    "project_id", "teacher_id", "hours", "rate", "amount");

        if ("questionnaires".equals(mod) && existing != null && !"草稿".equals(str(existing, "status")))
            requireUnchanged(existing, body, "问卷已发布，评估对象和题目口径不能再修改",
                    "project_id", "target", "questions");
        if ("questionnaires".equals(mod)) validateQuestions(Json.str(body, "questions"));

        // 关键数值规则必须在服务端成立，不能只依赖浏览器表单。
        if (("demands".equals(mod) || "dispatches".equals(mod)) && Json.num(body, "hours") <= 0)
            throw new ApiException(400, "课时数必须大于0");
        if ("projects".equals(mod) && (Json.num(body, "hours") < 0 || Json.num(body, "amount") < 0))
            throw new ApiException(400, "计划课时和合同金额不能为负数");
        if ("projects".equals(mod)) {
            double participantCount = Json.num(body, "participant_count");
            if (participantCount < 0 || participantCount > 100000 || participantCount != Math.rint(participantCount))
                throw new ApiException(400, "参训人数必须是0到100000之间的整数");
        }
        if ("teachers".equals(mod) && Json.num(body, "fee_rate") < 0)
            throw new ApiException(400, "课酬标准不能为负数");
        if ("teacher_evals".equals(mod) && (Json.num(body, "score") < 1 || Json.num(body, "score") > 5))
            throw new ApiException(400, "师资评分必须在1到5分之间");
        if ("charges".equals(mod) && Json.num(body, "amount") <= 0)
            throw new ApiException(400, "应收金额必须大于0");
        if ("fees".equals(mod)) {
            double hours = Json.num(body, "hours"), rate = Json.num(body, "rate"), amount = Json.num(body, "amount");
            if (hours <= 0 || rate < 0 || amount < 0)
                throw new ApiException(400, "课酬课时必须大于0，标准和金额不能为负数");
            double expected = Math.round(hours * rate * 100) / 100.0;
            if (Math.abs(amount - expected) > 0.005)
                throw new ApiException(400, "课酬金额必须等于课时 × 课酬标准");
        }
        if ("costs".equals(mod) && Json.num(body, "amount") <= 0)
            throw new ApiException(400, "成本金额必须大于0");

        if ("dispatches".equals(mod) || "fees".equals(mod) || "teacher_evals".equals(mod)) {
            long teacherId = Json.lng(body, "teacher_id");
            Map<String, Object> teacher = teacherId > 0 ? Db.one("SELECT id,status FROM teachers WHERE id=?", teacherId) : null;
            if (teacher == null)
                throw new ApiException(400, "关联的授课师资不存在");
            if ("dispatches".equals(mod) && (existing == null || fieldChanged(existing, body, "teacher_id")) &&
                    !"在库".equals(str(teacher, "status")))
                throw new ApiException(400, "该师资已出库，不能参与新的授课调度");
            if ("teacher_evals".equals(mod)) {
                long projectId = Json.lng(body, "project_id");
                long completed = lng(Db.one("SELECT COUNT(*) c FROM dispatches WHERE project_id=? AND teacher_id=? AND status='已完成'",
                        projectId, teacherId), "c");
                if (completed <= 0)
                    throw new ApiException(400, "只能评价在该项目中已有完成授课记录的师资");
            }
        }
        if ("bids".equals(mod)) {
            long demandId = Json.lng(body, "demand_id");
            if (demandId <= 0 || Db.one("SELECT id FROM demands WHERE id=?", demandId) == null)
                throw new ApiException(400, "关联的培训需求不存在");
            if (Json.num(body, "amount") <= 0) throw new ApiException(400, "投标金额必须大于0");
            if (existing != null)
                requireUnchanged(existing, body, "投标所属需求不能通过普通编辑重新绑定", "demand_id");
            else {
                Map<String, Object> demand = Db.one("SELECT status FROM demands WHERE id=?", demandId);
                if (!("待处理".equals(str(demand, "status")) || "已投标".equals(str(demand, "status"))))
                    throw new ApiException(400, "当前需求状态不能新增投标");
                if (Db.one("SELECT id FROM projects WHERE demand_id=?", demandId) != null)
                    throw new ApiException(400, "该需求已经形成项目，不能再新增投标");
            }
        }
        if ("users".equals(mod)) {
            String username = Json.str(body, "username").trim();
            String role = Json.str(body, "role");
            double rawStatus = Json.num(body, "status");
            if (rawStatus != Math.rint(rawStatus)) throw new ApiException(400, "用户状态不正确");
            int status = (int) rawStatus;
            if (username.isEmpty()) throw new ApiException(400, "用户名不能为空");
            if (!("admin".equals(role) || "manager".equals(role) || "viewer".equals(role)))
                throw new ApiException(400, "用户角色不正确");
            if (!(status == 0 || status == 1)) throw new ApiException(400, "用户状态不正确");
            Map<String, Object> duplicate = Db.one("SELECT id FROM users WHERE username=? AND id<>?", username, id);
            if (duplicate != null) throw new ApiException(400, "该用户名已存在");
            if (existing != null && "admin".equals(str(existing, "role")) && lng(existing, "status") == 1 &&
                    !("admin".equals(role) && status == 1)) {
                long admins = lng(Db.one("SELECT COUNT(*) c FROM users WHERE role='admin' AND status=1"), "c");
                if (admins <= 1) throw new ApiException(400, "系统必须至少保留一个启用的管理员账号");
            }
        }
    }

    private static void save(HttpExchange ex, String mod, Auth.Session actor) throws Exception {
        Map<String, Object> b = body(ex);
        long id = b.containsKey("id") ? Json.lng(b, "id") : 0;
        if (b.containsKey("id") && id <= 0) throw new ApiException(400, "记录编号必须是正整数");
        String[] fields = FIELDS.get(mod);
        Map<String, Object> existing = id > 0 ? Db.one("SELECT * FROM " + mod + " WHERE id=?", id) : null;
        String requestedPassword = "users".equals(mod) ? Json.str(b, "password") : "";
        if ("users".equals(mod)) {
            b.put("username", Json.str(b, "username").trim());
            if ((existing == null || !requestedPassword.isEmpty()) && requestedPassword.length() < 8)
                throw new ApiException(400, "密码长度至少8位");
        }
        validateSave(mod, b, id, existing);
        String demandTargetStatus = "demands".equals(mod) ? demandStatusForSave(existing, b) : null;
        if ("users".equals(mod) && existing != null && actor.uid == id &&
                (!str(existing, "role").equals(Json.str(b, "role")) || (int) Json.num(b, "status") != 1))
            throw new ApiException(400, "不能停用当前登录账号或变更自己的角色，请由另一位管理员操作");
        // 数值字段转换
        if (id > 0) {
            StringBuilder sql = new StringBuilder("UPDATE " + mod + " SET ");
            List<Object> args = new ArrayList<>();
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) sql.append(',');
                sql.append(fields[i]).append("=?");
                Object v = b.get(fields[i]);
                if (NUMERIC_FIELDS.contains(fields[i])) v = Json.num(b, fields[i]);
                if ("status".equals(fields[i]) && "users".equals(mod)) v = (long) Json.num(b, fields[i]);
                // 流程状态及其审计字段只能由专用动作推进，普通编辑不得绕过。
                if ("demands".equals(mod) && "status".equals(fields[i])) v = demandTargetStatus;
                else if (existing != null && protectedWorkflowField(mod, fields[i])) v = existing.get(fields[i]);
                args.add(v == null ? "" : v);
            }
            sql.append(" WHERE id=?");
            args.add(id);
            boolean revokeSessions = "users".equals(mod) && (
                    !str(existing, "username").equals(Json.str(b, "username")) ||
                    !str(existing, "role").equals(Json.str(b, "role")) ||
                    lng(existing, "status") != (long) Json.num(b, "status") ||
                    !requestedPassword.isEmpty());
            if ("users".equals(mod)) {
                final String updateSql = sql.toString();
                final Object[] updateArgs = args.toArray();
                Db.transaction(() -> {
                    Db.exec(updateSql, updateArgs);
                    if (!requestedPassword.isEmpty())
                        Db.exec("UPDATE users SET password=? WHERE id=?", Auth.hash(requestedPassword), id);
                    return null;
                });
            } else if ("demands".equals(mod) && "已流标".equals(demandTargetStatus) &&
                    !"已流标".equals(str(existing, "status"))) {
                final String updateSql = sql.toString();
                final Object[] updateArgs = args.toArray();
                Db.transaction(() -> {
                    Db.exec(updateSql, updateArgs);
                    Db.exec("UPDATE bids SET status='未中标' WHERE demand_id=? AND status='待评审'", id);
                    return null;
                });
            } else {
                Db.exec(sql.toString(), args.toArray());
            }
            if (revokeSessions) {
                Auth.revokeUserSessions(id);
                if (actor.uid == id) clearSessionCookie(ex);
            }
            ok(ex, id);
        } else {
            StringBuilder cols = new StringBuilder(), vals = new StringBuilder();
            List<Object> args = new ArrayList<>();
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) { cols.append(','); vals.append(','); }
                cols.append(fields[i]); vals.append('?');
                Object v = b.get(fields[i]);
                if (NUMERIC_FIELDS.contains(fields[i])) v = Json.num(b, fields[i]);
                if ("demands".equals(mod) && "status".equals(fields[i])) v = demandTargetStatus;
                else if (protectedWorkflowField(mod, fields[i])) v = initialWorkflowValue(mod, fields[i]);
                args.add(v == null ? "" : v);
            }
            if ("users".equals(mod)) {
                cols.append(",password"); vals.append(",?");
                args.add(Auth.hash(requestedPassword));
            }
            final String insertSql = "INSERT INTO " + mod + "(" + cols + ") VALUES(" + vals + ")";
            final Object[] insertArgs = args.toArray();
            long nid;
            if ("bids".equals(mod)) {
                nid = Db.transaction(() -> {
                    long insertedId = Db.insert(insertSql, insertArgs);
                    Db.exec("UPDATE demands SET status='已投标' WHERE id=? AND status='待处理'", Json.lng(b, "demand_id"));
                    return insertedId;
                });
            } else {
                nid = Db.insert(insertSql, insertArgs);
            }
            ok(ex, nid);
        }
    }

    private static void delete(HttpExchange ex, String mod, Auth.Session actor) throws Exception {
        Map<String, Object> b = body(ex);
        long id = Json.lng(b, "id");
        Map<String, Object> row = Db.one("SELECT * FROM " + mod + " WHERE id=?", id);
        if (row == null) throw new ApiException(404, "要删除的记录不存在或已被删除");
        if ("users".equals(mod) && actor.uid == id)
            throw new ApiException(400, "不能删除当前登录账号，请由另一位管理员操作");

        if (PROJECT_CHILD_MODULES.contains(mod)) {
            Map<String, Object> project = requireProject(lng(row, "project_id"));
            if ("已归档".equals(str(project, "status")))
                throw new ApiException(400, "项目已归档，相关业务记录不能删除");
            if ("dispatches".equals(mod) && "已完成".equals(str(project, "status")))
                throw new ApiException(400, "项目已完成交付，授课记录已锁定，不能删除");
        }

        if ("projects".equals(mod)) {
            if ("已完成".equals(str(row, "status")) || "已归档".equals(str(row, "status")))
                throw new ApiException(400, "已完成或已归档项目不能删除");
            long refs = lng(Db.one("SELECT " +
                    "(SELECT COUNT(*) FROM dispatches WHERE project_id=?) + " +
                    "(SELECT COUNT(*) FROM charges WHERE project_id=?) + " +
                    "(SELECT COUNT(*) FROM fees WHERE project_id=?) + " +
                    "(SELECT COUNT(*) FROM costs WHERE project_id=?) + " +
                    "(SELECT COUNT(*) FROM questionnaires WHERE project_id=?) + " +
                    "(SELECT COUNT(*) FROM teacher_evals WHERE project_id=?) c",
                    id, id, id, id, id, id), "c");
            if (refs > 0 || lng(row, "demand_id") > 0 || lng(row, "bid_id") > 0)
                throw new ApiException(400, "项目已有来源或业务记录，不能直接删除；请处理完成后归档");
        } else if ("dispatches".equals(mod)) {
            if (!("待发送".equals(str(row, "status")) || "已拒绝".equals(str(row, "status"))))
                throw new ApiException(400, "授课邀请已发送、确认或完成，不能删除该调度记录");
        } else if ("charges".equals(mod)) {
            if (dbl(row, "received") > 0.005)
                throw new ApiException(400, "该应收已有收款记录，不能删除");
        } else if ("fees".equals(mod)) {
            if ("已发放".equals(str(row, "status")))
                throw new ApiException(400, "该笔课酬已发放，不能删除");
        } else if ("questionnaires".equals(mod)) {
            long sends = lng(Db.one("SELECT COUNT(*) c FROM q_sends WHERE questionnaire_id=?", id), "c");
            if (!"草稿".equals(str(row, "status")) || sends > 0)
                throw new ApiException(400, "问卷已发布或已发送，不能删除；可关闭问卷并保留统计记录");
        } else if ("teachers".equals(mod)) {
            long refs = lng(Db.one("SELECT " +
                    "(SELECT COUNT(*) FROM dispatches WHERE teacher_id=?) + " +
                    "(SELECT COUNT(*) FROM fees WHERE teacher_id=?) + " +
                    "(SELECT COUNT(*) FROM teacher_evals WHERE teacher_id=?) + " +
                    "(SELECT COUNT(*) FROM teacher_resumes WHERE teacher_id=?) c", id, id, id, id), "c");
            if (refs > 0) throw new ApiException(400, "该师资已有排课、课酬、评价或简历记录，请使用出库保留历史档案");
        } else if ("bids".equals(mod)) {
            if ("已中标".equals(str(row, "status")) ||
                    lng(Db.one("SELECT COUNT(*) c FROM projects WHERE bid_id=?", id), "c") > 0)
                throw new ApiException(400, "该投标已中标并形成项目，不能删除");
        } else if ("demands".equals(mod)) {
            long refs = lng(Db.one("SELECT (SELECT COUNT(*) FROM bids WHERE demand_id=?) + " +
                    "(SELECT COUNT(*) FROM projects WHERE demand_id=?) c", id, id), "c");
            if (refs > 0) throw new ApiException(400, "该需求已进入投标或立项流程，不能删除");
        } else if ("users".equals(mod) && "admin".equals(str(row, "role"))) {
            long admins = lng(Db.one("SELECT COUNT(*) c FROM users WHERE role='admin' AND status=1"), "c");
            if (admins <= 1) throw new ApiException(400, "系统必须至少保留一个启用的管理员账号");
        }
        if ("bids".equals(mod)) {
            long demandId = lng(row, "demand_id");
            Db.transaction(() -> {
                Db.exec("DELETE FROM bids WHERE id=?", id);
                if (Db.one("SELECT id FROM bids WHERE demand_id=?", demandId) == null &&
                        Db.one("SELECT id FROM projects WHERE demand_id=?", demandId) == null)
                    Db.exec("UPDATE demands SET status='待处理' WHERE id=? AND status='已投标'", demandId);
                return null;
            });
        } else {
            Db.exec("DELETE FROM " + mod + " WHERE id=?", id);
        }
        if ("users".equals(mod)) Auth.revokeUserSessions(id);
        ok(ex, "已删除");
    }

    // ================= 工作流动作 =================

    private static boolean protectedWorkflowField(String mod, String field) {
        if ("demands".equals(mod)) return "status".equals(field);
        if ("bids".equals(mod)) return "status".equals(field);
        if ("projects".equals(mod)) return "status".equals(field);
        if ("teachers".equals(mod)) return "status".equals(field) || "out_date".equals(field);
        if ("dispatches".equals(mod)) return Arrays.asList("status", "sent_at", "confirmed_at", "msg_log").contains(field);
        if ("questionnaires".equals(mod)) return "status".equals(field);
        if ("charges".equals(mod)) return "received".equals(field) || "status".equals(field);
        if ("fees".equals(mod)) return "status".equals(field) || "pay_date".equals(field);
        return false;
    }

    private static Object initialWorkflowValue(String mod, String field) {
        if ("status".equals(field)) {
            if ("demands".equals(mod)) return "待处理";
            if ("bids".equals(mod)) return "待评审";
            if ("projects".equals(mod)) return "待启动";
            if ("teachers".equals(mod)) return "在库";
            if ("dispatches".equals(mod)) return "待发送";
            if ("questionnaires".equals(mod)) return "草稿";
            if ("charges".equals(mod)) return "未收费";
            if ("fees".equals(mod)) return "待发放";
        }
        if ("received".equals(field)) return 0.0;
        return "";
    }

    private static Map<String, Object> projectIssue(String code, String title, String detail, String module) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("code", code);
        issue.put("title", title);
        issue.put("detail", detail);
        issue.put("module", module);
        return issue;
    }

    /**
     * 项目闭环校验：完成交付只验证课程是否真实完成；归档再验证回款、课酬及未关闭流程。
     * 无法从现有字段可靠推断的事项只提示，不阻断，避免把内部师资或免评项目误判为异常。
     */
    private static Map<String, Object> projectTransitionState(long id, String action) throws Exception {
        if (!"complete".equals(action) && !"archive".equals(action)) throw new ApiException(400, "不支持的项目操作");
        Map<String, Object> project = Db.one("SELECT * FROM projects WHERE id=?", id);
        if (project == null) throw new ApiException(404, "项目不存在或已被删除");

        Map<String, Object> delivery = Db.one(
                "SELECT COALESCE(SUM(CASE WHEN status<>'已拒绝' THEN 1 ELSE 0 END),0) total, " +
                "COALESCE(SUM(CASE WHEN status<>'已拒绝' THEN hours ELSE 0 END),0) scheduled_hours, " +
                "COALESCE(SUM(CASE WHEN status='已完成' THEN hours ELSE 0 END),0) completed_hours, " +
                "COALESCE(SUM(CASE WHEN status NOT IN ('已完成','已拒绝') THEN 1 ELSE 0 END),0) open_count, " +
                "COALESCE(SUM(CASE WHEN status='已拒绝' THEN 1 ELSE 0 END),0) rejected_count, " +
                "COALESCE(SUM(CASE WHEN status<>'已拒绝' AND COALESCE(material_status,'')<>'已就绪' THEN 1 ELSE 0 END),0) material_pending, " +
                "COALESCE(SUM(CASE WHEN status='已完成' AND teach_date>? THEN 1 ELSE 0 END),0) future_completed " +
                "FROM dispatches WHERE project_id=?", today(), id);
        Map<String, Object> charges = Db.one(
                "SELECT COUNT(*) total, COALESCE(SUM(amount),0) due, COALESCE(SUM(received),0) received, " +
                "COALESCE(SUM(CASE WHEN received>amount THEN 1 ELSE 0 END),0) overpaid_count FROM charges WHERE project_id=?", id);
        Map<String, Object> fees = Db.one(
                "SELECT COUNT(*) total, COALESCE(SUM(CASE WHEN status='待发放' THEN 1 ELSE 0 END),0) pending_count, " +
                "COALESCE(SUM(CASE WHEN status='待发放' THEN amount ELSE 0 END),0) pending_amount, " +
                "COALESCE(SUM(CASE WHEN ABS(amount-hours*rate)>0.005 THEN 1 ELSE 0 END),0) invalid_amount " +
                "FROM fees WHERE project_id=?", id);
        Map<String, Object> questionnaires = Db.one(
                "SELECT COUNT(*) total, COALESCE(SUM(CASE WHEN status='已发布' THEN 1 ELSE 0 END),0) published_count, " +
                "COALESCE(SUM(CASE WHEN status='草稿' THEN 1 ELSE 0 END),0) draft_count FROM questionnaires WHERE project_id=?", id);
        Map<String, Object> costs = Db.one("SELECT COUNT(*) total FROM costs WHERE project_id=?", id);

        double planHours = dbl(project, "hours");
        double scheduledHours = dbl(delivery, "scheduled_hours");
        double completedHours = dbl(delivery, "completed_hours");
        long dispatchTotal = lng(delivery, "total");
        long openCount = lng(delivery, "open_count");
        long rejectedCount = lng(delivery, "rejected_count");
        long materialPending = lng(delivery, "material_pending");
        long futureCompleted = lng(delivery, "future_completed");
        long chargeTotal = lng(charges, "total");
        double due = dbl(charges, "due");
        double received = dbl(charges, "received");
        double contractAmount = dbl(project, "amount");
        double outstanding = Math.max(0, (contractAmount > 0 ? contractAmount : due) - received);
        long overpaidCount = lng(charges, "overpaid_count");
        long pendingFeeCount = lng(fees, "pending_count");
        double pendingFeeAmount = dbl(fees, "pending_amount");
        long invalidFeeAmount = lng(fees, "invalid_amount");
        long qTotal = lng(questionnaires, "total");
        long publishedQ = lng(questionnaires, "published_count");
        long draftQ = lng(questionnaires, "draft_count");

        List<Map<String, Object>> blockers = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        String status = str(project, "status");

        if ("complete".equals(action)) {
            if (!"进行中".equals(status))
                blockers.add(projectIssue("status", "当前状态不能完成交付", "请先完成项目启动，再执行交付闭环。", "projects"));
            if (planHours <= 0)
                blockers.add(projectIssue("plan_hours", "项目计划课时未设置", "请先补充计划课时，系统才能判断交付是否完整。", "projects"));
            if (dispatchTotal == 0)
                blockers.add(projectIssue("no_dispatch", "尚未安排任何课程", "请先完成课程、讲师与授课日期安排。", "dispatches"));
            if (planHours > 0 && completedHours + 0.001 < planHours)
                blockers.add(projectIssue("hours_gap", "已完成课时不足", String.format("计划 %.1f 课时，当前只完成 %.1f 课时，还差 %.1f 课时。", planHours, completedHours, Math.max(0, planHours - completedHours)), "dispatches"));
            if (openCount > 0)
                blockers.add(projectIssue("open_dispatch", "仍有课程未完成", openCount + " 条排课尚未标记为已完成。", "dispatches"));
            if (futureCompleted > 0)
                blockers.add(projectIssue("future_completed", "存在未来日期的已完成课程", futureCompleted + " 条已完成排课的授课日期晚于今天，请先核对日期或状态。", "dispatches"));
            if (materialPending > 0)
                warnings.add(projectIssue("material", "课程材料状态未闭环", materialPending + " 条排课未标记为“已就绪”，请确认实际材料已归集。", "dispatches"));
            if (outstanding > 0.005)
                warnings.add(projectIssue("outstanding", "项目仍有待回款", String.format("当前尚有 %.2f 元未回款；交付可完成，但归档前必须结清。", outstanding), "charges"));
            if (pendingFeeCount > 0)
                warnings.add(projectIssue("pending_fee", "课酬尚未全部发放", String.format("%d 笔课酬待发放，合计 %.2f 元。", pendingFeeCount, pendingFeeAmount), "fees"));
            if (qTotal == 0)
                warnings.add(projectIssue("no_questionnaire", "尚未建立效果评估", "如本项目需要培训反馈，请先创建并发送问卷。", "questionnaires"));
            if (rejectedCount > 0)
                warnings.add(projectIssue("rejected_dispatch", "保留了已拒绝排课", rejectedCount + " 条被拒绝的排课将作为历史记录保留。", "dispatches"));
            if (planHours > 0 && completedHours > planHours + 0.001)
                warnings.add(projectIssue("excess_hours", "实际课时超过计划", String.format("计划 %.1f 课时，实际完成 %.1f 课时，请确认是否需要调整项目计划。", planHours, completedHours), "projects"));
            String endDate = str(project, "end_date");
            if (endDate.isEmpty() || endDate.compareTo(today()) > 0)
                warnings.add(projectIssue("end_date", "项目结束日期仍需核对", endDate.isEmpty() ? "尚未填写项目结束日期。" : "项目结束日期晚于今天。", "projects"));
        } else {
            if (!"已完成".equals(status))
                blockers.add(projectIssue("status", "项目尚未完成交付", "请先完成全部课程，再执行归档。", "projects"));
            if (planHours <= 0)
                blockers.add(projectIssue("plan_hours", "项目计划课时未设置", "归档前必须保留可核对的计划课时。", "projects"));
            if (dispatchTotal == 0)
                blockers.add(projectIssue("no_dispatch", "没有有效的已完成课程", "归档前必须保留实际交付记录。", "dispatches"));
            if (openCount > 0)
                blockers.add(projectIssue("open_dispatch", "仍有课程未完成", openCount + " 条排课尚未标记为已完成。", "dispatches"));
            if (planHours > 0 && completedHours + 0.001 < planHours)
                blockers.add(projectIssue("hours_gap", "交付课时仍有缺口", String.format("计划 %.1f 课时，已完成 %.1f 课时。", planHours, completedHours), "dispatches"));
            if (futureCompleted > 0)
                blockers.add(projectIssue("future_completed", "存在未来日期的已完成课程", futureCompleted + " 条已完成排课的授课日期晚于今天，请先核对。", "dispatches"));
            if (contractAmount > 0 && chargeTotal == 0)
                blockers.add(projectIssue("no_charge", "尚未建立回款记录", "项目存在合同金额，但没有对应的应收记录。", "charges"));
            if (contractAmount > 0 && chargeTotal > 0 && Math.abs(due - contractAmount) > 0.005)
                blockers.add(projectIssue("charge_mismatch", "合同金额与应收记录不一致", String.format("合同金额 %.2f 元，应收记录合计 %.2f 元，请先核对。", contractAmount, due), "charges"));
            if (outstanding > 0.005)
                blockers.add(projectIssue("outstanding", "回款尚未结清", String.format("仍有 %.2f 元待回款。", outstanding), "charges"));
            if (overpaidCount > 0)
                blockers.add(projectIssue("overpaid", "存在实收大于应收的记录", overpaidCount + " 笔回款记录金额异常，请先核对。", "charges"));
            if (pendingFeeCount > 0)
                blockers.add(projectIssue("pending_fee", "课酬尚未全部发放", String.format("%d 笔课酬待发放，合计 %.2f 元。", pendingFeeCount, pendingFeeAmount), "fees"));
            if (invalidFeeAmount > 0)
                blockers.add(projectIssue("fee_amount", "课酬金额计算不一致", invalidFeeAmount + " 笔课酬不等于课时 × 标准，请重新计算并核对。", "fees"));
            List<Map<String, Object>> deliveredByTeacher = Db.query(
                    "SELECT teacher_id, COALESCE(SUM(hours),0) hours FROM dispatches WHERE project_id=? AND status='已完成' GROUP BY teacher_id", id);
            List<Map<String, Object>> feeByTeacher = Db.query(
                    "SELECT teacher_id, COALESCE(SUM(hours),0) hours FROM fees WHERE project_id=? GROUP BY teacher_id", id);
            Map<Long, Double> deliveredHours = new LinkedHashMap<>(), feeHours = new LinkedHashMap<>();
            for (Map<String, Object> row : deliveredByTeacher) deliveredHours.put(Long.parseLong(String.valueOf(row.get("teacher_id"))), dbl(row, "hours"));
            for (Map<String, Object> row : feeByTeacher) feeHours.put(Long.parseLong(String.valueOf(row.get("teacher_id"))), dbl(row, "hours"));
            Set<Long> teacherIds = new LinkedHashSet<>();
            teacherIds.addAll(deliveredHours.keySet()); teacherIds.addAll(feeHours.keySet());
            int feeMismatch = 0;
            for (Long teacherId : teacherIds)
                if (Math.abs(deliveredHours.getOrDefault(teacherId, 0.0) - feeHours.getOrDefault(teacherId, 0.0)) > 0.001) feeMismatch++;
            if (feeMismatch > 0)
                blockers.add(projectIssue("fee_hours", "课酬课时与实际授课不一致", feeMismatch + " 位讲师的已完成课时与课酬记录不一致，请重新计算并核对。", "fees"));
            if (publishedQ > 0 || qTotal == 0 || draftQ > 0)
                warnings.add(projectIssue("evaluation", "效果评估尚未完整闭环", qTotal == 0 ? "本项目没有问卷记录，请确认是否属于免评项目。" : (publishedQ > 0 ? publishedQ + " 份问卷仍在回收；" : "") + (draftQ > 0 ? draftQ + " 份问卷仍为草稿。" : ""), "questionnaires"));
            if (lng(costs, "total") == 0)
                warnings.add(projectIssue("no_cost", "没有其他成本记录", "请确认差旅、场地、物料等成本是否确实为零。", "costs"));
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("plan_hours", planHours);
        metrics.put("scheduled_hours", scheduledHours);
        metrics.put("completed_hours", completedHours);
        metrics.put("outstanding", outstanding);
        metrics.put("pending_fee_amount", pendingFeeAmount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_id", id);
        result.put("title", project.get("title"));
        result.put("action", action);
        result.put("ready", blockers.isEmpty());
        result.put("metrics", metrics);
        result.put("blockers", blockers);
        result.put("warnings", warnings);
        return result;
    }

    private static void projectTransitionCheck(HttpExchange ex) throws Exception {
        Map<String, String> q = query(ex);
        long id;
        try { id = Long.parseLong(q.getOrDefault("id", "0")); }
        catch (NumberFormatException e) { throw new ApiException(400, "项目编号格式不正确"); }
        ok(ex, projectTransitionState(id, q.getOrDefault("action", "complete")));
    }

    /** 兼容历史库中“项目已由中标记录形成，但需求仍停留在已投标”的窄范围脏状态。 */
    private static boolean hasMatchingWinningBid(Map<String, Object> project, long demandId) throws Exception {
        long bidId = lng(project, "bid_id");
        if (demandId <= 0 || bidId <= 0) return false;
        Map<String, Object> bid = Db.one("SELECT demand_id,status FROM bids WHERE id=?", bidId);
        return bid != null && lng(bid, "demand_id") == demandId && "已中标".equals(str(bid, "status"));
    }

    /** 待启动项目完成基本资料检查后进入正式交付。 */
    private static void projectStart(HttpExchange ex) throws Exception {
        long id = Json.lng(body(ex), "id");
        Map<String, Object> project = requireProject(id);
        if ("进行中".equals(str(project, "status"))) { ok(ex, "项目已经启动"); return; }
        if (!"待启动".equals(str(project, "status")))
            throw new ApiException(400, "只有待启动项目可以执行启动操作");
        List<String> missing = new ArrayList<>();
        if (str(project, "owner").trim().isEmpty()) missing.add("项目负责人");
        if (str(project, "start_date").trim().isEmpty()) missing.add("开始日期");
        if (str(project, "end_date").trim().isEmpty()) missing.add("结束日期");
        if (dbl(project, "hours") <= 0) missing.add("计划课时");
        if (str(project, "delivery_mode").trim().isEmpty()) missing.add("授课方式");
        if (dbl(project, "amount") > 0 && str(project, "contract_no").trim().isEmpty()) missing.add("合同编号");
        if (!str(project, "start_date").isEmpty() && !str(project, "end_date").isEmpty() &&
                str(project, "start_date").compareTo(str(project, "end_date")) > 0)
            throw new ApiException(400, "项目结束日期不能早于开始日期");
        if (!missing.isEmpty()) throw new ApiException(400, "启动前请补齐：" + String.join("、", missing));
        Db.transaction(() -> {
            long demandId = lng(project, "demand_id");
            if (demandId > 0) {
                Map<String, Object> demand = Db.one("SELECT id,status FROM demands WHERE id=?", demandId);
                if (demand == null) throw new ApiException(400, "关联的培训需求不存在，请先核对项目资料");
                String demandStatus = str(demand, "status");
                boolean legacyWinningLink = "已投标".equals(demandStatus) && hasMatchingWinningBid(project, demandId);
                if (!("已立项".equals(demandStatus) || "进行中".equals(demandStatus) || legacyWinningLink))
                    throw new ApiException(400, "关联需求当前为“" + demandStatus + "”，不能启动项目，请先核对需求状态");
                if ("已立项".equals(demandStatus) || legacyWinningLink)
                    Db.exec("UPDATE demands SET status='进行中' WHERE id=? AND status IN ('已立项','已投标')", demandId);
            }
            Db.exec("UPDATE projects SET status='进行中' WHERE id=? AND status='待启动'", id);
            return null;
        });
        ok(ex, "项目已启动，现已进入交付阶段");
    }

    private static void projectTransition(HttpExchange ex, String action) throws Exception {
        long id = Json.lng(body(ex), "id");
        Map<String, Object> check = Db.transaction(() -> {
            Map<String, Object> state = projectTransitionState(id, action);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blockers = (List<Map<String, Object>>) state.get("blockers");
            if (!blockers.isEmpty()) {
                List<String> titles = new ArrayList<>();
                for (Map<String, Object> blocker : blockers) titles.add(String.valueOf(blocker.get("title")));
                throw new ApiException(400, ("complete".equals(action) ? "暂时不能完成交付：" : "暂时不能归档：") + String.join("；", titles));
            }
            String status = "complete".equals(action) ? "已完成" : "已归档";
            Db.exec("UPDATE projects SET status=? WHERE id=?", status, id);
            if ("complete".equals(action)) {
                Map<String, Object> project = requireProject(id);
                long demandId = lng(project, "demand_id");
                if (demandId > 0) {
                    Map<String, Object> demand = Db.one("SELECT id,status FROM demands WHERE id=?", demandId);
                    if (demand == null) throw new ApiException(400, "关联的培训需求不存在，请先核对项目资料");
                    String demandStatus = str(demand, "status");
                    boolean legacyWinningLink = "已投标".equals(demandStatus) && hasMatchingWinningBid(project, demandId);
                    if (!("已立项".equals(demandStatus) || "进行中".equals(demandStatus) ||
                            "已完成".equals(demandStatus) || legacyWinningLink))
                        throw new ApiException(400, "关联需求当前为“" + demandStatus + "”，不能完成项目，请先核对需求状态");
                    if (!"已完成".equals(demandStatus))
                        Db.exec("UPDATE demands SET status='已完成' WHERE id=? AND status IN ('已投标','已立项','进行中')", demandId);
                }
            }
            if ("archive".equals(action))
                Db.exec("UPDATE questionnaires SET status='已关闭' WHERE project_id=? AND status<>'已关闭'", id);
            state.put("status", status);
            state.put("msg", "complete".equals(action) ? "项目已完成交付，未结清的回款、课酬和评估事项将继续保留" : "项目已归档，并已从日常运营视图移出");
            return state;
        });
        ok(ex, check);
    }

    /** 投标中标：更新投标/需求状态，自动生成培训项目 */
    private static void bidWin(HttpExchange ex) throws Exception {
        long id = Json.lng(body(ex), "id");
        Map<String, Object> bid = Db.one("SELECT * FROM bids WHERE id=?", id);
        if (bid == null) throw new ApiException(404, "投标记录不存在");
        Map<String, Object> existingProject = Db.one("SELECT id FROM projects WHERE bid_id=?", id);
        if (existingProject != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("project_id", existingProject.get("id"));
            r.put("msg", "该投标已中标并完成立项，无需重复操作");
            ok(ex, r);
            return;
        }
        if (!"待评审".equals(str(bid, "status")))
            throw new ApiException(400, "只有待评审投标可以执行中标立项");
        long demandId = Long.parseLong(bid.get("demand_id").toString());
        Map<String, Object> d = Db.one("SELECT * FROM demands WHERE id=?", demandId);
        if (d == null) throw new ApiException(400, "关联的培训需求不存在，无法立项");
        if (!"已投标".equals(str(d, "status")))
            throw new ApiException(400, "只有已投标需求可以执行中标立项");
        if (Db.one("SELECT id FROM projects WHERE demand_id=?", demandId) != null)
            throw new ApiException(400, "该培训需求已经形成项目，不能重复立项");
        long pid = Db.transaction(() -> {
            Db.exec("UPDATE bids SET status='未中标' WHERE demand_id=? AND id<>?", demandId, id);
            Db.exec("UPDATE bids SET status='已中标' WHERE id=?", id);
            Db.exec("UPDATE demands SET status='已立项' WHERE id=?", demandId);
            return Db.insert(
                    "INSERT INTO projects(demand_id,bid_id,title,unit,hours,amount,start_date,end_date,status) VALUES(?,?,?,?,?,?,?,?,?)",
                    demandId, id, str(d, "title"), str(d, "unit"), dbl(d, "hours"), dbl(bid, "amount"),
                    str(d, "expect_date"), "", "待启动");
        });
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("project_id", pid);
        r.put("msg", "已中标并自动立项，项目编号：" + pid);
        ok(ex, r);
    }

    /** 师资调度：记录已通过外部渠道完成的通知，不冒充真实消息发送。 */
    private static void dispatchSend(HttpExchange ex) throws Exception {
        long id = Json.lng(body(ex), "id");
        Map<String, Object> dp = Db.one("SELECT p2.*, t.name AS tname, t.status AS teacher_status, p.title AS ptitle FROM dispatches p2 " +
                "LEFT JOIN teachers t ON t.id=p2.teacher_id LEFT JOIN projects p ON p.id=p2.project_id WHERE p2.id=?", id);
        if (dp == null) throw new ApiException(404, "调度记录不存在");
        requireDeliveryProjectOpen(lng(dp, "project_id"));
        requireTeacherInLibrary(dp);
        if (!("待发送".equals(str(dp, "status")) || "已拒绝".equals(str(dp, "status"))))
            throw new ApiException(400, "当前调度状态不能重复记录师资通知");
        String now = now();
        String timeRange = str(dp, "start_time").isEmpty() ? "" : " " + str(dp, "start_time") +
                (str(dp, "end_time").isEmpty() ? "" : "—" + str(dp, "end_time"));
        String venue = str(dp, "venue").isEmpty() ? "" : "，地点：" + str(dp, "venue");
        String log = str(dp, "msg_log") + (str(dp, "msg_log").isEmpty() ? "" : "\n") +
                "【" + now + "】已记录通知" + str(dp, "tname") + "：" + str(dp, "ptitle") +
                "《" + str(dp, "subject") + "》" + dbl(dp, "hours") + "课时，" + str(dp, "teach_date") + timeRange + venue + "。等待确认。";
        Db.exec("UPDATE dispatches SET status='已发送', sent_at=?, msg_log=? WHERE id=?", now, log, id);
        ok(ex, "已记录师资通知，等待确认");
    }

    /** 师资确认/拒绝 */
    private static void dispatchConfirm(HttpExchange ex) throws Exception {
        Map<String, Object> b = body(ex);
        long id = Json.lng(b, "id");
        boolean accept = Json.num(b, "accept") == 1 || "true".equals(Json.str(b, "accept"));
        Map<String, Object> dp = Db.one("SELECT p2.*, t.name AS tname, t.status AS teacher_status FROM dispatches p2 LEFT JOIN teachers t ON t.id=p2.teacher_id WHERE p2.id=?", id);
        if (dp == null) throw new ApiException(404, "调度记录不存在");
        requireDeliveryProjectOpen(lng(dp, "project_id"));
        requireTeacherInLibrary(dp);
        if (!"已发送".equals(str(dp, "status"))) throw new ApiException(400, "请先记录已通知师资，再登记确认结果");
        if (!accept && Json.str(b, "reason").trim().isEmpty()) throw new ApiException(400, "请填写师资拒绝原因");
        String now = now();
        String log = str(dp, "msg_log") + (str(dp, "msg_log").isEmpty() ? "" : "\n") +
                "【" + now + "】" + str(dp, "tname") + (accept ? "已确认授课安排。" : "拒绝了授课安排。原因：" + Json.str(b, "reason"));
        Db.exec("UPDATE dispatches SET status=?, confirmed_at=?, msg_log=? WHERE id=?",
                accept ? "已确认" : "已拒绝", now, log, id);
        ok(ex, accept ? "师资已确认" : "师资已拒绝，请重新调度");
    }

    /** 课程完成：仅允许已确认且授课日期不晚于今天的安排完成。 */
    private static void dispatchComplete(HttpExchange ex) throws Exception {
        long id = Json.lng(body(ex), "id");
        Map<String, Object> dp = Db.one("SELECT p2.*, t.name AS tname, t.status AS teacher_status FROM dispatches p2 LEFT JOIN teachers t ON t.id=p2.teacher_id WHERE p2.id=?", id);
        if (dp == null) throw new ApiException(404, "调度记录不存在");
        if ("已完成".equals(str(dp, "status"))) { ok(ex, "该课程已完成，无需重复操作"); return; }
        requireDeliveryProjectOpen(lng(dp, "project_id"));
        requireTeacherInLibrary(dp);
        if (!"已确认".equals(str(dp, "status"))) throw new ApiException(400, "只有师资已确认的课程才能标记完成");
        if (str(dp, "teach_date").isEmpty()) throw new ApiException(400, "请先补充授课日期");
        if (str(dp, "teach_date").compareTo(today()) > 0) throw new ApiException(400, "授课日期尚未到达，不能提前标记完成");
        String now = now();
        String log = str(dp, "msg_log") + (str(dp, "msg_log").isEmpty() ? "" : "\n") +
                "【" + now + "】课程已完成：" + str(dp, "subject") + "，" + dbl(dp, "hours") + "课时。";
        Db.exec("UPDATE dispatches SET status='已完成', msg_log=? WHERE id=?", log, id);
        ok(ex, "课程已完成，现已纳入项目交付课时");
    }

    private static void qStatus(HttpExchange ex, String st) throws Exception {
        long id = Json.lng(body(ex), "id");
        Map<String, Object> questionnaire = Db.one("SELECT * FROM questionnaires WHERE id=?", id);
        if (questionnaire == null) throw new ApiException(404, "问卷不存在");
        requireProjectNotArchived(lng(questionnaire, "project_id"));
        String current = str(questionnaire, "status");
        if (st.equals(current)) { ok(ex, "问卷已处于该状态"); return; }
        if ("已发布".equals(st) && !"草稿".equals(current))
            throw new ApiException(400, "只有草稿问卷可以发布");
        if ("已关闭".equals(st) && !"已发布".equals(current))
            throw new ApiException(400, "只有已发布问卷可以关闭");
        if ("已发布".equals(st)) validateQuestions(str(questionnaire, "questions"));
        Db.exec("UPDATE questionnaires SET status=? WHERE id=?", st, id);
        ok(ex, "已" + ("已发布".equals(st) ? "发布" : "关闭"));
    }

    /** 发送问卷（模拟微信推送），生成作答链接 */
    private static void qSend(HttpExchange ex) throws Exception {
        Map<String, Object> b = body(ex);
        long qid = Json.lng(b, "id");
        Map<String, Object> q = Db.one("SELECT * FROM questionnaires WHERE id=?", qid);
        if (q == null) throw new ApiException(404, "问卷不存在");
        requireProjectNotArchived(lng(q, "project_id"));
        if (!"已发布".equals(str(q, "status"))) throw new ApiException(400, "请先发布问卷再发送");
        validateQuestions(str(q, "questions"));
        String target = Json.str(b, "target_desc").trim();
        double rawSendCount = Json.num(b, "send_count");
        int sendCount = (int) rawSendCount;
        if (target.isEmpty() || target.length() > 200) throw new ApiException(400, "请填写有效的发送对象");
        if (!Double.isFinite(rawSendCount) || rawSendCount != Math.rint(rawSendCount) || sendCount < 1 || sendCount > 100000)
            throw new ApiException(400, "发送人数必须是1到100000之间的整数");
        String token = Auth.randomToken(24);
        long sid = Db.insert("INSERT INTO q_sends(questionnaire_id,channel,target_desc,send_count,token,sent_at) VALUES(?,?,?,?,?,?)",
                qid, "微信", target, sendCount, token, now());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("send_id", sid);
        r.put("link", "/answer.html?token=" + token);
        r.put("msg", "已模拟通过微信向【" + target + "】推送问卷，作答链接已生成");
        ok(ex, r);
    }

    /** 问卷作答页数据（公开） */
    private static void qPub(HttpExchange ex) throws Exception {
        Map<String, String> q = query(ex);
        String token = q.get("token");
        Map<String, Object> s = Db.one("SELECT s.*, q.title, q.target, q.questions, q.status, p.title AS project_title " +
                "FROM q_sends s JOIN questionnaires q ON q.id=s.questionnaire_id LEFT JOIN projects p ON p.id=q.project_id WHERE s.token=?", token);
        if (s == null) { err(ex, 404, "链接无效或已过期"); return; }
        if ("已关闭".equals(str(s, "status"))) { err(ex, 400, "问卷已关闭，感谢关注"); return; }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("title", s.get("title"));
        r.put("target", s.get("target"));
        r.put("project_title", s.get("project_title"));
        r.put("questions", Json.parse(str(s, "questions")));
        ok(ex, r);
    }

    /** 提交问卷答案（公开） */
    @SuppressWarnings("unchecked")
    private static void qAnswer(HttpExchange ex) throws Exception {
        Map<String, Object> b = body(ex);
        String token = Json.str(b, "token");
        Map<String, Object> s = Db.one("SELECT s.id AS sid, s.questionnaire_id AS qid, s.send_count, q.questions, q.status FROM q_sends s " +
                "JOIN questionnaires q ON q.id=s.questionnaire_id WHERE s.token=?", token);
        if (s == null) throw new ApiException(404, "链接无效");
        if ("已关闭".equals(str(s, "status"))) throw new ApiException(400, "问卷已关闭");

        List<Map<String, Object>> questions = validateQuestions(str(s, "questions"));
        Object rawAnswers = b.get("answers");
        if (!(rawAnswers instanceof List) || ((List<?>) rawAnswers).size() != questions.size())
            throw new ApiException(400, "答案数量与问卷题目不一致");

        List<Object> answers = (List<Object>) rawAnswers;
        double sum = 0;
        int cnt = 0;
        for (int i = 0; i < questions.size(); i++) {
            Object rawAnswer = answers.get(i);
            if (!(rawAnswer instanceof Map)) throw new ApiException(400, "第" + (i + 1) + "题答案格式不正确");
            Object value = ((Map<String, Object>) rawAnswer).get("value");
            String type = str(questions.get(i), "type");
            if ("score".equals(type)) {
                if (!(value instanceof Number)) throw new ApiException(400, "第" + (i + 1) + "题请选择1到5分");
                double score = ((Number) value).doubleValue();
                if (!Double.isFinite(score) || score < 1 || score > 5 || score != Math.rint(score))
                    throw new ApiException(400, "第" + (i + 1) + "题评分必须是1到5的整数");
                sum += score;
                cnt++;
            } else if ("single".equals(type)) {
                String selected = value == null ? "" : value.toString();
                boolean allowed = false;
                for (Object option : (List<?>) questions.get(i).get("options"))
                    if (option != null && option.toString().equals(selected)) { allowed = true; break; }
                if (!allowed) throw new ApiException(400, "第" + (i + 1) + "题选项无效");
            } else {
                String text = value == null ? "" : value.toString();
                if (text.length() > 5000) throw new ApiException(400, "第" + (i + 1) + "题文字回答过长");
            }
        }
        double avg = cnt > 0 ? Math.round(sum / cnt * 100) / 100.0 : 0;
        String respondent = Json.str(b, "respondent").trim();
        if (respondent.isEmpty()) respondent = "匿名";
        if (respondent.length() > 64) throw new ApiException(400, "姓名或称呼最多64个字符");
        long submitted = ((Number) Db.one("SELECT COUNT(*) AS c FROM q_responses WHERE send_id=?", s.get("sid")).get("c")).longValue();
        if (submitted >= lng(s, "send_count")) throw new ApiException(400, "该批次问卷已达到计划回收数");
        Db.insert("INSERT INTO q_responses(send_id,questionnaire_id,respondent,answers,avg_score,submitted_at) VALUES(?,?,?,?,?,?)",
                s.get("sid"), s.get("qid"), respondent, Json.write(answers), avg, now());
        ok(ex, "提交成功，感谢您的反馈！");
    }

    /** 问卷统计分析 */
    @SuppressWarnings("unchecked")
    private static void qStats(HttpExchange ex) throws Exception {
        Map<String, String> qp = query(ex);
        long qid = positiveQueryId(qp.get("id"), "问卷编号");
        Map<String, Object> q = Db.one("SELECT * FROM questionnaires WHERE id=?", qid);
        if (q == null) throw new ApiException(404, "问卷不存在");
        List<Map<String, Object>> resps = Db.query("SELECT * FROM q_responses WHERE questionnaire_id=? ORDER BY id", qid);
        Object questions = Json.parse(str(q, "questions"));
        List<Map<String, Object>> qs = questions instanceof List ? (List<Map<String, Object>>) questions : new ArrayList<>();

        List<Map<String, Object>> stats = new ArrayList<>();
        for (int qi = 0; qi < qs.size(); qi++) {
            Map<String, Object> qu = qs.get(qi);
            String type = String.valueOf(qu.get("type"));
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("title", qu.get("title"));
            st.put("type", type);
            if ("score".equals(type)) {
                double sum = 0; int n = 0;
                int[] dist = new int[6];
                for (Map<String, Object> r : resps) {
                    Object ans = Json.parse(str(r, "answers"));
                    if (ans instanceof List && qi < ((List<Object>) ans).size()) {
                        Object a = ((List<Object>) ans).get(qi);
                        if (a instanceof Map && ((Map<String, Object>) a).get("value") instanceof Number) {
                            double v = ((Number) ((Map<String, Object>) a).get("value")).doubleValue();
                            sum += v; n++;
                            if (v >= 1 && v <= 5) dist[(int) v]++;
                        }
                    }
                }
                st.put("avg", n > 0 ? Math.round(sum / n * 100) / 100.0 : 0);
                st.put("count", n);
                st.put("dist", dist);
            } else if ("single".equals(type)) {
                Map<String, Integer> opt = new LinkedHashMap<>();
                Object opts = qu.get("options");
                if (opts instanceof List) for (Object o : (List<Object>) opts) opt.put(String.valueOf(o), 0);
                for (Map<String, Object> r : resps) {
                    Object ans = Json.parse(str(r, "answers"));
                    if (ans instanceof List && qi < ((List<Object>) ans).size()) {
                        Object a = ((List<Object>) ans).get(qi);
                        if (a instanceof Map) {
                            String v = String.valueOf(((Map<String, Object>) a).get("value"));
                            opt.put(v, opt.getOrDefault(v, 0) + 1);
                        }
                    }
                }
                st.put("options", opt);
            } else {
                List<String> texts = new ArrayList<>();
                for (Map<String, Object> r : resps) {
                    Object ans = Json.parse(str(r, "answers"));
                    if (ans instanceof List && qi < ((List<Object>) ans).size()) {
                        Object a = ((List<Object>) ans).get(qi);
                        if (a instanceof Map) {
                            String v = String.valueOf(((Map<String, Object>) a).get("value"));
                            if (!v.isEmpty() && !"null".equals(v)) texts.add(v);
                        }
                    }
                }
                st.put("texts", texts);
            }
            stats.add(st);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("title", q.get("title"));
        r.put("target", q.get("target"));
        r.put("total", resps.size());
        double avgAll = resps.stream().filter(x -> x.get("avg_score") != null).mapToDouble(x -> Double.parseDouble(x.get("avg_score").toString())).average().orElse(0);
        r.put("avg_score", Math.round(avgAll * 100) / 100.0);
        r.put("questions", stats);
        r.put("responses", resps);
        ok(ex, r);
    }

    /** 课酬自动计算：保留已发放记录，只为尚未覆盖的已确认/完成课时生成差额。 */
    private static void feeCalc(HttpExchange ex) throws Exception {
        long pid = Json.lng(body(ex), "project_id");
        List<Map<String, Object>> created = Db.transaction(() -> {
            requireProjectNotArchived(pid);
            List<Map<String, Object>> rows = Db.query(
                    "SELECT d.teacher_id, SUM(d.hours) AS hours, t.name AS tname, t.fee_rate FROM dispatches d " +
                    "JOIN teachers t ON t.id=d.teacher_id WHERE d.project_id=? AND d.status IN ('已确认','已完成') GROUP BY d.teacher_id, t.name, t.fee_rate", pid);
            if (rows.isEmpty()) throw new ApiException(400, "该项目暂无已确认的授课安排，无法计算课酬");

            List<Map<String, Object>> paidRows = Db.query(
                    "SELECT teacher_id, COALESCE(SUM(hours),0) hours FROM fees " +
                    "WHERE project_id=? AND status='已发放' GROUP BY teacher_id", pid);
            Map<Long, Double> targetHours = new LinkedHashMap<>(), paidHours = new LinkedHashMap<>();
            for (Map<String, Object> row : rows)
                targetHours.put(Long.parseLong(String.valueOf(row.get("teacher_id"))), dbl(row, "hours"));
            for (Map<String, Object> row : paidRows)
                paidHours.put(Long.parseLong(String.valueOf(row.get("teacher_id"))), dbl(row, "hours"));
            for (Map.Entry<Long, Double> paid : paidHours.entrySet()) {
                double target = targetHours.getOrDefault(paid.getKey(), 0.0);
                if (paid.getValue() > target + 0.001)
                    throw new ApiException(400, "已发放课酬覆盖的课时超过当前授课安排，请先核对历史记录");
            }

            Db.exec("DELETE FROM fees WHERE project_id=? AND status='待发放'", pid);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                long teacherId = Long.parseLong(String.valueOf(r.get("teacher_id")));
                double hours = Math.max(0, dbl(r, "hours") - paidHours.getOrDefault(teacherId, 0.0));
                if (hours <= 0.001) continue;
                double rate = dbl(r, "fee_rate");
                double amount = Math.round(hours * rate * 100) / 100.0;
                long fid = Db.insert("INSERT INTO fees(project_id,teacher_id,hours,rate,amount,status,pay_date) VALUES(?,?,?,?,?,'待发放','')",
                        pid, teacherId, hours, rate, amount);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", fid);
                c.put("teacher_name", r.get("tname"));
                c.put("hours", hours);
                c.put("rate", rate);
                c.put("amount", amount);
                result.add(c);
            }
            return result;
        });
        ok(ex, created);
    }

    private static void feePay(HttpExchange ex) throws Exception {
        long id = Json.lng(body(ex), "id");
        Map<String, Object> fee = Db.one("SELECT * FROM fees WHERE id=?", id);
        if (fee == null) throw new ApiException(404, "课酬记录不存在");
        if ("已发放".equals(str(fee, "status"))) { ok(ex, "该笔课酬已发放，无需重复操作"); return; }
        requireProjectNotArchived(lng(fee, "project_id"));
        if (!"待发放".equals(str(fee, "status"))) throw new ApiException(400, "当前课酬状态不能执行发放");
        double expectedAmount = Math.round(dbl(fee, "hours") * dbl(fee, "rate") * 100) / 100.0;
        if (Math.abs(dbl(fee, "amount") - expectedAmount) > 0.005)
            throw new ApiException(400, "课酬金额与课时、标准不一致，请先重新计算");
        Map<String, Object> delivered = Db.one(
                "SELECT COALESCE(SUM(hours),0) hours FROM dispatches WHERE project_id=? AND teacher_id=? AND status='已完成'",
                fee.get("project_id"), fee.get("teacher_id"));
        Map<String, Object> paid = Db.one(
                "SELECT COALESCE(SUM(hours),0) hours FROM fees WHERE project_id=? AND teacher_id=? AND status='已发放'",
                fee.get("project_id"), fee.get("teacher_id"));
        double coveredAfterPay = dbl(paid, "hours") + dbl(fee, "hours");
        if (coveredAfterPay > dbl(delivered, "hours") + 0.001)
            throw new ApiException(400, "对应授课尚未完成，课酬可先核算，但只能在实际交付后发放");
        Db.exec("UPDATE fees SET status='已发放', pay_date=? WHERE id=?", today(), id);
        ok(ex, "课酬已发放");
    }

    /** 收费登记 */
    private static void chargeReceive(HttpExchange ex) throws Exception {
        Map<String, Object> b = body(ex);
        long id = Json.lng(b, "id");
        double amt = Json.num(b, "amount");
        if (amt <= 0) throw new ApiException(400, "收款金额必须大于0");
        Map<String, Object> c = Db.one("SELECT * FROM charges WHERE id=?", id);
        if (c == null) throw new ApiException(404, "收费记录不存在");
        requireProjectNotArchived(lng(c, "project_id"));
        double current = dbl(c, "received");
        double total = dbl(c, "amount");
        double remaining = Math.max(0, total - current);
        if (remaining <= 0.005) throw new ApiException(400, "该笔应收已结清，无需重复登记");
        if (amt - remaining > 0.005) throw new ApiException(400, String.format("本次收款不能超过剩余应收 %.2f 元", remaining));
        double received = current + amt;
        String st = received >= total ? "已结清" : "部分收费";
        Db.exec("UPDATE charges SET received=?, status=?, charge_date=? WHERE id=?", received, st, today(), id);
        ok(ex, "收款成功，当前状态：" + st);
    }

    /** 师资出库/重新入库 */
    private static void teacherOut(HttpExchange ex, String st) throws Exception {
        long id = Json.lng(body(ex), "id");
        Map<String, Object> teacher = Db.one("SELECT * FROM teachers WHERE id=?", id);
        if (teacher == null) throw new ApiException(404, "师资记录不存在");
        if ("出库".equals(st)) {
            long activeDispatches = lng(Db.one("SELECT COUNT(*) c FROM dispatches WHERE teacher_id=? " +
                    "AND status IN ('待发送','已发送','已确认')", id), "c");
            if (activeDispatches > 0)
                throw new ApiException(400, "该师资仍有 " + activeDispatches + " 条待执行授课安排，请先完成或重新调度");
            Db.exec("UPDATE teachers SET status='出库', out_date=? WHERE id=?", today(), id);
        } else Db.exec("UPDATE teachers SET status='在库', out_date='' WHERE id=?", id);
        ok(ex, "已" + st);
    }

    // ================= 统计分析 =================

    private static void statsOverview(HttpExchange ex) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("demands", Db.count("demands"));
        r.put("projects", Db.count("projects"));
        r.put("teachers", Db.one("SELECT COUNT(*) c FROM teachers WHERE status='在库'").get("c"));
        r.put("questionnaires", Db.count("questionnaires"));

        Map<String, Object> money = Db.one("SELECT COALESCE(SUM(amount),0) amt FROM projects");
        Map<String, Object> recv = Db.one("SELECT COALESCE(SUM(received),0) r, COALESCE(SUM(amount),0) a FROM charges");
        Map<String, Object> fee = Db.one("SELECT COALESCE(SUM(amount),0) a, COALESCE(SUM(CASE WHEN status='待发放' THEN amount END),0) p FROM fees");
        Map<String, Object> cost = Db.one("SELECT COALESCE(SUM(amount),0) a FROM costs");
        Map<String, Object> hours = Db.one("SELECT COALESCE(SUM(hours),0) h FROM dispatches WHERE status IN ('已确认','已完成')");
        Map<String, Object> eval = Db.one("SELECT ROUND(AVG(score),2) a, COUNT(*) c FROM teacher_evals");

        double amt = dbl(money, "amt"), feeA = dbl(fee, "a"), costA = dbl(cost, "a");
        r.put("project_amount", amt);
        r.put("charge_amount", dbl(recv, "a"));
        r.put("received", dbl(recv, "r"));
        r.put("fee_amount", feeA);
        r.put("fee_pending", dbl(fee, "p"));
        r.put("cost_amount", costA);
        r.put("profit", Math.round((amt - feeA - costA) * 100) / 100.0);
        r.put("teach_hours", dbl(hours, "h"));
        r.put("eval_avg", eval.get("a") == null ? 0 : eval.get("a"));
        r.put("eval_count", eval.get("c"));

        r.put("monthly", Db.query("SELECT SUBSTRING(start_date,1,7) AS ym, COUNT(*) AS cnt, COALESCE(SUM(amount),0) AS amt FROM projects WHERE start_date<>'' GROUP BY SUBSTRING(start_date,1,7) ORDER BY ym"));
        r.put("by_unit", Db.query("SELECT unit, COUNT(*) AS cnt, COALESCE(SUM(amount),0) AS amt FROM projects GROUP BY unit ORDER BY amt DESC"));
        r.put("by_teacher", Db.query("SELECT t.name, " +
                "COALESCE((SELECT SUM(d.hours) FROM dispatches d WHERE d.teacher_id=t.id AND d.status IN ('已确认','已完成')),0) AS hours, " +
                "COALESCE((SELECT SUM(f.amount) FROM fees f WHERE f.teacher_id=t.id),0) AS fee " +
                "FROM teachers t ORDER BY hours DESC"));
        r.put("cost_type", Db.query("SELECT type, COALESCE(SUM(amount),0) AS amt FROM costs GROUP BY type"));
        r.put("demand_status", Db.query("SELECT status, COUNT(*) AS cnt FROM demands GROUP BY status"));
        r.put("teacher_score", Db.query("SELECT t.name, ROUND(AVG(e.score),2) AS score, COUNT(*) AS cnt FROM teacher_evals e JOIN teachers t ON t.id=e.teacher_id GROUP BY t.name ORDER BY score DESC"));
        ok(ex, r);
    }

    /** 自动生成分析报告 */
    private static void statsReport(HttpExchange ex) throws Exception {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> p = Db.one("SELECT COUNT(*) c, COALESCE(SUM(amount),0) amt, COALESCE(SUM(hours),0) h FROM projects");
        Map<String, Object> recv = Db.one("SELECT COALESCE(SUM(received),0) r, COALESCE(SUM(amount),0) a FROM charges");
        Map<String, Object> fee = Db.one("SELECT COALESCE(SUM(amount),0) a, COALESCE(SUM(CASE WHEN status='待发放' THEN amount END),0) p FROM fees");
        Map<String, Object> cost = Db.one("SELECT COALESCE(SUM(amount),0) a FROM costs");
        Map<String, Object> eval = Db.one("SELECT ROUND(AVG(score),2) a FROM teacher_evals");
        List<Map<String, Object>> byUnit = Db.query("SELECT unit, COUNT(*) cnt, COALESCE(SUM(amount),0) amt FROM projects GROUP BY unit ORDER BY amt DESC");
        List<Map<String, Object>> byTeacher = Db.query("SELECT t.name, COALESCE(SUM(d.hours),0) hours FROM teachers t LEFT JOIN dispatches d ON d.teacher_id=t.id AND d.status IN ('已确认','已完成') GROUP BY t.name ORDER BY hours DESC");
        List<Map<String, Object>> costType = Db.query("SELECT type, COALESCE(SUM(amount),0) amt FROM costs GROUP BY type ORDER BY amt DESC");

        double amt = dbl(p, "amt"), r = dbl(recv, "r"), f = dbl(fee, "a"), c = dbl(cost, "a");
        double profit = amt - f - c;

        sb.append("培训项目统计分析报告\n");
        sb.append("生成时间：").append(now()).append("\n\n");
        sb.append("一、总体概况\n");
        sb.append(String.format("  累计培训需求 %d 项；已立项项目 %d 个，合同总额 %.2f 元，计划课时合计 %.0f 课时。\n",
                Db.count("demands"), lng(p, "c"), amt, dbl(p, "h")));
        sb.append(String.format("  师资库在库师资 %d 人；累计讲师履约评价 %d 份，平均评分 %.2f 分（5分制）。\n\n",
                Long.parseLong(String.valueOf(Db.one("SELECT COUNT(*) c FROM teachers WHERE status='在库'").get("c"))),
                Db.count("teacher_evals"), eval.get("a") == null ? 0.0 : dbl(eval, "a")));

        sb.append("二、财务状况\n");
        sb.append(String.format("  应收培训费 %.2f 元，已收 %.2f 元，收费率 %.1f%%。\n", dbl(recv, "a"), r, dbl(recv, "a") > 0 ? r / dbl(recv, "a") * 100 : 0));
        sb.append(String.format("  当前已录课酬 %.2f 元（其中待发放 %.2f 元），其他已录成本 %.2f 元；按当前已录支出估算余额 %.2f 元，占合同额 %.1f%%。\n",
                f, dbl(fee, "p"), c, profit, amt > 0 ? profit / amt * 100 : 0));
        sb.append("  注：尚未排课或尚未录入的未来支出未计入，不应将该估算余额视为最终项目毛利。\n\n");

        sb.append("三、项目分布（按需求单位）\n");
        for (Map<String, Object> u : byUnit)
            sb.append(String.format("  · %s：%d 个项目，金额 %.2f 元\n", u.get("unit"), lng(u, "cnt"), dbl(u, "amt")));
        sb.append("\n四、师资授课量排行\n");
        for (Map<String, Object> t : byTeacher)
            sb.append(String.format("  · %s：%.0f 课时\n", t.get("name"), dbl(t, "hours")));
        if (!costType.isEmpty()) {
            sb.append("\n五、成本构成\n");
            for (Map<String, Object> ct : costType)
                sb.append(String.format("  · %s：%.2f 元（%.1f%%）\n", ct.get("type"), dbl(ct, "amt"), c > 0 ? dbl(ct, "amt") / c * 100 : 0));
        }
        sb.append("\n六、工作建议\n");
        if (dbl(fee, "p") > 0) sb.append("  1. 存在待发放课酬，请财务及时办理发放手续。\n");
        if (r < dbl(recv, "a")) sb.append("  2. 部分项目款项未结清，建议跟进催收。\n");
        sb.append("  3. 持续关注师资评估结果，对评分较低的师资加强沟通或调整出库。\n");
        sb.append("  4. 按季度复盘各单位培训需求，提前储备对口师资。\n");

        ok(ex, sb.toString());
    }

    // ================= 工具方法 =================

    static class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;
        ApiException(int code, String msg) { super(msg); this.code = code; }
    }

    private static void requireWrite(Auth.Session s) throws ApiException {
        if (!Auth.canWrite(s)) throw new ApiException(403, "无权限：只读用户无法执行此操作");
    }

    private static void requireMethod(HttpExchange ex, String... allowed) throws ApiException {
        String actual = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        for (String method : allowed) if (method.equals(actual)) return;
        ex.getResponseHeaders().set("Allow", String.join(", ", allowed));
        throw new ApiException(405, "请求方法不受支持");
    }

    private static void requireJsonContentType(HttpExchange ex) throws ApiException {
        String raw = ex.getRequestHeaders().getFirst("Content-Type");
        String mediaType = raw == null ? "" : raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!"application/json".equals(mediaType))
            throw new ApiException(415, "写入接口只接受 application/json 请求");
    }

    static String token(HttpExchange ex) {
        String cookieToken = null;
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String item = part.trim();
                String prefix = SESSION_COOKIE + "=";
                if (item.startsWith(prefix) && item.length() > prefix.length()) {
                    cookieToken = item.substring(prefix.length());
                    break;
                }
            }
        }
        if (cookieToken != null && Auth.get(cookieToken) != null) return cookieToken;
        String headerToken = ex.getRequestHeaders().getFirst("X-Token");
        if (headerToken != null && !headerToken.trim().isEmpty() && Auth.get(headerToken.trim()) != null)
            return headerToken.trim();
        return cookieToken != null ? cookieToken : (headerToken == null ? null : headerToken.trim());
    }

    private static boolean secureCookie(HttpExchange ex) {
        String forwarded = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
        boolean forwardedHttps = forwarded != null && Arrays.stream(forwarded.split(","))
                .anyMatch(value -> "https".equalsIgnoreCase(value.trim()));
        String host = String.valueOf(ex.getRequestHeaders().getFirst("Host")).trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            host = end > 0 ? host.substring(1, end) : host;
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
        }
        String[] octets = host.split("\\.");
        boolean ipv4Loopback = octets.length == 4 && "127".equals(octets[0]);
        boolean loopbackHost = "localhost".equals(host) || "::1".equals(host) ||
                "0:0:0:0:0:0:0:1".equals(host) || ipv4Loopback;
        return forwardedHttps || !loopbackHost;
    }

    private static void setSessionCookie(HttpExchange ex, String token) {
        String value = SESSION_COOKIE + "=" + token + "; Path=/; Max-Age=43200; HttpOnly; SameSite=Lax";
        if (secureCookie(ex)) value += "; Secure";
        ex.getResponseHeaders().add("Set-Cookie", value);
    }

    private static void clearSessionCookie(HttpExchange ex) {
        String value = SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax";
        if (secureCookie(ex)) value += "; Secure";
        ex.getResponseHeaders().add("Set-Cookie", value);
    }

    private static Map<String, Object> readBodyLimited(HttpExchange ex) throws IOException, ApiException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY_BYTES) throw new ApiException(413, "请求内容过大");
                bos.write(buf, 0, n);
            }
            String s = bos.toString(StandardCharsets.UTF_8);
            if (s.trim().isEmpty()) return new LinkedHashMap<>();
            try {
                Object parsed = Json.parse(s);
                if (!(parsed instanceof Map)) throw new ApiException(400, "请求内容必须是 JSON 对象");
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) parsed;
                return result;
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ApiException(400, "请求内容不是有效的 JSON");
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(HttpExchange ex) {
        Object cached = ex.getAttribute(BODY_ATTRIBUTE);
        return cached instanceof Map ? (Map<String, Object>) cached : new LinkedHashMap<>();
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try {
                if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                else m.put(URLDecoder.decode(kv, "UTF-8"), "");
            } catch (Exception ignored) {}
        }
        return m;
    }

    static void ok(HttpExchange ex, Object data) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 0);
        r.put("data", data);
        json(ex, 200, r);
    }

    static void err(HttpExchange ex, int code, String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", code);
        r.put("msg", msg);
        json(ex, code >= 400 && code <= 599 ? code : 400, r);
    }

    /** 锁内只生成响应；真正的网络写回在数据库锁释放后执行。 */
    private static void json(HttpExchange ex, int httpCode, Object o) {
        byte[] b = Json.write(o).getBytes(StandardCharsets.UTF_8);
        ex.setAttribute(RESPONSE_ATTRIBUTE, new ApiResponse(httpCode, b));
    }

    private static void flushResponse(HttpExchange ex) throws IOException {
        Object pending = ex.getAttribute(RESPONSE_ATTRIBUTE);
        ApiResponse response = pending instanceof ApiResponse
                ? (ApiResponse) pending
                : new ApiResponse(500, "{\"code\":500,\"msg\":\"服务器未生成响应\"}".getBytes(StandardCharsets.UTF_8));
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(response.httpCode, -1);
            return;
        }
        ex.sendResponseHeaders(response.httpCode, response.body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(response.body); }
    }

    static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    static double dbl(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return v == null ? 0 : Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    static long lng(Map<String, Object> m, String k) {
        return (long) dbl(m, k);
    }

    static String today() {
        return LocalDate.now().toString();
    }

    static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
