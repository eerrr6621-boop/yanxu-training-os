package com.training;

import java.time.*;
import java.util.*;

/** Synthetic role/cell regressions; actual source preparation is kept private. */
public final class PublicStopChainTest {
    static final LocalDate TODAY=LocalDate.of(2026,9,8);static int checks;
    static final String OUT="去程G812次：福州08:00始发→泉州08:45/08:50→厦门09:40终到。";
    static final String BACK="返程G813次：厦门10:00始发→泉州10:45/10:49→福州11:40终到。";
    static Map<String,Object> m(Object...kv){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)r.put((String)kv[i],kv[i+1]);return r;}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object x){return (Map<String,Object>)x;}
    static Map<String,Object> copy(Map<?,?> x){return map(Json.parse(Json.write(x)));}
    static void ok(boolean b,String msg){if(!b)throw new AssertionError(msg);checks++;}
    interface Run{void run();}static void bad(Run f,String msg){try{f.run();throw new AssertionError("accepted "+msg);}catch(IllegalArgumentException|DateTimeException e){checks++;}}
    static void original(Map<String,Object> c,String s){c.put("original_content",s);c.put("assertion_start",0);c.put("assertion_end",s.endsWith("。")?s.length()-1:s.length());}
    static Map<String,Object> strict(boolean reverse,String from,String to){
        Map<String,Object> c=m("mode","rail","source_access","public_page","evidence_type","public_transport_duration_statement","duration_basis",PublicCityBounds.DURATION_BASIS,"scope",PublicCityBounds.SCOPE,"direction_basis","explicit_direction","city_mapping_checked",true,"transport_context_checked",true,"research_on",TODAY.toString(),"service_state","published_schedule_baseline","source_url","https://example.invalid/synthetic","source_provider","Synthetic government test","publisher_kind","government","source_published_on","2026-08-20","source_title","Synthetic normal rail diagram","from_province","福建","from_city",from,"to_province","福建","to_city",to,"from_endpoint",from,"to_endpoint",to,"business_import_allowed",true,"use_basis","independently_curated_public_facts","license_claimed",false,"context_binding_type",PublicCityContext.SCHEDULE_CHAIN,"selected_service_id",reverse?"G813":"G812","selected_from_station",from,"selected_to_station",to,"schedule_basis","regular_published_running_diagram","effective_from","2026-08-21","scope_note","Synthetic same-service reference, not fastest or live availability.");
        Map<String,Object> notice=m("source_url",c.get("source_url"),"source_access","public_page","source_published_on",c.get("source_published_on"));original(notice,"自8月21日零时起，全国铁路将实行新一季度列车运行图。");c.put("schedule_effective_notice",notice);original(c,reverse?BACK:OUT);return c;
    }
    static PublicCityBounds.Duration validate(Map<String,Object> c){c.put("context_binding",PublicCityContext.describe(c));return PublicCityBounds.validateEvidence(c,TODAY);}
    static Map<String,Object> span(String u,String original,String token,int start){int a=original.indexOf(token,start);if(a<0)throw new AssertionError("test span");return m("unit_id",u,"start",a,"end",a+token.length(),"text",token);}
    static Map<String,Object> source(){return m("source_id","TEST","published_on","2026-08-20","units",List.of(m("unit_id","O","text",OUT,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(OUT)),m("unit_id","B","text",BACK,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(BACK))));}
    static Map<String,Object> city(String name){return m("id",CityPlanningReference.registryId("福建",name),"province","福建","city",name);}
    static Map<String,Object> fact(){return m("source_id","TEST","from_city","泉州","to_city","厦门","canonical",m("from_registry_id",city("泉州").get("id"),"to_registry_id",city("厦门").get("id"),"from_endpoint","泉州","to_endpoint","厦门","transport_mode","rail","route_scope","station_service_pair","service_id","G812","duration_kind","computed_same_service","duration_text",null,"reported_minutes",null,"calculated_minutes",50,"nominal_value",null,"nominal_unit",null,"departure_clock","08:50","arrival_clock","09:40","arrival_day_offset",0,"published_on","2026-08-20","effective_on","2026-08-21","effective_until",null,"service_state","regular_diagram_reference","directness","same_service","mapping_basis","literal_city_prefix","preparation_status","fact_confirmed"),"spans",m("from",List.of(span("O",OUT,"泉州",0)),"to",List.of(span("O",OUT,"厦门",0)),"departure",List.of(span("O",OUT,"08:50",0)),"arrival",List.of(span("O",OUT,"09:40",0)),"duration",List.of(),"service",List.of(span("O",OUT,"G812",0)),"context",List.of(span("O",OUT,OUT,0))),"rationale","Synthetic explicit single-service stop cells, departure at origin and arrival at destination.");}
    static int planning(Map<?,?> f,Map<?,?> s){return CityPlanningReference.validateReadFact(f,s,city("泉州"),city("厦门"));}
    static void syntax(){
        ok(validate(strict(false,"福州","泉州")).lowerMinutes==45,"initial to intermediate uses arrival");
        ok(validate(strict(false,"泉州","厦门")).lowerMinutes==50,"intermediate departure not arrival");
        ok(validate(strict(false,"福州","厦门")).lowerMinutes==100,"whole same service");
        ok(validate(strict(true,"厦门","福州")).lowerMinutes==100,"independent reverse service");
        Map<String,Object> c=strict(false,"泉州","厦门");Map<String,Object> bound=validate(c).toMap();ok(bound.get("reported_minutes")==null&&bound.get("calculated_minutes").equals(50),"calculated not published exact");
        ok(PublicCityBounds.fromStored(bound).lowerMinutes==50,"stored original deterministically re-evaluated");
        for(String key:List.of("calculated_minutes","lower_minutes","upper_minutes")){Map<String,Object> b=copy(bound);b.put(key,49);bad(()->CityTravelReference.legDuration(m("duration_bounds",b)),"stored minute mismatch "+key);}
        for(String value:List.of(OUT.replace("08:45/08:50","08:50/08:45"),OUT.replace("08:45/08:50","08:45"),OUT.replace("09:40终到","07:40终到"),OUT.replace("09:40终到","次日09:40终到"),OUT.replace("08:45/08:50","08:45/50"),OUT.replace("08:00始发","08:00"),OUT.replace("09:40终到","09:40"),OUT.replace("08:00","24:00"),OUT.replace("泉州","泉\n州"),OUT.replace("08:50","08:\n50"),OUT+BACK,OUT.replace("→厦门","→泉州09:00/09:01→厦门"),OUT.replace("去程G812次：","去程G812次：预计"),OUT.replace("08:45/08:50","08:45/08:50（换乘）"))){Map<String,Object> x=strict(false,"福州","厦门");original(x,value);bad(()->validate(x),"invalid explicit chain");}
        c=strict(false,"厦门","福州");Map<String,Object> backwards=c;bad(()->validate(backwards),"cannot mirror selected direction");
        c=strict(false,"福州","厦门");c.put("selected_service_id","G813");Map<String,Object> wrongService=c;bad(()->validate(wrongService),"wrong service");
        for(String k:List.of("selected_from_station","selected_to_station")){Map<String,Object> x=strict(false,"福州","厦门");x.put(k,"漳州");bad(()->validate(x),"unknown selected stop");}
        c=strict(false,"福州","厦门");c.put("fastest_claim",true);Map<String,Object> fastest=c;bad(()->validate(fastest),"not global fastest");
        c=strict(false,"福州","厦门");c.put("source_published_on","2026-01-12");map(c.get("schedule_effective_notice")).put("source_published_on","2026-01-12");c.put("effective_from","2026-01-26");original(map(c.get("schedule_effective_notice")),"自1月26日零时起，全国铁路将实行新一季度列车运行图。");ok(validate(c).lowerMinutes==100,"announcement predates already effective regular diagram");
        for(String notice:List.of("自10月26日零时起，全国铁路将实行新一季度列车运行图。","预计自8月21日零时起，全国铁路将实行新一季度列车运行图。","自8月21日零时起，全国铁路将实行临时列车运行图。","自8月21日零时起，全国铁路将实行新一季度列车运行图，现已停运。")){Map<String,Object> x=strict(false,"福州","厦门");original(map(x.get("schedule_effective_notice")),notice);bad(()->validate(x),"future/temporary/stopped notice");}
    }
    static void planning(){
        ok(planning(fact(),source())==50,"planning loader shares same-service stop grammar");
        Map<String,Object> f=fact();map(f.get("canonical")).put("departure_clock","08:45");map(f.get("canonical")).put("calculated_minutes",55);map(f.get("spans")).put("departure",List.of(span("O",OUT,"08:45",0)));Map<String,Object> arriveAsDepart=f;bad(()->planning(arriveAsDepart,source()),"arrival used for departure");
        for(String key:List.of("departure_clock","arrival_clock","calculated_minutes","service_id","arrival_day_offset")){Map<String,Object> x=fact();map(x.get("canonical")).put(key,key.equals("departure_clock")?"08:51":key.equals("arrival_clock")?"09:41":key.equals("service_id")?"G813":key.equals("arrival_day_offset")?1:49);bad(()->planning(x,source()),"canonical mismatch "+key);}
        for(String role:List.of("from","to","departure","arrival","service")){Map<String,Object> x=fact();Map<String,Object> s=map(((List<?>)map(x.get("spans")).get(role)).get(0));s.put("unit_id","B");bad(()->planning(x,source()),"different direction unit "+role);}
        Map<String,Object> x=fact();map(x.get("spans")).put("service",List.of(span("B",BACK,"G813",0)));map(x.get("canonical")).put("service_id","G813");Map<String,Object> mixed=x;bad(()->planning(mixed,source()),"mixed service clocks across source units");
        x=fact();map(x.get("spans")).put("service",List.of(span("O",OUT,"G812",0),span("O",OUT,"G812",0)));Map<String,Object> repeated=x;bad(()->planning(repeated,source()),"ambiguous multiple service bindings");
        x=fact();Map<String,Object> s=copy(source());map(((List<?>)s.get("units")).get(0)).put("text",OUT.replace("08:50","08:49"));Map<String,Object> unchangedHash=x;bad(()->planning(unchangedHash,s),"changed raw source with old hash");
    }
    public static void main(String[] args){syntax();planning();System.out.println("PublicStopChainTest: "+checks+" checks passed (synthetic only)");}
}
