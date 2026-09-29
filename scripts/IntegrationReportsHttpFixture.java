package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Test-only legacy/corruption fixture. Normal workflow and reviewed facts are created through real HTTP. */
public final class IntegrationReportsHttpFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected isolated port, inspect or corrupt");
        Path root = Path.of(required("integration.reports.root")).toRealPath();
        Path data = Path.of(required("data.dir")).toRealPath();
        if (!root.getFileName().toString().startsWith("yanxu-reports-http-") || !data.getParent().equals(root)
                || !"127.0.0.1".equals(System.getProperty("bind.address")) || Boolean.getBoolean("bootstrap.demo")
                || !required("bootstrap.admin.password").matches("[a-f0-9]{48}"))
            throw new IllegalArgumentException("Explicit isolated loopback fixture directory required");
        if (args[0].equals("inspect")) {
            Map<String,Object> result = new TreeMap<>();
            for (Map<String,Object> table : Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
                String name = table.get("name").toString();
                if (!name.matches("[A-Z0-9_]+")) throw new IllegalStateException("Unexpected table identifier");
                List<String> rows = new ArrayList<>();
                for (Map<String,Object> row : Db.query("SELECT * FROM " + name)) rows.add(Json.write(new TreeMap<>(row)));
                Collections.sort(rows);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(rows).getBytes(StandardCharsets.UTF_8)));
                result.put(name, Map.of("count", rows.size(), "sha256", hash));
            }
            System.out.println(Json.write(result)); Db.get().close(); return;
        }
        if (args[0].equals("corrupt")) {
            // Deliberate offline faults only after the script has created genuine HTTP verified sources.
            long invalid = record("SYNTHETIC CORRUPT REVISION"), moved = record("SYNTHETIC DATE DRIFT");
            Db.transaction(() -> {
                Db.exec("UPDATE m05_fact_revisions SET actor_code='P999' WHERE dispatch_id=? AND revision=(SELECT revision FROM m05_delivery_facts WHERE dispatch_id=?)", invalid, invalid);
                Db.exec("UPDATE dispatches SET teach_date='2025-02-18' WHERE id=?", moved);
                return null;
            });
            Db.get().close(); return;
        }
        try (var entries = Files.list(data)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Fresh empty fixture data required");
        }
        Db.init();
        if (OrganizationAccessStore.configuration() != null || Db.count("m05_delivery_facts") != 0)
            throw new IllegalStateException("Unexpected identity or fact seed");
        long teacher = Db.insert("INSERT INTO teachers(name,status,fee_rate,base_province,base_city) VALUES(?,'在库',999,'浙江','杭州')", "SYNTHETIC HISTORICAL TEACHER");
        long legacy = project(null, "SYNTHETIC UNMIGRATED REPORT PROJECT");
        dispatch(legacy, teacher, "SYNTHETIC UNMIGRATED HOURS", "2025-01-10", 999);
        long demand = Db.insert("INSERT INTO demands(title,unit,hours,status) VALUES(?,'SYNTHETIC',1,'进行中')", "SYNTHETIC REPORT HISTORY");
        long controlled = project(demand, "SYNTHETIC CONTROLLED REPORT HISTORY");
        Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct','001','P1','{}','{}','SYNTHETIC-HISTORY')", demand);
        Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'900','P4',1,'2025-01-01T00:00:00Z')", demand, controlled);
        dispatch(controlled, teacher, "SYNTHETIC HISTORICAL FACT MISSING", "2025-01-15", 999);
        dispatch(controlled, teacher, "SYNTHETIC HISTORICAL DATE UNKNOWN", "", 999);
        dispatch(controlled, teacher, "SYNTHETIC HISTORICAL FUTURE", "2099-01-01", 999);
        Main.main(args);
    }
    private static long record(String subject) throws Exception {
        List<Map<String,Object>> rows = Db.query("SELECT d.id FROM dispatches d JOIN m05_delivery_facts f ON f.dispatch_id=d.id WHERE d.subject=? AND d.status='已完成' AND f.revision=2", subject);
        if (rows.size() != 1) throw new IllegalStateException("Expected one genuine HTTP reviewed synthetic source");
        return ((Number)rows.get(0).get("id")).longValue();
    }
    private static long project(Long demand, String title) throws Exception {
        return Db.insert("INSERT INTO projects(demand_id,title,unit,hours,amount,start_date,end_date,owner,participant_count,delivery_mode,status) VALUES(?,?,'SYNTHETIC',1,0,'2025-01-01','2025-01-31','SYNTHETIC',1,'线上','进行中')", demand, title);
    }
    private static void dispatch(long project, long teacher, String subject, String day, int hours) throws Exception {
        Db.insert("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,hours,status,material_status) VALUES(?,?,?,?,?,'已完成','已就绪')", project, teacher, subject, day, hours);
    }
    private static String required(String name) {
        String value = System.getProperty(name, "");
        if (value.isBlank()) throw new IllegalArgumentException("Missing isolated fixture setting: " + name);
        return value;
    }
}
