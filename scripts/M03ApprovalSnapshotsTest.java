package com.training;

import static com.training.ApprovalWorkflow.*;
import static com.training.ApprovalWorkflowSnapshots.*;
import java.time.Instant;
import java.util.*;

/** Exercises actual JSON persistence boundaries using synthetic identities only. */
public final class M03ApprovalSnapshotsTest {
    static int checks;
    static final Instant TIME = Instant.parse("2026-09-22T00:00:00Z");
    static final CombinedAssignment ASSIGNMENT = new CombinedAssignment("SYN-ORG", "SYN-REVIEWER", "SYN-DIRECTORY-1", "SYN-EVIDENCE-1");
    static Access as(String id) { return new Access(id, Map.of("SYN-SUBMITTER", Activity.ACTIVE, "SYN-REVIEWER", Activity.ACTIVE), Map.of()); }
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    static void invalid(Runnable run) {
        try { run.run(); throw new AssertionError("Expected INVALID_STATE"); }
        catch (Failure failure) { check("INVALID_STATE".equals(failure.code), "Wrong error " + failure.code); }
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> persisted(Map<String, Object> source) { return (Map<String, Object>) Json.parse(Json.write(source)); }
    static Map<String, Object> with(Map<String, Object> source, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(source); result.put(key, value); return result;
    }
    static Map<String, Object> without(Map<String, Object> source, String key) {
        Map<String, Object> result = new LinkedHashMap<>(source); result.remove(key); return result;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Policy baseline = Policy.baseline("SYN-BASELINE-1");
        Policy combined = Policy.combinedBaseline("SYN-COMBINED-1", ASSIGNMENT);
        Map<String, Object> normalMap = persisted(encodePolicy(baseline));
        Map<String, Object> combinedMap = persisted(encodePolicy(combined));
        check(decodePolicy(normalMap).equals(baseline), "Baseline JSON roundtrip");
        check(decodePolicy(combinedMap).equals(combined), "Frozen assignment JSON roundtrip");
        Map<String, Object> legacy = without(normalMap, "reviewMode");
        check(decodePolicy(legacy).reviewMode() == ReviewMode.SEQUENTIAL, "Absent extension is historical sequential");
        legacy.put("combinedRoles", "ALLOW");
        check(decodePolicy(legacy).reviewMode() == ReviewMode.SEQUENTIAL, "Historical ALLOW still requires two approvals");
        check(decodePolicy(encodePolicy(Policy.unconfigured("SYN-UNKNOWN"))).equals(Policy.unconfigured("SYN-UNKNOWN")), "Unknown rule snapshot remains unknown");
        invalid(() -> decodePolicy(without(combinedMap, "reviewMode")));
        invalid(() -> decodePolicy(without(combinedMap, "combinedAssignment")));
        for (Object mode : Arrays.asList(null, "", "FUTURE_MODE", false)) invalid(() -> decodePolicy(with(combinedMap, "reviewMode", mode)));
        for (Object value : Arrays.asList(null, "invalid", List.of())) invalid(() -> decodePolicy(with(combinedMap, "combinedAssignment", value)));
        invalid(() -> decodePolicy(with(normalMap, "combinedAssignment", null)));
        invalid(() -> decodePolicy(with(normalMap, "combinedAssignment", combinedMap.get("combinedAssignment"))));
        Map<String, Object> binding = (Map<String, Object>) combinedMap.get("combinedAssignment");
        for (String key : List.of("organizationCode", "approverId", "directoryVersion", "evidenceRef")) {
            invalid(() -> decodePolicy(with(combinedMap, "combinedAssignment", without(binding, key))));
            invalid(() -> decodePolicy(with(combinedMap, "combinedAssignment", with(binding, key, " "))));
        }
        for (String key : List.of("selfApproval", "delegation", "inactiveAssigneeDelegation")) invalid(() -> decodePolicy(with(combinedMap, key, "ALLOW")));
        invalid(() -> decodePolicy(with(combinedMap, "combinedRoles", "DENY")));
        invalid(() -> decodePolicy(with(combinedMap, "bpReturnTo", "LEADER")));
        invalid(() -> decodePolicy(with(combinedMap, "resubmitFrom", "FROM_RETURNED_STAGE")));
        invalid(() -> decodePolicy(with(combinedMap, "withdrawalConfigured", "true")));
        invalid(() -> decodePolicy(with(combinedMap, "withdrawableStatuses", List.of("LEADER_PENDING", "LEADER_PENDING"))));
        invalid(() -> decodePolicy(with(combinedMap, "withdrawableStatuses", List.of("READY_FOR_TEAM"))));
        invalid(() -> decodePolicy(with(combinedMap, "withdrawableStatuses", List.of(3))));

        Participants people = new Participants("SYN-SUBMITTER", "SYN-REVIEWER", "SYN-REVIEWER");
        State pending = submit(91, 92, "合成持久化", "DIRECT", people, combined, 1, "syn-submit", TIME, as("SYN-SUBMITTER"), "SYN-ORG");
        State ready = apply(pending, new Command(Action.APPROVE_COMBINED, 1, Stage.LEADER, "syn-combined", "同时审核", 1, TIME.plusSeconds(1)), as("SYN-REVIEWER"));
        Map<String, Object> stateMap = persisted(view(ready, as("SYN-REVIEWER"), 1));
        List<Map<String, Object>> encodedEvents = (List<Map<String, Object>>) stateMap.get("history");
        List<Event> events = encodedEvents.stream().map(ApprovalWorkflowSnapshots::decodeEvent).toList();
        State restored = new State(ready.id(), ready.businessId(), ready.title(), people, decodePolicy(combinedMap), ready.status(), ready.version(), ready.dataRevision(), ready.round(), true, true, Stage.NONE, "", events);
        check(ready.equals(restored), "Real JSON policy/event roundtrip preserves immutable state");
        requireReadyForTeam(restored, 1); checks++;
        Map<String, Object> eventMap = encodedEvents.get(1);
        check(decodeEvent(without(eventMap, "responsibilities")).responsibilities().equals(List.of(Stage.LEADER, Stage.BP)), "Action gives canonical two-duty evidence");
        invalid(() -> decodeEvent(with(eventMap, "responsibilities", List.of("BP", "LEADER"))));
        invalid(() -> decodeEvent(with(eventMap, "responsibilities", List.of("LEADER"))));
        invalid(() -> decodeEvent(with(eventMap, "responsibilities", null)));
        invalid(() -> decodeEvent(with(eventMap, "action", "APPROVE")));
        invalid(() -> decodeEvent(with(eventMap, "version", 2.5)));
        invalid(() -> decodeEvent(with(eventMap, "dataRevision", "1")));
        invalid(() -> decodeEvent(with(eventMap, "at", "2026-09-22")));
        invalid(() -> decodeEvent(with(eventMap, "onBehalfOf", false)));
        Map<String, Object> first = without(encodedEvents.get(0), "responsibilities");
        check(decodeEvent(first).equals(ready.history().get(0)), "Old event shape remains readable");
        invalid(() -> new State(ready.id(), ready.businessId(), ready.title(), people, decodePolicy(legacy), ready.status(), ready.version(), 1, 1, true, true, Stage.NONE, "", events));
        System.out.println("M03 snapshot checks passed: " + checks);
    }
}
