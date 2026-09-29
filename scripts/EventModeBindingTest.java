package com.training;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Source-bound synthetic regression, not a model accuracy benchmark. */
public final class EventModeBindingTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static Map<String,Object> assess(String query,String source){
        Map<String,Object> result=new LinkedHashMap<>();result.put("professional_score",80.0);result.put("_rule_relevant",true);result.put("gaps",new ArrayList<String>());
        new RequirementCoverage(query).assess(result,source,false);return result;
    }
    static boolean admitted(String topic,String mode,String source){
        return Boolean.TRUE.equals(assess("培训主题："+topic+"；必须有本人实际授课记录；必须"+mode+"授课",source).get("_admitted"));
    }
    static String method(){return "方法先演示操作步骤，再指出动作需要调整，而不是只评价“挺好”。";}
    static void literalFields(String raw){
        String s=ProfessionalEvidence.normalized(raw);
        for(TeachingEvents.Event e:TeachingEvents.extract(s)){
            check(e.map().get("text").equals(s.substring(e.start,e.end)),"full original event");
            for(TeachingEvents.Field f:e.fields.values())check(f.value().equals(s.substring(f.start(),f.end())),"literal field "+f.label());
            for(Map<String,Object> r:e.relations){
                int start=s.offsetByCodePoints(0,((Number)r.get("source_start")).intValue());
                int end=s.offsetByCodePoints(0,((Number)r.get("source_end")).intValue());
                check(s.substring(start,end).equals(r.get("text")),"literal context relation");
            }
        }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("--inspect")){
            List<Object> values=(List<Object>)Json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
            System.out.println(Json.write(values.stream().map(v->TeachingEvents.extract((String)v).stream().map(e->e.map()).toList()).toList()));return;
        }
        for(String topic:List.of("客户服务","古籍页码核对","园艺工具清洁")) {
            String lead="本人曾主讲《"+topic+"》。";
            for(String self:List.of("本人","我","该教师"))for(String declaration:List.of("作为主讲教师在教室现场面授","在课堂面对面授课","现场面授","作为授课教师线下教学")){
                String source=lead+self+declaration+"。"+method();
                check(admitted(topic,"面对面",source),"self onsite bound "+self+declaration);
                check(!admitted(topic,"远程",source),"not remote "+self+declaration);
                check(TeachingEvents.extract(source).stream().anyMatch(TeachingEvents.Event::completedPersonalTeaching),"completed own event");
                check(TeachingEvents.scoringSections(source).isEmpty(),"no full-event quote reintroduces rejected utterance");
                literalFields(source);
            }
            String remote=lead+"本人作为主讲教师通过远程连线授课，只有学员在教室现场练习。"+method();
            check(admitted(topic,"远程",remote),"remote teacher correctly bound");
            check(!admitted(topic,"面对面",remote),"learner presence not onsite teacher");literalFields(remote);
            String positive=lead+"本人作为主讲教师在教室现场面授。"+method();
            for(String tail:List.of("本人实际上没有主讲上述课程。","实际由同事主讲。","本人仅担任助教。","上述只是模板，不代表本人经历。","本人通过远程连线授课。","这个记录待核实。"))
                check(!admitted(topic,"面对面",positive+tail),"later denial/conflict/unknown not erased "+tail);
            for(String bad:List.of("本人计划现场面授","本人可以现场面授","本人协助教师现场授课","同事作为主讲教师在教室现场面授","学员现场练习","“本人现场面授”","本人并非现场面授","本人在另一门课程现场面授"))
                check(!admitted(topic,"面对面",lead+bad+"。"+method()),"nonpersonal/nonactual/other event mode "+bad);
            check(!admitted(topic,"面对面",lead+"\n\n本人在教室现场面授。"+method()),"no cross-paragraph binding");
            check(!admitted(topic,"面对面","本人作为主讲教师在教室现场面授。"+lead+method()),"preface not silently assigned");
            check(!admitted(topic,"面对面","本人可以主讲《"+topic+"》。本人在教室现场面授。"+method()),"mode cannot create historical completion");
            check(!admitted(topic,"面对面","同事曾主讲《"+topic+"》。本人在教室现场面授。"+method()),"mode cannot borrow other actor teaching");
            check(!admitted(topic,"面对面",lead+"本人通过远程连线授课。本人曾主讲《其他课程》。本人现场面授。"),"other course onsite not borrowed");
            check(!admitted(topic,"远程",lead+"本人现场面授。本人曾主讲《其他课程》。本人远程授课。"),"other course remote not borrowed");
            for(String heading:List.of("计划课程","未授课课程","助教经历","团队授课经历"))
                check(!admitted(topic,"面对面",heading+"\n"+positive),"excluded section "+heading);
            check(admitted(topic,"面对面",positive.replace("。","。\r\n")),"single soft line wraps retain event");
            check(!Boolean.TRUE.equals(assess("培训主题：挺好",positive).get("_admitted")),"rejected quote not a supplied topic");
        }
        for(String mode:List.of("本人现场面授","我通过远程连线授课","该教师线上实时教学")){
            InstructorMode.SourceDeclaration declaration=InstructorMode.sourceDeclaration(mode);
            check(declaration!=null,"complete bounded declaration");
            check(declaration.actor().equals(mode.substring(declaration.actorStart(),declaration.actorEnd())),"literal actor");
            check(declaration.mode().equals(mode.substring(declaration.modeStart(),declaration.modeEnd())),"literal mode");
        }
        System.out.println("Event mode binding checks: "+checks);
    }
}
