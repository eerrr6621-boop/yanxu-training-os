package com.training;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline evaluation adapter, not an alternative recommender. Calls the current
 * effective-profile, evidence gate, model validation and final selector. No DB,
 * HTTP, production writes or pre-model relevance exclusion. Inputs are private. */
public final class TwentyRoundSemanticProbe {
    static Method method(Class<?> type,String name,Class<?>... parameters) throws Exception {
        Method m=type.getDeclaredMethod(name,parameters);m.setAccessible(true);return m;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String,Object> input=(Map<String,Object>)Json.parse(new String(System.in.readAllBytes(),StandardCharsets.UTF_8));
        Class<?> t=TeacherIntelligence.class;
        Class<?> requirementClass=Class.forName("com.training.TeacherIntelligence$Requirement");
        Class<?> matchClass=Class.forName("com.training.TeacherIntelligence$Match");
        Method from=method(requirementClass,"from",String.class,Map.class,double.class);
        Method auto=method(t,"autoProfile",String.class,Map.class);
        Method profile=method(t,"profileForMatching",Map.class);
        Method score=method(t,"scoreTeacher",requirementClass,Map.class,Map.class);
        Method map=method(matchClass,"toMap",Map.class,Map.class);
        Method redact=method(t,"redact",String.class),clean=method(t,"cleanText",String.class);
        Method sourceMethod=method(t,"requirementSource",Map.class,String.class);
        Field relevant=matchClass.getDeclaredField("relevant");relevant.setAccessible(true);
        List<Map<String,Object>> output=new ArrayList<>();
        DispatchPreference pref=DispatchPreference.from(Map.of("training_mode","线上"),null);
        for(Object raw:(List<?>)input.get("cases")) {
            Map<String,Object> test=(Map<String,Object>)raw;
            String source=(String)sourceMethod.invoke(null,null,test.get("query"));
            Object requirement=from.invoke(null,source,null,0.0);
            RequirementCoverage coverage=new RequirementCoverage(source);
            List<Map<String,Object>> candidates=new ArrayList<>(),prepared=new ArrayList<>();
            Map<Long,String> texts=new LinkedHashMap<>();
            for(Object rawDoc:(List<?>)test.get("documents")) {
                Map<String,Object> doc=(Map<String,Object>)rawDoc;
                long id=((Number)doc.get("id")).longValue();
                String text=Objects.toString(doc.get("text"),""),manual=Objects.toString(doc.get("manual_text"),"");
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
                String professional=(String)redact.invoke(null,clean.invoke(null,Objects.toString(effective.get("_matching_text"),"")));
                texts.put(id,professional);prepared.add(Map.of("id",id,"text",professional));
            }
            String redactedSource=(String)redact.invoke(null,source);
            Map<String,Object> semantic=Map.of("status","disabled");
            if(test.get("semantic_response") instanceof Map<?,?>) {
                Map<String,Object> response=(Map<String,Object>)test.get("semantic_response");
                semantic=LocalSemantic.augment(redactedSource,candidates,texts,request->response);
            } else if(test.containsKey("semantic_error")) {
                semantic=LocalSemantic.augment(redactedSource,candidates,texts,request->{throw new IllegalArgumentException("evaluation_worker_failed");});
            }
            List<Map<String,Object>> supported=new ArrayList<>(),review=new ArrayList<>();
            List<Long> excluded=new ArrayList<>();
            for(Map<String,Object> candidate:candidates) {
                long id=((Number)candidate.get("teacher_id")).longValue();
                coverage.assess(candidate,texts.get(id),"ready".equals(semantic.get("status")));
                if(Boolean.TRUE.equals(candidate.remove("_admitted"))) supported.add(candidate);
                else if(Boolean.TRUE.equals(candidate.get("_review_candidate"))) review.add(candidate);
                else excluded.add(id);
                candidate.remove("_review_candidate");candidate.remove("_rule_relevant");
            }
            List<Long> admitted=supported.stream().map(c->((Number)c.get("teacher_id")).longValue()).toList();
            Map<String,Object> selection=NearbySelection.select(supported,pref,3);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("id",test.get("id"));result.put("focused_query",LocalSemantic.focusedQuery(redactedSource));
            result.put("prepared_documents",prepared);result.put("semantic_state",semantic);
            result.put("admitted_ids",admitted);result.put("selected",selection.remove("selected"));
            result.put("selection",selection);result.put("review",review);result.put("excluded_ids",excluded);
            output.add(result);
        }
        System.out.println(Json.write(Map.of("cases",output)));
    }
}
