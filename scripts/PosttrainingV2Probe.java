package com.training;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Isolated stdin/stdout experiment adapter. No HTTP, database, training or
 * production configuration. The supplied artifact identity is retained, never
 * rewritten to bypass LocalSemantic's production allowlist. All models use the
 * same real profile, coverage and selection code. Synthetic protocol self-tests
 * are not inference tests or accuracy measurements. */
public final class PosttrainingV2Probe {
    private static final int MAX_INPUT = 32 * 1024 * 1024;
    private static final Set<String> EVIDENCE_REASONS = Set.of("mixed_personal_fields",
        "qualified_or_unconfirmed_paragraph", "non_teaching_or_ambiguous_role", "context_unit_too_long");
    private record Identity(String model, String revision, String precision) {}

    private static void require(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException("offline_protocol:" + reason);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        require(value instanceof Map<?,?>, "object_required");
        return (Map<String,Object>) value;
    }
    private static List<?> list(Object value, int min, int max) {
        require(value instanceof List<?>, "list_required");
        List<?> items = (List<?>) value;
        require(items.size() >= min && items.size() <= max, "list_size");
        return items;
    }
    private static String string(Object value, int min, int max) {
        require(value instanceof String, "string_required");
        String text = (String) value;
        require(text.length() >= min && text.length() <= max, "string_size");
        return text;
    }
    private static long integer(Object raw, long min, long max) {
        require(raw instanceof Number, "integer_required");
        Number number = (Number) raw;
        long value = number.longValue();
        require(Double.isFinite(number.doubleValue()) && number.doubleValue() == value &&
            value >= min && value <= max, "integer_range");
        return value;
    }
    private static List<String> strings(Object raw, int min, int max, int maxChars) {
        List<String> result = new ArrayList<>();
        for (Object item : list(raw,min,max)) result.add(string(item,1,maxChars));
        require(new HashSet<>(result).size() == result.size(), "duplicate_metadata");
        return result;
    }
    private static Method method(Class<?> type,String name,Class<?>... parameters) throws Exception {
        Method method=type.getDeclaredMethod(name,parameters);method.setAccessible(true);return method;
    }
    private static Map<String,Object> state(String status, Identity identity) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status",status);result.put("model",identity.model);result.put("revision",identity.revision);
        result.put("precision",identity.precision);result.put("offline_only",true);
        result.put("production_path",false);result.put("experimental_evaluation",true);
        result.put("external_ai",false);result.put("mode","offline_evidence_assist");
        result.put("eligibility_changed",false);
        return result;
    }
    /** JSON-detached observation: selection mutates candidates after assessment.
     * Never rerun a business predicate to produce an ostensibly better trace. */
    private static Object snapshot(Object value) { return Json.parse(Json.write(value)); }
    private static Map<String,Object> requirementAudit(RequirementCoverage coverage) {
        RequirementConstraints constraints=coverage.constraints;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("required_content",coverage.required);
        result.put("positive_constraint_text",constraints.positive);
        result.put("features",constraints.features);result.put("each_features",constraints.eachFeatures);
        result.put("minimums",constraints.minimums);result.put("audience_review",constraints.audienceReview);
        result.put("unknown_exclusion",constraints.unknownExclusion);
        result.put("teaching_history",constraints.teachingHistory);
        result.put("global_teaching_history",constraints.globalTeachingHistory);
        result.put("single_instructor",constraints.singleInstructor);
        result.put("qualification_required",constraints.qualificationRequired);
        result.put("distributed_counts",constraints.distributedCounts);
        result.put("resolved_worker_exclusions",constraints.resolvedWorkerExclusions);
        result.put("location_hard_review",coverage.locationHardReview);
        result.put("uncovered_conjunction",coverage.uncoveredConjunction);
        result.put("requires_teaching_history",coverage.requiresTeachingHistory);
        result.put("comparisons",constraints.comparisons.stream().map(c->Map.of(
            "start",c.start(),"end",c.end(),"operator",c.operator(),"value",c.value(),
            "unit",c.unit(),"supported",c.supported(),"offset_unit","java_utf16_code_unit",
            "source","requirement_source")).toList());
        return object(snapshot(result));
    }
    private static Map<String,Object> candidateAudit(Map<String,Object> candidate,
                                                    RequirementCoverage coverage,int documentIndex) {
        // Everything here was already produced by the SAME assess() call.
        // Local variables not exported by Coverage remain explicitly unobserved.
        Map<String,Object> result=new LinkedHashMap<>(),gates=new LinkedHashMap<>();
        boolean admitted=Boolean.TRUE.equals(candidate.get("_admitted"));
        boolean review=Boolean.TRUE.equals(candidate.get("_review_candidate"));
        result.put("teacher_id",candidate.get("teacher_id"));
        result.put("input_document_index",documentIndex);
        result.put("disposition_before_selection",admitted?"admitted":review?"review":"excluded");
        result.put("candidate_after_assessment",snapshot(candidate));
        gates.put("admitted",candidate.get("_admitted"));
        gates.put("review_candidate",candidate.get("_review_candidate"));
        gates.put("rule_relevant",candidate.get("_rule_relevant"));
        gates.put("coverage_complete",candidate.get("coverage_complete"));
        gates.put("location_hard_review",coverage.locationHardReview);
        gates.put("uncovered_conjunction",coverage.uncoveredConjunction);
        gates.put("requirement_checks",snapshot(candidate.get("requirement_coverage")));
        gates.put("failed_requirement_checks",((List<?>)candidate.get("requirement_coverage")).stream()
            .map(PosttrainingV2Probe::object).filter(c->!"source_supported".equals(c.get("status")))
            .map(PosttrainingV2Probe::snapshot).toList());
        gates.put("gaps",snapshot(candidate.get("gaps")));
        gates.put("not_exposed_by_business_code",List.of("literal","history","contentSupported",
            "boundRecords","denialConflict","scoped","evidenceReview","hardSupported"));
        result.put("observed_gates",gates);
        result.put("facts_reference","candidate_after_assessment.teaching_events and requirement_coverage");
        result.put("legacy_records_not_exported",true);
        result.put("business_reassessment_performed",false);
        return result;
    }
    private static Map<String,Object> analysis(Object raw, String query) {
        Map<String,Object> analysis=object(raw);
        List<String> positive=strings(analysis.get("positive_fragments"),1,8,440);
        List<String> exclusions=strings(analysis.get("exclusions"),0,64,10000);
        require(positive.stream().allMatch(query::contains) && exclusions.stream().allMatch(query::contains),
            "query_fragment_not_in_source");
        require(analysis.get("requires_review") instanceof Boolean, "query_review_boolean");
        require(Boolean.FALSE.equals(analysis.get("exclusions_enforced")), "retrieval_cannot_enforce_exclusions");
        List<String> reasons=strings(analysis.get("review_reasons"),0,8,100);
        require(reasons.stream().allMatch("exclusions_require_hard_verification"::equals), "query_review_reason");
        require(Boolean.TRUE.equals(analysis.get("requires_review")) == !exclusions.isEmpty() &&
            reasons.isEmpty() == exclusions.isEmpty(), "query_review_consistency");
        return analysis;
    }
    private static void sourceUnit(Map<String,Object> unit,String quote,String source,boolean mixed) {
        require(quote.equals(unit.get("text")) && "unicode_code_point".equals(unit.get("offset_unit")) &&
            "retrieval_only".equals(unit.get("kind")), "evidence_unit_metadata");
        int length=source.codePointCount(0,source.length());
        int start=(int)integer(unit.get("source_start"),0,length);
        int end=(int)integer(unit.get("source_end"),start,length);
        int paragraphStart=(int)integer(unit.get("paragraph_start"),0,start);
        int paragraphEnd=(int)integer(unit.get("paragraph_end"),end,length);
        int utfStart=source.offsetByCodePoints(0,start),utfEnd=source.offsetByCodePoints(0,end);
        require(quote.equals(source.substring(utfStart,utfEnd)), "evidence_offset_mismatch");
        String context=string(unit.get("context_status"),1,80);
        require(Set.of("paragraph_preserved","self_contained_sentences_after_paragraph_review","independent_fact_after_section_review","server_source_event").contains(context),
            "evidence_context_status");
        if ("paragraph_preserved".equals(context))
            require(start == paragraphStart && end == paragraphEnd, "paragraph_not_preserved");
        Object heading=unit.get("section_heading");
        require(unit.containsKey("section_heading"), "section_heading_missing");
        // Mixed headings belong to the one verified containing source scope.
        // That can have an empty title or a catalog heading after the quote.
        // containsUnit below validates them; single-version behavior is unchanged.
        if (!mixed && heading != null) {
            String text=string(heading,1,440);
            require(source.substring(0,source.offsetByCodePoints(0,paragraphStart)).contains(text), "heading_not_in_source");
        }
    }

    /** Validate all rows before attaching any; malformed output fails the run,
     * rather than being silently counted as a rule-only successful model run. */
    static Map<Long,Map<String,Object>> validate(Map<String,Object> response,Map<Long,String> texts,
                                               String query,Identity expected) {
        require(Boolean.TRUE.equals(response.get("experimental_evaluation")), "experimental_marker_required");
        require(expected.model.equals(response.get("model")) && expected.revision.equals(response.get("revision")),
            "artifact_identity_mismatch");
        require(expected.precision.equals(response.get("precision")), "precision_mismatch");
        require(Boolean.TRUE.equals(response.get("complete")) && "ready".equals(response.get("status")), "response_not_complete");
        Map<String,Object> queryAnalysis=analysis(response.get("requirement_analysis"),query);
        Map<Long,Map<String,Object>> result=new LinkedHashMap<>();
        for (Object raw:list(response.get("results"),texts.size(),texts.size())) {
            Map<String,Object> row=object(raw);
            long id=integer(row.get("id"),1,9007199254740991L);
            require(texts.containsKey(id) && !result.containsKey(id), "unknown_or_duplicate_candidate");
            require(row.containsKey("similarity"), "score_missing");
            Object score=row.get("similarity");
            require(score==null || score instanceof Number && Double.isFinite(((Number)score).doubleValue()) &&
                ((Number)score).doubleValue()>=-1 && ((Number)score).doubleValue()<=1, "score_range");
            require(row.get("evidence_complete") instanceof Boolean, "evidence_complete_missing");
            boolean complete=Boolean.TRUE.equals(row.get("evidence_complete"));
            boolean scoped=!complete && EvidenceSections.verified(texts.get(id),row);
            require(!Boolean.TRUE.equals(row.get("scoped_evidence_complete")) || scoped,"scoped_evidence_not_verified");
            List<String> evidence=strings(row.get("evidence"),0,2,440);
            require(evidence.stream().allMatch(q->q.length()>=2 && texts.get(id).contains(q)), "quote_not_in_source");
            List<String> reasons=strings(row.get("evidence_review_reasons"),0,4,100);
            require(reasons.stream().allMatch(EVIDENCE_REASONS::contains) && complete==reasons.isEmpty(), "evidence_review_consistency");
            require(complete ? (score==null)==evidence.isEmpty() : scoped ? score!=null && !evidence.isEmpty() : score==null, "incomplete_evidence_cannot_score");
            require(!scoped || evidence.stream().allMatch(q->EvidenceSections.containsQuote(texts.get(id),row,q)),"quote_outside_scoring_sections");
            List<?> units=list(row.get("evidence_units"),evidence.size(),evidence.size());
            boolean mixed=EvidenceSections.MIXED_VERSION.equals(row.get("scoring_scope"));
            for(int index=0;index<units.size();index++) {
                Map<String,Object> unit=object(units.get(index));
                sourceUnit(unit,evidence.get(index),texts.get(id),mixed);
                if(mixed)require(EvidenceSections.containsUnit(texts.get(id),row,unit),"quote_unit_outside_scoring_sections");
            }
            Map<String,Object> item=state(score==null?"no_evidence":"ready",expected);
            item.put("similarity",score);item.put("evidence",evidence);item.put("evidence_units",units);
            item.put("source","effective_profile");item.put("verified_qualification",false);
            item.put("evidence_complete",complete);item.put("evidence_review_reasons",reasons);
            if(scoped) {
                item.put("scoped_evidence_complete",true);item.put("scoring_scope",row.get("scoring_scope"));
                item.put("scoring_sections",row.get("scoring_sections"));
            }
            item.put("query_requires_review",queryAnalysis.get("requires_review"));
            item.put("query_exclusions",queryAnalysis.get("exclusions"));
            result.put(id,item);
        }
        require(result.keySet().equals(texts.keySet()), "candidate_set_incomplete");
        return result;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if(args.length==1 && "--self-test".equals(args[0])) { selfTest();return; }
        require(args.length==0,"unexpected_arguments");
        byte[] bytes=System.in.readNBytes(MAX_INPUT+1);
        require(bytes.length<=MAX_INPUT,"input_capacity");
        Map<String,Object> input=object(Json.parse(new String(bytes,StandardCharsets.UTF_8)));
        String phase=string(input.get("phase"),1,16);
        require(Set.of("prepare","evaluate").contains(phase),"phase_required");
        Identity expected=new Identity(string(input.get("expected_model"),1,300),
            string(input.get("expected_revision"),1,300),string(input.getOrDefault("expected_precision","int8"),1,8));
        require(Set.of("int8","fp32").contains(expected.precision),"precision_not_supported");
        Class<?> type=TeacherIntelligence.class;
        Class<?> requirementClass=Class.forName("com.training.TeacherIntelligence$Requirement");
        Class<?> matchClass=Class.forName("com.training.TeacherIntelligence$Match");
        Method from=method(requirementClass,"from",String.class,Map.class,double.class);
        Method auto=method(type,"autoProfile",String.class,Map.class),profile=method(type,"profileForMatching",Map.class);
        Method score=method(type,"scoreTeacher",requirementClass,Map.class,Map.class);
        Method map=method(matchClass,"toMap",Map.class,Map.class);
        Method redact=method(type,"redact",String.class),clean=method(type,"cleanText",String.class);
        Method sourceMethod=method(type,"requirementSource",Map.class,String.class);
        Field relevant=matchClass.getDeclaredField("relevant");relevant.setAccessible(true);
        List<Map<String,Object>> output=new ArrayList<>();Set<String> caseIds=new HashSet<>();
        DispatchPreference preference=DispatchPreference.from(Map.of("training_mode","线上"),null);
        for(Object raw:list(input.get("cases"),1,1000)) {
            Map<String,Object> test=object(raw);
            String caseId=string(test.get("id"),1,300);require(caseIds.add(caseId),"duplicate_case_id");
            boolean hasResponse=test.containsKey("semantic_response"),hasError=test.containsKey("semantic_error");
            require("evaluate".equals(phase) ? hasResponse!=hasError : !hasResponse && !hasError,"phase_response_contract");
            String query=string(test.get("query"),1,10000);
            // Same complete-query guard as production, before focus can drop fields.
            require(!LocalSemantic.normalized(query).matches("(?s).*(?:(?:男|女)(?:性)?(?:讲师|老师)|(?:讲师|老师)(?:性别)?[:：]?(?:男|女)|(?:性别|年龄|出生日期|民族|婚姻状况|宗教信仰)\\s*[:：]).*"),
                "personal_attribute_requirement_not_supported");
            String source=(String)sourceMethod.invoke(null,null,query);
            Object requirement=from.invoke(null,source,null,0.0);
            RequirementCoverage coverage=new RequirementCoverage(source);
            List<Map<String,Object>> candidates=new ArrayList<>(),prepared=new ArrayList<>();
            Map<Long,String> texts=new LinkedHashMap<>();
            for(Object rawDoc:list(test.get("documents"),1,200)) {
                Map<String,Object> doc=object(rawDoc);
                long id=integer(doc.get("id"),1,9007199254740991L);require(!texts.containsKey(id),"duplicate_document_id");
                String text=string(doc.get("text"),0,512*1024),manual=string(doc.getOrDefault("manual_text",""),0,512*1024);
                Map<String,Object> teacher=new LinkedHashMap<>();
                teacher.put("id",id);teacher.put("name","【灰度测试】匿名讲师"+id);
                teacher.put("extracted_text",text);teacher.put("manual_profile",manual);
                teacher.put("field","");teacher.put("intro","");teacher.put("fee_rate",1800);
                teacher.put("teacher_level",doc.getOrDefault("teacher_level","讲师"));
                teacher.put("profile_json",Json.write(auto.invoke(null,manual.isEmpty()?text:manual,teacher)));
                Map<String,Object> effective=(Map<String,Object>)profile.invoke(null,teacher);
                Object match=score.invoke(null,requirement,teacher,effective);
                Map<String,Object> candidate=(Map<String,Object>)map.invoke(match,teacher,effective);
                candidate.put("_rule_relevant",relevant.getBoolean(match));
                candidate.put("dispatch_fit",new LinkedHashMap<>(Map.of("local_priority",false)));
                candidates.add(candidate);
                String professional=LocalSemantic.normalized((String)redact.invoke(null,clean.invoke(null,Objects.toString(effective.get("_matching_text"),""))));
                texts.put(id,professional);Map<String,Object> preparedDoc=new LinkedHashMap<>(Map.of("id",id,"text",professional));
                List<Map<String,Object>> eventScopes=TeachingEvents.scoringSections(professional);
                if(!eventScopes.isEmpty())preparedDoc.put("server_teaching_event_scopes",eventScopes);
                prepared.add(preparedDoc);
            }
            String focused=LocalSemantic.focusedQuery((String)redact.invoke(null,source));
            require(!focused.isBlank(),"empty_focused_query");
            require(Json.write(Map.of("query",focused,"documents",prepared)).getBytes(StandardCharsets.UTF_8).length<=512*1024,"request_capacity");
            Map<String,Object> semantic=state("prepared",expected);
            if(hasResponse) {
                Map<Long,Map<String,Object>> checked=validate(object(test.get("semantic_response")),texts,focused,expected);
                for(Map<String,Object> candidate:candidates)
                    candidate.put("semantic",checked.get(((Number)candidate.get("teacher_id")).longValue()));
                semantic=state("ready",expected);
                semantic.put("message","离线实验输出校验通过；非生产 HTTP 接入，相似度不构成授课能力认证");
            } else if(hasError) {
                semantic=state("inference_error",expected);
                semantic.put("message","离线模型未完成此题；保留失败计数，不以规则回退冒充成功推理");
                for(Map<String,Object> candidate:candidates) {
                    Map<String,Object> unavailable=state("inference_error",expected);
                    unavailable.put("similarity",null);unavailable.put("evidence",List.of());
                    unavailable.put("evidence_complete",false);unavailable.put("verified_qualification",false);
                    candidate.put("semantic",unavailable);
                }
            }
            semantic.put("candidate_count",candidates.size());
            List<Map<String,Object>> supported=new ArrayList<>(),review=new ArrayList<>(),candidateAudits=new ArrayList<>();List<Long> excluded=new ArrayList<>();
            for(Map<String,Object> candidate:candidates) {
                long id=((Number)candidate.get("teacher_id")).longValue();
                coverage.assess(candidate,texts.get(id),"ready".equals(semantic.get("status")));
                candidateAudits.add(candidateAudit(candidate,coverage,candidateAudits.size()));
                if(Boolean.TRUE.equals(candidate.remove("_admitted"))) supported.add(candidate);
                else if(Boolean.TRUE.equals(candidate.get("_review_candidate"))) review.add(candidate);
                else excluded.add(id);
                candidate.remove("_review_candidate");candidate.remove("_rule_relevant");
            }
            Map<String,Object> selection=NearbySelection.select(supported,preference,3);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("id",caseId);result.put("focused_query",focused);result.put("prepared_documents",prepared);
            result.put("semantic_state",semantic);result.put("offline_only",true);result.put("production_path",false);
            result.put("admitted_ids",supported.stream().map(c->((Number)c.get("teacher_id")).longValue()).toList());
            result.put("selected",selection.remove("selected"));result.put("selection",selection);
            result.put("review",review);result.put("excluded_ids",excluded);
            Map<String,Object> audit=new LinkedHashMap<>(),semanticInput=new LinkedHashMap<>();
            audit.put("schema_version","posttraining_candidate_audit_v1");audit.put("phase",phase);
            audit.put("private_contains_source_text",true);audit.put("captured_before_filter_and_selection",true);
            audit.put("original_query",query);audit.put("requirement_source",source);
            audit.put("input_documents",snapshot(test.get("documents")));
            audit.put("worker_request",snapshot(Map.of("query",focused,"documents",prepared)));
            audit.put("requirement_configuration",requirementAudit(coverage));
            semanticInput.put("mode",hasResponse?"validated_supplied_response":hasError?"strict_error_replay":"rule_prepare_only");
            semanticInput.put("response",hasResponse?snapshot(test.get("semantic_response")):null);
            semanticInput.put("error",hasError?snapshot(test.get("semantic_error")):null);
            semanticInput.put("error_details",test.containsKey("semantic_error_details")?snapshot(test.get("semantic_error_details")):null);
            semanticInput.put("original_error_details_supplied",hasError&&test.containsKey("semantic_error_details"));
            semanticInput.put("error_payload_preserved_verbatim",true);
            semanticInput.put("model_invoked_by_probe",false);
            semanticInput.put("error_or_rule_prepare_is_model_success",false);
            semanticInput.put("production_http_fallback_observed",false);
            semanticInput.put("fallback_note","Prepare is a separate rule-only observation; strict error replay is not production HTTP fallback. No rule result is relabelled as successful inference.");
            audit.put("semantic_input",semanticInput);audit.put("candidates",candidateAudits);
            result.put("audit",audit);output.add(result);
        }
        System.out.println(Json.write(Map.of("cases",output,"phase",phase,"offline_only",true,"production_path",false)));
    }

    private static Map<String,Object> fixture(Identity identity,String query,String source) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",1L);row.put("similarity",0.6);
        row.put("evidence",List.of(source));row.put("evidence_complete",true);row.put("evidence_review_reasons",List.of());
        row.put("evidence_units",List.of(new LinkedHashMap<>(Map.of("text",source,"source_start",0,
            "source_end",source.codePointCount(0,source.length()),"paragraph_start",0,"paragraph_end",source.codePointCount(0,source.length()),
            "offset_unit","unicode_code_point","kind","retrieval_only","context_status","paragraph_preserved"))));
        object(list(row.get("evidence_units"),1,1).get(0)).put("section_heading",null);
        Map<String,Object> response=state("ready",identity);response.put("complete",true);response.put("results",List.of(row));
        response.put("requirement_analysis",new LinkedHashMap<>(Map.of("positive_fragments",List.of(query),"exclusions",List.of(),
            "requires_review",false,"exclusions_enforced",false,"review_reasons",List.of())));
        return response;
    }
    private static void selfTest() {
        Identity identity=new Identity("offline/mock-artifact","mock-revision-not-a-trained-checkpoint","int8");
        String query="示例课程",source="🧪本人主讲示例课程。";
        Map<Long,String> texts=Map.of(1L,source);
        validate(fixture(identity,query,source),texts,query,identity);
        List<Consumer<Map<String,Object>>> rejected=List.of(
            r->r.put("model","another-model"), r->r.put("revision","another-revision"),
            r->r.remove("experimental_evaluation"), r->r.put("precision","fp32"),
            r->r.put("complete",false), r->r.put("results",List.of()),
            r->object(list(r.get("results"),1,1).get(0)).put("id",2L),
            r->object(list(r.get("results"),1,1).get(0)).put("similarity",Double.NaN),
            r->object(list(r.get("results"),1,1).get(0)).put("similarity",1.1),
            r->object(list(r.get("results"),1,1).get(0)).put("evidence",List.of("伪造引用")),
            r->object(list(object(list(r.get("results"),1,1).get(0)).get("evidence_units"),1,1).get(0)).put("source_end",source.length()),
            r->object(list(r.get("results"),1,1).get(0)).put("evidence_complete",false),
            r->object(r.get("requirement_analysis")).put("positive_fragments",List.of("不在需求原文中")),
            r->object(r.get("requirement_analysis")).put("exclusions_enforced",true),
            r->object(list(r.get("results"),1,1).get(0)).remove("evidence_units")
        );
        for(Consumer<Map<String,Object>> mutation:rejected) {
            Map<String,Object> response=fixture(identity,query,source);mutation.accept(response);
            boolean failed=false;try {validate(response,texts,query,identity);} catch(IllegalArgumentException expected) {failed=true;}
            require(failed,"self_test_expected_rejection");
        }
        Map<String,Object> incomplete=fixture(identity,query,source);
        Map<String,Object> row=object(list(incomplete.get("results"),1,1).get(0));
        row.put("evidence_complete",false);row.put("similarity",null);
        row.put("evidence_review_reasons",List.of("qualified_or_unconfirmed_paragraph"));
        validate(incomplete,texts,query,identity);
        Map<String,Object> duplicated=fixture(identity,query,source);
        Object first=list(duplicated.get("results"),1,1).get(0);duplicated.put("results",List.of(first,first));
        boolean failed=false;try {validate(duplicated,Map.of(1L,source,2L,source),query,identity);}
        catch(IllegalArgumentException expected) {failed=true;}require(failed,"duplicate_result_rejected");
        System.out.println(Json.write(Map.of("protocol_tests_passed",rejected.size()+3,"models_invoked",false,
            "synthetic_protocol_only",true,"offline_only",true,"production_path",false)));
    }
}
