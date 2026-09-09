package com.training;

import java.time.LocalDate;
import java.util.*;

/** One explicitly round-trip-scoped single-journey summary, NOT two observations.
 * Pure structural checks AFTER the existing protected source/review envelope.
 * Source-reader semantics are trusted annotations, never machine language proof. */
final class SharedCityDuration {
    static final String KIND="explicit_roundtrip_shared_v1";
    static final String METHOD="dual_review_explicit_roundtrip_shared_v1";
    static final String STATUS="shared_city_planning_reference";
    record Shared(Map<?,?> source,Map<?,?> readA,Map<?,?> readB,Map<?,?> cityA,Map<?,?> cityB,LocalDate reviewedOn){}
    private static final Set<String> CORE=Set.of("verification_method","source_id","city_a_id","city_b_id",
        "endpoint_a","endpoint_b","endpoint_scope","mapping_basis","transport_mode","route_scope",
        "published_on","effective_on","effective_until","service_state","status","duration",
        "direction_semantics","duration_scope");
    private static final Set<String> ROLES=Set.of("endpoint_a","endpoint_b","city_context_a","city_context_b",
        "context","roundtrip_context","duration");
    private static void need(boolean value,String reason){CityPlanningReference.need(value,reason);}
    private static Map<?,?> map(Object value){return CityPlanningReference.map(value);}
    private static String text(Map<?,?> value,String key){return CityPlanningReference.text(value,key);}
    private static LocalDate day(Object value){return CityPlanningReference.day(value);}
    private static void keys(Map<?,?> value,Set<String> expected){need(value.keySet().equals(expected),"shared_fields_missing_or_unknown");}

    static Map<String,Object> afterEnvelopeValidation(Shared input){
        need(input!=null&&input.reviewedOn()!=null,"shared_context_missing");
        CityPlanningReference.city(input.cityA());CityPlanningReference.city(input.cityB());
        need(!CityPlanningReference.same(input.cityA(),input.cityB()),"shared_cross_city_required");
        Map<?,?> source=input.source();CityPlanningReference.originalRestrictions(source);CityPlanningReference.url(text(source,"url"));
        LocalDate published=day(source.get("published_on"));need(!input.reviewedOn().isBefore(published),"shared_review_before_publication");
        Map<?,?> a=map(input.readA().get("canonical")),b=map(input.readB().get("canonical"));keys(a,CORE);keys(b,CORE);
        need(CityPlanningReference.same(a,b),"shared_core_read_disagreement");
        need(METHOD.equals(a.get("verification_method")),"shared_verification_method_invalid");
        need(!text(source,"source_id").isBlank()&&source.get("source_id").equals(a.get("source_id")),"shared_source_id_mismatch");
        need("explicit_roundtrip_context".equals(a.get("direction_semantics"))&&
            "single_journey_shared_summary".equals(a.get("duration_scope")),"shared_roundtrip_semantics_required");
        need("rail".equals(a.get("transport_mode"))&&Set.of("city_summary","station_corridor").contains(text(a,"route_scope")),"shared_mode_or_scope_invalid");
        need("active".equals(a.get("status"))&&Set.of("reported_operating","regular_diagram_reference","retrospective_rail_reference").contains(text(a,"service_state")),"shared_not_operating_reference");
        need(published.equals(day(a.get("published_on"))),"shared_source_date_mismatch");
        if(a.get("effective_on")!=null)day(a.get("effective_on"));
        if("regular_diagram_reference".equals(a.get("service_state")))need(a.get("effective_on")!=null,"shared_diagram_date_missing");
        if(a.get("effective_until")!=null)need(a.get("effective_on")!=null&&!day(a.get("effective_until")).isBefore(day(a.get("effective_on"))),"shared_effective_period_invalid");
        Map<?,?> scopes=map(a.get("endpoint_scope")),mapping=map(a.get("mapping_basis"));keys(scopes,Set.of("a","b"));keys(mapping,Set.of("a","b"));
        Map<String,String> units=CityPlanningReference.units(source);CuratedCityDuration.Duration duration=null;
        for(Map<?,?> fact:List.of(input.readA(),input.readB())){
            keys(fact,Set.of("source_id","city_a","city_b","canonical","spans","rationale"));
            need(source.get("source_id").equals(fact.get("source_id"))&&input.cityA().get("city").equals(fact.get("city_a"))&&input.cityB().get("city").equals(fact.get("city_b")),"shared_fact_pair_mismatch");
            need(!text(fact,"rationale").isBlank(),"shared_reader_rationale_missing");keys(map(fact.get("spans")),ROLES);
            var context=CuratedCityDuration.span(fact,units,"context");
            var roundtrip=CuratedCityDuration.span(fact,units,"roundtrip_context");
            var token=CuratedCityDuration.span(fact,units,"duration");
            CuratedCityDuration.inside(roundtrip,context);CuratedCityDuration.inside(token,roundtrip);
            // Necessary lexical guard only. Two source readers must bind the same pair
            // and the single-journey scope; 'two cities fastest' alone is insufficient.
            need(roundtrip.text().contains("往返")&&roundtrip.text().contains("单程")&&
                !roundtrip.text().matches("(?s).*(?:往返(?:总计|合计|共计)|来回(?:总计|合计|共计)).*"),"shared_explicit_roundtrip_single_journey_missing");
            for(String side:List.of("a","b")){
                Map<?,?> city=side.equals("a")?input.cityA():input.cityB();String endpoint=text(a,"endpoint_"+side),scope=text(scopes,side),name=text(city,"city");
                need(city.get("id").equals(a.get("city_"+side+"_id")),"shared_city_id_mismatch");
                var endpointSpan=CuratedCityDuration.span(fact,units,"endpoint_"+side);
                var cityContext=CuratedCityDuration.span(fact,units,"city_context_"+side);
                need(!endpoint.isBlank()&&endpointSpan.text().equals(endpoint),"shared_endpoint_span_mismatch");
                CuratedCityDuration.inside(endpointSpan,roundtrip);
                need("literal_city_prefix".equals(mapping.get(side)),"shared_external_city_mapping_not_supported");
                need(Set.of("city_summary","main_urban_station","urban_subcentre_station").contains(scope),"shared_endpoint_scope_unconfirmed");
                need("city_summary".equals(scope)?endpoint.equals(name):endpoint.startsWith(name)&&endpoint.length()>name.length(),"shared_endpoint_city_mismatch");
                need(cityContext.text().contains(endpoint),"shared_city_context_missing");
                if("urban_subcentre_station".equals(scope))need(cityContext.text().contains(name)&&cityContext.text().contains("副中心"),"shared_subcentre_context_missing");
                else need(!cityContext.text().contains("副中心"),"shared_subcentre_scope_erased");
            }
            boolean summary="city_summary".equals(scopes.get("a"))&&"city_summary".equals(scopes.get("b"));
            need((summary?"city_summary":"station_corridor").equals(a.get("route_scope")),"shared_route_scope_mismatch");
            // Clocks and bounds are outside this initial shared-summary type. Never
            // turn one city summary into two service observations or a proved limit.
            need(Set.of("reported_minutes","nominal_hour","nominal_half_hour","approximate").contains(text(map(a.get("duration")),"kind")),"shared_precision_unsupported");
            var current=CuratedCityDuration.scalarDuration(fact,map(a.get("duration")),units);
            need(duration==null||duration.equals(current),"shared_recomputed_read_disagreement");duration=current;
        }
        LocalDate due=published.plusDays(CityPlanningReference.SOURCE_DAYS);
        if(input.reviewedOn().plusDays(CityPlanningReference.REVIEW_DAYS).isBefore(due))due=input.reviewedOn().plusDays(CityPlanningReference.REVIEW_DAYS);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("city_a",input.cityA());result.put("city_b",input.cityB());
        for(String key:List.of("endpoint_a","endpoint_b","endpoint_scope","mapping_basis","route_scope","service_state","effective_on","effective_until","direction_semantics","duration_scope"))result.put(key,a.get(key));
        result.put("source_url",source.get("url"));result.put("source_published_on",published.toString());
        result.put("reviewed_on",input.reviewedOn().toString());result.put("review_due_on",due.toString());
        result.put("verification_method",METHOD);result.put("precision",duration.kind());
        Map<String,Object> value=new LinkedHashMap<>();duration.raw().forEach((key,item)->{if(!"token".equals(key))value.put(key.toString(),item);});
        result.put("duration",value);result.put("independent_direction_observations",0);
        result.put("value_is_proven_travel_upper_bound",false);result.put("transfer_time_estimated",false);
        result.put("semantic_annotations_are_language_proof",false);return result;
    }
    private SharedCityDuration(){}
}
