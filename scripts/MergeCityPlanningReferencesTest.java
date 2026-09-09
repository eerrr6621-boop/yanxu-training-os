package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Self-contained synthetic parent libraries only. No private fixture, network or database. */
public final class MergeCityPlanningReferencesTest {
    static int checks,seq;static Path root;static final LocalDate TODAY=LocalDate.of(2026,9,8);
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked") static List<Object> list(Object v){return (List<Object>)v;}
    static Map<String,Object> m(Object...kv){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)r.put((String)kv[i],kv[i+1]);return r;}
    static Map<String,Object> copy(Object v){return map(Json.parse(Json.write(v)));}
    static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void eq(Object a,Object b,String why){ok(CityPlanningReference.same(a,b),why+": "+a+" != "+b);}
    static Path fresh(String name){return root.resolve((++seq)+"-"+name+".json");}
    static Map<String,Object> city(String p,String c){return m("province",p,"city",c,"id",CityPlanningReference.registryId(p,c));}
    static Map<String,Object> span(String raw,String token){int at=raw.indexOf(token);if(at<0)throw new AssertionError(token);return m("unit_id","u","start",at,"end",at+token.length(),"text",token);}
    static void doc(Map<String,Object> d,String id,Map<?,?> v){String raw=Json.write(v);map(d.get("documents")).put(id,m("raw_json",raw,"raw_sha256",CityPlanningReference.rawHash(raw)));}
    static Map<String,Object> policy(){return m("use_basis","independently_curated_public_facts","license_claimed",false,"minimal_fact_review","no_explicit_minimal_fact_prohibition_observed","article_republication_allowed",false,"checked_on",TODAY.toString(),"scope_note","Synthetic protocol fixture, no actual source or rights claim","external_restrictions",m("minimal_fact_extraction_prohibited",false,"minimal_fact_reuse_prohibited",false,"source_research_only",false,"automation_prohibited",false,"access_restricted",false,"direct_article_republication_restricted",false),"checks",List.of(m("url","https://example.invalid/synthetic","record_locator","SYNTHETIC","observation","No actual source reading")));}
    static Map<String,Object> fixture(String prefix,String fp,String fc,String tp,String tc){
        Map<String,Object> from=city(fp,fc),to=city(tp,tc),d=m("version",CityPlanningReference.SCHEMA,"library_revision",1,"use_basis","independently_curated_public_facts","license_claimed",false,"local_planning_import_allowed",true,"article_republication_allowed",false,"strict_runtime_admission",false,"purpose","prebid_city_planning_only","policy",m("buffer_minutes",30,"buffer_basis","planning_assumption_not_source_error_bound"),"documents",new LinkedHashMap<>(),"counts",m("old_statistics",999),"reviewed_on","DO NOT COPY THIS TOP-LEVEL MARKER");
        List<Object> sources=new ArrayList<>(),facts=new ArrayList<>(),events=new ArrayList<>(),reviews=new ArrayList<>(),legs=new ArrayList<>();
        for(int i=0;i<2;i++){
            Map<String,Object> f=i==0?from:to,t=i==0?to:from;String id=prefix+"source"+i,url="https://example.invalid/"+id,raw=f.get("city")+"至"+t.get("city")+"运行90分钟。";
            doc(d,id,m("source_id",id,"url",url,"published_on",TODAY.toString(),"synthetic_only",true,"units",List.of(m("unit_id","u","text",raw,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(raw)))));
            sources.add(m("source_id",id,"url",url,"file_sha256",map(map(d.get("documents")).get(id)).get("raw_sha256")));
            events.add(m("source_id",id,"url",url,"result","original_read","recorded_at",null,"record_locator","SYNTHETIC protocol value; no original URL reading"));reviews.add(m("source_id",id,"reuse_state","no_explicit_prohibition_observed","rationale","SYNTHETIC"));
            facts.add(m("source_id",id,"from_city",f.get("city"),"to_city",t.get("city"),"rationale","Synthetic annotation only", "canonical",m("verification_method",CuratedCityDuration.METHOD,"source_id",id,"from_registry_id",f.get("id"),"to_registry_id",t.get("id"),"from_endpoint",f.get("city"),"to_endpoint",t.get("city"),"endpoint_scope",m("from","city_summary","to","city_summary"),"mapping_basis","literal_city_prefix","transport_mode","rail","route_scope","city_summary","published_on",TODAY.toString(),"effective_on",null,"effective_until",null,"service_state","reported_operating","status","active","duration",m("kind","reported_minutes","value",90,"unit","minute","token","90分钟")),"spans",m("from",List.of(span(raw,f.get("city").toString())),"to",List.of(span(raw,t.get("city").toString())),"duration",List.of(span(raw,"90分钟")),"context",List.of(span(raw,raw)))));
            legs.add(m("from_registry_id",f.get("id"),"to_registry_id",t.get("id"),"packet_document",prefix+"packet","source_document",id,"review_a",prefix+"A","review_b",prefix+"B","source_policy",policy()));
        }
        doc(d,prefix+"packet",m("created_on",TODAY.toString(),"sources",sources));
        for(String role:List.of("A","B"))doc(d,prefix+role,m("reviewer",m("role",role,"actor_type","assistant","agent_id","synthetic-"+role,"run_id","synthetic-"+prefix+role,"background_context_prior_research",false,"prior_A_result_access",false,"fresh_blind_claimed",false),"source_packet_sha256",map(map(d.get("documents")).get(prefix+"packet")).get("raw_sha256"),"read_events",events,"source_reviews",reviews,"facts",facts,"synthetic_only",true));
        String id=prefix+"reference";Map<String,Object> snapshot=m("reference_id",id,"version",1,"from",from,"to",to,"status","active","reviewed_on",TODAY.toString(),"outbound",legs.get(0),"inbound",legs.get(1));
        Map<String,Object> event=m("snapshot",snapshot,"previous_hash","","change_reason","Synthetic initial fixture");event.put("event_sha256",CityPlanningReference.eventHash(event));d.put("references",new ArrayList<>(List.of(m("reference_id",id,"revisions",new ArrayList<>(List.of(event))))));return d;
    }
    static Path prepared(Map<?,?> d)throws Exception{Path p=fresh("parent");CityPlanningReference.prepare(d,p,TODAY);return p;}
    static Map<String,Object> merged(List<Path> parents,int revision)throws Exception{return MergeCityPlanningReferences.merge(parents,fresh("merged"),revision,TODAY);}
    static Map<String,Object> firstChain(Map<String,Object>d){return map(list(d.get("references")).get(0));}
    static void renamed(Map<String,Object>d,String id){Map<String,Object> c=firstChain(d);c.put("reference_id",id);for(Object r:list(c.get("revisions"))){Map<String,Object> e=map(r);map(e.get("snapshot")).put("reference_id",id);e.put("event_sha256",CityPlanningReference.eventHash(e));}}
    @FunctionalInterface interface Attempt{void run()throws Exception;}
    static void reject(String reason,Attempt a)throws Exception {try{a.run();throw new AssertionError("unexpectedly accepted "+reason);}catch(IllegalArgumentException e){ok(e.getMessage().contains(reason),"expected "+reason+", got "+e.getMessage());}}
    static void rejectsMerge(List<Path> parents,int revision,String reason)throws Exception {Path out=fresh("reject");reject(reason,()->MergeCityPlanningReferences.merge(parents,out,revision,TODAY));ok(!Files.exists(out)&&!Files.exists(Path.of(out+".integrity.json")),"validation failure leaves target absent");}
    public static void main(String[] args)throws Exception {
        root=args.length==0?Files.createTempDirectory("planning-merge-synthetic-"):Files.createDirectory(Path.of(args[0]));
        Map<String,Object> a=fixture("a-","江苏","南京","上海","上海"),b=fixture("b-","浙江","杭州","浙江","宁波");b.put("library_revision",4);
        Path pa=prepared(a),pb=prepared(b);String rawA=Files.readString(pa),anchorA=Files.readString(Path.of(pa+".integrity.json")),rawB=Files.readString(pb),anchorB=Files.readString(Path.of(pb+".integrity.json"));
        Map<String,Object> merged=merged(List.of(pa,pb),5);
        eq(CityPlanningReference.heads(merged).size(),2,"distinct pairs union");eq(merged.get("library_revision"),5,"new revision");
        ok(!merged.containsKey("counts")&&!merged.containsKey("reviewed_on"),"old metadata not promoted");eq(CityPlanningReference.list(map(merged.get("merge_provenance")).get("parents")).size(),2,"all parent provenance");
        eq(map(merged.get("merge_provenance")).get("source_revalidation_claimed"),false,"merge not new research");
        for(Map<String,Object>d:List.of(a,b)){
            for(var e:map(d.get("documents")).entrySet())eq(map(merged.get("documents")).get(e.getKey()),e.getValue(),"entire original document entry retained");
            for(Object chain:list(d.get("references")))ok(list(merged.get("references")).stream().anyMatch(candidate->CityPlanningReference.same(candidate,chain)),"original full revision chains retained across JSON integer representation");
            CityPlanningReference.continuation(CityPlanningReference.anchor(d),merged);checks++;
        }
        eq(CityPlanningReference.lookup(merged,"江苏","南京","上海","上海",TODAY).get("status"),"planning_reference","merged real lookup");
        eq(CityPlanningReference.lookup(merged,"浙江","宁波","浙江","杭州",TODAY.plusDays(91)).get("reason"),"source_or_review_stale","old date not renewed by merge");
        eq(Files.readString(pa),rawA,"parent A bytes unchanged");eq(Files.readString(Path.of(pa+".integrity.json")),anchorA,"A anchor unchanged");eq(Files.readString(pb),rawB,"parent B bytes unchanged");eq(Files.readString(Path.of(pb+".integrity.json")),anchorB,"B anchor unchanged");
        Path same=prepared(a);Map<String,Object> dedup=merged(List.of(pa,same),2);eq(CityPlanningReference.heads(dedup).size(),1,"identical references deduplicated");eq(map(dedup.get("documents")).size(),map(a.get("documents")).size(),"identical documents deduplicated");
        for(int revision:List.of(1,4,0,100000))rejectsMerge(List.of(pa,pb),revision,revision==0||revision==100000?"out_of_range":"must_exceed_all_parents");
        rejectsMerge(List.of(pa,pa),2,"duplicate_parent_path");rejectsMerge(List.of(pa),2,"two_to_64");
        Map<String,Object> changed=copy(a);Map<String,Object> entry=map(map(changed.get("documents")).get("a-A"));entry.put("adapter_note","same content but different entry metadata");Path changedPath=prepared(changed);rejectsMerge(List.of(pa,changedPath),2,"document_conflict");
        changed=copy(a);doc(changed,"unreferenced-shared",m("n",2));Map<String,Object> withExtra=copy(a);doc(withExtra,"unreferenced-shared",m("n",1));rejectsMerge(List.of(prepared(withExtra),prepared(changed)),2,"document_conflict");
        changed=copy(a);entry=map(map(changed.get("documents")).get("a-A"));String old=entry.get("raw_json").toString();entry.put("raw_json",old+"\n");entry.put("raw_sha256",CityPlanningReference.rawHash(old+"\n"));rejectsMerge(List.of(pa,prepared(changed)),2,"document_conflict");
        changed=copy(a);Map<String,Object> first=map(list(firstChain(changed).get("revisions")).get(0)),snapshot=copy(first.get("snapshot"));snapshot.put("version",2);snapshot.put("status","revoked");Map<String,Object> rev=m("snapshot",snapshot,"previous_hash",first.get("event_sha256"),"change_reason","Synthetic withdrawal");rev.put("event_sha256",CityPlanningReference.eventHash(rev));list(firstChain(changed).get("revisions")).add(rev);changed.put("library_revision",2);Path revoked=prepared(changed);
        rejectsMerge(List.of(pa,revoked),3,"reference_conflict");Map<String,Object> retained=merged(List.of(revoked,pb),5);eq(CityPlanningReference.lookup(retained,"江苏","南京","上海","上海",TODAY).get("reason"),"reference_revoked","revoked status retained with entire chain");
        changed=copy(a);renamed(changed,"other-reference-same-pair");rejectsMerge(List.of(pa,prepared(changed)),2,"duplicate_city_pair");
        changed=copy(b);map(changed.get("policy")).put("extra_scope_note","different outer policy");rejectsMerge(List.of(pa,prepared(changed)),5,"outer_contract_conflict: policy");
        Path missing=fresh("unprepared");Files.writeString(missing,Json.write(a),StandardOpenOption.CREATE_NEW);rejectsMerge(List.of(pa,missing),2,"missing_symlink_or_oversized_input");
        Path broken=prepared(b);Files.writeString(Path.of(broken+".integrity.json"),"{}",StandardOpenOption.TRUNCATE_EXISTING);rejectsMerge(List.of(pa,broken),5,"parent_load_rejected");
        broken=prepared(b);Files.writeString(broken,Json.write(a),StandardOpenOption.TRUNCATE_EXISTING);rejectsMerge(List.of(pa,broken),5,"parent_load_rejected");
        Path existing=fresh("exists");Files.writeString(existing,"do not alter",StandardOpenOption.CREATE_NEW);reject("target_or_anchor_already_exists",()->MergeCityPlanningReferences.merge(List.of(pa,pb),existing,5,TODAY));eq(Files.readString(existing),"do not alter","existing target preserved");
        Path anchorOnly=fresh("anchor-only");Files.writeString(Path.of(anchorOnly+".integrity.json"),"retain anchor",StandardOpenOption.CREATE_NEW);reject("target_or_anchor_already_exists",()->MergeCityPlanningReferences.merge(List.of(pa,pb),anchorOnly,5,TODAY));eq(Files.readString(Path.of(anchorOnly+".integrity.json")),"retain anchor","existing anchor preserved");
        reject("target_or_anchor_already_exists",()->MergeCityPlanningReferences.merge(List.of(pa,pb),pa,5,TODAY));eq(Files.readString(pa),rawA,"cannot overwrite parent");
        Path cli=fresh("cli");MergeCityPlanningReferences.main(new String[]{cli.toString(),"5",TODAY.toString(),pa.toString(),pb.toString()});ok(!CityPlanningReference.load(cli).containsKey("load_status"),"actual CLI prepares valid output");
        reject("integer_library_revision_required",()->MergeCityPlanningReferences.main(new String[]{fresh("bad-cli").toString(),"5.5",TODAY.toString(),pa.toString(),pb.toString()}));
        reject("ISO_date_required",()->MergeCityPlanningReferences.main(new String[]{fresh("bad-date").toString(),"5","2026-9-8",pa.toString(),pb.toString()}));
        Files.writeString(root.resolve("receipt.json"),Json.write(m("checks",checks,"synthetic_only",true,"private_fixture_dependency",false,"model_used",false,"production_changed",false))+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("Planning library merge: "+checks+" assertions passed; synthetic only; "+root);
    }
}
