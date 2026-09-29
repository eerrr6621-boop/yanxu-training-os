package com.training;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;

/** Fully synthetic, offline tests; no real city timetable or teacher input is read. */
@SuppressWarnings("unchecked")
public final class CityTravelReferenceTest {
    static final LocalDate TODAY=LocalDate.of(2026,9,7),REVIEW=TODAY.minusDays(60),RESEARCH=REVIEW.minusDays(1);
    static final String FP="江苏",FC="南京",TP="安徽",TC="合肥";
    static int checks;static long tick=1;
    static void check(boolean ok,String label) { if(!ok)throw new AssertionError(label);checks++; }
    static Map<String,Object> m(Object... pairs) { Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m; }
    static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    static List<Object> list(Object value) { return (List<Object>)value; }
    static Map<String,Object> copy(Map<?,?> value) { return map(Json.parse(Json.write(value))); }
    static Map<String,Object> snapshot(Map<String,Object> data) { return map(map(list(map(list(data.get("references")).get(0)).get("revisions")).get(0)).get("snapshot")); }
    static void reseal(Map<String,Object> data) {
        for(Object raw:list(data.get("references"))) {
            String previous="";
            for(Object rawEvent:list(map(raw).get("revisions"))) {
                Map<String,Object> event=map(rawEvent);event.put("previous_hash",previous);
                previous=CityTravelReference.eventHash(event);event.put("event_sha256",previous);
            }
        }
    }
    static Map<String,Object> content(String mode,boolean reverse,int minutes,String scope) {
        return m("from_province",reverse?TP:FP,"from_city",reverse?TC:FC,"to_province",reverse?FP:TP,"to_city",reverse?FC:TC,
            "mode",mode,"scope",scope,"reference_minutes",minutes,"research_on",RESEARCH.toString(),
            "source_url","https://example.test/gray-city-reference/"+mode+"/"+reverse,"source_provider","【灰度测试】虚构授权资料",
            "original_content","【灰度测试】本内容和分钟数均为合成，不是任何真实路线。","evidence_type","authorized_schedule_research",
            "usage_authorized",true,"business_import_allowed",true,"authorization_ref","GRAY-FIXTURE-NOT-A-REAL-LICENSE","city_mapping_checked",true,
            "nonstop",true,"service_type","passenger");
    }
    static Map<String,Object> addEvidence(Map<String,Object> data,String id,Map<String,Object> content,int minutes) {
        String hash=CityTravelReference.sha256(content);
        list(data.get("evidence_snapshots")).add(m("evidence_id",id,"sha256",hash,"content",content));
        return m("evidence_id",id,"source_sha256",hash,"minutes",minutes);
    }
    static Map<String,Object> fixture(String mode,int out,int back) {
        Map<String,Object> data=m("version",CityTravelReference.SCHEMA,"library_revision",1,"usage_authorized",true,"business_import_allowed",true,
            "authorization_ref","GRAY-LIBRARY-NOT-A-REAL-LICENSE","evidence_snapshots",new ArrayList<>(),"references",new ArrayList<>());
        String scope=mode.equals("rail")?"city_station_to_station":"city_airport_to_airport_nonstop";
        Map<String,Object> ref=m("reference_id","gray-nanjing-hefei","version",1,"from_province",FP,"from_city",FC,"to_province",TP,"to_city",TC,
            "mode",mode,"review_status","approved","reviewer_type","human","reviewer_id","GRAY-SYNTHETIC-NOT-A-REAL-REVIEWER",
            "review_method","synthetic_test_fixture_only","reviewed_on",REVIEW.toString(),"research_on",RESEARCH.toString(),"review_due_on",REVIEW.plusDays(90).toString(),
            "applicable_scope","pre_bid_city_catchment","usage_authorized",true,"business_import_allowed",true,"authorization_ref","GRAY-REFERENCE-NOT-A-REAL-LICENSE");
        ref.put("outbound",addEvidence(data,"gray-out",content(mode,false,out,scope),out));
        ref.put("return",addEvidence(data,"gray-return",content(mode,true,back,scope),back));
        if(mode.equals("air")) {
            Map<String,Object> proof=new LinkedHashMap<>();
            for(boolean reverse:List.of(false,true)) {
                Map<String,Object> raw=content("rail",reverse,300,"all_city_stations_and_rail_itineraries");
                raw.put("coverage_complete",true);raw.put("minimum_minutes",300);raw.put("no_service",false);
                proof.put(reverse?"return":"outbound",addEvidence(data,"gray-rail-proof-"+reverse,raw,300));
            }
            ref.put("rail_exclusion",proof);
        }
        Map<String,Object> event=m("snapshot",ref,"previous_hash","","change_reason","【灰度测试】初始合成审核记录");
        list(data.get("references")).add(m("reference_id",ref.get("reference_id"),"revisions",new ArrayList<>(List.of(event))));reseal(data);return data;
    }
    static void rehashEvidence(Map<String,Object> data,int index) {
        Map<String,Object> evidence=map(list(data.get("evidence_snapshots")).get(index));String old=(String)evidence.get("sha256");
        String changed=CityTravelReference.sha256(evidence.get("content"));evidence.put("sha256",changed);
        replaceHashes(data.get("references"),old,changed);reseal(data);
    }
    static void replaceHashes(Object value,String old,String changed) {
        if(value instanceof Map<?,?>) {
            Map<String,Object> row=map(value);if(old.equals(row.get("source_sha256")))row.put("source_sha256",changed);
            for(Object child:row.values())replaceHashes(child,old,changed);
        } else if(value instanceof List<?>)for(Object child:(List<?>)value)replaceHashes(child,old,changed);
    }
    static Map<String,Object> rawRail(boolean reverse,int minutes,LocalDate observed) {
        Map<String,Object> row=m("from_province",reverse?TP:FP,"from_city",reverse?TC:FC,"to_province",reverse?FP:TP,"to_city",reverse?FC:TC,
            "status","observed_timetable","observed_on",observed.toString(),"source_service_date",observed.toString(),"source_url","https://example.test/gray-newer-signal");
        row.put("samples",List.of(m("train_no",reverse?"G90002":"G90001","from_station","【灰度测试】甲站","to_station","【灰度测试】乙站",
            "departure_time","08:00","arrival_time",LocalTime.of(8,0).plusMinutes(minutes).toString(),"arrival_day_offset",0,"rail_minutes",minutes,"station_city_mapping_checked",true)));
        return row;
    }
    static DispatchPreference pref() { return DispatchPreference.from(Map.of("training_province",TP,"training_city",TC,"training_mode","线下"),null); }
    static Map<String,Object> lookup(Map<String,Object> data) { return CityTravelReference.lookup(data,FP,FC,TP,TC,TODAY); }
    static Map<String,Object> resolve(Map<String,Object> data,List<?> rails,Map<?,?> matrix) { return TravelMatrix.resolve(rails,matrix,data,Map.of("base_province",FP,"base_city",FC),pref(),TODAY); }
    static boolean is(Map<String,Object> row,String state) { return state.equals(row.get("eligibility")); }
    static void approvedAndBoundaries() {
        Map<String,Object> data=fixture("rail",100,120),result=lookup(data);
        check(is(result,"rail")&&"city_transport_reference".equals(result.get("status")),"long-term city reference works after raw 30-day validity would have expired");
        check(Boolean.FALSE.equals(result.get("transport_verified"))&&Boolean.FALSE.equals(result.get("travel_date_verified")),"reference is not verified trip or ticket data");
        check(REVIEW.toString().equals(result.get("reviewed_on"))&&RESEARCH.toString().equals(result.get("research_on")),"real synthetic research/review dates retained, never today");
        check(is(resolve(data,List.of(),Map.of()),"rail"),"explicit long-term bundle integrated into TravelMatrix");
        Map<String,Object> reverse=CityTravelReference.lookup(data,TP,TC,FP,FC,TODAY);
        check(Objects.equals(reverse.get("outbound_reference_minutes"),120)&&Objects.equals(reverse.get("return_reference_minutes"),100),"reverse lookup uses independently supported opposite leg");
        Map<String,Object> missing=fixture("rail",100,120);snapshot(missing).remove("return");reseal(missing);
        check(is(lookup(missing),"unknown"),"one direction cannot invent the other");
        for(int minutes:List.of(230,239,240,241))check(is(lookup(fixture("rail",minutes,100)),"unknown"),"long-term rail near boundary remains pending, not outside: "+minutes);
        check(is(lookup(fixture("rail",229,229)),"rail"),"long-term 229-minute rail outside the 10-minute review zone");
        check(is(lookup(fixture("air",169,169)),"air"),"complete railway exclusion and bilateral non-stop long-term air");
        for(int minutes:List.of(170,179,180,181))check(is(lookup(fixture("air",minutes,100)),"unknown"),"long-term air boundary remains review-pending: "+minutes);
        Map<String,Object> incomplete=fixture("air",100,100);map(map(list(incomplete.get("evidence_snapshots")).get(2)).get("content")).put("coverage_complete",false);rehashEvidence(incomplete,2);
        check(is(lookup(incomplete),"unknown"),"slow rail sample cannot become complete exclusion");
        Map<String,Object> noService=fixture("air",100,100);map(map(list(noService.get("evidence_snapshots")).get(2)).get("content")).put("no_service",true);rehashEvidence(noService,2);
        check(is(lookup(noService),"unknown"),"no service is not invented as rail longer than four hours");
        Map<String,Object> fresh=resolve(fixture("rail",239,239),List.of(rawRail(false,239,TODAY),rawRail(true,239,TODAY)),Map.of());
        check(is(fresh,"rail")&&"rail_time_reference".equals(fresh.get("status")),"recent strict 239-minute evidence may pass despite long-term boundary review");
        Map<String,Object> airRecent=m("rail_checks",new ArrayList<>(),"flights",new ArrayList<>());
        for(boolean reverseFlag:List.of(false,true)) {
            Map<String,Object> audit=rawRail(reverseFlag,300,TODAY);audit.put("coverage_complete",true);audit.put("minimum_minutes",300);audit.put("scope","all_city_stations_and_rail_itineraries");audit.put("usage_authorized",true);list(airRecent.get("rail_checks")).add(audit);
            Map<String,Object> f=copy(audit);f.putAll(m("flight_no","MU9001","from_airport_code",reverseFlag?"BBB":"AAA","to_airport_code",reverseFlag?"AAA":"BBB","nonstop",true,"stops",0,"airport_city_mapping_checked",true,
                "departure_time","08:00","arrival_time","10:59","arrival_day_offset",0,"flight_minutes",179,"service_type","passenger","service_mode","air","cancelled",false,"timezone","Asia/Shanghai"));list(airRecent.get("flights")).add(f);
        }
        check(is(resolve(fixture("air",179,179),List.of(),airRecent),"air"),"recent strict bilateral 179-minute flights retain existing eligibility rule");
    }
    static void reviewAndIntegrity() {
        for(Map<String,Object> change:List.of(m("reviewer_type","ai"),m("reviewer_type","unknown"),m("reviewer_id",""),m("review_method",""),
            m("review_status","pending"),m("review_status","revoked"),m("usage_authorized",false),m("business_import_allowed",false),m("research_only",true),
            m("authorization_ref",""),m("applicable_scope","ticket_availability"),m("reviewed_on",TODAY.plusDays(1).toString()),
            m("research_on",TODAY.plusDays(1).toString()),m("review_due_on",REVIEW.plusDays(181).toString()),m("review_due_on",TODAY.minusDays(1).toString()))) {
            Map<String,Object> data=fixture("rail",100,120);snapshot(data).putAll(change);reseal(data);
            check(is(lookup(data),"unknown"),"review metadata fail closed: "+change);
        }
        Map<String,Object> due=fixture("rail",100,120);snapshot(due).put("review_due_on",TODAY.toString());reseal(due);
        check(is(lookup(due),"rail"),"review due date inclusive");
        snapshot(due).remove("review_due_on");reseal(due);check(is(lookup(due),"rail"),"explicit default review policy is 90 days");
        Map<String,Object> tamper=fixture("rail",100,120);snapshot(tamper).put("reviewer_id","CHANGED-WITHOUT-RESEALING");
        check(is(lookup(tamper),"unknown"),"review metadata tampering changes audit digest");
        tamper=fixture("rail",100,120);map(map(list(tamper.get("evidence_snapshots")).get(0)).get("content")).put("reference_minutes",1);
        check(is(lookup(tamper),"unknown"),"source snapshot tampering changes source digest");
        for(Map<String,Object> change:List.of(m("source_url",""),m("source_url","https://user:secret@example.test/"),m("original_content",""),m("source_provider",""),
            m("research_on",TODAY.plusDays(1).toString()),m("research_on",REVIEW.minusDays(31).toString()),m("evidence_type","straight_line_estimate"),
            m("usage_authorized",false),m("authorization_ref",""),m("city_mapping_checked",false),m("from_city",TC))) {
            Map<String,Object> data=fixture("rail",100,120);map(map(list(data.get("evidence_snapshots")).get(0)).get("content")).putAll(change);rehashEvidence(data,0);
            check(is(lookup(data),"unknown"),"invalid evidence despite internally matching hashes: "+change);
        }
        Map<String,Object> duplicate=fixture("rail",100,120);list(duplicate.get("evidence_snapshots")).add(copy(map(list(duplicate.get("evidence_snapshots")).get(0))));
        check(is(lookup(duplicate),"unknown"),"duplicate source ID not arbitrarily selected");
        check(CityTravelReference.lookup(Map.of(),FP,FC,TP,TC,TODAY).isEmpty(),"empty library provides no invented route");
        check(is(CityTravelReference.lookup(fixture("rail",100,120),FP,"灰度未知城市",TP,TC,TODAY),"unknown"),"unknown city remains pending");
    }
    static void newerChanges() {
        Map<String,Object> data=fixture("rail",100,120);
        check(is(resolve(data,List.of(rawRail(false,100,TODAY)),Map.of()),"unknown"),"newer single-direction research requires updating the whole paired reference");
        check(is(resolve(data,List.of(rawRail(false,100,TODAY.plusDays(1))),Map.of()),"unknown"),"future-dated upstream research is a review blocker, not an excuse to revive older reference");
        check(is(resolve(data,List.of(rawRail(false,300,TODAY),rawRail(true,310,TODAY)),Map.of()),"unknown"),"new slow/changed samples cannot be overwritten by old approved reference");
        check(is(resolve(data,List.of(rawRail(false,100,TODAY.minusDays(1)),rawRail(true,120,TODAY.minusDays(1)),rawRail(false,310,TODAY)),Map.of()),"unknown"),"newer changed leg cannot be hidden by a faster older sample inside the recent window");
        check(is(resolve(data,List.of(rawRail(false,100,RESEARCH.minusDays(1))),Map.of()),"rail"),"older observations do not override a newer reviewed city reference");
        Map<String,Object> revoked=rawRail(false,100,TODAY);revoked.put("status","revoked");revoked.put("usage_authorized",false);
        check(is(resolve(data,List.of(revoked),Map.of()),"unknown"),"newer upstream revocation invalidates long-term fallback");
        Map<String,Object> undated=rawRail(false,100,TODAY);undated.remove("observed_on");
        check(is(resolve(data,List.of(undated),Map.of()),"unknown"),"undated changed upstream row cannot be safely ignored");
        Map<String,Object> checked=m("rail_checks",List.of(m("from_province",FP,"from_city",FC,"to_province",TP,"to_city",TC,"observed_on",TODAY.toString(),"no_service",true)));
        check(is(resolve(data,List.of(),checked),"unknown"),"latest negative upstream signal blocks stale favorable fallback without claiming complete exclusion");
        Map<String,Object> changed=copy(data);appendRevision(changed,"revoked");
        check(is(lookup(changed),"unknown"),"newest review revocation wins over older approval");
        Map<String,Object> broken=copy(changed);map(map(list(map(list(broken.get("references")).get(0)).get("revisions")).get(1)).get("snapshot")).put("version",3);reseal(broken);
        check(is(lookup(broken),"unknown"),"gaps in version history rejected");
    }
    static void appendRevision(Map<String,Object> data,String status) {
        List<Object> history=list(map(list(data.get("references")).get(0)).get("revisions"));Map<String,Object> latest=copy(map(map(history.get(history.size()-1)).get("snapshot")));
        latest.put("version",history.size()+1);latest.put("review_status",status);latest.put("reviewed_on",TODAY.toString());
        history.add(m("snapshot",latest,"previous_hash","","change_reason","【灰度测试】记录审核状态变更"));data.put("library_revision",history.size());reseal(data);
    }
    static Map<String,Object> candidate(long id,int score,String level,Map<String,Object> route) {
        return m("teacher_id",id,"name","【灰度测试】合成候选"+id,"model_score",score,"recommendation_score",score,"teacher_level",level,
            "dispatch_fit",m("local_priority",false,"route_reference",route));
    }
    static void selection() {
        Map<String,Object> rail=lookup(fixture("rail",100,120)),air=lookup(fixture("air",100,120));
        Map<String,Object> chosen=NearbySelection.select(List.of(candidate(1,80,"讲师",rail),candidate(2,80,"特聘讲师",rail),
            candidate(3,95,"讲师",rail),candidate(4,80,"高级讲师",rail),candidate(5,100,"特聘讲师",air)),pref(),3);
        check(list(chosen.get("selected")).stream().map(CityTravelReferenceTest::map).map(v->v.get("teacher_id")).toList().equals(List.of(3L,2L,4L,1L)),
            "city-reference rail pool ranks by match then grade, keeps all cutoff ties, and excludes displacing air");
        chosen=NearbySelection.select(List.of(candidate(1,30,"讲师",rail),candidate(2,90,"讲师",air),candidate(3,90,"特聘讲师",air),candidate(4,90,"高级讲师",air)),pref(),3);
        check(list(chosen.get("selected")).stream().map(CityTravelReferenceTest::map).map(v->v.get("teacher_id")).toList().equals(List.of(1L,3L,4L,2L)),
            "city-reference air is only a shortage fallback and its own score ties remain intact");
        Map<String,Object> denied=new LinkedHashMap<>(rail);denied.put("eligibility","unknown");
        check(list(NearbySelection.select(List.of(candidate(9,100,"特聘讲师",denied)),pref(),3).get("selected")).isEmpty(),"explicit city-reference unknown cannot enter by presentation status");
        System.setProperty("dispatch.nearby.max.minutes","0");
        try {check(list(NearbySelection.select(List.of(candidate(9,100,"特聘讲师",rail),candidate(10,100,"特聘讲师",air)),pref(),3).get("selected")).isEmpty(),"cross-city pause includes both new reference modes");}
        finally {System.setProperty("dispatch.nearby.max.minutes","240");}
    }
    static void write(Path path,Map<?,?> data) throws Exception { Files.writeString(path,Json.write(data));Files.setLastModifiedTime(path,FileTime.fromMillis(System.currentTimeMillis()+tick++*1000)); }
    static void filesAndCatalog(Path directory) throws Exception {
        Path file=directory.resolve("gray-city-library.json");Map<String,Object> v1=fixture("rail",100,120);write(file,v1);
        System.setProperty("dispatch.city.references.file",file.toString());
        check(is(CityTravelReference.lookup(CityTravelReference.bundle(TP,TC),FP,FC,TP,TC,TODAY),"rail"),"configured private library loads and verifies");
        check(Files.exists(file.resolveSibling(file.getFileName()+".integrity.json")),"local integrity anchor retains accepted revision independently");
        Map<String,Object> catalog=DispatchPriority.catalog(TP,TC,"2099-12-31",TODAY);
        check(list(catalog.get("priorities")).stream().map(CityTravelReferenceTest::map).anyMatch(r->FC.equals(r.get("city"))&&"reviewed_city_reference".equals(r.get("reference_basis"))),"all-city catalog uses reviewed reference independently of teachers and course date");
        int universe=RegionDirectory.CITIES.values().stream().mapToInt(Set::size).sum();
        check(Objects.equals(catalog.get("enumerated_count"),universe)&&Objects.equals(catalog.get("unknown_count"),universe-2)&&Boolean.FALSE.equals(catalog.get("transport_scope_complete")),"one reviewed pair does not claim whole-country coverage");
        Set<String> original=RegionDirectory.CITIES.get(TP);
        try {
            Set<String> expanded=new LinkedHashSet<>(original);expanded.add("灰度新增城市");RegionDirectory.CITIES.put(TP,expanded);
            check(Objects.equals(DispatchPriority.catalog(TP,TC,"",TODAY).get("enumerated_count"),universe+1),"directory expansion is independent of teacher roster and reference coverage");
        } finally { RegionDirectory.CITIES.put(TP,original); }
        Map<String,Object> v2=copy(v1);appendRevision(v2,"revoked");write(file,v2);
        check(is(CityTravelReference.lookup(CityTravelReference.bundle(TP,TC),FP,FC,TP,TC,TODAY),"unknown"),"append-only revoked version loads without falling back to v1");
        write(file,v1);check("reference_library_unavailable".equals(CityTravelReference.bundle(TP,TC).get("load_status")),"whole-file library rollback rejected against retained anchor");
        Map<String,Object> tamper=copy(v2);snapshot(tamper).put("reviewer_id","GRAY-REWRITE-PAST");reseal(tamper);tamper.put("library_revision",3);write(file,tamper);
        check("reference_library_unavailable".equals(CityTravelReference.bundle(TP,TC).get("load_status")),"higher bundle version cannot rewrite anchored past history");
        Map<String,Object> same=copy(v2);same.put("authorization_ref","GRAY-CHANGED-SAME-VERSION");write(file,same);
        check("reference_library_unavailable".equals(CityTravelReference.bundle(TP,TC).get("load_status")),"same revision cannot silently change metadata");
        write(file,v2);check(CityTravelReference.bundle(TP,TC).get("version").equals(CityTravelReference.SCHEMA),"unchanged anchored library can recover after rejected edits");
        Files.delete(file.resolveSibling(file.getFileName()+".integrity.json"));
        check("reference_library_unavailable".equals(CityTravelReference.bundle(TP,TC).get("load_status")),"removing anchor cannot reset live process rollback guard");
        System.setProperty("dispatch.city.references.dir",directory.toString());
        check("reference_library_unavailable".equals(CityTravelReference.bundle("广东","广州").get("load_status")),"missing destination shard cannot borrow previous city's data");
        System.setProperty("dispatch.city.references.dir","");System.setProperty("dispatch.city.references.file","");
        check(CityTravelReference.bundle(TP,TC).isEmpty(),"unconfigured library does not resurrect cached data");
    }
    public static void main(String[] args) throws Exception {
        Map<String,String> settings=new LinkedHashMap<>();
        for(String key:List.of("dispatch.timetable.file","dispatch.transport.file","dispatch.transport.dir","dispatch.routes.file","dispatch.organizations.file",
            "dispatch.city.references.file","dispatch.city.references.dir","dispatch.nearby.max.minutes")) {
            settings.put(key,System.getProperty(key));System.setProperty(key,key.endsWith("max.minutes")?"240":"");
        }
        Path directory=Files.createTempDirectory("yanxu-gray-city-reference-");
        try { approvedAndBoundaries();reviewAndIntegrity();newerChanges();selection();filesAndCatalog(directory); }
        finally {
            settings.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
            try(var files=Files.list(directory)){for(Path path:files.toList())Files.deleteIfExists(path);}Files.deleteIfExists(directory);
        }
        System.out.println("Synthetic reviewed city references: "+checks+" passed");
    }
}
