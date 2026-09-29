package com.training;

import java.util.*;

/** New fixed actor/event pairs; not a model benchmark or certification test. */
public final class EvidenceActorEventTest {
    static int checks;
    static void check(boolean condition,String message) { checks++;if(!condition) throw new AssertionError(message); }
    static boolean history(String text) { return ProfessionalEvidence.records(text).stream().anyMatch(r->"teaching_history_statement".equals(r.get("role"))); }
    public static void main(String[] args) {
        for(String topic:List.of("茶叶含水量校准","珐琅釉色辨识")) {
            for(String text:List.of("本人独立讲完《"+topic+"》，课堂已结课。",
                    "《"+topic+"》课堂已结课，独立主讲是本人。",
                    "我已独立完成"+topic+"教学。",
                    "本人已给质检员独立完成"+topic+"教学。")) {
                check(ProfessionalEvidence.completedPersonalEvent(text),"explicit completed event: "+text);
                check(history(text),"personal teaching history: "+text);
            }
            for(String text:List.of("本单位已有《"+topic+"》的结课案例，独立主讲是同事。本人负责讲义装订和发放。",
                    "《"+topic+"》课程已结课，主讲为另一位教员。本人是旁听学员。",
                    "《"+topic+"》课程已结课，本人是旁听学员。",
                    "本部门已举办《"+topic+"》培训，班级已结课。",
                    "本人负责《"+topic+"》讲义装订和发放。",
                    "本人独立完成《"+topic+"》课程教具制作，尚未开讲。",
                    "本人已独立完成"+topic+"教学助理工作。",
                    "本人已独立完成"+topic+"授课讲义。",
                    "本人已独立完成安排其他人进行"+topic+"教学。")) {
                check(!history(text),"not personal history: "+text);
                check(ProfessionalEvidence.grade(text)==0,"not affirmative course evidence: "+text);
            }
        }
        check(!history("本人负责讲义装订和发放。\n2025年开展釉色识别课程。"),"crossline support role");
        for(String topic:List.of("茶叶含水量校准","珐琅釉色辨识")) {
            String prefix="本人独立讲完《"+topic+"》。";
            for(String role:List.of("本人仅负责讲义装订和发放。","本人只承担课件制作。"))
                check(!history(prefix+role),"exclusive support contradicts earlier completion");
            for(String role:List.of("本人负责讲义装订和发放。","本人也负责课件制作。"))
                check(history(prefix+role),"incidental material work does not erase teaching");
        }
        for(String text:List.of("本人已完成茶叶校准教学记录。","本人已完成其他教员的釉色识别教学。",
                "《已经开设的课程》已结课。另一门《待安排的课程》的独立主讲是本人。"))
            check(!ProfessionalEvidence.completedPersonalEvent(text),"event object and nearest completion: "+text);
        String safe="本人独立讲完《茶叶含水量校准》。课堂不包含机械维修内容。";
        check(history(safe),"bounded unrelated content exclusion preserves history");
        for(Map<String,Object> record:ProfessionalEvidence.records(safe)) {
            String quote=(String)record.get("text");int at=(Integer)record.get("source_offset");
            check(!quote.contains("机械维修"),"excluded topic never becomes positive proof");
            check(safe.substring(at,at+quote.length()).equals(quote),"exact source quote");
            check(safe.equals(record.get("context_text")),"full context retained");
        }
        for(String suffix:List.of("课堂不包含茶叶含水量校准内容。","课堂不包含上述内容。","课堂不包含本人授课记录。","本人并非该课程主讲。"))
            check(!history("本人独立讲完《茶叶含水量校准》。"+suffix),"unresolved or personal denial: "+suffix);
        check(!history("本人已完成机械维修教学。介绍《茶叶含水量校准》课程，课堂不包含焊接内容。"),"exclusion cannot borrow a different completed event");
        System.out.println("Evidence actor/event: "+checks+" checks passed");
    }
}
