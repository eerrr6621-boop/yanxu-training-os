package com.training;

import java.util.*;
import java.util.regex.*;

/** Display-only, bounded source claims. Never certifies eligibility or teaching. */
final class QualificationClaim {
    private static final String TITLE="(?:(?:教师|讲师|培训师|授课)(?:资格(?:证(?:书)?)?|资质|证书)|认证(?:讲师|培训师|教师))";
    private static final Pattern ASSERTION=Pattern.compile("^(?:(?:本人|我|该讲师|该教师|同时|已经|现已|目前|并|已)){0,4}(?:持有|具备|取得|获得|拥有)(.{1,110})$");
    private static final Pattern ENDING=Pattern.compile("(?:"+TITLE+"|资格证书|资格证|认证证书)[》”」]?$");
    private static final Pattern GENERAL=Pattern.compile("^[《“「]?(?:高级|中级|初级)?"+TITLE+"[》”」]?$");
    private static final Pattern NOMINAL=Pattern.compile("^([^:：\\r\\n]{2,90}?)(?:认证讲师|认证培训师|认证教师)$");
    private static final Pattern RISK=Pattern.compile("计划|拟|希望|将要|准备|申请|失效|过期|撤销|吊销|无效|他人|同事|别人|转述|引用|示例|样例|模板|假设|如果|要求|条件|报名|参加|参训|学习|结业|培养|成为|可获");

    static Map<String,Object> check(List<Map<String,Object>> records,Map<String,List<String>> topics) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("criterion","授课资格真实性需另行核验（本人授课经历不代替资格核验）");
        out.put("status","needs_evidence");out.put("verified",false);out.put("evidence","");
        out.put("source_claim_lookup","bounded_not_exhaustive");
        for(Map<String,Object> record:records) {
            String text=Objects.toString(record.get("text"),"");
            // Do not cut away an expiry, ownership qualifier or other disclaimer
            // from the same record before looking for an affirmative clause.
            if(RISK.matcher(text).find()||!ProfessionalEvidence.allowed(text)||ProfessionalEvidence.organizationClaim(text))continue;
            Matcher clauses=Pattern.compile("[^，,。；;！？!?\\r\\n]+").matcher(text);
            while(clauses.find()) {
                String raw=clauses.group(),claim=raw.strip();
                Matcher assertion=ASSERTION.matcher(claim),nominal=NOMINAL.matcher(claim);
                String object;
                if(assertion.matches()&&ENDING.matcher(assertion.group(1)).find())object=assertion.group(1);
                else if(nominal.matches()&&!claim.matches("(?s).*(?:主讲|讲授|授课|介绍|讲解|方法).*"))object=claim;
                else continue;
                boolean general=GENERAL.matcher(object).matches();
                boolean named=topics.values().stream().anyMatch(terms->RequirementCoverage.contains(object,terms));
                if(!general&&!named)continue;
                out.put("evidence",claim);out.put("claim_verification","unverified_resume_statement");
                out.put("claim_scope",general?"general_teaching_qualification":"requested_topic_mentioned");
                out.put("evidence_id",record.get("evidence_id"));out.put("source_line",record.get("source_line"));
                if(record.get("source_offset") instanceof Number at) {
                    out.put("source_offset",at.intValue()+clauses.start()+raw.length()-raw.stripLeading().length());
                    out.put("source_offset_unit","java_utf16_code_unit");
                    out.put("source_kind","normalized_resume_matching_text");
                }
                return out;
            }
        }
        return out;
    }
    private QualificationClaim() {}
}
