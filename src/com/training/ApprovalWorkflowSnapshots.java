package com.training;

import static com.training.ApprovalWorkflow.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Strict, side-effect-free codec for trusted persisted snapshots. Never an HTTP body adapter. */
public final class ApprovalWorkflowSnapshots {
    private ApprovalWorkflowSnapshots() {}
    public static Map<String, Object> encodePolicy(Policy p) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", p.version()); result.put("leaderReturnTo", p.leaderReturnTo().name());
        result.put("bpReturnTo", p.bpReturnTo().name()); result.put("resubmitFrom", p.resubmitFrom().name());
        result.put("selfApproval", p.selfApproval().name()); result.put("combinedRoles", p.combinedRoles().name());
        result.put("delegation", p.delegation().name()); result.put("inactiveAssigneeDelegation", p.inactiveAssigneeDelegation().name());
        result.put("withdrawalConfigured", p.withdrawalConfigured());
        result.put("withdrawableStatuses", p.withdrawableStatuses().stream().map(Enum::name).sorted().toList());
        result.put("documentChanges", p.documentChanges().name()); result.put("reviewMode", p.reviewMode().name());
        if (p.combinedAssignment() != null) {
            CombinedAssignment a = p.combinedAssignment();
            result.put("combinedAssignment", Map.of("organizationCode", a.organizationCode(), "approverId", a.approverId(),
                    "directoryVersion", a.directoryVersion(), "evidenceRef", a.evidenceRef()));
        }
        return result;
    }
    public static Policy decodePolicy(Map<String, Object> source) {
        try {
            Objects.requireNonNull(source);
            // Only a complete absence of extension fields denotes the historical schema.
            boolean hasMode = source.containsKey("reviewMode");
            boolean hasAssignment = source.containsKey("combinedAssignment");
            require(hasMode || !hasAssignment, "兼任依据缺少模式标识");
            ReviewMode mode = hasMode ? enumeration(source, "reviewMode", ReviewMode.class) : ReviewMode.SEQUENTIAL;
            CombinedAssignment assignment = null;
            if (mode == ReviewMode.SINGLE_EXPLICIT) {
                Map<String, Object> a = object(source.get("combinedAssignment"));
                assignment = new CombinedAssignment(string(a, "organizationCode"), string(a, "approverId"),
                        string(a, "directoryVersion"), string(a, "evidenceRef"));
            } else require(!hasAssignment, "普通顺序策略不能携带兼任依据");
            Object raw = source.get("withdrawableStatuses"); require(raw instanceof List<?>, "撤回阶段必须为列表");
            Set<Status> withdrawal = new HashSet<>();
            for (Object status : (List<?>) raw) {
                require(status instanceof String, "撤回阶段必须为字符串");
                require(withdrawal.add(Status.valueOf((String) status)), "撤回阶段重复");
            }
            return new Policy(string(source, "version"), enumeration(source, "leaderReturnTo", ReturnTo.class),
                    enumeration(source, "bpReturnTo", ReturnTo.class), enumeration(source, "resubmitFrom", Restart.class),
                    enumeration(source, "selfApproval", Permission.class), enumeration(source, "combinedRoles", Permission.class),
                    enumeration(source, "delegation", Permission.class), enumeration(source, "inactiveAssigneeDelegation", Permission.class),
                    bool(source, "withdrawalConfigured"), withdrawal, enumeration(source, "documentChanges", ChangeRule.class), assignment);
        } catch (RuntimeException failure) { throw invalid("策略快照无效", failure); }
    }
    public static Event decodeEvent(Map<String, Object> source) {
        try {
            Objects.requireNonNull(source);
            // Old events have no responsibilities. New events must agree with their canonical action.
            Event event = new Event(integer(source, "version"), string(source, "requestId"), enumeration(source, "action", Action.class),
                    string(source, "actorId"), optionalString(source, "onBehalfOf"),
                    source.get("from") == null ? null : enumeration(source, "from", Status.class), enumeration(source, "to", Status.class),
                    enumeration(source, "stage", Stage.class), optionalString(source, "comment"), integer(source, "dataRevision"),
                    Instant.parse(string(source, "at")));
            if (source.containsKey("responsibilities")) require(event.responsibilities().stream().map(Enum::name).toList().equals(source.get("responsibilities")),
                    "职责记录与办理动作不符");
            return event;
        } catch (RuntimeException failure) { throw invalid("办理记录无效", failure); }
    }
    private static Failure invalid(String label, RuntimeException failure) {
        if (failure instanceof Failure f && "INVALID_STATE".equals(f.code)) return f;
        return new Failure("INVALID_STATE", label + "，请核对持久化数据");
    }
    private static void require(boolean value, String message) { if (!value) throw new Failure("INVALID_STATE", message); }
    private static String string(Map<String, Object> source, String field) {
        Object value = source.get(field); require(value instanceof String && !((String) value).isBlank(), field + "须为非空文本");
        return (String) value;
    }
    private static String optionalString(Map<String, Object> source, String field) {
        Object value = source.get(field); require(value == null || value instanceof String, field + "须为文本");
        return value == null ? "" : (String) value;
    }
    private static boolean bool(Map<String, Object> source, String field) {
        Object value = source.get(field); require(value instanceof Boolean, field + "须为布尔值"); return (Boolean) value;
    }
    private static long integer(Map<String, Object> source, String field) {
        Object value = source.get(field); require(value instanceof Number, field + "须为整数");
        return new BigDecimal(value.toString()).longValueExact();
    }
    private static <T extends Enum<T>> T enumeration(Map<String, Object> source, String field, Class<T> type) { return Enum.valueOf(type, string(source, field)); }
    private static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?>, "兼任依据须为对象");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : ((Map<?, ?>) value).entrySet()) { require(entry.getKey() instanceof String, "对象键须为文本"); result.put((String) entry.getKey(), entry.getValue()); }
        return result;
    }
}
