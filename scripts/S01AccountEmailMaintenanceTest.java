package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Real Auth + synthetic H2 only; no sockets, SMTP, real credentials or application data. */
public final class S01AccountEmailMaintenanceTest {
    private static int checks;
    private static Auth.Session admin, secondAdmin, viewer, manager;
    private static final Map<Long,String> tokens = new HashMap<>();
    private static final String ROUTE = "/api/account-email-maintenance";
    private static final String PASSWORD = "synthetic-email-maintenance-only";
    private static final String FIRST_REQUEST = "11111111-2222-4333-8444-555555555555";
    private static final List<String> EMAIL_TABLES = List.of("s01_account_email_heads", "s01_account_email_revisions", "s01_account_email_requests", "s01_account_email_maintenance_heads", "s01_account_email_maintenance_revisions", "s01_account_email_maintenance_requests");
    private static final List<String> PROTECTED_TABLES = List.of("users", "organization_account_import_state", "organization_account_import_batches", "organization_account_import_people", "m01_synthetic_guard", "s01_notifications", "workflow_outbox");
    @FunctionalInterface interface Work { void run() throws Exception; }
    static void check(boolean value,String label) { checks++; if(!value)throw new AssertionError(label); }
    static Api.ApiException reject(int code,Work work,String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected rejection: "+label); }
        catch(Api.ApiException e) { check(e.code==code,label+" expected="+code+" actual="+e.code); check(!Objects.toString(e.getMessage(),"").contains("@example.test"),label+" does not leak addresses");return e; }
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object o){return (Map<String,Object>)o;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> history(Map<String,Object> o){return (List<Map<String,Object>>)o.get("history");}
    static long n(Map<String,Object> o,String k){return ((Number)o.get(k)).longValue();}
    static String uuid(){return UUID.randomUUID().toString();}
    static String username(long id){return "synthetic-email-"+id;}
    static Map<String,Object> command(long id,long rev,String req,Object email){
        Map<String,Object> body=new LinkedHashMap<>();body.put("user_id",id);body.put("expected_revision",rev);body.put("request_id",req);body.put("email",email);body.put("confirmed_username",username(id));body.put("purpose","LOGIN_VERIFICATION");return body;
    }
    static Map<String,Object> oldCommand(long id,long rev,String req,Object email){Map<String,Object> body=command(id,rev,req,email);body.remove("confirmed_username");body.remove("purpose");return body;}
    static Map<String,Object> get(long id)throws Exception{return NotificationChannelsAccountEmailMaintenance.get(admin,id);}
    static Map<String,Object> save(long id,long rev,String req,Object email)throws Exception{return NotificationChannelsAccountEmailMaintenance.save(admin,command(id,rev,req,email));}
    static Map<String,Object> oldSave(long id,long rev,String req,Object email)throws Exception{return NotificationChannelsAccountEmailPreparation.save(admin,oldCommand(id,rev,req,email));}
    static Auth.Session login(long id)throws Exception{
        String token=Auth.login(username(id),PASSWORD);check(token!=null,"real Auth accepts synthetic enabled account "+id);tokens.put(id,token);return Auth.get(token);
    }
    static Auth.Session verifiedLogin(long id)throws Exception{
        Auth.Credential credential=Auth.checkedCredential(username(id),PASSWORD);check(credential!=null,"password credential exists");
        String token=Auth.issueVerified(credential,NotificationChannelsAccountEmailPreparation.loginSnapshot(id));tokens.put(id,token);Auth.Session result=Auth.get(token);check(result!=null,"real required-mode verified session");return result;
    }
    static Map<String,String> snapshot(List<String> tables)throws Exception{
        Map<String,String> result=new TreeMap<>();for(String table:tables){List<String> rows=new ArrayList<>();for(Map<String,Object> row:Db.query("SELECT * FROM "+table))rows.add(Json.write(row));Collections.sort(rows);result.put(table,rows.toString());}return result;
    }
    static Map<String,String> emailSnapshot()throws Exception{return snapshot(EMAIL_TABLES);}
    static void unchangedFailure(int code,Map<String,Object> body,String label)throws Exception{Map<String,String> before=emailSnapshot();reject(code,()->NotificationChannelsAccountEmailMaintenance.save(admin,body),label);check(before.equals(emailSnapshot()),label+" is write-free");}
    static void state(Map<String,Object> value,long id,String kind,long revision,String email,int count){
        check(value.keySet().equals(Set.of("user_id","username","name","account_kind","revision","email","status","history","can_save","duplicate_email","reauthentication_required")),"response contains only approved public fields");
        check(n(value,"user_id")==id&&n(value,"revision")==revision,"current identity and revision");
        check(username(id).equals(value.get("username"))&&Objects.toString(value.get("name"),"").startsWith("Synthetic"),"confirmed public identity");
        check(kind.equals(value.get("account_kind")),"account classification follows real import ownership");
        check(Objects.equals(value.get("email"),email),"normalized current address");
        check((email==null?"MISSING":"PENDING_VERIFICATION").equals(value.get("status")),"no activation or verification claim");
        check(Boolean.TRUE.equals(value.get("can_save"))&&value.get("duplicate_email") instanceof Boolean&&value.get("reauthentication_required") instanceof Boolean,"save, duplicate and reauthentication flags are booleans");
        check(history(value).size()==count,"exact append-only history count");
        for(Map<String,Object> row:history(value)){check(row.keySet().equals(Set.of("revision","email","status","actor_user_id","recorded_at")),"audit exposes only approved fields");check(n(row,"revision")>0&&n(row,"actor_user_id")>0,"positive audit revision and actor");java.time.Instant.parse(Objects.toString(row.get("recorded_at")));}
    }
    /** Host-compatible deferred HTTP response fixture; never binds a network socket. */
    static final class Exchange extends HttpExchange {
        final Headers requestHeaders=new Headers(),responseHeaders=new Headers();final Map<String,Object> attributes=new HashMap<>();final URI uri;final String method;
        InputStream input=new ByteArrayInputStream(new byte[0]);OutputStream output=new ByteArrayOutputStream();int responseCode=-1;
        Exchange(String method,String path,String token,Map<String,Object> body)throws Exception{this.method=method;uri=URI.create(path);if(token!=null)requestHeaders.set("Cookie","yx_session="+token);if(body!=null){requestHeaders.set("Content-Type","application/json");attributes.put(Api.class.getName()+".body",NotificationChannelsAccountEmailMaintenance.parseBody(Json.write(body)));}}
        Map<String,Object> response()throws Exception{Object pending=attributes.get(Api.class.getName()+".response");check(pending!=null,"response was buffered by real Api");Field field=pending.getClass().getDeclaredField("body");field.setAccessible(true);return map(Json.parse(new String((byte[])field.get(pending),StandardCharsets.UTF_8)));}
        @Override public Headers getRequestHeaders(){return requestHeaders;}@Override public Headers getResponseHeaders(){return responseHeaders;}@Override public URI getRequestURI(){return uri;}@Override public String getRequestMethod(){return method;}@Override public HttpContext getHttpContext(){return null;}@Override public void close(){}@Override public InputStream getRequestBody(){return input;}@Override public OutputStream getResponseBody(){return output;}@Override public void sendResponseHeaders(int code,long length){responseCode=code;}@Override public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",0);}@Override public int getResponseCode(){return responseCode;}@Override public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",0);}@Override public String getProtocol(){return "HTTP/1.1";}@Override public Object getAttribute(String name){return attributes.get(name);}@Override public void setAttribute(String name,Object value){attributes.put(name,value);}@Override public void setStreams(InputStream in,OutputStream out){input=in;output=out;}@Override public HttpPrincipal getPrincipal(){return null;}
    }
    static Exchange exchange(Auth.Session actor,String method,String path,Map<String,Object> body)throws Exception{return new Exchange(method,path,actor==null?null:tokens.get(actor.uid),body);}
    static Map<String,Object> http(Auth.Session actor,String method,String path,Map<String,Object> body)throws Exception{Exchange ex=exchange(actor,method,path,body);check(NotificationChannelsAccountEmailMaintenance.handle(ex,actor),"HTTP route recognized");check("no-store".equals(ex.responseHeaders.getFirst("Cache-Control")),"email HTTP responses forbid cache");return map(ex.response().get("data"));}

    static void authorizationAndHttp()throws Exception{
        Auth.Session forged=new Auth.Session();forged.uid=1;forged.role="admin";
        for(Auth.Session actor:Arrays.asList(null,forged)){reject(401,()->NotificationChannelsAccountEmailMaintenance.get(actor,20),"absent/fabricated session get");reject(401,()->NotificationChannelsAccountEmailMaintenance.save(actor,command(20,0,uuid(),null)),"absent/fabricated session save");}
        for(Auth.Session actor:List.of(viewer,manager)){reject(403,()->NotificationChannelsAccountEmailMaintenance.get(actor,20),"non-admin get");reject(403,()->NotificationChannelsAccountEmailMaintenance.save(actor,command(20,0,uuid(),null)),"non-admin save");}
        reject(404,()->get(99999),"unknown target");unchangedFailure(404,command(99999,0,uuid(),null),"unknown target save");
        Db.exec("UPDATE users SET status=2 WHERE id=28");try{reject(409,()->get(28),"unsupported status");unchangedFailure(409,command(28,0,uuid(),null),"unsupported status save");}finally{Db.exec("UPDATE users SET status=1 WHERE id=28");}
        check(NotificationChannelsAccountEmailMaintenance.matches(ROUTE),"exact route matches");check(!NotificationChannelsAccountEmailMaintenance.matches("/api/account-email-other"),"unrelated route excluded");
        reject(401,()->NotificationChannelsAccountEmailMaintenance.handle(exchange(null,"GET",ROUTE+"?user_id=20",null),admin),"supplied session cannot replace missing HTTP token");
        reject(401,()->NotificationChannelsAccountEmailMaintenance.handle(exchange(secondAdmin,"GET",ROUTE+"?user_id=20",null),admin),"HTTP token must match session");
        Exchange header=exchange(null,"GET",ROUTE+"?user_id=20",null);header.requestHeaders.set("X-Token",tokens.get(1L));check(NotificationChannelsAccountEmailMaintenance.handle(header,admin),"matching X-Token session accepted");
        for(String query:List.of("","?user_id=0","?user_id=-1","?user_id=1.1","?user_id=20&other=1","?user_id=20&user_id=21","?user_id=20&email=x%40example.test"))reject(400,()->http(admin,"GET",ROUTE+query,null),"GET accepts only one positive user_id");
        for(String method:List.of("PUT","PATCH","DELETE"))reject(405,()->http(admin,method,ROUTE,null),"unsupported method");
        reject(415,()->http(admin,"POST",ROUTE,null),"POST requires JSON");reject(400,()->http(admin,"POST",ROUTE+"?user_id=21",command(20,0,uuid(),null)),"POST cannot override target through query");
        state(http(admin,"GET",ROUTE+"?user_id=20",null),20,"EXISTING",0,null,0);
        Db.exec("UPDATE users SET role='viewer' WHERE id=1");try{reject(403,()->get(20),"current database role overrides stale session");}finally{Db.exec("UPDATE users SET role='admin' WHERE id=1");}
        Auth.revokeUserSessions(1);reject(401,()->get(20),"revoked real session rejected");admin=login(1);
    }
    static void validationAndParser()throws Exception{
        for(String key:command(23,0,uuid(),null).keySet()){Map<String,Object> body=command(23,0,uuid(),null);body.remove(key);unchangedFailure(400,body,"missing required "+key);}
        for(String key:List.of("verified","activate","role","actor_user_id","send_email")){Map<String,Object> body=command(23,0,uuid(),null);body.put(key,true);unchangedFailure(400,body,"unknown field "+key);}
        for(Object purpose:Arrays.asList(null,true,42L,"login_verification","LOGIN_VERIFICATION ","OTHER")){Map<String,Object> body=command(23,0,uuid(),null);body.put("purpose",purpose);unchangedFailure(400,body,"purpose must be exact fixed declaration");}
        for(Object name:Arrays.asList(null,true,23L,""," "+username(23),username(23).toUpperCase(Locale.ROOT),username(24))){Map<String,Object> body=command(23,0,uuid(),null);body.put("confirmed_username",name);try{unchangedFailure(name instanceof String&&!((String)name).isEmpty()?409:400,body,"username must exactly confirm current target");}catch(AssertionError mismatch){throw mismatch;}}
        for(String key:List.of("user_id","expected_revision"))for(Object value:Arrays.asList("23",true,null,-1L,Double.NaN,Double.POSITIVE_INFINITY,new BigDecimal("23.0000000000000001"),9007199254740992L)){Map<String,Object> body=command(23,0,uuid(),null);body.put(key,value);unchangedFailure(400,body,"strict bounded integer "+key);}
        for(Object email:Arrays.asList(42L,true,List.of("x@example.test"),"bad","a@localhost","a@@example.test","a..b@example.test","a@example..test","a@example.test\r\nBcc: x@example.test","a@example.test\t","用户@example.test","a@example.test,other@example.test","x".repeat(65)+"@example.test"))unchangedFailure(400,command(23,0,uuid(),email),"malformed email rejected");
        String valid=Json.write(command(23,0,uuid(),"parser@example.test"));
        for(String raw:List.of("","[]",valid+"{}",valid.replace("\"user_id\":23","\"user_id\":23,\"user_id\":23"),valid.replace("\"user_id\":23","\"user_id\":23,\"\\u0075ser_id\":23"),valid.replace("\"user_id\":23","\"user_id\":01"),valid.replace("\"user_id\":23","\"user_id\":+23"),valid.replace("\"user_id\":23","\"user_id\":true"),valid.replace("\"user_id\":23","\"user_id\":{}"),valid.replace("\"user_id\":23","\"user_id\":NaN"),valid.replace("\"user_id\":23","\"unknown\":23"),valid.substring(0,valid.length()-1)+",}","\u000b"+valid))reject(400,()->NotificationChannelsAccountEmailMaintenance.parseBody(raw),"strict flat JSON parser");
        for(String number:List.of("23.0000000000000001","9007199254740991.1","1e-1")){Map<String,Object> parsed=NotificationChannelsAccountEmailMaintenance.parseBody(valid.replace("\"user_id\":23","\"user_id\":"+number));check(parsed.get("user_id") instanceof BigDecimal,"parser retains decimal precision");unchangedFailure(400,parsed,"fraction cannot round into valid target");}
        Map<String,Object> exact=NotificationChannelsAccountEmailMaintenance.parseBody(" \r\n\t"+valid.replace("\"user_id\":23","\"user_id\":23e0")+" \n");state(NotificationChannelsAccountEmailMaintenance.save(admin,exact),23,"EXISTING",1,"parser@example.test",1);
    }
    static void nativeLifecycle()throws Exception{
        Auth.Session target=login(20);String oldToken=tokens.get(20L);Auth.Credential proof=Auth.checkedCredential(username(20),PASSWORD);
        Map<String,Object> first=http(admin,"POST",ROUTE,command(20,0,FIRST_REQUEST,"  Local.Part@EXAMPLE.TEST  "));state(first,20,"EXISTING",1,"Local.Part@example.test",1);check(Boolean.FALSE.equals(first.get("reauthentication_required")),"editing another user does not log out acting admin");check(Auth.get(oldToken)==null&&!Auth.credentialCurrent(proof),"actual change revokes target sessions and password generation");
        target=login(20);String freshToken=tokens.get(20L);Map<String,String> before=emailSnapshot();state(save(20,0,FIRST_REQUEST,"Local.Part@example.test"),20,"EXISTING",1,"Local.Part@example.test",1);check(before.equals(emailSnapshot()),"normalized idempotent replay writes nothing");check(Auth.get(freshToken)==target,"replay keeps newly issued target session");
        state(save(20,1,uuid(),"Local.Part@Example.Test"),20,"EXISTING",1,"Local.Part@example.test",1);check(Auth.get(freshToken)==target,"same-value save preserves target session");
        unchangedFailure(409,command(20,0,uuid(),"Local.Part@example.test"),"stale no-op is conflict");unchangedFailure(409,command(20,0,FIRST_REQUEST,"Other@example.test"),"UUID payload collision");unchangedFailure(409,command(20,1,FIRST_REQUEST,"Local.Part@example.test"),"UUID revision collision");unchangedFailure(409,command(21,0,FIRST_REQUEST,"Local.Part@example.test"),"UUID target collision");
        reject(409,()->NotificationChannelsAccountEmailMaintenance.save(secondAdmin,command(20,0,FIRST_REQUEST,"Local.Part@example.test")),"UUID actor collision");
        state(save(20,1,uuid(),"Next@example.test"),20,"EXISTING",2,"Next@example.test",2);before=emailSnapshot();state(save(20,0,FIRST_REQUEST,"Local.Part@example.test"),20,"EXISTING",2,"Next@example.test",2);check(before.equals(emailSnapshot()),"old request returns current state without restoring old value");
        state(save(20,2,uuid(),"  "),20,"EXISTING",3,null,3);state(save(20,3,uuid(),null),20,"EXISTING",3,null,3);state(save(20,3,uuid(),"Persisted@example.test"),20,"EXISTING",4,"Persisted@example.test",4);
        state(save(21,0,uuid(),null),21,"EXISTING",0,null,0);check(Db.query("SELECT * FROM s01_account_email_maintenance_heads WHERE user_id=21").size()==1,"initial null establishes identity head");check(Db.query("SELECT * FROM s01_account_email_maintenance_revisions WHERE user_id=21").isEmpty(),"initial null invents no history revision");
        check(Db.query("SELECT * FROM organization_account_import_people WHERE account_id BETWEEN 20 AND 40").isEmpty(),"native maintenance does not fabricate import associations");
    }
    static void importedCompatibilityAndDuplicates()throws Exception{
        String legacy=uuid();oldSave(101,0,legacy,"ImportOld@example.test");Map<String,Object> before=NotificationChannelsAccountEmailPreparation.get(admin,101);Map<String,Object> firstHistory=new LinkedHashMap<>(history(before).get(0));
        String maintenance=uuid();state(save(101,1,maintenance,"ImportNew@example.test"),101,"IMPORTED",2,"ImportNew@example.test",2);check(history(get(101)).get(0).equals(firstHistory),"maintenance preserves historical imported row exactly");check(Db.query("SELECT * FROM s01_account_email_maintenance_heads WHERE user_id=101").isEmpty(),"imported account has no second head");
        state(get(101),101,"IMPORTED",2,"ImportNew@example.test",2);check(n(NotificationChannelsAccountEmailPreparation.get(admin,101),"revision")==2,"old preparation reads shared imported history");
        unchangedFailure(409,command(101,0,legacy,"ImportOld@example.test"),"old preparation UUID cannot be reused in maintenance");Map<String,String> rows=emailSnapshot();reject(409,()->oldSave(101,1,maintenance,"ImportNew@example.test"),"maintenance UUID cannot be reused in preparation");check(rows.equals(emailSnapshot()),"cross-entry UUID collision is write-free");
        oldSave(102,0,uuid(),"LegacyDupe@example.test");oldSave(103,0,uuid(),"legacydupe@example.test");check(Boolean.TRUE.equals(NotificationChannelsAccountEmailPreparation.get(admin,102).get("duplicate_email")),"old imported-to-imported duplicate behavior remains compatible");check(Boolean.TRUE.equals(get(103).get("duplicate_email")),"maintenance detects legacy imported duplicates");
        unchangedFailure(409,command(103,1,uuid(),"legacydupe@example.test"),"maintenance same-value cannot preserve duplicate conflict");unchangedFailure(409,command(24,0,uuid(),"LEGACYDUPE@EXAMPLE.TEST"),"native conflicts with imported case-insensitive address");
        save(24,0,uuid(),"NativeDuplicate@example.test");rows=emailSnapshot();reject(409,()->oldSave(104,0,uuid(),"nativeduplicate@example.test"),"legacy preparation cannot conflict with native candidate");check(rows.equals(emailSnapshot()),"old entry native conflict rolls back");unchangedFailure(409,command(104,0,uuid(),"NATIVEDUPLICATE@example.test"),"maintenance imported conflicts with native");unchangedFailure(409,command(25,0,uuid(),"nativeduplicate@example.test"),"native-to-native duplicate denied");
        save(103,1,uuid(),"Resolved@example.test");check(Boolean.FALSE.equals(get(102).get("duplicate_email")),"duplicate detection ignores obsolete history");
        reject(404,()->NotificationChannelsAccountEmailPreparation.get(admin,24),"native accounts stay outside old preparation route");
    }
    static void identityAndHistory()throws Exception{
        reject(409,()->NotificationChannelsAccountEmailPreparation.loginSnapshot(26),"native login without maintained head fails closed");
        String originalRequest=uuid();save(25,0,originalRequest,"Bound@example.test");save(25,1,uuid(),"BoundNew@example.test");
        login(25);String priorToken=tokens.get(25L);var beforeRename=NotificationChannelsAccountEmailPreparation.loginSnapshot(25);
        Db.exec("UPDATE users SET username='renamed-synthetic' WHERE id=25");Auth.revokeUserSessions(25);
        try{
            check(Auth.get(priorToken)==null,"normal username change revokes prior session");Map<String,String> rows=emailSnapshot();Map<String,Object> renamed=get(25);
            check("renamed-synthetic".equals(renamed.get("username"))&&n(renamed,"revision")==2&&history(renamed).size()==2,"normal username change preserves maintained history under stable user identity");check(beforeRename.equals(NotificationChannelsAccountEmailPreparation.loginSnapshot(25)),"native login snapshot remains usable after supported username change");check(Auth.checkedCredential("renamed-synthetic",PASSWORD)!=null,"renamed account can obtain a fresh real password proof");check(rows.equals(emailSnapshot()),"rename read path never rewrites historical confirmation declarations");
            unchangedFailure(409,command(25,2,uuid(),"BoundNew@example.test"),"new save must confirm target current username");unchangedFailure(409,command(25,0,originalRequest,"Bound@example.test"),"old username cannot confirm replay after rename");
            Map<String,Object> collision=command(25,0,originalRequest,"Bound@example.test");collision.put("confirmed_username","renamed-synthetic");unchangedFailure(409,collision,"old UUID cannot be reused with new confirmed username");
            Map<String,Object> confirmed=command(25,2,uuid(),"BoundNew@example.test");confirmed.put("confirmed_username","renamed-synthetic");Map<String,Object> saved=NotificationChannelsAccountEmailMaintenance.save(admin,confirmed);check(n(saved,"revision")==2&&"renamed-synthetic".equals(saved.get("username")),"fresh save can confirm current renamed username without rewriting earlier declarations");
        }finally{Db.exec("UPDATE users SET username=? WHERE id=25",username(25));Auth.revokeUserSessions(25);}
        state(get(25),25,"EXISTING",2,"BoundNew@example.test",2);
        association(25);try{reject(409,()->get(25),"native target subsequently imported cannot silently migrate");reject(409,()->NotificationChannelsAccountEmailPreparation.loginSnapshot(25),"later import cannot fallback to native login history");}finally{Db.exec("DELETE FROM organization_account_import_people WHERE account_id=25");}
        for(String table:List.of("s01_account_email_maintenance_revisions","s01_account_email_revisions")){
            long id=table.contains("maintenance")?25:101;Map<String,Object> old=Db.one("SELECT email,recorded_at FROM "+table+" WHERE user_id=? AND revision=1",id);
            Db.exec("UPDATE "+table+" SET email='invalid-value' WHERE user_id=? AND revision=1",id);try{reject(409,()->get(id),"all historical revisions validated");unchangedFailure(409,command(id,2,uuid(),"WriteBlocked@example.test"),"bad old history blocks write");reject(409,()->NotificationChannelsAccountEmailPreparation.loginSnapshot(id),"bad old history blocks login snapshot");}finally{Db.exec("UPDATE "+table+" SET email=? WHERE user_id=? AND revision=1",old.get("email"),id);}
            Db.exec("UPDATE "+table+" SET recorded_at='invalid-instant' WHERE user_id=? AND revision=1",id);try{reject(409,()->get(id),"bad historical timestamp is conflict");}finally{Db.exec("UPDATE "+table+" SET recorded_at=? WHERE user_id=? AND revision=1",old.get("recorded_at"),id);}
        }
        check(NotificationChannelsAccountEmailPreparation.loginSnapshot(25).email().equals("BoundNew@example.test"),"native login uses coherent current state");
        for(String assignment:List.of("status=0","password=NULL","password=''")){Map<String,Object> old=Db.one("SELECT status,password FROM users WHERE id=25");Db.exec("UPDATE users SET "+assignment+" WHERE id=25");try{reject(409,()->NotificationChannelsAccountEmailPreparation.loginSnapshot(25),"login requires enabled target with nonempty password");}finally{Db.exec("UPDATE users SET status=?,password=? WHERE id=25",old.get("status"),old.get("password"));}}
        Db.exec("UPDATE organization_account_import_people SET source_reference='replaced-import-binding' WHERE account_id=101");try{reject(409,()->get(101),"import identity drift rejected");}finally{Db.exec("UPDATE organization_account_import_people SET source_reference='synthetic-ref-101' WHERE account_id=101");}
    }
    static void restoreRow(String table,Map<String,Object> row)throws Exception{
        String placeholders=String.join(",",Collections.nCopies(row.size(),"?"));Db.exec("INSERT INTO "+table+"("+String.join(",",row.keySet())+") VALUES("+placeholders+")",row.values().toArray());
    }
    static void requestLedgerIntegrity()throws Exception{
        for(String table:List.of("s01_account_email_requests","s01_account_email_maintenance_requests")){
            long id=table.contains("maintenance")?25:101;Map<String,Object> row=Db.one("SELECT * FROM "+table+" WHERE user_id=? AND expected_revision=0 AND result_revision=1",id);String req=(String)row.get("request_id");
            Db.exec("UPDATE "+table+" SET email_digest=? WHERE request_id=?","f".repeat(64),req);
            try{reject(409,()->get(id),"bad historical request digest invalidates current state");unchangedFailure(409,command(id,2,uuid(),"BlockedByDigest@example.test"),"bad old request digest blocks new edits");reject(409,()->NotificationChannelsAccountEmailPreparation.loginSnapshot(id),"bad old digest blocks login snapshot");}
            finally{Db.exec("UPDATE "+table+" SET email_digest=? WHERE request_id=?",row.get("email_digest"),req);}
            Db.exec("UPDATE "+table+" SET actor_user_id=2 WHERE request_id=?",req);try{reject(409,()->get(id),"historical request actor must match associated revision");}finally{Db.exec("UPDATE "+table+" SET actor_user_id=? WHERE request_id=?",row.get("actor_user_id"),req);}
            Db.exec("DELETE FROM "+table+" WHERE request_id=?",req);try{reject(409,()->get(id),"missing historical change request invalidates complete history");reject(409,()->NotificationChannelsAccountEmailPreparation.loginSnapshot(id),"missing old request prevents login fallback");}finally{restoreRow(table,row);}
        }
        Map<String,Object> declaration=Db.one("SELECT * FROM s01_account_email_maintenance_requests WHERE user_id=25 AND result_revision=1");String req=(String)declaration.get("request_id");
        Db.exec("UPDATE s01_account_email_maintenance_requests SET confirmed_username='wrong-but-valid-name' WHERE request_id=?",req);try{reject(409,()->get(25),"historical confirmed username must match its immutable declaration digest");}finally{Db.exec("UPDATE s01_account_email_maintenance_requests SET confirmed_username=? WHERE request_id=?",declaration.get("confirmed_username"),req);}
        for(long id:List.of(25L,101L)){Map<String,Object> row=Db.one("SELECT request_id,confirmation_digest FROM s01_account_email_maintenance_requests WHERE user_id=? ORDER BY result_revision LIMIT 1",id);Db.exec("UPDATE s01_account_email_maintenance_requests SET confirmation_digest=? WHERE request_id=?","d".repeat(64),row.get("request_id"));try{reject(409,()->get(id),"direct confirmation digest corruption invalidates declaration");reject(409,()->NotificationChannelsAccountEmailPreparation.loginSnapshot(id),"damaged confirmation digest blocks login snapshot");}finally{Db.exec("UPDATE s01_account_email_maintenance_requests SET confirmation_digest=? WHERE request_id=?",row.get("confirmation_digest"),row.get("request_id"));}}
        // A maintained imported record must have the identical declaration in both request ledgers.
        Map<String,Object> imported=Db.one("SELECT * FROM s01_account_email_maintenance_requests WHERE user_id=101");
        Db.exec("UPDATE s01_account_email_maintenance_requests SET email_digest=? WHERE request_id=?","e".repeat(64),imported.get("request_id"));try{reject(409,()->get(101),"imported maintenance declaration must agree with original request");}finally{Db.exec("UPDATE s01_account_email_maintenance_requests SET email_digest=? WHERE request_id=?",imported.get("email_digest"),imported.get("request_id"));}
    }
    static void enabledImportedAndDisabledNative()throws Exception{
        Map<String,Object> before=Db.one("SELECT * FROM users WHERE id=105");Db.exec("UPDATE users SET status=1,role='manager',password=? WHERE id=105",Db.one("SELECT password FROM users WHERE id=20").get("password"));
        try{
            Auth.Session target=login(105);String token=tokens.get(105L);state(save(105,0,uuid(),"EnabledImported@example.test"),105,"IMPORTED",1,"EnabledImported@example.test",1);check(Auth.get(token)==null,"enabled imported edit revokes target session");check(NotificationChannelsAccountEmailPreparation.loginSnapshot(105).email().equals("EnabledImported@example.test"),"enabled imported login has same unified source");reject(409,()->NotificationChannelsAccountEmailPreparation.get(admin,105),"old preparation retains pending-only eligibility");
            target=login(105);token=tokens.get(105L);String stableToken=token;Auth.Session stableSession=target;Map<String,String> rows=emailSnapshot();
            Db.exec("ALTER TABLE s01_account_email_maintenance_requests ADD CONSTRAINT synthetic_imported_request_failure CHECK(user_id<>105 OR result_revision<>2)");try{unchangedFailure(503,command(105,1,uuid(),"ImportedRollback@example.test"),"imported declaration failure rolls back all original and maintenance writes");check(rows.equals(emailSnapshot())&&Auth.get(stableToken)==stableSession,"imported transaction failure preserves all history and live session");}finally{Db.exec("ALTER TABLE s01_account_email_maintenance_requests DROP CONSTRAINT synthetic_imported_request_failure");}
        }finally{Db.exec("UPDATE users SET status=?,role=?,password=? WHERE id=105",before.get("status"),before.get("role"),before.get("password"));Auth.revokeUserSessions(105);}
        state(save(40,0,uuid(),"DisabledNative@example.test"),40,"EXISTING",1,"DisabledNative@example.test",1);reject(409,()->NotificationChannelsAccountEmailPreparation.loginSnapshot(40),"maintaining disabled native account cannot enable login");check(Auth.login(username(40),PASSWORD)==null,"disabled native remains disabled");
    }
    static void concurrency()throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(8);try{
            String req=uuid();CountDownLatch start=new CountDownLatch(1);List<Future<Map<String,Object>>> same=new ArrayList<>();for(int i=0;i<8;i++)same.add(pool.submit(()->{start.await();return save(29,0,req,"Concurrent@example.test");}));start.countDown();for(Future<Map<String,Object>> f:same)state(f.get(20,TimeUnit.SECONDS),29,"EXISTING",1,"Concurrent@example.test",1);check(Db.query("SELECT * FROM s01_account_email_maintenance_requests WHERE user_id=29").size()==1,"concurrent replay has exactly one request claim");
            CountDownLatch race=new CountDownLatch(1);List<Future<Integer>> outcomes=new ArrayList<>();for(int i=0;i<2;i++){final int k=i;outcomes.add(pool.submit(()->{race.await();try{save(30,0,uuid(),"CAS-"+k+"@example.test");return 200;}catch(Api.ApiException e){return e.code;}}));}race.countDown();List<Integer> codes=new ArrayList<>();for(Future<Integer> f:outcomes)codes.add(f.get(20,TimeUnit.SECONDS));Collections.sort(codes);check(codes.equals(List.of(200,409)),"one compare-and-set winner");check(n(get(30),"revision")==1&&history(get(30)).size()==1,"CAS loser invents no revision");
            CountDownLatch duplicate=new CountDownLatch(1);outcomes.clear();for(int i=0;i<2;i++){final long id=31+i;outcomes.add(pool.submit(()->{duplicate.await();try{save(id,0,uuid(),"ConcurrentDuplicate@example.test");return 200;}catch(Api.ApiException e){return e.code;}}));}duplicate.countDown();codes.clear();for(Future<Integer> f:outcomes)codes.add(f.get(20,TimeUnit.SECONDS));Collections.sort(codes);check(codes.equals(List.of(200,409)),"simultaneous global duplicate attempts have one winner");
            CountDownLatch crossSource=new CountDownLatch(1);outcomes.clear();for(int i=0;i<2;i++){final boolean old=i==0;outcomes.add(pool.submit(()->{crossSource.await();try{if(old)oldSave(106,0,uuid(),"CrossSourceRace@example.test");else save(35,0,uuid(),"CrossSourceRace@example.test");return 200;}catch(Api.ApiException e){return e.code;}}));}crossSource.countDown();codes.clear();for(Future<Integer> f:outcomes)codes.add(f.get(20,TimeUnit.SECONDS));Collections.sort(codes);check(codes.equals(List.of(200,409)),"old imported and native entries serialize one global duplicate winner");
            CountDownLatch claim=new CountDownLatch(1);String claimId=uuid();outcomes.clear();for(int i=0;i<2;i++){final boolean old=i==0;outcomes.add(pool.submit(()->{claim.await();try{if(old)oldSave(107,0,claimId,"CrossEntryOld@example.test");else save(36,0,claimId,"CrossEntryNew@example.test");return 200;}catch(Api.ApiException e){return e.code;}}));}claim.countDown();codes.clear();for(Future<Integer> f:outcomes)codes.add(f.get(20,TimeUnit.SECONDS));Collections.sort(codes);check(codes.equals(List.of(200,409)),"UUID can be claimed by only one of old and new entry points");
        }finally{pool.shutdownNow();check(pool.awaitTermination(5,TimeUnit.SECONDS),"all test workers stopped");}
    }
    static void sqlFailuresAndDeletion()throws Exception{
        Auth.Session target=login(27);String token=tokens.get(27L);Auth.Credential credential=Auth.checkedCredential(username(27),PASSWORD);
        Db.exec("ALTER TABLE s01_account_email_maintenance_heads ADD CONSTRAINT synthetic_email_failure CHECK(email IS NULL OR email <> 'Private-SQL-Failure@example.test')");try{unchangedFailure(503,command(27,0,uuid(),"Private-SQL-Failure@example.test"),"SQL integrity error is sanitized and fully atomic");check(Auth.get(token)==target&&Auth.credentialCurrent(credential),"rolled-back write does not revoke valid sessions or generation");}finally{Db.exec("ALTER TABLE s01_account_email_maintenance_heads DROP CONSTRAINT synthetic_email_failure");}
        state(NotificationChannelsAccountEmailMaintenance.save(secondAdmin,command(22,0,uuid(),null)),22,"EXISTING",0,null,0);check(Db.query("SELECT * FROM s01_account_email_maintenance_revisions WHERE actor_user_id=2").isEmpty(),"second actor owns only initial-null request");
        Map<String,String> before=emailSnapshot();for(long id:List.of(1L,2L,20L,21L,22L)){reject(409,()->NotificationChannelsAccountEmailMaintenance.guardAccountDeletion(id),"maintenance protects target and actor references");reject(409,()->NotificationChannelsAccountEmailPreparation.guardAccountDeletion(id),"existing deletion host delegates maintenance guards");}NotificationChannelsAccountEmailMaintenance.guardAccountDeletion(4);check(before.equals(emailSnapshot()),"deletion guards are read-only");
    }
    static void requiredSelfChangeAndChallenges()throws Exception{
        Map<String,Object> self=save(1,0,uuid(),"AdminA@example.test");check(Boolean.TRUE.equals(self.get("reauthentication_required")),"self change asks for reauthentication");check(Auth.current(admin)==null,"legacy admin self change revokes acting session after committing");admin=login(1);save(2,0,uuid(),"AdminSecond@example.test");secondAdmin=login(2);
        System.setProperty("login.email.mode","required");try{
            admin=verifiedLogin(1);secondAdmin=verifiedLogin(2);Auth.Session original=admin;String firstToken=tokens.get(1L);
            final List<String> codes=new ArrayList<>();NotificationChannelsLoginVerification core=new NotificationChannelsLoginVerification(System::currentTimeMillis,(address,code)->codes.add(code));Auth.Credential proof=Auth.checkedCredential(username(1),PASSWORD);var challenge=core.begin(proof,"synthetic-required-ip");String code=codes.get(0);
            String request=uuid();Map<String,Object> changed=http(admin,"POST",ROUTE,command(1,1,request,"AdminB@example.test"));state(changed,1,"EXISTING",2,"AdminB@example.test",2);check(Boolean.TRUE.equals(changed.get("reauthentication_required")),"required verified self save returns successful reauthentication DTO");check(Auth.get(firstToken)==null&&Auth.current(original)==null&&!Auth.credentialCurrent(proof),"committed required self change revokes verified session and generation");
            admin=verifiedLogin(1);Auth.Session fresh=admin;String freshToken=tokens.get(1L);Map<String,String> before=emailSnapshot();Map<String,Object> replay=save(1,1,request,"AdminB@example.test");check(Boolean.FALSE.equals(replay.get("reauthentication_required")),"replay does not require reauthentication again");check(before.equals(emailSnapshot())&&Auth.get(freshToken)==fresh,"required replay remains write-free and keeps new session");Map<String,Object> noop=save(1,2,uuid(),"AdminB@example.test");check(Boolean.FALSE.equals(noop.get("reauthentication_required"))&&Auth.get(freshToken)==fresh,"required no-op preserves verified session");
            Map<String,Object> back=NotificationChannelsAccountEmailMaintenance.save(secondAdmin,command(1,2,uuid(),"AdminA@example.test"));state(back,1,"EXISTING",3,"AdminA@example.test",3);reject(401,()->core.verify(challenge.challengeId(),code),"A to B to A cannot revive old challenge");reject(401,()->Auth.issueVerified(proof,NotificationChannelsAccountEmailPreparation.loginSnapshot(1)),"old password proof cannot issue after A to B to A");check(Auth.get(freshToken)==null,"other administrator edit revokes target required session");admin=verifiedLogin(1);
        }finally{System.setProperty("login.email.mode","legacy");admin=login(1);secondAdmin=login(2);}
    }
    static void uncertainCommitRevokes()throws Exception{
        for(boolean throwAfterCommit:List.of(true,false)){
            long id=throwAfterCommit?33:34;Auth.Session target=login(id);String token=tokens.get(id);Auth.Credential proof=Auth.checkedCredential(username(id),PASSWORD);Connection actual=Db.get();Field field=Db.class.getDeclaredField("conn");field.setAccessible(true);boolean[] committed={false};
            Connection proxy=(Connection)java.lang.reflect.Proxy.newProxyInstance(S01AccountEmailMaintenanceTest.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                try{if(m.getName().equals("commit")){Object value=m.invoke(actual,a);committed[0]=true;if(throwAfterCommit)throw new SQLException("synthetic uncertain commit");return value;}if(!throwAfterCommit&&committed[0]&&m.getName().equals("setAutoCommit")&&Boolean.TRUE.equals(a[0])){m.invoke(actual,a);throw new SQLException("synthetic restore failure");}return m.invoke(actual,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
            field.set(null,proxy);try{reject(503,()->save(id,0,uuid(),"Commit-"+id+"@example.test"),throwAfterCommit?"unknown commit outcome":"autocommit restoration failure");}finally{field.set(null,actual);if(!actual.getAutoCommit())actual.setAutoCommit(true);}
            check(committed[0],"injected boundary failure occurs after real commit");check(Auth.get(token)==null&&!Auth.credentialCurrent(proof),"committed but failed response conservatively revokes target");check(n(get(id),"revision")==1,"committed history is durable despite response uncertainty");
        }
    }
    static void association(long id)throws Exception{Db.exec("INSERT INTO organization_account_import_people(person_code,source_namespace,source_reference,account_id,batch_key,source_fingerprint,payload,disposition,created_by) VALUES(?,?,?,?,?,?,?,?,?)","person-"+id,"a".repeat(64),"synthetic-ref-"+id,id,"synthetic-batch","b".repeat(64),"{}","create_pending",1L);}
    static void setup()throws Exception{
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();String hash=Auth.hash(PASSWORD);
        for(int id=1;id<=4;id++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",id,username(id),hash,"Synthetic actor "+id,id<=2?"admin":id==3?"viewer":"manager");
        for(long id=20;id<=40;id++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,?)",id,username(id),id==40?null:hash,"Synthetic existing "+id,id==39?"manager":"viewer",id==40?0:1);
        OrganizationAccountImport.init();for(long id=101;id<=110;id++){Db.exec("INSERT INTO users VALUES(?,?,NULL,?,'viewer',0)",id,username(id),"Synthetic imported "+id);association(id);}
        for(String table:List.of("m01_synthetic_guard","s01_notifications","workflow_outbox")){Db.exec("CREATE TABLE "+table+"(id INT PRIMARY KEY,payload VARCHAR(100))");Db.exec("INSERT INTO "+table+" VALUES(1,'synthetic unchanged sentinel')");}
        admin=login(1);secondAdmin=login(2);viewer=login(3);manager=login(4);
    }
    static Path configureData(String[] args,boolean reopen)throws Exception{
        if(args.length!=(reopen?2:1))throw new IllegalArgumentException("Owned synthetic data directory required");Path supplied=Path.of(args[reopen?1:0]);if(!supplied.isAbsolute()||Files.isSymbolicLink(supplied))throw new IllegalArgumentException("Absolute non-symbolic data directory required");Path data=supplied.toRealPath(),parent=data.getParent();
        if(!data.getFileName().toString().equals("data")||parent==null||!parent.getFileName().toString().startsWith("yanxu-s01-email-maintenance.")||!Files.isRegularFile(parent.resolve(".s01-email-maintenance-test-root")))throw new IllegalArgumentException("Directory is not owned by this invocation");
        String prop=System.getProperty("data.dir");if(prop==null||!Path.of(prop).toRealPath().equals(data))throw new IllegalArgumentException("JVM data.dir must already name the owned synthetic directory");
        if(reopen){if(!Files.isRegularFile(data.resolve("training.mv.db")))throw new IllegalArgumentException("Cold reopen requires prior synthetic database");}else try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fresh synthetic directory must be empty");}
        System.setProperty("data.dir",data.toString());return data;
    }
    static void coldReopen()throws Exception{
        List<String> all=new ArrayList<>(PROTECTED_TABLES);all.addAll(EMAIL_TABLES);Map<String,String> before=snapshot(all);admin=login(1);state(get(20),20,"EXISTING",4,"Persisted@example.test",4);state(get(101),101,"IMPORTED",2,"ImportNew@example.test",2);state(save(20,0,FIRST_REQUEST,"Local.Part@example.test"),20,"EXISTING",4,"Persisted@example.test",4);check(before.equals(snapshot(all)),"cold JVM real login, read and old replay change no database row");check(NotificationChannelsAccountEmailPreparation.loginSnapshot(20).email().equals("Persisted@example.test"),"native login source survives cold JVM");System.out.println("S01 account email maintenance cold JVM: "+checks+" checks passed.");
    }
    public static void main(String[] args)throws Exception{
        boolean reopen=args.length>0&&args[0].equals("--reopen");Path data=configureData(args,reopen);boolean opened=false;
        try{String url=Db.get().getMetaData().getURL();opened=true;check(url.startsWith("jdbc:h2:"+data.resolve("training")),"database confined to validated synthetic root");if(reopen){coldReopen();return;}setup();Map<String,String> protectedBefore=snapshot(PROTECTED_TABLES);NotificationChannelsAccountEmailPreparation.init();NotificationChannelsAccountEmailPreparation.init();for(String table:EMAIL_TABLES)check(Db.count(table)==0,"repeated init leaves "+table+" empty");Map<String,String> initial=emailSnapshot();state(get(20),20,"EXISTING",0,null,0);state(get(101),101,"IMPORTED",0,null,0);state(get(40),40,"EXISTING",0,null,0);check(initial.equals(emailSnapshot()),"GET never creates a head, revision or request");
            authorizationAndHttp();validationAndParser();nativeLifecycle();importedCompatibilityAndDuplicates();identityAndHistory();requestLedgerIntegrity();enabledImportedAndDisabledNative();concurrency();sqlFailuresAndDeletion();requiredSelfChangeAndChallenges();uncertainCommitRevokes();
            check(protectedBefore.equals(snapshot(PROTECTED_TABLES)),"all users, import, permissions and unrelated fixture rows are byte-for-byte unchanged");Map<String,String> before=emailSnapshot();Db.get().close();state(get(20),20,"EXISTING",4,"Persisted@example.test",4);check(before.equals(emailSnapshot()),"reconnect and read leave all audit rows unchanged");check(Auth.login(username(101),PASSWORD)==null,"pending imported account remains unable to log in");System.out.println("S01 account email maintenance: "+checks+" checks passed (synthetic H2, real Auth, required self edit, no network/mail/private data).");
        }finally{if(opened)Db.exec("SHUTDOWN");}
    }
}
