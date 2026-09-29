package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccountImportSource.*;

/** Synthetic pinned source preparation plus a real Main wrapper with private fixed fault controls. */
public final class IntegrationTeacherRosterHttpFixture {
    private static Map<String,Object> saved;
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected prepare, inspect or isolated port");
        Path root=Path.of(required("integration.teacher.roster.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-teacher-roster-http-")||!data.getParent().equals(root)
                ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")
                ||!required("bootstrap.admin.password").matches("[a-f0-9]{48}"))throw new IllegalArgumentException("Isolated synthetic loopback fixture required");
        if(args[0].equals("prepare")){
            if(Files.exists(root.resolve("sources")))throw new IllegalArgumentException("Prepare a fresh source directory once");
            OrganizationAccountImportSourceTest.Fixture material=new OrganizationAccountImportSourceTest.Fixture(root.resolve("sources"));
            for(Object value:(List<?>)material.batch.get("candidates")){
                @SuppressWarnings("unchecked") Map<String,Object> row=(Map<String,Object>)value;
                String reference=row.get("candidate_reference").toString();
                if(reference.startsWith("teacher-row-")){int n=Integer.parseInt(reference.substring(12));row.put("source_teacher_level",List.of("讲师","高级讲师","特级讲师").get((n-3)%3));row.put("source_job",n==7?null:"SYNTHETIC JOB "+n);}
            }
            Config c=material.save();OrganizationAccountImportSource.load(c);
            Map<String,Object> pins=new LinkedHashMap<>();pins.put("candidates",pin(c.candidates()));pins.put("candidatePolicy",pin(c.candidatePolicy()));pins.put("roleSource",pin(c.roleSource()));pins.put("authorization",pin(c.authorization()));pins.put("audit",pin(c.audit()));pins.put("regionReference",pin(c.regionReference()));pins.put("scopeDecision",pin(c.scopeDecision()));pins.put("preparedPreview",pin(c.preparedPreview()));
            Path manifest=root.resolve("synthetic-manifest.json");Files.writeString(manifest,Json.write(Map.of("schema","M01-SERVER-PIN-MANIFEST-v1","batchKey",c.batchKey(),"usage","SYNTHETIC ONLY teacher roster real HTTP acceptance","pins",pins,"originals",c.originals().stream().map(IntegrationTeacherRosterHttpFixture::pin).toList())));
            var info=Map.of("manifest",manifest.toString(),"sha256",hash(Files.readAllBytes(manifest)),"candidates",7,"teachers",6,"historicalExcluded",2);Files.writeString(root.resolve("fixture.json"),Json.write(info));System.out.println(Json.write(info));return;
        }
        if(args[0].equals("inspect")){System.out.println(Json.write(snapshot()));Db.get().close();return;}
        Path manifest=Path.of(required(OrganizationAccountImportHost.MANIFEST_PROPERTY)).toRealPath();
        if(!manifest.equals(root.resolve("synthetic-manifest.json"))||!hash(Files.readAllBytes(manifest)).equals(required(OrganizationAccountImportHost.SHA256_PROPERTY)))throw new IllegalArgumentException("Use this fixture's immutable generated pins");
        if(!Boolean.getBoolean("integration.teacher.roster.resume")){
            try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
            Db.init();if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null||Db.count("organization_account_import_people")!=0)throw new IllegalStateException("Unexpected fixture seed");
        }else Db.init();
        Thread worker=new Thread(()->controls(root),"synthetic-teacher-roster-controls");worker.setDaemon(true);worker.start();Main.main(args);
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
            case "rollback-on":Db.exec("ALTER TABLE teachers ADD CONSTRAINT synthetic_teacher_roster_rollback CHECK (name <> 'SYNTHETIC PRIVATE 6')");break;
            case "rollback-off":Db.exec("ALTER TABLE teachers DROP CONSTRAINT synthetic_teacher_roster_rollback");break;
            case "capture-account":saved=new LinkedHashMap<>();saved.put("accountReceipt",Db.one("SELECT receipt FROM organization_account_import_batches WHERE batch_key='synthetic-import'").get("receipt"));break;
            case "restore-account":Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key='synthetic-import'",saved.get("accountReceipt"));break;
            case "capture-roster":saved.put("rosterReceipt",Db.one("SELECT receipt FROM teacher_roster_import_batches WHERE batch_key='synthetic-import'").get("receipt"));break;
            case "restore-roster":Db.exec("UPDATE teacher_roster_import_batches SET receipt=? WHERE batch_key='synthetic-import'",saved.get("rosterReceipt"));break;
            case "fault-roster-receipt":
                var roster=Db.one("SELECT receipt FROM teacher_roster_import_batches WHERE batch_key='synthetic-import'");var rosterReceipt=(Map<String,Object>)Json.parse(roster.get("receipt").toString());var rosterRows=(List<Object>)rosterReceipt.get("rows");((Map<String,Object>)rosterRows.get(0)).put("teacherId",900000L);Db.exec("UPDATE teacher_roster_import_batches SET receipt=? WHERE batch_key='synthetic-import'",Json.write(rosterReceipt));break;
            case "fault-account-receipt":
                var row=Db.one("SELECT receipt FROM organization_account_import_batches WHERE batch_key='synthetic-import'");var receipt=(Map<String,Object>)Json.parse(row.get("receipt").toString());var rows=(List<Object>)receipt.get("rows");((Map<String,Object>)rows.get(0)).put("accountId",900000L);Db.exec("UPDATE organization_account_import_batches SET receipt=? WHERE batch_key='synthetic-import'",Json.write(receipt));break;
            case "expire-reviews":expireReviews();break;
            default:throw new IllegalArgumentException("Unknown fixed synthetic operation");
        }return Map.of();
    }
    /** Test-only clock fault; never exposes review tokens or adds a product expiration override. */
    @SuppressWarnings({"unchecked","rawtypes"}) private static void expireReviews()throws Exception {
        var host=Class.forName("com.training.TeacherRosterImportHost");var importerField=host.getDeclaredField("importer");importerField.setAccessible(true);Object importer=importerField.get(null);
        var reviewsField=importer.getClass().getDeclaredField("reviews");reviewsField.setAccessible(true);Map reviews=(Map)reviewsField.get(importer);if(reviews.isEmpty())throw new IllegalStateException("Create a genuine HTTP review first");
        for(Object key:new ArrayList<>(reviews.keySet())){
            Object review=reviews.get(key);var components=review.getClass().getRecordComponents();if(components==null)throw new IllegalStateException("Expected bounded review record");Object[] values=new Object[components.length];Class<?>[] types=new Class<?>[components.length];boolean expired=false;
            for(int i=0;i<components.length;i++){var accessor=components[i].getAccessor();accessor.setAccessible(true);values[i]=accessor.invoke(review);types[i]=components[i].getType();if(components[i].getName().toLowerCase(Locale.ROOT).contains("expir")&&types[i]==long.class){values[i]=0L;expired=true;}}
            if(!expired)throw new IllegalStateException("Review expiration component unavailable");var constructor=review.getClass().getDeclaredConstructor(types);constructor.setAccessible(true);reviews.put(key,constructor.newInstance(values));
        }
    }
    @SuppressWarnings("unchecked") private static void controls(Path root){String last="";while(!Thread.currentThread().isInterrupted())try{
        Path file=root.resolve("control-request.json");if(Files.exists(file)){var request=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(request.get("nonce"),"");if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")){
            Map<String,Object> result;synchronized(Api.MUTATION_LOCK){result=Map.of("nonce",nonce,"ok",true,"data",operation(Objects.toString(request.get("operation"),"")));}Path tmp=root.resolve("control-result.next");Files.writeString(tmp,Json.write(result));Files.move(tmp,root.resolve("control-result.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);last=nonce;
        }}Thread.sleep(20);
    }catch(InterruptedException done){Thread.currentThread().interrupt();return;}catch(Exception error){throw new RuntimeException("Synthetic teacher roster control failed",error);}}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing fixture property: "+name);return value;}
}
