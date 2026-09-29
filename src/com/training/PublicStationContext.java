package com.training;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Complete public operating paragraphs with explicit station/service roles.
 * No lookup, implicit return, database of guessed aliases, or real-time promise. */
final class PublicStationContext {
    private static final String TOKEN="[\\p{IsHan}]{2,20}?";
    private static final String TIME="(?:[0-9]{1,2}小时)?[0-9]{1,2}(?:分钟|分)";
    private static final String CLOCK="[0-9]{1,2}[:：][0-9]{2}";
    private static final String ITEM=TOKEN+"至"+TOKEN+"最快"+TIME+"可达";
    private static final String SERVICE="[GDC][0-9]{1,5}次"+CLOCK+TOKEN+"(?:始发|发车)，"+CLOCK+"分?抵达"+TOKEN;
    private static final Pattern UNSAFE=Pattern.compile("并非|并不|不是|不再|不能|未|没有|无法|取消|停运|停开|停售|暂停|不通|不适用|已到期|作废|失效|计划|预计|拟|届时|将|临时|春运|暑运|季节|节假日|仅限|仅在|仅于|截至|曾经|当时|彼时|过去|旧图|原图|换乘|中转|转乘|尚|目标|累计|合计|总计|驾车|汽车|公路|步行|航空|飞机|次日|翌日|第二天");
    private static Map<String,Object> m(Object... kv){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)r.put((String)kv[i],kv[i+1]);return r;}
    private static void need(boolean b,String why){CityTravelReference.require(b,why);}
    private static String text(Map<?,?> r,String k){return CityTravelReference.text(r,k);}
    static void safe(String s){need(!UNSAFE.matcher(s).find(),"city_station_not_current_affirmative_rail");need(!s.matches("(?s).*\\R.*"),"city_station_paragraph_cross_line_unsupported");}
    private static String station(String s){return s.endsWith("站")?s.substring(0,s.length()-1):s;}
    static Map<String,Object> mapping(Map<?,?> c,PublicCityContext.View v,int a,int b,String side){
        String province=text(c,side+"_province"),city=text(c,side+"_city"),raw=v.value.substring(a,b),label=station(raw);
        need(RegionDirectory.known(province,city),"city_station_mapping_city_unknown");
        Map<String,Object> result=m("province",province,"city",city,"kind","station","source_span",v.span(a,b));
        // Simple literal city-prefix labels are a narrowly stated curation basis.
        // Special administrative regions always need separate explicit source context.
        if(!List.of("香港","澳门","台湾").contains(province)&&label.matches(Pattern.quote(city)+"[\\p{IsHan}]{0,6}")){
            result.put("mapping_basis","literal_city_prefix_in_station_role");return result;
        }
        List<?> entries=CityTravelReference.list(c.get("station_city_mappings"));Map<?,?> chosen=null;
        for(Object entry:entries){Map<?,?> e=CityTravelReference.map(entry);if(station(text(e,"station_label")).equals(label)){need(chosen==null,"city_station_mapping_duplicate");chosen=e;}}
        need(chosen!=null,"city_station_mapping_source_required");
        need(province.equals(chosen.get("province"))&&city.equals(chosen.get("city"))&&Boolean.TRUE.equals(chosen.get("mapping_checked")),"city_station_mapping_direction_mismatch");
        need(PublicTransportEvidence.sourceStateAllowed(chosen)&&"public_page".equals(chosen.get("source_access"))&&
            List.of("government","transport_operator").contains(text(chosen,"publisher_kind")),"city_station_mapping_source_invalid");
        CityTravelReference.source(chosen);
        LocalDate researched=LocalDate.parse(text(c,"research_on")),checked=LocalDate.parse(text(chosen,"checked_on"));
        need(!checked.isAfter(researched)&&!checked.isBefore(researched.minusDays(365)),"city_station_mapping_check_date_invalid");
        String declaredDate=text(chosen,"source_published_on");
        if(declaredDate.isBlank())need("not_shown".equals(chosen.get("source_date_status")),"city_station_mapping_date_missing");
        else need(!LocalDate.parse(declaredDate).isAfter(checked),"city_station_mapping_future_source");
        String original=text(chosen,"original_content"),quote=PublicTransportEvidence.assertion(chosen);
        Matcher statement=Pattern.compile("(?<station>"+TOKEN+"站)是高速铁路[（(](?<city>"+TOKEN+")段[）)](?:[（(][\"“]高铁[\"”][）)])?的总站").matcher(quote);
        need(statement.matches()&&station(statement.group("station")).equals(label)&&statement.group("city").equals(city),"city_station_mapping_statement_unbound");
        result.put("mapping_basis","public_station_city_segment_statement");
        result.put("mapping_source_sha256",CityTravelReference.sha256(chosen));result.put("mapping_source_url",chosen.get("source_url"));
        result.put("mapping_original_content",original);return result;
    }
    private static Map<String,Object> selectRoute(Map<?,?> c,PublicCityContext.View v,String items,int base){
        Matcher item=Pattern.compile("(?<from>"+TOKEN+")至(?<to>"+TOKEN+")最快(?<time>"+TIME+")可达").matcher(items);
        List<Object> rows=new ArrayList<>();Set<String> seen=new HashSet<>();Map<String,Object> result=null;int end=0;
        String from=text(c,"selected_from_station"),to=text(c,"selected_to_station");need(!from.isBlank()&&!to.isBlank(),"city_station_selected_endpoints_missing");
        while(item.find()){
            need(item.start()==end||(item.start()==end+1&&items.charAt(end)=='，'),"city_station_route_content_unconsumed");
            String a=item.group("from"),b=item.group("to");need(!station(a).equals(station(b))&&seen.add(station(a)+"→"+station(b)),"city_station_route_duplicate_or_same");
            PublicCityBounds.duration(item.group("time"));
            rows.add(m("from_span",v.span(base+item.start("from"),base+item.end("from")),"to_span",v.span(base+item.start("to"),base+item.end("to")),"duration_span",v.span(base+item.start("time"),base+item.end("time"))));
            if(a.equals(from)&&b.equals(to)){
                need(result==null,"city_station_route_target_ambiguous");
                result=m("from_binding",mapping(c,v,base+item.start("from"),base+item.end("from"),"from"),"to_binding",mapping(c,v,base+item.start("to"),base+item.end("to"),"to"),
                    "duration_span",v.span(base+item.start("time"),base+item.end("time")),"selected_item",rows.size()-1,"precision_basis","published_independent_station_route");
            }
            end=item.end();
        }
        need(end==items.length()&&!rows.isEmpty()&&rows.size()<=8&&result!=null,"city_station_route_target_missing");result.put("all_route_items",rows);return result;
    }
    static Map<String,Object> routes(Map<?,?> c,PublicCityContext.View v){
        String items="(?<items>"+ITEM+"(?:，"+ITEM+"){0,7})";
        Matcher opening=Pattern.compile("(?<date>(?:(?<year>[0-9]{4})年)?(?<month>[0-9]{1,2})月(?<day>[0-9]{1,2})日)(?<hour>[0-9]{1,2})时许，随着[GDC][0-9]{1,5}次“复兴号”列车驶出(?<departure>"+TOKEN+"站)，(?<openingfrom>"+TOKEN+")至(?<openingto>"+TOKEN+")高铁开通运营，"+items+"，[\\p{IsHan}、]{2,45}时空距离大幅压缩").matcher(v.value);
        if(opening.matches()){
            LocalDate published=LocalDate.parse(text(c,"source_published_on"));int year=opening.group("year")==null?published.getYear():Integer.parseInt(opening.group("year"));
            LocalDate date=LocalDate.of(year,Integer.parseInt(opening.group("month")),Integer.parseInt(opening.group("day")));
            need(!date.isAfter(published)&&Integer.parseInt(opening.group("hour"))<24,"city_station_opening_future_or_clock_invalid");
            need(opening.group("openingfrom").equals(c.get("from_city"))&&opening.group("openingto").equals(c.get("to_city"))&&
                station(opening.group("departure")).equals(station(text(c,"selected_from_station"))),"city_station_opening_route_mismatch");
            Map<String,Object> result=selectRoute(c,v,opening.group("items"),opening.start("items"));result.put("opening_date_span",v.span(opening.start("date"),opening.end("date")));result.put("operating_context","dated_opening_station_narrative");return result;
        }
        String line=TOKEN+"至"+TOKEN+"高铁(?:"+TOKEN+"至"+TOKEN+"段)?";
        String product="(?:"+TOKEN+"至)?"+TOKEN+"(?:（"+TOKEN+"）)?";
        String products=product+"(?:、"+product+"){0,7}的动车组列车[1-9][0-9]{0,3}列(?:、[1-9][0-9]{0,3}列){0,7}";
        Matcher diagram=Pattern.compile("优化[\\p{IsHan}]{2,12}地区列车运行图：利用新开通的"+line+"(?:、"+line+"){0,7}，分别安排开行"+products+"，开行"+products+"，"+items+"，进一步密切[\\p{IsHan}、]{2,30}地区与[\\p{IsHan}、]{2,30}地区的联系").matcher(v.value);
        need(diagram.matches(),"city_station_operating_routes_syntax_unsupported");
        Map<String,Object> result=selectRoute(c,v,diagram.group("items"),diagram.start("items"));result.put("operating_context","current_running_diagram_route_list");return result;
    }
    private static Map<String,Object> service(PublicCityContext.View v,String s,int base){
        Matcher row=Pattern.compile("(?<id>[GDC][0-9]{1,5})次(?<departure>"+CLOCK+")(?<from>"+TOKEN+")(?:始发|发车)，(?<arrival>"+CLOCK+")分?抵达(?<to>"+TOKEN+")").matcher(s);
        need(row.matches(),"city_service_roles_unbound");
        int departure=PublicTransportEvidence.clockMinutes(row.group("departure").replace('：',':')),arrival=PublicTransportEvidence.clockMinutes(row.group("arrival").replace('：',':'));
        need(arrival>departure,"city_service_overnight_or_nonincreasing");
        return m("service_id",row.group("id"),"service_span",v.span(base,base+s.length()),"from_span",v.span(base+row.start("from"),base+row.end("from")),
            "to_span",v.span(base+row.start("to"),base+row.end("to")),"departure_span",v.span(base+row.start("departure"),base+row.end("departure")),"arrival_span",v.span(base+row.start("arrival"),base+row.end("arrival")),
            "clock_duration_minutes",arrival-departure,"arrival_day_offset",0);
    }
    private static String spanText(Map<?,?> row,String name){return text(CityTravelReference.map(row.get(name)),"text");}
    static Map<String,Object> services(Map<?,?> c,PublicCityContext.View v){
        Matcher whole=Pattern.compile("本次开通的直达班次形成稳定往返通行能力：来程(?<out>"+SERVICE+")，全程仅需(?<outtime>"+TIME+")；返程(?<back>"+SERVICE+"(?:，另一趟是"+SERVICE+"){0,5})，返程时间最短为(?<backtime>"+TIME+")(?:，真正实现"+TOKEN+"与"+TOKEN+"“[一二两三四五六七八九十0-9]{1,3}小时生活圈”)?").matcher(v.value);
        need(whole.matches(),"city_services_roundtrip_syntax_unsupported");
        Map<String,Object> outward=service(v,whole.group("out"),whole.start("out"));List<Map<String,Object>> all=new ArrayList<>();all.add(outward);
        String[] returns=whole.group("back").split("，另一趟是",-1);int cursor=whole.start("back"),minimum=Integer.MAX_VALUE;Set<String> ids=new HashSet<>();ids.add(text(outward,"service_id"));
        for(String s:returns){Map<String,Object> row=service(v,s,cursor);cursor+=s.length()+"，另一趟是".length();
            need(ids.add(text(row,"service_id")),"city_service_id_reused");
            need(station(spanText(row,"from_span")).equals(station(spanText(outward,"to_span")))&&station(spanText(row,"to_span")).equals(station(spanText(outward,"from_span"))),"city_service_return_endpoints_mismatch");
            minimum=Math.min(minimum,((Number)row.get("clock_duration_minutes")).intValue());all.add(row);
        }
        need(PublicTransportEvidence.duration(whole.group("outtime"))==((Number)outward.get("clock_duration_minutes")).intValue(),"city_service_outward_duration_clock_mismatch");
        need(PublicTransportEvidence.duration(whole.group("backtime"))==minimum,"city_service_return_duration_clock_mismatch");
        String selectedId=text(c,"selected_service_id");Map<String,Object> selected=null;int index=-1;
        for(int i=0;i<all.size();i++)if(selectedId.equals(all.get(i).get("service_id"))){selected=all.get(i);index=i;}
        need(selected!=null,"city_service_selected_id_missing");
        if(index>0)need(((Number)selected.get("clock_duration_minutes")).intValue()==minimum,"city_service_selected_not_stated_fastest");
        need(spanText(selected,"from_span").equals(c.get("selected_from_station"))&&spanText(selected,"to_span").equals(c.get("selected_to_station")),"city_service_selected_direction_mismatch");
        Map<?,?> from=CityTravelReference.map(selected.get("from_span")),to=CityTravelReference.map(selected.get("to_span"));
        // Source spans use UTF-16 offsets. v has no whitespace within selected tokens.
        int fromStart=v.offsets.indexOf(((Number)from.get("start")).intValue()),toStart=v.offsets.indexOf(((Number)to.get("start")).intValue());
        String group=index==0?"outtime":"backtime";
        return m("from_binding",mapping(c,v,fromStart,fromStart+spanText(selected,"from_span").length(),"from"),
            "to_binding",mapping(c,v,toStart,toStart+spanText(selected,"to_span").length(),"to"),"duration_span",v.span(whole.start(group),whole.end(group)),
            "selected_service",index,"all_service_items",all,"return_fastest_minutes_crosscheck",minimum,
            "precision_basis","published_direction_duration_crosschecked_with_independent_service_clocks","cross_border_conditions_separate",true);
    }
    /** A regular published diagram is a reusable baseline, not proof that a
     * particular service still runs on every later departure date. */
    static void effectiveChainSchedule(Map<?,?> c,LocalDate published,LocalDate researched,LocalDate today){
        need("published_schedule_baseline".equals(c.get("service_state"))&&"regular_published_running_diagram".equals(c.get("schedule_basis")),"city_chain_schedule_basis_missing");
        LocalDate effective=CityTravelReference.date(c,"effective_from",today);
        need(!published.isAfter(effective)&&!effective.isAfter(researched),"city_chain_schedule_not_yet_effective");
        Map<?,?> notice=CityTravelReference.map(c.get("schedule_effective_notice"));
        need(PublicTransportEvidence.sourceStateAllowed(notice)&&!Boolean.TRUE.equals(notice.get("temporary_service"))&&"public_page".equals(notice.get("source_access")),"city_chain_schedule_notice_invalid");
        need(text(c,"source_url").equals(notice.get("source_url"))&&published.toString().equals(notice.get("source_published_on")),"city_chain_schedule_notice_source_mismatch");
        String q=PublicTransportEvidence.assertion(notice);
        Matcher date=Pattern.compile("新图自(?:(?<year>[0-9]{4})年)?(?<month>[0-9]{1,2})月(?<day>[0-9]{1,2})日零时起执行，具体车次时刻、票务信息请以12306官网、App查询为准").matcher(q);
        if(!date.matches())date=Pattern.compile("自(?:(?<year>[0-9]{4})年)?(?<month>[0-9]{1,2})月(?<day>[0-9]{1,2})日零时起，全国铁路将实行新[一二三四1-4]季度列车运行图").matcher(q);
        if(!date.matches())date=addedServicesNotice(c,q);
        LocalDate stated=LocalDate.of(date.group("year")==null?published.getYear():Integer.parseInt(date.group("year")),Integer.parseInt(date.group("month")),Integer.parseInt(date.group("day")));
        need(stated.equals(effective),"city_chain_schedule_date_mismatch");
    }
    /** A definite dated addition of named rail products is also a baseline
     * announcement once its effective date has passed. The notice binds the
     * selected station corridor; the separate full source sentence binds the
     * actual service id and its own direction/clocks. Do not expand compressed
     * group ids or use "pairs" to mirror an unobserved journey time. */
    private static Matcher addedServicesNotice(Map<?,?> c,String q){
        String ids="[GDC][0-9]{1,5}(?:/[GDC]?[0-9]{1,5}){0,3}";
        String item=TOKEN+"至"+TOKEN+ids+"次";
        Matcher notice=Pattern.compile("(?:为更好满足[\\p{IsHan}]{2,15}与[\\p{IsHan}]{2,15}地区间旅客出行需求，)?自(?:(?<year>[0-9]{4})年)?(?<month>[0-9]{1,2})月(?<day>[0-9]{1,2})日起，将新增(?<items>"+item+"(?:、"+item+"){0,7})共(?<count>[1-9][0-9]?)对动车组列车").matcher(q);
        need(notice.matches(),"city_chain_schedule_notice_syntax_unsupported");
        Matcher product=Pattern.compile("(?<from>"+TOKEN+")至(?<to>"+TOKEN+")(?<ids>"+ids+")次").matcher(notice.group("items"));
        String from=station(text(c,"selected_from_station")),to=station(text(c,"selected_to_station"));boolean bound=false;int count=0,end=0;
        while(product.find()){
            need(product.start()==end||(product.start()==end+1&&notice.group("items").charAt(end)=='、'),"city_chain_addition_notice_unconsumed");
            String a=station(product.group("from")),b=station(product.group("to"));need(!a.equals(b),"city_chain_addition_notice_same_endpoint");
            if(a.equals(from)&&b.equals(to)||a.equals(to)&&b.equals(from))bound=true;
            count++;end=product.end();
        }
        need(end==notice.group("items").length()&&count==Integer.parseInt(notice.group("count")),"city_chain_addition_notice_count_mismatch");
        need(bound,"city_chain_addition_notice_corridor_unbound");return notice;
    }
    static Map<String,Object> chain(Map<?,?> c,PublicCityContext.View v){
        // Newlines are permitted only between complete labels/tokens; View.span
        // rejects assembling a city or a clock from disconnected source pieces.
        need(!UNSAFE.matcher(v.original).find(),"city_chain_nonregular_or_discontinuous");
        String node=TOKEN+CLOCK+"(?:/(?:"+CLOCK+"|[0-9]{2}))?";
        String end="(?:填补(?<tailfirst>"+TOKEN+")、(?<tailsecond>"+TOKEN+")至(?<tailto>"+TOKEN+")早班通勤空白|补强晚间通勤时段运能)";
        Matcher whole=Pattern.compile("(?<hf>"+TOKEN+")→(?<ht>"+TOKEN+")(?:（经停(?<via>"+TOKEN+")）)?新增动车(?<id>[GDC][0-9]{1,5}(?:/[0-9]{1,5})?)次：(?<nodes>"+node+"(?:→"+node+"){1,11})"+end).matcher(v.value);
        if(!whole.matches())return PublicStopChain.strict(c,v);
        need(whole.group("id").equals(c.get("selected_service_id")),"city_chain_selected_service_mismatch");
        need(text(c,"minimum_basis").isBlank()&&!Boolean.TRUE.equals(c.get("fastest_claim")),"city_chain_not_global_fastest_claim");
        String ns=whole.group("nodes");int base=whole.start("nodes"),endOffset=0,previous=-1;
        Matcher item=Pattern.compile("(?<station>"+TOKEN+")(?<arrival>"+CLOCK+")(?:/(?<departure>"+CLOCK+"|[0-9]{2}))?").matcher(ns);
        List<Map<String,Object>> stops=new ArrayList<>();Set<String> seen=new HashSet<>();
        while(item.find()){
            need(item.start()==endOffset||(item.start()==endOffset+1&&ns.charAt(endOffset)=='→'),"city_chain_unconsumed_stop_content");
            String label=item.group("station"),a=item.group("arrival"),d=item.group("departure");
            need(seen.add(station(label)),"city_chain_repeated_station");
            int arrival=PublicTransportEvidence.clockMinutes(a.replace('：',':'));
            String departureText=d==null?a:(d.matches("[0-9]{2}")?a.substring(0,a.length()-2)+d:d);
            int departure=PublicTransportEvidence.clockMinutes(departureText.replace('：',':'));
            need(arrival>previous&&departure>=arrival,"city_chain_clock_order_or_overnight_unsupported");
            Map<String,Object> row=m("station",label,"station_span",v.span(base+item.start("station"),base+item.end("station")),
                "arrival_source_span",v.span(base+item.start("arrival"),base+item.end("arrival")),"arrival_clock",a,"arrival_minute",arrival,
                "departure_clock",departureText,"departure_minute",departure,"explicit_arrival_departure_pair",d!=null);
            if(d!=null){row.put("departure_source_span",v.span(base+item.start("departure"),base+item.end("departure")));row.put("departure_hour_basis",d.matches("[0-9]{2}")?"same_stop_arrival_hour_shorthand":"explicit_full_clock");}
            stops.add(row);previous=departure;endOffset=item.end();
        }
        need(endOffset==ns.length()&&stops.size()>=2,"city_chain_missing_stops");
        need(whole.group("hf").equals(stops.get(0).get("station"))&&whole.group("ht").equals(stops.get(stops.size()-1).get("station")),"city_chain_heading_endpoints_mismatch");
        if(whole.group("via")!=null)need(stops.subList(1,stops.size()-1).stream().anyMatch(r->r.get("station").equals(whole.group("via"))),"city_chain_via_not_intermediate_stop");
        for(int i=0;i<stops.size();i++)need(Boolean.TRUE.equals(stops.get(i).get("explicit_arrival_departure_pair"))==(i>0&&i<stops.size()-1),"city_chain_stop_roles_ambiguous");
        if(whole.group("tailfirst")!=null){
            int before=-1;for(String group:List.of("tailfirst","tailsecond","tailto")){String city=whole.group(group);need(RegionDirectory.knownElsewhere("",city),"city_chain_tail_city_unknown");int found=-1;
                for(int i=before+1;i<stops.size();i++)if(text(stops.get(i),"station").matches(Pattern.quote(city)+"[\\p{IsHan}]{0,6}")){found=i;break;}
                need(found>before,"city_chain_tail_route_mismatch");before=found;}
        }
        for(int i=0;i<stops.size();i++){Map<String,Object> row=stops.get(i);row.put("stop_role",i==0?"initial_departure":i==stops.size()-1?"final_arrival":"intermediate_arrival_departure");
            if(i==0){row.put("departure_source_span",row.remove("arrival_source_span"));row.remove("arrival_clock");row.remove("arrival_minute");}
            if(i==stops.size()-1){row.remove("departure_clock");row.remove("departure_minute");}
        }
        int from=-1,to=-1;for(int i=0;i<stops.size();i++){if(stops.get(i).get("station").equals(c.get("selected_from_station")))from=i;if(stops.get(i).get("station").equals(c.get("selected_to_station")))to=i;}
        need(from>=0&&to>from,"city_chain_selected_segment_not_forward");
        Map<String,Object> a=stops.get(from),b=stops.get(to);int minutes=((Number)b.get("arrival_minute")).intValue()-((Number)a.get("departure_minute")).intValue();
        RailTimetable.minute(minutes);
        Map<?,?> as=CityTravelReference.map(a.get("station_span")),bs=CityTravelReference.map(b.get("station_span"));
        int ai=v.offsets.indexOf(((Number)as.get("start")).intValue()),bi=v.offsets.indexOf(((Number)bs.get("start")).intValue());
        Map<String,Object> fromBinding=mapping(c,v,ai,ai+text(a,"station").length(),"from"),toBinding=mapping(c,v,bi,bi+text(b,"station").length(),"to");
        need("literal_city_prefix_in_station_role".equals(fromBinding.get("mapping_basis"))&&"literal_city_prefix_in_station_role".equals(toBinding.get("mapping_basis")),"city_chain_nonliteral_station_mapping_not_supported");
        return m("from_binding",fromBinding,"to_binding",toBinding,
            "service_id",whole.group("id"),"selected_departure_stop",from,"selected_arrival_stop",to,"all_stop_items",stops,
            "clock_duration_minutes",minutes,"arrival_day_offset",0,"precision_basis","computed_same_service_departure_arrival",
            "not_published_fastest_duration",true,"operating_context","effective_regular_diagram_baseline");
    }
    private PublicStationContext(){}
}
