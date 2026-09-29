package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Comparison-specific checks. Existing fixture helpers create only a fresh isolated synthetic database. */
public final class M08ComparisonTest {
    private static final String PASSWORD="M08-SYNTHETIC-ONLY";
    private static final String PATH="/api/training-summaries/comparison";
    private static Auth.Session full,hidden,other,admin;private static String token,hiddenToken;
    private static int checks,scenarios;private static long request;
    private static Map<String,Object> first,second,initialRequest;
    private static final Set<String> ROOT=Set.of("project_id","from_revision","to_revision","latest_version","before","after","read_only","synthetic","draft_only");
    @FunctionalInterface interface Run{void run()throws Exception;}
    @SuppressWarnings("unchecked")private static Map<String,Object> m(Object value){return (Map<String,Object>)value;}
    private static long n(Object value){return ((Number)value).longValue();}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void eq(Object a,Object b,String message){check(Objects.equals(a,b),message);}
    private static String canonical(Object value){return M08DeliverySourceTest.canonical(value);}
    private static void rejects(int expected,Run run)throws Exception{checks++;try{run.run();throw new AssertionError("expected "+expected);}catch(Api.ApiException e){if(e.code!=expected)throw new AssertionError("unexpected "+e.code+" instead of "+expected);}}
    private static void run(String name,Run work)throws Exception{work.run();scenarios++;System.out.println("PASS "+name);}
    private static void sessions()throws Exception{token=Auth.login("u2",PASSWORD);hiddenToken=Auth.login("u3",PASSWORD);full=Auth.get(token);hidden=Auth.get(hiddenToken);other=Auth.get(Auth.login("u4",PASSWORD));admin=Auth.get(Auth.login("u1",PASSWORD));}
    private static Map<String,Object> content(String label,List<?> sections,List<String> captions){return Map.of("achievements",label,"issues","SYNTHETIC ISSUE","nextSteps","SYNTHETIC NEXT","publicity",Map.of("title",label,"introduction","SYNTHETIC INTRO","sections",sections,"photoCaptions",captions));}
    private static Map<String,Object> save(Map<String,Object> content)throws Exception{long version=n(TrainingSummariesIntegration.read(full,101).get("version"));return TrainingSummariesIntegration.mutate("save",full,Map.of("project_id",101,"expected_version",version,"request_id","comparison-save-"+(++request),"content",content));}
    private static void refresh()throws Exception{TrainingSummariesIntegration.mutate("refresh",full,Map.of("project_id",101,"expected_version",n(TrainingSummariesIntegration.read(full,101).get("version")),"request_id","comparison-refresh-"+(++request)));}
    private static Map<String,Object> compare(Auth.Session who,long from,long to)throws Exception{return TrainingSummariesIntegration.comparison(who,101,from,to);}
    private static Map<String,Object> view(Auth.Session who,long revision)throws Exception{return TrainingSummariesIntegration.revision(who,101,revision);}
    private static Map<String,Object> delivery(Map<String,Object> version){return m(m(version.get("sources")).get("delivery"));}
    private static void hiddenVersion(Map<String,Object> version){
        Map<String,Object>sources=m(version.get("sources")),d=delivery(version);eq("UNAVAILABLE",d.get("status"),"hidden status");eq("DELIVERY_READ_REQUIRED",d.get("reason"),"hidden reason");eq(null,d.get("value"),"hidden value");eq(Set.of("status","reason","value"),d.keySet(),"no per-source hash/count");eq(null,sources.get("source_version"),"no outer source hash");
    }
    private static void shape(Map<String,Object> result,long from,long to){
        eq(ROOT,result.keySet(),"strict root projection");eq(101L,n(result.get("project_id")),"project");eq(from,n(result.get("from_revision")),"from");eq(to,n(result.get("to_revision")),"to");eq(6L,n(result.get("latest_version")),"latest is label only");
        eq(true,result.get("read_only"),"read only");eq(false,result.get("synthetic"),"real boundary");eq(true,result.get("draft_only"),"draft only");
        check(!result.containsKey("source_changed")&&!result.containsKey("diff")&&!result.containsKey("hash"),"no hidden-change sidechannel");
    }
    private static Map<String,String> tables()throws Exception{
        Map<String,String> snapshot=new TreeMap<>();
        for(Map<String,Object>row:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")){
            String name=row.get("table_name").toString();if(!name.matches("[A-Z_0-9]+"))throw new AssertionError("unexpected fixture table name");
            List<String> rows=new ArrayList<>();for(Map<String,Object>value:Db.query("SELECT * FROM \""+name+"\""))rows.add(canonical(value));Collections.sort(rows);snapshot.put(name,canonical(rows));
        }
        check(snapshot.size()>=10,"snapshot all fixture tables");return snapshot;
    }
    private static void readOnly(Run work)throws Exception{Map<String,String> before=tables();work.run();eq(before,tables(),"no writes in any table");}
    private static Map<String,Object> http(String query,Auth.Session session,String tok)throws Exception{
        var exchange=new M08DeliverySourceTest.Exchange("GET",PATH+query,tok,null);check(TrainingSummariesIntegration.handle(exchange,session),"comparison path handled");return M08DeliverySourceTest.data(exchange);
    }
    private static void fixture(Path path)throws Exception{
        M08DeliverySourceTest.fixture(path);sessions();
        M08DeliverySourceTest.dispatch(1,LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString(),"已确认");M08DeliverySourceTest.fact(1,"3","2","45",null);M08DeliverySourceTest.verify(1);Db.exec("UPDATE dispatches SET status='已完成' WHERE id=1");
        first=content("SYNTHETIC FIRST",List.of(Map.of("heading","A","body","A<&>\n正文"),Map.of("heading","B","body","B正文")),List.of("第一张图注","第二张图注"));
        second=content("SYNTHETIC SECOND",List.of(Map.of("heading","B","body","变更正文"),Map.of("heading","C","body","新增章节")),List.of("调整的图注"));
        initialRequest=Map.of("project_id",101,"expected_version",0,"request_id","comparison-initial","content",first);TrainingSummariesIntegration.mutate("save",full,initialRequest);save(second);
        M08DeliverySourceTest.fact(1,"4","3","90",null);M08DeliverySourceTest.verify(1);refresh(); // 3: only teaching source changed
        Db.exec("UPDATE projects SET participant_count=25 WHERE id=101");refresh(); // 4: project source changed
        save(second); // 5: same content/source but new saved revision
        save(Map.of("achievements","LEGACY ONLY","issues","","nextSteps","")); // 6: optional publicity absent
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("fresh temp directory required");Path path=Path.of(args[0]).toAbsolutePath().normalize();if(!Files.isDirectory(path)||Files.isSymbolicLink(path))throw new IllegalArgumentException();try(var files=Files.list(path)){if(files.findAny().isPresent())throw new IllegalArgumentException("directory must be empty");}
        try{fixture(path);
            run("empty origin and exact immutable revision views",()->readOnly(()->{
                Map<String,Object>result=compare(full,0,1);shape(result,0,1);eq(null,result.get("before"),"empty origin");eq(canonical(view(full,1)),canonical(result.get("after")),"after is existing revisionView");
                result=compare(full,1,2);shape(result,1,2);eq(canonical(view(full,1)),canonical(result.get("before")),"same before");eq(canonical(view(full,2)),canonical(result.get("after")),"same after");
                eq(canonical(first),canonical(m(result.get("before")).get("content")),"full first text chapters captions");eq(canonical(second),canonical(m(result.get("after")).get("content")),"full second text chapters captions");
                eq(canonical(m(result.get("before")).get("sources")),canonical(m(result.get("after")).get("sources")),"ordinary save keeps frozen source");
            }));
            run("source refresh and same-content saves are not fake diffs",()->readOnly(()->{
                Map<String,Object>result=compare(full,2,3);shape(result,2,3);eq(canonical(m(result.get("before")).get("content")),canonical(m(result.get("after")).get("content")),"refresh does not alter text");
                check(!delivery(m(result.get("before"))).equals(delivery(m(result.get("after")))),"captured teaching changed");eq("1.00",m(delivery(m(result.get("before"))).get("value")).get("actual"),"old exact actual");eq("2.00",m(delivery(m(result.get("after"))).get("value")).get("actual"),"new exact actual");
                result=compare(full,3,4);check(!m(m(result.get("before")).get("sources")).get("project").equals(m(m(result.get("after")).get("sources")).get("project")),"project refresh snapshots differ");
                result=compare(full,4,5);eq(canonical(m(result.get("before")).get("content")),canonical(m(result.get("after")).get("content")),"same-content version");eq(canonical(m(result.get("before")).get("sources")),canonical(m(result.get("after")).get("sources")),"same-source version");
                result=compare(full,1,6);shape(result,1,6);check(!m(m(result.get("after")).get("content")).containsKey("publicity"),"legacy format remains absent");
            }));
            run("single current visibility projects both hidden sides",()->readOnly(()->{
                Map<String,Object>result=compare(hidden,2,3);shape(result,2,3);hiddenVersion(m(result.get("before")));hiddenVersion(m(result.get("after")));
                eq(canonical(m(result.get("before")).get("sources")),canonical(m(result.get("after")).get("sources")),"only-hidden changes fully masked");
                result=compare(hidden,0,1);eq(null,result.get("before"),"hidden empty baseline");hiddenVersion(m(result.get("after")));
                eq(canonical(view(hidden,1)),canonical(result.get("after")),"same historical masking");
            }));
            run("range session scope and nested transaction rejection",()->readOnly(()->{
                for(long[]range:new long[][]{{-1,1},{0,0},{1,1},{2,1},{0,9007199254740992L},{Long.MAX_VALUE,Long.MAX_VALUE}})rejects(400,()->compare(full,range[0],range[1]));
                rejects(404,()->compare(full,0,7));rejects(404,()->compare(full,7,8));rejects(404,()->TrainingSummariesIntegration.comparison(full,102,0,1));rejects(400,()->TrainingSummariesIntegration.comparison(full,9007199254740992L,0,1));
                rejects(403,()->compare(other,1,2));rejects(403,()->compare(admin,1,2));Auth.Session fake=new Auth.Session();fake.uid=full.uid;rejects(401,()->compare(fake,1,2));
                Db.get().setAutoCommit(false);try{rejects(409,()->compare(full,1,2));check(!Db.get().getAutoCommit(),"outer transaction not committed");}finally{Db.get().rollback();Db.get().setAutoCommit(true);}
            }));
            run("strict HTTP parameters and authorization",M08ComparisonTest::queries);
            run("archived project is read only comparable",()->{
                Db.exec("UPDATE projects SET status='已归档' WHERE id=101");try{readOnly(()->{shape(compare(full,1,2),1,2);hiddenVersion(m(compare(hidden,1,2).get("before")));rejects(409,()->save(first));});}finally{Db.exec("UPDATE projects SET status='进行中' WHERE id=101");}
            });
            run("current delivery revoke hides both already frozen revisions",()->{
                Auth.Session stale=full;M08DeliverySourceTest.publish(false);sessions();readOnly(()->{rejects(401,()->compare(stale,1,2));Map<String,Object>result=compare(full,2,3);hiddenVersion(m(result.get("before")));hiddenVersion(m(result.get("after")));eq(canonical(m(result.get("before")).get("sources")),canonical(m(result.get("after")).get("sources")),"revoke does not reveal hidden change");});M08DeliverySourceTest.publish(true);sessions();
            });
            run("summary revoke disables comparison and restoration needs fresh login",()->{
                Configuration c=OrganizationAccessStore.configuration();var grants=c.grants().stream().filter(g->!g.resource().equals("summary.read")).toList();var revoked=new Configuration(c.version()+"R",c.codeRules(),c.roleCodes(),c.organizations(),c.people(),c.relations(),c.accountBindings(),grants,c.combinedApprovals());Auth.Session old=full;OrganizationAccessStore.publish(admin,c.version(),revoked);sessions();readOnly(()->{rejects(401,()->compare(old,1,2));rejects(403,()->compare(full,1,2));rejects(403,()->compare(hidden,0,1));});M08DeliverySourceTest.publish(true);sessions();
                Db.exec("UPDATE users SET status=0 WHERE id=2");try{readOnly(()->rejects(401,()->compare(full,1,2)));}finally{Db.exec("UPDATE users SET status=1 WHERE id=2");}rejects(401,()->compare(full,1,2));sessions();
            });
            run("damage anywhere in full history cannot be hidden",M08ComparisonTest::damage);
            run("existing read history revision save refresh semantics preserved",()->{
                Map<String,Object>current=TrainingSummariesIntegration.read(full,101),history=TrainingSummariesIntegration.history(full,101,0,20),revision=view(full,1);String stored=Db.one("SELECT sources_json FROM m08_summary_revisions WHERE project_id=101 AND revision=1").get("sources_json").toString();
                readOnly(()->{compare(full,1,2);compare(hidden,0,6);});eq(canonical(current),canonical(TrainingSummariesIntegration.read(full,101)),"current read unchanged");eq(canonical(history),canonical(TrainingSummariesIntegration.history(full,101,0,20)),"history unchanged");eq(canonical(revision),canonical(view(full,1)),"old revision unchanged");
                Map<String,Object>saved=save(first);eq(7L,n(saved.get("version")),"normal save CAS still applies");eq(7L,n(compare(full,1,2).get("latest_version")),"only latest label changes");eq(canonical(revision),canonical(compare(full,0,1).get("after")),"old comparison remains pinned");refresh();eq(8L,n(TrainingSummariesIntegration.read(full,101).get("version")),"refresh still appends");
                Map<String,Object>replay=TrainingSummariesIntegration.mutate("save",full,initialRequest);eq(true,replay.get("replayed"),"idempotent replay unchanged");eq(1L,n(replay.get("revision")),"original replay revision");eq(stored,Db.one("SELECT sources_json FROM m08_summary_revisions WHERE project_id=101 AND revision=1").get("sources_json"),"original bytes intact");
            });
            System.out.println("M08Comparison: "+scenarios+" scenarios, "+checks+" checks passed; isolated H2, all-table read-only assertions");
        }finally{Db.get().close();}
    }
    private static void queries()throws Exception{readOnly(()->{
        Map<String,Object>result=http("?project_id=101&from_revision=0&to_revision=1",full,token);shape(result,0,1);eq(null,result.get("before"),"HTTP null baseline");
        result=http("?project_id=%31%30%31&from_revision=0001&to_revision=2",hidden,hiddenToken);shape(result,1,2);hiddenVersion(m(result.get("before")));hiddenVersion(m(result.get("after")));
        List<String>bad=List.of("","?project_id=101&from_revision=0","?from_revision=0&to_revision=1","?project_id=101&from_revision=0&to_revision=1&expected_version=6","?project_id=101&from_revision=0&to_revision=1&to_revision=2","?project_id=101&from_revision=0&to_revision=1&%74o_revision=2","?project_id=101&from_revision=0&to_revision=1&","?project_id=101&from_revision=0&to_revision=1&&","?project_id=101&from_revision=0&to_revision=1;extra=1");
        for(String query:bad)rejects(400,()->http(query,full,token));
        for(String field:List.of("project_id","from_revision","to_revision"))for(String value:List.of("","-1","1.5","1e0","NaN","9007199254740992","99999999999999999","%20","%FF","+1","%2B1","%250031")){
            Map<String,String>q=new LinkedHashMap<>(Map.of("project_id","101","from_revision","0","to_revision","1"));q.put(field,value);String query="?"+q.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).reduce((a,b)->a+"&"+b).orElseThrow();rejects(400,()->http(query,full,token));
        }
        rejects(400,()->http("?project_id=101&from_revision=2&to_revision=1",full,token));rejects(404,()->http("?project_id=101&from_revision=0&to_revision=9007199254740991",full,token));
        rejects(401,()->http("?project_id=101&from_revision=0&to_revision=1",full,hiddenToken));String otherToken=Auth.login("u4",PASSWORD);Auth.Session otherSession=Auth.get(otherToken);rejects(403,()->http("?project_id=101&from_revision=0&to_revision=1",otherSession,otherToken));
        for(String method:List.of("POST","DELETE","PUT","HEAD"))rejects(405,()->TrainingSummariesIntegration.handle(new M08DeliverySourceTest.Exchange(method,PATH+"?project_id=101&from_revision=0&to_revision=1",token,null),full));
    });}
    private static void damage()throws Exception{
        for(String column:List.of("content_json","sources_json","revision_hash","previous_hash")){
            String original=Db.one("SELECT "+column+" AS stored_value FROM m08_summary_revisions WHERE project_id=101 AND revision=6").get("stored_value").toString();Db.exec("UPDATE m08_summary_revisions SET "+column+"=? WHERE project_id=101 AND revision=6",column.endsWith("hash")?"f".repeat(64):"{}");
            try{readOnly(()->{rejects(409,()->compare(full,1,2));rejects(409,()->compare(hidden,0,1));});}finally{Db.exec("UPDATE m08_summary_revisions SET "+column+"=? WHERE project_id=101 AND revision=6",original);}
        }
        String original=Db.one("SELECT head_hash FROM m08_summary_heads WHERE project_id=101").get("head_hash").toString();Db.exec("UPDATE m08_summary_heads SET head_hash=? WHERE project_id=101","f".repeat(64));try{readOnly(()->rejects(409,()->compare(full,1,2)));}finally{Db.exec("UPDATE m08_summary_heads SET head_hash=? WHERE project_id=101",original);}
        Db.exec("UPDATE projects SET demand_id=102 WHERE id=101");try{readOnly(()->rejects(403,()->compare(full,1,2)));}finally{Db.exec("UPDATE projects SET demand_id=101 WHERE id=101");}
    }
}
