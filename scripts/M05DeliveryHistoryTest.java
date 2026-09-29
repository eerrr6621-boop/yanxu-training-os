package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import static com.training.OrganizationAccess.*;

/** Independent real-H2/Auth/M01 history contract. Never initializes or opens production data. */
public final class M05DeliveryHistoryTest {
    private static final String PASSWORD="SYNTHETIC-M05-HISTORY-ONLY";
    private static final String DATE=LocalDate.now().minusDays(1).toString();
    private static final String INTEGRITY="授课修订历史不完整或无法核实，请联系管理员核对";
    private static final String BASE="/api/delivery-settlement/history";
    private static final List<String> FIELDS=List.of("estimated_hours","planned_hours","actual_minutes","actual_hours","payable_hours","verification_status","verification_actor_code","verification_checked_at","verification_evidence_code");
    private static final Set<String> ITEM_FIELDS=Set.of("version","event_type","actor_code","account_id","created_at","estimated_hours","planned_hours","actual_minutes","actual_hours","payable_hours","verification","changes","verification_invalidated");
    private static int checks,requestNumber,accessNumber;
    private static boolean historicActorEnabled=true;
    private static Auth.Session admin,worker,outsider,unbound;
    private static String workerToken;
    @FunctionalInterface private interface Work { void run() throws Exception; }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object value) { return (List<Map<String,Object>>)value; }
    private static Map<String,Object> copy(Map<String,Object> value) { return map(Json.parse(Json.write(value))); }
    private static long number(Object value) { return new BigDecimal(value.toString()).longValueExact(); }
    private static void check(boolean condition,String message) { checks++;if(!condition)throw new AssertionError(message); }
    private static void equal(Object expected,Object actual,String message) { check(Objects.equals(expected,actual),message+" expected="+expected+" actual="+actual); }
    private static void rejects(int code,Work work,String label) throws Exception { rejects(code,null,work,label); }
    private static void rejects(int code,String exactMessage,Work work,String label) throws Exception {
        String before=databaseDigest();
        try { work.run();throw new AssertionError("Expected rejection: "+label); }
        catch(Api.ApiException e) { check(e.code==code,label+" status="+e.code+" message="+e.getMessage());if(exactMessage!=null)equal(exactMessage,e.getMessage(),label+" safe message"); }
        finally { equal(before,databaseDigest(),label+" is read-only across every public table"); }
    }
    private static Map<String,Object> history(long id,long version,int page,int size) throws Exception {
        String before=databaseDigest();
        Map<String,Object> result=DeliverySettlementIntegration.history(worker,id,version,page,size);
        equal(before,databaseDigest(),"successful history is read-only across every public table");return result;
    }
    private static Map<String,Object> save(long id,String estimated,String planned,String minutes,String payable) throws Exception {
        Map<String,Object> body=command(id);body.put("estimated_hours",estimated);body.put("planned_hours",planned);body.put("actual_minutes",minutes);body.put("payable_hours",payable);
        return DeliverySettlementIntegration.mutate("save",worker,body);
    }
    private static Map<String,Object> verify(long id) throws Exception {
        Map<String,Object> body=command(id);body.put("evidence_code","SYNTHETIC-HISTORY-"+requestNumber);
        return DeliverySettlementIntegration.mutate("verify",worker,body);
    }
    private static Map<String,Object> command(long id) throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();result.put("dispatch_id",id);result.put("expected_version",head(id));result.put("request_id","SYNTHETIC-HISTORY-REQUEST-"+(++requestNumber));return result;
    }
    private static long head(long id) throws Exception { Map<String,Object> row=Db.one("SELECT revision FROM m05_delivery_facts WHERE dispatch_id=?",id);return row==null?0:number(row.get("revision")); }
    private static Configuration access(boolean read,boolean bound,boolean personEnabled,boolean orgEnabled) {
        RelationRule optional=new RelationRule(false,Set.of("OPERATOR"),false,false);List<Grant> grants=new ArrayList<>();
        for(String resource:List.of("delivery.read","delivery.write","delivery.verify"))if(read||!resource.equals("delivery.read"))grants.add(new Grant("GRANT-"+resource,"OPERATOR",resource,resource.equals("delivery.read")?Action.VIEW:Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        List<AccountBinding> bindings=new ArrayList<>();if(bound)bindings.add(new AccountBinding(2,"0002",true));bindings.add(new AccountBinding(3,"0003",true));if(historicActorEnabled)bindings.add(new AccountBinding(5,"0005",true));
        return new Configuration("HISTORY-ACCESS-"+(++accessNumber),new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),Set.of("OPERATOR"),
            List.of(new Organization("001",null,orgEnabled),new Organization("002",null,true)),
            List.of(new Person("0002","001",Set.of(),null,null,Set.of("OPERATOR"),personEnabled),new Person("0003","002",Set.of(),null,null,Set.of("OPERATOR"),true),new Person("0005","001",Set.of(),null,null,Set.of("OPERATOR"),historicActorEnabled)),
            List.of(new RoleRelations("OPERATOR",optional,optional)),bindings,grants);
    }
    private static void publish(boolean read,boolean bound,boolean personEnabled,boolean orgEnabled) throws Exception {
        Configuration old=OrganizationAccessStore.configuration();OrganizationAccessStore.publish(admin,old==null?null:old.version(),access(read,bound,personEnabled,orgEnabled));
        loginWorker();outsider=Auth.get(Auth.login("synthetic-history-3",PASSWORD));
    }
    private static void loginWorker() throws Exception { workerToken=Auth.login("synthetic-history-2",PASSWORD);worker=Auth.get(workerToken);check(worker!=null,"fresh real Auth worker session"); }
    private static void fixtures(Path data) throws Exception {
        System.setProperty("data.dir",data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id BIGINT PRIMARY KEY,title VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,title VARCHAR(200),status VARCHAR(32),hours DOUBLE)");
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,name VARCHAR(64),status VARCHAR(32),fee_rate DOUBLE)");
        Db.exec("CREATE TABLE dispatches(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,subject VARCHAR(200),teach_date VARCHAR(32),hours DOUBLE,status VARCHAR(32))");
        Db.exec("CREATE TABLE fees(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,hours DOUBLE,rate DOUBLE,amount DOUBLE,status VARCHAR(32))");
        String hash=Auth.hash(PASSWORD);for(int i=1;i<=5;i++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",i,"synthetic-history-"+i,hash,"SYNTHETIC HISTORY "+i,i==1?"admin":i==4?"manager":"viewer");
        admin=Auth.get(Auth.login("synthetic-history-1",PASSWORD));loginWorker();outsider=Auth.get(Auth.login("synthetic-history-3",PASSWORD));unbound=Auth.get(Auth.login("synthetic-history-4",PASSWORD));
        OrganizationAccessStore.init();WorkflowIntegration.init();NotificationChannelsIntegration.init();DeliverySettlementIntegration.init();
        for(long demand:List.of(1L,2L)) {
            Db.exec("INSERT INTO demands VALUES(?,?,'团队已受理')",demand,"SYNTHETIC DEMAND "+demand);
            Db.exec("INSERT INTO projects VALUES(?,?,?,'进行中',99)",demand+9,demand,"SYNTHETIC PROJECT "+demand);
            Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct',?,'0002','{}','{}','SYNTHETIC-HISTORY')",demand,demand==1?"001":"002");
            Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'001','0002',1,'2026-01-01T00:00:00Z')",demand,demand+9);
        }
        Db.exec("INSERT INTO teachers VALUES(20,'SYNTHETIC TEACHER','在库',99999)");Db.exec("INSERT INTO teachers VALUES(21,'SYNTHETIC ALTERNATE','在库',88888)");
        for(long id:List.of(101L,102L,103L,104L,105L,106L,107L,201L,202L,203L))Db.exec("INSERT INTO dispatches VALUES(?,?,20,'SYNTHETIC SESSION',?,77,'已确认')",id,id==103?11:10,DATE);
        Db.exec("INSERT INTO fees VALUES(501,10,20,77,99999,7699923,'待发放')");
        check(Db.get().getMetaData().getURL().contains(data.resolve("training").toString()),"isolated caller-owned real H2 path");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("One fresh empty synthetic data directory is required");
        Path data=Path.of(args[0]).toAbsolutePath().normalize();if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("Data directory must exist and not be a symlink");
        try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Data directory must be empty");}
        fixtures(data);
        rejects(403,()->DeliverySettlementIntegration.history(worker,101,0,1,20),"missing M01 denies history");publish(true,true,true,true);
        normalHistory();compatibleHistory();httpContract();authorization();integrity();capacity();
        System.out.println("M05 delivery history: "+checks+" checks passed (independent real H2/Auth/M01; all history reads verified read-only).");
        Db.get().close();
    }
    private static void normalHistory() throws Exception {
        Map<String,Object> empty=history(101,0,1,20);equal(Set.of("dispatch_id","project_id","teacher_id","organization_code","current_version","page","page_size","total","items"),empty.keySet(),"bounded public response fields");
        equal(0L,number(empty.get("current_version")),"empty head version");equal(0L,number(empty.get("total")),"empty count");equal(List.of(),empty.get("items"),"empty head history");
        save(101,null,"0.00000000","60",null);verify(101);save(101,null,"0.00000000","60",null);save(101,null,"0.00000000","60",null);
        save(101,"9999999999999999.12345678","0",null,"0.00000000");save(101,null,null,"0",null);
        Map<String,Object> response=history(101,6,1,20);equal(101L,number(response.get("dispatch_id")),"dispatch association");equal(10L,number(response.get("project_id")),"project association");equal(20L,number(response.get("teacher_id")),"teacher association");equal("001",response.get("organization_code"),"server organization association");equal(6L,number(response.get("total")),"all real revisions retained");
        List<Map<String,Object>> items=list(response.get("items"));equal(6,items.size(),"full page count");Map<Long,Map<String,Object>> byVersion=new LinkedHashMap<>();
        for(int i=0;i<items.size();i++) {
            Map<String,Object> item=items.get(i);long v=6-i;equal(v,number(item.get("version")),"descending versions");equal(ITEM_FIELDS,item.keySet(),"no payload/configuration/finance data leaks");equal("0002",item.get("actor_code"),"recorded actor");equal(2L,number(item.get("account_id")),"recorded account");check(item.get("created_at") instanceof String,"recorded timestamp remains text");
            Map<String,Object> row=Db.one("SELECT * FROM m05_fact_revisions WHERE dispatch_id=101 AND revision=?",v);equal(row.get("event_type"),item.get("event_type"),"stored event type");equal(row.get("created_at"),item.get("created_at"),"stored timestamp exact");
            Map<String,Object> p=map(Json.parse(row.get("payload").toString()));Map<String,Object> before=v==1?null:map(Json.parse(Db.one("SELECT payload FROM m05_fact_revisions WHERE dispatch_id=101 AND revision=?",v-1).get("payload").toString()));
            assertChanges(item,before,p);byVersion.put(v,item);
        }
        equal("1.33",byVersion.get(1L).get("actual_hours"),"60 minutes remains 1.33 class hours");equal("60",byVersion.get(1L).get("actual_minutes"),"original minutes preserved");equal("0.00000000",byVersion.get(1L).get("planned_hours"),"8-place decimal preserved");equal("9999999999999999.12345678",byVersion.get(5L).get("estimated_hours"),"24 precision / 8 scale preserved without floating point");
        equal("0",byVersion.get(6L).get("actual_minutes"),"zero minutes differs from null");equal("0.00",byVersion.get(6L).get("actual_hours"),"zero actual hours preserves conversion scale");
        equal(Boolean.TRUE,byVersion.get(3L).get("verification_invalidated"),"same-value save invalidates previous verification");equal(null,byVersion.get(3L).get("verification"),"SAVE has no verification");equal(List.of(),byVersion.get(4L).get("changes"),"same-value unverified save still records revision with no false changes");
        for(Map<String,Object> change:list(byVersion.get(2L).get("changes")))check(change.get("field").toString().startsWith("verification_"),"VERIFY changes only verification fields");
        for(int page=1;page<=3;page++) {
            Map<String,Object> paged=history(101,6,page,2);equal((long)page,number(paged.get("page")),"page echoed");equal(2L,number(paged.get("page_size")),"page size echoed");equal(items.subList((page-1)*2,page*2),paged.get("items"),"cross-page diffs use previous revision outside the page");
        }
        equal(List.of(),history(101,6,4,2).get("items"),"beyond-last page is empty");equal(List.of(),history(101,6,Integer.MAX_VALUE,50).get("items"),"large valid page cannot overflow offset");
        rejects(409,()->DeliverySettlementIntegration.history(worker,101,5,1,20),"stale expected version");rejects(409,()->DeliverySettlementIntegration.history(worker,101,0,1,20),"empty expected version cannot read populated history");
        rejects(400,()->DeliverySettlementIntegration.history(worker,101,6,0,20),"direct zero page");rejects(400,()->DeliverySettlementIntegration.history(worker,101,6,1,0),"direct zero page size");rejects(400,()->DeliverySettlementIntegration.history(worker,101,6,1,51),"direct excessive page size");
        rejects(400,()->DeliverySettlementIntegration.history(worker,101,-1,1,20),"negative version");rejects(400,()->DeliverySettlementIntegration.history(worker,0,0,1,20),"zero dispatch id");
    }
    private static void assertChanges(Map<String,Object> item,Map<String,Object> previous,Map<String,Object> current) {
        Map<String,Object> before=flat(previous),after=flat(current);List<Map<String,Object>> expected=new ArrayList<>();
        for(String field:FIELDS)if(!Objects.equals(before.get(field),after.get(field))) {Map<String,Object> diff=new LinkedHashMap<>();diff.put("field",field);diff.put("before",before.get(field));diff.put("after",after.get(field));expected.add(diff);}
        equal(expected,item.get("changes"),"ordered exact text/null before-and-after changes");
        for(String field:FIELDS.subList(0,5))equal(after.get(field),item.get(field),"history field equals original stored value: "+field);
        equal(current.get("verification"),item.get("verification"),"verification preserves only original evidence");
        equal(previous!=null&&previous.get("verification")!=null&&current.get("verification")==null,item.get("verification_invalidated"),"invalidation depends on adjacent revisions");
    }
    private static void compatibleHistory() throws Exception {
        Map<String,Object> body=command(106);body.put("dimensions",Map.of());DeliverySettlementIntegration.mutate("save",worker,body);
        Map<String,Object> allNull=list(history(106,1,1,20).get("items")).get(0);
        for(String field:FIELDS.subList(0,5))equal(null,allNull.get(field),"initial all-null draft is legal: "+field);
        equal(List.of(new LinkedHashMap<String,Object>(){{put("field","verification_status");put("before",null);put("after","UNVERIFIED");}}),allNull.get("changes"),"all-null initial draft only introduces its unverified status");
        save(106,null,null,"60",null);verify(106);verify(106);
        Map<String,Object> successive=list(history(106,4,1,1).get("items")).get(0);equal("VERIFY",successive.get("event_type"),"successive VERIFY revisions remain valid");
        check(!java.time.Instant.parse(map(successive.get("verification")).get("checked_at").toString()).isAfter(java.time.Instant.parse(successive.get("created_at").toString())),"real verification precedes or equals revision creation");
        save(106,null,null,null,null);Map<String,Object> cleared=list(history(106,5,1,1).get("items")).get(0);equal(null,cleared.get("actual_minutes"),"cleared actual minutes are null");equal(null,cleared.get("actual_hours"),"cleared actual hours are null");equal(Boolean.TRUE,cleared.get("verification_invalidated"),"clearing actual minutes invalidates review");
        Auth.Session viewer=worker;worker=Auth.get(Auth.login("synthetic-history-5",PASSWORD));save(105,"2.00","1.00","60",null);verify(105);worker=viewer;
        historicActorEnabled=false;publish(true,true,true,true);Db.exec("UPDATE users SET status=0 WHERE id=5");
        Map<String,Object> retiredActor=list(history(105,2,1,20).get("items")).get(0);equal("0005",retiredActor.get("actor_code"),"historical actor stays original after person disable/account unbind");equal(5L,number(retiredActor.get("account_id")),"historical account survives current account disable");equal("0005",map(retiredActor.get("verification")).get("actor_code"),"historical verification is not relabeled to current viewer");
        save(107,null,null,"60",null);
        // Stored historically valid conversion stays exact even when it predates current 45-minute rules.
        String original=Db.one("SELECT payload FROM m05_delivery_facts WHERE dispatch_id=107").get("payload").toString();Map<String,Object> stored=map(Json.parse(original));
        map(stored.get("hours")).put("actual","1.000");Map<String,Object> conversion=map(stored.get("conversion"));conversion.put("class_hours","1.000");conversion.put("minutes_per_class_hour",60);conversion.put("class_hour_scale",3);conversion.put("class_hour_rounding","DOWN");conversion.put("rule_version","HISTORICAL-RULE");conversion.put("rounded",false);
        Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=107",Json.write(stored));Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=107",Json.write(stored));
        equal("1.000",list(history(107,1,1,20).get("items")).get(0).get("actual_hours"),"history projects recorded conversion without recalculating against current rules");
    }
    private static Map<String,Object> flat(Map<String,Object> p) {
        Map<String,Object> result=new LinkedHashMap<>();for(String field:FIELDS)result.put(field,null);if(p==null)return result;
        Map<String,Object> h=map(p.get("hours"));result.put("estimated_hours",h.get("estimated"));result.put("planned_hours",h.get("planned"));result.put("actual_hours",h.get("actual"));result.put("payable_hours",h.get("payable"));result.put("actual_minutes",p.get("conversion")==null?null:map(p.get("conversion")).get("minutes"));
        Map<String,Object> v=p.get("verification")==null?null:map(p.get("verification"));result.put("verification_status",v==null?"UNVERIFIED":"VERIFIED");
        if(v!=null){result.put("verification_actor_code",v.get("actor_code"));result.put("verification_checked_at",v.get("checked_at"));result.put("verification_evidence_code",v.get("evidence_code"));}return result;
    }
    private static void httpContract() throws Exception {
        String valid="dispatch_id=101&expected_version=6";Exchange ex=new Exchange("GET",BASE+"?"+valid,workerToken);String before=databaseDigest();
        check(DeliverySettlementIntegration.handle(ex,worker),"history route mounted");Map<String,Object> data=map(response(ex).get("data"));equal(1L,number(data.get("page")),"HTTP default page 1");equal(20L,number(data.get("page_size")),"HTTP default page size 20");equal(6L,number(data.get("current_version")),"HTTP current version");equal(before,databaseDigest(),"HTTP success no writes");
        Exchange explicit=new Exchange("GET",BASE+"?page_size=2&expected_version=6&page=2&dispatch_id=101",workerToken);check(DeliverySettlementIntegration.handle(explicit,worker),"arbitrary parameter order");equal(copy(history(101,6,2,2)).get("items"),map(response(explicit).get("data")).get("items"),"HTTP pagination matches direct contract");
        for(String query:List.of("","dispatch_id=101","expected_version=6",valid+"&organization_code=001",valid+"&limit=2",valid+"&page=1&page=2",valid+"&dispatch_id=101",valid+"&%64ispatch_id=101",valid+"&%65xpected_version=6",valid+"&page=1&%70age=1",valid+"&page_size=2&%70age_size=2",valid+"&",valid+"&&page=1",valid+"&=1",valid+"&page",valid+"&page=",valid+"&page_size=","dispatch_id=&expected_version=6","dispatch_id=101&expected_version=","dispatch_id=101=102&expected_version=6",valid+"&page=%20",valid+"&page=1%00"))badQuery(query,"strict HTTP query shape: "+query);
        for(String bad:List.of("-1","+1","%2B1","1.0","1e0","NaN","Infinity","9007199254740992","9223372036854775808","%20%31","%31%20")) {
            badQuery("dispatch_id="+bad+"&expected_version=6","invalid dispatch safe integer "+bad);badQuery("dispatch_id=101&expected_version="+bad,"invalid version safe integer "+bad);badQuery(valid+"&page="+bad,"invalid page integer "+bad);badQuery(valid+"&page_size="+bad,"invalid page size integer "+bad);
        }
        badQuery("dispatch_id=0&expected_version=6","zero dispatch");badQuery(valid+"&page=0","zero page");badQuery(valid+"&page_size=0","zero size");badQuery(valid+"&page_size=51","page size cap");badQuery(valid+"&page=2147483648","page integer overflow");
        rejects(409,()->DeliverySettlementIntegration.handle(new Exchange("GET",BASE+"?dispatch_id=101&expected_version=5",workerToken),worker),"HTTP stale version");
        for(String method:List.of("POST","PUT","DELETE","PATCH","HEAD"))rejects(405,()->DeliverySettlementIntegration.handle(new Exchange(method,BASE+"?"+valid,workerToken),worker),"GET-only history: "+method);
        rejects(401,()->DeliverySettlementIntegration.handle(new Exchange("GET",BASE+"?"+valid,null),worker),"HTTP token required");rejects(401,()->DeliverySettlementIntegration.handle(new Exchange("GET",BASE+"?"+valid,workerToken),outsider),"supplied session must match actual request token");
    }
    private static void badQuery(String query,String label) throws Exception { rejects(400,()->DeliverySettlementIntegration.handle(new Exchange("GET",BASE+"?"+query,workerToken),worker),label); }
    private static void authorization() throws Exception {
        rejects(403,()->DeliverySettlementIntegration.history(outsider,101,6,1,2),"cross-organization history");rejects(403,()->DeliverySettlementIntegration.history(worker,103,0,1,2),"cross-organization empty history");rejects(403,()->DeliverySettlementIntegration.history(admin,101,6,1,2),"old admin has no automatic business permission");rejects(403,()->DeliverySettlementIntegration.history(unbound,101,6,1,2),"old manager cannot bypass binding");
        Auth.Session forged=new Auth.Session();forged.uid=2;forged.role="admin";rejects(401,()->DeliverySettlementIntegration.history(forged,101,6,1,2),"forged session");rejects(401,()->DeliverySettlementIntegration.history(null,101,6,1,2),"missing session");
        history(101,6,1,2);Auth.Session old=worker;publish(false,true,true,true);rejects(401,()->DeliverySettlementIntegration.history(old,101,6,2,2),"policy publication invalidates old session between pages");rejects(403,()->DeliverySettlementIntegration.history(worker,101,6,2,2),"fresh login cannot bypass revoked read grant");publish(true,true,true,true);
        history(101,6,1,2);publish(true,false,true,true);rejects(403,()->DeliverySettlementIntegration.history(worker,101,6,2,2),"unbinding takes effect on later page");publish(true,true,true,true);
        history(101,6,1,2);publish(true,true,false,true);rejects(403,()->DeliverySettlementIntegration.history(worker,101,6,2,2),"disabled person denied on later page");publish(true,true,true,true);
        history(101,6,1,2);publish(true,true,true,false);rejects(403,()->DeliverySettlementIntegration.history(worker,101,6,2,2),"disabled organization denied on later page");publish(true,true,true,true);
        history(101,6,1,2);Db.exec("UPDATE users SET status=0 WHERE id=2");rejects(401,()->DeliverySettlementIntegration.history(worker,101,6,2,2),"account disable denies cached session");Db.exec("UPDATE users SET status=1 WHERE id=2");rejects(401,()->DeliverySettlementIntegration.history(worker,101,6,2,2),"account reenable does not resurrect session");loginWorker();
        history(101,6,1,2);Auth.logout(workerToken);rejects(401,()->DeliverySettlementIntegration.history(worker,101,6,2,2),"logout denies later page");loginWorker();
        for(String status:List.of("已归档","已完成")){Db.exec("UPDATE projects SET status=? WHERE id=10",status);equal(6L,number(history(101,6,1,20).get("total")),"historical project status remains readable: "+status);}Db.exec("UPDATE teachers SET status='出库' WHERE id=20");equal(6L,number(history(101,6,1,20).get("total")),"retired teacher history stays readable");Db.exec("UPDATE teachers SET status='在库' WHERE id=20");Db.exec("UPDATE projects SET status='进行中' WHERE id=10");
    }
    private static void integrity() throws Exception {
        save(102,"3.00","2.00","60","1.25");verify(102);save(102,"4.00","2.00","60","1.25");
        for(long invalid:List.of(0L,-1L)) {
            Db.exec("UPDATE m05_fact_revisions SET revision=? WHERE dispatch_id=102 AND revision=1",invalid);
            try{integrityReject(102,3,"non-positive stored revision returns unified integrity error: "+invalid);}finally{Db.exec("UPDATE m05_fact_revisions SET revision=1 WHERE dispatch_id=102 AND revision=?",invalid);}
        }
        for(String field:List.of("record_id","revision","session_code","instructor_code","organization_code","service_date"))corruptPayload(102,1,p->p.put(field,switch(field){case "record_id"->999L;case "revision"->2L;case "session_code"->"DISPATCH-999";case "instructor_code"->"TEACHER-21";case "organization_code"->"002";default->"2001-01-01";}),false,"historical source/revision drift: "+field);
        for(String field:List.of("estimated","planned","actual","payable"))corruptPayload(102,1,p->map(p.get("hours")).put(field,new BigDecimal("1.25")),false,"stored numeric JSON rejected: "+field);
        for(String invalid:List.of("-1","1234567890123456789012345","0.123456789","1e0","NaN"))corruptPayload(102,1,p->map(p.get("hours")).put("estimated",invalid),false,"invalid persisted decimal "+invalid);
        corruptPayload(102,1,p->p.put("data_mode","SYNTHETIC_DEMO"),false,"synthetic facts cannot enter live history");
        corruptPayload(102,1,p->p.put("record_id","102"),false,"stored numeric identifier cannot become a string");corruptPayload(102,1,p->map(p.get("conversion")).put("minutes_per_class_hour","45"),false,"stored conversion unit cannot become a string");
        corruptColumn(102,1,"created_at","2999-01-01T00:00:00Z","future event timestamp rejected");corruptPayload(102,2,p->map(p.get("verification")).put("checked_at","2999-01-01T00:00:00Z"),false,"future verification timestamp rejected");
        corruptPayload(102,1,p->p.remove("hours"),false,"missing hours");corruptPayload(102,1,p->p.put("conversion",null),false,"actual value requires conversion evidence");
        for(String field:List.of("minutes","class_hours","status","minutes_per_class_hour","class_hour_scale","class_hour_rounding","rule_version","evidence_code","rounding_decision_code"))corruptPayload(102,1,p->map(p.get("conversion")).remove(field),false,"incomplete conversion "+field);
        corruptPayload(102,1,p->map(p.get("conversion")).put("minutes",60),false,"numeric stored minutes rejected");corruptPayload(102,1,p->map(p.get("conversion")).put("class_hours","1.34"),false,"conversion projection mismatch");
        corruptPayload(102,2,p->map(p.get("hours")).put("estimated","5.00"),false,"VERIFY cannot change teaching values");corruptPayload(102,2,p->p.put("verification",null),false,"VERIFY must contain evidence");
        for(String field:List.of("actor_code","checked_at","evidence_code"))corruptPayload(102,2,p->map(p.get("verification")).put(field,field.equals("actor_code")?"0003":field.equals("checked_at")?"not-an-instant":""),false,"invalid verification metadata "+field);
        Map<String,Object> verification=map(Json.parse(Db.one("SELECT payload FROM m05_fact_revisions WHERE dispatch_id=102 AND revision=2").get("payload").toString()));corruptPayload(102,1,p->p.put("verification",verification.get("verification")),false,"SAVE cannot retain verification");
        corruptColumn(102,1,"event_type","FORGED","unknown event");corruptColumn(102,1,"event_type","VERIFY","first event cannot verify");corruptColumn(102,1,"actor_code","","blank recorded actor");corruptColumn(102,1,"account_id",0L,"invalid recorded account");corruptColumn(102,1,"created_at","invalid","invalid recorded event time");corruptColumn(102,2,"actor_code","0003","VERIFY recorded actor disagrees with evidence actor");
        corruptPayload(102,3,p->map(p.get("hours")).put("estimated","999.00"),false,"head payload and latest payload disagree");corruptPayload(102,3,p->p.put("data_mode","SYNTHETIC_DEMO"),true,"tampered matching head/latest both rejected");
        String original=Db.one("SELECT payload FROM m05_delivery_facts WHERE dispatch_id=102").get("payload").toString();Map<String,Object> changed=map(Json.parse(original));map(changed.get("hours")).put("estimated","8.00");Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=102",Json.write(changed));try{integrityReject(102,3,"head-only mismatch");}finally{Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=102",original);}
        Db.exec("UPDATE m05_delivery_facts SET revision=4 WHERE dispatch_id=102");try{integrityReject(102,4,"head revision differs from latest");}finally{Db.exec("UPDATE m05_delivery_facts SET revision=3 WHERE dispatch_id=102");}
        Map<String,Object> deleted=Db.one("SELECT * FROM m05_fact_revisions WHERE dispatch_id=102 AND revision=2");Db.exec("DELETE FROM m05_fact_revisions WHERE dispatch_id=102 AND revision=2");try{integrityReject(102,3,"gap is rejected even when outside requested page");}finally{insertRevision(deleted);}
        Map<String,Object> last=Db.one("SELECT * FROM m05_fact_revisions WHERE dispatch_id=102 AND revision=3");Db.exec("DELETE FROM m05_fact_revisions WHERE dispatch_id=102 AND revision=3");try{integrityReject(102,3,"missing latest revision");}finally{insertRevision(last);}
        Db.exec("UPDATE dispatches SET teacher_id=21 WHERE id=102");try{integrityReject(102,3,"current source teacher drift");}finally{Db.exec("UPDATE dispatches SET teacher_id=20 WHERE id=102");}
        Db.exec("UPDATE dispatches SET teach_date='2001-01-01' WHERE id=102");try{integrityReject(102,3,"current service date drift");}finally{Db.exec("UPDATE dispatches SET teach_date=? WHERE id=102",DATE);}
        save(104,null,null,null,null);Map<String,Object> r=Db.one("SELECT * FROM m05_fact_revisions WHERE dispatch_id=104 AND revision=1");Db.exec("DELETE FROM m05_fact_revisions WHERE dispatch_id=104");try{integrityReject(104,1,"head without any history cannot become empty");}finally{insertRevision(r);}
        equal(3L,number(history(102,3,1,20).get("total")),"all explicit corruption fixtures restored");
    }
    private static void corruptPayload(long id,long revision,Consumer<Map<String,Object>> edit,boolean mirrorHead,String label) throws Exception {
        String before=Db.one("SELECT payload FROM m05_fact_revisions WHERE dispatch_id=? AND revision=?",id,revision).get("payload").toString();String headPayload=Db.one("SELECT payload FROM m05_delivery_facts WHERE dispatch_id=?",id).get("payload").toString();Map<String,Object> p=map(Json.parse(before));edit.accept(p);
        Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=? AND revision=?",Json.write(p),id,revision);if(mirrorHead)Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=?",Json.write(p),id);
        try{integrityReject(id,head(id),label);}finally{Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=? AND revision=?",before,id,revision);if(mirrorHead)Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=?",headPayload,id);}
    }
    private static void corruptColumn(long id,long revision,String column,Object value,String label) throws Exception {
        check(Set.of("event_type","actor_code","account_id","created_at").contains(column),"test SQL column is constant whitelisted");Object previous=Db.one("SELECT * FROM m05_fact_revisions WHERE dispatch_id=? AND revision=?",id,revision).get(column);Db.exec("UPDATE m05_fact_revisions SET "+column+"=? WHERE dispatch_id=? AND revision=?",value,id,revision);
        try{integrityReject(id,head(id),label);}finally{Db.exec("UPDATE m05_fact_revisions SET "+column+"=? WHERE dispatch_id=? AND revision=?",previous,id,revision);}
    }
    private static void integrityReject(long id,long expected,String label) throws Exception { rejects(409,INTEGRITY,()->DeliverySettlementIntegration.history(worker,id,expected,1,1),label); }
    private static void insertRevision(Map<String,Object> r) throws Exception { Db.exec("INSERT INTO m05_fact_revisions(dispatch_id,revision,payload,event_type,actor_code,account_id,created_at) VALUES(?,?,?,?,?,?,?)",r.get("dispatch_id"),r.get("revision"),r.get("payload"),r.get("event_type"),r.get("actor_code"),r.get("account_id"),r.get("created_at")); }
    private static void capacity() throws Exception {
        save(201,null,null,null,null);bulkRevisions(201,10000,0);Map<String,Object> large=history(201,10000,200,50);equal(10000L,number(large.get("total")),"10,000 revisions accepted");equal(50,list(large.get("items")).size(),"last full page at revision ceiling");equal(1L,number(list(large.get("items")).get(49).get("version")),"ceiling last page ends at first revision");
        bulkRevisions(201,10001,0);capacityReject(201,10001,"more than 10000 revisions");
        save(202,null,null,null,null);bulkRevisions(202,1,32000);equal(1L,number(history(202,1,1,20).get("total")),"single 32000-character payload accepted");bulkRevisions(202,1,32001);capacityReject(202,1,"single payload over 32000 characters");
        save(203,null,null,null,null);bulkRevisions(203,263,32000);capacityReject(203,263,"cumulative payload over 8 Mi characters");
    }
    /** Valid unchanged SAVE revisions; JSON trailing whitespace tests actual CLOB character ceilings. */
    private static void bulkRevisions(long id,int count,int paddedLength) throws Exception {
        Map<String,Object> seed=map(Json.parse(Db.one("SELECT payload FROM m05_delivery_facts WHERE dispatch_id=?",id).get("payload").toString()));seed.put("verification",null);
        Db.transaction(()->{Db.exec("DELETE FROM m05_fact_revisions WHERE dispatch_id=?",id);String last=null;
            try(PreparedStatement ps=Db.get().prepareStatement("INSERT INTO m05_fact_revisions(dispatch_id,revision,payload,event_type,actor_code,account_id,created_at) VALUES(?,?,?,'SAVE','0002',2,'2026-01-01T00:00:00Z')")) {
                for(int v=1;v<=count;v++){seed.put("revision",v);last=Json.write(seed);if(paddedLength>0){check(last.length()<=paddedLength,"capacity fixture base fits padding");last+= " ".repeat(paddedLength-last.length());}ps.setLong(1,id);ps.setInt(2,v);ps.setString(3,last);ps.addBatch();if(v%500==0)ps.executeBatch();}ps.executeBatch();
            }Db.exec("UPDATE m05_delivery_facts SET revision=?,payload=? WHERE dispatch_id=?",count,last,id);return null;});
    }
    private static void capacityReject(long id,long expected,String label) throws Exception {
        String before=databaseDigest();try {DeliverySettlementIntegration.history(worker,id,expected,1,20);throw new AssertionError("Expected capacity rejection: "+label);}catch(Api.ApiException e){check(e.code==409,label+" status");check(e.getMessage()!=null&&!e.getMessage().isBlank()&&!INTEGRITY.equals(e.getMessage()),label+" has distinct explicit capacity guidance");check(e.getMessage().contains("上限")||e.getMessage().contains("容量")||e.getMessage().contains("过大")||e.getMessage().contains("超过"),label+" explains capacity");}finally{equal(before,databaseDigest(),label+" rejection is read-only");}
    }
    /** Digest every persisted column in every public base table, including unrelated legacy/M01 data. */
    private static String databaseDigest() throws Exception {
        List<String> tables=new ArrayList<>();try(ResultSet rs=Db.get().getMetaData().getTables(null,"PUBLIC","%",new String[]{"TABLE"})){while(rs.next())tables.add(rs.getString("TABLE_NAME"));}if(!tables.contains("M05_FACT_REVISIONS")||!tables.contains("USERS"))throw new AssertionError("Read-only digest must cover actual public business and identity tables");Collections.sort(tables);MessageDigest all=MessageDigest.getInstance("SHA-256");
        for(String table:tables){all.update(table.getBytes(StandardCharsets.UTF_8));List<String> rows=new ArrayList<>();try(Statement st=Db.get().createStatement();ResultSet rs=st.executeQuery("SELECT * FROM \""+table.replace("\"","\"\"")+"\"")){ResultSetMetaData md=rs.getMetaData();while(rs.next()){MessageDigest row=MessageDigest.getInstance("SHA-256");for(int i=1;i<=md.getColumnCount();i++){Object v=rs.getObject(i);String text=v instanceof Clob c?c.getSubString(1,Math.toIntExact(c.length())):v==null?null:v.toString();String encoded=md.getColumnName(i)+":"+(text==null?"NULL":text.length()+":"+text)+";";row.update(encoded.getBytes(StandardCharsets.UTF_8));}rows.add(HexFormat.of().formatHex(row.digest()));}}Collections.sort(rows);for(String row:rows)all.update(row.getBytes(StandardCharsets.UTF_8));}return HexFormat.of().formatHex(all.digest());
    }
    private static Map<String,Object> response(Exchange exchange) throws Exception {
        Object value=exchange.getAttribute("com.training.Api.response");check(value!=null,"handler stages a normal API response");java.lang.reflect.Field body=value.getClass().getDeclaredField("body");body.setAccessible(true);return map(Json.parse(new String((byte[])body.get(value),StandardCharsets.UTF_8)));
    }
    private static final class Exchange extends HttpExchange {
        private final String method;private final URI uri;private final Headers request=new Headers(),response=new Headers();private final Map<String,Object> attributes=new HashMap<>();
        Exchange(String method,String path,String token){this.method=method;uri=URI.create(path);if(token!=null)request.set("X-Token",token);}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}
        public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return InputStream.nullInputStream();}public OutputStream getResponseBody(){return OutputStream.nullOutputStream();}
        public void sendResponseHeaders(int code,long length){}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}public int getResponseCode(){return 0;}
        public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",1);}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String key){return attributes.get(key);}
        public void setAttribute(String key,Object value){attributes.put(key,value);}public void setStreams(InputStream input,OutputStream output){}public HttpPrincipal getPrincipal(){return null;}
    }
}
