package com.training;

import java.util.*;

/** Synthetic local clock notation, not a new transport source or semantic reader. */
public final class CuratedClockNotationTest {
    static int checks;
    static void check(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
    static Map<String,Object> m(Object...kv){return CuratedCityDurationTest.m(kv);}
    static Map<String,Object> map(Object x){return CuratedCityDurationTest.mutable(x);}
    static CuratedCityDuration.Direction fixture(boolean reverse,String depToken,String arrToken,String depValue,String arrValue,int minutes){
        var original=CuratedCityDurationTest.fixture(reverse,CuratedCityDurationTest.clocks(depValue,arrValue,0,minutes));
        String from=original.from().get("city")+(reverse?"虹桥站":"南站"),to=original.to().get("city")+(reverse?"南站":"虹桥站");
        String raw="此项是已运营高铁公开城市参考。G123次："+from+depToken+"，"+to+arrToken+"。";
        var changed=CuratedCityDurationTest.source(original,s->s.put("units",List.of(m("unit_id","u1","text",raw,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(raw)))));
        return CuratedCityDurationTest.both(changed,f->f.put("spans",m(
            "context",List.of(CuratedCityDurationTest.span(raw,"此项是已运营高铁公开城市参考。")),
            "from",List.of(CuratedCityDurationTest.span(raw,from)),"to",List.of(CuratedCityDurationTest.span(raw,to)),
            "service",List.of(CuratedCityDurationTest.span(raw,"G123")),
            "departure",List.of(CuratedCityDurationTest.span(raw,depToken)),"arrival",List.of(CuratedCityDurationTest.span(raw,arrToken)),
            "segment",List.of(CuratedCityDurationTest.span(raw,raw.substring(raw.indexOf("G123")))))));
    }
    static Map<String,Object> run(CuratedCityDuration.Direction out,CuratedCityDuration.Direction back){return CuratedCityDurationTest.run(out,back);}
    static CuratedCityDuration.Direction clippedClock(CuratedCityDuration.Direction original,String role,String token,String value,int minutes){
        return CuratedCityDurationTest.both(original,f->{
            Map<String,Object> span=CuratedCityDurationTest.sp(f,role);String whole=(String)span.get("text");int at=whole.indexOf(token);
            if(at<0||whole.indexOf(token,at+token.length())>=0)throw new AssertionError("unique clock substring required");
            int start=((Number)span.get("start")).intValue()+at;span.put("start",start);span.put("end",start+token.length());span.put("text",token);
            CuratedCityDurationTest.dur(f).put(role+"_clock",value);CuratedCityDurationTest.dur(f).put("calculated_minutes",minutes);
        });
    }
    static void ready(Map<?,?> r,String why){check("curated_planning_values".equals(r.get("status")),why+": "+r);check(Boolean.FALSE.equals(r.get("transport_verified")),"notation never confirms an itinerary");}
    static void pending(Map<?,?> r,String reason){check("curated_planning_pending".equals(r.get("status"))&&r.get("reason").toString().contains(reason),reason+": "+r);}
    public static void main(String[] args){
        var back=fixture(true,"21:33/35","22:10","21:35","22:10",35);
        var out=fixture(false,"06:21","06:56/58","06:21","06:56",35);
        ready(run(out,back),"return uses departure minute35; outward uses arrival minute56");
        ready(run(back,out),"both directions independently reversible");
        for(String sep:List.of("/","／"))for(String colon:List.of(":","：","∶")){
            var a=fixture(false,"06"+colon+"19"+sep+"21","06"+colon+"56"+sep+"58","06:21","06:56",35);
            var b=fixture(true,"21"+colon+"33"+sep+"21"+colon+"35","22"+colon+"10"+sep+"22"+colon+"12","21:35","22:10",35);
            ready(run(a,b),"complete shorthand and full pair tokens "+sep+colon);
        }
        pending(run(out,CuratedCityDurationTest.both(back,f->CuratedCityDurationTest.dur(f).put("departure_clock","21:33"))),"clock_source_mismatch");
        pending(run(CuratedCityDurationTest.both(out,f->CuratedCityDurationTest.dur(f).put("arrival_clock","06:58")),back),"clock_source_mismatch");
        pending(run(out,CuratedCityDurationTest.both(back,f->CuratedCityDurationTest.dur(f).put("calculated_minutes",37))),"clock_duration_mismatch");
        var clipped=CuratedCityDurationTest.both(back,f->{Map<String,Object> s=CuratedCityDurationTest.sp(f,"departure");s.put("end",((Number)s.get("start")).intValue()+5);s.put("text","21:33");});
        pending(run(out,clipped),"incomplete_clock_token");
        var minuteOnly=CuratedCityDurationTest.both(back,f->{Map<String,Object> s=CuratedCityDurationTest.sp(f,"departure");s.put("start",((Number)s.get("end")).intValue()-2);s.put("text","35");});
        pending(run(out,minuteOnly),"incomplete_clock_token");
        for(String whitespace:List.of(" ","\t","\u00a0","\u3000","\n"))for(String slash:List.of("/","／")){
            var departure=fixture(true,"21:33"+whitespace+slash+whitespace+"21:35","22:10","21:35","22:10",35);
            String originalDeparture=Json.write(departure.source());
            pending(run(out,departure),"clock_token_invalid"); // No new whitespace-separated syntax support.
            pending(run(out,clippedClock(departure,"departure","21:33","21:33",37)),"incomplete_clock_token");
            pending(run(out,clippedClock(departure,"departure","21:35","21:35",35)),"incomplete_clock_token");
            var arrival=fixture(false,"06:21","06:56"+whitespace+slash+whitespace+"06:58","06:21","06:56",35);
            String originalArrival=Json.write(arrival.source());
            pending(run(arrival,back),"clock_token_invalid");
            pending(run(clippedClock(arrival,"arrival","06:56","06:56",35),back),"incomplete_clock_token");
            pending(run(clippedClock(arrival,"arrival","06:58","06:58",37),back),"incomplete_clock_token");
            check(originalDeparture.equals(Json.write(departure.source()))&&originalArrival.equals(Json.write(arrival.source())),"whitespace boundary checks never rewrite source");
        }
        for(int count:List.of(32,33)){
            var separated=fixture(true,"21:33"+" ".repeat(count)+"/"+" ".repeat(count)+"21:35","22:10","21:35","22:10",35);
            pending(run(out,clippedClock(separated,"departure","21:33","21:33",37)),"incomplete_clock_token");
            pending(run(out,clippedClock(separated,"departure","21:35","21:35",35)),"incomplete_clock_token");
        }
        for(String whitespace:List.of(" ","\t","\u00a0","\u3000","\n")){
            var padded=fixture(true,whitespace+"21:35"+whitespace,"22:10","21:35","22:10",35);
            ready(run(out,clippedClock(padded,"departure","21:35","21:35",35)),"standalone clock with surrounding whitespace and no slash remains valid");
        }
        for(String token:List.of("21:59/02","21:59/00:02","21:33/21:32"))pending(run(out,fixture(true,token,"22:10","21:35","22:10",35)),"clock_pair_rollover_not_explicit");
        for(String token:List.of("21:33/60","25:00/02","21:33/25:02"))pending(run(out,fixture(true,token,"22:10","21:35","22:10",35)),"clock_invalid");
        for(String token:List.of("21:33/5","21:33/","21:33/35/36"))pending(run(out,fixture(true,token,"22:10","21:35","22:10",35)),token.endsWith("/36")?"clock_pair_invalid":"clock_token_invalid");
        ready(run(out,fixture(true,"21:35/35","22:10/10","21:35","22:10",35)),"equal arrival departure is explicit zero dwell");
        String before=Json.write(back.source());run(out,back);check(before.equals(Json.write(back.source())),"original complete paired token remains unchanged");
        System.out.println("Curated arrival/departure clock notation: "+checks+" synthetic assertions passed");
    }
}
