package com.training;

import java.util.*;

/** Future recipients are not past-teaching qualifications. Pure synthetic Java. */
public final class AudienceIntentTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static String event(String topic,String audience,boolean completed) {
        return "课程名称："+topic+"\n授课人：本人\n授课角色：独立主讲"+
            (completed?"\n完成状态：已完成":"")+(audience==null?"":"\n培训对象："+audience);
    }
    static Map<String,Object> assess(String query,String source) {
        Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",0.0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage(query).assess(c,source,false);return c;
    }
    static boolean admitted(Map<String,Object> c){return Boolean.TRUE.equals(c.get("_admitted"));}
    @SuppressWarnings("unchecked")
    static void contextOnly(String query,String source) {
        Map<String,Object> c=assess(query,source);
        check(admitted(c),"plain future audience cannot independently reject a supported topic");
        check(!new RequirementConstraints(query).teachingHistory,"future recipient does not assert past teaching");
        List<Map<String,Object>> contexts=(List<Map<String,Object>>)c.get("audience_context");
        check(contexts!=null&&!contexts.isEmpty(),"future audience kept in separate audit");
        check(contexts.stream().allMatch(x->Boolean.FALSE.equals(x.get("hard_requirement"))),"audit explicitly non-hard");
        check(contexts.stream().noneMatch(x->x.containsKey("status")),"context cannot masquerade as a hard-check status row");
        check(((List<Map<String,Object>>)c.get("requirement_coverage")).stream().noneMatch(x->Objects.toString(x.get("criterion")).startsWith("授课对象：")),"no future audience in checks.every or failed_count input");
        check(c.get("score_source").equals("unscored_evidence_fallback"),"no fabricated model score");
        check(c.get("gaps").toString().contains("对象适配待确认"),"user wording does not certify suitability or past audience");
        for(Map<String,Object> a:contexts) {
            Map<String,Object> span=(Map<String,Object>)a.get("requirement_source_span");String normalized=(String)a.get("requirement_matching_text");
            int start=normalized.offsetByCodePoints(0,((Number)span.get("source_start")).intValue());
            int end=normalized.offsetByCodePoints(0,((Number)span.get("source_end")).intValue());
            check(normalized.substring(start,end).equals(span.get("text")),"audience source range is exact and labelled normalized");
        }
    }
    public static void main(String[] args) {
        for(String[] pair:List.of(new String[]{"厅堂客户动线讲解","银行厅堂服务人员"},
            new String[]{"设备标签复核","设备巡检员"},new String[]{"餐桌过敏原沟通","餐厅值班员"},
            new String[]{"实验样品接收","实验室样品接收员"})) {
            String topic=pair[0],audience=pair[1],base="培训主题：《"+topic+"》",future=base+"\n培训对象：新一批"+audience;
            for(String target:Arrays.asList(null,"其他岗位人员")) {
                contextOnly(future,event(topic,target,true));
                contextOnly(future.replace("培训对象：","参训对象："),event(topic,target,true));
                contextOnly("这次需要给新一批"+audience+"讲《"+topic+"》课程",event(topic,target,true));
            }
            String history=future+"\n请找以前给这类学员讲完这门课的老师";
            check(admitted(assess(history,event(topic,audience,true))),"bound same-audience completed event passes");
            check(!admitted(assess(history,event(topic,null,true))),"explicit audience history cannot use missing audience");
            check(!admitted(assess(history,event(topic,"其他岗位人员",true))),"explicit audience history cannot use a different audience");
            check(!admitted(assess(history,event(topic,audience,false))),"explicit audience history cannot use uncompleted course statement");
            check(admitted(assess(future+"\n必须有本人实际授课记录",event(topic,"其他岗位人员",true))),"general course history must not silently bind the future recipient");
            String mandatory=future+"\n课程必须适配"+audience;
            check(admitted(assess(mandatory,event(topic,audience,false))),"same-course declared target can support fit without past completion");
            check(!admitted(assess(mandatory,event(topic,null,true))),"mandatory fit remains hard when unspecified");
            check(!admitted(assess(mandatory,event(topic,"其他岗位人员",true))),"mandatory fit cannot use a different source target");
            for(String suffix:List.of("且必须满足尚未定义条件","但只接受核验合格者","并且不得涉及尚未定义限制"))
                check(!admitted(assess(mandatory+suffix,event(topic,audience,true))),"a partial suitability match cannot consume an unknown suffix");
            check(!admitted(assess(future+"\n课程必须满足尚未定义条件",event(topic,audience,true))),"subject-first unknown modality remains review");
            check(!admitted(assess(future+"\n必须具有讲师资格证",event(topic,audience,true))),"topic/adaptation never verifies qualifications");
            check(!admitted(assess(base+"\n培训对象：只接受"+audience,event(topic,audience,true))),"exclusive field is not silently downgraded to context");
            check(!admitted(assess(base+"\n培训对象："+audience+"（不包括其他人员）",event(topic,audience,true))),"field exclusion remains review");
            check(!admitted(assess(history,event(topic,audience,true).replace("独立主讲","助教"))),"role restriction unchanged");
        }
        String topic="样品封口编号",base="培训主题：《"+topic+"》";
        for(String audience:List.of("老年学员","行政人员","零基础学员"))contextOnly(base+"\n培训对象："+audience,event(topic,"其他岗位人员",true));
        String literal="课程必须适配编号";
        check(admitted(assess("培训主题：《"+literal+"》",event(literal,null,true))),"modality inside literal course title is not a requirement predicate");
        String explicit=base+"\n培训对象：新一批样品接收员\n必须有给样品接收员讲过此课的经历";
        check(admitted(assess(explicit,event(topic,"样品接收员",true))),"literal named audience historical contract retained");
        check(!admitted(assess(explicit,event(topic,"其他岗位人员",true))),"literal historical audience cannot be downgraded");
        System.out.println("Audience intent: "+checks+" assertions passed");
    }
}
