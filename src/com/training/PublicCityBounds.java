package com.training;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;

/** Typed, direction-specific public city references. No loading, network,
 * approval, hash-chain replacement, timetable estimation, or airline exclusion. */
final class PublicCityBounds {
    static final String DURATION_BASIS="public_city_duration_bounds";
    static final String SCOPE="city_to_city_rail_reference";
    static final String LANGUAGE_POLICY="zh-within-stated-hour-v1";
    private static final String NUMBER="(?:[0-9]{1,4}|[零〇一二两三四五六七八九十百]{1,5})";
    private static void require(boolean ok,String reason) { CityTravelReference.require(ok,reason); }
    private static String text(Map<?,?> row,String key) { return CityTravelReference.text(row,key); }

    static final class Duration {
        final String kind, originalText, precision, inferenceNotice;
        final Integer lowerMinutes, upperMinutes, approximateMinutes;
        final boolean lowerInclusive, upperInclusive;
        final Map<String,Object> calculationInput;
        Duration(String kind,String original,Integer lower,boolean lowerInclusive,Integer upper,boolean upperInclusive,
                 Integer approximate,String precision,String inference) {
            this(kind,original,lower,lowerInclusive,upper,upperInclusive,approximate,precision,inference,Map.of());
        }
        Duration(String kind,String original,Integer lower,boolean lowerInclusive,Integer upper,boolean upperInclusive,
                 Integer approximate,String precision,String inference,Map<String,Object> calculationInput) {
            this.kind=kind;this.originalText=original;this.lowerMinutes=lower;this.lowerInclusive=lowerInclusive;
            this.upperMinutes=upper;this.upperInclusive=upperInclusive;this.approximateMinutes=approximate;
            this.precision=precision;this.inferenceNotice=inference;this.calculationInput=Collections.unmodifiableMap(new LinkedHashMap<>(calculationInput));
        }
        boolean strictlyUnder(int limit) {
            return limit>0&&upperMinutes!=null&&(upperMinutes<limit||upperMinutes==limit&&!upperInclusive);
        }
        // Interval intersection is only a comparison aid, never a route proof.
        boolean overlaps(Duration other) {
            if(other==null)return false;
            if(upperMinutes!=null&&other.lowerMinutes!=null&&
                (upperMinutes<other.lowerMinutes||upperMinutes.equals(other.lowerMinutes)&&!(upperInclusive&&other.lowerInclusive)))return false;
            if(other.upperMinutes!=null&&lowerMinutes!=null&&
                (other.upperMinutes<lowerMinutes||other.upperMinutes.equals(lowerMinutes)&&!(other.upperInclusive&&lowerInclusive)))return false;
            return true;
        }
        Map<String,Object> toMap() {
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("kind",kind);m.put("original_text",originalText);m.put("display",originalText);
            boolean clockDerived=precision.equals("computed_same_service_departure_arrival");
            if(clockDerived){m.put("display",lowerMinutes+"分钟（公开时刻差）");m.put("calculated_minutes",lowerMinutes);m.put("calculation_notice","按同一列车所选出发站的发车时刻和到达站的到达时刻计算；不是来源直接公布的最快用时。");m.put("calculation_input",calculationInput);}
            m.put("lower_minutes",lowerMinutes);m.put("lower_inclusive",lowerInclusive);
            m.put("upper_minutes",upperMinutes);m.put("upper_inclusive",upperInclusive);
            m.put("reported_minutes",kind.equals("exact")&&!clockDerived?lowerMinutes:null);
            m.put("approximate_minutes",approximateMinutes);m.put("precision",precision);
            m.put("inference_notice",inferenceNotice);m.put("language_policy",inferenceNotice.isEmpty()?null:LANGUAGE_POLICY);
            m.put("published_exact_upper",upperMinutes!=null&&inferenceNotice.isEmpty()&&!clockDerived);
            m.put("not_a_global_minimum_or_door_to_door_guarantee",true);return m;
        }
    }
    private static int number(String s) {
        if(s.matches("[0-9]+"))return Integer.parseInt(s);
        if(s.equals("两"))return 2;
        String digits="零一二三四五六七八九";s=s.replace('〇','零');
        if(s.length()==1&&digits.indexOf(s)>=0)return digits.indexOf(s);
        if(s.matches("[一二三四五六七八九]?十[一二三四五六七八九]?")) {
            int ten=s.indexOf('十');return (ten==0?1:digits.indexOf(s.charAt(0)))*10+
                (ten==s.length()-1?0:digits.indexOf(s.charAt(s.length()-1)));
        }
        throw new IllegalArgumentException("city_duration_numeral_unsupported");
    }
    private static int exactMinutes(String s) {
        Matcher m=Pattern.compile("^(?:("+NUMBER+")小时)?(?:("+NUMBER+")(?:分钟|分))?$").matcher(s);
        require(m.matches()&&(m.group(1)!=null||m.group(2)!=null),"city_duration_not_explicit");
        int hours=m.group(1)==null?0:number(m.group(1)),minutes=m.group(2)==null?0:number(m.group(2));
        require(m.group(1)==null||minutes<60,"city_duration_invalid_minute_component");
        return RailTimetable.minute(hours*60+minutes);
    }
    private static Duration exact(String original,int minutes) {
        return new Duration("exact",original,minutes,true,minutes,true,null,"published_number","");
    }
    static Duration duration(String original) {
        require(original!=null&&!original.isBlank()&&original.length()<=100,"city_duration_missing");
        String s=original.strip();require(!s.matches(".*[\\r\\n；;。].*"),"city_duration_multiple_statements");
        if(s.startsWith("最快"))s=s.substring(2);
        String core=s;
        if(core.startsWith("约")||core.startsWith("大约")||core.endsWith("左右")) {
            core=core.replaceFirst("^(?:大约|约)","").replaceFirst("左右$","");
            int center=exactMinutes(core);
            return new Duration("approximate_unknown",original,null,false,null,false,center,"approximate_no_defined_bounds","");
        }
        // Explicit agreed language policy: stay within the stated hour. Do not
        // assume that "四十多" means less than fifty, or call it a published cap.
        Matcher colloquial=Pattern.compile("^("+NUMBER+")小时("+NUMBER+")多(?:分钟|分)$").matcher(s);
        if(colloquial.matches()) {
            int h=number(colloquial.group(1)),m=number(colloquial.group(2));
            require(m>0&&m<60&&m%10==0,"city_colloquial_minute_component_unsupported");
            int lower=RailTimetable.minute(h*60+m),upper=RailTimetable.minute((h+1)*60);
            return new Duration("open_interval",original,lower,false,upper,false,null,"colloquial_within_hour_inference",
                "依据中文时长表述解释，非公布精确上界；只保守限定在已表述小时内，不假设低于下一个十分钟。");
        }
        Matcher more=Pattern.compile("^("+NUMBER+")多(?:分钟|分)$").matcher(s);
        if(more.matches())return new Duration("open_interval",original,RailTimetable.minute(number(more.group(1))),false,null,false,null,"colloquial_no_upper_bound","");
        Matcher hoursMore=Pattern.compile("^("+NUMBER+")(?:个)?多小时$").matcher(s);
        if(hoursMore.matches())return new Duration("open_interval",original,RailTimetable.minute(number(hoursMore.group(1))*60),false,null,false,null,"colloquial_no_upper_bound","");
        Matcher interval=Pattern.compile("^(?:超过|大于)(.+?)(?:且|但)(?:不足|少于|小于)(.+)$").matcher(s);
        if(interval.matches()) {
            int lower=exactMinutes(interval.group(1)),upper=exactMinutes(interval.group(2));require(lower<upper,"city_duration_interval_empty");
            return new Duration("open_interval",original,lower,false,upper,false,null,"published_explicit_interval","");
        }
        for(String prefix:List.of("不超过","至多","最多","不足","不到","少于","小于","控制在"))if(s.startsWith(prefix)) {
            core=s.substring(prefix.length()).replaceFirst("(?:以内|之内|以下)$","");int upper=exactMinutes(core);
            boolean inclusive=List.of("不超过","至多","最多","控制在").contains(prefix);
            return new Duration("upper_bound",original,0,false,upper,inclusive,null,"published_upper_bound","");
        }
        for(String suffix:List.of("以内","之内","以下"))if(s.endsWith(suffix))
            return new Duration("upper_bound",original,0,false,exactMinutes(s.substring(0,s.length()-suffix.length())),true,null,"published_upper_bound","");
        return exact(original,exactMinutes(s));
    }
    static boolean bothStrictlyUnder(Duration outward,Duration returning,int limit) {
        return outward!=null&&returning!=null&&outward.strictlyUnder(limit)&&returning.strictlyUnder(limit);
    }
    /** Reconstruct a rendered typed duration, never trust stored minute fields.
     * Source approval, hash identity and independent directions remain upstream. */
    static Duration fromStored(Map<?,?> stored){
        if(!"computed_same_service_departure_arrival".equals(stored.get("precision")))return duration(text(stored,"original_text"));
        Map<String,Object> input=new LinkedHashMap<>();CityTravelReference.map(stored.get("calculation_input")).forEach((k,v)->input.put((String)k,v));
        require(PublicCityContext.SCHEDULE_CHAIN.equals(input.get("context_binding_type")),"city_computed_duration_input_missing");
        input.put("context_binding",PublicCityContext.describe(input));
        return PublicCityContext.boundClockDuration(input);
    }
    private static String assertion(Map<?,?> content) { return PublicTransportEvidence.assertion(content); }
    private static String quotedDuration(Map<?,?> content,String quote,String from,String to) {
        if(content.containsKey("context_binding_type")||content.containsKey("context_binding"))
            return PublicCityContext.boundDuration(content);
        String declared=text(content,"duration_text");require(!declared.isBlank(),"city_duration_text_missing");
        String route="(?:从)?"+Pattern.quote(from)+"(?:至|到|→)"+Pattern.quote(to);
        String modifiers="(?:(?:的|乘坐|高铁|动车|铁路|列车|运行|旅行|全程|耗时|用时|最快|最短|仅需|只需要|需要|为|时间|缩短至)|[，,:：\\s])*";
        if(quote.matches(route+modifiers+Pattern.quote(declared)+"(?:可达)?"))return declared;
        // Exact, positional 分别 structure: one origin, 2..8 distinct named
        // destination cities and exactly the same number of duration tokens.
        String prefix="(?:(?:国铁集团|铁路部门)持续优化列车运行图，)?";
        Matcher separate=Pattern.compile(prefix+"(?:从)?"+Pattern.quote(from)+"(?:至|到)([^，。；;]+?)最快分别([^，。；;]+?)可达").matcher(quote);
        if(separate.matches()) {
            String[] cities=separate.group(1).split("、",-1),times=separate.group(2).split("、",-1);
            require(cities.length>=2&&cities.length<=8&&times.length==cities.length,"city_separate_lengths_mismatch");
            Set<String> unique=new HashSet<>();int target=-1;
            for(int i=0;i<cities.length;i++) {
                require(cities[i].matches("[\\p{IsHan}]{2,20}")&&unique.add(cities[i])&&!cities[i].equals(from),"city_separate_destinations_invalid");
                duration(times[i]);if(cities[i].equals(to))target=i;
            }
            require(target>=0&&times[target].equals(declared),"city_separate_duration_wrong_destination");return declared;
        }
        // A single route's explicit former car trip contrasted with its current
        // train trip. The old road duration is never returned or added to rail.
        String oldRoad="(?:"+NUMBER+")(?:到"+NUMBER+")?小时";
        if(quote.matches("以前从"+Pattern.quote(from)+"到"+Pattern.quote(to)+"，开汽车需要"+oldRoad+"，现在乘坐动车只需要"+Pattern.quote(declared)))return declared;
        throw new IllegalArgumentException("city_duration_not_bound_to_direction");
    }
    /** Hash/immutable evidence identity and reference review are verified by the
     * existing CityTravelReference pipeline, not replaced by this helper. */
    static Duration validateEvidence(Map<?,?> content,LocalDate today) {
        require(PublicTransportEvidence.usable(content),"city_public_use_basis_missing");
        require(DURATION_BASIS.equals(content.get("duration_basis"))&&SCOPE.equals(content.get("scope")),"city_bounds_basis_or_scope_invalid");
        require("public_transport_duration_statement".equals(content.get("evidence_type"))&&"public_page".equals(content.get("source_access")),"city_public_evidence_type_invalid");
        String publisher=text(content,"publisher_kind");
        require(List.of("government","transport_operator","official_media").contains(publisher),"city_publisher_unsupported");
        if(publisher.equals("official_media"))require("transport_operator_statement".equals(content.get("attribution_basis"))&&
            Boolean.TRUE.equals(content.get("transport_operator_attribution_checked"))&&!text(content,"attribution_text").isBlank(),"city_media_operator_attribution_pending");
        boolean scheduleChain=PublicCityContext.SCHEDULE_CHAIN.equals(content.get("context_binding_type"));
        require("rail".equals(content.get("mode"))&&("operating".equals(content.get("service_state"))||scheduleChain&&"published_schedule_baseline".equals(content.get("service_state"))),"city_not_operating_rail");
        require("explicit_direction".equals(content.get("direction_basis")),"city_direction_not_independent");
        require(Boolean.TRUE.equals(content.get("city_mapping_checked"))&&Boolean.TRUE.equals(content.get("transport_context_checked")),"city_context_or_mapping_pending");
        require(!text(content,"scope_note").isBlank(),"city_scope_note_missing");
        String fp=text(content,"from_province"),fc=text(content,"from_city"),tp=text(content,"to_province"),tc=text(content,"to_city");
        require(RegionDirectory.known(fp,fc)&&RegionDirectory.known(tp,tc)&&!(fp.equals(tp)&&fc.equals(tc)),"city_endpoints_unknown_or_same");
        require(fc.equals(content.get("from_endpoint"))&&tc.equals(content.get("to_endpoint")),"city_endpoint_is_not_city_label");
        for(String field:List.of("from_station","to_station","reference_minutes","minimum_minutes","minutes"))
            require(!content.containsKey(field)||content.get(field)==null||text(content,field).isBlank(),"city_bounds_must_not_masquerade_as_station_minutes");
        require(!Boolean.TRUE.equals(content.get("temporary_service"))&&!Boolean.TRUE.equals(content.get("coverage_complete")),"city_temporary_or_global_scope_unsupported");
        CityTravelReference.source(content);
        LocalDate published=CityTravelReference.date(content,"source_published_on",today),researched=CityTravelReference.date(content,"research_on",today);
        require(!published.isAfter(researched),"city_research_before_publication");
        require(ChronoUnit.DAYS.between(published,today)<=PublicTransportEvidence.MAX_PUBLICATION_AGE_DAYS,"city_source_needs_recent_confirmation");
        if(scheduleChain)PublicStationContext.effectiveChainSchedule(content,published,researched,today);
        else if(!text(content,"effective_from").isBlank())require(!CityTravelReference.date(content,"effective_from",today).isAfter(published),"city_notice_not_post_operation");
        if(!text(content,"effective_until").isBlank())require(!LocalDate.parse(text(content,"effective_until")).isBefore(today),"city_source_period_ended");
        String quote=assertion(content);
        // Only the complete, independently clock-bound multi-service grammar
        // may contain its explicit round-trip introduction and qualitative slogan.
        String blocked=PublicCityContext.SERVICE_ROUNDTRIP.equals(content.get("context_binding_type"))?
            "累计|合计|总计|交通圈|工作圈|需换乘|需要换乘|中转|不再|不能|不需要|未能|未达到|无法":
            "累计|往返|合计|总计|生活圈|交通圈|工作圈|需换乘|需要换乘|中转|不再|不能|不需要|未能|未达到|无法";
        require(!Pattern.compile(blocked).matcher(quote).find(),"city_not_single_current_direction_duration");
        Duration parsed=scheduleChain?PublicCityContext.boundClockDuration(content):duration(quotedDuration(content,quote,fc,tc));
        if(!parsed.inferenceNotice.isEmpty())require(LANGUAGE_POLICY.equals(content.get("language_policy")),"city_language_inference_policy_not_selected");
        if(content.containsKey("duration_bounds"))require(CityTravelReference.canonical(parsed.toMap()).equals(CityTravelReference.canonical(content.get("duration_bounds"))),"city_declared_bounds_mismatch");
        return parsed;
    }
    /** Pure pairing aid. Caller still applies library integrity, latest negative
     * evidence, review deadlines, cross-library conflicts and administrator limits. */
    static Map<String,Object> validatePair(Map<?,?> outward,Map<?,?> returning,LocalDate today,int limit) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("status","city_reference_pending");result.put("eligibility","unknown");
        result.put("rail_exclusion_complete",false);result.put("air_eligibility","not_determined");
        try {
            require(limit>0&&limit<=TravelMatrix.RAIL_LIMIT,"city_rail_limit_invalid");
            for(String name:List.of("province","city"))require(Objects.equals(outward.get("from_"+name),returning.get("to_"+name))&&
                Objects.equals(outward.get("to_"+name),returning.get("from_"+name)),"city_return_direction_mismatch");
            Duration a=validateEvidence(outward,today),b=validateEvidence(returning,today);
            result.put("outbound_duration",a.toMap());result.put("return_duration",b.toMap());
            result.put("source_published_on",List.of(outward.get("source_published_on"),returning.get("source_published_on")));
            result.put("source_urls",List.of(outward.get("source_url"),returning.get("source_url")));
            if(bothStrictlyUnder(a,b,limit)) {result.put("status","city_rail_bounds_reference");result.put("eligibility","rail");}
            else result.put("reason","city_bounds_do_not_prove_both_strictly_within_limit");
        } catch(RuntimeException invalid) {result.put("reason",Objects.toString(invalid.getMessage(),"city_bounds_invalid"));}
        return result;
    }
    private PublicCityBounds() {}
}
