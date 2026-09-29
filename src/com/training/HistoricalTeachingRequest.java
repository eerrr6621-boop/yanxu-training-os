package com.training;

import java.util.*;
import java.util.regex.*;

/** Source-bound request grammar; course content is an object, never a predicate.
 * This recognises requirements only, and provides no evidence about a teacher. */
final class HistoricalTeachingRequest {
    private static final String MOD="(?:(?:以前|过去|曾经|已经|实际|亲自|独立|完整|全程|曾|已)){0,5}";
    private static final String MODE="(?:在教室现场|在现场|面对面|线下|现场|通过远程连线|远程|线上)";
    private static final Pattern REQUEST=Pattern.compile(
        "^(?:(?:请|希望|需要|要求|必须|须|需|应|要找|寻找|找|推荐)){0,5}"+
        "(?:讲师本人|老师本人|教师本人|本人)(?<before>"+MOD+")(?<mode1>"+MODE+")?"+
        "(?:(?:给|为|面向)(?<audience>[^《》“”「」\\\"\\r\\n，,。；;！？!?：:]{1,40}?))?"+
        "(?<after>"+MOD+")(?<mode2>"+MODE+")?"+
        "(?<action>教完过|讲完过|教完了|讲完了|完成过|完成了|讲授过|教授过|主讲过|教过|讲过)"+
        "(?<open>[《“「\\\"])(?<topic>[^《》“”「」\\\"\\r\\n]{2,80})(?<close>[》”」\\\"])"+
        "(?:方法|课程|主题|教学|授课)?(?:的(?:讲师|老师|教师))?$");
    // The final relative head is the requested teacher, not an unnamed actor
    // found elsewhere in the paragraph. A past teaching verb is mandatory.
    private static final String RELATIVE_BODY=
        "^(?:(?:请推荐|希望找到|希望推荐|请找|需要|要求|要找|寻找|推荐|找)){1,3}"+
        "(?:讲师本人|老师本人|教师本人|本人)?(?<before>"+MOD+")(?<mode1>"+MODE+")?"+
        "(?:(?:给|为|面向)(?<audience>[^《》“”「」\\\"\\r\\n，,。；;！？!?：:]{1,40}?))?"+
        "(?<after>"+MOD+")(?<mode2>"+MODE+")?"+
        "(?<action>教完过|讲完过|教完了|讲完了|讲授过|教授过|主讲过|教过|讲过)";
    private static final Pattern RELATIVE_QUOTED=Pattern.compile(RELATIVE_BODY+
        "(?<open>[《“「\\\"])(?<topic>[^《》“”「」\\\"\\r\\n]{2,80})(?<close>[》”」\\\"])"+
        "(?:方法|课程|主题|教学|授课)?的(?:讲师|老师|教师)$");
    private static final Pattern RELATIVE_PLAIN=Pattern.compile(RELATIVE_BODY+
        "(?<topic>[^《》“”「」\\\"\\r\\n，,。；;！？!?：:]{2,80}?)的(?:讲师|老师|教师)$");
    record Part(int start,int end,String text) {}
    record Fact(Part topic,Part audience,Part mode,boolean completed,boolean independent) {}
    static Fact parse(String source) {
        Matcher m=REQUEST.matcher(source);boolean plain=false;
        if(!m.matches()){
            m=RELATIVE_QUOTED.matcher(source);
            if(!m.matches()){m=RELATIVE_PLAIN.matcher(source);if(!m.matches())return null;plain=true;}
        }
        if(plain){
            String topic=m.group("topic");
            // Without delimiters these may be conditions or multiple objects,
            // not part of a course name. Keep ambiguous wording unconsumed.
            if(topic.isBlank()||topic.matches("(?s).*(?:并且|而且|以及|或者|要求|必须|至少|每次|每场|不少于|不超过|本人|讲师|老师|教师|同事|助教|授课对象|授课方式|计划|尚未|没有|不能|不得|不需要|无需|[()（）<>≥≤]).*"))return null;
        }else{
            char open=m.group("open").charAt(0),close=m.group("close").charAt(0);
            if(closing(open)!=close)return null;
        }
        String receiver=m.group("audience");
        // An arbitrary learner name is allowed, but not another predicate or
        // a hidden qualification smuggled into that name by regex backtracking.
        if(receiver!=null&&(receiver.isBlank()||receiver.matches("(?s).*(?:邀请|安排|委托|联络|协调|协助|审核|审批|请|让|由|拟|计划|准备|尚未|未曾|并非|不是|而非|没有|否认|主讲|讲授|授课|教过|讲完|完成|作为|担任|代为|必须|要求|需要|希望|仅|只|或者|如果|至少|每场|每次|[()（）]).*")))return null;
        if(receiver!=null&&receiver.matches("(?s).*(?:不|未|不够|未必|基本|大致|可能|部分|仅|只)$"))return null;
        if(m.group("mode1")!=null&&m.group("mode2")!=null)return null;
        String modifier=m.group("before")+m.group("after");
        return new Fact(part(m,"topic"),receiver==null?null:part(m,"audience"),
            m.group("mode1")!=null?part(m,"mode1"):m.group("mode2")!=null?part(m,"mode2"):null,
            modifier.contains("完整")||modifier.contains("全程")||m.group("action").matches(".*(?:完|完成).*"),modifier.contains("独立"));
    }
    private static Part part(Matcher m,String name){return new Part(m.start(name),m.end(name),m.group(name));}
    static char closing(char open){return switch(open){case '《'->'》';case '“'->'”';case '「'->'」';case '"'->'"';default->0;};}
    static String modeLabel(String mode){return mode.matches("远程直播|线上直播")?"线上实时教学":mode.matches("通过远程连线|远程|线上")?"远程授课形式":"面对面授课形式";}
    static List<String> modeTerms(String mode){String label=modeLabel(mode);return label.equals("线上实时教学")?List.of("直播","实时"):label.equals("远程授课形式")?List.of("远程","线上","直播"):List.of("现场","面授","线下","面对面");}
}
