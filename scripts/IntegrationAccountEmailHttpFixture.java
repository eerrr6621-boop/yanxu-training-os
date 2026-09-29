package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Test-only synthetic import metadata and file-controlled fault injection; no production HTTP route. */
public final class IntegrationAccountEmailHttpFixture {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected isolated port or inspect");
        Path root=Path.of(required("integration.account.email.root")).toRealPath();
        Path data=Path.of(required("data.dir")).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-account-email-http-")||!data.getParent().equals(root)
                ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")
                ||!required("bootstrap.admin.password").matches("[a-f0-9]{48}"))
            throw new IllegalArgumentException("Explicit isolated synthetic fixture required");
        if(args[0].equals("inspect")) {
            Map<String,Object> result=new TreeMap<>();
            for(var row:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
                String name=row.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalArgumentException("Unexpected identifier");
                List<String> values=new ArrayList<>();for(var record:Db.query("SELECT * FROM "+name))values.add(Json.write(new TreeMap<>(record)));
                Collections.sort(values);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(values).getBytes(StandardCharsets.UTF_8)));
                result.put(name,Map.of("count",values.size(),"sha256",hash));
            }
            System.out.println(Json.write(result));Db.get().close();return;
        }
        boolean resume=Boolean.getBoolean("integration.account.email.resume");
        if(!resume) {
            try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
            Db.init();
            if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null||Db.count("organization_account_import_people")!=0)
                throw new IllegalStateException("Unexpected startup seed");
            String hash=Auth.hash(required("bootstrap.admin.password"));
            user(2,"admin2","admin",1,hash);user(3,"manager","manager",1,hash);user(4,"viewer","viewer",1,hash);
            user(5,"pending-a","viewer",0,null);user(6,"pending-b","viewer",0,null);
            user(7,"not-imported","viewer",0,null);user(8,"active-imported","viewer",1,null);
            user(9,"password-imported","viewer",0,hash);user(10,"role-imported","manager",0,null);
            String fingerprint="a".repeat(64),namespace="b".repeat(64),batch="SYNTHETIC-EMAIL-PREPARATION";
            for(long id:List.of(5L,6L,8L,9L,10L)) {
                Map<String,Object> payload=new LinkedHashMap<>();payload.put("personCode","SYNTHETIC-EMAIL-P"+id);payload.put("accountId",id);payload.put("name","SYNTHETIC EMAIL "+id);payload.put("action","CREATE_PENDING");payload.put("preparation",Map.of("synthetic",true));
                Db.exec("INSERT INTO organization_account_import_people(person_code,source_namespace,source_reference,account_id,batch_key,source_fingerprint,payload,disposition,created_by) VALUES(?,?,?,?,?,?,?,?,1)",
                        "SYNTHETIC-EMAIL-P"+id,namespace,"SYNTHETIC-EMAIL-REF-"+id,id,batch,fingerprint,Json.write(payload),"CREATE_PENDING");
            }
            Db.exec("INSERT INTO organization_account_import_batches(batch_key,source_fingerprint,decision_digest,receipt,created_by) VALUES(?,?,?,?,1)",batch,fingerprint,"c".repeat(64),Json.write(Map.of("synthetic",true,"imported",true,"revision",1,"accountsActivated",false,"permissionsPublished",false)));
            Db.exec("UPDATE organization_account_import_state SET revision=1 WHERE singleton=1");
            // Unrelated rows make accidental broad writes visible without using a real roster or outbox destination.
            Db.exec("INSERT INTO teachers(name,status,phone,email) VALUES('SYNTHETIC UNRELATED TEACHER','在库','00000000000','unrelated@example.test')");
            Files.writeString(root.resolve("fixture.json"),Json.write(Map.of("accounts",Map.of("admin",1,"synthetic-email-admin2",2,"synthetic-email-manager",3,"synthetic-email-viewer",4),"targets",Map.of("pending_a",5,"pending_b",6,"nonimported",7,"active_imported",8,"password_imported",9,"role_imported",10),"synthetic",true)));
        } else Db.init();
        // Dedicated script fixture only. It accepts a fixed fault allowlist from this private temp root.
        // This exercises live Auth and target revalidation while Main is reading a deliberately slow body.
        Thread control=new Thread(()->controls(root),"synthetic-account-email-controls");control.setDaemon(true);control.start();
        Main.main(args);
    }
    private static void user(long expected,String name,String role,int status,String hash)throws Exception {
        long id=Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,?)","synthetic-email-"+name,hash,"SYNTHETIC EMAIL "+name,role,status);
        if(id!=expected)throw new IllegalStateException("Unexpected synthetic account sequence");
    }
    @SuppressWarnings("unchecked") private static void controls(Path root) {
        String last="";
        while(!Thread.currentThread().isInterrupted()) {
            try {
                Path file=root.resolve("control-request.json");
                if(Files.exists(file)) {
                    Map<String,Object> command=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(command.get("nonce"),"");
                    if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")) {
                        String operation=Objects.toString(command.get("operation"),"");
                        synchronized(Api.MUTATION_LOCK) {
                            switch(operation) {
                                case "revoke-admin" -> Auth.revokeUserSessions(1);
                                case "downgrade-admin" -> Db.exec("UPDATE users SET role='manager' WHERE id=1");
                                case "restore-admin" -> Db.exec("UPDATE users SET role='admin',status=1 WHERE id=1");
                                case "disable-admin" -> Db.exec("UPDATE users SET status=0 WHERE id=1");
                                case "activate-target" -> Db.exec("UPDATE users SET status=1 WHERE id=5");
                                case "password-target" -> Db.exec("UPDATE users SET password=? WHERE id=5",Auth.hash(required("bootstrap.admin.password")));
                                case "role-target" -> Db.exec("UPDATE users SET role='manager' WHERE id=5");
                                case "restore-target" -> Db.exec("UPDATE users SET status=0,role='viewer',password=NULL WHERE id=5");
                                default -> throw new IllegalArgumentException("Unknown synthetic operation");
                            }
                        }
                        last=nonce;Path temporary=root.resolve("control-result.next");Files.writeString(temporary,Json.write(Map.of("nonce",nonce,"operation",operation,"ok",true)));
                        Files.move(temporary,root.resolve("control-result.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                    }
                }
                Thread.sleep(20);
            } catch(InterruptedException stop) {Thread.currentThread().interrupt();return;}
              catch(Exception failure) {throw new RuntimeException("Synthetic control failed",failure);}
        }
    }
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing fixture setting: "+name);return value;}
}
