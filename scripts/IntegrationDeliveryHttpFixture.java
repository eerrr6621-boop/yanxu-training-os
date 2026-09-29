package com.training;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Scripts-only historical fixture; all new workflow and M05 writes run over real Main/Api HTTP. */
public final class IntegrationDeliveryHttpFixture {
    private static final List<String> TABLES = List.of("m05_delivery_facts", "m05_fact_revisions",
            "m05_policy_versions", "m05_settlement_configs", "m05_settlement_snapshots", "m05_requests");
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected port or inspect");
        Path root = Path.of(required("integration.delivery.root")).toRealPath();
        Path data = Path.of(required("data.dir")).toRealPath();
        if (!root.getFileName().toString().startsWith("yanxu-delivery-http-") || !data.getParent().equals(root)
                || !"127.0.0.1".equals(System.getProperty("bind.address")) || Boolean.getBoolean("bootstrap.demo"))
            throw new IllegalArgumentException("Explicit isolated loopback fixture directory required");
        if ("inspect".equals(args[0])) {
            Map<String,Object> result = new LinkedHashMap<>();
            for (String table : TABLES) result.put(table, Db.count(table));
            result.put("historical_fees", Db.query("SELECT f.id,f.status,f.project_id,f.hours,f.amount,f.pay_date FROM fees f JOIN projects p ON p.id=f.project_id WHERE p.title='SYNTHETIC CONTROLLED HISTORY' ORDER BY f.id"));
            result.put("legacy_m05_facts", Db.one("SELECT COUNT(*) c FROM m05_delivery_facts f JOIN projects p ON p.id=f.project_id WHERE p.title='SYNTHETIC LEGACY PROJECT'").get("c"));
            System.out.println(Json.write(result)); Db.get().close(); return;
        }
        try (var entries = Files.list(data)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Fresh empty fixture data required");
        }
        if (!required("bootstrap.admin.password").matches("[a-f0-9]{48}"))
            throw new IllegalArgumentException("Random fixture password required");
        Db.init();
        if (OrganizationAccessStore.configuration() != null) throw new IllegalStateException("Unexpected identity seed");
        for (String table : TABLES) if (Db.count(table) != 0) throw new IllegalStateException("Unexpected M05 seed");
        long teacher = Db.insert("INSERT INTO teachers(name,status,teacher_level,fee_rate,base_province,base_city) VALUES(?,'在库','讲师',100,'浙江','杭州')", "SYNTHETIC DELIVERY TEACHER");
        Db.insert("INSERT INTO teachers(name,status,teacher_level,fee_rate,base_province,base_city) VALUES(?,'在库','讲师',200,'浙江','杭州')", "SYNTHETIC ALTERNATE TEACHER");
        long legacy = project(null, "SYNTHETIC LEGACY PROJECT");
        dispatch(legacy, teacher, "SYNTHETIC LEGACY CONFIRMED", "已确认", 2);
        long demand = Db.insert("INSERT INTO demands(title,unit,hours,status) VALUES(?,'SYNTHETIC',2,'进行中')", "SYNTHETIC HISTORICAL ACCEPTANCE");
        long controlled = project(demand, "SYNTHETIC CONTROLLED HISTORY");
        // Deliberate historical acceptance with mixed old fee rows; this is never exposed as a runtime seeding API.
        Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct','001','P1','{}','{}','SYNTHETIC-HISTORICAL')", demand);
        Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'900','P4',1,'2026-01-01T00:00:00Z')", demand, controlled);
        dispatch(controlled, teacher, "SYNTHETIC HISTORICAL COMPLETED", "已完成", 1);
        dispatch(controlled, teacher, "SYNTHETIC HISTORICAL CONFIRMED", "已确认", 1);
        Db.insert("INSERT INTO fees(project_id,teacher_id,hours,rate,amount,status,pay_date,remark) VALUES(?,?,1,100,100,'待发放','','SYNTHETIC PENDING HISTORY')", controlled, teacher);
        Db.insert("INSERT INTO fees(project_id,teacher_id,hours,rate,amount,status,pay_date,remark) VALUES(?,?,1,100,100,'已发放','2025-01-01','SYNTHETIC PAID HISTORY')", controlled, teacher);
        Main.main(args);
    }
    private static long project(Long demand, String title) throws Exception {
        return Db.insert("INSERT INTO projects(demand_id,title,unit,hours,amount,start_date,end_date,owner,participant_count,delivery_mode,status) VALUES(?,?,'SYNTHETIC',2,0,'2025-01-01','2025-01-02','SYNTHETIC',1,'线上','进行中')", demand, title);
    }
    private static void dispatch(long project,long teacher,String subject,String status,int hours) throws Exception {
        Db.insert("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,hours,status,material_status,remark) VALUES(?,?,?,'2025-01-01',?,?,'已就绪','SYNTHETIC HISTORY')", project, teacher, subject, hours, status);
    }
    private static String required(String name) {
        String value=System.getProperty(name, "");
        if(value.isBlank()) throw new IllegalArgumentException("Missing isolated fixture setting: " + name);
        return value;
    }
}
