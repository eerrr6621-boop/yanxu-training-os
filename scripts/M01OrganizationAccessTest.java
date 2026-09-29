package com.training;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.training.OrganizationAccess.*;

/** Standalone, no database and no real users. Throws on any regression. */
public final class M01OrganizationAccessTest {
    private static int checks;
    private static final Configuration BASE = OrganizationAccessDemo.configuration();
    private static final Map<String, Long> SESSIONS = Map.of("synthetic-session-a", 1001L,
            "synthetic-session-b", 1002L, "synthetic-session-c", 1003L,
            "synthetic-session-d", 1004L, "synthetic-session-e", 1005L);
    private static final SessionVerifier VERIFY = token -> SESSIONS.containsKey(token)
            ? OptionalLong.of(SESSIONS.get(token)) : OptionalLong.empty();
    public static void main(String[] args) {
        Engine engine = new Engine(BASE);
        check(engine.validation().valid(), "synthetic fixture valid: " + engine.validation().issues());
        status(engine, "synthetic-session-a", "ORG-001", Action.VIEW, Status.ALLOWED);
        status(engine, "synthetic-session-a", "ORG-002", Action.VIEW, Status.DENIED);
        status(engine, "synthetic-session-a", "ORG-000", Action.VIEW, Status.DENIED);
        status(engine, "synthetic-session-a", "ORG-001", Action.HANDLE, Status.NOT_CONFIGURED);
        status(engine, "synthetic-session-a", "ORG-001", Action.EXPORT, Status.NOT_CONFIGURED);
        status(engine, "synthetic-session-b", "ORG-001", Action.HANDLE, Status.ALLOWED);
        status(engine, "synthetic-session-b", "ORG-002", Action.HANDLE, Status.DENIED);
        status(engine, "synthetic-session-c", "ORG-002", Action.HANDLE, Status.ALLOWED);
        status(engine, "synthetic-session-c", "ORG-000", Action.VIEW, Status.DENIED);
        status(engine, "synthetic-session-d", "ORG-001", Action.EXPORT, Status.ALLOWED);
        status(engine, "synthetic-session-d", "ORG-000", Action.EXPORT, Status.DENIED);
        status(engine, "synthetic-session-e", "ORG-002", Action.VIEW, Status.DENIED);
        for (String invalid : Arrays.asList(null, "", " ", "PERSON-001", "1001", "arbitrary"))
            status(engine, invalid, "ORG-001", Action.VIEW, Status.UNAUTHENTICATED);
        check(engine.authorize("synthetic-session-a", null, resource("ORG-001"), Action.VIEW).status() == Status.UNAUTHENTICATED, "no verifier");
        check(engine.authorize("token", t -> null, resource("ORG-001"), Action.VIEW).status() == Status.UNAUTHENTICATED, "null verifier result");
        check(engine.authorize("token", t -> OptionalLong.of(0), resource("ORG-001"), Action.VIEW).status() == Status.UNAUTHENTICATED, "invalid account id");
        check(engine.authorize("token", t -> { throw new Exception("private detail"); }, resource("ORG-001"), Action.VIEW)
                .status() == Status.AUTHENTICATION_UNAVAILABLE, "verification failure");
        AtomicInteger calls = new AtomicInteger();
        SessionVerifier revoked = t -> calls.getAndIncrement() == 0 ? OptionalLong.of(1001) : OptionalLong.empty();
        check(engine.authorize("token", revoked, resource("ORG-001"), Action.VIEW).allowed(), "initial authenticated request");
        check(!engine.authorize("token", revoked, resource("ORG-001"), Action.VIEW).allowed() && calls.get() == 2, "revoke takes effect per request");
        check(engine.authorize("token", t -> OptionalLong.of(8888), resource("ORG-001"), Action.VIEW).status() == Status.NOT_CONFIGURED, "unbound account");
        check(engine.authorize("synthetic-session-a", VERIFY, new Resource("TRAINING.RECORD", "ORG-001"), Action.VIEW).status() == Status.NOT_CONFIGURED, "exact resource match");
        check(engine.authorize("synthetic-session-a", VERIFY, null, Action.VIEW).status() == Status.NOT_CONFIGURED, "missing resource");
        check(engine.authorize("synthetic-session-a", VERIFY, resource("ORG-001"), null).status() == Status.NOT_CONFIGURED, "missing action");
        status(engine, "synthetic-session-a", "ORG-999", Action.VIEW, Status.NOT_CONFIGURED);
        check(!new Engine(null).authorize("synthetic-session-a", VERIFY, resource("ORG-001"), Action.VIEW).allowed(), "missing configuration");

        List<AccountBinding> bindings = new ArrayList<>(BASE.accountBindings());
        bindings.set(0, new AccountBinding(1001, "PERSON-001", false));
        status(new Engine(copy(BASE.people(), BASE.organizations(), BASE.relations(), bindings, BASE.grants())),
                "synthetic-session-a", "ORG-001", Action.VIEW, Status.DENIED);
        bindings.add(new AccountBinding(1002, "PERSON-004", true));
        issue(copy(BASE.people(), BASE.organizations(), BASE.relations(), bindings, BASE.grants()), "DUPLICATE_BINDING");

        List<Person> people = new ArrayList<>(BASE.people());
        people.add(BASE.people().get(0));
        issue(copyPeople(people), "DUPLICATE_CODE");
        people = new ArrayList<>(BASE.people());
        Person first = people.get(0);
        people.set(0, person(first, "PERSON-999", first.bpPersonCode(), first.roleCodes(), first.enabled()));
        issue(copyPeople(people), "UNKNOWN_PERSON");
        people.set(0, person(first, null, first.bpPersonCode(), first.roleCodes(), true));
        issue(copyPeople(people), "MISSING_RELATION");
        people.set(0, person(first, first.leaderPersonCode(), "PERSON-002", first.roleCodes(), true));
        issue(copyPeople(people), "RELATION_ROLE_MISMATCH");
        people.set(0, person(first, first.personCode(), first.bpPersonCode(), first.roleCodes(), true));
        issue(copyPeople(people), "SELF_RELATION");
        people = new ArrayList<>(BASE.people());
        Person leader = people.get(1);
        people.set(1, person(leader, null, null, leader.roleCodes(), false));
        issue(copyPeople(people), "INACTIVE_RELATION");
        people.set(1, new Person(leader.personCode(), leader.organizationCode(), Set.of("ORG-002"), null, null, leader.roleCodes(), true));
        issue(copyPeople(people), "RELATION_OUT_OF_SCOPE");

        List<RoleRelations> relations = new ArrayList<>(BASE.relations());
        relations.remove(0);
        issue(copy(BASE.people(), BASE.organizations(), relations, BASE.accountBindings(), BASE.grants()), "NOT_CONFIGURED");
        Configuration noPatterns = new Configuration(BASE.version(), null, BASE.roleCodes(), BASE.organizations(), BASE.people(), BASE.relations(), BASE.accountBindings(), BASE.grants());
        issue(noPatterns, "NOT_CONFIGURED");
        Configuration invalidPattern = new Configuration(BASE.version(), new CodeRules("[", "PERSON-[0-9]{3}", "ROLE-[A-Z]+"), BASE.roleCodes(), BASE.organizations(), BASE.people(), BASE.relations(), BASE.accountBindings(), BASE.grants());
        issue(invalidPattern, "INVALID_PATTERN");
        people = new ArrayList<>(BASE.people());
        people.set(0, new Person(" PERSON-001", first.organizationCode(), Set.of(), first.leaderPersonCode(), first.bpPersonCode(), first.roleCodes(), true));
        issue(copyPeople(people), "INVALID_CODE");
        people.set(0, person(first, first.leaderPersonCode(), first.bpPersonCode(), Set.of("admin"), true));
        issue(copyPeople(people), "UNKNOWN_ROLE");

        List<Organization> organizations = new ArrayList<>(BASE.organizations());
        organizations.set(0, new Organization("ORG-000", "ORG-001", true));
        issue(copy(BASE.people(), organizations, BASE.relations(), BASE.accountBindings(), BASE.grants()), "RELATION_CYCLE");
        organizations = new ArrayList<>(BASE.organizations());
        organizations.set(2, new Organization("ORG-002", "ORG-000", false));
        status(new Engine(copy(BASE.people(), organizations, BASE.relations(), BASE.accountBindings(), BASE.grants())),
                "synthetic-session-d", "ORG-002", Action.EXPORT, Status.DENIED);
        organizations.set(0, new Organization("ORG-000", null, false));
        check(!new Engine(copy(BASE.people(), organizations, BASE.relations(), BASE.accountBindings(), BASE.grants()))
                .authorize("synthetic-session-a", VERIFY, resource("ORG-001"), Action.VIEW).allowed(), "disabled ancestor never allowed");

        List<Grant> grants = new ArrayList<>(BASE.grants());
        grants.add(new Grant("stop-export", "ROLE-STAFF", "training.record", Action.EXPORT, Effect.DENY, Scope.OWN_ORG, Set.of()));
        people = new ArrayList<>(BASE.people());
        people.set(0, person(first, first.leaderPersonCode(), first.bpPersonCode(), Set.of("ROLE-STAFF", "ROLE-TRAINING"), true));
        Configuration multiple = copy(people, BASE.organizations(), BASE.relations(), BASE.accountBindings(), grants);
        status(new Engine(multiple), "synthetic-session-a", "ORG-001", Action.EXPORT, Status.DENIED);
        status(new Engine(multiple), "synthetic-session-a", "ORG-002", Action.EXPORT, Status.ALLOWED);
        Collections.reverse(grants);
        status(new Engine(copy(people, BASE.organizations(), BASE.relations(), BASE.accountBindings(), grants)), "synthetic-session-a", "ORG-001", Action.EXPORT, Status.DENIED);
        grants.add(new Grant("malformed-deny", "ROLE-STAFF", "training.record", Action.VIEW, Effect.DENY, null, Set.of()));
        issue(copy(people, BASE.organizations(), BASE.relations(), BASE.accountBindings(), grants), "NOT_CONFIGURED");
        grants = new ArrayList<>(BASE.grants());
        grants.add(new Grant("empty-scope", "ROLE-STAFF", "training.record", Action.VIEW, Effect.ALLOW, Scope.NAMED_ORGS, Set.of()));
        issue(copy(BASE.people(), BASE.organizations(), BASE.relations(), BASE.accountBindings(), grants), "NOT_CONFIGURED");
        status(new Engine(copy(BASE.people(), BASE.organizations(), BASE.relations(), BASE.accountBindings(), List.of())), "synthetic-session-a", "ORG-001", Action.VIEW, Status.NOT_CONFIGURED);

        // Collection ownership: caller mutations cannot silently change an approved snapshot.
        List<Person> mutable = new ArrayList<>(BASE.people());
        Configuration snapshot = copyPeople(mutable);
        mutable.clear();
        check(snapshot.people().size() == 5, "defensive list copy");
        try { snapshot.people().clear(); throw new AssertionError("snapshot is mutable"); }
        catch (UnsupportedOperationException expected) { checks++; }
        check(engine.authorize("synthetic-session-a", VERIFY, resource("ORG-001"), Action.VIEW).toMap().get("allowed").equals(true), "JSON-ready decision");

        // Malformed DTO rows remain fail-closed validation results, not accidental partial grants.
        issue(copy(Arrays.asList((Person) null), BASE.organizations(), BASE.relations(), BASE.accountBindings(), BASE.grants()), "INVALID_ROW");
        issue(copy(BASE.people(), Arrays.asList((Organization) null), BASE.relations(), BASE.accountBindings(), BASE.grants()), "INVALID_ROW");
        issue(copy(BASE.people(), BASE.organizations(), Arrays.asList((RoleRelations) null), BASE.accountBindings(), BASE.grants()), "INVALID_ROW");
        issue(copy(BASE.people(), BASE.organizations(), BASE.relations(), Arrays.asList((AccountBinding) null), BASE.grants()), "INVALID_ROW");
        issue(copy(BASE.people(), BASE.organizations(), BASE.relations(), BASE.accountBindings(), Arrays.asList((Grant) null)), "INVALID_ROW");
        Configuration missingRoleDictionary = new Configuration(BASE.version(), BASE.codeRules(), null,
                BASE.organizations(), BASE.people(), List.of(new RoleRelations(null, null, null)), BASE.accountBindings(), BASE.grants());
        issue(missingRoleDictionary, "NOT_CONFIGURED");
        people = new ArrayList<>(BASE.people());
        people.set(0, new Person(first.personCode(), null, Set.of(), first.leaderPersonCode(), first.bpPersonCode(), first.roleCodes(), true));
        people.set(1, new Person(leader.personCode(), leader.organizationCode(), null, null, null, leader.roleCodes(), true));
        issue(copyPeople(people), "UNKNOWN_ORGANIZATION");
        summaryContractChecks();
        System.out.println("M01 OrganizationAccess: " + checks + " assertions passed (synthetic only, no database).");
    }
    private static void summaryContractChecks() {
        Engine engine = new Engine(BASE);
        // Existing generic training grants do not become summary privileges.
        for (SummaryPermission permission : SummaryPermission.values()) {
            check(engine.authorizeSummary("synthetic-session-d", VERIFY, permission, "ORG-001").status()
                    == Status.NOT_CONFIGURED, "no implicit summary grant: " + permission);
        }
        check(engine.authorizeSummary("verified@example.invalid", VERIFY, SummaryPermission.EXPORT, "ORG-001").status()
                == Status.UNAUTHENTICATED, "verified-looking email is not a session");
        check(engine.authorizeSummary("synthetic-session-a", VERIFY, null, "ORG-001").status()
                == Status.NOT_CONFIGURED, "missing summary operation");
        check(engine.authorizeSummary("synthetic-session-a", token -> OptionalLong.of(7001), SummaryPermission.EDIT, "ORG-001").status()
                == Status.NOT_CONFIGURED, "verified account without personnel mapping has no summary permission");

        // Explicit synthetic grants, no automatic promotion of training staff or email owners.
        List<Grant> grants = new ArrayList<>(BASE.grants());
        grants.add(summaryGrant("summary-editor", "ROLE-STAFF", SummaryPermission.EDIT, Scope.OWN_ORG, Set.of()));
        grants.add(summaryGrant("summary-branch-review", "ROLE-LEADER", SummaryPermission.REVIEW_BRANCH, Scope.RESPONSIBLE_ORGS, Set.of()));
        grants.add(summaryGrant("summary-bp-review", "ROLE-BP", SummaryPermission.REVIEW_BP, Scope.RESPONSIBLE_ORGS, Set.of()));
        Engine summary = new Engine(copy(BASE.people(), BASE.organizations(), BASE.relations(), BASE.accountBindings(), grants));
        check(summary.authorizeSummary("synthetic-session-a", VERIFY, SummaryPermission.EDIT, "ORG-001").allowed(), "explicit editor permitted");
        check(summary.authorizeSummary("synthetic-session-a", VERIFY, SummaryPermission.EDIT, "ORG-002").status() == Status.DENIED, "editor organization scope");
        check(summary.authorizeSummary("synthetic-session-a", VERIFY, SummaryPermission.REVIEW_BRANCH, "ORG-001").status() == Status.NOT_CONFIGURED, "edit is not branch review");
        check(summary.authorizeSummary("synthetic-session-b", VERIFY, SummaryPermission.REVIEW_BRANCH, "ORG-001").allowed(), "explicit branch reviewer");
        check(summary.authorizeSummary("synthetic-session-b", VERIFY, SummaryPermission.REVIEW_BP, "ORG-001").status() == Status.NOT_CONFIGURED, "branch review is not BP review");
        check(summary.authorizeSummary("synthetic-session-c", VERIFY, SummaryPermission.REVIEW_BP, "ORG-001").allowed(), "explicit BP reviewer, no order assumed");
        check(summary.authorizeSummary("synthetic-session-c", VERIFY, SummaryPermission.EXPORT, "ORG-001").status() == Status.NOT_CONFIGURED, "review is not export");
        check(summary.authorizeSummary("synthetic-session-a", VERIFY, SummaryPermission.READ, "ORG-001").status() == Status.NOT_CONFIGURED, "read requires explicit scope too");

        Set<String> roles = new HashSet<>(BASE.roleCodes());
        roles.add("ROLE-SUMMARYADMIN");
        List<RoleRelations> relations = new ArrayList<>(BASE.relations());
        relations.add(new RoleRelations("ROLE-SUMMARYADMIN", BASE.relations().get(3).leader(), BASE.relations().get(3).bp()));
        List<Person> people = new ArrayList<>(BASE.people());
        Person training = people.get(3);
        people.set(3, person(training, null, null, Set.of("ROLE-TRAINING", "ROLE-SUMMARYADMIN"), true));
        grants.add(summaryGrant("explicit-summary-admin", "ROLE-SUMMARYADMIN", SummaryPermission.EXPORT, Scope.NAMED_ORGS, Set.of("ORG-001")));
        Configuration explicitAdmin = new Configuration("demo-summary-contract-v1", BASE.codeRules(), roles,
                BASE.organizations(), people, relations, BASE.accountBindings(), grants);
        Engine admin = new Engine(explicitAdmin);
        check(admin.validation().valid(), "summary administrator fixture valid");
        check(admin.authorizeSummary("synthetic-session-d", VERIFY, SummaryPermission.EXPORT, "ORG-001").allowed(), "explicit summary administrator export");
        check(admin.authorizeSummary("synthetic-session-d", VERIFY, SummaryPermission.EXPORT, "ORG-002").status() == Status.DENIED, "administrator has no global scope bypass");
        check(admin.authorizeSummary("synthetic-session-d", VERIFY, SummaryPermission.REVIEW_BRANCH, "ORG-001").status() == Status.NOT_CONFIGURED, "export administrator not implicitly reviewer");
        check(admin.authorizeSummary("synthetic-session-a", VERIFY, SummaryPermission.EXPORT, "ORG-001").status() == Status.NOT_CONFIGURED, "other accounts do not inherit admin export");
    }
    private static Grant summaryGrant(String id, String role, SummaryPermission permission, Scope scope, Set<String> orgs) {
        return new Grant(id, role, permission.resource(), permission.action(), Effect.ALLOW, scope, orgs);
    }
    private static Resource resource(String org) { return new Resource("training.record", org); }
    private static Person person(Person p, String leader, String bp, Set<String> roles, boolean enabled) {
        return new Person(p.personCode(), p.organizationCode(), p.responsibleOrganizationCodes(), leader, bp, roles, enabled);
    }
    private static Configuration copyPeople(List<Person> people) { return copy(people, BASE.organizations(), BASE.relations(), BASE.accountBindings(), BASE.grants()); }
    private static Configuration copy(List<Person> people, List<Organization> organizations, List<RoleRelations> relations, List<AccountBinding> bindings, List<Grant> grants) {
        return new Configuration(BASE.version(), BASE.codeRules(), BASE.roleCodes(), organizations, people, relations, bindings, grants);
    }
    private static void status(Engine engine, String credential, String org, Action action, Status expected) {
        Decision result = engine.authorize(credential, VERIFY, resource(org), action);
        check(result.status() == expected, action + " " + org + " expected " + expected + ", got " + result);
    }
    private static void issue(Configuration config, String code) {
        Engine engine = new Engine(config);
        check(engine.validation().issues().stream().anyMatch(i -> i.code().equals(code)), "expected " + code + ": " + engine.validation().issues());
        check(!engine.authorize("synthetic-session-a", VERIFY, resource("ORG-001"), Action.VIEW).allowed(), "invalid config must never allow");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
