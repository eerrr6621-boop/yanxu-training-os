package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static com.training.OrganizationAccess.*;
import static com.training.ManagementSettlementAccounting.*;

/** Real Auth/M01 and M05 ledger/source on caller-owned empty synthetic H2. */
public final class M06SettlementBridgeTest {
    static int checks;static Auth.Session admin,worker,other;static String token;
    static final String PASSWORD="SYNTHETIC-M06-SETTLEMENT-ONLY";
    static final Map<String,Object> FILTER=map("start","2026-09-01","end","2026-09-30");
    @FunctionalInterface interface Work{void run()throws Exception;}
    static void check(boolean b,String msg){checks++;if(!b)throw new AssertionError(msg);}
    static void eq(Object a,Object b,String msg){check(Objects.equals(a,b),msg+" actual="+a+" expected="+b);}
    static void rejects(int status,Work w,String msg)throws Exception{try{w.run();throw new AssertionError("Accepted "+msg);}catch(Api.ApiException e){check(e.code==status,msg+" status "+e.code+" "+e.getMessage());}}
    static Map<String,Object> copy(Map<String,Object> m){return obj(Json.parse(Json.write(m)));}
    static Map<String,Object> read()throws Exception{return ManagementSettlementBridge.read(worker,FILTER);}
    static Map<String,Object> downloadQuery(Map<String,Object>view){Map<String,Object>q=new LinkedHashMap<>(FILTER);q.put("snapshot_version",view.get("snapshot_version"));return q;}
    static void login()throws Exception{token=Auth.login("m06-worker",PASSWORD);worker=Auth.get(token);other=Auth.get(Auth.login("m06-other",PASSWORD));}
    static Configuration config(String version,boolean read,boolean export,boolean deny){
        RelationRule optional=new RelationRule(false,Set.of("OPERATOR"),false,false);List<Grant> grants=new ArrayList<>();
        if(read)grants.add(new Grant("R","OPERATOR","reports.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        if(export)grants.add(new Grant("E","OPERATOR","reports.export",Action.EXPORT,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        if(deny)grants.add(new Grant("D","OPERATOR","reports.export",Action.EXPORT,Effect.DENY,Scope.OWN_ORG,Set.of()));
        return new Configuration(version,new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),Set.of("OPERATOR"),
            List.of(new Organization("001",null,true,"PRIVATE-ORG-ONE"),new Organization("002",null,true,"PRIVATE-ORG-TWO")),
            List.of(new Person("0002","001",Set.of(),null,null,Set.of("OPERATOR"),true),new Person("0003","002",Set.of(),null,null,Set.of("OPERATOR"),true)),
            List.of(new RoleRelations("OPERATOR",optional,optional)),List.of(new AccountBinding(2,"0002",true),new AccountBinding(3,"0003",true)),grants);
    }
    static void publish(String expected,String version,boolean read,boolean export,boolean deny)throws Exception{OrganizationAccessStore.publish(admin,expected,config(version,read,export,deny));login();}
    static void fixture(Path dir)throws Exception{
        System.setProperty("data.dir",dir.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id BIGINT PRIMARY KEY,title VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,title VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,name VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE dispatches(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,subject VARCHAR(200),teach_date VARCHAR(32),status VARCHAR(32))");
        String pass=Auth.hash(PASSWORD);
        for(int i=1;i<=3;i++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",i,List.of("m06-admin","m06-worker","m06-other").get(i-1),pass,"PRIVATE-USER",i==1?"admin":"viewer");
        admin=Auth.get(Auth.login("m06-admin",PASSWORD));login();
        OrganizationAccessStore.init();WorkflowIntegration.init();NotificationChannelsIntegration.init();DeliverySettlementIntegration.init();
        publish(null,"M06-A1",true,true,false);
        for(int i=1;i<=2;i++){Db.exec("INSERT INTO demands VALUES(?,?,'团队已受理')",i,"PRIVATE-DEMAND");Db.exec("INSERT INTO projects VALUES(?,?,?,'进行中')",i+9,i,"PRIVATE-PROJECT");Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct',?,'0002','{}','{}','SYNTHETIC-M03')",i,i==1?"001":"002");Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'001','0002',1,'2026-01-01T00:00:00Z')",i,i+9);}
        Db.exec("INSERT INTO teachers VALUES(20,'PRIVATE-TEACHER','在库')");
        check(Db.get().getMetaData().getURL().contains(dir.resolve("training").toString()),"isolated H2");
    }
    static Map<String,Object> total(String amount,String date,boolean coded){return map("activity","TEACHING","source_record_id",null,"source_fact_revision",null,"source_code","SOURCE-1","service_date",date,"organization_code","001","teacher_id",20L,"teacher_code",coded?"00020":null,"system_teacher_code","TEACHER-20","course_id",coded?30L:null,"course_code",coded?"00030":null,"amount",amount,"hours",map("ESTIMATED","3","PLANNED","2","ACTUAL","1.50","PAYABLE","1.50"),"policy_version","POLICY-1","rate_version","RATE-1","payroll_month",null);}
    static void write(Work w)throws Exception{synchronized(Api.MUTATION_LOCK){Db.transaction(()->{w.run();return null;});}}
    static String fingerprint()throws Exception{
        Map<String,Object> tables=new TreeMap<>();for(Map<String,Object> row:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME")){String t=row.get("table_name").toString();List<String> records=new ArrayList<>();for(Map<String,Object> r:Db.query("SELECT * FROM "+t))records.add(Json.write(new TreeMap<>(r)));Collections.sort(records);tables.put(t,records);}return Json.write(tables);
    }
    public static void main(String[]args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Fresh empty directory required");Path dir=Path.of(args[0]).toAbsolutePath();if(!Files.isDirectory(dir)||Files.isSymbolicLink(dir))throw new IllegalArgumentException();try(var files=Files.list(dir)){if(files.findAny().isPresent())throw new IllegalArgumentException("Empty fixture directory required");}
        fixture(dir);String db0=fingerprint();Map<String,Object> empty=read();eq(obj(empty.get("totals")).get("amount"),"0.00","empty real ledger");check(Boolean.TRUE.equals(empty.get("export_available")),"empty coded export allowed");ManagementSettlementBridge.download(worker,downloadQuery(empty));eq(fingerprint(),db0,"empty read/export no writes");
        write(()->DeliverySettlementLedger.open(10,"001","LEDGER-A",total("100.00","2026-08-01",true),"E1","CONFIRMED","PRIVATE-EVIDENCE"));
        write(()->DeliverySettlementLedger.payment("LEDGER-A","E1",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),"60.00","PRIVATE-PAY","0002",2,false));
        write(()->DeliverySettlementLedger.replace("LEDGER-A","E1",total("80.00","2026-09-01",true),"E2","PRIVATE-CORRECTION"));
        write(()->DeliverySettlementLedger.payment("LEDGER-A","E2",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),"20.00","PRIVATE-PAY-2","0002",2,false));
        String stable=fingerprint();Map<String,Object> view=read();eq(obj(view.get("totals")).get("amount"),"80.00","M05 exact contract accrual");eq(rows(view.get("details")).size(),1,"crossdate September rebook only");eq(view.get("selected_organizations"),List.of("001"),"actual scope");
        eq(view.get("available_organizations"),List.of("001"),"only actual reports grant");eq(rows(view.get("teachers")).get(0).get("teacher_display_name"),"PRIVATE-TEACHER","UI-only real teacher name");eq(rows(view.get("available_organization_options")).size(),1,"scoped organization options");eq(rows(view.get("available_organization_options")).get(0).get("display_name"),"PRIVATE-ORG-ONE","trusted organization display name");eq(rows(view.get("details")).get(0).get("organization_display_name"),"PRIVATE-ORG-ONE","authorized detail organization name");check(!Json.write(view).contains("PRIVATE-ORG-TWO"),"no other organization name leaked");check(view.get("snapshot_version").toString().matches("[0-9a-f]{64}"),"version hash");eq(view.get("snapshot_version"),read().get("snapshot_version"),"stable hash excludes clock");
        Map<String,Object> cashQuery=new LinkedHashMap<>(FILTER);cashQuery.put("date_basis","PAYMENT");cashQuery.put("start",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString());cashQuery.put("end",cashQuery.get("start"));Map<String,Object> cash=ManagementSettlementBridge.read(worker,cashQuery);eq(obj(cash.get("totals")).get("amount"),"80.00","cash independent date");eq(cash.get("rank_metric"),"FEE","cash default amount ranking");
        byte[] bytes=ManagementSettlementBridge.download(worker,downloadQuery(view));check(bytes.length>1000&&bytes[0]=='P'&&bytes[1]=='K',"actual XLSX download");eq(fingerprint(),stable,"read/source/download leave whole database unchanged");
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))){java.util.zip.ZipEntry part;while((part=zip.getNextEntry())!=null)check(!new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("PRIVATE-"),"UI display names and evidence never enter XLSX "+part.getName());}
        Auth.Session forged=new Auth.Session();forged.uid=worker.uid;rejects(401,()->ManagementSettlementBridge.read(forged,FILTER),"copied identity cannot authenticate");
        Db.exec("UPDATE teachers SET name='PRIVATE-RENAMED' WHERE id=20");rejects(409,()->ManagementSettlementBridge.download(worker,downloadQuery(view)),"display changes invalidate output version");Db.exec("UPDATE teachers SET name='PRIVATE-TEACHER' WHERE id=20");
        Db.exec("UPDATE teachers SET name=NULL WHERE id=20");eq(rows(read().get("teachers")).get(0).get("teacher_display_name"),"讲师记录#20（待完善）","missing name placeholder");Db.exec("UPDATE teachers SET name='PRIVATE-TEACHER' WHERE id=20");
        rejects(403,()->ManagementSettlementBridge.read(admin,FILTER),"admin cannot bypass formal scopes");rejects(403,()->ManagementSettlementBridge.read(worker,map("start","2026-09-01","end","2026-09-30","organizations","002")),"cross org denied");
        for(String key:List.of("facts","source","entries","payments","actor","teacher_id","project_id","snapshot_version"))rejects(400,()->ManagementSettlementBridge.read(worker,map(key,"x")),"browser cannot provide "+key);
        rejects(400,()->ManagementSettlementBridge.read(worker,map("date_basis","PAYMENT","rank_metric","HOURS")),"cash hours ranking unavailable");rejects(400,()->ManagementSettlementBridge.read(worker,map("start","2026-02-30","end","2026-03-01")),"invalid dates");rejects(400,()->ManagementSettlementBridge.read(worker,map("organizations","001,001")),"duplicate org");
        rejects(400,()->ManagementSettlementBridge.download(worker,FILTER),"download version required");Map<String,Object> stale=downloadQuery(view);stale.put("snapshot_version","f".repeat(64));rejects(409,()->ManagementSettlementBridge.download(worker,stale),"stale token");
        Object originalTitle=Db.one("SELECT title FROM projects WHERE id=10").get("title");
        try{write(()->{Db.exec("UPDATE projects SET title='ROLLBACK-ME' WHERE id=10");rejects(409,()->read(),"nested read transaction rejected");throw new IllegalStateException("INTENTIONAL-M06-ROLLBACK");});throw new AssertionError("rollback not thrown");}
        catch(IllegalStateException expected){eq(expected.getMessage(),"INTENTIONAL-M06-ROLLBACK","intentional rollback marker");}
        eq(Db.one("SELECT title FROM projects WHERE id=10").get("title"),originalTitle,"nested read did not commit caller transaction");
        // Version changes even when a posting falls outside the selected month: full ledger is controlled.
        write(()->DeliverySettlementLedger.replace("LEDGER-A","E2",total("90.00","2026-09-01",true),"E3","PRIVATE-CORRECTION-2"));rejects(409,()->ManagementSettlementBridge.download(worker,downloadQuery(view)),"ledger update invalidates download");
        Map<String,Object> after=read();eq(obj(after.get("totals")).get("amount"),"90.00","updated exact net");
        write(()->DeliverySettlementLedger.open(10,"001","LEDGER-MISSING",total("0.00","2026-09-03",false),"MISSING-1","CONFIRMED","PRIVATE-WAIVER"));Map<String,Object> missing=read();check(!Boolean.TRUE.equals(missing.get("export_available")),"missing formal identity disables coded export");eq(rows(missing.get("code_gaps")).size(),1,"missing fields visible");rejects(409,()->ManagementSettlementBridge.download(worker,downloadQuery(missing)),"no synthetic formal code fallback");
        Db.exec("DELETE FROM workflow_acceptances WHERE project_id=10");rejects(409,()->read(),"orphan frozen ledger cannot silently disappear");Db.exec("INSERT INTO workflow_acceptances VALUES(1,10,'001','0002',1,'2026-01-01T00:00:00Z')");
        Db.exec("UPDATE projects SET demand_id=2 WHERE id=10");rejects(409,()->read(),"trusted project association changed");Db.exec("UPDATE projects SET demand_id=1 WHERE id=10");
        String payload=Db.one("SELECT payload FROM m05_financial_chains WHERE chain_code='LEDGER-A'").get("payload").toString();Map<String,Object> corrupt=obj(Json.parse(payload));corrupt.put("current_amount","999.00");Db.exec("UPDATE m05_financial_chains SET payload=? WHERE chain_code='LEDGER-A'",Json.write(corrupt));rejects(409,()->read(),"actual M05 source rejects tampered head");Db.exec("UPDATE m05_financial_chains SET payload=? WHERE chain_code='LEDGER-A'",payload);
        hourCorrectionCases();
        Auth.Session old=worker;publish("M06-A1","M06-A2",true,false,false);rejects(401,()->ManagementSettlementBridge.read(old,FILTER),"revoked captured session");check(!Boolean.TRUE.equals(read().get("can_export")),"read only grant");rejects(403,()->ManagementSettlementBridge.download(worker,downloadQuery(read())),"export permission absent");
        publish("M06-A2","M06-A3",true,true,true);rejects(403,()->ManagementSettlementBridge.download(worker,downloadQuery(read())),"explicit export deny");publish("M06-A3","M06-A4",false,true,false);rejects(403,()->read(),"export does not imply read");publish("M06-A4","M06-A5",true,true,false);
        for(String query:List.of("start=2026-09-01&start=2026-09-01","start=2026-09-01&%73tart=2026-09-01","x=1","bad","organizations=001&organizations=001")){Exchange ex=new Exchange("GET","/api/management-settlement-reports?"+query,token);rejects(400,()->ManagementSettlementBridge.handle(ex,worker),"strict transport "+query);}
        rejects(405,()->ManagementSettlementBridge.handle(new Exchange("POST","/api/management-settlement-reports",token),worker),"read only transport");rejects(401,()->ManagementSettlementBridge.handle(new Exchange("GET","/api/management-settlement-reports",token),other),"token-session mismatch");check(!ManagementSettlementBridge.handle(new Exchange("GET","/other",null),null),"unrelated route untouched");
        Auth.logout(token);rejects(401,()->read(),"logout invalidates read");
        System.out.println("M06 settlement bridge checks passed: "+checks);
    }
    static Map<String,Object> hourTotal(String day,String actual){Map<String,Object> t=total("0.00",day,true);obj(t.get("hours")).put("ACTUAL",actual);return t;}
    static Object actual(String start,String end)throws Exception{return obj(obj(obj(ManagementSettlementBridge.read(worker,map("start",start,"end",end)).get("totals")).get("hours")).get("ACTUAL")).get("total");}
    static void hourCorrectionCases()throws Exception {
        write(()->DeliverySettlementLedger.open(10,"001","HOUR-TRANSITION",hourTotal("2026-07-15",null),"H0","CONFIRMED","HOUR-OPEN"));
        eq(actual("2026-07-01","2026-07-31"),null,"initial unknown hours remain unknown");
        write(()->DeliverySettlementLedger.replace("HOUR-TRANSITION","H0",hourTotal("2026-07-15","2"),"H1","HOUR-KNOWN"));
        eq(actual("2026-07-01","2026-07-31"),"2","same day unknown to known is reconstructed without null as zero");
        write(()->DeliverySettlementLedger.replace("HOUR-TRANSITION","H1",hourTotal("2026-07-15",null),"H2","HOUR-UNKNOWN"));
        eq(actual("2026-07-01","2026-07-31"),null,"same day known to unknown removes obsolete known total");
        write(()->DeliverySettlementLedger.replace("HOUR-TRANSITION","H2",hourTotal("2026-07-15","3"),"H3","HOUR-RECOVER"));
        eq(actual("2026-07-01","2026-07-31"),"3","second recovery does not retain old unknown");
        write(()->DeliverySettlementLedger.replace("HOUR-TRANSITION","H3",hourTotal("2026-07-15","4"),"H4","HOUR-DELTA"));
        eq(actual("2026-07-01","2026-07-31"),"4","ordinary known delta counts once");
        write(()->DeliverySettlementLedger.replace("HOUR-TRANSITION","H4",hourTotal("2026-08-15",null),"H5","HOUR-CROSS"));
        eq(actual("2026-07-01","2026-07-31"),"0","fully moved old month is known zero");
        eq(actual("2026-08-15","2026-08-15"),null,"new date remains explicitly unknown");
        write(()->DeliverySettlementLedger.replace("HOUR-TRANSITION","H5",hourTotal("2026-08-15","2"),"H6","HOUR-CROSS-RECOVER"));
        eq(actual("2026-08-15","2026-08-15"),"2","new date unknown recovery");
        write(()->DeliverySettlementLedger.replace("HOUR-TRANSITION","H6",hourTotal("2026-07-15","1"),"H7","HOUR-MOVE-BACK"));
        eq(actual("2026-08-15","2026-08-15"),"0","second old date fully reversed");
        eq(actual("2026-07-01","2026-07-31"),"1","moving back uses final frozen hours once");
    }
    static final class Exchange extends HttpExchange{
        final Headers req=new Headers(),res=new Headers();final Map<String,Object>attrs=new HashMap<>();final URI uri;final String method;Exchange(String m,String u,String token){method=m;uri=URI.create(u);if(token!=null)req.set("X-Token",token);}public Headers getRequestHeaders(){return req;}public Headers getResponseHeaders(){return res;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return InputStream.nullInputStream();}public OutputStream getResponseBody(){return OutputStream.nullOutputStream();}public void sendResponseHeaders(int c,long n){}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}public InetSocketAddress getLocalAddress(){return getRemoteAddress();}public int getResponseCode(){return 0;}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String k){return attrs.get(k);}public void setAttribute(String k,Object v){attrs.put(k,v);}public void setStreams(InputStream a,OutputStream b){}public HttpPrincipal getPrincipal(){return null;}
    }
}
