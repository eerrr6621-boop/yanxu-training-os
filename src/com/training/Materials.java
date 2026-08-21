package com.training;

import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** 公开培训资料中心：系统/业务管理员维护资料包，访客无需登录即可下载已上架文件。 */
public final class Materials {
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 100L * 1024 * 1024;
    private static final long MAX_UPLOAD_BYTES = Math.max(1024, Long.getLong("materials.max.bytes", DEFAULT_MAX_UPLOAD_BYTES));
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final int MAX_META_HEADER_BYTES = 8 * 1024;
    private static final Set<String> STATUSES = new LinkedHashSet<>(Arrays.asList("上架", "下架"));
    private static final Map<String, String> FILE_TYPES = new LinkedHashMap<>();

    static {
        FILE_TYPES.put("pdf", "application/pdf");
        FILE_TYPES.put("doc", "application/msword");
        FILE_TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        FILE_TYPES.put("ppt", "application/vnd.ms-powerpoint");
        FILE_TYPES.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        FILE_TYPES.put("xls", "application/vnd.ms-excel");
        FILE_TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        FILE_TYPES.put("zip", "application/zip");
    }

    private Materials() {}

    public static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if ("/api/materials/public".equals(path)) {
                requireMethod(ex, "GET", "HEAD");
                publicList(ex);
                return;
            }
            if ("/api/materials/download".equals(path)) {
                requireMethod(ex, "GET", "HEAD");
                download(ex);
                return;
            }

            Auth.Session session = Auth.get(Api.token(ex));
            if (session == null) throw new MaterialException(401, "未登录或会话已过期，请重新登录");
            if (!Auth.canWrite(session)) throw new MaterialException(403, "仅系统管理员或业务管理员可维护培训资料");

            if ("/api/materials/manage".equals(path)) {
                requireMethod(ex, "GET", "HEAD");
                manageList(ex);
            } else if ("/api/materials/upload".equals(path)) {
                requireMethod(ex, "POST");
                upload(ex, session);
            } else if ("/api/materials/update".equals(path)) {
                requireMethod(ex, "POST");
                update(ex);
            } else if ("/api/materials/delete".equals(path)) {
                requireMethod(ex, "POST");
                delete(ex);
            } else {
                throw new MaterialException(404, "资料接口不存在");
            }
        } catch (MaterialException e) {
            sendError(ex, e.code, e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage() == null ? "请求参数格式不正确" : e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            sendError(ex, 500, "资料服务暂时无法处理请求，请稍后重试");
        }
    }

    private static void publicList(HttpExchange ex) throws Exception {
        List<Map<String, Object>> rows;
        synchronized (Api.MUTATION_LOCK) {
            rows = Db.query("SELECT id,title,category,summary,version,file_name,content_type,file_size," +
                    "download_count,created_at,updated_at FROM materials WHERE status='上架' ORDER BY created_at DESC,id DESC");
        }
        sendOk(ex, rows);
    }

    private static void manageList(HttpExchange ex) throws Exception {
        List<Map<String, Object>> rows;
        synchronized (Api.MUTATION_LOCK) {
            rows = Db.query("SELECT id,title,category,summary,version,file_name,content_type,file_size,sha256," +
                    "status,download_count,created_at,updated_at FROM materials ORDER BY created_at DESC,id DESC");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("max_upload_bytes", MAX_UPLOAD_BYTES);
        result.put("allowed_extensions", new ArrayList<>(FILE_TYPES.keySet()));
        sendOk(ex, result);
    }

    private static void upload(HttpExchange ex, Auth.Session session) throws Exception {
        String rawMeta = ex.getRequestHeaders().getFirst("X-Material-Meta");
        if (rawMeta == null || rawMeta.trim().isEmpty()) throw new MaterialException(400, "缺少资料信息");
        if (rawMeta.length() > MAX_META_HEADER_BYTES) throw new MaterialException(413, "资料信息过长");

        Map<String, Object> meta;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(rawMeta.trim());
            if (decoded.length > MAX_META_HEADER_BYTES) throw new MaterialException(413, "资料信息过长");
            Object parsed = Json.parse(new String(decoded, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new MaterialException(400, "资料信息格式不正确");
            @SuppressWarnings("unchecked")
            Map<String, Object> parsedMap = (Map<String, Object>) parsed;
            meta = parsedMap;
        } catch (MaterialException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MaterialException(400, "资料信息格式不正确");
        }

        MaterialInput input = validateInput(meta, true);
        long announced = parseContentLength(ex.getRequestHeaders().getFirst("Content-Length"));
        if (announced == 0) throw new MaterialException(400, "请选择需要上传的学习包文件");
        if (announced > MAX_UPLOAD_BYTES) throw new MaterialException(413, uploadLimitMessage());

        Path root = materialRoot();
        Files.createDirectories(root);
        String storageName = UUID.randomUUID().toString() + "." + input.extension;
        Path finalFile = safeStoragePath(root, storageName);
        Path tempFile = Files.createTempFile(root, ".upload-", ".tmp");
        boolean moved = false;
        try {
            UploadResult upload = copyLimited(ex.getRequestBody(), tempFile);
            if (upload.size == 0) throw new MaterialException(400, "上传文件不能为空");
            validateSignature(tempFile, input.extension);
            moveAtomically(tempFile, finalFile);
            moved = true;

            long id;
            try {
                synchronized (Api.MUTATION_LOCK) {
                    id = Db.insert("INSERT INTO materials(title,category,summary,version,file_name,storage_name," +
                                    "content_type,file_size,sha256,status,download_count,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,0,?)",
                            input.title, input.category, input.summary, input.version, input.fileName, storageName,
                            FILE_TYPES.get(input.extension), upload.size, upload.sha256, input.status, session.uid);
                }
            } catch (Exception e) {
                try { Files.deleteIfExists(finalFile); } catch (IOException cleanupError) { e.addSuppressed(cleanupError); }
                throw e;
            }
            sendOk(ex, Collections.singletonMap("id", id));
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            if (!moved) try { Files.deleteIfExists(finalFile); } catch (IOException ignored) {}
        }
    }

    private static void update(HttpExchange ex) throws Exception {
        requireJson(ex);
        Map<String, Object> body = readJsonBody(ex);
        long id = positiveId(body.get("id"));
        MaterialInput input = validateInput(body, false);
        synchronized (Api.MUTATION_LOCK) {
            if (Db.one("SELECT id FROM materials WHERE id=?", id) == null)
                throw new MaterialException(404, "资料不存在或已被删除");
            Db.exec("UPDATE materials SET title=?,category=?,summary=?,version=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    input.title, input.category, input.summary, input.version, input.status, id);
        }
        sendOk(ex, "资料信息已更新");
    }

    private static void delete(HttpExchange ex) throws Exception {
        requireJson(ex);
        Map<String, Object> body = readJsonBody(ex);
        long id = positiveId(body.get("id"));
        Map<String, Object> row;
        synchronized (Api.MUTATION_LOCK) {
            row = Db.one("SELECT id,storage_name FROM materials WHERE id=?", id);
            if (row == null) throw new MaterialException(404, "资料不存在或已被删除");
            Db.exec("DELETE FROM materials WHERE id=?", id);
        }
        String storageName = String.valueOf(row.get("storage_name"));
        try { Files.deleteIfExists(safeStoragePath(materialRoot(), storageName)); }
        catch (IOException e) { System.err.println("资料文件清理失败: " + storageName); }
        sendOk(ex, "资料已删除");
    }

    private static void download(HttpExchange ex) throws Exception {
        long id = positiveId(Api.query(ex).get("id"));
        Map<String, Object> row;
        synchronized (Api.MUTATION_LOCK) {
            row = Db.one("SELECT id,file_name,storage_name,file_size,content_type FROM materials WHERE id=? AND status='上架'", id);
        }
        if (row == null) throw new MaterialException(404, "资料不存在或尚未上架");

        Path file = safeStoragePath(materialRoot(), String.valueOf(row.get("storage_name")));
        if (!Files.isRegularFile(file)) throw new MaterialException(404, "资料文件暂时不可用，请联系管理员");
        long size = Files.size(file);
        String fileName = safeFileName(String.valueOf(row.get("file_name")));
        String extension = extensionOf(fileName);
        String fallback = "yanxu-material-" + id + (extension.isEmpty() ? "" : "." + extension);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name()).replace("+", "%20");

        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded);
        ex.getResponseHeaders().set("Cache-Control", "private, no-store");
        ex.getResponseHeaders().set("Pragma", "no-cache");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");

        ByteRange range = parseRange(ex.getRequestHeaders().getFirst("Range"), size);
        long start = range == null ? 0 : range.start;
        long end = range == null ? size - 1 : range.end;
        long length = size == 0 ? 0 : end - start + 1;
        int status = range == null ? 200 : 206;
        if (range != null) ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
        ex.getResponseHeaders().set("Content-Length", String.valueOf(length));

        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        if (start == 0) {
            synchronized (Api.MUTATION_LOCK) {
                Db.exec("UPDATE materials SET download_count=download_count+1 WHERE id=?", id);
            }
        }
        ex.sendResponseHeaders(status, length);
        try (InputStream in = Files.newInputStream(file); OutputStream out = ex.getResponseBody()) {
            skipFully(in, start);
            copyRange(in, out, length);
        }
    }

    private static MaterialInput validateInput(Map<String, Object> source, boolean fileRequired) throws MaterialException {
        String title = cleanLine(value(source, "title"));
        String category = cleanLine(value(source, "category"));
        String summary = cleanSummary(value(source, "summary"));
        String version = cleanLine(value(source, "version"));
        String status = cleanLine(value(source, "status"));
        if (status.isEmpty()) status = "上架";
        if (title.isEmpty()) throw new MaterialException(400, "请填写学习包名称");
        if (title.length() > 120) throw new MaterialException(400, "学习包名称不能超过120个字符");
        if (category.isEmpty()) category = "综合学习包";
        if (category.length() > 40) throw new MaterialException(400, "资料分类不能超过40个字符");
        if (summary.length() > 500) throw new MaterialException(400, "资料简介不能超过500个字符");
        if (version.length() > 32) throw new MaterialException(400, "版本信息不能超过32个字符");
        if (!STATUSES.contains(status)) throw new MaterialException(400, "资料状态只能是上架或下架");

        String fileName = "";
        String extension = "";
        if (fileRequired) {
            fileName = safeFileName(value(source, "file_name"));
            if (fileName.isEmpty()) throw new MaterialException(400, "请选择需要上传的学习包文件");
            if (fileName.length() > 180) throw new MaterialException(400, "文件名不能超过180个字符");
            extension = extensionOf(fileName);
            if (!FILE_TYPES.containsKey(extension))
                throw new MaterialException(415, "仅支持 PDF、Word、PPT、Excel 与 ZIP 学习包");
        }
        return new MaterialInput(title, category, summary, version, status, fileName, extension);
    }

    private static String safeFileName(String raw) throws MaterialException {
        String normalized = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKC)
                .replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        normalized = normalized.replaceAll("[\\p{Cntrl}]", "").trim();
        if (normalized.equals(".") || normalized.equals("..")) throw new MaterialException(400, "文件名不正确");
        return normalized;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Path materialRoot() throws IOException {
        String configured = System.getProperty("data.dir", "data").trim();
        Path data = Paths.get(configured.isEmpty() ? "data" : configured).toAbsolutePath().normalize();
        Path root = data.resolve("materials").normalize();
        if (!root.startsWith(data)) throw new IOException("资料目录不安全");
        return root;
    }

    private static Path safeStoragePath(Path root, String storageName) throws IOException {
        if (storageName == null || !storageName.matches("[0-9a-f-]{36}\\.[a-z0-9]+"))
            throw new IOException("资料存储标识不正确");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path file = normalizedRoot.resolve(storageName).normalize();
        if (!file.startsWith(normalizedRoot)) throw new IOException("资料路径不安全");
        return file;
    }

    private static UploadResult copyLimited(InputStream source, Path target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = source; OutputStream out = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_UPLOAD_BYTES) throw new MaterialException(413, uploadLimitMessage());
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return new UploadResult(total, hex(digest.digest()));
    }

    private static void validateSignature(Path file, String extension) throws Exception {
        byte[] header = new byte[8];
        int length;
        try (InputStream in = Files.newInputStream(file)) { length = in.read(header); }
        boolean valid;
        if ("pdf".equals(extension)) {
            valid = startsWith(header, length, new byte[]{'%', 'P', 'D', 'F', '-'});
        } else if (Arrays.asList("docx", "pptx", "xlsx", "zip").contains(extension)) {
            valid = startsWith(header, length, new byte[]{'P', 'K', 3, 4}) ||
                    startsWith(header, length, new byte[]{'P', 'K', 5, 6}) ||
                    startsWith(header, length, new byte[]{'P', 'K', 7, 8});
        } else {
            valid = startsWith(header, length, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
        }
        if (!valid) throw new MaterialException(415, "文件内容与扩展名不匹配，请上传原始学习资料文件");
    }

    private static boolean startsWith(byte[] source, int sourceLength, byte[] prefix) {
        if (sourceLength < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (source[i] != prefix[i]) return false;
        return true;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target); }
    }

    private static ByteRange parseRange(String raw, long size) throws MaterialException {
        if (raw == null || raw.trim().isEmpty()) return null;
        if (size <= 0 || !raw.matches("bytes=\\d+-\\d*")) throw new MaterialException(416, "下载范围不受支持");
        String[] values = raw.substring(6).split("-", -1);
        try {
            long start = Long.parseLong(values[0]);
            long end = values[1].isEmpty() ? size - 1 : Long.parseLong(values[1]);
            if (start < 0 || start >= size || end < start) throw new MaterialException(416, "下载范围超出文件大小");
            return new ByteRange(start, Math.min(end, size - 1));
        } catch (NumberFormatException e) {
            throw new MaterialException(416, "下载范围不正确");
        }
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) remaining -= skipped;
            else if (in.read() < 0) throw new EOFException("文件长度发生变化");
            else remaining--;
        }
    }

    private static void copyRange(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException("文件长度发生变化");
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static Map<String, Object> readJsonBody(HttpExchange ex) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) {
            byte[] buffer = new byte[4096];
            int read, total = 0;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_JSON_BYTES) throw new MaterialException(413, "请求内容过大");
                out.write(buffer, 0, read);
            }
        }
        try {
            Object parsed = Json.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new MaterialException(400, "请求内容必须是 JSON 对象");
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) parsed;
            return body;
        } catch (MaterialException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MaterialException(400, "请求内容不是有效的 JSON");
        }
    }

    private static void requireJson(HttpExchange ex) throws MaterialException {
        String raw = ex.getRequestHeaders().getFirst("Content-Type");
        String type = raw == null ? "" : raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!"application/json".equals(type)) throw new MaterialException(415, "该接口只接受 application/json 请求");
    }

    private static void requireMethod(HttpExchange ex, String... allowed) throws MaterialException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        for (String item : allowed) if (item.equals(method)) return;
        ex.getResponseHeaders().set("Allow", String.join(", ", allowed));
        throw new MaterialException(405, "请求方法不受支持");
    }

    private static long positiveId(Object value) throws MaterialException {
        if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            if (!Double.isFinite(numeric) || numeric < 1 || numeric > Long.MAX_VALUE || numeric != Math.rint(numeric))
                throw new MaterialException(400, "资料编号不正确");
            return (long) numeric;
        }
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (!raw.matches("[1-9]\\d*")) throw new MaterialException(400, "资料编号不正确");
        try { return Long.parseLong(raw); }
        catch (NumberFormatException e) { throw new MaterialException(400, "资料编号不正确"); }
    }

    private static long parseContentLength(String raw) throws MaterialException {
        if (raw == null || raw.trim().isEmpty()) return -1;
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new MaterialException(400, "文件长度不正确");
        }
    }

    private static String value(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String cleanLine(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String cleanSummary(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String uploadLimitMessage() {
        return "单个学习包不能超过 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + " MB";
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void sendOk(HttpExchange ex, Object data) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("data", data);
        sendJson(ex, 200, body);
    }

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
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }

    private static final class MaterialInput {
        final String title, category, summary, version, status, fileName, extension;
        MaterialInput(String title, String category, String summary, String version, String status, String fileName, String extension) {
            this.title = title;
            this.category = category;
            this.summary = summary;
            this.version = version;
            this.status = status;
            this.fileName = fileName;
            this.extension = extension;
        }
    }

    private static final class UploadResult {
        final long size;
        final String sha256;
        UploadResult(long size, String sha256) { this.size = size; this.sha256 = sha256; }
    }

    private static final class ByteRange {
        final long start, end;
        ByteRange(long start, long end) { this.start = start; this.end = end; }
    }

    private static final class MaterialException extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;
        MaterialException(int code, String message) { super(message); this.code = code; }
    }
}
