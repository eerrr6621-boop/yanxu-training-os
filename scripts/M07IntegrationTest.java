package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import static com.training.OrganizationAccess.*;

/** Only synthetic XLSX, real Auth against a new caller-owned H2, and an isolated loopback handler. */
public final class M07IntegrationTest {
    private static final String PASSWORD="SYNTHETIC-M07-ONLY";
    private static final String BASE="/api/survey-response-imports";
    private static Auth.Session admin, worker, peer, third, other, reader, empty;
    private static String workerToken, readerToken;
    private static int passed, failed, configVersion;
    private static byte[] workbook;
    private static int normalCheckpoints;
    @FunctionalInterface interface Work { void run() throws Exception; }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void eq(Object expected,Object actual){check(Objects.equals(expected,actual),"expected="+expected+", actual="+actual);}
    private static void run(String name,Work work){try{work.run();passed++;System.out.println("PASS "+name);}catch(Throwable e){failed++;System.out.println("FAIL "+name+": "+e.getClass().getSimpleName()+" "+e.getMessage());}}
    private static void rejects(int status,Work work)throws Exception{try{work.run();throw new AssertionError("expected status "+status);}catch(Api.ApiException e){eq(status,e.code);}}
    private static void rejectsAny(Set<Integer> statuses,Work work)throws Exception{try{work.run();throw new AssertionError("expected rejection");}catch(Api.ApiException e){check(statuses.contains(e.code),"unexpected status "+e.code);}}
    @SuppressWarnings("unchecked")private static Map<String,Object> map(Object value){return(Map<String,Object>)value;}
    private static List<?> projects(Map<String,Object> config){return(List<?>)config.get("projects");}
    private static Set<Long> ids(Map<String,Object> config){Set<Long> out=new HashSet<>();for(Object row:projects(config))out.add(((Number)map(row).get("id")).longValue());return out;}
    private static Map<String,Object> body(long project){Map<String,Object>b=new LinkedHashMap<>();b.put("fileName","synthetic.xlsx");b.put("xlsxBase64",Base64.getEncoder().encodeToString(workbook));b.put("projectId",project);return b;}
    private static Map<String,Object> preview(Auth.Session s,long project)throws Exception{return SurveySummaryImportsIntegration.preview(s,body(project));}
    private static void sql(String statement,Object...args)throws Exception{synchronized(Api.MUTATION_LOCK){Db.exec(statement,args);}}
    private static Configuration configuration(String version,boolean allow,boolean wrongAction,boolean deny){
        RelationRule optional=new RelationRule(false,Set.of("WORKER","READER"),false,false);
        List<Grant>grants=new ArrayList<>();
        grants.add(new Grant("DEMAND-READ","READER","demand.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        grants.add(new Grant("DEMAND-WRITE","READER","demand.write",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        if(allow)grants.add(new Grant("SURVEY","WORKER","survey.preview",wrongAction?Action.VIEW:Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        if(deny)grants.add(new Grant("DENY","WORKER","survey.preview",Action.HANDLE,Effect.DENY,Scope.OWN_ORG,Set.of()));
        return new Configuration(version,new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),Set.of("WORKER","READER"),
            List.of(new Organization("001",null,true),new Organization("002",null,true),new Organization("003",null,true)),
            List.of(new Person("0002","001",Set.of(),null,null,Set.of("WORKER"),true),new Person("0003","001",Set.of(),null,null,Set.of("WORKER"),true),
                    new Person("0004","002",Set.of(),null,null,Set.of("WORKER"),true),new Person("0007","001",Set.of(),null,null,Set.of("WORKER"),true),
                    new Person("0008","001",Set.of(),null,null,Set.of("READER"),true),new Person("0009","003",Set.of(),null,null,Set.of("WORKER"),true)),
            List.of(new RoleRelations("WORKER",optional,optional),new RoleRelations("READER",optional,optional)),
            List.of(new AccountBinding(2,"0002",true),new AccountBinding(3,"0003",true),new AccountBinding(4,"0004",true),new AccountBinding(7,"0007",true),new AccountBinding(8,"0008",true),new AccountBinding(9,"0009",true)),grants);
    }
    private static void publish(boolean allow,boolean wrongAction,boolean deny)throws Exception{
        Configuration old=OrganizationAccessStore.configuration();
        OrganizationAccessStore.publish(admin,old==null?null:old.version(),configuration("M07-TEST-"+(++configVersion),allow,wrongAction,deny));
        // In-flight work retains its captured old session; later cases explicitly log in again.
        if(Auth.current(worker)==null)loginWorker();
        if(Auth.current(peer)==null)peer=login(3);
        if(Auth.current(other)==null)other=login(4);
        if(Auth.current(third)==null)third=login(7);
        if(Auth.current(reader)==null){readerToken=Auth.login("u8",PASSWORD);reader=Auth.get(readerToken);}
        if(Auth.current(empty)==null)empty=login(9);
    }
    private static void loginWorker()throws Exception{workerToken=Auth.login("u2",PASSWORD);worker=Auth.get(workerToken);check(worker!=null,"worker session");}
    private static Auth.Session login(int id)throws Exception{return Auth.get(Auth.login("u"+id,PASSWORD));}
    private static void fixtures(Path data)throws Exception{
        System.setProperty("data.dir",data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,title VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE workflow_demands(demand_id BIGINT PRIMARY KEY,organization_code VARCHAR(120),data_revision BIGINT,version BIGINT,draft BOOLEAN)");
        Db.exec("CREATE TABLE workflow_acceptances(demand_id BIGINT PRIMARY KEY,project_id BIGINT UNIQUE,data_revision BIGINT,team_code VARCHAR(120),actor_code VARCHAR(120),created_at VARCHAR(40))");
        String hash=Auth.hash(PASSWORD);
        for(int id:new int[]{1,2,3,4,5,7,8,9})Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",id,"u"+id,hash,"SYNTHETIC USER "+id,id==1?"admin":id==5?"manager":"viewer");
        Db.exec("INSERT INTO workflow_demands VALUES(11,'001',1,1,FALSE),(12,'002',1,1,FALSE),(13,'001',1,1,FALSE),(14,'001',2,1,FALSE),(15,'001',1,1,TRUE)");
        Db.exec("INSERT INTO projects VALUES(101,11,'SYNTHETIC PROJECT A','进行中'),(102,12,'SYNTHETIC PROJECT B','进行中'),(103,11,'UNTRUSTED LEGACY','进行中'),(104,999,'MISMATCHED SOURCE','进行中'),(105,14,'REVISION MISMATCH','进行中'),(106,15,'DRAFT SOURCE','进行中')");
        Db.exec("INSERT INTO workflow_acceptances VALUES(11,101,1,'TEAM-A','ACTOR-A','2026-01-01T00:00:00Z'),(12,102,1,'TEAM-B','ACTOR-B','2026-01-01T00:00:00Z'),(13,104,1,'TEAM-A','ACTOR-A','2026-01-01T00:00:00Z'),(14,105,1,'TEAM-A','ACTOR-A','2026-01-01T00:00:00Z'),(15,106,1,'TEAM-A','ACTOR-A','2026-01-01T00:00:00Z')");
        admin=login(1);loginWorker();peer=login(3);other=login(4);third=login(7);readerToken=Auth.login("u8",PASSWORD);reader=Auth.get(readerToken);empty=login(9);
        check(Db.get().getMetaData().getURL().contains(data.resolve("training").toString()),"H2 path must be isolated");
        OrganizationAccessStore.init();workbook=xlsx();
    }
    private static String esc(String text){return text.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
    private static String cell(String ref,String value){return"<c r=\""+ref+"\" t=\"inlineStr\"><is><t>"+esc(value)+"</t></is></c>";}
    private static byte[] xlsx()throws Exception{
        String ns="http://schemas.openxmlformats.org/spreadsheetml/2006/main",rel="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        StringBuilder sheet=new StringBuilder("<worksheet xmlns=\"").append(ns).append("\"><sheetData><row r=\"1\">").append(cell("A1","record marker"));
        for(int q=0;q<10;q++)sheet.append(cell((char)('B'+q)+"1",SurveySummaryImportsResponses.QUESTION_LABELS.get(q)));
        sheet.append("</row><row r=\"2\">").append(cell("A2","SYNTHETIC_PRIVATE_PERSON"));for(int q=0;q<10;q++)sheet.append("<c r=\"").append((char)('B'+q)).append("2\"><v>8</v></c>");
        sheet.append("</row></sheetData></worksheet>");Map<String,String>parts=new LinkedHashMap<>();
        parts.put("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
        parts.put("_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"root\" Type=\""+rel+"/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        parts.put("xl/workbook.xml","<workbook xmlns=\""+ns+"\" xmlns:r=\""+rel+"\"><sheets><sheet name=\""+SurveySummaryImportsResponses.SHEET_NAME+"\" sheetId=\"1\" r:id=\"sheet\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"sheet\" Type=\""+rel+"/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");parts.put("xl/worksheets/sheet1.xml",sheet.toString());
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(var entry:parts.entrySet()){ZipEntry item=new ZipEntry(entry.getKey());item.setTime(0);zip.putNextEntry(item);zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return out.toByteArray();
    }
    private static boolean insideCore(){for(StackTraceElement frame:Thread.currentThread().getStackTrace())if(frame.getClassName().startsWith("com.training.SurveySummaryImportsResponses"))return true;return false;}
    private static BooleanSupplier mutateNearEnd(Work mutation){AtomicBoolean done=new AtomicBoolean();return()->{if(insideCore()&&done.compareAndSet(false,true)){try{mutation.run();}catch(Exception e){throw new IllegalStateException("Synthetic mutation failed",e);}}return false;};}
    private static void freshness(String name,int expected,Work mutation,Work restore){run(name,()->{try{rejects(expected,()->SurveySummaryImportsIntegration.preview(worker,body(101),mutateNearEnd(mutation)));}finally{restore.run();}});}
    private static void unchangedBusiness()throws Exception{eq(6L,Db.count("projects"));eq(5L,Db.count("workflow_demands"));eq(5L,Db.count("workflow_acceptances"));}

    public static void main(String[]args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("One fresh empty synthetic H2 directory is required");
        Path data=Path.of(args[0]).toAbsolutePath().normalize();if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("Directory must exist and not be a symlink");try(var stream=Files.list(data)){if(stream.findAny().isPresent())throw new IllegalArgumentException("Directory must be empty");}
        try{
            fixtures(data);
            run("missing organization configuration denies",()->rejects(403,()->SurveySummaryImportsIntegration.config(worker)));
            publish(true,false,false);
            run("owned route prefix without unrelated route matching",()->{check(SurveySummaryImportsIntegration.matches(BASE+"/config"),"config path");check(SurveySummaryImportsIntegration.matches(BASE+"/preview"),"preview path");check(!SurveySummaryImportsIntegration.matches(BASE+"-extra/preview"),"must not catch adjacent prefix");check(!SurveySummaryImportsIntegration.matches("/api/projects"),"must not catch unrelated route");});
            run("config includes only authorized trusted sources",()->{Map<String,Object>c=SurveySummaryImportsIntegration.config(worker);eq(true,c.get("ready"));eq(false,c.get("synthetic"));eq(Set.of(101L),ids(c));eq(Set.of(102L),ids(SurveySummaryImportsIntegration.config(other)));});
            run("other organization bulk rows cannot exhaust scoped config",()->{
                try{
                    sql("INSERT INTO workflow_demands SELECT 20000+X,'002',1,1,FALSE FROM SYSTEM_RANGE(1,10001)");
                    sql("INSERT INTO projects SELECT 20000+X,20000+X,'OTHER_ORG_BULK','进行中' FROM SYSTEM_RANGE(1,10001)");
                    sql("INSERT INTO workflow_acceptances SELECT 20000+X,20000+X,1,'TEAM-B','ACTOR-B','2026-01-01T00:00:00Z' FROM SYSTEM_RANGE(1,10001)");
                    eq(Set.of(101L),ids(SurveySummaryImportsIntegration.config(worker)));
                }finally{
                    sql("DELETE FROM workflow_acceptances WHERE project_id BETWEEN 20001 AND 30001");
                    sql("DELETE FROM projects WHERE id BETWEEN 20001 AND 30001");
                    sql("DELETE FROM workflow_demands WHERE demand_id BETWEEN 20001 AND 30001");
                }
                unchangedBusiness();
            });
            run("authorized organization without projects is ready empty",()->{var c=SurveySummaryImportsIntegration.config(empty);eq(true,c.get("ready"));eq(0,projects(c).size());});
            run("old viewer with explicit grant can preview",()->{Map<String,Object>r=preview(worker,101);eq("PREVIEW",r.get("state"));eq(false,r.get("canCommit"));eq(false,r.get("policyConfirmed"));eq(false,r.get("synthetic"));eq("NOT_CHECKED",map(r.get("duplicateCheck")).get("status"));eq(1,r.get("responseRowCount"));check(!r.containsKey("fileFingerprint")&&!r.containsKey("scoreFingerprint"),"integration must omit fingerprints");check(!r.toString().contains("SYNTHETIC_PRIVATE_PERSON"),"individual identity leaked");});
            run("old administrator not implicit survey permission",()->rejects(403,()->preview(admin,101)));
            run("old unbound manager not implicit permission",()->rejects(403,()->preview(login(5),101)));
            run("demand grants do not imply survey preview",()->rejects(403,()->preview(reader,101)));
            run("wrong survey action is denied",()->{try{publish(true,true,false);rejects(403,()->preview(worker,101));}finally{publish(true,false,false);}});
            run("explicit DENY overrides ALLOW",()->{try{publish(true,false,true);rejects(403,()->preview(worker,101));}finally{publish(true,false,false);}});
            run("forged same uid session denied",()->{Auth.Session fake=new Auth.Session();fake.uid=worker.uid;fake.role="admin";rejects(401,()->preview(fake,101));});
            run("cross organization denied before base64 decoding",()->{Map<String,Object>b=body(102);b.put("xlsxBase64","%%%PRIVATE%%%");rejects(403,()->SurveySummaryImportsIntegration.preview(worker,b));});
            run("missing trusted source denied",()->rejectsAny(Set.of(403,409),()->preview(worker,103)));
            run("project demand source mismatch denied",()->rejectsAny(Set.of(403,409),()->preview(worker,104)));
            run("accepted revision mismatch denied",()->rejectsAny(Set.of(403,409),()->preview(worker,105)));
            run("draft demand source denied",()->rejectsAny(Set.of(403,409),()->preview(worker,106)));
            run("request cannot inject authorization fields",()->{for(String k:List.of("rules","permission","organizationCode","synthetic","previousImports")){Map<String,Object>b=body(101);b.put(k,"PRIVATE_INJECTION");rejects(400,()->SurveySummaryImportsIntegration.preview(worker,b));}});
            run("malformed base64 is safe generic error",()->{Map<String,Object>b=body(101);b.put("xlsxBase64","PRIVATE_ILLEGAL_%%%%");try{SurveySummaryImportsIntegration.preview(worker,b);throw new AssertionError("expected400");}catch(Api.ApiException e){eq(400,e.code);check(!e.getMessage().contains("PRIVATE"),"request text in error");}});
            run("base64 data URI rejected",()->{var b=body(101);b.put("xlsxBase64","data:application/octet-stream;base64,"+b.get("xlsxBase64"));rejects(400,()->SurveySummaryImportsIntegration.preview(worker,b));});
            run("encoded length budget before decoding",()->{var b=body(101);b.put("xlsxBase64","A".repeat(6990512));rejects(413,()->SurveySummaryImportsIntegration.preview(worker,b));});
            run("decoded byte budget rechecked at allowed encoded length",()->{var b=body(101);b.put("xlsxBase64","A".repeat(6990508));rejects(413,()->SurveySummaryImportsIntegration.preview(worker,b));});
            run("safe integer project id validation",()->{for(Object v:List.of(0,-1,1.5,Double.NaN,"9007199254740992","101junk")){var b=body(101);b.put("projectId",v);rejects(400,()->SurveySummaryImportsIntegration.preview(worker,b));}});
            run("inside shared mutation lock rejected",()->{synchronized(Api.MUTATION_LOCK){rejects(503,()->preview(worker,101));}});
            run("open business transaction rejected",()->{synchronized(Api.MUTATION_LOCK){try{Db.get().setAutoCommit(false);rejects(409,()->SurveySummaryImportsIntegration.config(worker));}finally{Db.get().rollback();Db.get().setAutoCommit(true);}}});
            run("cancellation returns499 and releases capacity",()->{rejects(499,()->SurveySummaryImportsIntegration.preview(worker,body(101),()->true));eq("PREVIEW",preview(worker,101).get("state"));});
            run("checkpoint probe confirms parsing outside shared lock",()->{AtomicInteger calls=new AtomicInteger();AtomicBoolean locked=new AtomicBoolean();SurveySummaryImportsIntegration.preview(worker,body(101),()->{calls.incrementAndGet();if(insideCore()&&Thread.holdsLock(Api.MUTATION_LOCK))locked.set(true);return false;});normalCheckpoints=calls.get();check(normalCheckpoints>10,"enough cooperative parser checkpoints");check(!locked.get(),"parser held global lock");});
            freshness("configuration changes during preview rejected",409,()->publish(true,false,false),()->{});
            freshness("survey permission publication invalidates in-flight session",401,()->publish(false,false,false),()->publish(true,false,false));
            freshness("source title snapshot changes rejected",409,()->sql("UPDATE projects SET title='CHANGED' WHERE id=101"),()->sql("UPDATE projects SET title='SYNTHETIC PROJECT A' WHERE id=101"));
            freshness("source status snapshot changes rejected",409,()->sql("UPDATE projects SET status='已完成' WHERE id=101"),()->sql("UPDATE projects SET status='进行中' WHERE id=101"));
            freshness("acceptance snapshot changes rejected",409,()->sql("UPDATE workflow_acceptances SET actor_code='OTHER' WHERE project_id=101"),()->sql("UPDATE workflow_acceptances SET actor_code='ACTOR-A' WHERE project_id=101"));
            freshness("organization changes during preview rejected",403,()->sql("UPDATE workflow_demands SET organization_code='002' WHERE demand_id=11"),()->sql("UPDATE workflow_demands SET organization_code='001' WHERE demand_id=11"));
            freshness("session logout during preview rejected",401,()->Auth.logout(workerToken),M07IntegrationTest::loginWorker);
            freshness("account disabled during preview rejected",401,()->sql("UPDATE users SET status=0 WHERE id=2"),()->{sql("UPDATE users SET status=1 WHERE id=2");loginWorker();});
            run("global two slots and account one slot",M07IntegrationTest::concurrency);
            run("real timeout retains lease until actual work exits",M07IntegrationTest::timeout);
            run("no business rows or historical imports persisted",M07IntegrationTest::unchangedBusiness);
            run("HTTP authentication checked before reading body",()->{ProbeExchange ex=new ProbeExchange("POST",BASE+"/preview",null,"PRIVATE_BAD_BODY");rejects(401,()->SurveySummaryImportsIntegration.handle(ex,null));eq(0,ex.readCount);});
            run("HTTP survey permission checked before reading body",()->{ProbeExchange ex=new ProbeExchange("POST",BASE+"/preview",readerToken,"PRIVATE_BAD_BODY");rejects(403,()->SurveySummaryImportsIntegration.handle(ex,reader));eq(0,ex.readCount);});
            run("HTTP supplied session must match token",()->{ProbeExchange ex=new ProbeExchange("POST",BASE+"/preview",workerToken,Json.write(body(101)));rejects(401,()->SurveySummaryImportsIntegration.handle(ex,peer));eq(0,ex.readCount);});
            run("HTTP unrelated path left untouched",()->{ProbeExchange ex=new ProbeExchange("GET","/api/projects",null,"");eq(false,SurveySummaryImportsIntegration.handle(ex,null));eq(0,ex.readCount);});
            run("isolated loopback HTTP contract",M07IntegrationTest::http);
        }finally{try{Db.get().close();}catch(Exception ignored){}}
        System.out.println("M07 INTEGRATION SCENARIOS: passed="+passed+" failed="+failed+" (isolated handler; main Api not mounted)");
        if(failed>0)throw new AssertionError(failed+" scenarios failed");
    }
    private static void concurrency()throws Exception{
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);List<Future<Map<String,Object>>>jobs=new ArrayList<>();
        try{
            for(Auth.Session s:List.of(worker,peer)){AtomicBoolean parked=new AtomicBoolean();jobs.add(pool.submit(()->SurveySummaryImportsIntegration.preview(s,body(101),()->{if(insideCore()&&parked.compareAndSet(false,true)){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("gate timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();return true;}}return false;})));}
            check(entered.await(5,TimeUnit.SECONDS),"both global slots entered parser without lock serialization");
            rejects(429,()->preview(worker,101));rejects(429,()->preview(third,101));
        }finally{release.countDown();try{for(var job:jobs){try{job.get(5,TimeUnit.SECONDS);}catch(ExecutionException e){throw new AssertionError("concurrent worker failed",e.getCause());}}}finally{pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}}
        eq("PREVIEW",preview(worker,101).get("state"));
    }
    private static void timeout()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();AtomicBoolean parked=new AtomicBoolean();Future<Map<String,Object>>job=null;
        try{
            job=pool.submit(()->SurveySummaryImportsIntegration.preview(worker,body(101),()->{if(insideCore()&&parked.compareAndSet(false,true)){entered.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new AssertionError("timeout gate not released");}catch(InterruptedException e){Thread.currentThread().interrupt();return true;}}return false;}));
            check(entered.await(5,TimeUnit.SECONDS),"timeout worker entered parser");rejects(429,()->preview(worker,101));
            Thread.sleep(SurveySummaryImportsIntegration.MAX_REQUEST_MILLIS+300);
            check(!job.isDone(),"actual parser still blocked");rejects(429,()->preview(worker,101));
            release.countDown();try{job.get(5,TimeUnit.SECONDS);throw new AssertionError("expected actual timeout408");}catch(ExecutionException e){check(e.getCause()instanceof Api.ApiException,"timeout must be safe ApiException");eq(408,((Api.ApiException)e.getCause()).code);}
        }finally{release.countDown();if(job!=null&&!job.isDone())job.cancel(true);pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
        eq("PREVIEW",preview(worker,101).get("state"));
    }
    private static Map<String,Object> request(int port,String method,String path,String token,String body,String contentType,int expected)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(20000);c.setRequestMethod(method);if(token!=null)c.setRequestProperty("X-Token",token);if(contentType!=null)c.setRequestProperty("Content-Type",contentType);
        try{if(body!=null){c.setDoOutput(true);byte[]bytes=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}int status=c.getResponseCode();eq(expected,status);InputStream in=status>=400?c.getErrorStream():c.getInputStream();String text=in==null?"":new String(in.readAllBytes(),StandardCharsets.UTF_8);check(!text.contains("SYNTHETIC_PRIVATE_PERSON"),"HTTP identity leaked");return text.isEmpty()?Map.of():map(Json.parse(text));}finally{c.disconnect();}
    }
    private static void http()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService executor=Executors.newFixedThreadPool(4);server.setExecutor(executor);
        Method flush=Api.class.getDeclaredMethod("flushResponse",HttpExchange.class);flush.setAccessible(true);
        server.createContext("/",ex->{try{Auth.Session s;synchronized(Api.MUTATION_LOCK){s=Auth.get(Api.token(ex));}if(!SurveySummaryImportsIntegration.handle(ex,s))Api.err(ex,404,"TEST_ROUTE_NOT_FOUND");}catch(Api.ApiException e){Api.err(ex,e.code,e.getMessage());}catch(Exception e){Api.err(ex,500,"TEST_INTERNAL_ERROR");}finally{try{flush.invoke(null,ex);}catch(Exception ignored){}ex.close();}});
        server.start();int port=server.getAddress().getPort();
        try{
            eq(0,((Number)request(port,"GET",BASE+"/config",workerToken,null,null,200).get("code")).intValue());
            var result=request(port,"POST",BASE+"/preview",workerToken,Json.write(body(101)),"application/json",200);eq("PREVIEW",map(result.get("data")).get("state"));
            request(port,"POST",BASE+"/preview",workerToken,Json.write(body(101)),"text/plain",415);
            request(port,"GET",BASE+"/preview",workerToken,null,null,405);
            request(port,"POST",BASE+"/preview",workerToken,"{","application/json",400);
            request(port,"POST",BASE+"/preview",workerToken,"[]","application/json",400);
            request(port,"POST",BASE+"/preview",workerToken,"{\"fileName\":{},\"xlsxBase64\":\"AAAA\",\"projectId\":101}","application/json",400);
            request(port,"POST",BASE+"/preview",workerToken,"{\"fileName\":\"x.xlsx\",\"xlsxBase64\":[],\"projectId\":101}","application/json",400);
            request(port,"POST",BASE+"/preview",workerToken,"{\"fileName\":\"x.xlsx\",\"xlsxBase64\":\"AAAA\",\"projectId\":101,\"injected\":true}","application/json",400);
            request(port,"POST",BASE+"/preview?organizationCode=002",workerToken,Json.write(body(101)),"application/json",400);
            request(port,"GET",BASE+"/config?uid=1",workerToken,null,null,400);
            request(port,"POST",BASE+"/preview",null,"PRIVATE_BAD_JSON","application/json",401);
            String whitespace=" ".repeat(7*1024*1024+1);request(port,"POST",BASE+"/preview",workerToken,whitespace,"application/json",413);
            // Send a partial body and verify the integration's own 15-second upload deadline closes it.
            long start=System.nanoTime();try(Socket socket=new Socket("127.0.0.1",port)){socket.setSoTimeout(22000);String prefix="POST "+BASE+"/preview HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Token: "+workerToken+"\r\nContent-Type: application/json\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n{";socket.getOutputStream().write(prefix.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();socket.getInputStream().readAllBytes();}
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);check(elapsed>=10000&&elapsed<22000,"upload deadline elapsed="+elapsed);
            eq("PREVIEW",map(request(port,"POST",BASE+"/preview",workerToken,Json.write(body(101)),"application/json",200).get("data")).get("state"));
        }finally{server.stop(0);executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);}
    }
    private static final class ProbeExchange extends HttpExchange{
        final Headers request=new Headers(),response=new Headers();final Map<String,Object>attributes=new HashMap<>();final String method;final URI uri;final byte[]body;int readCount;boolean closed;
        ProbeExchange(String method,String path,String token,String text){this.method=method;uri=URI.create(path);body=text.getBytes(StandardCharsets.UTF_8);request.set("Content-Type","application/json");if(token!=null)request.set("X-Token",token);}
        @Override public Headers getRequestHeaders(){return request;}@Override public Headers getResponseHeaders(){return response;}@Override public URI getRequestURI(){return uri;}@Override public String getRequestMethod(){return method;}@Override public HttpContext getHttpContext(){return null;}
        @Override public void close(){closed=true;}@Override public InputStream getRequestBody(){return new ByteArrayInputStream(body){@Override public synchronized int read(byte[]b,int off,int len){readCount++;return super.read(b,off,len);}@Override public synchronized int read(){readCount++;return super.read();}};}
        @Override public OutputStream getResponseBody(){return new ByteArrayOutputStream();}@Override public void sendResponseHeaders(int status,long length){}@Override public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",0);}@Override public int getResponseCode(){return 200;}@Override public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",0);}@Override public String getProtocol(){return"HTTP/1.1";}
        @Override public Object getAttribute(String name){return attributes.get(name);}@Override public void setAttribute(String name,Object value){attributes.put(name,value);}@Override public void setStreams(InputStream in,OutputStream out){}@Override public HttpPrincipal getPrincipal(){return null;}
    }
}
