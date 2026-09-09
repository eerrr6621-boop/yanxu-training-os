package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Admit all qualifying nearby candidates, THEN rank the whole pool.
 * No geographic distance or assumed speed may be used to supply route times. */
final class NearbySelection {
    static int timeTier(int minutes) { return minutes<=120?1:minutes<=180?2:3; }
    static int priorityScore(int minutes) { return minutes<=120?85:minutes<=180?70:55; }
    static double rankScore(Map<String,Object> c) {
        Object value=c.get("ranking_score");
        if(!(value instanceof Number)) value=c.get("recommendation_score");
        return value instanceof Number?((Number)value).doubleValue():0;
    }
    static int transportTier(Map<String,Object> c) {
        Object fit=c.get("dispatch_fit");
        return fit instanceof Map<?,?>&&((Map<?,?>)fit).get("route_reference") instanceof Map<?,?>&&
            ("air_time_reference".equals(((Map<?,?>)((Map<?,?>)fit).get("route_reference")).get("status"))||
                "air".equals(((Map<?,?>)((Map<?,?>)fit).get("route_reference")).get("eligibility")))?1:0;
    }
    static int maximumNearbyMinutes() {
        // Product boundary, not a measured route: each direction must fit.
        try { return Math.max(0,Math.min(240,Integer.parseInt(System.getProperty("dispatch.nearby.max.minutes",
                System.getenv().getOrDefault("YANXU_NEARBY_MAX_MINUTES","240"))))); }
        catch(NumberFormatException invalid) { return 0; }
    }
    static Map<String,Object> route(Map<String,Object> teacher,DispatchPreference preference,String date) {
        return route(teacher,preference,date,RailTimetable.today());
    }
    static Map<String,Object> route(Map<String,Object> teacher,DispatchPreference preference,String date,LocalDate referenceDay) {
        if(!preference.localPreferenceRequested()) return Map.of("status","not_required");
        Map<String,Object> timetable=TravelMatrix.resolve(RailTimetable.rows(),TravelMatrix.bundle(preference.province,preference.city),CityTravelReference.bundle(preference.province,preference.city),CityTravelReference.publicBundle(preference.province,preference.city),teacher,preference,Objects.requireNonNull(referenceDay,"reference day"));
        if("rail_time_reference".equals(timetable.get("status"))||"same_city".equals(timetable.get("status"))||"air_time_reference".equals(timetable.get("status"))||"outside_transport_limits".equals(timetable.get("status"))||"city_transport_reference".equals(timetable.get("status"))||"city_reference_pending".equals(timetable.get("status"))) return timetable;
        Map<String,Object> legacy=lookup(references(),teacher,preference,date);
        return "reference_available".equals(legacy.get("status"))?legacy:timetable;
    }
    static List<?> references() {
        String configured=System.getProperty("dispatch.routes.file",System.getenv().getOrDefault("YANXU_ROUTE_REFERENCES_FILE","config/dispatch-route-references.local.json"));
        try {
            Path path=Paths.get(configured);
            if(Files.size(path)>1024*1024) throw new IllegalArgumentException();
            Object parsed=Json.parse(Files.readString(path));
            if(!(parsed instanceof List<?>)) throw new IllegalArgumentException();
            return (List<?>)parsed;
        } catch(Exception invalid) { return List.of(); }
    }
    static Map<String,Object> lookup(List<?> references,Map<String,Object> teacher,DispatchPreference pref,String date) {
        if(!pref.localPreferenceActive()) return Map.of("status","not_required");
        if(date==null || date.isBlank()) return Map.of("status","date_required","message","确定日期后才能检查交通参考有效期");
        Map<String,Object> best=null;
        for(Object raw:references) {
            if(!(raw instanceof Map<?,?>)) continue;
            Map<?,?> r=(Map<?,?>)raw;
            try {
                if(!RailTimetable.businessUseAllowed(r)) continue;
                if(!Objects.equals(r.get("from_province"),teacher.get("base_province")) || !Objects.equals(r.get("from_city"),teacher.get("base_city")) ||
                        !Objects.equals(r.get("to_province"),pref.province) || !Objects.equals(r.get("to_city"),pref.city)) continue;
                if(!RegionDirectory.known(String.valueOf(r.get("from_province")),String.valueOf(r.get("from_city")))) continue;
                if(!"reviewed_reference".equals(r.get("kind"))) continue;
                if(!"high_speed_rail".equals(r.get("transport_mode")) || !Boolean.TRUE.equals(r.get("rail_priority_checked"))) continue;
                LocalDate target=LocalDate.parse(date),from=LocalDate.parse(String.valueOf(r.get("valid_from"))),until=LocalDate.parse(String.valueOf(r.get("valid_until")));
                LocalDate checked=LocalDate.parse(String.valueOf(r.get("checked_on")));
                LocalDate outwardDay=Arrays.asList("上午","全天").contains(pref.period)?target.minusDays(1):target;
                if(checked.isAfter(LocalDate.now()) || until.isBefore(from) || target.isBefore(from) || target.isAfter(until) ||
                        outwardDay.isBefore(from) || outwardDay.isAfter(until) ||
                        java.time.temporal.ChronoUnit.DAYS.between(checked,until)>90) continue;
                String source=String.valueOf(r.get("source"));
                if(!source.matches("https?://[^\\s]+")) continue;
                int outward=minutes(r.get("outbound_door_to_door_upper_minutes"));
                int returning=minutes(r.get("return_door_to_door_upper_minutes"));
                int outRail=minutes(r.get("outbound_rail_minutes")),backRail=minutes(r.get("return_rail_minutes"));
                int outConnections=minutes(r.get("outbound_connection_minutes")),backConnections=minutes(r.get("return_connection_minutes"));
                if(outward<outRail+outConnections || returning<backRail+backConnections) continue;
                if(Objects.toString(r.get("from_station"),"").isBlank() || Objects.toString(r.get("to_station"),"").isBlank()) continue;
                if(!Boolean.TRUE.equals(r.get("includes_transfers_and_local_connections"))) continue;
                if(best==null || Math.max(outward,returning)<Math.max((int)best.get("upper_minutes"),(int)best.get("return_upper_minutes"))) {
                    best=new LinkedHashMap<>();
                    best.put("status","reference_available");best.put("source",source);best.put("checked_on",checked.toString());
                    best.put("valid_until",until.toString());best.put("upper_minutes",outward);best.put("return_upper_minutes",returning);
                    best.put("transport_mode","high_speed_rail");best.put("outbound_rail_minutes",outRail);best.put("return_rail_minutes",backRail);
                    best.put("outbound_connection_minutes",outConnections);best.put("return_connection_minutes",backConnections);
                    best.put("from_station",r.get("from_station"));best.put("to_station",r.get("to_station"));
                    best.put("transport_verified",false);best.put("message","已核对的高铁时间含市内接驳与候乘；不是实时车票或已确认行程，出发前须复核");
                }
            } catch(Exception invalid) { /* An invalid row is not an available route. */ }
        }
        return best==null?Map.of("status","no_current_reference","message","缺少适用日期的门到门交通参考"):best;
    }
    static int minutes(Object n) {
        if(!(n instanceof Number)) throw new IllegalArgumentException();
        double d=((Number)n).doubleValue();int v=((Number)n).intValue();
        if(!Double.isFinite(d)||d!=v||v<1||v>2880) throw new IllegalArgumentException();
        return v;
    }
    static Map<String,Object> select(List<Map<String,Object>> candidates,DispatchPreference pref,int target) {
        return select(candidates,pref,target,null,null);
    }
    /** Separate local opt-in; the original call sites still use the three-argument
     * entry point. Do not retain a Catalog beyond this request/date. */
    static Map<String,Object> select(List<Map<String,Object>> candidates,DispatchPreference pref,int target,
            ContextSharedPlanningAdapter.Catalog context,LocalDate requestDate) {
        return selectInternal(candidates,pref,target,context,null,requestDate);
    }
    /** One request, one whole-candidate-pool ranking; the component policies remain separate. */
    static Map<String,Object> selectWithContextCatalogs(List<Map<String,Object>> candidates,DispatchPreference pref,int target,
            ContextSharedPlanningAdapter.RequestCatalog routed,LocalDate requestDate) {
        return selectInternal(candidates,pref,target,null,routed,requestDate);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> selectInternal(List<Map<String,Object>> candidates,DispatchPreference pref,int target,
            ContextSharedPlanningAdapter.Catalog context,ContextSharedPlanningAdapter.RequestCatalog routed,LocalDate requestDate) {
        List<Map<String,Object>> pool=new ArrayList<>(),pending=new ArrayList<>(),air=new ArrayList<>();
        int expanded=-1;
        int maximum=maximumNearbyMinutes();
        for(Map<String,Object> candidate:candidates)if(candidate.get("dispatch_fit") instanceof Map<?,?> fit){
            CityPlanningSelection.clearSharedPlanningDecision((Map<String,Object>)fit);
            CityPlanningSelection.clearContextPlanningDecision((Map<String,Object>)fit);
        }
        if(!pref.localPreferenceActive()) {
            if(pref.localPreferenceRequested()) pending.addAll(candidates);
            else pool.addAll(candidates);
        }
        else {
            TreeMap<Integer,List<Map<String,Object>>> groups=new TreeMap<>();
            for(Map<String,Object> c:candidates) {
                Map<String,Object> fit=(Map<String,Object>)c.get("dispatch_fit");
                int proximity=-1;
                if(Boolean.TRUE.equals(fit.get("local_priority"))) {
                    proximity=0;
                    fit.put("priority_score",100);fit.put("priority_label","同城优先");
                }
                else if(CityPlanningSelection.include(fit,pref,maximum)) {
                    // A distinct, labelled pre-bid judgement; strict route data
                    // and its verification flags remain untouched.
                    proximity=1;
                }
                else if(CityPlanningSelection.includeSharedPlanning(fit,pref,maximum)) {
                    // One shared scalar supports planning membership only. Keep
                    // strict route evidence, exclusions and airline gates intact.
                    proximity=1;
                }
                else if((context!=null||routed!=null)&&Objects.equals(c.get("base_province"),fit.get("base_province"))&&
                        Objects.equals(c.get("base_city"),fit.get("base_city"))&&(routed!=null?CityPlanningSelection.includeRoutedContextPlanning(fit,pref,maximum,routed,requestDate):CityPlanningSelection.includeContextPlanning(fit,pref,maximum,context,requestDate))) {
                    // Same catchment and ranking below; never create a second
                    // direction value, strict time bound or airline exclusion.
                    proximity=1;
                }
                else if(!Boolean.TRUE.equals(fit.get("departure_uncertain")) && fit.get("route_reference") instanceof Map<?,?>) {
                    Map<?,?> route=(Map<?,?>)fit.get("route_reference");
                    if("unknown".equals(route.get("eligibility"))||"outside".equals(route.get("eligibility"))) {
                        pending.add(c);fit.put("nearby_pending_reason",Objects.toString(route.get("message"),"交通参考待核实，不自动纳入推荐"));continue;
                    }
                    if("rail_time_reference".equals(route.get("status")) || "reference_available".equals(route.get("status"))||"city_transport_reference".equals(route.get("status"))&&"rail".equals(route.get("eligibility"))) {
                        if(CityTravelReference.typed(route)) {
                            if(CityTravelReference.strictlyUnder(route,maximum)) {
                                // Membership only: never score an inferred cap as a measured journey.
                                proximity=1;fit.put("priority_score",null);fit.put("time_score_applicable",false);
                                fit.put("priority_label","铁路就近池 · 城市时间范围");
                            } else fit.put("nearby_pending_reason","双向城市时间区间未能证明处于当前范围，待核实");
                        } else {
                        int outward=minutes(route.get("outbound_reference_minutes")!=null?route.get("outbound_reference_minutes"):route.get("outbound_rail_minutes")!=null?route.get("outbound_rail_minutes"):route.get("upper_minutes"));
                        int returning=minutes(route.get("return_reference_minutes")!=null?route.get("return_reference_minutes"):route.get("return_rail_minutes")!=null?route.get("return_rail_minutes"):route.get("return_upper_minutes"));
                        if(Math.max(outward,returning)<maximum) {
                            proximity=timeTier(Math.max(outward,returning));
                            fit.put("priority_score",priorityScore(Math.max(outward,returning)));fit.put("priority_label","高铁优先 · 站到站时间参考");
                        }
                        else fit.put("nearby_pending_reason",Objects.toString(route.get("message"),"铁路全车次范围和直飞时刻待完整核对，不用慢车样本推断超时"));
                        }
                    } else if(maximum>0&&("air_time_reference".equals(route.get("status"))||"city_transport_reference".equals(route.get("status")))&&"air".equals(route.get("eligibility"))&&
                        Math.max(minutes(route.get("outbound_reference_minutes")),minutes(route.get("return_reference_minutes")))<TravelMatrix.AIR_LIMIT) {
                        air.add(c);fit.put("priority_score",40);fit.put("priority_label","航空备选 · 直飞小于3小时");continue;
                    }
                }
                if(proximity<0) pending.add(c);
                else groups.computeIfAbsent(proximity,k->new ArrayList<>()).add(c);
            }
            for(Map.Entry<Integer,List<Map<String,Object>>> entry:groups.entrySet()) {
                pool.addAll(entry.getValue());expanded=entry.getKey();
                // Check and admit EVERY qualifying rail city, never stop when a closer band reaches three.
            }
            if(pool.size()<target) pool.addAll(air);
        }
        int catchmentSize=pool.size();
        long planningPoolSize=pool.stream().filter(c->Boolean.TRUE.equals(((Map<?,?>)c.get("dispatch_fit")).get("planning_included"))).count();
        long sharedPlanningPoolSize=pool.stream().filter(c->CityPlanningSelection.SHARED_POLICY.equals(((Map<?,?>)c.get("dispatch_fit")).get("planning_policy"))).count();
        long contextPlanningPoolSize=pool.stream().filter(c->ContextSharedPlanningAdapter.ownsPolicy(((Map<?,?>)c.get("dispatch_fit")).get("planning_policy"))).count();
        boolean baselinePresent=pool.stream().anyMatch(c->ContextSharedPlanningAdapter.BASELINE_POLICY.equals(((Map<?,?>)c.get("dispatch_fit")).get("planning_policy")));
        List<Map<String,Object>> scoringPending=new ArrayList<>();
        boolean anyModel=pool.stream().anyMatch(c->c.get("model_score") instanceof Number);
        if(anyModel) {
            for(Map<String,Object> c:pool) if(!(c.get("model_score") instanceof Number)) scoringPending.add(c);
            pool.removeAll(scoringPending);
            if(scoringPending.stream().anyMatch(c->transportTier(c)==0)) pool.removeIf(c->transportTier(c)==1);
        }
        java.util.function.ToDoubleFunction<Map<String,Object>> ranking=c->anyModel?((Number)c.get("model_score")).doubleValue():rankScore(c);
        pool.sort((a,b)-> {
            int byTransport=Integer.compare(transportTier(a),transportTier(b));
            if(byTransport!=0) return byTransport;
            int score=Double.compare(ranking.applyAsDouble(b),ranking.applyAsDouble(a));
            if(score!=0) return score;
            int level=Integer.compare(TeacherLevel.rank(b.get("teacher_level")),TeacherLevel.rank(a.get("teacher_level")));
            return level!=0?level:Long.compare(((Number)a.get("teacher_id")).longValue(),((Number)b.get("teacher_id")).longValue());
        });
        Double cutoff=pool.size()<target?null:ranking.applyAsDouble(pool.get(target-1));
        int cutoffTier=pool.size()<target?9:transportTier(pool.get(target-1));
        List<Map<String,Object>> selected=new ArrayList<>();
        for(Map<String,Object> c:pool) if(cutoff==null||transportTier(c)<cutoffTier||transportTier(c)==cutoffTier&&ranking.applyAsDouble(c)>=cutoff) selected.add(c);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("selected",selected);result.put("travel_pending",pending);
        result.put("scoring_pending",scoringPending);
        result.put("air_candidates",air);
        result.put("pool_size",catchmentSize);result.put("ranked_pool_size",pool.size());result.put("cutoff_score",cutoff);
        result.put("planning_pool_size",planningPoolSize);
        result.put("planning_selected_count",selected.stream().filter(c->Boolean.TRUE.equals(((Map<?,?>)c.get("dispatch_fit")).get("planning_included"))).count());
        result.put("planning_notice",planningPoolSize>0?"部分候选依据公开城市旅时参考初筛，标注出行待核；粗时间档不参与能力赋分，也不代表具体行程已确认。":"");
        if(sharedPlanningPoolSize>0){
            result.put("shared_planning_pool_size",sharedPlanningPoolSize);
            result.put("shared_planning_selected_count",selected.stream().filter(c->CityPlanningSelection.SHARED_POLICY.equals(((Map<?,?>)c.get("dispatch_fit")).get("planning_policy"))).count());
            result.put("planning_notice","部分候选采用明确往返语境的一份单程共享参考，原精度保留，仅作投标前初筛；不是两向独立观测或严格耗时上界，不赋交通时间分，出行待核。");
        }
        if(contextPlanningPoolSize>0){
            result.put("context_planning_pool_size",contextPlanningPoolSize);
            result.put("context_planning_selected_count",selected.stream().filter(c->ContextSharedPlanningAdapter.ownsPolicy(((Map<?,?>)c.get("dispatch_fit")).get("planning_policy"))).count());
            result.put("planning_notice",baselinePresent?"部分候选采用报道中的现有旅时参考，保留原精度，仅作投标前初筛；实际出行待核，市内接驳另行确认。":"部分候选采用城市往返语境的一份旅时参考，保留原精度，仅作投标前初筛；实际出行待核，市内接驳另行确认。");
        }
        if(routed!=null&&contextPlanningPoolSize>0)result.put("planning_notice","部分候选采用公开城市旅时参考，保留各来源原精度，仅作投标前初筛；实际出行待核，市内接驳另行确认。");
        result.put("ties_included",Math.max(0,selected.size()-target));result.put("expanded_rail_reference_minutes",expanded>0?maximum:null);
        result.put("selection_mode","all_rail_cities_then_air");result.put("candidate_routes_complete",pending.isEmpty());
        result.put("model_scoring_complete",scoringPending.isEmpty()&&pool.stream().allMatch(c->c.get("model_score") instanceof Number));
        result.put("scoring_mode",anyModel?"model_only":"unscored_evidence_fallback");
        result.put("maximum_nearby_minutes",maximum);
        result.put("policy",pref.localPreferenceActive()?
                "同城与全部铁路小于4小时的合格老师统一比较，不因近处够三位而停止；不足再纳入已核实的直飞小于3小时备选。池内比模型分，同分比等级，第三名同分全留。未知路线仍待核实，不按公里估算。":
                pref.localPreferenceRequested()?"授课地区待核实，候选保留在交通待核实列表，暂不自动纳入就近推荐；不按不限地区处理。":
                "未启用地区限制，按模型匹配分选取，同分时等级高的在前，末位并列全部保留；未评分时提供文字依据参考顺序");
        if(sharedPlanningPoolSize>0)result.put("policy","同城、全部合格铁路与明确标注的城市旅时规划候选统一比较，不因近处够三位而停止；共享单值按原精度与当前上限比较，不证明双向严格可达，也不提供航空排除证明。池内比模型分，同分比等级，第三名同分全留；不足时仅考虑独立核验合格的航空备选。");
        if(contextPlanningPoolSize>0)result.put("policy","同城、全部合格铁路与明确标注的城市旅时规划候选统一比较，不因近处够三位而停止；共享参考保留原精度，仅作投标前初筛，不提供航空排除证明。池内比模型分，同分比等级，末位同分全留；不足时仅考虑独立核验合格的航空备选。");
        return result;
    }
}
