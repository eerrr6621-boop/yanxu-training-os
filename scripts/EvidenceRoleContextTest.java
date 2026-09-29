package com.training;

import java.util.*;

/** Fixed synthetic role-boundary tests, including genuine independent lectures. */
public final class EvidenceRoleContextTest {
    record Case(String id,String source,boolean expected) {}
    static int passed;
    static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label);passed++; }
    static Map<String,Object> assess(String source,boolean scoped) {
        Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",80.0);
        c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
        Map<String,Object> semantic=new LinkedHashMap<>();semantic.put("similarity",0.75);
        semantic.put("evidence_complete",!scoped);
        if(scoped) {
            List<Map<String,Object>> sections=EvidenceSections.sections(source);
            semantic.put("scoped_evidence_complete",!sections.isEmpty());
            semantic.put("scoring_sections",sections);semantic.put("scoring_scope",EvidenceSections.versionFor(sections));
        }
        c.put("semantic",semantic);
        new RequirementCoverage("培训主题：服务礼仪；必须有实际授课记录").assess(c,source,true);return c;
    }
    public static void main(String[] args) {
        List<Case> cases=List.of(
            new Case("R01","个人介绍\n本人担任助教。\n2025年开展服务礼仪课堂。\n精品课程\n服务礼仪课程",false),
            new Case("R02","个人介绍\n本人担任课程助理。\n2025年开展服务礼仪课堂。",false),
            new Case("R03","个人介绍\n本人在现场旁听。\n2025年开展服务礼仪课堂。",false),
            new Case("R04","个人介绍\n本人未亲自授课。\n2025年开展服务礼仪课堂。",false),
            new Case("R05","个人介绍\n本人角色：助理。\n2025年开展服务礼仪课堂。",false),
            new Case("R06","个人介绍\n本人担任助教。\n实际授课记录\n2025年开展服务礼仪课堂。",false),
            new Case("R07","个人介绍\n本人担任助教。\n另于2025年独立主讲服务礼仪课程。",true),
            new Case("R08","教学经历\n2023年本人担任课程助理。\n2025年本人独立主讲服务礼仪课程。",true),
            new Case("R09","教学经历\n2023年本人亲自主讲服务礼仪课程。\n2025年本人担任课程助理。",true),
            new Case("R10","教学经历\n2025年本人担任课程助理。\n另于2025年独立主讲服务礼仪课程。",false),
            new Case("R11","教学经历\n另于2025年独立主讲服务礼仪课程。\n本人担任课程助理。",false),
            new Case("R12","个人介绍\n本人担任助教。\n本人独立主讲服务礼仪课程。",false),
            new Case("R13","个人介绍\n本人担任助教。\n另于2025年带教服务礼仪课程。",false),
            new Case("R14","个人介绍\n本人没有承担任何教学工作。\n教学经历\n另于2025年独立主讲服务礼仪课程。",false),
            new Case("R15","个人介绍\n本人担任助教。\n另于2025年独立主讲服务礼仪课程。\n上述经历仅为团队案例。",false),
            new Case("R16","教学经历\n2023年本人担任课程助理，另于2025年独立主讲服务礼仪课程。",false),
            new Case("R17","教学经历\n2025年本人主讲服务礼仪课程。",true),
            new Case("R18","授课风采\n2025年本人带教服务礼仪课程。",false),
            new Case("R19","个人介绍\n本人未实际讲授该课。\n2025年开展服务礼仪课堂。",false),
            new Case("R20","教学经历\n2023年本人在现场旁听。\n本人于2025年亲自主讲服务礼仪课程。",true),
            new Case("R21","个人介绍\n本人是授课助理。\n2025年开展服务礼仪课堂。",false),
            new Case("R22","教学经历\n2025年曾带教助理主讲服务礼仪课程。",false),
            new Case("R23","教学经历\n2025年本人曾主讲服务礼仪课程，同时带教助理。",true)
        );
        for(Case test:cases) {
            for(boolean scoped:List.of(false,true)) {
                Map<String,Object> result=assess(test.source(),scoped);
                check(Boolean.TRUE.equals(result.get("_admitted"))==test.expected(),test.id()+" scoped="+scoped);
            }
        }
        String unicode="🧪\n个人介绍\n本人担任助教。\n另于2025年独立主讲服务礼仪课程。";
        Map<String,Object> c=assess(unicode,true);
        check(Boolean.TRUE.equals(c.get("_admitted")),"Unicode keeps role and distinct event association");
        for(Map<String,Object> record:ProfessionalEvidence.records(unicode)) {
            int start=(Integer)record.get("source_offset");String quote=(String)record.get("text");
            check(ProfessionalEvidence.normalized(unicode).substring(start,start+quote.length()).equals(quote),"Contiguous original record retained");
        }
        System.out.println("Evidence role context: "+passed+" checks passed; synthetic, no model invoked");
    }
}
