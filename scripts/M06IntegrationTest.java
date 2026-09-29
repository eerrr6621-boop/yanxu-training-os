package com.training;

import com.sun.net.httpserver.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Synthetic identities, real Auth/M01/M05, and a caller-owned empty H2 directory. Never Db.init/seed. */
public final class M06IntegrationTest {
    /** Continue authorization checks with fresh sessions after identity publication. */
    private static void publishAccess(String expected,Configuration configuration) throws Exception {
        OrganizationAccessStore.publish(admin,expected,configuration);
        if(Auth.current(worker)==null){workerToken=Auth.login("synthetic-m06-2",PASSWORD);worker=Auth.get(workerToken);}
        if(Auth.current(outsider)==null)outsider=Auth.get(Auth.login("synthetic-m06-3",PASSWORD));
    }
    private static int checks;
    private static final String PASSWORD="SYNTHETIC-M06-INTEGRATION-ONLY";
    private static final LocalDate TODAY=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private static final String DATE=TODAY.minusDays(1).toString();
    private static Auth.Session admin,worker,outsider,unbound;
    private static String workerToken;
    private static final Map<String,Object> QUERY=Map.of("start",TODAY.minusDays(2).toString(),"end",TODAY.plusDays(2).toString());
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void check(boolean value,String label) { checks++;if(!value)throw new AssertionError(label); }
    private static void rejects(int code,Work work,String label) throws Exception {
        try {work.run();throw new AssertionError("Expected rejection: "+label);}
        catch(Api.ApiException e) {check(e.code==code,label+" expected="+code+" actual="+e.code+" "+e.getMessage());}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) {return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> view,String key) {return (List<Map<String,Object>>)view.get(key);}
    private static long number(Object value) {return new BigDecimal(value.toString()).longValueExact();}
    private static Map<String,Object> query(String key,Object value) {Map<String,Object> q=new LinkedHashMap<>(QUERY);q.put(key,value);return q;}
    private static Map<String,Object> read() throws Exception {return ManagementReportsIntegration.read(worker,QUERY);}
    private static String snapshot(Map<String,Object> view) {return Objects.toString(view.get("snapshot_version"),"");}
    private static Map<String,Object> downloadQuery(Map<String,Object> view) {return query("snapshot_version",snapshot(view));}
    private static Map<String,Object> detail(Map<String,Object> view,long id) {
        return rows(view,"details").stream().filter(r->number(r.get("dispatch_id"))==id).findFirst().orElseThrow(()->new AssertionError("Missing detail "+id));
    }
    private static boolean excluded(Map<String,Object> view,long id) {return rows(view,"excluded").stream().anyMatch(r->number(r.get("dispatch_id"))==id);}
    private static void total(Map<String,Object> view,String category,String value,String known,long missing,boolean complete) {
        Map<String,Object> t=map(map(view.get("totals")).get(category));
        check(Objects.equals(t.get("value"),value)&&known.equals(t.get("known_subtotal"))&&number(t.get("missing_records"))==missing&&Boolean.valueOf(complete).equals(t.get("complete")),category+" preserves value, known subtotal, missing count and completeness: "+t);
    }
    private static Configuration access(String version,Set<String> omitted) {
        RelationRule optional=new RelationRule(false,Set.of("OPERATOR"),false,false);List<Grant> grants=new ArrayList<>();
        for(String resource:List.of("delivery.read","delivery.write","delivery.verify","reports.read","reports.export"))
            if(!omitted.contains(resource))grants.add(new Grant("GRANT-"+resource,"OPERATOR",resource,resource.endsWith(".read")?Action.VIEW:resource.equals("reports.export")?Action.EXPORT:Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        return new Configuration(version,new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),Set.of("OPERATOR"),
                List.of(new Organization("001",null,true),new Organization("002",null,true)),
                List.of(new Person("0002","001",Set.of(),null,null,Set.of("OPERATOR"),true),new Person("0003","002",Set.of(),null,null,Set.of("OPERATOR"),true)),
                List.of(new RoleRelations("OPERATOR",optional,optional)),List.of(new AccountBinding(2,"0002",true),new AccountBinding(3,"0003",true)),grants);
    }
    private static Map<String,Object> save(Auth.Session actor,long id,String estimated,String planned,String minutes,String payable,String request) throws Exception {
        Map<String,Object> old=DeliverySettlementIntegration.read(actor,id),body=new LinkedHashMap<>();
        body.put("dispatch_id",id);body.put("expected_version",old.get("version"));body.put("request_id",request);
        body.put("estimated_hours",estimated);body.put("planned_hours",planned);body.put("actual_minutes",minutes);body.put("payable_hours",payable);
        return DeliverySettlementIntegration.mutate("save",actor,body);
    }
    private static Map<String,Object> verify(Auth.Session actor,long id,String request) throws Exception {
        Map<String,Object> old=DeliverySettlementIntegration.read(actor,id);
        return DeliverySettlementIntegration.mutate("verify",actor,Map.of("dispatch_id",id,"expected_version",old.get("version"),"request_id",request,"evidence_code","SYNTHETIC-"+request));
    }
    private static void complete(Auth.Session actor,long id) throws Exception {
        Map<String,Object> old=DeliverySettlementIntegration.read(actor,id);
        DeliverySettlementIntegration.requireCompletion(actor,id,number(old.get("version")));
        Db.exec("UPDATE dispatches SET status='已完成' WHERE id=?",id);
    }
    private static void fixtures(Path data) throws Exception {
        System.setProperty("data.dir",data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id BIGINT PRIMARY KEY,title VARCHAR(200),status VARCHAR(32))");
        // Deliberately omit old hours, fee_rate and the fees table: reading them must not be required.
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,title VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,name VARCHAR(64),status VARCHAR(32))");
        Db.exec("CREATE TABLE dispatches(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,subject VARCHAR(200),teach_date VARCHAR(32),status VARCHAR(32))");
        String hash=Auth.hash(PASSWORD);
        for(int i=1;i<=4;i++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",i,"synthetic-m06-"+i,hash,"SYNTHETIC M06 "+i,i==1?"admin":i==4?"manager":"viewer");
        admin=Auth.get(Auth.login("synthetic-m06-1",PASSWORD));workerToken=Auth.login("synthetic-m06-2",PASSWORD);worker=Auth.get(workerToken);
        outsider=Auth.get(Auth.login("synthetic-m06-3",PASSWORD));unbound=Auth.get(Auth.login("synthetic-m06-4",PASSWORD));
        check(admin!=null&&worker!=null&&outsider!=null&&unbound!=null,"real Auth login creates all fixture sessions");
        OrganizationAccessStore.init();WorkflowIntegration.init();NotificationChannelsIntegration.init();
        for(long id:List.of(1L,2L)) {
            Db.exec("INSERT INTO demands VALUES(?,?,'团队已受理')",id,"SYNTHETIC DEMAND "+id);
            Db.exec("INSERT INTO projects VALUES(?,?,?,'进行中')",id+9,id,"SYNTHETIC PROJECT "+id);
            Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct',?,'0002','{}','{}','SYNTHETIC-M03')",id,id==1?"001":"002");
            Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'001','0002',1,'2026-01-01T00:00:00Z')",id,id+9);
        }
        Db.exec("INSERT INTO teachers VALUES(20,'SYNTHETIC TEACHER','在库')");Db.exec("INSERT INTO teachers VALUES(21,'SYNTHETIC ALTERNATE','在库')");
        for(long id:List.of(101L,102L,103L,104L,105L,106L,201L))Db.exec("INSERT INTO dispatches VALUES(?,?,20,'SYNTHETIC SESSION',?,'已确认')",id,id==201?11:10,DATE);
        DeliverySettlementIntegration.init();
        check(Db.get().getMetaData().getURL().contains(data.resolve("training").toString()),"H2 URL uses only supplied synthetic directory");
    }
    private static void teachingFacts() throws Exception {
        save(worker,101,"5","4","60",null,"main-save");verify(worker,101,"main-verify");complete(worker,101);
        save(worker,102,"100","100","45","9","pending-save");verify(worker,102,"pending-verify");
        Db.exec("UPDATE dispatches SET status='已完成' WHERE id=103");
        save(worker,104,"7","6","90","2","unverified-save");Db.exec("UPDATE dispatches SET status='已完成' WHERE id=104");
        save(worker,105,"100","100","45","9","rejected-save");verify(worker,105,"rejected-verify");Db.exec("UPDATE dispatches SET status='已拒绝' WHERE id=105");
        save(worker,106,null,null,"45",null,"actual-only-save");verify(worker,106,"actual-only-verify");complete(worker,106);
        save(outsider,201,"30","30","45","2","other-org-save");verify(outsider,201,"other-org-verify");complete(outsider,201);
    }
    /** Compare every test table's data and column shape, including payload updates, not merely row counts. */
    private static String databaseState() throws Exception {
        Map<String,Object> state=new TreeMap<>();
        for(Map<String,Object> table:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME")) {
            String name=table.get("table_name").toString();
            List<String> data=Db.query("SELECT * FROM \""+name.replace("\"","\"\"")+"\"").stream().map(Json::write).sorted().toList();
            state.put(name,data);
        }
        state.put("_columns",Db.query("SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME,ORDINAL_POSITION"));
        return Json.write(state);
    }
    private static List<Long> m05Counts() throws Exception {return List.of(Db.count("m05_fact_revisions"),Db.count("m05_settlement_snapshots"),Db.count("m05_requests"));}
    private static void httpChecks() throws Exception {
        check(!ManagementReportsIntegration.handle(new Exchange("GET","/api/not-an-m06-route",null),null),"unknown route does not demand authentication");
        String route="/api/management-reports?start="+QUERY.get("start")+"&end="+QUERY.get("end");
        rejects(401,()->ManagementReportsIntegration.handle(new Exchange("GET",route,null),worker),"request token required despite supplied session");
        rejects(401,()->ManagementReportsIntegration.handle(new Exchange("GET",route,workerToken),outsider),"request token and supplied session must match");
        rejects(405,()->ManagementReportsIntegration.handle(new Exchange("POST",route,workerToken),worker),"read HTTP endpoint rejects POST");
        rejects(405,()->ManagementReportsIntegration.handle(new Exchange("POST","/api/management-reports/export",workerToken),worker),"export HTTP endpoint rejects POST");
        Exchange request=new Exchange("GET",route,workerToken);
        check(ManagementReportsIntegration.handle(request,worker)&&request.getAttribute("com.training.Api.response")!=null,"read route stages the API response without a live HTTP server");
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
    private static void assertExclusion(long id,String before,String label) throws Exception {
        Map<String,Object> view=read();
        check(excluded(view,id)&&rows(view,"details").stream().noneMatch(r->number(r.get("dispatch_id"))==id),label+" excluded explicitly, not included");
        check(!before.equals(snapshot(view)),label+" changes snapshot version");
    }
    private static void corruptAndRestore(Map<String,Object> baseline) throws Exception {
        String token=snapshot(baseline),original=Db.one("SELECT payload FROM m05_delivery_facts WHERE dispatch_id=101").get("payload").toString();
        Map<String,Object> corrupt=map(Json.parse(original));map(corrupt.get("conversion")).put("minutes","45");
        Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=101",Json.write(corrupt));
        Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=101 AND revision=(SELECT revision FROM m05_delivery_facts WHERE dispatch_id=101)",Json.write(corrupt));
        try {
            assertExclusion(101,token,"inconsistent minute conversion");
            check(rows(read(),"excluded").stream().anyMatch(r->number(r.get("dispatch_id"))==101&&"ACTUAL_EVIDENCE_INVALID".equals(r.get("code"))),"conversion corruption is diagnosed independently of revision integrity");
        } finally {
            Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=101",original);
            Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=101 AND revision=(SELECT revision FROM m05_delivery_facts WHERE dispatch_id=101)",original);
        }
        Db.exec("UPDATE m05_delivery_facts SET teacher_id=21 WHERE dispatch_id=101");
        try {assertExclusion(101,token,"mismatched stored teacher association");}finally{Db.exec("UPDATE m05_delivery_facts SET teacher_id=20 WHERE dispatch_id=101");}
        Db.exec("UPDATE projects SET demand_id=2 WHERE id=10");
        try {assertExclusion(101,token,"acceptance versus project demand mismatch");}finally{Db.exec("UPDATE projects SET demand_id=1 WHERE id=10");}
        for(String date:Arrays.asList(TODAY.plusDays(1).toString(),null,"2026-02-30")) {
            Db.exec("UPDATE dispatches SET teach_date=? WHERE id=101",date);
            try {
                boolean unknown=date==null||date.equals("2026-02-30");
                assertExclusion(101,token,unknown?"missing or invalid teaching date":"future teaching date");
                if(unknown) {
                    check(number(read().get("undated_excluded_count"))==1&&rows(read(),"excluded").stream().anyMatch(r->number(r.get("dispatch_id"))==101&&Boolean.TRUE.equals(r.get("date_unknown"))),"undated exclusion is explicit rather than assigned to a month");
                    Map<String,Object> otherMonth=ManagementReportsIntegration.read(worker,Map.of("start",TODAY.minusMonths(2).withDayOfMonth(1).toString(),"end",TODAY.minusMonths(2).withDayOfMonth(28).toString()));
                    check(excluded(otherMonth,101)&&number(otherMonth.get("undated_excluded_count"))==1,"unknown date remains visible when another month is selected");
                }
            } finally {Db.exec("UPDATE dispatches SET teach_date=? WHERE id=101",DATE);}
        }
        Db.exec("UPDATE dispatches SET teach_date=? WHERE id=101",TODAY.minusMonths(1).toString());
        try {
            assertExclusion(101,token,"teaching date drifts outside the selected range");
            check(rows(read(),"excluded").stream().anyMatch(r->number(r.get("dispatch_id"))==101&&"SOURCE_ASSOCIATION_CHANGED".equals(r.get("code"))),"in-range stored fact cannot silently disappear after source date moves across months");
        } finally {Db.exec("UPDATE dispatches SET teach_date=? WHERE id=101",DATE);}
        check(token.equals(snapshot(read())),"restored identical sources restore deterministic snapshot");
    }
    private static void rankingAndZero() throws Exception {
        Db.exec("INSERT INTO teachers VALUES(22,'SYNTHETIC THIRD','在库')");Db.exec("INSERT INTO teachers VALUES(23,'SYNTHETIC ZERO','在库')");
        for(long id:List.of(301L,302L,303L)) {
            Db.exec("INSERT INTO dispatches VALUES(?,10,?,'SYNTHETIC RANKING',?,'已确认')",id,id-280,DATE);
            save(worker,id,null,null,id==301?"195":id==302?"45":"0",null,"ranking-save-"+id);
            verify(worker,id,"ranking-verify-"+id);complete(worker,id);
        }
        Map<String,Object> view=read();
        check(number(view.get("included_count"))==6&&"0.00".equals(map(detail(view,303).get("hours")).get("actual")),"verified completed zero minutes are included and retain exact 0.00 hours");
        List<Map<String,Object>> ranking=rows(view,"teacher_ranking");
        check(ranking.stream().map(r->number(r.get("rank"))).toList().equals(List.of(1L,1L,3L,4L)),"equal actual hours use competition ranks 1,1,3,4");
        check(ranking.stream().map(r->number(r.get("teacher_id"))).toList().equals(List.of(20L,21L,22L,23L)),"ranking is stable for ties and keeps zero-hour teacher");
        total(view,"actual","9.66","9.66",0,true);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("One fresh empty test directory is required");
        Path data=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("Test directory must exist and not be a symlink");
        try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Test directory must be empty");}
        fixtures(data);
        rejects(403,()->read(),"no published M01 configuration");
        publishAccess(null,access("M06-ACCESS-1",Set.of()));teachingFacts();
        String state=databaseState();List<Long> counts=m05Counts();Map<String,Object> view=read();
        check(number(view.get("included_count"))==2&&rows(view,"details").size()==2,"only completed and verified facts included");
        check(view.get("organizations").equals(List.of("001"))&&"M06-ACCESS-1".equals(view.get("access_version")),"default scope and published access version preserved");
        check(!snapshot(view).isBlank()&&snapshot(view).equals(snapshot(read())),"unchanged snapshot is stable");
        Map<String,Object> empty=ManagementReportsIntegration.read(worker,Map.of("start",TODAY.minusYears(2).toString(),"end",TODAY.minusYears(2).toString()));
        check(number(empty.get("included_count"))==0&&rows(empty,"details").isEmpty()&&rows(empty,"teacher_ranking").isEmpty(),"empty period has no invented teaching records or rankings");
        total(empty,"actual","0","0",0,true);
        check(empty.get("organizations").equals(List.of("001"))&&empty.get("fee")==null&&empty.get("currency")==null&&!snapshot(empty).isBlank(),"empty result retains scope and snapshot without inventing zero money");
        check(view.get("availability") instanceof Map&&Boolean.FALSE.equals(map(view.get("availability")).get("formal_export")),"formal export availability explicitly blocked without requiring money to read");
        Map<String,Object> first=detail(view,101),hours=map(first.get("hours"));
        check("1.33".equals(hours.get("actual")),"real M05 60 minutes remains exact 1.33 class hours");
        check(first.containsKey("fee")&&first.get("fee")==null&&first.containsKey("currency")&&first.get("currency")==null&&first.containsKey("course_code")&&first.get("course_code")==null&&first.containsKey("teacher_code")&&first.get("teacher_code")==null&&first.containsKey("course_id")&&first.get("course_id")==null,"no guessed fee, currency or formal course/teacher identity");
        for(Map<String,Object> row:rows(view,"details"))for(Object value:map(row.get("hours")).values())check(value==null||value instanceof String,"detail hours stay exact text or null");
        total(view,"estimated",null,"5",1,false);total(view,"planned",null,"4",1,false);total(view,"actual","2.33","2.33",0,true);total(view,"payable",null,"0",2,false);
        check(List.of(102L,103L,104L,105L).stream().allMatch(id->excluded(view,id)),"uncompleted, no fact, unverified and rejected rows each excluded");
        for(Map<String,Object> row:rows(view,"excluded"))check(row.keySet().equals(Set.of("dispatch_id","organization_code","date_unknown","code","message"))&&row.get("code") instanceof String&&!row.get("code").toString().isBlank()&&row.get("message") instanceof String&&!row.get("message").toString().isBlank(),"exclusion is a minimal reason without source payload");
        check(!excluded(view,201)&&rows(view,"details").stream().noneMatch(r->number(r.get("dispatch_id"))==201)&&view.get("available_organizations").equals(List.of("001")),"other organization facts and options cannot leak");
        rejects(409,()->ManagementReportsIntegration.download(worker,downloadQuery(view)),"missing money and formal codes block download, no fake file");
        rejects(409,()->ManagementReportsIntegration.download(worker,query("snapshot_version","0".repeat(64))),"stale snapshot blocks download");
        rejects(400,()->ManagementReportsIntegration.read(worker,query("unknown","x")),"unknown query is rejected");
        rejects(400,()->ManagementReportsIntegration.read(worker,query("start","2026-02-30")),"invalid calendar date rejected");
        rejects(409,()->ManagementReportsIntegration.read(worker,query("date_basis","PAYMENT")),"payment dates not falsely inferred");
        rejects(403,()->ManagementReportsIntegration.read(worker,query("organizations","002")),"cross organization request denied");
        rejects(403,()->ManagementReportsIntegration.read(worker,query("organizations","001,002")),"mixed authorized and unauthorized list denied");
        rejects(403,()->ManagementReportsIntegration.read(admin,QUERY),"legacy admin cannot bypass M01 business binding");
        rejects(403,()->ManagementReportsIntegration.read(unbound,QUERY),"legacy manager without binding denied");
        rejects(401,()->ManagementReportsIntegration.read(null,QUERY),"missing session");
        Auth.Session forged=new Auth.Session();forged.uid=worker.uid;forged.role="admin";
        rejects(401,()->ManagementReportsIntegration.read(forged,QUERY),"copied uid is not authenticated");
        rejects(401,()->ManagementReportsIntegration.download(forged,downloadQuery(view)),"download revalidates real Auth before snapshot");
        httpChecks();
        check(counts.equals(m05Counts()),"read/download do not append M05 revisions, snapshots or request rows");
        check(state.equals(databaseState()),"successful reads and rejected requests do not change any test table or schema");
        Db.get().setAutoCommit(false);
        try {
            Db.exec("INSERT INTO demands VALUES(999,'SYNTHETIC OUTER TRANSACTION','待处理')");
            rejects(409,()->read(),"nested read transaction rejected");
            rejects(409,()->ManagementReportsIntegration.download(worker,downloadQuery(view)),"nested download transaction rejected");
            check(!Db.get().getAutoCommit(),"adapter does not commit or reset caller transaction");
        } finally {Db.get().rollback();Db.get().setAutoCommit(true);}
        check(Db.one("SELECT id FROM demands WHERE id=999")==null&&state.equals(databaseState()),"caller transaction remains rollbackable");
        corruptAndRestore(view);
        Db.exec("UPDATE teachers SET status='出库' WHERE id=20");Db.exec("UPDATE projects SET status='已归档' WHERE id=10");
        try {check(number(read().get("included_count"))==2,"retired teacher and archived project retain completed historical teaching");total(read(),"actual","2.33","2.33",0,true);}
        finally {Db.exec("UPDATE teachers SET status='在库' WHERE id=20");Db.exec("UPDATE projects SET status='进行中' WHERE id=10");}
        save(worker,102,"200","100","90","9","pending-revision");
        Map<String,Object> changed=read();
        total(changed,"estimated",null,"5",1,false);total(changed,"actual","2.33","2.33",0,true);
        verify(worker,102,"pending-review-new");complete(worker,102);Map<String,Object> completed=read();
        check(number(completed.get("included_count"))==3&&!snapshot(changed).equals(snapshot(completed)),"newly completed verified fact changes report and snapshot");
        total(completed,"actual","4.33","4.33",0,true);
        publishAccess("M06-ACCESS-1",access("M06-ACCESS-2",Set.of("reports.export")));
        Map<String,Object> readOnly=read();check(number(readOnly.get("included_count"))==3,"view grant independent of export grant");
        rejects(403,()->ManagementReportsIntegration.download(worker,downloadQuery(completed)),"revoked export overrides prior snapshot authorization");
        rejects(403,()->ManagementReportsIntegration.download(worker,query("snapshot_version","INVALID")),"current export authorization is checked before snapshot validity");
        publishAccess("M06-ACCESS-2",access("M06-ACCESS-3",Set.of("reports.read")));
        rejects(403,()->read(),"revoked read permission applies after fresh login");
        rejects(403,()->ManagementReportsIntegration.download(worker,downloadQuery(completed)),"export grant alone cannot bypass read revocation");
        publishAccess("M06-ACCESS-3",access("M06-ACCESS-4",Set.of()));
        Configuration allowed=access("M06-ACCESS-DENY",Set.of());List<Grant> deniedGrants=new ArrayList<>(allowed.grants());
        deniedGrants.add(new Grant("DENY-REPORTS-READ","OPERATOR","reports.read",Action.VIEW,Effect.DENY,Scope.OWN_ORG,Set.of()));
        publishAccess("M06-ACCESS-4",new Configuration(allowed.version(),allowed.codeRules(),allowed.roleCodes(),allowed.organizations(),allowed.people(),allowed.relations(),allowed.accountBindings(),deniedGrants));
        rejects(403,()->read(),"explicit reports read DENY overrides simultaneous ALLOW");
        rejects(403,()->ManagementReportsIntegration.download(worker,downloadQuery(completed)),"explicit read DENY blocks export despite export ALLOW");
        publishAccess("M06-ACCESS-DENY",access("M06-ACCESS-5",Set.of()));
        rankingAndZero();
        state=databaseState();Auth.logout(workerToken);
        rejects(401,()->read(),"logout invalidates captured session reference");
        rejects(401,()->ManagementReportsIntegration.download(worker,downloadQuery(completed)),"logout invalidates captured download session");
        check(state.equals(databaseState()),"logged-out requests leave all database content intact");
        Db.exec("SHUTDOWN");System.out.println("M06Integration: "+checks+" checks passed (real Auth/M01/M05; isolated synthetic H2)");
    }
}
