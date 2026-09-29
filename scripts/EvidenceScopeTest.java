package com.training;

import java.util.*;

/** Frozen follow-up scope probes with affirmative counterparts.
 * Synthetic, author-labelled diagnostic cases, not blind business validation.
 * Per-topic/per-session distribution may remain manual review in this iteration;
 * the five affirmative cases use supported, unambiguous requirements instead.
 * No real resumes, model calls, HTTP, database, file writes or historical edits.
 */
public final class EvidenceScopeTest {
    record Case(String id,String purpose,String query,String text,boolean expectedAdmission) {}

    static List<Case> cases() {
        return List.of(
            new Case("S01","Language on another topic is not transferred",
                "培训主题：领导力；必须英文授课",
                "本人实际主讲中文领导力课程，另主讲英文焊接识图课程。",false),
            new Case("S02","Requested topic explicitly taught in the requested language",
                "培训主题：领导力；必须英文授课",
                "本人实际主讲英文领导力课程，学员完成了课堂练习。",true),
            new Case("S03","Audience on another topic is not transferred",
                "培训主题：领导力；必须面向生产班组长",
                "本人实际主讲面向企业董事长的领导力，另外面向生产班组长讲授设备维护课。",false),
            new Case("S04","Requested topic explicitly taught to the requested audience",
                "培训主题：领导力；必须面向生产班组长",
                "本人实际主讲面向生产班组长的领导力课程，并完成课堂练习点评。",true),
            new Case("S05","Named teacher and personal liaison duties do not prove own teaching",
                "培训主题：领导力；必须有实际授课记录",
                "2025年领导力课由陈老师主讲，本人负责联络学员。",false),
            new Case("S06","Passive voice clearly attributes teaching to the resume owner",
                "培训主题：领导力；必须有实际授课记录",
                "2025年领导力课程由本人主讲，学员由项目助理联络。",true),
            new Case("S07","Per-topic lower bound cannot be satisfied by only one topic",
                "培训主题：领导力；战略管理；两门课程各至少3小时",
                "本人实际主讲领导力30分钟。\n本人实际主讲战略管理180分钟。",false),
            new Case("S08","Both topics and unambiguous supported duration remain eligible",
                "培训主题：领导力；战略管理；至少3小时",
                "本人实际主讲领导力，实际授课180分钟。\n本人实际主讲战略管理，实际授课180分钟。",true),
            new Case("S09","Per-session lower bound cannot borrow aggregate duration",
                "培训主题：领导力；每场授课至少90分钟",
                "本人实际主讲领导力，三场累计授课180分钟，每场授课60分钟。",false),
            new Case("S10","Explicit supported duration still qualifies without ambiguous aggregation",
                "培训主题：领导力；至少90分钟",
                "本人实际主讲领导力，实际授课90分钟，课程记录已归档。",true)
        );
    }

    static Map<String,Object> assess(Case test) {
        Map<String,Object> candidate=new LinkedHashMap<>();
        candidate.put("professional_score",80.0);
        candidate.put("_rule_relevant",true);
        candidate.put("gaps",new ArrayList<String>());
        candidate.put("semantic",Map.of("similarity",.99,"evidence_complete",true));
        new RequirementCoverage(test.query()).assess(candidate,test.text(),true);
        return candidate;
    }

    public static void main(String[] args) {
        int passed=0,positives=0,positivePassed=0;
        List<Map<String,Object>> results=new ArrayList<>();
        for(Case test:cases()) {
            if(test.expectedAdmission()) positives++;
            Map<String,Object> entry=new LinkedHashMap<>();
            entry.put("id",test.id());entry.put("purpose",test.purpose());
            entry.put("expected_admission",test.expectedAdmission());
            try {
                Map<String,Object> actual=assess(test);
                boolean admitted=Boolean.TRUE.equals(actual.get("_admitted"));
                boolean ok=admitted==test.expectedAdmission();
                entry.put("actual_admission",admitted);entry.put("passed",ok);
                entry.put("coverage",actual.get("requirement_coverage"));
                if(ok) { passed++;if(test.expectedAdmission()) positivePassed++; }
            } catch(Exception error) {
                entry.put("passed",false);entry.put("error",error.getClass().getSimpleName()+": "+error.getMessage());
            }
            results.add(entry);
        }
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("notice","Frozen synthetic follow-up scope diagnostics, not blind business validation. Unsupported per-topic/per-session distribution remains manual review; positive cases avoid those unsupported quantifiers.");
        report.put("cases",results.size());report.put("passed",passed);
        report.put("positive_cases",positives);report.put("positive_passed",positivePassed);
        report.put("review_expected_cases",results.size()-positives);
        report.put("review_expected_passed",passed-positivePassed);
        report.put("results",results);
        System.out.println(Json.write(report));
        if(passed!=results.size()) throw new AssertionError("Evidence scope: "+passed+"/"+results.size()+" passed; expectations unchanged");
    }
}
