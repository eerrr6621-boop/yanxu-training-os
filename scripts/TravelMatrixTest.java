package com.training;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Entirely synthetic fixtures; never loads supplier research or local business tables. */
public final class TravelMatrixTest {
    static int count;
    static final LocalDate DAY=LocalDate.of(2026,9,7);
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);count++;}
    static Map<String,Object> base(boolean reverse) {
        return new LinkedHashMap<>(Map.of("from_province",reverse?"北京":"江苏","from_city",reverse?"北京":"南京",
            "to_province",reverse?"江苏":"北京","to_city",reverse?"南京":"北京","source_url","https://example.test/gray-fixture",
            "source_service_date",DAY.toString(),"observed_on",DAY.toString(),"usage_authorized",true));
    }
    static Map<String,Object> audit(boolean reverse,String mode,int minutes) {
        Map<String,Object> r=base(reverse);r.put("scope",mode.equals("rail")?"all_city_stations_and_rail_itineraries":"all_city_airports_nonstop_services");
        r.put("coverage_complete",true);r.put("minimum_minutes",minutes);return r;
    }
    static Map<String,Object> flight(boolean reverse,int minutes) {
        Map<String,Object> r=base(reverse);r.put("flight_no",reverse?"MU1235":"MU1234");r.put("from_airport_code",reverse?"PEK":"NKG");r.put("to_airport_code",reverse?"NKG":"PEK");
        r.put("nonstop",true);r.put("stops",0);r.put("airport_city_mapping_checked",true);r.put("departure_time","08:00");
        r.put("arrival_time",LocalTime.of(8,0).plusMinutes(minutes).toString());r.put("arrival_day_offset",0);r.put("flight_minutes",minutes);
        r.put("service_type","passenger");r.put("service_mode","air");r.put("cancelled",false);r.put("timezone","Asia/Shanghai");return r;
    }
    static Map<String,Object> rail(boolean reverse,int minutes) {
        Map<String,Object> r=base(reverse);r.put("status","observed_timetable");
        r.put("samples",List.of(Map.of("train_no","G1","from_station",reverse?"北京南":"南京南","to_station",reverse?"南京南":"北京南",
            "departure_time","08:00","arrival_time",LocalTime.of(8,0).plusMinutes(minutes).toString(),"arrival_day_offset",0,"rail_minutes",minutes,"station_city_mapping_checked",true)));return r;
    }
    static Map<String,Object> data(int out,int back) {
        return new LinkedHashMap<>(Map.of("version","transport-matrix-v1","rail_checks",List.of(audit(false,"rail",300),audit(true,"rail",300)),"flights",List.of(flight(false,out),flight(true,back))));
    }
    static DispatchPreference pref(){return DispatchPreference.from(Map.of("training_province","北京","training_city","北京","training_mode","线下"),null);}
    static Map<String,Object> resolve(List<?> rails,Map<?,?> data){return TravelMatrix.resolve(rails,data,Map.of("base_province","江苏","base_city","南京"),pref(),DAY);}
    static Map<String,Object> c(long id,int score,Map<String,Object> route) {
        return new LinkedHashMap<>(Map.of("teacher_id",id,"name","【灰度测试】交通"+id,"model_score",score,"teacher_level","讲师","recommendation_score",score,"dispatch_fit",new LinkedHashMap<>(Map.of("local_priority",false,"route_reference",route))));
    }
    @SuppressWarnings("unchecked") static void runChecks(Path fixture) throws Exception {
        Map<String,Object> ready=data(179,150);
        check("air".equals(resolve(List.of(),ready).get("eligibility")),"179-minute independently verified nonstop directions qualify only after complete railway proof");
        for(int duration:List.of(180,181))check("unknown".equals(resolve(List.of(),data(duration,150)).get("eligibility")),"Slow flight sample does not prove absence of shorter flight "+duration);
        Map<String,Object> missing=data(150,150);missing.remove("rail_checks");
        check("unknown".equals(resolve(List.of(rail(false,300),rail(true,320)),missing).get("eligibility")),"Two slow railway samples are not complete rail-exclusion proof");
        check("rail".equals(resolve(List.of(rail(false,239),rail(true,239)),ready).get("eligibility")),"Known sub-four-hour rail witness overrides contradictory slow-rail audit");
        check("unknown".equals(resolve(List.of(rail(false,240),rail(true,240)),missing).get("eligibility")),"240-minute sample not included and cannot silently trigger air");
        Map<String,Object> invalid=data(150,150);Map<String,Object> partial=audit(false,"rail",300);partial.put("coverage_complete",false);invalid.put("rail_checks",List.of(partial,audit(true,"rail",300)));
        check("unknown".equals(resolve(List.of(),invalid).get("eligibility")),"Partial or incomplete pagination cannot prove railway beyond limit");
        for(Map<String,?> mutation:List.of(Map.of("stops",1),Map.of("nonstop",false),Map.of("airport_city_mapping_checked",false),Map.of("usage_authorized",false),Map.of("source_url","javascript:alert(1)"),Map.of("source_service_date","2026-07-01"),Map.of("arrival_time","09:31"),Map.of("flight_minutes",0),Map.of("flight_minutes",90.5),Map.of("service_type","cargo"),Map.of("service_mode","surface"),Map.of("cancelled",true),Map.of("timezone","UTC"))) {
            Map<String,Object> f=flight(false,90);f.putAll(mutation);Map<String,Object> d=data(90,90);d.put("flights",List.of(f,flight(true,90)));
            check("unknown".equals(resolve(List.of(),d).get("eligibility")),"Malformed/stale/transfer flight rejected: "+mutation);
        }
        invalid=data(90,90);invalid.put("flights",List.of(flight(false,90)));
        check("unknown".equals(resolve(List.of(),invalid).get("eligibility")),"Return flight not fabricated from outbound");
        invalid.put("air_checks",List.of(audit(false,"air",200),audit(true,"air",200)));
        check("unknown".equals(resolve(List.of(),invalid).get("eligibility")),"Negative air audit conflicts with existing short outbound witness");
        invalid.put("flights",List.of());
        check("outside".equals(resolve(List.of(),invalid).get("eligibility")),"Only complete compatible rail and air checks can conclude outside");
        for(boolean reverse:List.of(false,true)) for(boolean absent:List.of(false,true)) {
            Map<String,Object> contradictory=audit(reverse,"air",200);if(absent) contradictory.put("no_service",true);
            Map<String,Object> d=data(90,90);d.put("flights",List.of(flight(reverse,90)));
            d.put("air_checks",List.of(contradictory,audit(!reverse,"air",200)));
            check("unknown".equals(resolve(List.of(),d).get("eligibility")),"Either-direction short-flight contradiction remains unknown, no_service="+absent);
        }
        for(boolean reverseOrder:List.of(false,true)) {
            List<Map<String,Object>> checks=new ArrayList<>(List.of(audit(false,"rail",300),audit(false,"rail",100),audit(true,"rail",300)));
            if(reverseOrder)Collections.reverse(checks);Map<String,Object> d=data(90,90);d.put("rail_checks",checks);
            check("unknown".equals(resolve(List.of(),d).get("eligibility")),"Conflicting complete checks never depend on file order");
        }
        Map<String,Object> noRail=audit(false,"rail",300);noRail.put("no_service",true);ready.put("rail_checks",List.of(noRail,audit(true,"rail",300)));
        check("unknown".equals(resolve(List.of(),ready).get("eligibility")),"No railway is not assigned a fake long duration or silently new air policy");
        Map<String,Object> air=resolve(List.of(),data(90,90)),r=resolve(List.of(rail(false,200),rail(true,200)),Map.of());
        List<Map<String,Object>> candidates=List.of(c(1,30,r),c(2,40,r),c(3,95,air),c(4,95,air));
        Map<String,Object> selection=NearbySelection.select(candidates,pref(),3);
        List<Map<String,Object>> selected=(List<Map<String,Object>>)selection.get("selected");
        check(selected.stream().map(v->v.get("teacher_id")).toList().equals(List.of(2L,1L,3L,4L)),"Air fills shortage without displacing rail seats; third-place air ties all retained");
        candidates=List.of(c(1,30,r),c(2,40,r),c(3,50,r),c(4,99,air));
        check(((List<Map<String,Object>>)NearbySelection.select(candidates,pref(),3).get("selected")).stream().noneMatch(v->v.get("teacher_id").equals(4L)),"Air cannot displace a sufficient rail pool");
        String maximum=System.getProperty("dispatch.nearby.max.minutes");
        try {
            System.setProperty("dispatch.nearby.max.minutes","0");
            check("unknown".equals(resolve(List.of(),data(90,90)).get("eligibility")),"Cross-city pause includes airline resolver");
            check(((List<?>)NearbySelection.select(List.of(c(1,90,air)),pref(),3).get("selected")).isEmpty(),"Cross-city pause cannot be bypassed by preexisting air result");
            System.setProperty("dispatch.nearby.max.minutes","180");
            check("unknown".equals(resolve(List.of(rail(false,200),rail(true,200)),data(90,90)).get("eligibility")),"Reduced policy limit does not fall through to air or disagree with catalog");
        } finally {if(maximum==null)System.clearProperty("dispatch.nearby.max.minutes");else System.setProperty("dispatch.nearby.max.minutes",maximum);}
        String original=System.getProperty("dispatch.timetable.file");System.setProperty("dispatch.timetable.file","");
        try {
            Map<String,Object> catalog=DispatchPriority.catalog("安徽","合肥","",DAY);
            check(((Number)catalog.get("enumerated_count")).intValue()==371,"Empty reference data still enumerates 371 cities");
            check(((Number)catalog.get("unknown_count")).intValue()==370,"All 370 outside-city cells remain unknown, not missing or excluded");
            check(Boolean.FALSE.equals(catalog.get("transport_scope_complete")),"A complete city list is not complete transport evidence");
        } finally {if(original==null)System.clearProperty("dispatch.timetable.file");else System.setProperty("dispatch.timetable.file",original);}
        // A constructed 204-minute pair exercises the regression without using real train research.
        List<Map<String,Object>> directions=new ArrayList<>();
        for(boolean reverse:List.of(false,true)) {
            Map<String,Object> row=rail(reverse,204);
            row.put("source_provider","合成测试：非真实列车时刻");row.put("synthetic_test_fixture",true);
            directions.add(row);
        }
        Files.writeString(fixture,Json.write(Map.of("version","rail-timetable-v1","synthetic_test_fixture",true,
            "source_provider","合成测试：非真实列车时刻","usage_authorized",true,"directions",directions)));
        System.setProperty("dispatch.timetable.file",fixture.toString());
        check(RailTimetable.rows().size()==2,"Only the explicitly synthetic two-direction fixture is loaded");
        Map<String,Object> catalog=DispatchPriority.catalog("北京","北京","",DAY);
        check(((List<Map<String,Object>>)catalog.get("priorities")).stream().anyMatch(v->"南京".equals(v.get("city"))),"Synthetic 204-minute Nanjing/Beijing pair prevents false long-route exclusion");
        Map<String,Object> nearby=((List<Map<String,Object>>)catalog.get("priorities")).stream().filter(v->"南京".equals(v.get("city"))).findFirst().orElseThrow();
        check(((Number)nearby.get("outbound_reference_minutes")).intValue()==204&&((Number)nearby.get("return_reference_minutes")).intValue()==204,"Both 204-minute fixture directions are preserved, not estimated");
        check(((Number)catalog.get("resolved_count")).intValue()+((Number)catalog.get("unknown_count")).intValue()==371,"Resolved plus unknown exactly covers current city universe");
        check(((Number)catalog.get("resolved_count")).intValue()==2&&((Number)catalog.get("unknown_count")).intValue()==369,"Only the same city and the synthetic pair resolve; all other cities remain unknown");
        System.out.println("All-city coverage and rail/air boundaries: "+count+" passed");
    }
    public static void main(String[] args) throws Exception {
        Map<String,String> settings=new LinkedHashMap<>();
        for(String key:List.of("dispatch.timetable.file","dispatch.transport.file","dispatch.transport.dir","dispatch.routes.file","dispatch.organizations.file",
            "dispatch.city.references.file","dispatch.city.references.dir","dispatch.nearby.max.minutes")) {
            settings.put(key,System.getProperty(key));System.setProperty(key,key.endsWith("max.minutes")?"240":"");
        }
        Path fixture=null;
        try {
            fixture=Files.createTempFile("yanxu-synthetic-travel-matrix-",".json");
            runChecks(fixture);
        } finally {
            settings.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
            if(fixture!=null)Files.deleteIfExists(fixture);
        }
    }
}
