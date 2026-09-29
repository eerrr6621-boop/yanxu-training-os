package com.training;
import java.util.*;

/** Permanent regression for independently found event attribution defects. */
public class NarrativeSafetyTest {
    static int count;
    static void check(String text,boolean expected,String label) {
        Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",20.0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage("需要主讲过《课堂主题》。").assess(c,text,false);
        count++;if(Boolean.TRUE.equals(c.get("_admitted"))!=expected)throw new AssertionError(label+": "+Json.write(c));
        if(!expected){count++;if(!TeachingEvents.scoringSections(text).isEmpty())throw new AssertionError("Unsafe source scope: "+label);}
    }
    public static void main(String[] args) {
        String own="本人已独立讲完《课堂主题》。";
        check(own+"\n\n本次教学已完成。",true,"completed self");
        check(own+"\n\n实际上我未完成这门课的教学。",false,"cross-paragraph unfinished teaching");
        check("以下是本人的教学记录：\n"+own,true,"personal metadata");
        check("以下引用项目负责人的教学记录：\n"+own,false,"quoted author is not candidate");
        check("下列摘录合作方的授课经历：\n\n"+own,false,"quoted author across blank line");
        check("本人已为实习店员主讲《课堂主题》。",true,"teaching for audience");
        for(String verb:List.of("安排","委托","邀请","协调"))check("本人已为实习店员"+verb+"主讲《课堂主题》。",false,"arrangement not own teaching "+verb);
        check(own+"公司只负责会务。",true,"organization support is not own support role");
        check(own+"本人只负责会务。",false,"own support contradicts lead role");
        System.out.println("Narrative attribution safety: "+count+" checks passed; synthetic, no model");
    }
}
