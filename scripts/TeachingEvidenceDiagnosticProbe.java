package com.training;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Private offline observer. Never changes production classes or manufactures
 * a model score. Full source text is deliberately PRIVATE diagnostic output. */
public final class TeachingEvidenceDiagnosticProbe {
    static Method method(Class<?> type,String name,Class<?>... parameters)throws Exception {
        Method method=type.getDeclaredMethod(name,parameters);method.setAccessible(true);return method;
    }
    static Map<String,Object> object(Object raw) {
        if(!(raw instanceof Map<?,?> map))throw new IllegalArgumentException("object_required");
        Map<String,Object> out=new LinkedHashMap<>();map.forEach((k,v)->out.put(k.toString(),v));return out;
    }
    static String sha(String text)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    static Map<String,Object> location(String source,int start,int end) throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();out.put("source_start",source.codePointCount(0,start));out.put("source_end",source.codePointCount(0,end));
        out.put("source_line",1+source.substring(0,start).chars().filter(c->c=='\n').count());
        out.put("offset_unit","unicode_code_point");out.put("source","normalized_effective_resume");
        out.put("text",source.substring(start,end));out.put("text_sha256",sha(source.substring(start,end)));return out;
    }
    static Map<String,Object> document(String source)throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();out.put("text",source);out.put("text_sha256",sha(source));
        out.put("characters",source.codePointCount(0,source.length()));
        List<TeachingEvents.Event> events=TeachingEvents.extract(source);List<Map<String,Object>> details=new ArrayList<>();
        int completed=0,lengthEligible=0;for(TeachingEvents.Event event:events) {
            Map<String,Object> detail=new LinkedHashMap<>(event.map());int length=source.codePointCount(event.start,event.end);
            detail.put("source_line",1+source.substring(0,event.start).chars().filter(c->c=='\n').count());
            detail.put("usable",event.usable());detail.put("personal_lead",event.personalLead());detail.put("explicit_block",event.explicitBlock());
            detail.put("characters",length);detail.put("source_text_sha256",sha(source.substring(event.start,event.end)));
            List<String> reasons=new ArrayList<>();
            if(!event.usable())reasons.add("event_has_issues_or_missing_course");
            if(!event.personalLead())reasons.add("personal_lead_not_established");
            if(!event.completedPersonalTeaching())reasons.add("completed_personal_teaching_not_established");
            if(length>220)reasons.add("event_exceeds_220_code_points");
            if(event.completedPersonalTeaching()){completed++;if(length<=220)lengthEligible++;}
            detail.put("scope_exclusion_reasons",reasons);details.add(detail);
        }
        out.put("events",details);out.put("event_count",events.size());out.put("completed_personal_event_count",completed);
        out.put("completed_length_eligible_event_count",lengthEligible);out.put("scope_count_limit_exceeded",lengthEligible>64);
        out.put("server_teaching_event_scopes",TeachingEvents.scoringSections(source));
        out.put("legacy_independent_sections",EvidenceSections.sections(source));
        List<Map<String,Object>> records=new ArrayList<>();
        for(Map<String,Object> record:ProfessionalEvidence.records(source)) {
            Map<String,Object> item=new LinkedHashMap<>(record);int start=((Number)record.get("source_offset")).intValue();
            String text=Objects.toString(record.get("text"));int end=start+text.length();
            boolean exact=start>=0&&end<=source.length()&&source.substring(start,end).equals(text);
            item.put("source_offset_unit","utf16");item.put("contiguous_source_verified",exact);
            if(exact){item.put("source_start",source.codePointCount(0,start));item.put("source_end",source.codePointCount(0,end));item.put("offset_unit","unicode_code_point");}
            records.add(item);
        }
        out.put("legacy_records",records);
        out.put("java_global_qualification",EvidenceSections.globalQualification(source));
        List<Map<String,Object>> signals=new ArrayList<>();Matcher lines=Pattern.compile("[^\\r\\n]+").matcher(source);
        while(lines.find()) {
            String text=lines.group();
            if(!text.matches("(?s).*(?:授课|主讲|讲授|教学|课程|培训|精通|擅长).*"))continue;
            Map<String,Object> line=location(source,lines.start(),lines.end());
            line.put("teaching_keyword_is_locator_not_fact",true);line.put("grade",ProfessionalEvidence.grade(text));
            line.put("own_teaching_signal",ProfessionalEvidence.ownTeaching(text));line.put("completed_personal_signal",ProfessionalEvidence.completedPersonalEvent(text));
            line.put("negative",ProfessionalEvidence.negative(text));line.put("other_actor",ProfessionalEvidence.otherActor(text));
            line.put("support_only",ProfessionalEvidence.supportOnly(text));line.put("organization_claim",ProfessionalEvidence.organizationClaim(text));
            line.put("role_safe_full_source",ProfessionalEvidence.roleSafeAt(source,lines.start(),lines.end(),0,source.length()));signals.add(line);
        }
        out.put("teaching_signal_lines",signals);return out;
    }
    static Map<String,Object> requirement(RequirementCoverage coverage) {
        RequirementConstraints c=coverage.constraints;Map<String,Object> out=new LinkedHashMap<>();
        out.put("course_contract",coverage.topicContract.toMap());out.put("required_content",coverage.required);
        out.put("positive_constraint_text",c.positive);out.put("features",c.features);out.put("each_features",c.eachFeatures);
        out.put("minimums",c.minimums);out.put("audience_review",c.audienceReview);out.put("unknown_exclusion",c.unknownExclusion);
        out.put("teaching_history",c.teachingHistory);out.put("global_teaching_history",c.globalTeachingHistory);
        out.put("single_instructor",c.singleInstructor);out.put("qualification_required",c.qualificationRequired);
        out.put("uncovered_conjunction",coverage.uncoveredConjunction);out.put("location_hard_review",coverage.locationHardReview);
        out.put("resolved_worker_exclusions",c.resolvedWorkerExclusions);
        out.put("comparisons",c.comparisons.stream().map(x->Map.of("start",x.start(),"end",x.end(),"operator",x.operator(),"value",x.value(),"unit",x.unit(),"supported",x.supported())).toList());
        return out;
    }
    static Map<String,Object> outcome(Map<String,Object> candidate) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(String key:List.of("_admitted","_review_candidate","_rule_relevant","requirement_topic_contract","unparsed_requirements",
            "requirement_coverage","coverage_complete","match_tier","gaps","retrieval_method"))out.put(key,candidate.get(key));
        return out;
    }
    static Map<String,Object> cloneCandidate(Map<String,Object> original) {
        Map<String,Object> copy=new LinkedHashMap<>(original);copy.put("gaps",new ArrayList<>((Collection<?>)original.get("gaps")));return copy;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        byte[] bytes=System.in.readNBytes(32*1024*1024+1);
        if(args.length!=0||bytes.length>32*1024*1024)throw new IllegalArgumentException("private_probe_capacity");
        Map<String,Object> input=object(Json.parse(new String(bytes,StandardCharsets.UTF_8)));
        Class<?> type=TeacherIntelligence.class,req=Class.forName("com.training.TeacherIntelligence$Requirement"),match=Class.forName("com.training.TeacherIntelligence$Match");
        Method from=method(req,"from",String.class,Map.class,double.class),auto=method(type,"autoProfile",String.class,Map.class),profile=method(type,"profileForMatching",Map.class);
        Method score=method(type,"scoreTeacher",req,Map.class,Map.class),toMap=method(match,"toMap",Map.class,Map.class);
        Method clean=method(type,"cleanText",String.class),redact=method(type,"redact",String.class),sourceMethod=method(type,"requirementSource",Map.class,String.class);
        Field relevant=match.getDeclaredField("relevant");relevant.setAccessible(true);
        Map<String,Map<String,Object>> documents=new LinkedHashMap<>();List<Map<String,Object>> results=new ArrayList<>();
        for(Object rawCase:(List<?>)input.get("cases")) {
            Map<String,Object> test=object(rawCase);String query=Objects.toString(test.get("query"));
            String source=(String)sourceMethod.invoke(null,null,query);Object demand=from.invoke(null,source,null,0.0);
            RequirementCoverage coverage=new RequirementCoverage(source);List<Map<String,Object>> candidates=new ArrayList<>();List<Map<String,Object>> supported=new ArrayList<>();
            for(Object rawDoc:(List<?>)test.get("documents")) {
                Map<String,Object> doc=object(rawDoc);long id=((Number)doc.get("id")).longValue();String text=Objects.toString(doc.get("text")),manual=Objects.toString(doc.getOrDefault("manual_text",""));
                Map<String,Object> teacher=new LinkedHashMap<>();teacher.put("id",id);teacher.put("name","【灰度测试】匿名讲师"+id);
                teacher.put("extracted_text",text);teacher.put("manual_profile",manual);teacher.put("field","");teacher.put("intro","");teacher.put("fee_rate",1800);
                teacher.put("teacher_level",doc.getOrDefault("teacher_level","讲师"));
                teacher.put("profile_json",Json.write(auto.invoke(null,manual.isEmpty()?text:manual,teacher)));
                Map<String,Object> effective=(Map<String,Object>)profile.invoke(null,teacher);Object scored=score.invoke(null,demand,teacher,effective);
                Map<String,Object> candidate=(Map<String,Object>)toMap.invoke(scored,teacher,effective);candidate.put("_rule_relevant",relevant.getBoolean(scored));
                candidate.put("dispatch_fit",new LinkedHashMap<>(Map.of("local_priority",false)));
                String prepared=LocalSemantic.normalized((String)redact.invoke(null,clean.invoke(null,Objects.toString(effective.get("_matching_text"),""))));
                String hash=sha(prepared);if(!documents.containsKey(hash))documents.put(hash,document(prepared));
                Map<String,Object> rule=cloneCandidate(candidate);coverage.assess(rule,prepared,false);
                if(Boolean.TRUE.equals(rule.get("_admitted")))supported.add(rule);
                Map<String,Object> row=new LinkedHashMap<>();row.put("fixture_id",id);row.put("document_sha256",hash);
                row.put("prepared_text",prepared);row.put("prepared_server_scopes",TeachingEvents.scoringSections(prepared));
                row.put("full_source_rule",outcome(rule));
                if(doc.get("diagnostic_evidence_metadata") instanceof Map<?,?> raw) {
                    Map<String,Object> metadata=object(raw);if(metadata.containsKey("similarity")||metadata.containsKey("evidence"))throw new IllegalArgumentException("diagnostic_metadata_is_not_a_score_or_response");
                    Map<String,Object> scoped=cloneCandidate(candidate);scoped.put("semantic",metadata);
                    boolean verified=EvidenceSections.verified(prepared,metadata);coverage.assess(scoped,prepared,false);
                    row.put("structure_only_scope_verified",verified);row.put("structure_only_coverage",outcome(scoped));
                    row.put("structure_only_not_a_model_response",true);
                }
                candidates.add(row);
            }
            Map<String,Object> result=new LinkedHashMap<>();result.put("id",test.get("id"));result.put("original_query",query);result.put("matching_query",source);
            result.put("focused_query",LocalSemantic.focusedQuery((String)redact.invoke(null,source)));result.put("requirement",requirement(coverage));result.put("candidates",candidates);
            result.put("admitted_ids",supported.stream().map(c->c.get("teacher_id")).toList());
            result.put("selection",NearbySelection.select(supported,DispatchPreference.from(Map.of("training_mode","线上"),null),3));results.add(result);
        }
        Map<String,Object> output=new LinkedHashMap<>();output.put("schema_version","private_teaching_evidence_layer_diagnostic_v1");
        output.put("private_contains_resume_text",true);output.put("model_invoked",false);output.put("production_touched",false);
        output.put("interpretation","Signals locate source text, not certified truth. Structure-only coverage is diagnostic, not a successful model response.");
        output.put("documents",documents);output.put("cases",results);System.out.println(Json.write(output));
    }
}
