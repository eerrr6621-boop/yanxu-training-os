package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.util.*;

/** Applies the new organization boundary to legacy routes without changing historical workflows. */
final class WorkflowLegacyAccess {
    private static final Set<String> CHILDREN = Set.of("dispatches", "teacher_evals", "questionnaires", "charges", "fees", "costs");
    private static final Set<String> SCOPED = Set.of("demands", "bids", "projects", "dispatches", "teacher_evals", "questionnaires", "charges", "fees", "costs", "q_sends");
    private WorkflowLegacyAccess() {}

    static void check(HttpExchange ex, String path, Auth.Session actor) throws Exception {
        boolean write = "POST".equalsIgnoreCase(ex.getRequestMethod());
        Map<String, Object> body = write ? Api.body(ex) : Map.of();
        if (!write) {
            if (path.equals("/api/projects/check"))
                WorkflowIntegration.requireProjectAccess(id(Api.query(ex).get("id")), actor, false);
            if (path.equals("/api/stats/q"))
                checkRow("questionnaires", id(Api.query(ex).get("id")), actor, false);
            return;
        }
        String tail = path.startsWith("/api/") ? path.substring(5) : "";
        String mod = tail.endsWith("/delete") ? tail.substring(0, tail.length() - 7) : tail;
        long rowId = id(body.get("id"));
        if (mod.equals("projects")) {
            if (rowId > 0) WorkflowIntegration.requireProjectAccess(rowId, actor, true);
        } else if (CHILDREN.contains(mod)) {
            if (rowId > 0) checkRow(mod, rowId, actor, true);
            if (body.containsKey("project_id")) checkProject(mod, id(body.get("project_id")), actor, true);
        } else if (Set.of("projects/start", "projects/complete", "projects/archive").contains(tail)) {
            WorkflowIntegration.requireProjectAccess(rowId, actor, true);
        } else if (Set.of("dispatches/send", "dispatches/confirm", "dispatches/complete").contains(tail)) {
            // Controlled completion is authorized by delivery.verify inside the same mutation transaction.
            // Do not require the unrelated legacy role or demand.write grant before that authority runs.
            if (!tail.equals("dispatches/complete") || !IntegrationDeliveryHost.controlledDispatch(rowId))
                checkRow("dispatches", rowId, actor, true);
        } else if (tail.equals("fees/calc")) {
            WorkflowIntegration.requireProjectAccess(id(body.get("project_id")), actor, true);
        } else if (tail.equals("fees/pay")) {
            checkRow("fees", rowId, actor, true);
        } else if (tail.equals("charges/receive")) {
            checkRow("charges", rowId, actor, true);
        } else if (Set.of("q/publish", "q/close", "q/send").contains(tail)) {
            checkRow("questionnaires", rowId, actor, true);
        }
    }

    private static void checkRow(String table, long rowId, Auth.Session actor, boolean write) throws Exception {
        if (rowId <= 0) return; // Original route supplies its usual missing-record validation.
        Map<String, Object> row = Db.one("SELECT project_id FROM " + table + " WHERE id=?", rowId);
        if (row != null) checkProject(table, id(row.get("project_id")), actor, write);
    }

    private static void checkProject(String table, long projectId, Auth.Session actor, boolean write) throws Exception {
        if (projectId <= 0) return;
        WorkflowIntegration.requireProjectAccess(projectId, actor, write);
        if (write && table.equals("questionnaires") && WorkflowIntegration.managesProject(projectId))
            throw new Api.ApiException(400, "新流程项目使用原问卷平台的结果导入，不在研序新建或发放问卷");
    }

    /** SQL identifiers are fixed allowlist values; only database-generated positive IDs enter SQL. */
    static String scopedTable(String table, Auth.Session actor) throws Exception {
        if (!SCOPED.contains(table)) throw new IllegalArgumentException("未知统计范围");
        List<Map<String,Object>> all = Db.query("SELECT * FROM " + table);
        Set<Long> visible = new HashSet<>();
        for (Map<String,Object> row : WorkflowIntegration.visibleRows(table, all, actor)) visible.add(id(row.get("id")));
        List<String> hidden = new ArrayList<>();
        for (Map<String,Object> row : all) {
            long rowId = id(row.get("id"));
            if (!visible.contains(rowId)) hidden.add(Long.toString(rowId));
        }
        if (hidden.isEmpty()) return table;
        return "(SELECT * FROM " + table + " WHERE id NOT IN (" + String.join(",", hidden) + "))";
    }

    static long count(String table, Auth.Session actor) throws Exception {
        Object result = Db.one("SELECT COUNT(*) c FROM " + scopedTable(table, actor)).get("c");
        return ((Number) result).longValue();
    }

    private static long id(Object value) throws Api.ApiException {
        if (value == null || String.valueOf(value).isBlank()) return 0;
        String text = String.valueOf(value);
        try {
            if (value instanceof Number) {
                long parsed = new java.math.BigDecimal(text).longValueExact();
                if (parsed < 0 || parsed > 9007199254740991L) throw new NumberFormatException();
                return parsed;
            }
            if (!text.matches("[0-9]+")) throw new NumberFormatException();
            return Long.parseLong(text);
        } catch (ArithmeticException | NumberFormatException error) { throw new Api.ApiException(400, "记录编号格式不正确"); }
    }
}
