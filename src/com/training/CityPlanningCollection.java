package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Anchored, append-only membership of immutable planning libraries, not a merged document
 * namespace. Hashes bind supplied files; they are not signatures or fresh source reviews. */
final class CityPlanningCollection {
    static final String SCHEMA="city-planning-collection-v1", ANCHOR="city-planning-collection-integrity-v1";
    static final String PROPERTY="dispatch.city.planning.collection.file", ENV="YANXU_CITY_PLANNING_COLLECTION_FILE";
    private static final int MAX_BYTES=4*1024*1024, MAX_ANCHOR=1024*1024;
    private static final Map<String,Map<?,?>> remembered=new HashMap<>();
    private static final Map<Path,Map<?,?>> rememberedPaths=new HashMap<>();
    record Member(String id,Path file,Map<?,?> library,Map<?,?> expectedAnchor) {}
    record Loaded(Map<?,?> data,List<Member> members,String reason) {boolean valid(){return reason.isEmpty();}}
    private record Structure(List<Map<?,?>> snapshots,Map<?,?> head) {}
    private static void need(boolean b,String message){CityPlanningReference.need(b,message);}
    private static Map<?,?> map(Object x){return CityPlanningReference.map(x);}
    private static List<?> list(Object x){return CityPlanningReference.list(x);}
    private static String text(Map<?,?> x,String k){return CityPlanningReference.text(x,k);}
    private static void keys(Map<?,?> x,String...expected){need(x.keySet().equals(Set.of(expected)),"collection_fields_missing_or_unknown");}
    private static int number(Object x){return CityPlanningReference.number(x);}
    private static LocalDate day(Object x){return CityPlanningReference.day(x);}
    private static boolean same(Object a,Object b){return CityPlanningReference.same(a,b);}
    private static void digest(Object x){need(x instanceof String&&((String)x).matches("[a-f0-9]{64}"),"collection_invalid_sha256");}
    private static Path anchorPath(Path p){return Path.of(p+".integrity.json");}
    private static Path canonicalExisting(Path p)throws java.io.IOException {
        Path absolute=p.toAbsolutePath().normalize();need(Files.isRegularFile(absolute,LinkOption.NOFOLLOW_LINKS),"collection_member_file_missing_or_symlink");
        return absolute.toRealPath();
    }
    private static Path newPath(Path p)throws java.io.IOException {
        Path absolute=p.toAbsolutePath().normalize();need(absolute.getParent()!=null&&absolute.getFileName()!=null,"collection_file_path_required");
        Path target=absolute.getParent().toRealPath().resolve(absolute.getFileName());
        need(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!Files.exists(anchorPath(target),LinkOption.NOFOLLOW_LINKS),"collection_target_or_anchor_exists");return target;
    }
    private static Map<String,Map<?,?>> memberRows(Map<?,?> snapshot){Map<String,Map<?,?>> out=new LinkedHashMap<>();Set<String> paths=new HashSet<>();
        need(!list(snapshot.get("members")).isEmpty()&&list(snapshot.get("members")).size()<=64,"collection_member_count_invalid");
        for(Object raw:list(snapshot.get("members"))){Map<?,?> m=map(raw);keys(m,"member_id","file","file_sha256","anchor_file","anchor_sha256","expected_anchor");
            String id=text(m,"member_id"),file=text(m,"file");need(id.matches("[A-Za-z0-9_.:-]{1,120}")&&out.putIfAbsent(id,m)==null,"collection_duplicate_or_invalid_member_id");
            Path p=Path.of(file);need(p.isAbsolute()&&p.normalize().toString().equals(file)&&paths.add(file),"collection_duplicate_or_noncanonical_member_path");
            need(anchorPath(p).toString().equals(m.get("anchor_file")),"collection_anchor_path_mismatch");digest(m.get("file_sha256"));digest(m.get("anchor_sha256"));
            Map<?,?> a=map(m.get("expected_anchor"));keys(a,"version","library_revision","library_sha256","heads");need(CityPlanningReference.ANCHOR.equals(a.get("version"))&&number(a.get("library_revision"))>0&&!map(a.get("heads")).isEmpty(),"collection_child_anchor_invalid");digest(a.get("library_sha256"));
        }return out;
    }
    private static Structure structure(Map<?,?> data){
        keys(data,"version","collection_id","collection_revision","purpose","policy","revisions");
        String id=text(data,"collection_id");need(SCHEMA.equals(data.get("version"))&&id.matches("[A-Za-z0-9_.:-]{1,120}")&&"prebid_city_planning_only".equals(data.get("purpose")),"collection_schema_or_purpose_invalid");
        Map<?,?> policy=map(data.get("policy"));need(number(policy.get("buffer_minutes"))==CityPlanningReference.BUFFER_MINUTES&&"planning_assumption_not_source_error_bound".equals(policy.get("buffer_basis")),"collection_policy_invalid");
        List<?> history=list(data.get("revisions"));need(!history.isEmpty()&&history.size()<=256&&number(data.get("collection_revision"))==history.size(),"collection_revision_invalid");
        List<Map<?,?>> snapshots=new ArrayList<>();Map<String,Map<?,?>> prior=Map.of();String hash="";LocalDate previousDay=null;int version=0;Map<?,?> head=Map.of();
        for(Object raw:history){Map<?,?> e=map(raw);keys(e,"snapshot","previous_hash","change_reason","event_sha256");Map<?,?> s=map(e.get("snapshot"));keys(s,"collection_id","version","prepared_on","policy_sha256","members");
            need(id.equals(s.get("collection_id"))&&number(s.get("version"))==++version&&hash.equals(e.get("previous_hash"))&&!text(e,"change_reason").isBlank()&&CityPlanningReference.eventHash(e).equals(e.get("event_sha256")),"collection_history_invalid");
            need(CityPlanningReference.hash(policy).equals(s.get("policy_sha256")),"collection_policy_changed");
            LocalDate date=day(s.get("prepared_on"));need(previousDay==null||!date.isBefore(previousDay),"collection_preparation_date_rollback");
            Map<String,Map<?,?>> current=memberRows(s);need(current.keySet().containsAll(prior.keySet()),"collection_member_removed");
            for(var p:prior.entrySet()){Map<?,?> oldAnchor=map(p.getValue().get("expected_anchor")),newAnchor=map(current.get(p.getKey()).get("expected_anchor"));
                int before=number(oldAnchor.get("library_revision")),after=number(newAnchor.get("library_revision"));need(after>=before,"collection_child_revision_rollback");if(after==before)need(same(oldAnchor,newAnchor),"collection_same_child_version_changed");}
            snapshots.add(s);prior=current;hash=text(e,"event_sha256");previousDay=date;head=e;
        }return new Structure(snapshots,head);
    }
    static Map<String,Object> anchor(Map<?,?> data){Structure s=structure(data);return Map.of("version",ANCHOR,"collection_id",data.get("collection_id"),"collection_revision",data.get("collection_revision"),"collection_sha256",CityPlanningReference.hash(data),"head_sha256",s.head.get("event_sha256"));}
    private static void continuation(Map<?,?> previous,Map<?,?> data){
        need(Objects.equals(previous.get("collection_id"),data.get("collection_id")),"collection_identity_changed");int before=number(previous.get("collection_revision")),after=number(data.get("collection_revision"));
        need(after>=before,"collection_rollback");if(after==before)need(previous.get("collection_sha256").equals(CityPlanningReference.hash(data)),"collection_same_revision_changed");
        need(list(data.get("revisions")).size()>=before&&Objects.equals(map(list(data.get("revisions")).get(before-1)).get("event_sha256"),previous.get("head_sha256")),"collection_history_rewritten");
    }
    private static Loaded validateMembers(Map<?,?> data,LocalDate today)throws java.io.IOException {
        Structure structure=structure(data);Map<?,?> current=structure.snapshots.get(structure.snapshots.size()-1);need(!day(current.get("prepared_on")).isAfter(today),"collection_future_preparation");
        List<Member> members=new ArrayList<>();
        for(var entry:memberRows(current).entrySet()){
            Map<?,?> row=entry.getValue();Path supplied=Path.of(text(row,"file")),file=canonicalExisting(supplied);need(file.toString().equals(supplied.toString()),"collection_noncanonical_resolved_path");
            need(canonicalExisting(anchorPath(file)).toString().equals(row.get("anchor_file")),"collection_noncanonical_anchor_path");
            Map<?,?> library=CityPlanningReference.loadPinned(file,text(row,"file_sha256"),text(row,"anchor_sha256"));
            need(!library.containsKey("load_status"),"collection_member_load_failed: "+entry.getKey()+": "+library.get("load_reason"));
            need(same(CityPlanningReference.anchor(library),row.get("expected_anchor")),"collection_child_anchor_binding_mismatch");
            need(Objects.equals(data.get("purpose"),library.get("purpose"))&&Objects.equals(data.get("policy"),library.get("policy")),"collection_child_outer_contract_mismatch");
            // The current child must retain every earlier declared head, including revoked ones.
            for(Map<?,?> old:structure.snapshots){Map<?,?> oldRow=memberRows(old).get(entry.getKey());if(oldRow!=null)CityPlanningReference.continuation(map(oldRow.get("expected_anchor")),library);}
            members.add(new Member(entry.getKey(),file,library,map(row.get("expected_anchor"))));
        }return new Loaded(data,List.copyOf(members),"");
    }
    static synchronized Loaded load(Path path,LocalDate today){try{
        Path file=canonicalExisting(path),integrity=anchorPath(file);need(Files.size(file)<=MAX_BYTES&&Files.isRegularFile(integrity,LinkOption.NOFOLLOW_LINKS)&&Files.size(integrity)<=MAX_ANCHOR,"collection_file_or_anchor_invalid");
        Map<?,?> data=CityPlanningReference.readBoundJson(file,null),stored=CityPlanningReference.readBoundJson(integrity,null);
        need(same(anchor(data),stored),"collection_anchor_mismatch");String id=text(data,"collection_id");
        if(remembered.containsKey(id))continuation(remembered.get(id),data);
        if(rememberedPaths.containsKey(file))continuation(rememberedPaths.get(file),data);
        Loaded validated=validateMembers(data,today);Map<String,Object> verifiedAnchor=anchor(data);remembered.put(id,verifiedAnchor);rememberedPaths.put(file,verifiedAnchor);return validated;
    }catch(Exception e){return new Loaded(Map.of(),List.of(),Objects.toString(e.getMessage(),"collection_invalid"));}}

    /** Explicit offline preparation only; caller supplies each member ID, no implicit renaming.
     * A previous collection preserves its full history; absent previous means revision one. */
    static synchronized Map<String,Object> prepare(String id,Map<String,Path> inputs,Path previous,Path requested,LocalDate today)throws java.io.IOException {
        Path target=newPath(requested);need(inputs!=null&&!inputs.isEmpty()&&inputs.size()<=64,"collection_member_count_invalid");
        Map<?,?> old=Map.of();if(previous!=null){Loaded loaded=load(previous,today);need(loaded.valid(),"previous_collection_invalid: "+loaded.reason);old=loaded.data;need(id.equals(old.get("collection_id")),"collection_identity_changed");}
        List<Object> rows=new ArrayList<>();Object policy=null;Set<Path> paths=new HashSet<>();
        for(var entry:inputs.entrySet()){
            Path file=canonicalExisting(entry.getValue());need(paths.add(file),"collection_duplicate_member_path");Path integrity=canonicalExisting(anchorPath(file));need(integrity.equals(anchorPath(file)),"collection_noncanonical_anchor_path");
            need(Files.size(file)<=16*1024*1024&&Files.size(integrity)<=MAX_ANCHOR,"collection_member_size_invalid");
            String fileSha=CityPlanningReference.rawHash(Files.readString(file)),anchorSha=CityPlanningReference.rawHash(Files.readString(integrity));
            Map<?,?> library=CityPlanningReference.loadPinned(file,fileSha,anchorSha);need(!library.containsKey("load_status"),"collection_member_load_failed: "+entry.getKey()+": "+library.get("load_reason"));
            if(policy==null)policy=library.get("policy");else need(Objects.equals(policy,library.get("policy")),"collection_child_outer_contract_mismatch");
            rows.add(Map.of("member_id",entry.getKey(),"file",file.toString(),"file_sha256",fileSha,"anchor_file",integrity.toString(),"anchor_sha256",anchorSha,"expected_anchor",CityPlanningReference.anchor(library)));
        }
        List<Object> revisions=new ArrayList<>(list(old.get("revisions")));int version=revisions.size()+1;
        Map<String,Object> snapshot=Map.of("collection_id",id,"version",version,"prepared_on",today.toString(),"policy_sha256",CityPlanningReference.hash(policy),"members",rows);
        Map<String,Object> event=new LinkedHashMap<>();event.put("snapshot",snapshot);event.put("previous_hash",revisions.isEmpty()?"":map(revisions.get(revisions.size()-1)).get("event_sha256"));event.put("change_reason","Offline collection preparation; child files, source dates and reference identities remain unchanged. Not new source research.");event.put("event_sha256",CityPlanningReference.eventHash(event));revisions.add(event);
        Map<String,Object> data=new LinkedHashMap<>();data.put("version",SCHEMA);data.put("collection_id",id);data.put("collection_revision",version);data.put("purpose","prebid_city_planning_only");data.put("policy",policy);data.put("revisions",revisions);
        validateMembers(data,today);if(!old.isEmpty())continuation(anchor(old),data);if(remembered.containsKey(id))continuation(remembered.get(id),data);
        String raw=Json.write(data)+"\n",bound=Json.write(anchor(data))+"\n";need(raw.getBytes(StandardCharsets.UTF_8).length<=MAX_BYTES&&bound.getBytes(StandardCharsets.UTF_8).length<=MAX_ANCHOR,"collection_size_invalid");newPath(target);
        Files.writeString(target,raw,StandardOpenOption.CREATE_NEW);Files.writeString(anchorPath(target),bound,StandardOpenOption.CREATE_NEW);
        Loaded actual=load(target,today);need(actual.valid(),"prepared_collection_load_failed: "+actual.reason);return data;
    }
    private CityPlanningCollection(){}
}
