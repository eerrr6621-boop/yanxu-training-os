package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Test-only Main wrapper, whole-table digests and fixed private fault controls for HTTP-created data. */
public final class IntegrationSummaryComparisonHttpFixture {
    private static Map<String,Object> saved;
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected isolated port or inspect");
        Path root=Path.of(required("integration.summary.comparison.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-summary-comparison-http-")||!data.getParent().equals(root)
                ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")
                ||!required("bootstrap.admin.password").matches("[a-f0-9]{48}"))throw new IllegalArgumentException("Explicit isolated synthetic loopback fixture required");
        if(args[0].equals("inspect")){System.out.println(Json.write(snapshot()));Db.get().close();return;}
        if(!Boolean.getBoolean("integration.summary.comparison.resume")){
            try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
            Db.init();if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null||Db.count("m08_summary_heads")!=0)throw new IllegalStateException("Unexpected fixture seed");
        }else Db.init();
        Thread worker=new Thread(()->controls(root),"synthetic-summary-comparison-controls");worker.setDaemon(true);worker.start();Main.main(args);
    }
    private static Map<String,Object> snapshot()throws Exception {
        Map<String,Object> result=new TreeMap<>();for(var table:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
            String name=table.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected table");List<String> rows=new ArrayList<>();for(var row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);
            result.put(name,Map.of("count",rows.size(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(rows).getBytes(StandardCharsets.UTF_8)))));
        }return result;
    }
    private static long primary()throws Exception {var p=Db.one("SELECT id FROM projects WHERE title='SYNTHETIC SUMMARY COMPARISON PRIMARY'");if(p==null)throw new IllegalStateException("HTTP setup missing");return ((Number)p.get("id")).longValue();}
    @SuppressWarnings("unchecked") private static Object operation(String op)throws Exception {
        if(op.equals("snapshot"))return snapshot();
        if(op.equals("disable-editor")){Db.exec("UPDATE users SET status=0 WHERE username='synthetic-summary-comparison-editor'");return Map.of();}
        if(op.equals("restore-editor")){Db.exec("UPDATE users SET status=1 WHERE username='synthetic-summary-comparison-editor'");return Map.of();}
        if(op.equals("revoke-editor")){var u=Db.one("SELECT id FROM users WHERE username='synthetic-summary-comparison-editor'");Auth.revokeUserSessions(((Number)u.get("id")).longValue());return Map.of();}
        long id=primary();switch(op){
            case "archive":Db.exec("UPDATE projects SET status='已归档' WHERE id=?",id);break;
            case "unarchive":Db.exec("UPDATE projects SET status='进行中' WHERE id=?",id);break;
            case "capture":saved=new LinkedHashMap<>();saved.put("head",Db.one("SELECT * FROM m08_summary_heads WHERE project_id=?",id));saved.put("revisions",Db.query("SELECT * FROM m08_summary_revisions WHERE project_id=? ORDER BY revision",id));saved.put("requests",Db.query("SELECT * FROM m08_summary_requests WHERE project_id=?",id));break;
            case "restore":
                if(saved==null)throw new IllegalStateException("Capture before restore");
                var head=(Map<String,Object>)saved.get("head");Db.exec("DELETE FROM m08_summary_requests WHERE project_id=?",id);Db.exec("DELETE FROM m08_summary_revisions WHERE project_id=?",id);
                Db.exec("UPDATE m08_summary_heads SET demand_id=?,organization_code=?,version=?,head_hash=? WHERE project_id=?",head.get("demand_id"),head.get("organization_code"),head.get("version"),head.get("head_hash"),id);
                for(var r:(List<Map<String,Object>>)saved.get("revisions"))Db.exec("INSERT INTO m08_summary_revisions(project_id,revision,operation,actor_code,account_id,config_version,saved_at,content_json,sources_json,previous_hash,revision_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?)",r.get("project_id"),r.get("revision"),r.get("operation"),r.get("actor_code"),r.get("account_id"),r.get("config_version"),r.get("saved_at"),r.get("content_json"),r.get("sources_json"),r.get("previous_hash"),r.get("revision_hash"));
                for(var r:(List<Map<String,Object>>)saved.get("requests"))Db.exec("INSERT INTO m08_summary_requests(account_id,request_id,actor_code,project_id,operation,payload_hash,result_revision) VALUES(?,?,?,?,?,?,?)",r.get("account_id"),r.get("request_id"),r.get("actor_code"),r.get("project_id"),r.get("operation"),r.get("payload_hash"),r.get("result_revision"));break;
            case "fault-content":Db.exec("UPDATE m08_summary_revisions SET content_json=? WHERE project_id=? AND revision=2","{\"achievements\":\"SYNTHETIC ALTERED\",\"issues\":\"\",\"nextSteps\":\"\"}",id);break;
            case "fault-unselected":Db.exec("UPDATE m08_summary_revisions SET actor_code='P999' WHERE project_id=? AND revision=1",id);break;
            case "fault-source":Db.exec("UPDATE m08_summary_revisions SET sources_json='{}' WHERE project_id=? AND revision=2",id);break;
            case "fault-middle-hash":Db.exec("UPDATE m08_summary_revisions SET revision_hash=? WHERE project_id=? AND revision=2","f".repeat(64),id);break;
            case "fault-head":Db.exec("UPDATE m08_summary_heads SET head_hash=? WHERE project_id=?","0".repeat(64),id);break;
            case "fault-association":Db.exec("UPDATE m08_summary_heads SET organization_code='999' WHERE project_id=?",id);break;
            case "fault-missing":Db.exec("DELETE FROM m08_summary_requests WHERE project_id=? AND result_revision=2",id);Db.exec("DELETE FROM m08_summary_revisions WHERE project_id=? AND revision=2",id);break;
            default:throw new IllegalArgumentException("Unknown fixture operation");
        }return Map.of();
    }
    @SuppressWarnings("unchecked") private static void controls(Path root){String last="";while(!Thread.currentThread().isInterrupted())try{
        Path file=root.resolve("control-request.json");if(Files.exists(file)){var request=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(request.get("nonce"),"");if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")){
            Map<String,Object> result;synchronized(Api.MUTATION_LOCK){result=Map.of("nonce",nonce,"ok",true,"data",operation(Objects.toString(request.get("operation"),"")));}
            Path tmp=root.resolve("control-result.next");Files.writeString(tmp,Json.write(result));Files.move(tmp,root.resolve("control-result.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);last=nonce;
        }}Thread.sleep(20);
    }catch(InterruptedException done){Thread.currentThread().interrupt();return;}catch(Exception error){throw new RuntimeException("Synthetic summary comparison control failed",error);}}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing isolated fixture property: "+name);return value;}
}
