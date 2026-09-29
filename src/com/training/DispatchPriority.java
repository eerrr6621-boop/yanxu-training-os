package com.training;

import java.nio.file.*;
import java.util.*;

/** Private organization references and sourced station-to-station rail-time catalog. */
final class DispatchPriority {
    static java.time.LocalDate latestObserved(Object data) {
        java.time.LocalDate latest=null;
        if(data instanceof Map<?,?>) {
            Map<?,?> map=(Map<?,?>)data;
            try { if(map.get("observed_on") instanceof String) latest=java.time.LocalDate.parse((String)map.get("observed_on")); }
            catch(java.time.format.DateTimeParseException invalid) { /* Missing/invalid dates cannot become today. */ }
            for(Object child:map.values()) {
                java.time.LocalDate next=latestObserved(child);
                if(next!=null&&(latest==null||next.isAfter(latest))) latest=next;
            }
        } else if(data instanceof List<?>) for(Object child:(List<?>)data) {
            java.time.LocalDate next=latestObserved(child);
            if(next!=null&&(latest==null||next.isAfter(latest))) latest=next;
        }
        return latest;
    }
    static List<Map<String,Object>> organizations() {
        try {
            Path path=Paths.get(System.getProperty("dispatch.organizations.file",System.getenv().getOrDefault("YANXU_DISPATCH_ORGANIZATIONS_FILE","config/dispatch-organizations.local.json")));
            if(Files.size(path)>1024*1024) return List.of();
            Object raw=Json.parse(Files.readString(path));
            if(!(raw instanceof List<?>)) return List.of();
            List<Map<String,Object>> result=new ArrayList<>();
            for(Object item:(List<?>)raw) if(item instanceof Map<?,?>) {
                Map<?,?> row=(Map<?,?>)item;
                String name=Objects.toString(row.get("organization_exact_name"),"");
                if(name.isBlank()||name.length()>120) continue;
                Map<String,Object> safe=new LinkedHashMap<>();
                for(String field:List.of("province","city","status","active_status")) safe.put(field,row.get(field));
                safe.put("name",name);safe.put("note",row.get("notes"));
                safe.put("source","用户提供的机构名单；实际授课城市优先");
                result.add(safe);
            }
            return result;
        } catch(Exception unavailable) { return List.of(); }
    }
    static Map<String,Object> catalog(String province,String city,String date) {
        return catalog(province,city,date,RailTimetable.today());
    }
    static Map<String,Object> catalog(String province,String city,String date,java.time.LocalDate today) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("organizations",organizations());
        List<Map<String,Object>> priorities=new ArrayList<>();
        List<Map<String,Object>> pending=new ArrayList<>(),outside=new ArrayList<>();
        java.time.LocalDate lastObserved=null;
        if(RegionDirectory.known(province,city)) {
            priorities.add(Map.of("province",province,"city",city,"tier",0,"priority_score",100,"label","同城优先（市内通勤待确认）"));
            DispatchPreference pref=DispatchPreference.from(Map.of("training_province",province,"training_city",city,"training_mode","线下","training_period","下午"),null);
            List<?> references=RailTimetable.rows();
            Map<?,?> transport=TravelMatrix.bundle(province,city);
            Map<?,?> cityReferences=CityTravelReference.bundle(province,city);
            Map<?,?> publicReferences=CityTravelReference.publicBundle(province,city);
            for(Map.Entry<String,Set<String>> region:RegionDirectory.CITIES.entrySet()) for(String fromCity:region.getValue()) {
                String fromProvince=region.getKey();
                if(fromProvince.equals(province)&&fromCity.equals(city)) continue;
                Map<String,Object> route=TravelMatrix.resolve(references,transport,cityReferences,publicReferences,Map.of("base_province",fromProvince,"base_city",fromCity),pref,today);
                java.time.LocalDate routeObserved=latestObserved(route);
                if(routeObserved!=null&&(lastObserved==null||routeObserved.isAfter(lastObserved))) lastObserved=routeObserved;
                if("outside".equals(route.get("eligibility"))) { outside.add(Map.of("province",fromProvince,"city",fromCity,"message",route.get("message")));continue; }
                if(!"rail".equals(route.get("eligibility"))&&!"air".equals(route.get("eligibility"))) {
                    Map<String,Object> waiting=new LinkedHashMap<>();waiting.put("province",fromProvince);waiting.put("city",fromCity);waiting.put("message",route.get("message"));
                    if(route.containsKey("historical_reference")) waiting.put("historical_reference",route.get("historical_reference"));
                    if(route.containsKey("city_reference_history")) waiting.put("city_reference_history",route.get("city_reference_history"));
                    pending.add(waiting);continue;
                }
                Map<String,Object> safe=new LinkedHashMap<>(route);
                safe.put("province",fromProvince);safe.put("city",fromCity);
                boolean isAir="air".equals(route.get("eligibility"));
                if(CityTravelReference.typed(route)) {
                    safe.put("priority_score",null);safe.put("tier",null);safe.put("time_score_applicable",false);
                    safe.put("label","铁路就近池 · 城市时间范围（不是精确时长评分）");
                } else {
                    int minutes=Math.max(NearbySelection.minutes(route.get("outbound_reference_minutes")),NearbySelection.minutes(route.get("return_reference_minutes")));
                    safe.put("priority_score",isAir?40:NearbySelection.priorityScore(minutes));
                    safe.put("tier",isAir?4:NearbySelection.timeTier(minutes));safe.put("label",(isAir?"直飞备选":"高铁优先")+" · 双向取较长参考 "+minutes+" 分钟");
                }
                priorities.add(safe);
            }
        }
        result.put("status",priorities.size()>1?"ready":"rail_references_pending");
        // Catalog grouping only. Untimed bounds have no fabricated distance tier.
        priorities.sort(Comparator.comparingInt(row->row.get("tier") instanceof Number?((Number)row.get("tier")).intValue():3));
        result.put("priorities",priorities);
        int universe=RegionDirectory.CITIES.values().stream().mapToInt(Set::size).sum();
        int enumerated=priorities.size()+pending.size()+outside.size();
        result.put("coverage",enumerated==universe&&pending.isEmpty()?"complete":"partial");
        result.put("reference_checked_on",lastObserved==null?null:lastObserved.toString());result.put("catalog_generated_on",today.toString());
        result.put("universe_count",universe);result.put("enumerated_count",enumerated);
        result.put("resolved_count",priorities.size()+outside.size());result.put("unknown_count",pending.size());
        result.put("transport_scope_complete",enumerated==universe&&pending.isEmpty());
        result.put("pending_routes",pending);result.put("outside_nearby_routes",outside);
        result.put("rule","检查全部城市：同城及所有铁路小于4小时的老师统一比较，不在2/3小时层凑够人数就停止。铁路未满足4小时须完整核对后才转直飞小于3小时；航空后备不挤占铁路候选。各池按模型分、同分等级，第三名同分全留。");
        result.put("notice","清单遍历全部已收录城市，不等于交通参考已全部齐备。未查、过期、只有慢车样本或缺返程保持未知，不推断无车或超时。按公开城市通达参考或已审核资料划定范围，不要求每次实时查询具体班次；时长不含接驳、候乘和值机安检。");
        result.put("attribution","");
        return result;
    }
    private DispatchPriority() {}
}
