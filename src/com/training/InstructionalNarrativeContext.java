package com.training;

import java.util.*;
import java.util.regex.*;

/** Complete classroom-context sentences, never independent teaching proof. */
final class InstructionalNarrativeContext {
    record Span(int start,int end,String kind) {}
    private static final String SELF="(?:本人|我|该讲师|该教师|该老师)";
    private static final Pattern ROLE=Pattern.compile("^"+SELF+"(?:是|担任)(?:该班|本班|这个班|该课程|本课程|这门课)(?:的)?(?:主讲人|主讲教师|授课教师)$");
    private static final Pattern EXAMPLE=Pattern.compile("^"+SELF+"(?:负责|承担)从(?:示例|例题)(?:讲解|演示)到(?:练习|作业)(?:后的|后)(?:答疑|讲评)$");
    private static final Pattern RISK=Pattern.compile("本人|讲师|老师|教师|同事|他人|主讲|讲授|授课|资格|履历|简历|经历|证明|假设|虚构|引用|转述|未曾|尚未|从未|计划|不具备|没有|我");
    static String kind(String text) {
        if(ROLE.matcher(text).matches())return "same_course_role_restatement";
        if(EXAMPLE.matcher(text).matches())return "owned_example_explanation_context";
        if(text.matches("^(?:业务主管|现场工作人员|工作人员|助教)(?:仅|只)(?:帮助|协助)(?:解释|说明)(?:表格栏目|设备按键|工具名称|场地布局)$"))return "auxiliary_material_explanation_context";
        if(RISK.matcher(text).find())return "";
        if(text.matches("^(?:最后|第一|第二|第三)(?:一)?段练习(?:要求|要)(?:补上|填写|列出)[\\p{IsHan}、]{2,75}$"))return "learner_practice_instruction_context";
        if(!text.matches("^(?:课堂|课程|教学)要求[:：]?.*"))return "";
        String body=text.replaceFirst("^(?:课堂|课程|教学)要求[:：]?","");
        String[] parts=body.split("[，,]",-1);boolean positive=false;
        for(int i=0;i<parts.length;i++) {
            String clause=parts[i].strip();
            boolean action=clause.matches("^(?:先|首先|再|随后|接着)把[\\p{IsHan}]{1,40}(?:记下来|分栏|分类|列出|写入表格)$");
            boolean instruction=clause.matches("^不把[\\p{IsHan}]{1,30}写成[\\p{IsHan}]{1,15}$");
            if(i==0&&!action||!action&&!instruction)return "";
            positive|=action;
        }
        return positive?"classroom_instruction_context":"";
    }
    static List<Span> spans(String source) {
        List<Span> result=new ArrayList<>();Matcher sentences=Pattern.compile("[^。；;！？!?]+").matcher(source);
        while(sentences.find()) {
            String raw=sentences.group(),text=raw.strip();String kind=kind(text);if(kind.isEmpty())continue;
            int start=sentences.start()+raw.indexOf(text);result.add(new Span(start,start+text.length(),kind));
        }
        return result;
    }
    private InstructionalNarrativeContext(){}
}
