package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Scripts-only historical rows and offline inspection. All preview requests use real Main/Api. */
public final class IntegrationPolicyPreviewHttpFixture {
    private static final List<String> TABLES = List.of("m05_delivery_facts", "m05_fact_revisions",
            "m05_policy_versions", "m05_settlement_configs", "m05_settlement_snapshots", "m05_requests",
            "fees", "dispatches", "projects", "teachers");
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected port or inspect");
        Path root=Path.of(required("integration.policy.preview.root")).toRealPath();
        Path data=Path.of(required("data.dir")).toRealPath();
        if (!root.getFileName().toString().startsWith("yanxu-policy-preview-http-") || !data.getParent().equals(root)
                || !"127.0.0.1".equals(System.getProperty("bind.address")) || Boolean.getBoolean("bootstrap.demo"))
            throw new IllegalArgumentException("Explicit isolated loopback fixture directory required");
        if ("inspect".equals(args[0])) {
            Map<String,Object> result=new LinkedHashMap<>();
            for (String table:TABLES) {
                List<String> rows=new ArrayList<>();
                for (Map<String,Object> row:Db.query("SELECT * FROM "+table)) rows.add(Json.write(new TreeMap<>(row)));
                Collections.sort(rows);
                String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(rows).getBytes(StandardCharsets.UTF_8)));
                result.put(table,Map.of("count",rows.size(),"sha256",digest));
            }
            System.out.println(Json.write(result)); Db.get().close(); return;
        }
        try(var entries=Files.list(data)) { if(entries.findAny().isPresent()) throw new IllegalArgumentException("Fresh empty data required"); }
        if(!required("bootstrap.admin.password").matches("[a-f0-9]{48}")) throw new IllegalArgumentException("Random fixture password required");
        Db.init();
        if(OrganizationAccessStore.configuration()!=null) throw new IllegalStateException("No implicit identity config permitted");
        for(String table:TABLES) if(Db.count(table)!=0) throw new IllegalStateException("No implicit business seed permitted: "+table);
        long teacher=Db.insert("INSERT INTO teachers(name,status,teacher_level,fee_rate,base_province,base_city,in_date) VALUES(?,'在库','特级讲师',99999,'浙江','杭州','2020-01-01')","SYNTHETIC POLICY HISTORY TEACHER");
        long old=project(null,"SYNTHETIC POLICY UNMIGRATED");
        dispatch(old,teacher,"SYNTHETIC POLICY UNMIGRATED CLASS");
        for(String type:List.of("PAID","PENDING")) {
            long demand=Db.insert("INSERT INTO demands(title,unit,hours,status) VALUES(?,'SYNTHETIC',1,'进行中')","SYNTHETIC POLICY "+type+" SOURCE");
            long project=project(demand,"SYNTHETIC POLICY "+type+" HISTORY");
            Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct','001','P1','{}','{}','SYNTHETIC-HISTORY')",demand);
            Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'900','P4',1,'2025-01-01T00:00:00Z')",demand,project);
            dispatch(project,teacher,"SYNTHETIC POLICY "+type+" CLASS");
            Db.insert("INSERT INTO fees(project_id,teacher_id,hours,rate,amount,status,pay_date,remark) VALUES(?,?,1,123,123,?,?,?)",
                    project,teacher,type.equals("PAID")?"已发放":"待发放",type.equals("PAID")?"2025-01-02":"","SYNTHETIC "+type+" ORIGINAL");
        }
        Main.main(args);
    }
    private static long project(Long demand,String title) throws Exception {
        return Db.insert("INSERT INTO projects(demand_id,title,unit,hours,amount,start_date,end_date,owner,participant_count,delivery_mode,status) VALUES(?,?,'SYNTHETIC',1,999999,'2025-01-01','2025-01-02','SYNTHETIC',1,'线上','进行中')",demand,title);
    }
    private static void dispatch(long project,long teacher,String subject) throws Exception {
        Db.insert("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,hours,status,material_status) VALUES(?,?,?,'2025-01-01',99,'已确认','已就绪')",project,teacher,subject);
    }
    private static String required(String name) {
        String value=System.getProperty(name,"");
        if(value.isBlank()) throw new IllegalArgumentException("Missing fixture setting: "+name);
        return value;
    }
}
