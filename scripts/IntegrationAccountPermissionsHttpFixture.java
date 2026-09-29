package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Test-only synthetic organization gates. Main and Auth remain the real application entry points. */
public final class IntegrationAccountPermissionsHttpFixture {
    private static String savedConfiguration;
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected isolated port or inspect");
        Path root=Path.of(required("integration.account.permissions.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();
        String password=required("bootstrap.admin.password");
        if(!root.getFileName().toString().startsWith("yanxu-account-permissions-http-")||!data.getParent().equals(root)
                ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")
                ||!password.matches("[a-f0-9]{48}")||!"legacy".equals(System.getProperty("login.email.mode")))
            throw new IllegalArgumentException("Explicit isolated synthetic loopback fixture required");
        if(args[0].equals("inspect")){System.out.println(Json.write(snapshot()));Db.get().close();return;}
        if(!Boolean.getBoolean("integration.account.permissions.resume")){
            try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty data required");}
            Db.init();if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null)throw new IllegalStateException("Unexpected fixture seed");
            setup(root,password);
        }else Db.init();
        Thread control=new Thread(()->controls(root),"synthetic-account-permissions-controls");control.setDaemon(true);control.start();Main.main(args);
    }
    private static void setup(Path root,String password)throws Exception {
        Map<String,Long> accounts=new LinkedHashMap<>();accounts.put("admin",1L);String hash=Auth.hash(password);
        for(String name:List.of("admin2","manager","viewer","teacher","combined","denied","disabled","unbound","binding-off","person-off","org-off","parent-only")){
            String role=name.equals("admin2")?"admin":name.equals("manager")?"manager":"viewer";
            accounts.put(name,Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)","synthetic-access-"+name,hash,"SYNTHETIC ACCESS "+name,role));
        }
        Set<String> roles=Set.of("TEACHER","LEADER","BP","CAT","BLOCK");RelationRule optional=new RelationRule(false,roles,false,false);
        List<Person> people=List.of(person("P1","001",Set.of("TEACHER"),true),
                new Person("P2","001",Set.of("001","002"),null,null,Set.of("LEADER","BP"),true,Map.of("LEADER",Set.of("001"),"BP",Set.of("002"))),
                person("P3","001",Set.of("CAT","BLOCK"),true),person("P4","001",Set.of("TEACHER"),true),
                person("P5","001",Set.of("TEACHER"),true),person("P6","001",Set.of("TEACHER"),false),
                person("P7","011",Set.of("TEACHER"),true),person("P8","001",Set.of("CAT"),true));
        List<AccountBinding> bindings=List.of(new AccountBinding(accounts.get("teacher"),"P1",true),new AccountBinding(accounts.get("combined"),"P2",true),
                new AccountBinding(accounts.get("denied"),"P3",true),new AccountBinding(accounts.get("disabled"),"P4",true),
                new AccountBinding(accounts.get("binding-off"),"P5",false),new AccountBinding(accounts.get("person-off"),"P6",true),
                new AccountBinding(accounts.get("org-off"),"P7",true),new AccountBinding(accounts.get("parent-only"),"P8",true));
        List<Grant> grants=List.of(new Grant("TEACHER_READ","TEACHER","delivery.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()),
                new Grant("LEADER_CATALOG","LEADER","catalog.manage",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()),
                new Grant("LEADER_APPROVAL","LEADER","approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()),
                new Grant("BP_VERIFY","BP","delivery.verify",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()),
                new Grant("BP_APPROVAL","BP","approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()),
                new Grant("CAT_READ","CAT","catalog.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()),
                new Grant("CAT_MANAGE","CAT","catalog.manage",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()),
                new Grant("BLOCK_MANAGE","BLOCK","catalog.manage",Action.HANDLE,Effect.DENY,Scope.NAMED_ORGS,Set.of("001")));
        Configuration configuration=new Configuration("synthetic-access-v1",new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),roles,
                List.of(new Organization("001",null,true),new Organization("002",null,true),new Organization("003","001",true),new Organization("010",null,false),new Organization("011","010",true)),
                people,roles.stream().sorted().map(role->new RoleRelations(role,optional,optional)).toList(),bindings,grants);
        Files.writeString(root.resolve("configuration.json"),Json.write(OrganizationAccessStore.toMap(configuration)));
        if(!Boolean.getBoolean("integration.account.permissions.empty")){
            String token=Auth.login("admin",password);OrganizationAccessStore.publish(Auth.get(token),null,configuration);Auth.revokeUserSessions(1);
            Db.exec("UPDATE users SET status=0 WHERE username='synthetic-access-disabled'");
        }
        // An unrelated teacher makes accidental role/business updates visible in whole-table checks.
        Db.exec("INSERT INTO teachers(name,status,teacher_level) VALUES('SYNTHETIC ACCESS UNRELATED TEACHER','待完善','讲师')");
        Files.writeString(root.resolve("fixture.json"),Json.write(Map.of("accounts",accounts,"organizations",List.of("001","002","003","010","011"),"synthetic",true,"configuration_version","synthetic-access-v1")));
    }
    private static Person person(String code,String organization,Set<String> roles,boolean enabled){return new Person(code,organization,Set.of(),null,null,roles,enabled);}
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
            case "session-counts":{
                var field=Auth.class.getDeclaredField("SESSIONS");field.setAccessible(true);Map<String,Integer> counts=new TreeMap<>();
                for(Auth.Session session:((Map<String,Auth.Session>)field.get(null)).values())counts.merge(Long.toString(session.uid),1,Integer::sum);return counts;
            }
            case "disable-target":Db.exec("UPDATE users SET status=0 WHERE username='synthetic-access-disabled'");break;
            case "enable-target":Db.exec("UPDATE users SET status=1 WHERE username='synthetic-access-disabled'");break;
            case "downgrade-admin":Db.exec("UPDATE users SET role='manager' WHERE id=1");break;
            case "restore-admin":Db.exec("UPDATE users SET role='admin',status=1 WHERE id=1");break;
            case "disable-admin":Db.exec("UPDATE users SET status=0 WHERE id=1");break;
            case "revoke-admin":Auth.revokeUserSessions(1);break;
            case "configuration-unavailable":Db.exec("ALTER TABLE organization_access_config RENAME TO synthetic_account_access_unavailable");break;
            case "configuration-available":Db.exec("ALTER TABLE synthetic_account_access_unavailable RENAME TO organization_access_config");break;
            case "corrupt-configuration":
                savedConfiguration=Db.one("SELECT payload FROM organization_access_config WHERE active_slot=1").get("payload").toString();
                Db.exec("UPDATE organization_access_config SET payload='{}' WHERE active_slot=1");break;
            case "restore-configuration":
                if(savedConfiguration==null)throw new IllegalStateException("No saved configuration");Db.exec("UPDATE organization_access_config SET payload=? WHERE active_slot=1",savedConfiguration);break;
            default:throw new IllegalArgumentException("Unknown fixed synthetic control");
        }return Map.of();
    }
    @SuppressWarnings("unchecked") private static void controls(Path root){String last="";while(!Thread.currentThread().isInterrupted())try{
        Path file=root.resolve("control-request.json");if(Files.exists(file)){var request=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(request.get("nonce"),"");if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")){
            Map<String,Object> result;synchronized(Api.MUTATION_LOCK){result=Map.of("nonce",nonce,"ok",true,"data",operation(Objects.toString(request.get("operation"),"")));}
            Path tmp=root.resolve("control-result.next");Files.writeString(tmp,Json.write(result));Files.move(tmp,root.resolve("control-result.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);last=nonce;
        }}Thread.sleep(20);
    }catch(InterruptedException done){Thread.currentThread().interrupt();return;}catch(Exception error){throw new RuntimeException("Synthetic permission-inspection control failed",error);}}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing fixture property: "+name);return value;}
}
