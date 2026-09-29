package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Scripts-only legacy/history faults. Valid summaries are created solely through real Main/Api HTTP. */
public final class IntegrationSummariesHttpFixture {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected port, inspect or historical-states");
        Path root=Path.of(required("integration.summaries.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-summaries-http-")||!data.getParent().equals(root)||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")||!required("bootstrap.admin.password").matches("[a-f0-9]{48}"))throw new IllegalArgumentException("Explicit isolated synthetic fixture required");
        if(args[0].equals("inspect")){
            Map<String,Object> result=new TreeMap<>();
            for(Map<String,Object> table:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
                String name=table.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected identifier");
                List<String> rows=new ArrayList<>();for(Map<String,Object> row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);
                result.put(name,Map.of("count",rows.size(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(rows).getBytes(StandardCharsets.UTF_8)))));
            }
            System.out.println(Json.write(result));Db.get().close();return;
        }
        if(args[0].equals("historical-states")){
            long archived=summaryProject("SYNTHETIC SUMMARY ARCHIVE"),damaged=summaryProject("SYNTHETIC SUMMARY DAMAGED"),drift=summaryProject("SYNTHETIC SUMMARY DRIFT");
            Db.transaction(()->{
                Db.exec("UPDATE projects SET status='已归档' WHERE id=?",archived);
                Db.exec("UPDATE m08_summary_revisions SET content_json=? WHERE project_id=? AND revision=1","{\"achievements\":\"SYNTHETIC TAMPER\",\"issues\":\"\",\"nextSteps\":\"\"}",damaged);
                Db.exec("UPDATE workflow_demands SET organization_code='999' WHERE demand_id=(SELECT demand_id FROM projects WHERE id=?)",drift);
                return null;
            });Db.get().close();return;
        }
        try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
        Db.init();if(OrganizationAccessStore.configuration()!=null||Db.count("m08_summary_heads")!=0||Db.count("m08_summary_revisions")!=0||Db.count("m08_summary_requests")!=0)throw new IllegalStateException("Unexpected identity or summary seed");
        project(null,"SYNTHETIC LEGACY SUMMARY PROJECT");
        history("SYNTHETIC MISMATCHED SUMMARY SOURCE",1,1,false,true);
        history("SYNTHETIC STALE SUMMARY SOURCE",2,1,false,false);
        history("SYNTHETIC DRAFT SUMMARY SOURCE",1,1,true,false);
        String expired=Auth.login("admin",required("bootstrap.admin.password"));Auth.Session session=Auth.get(expired);if(session==null)throw new AssertionError("Real fixture session");session.time=0;
        Path tokenFile=root.resolve("expired-token.txt");Files.writeString(tokenFile,expired,StandardCharsets.UTF_8);Files.setPosixFilePermissions(tokenFile,PosixFilePermissions.fromString("rw-------"));
        Main.main(args);
    }
    private static long summaryProject(String title)throws Exception{
        List<Map<String,Object>> rows=Db.query("SELECT p.id FROM projects p JOIN m08_summary_heads h ON h.project_id=p.id WHERE p.title=? AND h.version=1",title);
        if(rows.size()!=1)throw new IllegalStateException("Expected one HTTP-created synthetic summary");return ((Number)rows.get(0).get("id")).longValue();
    }
    private static void history(String title,int revision,int accepted,boolean draft,boolean mismatch)throws Exception{
        long demand=Db.insert("INSERT INTO demands(title,unit,hours,status) VALUES(?,'SYNTHETIC',1,'进行中')",title);long p=project(mismatch?demand+100000:demand,title);
        Db.exec("INSERT INTO workflow_demands VALUES(?,1,?,?,'direct','001','P1','{}','{}','SYNTHETIC-HISTORY')",demand,revision,draft);
        Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'900','P4',?,'2025-01-01T00:00:00Z')",demand,p,accepted);
    }
    private static long project(Long demand,String title)throws Exception{return Db.insert("INSERT INTO projects(demand_id,title,unit,hours,amount,status) VALUES(?,?,'SYNTHETIC',1,0,'进行中')",demand,title);}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing isolated fixture setting: "+name);return value;}
}
