package com.training;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Finite source-context binding for the existing public evidence chain.
 * This parser proves text alignment, not source authenticity or permission. */
final class PublicCityContext {
    static final String VERSION="public-city-context-v1";
    static final String PARALLEL="parallel_destination_durations";
    static final String STATION_LINES="departure_heading_direct_station";
    static final String DATED_ROUTE="dated_opening_current_route";
    static final String STATION_ROUTES="operating_station_route_list";
    static final String SERVICE_ROUNDTRIP="explicit_service_roundtrip_narrative";
    static final String SCHEDULE_CHAIN="published_diagram_same_service_stop_chain";
    static final String REPORTED_JOURNEY="dated_reported_actual_rail_journey";
    private static final String CITY="[\\p{IsHan}]{2,20}?";
    private static final String NUM="(?:[0-9]{1,4}|[零〇一二两三四五六七八九十百]{1,5})";
    private static final String TIME="(?:最快)?(?:约|大约|不超过|不足|不到|少于|控制在)?(?:"+NUM+"小时(?:"+NUM+"多?(?:分钟|分))?|"+NUM+"多?(?:分钟|分))(?:以内|左右)?";
    private static final Pattern UNSAFE=Pattern.compile("并非|并不|不是|不再|不能|未|没有|无法|取消|停运|停开|停售|暂停|不通|不适用|已到期|作废|失效|计划|预计|拟|届时|将|临时|春运|暑运|节假日|仅限|仅在|仅于|截至|曾经|当时|彼时|过去|旧图|原图|换乘|中转|转乘|尚|目标|往返|累计|合计|总计|生活圈|交通圈|驾车|汽车|公路|步行|航空|飞机|[“”‘’「」\"]");
    private static void require(boolean yes,String why){CityTravelReference.require(yes,why);}
    private static String text(Map<?,?> row,String key){return CityTravelReference.text(row,key);}
    private static Map<String,Object> m(Object... kv){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)r.put((String)kv[i],kv[i+1]);return r;}
    private static boolean city(String s){return RegionDirectory.knownElsewhere("",s);}

    /** Whitespace-only reading view. Every emitted token points back to the
     * original UTF-16 range; punctuation and source content are never rewritten. */
    static final class View {
        final String original,value;final List<Integer> offsets=new ArrayList<>();
        View(String original){
            this.original=original;StringBuilder b=new StringBuilder();
            for(int i=0;i<original.length();i++){
                char c=original.charAt(i);
                if(c==' '||c=='\t'||c=='\r'||c=='\n'||c=='\u3000')continue;
                b.append(c);offsets.add(i);
            }
            String s=b.toString();if(s.endsWith("。")){s=s.substring(0,s.length()-1);offsets.remove(offsets.size()-1);}
            value=s;
        }
        Map<String,Object> span(int start,int end){
            require(start>=0&&end>start&&end<=value.length(),"city_context_generated_span_invalid");
            int a=offsets.get(start),b=offsets.get(end-1)+1;
            String raw=original.substring(a,b),normalized=value.substring(start,end);
            // A city/station/duration cannot be assembled from different lines.
            require(raw.equals(normalized),"city_context_token_crosses_line_or_space");
            return m("start",a,"end",b,"text",raw);
        }
    }
    private static Map<String,Object> endpoint(View v,int start,int end,String province,String city,String kind){
        return m("province",province,"city",city,"kind",kind,"source_span",v.span(start,end));
    }
    private static Map<String,Object> parallel(Map<?,?> c,View v){
        require(!v.original.matches("(?s).*\\R.*"),"city_parallel_cross_paragraph_unsupported");
        String s=v.value,from=text(c,"from_city"),to=text(c,"to_city");
        String suffix="(?:的快速通达格局已常态化(?:，[东西南北向]{1,8}高铁主轴运力充足、通行高效)?)?";
        Matcher whole=Pattern.compile("(?<prefix>.*?)目前(?<from>"+CITY+")(?<items>(?:至|到)"+CITY+TIME+"(?:、(?:至|到)"+CITY+TIME+"){1,7})"+suffix).matcher(s);
        require(whole.matches(),"city_parallel_context_syntax_unsupported");
        require(whole.group("from").equals(from)&&city(from),"city_parallel_origin_mismatch");
        String prefix=whole.group("prefix");
        if(!prefix.isEmpty()){
            require(prefix.endsWith("，")&&prefix.contains("已实现")&&prefix.contains("稳定运营")&&
                !Pattern.compile(TIME).matcher(prefix).find(),"city_parallel_background_not_operating_or_has_other_time");
        }
        require(!s.matches(".*[。；;！？!?].*"),"city_parallel_multiple_statements");
        int base=whole.start("items");String items=whole.group("items");
        Matcher item=Pattern.compile("(?:至|到)(?<to>"+CITY+")(?<time>"+TIME+")").matcher(items);
        List<Object> rows=new ArrayList<>();Set<String> unique=new HashSet<>();int end=0,selected=-1;
        while(item.find()){
            require(item.start()==end||(item.start()==end+1&&items.charAt(end)=='、'),"city_parallel_unbound_content");
            String destination=item.group("to");require(city(destination)&&!destination.equals(from)&&unique.add(destination),"city_parallel_destination_unknown_or_duplicate");
            PublicCityBounds.duration(item.group("time"));
            if(destination.equals(to))selected=rows.size();
            rows.add(m("to_city",destination,"to_span",v.span(base+item.start("to"),base+item.end("to")),
                "duration_span",v.span(base+item.start("time"),base+item.end("time"))));end=item.end();
        }
        require(end==items.length()&&rows.size()>=2&&selected>=0,"city_parallel_target_missing");
        Map<?,?> chosen=CityTravelReference.map(rows.get(selected));
        return m("from_binding",endpoint(v,whole.start("from"),whole.end("from"),text(c,"from_province"),from,"city"),
            "to_binding",m("province",text(c,"to_province"),"city",to,"kind","city","source_span",chosen.get("to_span")),
            "duration_span",chosen.get("duration_span"),"all_destination_items",rows,"selected_item",selected,
            "precision_basis","published_city_narrative");
    }
    private static Map<String,Object> stationLines(Map<?,?> c,View v){
        String from=text(c,"from_city"),to=text(c,"to_city");
        require(v.original.contains("\n"),"city_station_context_not_lines");
        Matcher lines=Pattern.compile("(?<heading>"+CITY+")出发(?<from>"+CITY+"站)(?:全天)?开往(?<destination>"+CITY+")的(?:高铁|动车)"+
            "(?:超过"+NUM+"趟)?(?<time>"+TIME+")即可直达(?<to>"+CITY+"站)").matcher(v.value);
        require(lines.matches(),"city_station_lines_not_one_direct_product");
        require(lines.group("heading").equals(from)&&lines.group("destination").equals(to),"city_station_heading_direction_mismatch");
        // Only a literal same-city station label supported by the heading and
        // destination clause is eligible here. Other aliases need a later
        // independently evidenced mapping contract, never guessed prefixes.
        require(lines.group("from").matches(Pattern.quote(from)+"[东西南北]?站")&&
            lines.group("to").matches(Pattern.quote(to)+"[东西南北]?站"),"city_station_city_mapping_not_supported");
        return m("from_binding",endpoint(v,lines.start("from"),lines.end("from"),text(c,"from_province"),from,"station"),
            "to_binding",endpoint(v,lines.start("to"),lines.end("to"),text(c,"to_province"),to,"station"),
            "from_city_span",v.span(lines.start("heading"),lines.end("heading")),
            "to_city_span",v.span(lines.start("destination"),lines.end("destination")),
            "duration_span",v.span(lines.start("time"),lines.end("time")),"precision_basis","published_station_product");
    }
    private static Map<String,Object> datedRoute(Map<?,?> c,View v){
        require(!v.original.matches("(?s).*\\R.*"),"city_dated_route_cross_paragraph_unsupported");
        String geography="[\\p{IsHan}]{2,6}";
        String suffix="(?:，进一步畅通"+geography+"地区通往"+geography+"(?:、"+geography+"){0,3}的快速通道)?";
        Matcher route=Pattern.compile("(?<date>(?:(?<year>[0-9]{4})年|今年)?(?<month>[0-9]{1,2})月(?:(?<day>[0-9]{1,2})日)?)，"+
            "(?<opening>[\\p{IsHan}]{2,12}高铁(?:正式)?开通(?:运营)?)，"+
            "(?:从)?(?<from>"+CITY+")(?:到|至)(?<to>"+CITY+")(?:的高铁)?"+
            "(?:最快旅行时间|高铁最短用时|最短用时)(?:缩短到|压缩至|缩短至)(?<time>"+TIME+")"+suffix).matcher(v.value);
        if(!route.matches())return relativeOpening(c,v);
        String from=text(c,"from_city"),to=text(c,"to_city");
        require(route.group("from").equals(from)&&route.group("to").equals(to)&&city(from)&&city(to)&&!from.equals(to),"city_dated_route_direction_mismatch");
        LocalDate published=LocalDate.parse(text(c,"source_published_on"));
        int year=route.group("year")==null?published.getYear():Integer.parseInt(route.group("year"));
        int month=Integer.parseInt(route.group("month"));
        LocalDate opening=LocalDate.of(year,month,route.group("day")==null?1:Integer.parseInt(route.group("day")));
        require(!opening.isAfter(published),"city_dated_route_future_opening");
        // Month-only source dates stay month-precision, never a fabricated
        // service day or a claim that the route runs on every future date.
        return m("from_binding",endpoint(v,route.start("from"),route.end("from"),text(c,"from_province"),from,"city"),
            "to_binding",endpoint(v,route.start("to"),route.end("to"),text(c,"to_province"),to,"city"),
            "duration_span",v.span(route.start("time"),route.end("time")),
            "opening_date_span",v.span(route.start("date"),route.end("date")),
            "opening_statement_span",v.span(route.start("opening"),route.end("opening")),
            "opening_date_precision",route.group("day")==null?"month":"day",
            "precision_basis","published_city_operating_narrative");
    }
    private static Map<String,Object> relativeOpening(Map<?,?> c,View v){
        Matcher r=Pattern.compile("(?<date>上月(?<day>[0-9]{1,2})日)，[\\p{IsHan}]{2,12}高铁(?<openingfrom>"+CITY+")至(?<openingto>"+CITY+")段正式开通运营，(?<from>"+CITY+")至(?<to>"+CITY+")最快(?<time>"+TIME+")可达，较以往压缩(?<reduction>"+TIME+")").matcher(v.value);
        require(r.matches(),"city_dated_route_syntax_unsupported");
        String from=text(c,"from_city"),to=text(c,"to_city");
        require(city(from)&&city(to)&&!from.equals(to)&&r.group("from").equals(from)&&r.group("to").equals(to)&&r.group("openingfrom").equals(from)&&r.group("openingto").equals(to),"city_relative_opening_direction_mismatch");
        LocalDate publication=LocalDate.parse(text(c,"source_published_on"));
        LocalDate opening=publication.minusMonths(1).withDayOfMonth(Integer.parseInt(r.group("day")));
        require(!opening.isAfter(publication),"city_relative_opening_future");
        PublicCityBounds.duration(r.group("reduction"));
        return m("from_binding",endpoint(v,r.start("from"),r.end("from"),text(c,"from_province"),from,"city"),"to_binding",endpoint(v,r.start("to"),r.end("to"),text(c,"to_province"),to,"city"),
            "duration_span",v.span(r.start("time"),r.end("time")),"opening_date_span",v.span(r.start("date"),r.end("date")),"opening_date_precision","day","resolved_opening_date",opening.toString(),
            "unselected_reduction_span",v.span(r.start("reduction"),r.end("reduction")),"precision_basis","published_city_operating_narrative");
    }
    private static Map<String,Object> reportedJourney(Map<?,?> c,View v){
        require(!UNSAFE.matcher(v.original.replace("“","").replace("”","")).find(),"city_reported_journey_not_current_affirmative_rail");
        String time="(?:[0-9]{1,2}小时)?[0-9]{1,3}(?:分钟|分)";
        Matcher r=Pattern.compile("“(?<time>"+time+")，(?<from>"+CITY+")直达(?<to>"+CITY+")，对于我们双城生活的人来说，确实方便！”(?<date>(?<month>[0-9]{1,2})月(?<day>[0-9]{1,2})日)，(?<resident>"+CITY+")市民[\\p{IsHan}]{2,8}从(?<station>"+CITY+"站)乘坐高铁，到(?<destination>"+CITY+")上班").matcher(v.value);
        require(r.matches(),"city_reported_journey_syntax_unsupported");
        String from=text(c,"from_city"),to=text(c,"to_city");
        require(city(from)&&city(to)&&!from.equals(to)&&r.group("from").equals(from)&&r.group("to").equals(to)&&r.group("resident").equals(from)&&r.group("destination").equals(to),"city_reported_journey_direction_mismatch");
        require("reported_actual_trip".equals(c.get("journey_observation_basis"))&&text(c,"minimum_basis").isBlank()&&!Boolean.TRUE.equals(c.get("fastest_claim")),"city_reported_journey_not_fastest_or_operator_claim");
        LocalDate publication=LocalDate.parse(text(c,"source_published_on")),journey=LocalDate.of(publication.getYear(),Integer.parseInt(r.group("month")),Integer.parseInt(r.group("day")));
        require(!journey.isAfter(publication),"city_reported_journey_future_date");
        return m("from_binding",PublicStationContext.mapping(c,v,r.start("station"),r.end("station"),"from"),"to_binding",endpoint(v,r.start("destination"),r.end("destination"),text(c,"to_province"),to,"city"),
            "reported_from_city_span",v.span(r.start("from"),r.end("from")),"reported_to_city_span",v.span(r.start("to"),r.end("to")),"duration_span",v.span(r.start("time"),r.end("time")),
            "journey_date_span",v.span(r.start("date"),r.end("date")),"reported_journey_on",journey.toString(),"precision_basis","published_resident_actual_journey","not_operator_or_fastest_claim",true);
    }
    /** Generate a verifiable proposal, not approval. The caller must persist the
     * full returned binding inside the source content before evidence validation. */
    static Map<String,Object> describe(Map<?,?> c){
        String original=text(c,"original_content"),type=text(c,"context_binding_type");
        require(!original.isBlank()&&original.length()<=2500,"city_context_size_invalid");
        PublicTransportEvidence.assertion(c); // Existing complete-consumption and service-state guard.
        boolean stationContext=STATION_ROUTES.equals(type)||SERVICE_ROUNDTRIP.equals(type);
        if(stationContext)PublicStationContext.safe(original);
        else if(!SCHEDULE_CHAIN.equals(type)&&!REPORTED_JOURNEY.equals(type))require(!UNSAFE.matcher(original).find(),"city_context_not_current_affirmative_rail");
        View v=new View(original);Map<String,Object> result;
        if(PARALLEL.equals(type))result=parallel(c,v);
        else if(STATION_LINES.equals(type))result=stationLines(c,v);
        else if(DATED_ROUTE.equals(type))result=datedRoute(c,v);
        else if(STATION_ROUTES.equals(type))result=PublicStationContext.routes(c,v);
        else if(SERVICE_ROUNDTRIP.equals(type))result=PublicStationContext.services(c,v);
        else if(SCHEDULE_CHAIN.equals(type))result=PublicStationContext.chain(c,v);
        else if(REPORTED_JOURNEY.equals(type))result=reportedJourney(c,v);
        else throw new IllegalArgumentException("city_context_type_unsupported");
        result.put("version",VERSION);result.put("type",type);result.put("offset_unit","utf16");
        result.put("context_start",0);result.put("context_end",original.length());
        result.put("context_canonical_sha256",CityTravelReference.sha256(original));
        if(SCHEDULE_CHAIN.equals(type))require(!c.containsKey("duration_text"),"city_chain_must_not_invent_published_duration_text");
        else require(text(CityTravelReference.map(result.get("duration_span")),"text").equals(c.get("duration_text")),"city_context_declared_duration_mismatch");
        return result;
    }
    static String boundDuration(Map<?,?> c){
        Map<String,Object> expected=describe(c);
        require(CityTravelReference.canonical(expected).equals(CityTravelReference.canonical(c.get("context_binding"))),"city_context_binding_mismatch");
        // Do not let a future-dated passage override truthful publication
        // metadata. Old construction years are retained, not treated as expiry.
        int publicationYear=LocalDate.parse(text(c,"source_published_on")).getYear();
        Matcher years=Pattern.compile("(?<![0-9])([0-9]{4})年").matcher(text(c,"original_content"));
        while(years.find())require(Integer.parseInt(years.group(1))<=publicationYear,"city_context_future_year_not_current_service");
        String value=text(c,"duration_text");
        // A city-level "one-hour pattern" is not a minute-accurate upper bound.
        // Keep the original evidence/binding available, but do not synthesize 60.
        if(PARALLEL.equals(c.get("context_binding_type"))&&value.matches("(?:最快)?"+NUM+"小时"))
            throw new IllegalArgumentException("city_context_hour_narrative_precision_pending");
        return value;
    }
    static PublicCityBounds.Duration boundClockDuration(Map<?,?> c){
        require(SCHEDULE_CHAIN.equals(c.get("context_binding_type")),"city_chain_type_missing");
        Map<String,Object> binding=describe(c);
        require(CityTravelReference.canonical(binding).equals(CityTravelReference.canonical(c.get("context_binding"))),"city_context_binding_mismatch");
        Map<String,Object> input=new LinkedHashMap<>();
        for(String key:List.of("original_content","assertion_start","assertion_end","context_binding_type","from_province","from_city","to_province","to_city","selected_service_id","selected_from_station","selected_to_station"))input.put(key,c.get(key));
        return new PublicCityBounds.Duration("exact",text(c,"original_content"),((Number)binding.get("clock_duration_minutes")).intValue(),true,
            ((Number)binding.get("clock_duration_minutes")).intValue(),true,null,"computed_same_service_departure_arrival","",input);
    }
    private PublicCityContext(){}
}
