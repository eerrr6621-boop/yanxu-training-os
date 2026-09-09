package com.training;

import java.net.URI;
import java.time.*;
import java.util.*;

/** Explicit public-UI sample metadata, only inside the existing protected dual-read envelope.
 * This is neither a network client nor a licence/reader identity verifier. */
final class PublicUiCitySample {
    static final String METHOD="dual_review_curated_public_ui_sample_v1";
    static final String SOURCE_TYPE="official_public_rail_ui_sample", DATE_BASIS="query_service_day_v1";
    static final String DATE_BASIS_V2="query_and_service_day_v2";
    static boolean method(Object value){return METHOD.equals(value)||PublicUiStopTable.method(value)||PublicUiPopupTable.method(value);}
    static String sourceType(Object method){return METHOD.equals(method)?SOURCE_TYPE:PublicUiStopTable.method(method)?PublicUiStopTable.SOURCE_TYPE:PublicUiPopupTable.method(method)?PublicUiPopupTable.SOURCE_TYPE:"";}
    static boolean source(Map<?,?> source){return SOURCE_TYPE.equals(source.get("source_type"))||PublicUiStopTable.SOURCE_TYPE.equals(source.get("source_type"))||PublicUiPopupTable.SOURCE_TYPE.equals(source.get("source_type"));}
    private static void need(boolean value,String reason){CityPlanningReference.need(value,"ui_sample_"+reason);}
    private static Map<?,?> map(Object x){return CityPlanningReference.map(x);}
    private static String text(Map<?,?> x,String key){return CityPlanningReference.text(x,key);}
    /** Source identity is the ordinary public page, not its mutable query string.
     * Never rewrites the observed URL stored in either reader's event. */
    static boolean ordinaryPage(String address){
        try{URI url=URI.create(address);return "https".equals(url.getScheme())&&"kyfw.12306.cn".equals(url.getHost())&&"/otn/leftTicket/init".equals(url.getRawPath())&&url.getUserInfo()==null&&url.getPort()==-1&&url.getRawFragment()==null;}catch(RuntimeException invalid){return false;}
    }
    private static boolean pageMatches(Map<?,?> source,String address){return SOURCE_TYPE.equals(source.get("source_type"))||PublicUiPopupTable.SOURCE_TYPE.equals(source.get("source_type"))?ordinaryPage(address):PublicUiStopTable.SOURCE_TYPE.equals(source.get("source_type"))&&PublicUiStopTable.ordinaryPage(address);}
    static boolean readUrlMatches(Map<?,?> source,Map<?,?> ref,Map<?,?> review,Object observed){
        if(!source(source))return Objects.equals(source.get("url"),observed); // Old sources stay literal.
        if(!(observed instanceof String address)||!pageMatches(source,address)||!pageMatches(source,text(source,"url")))return false;
        int matching=0;for(Object raw:CityPlanningReference.list(review.get("facts"))){Map<?,?> fact=map(raw),c=map(fact.get("canonical"));
            if(Objects.equals(source.get("source_id"),fact.get("source_id"))&&Objects.equals(ref.get("from_registry_id"),c.get("from_registry_id"))&&Objects.equals(ref.get("to_registry_id"),c.get("to_registry_id"))){if(!method(c.get("verification_method")))return false;matching++;}}
        return matching==1; // The regular envelope/date/heading/row validation still follows.
    }
    static LocalDate dateBasis(Map<?,?> basis){
        need(basis.keySet().equals(Set.of("kind","query_date","service_date"))&&(DATE_BASIS.equals(basis.get("kind"))||DATE_BASIS_V2.equals(basis.get("kind"))),"date_basis_invalid");
        LocalDate query=CityPlanningReference.day(basis.get("query_date")),service=CityPlanningReference.day(basis.get("service_date"));
        if(DATE_BASIS.equals(basis.get("kind")))need(query.equals(service),"same_day_sample_required");
        else need(!service.isBefore(query)&&!service.isAfter(query.plusDays(14)),"query_service_window");
        return service;
    }
    /** The new format's effective_on retains the selected train date, not a promise
     * that the timetable starts on that date. Planning becomes available when read. */
    static boolean separateDates(Map<?,?> fact){return method(fact.get("verification_method"))&&DATE_BASIS_V2.equals(fact.get("source_date_basis"));}
    static LocalDate timingDate(Map<?,?> fact){return CityPlanningReference.day(fact.get(separateDates(fact)?"source_query_date":"source_service_date"));}
    /** Tie an advance sample's query date to the earliest actual source-read event.
     * A later review must not refresh its original observation date. As for older
     * envelopes, missing seconds retain the dated packet fallback, not fake time. */
    static void validateQueryObservation(Map<?,?> source,Map<?,?> a,Map<?,?> b,Map<?,?> packet){
        if(!source(source))return;
        Map<?,?> basis=map(source.get("date_basis"));if(!DATE_BASIS_V2.equals(basis.get("kind")))return;
        dateBasis(basis);LocalDate first=null;String sid=text(source,"source_id");
        for(Map<?,?> review:List.of(a,b))for(Object raw:CityPlanningReference.list(review.get("read_events"))){
            Map<?,?> event=map(raw);if(!sid.equals(event.get("source_id")))continue;
            LocalDate day=event.get("recorded_at")==null?CityPlanningReference.day(packet.get("created_on")):
                Instant.parse(text(event,"recorded_at")).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
            if(first==null||day.isBefore(first))first=day;
        }
        need(CityPlanningReference.day(basis.get("query_date")).equals(first),"query_date_not_bound_to_original_read");
    }
    /** Preparation may use this only after heads() has validated the method and both readers. */
    static LocalDate referenceDate(Map<?,?> source){
        need(source(source)&&source.containsKey("published_on")&&source.get("published_on")==null,"publication_must_be_null");
        need(pageMatches(source,text(source,"url")),"ordinary_public_page_required");
        Map<?,?> basis=map(source.get("date_basis"));dateBasis(basis);
        return CityPlanningReference.day(basis.get("query_date"));
    }
    static LocalDate reviewDueDate(Map<?,?> source,LocalDate reviewed){
        LocalDate query=referenceDate(source);
        boolean separate=DATE_BASIS_V2.equals(map(source.get("date_basis")).get("kind"));
        LocalDate due=query.plusDays(separate?CityPlanningReference.REVIEW_DAYS:CityPlanningReference.SOURCE_DAYS);
        LocalDate reviewDue=reviewed.plusDays(CityPlanningReference.REVIEW_DAYS);
        return due.isBefore(reviewDue)?due:reviewDue;
    }
    static void validate(CuratedCityDuration.Direction direction,Map<?,?> canonical,Map<String,String> units){
        Map<?,?> source=direction.source();LocalDate queryDay=referenceDate(source),serviceDay=dateBasis(map(source.get("date_basis")));
        need(sourceType(canonical.get("verification_method")).equals(source.get("source_type")),"source_method_mismatch");
        need(method(canonical.get("verification_method"))&&CityPlanningReference.same(canonical.get("date_basis"),source.get("date_basis")),"method_or_date_basis_mismatch");
        need(canonical.get("published_on")==null&&!direction.reviewedOn().isBefore(queryDay),"publication_or_review_invalid");
        need("station_service_pair".equals(canonical.get("route_scope"))&&"observed_public_ui_service_sample".equals(canonical.get("service_state")),"scope_invalid");
        need(serviceDay.toString().equals(canonical.get("effective_on"))&&canonical.get("effective_until")==null,"sample_not_service_schedule_period");
        need("selected_service_sample_not_fastest_typical_or_upper_bound".equals(source.get("sample_scope")),"sample_scope_missing");
        need("general_reported".equals(source.get("duration_qualifier")),"sample_not_fastest_or_typical");
        Map<?,?> duration=map(canonical.get("duration"));
        need("same_service_clocks".equals(duration.get("kind"))&&CityPlanningReference.number(duration.get("arrival_day_offset"))==0,"same_day_clock_sample_required");
        need(text(duration,"service_id").matches("[GDC][0-9]{1,5}"),"high_speed_service_required");
        if(PublicUiStopTable.method(canonical.get("verification_method"))||PublicUiPopupTable.method(canonical.get("verification_method")))return; // Per-cell topology and dates are checked by duration().
        for(Map<?,?> fact:List.of(direction.readA(),direction.readB())){
            var date=CuratedCityDuration.span(fact,units,"service_date");
            need(date.text().equals(serviceDay.toString()),"visible_service_date_mismatch");
            var context=CuratedCityDuration.span(fact,units,"context");
            String prefix=text(direction.from(),"city")+" --> "+text(direction.to(),"city");
            need(context.text().startsWith(prefix)&&context.text().substring(prefix.length()).stripLeading().startsWith("（"),"query_direction_mismatch");
            var segment=CuratedCityDuration.span(fact,units,"segment");var elapsed=CuratedCityDuration.span(fact,units,"duration");var day=CuratedCityDuration.span(fact,units,"displayed_arrival_day");
            CuratedCityDuration.inside(elapsed,segment);CuratedCityDuration.inside(day,segment);
            need(CuratedCityDuration.span(fact,units,"arrival").end()<=elapsed.start()&&elapsed.end()<=day.start(),"elapsed_column_order_invalid");
            String raw=units.get(elapsed.unit());
            need((elapsed.start()==0||Character.isWhitespace(raw.charAt(elapsed.start()-1)))&&(elapsed.end()==raw.length()||Character.isWhitespace(raw.charAt(elapsed.end()))),"incomplete_displayed_elapsed");
            need("当日到达".equals(day.text())&&elapsed.text().matches("[0-9]{2}:[0-5][0-9]"),"displayed_elapsed_or_day_invalid");
            String[] values=elapsed.text().split(":");int minutes=Integer.parseInt(values[0])*60+Integer.parseInt(values[1]);
            need(minutes>0&&minutes==CityPlanningReference.number(duration.get("calculated_minutes")),"displayed_elapsed_mismatch");
        }
    }
    static void decorate(Map<String,Object> result,Map<?,?> canonical,Map<?,?> source){
        Map<?,?> basis=map(canonical.get("date_basis"));result.put("source_date_basis",basis.get("kind"));
        result.put("source_query_date",basis.get("query_date"));result.put("source_service_date",basis.get("service_date"));
        result.put("sample_scope","selected_service_sample_not_fastest_typical_or_upper_bound");
        result.put("source_type",sourceType(canonical.get("verification_method")));result.put("station_to_teaching_location_minutes",null);
        if(PublicUiStopTable.method(canonical.get("verification_method"))){var table=PublicUiStopTable.parse(source);result.put("query_service_id",table.queryService());result.put("displayed_service_id",table.service());result.put("query_display_equivalence_claimed",false);}
        if(PublicUiPopupTable.method(canonical.get("verification_method"))){var table=PublicUiPopupTable.parse(source);result.put("query_service_id",table.service());result.put("displayed_service_id",table.service());result.put("query_display_equivalence_claimed",false);}
        result.put("sample_notice",DATE_BASIS_V2.equals(basis.get("kind"))?
            "普通页面所选乘车日期的具名列车样本，按查询日计算复核期限；仅作城市初筛参考，非最快、典型或未来行程保证，站点至授课地点另核。":
            "普通页面当日具名列车样本，仅作城市初筛参考；非最快、典型或未来保证，站点至授课地点另核。");
    }
}
