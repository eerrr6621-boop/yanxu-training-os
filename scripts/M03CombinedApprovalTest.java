package com.training;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.training.ApprovalWorkflow.*;

/** Synthetic-only regression suite for explicitly configured single-event dual-duty review. */
public final class M03CombinedApprovalTest {
    private static final String S = "SYN-SUBMITTER", L = "SYN-LEADER", B = "SYN-BP";
    private static final String C = "SYN-COMBINED", X = "SYN-STRANGER", D = "SYN-DELEGATE";
    private static final String ORG = "SYN-ORG-001";
    private static final Instant T = Instant.parse("2026-09-22T01:00:00Z");
    private static final Participants DISTINCT = new Participants(S, L, B);
    private static final Participants SHARED = new Participants(S, C, C);
    private static int checks, sequence;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        run("ordinary sequential baseline", M03CombinedApprovalTest::ordinarySequential);
        run("legacy combined ALLOW remains sequential", M03CombinedApprovalTest::legacyCompatibility);
        run("explicit configuration and scope", M03CombinedApprovalTest::configuration);
        run("single event carries two responsibilities", M03CombinedApprovalTest::combinedSuccess);
        run("actor and activity authorization", M03CombinedApprovalTest::authorization);
        run("return and resubmit", M03CombinedApprovalTest::returnAndResubmit);
        run("withdraw and restart", M03CombinedApprovalTest::withdrawAndRestart);
        run("content changes and stale commands", M03CombinedApprovalTest::revisionsAndStaleCommands);
        run("snapshot evidence forgery", M03CombinedApprovalTest::snapshotForgery);
        run("notices and public view", M03CombinedApprovalTest::noticesAndView);
        if (!failures.isEmpty()) {
            throw new AssertionError("M03CombinedApprovalTest: " + failures.size() + " failure(s) in " + checks
                    + " checks:\n - " + String.join("\n - ", failures));
        }
        System.out.println("M03CombinedApprovalTest: " + checks + " checks passed (SYN fixtures only)");
    }

    private static void run(String label, Runnable body) {
        try { body.run(); }
        catch (Throwable failure) { failures.add(label + " unexpectedly threw " + failure); }
    }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) failures.add(label);
    }
    private static void eq(Object expected, Object actual, String label) {
        check(Objects.equals(expected, actual), label + " expected=" + expected + " actual=" + actual);
    }
    private static void fails(String code, Runnable operation, String label) {
        checks++;
        try { operation.run(); failures.add(label + " did not reject (expected " + code + ")"); }
        catch (Failure failure) {
            if (!code.equals(failure.code)) failures.add(label + " expected=" + code + " actual=" + failure.code);
        }
        catch (Throwable failure) { failures.add(label + " threw unexpected " + failure); }
    }
    private static String requestId() { return "SYN-REQUEST-" + (++sequence); }
    private static Access access(String actor) {
        return new Access(actor, Map.of(S, Activity.ACTIVE, L, Activity.ACTIVE, B, Activity.ACTIVE,
                C, Activity.ACTIVE, X, Activity.ACTIVE, D, Activity.ACTIVE), Map.of());
    }
    private static CombinedAssignment assignment() {
        return new CombinedAssignment(ORG, C, "SYN-DIRECTORY-v1", "SYN-EVIDENCE-dual-duties");
    }
    private static Policy combined() { return Policy.combinedBaseline("SYN-COMBINED-v1", assignment()); }
    private static State start(Participants participants, Policy policy, String organizationCode) {
        return ApprovalWorkflow.submit(71001, 72001, "SYN 合成兼任审批", "DIRECT", participants, policy,
                1, requestId(), T, access(participants.submitterId()), organizationCode);
    }
    private static State start() { return start(SHARED, combined(), ORG); }
    private static Command command(State state, Action action, long revision) {
        return new Command(action, state.version(), state.stage(), requestId(), "SYN 合成办理说明", revision,
                state.history().get(state.history().size() - 1).at().plusSeconds(1));
    }
    private static State act(State state, Action action, String actor) {
        return ApprovalWorkflow.apply(state, command(state, action, state.dataRevision()), access(actor));
    }
    private static Event last(State state) { return state.history().get(state.history().size() - 1); }
    private static State restore(State template, Policy policy, Status status, boolean leader, boolean bp, List<Event> history) {
        return new State(template.id(), template.businessId(), template.title(), template.participants(), policy,
                status, template.version(), template.dataRevision(), template.round(), leader, bp,
                template.returnedStage(), template.returnTargetId(), history);
    }
    private static List<Event> replacingLast(State state, Event replacement) {
        List<Event> events = new ArrayList<>(state.history()); events.set(events.size() - 1, replacement); return events;
    }
    private static State restoreReturnMetadata(State template, Stage returnedStage, String target) {
        return new State(template.id(), template.businessId(), template.title(), template.participants(), template.policy(),
                template.status(), template.version(), template.dataRevision(), template.round(), template.leaderApproved(),
                template.bpApproved(), returnedStage, target, template.history());
    }
    private static Event altered(Event event, Action action, String actor, String onBehalfOf, Status to, Stage stage) {
        return new Event(event.version(), event.requestId(), action, actor, onBehalfOf, event.from(), to, stage,
                event.comment(), event.dataRevision(), event.at());
    }

    private static void ordinarySequential() {
        State initial = start(DISTINCT, Policy.baseline("SYN-ORDINARY-v1"), ORG);
        eq(ReviewMode.SEQUENTIAL, initial.policy().reviewMode(), "ordinary mode remains sequential");
        eq(Status.LEADER_PENDING, initial.status(), "ordinary first stage");
        fails("FORBIDDEN", () -> act(initial, Action.APPROVE, B), "BP cannot skip leader");
        fails("CONFIGURATION_REQUIRED", () -> act(initial, Action.APPROVE_COMBINED, L), "two-person workflow cannot use combined action");
        State leader = act(initial, Action.APPROVE, L);
        eq(Status.BP_PENDING, leader.status(), "ordinary first approval opens BP");
        check(leader.leaderApproved() && !leader.bpApproved(), "ordinary first approval has only leader evidence");
        eq(List.of(Stage.LEADER), last(leader).responsibilities(), "ordinary first responsibilities");
        State ready = act(leader, Action.APPROVE, B);
        eq(3L, ready.version(), "ordinary two approvals require two increments");
        eq(List.of(Stage.BP), last(ready).responsibilities(), "ordinary second responsibilities");
        eq(Status.READY_FOR_TEAM, ready.status(), "ordinary completed status");
        ApprovalWorkflow.requireReadyForTeam(ready, 1);
        eq(3, ready.history().size(), "ordinary preserves submit plus two events");
    }

    private static void legacyCompatibility() {
        Policy legacy = new Policy("SYN-LEGACY-ALLOW", ReturnTo.SUBMITTER, ReturnTo.SUBMITTER,
                Restart.FROM_LEADER, Permission.DENY, Permission.ALLOW, Permission.DENY, Permission.DENY,
                true, Set.of(Status.LEADER_PENDING, Status.BP_PENDING, Status.RETURNED), ChangeRule.RESTART_FROM_LEADER);
        State initial = start(SHARED, legacy, ORG);
        eq(ReviewMode.SEQUENTIAL, legacy.reviewMode(), "old 11-argument ALLOW must not opt in to single approval");
        eq(null, legacy.combinedAssignment(), "legacy has no frozen combined assignment");
        fails("CONFIGURATION_REQUIRED", () -> act(initial, Action.APPROVE_COMBINED, C), "legacy combined action requires explicit configuration");
        State first = act(initial, Action.APPROVE, C);
        eq(Status.BP_PENDING, first.status(), "legacy same person still reaches BP pending");
        check(!first.bpApproved(), "legacy first approval cannot silently grant BP evidence");
        fails("APPROVAL_INCOMPLETE", () -> ApprovalWorkflow.requireReadyForTeam(first, 1), "legacy team handoff before second click rejected");
        State second = act(first, Action.APPROVE, C);
        eq(Status.READY_FOR_TEAM, second.status(), "legacy second same-person approval completes");
        eq(3L, second.version(), "legacy retains two action events");
        eq(Action.APPROVE, last(second).action(), "legacy audit does not auto-upgrade action");
        ApprovalWorkflow.requireReadyForTeam(second, 1);
    }

    private static void configuration() {
        fails("CONFIGURATION_REQUIRED", () -> Policy.combinedBaseline("SYN-MISSING", null), "combined policy requires assignment");
        fails("INVALID_INPUT", () -> new CombinedAssignment(ORG, C, "", "SYN-EVIDENCE"), "assignment requires directory version");
        fails("INVALID_INPUT", () -> new CombinedAssignment(ORG, C, "SYN-DIR", ""), "assignment requires evidence reference");
        fails("CONFIGURATION_REQUIRED", () -> ApprovalWorkflow.submit(71001, 72001, "SYN", "DIRECT", SHARED,
                combined(), 1, requestId(), T, access(S)), "old submit overload cannot bypass organization binding");
        fails("CONFIGURATION_REQUIRED", () -> start(SHARED, combined(), ""), "blank organization rejected");
        fails("FORBIDDEN", () -> start(SHARED, combined(), "SYN-OTHER-ORG"), "assignment for another organization rejected");
        fails("INVALID_STATE", () -> start(DISTINCT, combined(), ORG), "two distinct principals cannot be combined");
        Policy wrongPerson = Policy.combinedBaseline("SYN-WRONG-PERSON", new CombinedAssignment(ORG, X, "SYN-DIR", "SYN-REF"));
        fails("INVALID_STATE", () -> start(SHARED, wrongPerson, ORG), "assignment person must match both duties");
        fails("POLICY_DENIED", () -> start(new Participants(C, C, C), combined(), ORG), "combined approver cannot submit own work");
        State notAllowed = start(SHARED, Policy.baseline("SYN-NO-COMBINED"), ORG);
        fails("POLICY_DENIED", () -> act(notAllowed, Action.APPROVE, C), "ordinary default does not authorize combined roles");
        fails("INVALID_POLICY", () -> new Policy("SYN-UNSAFE", ReturnTo.SUBMITTER, ReturnTo.SUBMITTER,
                Restart.FROM_LEADER, Permission.ALLOW, Permission.ALLOW, Permission.DENY, Permission.DENY,
                true, Set.of(Status.LEADER_PENDING), ChangeRule.RESTART_FROM_LEADER, assignment()), "combined policy cannot permit self-approval");
        fails("INVALID_POLICY", () -> new Policy("SYN-UNSAFE-PROXY", ReturnTo.SUBMITTER, ReturnTo.SUBMITTER,
                Restart.FROM_LEADER, Permission.DENY, Permission.ALLOW, Permission.ALLOW, Permission.DENY,
                true, Set.of(Status.LEADER_PENDING), ChangeRule.RESTART_FROM_LEADER, assignment()), "combined policy cannot permit delegation");
    }

    private static void combinedSuccess() {
        State initial = start();
        eq(ReviewMode.SINGLE_EXPLICIT, initial.policy().reviewMode(), "explicit review mode");
        eq(C, initial.assigneeId(), "one initial combined assignee");
        fails("EXPLICIT_COMBINED_REQUIRED", () -> act(initial, Action.APPROVE, C), "ordinary approve cannot silently count both duties");
        fails("APPROVAL_INCOMPLETE", () -> ApprovalWorkflow.requireReadyForTeam(initial, 1), "unapproved combined task cannot reach team");
        Command approve = command(initial, Action.APPROVE_COMBINED, 1);
        State ready = ApprovalWorkflow.apply(initial, approve, access(C));
        eq(Status.READY_FOR_TEAM, ready.status(), "one explicit action reaches ready");
        eq(Stage.NONE, ready.stage(), "no lingering BP stage");
        eq(2L, ready.version(), "one approval increments once");
        eq(2, ready.history().size(), "one combined approval writes exactly one event");
        check(ready.leaderApproved() && ready.bpApproved(), "single event establishes both responsibilities");
        eq(List.of(Stage.LEADER, Stage.BP), last(ready).responsibilities(), "combined audit identifies both responsibilities");
        eq(Action.APPROVE_COMBINED, last(ready).action(), "combined action is explicit in audit");
        eq(Stage.LEADER, last(ready).stage(), "combined event originates at leader node");
        eq("", last(ready).onBehalfOf(), "combined event is direct, never proxy");
        eq(C, last(ready).actorId(), "combined actor recorded once");
        eq(1L, ready.dataRevision(), "combined action does not alter content revision");
        eq(Status.LEADER_PENDING, initial.status(), "original immutable state remains pending");
        ApprovalWorkflow.requireReadyForTeam(ready, 1);
        fails("DOCUMENT_CHANGED", () -> ApprovalWorkflow.requireReadyForTeam(ready, 2), "changed content blocks team handoff");
        fails("DUPLICATE_REQUEST", () -> ApprovalWorkflow.apply(ready, approve, access(C)), "replayed combined request is rejected");
        fails("INVALID_TRANSITION", () -> act(ready, Action.APPROVE_COMBINED, C), "fresh second combined request is rejected");
        fails("INVALID_TRANSITION", () -> act(ready, Action.WITHDRAW, S), "ready combined work cannot be withdrawn");
    }

    private static void authorization() {
        State initial = start();
        fails("FORBIDDEN", () -> act(initial, Action.APPROVE_COMBINED, X), "stranger cannot combine approval");
        fails("FORBIDDEN", () -> act(initial, Action.APPROVE_COMBINED, S), "submitter is not combined reviewer");
        Access inactive = new Access(C, Map.of(C, Activity.INACTIVE, S, Activity.ACTIVE), Map.of());
        fails("FORBIDDEN", () -> ApprovalWorkflow.apply(initial, command(initial, Action.APPROVE_COMBINED, 1), inactive), "inactive combined approver rejected");
        Access unknown = new Access(C, Map.of(S, Activity.ACTIVE), Map.of());
        fails("CONFIGURATION_REQUIRED", () -> ApprovalWorkflow.apply(initial, command(initial, Action.APPROVE_COMBINED, 1), unknown), "unknown approver activity fails closed");
        Access proxy = new Access(D, Map.of(C, Activity.ACTIVE, D, Activity.ACTIVE), Map.of(Stage.LEADER, D));
        fails("POLICY_DENIED", () -> ApprovalWorkflow.apply(initial, command(initial, Action.APPROVE_COMBINED, 1), proxy), "even listed delegate cannot perform combined approval");
        Access absentProxy = new Access(D, Map.of(C, Activity.INACTIVE, D, Activity.ACTIVE), Map.of(Stage.LEADER, D));
        fails("POLICY_DENIED", () -> ApprovalWorkflow.apply(initial, command(initial, Action.APPROVE_COMBINED, 1), absentProxy), "inactive principal cannot be bypassed by proxy");
        fails("FORBIDDEN", () -> ApprovalWorkflow.apply(initial, command(initial, Action.APPROVE_COMBINED, 1), access(D)), "unlisted proxy is stranger");
    }

    private static void returnAndResubmit() {
        State initial = start();
        Command blankReason = new Command(Action.RETURN, 1, Stage.LEADER, requestId(), " ", 1, T.plusSeconds(1));
        fails("COMMENT_REQUIRED", () -> ApprovalWorkflow.apply(initial, blankReason, access(C)), "combined return requires reason");
        State returned = act(initial, Action.RETURN, C);
        eq(Status.RETURNED, returned.status(), "combined reviewer can return");
        eq(S, returned.assigneeId(), "combined return goes to submitter");
        eq(Stage.LEADER, returned.returnedStage(), "combined return remembers initial node");
        check(!returned.leaderApproved() && !returned.bpApproved(), "returned combined task carries no approved responsibility");
        fails("FORBIDDEN", () -> act(returned, Action.RESUBMIT, C), "reviewer cannot resubmit for submitter");
        State resubmitted = act(returned, Action.RESUBMIT, S);
        eq(Status.LEADER_PENDING, resubmitted.status(), "unchanged resubmission restarts at leader");
        eq(1L, resubmitted.dataRevision(), "unchanged saved content may be resubmitted");
        eq(2, resubmitted.round(), "resubmission opens next review round");
        eq(C, resubmitted.assigneeId(), "resubmitted task assigned once to dual-duty reviewer");
        State ready = act(resubmitted, Action.APPROVE_COMBINED, C);
        eq(4L, ready.version(), "return plus resubmit plus combined approve each increment once");
        ApprovalWorkflow.requireReadyForTeam(ready, 1);
    }

    private static void withdrawAndRestart() {
        State initial = start();
        fails("FORBIDDEN", () -> act(initial, Action.WITHDRAW, C), "reviewer cannot withdraw submitted work");
        State withdrawn = act(initial, Action.WITHDRAW, S);
        eq(Status.WITHDRAWN, withdrawn.status(), "pending combined task can be withdrawn");
        eq(S, withdrawn.assigneeId(), "withdrawn work belongs to submitter");
        fails("INVALID_TRANSITION", () -> act(withdrawn, Action.APPROVE_COMBINED, C), "withdrawn task cannot be approved");
        State restarted = act(withdrawn, Action.RESUBMIT, S);
        eq(Status.LEADER_PENDING, restarted.status(), "withdrawn combined work restarts at leader");
        check(!restarted.leaderApproved() && !restarted.bpApproved(), "withdrawn restart has no stale approvals");
        State returned = act(start(), Action.RETURN, C);
        State returnedWithdrawn = act(returned, Action.WITHDRAW, S);
        eq(Status.WITHDRAWN, returnedWithdrawn.status(), "returned work can also be withdrawn");
        eq(Stage.NONE, returnedWithdrawn.returnedStage(), "withdraw clears returned stage");
        eq("", returnedWithdrawn.returnTargetId(), "withdraw clears return recipient");
    }

    private static void revisionsAndStaleCommands() {
        State initial = start();
        Command oldApproval = command(initial, Action.APPROVE_COMBINED, 1);
        State revised = ApprovalWorkflow.apply(initial, command(initial, Action.REVISE, 2), access(S));
        eq(Status.LEADER_PENDING, revised.status(), "editing restarts combined leader node");
        eq(2L, revised.dataRevision(), "explicit revision advances saved content version");
        eq(2, revised.round(), "editing opens a fresh review round");
        check(!revised.leaderApproved() && !revised.bpApproved(), "editing clears both responsibility approvals");
        fails("STALE_VERSION", () -> ApprovalWorkflow.apply(revised, oldApproval, access(C)), "old combined approval loses optimistic lock after edit");
        fails("DOCUMENT_CHANGED", () -> ApprovalWorkflow.apply(revised, command(revised, Action.APPROVE_COMBINED, 1), access(C)), "stale document revision rejected even with fresh state version");
        Command wrongStage = new Command(Action.APPROVE_COMBINED, revised.version(), Stage.BP, requestId(), "SYN", 2, T.plusSeconds(2));
        fails("STALE_STAGE", () -> ApprovalWorkflow.apply(revised, wrongStage, access(C)), "invented BP approval stage rejected");
        fails("FORBIDDEN", () -> ApprovalWorkflow.apply(initial, command(initial, Action.REVISE, 2), access(C)), "combined reviewer cannot alter submitter content");
        fails("INVALID_INPUT", () -> ApprovalWorkflow.apply(initial, command(initial, Action.REVISE, 1), access(S)), "revision must actually advance");
        State ready = act(revised, Action.APPROVE_COMBINED, C);
        eq(2L, last(ready).dataRevision(), "combined evidence belongs to latest content");
        ApprovalWorkflow.requireReadyForTeam(ready, 2);
        State returned = act(start(), Action.RETURN, C);
        State changedResubmit = ApprovalWorkflow.apply(returned, command(returned, Action.RESUBMIT, 2), access(S));
        eq(Status.LEADER_PENDING, changedResubmit.status(), "changed resubmission cannot skip combined review");
        eq(2L, changedResubmit.dataRevision(), "changed resubmission uses saved new revision");
        fails("INVALID_INPUT", () -> ApprovalWorkflow.apply(act(changedResubmit, Action.RETURN, C),
                new Command(Action.RESUBMIT, changedResubmit.version() + 1, Stage.NONE, requestId(), "SYN", 1, T.plusSeconds(9)), access(S)), "resubmission revision cannot roll back");
    }

    private static void snapshotForgery() {
        State initial = start(); State ready = act(initial, Action.APPROVE_COMBINED, C); Event event = last(ready);
        State restored = restore(ready, ready.policy(), ready.status(), true, true, ready.history());
        eq(ready, restored, "valid combined snapshot round-trips");
        fails("INVALID_STATE", () -> restore(ready, ready.policy(), ready.status(), true, false, ready.history()), "snapshot cannot omit BP flag backed by combined event");
        fails("INVALID_STATE", () -> restore(ready, ready.policy(), ready.status(), false, true, ready.history()), "snapshot cannot omit leader flag backed by combined event");
        fails("INVALID_STATE", () -> restore(ready, ready.policy(), ready.status(), true, true,
                replacingLast(ready, altered(event, Action.APPROVE, C, "", Status.READY_FOR_TEAM, Stage.LEADER))), "ordinary approval cannot be forged into both-duty completion");
        fails("INVALID_STATE", () -> restore(ready, ready.policy(), ready.status(), true, true,
                replacingLast(ready, altered(event, Action.APPROVE_COMBINED, X, "", Status.READY_FOR_TEAM, Stage.LEADER))), "combined snapshot rejects stranger actor");
        fails("INVALID_STATE", () -> restore(ready, ready.policy(), ready.status(), true, true,
                replacingLast(ready, altered(event, Action.APPROVE_COMBINED, S, "", Status.READY_FOR_TEAM, Stage.LEADER))), "combined snapshot rejects submitter self approval");
        fails("INVALID_STATE", () -> restore(ready, ready.policy(), ready.status(), true, true,
                replacingLast(ready, altered(event, Action.APPROVE_COMBINED, C, X, Status.READY_FOR_TEAM, Stage.LEADER))), "combined snapshot rejects proxy marker");
        fails("INVALID_STATE", () -> restore(ready, ready.policy(), ready.status(), true, true,
                replacingLast(ready, altered(event, Action.APPROVE_COMBINED, C, "", Status.READY_FOR_TEAM, Stage.BP))), "combined snapshot rejects forged BP origin");
        fails("INVALID_STATE", () -> restore(ready, ready.policy(), Status.BP_PENDING, true, false,
                replacingLast(ready, altered(event, Action.APPROVE_COMBINED, C, "", Status.BP_PENDING, Stage.LEADER))), "combined snapshot cannot contain intermediate BP pending");
        fails("INVALID_STATE", () -> restore(ready, Policy.baseline("SYN-FORGED-POLICY"), ready.status(), true, true, ready.history()), "sequential snapshot cannot borrow combined completion evidence");
        Event submit = last(initial);
        Event badSubmit = altered(submit, Action.SUBMIT, X, "", Status.LEADER_PENDING, Stage.NONE);
        fails("INVALID_STATE", () -> restore(initial, initial.policy(), initial.status(), false, false, List.of(badSubmit)), "snapshot rejects forged initial submitter");
        Event repeatedRequest = new Event(event.version(), submit.requestId(), event.action(), event.actorId(), "", event.from(), event.to(), event.stage(), event.comment(), 1, event.at());
        fails("INVALID_STATE", () -> restore(ready, ready.policy(), ready.status(), true, true, replacingLast(ready, repeatedRequest)), "snapshot rejects duplicated request ID");
        Event implicitRevision = new Event(event.version(), event.requestId(), event.action(), event.actorId(), "", event.from(), event.to(), event.stage(), event.comment(), 2, event.at());
        fails("INVALID_STATE", () -> restore(ready, ready.policy(), ready.status(), true, true, replacingLast(ready, implicitRevision)), "combined audit cannot silently change data revision");
        Event proxySubmit = altered(submit, Action.SUBMIT, S, X, Status.LEADER_PENDING, Stage.NONE);
        fails("INVALID_STATE", () -> restore(initial, initial.policy(), initial.status(), false, false, List.of(proxySubmit)), "combined snapshot rejects delegated initial submission");
        State returned = act(initial, Action.RETURN, C);
        State withdrawn = act(initial, Action.WITHDRAW, S);
        State revised = ApprovalWorkflow.apply(initial, command(initial, Action.REVISE, 2), access(S));
        State resubmitted = act(returned, Action.RESUBMIT, S);
        for (State changed : List.of(returned, withdrawn, revised, resubmitted)) {
            Event original = last(changed);
            Event actorForgery = altered(original, original.action(), X, "", original.to(), original.stage());
            fails("INVALID_STATE", () -> restore(changed, changed.policy(), changed.status(), changed.leaderApproved(), changed.bpApproved(),
                    replacingLast(changed, actorForgery)), "combined snapshot rejects forged actor for " + original.action());
            Event proxyForgery = altered(original, original.action(), original.actorId(), X, original.to(), original.stage());
            fails("INVALID_STATE", () -> restore(changed, changed.policy(), changed.status(), changed.leaderApproved(), changed.bpApproved(),
                    replacingLast(changed, proxyForgery)), "combined snapshot rejects proxy marker for " + original.action());
        }
        fails("INVALID_STATE", () -> restoreReturnMetadata(returned, Stage.LEADER, X), "forged return target cannot grant resubmission to stranger");
        fails("INVALID_STATE", () -> restoreReturnMetadata(returned, Stage.BP, S), "combined returned snapshot cannot invent BP origin");
        fails("INVALID_STATE", () -> restoreReturnMetadata(initial, Stage.NONE, X), "nonreturned combined snapshot cannot retain arbitrary target");
        fails("INVALID_STATE", () -> restoreReturnMetadata(ready, Stage.LEADER, ""), "completed combined snapshot cannot retain returned stage");
    }

    private static void noticesAndView() {
        State initial = start(); State ready = act(initial, Action.APPROVE_COMBINED, C);
        List<Notice> pending = ApprovalWorkflow.notices(null, initial);
        eq(1, pending.size(), "combined submission creates one pending notice");
        eq(NoticeKind.TODO, pending.get(0).kind(), "initial combined notice is to-do");
        eq(C, pending.get(0).targetId(), "initial combined notice targets one reviewer");
        List<Notice> completion = ApprovalWorkflow.notices(initial, ready);
        eq(1, completion.size(), "combined completion creates exactly one notification intent");
        eq(NoticeKind.TEAM_READY, completion.get(0).kind(), "combined completion announces team readiness");
        eq(NoticeTarget.TEAM_QUEUE, completion.get(0).targetType(), "team queue remains separately resolvable");
        eq("", completion.get(0).targetId(), "no fabricated team personnel ID");
        check(completion.stream().noneMatch(n -> n.kind() == NoticeKind.TODO), "no fake BP to-do follows combined approval");
        eq(completion, ApprovalWorkflow.notices(initial, ready), "notice derivation has stable deduplication keys");
        List<Availability> actions = ApprovalWorkflow.actions(initial, access(C), 1);
        check(actions.stream().anyMatch(a -> a.action() == Action.APPROVE_COMBINED && a.enabled()), "authorized reviewer sees explicit combined action");
        check(actions.stream().noneMatch(a -> a.action() == Action.APPROVE), "combined view hides ambiguous ordinary approve action");
        check(ApprovalWorkflow.actions(initial, access(X), 1).stream().noneMatch(Availability::enabled), "stranger has no enabled actions");
        eq("SINGLE_EXPLICIT", ApprovalWorkflow.view(initial, access(C), 1).get("reviewMode"), "public view declares review mode");
        Object historyValue = ApprovalWorkflow.view(ready, access(C), 1).get("history");
        check(historyValue instanceof List<?>, "public audit history is a list");
        List<?> history = (List<?>) historyValue;
        Map<?, ?> lastView = (Map<?, ?>) history.get(history.size() - 1);
        eq(List.of("LEADER", "BP"), lastView.get("responsibilities"), "public audit exports both duty names");
        State ordinary = start(DISTINCT, Policy.baseline("SYN-NOTICE-LEGACY"), ORG);
        List<Notice> ordinaryHandoff = ApprovalWorkflow.notices(ordinary, act(ordinary, Action.APPROVE, L));
        eq(NoticeKind.TODO, ordinaryHandoff.get(0).kind(), "ordinary sequential mode still sends BP pending notice");
        eq(B, ordinaryHandoff.get(0).targetId(), "ordinary handoff goes to distinct BP");
    }
}
