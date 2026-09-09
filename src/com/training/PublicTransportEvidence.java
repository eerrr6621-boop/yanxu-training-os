package com.training;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;

/** Independently curated public transport facts, not a licensed timetable or
 * human approval. These records support pre-bid city screening only. */
final class PublicTransportEvidence {
    static final String SCHEMA="public-city-travel-reference-v1";
    static final int MAX_PUBLICATION_AGE_DAYS=365;
    static String text(Map<?,?> row,String key) { return CityTravelReference.text(row,key); }
    static void require(boolean ok,String reason) { CityTravelReference.require(ok,reason); }
    static boolean schema(Map<?,?> library) { return SCHEMA.equals(library.get("version")); }
    static boolean sourceStateAllowed(Map<?,?> row) {
        for(String field:List.of("status","review_status","source_status"))
            if(List.of("revoked","withdrawn","suspended","cancelled","rejected","superseded","expired").contains(text(row,field)))return false;
        for(String field:List.of("cancelled","suspended","superseded"))if(Boolean.TRUE.equals(row.get(field)))return false;
        return true;
    }
    static boolean usable(Map<?,?> row) {
        return sourceStateAllowed(row) && RailTimetable.businessUseAllowed(row) && Boolean.TRUE.equals(row.get("business_import_allowed")) &&
            "independently_curated_public_facts".equals(row.get("use_basis")) &&
            Boolean.FALSE.equals(row.get("license_claimed")) && !row.containsKey("usage_authorized") && !row.containsKey("authorization_ref");
    }
    static int duration(String value) {
        Matcher m=Pattern.compile("^(?:(\\d{1,2})小时)?(?:(\\d{1,3})(?:分钟|分))?$").matcher(value);
        require(m.matches()&&(m.group(1)!=null||m.group(2)!=null),"public_duration_not_explicit");
        int hours=m.group(1)==null?0:Integer.parseInt(m.group(1));
        int minutes=m.group(2)==null?0:Integer.parseInt(m.group(2));
        require(m.group(1)==null||minutes<60,"public_duration_invalid_minutes");
        return RailTimetable.minute(hours*60+minutes);
    }
    // Offsets refer to a complete original clause, not a paraphrase or a
    // substring cut out of a planning/negative statement. Unknown syntax stays pending.
    static String assertion(Map<?,?> content) {
        String original=text(content,"original_content");
        Object s=content.get("assertion_start"),e=content.get("assertion_end");
        require(s instanceof Number&&e instanceof Number,"public_assertion_offsets_missing");
        int start=((Number)s).intValue(),end=((Number)e).intValue();
        require(((Number)s).doubleValue()==start&&((Number)e).doubleValue()==end&&start>=0&&end>start&&end<=original.length(),"public_assertion_offsets_invalid");
        require((start==0||"。；;\n".indexOf(original.charAt(start-1))>=0)&&
            (end==original.length()||"。；;\n".indexOf(original.charAt(end))>=0),"public_assertion_not_complete_clause");
        // Curation supplies one independent source assertion. Do not select an
        // affirmative substring while silently dropping contextual conditions.
        // Narrative outside the declared span requires separate source review;
        // punctuation and whitespace alone do not assert additional facts.
        require(original.substring(0,start).matches("[\\p{P}\\p{Z}\\s]*")&&
            original.substring(end).matches("[\\p{P}\\p{Z}\\s]*"),"public_assertion_context_not_consumed");
        String value=original.substring(start,end).strip();
        require(!Pattern.compile("规划|预计|计划|拟[开建运]|将[于开达]|尚未|未开通|目标|临时|暑运|春运|季节|停运|取消|暂停").matcher(value).find(),"public_assertion_not_current_regular_service");
        return value;
    }
    static int statedMinutes(Map<?,?> content,String value,String from,String to) {
        String wording=text(content,"duration_text");
        int minutes=duration(wording);
        if("explicit_round_trip_product".equals(content.get("direction_basis"))) {
            require("rail".equals(content.get("mode"))&&"city_station_to_station".equals(content.get("scope")),"public_round_trip_scope_unsupported");
            require("each_one_way_service".equals(content.get("duration_applies_to")),"public_round_trip_duration_scope_missing");
            // A published round-trip train product is explicit bilateral evidence,
            // unlike mirroring a one-way fastest time. Bind one pair and one
            // per-service duration; totals, approximations and mixed pairs fail.
            String product="(?:每日)?开行[1-9][0-9]*列(?:"+Pattern.quote(from)+"往返"+Pattern.quote(to)+"|"+
                Pattern.quote(to)+"往返"+Pattern.quote(from)+")的(?:标杆|高铁|动车)列车，全程用时"+Pattern.quote(wording);
            require(value.matches(product),"public_round_trip_not_explicit_product");
            return minutes;
        }
        String route="(?:从)?"+Pattern.quote(from)+"(?:至|到|→)"+Pattern.quote(to);
        String modifiers="(?:(?:高铁|动车|铁路|直飞|航程|列车|运行|旅行|全程|耗时|用时|最快|最短|仅需|需要|为|时间|按两市全部车站及所有铁路路径统计)|[，,:：\\s])*";
        require(value.matches(route+modifiers+Pattern.quote(wording)),"public_duration_not_bound_to_single_direction");
        return minutes;
    }
    static void effectiveSchedule(Map<?,?> content,LocalDate published,LocalDate researched,LocalDate effective,LocalDate today) {
        require("rail".equals(content.get("mode"))&&"regular_published_running_diagram".equals(content.get("schedule_basis")),"public_schedule_basis_unsupported");
        require(!published.isAfter(effective)&&!effective.isAfter(researched),"public_schedule_not_effective_when_researched");
        Map<?,?> notice=CityTravelReference.map(content.get("schedule_effective_notice"));
        require(sourceStateAllowed(notice)&&!Boolean.TRUE.equals(notice.get("temporary_service")),"public_schedule_notice_invalidated");
        require(!notice.containsKey("source_access")||"public_page".equals(notice.get("source_access")),"public_schedule_notice_not_public");
        require(text(content,"source_url").equals(notice.get("source_url"))&&published.toString().equals(notice.get("source_published_on")),"public_schedule_notice_source_mismatch");
        String value=assertion(notice);
        // Recognize a dated, definitive implementation notice, not a railway
        // construction proposal. No year inference across publication years.
        Matcher m=Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})日起，铁路部门将实施新的列车运行图").matcher(value);
        require(m.matches(),"public_schedule_effective_notice_unsupported");
        int year=m.group(1)==null?published.getYear():Integer.parseInt(m.group(1));
        LocalDate stated=LocalDate.of(year,Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));
        require(effective.equals(stated),"public_schedule_effective_date_mismatch");
        require(!Boolean.TRUE.equals(content.get("suspended"))&&!Boolean.TRUE.equals(content.get("superseded")),"public_schedule_suspended_or_superseded");
        String end=text(content,"effective_until");
        if(!end.isBlank())require(!LocalDate.parse(end).isBefore(today),"public_schedule_already_ended");
    }
    static int clockMinutes(String value) {
        String[] parts=value.split(":");
        int h=Integer.parseInt(parts[0]),m=Integer.parseInt(parts[1]);
        require(h<24&&m<60,"public_endpoint_clock_invalid");return h*60+m;
    }
    static int endpointMinutes(Map<?,?> content,String value,String from,String to) {
        require("same_service_departure_arrival".equals(content.get("time_binding"))&&
            "departure".equals(content.get("departure_kind"))&&"arrival".equals(content.get("arrival_kind")),"public_endpoint_roles_unconfirmed");
        require(content.get("arrival_day_offset") instanceof Number&&((Number)content.get("arrival_day_offset")).doubleValue()==0,
            "public_overnight_requires_separate_contract");
        // Supported station tokens terminate at their first 站. An intermediate
        // arrival cannot absorb parenthetical prose or a subsequent action and
        // thereby turn different services into one timetable chain. This is a
        // syntax contract, not a station-to-city directory or source approval.
        String station="(?:(?!站)[\\p{IsHan}A-Za-z0-9·-]){1,40}站";
        require(from.matches(station)&&to.matches(station),"public_endpoint_station_token_unsupported");
        String prefix="(?:这趟(?:早|晚)高峰列车|(?:早|晚)高峰时段，(?:[GDCZTK]\\d+(?:/\\d+)?次)?列车|(?:[GDCZTK]\\d+(?:/\\d+)?次)?列车)?";
        String arrival="\\d{1,2}:\\d{2}(?:到达|抵达)"+station;
        require(value.matches(prefix+"\\d{1,2}:\\d{2}从"+Pattern.quote(from)+"(?:始发|出发)(?:，(?:经(?:停)?"+station+"(?:，|后))?"+arrival+")+"),
            "public_same_service_statement_unsupported");
        Matcher departure=Pattern.compile("(\\d{1,2}:\\d{2})从([^，,。；;]+?)(始发|出发)").matcher(value);
        require(departure.find(),"public_departure_not_explicit");
        String depTime=departure.group(1);int depEnd=departure.end();
        require(departure.group(2).equals(from)&&!departure.find(),"public_multiple_or_wrong_departures");
        Matcher arrivals=Pattern.compile("(\\d{1,2}:\\d{2})(?:到达|抵达)("+station+")").matcher(value);
        int lastTime=-1,lastEnd=-1,count=1;String targetTime="";boolean reached=false;
        int previous=clockMinutes(depTime);
        while(arrivals.find()) {
            require(arrivals.start()>=depEnd&&!reached,"public_arrival_order_invalid");
            int current=clockMinutes(arrivals.group(1));
            require(current>previous,"public_times_not_increasing");previous=current;count++;
            if(arrivals.group(2).equals(to)){reached=true;targetTime=arrivals.group(1);lastTime=current;lastEnd=arrivals.end();}
        }
        require(reached&&lastEnd==value.length(),"public_arrival_not_final_endpoint");
        Matcher all=Pattern.compile("\\d{1,2}:\\d{2}").matcher(value);int clocks=0;while(all.find())clocks++;
        require(clocks==count&&depTime.equals(content.get("departure_time_text"))&&targetTime.equals(content.get("arrival_time_text")),"public_unbound_endpoint_times");
        require(!Pattern.compile("换乘|中转|次日|翌日|第二天").matcher(value).find(),"public_service_continuity_unconfirmed");
        return RailTimetable.minute(lastTime-clockMinutes(depTime));
    }
    static void evidence(Map<?,?> content,LocalDate today) {
        if(PublicCityBounds.DURATION_BASIS.equals(content.get("duration_basis"))) {
            PublicCityBounds.validateEvidence(content,today);return;
        }
        require(usable(content),"public_evidence_use_basis_missing");
        require("public_transport_duration_statement".equals(content.get("evidence_type")),"not_public_duration_statement");
        require("public_page".equals(content.get("source_access")),"not_public_page_evidence");
        require(List.of("government","transport_operator").contains(text(content,"publisher_kind")),"public_publisher_not_primary");
        boolean schedule="published_schedule_baseline".equals(content.get("service_state"));
        require("operating".equals(content.get("service_state"))||schedule,"planned_or_unconfirmed_operation");
        require(List.of("explicit_direction","explicit_round_trip_product").contains(text(content,"direction_basis")),"public_direction_unspecified");
        require(Boolean.TRUE.equals(content.get("city_mapping_checked")),"public_city_mapping_pending");
        CityTravelReference.source(content);
        LocalDate published=CityTravelReference.date(content,"source_published_on",today);
        LocalDate researched=CityTravelReference.date(content,"research_on",today);
        LocalDate effective=CityTravelReference.date(content,"effective_from",today);
        require(!published.isAfter(researched),"public_research_before_publication");
        if(schedule)effectiveSchedule(content,published,researched,effective,today);
        else require(!effective.isAfter(published),"public_notice_not_post_operation");
        require(ChronoUnit.DAYS.between(published,today)<=MAX_PUBLICATION_AGE_DAYS,"public_source_needs_recent_confirmation");
        String quote=assertion(content),from=text(content,"from_endpoint"),to=text(content,"to_endpoint");
        require(!from.isBlank()&&!to.isBlank()&&!from.equals(to),"public_endpoints_missing");
        require(List.of("","direct_duration_statement","published_endpoint_times").contains(text(content,"duration_basis")),"public_duration_basis_unsupported");
        int minutes="published_endpoint_times".equals(content.get("duration_basis"))?endpointMinutes(content,quote,from,to):statedMinutes(content,quote,from,to);
        String scope=text(content,"scope");
        if(scope.equals("all_city_stations_and_rail_itineraries")) {
            require("rail".equals(content.get("mode"))&&Boolean.TRUE.equals(content.get("coverage_complete"))&&
                "explicit_citywide_fastest".equals(content.get("minimum_basis")),"public_citywide_minimum_not_established");
            require(from.equals(text(content,"from_city"))&&to.equals(text(content,"to_city"))&&
                quote.contains("按两市全部车站及所有铁路路径统计")&&(quote.contains("最快")||quote.contains("最短"))&&
                !"published_endpoint_times".equals(content.get("duration_basis")),"public_citywide_scope_not_in_statement");
            require(minutes==RailTimetable.minute(content.get("minimum_minutes")),"public_minimum_mismatch");
        } else {
            require(minutes==RailTimetable.minute(content.get("reference_minutes")),"public_minutes_mismatch");
            require(List.of("city_station_to_station","city_airport_to_airport_nonstop").contains(scope),"public_scope_invalid");
        }
        // A cancelled/seasonal/planned witness is not made permanent by curation.
        require(!Boolean.TRUE.equals(content.get("temporary_service"))&&!Boolean.TRUE.equals(content.get("cancelled")),"public_temporary_or_cancelled");
        if("air".equals(content.get("mode"))) require(Boolean.TRUE.equals(content.get("nonstop"))&&
            "passenger".equals(content.get("service_type"))&&quote.contains("直飞"),"public_air_not_explicit_nonstop");
    }
    static LocalDate reference(Map<?,?> reference,LocalDate today) {
        require(usable(reference),"public_reference_use_basis_missing");
        require("source_checked".equals(reference.get("review_status")),"public_reference_not_source_checked");
        require("assistant".equals(reference.get("reviewer_type")),"public_reference_actor_mislabeled");
        require(!text(reference,"reviewer_id").isBlank()&&"direction_quote_duration_city_crosscheck".equals(reference.get("review_method")),"public_reference_checks_missing");
        require("pre_bid_city_catchment".equals(reference.get("applicable_scope")),"public_reference_wrong_scope");
        LocalDate researched=CityTravelReference.date(reference,"research_on",today),reviewed=CityTravelReference.date(reference,"reviewed_on",today);
        require(!researched.isAfter(reviewed),"public_research_after_review");
        LocalDate due=text(reference,"review_due_on").isBlank()?reviewed.plusDays(90):LocalDate.parse(text(reference,"review_due_on"));
        require(due.isAfter(reviewed)&&!due.isAfter(reviewed.plusDays(180))&&!due.isBefore(today),"public_review_interval_invalid_or_overdue");
        return due;
    }
    private PublicTransportEvidence() {}
}
