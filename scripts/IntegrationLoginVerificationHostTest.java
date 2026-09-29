package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Narrow host/Auth checks. Only a fresh synthetic H2; no sender, server, private input or activation. */
public final class IntegrationLoginVerificationHostTest {
    private static final String PASSWORD="SYNTHETIC-LOGIN-HOST-ONLY";
    private static int checks;
    private static Auth.Session admin;
    private static String originalHash;
    @FunctionalInterface interface Work {void run()throws Exception;}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void eq(Object expected,Object actual,String label){check(Objects.equals(expected,actual),label);}
    private static void reject(int expected,Work action,String label)throws Exception{
        try{action.run();throw new AssertionError("Expected rejection: "+label);}
        catch(Api.ApiException error){eq(expected,error.code,label+" status");}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return(Map<String,Object>)value;}
    private static int sessions()throws Exception{
        Field field=Auth.class.getDeclaredField("SESSIONS");field.setAccessible(true);return((Map<?,?>)field.get(null)).size();
    }
    private static void parser()throws Exception{
        Map<String,Object> valid=LoginVerificationHost.parseBody(" \n{\"username\":\"synthetic-host\",\"password\":\"p\\n\\\"\\\\\"}\t");
        eq("synthetic-host",valid.get("username"),"flat parser preserves strings");
        eq("p\n\"\\",valid.get("password"),"valid string escapes preserved");
        eq("code",LoginVerificationHost.parseBody("{\"co\\u0064e\":\"000000\"}").keySet().iterator().next(),"escaped keys decoded before duplicate check");
        for(String invalid:new String[]{"", "[]", "null", "{", "{}x", "{\"a\":1}", "{\"a\":null}",
                "{\"a\":true}", "{\"a\":{}}", "{\"a\":[]}", "{\"a\":\"b\",}", "{\"a\":\"b\" \"c\":\"d\"}",
                "{\"username\":\"a\",\"username\":\"b\"}", "{\"password\":\"a\",\"pass\\u0077ord\":\"b\"}",
                "{\"a\":\"raw\nline\"}", "{\"a\":\"\\x41\"}", "{\"a\":\"\\u12x4\"}", "{\"a\":\"b\"}\u00a0",
                "{\"a\":\""+"x".repeat(4096)+"\"}"})
            reject(400,()->LoginVerificationHost.parseBody(invalid),"reject malformed or ambiguous authentication JSON");
        check(LoginVerificationHost.matches("/api/login")&&LoginVerificationHost.matches("/api/login/email/verify"),"login paths match host");
        check(!LoginVerificationHost.matches("/api/login-other")&&!LoginVerificationHost.matches(null),"unrelated paths do not match");
    }
    private static Exchange request(String method,String path,String body)throws Exception{
        Exchange ex=new Exchange(method,path,body);Api.handle(ex);return ex;
    }
    private static void status(Exchange ex,int expected){
        eq(expected,ex.status,"host HTTP status");eq(1,ex.sends,"one response");
        eq("no-store",ex.response.getFirst("Cache-Control"),"authentication response uncached");
        eq(expected==200?0:expected,((Number)ex.json().get("code")).intValue(),"matching JSON status");
        check(!ex.output.toString(StandardCharsets.UTF_8).contains(PASSWORD),"password absent from response");
    }
    private static String loginBody(){return Json.write(Map.of("username","synthetic-host-admin","password",PASSWORD));}
    private static void nullableLegacy()throws Exception{
        System.setProperty("login.email.mode","legacy");
        for(long id:new long[]{201,202}){
            String username="synthetic-null-"+id;
            Auth.Credential proof=Auth.checkedCredential(username,PASSWORD);
            check(proof!=null&&Auth.credentialCurrent(proof),"legacy nullable columns preserve valid password proof");
            String token=Auth.login(username,PASSWORD);Auth.Session session=Auth.get(token);
            check(session!=null&&session.uid==id,"legacy account with NULL profile field still logs in");
            eq("null",session.name,"legacy NULL name response compatibility");
            eq(id==201?"viewer":"null",session.role,"legacy nullable role response compatibility");
            Auth.logout(token);
        }
    }
    private static void modesAndRoutes()throws Exception{
        System.clearProperty("login.email.mode");
        check(LoginVerificationHost.acceptsUnverifiedSessions(),"default explicitly preserves current legacy operation");
        Exchange login=request("POST","/api/login",loginBody());status(login,200);
        String legacy=String.valueOf(map(login.json().get("data")).get("token"));
        check(Auth.get(legacy)!=null,"legacy session authenticates in legacy mode");
        int before=sessions();
        for(String body:List.of("{}", "{\"username\":\"a\",\"password\":\"b\",\"mode\":\"legacy\"}",
                "{\"username\":\"a\",\"password\":null}", "{\"username\":\"a\",\"password\":\"b\",\"password\":\"c\"}"))
            status(request("POST","/api/login",body),400);
        status(request("GET","/api/login",""),405);
        status(request("POST","/api/login?mode=legacy",loginBody()),400);
        status(request("POST","/api/login/unknown","{}"),404);
        status(request("POST","/api/login/email/verify","{\"challenge_id\":\"bad\",\"code\":\"000000\"}"),400);
        eq(before,sessions(),"malformed host calls create no session");

        System.setProperty("login.email.mode","required");
        check(Auth.get(legacy)==null,"required rejects and revokes a previous unverified session");
        reject(503,()->Auth.login("synthetic-host-admin",PASSWORD),"direct legacy Auth login cannot bypass required mode");
        before=sessions();status(request("POST","/api/login",Json.write(Map.of("username","synthetic-host-101","password",PASSWORD))),503);
        eq(before,sessions(),"default unconfigured sender cannot sign a session");
        System.setProperty("login.email.mode","unknown");
        status(request("POST","/api/login",loginBody()),503);
        reject(503,()->Auth.login("synthetic-host-admin",PASSWORD),"unknown mode cannot use legacy Auth");
        System.setProperty("login.email.mode","legacy");
        check(Auth.get(legacy)==null,"returning to legacy does not revive a revoked token");
    }
    private static void addCandidate(long id)throws Exception{
        Db.exec("INSERT INTO users VALUES(?,?,NULL,?,'viewer',0)",id,"synthetic-host-"+id,"SYNTHETIC HOST "+id);
        Db.exec("INSERT INTO organization_account_import_people(person_code,source_namespace,source_reference,account_id,batch_key,source_fingerprint,payload,disposition,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                "host-person-"+id,"a".repeat(64),"host-ref-"+id,id,"host-batch","b".repeat(64),"{}","create_pending",1);
        NotificationChannelsAccountEmailPreparation.save(admin,Map.of("user_id",id,"expected_revision",0,"request_id",UUID.randomUUID().toString(),"email","synthetic"+id+"@example.invalid"));
        // Synthetic fixture models an account activated by a separate authorized process.
        Db.exec("UPDATE users SET status=1,password=? WHERE id=?",originalHash,id);
    }
    private static Auth.Credential credential(long id)throws Exception{
        Auth.Credential result=Auth.checkedCredential("synthetic-host-"+id,PASSWORD);
        check(result!=null,"password proof exists only for active synthetic account");return result;
    }
    private static String verified(long id)throws Exception{
        return Auth.issueVerified(credential(id),NotificationChannelsAccountEmailPreparation.loginSnapshot(id));
    }
    private static void replaceCandidate(long id)throws Exception{
        System.setProperty("login.email.mode","legacy");
        admin=Auth.get(Auth.login("synthetic-host-admin",PASSWORD));
        Db.exec("UPDATE users SET status=0,password=NULL WHERE id=?",id);
        NotificationChannelsAccountEmailPreparation.save(admin,Map.of("user_id",id,"expected_revision",1,"request_id",UUID.randomUUID().toString(),"email","changed"+id+"@example.invalid"));
        Db.exec("UPDATE users SET status=1,password=? WHERE id=?",originalHash,id);
        System.setProperty("login.email.mode","required");
    }
    private static void credentialAndSessionBinding()throws Exception{
        System.setProperty("login.email.mode","required");
        int before=sessions();Auth.Credential proof=credential(101);
        eq(before,sessions(),"password proof does not create a session");
        check(Auth.credentialCurrent(proof),"fresh server credential is current");
        check(!proof.toString().contains(PASSWORD)&&!proof.toString().contains(originalHash),"credential debug rendering redacts secrets");
        var candidate=NotificationChannelsAccountEmailPreparation.loginSnapshot(101);
        String token=Auth.issueVerified(proof,candidate);Auth.Session session=Auth.get(token);
        check(session!=null&&session.uid==101,"verified issuance keeps exact trusted account");
        check(Auth.current(session)==session,"verified session is accepted by original Auth.current");
        eq(before+1,sessions(),"only final issuance adds a session");
        reject(401,()->Auth.issueVerified(proof,NotificationChannelsAccountEmailPreparation.loginSnapshot(102)),"another account's candidate cannot issue a session");
        reject(401,()->Auth.issueVerified(proof,null),"missing candidate cannot issue");

        Auth.revokeUserSessions(101);
        check(Auth.get(token)==null,"explicit revocation invalidates verified session");
        check(!Auth.credentialCurrent(proof),"explicit revocation invalidates unchanged password proof");
        reject(401,()->Auth.issueVerified(proof,candidate),"revoked pending proof cannot finish verification");

        String passwordSession=verified(102);Auth.Credential oldPassword=credential(102);
        Db.exec("UPDATE users SET password=? WHERE id=102",Auth.hash("SYNTHETIC-REPLACEMENT"));
        check(Auth.get(passwordSession)==null&&!Auth.credentialCurrent(oldPassword),"password changes invalidate proof and verified session");
        Db.exec("UPDATE users SET password=? WHERE id=102",originalHash);
        check(Auth.get(passwordSession)==null,"restoring password does not revive consumed session");

        String statusSession=verified(103);Db.exec("UPDATE users SET status=0 WHERE id=103");
        check(Auth.get(statusSession)==null,"disabled account invalidates verified session");
        check(Auth.checkedCredential("synthetic-host-103",PASSWORD)==null,"disabled account cannot obtain password proof");
        Db.exec("UPDATE users SET status=1 WHERE id=103");check(Auth.get(statusSession)==null,"re-enable does not revive rejected session");

        String candidateSession=verified(104);Auth.Credential candidateProof=credential(104);
        var previousCandidate=NotificationChannelsAccountEmailPreparation.loginSnapshot(104);
        replaceCandidate(104);
        check(Auth.get(candidateSession)==null,"legitimate candidate revision invalidates verified session");
        reject(401,()->Auth.issueVerified(candidateProof,previousCandidate),"old candidate revision cannot sign session");

        String sourceSession=verified(105);Auth.Credential sourceProof=credential(105);
        var oldSource=NotificationChannelsAccountEmailPreparation.loginSnapshot(105);
        Db.exec("UPDATE organization_account_import_people SET source_fingerprint=? WHERE account_id=105","c".repeat(64));
        check(Auth.get(sourceSession)==null,"changed source relation revokes verified session");
        reject(401,()->Auth.issueVerified(sourceProof,oldSource),"source integrity failure is a generic invalid verification");

        String unknownSession=verified(106);System.setProperty("login.email.mode","typo");
        check(Auth.get(unknownSession)==null,"unknown mode rejects even previously verified sessions");
        System.setProperty("login.email.mode","required");check(Auth.get(unknownSession)==null,"valid mode does not revive unknown-mode rejection");
        String logoutSession=verified(106);Auth.logout(logoutSession);check(Auth.get(logoutSession)==null,"original logout still revokes verified session");

        Db.exec("UPDATE users SET password=NULL WHERE id=106");
        check(Auth.checkedCredential("synthetic-host-106",PASSWORD)==null,"password NULL cannot become email-only authentication");
        eq(null,Auth.checkedCredential("unknown-account",PASSWORD),"unknown account cannot acquire credential");
    }
    private static final class Exchange extends HttpExchange{
        final Headers request=new Headers(),response=new Headers();final Map<String,Object>attributes=new HashMap<>();
        final URI uri;final String method;final InputStream input;final ByteArrayOutputStream output=new ByteArrayOutputStream();int status=-1,sends;
        Exchange(String method,String path,String body){this.method=method;uri=URI.create(path);input=new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));request.set("Content-Type","application/json");request.set("Host","localhost");}
        Map<String,Object>json(){return map(Json.parse(output.toString(StandardCharsets.UTF_8)));}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}
        public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}public HttpContext getHttpContext(){return null;}
        public void close(){}public InputStream getRequestBody(){return input;}public OutputStream getResponseBody(){return output;}
        public void sendResponseHeaders(int code,long length){check(!Thread.holdsLock(Api.MUTATION_LOCK),"host response sends outside shared lock");status=code;sends++;}
        public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1234);}public int getResponseCode(){return status;}
        public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",4321);}public String getProtocol(){return"HTTP/1.1";}
        public Object getAttribute(String key){return attributes.get(key);}public void setAttribute(String key,Object value){attributes.put(key,value);}
        public void setStreams(InputStream in,OutputStream out){}public HttpPrincipal getPrincipal(){return null;}
    }
    public static void main(String[]args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Empty synthetic temporary directory required");
        Path supplied=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isDirectory(supplied)||Files.isSymbolicLink(supplied))throw new IllegalArgumentException("Real temporary directory required");
        Path directory=supplied.toRealPath();
        if(!directory.startsWith(Path.of("/tmp").toRealPath())&&!directory.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))throw new IllegalArgumentException("Temporary directory only");
        try(var files=Files.list(directory)){if(files.findAny().isPresent())throw new IllegalArgumentException("Directory must be empty");}
        String previousMode=System.getProperty("login.email.mode");System.setProperty("login.email.mode","legacy");System.setProperty("data.dir",directory.toString());
        try{
            Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
            originalHash=Auth.hash(PASSWORD);Db.exec("INSERT INTO users VALUES(1,'synthetic-host-admin',?,'SYNTHETIC ADMIN','admin',1)",originalHash);
            Db.exec("INSERT INTO users VALUES(201,'synthetic-null-201',?,NULL,'viewer',1)",originalHash);
            Db.exec("INSERT INTO users VALUES(202,'synthetic-null-202',?,NULL,NULL,1)",originalHash);
            OrganizationAccountImport.init();NotificationChannelsAccountEmailPreparation.init();FirstBindStore.init();
            admin=Auth.get(Auth.login("synthetic-host-admin",PASSWORD));
            for(long id=101;id<=106;id++)addCandidate(id);
            parser();nullableLegacy();modesAndRoutes();credentialAndSessionBinding();
            System.out.println("IntegrationLoginVerificationHost: "+checks+" checks passed (host/Auth only; synthetic H2; no sender/server)");
        }finally{Db.get().close();if(previousMode==null)System.clearProperty("login.email.mode");else System.setProperty("login.email.mode",previousMode);}
    }
}
