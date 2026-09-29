package com.training;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static com.training.OrganizationAccess.*;
import static com.training.M08FormalTest.*;

/** Real Auth/M01/M05/M08, fresh synthetic H2, explicit reviewed-M07 provider double. */
public final class M08CombinedTest {
    static Path qa;static String photo;static byte[] legacyWord;static Map<String,Object> legacyExport,combinedApprove;
    static Configuration combinedConfig(boolean evidence,String relationBp,boolean bpGrant,String evidenceRef,boolean self){
        try{
            Configuration base=config("0005",relationBp,true,true);List<Person> people=new ArrayList<>();
            for(Person person:base.people()){
                if(person.personCode().equals("0005"))people.add(new Person("0005","001",Set.of("001"),self?"0005":null,self?"0005":null,self?Set.of("BRANCH","BP","FULL"):Set.of("BRANCH","BP"),true,self?Map.of("BRANCH",Set.of("001"),"BP",Set.of("001"),"FULL",Set.of()):Map.of("BRANCH",Set.of("001"),"BP",Set.of("001"))));else people.add(person);
            }
            var grants=base.grants().stream().filter(g->bpGrant||!g.resource().equals("summary.review.bp")).toList();
            var relations=self?base.roleCodes().stream().map(r->new RoleRelations(r,new RelationRule(false,base.roleCodes(),false,true),new RelationRule(false,base.roleCodes(),false,true))).toList():base.relations();
            return new Configuration(base.version(),base.codeRules(),base.roleCodes(),base.organizations(),people,relations,base.accountBindings(),grants,evidence?List.of(new CombinedApprovalAssignment("001","0005","BRANCH","BP",evidenceRef)):List.of());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    static void install(Configuration config)throws Exception{OrganizationAccessStore.publish(Auth.get(Auth.login("u1",PASSWORD)),OrganizationAccessStore.configuration().version(),config);login();}
    static void combined(boolean evidence,String bp,boolean grant,String ref)throws Exception{install(combinedConfig(evidence,bp,grant,ref,false));}
    static Map<String,Object> command(long project,String decision,String note)throws Exception{return reviewBody(project,"COMBINED",decision,note);}
    static Map<String,Object> approve(long project)throws Exception{return TrainingSummariesWorkflow.mutate("review",s(5),command(project,"APPROVE_COMBINED","同时核对负责人及BP两项职责"));}
    static long eventCount(long project)throws Exception{return n(Db.one("SELECT COUNT(*) AS n FROM m08_summary_workflow_events WHERE project_id=?",project).get("n"));}
    static String historyBytes(long project)throws Exception{return c(Db.query("SELECT * FROM m08_summary_workflow_events WHERE project_id=? ORDER BY version",project));}
    static Map<String,Object> capabilities(long project)throws Exception{return m(flow(5,project).get("capabilities"));}
    static void photoDraft(long project,String caption)throws Exception{
        byte[] image=M08PhotosTest.makeImage(960,540,"jpeg","SYNTHETIC COMBINED REVIEW");var upload=TrainingSummariesPhotos.upload(s(2),Map.of("project_id",project,"expected_version",n(read(project).get("version")),"request_id","combined-photo-"+(++serial),"content_type","image/jpeg","data_base64",Base64.getEncoder().encodeToString(image)));photo=(String)upload.get("photo_id");
        TrainingSummariesIntegration.mutate("save",s(2),Map.of("project_id",project,"expected_version",n(read(project).get("version")),"request_id","combined-draft-"+(++serial),"content",article("时间管理培训总结 兼任复核合成验收"),"photos",List.of(Map.of("photo_id",photo,"caption",caption))));
    }
    public static void main(String[] args)throws Exception {
        Path data=Path.of(args[0]).toAbsolutePath().normalize();qa=Path.of(args[1]).toAbsolutePath().normalize();try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new AssertionError("fresh synthetic DB only");}Files.createDirectories(qa);
        try{fixture(data);TrainingSummariesFeedbackSource.connect(new TrainingSummariesFeedbackSource.ReviewedProvider(){public Map<String,Object>capture(Auth.Session s,long p,String org){return feedback(p,org);}public void validate(Object value)throws Exception{var copy=new LinkedHashMap<>(m(value));Object digest=copy.remove("source_version");if(!Objects.equals(digest,TrainingSummariesWorkflow.hash(c(copy))))throw new Api.ApiException(409,"synthetic M07 digest invalid");}});
            group("frozen ordinary pending history is not promoted after explicit combined configuration",()->{
                System.setProperty("training.summary.review.order","BRANCH_THEN_BP");System.setProperty("training.summary.review.policyVersion","SYNTHETIC-ORDINARY");photoDraft(101,"普通流程兼容照片");TrainingSummariesPhotoBaselineWorkflow.mutate("submit",s(2),body(101));String before=historyBytes(101);
                combined(true,"0005",true,"SYNTHETIC-DUAL-DUTY");eq("DUAL_REVIEW",flow(2,101).get("review_mode"));eq(before,historyBytes(101));rejects(409,()->approve(101));
                TrainingSummariesPhotoBaselineWorkflow.mutate("review",s(5),reviewBody(101,"BRANCH","APPROVE","已核对"));rejects(403,()->review(5,101,"BP","APPROVE"));TrainingSummariesPhotoBaselineWorkflow.mutate("review",s(6),reviewBody(101,"BP","APPROVE","已核对"));
                legacyExport=body(101);legacyWord=TrainingSummariesPhotoBaselineWorkflow.export(s(7),legacyExport).bytes();before=historyBytes(101);check(Arrays.equals(legacyWord,TrainingSummariesWorkflow.export(s(7),legacyExport).bytes()),"old ordinary photo Word export bytes and request replay remain exact");eq(before,historyBytes(101));
                eq(2,((List<?>)flow(2,101).get("decisions")).size());
            });
            group("explicit summary evidence works without demand approval permission or normal order default",()->{
                System.clearProperty("training.summary.review.order");System.clearProperty("training.summary.review.policyVersion");
                check(OrganizationAccessStore.combinedAssignment("001","0005")==null,"M03 helper needs approval.review, intentionally not granted");photoDraft(102,"兼任复核核准的现场照片 合成图");eq(true,m(read(102).get("capabilities")).get("submit"));
                TrainingSummariesWorkflow.mutate("submit",s(2),body(102));var flow=flow(5,102);eq("SINGLE_EXPLICIT",flow.get("review_mode"));eq("COMBINED",flow.get("review_order"));eq(1L,n(flow.get("workflow_version")));eq("COMBINED",m(flow.get("capabilities")).get("review_role"));
                var submit=m(Json.parse(Db.one("SELECT payload_json FROM m08_summary_workflow_events WHERE project_id=102 AND version=1").get("payload_json").toString()));var evidence=m(submit.get("combined_assignment"));eq("SYNTHETIC-DUAL-DUTY",evidence.get("evidence_ref"));eq(5L,n(evidence.get("reviewer_account_id")));eq("001",evidence.get("organization_code"));
                rejects(409,()->review(5,102,"BRANCH","APPROVE"));rejects(400,()->TrainingSummariesWorkflow.mutate("review",s(5),command(102,"APPROVE","ambiguous")));rejects(403,()->TrainingSummariesWorkflow.mutate("review",s(6),command(102,"APPROVE_COMBINED","wrong person")));rejects(403,()->TrainingSummariesWorkflow.mutate("review",s(7),command(102,"APPROVE_COMBINED","admin is not the reviewer")));
                combinedApprove=command(102,"APPROVE_COMBINED","同时核对两项岗位");var approved=TrainingSummariesWorkflow.mutate("review",s(5),combinedApprove);eq("APPROVED",approved.get("status"));eq(2L,eventCount(102));eq(2L,n(approved.get("workflow_version")));List<?> decisions=(List<?>)approved.get("decisions");eq(1,decisions.size());eq(List.of("BRANCH","BP"),m(decisions.get(0)).get("responsibilities"));eq("0005",m(decisions.get(0)).get("actor_code"));
                eq(true,TrainingSummariesWorkflow.mutate("review",s(5),combinedApprove).get("replayed"));eq(2L,eventCount(102));var history=TrainingSummariesWorkflow.history(s(2),102,0,10);var item=m(((List<?>)history.get("items")).get(0));eq("APPROVE_COMBINED",item.get("action"));eq(List.of("BRANCH","BP"),item.get("responsibilities"));
                var request=body(102);var export=TrainingSummariesWorkflow.export(s(7),request);String xml=unzip(export.bytes()).get("word/document.xml");check(xml.contains("负责人及BP（兼任）：同一人员同时完成两岗位复核"),"clear combined Word wording");check(!xml.contains("BP：已通过"),"no fake second independent reviewer");check(Arrays.equals(export.bytes(),TrainingSummariesWorkflow.export(s(7),request).bytes()),"combined export deterministic retry");Files.write(qa.resolve("M08_兼任复核_图文总结_合成验收.docx"),export.bytes());
            });
            group("missing evidence and permission loss fail; M01 resource decisions remain authoritative",()->{
                save(2,103,"兼任待审");combined(false,"0005",true,"SYNTHETIC-DUAL-DUTY");rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(103)));eq(0L,eventCount(103));eq(false,m(read(103).get("capabilities")).get("submit"));
                combined(true,"0005",false,"SYNTHETIC-DUAL-DUTY");rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(103)));combined(true,"0005",true,"SYNTHETIC-DUAL-DUTY");TrainingSummariesWorkflow.mutate("submit",s(2),body(103));String frozen=historyBytes(103);
                combined(true,"0005",false,"SYNTHETIC-DUAL-DUTY");eq(false,capabilities(103).get("review"));check(flow(5,103).get("review_blocked_reason")!=null,"reason shown");rejects(403,()->approve(103));eq(frozen,historyBytes(103));
                Configuration base=combinedConfig(true,"0005",false,"SYNTHETIC-DUAL-DUTY",false);var grants=new ArrayList<>(base.grants());grants.add(new Grant("WRONGROLEBP","BRANCH","summary.review.bp",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));install(new Configuration(base.version(),base.codeRules(),base.roleCodes(),base.organizations(),base.people(),base.relations(),base.accountBindings(),grants,base.combinedApprovals()));
                check(OrganizationAccessStore.authorize(s(5),"summary.review.bp",Action.HANDLE,"001").allowed(),"explicit M01 resource grant is authoritative");eq(true,capabilities(103).get("review"));eq(frozen,historyBytes(103));combined(true,"0005",true,"SYNTHETIC-DUAL-DUTY");
            });
            group("changed relation and evidence pause pending work; unrelated config changes preserve frozen audit",()->{
                String before=historyBytes(103);combined(true,"0006",true,"SYNTHETIC-DUAL-DUTY");rejects(409,()->approve(103));eq(false,capabilities(103).get("review"));eq("SINGLE_EXPLICIT",flow(5,103).get("review_mode"));eq(before,historyBytes(103));
                combined(true,"0005",true,"SYNTHETIC-CHANGED-EVIDENCE");rejects(409,()->approve(103));eq(before,historyBytes(103));combined(true,"0005",true,"SYNTHETIC-DUAL-DUTY");eq(true,capabilities(103).get("review"));eq(before,historyBytes(103));
                Configuration old=OrganizationAccessStore.configuration();String frozenConfig=m(m(Json.parse(Db.one("SELECT payload_json FROM m08_summary_workflow_events WHERE project_id=103 AND version=1").get("payload_json").toString())).get("combined_assignment")).get("configuration_version").toString();check(!frozenConfig.equals(old.version()),"unrelated current version differs from frozen");
                rejects(400,()->TrainingSummariesWorkflow.mutate("review",s(5),command(103,"RETURN","")));TrainingSummariesWorkflow.mutate("review",s(5),command(103,"RETURN","补充总结内容"));eq("RETURNED",flow(2,103).get("status"));eq(0,((List<?>)flow(2,103).get("decisions")).size());rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(103)));save(2,103,"修改后重新兼任复核");TrainingSummariesWorkflow.mutate("submit",s(2),body(103));eq(0,((List<?>)flow(2,103).get("decisions")).size());
            });
            group("self-review and account rebinding remain forbidden",()->{
                swapBindings(2,5);rejects(403,()->TrainingSummariesWorkflow.mutate("review",s(2),command(103,"APPROVE_COMBINED","rebound submitting account")));swapBindings(2,5);eq(true,capabilities(103).get("review"));
                install(combinedConfig(true,"0005",true,"SYNTHETIC-DUAL-DUTY",true));save(5,105,"不能复核自己的总结");rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(5),new LinkedHashMap<>(Map.of("project_id",105,"expected_version",1,"expected_workflow_version",0,"request_id","self-combined"))));combined(true,"0005",true,"SYNTHETIC-DUAL-DUTY");
            });
            group("post-write revocation rolls back one event and request; concurrent approvals commit once",()->{
                long before=eventCount(103);String audit=historyBytes(103);Db.exec("CREATE TRIGGER combined_revoke AFTER INSERT ON m08_summary_workflow_events FOR EACH ROW CALL 'com.training.M08CombinedTest$Revoke'");try{rejects(401,()->approve(103));}finally{Db.exec("DROP TRIGGER combined_revoke");}eq(before,eventCount(103));eq(audit,historyBytes(103));login();
                var a=command(103,"APPROVE_COMBINED","one");var b=command(103,"APPROVE_COMBINED","two");ExecutorService pool=Executors.newFixedThreadPool(2);List<Integer> codes=new ArrayList<>();try{var tasks=pool.invokeAll(List.of((Callable<Integer>)()->attemptReview(a),()->attemptReview(b)));for(var task:tasks)codes.add(task.get());}finally{pool.shutdown();check(pool.awaitTermination(10,TimeUnit.SECONDS),"combined workers stop");}Collections.sort(codes);eq(List.of(200,409),codes);eq(before+1,eventCount(103));eq(1,((List<?>)flow(2,103).get("decisions")).size());
            });
            group("frozen approved history survives current removal; replay still rechecks current rights",()->{
                String audit=historyBytes(102);combined(false,"0006",true,"SYNTHETIC-DUAL-DUTY");eq("APPROVED",flow(2,102).get("status"));eq(audit,historyBytes(102));rejects(409,()->TrainingSummariesWorkflow.mutate("review",s(5),combinedApprove));check(TrainingSummariesWorkflow.export(s(7),body(102)).bytes().length>0,"historically valid combined approval can still be exported by current authorized admin");combined(true,"0005",true,"SYNTHETIC-DUAL-DUTY");
                save(2,102,"已批准后修改正文需要新的复核");eq("DRAFT",flow(2,102).get("status"));rejects(409,()->TrainingSummariesWorkflow.export(s(7),body(102)));
                check(((List<?>)TrainingSummariesIntegration.revision(s(2),102,1).get("photos")).size()==1,"old photo reference preserved");check(((List<?>)m(read(102).get("current")).get("photos")).size()==1,"ordinary edit preserves fixed photo");
            });
            group("strict new event recovery and restart preserve original ordinary byte history",()->{
                save(2,104,"恢复校验");TrainingSummariesWorkflow.mutate("submit",s(2),body(104));approve(104);
                tamper(104,1,d->d.remove("review_mode"));tamper(104,1,d->d.put("review_mode","AUTO"));tamper(104,1,d->{m(d.get("combined_assignment")).put("organization_code","002");});tamper(104,1,d->{m(d.get("combined_assignment")).put("bp_role_code","BRANCH");});tamper(104,1,d->{m(d.get("combined_assignment")).put("configuration_version","OTHER");});tamper(104,1,d->{m(d.get("combined_assignment")).put("reviewer_account_id",2);});tamper(104,2,d->d.put("responsibilities",List.of("BRANCH")));tamper(104,2,d->d.put("review_role","BRANCH"));
                String before=historyBytes(104);Db.get().close();TrainingSummariesIntegration.init();eq("APPROVED",flow(2,104).get("status"));eq(before,historyBytes(104));check(Arrays.equals(legacyWord,TrainingSummariesWorkflow.export(s(7),legacyExport).bytes()),"ordinary photo Word still exact after combined history and restart");
            });
            System.out.println("M08Combined: "+groups+" groups, "+checks+" checks passed; real M01 evidence and summary-only rights, synthetic data only.");
        }finally{System.clearProperty("training.summary.review.order");System.clearProperty("training.summary.review.policyVersion");Db.get().close();}
    }
    static int attemptReview(Map<String,Object> request)throws Exception{try{TrainingSummariesWorkflow.mutate("review",s(5),request);return 200;}catch(Api.ApiException e){return e.code;}}
    static void tamper(long project,long version,Consumer<Map<String,Object>> mutate)throws Exception {
        var rows=Db.query("SELECT * FROM m08_summary_workflow_events WHERE project_id=? ORDER BY version",project);String head=Db.one("SELECT head_hash FROM m08_summary_workflow_heads WHERE project_id=?",project).get("head_hash").toString();
        try{String previous="0".repeat(64);for(var row:rows){var data=new LinkedHashMap<>(m(Json.parse(row.get("payload_json").toString())));if(n(row.get("version"))==version)mutate.accept(data);String digest=TrainingSummariesWorkflow.hash(c(TrainingSummariesWorkflow.map("format","M08-WORKFLOW-1","project_id",project,"version",row.get("version"),"revision",row.get("revision"),"action",row.get("action"),"actor_code",row.get("actor_code"),"account_id",row.get("account_id"),"config_version",row.get("config_version"),"created_at",row.get("created_at"),"data",data,"previous_hash",previous)));Db.exec("UPDATE m08_summary_workflow_events SET payload_json=?,previous_hash=?,event_hash=? WHERE project_id=? AND version=?",c(data),previous,digest,project,row.get("version"));previous=digest;}Db.exec("UPDATE m08_summary_workflow_heads SET head_hash=? WHERE project_id=?",previous,project);rejects(409,()->flow(2,project));}
        finally{for(var row:rows)Db.exec("UPDATE m08_summary_workflow_events SET payload_json=?,previous_hash=?,event_hash=? WHERE project_id=? AND version=?",row.get("payload_json"),row.get("previous_hash"),row.get("event_hash"),project,row.get("version"));Db.exec("UPDATE m08_summary_workflow_heads SET head_hash=? WHERE project_id=?",head,project);}
    }
    public static final class Revoke implements org.h2.api.Trigger {public void fire(Connection connection,Object[] oldRow,Object[] newRow)throws SQLException{try(Statement s=connection.createStatement()){s.executeUpdate("UPDATE users SET status=0 WHERE id=5");}}}
}
