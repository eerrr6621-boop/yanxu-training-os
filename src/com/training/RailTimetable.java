package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Reusable, sourced station-to-station samples; never a date/arrival guarantee. */
final class RailTimetable {
    private static final int MAX_AGE_DAYS=30;
    private static String fingerprint="";
    private static List<?> cached=List.of();
    static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    static synchronized List<?> rows() {
        try {
            String setting=System.getProperty("dispatch.timetable.file",System.getenv().getOrDefault("YANXU_RAIL_TIMETABLE_FILE","config/rail-timetable.json"));
            if(setting.isBlank()) return List.of();
            Path path=Paths.get(setting);long size=Files.size(path);
            if(size>1024*1024) return List.of();
            String stamp=path.toAbsolutePath()+":"+size+":"+Files.getLastModifiedTime(path);
            if(!stamp.equals(fingerprint)) {
                Object parsed=Json.parse(Files.readString(path));
                if(!(parsed instanceof Map<?,?>)) return List.of();
                Map<?,?> data=(Map<?,?>)parsed;
                if(!businessUseAllowed(data)||!"rail-timetable-v1".equals(data.get("version")) || !(data.get("directions") instanceof List<?>)) return List.of();
                cached=(List<?>)data.get("directions");fingerprint=stamp;
            }
            return cached;
        } catch(Exception unavailable) { return List.of(); }
    }
    static String value(Map<?,?> map,String field) { return Objects.toString(map.get(field),""); }
    // Keep older reviewed catalog rows compatible, but never override an explicit usage prohibition.
    static boolean businessUseAllowed(Map<?,?> row) {
        return !Boolean.TRUE.equals(row.get("research_only"))&&!Boolean.FALSE.equals(row.get("business_import_allowed"))&&
            !Boolean.FALSE.equals(row.get("usage_authorized"))&&!"RESEARCH_ONLY".equalsIgnoreCase(value(row,"status"));
    }
    static int minute(Object raw) {
        if(!(raw instanceof Number)) throw new IllegalArgumentException();
        double d=((Number)raw).doubleValue();int n=((Number)raw).intValue();
        if(!Double.isFinite(d)||d!=n||n<1||n>2880) throw new IllegalArgumentException();
        return n;
    }
    static Map<String,Object> direction(List<?> rows,String fromProvince,String fromCity,String toProvince,String toCity,LocalDate today) {
        return direction(rows,fromProvince,fromCity,toProvince,toCity,today,false);
    }
    private static Map<String,Object> direction(List<?> rows,String fromProvince,String fromCity,String toProvince,String toCity,LocalDate today,boolean historical) {
        Map<String,Object> best=null;int bestType=9;
        if(!RegionDirectory.known(fromProvince,fromCity)||!RegionDirectory.known(toProvince,toCity)) return null;
        for(Object raw:rows) if(raw instanceof Map<?,?>) {
            Map<?,?> r=(Map<?,?>)raw;
            try {
                if(!businessUseAllowed(r)||!"observed_timetable".equals(r.get("status")) || !fromProvince.equals(r.get("from_province")) || !fromCity.equals(r.get("from_city")) ||
                    !toProvince.equals(r.get("to_province")) || !toCity.equals(r.get("to_city"))) continue;
                LocalDate observed=LocalDate.parse(value(r,"observed_on"));
                if(observed.isAfter(today)||!historical&&observed.plusDays(MAX_AGE_DAYS).isBefore(today)) continue;
                String serviceDate=value(r,"source_service_date");
                LocalDate recheck=observed.plusDays(MAX_AGE_DAYS);
                if(!serviceDate.isEmpty()) {
                    LocalDate sampledDay=LocalDate.parse(serviceDate);
                    if(!historical&&sampledDay.plusDays(MAX_AGE_DAYS).isBefore(today)||sampledDay.isAfter(observed.plusDays(30))) continue;
                    if(sampledDay.plusDays(MAX_AGE_DAYS).isBefore(recheck)) recheck=sampledDay.plusDays(MAX_AGE_DAYS);
                }
                String url=value(r,"source_url");
                java.net.URI source=java.net.URI.create(url);
                if(!"https".equals(source.getScheme())||source.getHost()==null||source.getUserInfo()!=null) continue;
                if(!(r.get("samples") instanceof List<?>)) continue;
                for(Object sample:(List<?>)r.get("samples")) if(sample instanceof Map<?,?>) {
                    try {
                    Map<?,?> s=(Map<?,?>)sample;
                    if(!businessUseAllowed(s)) continue;
                    String train=value(s,"train_no"),fromStation=value(s,"from_station"),toStation=value(s,"to_station");
                    if(!train.matches("[GDC]\\d{1,6}") || fromStation.isBlank()||toStation.isBlank() || fromStation.length()>40||toStation.length()>40) continue;
                    if(!Boolean.TRUE.equals(s.get("station_city_mapping_checked"))) continue;
                    int duration=minute(s.get("rail_minutes"));
                    if(!value(s,"departure_time").matches("\\d{2}:\\d{2}")||!value(s,"arrival_time").matches("\\d{2}:\\d{2}")) continue;
                    LocalTime dep=LocalTime.parse(value(s,"departure_time")),arr=LocalTime.parse(value(s,"arrival_time"));
                    if(!(s.get("arrival_day_offset") instanceof Number)) continue;
                    double rawOffset=((Number)s.get("arrival_day_offset")).doubleValue();int offset=((Number)s.get("arrival_day_offset")).intValue();
                    if(rawOffset!=offset||offset<0||offset>2 || arr.toSecondOfDay()/60+offset*1440-dep.toSecondOfDay()/60!=duration) continue;
                    int type=train.startsWith("G")?0:train.startsWith("D")?1:2;
                    if(best!=null && (duration>((Number)best.get("rail_minutes")).intValue() || duration==((Number)best.get("rail_minutes")).intValue()&&type>=bestType)) continue;
                    bestType=type;best=new LinkedHashMap<>();
                    for(String field:List.of("train_no","from_station","to_station","departure_time","arrival_time","arrival_day_offset","rail_minutes")) best.put(field,s.get(field));
                    best.put("from_province",fromProvince);best.put("from_city",fromCity);best.put("to_province",toProvince);best.put("to_city",toCity);
                    best.put("source_url",url);best.put("source_provider",value(r,"source_provider"));
                    best.put("observed_on",observed.toString());best.put("source_service_date",serviceDate.isEmpty()?null:serviceDate);
                    best.put("source_date_status",serviceDate.isEmpty()?"not_shown":"shown");
                    best.put("reference_recheck_after",recheck.toString());best.put("train_type",type==0?"G 高铁":type==1?"D 动车":"C 城际");
                    best.put("selection_basis","所核对页面中的列车样本，不保证全网最快；不含接驳候乘");
                    } catch(Exception invalidSample) { /* One bad sample cannot hide a later valid sample. */ }
                }
            } catch(Exception invalid) { /* No guessed duration, date or station. */ }
        }
        return best;
    }
    static Map<String,Object> lookup(List<?> rows,Map<String,Object> teacher,DispatchPreference pref,LocalDate today) {
        if(!pref.localPreferenceRequested()) return Map.of("status","not_required");
        if(!pref.localPreferenceActive()) return Map.of("status","rail_reference_pending","eligibility","unknown","message","授课地区待核实，暂不自动纳入就近推荐；不按不限地区处理");
        String fromProvince=value(teacher,"base_province"),fromCity=value(teacher,"base_city");
        if(RegionDirectory.known(fromProvince,fromCity)&&fromProvince.equals(pref.province)&&fromCity.equals(pref.city))
            return Map.of("status","same_city","message","同城初选不需要跨城高铁参考；市内通勤、实际出发地与档期仍需确认","transport_verified",false);
        Map<String,Object> outward=direction(rows,fromProvince,fromCity,pref.province,pref.city,today);
        Map<String,Object> returning=direction(rows,pref.province,pref.city,fromProvince,fromCity,today);
        if(outward==null||returning==null) {
            List<String> missing=new ArrayList<>();
            if(outward==null) missing.add(fromCity+" → "+pref.city);
            if(returning==null) missing.add(pref.city+" → "+fromCity);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("status","rail_reference_pending");result.put("missing_directions",missing);
            result.put("message","尚无完整且近期的双向高铁/动车时间参考，需补查；不是不通车的结论");
            Map<String,Object> oldOut=outward==null?direction(rows,fromProvince,fromCity,pref.province,pref.city,today,true):null;
            Map<String,Object> oldBack=returning==null?direction(rows,pref.province,pref.city,fromProvince,fromCity,today,true):null;
            if(oldOut!=null||oldBack!=null) {
                Map<String,Object> history=new LinkedHashMap<>();history.put("status","historical_reference");history.put("eligibility","unknown");
                if(oldOut!=null) history.put("outbound",oldOut);
                if(oldBack!=null) history.put("return",oldBack);
                history.put("message","历史交通参考保留原始来源与核对日期，当前待复核；不自动纳入就近推荐，也不代表授课日可达");
                result.put("historical_reference",history);
            }
            return result;
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("status","rail_time_reference");result.put("reference_basis","station_to_station");
        result.put("outbound_reference_minutes",outward.get("rail_minutes"));result.put("return_reference_minutes",returning.get("rail_minutes"));
        result.put("outbound",outward);result.put("return",returning);
        result.put("transport_verified",false);result.put("travel_date_verified",false);
        result.put("local_connections_status","pending");result.put("arrival_feasibility","unverified");
        result.put("message","高铁优先的站到站时刻样本；可用于城市初选，不代表授课日前后开行、有票或能按时到场。接驳候乘与行程仍需核对。");
        return result;
    }
    private RailTimetable() {}
}
