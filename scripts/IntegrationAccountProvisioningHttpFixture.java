package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Synthetic pinned intake for initial configuration UI/HTTP; no real personnel material is opened. */
public final class IntegrationAccountProvisioningHttpFixture {
    private static Object savedReceipt,savedDecisionDigest,savedAccountName,savedConfiguration;
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected prepare, inspect or isolated port");
        Path root=Path.of(required("integration.account.provisioning.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();String password=required("bootstrap.admin.password");
        if(!root.getFileName().toString().startsWith("yanxu-account-provisioning-http-")||!data.getParent().equals(root)
            ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")||!password.matches("[a-f0-9]{48}")
            ||!"legacy".equals(System.getProperty("login.email.mode")))throw new IllegalArgumentException("Explicit synthetic loopback fixture required");
        if(args[0].equals("prepare")){prepare(root);return;}
        if(args[0].equals("inspect")){System.out.println(Json.write(snapshot()));Db.get().close();return;}
        Path manifest=Path.of(required(OrganizationAccountImportHost.MANIFEST_PROPERTY)).toRealPath();
        if(!manifest.equals(root.resolve("synthetic-manifest.json"))||!hash(Files.readAllBytes(manifest)).equals(required(OrganizationAccountImportHost.SHA256_PROPERTY)))throw new IllegalArgumentException("Use this fixture's immutable server pins");
        if(!Boolean.getBoolean("integration.account.provisioning.resume")){
            try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
            Db.init();if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null||Db.count("organization_account_import_people")!=0)throw new IllegalStateException("Unexpected fixture seed");
            Map<String,Object> accounts=new LinkedHashMap<>();accounts.put("admin",1L);String passwordHash=Auth.hash(password);
            for(String role:List.of("admin2","manager","viewer"))accounts.put(role,Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)","synthetic-provisioning-"+role,passwordHash,"SYNTHETIC PROVISIONING "+role,role.equals("admin2")?"admin":role));
            if(!Boolean.getBoolean("integration.account.provisioning.empty"))receive(password);
            Map<String,Object> metadata=Json.parseMap(Files.readString(root.resolve("fixture.json")));metadata.put("accounts",accounts);metadata.put("received",!Boolean.getBoolean("integration.account.provisioning.empty"));metadata.put("linked_admin",Boolean.getBoolean("integration.account.provisioning.linkAdmin"));Files.writeString(root.resolve("fixture.json"),Json.write(metadata));
        }else Db.init();
        Thread control=new Thread(()->controls(root),"synthetic-account-provisioning-controls");control.setDaemon(true);control.start();Main.main(args);
    }
    private static void prepare(Path root)throws Exception {
        if(Files.exists(root.resolve("sources")))throw new IllegalArgumentException("Prepare synthetic source once");
        Config c=OrganizationAccountImportSourceTest.fixture(root.resolve("sources"));OrganizationAccountImportSource.load(c);
        Map<String,Object> pins=new LinkedHashMap<>();pins.put("candidates",pin(c.candidates()));pins.put("candidatePolicy",pin(c.candidatePolicy()));pins.put("roleSource",pin(c.roleSource()));pins.put("authorization",pin(c.authorization()));pins.put("audit",pin(c.audit()));pins.put("regionReference",pin(c.regionReference()));pins.put("scopeDecision",pin(c.scopeDecision()));pins.put("preparedPreview",pin(c.preparedPreview()));
        Path manifest=root.resolve("synthetic-manifest.json");Files.writeString(manifest,Json.write(Map.of("schema","M01-SERVER-PIN-MANIFEST-v1","batchKey",c.batchKey(),"usage","SYNTHETIC ONLY initial organization configuration HTTP/UI","pins",pins,"originals",c.originals().stream().map(IntegrationAccountProvisioningHttpFixture::pin).toList())));
        Map<String,Object> metadata=Map.of("manifest",manifest.toString(),"sha256",hash(Files.readAllBytes(manifest)),"candidates",7,"branches",3,"batch_key",c.batchKey(),"synthetic",true);Files.writeString(root.resolve("fixture.json"),Json.write(metadata));System.out.println(Json.write(metadata));
    }
    @SuppressWarnings("unchecked") private static void receive(String password)throws Exception {
        String token=Auth.login("admin",password);Auth.Session actor=Auth.get(token);OrganizationAccountImport importer=OrganizationAccountImportHost.trustedImporter();Map<String,Object> preview=importer.preview(actor);List<Map<String,Object>> decisions=new ArrayList<>();boolean linkAdmin=Boolean.getBoolean("integration.account.provisioning.linkAdmin");Map<String,Object> proof=linkAdmin?importer.account(actor,1):Map.of();
        for(Object raw:(List<?>)preview.get("rows")){Map<String,Object> row=(Map<String,Object>)raw;boolean link=linkAdmin&&"teacher-row-4".equals(row.get("reference"));Map<String,Object> choice=new LinkedHashMap<>();choice.put("reference",row.get("reference"));choice.put("action",link?"LINK_EXISTING":"CREATE_PENDING");choice.put("accountId",link?1L:null);choice.put("accountProof",link?proof.get("proof"):null);choice.put("reviewed",true);decisions.add(choice);}
        importer.commit(actor,Map.of("expectedRevision",preview.get("revision"),"sourceFingerprint",preview.get("sourceFingerprint"),"reviewToken",preview.get("reviewToken"),"decisions",decisions));Auth.revokeUserSessions(1);
    }
    private static Map<String,Object> pin(FilePin pin){return Map.of("path",pin.path().toString(),"sha256",pin.sha256());}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static Map<String,Object> snapshot()throws Exception {
        Map<String,Object> result=new TreeMap<>();for(var table:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
            String name=table.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected table identifier");List<String> rows=new ArrayList<>();for(var row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);result.put(name,Map.of("count",rows.size(),"sha256",hash(Json.write(rows).getBytes(StandardCharsets.UTF_8))));
        }return result;
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static Object operation(String op)throws Exception {
        switch(op){
            case "snapshot":return snapshot();
            case "device-epochs":return Db.query("SELECT user_id,epoch FROM s01_trusted_device_epochs ORDER BY user_id");
            case "revoke-admin":Auth.revokeUserSessions(1);break;
            case "downgrade-admin":Db.exec("UPDATE users SET role='manager' WHERE id=1");break;
            case "restore-admin":Db.exec("UPDATE users SET role='admin',status=1 WHERE id=1");break;
            case "capture":savedDecisionDigest=Db.one("SELECT decision_digest FROM organization_account_import_batches WHERE batch_key='synthetic-import'").get("decision_digest");savedReceipt=Db.one("SELECT receipt FROM organization_account_import_batches WHERE batch_key='synthetic-import'").get("receipt");savedAccountName=Db.one("SELECT name FROM users WHERE id=(SELECT account_id FROM organization_account_import_people WHERE source_reference='teacher-row-3')").get("name");break;
            case "account-change":Db.exec("UPDATE users SET name='SYNTHETIC CHANGED CURRENT ACCOUNT' WHERE id=(SELECT account_id FROM organization_account_import_people WHERE source_reference='teacher-row-3')");break;
            case "account-restore":Db.exec("UPDATE users SET name=? WHERE id=(SELECT account_id FROM organization_account_import_people WHERE source_reference='teacher-row-3')",savedAccountName);break;
            case "receipt-change":var row=Db.one("SELECT receipt FROM organization_account_import_batches WHERE batch_key='synthetic-import'");var receipt=Json.parseMap(row.get("receipt").toString());((Map<String,Object>)((List<?>)receipt.get("rows")).get(0)).put("accountId",900000L);Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key='synthetic-import'",Json.write(receipt));break;
            case "receipt-replay-change":var stored=Json.parseMap(savedReceipt.toString());stored.put("replayed",!Boolean.TRUE.equals(stored.get("replayed")));Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key='synthetic-import'",Json.write(stored));break;
            case "decision-digest-change":Db.exec("UPDATE organization_account_import_batches SET decision_digest=? WHERE batch_key='synthetic-import'","b".repeat(64));break;
            case "receipt-restore":Db.exec("UPDATE organization_account_import_batches SET receipt=?,decision_digest=? WHERE batch_key='synthetic-import'",savedReceipt,savedDecisionDigest);break;
            case "publish-failure-on":Db.exec("ALTER TABLE organization_access_config ADD CONSTRAINT synthetic_provisioning_publish_failure CHECK (active_slot IS NULL)");break;
            case "publish-failure-off":Db.exec("ALTER TABLE organization_access_config DROP CONSTRAINT synthetic_provisioning_publish_failure");break;
            case "corrupt-configuration":savedConfiguration=Db.one("SELECT payload FROM organization_access_config WHERE active_slot=1").get("payload");Db.exec("UPDATE organization_access_config SET payload='{}' WHERE active_slot=1");break;
            case "restore-configuration":Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",savedConfiguration);break;
            case "expire-reviews":{
                var holder=Class.forName("com.training.OrganizationAccountProvisioningHost").getDeclaredField("provisioning");holder.setAccessible(true);Object generator=holder.get(null);if(generator==null)throw new IllegalStateException("Preview first");
                var field=generator.getClass().getDeclaredField("REVIEWS");field.setAccessible(true);Map reviews=(Map)field.get(generator);
                for(Object key:new ArrayList<>(reviews.keySet())){Object value=reviews.get(key);try{var expires=value.getClass().getDeclaredField("expires");expires.setAccessible(true);expires.setLong(value,0L);}catch(IllegalAccessException immutable){var parts=value.getClass().getRecordComponents();if(parts==null)throw immutable;Object[] values=new Object[parts.length];Class<?>[] types=new Class<?>[parts.length];for(int i=0;i<parts.length;i++){var access=parts[i].getAccessor();access.setAccessible(true);values[i]=access.invoke(value);types[i]=parts[i].getType();if(parts[i].getName().equals("expires"))values[i]=0L;}var constructor=value.getClass().getDeclaredConstructor(types);constructor.setAccessible(true);reviews.put(key,constructor.newInstance(values));}}
                return Map.of("expired_reviews",reviews.size());
            }
            default:throw new IllegalArgumentException("Unknown fixed synthetic control");
        }return Map.of();
    }
    @SuppressWarnings("unchecked") private static void controls(Path root){String last="";while(!Thread.currentThread().isInterrupted())try{
        Path file=root.resolve("control-request.json");if(Files.exists(file)){var request=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(request.get("nonce"),"");if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")){
            Map<String,Object> result;synchronized(Api.MUTATION_LOCK){result=Map.of("nonce",nonce,"ok",true,"data",operation(Objects.toString(request.get("operation"),"")));}Path tmp=root.resolve("control-result.next");Files.writeString(tmp,Json.write(result));Files.move(tmp,root.resolve("control-result.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);last=nonce;
        }}Thread.sleep(20);
    }catch(InterruptedException done){Thread.currentThread().interrupt();return;}catch(Exception error){throw new RuntimeException("Synthetic provisioning control failed",error);}}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing fixture property: "+name);return value;}
}
