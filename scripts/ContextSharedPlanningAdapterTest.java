package com.training;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

/** Two pinned real source candidates; private fault copies only. No network, teacher,
 * model, global config, old-library writes or admission into the 39-pair collection. */
public final class ContextSharedPlanningAdapterTest {
    static final LocalDate DAY=LocalDate.of(2026,9,8);
    static final String SOURCE_SHA="5a721535a3aefc9be3af7fe126c71026009e95565fb946eeb5f3f25412f348a9";
    static final String REVIEW_SHA="590ef1a6867150a5bed7a087348b2b2aaa9f489bcb91a6f7a6e2c0d7753b47ec";
    static int checks;static final List<String> rejected=new ArrayList<>();
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    @SuppressWarnings("unchecked") static Map<String,Object> m(Object x){return (Map<String,Object>)x;}
    @SuppressWarnings("unchecked") static List<Object> l(Object x){return (List<Object>)x;}
    static Map<String,Object> obj(Object... kv){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)r.put((String)kv[i],kv[i+1]);return r;}
    static Map<String,Object> copy(Map<?,?> x){return m(Json.parse(Json.write(x)));}
    static String raw(Path p)throws Exception{return CityPlanningReference.rawHash(Files.readString(p));}
    static Map<String,Object> city(Map<?,?> x){String p=CityPlanningReference.text(x,"province"),c=CityPlanningReference.text(x,"city");return obj("id",CityPlanningReference.registryId(p,c),"province",p,"city",c);}
    static Map<String,Object> sourcePolicy(Map<?,?> s){
        Map<String,Object> flags=obj("minimal_fact_extraction_prohibited",false,"minimal_fact_reuse_prohibited",false,"source_research_only",false,"automation_prohibited",false,"access_restricted",false,"direct_article_republication_restricted",false);
        Map<?,?> access=CityPlanningReference.map(s.get("access")),policy=CityPlanningReference.map(s.get("policy_review"));
        List<Map<String,Object>> checks=new ArrayList<>();checks.add(obj("url",s.get("url"),"record_locator",access.get("result_locator"),"observation",policy.get("note")));
        if(policy.containsKey("url"))checks.add(obj("url",policy.get("url"),"record_locator",policy.get("locator"),"observation","Unauthorized mirrors are prohibited; keep original third-party attribution. Not a republication permission."));
        return obj("use_basis","independently_curated_public_facts","license_claimed",false,"minimal_fact_review","no_explicit_minimal_fact_prohibition_observed","external_restrictions",flags,"article_republication_allowed",false,"checked_on",DAY.toString(),"scope_note","Reuse only the approved minimum facts and attribution within the source packet's actually read scope; no blanket legal or publication permission.","checks",checks);
    }
    static Map<String,Object> library(Map<?,?> packet){
        List<Map<String,Object>> refs=new ArrayList<>();int n=0;
        for(Object raw:CityPlanningReference.list(packet.get("sources"))){Map<?,?> s=CityPlanningReference.map(raw),c=CityPlanningReference.map(s.get("semantic_draft"));List<?> pair=CityPlanningReference.list(c.get("pair"));String id="ctx-real-"+(++n);
            Map<String,Object> snapshot=obj("reference_id",id,"version",1,"reference_kind",ContextSharedPlanningAdapter.KIND,"from",city(CityPlanningReference.map(pair.get(0))),"to",city(CityPlanningReference.map(pair.get(1))),"status","active","reviewed_on",DAY.toString(),"source_id",s.get("source_id"),"source_document_sha256",s.get("source_document_sha256"),"context_structure_sha256",c.get("context_structure_sha256"),"canonical_sha256",c.get("canonical_sha256"),"source_policy",sourcePolicy(s));
            refs.add(obj("reference_id",id,"revisions",new ArrayList<>(List.of(obj("snapshot",snapshot,"previous_hash","","change_reason","Root-approved context-inferred shared candidate; local adapter test only.")))));
        }
        Map<String,Object> data=obj("version",ContextSharedPlanningAdapter.VERSION,"library_id","context-shared-two-local-tests","library_revision",1,"purpose","local_prebid_context_shared_tests_only","policy",ContextSharedPlanningAdapter.POLICY,"source_fixture_sha256",SOURCE_SHA,"root_review_sha256",REVIEW_SHA,"references",refs);rehash(data);return data;
    }
    static Map<String,Object> snapshot(Map<String,Object> data,int n){return m(m(l(m(l(data.get("references")).get(n)).get("revisions")).get(0)).get("snapshot"));}
    static void rehash(Map<String,Object> data){for(Object rr:l(data.get("references"))){String prior="";for(Object ee:l(m(rr).get("revisions"))){Map<String,Object> e=m(ee);e.put("previous_hash",prior);prior=CityPlanningReference.eventHash(e);e.put("event_sha256",prior);}}}
    static Path write(Path root,String name,Map<String,Object> data,ContextSharedPlanningAdapter.Pins pins)throws Exception{
        Map<String,Object> anchor=ContextSharedPlanningAdapter.anchorForPreparation(data,pins);Path p=root.resolve(name+".private.json");
        Files.writeString(p,Json.write(data)+"\n",StandardOpenOption.CREATE_NEW);Files.writeString(Path.of(p+".integrity.json"),Json.write(anchor)+"\n",StandardOpenOption.CREATE_NEW);return p;
    }
    static ContextSharedPlanningAdapter.Catalog open(Path p,ContextSharedPlanningAdapter.Pins pins,LocalDate day)throws Exception{return ContextSharedPlanningAdapter.open(p,raw(p),raw(Path.of(p+".integrity.json")),pins,day,ContextSharedPlanningAdapter.OptIn.CONTEXT_SHARED_PREBID_V1);}
    static void reject(Map<String,Object> base,ContextSharedPlanningAdapter.Pins pins,String name,Consumer<Map<String,Object>> edit)throws Exception{
        Map<String,Object> bad=copy(base);edit.accept(bad);rehash(bad);
        try{ContextSharedPlanningAdapter.anchorForPreparation(bad,pins);throw new AssertionError("accepted "+name);}catch(IllegalArgumentException expected){check(true,name);rejected.add(name+": "+expected.getMessage());}
    }
    static void unavailable(ContextSharedPlanningAdapter.Catalog c,String name){check(!c.valid()&&c.sharedCount()==0,name);Map<String,Object> r=c.select("北京","北京","河北","承德",240);check(Boolean.FALSE.equals(r.get("planning_pool_eligible"))&&Boolean.FALSE.equals(r.get("air_fallback_trigger")),name+" no planning/air");}
    static boolean fit(ContextSharedPlanningAdapter.Catalog c,String fp,String fc,String tp,String tc,int limit){Map<String,Object> r=c.select(fp,fc,tp,tc,limit);for(String f:List.of("strict_eligibility","transport_verified","rail_exclusion_complete","air_fallback_trigger","time_score_applicable"))check(Boolean.FALSE.equals(r.get(f)),"no capability "+f);return Boolean.TRUE.equals(r.get("planning_pool_eligible"));}
    public static void main(String[] args)throws Exception{
        check(args.length==2,"workspace and new output path required");Path workspace=Path.of(args[0]).toAbsolutePath().normalize(),root=Files.createDirectory(Path.of(args[1]).toAbsolutePath().normalize());
        Path source=workspace.resolve(".codex-tmp/yanxu-context-shared-sources.DoNWLx/source-fixtures.private.json"),review=source.resolveSibling("ROOT_SOURCE_REVIEW.md");
        var pins=new ContextSharedPlanningAdapter.Pins(source,SOURCE_SHA,review,REVIEW_SHA);check(SOURCE_SHA.equals(raw(source))&&REVIEW_SHA.equals(raw(review)),"frozen inputs exact");
        Path baseline=workspace.resolve(".codex-tmp/yanxu-planning19-shared1.uShL9f/actual-collection-result.json");String baselineSha=raw(baseline);
        unavailable(ContextSharedPlanningAdapter.open(null,null,null,null,null,ContextSharedPlanningAdapter.OptIn.OFF),"off does no file access");
        Map<String,Object> packet=m(Json.parse(Files.readString(source))),data=library(packet);Path candidate=write(root,"two-context-candidates",data,pins);var catalog=open(candidate,pins,DAY);
        check(catalog.valid(),"real candidate load: "+catalog.reason());check(catalog.sharedCount()==2&&catalog.independentDirectionCount()==0,"two shared objects, zero independent directions");
        Map<String,Object> cd=catalog.lookup("北京","北京","河北","承德"),sf=catalog.lookup("辽宁","沈阳","辽宁","抚顺");
        check(cd==catalog.lookup("河北","承德","北京","北京")&&sf==catalog.lookup("辽宁","抚顺","辽宁","沈阳"),"reverse query is the same object, not copied observations");
        check(((Number)m(cd.get("shared_observation")).get("reference_minutes")).intValue()==45&&((Number)m(sf.get("shared_observation")).get("reference_minutes")).intValue()==20,"original shared values");
        check("reported_fastest".equals(m(cd.get("shared_observation")).get("qualifier"))&&"approximate".equals(m(sf.get("shared_observation")).get("precision")),"qualifier and precision retained");
        check(l(cd.get("stations")).equals(Arrays.asList(null,null))&&l(sf.get("stations")).equals(Arrays.asList(null,null)),"no invented stations");
        for(Map<String,Object> ref:List.of(cd,sf)){
            check(!ref.containsKey("outbound")&&!ref.containsKey("inbound"),"no fabricated legs");
            check(!CityPlanningSelection.underConfiguredLimit(ref,240)&&!CityPlanningSelection.underSharedConfiguredLimit(ref,240),"old directional and shared-v1 selection do not admit new kind");
            try{m(ref.get("shared_observation")).put("reference_minutes",1);throw new AssertionError("mutable private capability");}catch(UnsupportedOperationException expected){check(true,"immutable nested observation");}
        }
        check(!fit(catalog,"北京","北京","河北","承德",45)&&fit(catalog,"北京","北京","河北","承德",46),"45/46 strict boundary");
        check(!fit(catalog,"辽宁","沈阳","辽宁","抚顺",20)&&fit(catalog,"辽宁","抚顺","辽宁","沈阳",21),"20/21 and reverse boundary");
        check(fit(catalog,"北京","北京","河北","承德",240)&&!fit(catalog,"北京","北京","河北","承德",241),"240 hard cap");
        check(!fit(catalog,"北京","北京","河北","承德",0)&&!fit(catalog,"北京","北京","河北","承德",-1),"nonpositive limit");
        check(!fit(catalog,"四川","成都","四川","眉山",240)&&!fit(catalog,"不存在","不存在","河北","承德",240),"unobserved and unknown are not air evidence");
        check(fit(open(candidate,pins,LocalDate.of(2026,10,31)),"辽宁","沈阳","辽宁","抚顺",240)&&!fit(open(candidate,pins,LocalDate.of(2026,11,1)),"辽宁","沈阳","辽宁","抚顺",240),"source365 boundary");
        check(fit(open(candidate,pins,LocalDate.of(2026,12,7)),"北京","北京","河北","承德",240)&&!fit(open(candidate,pins,LocalDate.of(2026,12,8)),"北京","北京","河北","承德",240),"review90 boundary");
        check(!fit(open(candidate,pins,DAY.minusDays(1)),"北京","北京","河北","承德",240),"future review is pending");
        for(String extra:List.of("outbound","inbound","strict_eligibility"))reject(data,pins,"snapshot injection "+extra,d->snapshot(d,0).put(extra,45));
        reject(data,pins,"unknown top field",d->d.put("approved",true));
        reject(data,pins,"wrong registry id",d->m(snapshot(d,0).get("to")).put("id","C001"));
        reject(data,pins,"wrong registry city",d->m(snapshot(d,0).get("to")).put("city","唐山"));
        reject(data,pins,"unknown reference kind",d->snapshot(d,0).put("reference_kind",SharedCityDuration.KIND));
        reject(data,pins,"source reuse STOP",d->m(m(snapshot(d,0).get("source_policy")).get("external_restrictions")).put("minimal_fact_reuse_prohibited",true));
        reject(data,pins,"source automation STOP",d->m(m(snapshot(d,0).get("source_policy")).get("external_restrictions")).put("automation_prohibited",true));
        reject(data,pins,"license claim",d->m(snapshot(d,0).get("source_policy")).put("license_claimed",true));
        reject(data,pins,"wrong canonical",d->snapshot(d,0).put("canonical_sha256","0".repeat(64)));
        reject(data,pins,"wrong context hash",d->snapshot(d,0).put("context_structure_sha256","0".repeat(64)));
        reject(data,pins,"wrong source hash",d->snapshot(d,0).put("source_document_sha256","0".repeat(64)));
        reject(data,pins,"refresh review without source evidence",d->snapshot(d,0).put("reviewed_on","2026-09-09"));
        reject(data,pins,"duplicate reference",d->l(d.get("references")).add(copy(m(l(d.get("references")).get(0)))));
        unavailable(ContextSharedPlanningAdapter.open(candidate,"0".repeat(64),raw(Path.of(candidate+".integrity.json")),pins,DAY,ContextSharedPlanningAdapter.OptIn.CONTEXT_SHARED_PREBID_V1),"wrong outer file pin");
        unavailable(ContextSharedPlanningAdapter.open(candidate,raw(candidate),"0".repeat(64),pins,DAY,ContextSharedPlanningAdapter.OptIn.CONTEXT_SHARED_PREBID_V1),"wrong anchor pin");
        unavailable(open(candidate,new ContextSharedPlanningAdapter.Pins(source,"0".repeat(64),review,REVIEW_SHA),DAY),"wrong original source pin");
        unavailable(open(candidate,new ContextSharedPlanningAdapter.Pins(source,SOURCE_SHA,review,"0".repeat(64)),DAY),"wrong informed review pin");
        Map<String,Object> badPacket=copy(packet);m(l(m(l(badPacket.get("sources")).get(0)).get("units")).get(3)).put("text","京承往返，最快1分钟");Path corrupt=root.resolve("FAULT-source.private.json");Files.writeString(corrupt,Json.write(badPacket),StandardOpenOption.CREATE_NEW);
        Map<String,Object> badLib=copy(data);badLib.put("source_fixture_sha256",raw(corrupt));try{ContextSharedPlanningAdapter.anchorForPreparation(badLib,new ContextSharedPlanningAdapter.Pins(corrupt,raw(corrupt),review,REVIEW_SHA));throw new AssertionError("tampered source");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("source_packet_structure_hash"),"retained source structure tampering");}
        Path wrongReview=root.resolve("FAULT-unapproved-review.md");Files.writeString(wrongReview,Files.readString(review).replace("canonical84803e2ab08c8fd07bd13378a8df05e775cdd4b8fc0799181bce3d1fe6595253","canonical"+"0".repeat(64)),StandardOpenOption.CREATE_NEW);
        badLib=copy(data);badLib.put("root_review_sha256",raw(wrongReview));try{ContextSharedPlanningAdapter.anchorForPreparation(badLib,new ContextSharedPlanningAdapter.Pins(source,SOURCE_SHA,wrongReview,raw(wrongReview)));throw new AssertionError("wrong draft approval");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("exact_draft_not_approved"),"root review bound to exact canonical");}
        // Historical revocation is append-only and cannot be bypassed by a new path.
        Map<String,Object> revoked=copy(data);revoked.put("library_revision",2);List<Object> history=l(m(l(revoked.get("references")).get(0)).get("revisions"));Map<String,Object> next=copy(m(m(history.get(0)).get("snapshot")));next.put("version",2);next.put("status","revoked");history.add(obj("snapshot",next,"change_reason","Synthetic revocation control, original reference evidence retained.","previous_hash",""));rehash(revoked);
        Path revokeFile=write(root,"FAULT-revocation-control",revoked,pins);var withdrawn=open(revokeFile,pins,DAY);check(withdrawn.valid()&&!fit(withdrawn,"北京","北京","河北","承德",240)&&fit(withdrawn,"辽宁","沈阳","辽宁","抚顺",240),"revocation suppresses only the target and never makes air evidence");
        unavailable(open(candidate,pins,DAY),"same library rollback across paths");
        reject(revoked,pins,"reactivate revoked",d->{d.put("library_revision",3);List<Object> h=l(m(l(d.get("references")).get(0)).get("revisions"));Map<String,Object> s=copy(m(m(h.get(1)).get("snapshot")));s.put("version",3);s.put("status","active");h.add(obj("snapshot",s,"change_reason","Synthetic forbidden reactivation","previous_hash",""));});
        Map<String,Object> altered=copy(revoked);altered.put("library_revision",3);m(l(m(l(altered.get("references")).get(0)).get("revisions")).get(0)).put("change_reason","rewritten historical reason");rehash(altered);Path rewritten=write(root,"FAULT-rewritten-history",altered,pins);unavailable(open(rewritten,pins,DAY),"rewritten prior history");
        check(SOURCE_SHA.equals(raw(source))&&REVIEW_SHA.equals(raw(review))&&baselineSha.equals(raw(baseline)),"all original source/review/39 result bytes unchanged");
        Map<String,Object> result=obj("status","PASS_local_context_adapter_not_collection_admission","checks",checks,"candidate_file",candidate.toString(),"candidate_sha256",raw(candidate),"anchor_sha256",raw(Path.of(candidate+".integrity.json")),"real_shared_candidates",2,"independent_direction_observations",0,"baseline_pairs",39,"baseline_result_sha256",baselineSha,"source_fixture_sha256",SOURCE_SHA,"root_review_sha256",REVIEW_SHA,"source_fixture_or_baseline_modified",false,"strict_or_air_admission_increment",0,"production_or_default_configuration_changed",false,"model_called",false,"network_called",false,"initial_valid_catalog",List.of(cd,sf),"rejected_controls",rejected,"note","Fault copies and process-local revocation controls are synthetic; the real candidate file is revision1 and is not merged into any collection.");
        Files.writeString(root.resolve("result.json"),Json.write(result)+"\n",StandardOpenOption.CREATE_NEW);System.out.println("Context shared adapter: "+checks+" checks PASS; 2 standalone candidates, 0 collection admissions.");
    }
}
