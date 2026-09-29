package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import static com.training.OrganizationAccess.*;

/** Entirely synthetic H2, real Auth/M01/M05 and the actual successful teaching download. */
public final class M06TeachingExportTest {
    private static int checks; private static Auth.Session admin, worker, other; private static String token;
    private static final String PASSWORD="SYNTHETIC-TEACHING-EXPORT-ONLY";
    private static final String DAY=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
    private static final Map<String,Object> FILTER=Map.of("start",DAY,"end",DAY,"date_basis","TEACHING","organizations","001");
    private static final String LONG="1234567890123456.12345678";
    private static final List<String> PRIVATE=List.of("PRIVATE-TEACHER", "PRIVATE-PROJECT", "PRIVATE-SUBJECT", "PRIVATE-EVIDENCE", "13800138000", "private@example.invalid", "HYPERLINK", "cmd|'", "PHONE-SHOULD-NOT-EXPORT");
    @FunctionalInterface private interface Work {void run()throws Exception;}
    private static void check(boolean ok,String label) {checks++;if(!ok)throw new AssertionError(label);}
    private static void rejects(int code,Work work,String label)throws Exception {try{work.run();throw new AssertionError("Expected rejection: "+label);}catch(Api.ApiException e){check(e.code==code,label+" status="+e.code+" "+e.getMessage());}}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private static long number(Object value){return new BigDecimal(value.toString()).longValueExact();}
    private static Map<String,Object> read()throws Exception{return ManagementReportsIntegration.read(worker,FILTER);}
    private static Map<String,Object> query(Map<String,Object> view){Map<String,Object> q=new LinkedHashMap<>(FILTER);q.put("snapshot_version",view.get("snapshot_version"));return q;}
    private static void login()throws Exception{token=Auth.login("export-worker",PASSWORD);worker=Auth.get(token);other=Auth.get(Auth.login("export-other",PASSWORD));}
    private static Configuration configuration(String version,boolean export,boolean read,boolean deny){
        RelationRule optional=new RelationRule(false,Set.of("OPERATOR"),false,false);List<Grant> grants=new ArrayList<>();
        for(String resource:List.of("delivery.read","delivery.write","delivery.verify","reports.read","reports.export")) {
            if(resource.equals("reports.export")&&!export||resource.equals("reports.read")&&!read)continue;
            grants.add(new Grant("GRANT-"+resource,"OPERATOR",resource,resource.endsWith(".read")?Action.VIEW:resource.equals("reports.export")?Action.EXPORT:Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        }
        if(deny)grants.add(new Grant("DENY-EXPORT","OPERATOR","reports.export",Action.EXPORT,Effect.DENY,Scope.OWN_ORG,Set.of()));
        return new Configuration(version,new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),Set.of("OPERATOR"),
                List.of(new Organization("001",null,true),new Organization("002",null,true)),
                List.of(new Person("0002","001",Set.of(),null,null,Set.of("OPERATOR"),true),new Person("0003","002",Set.of(),null,null,Set.of("OPERATOR"),true)),
                List.of(new RoleRelations("OPERATOR",optional,optional)),List.of(new AccountBinding(2,"0002",true),new AccountBinding(3,"0003",true)),grants);
    }
    private static void fixtures(Path data)throws Exception {
        System.setProperty("data.dir",data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        // Current Auth/M01 also require the real first-bind and trusted-device schemas.
        FirstBindStore.init(); TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id BIGINT PRIMARY KEY,title VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,title VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,name VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE dispatches(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,subject VARCHAR(200),teach_date VARCHAR(32),status VARCHAR(32))");
        String password=Auth.hash(PASSWORD);
        Db.exec("INSERT INTO users VALUES(1,'export-admin',?,'PRIVATE-ADMIN','admin',1)",password);
        Db.exec("INSERT INTO users VALUES(2,'export-worker',?,'PRIVATE-WORKER','viewer',1)",password);
        Db.exec("INSERT INTO users VALUES(3,'export-other',?,'PRIVATE-OTHER','viewer',1)",password);
        admin=Auth.get(Auth.login("export-admin",PASSWORD));login();
        OrganizationAccessStore.init();WorkflowIntegration.init();NotificationChannelsIntegration.init();DeliverySettlementIntegration.init();
        OrganizationAccessStore.publish(admin,null,configuration("EXPORT-1",true,true,false));login();
        for(int i=1;i<=2;i++) {
            Db.exec("INSERT INTO demands VALUES(?,?,'团队已受理')",i,"PRIVATE-DEMAND");
            Db.exec("INSERT INTO projects VALUES(?,?,?,'进行中')",i+9,i,"=HYPERLINK(\"https://private@example.invalid\",\"PRIVATE-PROJECT\")");
            Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct',?,'0002','{}','{}','SYNTHETIC-M03')",i,i==1?"001":"002");
            Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'001','0002',1,'2026-01-01T00:00:00Z')",i,i+9);
        }
        for(int t=20;t<=22;t++)Db.exec("INSERT INTO teachers VALUES(?,?,'在库')",t,"+cmd|'PRIVATE-TEACHER'!A0 13800138000");
        for(int id=101;id<=110;id++)Db.exec("INSERT INTO dispatches VALUES(?,10,?,? ,?,'已确认')",id,id==103?21:id==104?22:20,"@PRIVATE-SUBJECT private@example.invalid",id==110?null:DAY);
        Db.exec("INSERT INTO dispatches VALUES(201,11,20,'PHONE-SHOULD-NOT-EXPORT',?,'已确认')",DAY);
        save(worker,101,LONG,"0","60",null,true);save(worker,102,"0",null,"0","0",true);
        save(worker,103,"1.00000001","2.25","60","1.5",true);save(worker,104,null,"3","45",null,true);
        save(worker,105,"9","9","45","9",false); // deliberately unverified completed source
        Db.exec("UPDATE dispatches SET status='已完成' WHERE id=105");
        save(worker,106,"8","8","45","8",true);Db.exec("UPDATE dispatches SET status='已确认' WHERE id=106");
        Db.exec("UPDATE dispatches SET status='已完成' WHERE id=107"); // missing fact
        Db.exec("UPDATE dispatches SET status='已拒绝' WHERE id=108");
        save(worker,109,"7","7","60","7",true);
        Map<String,Object> broken=map(Json.parse(Db.one("SELECT payload FROM m05_delivery_facts WHERE dispatch_id=109").get("payload").toString()));
        map(broken.get("conversion")).put("minutes","45");String corrupted=Json.write(broken);
        Db.exec("UPDATE m05_delivery_facts SET payload=? WHERE dispatch_id=109",corrupted);
        Db.exec("UPDATE m05_fact_revisions SET payload=? WHERE dispatch_id=109 AND revision=2",corrupted);
        save(other,201,"999","999","45","999",true);
        check(Db.get().getMetaData().getURL().contains(data.resolve("training").toString()),"isolated H2 only");
    }
    private static void save(Auth.Session actor,long id,String estimated,String planned,String minutes,String payable,boolean verify)throws Exception {
        Map<String,Object> body=new LinkedHashMap<>();body.put("dispatch_id",id);body.put("expected_version",0);body.put("request_id","SAVE-"+id);
        body.put("estimated_hours",estimated);body.put("planned_hours",planned);body.put("actual_minutes",minutes);body.put("payable_hours",payable);
        DeliverySettlementIntegration.mutate("save",actor,body);
        if(verify){DeliverySettlementIntegration.mutate("verify",actor,Map.of("dispatch_id",id,"expected_version",1,"request_id","VERIFY-"+id,"evidence_code","PRIVATE-EVIDENCE-"+id));
            DeliverySettlementIntegration.requireCompletion(actor,id,2);Db.exec("UPDATE dispatches SET status='已完成' WHERE id=?",id);}
    }
    private static String dbHash()throws Exception {
        Map<String,Object> data=new TreeMap<>();
        for(Map<String,Object> table:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME")) {
            String name=table.get("table_name").toString();data.put(name,Db.query("SELECT * FROM \""+name.replace("\"","\"\"")+"\"").stream().map(Json::write).sorted().toList());
        }
        data.put("columns",Db.query("SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME,ORDINAL_POSITION"));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(data).getBytes(StandardCharsets.UTF_8)));
    }
    private static Map<String,Document> inspect(byte[] bytes)throws Exception {
        Map<String,Document> docs=new LinkedHashMap<>();DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
                String path=entry.getName();byte[] data=zip.readAllBytes();String xml=new String(data,StandardCharsets.UTF_8);
                check(!path.contains("vba")&&!path.contains("external")&&!path.contains("comments")&&!path.contains("customXml"),"no macros external connections comments or private XML");
                for(String secret:PRIVATE)check(!xml.contains(secret),"no private sentinel: "+secret);
                Document doc=f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
                check(doc.getElementsByTagNameNS("*","f").getLength()==0&&doc.getElementsByTagNameNS("*","hyperlink").getLength()==0,"no formulas or hyperlink elements");
                NodeList rel=doc.getElementsByTagNameNS("*","Relationship");for(int i=0;i<rel.getLength();i++)check(!"External".equals(((Element)rel.item(i)).getAttribute("TargetMode")),"all relationships internal");
                check(docs.put(path,doc)==null,"unique ZIP entry");
            }
        }
        check(docs.size()==11&&docs.get("xl/workbook.xml").getElementsByTagNameNS("*","sheet").getLength()==6,"six actual OOXML sheets and minimal package");
        return docs;
    }
    private static Element cell(Document sheet,String ref) {
        NodeList cells=sheet.getElementsByTagNameNS("*","c");for(int i=0;i<cells.getLength();i++)if(ref.equals(((Element)cells.item(i)).getAttribute("r")))return (Element)cells.item(i);
        throw new AssertionError("Missing cell "+ref);
    }
    private static String value(Document sheet,String ref){return cell(sheet,ref).getTextContent();}
    private static String url(Map<String,Object> q){return "/api/management-reports/teaching-export?"+q.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue().toString(),StandardCharsets.UTF_8)).reduce((a,b)->a+"&"+b).orElse("");}
    private static void aggregatePrecision()throws Exception {
        String date=LocalDate.parse(DAY).minusDays(2).toString(),large="999999999999999999999999",expected="1999999999999999999999998.00000001";
        for(int id=301;id<=303;id++) {
            Db.exec("INSERT INTO dispatches VALUES(?,10,20,'PRIVATE-SUBJECT',?,'已确认')",id,date);
            save(worker,id,id==303?"0.00000001":large,"0","45",null,true);
        }
        Map<String,Object> filter=Map.of("start",date,"end",date,"organizations","001"),view=ManagementReportsIntegration.read(worker,filter);
        check(expected.equals(map(map(view.get("totals")).get("estimated")).get("value")),"real M05 24-digit sources plus scale-8 source produce exact 33-digit total");
        String before=dbHash();Map<String,Object> q=new LinkedHashMap<>(filter);q.put("snapshot_version",view.get("snapshot_version"));
        Map<String,Document> docs=inspect(ManagementReportsIntegration.downloadTeaching(worker,q));
        for(String[] target:List.of(new String[]{"2","B4"},new String[]{"3","E4"},new String[]{"4","D4"})) {
            Document sheet=docs.get("xl/worksheets/sheet"+target[0]+".xml");
            check(expected.equals(value(sheet,target[1]))&&"inlineStr".equals(cell(sheet,target[1]).getAttribute("t")),"33-digit aggregate retained as precise text in totals/ranking/organization sheet "+target[0]);
        }
        Document detail=docs.get("xl/worksheets/sheet5.xml");
        check(large.equals(value(detail,"G4"))&&large.equals(value(detail,"G5"))&&"0.00000001".equals(value(detail,"G6")),"detail keeps original 24-digit and 8-decimal source precision");
        check(before.equals(dbHash()),"aggregate precision export is read-only");
    }
    private static final class Exchange extends HttpExchange {
        final Headers req=new Headers(),res=new Headers();final Map<String,Object> attributes=new HashMap<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();final URI uri;final String method;int status;
        Exchange(String method,String uri,String token){this.method=method;this.uri=URI.create(uri);if(token!=null)req.set("X-Token",token);}
        public Headers getRequestHeaders(){return req;}public Headers getResponseHeaders(){return res;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return InputStream.nullInputStream();}public OutputStream getResponseBody(){return bytes;}
        public void sendResponseHeaders(int code,long length){check(!Thread.holdsLock(Api.MUTATION_LOCK),"binary HTTP flush occurs outside business lock");status=code;}
        public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}public InetSocketAddress getLocalAddress(){return getRemoteAddress();}public int getResponseCode(){return status;}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String k){return attributes.get(k);}public void setAttribute(String k,Object v){attributes.put(k,v);}public void setStreams(InputStream a,OutputStream b){}public HttpPrincipal getPrincipal(){return null;}
    }
    private static void http(Map<String,Object> view,byte[] expected)throws Exception {
        Exchange ex=new Exchange("GET",url(query(view)),token);String before=dbHash();Api.handle(ex);
        check(ex.status==200&&Arrays.equals(expected,ex.bytes.toByteArray()),"actual Api.handle successful download returns exact workbook");
        check(ManagementTeachingWorkbook.CONTENT_TYPE.equals(ex.res.getFirst("Content-Type"))&&"no-store".equals(ex.res.getFirst("Cache-Control")),"XLSX MIME and no-store");
        check(("attachment; filename=\"reviewed-teaching-"+DAY.replace("-","")+"-"+DAY.replace("-","")+".xlsx\"").equals(ex.res.getFirst("Content-Disposition")),"safe fixed ASCII filename");
        for(String extra:List.of("&start="+DAY,"&%73tart="+DAY,"&snapshot_version="+view.get("snapshot_version"),"&facts=%5B%5D","&actor=0002","&unexpected=1","&bad")) {
            Exchange bad=new Exchange("GET",url(query(view))+extra,token);Api.handle(bad);
            check(bad.status==400&&bad.res.getFirst("Content-Disposition")==null&&bad.res.getFirst("Content-Type").contains("json"),"duplicate/extra malformed query refuses file: "+extra.split("=")[0]);
        }
        Exchange noLogin=new Exchange("GET",url(query(view)),null);Api.handle(noLogin);check(noLogin.status==401,"HTTP requires real session");
        Exchange head=new Exchange("HEAD",url(query(view)),token);Api.handle(head);check(head.status==405,"GET only");
        Exchange mismatch=new Exchange("GET",url(query(view)),token);rejects(401,()->ManagementReportsIntegration.handle(mismatch,other),"HTTP supplied session must match token");
        check(before.equals(dbHash()),"HTTP success and failures leave every table unchanged");
    }
    public static void main(String[] args)throws Exception {
        if(args.length<1||args.length>2)throw new IllegalArgumentException("fresh empty synthetic H2 directory; optional XLSX fixture output");
        Path data=Path.of(args[0]).toAbsolutePath();if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("empty test directory required");
        try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new IllegalArgumentException("test directory must be empty");}
        fixtures(data);String before=dbHash();Map<String,Object> view=read();
        check(number(view.get("included_count"))==4&&number(view.get("excluded_count"))==6,"only four verified completed facts, explicit six exclusions");
        check(Boolean.TRUE.equals(view.get("teaching_export_available"))&&!Boolean.TRUE.equals(map(view.get("availability")).get("formal_export")),"new teaching capability without enabling formal export");
        byte[] bytes=ManagementReportsIntegration.downloadTeaching(worker,query(view));Map<String,Document> docs=inspect(bytes);
        Document summary=docs.get("xl/worksheets/sheet2.xml"),detail=docs.get("xl/worksheets/sheet5.xml"),rank=docs.get("xl/worksheets/sheet3.xml"),reasons=docs.get("xl/worksheets/sheet6.xml");
        check("未知".equals(value(summary,"B4"))&&"1234567890123457.12345679".equals(value(summary,"C4"))&&"inlineStr".equals(cell(summary,"C4").getAttribute("t")),"unknown total and exact long known subtotal kept separately as text");
        check("3.66".equals(value(summary,"B6"))&&"n".equals(cell(summary,"B6").getAttribute("t")),"actual sum uses per-line 1.33+0+1.33+1.00");
        check("101".equals(value(detail,"A4"))&&"inlineStr".equals(cell(detail,"A4").getAttribute("t"))&&"001".equals(value(detail,"D4")),"internal ID and leading-zero organization stored as text");
        check(LONG.equals(value(detail,"G4"))&&"未知".equals(value(detail,"J4"))&&"0.00".equals(value(detail,"I5")),"detail precision null and valid zero preserved");
        check("1".equals(value(rank,"A4"))&&"1".equals(value(rank,"A8"))&&"3".equals(value(rank,"A12")),"same competition ranks 1 1 3 across category rows");
        check(reasons.getDocumentElement().getTextContent().contains("日期未知、无法归月")&&reasons.getDocumentElement().getTextContent().contains("ACTUAL_EVIDENCE_INVALID")&&reasons.getDocumentElement().getTextContent().contains("NOT_VERIFIED"),"unknown dates corrupt conversion unverified sources explicit");
        check(!detail.getDocumentElement().getTextContent().contains("201")&&!reasons.getDocumentElement().getTextContent().contains("201"),"out-of-scope system ID not exported");
        // Even if a trusted caller accidentally adds free fields, the serializer uses its fixed whitelist.
        Map<String,Object> poisoned=map(Json.parse(Json.write(view)));for(Map<String,Object> r:rows(poisoned.get("excluded")))r.put("message","=HYPERLINK(\"PRIVATE-EVIDENCE\")");
        check(Arrays.equals(bytes,ManagementTeachingWorkbook.export(poisoned)),"fixed reason dictionary ignores source free text and injected formulas");
        check(before.equals(dbHash()),"successful complete download and all checks are read-only");http(view,bytes);
        if(args.length==2)Files.write(Path.of(args[1]),bytes);
        Map<String,Object> wrong=query(view);wrong.put("snapshot_version","0".repeat(64));rejects(409,()->ManagementReportsIntegration.downloadTeaching(worker,wrong),"CAS mismatch");
        Map<String,Object> cross=query(view);cross.put("organizations","001,002");rejects(403,()->ManagementReportsIntegration.downloadTeaching(worker,cross),"mixed cross-organization scope refused");
        Map<String,Object> payment=query(view);payment.put("date_basis","PAYMENT");rejects(409,()->ManagementReportsIntegration.downloadTeaching(worker,payment),"payment remains unavailable");
        rejects(409,()->ManagementReportsIntegration.download(worker,query(view)),"original formal export still blocked");
        Auth.Session forged=new Auth.Session();forged.uid=worker.uid;rejects(401,()->ManagementReportsIntegration.downloadTeaching(forged,query(view)),"copied uid not authenticated");
        Db.exec("UPDATE dispatches SET status='已确认' WHERE id=102");String afterChange=dbHash();
        rejects(409,()->ManagementReportsIntegration.downloadTeaching(worker,query(view)),"current facts changed after read");check(afterChange.equals(dbHash()),"stale failure does not mutate database");Db.exec("UPDATE dispatches SET status='已完成' WHERE id=102");
        Map<String,Object> emptyFilter=Map.of("start","2000-01-01","end","2000-01-31","organizations","001");Map<String,Object> empty=ManagementReportsIntegration.read(worker,emptyFilter),emptyQuery=new LinkedHashMap<>(emptyFilter);emptyQuery.put("snapshot_version",empty.get("snapshot_version"));
        Map<String,Document> emptyDocs=inspect(ManagementReportsIntegration.downloadTeaching(worker,emptyQuery));
        check("0".equals(value(emptyDocs.get("xl/worksheets/sheet2.xml"),"B6"))&&emptyDocs.get("xl/worksheets/sheet5.xml").getElementsByTagNameNS("*","row").getLength()==3,"empty period has no invented details with zero actual total");
        if(args.length==2)Files.write(Path.of(args[1]).resolveSibling("empty-teaching.xlsx"),ManagementReportsIntegration.downloadTeaching(worker,emptyQuery));
        OrganizationAccessStore.publish(admin,"EXPORT-1",configuration("EXPORT-2",true,true,false));
        rejects(409,()->ManagementReportsIntegration.downloadTeaching(worker,query(view)),"access version change invalidates previous projection without altering rules");
        OrganizationAccessStore.publish(admin,"EXPORT-2",configuration("EXPORT-3",false,true,false));
        rejects(401,()->ManagementReportsIntegration.downloadTeaching(worker,query(view)),"revoked access invalidates captured real session");login();Map<String,Object> readOnly=read();
        check(Boolean.FALSE.equals(readOnly.get("teaching_export_available"))&&number(readOnly.get("included_count"))==4,"view remains available without export grant");
        rejects(403,()->ManagementReportsIntegration.downloadTeaching(worker,query(readOnly)),"read grant does not grant export");
        OrganizationAccessStore.publish(admin,"EXPORT-3",configuration("EXPORT-4",true,true,true));login();rejects(403,()->ManagementReportsIntegration.downloadTeaching(worker,query(read())),"explicit export DENY wins");
        OrganizationAccessStore.publish(admin,"EXPORT-4",configuration("EXPORT-5",true,false,false));login();rejects(403,()->ManagementReportsIntegration.downloadTeaching(worker,query(view)),"export grant alone cannot read");
        OrganizationAccessStore.publish(admin,"EXPORT-5",configuration("EXPORT-6",true,true,false));login();aggregatePrecision();Map<String,Object> latest=read();before=dbHash();
        Db.get().setAutoCommit(false);try {Db.exec("INSERT INTO demands VALUES(999,'OUTER','待处理')");rejects(409,()->ManagementReportsIntegration.downloadTeaching(worker,query(latest)),"no nested transaction commit");}finally{Db.get().rollback();Db.get().setAutoCommit(true);}
        check(before.equals(dbHash()),"outer transaction remains rollbackable");Auth.logout(token);rejects(401,()->ManagementReportsIntegration.downloadTeaching(worker,query(latest)),"logout rechecked at download");check(before.equals(dbHash()),"permission and logout rejection read-only");
        Db.exec("SHUTDOWN");System.out.println("M06TeachingExport: "+checks+" checks passed; real Auth/M01/M05/Api, isolated H2 only");
    }
}
