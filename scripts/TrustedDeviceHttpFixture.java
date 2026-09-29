package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.sql.*;
import org.h2.api.Trigger;

/** Scripts-only synthetic accounts, private file controls and memory delivery for actual Main HTTP. */
public final class TrustedDeviceHttpFixture {
    private static final AtomicLong clock=new AtomicLong(System.currentTimeMillis());
    private static final AtomicReference<String> senderMode=new AtomicReference<>("normal");
    private static volatile CountDownLatch senderRelease=new CountDownLatch(0);
    private static final List<Map<String,Object>> mailbox=new ArrayList<>();
    private static final AtomicLong lockViolations=new AtomicLong();
    private static Path root;
    private static final AtomicLong deviceClock=new AtomicLong(System.currentTimeMillis());
    private static volatile boolean failEpochWrites;
    private static volatile long epochFailures;
    private static String configuredOrigin,configuredMaxAge;

    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected isolated loopback port or inspect");
        root=Path.of(required("integration.trusted.device.root")).toRealPath();
        Path data=Path.of(required("data.dir")).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-trusted-device-http-")
                ||!root.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
                ||!data.equals(root.resolve("data"))||!"127.0.0.1".equals(System.getProperty("bind.address"))
                ||!"false".equals(System.getProperty("bootstrap.demo"))
                ||!required("bootstrap.admin.password").matches("[a-f0-9]{48}")
                ||!"disabled".equals(System.getenv("YANXU_LOGIN_MAIL_TRANSPORT")))
            throw new IllegalArgumentException("Explicit isolated synthetic setup and disabled real transport required");
        for(String key:System.getenv().keySet())if(key.startsWith("YANXU_LOGIN_SMTP_"))
            throw new IllegalArgumentException("Remove SMTP configuration from synthetic process");
        for(String key:List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS"))
            if(!Objects.toString(System.getenv(key),"").isBlank())throw new IllegalArgumentException("Remove inherited JVM options");
        if(args[0].equals("inspect")) {
            if(!Files.isRegularFile(data.resolve("training.mv.db")))throw new IllegalArgumentException("Existing isolated database required");
            System.out.println(Json.write(snapshot()));Db.get().close();return;
        }
        int port=Integer.parseInt(args[0]);if(port<1024||port>65535)throw new IllegalArgumentException("Explicit unprivileged port required");
        configuredOrigin=required("login.device.origin");configuredMaxAge=required("login.device.maxAgeSeconds");
        if(!configuredOrigin.equals("http://127.0.0.1:"+port)||!Set.of("60","3600").contains(configuredMaxAge))
            throw new IllegalArgumentException("Explicit synthetic device policy required");
        if(!"required".equals(System.getProperty("login.email.mode")))throw new IllegalArgumentException("Required email mode expected on fixture startup");
        if(!Boolean.getBoolean("integration.trusted.device.resume")) {
            try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty fixture data required");}
            Db.init();String mode=System.getProperty("login.email.mode");System.setProperty("login.email.mode","legacy");
            try{setup();}finally{if(mode==null)System.clearProperty("login.email.mode");else System.setProperty("login.email.mode",mode);}
        } else {
            if(!Files.isRegularFile(root.resolve("fixture.json")))throw new IllegalArgumentException("Resume requires owned fixture metadata");
            Db.init();
        }
        atomic("mailbox.json","[]");
        Path savedClock=root.resolve("device-clock.txt");
        if(Files.isRegularFile(savedClock))deviceClock.set(Long.parseLong(Files.readString(savedClock)));
        else Files.writeString(savedClock,Long.toString(deviceClock.get()));
        installDeviceService();
        Db.exec("CREATE TRIGGER IF NOT EXISTS synthetic_device_epoch_failure BEFORE INSERT, UPDATE ON s01_trusted_device_epochs FOR EACH ROW CALL \"com.training.TrustedDeviceHttpFixture$EpochFailure\"");
        NotificationChannelsLoginVerification service=new NotificationChannelsLoginVerification(clock::get,TrustedDeviceHttpFixture::send);
        Thread controls=new Thread(TrustedDeviceHttpFixture::controls,"synthetic-trusted-device-controls");
        controls.setDaemon(true);controls.start();Main.start(args,service);
    }
    private static void setup()throws Exception {
        if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null||Db.count("organization_account_import_people")!=0)
            throw new IllegalStateException("Unexpected non-synthetic seed");
        String password=required("bootstrap.admin.password"),hash=Auth.hash(password);
        user(2,"admin2","admin",1,hash);user(3,"manager","manager",1,hash);user(4,"viewer","viewer",1,hash);
        user(5,"native","viewer",1,hash);user(6,"imported","viewer",0,null);user(7,"pending","viewer",0,null);
        user(8,"native_empty","viewer",1,hash);user(9,"imported_empty","viewer",1,hash);
        String namespace="b".repeat(64),fingerprint="a".repeat(64),batch="SYNTHETIC-EMAIL-MAINTENANCE";
        for(long id:List.of(6L,7L,9L)) {
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("personCode","SYNTHETIC-DEVICE-P"+id);payload.put("accountId",id);
            payload.put("name","SYNTHETIC DEVICE "+id);payload.put("action","CREATE_PENDING");payload.put("preparation",Map.of("synthetic",true));
            Db.exec("INSERT INTO organization_account_import_people(person_code,source_namespace,source_reference,account_id,batch_key,source_fingerprint,payload,disposition,created_by) VALUES(?,?,?,?,?,?,?,?,1)",
                "SYNTHETIC-DEVICE-P"+id,namespace,"SYNTHETIC-DEVICE-REF-"+id,id,batch,fingerprint,Json.write(payload),"CREATE_PENDING");
        }
        Db.exec("INSERT INTO organization_account_import_batches(batch_key,source_fingerprint,decision_digest,receipt,created_by) VALUES(?,?,?,?,1)",
            batch,fingerprint,"c".repeat(64),Json.write(Map.of("synthetic",true,"imported",true,"revision",1,"accountsActivated",false,"permissionsPublished",false)));
        Db.exec("UPDATE organization_account_import_state SET revision=1 WHERE singleton=1");
        String token=Auth.login("admin",password);Auth.Session admin=Auth.get(token);
        Map<String,Object> imported=new LinkedHashMap<>();imported.put("user_id",6L);imported.put("expected_revision",0L);
        imported.put("request_id",UUID.randomUUID().toString());imported.put("email","imported@example.invalid");
        NotificationChannelsAccountEmailPreparation.save(admin,imported);
        Db.exec("UPDATE users SET status=1,password=? WHERE id=6",hash);
        for(long id:List.of(2L,5L,1L)) {
            String key=id==1?"admin":id==2?"admin2":"native";
            NotificationChannelsAccountEmailMaintenance.save(admin,Map.of("user_id",id,"expected_revision",0L,"request_id",UUID.randomUUID().toString(),
                "email",key+"@example.invalid","confirmed_username",id==1?"admin":"synthetic-device-"+key,"purpose","LOGIN_VERIFICATION"));
        }
        Auth.logout(token);
        Db.exec("INSERT INTO teachers(name,status,email) VALUES('SYNTHETIC DEVICE UNRELATED TEACHER','待完善','unrelated@example.invalid')");
        Map<String,Object> accounts=new LinkedHashMap<>(),usernames=new LinkedHashMap<>();
        List<String> names=List.of("admin","admin2","manager","viewer","native","imported","pending","native_empty","imported_empty");
        for(int i=0;i<names.size();i++){String name=names.get(i);accounts.put(name,i+1);usernames.put(name,i==0?"admin":"synthetic-device-"+name);}
        atomic("fixture.json",Json.write(Map.of("synthetic",true,"accounts",accounts,"usernames",usernames,"initial_emails",Map.of(
            "admin","admin@example.invalid","admin2","admin2@example.invalid","native","native@example.invalid","imported","imported@example.invalid"),
            "mailbox","mailbox.json","sender","private-memory-only","real_transport","disabled")));
    }
    private static void user(long expected,String name,String role,int status,String hash)throws Exception {
        long id=Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,?)","synthetic-device-"+name,hash,
            "SYNTHETIC DEVICE "+name,role,status);
        if(id!=expected)throw new IllegalStateException("Unexpected synthetic account sequence");
    }
    private static void send(String email,String code)throws Exception {
        if(!email.endsWith("@example.invalid"))throw new IllegalArgumentException("Synthetic invalid-domain recipient required");
        boolean held=Thread.holdsLock(Api.MUTATION_LOCK);if(held)lockViolations.incrementAndGet();String mode=senderMode.get();
        synchronized(mailbox){mailbox.add(Map.of("sequence",mailbox.size()+1,"email",email,"code",code,"at",Instant.ofEpochMilli(clock.get()).toString(),
            "held_lock",held,"sender_mode",mode));atomic("mailbox.json",Json.write(mailbox));}
        if(held)throw new IllegalStateException("Synthetic sender must run outside business lock");
        if(mode.equals("fail"))throw new java.io.IOException("Synthetic sender unavailable");
        if(mode.equals("hold")) {
            atomic("sender-waiting.json",Json.write(Map.of("waiting",true)));
            if(!senderRelease.await(20,TimeUnit.SECONDS))throw new java.io.IOException("Synthetic sender release timeout");
            atomic("sender-waiting.json",Json.write(Map.of("waiting",false)));
        }
    }
    private static Object operation(Map<String,Object> command)throws Exception {
        switch(Objects.toString(command.get("operation"),"")) {
            case "snapshot":return snapshot();
            case "device-storage":return Map.of("epochs",Db.query("SELECT * FROM s01_trusted_device_epochs"),"devices",Db.query("SELECT * FROM s01_trusted_devices"));
            case "expire-short-sessions":for(Object item:sessions().values())((Auth.Session)item).time=0;break;
            case "clear-short-sessions":sessions().clear();break;
            case "epochs-fail":failEpochWrites=true;break;
            case "epochs-normal":failEpochWrites=false;break;
            case "device-advance":long increment=((Number)command.get("millis")).longValue();if(Math.abs(increment)>86_400_000L)throw new IllegalArgumentException("Bounded synthetic device clock only");deviceClock.addAndGet(increment);Files.writeString(root.resolve("device-clock.txt"),Long.toString(deviceClock.get()));break;
            case "device-disable":System.clearProperty("login.device.maxAgeSeconds");break;
            case "device-invalid":System.setProperty("login.device.maxAgeSeconds","1.5");break;
            case "device-missing-origin":System.clearProperty("login.device.origin");break;
            case "device-production-origin":System.setProperty("login.device.origin","https://device.example.invalid");break;
            case "device-restore-config":System.setProperty("login.device.origin",configuredOrigin);System.setProperty("login.device.maxAgeSeconds",configuredMaxAge);break;
            case "stats":var field=Auth.class.getDeclaredField("SESSIONS");field.setAccessible(true);
                synchronized(mailbox){return Map.of("clock",clock.get(),"sessions",((Map<?,?>)field.get(null)).size(),"mail_count",mailbox.size(),"sender_lock_violations",lockViolations.get(),"device_clock",deviceClock.get(),"epoch_failures",epochFailures);}
            case "advance":long amount=((Number)command.get("millis")).longValue();if(amount<0||amount>86_400_000L)throw new IllegalArgumentException("Bounded synthetic clock only");clock.addAndGet(amount);break;
            case "mode-legacy":System.setProperty("login.email.mode","legacy");break;
            case "mode-required":System.setProperty("login.email.mode","required");break;
            case "sender-normal":senderMode.set("normal");break;
            case "sender-fail":senderMode.set("fail");break;
            case "sender-hold":senderRelease=new CountDownLatch(1);senderMode.set("hold");break;
            case "sender-release":senderMode.set("normal");senderRelease.countDown();break;
            case "revoke-admin":Auth.revokeUserSessions(1);break;
            case "downgrade-admin":Db.exec("UPDATE users SET role='manager' WHERE id=1");break;
            case "restore-admin":Db.exec("UPDATE users SET role='admin',status=1 WHERE id=1");break;
            case "rename-native":Db.exec("UPDATE users SET username='synthetic-device-native-renamed' WHERE id=5");break;
            case "restore-native-name":Db.exec("UPDATE users SET username='synthetic-device-native' WHERE id=5");break;
            default:throw new IllegalArgumentException("Unknown fixed synthetic operation");
        }
        return Map.of();
    }
    private static Map<String,Object> snapshot()throws Exception {
        Map<String,Object> result=new TreeMap<>();
        for(var table:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            String name=table.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected table");
            List<String> rows=new ArrayList<>();for(var row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(rows).getBytes(StandardCharsets.UTF_8)));
            result.put(name,Map.of("count",rows.size(),"sha256",hash));
        }
        return result;
    }
    @SuppressWarnings("unchecked") private static void controls() {
        String last="";
        while(!Thread.currentThread().isInterrupted())try {
            Path file=root.resolve("control-request.json");
            if(Files.exists(file)) {
                var command=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(command.get("nonce"),"");
                if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")) {
                    Map<String,Object> response;
                    try{synchronized(Api.MUTATION_LOCK){response=Map.of("nonce",nonce,"ok",true,"data",operation(command));}}
                    catch(Exception failure){response=Map.of("nonce",nonce,"ok",false,"error","Synthetic control failed");}
                    atomic("control-result.json",Json.write(response));last=nonce;
                }
            }
            Thread.sleep(20);
        }catch(InterruptedException done){Thread.currentThread().interrupt();return;}
         catch(Exception failure){throw new RuntimeException("Synthetic fixture control failed",failure);}
    }
    private static void installDeviceService()throws Exception {
        TrustedDeviceHost.installService(new TrustedDevices(deviceClock::get));
    }
    @SuppressWarnings("unchecked") private static Map<Object,Object> sessions()throws Exception {
        var field=Auth.class.getDeclaredField("SESSIONS");field.setAccessible(true);return (Map<Object,Object>)field.get(null);
    }
    public static final class EpochFailure implements Trigger {
        public void init(Connection connection,String schema,String name,String table,boolean before,int type) {}
        public void fire(Connection connection,Object[] oldRow,Object[] newRow)throws SQLException {
            if(failEpochWrites){epochFailures++;throw new SQLException("Synthetic isolated epoch write failure","45000");}
        }
        public void close() {}
        public void remove() {}
    }
    private static void atomic(String name,String value)throws Exception {
        Path temporary=root.resolve(name+".next");Files.writeString(temporary,value);
        Files.move(temporary,root.resolve(name),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private static String required(String key){String value=System.getProperty(key,"");if(value.isBlank())throw new IllegalArgumentException("Missing synthetic setting: "+key);return value;}
}
