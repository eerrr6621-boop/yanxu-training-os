package com.training;

import java.util.*;
import static com.training.PrepareStationCityReview.*;

/** Synthetic data; does not independently review the actual station observations. */
public final class PrepareStationCityReviewTest {
    static int checks;
    static void ok(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    static void rejects(Runnable action,String reason){try{action.run();throw new AssertionError("accepted "+reason);}catch(IllegalArgumentException ex){ok(ex.getMessage().equals(reason));}}
    static Map<String,Object> pair(String a,String b,int out,int back){return obj("stations",List.of(a,b),
        "forward_samples",List.of(obj("from_station",a,"to_station",b,"minutes",out)),
        "reverse_samples",back==0?List.of():List.of(obj("from_station",b,"to_station",a,"minutes",back)),
        "observed_both_directions",back!=0,"both_directions_have_under240_sample",out<240&&back>0&&back<240,
        "city_admitted",false,"air_fallback_proven",false);}
    static Map<String,Object> fixture(){return obj("schema","whole_corridor_pending_research_v1","new_city_admissions",0,"production_changed",false,
        "station_pairs",new ArrayList<>(List.of(pair("南京南","宁波",239,239),pair("汉口","宜昌东",120,130),
            pair("宜兴","南京南",40,45),pair("宁波","天津西",240,100),pair("南京南","石柱县",200,0))),
        "unordered_station_pairs",5,"distinct_station_names",7,"bidirectional_station_pairs",4,"both_direction_under240_station_pairs",3);}
    @SuppressWarnings("unchecked") static Map<String,Object> first(Map<String,Object> input){return (Map<String,Object>)list(input.get("station_pairs")).get(0);}
    public static void main(String[] args){
        var input=fixture();String before=Json.write(input);var result=build(input,RegionDirectory.CITIES);
        ok(before.equals(Json.write(input)));ok(integer(result.get("bidirectional_under240_pairs"))==3);
        ok(integer(result.get("pairs_with_two_unique_name_suggestions"))==1);ok(integer(result.get("pairs_needing_explicit_mapping"))==2);
        ok(integer(result.get("new_city_admissions"))==0);ok(Boolean.FALSE.equals(result.get("production_changed")));
        ok(suggestions("汉口",RegionDirectory.CITIES).isEmpty());ok(suggestions("宜兴",RegionDirectory.CITIES).isEmpty());
        ok(suggestions("石柱县",RegionDirectory.CITIES).isEmpty());ok(suggestions("恩施",RegionDirectory.CITIES).isEmpty());
        ok(suggestions("宁波",RegionDirectory.CITIES).size()==1);ok(suggestions("上海虹桥",RegionDirectory.CITIES).size()==1);
        ok(suggestions("同名南",Map.of("甲省",Set.of("同名"),"乙省",Set.of("同名"))).size()==2);
        for(Object raw:list(result.get("stations"))){var station=map(raw);ok(Boolean.FALSE.equals(station.get("geography_verified")));ok(Boolean.FALSE.equals(station.get("second_reader_verified")));}
        for(Object raw:list(result.get("pairs"))){ok(Boolean.FALSE.equals(map(raw).get("city_admitted")));ok(Boolean.FALSE.equals(map(raw).get("air_fallback_proven")));}
        var bad=fixture();first(bad).put("city_admitted",true);rejects(()->build(bad,RegionDirectory.CITIES),"pending_pair_only");
        var air=fixture();first(air).put("air_fallback_proven",true);rejects(()->build(air,RegionDirectory.CITIES),"pending_pair_only");
        var flag=fixture();first(flag).put("both_directions_have_under240_sample",false);rejects(()->build(flag,RegionDirectory.CITIES),"threshold_flag_mismatch");
        var direction=fixture();first(direction).put("forward_samples",List.of(obj("from_station","宁波","to_station","南京南","minutes",239)));
        rejects(()->build(direction,RegionDirectory.CITIES),"sample_direction_mismatch");
        var duplicate=fixture();list(duplicate.get("station_pairs"));
        duplicate.put("station_pairs",List.of(first(duplicate),pair("宁波","南京南",239,239)));rejects(()->build(duplicate,RegionDirectory.CITIES),"duplicate_station_pair");
        var count=fixture();count.put("unordered_station_pairs",6);rejects(()->build(count,RegionDirectory.CITIES),"pair_count_mismatch");
        for(double invalid:new double[]{-1,.5,Double.NaN,Double.POSITIVE_INFINITY})rejects(()->integer(invalid),"integer_required");
        ok(!under(List.of(obj("from_station","甲","to_station","乙","minutes",240)),"甲","乙"));
        ok(under(List.of(obj("from_station","甲","to_station","乙","minutes",239)),"甲","乙"));
        System.out.println("PrepareStationCityReviewTest: "+checks+" synthetic checks passed");
    }
}
