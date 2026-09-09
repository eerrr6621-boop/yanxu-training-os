package com.training;

import java.nio.file.*;
import java.util.*;
import static com.training.CuratedPlanningIntegrationTest.*;
import static com.training.SharedCityDurationTest.DAY;

/** Pure synthetic selection contracts; optional protected real public fixture.
 * No teacher records, network, model calls or modifications to input libraries. */
public final class SharedCityPlanningSelectionTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static DispatchPreference preference(){return DispatchPreference.from(Map.of("training_province","河北","training_city","秦皇岛","training_mode","线下"),null);}
    static Map<String,Object> base(){return SharedCityDurationTest.query(SharedCityDurationTest.fixture(),DAY);}
    static Map<String,Object> scalar(Map<String,Object> base,String kind,int value,String unit){Map<String,Object> p=copy(base),s=m(p.get("shared_reference"));s.put("precision",kind);s.put("duration",obj("kind",kind,"value",value,"unit",unit));return p;}
    static Map<String,Object> fit(Map<String,Object> p){return CityPlanningSelectionTest.fit(p,CityPlanningSelectionTest.missing());}
    static Map<String,Object> candidate(long id,Map<String,Object> p,Integer score,String level){Map<String,Object> c=obj("teacher_id",id,"dispatch_fit",fit(p),"teacher_level",level,"ranking_score",100-id);if(score!=null)c.put("model_score",score);return c;}
    static void rejected(Map<String,Object> p,String why){check(!CityPlanningSelection.includeSharedPlanning(fit(p),preference(),240),why);}
    static void shapeTests(Map<String,Object> p){
        for(String key:List.of("strict_eligibility","transport_verified","time_score_applicable","air_fallback_trigger","rail_exclusion_complete","semantic_annotations_are_language_proof")){
            Map<String,Object> q=copy(p);q.put(key,true);rejected(q,"no strict capability "+key);
            q=copy(p);q.remove(key);rejected(q,"missing capability "+key);
        }
        for(String field:List.of("outbound","inbound","return","selection_reference","planning_band_id","reference_minutes","duration_bounds"))for(boolean outer:List.of(true,false)){
            Map<String,Object> q=copy(p);(outer?q:m(q.get("shared_reference"))).put(field,90);rejected(q,"no mirrored or bound field "+field+"/"+outer);
        }
        for(String field:List.of("status","reference_kind","purpose","protected_envelope_validated","shared_reference")){
            Map<String,Object> q=copy(p);q.remove(field);rejected(q,"missing top field "+field);
        }
        for(String field:List.of("duration","precision","verification_method","direction_semantics","duration_scope","independent_direction_observations","value_is_proven_travel_upper_bound","transfer_time_estimated","semantic_annotations_are_language_proof","source_url","source_published_on","reviewed_on","review_due_on","endpoint_scope","mapping_basis","city_a","city_b","endpoint_a","endpoint_b","route_scope","service_state")){
            Map<String,Object> q=copy(p);m(q.get("shared_reference")).remove(field);rejected(q,"missing shared field "+field);
        }
        for(Object value:List.of(0,-1,90.5,"90",Double.NaN,Double.POSITIVE_INFINITY,Long.MAX_VALUE)){
            Map<String,Object> q=copy(p);m(m(q.get("shared_reference")).get("duration")).put("value",value);rejected(q,"invalid scalar "+value);
        }
        for(String mutation:List.of("unit","extra","kind","precision","observation","bound","scope","url","date")){
            Map<String,Object> q=copy(p),s=m(q.get("shared_reference")),d=m(s.get("duration"));
            switch(mutation){case "unit"->d.put("unit","hour");case "extra"->d.put("approved",true);case "kind"->d.put("kind","exact");case "precision"->s.put("precision","reported_minutes");case "observation"->s.put("independent_direction_observations",2);case "bound"->s.put("value_is_proven_travel_upper_bound",true);case "scope"->m(s.get("endpoint_scope")).put("a","county");case "url"->s.put("source_url","javascript:alert(1)");case "date"->s.put("review_due_on","2030-09-08");}
            rejected(q,"invalid shape "+mutation);
        }
    }
    static void poolTests(Map<String,Object> p){
        List<Map<String,Object>> rows=new ArrayList<>();
        rows.add(candidate(1,scalar(p,"approximate",50,"minute"),70,"讲师"));
        rows.add(candidate(2,scalar(p,"approximate",60,"minute"),70,"讲师"));
        rows.add(candidate(3,scalar(p,"approximate",70,"minute"),70,"讲师"));
        rows.add(candidate(4,scalar(p,"approximate",239,"minute"),90,"讲师"));
        rows.add(candidate(5,p,80,"特聘讲师"));rows.add(candidate(6,p,80,"高级讲师"));rows.add(candidate(7,p,80,"讲师"));
        Map<String,Object> result=NearbySelection.select(rows,preference(),3);
        check(result.get("pool_size").equals(7),"do not stop after three closer candidates");
        check(l(result.get("selected")).stream().map(x->m(x).get("teacher_id")).toList().equals(List.of(4L,5L,6L,7L)),"whole-pool model score, grade ordering and cutoff ties");
        check(((Number)result.get("shared_planning_pool_size")).intValue()==7&&((Number)result.get("shared_planning_selected_count")).intValue()==4,"shared candidate counts separate from observations");
        check(result.get("ties_included").equals(1),"all cutoff ties retained");
        for(Map<String,Object> row:rows){Map<String,Object> f=m(row.get("dispatch_fit"));check(f.get("priority_score")==null&&Boolean.FALSE.equals(f.get("time_score_applicable")),"no travel time score");}
        result=NearbySelection.select(List.of(candidate(1,p,80,"讲师"),candidate(2,p,null,"特聘讲师")),preference(),3);
        check(l(result.get("scoring_pending")).size()==1&&l(result.get("selected")).size()==1,"unscored shared candidate follows existing model-pending rule");
        for(Map<String,Object> q:List.of(scalar(p,"approximate",240,"minute"),scalar(p,"approximate",300,"minute"),obj("status","planning_pending"))){
            Map<String,Object> c=candidate(1,q,99,"特聘讲师"),strict=m(m(c.get("dispatch_fit")).get("route_reference"));String before=Json.write(strict);
            result=NearbySelection.select(List.of(c),preference(),3);
            check(l(result.get("selected")).isEmpty()&&l(result.get("travel_pending")).size()==1&&l(result.get("air_candidates")).isEmpty(),"missing/long shared cannot manufacture air fallback");
            check(before.equals(Json.write(strict))&&!strict.containsKey("rail_exclusion_complete"),"strict/exclusion evidence untouched on failure");
        }
        String previous=System.getProperty("dispatch.nearby.max.minutes");try{
            for(int limit:List.of(90,91,240,999)){
                System.setProperty("dispatch.nearby.max.minutes",""+limit);result=NearbySelection.select(List.of(candidate(1,p,80,"讲师")),preference(),3);
                check(l(result.get("selected")).size()==(limit==90?0:1),"configured limit integration "+limit);
                check(((Number)result.get("maximum_nearby_minutes")).intValue()<=240,"hard cap never expanded");
            }
        }finally{if(previous==null)System.clearProperty("dispatch.nearby.max.minutes");else System.setProperty("dispatch.nearby.max.minutes",previous);}
    }
    public static void main(String[] args)throws Exception{
        Map<String,Object> p=base(),f=fit(p),strict=m(f.get("route_reference"));String sourceBefore=Json.write(p),strictBefore=Json.write(strict);
        check(!CityPlanningSelection.include(f,preference(),240),"legacy include still refuses shared");
        check(CityPlanningSelection.includeSharedPlanning(f,preference(),240),"explicit shared planning admission");
        check(CityPlanningSelection.SHARED_POLICY.equals(f.get("planning_policy"))&&SharedCityDuration.KIND.equals(f.get("planning_reference_kind")),"separate named policy and kind");
        check(sourceBefore.equals(Json.write(p))&&strictBefore.equals(Json.write(strict)),"no source or strict mutation");
        check("planning_1_2h".equals(f.get("shared_planning_band_id")),"approx90 has a display-only planning band");
        check(!CityPlanningSelection.includeSharedPlanning(f,preference(),90)&&!f.containsKey("planning_included"),"reused fit cannot retain prior cutoff admission");
        for(String state:List.of("uncertain","local","online")){
            Map<String,Object> c=candidate(1,p,80,"讲师");NearbySelection.select(List.of(c),preference(),3);Map<String,Object> reused=m(c.get("dispatch_fit"));
            if(state.equals("uncertain"))reused.put("departure_uncertain",true);if(state.equals("local"))reused.put("local_priority",true);
            NearbySelection.select(List.of(c),state.equals("online")?DispatchPreference.from(Map.of("training_mode","线上"),null):preference(),3);
            check(!reused.containsKey("planning_policy")&&!reused.containsKey("planning_included"),"new request clears stale shared judgement: "+state);
        }
        for(int max:List.of(-1,0,90,241,999))check(!CityPlanningSelection.underSharedConfiguredLimit(p,max),"strict/invalid cutoff "+max);
        check(CityPlanningSelection.underSharedConfiguredLimit(p,91),"approx90 strictly below91 for planning only");
        check(CityPlanningSelection.underSharedConfiguredLimit(scalar(p,"approximate",239,"minute"),240),"approx239 planning qualifies");
        check(!CityPlanningSelection.underSharedConfiguredLimit(scalar(p,"approximate",240,"minute"),240),"approx240 not below240");
        for(String kind:List.of("reported_minutes","approximate","nominal_hour","nominal_half_hour")){
            int value=kind.equals("nominal_hour")?2:kind.equals("nominal_half_hour")?3:90;String unit=kind.equals("nominal_hour")?"hour":kind.equals("nominal_half_hour")?"half_hour":"minute";
            Map<String,Object> q=scalar(p,kind,value,unit);check(CityPlanningSelection.underSharedConfiguredLimit(q,240),"accepted original precision "+kind);check(!CityPlanningSelection.underConfiguredLimit(q,240),"no old-type coercion "+kind);
        }
        shapeTests(p);
        for(String key:List.of("departure_uncertain","local_priority")){Map<String,Object> q=fit(p);q.put(key,true);check(!CityPlanningSelection.includeSharedPlanning(q,preference(),240),"uncertainty/local guard "+key);}
        check(!CityPlanningSelection.includeSharedPlanning(fit(p),DispatchPreference.from(Map.of("training_mode","线上"),null),240),"online unchanged");
        for(Map<String,Object> route:List.of(obj("status","city_reference_pending","review_reason","reference_bounds_do_not_prove_limit","eligibility","outside"),obj("status","rail_reference_pending","missing_directions",List.of("x"),"load_status","broken"),obj("status","rail_reference_pending","missing_directions",List.of("x"),"cancelled",true),obj("status","city_reference_pending","review_reason","reference_hash_mismatch"),obj("status","air_time_reference","eligibility","air"))){
            check(!CityPlanningSelection.includeSharedPlanning(CityPlanningSelectionTest.fit(p,route),preference(),240),"hard failure/independent air not overwritten");
        }
        check(CityPlanningSelection.includeSharedPlanning(CityPlanningSelectionTest.fit(p,obj("status","city_reference_pending","review_reason","reference_bounds_do_not_prove_limit")),preference(),240),"missing upper bound supports only new planning policy");
        for(String field:List.of("eligibility","rail_exclusion_complete")){
            Map<String,Object> route=CityPlanningSelectionTest.missing();route.put(field,field.equals("eligibility")?"air":true);
            check(!CityPlanningSelection.includeSharedPlanning(CityPlanningSelectionTest.fit(p,route),preference(),240),"conflicting air/exclusion claims remain pending");
        }
        Map<String,Object> raw=SharedCityDurationTest.fixture();
        rejected(SharedCityDurationTest.query(raw,DAY.plusDays(91)),"review expiry from protected lookup");
        rejected(SharedCityDurationTest.query(raw,DAY.plusDays(366)),"source expiry from protected lookup");
        rejected(SharedCityDurationTest.query(raw,DAY.minusDays(1)),"future publication from protected lookup");
        Map<String,Object> future=copy(raw);SharedCityDurationTest.bothCore(future,c->{c.put("service_state","regular_diagram_reference");c.put("effective_on",DAY.plusDays(1).toString());});rehash(future);rejected(SharedCityDurationTest.query(future,DAY),"not-yet-effective reference remains pending");
        Map<String,Object> withdrawn=copy(raw);snapshot(withdrawn).put("status","withdrawn");rehash(withdrawn);rejected(SharedCityDurationTest.query(withdrawn,DAY),"withdrawal remains pending");
        rejected(CityPlanningReference.lookup(raw,"河北","不存在","北京","北京",DAY),"unknown city remains pending");
        poolTests(p);
        if(args.length==2){
            Path file=Path.of(args[0]),anchor=Path.of(args[0]+".integrity.json");String hash=CityPlanningReference.rawHash(Files.readString(file)),anchorHash=CityPlanningReference.rawHash(Files.readString(anchor));
            var index=CityPlanningReference.index(file,DAY);Map<String,Object> real=index.lookup("北京","北京","河北","秦皇岛"),reverse=index.lookup("河北","秦皇岛","北京","北京");
            check(index.sharedPairCount()==1&&index.directionCount()==0&&real==reverse,"real one fact, zero independent directional observations");
            check("approximate".equals(m(real.get("shared_reference")).get("precision")),"real approximation retained");
            check("urban_subcentre_station".equals(m(m(real.get("shared_reference")).get("endpoint_scope")).get("a")),"real subcentre retained");
            Map<String,Object> selected=NearbySelection.select(List.of(candidate(1,real,80,"讲师")),preference(),3);
            check(l(selected.get("selected")).size()==1,"real shared fact admits synthetic teacher to planning pool");
            check(CityPlanningSelection.includeSharedPlanning(fit(reverse),DispatchPreference.from(Map.of("training_province","北京","training_city","北京","training_mode","线下"),null),240),"reverse request uses same single fact");
            check(hash.equals(CityPlanningReference.rawHash(Files.readString(file)))&&anchorHash.equals(CityPlanningReference.rawHash(Files.readString(anchor))),"real input and anchor unchanged");
            Files.writeString(Path.of(args[1]),Json.write(obj("synthetic_teacher_only",true,"source_file_sha256",hash,"anchor_sha256",anchorHash,"shared_pairs",1,"independent_directions",0,"reverse_same_fact",true,"selection",selected))+"\n",StandardOpenOption.CREATE_NEW);
        }
        System.out.println("Shared city planning selection: "+checks+" assertions passed");
    }
}
