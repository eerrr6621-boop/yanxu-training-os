package com.training;

import java.time.Instant;
import java.time.Duration;
import java.util.*;

/** Pure approval aggregate. Host owns authentication, persistence, locking and notification delivery. */
public final class ApprovalWorkflow {
    private ApprovalWorkflow() {}
    public enum Stage { LEADER, BP, NONE }
    public enum Status { LEADER_PENDING, BP_PENDING, RETURNED, WITHDRAWN, READY_FOR_TEAM }
    public enum Action { SUBMIT, APPROVE, RETURN, RESUBMIT, WITHDRAW, REVISE, APPROVE_COMBINED }
    public enum ReviewMode { SEQUENTIAL, SINGLE_EXPLICIT }
    public enum Permission { ALLOW, DENY, UNCONFIGURED }
    public enum Activity { ACTIVE, INACTIVE, UNKNOWN }
    public enum ReturnTo { SUBMITTER, LEADER, UNCONFIGURED }
    public enum Restart { FROM_LEADER, FROM_RETURNED_STAGE, UNCONFIGURED }
    public enum ChangeRule { RESTART_FROM_LEADER, DENY, UNCONFIGURED }

    public static final class Failure extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public final String code;
        public Failure(String code, String message) { super(message); this.code = code; }
    }
    private static void require(boolean ok, String code, String message) {
        if (!ok) throw new Failure(code, message);
    }
    private static String id(String value, String field) {
        require(value != null && !value.isBlank(), "INVALID_INPUT", field + "不能为空");
        require(value.length() <= 160 && value.equals(value.trim()) &&
                value.chars().noneMatch(Character::isISOControl), "INVALID_INPUT", field + "格式无效");
        return value;
    }
    private static void configured(boolean ok, String field) {
        require(ok, "CONFIGURATION_REQUIRED", field + "待配置");
    }
    private static void permit(Permission value, String field) {
        configured(value != Permission.UNCONFIGURED, field);
        require(value == Permission.ALLOW, "POLICY_DENIED", field + "不允许");
    }
    public record Participants(String submitterId, String leaderId, String bpId) {
        public Participants {
            configured(submitterId != null && !submitterId.isBlank(), "填报人ID");
            configured(leaderId != null && !leaderId.isBlank(), "负责人ID");
            configured(bpId != null && !bpId.isBlank(), "BP ID");
            id(submitterId, "填报人ID"); id(leaderId, "负责人ID"); id(bpId, "BP ID");
        }
        public String forStage(Stage stage) {
            return switch (stage) { case LEADER -> leaderId; case BP -> bpId; case NONE -> ""; };
        }
    }
    /** Trusted M01 facts frozen at submission; not an account grant or a client-supplied role claim. */
    public record CombinedAssignment(String organizationCode, String approverId, String directoryVersion, String evidenceRef) {
        public CombinedAssignment {
            id(organizationCode, "兼任机构编码"); id(approverId, "兼任办理人ID");
            id(directoryVersion, "职责配置版本"); id(evidenceRef, "兼任职责依据");
        }
    }
    /** All exception policies are explicit. No production defaults are inferred. */
    public record Policy(String version, ReturnTo leaderReturnTo, ReturnTo bpReturnTo,
                         Restart resubmitFrom, Permission selfApproval, Permission combinedRoles,
                         Permission delegation, Permission inactiveAssigneeDelegation,
                         boolean withdrawalConfigured, Set<Status> withdrawableStatuses,
                         ChangeRule documentChanges, CombinedAssignment combinedAssignment) {
        /** Historical call sites/snapshots retain sequential semantics, including old combinedRoles=ALLOW. */
        public Policy(String version, ReturnTo leaderReturnTo, ReturnTo bpReturnTo, Restart resubmitFrom,
                      Permission selfApproval, Permission combinedRoles, Permission delegation,
                      Permission inactiveAssigneeDelegation, boolean withdrawalConfigured,
                      Set<Status> withdrawableStatuses, ChangeRule documentChanges) {
            this(version, leaderReturnTo, bpReturnTo, resubmitFrom, selfApproval, combinedRoles, delegation,
                    inactiveAssigneeDelegation, withdrawalConfigured, withdrawableStatuses, documentChanges, null);
        }
        public Policy {
            id(version, "策略版本");
            Objects.requireNonNull(leaderReturnTo); Objects.requireNonNull(bpReturnTo);
            Objects.requireNonNull(resubmitFrom); Objects.requireNonNull(selfApproval);
            Objects.requireNonNull(combinedRoles); Objects.requireNonNull(delegation);
            Objects.requireNonNull(inactiveAssigneeDelegation); Objects.requireNonNull(documentChanges);
            withdrawableStatuses = Set.copyOf(withdrawableStatuses);
            require(!withdrawableStatuses.contains(Status.READY_FOR_TEAM) &&
                    !withdrawableStatuses.contains(Status.WITHDRAWN), "INVALID_POLICY", "交团队后撤回须另行确认跨模块规则");
            require(leaderReturnTo != ReturnTo.LEADER, "INVALID_POLICY", "负责人退回本人不是有效退回目标");
            require(withdrawalConfigured || withdrawableStatuses.isEmpty(), "INVALID_POLICY", "未配置撤回规则不能指定可撤回节点");
            if (combinedAssignment != null) require(selfApproval == Permission.DENY && combinedRoles == Permission.ALLOW &&
                    delegation == Permission.DENY && inactiveAssigneeDelegation == Permission.DENY &&
                    leaderReturnTo == ReturnTo.SUBMITTER && bpReturnTo == ReturnTo.SUBMITTER &&
                    resubmitFrom == Restart.FROM_LEADER && documentChanges == ChangeRule.RESTART_FROM_LEADER,
                    "INVALID_POLICY", "单次兼任策略必须禁自批和代理、退回填报人并从头重审");
        }
        public ReviewMode reviewMode() { return combinedAssignment == null ? ReviewMode.SEQUENTIAL : ReviewMode.SINGLE_EXPLICIT; }
        public static Policy unconfigured(String version) {
            return new Policy(version, ReturnTo.UNCONFIGURED, ReturnTo.UNCONFIGURED, Restart.UNCONFIGURED,
                    Permission.UNCONFIGURED, Permission.UNCONFIGURED, Permission.UNCONFIGURED,
                    Permission.UNCONFIGURED, false, Set.of(), ChangeRule.UNCONFIGURED);
        }
        /** v1 designed after the user's 2026-09-21 delegation; explicit personnel still required. */
        public static Policy baseline(String version) {
            return new Policy(version, ReturnTo.SUBMITTER, ReturnTo.SUBMITTER, Restart.FROM_LEADER,
                    Permission.DENY, Permission.DENY, Permission.DENY, Permission.DENY,
                    true, Set.of(Status.LEADER_PENDING, Status.BP_PENDING, Status.RETURNED),
                    ChangeRule.RESTART_FROM_LEADER);
        }
        /** Opt-in only for a new workflow after both duties and organization scope have been verified by M01. */
        public static Policy combinedBaseline(String version, CombinedAssignment assignment) {
            configured(assignment != null, "明确兼任职责依据");
            return new Policy(version, ReturnTo.SUBMITTER, ReturnTo.SUBMITTER, Restart.FROM_LEADER,
                    Permission.DENY, Permission.ALLOW, Permission.DENY, Permission.DENY,
                    true, Set.of(Status.LEADER_PENDING, Status.RETURNED), ChangeRule.RESTART_FROM_LEADER, assignment);
        }
    }
    /** Supplied by the authenticated host/M01, never constructed from client identity claims. */
    public record Access(String actorId, Map<String, Activity> activity, Map<Stage, String> delegates) {
        public Access {
            id(actorId, "办理人ID"); activity = Map.copyOf(activity); delegates = Map.copyOf(delegates);
            require(!delegates.containsKey(Stage.NONE), "INVALID_INPUT", "无节点不能配置代理");
            delegates.values().forEach(value -> id(value, "代理人ID"));
        }
    }
    public record Command(Action action, long expectedVersion, Stage expectedStage, String requestId,
                          String comment, long dataRevision, Instant at) {
        public Command {
            Objects.requireNonNull(action); Objects.requireNonNull(expectedStage); Objects.requireNonNull(at);
            id(requestId, "请求ID"); require(expectedVersion >= 1 && dataRevision >= 1, "INVALID_INPUT", "版本必须为正整数");
            comment = comment == null ? "" : comment.trim();
            require(comment.length() <= 2000, "INVALID_INPUT", "办理说明不能超过2000字");
        }
    }
    public record Event(long version, String requestId, Action action, String actorId, String onBehalfOf,
                        Status from, Status to, Stage stage, String comment, long dataRevision, Instant at) {
        public Event {
            id(requestId, "请求ID"); id(actorId, "办理人ID"); Objects.requireNonNull(action);
            Objects.requireNonNull(to); Objects.requireNonNull(stage); Objects.requireNonNull(at);
            onBehalfOf = onBehalfOf == null ? "" : onBehalfOf;
            comment = comment == null ? "" : comment;
        }
        public List<Stage> responsibilities() {
            if (action == Action.APPROVE_COMBINED) return List.of(Stage.LEADER, Stage.BP);
            return action == Action.APPROVE ? List.of(stage) : List.of();
        }
    }
    /** Immutable server-owned state. Rehydrate trusted persisted records only, never request bodies. */
    public record State(long id, long businessId, String title, Participants participants, Policy policy,
                        Status status, long version, long dataRevision, int round,
                        boolean leaderApproved, boolean bpApproved, Stage returnedStage,
                        String returnTargetId, List<Event> history) {
        public State {
            require(id > 0 && businessId > 0 && version > 0 && dataRevision > 0 && round > 0,
                    "INVALID_STATE", "单据与流程版本无效");
            Objects.requireNonNull(participants); Objects.requireNonNull(policy); Objects.requireNonNull(status);
            Objects.requireNonNull(returnedStage); title = title == null ? "" : title;
            returnTargetId = returnTargetId == null ? "" : returnTargetId;
            history = List.copyOf(history);
            validateCombinedBinding(participants, policy);
            require(policy.reviewMode() != ReviewMode.SINGLE_EXPLICIT || status != Status.BP_PENDING,
                    "INVALID_STATE", "单次兼任流程不存在待BP第二次审批节点");
            require(!history.isEmpty() && history.size() == version, "INVALID_STATE", "办理记录不完整");
            Set<String> requests = new HashSet<>();
            Status previous = null;
            boolean evidenceLeader = false, evidenceBp = false;
            long previousDataRevision = 0;
            int evidenceRound = 1;
            Instant previousTime = null;
            for (int i = 0; i < history.size(); i++) {
                Event event = history.get(i);
                if (policy.reviewMode() == ReviewMode.SINGLE_EXPLICIT) {
                    boolean review = event.action() == Action.APPROVE_COMBINED || event.action() == Action.RETURN;
                    String expectedActor = review ? participants.leaderId() : participants.submitterId();
                    require(event.actorId().equals(expectedActor) && event.onBehalfOf().isEmpty(),
                            "INVALID_STATE", "兼任流程办理人或代理记录不符");
                    if (event.action() == Action.RETURN || event.action() == Action.REVISE)
                        require(!event.comment().isBlank(), "INVALID_STATE", "退回或变更须有办理原因");
                    if (event.action() == Action.WITHDRAW)
                        require(policy.withdrawalConfigured() && policy.withdrawableStatuses().contains(previous),
                                "INVALID_STATE", "撤回记录与固定策略不符");
                }
                require(event.version() == i + 1 && requests.add(event.requestId()) && event.from() == previous,
                        "INVALID_STATE", "办理记录版本或请求ID冲突");
                if (i == 0) require(event.action() == Action.SUBMIT && event.to() == Status.LEADER_PENDING,
                        "INVALID_STATE", "首次提交必须交负责人");
                require(event.dataRevision() > 0 && event.dataRevision() >= previousDataRevision &&
                        (previousTime == null || !event.at().isBefore(previousTime)), "INVALID_STATE", "内容版本或办理时间倒退");
                require(event.stage() == (previous == null ? Stage.NONE : stageFor(previous)), "INVALID_STATE", "办理记录节点不符");
                if (i > 0) {
                    boolean changed = event.dataRevision() != previousDataRevision;
                    require(!changed || event.action() == Action.REVISE || event.action() == Action.RESUBMIT,
                            "INVALID_STATE", "审批记录不能隐式修改内容版本");
                    switch (event.action()) {
                        case APPROVE -> {
                            require(policy.reviewMode() == ReviewMode.SEQUENTIAL, "INVALID_STATE", "单次兼任流程须有明确合并审批事件");
                            require((previous == Status.LEADER_PENDING && event.to() == Status.BP_PENDING) ||
                                    (previous == Status.BP_PENDING && event.to() == Status.READY_FOR_TEAM && evidenceLeader),
                                    "INVALID_STATE", "审批记录不符合顺序");
                            if (previous == Status.LEADER_PENDING) evidenceLeader = true; else evidenceBp = true;
                        }
                        case APPROVE_COMBINED -> {
                            require(policy.reviewMode() == ReviewMode.SINGLE_EXPLICIT && previous == Status.LEADER_PENDING &&
                                    event.to() == Status.READY_FOR_TEAM && event.actorId().equals(participants.leaderId()) &&
                                    !event.actorId().equals(participants.submitterId()) && event.onBehalfOf().isEmpty(),
                                    "INVALID_STATE", "兼任审批须为本人一次明确办理，且同时承担两项职责");
                            evidenceLeader = true; evidenceBp = true;
                        }
                        case RETURN -> {
                            require(stageFor(previous) != Stage.NONE && event.to() == Status.RETURNED, "INVALID_STATE", "退回记录无效");
                            if (previous == Status.LEADER_PENDING) evidenceLeader = false;
                            evidenceBp = false;
                        }
                        case WITHDRAW -> {
                            require((stageFor(previous) != Stage.NONE || previous == Status.RETURNED) && event.to() == Status.WITHDRAWN,
                                    "INVALID_STATE", "撤回记录无效");
                            evidenceLeader = false; evidenceBp = false;
                        }
                        case RESUBMIT -> {
                            require((previous == Status.RETURNED || previous == Status.WITHDRAWN) &&
                                    (event.to() == Status.LEADER_PENDING || (event.to() == Status.BP_PENDING && evidenceLeader && !changed)),
                                    "INVALID_STATE", "重提记录不能跳过负责人");
                            if (event.to() == Status.LEADER_PENDING) evidenceLeader = false;
                            evidenceBp = false; evidenceRound++;
                        }
                        case REVISE -> {
                            require(stageFor(previous) != Stage.NONE && event.to() == Status.LEADER_PENDING && changed,
                                    "INVALID_STATE", "变更记录无效");
                            evidenceLeader = false; evidenceBp = false; evidenceRound++;
                        }
                        default -> throw new Failure("INVALID_STATE", "流程内不能再次初次提交");
                    }
                }
                previous = event.to(); previousDataRevision = event.dataRevision(); previousTime = event.at();
            }
            require(leaderApproved == evidenceLeader && bpApproved == evidenceBp && round == evidenceRound,
                    "INVALID_STATE", "审批证据与快照标记不一致");
            require(previous == status && history.get(history.size() - 1).dataRevision() == dataRevision,
                    "INVALID_STATE", "状态与办理记录不一致");
            require(!bpApproved || leaderApproved, "INVALID_STATE", "BP通过前必须负责人通过");
            require(status != Status.READY_FOR_TEAM || (leaderApproved && bpApproved), "INVALID_STATE", "两级通过后才可交团队");
            require(status != Status.BP_PENDING || (leaderApproved && !bpApproved), "INVALID_STATE", "BP节点审批证据无效");
            require(status != Status.LEADER_PENDING || (!leaderApproved && !bpApproved), "INVALID_STATE", "负责人节点不能带入旧审批");
            require(status == Status.READY_FOR_TEAM || !bpApproved, "INVALID_STATE", "非完成状态不能保留BP通过");
            require(status != Status.RETURNED || (returnedStage != Stage.NONE && !returnTargetId.isBlank()),
                    "INVALID_STATE", "退回节点和接收人缺失");
            if (policy.reviewMode() == ReviewMode.SINGLE_EXPLICIT)
                require(status == Status.RETURNED
                        ? returnedStage == Stage.LEADER && returnTargetId.equals(participants.submitterId())
                        : returnedStage == Stage.NONE && returnTargetId.isEmpty(),
                        "INVALID_STATE", "兼任流程退回目标与固定策略不符");
        }
        public Stage stage() { return stageFor(status); }
        public String assigneeId() {
            if (status == Status.RETURNED) return returnTargetId;
            if (status == Status.WITHDRAWN) return participants.submitterId();
            return participants.forStage(stage());
        }
    }
    public static Stage stageFor(Status status) {
        return switch (status) { case LEADER_PENDING -> Stage.LEADER; case BP_PENDING -> Stage.BP; default -> Stage.NONE; };
    }
    private static void validateCombinedBinding(Participants participants, Policy policy) {
        if (policy.combinedAssignment() == null) return;
        require(participants.leaderId().equals(participants.bpId()) &&
                participants.leaderId().equals(policy.combinedAssignment().approverId()),
                "INVALID_STATE", "兼任策略必须匹配已明确的同一负责人和BP");
        require(!participants.submitterId().equals(participants.leaderId()), "POLICY_DENIED", "填报人不能审批自己的单据");
    }
    private static void active(Access access, String actor) {
        Activity activity = access.activity().getOrDefault(actor, Activity.UNKNOWN);
        configured(activity != Activity.UNKNOWN, "人员在岗状态");
        require(activity == Activity.ACTIVE, "FORBIDDEN", "离岗人员不可办理");
    }
    private static void owner(Access access, String expected) {
        require(access.actorId().equals(expected), "FORBIDDEN", "当前用户不是指定办理人"); active(access, access.actorId());
    }
    private static String reviewer(State state, Access access) {
        String assigned = state.participants().forStage(state.stage());
        require(!assigned.isEmpty(), "INVALID_TRANSITION", "当前没有审批节点");
        if (!access.actorId().equals(assigned)) {
            require(access.actorId().equals(access.delegates().get(state.stage())), "FORBIDDEN", "当前用户不是指定审批人或有效代理人");
            permit(state.policy().delegation(), "代理审批规则");
            Activity original = access.activity().getOrDefault(assigned, Activity.UNKNOWN);
            configured(original != Activity.UNKNOWN, "原审批人在岗状态");
            if (original == Activity.INACTIVE) permit(state.policy().inactiveAssigneeDelegation(), "离岗代理规则");
        }
        active(access, access.actorId());
        if (state.participants().leaderId().equals(state.participants().bpId())) permit(state.policy().combinedRoles(), "负责人兼任BP规则");
        if (state.participants().submitterId().equals(assigned) || state.participants().submitterId().equals(access.actorId()))
            permit(state.policy().selfApproval(), "自批规则");
        // Distinct assigned principals can still collapse through a shared delegate.
        if (state.stage() == Stage.BP) {
            Event prior = latestLeaderApproval(state);
            if (prior != null && prior.actorId().equals(access.actorId()) && !state.participants().leaderId().equals(state.participants().bpId()))
                permit(state.policy().combinedRoles(), "同一办理人两级审批规则");
        }
        return access.actorId().equals(assigned) ? "" : assigned;
    }
    private static Event latestLeaderApproval(State state) {
        List<Event> events = state.history();
        for (int i = events.size() - 1; i >= 0; i--) {
            Event event = events.get(i);
            if (event.action() == Action.REVISE || (event.to() == Status.LEADER_PENDING && event.action() == Action.RESUBMIT)) return null;
            if ((event.action() == Action.APPROVE || event.action() == Action.APPROVE_COMBINED) && event.stage() == Stage.LEADER && event.dataRevision() == state.dataRevision()) return event;
        }
        return null;
    }
    /** Call only for M02 direct-intake records. Tender approvals stay in the original signing system. */
    public static State submit(long workflowId, long businessId, String title, String route, Participants participants,
                               Policy policy, long dataRevision, String requestId, Instant at, Access access) {
        return submit(workflowId, businessId, title, route, participants, policy, dataRevision, requestId, at, access, null);
    }
    /** organizationCode is server-owned M02 scope, mandatory for the explicit combined policy. */
    public static State submit(long workflowId, long businessId, String title, String route, Participants participants,
                               Policy policy, long dataRevision, String requestId, Instant at, Access access, String organizationCode) {
        require("DIRECT".equals(route), "EXTERNAL_APPROVAL_REQUIRED", "投标沿用原签报，本流程仅用于直接承接");
        configured(policy != null, "审批策略"); Objects.requireNonNull(participants); Objects.requireNonNull(at);
        owner(access, participants.submitterId());
        if (policy.combinedAssignment() != null) {
            configured(organizationCode != null && !organizationCode.isBlank(), "兼任审批的可信机构范围");
            require(policy.combinedAssignment().organizationCode().equals(organizationCode), "FORBIDDEN", "兼任职责不覆盖当前机构");
            validateCombinedBinding(participants, policy);
        }
        Event event = new Event(1, requestId, Action.SUBMIT, access.actorId(), "", null, Status.LEADER_PENDING,
                Stage.NONE, "", dataRevision, at);
        return new State(workflowId, businessId, title, participants, policy, Status.LEADER_PENDING, 1, dataRevision,
                1, false, false, Stage.NONE, "", List.of(event));
    }
    public static State apply(State state, Command command, Access access) {
        require(state.history().stream().noneMatch(e -> e.requestId().equals(command.requestId())), "DUPLICATE_REQUEST", "该请求已办理，请刷新查看记录");
        require(command.expectedVersion() == state.version(), "STALE_VERSION", "单据已更新，请刷新后办理");
        require(command.expectedStage() == state.stage(), "STALE_STAGE", "审批节点已变化，请刷新后办理");
        require(!command.at().isBefore(state.history().get(state.history().size() - 1).at()), "INVALID_INPUT", "办理时间不能早于上一条记录");
        require(command.action() != Action.SUBMIT, "INVALID_TRANSITION", "已有流程不能重复提交");
        if (command.action() != Action.RESUBMIT && command.action() != Action.REVISE)
            require(command.dataRevision() == state.dataRevision(), "DOCUMENT_CHANGED", "单据内容已变更，需按重审规则处理");
        Status next = state.status(); boolean leader = state.leaderApproved(), bp = state.bpApproved();
        Stage returned = state.returnedStage(); String target = state.returnTargetId();
        int round = state.round(); String onBehalfOf = "";
        switch (command.action()) {
            case APPROVE -> {
                require(state.policy().reviewMode() == ReviewMode.SEQUENTIAL, "EXPLICIT_COMBINED_REQUIRED", "请明确选择同时完成负责人和BP审批");
                onBehalfOf = reviewer(state, access);
                if (state.stage() == Stage.LEADER) { leader = true; next = Status.BP_PENDING; }
                else { require(leader, "INVALID_STATE", "负责人尚未通过"); bp = true; next = Status.READY_FOR_TEAM; }
                returned = Stage.NONE; target = "";
            }
            case APPROVE_COMBINED -> {
                configured(state.policy().combinedAssignment() != null, "单次兼任审批策略");
                require(state.stage() == Stage.LEADER, "INVALID_TRANSITION", "当前不是兼任审批待办");
                validateCombinedBinding(state.participants(), state.policy());
                onBehalfOf = reviewer(state, access);
                require(onBehalfOf.isEmpty(), "POLICY_DENIED", "兼任审批须由本人明确办理");
                next = Status.READY_FOR_TEAM; leader = true; bp = true; returned = Stage.NONE; target = "";
            }
            case RETURN -> {
                onBehalfOf = reviewer(state, access);
                ReturnTo destination = state.stage() == Stage.LEADER ? state.policy().leaderReturnTo() : state.policy().bpReturnTo();
                configured(destination != ReturnTo.UNCONFIGURED, "退回接收人规则");
                require(!command.comment().isBlank(), "COMMENT_REQUIRED", "请填写退回原因");
                target = destination == ReturnTo.SUBMITTER ? state.participants().submitterId() : state.participants().leaderId();
                returned = state.stage(); next = Status.RETURNED; bp = false;
                if (returned == Stage.LEADER) leader = false;
            }
            case RESUBMIT -> {
                require(state.status() == Status.RETURNED || state.status() == Status.WITHDRAWN, "INVALID_TRANSITION", "仅退回或撤回单据可重提");
                owner(access, state.assigneeId());
                configured(state.policy().resubmitFrom() != Restart.UNCONFIGURED, "重提起点规则");
                require(command.dataRevision() >= state.dataRevision(), "INVALID_INPUT", "单据内容版本不可回退");
                boolean changed = command.dataRevision() != state.dataRevision();
                if (changed) checkChangePolicy(state.policy());
                if (state.status() == Status.WITHDRAWN)
                    configured(state.policy().resubmitFrom() == Restart.FROM_LEADER, "撤回后重提起点规则");
                boolean fromLeader = changed || state.status() == Status.WITHDRAWN || state.policy().resubmitFrom() == Restart.FROM_LEADER || returned == Stage.LEADER;
                next = fromLeader ? Status.LEADER_PENDING : Status.BP_PENDING;
                if (fromLeader) leader = false;
                else require(leader && latestLeaderApproval(state) != null, "INVALID_STATE", "缺少当前内容版本的负责人审批");
                bp = false; returned = Stage.NONE; target = ""; round++;
            }
            case WITHDRAW -> {
                require(state.status() == Status.LEADER_PENDING || state.status() == Status.BP_PENDING || state.status() == Status.RETURNED,
                        "INVALID_TRANSITION", "当前状态不可撤回");
                owner(access, state.participants().submitterId());
                configured(state.policy().withdrawalConfigured(), "撤回规则");
                require(state.policy().withdrawableStatuses().contains(state.status()), "POLICY_DENIED", "该阶段不允许撤回");
                next = Status.WITHDRAWN; leader = false; bp = false; returned = Stage.NONE; target = "";
            }
            case REVISE -> {
                require(state.stage() != Stage.NONE, "INVALID_TRANSITION", "已结束或退回流程请使用相应业务入口");
                owner(access, state.participants().submitterId()); checkChangePolicy(state.policy());
                require(command.dataRevision() > state.dataRevision(), "INVALID_INPUT", "变更须提供新的单据版本");
                require(!command.comment().isBlank(), "COMMENT_REQUIRED", "请说明变更内容");
                next = Status.LEADER_PENDING; leader = false; bp = false; returned = Stage.NONE; target = ""; round++;
            }
            default -> throw new Failure("INVALID_TRANSITION", "不支持的办理动作");
        }
        List<Event> events = new ArrayList<>(state.history());
        events.add(new Event(state.version() + 1, command.requestId(), command.action(), access.actorId(), onBehalfOf,
                state.status(), next, state.stage(), command.comment(), command.dataRevision(), command.at()));
        return new State(state.id(), state.businessId(), state.title(), state.participants(), state.policy(), next,
                state.version() + 1, command.dataRevision(), round, leader, bp, returned, target, events);
    }
    private static void checkChangePolicy(Policy policy) {
        configured(policy.documentChanges() != ChangeRule.UNCONFIGURED, "字段变更重审规则");
        require(policy.documentChanges() == ChangeRule.RESTART_FROM_LEADER, "POLICY_DENIED", "当前规则不允许修改审批内容");
    }
    /** M02 must call in the SAME transaction as team acceptance, using its current content revision. */
    public static void requireReadyForTeam(State state, long currentDataRevision) {
        require(state.status() == Status.READY_FOR_TEAM && state.leaderApproved() && state.bpApproved(),
                "APPROVAL_INCOMPLETE", "负责人和BP均通过后才可交团队");
        require(state.dataRevision() == currentDataRevision, "DOCUMENT_CHANGED", "审批通过后内容有变更，不可交团队");
        require(latestLeaderApproval(state) != null, "INVALID_STATE", "负责人审批证据缺失");
        Event last = state.history().get(state.history().size() - 1);
        boolean lastApproval = state.policy().reviewMode() == ReviewMode.SINGLE_EXPLICIT
                ? last.action() == Action.APPROVE_COMBINED && last.stage() == Stage.LEADER && last.responsibilities().equals(List.of(Stage.LEADER, Stage.BP))
                : last.action() == Action.APPROVE && last.stage() == Stage.BP;
        require(lastApproval && last.dataRevision() == currentDataRevision,
                "INVALID_STATE", "BP审批证据缺失");
    }
    public record Availability(Action action, String label, boolean enabled, String reason, String code) {}
    public enum NoticeKind { TODO, RETURNED, TEAM_READY, WITHDRAWN, TASK_CANCELLED }
    public enum NoticeTarget { PARTICIPANT, TEAM_QUEUE }
    /** Delivery is a host responsibility. TEAM_QUEUE must be resolved explicitly, never guessed. */
    public record Notice(String deduplicationKey, long workflowId, long businessId, long eventVersion,
                         NoticeKind kind, NoticeTarget targetType, String targetId) {}
    public static List<Notice> notices(State before, State after) {
        if (before == null) require(after.version() == 1, "INVALID_STATE", "初次通知须对应首次提交");
        else require(before.id() == after.id() && before.businessId() == after.businessId() &&
                before.version() + 1 == after.version() && after.history().subList(0, before.history().size()).equals(before.history()),
                "INVALID_STATE", "通知须对应本次原子状态变更");
        List<Notice> notices = new ArrayList<>();
        Event event = after.history().get(after.history().size() - 1);
        switch (event.action()) {
            case SUBMIT, RESUBMIT -> addNotice(notices, after, NoticeKind.TODO, NoticeTarget.PARTICIPANT, after.assigneeId());
            case APPROVE, APPROVE_COMBINED -> {
                if (after.status() == Status.BP_PENDING) addNotice(notices, after, NoticeKind.TODO, NoticeTarget.PARTICIPANT, after.assigneeId());
                else {
                    addNotice(notices, after, NoticeKind.TEAM_READY, NoticeTarget.TEAM_QUEUE, "");
                }
            }
            case RETURN -> addNotice(notices, after, NoticeKind.RETURNED, NoticeTarget.PARTICIPANT, after.returnTargetId());
            case WITHDRAW -> {
                addNotice(notices, after, NoticeKind.WITHDRAWN, NoticeTarget.PARTICIPANT, after.participants().submitterId());
                if (before != null && !before.assigneeId().isBlank() && !before.assigneeId().equals(after.participants().submitterId()))
                    addNotice(notices, after, NoticeKind.TASK_CANCELLED, NoticeTarget.PARTICIPANT, before.assigneeId());
            }
            case REVISE -> {
                addNotice(notices, after, NoticeKind.TODO, NoticeTarget.PARTICIPANT, after.assigneeId());
                if (before != null && !before.assigneeId().equals(after.assigneeId()))
                    addNotice(notices, after, NoticeKind.TASK_CANCELLED, NoticeTarget.PARTICIPANT, before.assigneeId());
            }
        }
        return List.copyOf(notices);
    }
    private static void addNotice(List<Notice> notices, State state, NoticeKind kind, NoticeTarget target, String recipient) {
        notices.add(new Notice("m03:" + state.id() + ":" + state.version() + ":" + kind + ":" + target + ":" + recipient,
                state.id(), state.businessId(), state.version(), kind, target, recipient));
    }
    public record Reminder(long workflowId, long eventVersion, String requestId, String actorId, String recipientId, Instant at) {
        public Reminder {
            require(workflowId > 0 && eventVersion > 0, "INVALID_INPUT", "催办版本无效");
            id(requestId, "催办请求ID"); id(actorId, "催办人ID"); id(recipientId, "催办接收人ID"); Objects.requireNonNull(at);
        }
    }
    /** Manual reminders only: host must atomically load the complete ledger and append the result. */
    public static Reminder remind(State state, long expectedVersion, String requestId, Instant at,
                                  Access access, List<Reminder> ledger) {
        require(state.version() == expectedVersion, "STALE_VERSION", "单据已更新，请刷新后催办");
        require(state.stage() != Stage.NONE, "INVALID_TRANSITION", "仅待审批单据可催办");
        owner(access, state.participants().submitterId()); active(access, state.assigneeId()); Objects.requireNonNull(at);
        Instant availableAt = state.history().get(state.history().size() - 1).at().plus(Duration.ofHours(24));
        for (Reminder previous : ledger) {
            require(previous.workflowId() == state.id(), "INVALID_STATE", "催办记录不属于当前流程");
            require(!previous.requestId().equals(requestId), "DUPLICATE_REQUEST", "该催办请求已受理");
            Instant next = previous.at().plus(Duration.ofHours(24));
            if (next.isAfter(availableAt)) availableAt = next;
        }
        require(!at.isBefore(availableAt), "REMINDER_TOO_EARLY", "待办停留满24小时后可催办，同一流程24小时内最多一次");
        return new Reminder(state.id(), state.version(), requestId, access.actorId(), state.assigneeId(), at);
    }
    public static List<Availability> actions(State state, Access access, long currentDataRevision) {
        List<Availability> result = new ArrayList<>();
        if (state.stage() != Stage.NONE) {
            if (state.policy().reviewMode() == ReviewMode.SINGLE_EXPLICIT)
                probe(result, state, access, currentDataRevision, Action.APPROVE_COMBINED, "同时完成负责人和BP审批");
            else probe(result, state, access, currentDataRevision, Action.APPROVE, "通过");
            probe(result, state, access, currentDataRevision, Action.RETURN, "退回");
        }
        if (state.status() == Status.RETURNED || state.status() == Status.WITHDRAWN)
            probe(result, state, access, currentDataRevision, Action.RESUBMIT, "重新提交");
        if (state.status() == Status.LEADER_PENDING || state.status() == Status.BP_PENDING || state.status() == Status.RETURNED)
            probe(result, state, access, currentDataRevision, Action.WITHDRAW, "撤回");
        return List.copyOf(result);
    }
    private static void probe(List<Availability> result, State state, Access access, long revision, Action action, String label) {
        String requestId = "availability-" + state.version();
        while (hasRequest(state, requestId)) requestId += "x";
        try {
            apply(state, new Command(action, state.version(), state.stage(), requestId, "可办理性检查", revision,
                    state.history().get(state.history().size() - 1).at()), access);
            result.add(new Availability(action, label, true, "", ""));
        } catch (Failure failure) {
            result.add(new Availability(action, label, false, failure.getMessage(), failure.code));
        }
    }
    private static boolean hasRequest(State state, String requestId) {
        for (Event e : state.history()) if (e.requestId().equals(requestId)) return true;
        return false;
    }
    /** Explicit map for the existing Json writer (which does not serialize Java records). */
    public static Map<String, Object> view(State state, Access access, long currentDataRevision) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", state.id()); view.put("businessId", state.businessId()); view.put("title", state.title());
        view.put("status", state.status().name()); view.put("stage", state.stage().name()); view.put("version", state.version());
        view.put("dataRevision", state.dataRevision()); view.put("round", state.round()); view.put("policyVersion", state.policy().version());
        view.put("reviewMode", state.policy().reviewMode().name());
        view.put("participants", Map.of("submitterId", state.participants().submitterId(), "leaderId", state.participants().leaderId(), "bpId", state.participants().bpId()));
        view.put("assigneeId", state.assigneeId());
        view.put("actions", actions(state, access, currentDataRevision).stream().map(a -> Map.of("action", a.action().name(), "label", a.label(), "enabled", a.enabled(), "reason", a.reason(), "code", a.code())).toList());
        view.put("history", state.history().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", e.version()); m.put("requestId", e.requestId()); m.put("action", e.action().name());
            m.put("actorId", e.actorId()); m.put("onBehalfOf", e.onBehalfOf()); m.put("from", e.from() == null ? null : e.from().name());
            m.put("to", e.to().name()); m.put("stage", e.stage().name()); m.put("comment", e.comment()); m.put("dataRevision", e.dataRevision()); m.put("at", e.at().toString());
            m.put("responsibilities", e.responsibilities().stream().map(Enum::name).toList());
            return m;
        }).toList());
        return view;
    }
}
