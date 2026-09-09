package com.training;

import java.util.*;

/** Independent affirmative/negative syntax pairs; no model or database. */
public final class EvidenceGrammarTest {
    record Case(String id,String text,boolean history) {}
    static int checks;
    static void check(boolean ok,String message) { checks++;if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        List<Case> cases=List.of(
            new Case("G01","本人已为园艺学员主讲土壤含水量观察课程，课程已结束。",true),
            new Case("G02","本人已经给社区讲解员独立讲授馆藏编号规则课程。",true),
            new Case("G03","该讲师实际主讲应急装备盘点课程，共完成两次授课。",true),
            new Case("G04","本人已给维修团队主讲紧固件识别课程，课程已结束。",true),
            new Case("G05","两份结课记录注明，同一讲师先为少年志愿者完成一场文物标签授课，再为成人志愿者完成另一场授课。",true),
            new Case("G06","本人已为仓库管理员主讲货位标识课程。其他讲师没有承担该课讲授。",true),
            new Case("G07","本人已给讲解员主讲馆藏编码课程；其他老师未承担该课程的授课。",true),
            new Case("G08","本人已为档案管理人员主讲文件命名规范课程。课程无需写代码或配置系统。",true),
            new Case("G09","本人已为保管员主讲封签核对课程，课堂不要求学员安装软件。",true),
            new Case("G10","课程无需写代码。",false),
            new Case("G11","其他讲师没有承担该课讲授。",false),
            new Case("G12","本人已为学员邀请另一位老师主讲垃圾减量课程。",false),
            new Case("G13","本人已给学员安排其他讲师讲授水表抄录课程。",false),
            new Case("G14","本人已为学员计划主讲盆栽养护课程。",false),
            new Case("G15","本人未曾给学员主讲盆栽养护课程。",false),
            new Case("G16","本人已为学员主讲庭院植物识别课程。本人没有承担该课讲授。",false),
            new Case("G17","本人已为学员主讲庭院植物识别课程。其他讲师承担该课讲授。",false),
            new Case("G18","本人已为学员主讲庭院植物识别课程。其他讲师没有不承担该课讲授。",false),
            new Case("G19","本人已为学员主讲庭院植物识别课程。课程不需要本人授课。",false),
            new Case("G20","本人已为学员主讲庭院植物识别课程。课程无需写代码，但本人尚未授课。",false),
            new Case("G21","本人担任助教。\n该讲师实际主讲水表抄录课程。",false),
            new Case("G22","本人为教学助理。\n本人已给学员主讲水表抄录课程。",false),
            new Case("G23","本人已给学员主讲水表抄录课程。\n本人未亲自授课。",false),
            new Case("G24","本人已为学员主讲导览标识课程。课程无需写代码。\n上述记录非本人授课。",false),
            new Case("G25","张伟讲师先为志愿者完成一场文物标签授课。",false),
            new Case("G26","本人已为学员让其他人主讲封签核对课程。",false),
            new Case("G27","本人已给学员编写主讲老师的封签核对教材。",false),
            new Case("G28","同一讲师实际主讲陶土成型课程，已完成教学。",true),
            new Case("G29","本人已给学员主讲庭院排水课程。课程不要求学员讲授。",false),
            new Case("G30","本人已给学员主讲庭院排水课程。课堂不安排设备拆卸。",false),
            new Case("G31","个人介绍\n王明老师实际主讲盆栽养护课程。\n主讲课程\n同一讲师已给学员主讲园艺工具课程。",false),
            new Case("G32","个人介绍\n其他讲师已讲授盆栽养护课程。\n主讲课程\n同一讲师已给学员主讲园艺工具课程。",false),
            new Case("G33","本人已完成参加培训授课的报名。",false),
            new Case("G34","本人已完成授课报名，主讲人另行安排。",false),
            new Case("G35","本人已完成课程授课的准备材料。",false),
            new Case("G36","本人已为志愿者安排主讲老师的签到。",false)
        );
        for(Case c:cases) {
            List<Map<String,Object>> records=ProfessionalEvidence.records(c.text());
            check(records.stream().anyMatch(r->"teaching_history_statement".equals(r.get("role")))==c.history(),c.id());
            for(Map<String,Object> record:records) {
                String normalized=ProfessionalEvidence.normalized(c.text()),quote=(String)record.get("text");
                int at=(Integer)record.get("source_offset");
                check(normalized.substring(at,at+quote.length()).equals(quote),c.id()+" contiguous quote");
            }
        }
        String source="🧪本人已给检验人员主讲标签核对课程。课程无需写代码。本人已为保管员主讲封签课程。";
        List<Map<String,Object>> records=ProfessionalEvidence.records(source);
        check(!records.isEmpty(),"bounded grammar has positive records");
        for(Map<String,Object> record:records) {
            check(!((String)record.get("text")).contains("写代码"),"excluded task is not topic proof");
            check(source.equals(record.get("context_text")),"full original context retained");
            check(Integer.valueOf(0).equals(record.get("context_source_offset")),"original context offset retained");
        }
        System.out.println("Evidence grammar: "+checks+" checks passed; synthetic, no model invoked");
    }
}
