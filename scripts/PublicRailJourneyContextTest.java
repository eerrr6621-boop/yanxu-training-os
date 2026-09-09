package com.training;

import java.time.*;
import java.util.*;

/** Synthetic variants only; actual source pages are tested in private artifacts. */
public final class PublicRailJourneyContextTest {
    static int checks;
    static final LocalDate TODAY=LocalDate.of(2026,9,8);
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
    static Map<String,Object> m(Object...kv){return PublicCityBoundsTest.m(kv);}
    static Map<String,Object> map(Object v){return CityTravelReferenceTest.map(v);}
    static Map<String,Object> mutableInput(Map<String,Object> bounds){Map<String,Object> input=new LinkedHashMap<>(map(bounds.get("calculation_input")));bounds.put("calculation_input",input);return input;}
    static void original(Map<String,Object> c,String s){c.put("original_content",s);c.put("assertion_start",0);c.put("assertion_end",s.endsWith("。")?s.length()-1:s.length());}
    static void city(Map<String,Object> c,String from,String to){c.put("from_city",from);c.put("to_city",to);c.put("from_endpoint",from);c.put("to_endpoint",to);}
    static void bind(Map<String,Object> c){c.put("context_binding",PublicCityContext.describe(c));}
    static PublicCityBounds.Duration ok(Map<String,Object> c,String why){bind(c);PublicCityBounds.Duration d=PublicCityBounds.validateEvidence(c,TODAY);check(d!=null,why);return d;}
    static void bad(Map<String,Object> c,boolean rebind,String why){try{if(rebind)bind(c);PublicCityBounds.validateEvidence(c,TODAY);throw new AssertionError("accepted: "+why);}catch(IllegalArgumentException|DateTimeException expected){checks++;}}
    static String out="福州→厦门（经停泉州） 新增动车\n\nC851次：福州6:05→泉州6:35/40→厦门7:20\n\n填补福州、泉州至厦门早班通勤空白";
    static String back="厦门→福州 新增动车\n\nC852次：\n\n厦门20:15→泉州20:55/57→福州21:40\n\n补强晚间通勤时段运能";
    static Map<String,Object> chain(boolean reverse,String from,String to){
        Map<String,Object> c=PublicCityBoundsTest.fixture(false,"1分钟");c.remove("duration_text");c.put("context_binding_type",PublicCityContext.SCHEDULE_CHAIN);
        city(c,from,to);c.put("selected_from_station",from);c.put("selected_to_station",to);c.put("selected_service_id",reverse?"C852":"C851");
        c.put("service_state","published_schedule_baseline");c.put("schedule_basis","regular_published_running_diagram");c.put("effective_from","2026-08-21");
        Map<String,Object> notice=m("source_url",c.get("source_url"),"source_published_on",c.get("source_published_on"),"source_access","public_page");
        original(notice,"新图自8月21日零时起执行，具体车次时刻、票务信息请以12306官网、App查询为准。");c.put("schedule_effective_notice",notice);original(c,reverse?back:out);return c;
    }
    static Map<String,Object> relative(){Map<String,Object> c=PublicCityBoundsTest.fixture(false,"73分钟");c.put("context_binding_type",PublicCityContext.DATED_ROUTE);
        original(c,"上月26日，沿江高铁福州至厦门段正式开通运营，福州至厦门最快73分钟可达，较以往压缩18分钟。");return c;}
    static Map<String,Object> resident(){Map<String,Object> c=PublicCityBoundsTest.fixture(true,"81分钟");c.put("context_binding_type",PublicCityContext.REPORTED_JOURNEY);c.put("journey_observation_basis","reported_actual_trip");
        original(c,"“81分钟，厦门直达福州，对于我们双城生活的人来说，确实方便！”8月19日，厦门市民林某从厦门北站乘坐高铁，到福州上班。");return c;}
    static void clocks(){
        Map<String,Object> c=chain(false,"福州","泉州");PublicCityBounds.Duration d=ok(c,"initial to intermediate");check(d.lowerMinutes==30,"arrival not intermediate departure selected");
        Map<String,Object> dm=d.toMap();check(dm.get("reported_minutes")==null&&dm.get("calculated_minutes").equals(30),"calculated not source-reported minute");check(Boolean.FALSE.equals(dm.get("published_exact_upper")),"derived cap not published exact cap");
        check(dm.get("original_text").equals(out)&&dm.get("display").equals("30分钟（公开时刻差）"),"source original preserved and calculated display labeled");
        c=chain(false,"泉州","厦门");d=ok(c,"intermediate to final");check(d.lowerMinutes==40,"departure minute shorthand used not arrival");
        List<?> stops=(List<?>)map(c.get("context_binding")).get("all_stop_items");check(map(stops.get(1)).get("departure_clock").equals("6:40"),"same stop hour shorthand expanded visibly");
        check(map(map(stops.get(1)).get("departure_source_span")).get("text").equals("40"),"source span is raw 40 not fabricated 6:40");
        check(!map(stops.get(0)).containsKey("arrival_minute")&&!map(stops.get(2)).containsKey("departure_minute"),"initial/final roles not fabricated");
        d=ok(chain(false,"福州","厦门"),"full forward");check(d.lowerMinutes==75,"full forward 75");
        d=ok(chain(true,"厦门","福州"),"full reverse");check(d.lowerMinutes==85,"independent reverse85");
        c=chain(false,"泉州","厦门");original(c,out.replace("6:35/40","6:35/6:40"));check(ok(c,"full intermediate departure clock").lowerMinutes==40,"full departure same result");
        for(String replacement:List.of("6:35/20","6:35/99","24:35/40","6:35","6:35/次日6:40","6:35/07:40/45","6:35/40（换乘）")){
            c=chain(false,"福州","厦门");original(c,out.replace("6:35/40",replacement));bad(c,true,"bad intermediate "+replacement);
        }
        for(String value:List.of(out.replace("C851","C999"),out.replace("厦门7:20","厦门6:30"),out.replace("福州6:05","福州6:99"),out.replace("福州6:05","福州6:\n05"),out.replace("泉州6:35","泉\n州6:35"),out.replace("→泉州6:35/40","→泉州6:35/40→泉州6:50/55"),out.replace("经停泉州","经停漳州"),out.replace("填补福州、泉州至厦门","填补福州、漳州至厦门"),out.replace("新增动车","新增汽车"),out+"\n临时运行",out.replace("福州→厦门","厦门→福州"))){c=chain(false,"福州","厦门");original(c,value);bad(c,true,"complete chain mutation");}
        c=chain(false,"厦门","福州");bad(c,true,"cannot reverse one service");
        c=chain(false,"福州","厦门");c.put("selected_from_station","福州南");bad(c,true,"selected station not in row");
        c=chain(false,"福州","厦门");c.put("duration_text","75分钟");bad(c,true,"cannot invent a quoted duration");
        c=chain(false,"福州","厦门");c.put("fastest_claim",true);bad(c,true,"service clock not city fastest");
        c=chain(false,"福州","厦门");bind(c);map(c.get("context_binding")).put("clock_duration_minutes",1);bad(c,false,"rehashed derived result cannot lie");
        for(String word:List.of("临时","春运","暑运","停运","取消","预计","换乘","次日","不再","仅限")){c=chain(false,"福州","厦门");original(c,word+out);bad(c,true,"unsafe chain "+word);}
    }
    static void dates(){
        for(String key:List.of("schedule_effective_notice","schedule_basis","effective_from")){Map<String,Object> c=chain(false,"福州","厦门");c.remove(key);bad(c,true,"missing diagram field "+key);}
        for(String date:List.of("2026-08-19","2026-08-22","2026-10-21")){Map<String,Object> c=chain(false,"福州","厦门");c.put("effective_from",date);bad(c,true,"wrong effective date "+date);}
        for(String change:List.of("计划","临时","预计","暂停")){Map<String,Object> c=chain(false,"福州","厦门");Map<String,Object> n=map(c.get("schedule_effective_notice"));original(n,change+n.get("original_content"));bad(c,true,"notice not affirmative");}
        for(String key:List.of("source_url","source_published_on","source_access")){Map<String,Object> c=chain(false,"福州","厦门");map(c.get("schedule_effective_notice")).put(key,"wrong");bad(c,true,"notice identity mismatch "+key);}
        Map<String,Object> c=chain(false,"福州","厦门");map(c.get("schedule_effective_notice")).put("source_status","revoked");bad(c,true,"withdrawn schedule notice");
        c=chain(false,"福州","厦门");c.put("service_state","operating");bad(c,true,"baseline cannot masquerade as observed operation");
        c=relative();check(ok(c,"relative opening minute statement").lowerMinutes==73,"reduction18 not selected as duration");check(map(c.get("context_binding")).get("resolved_opening_date").equals("2026-07-26"),"relative month resolved");
        c=relative();c.put("source_published_on","2026-01-25");ok(c,"relative month crosses year");check(map(c.get("context_binding")).get("resolved_opening_date").equals("2025-12-26"),"year-crossing actual date");
        for(String value:List.of("上月32日","下月26日","上月0日")){c=relative();original(c,((String)c.get("original_content")).replace("上月26日",value));bad(c,true,"relative date mutation");}
        c=relative();c.put("duration_text","18分钟");bad(c,true,"reduction never selected");
        c=relative();original(c,((String)c.get("original_content")).replace("段正式开通运营","段计划开通运营"));bad(c,true,"future opening not current");
        c=relative();city(c,"厦门","福州");bad(c,true,"relative opening cannot mirror");
    }
    static void residents(){
        Map<String,Object> c=resident();check(ok(c,"actual trip testimony").lowerMinutes==81,"trip minutes retained");check(Boolean.TRUE.equals(map(c.get("context_binding")).get("not_operator_or_fastest_claim")),"scope remains actual individual trip");
        check(map(c.get("context_binding")).get("reported_journey_on").equals("2026-08-19"),"reported past trip date");
        for(String key:List.of("journey_observation_basis","minimum_basis","fastest_claim")){c=resident();if(key.equals("journey_observation_basis"))c.remove(key);else c.put(key,key.equals("fastest_claim")?true:"explicit_citywide_fastest");bad(c,true,"scope misrepresentation "+key);}
        for(String[] change:List.of(new String[]{"8月19日","8月21日"},new String[]{"8月19日","13月19日"},new String[]{"厦门北站","泉州站"},new String[]{"到福州上班","到泉州上班"},new String[]{"乘坐高铁","乘坐飞机"},new String[]{"乘坐高铁","准备乘坐高铁"},new String[]{"81分钟","约81分钟"},new String[]{"确实方便","不是确实方便"})) {c=resident();original(c,((String)c.get("original_content")).replace(change[0],change[1]));bad(c,true,"trip original mutation");}
        c=resident();bind(c);c.put("source_published_on","2025-08-20");bad(c,false,"binding date cannot change");
        c=resident();bind(c);map(c.get("context_binding")).put("reported_journey_on","2026-08-18");bad(c,false,"trip date hash-bound");
        Map<String,Object> a=relative(),b=resident();bind(a);bind(b);check("rail".equals(PublicCityBounds.validatePair(a,b,TODAY,240).get("eligibility")),"different independent source scopes pair without fastest claims");
    }
    static Map<String,Object> chained(){
        Map<String,Object> data=PublicCityBoundsIntegrationTest.fixture("75分钟","85分钟"),ref=PublicCityBoundsIntegrationTest.ref(data);
        ref.putAll(m("from_province","福建","from_city","福州","to_province","福建","to_city","厦门","research_on",TODAY.toString()));
        for(int i=0;i<2;i++){Map<String,Object> content=PublicCityBoundsIntegrationTest.content(data,i);content.clear();content.putAll(chain(i==1,i==0?"福州":"厦门",i==0?"厦门":"福州"));bind(content);
            map(ref.get(i==0?"outbound":"return")).put("duration_bounds",PublicCityBounds.validateEvidence(content,TODAY).toMap());CityTravelReferenceTest.rehashEvidence(data,i);}
        CityTravelReferenceTest.reseal(data);return data;
    }
    static Map<String,Object> lookup(Map<?,?> data){return CityTravelReference.lookup(data,"福建","福州","福建","厦门",TODAY);}
    static void integration(){
        Map<String,Object> data=chained(),route=lookup(data);check("rail".equals(route.get("eligibility")),"new clock chain uses existing evidence hash chain: "+Json.write(route));
        check(map(route.get("outbound_duration")).get("reported_minutes")==null&&map(route.get("outbound_duration")).get("calculated_minutes").equals(75),"loaded derived duration honest");
        DispatchPreference pref=DispatchPreference.from(m("training_province","福建","training_city","厦门","training_mode","线下"),null);
        check("rail".equals(TravelMatrix.resolve(List.of(),Map.of(),Map.of(),data,m("base_province","福建","base_city","福州"),pref,TODAY).get("eligibility")),"same resolver accepts clock-based city reference");
        data=chained();PublicCityBoundsIntegrationTest.content(data,0).put("original_content",out.replace("7:20","7:10"));check(!"rail".equals(lookup(data).get("eligibility")),"original content hash change rejected");
        data=chained();map(PublicCityBoundsIntegrationTest.content(data,0).get("context_binding")).put("clock_duration_minutes",5);CityTravelReferenceTest.rehashEvidence(data,0);check(!"rail".equals(lookup(data).get("eligibility")),"even rehashed derived-time lie rejected");
        data=chained();PublicCityBoundsIntegrationTest.ref(data).remove("return");CityTravelReferenceTest.reseal(data);check(!"rail".equals(lookup(data).get("eligibility")),"missing reverse not accepted");
        data=chained();Map<String,Object> ref=PublicCityBoundsIntegrationTest.ref(data);ref.put("return",ref.get("outbound"));CityTravelReferenceTest.reseal(data);check(!"rail".equals(lookup(data).get("eligibility")),"one train cannot fabricate reverse");
        data=chained();map(map(PublicCityBoundsIntegrationTest.ref(data).get("outbound")).get("duration_bounds")).put("reported_minutes",75);CityTravelReferenceTest.reseal(data);check(!"rail".equals(lookup(data).get("eligibility")),"computed value cannot be relabeled published");
        data=chained();map(PublicCityBoundsIntegrationTest.content(data,0).get("schedule_effective_notice")).put("source_status","withdrawn");CityTravelReferenceTest.rehashEvidence(data,0);check(!"rail".equals(lookup(data).get("eligibility")),"withdrawn diagram revokes reference eligibility");
        for(String field:List.of("calculated_minutes","lower_minutes","upper_minutes")){
            data=chained();Map<String,Object> bound=map(map(PublicCityBoundsIntegrationTest.ref(data).get("outbound")).get("duration_bounds"));bound.put(field,74);CityTravelReferenceTest.reseal(data);check(!"rail".equals(lookup(data).get("eligibility")),"one-minute stored discrepancy "+field);
        }
        for(String field:List.of("selected_from_station","selected_to_station","from_city","to_city")){
            data=chained();Map<String,Object> bound=map(map(PublicCityBoundsIntegrationTest.ref(data).get("outbound")).get("duration_bounds"));mutableInput(bound).put(field,"泉州");CityTravelReferenceTest.reseal(data);check(!"rail".equals(lookup(data).get("eligibility")),"selected segment output tamper "+field);
        }
        Map<String,Object> content=chain(false,"泉州","厦门");bind(content);Map<String,Object> bounds=PublicCityBounds.validateEvidence(content,TODAY).toMap();
        check(PublicCityBounds.fromStored(bounds).lowerMinutes==40,"stored reconstruction takes departure not arrival");
        Map<String,Object> leg=m("duration_bounds",bounds);bounds.put("lower_minutes",45);bounds.put("upper_minutes",45);bounds.put("calculated_minutes",45);
        try{CityTravelReference.legDuration(leg);throw new AssertionError("accepted arrival-as-departure output");}catch(IllegalArgumentException expected){checks++;}
        content=chain(false,"福州","厦门");bind(content);bounds=PublicCityBounds.validateEvidence(content,TODAY).toMap();mutableInput(bounds).put("original_content",out.replace("厦门7:20","厦门0:20"));
        try{CityTravelReference.legDuration(m("duration_bounds",bounds));throw new AssertionError("accepted overnight stored original");}catch(IllegalArgumentException expected){checks++;}
        content=chain(false,"福州","厦门");bind(content);bounds=PublicCityBounds.validateEvidence(content,TODAY).toMap();bounds.remove("calculation_input");
        try{CityTravelReference.legDuration(m("duration_bounds",bounds));throw new AssertionError("accepted missing calculation input");}catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args){clocks();dates();residents();integration();System.out.println("PublicRailJourneyContextTest: "+checks+" checks passed (synthetic only)");}
}
