package com.training;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import static com.training.ContextSharedPlanningIntegrationTest.*;

/** The same opt-in request entry routes real39 + existing2 + baseline1. Synthetic controls only. */
public final class ContextRequestPlanningTest {
    static int checks;static Path base,out,oldFile,newFile;static ContextSharedPlanningAdapter.Pins oldPins,newPins;
    static final String OLD_FILE_SHA="42182000e933db761e9ba0e3405e62433368516d8c69c2d95d6a11b093d0546c",OLD_ANCHOR_SHA="aa2b383b8d4ba37ef5cf8691a617774a5eedea6c2b653c8d2b61e178b6901b67";
    static final String NEW_FILE_SHA="aff62fc38a1f1fe10cb8d8cff1c42366a6ef2460ec9c0d5861b21c5995cb9e3d",NEW_ANCHOR_SHA="8dc48ee6dfe4dea13812a54e52abed50a5a031dcfd13ec3811aaacd992d3f094";
    static final ContextSharedPlanningAdapter.OptIn V1=ContextSharedPlanningAdapter.OptIn.CONTEXT_SHARED_PREBID_V1,V2=ContextSharedPlanningAdapter.OptIn.CURRENT_BASELINE_PREBID_V2;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void same(Object a,Object b,String why){check(CityPlanningReference.same(a,b),why);}
    static ContextSharedPlanningAdapter.Catalog old(LocalDate day){return ContextSharedPlanningAdapter.open(oldFile,OLD_FILE_SHA,OLD_ANCHOR_SHA,oldPins,day,V1);}
    static ContextSharedPlanningAdapter.Catalog baseline(LocalDate day){return ContextSharedPlanningAdapter.open(newFile,NEW_FILE_SHA,NEW_ANCHOR_SHA,newPins,day,V2);}
    static ContextSharedPlanningAdapter.RequestCatalog request(LocalDate day){return ContextSharedPlanningAdapter.RequestCatalog.combine(day,List.of(old(day),baseline(day)));}
    static Map<String,Object> run(List<Map<String,Object>> rows,DispatchPreference pref,ContextSharedPlanningAdapter.RequestCatalog routed,LocalDate day,int limit){
        String previous=System.getProperty("dispatch.nearby.max.minutes");try{System.setProperty("dispatch.nearby.max.minutes",Integer.toString(limit));return NearbySelection.selectWithContextCatalogs(rows,pref,3,routed,day);}finally{if(previous==null)System.clearProperty("dispatch.nearby.max.minutes");else System.setProperty("dispatch.nearby.max.minutes",previous);}
    }
    static String pair(String ap,String ac,String bp,String bc){List<String> names=new ArrayList<>(List.of(ap+":"+ac,bp+":"+bc));Collections.sort(names);return String.join(" / ",names);}
    static void noPositive(Map<String,Object> decision,String why){check(Boolean.FALSE.equals(decision.get("planning_pool_eligible"))&&Boolean.FALSE.equals(decision.get("air_fallback_trigger"))&&Boolean.FALSE.equals(decision.get("rail_exclusion_complete"))&&Boolean.FALSE.equals(decision.get("strict_eligibility")),why);}
    static void noShown(Map<String,Object> c,String why){check(!fit(c).containsKey("context_planning_reference")&&!fit(c).containsKey("context_planning_display")&&!ContextSharedPlanningAdapter.ownsPolicy(fit(c).get("planning_policy")),why);}
    static ContextSharedPlanningAdapter.Catalog variant(String name,Consumer<Map<String,Object>> edit)throws Exception{
        Map<String,Object> data=m(Json.parse(Files.readString(oldFile)));data.put("library_id","request-SYNTHETIC-"+name);edit.accept(data);ContextSharedPlanningAdapterTest.rehash(data);
        Path file=ContextSharedPlanningAdapterTest.write(out,"SYNTHETIC-"+name,data,oldPins);return ContextSharedPlanningAdapter.open(file,sha(file),sha(Path.of(file+".integrity.json")),oldPins,DAY,V1);
    }
    static void revokeFirst(Map<String,Object> d){
        d.put("library_revision",2);List<Object> history=l(m(l(d.get("references")).get(0)).get("revisions"));Map<String,Object> next=copy(m(m(history.get(0)).get("snapshot")));next.put("version",2);next.put("status","revoked");
        history.add(obj("snapshot",next,"previous_hash","","change_reason","SYNTHETIC routing revocation control; source unchanged."));
    }
    static ContextSharedPlanningAdapter.Catalog syntheticBaseline(String name,Consumer<Map<String,Object>> edit,LocalDate day)throws Exception{
        Map<String,Object> packet=m(Json.parse(Files.readString(newPins.sourceFixture())));edit.accept(packet);
        var pins=ContextBaselinePlanningTest.faultPins(out,"request-"+name,packet,Files.readString(newPins.rootReview()));
        Path file=ContextBaselinePlanningTest.writeBaseline(out,"SYNTHETIC-request-"+name,ContextBaselinePlanningTest.data(packet,pins,"request-synthetic-"+name),pins);
        return ContextSharedPlanningAdapter.open(file,sha(file),sha(Path.of(file+".integrity.json")),pins,day,V2);
    }
    public static void main(String[] args)throws Exception{
        base=Path.of(args[0]).toAbsolutePath().normalize();out=Files.createDirectory(Path.of(args[1]).toAbsolutePath().normalize());
        Path os=base.resolve(".codex-tmp/yanxu-context-shared-sources.DoNWLx/source-fixtures.private.json"),ns=base.resolve(".codex-tmp/yanxu-context-baseline-source.L6ZzsV/source-fixture.private.json");
        oldPins=new ContextSharedPlanningAdapter.Pins(os,ContextSharedPlanningAdapterTest.SOURCE_SHA,os.resolveSibling("ROOT_SOURCE_REVIEW.md"),ContextSharedPlanningAdapterTest.REVIEW_SHA);
        newPins=new ContextSharedPlanningAdapter.Pins(ns,ContextBaselinePlanningTest.PACKET_SHA,ns.resolveSibling("ROOT_SOURCE_REVIEW.md"),ContextBaselinePlanningTest.ROOT_SHA);
        oldFile=base.resolve(".codex-tmp/yanxu-context-shared-adapter.hqpB43/actual-final/two-context-candidates.private.json");newFile=base.resolve(".codex-tmp/yanxu-context-baseline-adapter.rxPGgR/actual-final/hefei-wuhan-baseline.private.json");
        Map<Path,String> kept=new LinkedHashMap<>();kept.put(os,oldPins.sourceSha());kept.put(oldPins.rootReview(),oldPins.rootReviewSha());kept.put(ns,newPins.sourceSha());kept.put(newPins.rootReview(),newPins.rootReviewSha());kept.put(oldFile,OLD_FILE_SHA);kept.put(Path.of(oldFile+".integrity.json"),OLD_ANCHOR_SHA);kept.put(newFile,NEW_FILE_SHA);kept.put(Path.of(newFile+".integrity.json"),NEW_ANCHOR_SHA);
        Path baselineFile=base.resolve(".codex-tmp/yanxu-planning19-shared1.uShL9f/actual-collection-result.json");kept.put(baselineFile,"73e217decbd8a1f34ef483f2338ad1dd896325994729b771929251339393f47a");
        Map<String,Object> b=m(Json.parse(Files.readString(baselineFile)));for(var pin:m(b.get("input_pins")).entrySet())kept.put(Path.of(pin.getKey()),pin.getValue().toString());
        for(String p:List.of(b.get("collection_file").toString(),b.get("collection_file")+".integrity.json",b.get("sidecar_file").toString(),b.get("sidecar_file")+".integrity.json"))kept.put(Path.of(p),sha(Path.of(p)));
        for(var pin:kept.entrySet())check(pin.getValue().equals(sha(pin.getKey())),"initial frozen pin");
        var first=old(DAY);var second=baseline(DAY);check(first.valid()&&second.valid(),"both original catalog versions load under their own pins");
        var routed=ContextSharedPlanningAdapter.RequestCatalog.combine(DAY,List.of(first,second));check(routed.valid()&&routed.pairCount()==3&&routed.conflictCount()==0&&routed.independentDirectionCount()==0,"one request has three distinct shared pairs and no independent legs");
        check(routed.lookup("北京","北京","河北","承德")==first.lookup("北京","北京","河北","承德")&&routed.lookup("湖北","武汉","安徽","合肥")==second.lookup("安徽","合肥","湖北","武汉"),"composition reuses exact owner reference, including reversed query");
        for(var duplicate:List.of(ContextSharedPlanningAdapter.RequestCatalog.combine(DAY,List.of(first,first)),ContextSharedPlanningAdapter.RequestCatalog.combine(DAY,List.of(first,old(DAY))))){
            check(duplicate.valid()&&duplicate.pairCount()==2&&duplicate.conflictCount()==0,"same and separately reloaded full references deduplicate");
            check(duplicate.lookup("北京","北京","河北","承德")==first.lookup("北京","北京","河北","承德"),"first immutable observation identity retained");
        }
        List<Object> newQueries=new ArrayList<>(),mixedResults=new ArrayList<>();Set<String> pairs=new TreeSet<>();int queryCount=0;
        for(String[] p:List.of(new String[]{"北京","北京","河北","承德","45","reported_minutes","reported_fastest"},new String[]{"辽宁","沈阳","辽宁","抚顺","20","approximate","general_reported"},new String[]{"湖北","武汉","安徽","合肥","120","nominal_hour","reported_current_baseline"})){
            var perRequest=request(DAY);int value=Integer.parseInt(p[4]);for(boolean reverse:List.of(false,true)){Map<String,Object> result=perRequest.select(reverse?p[0]:p[2],reverse?p[1]:p[3],reverse?p[2]:p[0],reverse?p[3]:p[1],240);
                check(Boolean.TRUE.equals(result.get("planning_pool_eligible")),"actual pair selectable through unified entry");Map<String,Object> ref=m(result.get("reference")),obs=m(ref.get("shared_observation"));
                check(p[5].equals(obs.get("precision"))&&p[6].equals(obs.get("qualifier"))&&((Number)obs.get("reference_minutes")).intValue()==value,"original precision and qualifier remain");check((value==120?ContextSharedPlanningAdapter.BASELINE_POLICY:ContextSharedPlanningAdapter.POLICY).equals(result.get("policy")),"no fabricated unified policy");
                check(l(ref.get("stations")).equals(Arrays.asList(null,null))&&!ref.containsKey("outbound")&&!ref.containsKey("inbound")&&((Number)ref.get("independent_direction_observations")).intValue()==0,"one shared reference, unknown stations");
                newQueries.add(obj("from",reverse?p[1]:p[3],"to",reverse?p[3]:p[1],"decision",result));queryCount++;
            }
            for(int limit:List.of(value,value+1,240,241))check(Boolean.TRUE.equals(perRequest.select(p[2],p[3],p[0],p[1],limit).get("planning_pool_eligible"))==(limit>value&&limit<=240),"strict per-pair cutoff "+limit);
            pairs.add(pair(p[0],p[1],p[2],p[3]));
            List<Map<String,Object>> teachers=new ArrayList<>();for(int i=1;i<=3;i++)teachers.add(candidate(i,p[0],p[1],60+i,"讲师",true,obj("status","same_city"),null));
            for(int i=4;i<=6;i++)teachers.add(context(i,p[2],p[3],90,i==4?"特聘讲师":i==5?"高级讲师":"讲师"));
            // Inapplicable context-backed cities cannot enter this different target's pool.
            String wrongProvince=p[1].equals("武汉")?"河北":"安徽",wrongCity=p[1].equals("武汉")?"承德":"合肥";teachers.add(context(9,wrongProvince,wrongCity,99,"特聘讲师"));
            Map<String,Object> selected=run(teachers,pref(p[0],p[1]),perRequest,DAY,240);check(((Number)selected.get("pool_size")).intValue()==6&&ids(selected).equals(List.of(4L,5L,6L)),"target-applicable teachers only, all local and context one model/level pool");
            check(l(selected.get("travel_pending")).size()==1&&l(selected.get("air_candidates")).isEmpty(),"wrong target city stays pending, never air");
            check(fit(teachers.get(3)).get("context_planning_reference")==fit(teachers.get(4)).get("context_planning_reference"),"no duplicate shared object per teacher");
            mixedResults.add(selected);
        }
        // Wuhan request includes old strict+planning as well as baseline candidates, all before sorting.
        var whRequest=request(DAY);Map<String,Object> strictYichang=m(l(b.get("strict_outputs")).stream().filter(x->m(x).get("from").equals("宜昌")&&m(x).get("to").equals("武汉")).findFirst().orElseThrow());
        Map<String,Object> planningZhengzhou=m(l(b.get("planning_outputs")).stream().filter(x->m(m(x).get("from")).get("city").equals("郑州")&&m(m(x).get("to")).get("city").equals("武汉")).findFirst().orElseThrow());
        List<Map<String,Object>> wh=new ArrayList<>();for(int i=1;i<=3;i++)wh.add(candidate(i,"湖北","武汉",60+i,"讲师",true,obj("status","same_city"),null));for(int i=4;i<=6;i++)wh.add(context(i,"安徽","合肥",90,i==4?"特聘讲师":i==5?"高级讲师":"讲师"));
        wh.add(candidate(7,"湖北","宜昌",95,"讲师",false,copy(strictYichang.get("result")),null));wh.add(candidate(8,"河南","郑州",80,"讲师",false,missing(),copy(planningZhengzhou.get("without_presentation"))));
        List<String> originalRoutes=wh.stream().map(x->Json.write(fit(x).get("route_reference"))).toList();Map<String,Object> unified=run(wh,pref("湖北","武汉"),whRequest,DAY,240);
        check(((Number)unified.get("pool_size")).intValue()==8&&ids(unified).equals(List.of(7L,4L,5L,6L))&&((Number)unified.get("ties_included")).intValue()==1,"not two Top3 lists: full mixed8 pool yields4 cutoff ties");
        for(int i=0;i<wh.size();i++)check(originalRoutes.get(i).equals(Json.write(fit(wh.get(i)).get("route_reference"))),"no strict source mutation");
        var index=CityPlanningReference.collectionIndex(Path.of(b.get("collection_file").toString()),DAY);var shown=CityPlanningReference.collectionIndex(Path.of(b.get("collection_file").toString()),DAY,CityPlanningPresentation.load(Path.of(b.get("sidecar_file").toString()),CityPlanningPresentation.VERSION_V2));int oldQueries=0;
        for(Object raw:l(b.get("planning_outputs"))){Map<String,Object> row=m(raw),a=m(row.get("from")),z=m(row.get("to"));String ap=a.get("province").toString(),ac=a.get("city").toString(),zp=z.get("province").toString(),zc=z.get("city").toString();
            Map<String,Object> actual=index.lookup(ap,ac,zp,zc);same(actual,row.get("without_presentation"),"actual old planning full output");same(shown.lookup(ap,ac,zp,zc),row.get("reference"),"actual old presentation full output");
            Map<String,Object> t=candidate(1000+oldQueries,ap,ac,80,"讲师",false,missing(),actual);same(NearbySelection.select(List.of(copy(t)),pref(zp,zc),3),run(List.of(copy(t)),pref(zp,zc),request(DAY),DAY,240),"old planning full selection with same routed entry");
            pairs.add(pair(ap,ac,zp,zc));oldQueries++;
        }
        Map<String,Object> strict=strictBundle(base.resolve(".codex-tmp/yanxu-jinan-natural-clock.njabjQz8/prepared-v2/public-city-nineteen-merged.private.json"));List<Object> strictActual=new ArrayList<>();int strictCount=0;
        for(Map<?,?> event:CityTravelReference.heads(strict).values()){Map<?,?> s=CityPlanningReference.map(event.get("snapshot"));String fp=s.get("from_province").toString(),fc=s.get("from_city").toString(),tp=s.get("to_province").toString(),tc=s.get("to_city").toString();
            for(boolean back:List.of(false,true)){String ap=back?tp:fp,ac=back?tc:fc,zp=back?fp:tp,zc=back?fc:tc;Map<String,Object> actual=CityTravelReference.lookup(strict,ap,ac,zp,zc,DAY);strictActual.add(obj("layer","strict","from",ac,"to",zc,"result",actual));
                Map<String,Object> t=candidate(2000+strictCount,ap,ac,80,"讲师",false,actual,null);same(NearbySelection.select(List.of(copy(t)),pref(zp,zc),3),run(List.of(copy(t)),pref(zp,zc),request(DAY),DAY,240),"old strict full selection with same routed entry");pairs.add(pair(ap,ac,zp,zc));strictCount++;}
        }
        same(strictActual,b.get("strict_outputs"),"all actual strict38 complete values preserved");check(oldQueries==39&&strictCount==38&&queryCount==6&&pairs.size()==42,"same entry exercises original39 plus3 nonoverlapping pairs, not file-count arithmetic");
        var changedEvent=variant("changed-event",d->m(l(m(l(d.get("references")).get(0)).get("revisions")).get(0)).put("change_reason","SYNTHETIC different event with same minutes"));
        var revoked=variant("revoked",ContextRequestPlanningTest::revokeFirst);check(changedEvent.valid()&&revoked.valid(),"synthetic variants still satisfy original protected loader");
        for(var other:List.of(changedEvent,revoked)){var conflict=ContextSharedPlanningAdapter.RequestCatalog.combine(DAY,List.of(old(DAY),other));check(conflict.valid()&&conflict.pairCount()==2&&conflict.conflictCount()==1,"same pair different event/status is a local conflict");
            Map<String,Object> decision=conflict.select("河北","承德","北京","北京",240);noPositive(decision,"conflict does not choose active/shorter value");check("context_pair_conflict".equals(decision.get("reason"))&&!decision.containsKey("policy")&&m(decision.get("reference")).isEmpty()&&conflict.lookup("北京","北京","河北","承德").isEmpty(),"no positive policy/reference on conflict");
            check(Boolean.TRUE.equals(conflict.select("辽宁","抚顺","辽宁","沈阳",240).get("planning_pool_eligible")),"unrelated pair survives deduplicated unchanged source");
            Map<String,Object> t=context(30,"河北","承德",80,"讲师");Map<String,Object> result=run(List.of(t),pref("北京","北京"),conflict,DAY,240);check(ids(result).isEmpty()&&l(result.get("air_candidates")).isEmpty()&&"context_pair_conflict".equals(fit(t).get("context_planning_pending_reason")),"conflict pending in existing candidate pool");noShown(t,"conflict has no positive display");
        }
        var differentValue=syntheticBaseline("three-hours",p->{ContextBaselinePlanningTest.replaceRoleToken(p,"baseline_duration","3小时");Map<String,Object> o=ContextBaselinePlanningTest.observation(p);o.put("reference_minutes",180);o.put("original_duration_text","3小时");o.put("source_scalar",obj("kind","nominal_hour","value",3,"unit","hour","token","3小时"));},DAY);
        var differentDate=syntheticBaseline("older-date",p->{ContextBaselinePlanningTest.source(p).put("published_on","2025-09-08");ContextBaselinePlanningTest.unit(p,"WH_HEADER").put("text","2025-09-08 17:34 湖北发布");},DAY.plusDays(1));
        for(var pair:List.of(List.of(baseline(DAY),differentValue),List.of(baseline(DAY.plusDays(1)),differentDate))){LocalDate day=pair.get(1)==differentDate?DAY.plusDays(1):DAY;var conflict=ContextSharedPlanningAdapter.RequestCatalog.combine(day,pair);check(conflict.valid()&&conflict.conflictCount()==1,"different value/date including expired duplicate conflicts");noPositive(conflict.select("安徽","合肥","湖北","武汉",240),"cannot choose good duplicate over stale or slower");}
        var otherPolicy=syntheticBaseline("beijing-pair",p->{ContextBaselinePlanningTest.replaceRoleToken(p,"city_a","北京");ContextBaselinePlanningTest.replaceRoleToken(p,"context_city_a","北京");ContextBaselinePlanningTest.replaceRoleToken(p,"city_b","承德");ContextBaselinePlanningTest.replaceRoleToken(p,"context_city_b","承德");Map<String,Object> c=ContextBaselinePlanningTest.canonical(p);List<Object> cities=List.of(obj("province","北京","city","北京"),obj("province","河北","city","承德"));c.put("pair",cities);c.put("registry_binding",cities.stream().map(x->ContextSharedPlanningAdapterTest.city(m(x))).toList());},DAY);
        var policyConflict=ContextSharedPlanningAdapter.RequestCatalog.combine(DAY,List.of(old(DAY),otherPolicy));check(policyConflict.valid()&&policyConflict.conflictCount()==1,"different source precision/kind/policy same pair conflicts");noPositive(policyConflict.select("北京","北京","河北","承德",240),"no unified policy bridge");
        var invalid=ContextSharedPlanningAdapter.open(oldFile,"0".repeat(64),OLD_ANCHOR_SHA,oldPins,DAY,V1);
        for(var bad:List.of(ContextSharedPlanningAdapter.RequestCatalog.combine(DAY,List.of(invalid,baseline(DAY))),ContextSharedPlanningAdapter.RequestCatalog.combine(DAY,Arrays.asList(old(DAY),null)),ContextSharedPlanningAdapter.RequestCatalog.combine(DAY,List.of(old(DAY.minusDays(1)),baseline(DAY))),ContextSharedPlanningAdapter.RequestCatalog.combine(DAY,List.of()),ContextSharedPlanningAdapter.RequestCatalog.combine(DAY,List.of(old(DAY),baseline(DAY),baseline(DAY))))){
            check(!bad.valid()&&bad.pairCount()==0,"no silently discarded bad/missing/stale component");noPositive(bad.select("安徽","合肥","湖北","武汉",240),"bad component cannot borrow good baseline");
            Map<String,Object> oldTeacher=candidate(40,"湖北","宜昌",80,"讲师",false,copy(strictYichang.get("result")),null);same(NearbySelection.select(List.of(copy(oldTeacher)),pref("湖北","武汉"),3),run(List.of(copy(oldTeacher)),pref("湖北","武汉"),bad,DAY,240),"bad additive routing does not alter independent old strict result");
        }
        var november=request(LocalDate.of(2026,11,1));noPositive(november.select("辽宁","抚顺","辽宁","沈阳",240),"real source expiry");check(Boolean.TRUE.equals(november.select("河北","承德","北京","北京",240).get("planning_pool_eligible"))&&Boolean.TRUE.equals(november.select("安徽","合肥","湖北","武汉",240).get("planning_pool_eligible")),"known expired沈抚 does not block other current pairs");
        noPositive(request(LocalDate.of(2026,12,8)).select("安徽","合肥","湖北","武汉",240),"real review expiry");noPositive(request(DAY).select("四川","眉山","湖北","武汉",240),"unknown city no rail/air conclusion");
        for(String reason:List.of("source_or_review_stale","cross_library_revocation_or_integrity_failure","reference_hash_mismatch")){Map<String,Object> t=context(50,"安徽","合肥",80,"讲师");fit(t).put("planning_reference",CityPlanningReference.pending(reason));check(ids(run(List.of(t),pref("湖北","武汉"),request(DAY),DAY,240)).isEmpty(),"old39 planning hard failure not overridden "+reason);noShown(t,"hard failure no positive context");}
        for(String flag:List.of("load_status","cancelled")){Map<String,Object> t=context(51,"安徽","合肥",80,"讲师");m(fit(t).get("route_reference")).put(flag,flag.equals("cancelled")?true:"broken");check(ids(run(List.of(t),pref("湖北","武汉"),request(DAY),DAY,240)).isEmpty(),"strict hard failure not overridden "+flag);}
        Map<String,Object> reused=context(60,"安徽","合肥",80,"讲师");run(List.of(reused),pref("湖北","武汉"),request(DAY),DAY,240);check(fit(reused).containsKey("context_planning_display"),"initial request success");
        check(ids(run(List.of(reused),pref("湖北","武汉"),null,DAY,240)).isEmpty(),"null request clears previous success");noShown(reused,"null clean");check(!fit(reused).containsKey("context_planning_pending_reason"),"null clears own pending too");
        run(List.of(reused),pref("湖北","武汉"),request(DAY),DAY,240);check(ids(run(List.of(reused),pref("北京","北京"),request(DAY),DAY,240)).isEmpty(),"target switch");noShown(reused,"target switch clean");
        run(List.of(reused),pref("湖北","武汉"),request(DAY),DAY,240);check(ids(run(List.of(reused),pref("湖北","武汉"),request(DAY),DAY.plusDays(1),240)).isEmpty(),"cross-day request rejected");noShown(reused,"cross-day clean");
        check(ids(NearbySelection.select(List.of(reused),pref("湖北","武汉"),3)).isEmpty()&&!fit(reused).containsKey("context_planning_pending_reason"),"old default cleans routed decisions and pending reasons");
        fit(reused).put("context_planning_reference",second.lookup("安徽","合肥","湖北","武汉"));fit(reused).put("planning_policy",ContextSharedPlanningAdapter.BASELINE_POLICY);fit(reused).put("planning_included",true);check(ids(run(List.of(reused),pref("湖北","武汉"),null,DAY,240)).isEmpty(),"forged Map cannot substitute capability");noShown(reused,"forgery cleaned");
        Map<String,Object> missingScore=context(61,"安徽","合肥",80,"讲师");missingScore.remove("model_score");var scoring=run(List.of(missingScore,candidate(62,"湖北","武汉",70,"讲师",true,obj("status","same_city"),null)),pref("湖北","武汉"),request(DAY),DAY,240);check(l(scoring.get("scoring_pending")).size()==1&&ids(scoring).equals(List.of(62L))&&!scoring.get("planning_notice").toString().contains("往返"),"one pool keeps model-pending and neutral notice");
        for(var pin:kept.entrySet())check(pin.getValue().equals(sha(pin.getKey())),"final source/library/anchor/old39 pin unchanged");
        Map<String,Object> result=obj("status","PASS_local_unified_request_not_default_or_collection","checks",checks,"real_component_catalogs",2,"context_distinct_pairs",3,"context_independent_direction_observations",0,"old_planning_full_outputs",oldQueries,"old_strict_full_outputs",strictCount,"new_pair_direction_queries",queryCount,"distinct_reference_pairs_exercised_at_same_request_entry",pairs.size(),"distinct_pairs",pairs,"collection_added_pairs",0,"strict_or_air_increment",0,"original_input_pins",kept.entrySet().stream().map(e->obj("path",e.getKey().toString(),"sha256",e.getValue())).toList(),"actual_new_queries",newQueries,"per_target_mixed_pool_results",mixedResults,"wuhan_complete_mixed_pool",unified,"network_called",false,"model_called",false,"default_or_production_configuration_changed",false);
        Files.writeString(out.resolve("result.json"),Json.write(result)+"\n",StandardOpenOption.CREATE_NEW);System.out.println("Context request: "+checks+" checks PASS; 42 distinct reference pairs exercised, 0 collection/strict/air increment.");
    }
}
