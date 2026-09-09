package com.training;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only synthetic probe of the actual rule gate, effective profile and sorter.
 * No HTTP listener, credentials, database or production data. Optional model results
 * are independently computed by the evaluator, then validated by LocalSemantic.
 */
public final class SemanticChallengeProbe {
    static Method method(Class<?> type, String name, Class<?>... params) throws Exception {
        Method value = type.getDeclaredMethod(name, params); value.setAccessible(true); return value;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String,Object> input = (Map<String,Object>) Json.parse(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        Class<?> intelligence = TeacherIntelligence.class;
        Class<?> requirementType = Class.forName("com.training.TeacherIntelligence$Requirement");
        Class<?> matchType = Class.forName("com.training.TeacherIntelligence$Match");
        Method from = method(requirementType,"from",String.class,Map.class,double.class);
        Method autoProfile = method(intelligence,"autoProfile",String.class,Map.class);
        Method profileForMatching = method(intelligence,"profileForMatching",Map.class);
        Method score = method(intelligence,"scoreTeacher",requirementType,Map.class,Map.class);
        Method toMap = method(matchType,"toMap",Map.class,Map.class);
        Method redact = method(intelligence,"redact",String.class);
        Method requirementSource = method(intelligence,"requirementSource",Map.class,String.class);
        Field relevant = matchType.getDeclaredField("relevant"); relevant.setAccessible(true);
        List<Map<String,Object>> output = new ArrayList<>();
        for (Object raw : (List<?>)input.get("cases")) {
            Map<String,Object> test = (Map<String,Object>)raw;
            String query = (String)test.get("query");
            String source = (String)requirementSource.invoke(null,null,LocalSemantic.normalized(query));
            Object requirement = from.invoke(null,source,null,0.0);
            List<Map<String,Object>> candidates = new ArrayList<>(), excluded = new ArrayList<>();
            Map<Long,String> texts = new LinkedHashMap<>();
            for (Object rawDoc : (List<?>)test.get("documents")) {
                Map<String,Object> doc = (Map<String,Object>)rawDoc;
                long id = ((Number)doc.get("id")).longValue();
                String text = (String)doc.get("text"), manual = (String)doc.getOrDefault("manual_text","");
                Map<String,Object> teacher = new LinkedHashMap<>();
                teacher.put("id",id); teacher.put("name","构造讲师"+id); teacher.put("extracted_text",text);
                teacher.put("field",""); teacher.put("intro",""); teacher.put("fee_rate",1800);
                teacher.put("manual_profile",manual);
                teacher.put("profile_json",Json.write(autoProfile.invoke(null,manual.isEmpty()?text:manual,teacher)));
                Map<String,Object> profile = (Map<String,Object>)profileForMatching.invoke(null,teacher);
                Object match = score.invoke(null,requirement,teacher,profile);
                Map<String,Object> candidate = (Map<String,Object>)toMap.invoke(match,teacher,profile);
                candidate.put("dispatch_fit",Map.of("local_priority",false));
                if (relevant.getBoolean(match)) {
                    candidates.add(candidate);
                    texts.put(id,(String)redact.invoke(null,(String)profile.get("_matching_text")));
                } else excluded.add(Map.of("id",id,"professional_score",candidate.get("professional_score")));
            }
            candidates.sort((a,b)->TeacherIntelligence.compareCandidates(a,b,false));
            List<Object> baseline = new ArrayList<>();
            for(Map<String,Object> candidate:candidates) baseline.add(candidate.get("teacher_id"));
            Map<String,Object> state = Map.of("status","not_requested");
            if (test.get("semantic_response") instanceof Map<?,?>) {
                Map<String,Object> response = new LinkedHashMap<>((Map<String,Object>)test.get("semantic_response"));
                List<Object> filtered = new ArrayList<>();
                for(Object row:(List<?>)response.get("results"))
                    if(texts.containsKey(((Number)((Map<?,?>)row).get("id")).longValue())) filtered.add(row);
                response.put("results",filtered);
                state=LocalSemantic.augment((String)redact.invoke(null,source),candidates,texts,request->response);
            }
            candidates.sort((a,b)->TeacherIntelligence.compareCandidates(a,b,false));
            List<Map<String,Object>> rows=new ArrayList<>();
            for(Map<String,Object> candidate:candidates) {
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("id",candidate.get("teacher_id")); row.put("score",candidate.get("score"));
                row.put("professional_score",candidate.get("professional_score")); row.put("semantic",candidate.get("semantic")); rows.add(row);
            }
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",test.get("id")); item.put("baseline_rank",baseline); item.put("hybrid_rank",rows);
            item.put("excluded_by_rules",excluded); item.put("semantic_state",state);
            item.put("focused_query",LocalSemantic.focusedQuery((String)redact.invoke(null,source)));
            output.add(item);
        }
        System.out.println(Json.write(Map.of("cases",output)));
    }
}
