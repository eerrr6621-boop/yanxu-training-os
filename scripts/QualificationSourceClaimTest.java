package com.training;

import java.util.*;

/** Exposed synthetic qualification-display regression; not certificate verification. */
public final class QualificationSourceClaimTest {
    static int checks;
    static List<String> failures=new ArrayList<>();
    static void check(boolean ok,String why){checks++;if(!ok)failures.add(why);}
    @SuppressWarnings("unchecked") static Map<String,Object> qualification(String topic,String text) {
        Map<String,Object> candidate=new LinkedHashMap<>();candidate.put("professional_score",0.0);
        candidate.put("_rule_relevant",true);candidate.put("gaps",new ArrayList<String>());
        new RequirementCoverage("培训主题："+topic+"；需要已具备授课资格。").assess(candidate,text,false);
        check(Boolean.FALSE.equals(candidate.get("_admitted")),"source claim never verifies qualification");
        return ((List<Map<String,Object>>)candidate.get("requirement_coverage")).stream()
            .filter(c->c.get("criterion").toString().startsWith("授课资格真实性")).findFirst().orElseThrow();
    }
    static void expect(String topic,String line,boolean expected) {
        String source="主讲课程："+topic+"\n"+line;
        Map<String,Object> q=qualification(topic,source);
        check("needs_evidence".equals(q.get("status"))&&Boolean.FALSE.equals(q.get("verified")),"unverified status retained");
        boolean actual=!q.get("evidence").toString().isEmpty();
        check(actual==expected,"claim display "+expected+": "+line);
        if(expected&&actual) {
            check("unverified_resume_statement".equals(q.get("claim_verification")),"self statement labelled");
            check(q.get("source_offset") instanceof Number,"claim offset available");
            if(q.get("source_offset") instanceof Number n) {
                int at=n.intValue();String quote=q.get("evidence").toString();
                check(at>=0&&at+quote.length()<=source.length()&&source.substring(at,at+quote.length()).equals(quote),"contiguous source quote");
            }
        }
    }
    public static void main(String[] args) {
        for(String topic:List.of("亲子财商","劳动用工风险","服务与商务礼仪")) {
            for(String line:List.of(
                "某分行《"+topic+"资格认证培训》",
                "本人主讲《如何取得"+topic+"讲师资格证书》",
                "主讲课程："+topic+"讲师资格认证培训",
                "本人参加"+topic+"讲师资格认证培训并获得结业证书。",
                "计划取得"+topic+"讲师资格证书。",
                "本人尚未取得"+topic+"讲师资格证书。",
                "同事持有"+topic+"讲师资格证书。",
                "本公司拥有"+topic+"讲师资格认证证书。",
                "本人持有会计从业资格证书。",
                "持有"+topic+"讲师资格证书是本次培训的报名条件。"))expect(topic,line,false);
            for(String line:List.of(
                "本人持有"+topic+"讲师资格证书。",
                "已取得"+topic+"授课资格。",
                "拥有多年工作经验，同时具备"+topic+"认证培训师资质。",
                "某机构"+topic+"认证讲师",
                "本人持有教师资格证书。"))expect(topic,line,true);
        }
        expect("亲子财商","本人持有《劳动用工风险讲师资格证书》。",false);
        expect("亲子财商","本人持有《亲子财商讲师资格证书》。",true);
        expect("亲子财商","📚本人持有亲子财商讲师资格证书。",false);
        expect("亲子财商","本人持有亲子财商讲师资格证书，但上述证书已失效。",false);
        expect("亲子财商","本人持有亲子财商讲师资格证书。\n本人已主讲亲子财商课程。",true);
        if(!failures.isEmpty())throw new AssertionError(failures.size()+" failures / "+checks+" checks: "+String.join(" | ",failures));
        System.out.println("Qualification source claims: "+checks+" synthetic checks passed");
    }
}
