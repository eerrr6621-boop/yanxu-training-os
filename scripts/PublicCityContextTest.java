package com.training;

import java.time.*;
import java.util.*;

/** Pure synthetic source binding and original-chain regression; no actual library. */
public final class PublicCityContextTest {
    static int checks;
    static final LocalDate TODAY=LocalDate.of(2026,9,8);
    static void check(boolean b,String name){if(!b)throw new AssertionError(name);checks++;}
    static Map<String,Object> map(Object v){return CityTravelReferenceTest.map(v);}
    static Map<String,Object> copy(Map<String,Object> v){return CityTravelReferenceTest.copy(v);}
    static void original(Map<String,Object> c,String s){c.put("original_content",s);c.put("assertion_start",0);c.put("assertion_end",s.endsWith("。")?s.length()-1:s.length());}
    static void bind(Map<String,Object> c){c.put("context_binding",PublicCityContext.describe(c));}
    static Map<String,Object> parallel(String duration){
        Map<String,Object> c=PublicCityBoundsTest.fixture(false,duration);c.put("context_binding_type",PublicCityContext.PARALLEL);
        original(c,"目前福州至厦门"+duration+"、至泉州最快55分钟的快速通达格局已常态化。");return c;
    }
    static Map<String,Object> lines(String duration){
        Map<String,Object> c=PublicCityBoundsTest.fixture(true,duration);c.put("context_binding_type",PublicCityContext.STATION_LINES);
        original(c,"厦门出发\n\n厦门北站\n\n全天开往福州的高铁\n\n超过百趟\n\n"+duration+"\n\n即可直达福州站");return c;
    }
    static Map<String,Object> dated(String duration){
        Map<String,Object> c=PublicCityBoundsTest.fixture(false,duration);c.put("context_binding_type",PublicCityContext.DATED_ROUTE);
        original(c,"今年6月，示范高铁开通，从福州到厦门的高铁最短用时缩短到"+duration+"。");return c;
    }
    static PublicCityBounds.Duration ok(Map<String,Object> c,String name){bind(c);PublicCityBounds.Duration d=PublicCityBounds.validateEvidence(c,TODAY);check(d!=null,name);return d;}
    static void bad(Map<String,Object> c,boolean rebind,String name){
        try{if(rebind)bind(c);PublicCityBounds.validateEvidence(c,TODAY);throw new AssertionError("accepted: "+name);}
        catch(IllegalArgumentException expected){checks++;}
    }
    static void verifySpans(Object obj,String s){
        if(obj instanceof Map<?,?> row){
            if(row.containsKey("start")&&row.containsKey("end")&&row.containsKey("text")){
                int a=((Number)row.get("start")).intValue(),b=((Number)row.get("end")).intValue();check(s.substring(a,b).equals(row.get("text")),"literal source span");
            }
            row.values().forEach(v->verifySpans(v,s));
        }else if(obj instanceof List<?> list)list.forEach(v->verifySpans(v,s));
    }
    static void grammar(){
        Map<String,Object> p=parallel("最快62分钟");check(ok(p,"parallel explicit minutes").lowerMinutes==62,"62 not neighboring 55");
        verifySpans(p.get("context_binding"),(String)p.get("original_content"));
        check(((List<?>)map(p.get("context_binding")).get("all_destination_items")).size()==2,"all destination items retained");
        p=parallel("最快62分钟");p.put("to_city","泉州");p.put("to_endpoint","泉州");p.put("duration_text","最快55分钟");
        check(ok(p,"select second item").lowerMinutes==55,"second target time");
        p=parallel("不足4小时");check(ok(p,"explicit bound remains typed").strictlyUnder(240),"open bound passes");
        p=parallel("约1小时");check(!ok(p,"approximate is retained").strictlyUnder(240),"approximate is not a bound");
        p=parallel("1小时");bind(p);check(map(p.get("context_binding")).containsKey("duration_span"),"hour narrative remains source-bindable");bad(p,false,"bare hour narrative cannot become precise 60");
        p=parallel("4小时以内");check(!ok(p,"inclusive bound retained").strictlyUnder(240),"240 inclusive pending");
        p=parallel("239分钟");check(ok(p,"strict 239").strictlyUnder(240),"239 passes");
        p=parallel("240分钟");check(!ok(p,"strict 240").strictlyUnder(240),"240 pending");
        Map<String,Object> b=lines("最快1小时12分钟");check(ok(b,"heading and station lines").lowerMinutes==72,"72 independent reverse");verifySpans(b.get("context_binding"),(String)b.get("original_content"));
        check(map(map(b.get("context_binding")).get("from_binding")).get("kind").equals("station"),"station precision retained in evidence");
        check(b.get("from_endpoint").equals("厦门")&&!b.containsKey("from_station"),"city scope not mislabeled station flat minutes");
        original(b,((String)b.get("original_content")).replace("\n\n","\r\n"));ok(b,"CRLF positions");verifySpans(b.get("context_binding"),(String)b.get("original_content"));
        p=parallel("最快62分钟");original(p,"据了解，沿线高铁已实现稳定运营，目前福州至厦门最快62分钟、至泉州最快55分钟的快速通达格局已常态化。");ok(p,"bounded operating background");
        p=parallel("最快62分钟");original(p,"线路于2009年建成，已实现稳定运营，目前福州至厦门最快62分钟、至泉州最快55分钟的快速通达格局已常态化。");ok(p,"old construction year is not expiry");
        p=dated("2小时41分");check(ok(p,"dated opening with current route").lowerMinutes==161,"dated route keeps precise number");
        verifySpans(p.get("context_binding"),(String)p.get("original_content"));
        check(map(p.get("context_binding")).get("opening_date_precision").equals("month"),"month not invented as exact day");
        p=dated("2小时53分");original(p,"6月30日，示范高铁正式开通运营，福州至厦门最快旅行时间压缩至2小时53分，进一步畅通西北地区通往华中、华南的快速通道。");
        check(ok(p,"full dated narrative with qualitative corridor").lowerMinutes==173,"second current narrative not mirrored");verifySpans(p.get("context_binding"),(String)p.get("original_content"));
        check(map(p.get("context_binding")).get("opening_date_precision").equals("day"),"explicit day retained");
    }
    static void guards(){
        String good=(String)parallel("最快62分钟").get("original_content");
        for(String s:List.of(
            good.replace("目前","预计"),good.replace("目前","目前计划"),good.replace("福州至厦门","福州并非至厦门"),
            good.replace("目前","以前"),good+"该方向已停运。",good.replace("、至泉州","。\n至泉州"),
            good.replace("、至泉州","\n至泉州"),good.replace("、至泉州","；至泉州"),good.replace("至厦门最快62分钟","至厦门"),
            good.replace("至泉州最快55分钟","至厦门最快55分钟"),good.replace("至泉州","至虚构城市"),
            good.replace("最快62分钟","最快62分钟、最快40分钟"),good.replace("目前","目前厦门至福州最快90分钟，"),
            good.replace("目前","据说"),"引文：“"+good+"”",good.replace("目前","目前春运"),good.replace("目前","目前节假日"),
            good.replace("目前","目前驾车"),good.replace("目前","目前需换乘"),good.replace("目前","铁路已经提供1小时服务，已实现稳定运营，目前"),
            good.replace("已常态化","尚未实现"),good.replace("已常态化","已常态化，但该路段不通"))){
            Map<String,Object> p=parallel("最快62分钟");original(p,s);bad(p,true,"parallel unsafe or ambiguous");
        }
        String source=(String)lines("最快1小时12分钟").get("original_content");
        for(String s:List.of(source.replace("厦门出发","泉州出发"),source.replace("厦门北站","厦门北总站"),source.replace("厦门北站","福州站"),
            source.replace("开往福州","开往泉州"),source.replace("直达福州站","直达泉州站"),source.replace("直达","换乘到达"),
            source.replace("超过百趟","尚未开通"),source+"\n福州出发\n福州站",source+"\n仅限周末运行",source.replace("全天","计划全天"),
            source.replace("最快1小时12分钟","另一方向最快1小时12分钟"),source.replace("1小时12分钟","1小时\n12分钟"),
            source.replace("厦门北站","厦\n门北站"),source.replace("高铁","驾车"),source.replace("最快","往返最快"),
            source.replace("最快","预计最快"),source.replace("\n\n超过百趟","\n\n泉州出发\n\n超过百趟"))){
            Map<String,Object> b=lines("最快1小时12分钟");original(b,s);bad(b,true,"line product unsafe or wrong station");
        }
        Map<String,Object> b=lines("最快1小时12分钟");bind(b);map(map(b.get("context_binding")).get("duration_span")).put("start",0);bad(b,false,"duration offset tampered");
        b=lines("最快1小时12分钟");bind(b);map(map(b.get("context_binding")).get("from_binding")).put("city","福州");bad(b,false,"station city mapping tampered");
        b=lines("最快1小时12分钟");bind(b);map(b.get("context_binding")).put("context_canonical_sha256","0".repeat(64));bad(b,false,"context hash tampered");
        b=lines("最快1小时12分钟");bind(b);map(b.get("context_binding")).put("offset_unit","codepoints");bad(b,false,"wrong offset unit");
        b=lines("最快1小时12分钟");bind(b);b.put("assertion_start",4);bad(b,false,"drop heading via assertion offsets");
        b=lines("最快1小时12分钟");b.put("duration_text","最快12分钟");bad(b,true,"declared duration differs from source");
        for(Map<String,?> change:List.of(Map.of("source_published_on","2025-09-07"),Map.of("effective_until","2026-09-07"),
            Map.of("effective_from","2026-09-09"),Map.of("research_on","2026-08-01"),Map.of("city_mapping_checked",false),
            Map.of("source_status","revoked"),Map.of("research_only",true),Map.of("usage_authorized",true),Map.of("temporary_service",true))){
            b=lines("最快1小时12分钟");b.putAll(change);bad(b,true,"old date/metadata guard retained");
        }
        b=lines("最快1小时12分钟");b.put("source_published_on","2025-09-08");ok(b,"365 day exact boundary remains policy");
        for(String prefix:List.of("2027年已实现稳定运营，","截至2025年已实现稳定运营，","旧图已实现稳定运营，","仅于2026年8月已实现稳定运营，")){
            Map<String,Object> p=parallel("最快62分钟");original(p,prefix+good);bad(p,true,"source narrative date not current");
        }
        String datedGood=(String)dated("2小时41分").get("original_content");
        for(String s:List.of(datedGood.replace("6月","12月"),datedGood.replace("今年6月","2027年6月"),
            datedGood.replace("今年6月","6月31日"),datedGood.replace("今年6月","今年13月"),
            datedGood.replace("高铁开通","高铁将开通"),datedGood.replace("高铁开通","高铁未开通"),
            datedGood.replace("高铁开通","高铁预计开通"),datedGood.replace("从福州到厦门","从厦门到福州"),
            datedGood.replace("到厦门","到泉州"),datedGood.replace("最短用时","往返最短用时"),
            datedGood.replace("缩短到","有望缩短到"),datedGood+"该路线已经停运。",datedGood.replace("，从","。从"),
            datedGood.replace("示范高铁开通","示范高铁开通，厦门至福州62分钟"))){
            Map<String,Object> d=dated("2小时41分");original(d,s);
            try{bind(d);PublicCityBounds.validateEvidence(d,TODAY);throw new AssertionError("accepted dated mutation");}
            catch(IllegalArgumentException|DateTimeException expected){checks++;}
        }
        Map<String,Object> d=dated("2小时41分");bind(d);map(map(d.get("context_binding")).get("opening_statement_span")).put("text","已经开通");bad(d,false,"opening statement cannot be rewritten");
    }
    static Map<String,Object> chained(){
        Map<String,Object> d=PublicCityBoundsIntegrationTest.fixture("75分钟","最快82分钟");
        for(int i=0;i<2;i++){
            Map<String,Object> c=PublicCityBoundsIntegrationTest.content(d,i);
            c.put("context_binding_type",i==0?PublicCityContext.PARALLEL:PublicCityContext.STATION_LINES);
            original(c,i==0?"目前南京至合肥75分钟、至杭州90分钟的快速通达格局已常态化。":"合肥出发\n合肥南站\n全天开往南京的高铁\n超过百趟\n最快82分钟\n即可直达南京站");
            bind(c);CityTravelReferenceTest.rehashEvidence(d,i);
        }
        CityTravelReferenceTest.reseal(d);return d;
    }
    static void chain(){
        Map<String,Object> d=chained(),r=PublicCityBoundsIntegrationTest.lookup(d);
        check("rail".equals(r.get("eligibility")),"same original public chain qualifies independent contexts");
        check("rail".equals(PublicCityBoundsIntegrationTest.resolve(Map.of(),d).get("eligibility")),"cross-library resolver sees same typed evidence");
        Map<String,Object> back=CityTravelReference.lookup(d,"安徽","合肥","江苏","南京",TODAY);
        check(map(back.get("outbound_duration")).get("reported_minutes").equals(82),"reverse swaps whole source");
        check(map(back.get("return_duration")).get("reported_minutes").equals(75),"outward never mirrored");
        check(!r.containsKey("outbound_reference_minutes")&&Boolean.FALSE.equals(r.get("time_score_applicable")),"no fake distance score or flat minutes");
        d=chained();PublicCityBoundsIntegrationTest.content(d,0).put("original_content","伪造原文");check(!PublicCityBoundsIntegrationTest.rail(PublicCityBoundsIntegrationTest.lookup(d)),"original changed without rehash rejected");
        d=chained();Map<String,Object> c=PublicCityBoundsIntegrationTest.content(d,0);map(map(c.get("context_binding")).get("duration_span")).put("text","5分钟");CityTravelReferenceTest.rehashEvidence(d,0);
        check(!PublicCityBoundsIntegrationTest.rail(PublicCityBoundsIntegrationTest.lookup(d)),"even rehashed binding lie rejected");
        d=chained();PublicCityBoundsIntegrationTest.ref(d).remove("return");PublicCityBoundsIntegrationTest.seal(d);check(!PublicCityBoundsIntegrationTest.rail(PublicCityBoundsIntegrationTest.lookup(d)),"single direction stays pending");
        d=chained();PublicCityBoundsIntegrationTest.ref(d).put("return",PublicCityBoundsIntegrationTest.ref(d).get("outbound"));PublicCityBoundsIntegrationTest.seal(d);check(!PublicCityBoundsIntegrationTest.rail(PublicCityBoundsIntegrationTest.lookup(d)),"shared evidence cannot fabricate reverse");
    }
    static Map<String,Object> stationRoute(boolean reverse){
        Map<String,Object> c=PublicCityBoundsIntegrationTest.content(PublicCityBoundsIntegrationTest.fixture("1小时15分","1小时22分"),reverse?1:0);
        c.put("context_binding_type",PublicCityContext.STATION_ROUTES);
        c.put("selected_from_station",reverse?"合肥南":"南京南站");c.put("selected_to_station",reverse?"南京南":"合肥南站");
        original(c,reverse?"优化华东地区列车运行图：利用新开通的合肥至南京高铁、杭州至南京高铁，分别安排开行合肥南至南京南、杭州东、福州的动车组列车100列、10列、10列，开行杭州东至南京南、福州南至厦门北的动车组列车6列、12列，杭州东至南京南最快58分钟可达，合肥南至南京南最快1小时22分可达，进一步密切沿线地区与南京地区的联系。":
            "6月30日10时许，随着G111次“复兴号”列车驶出南京南站，南京至合肥高铁开通运营，南京南站至合肥南站最快1小时15分可达，区域城市群时空距离大幅压缩。");return c;
    }
    static Map<String,Object> stationMapping(){
        Map<String,Object> mapping=new LinkedHashMap<>(Map.of("station_label","香港示范站","province","香港","city","香港","mapping_checked",true,
            "source_access","public_page","publisher_kind","government","source_url","https://transport.example.test/synthetic-station","source_provider","虚构测试交通部门",
            "checked_on",TODAY.toString(),"source_date_status","not_shown"));
        original(mapping,"香港示范站是高速铁路(香港段)(\"高铁\")的总站。");return mapping;
    }
    static Map<String,Object> serviceTrip(boolean reverse){
        Map<String,Object> c=PublicCityBoundsTest.fixture(false,reverse?"1小时8分钟":"1小时5分");
        c.put("from_province",reverse?"广东":"香港");c.put("from_city",reverse?"广州":"香港");c.put("from_endpoint",c.get("from_city"));
        c.put("to_province",reverse?"香港":"广东");c.put("to_city",reverse?"香港":"广州");c.put("to_endpoint",c.get("to_city"));
        c.put("context_binding_type",PublicCityContext.SERVICE_ROUNDTRIP);c.put("selected_service_id",reverse?"G903":"G6080");
        c.put("selected_from_station",reverse?"广州北站":"香港示范");c.put("selected_to_station",reverse?"香港示范":"广州北站");
        c.put("station_city_mappings",List.of(stationMapping()));
        original(c,"本次开通的直达班次形成稳定往返通行能力：来程G6080次8:00香港示范始发，9:05抵达广州北站，全程仅需1小时5分；返程G6079次20：05广州北站发车，21：17分抵达香港示范，另一趟是G903次20:49广州北站发车，21:57抵达香港示范，返程时间最短为1小时8分钟，真正实现香港与花都“一小时生活圈”。");return c;
    }
    static void stationGrammar(){
        Map<String,Object> a=stationRoute(false),b=stationRoute(true);
        check(ok(a,"complete opening station paragraph").lowerMinutes==75,"station forward time");verifySpans(a.get("context_binding"),(String)a.get("original_content"));
        check(ok(b,"complete multiple route diagram").lowerMinutes==82,"target time not neighbor58 or count100");verifySpans(b.get("context_binding"),(String)b.get("original_content"));
        check(((List<?>)map(b.get("context_binding")).get("all_route_items")).size()==2,"all time routes preserved");
        a=serviceTrip(false);b=serviceTrip(true);
        check(ok(a,"roundtrip outward independent service").lowerMinutes==65,"outward65");verifySpans(a.get("context_binding"),(String)a.get("original_content"));
        check(ok(b,"roundtrip independently fastest returning service").lowerMinutes==68,"return68 not mirrored65 or other72");verifySpans(b.get("context_binding"),(String)b.get("original_content"));
        List<?> services=(List<?>)map(b.get("context_binding")).get("all_service_items");
        check(services.size()==3&&map(services.get(1)).get("clock_duration_minutes").equals(72),"nonselected return72 retained separately");
        check(map(map(a.get("context_binding")).get("from_binding")).get("mapping_basis").equals("public_station_city_segment_statement"),"special region explicit mapping evidence");
        check(map(map(a.get("context_binding")).get("to_binding")).get("mapping_basis").equals("literal_city_prefix_in_station_role"),"simple station literal city prefix");
        Map<String,Object> pair=PublicCityBounds.validatePair(a,b,TODAY,240);check("rail".equals(pair.get("eligibility")),"multi-service remains independent directions");
        for(String role:List.of("from_binding","to_binding"))check(map(map(a.get("context_binding")).get(role)).get("kind").equals("station"),"not citywide station guarantee");
    }
    static void stationGuards(){
        String forward=(String)stationRoute(false).get("original_content"),back=(String)stationRoute(true).get("original_content");
        for(String s:List.of(forward.replace("6月30日","12月30日"),forward.replace("6月30日","6月31日"),forward.replace("10时许","25时许"),
            forward.replace("南京至合肥高铁","合肥至南京高铁"),forward.replace("开通运营","将开通运营"),forward.replace("开通运营","未开通运营"),
            forward.replace("驶出南京南站","驶出杭州东站"),forward.replace("1小时15分","预计1小时15分"),forward+"该线路已经停运。",
            forward.replace("，南京南站至","。南京南站至"),forward.replace("南京南站至合肥南站","南京南站至合肥南站往返"))){
            Map<String,Object> c=stationRoute(false);original(c,s);try{bind(c);PublicCityBounds.validateEvidence(c,TODAY);throw new AssertionError("accepted opening mutation");}catch(IllegalArgumentException|DateTimeException expected){checks++;}
        }
        for(String s:List.of(back.replace("合肥南至南京南最快1小时22分可达","南京南至合肥南最快1小时22分可达"),
            back.replace("1小时22分","100列"),back.replace("1小时22分","约1小时22分"),back.replace("最快1小时22分","往返最快1小时22分"),
            back.replace("利用新开通","预计利用新开通"),back+"该路线仅限节假日。",back.replace("，进一步密切","，合肥南至南京南最快1小时22分可达，进一步密切"))){
            Map<String,Object> c=stationRoute(true);original(c,s);bad(c,true,"diagram mutation");
        }
        Map<String,Object> c=stationRoute(true);c.put("duration_text","58分钟");bad(c,true,"neighbor duration cannot bind target");
        c=stationRoute(true);c.put("from_city","杭州");c.put("from_province","浙江");c.put("from_endpoint","杭州");bad(c,true,"station cannot bind other city");
        String service=(String)serviceTrip(false).get("original_content");
        for(String s:List.of(service.replace("1小时5分","1小时8分"),service.replace("1小时8分钟","1小时12分钟"),service.replace("G903","G6079"),
            service.replace("21:57","19:57"),service.replace("21:57","25:57"),service.replace("21:57","21:99"),service.replace("21:57","次日21:57"),
            service.replace("21:57抵达香港示范","21:57抵达澳门示范"),service.replace("8:00香港示范始发","8:00广州北站始发"),
            service.replace("稳定往返","临时往返"),service.replace("返程G6079","返程预计G6079"),service+"该线路不再开行。",
            service.replace("全程仅需","往返合计仅需"),service.replace("8:00香港示范","8:00香港\n示范"))){
            c=serviceTrip(false);original(c,s);bad(c,true,"service mutation cannot compose unrelated clocks");
        }
        c=serviceTrip(true);c.put("selected_service_id","G6079");bad(c,true,"slower service cannot inherit fastest text");
        c=serviceTrip(true);c.put("selected_service_id","G6080");bad(c,true,"return cannot reuse outward service");
        c=serviceTrip(false);c.remove("station_city_mappings");bad(c,true,"HK prefix alone insufficient");
        for(Map<String,?> mutation:List.of(Map.of("mapping_checked",false),Map.of("city","澳门"),Map.of("province","广东"),
            Map.of("source_url","http://transport.example.test/station"),Map.of("source_status","revoked"),Map.of("source_date_status",""),
            Map.of("checked_on","2026-09-09"),Map.of("checked_on","2024-09-08"))){
            c=serviceTrip(false);Map<String,Object> mapping=map(((List<?>)c.get("station_city_mappings")).get(0));mapping.putAll(mutation);bad(c,true,"mapping metadata mutation");
        }
        c=serviceTrip(false);Map<String,Object> mapping=map(((List<?>)c.get("station_city_mappings")).get(0));original(mapping,"香港示范站是高速铁路(澳门段)(\"高铁\")的总站。");bad(c,true,"source mapping city mismatch");
        c=serviceTrip(false);bind(c);map(map(c.get("context_binding")).get("from_binding")).put("mapping_source_sha256","0".repeat(64));bad(c,false,"source mapping hash tampered");
        c=serviceTrip(true);bind(c);map(((List<?>)map(c.get("context_binding")).get("all_service_items")).get(1)).put("clock_duration_minutes",68);bad(c,false,"other service clock tampered");
        c=serviceTrip(false);c.put("assertion_start",23);bad(c,true,"cannot discard full paragraph context");
        c=serviceTrip(false);c.put("context_binding_type",PublicCityContext.DATED_ROUTE);bad(c,true,"old grammar did not relax roundtrip ban");
    }
    static void stationChain(){
        Map<String,Object> d=PublicCityBoundsIntegrationTest.fixture("1小时15分","1小时22分");
        for(int i=0;i<2;i++){Map<String,Object> c=PublicCityBoundsIntegrationTest.content(d,i);c.clear();c.putAll(stationRoute(i==1));bind(c);CityTravelReferenceTest.rehashEvidence(d,i);}CityTravelReferenceTest.reseal(d);
        Map<String,Object> r=PublicCityBoundsIntegrationTest.lookup(d);check("rail".equals(r.get("eligibility")),"station contexts existing hash chain");
        check("rail".equals(PublicCityBoundsIntegrationTest.resolve(Map.of(),d).get("eligibility")),"station contexts TravelMatrix");
        Map<String,Object> reverse=CityTravelReference.lookup(d,"安徽","合肥","江苏","南京",TODAY);check(map(reverse.get("outbound_duration")).get("reported_minutes").equals(82),"station reverse independent82");
        check(map(reverse.get("return_duration")).get("reported_minutes").equals(75),"station reverse returns original75");
        check(Boolean.FALSE.equals(reverse.get("time_score_applicable")),"station city bounds no invented traffic score");
    }
    public static void main(String[] args){grammar();guards();chain();stationGrammar();stationGuards();stationChain();System.out.println("PublicCityContextTest: "+checks+" checks passed (synthetic only)");}
}
