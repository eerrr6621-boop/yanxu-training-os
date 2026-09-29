package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Scripts-only synthetic relationships; no private roster, business inference or production route. */
public final class IntegrationAccountRelationshipsHttpFixture {
    private static String savedConfiguration;
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected isolated port or inspect");
        Path root=Path.of(required("integration.account.relationships.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();String password=required("bootstrap.admin.password");
        if(!root.getFileName().toString().startsWith("yanxu-account-relationships-http-")||!data.getParent().equals(root)
                ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")
                ||!password.matches("[a-f0-9]{48}")||!"legacy".equals(System.getProperty("login.email.mode")))throw new IllegalArgumentException("Explicit isolated synthetic loopback fixture required");
        if(args[0].equals("inspect")){System.out.println(Json.write(snapshot()));Db.get().close();return;}
        if(!Boolean.getBoolean("integration.account.relationships.resume")){
            try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
            Db.init();if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null)throw new IllegalStateException("Unexpected fixture seed");setup(root,password);
        }else Db.init();
        Thread control=new Thread(()->controls(root),"synthetic-account-relationships-controls");control.setDaemon(true);control.start();Main.main(args);
    }
    private static void setup(Path root,String password)throws Exception {
        Map<String,Long> accounts=new LinkedHashMap<>();accounts.put("admin",1L);String hash=Auth.hash(password);
        for(String name:List.of("admin2","manager","viewer","subject","leader-a","bp-a","leader-b","bp-other","teacher","combined-split","combined-approved","combined-unapproved","intersection","intersection-leader","cycle-a","cycle-b","invalid-current","retired-leader","peer","leader-other","unbound","self-allowed")){
            String role=name.equals("admin2")?"admin":name.equals("manager")?"manager":"viewer";
            String display=Set.of("leader-a","leader-b").contains(name)?"SYNTHETIC SAME DISPLAY NAME":"SYNTHETIC RELATION "+name;
            accounts.put(name,Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)","synthetic-relation-"+name,hash,display,role));
        }
        Set<String> roles=Set.of("EMP","AUX","LEADER","BP","TEACHER","REVIEWER","SELF");
        RelationRule leader=new RelationRule(true,Set.of("LEADER"),true,false),bp=new RelationRule(true,Set.of("BP"),true,false);
        RelationRule optionalLeader=new RelationRule(false,Set.of("LEADER"),true,false),optionalBp=new RelationRule(false,Set.of("BP"),true,false);
        List<RoleRelations> relations=new ArrayList<>();for(String role:roles.stream().sorted().toList())relations.add(new RoleRelations(role,
                role.equals("EMP")?leader:role.equals("AUX")?new RelationRule(true,Set.of("REVIEWER"),true,false):role.equals("SELF")?new RelationRule(false,Set.of("SELF"),true,true):optionalLeader,
                role.equals("EMP")||role.equals("AUX")?bp:optionalBp));
        List<Person> people=List.of(
            person("P1","001",Set.of("EMP"),Map.of("EMP",Set.of()),"P2","P3"),
            person("P2","001",Set.of("LEADER"),Map.of("LEADER",Set.of("001")),null,null),
            person("P3","001",Set.of("BP"),Map.of("BP",Set.of("001")),null,null),
            person("P4","001",Set.of("LEADER"),Map.of("LEADER",Set.of("001")),null,null),
            person("P5","002",Set.of("BP"),Map.of("BP",Set.of("002")),null,null),
            person("P6","001",Set.of("TEACHER"),Map.of("TEACHER",Set.of()),null,null),
            person("P7","001",Set.of("LEADER","BP"),Map.of("LEADER",Set.of("001"),"BP",Set.of("002")),null,null),
            person("P8","001",Set.of("LEADER","BP"),Map.of("LEADER",Set.of("001"),"BP",Set.of("001")),null,null),
            person("P9","001",Set.of("LEADER","BP"),Map.of("LEADER",Set.of("001"),"BP",Set.of("001")),null,null),
            person("P10","001",Set.of("EMP","AUX"),Map.of("EMP",Set.of(),"AUX",Set.of()),"P11","P3"),
            person("P11","001",Set.of("LEADER","REVIEWER"),Map.of("LEADER",Set.of("001"),"REVIEWER",Set.of("001")),null,null),
            person("P12","001",Set.of("LEADER"),Map.of("LEADER",Set.of("001")),null,null),
            person("P13","001",Set.of("LEADER"),Map.of("LEADER",Set.of("001")),"P12",null),
            person("P14","001",Set.of("EMP"),Map.of("EMP",Set.of()),"P15","P3"),
            person("P15","001",Set.of("LEADER"),Map.of("LEADER",Set.of("001")),null,null),
            person("P16","002",Set.of("EMP"),Map.of("EMP",Set.of()),"P17","P5"),
            person("P17","002",Set.of("LEADER"),Map.of("LEADER",Set.of("002")),null,null),
            person("P18","001",Set.of("SELF"),Map.of("SELF",Set.of("001")),null,null),
            person("P19","001",Set.of("EMP"),Map.of("EMP",Set.of()),"P2","P3"));
        List<String> bound=List.of("subject","leader-a","bp-a","leader-b","bp-other","teacher","combined-split","combined-approved","combined-unapproved","intersection","intersection-leader","cycle-a","cycle-b","invalid-current","retired-leader","peer","leader-other","self-allowed");
        List<AccountBinding> bindings=new ArrayList<>();Map<String,String> personCodes=new LinkedHashMap<>();for(int i=0;i<bound.size();i++){String name=bound.get(i),code="P"+(i+1);personCodes.put(name,code);bindings.add(new AccountBinding(accounts.get(name),code,!name.equals("retired-leader")));}
        bindings.add(new AccountBinding(accounts.get("admin2"),"P19",true));personCodes.put("admin2","P19");
        List<Grant> grants=new ArrayList<>();for(String role:List.of("EMP","LEADER","BP","REVIEWER","SELF")){
            grants.add(new Grant(role+"_READ",role,"demand.read",Action.VIEW,Effect.ALLOW,role.equals("EMP")?Scope.OWN_ORG:Scope.RESPONSIBLE_ORGS,Set.of()));
            if(!role.equals("EMP"))grants.add(new Grant(role+"_APPROVAL",role,"approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        }
        grants.add(new Grant("EMP_WRITE","EMP","demand.write",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        Configuration configuration=new Configuration("synthetic-relationships-v1",new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),roles,
                List.of(new Organization("001",null,true),new Organization("002",null,true)),people,relations,bindings,grants,
                List.of(new CombinedApprovalAssignment("001","P8","LEADER","BP","SYNTHETIC-EXPLICIT-COMBINED-001")));
        Files.writeString(root.resolve("configuration.json"),Json.write(OrganizationAccessStore.toMap(configuration)));
        if(!Boolean.getBoolean("integration.account.relationships.empty")){String token=Auth.login("admin",password);OrganizationAccessStore.publish(Auth.get(token),null,configuration);Auth.revokeUserSessions(1);}
        Db.exec("INSERT INTO teachers(name,status,teacher_level) VALUES('SYNTHETIC RELATION UNRELATED TEACHER','待完善','讲师')");
        Files.writeString(root.resolve("fixture.json"),Json.write(Map.of("accounts",accounts,"person_codes",personCodes,"organizations",List.of("001","002"),"synthetic",true,"configuration_version",configuration.version())));
    }
    private static Person person(String code,String org,Set<String> roles,Map<String,Set<String>> scopes,String leader,String bp){Set<String> union=new LinkedHashSet<>();scopes.values().forEach(union::addAll);return new Person(code,org,union,leader,bp,roles,true,scopes);}
    private static Map<String,Object> snapshot()throws Exception {
        Map<String,Object> result=new TreeMap<>();for(var table:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
            String name=table.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected table identifier");List<String> rows=new ArrayList<>();for(var row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);
            result.put(name,Map.of("count",rows.size(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(rows).getBytes(StandardCharsets.UTF_8)))));
        }return result;
    }
    @SuppressWarnings("unchecked") private static Object operation(String op)throws Exception {
        switch(op){
            case "snapshot":return snapshot();
            case "device-epochs":return Db.query("SELECT user_id,epoch FROM s01_trusted_device_epochs ORDER BY user_id");
            case "devices":return Db.query("SELECT * FROM s01_trusted_devices ORDER BY selector");
            case "seed-devices":{
                if(Db.count("s01_trusted_devices")!=0)throw new IllegalStateException("Synthetic devices must begin empty");long now=System.currentTimeMillis();
                for(Map<String,Object> user:Db.query("SELECT id FROM users ORDER BY id")){
                    long id=((Number)user.get("id")).longValue();Map<String,Object> epoch=Db.one("SELECT epoch FROM s01_trusted_device_epochs WHERE user_id=?",id);
                    Db.exec("INSERT INTO s01_trusted_devices(selector,user_id,secret_hash,fingerprint,user_epoch,created_at,last_used_at,expires_at,revoked,expired,origin) VALUES(?,?,?,?,?,?,?,?,FALSE,FALSE,?)",String.format("%032x",id),id,"0".repeat(64),"1".repeat(64),epoch==null?0:epoch.get("epoch"),now,now,now+3600000,"http://127.0.0.1:1");
                }
                return Map.of("seeded",Db.count("s01_trusted_devices"));
            }
            case "expire-reviews":{
                // Test-only reflection of private memory expiry; never exposed as a product endpoint.
                var field=Class.forName("com.training.OrganizationAccountRelationships").getDeclaredField("REVIEWS");field.setAccessible(true);var reviews=(Map<?,?>)field.get(null);
                for(Object review:reviews.values()){var expires=review.getClass().getDeclaredField("expires");expires.setAccessible(true);expires.setLong(review,0L);}
                return Map.of("expired_reviews",reviews.size());
            }
            case "session-counts":{var field=Auth.class.getDeclaredField("SESSIONS");field.setAccessible(true);Map<String,Integer> counts=new TreeMap<>();for(Auth.Session session:((Map<String,Auth.Session>)field.get(null)).values())counts.merge(Long.toString(session.uid),1,Integer::sum);return counts;}
            case "subject-rename":Db.exec("UPDATE users SET name='SYNTHETIC CHANGED DISPLAY' WHERE username='synthetic-relation-subject'");break;
            case "subject-disable":Db.exec("UPDATE users SET status=0 WHERE username='synthetic-relation-subject'");break;
            case "subject-restore":Db.exec("UPDATE users SET name='SYNTHETIC RELATION subject',status=1 WHERE username='synthetic-relation-subject'");break;
            case "retired-disable":Db.exec("UPDATE users SET status=0 WHERE username='synthetic-relation-retired-leader'");break;
            case "retired-restore":Db.exec("UPDATE users SET status=1 WHERE username='synthetic-relation-retired-leader'");break;
            case "candidate-disable":Db.exec("UPDATE users SET status=0 WHERE username='synthetic-relation-leader-b'");break;
            case "candidate-restore":Db.exec("UPDATE users SET status=1 WHERE username='synthetic-relation-leader-b'");break;
            case "downgrade-admin":Db.exec("UPDATE users SET role='manager' WHERE id=1");break;
            case "restore-admin":Db.exec("UPDATE users SET role='admin',status=1 WHERE id=1");break;
            case "disable-admin":Db.exec("UPDATE users SET status=0 WHERE id=1");break;
            case "revoke-admin":Auth.revokeUserSessions(1);break;
            case "corrupt-configuration":savedConfiguration=Db.one("SELECT payload FROM organization_access_config WHERE active_slot=1").get("payload").toString();Db.exec("UPDATE organization_access_config SET payload='{}' WHERE active_slot=1");break;
            case "restore-configuration":if(savedConfiguration==null)throw new IllegalStateException("No saved configuration");Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",savedConfiguration);break;
            default:throw new IllegalArgumentException("Unknown fixed synthetic control");
        }return Map.of();
    }
    @SuppressWarnings("unchecked") private static void controls(Path root){String last="";while(!Thread.currentThread().isInterrupted())try{
        Path file=root.resolve("control-request.json");if(Files.exists(file)){var request=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(request.get("nonce"),"");if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")){
            Map<String,Object> result;synchronized(Api.MUTATION_LOCK){result=Map.of("nonce",nonce,"ok",true,"data",operation(Objects.toString(request.get("operation"),"")));}Path tmp=root.resolve("control-result.next");Files.writeString(tmp,Json.write(result));Files.move(tmp,root.resolve("control-result.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);last=nonce;
        }}Thread.sleep(20);
    }catch(InterruptedException done){Thread.currentThread().interrupt();return;}catch(Exception error){throw new RuntimeException("Synthetic relationship-maintenance control failed",error);}}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing fixture property: "+name);return value;}
}
