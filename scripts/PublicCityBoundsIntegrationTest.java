package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Synthetic end-to-end bounds through the existing public hash-chain. */
public final class PublicCityBoundsIntegrationTest {
    static final LocalDate TODAY=PublicTransportEvidenceTest.TODAY;
    static int checks;
    static void check(boolean yes,String name) {if(!yes)throw new AssertionError(name);checks++;}
    static Map<String,Object> ref(Map<String,Object> data) {return CityTravelReferenceTest.snapshot(data);}
    static Map<String,Object> content(Map<String,Object> data,int i) {return PublicReferenceResolutionTest.content(data,i);}
    static void seal(Map<String,Object> data) {CityTravelReferenceTest.reseal(data);}
    static Map<String,Object> fixture(String out,String back) {
        Map<String,Object> data=PublicTransportEvidenceTest.fixture("rail",100,100);
        for(int i=0;i<2;i++) {
            Map<String,Object> c=content(data,i),link=CityTravelReferenceTest.map(ref(data).get(i==0?"outbound":"return"));
            String duration=i==0?out:back,from=(String)c.get("from_city"),to=(String)c.get("to_city");
            c.remove("reference_minutes");c.put("scope",PublicCityBounds.SCOPE);c.put("duration_basis",PublicCityBounds.DURATION_BASIS);
            c.put("from_endpoint",from);c.put("to_endpoint",to);c.put("duration_text",duration);
            c.put("transport_context_checked",true);c.put("scope_note","合成城市级时间范围，不是站对或全网最短");
            c.put("language_policy",PublicCityBounds.LANGUAGE_POLICY);
            String quote=from+"至"+to+"动车旅行时间"+duration;c.put("original_content",quote+"。");c.put("assertion_start",0);c.put("assertion_end",quote.length());
            link.remove("minutes");link.put("duration_bounds",PublicCityBounds.duration(duration).toMap());
            CityTravelReferenceTest.rehashEvidence(data,i);
        }
        seal(data);return data;
    }
    static Map<String,Object> lookup(Map<String,Object> data) {return PublicTransportEvidenceTest.lookup(data);}
    static Map<String,Object> resolve(Map<?,?> privateData,Map<?,?> publicData) {return PublicReferenceResolutionTest.resolve(privateData,publicData);}
    static boolean rail(Map<?,?> row) {return "rail".equals(row.get("eligibility"));}
    static Map<String,Object> candidate(long id,int score,Map<String,Object> route,String level) {
        Map<String,Object> fit=new LinkedHashMap<>();fit.put("local_priority",false);fit.put("departure_uncertain",false);fit.put("route_reference",route);
        return new LinkedHashMap<>(Map.of("teacher_id",id,"model_score",score,"recommendation_score",score,"teacher_level",level,"dispatch_fit",fit));
    }
    static void basics() {
        Map<String,Object> data=fixture("229分钟","3小时40多分钟"),r=lookup(data);
        check(rail(r)&&CityTravelReference.typed(r),"typed city pair through original public schema");
        check(PublicTransportEvidence.SCHEMA.equals(data.get("version")),"not fourth schema");
        check(r.get("status").equals("city_transport_reference")&&r.get("reference_basis").equals("public_city_reference"),"existing status and public identity");
        check(!r.containsKey("outbound_reference_minutes")&&!r.containsKey("return_reference_minutes"),"no counterfeit flat minute compatibility");
        check(!CityTravelReference.map(r.get("return")).containsKey("reference_minutes"),"no counterfeit nested minute");
        check(CityTravelReference.map(r.get("return_duration")).get("reported_minutes")==null,"inferred interval remains unmeasured");
        check(Boolean.FALSE.equals(r.get("time_score_applicable"))&&Boolean.FALSE.equals(r.get("rail_exclusion_complete")),"membership not distance score or global exclusion");
        check(r.containsKey("audit_head_sha256")&&r.containsKey("review_due_on"),"existing chain and review metadata kept");
        check(Boolean.FALSE.equals(r.get("transport_verified"))&&Boolean.FALSE.equals(r.get("human_reviewed")),"no real-trip/human fabrication");
        Map<String,Object> reverse=CityTravelReference.lookup(data,"安徽","合肥","江苏","南京",TODAY);
        check(CityTravelReference.map(reverse.get("outbound_duration")).get("original_text").equals("3小时40多分钟")&&CityTravelReference.map(reverse.get("return_duration")).get("reported_minutes").equals(229),"reverse lookup swaps whole independent evidence");
        check(rail(resolve(Map.of(),data)),"TravelMatrix accepts same public library typed result");
        for(String back:List.of("240分钟","4小时以内","约3小时40分钟","40多分钟","4小时10多分钟"))check(!rail(resolve(Map.of(),fixture("229分钟",back))),"unproven bound pending "+back);
        for(String back:List.of("239分钟","不足4小时","不超过239分钟","3小时50多分钟"))check(rail(resolve(Map.of(),fixture("229分钟",back))),"strict typed boundary "+back);
        data=fixture("229分钟","3小时40多分钟");ref(data).remove("return");seal(data);check(!rail(lookup(data)),"no return is pending");
        data=fixture("229分钟","3小时40多分钟");ref(data).put("return",ref(data).get("outbound"));seal(data);check(!rail(lookup(data)),"same source direction cannot mirror");
        Map<String,Object> legacy=PublicTransportEvidenceTest.lookup(PublicTransportEvidenceTest.fixture("rail",110,122));
        check(legacy.get("outbound_reference_minutes").equals(110)&&!CityTravelReference.typed(legacy),"old exact path unchanged");
    }
    static void integrityAndConflicts() {
        Map<String,Object> privateData=CityTravelReferenceTest.fixture("rail",100,120),data=fixture("229分钟","3小时40多分钟");
        Map<String,Object> chosen=resolve(privateData,data);
        check(rail(chosen)&&CityTravelReference.typed(chosen),"newer whole pair selected, not shortest values");
        check(((List<?>)chosen.get("reference_comparison")).size()==2,"both source alternatives retained");
        for(String value:List.of("4小时以内","约3小时40分钟","超过220分钟但不足260分钟"))
            check(!rail(resolve(privateData,fixture("229分钟",value))),"threshold uncertain cannot hide behind favorable exact pair "+value);
        data=fixture("229分钟","3小时40多分钟");content(data,0).put("duration_text","30分钟");
        check(!rail(resolve(privateData,data)),"source digest mismatch blocks another library");
        data=fixture("229分钟","3小时40多分钟");CityTravelReferenceTest.map(ref(data).get("outbound")).put("duration_bounds",PublicCityBounds.duration("30分钟").toMap());seal(data);
        check(!rail(resolve(privateData,data)),"rehashed reference cannot lie about source bounds");
        data=fixture("229分钟","3小时40多分钟");CityTravelReferenceTest.map(ref(data).get("outbound")).put("minutes",229);seal(data);
        check(!rail(resolve(privateData,data)),"typed reference cannot acquire fake exact field");
        for(String state:List.of("revoked","withdrawn","rejected","superseded","expired")) {
            data=fixture("229分钟","3小时40多分钟");content(data,1).put("source_status",state);CityTravelReferenceTest.rehashEvidence(data,1);
            check(!rail(resolve(privateData,data)),"explicit source veto "+state);
        }
        for(String layer:List.of("reference","evidence","library")) {
            data=fixture("229分钟","3小时40多分钟");Map<String,Object> denied=layer.equals("reference")?ref(data):layer.equals("evidence")?content(data,0):data;
            denied.put("research_only",true);if(layer.equals("evidence"))CityTravelReferenceTest.rehashEvidence(data,0);else seal(data);
            check(!rail(resolve(privateData,data)),"research-only prohibition "+layer);
        }
        data=fixture("229分钟","3小时40多分钟");ref(data).put("review_status","pending");seal(data);
        check(rail(resolve(privateData,data))&&!CityTravelReference.typed(resolve(privateData,data)),"ordinary review unknown does not negate private evidence");
        data=fixture("229分钟","3小时40多分钟");ref(data).put("review_due_on",TODAY.minusDays(1).toString());seal(data);
        check(!rail(lookup(data)),"expired typed review cannot self-renew");
        data=fixture("229分钟","3小时40多分钟");
        PublicReferenceResolutionTest.datePair(privateData,TODAY.minusDays(15),TODAY.minusDays(15));
        check(!rail(resolve(privateData,data)),"same-day unequal exact vs interval not merged");
        privateData=CityTravelReferenceTest.fixture("rail",100,120);PublicReferenceResolutionTest.datePair(privateData,TODAY.minusDays(14),TODAY.minusDays(16));
        check(!rail(resolve(privateData,data)),"crossed freshness cannot split pair");
        PublicReferenceResolutionTest.datePair(privateData,TODAY.minusDays(3),TODAY.minusDays(3));
        Map<String,Object> older=resolve(privateData,data);check(rail(older)&&!CityTravelReference.typed(older),"recent private evidence beats recently reread older city report");
        List<?> raw=List.of(CityTravelReferenceTest.rawRail(false,300,TODAY.minusDays(2)));
        check(!rail(TravelMatrix.resolve(raw,Map.of(),Map.of(),data,Map.of("base_province","江苏","base_city","南京"),CityTravelReferenceTest.pref(),TODAY)),"newer single-direction raw signal blocks old typed pair");
        check(!rail(resolve(CityTravelReferenceTest.fixture("air",100,120),data)),"air exclusion and typed rail contradiction remains pending");
        data=fixture("229分钟","3小时40多分钟");ref(data).put("mode","air");seal(data);check(!rail(lookup(data)),"typed city rail is not a flight proof");
    }
    @SuppressWarnings("unchecked") static void rankingAndCatalog() throws Exception {
        Map<String,Object> typed=lookup(fixture("229分钟","3小时40多分钟")),exact=lookup(PublicTransportEvidenceTest.fixture("rail",60,60));
        List<Map<String,Object>> rows=new ArrayList<>(List.of(candidate(1,95,typed,"讲师"),candidate(2,90,exact,"特聘讲师"),candidate(3,90,typed,"高级讲师"),candidate(4,90,typed,"特级讲师")));
        Map<String,Object> ranked=NearbySelection.select(rows,CityTravelReferenceTest.pref(),3);
        List<Map<String,Object>> selected=(List<Map<String,Object>>)ranked.get("selected");
        check(selected.stream().map(x->x.get("teacher_id")).toList().equals(List.of(1L,2L,4L,3L)),"whole rail pool model score then grade, all cutoff ties");
        check(((Map<?,?>)rows.get(0).get("dispatch_fit")).get("priority_score")==null,"inferred interval not scored by cap");
        check(ranked.get("pool_size").equals(4)&&ranked.get("ties_included").equals(1),"all qualified typed candidates remain");
        String previous=System.getProperty("dispatch.nearby.max.minutes");
        try {System.setProperty("dispatch.nearby.max.minutes","230");check(!rail(lookup(fixture("229分钟","3小时40多分钟"))),"administrator bound intersects interval -> pending");}
        finally {if(previous==null)System.clearProperty("dispatch.nearby.max.minutes");else System.setProperty("dispatch.nearby.max.minutes",previous);}
        Map<String,Object> tampered=new LinkedHashMap<>(typed);tampered.put("return_duration",PublicCityBounds.duration("50分钟").toMap());
        check(((List<?>)NearbySelection.select(List.of(candidate(8,99,tampered,"特聘讲师")),CityTravelReferenceTest.pref(),3).get("selected")).isEmpty(),"selection rechecks normalized duration contract");
        Path dir=Files.createTempDirectory("yanxu-typed-city-synthetic-");Path publicFile=dir.resolve("public.json"),railFile=dir.resolve("rail.json");
        Files.writeString(publicFile,Json.write(fixture("229分钟","3小时40多分钟")),StandardOpenOption.CREATE_NEW);
        Files.writeString(railFile,Json.write(Map.of("version","rail-timetable-v1","directions",List.of())),StandardOpenOption.CREATE_NEW);
        Map<String,String> settings=Map.of("dispatch.public.city.references.file",publicFile.toString(),"dispatch.public.city.references.dir","","dispatch.city.references.file","","dispatch.city.references.dir","",
            "dispatch.transport.file","","dispatch.transport.dir","","dispatch.timetable.file",railFile.toString(),"dispatch.organizations.file",dir.resolve("not-present.json").toString());
        Map<String,String> saved=new HashMap<>();for(String key:settings.keySet())saved.put(key,System.getProperty(key));
        try {
            settings.forEach(System::setProperty);
            Map<String,Object> catalog=DispatchPriority.catalog("安徽","合肥","2026-09-10",TODAY);
            List<Map<String,Object>> priorities=(List<Map<String,Object>>)catalog.get("priorities");
            Map<String,Object> city=priorities.stream().filter(x->"南京".equals(x.get("city"))).findFirst().orElseThrow();
            check(CityTravelReference.typed(city)&&city.get("priority_score")==null&&city.get("tier")==null,"catalog includes typed pair without fake distance score/tier");
            check("partial".equals(catalog.get("coverage")),"single pair is not nationwide coverage");
            check(Files.exists(publicFile.resolveSibling("public.json.integrity.json")),"existing public loader retains integrity anchor");
            check(!catalog.get("reference_checked_on").equals(TODAY.toString()),"catalog not fabricated as newly observed today");
        } finally {settings.keySet().forEach(k->{if(saved.get(k)==null)System.clearProperty(k);else System.setProperty(k,saved.get(k));});}
    }
    public static void main(String[] args) throws Exception {basics();integrityAndConflicts();rankingAndCatalog();System.out.println("PublicCityBoundsIntegrationTest: "+checks+" checks passed (synthetic only)");}
}
