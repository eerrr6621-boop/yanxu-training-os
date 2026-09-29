package com.training;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;

/** Authored grammar and source-binding regressions, not a blind model test. */
public final class PassiveRecipientTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static Map<String,Object> assess(String topic,String source){
        var result=new LinkedHashMap<String,Object>();result.put("professional_score",80.0);result.put("_rule_relevant",true);result.put("gaps",new ArrayList<String>());
        new RequirementCoverage("培训主题："+topic+"；必须有本人实际授课记录；必须现场面授").assess(result,source,false);return result;
    }
    static String context(){return "方法先由带教人做一遍给新人看，再请新人按顺序重做，最后指出哪一个动作需要调整，而不是只评价“挺好”。我用登记一条模拟工单来演示整个循环，学员随后练习怎样把反馈说具体。厂部只负责召集人员和借用教室，实际讲解与收尾讨论由我完成；课堂结束后留下的是教学步骤卡，而非师徒业务考核记录。";}
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("--inspect")){
            List<Object> input=(List<Object>)Json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
            System.out.println(Json.write(input.stream().map(x->TeachingEvents.extract((String)x).stream().map(e->e.map()).toList()).toList()));return;
        }
        for(String topic:List.of("客户服务","古籍页码核对","园艺工具清洁")){
            for(String prep:List.of("给","为","面向"))for(String learner:List.of("带教师傅","新员工","支行经理","未成年学员"))for(String modifier:List.of("完整","亲自","独立")){
                String clause="《"+topic+"》已由我"+prep+learner+modifier+"讲完";
                var f=NamedPassiveTeaching.parse(clause);
                check(f!=null,"full recipient clause "+clause);
                check(f.audience().equals(learner),"modifiers are not audience");
                check(f.audience().equals(clause.substring(f.audienceStart(),f.audienceEnd())),"literal recipient bounds");
                check(f.history()&&f.finished(),"explicit completion");
                check(f.independent()==modifier.equals("独立"),"independence only when stated");
            }
            for(String modifier:List.of("完整","亲自","独立")){
                String lead="《"+topic+"》已由我给带教师傅"+modifier+"讲完";
                String onsite=lead+"，我作为主讲教师在教室现场面授。"+context();
                String remote=lead+"，我作为主讲教师通过远程连线授课，只有学员在教室现场练习。"+context();
                check(Boolean.TRUE.equals(assess(topic,onsite).get("_admitted")),"whole rich paragraph supports onsite");
                check(!Boolean.TRUE.equals(assess(topic,remote).get("_admitted")),"learner site not teacher site");
                List<TeachingEvents.Event> events=TeachingEvents.extract(onsite);
                check(events.size()==1&&events.get(0).completedPersonalTeaching(),"one completed own event");
                var event=events.get(0);String source=ProfessionalEvidence.normalized(onsite);
                check(event.value("audience").equals("带教师傅"),"recipient preserved");
                check(event.value("mode").equals("现场面授"),"same event mode");
                for(var f:event.fields.values())check(source.substring(f.start(),f.end()).equals(f.value()),"literal field "+f.label());
                for(var r:event.relations){
                    int start=source.offsetByCodePoints(0,((Number)r.get("source_start")).intValue()),end=source.offsetByCodePoints(0,((Number)r.get("source_end")).intValue());
                    check(start>=event.start&&end<=event.end,"relation inside preserved event");
                    check(source.substring(start,end).equals(r.get("text")),"literal supporting relation");
                }
                check(source.substring(event.start,event.end).contains("而非师徒业务考核记录"),"artifact qualification retained");
                check(TeachingEvents.scoringSections(onsite).isEmpty(),"rejected feedback not positive full-event quote");
                for(String tail:List.of("本人没有实际授课。","上述只是同事的履历。","以上为模板，不代表本人经历。","本人只负责助教工作。","授课记录待核实。"))
                    check(!Boolean.TRUE.equals(assess(topic,onsite+tail).get("_admitted")),"later correction remains "+tail);
            }
            for(String recipient:List.of("经理安排同事","新员工并非学员","经理审核后","学员请同事","学员未曾参加","学员计划参加","学员由他人"))
                check(NamedPassiveTeaching.parse("《"+topic+"》已由本人给"+recipient+"完整讲完")==null,"intervening actions/qualifiers not recipient "+recipient);
            for(String clause:List.of("《"+topic+"》已由同事给新员工讲完","《"+topic+"》拟由我给新员工讲完","《"+topic+"》由我给新员工完成备课","《"+topic+"》由我给新员工审核讲义"))
                check(NamedPassiveTeaching.parse(clause)==null,"not a personal completed lesson "+clause);
            check(!NamedPassiveTeaching.parse("《"+topic+"》由我给新员工主讲").history(),"undated assignment not past teaching");
            check(!NamedPassiveTeaching.parse("2099年《"+topic+"》已由我给新员工讲完",LocalDate.of(2026,9,9)).history(),"future date not completed history");
            String qualified="《"+topic+"》已由我给带教师傅讲完。我现场面授。"+context();
            for(String change:List.of("厂部只负责主讲课程和借用教室","实际讲解与收尾讨论由同事完成","课堂结束后留下的是他人的授课记录","而非本人授课记录")){
                String source=qualified.replace(change.startsWith("厂部")?"厂部只负责召集人员和借用教室":change.startsWith("实际")?"实际讲解与收尾讨论由我完成":change.startsWith("课堂")?"课堂结束后留下的是教学步骤卡":"而非师徒业务考核记录",change);
                check(!Boolean.TRUE.equals(assess(topic,source).get("_admitted")),"support cannot swallow fact conflict "+change);
            }
            check(!Boolean.TRUE.equals(assess(topic,qualified.replace("，而非","。而非")).get("_admitted")),"artifact limitation must stay attached");
            check(!Boolean.TRUE.equals(assess(topic,qualified.replace("已由我给带教师傅讲完","由我给带教师傅主讲")).get("_admitted")),"artifact and discussion do not prove course completion");
        }
        System.out.println("Passive recipient checks: "+checks);
    }
}
