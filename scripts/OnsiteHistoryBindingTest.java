package com.training;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Authored end-to-end source contracts, not model accuracy or credentials. */
public final class OnsiteHistoryBindingTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static String query(String topic){return "信息中心为账号管理员找《"+topic+"》老师，须有给同类管理员现场面授完成该课的经历。只做过在线录屏讲解不满足这次条件。";}
    static String source(String topic){return "我已给账号管理员完整讲授《"+topic+"》，采用现场面授，课程已经结束。课程材料说明了账号停用与权限复核的衔接。";}
    static Map<String,Object> inspect(String q,String text){
        Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",80.0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage(q).assess(c,text,false);
        RequirementConstraints r=new RequirementConstraints(q,RequirementTopic.parse(q));
        return Map.of("candidate",c,"contract",RequirementTopic.parse(q).toMap(),"unknown",r.unknownExclusion,
            "exclusions",r.resolvedWorkerExclusions,"features",r.features);
    }
    static boolean admitted(String q,String text){return Boolean.TRUE.equals(((Map<?,?>)inspect(q,text).get("candidate")).get("_admitted"));}
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("--inspect")){
            List<?> input=(List<?>)Json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
            System.out.println(Json.write(input.stream().map(x->{Map<?,?> row=(Map<?,?>)x;return inspect((String)row.get("query"),(String)row.get("text"));}).toList()));return;
        }
        for(String topic:List.of("离岗权限回收","古籍页码核对","园艺工具清洁","🌿标本标签核对","不要轻信陌生人","必须核对的步骤")){
            String q=query(topic),text=source(topic);
            var contract=RequirementTopic.parse(q);check(contract.courses().size()==1,"one declared course");
            var course=contract.courses().get(0);check(course.canonicalAnchor().equals(topic),"literal course");
            check(course.historyConditions().size()==1,"attached history carried");
            var h=course.historyConditions().get(0);
            check(h.personal()&&h.completed()&&h.pastTeaching(),"required personal completed history");
            check(h.audienceBindings().size()==1&&h.audienceBindings().get(0).text().equals("账号管理员"),"same-type reference uses earlier literal audience");
            check(h.audienceReferences().get(0).text().equals("同类管理员"),"reference text retained, not rewritten");
            check(h.modeBindings().get(0).text().equals("现场面授"),"mode bound to same course");
            RequirementConstraints constraints=new RequirementConstraints(q,contract);
            check(!constraints.unknownExclusion,"recording exclusion covered by positive onsite history");
            check(constraints.handlesWorkerExclusions(Map.of("query_requires_review",true,"query_exclusions",List.of("只做过在线录屏讲解不满足这次条件"))),"worker exclusion reconciled");
            check(!constraints.handlesWorkerExclusions(Map.of("query_requires_review",true,"query_exclusions",List.of("不得采用其他教材"))),"unknown worker exclusion retained");
            check(admitted(q,text),"original full onsite request recovers");
            String past="需要主讲过《"+topic+"》";
            var pastCondition=RequirementTopic.parse(past).courses().get(0).historyConditions().get(0);
            check(pastCondition.pastTeaching()&&!pastCondition.completed(),"plain past request not whole-course completion");
            check(admitted(past,"本人已为实习店员主讲《"+topic+"》。"),"plain past history remains sufficient for plain past request");
            var events=TeachingEvents.extract(text);check(events.size()==1&&events.get(0).completedPersonalTeaching(),"one established completed event");
            check(events.get(0).value("audience").equals("账号管理员"),"source audience unchanged");
            check(events.get(0).relations.stream().anyMatch(r->"course_status_context_only".equals(r.get("relation"))),"status context only");
            check(events.get(0).relations.stream().anyMatch(r->"material_description_context_only".equals(r.get("relation"))),"material context only");
            check(TeachingEvents.scoringSections(text).isEmpty(),"no full-event material reembedding");
            for(String bad:List.of(text.replace("现场面授","在线录屏"),text.replace("现场面授","远程直播"),text.replace("账号管理员","仓库盘点员"),
                    text.replace("我已给","同事已给"),text.replace("我已给","我计划给"),text.replace("完整讲授","讲授"),text.replace(topic,"另一门课"),
                    text+"本人仅担任助教。",text+"以上是履历模板，不代表本人经历。"))
                check(!admitted(q,bad),"insufficient fact not admitted: "+bad);
            for(String badQuery:List.of(q.replace("同类管理员","同类审计员"),q.replace("同类管理员","同一批管理员"),q.replace("现场面授完成","远程直播完成"),
                    q.replace("信息中心为账号管理员找","信息中心为账号管理员或审计员找"),
                    q+"必须每场不少于三小时。",q.replace("不满足这次条件","不满足这次条件且不得使用投影"),
                    "培训主题：《"+topic+"》。只做过在线录屏讲解不满足这次条件。"))
                check(!admitted(badQuery,text),"unbound/extra constraints not discharged: "+badQuery);
            String remote=q.substring(0,q.indexOf('。')).replace("现场面授","远程直播")+"。";
            check(admitted(remote,text.replace("现场面授","远程直播")),"explicit live requirement supported");
            check(!admitted(remote,text.replace("现场面授","在线录屏")),"recorded not live");
            check(TeachingEvents.extract("我主讲《"+topic+"》。课程已经结束。").stream().noneMatch(TeachingEvents.Event::completedPersonalTeaching),"status alone does not invent personal completion");
            check(TeachingEvents.extract("课程材料说明了账号停用与权限复核的衔接。").isEmpty(),"materials alone not a teaching event");
        }
        System.out.println("Onsite history binding checks: "+checks);
    }
}
