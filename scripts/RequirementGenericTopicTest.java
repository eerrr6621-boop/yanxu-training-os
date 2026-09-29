package com.training;

import java.util.*;

/** Domain-independent course objects and source spans; no model or real data. */
public final class RequirementGenericTopicTest {
    private static int passed;
    private static Map<String,Object> assess(String query,String source) {
        Map<String,Object> candidate=new LinkedHashMap<>();
        candidate.put("_rule_relevant",true);candidate.put("professional_score",50.0);candidate.put("gaps",new ArrayList<String>());
        candidate.put("semantic",Map.of("similarity",.999,"evidence_complete",true));
        new RequirementCoverage(query).assess(candidate,source,true);return candidate;
    }
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
        passed++;
    }
    private static void expect(boolean expected,String query,String source,String message) {
        Map<String,Object> result=assess(query,source);
        check(Boolean.TRUE.equals(result.get("_admitted"))==expected,message+" "+Json.write(result));
    }
    public static void main(String[] args) {
        for(String topic:List.of("无人机电池保养","古籍页码核对","餐具消毒记录")) {
            String query="请教"+topic+"课程";
            String record="2025年本人主讲"+topic+"课程。";
            check(!new RequirementCoverage(query).required.isEmpty(),topic+" explicit object produces topic outside vocabulary");
            expect(true,query,record,topic+" clear course object matches own course");
            expect(true,"培训主题："+topic,"主讲课程："+topic,topic+" labelled subject supports content only");
            expect(false,query,"本人主讲无关课程，课前提到"+topic+"。",topic+" incidental name outside taught object not evidence");
            expect(false,query,"本人主讲无关课程。",topic+" high synthetic similarity cannot replace content");
            expect(false,query,"本人仅担任"+topic+"课程助教。",topic+" same title assistant is not teaching evidence");
            expect(false,query,"主讲课程："+topic+"\n未授课课程\n"+topic,topic+" title and explicit denial conflict");
            expect(false,query,"培训主题："+topic+"\n本人没有讲授"+topic+"。",topic+" later denial overrides label");
            String history="培训主题："+topic+"；必须有实际授课记录";
            expect(false,history,"培训主题："+topic,topic+" subject label cannot invent completed history");
            expect(true,history,record,topic+" clear personal completed course history");
            expect(false,history,"本人担任助教。\n2025年开展"+topic+"课堂。",topic+" previous assistant context stays restricted");
            expect(false,"不教"+topic+"，只讲无关课程",record,topic+" unfamiliar negative teaching query stays pending");
            expect(false,"请教"+topic+"和相关内容课程",record,topic+" unresolved AND part never disappears");
            Map<String,Object> unknown=assess("请教"+topic+"课程；还需要精确满足未定义要求",record);
            check(!Boolean.TRUE.equals(unknown.get("_admitted")) && !((List<?>)unknown.get("unparsed_requirements")).isEmpty(),topic+" preserves unresolved requirement source");
            String face="请教"+topic+"课程；必须面对面授课";
            expect(true,face,"本人主讲"+topic+"课程，采用面对面教学。",topic+" local course form evidence");
            expect(false,face,"本人主讲"+topic+"课程。\n本人主讲另一课程，采用面对面教学。",topic+" other course form cannot cross records");
            expect(false,face,"本人主讲"+topic+"课程；另主讲其他课程，采用面对面教学。",topic+" other course form cannot cross clauses");
            String bound="请教"+topic+"课程；授课不少于120分钟";
            expect(true,bound,record.replace("。","，实际授课120分钟。"),topic+" same course lower bound");
            expect(false,bound,record.replace("。","，实际授课30分钟。")+"\n2025年本人主讲无关课程，实际授课120分钟。",topic+" other course duration cannot transfer");
        }
        expect(true,"教冷链温度怎么记录","本人主讲冷链温度记录课程。","Grammar-only interrogative normalization");
        expect(true,"教冷链温度如何记录","本人主讲冷链温度怎么记录课程。","Equivalent question particles preserve object and action");
        expect(false,"教冷链温度怎么记录","本人主讲冷链温度测量课程。","Same object wrong action is not equivalent");
        expect(false,"教古籍页码怎么核对","本人主讲古籍页码编写课程。","Second domain action exchange rejected");
        String both="请教无人机电池保养和古籍页码核对课程";
        String a="2025年本人主讲无人机电池保养课程。",b="2025年本人主讲古籍页码核对课程。";
        check(new RequirementCoverage(both).required.size()==2,"Explicit AND requires two separate course units");
        expect(true,both,a+"\n"+b,"One candidate can evidence separate courses");
        expect(false,both,a,"First candidate cannot borrow second candidate course");
        expect(false,both,b,"Second candidate cannot borrow first candidate course");
        expect(false,both+"；必须面对面授课",a.replace("。","，采用面对面教学。")+"\n"+b.replace("。","，采用远程教学。"),"One requested course form cannot stand for both");
        expect(true,both+"；必须面对面授课",a.replace("。","，采用面对面教学。")+"\n"+b.replace("。","，采用面对面教学。"),"Each requested course supports the delivery form");
        String each=both+"；每个模块至少120分钟";
        expect(true,each,a.replace("。","，每场授课120分钟。")+"\n"+b.replace("。","，每场授课120分钟。"),"Each topic has its own duration");
        expect(false,each,a.replace("。","，每场授课30分钟。")+"\n"+b.replace("。","，每场授课240分钟。"),"Module durations cannot be pooled");
        String unicode="🧪培训主题：古籍页码核对和餐具消毒记录";
        Map<String,Object> result=assess(unicode,b+"\n本人主讲餐具消毒记录课程。");
        for(Object raw:(List<?>)result.get("requirement_coverage")) {
            Map<?,?> row=(Map<?,?>)raw;
            if(!(row.get("requirement_source_span") instanceof Map<?,?> span)) continue;
            int start=((Number)span.get("source_start")).intValue(),end=((Number)span.get("source_end")).intValue();
            check("unicode_code_point".equals(span.get("offset_unit")),"Requirement spans identify offset unit");
            check(unicode.substring(unicode.offsetByCodePoints(0,start),unicode.offsetByCodePoints(0,end)).equals(span.get("text")),"Requirement span points into exact original, including supplementary prefix");
        }
        System.out.println("Generic topics: "+passed+" checks passed");
    }
}
