package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Self-contained synthetic collection protocol; no private fixture, network, model or DB. */
public final class CityPlanningCollectionTest {
    static int checks,sequence;static Path root;static final LocalDate TODAY=LocalDate.of(2026,9,8);
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked") static List<Object> list(Object v){return (List<Object>)v;}
    static Map<String,Object> m(Object...kv){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)r.put((String)kv[i],kv[i+1]);return r;}
    static Map<String,Object> copy(Object v){return map(Json.parse(Json.write(v)));}
    static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void eq(Object a,Object b,String why){ok(CityPlanningReference.same(a,b),why+": "+a+" != "+b);}
    static Path fresh(String n){return root.resolve((++sequence)+"-"+n+".json");}
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

    static Map<String,Object> library(String[][] destinations){
        Map<String,Object> result=null;int i=0;
        for(String[] to:destinations){Map<String,Object> part=fixture("p"+(i++)+"-","江苏","南京",to[0],to[1]);
            if(result==null)result=part;else{map(result.get("documents")).putAll(map(part.get("documents")));list(result.get("references")).addAll(list(part.get("references")));}}
        return result;
    }
    static Path prepared(Map<?,?> d)throws Exception{Path p=fresh("child");CityPlanningReference.prepare(d,p,TODAY);return p;}
    static Path collection(String id,Map<String,Path> members,Path previous)throws Exception{Path p=fresh("collection");CityPlanningCollection.prepare(id,members,previous,p,TODAY);return p;}
    static Map<String,Object> query(CityPlanningReference.Index index,String p,String c){return index.lookup("江苏","南京",p,c);}
    static void pending(CityPlanningReference.Index index,String p,String c,String reason){Map<?,?> value=query(index,p,c);eq(value.get("status"),"planning_pending","pending");ok(value.get("reason").toString().contains(reason),reason+": "+value);}
    static String bytes(Path p)throws Exception{return Files.readString(p);}
    static Path anchorPath(Path p){return Path.of(p+".integrity.json");}
    static Map<String,Object> read(Path p)throws Exception{return Json.parseMap(bytes(p));}
    @FunctionalInterface interface Action{void run()throws Exception;}
    static void rejected(String reason,Action action)throws Exception{try{action.run();throw new AssertionError("accepted "+reason);}catch(IllegalArgumentException e){ok(e.getMessage().contains(reason),"expected "+reason+", got "+e.getMessage());}}
    static void rehash(Map<String,Object> data){String previous="";for(Object raw:list(data.get("revisions"))){Map<String,Object> e=map(raw);e.put("previous_hash",previous);e.put("event_sha256",CityPlanningReference.eventHash(e));previous=e.get("event_sha256").toString();}}
    static void writeCollection(Path p,Map<String,Object> data)throws Exception{Files.writeString(p,Json.write(data)+"\n");Files.writeString(anchorPath(p),Json.write(CityPlanningCollection.anchor(data))+"\n");}
    static Map<String,Object> revoked(Map<String,Object> base){Map<String,Object> d=copy(base),chain=map(list(d.get("references")).get(0)),first=map(list(chain.get("revisions")).get(0)),s=copy(first.get("snapshot"));s.put("version",2);s.put("status","revoked");Map<String,Object> event=m("snapshot",s,"previous_hash",first.get("event_sha256"),"change_reason","Synthetic withdrawal");event.put("event_sha256",CityPlanningReference.eventHash(event));list(chain.get("revisions")).add(event);d.put("library_revision",2);return d;}
    public static void main(String[] args)throws Exception {
        root=args.length==0?Files.createTempDirectory("planning-collection-synthetic-"):Files.createDirectory(Path.of(args[0]));
        String[][] nine={{"上海","上海"},{"浙江","杭州"},{"浙江","宁波"},{"安徽","合肥"},{"江苏","苏州"},{"江苏","无锡"},{"江苏","常州"},{"山东","济南"},{"山东","青岛"}};
        String[][] three={{"内蒙古","乌海"},{"宁夏","银川"},{"甘肃","兰州"}},one={{"四川","成都"}};
        Map<String,Object> a=library(nine),b=library(three),c=library(one);Path pa=prepared(a),pb=prepared(b),pc=prepared(c);
        Map<Path,String> originals=new LinkedHashMap<>();for(Path p:List.of(pa,pb,pc)){originals.put(p,bytes(p));originals.put(anchorPath(p),bytes(anchorPath(p)));}
        Map<String,Path> members=new LinkedHashMap<>();members.put("nine",pa);members.put("three",pb);members.put("one",pc);
        Path all=collection("thirteen-pairs",members,null);CityPlanningReference.Index index=CityPlanningReference.collectionIndex(all,TODAY);
        eq(index.directionCount(),26,"13 synthetic pairs /26 directions");
        ok(!Objects.equals(map(map(a.get("documents")).get("p0-packet")).get("raw_json"),map(map(b.get("documents")).get("p0-packet")).get("raw_json")),"same document IDs have different original contents");
        eq(map(list(a.get("references")).get(0)).get("reference_id"),map(list(b.get("references")).get(0)).get("reference_id"),"same reference ID across distinct member namespace");
        int memberNo=0;for(Map<String,Object> lib:List.of(a,b,c)){String id=List.of("nine","three","one").get(memberNo++);for(Map<?,?> event:CityPlanningReference.heads(lib).values()){
            Map<?,?> s=CityPlanningReference.map(event.get("snapshot")),from=CityPlanningReference.map(s.get("from")),to=CityPlanningReference.map(s.get("to"));
            for(boolean forward:List.of(true,false)){Map<?,?> f=forward?from:to,t=forward?to:from;Map<?,?> value=index.lookup(f.get("province").toString(),f.get("city").toString(),t.get("province").toString(),t.get("city").toString());
                eq(value.get("status"),"planning_reference","actual member lookup");eq(value.get("collection_member_id"),id,"member provenance");eq(value.get("reference_id"),s.get("reference_id"),"original ref ID retained");
                eq(CityPlanningReference.map(value.get("outbound")).get("from"),f,"no cross-library direction mixing");
                for(String flag:List.of("transport_verified","time_score_applicable","air_fallback_trigger","rail_exclusion_complete"))eq(value.get(flag),false,"no stronger capability");
            }}}
        eq(index.buildMetrics().get("content_file_reads"),8L,"collection+anchor and each child+anchor read once");
        eq(index.buildMetrics().get("file_json_parses"),8L,"eight file parses");eq(index.buildMetrics().get("full_library_validations"),3L,"three independent full child validations");eq(index.buildMetrics().get("source_document_parses"),65L,"same IDs parsed within own member");
        eq(index.buildMetrics().get("direction_fact_validations"),26L,"26 independent facts");
        var repeated=CityPlanningReference.observe(()->{for(int i=0;i<100;i++)query(index,"上海","上海");return 0;});ok(repeated.metrics().values().stream().allMatch(v->v==0),"100 request-snapshot lookups no additional I/O");
        rejectedImmutable(()->query(index,"上海","上海").put("status","changed"));
        pending(CityPlanningReference.collectionIndex(all,TODAY.plusDays(91)),"上海","上海","source_or_review_stale");
        String oldSingle=System.getProperty(CityPlanningReference.PROPERTY),oldCollection=System.getProperty(CityPlanningCollection.PROPERTY);
        try{System.setProperty(CityPlanningReference.PROPERTY,"");System.setProperty(CityPlanningCollection.PROPERTY,all.toString());eq(CityPlanningReference.index(TODAY).directionCount(),26,"collection config");
            System.setProperty(CityPlanningReference.PROPERTY,pa.toString());var conflict=CityPlanningReference.index(TODAY);pending(conflict,"上海","上海","planning_configuration_conflict");ok(conflict.buildMetrics().values().stream().allMatch(v->v==0),"configuration conflict before I/O");
            System.setProperty(CityPlanningCollection.PROPERTY,"");var single=CityPlanningReference.index(TODAY);eq(single.directionCount(),18,"legacy single-library API");eq(single.buildMetrics().get("content_file_reads"),2L,"legacy I/O count unchanged");
            eq(query(single,"上海","上海"),CityPlanningReference.lookup(CityPlanningReference.load(pa),"江苏","南京","上海","上海",TODAY),"legacy exact output parity");
        }finally{restore(CityPlanningReference.PROPERTY,oldSingle);restore(CityPlanningCollection.PROPERTY,oldCollection);}
        // Cross-library duplicate pairs are detected from heads before either result can win.
        Path overlap=prepared(library(new String[][]{{"上海","上海"}}));Path duplicates=collection("duplicates",new LinkedHashMap<>(Map.of("a",pa,"overlap",overlap,"one",pc)),null);
        var duplicateIndex=CityPlanningReference.collectionIndex(duplicates,TODAY);pending(duplicateIndex,"上海","上海","collection_duplicate_city_pair");eq(duplicateIndex.lookup("上海","上海","江苏","南京").get("reason"),"collection_duplicate_city_pair","both directions blocked");
        eq(query(duplicateIndex,"四川","成都").get("status"),"planning_reference","unrelated duplicate-free pair available");
        Path withdrawal=prepared(revoked(library(new String[][]{{"上海","上海"}})));Path revokedDuplicate=collection("revoked-overlap",new LinkedHashMap<>(Map.of("a",pa,"revoked",withdrawal,"one",pc)),null);
        pending(CityPlanningReference.collectionIndex(revokedDuplicate,TODAY),"上海","上海","collection_cross_library_revocation_conflict");
        // Honest update: retain old child history while repinning to a new immutable version.
        Map<String,Object> small=library(new String[][]{{"上海","上海"}});Path oldChild=prepared(small),old=collection("update",new LinkedHashMap<>(Map.of("a",oldChild,"b",pc)),null);
        Path newChild=prepared(revoked(small));Path updated=collection("update",new LinkedHashMap<>(Map.of("a",newChild,"b",pc)),old);
        var newer=CityPlanningReference.collectionIndex(updated,TODAY);pending(newer,"上海","上海","reference_revoked");eq(query(newer,"四川","成都").get("status"),"planning_reference","unrelated member unaffected");
        pending(CityPlanningReference.collectionIndex(old,TODAY),"上海","上海","collection_rollback");
        Path movedOld=fresh("old-at-new-path");Files.copy(old,movedOld);Files.copy(anchorPath(old),anchorPath(movedOld));pending(CityPlanningReference.collectionIndex(movedOld,TODAY),"上海","上海","collection_rollback");
        rejected("collection_member_removed",()->collection("update",new LinkedHashMap<>(Map.of("a",newChild)),updated));
        rejected("collection_child_revision_rollback",()->collection("update",new LinkedHashMap<>(Map.of("a",oldChild,"b",pc)),updated));
        Map<String,Object> rewritten=revoked(small);Map<String,Object> chain=map(list(rewritten.get("references")).get(0));String prior="";for(Object raw:list(chain.get("revisions"))){Map<String,Object> e=map(raw);e.put("previous_hash",prior);e.put("change_reason","rewritten old reason");e.put("event_sha256",CityPlanningReference.eventHash(e));prior=e.get("event_sha256").toString();}
        // Increase the outer child revision to reach the historical-chain guard;
        // unchanged revision 2 is correctly rejected earlier as same-version mutation.
        rewritten.put("library_revision",3);Path rewrittenChild=prepared(rewritten);rejected("reference_history_rewritten",()->collection("update",new LinkedHashMap<>(Map.of("a",rewrittenChild,"b",pc)),updated));
        Map<String,Object> altered=read(updated);map(list(altered.get("revisions")).get(1)).put("change_reason","same version changed");rehash(altered);Path alteredFile=fresh("altered-collection");writeCollection(alteredFile,altered);pending(CityPlanningReference.collectionIndex(alteredFile,TODAY),"上海","上海","collection_same_revision_changed");
        // One bad child invalidates the collection, never skipped in favour of a sibling.
        Path pinnedChild=prepared(small);Path pinned=collection("pinned",new LinkedHashMap<>(Map.of("a",pinnedChild,"b",pc)),null);
        String original=bytes(pinnedChild);Files.writeString(pinnedChild,original+"\n");var broken=CityPlanningReference.collectionIndex(pinned,TODAY);eq(broken.directionCount(),0,"failed member produces no partial collection");pending(broken,"四川","成都","planning_pinned_file_mismatch");
        Files.writeString(pinnedChild,original);Path aside=fresh("moved-source");Files.move(pinnedChild,aside);pending(CityPlanningReference.collectionIndex(pinned,TODAY),"四川","成都","member_file_missing");Files.move(aside,pinnedChild);
        Path movedAnchor=fresh("moved-anchor");Files.move(anchorPath(pinnedChild),movedAnchor);pending(CityPlanningReference.collectionIndex(pinned,TODAY),"四川","成都","member_file_missing");Files.move(movedAnchor,anchorPath(pinnedChild));
        Path damaged=collection("bad-collection-anchor",members,null);Files.writeString(anchorPath(damaged),"{}");pending(CityPlanningReference.collectionIndex(damaged,TODAY),"上海","上海","collection_anchor_mismatch");
        Path identity=collection("stable-identity",members,null);Map<String,Object> changedId=read(identity);changedId.put("collection_id","replacement-identity");for(Object raw:list(changedId.get("revisions")))map(map(raw).get("snapshot")).put("collection_id","replacement-identity");rehash(changedId);writeCollection(identity,changedId);pending(CityPlanningReference.collectionIndex(identity,TODAY),"上海","上海","collection_identity_changed");
        rejected("collection_duplicate_member_path",()->collection("dup-path",new LinkedHashMap<>(Map.of("a",pa,"alias",pa)),null));
        Path symlink=root.resolve("member-link.json");Files.createSymbolicLink(symlink,pa);rejected("file_missing_or_symlink",()->collection("symlink",new LinkedHashMap<>(Map.of("a",symlink)),null));
        Path outputExists=fresh("existing");Files.writeString(outputExists,"retain");rejected("collection_target_or_anchor_exists",()->CityPlanningCollection.prepare("output-exists",members,null,outputExists,TODAY));eq(bytes(outputExists),"retain","existing target unmodified");
        Path onlyAnchor=fresh("only-anchor");Files.writeString(anchorPath(onlyAnchor),"retain anchor");rejected("collection_target_or_anchor_exists",()->CityPlanningCollection.prepare("anchor-exists",members,null,onlyAnchor,TODAY));eq(bytes(anchorPath(onlyAnchor)),"retain anchor","existing anchor unmodified");
        Path cli=fresh("cli");PrepareCityPlanningCollection.main(new String[]{cli.toString(),"cli",TODAY.toString(),"nine="+pa,"three="+pb,"one="+pc});eq(CityPlanningReference.collectionIndex(cli,TODAY).directionCount(),26,"actual offline CLI");
        for(var e:originals.entrySet())eq(bytes(e.getKey()),e.getValue(),"original synthetic parent and anchor bytes unchanged");
        Files.writeString(root.resolve("receipt.json"),Json.write(m("checks",checks,"synthetic_pairs",13,"directions",26,"model_used",false,"real_private_library_used",false,"production_changed",false))+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("Planning collection: "+checks+" assertions passed; synthetic only; "+root);
    }
    static void rejectedImmutable(Runnable edit){try{edit.run();throw new AssertionError("mutable snapshot");}catch(UnsupportedOperationException expected){checks++;}}
    static void restore(String key,String old){if(old==null)System.clearProperty(key);else System.setProperty(key,old);}
}
