package com.training;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import java.io.*;
import static com.training.OrganizationAccess.*;

/** Real Auth/M01/M05/M08 on fresh synthetic H2; the M07 provider below is an explicit contract test double. */
public final class M08FormalTest {
    static final String PASSWORD="M08-SYNTHETIC-ONLY";
    static final Map<Integer,Auth.Session> sessions=new HashMap<>();static final Map<Integer,String> tokens=new HashMap<>();
    static int checks,groups,configCounter,feedbackRevision=1;static long serial;static Path output;
    @FunctionalInterface interface Run{void run()throws Exception;}
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void eq(Object a,Object b,String message){check(Objects.equals(a,b),message);}
    static void eq(Object a,Object b){eq(a,b,"expected "+a+", got "+b);}
    static void rejects(int status,Run run)throws Exception{checks++;try{run.run();throw new AssertionError("expected "+status);}catch(Api.ApiException e){if(e.code!=status)throw new AssertionError("expected "+status+" got "+e.code+": "+e.getMessage());}}
    static void group(String name,Run run)throws Exception{run.run();groups++;System.out.println("PASS "+name);}
    @SuppressWarnings("unchecked") static Map<String,Object> m(Object v){return (Map<String,Object>)v;}
    static long n(Object v){return ((Number)v).longValue();}
    static String c(Object v){return TrainingSummariesWorkflow.canonical(v);}
    static Auth.Session s(int id){return sessions.get(id);}
    static void login()throws Exception{for(int i=1;i<=9;i++){String token=Auth.login("u"+i,PASSWORD);tokens.put(i,token);sessions.put(i,Auth.get(token));}}
    static Map<String,Object> read(long project)throws Exception{return TrainingSummariesIntegration.read(s(2),project);}
    static Map<String,Object> flow(int user,long project)throws Exception{return TrainingSummariesWorkflow.read(s(user),project);}
    static Map<String,Object> body(long project)throws Exception{Map<String,Object> f=flow(2,project);return new LinkedHashMap<>(Map.of("project_id",project,"expected_version",n(f.get("revision")),"expected_workflow_version",n(f.get("workflow_version")),"request_id","formal-"+(++serial)));}
    static Map<String,Object> reviewBody(long project,String role,String decision,String note)throws Exception{Map<String,Object>b=body(project);b.put("review_role",role);b.put("decision",decision);b.put("note",note);return b;}
    static Map<String,Object> review(int who,long project,String role,String decision)throws Exception{return TrainingSummariesWorkflow.mutate("review",s(who),reviewBody(project,role,decision,decision.equals("RETURN")?"请补充课程亮点":"已核对"));}
    static Map<String,Object> save(int who,long project,String label)throws Exception{return TrainingSummariesIntegration.mutate("save",s(who),Map.of("project_id",project,"expected_version",n(TrainingSummariesIntegration.read(s(who),project).get("version")),"request_id","draft-"+(++serial),"content",article(label)));}
    static Map<String,Object> article(String title){return Map.of("achievements","","issues","","nextSteps","","publicity",Map.of("title",title,"introduction","为帮助新员工建立高效工作方法，本次培训围绕真实工作场景展开，通过方法讲解、案例分析和互动练习，帮助学员梳理任务安排并形成行动计划。","sections",List.of(Map.of("heading","理论筑基 靶向破题","body","课程围绕时间觉察、策略调整、场景练习与行动承诺展开，引导学员识别个人工作节奏中的问题。"),Map.of("heading","方法落地 提升效率","body","结合时间防火墙、番茄工作法和任务四象限，学员练习明确任务优先级，并安排连续的专注时段。"),Map.of("heading","互动共创 学练结合","body","小组围绕培训场景进行讨论，通过方案展示与复盘完善工作安排。字符校验：A<&>，以及中文段落。"),Map.of("heading","精心组织 全程保障","body","分公司对接人统筹培训安排，教学研发团队配合课程准备和现场实施。"),Map.of("heading","学以致用 持续改进","body","学员制定后续行动计划，并根据岗位应用情况逐步调整和完善。")),"photoCaptions",List.of("讲师授课","小组讨论","培训合影")));}
    static void refresh(int who,long project)throws Exception{TrainingSummariesIntegration.mutate("refresh",s(who),Map.of("project_id",project,"expected_version",n(TrainingSummariesIntegration.read(s(who),project).get("version")),"request_id","refresh-"+(++serial)));}
    static Configuration config(String branch,String bp,boolean reviewerPermission,boolean survey)throws Exception{
        Set<String>roles=Set.of("FULL","WRITER","BRANCH","BP","ADMIN");RelationRule relation=new RelationRule(false,roles,false,false);List<Grant>grants=new ArrayList<>();
        for(String role:roles){grants.add(new Grant(role+"READ",role,"summary.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));if(!role.equals("WRITER")){grants.add(new Grant(role+"DELIVERY",role,"delivery.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));if(survey)grants.add(new Grant(role+"SURVEY",role,"survey.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));}}
        for(String role:List.of("FULL","WRITER"))grants.add(new Grant(role+"EDIT",role,"summary.edit",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        if(reviewerPermission)grants.add(new Grant("BRANCHREVIEW","BRANCH","summary.review.branch",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));grants.add(new Grant("BPREVIEW","BP","summary.review.bp",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));grants.add(new Grant("ADMINEXPORT","ADMIN","summary.export",Action.EXPORT,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        List<Person>people=List.of(new Person("0002","001",Set.of(),branch,bp,Set.of("FULL"),true),new Person("0003","001",Set.of(),"0005","0006",Set.of("WRITER"),true),new Person("0004","002",Set.of(),null,null,Set.of("FULL"),true),new Person("0005","001",Set.of(),null,null,Set.of("BRANCH"),true),new Person("0006","001",Set.of(),null,null,Set.of("BP"),true),new Person("0007","001",Set.of(),null,null,Set.of("ADMIN"),true),new Person("0008","001",Set.of(),null,null,Set.of("BRANCH"),true),new Person("0009","001",Set.of(),null,null,Set.of("ADMIN"),true));
        List<AccountBinding>bindings=new ArrayList<>();for(int i=2;i<=9;i++)bindings.add(new AccountBinding(i,String.format("%04d",i),true));List<RoleRelations>relations=roles.stream().map(r->new RoleRelations(r,relation,relation)).toList();return new Configuration("FORMAL-"+(++configCounter),new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),roles,List.of(new Organization("001",null,true),new Organization("002",null,true)),people,relations,bindings,grants);
    }
    static void publish(String branch,String bp,boolean review,boolean survey)throws Exception{Auth.Session admin=Auth.get(Auth.login("u1",PASSWORD));Configuration old=OrganizationAccessStore.configuration();OrganizationAccessStore.publish(admin,old.version(),config(branch,bp,review,survey));login();}
    static void swapBindings(int a,int b)throws Exception{
        Configuration old=OrganizationAccessStore.configuration();String ac=old.accountBindings().stream().filter(x->x.accountId()==a).findFirst().orElseThrow().personCode(),bc=old.accountBindings().stream().filter(x->x.accountId()==b).findFirst().orElseThrow().personCode();
        var bindings=old.accountBindings().stream().map(x->new AccountBinding(x.accountId(),x.accountId()==a?bc:x.accountId()==b?ac:x.personCode(),x.enabled())).toList();
        OrganizationAccessStore.publish(Auth.get(Auth.login("u1",PASSWORD)),old.version(),new Configuration("FORMAL-"+(++configCounter),old.codeRules(),old.roleCodes(),old.organizations(),old.people(),old.relations(),bindings,old.grants(),old.combinedApprovals()));login();
    }
    static Map<String,Object> feedback(long project,String org){List<Map<String,Object>>questions=new ArrayList<>();for(int i=1;i<=10;i++)questions.add(Map.of("key","q"+i,"label",SurveySummaryImportsResponses.QUESTION_LABELS.get(i-1),"validCount",2,"blankCount",0,"invalidCount",0,"sumText","18","averageText","9.00"));Map<String,Object>out=TrainingSummariesWorkflow.map("format","M07-REVIEWED-SOURCE-1","status","AVAILABLE","reason",null,"value",Map.of("project_id",project,"organization_code",org,"results",List.of(Map.of("import_id",1,"series_id",1,"revision",feedbackRevision,"policy_version","SYNTHETIC-POLICY","policy_hash","a".repeat(64),"summary",Map.of("responseRowCount",2,"questions",questions),"review",Map.of("person_code","0006","reviewed_at","2026-09-23T00:00:00Z","review_event_id",1),"source_digest","b".repeat(64)))));out.put("source_version",TrainingSummariesWorkflow.hash(c(out)));return out;}
    static Map<String,String> tables()throws Exception{Map<String,String> out=new TreeMap<>();for(var t:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE'")){String name=t.get("table_name").toString();if(!name.matches("[A-Z_0-9]+"))throw new AssertionError();List<String>rows=Db.query("SELECT * FROM \""+name+"\"").stream().map(M08FormalTest::c).sorted().toList();out.put(name,c(rows));}check(out.size()>=15,"all synthetic tables snapshotted");return out;}
    static void unchanged(Run run)throws Exception{var before=tables();run.run();eq(before,tables(),"rejected/read operation writes no table");}
    static void fixture(Path data)throws Exception{M08DeliverySourceTest.fixture(data);String hash=Auth.hash(PASSWORD);for(int i=5;i<=9;i++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",i,"u"+i,hash,"SYNTHETIC",i==7?"admin":"viewer");publish("0005","0006",true,true);}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException();Path data=Path.of(args[0]).toAbsolutePath().normalize();if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException();try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("fresh database only");}output=Path.of(args[1]).toAbsolutePath().normalize();Files.createDirectories(output);
        try{fixture(data);
            TrainingSummariesFeedbackSource.connect(new TrainingSummariesFeedbackSource.ReviewedProvider(){public Map<String,Object>capture(Auth.Session session,long project,String org){return feedback(project,org);}public void validate(Object value)throws Exception{Map<String,Object>copy=new LinkedHashMap<>(m(value));Object digest=copy.remove("source_version");if(!"M07-REVIEWED-SOURCE-1".equals(copy.get("format"))||!Objects.equals(digest,TrainingSummariesWorkflow.hash(c(copy))))throw new Api.ApiException(409,"synthetic M07 hash invalid");}});
            group("explicit configuration and real scoped identity",()->{
                save(2,101,"新员工时间管理培训总结");unchanged(()->{rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(101)));rejects(403,()->flow(1,101));rejects(403,()->flow(4,101));eq("DRAFT",flow(2,101).get("status"));eq(false,m(flow(2,101).get("capabilities")).get("submit"));});System.setProperty("training.summary.review.order","BRANCH_THEN_BP");System.setProperty("training.summary.review.policyVersion","SYNTHETIC-REVIEW-1");
            });
            group("sequential frozen reviewers strict request and no draft edits",()->{
                Map<String,Object>submit=body(101);Map<String,Object>result=TrainingSummariesIntegration.mutate("submit",s(2),submit);eq("SUBMITTED",result.get("status"));eq("0005",result.get("branch_reviewer_code"));eq("0006",result.get("bp_reviewer_code"));eq(1L,n(result.get("workflow_version")));
                unchanged(()->{eq(true,TrainingSummariesWorkflow.mutate("submit",s(2),submit).get("replayed"));rejects(409,()->save(2,101,"禁止修改"));rejects(409,()->refresh(2,101));rejects(409,()->review(6,101,"BP","APPROVE"));rejects(403,()->review(8,101,"BRANCH","APPROVE"));rejects(403,()->review(2,101,"BRANCH","APPROVE"));rejects(409,()->TrainingSummariesWorkflow.export(s(7),body(101)));Map<String,Object>injected=body(101);injected.put("branch_reviewer_code","0008");rejects(400,()->TrainingSummariesWorkflow.mutate("submit",s(2),injected));});
                Map<String,Object>b=reviewBody(101,"BRANCH","APPROVE","已核对");result=TrainingSummariesWorkflow.mutate("review",s(5),b);eq("SUBMITTED",result.get("status"));unchanged(()->eq(true,TrainingSummariesWorkflow.mutate("review",s(5),b).get("replayed")));result=review(6,101,"BP","APPROVE");eq("APPROVED",result.get("status"));eq(2,((List<?>)result.get("decisions")).size());
            });
            group("formal Word and export idempotency preserve exact approved bytes",()->{
                unchanged(()->{rejects(403,()->TrainingSummariesWorkflow.export(s(1),body(101)));rejects(403,()->TrainingSummariesWorkflow.export(s(2),body(101)));rejects(403,()->TrainingSummariesWorkflow.export(s(9),body(101)));});Map<String,Object>request=body(101);var first=TrainingSummariesWorkflow.export(s(7),request);eq(4L,first.workflowVersion());check(first.filename().endsWith(".docx"),"docx filename");var before=tables();var replay=TrainingSummariesWorkflow.export(s(7),request);eq(before,tables(),"replay creates no audit duplicate");check(Arrays.equals(first.bytes(),replay.bytes()),"deterministic export replay");
                Map<String,String>xml=unzip(first.bytes());eq(Set.of("[Content_Types].xml","_rels/.rels","word/document.xml","word/styles.xml","word/_rels/document.xml.rels"),xml.keySet(),"strict macro-free package");String doc=xml.get("word/document.xml");for(String expected:List.of("新员工时间管理培训总结","理论筑基","照片位置","9.00","分公司负责人","0005","BP","0006","A&lt;&amp;&gt;"))check(doc.contains(expected),"approved Word content "+expected);for(String bad:List.of("TargetMode=\"External\"","PRIVATE","username","password","SYNTHETIC-POLICY","未提供汇总"))check(!doc.contains(bad),"no unintended metadata "+bad);Files.write(output.resolve("M08_正式总结_合成验收.docx"),first.bytes());
                save(2,101,"修改后须重新复核");eq("DRAFT",flow(2,101).get("status"));unchanged(()->rejects(409,()->TrainingSummariesWorkflow.export(s(7),request)));
            });
            group("return and correction reset both decisions and reject old action CAS",()->{
                TrainingSummariesWorkflow.mutate("submit",s(2),body(101));review(5,101,"BRANCH","APPROVE");Map<String,Object>old=reviewBody(101,"BP","APPROVE","旧操作");eq("RETURNED",review(6,101,"BP","RETURN").get("status"));unchanged(()->rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(101))));save(2,101,"根据意见修改总结");TrainingSummariesWorkflow.mutate("submit",s(2),body(101));eq(0,((List<?>)flow(2,101).get("decisions")).size());unchanged(()->rejects(409,()->TrainingSummariesWorkflow.mutate("review",s(6),old)));review(5,101,"BRANCH","APPROVE");review(6,101,"BP","APPROVE");
            });
            group("independent M07 rights and hidden refresh preserve original snapshot",()->{
                save(2,105,"问卷权限隔离");String stored=Db.one("SELECT sources_json FROM m08_summary_revisions WHERE project_id=105 AND revision=1").get("sources_json").toString();Map<String,Object>hidden=TrainingSummariesIntegration.read(s(3),105);Map<String,Object>source=m(hidden.get("sources"));eq("SURVEY_READ_REQUIRED",m(source.get("feedback")).get("reason"));eq(null,source.get("source_version"));check(!c(hidden).contains("SYNTHETIC-POLICY"),"no feedback source metadata");save(3,105,"无来源权限可填写");refresh(3,105);eq(m(Json.parse(stored)).get("feedback"),m(Json.parse(Db.one("SELECT sources_json FROM m08_summary_revisions WHERE project_id=105 AND revision=3").get("sources_json").toString())).get("feedback"),"hidden refresh retains full M07 source");unchanged(()->rejects(403,()->TrainingSummariesWorkflow.mutate("submit",s(3),body(105))));
                Map<String,Object>comparison=TrainingSummariesIntegration.comparison(s(3),105,1,3);for(String side:List.of("before","after")){Map<String,Object>sources=m(m(comparison.get(side)).get("sources"));eq(null,sources.get("source_version"));eq(null,m(sources.get("feedback")).get("value"));}
            });
            group("reviewed source change requires explicit refresh and resubmission",()->{
                save(2,103,"来源发生变化");feedbackRevision++;unchanged(()->rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(103))));refresh(2,103);TrainingSummariesWorkflow.mutate("submit",s(2),body(103));feedbackRevision++;unchanged(()->rejects(409,()->review(5,103,"BRANCH","APPROVE")));review(5,103,"BRANCH","RETURN");refresh(2,103);TrainingSummariesWorkflow.mutate("submit",s(2),body(103));review(5,103,"BRANCH","APPROVE");review(6,103,"BP","APPROVE");
            });
            group("unordered review is explicit and policy freezes per submission",()->{
                System.setProperty("training.summary.review.order","UNORDERED");System.setProperty("training.summary.review.policyVersion","SYNTHETIC-REVIEW-2");save(2,102,"并行复核总结");TrainingSummariesWorkflow.mutate("submit",s(2),body(102));System.setProperty("training.summary.review.order","BRANCH_THEN_BP");System.setProperty("training.summary.review.policyVersion","SYNTHETIC-REVIEW-3");review(6,102,"BP","APPROVE");eq("APPROVED",review(5,102,"BRANCH","APPROVE").get("status"));
            });
            group("configuration changes cannot reroute an existing submission",()->{
                save(2,104,"冻结复核人");TrainingSummariesWorkflow.mutate("submit",s(2),body(104));Auth.Session old=s(2);publish("0008","0006",true,true);unchanged(()->{rejects(401,()->TrainingSummariesWorkflow.read(old,104));rejects(403,()->review(8,104,"BRANCH","APPROVE"));});review(5,104,"BRANCH","APPROVE");review(6,104,"BP","APPROVE");save(2,104,"新提交使用新关系");TrainingSummariesWorkflow.mutate("submit",s(2),body(104));eq("0008",flow(2,104).get("branch_reviewer_code"));publish("0008","0006",false,true);unchanged(()->rejects(403,()->review(8,104,"BRANCH","APPROVE")));publish("0005","0006",true,true);review(8,104,"BRANCH","RETURN");
            });
            group("archived records read and approved export only",()->{
                Db.exec("UPDATE projects SET status='已归档' WHERE id=102");try{unchanged(()->{eq("APPROVED",flow(2,102).get("status"));rejects(409,()->save(2,102,"归档禁止编辑"));rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(102)));});TrainingSummariesWorkflow.export(s(7),body(102));}finally{Db.exec("UPDATE projects SET status='进行中' WHERE id=102");}
            });
            group("strict HTTP routing methods fields and session identity",M08FormalTest::http);
            group("damaged history fails even when older event is not displayed",()->{
                var row=Db.one("SELECT payload_json FROM m08_summary_workflow_events WHERE project_id=101 AND version=1");String original=row.get("payload_json").toString();Db.exec("UPDATE m08_summary_workflow_events SET payload_json='{}' WHERE project_id=101 AND version=1");try{unchanged(()->{rejects(409,()->flow(2,101));rejects(409,()->TrainingSummariesIntegration.read(s(3),101));rejects(409,()->TrainingSummariesWorkflow.history(s(2),101,10,1));});}finally{Db.exec("UPDATE m08_summary_workflow_events SET payload_json=? WHERE project_id=101 AND version=1",original);}
                String head=Db.one("SELECT head_hash FROM m08_summary_workflow_heads WHERE project_id=101").get("head_hash").toString();Db.exec("UPDATE m08_summary_workflow_heads SET head_hash=? WHERE project_id=101","f".repeat(64));try{unchanged(()->rejects(409,()->flow(2,101)));}finally{Db.exec("UPDATE m08_summary_workflow_heads SET head_hash=? WHERE project_id=101",head);}
            });
            group("missing or combined reviewers never silently route",()->{
                save(2,104,"复核人员必须明确");publish(null,"0006",true,true);unchanged(()->rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(104))));
                publish("0005","0005",true,true);unchanged(()->rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(104))));publish("0005","0006",true,true);
            });
            group("idempotency result cannot point to another valid submission",()->{
                var request=Db.one("SELECT request_id,result_version FROM m08_summary_workflow_requests WHERE project_id=101 AND operation='submit' AND result_version=1");
                Map<String,Object>b=Map.of("project_id",101,"expected_version",1,"expected_workflow_version",0,"request_id",request.get("request_id"));
                Db.exec("UPDATE m08_summary_workflow_requests SET result_version=5 WHERE account_id=2 AND request_id=?",request.get("request_id"));
                try{unchanged(()->rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),b)));}finally{Db.exec("UPDATE m08_summary_workflow_requests SET result_version=1 WHERE account_id=2 AND request_id=?",request.get("request_id"));}
            });
            group("account rebinding cannot self-review or complete both review roles",()->{
                TrainingSummariesWorkflow.mutate("submit",s(2),body(104));swapBindings(2,5);
                unchanged(()->rejects(403,()->review(2,104,"BRANCH","APPROVE")));swapBindings(2,5);review(5,104,"BRANCH","APPROVE");swapBindings(5,6);
                unchanged(()->rejects(403,()->review(5,104,"BP","APPROVE")));swapBindings(5,6);review(6,104,"BP","RETURN");
            });
            group("two concurrent submissions yield one commit and stale conflict",()->{
                save(2,104,"并发提交");var first=body(104);var second=new LinkedHashMap<>(first);second.put("request_id","concurrent-"+(++serial));ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Integer>a=()->attempt(first),b=()->attempt(second);var results=pool.invokeAll(List.of(a,b));List<Integer>codes=new ArrayList<>();for(var result:results)codes.add(result.get());Collections.sort(codes);eq(List.of(200,409),codes,"only one CAS winner");}finally{pool.shutdown();check(pool.awaitTermination(10,TimeUnit.SECONDS),"workers exited");}
            });
            group("post-write revocation rolls back head event request and user change",()->{
                review(5,104,"BRANCH","RETURN");save(2,104,"回滚检查");var before=tables();Db.exec("CREATE TRIGGER formal_revoke AFTER INSERT ON m08_summary_workflow_events FOR EACH ROW CALL 'com.training.M08FormalTest$Revoke'");try{rejects(401,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(104)));}finally{Db.exec("DROP TRIGGER formal_revoke");}eq(before,tables(),"complete transaction rollback");login();
            });
            group("restart and existing revision projections remain usable",()->{String frozen=Db.one("SELECT sources_json FROM m08_summary_revisions WHERE project_id=101 AND revision=1").get("sources_json").toString();Db.get().close();TrainingSummariesIntegration.init();eq(frozen,Db.one("SELECT sources_json FROM m08_summary_revisions WHERE project_id=101 AND revision=1").get("sources_json"));eq("APPROVED",flow(2,101).get("status"));unchanged(()->{TrainingSummariesIntegration.history(s(2),101,0,20);TrainingSummariesIntegration.revision(s(2),101,1);TrainingSummariesIntegration.comparison(s(3),101,0,1);});});
            System.out.println("M08Formal: "+groups+" groups, "+checks+" checks passed. M07 is an explicit synthetic reviewed-provider test double; no shared or production data.");
        }finally{System.clearProperty("training.summary.review.order");System.clearProperty("training.summary.review.policyVersion");Db.get().close();}
    }
    static int attempt(Map<String,Object>b)throws Exception{try{TrainingSummariesWorkflow.mutate("submit",s(2),b);return 200;}catch(Api.ApiException e){return e.code;}}
    static Map<String,String> unzip(byte[] bytes)throws IOException{Map<String,String>out=new LinkedHashMap<>();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){for(ZipEntry e;(e=z.getNextEntry())!=null;)out.put(e.getName(),new String(z.readAllBytes(),StandardCharsets.UTF_8));}return out;}
    static void http()throws Exception {unchanged(()->{
        for(String path:List.of("/api/training-summaries/workflow?project_id=101","/api/training-summaries/workflow-history?project_id=101&offset=0&limit=10")){var exchange=new M08DeliverySourceTest.Exchange("GET",path,tokens.get(2),null);check(TrainingSummariesIntegration.handle(exchange,s(2)),"workflow HTTP handled");check(M08DeliverySourceTest.data(exchange).containsKey("workflow_version"),"workflow DTO");}
        for(String q:List.of("project_id=101&project_id=102","project_id=101&%70roject_id=102","project_id=101&expected_version=1","project_id=1e2","project_id=-1","project_id=9007199254740992"))rejects(400,()->TrainingSummariesIntegration.handle(new M08DeliverySourceTest.Exchange("GET","/api/training-summaries/workflow?"+q,tokens.get(2),null),s(2)));
        rejects(405,()->TrainingSummariesIntegration.handle(new M08DeliverySourceTest.Exchange("POST","/api/training-summaries/workflow?project_id=101",tokens.get(2),null),s(2)));
        rejects(401,()->TrainingSummariesIntegration.handle(new M08DeliverySourceTest.Exchange("GET","/api/training-summaries/workflow?project_id=101",tokens.get(3),null),s(2)));
        for(String bad:List.of("role","approved","reviewers","configuration")){Map<String,Object>b=body(104);b.put(bad,true);rejects(400,()->TrainingSummariesWorkflow.mutate("submit",s(2),b));}
        for(String field:List.of("project_id","expected_version","expected_workflow_version"))for(Object invalid:Arrays.asList(null,"1",-1,1.5,9007199254740992L)){
            Map<String,Object>b=body(104);b.put(field,invalid);rejects(400,()->TrainingSummariesWorkflow.mutate("submit",s(2),b));
        }
        for(String field:List.of("review_role","decision"))for(Object invalid:Arrays.asList(null,"",true,Map.of())){
            Map<String,Object>b=reviewBody(104,"BRANCH","APPROVE","");b.put(field,invalid);rejects(400,()->TrainingSummariesWorkflow.mutate("review",s(5),b));
        }
        Map<String,Object>b=reviewBody(104,"BP","RETURN","");rejects(409,()->TrainingSummariesWorkflow.mutate("review",s(6),b));
        Db.get().setAutoCommit(false);try{rejects(409,()->flow(2,101));}finally{Db.get().rollback();Db.get().setAutoCommit(true);}
    });}
    public static final class Revoke implements org.h2.api.Trigger{public void fire(java.sql.Connection connection,Object[]old,Object[]row)throws java.sql.SQLException{try(var q=connection.prepareStatement("UPDATE users SET status=0 WHERE id=2")){q.executeUpdate();}}}
}
