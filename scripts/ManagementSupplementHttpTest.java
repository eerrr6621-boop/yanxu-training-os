package com.training;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.training.ManagementSupplementHttpFixture.*;

/** Runs real integrated Main/Api in child JVMs; all accounts, pins and H2 files are synthetic. */
public final class ManagementSupplementHttpTest {
    private static final String BASE="/api/organization/management-supplement";
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static Path root;
    private static int checks;
    private record Reply(int status,Map<String,Object> json,String text,String cache) {
        Map<String,Object> data(){return object(json.get("data"));}
    }
    public static void main(String[] args)throws Exception {
        root=Path.of(args[0]).toRealPath();
        if(!root.getFileName().toString().startsWith("yanxu-management-supplement-http-test."))throw new IllegalArgumentException("Dedicated synthetic root required");
        Map<String,Object> fixture=object(Json.parse(Files.readString(root.resolve("fixture.json"))));
        long linked=n(fixture.get("linkedId")),oldId=n(fixture.get("oldId"));
        Path manifest=root.resolve("manifest.json"),source=Path.of((String)fixture.get("sourceFile"));
        String digest=sha(manifest);parserChecks(manifest,digest);
        try(Server server=new Server(null,null)) {
            privateError(server.get(BASE+"/preview",null),401,"anonymous before disabled source");
            privateError(server.get(BASE+"/preview",server.login("admin")),503,"missing startup configuration");
        }
        Map<String,Object> committed,receipt;
        try(Server server=new Server(manifest,digest)) {
            String admin=server.login("admin"),viewer=server.login("synthetic-viewer");
            privateError(server.get(BASE+"/preview",null),401,"anonymous preview");
            privateError(server.get(BASE+"/accounts/"+linked,viewer),403,"viewer lookup");
            privateError(server.request(BASE+"/commit",null,"POST","x".repeat(4097).getBytes(StandardCharsets.UTF_8),"text/plain"),401,"authenticate before body/type/size");
            privateError(server.get(BASE+"/unknown",viewer),403,"authenticate before unknown operation");
            privateError(server.get(BASE+"/unknown",admin),404,"unknown operation");
            privateError(server.get(BASE+"/received",admin),404,"internal association has no HTTP route");
            privateError(server.post(BASE+"/preview",admin,map()),405,"wrong preview method");
            privateError(server.get(BASE+"/preview?path=private-injected",admin),400,"query rejected");
            privateError(server.get(BASE+"/%70review",admin),400,"encoded path rejected");
            for(String value:List.of("0","01","-1","9007199254740992","1.0","1/extra"))
                privateError(server.get(BASE+"/accounts/"+value,admin),400,"invalid explicit ID "+value);
            privateError(server.request(BASE+"/commit",admin,"POST",new byte[]{(byte)0xc3,0x28},"application/json"),400,"invalid UTF8");
            privateError(server.request(BASE+"/commit",admin,"POST","{}".getBytes(StandardCharsets.UTF_8),"text/plain"),415,"content type");
            privateError(server.request(BASE+"/commit",admin,"POST","x".repeat(4097).getBytes(StandardCharsets.UTF_8),"application/json"),413,"four KiB limit");
            privateError(server.post(BASE+"/commit",admin,List.of()),400,"array root rejected");
            privateError(server.request(BASE+"/commit",admin,"POST","{\"decisions\":[{\"private-field\":1,\"private-field\":2}]}".getBytes(StandardCharsets.UTF_8),"application/json"),400,"nested duplicate sanitized");
            String loggedOut=server.login("admin");check(server.post("/api/logout",loggedOut,map()).status==200,"real logout");
            privateError(server.post(BASE+"/commit",loggedOut,map()),401,"revoked session");
            Reply original=server.get("/api/organization/account-import/preview",admin);
            check(original.status==200 && ((List<?>)original.data().get("rows")).size()==7 && Boolean.TRUE.equals(original.data().get("imported")),"original manifest/receipt still served separately");
            Reply preview=server.get(BASE+"/preview",admin);privateSuccess(preview,"supplement preview");
            check(((List<?>)preview.data().get("rows")).size()==2,"exact two candidates");
            check(((List<?>)preview.data().get("branchCoverage")).isEmpty(),"no invented coverage");
            Reply occupied=server.get(BASE+"/accounts/"+oldId,admin);
            check(occupied.status==200 && Boolean.FALSE.equals(occupied.data().get("available")) && occupied.data().get("proof")==null,"old intake reserves account");
            Map<String,Object> lookup=server.get(BASE+"/accounts/"+linked,admin).data();
            check(Boolean.TRUE.equals(lookup.get("available")) && lookup.get("proof") instanceof String,"explicit available account proof");
            Map<String,Object> request=decisions(preview.data(),linked,lookup);
            Map<String,Object> injection=copy(request);injection.put("sourceNamespace","private-injected");
            privateError(server.post(BASE+"/commit",admin,injection),400,"browser cannot set namespace");
            byte[] manifestBytes=Files.readAllBytes(manifest);
            try {Files.writeString(manifest,"SYNTHETIC TAMPER");privateError(server.get(BASE+"/accounts/"+linked,admin),503,"manifest change blocks lookup");}
            finally {Files.write(manifest,manifestBytes);}
            privateError(server.post(BASE+"/commit",admin,request),409,"restored manifest cannot revive review");
            request=decisions(server.get(BASE+"/preview",admin).data(),linked,server.get(BASE+"/accounts/"+linked,admin).data());
            byte[] sourceBytes=Files.readAllBytes(source);
            try {Files.writeString(source,"SYNTHETIC TAMPER");privateError(server.get(BASE+"/accounts/"+linked,admin),503,"source change blocks lookup");}
            finally {Files.write(source,sourceBytes);}
            privateError(server.post(BASE+"/commit",admin,request),409,"restored source cannot revive review");
            request=decisions(server.get(BASE+"/preview",admin).data(),linked,server.get(BASE+"/accounts/"+linked,admin).data());
            privateError(server.post(BASE+"/commit",server.login("admin"),request),409,"review belongs to real session");
            committed=decisions(server.get(BASE+"/preview",admin).data(),linked,server.get(BASE+"/accounts/"+linked,admin).data());
            Reply saved=server.post(BASE+"/commit",admin,committed);privateSuccess(saved,"mixed CREATE/LINK commit");receipt=saved.data();
            check(n(object(receipt.get("summary")).get("createdPending"))==1 && n(object(receipt.get("summary")).get("linkedExisting"))==1,"one independent create and one explicit link");
            check(Boolean.FALSE.equals(receipt.get("accountsActivated")) && Boolean.FALSE.equals(receipt.get("permissionsPublished")),"intake does not activate or publish");
            Reply replay=server.post(BASE+"/commit",admin,committed);
            check(replay.status==200 && Boolean.TRUE.equals(replay.data().get("replayed")) && Json.write(replay.data().get("rows")).equals(Json.write(receipt.get("rows"))),"same command replay preserves identities");
            check(server.login("synthetic-existing")!=null,"LINK keeps existing login credential and enabled state");
        }
        try(Server server=new Server(manifest,digest)) {
            String admin=server.login("admin");Reply persisted=server.get(BASE+"/preview",admin);privateSuccess(persisted,"restart reads receipt");
            check(Boolean.TRUE.equals(persisted.data().get("imported")) && Json.write(object(persisted.data().get("receipt")).get("rows")).equals(Json.write(receipt.get("rows"))),"durable supplement identities");
            privateError(server.post(BASE+"/commit",admin,committed),409,"prior process token rejected");
        }
        try(Connection db=DriverManager.getConnection("jdbc:h2:"+root.resolve("data/training")+";DB_CLOSE_DELAY=0","sa","")) {
            check(Files.readString(root.resolve("protected-before.json")).equals(protectedState(db,(String)fixture.get("oldBatch"),linked)),"original intake/users/receipt, linked password/role/status, config and teachers unchanged");
            check(scalar(db,"SELECT COUNT(*) FROM organization_account_import_people")==9,"seven original plus exactly two supplement associations");
            check(scalar(db,"SELECT COUNT(*) FROM organization_account_import_batches")==2,"original plus independent supplement receipt");
            check(scalar(db,"SELECT COUNT(*) FROM users WHERE username LIKE 'pending_%' AND password IS NULL AND role='viewer' AND status=0")==8,"all CREATE accounts remain NULL/disabled/viewer");
        }
        System.out.println("ManagementSupplementHttp: "+checks+" checks passed (real integrated Main/Api; private synthetic H2/files).");
    }

    private static void parserChecks(Path manifest,String digest)throws Exception {
        var config=OrganizationManagementSupplementHttp.readConfig(manifest.toString(),digest);
        check(config.groupsOriginal()!=null,"exact manifest accepted");
        check(OrganizationManagementSupplementHttp.parseBody("{\"decisions\":[{\"reviewed\":true,\"accountId\":12,\"accountProof\":null}]}".getBytes(StandardCharsets.UTF_8)).get("decisions") instanceof List<?>,"nested JSON types retained");
        Map<String,Object> original=object(Json.parse(Files.readString(manifest)));
        for(String mutation:List.of("schema","extra","missing","pin-key","relative","same-file","hash")) {
            Map<String,Object> changed=copy(original);Map<String,Object> pins=object(changed.get("pins"));
            switch(mutation) {
                case "schema" -> changed.put("schema","other");case "extra" -> changed.put("role","admin");case "missing" -> changed.remove("batchKey");
                case "pin-key" -> object(pins.get("decision")).put("approved",true);
                case "relative" -> object(pins.get("projection")).put("path","private-relative");
                case "same-file" -> pins.put("decision",pins.get("projection"));
                default -> object(pins.get("projection")).put("sha256","X".repeat(64));
            }
            Path file=root.resolve("parser-"+mutation+".json");Files.writeString(file,Json.write(changed));badManifest(file,sha(file),mutation);
        }
        Path link=root.resolve("manifest-link.json");Files.createSymbolicLink(link,manifest);badManifest(link,digest,"manifest symlink");
        Path invalid=root.resolve("invalid-utf8.json");Files.write(invalid,new byte[]{(byte)0xff});badManifest(invalid,sha(invalid),"manifest UTF8");
        Path duplicate=root.resolve("duplicate.json");Files.writeString(duplicate,"{\"private-field\":1,\"private-field\":2}");badManifest(duplicate,sha(duplicate),"manifest duplicate");
    }
    private static void badManifest(Path file,String digest,String label)throws Exception {
        try{OrganizationManagementSupplementHttp.readConfig(file.toString(),digest);throw new AssertionError(label);}
        catch(IllegalArgumentException expected){check(expected.getCause()==null && !expected.getMessage().contains(root.toString()) && !expected.getMessage().contains("private-field"),label+" sanitized");}
    }
    private static Map<String,Object> decisions(Map<String,Object> preview,long linked,Map<String,Object> account) {
        List<Object> choices=new ArrayList<>();boolean first=true;
        for(Object value:(List<?>)preview.get("rows")){boolean link=first;first=false;choices.add(map("reference",object(value).get("reference"),"action",link?"LINK_EXISTING":"CREATE_PENDING","accountId",link?linked:null,"accountProof",link?account.get("proof"):null,"reviewed",true));}
        return map("expectedRevision",preview.get("revision"),"sourceFingerprint",preview.get("sourceFingerprint"),"reviewToken",preview.get("reviewToken"),"decisions",choices);
    }
    private static final class Server implements AutoCloseable {
        final Process process;final String base;final Path log;
        Server(Path manifest,String digest)throws Exception {
            int port;try(ServerSocket socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
            base="http://127.0.0.1:"+port;log=Files.createTempFile(root,"main-",".log");
            Path original=root.resolve("original-manifest.json");
            List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Ddata.dir="+root.resolve("data"),"-Dbootstrap.demo=false","-Dbootstrap.admin.password="+PASSWORD,"-Dbind.address=127.0.0.1","-Dhttp.workers=4","-Daccount.import.manifest="+original,"-Daccount.import.manifest.sha256="+sha(original)));
            if(manifest!=null)command.add("-D"+OrganizationManagementSupplementHttp.MANIFEST_PROPERTY+"="+manifest);
            if(digest!=null)command.add("-D"+OrganizationManagementSupplementHttp.SHA256_PROPERTY+"="+digest);
            command.addAll(List.of("-cp",System.getProperty("java.class.path"),"com.training.Main",String.valueOf(port)));
            process=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {boolean ready=false;for(int i=0;i<100&&process.isAlive();i++){try{if(get("/api/me",null).status==401){ready=true;break;}}catch(Exception ignored){}Thread.sleep(100);}if(!ready)throw new AssertionError("Synthetic integrated Main failed; inspect private fixture log");}
            catch(Exception|Error failure){close();throw failure;}
        }
        Reply request(String path,String token,String method,byte[] bytes,String type)throws Exception {
            HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15));
            if(token!=null)request.header("Cookie","yx_session="+token);if(type!=null)request.header("Content-Type",type);
            request.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes));
            HttpResponse<String> response=CLIENT.send(request.build(),HttpResponse.BodyHandlers.ofString());String text=response.body();
            return new Reply(response.statusCode(),text.isEmpty()?Map.of():object(Json.parse(text)),text,response.headers().firstValue("Cache-Control").orElse(""));
        }
        Reply get(String path,String token)throws Exception{return request(path,token,"GET",null,null);}
        Reply post(String path,String token,Object body)throws Exception{return request(path,token,"POST",Json.write(body).getBytes(StandardCharsets.UTF_8),"application/json");}
        String login(String username)throws Exception {Reply reply=post("/api/login",null,map("username",username,"password",PASSWORD));check(reply.status==200,"synthetic real login");return (String)reply.data().get("token");}
        public void close()throws Exception {process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();if(!process.waitFor(5,TimeUnit.SECONDS))throw new AssertionError("Synthetic Main did not stop");}String text=Files.readString(log);check(!text.contains("SYNTHETIC SUPPLEMENT")&&!text.contains("000SUP")&&!text.contains(root.resolve("supplement-source").toString()),"no source identity/path in logs");}
    }
    private static void privateError(Reply reply,int status,String label){check(reply.status==status,label+" actual="+reply.status);check("no-store".equals(reply.cache),label+" no-store");check(!reply.text.contains(root.toString())&&!reply.text.contains("private-field")&&!reply.text.contains("000SUP"),label+" sanitized");}
    private static void privateSuccess(Reply reply,String label){check(reply.status==200 && "no-store".equals(reply.cache),label);check(!reply.text.contains(root.toString())&&!reply.text.contains("000SUP")&&!reply.text.contains("sourceStaffId"),label+" no private evidence");}
    private static Map<String,Object> copy(Map<String,Object> value){return object(Json.parse(Json.write(value)));}
    private static long n(Object value){return ((Number)value).longValue();}
    private static long scalar(Connection db,String sql)throws Exception{try(Statement statement=db.createStatement();ResultSet rows=statement.executeQuery(sql)){rows.next();return rows.getLong(1);}}
    private static void check(boolean valid,String message){checks++;if(!valid)throw new AssertionError(message);}
}
