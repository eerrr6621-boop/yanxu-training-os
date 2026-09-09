package com.training;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import static com.training.ContextSharedPlanningAdapterTest.*;

/** Real single-source opt-in; fault copies explicitly synthetic. No network/model/default writes. */
public final class ContextBaselinePlanningTest {
    static final String PACKET_SHA="945fe357c9f7a0be021fd3a1b1b985aac03b28cb062dbd94d53bbc4727a2f7b8";
    static final String ROOT_SHA="bc9ac1054b8d8489ee983d21b599e7b4fa0355a333c580f2b53658b1edb48adb";
    static final ContextSharedPlanningAdapter.OptIn MODE=ContextSharedPlanningAdapter.OptIn.CURRENT_BASELINE_PREBID_V2;
    static int checks;static final List<String> faults=new ArrayList<>();
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static Map<String,Object> source(Map<String,Object> p){return m(l(p.get("sources")).get(0));}
    static Map<String,Object> canonical(Map<String,Object> p){return m(source(p).get("semantic_draft"));}
    static Map<String,Object> structure(Map<String,Object> p){return m(source(p).get("context_structure"));}
    static Map<String,Object> observation(Map<String,Object> p){return m(canonical(p).get("shared_observation"));}
    static Map<String,Object> role(Map<String,Object> p,String role){return m(l(structure(p).get("spans")).stream().filter(x->role.equals(m(x).get("role"))).findFirst().orElseThrow());}
    static Map<String,Object> unit(Map<String,Object> p,String id){return m(l(source(p).get("units")).stream().filter(x->id.equals(m(x).get("unit_id"))).findFirst().orElseThrow());}
    static Map<String,Object> without(Map<String,Object> x,String... keys){Map<String,Object> result=new LinkedHashMap<>(x);for(String k:keys)result.remove(k);return result;}
    static void hashPacket(Map<String,Object> p){
        Map<String,Object> s=source(p),c=canonical(p),t=structure(p);
        for(Object raw:l(s.get("units"))){Map<String,Object> u=m(raw);u.put("text_sha256",CityPlanningReference.rawHash(u.get("text").toString()));}
        t.put("context_structure_sha256",CityPlanningReference.hash(without(t,"context_structure_sha256")));
        s.put("source_document_sha256",CityPlanningReference.hash(without(s,"source_document_sha256","semantic_draft","prior_root_original_reads")));
        c.put("source_document_sha256",s.get("source_document_sha256"));c.put("context_structure_sha256",t.get("context_structure_sha256"));c.put("canonical_sha256",CityPlanningReference.hash(without(c,"canonical_sha256")));
        p.put("source_structure_sha256",CityPlanningReference.hash(without(p,"source_structure_sha256")));
    }
    static Map<String,Object> data(Map<String,Object> packet,ContextSharedPlanningAdapter.Pins pins,String id){
        Map<String,Object> d=library(packet);d.put("version",ContextSharedPlanningAdapter.BASELINE_VERSION);d.put("policy",ContextSharedPlanningAdapter.BASELINE_POLICY);d.put("library_id",id);
        d.put("source_fixture_sha256",pins.sourceSha());d.put("root_review_sha256",pins.rootReviewSha());snapshot(d,0).put("reference_kind",ContextSharedPlanningAdapter.BASELINE_KIND);rehash(d);return d;
    }
    static Path writeBaseline(Path out,String name,Map<String,Object> data,ContextSharedPlanningAdapter.Pins pins)throws Exception{
        Map<String,Object> anchor=ContextSharedPlanningAdapter.anchorForPreparation(data,pins,MODE);Path p=out.resolve(name+".private.json");
        Files.writeString(p,Json.write(data)+"\n",StandardOpenOption.CREATE_NEW);Files.writeString(Path.of(p+".integrity.json"),Json.write(anchor)+"\n",StandardOpenOption.CREATE_NEW);return p;
    }
    static ContextSharedPlanningAdapter.Catalog load(Path file,ContextSharedPlanningAdapter.Pins pins,LocalDate date)throws Exception{return ContextSharedPlanningAdapter.open(file,raw(file),raw(Path.of(file+".integrity.json")),pins,date,MODE);}
    static ContextSharedPlanningAdapter.Pins faultPins(Path out,String name,Map<String,Object> packet,String originalReview)throws Exception{
        hashPacket(packet);Path source=out.resolve("SYNTHETIC-"+name+"-source.json"),review=out.resolve("SYNTHETIC-"+name+"-review.md");Files.writeString(source,Json.write(packet)+"\n",StandardOpenOption.CREATE_NEW);
        String body=originalReview.replace(PACKET_SHA,raw(source)).replace("7d0b5b1c041c38ceb91a965b84b06b45149b63fa3031f86aa23eb4d42df7e133",canonical(packet).get("canonical_sha256").toString());
        Files.writeString(review,"SYNTHETIC CONTROL ONLY — NOT AN ACTUAL SOURCE READ OR ROOT APPROVAL.\n"+body,StandardOpenOption.CREATE_NEW);return new ContextSharedPlanningAdapter.Pins(source,raw(source),review,raw(review));
    }
    static void rejectPacket(Path out,Map<String,Object> original,String review,String name,Consumer<Map<String,Object>> edit)throws Exception{
        Map<String,Object> bad=copy(original);edit.accept(bad);var pins=faultPins(out,name,bad,review);
        try{ContextSharedPlanningAdapter.anchorForPreparation(data(bad,pins,"baseline-fault-"+name),pins,MODE);throw new AssertionError("accepted "+name);}
        catch(IllegalArgumentException expected){check(true,name);faults.add(name+": "+expected.getMessage());}
    }
    static void rejectLibrary(Map<String,Object> original,ContextSharedPlanningAdapter.Pins pins,String name,Consumer<Map<String,Object>> edit)throws Exception{
        Map<String,Object> bad=copy(original);edit.accept(bad);rehash(bad);
        try{ContextSharedPlanningAdapter.anchorForPreparation(bad,pins,MODE);throw new AssertionError("accepted "+name);}
        catch(IllegalArgumentException expected){check(true,name);faults.add(name+": "+expected.getMessage());}
    }
    static Map<String,Object> teacher(long id,String p,String c,int score,String level){return ContextSharedPlanningIntegrationTest.context(id,p,c,score,level);}
    static Map<String,Object> fit(Map<String,Object> c){return m(c.get("dispatch_fit"));}
    static Map<String,Object> run(List<Map<String,Object>> rows,ContextSharedPlanningAdapter.Catalog c,LocalDate day,int limit){return ContextSharedPlanningIntegrationTest.run(rows,ContextSharedPlanningIntegrationTest.pref("湖北","武汉"),3,c,day,limit);}
    static List<Long> ids(Map<String,Object> result){return ContextSharedPlanningIntegrationTest.ids(result);}
    static void noSuccess(Map<String,Object> row,Map<String,Object> result,String why){check(ids(result).isEmpty()&&l(result.get("air_candidates")).isEmpty()&&!fit(row).containsKey("context_planning_reference")&&!fit(row).containsKey("context_planning_display")&&!ContextSharedPlanningAdapter.ownsPolicy(fit(row).get("planning_policy")),why);}
    static void replaceRoleToken(Map<String,Object> p,String r,String value){
        Map<String,Object> sp=role(p,r),u=unit(p,sp.get("unit_id").toString());String text=u.get("text").toString();int a=((Number)sp.get("start")).intValue(),b=((Number)sp.get("end")).intValue(),delta=value.length()-(b-a);
        u.put("text",text.substring(0,a)+value+text.substring(b));sp.put("text",value);sp.put("end",a+value.length());
        for(Object raw:l(structure(p).get("spans"))){Map<String,Object> other=m(raw);if(other!=sp&&sp.get("unit_id").equals(other.get("unit_id"))&&((Number)other.get("start")).intValue()>=b){other.put("start",((Number)other.get("start")).intValue()+delta);other.put("end",((Number)other.get("end")).intValue()+delta);}}
    }
    public static void main(String[] args)throws Exception{
        Path base=Path.of(args[0]).toAbsolutePath().normalize(),out=Files.createDirectory(Path.of(args[1]).toAbsolutePath().normalize());
        Path source=base.resolve(".codex-tmp/yanxu-context-baseline-source.L6ZzsV/source-fixture.private.json"),review=source.resolveSibling("ROOT_SOURCE_REVIEW.md");
        check(PACKET_SHA.equals(raw(source))&&ROOT_SHA.equals(raw(review)),"frozen source/root review pins");var pins=new ContextSharedPlanningAdapter.Pins(source,PACKET_SHA,review,ROOT_SHA);
        Map<String,Object> packet=m(Json.parse(Files.readString(source))),data=data(packet,pins,"baseline-real-one-local-tests");String originalReview=Files.readString(review);
        Path file=writeBaseline(out,"hefei-wuhan-baseline",data,pins);var catalog=load(file,pins,DAY);check(catalog.valid()&&catalog.sharedCount()==1&&catalog.independentDirectionCount()==0,"real independent single catalog loads: "+catalog.reason());
        Map<String,Object> ref=catalog.lookup("安徽","合肥","湖北","武汉"),obs=m(ref.get("shared_observation"));
        check(ref==catalog.lookup("湖北","武汉","安徽","合肥")&&ref==catalog.select("湖北","武汉","安徽","合肥",240).get("reference"),"both queries preserve same immutable observation identity");
        check(obs.equals(observation(packet))&&obs.get("original_duration_text").equals("2小时")&&obs.get("precision").equals("nominal_hour")&&obs.get("qualifier").equals("reported_current_baseline")&&((Number)obs.get("reference_minutes")).intValue()==120,"actual approved scalar and qualifier");
        check(l(ref.get("stations")).equals(Arrays.asList(null,null))&&!ref.containsKey("outbound")&&!ref.containsKey("inbound")&&!Json.write(ref).contains("1小时左右"),"future value and invented stations/legs absent from output");
        check(Boolean.FALSE.equals(ref.get("literal_roundtrip_wording_present"))&&Boolean.FALSE.equals(ref.get("literal_single_trip_wording_present")),"no literal roundtrip/single-trip claim");
        for(String flag:List.of("strict_eligibility","transport_verified","rail_exclusion_complete","air_fallback_trigger","value_is_proven_travel_upper_bound","time_score_applicable"))check(Boolean.FALSE.equals(ref.get(flag)),"capability remains false "+flag);
        check(!CityPlanningSelection.underConfiguredLimit(ref,240)&&!CityPlanningSelection.underSharedConfiguredLimit(ref,240),"old directional/shared-v1 entry rejects new type");
        try{obs.put("reference_minutes",60);throw new AssertionError("mutable observation");}catch(UnsupportedOperationException expected){check(true,"deep immutable");}
        for(int limit:List.of(-1,0,60,61,120,121,240,241))check(Boolean.TRUE.equals(catalog.select("安徽","合肥","湖北","武汉",limit).get("planning_pool_eligible"))==(limit>120&&limit<=240),"reference strictly below cutoff "+limit);
        for(var opt:List.of(ContextSharedPlanningAdapter.OptIn.OFF,ContextSharedPlanningAdapter.OptIn.CONTEXT_SHARED_PREBID_V1))check(!ContextSharedPlanningAdapter.open(file,raw(file),raw(Path.of(file+".integrity.json")),pins,DAY,opt).valid(),"requires specific new opt-in "+opt);
        check(!load(file,pins,DAY.minusDays(1)).select("安徽","合肥","湖北","武汉",240).get("planning_pool_eligible").equals(true),"future review excluded");
        for(LocalDate day:List.of(LocalDate.of(2026,12,7),LocalDate.of(2026,12,8)))check(Boolean.TRUE.equals(load(file,pins,day).select("安徽","合肥","湖北","武汉",240).get("planning_pool_eligible"))==day.equals(LocalDate.of(2026,12,7)),"actual review90 boundary "+day);
        Map<String,Object> unknown=catalog.select("安徽","芜湖","湖北","武汉",240);check(Boolean.FALSE.equals(unknown.get("planning_pool_eligible"))&&Boolean.FALSE.equals(unknown.get("air_fallback_trigger")),"unknown not air evidence");
        var pref=ContextSharedPlanningIntegrationTest.pref("湖北","武汉");List<Map<String,Object>> pool=new ArrayList<>();
        for(int i=1;i<=3;i++)pool.add(ContextSharedPlanningIntegrationTest.candidate(i,"湖北","武汉",60+i,"讲师",true,obj("status","same_city"),null));
        for(int i=4;i<=6;i++)pool.add(teacher(i,"安徽","合肥",90,i==4?"特聘讲师":i==5?"高级讲师":"讲师"));
        Map<String,Object> baseline=m(Json.parse(Files.readString(base.resolve(".codex-tmp/yanxu-planning19-shared1.uShL9f/actual-collection-result.json"))));
        Map<String,Object> strictRow=m(l(baseline.get("strict_outputs")).stream().filter(x->m(x).get("from").equals("宜昌")&&m(x).get("to").equals("武汉")).findFirst().orElseThrow());
        pool.add(ContextSharedPlanningIntegrationTest.candidate(7,"湖北","宜昌",95,"讲师",false,copy(m(strictRow.get("result"))),null));
        Map<String,Object> planningRow=m(l(baseline.get("planning_outputs")).stream().filter(x->m(m(x).get("from")).get("city").equals("郑州")&&m(m(x).get("to")).get("city").equals("武汉")).findFirst().orElseThrow());
        pool.add(ContextSharedPlanningIntegrationTest.candidate(8,"河南","郑州",80,"讲师",false,ContextSharedPlanningIntegrationTest.missing(),copy(m(planningRow.get("without_presentation")))));
        List<String> strictBefore=pool.stream().map(x->Json.write(fit(x).get("route_reference"))).toList();Map<String,Object> mixed=run(pool,catalog,DAY,240);
        check(((Number)mixed.get("pool_size")).intValue()==8&&ids(mixed).equals(List.of(7L,4L,5L,6L))&&((Number)mixed.get("ties_included")).intValue()==1,"local+old strict/planning+all baseline teachers one model/level/cutoff-tie pool");
        check(((Number)mixed.get("context_planning_pool_size")).intValue()==3&&((Number)mixed.get("context_planning_selected_count")).intValue()==3,"v2 counted under context pool");
        for(int i=0;i<pool.size();i++)check(strictBefore.get(i).equals(Json.write(fit(pool.get(i)).get("route_reference"))),"strict route never mutated");
        check(fit(pool.get(3)).get("context_planning_reference")==fit(pool.get(4)).get("context_planning_reference"),"many teachers one shared fact");
        String display=Json.write(fit(pool.get(3)).get("context_planning_display"));check(display.contains("报道现有旅时2小时")&&display.contains("未指定车站")&&display.contains("实际出行待核")&&!display.contains("往返")&&!display.contains("单程")&&!mixed.get("planning_notice").toString().contains("往返"),"v2 honest compact display, no invented literal wording");
        check("2026-06-03".equals(ref.get("source_published_on"))&&"湖北发布".equals(ref.get("attributed_source"))&&ref.get("source_url").equals(source(packet).get("url")),"source date attribution URL retained");
        Map<String,Object> unscored=teacher(9,"安徽","合肥",80,"讲师");unscored.remove("model_score");Map<String,Object> scorePending=run(List.of(unscored,ContextSharedPlanningIntegrationTest.candidate(99,"湖北","武汉",70,"讲师",true,obj("status","same_city"),null)),catalog,DAY,240);
        check(l(scorePending.get("scoring_pending")).size()==1&&ids(scorePending).equals(List.of(99L))&&!scorePending.get("planning_notice").toString().contains("往返"),"baseline scoring pending still uses baseline wording, not old roundtrip text");
        for(int limit:List.of(61,120,121,240)){Map<String,Object> t=teacher(10,"安徽","合肥",80,"讲师");check(!ids(run(List.of(t),catalog,DAY,limit)).isEmpty()==(limit>120),"actual selection cutoff "+limit);}
        Map<String,Object> reuse=teacher(20,"安徽","合肥",80,"讲师");run(List.of(reuse),catalog,DAY,240);noSuccess(reuse,NearbySelection.select(List.of(reuse),pref,3),"old default clears v2 previous success");
        run(List.of(reuse),catalog,DAY,240);noSuccess(reuse,run(List.of(reuse),null,DAY,240),"null catalog clears v2");
        run(List.of(reuse),catalog,DAY,240);noSuccess(reuse,run(List.of(reuse),catalog,DAY.plusDays(1),240),"cross-day clears v2");
        run(List.of(reuse),catalog,DAY,240);noSuccess(reuse,ContextSharedPlanningIntegrationTest.run(List.of(reuse),ContextSharedPlanningIntegrationTest.pref("上海","上海"),3,catalog,DAY,240),"destination change clears v2");
        var expired=load(file,pins,LocalDate.of(2026,12,8));noSuccess(reuse,run(List.of(reuse),expired,LocalDate.of(2026,12,8),240),"expiry no display/planning/air");
        Map<String,Object> forged=teacher(21,"安徽","合肥",80,"讲师");fit(forged).put("context_planning_reference",ref);fit(forged).put("planning_policy",ContextSharedPlanningAdapter.BASELINE_POLICY);fit(forged).put("planning_included",true);noSuccess(forged,run(List.of(forged),null,DAY,240),"forged response Map no capability");
        Map<String,Object> wrong=teacher(22,"安徽","合肥",80,"讲师");wrong.put("base_city","芜湖");noSuccess(wrong,run(List.of(wrong),catalog,DAY,240),"teacher city cannot be substituted by fit");
        Map<String,Object> broken=teacher(23,"安徽","合肥",80,"讲师");fit(broken).put("planning_reference",CityPlanningReference.pending("source_or_review_stale"));noSuccess(broken,run(List.of(broken),catalog,DAY,240),"old source failure cannot be overwritten");
        int parity=0;for(Object raw:l(baseline.get("planning_outputs"))){Map<String,Object> row=m(raw),a=m(row.get("from")),b=m(row.get("to"));Map<String,Object> t=ContextSharedPlanningIntegrationTest.candidate(1000+parity,a.get("province").toString(),a.get("city").toString(),80,"讲师",false,ContextSharedPlanningIntegrationTest.missing(),copy(m(row.get("without_presentation"))));var p=ContextSharedPlanningIntegrationTest.pref(b.get("province").toString(),b.get("city").toString());check(CityPlanningReference.same(NearbySelection.select(List.of(copy(t)),p,3),NearbySelection.select(List.of(copy(t)),p,3,catalog,DAY)),"old planning full selection unchanged with v2");parity++;}
        check(parity==39,"all39 stored planning query outputs exercised with v2; separate old integration rechecks actual source loads");
        for(String flag:List.of("outbound","inbound","strict_eligibility"))rejectLibrary(data,pins,"injected-"+flag,d->snapshot(d,0).put(flag,120));
        rejectLibrary(data,pins,"wrong-registry",d->m(snapshot(d,0).get("from")).put("id","C001"));
        rejectLibrary(data,pins,"source-STOP",d->m(m(snapshot(d,0).get("source_policy")).get("external_restrictions")).put("minimal_fact_reuse_prohibited",true));
        rejectLibrary(data,pins,"wrong-canonical",d->snapshot(d,0).put("canonical_sha256","0".repeat(64)));
        rejectLibrary(data,pins,"wrong-version",d->d.put("version",ContextSharedPlanningAdapter.VERSION));
        for(String field:List.of("reference_minutes","qualifier","precision"))rejectPacket(out,packet,originalReview,"observation-"+field,p->observation(p).put(field,field.equals("reference_minutes")?60:field.equals("qualifier")?"reported_fastest":"reported_minutes"));
        rejectPacket(out,packet,originalReview,"future-as-baseline",p->{observation(p).put("reference_minutes",60);observation(p).put("original_duration_text","1小时左右");observation(p).put("source_scalar",obj("kind","approximate","value",60,"unit","minute","token","1小时左右"));});
        for(String value:List.of("→","↔"))rejectPacket(out,packet,originalReview,"arrow-"+(value.equals("→")?"oneway":"other"),p->{replaceRoleToken(p,"bidirectional_symbol",value);structure(p).put("bidirectional_symbol",value);});
        rejectPacket(out,packet,originalReview,"past-not-future",p->replaceRoleToken(p,"future_scope","过去建成后"));
        rejectPacket(out,packet,originalReview,"missing-will",p->replaceRoleToken(p,"future_auxiliary","已"));
        rejectPacket(out,packet,originalReview,"swapped-operands",p->{Map<String,Object> a=role(p,"baseline_duration"),b=role(p,"future_duration");a.put("role","future_duration");b.put("role","baseline_duration");});
        rejectPacket(out,packet,originalReview,"cross-unit-comparison",p->{Map<String,Object> u=copy(unit(p,"WH_COMPARISON"));u.put("unit_id","COPIED-SENTENCE");l(source(p).get("units")).add(u);role(p,"baseline_duration").put("unit_id","COPIED-SENTENCE");});
        rejectPacket(out,packet,originalReview,"source-unit-splice",p->unit(p,"WH_COMPARISON").put("parent_segment","WH_L39"));
        rejectPacket(out,packet,originalReview,"missing-rail",p->replaceRoleToken(p,"rail_mode","公路"));
        rejectPacket(out,packet,originalReview,"wrong-context-city",p->replaceRoleToken(p,"context_city_a","芜湖"));
        rejectPacket(out,packet,originalReview,"future-station",p->canonical(p).put("stations",Arrays.asList(null,"武汉天河")));
        rejectPacket(out,packet,originalReview,"literal-roundtrip-claim",p->canonical(p).put("literal_roundtrip_wording_present",true));
        rejectPacket(out,packet,originalReview,"unknown-discriminator",p->structure(p).put("context_type","trust_any_two_cities"));
        rejectPacket(out,packet,originalReview,"source-restriction",p->source(p).put("automation_prohibited",true));
        rejectPacket(out,packet,originalReview,"wrong-publication",p->source(p).put("published_on","2026-09-08"));
        rejectPacket(out,packet,originalReview,"wrong-attribution",p->source(p).put("attributed_source","长江日报"));
        // Independently pinned synthetic positive controls demonstrate numeric/source-id generality and source365 boundary.
        Map<String,Object> generic=copy(packet);source(generic).put("source_id","SYNTHETIC-NONHARDCODED");canonical(generic).put("source_id","SYNTHETIC-NONHARDCODED");replaceRoleToken(generic,"baseline_duration","3小时");observation(generic).put("reference_minutes",180);observation(generic).put("original_duration_text","3小时");observation(generic).put("source_scalar",obj("kind","nominal_hour","value",3,"unit","hour","token","3小时"));
        var genericPins=faultPins(out,"generic-hour-positive",generic,originalReview);Path genericFile=writeBaseline(out,"SYNTHETIC-generic-positive",data(generic,genericPins,"baseline-synthetic-generic"),genericPins);check(Boolean.TRUE.equals(load(genericFile,genericPins,DAY).select("安徽","合肥","湖北","武汉",181).get("planning_pool_eligible")),"parameterized source-id/hour, not hardcoded real120");
        Map<String,Object> aged=copy(packet);source(aged).put("published_on","2025-09-08");unit(aged,"WH_HEADER").put("text","2025-09-08 17:34 湖北发布");var agedPins=faultPins(out,"source-age-positive",aged,originalReview);Path agedFile=writeBaseline(out,"SYNTHETIC-source-age",data(aged,agedPins,"baseline-synthetic-source-age"),agedPins);
        check(Boolean.TRUE.equals(load(agedFile,agedPins,DAY).select("安徽","合肥","湖北","武汉",240).get("planning_pool_eligible"))&&!Boolean.TRUE.equals(load(agedFile,agedPins,DAY.plusDays(1)).select("安徽","合肥","湖北","武汉",240).get("planning_pool_eligible")),"synthetic source365 exact boundary, no factual date mutation");
        check(!ContextSharedPlanningAdapter.open(file,"0".repeat(64),raw(Path.of(file+".integrity.json")),pins,DAY,MODE).valid(),"bad library hash");
        check(!ContextSharedPlanningAdapter.open(file,raw(file),"0".repeat(64),pins,DAY,MODE).valid(),"bad anchor hash");
        check(!load(file,new ContextSharedPlanningAdapter.Pins(source,"0".repeat(64),review,ROOT_SHA),DAY).valid(),"bad original-source pin");
        check(!load(file,new ContextSharedPlanningAdapter.Pins(source,PACKET_SHA,review,"0".repeat(64)),DAY).valid(),"bad root-review pin");
        Map<String,Object> revoked=copy(data);revoked.put("library_revision",2);List<Object> history=l(m(l(revoked.get("references")).get(0)).get("revisions"));Map<String,Object> next=copy(snapshot(revoked,0));next.put("version",2);next.put("status","revoked");history.add(obj("snapshot",next,"previous_hash","","change_reason","SYNTHETIC revocation only"));rehash(revoked);
        Path revokedFile=writeBaseline(out,"SYNTHETIC-revoked",revoked,pins);var revokedCatalog=load(revokedFile,pins,DAY);Map<String,Object> rt=teacher(24,"安徽","合肥",80,"讲师");noSuccess(rt,run(List.of(rt),revokedCatalog,DAY,240),"revoked baseline no planning/air");
        check(!load(file,pins,DAY).valid(),"rollback across paths rejected");
        rejectLibrary(revoked,pins,"revocation-reactivate",d->{d.put("library_revision",3);List<Object> hist=l(m(l(d.get("references")).get(0)).get("revisions"));Map<String,Object> sn=copy(m(m(hist.get(1)).get("snapshot")));sn.put("version",3);sn.put("status","active");hist.add(obj("snapshot",sn,"previous_hash","","change_reason","SYNTHETIC forbidden reactivation"));});
        check(PACKET_SHA.equals(raw(source))&&ROOT_SHA.equals(raw(review)),"source packet/root approval never changed");
        Map<String,Object> result=obj("status","PASS_standalone_baseline_not_collection_admission","checks",checks,"real_source_candidates",1,"independent_direction_observations",0,"collection_added_pairs",0,"candidate_file",file.toString(),"candidate_sha256",raw(file),"anchor_sha256",raw(Path.of(file+".integrity.json")),"source_fixture_sha256",PACKET_SHA,"root_review_sha256",ROOT_SHA,"initial_valid_reference",ref,"mixed_pool",mixed,"old_v2_selection_parity_queries",parity,"synthetic_rejected_controls",faults,"model_called",false,"network_called",false,"default_configuration_changed",false);
        Files.writeString(out.resolve("result.json"),Json.write(result)+"\n",StandardOpenOption.CREATE_NEW);System.out.println("Context baseline: "+checks+" checks PASS; standalone1, no collection/strict/air increment.");
    }
}
