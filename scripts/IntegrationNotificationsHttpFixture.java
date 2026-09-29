package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Test-only historical outbox and offline faults; all normal notices enter through actual Main/Api. */
public final class IntegrationNotificationsHttpFixture {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected port or a named offline operation");
        Path root=Path.of(required("integration.notifications.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-notifications-http-")||!data.getParent().equals(root)||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")||!required("bootstrap.admin.password").matches("[a-f0-9]{48}"))throw new IllegalArgumentException("Explicit isolated synthetic fixture required");
        if(args[0].equals("inspect")){
            Map<String,Object> tables=new TreeMap<>();
            for(Map<String,Object> table:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
                String name=table.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected identifier");
                List<String> rows=new ArrayList<>();for(Map<String,Object> row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);
                tables.put(name,Map.of("count",rows.size(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(rows).getBytes(StandardCharsets.UTF_8)))));
            }
            System.out.println(Json.write(Map.of("tables",tables,"outbox_statuses",Db.query("SELECT status,COUNT(*) AS count FROM workflow_outbox GROUP BY status"),"event_statuses",Db.query("SELECT delivery_status,blocked_reason,COUNT(*) AS count FROM s01_notification_events GROUP BY delivery_status,blocked_reason"))));Db.get().close();return;
        }
        if(args[0].equals("faults")){
            long rollback=demand("SYNTHETIC NOTIFICATION ROLLBACK"),source=demand("SYNTHETIC NOTIFICATION SOURCE");
            Db.exec("ALTER TABLE s01_notifications ADD CONSTRAINT synthetic_s01_rollback CHECK(event_key NOT LIKE 'm03:"+rollback+":%')");
            Map<String,Object> event=Db.one("SELECT event_json FROM approval_events WHERE workflow_id=? AND version=1",source);
            if(event==null)throw new IllegalStateException("Expected genuine HTTP-created source event");
            String original=event.get("event_json").toString();Files.writeString(root.resolve("source-event.json"),original,StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked") Map<String,Object> changed=new LinkedHashMap<>((Map<String,Object>)Json.parse(original));changed.put("comment","SYNTHETIC SOURCE TAMPER");
            Db.exec("UPDATE approval_events SET event_json=? WHERE workflow_id=? AND version=1",Json.write(changed),source);Db.get().close();return;
        }
        if(args[0].equals("restore")){
            long source=demand("SYNTHETIC NOTIFICATION SOURCE");
            Db.exec("ALTER TABLE s01_notifications DROP CONSTRAINT synthetic_s01_rollback");
            Db.exec("UPDATE approval_events SET event_json=? WHERE workflow_id=? AND version=1",Files.readString(root.resolve("source-event.json"),StandardCharsets.UTF_8),source);Db.get().close();return;
        }
        try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
        Db.init();if(OrganizationAccessStore.configuration()!=null||Db.count("s01_notification_events")!=0||Db.count("s01_notifications")!=0)throw new IllegalStateException("Unexpected identity or notification seed");
        long old=Db.insert("INSERT INTO demands(title,unit,hours,status) VALUES('SYNTHETIC HISTORICAL OUTBOX','SYNTHETIC',1,'草稿')");
        Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,TRUE,'direct','001','P1','{}','{}','SYNTHETIC-HISTORY')",old);
        Db.exec("INSERT INTO workflow_outbox(event_key,demand_id,payload,status,created_at) VALUES('synthetic-historical-unproven',?,?,'PENDING',?)",old,Json.write(Map.of("kind","TODO","target","PARTICIPANT","recipient","P2","event_version",1)),Instant.now().toString());
        String expired=Auth.login("admin",required("bootstrap.admin.password"));Auth.Session session=Auth.get(expired);if(session==null)throw new AssertionError("Real fixture session");session.time=0;
        Path tokenFile=root.resolve("expired-token.txt");Files.writeString(tokenFile,expired,StandardCharsets.UTF_8);Files.setPosixFilePermissions(tokenFile,PosixFilePermissions.fromString("rw-------"));
        Main.main(args);
    }
    private static long demand(String title)throws Exception{
        List<Map<String,Object>> rows=Db.query("SELECT d.id FROM demands d JOIN workflow_demands w ON w.demand_id=d.id WHERE d.title=?",title);
        if(rows.size()!=1)throw new IllegalStateException("Expected one genuine HTTP-created synthetic demand");return ((Number)rows.get(0).get("id")).longValue();
    }
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing isolated fixture setting: "+name);return value;}
}
