package com.training;

import java.util.*;
import java.util.regex.*;

/** Learner materials are non-proof context, never new actor/history fields. */
final class LearnerArtifactContext {
    private static final String TIME="(?:课堂|课程|培训|整期)(?:结束|结课)后";
    private static final String MATERIAL="[\\u4e00-\\u9fff]{0,20}(?:模板|步骤卡|练习卡|练习册|练习表|记录表|讲义|工作表|答题纸|操作卡|检查表|提示卡)";
    private static final String INFORMATION="[\\u4e00-\\u9fff]{0,20}(?:信息|资料|数据|档案|记录|名单)";
    private static final String JOIN="[，,][\\t ]*(?:\\r?\\n[\\t ]*)?";
    private static final String SUBJECT="(?:(?:"+TIME+JOIN+")?(?:学员|学生|参训者)(?:带走|领取|拿到|收到|保留)的是|"+TIME+"(?:留下|保存)的是)";
    private static final Pattern SENTENCE=Pattern.compile(SUBJECT+"(?<material>"+MATERIAL+")"+JOIN+"(?:而不是|不是|而非|并非)(?<information>"+INFORMATION+")");
    private static final Pattern FACT_RISK=Pattern.compile("本人|我|他人|同事|讲师|老师|教师|主讲|讲授|授课|教学(?:记录|经历|证明)|履历|简历|经历|资格|证明|证据|证书|认证|完成|未|不|非|没有|计划|拟|假设|虚构|引用|转述");
    private static final Pattern PRIOR_RISK=Pattern.compile("假设|假如|如果|虚构|转述|引用|摘录|原话|(?:以下|上述|以上|这是|此为|仅为|只是)[^。；;\\r\\n]{0,30}(?:模板|样例|示例)");

    /** UTF-16 source offsets; callers convert to code points at the wire boundary. */
    static List<int[]> spans(String text) {
        List<int[]> result=new ArrayList<>();
        Matcher sentences=Pattern.compile("(?:[^。；;！？!?\\r\\n]|\\r?\\n(?![\\t ]*\\r?\\n))+").matcher(text);
        while(sentences.find()) {
            String raw=sentences.group(),clause=raw.strip();Matcher found=SENTENCE.matcher(clause);
            if(!found.matches()||FACT_RISK.matcher(found.group("material")+found.group("information")).find())continue;
            int at=sentences.start()+raw.length()-raw.stripLeading().length(),paragraphStart=0;
            Matcher breaks=Pattern.compile("\\r?\\n[\\t ]*\\r?\\n").matcher(text);
            while(breaks.find())if(breaks.end()<=at)paragraphStart=breaks.end();
            String prior=text.substring(paragraphStart,Math.max(paragraphStart,sentences.start()));
            if(!ProfessionalEvidence.completedPersonalEvent(prior)||PRIOR_RISK.matcher(prior).find())continue;
            result.add(new int[]{at,at+clause.length()});
        }
        return result;
    }
}
