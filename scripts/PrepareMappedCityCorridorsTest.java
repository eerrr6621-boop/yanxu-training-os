package com.training;

import java.util.*;
import java.nio.file.*;

/** Synthetic research-queue tests, not transport or geography verification. */
public final class PrepareMappedCityCorridorsTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static Map<String,Object> obj(Object... x){return PrepareMappedCityCorridors.obj(x);}
    static Map<String,Set<String>> directory=Map.of("甲省",Set.of("甲城","乙城"),"乙省",Set.of("乙城"));
    static Map<String,Object> sample(String a,String b,int m){return obj("from_station",a,"to_station",b,"minutes",m,"train","G123");}
    static Map<String,Object> pair(String a,String b,List<?> f,List<?> r){return obj("stations",List.of(a,b),"forward_samples",f,"reverse_samples",r,
        "observed_both_directions",!f.isEmpty()&&!r.isEmpty(),"both_directions_have_under240_sample",false,"city_admitted",false,"air_fallback_proven",false);}
    static Map<String,Object> input(int first,int second){return obj("schema","whole_corridor_pending_research_v1","new_city_admissions",0,"production_changed",false,
        "unordered_station_pairs",2,"distinct_station_names",3,"bidirectional_station_pairs",0,"both_direction_under240_station_pairs",0,
        "station_pairs",List.of(pair("甲东","乙北",List.of(sample("甲东","乙北",first)),List.of()),pair("甲东","乙南",List.of(),List.of(sample("乙南","甲东",second)))));}
    static Map<String,Object> station(String s,String city){return obj("station",s,"province","甲省","city",city,"scope","named_city_station",
        "review_status","first_read_only","source_ids",List.of("s1"),"basis","Synthetic source, no real geography claim");}
    static Map<String,Object> observation(){return obj("schema","station_city_observation_v1","review_status","first_read_only","production_adopted",false,
        "sources",List.of(obj("id","s1","url","https://example.invalid/fixture","actual_original_page_read",true)),
        "stations",new ArrayList<>(List.of(station("甲东","甲城"),station("乙北","乙城"),station("乙南","乙城"))));}
    static Map<String,Object> build(Map<String,Object> in,Map<String,Object> geo){return PrepareMappedCityCorridors.build(in,geo,directory);}
    static void rejected(Runnable action,String why){boolean failed=false;try{action.run();}catch(IllegalArgumentException e){failed=true;}check(failed,why);}
    @SuppressWarnings("unchecked") static Map<String,Object> first(Map<String,Object> out){return (Map<String,Object>)((List<?>)out.get("city_pairs")).get(0);}
    static boolean outputRejected(Path path)throws Exception{
        try{PrepareStationCityReview.privateNewOutput(path);return false;}
        catch(IllegalArgumentException e){return true;}
    }
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        var in=input(150,138);var geo=observation();String savedIn=Json.write(in),savedGeo=Json.write(geo);
        var out=build(in,geo);var p=first(out);
        check(out.get("under240_city_candidates").equals(1),"different return station may supply city reverse");
        check(out.get("bidirectional_city_pairs").equals(1),"both observed directions");
        check(p.get("different_stations_within_city").equals(true),"endpoint difference explicit");
        check(p.get("city_admitted").equals(false),"research does not admit");
        check(p.get("air_fallback_proven").equals(false),"no air proof");
        check(p.get("transfer_time_estimated").equals(false),"no invented transfers");
        check(out.get("production_changed").equals(false)&&out.get("new_city_admissions").equals(0),"not a production import");
        check(Json.write(in).equals(savedIn)&&Json.write(geo).equals(savedGeo),"inputs not mutated");
        for(int boundary:List.of(239,240,241)) {
            check(build(input(boundary,138),observation()).get("under240_city_candidates").equals(boundary<240?1:0),"strict outbound boundary");
            check(build(input(150,boundary),observation()).get("under240_city_candidates").equals(boundary<240?1:0),"strict reverse boundary");
        }
        var partial=observation();((List<?>)partial.get("stations")).remove(2);
        var one=build(in,partial);check(one.get("under240_city_candidates").equals(0),"missing mapping is not guessed from station name");
        check(one.get("unmapped_directed_samples").equals(1),"unmapped work retained");
        check(one.get("bidirectional_city_pairs").equals(0),"do not mirror forward");
        var within=observation();for(Object s:(List<?>)within.get("stations"))((Map<String,Object>)s).put("city","甲城");
        check(build(in,within).get("intra_city_directed_samples").equals(2),"same city not a second intercity route");
        check(((List<?>)build(in,within).get("city_pairs")).isEmpty(),"no self-city pair");
        for(String bad:List.of("county_station","unknown","main_urban_assumed")) {
            var g=observation();((Map<String,Object>)((List<?>)g.get("stations")).get(0)).put("scope",bad);
            rejected(()->build(in,g),"unknown scope rejected");
        }
        var duplicate=observation();((List<Object>)duplicate.get("stations")).add(station("甲东","乙城"));
        rejected(()->build(in,duplicate),"conflicting station mappings rejected");
        var unknown=observation();((Map<String,Object>)((List<?>)unknown.get("stations")).get(0)).put("city","未注册");
        rejected(()->build(in,unknown),"unregistered city rejected");
        var missing=observation();((Map<String,Object>)((List<?>)missing.get("stations")).get(0)).put("source_ids",List.of("absent"));
        rejected(()->build(in,missing),"source link required");
        var unread=observation();((Map<String,Object>)((List<?>)unread.get("sources")).get(0)).put("actual_original_page_read",false);
        rejected(()->build(in,unread),"search snippet not original read");
        var admitted=input(150,138);admitted.put("new_city_admissions",1);
        rejected(()->build(admitted,geo),"non-pending corpus rejected");
        rejected(()->build(input(0,138),geo),"invalid duration rejected");
        rejected(()->build(input(1440,138),geo),"unsupported day rollover rejected");
        var cross=observation();((Map<String,Object>)((List<?>)cross.get("stations")).get(2)).put("province","乙省");
        check(build(in,cross).get("bidirectional_city_pairs").equals(0),"same city spelling in different provinces never merged");
        Path temp=Files.createTempDirectory("mapped-city-output-test-");
        Path web=Files.createDirectory(temp.resolve("web")),hidden=Files.createDirectory(temp.resolve("private"));
        Path alias=Files.createSymbolicLink(temp.resolve("alias"),web),safeAlias=Files.createSymbolicLink(temp.resolve("safe-alias"),hidden);
        Path existing=Files.writeString(hidden.resolve("existing.json"),"original");
        try{
            check(outputRejected(web.resolve("out.json")),"public output rejected");
            check(outputRejected(alias.resolve("out.json")),"private-looking alias to public output rejected");
            check(outputRejected(existing),"existing output rejected without overwrite");
            check(Files.readString(existing).equals("original"),"original file unchanged");
            check(PrepareStationCityReview.privateNewOutput(safeAlias.resolve("out.json")).equals(hidden.toRealPath().resolve("out.json")),"private parent canonicalized");
            check(!Files.exists(web.resolve("out.json"))&&!Files.exists(hidden.resolve("out.json")),"validation never creates output");
        }finally{
            Files.delete(existing);Files.delete(safeAlias);Files.delete(alias);Files.delete(hidden);Files.delete(web);Files.delete(temp);
        }
        System.out.println("Mapped city research queue: "+checks+" synthetic assertions passed");
    }
}
