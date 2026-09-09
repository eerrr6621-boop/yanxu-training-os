package com.training;

import java.util.*;
import java.util.regex.*;

/** Shared local cell grammar for one explicitly headed public rail stop chain.
 * It binds source roles, not current train operation, fastest time or permission.
 * No different-service concatenation, mirrored direction or implicit overnight. */
final class PublicStopChain {
    private static final String STATION="[\\p{IsHan}]{2,20}?",CLOCK="[0-9]{1,2}[:：][0-9]{2}";
    private static void need(boolean b,String s){CityTravelReference.require(b,s);}
    private static Map<String,Object> m(Object...kv){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)r.put((String)kv[i],kv[i+1]);return r;}
    private static Map<?,?> map(Object v){return CityTravelReference.map(v);}
    private static String text(Map<?,?> v,String k){return CityTravelReference.text(v,k);}
    private static int minute(String s){return PublicTransportEvidence.clockMinutes(s.replace('：',':'));}
    private static final String SINGLE_SERVICE="[GDC][0-9]{1,5}(?:/[0-9]{1,5})?";
    private static boolean separateHeading(String s){return Pattern.compile("^[ \\t\\u3000]*"+SINGLE_SERVICE+"次[ \\t\\u3000]*\\R").matcher(s).find();}
    static boolean heading(String s){return new PublicCityContext.View(s).value.matches("^(?:去程|返程)[GDC][0-9]{1,5}次[:：].*")||separateHeading(s);}

    /** A complete, separate service heading and one same-day endpoint sentence.
     * Compound ids remain literal ids: never infer an opposite service or merge
     * a list of services. Every emitted token retains one contiguous raw span. */
    private static Map<String,Object> headedEndpoints(PublicCityContext.View v){
        need(separateHeading(v.original),"stop_chain_header_missing");
        Matcher r=Pattern.compile("(?<id>"+SINGLE_SERVICE+")次(?<from>"+STATION+"站)(?<departure>"+CLOCK+")始发，(?<arrival>"+CLOCK+")终到(?<to>"+STATION+"站)").matcher(v.value);
        need(r.matches(),"stop_chain_headed_endpoint_roles_required");
        int departure=minute(r.group("departure")),arrival=minute(r.group("arrival"));
        need(arrival>departure,"stop_chain_clock_order_or_overnight_unsupported");
        need(!r.group("from").equals(r.group("to")),"stop_chain_duplicate_station");
        Map<String,Object> from=m("station",r.group("from"),"station_span",v.span(r.start("from"),r.end("from")),"stop_role","initial_departure","explicit_arrival_departure_pair",false,
            "departure_clock",r.group("departure"),"departure_minute",departure,"departure_source_span",v.span(r.start("departure"),r.end("departure")),"departure_hour_basis","explicit_full_clock");
        Map<String,Object> to=m("station",r.group("to"),"station_span",v.span(r.start("to"),r.end("to")),"stop_role","final_arrival","explicit_arrival_departure_pair",false,
            "arrival_clock",r.group("arrival"),"arrival_minute",arrival,"arrival_source_span",v.span(r.start("arrival"),r.end("arrival")));
        return m("service_id",r.group("id"),"service_span",v.span(r.start("id"),r.end("id")),"direction_label","independent_source_service","all_stop_items",List.of(from,to),"source_syntax","separate_service_heading_endpoint_clocks_v1");
    }

    static Map<String,Object> parse(PublicCityContext.View v){
        Matcher header=Pattern.compile("(?<direction>去程|返程)(?<id>[GDC][0-9]{1,5})次[:：](?<nodes>.+)").matcher(v.value);
        if(!header.matches())return headedEndpoints(v);String[] nodes=header.group("nodes").split("→",-1);
        need(nodes.length>=2&&nodes.length<=32,"stop_chain_node_count_invalid");
        List<Map<String,Object>> rows=new ArrayList<>();Set<String> seen=new HashSet<>();int offset=header.start("nodes"),previous=-1;
        for(int i=0;i<nodes.length;i++){
            boolean first=i==0,last=i==nodes.length-1;
            String pattern=first?"(?<station>"+STATION+")(?<departure>"+CLOCK+")始发":last?"(?<station>"+STATION+")(?<arrival>"+CLOCK+")终到":"(?<station>"+STATION+")(?<arrival>"+CLOCK+")/(?<departure>"+CLOCK+")";
            Matcher node=Pattern.compile(pattern).matcher(nodes[i]);need(node.matches(),"stop_chain_explicit_stop_roles_required");
            String station=node.group("station"),key=station.endsWith("站")?station.substring(0,station.length()-1):station;
            need(seen.add(key),"stop_chain_duplicate_station");
            Map<String,Object> row=m("station",station,"station_span",v.span(offset+node.start("station"),offset+node.end("station")),"stop_role",first?"initial_departure":last?"final_arrival":"intermediate_arrival_departure","explicit_arrival_departure_pair",!first&&!last);
            int arrival=first?-1:minute(node.group("arrival")),departure=last?-1:minute(node.group("departure"));
            need((first?departure:arrival)>previous&&(first||last||departure>=arrival),"stop_chain_clock_order_or_overnight_unsupported");
            if(!first){row.put("arrival_clock",node.group("arrival"));row.put("arrival_minute",arrival);row.put("arrival_source_span",v.span(offset+node.start("arrival"),offset+node.end("arrival")));}
            if(!last){row.put("departure_clock",node.group("departure"));row.put("departure_minute",departure);row.put("departure_source_span",v.span(offset+node.start("departure"),offset+node.end("departure")));row.put("departure_hour_basis","explicit_full_clock");}
            rows.add(row);previous=last?arrival:departure;offset+=nodes[i].length()+1;
        }
        return m("service_id",header.group("id"),"service_span",v.span(header.start("id"),header.end("id")),"direction_label",header.group("direction"),"all_stop_items",rows,"source_syntax","explicit_header_initial_intermediate_final_chain_v1");
    }
    static Map<String,Object> segment(Map<?,?> parsed,String service,String from,String to){
        need(service.equals(parsed.get("service_id")),"stop_chain_selected_service_mismatch");List<?> rows=CityTravelReference.list(parsed.get("all_stop_items"));int a=-1,b=-1;
        for(int i=0;i<rows.size();i++){if(from.equals(map(rows.get(i)).get("station")))a=i;if(to.equals(map(rows.get(i)).get("station")))b=i;}
        need(a>=0&&b>a,"stop_chain_selected_segment_not_forward");Map<?,?> f=map(rows.get(a)),t=map(rows.get(b));
        int duration=((Number)t.get("arrival_minute")).intValue()-((Number)f.get("departure_minute")).intValue();RailTimetable.minute(duration);
        return m("selected_departure_stop",a,"selected_arrival_stop",b,"from_stop",f,"to_stop",t,"clock_duration_minutes",duration);
    }
    static Map<String,Object> strict(Map<?,?> c,PublicCityContext.View v){
        Map<String,Object> parsed=parse(v),selected=segment(parsed,text(c,"selected_service_id"),text(c,"selected_from_station"),text(c,"selected_to_station"));
        need(text(c,"minimum_basis").isBlank()&&!Boolean.TRUE.equals(c.get("fastest_claim")),"city_chain_not_global_fastest_claim");
        for(String side:List.of("from","to")){Map<?,?> stop=map(selected.get(side+"_stop")),span=map(stop.get("station_span"));int start=v.offsets.indexOf(((Number)span.get("start")).intValue());
            Map<String,Object> binding=PublicStationContext.mapping(c,v,start,start+text(stop,"station").length(),side);
            need("literal_city_prefix_in_station_role".equals(binding.get("mapping_basis")),"city_chain_nonliteral_station_mapping_not_supported");parsed.put(side+"_binding",binding);
        }
        parsed.put("selected_departure_stop",selected.get("selected_departure_stop"));parsed.put("selected_arrival_stop",selected.get("selected_arrival_stop"));parsed.put("clock_duration_minutes",selected.get("clock_duration_minutes"));parsed.put("arrival_day_offset",0);parsed.put("precision_basis","computed_same_service_departure_arrival");parsed.put("not_published_fastest_duration",true);parsed.put("operating_context","effective_regular_diagram_baseline");return parsed;
    }
    private static boolean exactSpan(Map<?,?> spans,String role,String unit,Map<?,?> expected){
        for(Object value:CityTravelReference.list(spans.get(role))){Map<?,?> s=map(value);if(unit.equals(s.get("unit_id"))&&sameOffset(s.get("start"),expected.get("start"))&&sameOffset(s.get("end"),expected.get("end"))&&Objects.equals(s.get("text"),expected.get("text")))return true;}return false;
    }
    private static boolean sameOffset(Object a,Object b){return a instanceof Number&&b instanceof Number&&((Number)a).doubleValue()==((Number)b).doubleValue();}
    /** null means the existing station-cell syntax should handle this fact.
     * Once an explicit stop-chain service span is selected, errors never fall back. */
    static Integer planning(Map<?,?> fact,Map<String,String> units){
        Map<?,?> c=map(fact.get("canonical")),spans=map(fact.get("spans"));Integer value=null;
        for(Object raw:CityTravelReference.list(spans.get("service"))){Map<?,?> ref=map(raw);String id=text(ref,"unit_id"),original=units.get(id);if(original==null||!heading(original))continue;
            need(value==null,"stop_chain_multiple_service_units");Map<String,Object> parsed=parse(new PublicCityContext.View(original)),selected=segment(parsed,text(c,"service_id"),text(c,"from_endpoint"),text(c,"to_endpoint"));Map<?,?> from=map(selected.get("from_stop")),to=map(selected.get("to_stop"));
            need(exactSpan(spans,"service",id,map(parsed.get("service_span")))&&exactSpan(spans,"from",id,map(from.get("station_span")))&&exactSpan(spans,"to",id,map(to.get("station_span")))&&exactSpan(spans,"departure",id,map(from.get("departure_source_span")))&&exactSpan(spans,"arrival",id,map(to.get("arrival_source_span"))),"stop_chain_fact_spans_not_selected_roles");
            need(minute(text(c,"departure_clock"))==((Number)from.get("departure_minute")).intValue()&&minute(text(c,"arrival_clock"))==((Number)to.get("arrival_minute")).intValue(),"stop_chain_fact_clock_mismatch");
            value=((Number)selected.get("clock_duration_minutes")).intValue();
        }
        return value;
    }
    private PublicStopChain(){}
}
