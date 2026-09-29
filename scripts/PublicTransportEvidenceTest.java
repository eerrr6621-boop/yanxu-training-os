package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Synthetic public-fact contract checks; not a real transport survey. */
public final class PublicTransportEvidenceTest {
    static final LocalDate TODAY=LocalDate.of(2026,9,8);
    static int checks;
    static void check(boolean value,String name) { if(!value)throw new AssertionError(name);checks++; }
    static Map<String,Object> m(Object... pairs) { return CityTravelReferenceTest.m(pairs); }
    static Map<String,Object> map(Object value) { return CityTravelReferenceTest.map(value); }
    static List<Object> list(Object value) { return CityTravelReferenceTest.list(value); }
    static void publicUse(Map<String,Object> row) {
        row.remove("usage_authorized");row.remove("authorization_ref");
        row.put("business_import_allowed",true);row.put("use_basis","independently_curated_public_facts");row.put("license_claimed",false);
    }
    static Map<String,Object> fixture(String mode,int out,int back) {
        Map<String,Object> data=CityTravelReferenceTest.fixture(mode,out,back);
        data.put("version",PublicTransportEvidence.SCHEMA);publicUse(data);
        Map<String,Object> ref=CityTravelReferenceTest.snapshot(data);publicUse(ref);
        ref.put("review_status","source_checked");ref.put("reviewer_type","assistant");
        ref.put("review_method","direction_quote_duration_city_crosscheck");
        ref.put("research_on",TODAY.minusDays(1).toString());ref.put("reviewed_on",TODAY.toString());ref.put("review_due_on",TODAY.plusDays(90).toString());
        List<Object> evidence=list(data.get("evidence_snapshots"));
        for(int i=0;i<evidence.size();i++) {
            Map<String,Object> content=map(map(evidence.get(i)).get("content"));publicUse(content);
            String fp=(String)content.get("from_city"),tp=(String)content.get("to_city");
            content.putAll(m("evidence_type","public_transport_duration_statement","source_access","public_page",
                "publisher_kind","government","service_state","operating","direction_basis","explicit_direction",
                "source_published_on",TODAY.minusDays(15).toString(),"effective_from",TODAY.minusDays(16).toString(),
                "research_on",TODAY.minusDays(1).toString(),"from_endpoint",fp+"灰度站","to_endpoint",tp+"灰度站"));
            int minutes=((Number)content.get("reference_minutes")).intValue();
            String duration=minutes/60+"小时"+minutes%60+"分钟";
            content.put("duration_text",duration);
            String statement=fp+"灰度站至"+tp+"灰度站"+("air".equals(content.get("mode"))?"直飞":"高铁")+"最快"+duration;
            if("all_city_stations_and_rail_itineraries".equals(content.get("scope"))) {
                content.put("minimum_basis","explicit_citywide_fastest");content.put("from_endpoint",fp);content.put("to_endpoint",tp);
                statement=fp+"至"+tp+"，按两市全部车站及所有铁路路径统计，最快用时为"+duration;
            }
            content.put("original_content",statement+"。");
            content.put("synthetic_notice","这是虚构测试，不是实际交通来源。");
            content.put("assertion_start",0);content.put("assertion_end",statement.length());
            CityTravelReferenceTest.rehashEvidence(data,i);
        }
        CityTravelReferenceTest.reseal(data);return data;
    }
    static Map<String,Object> lookup(Map<String,Object> data) { return CityTravelReference.lookup(data,"江苏","南京","安徽","合肥",TODAY); }
    static boolean rail(Map<String,Object> data) { return "rail".equals(lookup(data).get("eligibility")); }
    static void mutations() {
        check(rail(fixture("rail",113,106)),"public independent summaries qualify without inventing timetable license or human reviewer");
        Map<String,Object> good=lookup(fixture("rail",113,106));
        check("public_city_reference".equals(good.get("reference_basis"))&&Boolean.FALSE.equals(good.get("human_reviewed"))&&Boolean.FALSE.equals(good.get("license_claimed")),"truthful provenance");
        check(Boolean.FALSE.equals(good.get("transport_verified"))&&Boolean.FALSE.equals(good.get("travel_date_verified")),"reference is not verified journey");
        Map<String,Object> reverse=CityTravelReference.lookup(fixture("rail",113,106),"安徽","合肥","江苏","南京",TODAY);
        check(reverse.get("outbound_reference_minutes").equals(106)&&reverse.get("return_reference_minutes").equals(113),"direction swap preserves distinct witnesses");
        check(rail(fixture("rail",239,225))&&Boolean.TRUE.equals(lookup(fixture("rail",239,225)).get("near_threshold")),"explicit public 239-minute reference remains within user boundary with caution");
        check(!rail(fixture("rail",240,225)),"strict four-hour rail boundary");
        check("air".equals(lookup(fixture("air",179,160)).get("eligibility")),"explicit bilateral 179-minute air with complete railway proof");
        check("unknown".equals(lookup(fixture("air",180,160)).get("eligibility")),"strict three-hour air boundary");
        for(Map<String,Object> change:List.of(m("reviewer_type","human"),m("review_status","approved"),m("review_status","revoked"),m("license_claimed",true),
            m("research_only",true),m("usage_authorized",true),m("business_import_allowed",false),m("use_basis","scraped_vendor_database"),m("review_due_on",TODAY.minusDays(1).toString()))) {
            Map<String,Object> data=fixture("rail",113,106);CityTravelReferenceTest.snapshot(data).putAll(change);CityTravelReferenceTest.reseal(data);
            check(!rail(data),"invalid public review/use metadata: "+change);
        }
        for(Map<String,Object> change:List.of(m("source_access","restricted_api"),m("publisher_kind","unverified_repost"),m("service_state","planned"),
            m("direction_basis","mirrored"),m("duration_basis","straight_line_estimate"),m("status","revoked"),m("source_status","withdrawn"),m("assertion_start",1),m("assertion_end",3),
            m("source_published_on",TODAY.minusDays(366).toString()),m("effective_from",TODAY.plusDays(1).toString()),
            m("source_published_on",TODAY.toString()),m("temporary_service",true),m("cancelled",true),m("original_content","没有方向和分钟的说明"),
            m("duration_text","1小时52分钟"),m("from_endpoint","其他灰度站"),m("city_mapping_checked",false),m("usage_authorized",true),m("license_claimed",true),
            m("source_url","https://user:secret@example.test/"),m("research_only",true),m("business_import_allowed",false))) {
            Map<String,Object> data=fixture("rail",113,106);map(map(list(data.get("evidence_snapshots")).get(0)).get("content")).putAll(change);
            CityTravelReferenceTest.rehashEvidence(data,0);check(!rail(data),"invalid explicit public witness: "+change);
        }
        Map<String,Object> missing=fixture("rail",113,106);list(missing.get("evidence_snapshots")).remove(1);
        check(!rail(missing),"missing reverse never mirrored");
        for(String key:List.of("nonstop","coverage_complete")) {
            Map<String,Object> data=fixture("air",120,130);int index=key.equals("nonstop")?0:2;
            map(map(list(data.get("evidence_snapshots")).get(index)).get("content")).put(key,false);CityTravelReferenceTest.rehashEvidence(data,index);
            check("unknown".equals(lookup(data).get("eligibility")),"incomplete air or rail exclusion "+key);
        }
        Map<String,Object> stationOnly=fixture("air",120,130);
        map(map(list(stationOnly.get("evidence_snapshots")).get(2)).get("content")).put("minimum_basis","one_slow_train");CityTravelReferenceTest.rehashEvidence(stationOnly,2);
        check("unknown".equals(lookup(stationOnly).get("eligibility")),"slow witness is not citywide minimum");
    }
    static Map<String,Object> first(Map<String,Object> data) {return map(map(list(data.get("evidence_snapshots")).get(0)).get("content"));}
    static void statement(Map<String,Object> content,String value) {
        content.put("original_content",value+"。");content.put("assertion_start",0);content.put("assertion_end",value.length());
    }
    static void binding() {
        for(String bad:List.of("南京灰度站至合肥灰度站最快5小时；合肥灰度站至南京灰度站最快1小时53分钟",
            "南京灰度站至合肥灰度站最快5小时，杭州灰度站至绍兴灰度站最快1小时53分钟",
            "南京灰度站至合肥灰度站规划最快1小时53分钟，尚未开通",
            "南京灰度站至合肥灰度站最快1小时53分钟，暑运临时开行")) {
            Map<String,Object> data=fixture("rail",113,106);statement(first(data),bad);CityTravelReferenceTest.rehashEvidence(data,0);
            check(!rail(data),"duration must bind current regular direction: "+bad);
        }
        Map<String,Object> data=fixture("air",120,130),proof=map(map(list(data.get("evidence_snapshots")).get(2)).get("content"));
        proof.put("from_endpoint","南京灰度站");proof.put("to_endpoint","合肥灰度站");statement(proof,"南京灰度站至合肥灰度站高铁最快5小时0分钟");
        CityTravelReferenceTest.rehashEvidence(data,2);check("unknown".equals(lookup(data).get("eligibility")),"station-pair fastest never means citywide exclusion");
        Map<String,Object> timed=fixture("rail",110,106),c=first(timed);
        c.putAll(m("duration_basis","published_endpoint_times","time_binding","same_service_departure_arrival","departure_kind","departure","arrival_kind","arrival",
            "departure_time_text","6:48","arrival_time_text","8:38","arrival_day_offset",0));
        statement(c,"这趟早高峰列车6:48从南京灰度站始发，经灰度中间站，7:53到达灰度第二站，8:38抵达合肥灰度站");
        CityTravelReferenceTest.rehashEvidence(timed,0);check(rail(timed),"same-service endpoints support exact arithmetic, not geographic estimation");
        for(Map<String,Object> change:List.of(m("departure_kind","arrival"),m("arrival_time_text","7:53"),m("arrival_day_offset",1),m("time_binding","different_services"))) {
            Map<String,Object> copy=CityTravelReferenceTest.copy(timed);first(copy).putAll(change);CityTravelReferenceTest.rehashEvidence(copy,0);
            check(!rail(copy),"reject incompatible endpoint roles: "+change);
        }
        for(String bad:List.of("6:48从南京灰度站始发，7:53到达灰度第二站，另一趟列车8:38抵达合肥灰度站",
            "6:48从南京灰度站始发，8:53到达灰度第二站，8:38抵达合肥灰度站",
            "6:48从南京灰度站始发，7:53到达合肥灰度站，8:38抵达灰度第三站")) {
            Map<String,Object> copy=CityTravelReferenceTest.copy(timed);statement(first(copy),bad);CityTravelReferenceTest.rehashEvidence(copy,0);
            check(!rail(copy),"different services or arrival sequence cannot make a duration: "+bad);
        }
    }
    static void integrated() throws Exception {
        Map<String,Object> data=fixture("rail",113,106),teacher=m("base_province","江苏","base_city","南京");
        DispatchPreference pref=CityTravelReferenceTest.pref();
        Map<String,Object> result=TravelMatrix.resolve(List.of(),Map.of(),Map.of(),data,teacher,pref,TODAY);
        check("rail".equals(result.get("eligibility")),"public library reaches actual resolver");
        Map<String,Object> revoked=CityTravelReferenceTest.fixture("rail",113,106);
        CityTravelReferenceTest.snapshot(revoked).put("review_status","revoked");CityTravelReferenceTest.reseal(revoked);
        check("unknown".equals(TravelMatrix.resolve(List.of(),Map.of(),revoked,data,teacher,pref,TODAY).get("eligibility")),"public fallback does not bypass configured revocation");
        Map<String,Object> c=CityTravelReferenceTest.candidate(1,80,"讲师",result);
        check(list(NearbySelection.select(List.of(c),pref,3).get("selected")).size()==1,"actual selection includes public reference");
        System.setProperty("dispatch.nearby.max.minutes","0");
        try {check(!"rail".equals(TravelMatrix.resolve(List.of(),Map.of(),Map.of(),data,teacher,pref,TODAY).get("eligibility")),"admin pause respected");}
        finally {System.clearProperty("dispatch.nearby.max.minutes");}
        Path directory=Files.createTempDirectory("yanxu-public-reference-test-");Path file=directory.resolve("synthetic-public.json");
        Files.writeString(file,Json.write(data));
        System.setProperty("dispatch.public.city.references.file",file.toString());
        try {
            check(PublicTransportEvidence.schema(CityTravelReference.publicBundle("安徽","合肥")),"public-only configured loader");
            check(Files.exists(directory.resolve("synthetic-public.json.integrity.json")),"persistent revision integrity written in isolated directory");
            check(CityTravelReference.sha256(CityTravelReference.publicBundle("安徽","合肥")).equals(CityTravelReference.sha256(data)),"repeat load retains same canonical reference across JSON integer types");
        } finally {System.clearProperty("dispatch.public.city.references.file");}
        // Generated synthetic files are retained for test inspection, not mixed with a real library.
    }
    static void completeAssertionAndStationTokens() {
        Map<String,Object> unambiguous=fixture("rail",110,106),c=first(unambiguous);
        c.putAll(m("duration_basis","published_endpoint_times","time_binding","same_service_departure_arrival","departure_kind","departure","arrival_kind","arrival",
            "departure_time_text","6:48","arrival_time_text","8:38","arrival_day_offset",0));
        statement(c,"这趟早高峰列车6:48从南京灰度站始发，经灰度中间站，7:53到达灰度第二站，8:38抵达合肥灰度站");
        CityTravelReferenceTest.rehashEvidence(unambiguous,0);
        check(rail(unambiguous),"full independent endpoint assertion remains usable");
        for(String prefix:List.of("下述列车暂未开行，仅为规划时刻。","以下为另一日期的时刻。","材料背景。")) {
            Map<String,Object> copy=CityTravelReferenceTest.copy(unambiguous),row=first(copy);
            int oldEnd=((Number)row.get("assertion_end")).intValue();
            row.put("original_content",prefix+row.get("original_content"));row.put("assertion_start",prefix.length());row.put("assertion_end",prefix.length()+oldEnd);
            CityTravelReferenceTest.rehashEvidence(copy,0);
            check(!rail(copy),"unconsumed prefix stays pending even at a sentence boundary: "+prefix);
        }
        for(String suffix:List.of("上述列车尚未开通，仅为规划时刻。","以上包含两班不同的列车。","这是虚构测试。")) {
            Map<String,Object> copy=CityTravelReferenceTest.copy(unambiguous),row=first(copy);
            row.put("original_content",row.get("original_content")+suffix);CityTravelReferenceTest.rehashEvidence(copy,0);
            check(!rail(copy),"unconsumed suffix cannot be discarded: "+suffix);
        }
        Map<String,Object> punctuation=CityTravelReferenceTest.copy(unambiguous),pc=first(punctuation);
        String original=(String)pc.get("original_content");int oldEnd=((Number)pc.get("assertion_end")).intValue();
        pc.put("original_content"," \n"+original+"； \n");pc.put("assertion_start",2);pc.put("assertion_end",oldEnd+2);
        CityTravelReferenceTest.rehashEvidence(punctuation,0);
        check(rail(punctuation),"outside punctuation and whitespace carry no omitted assertion");
        for(String stationTail:List.of("灰度第二站后改乘另一趟列车","灰度第二站（下段为另一趟列车）","灰度第二站后等待另一班车",
            "灰度第二站后经其他站","灰度第二站后办理业务","灰度第二站A")) {
            Map<String,Object> copy=CityTravelReferenceTest.copy(unambiguous);
            statement(first(copy),"这趟早高峰列车6:48从南京灰度站始发，7:53到达"+stationTail+"，8:38抵达合肥灰度站");
            CityTravelReferenceTest.rehashEvidence(copy,0);
            check(!rail(copy),"unparsed station narrative never proves same service: "+stationTail);
        }
        for(String station:List.of("灰度第二站","新城东站","灰度T2站")) {
            Map<String,Object> copy=CityTravelReferenceTest.copy(unambiguous);
            statement(first(copy),"这趟早高峰列车6:48从南京灰度站始发，7:53到达"+station+"，8:38抵达合肥灰度站");
            CityTravelReferenceTest.rehashEvidence(copy,0);check(rail(copy),"complete intermediate station token: "+station);
        }
        Map<String,Object> evening=fixture("rail",122,106),ec=first(evening);
        ec.putAll(m("duration_basis","published_endpoint_times","time_binding","same_service_departure_arrival","departure_kind","departure","arrival_kind","arrival",
            "departure_time_text","17:16","arrival_time_text","19:18","arrival_day_offset",0));
        statement(ec,"晚高峰时段，G8861/2次列车17:16从南京灰度站出发，17:59到达灰度第二站，经停灰度中间站后19:18抵达合肥灰度站");
        CityTravelReferenceTest.rehashEvidence(evening,0);check(rail(evening),"whole transit clause with station followed by 后 keeps exact 122-minute event");
    }
    public static void main(String[] args) throws Exception {
        mutations();binding();completeAssertionAndStationTokens();integrated();
        check(PublicTransportEvidence.duration("1小时53分钟")==113&&PublicTransportEvidence.duration("45分钟")==45,"explicit duration parser");
        System.out.println("Public transport facts: "+checks+" synthetic checks passed; no live routes researched or production writes");
    }
}
