package com.training;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual Api HTTP deletion; only generated Cases and a caller-owned empty H2 database. */
public final class M05ReferencesTest {
    private static Auth.Session worker;
    private static int checks,requests;
    private static final String DAY=LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(5).toString();
    private static final LinkedHashMap<Long,String> REFERENCES=new LinkedHashMap<>();
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object v){return (Map<String,Object>)v;}
    private static long number(Object v){return new BigDecimal(v.toString()).longValueExact();}
    private static Object invoke(Class<?> owner,String name,Class<?>[] types,Object... args)throws Exception {
        Method method=owner.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(null,args);}catch(InvocationTargetException invalid){if(invalid.getCause() instanceof Exception e)throw e;throw invalid;}
    }
    private static Map<String,Object> command(String code,long version){
        Map<String,Object>b=new LinkedHashMap<>();b.put("project_id",10);b.put("case_code",code);b.put("expected_version",version);b.put("request_id","REF-TEST-REQUEST-"+(++requests));return b;
    }
    private static Map<String,Object> command(String code)throws Exception{return command(code,number(DeliverySettlementCases.read(worker,code).get("version")));}
    private static void proof(Map<String,Object>b){b.put("evidence_note","隔离测试：本次记录仅用于核验教师引用保留，已核对合成凭证。");b.put("evidence_reference","隔离测试资料 / 教师引用矩阵 / 第1条");}
    private static Map<String,Object> dev(String origin,long teacher,boolean joint)throws Exception {
        Map<String,Object>p=map(invoke(M05CasesTest.class,"dev",new Class<?>[]{String.class,boolean.class},origin,joint));p.put("lead_teacher_id",teacher);
        if(joint)p.put("allocations",List.of(Map.of("teacher_id",teacher,"amount","180.00"),Map.of("teacher_id",38L,"amount","120.00")));return p;
    }
    private static void save(String code,String kind,Map<String,Object>payload)throws Exception {
        Map<String,Object>b=Db.one("SELECT case_code FROM m05_cases WHERE case_code=?",code)==null?command(code,0):command(code);
        b.put("kind",kind);b.put("payload",payload);DeliverySettlementCases.mutate("save",worker,b);
    }
    private static void submit(String code)throws Exception {Map<String,Object>b=command(code);proof(b);DeliverySettlementCases.mutate("submit",worker,b);}
    private static void review(String code,boolean approved)throws Exception {
        Map<String,Object>b=command(code);b.put("decision",approved?"APPROVE":"RETURN");proof(b);
        if(!approved)b.put("reason_note","隔离测试：资料待补，先退回并完整保留原引用记录。");DeliverySettlementCases.mutate("review",worker,b);
    }
    private static void confirm(String code)throws Exception{submit(code);review(code,true);DeliverySettlementCases.mutate("confirm",worker,command(code));}
    private static void fixtures(Path data)throws Exception {
        invoke(M05FormalWorkflowTest.class,"fixtures",new Class<?>[]{Path.class},data);DeliverySettlementIntegration.init();
        invoke(M05FormalWorkflowTest.class,"publish",new Class<?>[]{String.class,String.class,Set.class},null,"REFERENCES-ACCESS-1",Set.of());
        Field session=M05FormalWorkflowTest.class.getDeclaredField("worker");session.setAccessible(true);worker=(Auth.Session)session.get(null);
        for(String table:List.of("teacher_roster_import_people","teacher_evals","teacher_resumes","m04_teacher_bindings"))
            Db.exec("CREATE TABLE "+table+"(teacher_id BIGINT)");
        for(long id=30;id<=38;id++)Db.exec("INSERT INTO teachers VALUES(?,?,'在库',99999)",id,"SYNTHETIC REFERENCE TEACHER "+id);
        save("REF-DEVELOPMENT","DEVELOPMENT",dev("REF-DELIVERABLE-30",30,false));confirm("REF-DEVELOPMENT");REFERENCES.put(30L,"confirmed development without dispatch");
        Map<String,Object>migration=map(invoke(M05CasesTest.class,"migration",new Class<?>[]{String.class},"隔离旧台账 / 教师31 / 第1笔"));migration.put("teacher_id",31L);
        save("REF-MIGRATION","MIGRATION",migration);confirm("REF-MIGRATION");REFERENCES.put(31L,"legacy opening and historical actual payment without dispatch");
        save("REF-RETURNED","DEVELOPMENT",dev("REF-DELIVERABLE-32",32,false));submit("REF-RETURNED");review("REF-RETURNED",false);REFERENCES.put(32L,"returned case");
        save("REF-WITHDRAWN","DEVELOPMENT",dev("REF-DELIVERABLE-33",33,false));Map<String,Object>withdraw=command("REF-WITHDRAWN");withdraw.put("reason_note","隔离测试：误建事项作废但保留教师与历史记录。");withdraw.put("reason_reference","隔离测试作废核对单第1号");DeliverySettlementCases.mutate("withdraw",worker,withdraw);REFERENCES.put(33L,"withdrawn case");
        save("REF-HISTORY","DEVELOPMENT",dev("REF-DELIVERABLE-34",34,false));save("REF-HISTORY","DEVELOPMENT",dev("REF-DELIVERABLE-34",35,false));
        REFERENCES.put(34L,"historical revision only after teacher replacement");REFERENCES.put(35L,"current revised draft");
        save("REF-ALLOCATION","DEVELOPMENT",dev("REF-DELIVERABLE-38",20,true));REFERENCES.put(38L,"joint member allocation without dispatch");
        Map<String,Object>total=map(invoke(M05FormalWorkflowTest.class,"ledgerTotal",new Class<?>[]{String.class,String.class},DAY,"10.00"));
        total.put("teacher_id",37L);total.put("system_teacher_code","TEACHER-37");total.put("source_record_id",null);total.put("source_code","REF-FINANCIAL-ONLY");
        synchronized(Api.MUTATION_LOCK){Db.transaction(()->{
            if(!OrganizationAccessStore.authorize(worker,"settlement.confirm",OrganizationAccess.Action.HANDLE,"001").allowed())throw new AssertionError("Fixture lacks scoped confirmation grant");
            DeliverySettlementLedger.open(10,"001","REF-FINANCIAL-ONLY",total,"REF-FINANCIAL-ENTRY","CONFIRMED","REF-TRUSTED-SYNTHETIC-HOST");return null;
        });}
        REFERENCES.put(37L,"immutable financial chain without a case or dispatch");
        for(long id:REFERENCES.keySet())check(number(Db.one("SELECT (SELECT COUNT(*) FROM dispatches WHERE teacher_id=?)+(SELECT COUNT(*) FROM fees WHERE teacher_id=?)+(SELECT COUNT(*) FROM teacher_evals WHERE teacher_id=?)+(SELECT COUNT(*) FROM teacher_resumes WHERE teacher_id=?)+(SELECT COUNT(*) FROM m04_teacher_bindings WHERE teacher_id=?) AS n",id,id,id,id,id).get("n"))==0,"fixture has no legacy teacher reference: "+id);
        check(Db.get().getMetaData().getURL().contains(data.resolve("training").toString()),"HTTP test uses explicitly supplied isolated synthetic H2");
    }
    private static String fingerprint(boolean includeTeachers)throws Exception {
        Map<String,Object>snapshot=new TreeMap<>();
        for(Map<String,Object>table:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME")){
            String name=table.get("table_name").toString();if(!includeTeachers&&name.equalsIgnoreCase("teachers"))continue;
            snapshot.put(name,Db.query("SELECT * FROM \""+name.replace("\"","\"\"")+"\"").stream().map(Json::write).sorted().toList());
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(snapshot).getBytes(StandardCharsets.UTF_8)));
    }
    private record Response(int status,String body){}
    private static Response delete(int port,String token,long teacher)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/api/teachers/delete").openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setRequestMethod("POST");c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("X-Token",token);c.setDoOutput(true);
        try{
            try(OutputStream out=c.getOutputStream()){out.write(Json.write(Map.of("id",teacher)).getBytes(StandardCharsets.UTF_8));}
            int status=c.getResponseCode();InputStream body=status>=400?c.getErrorStream():c.getInputStream();
            String value=body==null?"":new String(body.readAllBytes(),StandardCharsets.UTF_8);if(body!=null)body.close();return new Response(status,value);
        }finally{c.disconnect();}
    }
    private static void corruptedHistoryChecks(int port,String token)throws Exception {
        Map<String,Object>old=Db.one("SELECT * FROM m05_case_revisions WHERE case_code='REF-HISTORY' AND version=1");
        try{
            Db.exec("UPDATE m05_case_revisions SET payload='{broken' WHERE case_code='REF-HISTORY' AND version=1");
            rejectedUnusedDelete(port,token,"malformed historical case JSON");
        }finally{Db.exec("UPDATE m05_case_revisions SET payload=? WHERE case_code='REF-HISTORY' AND version=1",old.get("payload").toString());}
        try{
            Db.exec("DELETE FROM m05_case_revisions WHERE case_code='REF-HISTORY' AND version=1");
            rejectedUnusedDelete(port,token,"missing early case revision");
        }finally{Db.exec("INSERT INTO m05_case_revisions VALUES(?,?,?,?,?,?)",old.get("case_code"),old.get("version"),old.get("payload").toString(),old.get("actor_code"),old.get("account_id"),old.get("created_at"));}
    }
    private static void rejectedUnusedDelete(int port,String token,String label)throws Exception {
        String before=fingerprint(true);Response response=delete(port,token,36);
        check(response.status()==409,label+" fails closed even for otherwise unreferenced teacher: "+response.status()+" "+response.body());
        check(Db.one("SELECT id FROM teachers WHERE id=36")!=null,label+" preserves teacher row");
        check(before.equals(fingerprint(true)),label+" real HTTP rejection performs no database writes");
        System.out.println("BLOCKED malformed-history "+label);
    }
    public static void main(String[]args)throws Exception {
        if(args.length!=2||!Set.of("baseline","guarded").contains(args[1]))throw new IllegalArgumentException("Fresh empty H2 directory and baseline|guarded required");
        Path data=Path.of(args[0]).toAbsolutePath().normalize();if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("Existing empty real directory required");
        try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Test directory must be empty");}
        fixtures(data);String token=Auth.login("formal-admin","SYNTHETIC-M05-FORMAL-ONLY");check(Auth.get(token)!=null,"actual admin Auth session for legacy delete route");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService executor=Executors.newSingleThreadExecutor();server.setExecutor(executor);server.createContext("/api/",Api::handle);server.start();
        boolean guarded=args[1].equals("guarded");
        try{
            for(var reference:REFERENCES.entrySet()){
                String before=fingerprint(guarded);Response response=delete(server.getAddress().getPort(),token,reference.getKey());
                check(response.status()==(guarded?409:200),(guarded?"guard rejects ":"baseline reproduces deletion of ")+reference.getValue()+" HTTP="+response.status()+" "+response.body());
                check((Db.one("SELECT id FROM teachers WHERE id=?",reference.getKey())!=null)==guarded,"teacher row existence matches exact HTTP outcome: "+reference.getKey());
                check(before.equals(fingerprint(guarded)),guarded?"blocked real HTTP delete writes no database rows":"original deletion leaves all referencing business history unchanged and dangling");
                System.out.println((guarded?"BLOCKED":"REPRODUCED")+" teacher="+reference.getKey()+" "+reference.getValue());
            }
            if(guarded)corruptedHistoryChecks(server.getAddress().getPort(),token);
            Response free=delete(server.getAddress().getPort(),token,36);check(free.status()==200,"truly unreferenced temporary teacher remains deletable: "+free.body());check(Db.one("SELECT id FROM teachers WHERE id=36")==null,"unreferenced teacher is actually deleted");
        }finally{server.stop(0);executor.shutdownNow();}
        Db.exec("SHUTDOWN");System.out.println("M05References "+args[1]+": "+checks+" checks passed (actual Api HTTP; isolated synthetic H2)");
    }
}
