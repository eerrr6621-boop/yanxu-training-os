package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static com.training.CuratedPlanningIntegrationTest.*;

/** Synthetic integration tests. A real-source fixture, if supplied, is a separate
 * local artifact with actual source/read-policy records, not these synthetic actors. */
public final class SharedCityDurationTest {
    static final LocalDate DAY=LocalDate.of(2026,9,8);
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static Map<String,Object> span(String id,String raw,String token){int at=raw.indexOf(token);if(at<0)throw new IllegalArgumentException("missing fixture token "+token);return obj("unit_id",id,"start",at,"end",at+token.length(),"text",token);}
    static Map<String,Object> unit(String id,String raw){return obj("unit_id",id,"text",raw,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(raw));}
    static Map<String,Object> fixture(){return fixture("北京通州站往返秦皇岛的列车，单程运行时长约90分钟。","北京通州站是北京城市副中心的车站。","https://example.invalid/shared",DAY.toString());}
    static Map<String,Object> fixture(String raw,String subcentre,String url,String published){
        Map<String,Object> a=CuratedCityDurationTest.city("北京","北京"),b=CuratedCityDurationTest.city("河北","秦皇岛");
        Map<String,Object> data=obj("version",CityPlanningReference.SHARED_SCHEMA,"library_revision",1,"use_basis","independently_curated_public_facts","license_claimed",false,
            "local_planning_import_allowed",true,"article_republication_allowed",false,"strict_runtime_admission",false,"purpose","prebid_city_planning_only",
            "policy",obj("buffer_minutes",30,"buffer_basis","planning_assumption_not_source_error_bound"),"documents",new LinkedHashMap<>(),"synthetic_only",true);
        putDoc(data,"source",obj("source_id","shared-source","url",url,"published_on",published,"synthetic_only",true,"units",List.of(unit("u1",raw),unit("u2",subcentre))));
        putDoc(data,"packet",obj("created_on",DAY.toString(),"sources",List.of(obj("source_id","shared-source","url",url,"file_sha256",m(m(data.get("documents")).get("source")).get("raw_sha256")))));
        Map<String,Object> canonical=obj("verification_method",SharedCityDuration.METHOD,"source_id","shared-source","city_a_id",a.get("id"),"city_b_id",b.get("id"),
            "endpoint_a","北京通州站","endpoint_b","秦皇岛","endpoint_scope",obj("a","urban_subcentre_station","b","city_summary"),"mapping_basis",obj("a","literal_city_prefix","b","literal_city_prefix"),
            "transport_mode","rail","route_scope","station_corridor","published_on",published,"effective_on",null,"effective_until",null,"service_state","reported_operating","status","active",
            "direction_semantics","explicit_roundtrip_context","duration_scope","single_journey_shared_summary","duration",obj("kind","approximate","value",90,"unit","minute","token","约90分钟"));
        Map<String,Object> fact=obj("source_id","shared-source","city_a","北京","city_b","秦皇岛","canonical",canonical,"rationale","Synthetic semantic annotations, no real reader claim", "spans",
            obj("endpoint_a",List.of(span("u1",raw,"北京通州站")),"endpoint_b",List.of(span("u1",raw,"秦皇岛")),"city_context_a",List.of(span("u2",subcentre,subcentre)),"city_context_b",List.of(span("u1",raw,"秦皇岛")),
                "context",List.of(span("u1",raw,raw)),"roundtrip_context",List.of(span("u1",raw,raw)),"duration",List.of(span("u1",raw,"约90分钟"))));
        for(String role:List.of("A","B"))putDoc(data,role,obj("reviewer",obj("role",role,"actor_type","assistant","agent_id","synthetic-shared-"+role,"run_id","synthetic-shared-run-"+role,"background_context_prior_research",true,"prior_A_result_access",false,"fresh_blind_claimed",false),
            "source_packet_sha256",m(m(data.get("documents")).get("packet")).get("raw_sha256"),"read_events",List.of(obj("source_id","shared-source","url",url,"result","original_read","recorded_at",null,"record_locator","SYNTHETIC, no original reading claimed")),
            "source_reviews",List.of(obj("source_id","shared-source","reuse_state","no_explicit_prohibition_observed","rationale","Synthetic only")),"shared_facts",List.of(copy(fact)),"synthetic_only",true));
        Map<String,Object> reference=obj("packet_document","packet","source_document","source","review_a","A","review_b","B","source_policy",policy());
        Map<String,Object> snapshot=obj("reference_id","shared-tongzhou-qinhuangdao","version",1,"reference_kind",SharedCityDuration.KIND,"from",a,"to",b,"status","active","reviewed_on",DAY.toString(),"shared_reference",reference);
        data.put("references",new ArrayList<>(List.of(obj("reference_id",snapshot.get("reference_id"),"revisions",new ArrayList<>(List.of(obj("snapshot",snapshot,"previous_hash","","change_reason","Synthetic shared-reference contract test")))))));rehash(data);return data;
    }
    static void bothFacts(Map<String,Object> data,Consumer<Map<String,Object>> edit){for(String role:List.of("A","B"))editDoc(data,role,r->edit.accept(m(l(r.get("shared_facts")).get(0))));}
    static void bothCore(Map<String,Object> data,Consumer<Map<String,Object>> edit){bothFacts(data,f->edit.accept(m(f.get("canonical"))));}
    static Map<String,Object> query(Map<?,?> data,LocalDate day){return CityPlanningReference.lookup(data,"北京","北京","河北","秦皇岛",day);}
    static void reject(Map<String,Object> original,String name,Consumer<Map<String,Object>> edit){Map<String,Object> data=copy(original);edit.accept(data);rehash(data);try{CityPlanningReference.anchor(data);throw new AssertionError("accepted "+name);}catch(IllegalArgumentException expected){checks++;}}
    static Path prepareAt(Map<?,?> data,Path root,String name)throws Exception{Path file=root.resolve(name+".json");CityPlanningReference.prepare(data,file,DAY);check(!CityPlanningReference.load(file).containsKey("load_status"),"actual load "+name);return file;}
    static void noAdmission(Map<String,Object> value){
        check(!value.containsKey("outbound")&&!value.containsKey("inbound")&&!value.containsKey("selection_reference")&&!value.containsKey("planning_band_id"),"no two-direction or selection representation");
        for(String flag:List.of("strict_eligibility","transport_verified","time_score_applicable","air_fallback_trigger","rail_exclusion_complete"))check(Boolean.FALSE.equals(value.get(flag)),"no capability "+flag);
        check(!CityPlanningSelection.underConfiguredLimit(value,240),"shared not selection input");
    }
    public static void main(String[] args)throws Exception{
        Path root=args.length==0?Files.createTempDirectory("shared-city-tests-"):Files.createDirectory(Path.of(args[0]));Map<String,Object> base=fixture();
        Path file=prepareAt(base,root,"shared");Map<?,?> loaded=CityPlanningReference.load(file);var index=CityPlanningReference.index(file,DAY);
        Map<String,Object> value=index.lookup("北京","北京","河北","秦皇岛");check(SharedCityDuration.STATUS.equals(value.get("status")),"shared status");noAdmission(value);
        check(index.directionCount()==0&&index.sharedPairCount()==1,"one shared pair, zero directions");
        check(value==index.lookup("河北","秦皇岛","北京","北京"),"inverse lookup aliases same immutable shared fact, not a mirror observation");
        check(CityPlanningReference.same(value,query(loaded,DAY)),"direct/index parity");
        check(index.buildMetrics().get("shared_fact_validations")==1&&index.buildMetrics().get("direction_fact_validations")==0,"one protected shared validation");
        check(m(m(value.get("shared_reference")).get("duration")).get("kind").equals("approximate"),"approximation retained");
        check(m(m(value.get("shared_reference")).get("endpoint_scope")).get("a").equals("urban_subcentre_station"),"subcentre preserved");
        try{m(m(value.get("shared_reference")).get("duration")).put("kind","reported_minutes");throw new AssertionError("mutable shared");}catch(UnsupportedOperationException expected){checks++;}
        var observation=CityPlanningReference.observe(()->index.lookup("河北","秦皇岛","北京","北京"));check(observation.metrics().values().stream().allMatch(n->n==0),"lookup no I/O or extra validation");
        for(String text:List.of("北京通州站至秦皇岛的列车，单程运行时长约90分钟。","北京通州站与秦皇岛两地最快约90分钟。","北京通州站往返秦皇岛的列车，往返总计约90分钟。"))reject(fixture(text,"北京通州站是北京城市副中心的车站。","https://example.invalid/shared",DAY.toString()),"not explicit shared single journey",d->{});
        for(String key:List.of("outbound","inbound","selection_reference"))reject(base,"snapshot smuggles "+key,d->snapshot(d).put(key,Map.of()));
        reject(base,"shared in v1",d->d.put("version",CityPlanningReference.SCHEMA));
        reject(base,"unknown kind",d->snapshot(d).put("reference_kind","two_cities_fastest"));
        reject(base,"missing shared cannot fall back",d->{snapshot(d).remove("shared_reference");snapshot(d).put("outbound",Map.of());snapshot(d).put("inbound",Map.of());});
        reject(base,"two values in ref",d->m(snapshot(d).get("shared_reference")).put("outbound",90));
        for(String mode:List.of("single_direction","two_places_fastest","shared_pair_summary"))reject(base,mode,d->bothCore(d,c->c.put("direction_semantics",mode)));
        reject(base,"total duration",d->bothCore(d,c->c.put("duration_scope","roundtrip_total")));
        reject(base,"source date renewed",d->bothCore(d,c->c.put("published_on","2026-09-09")));
        reject(base,"main centre promoted",d->bothCore(d,c->m(c.get("endpoint_scope")).put("a","main_urban_station")));
        for(String scope:List.of("county","unknown"))reject(base,scope,d->bothCore(d,c->m(c.get("endpoint_scope")).put("b",scope)));
        reject(base,"city changed into station",d->bothCore(d,c->c.put("endpoint_b","秦皇岛站")));
        reject(base,"fake exact",d->bothCore(d,c->m(c.get("duration")).put("kind","reported_minutes")));
        reject(base,"stripped approximation",d->bothFacts(d,f->{Map<String,Object> duration=m(m(f.get("canonical")).get("duration"));duration.put("kind","reported_minutes");duration.put("token","90分钟");Map<String,Object> s=m(l(m(f.get("spans")).get("duration")).get(0));s.put("start",((Number)s.get("start")).intValue()+1);s.put("text","90分钟");}));
        reject(base,"disagreeing reader",d->editDoc(d,"B",r->m(m(l(r.get("shared_facts")).get(0)).get("canonical")).put("endpoint_a","北京站")));
        reject(base,"same agent",d->editDoc(d,"B",r->m(r.get("reviewer")).put("agent_id","synthetic-shared-A")));
        reject(base,"same run",d->editDoc(d,"B",r->m(r.get("reviewer")).put("run_id","synthetic-shared-run-A")));
        reject(base,"no read event",d->editDoc(d,"B",r->r.put("read_events",List.of())));
        reject(base,"false blind",d->editDoc(d,"B",r->{m(r.get("reviewer")).put("prior_A_result_access",true);m(r.get("reviewer")).put("fresh_blind_claimed",true);}));
        reject(base,"wrong packet binding",d->editDoc(d,"B",r->r.put("source_packet_sha256","0".repeat(64))));
        reject(base,"raw SHA",d->m(m(d.get("documents")).get("A")).put("raw_sha256","0".repeat(64)));
        reject(base,"source policy prohibition",d->m(m(m(snapshot(d).get("shared_reference")).get("source_policy")).get("external_restrictions")).put("minimal_fact_reuse_prohibited",true));
        reject(base,"cross source segment",d->bothFacts(d,f->m(l(m(f.get("spans")).get("roundtrip_context")).get(0)).put("unit_id","u2")));
        Map<String,Object> informed=copy(base);editDoc(informed,"B",r->{m(r.get("reviewer")).put("prior_A_result_access",true);m(r.get("reviewer")).put("review_mode","informed_original_source_crosscheck");});
        check(Boolean.TRUE.equals(m(query(informed,DAY).get("shared_reference")).get("informed_source_crosscheck")),"informed B not relabelled blind");
        check("source_or_review_stale".equals(query(loaded,DAY.plusDays(91)).get("reason")),"review expiry");
        Map<String,Object> aged=fixture("北京通州站往返秦皇岛的列车，单程运行时长约90分钟。","北京通州站是北京城市副中心的车站。","https://example.invalid/shared",DAY.minusDays(364).toString());
        check(SharedCityDuration.STATUS.equals(query(aged,DAY.plusDays(1)).get("status")),"source365 inclusive last day");
        check("source_or_review_stale".equals(query(aged,DAY.plusDays(2)).get("reason")),"source365 not renewed by fresh review");
        check("future_metadata".equals(query(loaded,DAY.minusDays(1)).get("reason")),"future read/source rejected at lookup");
        Map<String,Object> future=copy(base);bothCore(future,c->c.put("effective_on",DAY.plusDays(1).toString()));check("not_yet_effective".equals(query(future,DAY).get("reason")),"future effectiveness");
        Map<String,Object> ended=copy(base);bothCore(ended,c->{c.put("effective_on",DAY.minusDays(2).toString());c.put("effective_until",DAY.minusDays(1).toString());});check("source_period_ended".equals(query(ended,DAY).get("reason")),"ended service not indefinite");
        reject(base,"review date cannot renew without reads",d->{snapshot(d).put("reviewed_on",DAY.plusDays(1).toString());m(m(snapshot(d).get("shared_reference")).get("source_policy")).put("checked_on",DAY.plusDays(1).toString());});
        Map<String,Object> withdrawn=revoked(base);CityPlanningReference.continuation(CityPlanningReference.anchor(base),withdrawn);
        check("reference_revoked".equals(query(withdrawn,DAY.plusDays(100)).get("reason")),"expired history can be revoked");
        try{CityPlanningReference.continuation(CityPlanningReference.anchor(withdrawn),base);throw new AssertionError("rollback");}catch(IllegalArgumentException expected){checks++;}
        Map<String,Object> rewritten=copy(withdrawn);m(l(m(l(rewritten.get("references")).get(0)).get("revisions")).get(0)).put("change_reason","rewritten old review");rehash(rewritten);
        try{CityPlanningReference.continuation(CityPlanningReference.anchor(base),rewritten);throw new AssertionError("history rewrite");}catch(IllegalArgumentException expected){checks++;}
        Path corrupt=root.resolve("corrupt-anchor.json");CityPlanningReference.prepare(base,corrupt,DAY);Files.writeString(Path.of(corrupt+".integrity.json"),"{}");check(CityPlanningReference.load(corrupt).containsKey("load_status"),"shared external anchor enforced");
        try{CityPlanningReference.prepare(base,file,DAY);throw new AssertionError("shared existing target overwritten");}catch(IllegalArgumentException expected){checks++;}
        Map<String,Object> forged=copy(value);forged.put("status","planning_reference");forged.put("planning_band_id","planning_0_1h");
        forged.put("selection_reference",CityPlanningReference.selectionReference(Map.of("precision","reported_minutes","reference_value_minutes_internal",90),Map.of("precision","reported_minutes","reference_value_minutes_internal",90)));
        check(!CityPlanningSelection.underConfiguredLimit(forged,240),"forged coarse/typed selection fails closed");
        DispatchPreference pref=DispatchPreference.from(Map.of("training_province","河北","training_city","秦皇岛","training_mode","线下"),null);
        check(!CityPlanningSelection.include(obj("local_priority",false,"planning_reference",forged,"route_reference",CityPlanningSelectionTest.missing()),pref,240),"no shared planning inclusion");
        Path legacy=prepareAt(CityPlanningCollectionTest.fixture("legacy-","江苏","南京","上海","上海"),root,"legacy");Path collection=root.resolve("mixed.json");
        CityPlanningCollection.prepare("mixed-shared",new LinkedHashMap<>(Map.of("legacy",legacy,"shared",file)),null,collection,DAY);var mixed=CityPlanningReference.collectionIndex(collection,DAY);
        check(mixed.directionCount()==2&&mixed.sharedPairCount()==1,"mixed types counted separately");
        check("planning_reference".equals(mixed.lookup("江苏","南京","上海","上海").get("status")),"legacy sibling unchanged");noAdmission(mixed.lookup("北京","北京","河北","秦皇岛"));
        Path duplicate=prepareAt(CityPlanningCollectionTest.fixture("overlap-","北京","北京","河北","秦皇岛"),root,"duplicate");Path conflicts=root.resolve("conflicts.json");
        CityPlanningCollection.prepare("conflicts-shared",Map.of("legacy",duplicate,"shared",file),null,conflicts,DAY);
        var conflict=CityPlanningReference.collectionIndex(conflicts,DAY);check("collection_duplicate_city_pair".equals(conflict.lookup("河北","秦皇岛","北京","北京").get("reason")),"cross-type duplicate blocked");
        Path rev=prepareAt(withdrawn,root,"revoked");Path revCollection=root.resolve("rev-conflict.json");CityPlanningCollection.prepare("rev-shared",Map.of("legacy",duplicate,"shared",rev),null,revCollection,DAY);
        check("collection_cross_library_revocation_conflict".equals(CityPlanningReference.collectionIndex(revCollection,DAY).lookup("北京","北京","河北","秦皇岛").get("reason")),"cross-type revocation wins");
        Files.writeString(file,Files.readString(file)+"\n");var broken=CityPlanningReference.collectionIndex(collection,DAY);check(broken.directionCount()==0&&broken.sharedPairCount()==0,"bad shared member invalidates whole collection");
        Files.writeString(root.resolve("receipt.json"),Json.write(obj("checks",checks,"synthetic_only",true,"production_changed",false,"real_source_claimed",false))+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("Shared city duration: "+checks+" assertions passed; synthetic protected prepare/load/index/collection; "+root);
    }
}
