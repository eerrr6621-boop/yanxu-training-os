package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.training.CuratedCityDurationTest.*;

/** Synthetic advance-date samples. No reader identity, live train or geography claim. */
public final class PublicUiDateBasisTest {
    static final String V2="query_and_service_day_v2";
    static int checks;
    static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void rejects(Runnable task,String reason){try{task.run();throw new AssertionError("accepted "+reason);}catch(IllegalArgumentException ex){ok(ex.getMessage().contains(reason),reason+": "+ex.getMessage());}}
    static CuratedCityDuration.Direction dated(CuratedCityDuration.Direction input,LocalDate query,LocalDate service,String kind){
        var source=copy(input.source());var basis=m("kind",kind,"query_date",query.toString(),"service_date",service.toString());source.put("date_basis",basis);
        PublicUiStopTableTest.editCell(source,"date",service.toString());
        var a=copy(input.readA());var b=copy(input.readB());
        for(var fact:List.of(a,b)){
            c(fact).put("date_basis",copy(basis));c(fact).put("effective_on",service.toString());
            mutable(fact.get("spans")).put("service_date",List.of(PublicUiNamedStationTest.role("date",service.toString(),service.toString())));
        }
        return new CuratedCityDuration.Direction(source,a,b,input.from(),input.to(),input.reviewedOn());
    }
    static CuratedCityDuration.Direction advance(boolean reverse,int days){return dated(PublicUiNamedStationTest.sample(reverse),TODAY,TODAY.plusDays(days),V2);}
    static Map<String,Object> bundle(int days){return CuratedPlanningIntegrationTest.bundle(advance(false,days),advance(true,days));}
    static Map<String,Object> lookup(Map<?,?> data,LocalDate date,boolean reverse){return CityPlanningReference.lookup(data,"山东",reverse?"青岛":"济南","山东",reverse?"济南":"青岛",date);}
    public static void main(String[] args)throws Exception{
        Path dir=(args.length==0?Files.createTempDirectory("ui-date-synthetic-"):Files.createDirectory(Path.of(args[0]))).toRealPath();
        List<Object> rendering=new ArrayList<>();
        for(int days:List.of(0,1,14)){
            var data=bundle(days);String original=Json.write(data);Path target=dir.resolve("day-"+days+".json");
            CityPlanningReference.prepare(data,target,TODAY);var loaded=CityPlanningReference.load(target);
            ok(!loaded.containsKey("load_status"),"advance sample protected load, days="+days);
            ok(original.equals(Json.write(data))&&Files.readString(target).equals(original+"\n"),"unmodified observed input and persisted bytes");
            for(boolean back:List.of(false,true)){
                var result=lookup(loaded,TODAY,back);ok("planning_reference".equals(result.get("status")),"usable for planning on query day");
                rendering.add(m("kind","named","days",days,"reverse",back,"fit",m("planning_included",true,"planning_reference",result)));
                var leg=mutable(result.get("outbound"));
                ok(V2.equals(leg.get("source_date_basis")),"explicit date version retained");
                ok(TODAY.toString().equals(leg.get("source_query_date")),"query date retained");
                ok(TODAY.plusDays(days).toString().equals(leg.get("source_service_date")),"service date retained");
                ok(TODAY.plusDays(days).toString().equals(leg.get("effective_on")),"original selected service date not overwritten");
                ok(leg.get("source_published_on")==null,"no invented publication date");
                ok(TODAY.plusDays(90).toString().equals(leg.get("review_due_on")),"future service does not extend source freshness");
                for(String flag:List.of("transport_verified","time_score_applicable","air_fallback_trigger","rail_exclusion_complete"))ok(Boolean.FALSE.equals(result.get(flag)),"no stronger claim "+flag);
            }
            ok("planning_reference".equals(lookup(loaded,TODAY.plusDays(90),false).get("status")),"query +90 boundary");
            var expired=lookup(loaded,TODAY.plusDays(91),false);
            ok("source_or_review_stale".equals(expired.get("reason")),"expires from query date, not future service");
            ok(Boolean.FALSE.equals(expired.get("air_fallback_trigger")),"expired sample is not air evidence");
            ok("planning_pending".equals(lookup(loaded,TODAY.minusDays(1),false).get("status")),"not available before query");
            var pair=CuratedCityDuration.pairAfterEnvelopeValidation(advance(false,days),advance(true,days),TODAY);
            ok("curated_planning_values".equals(pair.get("status")),"component and outer timing agree");
        }
        rejects(()->PublicUiCitySample.dateBasis(m("kind",PublicUiCitySample.DATE_BASIS,"query_date",TODAY.toString(),"service_date",TODAY.plusDays(1).toString())),"same_day_sample_required");
        for(int delta:List.of(-1,15))rejects(()->PublicUiCitySample.dateBasis(m("kind",V2,"query_date",TODAY.toString(),"service_date",TODAY.plusDays(delta).toString())),"query_service_window");
        rejects(()->PublicUiCitySample.dateBasis(m("kind",V2,"query_date","2026-02-30","service_date","2026-03-01")),"date");
        rejects(()->PublicUiCitySample.dateBasis(m("kind",V2+"_unknown","query_date",TODAY.toString(),"service_date",TODAY.toString())),"date_basis_invalid");
        rejects(()->PublicUiCitySample.dateBasis(m("kind",null,"query_date",TODAY.toString(),"service_date",TODAY.toString())),"date_basis_invalid");
        rejects(()->PublicUiCitySample.dateBasis(m("kind",V2,"query_date",TODAY.toString(),"service_date",TODAY.toString(),"published_on",null)),"date_basis_invalid");
        ok(PublicUiCitySample.dateBasis(m("kind",V2,"query_date","2024-02-28","service_date","2024-03-01")).equals(LocalDate.of(2024,3,1)),"leap-year date arithmetic");
        var different=advance(false,1);
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(both(different,f->mutable(c(f).get("date_basis")).put("query_date",TODAY.minusDays(1).toString()))),"method_or_date_basis_mismatch");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(source(different,s->PublicUiStopTableTest.editCell(s,"date",TODAY.toString()))),"curated_span_mismatch");
        var visibleMismatch=source(both(different,f->mutable(f.get("spans")).put("service_date",List.of(PublicUiNamedStationTest.role("date",TODAY.toString(),TODAY.toString())))),s->PublicUiStopTableTest.editCell(s,"date",TODAY.toString()));
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(visibleMismatch),"visible_service_date_mismatch");
        var fabricated=bundle(1);
        CuratedPlanningIntegrationTest.editDoc(fabricated,"A",v->mutable(v.get("reviewer")).put("agent_id","synthetic-agent-B"));
        rejects(()->CityPlanningReference.anchor(fabricated),"second_independent_reader_missing");
        var missing=bundle(1);CuratedPlanningIntegrationTest.editDoc(missing,"B",v->v.put("read_events",List.of()));
        rejects(()->CityPlanningReference.anchor(missing),"original_read_event_missing");
        var backdated=CuratedPlanningIntegrationTest.bundle(dated(PublicUiNamedStationTest.sample(false),TODAY.minusDays(1),TODAY,V2),dated(PublicUiNamedStationTest.sample(true),TODAY.minusDays(1),TODAY,V2));
        rejects(()->CityPlanningReference.anchor(backdated),"query_date_not_bound_to_original_read");
        var lateReview=bundle(14);
        CuratedPlanningIntegrationTest.editDoc(lateReview,"B",v->{for(Object e:(List<?>)v.get("read_events"))mutable(e).put("recorded_at",TODAY.plusDays(1)+"T04:00:00Z");});
        var lateSnapshot=CuratedPlanningIntegrationTest.snapshot(lateReview);lateSnapshot.put("reviewed_on",TODAY.plusDays(1).toString());
        for(String side:List.of("outbound","inbound"))mutable(mutable(lateSnapshot.get(side)).get("source_policy")).put("checked_on",TODAY.plusDays(1).toString());
        CuratedPlanningIntegrationTest.rehash(lateReview);
        Path latePath=dir.resolve("later-review.json");CityPlanningReference.prepare(lateReview,latePath,TODAY.plusDays(1));
        var lateResult=lookup(CityPlanningReference.load(latePath),TODAY.plusDays(1),false);
        ok("planning_reference".equals(lateResult.get("status")),"different actual read days retain first observation");
        ok(TODAY.plusDays(90).toString().equals(mutable(lateResult.get("outbound")).get("review_due_on")),"later reviewer cannot prolong observation freshness");
        var zone=bundle(1);
        for(String who:List.of("A","B"))CuratedPlanningIntegrationTest.editDoc(zone,who,v->{for(Object e:(List<?>)v.get("read_events"))mutable(e).put("recorded_at",TODAY.minusDays(1)+"T16:00:00Z");});
        ok(CityPlanningReference.anchor(zone)!=null,"read timestamp converted to Shanghai date");
        // A genuine advance capture is still rejected when the source prohibits reuse.
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(source(different,s->s.put("access_restricted",true))),"original_source_restriction");
        // The separate six-column stop table continues to bind the displayed service date.
        var stopOut=dated(PublicUiStopTableTest.direction(false),TODAY,TODAY.plusDays(1),V2);
        var stopBack=dated(PublicUiStopTableTest.direction(true),TODAY,TODAY.plusDays(1),V2);
        var stopData=CuratedPlanningIntegrationTest.bundle(stopOut,stopBack);Path stopPath=dir.resolve("stop-table.json");
        CityPlanningReference.prepare(stopData,stopPath,TODAY);
        var stopResult=CityPlanningReference.lookup(CityPlanningReference.load(stopPath),"江苏","南京","安徽","滁州",TODAY);
        ok("planning_reference".equals(stopResult.get("status")),"stop-table advance capture supported");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(source(stopOut,s->PublicUiStopTableTest.editCell(s,"date",TODAY.toString()))),"visible_date_mismatch");
        Path collection=dir.resolve("collection.json");
        CityPlanningCollection.prepare("synthetic-date-v2",Map.of("synthetic-stop",stopPath),null,collection,TODAY);
        var annotations=PublicUiStopTableTest.annotations(stopPath,stopData,collection);
        for(Object raw:(List<?>)annotations.get("rows")){
            var row=mutable(raw);var originalSource=CuratedPlanningIntegrationTest.doc(stopData,(String)row.get("source_document"));
            row.put("date_basis",copy(originalSource.get("date_basis")));
        }
        var catalog=PublicUiStopTableTest.view(dir,"date-display",annotations);
        for(boolean back:List.of(false,true)){
            var displayed=CityPlanningReference.collectionIndex(collection,TODAY,catalog).lookup(back?"安徽":"江苏",back?"滁州":"南京",back?"江苏":"安徽",back?"南京":"滁州");
            rendering.add(m("kind","stop_table","days",1,"reverse",back,"fit",m("planning_included",true,"planning_reference",displayed)));
            ok("ready".equals(mutable(displayed.get("presentation")).get("status")),"advance sample source-bound display in both directions");
            var plain=new LinkedHashMap<>(displayed);plain.remove("presentation");
            ok(CityPlanningReference.same(plain,CityPlanningReference.collectionIndex(collection,TODAY).lookup(back?"安徽":"江苏",back?"滁州":"南京",back?"江苏":"安徽",back?"南京":"滁州")),"date display leaves selection facts unchanged");
        }
        var badDates=copy(annotations);
        mutable(mutable(((List<?>)badDates.get("rows")).get(0)).get("date_basis")).put("query_date",TODAY.minusDays(1).toString());
        var badView=CityPlanningReference.collectionIndex(collection,TODAY,PublicUiStopTableTest.view(dir,"wrong-date-display",badDates)).lookup("江苏","南京","安徽","滁州");
        ok("presentation_ui_sample_source_binding_invalid".equals(mutable(badView.get("presentation")).get("reason")),"display date cannot diverge from retained source");
        var selected=lookup(bundle(1),TODAY,false);
        ok(!CityPlanningSelection.underConfiguredLimit(selected,120)&&CityPlanningSelection.underConfiguredLimit(selected,121),"strict time cutoff unaffected by future sample date");
        var futurePair=CuratedCityDuration.pairAfterEnvelopeValidation(advance(false,1),advance(true,1),TODAY.minusDays(1));
        ok(String.valueOf(futurePair.get("reason")).contains("future_source_or_review"),"future observation itself still rejected");
        var legacy=CuratedCityDuration.directionAfterEnvelopeValidation(PublicUiNamedStationTest.sample(false));
        ok(PublicUiCitySample.DATE_BASIS.equals(legacy.get("source_date_basis")),"legacy version unchanged");
        ok("普通页面当日具名列车样本，仅作城市初筛参考；非最快、典型或未来保证，站点至授课地点另核。".equals(legacy.get("sample_notice")),"legacy display unchanged");
        Files.writeString(dir.resolve("renderable-samples.json"),Json.write(m("schema","synthetic_ui_date_rendering_v1","synthetic_only",true,"samples",rendering))+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("Public UI date basis: "+checks+" synthetic checks PASS; no real source admission or production change.");
    }
}
