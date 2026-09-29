package com.training;

import java.util.*;

/** Cross-domain source-field binding checks, not a model evaluation or real resumes. */
public final class TeachingEventBindingTest {
    static int checks;static final List<String> failures=new ArrayList<>();
    static Map<String,Object> assess(String query,String source) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("_rule_relevant",true);
        result.put("gaps",new ArrayList<String>());result.put("professional_score",40.0);
        new RequirementCoverage(query).assess(result,source,false);return result;
    }
    static void check(boolean value,String label) {checks++;if(!value)failures.add(label);}
    static String query(String topic,String audience) {return "培训主题："+topic+"\n培训对象："+audience+"\n请找以前给这类学员讲完这门课的老师";}
    static String block(String topic,String audience) {
        return "实际授课记录\n课程名称："+topic+"\n授课人：本人\n授课角色：主讲\n完成状态：已完成\n培训对象："+audience+"\n授课形式：线下";
    }
    public static void main(String[] args) {
        for(String[] values:List.of(new String[]{"窑炉巡检","车间班组长"},new String[]{"图像标注","数据审核员"})) {
            String topic=values[0],audience=values[1],q=query(topic,audience),good=block(topic,audience);
            check(Boolean.TRUE.equals(assess(q,good).get("_admitted")),"same structured event qualifies "+topic);
            for(String[] change:List.of(new String[]{audience,"其他岗位学员"},new String[]{"授课人：本人","授课人：同事"},
                new String[]{"授课角色：主讲","授课角色：助教"},new String[]{"完成状态：已完成","完成状态：计划中"},
                new String[]{"授课人：本人\n",""},new String[]{"完成状态：已完成\n",""}))
                check(!Boolean.TRUE.equals(assess(q,good.replace(change[0],change[1])).get("_admitted")),"missing/wrong independent event field "+topic+" "+change[0]);
            check(!Boolean.TRUE.equals(assess(q,block(topic,"其他岗位学员")+"\n\n"+block("其他独立课程",audience)).get("_admitted")),"audience cannot cross event boundary "+topic);
            check(Boolean.TRUE.equals(assess(q,block("其他独立课程","其他岗位学员")+"\n\n"+good).get("_admitted")),"independent positive survives another event "+topic);
            check(!Boolean.TRUE.equals(assess(q,good+"\n培训对象：其他岗位学员").get("_admitted")),"conflicting duplicate field remains pending "+topic);
            check(!Boolean.TRUE.equals(assess(q,good+"\n备注：仅为团队业绩，不能视为本人主讲").get("_admitted")),"qualification context not discarded "+topic);
            check(!Boolean.TRUE.equals(assess(q,"以下表格由同事填写，字段本人指同事。\n"+good).get("_admitted")),"outside preface cannot redefine actor unnoticed "+topic);
            check(!Boolean.TRUE.equals(assess(q,good+"\n\n补充说明：授课人为同事，本人仅汇总。").get("_admitted")),"blank-separated correction retained "+topic);
            Map<String,Object> outcome=assess(q,good);
            check(outcome.get("model_score")==null,"structured support never invents model score "+topic);
            check(outcome.get("teaching_events") instanceof List<?> list&&!list.isEmpty(),"source-backed event metadata retained "+topic);
            check(Boolean.TRUE.equals(assess(q,good.replace("\n","\r\n")).get("_admitted")),"Windows field lines stay one event "+topic);
            String mixed=block(topic,audience).replace("线下","线上")+"\n\n"+block(topic,"其他岗位学员");
            check(!Boolean.TRUE.equals(assess(q+"\n必须线下授课",mixed).get("_admitted")),"mode and audience cannot borrow another same-title event "+topic);
            check(Boolean.TRUE.equals(assess(q+"\n必须线下授课",good).get("_admitted")),"same event has both requested audience and mode "+topic);
            String counted=good+"\n已完成场次：3场\n每场时长：2小时";
            check(Boolean.TRUE.equals(assess(q+"\n至少3场，每场至少90分钟",counted).get("_admitted")),"count and each-duration share one event set "+topic);
            check(!Boolean.TRUE.equals(assess(q+"\n至少3场，每场至少90分钟",counted.replace("每场时长：2小时","授课时长：6小时")).get("_admitted")),"total duration cannot prove each-duration "+topic);
            for(Object raw:(List<?>)outcome.get("teaching_events")) {
                Map<?,?> event=(Map<?,?>)raw;String source=ProfessionalEvidence.normalized(good);
                int start=source.offsetByCodePoints(0,((Number)event.get("source_start")).intValue()),end=source.offsetByCodePoints(0,((Number)event.get("source_end")).intValue());
                check(event.get("text").equals(source.substring(start,end)),"event text bound to actual normalized source "+topic);
                for(Object item:((Map<?,?>)event.get("fields")).values()) {
                    Map<?,?> field=(Map<?,?>)item;
                    int a=source.offsetByCodePoints(0,((Number)field.get("source_start")).intValue()),b=source.offsetByCodePoints(0,((Number)field.get("source_end")).intValue());
                    check(field.get("text").equals(source.substring(a,b))&&field.get("text").equals(field.get("value")),"field literal and original position retained "+field.get("label"));
                }
            }
        }
        System.out.println(Json.write(Map.of("suite","teaching-event-field-binding","checks",checks,"failures",failures,"model_executed",false,"real_resumes_used",false)));
        if(!failures.isEmpty())throw new AssertionError(failures.size()+" failed event binding checks");
    }
}
