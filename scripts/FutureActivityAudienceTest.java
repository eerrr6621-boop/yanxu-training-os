package com.training;

import java.util.*;

/** Closed future activity/benefit recipients are context, not past credentials. */
public final class FutureActivityAudienceTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static String source(String audience) {
        return "课程名称：金融防骗反诈\n授课人：本人\n授课角色：独立主讲\n完成状态：已完成"+
            (audience==null?"":"\n培训对象："+audience);
    }
    static Map<String,Object> assess(String query,String text) {
        Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",0.0);
        c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage(query).assess(c,text,false);return c;
    }
    @SuppressWarnings("unchecked")
    static void context(String clause,String audience) {
        String query="培训主题：金融防骗反诈\n补充要求："+clause;
        Map<String,Object> c=assess(query,source(null));
        check(Boolean.TRUE.equals(c.get("_admitted")),"future recipient wrongly rejected: "+clause);
        List<Map<String,Object>> contexts=(List<Map<String,Object>>)c.get("audience_context");
        check(contexts.stream().anyMatch(x->audience.equals(x.get("requested_audience"))),"recipient retained: "+clause);
        for(Map<String,Object> item:contexts) {
            check(Boolean.FALSE.equals(item.get("hard_requirement")),"context is not hard");
            check(!item.containsKey("status"),"context is not a coverage check");
            Map<String,Object> span=(Map<String,Object>)item.get("requirement_source_span");
            String text=(String)item.get("requirement_matching_text");
            int start=text.offsetByCodePoints(0,((Number)span.get("source_start")).intValue());
            int end=text.offsetByCodePoints(0,((Number)span.get("source_end")).intValue());
            check(text.substring(start,end).equals(span.get("text")),"exact source offsets");
        }
        check(c.get("gaps").toString().contains("对象适配待确认"),"visible suitability reminder");
        check(!new RequirementConstraints(query).teachingHistory,"future clause did not invent history");
    }
    public static void main(String[] args) {
        for(String audience:List.of("社区银龄客户","零基础学员","行政人员","设备巡检员")) {
            for(String verb:List.of("要加入","计划增加","准备安排","将开展"))
                context(audience+"活动"+verb+"金融安全宣讲",audience);
            for(String verb:List.of("识别骗局","掌握金融安全知识","了解风险信号"))
                context("希望帮助"+audience+verb,audience);
        }
        context("希望帮助长辈识别骗局、保护自己的养老积蓄","长辈");
        String base="培训主题：金融防骗反诈\n补充要求：社区银龄客户活动要加入金融安全宣讲";
        for(String hard:List.of("课程必须适配老年学员","必须有给老年学员讲过此课的经历",
                "必须具有讲师资格证","必须完成过三场授课"))
            check(!Boolean.TRUE.equals(assess(base+"\n"+hard,source(null)).get("_admitted")),"separate hard condition remains: "+hard);
        check(!Boolean.TRUE.equals(assess(base,source(null).replace("独立主讲","助教")).get("_admitted")),"assistant is not lecturer");
        for(String unsafe:List.of("希望帮助长辈识别骗局且必须具备认证资格",
                "社区银龄客户活动要加入金融安全宣讲但只接受核验合格者",
                "社区银龄客户活动要加入老师过去讲过的内容")) {
            AudienceIntent intent=new AudienceIntent("补充要求："+unsafe,RequirementTopic.parse("补充要求："+unsafe));
            check(intent.contexts.isEmpty(),"do not partially consume a qualified clause");
        }
        String literal="社区银龄客户活动要加入风险辨识";
        AudienceIntent title=new AudienceIntent("培训主题：《"+literal+"》",RequirementTopic.parse("培训主题：《"+literal+"》"));
        check(title.contexts.isEmpty(),"words in course title are data");
        System.out.println("Future activity audience: "+checks+" assertions passed");
    }
}
