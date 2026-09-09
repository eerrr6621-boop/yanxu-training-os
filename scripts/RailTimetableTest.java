package com.training;

import java.time.*;
import java.util.*;
import java.nio.file.*;

/** Synthetic, isolated timetable contract checks; no supplier data or business writes. */
public final class RailTimetableTest {
    static int checks;
    static final LocalDate DAY=LocalDate.of(2026,9,7);
    static void check(boolean ok,String label) { if(!ok) throw new AssertionError(label);checks++; }
    static Map<String,Object> sample(String train,int minutes) {
        return new LinkedHashMap<>(Map.of("train_no",train,"from_station","南京南","to_station","合肥南","departure_time","08:00",
            "arrival_time",LocalTime.of(8,0).plusMinutes(minutes).toString(),"arrival_day_offset",0,"rail_minutes",minutes,"station_city_mapping_checked",true));
    }
    static Map<String,Object> row() {
        Map<String,Object> row=new LinkedHashMap<>(Map.of("from_province","江苏","from_city","南京","to_province","安徽","to_city","合肥",
            "source_url","https://example.test/gray-timetable","source_provider","虚构测试来源","observed_on",DAY.toString(),"status","observed_timetable"));
        row.put("source_service_date",DAY.toString());row.put("samples",List.of(sample("G1",50)));return row;
    }
    static Map<String,Object> direction(List<?> rows) { return RailTimetable.direction(rows,"江苏","南京","安徽","合肥",DAY); }
    static DispatchPreference destination(String province,String city) { return DispatchPreference.from(Map.of("training_province",province,"training_city",city,"training_mode","线下","training_period","下午"),null); }
    static Map<String,Object> route(List<?> rows,String fp,String fc,String tp,String tc,LocalDate day) {
        return RailTimetable.lookup(rows,Map.of("base_province",fp,"base_city",fc),destination(tp,tc),day);
    }
    static Map<String,Object> synthetic(String fp,String fc,String tp,String tc,String train,int minutes) {
        Map<String,Object> item=row();item.put("from_province",fp);item.put("from_city",fc);
        item.put("to_province",tp);item.put("to_city",tc);
        Map<String,Object> clock=sample(train,minutes);clock.put("from_station",fc+"测试站");clock.put("to_station",tc+"测试站");
        item.put("samples",List.of(clock));item.put("usage_authorized",true);return item;
    }
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        check(direction(List.of(row())).get("rail_minutes").equals(50),"Sourced station time available without guessed connections");
        for(String field:List.of("observed_on","source_service_date")) {
            Map<String,Object> r=row();r.put(field,DAY.minusDays(31).toString());check(direction(List.of(r))==null,"Old "+field+" cannot be washed by another date");
            r.put(field,DAY.minusDays(30).toString());check(direction(List.of(r))!=null,"30-day inclusive boundary: "+field);
            r.put(field,"not-a-date");check(direction(List.of(r))==null,"Invalid date rejected: "+field);
        }
        Map<String,Object> r=row();r.put("observed_on",DAY.plusDays(1).toString());check(direction(List.of(r))==null,"Future observation rejected");
        r=row();r.put("source_service_date",DAY.plusDays(31).toString());check(direction(List.of(r))==null,"Far-future query date rejected");
        r=row();r.put("source_service_date",null);Map<String,Object> undated=direction(List.of(r));
        check(undated!=null&&undated.get("source_service_date")==null&&"not_shown".equals(undated.get("source_date_status")),"Truly undated source remains visibly undated, not invented service validity");
        for(String url:List.of("javascript:alert(1)","http://example.test/","https://user:pass@example.test/","/private/file")) {
            r=row();r.put("source_url",url);check(direction(List.of(r))==null,"Unsafe source URL rejected");
        }
        for(Object invalid:List.of(0,-1,12.5,Double.NaN,Double.POSITIVE_INFINITY,3000,"50")) {
            Map<String,Object> s=sample("G1",50);s.put("rail_minutes",invalid);r=row();r.put("samples",List.of(s));check(direction(List.of(r))==null,"Invalid time is not a measured duration");
        }
        for(Map<String,?> replacement:List.of(Map.of("train_no","K123"),Map.of("train_no","Z2"),Map.of("arrival_time","08:51"),Map.of("arrival_time","08:50:59"),Map.of("arrival_day_offset",.5),Map.of("station_city_mapping_checked",false))) {
            Map<String,Object> s=sample("G1",50);s.putAll(replacement);r=row();r.put("samples",List.of(s));check(direction(List.of(r))==null,"Train/time/mapping boundary rejected: "+replacement);
        }
        Map<String,Object> s=sample("G1",50);s.put("departure_time","23:40");s.put("arrival_time","00:30");s.put("arrival_day_offset",1);r=row();r.put("samples",List.of(s));
        check(direction(List.of(r))!=null,"Explicit overnight day offset respected");
        Map<String,Object> bad=sample("G1",50);bad.put("rail_minutes","bad");r=row();r.put("samples",List.of(bad,sample("D2",40),sample("G3",60),sample("G4",50)));
        check("D2".equals(direction(List.of(r)).get("train_no")),"Bad sample does not hide later rows; slower G cannot hide faster eligible D");
        r=row();r.put("from_city","苏州");check(direction(List.of(r))==null,"City direction not inferred from similar station names");
        check("rail_reference_pending".equals(route(List.of(row()),"江苏","南京","安徽","合肥",DAY).get("status")),"One direction cannot manufacture its return");
        check("same_city".equals(route(List.of(),"安徽","合肥","安徽","合肥",DAY).get("status")),"Same city does not request cross-city trains");
        // Business tests never load historical supplier research from the working directory.
        Path fixture=Files.createTempFile("yanxu-synthetic-rail-", ".json");
        fixture.toFile().deleteOnExit();
        System.setProperty("dispatch.timetable.file",fixture.toString());
        List<?> syntheticRows=List.of(
            synthetic("江苏","南京","安徽","合肥","G1",48),
            synthetic("安徽","合肥","江苏","南京","G2",50),
            synthetic("新疆","乌鲁木齐","甘肃","兰州","D3",633),
            synthetic("甘肃","兰州","新疆","乌鲁木齐","D4",669));
        Map<String,Object> file=new LinkedHashMap<>(Map.of("version","rail-timetable-v1","directions",syntheticRows));
        for(Map<String,Object> restriction:List.of(Map.<String,Object>of("research_only",true),Map.<String,Object>of("business_import_allowed",false),Map.<String,Object>of("usage_authorized",false))) {
            Map<String,Object> blocked=new LinkedHashMap<>(file);blocked.putAll(restriction);
            Files.writeString(fixture,Json.write(blocked));
            check(RailTimetable.rows().isEmpty(),"Research/unauthorized file cannot enter business catalog: "+restriction.keySet());
        }
        file.put("usage_authorized",true);Files.writeString(fixture,Json.write(file));
        List<?> rows=RailTimetable.rows();check(rows.size()==4,"Explicit synthetic test catalog loads after restricted file");
        for(Object raw:rows) {
            Map<String,Object> item=(Map<String,Object>)raw;
            check(!Json.write(item).contains("/Users/")&&!Json.write(item).contains("/private/"),"Synthetic table contains no private source paths");
            check(RegionDirectory.known((String)item.get("from_province"),(String)item.get("from_city"))&&RegionDirectory.known((String)item.get("to_province"),(String)item.get("to_city")),"City/province mapping recognized");
            for(Object sm:(List<?>)item.get("samples")) {
                Map<String,Object> m=(Map<String,Object>)sm;
                int out=LocalTime.parse((String)m.get("departure_time")).toSecondOfDay()/60;
                int in=LocalTime.parse((String)m.get("arrival_time")).toSecondOfDay()/60;
                check(in+((Number)m.get("arrival_day_offset")).intValue()*1440-out==((Number)m.get("rail_minutes")).intValue(),"Each synthetic duration agrees with its fixture clocks");
            }
        }
        Map<String,Object> hefei=route(rows,"江苏","南京","安徽","合肥",DAY);
        check("rail_time_reference".equals(hefei.get("status"))&&((Number)hefei.get("outbound_reference_minutes")).intValue()==48&&((Number)hefei.get("return_reference_minutes")).intValue()==50,"Synthetic asymmetric pair uses separately recorded directions");
        check(Boolean.FALSE.equals(hefei.get("transport_verified"))&&Boolean.FALSE.equals(hefei.get("travel_date_verified"))&&!hefei.containsKey("upper_minutes"),"Rail samples not promoted to door-to-door or course-day verification");
        Map<String,Object> catalog=DispatchPriority.catalog("安徽","合肥","2099-01-01",DAY);
        check("ready".equals(catalog.get("status"))&&"partial".equals(catalog.get("coverage")),"Catalog distinguishes available subset from complete network");
        check(((List<Map<String,Object>>)catalog.get("priorities")).stream().anyMatch(c->"南京".equals(c.get("city"))&&((Number)c.get("priority_score")).intValue()==85),"Synthetic pair produces expected two-hour band");
        check("rail_references_pending".equals(DispatchPriority.catalog("安徽","合肥","",DAY.plusDays(31)).get("status")),"Snapshot expires safely, cannot silently remain current forever");
        Map<String,Object> longRoute=route(rows,"新疆","乌鲁木齐","甘肃","兰州",DAY);
        check(((Number)longRoute.get("outbound_reference_minutes")).intValue()==633&&((Number)longRoute.get("return_reference_minutes")).intValue()==669,"Ten-hour train references not compressed into nearby estimates");
        catalog=DispatchPriority.catalog("甘肃","兰州","",DAY);
        check(((List<Map<String,Object>>)catalog.get("pending_routes")).stream().anyMatch(c->"乌鲁木齐".equals(c.get("city"))),"A long sampled service cannot prove all trains exceed the limit");
        check("rail_reference_pending".equals(route(rows,"海南","海口","广东","广州",DAY).get("status")),"K/Z or mixed ferry route not fabricated as a high-speed sample");
        check("rail_reference_pending".equals(route(rows,"广东","中山","广东","广州",DAY).get("status")),"Missing complete-date reverse remains pending");
        List<Map<String,Object>> teachers=new ArrayList<>();
        for(int i=0;i<4;i++) {
            Map<String,Object> t=new LinkedHashMap<>();t.put("teacher_id",(long)i+1);t.put("name","【灰度测试】铁路匹配"+i);t.put("teacher_level",i==3?"特聘讲师":"讲师");
            t.put("model_score",i==3?null:90-i*5);t.put("ranking_score",i==3?99:90-i*5);t.put("recommendation_score",i==3?null:90-i*5);
            t.put("dispatch_fit",new LinkedHashMap<>(Map.of("local_priority",false,"route_reference",hefei)));teachers.add(t);
        }
        Map<String,Object> selection=NearbySelection.select(teachers,destination("安徽","合肥"),3);
        check(((List<?>)selection.get("selected")).size()==3&&((List<?>)selection.get("scoring_pending")).size()==1,"Unscored rule99 cannot displace model80 from sourced nearby pool");
        check(((Number)selection.get("expanded_rail_reference_minutes")).intValue()==240,"Complete rail catchment replaces two-hour quota truncation");
        file.put("research_only",true);Files.writeString(fixture,Json.write(file));
        check(RailTimetable.rows().isEmpty(),"Restricting a previously loaded file stops business use without stale-cache fallback");
        System.out.println("Synthetic rail timetable dates, safety and selection: "+checks+" passed; no actual traffic data verified");
    }
}
