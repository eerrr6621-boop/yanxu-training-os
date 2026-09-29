package com.training;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** S01 notification projection. Identity and workflow authority are injected by M01/M03. */
public final class NotificationChannels {
    private NotificationChannels() {}
    public static final long MAX_WEB_ID = 9007199254740991L;
    public enum EventType { REVIEW_REQUIRED, RETURNED, HANDOVER_REQUIRED, HANDOVER_ACCEPTED }
    public enum Destination { M02, M03 }
    public enum Intent { ACTION, INFORMATION }
    public enum BusinessMoment { SUBMITTED, LEADER_APPROVED, BP_APPROVED, RETURNED, TEAM_ACCEPTED }
    public enum RecipientRole { LEADER, BP, TRAINING_TEAM, SUBMITTER }
    public record ReminderRule(String version, EventType type, RecipientRole role,
                               Intent intent, Destination destination) {}

    /** Explicitly approved by the user on 2026-09-21; resolve people through M01/M03/M02. */
    public static ReminderRule approvedRule(BusinessMoment moment) {
        Objects.requireNonNull(moment, "moment");
        String version = "S01-USER-20260921-v1";
        return switch (moment) {
            case SUBMITTED -> new ReminderRule(version, EventType.REVIEW_REQUIRED,
                    RecipientRole.LEADER, Intent.ACTION, Destination.M03);
            case LEADER_APPROVED -> new ReminderRule(version, EventType.REVIEW_REQUIRED,
                    RecipientRole.BP, Intent.ACTION, Destination.M03);
            case BP_APPROVED -> new ReminderRule(version, EventType.HANDOVER_REQUIRED,
                    RecipientRole.TRAINING_TEAM, Intent.ACTION, Destination.M02);
            case RETURNED -> new ReminderRule(version, EventType.RETURNED,
                    RecipientRole.SUBMITTER, Intent.ACTION, Destination.M02);
            case TEAM_ACCEPTED -> new ReminderRule(version, EventType.HANDOVER_ACCEPTED,
                    RecipientRole.SUBMITTER, Intent.INFORMATION, Destination.M02);
        };
    }
    public enum ActionState { PENDING, RESOLVED, NOT_ASSIGNED }
    public enum Channel { IN_APP, EMAIL, PUBLIC_ACCOUNT }
    public enum ChannelState { READY, UNCONFIGURED, ADAPTER_NOT_CONNECTED }

    /** A committed M03 event, normalized by the server, never accepted from a browser. */
    public record Event(String eventId, EventType type, long recordId, String taskId,
                        Destination destination, Instant occurredAt) {
        public Event {
            eventId = token(eventId, "eventId");
            Objects.requireNonNull(type, "type");
            positiveId(recordId);
            taskId = taskId == null || taskId.isEmpty() ? null : token(taskId, "taskId");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (destination == Destination.M03 && taskId == null)
                throw failure("TASK_REQUIRED", "审批事项必须提供原始任务标识");
        }
    }

    /** Message entitlement, not an account or a role assignment. */
    public record Recipient(String userId, Intent intent) {
        public Recipient {
            userId = token(userId, "userId");
            Objects.requireNonNull(intent, "intent");
        }
    }
    public record Audience(String rulesVersion, List<Recipient> recipients) {
        public Audience {
            rulesVersion = token(rulesVersion, "rulesVersion");
            recipients = List.copyOf(Objects.requireNonNull(recipients, "recipients"));
        }
    }

    /** Implement only on the server with existing session, M01 permissions and M03 state. */
    public interface Authority {
        Audience audience(Event event);
        boolean active(String userId);
        boolean canView(String userId, long recordId);
        ActionState actionState(String userId, Event event);
    }

    /** Persist these immutable fields together with the M03 outbox event. */
    public record Notice(String id, Event event, Recipient recipient, String rulesVersion) {
        public Notice {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(recipient, "recipient");
            rulesVersion = token(rulesVersion, "rulesVersion");
            if (!noticeId(event.eventId(), recipient.userId()).equals(id))
                throw failure("INVALID_NOTICE_ID", "通知标识与事件收件人不一致");
        }
    }
    public record Batch(Event event, String rulesVersion, List<Notice> notices) {
        public Batch {
            Objects.requireNonNull(event, "event");
            rulesVersion = token(rulesVersion, "rulesVersion");
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
            if (notices.isEmpty()) throw failure("NO_RECIPIENTS", "事件没有已确认的收件人");
            Set<String> ids = new HashSet<>();
            for (Notice notice : notices) {
                if (!notice.event().equals(event) || !notice.rulesVersion().equals(rulesVersion)
                        || !ids.add(notice.id()))
                    throw failure("INVALID_BATCH", "通知批次内容不一致或重复");
            }
        }
    }
    public static final class Rejected extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private Rejected(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    /** All-or-nothing validation: missing, extra or unauthorized recipients reject the event. */
    public static Batch prepare(Event event, Collection<String> proposedRecipients, Authority authority) {
        Objects.requireNonNull(event, "event");
        if (authority == null) throw failure("AUTHORITY_UNAVAILABLE", "身份与事项权限尚未接入");
        Audience audience = authority.audience(event);
        if (audience == null) throw failure("AUDIENCE_UNCONFIGURED", "事件收件人规则尚未配置");
        SortedMap<String, Recipient> canonical = new TreeMap<>();
        for (Recipient recipient : audience.recipients()) {
            Recipient previous = canonical.putIfAbsent(recipient.userId(), recipient);
            if (previous != null && previous.intent() != recipient.intent())
                throw failure("AUDIENCE_CONFLICT", "同一收件人的通知用途冲突");
        }
        if (canonical.isEmpty()) throw failure("NO_RECIPIENTS", "事件没有已确认的收件人");
        if (proposedRecipients == null) throw failure("RECIPIENT_MISMATCH", "事件收件人缺失");
        Set<String> proposed = new TreeSet<>();
        for (String id : proposedRecipients) proposed.add(token(id, "recipientId"));
        if (!proposed.equals(canonical.keySet()))
            throw failure("RECIPIENT_MISMATCH", "事件收件人与已确认规则不一致");
        List<Notice> notices = new ArrayList<>();
        for (Recipient recipient : canonical.values()) {
            if (!authority.active(recipient.userId()))
                throw failure("RECIPIENT_INACTIVE", "事件包含不可用的收件人");
            if (!authority.canView(recipient.userId(), event.recordId()))
                throw failure("RECIPIENT_FORBIDDEN", "事件包含无权查看事项的收件人");
            if (recipient.intent() == Intent.ACTION
                    && authority.actionState(recipient.userId(), event) != ActionState.PENDING)
                throw failure("NOT_CURRENT_ASSIGNEE", "待办收件人与当前办理人不一致");
            notices.add(new Notice(noticeId(event.eventId(), recipient.userId()), event,
                    recipient, audience.rulesVersion()));
        }
        return new Batch(event, audience.rulesVersion(), notices);
    }

    /** No business content is emitted before checking ownership and current M01 access. */
    public static Map<String, Object> view(Notice notice, String sessionUserId,
                                          Authority authority, Instant readAt) {
        authorize(notice, sessionUserId, authority);
        boolean actionable = actionable(notice, authority);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", notice.id());
        row.put("eventId", notice.event().eventId());
        row.put("type", notice.event().type().name());
        row.put("title", title(notice.event().type()));
        row.put("body", "事项 #" + notice.event().recordId() + " · 打开后以事项当前状态为准。");
        row.put("createdAt", notice.event().occurredAt().toString());
        row.put("readAt", readAt == null ? null : readAt.toString());
        row.put("actionable", actionable);
        return Collections.unmodifiableMap(row);
    }

    /** Build an internal target only. Never accept return URLs, names, tokens or arbitrary hashes. */
    public static Map<String, Object> target(Notice notice, String sessionUserId, Authority authority) {
        authorize(notice, sessionUserId, authority);
        boolean actionable = actionable(notice, authority);
        Event event = notice.event();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("recordId", Long.toString(event.recordId()));
        params.put("eventId", event.eventId());
        params.put("view", actionable && event.destination() == Destination.M03 ? "task" : "detail");
        if (event.destination() == Destination.M03) params.put("taskId", event.taskId());
        return Map.of("moduleId", event.destination().name(), "params", Collections.unmodifiableMap(params));
    }

    /** Read status is separate from workflow completion; opening does not approve or claim work. */
    public static void authorize(Notice notice, String sessionUserId, Authority authority) {
        Objects.requireNonNull(notice, "notice");
        if (sessionUserId == null || !notice.recipient().userId().equals(sessionUserId)
                || authority == null || !authority.active(sessionUserId)
                || !authority.canView(sessionUserId, notice.event().recordId()))
            throw failure("NOTICE_UNAVAILABLE", "通知不存在或当前无权访问");
    }

    private static boolean actionable(Notice notice, Authority authority) {
        if (notice.recipient().intent() != Intent.ACTION) return false;
        ActionState state = authority.actionState(notice.recipient().userId(), notice.event());
        if (state == null) throw failure("WORKFLOW_UNAVAILABLE", "事项当前状态暂不可用");
        return state == ActionState.PENDING;
    }

    /** External channels never report connected/sent: this module contains no sender implementation. */
    public static List<Map<String, Object>> channelStatus(boolean persistentInboxConnected,
                                                          Set<Channel> configuredExternalChannels) {
        Set<Channel> configured = configuredExternalChannels == null ? Set.of() : configuredExternalChannels;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Channel channel : Channel.values()) {
            ChannelState state = channel == Channel.IN_APP
                    ? (persistentInboxConnected ? ChannelState.READY : ChannelState.ADAPTER_NOT_CONNECTED)
                    : (configured.contains(channel) ? ChannelState.ADAPTER_NOT_CONNECTED : ChannelState.UNCONFIGURED);
            rows.add(Map.of("channel", channel.name(), "status", state.name().toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(rows);
    }

    public static String noticeId(String eventId, String recipientId) {
        // The separator cannot occur in tokens, preventing ambiguous concatenation.
        return UUID.nameUUIDFromBytes((token(eventId, "eventId") + "\n" + token(recipientId, "recipientId"))
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String token(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw failure("INVALID_IDENTIFIER", field + " 必须为已有的稳定标识");
        return value;
    }
    private static void positiveId(long value) {
        if (value <= 0 || value > MAX_WEB_ID) throw failure("INVALID_RECORD_ID", "事项 ID 必须为安全范围内的正整数");
    }
    private static Rejected failure(String code, String message) { return new Rejected(code, message); }
    private static String title(EventType type) {
        return switch (type) {
            case REVIEW_REQUIRED -> "有审批事项待处理";
            case RETURNED -> "事项已退回";
            case HANDOVER_REQUIRED -> "有事项等待团队承接";
            case HANDOVER_ACCEPTED -> "事项已由团队承接";
        };
    }

    /** Synthetic demo/test adapter only. Not durable and never a production fallback. */
    public static final class DemoInbox {
        private final Map<String, Batch> events = new LinkedHashMap<>();
        private final Map<String, Notice> notices = new LinkedHashMap<>();
        private final Map<String, Instant> readTimes = new HashMap<>();

        /** Atomic duplicate acceptance; changed payload/audience for an event ID is rejected. */
        public synchronized int accept(Batch batch) {
            Objects.requireNonNull(batch, "batch");
            Batch previous = events.get(batch.event().eventId());
            if (previous != null) {
                if (!previous.equals(batch)) throw failure("EVENT_CONFLICT", "同一事件标识的内容或收件人发生变化");
                return 0;
            }
            for (Notice notice : batch.notices()) {
                if (notices.containsKey(notice.id())) throw failure("NOTICE_CONFLICT", "通知标识冲突");
            }
            events.put(batch.event().eventId(), batch);
            for (Notice notice : batch.notices()) notices.put(notice.id(), notice);
            return batch.notices().size();
        }

        public synchronized List<Map<String, Object>> list(String sessionUserId, Authority authority) {
            if (sessionUserId == null || authority == null || !authority.active(sessionUserId))
                throw failure("NOTICE_UNAVAILABLE", "当前会话不可用");
            List<Notice> own = notices.values().stream()
                    .filter(n -> n.recipient().userId().equals(sessionUserId))
                    .sorted(Comparator.comparing((Notice n) -> n.event().occurredAt()).reversed()
                            .thenComparing(Notice::id)).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Notice notice : own) {
                if (!authority.canView(sessionUserId, notice.event().recordId())) continue;
                rows.add(view(notice, sessionUserId, authority, readTimes.get(notice.id())));
            }
            return List.copyOf(rows);
        }

        public synchronized Map<String, Object> markRead(String id, String sessionUserId,
                                                         Authority authority, Instant now) {
            Notice notice = lookup(id);
            authorize(notice, sessionUserId, authority);
            Objects.requireNonNull(now, "now");
            Map<String, Object> row = view(notice, sessionUserId, authority, readTimes.get(id));
            if (now.isBefore(notice.event().occurredAt()))
                throw failure("INVALID_READ_TIME", "已读时间早于事件时间");
            readTimes.putIfAbsent(id, now);
            Map<String, Object> updated = new LinkedHashMap<>(row);
            updated.put("readAt", readTimes.get(id).toString());
            return Collections.unmodifiableMap(updated);
        }

        public synchronized Map<String, Object> open(String id, String sessionUserId, Authority authority) {
            return target(lookup(id), sessionUserId, authority);
        }
        private Notice lookup(String id) {
            Notice notice = notices.get(id);
            if (notice == null) throw failure("NOTICE_UNAVAILABLE", "通知不存在或当前无权访问");
            return notice;
        }
    }
}
