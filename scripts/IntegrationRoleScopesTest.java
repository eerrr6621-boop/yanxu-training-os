package com.training;

import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Synthetic, isolated M01 role ranges and explicit M03 duty evidence. */
public final class IntegrationRoleScopesTest {
    private static int checks;
    private static final String PASS = "SYNTHETIC-ROLE-SCOPES-20260923";
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    @FunctionalInterface interface Work { void run() throws Exception; }
    private static void reject(int code, Work work) throws Exception {
        try { work.run(); throw new AssertionError("Expected HTTP " + code); }
        catch (Api.ApiException e) { check(e.code == code, "rejection status " + e.code); }
    }
    private static Configuration fixture(String version, boolean explicit, boolean combined) {
        Set<String> roles = Set.of("LEADER", "BP", "FILLER");
        RelationRule optional = new RelationRule(false, roles, false, false);
        Person reviewer = explicit ? new Person("P2", "900", Set.of("001", "002"), null, null, Set.of("LEADER", "BP"), true,
                Map.of("LEADER", Set.of("001"), "BP", Set.of("001", "002")))
                : new Person("P2", "900", Set.of("001", "002"), null, null, Set.of("LEADER", "BP"), true);
        return new Configuration(version, new CodeRules("[0-9]{3}", "P[0-9]+", "[A-Z]+"), roles,
                List.of(new Organization("001", null, true), new Organization("002", null, true), new Organization("900", null, true)),
                List.of(reviewer, new Person("P3", "001", Set.of(), "P2", "P2", Set.of("FILLER"), true)),
                List.of(new RoleRelations("LEADER", optional, optional), new RoleRelations("BP", optional, optional),
                    new RoleRelations("FILLER", new RelationRule(true, Set.of("LEADER"), true, false), new RelationRule(true, Set.of("BP"), true, false))),
                List.of(new AccountBinding(2, "P2", true), new AccountBinding(3, "P3", true)),
                List.of(new Grant("lead-review", "LEADER", "approval.review", Action.HANDLE, Effect.ALLOW, Scope.RESPONSIBLE_ORGS, Set.of()),
                    new Grant("bp-review", "BP", "approval.review", Action.HANDLE, Effect.ALLOW, Scope.RESPONSIBLE_ORGS, Set.of()),
                    new Grant("lead-summary", "LEADER", "summary.review.branch", Action.HANDLE, Effect.ALLOW, Scope.RESPONSIBLE_ORGS, Set.of()),
                    new Grant("bp-summary", "BP", "summary.review.bp", Action.HANDLE, Effect.ALLOW, Scope.RESPONSIBLE_ORGS, Set.of())),
                combined ? List.of(new CombinedApprovalAssignment("001", "P2", "LEADER", "BP", "SYNTHETIC-EVIDENCE-1")) : List.of());
    }
    private static Configuration copy(Configuration c, String version, List<Person> people, List<Grant> grants, List<CombinedApprovalAssignment> combined) {
        return new Configuration(version, c.codeRules(), c.roleCodes(), c.organizations(), people, c.relations(), c.accountBindings(), grants, combined);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> raw(Configuration c) {
        return (Map<String,Object>)Json.parse(Json.write(OrganizationAccessStore.toMap(c)));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> firstPerson(Map<String,Object> m) { return (Map<String,Object>)((List<?>)m.get("people")).get(0); }
    private static boolean allowed(Configuration c, String key, String org) {
        return new Engine(c).authorize("synthetic", ignored -> OptionalLong.of(2), new Resource(key, org), Action.HANDLE).allowed();
    }
    private static Auth.Session login(int id) throws Exception { return Auth.get(Auth.login("synthetic-role-"+id, PASS)); }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("New empty data directory required");
        Path data = Path.of(args[0]).toAbsolutePath();
        try (var paths=Files.list(data)) { if(paths.findAny().isPresent()) throw new IllegalArgumentException("Data directory must be empty"); }
        System.setProperty("data.dir", data.toString());
        Configuration c = fixture("scopes-v1", true, true);
        try {
            new Person("P2", "900", Set.of("001", "002"), null, null, Set.of("LEADER", "BP"), true, null);
            throw new AssertionError("Explicit null scopes must not become legacy union");
        } catch (NullPointerException expected) { check(true, "explicit Java null scopes rejected"); }
        Map<String,Set<String>> nullRange = new HashMap<>(); nullRange.put("LEADER", null);
        try {
            new Person("P2", "900", Set.of("001"), null, null, Set.of("LEADER"), true, nullRange);
            throw new AssertionError("Explicit null role range must not become empty");
        } catch (NullPointerException expected) { check(true, "explicit Java null role range rejected"); }
        try {
            copy(c, "null-duties", c.people(), c.grants(), null);
            throw new AssertionError("Explicit null duties must not become legacy list");
        } catch (NullPointerException expected) { check(true, "explicit Java null duties rejected"); }
        check(validate(c).valid(), "valid explicit role configuration");
        check(allowed(c, "summary.review.branch", "001"), "leader can review assigned branch");
        check(!allowed(c, "summary.review.branch", "002"), "BP range does not leak into branch duty");
        check(allowed(c, "summary.review.bp", "001") && allowed(c, "summary.review.bp", "002"), "BP keeps both assigned branches");
        check(!allowed(c, "summary.review.bp", "900"), "HQ membership does not grant review");
        check(allowed(fixture("legacy", false, false), "summary.review.branch", "002"), "old saved configuration retains legacy scope semantics");
        check(!validate(fixture("missing-explicit", false, true)).valid(), "combined duty cannot use legacy union");
        check(OrganizationAccessStore.parseConfiguration(raw(c)).equals(c), "new fields round trip exactly");
        check(!raw(fixture("legacy", false, false)).containsKey("combinedApprovals"), "legacy JSON shape remains unchanged");
        for (Object invalid : Arrays.asList(null, List.of(), Map.of())) {
            Map<String,Object> value = raw(c); firstPerson(value).put("responsibleOrganizationsByRole", invalid);
            reject(400, () -> OrganizationAccessStore.parseConfiguration(value));
        }
        Map<String,Object> missingScope = raw(c); firstPerson(missingScope).put("responsibleOrganizationsByRole", Map.of("LEADER", List.of("001")));
        check(!validate(OrganizationAccessStore.parseConfiguration(missingScope)).valid(), "partial scope map cannot fall back to union");
        Map<String,Object> unknownScope = raw(c); firstPerson(unknownScope).put("responsibleOrganizationsByRole", Map.of("LEADER", List.of("001"), "BP", List.of("999")));
        check(!validate(OrganizationAccessStore.parseConfiguration(unknownScope)).valid(), "unknown org rejected");
        Map<String,Object> nullAssignments = raw(c); nullAssignments.put("combinedApprovals", null);
        reject(400, () -> OrganizationAccessStore.parseConfiguration(nullAssignments));
        check(!validate(copy(c, "one-role", c.people(), c.grants(), List.of(new CombinedApprovalAssignment("001", "P2", "BP", "BP", "E")))).valid(), "one role cannot impersonate two duties");
        check(!validate(copy(c, "wrong-branch", c.people(), c.grants(), List.of(new CombinedApprovalAssignment("002", "P2", "LEADER", "BP", "E")))).valid(), "combined both ranges must cover org");
        check(!validate(copy(c, "no-evidence", c.people(), c.grants(), List.of(new CombinedApprovalAssignment("001", "P2", "LEADER", "BP", "")))).valid(), "missing evidence rejected");
        List<Person> misplaced = new ArrayList<>(c.people());
        misplaced.set(1, new Person("P3", "002", Set.of(), "P2", "P2", Set.of("FILLER"), true));
        check(!validate(copy(c, "misplaced", misplaced, c.grants(), c.combinedApprovals())).valid(), "leader relationship uses role-specific range");
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64),password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        for(int i=1;i<=3;i++) Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)", i, "synthetic-role-"+i, Auth.hash(PASS), "SYNTHETIC", i==1?"admin":"viewer");
        TrustedDevices.init(); FirstBindStore.init(); OrganizationAccessStore.init(); Auth.Session admin=login(1), oldReviewer=login(2);
        check(OrganizationAccessStore.combinedAssignment("001", "P2") == null, "no configuration no authority");
        OrganizationAccessStore.publish(admin, null, c);
        check(Auth.current(oldReviewer)==null, "new binding invalidates old session");
        var assignment=OrganizationAccessStore.combinedAssignment("001", "P2");
        check(assignment!=null && assignment.directoryVersion().equals("scopes-v1") && assignment.evidenceRef().equals("SYNTHETIC-EVIDENCE-1"), "trusted frozen evidence from published config");
        check(OrganizationAccessStore.combinedAssignment("002", "P2")==null, "BP coverage alone cannot infer combined duty");
        check(OrganizationAccessStore.combinedAssignment("001", "P3")==null, "filler cannot use another person's evidence");
        Db.exec("UPDATE users SET status=0 WHERE id=2");
        check(OrganizationAccessStore.combinedAssignment("001", "P2")==null, "disabled account invalidates current duty");
        Db.exec("UPDATE users SET status=1 WHERE id=2");
        Auth.Session reviewer=login(2);
        Configuration c2=copy(c,"scopes-v2",c.people(),c.grants(),c.combinedApprovals());
        OrganizationAccessStore.publish(admin,"scopes-v1",c2);
        check(Auth.current(reviewer)!=null, "version-only update preserves current session");
        check(OrganizationAccessStore.combinedAssignment("001","P2").directoryVersion().equals("scopes-v2"), "new workflows freeze current directory version");
        List<Grant> noLeader=c.grants().stream().filter(g->!g.ruleId().equals("lead-review")).toList();
        OrganizationAccessStore.publish(admin,"scopes-v2",copy(c,"scopes-v3",c.people(),noLeader,c.combinedApprovals()));
        check(OrganizationAccessStore.combinedAssignment("001","P2")==null, "BP approval allow cannot substitute absent leader grant");
        List<Grant> denied=new ArrayList<>(c.grants());
        denied.add(new Grant("explicit-deny","LEADER","approval.review",Action.HANDLE,Effect.DENY,Scope.NAMED_ORGS,Set.of("001")));
        OrganizationAccessStore.publish(admin,"scopes-v3",copy(c,"scopes-v4",c.people(),denied,c.combinedApprovals()));
        check(OrganizationAccessStore.combinedAssignment("001","P2")==null, "explicit deny beats both allows");
        OrganizationAccessStore.publish(admin,"scopes-v4",copy(c,"scopes-v5",c.people(),c.grants(),c.combinedApprovals()));
        reviewer=login(2);
        OrganizationAccessStore.publish(admin,"scopes-v5",copy(c,"scopes-v6",c.people(),c.grants(),List.of()));
        check(Auth.current(reviewer)==null, "removing combined evidence revokes old session");
        check(OrganizationAccessStore.combinedAssignment("001","P2")==null, "same participants without explicit evidence remain sequential-only");
        check(Db.count("users")==3 && Db.count("organization_access_config")==6, "only synthetic users and versioned configs exist");
        System.out.println("M01 role scopes / combined authority: "+checks+" checks passed");
    }
}
