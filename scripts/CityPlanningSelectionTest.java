package com.training;

import java.util.*;

/** Synthetic backend contract only; no real routes, teachers, model or network. */
public final class CityPlanningSelectionTest {
    static int checks;
    static void check(boolean x,String message){checks++;if(!x)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") static Map<String,Object> mutable(Object x){return (Map<String,Object>)x;}
    static Map<String,Object> copy(Object x){return mutable(Json.parse(Json.write(x)));}
    static Map<String,Object> planning(String band) {
        Map<String,Object> p=new LinkedHashMap<>();
        p.put("status","planning_reference");p.put("purpose","prebid_city_planning_only");
        p.put("planning_band_id",band);p.put("time_score_applicable",false);p.put("transport_verified",false);
        p.put("air_fallback_trigger",false);p.put("rail_exclusion_complete",false);return p;
    }
    static Map<String,Object> fit(Map<String,Object> planning,Map<String,Object> route) {
        Map<String,Object> f=new LinkedHashMap<>();f.put("local_priority",false);f.put("planning_reference",planning);f.put("route_reference",route);return f;
    }
    static Map<String,Object> missing(){return new LinkedHashMap<>(Map.of("status","rail_reference_pending","eligibility","unknown","missing_directions",List.of("测试甲→测试乙","测试乙→测试甲")));}
    public static void main(String[] args) {
        DispatchPreference p=DispatchPreference.from(Map.of("training_province","安徽","training_city","合肥","training_mode","线下"),null);
        for(String band:List.of("planning_0_1h","planning_1_2h","planning_2_3h","planning_3_4h")) {
            Map<String,Object> route=missing(),advice=planning(band),fit=fit(advice,route);
            String before=Json.write(route),beforeAdvice=Json.write(advice);
            check(CityPlanningSelection.include(fit,p,240),"validated public planning band may support a labelled pre-bid pool");
            check(before.equals(Json.write(route))&&beforeAdvice.equals(Json.write(advice)),"strict result and original planning evidence never overwritten");
            check(Boolean.TRUE.equals(fit.get("planning_included"))&&fit.get("priority_score")==null,"no synthetic journey score");
            check(Boolean.FALSE.equals(fit.get("time_score_applicable")),"coarse policy band cannot become precise time score");
        }
        for(int max:List.of(0,60,120,180,239)) {
            check(!CityPlanningSelection.include(fit(planning("planning_3_4h"),missing()),p,max),"last coarse band cannot prove a lower configured limit");
        }
        check(CityPlanningSelection.underConfiguredLimit(planning("planning_0_1h"),61),"inclusive 60-minute band below 61");
        check(!CityPlanningSelection.underConfiguredLimit(planning("planning_0_1h"),60),"inclusive 60-minute band cannot prove strictly below 60");
        Map<String,Object> typed=planning("planning_3_4h");
        typed.put("selection_reference",CityPlanningReference.selectionReference(
            Map.of("precision","reported_minutes","reference_value_minutes_internal",227),
            Map.of("precision","approximate","reference_value_minutes_internal",239)));
        check(CityPlanningSelection.underConfiguredLimit(typed,240),"227/239-minute references satisfy 240 without reserve");
        check(!CityPlanningSelection.underConfiguredLimit(typed,239),"each direction must satisfy custom cutoff");
        for(String direction:List.of("outbound","inbound"))for(String mutation:List.of("unit","zero","negative","fraction","unknown_precision","false_open","missing","extra")){
            Map<String,Object> changed=copy(typed);
            Map<String,Object> values=mutable(changed.get("selection_reference")),leg=mutable(values.get(direction));
            switch(mutation){case "unit"->leg.put("unit","hour");case "zero"->leg.put("reference_minutes",0);case "negative"->leg.put("reference_minutes",-1);case "fraction"->leg.put("reference_minutes",90.5);case "unknown_precision"->leg.put("precision","exact");case "false_open"->leg.put("upper_exclusive",true);case "missing"->leg.remove("reference_minutes");case "extra"->leg.put("approved",true);}
            check(!CityPlanningSelection.underConfiguredLimit(changed,240),"malformed typed direction refuses: "+direction+"/"+mutation);
        }
        for(String mutation:List.of("basis","buffer","itinerary","missing_leg","extra")){
            Map<String,Object> changed=copy(typed),values=mutable(changed.get("selection_reference"));
            switch(mutation){case "basis"->values.put("basis","old_buffered");case "buffer"->values.put("buffer_applied",true);case "itinerary"->values.put("confirmed_itinerary",true);case "missing_leg"->values.remove("inbound");case "extra"->values.put("approved",true);}
            check(!CityPlanningSelection.underConfiguredLimit(changed,240),"malformed typed envelope refuses: "+mutation);
        }
        for(int maximum:List.of(-1,0,241,999))check(!CityPlanningSelection.underConfiguredLimit(typed,maximum),"invalid configured cutoff refuses");
        for(String status:List.of("city_reference_pending","outside_transport_limits","air_time_reference","rail_time_reference","reference_available","same_city","not_required","unknown"))
            check(!CityPlanningSelection.include(fit(planning("planning_0_1h"),new LinkedHashMap<>(Map.of("status",status,"eligibility","unknown"))),p,240),"unrecognized/strict failure cannot be overwritten: "+status);
        for(String reason:List.of("cross_library_revocation_or_integrity_failure","cross_library_threshold_conflict","newer_transport_evidence_requires_review","reference_not_within_limit","region_pending","reference_hash_mismatch"))
            check(!CityPlanningSelection.include(fit(planning("planning_0_1h"),new LinkedHashMap<>(Map.of("status","city_reference_pending","review_reason",reason))),p,240),"hard reference warning remains pending: "+reason);
        check(CityPlanningSelection.include(fit(planning("planning_1_2h"),new LinkedHashMap<>(Map.of("status","city_reference_pending","review_reason","reference_bounds_do_not_prove_limit"))),p,240),"approximate fact receives planning judgement only");
        for(String flag:List.of("transport_verified","time_score_applicable","air_fallback_trigger","rail_exclusion_complete")) {
            Map<String,Object> advice=planning("planning_1_2h");advice.put(flag,true);
            check(!CityPlanningSelection.include(fit(advice,missing()),p,240),"advisory cannot claim strict capability "+flag);
        }
        Map<String,Object> uncertain=fit(planning("planning_0_1h"),missing());uncertain.put("departure_uncertain",true);
        check(!CityPlanningSelection.include(uncertain,p,240),"adjacent course actual departure remains pending");
        Map<String,Object> broken=missing();broken.put("load_status","broken");
        check(!CityPlanningSelection.include(fit(planning("planning_0_1h"),broken),p,240),"file failure cannot be overridden");
        check(!CityPlanningSelection.include(fit(planning("planning_0_1h"),missing()),DispatchPreference.from(Map.of("training_mode","线上"),null),240),"online mode has no new travel gate");
        check(!CityPlanningSelection.include(fit(planning("unrecognized"),missing()),p,240),"unknown band never guessed");
        List<Map<String,Object>> candidates=new ArrayList<>();
        for(int i=1;i<=5;i++) {
            Map<String,Object> f=i==1?new LinkedHashMap<>(Map.of("local_priority",true)):fit(planning("planning_2_3h"),missing());
            Map<String,Object> c=new LinkedHashMap<>();c.put("teacher_id",(long)i);c.put("ranking_score",i==1?90:80);
            c.put("teacher_level",i==5?"特聘讲师":"讲师");c.put("dispatch_fit",f);candidates.add(c);
        }
        Map<String,Object> selected=NearbySelection.select(candidates,p,3);
        List<?> picks=(List<?>)selected.get("selected");
        check(picks.size()==5&&((Number)selected.get("ties_included")).intValue()==2,"planning pool includes all third-place score ties");
        check(((Number)selected.get("planning_pool_size")).intValue()==4&&((Number)selected.get("planning_selected_count")).intValue()==4,"separate planning coverage counts do not claim strict routes");
        check(((Map<?,?>)picks.get(0)).get("teacher_id").equals(1L)&&((Map<?,?>)picks.get(1)).get("teacher_id").equals(5L),"model score first, teacher level only breaks equal scores");
        check(((List<?>)selected.get("travel_pending")).isEmpty(),"validated planning teachers enter labelled initial-selection pool");
        System.out.println("City planning selection contract: "+checks+" assertions passed");
    }
}
