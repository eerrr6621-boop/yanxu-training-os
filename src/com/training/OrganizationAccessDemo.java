package com.training;

import java.util.*;
import com.training.OrganizationAccess.*;

/** Explicit synthetic fixture only. Never install this fixture/verifier in a live service. */
public final class OrganizationAccessDemo {
    private OrganizationAccessDemo() {}
    public static Configuration configuration() {
        Set<String> roles = Set.of("ROLE-STAFF", "ROLE-LEADER", "ROLE-BP", "ROLE-TRAINING");
        RelationRule optionalLeader = new RelationRule(false, Set.of("ROLE-LEADER"), true, false);
        RelationRule optionalBp = new RelationRule(false, Set.of("ROLE-BP"), true, false);
        return new Configuration("demo-m01-v1",
                new CodeRules("ORG-[0-9]{3}", "PERSON-[0-9]{3}", "ROLE-[A-Z]+"), roles,
                List.of(new Organization("ORG-000", null, true),
                        new Organization("ORG-001", "ORG-000", true),
                        new Organization("ORG-002", "ORG-000", true)),
                List.of(new Person("PERSON-001", "ORG-001", Set.of(), "PERSON-002", "PERSON-003", Set.of("ROLE-STAFF"), true),
                        new Person("PERSON-002", "ORG-001", Set.of("ORG-001"), null, null, Set.of("ROLE-LEADER"), true),
                        new Person("PERSON-003", "ORG-000", Set.of("ORG-001", "ORG-002"), null, null, Set.of("ROLE-BP"), true),
                        new Person("PERSON-004", "ORG-000", Set.of(), null, null, Set.of("ROLE-TRAINING"), true),
                        new Person("PERSON-005", "ORG-002", Set.of(), null, null, Set.of("ROLE-TRAINING"), false)),
                List.of(new RoleRelations("ROLE-STAFF", new RelationRule(true, Set.of("ROLE-LEADER"), true, false),
                                new RelationRule(true, Set.of("ROLE-BP"), true, false)),
                        new RoleRelations("ROLE-LEADER", optionalLeader, optionalBp),
                        new RoleRelations("ROLE-BP", optionalLeader, optionalBp),
                        new RoleRelations("ROLE-TRAINING", optionalLeader, optionalBp)),
                List.of(new AccountBinding(1001, "PERSON-001", true), new AccountBinding(1002, "PERSON-002", true),
                        new AccountBinding(1003, "PERSON-003", true), new AccountBinding(1004, "PERSON-004", true),
                        new AccountBinding(1005, "PERSON-005", true)),
                List.of(grant("staff-view", "ROLE-STAFF", Action.VIEW, Scope.OWN_ORG, Set.of()),
                        grant("leader-view", "ROLE-LEADER", Action.VIEW, Scope.RESPONSIBLE_ORGS, Set.of()),
                        grant("leader-handle", "ROLE-LEADER", Action.HANDLE, Scope.RESPONSIBLE_ORGS, Set.of()),
                        grant("bp-view", "ROLE-BP", Action.VIEW, Scope.RESPONSIBLE_ORGS, Set.of()),
                        grant("bp-handle", "ROLE-BP", Action.HANDLE, Scope.RESPONSIBLE_ORGS, Set.of()),
                        grant("training-view", "ROLE-TRAINING", Action.VIEW, Scope.NAMED_ORGS, Set.of("ORG-001", "ORG-002")),
                        grant("training-handle", "ROLE-TRAINING", Action.HANDLE, Scope.NAMED_ORGS, Set.of("ORG-001", "ORG-002")),
                        grant("training-export", "ROLE-TRAINING", Action.EXPORT, Scope.NAMED_ORGS, Set.of("ORG-001", "ORG-002"))));
    }
    private static Grant grant(String id, String role, Action action, Scope scope, Set<String> organizations) {
        return new Grant(id, role, "training.record", action, Effect.ALLOW, scope, organizations);
    }
}
