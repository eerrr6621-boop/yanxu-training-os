package com.training;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Scripts-only synthetic provisioning. Starts the real Main; never exposes a provisioning route. */
public final class IntegrationAccountCatalogHttpFixture {
    private static final String[] CATALOG_TABLES = {
            "m04_catalog_scopes", "m04_catalog_batches", "m04_catalog_revisions",
            "m04_teacher_bindings", "m04_catalog_events"};

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected port or inspect");
        Path root = Path.of(required("integration.account.catalog.root")).toRealPath();
        Path data = Path.of(required("data.dir")).toRealPath();
        if (!root.getFileName().toString().startsWith("yanxu-account-catalog-http-")
                || !data.getParent().equals(root) || !"127.0.0.1".equals(System.getProperty("bind.address"))
                || Boolean.getBoolean("bootstrap.demo"))
            throw new IllegalArgumentException("Explicit isolated loopback fixture directory required");
        if ("inspect".equals(args[0])) {
            Map<String,Object> counts = new LinkedHashMap<>();
            for (String table : CATALOG_TABLES) counts.put(table, Db.count(table));
            counts.put("organization_access_config", Db.count("organization_access_config"));
            System.out.println(Json.write(counts));
            Db.get().close();
            return;
        }
        try (var entries = Files.list(data)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Fresh empty fixture data required");
        }
        String password = required("bootstrap.admin.password");
        if (!password.matches("[a-f0-9]{48}")) throw new IllegalArgumentException("Random fixture password required");
        Db.init();
        if (OrganizationAccessStore.configuration() != null) throw new IllegalStateException("Unexpected seeded identity");
        for (String table : CATALOG_TABLES) {
            if (Db.count(table) != 0) throw new IllegalStateException("Unexpected seeded catalog");
        }
        String hash = Auth.hash(password);
        Map<String,Long> accounts = new LinkedHashMap<>();
        for (String name : List.of("manager", "reader", "other", "colleague", "unbound")) {
            // A legacy viewer with an explicit catalog grant must still reach the M04 route.
            String role = Set.of("reader", "unbound").contains(name) ? "manager" : "viewer";
            accounts.put(name, Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)",
                    "synthetic-" + name, hash, "SYNTHETIC " + name, role));
        }
        Set<String> roles = Set.of("MAINTAIN", "READER");
        RelationRule optional = new RelationRule(false, roles, false, false);
        Configuration config = new Configuration("synthetic-catalog-http-v1",
                new CodeRules("[0-9]{3}", "P[0-9]+", "[A-Z]+"), roles,
                List.of(new Organization("001", null, true), new Organization("002", null, true)),
                List.of(person("P1", "001", "MAINTAIN"), person("P2", "001", "READER"),
                        person("P3", "002", "MAINTAIN"), person("P4", "001", "MAINTAIN")),
                List.of(new RoleRelations("MAINTAIN", optional, optional), new RoleRelations("READER", optional, optional)),
                List.of(new AccountBinding(accounts.get("manager"), "P1", true),
                        new AccountBinding(accounts.get("reader"), "P2", true),
                        new AccountBinding(accounts.get("other"), "P3", true),
                        new AccountBinding(accounts.get("colleague"), "P4", true)),
                List.of(new Grant("maintain-read", "MAINTAIN", "catalog.read", Action.VIEW, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("maintain-write", "MAINTAIN", "catalog.manage", Action.HANDLE, Effect.ALLOW, Scope.OWN_ORG, Set.of()),
                        new Grant("reader-read", "READER", "catalog.read", Action.VIEW, Effect.ALLOW, Scope.OWN_ORG, Set.of())));
        Auth.Session admin = login("admin", password);
        OrganizationAccessStore.publish(admin, null, config);
        CourseCatalogIntegration.registerScope(login("synthetic-manager", password), "001");
        CourseCatalogIntegration.registerScope(login("synthetic-other", password), "002");
        Db.insert("INSERT INTO teachers(name,status,teacher_level,base_province,base_city) VALUES(?,?,?,?,?)",
                "SYNTHETIC CATALOG TEACHER", "在库", "讲师", "浙江", "杭州");
        // All subsequent requests use the production Main/Api handlers and fresh HTTP logins.
        Main.main(args);
    }

    private static Person person(String code, String org, String role) {
        return new Person(code, org, Set.of(), null, null, Set.of(role), true);
    }
    private static Auth.Session login(String username, String password) throws Exception {
        Auth.Session session = Auth.get(Auth.login(username, password));
        if (session == null || Auth.current(session) == null) throw new IllegalStateException("Synthetic authentication failed");
        return session;
    }
    private static String required(String name) {
        String value = System.getProperty(name, "");
        if (value.isBlank()) throw new IllegalArgumentException("Missing isolated fixture setting: " + name);
        return value;
    }
}
