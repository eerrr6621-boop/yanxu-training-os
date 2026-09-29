package com.training;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Authored syntax/source-binding regressions, not a model accuracy benchmark. */
public final class HistoricalRequestTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static Map<String,Object> assess(String query,String text){
        Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",80.0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage(query).assess(c,text,false);return c;
    }
    static boolean admitted(String q,String source){return Boolean.TRUE.equals(assess(q,source).get("_admitted"));}
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("--inspect")){
            List<Object> input=(List<Object>)Json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
            System.out.println(Json.write(input.stream().map(x->RequirementTopic.parse((String)x).toMap()).toList()));return;
        }
        for(String topic:List.of("先示范、让新人复做、再给动作反馈","观察，记录；复核","园艺工具清洁","🌱幼苗移栽"))for(String quotes:List.of("“”","「」","《》","\"\"")){
            String q="要找本人以前在现场给带教师傅完整教过"+quotes.charAt(0)+topic+quotes.charAt(1)+"方法的讲师";
            var contract=RequirementTopic.parse(q);check(contract.courses().size()==1,"one intact content object "+q);
            var course=contract.courses().get(0);check(course.canonicalAnchor().equals(java.text.Normalizer.normalize(topic,java.text.Normalizer.Form.NFKC)),"normalised canonical content");
            check(course.anchorSpans().get(0).text().equals(topic),"literal quoted content preserved");
            var h=course.historyConditions().get(0);check(h.personal()&&h.pastTeaching()&&h.completed()&&!h.independent(),"only explicit history predicates");
            check(h.audienceBindings().get(0).text().equals("带教师傅"),"audience excludes completion modifier");
            check(h.modeBindings().get(0).text().equals("在现场"),"mode bound to same course");
            check(contract.unparsedClauses().isEmpty(),"whole recognised request");
            for(var s:List.of(course.anchorSpans().get(0),h.span(),h.audienceBindings().get(0),h.modeBindings().get(0))){
                check(q.substring(s.start(),s.end()).equals(s.text()),"literal UTF16 span");
                Map<String,Object> m=s.toMap(q,"requirement_original");int start=((Number)m.get("source_start")).intValue(),end=((Number)m.get("source_end")).intValue();
                check(q.substring(q.offsetByCodePoints(0,start),q.offsetByCodePoints(0,end)).equals(s.text()),"unicode wire span");
            }
        }
        for(String actor:List.of("本人","讲师本人","老师本人","教师本人"))for(String learner:List.of("新员工","未成年学员","支行经理")){
            var f=HistoricalTeachingRequest.parse("需要"+actor+"曾经为"+learner+"独立完整讲授过《古籍页码核对》课程");
            check(f!=null&&f.independent()&&f.completed(),"explicit independent/completed");check(f.audience().text().equals(learner),"arbitrary learner profession");
        }
        check(!HistoricalTeachingRequest.parse("本人曾给新员工教过《工具清洁》").completed(),"past teaching is not full completion");
        check(HistoricalTeachingRequest.parse("本人曾给新员工讲完过《工具清洁》").completed(),"explicit finished action");
        var inert=HistoricalTeachingRequest.parse("本人曾教过《完整独立现场授课记录》");
        check(inert!=null&&!inert.completed()&&!inert.independent()&&inert.mode()==null,"quoted words never create predicates");
        for(String invalid:List.of(
            "要找本人计划在现场给新员工完整教过《工具清洁》课程的讲师",
            "要找本人以前在现场给经理安排同事完整教过《工具清洁》课程的讲师",
            "要找本人以前在现场给新员工必须英语授课完整教过《工具清洁》课程的讲师",
            "要找本人以前在现场给新员工完整教过“工具清洁》课程的讲师",
            "要找本人以前在现场给新员工完整教过“工具清洁”与“仪器复核”课程的讲师",
            "本人以前在现场给新员工完整远程教过《工具清洁》",
            "本人以前给新员工完整教过《工具清洁》且每次不少于三小时",
            "本人以前给新员工完整教过《工具清洁》，但只是同事的经历"))
            check(HistoricalTeachingRequest.parse(invalid)==null,"unknown qualifications not swallowed "+invalid);
        String topic="古籍页码核对",q="要找本人以前在现场给带教师傅完整教过《"+topic+"》课程的讲师";
        String lead="《"+topic+"》已由我给带教师傅完整讲完。";
        check(admitted(q,lead+"我作为主讲教师在教室现场面授。"),"complete same-event personal onsite history admitted");
        check(!admitted(q,lead+"我作为主讲教师通过远程连线授课，只有学员在教室现场练习。"),"learner onsite not teacher onsite");
        check(!admitted(q,lead.replace("带教师傅","客户经理")+"我在现场面授。"),"wrong history audience");
        check(!admitted(q,lead.replace("我","同事")+"我仅协助准备材料。"),"colleague history not personal");
        check(!admitted(q,lead.replace("完整讲完","主讲")+"我在现场面授。"),"past lead not completed whole course");
        check(!admitted(q,lead+"我作为主讲教师通过远程连线授课。\n\n《园艺工具清洁》已由我给带教师傅完整讲完。我在现场面授。"),"different course onsite not borrowed");
        check(!admitted(q+"；必须每次现场完成三小时",lead+"我在现场面授。"),"new binding doesn't swallow unresolved followup");
        String remote=q.replace("在现场","通过远程连线");
        check(admitted(remote,lead+"我作为主讲教师通过远程连线授课。"),"remote requirement supported by teacher remote");
        check(!admitted(remote,lead+"我在现场面授。"),"onsite not remote");
        System.out.println("Historical request checks: "+checks);
    }
}
