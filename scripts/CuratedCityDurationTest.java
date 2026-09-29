package com.training;

import java.time.LocalDate;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Synthetic arithmetic/trust-boundary tests only. No real sources, model, DB or network. */
public final class CuratedCityDurationTest {
    static int checks;static final LocalDate TODAY=LocalDate.of(2026,9,8);
    static Map<String,Object> m(Object...kv){Map<String,Object> x=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)x.put((String)kv[i],kv[i+1]);return x;}
    @SuppressWarnings("unchecked") static Map<String,Object> mutable(Object x){return (Map<String,Object>)x;}
    static Map<String,Object> copy(Object x){return mutable(Json.parse(Json.write(x)));}
    static Map<String,Object> city(String p,String c){return m("province",p,"city",c,"id",CityPlanningReference.registryId(p,c));}
    static Map<String,Object> span(String raw,String token){int at=raw.indexOf(token);if(at<0)throw new AssertionError(token);return m("unit_id","u1","start",at,"end",at+token.length(),"text",token);}
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static Map<String,Object> scalar(String kind,String token,int value){return m("kind",kind,"value",value,"unit",kind.equals("nominal_hour")?"hour":"minute","token",token);}
    static Map<String,Object> upper(String token,int value,boolean incl){return m("kind","upper_bound","upper",value,"unit","minute","upper_inclusive",incl,"token",token);}
    static Map<String,Object> range(String token,int lo,int hi,boolean li,boolean ui){return m("kind","bounded_range","lower",lo,"upper",hi,"unit","minute","lower_inclusive",li,"upper_inclusive",ui,"token",token);}
    static CuratedCityDuration.Direction fixture(boolean reverse,Map<String,Object> duration){
        return fixture(reverse,duration,null,null);
    }
    static CuratedCityDuration.Direction fixture(boolean reverse,Map<String,Object> duration,String fromEndpoint,String toEndpoint){
        Map<String,Object> from=city(reverse?"上海":"江苏",reverse?"上海":"南京"),to=city(reverse?"江苏":"上海",reverse?"南京":"上海");
        String f=(String)from.get("city"),t=(String)to.get("city"),id=reverse?"synthetic-return":"synthetic-out";
        boolean clock=duration.get("kind").equals("same_service_clocks");if(clock){f+=reverse?"虹桥站":"南站";t+=reverse?"南站":"虹桥站";}
        if(fromEndpoint!=null)f=fromEndpoint;if(toEndpoint!=null)t=toEndpoint;
        String fromScope=f.equals(from.get("city"))?"city_summary":"main_urban_station",toScope=t.equals(to.get("city"))?"city_summary":"main_urban_station";
        String raw="此项是已运营高铁公开城市参考。"+(clock?duration.get("service_id")+"次："+f+duration.get("departure_clock")+"出发，"+(((Number)duration.get("arrival_day_offset")).intValue()==1?"次日":"")+t+duration.get("arrival_clock")+"到达。":f+"至"+t+"运行"+duration.get("token")+"。");
        Map<String,Object> unit=m("unit_id","u1","text",raw,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(raw));
        Map<String,Object> source=m("source_id",id,"url","https://example.invalid/"+id,"published_on",TODAY.toString(),"units",new ArrayList<>(List.of(unit)));
        Map<String,Object> c=m("verification_method",CuratedCityDuration.METHOD,"source_id",id,"from_registry_id",from.get("id"),"to_registry_id",to.get("id"),
            "from_endpoint",f,"to_endpoint",t,"endpoint_scope",m("from",fromScope,"to",toScope),
            "mapping_basis","literal_city_prefix","transport_mode","rail","route_scope",clock?"station_service_pair":fromScope.equals("city_summary")&&toScope.equals("city_summary")?"city_summary":"station_corridor",
            "published_on",TODAY.toString(),"effective_on",TODAY.toString(),"effective_until",null,"service_state","regular_diagram_reference","status","active","duration",duration);
        Map<String,Object> spans=m("from",List.of(span(raw,f)),"to",List.of(span(raw,t)),"context",List.of(span(raw,"此项是已运营高铁公开城市参考。")));
        if(clock){spans.put("service",List.of(span(raw,(String)duration.get("service_id"))));spans.put("departure",List.of(span(raw,(String)duration.get("departure_clock"))));spans.put("arrival",List.of(span(raw,(String)duration.get("arrival_clock"))));spans.put("segment",List.of(span(raw,raw.substring(raw.indexOf((String)duration.get("service_id"))))));if(((Number)duration.get("arrival_day_offset")).intValue()==1)spans.put("arrival_day",List.of(span(raw,"次日")));}
        else spans.put("duration",List.of(span(raw,(String)duration.get("token"))));
        Map<String,Object> fact=m("from_city",from.get("city"),"to_city",to.get("city"),"canonical",c,"spans",spans,"rationale","Synthetic agreeing annotations; these tests authenticate no reader or source.");
        return new CuratedCityDuration.Direction(source,fact,copy(fact),from,to,TODAY);
    }
    static CuratedCityDuration.Direction change(CuratedCityDuration.Direction d,Consumer<Map<String,Object>> a,Consumer<Map<String,Object>> b){Map<String,Object> aa=copy(d.readA()),bb=copy(d.readB());a.accept(aa);b.accept(bb);return new CuratedCityDuration.Direction(d.source(),aa,bb,d.from(),d.to(),d.reviewedOn());}
    static CuratedCityDuration.Direction both(CuratedCityDuration.Direction d,Consumer<Map<String,Object>> edit){return change(d,edit,edit);}
    static Map<String,Object> c(Map<String,Object> f){return mutable(f.get("canonical"));}
    static Map<String,Object> dur(Map<String,Object> f){return mutable(c(f).get("duration"));}
    static Map<String,Object> sp(Map<String,Object> f,String role){return mutable(((List<?>)mutable(f.get("spans")).get(role)).get(0));}
    static CuratedCityDuration.Direction source(CuratedCityDuration.Direction d,Consumer<Map<String,Object>> change){Map<String,Object> s=copy(d.source());change.accept(s);return new CuratedCityDuration.Direction(s,d.readA(),d.readB(),d.from(),d.to(),d.reviewedOn());}
    static String raw(CuratedCityDuration.Direction d){return (String)((Map<?,?>)((List<?>)d.source().get("units")).get(0)).get("text");}
    static Map<String,Object> run(CuratedCityDuration.Direction out,CuratedCityDuration.Direction back){return CuratedCityDuration.pairAfterEnvelopeValidation(out,back,TODAY);}
    static void ready(Map<String,Object> result,String why){check("curated_planning_values".equals(result.get("status")),why+": "+result);check(Boolean.FALSE.equals(result.get("envelope_verified_by_this_component"))&&Boolean.TRUE.equals(result.get("requires_validated_envelope")),"not an envelope/identity verdict");check(!"planning_reference".equals(result.get("status")),"not directly consumable by old business admission");}
    static void pending(Map<String,Object> result,String reason){check("curated_planning_pending".equals(result.get("status")),"pending expected "+reason+": "+result);check(result.get("reason").toString().contains(reason),"correct reason expected "+reason+": "+result.get("reason"));check(!result.containsKey("planning_band_id"),"pending never carries affirmative band");}
    static Map<String,Object> clocks(String dep,String arr,int offset,int minutes){return m("kind","same_service_clocks","service_id","G123","departure_clock",dep,"arrival_clock",arr,"arrival_day_offset",offset,"calculated_minutes",minutes);}
    public static void main(String[] args) throws Exception {
        var out=fixture(false,scalar("reported_minutes","90分钟",90));var back=fixture(true,scalar("reported_minutes","100分钟",100));
        Map<String,Object> first=run(out,back);ready(first,"plain city minutes");check(first.get("planning_band_id").equals("planning_1_2h"),"larger independent unbuffered return selects band");
        check(Boolean.FALSE.equals(first.get("buffer_applied_to_selection")),"historical reminder does not tighten the requested cutoff");
        for(String flag:List.of("transport_verified","time_score_applicable","air_fallback_trigger","rail_exclusion_complete","strict_eligibility","buffer_is_connection_or_door_to_door_time"))check(Boolean.FALSE.equals(first.get(flag)),"no stronger claim: "+flag);
        ready(run(fixture(false,scalar("nominal_hour","1小时",1)),fixture(true,scalar("nominal_hour","2小时",2))),"whole hour stays nominal");
        for(String token:List.of("约1小时30分钟","大约90分钟","90分钟左右","90分钟上下")){
            Map<String,Object> r=run(fixture(false,scalar("approximate",token,90)),back);ready(r,"explicit approximation "+token);
            Map<?,?> leg=(Map<?,?>)r.get("outbound");check(leg.get("precision").equals("approximate")&&Boolean.FALSE.equals(leg.get("policy_value_is_proven_travel_upper_bound")),"approximation does not gain a source error bound");
        }
        for(String token:List.of("1小时以内","1小时以下","不超过1小时","最多60分钟","至多1小时"))ready(run(fixture(false,upper(token,60,true)),back),"inclusive upper "+token);
        for(String token:List.of("不到1小时","少于1小时","低于1小时","不足1小时"))ready(run(fixture(false,upper(token,60,false)),back),"exclusive upper "+token);
        Map<String,Object> bounded=run(fixture(false,range("1小时至1小时30分钟",60,90,true,true)),back);ready(bounded,"closed range");
        Map<?,?> stored=(Map<?,?>)((Map<?,?>)bounded.get("outbound")).get("source_duration");check(stored.get("lower").equals(60)&&stored.get("upper").equals(90)&&stored.get("upper_inclusive").equals(true),"range bounds remain distinct");
        ready(run(fixture(false,range("大于1小时至不足1小时30分钟",60,90,false,false)),back),"open range preserved");
        pending(run(fixture(false,range("1小时至1小时30分钟",60,90,false,true)),back),"range_boundary_mismatch");
        pending(run(fixture(false,upper("不到1小时",60,true)),back),"upper_boundary_mismatch");
        pending(run(fixture(false,upper("约1小时",60,true)),back),"upper_token_invalid");
        pending(run(fixture(false,scalar("reported_minutes","约90分钟",90)),back),"duration_token_invalid");
        pending(run(fixture(false,scalar("reported_minutes","1小时",60)),back),"reported_precision_invalid");
        pending(run(fixture(false,scalar("nominal_hour","1小时30分钟",1)),back),"nominal_precision_invalid");
        pending(run(fixture(false,scalar("approximate","90分钟",90)),back),"approximate_marker_missing");
        pending(run(fixture(false,scalar("reported_minutes","90分钟",91)),back),"reported_value_mismatch");
        pending(run(change(out,f->dur(f).put("value",91),f->{}),back),"core_read_disagreement");
        pending(run(change(out,f->c(f).put("verification_method","other"),f->{}),back),"core_read_disagreement");
        pending(run(change(out,f->mutable(c(f).get("endpoint_scope")).put("from","county"),f->{}),back),"core_read_disagreement");
        pending(run(change(fixture(false,upper("不到1小时",60,false)),f->dur(f).put("upper_inclusive",true),f->{}),back),"core_read_disagreement");
        pending(run(both(out,f->c(f).put("verification_method","other")),back),"verification_method_mismatch");
        for(String scope:List.of("county","unknown","unknown_connection","city_with_county_station"))pending(run(both(out,f->mutable(c(f).get("endpoint_scope")).put("from",scope)),back),"county_or_unknown_endpoint_scope");
        pending(run(both(out,f->c(f).put("mapping_basis","county_city_mapping")),back),"external_station_mapping_not_supported");
        pending(run(both(out,f->c(f).put("from_endpoint","常熟")),back),"nonliteral_or_mismatched_endpoint");
        for(String state:List.of("unknown","temporary","planned","stopped","expired"))pending(run(both(out,f->c(f).put("service_state",state)),back),"not_active_operating_reference");
        pending(run(both(out,f->c(f).put("status","revoked")),back),"not_active_operating_reference");
        pending(run(both(out,f->c(f).put("effective_on","2026-09-09")),back),"not_yet_effective");
        pending(run(both(out,f->{c(f).put("effective_on","2026-08-01");c(f).put("effective_until","2026-08-31");}),back),"effective_period_ended_or_invalid");
        pending(run(both(out,f->c(f).put("effective_on",null)),back),"diagram_effective_date_missing");
        for(String flag:List.of("minimal_fact_extraction_prohibited","minimal_fact_reuse_prohibited","source_research_only","automation_prohibited","access_restricted"))pending(run(source(out,s->s.put(flag,true)),back),"original_source_restriction");
        pending(run(source(out,s->s.put("status","withdrawn")),back),"source_withdrawn");
        pending(run(source(out,s->s.put("reuse_state","restricted")),back),"original_source_reuse_restricted");
        ready(run(source(out,s->{s.put("runtime_enabled",false);s.put("private_only",true);}),back),"staging state is not a source prohibition");
        pending(run(source(out,s->s.put("url","http://example.invalid")),back),"invalid_source_url");
        pending(run(source(out,s->s.put("published_on","2026-09-09")),back),"future_source_or_review");
        pending(run(source(out,s->s.put("published_on","2025-09-07")),back),"source_or_review_stale");
        pending(run(source(out,s->s.put("published_on","2026-02-30")),back),"invalid_ISO_date");
        pending(CuratedCityDuration.pairAfterEnvelopeValidation(out,back,TODAY.plusDays(91)),"source_or_review_stale");
        pending(run(source(out,s->mutable(((List<?>)s.get("units")).get(0)).put("text","edited")),back),"source_unit_invalid");
        pending(run(both(out,f->sp(f,"duration").put("end",0)),back),"span_mismatch");
        pending(run(both(out,f->sp(f,"from").put("text","上海")),back),"span_mismatch");
        pending(run(both(out,f->f.put("rationale","")),back),"reader_rationale_missing");
        pending(run(both(out,f->c(f).put("extra_approved",true)),back),"fields_missing_or_unknown");
        pending(run(out,out),"two_directions_not_inverse");
        pending(CuratedCityDuration.pairAfterEnvelopeValidation(null,back,TODAY),"context_missing");
        for(int n:List.of(1,29,30,31,89,90,149,150,209,210,211,227,239))ready(run(fixture(false,scalar("reported_minutes",n+"分钟",n)),fixture(true,scalar("reported_minutes",n+"分钟",n))),"both reference directions below 240: "+n);
        for(int n:List.of(240,241,300)){
            pending(run(fixture(false,scalar("reported_minutes",n+"分钟",n)),back),"curated_reference_boundary_pending");
            pending(run(out,fixture(true,scalar("reported_minutes",n+"分钟",n))),"curated_reference_boundary_pending");
        }
        ready(run(fixture(false,scalar("approximate","约210分钟",210)),back),"approximate reference under cutoff remains planning only");
        ready(run(fixture(false,upper("不到210分钟",210,false)),back),"exclusive upper below cutoff");
        ready(run(fixture(false,upper("不到240分钟",240,false)),back),"explicit exclusive 240 upper qualifies");
        ready(run(out,fixture(true,range("大于180分钟至不足240分钟",180,240,false,false))),"return exclusive range upper qualifies");
        pending(run(fixture(false,upper("不超过240分钟",240,true)),back),"curated_reference_boundary_pending");
        pending(run(out,fixture(true,range("180分钟至240分钟",180,240,true,true))),"curated_reference_boundary_pending");
        pending(run(fixture(false,scalar("approximate","约240分钟",240)),back),"curated_reference_boundary_pending");
        var co=fixture(false,clocks("23:00","01:00",1,120));var cb=fixture(true,clocks("09:00","11:00",0,120));ready(run(co,cb),"explicit next day clock arithmetic");
        ready(run(fixture(false,clocks("05∶54","08∶46",0,172)),fixture(true,clocks("19∶21","22∶08",0,167))),"source Unicode clocks no newspaper grammar");
        pending(run(both(co,f->dur(f).put("calculated_minutes",121)),cb),"clock_duration_mismatch");
        pending(run(both(co,f->dur(f).put("arrival_day_offset",0)),cb),"unrecorded_day_offset");
        pending(run(both(cb,f->dur(f).put("arrival_day_offset",1)),co),"single_arrival_day_span_required");
        pending(run(both(co,f->dur(f).put("arrival_clock","01:01")),cb),"clock_source_mismatch");
        pending(run(fixture(false,clocks("25:00","27:00",0,120)),cb),"clock_invalid");
        pending(run(fixture(false,clocks("23:00","01:00",0,120)),cb),"clock_duration_mismatch");
        pending(run(both(co,f->sp(f,"segment").put("start",sp(f,"arrival").get("start"))),cb),"span_mismatch");
        pending(run(both(co,f->dur(f).put("service_id","G124")),cb),"service_token_mismatch");
        pending(run(both(co,f->mutable(c(f).get("endpoint_scope")).put("from","county")),cb),"county_or_unknown_endpoint_scope");
        Map<String,Object> mixed=run(co,back);ready(mixed,"city summaries and independently validated urban stations may mix");
        check(Boolean.TRUE.equals(mixed.get("endpoint_difference"))&&Boolean.TRUE.equals(mixed.get("endpoint_specificity_mixed")),"mixed specificity gets both explicit notices");
        check(((List<?>)mixed.get("endpoint_notices")).size()==2,"both cities retain separate endpoint notices");
        check(((Map<?,?>)mixed.get("outbound")).get("from_endpoint").equals("南京南站")&&((Map<?,?>)mixed.get("inbound")).get("to_endpoint").equals("南京"),"original station and city summary retained, not renamed to match");
        Map<String,Object> differentStation=run(co,fixture(true,clocks("09:00","11:00",0,120),"上海虹桥站","南京东站"));
        ready(differentStation,"different independently reviewed main-urban stations in the same city");
        check(Boolean.TRUE.equals(differentStation.get("endpoint_difference"))&&Boolean.FALSE.equals(differentStation.get("endpoint_specificity_mixed")),"different urban stations are not a specificity mixture");
        List<?> notices=(List<?>)differentStation.get("endpoint_notices");check(notices.size()==1,"only changed city has a notice");
        Map<?,?> notice=(Map<?,?>)notices.get(0);check(((Map<?,?>)notice.get("city")).get("city").equals("南京")&&notice.get("outbound_endpoint").equals("南京南站")&&notice.get("inbound_endpoint").equals("南京东站"),"notice binds both original station names to the same registry city");
        check(Boolean.FALSE.equals(notice.get("transfer_time_estimated"))&&Boolean.FALSE.equals(differentStation.get("buffer_is_connection_or_door_to_door_time")),"30-minute policy reserve is not a station transfer estimate");
        Map<String,Object> oneMixed=run(co,fixture(true,scalar("reported_minutes","100分钟",100),"上海虹桥站","南京"));
        ready(oneMixed,"one urban station pair and one summary-station pair");check(((List<?>)oneMixed.get("endpoint_notices")).size()==1&&Boolean.TRUE.equals(oneMixed.get("endpoint_specificity_mixed")),"one-city specificity warning only");
        check(Boolean.FALSE.equals(first.get("endpoint_difference"))&&Boolean.FALSE.equals(first.get("endpoint_specificity_mixed"))&&((List<?>)first.get("endpoint_notices")).isEmpty(),"matching city summaries do not get spurious differences");
        Map<String,Object> sameStations=run(co,cb);check(Boolean.FALSE.equals(sameStations.get("endpoint_difference"))&&Boolean.FALSE.equals(sameStations.get("endpoint_specificity_mixed")),"matching urban stations remain unchanged");
        var countyStation=fixture(true,clocks("09:00","11:00",0,120),"上海虹桥站","南京县域站");
        for(String scope:List.of("county","unknown","unknown_connection"))pending(run(co,both(countyStation,f->mutable(c(f).get("endpoint_scope")).put("to",scope))),"county_or_unknown_endpoint_scope");
        pending(run(co,both(cb,f->c(f).put("to_endpoint","南京东站"))),"endpoint_span_mismatch");
        pending(run(co,change(cb,f->mutable(c(f).get("endpoint_scope")).put("to","county"),f->{})),"core_read_disagreement");
        try{mutable(notices.get(0)).put("transfer_time_estimated",true);throw new AssertionError("mutable endpoint notice");}catch(UnsupportedOperationException good){checks++;}
        var stripped=fixture(false,scalar("approximate","约90分钟",90));
        stripped=both(stripped,f->{dur(f).put("kind","reported_minutes");dur(f).put("token","90分钟");Map<String,Object> s=sp(f,"duration");s.put("start",((Number)s.get("start")).intValue()+1);s.put("text","90分钟");});
        pending(run(stripped,back),"qualifier_outside_selected_token");
        var stripBound=fixture(false,upper("不超过90分钟",90,true));
        stripBound=both(stripBound,f->{c(f).put("duration",scalar("reported_minutes","90分钟",90));Map<String,Object> s=sp(f,"duration");s.put("start",((Number)s.get("start")).intValue()+3);s.put("text","90分钟");});
        pending(run(stripBound,back),"qualifier_outside_selected_token");
        pending(run(fixture(false,range("1小时至2小时",60,90,true,true)),back),"range_value_mismatch");
        pending(run(change(out,f->dur(f).put("kind","approximate"),f->{}),back),"core_read_disagreement");
        pending(run(both(out,f->dur(f).put("value",90.5)),back),"invalid_integer");
        var differentUnit=source(co,s->{String next="另一服务上海虹桥站01:00到达。";mutable(s).put("units",List.of(((List<?>)s.get("units")).get(0),m("unit_id","u2","text",next,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(next))));});
        differentUnit=both(differentUnit,f->{Map<String,Object> s=sp(f,"arrival");s.put("unit_id","u2");s.put("start",11);s.put("end",16);});
        // Exact offsets are recomputed against the second unit; the source span is valid but
        // it is not in the first service's segment and must not be joined into a journey.
        differentUnit=both(differentUnit,f->{String next="另一服务上海虹桥站01:00到达。";int at=next.indexOf("01:00");sp(f,"arrival").put("start",at);sp(f,"arrival").put("end",at+5);});
        pending(run(differentUnit,cb),"cross_segment_binding");
        String appended=raw(cb)+"G124次：上海虹桥站11:30出发，南京南站12:30到达。";
        var multiple=source(cb,s->{Map<String,Object> u=mutable(((List<?>)s.get("units")).get(0));u.put("text",appended);u.put("text_sha256",CityPlanningReference.rawHash(appended));});
        multiple=both(multiple,f->{Map<String,Object> s=sp(f,"segment");int at=((Number)s.get("start")).intValue();s.put("end",appended.length());s.put("text",appended.substring(at));});
        pending(run(multiple,co),"multiple_services_in_segment");
        String repeated=raw(cb)+"G123次：上海虹桥站11:30出发，南京南站12:30到达。";
        var duplicate=source(cb,s->{Map<String,Object> u=mutable(((List<?>)s.get("units")).get(0));u.put("text",repeated);u.put("text_sha256",CityPlanningReference.rawHash(repeated));});
        duplicate=both(duplicate,f->{Map<String,Object> s=sp(f,"segment");int at=((Number)s.get("start")).intValue();s.put("end",repeated.length());s.put("text",repeated.substring(at));});
        pending(run(duplicate,co),"unique_service_required");
        Map<String,Object> unknownCity=copy(out.from());unknownCity.put("id","");
        pending(run(new CuratedCityDuration.Direction(out.source(),out.readA(),out.readB(),unknownCity,out.to(),TODAY),back),"unknown_or_mismatched_city");
        for(int changed:List.of(-1,2))pending(run(both(co,f->dur(f).put("arrival_day_offset",changed)),cb),changed<0?"invalid_integer":"day_offset_unsupported");
        pending(run(both(co,f->mutable(f.get("spans")).put("arrival_day",List.of())),cb),"single_arrival_day_span_required");
        Map<String,Object> examples=m("method",CuratedCityDuration.METHOD,"synthetic_only",true,"envelope_validated",false,
            "reported",first,"approximate",run(fixture(false,scalar("approximate","约1小时30分钟",90)),back),
            "range",bounded,"explicit_next_day",run(co,cb),"different_urban_stations",differentStation,"specificity_mixed",oneMixed,"boundary_pending",run(fixture(false,upper("不超过240分钟",240,true)),back));
        if(args.length==1)Files.writeString(Path.of(args[0]),Json.write(examples)+"\n",StandardOpenOption.CREATE_NEW);
        String before=Json.write(out.source());run(out,back);check(before.equals(Json.write(out.source())),"input source not mutated");
        try{mutable(first.get("outbound")).put("precision","exact");throw new AssertionError("mutable output");}catch(UnsupportedOperationException good){checks++;}
        mutable(c(mutable(out.readA())).get("duration")).put("value",91);check(((Map<?,?>)((Map<?,?>)first.get("outbound")).get("source_duration")).get("value").equals(90),"returned snapshot does not alias input maps");
        System.out.println("Curated city duration synthetic contract: "+checks+" assertions passed; no envelope/authentication, runtime, source-data or production claim");
    }
}
