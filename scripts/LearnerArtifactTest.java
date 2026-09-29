package com.training;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Source-scope/protocol checks, not a model-accuracy benchmark. */
public final class LearnerArtifactTest {
    static int checks;
    static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    static Map<String,Object> inspect(String raw){
        String source=ProfessionalEvidence.normalized(raw);
        Map<String,Object> out=new LinkedHashMap<>();out.put("text",source);
        out.put("omitted",LearnerArtifactContext.spans(source).stream().map(s->List.of(source.codePointCount(0,s[0]),source.codePointCount(0,s[1]))).toList());
        out.put("negative",ProfessionalEvidence.negative(source));out.put("allowed",ProfessionalEvidence.allowed(source));
        out.put("positive",ProfessionalEvidence.units(source));out.put("records",ProfessionalEvidence.records(source));
        out.put("events",TeachingEvents.extract(source).stream().map(e->e.map()).toList());
        out.put("scoring_sections",TeachingEvents.scoringSections(source));return out;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("--inspect")){
            List<Object> values=(List<Object>)Json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
            System.out.println(Json.write(values.stream().map(v->inspect((String)v)).toList()));return;
        }
        for(String topic:List.of("档案目录核对","设备编号登记","🌿标本标签核对")){
            String lead="我曾给资料管理员完整讲授《"+topic+"》，授课方式是到场面授。";
            String artifact="整期结束后，学员带走的是空白笔记模板，不是客户信息";
            String source=ProfessionalEvidence.normalized(lead+artifact+"。");
            check(LearnerArtifactContext.spans(source).size()==1,"whole artifact comparison");
            check(!ProfessionalEvidence.negative(source),"not teaching denial");
            check(ProfessionalEvidence.allowed(source),"actual teaching retained");
            check(ProfessionalEvidence.positive(source).contains(topic),"literal course retained");
            check(!ProfessionalEvidence.positive(source).contains("笔记模板"),"artifact not proof");
            check(ProfessionalEvidence.records(source).stream().allMatch(r->Boolean.FALSE.equals(r.get("verified"))),"no invented verification");
            List<TeachingEvents.Event> events=TeachingEvents.extract(source);
            check(events.size()==1,"no artifact event");TeachingEvents.Event event=events.get(0);
            check(event.completedPersonalTeaching(),"own original event recovers");
            check(event.value("course").equals(topic),"course unchanged");
            check(event.value("audience").equals("资料管理员"),"audience unchanged");
            check(event.relations.stream().anyMatch(r->"learner_artifact_context_only".equals(r.get("relation"))),"context only");
            check(TeachingEvents.scoringSections(source).isEmpty(),"no full-event artifact embedding");
            for(String tail:List.of("本人没有讲授上述课程。","实际由同事主讲。","本人仅担任助教。","以上只是模板，不代表本人经历。")){
                String limited=source+tail;
                check(!ProfessionalEvidence.allowed(limited),"later qualification applies");
                check(ProfessionalEvidence.positive(limited).isEmpty(),"no laundered quote");
                check(TeachingEvents.extract(limited).stream().noneMatch(TeachingEvents.Event::completedPersonalTeaching),"no qualified event");
            }
            for(String other:List.of("同事曾给资料管理员完整讲授《"+topic+"》。","我计划给资料管理员完整讲授《"+topic+"》。","我参加《"+topic+"》课程。", ""))
                check(LearnerArtifactContext.spans(other+artifact+"。").isEmpty(),"needs prior own teaching");
            check(LearnerArtifactContext.spans(lead+"\n\n"+artifact+"。").isEmpty(),"no cross-paragraph borrowing");
            check(LearnerArtifactContext.spans(artifact+"。"+lead).isEmpty(),"no later antecedent");
        }
        System.out.println("Learner artifact checks: "+checks);
    }
}
