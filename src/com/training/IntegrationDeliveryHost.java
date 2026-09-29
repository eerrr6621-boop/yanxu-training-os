package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Original UI host for reviewed delivery and approved financial workflows; legacy direct pricing remains blocked. */
final class IntegrationDeliveryHost {
    private IntegrationDeliveryHost() {}

    static boolean handle(HttpExchange exchange, Auth.Session actor) throws Exception {
        String path = exchange.getRequestURI().getPath();
        if (Set.of("/api/delivery-settlement/configure", "/api/delivery-settlement/confirm",
                "/api/delivery-settlement/correct").contains(path))
            throw new Api.ApiException(409, "请通过课酬申报核准流程办理，旧入口不支持直接登记规则、确认或更正");
        return DeliverySettlementIntegration.handle(exchange, actor);
    }

    static long safeInteger(Object value, boolean allowZero, String label) throws Api.ApiException {
        try {
            String raw;
            if (value instanceof String text && text.matches("[0-9]{1,16}")) raw = text;
            else if (value instanceof Number number) raw = number.toString();
            else throw new NumberFormatException();
            long result = new BigDecimal(raw).longValueExact();
            if (result < (allowZero ? 0 : 1) || result > 9007199254740991L) throw new NumberFormatException();
            return result;
        } catch (ArithmeticException | NumberFormatException error) {
            throw new Api.ApiException(400, label + "须为" + (allowZero ? "非负" : "正") + "安全整数");
        }
    }

    static boolean controlledDispatch(long dispatchId) throws Exception {
        Map<String,Object> dispatch = Db.one("SELECT project_id FROM dispatches WHERE id=?", dispatchId);
        return dispatch != null && DeliverySettlementIntegration.controlled(id(dispatch, "project_id"));
    }

    /** Completed delivery permits only the existing material/remark maintenance fields. */
    static boolean completedMaintenance(Map<String,Object> old, Map<String,Object> body) throws Exception {
        if (old == null || !"已完成".equals(old.get("status")) ||
                !DeliverySettlementIntegration.controlled(id(old, "project_id"))) return false;
        for (String key : List.of("project_id", "teacher_id", "subject", "teach_date", "start_time",
                "end_time", "venue", "confirm_deadline", "hours", "status", "sent_at", "confirmed_at", "msg_log")) {
            if (!body.containsKey(key)) continue;
            if (Set.of("project_id", "teacher_id", "hours").contains(key)) {
                try {
                    if (new BigDecimal(String.valueOf(old.get(key))).compareTo(new BigDecimal(String.valueOf(body.get(key)))) != 0) return false;
                } catch (NumberFormatException error) { return false; }
            } else if (!Objects.equals(text(old.get(key)), text(body.get(key)))) return false;
        }
        return true;
    }

    /** Existing visibility is applied before adding metadata; read denial must never hide unrelated records. */
    static List<Map<String,Object>> decorateRows(String module, List<Map<String,Object>> rows, Auth.Session actor) throws Exception {
        if (!Set.of("projects", "dispatches", "fees").contains(module)) return rows;
        Map<Long,Boolean> controls = new HashMap<>();
        for (Map<String,Object> row : rows) {
            long project = id(row, module.equals("projects") ? "id" : "project_id");
            Boolean controlled = controls.get(project);
            if (controlled == null) { controlled = DeliverySettlementIntegration.controlled(project); controls.put(project, controlled); }
            row.put("delivery_controlled", controlled);
            if (controlled && module.equals("projects")) { addHours(row, actor, project); addSettlement(row, actor, project); }
        }
        return rows;
    }

    private static void addHours(Map<String,Object> result, Auth.Session actor, long project) throws Exception {
        try {
            result.put("delivery_hours", DeliverySettlementIntegration.projectHours(actor, project));
            result.put("delivery_hours_reason", null);
        } catch (Api.ApiException denied) {
            if (denied.code != 403 && denied.code != 409) throw denied;
            result.put("delivery_hours", null);
            result.put("delivery_hours_reason", denied.getMessage());
        }
    }

    private static void addSettlement(Map<String,Object> result, Auth.Session actor, long project) throws Exception {
        try {
            result.put("delivery_settlement", DeliverySettlementIntegration.projectSettlement(actor, project));
            result.put("delivery_settlement_reason", null);
        } catch (Api.ApiException unavailable) {
            if (unavailable.code != 403 && unavailable.code != 409) throw unavailable;
            result.put("delivery_settlement", null);
            result.put("delivery_settlement_reason", unavailable.getMessage());
        }
    }

    /** The accepted project's existing target remains the target; only M05 reviewed actual hours satisfy it. */
    @SuppressWarnings("unchecked")
    static Map<String,Object> projectTransitionState(Map<String,Object> project, String action, Auth.Session actor) throws Exception {
        long projectId = id(project, "id");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("project_id", projectId); result.put("title", project.get("title")); result.put("action", action);
        result.put("delivery_controlled", true);
        addHours(result, actor, projectId);
        addSettlement(result, actor, projectId);
        Map<String,Object> hours = (Map<String,Object>) result.get("delivery_hours");
        List<Map<String,Object>> blockers = new ArrayList<>(), warnings = new ArrayList<>();
        String status = text(project.get("status"));
        boolean archive = action.equals("archive");
        // Gate before any old fee arithmetic or a ready/success result, including check-only requests.
        if (archive) {
            try { DeliverySettlementIntegration.guardLegacyArchive(projectId); }
            catch (Api.ApiException unavailable) {
                if (unavailable.code != 409) throw unavailable;
                blockers.add(issue("settlement_incomplete", "课酬及付款尚未完成核对", unavailable.getMessage(), "fees"));
            }
        }
        if (!(archive ? "已完成" : "进行中").equals(status))
            blockers.add(issue("status", archive ? "项目尚未完成交付" : "当前状态不能完成交付",
                    archive ? "请先完成全部课程。" : "请先完成项目启动，再执行交付闭环。", "projects"));
        BigDecimal target = decimal(project.get("hours"));
        if (target == null || target.signum() <= 0)
            blockers.add(issue("plan_hours", "项目计划课时未设置", "请先补充项目计划课时。", "projects"));
        if (hours == null) {
            blockers.add(issue("delivery_hours_unavailable", "无法核对项目授课记录", text(result.get("delivery_hours_reason")), "dispatches"));
        } else {
            if (id(hours, "dispatch_count") == 0)
                blockers.add(issue("no_dispatch", "尚未安排任何课程", "请先完成课程、讲师与授课日期安排。", "dispatches"));
            if (id(hours, "pending_count") > 0)
                blockers.add(issue("open_dispatch", "仍有课程未完成", hours.get("pending_count") + " 条有效排课尚未完成。", "dispatches"));
            if (id(hours, "unverified_completed_count") > 0)
                blockers.add(issue("unverified_delivery", "已完成课程仍有未核对记录", hours.get("unverified_completed_count") + " 条已完成排课缺少有效的授课核对。", "dispatches"));
            BigDecimal actual = decimal(hours.get("actual"));
            if (actual == null)
                blockers.add(issue("actual_hours_unknown", "实际授课课时尚未完整核对", "未知实际课时不能当作零或计划课时完成交付。", "dispatches"));
            else if (target != null && target.signum() > 0 && actual.compareTo(target) < 0)
                blockers.add(issue("hours_gap", "已核对实际课时不足", "项目计划 " + target.toPlainString() + " 课时，已核对完成 " + hours.get("actual") + " 课时。", "dispatches"));
            else if (target != null && actual.compareTo(target) > 0)
                warnings.add(issue("excess_hours", "实际课时超过计划", "项目计划 " + target.toPlainString() + " 课时，已核对完成 " + hours.get("actual") + " 课时。", "projects"));
        }
        Map<String,Object> charges = Db.one("SELECT COUNT(*) total, COALESCE(SUM(amount),0) due, COALESCE(SUM(received),0) received, " +
                "COALESCE(SUM(CASE WHEN received>amount THEN 1 ELSE 0 END),0) overpaid_count FROM charges WHERE project_id=?", projectId);
        BigDecimal amount = decimal(project.get("amount")), due = decimal(charges.get("due")), received = decimal(charges.get("received"));
        BigDecimal outstanding = (amount != null && amount.signum() > 0 ? amount : due).subtract(received).max(BigDecimal.ZERO);
        if (archive) {
            // Preserve the original receivables checks when the former unconditional M05 archive block opens.
            BigDecimal tolerance = new BigDecimal("0.005");
            if (amount != null && amount.signum() > 0 && id(charges, "total") == 0)
                blockers.add(issue("no_charge", "尚未建立回款记录", "项目存在合同金额，但没有对应的应收记录。", "charges"));
            if (amount != null && amount.signum() > 0 && id(charges, "total") > 0 && due.subtract(amount).abs().compareTo(tolerance) > 0)
                blockers.add(issue("charge_mismatch", "合同金额与应收记录不一致", "请先核对合同金额与应收记录合计。", "charges"));
            if (outstanding.compareTo(tolerance) > 0)
                blockers.add(issue("outstanding", "回款尚未结清", "仍有 " + outstanding.toPlainString() + " 元待回款。", "charges"));
            if (id(charges, "overpaid_count") > 0)
                blockers.add(issue("overpaid", "存在实收大于应收的记录", "请先核对回款金额。", "charges"));
        } else if (outstanding.signum() > 0) {
            warnings.add(issue("outstanding", "项目仍有待回款", "当前尚有 " + outstanding.toPlainString() + " 元待回款，归档前仍须结清。", "charges"));
        }
        Map<String,Object> material = Db.one("SELECT COUNT(*) c FROM dispatches WHERE project_id=? AND (status IS NULL OR status<>'已拒绝') AND COALESCE(material_status,'')<>'已就绪'", projectId);
        if (id(material, "c") > 0)
            warnings.add(issue("material", "课程材料状态未闭环", material.get("c") + " 条排课未标记为已就绪，请核对实际材料。", "dispatches"));
        String endDate = text(project.get("end_date"));
        if (endDate.isEmpty() || endDate.compareTo(LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()) > 0)
            warnings.add(issue("end_date", "项目结束日期仍需核对", endDate.isEmpty() ? "尚未填写项目结束日期。" : "项目结束日期晚于今天。", "projects"));
        Map<String,Object> metrics = new LinkedHashMap<>();
        metrics.put("plan_hours", target == null ? null : target.toPlainString());
        metrics.put("scheduled_hours", hours == null ? null : hours.get("planned"));
        metrics.put("completed_hours", hours == null ? null : hours.get("actual"));
        metrics.put("outstanding", outstanding.toPlainString());
        Map<String,Object> settlement = (Map<String,Object>) result.get("delivery_settlement");
        metrics.put("pending_fee_amount", settlement == null ? null : settlement.get("balance"));
        result.put("metrics", metrics); result.put("blockers", blockers); result.put("warnings", warnings);
        result.put("ready", blockers.isEmpty());
        return result;
    }

    private static Map<String,Object> issue(String code, String title, String detail, String module) {
        return new LinkedHashMap<>(Map.of("code", code, "title", title, "detail", detail, "module", module));
    }
    private static long id(Map<String,Object> row, String key) { Object value = row.get(key); return value == null ? 0 : ((Number)value).longValue(); }
    private static BigDecimal decimal(Object value) { return value == null ? null : new BigDecimal(value.toString()); }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
}
