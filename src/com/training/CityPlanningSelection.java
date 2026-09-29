package com.training;

import java.util.*;

/** Admission to a labelled pre-bid planning pool, never to strict route verification. */
final class CityPlanningSelection {
    static final String SHARED_POLICY="explicit_roundtrip_shared_planning_v1";
    static void clearContextPlanningDecision(Map<String,Object> fit) {
        // Source/strict maps are never overwritten. Remove only this adapter's
        // request-owned fields, including when the next call opts out.
        if(ContextSharedPlanningAdapter.ownsPolicy(fit.get("planning_policy")))for(String field:List.of("planning_included","planning_reference_kind","planning_policy","priority_score","priority_label","time_score_applicable"))fit.remove(field);
        for(String field:List.of("context_planning_reference","context_planning_display","context_planning_band_id","context_planning_pending_reason"))fit.remove(field);
    }
    static void clearSharedPlanningDecision(Map<String,Object> fit) {
        // A reused candidate must be assessed again under this request's cutoff.
        // Clear only fields owned by this policy, never the source/strict maps.
        if(SHARED_POLICY.equals(fit.get("planning_policy")))for(String field:List.of("planning_included","planning_reference_kind","planning_policy","shared_planning_band_id","priority_score","priority_label","time_score_applicable"))fit.remove(field);
    }
    // This consumes a current protected Index result, not an alternative raw-file
    // loader. Its scalar is used only for a named planning policy, never a bound.
    static int sharedPlanningMinutes(Map<?,?> reference) {
        if(!SharedCityDuration.STATUS.equals(reference.get("status"))||!SharedCityDuration.KIND.equals(reference.get("reference_kind"))||
            !"prebid_city_planning_only".equals(reference.get("purpose"))||!Boolean.TRUE.equals(reference.get("protected_envelope_validated"))||
            !Boolean.FALSE.equals(reference.get("semantic_annotations_are_language_proof")))return -1;
        for(String flag:List.of("strict_eligibility","transport_verified","time_score_applicable","air_fallback_trigger","rail_exclusion_complete"))
            if(!Boolean.FALSE.equals(reference.get(flag)))return -1;
        if(!(reference.get("shared_reference") instanceof Map<?,?> shared))return -1;
        for(String field:List.of("outbound","inbound","return","selection_reference","planning_band_id","reference_minutes","duration_bounds"))
            if(reference.containsKey(field)||shared.containsKey(field))return -1;
        if(!SharedCityDuration.METHOD.equals(shared.get("verification_method"))||!"explicit_roundtrip_context".equals(shared.get("direction_semantics"))||
            !"single_journey_shared_summary".equals(shared.get("duration_scope"))||!(shared.get("independent_direction_observations") instanceof Number observations)||observations.doubleValue()!=0||
            !Boolean.FALSE.equals(shared.get("value_is_proven_travel_upper_bound"))||!Boolean.FALSE.equals(shared.get("transfer_time_estimated"))||
            !Boolean.FALSE.equals(shared.get("semantic_annotations_are_language_proof")))return -1;
        try {
            CityPlanningReference.url(CityPlanningReference.text(shared,"source_url"));
            var published=CityPlanningReference.day(shared.get("source_published_on"));var reviewed=CityPlanningReference.day(shared.get("reviewed_on"));
            var due=CityPlanningReference.day(shared.get("review_due_on"));var expected=published.plusDays(CityPlanningReference.SOURCE_DAYS);
            if(reviewed.plusDays(CityPlanningReference.REVIEW_DAYS).isBefore(expected))expected=reviewed.plusDays(CityPlanningReference.REVIEW_DAYS);
            if(reviewed.isBefore(published)||!due.equals(expected))return -1;
            Map<?,?> scopes=CityPlanningReference.map(shared.get("endpoint_scope")),mapping=CityPlanningReference.map(shared.get("mapping_basis"));
            if(!scopes.keySet().equals(Set.of("a","b"))||!mapping.keySet().equals(Set.of("a","b")))return -1;
            for(String side:List.of("a","b")){
                Map<?,?> city=CityPlanningReference.map(shared.get("city_"+side));CityPlanningReference.city(city);
                String endpoint=CityPlanningReference.text(shared,"endpoint_"+side),name=CityPlanningReference.text(city,"city");
                if(!Set.of("city_summary","main_urban_station","urban_subcentre_station").contains(scopes.get(side))||!"literal_city_prefix".equals(mapping.get(side))||
                    ("city_summary".equals(scopes.get(side))?!endpoint.equals(name):!endpoint.startsWith(name)||endpoint.length()<=name.length()))return -1;
            }
            boolean summary="city_summary".equals(scopes.get("a"))&&"city_summary".equals(scopes.get("b"));
            if(!(summary?"city_summary":"station_corridor").equals(shared.get("route_scope"))||
                !Set.of("reported_operating","regular_diagram_reference","retrospective_rail_reference").contains(shared.get("service_state")))return -1;
        }catch(RuntimeException invalid){return -1;}
        if(!(shared.get("duration") instanceof Map<?,?> duration)||!duration.keySet().equals(Set.of("kind","value","unit"))||
            !Objects.equals(shared.get("precision"),duration.get("kind"))||!(duration.get("value") instanceof Number value))return -1;
        double raw=value.doubleValue();if(!Double.isFinite(raw)||raw!=Math.rint(raw)||raw<=0||raw>2880)return -1;
        String kind=Objects.toString(duration.get("kind"),""),unit=Objects.toString(duration.get("unit"),"");
        int factor=switch(kind){case "reported_minutes","approximate"->"minute".equals(unit)?1:0;
            case "nominal_hour"->"hour".equals(unit)?60:0;case "nominal_half_hour"->"half_hour".equals(unit)?30:0;default->0;};
        return factor==0?-1:(int)raw*factor;
    }
    static boolean underSharedConfiguredLimit(Map<?,?> reference,int maximum) {
        int minutes=sharedPlanningMinutes(reference);return maximum>0&&maximum<=240&&minutes>0&&minutes<maximum;
    }
    static String sharedPlanningBand(Map<?,?> reference) {
        int minutes=sharedPlanningMinutes(reference);return minutes<=0||minutes>=240?"planning_range_pending":
            minutes<=60?"planning_0_1h":minutes<=120?"planning_1_2h":minutes<=180?"planning_2_3h":"planning_3_4h";
    }
    static boolean underConfiguredLimit(Map<?,?> reference,int maximum) {
        if(reference.containsKey("shared_reference")||reference.containsKey("reference_kind")||SharedCityDuration.STATUS.equals(reference.get("status")))return false;
        if(maximum<=0||maximum>240)return false;
        // New snapshots preserve the reference's own precision and open/closed
        // upper edge. Malformed new data must never fall back to a coarse band.
        if(reference.containsKey("selection_reference"))return reference.get("selection_reference") instanceof Map<?,?> values&&CityPlanningReference.selectionUnder(values,maximum);
        // These are policy bands, not measured journey times. The last band's
        // upper edge is exclusive; the other displayed edges are inclusive.
        return switch(Objects.toString(reference.get("planning_band_id"),"")) {
            case "planning_0_1h" -> maximum>60;
            case "planning_1_2h" -> maximum>120;
            case "planning_2_3h" -> maximum>180;
            case "planning_3_4h" -> maximum==240;
            default -> false;
        };
    }
    static boolean missingStrictProof(Map<?,?> strict) {
        if(strict.containsKey("load_status")||Boolean.TRUE.equals(strict.get("cancelled")))return false;
        String status=Objects.toString(strict.get("status"),"");
        if("city_reference_pending".equals(status))
            // An approximate published duration may lack a strict upper bound.
            // It may support a separate planning judgement, not a strict proof.
            return "reference_bounds_do_not_prove_limit".equals(strict.get("review_reason"));
        return "rail_reference_pending".equals(status)&&
            strict.get("missing_directions") instanceof List<?> missing&&!missing.isEmpty()&&
            !strict.containsKey("review_reason")&&!"outside".equals(strict.get("eligibility"));
    }
    static boolean include(Map<String,Object> fit,DispatchPreference preference,int maximum) {
        if(!preference.localPreferenceActive()||Boolean.TRUE.equals(fit.get("departure_uncertain"))||
            Boolean.TRUE.equals(fit.get("local_priority")))return false;
        if(!(fit.get("planning_reference") instanceof Map<?,?> reference)||
            !(fit.get("route_reference") instanceof Map<?,?> strict))return false;
        if(!"planning_reference".equals(reference.get("status"))||
            !"prebid_city_planning_only".equals(reference.get("purpose"))||
            !Boolean.FALSE.equals(reference.get("transport_verified"))||
            !Boolean.FALSE.equals(reference.get("time_score_applicable"))||
            !Boolean.FALSE.equals(reference.get("air_fallback_trigger"))||
            !Boolean.FALSE.equals(reference.get("rail_exclusion_complete")))return false;
        if(!missingStrictProof(strict)||!underConfiguredLimit(reference,maximum))return false;
        fit.put("planning_included",true);fit.put("priority_score",null);
        fit.put("time_score_applicable",false);fit.put("priority_label","铁路初筛 · 公开旅时参考");
        return true;
    }
    static boolean includeSharedPlanning(Map<String,Object> fit,DispatchPreference preference,int maximum) {
        clearSharedPlanningDecision(fit);
        if(!preference.localPreferenceActive()||Boolean.TRUE.equals(fit.get("departure_uncertain"))||Boolean.TRUE.equals(fit.get("local_priority")))return false;
        if(!(fit.get("planning_reference") instanceof Map<?,?> reference)||!(fit.get("route_reference") instanceof Map<?,?> strict)||
            "outside".equals(strict.get("eligibility"))||"air".equals(strict.get("eligibility"))||Boolean.TRUE.equals(strict.get("rail_exclusion_complete"))||
            !missingStrictProof(strict)||!underSharedConfiguredLimit(reference,maximum))return false;
        fit.put("planning_included",true);fit.put("planning_reference_kind",SharedCityDuration.KIND);fit.put("planning_policy",SHARED_POLICY);
        fit.put("shared_planning_band_id",sharedPlanningBand(reference));fit.put("priority_score",null);fit.put("time_score_applicable",false);
        fit.put("priority_label","铁路初筛 · 往返单程共享参考");return true;
    }
    /** Explicit new policy only. A response Map cannot substitute for the validated,
     * current-request Catalog; ordinary/strict source failures remain authoritative. */
    static boolean includeContextPlanning(Map<String,Object> fit,DispatchPreference preference,int maximum,
            ContextSharedPlanningAdapter.Catalog catalog,java.time.LocalDate requestDate) {
        return includeContext(fit,preference,maximum,catalog,null,requestDate);
    }
    static boolean includeRoutedContextPlanning(Map<String,Object> fit,DispatchPreference preference,int maximum,
            ContextSharedPlanningAdapter.RequestCatalog routed,java.time.LocalDate requestDate) {
        return includeContext(fit,preference,maximum,null,routed,requestDate);
    }
    private static boolean includeContext(Map<String,Object> fit,DispatchPreference preference,int maximum,
            ContextSharedPlanningAdapter.Catalog catalog,ContextSharedPlanningAdapter.RequestCatalog routed,java.time.LocalDate requestDate) {
        clearContextPlanningDecision(fit);
        boolean current=routed!=null?routed.valid()&&routed.matchesAsOf(requestDate):catalog!=null&&catalog.valid()&&catalog.matchesAsOf(requestDate);
        if(!preference.localPreferenceActive()||Boolean.TRUE.equals(fit.get("departure_uncertain"))||Boolean.TRUE.equals(fit.get("local_priority")))return false;
        if(!(fit.get("route_reference") instanceof Map<?,?> strict)||!missingStrictProof(strict)||
            "outside".equals(strict.get("eligibility"))||"air".equals(strict.get("eligibility"))||
            Boolean.TRUE.equals(strict.get("rail_exclusion_complete"))||Boolean.TRUE.equals(strict.get("transport_verified")))return false;
        if(fit.containsKey("planning_reference")){
            if(!(fit.get("planning_reference") instanceof Map<?,?> old))return false;
            if(!old.isEmpty()&&!("planning_pending".equals(old.get("status"))&&
                Set.of("city_pair_unobserved","planning_library_not_configured").contains(Objects.toString(old.get("reason"),""))))return false;
        }
        String province=Objects.toString(fit.get("base_province"),""),city=Objects.toString(fit.get("base_city"),"");
        if(!RegionDirectory.known(province,city))return false;
        if(!current){if(routed!=null)fit.put("context_planning_pending_reason",routed.valid()?"context_request_date_mismatch":routed.reason());return false;}
        Map<String,Object> decision=routed!=null?routed.select(province,city,preference.province,preference.city,maximum):catalog.select(province,city,preference.province,preference.city,maximum);
        if(!Boolean.TRUE.equals(decision.get("planning_pool_eligible"))){if(routed!=null&&!"city_pair_unobserved".equals(decision.get("reason")))fit.put("context_planning_pending_reason",decision.get("reason"));return false;}
        Map<?,?> reference=CityPlanningReference.map(decision.get("reference")),observation=CityPlanningReference.map(reference.get("shared_observation"));
        int minutes=CityPlanningReference.number(observation.get("reference_minutes"));
        Map<?,?> a=CityPlanningReference.map(reference.get("city_a")),b=CityPlanningReference.map(reference.get("city_b"));
        boolean baseline=ContextSharedPlanningAdapter.BASELINE_KIND.equals(reference.get("reference_kind"));
        String duration=CityPlanningReference.text(observation,"original_duration_text");
        String qualifier="reported_fastest".equals(observation.get("qualifier"))?"报道最快":baseline?"报道现有旅时":"";
        // One numeric observation remains solely in context_planning_reference.
        // These strings are plain display data, not HTML or a second timed leg.
        Map<String,Object> display=new LinkedHashMap<>();display.put("version",baseline?"context-baseline-planning-display-v2":"context-shared-planning-display-v1");
        display.put("label","铁路出行参考 · "+qualifier+duration);
        display.put("endpoint_label",a.get("city")+"—"+b.get("city")+"（未指定车站）");
        display.put("notice","用于投标前初筛，实际出行待核，市内接驳另行确认。");
        display.put("basis_note",baseline?"报道用双向符号给出现有旅时基线，未来提速值不采用；未分别记录去返程。":"报道在往返语境中给出一份旅时参考，未分别记录去返程。");
        fit.put("planning_included",true);fit.put("planning_reference_kind",reference.get("reference_kind"));fit.put("planning_policy",decision.get("policy"));
        fit.put("context_planning_reference",reference);fit.put("context_planning_display",Collections.unmodifiableMap(display));
        fit.put("context_planning_band_id",minutes<=60?"planning_0_1h":minutes<=120?"planning_1_2h":minutes<=180?"planning_2_3h":"planning_3_4h");
        fit.put("priority_score",null);fit.put("time_score_applicable",false);fit.put("priority_label",baseline?"铁路初筛 · 报道现有旅时参考":"铁路初筛 · 城市往返语境参考");return true;
    }
    private CityPlanningSelection() {}
}
