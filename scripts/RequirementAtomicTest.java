package com.training;

import java.util.*;

/** Independent synthetic facts, no model/HTTP/database/private-resume access. */
public final class RequirementAtomicTest {
    static int passed;
    static Map<String,Object> assess(String query,String text) {
        Map<String,Object> candidate=new LinkedHashMap<>();
        candidate.put("professional_score",80.0);candidate.put("_rule_relevant",true);
        candidate.put("gaps",new ArrayList<String>());
        candidate.put("semantic",Map.of("similarity",.999,"evidence_complete",true));
        new RequirementCoverage(query).assess(candidate,text,true);return candidate;
    }
    static void expect(String id,boolean expected,String query,String text) {
        Map<String,Object> result=assess(query,text);
        if(Boolean.TRUE.equals(result.get("_admitted"))!=expected)
            throw new AssertionError(id+" expected="+expected+" "+Json.write(result.get("requirement_coverage")));
        passed++;
    }
    static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message);passed++; }
    public static void main(String[] args) {
        for(String topic:List.of("焊接安全","园艺修枝","仓储盘点")) {
            String query="主题为"+topic+"；必须有本人实际授课记录；需要面向一线工人；必须面对面授课";
            String good="本人实际主讲"+topic+"，授课对象为一线工人，采用面对面教学。";
            expect(topic+" face and audience",true,query,good);
            expect(topic+" remote mismatch",false,query,good.replace("面对面","远程直播"));
            expect(topic+" missing audience",false,query,good.replace("一线工人","部门经理"));
            expect(topic+" format absent",false,query,good.replace("，采用面对面教学",""));
            expect(topic+" literal unrelated clause",false,query,"本人实际主讲无关主题；授课对象为一线工人，采用面对面教学。");
            String repeated="主题为"+topic+"；已完成两次；需要面向一线工人；必须面对面授课；两次都须有练习和反馈";
            String twice="本人实际主讲"+topic+"，已完成个人授课两场，授课对象为一线工人，采用面对面教学，每场均包含练习和反馈。";
            expect(topic+" two completed with each activity",true,repeated,twice);
            expect(topic+" one only",false,repeated,twice.replace("两场","一场"));
            expect(topic+" only one activity session",false,repeated,twice.replace("每场均包含","其中一场包含"));
            expect(topic+" no feedback",false,repeated,twice.replace("和反馈",""));
            expect(topic+" feedback not yet arranged",false,repeated,twice.replace("每场均包含练习和反馈","每场均包含练习，反馈待补充"));
            expect(topic+" audience explicitly excluded",false,repeated,twice.replace("授课对象为一线工人","授课对象不含一线工人"));
            expect(topic+" remote despite topic",false,repeated,twice.replace("面对面","远程直播"));
            expect(topic+" no completed count",false,repeated,twice.replace("，已完成个人授课两场",""));
            expect(topic+" split unrelated records",false,repeated,
                    "本人实际主讲"+topic+"，已完成个人授课两场。\n"+
                    "本人实际主讲"+topic+"，已完成个人授课一场，授课对象为一线工人，采用面对面教学，每场均包含练习和反馈。");
        }
        String remote="主题：仓储盘点；必须有本人实际授课记录；要求远程授课";
        expect("remote positive",true,remote,"本人实际主讲远程仓储盘点课程。");
        expect("remote cannot use face",false,remote,"本人实际主讲面对面仓储盘点课程。");
        String each="培训主题：领导力；已完成两场；每场授课至少90分钟";
        expect("each exact positive",true,each,"本人实际主讲领导力，已完成个人授课两场，每场授课90分钟。");
        expect("total cannot prove each",false,each,"本人实际主讲领导力，已完成个人授课两场，累计授课180分钟。");
        expect("short each cannot borrow total",false,each,"本人实际主讲领导力，已完成个人授课三场，累计授课180分钟，每场授课60分钟。");
        expect("undefined lesson-hours not minutes",false,each,"本人实际主讲领导力，已完成个人授课两场，每场授课3课时。");
        expect("exact hours convert to minutes",true,each,"本人实际主讲领导力，已完成个人授课两场，每场授课2小时。");
        expect("different unknown course each duration",false,each,"本人实际主讲领导力，已完成个人授课两场。\n本人实际主讲仓储盘点，每场授课120分钟。");
        expect("unknown course later on same line each duration",false,each,"本人实际主讲领导力，已完成个人授课两场；另主讲仓储盘点，每场授课120分钟。");
        String multiEach="培训主题：领导力；战略管理；每场授课至少90分钟";
        expect("each applies to every requested topic",false,multiEach,
                "本人实际主讲领导力，每场授课90分钟。\n本人实际主讲战略管理，每场授课30分钟。");
        expect("each proved independently for every topic",true,multiEach,
                "本人实际主讲领导力，每场授课90分钟。\n本人实际主讲战略管理，每场授课120分钟。");
        expect("practice presence cannot borrow another topic each qualifier",false,
                "主题为园艺修枝；已完成两场；每场都须练习和反馈",
                "本人实际主讲园艺修枝，已完成个人授课两场，课堂包含练习和反馈；另主讲仓储盘点，每场均包含练习和反馈。");
        for(String wording:List.of("需要面向工厂新员工培训","要求面对面教学"))
            expect("recognized conditions are not unknown course modules "+wording,true,
                    "培训主题：领导力；"+wording,
                    "本人实际主讲领导力，授课对象为工厂新员工，采用面对面教学。");
        String four="课程模块：劳动用工、账务涉税、费用预算、经营数据分析；不能拆成几个人拼课";
        String all="本人主讲课程：劳动用工、账务涉税、费用预算、经营数据分析。";
        expect("same instructor all four",true,four,all);
        expect("fourth independent module absent",false,four,all.replace("、经营数据分析",""));
        expect("another instructor fourth module",false,four,
                all.replace("、经营数据分析","")+"\n经营数据分析由其他老师主讲。");
        expect("other exclusion survives single instructor",false,four+"；不接受外包老师",all);
        Map<String,Object> fourResult=assess(four,all);
        check(((List<?>)fourResult.get("requirement_coverage")).stream().anyMatch(c->
                c instanceof Map<?,?> m&&"经营数据分析".equals(m.get("criterion"))),"fourth module has independent criterion");
        for(String exclusion:List.of("资格证不是经验","助教、认证培训、筹备不能替代已讲","认证培训不能代替实际授课经历")) {
            String q="培训主题：亲子财商；"+exclusion;
            expect("actual teaching responds to "+exclusion,true,q,"2025年本人实际主讲亲子财商课程。");
            expect("certificate not teaching "+exclusion,false,q,"本人取得亲子财商资格证书。");
            expect("other unknown remains "+exclusion,false,q+"；不接受外包老师","2025年本人实际主讲亲子财商课程。");
        }
        expect("qualification remains independent review",false,
                "培训主题：亲子财商；需要已具备授课资格；资格证不是经验",
                "本人取得亲子财商资格证书。\n2025年本人实际主讲亲子财商课程。");
        check(!new RequirementConstraints("培训主题：领导力；资格证不是经验").unknownExclusion,"recognized exclusion not discarded as unknown");
        check(new RequirementConstraints("培训主题：领导力；资格证不是经验；不接受外包老师").unknownExclusion,"unrelated unknown exclusion preserved");
        // Authorized reused diagnostic, not a new blind label or training item.
        String care="需要面向家庭照护者、已完成两次面对面工作坊的讲师，主题为尊重隐私的日常沟通；两次都须带领角色练习并给学员反馈。";
        expect("reused care positive",true,care,
                "该讲师已为家庭照护者完成两次面对面工作坊，主题都是尊重隐私的日常沟通；两份活动记录均载有本人带领角色练习并逐组反馈的环节。");
        expect("reused care audience negative",false,care,
                "该讲师已为机构专业照护员完成两次面对面工作坊，主题为尊重隐私的日常沟通；两次都带领角色练习并反馈，报名对象不含家庭照护者。");
        expect("reused care online negative",false,care,
                "该讲师已为家庭照护者完成两次远程直播工作坊，主题为尊重隐私的日常沟通，两次都带领在线角色练习并反馈。");
        expect("reused care second activity absent",false,care,
                "该讲师已为家庭照护者完成两次面对面、尊重隐私的日常沟通工作坊；第一次安排角色练习和反馈，第二次仅播放示范，没有安排学员练习或反馈。");
        String oldQualification="亲子财商活动需要已经具备授课资格、且简历明确记载实际亲子课堂经历的老师；只有培训助教经历、参加认证培训或正在筹备讲师资格，都不能视为已经讲过。";
        check(!new RequirementConstraints(oldQualification).unknownExclusion,"reused role-list exclusion recognized precisely");
        check(new RequirementConstraints(oldQualification).qualificationRequired,"qualification remains separate condition");
        check(new RequirementConstraints(oldQualification).handlesWorkerExclusions(Map.of("query_requires_review",true,
                "query_exclusions",List.of("都不能视为已经讲过"))),"split worker suffix needs validated role antecedent");
        check(!new RequirementConstraints("亲子财商；其他条件，都不能视为已经讲过").handlesWorkerExclusions(Map.of("query_requires_review",true,
                "query_exclusions",List.of("都不能视为已经讲过"))),"unknown antecedent does not authorize suffix");
        check(!new RequirementConstraints(oldQualification).handlesWorkerExclusions(Map.of("query_requires_review",true,
                "query_exclusions",List.of("都不能视为已经讲过","不接受外包老师"))),"worker unrelated exclusion stays pending");
        System.out.println("RequirementAtomicTest: "+passed+" synthetic checks passed");
    }
}
