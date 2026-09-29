package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Synthetic pinned source preparation plus a real Main wrapper with private fixed fault controls. */
public final class IntegrationBranchCoverageHttpFixture {
    private static Map<String,Object> saved;
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected prepare, inspect or isolated port");
        Path root=Path.of(required("integration.branch.coverage.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-branch-coverage-http-")||!data.getParent().equals(root)
                ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")
                ||!required("bootstrap.admin.password").matches("[a-f0-9]{48}"))throw new IllegalArgumentException("Isolated synthetic loopback fixture required");
        if(args[0].equals("prepare")){
            if(Files.exists(root.resolve("sources")))throw new IllegalArgumentException("Prepare a fresh source directory once");
            Config c=OrganizationAccountImportSourceTest.fixture(root.resolve("sources"));OrganizationAccountImportSource.load(c);
            Map<String,Object> pins=new LinkedHashMap<>();pins.put("candidates",pin(c.candidates()));pins.put("candidatePolicy",pin(c.candidatePolicy()));pins.put("roleSource",pin(c.roleSource()));pins.put("authorization",pin(c.authorization()));pins.put("audit",pin(c.audit()));pins.put("regionReference",pin(c.regionReference()));pins.put("scopeDecision",pin(c.scopeDecision()));pins.put("preparedPreview",pin(c.preparedPreview()));
            Path manifest=root.resolve("synthetic-manifest.json");Files.writeString(manifest,Json.write(Map.of("schema","M01-SERVER-PIN-MANIFEST-v1","batchKey",c.batchKey(),"usage","SYNTHETIC ONLY branch coverage real HTTP acceptance","pins",pins,"originals",c.originals().stream().map(IntegrationBranchCoverageHttpFixture::pin).toList())));
            var info=Map.of("manifest",manifest.toString(),"sha256",hash(Files.readAllBytes(manifest)),"candidates",7,"branches",3,"historicalExcluded",2);Files.writeString(root.resolve("fixture.json"),Json.write(info));System.out.println(Json.write(info));return;
        }
        if(args[0].equals("inspect")){System.out.println(Json.write(snapshot()));Db.get().close();return;}
        Path manifest=Path.of(required(OrganizationAccountImportHost.MANIFEST_PROPERTY)).toRealPath();
        if(!manifest.equals(root.resolve("synthetic-manifest.json"))||!hash(Files.readAllBytes(manifest)).equals(required(OrganizationAccountImportHost.SHA256_PROPERTY)))throw new IllegalArgumentException("Use this fixture's immutable generated pins");
        if(!Boolean.getBoolean("integration.branch.coverage.resume")){
            try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
            Db.init();if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null||Db.count("organization_account_import_people")!=0)throw new IllegalStateException("Unexpected fixture seed");
        }else Db.init();
        Thread worker=new Thread(()->controls(root),"synthetic-branch-coverage-controls");worker.setDaemon(true);worker.start();Main.main(args);
    }
    private static Map<String,Object> pin(FilePin p){return Map.of("path",p.path().toString(),"sha256",p.sha256());}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static Map<String,Object> snapshot()throws Exception {
        Map<String,Object> result=new TreeMap<>();for(var table:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
            String name=table.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected table");List<String> rows=new ArrayList<>();for(var row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);result.put(name,Map.of("count",rows.size(),"sha256",hash(Json.write(rows).getBytes(StandardCharsets.UTF_8))));
        }return result;
    }
    @SuppressWarnings("unchecked") private static Object operation(String op)throws Exception {
        if(op.equals("snapshot"))return snapshot();
        if(op.equals("device-epochs"))return Db.query("SELECT user_id,epoch FROM s01_trusted_device_epochs ORDER BY user_id");
        if(op.equals("revoke-admin")){Auth.revokeUserSessions(1);return Map.of();}
        switch(op){
            case "capture":saved=new LinkedHashMap<>();saved.put("people",Db.query("SELECT * FROM organization_account_import_people ORDER BY person_code"));saved.put("batch",Db.one("SELECT * FROM organization_account_import_batches WHERE batch_key='synthetic-import'"));break;
            case "restore":
                if(saved==null)throw new IllegalStateException("Capture before restore");
                Db.exec("DELETE FROM organization_account_import_people");
                for(var r:(List<Map<String,Object>>)saved.get("people"))Db.exec("INSERT INTO organization_account_import_people(person_code,source_namespace,source_reference,account_id,batch_key,source_fingerprint,payload,disposition,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",r.get("person_code"),r.get("source_namespace"),r.get("source_reference"),r.get("account_id"),r.get("batch_key"),r.get("source_fingerprint"),r.get("payload"),r.get("disposition"),r.get("created_by"),r.get("created_at"));
                var b=(Map<String,Object>)saved.get("batch");Db.exec("UPDATE organization_account_import_batches SET receipt=?,source_fingerprint=?,decision_digest=? WHERE batch_key='synthetic-import'",b.get("receipt"),b.get("source_fingerprint"),b.get("decision_digest"));break;
            case "fault-missing-person":Db.exec("DELETE FROM organization_account_import_people WHERE source_reference='teacher-row-4'");break;
            case "fault-person-account":
                var spare=Db.one("SELECT id FROM users WHERE username='synthetic-branch-spare'");if(spare==null)throw new IllegalStateException("Synthetic spare account missing");Db.exec("UPDATE organization_account_import_people SET account_id=? WHERE source_reference='teacher-row-4'",spare.get("id"));break;
            case "fault-receipt-account":case "fault-receipt-duplicate":
                var batch=Db.one("SELECT receipt FROM organization_account_import_batches WHERE batch_key='synthetic-import'");var receipt=(Map<String,Object>)Json.parse(batch.get("receipt").toString());var rows=(List<Object>)receipt.get("rows");
                if(op.equals("fault-receipt-account"))((Map<String,Object>)rows.get(0)).put("accountId",900000L);else rows.add(new LinkedHashMap<>((Map<String,Object>)rows.get(0)));
                Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key='synthetic-import'",Json.write(receipt));break;
            default:throw new IllegalArgumentException("Unknown fixed synthetic operation");
        }return Map.of();
    }
    @SuppressWarnings("unchecked") private static void controls(Path root){String last="";while(!Thread.currentThread().isInterrupted())try{
        Path file=root.resolve("control-request.json");if(Files.exists(file)){var request=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(request.get("nonce"),"");if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")){
            Map<String,Object> result;synchronized(Api.MUTATION_LOCK){result=Map.of("nonce",nonce,"ok",true,"data",operation(Objects.toString(request.get("operation"),"")));}Path tmp=root.resolve("control-result.next");Files.writeString(tmp,Json.write(result));Files.move(tmp,root.resolve("control-result.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);last=nonce;
        }}Thread.sleep(20);
    }catch(InterruptedException done){Thread.currentThread().interrupt();return;}catch(Exception error){throw new RuntimeException("Synthetic branch coverage control failed",error);}}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing fixture property: "+name);return value;}
}
