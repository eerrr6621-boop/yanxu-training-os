package com.training;

import java.util.*;

/** Fixed source-role grammar checks, not model accuracy or reviewed train data. */
public final class PersonalCourseOfferTest {
    static int checks;
    static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static boolean course(String text){return ProfessionalEvidence.records(text).stream().anyMatch(r->"course_statement".equals(r.get("role")));}
    static boolean history(String text){return ProfessionalEvidence.records(text).stream().anyMatch(r->"teaching_history_statement".equals(r.get("role")));}
    public static void main(String[] args){
        for(String title:List.of("工单处理顺序","器材借用登记","课堂问答组织")){
            String intro="《"+title+"》课程讨论如何安排处理顺序。";
            for(String actor:List.of("本人","我","该讲师"))for(String modal:List.of("可以","能够","可","能"))for(String action:List.of("讲授","授课","主讲")){
                String claim=actor+modal+"提供这门课程，并亲自承担"+action+"。",text=intro+claim;
                ok(course(text),"explicit personal offer survives unrelated arrangement wording: "+text);
                ok(!history(text),"can provide is not a completed teaching event");
                ok(course(intro+"\n"+claim),"single line break cannot change course support");
                for(var record:ProfessionalEvidence.records(text)){
                    int at=((Number)record.get("source_offset")).intValue();String quote=(String)record.get("text");
                    ok(ProfessionalEvidence.normalized(text).substring(at,at+quote.length()).equals(quote),"original contiguous quote/offset preserved");
                    ok(Boolean.FALSE.equals(record.get("verified")),"offer not independently certified");
                }
            }
            String explicit="我可以提供《"+title+"》，并亲自讲授。";
            ok(course(intro+explicit),"repeated same quoted title is unambiguous");
            for(String qualifier:List.of("假设场景：","引用内容：","以下为模板："))
                ok(!course(qualifier+intro+"我可以提供这门课程，并亲自承担讲授。"),"a qualified example cannot acquire the personal-offer exception");
            for(String bad:List.of("同事可以提供这门课程并讲授，我只制作纸卡。","本人只负责资料，讲授由其他讲师完成。","我不能提供这门课程，并亲自承担讲授。",
                    "我计划提供这门课程，并亲自承担讲授。","我可以提供这门课程，并亲自承担讲授助理工作。","我可以提供这门课程，并亲自承担讲授资料的整理。",
                    "引用：我可以提供这门课程，并亲自承担讲授。","是否我可以提供这门课程，并亲自承担讲授。","我可以提供这门课程，并由同事承担讲授。"))
                ok(!course(intro+bad),"ownership, plan, materials and quoted questions must not gain this exception: "+bad);
            // An ambiguous organizing role must not become a personal offer merely
            // because the following sentence contains a teaching word.
            ok(!course("本人安排了《"+title+"》。主讲由某老师完成。"),"old cross-sentence organization guard retained");
            ok(!course(intro+"《另一门课》另有课程说明。我可以提供这门课程，并亲自承担讲授。"),"ambiguous multi-course pronoun not newly restored");
            ok(!course("课程内容讨论如何安排处理顺序。我可以提供这门课程，并亲自承担讲授。"+intro),"pronoun cannot borrow a later course declaration");
        }
        System.out.println("Personal course offer: "+checks+" synthetic checks PASS; no model or history certification.");
    }
}
