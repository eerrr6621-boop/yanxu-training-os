package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Scripts-only synthetic identity/source seed and private fixed fault controls. No product route. */
public final class IntegrationDeliveryHistoryHttpFixture {
    private static Map<String,Object> saved;
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected isolated port or inspect");
        Path root=Path.of(required("integration.delivery.history.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-delivery-history-http-")||!data.getParent().equals(root)
                ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")
                ||!required("bootstrap.admin.password").matches("[a-f0-9]{48}"))throw new IllegalArgumentException("Isolated synthetic loopback fixture required");
        if(args[0].equals("inspect")){System.out.println(Json.write(snapshot()));Db.get().close();return;}
        if(!Boolean.getBoolean("integration.delivery.history.resume")) {
            try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
            Db.init();if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null||Db.count("m05_delivery_facts")!=0)throw new IllegalStateException("Unexpected seed");
            String hash=Auth.hash(required("bootstrap.admin.password"));
            String[] names={"worker","reader","outsider","unbound","binding-off","writer","wrong-action"};
            for(int i=0;i<names.length;i++){long id=Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,'viewer',1)","synthetic-history-"+names[i],hash,"SYNTHETIC HISTORY "+names[i]);if(id!=i+2)throw new IllegalStateException("Unexpected user sequence");}
            String token=Auth.login("admin",required("bootstrap.admin.password"));
            OrganizationAccessStore.publish(Auth.get(token),null,OrganizationAccessStore.parseConfiguration(configuration()));Auth.logout(token);
            long teacher=Db.insert("INSERT INTO teachers(name,status,teacher_level,fee_rate,base_province,base_city) VALUES('SYNTHETIC HISTORY TEACHER','在库','讲师',999,'浙江','杭州')");
            Db.exec("INSERT INTO teachers(name,status) VALUES('SYNTHETIC ALTERNATE HISTORY TEACHER','在库')");
            long primary=project("001"),other=project("999"),legacy=Db.insert("INSERT INTO projects(title,hours,status) VALUES('SYNTHETIC UNMIGRATED HISTORY',1,'进行中')");
            dispatch(primary,teacher,"PRIMARY");dispatch(primary,teacher,"EMPTY");dispatch(primary,teacher,"SPARE");dispatch(other,teacher,"CROSS ORGANIZATION");dispatch(legacy,teacher,"LEGACY");
            Db.exec("INSERT INTO fees(project_id,teacher_id,hours,rate,amount,status,pay_date,remark) VALUES(?,?,99,999,98901,'已发放','2025-01-03','SYNTHETIC HISTORICAL FEE')",primary,teacher);
            Files.writeString(root.resolve("fixture.json"),Json.write(Map.of("synthetic",true,"primary_dispatch_id",1,"empty_dispatch_id",2,"cross_organization_dispatch_id",4,"legacy_dispatch_id",5,"project_id",primary,"teacher_id",teacher,"worker_account_id",2,"reader_account_id",3)));
        } else Db.init();
        Thread watcher=new Thread(()->controls(root),"synthetic-delivery-history-controls");watcher.setDaemon(true);watcher.start();Main.main(args);
    }
    private static long project(String org)throws Exception {
        long demand=Db.insert("INSERT INTO demands(title,unit,hours,status) VALUES(?,'SYNTHETIC',1,'进行中')","SYNTHETIC HISTORY SOURCE "+org);
        long project=Db.insert("INSERT INTO projects(demand_id,title,unit,hours,start_date,end_date,status) VALUES(?,?,'SYNTHETIC',1,'2025-01-01','2025-01-31','进行中')",demand,"SYNTHETIC HISTORY PROJECT "+org);
        Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct',?,'P1','{}','{}','SYNTHETIC-HISTORY')",demand,org);
        Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'900','P1',1,'2025-01-01T00:00:00Z')",demand,project);return project;
    }
    private static void dispatch(long project,long teacher,String label)throws Exception {Db.exec("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,hours,status,material_status,remark) VALUES(?,?,?,'2025-01-02',99,'已确认','已就绪','SYNTHETIC PRIVATE MATERIAL')",project,teacher,"SYNTHETIC HISTORY "+label);}
    private static Map<String,Object> configuration() {
        List<String> roles=List.of("DELIVERY","READER","WRITEONLY","HANDLEONLY");List<Object> people=new ArrayList<>(),bindings=new ArrayList<>(),relations=new ArrayList<>(),grants=new ArrayList<>();
        String[] codes={"P1","P2","P3","P4","P5","P6"},role={"DELIVERY","READER","DELIVERY","DELIVERY","WRITEONLY","HANDLEONLY"};long[] accounts={2,3,4,6,7,8};
        for(int i=0;i<codes.length;i++){Map<String,Object> p=new LinkedHashMap<>();p.put("personCode",codes[i]);p.put("organizationCode",i==2?"999":"001");p.put("roleCodes",List.of(role[i]));p.put("responsibleOrganizationCodes",List.of());p.put("leaderPersonCode",null);p.put("bpPersonCode",null);p.put("enabled",true);people.add(p);bindings.add(Map.of("accountId",accounts[i],"personCode",codes[i],"enabled",i!=3));}
        Map<String,Object> optional=Map.of("required",false,"allowedTargetRoles",roles,"targetMustCoverOrganization",false,"allowSelf",false);
        for(String r:roles){relations.add(Map.of("roleCode",r,"leader",optional,"bp",optional));grant(grants,r,"demand.read","VIEW");}
        for(String resource:List.of("delivery.read","delivery.write","delivery.verify"))grant(grants,"DELIVERY",resource,resource.endsWith("read")?"VIEW":"HANDLE");
        grant(grants,"READER","delivery.read","VIEW");grant(grants,"WRITEONLY","delivery.write","HANDLE");grant(grants,"HANDLEONLY","delivery.read","HANDLE");
        List<Object> orgs=new ArrayList<>();for(String code:List.of("001","999","900")){Map<String,Object> org=new LinkedHashMap<>();org.put("organizationCode",code);org.put("parentOrganizationCode",null);org.put("enabled",true);orgs.add(org);}
        return Map.of("version","synthetic-history-v1","codeRules",Map.of("organizationPattern","[0-9]{3}","personPattern","P[0-9]+","rolePattern","[A-Z]+"),"roleCodes",roles,"organizations",orgs,"people",people,"relations",relations,"accountBindings",bindings,"grants",grants);
    }
    private static void grant(List<Object> grants,String role,String resource,String action){grants.add(Map.of("ruleId",role+"-"+resource,"roleCode",role,"resource",resource,"action",action,"effect","ALLOW","scope","OWN_ORG","organizationCodes",List.of()));}
    private static Map<String,Object> snapshot()throws Exception {
        Map<String,Object> result=new TreeMap<>();for(var table:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
            String name=table.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected table identifier");List<String> values=new ArrayList<>();for(var row:Db.query("SELECT * FROM "+name))values.add(Json.write(new TreeMap<>(row)));Collections.sort(values);
            result.put(name,Map.of("count",values.size(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(values).getBytes(StandardCharsets.UTF_8)))));
        }return result;
    }
    @SuppressWarnings("unchecked") private static Object operation(String op)throws Exception {
        switch(op){
            case "snapshot":return snapshot();
            case "device-epochs":return Db.query("SELECT user_id,epoch FROM s01_trusted_device_epochs ORDER BY user_id");
            case "revoke-worker":Auth.revokeUserSessions(2);break;
            case "disable-worker":Db.exec("UPDATE users SET status=0 WHERE id=2");break;
            case "restore-worker":Db.exec("UPDATE users SET status=1 WHERE id=2");break;
            case "archive":Db.exec("UPDATE projects SET status='已归档' WHERE id=1");break;
            case "unarchive":Db.exec("UPDATE projects SET status='进行中' WHERE id=1");break;
            case "capture":saved=new LinkedHashMap<>();saved.put("head",Db.one("SELECT * FROM m05_delivery_facts WHERE dispatch_id=1"));saved.put("revisions",Db.query("SELECT * FROM m05_fact_revisions WHERE dispatch_id=1 ORDER BY revision"));break;
            case "restore":
                if(saved==null)throw new IllegalStateException("Capture before restore");
                Map<String,Object> head=(Map<String,Object>)saved.get("head");Db.exec("UPDATE m05_delivery_facts SET payload=?,revision=? WHERE dispatch_id=1",head.get("payload"),head.get("revision"));Db.exec("DELETE FROM m05_fact_revisions WHERE dispatch_id=1");
                for(var row:(List<Map<String,Object>>)saved.get("revisions"))Db.exec("INSERT INTO m05_fact_revisions(dispatch_id,revision,payload,event_type,actor_code,account_id,created_at) VALUES(?,?,?,?,?,?,?)",row.get("dispatch_id"),row.get("revision"),row.get("payload"),row.get("event_type"),row.get("actor_code"),row.get("account_id"),row.get("created_at"));
                Db.exec("UPDATE dispatches SET teacher_id=1 WHERE id=1");break;
            case "fault-missing":Db.exec("DELETE FROM m05_fact_revisions WHERE dispatch_id=1 AND revision=2");break;
            case "fault-event":Db.exec("UPDATE m05_fact_revisions SET event_type='SAVE' WHERE dispatch_id=1 AND revision=2");break;
            case "fault-actor":Db.exec("UPDATE m05_fact_revisions SET actor_code='P999' WHERE dispatch_id=1 AND revision=2");break;
            case "fault-source":Db.exec("UPDATE dispatches SET teacher_id=2 WHERE id=1");break;
            case "fault-oversize":Db.exec("UPDATE m05_fact_revisions SET payload=CONCAT(payload,?) WHERE dispatch_id=1 AND revision=1"," ".repeat(33000));break;
            case "fault-synthetic":case "fault-association":case "fault-verification":case "fault-head":
                boolean isHead=op.equals("fault-head");long revision=op.equals("fault-verification")?2:1;
                Map<String,Object> row=Db.one(isHead?"SELECT payload FROM m05_delivery_facts WHERE dispatch_id=1":"SELECT payload FROM m05_fact_revisions WHERE dispatch_id=1 AND revision=?",isHead?new Object[0]:new Object[]{revision});
                Map<String,Object> payload=(Map<String,Object>)Json.parse(row.get("payload").toString());
                if(op.equals("fault-synthetic"))payload.put("data_mode","SYNTHETIC");
                if(op.equals("fault-association"))payload.put("instructor_code","TEACHER-999999");
                if(op.equals("fault-verification"))((Map<String,Object>)payload.get("verification")).put("actor_code","P999");
                if(isHead){((Map<String,Object>)payload.get("hours")).put("estimated","987654321.12345678");Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=1",Json.write(payload));}
                else Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=1 AND revision=?",Json.write(payload),revision);break;
            default:throw new IllegalArgumentException("Unknown synthetic fault control");
        }return Map.of();
    }
    @SuppressWarnings("unchecked") private static void controls(Path root){String last="";while(!Thread.currentThread().isInterrupted())try{
        Path file=root.resolve("control-request.json");if(Files.exists(file)){Map<String,Object> command=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(command.get("nonce"),"");if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")){
            Map<String,Object> result; synchronized(Api.MUTATION_LOCK){result=Map.of("nonce",nonce,"ok",true,"data",operation(Objects.toString(command.get("operation"),"")));}
            Path tmp=root.resolve("control-result.next");Files.writeString(tmp,Json.write(result));Files.move(tmp,root.resolve("control-result.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);last=nonce;
        }}Thread.sleep(20);
    }catch(InterruptedException done){Thread.currentThread().interrupt();return;}catch(Exception failure){throw new RuntimeException("Synthetic history control failed",failure);}}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing fixture property: "+name);return value;}
}
