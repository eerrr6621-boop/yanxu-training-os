package com.training;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.training.OrganizationAccountImportSource.*;

/** Starts unmodified Main in child JVMs: all fixtures/data/ports are private, synthetic and temporary. */
public class OrganizationAccountImportHostTest {
    private static final String PASS = "Synthetic-Host-Password-Only";
    private static final String BASE = "/api/organization/account-import";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static int checks;
    private static Path root;
    private record Reply(int status, Map<String,Object> json, String raw, String cache) {
        Map<String,Object> data() { return obj(json.get("data")); }
    }
    public static void main(String[] args) throws Exception {
        root=Path.of(args[0]).toRealPath();
        check(root.getFileName().toString().startsWith("yanxu-m01-import-host."), "dedicated temporary root");
        Config fixture=OrganizationAccountImportSourceTest.fixture(root.resolve("sources"));
        Map<String,Object> manifest=manifest(fixture);
        Path pinned=root.resolve("manifest.json"); write(pinned,manifest);
        String digest=sha(Files.readAllBytes(pinned));
        parserChecks(manifest,pinned,digest);
        Path data=root.resolve("data");
        long linked, disposable; String viewerToken;
        try(Server server=new Server(data,null,null)) {
            String admin=server.login("admin");
            check(server.get("/api/me",admin).status==200,"normal system available without configuration");
            privateError(server.get(BASE+"/preview",admin),503,"unconfigured import");
            privateError(server.request(BASE+"/commit",null,"POST","invalid","text/plain"),401,"auth before body without config");
            linked=server.create(admin,"synthetic-viewer","viewer"); disposable=server.create(admin,"synthetic-disposable","viewer");
            viewerToken=server.login("synthetic-viewer");
            privateError(server.request(BASE+"/commit",viewerToken,"POST","invalid","text/plain"),403,"nonadmin before body/config");
        }
        try(Connection db=connection(data)) {
            check(count(db,"organization_account_import_people")==0 && count(db,"organization_account_import_batches")==0,"startup only empty import tables");
            check(scalar(db,"SELECT revision FROM organization_account_import_state WHERE singleton=1")==0,"startup revision zero");
            check(count(db,"demands")==0 && count(db,"teachers")==0,"no sample business seeds");
        }
        try(Server server=new Server(data,pinned,"0".repeat(64))) {
            String admin=server.login("admin"), viewer=server.login("synthetic-viewer");
            privateError(server.get(BASE+"/preview",admin),503,"wrong manifest hash");
            privateError(server.get(BASE+"/preview",viewer),403,"wrong hash remains private");
            check(server.get("/api/users",admin).status==200,"bad manifest cannot stop ordinary users route");
        }
        Path malformed=root.resolve("malformed.json");Files.writeString(malformed,"{\"private-field\":");
        try(Server server=new Server(data,malformed,sha(Files.readAllBytes(malformed)))) {
            privateError(server.get(BASE+"/preview",server.login("admin")),503,"malformed pinned manifest");
            check(server.get("/api/me",server.login("admin")).status==200,"malformed config leaves main running");
        }
        Map<String,Object> wrong=copy(manifest); obj(obj(wrong.get("pins")).get("candidates")).put("sha256","0".repeat(64));
        Path wrongPins=root.resolve("wrong-pins.json");write(wrongPins,wrong);
        try(Server server=new Server(data,wrongPins,sha(Files.readAllBytes(wrongPins)))) {
            privateError(server.get(BASE+"/preview",server.login("admin")),503,"valid manifest with invalid source pins");
        }
        Map<String,Object> receipt, committedBody;
        try(Server server=new Server(data,pinned,digest)) {
            String admin=server.login("admin"), viewer=server.login("synthetic-viewer");
            privateError(server.get(BASE+"/preview",null),401,"anonymous preview");
            privateError(server.get(BASE+"/accounts/"+linked,viewer),403,"nonadmin account lookup");
            String expired=server.login("admin");check(server.post("/api/logout",expired,m()).status==200,"logout synthetic session");
            privateError(server.get(BASE+"/preview",expired),401,"revoked session");
            privateError(server.request(BASE+"/commit",expired,"POST","x".repeat(1048577),"text/plain"),401,"revoked before size/type error");
            check(server.request(BASE+"/commit",admin,"POST","{}","text/plain").status==415,"original content type boundary");
            check(server.request(BASE+"/commit",admin,"POST","x".repeat(1048577),"application/json").status==413,"original one MiB boundary");
            check(server.post(BASE+"/preview",admin,m()).status==405,"original import method restriction");
            check(server.get(BASE+"/preview?manifest=/injected",admin).status==400,"query source injection refused");
            Reply preview=server.get(BASE+"/preview",admin);check(preview.status==200 && "no-store".equals(preview.cache),"preview through Main/Api is private");
            Map<String,Object> first=preview.data();check(list(first.get("rows")).size()==7,"complete candidate preview");
            check(((Number)obj(first.get("summary")).get("historicalExcluded")).intValue()==2,"historical rows excluded");
            check(!preview.raw.contains(root.toString()) && !preview.raw.contains("000SYN"),"no source paths or HR IDs in preview");
            Map<String,Object> body=decisions(first,linked,server.get(BASE+"/accounts/"+linked,admin).data());
            Map<String,Object> injection=copy(body);injection.put("manifest",pinned.toString());
            check(server.post(BASE+"/commit",admin,injection).status==400,"body source injection refused");
            check(sha(Files.readAllBytes(pinned)).equals(digest),"HTTP cannot rewrite trusted manifest");
            Files.writeString(pinned,"changed manifest");
            privateError(server.post(BASE+"/commit",admin,body),503,"manifest changed after preview");
            privateError(server.get(BASE+"/accounts/"+linked,admin),503,"changed manifest blocks lookup too");
            write(pinned,manifest);check(server.post(BASE+"/commit",admin,body).status==409,"restored manifest cannot revive review token");
            first=server.get(BASE+"/preview",admin).data();body=decisions(first,linked,server.get(BASE+"/accounts/"+linked,admin).data());
            check(server.post("/api/users",admin,m("id",disposable,"username","synthetic-disposable","name","Synthetic changed name","role","viewer","status",1)).status==200,"ordinary user update remains available");
            check(server.post(BASE+"/commit",admin,body).status==409,"account change invalidates old review");
            first=server.get(BASE+"/preview",admin).data();
            Reply lookup=server.get(BASE+"/accounts/"+linked,admin);check(lookup.status==200 && Boolean.TRUE.equals(lookup.data().get("available")),"explicit existing ID lookup");
            committedBody=decisions(first,linked,lookup.data());
            Reply result=server.post(BASE+"/commit",admin,committedBody);check(result.status==200,"whole batch committed through real API");receipt=result.data();
            check(Boolean.FALSE.equals(receipt.get("permissionsPublished")) && Boolean.FALSE.equals(receipt.get("accountsActivated")),"no activation or permission publication");
            Reply replay=server.post(BASE+"/commit",admin,committedBody);check(replay.status==200 && Boolean.TRUE.equals(replay.data().get("replayed")),"same command replays");
            check(Json.write(replay.data().get("rows")).equals(Json.write(receipt.get("rows"))),"stable account/person identities on replay");
            List<Object> users=list(server.get("/api/users",admin).json.get("data"));
            check(users.size()==9,"exactly six pending users plus original three");
            check(users.stream().map(OrganizationAccountImportHostTest::obj).filter(u->String.valueOf(u.get("username")).startsWith("pending_")).allMatch(u->((Number)u.get("status")).intValue()==0 && "viewer".equals(u.get("role"))),"new users remain disabled viewers");
            check(server.login("synthetic-viewer")!=null,"linked password/status unchanged");
            for(Object row:list(receipt.get("rows"))) check(server.post("/api/users/delete",admin,m("id",obj(row).get("accountId"))).status==409,"imported account deletion returns 409");
            check(server.post("/api/users/delete",admin,m("id",disposable)).status==200,"unreferenced ordinary account deletion unchanged");
            check(server.post("/api/users",admin,m("id",linked,"username","synthetic-viewer","name","Synthetic viewer","role","viewer","status",0)).status==200,"linked account can still be disabled");
            privateError(server.get(BASE+"/preview",viewer),401,"disable revokes real session");
            check(server.post("/api/users",admin,m("id",linked,"username","synthetic-viewer","name","Synthetic viewer","role","viewer","status",1)).status==200,"ordinary re-enable policy unchanged");
            long another=server.create(admin,"synthetic-admin","admin");String downgraded=server.login("synthetic-admin");
            check(server.post("/api/users",admin,m("id",another,"username","synthetic-admin","name","Synthetic admin","role","viewer","status",1)).status==200,"ordinary role update unchanged");
            privateError(server.get(BASE+"/preview",downgraded),401,"downgrade revokes session");
        }
        try(Server server=new Server(data,pinned,digest)) {
            String admin=server.login("admin"); Reply persisted=server.get(BASE+"/preview",admin);
            check(persisted.status==200 && Boolean.TRUE.equals(persisted.data().get("imported")),"restart recovers persisted receipt");
            check(Json.write(obj(persisted.data().get("receipt")).get("rows")).equals(Json.write(receipt.get("rows"))),"restart preserves identity mappings");
            check(server.post(BASE+"/commit",admin,committedBody).status==409,"old process review tokens cannot be reused");
            check(server.post("/api/users/delete",admin,m("id",linked)).status==409,"deletion protection survives restart");
        }
        try(Connection db=connection(data)) {
            check(count(db,"organization_account_import_people")==7 && count(db,"organization_account_import_batches")==1,"durable single batch");
            check(scalar(db,"SELECT COUNT(*) FROM users WHERE username LIKE 'pending_%' AND password IS NULL AND status=0")==6,"no passwords generated for imported users");
        }
        rollbackCheck(root.resolve("rollback"),pinned,digest);
        System.out.println("OrganizationAccountImportHost: "+checks+" checks passed (real Main/Api child JVMs, isolated synthetic H2). ");
    }

    private static void rollbackCheck(Path data,Path manifest,String digest)throws Exception {
        try(Server server=new Server(data,manifest,digest)) {check(server.get("/api/me",server.login("admin")).status==200,"rollback fixture initialized by real Main");}
        try(Connection db=connection(data);Statement s=db.createStatement()) {s.execute("ALTER TABLE users ADD CONSTRAINT synthetic_import_failure CHECK(name <> 'SYNTHETIC PRIVATE 6')");}
        try(Server server=new Server(data,manifest,digest)) {
            String admin=server.login("admin");Map<String,Object> preview=server.get(BASE+"/preview",admin).data();
            Reply failed=server.post(BASE+"/commit",admin,decisions(preview,0,null));privateError(failed,500,"mid-batch SQL failure");
            check(list(server.get("/api/users",admin).json.get("data")).size()==1,"failed commit leaves no partial users");
            check(Boolean.FALSE.equals(server.get(BASE+"/preview",admin).data().get("imported")),"failed commit not marked imported");
        }
        try(Connection db=connection(data)) {
            check(count(db,"organization_account_import_people")==0 && count(db,"organization_account_import_batches")==0,"failed commit leaves no people/receipt");
            check(scalar(db,"SELECT revision FROM organization_account_import_state WHERE singleton=1")==0,"failed commit rolls back revision");
        }
    }
    private static void parserChecks(Map<String,Object> manifest,Path pinned,String digest)throws Exception {
        check(OrganizationAccountImportHost.readConfig(pinned.toString(),digest).originals().size()==3,"strict manifest accepts exact pins");
        bad(null,digest,"missing path");bad(pinned.toString(),null,"missing digest");bad("relative.json",digest,"relative manifest");bad(pinned.toString(),digest.toUpperCase(Locale.ROOT),"noncanonical digest");
        for(String mutation:List.of("extra","missing","wrong-schema","duplicate-pin","relative-pin","bad-hash","wrong-originals","extra-pin-field","wrong-usage")) {
            Map<String,Object> value=copy(manifest);
            switch(mutation) {
                case "extra" -> value.put("enable",true);
                case "missing" -> value.remove("usage");
                case "wrong-schema" -> value.put("schema","unknown");
                case "duplicate-pin" -> obj(value.get("pins")).put("candidatePolicy",obj(value.get("pins")).get("candidates"));
                case "relative-pin" -> obj(obj(value.get("pins")).get("candidates")).put("path","candidate.json");
                case "bad-hash" -> obj(obj(value.get("pins")).get("audit")).put("sha256","invalid");
                case "wrong-originals" -> list(value.get("originals")).remove(0);
                case "extra-pin-field" -> obj(obj(value.get("pins")).get("audit")).put("approved",true);
                case "wrong-usage" -> value.put("usage",true);
            }
            Path file=root.resolve("parser-"+mutation+".json");write(file,value);bad(file.toString(),sha(Files.readAllBytes(file)),mutation);
        }
        Path duplicate=root.resolve("duplicate.json");Files.writeString(duplicate,"{\"private-test-key\":0,\"private-test-key\":1}");bad(duplicate.toString(),sha(Files.readAllBytes(duplicate)),"duplicate keys sanitized");
        Path invalid=root.resolve("invalid-utf8.json");Files.write(invalid,new byte[]{(byte)0xff});bad(invalid.toString(),sha(Files.readAllBytes(invalid)),"invalid UTF8");
        Path huge=root.resolve("large.json");Files.writeString(huge,"x".repeat(65537));bad(huge.toString(),sha(Files.readAllBytes(huge)),"bounded manifest read");
        Path symlink=root.resolve("linked.json");Files.createSymbolicLink(symlink,pinned);bad(symlink.toString(),digest,"manifest symlink refused");
    }
    private static void bad(String path,String digest,String label)throws Exception {
        try {OrganizationAccountImportHost.readConfig(path,digest);throw new AssertionError(label);}
        catch(IllegalArgumentException e){check(!e.getMessage().contains(root.toString())&&!e.getMessage().contains("private-test-key"),label);}
    }
    private static Map<String,Object> manifest(Config c) {return m("schema","M01-SERVER-PIN-MANIFEST-v1","batchKey",c.batchKey(),"usage","Synthetic server startup only",
        "pins",m("candidates",pin(c.candidates()),"candidatePolicy",pin(c.candidatePolicy()),"roleSource",pin(c.roleSource()),"authorization",pin(c.authorization()),"audit",pin(c.audit()),"regionReference",pin(c.regionReference()),"scopeDecision",pin(c.scopeDecision()),"preparedPreview",pin(c.preparedPreview())),"originals",new ArrayList<>(c.originals().stream().map(OrganizationAccountImportHostTest::pin).toList()));}
    private static Map<String,Object> pin(FilePin p){return m("path",p.path().toString(),"sha256",p.sha256());}
    private static Map<String,Object> decisions(Map<String,Object> preview,long linked,Map<String,Object> account) {
        List<Object> choices=new ArrayList<>();boolean first=true;
        for(Object row:list(preview.get("rows"))){boolean link=first&&linked>0;first=false;choices.add(m("reference",obj(row).get("reference"),"action",link?"LINK_EXISTING":"CREATE_PENDING","accountId",link?linked:null,"accountProof",link?account.get("proof"):null,"reviewed",true));}
        return m("expectedRevision",preview.get("revision"),"sourceFingerprint",preview.get("sourceFingerprint"),"reviewToken",preview.get("reviewToken"),"decisions",choices);
    }
    private static final class Server implements AutoCloseable {
        final Process process;final String base;final Path log;final Thread shutdown;
        Server(Path data,Path manifest,String digest)throws Exception {
            Files.createDirectories(data);int port;try(ServerSocket socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}base="http://127.0.0.1:"+port;
            log=Files.createTempFile(root,"main-",".log");
            List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Ddata.dir="+data,"-Dbootstrap.demo=false","-Dbootstrap.admin.password="+PASS,"-Dbind.address=127.0.0.1","-Dhttp.workers=4"));
            if(manifest!=null)command.add("-D"+OrganizationAccountImportHost.MANIFEST_PROPERTY+"="+manifest);
            if(digest!=null)command.add("-D"+OrganizationAccountImportHost.SHA256_PROPERTY+"="+digest);
            command.addAll(List.of("-cp",System.getProperty("java.class.path"),"com.training.Main",String.valueOf(port)));
            process=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            shutdown=new Thread(process::destroyForcibly);Runtime.getRuntime().addShutdownHook(shutdown);
            boolean ready=false;
            try {for(int attempt=0;attempt<100&&process.isAlive();attempt++){try{if(get("/api/me",null).status==401){ready=true;break;}}catch(Exception ignored){}Thread.sleep(100);}if(!ready)throw new AssertionError("Synthetic Main did not become ready: "+Files.readString(log).replace(root.toString(),"<temporary>").replace(PASS,"<synthetic-password>"));}
            catch(Exception|Error failure){close();throw failure;}
        }
        Reply request(String path,String token,String method,String body,String type)throws Exception {
            HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15));
            if(token!=null)request.header("Cookie","yx_session="+token);
            if(type!=null)request.header("Content-Type",type);
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> reply=CLIENT.send(request.build(),HttpResponse.BodyHandlers.ofString());
            return new Reply(reply.statusCode(),obj(Json.parse(reply.body())),reply.body(),reply.headers().firstValue("Cache-Control").orElse(""));
        }
        Reply get(String path,String token)throws Exception{return request(path,token,"GET",null,null);}
        Reply post(String path,String token,Object body)throws Exception{return request(path,token,"POST",Json.write(body),"application/json");}
        String login(String user)throws Exception{Reply r=post("/api/login",null,m("username",user,"password",PASS));if(r.status!=200)throw new AssertionError("Synthetic login failed");return (String)r.data().get("token");}
        long create(String token,String username,String role)throws Exception{Reply r=post("/api/users",token,m("username",username,"name","Synthetic account","role",role,"status",1,"password",PASS));check(r.status==200,"ordinary synthetic user creation");return ((Number)r.json.get("data")).longValue();}
        public void close()throws Exception {process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();if(!process.waitFor(5,TimeUnit.SECONDS))throw new AssertionError("Synthetic Main did not stop");}Runtime.getRuntime().removeShutdownHook(shutdown);String text=Files.readString(log);check(!text.contains(root.resolve("sources").toString())&&!text.contains("SYNTHETIC PRIVATE")&&!text.contains("private-test-key"),"no source identities/paths in Main logs");}
    }
    private static void privateError(Reply reply,int expected,String label){check(reply.status==expected,label);check(!reply.raw.contains(root.toString())&&!reply.raw.contains("SYNTHETIC PRIVATE")&&!reply.raw.contains("sha256")&&!reply.raw.contains("private-field"),label+" is sanitized");}
    private static Connection connection(Path dir)throws Exception{return DriverManager.getConnection("jdbc:h2:"+dir.resolve("training")+";DB_CLOSE_DELAY=0","sa","");}
    private static long count(Connection db,String table)throws Exception{return scalar(db,"SELECT COUNT(*) FROM "+table);}
    private static long scalar(Connection db,String sql)throws Exception{try(Statement s=db.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    private static void write(Path p,Object value)throws Exception{Files.writeString(p,Json.write(value));}
    private static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static Map<String,Object> copy(Map<String,Object> value){return obj(Json.parse(Json.write(value)));}
    @SuppressWarnings("unchecked")private static Map<String,Object> obj(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked")private static List<Object> list(Object value){return (List<Object>)value;}
    private static Map<String,Object> m(Object...values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    private static void check(boolean passed,String name){if(!passed)throw new AssertionError(name);checks++;}
}
