package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** No Db.init, production data, listeners, real identities or stored secrets. */
public final class M08DeliverySourceTest {
    private static final String PASSWORD="M08-SYNTHETIC-ONLY";
    private static Auth.Session admin,full,writer,other; private static String fullToken,writerToken;
    private static int checks,scenarios,configVersion; private static long request;
    private static final long PROJECT=101;
    private static Map<String,Object> initial;
    @FunctionalInterface interface Run {void run() throws Exception;}
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void eq(Object a,Object b,String msg){check(Objects.equals(a,b),msg);}
    static long n(Object n){return ((Number)n).longValue();}
    @SuppressWarnings("unchecked") static Map<String,Object> m(Object o){return (Map<String,Object>)o;}
    static void rejects(int status,Run run) throws Exception {checks++;try{run.run();throw new AssertionError("expected rejection "+status);}catch(Api.ApiException e){if(e.code!=status)throw new AssertionError("wrong status "+e.code);}}
    static void run(String label,Run run) throws Exception{run.run();scenarios++;System.out.println("PASS "+label);}
    static Map<String,Object> body(long project,long version,String request){return new LinkedHashMap<>(Map.of("project_id",project,"expected_version",version,"request_id",request,"content",Map.of("achievements","SYNTHETIC SUMMARY")));}
    static Map<String,Object> save(Auth.Session s,long p) throws Exception{return TrainingSummariesIntegration.mutate("save",s,body(p,n(TrainingSummariesIntegration.read(s,p).get("version")),"save-"+(++request)));}
    static Map<String,Object> refresh(Auth.Session s,long p) throws Exception{return TrainingSummariesIntegration.mutate("refresh",s,Map.of("project_id",p,"expected_version",n(TrainingSummariesIntegration.read(s,p).get("version")),"request_id","refresh-"+(++request)));}
    static Map<String,Object> read() throws Exception{return TrainingSummariesIntegration.read(full,PROJECT);}
    static Map<String,Object> delivery(Map<String,Object> response){return m(m(response.get("sources")).get("delivery"));}
    static Map<String,Object> value(Map<String,Object> response){return m(delivery(response).get("value"));}
    static String persisted(long revision) throws Exception{return Db.one("SELECT sources_json FROM m08_summary_revisions WHERE project_id=101 AND revision=?",revision).get("sources_json").toString();}
    static Map<String,Object> source() throws Exception{return DeliverySettlementIntegration.summaryDeliverySource(full,PROJECT);}
    static void decimal(String expected,Object actual){if(expected==null)eq(null,actual,"unknown is null");else check(actual instanceof String&&new BigDecimal(expected).compareTo(new BigDecimal(actual.toString()))==0,"exact decimal");}
    static Configuration config(boolean delivery) {
        Set<String> roles=Set.of("FULL","WRITER");RelationRule relation=new RelationRule(false,roles,false,false);
        List<Grant> grants=new ArrayList<>();
        for(String role:roles)for(var perm:List.of(SummaryPermission.READ,SummaryPermission.EDIT))grants.add(new Grant(role+perm.name(),role,perm.resource(),perm.action(),Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        for(String resource:List.of("delivery.write","delivery.verify"))grants.add(new Grant(resource,"FULL",resource,Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        if(delivery)grants.add(new Grant("delivery-read","FULL","delivery.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        return new Configuration("M08-SOURCE-"+(++configVersion),new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),roles,
                List.of(new Organization("001",null,true),new Organization("002",null,true)),
                List.of(new Person("0002","001",Set.of(),null,null,Set.of("FULL"),true),new Person("0003","001",Set.of(),null,null,Set.of("WRITER"),true),new Person("0004","002",Set.of(),null,null,Set.of("FULL"),true)),
                List.of(new RoleRelations("FULL",relation,relation),new RoleRelations("WRITER",relation,relation)),
                List.of(new AccountBinding(2,"0002",true),new AccountBinding(3,"0003",true),new AccountBinding(4,"0004",true)),grants);
    }
    static void publish(boolean delivery) throws Exception {
        admin=Auth.get(Auth.login("u1",PASSWORD));Configuration old=OrganizationAccessStore.configuration();OrganizationAccessStore.publish(admin,old==null?null:old.version(),config(delivery));
        fullToken=Auth.login("u2",PASSWORD);writerToken=Auth.login("u3",PASSWORD);full=Auth.get(fullToken);writer=Auth.get(writerToken);other=Auth.get(Auth.login("u4",PASSWORD));
    }
    static void fixture(Path path) throws Exception {
        System.setProperty("data.dir",path.toString());check(Db.get().getMetaData().getURL().contains(path.toString()),"isolated H2");
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64),password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,title VARCHAR(200),status VARCHAR(32),start_date VARCHAR(32),end_date VARCHAR(32),participant_count INT)");
        Db.exec("CREATE TABLE workflow_demands(demand_id BIGINT PRIMARY KEY,organization_code VARCHAR(120),data_revision BIGINT,version BIGINT,draft BOOLEAN)");
        Db.exec("CREATE TABLE workflow_acceptances(demand_id BIGINT PRIMARY KEY,project_id BIGINT,data_revision BIGINT,team_code VARCHAR(120),actor_code VARCHAR(120),created_at VARCHAR(40))");
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,status VARCHAR(32))");
        Db.exec("CREATE TABLE dispatches(id BIGINT PRIMARY KEY,project_id BIGINT REFERENCES projects(id),teacher_id BIGINT,teach_date VARCHAR(32),status VARCHAR(32),hours DOUBLE)");
        Db.exec("CREATE TABLE fees(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT)");
        String hash=Auth.hash(PASSWORD);for(int id=1;id<=4;id++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",id,"u"+id,hash,"SYNTHETIC",id==1?"admin":"viewer");
        for(long p=101;p<=105;p++){Db.exec("INSERT INTO projects VALUES(?,?,'SYNTHETIC','进行中','2026-09-01','2026-09-10',20)",p,p);Db.exec("INSERT INTO workflow_demands VALUES(?,'001',1,1,FALSE)",p);Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,1,'TEAM','0002','2026-09-01T00:00:00Z')",p,p);}
        Db.exec("INSERT INTO teachers VALUES(1,'在库')");OrganizationAccessStore.init();DeliverySettlementIntegration.init();TrainingSummariesIntegration.init();publish(true);
    }
    static void dispatch(long id,String day,String status) throws Exception{Db.exec("INSERT INTO dispatches VALUES(?,101,1,?,?,99999)",id,day,status);}
    static void fact(long id,String estimated,String planned,String minutes,String payable) throws Exception {
        Map<String,Object> row=Db.one("SELECT revision FROM m05_delivery_facts WHERE dispatch_id=?",id);
        Map<String,Object>b=new LinkedHashMap<>();b.put("dispatch_id",id);b.put("expected_version",row==null?0:row.get("revision"));b.put("request_id","fact-"+(++request));
        b.put("estimated_hours",estimated);b.put("planned_hours",planned);b.put("actual_minutes",minutes);b.put("payable_hours",payable);
        DeliverySettlementIntegration.mutate("save",full,b);
    }
    static void verify(long id) throws Exception{DeliverySettlementIntegration.mutate("verify",full,Map.of("dispatch_id",id,"expected_version",Db.one("SELECT revision FROM m05_delivery_facts WHERE dispatch_id=?",id).get("revision"),"request_id","verify-"+(++request),"evidence_code","PRIVATE-VERIFY-EVIDENCE"));}
    static void hidden(Map<String,Object> response) {
        Map<String,Object>d=delivery(response);eq("UNAVAILABLE",d.get("status"),"hidden unavailable");eq("DELIVERY_READ_REQUIRED",d.get("reason"),"hidden reason");eq(null,d.get("value"),"no facts");eq(Set.of("status","reason","value"),d.keySet(),"no fingerprints");eq(null,m(response.get("sources")).get("source_version"),"outer fingerprint hidden");
        if(response.get("current")!=null)eq(d,m(m(m(response.get("current")).get("sources")).get("delivery")),"nested hidden too");
    }
    static void privacy(Map<String,Object> source){String json=Json.write(source);for(String privateValue:List.of("PRIVATE-VERIFY-EVIDENCE","actor_code","account_id","teacher_id","instructor_code","minutes","fee_rate","amount","TEACHER-"))check(!json.contains(privateValue),"no private field "+privateValue);}
    public static void main(String[] args) throws Exception {
        Path path=Path.of(args[0]).toAbsolutePath().normalize();if(!Files.isDirectory(path)||Files.isSymbolicLink(path))throw new IllegalArgumentException();try(var files=Files.list(path)){if(files.findAny().isPresent())throw new IllegalArgumentException();}
        try{fixture(path);
            run("trusted empty and independent permissions",()->{Map<String,Object>s=source();eq("AVAILABLE",s.get("status"),"available empty");eq("NO_DISPATCHES",s.get("reason"),"true no rows");for(String h:List.of("estimated","planned","actual","payable"))decimal("0",m(s.get("value")).get(h));privacy(s);rejects(403,()->DeliverySettlementIntegration.summaryDeliverySource(writer,101));rejects(403,()->DeliverySettlementIntegration.summaryDeliverySource(other,101));rejects(403,()->TrainingSummariesIntegration.read(admin,101));hidden(save(writer,102));});
            run("mixed population with explicit unknown",()->{
                String yesterday=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
                dispatch(1,yesterday,"已确认");fact(1,"3","2","90","1.5");verify(1);Db.exec("UPDATE dispatches SET status='已完成' WHERE id=1");
                dispatch(2,yesterday,"已确认");fact(2,"4","3","45",null);
                dispatch(3,yesterday,"已拒绝");dispatch(4,yesterday,"已完成");
                Map<String,Object>v=m(source().get("value"));eq(3L,n(v.get("dispatch_count")),"effective count");eq(1L,n(v.get("rejected_count")),"rejected count");eq(1L,n(v.get("reviewed_completed_count")),"checked count");eq(1L,n(v.get("pending_count")),"pending count");eq(1L,n(v.get("unverified_completed_count")),"unknown completed");for(String h:List.of("estimated","planned","actual","payable"))decimal(null,v.get(h));
                Db.exec("UPDATE dispatches SET status='已拒绝' WHERE id=4");v=m(source().get("value"));decimal("7",v.get("estimated"));decimal("5",v.get("planned"));decimal("2",v.get("actual"));decimal("1.5",v.get("payable"));privacy(source());
                initial=body(101,0,"initial");TrainingSummariesIntegration.mutate("save",full,initial);check(persisted(1).length()<20000,"compact source");
            });
            run("ordinary save freezes sources explicit refresh changes",()->{
                String frozen=persisted(1);String version=delivery(read()).get("source_version").toString();
                fact(2,"5","4","60",null);check(Boolean.TRUE.equals(read().get("source_changed")),"fact revision change detected");
                save(full,101);eq(frozen,persisted(2),"save retains original source bytes");refresh(full,101);check(!frozen.equals(persisted(3)),"refresh updates sources");
                decimal("8",value(read()).get("estimated"));decimal("6",value(read()).get("planned"));decimal("2",value(read()).get("actual"));
                eq(version,delivery(TrainingSummariesIntegration.revision(full,101,1)).get("source_version"),"old snapshot unchanged");
            });
            run("verification new revision rejection and revocation detected",()->{
                verify(2);Db.exec("UPDATE dispatches SET status='已完成' WHERE id=2");check(Boolean.TRUE.equals(read().get("source_changed")),"verification and completion change");refresh(full,101);decimal("3.33",value(read()).get("actual"));decimal(null,value(read()).get("payable"));
                fact(2,"5","4","60",null);check(Boolean.TRUE.equals(read().get("source_changed")),"revoked verification change");refresh(full,101);decimal(null,value(read()).get("actual"));eq(1L,n(value(read()).get("unverified_completed_count")),"revoked excluded");
                Db.exec("UPDATE dispatches SET status='已拒绝' WHERE id=2");check(Boolean.TRUE.equals(read().get("source_changed")),"rejection change");refresh(full,101);decimal("3",value(read()).get("estimated"));decimal("2",value(read()).get("actual"));
            });
            run("hidden views and saves cannot erase frozen sources",()->{
                long v=n(read().get("version"));String raw=persisted(v);Map<String,Object>hiddenRead=TrainingSummariesIntegration.read(writer,101);hidden(hiddenRead);eq(false,hiddenRead.get("source_changed"),"no hidden diff");
                hidden(TrainingSummariesIntegration.revision(writer,101,1));hidden(save(writer,101));eq(raw,persisted(v+1),"hidden save preserves full source");
                Db.exec("UPDATE projects SET participant_count=21 WHERE id=101");check(Boolean.TRUE.equals(TrainingSummariesIntegration.read(writer,101).get("source_changed")),"visible facts still detectable");
                hidden(refresh(writer,101));eq(m(Json.parse(raw)).get("delivery"),m(Json.parse(persisted(v+2))).get("delivery"),"hidden refresh preserves delivery");
                Db.exec("UPDATE dispatches SET status='已确认' WHERE id=3");eq(false,TrainingSummariesIntegration.read(writer,101).get("source_changed"),"hidden change does not leak");
                hidden(TrainingSummariesIntegration.read(writer,101));check(!Json.write(TrainingSummariesIntegration.history(writer,101,0,20)).contains("source_version"),"history no sources");
            });
            run("permission revocation hides historical and replay payloads",()->{
                Auth.Session old=full;publish(false);rejects(401,()->TrainingSummariesIntegration.read(old,101));
                hidden(read());hidden(TrainingSummariesIntegration.revision(full,101,1));hidden(TrainingSummariesIntegration.mutate("save",full,initial));
                long v=n(read().get("version"));String raw=persisted(v);hidden(save(full,101));eq(raw,persisted(v+1),"revoked save preserves raw source");
                hidden(refresh(full,101));eq(m(Json.parse(raw)).get("delivery"),m(Json.parse(persisted(v+2))).get("delivery"),"revoked refresh retains old source");publish(true);check("AVAILABLE".equals(delivery(read()).get("status")),"newly authorized reads frozen source");
            });
            run("future missing and unknown quantities preserve meaning",()->{
                Db.exec("UPDATE dispatches SET status='已拒绝' WHERE id=3");
                String tomorrow=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString();dispatch(5,tomorrow,"已确认");fact(5,"1","1","45","1");
                Db.exec("UPDATE dispatches SET status='已完成' WHERE id=5");Map<String,Object>v=m(source().get("value"));eq(1L,n(v.get("unverified_completed_count")),"future not counted as reviewed");decimal(null,v.get("actual"));Db.exec("UPDATE dispatches SET status='已拒绝' WHERE id=5");
                dispatch(6,null,"已确认");v=m(source().get("value"));decimal(null,v.get("planned"));decimal("2",v.get("actual"));Db.exec("UPDATE dispatches SET status='已拒绝' WHERE id=6");
            });
            run("future verified teaching cannot enter actual totals",()->{
                Map<String,Object>row=Db.one("SELECT revision,payload FROM m05_delivery_facts WHERE dispatch_id=1");String raw=row.get("payload").toString();long revision=n(row.get("revision"));Map<String,Object>fact=m(Json.parse(raw));String day=fact.get("service_date").toString();String future=LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2).toString();fact.put("service_date",future);String changed=Json.write(fact);
                Db.exec("UPDATE dispatches SET teach_date=? WHERE id=1",future);Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=1",changed);Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=1 AND revision=?",changed,revision);
                try{Map<String,Object>v=m(source().get("value"));decimal(null,v.get("actual"));decimal(null,v.get("payable"));eq(0L,n(v.get("reviewed_completed_count")),"verified future excluded");eq(1L,n(v.get("unverified_completed_count")),"future marks incomplete coverage");}
                finally{Db.exec("UPDATE dispatches SET teach_date=? WHERE id=1",day);Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=1",raw);Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=1 AND revision=?",raw,revision);}
            });
            run("tampered configured evidence and history are rejected",M08DeliverySourceTest::corruption);
            run("legacy unavailable snapshot remains byte compatible",()->legacy(Path.of(args[1])));
            run("HTTP all source return paths project current permission",M08DeliverySourceTest::http);
            run("post-write permission change rolls back full transaction",()->{
                long version=n(read().get("version"));String original=persisted(version);
                Db.exec("CREATE TRIGGER m08_delivery_revocation AFTER INSERT ON m08_summary_revisions FOR EACH ROW CALL 'com.training.M08DeliverySourceTest$RevokeDuringWrite'");
                try{rejects(401,()->save(full,101));}finally{Db.exec("DROP TRIGGER m08_delivery_revocation");}
                check(n(Db.one("SELECT status FROM users WHERE id=2").get("status"))==1,"user change rolled back");fullToken=Auth.login("u2",PASSWORD);full=Auth.get(fullToken);
                eq(version,n(read().get("version")),"summary head rolled back");eq(original,persisted(version),"original source intact");
            });
            run("stored corruption cannot be hidden by redaction",()->{
                String original=persisted(1);Db.exec("UPDATE m08_summary_revisions SET sources_json='{}' WHERE project_id=101 AND revision=1");
                try{rejects(409,()->TrainingSummariesIntegration.read(writer,101));rejects(409,()->read());}finally{Db.exec("UPDATE m08_summary_revisions SET sources_json=? WHERE project_id=101 AND revision=1",original);}
            });
            run("empty versus all rejected and bounded snapshot",()->{
                Db.exec("UPDATE dispatches SET status='已拒绝' WHERE project_id=101");Map<String,Object>s=source();eq(null,s.get("reason"),"rejected is not empty");eq(0L,n(m(s.get("value")).get("dispatch_count")),"all rejected zero population");for(String h:List.of("estimated","planned","actual","payable"))decimal("0",m(s.get("value")).get(h));
                Db.exec("INSERT INTO dispatches SELECT 1000+X,101,1,'2026-09-01','已拒绝',99999 FROM SYSTEM_RANGE(1,1001)");try{rejects(409,()->source());hidden(TrainingSummariesIntegration.read(writer,101));}finally{Db.exec("DELETE FROM dispatches WHERE id>1000");}
            });
            run("reopen preserves immutable sources",()->{String original=persisted(1);Db.get().close();TrainingSummariesIntegration.init();eq(original,persisted(1),"database restart");privacy(delivery(read()));});
            System.out.println("M08DeliverySource: "+scenarios+" scenarios, "+checks+" checks passed (isolated H2, no network listener)");
        }finally{if(!Db.get().isClosed())Db.get().close();}
    }
    static void corruption() throws Exception {
        Db.exec("UPDATE dispatches SET project_id=102 WHERE id=1");try{rejects(409,()->source());}finally{Db.exec("UPDATE dispatches SET project_id=101 WHERE id=1");}
        Db.exec("UPDATE dispatches SET project_id=NULL WHERE id=1");try{rejects(409,()->source());}finally{Db.exec("UPDATE dispatches SET project_id=101 WHERE id=1");}

        Map<String,Object>row=Db.one("SELECT revision,payload FROM m05_delivery_facts WHERE dispatch_id=1");String original=row.get("payload").toString();long rev=n(row.get("revision"));
        for(String kind:List.of("synthetic","hours","conversion","verification","actor","revision","association")){
            Map<String,Object>p=m(Json.parse(original));
            switch(kind){case "synthetic"->p.put("data_mode","SYNTHETIC_DEMO");case "hours"->m(p.get("hours")).put("actual","99999");case "conversion"->m(p.get("conversion")).put("rule_version","UNTRUSTED");case "verification"->p.put("verification",null);case "actor"->m(p.get("verification")).put("actor_code","OTHER");case "revision"->p.put("revision",rev+1);case "association"->p.put("organization_code","002");}
            String changed=Json.write(p);Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=1",changed);Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=1 AND revision=?",changed,rev);
            try{rejects(409,()->source());rejects(409,()->read());hidden(TrainingSummariesIntegration.read(writer,101));}finally{Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=1",original);Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=1 AND revision=?",original,rev);}
        }
        Db.exec("UPDATE m05_fact_revisions SET event_type='SAVE' WHERE dispatch_id=1 AND revision=?",rev);try{rejects(409,()->source());}finally{Db.exec("UPDATE m05_fact_revisions SET event_type='VERIFY' WHERE dispatch_id=1 AND revision=?",rev);}
        Db.exec("UPDATE m05_fact_revisions SET payload='{}' WHERE dispatch_id=1 AND revision=?",rev);try{rejects(409,()->source());}finally{Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=1 AND revision=?",original,rev);}
        Db.exec("UPDATE dispatches SET teach_date='2026-01-01' WHERE id=1");try{rejects(409,()->source());}finally{Db.exec("UPDATE dispatches SET teach_date=? WHERE id=1",m(Json.parse(original)).get("service_date"));}
    }
    static Object normalized(Object v){if(v instanceof Map<?,?>in){Map<String,Object>o=new TreeMap<>();in.forEach((k,a)->o.put(k.toString(),normalized(a)));return o;}if(v instanceof List<?>l)return l.stream().map(M08DeliverySourceTest::normalized).toList();if(v instanceof Number n)return new BigDecimal(n.toString()).stripTrailingZeros();return v;}
    static String canonical(Object v){return Json.write(normalized(v));}
    static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    static void legacy(Path ignored)throws Exception{
        // Construct the exact previous on-disk schema/hash; no production migration or mass rewrite.
        save(writer,103);Map<String,Object>row=Db.one("SELECT * FROM m08_summary_revisions WHERE project_id=103 AND revision=1");Map<String,Object>sources=m(Json.parse(row.get("sources_json").toString()));
        Map<String,Object>d=new LinkedHashMap<>();d.put("status","UNAVAILABLE");d.put("reason","M05_SNAPSHOT_NOT_CONNECTED");d.put("value",null);sources.put("delivery",d);sources.remove("source_version");sources.put("source_version",hash(canonical(sources)));String json=canonical(sources);
        Map<String,Object>record=new LinkedHashMap<>();record.put("format","M08-DRAFT-20260922-1");record.put("project_id",103);record.put("revision",1);record.put("operation",row.get("operation"));record.put("actor_code",row.get("actor_code"));record.put("account_id",row.get("account_id"));record.put("config_version",row.get("config_version"));record.put("saved_at",row.get("saved_at"));record.put("content",Json.parse(row.get("content_json").toString()));record.put("sources",sources);record.put("previous_hash",row.get("previous_hash"));String digest=hash(canonical(record));
        Db.exec("UPDATE m08_summary_revisions SET sources_json=?,revision_hash=? WHERE project_id=103 AND revision=1",json,digest);Db.exec("UPDATE m08_summary_heads SET head_hash=? WHERE project_id=103",digest);
        Map<String,Object>read=TrainingSummariesIntegration.read(full,103);eq("M05_SNAPSHOT_NOT_CONNECTED",delivery(read).get("reason"),"legacy reason unchanged");check(Boolean.TRUE.equals(read.get("source_changed")),"new available source prompts refresh");save(full,103);eq(json,Db.one("SELECT sources_json FROM m08_summary_revisions WHERE project_id=103 AND revision=2").get("sources_json"),"no silent migration on save");refresh(full,103);eq("AVAILABLE",delivery(TrainingSummariesIntegration.read(full,103)).get("status"),"explicit refresh upgrade");eq(json,Db.one("SELECT sources_json FROM m08_summary_revisions WHERE project_id=103 AND revision=1").get("sources_json"),"old bytes intact");
    }
    static void http() throws Exception {
        for(String path:List.of("/api/training-summaries?project_id=101","/api/training-summaries/revision?project_id=101&revision=1")){
            Exchange ex=new Exchange("GET",path,writerToken,null);check(TrainingSummariesIntegration.handle(ex,writer),"HTTP handle");hidden(data(ex));
        }
        Exchange replay=new Exchange("POST","/api/training-summaries/save",fullToken,initial);TrainingSummariesIntegration.handle(replay,full);eq(true,data(replay).get("replayed"),"HTTP replay");privacy(delivery(data(replay)));
        publish(false);Exchange hiddenReplay=new Exchange("POST","/api/training-summaries/save",fullToken,initial);TrainingSummariesIntegration.handle(hiddenReplay,full);hidden(data(hiddenReplay));
        rejects(401,()->TrainingSummariesIntegration.handle(new Exchange("GET","/api/training-summaries?project_id=101",writerToken,null),full));
        rejects(400,()->TrainingSummariesIntegration.handle(new Exchange("POST","/api/training-summaries/save",fullToken,Map.of("project_id",101,"expected_version",1,"request_id","inject","content",Map.of(),"sources",Map.of())),full));publish(true);
        Exchange save=new Exchange("POST","/api/training-summaries/save",writerToken,body(104,0,"hidden-http"));TrainingSummariesIntegration.handle(save,writer);hidden(data(save));
        Exchange refresh=new Exchange("POST","/api/training-summaries/refresh",writerToken,Map.of("project_id",104,"expected_version",1,"request_id","hidden-http-refresh"));TrainingSummariesIntegration.handle(refresh,writer);hidden(data(refresh));
        Exchange history=new Exchange("GET","/api/training-summaries/history?project_id=101",writerToken,null);TrainingSummariesIntegration.handle(history,writer);check(!Json.write(data(history)).contains("source_version"),"HTTP history no source fingerprint");
    }
    static Map<String,Object>data(Exchange ex)throws Exception{Object response=ex.getAttribute("com.training.Api.response");var f=response.getClass().getDeclaredField("body");f.setAccessible(true);return m(m(Json.parse(new String((byte[])f.get(response),StandardCharsets.UTF_8))).get("data"));}
    public static final class RevokeDuringWrite implements org.h2.api.Trigger {
        public void fire(java.sql.Connection connection,Object[] oldRow,Object[] newRow) throws java.sql.SQLException {
            try(var p=connection.prepareStatement("UPDATE users SET status=0 WHERE id=2")){p.executeUpdate();}
        }
    }
    static final class Exchange extends HttpExchange{
        final String method;final URI uri;final Headers request=new Headers(),response=new Headers();final Map<String,Object>attributes=new HashMap<>();
        Exchange(String method,String path,String token,Map<String,Object>body){this.method=method;uri=URI.create(path);request.set("X-Token",token);request.set("Content-Type","application/json");if(body!=null)attributes.put("com.training.Api.body",body);}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return InputStream.nullInputStream();}public OutputStream getResponseBody(){return OutputStream.nullOutputStream();}public void sendResponseHeaders(int c,long l){}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}public int getResponseCode(){return 0;}public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",1);}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String k){return attributes.get(k);}public void setAttribute(String k,Object v){attributes.put(k,v);}public void setStreams(InputStream i,OutputStream o){}public HttpPrincipal getPrincipal(){return null;}
    }
}
