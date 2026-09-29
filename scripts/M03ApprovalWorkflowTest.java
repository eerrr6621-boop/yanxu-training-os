package com.training;

import static com.training.ApprovalWorkflow.*;
import java.time.Instant;
import java.util.*;

/** Synthetic-only protocol tests. No database, server, network or production identities. */
public final class M03ApprovalWorkflowTest {
    static int checks;
    static final Instant TIME = Instant.parse("2026-09-21T00:00:00Z");
    static final Participants PEOPLE = new Participants("SYN-SUBMITTER", "SYN-LEADER", "SYN-BP");
    static final Map<String, Activity> DIRECTORY = Map.of("SYN-SUBMITTER", Activity.ACTIVE, "SYN-LEADER", Activity.ACTIVE,
            "SYN-BP", Activity.ACTIVE, "SYN-DELEGATE", Activity.ACTIVE, "SYN-OTHER", Activity.ACTIVE);
    static Access as(String actor) { return new Access(actor, DIRECTORY, Map.of()); }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    static void fails(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (Failure failure) { check(code.equals(failure.code), "Expected " + code + ", got " + failure.code + ": " + failure.getMessage()); }
    }
    static Policy policy(Restart restart, ReturnTo bpReturn, Permission self, Permission combined, Permission delegated,
                         Permission leave, ChangeRule change, boolean withdrawal, Set<Status> stages) {
        return new Policy("SYN-POLICY-1", ReturnTo.SUBMITTER, bpReturn, restart, self, combined, delegated, leave, withdrawal, stages, change);
    }
    static Policy complete() { return Policy.baseline("SYN-POLICY-1"); }
    static State start(Participants p, Policy policy) {
        return submit(9001, 9002, "合成审批测试", "DIRECT", p, policy, 1, "synthetic-submit", TIME, as(p.submitterId()));
    }
    static State start(Policy p) { return start(PEOPLE, p); }
    static Command command(State state, Action action, long dataRevision, String requestId, String comment) {
        return new Command(action, state.version(), state.stage(), requestId, comment, dataRevision, TIME.plusSeconds(state.version()));
    }
    static State act(State state, Action action, String actor, String requestId) {
        return apply(state, command(state, action, state.dataRevision(), requestId, "合成办理说明"), as(actor));
    }
    static Policy resume(ReturnTo target) { return policy(Restart.FROM_RETURNED_STAGE, target, Permission.DENY, Permission.DENY,
            Permission.DENY, Permission.DENY, ChangeRule.RESTART_FROM_LEADER, true, Set.of(Status.BP_PENDING)); }
    public static void main(String[] args) {
        State initial = start(complete());
        check(initial.status() == Status.LEADER_PENDING && initial.assigneeId().equals("SYN-LEADER"), "Leader first");
        fails("APPROVAL_INCOMPLETE", () -> requireReadyForTeam(initial, 1));
        fails("FORBIDDEN", () -> act(initial, Action.APPROVE, "SYN-BP", "bp-before-leader"));
        fails("FORBIDDEN", () -> act(initial, Action.APPROVE, "SYN-OTHER", "stranger"));
        fails("STALE_STAGE", () -> apply(initial, new Command(Action.APPROVE, 1, Stage.BP, "skip", "", 1, TIME), as("SYN-BP")));
        fails("DUPLICATE_REQUEST", () -> act(initial, Action.APPROVE, "SYN-LEADER", "synthetic-submit"));
        State leader = act(initial, Action.APPROVE, "SYN-LEADER", "leader-ok");
        check(initial.version() == 1 && initial.history().size() == 1, "Input remains immutable");
        check(leader.status() == Status.BP_PENDING && leader.leaderApproved() && !leader.bpApproved(), "Only BP opens after leader");
        fails("APPROVAL_INCOMPLETE", () -> requireReadyForTeam(leader, 1));
        fails("DUPLICATE_REQUEST", () -> act(leader, Action.APPROVE, "SYN-BP", "leader-ok"));
        fails("STALE_VERSION", () -> apply(leader, command(initial, Action.APPROVE, 1, "stale", ""), as("SYN-LEADER")));
        fails("FORBIDDEN", () -> act(leader, Action.APPROVE, "SYN-LEADER", "leader-twice"));
        State ready = act(leader, Action.APPROVE, "SYN-BP", "bp-ok");
        requireReadyForTeam(ready, 1); checks++;
        check(ready.status() == Status.READY_FOR_TEAM && ready.history().size() == 3 && ready.assigneeId().isEmpty(), "Ready has two recorded approvals");
        fails("INVALID_TRANSITION", () -> act(ready, Action.APPROVE, "SYN-BP", "extra-approval"));
        fails("INVALID_TRANSITION", () -> act(ready, Action.WITHDRAW, "SYN-SUBMITTER", "late-withdraw"));
        fails("DOCUMENT_CHANGED", () -> requireReadyForTeam(ready, 2));
        fails("DOCUMENT_CHANGED", () -> apply(leader, command(leader, Action.APPROVE, 2, "edit-bypass", ""), as("SYN-BP")));
        fails("EXTERNAL_APPROVAL_REQUIRED", () -> submit(1, 2, "", "BID", PEOPLE, complete(), 1, "bid", TIME, as("SYN-SUBMITTER")));
        fails("CONFIGURATION_REQUIRED", () -> new Participants("SYN-SUBMITTER", "", "SYN-BP"));
        fails("FORBIDDEN", () -> submit(1, 2, "", "DIRECT", PEOPLE, complete(), 1, "spoof", TIME, as("SYN-OTHER")));
        fails("INVALID_INPUT", () -> apply(initial, new Command(Action.APPROVE, 1, Stage.LEADER, "time", "", 1, TIME.minusSeconds(1)), as("SYN-LEADER")));

        State unknown = start(Policy.unconfigured("SYN-UNKNOWN"));
        State unknownLeader = act(unknown, Action.APPROVE, "SYN-LEADER", "ordinary-approval");
        check(unknownLeader.status() == Status.BP_PENDING, "Unknown exception rules do not block ordinary distinct reviewers");
        fails("CONFIGURATION_REQUIRED", () -> act(unknown, Action.RETURN, "SYN-LEADER", "missing-return"));
        fails("CONFIGURATION_REQUIRED", () -> act(unknown, Action.WITHDRAW, "SYN-SUBMITTER", "missing-withdraw"));
        check(actions(unknown, as("SYN-LEADER"), 1).stream().anyMatch(a -> a.action() == Action.RETURN && a.code().equals("CONFIGURATION_REQUIRED") && !a.enabled()), "UI surfaces missing rule");
        fails("FORBIDDEN", () -> act(unknown, Action.WITHDRAW, "SYN-OTHER", "unknown-not-authority"));
        fails("COMMENT_REQUIRED", () -> apply(initial, command(initial, Action.RETURN, 1, "empty-return", "  "), as("SYN-LEADER")));
        State returnedLeader = act(initial, Action.RETURN, "SYN-LEADER", "leader-return");
        check(returnedLeader.status() == Status.RETURNED && returnedLeader.returnTargetId().equals("SYN-SUBMITTER"), "Leader returns to configured submitter");
        fails("FORBIDDEN", () -> act(returnedLeader, Action.RESUBMIT, "SYN-BP", "wrong-retry"));
        fails("INVALID_TRANSITION", () -> act(returnedLeader, Action.APPROVE, "SYN-LEADER", "approve-returned"));
        State retryLeader = act(returnedLeader, Action.RESUBMIT, "SYN-SUBMITTER", "retry-leader");
        check(retryLeader.status() == Status.LEADER_PENDING && retryLeader.round() == 2 && !retryLeader.leaderApproved(), "Restart clears evidence");
        fails("DUPLICATE_REQUEST", () -> act(retryLeader, Action.APPROVE, "SYN-LEADER", "leader-return"));

        State returnedBp = act(leader, Action.RETURN, "SYN-BP", "bp-return");
        State retryBpFromStart = act(returnedBp, Action.RESUBMIT, "SYN-SUBMITTER", "bp-retry");
        check(retryBpFromStart.status() == Status.LEADER_PENDING && !retryBpFromStart.leaderApproved(), "BP return restart policy honored");
        State resumable = act(act(start(resume(ReturnTo.LEADER)), Action.APPROVE, "SYN-LEADER", "r-leader"), Action.RETURN, "SYN-BP", "r-return");
        check(resumable.returnTargetId().equals("SYN-LEADER"), "BP configurable target leader");
        fails("FORBIDDEN", () -> act(resumable, Action.RESUBMIT, "SYN-SUBMITTER", "r-wrong-person"));
        State resumed = act(resumable, Action.RESUBMIT, "SYN-LEADER", "r-resubmit");
        check(resumed.status() == Status.BP_PENDING && resumed.leaderApproved(), "Unchanged explicit resume retains leader evidence");
        State resumedReady = act(resumed, Action.APPROVE, "SYN-BP", "r-final"); requireReadyForTeam(resumedReady, 1); checks++;
        State changed = apply(resumable, command(resumable, Action.RESUBMIT, 2, "r-changed", "修改内容"), as("SYN-LEADER"));
        check(changed.status() == Status.LEADER_PENDING && !changed.leaderApproved() && changed.dataRevision() == 2, "Changed revision overrides BP resume with explicit restart policy");
        fails("INVALID_INPUT", () -> apply(changed, command(changed, Action.REVISE, 1, "rollback", "回退"), as("SYN-SUBMITTER")));
        State revised = apply(leader, command(leader, Action.REVISE, 2, "revise", "合成字段变更"), as("SYN-SUBMITTER"));
        check(revised.status() == Status.LEADER_PENDING && !revised.leaderApproved(), "Pending edit clears prior approval");
        fails("CONFIGURATION_REQUIRED", () -> apply(unknownLeader, command(unknownLeader, Action.REVISE, 2, "revise-missing", "修改"), as("SYN-SUBMITTER")));
        State withdrawn = act(leader, Action.WITHDRAW, "SYN-SUBMITTER", "withdraw");
        check(withdrawn.status() == Status.WITHDRAWN && !withdrawn.leaderApproved(), "Withdraw invalidates evidence");
        State afterWithdraw = act(withdrawn, Action.RESUBMIT, "SYN-SUBMITTER", "withdraw-retry");
        check(afterWithdraw.status() == Status.LEADER_PENDING, "Configured restart after withdraw");
        fails("INVALID_TRANSITION", () -> act(withdrawn, Action.WITHDRAW, "SYN-SUBMITTER", "withdraw-twice"));

        State self = start(new Participants("SYN-SUBMITTER", "SYN-SUBMITTER", "SYN-BP"), Policy.unconfigured("SYN-SELF"));
        fails("CONFIGURATION_REQUIRED", () -> act(self, Action.APPROVE, "SYN-SUBMITTER", "self"));
        State deniedSelf = start(new Participants("SYN-SUBMITTER", "SYN-SUBMITTER", "SYN-BP"), complete());
        fails("POLICY_DENIED", () -> act(deniedSelf, Action.APPROVE, "SYN-SUBMITTER", "self-denied"));
        State combined = start(new Participants("SYN-SUBMITTER", "SYN-LEADER", "SYN-LEADER"), Policy.unconfigured("SYN-COMBINED"));
        fails("CONFIGURATION_REQUIRED", () -> act(combined, Action.APPROVE, "SYN-LEADER", "combined"));
        Policy allowed = policy(Restart.FROM_LEADER, ReturnTo.SUBMITTER, Permission.ALLOW, Permission.ALLOW,
                Permission.ALLOW, Permission.ALLOW, ChangeRule.RESTART_FROM_LEADER, true, Set.of(Status.LEADER_PENDING));
        State allowedCombined = start(new Participants("SYN-SUBMITTER", "SYN-LEADER", "SYN-LEADER"), allowed);
        State firstSame = act(allowedCombined, Action.APPROVE, "SYN-LEADER", "same-1");
        check(firstSame.status() == Status.BP_PENDING, "Combined roles still require separate steps");
        State secondSame = act(firstSame, Action.APPROVE, "SYN-LEADER", "same-2"); requireReadyForTeam(secondSame, 1); checks++;
        Access delegate = new Access("SYN-DELEGATE", DIRECTORY, Map.of(Stage.LEADER, "SYN-DELEGATE", Stage.BP, "SYN-DELEGATE"));
        fails("CONFIGURATION_REQUIRED", () -> apply(unknown, command(unknown, Action.APPROVE, 1, "proxy-unknown", ""), delegate));
        fails("POLICY_DENIED", () -> apply(initial, command(initial, Action.APPROVE, 1, "proxy-denied", ""), delegate));
        State allowedDelegated = start(allowed);
        State delegated = apply(allowedDelegated, command(allowedDelegated, Action.APPROVE, 1, "proxy-ok", "合成代理"), delegate);
        check(delegated.history().get(1).onBehalfOf().equals("SYN-LEADER") && delegated.history().get(1).actorId().equals("SYN-DELEGATE"), "Audit records actor and principal");
        Policy proxySeparate = policy(Restart.FROM_LEADER, ReturnTo.SUBMITTER, Permission.DENY, Permission.DENY,
                Permission.ALLOW, Permission.UNCONFIGURED, ChangeRule.UNCONFIGURED, false, Set.of());
        State firstProxy = start(proxySeparate);
        State afterProxy = apply(firstProxy, command(firstProxy, Action.APPROVE, 1, "proxy-first", ""), delegate);
        fails("POLICY_DENIED", () -> apply(afterProxy, command(afterProxy, Action.APPROVE, 1, "proxy-both", ""), delegate));
        Map<String, Activity> inactive = new HashMap<>(DIRECTORY); inactive.put("SYN-LEADER", Activity.INACTIVE);
        fails("FORBIDDEN", () -> apply(initial, command(initial, Action.APPROVE, 1, "inactive", ""), new Access("SYN-LEADER", inactive, Map.of())));
        fails("CONFIGURATION_REQUIRED", () -> apply(firstProxy, command(firstProxy, Action.APPROVE, 1, "inactive-proxy", ""), new Access("SYN-DELEGATE", inactive, Map.of(Stage.LEADER, "SYN-DELEGATE"))));
        fails("CONFIGURATION_REQUIRED", () -> apply(initial, command(initial, Action.APPROVE, 1, "missing-active", ""), new Access("SYN-LEADER", Map.of(), Map.of())));
        fails("CONFIGURATION_REQUIRED", () -> act(start(proxySeparate), Action.WITHDRAW, "SYN-SUBMITTER", "dummy"));
        Policy noWithdraw = policy(Restart.UNCONFIGURED, ReturnTo.SUBMITTER, Permission.DENY, Permission.DENY,
                Permission.DENY, Permission.DENY, ChangeRule.DENY, true, Set.of());
        State deniedWithdrawal = start(noWithdraw);
        fails("POLICY_DENIED", () -> act(deniedWithdrawal, Action.WITHDRAW, "SYN-SUBMITTER", "withdraw-denied"));
        State noRestart = act(deniedWithdrawal, Action.RETURN, "SYN-LEADER", "no-restart-return");
        fails("CONFIGURATION_REQUIRED", () -> act(noRestart, Action.RESUBMIT, "SYN-SUBMITTER", "no-restart"));
        fails("POLICY_DENIED", () -> apply(deniedWithdrawal, command(deniedWithdrawal, Action.REVISE, 2, "no-change", "change"), as("SYN-SUBMITTER")));
        State onlyReturnResume = act(act(start(resume(ReturnTo.SUBMITTER)), Action.APPROVE, "SYN-LEADER", "w-leader"), Action.WITHDRAW, "SYN-SUBMITTER", "w-withdraw");
        fails("CONFIGURATION_REQUIRED", () -> act(onlyReturnResume, Action.RESUBMIT, "SYN-SUBMITTER", "w-no-rule"));
        List<Event> corrupt = List.of(initial.history().get(0), new Event(2, "corrupt", Action.RETURN, "SYN-LEADER", "", Status.LEADER_PENDING, Status.BP_PENDING, Stage.LEADER, "", 1, TIME));
        fails("INVALID_STATE", () -> new State(9001, 9002, "", PEOPLE, complete(), Status.BP_PENDING, 2, 1, 1, true, false, Stage.NONE, "", corrupt));
        try { initial.history().clear(); throw new AssertionError("Mutable audit"); } catch (UnsupportedOperationException expected) { checks++; }
        Map<String, Activity> mutable = new HashMap<>(DIRECTORY);
        Access copied = new Access("SYN-LEADER", mutable, Map.of()); mutable.clear();
        check(copied.activity().size() == DIRECTORY.size(), "Directory defensively copied");
        String json = Json.write(view(ready, as("SYN-BP"), 1));
        Map<String, Object> parsed = Json.parseMap(json);
        check("READY_FOR_TEAM".equals(parsed.get("status")) && parsed.get("history") instanceof List, "UI projection serializes via existing Json");
        check(actions(ready, as("SYN-BP"), 1).isEmpty(), "Completed workflow has no mutation actions");
        check(initial.version() == 1 && initial.history().size() == 1, "All rejected probes leave original state unchanged");
        check(notices(null, initial).get(0).targetId().equals("SYN-LEADER"), "Submission notifies explicit leader");
        check(notices(initial, leader).get(0).targetId().equals("SYN-BP"), "Leader approval notifies explicit BP");
        List<Notice> completion = notices(leader, ready);
        check(completion.size() == 1 && completion.get(0).targetType() == NoticeTarget.TEAM_QUEUE && completion.get(0).targetId().isEmpty(), "BP approval routes only team queue; submitter notice follows M02 team acceptance");
        check(notices(leader, ready).equals(completion), "Notice keys stable on retry");
        check(notices(leader, returnedBp).get(0).targetId().equals("SYN-SUBMITTER"), "Return notifies configured recipient");
        check(notices(leader, withdrawn).stream().anyMatch(n -> n.kind() == NoticeKind.TASK_CANCELLED && n.targetId().equals("SYN-BP")), "Withdrawal cancels old task notification");
        fails("INVALID_STATE", () -> notices(initial, ready));
        fails("REMINDER_TOO_EARLY", () -> remind(initial, 1, "reminder-early", TIME.plusSeconds(86399), as("SYN-SUBMITTER"), List.of()));
        Reminder reminder = remind(initial, 1, "reminder-1", TIME.plusSeconds(86400), as("SYN-SUBMITTER"), List.of());
        check(reminder.recipientId().equals("SYN-LEADER"), "Manual reminder targets current stage only");
        fails("FORBIDDEN", () -> remind(initial, 1, "reminder-other", TIME.plusSeconds(86400), as("SYN-OTHER"), List.of()));
        fails("FORBIDDEN", () -> remind(initial, 1, "reminder-inactive", TIME.plusSeconds(86400), new Access("SYN-SUBMITTER", inactive, Map.of()), List.of()));
        fails("DUPLICATE_REQUEST", () -> remind(initial, 1, "reminder-1", TIME.plusSeconds(172800), as("SYN-SUBMITTER"), List.of(reminder)));
        fails("REMINDER_TOO_EARLY", () -> remind(initial, 1, "reminder-2", TIME.plusSeconds(172799), as("SYN-SUBMITTER"), List.of(reminder)));
        check(remind(initial, 1, "reminder-2", TIME.plusSeconds(172800), as("SYN-SUBMITTER"), List.of(reminder)).eventVersion() == 1, "24-hour boundary allows one new reminder without advancing approval");
        fails("STALE_VERSION", () -> remind(leader, 1, "reminder-stale", TIME.plusSeconds(172800), as("SYN-SUBMITTER"), List.of()));
        fails("INVALID_TRANSITION", () -> remind(ready, ready.version(), "reminder-done", TIME.plusSeconds(172800), as("SYN-SUBMITTER"), List.of()));
        System.out.println("M03 ApprovalWorkflow: " + checks + " synthetic checks passed; no DB/network/production input");
    }
}
