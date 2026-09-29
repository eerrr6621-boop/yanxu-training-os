package com.training;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;
import static com.training.OrganizationAccess.*;

/** Real Auth/M01/H2 with synthetic identities and a temporary workflow outbox hook only. */
public final class S01IntegrationTest {
    private static int checks;
    private static Auth.Session admin,filler,leader,bp,team,outsider,auditor;
    private static String configurationVersion;
    private static final Map<Long,String> tokens=new HashMap<>();
    @FunctionalInterface interface Work { void run() throws Exception; }
    static void check(boolean condition,String label) { checks++; if(!condition) throw new AssertionError(label); }
    static void reject(int code,Work work,String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected denial: "+label); }
        catch(Api.ApiException failure) { check(failure.code==code,label+" expected="+code+" actual="+failure.code+" "+failure.getMessage()); }
    }
    static void rejectState(Work work,String label) throws Exception {
        try { work.run(); throw new AssertionError("Expected state rejection: "+label); }
        catch(IllegalStateException expected) { check(true,label); }
    }
    /** No socket or network: Api's already-parsed body and deferred response contract are modeled. */
    static final class Exchange extends HttpExchange {
        final Headers requestHeaders=new Headers(),responseHeaders=new Headers();
        final Map<String,Object> attributes=new HashMap<>();final URI uri;final String method;
        InputStream input=new ByteArrayInputStream(new byte[0]);OutputStream output=new ByteArrayOutputStream();int responseCode=-1;
        Exchange(String method,String path,String token,Map<String,Object> body) {
            this.method=method;this.uri=URI.create(path);
            if(token!=null)requestHeaders.set("Cookie","yx_session="+token);
            if(body!=null){requestHeaders.set("Content-Type","application/json");attributes.put(Api.class.getName()+".body",body);}
        }
        Map<String,Object> response() throws Exception {
            Object pending=attributes.get(Api.class.getName()+".response");
            if(pending==null)throw new AssertionError("Expected deferred API response");
            var field=pending.getClass().getDeclaredField("body");field.setAccessible(true);
            return map(Json.parse(new String((byte[])field.get(pending),StandardCharsets.UTF_8)));
        }
        @Override public Headers getRequestHeaders(){return requestHeaders;}
        @Override public Headers getResponseHeaders(){return responseHeaders;}
        @Override public URI getRequestURI(){return uri;}
        @Override public String getRequestMethod(){return method;}
        @Override public HttpContext getHttpContext(){return null;}
        @Override public void close(){}
        @Override public InputStream getRequestBody(){return input;}
        @Override public OutputStream getResponseBody(){return output;}
        @Override public void sendResponseHeaders(int code,long length){responseCode=code;}
        @Override public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",0);}
        @Override public int getResponseCode(){return responseCode;}
        @Override public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",0);}
        @Override public String getProtocol(){return "HTTP/1.1";}
        @Override public Object getAttribute(String name){return attributes.get(name);}
        @Override public void setAttribute(String name,Object value){attributes.put(name,value);}
        @Override public void setStreams(InputStream i,OutputStream o){input=i;output=o;}
        @Override public HttpPrincipal getPrincipal(){return null;}
    }
    static Exchange exchange(Auth.Session actor,String method,String path,Map<String,Object> body) {return new Exchange(method,path,actor==null?null:tokens.get(actor.uid),body);}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> page) { return (List<Map<String,Object>>)page.get("items"); }
    static long n(Map<String,Object> value,String key) { return ((Number)value.get(key)).longValue(); }
    static String s(Map<String,Object> value,String key) { return Objects.toString(value.get(key),""); }
    static Map<String,Object> canonical(Map<String,Object> value) { return map(Json.parse(Json.write(value))); }
    static Map<String,Object> call(String op,Auth.Session actor,Map<String,Object> body) throws Exception { return WorkflowIntegration.mutate(op,actor,canonical(body)); }
    static Map<String,Object> command(Map<String,Object> demand,String request) { return new LinkedHashMap<>(Map.of("id",demand.get("id"),"expected_version",demand.get("version"),"request_id",request)); }
    static Map<String,Object> approve(Map<String,Object> demand,String action,String request) {
        Map<String,Object> approval=map(demand.get("approval"));
        return new LinkedHashMap<>(Map.of("id",demand.get("id"),"action",action,"expectedVersion",approval.get("version"),"expectedStage",approval.get("stage"),"requestId",request,"comment","合成 S01 验证意见"));
    }
    static Map<String,Object> full(String request) {
        Map<String,Object> b=new LinkedHashMap<>();
        b.put("request_id",request);b.put("title","合成通知需求 "+request);b.put("business_path","direct");b.put("organization_code","001");
        b.put("internal_contact_code","P2");b.put("category_text","业务技能");b.put("delivery_mode_text","线上");b.put("period_text","待协调");
        b.put("duration_minutes","45.00");b.put("participant_count","12");b.put("budget_amount","100.00");b.put("objectives","合成目标");
        b.put("unit","合成客户");b.put("contact","合成联系人");b.put("phone","00000000");b.put("content","合成内容");
        return b;
    }
    static Map<String,Object> submitted(String key) throws Exception {
        Map<String,Object> d=call("draft",filler,full(key+"-draft"));return call("submit",filler,command(d,key+"-submit"));
    }
    static Configuration config(String version,String leaderBinding,String leaderOrg,boolean notifyLeader,boolean demandLeader) {
        RelationRule optional=new RelationRule(false,Set.of("LEADER","BP"),false,false);
        List<Grant> grants=new ArrayList<>();
        for(String role:List.of("FILLER","LEADER","BP","TEAM")) {
            if(!role.equals("LEADER")||demandLeader) grants.add(new Grant("read-"+role,role,"demand.read",Action.VIEW,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
            if(!role.equals("LEADER")||notifyLeader) grants.add(new Grant("notify-"+role,role,"notifications.read",Action.VIEW,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
        }
        grants.add(new Grant("audit-notices","AUDIT","notifications.audit",Action.VIEW,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
        grants.add(new Grant("audit-demand","AUDIT","demand.read",Action.VIEW,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
        grants.add(new Grant("write-filler","FILLER","demand.write",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("write-team","TEAM","demand.write",Action.HANDLE,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
        for(String role:List.of("LEADER","BP")) grants.add(new Grant("review-"+role,role,"approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        grants.add(new Grant("accept","TEAM","demand.accept",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        grants.add(new Grant("out-read","OUT","demand.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("out-notify","OUT","notifications.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        List<RoleRelations> relations=new ArrayList<>();for(String role:List.of("LEADER","BP","TEAM","OUT","AUDIT")) relations.add(new RoleRelations(role,optional,optional));
        relations.add(new RoleRelations("FILLER",new RelationRule(true,Set.of("LEADER"),true,false),new RelationRule(true,Set.of("BP"),true,false)));
        return new Configuration(version,new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),Set.of("FILLER","LEADER","BP","TEAM","OUT","AUDIT"),
            List.of(new Organization("001",null,true),new Organization("002",null,true),new Organization("900",null,true),new Organization("999",null,true)),
            List.of(new Person("P2","001",Set.of(),"P3","P4",Set.of("FILLER"),true),new Person("P3",leaderOrg,Set.of("001"),null,null,Set.of("LEADER"),true),
                    new Person("P4","002",Set.of("001"),null,null,Set.of("BP"),true),new Person("P5","900",Set.of("001"),null,null,Set.of("TEAM"),true),
                    new Person("P6","999",Set.of(),null,null,Set.of("OUT"),true),new Person("P7","002",Set.of(),null,null,Set.of("AUDIT"),true),
                    new Person("P8","002",Set.of("001"),null,null,Set.of("LEADER"),true)),relations,
            List.of(new AccountBinding(2,"P2",true),new AccountBinding(3,leaderBinding,true),new AccountBinding(4,"P4",true),new AccountBinding(5,"P5",true),new AccountBinding(6,"P6",true),new AccountBinding(7,"P7",true)),grants);
    }
    static void publish(String version,String leaderBinding,String leaderOrg,boolean notifyLeader,boolean demandLeader) throws Exception {
        OrganizationAccessStore.publish(admin,configurationVersion,config(version,leaderBinding,leaderOrg,notifyLeader,demandLeader));configurationVersion=version;
        // Existing notifications keep frozen identities; later assertions use fresh logins.
        Auth.Session[] current={admin,filler,leader,bp,team,outsider,auditor};
        for(int i=1;i<current.length;i++)if(Auth.current(current[i])==null){String token=Auth.login("synthetic-s01-"+(i+1),"synthetic-s01-test-password");tokens.put((long)i+1,token);current[i]=Auth.get(token);}
        filler=current[1];leader=current[2];bp=current[3];team=current[4];outsider=current[5];auditor=current[6];
    }
    static Map<String,Object> page(Auth.Session actor) throws Exception { return NotificationChannelsIntegration.list(actor,0,100); }
    static Map<String,Object> forDemand(Auth.Session actor,long demand) throws Exception {
        for(Map<String,Object> row:items(page(actor))) {
            Map<String,Object> target=NotificationChannelsIntegration.target(actor,s(row,"id"));
            if(Long.toString(demand).equals(s(map(target.get("params")),"recordId"))) return row;
        }
        throw new AssertionError("No visible notification for demand "+demand+" / account "+actor.uid);
    }
    static Map<String,Object> append(String key,long demand,Map<String,Object> payload) throws Exception {
        synchronized(Api.MUTATION_LOCK) { return Db.transaction(()->NotificationChannelsIntegration.appendOutboxInTransaction(key,demand,payload)); }
    }
    static Map<String,Long> counts() throws Exception {
        Map<String,Long> result=new TreeMap<>();
        for(String table:List.of("demands","projects","workflow_demands","approval_workflows","approval_events","workflow_requests","workflow_outbox","s01_notification_events","s01_notifications")) result.put(table,Db.count(table));
        return result;
    }
    static Map<String,Object> outbox(long demand,String kind) throws Exception {
        for(Map<String,Object> row:Db.query("SELECT * FROM workflow_outbox WHERE demand_id=? ORDER BY created_at,event_key",demand)) if(kind.equals(s(map(Json.parse(s(row,"payload"))),"kind"))) return row;
        throw new AssertionError("No outbox "+demand+" / "+kind);
    }
    static void unavailable(Auth.Session actor,String id,String label) throws Exception {
        reject(404,()->NotificationChannelsIntegration.detail(actor,id),label+" detail");
        reject(404,()->NotificationChannelsIntegration.read(actor,id),label+" read");
        reject(404,()->NotificationChannelsIntegration.target(actor,id),label+" target");
        check(items(page(actor)).stream().noneMatch(row->id.equals(s(row,"id"))),label+" list filtered");
    }
    static void receipt(Map<String,Object> value,String status,String reason,long notices,String label) {
        check(status.equals(s(value,"status"))&&reason.equals(s(value,"reason"))&&n(value,"noticeCount")==notices,label+" "+value);
    }
    static boolean diagnosticReason(String reason) throws Exception {
        return items(NotificationChannelsIntegration.diagnostics(auditor,0,100)).stream().anyMatch(row->reason.equals(s(row,"reason")));
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Fresh empty test directory required");
        Path data=Path.of(args[0]);try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Test directory must be empty");}
        System.setProperty("data.dir",data.toAbsolutePath().toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64),password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id IDENTITY PRIMARY KEY,title VARCHAR(200),unit VARCHAR(200),contact VARCHAR(64),phone VARCHAR(32),hours DOUBLE,content CLOB,teacher_req CLOB,expect_date VARCHAR(32),status VARCHAR(16),remark CLOB,training_province VARCHAR(64),training_city VARCHAR(64),training_mode VARCHAR(500),training_period VARCHAR(500))");
        Db.exec("CREATE TABLE projects(id IDENTITY PRIMARY KEY,demand_id BIGINT,bid_id BIGINT,title VARCHAR(200),unit VARCHAR(200),hours DOUBLE,amount DOUBLE,start_date VARCHAR(32),end_date VARCHAR(32),owner VARCHAR(64),participant_count INT,delivery_mode VARCHAR(500),status VARCHAR(16),remark CLOB)");
        Db.exec("CREATE TABLE bids(id IDENTITY PRIMARY KEY,demand_id BIGINT)");
        Db.exec("CREATE TABLE questionnaires(id IDENTITY PRIMARY KEY,project_id BIGINT)");
        String synthetic="synthetic-s01-test-password";String hash=Auth.hash(synthetic);Auth.Session[] actors=new Auth.Session[7];
        for(int i=1;i<=8;i++){Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",i,"synthetic-s01-"+i,hash,"SYNTHETIC S01 "+i,i==1?"admin":"viewer");if(i<=7){String token=Auth.login("synthetic-s01-"+i,synthetic);tokens.put((long)i,token);actors[i-1]=Auth.get(token);}}
        admin=actors[0];filler=actors[1];leader=actors[2];bp=actors[3];team=actors[4];outsider=actors[5];auditor=actors[6];
        OrganizationAccessStore.init();WorkflowIntegration.init();NotificationChannelsIntegration.init();NotificationChannelsIntegration.init();
        check(Db.count("s01_notification_events")==0&&Db.count("s01_notifications")==0,"startup creates empty tables without backfill or recipients");
        publish("s01-synthetic-v1","P3","002",true,true);
        check(n(page(leader),"total")==0&&n(page(leader),"unreadCount")==0,"empty inbox metadata");
        reject(403,()->page(admin),"legacy admin has no implicit notification permission");
        reject(403,()->NotificationChannelsIntegration.diagnostics(admin,0,20),"legacy admin has no implicit audit permission");
        reject(403,()->NotificationChannelsIntegration.diagnostics(leader,0,20),"read permission is not audit permission");
        Auth.Session forged=new Auth.Session();forged.uid=3;forged.role="admin";
        reject(401,()->page(forged),"fabricated uid and admin role are not authentication");

        Map<String,Object> d=submitted("main");long id=n(d,"id");Map<String,Object> initial=forDemand(leader,id);String leaderNotice=s(initial,"id");
        check(n(page(leader),"total")==1&&n(page(leader),"unreadCount")==1,"submit creates one leader notice");
        check(n(page(bp),"total")==0&&n(page(team),"total")==0&&n(page(filler),"total")==0,"submit has no guessed recipients");
        check(Boolean.TRUE.equals(NotificationChannelsIntegration.detail(leader,leaderNotice).get("actionable")),"current leader notice actionable");
        reject(401,()->NotificationChannelsIntegration.handle(exchange(null,"GET","/api/notifications",null),leader),"HTTP missing token cannot use supplied session");
        reject(401,()->NotificationChannelsIntegration.handle(exchange(filler,"GET","/api/notifications",null),leader),"HTTP token must match supplied live session");
        reject(400,()->NotificationChannelsIntegration.handle(exchange(leader,"GET","/api/notifications?recipient=2",null),leader),"HTTP caller cannot choose recipient");
        reject(400,()->NotificationChannelsIntegration.handle(exchange(leader,"GET","/api/notifications/"+leaderNotice+"/target?returnUrl=%2Fadmin",null),leader),"HTTP caller cannot choose return URL");
        for(String forbidden:List.of("recipient","content")) reject(400,()->NotificationChannelsIntegration.handle(exchange(leader,"POST","/api/notifications/"+leaderNotice+"/read",Map.of(forbidden,"synthetic")),leader),"HTTP read body rejects "+forbidden);
        reject(405,()->NotificationChannelsIntegration.handle(exchange(leader,"GET","/api/notifications/"+leaderNotice+"/read",null),leader),"GET cannot mark read");
        reject(404,()->NotificationChannelsIntegration.handle(exchange(leader,"GET","/api/notifications/"+leaderNotice+"/unknown",null),leader),"unknown notification subroute denied");
        reject(415,()->NotificationChannelsIntegration.handle(exchange(leader,"POST","/api/notifications/"+leaderNotice+"/read",null),leader),"read requires JSON media type");
        Exchange channels=exchange(leader,"GET","/api/notifications/channels",null);check(NotificationChannelsIntegration.handle(channels,leader),"channels route recognized");
        check(((List<?>)map(channels.response().get("data")).get("channels")).stream().map(S01IntegrationTest::map).anyMatch(row->"EMAIL".equals(s(row,"channel"))&&"adapter_not_connected".equals(s(row,"status"))),"EMAIL remains adapter_not_connected");
        Map<String,Object> destination=NotificationChannelsIntegration.target(leader,leaderNotice);
        check("M03".equals(s(destination,"moduleId"))&&"task".equals(s(map(destination.get("params")),"view")),"approved internal target directs to current task");
        unavailable(outsider,leaderNotice,"cross-organization reader");unavailable(bp,leaderNotice,"same-organization nonrecipient");
        reject(401,()->NotificationChannelsIntegration.detail(forged,leaderNotice),"forged detail denied");
        Map<String,Object> sourceEvent=Db.one("SELECT version,event_json FROM approval_events WHERE workflow_id=? ORDER BY version",id);
        Map<String,Object> tamperedEvent=map(Json.parse(s(sourceEvent,"event_json")));tamperedEvent.put("comment","合成来源篡改检查");
        Db.exec("UPDATE approval_events SET event_json=? WHERE workflow_id=? AND version=?",Json.write(tamperedEvent),id,n(sourceEvent,"version"));
        unavailable(leader,leaderNotice,"source event changed while binding and permissions remain valid");
        Db.exec("UPDATE approval_events SET event_json=? WHERE workflow_id=? AND version=?",s(sourceEvent,"event_json"),id,n(sourceEvent,"version"));
        check(leaderNotice.equals(s(NotificationChannelsIntegration.detail(leader,leaderNotice),"id")),"exact source restoration restores source validation");
        Map<String,Object> workflowBeforeRead=WorkflowIntegration.detail(id,leader);
        Map<String,Object> firstRead=NotificationChannelsIntegration.read(leader,leaderNotice),secondRead=NotificationChannelsIntegration.read(leader,leaderNotice);
        check(!s(firstRead,"readAt").isBlank()&&firstRead.equals(secondRead),"read time first-write idempotent");
        check(n(page(leader),"unreadCount")==0&&canonical(workflowBeforeRead).equals(canonical(WorkflowIntegration.detail(id,leader))),"reading does not approve or alter business state");

        Map<String,Object> originalOutbox=outbox(id,"TODO");Map<String,Object> originalPayload=map(Json.parse(s(originalOutbox,"payload")));String originalKey=s(originalOutbox,"event_key");
        Map<String,Object> frozenRule=Db.one("SELECT event_type,event_intent,event_destination FROM s01_notification_events WHERE event_key=?",originalKey);
        check("REVIEW_REQUIRED".equals(s(frozenRule,"event_type"))&&"ACTION".equals(s(frozenRule,"event_intent"))&&"M03".equals(s(frozenRule,"event_destination")),"event type, intent and destination persist with original rule");
        Map<String,Long> boundaryBefore=counts();
        rejectState(()->{synchronized(Api.MUTATION_LOCK){NotificationChannelsIntegration.appendOutboxInTransaction(originalKey,id,originalPayload);}},"append with lock alone is rejected");
        rejectState(()->Db.transaction(()->NotificationChannelsIntegration.appendOutboxInTransaction(originalKey,id,originalPayload)),"append with transaction alone is rejected");
        rejectState(()->{synchronized(Api.MUTATION_LOCK){Db.transaction(()->{Db.exec("INSERT INTO workflow_outbox(event_key,demand_id,payload,status,created_at) VALUES('s01-init-sentinel',?,?,'PENDING',?)",id,Json.write(originalPayload),Instant.now().toString());NotificationChannelsIntegration.init();return null;});}},"init rejects caller transaction before executing DDL");
        check(boundaryBefore.equals(counts())&&Db.one("SELECT event_key FROM workflow_outbox WHERE event_key='s01-init-sentinel'")==null,"transaction boundary failures leave no writes or accidental commit");
        Map<String,Long> beforeReplay=counts();Map<String,Object> frozen=append(originalKey,id,originalPayload);
        check("READY".equals(s(frozen,"status"))&&n(frozen,"noticeCount")==1,"frozen successful receipt replays");
        check(frozen.equals(append(originalKey,id,canonical(originalPayload)))&&beforeReplay.equals(counts()),"replay neither duplicates notices nor rewrites receipt");
        Map<String,Object> changed=new LinkedHashMap<>(originalPayload);changed.put("recipient","P4");
        reject(409,()->append(originalKey,id,changed),"same key different payload conflicts");
        reject(409,()->append(originalKey,id+999,originalPayload),"same key different demand conflicts");
        d=call("approval",leader,approve(d,"APPROVE","main-leader"));Map<String,Object> bpNotice=forDemand(bp,id);
        check(!Boolean.TRUE.equals(NotificationChannelsIntegration.detail(leader,leaderNotice).get("actionable")),"old leader task becomes informational");
        check("detail".equals(s(map(NotificationChannelsIntegration.target(leader,leaderNotice).get("params")),"view")),"old target uses current business state");
        d=call("approval",bp,approve(d,"APPROVE","main-bp"));
        check(n(page(team),"total")==0&&diagnosticReason("TEAM_RECIPIENTS_UNCONFIGURED"),"BP approval blocks unconfigured training-team recipients");
        Map<String,Object> teamOutbox=outbox(id,"TEAM_READY");receipt(append(s(teamOutbox,"event_key"),id,map(Json.parse(s(teamOutbox,"payload")))),"BLOCKED","TEAM_RECIPIENTS_UNCONFIGURED",0,"blocked receipt persists without broadcast");
        Map<String,Object> accepted=command(d,"main-accept");accepted.put("team_code","900");d=call("accept",team,accepted);
        Map<String,Object> acceptanceNotice=forDemand(filler,id);
        check("HANDOVER_ACCEPTED".equals(s(acceptanceNotice,"type"))&&!Boolean.TRUE.equals(acceptanceNotice.get("actionable")),"team acceptance informs original submitter");
        check("M02".equals(s(NotificationChannelsIntegration.target(filler,s(acceptanceNotice,"id")),"moduleId")),"acceptance stays on original demand surface");

        Map<String,Object> returned=submitted("return");long returnedId=n(returned,"id");returned=call("approval",leader,approve(returned,"RETURN","return-leader"));
        Map<String,Object> returnNotice=forDemand(filler,returnedId);check("RETURNED".equals(s(returnNotice,"type")),"return informs original submitter");
        returned=call("approval",filler,approve(returned,"RESUBMIT","return-resubmit"));
        check(items(page(leader)).stream().filter(row->Boolean.TRUE.equals(row.get("actionable"))).count()==1,"resubmission creates current leader task without reviving resolved notices");
        Map<String,Object> revision=command(returned,"return-revise");revision.put("title","合成重大内容变更");revision.put("change_comment","合成重审说明");
        returned=call("draft",filler,revision);check(diagnosticReason("UNSUPPORTED_BUSINESS_MOMENT"),"unapproved REVISE moment does not reuse TODO as a new approved rule");

        Map<String,Object> historicalPayload=Map.of("kind","TODO","target","PARTICIPANT","recipient","P3","event_version",1);
        String historicalKey="s01-test-historical-unproven";
        Db.exec("INSERT INTO workflow_outbox(event_key,demand_id,payload,status,created_at) VALUES(?,?,?,'PENDING',?)",historicalKey,id,Json.write(historicalPayload),Instant.now().toString());
        Map<String,Long> beforeGet=counts();Map<String,Object> historicalBefore=Db.one("SELECT * FROM workflow_outbox WHERE event_key=?",historicalKey);
        page(leader);NotificationChannelsIntegration.detail(leader,leaderNotice);NotificationChannelsIntegration.target(leader,leaderNotice);NotificationChannelsIntegration.diagnostics(auditor,0,100);
        check(beforeGet.equals(counts())&&historicalBefore.equals(Db.one("SELECT * FROM workflow_outbox WHERE event_key=?",historicalKey)),"GET operations never consume or backfill old outbox");
        receipt(append(historicalKey,id,historicalPayload),"BLOCKED","HISTORICAL_BINDING_UNPROVEN",0,"old outbox cannot be rebound to current roster");
        check(beforeGet.equals(counts()),"old outbox refusal creates no S01 batch");
        Map<String,Object> reminder=append("s01-test-reminder",id,Map.of("kind","REMINDER","recipient","P3"));
        receipt(reminder,"BLOCKED","UNSUPPORTED_BUSINESS_MOMENT",0,"unapproved reminder moment blocked");

        Map<String,Object> rollbackDraft=call("draft",filler,full("rollback-draft"));Map<String,Long> beforeRollback=counts();
        System.setProperty("s01.test.failAfterAppend","true");
        try { reject(409,()->call("submit",filler,command(rollbackDraft,"rollback-submit")),"failure after append aborts caller transaction"); }
        finally { System.clearProperty("s01.test.failAfterAppend"); }
        check(beforeRollback.equals(counts()),"workflow, outbox, event and notification inserts all roll back together");
        check(WorkflowIntegration.detail(n(rollbackDraft,"id"),filler).get("approval")==null,"rollback leaves original draft and no approval");
        Map<String,Object> recovered=call("submit",filler,command(rollbackDraft,"rollback-submit"));forDemand(leader,n(recovered,"id"));

        Map<String,Object> concurrent=submitted("concurrent");Map<String,Object> sameApproval=approve(concurrent,"APPROVE","concurrent-identical");
        long eventsBefore=Db.count("approval_events"),noticesBefore=Db.count("s01_notifications");
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Map<String,Object>>> futures=new ArrayList<>();
        try { for(int i=0;i<2;i++)futures.add(workers.submit(()->{start.await();return call("approval",leader,sameApproval);}));start.countDown();for(Future<Map<String,Object>> future:futures)future.get(); }
        finally { workers.shutdownNow(); }
        check(Db.count("approval_events")==eventsBefore+1&&Db.count("s01_notifications")==noticesBefore+1,"concurrent identical approval has one event and one notification");
        Map<String,Long> persisted=counts();Db.get().close();
        check(persisted.equals(counts())&&firstRead.equals(NotificationChannelsIntegration.read(leader,leaderNotice)),"batches and original read time survive reconnect");
        reject(409,()->NotificationChannelsIntegration.guardAccountDeletion(3),"referenced account deletion blocked");
        NotificationChannelsIntegration.guardAccountDeletion(8);check(true,"unreferenced account deletion guard permits host decision");

        publish("s01-synthetic-v2","P8","002",true,true);unavailable(leader,leaderNotice,"account rebound to another person");
        publish("s01-synthetic-v3","P3","002",true,true);unavailable(leader,leaderNotice,"rebind then restore does not restore historical entitlement");
        Map<String,Object> reboundDemand=submitted("after-rebind");String reboundNotice=s(forDemand(leader,n(reboundDemand,"id")),"id");
        publish("s01-synthetic-v4","P3","002",false,true);unavailable(leader,reboundNotice,"notification permission removed");
        long noticesWithoutPermission=Db.count("s01_notifications");Map<String,Object> noNotificationPermission=submitted("without-notification-grant");
        check("LEADER_PENDING".equals(s(map(noNotificationPermission.get("approval")),"status"))&&Db.count("s01_notifications")==noticesWithoutPermission,"missing notification grant does not block authorized business submission or generate notice");
        Map<String,Object> blockedPermission=outbox(n(noNotificationPermission,"id"),"TODO");
        receipt(append(s(blockedPermission,"event_key"),n(noNotificationPermission,"id"),map(Json.parse(s(blockedPermission,"payload")))),"BLOCKED","RECIPIENT_BINDING_OR_PERMISSION_UNPROVEN",0,"missing notification grant produces explicit blocked receipt");
        publish("s01-synthetic-v5","P3","002",true,true);unavailable(leader,reboundNotice,"notification permission restoration does not revive history");
        Map<String,Object> permittedDemand=submitted("after-notify-restore");String permittedNotice=s(forDemand(leader,n(permittedDemand,"id")),"id");
        publish("s01-synthetic-v6","P3","002",true,false);unavailable(leader,permittedNotice,"demand permission removed");
        publish("s01-synthetic-v7","P3","002",true,true);unavailable(leader,permittedNotice,"demand permission restoration does not revive history");
        Map<String,Object> sourceDemand=submitted("source-change");long sourceId=n(sourceDemand,"id");String sourceNotice=s(forDemand(leader,sourceId),"id");
        Db.exec("UPDATE workflow_demands SET organization_code='999' WHERE demand_id=?",sourceId);
        unavailable(leader,sourceNotice,"source record moved outside frozen organization");
        Db.exec("UPDATE workflow_demands SET organization_code='001' WHERE demand_id=?",sourceId);
        publish("s01-synthetic-v8","P3","900",true,true);unavailable(leader,sourceNotice,"person organization changed");
        publish("s01-synthetic-v9","P3","002",true,true);unavailable(leader,sourceNotice,"person organization restore preserves broken historical chain");
        check(frozen.equals(append(originalKey,id,originalPayload)),"receipt replay remains frozen despite later configuration changes");
        Map<String,Object> finalDemand=submitted("fresh-after-history");forDemand(leader,n(finalDemand,"id"));
        Map<String,Object> firstPage=NotificationChannelsIntegration.list(leader,0,1),secondPage=NotificationChannelsIntegration.list(leader,1,1);
        check(n(firstPage,"offset")==0&&n(firstPage,"limit")==1&&items(firstPage).size()<=1,"bounded pagination metadata");
        check(items(firstPage).isEmpty()||items(secondPage).stream().noneMatch(row->s(row,"id").equals(s(items(firstPage).get(0),"id"))),"adjacent pages do not duplicate notification");
        reject(400,()->NotificationChannelsIntegration.list(leader,-1,1),"negative offset rejected");
        reject(400,()->NotificationChannelsIntegration.list(leader,0,101),"unbounded page limit rejected");
        // More than one adapter scan chunk, using genuine source events, then synthetic
        // source moves. New inaccessible rows must not consume visible pages or counts.
        Map<String,Object> beforeChunkInbox=page(leader),beforeChunkDiagnostics=NotificationChannelsIntegration.diagnostics(auditor,0,100);
        for(int i=0;i<201;i++) {
            Map<String,Object> chunkDemand=submitted("chunk-filter-"+i);long chunkId=n(chunkDemand,"id");
            Db.exec("UPDATE workflow_demands SET organization_code='999' WHERE demand_id=?",chunkId);
            append("s01-chunk-blocked-"+i,chunkId,Map.of("kind","REMINDER","recipient","P3"));
        }
        check(beforeChunkInbox.equals(page(leader)),"inbox filters inaccessible records across multiple 200-row chunks before totals and paging");
        check(beforeChunkDiagnostics.equals(NotificationChannelsIntegration.diagnostics(auditor,0,100)),"diagnostics filters unauthorized organizations across chunks without leaking totals");
        Map<String,Object> farPage=NotificationChannelsIntegration.list(leader,Integer.MAX_VALUE,1);
        check(items(farPage).isEmpty()&&n(farPage,"total")==n(beforeChunkInbox,"total"),"large valid offset preserves authorized total and returns empty page");
        String activeNotice=s(forDemand(leader,n(finalDemand,"id")),"id");Db.exec("UPDATE users SET status=0 WHERE id=3");
        reject(401,()->page(leader),"disabled account cannot list notifications");
        reject(401,()->NotificationChannelsIntegration.detail(leader,activeNotice),"disabled account cannot read detail");
        reject(401,()->NotificationChannelsIntegration.read(leader,activeNotice),"disabled account cannot mark read");
        reject(401,()->NotificationChannelsIntegration.target(leader,activeNotice),"disabled account cannot resolve target");
        Db.exec("SHUTDOWN");System.out.println("S01Integration: "+checks+" checks passed (isolated synthetic H2; temporary workflow outbox wiring)");
    }
}
