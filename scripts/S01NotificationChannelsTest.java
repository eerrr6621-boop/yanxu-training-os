package com.training;

import java.time.Instant;
import java.util.*;
import static com.training.NotificationChannels.*;

/** Synthetic cases only; does not initialize Db, Auth or a network sender. */
public final class S01NotificationChannelsTest {
    private static int checks;
    private static final Instant TIME = Instant.parse("2026-09-21T01:00:00Z");
    private static void check(boolean actual, String message) {
        checks++;
        if (!actual) throw new AssertionError(message);
    }
    private static void rejected(String code, Runnable work) {
        checks++;
        try { work.run(); } catch (Rejected ex) {
            if (code.equals(ex.code())) return;
            throw new AssertionError("Expected " + code + " but got " + ex.code(), ex);
        }
        throw new AssertionError("Expected rejection " + code);
    }
    private static Event event(String id, long record, String task) {
        return new Event(id, EventType.REVIEW_REQUIRED, record, task, Destination.M03, TIME);
    }
    private static final class SyntheticAuthority implements Authority {
        Audience configured = new Audience("synthetic-v1", List.of(
                new Recipient("demo-leader", Intent.ACTION), new Recipient("demo-observer", Intent.INFORMATION)));
        Set<String> inactive = new HashSet<>();
        Set<String> forbidden = new HashSet<>();
        ActionState state = ActionState.PENDING;
        public Audience audience(Event event) { return configured; }
        public boolean active(String user) { return !inactive.contains(user); }
        public boolean canView(String user, long record) { return !forbidden.contains(user); }
        public ActionState actionState(String user, Event event) { return state; }
    }
    public static void main(String[] args) {
        check(approvedRule(BusinessMoment.SUBMITTED).equals(new ReminderRule("S01-USER-20260921-v1",
                EventType.REVIEW_REQUIRED, RecipientRole.LEADER, Intent.ACTION, Destination.M03)), "submission reminds leader");
        check(approvedRule(BusinessMoment.LEADER_APPROVED).equals(new ReminderRule("S01-USER-20260921-v1",
                EventType.REVIEW_REQUIRED, RecipientRole.BP, Intent.ACTION, Destination.M03)), "leader approval reminds BP");
        check(approvedRule(BusinessMoment.BP_APPROVED).equals(new ReminderRule("S01-USER-20260921-v1",
                EventType.HANDOVER_REQUIRED, RecipientRole.TRAINING_TEAM, Intent.ACTION, Destination.M02)), "BP approval reminds team in M02");
        check(approvedRule(BusinessMoment.RETURNED).equals(new ReminderRule("S01-USER-20260921-v1",
                EventType.RETURNED, RecipientRole.SUBMITTER, Intent.ACTION, Destination.M02)), "return reminds original submitter");
        check(approvedRule(BusinessMoment.TEAM_ACCEPTED).equals(new ReminderRule("S01-USER-20260921-v1",
                EventType.HANDOVER_ACCEPTED, RecipientRole.SUBMITTER, Intent.INFORMATION, Destination.M02)), "acceptance informs original submitter");
        SyntheticAuthority authority = new SyntheticAuthority();
        Event event = event("demo-event-1", 4101, "demo-task-1");
        List<String> recipients = List.of("demo-leader", "demo-observer");
        Batch batch = prepare(event, recipients, authority);
        check(batch.notices().size() == 2, "all canonical recipients");
        check(prepare(event, List.of("demo-observer", "demo-leader", "demo-leader"), authority).equals(batch),
                "duplicate/reordered recipients normalize without new notices");
        rejected("RECIPIENT_MISMATCH", () -> prepare(event, List.of("demo-leader"), authority));
        rejected("RECIPIENT_MISMATCH", () -> prepare(event, List.of("demo-leader", "demo-observer", "intruder"), authority));
        rejected("RECIPIENT_MISMATCH", () -> prepare(event, null, authority));
        rejected("AUTHORITY_UNAVAILABLE", () -> prepare(event, recipients, null));
        authority.configured = null;
        rejected("AUDIENCE_UNCONFIGURED", () -> prepare(event, recipients, authority));
        authority.configured = new Audience("synthetic-v1", List.of());
        rejected("NO_RECIPIENTS", () -> prepare(event, List.of(), authority));
        authority.configured = new Audience("synthetic-v1", List.of(new Recipient("demo-leader", Intent.ACTION),
                new Recipient("demo-leader", Intent.INFORMATION)));
        rejected("AUDIENCE_CONFLICT", () -> prepare(event, List.of("demo-leader"), authority));
        authority.configured = new SyntheticAuthority().configured;
        authority.inactive.add("demo-observer");
        rejected("RECIPIENT_INACTIVE", () -> prepare(event, recipients, authority));
        authority.inactive.clear();
        authority.forbidden.add("demo-observer");
        rejected("RECIPIENT_FORBIDDEN", () -> prepare(event, recipients, authority));
        authority.forbidden.clear();
        authority.state = ActionState.NOT_ASSIGNED;
        rejected("NOT_CURRENT_ASSIGNEE", () -> prepare(event, recipients, authority));
        authority.state = ActionState.PENDING;

        DemoInbox inbox = new DemoInbox();
        check(inbox.accept(batch) == 2, "atomic insert");
        check(inbox.accept(batch) == 0, "idempotent retry");
        rejected("EVENT_CONFLICT", () -> inbox.accept(prepare(event("demo-event-1", 4102, "demo-task-2"), recipients, authority)));
        check(inbox.list("demo-leader", authority).size() == 1, "owner sees one notice");
        check(inbox.list("intruder", authority).isEmpty(), "other user sees no notices");
        String noticeId = noticeId(event.eventId(), "demo-leader");
        rejected("NOTICE_UNAVAILABLE", () -> inbox.open(noticeId, "demo-observer", authority));
        rejected("NOTICE_UNAVAILABLE", () -> inbox.markRead(noticeId, "intruder", authority, TIME.plusSeconds(1)));
        rejected("NOTICE_UNAVAILABLE", () -> inbox.open("missing", "demo-leader", authority));
        check(inbox.list("demo-leader", authority).get(0).get("readAt") == null, "not marked by forbidden access");
        Map<?, ?> target = inbox.open(noticeId, "demo-leader", authority);
        Map<?, ?> params = (Map<?, ?>) target.get("params");
        check(target.get("moduleId").equals("M03") && params.get("recordId").equals("4101")
                && params.get("taskId").equals("demo-task-1") && params.get("view").equals("task"), "exact matter/task target");
        check(inbox.list("demo-leader", authority).get(0).get("readAt") == null, "opening does not mark read");
        rejected("INVALID_READ_TIME", () -> inbox.markRead(noticeId, "demo-leader", authority, TIME.minusSeconds(1)));
        Map<String, Object> read = inbox.markRead(noticeId, "demo-leader", authority, TIME.plusSeconds(2));
        check(read.get("readAt").equals(TIME.plusSeconds(2).toString()), "read timestamp stored");
        check(Boolean.TRUE.equals(read.get("actionable")), "read is not approval completion");
        check(inbox.markRead(noticeId, "demo-leader", authority, TIME.plusSeconds(10)).get("readAt").equals(read.get("readAt")),
                "mark read is idempotent");
        String observerId = noticeId(event.eventId(), "demo-observer");
        check(Boolean.FALSE.equals(inbox.list("demo-observer", authority).get(0).get("actionable")), "FYI is never a task");
        check(((Map<?, ?>) inbox.open(observerId, "demo-observer", authority).get("params")).get("view").equals("detail"),
                "FYI opens read-only detail");
        authority.state = ActionState.RESOLVED;
        check(Boolean.FALSE.equals(inbox.list("demo-leader", authority).get(0).get("actionable")), "stale todo disappears");
        check(((Map<?, ?>) inbox.open(noticeId, "demo-leader", authority).get("params")).get("view").equals("detail"),
                "resolved task opens detail without action");
        authority.state = ActionState.NOT_ASSIGNED;
        check(((Map<?, ?>) inbox.open(noticeId, "demo-leader", authority).get("params")).get("view").equals("detail"),
                "reassigned task no longer opens action");
        authority.state = null;
        rejected("WORKFLOW_UNAVAILABLE", () -> inbox.open(noticeId, "demo-leader", authority));
        authority.state = ActionState.PENDING;
        authority.forbidden.add("demo-leader");
        check(inbox.list("demo-leader", authority).isEmpty(), "revoked access removes notification projection");
        rejected("NOTICE_UNAVAILABLE", () -> inbox.open(noticeId, "demo-leader", authority));
        rejected("NOTICE_UNAVAILABLE", () -> inbox.markRead(noticeId, "demo-leader", authority, TIME.plusSeconds(3)));
        authority.forbidden.clear();
        authority.inactive.add("demo-leader");
        rejected("NOTICE_UNAVAILABLE", () -> inbox.list("demo-leader", authority));
        rejected("NOTICE_UNAVAILABLE", () -> inbox.open(noticeId, "demo-leader", authority));
        authority.inactive.clear();
        rejected("NOTICE_UNAVAILABLE", () -> inbox.open(noticeId, null, authority));
        rejected("NOTICE_UNAVAILABLE", () -> inbox.open(noticeId, "demo-leader", null));
        rejected("INVALID_IDENTIFIER", () -> event("https://evil.test/", 4101, "task"));
        rejected("INVALID_IDENTIFIER", () -> event("event", 4101, "x\nuser"));
        rejected("TASK_REQUIRED", () -> event("event", 4101, null));
        rejected("INVALID_RECORD_ID", () -> event("event", 0, "task"));
        rejected("INVALID_RECORD_ID", () -> event("event", MAX_WEB_ID + 1, "task"));
        check(noticeId("a:b", "c").equals(noticeId("a:b", "c")), "stable notice key");
        check(!noticeId("a:b", "c").equals(noticeId("a", "b:c")), "no recipient/event delimiter collision");
        check(!noticeId("evt", "u1").equals(noticeId("evt", "u2")), "per-user keys");
        rejected("INVALID_NOTICE_ID", () -> new Notice("forged", event, batch.notices().get(0).recipient(), "v1"));
        rejected("INVALID_BATCH", () -> new Batch(event, "other-version", batch.notices()));
        List<Map<String, Object>> channels = channelStatus(false, Set.of(Channel.EMAIL));
        check(channels.get(0).get("status").equals("adapter_not_connected"), "memory inbox not reported live ready");
        check(channels.get(1).get("status").equals("adapter_not_connected"), "configured email still not connected");
        check(channels.get(2).get("status").equals("unconfigured"), "unconfigured public account");
        check(channelStatus(true, Set.of()).get(0).get("status").equals("ready"), "explicit host inbox integration");
        check(channels.stream().noneMatch(row -> row.containsValue("sent")), "no pretend external delivery");
        for (EventType type : EventType.values()) {
            Event business = new Event("demo-" + type, type, 4201, null, Destination.M02, TIME);
            Notice notice = prepare(business, recipients, authority).notices().get(0);
            Map<?, ?> businessTarget = target(notice, "demo-leader", authority);
            Map<?, ?> businessParams = (Map<?, ?>) businessTarget.get("params");
            check(businessTarget.get("moduleId").equals("M02") && businessParams.get("recordId").equals("4201")
                    && !businessParams.containsKey("taskId"), "business destination retained for " + type);
        }
        String json = Json.write(Map.of("state", "ready", "synthetic", true,
                "items", inbox.list("demo-leader", authority), "channels", channels));
        check(Json.parseMap(json).get("state").equals("ready"), "existing Json serialization works");
        System.out.println("S01 NotificationChannels: " + checks + " synthetic checks passed; no network or production database used.");
    }
}
