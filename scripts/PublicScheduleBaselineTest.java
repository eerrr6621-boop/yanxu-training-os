package com.training;

import java.util.*;

/** Synthetic notice-baseline regression, not evidence of any real route. */
public final class PublicScheduleBaselineTest {
    static int checks,failures;
    static Map<String,Object> m(Object... pairs){return PublicTransportEvidenceTest.m(pairs);}
    static Map<String,Object> first(Map<String,Object> data){return PublicTransportEvidenceTest.first(data);}
    static void check(boolean ok,String label){checks++;if(!ok){failures++;System.out.println("FAIL "+label);}}
    static Map<String,Object> fixture(int minutes) {
        Map<String,Object> data=PublicTransportEvidenceTest.fixture("rail",minutes,minutes);
        for(int i=0;i<2;i++) {
            Map<String,Object> c=PublicTransportEvidenceTest.map(PublicTransportEvidenceTest.map(PublicTransportEvidenceTest.list(data.get("evidence_snapshots")).get(i)).get("content"));
            String published="2026-01-16",notice="1月26日起，铁路部门将实施新的列车运行图";
            c.putAll(m("service_state","published_schedule_baseline","schedule_basis","regular_published_running_diagram",
                "source_published_on",published,"effective_from","2026-01-26","direction_basis","explicit_round_trip_product",
                "duration_applies_to","each_one_way_service","schedule_effective_notice",m("source_url",c.get("source_url"),
                    "source_published_on",published,"original_content",notice+"。","assertion_start",0,"assertion_end",notice.length())));
            PublicTransportEvidenceTest.statement(c,"每日开行8列南京灰度站往返合肥灰度站的标杆列车，全程用时"+c.get("duration_text"));
            CityTravelReferenceTest.rehashEvidence(data,i);
        }
        return data;
    }
    static boolean rail(Map<String,Object> data){return PublicTransportEvidenceTest.rail(data);}
    static void rejectChange(Map<String,Object> change,String label){
        Map<String,Object> data=fixture(110);first(data).putAll(change);CityTravelReferenceTest.rehashEvidence(data,0);check(!rail(data),label);
    }
    public static void main(String[] args) {
        Map<String,Object> good=fixture(110);
        check(rail(good),"effective dated regular announcement supports a reusable baseline");
        Map<String,Object> result=PublicTransportEvidenceTest.lookup(good);
        check(Boolean.FALSE.equals(result.get("transport_verified"))&&Boolean.FALSE.equals(result.get("travel_date_verified")),"baseline is not verified travel");
        Map<?,?> leg=CityTravelReference.map(result.get("outbound"));
        check("published_schedule_baseline".equals(leg.get("service_state"))&&"2026-01-26".equals(leg.get("effective_from")),"baseline nature/date survives business response");
        check("2026-01-16".equals(leg.get("source_published_on")),"notice publication is not refreshed");
        check(rail(fixture(239))&&!rail(fixture(240)),"same strict rail boundary");
        for(Map<String,Object> c:List.of(m("service_state","planned"),m("schedule_basis","construction_plan"),m("effective_from","2026-10-26"),
            m("effective_from","2026-01-25"),m("source_published_on","2025-01-16"),m("research_on","2026-01-20"),m("effective_until","2026-08-31"),
            m("suspended",true),m("superseded",true),m("cancelled",true),m("temporary_service",true),m("source_status","withdrawn"),
            m("direction_basis","mirrored"),m("duration_applies_to","round_trip_total"),m("mode","air"),m("city_mapping_checked",false)))rejectChange(c,"invalid baseline "+c);
        for(String bad:List.of("每日开行8列南京灰度站至合肥灰度站的标杆列车，全程用时1小时50分钟",
            "每日开行8列南京灰度站往返合肥灰度站的标杆列车，往返总共用时1小时50分钟",
            "每日开行8列南京灰度站往返合肥灰度站的标杆列车，全程约1小时50分钟",
            "每日开行8列南京灰度站往返合肥灰度站的标杆列车，全程用时1小时50分钟；反向5小时",
            "每日开行8列南京灰度站往返合肥灰度站的标杆列车，全程用时1小时50分钟，尚未开通",
            "每日开行8列南京灰度站往返其他灰度站的标杆列车，全程用时1小时50分钟")) {
            Map<String,Object> d=fixture(110);PublicTransportEvidenceTest.statement(first(d),bad);CityTravelReferenceTest.rehashEvidence(d,0);check(!rail(d),"reject malformed/one-way/total/uncertain product "+bad);
        }
        for(String bad:List.of("1月26日起，铁路部门预计实施新的列车运行图","1月26日起，铁路部门将建设新的列车运行图",
            "1月26日起，铁路部门将实施新的列车运行图。暂缓执行。","2027年1月26日起，铁路部门将实施新的列车运行图")) {
            Map<String,Object> d=fixture(110),n=PublicTransportEvidenceTest.map(first(d).get("schedule_effective_notice"));
            PublicTransportEvidenceTest.statement(n,bad);CityTravelReferenceTest.rehashEvidence(d,0);check(!rail(d),"reject uncertain/wrong-year notice "+bad);
        }
        for(Map<String,Object> change:List.of(m("source_url","https://other.example.test/notice"),m("source_published_on","2026-01-17"),m("assertion_start",1),m("original_content",""))) {
            Map<String,Object> d=fixture(110);PublicTransportEvidenceTest.map(first(d).get("schedule_effective_notice")).putAll(change);
            CityTravelReferenceTest.rehashEvidence(d,0);check(!rail(d),"notice provenance/offset mismatch "+change);
        }
        for(Map<String,Object> change:List.of(m("source_status","withdrawn"),m("status","revoked"),m("review_status","rejected"),
            m("cancelled",true),m("suspended",true),m("superseded",true),m("source_status","expired"),m("temporary_service",true),m("source_access","restricted_api"))) {
            Map<String,Object> d=fixture(110);PublicTransportEvidenceTest.map(first(d).get("schedule_effective_notice")).putAll(change);
            CityTravelReferenceTest.rehashEvidence(d,0);check(!rail(d),"dependent implementation notice has its own validity "+change);
        }
        Map<String,Object> missing=fixture(110);PublicTransportEvidenceTest.list(missing.get("evidence_snapshots")).remove(1);check(!rail(missing),"bilateral statement still needs both bound legs");
        check(rail(PublicTransportEvidenceTest.fixture("rail",110,120)),"existing post-operation path unchanged");
        Map<String,Object> existing=PublicTransportEvidenceTest.fixture("rail",110,120);first(existing).put("effective_from","2026-09-01");
        CityTravelReferenceTest.rehashEvidence(existing,0);check(!rail(existing),"ordinary operating label does not bypass post-operation rule");
        System.out.println("PublicScheduleBaselineTest: "+(checks-failures)+"/"+checks+" passed");
        if(failures>0)throw new AssertionError(failures+" failed");
    }
}
