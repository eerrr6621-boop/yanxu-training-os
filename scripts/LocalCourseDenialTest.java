package com.training;

import java.util.*;

/** Author-labelled synthetic scope tests; no model or real resume used. */
public final class LocalCourseDenialTest {
    static int checks;
    static final String POSITIVE="实际授课记录\n2025年本人主讲服务礼仪课程，完成课堂点评。";
    static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    static String text(String denial){return "未授课课程\n"+denial+"\n"+POSITIVE;}
    static Map<String,Object> assess(String source){
        Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",80.0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage("培训主题：服务礼仪").assess(c,source,false);return c;
    }
    public static void main(String[] args){
        for(String denial:List.of("本人没有讲授《数字营销》。","我从未主讲过《数字营销》这门课。","本人尚未亲自讲授《数字营销》课程。")){
            String source=text(denial);
            check(!EvidenceSections.globalQualification(source),"specific excluded course must not invalidate other headed records: "+denial);
            check(ProfessionalEvidence.positive(source).contains("本人主讲服务礼仪"),"independent positive record retained");
            check(!ProfessionalEvidence.positive(source).contains("数字营销"),"denied course stays excluded");
            check(!EvidenceSections.sections(source).isEmpty(),"independent scoring section survives: "+denial);
            check(Boolean.TRUE.equals(assess(source).get("_admitted")),"unrelated course denial must not suppress supported topic");
        }
        check(!Boolean.TRUE.equals(assess(text("本人没有讲授《服务礼仪》。")).get("_admitted")),"same-course contradiction still cannot support requested topic");
        for(String source:List.of(
            "本人没有讲授《数字营销》。\n"+POSITIVE,
            "个人介绍\n本人没有讲授《数字营销》。\n"+POSITIVE,
            text("本人没有讲授该课程。"),text("本人从未有过任何授课经历。"),
            text("以上所有课程都并非本人主讲。"),
            text("本人没有讲授《数字营销》，上述经历均为团队案例。"),
            text("本人没有讲授《数字营销》。")+"\n以上内容不代表本人授课经历。",
            text("本人没有讲授《数字营销》。")+"\n个人介绍\n本人没有讲授《服务礼仪》。")){
            check(EvidenceSections.globalQualification(source),"unbounded, global or carried denial remains review-required");
            check(ProfessionalEvidence.positive(source).isEmpty(),"global denial cannot be laundered by a heading");
        }
        check(!EvidenceSections.globalQualification(POSITIVE),"ordinary positive source unchanged");
        String original=text("本人没有讲授《数字营销》。");
        for(Map<String,Object> section:EvidenceSections.sections(original)) {
            int start=((Number)section.get("source_start")).intValue(),end=((Number)section.get("source_end")).intValue();
            String body=original.substring(original.offsetByCodePoints(0,start),original.offsetByCodePoints(0,end));
            check(body.contains("本人主讲服务礼仪"),"scoring offsets still address original source");
            check(!body.contains("数字营销"),"denied source is not scoring evidence");
        }
        check(EvidenceSections.globalQualification(text("本人没有讲授《以上全部课程》。")),"quoted reference is not a named-course escape");
        check(EvidenceSections.globalQualification("以上记录：\n"+original),"unresolved reference cannot lose its denial across a heading");
        System.out.println("Local course denial scope: "+checks+" synthetic checks passed");
    }
}
