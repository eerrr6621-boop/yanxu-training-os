package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Scripts-only isolated synthetic setup; the actual exercise uses Main and the normal HTTP handlers. */
public final class IntegrationCourseMaintenanceHttpFixture {
    private static Object teacherLevel;
    private static String savedPayload;
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected port or inspect");
        Path root=Path.of(required("integration.course.maintenance.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();
        String password=required("bootstrap.admin.password");
        if(!root.getFileName().toString().startsWith("yanxu-course-maintenance-http-")||!data.getParent().equals(root)
                ||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")
                ||!password.matches("[a-f0-9]{48}"))throw new IllegalArgumentException("Fresh synthetic loopback fixture required");
        if(args[0].equals("inspect")){System.out.println(Json.write(snapshot()));Db.get().close();return;}
        if(!Boolean.getBoolean("integration.course.maintenance.resume")){
            try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh empty fixture directory required");}
            Db.init();if(Db.count("users")!=1||OrganizationAccessStore.configuration()!=null||Db.count("m04_catalog_scopes")!=0)throw new IllegalStateException("Unexpected fixture seed");
            setup(root,password);
        }else Db.init();
        Thread control=new Thread(()->controls(root),"synthetic-course-maintenance-controls");control.setDaemon(true);control.start();Main.main(args);
    }
    private static void setup(Path root,String password)throws Exception {
        Map<String,Long> accounts=new LinkedHashMap<>();String hash=Auth.hash(password);
        for(String name:List.of("manager","maintainer","reader","colleague","other","unbound"))accounts.put(name,
                Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)","synthetic-course-"+name,hash,"SYNTHETIC COURSE "+name,Set.of("reader","unbound").contains(name)?"manager":"viewer"));
        Set<String> roles=Set.of("FULL","WRITE","READ","OTHER");RelationRule optional=new RelationRule(false,roles,false,false);
        List<Grant> grants=new ArrayList<>();for(String role:List.of("FULL","WRITE","READ")){
            if(!role.equals("WRITE"))grants.add(new Grant(role+"_READ",role,"catalog.read",Action.VIEW,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001","002","004")));
            if(!role.equals("READ"))grants.add(new Grant(role+"_WRITE",role,"catalog.manage",Action.HANDLE,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001","002","004")));
        }
        grants.add(new Grant("OTHER_READ","OTHER","catalog.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));grants.add(new Grant("OTHER_WRITE","OTHER","catalog.manage",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        Configuration config=new Configuration("synthetic-course-maintenance-v1",new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),roles,
                List.of(new Organization("001",null,true),new Organization("002",null,true),new Organization("003",null,true),new Organization("004",null,true)),
                List.of(person("P1","001","FULL"),person("P2","001","WRITE"),person("P3","001","READ"),person("P4","001","FULL"),person("P5","003","OTHER")),
                roles.stream().sorted().map(r->new RoleRelations(r,optional,optional)).toList(),
                List.of(new AccountBinding(accounts.get("manager"),"P1",true),new AccountBinding(accounts.get("maintainer"),"P2",true),new AccountBinding(accounts.get("reader"),"P3",true),new AccountBinding(accounts.get("colleague"),"P4",true),new AccountBinding(accounts.get("other"),"P5",true)),grants);
        String adminToken=Auth.login("admin",password);OrganizationAccessStore.publish(Auth.get(adminToken),null,config);
        String managerToken=Auth.login("synthetic-course-manager",password),otherToken=Auth.login("synthetic-course-other",password);Auth.Session manager=Auth.get(managerToken);
        long retained=((Number)CourseCatalogIntegration.registerScope(manager,"001").get("scope_id")).longValue(),empty=((Number)CourseCatalogIntegration.registerScope(manager,"002").get("scope_id")).longValue(),other=((Number)CourseCatalogIntegration.registerScope(Auth.get(otherToken),"003").get("scope_id")).longValue();
        long emptyCourses=((Number)CourseCatalogIntegration.registerScope(manager,"004").get("scope_id")).longValue();
        long first=Db.insert("INSERT INTO teachers(name,status,teacher_level,base_province,base_city) VALUES(?,?,?,?,?)","SYNTHETIC COURSE TEACHER A","在库","讲师","浙江","杭州");
        long second=Db.insert("INSERT INTO teachers(name,status,teacher_level,base_province,base_city) VALUES(?,?,?,?,?)","SYNTHETIC COURSE TEACHER B","在库","高级讲师","上海","上海");
        Map<String,Object> catalog=Json.parseMap("""
          {"schema_version":"m04_catalog_v1","catalog_version":"synthetic-original-v1",
           "courses":[{"course_code":"SYN-001","course_name":"合成课程甲","active":true},{"course_code":"SYN-002","course_name":"合成课程乙","active":true}],
           "teachers":[{"teacher_code":"SYN-T1","teacher_level":"讲师","city":"杭州"},{"teacher_code":"SYN-T2","teacher_level":"高级讲师","city":"上海"}],
           "certifications":[{"teacher_code":"SYN-T1","course_code":"SYN-001","status":"certified","source_ref":"SYNTHETIC-EVIDENCE-1","valid_from":"2026-01-01","valid_to":"2026-12-31"},{"teacher_code":"SYN-T2","course_code":"SYN-002","status":"unknown","source_ref":"","valid_from":"","valid_to":""}]}
          """);
        Map<String,Object> preview=call(managerToken,manager,retained,"preview",Map.of("expected_version",0L,"catalog",catalog,"bindings",List.of(Map.of("teacher_code","SYN-T1","teacher_id",first),Map.of("teacher_code","SYN-T2","teacher_id",second)),"change_comment","SYNTHETIC original catalog fixture"));
        if(!Boolean.TRUE.equals(preview.get("ready")))throw new IllegalStateException("Invalid synthetic original catalog");
        Map<String,Object> confirmation=call(managerToken,manager,retained,"confirm",Map.of("expected_version",0L,"batch_id",preview.get("batch_id"),"confirm",true));
        if(!"CONFIRMED".equals(confirmation.get("status")))throw new IllegalStateException("Synthetic catalog was not confirmed");
        Map<String,Object> noCourses=new LinkedHashMap<>(catalog);noCourses.put("catalog_version","synthetic-emptycourses-v1");noCourses.put("courses",List.of());noCourses.put("certifications",List.of());
        Map<String,Object> emptyPreview=call(managerToken,manager,emptyCourses,"preview",Map.of("expected_version",0L,"catalog",noCourses,"bindings",List.of(Map.of("teacher_code","SYN-T1","teacher_id",first),Map.of("teacher_code","SYN-T2","teacher_id",second)),"change_comment","SYNTHETIC coded teachers before first course"));
        if(!Boolean.TRUE.equals(emptyPreview.get("ready")))throw new IllegalStateException("Invalid synthetic empty-courses catalog");
        call(managerToken,manager,emptyCourses,"confirm",Map.of("expected_version",0L,"batch_id",emptyPreview.get("batch_id"),"confirm",true));
        Files.writeString(root.resolve("fixture.json"),Json.write(Map.of("retained_scope",retained,"empty_scope",empty,"empty_courses_scope",emptyCourses,"other_scope",other,"teacher_ids",List.of(first,second),"accounts",accounts,"synthetic",true)));
    }
    private static Person person(String code,String org,String role){return new Person(code,org,Set.of(),null,null,Set.of(role),true);}
    @SuppressWarnings("unchecked") private static Map<String,Object> call(String token,Auth.Session session,long scope,String action,Map<String,Object> body)throws Exception {
        Exchange ex=new Exchange("/api/course-catalog/scopes/"+scope+"/"+action,token,body);if(!CourseCatalogIntegration.handle(ex,session))throw new IllegalStateException("Expected original catalog handler");
        Object response=ex.getAttribute("com.training.Api.response");var field=response.getClass().getDeclaredField("body");field.setAccessible(true);var envelope=(Map<String,Object>)Json.parse(new String((byte[])field.get(response),StandardCharsets.UTF_8));return (Map<String,Object>)envelope.get("data");
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static Map<String,Object> snapshot()throws Exception {
        Map<String,Object> result=new TreeMap<>();for(var table:Db.query("SELECT TABLE_NAME AS name FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
            String name=table.get("name").toString();if(!name.matches("[A-Z0-9_]+"))throw new IllegalStateException("Unexpected table");List<String> rows=new ArrayList<>();for(var row:Db.query("SELECT * FROM "+name))rows.add(Json.write(new TreeMap<>(row)));Collections.sort(rows);result.put(name,Map.of("count",rows.size(),"sha256",hash(Json.write(rows).getBytes(StandardCharsets.UTF_8))));
        }return result;
    }
    @SuppressWarnings("unchecked") private static Object operation(String operation)throws Exception {
        long account=((Number)Db.one("SELECT id FROM users WHERE username='synthetic-course-maintainer'").get("id")).longValue();
        long teacher=((Number)Db.one("SELECT id FROM teachers WHERE name='SYNTHETIC COURSE TEACHER A'").get("id")).longValue();
        switch(operation){
            case "snapshot":return snapshot();
            case "corrupt-certification":
                var revision=Db.one("SELECT r.payload FROM m04_catalog_revisions r JOIN m04_catalog_scopes s ON r.scope_id=s.scope_id AND r.version=s.version WHERE s.organization_code='001'");savedPayload=revision.get("payload").toString();
                Map<String,Object> payload=(Map<String,Object>)Json.parse(savedPayload);((Map<String,Object>)((List<?>)payload.get("certifications")).get(0)).put("course_code","SYN-MISSING");
                Db.exec("UPDATE m04_catalog_revisions SET payload=? WHERE (scope_id,version) IN (SELECT scope_id,version FROM m04_catalog_scopes WHERE organization_code='001')",Json.write(payload));break;
            case "restore-certification":if(savedPayload==null)throw new IllegalStateException("No saved synthetic catalog");Db.exec("UPDATE m04_catalog_revisions SET payload=? WHERE (scope_id,version) IN (SELECT scope_id,version FROM m04_catalog_scopes WHERE organization_code='001')",savedPayload);break;
            case "revoke-maintainer":Auth.revokeUserSessions(account);break;
            case "teacher-change":teacherLevel=Db.one("SELECT teacher_level FROM teachers WHERE id=?",teacher).get("teacher_level");Db.exec("UPDATE teachers SET teacher_level='特级讲师' WHERE id=?",teacher);break;
            case "teacher-restore":if(teacherLevel==null)throw new IllegalStateException("No saved synthetic teacher level");Db.exec("UPDATE teachers SET teacher_level=? WHERE id=?",teacherLevel,teacher);break;
            default:throw new IllegalArgumentException("Unknown fixed synthetic operation");
        }return Map.of();
    }
    @SuppressWarnings("unchecked") private static void controls(Path root){String last="";while(!Thread.currentThread().isInterrupted())try{
        Path file=root.resolve("control-request.json");if(Files.exists(file)){var request=(Map<String,Object>)Json.parse(Files.readString(file));String nonce=Objects.toString(request.get("nonce"),"");if(!nonce.equals(last)&&nonce.matches("[a-f0-9-]{36}")){
            Map<String,Object> result;synchronized(Api.MUTATION_LOCK){result=Map.of("nonce",nonce,"ok",true,"data",operation(Objects.toString(request.get("operation"),"")));}Path tmp=root.resolve("control-result.next");Files.writeString(tmp,Json.write(result));Files.move(tmp,root.resolve("control-result.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);last=nonce;
        }}Thread.sleep(20);
    }catch(InterruptedException done){Thread.currentThread().interrupt();return;}catch(Exception error){throw new RuntimeException("Synthetic course-maintenance control failed",error);}}
    private static String required(String name){String value=System.getProperty(name,"");if(value.isBlank())throw new IllegalArgumentException("Missing fixture property: "+name);return value;}
    private static final class Exchange extends HttpExchange {
        private final URI uri;private final Headers request=new Headers(),response=new Headers();private final Map<String,Object> attributes=new HashMap<>();
        Exchange(String path,String token,Map<String,Object> body){uri=URI.create(path);request.set("X-Token",token);request.set("Content-Type","application/json");attributes.put("com.training.Api.body",body);}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return "POST";}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return InputStream.nullInputStream();}public OutputStream getResponseBody(){return OutputStream.nullOutputStream();}public void sendResponseHeaders(int code,long length){}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}public int getResponseCode(){return 0;}public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",1);}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String name){return attributes.get(name);}public void setAttribute(String name,Object value){attributes.put(name,value);}public void setStreams(InputStream in,OutputStream out){}public HttpPrincipal getPrincipal(){return null;}
    }
}
