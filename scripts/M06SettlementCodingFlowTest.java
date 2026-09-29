package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static com.training.OrganizationAccess.*;
import static com.training.ManagementSettlementAccounting.*;

/** Isolated real Auth/M01/M04/M05 audited coding through Api.handle; no production data or transfers. */
public final class M06SettlementCodingFlowTest {
    static int checks,request;static long scope;static String token;static Auth.Session worker;
    static final String CHAIN="CODING-COMPAT-A",DAY=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    static final String REPORT="/api/management-settlement-reports",CODING="/api/delivery-settlement/coding";
    static void check(boolean b,String msg){checks++;if(!b)throw new AssertionError(msg);}
    static void eq(Object a,Object b,String msg){check(Objects.equals(a,b),msg+" actual="+a+" expected="+b);}
    static long number(Object x){return new java.math.BigDecimal(x.toString()).longValueExact();}
    static Map<String,Object> copy(Map<String,Object>x){return obj(Json.parse(Json.write(x)));}
    static String requestId(){return "M06-CODING-REQUEST-"+(++request);}
    static void login()throws Exception{M06SettlementBridgeTest.login();token=M06SettlementBridgeTest.token;worker=M06SettlementBridgeTest.worker;}
    static void configure(String old,String version,boolean catalogRead,boolean export)throws Exception{
        List<Grant> grants=new ArrayList<>();for(String resource:List.of("reports.read","reports.export","delivery.read","settlement.confirm","settlement.correct","catalog.read","catalog.manage")){
            if(!catalogRead&&resource.equals("catalog.read")||!export&&resource.equals("reports.export"))continue;
            grants.add(new Grant("G-"+resource,"OPERATOR",resource,resource.endsWith(".read")?Action.VIEW:resource.endsWith(".export")?Action.EXPORT:Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        }
        RelationRule optional=new RelationRule(false,Set.of("OPERATOR"),false,false);
        Configuration c=new Configuration(version,new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),Set.of("OPERATOR"),List.of(new Organization("001",null,true,"PRIVATE-ORG-ONE"),new Organization("002",null,true,"PRIVATE-ORG-TWO")),List.of(new Person("0002","001",Set.of(),null,null,Set.of("OPERATOR"),true),new Person("0003","002",Set.of(),null,null,Set.of("OPERATOR"),true)),List.of(new RoleRelations("OPERATOR",optional,optional)),List.of(new AccountBinding(2,"0002",true),new AccountBinding(3,"0003",true)),grants);
        OrganizationAccessStore.publish(M06SettlementBridgeTest.admin,old,c);login();
    }
    static final class Exchange extends HttpExchange {
        final Headers requestHeaders=new Headers(),responseHeaders=new Headers();final URI uri;final String method;final byte[] body;final ByteArrayOutputStream response=new ByteArrayOutputStream();final Map<String,Object> attributes=new HashMap<>();int status;
        Exchange(String method,String uri,Map<String,Object> body,String token){this.method=method;this.uri=URI.create(uri);this.body=body==null?new byte[0]:Json.write(body).getBytes(StandardCharsets.UTF_8);if(token!=null)requestHeaders.set("X-Token",token);requestHeaders.set("Content-Type","application/json");}
        public Headers getRequestHeaders(){return requestHeaders;}public Headers getResponseHeaders(){return responseHeaders;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return new ByteArrayInputStream(body);}public OutputStream getResponseBody(){return response;}public void sendResponseHeaders(int c,long length){check(!Thread.holdsLock(Api.MUTATION_LOCK),"Api flush outside business lock");status=c;}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}public InetSocketAddress getLocalAddress(){return getRemoteAddress();}public int getResponseCode(){return status;}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String key){return attributes.get(key);}public void setAttribute(String key,Object value){attributes.put(key,value);}public void setStreams(InputStream input,OutputStream output){}public HttpPrincipal getPrincipal(){return null;}
    }
    static Exchange send(String path,Map<String,Object> body,int status)throws Exception{Exchange ex=new Exchange(body==null?"GET":"POST",path,body,token);Api.handle(ex);eq(ex.status,status,"HTTP "+path+" "+(ex.status==status?"":ex.response.toString(StandardCharsets.UTF_8)));return ex;}
    static Map<String,Object> api(String path,Map<String,Object> body)throws Exception{Exchange ex=send(path,body,200);Map<String,Object> envelope=obj(Json.parse(ex.response.toString(StandardCharsets.UTF_8)));eq(number(envelope.get("code")),0L,"success envelope");return obj(envelope.get("data"));}
    static void publishCatalog(long expected,String version,boolean active)throws Exception{
        Map<String,Object> catalog=map("schema_version","m04_catalog_v1","catalog_version",version,"courses",List.of(map("course_code","00030","course_name",active?"PRIVATE-COURSE-A":"PRIVATE-RENAMED-COURSE","active",active),map("course_code","00031","course_name","PRIVATE-COURSE-B","active",true)),"teachers",List.of(map("teacher_code","00020","teacher_level","L1","city","SYNTHETIC-CITY")),"certifications",List.of());
        Map<String,Object> preview=api("/api/course-catalog/scopes/"+scope+"/preview",map("expected_version",expected,"catalog",catalog,"bindings",List.of(map("teacher_code","00020","teacher_id",20L)),"change_comment","SYNTHETIC-CODING-CATALOG"));
        check(Boolean.TRUE.equals(preview.get("ready")),"real M04 preview ready");api("/api/course-catalog/scopes/"+scope+"/confirm",map("expected_version",expected,"batch_id",preview.get("batch_id"),"confirm",true));
    }
    static Map<String,Object> total(String amount,String date,String hours){Map<String,Object> t=M06SettlementBridgeTest.total(amount,date,false);for(String k:HOURS)obj(t.get("hours")).put(k,hours);return t;}
    static String ledgerFingerprint()throws Exception{Map<String,Object>all=new TreeMap<>();for(String table:List.of("m05_financial_chains","m05_financial_entries","m05_payment_events")){List<String> r=new ArrayList<>();for(Map<String,Object> row:Db.query("SELECT * FROM "+table))r.add(Json.write(new TreeMap<>(row)));Collections.sort(r);all.put(table,r);}return Json.write(all);}
    static String reportUrl(boolean payment){return REPORT+"?start="+(payment?DAY:"2026-09-01")+"&end="+(payment?DAY:"2026-09-30")+"&date_basis="+(payment?"PAYMENT":"TEACHING");}
    static String exportUrl(boolean payment,Map<String,Object> report){return reportUrl(payment).replace(REPORT,REPORT+"/export")+"&snapshot_version="+report.get("snapshot_version");}
    static Map<String,Object> link(long expected,long catalogVersion,String course)throws Exception{
        Map<String,Object> body=map("chain_code",CHAIN,"expected_version",expected,"expected_entry_code","E4","scope_id",scope,"expected_catalog_version",catalogVersion,"course_code",course,"evidence_note","PRIVATE-CODING-NOTE","evidence_reference","PRIVATE-CODING-REFERENCE","request_id",requestId());
        if(expected>0){body.put("reason_note","PRIVATE-CORRECTION-REASON");body.put("reason_reference","PRIVATE-CORRECTION-REFERENCE");}
        Map<String,Object> before=DeliverySettlementIntegration.financialSource(worker,10,false);
        Map<String,Object> preview=api(CODING+"/preview",body);check(preview.get("preview_code")!=null,"real coding preview created");
        eq(DeliverySettlementIntegration.financialSource(worker,10,false).get("source_version"),before.get("source_version"),"preview alone does not apply codes or change source version");
        return api(CODING+"/confirm",map("chain_code",CHAIN,"expected_version",expected,"expected_entry_code","E4","preview_code",preview.get("preview_code"),"request_id",requestId()));
    }
    static void codingMatches(Map<String,Object> source,String course)throws Exception{
        eq(rows(source.get("coding_versions")).size(),1,"one audited chain metadata row");Map<String,Object> c=rows(source.get("coding_versions")).get(0);eq(number(c.get("catalog_scope_id")),scope,"trusted catalog scope");eq(c.get("course_code"),course,"audited selected course");eq(number(c.get("teacher_id")),20L,"binding uses frozen real teacher id");
        for(Map<String,Object> e:rows(source.get("accrual_entries"))){eq(e.get("teacher_code"),"00020","all original/reversal/rebook/correction teacher codes");eq(e.get("course_code"),course,"all original/reversal/rebook/correction course codes");eq(e.get("course_id"),null,"course numeric ID remains absent");}
        Map<String,Object> raw=DeliverySettlementLedger.project(10,"001");Map<String,Map<String,Object>> originals=new HashMap<>();for(Map<String,Object> e:rows(raw.get("accrual_entries")))originals.put(e.get("entry_code").toString(),e);
        for(Map<String,Object> e:rows(source.get("accrual_entries"))){Map<String,Object> projected=copy(e),original=copy(originals.get(e.get("entry_code")));for(String field:List.of("teacher_code","course_code")){projected.remove(field);original.remove(field);}eq(projected,original,"financial fields exactly unchanged by audited projection");}
        eq(source.get("payment_entries"),raw.get("payment_entries"),"payment entries remain unchanged");
    }
    static byte[] download(boolean payment,Map<String,Object> report)throws Exception{return download(payment,report,"00030");}
    static byte[] download(boolean payment,Map<String,Object> report,String course)throws Exception{
        Exchange ex=send(exportUrl(payment,report),null,200);check(ex.response.size()>1000,"nonempty XLSX bytes");eq(ex.responseHeaders.getFirst("Content-Type"),ManagementSettlementWorkbook.CONTENT_TYPE,"XLSX type");eq(ex.responseHeaders.getFirst("Cache-Control"),"no-store","download no-store");check(ex.responseHeaders.getFirst("Content-Disposition").contains("settlement-"),"safe filename");
        int sheets=0;boolean code=false,amount=false;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(ex.response.toByteArray()),StandardCharsets.UTF_8)){ZipEntry entry;while((entry=zip.getNextEntry())!=null){String xml=new String(zip.readAllBytes(),StandardCharsets.UTF_8);check(!xml.contains("PRIVATE-")&&!xml.contains("catalog_scope_id"),"no names, notes or internal coding IDs in file");check(!xml.contains("<f>")&&!xml.contains("TargetMode=\"External\""),"no workbook formulas/external references");if(entry.getName().startsWith("xl/worksheets/")){sheets++;code|=xml.contains("00020")&&xml.contains(course);amount|=xml.contains("80.00");}}}
        eq(sheets,5,"five sheets");check(code&&amount,"nonempty coded amount actually exported");return ex.response.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Fresh empty data dir and output dir required");Path dir=Path.of(args[0]);if(!Files.isDirectory(dir)||Files.isSymbolicLink(dir))throw new IllegalArgumentException();try(var files=Files.list(dir)){if(files.findAny().isPresent())throw new IllegalArgumentException("Empty fixture required");}
        M06SettlementBridgeTest.fixture(dir);Db.exec("ALTER TABLE teachers ADD teacher_level VARCHAR(120)");Db.exec("ALTER TABLE teachers ADD base_city VARCHAR(120)");Db.exec("UPDATE teachers SET teacher_level='L1',base_city='SYNTHETIC-CITY' WHERE id=20");configure("M06-A1","CODING-ACCESS-1",true,true);CourseCatalogIntegration.init();scope=number(CourseCatalogIntegration.registerScope(worker,"001").get("scope_id"));publishCatalog(0,"CODING-CATALOG-1",true);
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.open(10,"001",CHAIN,total("100.00","2026-08-01","1.5"),"E1","CONFIRMED","PRIVATE-ORIGINAL"));
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.payment(CHAIN,"E1",DAY,"100.00","PRIVATE-PAY-1","0002",2,false));
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.replace(CHAIN,"E1",total("120.00","2026-08-01","2"),"E2","PRIVATE-ADJUST"));
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.replace(CHAIN,"E2",total("150.00","2026-09-01","3"),"E3","PRIVATE-CROSS"));
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.payment(CHAIN,"E3",DAY,"50.00","PRIVATE-PAY-2","0002",2,false));
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.replace(CHAIN,"E3",total("80.00","2026-09-01","2"),"E4","PRIVATE-AFTER-PAID"));
        M06SettlementBridgeTest.write(()->DeliverySettlementLedger.payment(CHAIN,"E4",DAY,"-70.00","PRIVATE-REFUND","0002",2,false));
        String ledger=ledgerFingerprint();Map<String,Object> before=api(reportUrl(false),null);eq(obj(before.get("totals")).get("amount"),"80.00","real nonempty amount before coding");check(!Boolean.TRUE.equals(before.get("export_available")),"missing code gate reproduced");send(exportUrl(false,before),null,409);
        if(Boolean.getBoolean("m06.coding.baseline_only")){Path output=Path.of(args[1]);Files.createDirectories(output);Files.writeString(output.resolve("baseline-evidence.json"),Json.write(map("checks",checks,"source_kind","FROZEN_R18_REAL_AUTH_M01_M04_M05_API_HANDLE_SYNTHETIC_DB","amount",obj(before.get("totals")).get("amount"),"code_gaps",before.get("code_gaps"),"export_http_status",409,"ledger_populated",true)));System.out.println("Frozen r18 nonempty coding gap reproduced; checks: "+checks);return;}
        Map<String,Object> options=api(CODING+"/options?chain_code="+CHAIN+"&scope_id="+scope,null);eq(obj(options.get("catalog")).get("teacher_code"),"00020","real M04 binding selected");
        Map<String,Object> confirmed=link(0,1,"00030");eq(number(confirmed.get("version")),1L,"coding confirmation version one");eq(ledgerFingerprint(),ledger,"coding confirmation leaves all financial ledger tables identical");
        Map<String,Object> source=DeliverySettlementIntegration.financialSource(worker,10,true);codingMatches(source,"00030");Map<String,Object> report=api(reportUrl(false),null),cash=api(reportUrl(true),null);check(Boolean.TRUE.equals(report.get("export_available")),"audited nonempty source export enabled");check(report.get("coverage_note").toString().contains("另行明确确认的审计关联"),"view accurately describes independent audited coding provenance");check(rows(report.get("code_gaps")).isEmpty(),"no code gaps after real confirmation");eq(obj(report.get("totals")).get("amount"),"80.00","accrual remains 80");eq(obj(cash.get("totals")).get("amount"),"80.00","payments/refund net remains 80");
        for(Map<String,Object> d:rows(cash.get("details"))){eq(d.get("course_code"),"00030","each payment and refund inherits coding");eq(obj(d.get("hours")).get("ACTUAL"),null,"cash has no duplicate teaching hours");}
        String readOnly=M06SettlementBridgeTest.fingerprint();byte[] teachingBytes=download(false,report),cashBytes=download(true,cash);eq(M06SettlementBridgeTest.fingerprint(),readOnly,"all database tables unchanged by read and both downloads");
        publishCatalog(1,"CODING-CATALOG-2",false);eq(DeliverySettlementIntegration.financialSource(worker,10,false).get("source_version"),source.get("source_version"),"later directory edit does not silently relink historical financial codes");
        Map<String,Object> correction=link(1,2,"00031");eq(number(correction.get("version")),2L,"explicit coding correction appends version");Map<String,Object> correctedSource=DeliverySettlementIntegration.financialSource(worker,10,false);codingMatches(correctedSource,"00031");check(!source.get("source_version").equals(correctedSource.get("source_version")),"coding-only revision invalidates source version");send(exportUrl(false,report),null,409);send(exportUrl(true,cash),null,409);eq(ledgerFingerprint(),ledger,"coding correction preserves original financial ledger");
        configure("CODING-ACCESS-1","CODING-ACCESS-2",false,true);Map<String,Object> afterRevocation=api(reportUrl(false),null);check(Boolean.TRUE.equals(afterRevocation.get("export_available")),"historical coding reports do not depend on current catalog-read grant");eq(rows(afterRevocation.get("details")).get(0).get("course_code"),"00031","approved history survives catalog permission removal");
        download(false,afterRevocation,"00031");download(true,api(reportUrl(true),null),"00031");eq(ledgerFingerprint(),ledger,"historical downloads without catalog permission leave financial ledger unchanged");
        configure("CODING-ACCESS-2","CODING-ACCESS-3",false,false);Map<String,Object> noExport=api(reportUrl(false),null);send(exportUrl(false,noExport),null,403);
        Path output=Path.of(args[1]);Files.createDirectories(output);Files.write(output.resolve("actual-coded-teaching-synthetic.xlsx"),teachingBytes);Files.write(output.resolve("actual-coded-payment-synthetic.xlsx"),cashBytes);Files.writeString(output.resolve("flow-evidence.json"),Json.write(map("checks",checks,"source_kind","REAL_AUTH_M01_M04_M05_CODING_API_HANDLE_SYNTHETIC_DB","amount","80.00","teacher_code","00020","course_code","00030","course_id",null,"entry_count",rows(report.get("details")).size(),"payment_count",rows(cash.get("details")).size(),"old_source_version",source.get("source_version"),"new_source_version",correctedSource.get("source_version"),"ledger_unchanged",true)));
        System.out.println("M06 actual coding-to-nonempty-XLSX checks passed: "+checks);
    }
}
