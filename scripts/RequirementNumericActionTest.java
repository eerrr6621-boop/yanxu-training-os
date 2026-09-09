package com.training;

import java.util.*;

/** Longest comparator tokens and teacher/learner actions; synthetic only. */
public final class RequirementNumericActionTest {
    private static int passed;
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
        passed++;
    }
    private static boolean admitted(String query,String source) {
        Map<String,Object> candidate=new LinkedHashMap<>();
        candidate.put("_rule_relevant",true);candidate.put("gaps",new ArrayList<String>());candidate.put("professional_score",50.0);
        new RequirementCoverage(query).assess(candidate,source,false);
        return Boolean.TRUE.equals(candidate.get("_admitted"));
    }
    public static void main(String[] args) {
        for(String topic:List.of("陶瓷修复","冷链质检","声学测量")) {
            String source="2025年本人主讲"+topic+"，实际授课120分钟。";
            for(String operator:List.of("至少","不少于","不低于",">=","≥")) {
                String query="主题为"+topic+"；授课"+operator+"120分钟";
                RequirementConstraints constraints=new RequirementConstraints(query);
                check(!constraints.unknownExclusion,topic+operator+" is a single lower bound");
                check(constraints.comparisons.size()==1 && constraints.minimums.size()==1,topic+operator+" no inner operator reparse");
                check(admitted(query,source),topic+operator+" exact boundary supported");
                check(!admitted(query,source.replace("120分钟","119分钟")),topic+operator+" below boundary stays pending");
            }
            check(admitted("主题为"+topic+"；授课不少于12小时",source.replace("120分钟","12小时")),topic+" integer hours lower bound");
            check(!admitted("主题为"+topic+"；授课不少于12小时",source.replace("120分钟","11小时")),topic+" hours lower bound still enforced");
            for(String bound:List.of("少于120分钟","低于120分钟","不超过120分钟","不高于120分钟","不不少于120分钟","未达到120分钟","不少于1.5小时","不少于三点五小时","不少于3至5小时","不少于-3小时","不少于3天")) {
                RequirementConstraints constraints=new RequirementConstraints("主题为"+topic+"；授课"+bound);
                check(constraints.unknownExclusion,topic+bound+" unsupported operator/value not silently removed");
                check(!admitted("主题为"+topic+"；授课"+bound,source),topic+bound+" pending independent of course hit");
            }
            RequirementConstraints two=new RequirementConstraints("主题为"+topic+"；授课不少于120分钟且少于180分钟");
            check(two.comparisons.size()==2 && two.minimums.size()==1 && two.unknownExclusion,topic+" one supported bound cannot hide another comparator");
            check(two.comparisons.get(0).end()<=two.comparisons.get(1).start(),topic+" comparator source spans do not overlap");
            String teachingQuery="主题为"+topic+"；需要本人在现场完成过教学";
            RequirementConstraints teaching=new RequirementConstraints(teachingQuery);
            check(teaching.teachingHistory && !teaching.features.containsKey("现场完成练习"),topic+" teacher completion is history, not learner exercise");
            check(admitted(teachingQuery,source.replace("。","，采用面对面教学。")),topic+" clear onsite completed history without invented practice");
            check(!admitted(teachingQuery,source),topic+" onsite history request still requires onsite evidence");
            check(!admitted(teachingQuery,"主讲课程："+topic+"。"),topic+" course label is not completed teaching");
            String learnerQuery="主题为"+topic+"；要求学员现场完成练习";
            check(new RequirementConstraints(learnerQuery).features.containsKey("现场完成练习"),topic+" explicit learner task remains required");
            check(admitted(learnerQuery,"本人主讲"+topic+"课程，带领学员现场完成练习。"),topic+" learner practice supported");
            check(!admitted(learnerQuery,"本人主讲"+topic+"课程，本人现场完成练习。"),topic+" instructor practice cannot replace learner task");
            check(!admitted(learnerQuery,"本人主讲"+topic+"课程，在现场完成教学。"),topic+" teaching completion cannot replace practice");
            check(!admitted(learnerQuery,"本人主讲"+topic+"课程；另有无关课程，学员现场完成练习。"),topic+" other course learner action cannot transfer");
            check(!admitted("主题为"+topic+"；必须现场完成练习",source),topic+" unbound action is reviewable, not dropped");
            check(!admitted("主题为"+topic+"；要求学员现场完成作业","本人主讲"+topic+"课程，学员现场完成练习。"),topic+" unknown task object cannot become generic practice");
            check(!admitted("主题为"+topic+"；要求学员现场完成练习和作业","本人主讲"+topic+"课程，学员现场完成练习。"),topic+" unsupported combined tasks stay reviewable");
        }
        System.out.println("Numeric/action scope: "+passed+" checks passed");
    }
}
