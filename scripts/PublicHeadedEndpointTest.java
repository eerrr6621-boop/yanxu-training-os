package com.training;

import java.time.*;
import java.util.*;

/** Synthetic natural-clock regressions; no real service ids/timetables embedded. */
public final class PublicHeadedEndpointTest {
    static int checks;static final LocalDate TODAY=LocalDate.of(2026,9,8);
    static final String OUT="G812/3次\n\n福州站7:53始发，11:38终到厦门北站";
    static final String BACK="G814/5次\n\n厦门北站12:05始发，15:26终到福州站";
    static final String NOTICE="为更好满足闽东与闽南地区间旅客出行需求，自8月21日起，将新增福州至厦门北G812/3/4/5次、泉州至厦门北G817/G818次共2对动车组列车。";
    static Map<String,Object> m(Object...kv){return PublicStopChainTest.m(kv);}
    static Map<String,Object> map(Object x){return PublicStopChainTest.map(x);}
    static Map<String,Object> copy(Map<?,?> x){return PublicStopChainTest.copy(x);}
    static void original(Map<String,Object> c,String s){PublicStopChainTest.original(c,s);}
    static void ok(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
    interface Run{void run();}static void bad(Run r,String m){try{r.run();throw new AssertionError("accepted "+m);}catch(IllegalArgumentException|DateTimeException e){checks++;}}
    static Map<String,Object> evidence(boolean back){
        Map<String,Object> c=PublicStopChainTest.strict(false,back?"厦门":"福州",back?"福州":"厦门");
        c.put("selected_service_id",back?"G814/5":"G812/3");c.put("selected_from_station",back?"厦门北站":"福州站");c.put("selected_to_station",back?"福州站":"厦门北站");
        original(c,back?BACK:OUT);original(map(c.get("schedule_effective_notice")),NOTICE);return c;
    }
    static PublicCityBounds.Duration validate(Map<String,Object> c){return PublicStopChainTest.validate(c);}
    static void syntax(){
        ok(validate(evidence(false)).lowerMinutes==225,"outbound clock");ok(validate(evidence(true)).lowerMinutes==201,"independently different reverse");
        Map<String,Object> c=evidence(false),context=PublicCityContext.describe(c);ok("G812/3".equals(context.get("service_id")),"compound literal id retained");
        Map<?,?> span=map(context.get("service_span"));ok(OUT.substring(((Number)span.get("start")).intValue(),((Number)span.get("end")).intValue()).equals("G812/3"),"exact id source span");
        ok("independent_source_service".equals(context.get("direction_label")),"no fabricated return direction label");
        Map<String,Object> bound=validate(c).toMap();ok(PublicCityBounds.fromStored(bound).lowerMinutes==225,"stored full original recalc");
        for(String k:List.of("calculated_minutes","lower_minutes","upper_minutes")){Map<String,Object> x=copy(bound);x.put(k,224);bad(()->CityTravelReference.legDuration(m("duration_bounds",x)),"minute mismatch "+k);}
        for(String source:List.of(OUT.replace("\n\n","\r\n"),OUT+"。",OUT.replace("7:53","07：53"))){Map<String,Object> x=evidence(false);original(x,source);ok(validate(x).lowerMinutes==225,"permitted layout/fullwidth clock");}
        for(String source:List.of(OUT.replace("\n\n",""),OUT.replace("G812/3","G812/3/4/5"),OUT.replace("G812/3","G812/G813"),OUT.replace("G812/3","G812/\n3"),OUT.replace("福州","福\n州"),OUT.replace("7:53","7:\n53"),OUT.replace("始发","到达"),OUT.replace("终到","出发"),OUT.replace("7:53","11:39"),OUT.replace("11:38","次日11:38"),OUT.replace("7:53","24:00"),OUT.replace("11:38","11:60"),OUT+"，现已停运。",OUT+"；仅节假日开行。",OUT+"，预计恢复。",OUT+"\n"+BACK,"不是"+OUT,OUT.replace("福州站","厦门北站"))){Map<String,Object> x=evidence(false);original(x,source);bad(()->validate(x),"malformed/nonregular clock source");}
        for(String k:List.of("selected_service_id","selected_from_station","selected_to_station")){Map<String,Object> x=evidence(false);x.put(k,k.equals("selected_service_id")?"G814/5":k.equals("selected_from_station")?"厦门北站":"福州站");bad(()->validate(x),"wrong selected role "+k);}
        Map<String,Object> x=evidence(false);x.put("from_city","厦门");x.put("from_endpoint","厦门");x.put("to_city","福州");x.put("to_endpoint","福州");bad(()->validate(x),"cannot mirror cities");
    }
    static void notice(){
        Map<String,Object> one=evidence(false);original(map(one.get("schedule_effective_notice")),"自2026年8月21日起，将新增福州至厦门北G812/3/4/5次共1对动车组列车。");ok(validate(one).lowerMinutes==225,"explicit year and single corridor");
        for(String notice:List.of(NOTICE.replace("共2对","共1对"),NOTICE.replace("8月21","10月21"),NOTICE.replace("8月21","8月22"),NOTICE.replace("将新增","拟新增"),NOTICE.replace("将新增","将临时新增"),NOTICE.replace("福州至厦门北","漳州至厦门北"),NOTICE.replace("日起","日起预计"),NOTICE.replace("。","，现已取消。"),NOTICE.replace("。","，仅暑运实施。"),NOTICE+"本公告尚未生效。",NOTICE.replace("共2对","共0对"))){Map<String,Object> x=evidence(false);original(map(x.get("schedule_effective_notice")),notice);bad(()->validate(x),"notice scope/date/regularity");}
        for(String field:List.of("source_url","source_published_on")){Map<String,Object> x=evidence(false);map(x.get("schedule_effective_notice")).put(field,field.equals("source_url")?"https://example.invalid/other":"2026-08-19");bad(()->validate(x),"notice source binding "+field);}
        Map<String,Object> x=evidence(false);x.put("effective_from","2026-10-21");original(map(x.get("schedule_effective_notice")),NOTICE.replace("8月21","10月21"));bad(()->validate(x),"future not effective even with matching declaration");
    }
    static Map<String,Object> role(String role,String token){return PublicStopChainTest.span("O",OUT,token,0);}
    static Map<String,Object> fact(){
        Map<String,Object> f=PublicStopChainTest.fact(),c=map(f.get("canonical"));f.put("from_city","福州");f.put("to_city","厦门");c.put("from_registry_id",CityPlanningReference.registryId("福建","福州"));c.put("from_endpoint","福州站");c.put("to_endpoint","厦门北站");c.put("service_id","G812/3");c.put("departure_clock","07:53");c.put("arrival_clock","11:38");c.put("calculated_minutes",225);
        f.put("spans",m("from",List.of(role("from","福州站")),"to",List.of(role("to","厦门北站")),"departure",List.of(role("departure","7:53")),"arrival",List.of(role("arrival","11:38")),"service",List.of(role("service","G812/3")),"duration",List.of(),"context",List.of(role("context",OUT))));return f;
    }
    static Map<String,Object> source(){return m("source_id","TEST","published_on","2026-08-20","units",List.of(m("unit_id","O","text",OUT,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(OUT)),m("unit_id","B","text",BACK,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(BACK))));}
    static int planning(Map<?,?> f,Map<?,?> source){return CityPlanningReference.validateReadFact(f,source,PublicStopChainTest.city("福州"),PublicStopChainTest.city("厦门"));}
    static void sharedPlanningGrammar(){
        ok(planning(fact(),source())==225,"same parser binds natural clock fact; not planning admission");
        for(String role:List.of("from","to","departure","arrival","service")){Map<String,Object> f=fact();map(((List<?>)map(f.get("spans")).get(role)).get(0)).put("unit_id","B");bad(()->planning(f,source()),"no mixed directional spans "+role);}
        for(String field:List.of("calculated_minutes","departure_clock","arrival_clock","service_id","arrival_day_offset")){Map<String,Object> f=fact();map(f.get("canonical")).put(field,field.equals("calculated_minutes")?224:field.equals("arrival_day_offset")?1:field.equals("service_id")?"G814/5":field.equals("departure_clock")?"11:38":"07:53");bad(()->planning(f,source()),"planning role/canonical mismatch "+field);}
    }
    public static void main(String[] args){syntax();notice();sharedPlanningGrammar();System.out.println("PublicHeadedEndpointTest: "+checks+" checks passed (synthetic only)");}
}
