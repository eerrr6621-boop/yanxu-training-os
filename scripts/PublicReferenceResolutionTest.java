package com.training;

import java.time.*;
import java.util.*;

/** Offline cross-library contracts. All travel assertions below are synthetic. */
public final class PublicReferenceResolutionTest {
    static final LocalDate TODAY=PublicTransportEvidenceTest.TODAY;
    static int checks;
    static void check(boolean ok,String label) { if(!ok)throw new AssertionError(label);checks++; }
    static Map<String,Object> privateRail() {return CityTravelReferenceTest.fixture("rail",100,120);}
    static Map<String,Object> publicRail() {return PublicTransportEvidenceTest.fixture("rail",113,106);}
    static Map<String,Object> ref(Map<String,Object> data) {return CityTravelReferenceTest.snapshot(data);}
    static void seal(Map<String,Object> data) {CityTravelReferenceTest.reseal(data);}
    static Map<String,Object> content(Map<String,Object> data,int index) {
        return CityTravelReferenceTest.map(CityTravelReferenceTest.map(CityTravelReferenceTest.list(data.get("evidence_snapshots")).get(index)).get("content"));
    }
    static Map<String,Object> resolve(Map<?,?> privateData,Map<?,?> publicData) {
        return TravelMatrix.resolve(List.of(),Map.of(),privateData,publicData,Map.of("base_province","江苏","base_city","南京"),CityTravelReferenceTest.pref(),TODAY);
    }
    static boolean is(Map<?,?> result,String state) {return state.equals(result.get("eligibility"));}
    static void datePair(Map<String,Object> data,LocalDate out,LocalDate back) {
        for(int i=0;i<2;i++) {
            content(data,i).put("research_on",(i==0?out:back).toString());
            CityTravelReferenceTest.rehashEvidence(data,i);
        }
        ref(data).put("research_on",(out.isAfter(back)?out:back).toString());
        ref(data).put("reviewed_on",TODAY.toString());ref(data).put("review_due_on",TODAY.plusDays(90).toString());seal(data);
    }
    static void publicationPair(Map<String,Object> data,LocalDate out,LocalDate back) {
        for(int i=0;i<2;i++) {
            LocalDate date=i==0?out:back;content(data,i).put("source_published_on",date.toString());content(data,i).put("effective_from",date.minusDays(1).toString());
            CityTravelReferenceTest.rehashEvidence(data,i);
        }
    }
    static void denialAndUnknown() {
        for(boolean privateSide:List.of(false,true))for(String layer:List.of("library","reference","evidence"))
            for(String[] denial:List.of(new String[]{"source_status","revoked"},new String[]{"source_status","withdrawn"},
                new String[]{"source_status","rejected"},new String[]{"status","rejected"},new String[]{"review_status","rejected"})) {
                Map<String,Object> p=privateRail(),q=publicRail(),denied=privateSide?p:q;
                Map<String,Object> row=layer.equals("library")?denied:layer.equals("reference")?ref(denied):content(denied,0);
                row.put(denial[0],denial[1]);
                if(layer.equals("evidence"))CityTravelReferenceTest.rehashEvidence(denied,0);else seal(denied);
                check(is(resolve(p,q),"unknown"),"explicit source-state contract: "+layer+" "+denial[0]+"="+denial[1]+" private="+privateSide);
            }
        for(boolean privateSide:List.of(false,true)) {
            for(String status:List.of("revoked","withdrawn","suspended")) {
                Map<String,Object> p=privateRail(),q=publicRail(),denied=privateSide?p:q;
                ref(denied).put("review_status",status);seal(denied);
                check(is(resolve(p,q),"unknown"),"explicit "+status+" cannot hide behind the other library: private="+privateSide);
            }
            for(String flag:List.of("research_only","business_import_allowed","usage_authorized")) {
                Map<String,Object> p=privateRail(),q=publicRail(),denied=privateSide?p:q;
                ref(denied).put(flag,flag.equals("research_only"));seal(denied);
                check(is(resolve(p,q),"unknown"),"explicit reference use prohibition: "+flag+" private="+privateSide);
            }
            Map<String,Object> p=privateRail(),q=publicRail(),broken=privateSide?p:q;
            content(broken,0).put("reference_minutes",1);
            check(is(resolve(p,q),"unknown"),"source integrity cannot be bypassed: private="+privateSide);
            p=privateRail();q=publicRail();broken=privateSide?p:q;
            ref(broken).put("reviewer_id","TAMPERED-WITHOUT-REHASH");
            check(is(resolve(p,q),"unknown"),"audit integrity cannot be bypassed: private="+privateSide);
        }
        Map<String,Object> p=privateRail(),q=publicRail();content(q,0).put("status","revoked");CityTravelReferenceTest.rehashEvidence(q,0);
        check(is(resolve(p,q),"unknown"),"linked public evidence explicit revocation overrides opposite operating metadata");
        q=publicRail();content(q,0).put("research_only",true);CityTravelReferenceTest.rehashEvidence(q,0);
        check(is(resolve(p,q),"unknown"),"linked public source research-only cannot be hidden by private rail");
        q=publicRail();ref(q).put("review_status","pending");seal(q);content(q,0).put("reference_minutes",1);
        check(is(resolve(p,q),"unknown"),"pending review cannot hide an already-declared source digest mismatch");
        q=publicRail();ref(q).put("review_status","pending");seal(q);
        CityTravelReferenceTest.list(q.get("evidence_snapshots")).add(CityTravelReferenceTest.copy(CityTravelReferenceTest.map(CityTravelReferenceTest.list(q.get("evidence_snapshots")).get(0))));
        check(is(resolve(p,q),"unknown"),"pending review cannot hide duplicate linked evidence identity");
        for(Map<?,?> broken:List.of(CityTravelReference.unavailable("audit_hash_mismatch"),CityTravelReference.unavailable("reference_history_rollback_or_changed")))
            check(is(resolve(p,broken),"unknown"),"configured unavailable integrity library blocks fallback");
        for(String status:List.of("pending","unknown","needs_review")) {
            q=publicRail();ref(q).put("review_status",status);seal(q);
            check(is(resolve(p,q),"rail"),"ordinary public review status does not veto valid private reference: "+status);
        }
        q=publicRail();ref(q).put("review_due_on",TODAY.minusDays(1).toString());seal(q);
        check(is(resolve(p,q),"rail"),"public review overdue is unknown rather than evidence of route failure");
        q=publicRail();ref(q).remove("return");seal(q);
        check(is(resolve(p,q),"rail"),"missing public return does not negate verified private pair");
        q=publicRail();CityTravelReferenceTest.list(q.get("references")).clear();
        check(is(resolve(p,q),"rail"),"public catalog without this pair does not negate private pair");
    }
    static void conflictsAndChoice() {
        check(is(resolve(privateRail(),PublicTransportEvidenceTest.fixture("rail",240,250)),"unknown"),"opposite rail threshold cannot choose favorable private duration");
        check(is(resolve(CityTravelReferenceTest.fixture("rail",240,250),publicRail()),"unknown"),"opposite rail threshold cannot choose favorable public duration");
        check(is(resolve(CityTravelReferenceTest.fixture("air",120,130),PublicTransportEvidenceTest.fixture("air",180,180)),"unknown"),"opposite air threshold cannot choose favorable duration");
        check(is(resolve(CityTravelReferenceTest.fixture("air",100,120),publicRail()),"unknown"),"old private air exclusion conflicts with new public rail");
        check(is(resolve(privateRail(),PublicTransportEvidenceTest.fixture("air",100,120)),"unknown"),"private rail conflicts with public air exclusion");
        Map<String,Object> result=resolve(privateRail(),publicRail());
        check(is(result,"rail")&&"public_city_reference".equals(result.get("reference_basis")),"newer whole paired reference chosen instead of shorter duration");
        check(result.get("outbound_reference_minutes").equals(113)&&result.get("return_reference_minutes").equals(106),"outbound and return never spliced across libraries");
        check(result.get("reference_comparison") instanceof List<?> && ((List<?>)result.get("reference_comparison")).size()>=2,"alternative provenance retained for review");
        Map<String,Object> p=privateRail(),q=publicRail();datePair(p,TODAY.minusDays(15),TODAY.minusDays(15));
        check(is(resolve(p,q),"unknown"),"same-date different minutes remain explicit unresolved conflict");
        p=privateRail();q=publicRail();datePair(p,TODAY.minusDays(15),TODAY.minusDays(17));publicationPair(q,TODAY.minusDays(16),TODAY.minusDays(15));
        check(is(resolve(p,q),"unknown"),"crossed direction freshness cannot choose one convenient pair");
        p=CityTravelReferenceTest.fixture("rail",113,106);datePair(p,TODAY.minusDays(15),TODAY.minusDays(15));
        result=resolve(p,publicRail());
        check(is(result,"rail")&&"reviewed_city_reference".equals(result.get("reference_basis")),"equal paired times/date use stable private-first tie, not false conflict");
        p=privateRail();datePair(p,TODAY.minusDays(3),TODAY.minusDays(3));q=publicRail();publicationPair(q,LocalDate.of(2026,1,27),LocalDate.of(2026,1,27));
        result=resolve(p,q);
        check(is(result,"rail")&&"reviewed_city_reference".equals(result.get("reference_basis")),"reading an old publication yesterday cannot supersede newer actual research");
        check(TODAY.minusDays(1).toString().equals(ref(q).get("research_on")),"comparison never rewrites public reading date to publication date");
        Map<String,Object> raw=CityTravelReferenceTest.rawRail(false,300,TODAY.minusDays(2));
        result=TravelMatrix.resolve(List.of(raw),Map.of(),Map.of(),q,Map.of("base_province","江苏","base_city","南京"),CityTravelReferenceTest.pref(),TODAY);
        check(is(result,"unknown"),"newer single-direction observation blocks reread old public notice");
        result=TravelMatrix.resolve(List.of(CityTravelReferenceTest.rawRail(false,150,TODAY.minusDays(2)),CityTravelReferenceTest.rawRail(true,150,TODAY.minusDays(2))),Map.of(),Map.of(),q,
            Map.of("base_province","江苏","base_city","南京"),CityTravelReferenceTest.pref(),TODAY);
        check(is(result,"rail")&&"rail_time_reference".equals(result.get("status")),"bilateral actual recent observations outrank a later reading of old public text");
    }
    static void compatibility() {
        for(Map<?,?> p:List.of(Map.of(),privateRail(),CityTravelReference.unavailable("reference_library_io_unavailable"))) {
            Map<String,Object> original=TravelMatrix.resolve(List.of(),Map.of(),p,Map.of("base_province","江苏","base_city","南京"),CityTravelReferenceTest.pref(),TODAY);
            check(CityTravelReference.sha256(original).equals(CityTravelReference.sha256(resolve(p,Map.of()))),"empty public library preserves original resolver exactly");
        }
        check(is(resolve(Map.of(),publicRail()),"rail"),"standalone valid public reference remains available");
        Map<String,Object> q=publicRail();ref(q).put("review_status","revoked");seal(q);
        Map<String,Object> recent=TravelMatrix.resolve(List.of(CityTravelReferenceTest.rawRail(false,100,TODAY),CityTravelReferenceTest.rawRail(true,100,TODAY)),Map.of(),Map.of(),q,
            Map.of("base_province","江苏","base_city","南京"),CityTravelReferenceTest.pref(),TODAY);
        check(is(recent,"unknown"),"recent sample cannot hide an explicitly configured public revocation");
        System.setProperty("dispatch.nearby.max.minutes","0");
        try {check(is(resolve(privateRail(),publicRail()),"unknown"),"administrator pause preserved");}
        finally {System.clearProperty("dispatch.nearby.max.minutes");}
        Map<String,Object> same=TravelMatrix.resolve(List.of(),Map.of(),Map.of(),publicRail(),Map.of("base_province","安徽","base_city","合肥"),CityTravelReferenceTest.pref(),TODAY);
        check("same_city".equals(same.get("status")),"unrelated intercity references do not invalidate same-city selection");
    }
    public static void main(String[] args) {
        denialAndUnknown();conflictsAndChoice();compatibility();
        System.out.println("Public cross-library resolution: "+checks+" synthetic checks passed; no actual transport library read");
    }
}
