package com.training;

import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 私有讲师简历、结构化画像与本地可解释推荐。
 *
 * 原始简历和提取文本只在服务端使用。PDF 解析运行在有内存和时间上限的
 * 独立 JVM 中；本类不访问任何外部 AI 或第三方服务。
 */
public final class TeacherIntelligence {
    private static final long DEFAULT_MAX_PDF_BYTES = 15L * 1024 * 1024;
    private static final long DEFAULT_MAX_PPTX_BYTES = 80L * 1024 * 1024;
    private static final long MAX_PDF_BYTES = positiveConfiguredLong("teacher.resume.pdf.max.bytes", DEFAULT_MAX_PDF_BYTES);
    private static final long MAX_PPTX_BYTES = positiveConfiguredLong("teacher.resume.pptx.max.bytes", DEFAULT_MAX_PPTX_BYTES);
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final int MAX_META_HEADER_BYTES = 8 * 1024;
    private static final int MAX_PDF_PAGES = 50;
    private static final int MAX_PPTX_PAGES = 100;
    private static final int MAX_EXTRACTED_CHARS = 200_000;
    private static final int MAX_REQUIREMENT_CHARS = 10_000;
    private static final long PARSE_TIMEOUT_SECONDS = 12;
    private static final String PROFILE_VERSION = "local-rules-v2";
    private static final String RECOMMENDATION_VERSION = "local-rules-v3-prebid-locality";

    private static final Set<Long> SCHEDULED = ConcurrentHashMap.newKeySet();
    /** Upload validation and queued reparses share the same two extraction slots. */
    private static final Semaphore EXTRACTION_SLOTS = new Semaphore(2, true);
    private static final ThreadPoolExecutor PARSER = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            new ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger number = new java.util.concurrent.atomic.AtomicInteger();
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "yanxu-resume-parser-" + number.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static final Map<String, List<String>> TOPICS = new LinkedHashMap<>();
    private static final Map<String, List<String>> INDUSTRIES = new LinkedHashMap<>();
    private static final Map<String, List<String>> AUDIENCES = new LinkedHashMap<>();
    private static final Map<String, List<String>> CREDENTIALS = new LinkedHashMap<>();
    private static final Map<String, List<String>> DELIVERY_MODES = new LinkedHashMap<>();
    private static final Pattern COURSE_PATTERN = Pattern.compile("[《<]([^》>\\r\\n]{2,80})[》>]");
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2})[年./-](\\d{1,2})[月./-](\\d{1,2})日?");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z][a-z0-9+#.-]{1,30}");
    private static final Pattern HAN_RUN = Pattern.compile("[\\p{IsHan}]{2,40}");
    private static final Pattern CLAIM_HOURS = Pattern.compile("(?i)(\\d{1,6}(?:\\.\\d+)?\\+?)\\s*(?:累计|completed\\s+)?(?:课时|小时|training\\s+hours?|hours?)");
    private static final Pattern CLAIM_SESSIONS = Pattern.compile("(?i)(\\d{1,6}(?:\\.\\d+)?\\+?)\\s*(?:累计|completed\\s+)?(?:场|场次|期|sessions?|classes?)");
    private static final Pattern CLAIM_SATISFACTION = Pattern.compile("(?i)(\\d{1,3}(?:\\.\\d+)?)\\s*(?:%|％|percent)\\s*(?:\\+|以上|or\\s+more)?(?:[^\\r\\n，。;；]{0,24})?(?:满意|satisfaction)");
    private static final Pattern CLAIM_SATISFACTION_PREFIX = Pattern.compile("(?i)(?:满意度?|satisfaction)(?:[^\\d\\r\\n]{0,12})(\\d{1,3}(?:\\.\\d+)?)\\s*(?:%|％|percent)");
    private static final Pattern CLAIM_YEARS = Pattern.compile("(?i)(\\d{1,3}(?:\\.\\d+)?\\+?)\\s*(?:年|years?)(?:[^\\r\\n，。;；]{0,16})?(?:经验|从业|experience)");

    static {
        put(TOPICS, "领导力与管理", "领导力", "中层管理", "团队管理", "组织管理", "执行力", "绩效管理", "管理能力");
        put(TOPICS, "客户服务与投诉", "客户服务", "投诉处理", "客户投诉", "异议处理", "消保", "消费者权益", "厅堂服务", "服务礼仪");
        put(TOPICS, "营销与客户经营", "营销", "客户经营", "客户拓展", "存量客户", "零售营销", "网点营销", "财富管理", "销售技巧");
        put(TOPICS, "合规与风险管理", "合规", "风险管理", "内控", "反洗钱", "监管政策", "操作风险", "风控");
        put(TOPICS, "财务与税务", "财务管理", "税务", "税收", "会计", "预算管理", "成本管理", "税务筹划");
        put(TOPICS, "党建与党史", "党建", "党史", "党风廉政", "廉洁", "党务", "政策法规", "习近平新时代");
        put(TOPICS, "人力资源", "人力资源", "人才管理", "招聘", "绩效体系", "员工关系", "人才发展", "组织发展");
        put(TOPICS, "沟通与表达", "沟通", "表达", "演讲", "商务礼仪", "跨部门沟通", "谈判", "汇报");
        put(TOPICS, "数字化与人工智能", "数字化", "人工智能", "大数据", "ai", "数字转型", "智能化", "数据分析");
        put(TOPICS, "职业素养与新员工", "职业素养", "新员工", "时间管理", "情绪管理", "压力管理", "职业化", "团队建设");
        put(TOPICS, "职业形象与礼仪", "职业形象", "职业礼仪", "商务礼仪", "服务礼仪", "形象管理");
        put(TOPICS, "办公效能与Excel", "excel", "办公软件", "办公效能", "数据透视表", "office办公");
        put(TOPICS, "求职与面试", "求职", "面试", "简历辅导", "就业指导", "职业规划");
        put(TOPICS, "员工关系", "员工关系", "劳动关系", "劳动争议", "用工风险");

        put(INDUSTRIES, "银行与金融", "银行", "金融", "保险", "证券", "信贷", "网点", "支行", "财富管理");
        put(INDUSTRIES, "政府与事业单位", "政府", "机关", "事业单位", "公务员", "党政", "干部教育", "国资委");
        put(INDUSTRIES, "国有企业", "国企", "央企", "国有企业");
        put(INDUSTRIES, "制造业", "制造业", "工厂", "生产管理", "供应链");
        put(INDUSTRIES, "互联网与科技", "互联网", "科技企业", "软件", "信息技术");
        put(INDUSTRIES, "医疗健康", "医疗", "医院", "医药", "健康");
        put(INDUSTRIES, "教育培训", "高校", "大学", "职业院校", "教育", "培训机构");

        put(AUDIENCES, "中高层管理者", "中高层", "中层干部", "管理干部", "领导干部", "管理者", "高管");
        put(AUDIENCES, "基层管理者", "基层管理", "班组长", "主管", "网点负责人");
        put(AUDIENCES, "新员工", "新员工", "新入职", "应届生", "校招生");
        put(AUDIENCES, "客户经理与营销人员", "客户经理", "营销人员", "销售人员", "理财经理");
        put(AUDIENCES, "柜员与厅堂人员", "柜员", "大堂经理", "厅堂人员", "市场助理");
        put(AUDIENCES, "财务人员", "财务人员", "会计人员", "财务经理");
        put(AUDIENCES, "党政干部", "党政干部", "党员干部", "党务干部");
        put(AUDIENCES, "高校学生", "高校学生", "大学生", "毕业生", "在校生");
        put(AUDIENCES, "企业员工", "企业员工", "公司员工", "职场员工", "全体员工");

        put(CREDENTIALS, "教授", "教授", "正高级");
        put(CREDENTIALS, "副教授或副高", "副教授", "副高", "副高级");
        put(CREDENTIALS, "高级经济师", "高级经济师");
        put(CREDENTIALS, "注册会计师", "注册会计师", "cpa");
        put(CREDENTIALS, "党校背景", "党校", "行政学院");
        put(CREDENTIALS, "金融行业实务", "银行从业", "金融从业", "银行工作", "金融实务");
        put(CREDENTIALS, "企业管理实务", "企业高管", "管理咨询", "实战经验", "企业管理经验");
        put(CREDENTIALS, "企业人力资源管理师", "企业人力资源管理师", "人力资源管理师");

        put(DELIVERY_MODES, "线下授课", "线下", "面授", "现场授课");
        put(DELIVERY_MODES, "线上授课", "线上", "直播", "远程授课");
        put(DELIVERY_MODES, "工作坊", "工作坊", "共创", "行动学习");
        put(DELIVERY_MODES, "案例研讨", "案例研讨", "案例教学", "情景演练", "角色扮演");
    }

    private TeacherIntelligence() {}

    /** 服务启动时恢复被重启打断的解析任务，并继续消费持久化队列。 */
    public static void init() {
        try {
            synchronized (Api.MUTATION_LOCK) {
                Db.exec("UPDATE teacher_resumes SET parse_status='queued',parse_error='服务重启后已重新排队'," +
                        "updated_at=CURRENT_TIMESTAMP WHERE parse_status='processing'");
            }
            drainQueued();
        } catch (Exception e) {
            System.err.println("讲师简历任务恢复失败: " + e.getClass().getSimpleName());
        }
    }

    public static void handle(HttpExchange ex) throws IOException {
        try {
            Auth.Session session;
            synchronized (Api.MUTATION_LOCK) { session = Auth.get(Api.token(ex)); }
            if (session == null) throw new TalentException(401, "未登录或会话已过期，请重新登录");
            if (!Auth.canWrite(session)) throw new TalentException(403, "仅系统管理员或业务管理员可使用师资智能功能");

            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            if ("/api/teacher-resumes".equals(path) || "/api/teacher-resumes/manage".equals(path)) {
                requireMethod(ex, "GET", "HEAD");
                listResumes(ex);
            } else if ("/api/teacher-resumes/upload".equals(path)) {
                requireMethod(ex, "POST");
                uploadResume(ex, session);
            } else if ("/api/teacher-resumes/profile".equals(path)) {
                if ("GET".equals(method) || "HEAD".equals(method)) getProfile(ex);
                else if ("POST".equals(method)) saveProfile(ex, session);
                else requireMethod(ex, "GET", "HEAD", "POST");
            } else if ("/api/teacher-resumes/download".equals(path)) {
                requireMethod(ex, "GET", "HEAD");
                downloadResume(ex);
            } else if ("/api/teacher-resumes/reparse".equals(path)) {
                requireMethod(ex, "POST");
                reparseResume(ex);
            } else if ("/api/teacher-resumes/delete".equals(path)) {
                requireMethod(ex, "POST");
                deleteResume(ex);
            } else if ("/api/teacher-recommendations".equals(path)) {
                requireMethod(ex, "POST");
                recommend(ex);
            } else {
                throw new TalentException(404, "师资智能接口不存在");
            }
        } catch (TalentException e) {
            sendError(ex, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage() == null ? "请求参数格式不正确" : e.getMessage());
        } catch (Exception e) {
            System.err.println("师资智能服务异常: " + e.getClass().getSimpleName());
            sendError(ex, 500, "师资智能服务暂时无法处理请求，请稍后重试");
        }
    }

    private static void listResumes(HttpExchange ex) throws Exception {
        Map<String, String> query = Api.query(ex);
        String rawTeacherId = cleanLine(query.get("teacher_id"));
        List<Map<String, Object>> rows;
        synchronized (Api.MUTATION_LOCK) {
            if (rawTeacherId.isEmpty()) {
                rows = Db.query("SELECT r.id,r.teacher_id,t.name teacher_name,r.file_name,r.file_size,r.page_count," +
                        "r.parse_status,r.parse_error parse_message,r.manual_profile,r.profile_source,r.is_current,r.created_at,r.parsed_at,r.reviewed_at,r.updated_at," +
                        "CASE WHEN r.profile_json IS NULL THEN FALSE ELSE TRUE END has_profile " +
                        "FROM teacher_resumes r LEFT JOIN teachers t ON t.id=r.teacher_id " +
                        "ORDER BY r.created_at DESC,r.id DESC");
            } else {
                long teacherId = positiveId(rawTeacherId, "师资编号");
                rows = Db.query("SELECT r.id,r.teacher_id,t.name teacher_name,r.file_name,r.file_size,r.page_count," +
                        "r.parse_status,r.parse_error parse_message,r.manual_profile,r.profile_source,r.is_current,r.created_at,r.parsed_at,r.reviewed_at,r.updated_at," +
                        "CASE WHEN r.profile_json IS NULL THEN FALSE ELSE TRUE END has_profile " +
                        "FROM teacher_resumes r LEFT JOIN teachers t ON t.id=r.teacher_id " +
                        "WHERE r.teacher_id=? ORDER BY r.created_at DESC,r.id DESC", teacherId);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("max_pdf_bytes", MAX_PDF_BYTES);
        result.put("max_pptx_bytes", MAX_PPTX_BYTES);
        result.put("accepted_extensions", Arrays.asList("pdf", "pptx"));
        result.put("limits", limits());
        sendOk(ex, result);
    }

    private static void uploadResume(HttpExchange ex, Auth.Session session) throws Exception {
        UploadRequest request = readUploadRequest(ex);
        long teacherId = request.teacherId;
        String fileName = request.fileName;
        String extension = request.extension;
        requireUploadContentType(ex, extension);
        if (fileName.isEmpty()) throw new TalentException(400, "请选择讲师简历文件");
        if (fileName.length() > 180) throw new TalentException(400, "文件名不能超过180个字符");
        synchronized (Api.MUTATION_LOCK) {
            Map<String, Object> residence = Db.one("SELECT id,base_province,base_city FROM teachers WHERE id=?", teacherId);
            if (residence == null) throw new TalentException(404, "讲师不存在或已被删除");
            DispatchPreference.validateRegion(residence, "base_province", "base_city", true);
        }

        long uploadLimit = uploadLimit(extension);
        long announced = contentLength(ex.getRequestHeaders().getFirst("Content-Length"));
        if (announced == 0) throw new TalentException(400, "上传文件不能为空");
        if (announced > uploadLimit) throw new TalentException(413, uploadLimitMessage(extension));

        Path root = resumeRoot();
        ensurePrivateDirectory(root);
        Path temp = Files.createTempFile(root, ".resume-upload-", ".tmp");
        Path extractedFile = null;
        setOwnerOnlyFile(temp);
        Path finalFile = null;
        boolean keepFinal = false;
        try {
            UploadResult upload = copyLimited(ex.getRequestBody(), temp, uploadLimit, extension);
            if (upload.size == 0) throw new TalentException(400, "上传文件不能为空");
            validateFileHeader(temp, extension);

            Map<String, Object> duplicate;
            synchronized (Api.MUTATION_LOCK) {
                duplicate = Db.one("SELECT id,teacher_id,parse_status FROM teacher_resumes WHERE teacher_id=? AND sha256=?",
                        teacherId, upload.sha256);
            }
            if (duplicate != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("teacher_id", teacherId);
                data.put("resume_id", duplicate.get("id"));
                data.put("status", duplicate.get("parse_status"));
                data.put("duplicate", true);
                sendOk(ex, data);
                return;
            }

            extractedFile = Files.createTempFile(root, ".resume-text-", ".tmp");
            setOwnerOnlyFile(extractedFile);
            ExtractedPdf extraction;
            String parsedStatus;
            String parsedMessage;
            if (!EXTRACTION_SLOTS.tryAcquire())
                throw new TalentException(429, "简历解析任务较多，请稍后再上传");
            try {
                try {
                    extraction = runExtractor(temp, extractedFile, extension);
                    parsedStatus = "ready";
                    parsedMessage = null;
                } catch (NeedsOcr e) {
                    // The document container is valid, so retain the private original and
                    // let an administrator supply a manual profile instead of losing it.
                    extraction = new ExtractedPdf(e.pages, "");
                    parsedStatus = "needs_ocr";
                    parsedMessage = "未提取到足够文字，可能是扫描版或图片型简历；可人工维护专业画像后参与推荐。";
                } catch (ParseFailure e) {
                    // Malformed/encrypted/unsafe files never replace the last good resume.
                    throw new TalentException(422, e.getMessage());
                }
            } finally {
                EXTRACTION_SLOTS.release();
            }
            final ExtractedPdf extracted = extraction;
            final String parseStatus = parsedStatus;
            final String parseMessage = parsedMessage;
            String safeText = redact(extracted.text);
            Map<String, Object> teacher;
            synchronized (Api.MUTATION_LOCK) {
                teacher = Db.one("SELECT id,name,org,title,field,intro FROM teachers WHERE id=?", teacherId);
            }
            if (teacher == null) throw new TalentException(404, "讲师不存在或已被删除");
            String profileSource = "ready".equals(parseStatus) ? "auto_local" : "basic_fallback";

            String storageName = UUID.randomUUID().toString() + "." + extension;
            finalFile = safeStoragePath(root, storageName);
            moveAtomically(temp, finalFile);
            setOwnerOnlyFile(finalFile);

            List<String> oldStorageNames = new ArrayList<>();
            long id;
            synchronized (Api.MUTATION_LOCK) {
                id = Db.transaction(() -> {
                    Auth.Session active = Auth.get(Api.token(ex));
                    if (active == null || !Auth.canWrite(active)) throw new TalentException(403, "账号权限已变更，请重新登录后重试");
                    Map<String, Object> currentTeacher = Db.one("SELECT id,name,org,title,field,intro,base_province,base_city FROM teachers WHERE id=?", teacherId);
                    if (currentTeacher == null) throw new TalentException(404, "讲师已被删除，请刷新后重试");
                    DispatchPreference.validateRegion(currentTeacher, "base_province", "base_city", true);
                    List<Map<String, Object>> old = Db.query("SELECT storage_name,manual_profile,profile_json,reviewed_by,reviewed_at FROM teacher_resumes WHERE teacher_id=? ORDER BY id DESC", teacherId);
                    for (Map<String, Object> item : old) oldStorageNames.add(value(item, "storage_name"));
                    Map<String, Object> previous = old.isEmpty() ? Collections.emptyMap() : old.get(0);
                    String preservedManual = value(previous, "manual_profile");
                    Map<String, Object> effective = effectiveProfile(safeText, currentTeacher, preservedManual, value(previous, "profile_json"));
                    Db.exec("DELETE FROM teacher_resumes WHERE teacher_id=?", teacherId);
                    return Db.insert("INSERT INTO teacher_resumes(teacher_id,file_name,storage_name,file_size,sha256," +
                                    "page_count,extracted_text,profile_json,profile_source,parse_status,parse_error,is_current,uploaded_by,parsed_at,manual_profile,reviewed_by,reviewed_at) " +
                                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,TRUE,?,CURRENT_TIMESTAMP,?,?,?)",
                            teacherId, fileName, storageName, upload.size, upload.sha256, extracted.pages, safeText,
                            Json.write(effective), preservedManual.isEmpty() ? profileSource : "manual", parseStatus, parseMessage, session.uid,
                            preservedManual.isEmpty() ? null : preservedManual, previous.get("reviewed_by"), previous.get("reviewed_at"));
                });
            }
            keepFinal = true;
            for (String oldStorageName : oldStorageNames) {
                if (oldStorageName.equals(storageName)) continue;
                try { Files.deleteIfExists(safeStoragePath(root, oldStorageName)); }
                catch (IOException e) { System.err.println("旧讲师简历文件清理失败: teacher_id=" + teacherId); }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("teacher_id", teacherId);
            data.put("resume_id", id);
            data.put("status", parseStatus);
            data.put("parse_status", parseStatus);
            if (parseMessage != null) data.put("parse_message", parseMessage);
            data.put("duplicate", false);
            sendOk(ex, data);
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            if (extractedFile != null) try { Files.deleteIfExists(extractedFile); } catch (IOException ignored) {}
            if (!keepFinal && finalFile != null) try { Files.deleteIfExists(finalFile); } catch (IOException ignored) {}
        }
    }

    private static void getProfile(HttpExchange ex) throws Exception {
        Map<String, String> query = Api.query(ex);
        String rawId = cleanLine(query.get("id"));
        String rawTeacherId = cleanLine(query.get("teacher_id"));
        if (rawId.isEmpty() && rawTeacherId.isEmpty()) throw new TalentException(400, "请提供师资编号");
        Map<String, Object> row;
        synchronized (Api.MUTATION_LOCK) {
            String sql = "SELECT r.id,r.teacher_id,t.name teacher_name,r.file_name,r.file_size,r.page_count," +
                    "r.parse_status,r.parse_error parse_message,r.manual_profile,r.profile_source,r.is_current,r.profile_json,r.created_at," +
                    "r.parsed_at,r.reviewed_at,r.updated_at FROM teacher_resumes r LEFT JOIN teachers t ON t.id=r.teacher_id WHERE " +
                    (rawId.isEmpty() ? "r.teacher_id=? AND r.is_current=TRUE" : "r.id=?");
            row = Db.one(sql, positiveId(rawId.isEmpty() ? rawTeacherId : rawId, rawId.isEmpty() ? "师资编号" : "简历编号"));
        }
        if (row == null) throw new TalentException(404, "简历不存在或已被删除");
        Object profile = parseProfile(value(row, "profile_json"));
        row.remove("profile_json");
        row.put("profile", profile);
        sendOk(ex, row);
    }

    private static void saveProfile(HttpExchange ex, Auth.Session session) throws Exception {
        requireJson(ex);
        Map<String, Object> body = readJsonBody(ex);
        long requestedResumeId = body.containsKey("id") ? positiveId(body.get("id"), "简历编号") : 0;
        long requestedTeacherId = body.containsKey("teacher_id") ? positiveId(body.get("teacher_id"), "师资编号") : 0;
        if (requestedResumeId == 0 && requestedTeacherId == 0) throw new TalentException(400, "请提供师资编号");

        Map<String, Object> row;
        synchronized (Api.MUTATION_LOCK) {
            row = Db.one("SELECT r.id,r.teacher_id,r.parse_status,r.extracted_text,t.name,t.org,t.title,t.field,t.intro " +
                            "FROM teacher_resumes r LEFT JOIN teachers t ON t.id=r.teacher_id WHERE " +
                            (requestedResumeId > 0 ? "r.id=?" : "r.teacher_id=? AND r.is_current=TRUE"),
                    requestedResumeId > 0 ? requestedResumeId : requestedTeacherId);
        }
        if (row == null) throw new TalentException(404, "简历不存在或已被删除");
        long id = asLong(row.get("id"));
        String manualProfile;
        Map<String, Object> profile;
        Object rawProfile = body.get("profile");
        if (body.containsKey("manual_profile")) {
            manualProfile = cleanText(value(body, "manual_profile"));
            if (manualProfile.isEmpty()) throw new TalentException(400, "请填写人工专业画像");
            if (manualProfile.length() > 10_000) throw new TalentException(400, "人工专业画像不能超过10000个字符");
            profile = autoProfile(manualProfile, row);
            profile.put("source", "manual");
            profile.put("summary", manualProfile.length() <= 1000 ? manualProfile : manualProfile.substring(0, 1000));
        } else if (rawProfile instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> input = (Map<String, Object>) rawProfile;
            profile = validatedManualProfile(input, row);
            manualProfile = value(profile, "summary");
        } else {
            throw new TalentException(400, "请填写人工专业画像");
        }
        profile.put("resume_claims", extractResumeClaims(value(row, "extracted_text")));
        profile.put("manual_claims", extractResumeClaims(manualProfile));
        String profileJson = Json.write(profile);
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session active = Auth.get(Api.token(ex));
            if (active == null || !Auth.canWrite(active)) throw new TalentException(403, "账号权限已变更，请重新登录后重试");
            Map<String, Object> current = Db.one("SELECT r.id,t.base_province,t.base_city FROM teacher_resumes r JOIN teachers t ON t.id=r.teacher_id WHERE r.id=? AND r.is_current=TRUE", id);
            if (current == null) throw new TalentException(409, "简历已被替换或删除，请刷新后重新编辑");
            if (body.containsKey("base_province")) current.put("base_province", body.get("base_province"));
            if (body.containsKey("base_city")) current.put("base_city", body.get("base_city"));
            DispatchPreference.validateRegion(current, "base_province", "base_city", true);
            final String profileText = manualProfile;
            Db.transaction(() -> {
                Db.exec("UPDATE teachers SET base_province=?,base_city=? WHERE id=?", current.get("base_province"), current.get("base_city"), row.get("teacher_id"));
                Db.exec("UPDATE teacher_resumes SET profile_json=?,manual_profile=?,profile_source='manual',parse_status='ready'," +
                                "parse_error=NULL,is_current=TRUE,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP," +
                                "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        profileJson, profileText, session.uid, id);
                return null;
            });
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teacher_id", row.get("teacher_id"));
        data.put("resume_id", id);
        data.put("status", "ready");
        data.put("is_current", true);
        data.put("manual_profile", manualProfile);
        data.put("profile", profile);
        sendOk(ex, data);
    }

    private static void reparseResume(HttpExchange ex) throws Exception {
        requireJson(ex);
        Map<String, Object> body = readJsonBody(ex);
        long requestedResumeId = body.containsKey("id") ? positiveId(body.get("id"), "简历编号") : 0;
        long requestedTeacherId = body.containsKey("teacher_id") ? positiveId(body.get("teacher_id"), "师资编号") : 0;
        if (requestedResumeId == 0 && requestedTeacherId == 0) throw new TalentException(400, "请提供师资编号");
        Map<String, Object> row;
        synchronized (Api.MUTATION_LOCK) {
            row = Db.one("SELECT id,teacher_id,storage_name,parse_status FROM teacher_resumes WHERE " +
                            (requestedResumeId > 0 ? "id=?" : "teacher_id=? AND is_current=TRUE"),
                    requestedResumeId > 0 ? requestedResumeId : requestedTeacherId);
            if (row == null) throw new TalentException(404, "简历不存在或已被删除");
            if ("processing".equals(value(row, "parse_status")))
                throw new TalentException(409, "简历正在解析，无需重复提交");
        }
        long id = asLong(row.get("id"));
        if (!Files.isRegularFile(safeStoragePath(resumeRoot(), value(row, "storage_name"))))
            throw new TalentException(404, "简历原件暂时不可用，请重新上传");
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session active = Auth.get(Api.token(ex));
            if (active == null || !Auth.canWrite(active)) throw new TalentException(403, "账号权限已变更，请重新登录后重试");
            if (Db.one("SELECT r.id FROM teacher_resumes r JOIN teachers t ON t.id=r.teacher_id WHERE r.id=? AND r.is_current=TRUE", id) == null)
                throw new TalentException(409, "简历已被替换或删除，请刷新后重试");
            Db.exec("UPDATE teacher_resumes SET parse_status='queued',parse_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        }
        enqueue(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teacher_id", row.get("teacher_id"));
        data.put("resume_id", id);
        data.put("status", "queued");
        sendJson(ex, 202, envelope(data));
    }

    private static void deleteResume(HttpExchange ex) throws Exception {
        requireJson(ex);
        Map<String, Object> body = readJsonBody(ex);
        long requestedResumeId = body.containsKey("id") ? positiveId(body.get("id"), "简历编号") : 0;
        long requestedTeacherId = body.containsKey("teacher_id") ? positiveId(body.get("teacher_id"), "师资编号") : 0;
        if (requestedResumeId == 0 && requestedTeacherId == 0) throw new TalentException(400, "请提供师资编号");
        List<Map<String, Object>> rows;
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session active = Auth.get(Api.token(ex));
            if (active == null || !Auth.canWrite(active)) throw new TalentException(403, "账号权限已变更，请重新登录后重试");
            rows = requestedResumeId > 0
                    ? Db.query("SELECT id,teacher_id,storage_name,parse_status FROM teacher_resumes WHERE id=?", requestedResumeId)
                    : Db.query("SELECT id,teacher_id,storage_name,parse_status FROM teacher_resumes WHERE teacher_id=?", requestedTeacherId);
            if (rows.isEmpty()) throw new TalentException(404, "简历不存在或已被删除");
            for (Map<String, Object> row : rows) {
                if ("processing".equals(value(row, "parse_status")))
                    throw new TalentException(409, "简历正在解析，请稍后再删除");
            }
            if (requestedResumeId > 0) Db.exec("DELETE FROM teacher_resumes WHERE id=?", requestedResumeId);
            else Db.exec("DELETE FROM teacher_resumes WHERE teacher_id=?", requestedTeacherId);
        }
        for (Map<String, Object> row : rows) {
            long id = asLong(row.get("id"));
            SCHEDULED.remove(id);
            try { Files.deleteIfExists(safeStoragePath(resumeRoot(), value(row, "storage_name"))); }
            catch (IOException e) { System.err.println("讲师简历文件清理失败: resume_id=" + id); }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("teacher_id", rows.get(0).get("teacher_id"));
        result.put("status", "deleted");
        sendOk(ex, result);
    }

    private static void downloadResume(HttpExchange ex) throws Exception {
        Map<String, String> query = Api.query(ex);
        String rawId = cleanLine(query.get("id"));
        String rawTeacherId = cleanLine(query.get("teacher_id"));
        if (rawId.isEmpty() && rawTeacherId.isEmpty()) throw new TalentException(400, "请提供师资编号");
        Map<String, Object> row;
        synchronized (Api.MUTATION_LOCK) {
            row = Db.one("SELECT id,teacher_id,file_name,storage_name,file_size FROM teacher_resumes WHERE " +
                            (rawId.isEmpty() ? "teacher_id=? AND is_current=TRUE" : "id=?"),
                    positiveId(rawId.isEmpty() ? rawTeacherId : rawId, rawId.isEmpty() ? "师资编号" : "简历编号"));
        }
        if (row == null) throw new TalentException(404, "简历不存在或已被删除");
        Path file = safeStoragePath(resumeRoot(), value(row, "storage_name"));
        if (!Files.isRegularFile(file)) throw new TalentException(404, "简历原件暂时不可用，请重新上传");
        long size = Files.size(file);
        String fileName = safeFileName(value(row, "file_name"));
        String extension = extensionOf(value(row, "storage_name"));
        String fallback = "teacher-resume-" + row.get("teacher_id") + "." + extension;
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded);
        ex.getResponseHeaders().set("Content-Length", String.valueOf(size));
        ex.getResponseHeaders().set("Cache-Control", "private, no-store");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, size);
        try (InputStream in = Files.newInputStream(file); OutputStream out = ex.getResponseBody()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) if (read > 0) out.write(buffer, 0, read);
        }
    }

    private static void recommend(HttpExchange ex) throws Exception {
        requireJson(ex);
        Map<String, Object> body = readJsonBody(ex);
        long demandId = body.containsKey("demand_id") ? integer(body.get("demand_id"), "需求编号", 0, Long.MAX_VALUE) : 0;
        String manual = cleanText(body.containsKey("requirement") ? value(body, "requirement") : value(body, "requirement_text"));
        if (manual.length() > MAX_REQUIREMENT_CHARS)
            throw new TalentException(413, "客户需求不能超过" + MAX_REQUIREMENT_CHARS + "个字符");
        Object rawTopK = body.containsKey("max_results") ? body.get("max_results") : body.get("top_k");
        int topK = rawTopK != null ? (int) integer(rawTopK, "推荐数量（至少3位）", 3, 20) : 3;
        double explicitBudget = body.containsKey("max_fee_rate") ? finiteNumber(body.get("max_fee_rate"), "最高课酬") : 0;
        if (explicitBudget < 0) throw new TalentException(400, "最高课酬不能为负数");
        boolean hardBudget = truthy(body.get("hard_budget"));

        Map<String, Object> demand = null;
        List<Map<String, Object>> teachers;
        synchronized (Api.MUTATION_LOCK) {
            if (demandId > 0) {
                demand = Db.one("SELECT id,title,unit,hours,content,teacher_req,expect_date,remark,training_province,training_city,training_mode,training_period FROM demands WHERE id=?", demandId);
                if (demand == null) throw new TalentException(404, "培训需求不存在或已被删除");
            }
            teachers = Db.query("SELECT t.id,t.name,t.org,t.title,t.field,t.fee_rate,t.intro,t.base_province,t.base_city," +
                    "r.id resume_id,r.profile_json,r.manual_profile,r.profile_source,r.parse_status,r.extracted_text," +
                    "(SELECT AVG(e.score) FROM teacher_evals e WHERE e.teacher_id=t.id) eval_avg," +
                    "(SELECT COUNT(*) FROM teacher_evals e WHERE e.teacher_id=t.id) eval_count," +
                    "(SELECT COUNT(*) FROM dispatches d WHERE d.teacher_id=t.id AND d.status='已完成') delivered_sessions," +
                    "(SELECT COALESCE(SUM(d.hours),0) FROM dispatches d WHERE d.teacher_id=t.id AND d.status='已完成') delivered_hours " +
                    "FROM teachers t LEFT JOIN teacher_resumes r ON r.id=(SELECT MAX(r2.id) FROM teacher_resumes r2 WHERE r2.teacher_id=t.id AND r2.is_current=TRUE) " +
                    "WHERE t.status='在库' ORDER BY t.id");
        }

        String source = requirementSource(demand, manual);
        if (source.trim().isEmpty()) throw new TalentException(400, "请填写客户需求，或选择已有培训需求");
        Requirement requirement = Requirement.from(source, demand, explicitBudget);
        DispatchPreference logistics = DispatchPreference.from(body, demand);
        Set<Long> conflicts = scheduleConflicts(requirement.date);
        String arrivalDate = requirement.date.isEmpty() ? "" : LocalDate.parse(requirement.date).minusDays(1).toString();
        Set<Long> arrivalConflicts = scheduleConflicts(arrivalDate);

        List<Map<String, Object>> candidates = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (Map<String, Object> teacher : teachers) {
            long teacherId = asLong(teacher.get("id"));
            if (conflicts.contains(teacherId)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("teacher_id", teacherId);
                item.put("name", teacher.get("name"));
                item.put("reason", requirement.date + " 已有未拒绝的授课安排");
                excluded.add(item);
                continue;
            }
            Map<String, Object> profile = profileForMatching(teacher);
            Match match = scoreTeacher(requirement, teacher, profile);
            if (!match.relevant) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("teacher_id", teacherId);
                item.put("name", teacher.get("name"));
                item.put("reason", "现有专业档案中没有足够的需求相关证据");
                excluded.add(item);
                continue;
            }
            if (hardBudget && requirement.maxFeeRate > 0 &&
                    (number(teacher, "fee_rate") <= 0 || number(teacher, "fee_rate") > requirement.maxFeeRate)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("teacher_id", teacherId);
                item.put("name", teacher.get("name"));
                item.put("reason", number(teacher, "fee_rate") <= 0 ? "课酬尚未确认，无法核实硬性预算" : "课酬标准超过硬性预算上限");
                excluded.add(item);
                continue;
            }
            Map<String, Object> candidate = match.toMap(teacher, profile);
            Map<String, Object> dispatchFit = logistics.describe(teacher, requirement.date);
            if (Boolean.TRUE.equals(dispatchFit.get("arrival_day_before")) && arrivalConflicts.contains(teacherId)) {
                // A prior-day course may still allow evening travel. Flag, never claim impossible.
                List<Object> notes = new ArrayList<>((List<?>) dispatchFit.get("notes"));
                notes.add("提前到达日 " + arrivalDate + " 已有系统授课记录，须核实结束时间与交通衔接");
                dispatchFit.put("notes", notes);
                dispatchFit.put("arrival_day_conflict", true);
            }
            candidate.put("dispatch_fit", dispatchFit);
            candidate.put("base_province", teacher.get("base_province"));
            candidate.put("base_city", teacher.get("base_city"));
            candidates.add(candidate);
        }
        candidates.sort((left, right) -> compareCandidates(left, right, logistics.localPreferenceActive()));
        for (int i = 0; i < candidates.size(); i++) candidates.get(i).put("rank", i + 1);
        int eligibleCount = candidates.size();
        if (candidates.size() > topK) candidates = new ArrayList<>(candidates.subList(0, topK));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("demand_id", demandId > 0 ? demandId : null);
        Map<String, Object> analysis = requirement.toMap();
        analysis.put("summary", requirement.summary());
        analysis.put("dispatch_preferences", logistics.toMap());
        analysis.put("needs_manual_review", Arrays.asList("所在地、出差范围、授课形式及复杂或否定条件请人工核实", "仅明确填写的每课时上限参与预算匹配；需求正文中的总预算不换算为课酬"));
        data.put("analysis", analysis);
        data.put("recognized_requirement", analysis);
        data.put("total_in_library", teachers.size());
        data.put("minimum_required", 3);
        data.put("eligible_count", eligibleCount);
        data.put("returned_count", candidates.size());
        data.put("shortfall", Math.max(0, 3 - candidates.size()));
        data.put("residence_pending_count", candidates.stream().filter(item -> !Boolean.TRUE.equals(((Map<?, ?>) item.get("dispatch_fit")).get("residence_complete"))).count());
        data.put("stage", "投标前师资推荐");
        data.put("recommendations", candidates);
        data.put("results", candidates);
        data.put("excluded", excluded);
        data.put("algorithm_version", RECOMMENDATION_VERSION);
        data.put("notice", candidates.isEmpty()
                ? "当前没有找到足够相关的讲师。可补充课程主题、具体案例或校准专业画像后再试；请勿把空结果理解为已核实无人具备能力。"
                : "这是基于档案文字与专业标签的本地规则匹配，分数不是胜任概率。地点、授课形式及复杂条件需人工核实，最终人选由运营人员确认。");
        sendOk(ex, data);
    }

    static int compareCandidates(Map<String, Object> left, Map<String, Object> right, boolean localPreferenceActive) {
            if (localPreferenceActive) {
                int byBand = Integer.compare(Math.min(9, (int) (number(right, "professional_score") / 10)), Math.min(9, (int) (number(left, "professional_score") / 10)));
                if (byBand != 0) return byBand;
                boolean leftLocal = Boolean.TRUE.equals(((Map<?, ?>) left.get("dispatch_fit")).get("local_priority"));
                boolean rightLocal = Boolean.TRUE.equals(((Map<?, ?>) right.get("dispatch_fit")).get("local_priority"));
                if (leftLocal != rightLocal) return leftLocal ? -1 : 1;
            }
            int byScore = Double.compare(number(right, "score"), number(left, "score"));
            return byScore != 0 ? byScore : Long.compare(asLong(left.get("teacher_id")), asLong(right.get("teacher_id")));
    }

    static Set<Long> scheduleConflicts(String date) throws Exception {
        Set<Long> ids = new LinkedHashSet<>();
        if (date == null || date.isEmpty()) return ids;
        List<Map<String, Object>> rows;
        synchronized (Api.MUTATION_LOCK) {
            rows = Db.query("SELECT teacher_id,teach_date FROM dispatches WHERE " +
                    "status IN ('待发送','已发送','已确认','已完成')");
        }
        // Do not rewrite history: compare valid legacy non-padded/whitespace dates in memory.
        // An unparseable date is not proof of availability; the UI always requires confirmation.
        for (Map<String, Object> row : rows) {
            try { if (date.equals(ScheduleDates.canonical(row.get("teach_date")))) ids.add(asLong(row.get("teacher_id"))); }
            catch (IllegalArgumentException ignored) { /* Invalid history remains for manual correction. */ }
        }
        return ids;
    }

    private static String requirementSource(Map<String, Object> demand, String manual) {
        StringBuilder text = new StringBuilder();
        if (demand != null) {
            appendLabeled(text, "项目", value(demand, "title"));
            appendLabeled(text, "客户单位", value(demand, "unit"));
            appendLabeled(text, "培训内容", value(demand, "content"));
            appendLabeled(text, "师资要求", value(demand, "teacher_req"));
            appendLabeled(text, "备注", value(demand, "remark"));
        }
        appendLabeled(text, "补充要求", manual);
        return text.toString();
    }

    private static void appendLabeled(StringBuilder target, String label, String value) {
        if (value != null && !value.trim().isEmpty()) target.append(label).append('：').append(value.trim()).append('\n');
    }

    private static Map<String, Object> profileForMatching(Map<String, Object> teacher) {
        Object parsed = parseProfile(value(teacher, "profile_json"));
        Map<String, Object> profile;
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) parsed;
            profile = new LinkedHashMap<>(map);
            profile.put("has_resume_profile", !value(teacher, "extracted_text").isEmpty());
        } else {
            profile = autoProfile(value(teacher, "field") + "\n" + value(teacher, "intro") + "\n" +
                    value(teacher, "title") + "\n" + value(teacher, "org"), teacher);
            profile.put("source", "basic-record-fallback");
            profile.put("has_resume_profile", false);
        }
        String manual = value(teacher, "manual_profile");
        if (!manual.isEmpty()) {
            profile.put("source", "manual");
            profile.put("has_resume_profile", true);
            profile.put("_matching_text", manual);
        } else {
            mergeDetected(profile, value(teacher, "field") + "\n" + value(teacher, "intro") + "\n" +
                    value(teacher, "title") + "\n" + value(teacher, "org"));
            profile.put("_matching_text", value(teacher, "extracted_text") + "\n" + value(teacher, "field") + "\n" + value(teacher, "intro"));
        }
        return profile;
    }

    /** 人工校准的专业范围是有效画像；重解析只更新原文声明，不重新并入被删掉的标签。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> effectiveProfile(String extracted, Map<String, Object> teacher,
                                                        String manual, String previousJson) {
        Map<String, Object> profile;
        Object previous = parseProfile(previousJson);
        if (!manual.isEmpty() && previous instanceof Map && "manual".equals(value((Map<String, Object>) previous, "source"))) {
            profile = new LinkedHashMap<>((Map<String, Object>) previous);
        } else {
            profile = autoProfile(manual.isEmpty() ? extracted : manual, teacher);
        }
        if (!manual.isEmpty()) {
            profile.put("source", "manual");
            profile.put("summary", manual.substring(0, Math.min(1000, manual.length())));
            profile.put("manual_claims", extractResumeClaims(manual));
        }
        profile.put("resume_claims", extractResumeClaims(extracted));
        return profile;
    }

    private static Match scoreTeacher(Requirement requirement, Map<String, Object> teacher,
                                      Map<String, Object> profile) {
        Match match = new Match();
        Set<String> profileTopics = stringSet(profile.get("tags"));
        Set<String> profileIndustries = stringSet(profile.get("industries"));
        Set<String> profileAudiences = stringSet(profile.get("audiences"));
        Set<String> profileCredentials = stringSet(profile.get("credentials"));
        String searchable = String.join(" ", profileTopics) + " " + String.join(" ", profileIndustries) + " " +
                String.join(" ", profileAudiences) + " " + String.join(" ", profileCredentials) + " " +
                String.join(" ", stringSet(profile.get("courses"))) + " " +
                String.join(" ", stringSet(profile.get("service_cases"))) + " " + value(profile, "_matching_text");
        Set<String> matchedTopics = intersection(requirement.topics, profileTopics);
        Set<String> teacherKeywords = keywords(searchable, MAX_EXTRACTED_CHARS);
        Set<String> matchedKeywords = intersection(requirement.keywords, teacherKeywords);
        match.relevant = !requirement.topics.isEmpty() ? !matchedTopics.isEmpty() : matchedKeywords.size() >= 2;
        match.evidenceStrength = !match.relevant ? "insufficient" :
                (!matchedTopics.isEmpty() && matchedKeywords.size() >= 3 ? "supported" : "limited");
        if (match.relevant && !truthy(profile.get("has_resume_profile"))) match.evidenceStrength = "limited";
        profile.put("evidence", matchingEvidence(value(profile, "_matching_text"), requirement,
                "manual".equals(value(profile, "source")) ? "manual_profile" :
                        value(teacher, "extracted_text").isEmpty() ? "basic_record" : "resume_statement"));

        match.dimension("topics", "主题能力", requirement.topics, profileTopics, 40);
        match.dimension("industries", "行业经验", requirement.industries, profileIndustries, 15);
        match.dimension("audiences", "授课对象", requirement.audiences, profileAudiences, 10);
        match.dimension("credentials", "资历要求", requirement.credentials, profileCredentials, 10);

        if (!requirement.keywords.isEmpty()) {
            Set<String> matched = matchedKeywords;
            double earned = 15.0 * matched.size() / Math.max(1, requirement.keywords.size());
            match.addComponent("keywords", "文本关键词", earned, 15, matched,
                    difference(requirement.keywords, teacherKeywords));
            if (match.relevant && !matched.isEmpty()) match.reasons.add("专业档案中有与本次需求对应的文字，请结合下方证据核实具体课程和案例");
        }

        match.professionalScore = match.possible <= 0 ? 0 : round(match.earned / match.possible * 100, 1);
        double avg = number(teacher, "eval_avg");
        long evalCount = asLong(teacher.get("eval_count"));
        long deliveredSessions = asLong(teacher.get("delivered_sessions"));
        double deliveredHours = number(teacher, "delivered_hours");
        double evaluationPart = evalCount > 0 ? Math.max(0, Math.min(7, avg / 5.0 * 7)) : 0;
        double deliveryPart = Math.min(3, deliveredSessions * 0.6);
        double performance = evaluationPart + deliveryPart;
        // Missing history is unknown, not a fabricated neutral performance score.
        double availablePerformanceWeight = (evalCount > 0 ? 7 : 0) + (deliveredSessions > 0 ? 3 : 0);
        if (availablePerformanceWeight > 0)
            match.addSimpleComponent("performance", "历史履约", performance, availablePerformanceWeight);
        match.performance.put("average_evaluation", evalCount > 0 ? round(avg, 2) : null);
        match.performance.put("evaluation_count", evalCount);
        match.performance.put("completed_sessions", deliveredSessions);
        match.performance.put("completed_hours", round(deliveredHours, 1));
        match.performance.put("component_score", round(performance, 1));
        if (evalCount > 0) match.reasons.add("历史授课评价 " + round(avg, 2) + "/5（" + evalCount + " 条）");
        else match.gaps.add("暂无已录入的授课评价，履约表现需人工核实");

        double feeRate = number(teacher, "fee_rate");
        if (requirement.maxFeeRate > 0) {
            double budgetScore = feeRate <= 0 ? 0 : feeRate <= requirement.maxFeeRate ? 5 :
                    Math.max(0, 5 * (1 - (feeRate - requirement.maxFeeRate) / requirement.maxFeeRate));
            match.addSimpleComponent("budget", "课酬预算", budgetScore, 5);
            if (feeRate <= 0) match.gaps.add("课酬尚未录入或待确认，不能据此判断预算达标");
            else if (feeRate > requirement.maxFeeRate)
                match.gaps.add("课酬标准 ¥" + round(feeRate, 0) + "/课时，高于预算上限 ¥" + round(requirement.maxFeeRate, 0));
            else match.reasons.add("课酬标准在预算上限内");
        }
        if (!truthy(profile.get("has_resume_profile")))
            match.gaps.add("尚无已解析简历，当前仅依据基础师资档案匹配");
        match.finish();
        return match;
    }

    private static Map<String, Object> autoProfile(String extractedText, Map<String, Object> teacher) {
        String text = redact(cleanText(extractedText));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("schema_version", 1);
        profile.put("source", "local_rules");
        profile.put("summary", profileSummary(teacher, text));
        profile.put("tags", new ArrayList<>(detect(text, TOPICS)));
        profile.put("industries", new ArrayList<>(detect(text, INDUSTRIES)));
        profile.put("audiences", new ArrayList<>(detect(text, AUDIENCES)));
        profile.put("credentials", new ArrayList<>(detect(text, CREDENTIALS)));
        profile.put("delivery_modes", new ArrayList<>(detect(text, DELIVERY_MODES)));
        profile.put("courses", extractCourses(text));
        profile.put("resume_claims", extractResumeClaims(text));
        profile.put("service_cases", extractServiceCases(text));
        profile.put("evidence", evidenceSnippets(text));
        profile.put("profile_version", PROFILE_VERSION);
        return profile;
    }

    private static void mergeDetected(Map<String, Object> profile, String text) {
        mergeList(profile, "tags", detect(text, TOPICS));
        mergeList(profile, "industries", detect(text, INDUSTRIES));
        mergeList(profile, "audiences", detect(text, AUDIENCES));
        mergeList(profile, "credentials", detect(text, CREDENTIALS));
        mergeList(profile, "delivery_modes", detect(text, DELIVERY_MODES));
    }

    private static Map<String, Object> validatedManualProfile(Map<String, Object> input, Map<String, Object> teacher)
            throws TalentException {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("schema_version", 1);
        profile.put("source", "manual");
        String summary = cleanText(value(input, "summary"));
        if (summary.length() > 1000) throw new TalentException(400, "画像摘要不能超过1000个字符");
        if (summary.isEmpty()) summary = profileSummary(teacher, "");
        profile.put("summary", summary);
        profile.put("tags", validatedStringList(input.get("tags"), "专业标签", 30, 60));
        profile.put("industries", validatedStringList(input.get("industries"), "行业经验", 30, 60));
        profile.put("audiences", validatedStringList(input.get("audiences"), "授课对象", 30, 60));
        profile.put("credentials", validatedStringList(input.get("credentials"), "资历", 30, 80));
        profile.put("delivery_modes", validatedStringList(input.get("delivery_modes"), "授课形式", 20, 60));
        profile.put("courses", validatedStringList(input.get("courses"), "代表课程", 30, 100));
        profile.put("resume_claims", extractResumeClaims(summary));
        profile.put("service_cases", extractServiceCases(summary));
        profile.put("evidence", evidenceSnippets(summary));
        profile.put("profile_version", PROFILE_VERSION);
        return profile;
    }

    private static List<String> validatedStringList(Object value, String label, int maxItems, int maxLength)
            throws TalentException {
        if (value == null) return new ArrayList<>();
        if (!(value instanceof List)) throw new TalentException(400, label + "必须是数组");
        List<?> raw = (List<?>) value;
        if (raw.size() > maxItems) throw new TalentException(400, label + "最多" + maxItems + "项");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object item : raw) {
            String clean = cleanLine(item == null ? "" : String.valueOf(item));
            if (clean.isEmpty()) continue;
            if (clean.length() > maxLength) throw new TalentException(400, label + "单项不能超过" + maxLength + "个字符");
            result.add(clean);
        }
        return new ArrayList<>(result);
    }

    private static String profileSummary(Map<String, Object> teacher, String text) {
        List<String> parts = new ArrayList<>();
        String name = value(teacher, "name"), title = value(teacher, "title"), org = value(teacher, "org");
        if (!name.isEmpty()) parts.add(name);
        if (!title.isEmpty()) parts.add(title);
        if (!org.isEmpty()) parts.add(org);
        Set<String> topics = detect(text + " " + value(teacher, "field") + " " + value(teacher, "intro"), TOPICS);
        if (!topics.isEmpty()) parts.add("擅长" + String.join("、", topics));
        return parts.isEmpty() ? "待管理员补充讲师画像" : String.join(" · ", parts);
    }

    private static List<String> extractCourses(String text) {
        text = positiveText(text);
        LinkedHashSet<String> courses = new LinkedHashSet<>();
        Matcher matcher = COURSE_PATTERN.matcher(text);
        while (matcher.find() && courses.size() < 20) {
            String course = cleanLine(matcher.group(1));
            if (!course.isEmpty()) courses.add(course);
        }
        Matcher headed = Pattern.compile("(?:主讲课程|精品课程|代表课程|课程|主讲)[：:]([^\\r\\n。；;]{2,180})").matcher(text);
        while (headed.find() && courses.size() < 20) {
            for (String item : headed.group(1).split("[、，,]+")) {
                String course = cleanLine(item);
                if (course.length() >= 2 && course.length() <= 100) courses.add(course);
                if (courses.size() >= 20) break;
            }
        }
        return new ArrayList<>(courses);
    }

    /** Values here remain explicitly unverified resume statements and never feed system metrics. */
    private static Map<String, Object> extractResumeClaims(String source) {
        String text = redact(cleanText(source));
        Map<String, Object> claims = new LinkedHashMap<>();
        String hours = firstClaim(CLAIM_HOURS, text);
        String sessions = firstClaim(CLAIM_SESSIONS, text);
        String satisfaction = firstClaim(CLAIM_SATISFACTION, text);
        if (satisfaction.isEmpty()) satisfaction = firstClaim(CLAIM_SATISFACTION_PREFIX, text);
        String years = firstClaim(CLAIM_YEARS, text);
        if (!hours.isEmpty()) claims.put("claimed_training_hours", hours);
        if (!sessions.isEmpty()) claims.put("claimed_sessions", sessions);
        if (!satisfaction.isEmpty()) claims.put("claimed_satisfaction_percent", satisfaction);
        if (!years.isEmpty()) claims.put("claimed_experience_years", years);
        claims.put("verification", "unverified_resume_statement");
        return claims;
    }

    private static String firstClaim(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? cleanLine(matcher.group(1)) : "";
    }

    private static List<String> extractServiceCases(String source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String text = redact(cleanText(positiveText(source)));
        for (String part : text.split("[\\r\\n。；;]+")) {
            String normalized = normalizeForMatch(part);
            if (!(normalized.contains("案例") || normalized.contains("客户") || normalized.contains("服务过") ||
                    normalized.contains("服务于") || normalized.contains("分行") || normalized.contains("支行") ||
                    normalized.contains("client") || normalized.contains("served"))) continue;
            String snippet = cleanLine(part);
            if (snippet.length() > 140) snippet = snippet.substring(0, 140) + "…";
            if (!snippet.isEmpty()) result.add(snippet);
            if (result.size() >= 5) break;
        }
        return new ArrayList<>(result);
    }

    private static List<Map<String, Object>> evidenceSnippets(String source) {
        List<Map<String, Object>> result = new ArrayList<>();
        String text = redact(cleanText(source));
        for (String part : text.split("[\\r\\n。；;]+")) {
            String snippet = cleanLine(part);
            if (snippet.length() < 8) continue;
            if (snippet.length() > 180) snippet = snippet.substring(0, 180) + "…";
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("text", snippet);
            evidence.put("source", "resume_statement");
            result.add(evidence);
            if (result.size() >= 3) break;
        }
        return result;
    }

    /** 展示与本次需求相交的原文片段；只返回三个脱敏短片段，私有全文不进入响应。 */
    private static List<Map<String, Object>> matchingEvidence(String source, Requirement requirement, String kind) {
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (String part : redact(cleanText(source)).split("[\\r\\n。；;]+")) {
            String sentence = cleanLine(part);
            // Large PPTX text runs may contain several paragraphs without punctuation.
            for (int offset = 0; offset < sentence.length(); offset += 160) {
                String snippet = sentence.substring(offset, Math.min(sentence.length(), offset + 180));
                if (snippet.length() < 4) continue;
                int hits = intersection(requirement.keywords, keywords(snippet)).size();
                hits += 3 * intersection(requirement.topics, detect(snippet, TOPICS)).size();
                if (hits == 0) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("text", snippet);
                item.put("source", kind);
                item.put("_hits", hits);
                ranked.add(item);
            }
        }
        ranked.sort((a, b) -> Double.compare(number(b, "_hits"), number(a, "_hits")));
        List<Map<String, Object>> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> item : ranked) {
            item.remove("_hits");
            if (seen.add(value(item, "text"))) selected.add(item);
            if (selected.size() == 3) break;
        }
        return selected;
    }

    private static void enqueue(long id) {
        if (id <= 0 || !SCHEDULED.add(id)) return;
        try {
            PARSER.execute(() -> {
                try { parseResume(id); }
                finally {
                    SCHEDULED.remove(id);
                    drainQueued();
                }
            });
        } catch (RejectedExecutionException e) {
            SCHEDULED.remove(id);
            // 数据库状态仍是 queued；已有任务结束时会继续拉取。
        }
    }

    private static void drainQueued() {
        try {
            int free = Math.max(0, 34 - SCHEDULED.size());
            if (free == 0) return;
            List<Map<String, Object>> rows;
            synchronized (Api.MUTATION_LOCK) {
                rows = Db.query("SELECT id FROM teacher_resumes WHERE parse_status='queued' ORDER BY created_at,id LIMIT " + free);
            }
            for (Map<String, Object> row : rows) enqueue(asLong(row.get("id")));
        } catch (Exception e) {
            System.err.println("讲师简历队列读取失败: " + e.getClass().getSimpleName());
        }
    }

    private static void parseResume(long id) {
        Path output = null;
        Map<String, Object> row = null;
        try {
            synchronized (Api.MUTATION_LOCK) {
                row = Db.one("SELECT r.*,t.name,t.org,t.title,t.field,t.intro FROM teacher_resumes r " +
                        "LEFT JOIN teachers t ON t.id=r.teacher_id WHERE r.id=?", id);
                if (row == null || !"queued".equals(value(row, "parse_status"))) return;
                Db.exec("UPDATE teacher_resumes SET parse_status='processing',parse_error=NULL," +
                        "updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            }
            Path root = resumeRoot();
            Path input = safeStoragePath(root, value(row, "storage_name"));
            if (!Files.isRegularFile(input)) throw new ParseFailure("简历原件不可用，请重新上传");
            output = Files.createTempFile(root, ".resume-text-", ".tmp");
            setOwnerOnlyFile(output);
            ExtractedPdf extracted;
            try {
                EXTRACTION_SLOTS.acquire();
                try { extracted = runExtractor(input, output, extensionOf(value(row, "storage_name"))); }
                finally { EXTRACTION_SLOTS.release(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ParseFailure("简历解析任务已中断，请重试");
            }
            String safeText = redact(extracted.text);
            String manualProfile = value(row, "manual_profile");
            Map<String, Object> profile = effectiveProfile(safeText, row, manualProfile, value(row, "profile_json"));
            String profileJson = Json.write(profile);
            synchronized (Api.MUTATION_LOCK) {
                Db.transaction(() -> {
                    Map<String, Object> current = Db.one("SELECT teacher_id,parse_status FROM teacher_resumes WHERE id=?", id);
                    if (current == null || !"processing".equals(value(current, "parse_status"))) return null;
                    Db.exec("UPDATE teacher_resumes SET page_count=?,extracted_text=?,profile_json=?," +
                                    "profile_source=?,parse_status='ready',parse_error=NULL,is_current=TRUE," +
                                    "parsed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                            extracted.pages, safeText, profileJson,
                            manualProfile.isEmpty() ? "auto_local" : "manual", id);
                    return null;
                });
            }
        } catch (NeedsOcr e) {
            markNeedsOcr(id, e.pages, "未提取到足够文字，可能是扫描版或图片型简历；可人工维护专业画像后参与推荐。");
        } catch (ParseFailure e) {
            markParseFailure(id, "failed", e.getMessage());
        } catch (Exception e) {
            markParseFailure(id, "failed", "简历解析失败，请检查文件后重试");
            System.err.println("讲师简历解析失败: resume_id=" + id + " " + e.getClass().getSimpleName());
        } finally {
            if (output != null) try { Files.deleteIfExists(output); } catch (IOException ignored) {}
        }
    }

    private static void markParseFailure(long id, String status, String message) {
        try {
            synchronized (Api.MUTATION_LOCK) {
                Db.exec("UPDATE teacher_resumes SET parse_status=?,parse_error=?,updated_at=CURRENT_TIMESTAMP " +
                        "WHERE id=? AND parse_status='processing'", status, cleanError(message), id);
            }
        } catch (Exception ignored) {}
    }

    private static void markNeedsOcr(long id, int pages, String message) {
        try {
            synchronized (Api.MUTATION_LOCK) {
                Db.exec("UPDATE teacher_resumes SET page_count=?,parse_status='needs_ocr',parse_error=?," +
                                "updated_at=CURRENT_TIMESTAMP WHERE id=? AND parse_status='processing'",
                        Math.max(0, pages), cleanError(message), id);
            }
        } catch (Exception ignored) {}
    }

    private static ExtractedPdf runExtractor(Path input, Path output, String extension) throws Exception {
        boolean pptx = "pptx".equals(extension);
        if (!(pptx || "pdf".equals(extension))) throw new ParseFailure("简历文件类型不受支持");
        Path java = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        List<String> command = Arrays.asList(
                java.toString(), "-Xms16m", "-Xmx128m", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true",
                "-cp", System.getProperty("java.class.path"),
                pptx ? PptxResumeExtractor.class.getName() : PdfResumeExtractor.class.getName(),
                input.toString(), output.toString(), String.valueOf(pptx ? MAX_PPTX_PAGES : MAX_PDF_PAGES),
                String.valueOf(MAX_EXTRACTED_CHARS));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try { process.getOutputStream().close(); } catch (IOException ignored) {}
        if (!process.waitFor(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            throw new ParseFailure((pptx ? "PPTX" : "PDF") + " 解析超过12秒，已安全终止；请压缩文件后重试");
        }
        int code = process.exitValue();
        int noText = pptx ? PptxResumeExtractor.EXIT_NO_TEXT : PdfResumeExtractor.EXIT_NO_TEXT;
        int tooMany = pptx ? PptxResumeExtractor.EXIT_TOO_MANY_SLIDES : PdfResumeExtractor.EXIT_TOO_MANY_PAGES;
        int textLimit = pptx ? PptxResumeExtractor.EXIT_TEXT_LIMIT : PdfResumeExtractor.EXIT_TEXT_LIMIT;
        if (code == noText) throw new NeedsOcr(readExtractedPageCount(output));
        if (!pptx && code == PdfResumeExtractor.EXIT_ENCRYPTED)
            throw new ParseFailure("不支持加密 PDF，请解除密码后重新上传");
        if (pptx && code == PptxResumeExtractor.EXIT_UNSAFE_PACKAGE)
            throw new ParseFailure("PPTX 包含不安全的路径或 XML 声明，已拒绝上传");
        if (pptx && code == PptxResumeExtractor.EXIT_XML_LIMIT)
            throw new ParseFailure("PPTX 幻灯片 XML 超过安全限制");
        if (code == tooMany)
            throw new ParseFailure("简历不能超过" + (pptx ? MAX_PPTX_PAGES : MAX_PDF_PAGES) + (pptx ? "张幻灯片" : "页"));
        if (code == textLimit) throw new ParseFailure("简历提取文本不能超过" + MAX_EXTRACTED_CHARS + "个字符");
        if (code != 0) throw new ParseFailure((pptx ? "PPTX" : "PDF") + " 结构无法解析，请重新导出后上传");
        if (!Files.isRegularFile(output) || Files.size(output) == 0 || Files.size(output) > MAX_EXTRACTED_CHARS * 4L + 128)
            throw new ParseFailure("简历提取结果不完整，请重试");
        String payload = Files.readString(output, StandardCharsets.UTF_8);
        int newline = payload.indexOf('\n');
        if (newline < 1 || !payload.startsWith("YANXU_PAGES=")) throw new ParseFailure("简历提取结果格式不正确");
        int pages;
        try { pages = Integer.parseInt(payload.substring("YANXU_PAGES=".length(), newline)); }
        catch (NumberFormatException e) { throw new ParseFailure("简历页数结果不正确"); }
        String text = cleanText(payload.substring(newline + 1));
        if (pages < 1 || pages > (pptx ? MAX_PPTX_PAGES : MAX_PDF_PAGES) || text.length() > MAX_EXTRACTED_CHARS)
            throw new ParseFailure("简历提取结果超过安全限制");
        return new ExtractedPdf(pages, text);
    }

    private static int readExtractedPageCount(Path output) {
        try {
            if (!Files.isRegularFile(output) || Files.size(output) > 128) return 0;
            String payload = Files.readString(output, StandardCharsets.UTF_8);
            int newline = payload.indexOf('\n');
            String raw = newline < 0 ? payload : payload.substring(0, newline);
            if (!raw.startsWith("YANXU_PAGES=")) return 0;
            return Integer.parseInt(raw.substring("YANXU_PAGES=".length()).trim());
        } catch (Exception ignored) { return 0; }
    }

    private static UploadRequest readUploadRequest(HttpExchange ex) throws Exception {
        Map<String, String> query = Api.query(ex);
        String rawTeacherId = cleanLine(query.get("teacher_id"));
        String encodedName = ex.getRequestHeaders().getFirst("X-Resume-Name");
        String fileName = "";
        if (encodedName != null && !encodedName.trim().isEmpty()) {
            if (encodedName.length() > MAX_META_HEADER_BYTES) throw new TalentException(413, "文件名信息过长");
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(encodedName.trim());
                if (decoded.length > 1024) throw new TalentException(413, "文件名信息过长");
                fileName = new String(decoded, StandardCharsets.UTF_8);
            } catch (TalentException e) {
                throw e;
            } catch (Exception e) {
                throw new TalentException(400, "文件名编码不正确");
            }
        } else {
            // Backward-compatible metadata header used by the first private beta.
            Map<String, Object> meta = readMetaHeader(ex);
            if (rawTeacherId.isEmpty()) rawTeacherId = value(meta, "teacher_id");
            fileName = value(meta, "file_name");
        }
        long teacherId = positiveId(rawTeacherId, "师资编号");
        fileName = safeFileName(fileName);
        if (fileName.isEmpty()) throw new TalentException(400, "文件名不能为空");
        String extension = extensionOf(fileName);
        if (!("pdf".equals(extension) || "pptx".equals(extension)))
            throw new TalentException(415, "讲师简历仅支持 PDF 或 PPTX 文件");
        return new UploadRequest(teacherId, fileName, extension);
    }

    private static Map<String, Object> readMetaHeader(HttpExchange ex) throws Exception {
        String raw = ex.getRequestHeaders().getFirst("X-Teacher-Resume-Meta");
        if (raw == null || raw.trim().isEmpty()) throw new TalentException(400, "缺少简历信息");
        if (raw.length() > MAX_META_HEADER_BYTES) throw new TalentException(413, "简历信息过长");
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(raw.trim());
            if (decoded.length > MAX_META_HEADER_BYTES) throw new TalentException(413, "简历信息过长");
            Object parsed = Json.parse(new String(decoded, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new TalentException(400, "简历信息格式不正确");
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) parsed;
            return map;
        } catch (TalentException e) {
            throw e;
        } catch (Exception e) {
            throw new TalentException(400, "简历信息格式不正确");
        }
    }

    private static void requireUploadContentType(HttpExchange ex, String extension) throws TalentException {
        String raw = ex.getRequestHeaders().getFirst("Content-Type");
        String type = raw == null ? "" : raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        boolean accepted = "application/octet-stream".equals(type) ||
                ("pdf".equals(extension) && "application/pdf".equals(type)) ||
                ("pptx".equals(extension) &&
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(type));
        if (!accepted) throw new TalentException(415, "上传内容类型与 PDF/PPTX 文件扩展名不一致");
    }

    private static UploadResult copyLimited(InputStream source, Path target, long limit, String extension) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = source; OutputStream out = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > limit) throw new TalentException(413, uploadLimitMessage(extension));
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return new UploadResult(total, hex(digest.digest()));
    }

    private static void validateFileHeader(Path file, String extension) throws Exception {
        byte[] header = new byte[8];
        int read;
        try (InputStream in = Files.newInputStream(file)) { read = in.read(header); }
        if ("pdf".equals(extension)) {
            if (read < 5 || header[0] != '%' || header[1] != 'P' || header[2] != 'D' || header[3] != 'F' || header[4] != '-')
                throw new TalentException(415, "文件内容不是有效的 PDF");
            return;
        }
        boolean zip = read >= 4 && header[0] == 'P' && header[1] == 'K' &&
                ((header[2] == 3 && header[3] == 4) || (header[2] == 5 && header[3] == 6) ||
                        (header[2] == 7 && header[3] == 8));
        if (!zip) throw new TalentException(415, "文件内容不是有效的 PPTX");
    }

    private static Path resumeRoot() throws IOException {
        String configured = System.getProperty("data.dir", "data").trim();
        Path data = Paths.get(configured.isEmpty() ? "data" : configured).toAbsolutePath().normalize();
        Path root = data.resolve("teacher-resumes").normalize();
        if (!root.startsWith(data)) throw new IOException("简历目录不安全");
        return root;
    }

    private static Path safeStoragePath(Path root, String storageName) throws IOException {
        if (storageName == null || !storageName.matches("[0-9a-f-]{36}\\.(?:pdf|pptx)")) throw new IOException("简历存储标识不正确");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path path = normalizedRoot.resolve(storageName).normalize();
        if (!path.startsWith(normalizedRoot)) throw new IOException("简历路径不安全");
        return path;
    }

    private static void ensurePrivateDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        try { Files.setPosixFilePermissions(directory, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
        catch (UnsupportedOperationException ignored) {}
    }

    private static void setOwnerOnlyFile(Path file) throws IOException {
        try { Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException ignored) {}
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target); }
    }

    private static String safeFileName(String raw) throws TalentException {
        String name = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKC).replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        if (".".equals(name) || "..".equals(name)) throw new TalentException(400, "文件名不正确");
        return name;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Object parseProfile(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            Object parsed = Json.parse(raw);
            return parsed instanceof Map ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void put(Map<String, List<String>> map, String canonical, String... synonyms) {
        map.put(canonical, Arrays.asList(synonyms));
    }

    private static Set<String> detect(String source, Map<String, List<String>> taxonomy) {
        String positive = positiveText(source);
        String text = normalizeForMatch(positive);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : taxonomy.entrySet()) {
            for (String synonym : entry.getValue()) {
                boolean found;
                if (synonym.matches("[A-Za-z0-9+#. -]+")) {
                    found = Pattern.compile("(?i)(?<![a-z0-9])" + Pattern.quote(synonym) + "(?![a-z0-9])").matcher(positive).find();
                } else if ("教授".equals(synonym)) {
                    found = Pattern.compile("(?<!副)教授").matcher(text).find();
                } else found = text.contains(normalizeForMatch(synonym));
                if (found) {
                    result.add(entry.getKey());
                    break;
                }
            }
        }
        return result;
    }

    private static Set<String> keywords(String source) {
        return keywords(source, 120);
    }

    private static Set<String> keywords(String source, int limit) {
        String text = Normalizer.normalize(positiveText(source), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher ascii = ASCII_WORD.matcher(text);
        while (ascii.find() && result.size() < limit) {
            String word = ascii.group();
            if (!Arrays.asList("the", "and", "for", "with", "yanxu", "training", "teacher", "trainer", "course", "courses", "experience").contains(word)) result.add(word);
        }
        Matcher han = HAN_RUN.matcher(text);
        while (han.find() && result.size() < limit) {
            String run = han.group();
            for (int i = 0; i + 1 < run.length() && result.size() < limit; i++) {
                String pair = run.substring(i, i + 2);
                if (!Arrays.asList("培训", "课程", "老师", "讲师", "要求", "需要", "相关", "经验", "能够", "单位", "项目", "客户", "专业", "领域", "擅长", "补充", "充要", "希望", "具备", "熟悉", "主讲").contains(pair))
                    result.add(pair);
            }
        }
        return result;
    }

    /** 仅忽略明确否定的短分句，不把不确定条件推断成排除讲师的硬约束。 */
    private static String positiveText(String source) {
        String normalized = Normalizer.normalize(source == null ? "" : source, Normalizer.Form.NFKC);
        return normalized.replaceAll("(?:不擅长|不包含|不需要|无需|不涉及|不具备|不是)[^，,。；;\\r\\n]*", " ");
    }

    private static String normalizeForMatch(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private static Set<String> stringSet(Object value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                String clean = cleanLine(item == null ? "" : String.valueOf(item));
                if (!clean.isEmpty()) result.add(clean);
            }
        }
        return result;
    }

    private static void mergeList(Map<String, Object> map, String key, Set<String> extra) {
        Set<String> values = stringSet(map.get(key));
        values.addAll(extra);
        map.put(key, new ArrayList<>(values));
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        LinkedHashSet<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        LinkedHashSet<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static String redact(String text) {
        return text
                .replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", "[邮箱]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[手机号]")
                .replaceAll("(?<!\\d)\\d{17}[0-9Xx](?!\\d)", "[证件号]");
    }

    private static String cleanLine(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String cleanText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String cleanError(String value) {
        String clean = cleanLine(value);
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    private static String value(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static double number(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (Exception e) { return 0; }
    }

    private static double finiteNumber(Object value, String label) throws TalentException {
        try {
            double number = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
            if (!Double.isFinite(number)) throw new NumberFormatException();
            return number;
        } catch (Exception e) {
            throw new TalentException(400, label + "格式不正确");
        }
    }

    private static long integer(Object value, String label, long min, long max) throws TalentException {
        double number = finiteNumber(value, label);
        if (number != Math.rint(number) || number < min || number > max) throw new TalentException(400, label + "格式不正确");
        return (long) number;
    }

    private static long positiveId(Object value, String label) throws TalentException {
        return integer(value, label, 1, Long.MAX_VALUE);
    }

    private static long asLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
        catch (Exception e) { return 0; }
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        String raw = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    private static long contentLength(String raw) throws TalentException {
        if (raw == null || raw.trim().isEmpty()) return -1;
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new TalentException(400, "文件长度不正确");
        }
    }

    private static long uploadLimit(String extension) {
        return "pptx".equals(extension) ? MAX_PPTX_BYTES : MAX_PDF_BYTES;
    }

    private static String uploadLimitMessage(String extension) {
        long megabytes = uploadLimit(extension) / (1024 * 1024);
        return "单份 " + extension.toUpperCase(Locale.ROOT) + " 讲师简历不能超过" + megabytes + " MB";
    }

    private static long positiveConfiguredLong(String key, long fallback) {
        String raw = System.getProperty(key, "").trim();
        if (raw.isEmpty()) return fallback;
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    private static Map<String, Object> limits() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_pdf_pages", MAX_PDF_PAGES);
        limits.put("max_pptx_slides", MAX_PPTX_PAGES);
        limits.put("max_extracted_chars", MAX_EXTRACTED_CHARS);
        limits.put("parse_timeout_seconds", PARSE_TIMEOUT_SECONDS);
        return limits;
    }

    private static void requireJson(HttpExchange ex) throws TalentException {
        String raw = ex.getRequestHeaders().getFirst("Content-Type");
        String type = raw == null ? "" : raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!"application/json".equals(type)) throw new TalentException(415, "该接口只接受 application/json 请求");
    }

    private static Map<String, Object> readJsonBody(HttpExchange ex) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = ex.getRequestBody()) {
            byte[] buffer = new byte[4096];
            int read, total = 0;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_JSON_BYTES) throw new TalentException(413, "请求内容过大");
                output.write(buffer, 0, read);
            }
        }
        try {
            Object parsed = Json.parse(new String(output.toByteArray(), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new TalentException(400, "请求内容必须是 JSON 对象");
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) parsed;
            return map;
        } catch (TalentException e) {
            throw e;
        } catch (Exception e) {
            throw new TalentException(400, "请求内容不是有效的 JSON");
        }
    }

    private static void requireMethod(HttpExchange ex, String... allowed) throws TalentException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        for (String item : allowed) if (item.equals(method)) return;
        ex.getResponseHeaders().set("Allow", String.join(", ", allowed));
        throw new TalentException(405, "请求方法不受支持");
    }

    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("data", data);
        return body;
    }

    private static void sendOk(HttpExchange ex, Object data) throws IOException { sendJson(ex, 200, envelope(data)); }

    private static void sendError(HttpExchange ex, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("msg", message);
        sendJson(ex, status, body);
    }

    private static void sendJson(HttpExchange ex, int status, Object value) throws IOException {
        byte[] body = Json.write(value).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream output = ex.getResponseBody()) { output.write(body); }
    }

    private static final class Requirement {
        final Set<String> topics, industries, audiences, credentials, deliveryModes, keywords;
        final String date;
        final double hours, maxFeeRate;

        private Requirement(Set<String> topics, Set<String> industries, Set<String> audiences,
                            Set<String> credentials, Set<String> deliveryModes, Set<String> keywords,
                            String date, double hours, double maxFeeRate) {
            this.topics = topics;
            this.industries = industries;
            this.audiences = audiences;
            this.credentials = credentials;
            this.deliveryModes = deliveryModes;
            this.keywords = keywords;
            this.date = date;
            this.hours = hours;
            this.maxFeeRate = maxFeeRate;
        }

        static Requirement from(String source, Map<String, Object> demand, double explicitBudget) {
            String date = demand == null ? "" : cleanLine(value(demand, "expect_date"));
            if (!date.isEmpty()) {
                try { date = ScheduleDates.canonical(date); }
                catch (Exception invalidDate) { throw new IllegalArgumentException("已有需求的培训日期无效，请先修正后再推荐"); }
            }
            if (!date.matches("20\\d{2}-\\d{2}-\\d{2}")) {
                Matcher match = DATE_PATTERN.matcher(source);
                if (match.find()) {
                    try { date = LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3))).toString(); }
                    catch (Exception ignored) { date = ""; }
                } else date = "";
            }
            double hours = demand == null ? 0 : number(demand, "hours");
            // Guided briefs use an explicit field, never infer hours from budgets,
            // teacher experience or bare numbers elsewhere in the customer text.
            if (demand == null) {
                Matcher hoursMatch = Pattern.compile("(?m)^\\s*(?:补充要求[：:]\\s*)?(?:预计课时|培训课时|课时)[：:]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:课时|学时)?\\s*$").matcher(source);
                if (hoursMatch.find()) {
                    double parsedHours = Double.parseDouble(hoursMatch.group(1));
                    if (Double.isFinite(parsedHours) && parsedHours > 0) hours = parsedHours;
                }
            }
            return new Requirement(detect(source, TOPICS), detect(source, INDUSTRIES), detect(source, AUDIENCES),
                    detect(source, CREDENTIALS), detect(source, DELIVERY_MODES), keywords(source), date, hours, explicitBudget);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("topics", new ArrayList<>(topics));
            map.put("industries", new ArrayList<>(industries));
            map.put("audiences", new ArrayList<>(audiences));
            map.put("credentials", new ArrayList<>(credentials));
            map.put("delivery_modes", new ArrayList<>(deliveryModes));
            map.put("keywords", new ArrayList<>(keywords).subList(0, Math.min(20, keywords.size())));
            map.put("expected_date", date.isEmpty() ? null : date);
            map.put("hours", hours > 0 ? round(hours, 1) : null);
            map.put("max_fee_rate", maxFeeRate > 0 ? round(maxFeeRate, 2) : null);
            return map;
        }

        String summary() {
            List<String> parts = new ArrayList<>();
            if (!topics.isEmpty()) parts.add("主题：" + String.join("、", topics));
            if (!industries.isEmpty()) parts.add("行业：" + String.join("、", industries));
            if (!audiences.isEmpty()) parts.add("对象：" + String.join("、", audiences));
            if (!credentials.isEmpty()) parts.add("资历：" + String.join("、", credentials));
            if (!date.isEmpty()) parts.add("日期：" + date);
            if (hours > 0) parts.add("课时：" + round(hours, 1));
            return parts.isEmpty() ? "已按客户原始需求进行关键词与讲师档案匹配" : String.join("；", parts);
        }
    }

    private static final class Match {
        double earned, possible, score, professionalScore;
        boolean relevant;
        String evidenceStrength = "insufficient";
        final Map<String, Object> breakdown = new LinkedHashMap<>();
        final Map<String, Object> breakdownDetails = new LinkedHashMap<>();
        final Map<String, Object> performance = new LinkedHashMap<>();
        final List<String> reasons = new ArrayList<>();
        final List<String> gaps = new ArrayList<>();

        void dimension(String key, String label, Set<String> required, Set<String> available, double maximum) {
            if (required.isEmpty()) return;
            Set<String> matched = intersection(required, available);
            Set<String> missing = difference(required, available);
            double points = maximum * matched.size() / Math.max(1, required.size());
            addComponent(key, label, points, maximum, matched, missing);
            if (!matched.isEmpty()) reasons.add(label + "匹配：" + String.join("、", matched));
            if (!missing.isEmpty()) gaps.add(label + "未在画像中确认：" + String.join("、", missing));
        }

        void addComponent(String key, String label, double points, double maximum,
                          Set<String> matched, Set<String> missing) {
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("label", label);
            component.put("score", round(points, 1));
            component.put("max", round(maximum, 1));
            component.put("matched", new ArrayList<>(matched));
            component.put("missing", new ArrayList<>(missing));
            breakdown.put(key, maximum <= 0 ? 0 : round(points / maximum * 100, 1));
            breakdownDetails.put(key, component);
            earned += points;
            possible += maximum;
        }

        void addSimpleComponent(String key, String label, double points, double maximum) {
            addComponent(key, label, points, maximum, Collections.emptySet(), Collections.emptySet());
        }

        void finish() {
            score = possible <= 0 ? 0 : round(Math.max(0, Math.min(100, earned / possible * 100)), 1);
            if (reasons.isEmpty()) reasons.add("现有档案匹配证据较少，建议先补充或复核讲师画像");
        }

        Map<String, Object> toMap(Map<String, Object> teacher, Map<String, Object> profile) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("teacher_id", teacher.get("id"));
            map.put("teacher_name", teacher.get("name"));
            map.put("name", teacher.get("name"));
            map.put("org", teacher.get("org"));
            map.put("organization", teacher.get("org"));
            map.put("title", teacher.get("title"));
            map.put("field", teacher.get("field"));
            map.put("fee_rate", teacher.get("fee_rate"));
            map.put("match_score", score);
            map.put("score", score);
            map.put("professional_score", professionalScore);
            map.put("evidence_strength", evidenceStrength);
            map.put("tags", profile.get("tags"));
            map.put("industries", profile.get("industries"));
            map.put("audiences", profile.get("audiences"));
            map.put("profile_source", profile.get("source"));
            map.put("resume_id", teacher.get("resume_id"));
            map.put("resume_status", teacher.get("parse_status"));
            map.put("reasons", reasons);
            map.put("match_reasons", reasons);
            map.put("gaps", gaps);
            map.put("score_breakdown", breakdown);
            map.put("score_breakdown_details", breakdownDetails);
            Map<String, Object> systemMetrics = new LinkedHashMap<>();
            systemMetrics.put("completed_sessions", performance.get("completed_sessions"));
            systemMetrics.put("completed_hours", performance.get("completed_hours"));
            systemMetrics.put("evaluation_count", performance.get("evaluation_count"));
            systemMetrics.put("evaluation_score", performance.get("average_evaluation") == null ? 0 : performance.get("average_evaluation"));
            map.put("system_metrics", systemMetrics);
            map.put("resume_claims", profile.get("resume_claims") instanceof Map ? profile.get("resume_claims") : Collections.emptyMap());
            Object evidence = profile.get("evidence");
            map.put("evidence", evidence instanceof List ? new ArrayList<>(((List<?>) evidence).subList(0, Math.min(3, ((List<?>) evidence).size()))) : Collections.emptyList());
            map.put("performance", performance);
            return map;
        }
    }

    private static final class ExtractedPdf {
        final int pages;
        final String text;
        ExtractedPdf(int pages, String text) { this.pages = pages; this.text = text; }
    }

    private static final class UploadResult {
        final long size;
        final String sha256;
        UploadResult(long size, String sha256) { this.size = size; this.sha256 = sha256; }
    }

    private static final class UploadRequest {
        final long teacherId;
        final String fileName;
        final String extension;
        UploadRequest(long teacherId, String fileName, String extension) {
            this.teacherId = teacherId;
            this.fileName = fileName;
            this.extension = extension;
        }
    }

    private static class ParseFailure extends Exception {
        private static final long serialVersionUID = 1L;
        ParseFailure(String message) { super(message); }
    }

    private static final class NeedsOcr extends ParseFailure {
        private static final long serialVersionUID = 1L;
        final int pages;
        NeedsOcr(int pages) {
            super("需要 OCR");
            this.pages = pages;
        }
    }

    private static final class TalentException extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;
        TalentException(int code, String message) { super(message); this.code = code; }
    }
}
