package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Private synthetic import/email preparation and in-memory sender for real Main HTTP acceptance. */
public final class IntegrationLoginVerificationHttpFixture {
    private static final AtomicLong clock=new AtomicLong(System.currentTimeMillis());
    private static final AtomicReference<String> senderMode=new AtomicReference<>("normal");
    private static volatile CountDownLatch senderRelease=new CountDownLatch(0);
    private static final List<Map<String,Object>> mailbox=new ArrayList<>();
    private static Path root;
    private static long worker;
    private static Map<String,Object> originalUser,originalHead,originalImport;
    private static List<Map<String,Object>> originalRevisions,originalRequests;
    private static final AtomicLong lockViolations=new AtomicLong();
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected port or inspect");
        root=Path.of(required("integration.login.verification.root")).toRealPath();Path data=Path.of(required("data.dir")).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-login-verification-http-")||!data.getParent().equals(root)||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")||!required("bootstrap.admin.password").matches("[a-f0-9]{48}"))throw new IllegalArgumentException("Isolated synthetic loopback fixture required");
        if(args[0].equals("inspect")){System.out.println(Json.write(snapshot()));Db.get().close();return;}
        if(!Boolean.getBoolean("integration.login.verification.resume")){
            try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty fixture database required");}
            Db.init();String requestedMode=System.getProperty("login.email.mode");System.clearProperty("login.email.mode");
            try{setup();}finally{if(requestedMode==null)System.clearProperty("login.email.mode");else System.setProperty("login.email.mode",requestedMode);}
        }else Db.init();
        worker=((Number)Db.one("SELECT id FROM users WHERE username='synthetic-login-worker'").get("id")).longValue();captureWorker();
        NotificationChannelsLoginVerification testLoginService=new NotificationChannelsLoginVerification(clock::get,
            "none".equals(System.getProperty("integration.login.verification.sender"))?null:IntegrationLoginVerificationHttpFixture::send);
        Files.writeString(root.resolve("mailbox.json"),"[]");
        Thread controls=new Thread(IntegrationLoginVerificationHttpFixture::controls,"synthetic-login-verification-controls");controls.setDaemon(true);controls.start();Main.start(args,testLoginService);
    }
    @SuppressWarnings("unchecked") private static void setup()throws Exception {
        if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null||Db.count("organization_account_import_people")!=0)throw new IllegalStateException("Unexpected fixture seed");
        String password=required("bootstrap.admin.password"),adminToken=Auth.login("admin",password);Auth.Session admin=Auth.get(adminToken);
        OrganizationAccountImport importer=new OrganizationAccountImport(OrganizationAccountImportSourceTest.fixture(root.resolve("sources")));
        Map<String,Object> preview=importer.preview(admin);List<Map<String,Object>> decisions=new ArrayList<>();
        for(var row:(List<Map<String,Object>>)preview.get("rows")){Map<String,Object> choice=new LinkedHashMap<>();choice.put("reference",row.get("reference"));choice.put("action","CREATE_PENDING");choice.put("accountId",null);choice.put("accountProof",null);choice.put("reviewed",true);decisions.add(choice);}
        Map<String,Object> receipt=importer.commit(admin,Map.of("expectedRevision",preview.get("revision"),"sourceFingerprint",preview.get("sourceFingerprint"),"reviewToken",preview.get("reviewToken"),"decisions",decisions));
        List<String> names=List.of("admin","worker","second","pending","missing","cleared","no-password");Map<String,Object> accounts=new LinkedHashMap<>();int index=0;String hashed=Auth.hash(password);
        for(var row:(List<Map<String,Object>>)receipt.get("rows")){
            long id=((Number)row.get("accountId")).longValue();String name=names.get(index++),username="synthetic-login-"+name;
            Db.exec("UPDATE users SET username=? WHERE id=?",username,id);
            if(!name.equals("missing")){
                Map<String,Object> command=new LinkedHashMap<>();command.put("user_id",id);command.put("expected_revision",0L);command.put("request_id",UUID.randomUUID().toString());command.put("email",name.equals("cleared")?null:name+"@example.test");
                NotificationChannelsAccountEmailPreparation.save(admin,command);
            }
            if(!name.equals("pending"))Db.exec("UPDATE users SET status=1,password=?,role=? WHERE id=?",name.equals("no-password")?null:hashed,name.equals("admin")?"admin":"viewer",id);
            accounts.put(username,id);
        }
        Auth.logout(adminToken);
        Db.exec("INSERT INTO teachers(name,status,email) VALUES('SYNTHETIC LOGIN UNRELATED TEACHER','待完善','unrelated@example.test')");
        Files.writeString(root.resolve("fixture.json"),Json.write(Map.of("accounts",accounts,"synthetic",true,"clock",clock.get(),"sender","private in-memory file mailbox only","mailbox","mailbox.json")));
    }
    private static void send(String email,String code)throws Exception {
        boolean held=Thread.holdsLock(Api.MUTATION_LOCK);if(held)lockViolations.incrementAndGet();String mode=senderMode.get();
        synchronized(mailbox){mailbox.add(Map.of("sequence",mailbox.size()+1,"email",email,"code",code,"at",Instant.ofEpochMilli(clock.get()).toString(),"held_lock",held,"sender_mode",mode));atomic("mailbox.json",Json.write(mailbox));}
        if(held)throw new IllegalStateException("Synthetic sender must run outside the business lock");
        if(mode.equals("fail"))throw new java.io.IOException("Synthetic delivery failure");
        if(mode.equals("hold")){
            atomic("sender-waiting.json",Json.write(Map.of("waiting",true)));
            if(!senderRelease.await(20,TimeUnit.SECONDS))throw new java.io.IOException("Synthetic sender release timeout");
            atomic("sender-waiting.json",Json.write(Map.of("waiting",false)));
        }
    }
    private static void captureWorker()throws Exception {
        originalUser=Db.one("SELECT * FROM users WHERE id=?",worker);originalHead=Db.one("SELECT * FROM s01_account_email_heads WHERE user_id=?",worker);originalImport=Db.one("SELECT * FROM organization_account_import_people WHERE account_id=?",worker);
        originalRevisions=Db.query("SELECT * FROM s01_account_email_revisions WHERE user_id=?",worker);originalRequests=Db.query("SELECT * FROM s01_account_email_requests WHERE user_id=?",worker);
    }
    private static void restoreWorker()throws Exception {
        Db.exec("UPDATE users SET username=?,password=?,name=?,role=?,status=? WHERE id=?",originalUser.get("username"),originalUser.get("password"),originalUser.get("name"),originalUser.get("role"),originalUser.get("status"),worker);
        Db.exec("UPDATE organization_account_import_people SET source_fingerprint=? WHERE account_id=?",originalImport.get("source_fingerprint"),worker);
        Db.exec("DELETE FROM s01_account_email_requests WHERE user_id=?",worker);Db.exec("DELETE FROM s01_account_email_revisions WHERE user_id=?",worker);
        Db.exec("UPDATE s01_account_email_heads SET import_person_code=?,import_binding=?,revision=?,email=?,email_key=? WHERE user_id=?",originalHead.get("import_person_code"),originalHead.get("import_binding"),originalHead.get("revision"),originalHead.get("email"),originalHead.get("email_key"),worker);
        for(var row:originalRevisions)insert("s01_account_email_revisions",row);for(var row:originalRequests)insert("s01_account_email_requests",row);
    }
    private static void insert(String table,Map<String,Object> row)throws Exception {
        List<String> keys=new ArrayList<>(row.keySet());for(String key:keys)if(!key.matches("[a-z_]+"))throw new IllegalStateException("Unexpected synthetic column");
        Db.exec("INSERT INTO "+table+"("+String.join(",",keys)+") VALUES("+String.join(",",Collections.nCopies(keys.size(),"?"))+")",keys.stream().map(row::get).toArray());
    }
    private static void changeEmail(boolean clear)throws Exception {
        Auth.Credential credential=Auth.checkedCredential("synthetic-login-admin",required("bootstrap.admin.password"));if(credential==null)throw new IllegalStateException("Synthetic administrator unavailable");
        String token=Auth.issueVerified(credential,NotificationChannelsAccountEmailPreparation.loginSnapshot(credential.uid()));
        try{
            Db.exec("UPDATE users SET status=0,password=NULL,role='viewer' WHERE id=?",worker);
            Map<String,Object> command=new LinkedHashMap<>();command.put("user_id",worker);command.put("expected_revision",Db.one("SELECT revision FROM s01_account_email_heads WHERE user_id=?",worker).get("revision"));command.put("request_id",UUID.randomUUID().toString());command.put("email",clear?null:"changed-worker@example.test");
            NotificationChannelsAccountEmailPreparation.save(Auth.get(token),command);
        }finally{Db.exec("UPDATE users SET status=?,password=?,role=? WHERE id=?",originalUser.get("status"),originalUser.get("password"),originalUser.get("role"),worker);Auth.logout(token);}
    }
    private static Object operation(Map<String,Object> command)throws Exception {
        String operation=Objects.toString(command.get("operation"),"");
        switch(operation){
            case "snapshot":return snapshot();
            case "stats":var f=Auth.class.getDeclaredField("SESSIONS");f.setAccessible(true);int sessions=((Map<?,?>)f.get(null)).size();synchronized(mailbox){return Map.of("sessions",sessions,"mail_count",mailbox.size(),"clock",clock.get(),"sender_lock_violations",lockViolations.get());}
            case "advance":long amount=((Number)command.get("millis")).longValue();if(amount<0||amount>24L*60*60*1000)throw new IllegalArgumentException("Bounded synthetic time only");clock.addAndGet(amount);break;
            case "mode-legacy":System.clearProperty("login.email.mode");break;
            case "mode-required":System.setProperty("login.email.mode","required");break;
            case "mode-unknown":System.setProperty("login.email.mode","synthetic-unknown");break;
            case "sender-normal":senderMode.set("normal");break;
            case "sender-fail":senderMode.set("fail");break;
            case "sender-hold":senderRelease=new CountDownLatch(1);senderMode.set("hold");break;
            case "sender-release":senderMode.set("normal");senderRelease.countDown();break;
            case "worker-disable":Db.exec("UPDATE users SET status=0 WHERE id=?",worker);break;
            case "worker-password":Db.exec("UPDATE users SET password=? WHERE id=?",Auth.hash(required("bootstrap.admin.password")+"-changed"),worker);break;
            case "worker-role":Db.exec("UPDATE users SET role='manager' WHERE id=?",worker);break;
            case "worker-email":changeEmail(false);break;
            case "worker-clear-email":changeEmail(true);break;
            case "worker-source":Db.exec("UPDATE organization_account_import_people SET source_fingerprint=? WHERE account_id=?","f".repeat(64),worker);break;
            case "worker-restore":restoreWorker();break;
            case "revoke-worker":Auth.revokeUserSessions(worker);break;
            default:throw new IllegalArgumentException("Unknown fixed synthetic control");
        }return Map.of();
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static Map<String,Object> snapshot()throws Exception {
        Map<String,Object> result=new TreeMap<>();for(var table:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
            String name=table.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected table");List<String> rows=new ArrayList<>();for(var row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);result.put(name,Map.of("count",rows.size(),"sha256",hash(Json.write(rows).getBytes(StandardCharsets.UTF_8))));
        }return result;
    }
    @SuppressWarnings("unchecked") private static void controls(){String last="";while(!Thread.currentThread().isInterrupted())try{
        Path file=root.resolve("control-request.json");if(Files.exists(file)){var command=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(command.get("nonce"),"");if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")){
            Map<String,Object> result;synchronized(Api.MUTATION_LOCK){result=Map.of("nonce",nonce,"ok",true,"data",operation(command));}atomic("control-result.json",Json.write(result));last=nonce;
        }}Thread.sleep(20);
    }catch(InterruptedException done){Thread.currentThread().interrupt();return;}catch(Exception e){throw new RuntimeException("Synthetic login control failed",e);}}
    private static void atomic(String name,String value)throws Exception {Path tmp=root.resolve(name+".next");Files.writeString(tmp,value);Files.move(tmp,root.resolve(name),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private static String required(String key){String value=System.getProperty(key,"");if(value.isBlank())throw new IllegalArgumentException("Missing synthetic setting: "+key);return value;}
}
