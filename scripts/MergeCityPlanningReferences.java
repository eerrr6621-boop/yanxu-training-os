package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Offline, conflict-refusing merge of already prepared planning libraries.
 * No source reading, re-curation, ID rewriting, configuration changes or overwrite.
 * An anchor proves consistency with the supplied parent, not reader authenticity. */
public final class MergeCityPlanningReferences {
    private static final int MAX_FILE=16*1024*1024, MAX_ANCHOR=1024*1024;
    private static final List<String> OUTER=List.of("version","use_basis","license_claimed",
        "local_planning_import_allowed","article_republication_allowed","strict_runtime_admission","purpose","policy");
    private record Parent(Path file,Path anchorFile,Map<?,?> data,Map<?,?> anchor,String fileSha,String anchorSha) {}

    private static void need(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
    private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static byte[] read(Path file,int limit)throws java.io.IOException {
        need(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)<=limit,"missing_symlink_or_oversized_input: "+file);
        byte[] bytes=Files.readAllBytes(file);need(bytes.length<=limit,"input_grew_beyond_limit: "+file);return bytes;
    }
    private static Path normalized(Path file)throws java.io.IOException {
        Path p=file.toAbsolutePath().normalize();need(p.getFileName()!=null&&p.getParent()!=null,"file_path_required");
        return p.getParent().toRealPath().resolve(p.getFileName());
    }
    private static Path anchorPath(Path file){return Path.of(file+".integrity.json");}
    private static void newTarget(Path file){need(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&!Files.exists(anchorPath(file),LinkOption.NOFOLLOW_LINKS),"target_or_anchor_already_exists: "+file);}
    private static Parent loadParent(Path file)throws java.io.IOException {
        Path anchor=anchorPath(file);byte[] before=read(file,MAX_FILE),anchorBefore=read(anchor,MAX_ANCHOR);
        Map<?,?> loaded=CityPlanningReference.load(file);
        need(!loaded.containsKey("load_status"),"parent_load_rejected: "+file+": "+loaded.get("load_reason"));
        Map<?,?> supplied=CityPlanningReference.map(Json.parse(new String(anchorBefore,StandardCharsets.UTF_8)));
        need(CityPlanningReference.same(loaded,Json.parse(new String(before,StandardCharsets.UTF_8))),"parent_changed_during_load: "+file);
        need(CityPlanningReference.same(CityPlanningReference.anchor(loaded),supplied),"parent_anchor_changed_during_load: "+file);
        Parent p=new Parent(file,anchor,loaded,supplied,sha(before),sha(anchorBefore));unchanged(p);return p;
    }
    private static void unchanged(Parent p)throws java.io.IOException {
        need(p.fileSha.equals(sha(read(p.file,MAX_FILE)))&&p.anchorSha.equals(sha(read(p.anchorFile,MAX_ANCHOR))),"parent_changed: "+p.file);
    }
    /** Map ordering may differ, but all parsed fields/list order must agree. In particular
     * an embedded raw_json string, its whitespace, source metadata and raw hash are exact. */
    private static boolean identical(Object a,Object b){return Objects.equals(a,b);}
    private static void putUnique(Map<String,Object> values,String id,Object value,String kind){
        need(!id.isBlank(),"empty_"+kind+"_id");
        if(values.containsKey(id))need(identical(values.get(id),value),kind+"_conflict: "+id);
        else values.put(id,value);
    }

    static Map<String,Object> merge(List<Path> inputPaths,Path requestedOutput,int revision,LocalDate asOf)throws java.io.IOException {
        Objects.requireNonNull(asOf,"asOf");need(inputPaths!=null&&inputPaths.size()>=2&&inputPaths.size()<=64,"two_to_64_parent_paths_required");
        need(revision>0&&revision<100000,"new_library_revision_out_of_range");Path output=normalized(requestedOutput);newTarget(output);
        List<Parent> parents=new ArrayList<>();Set<Path> unique=new HashSet<>();int maximum=0;
        for(Path input:inputPaths){Path file=normalized(input);need(unique.add(file),"duplicate_parent_path: "+file);
            Parent p=loadParent(file);parents.add(p);maximum=Math.max(maximum,CityPlanningReference.number(p.data.get("library_revision")));}
        need(revision>maximum,"new_library_revision_must_exceed_all_parents");
        Map<String,Object> merged=new LinkedHashMap<>(),documents=new LinkedHashMap<>(),references=new LinkedHashMap<>();
        Map<?,?> first=parents.get(0).data;
        for(String key:OUTER){need(first.containsKey(key),"outer_field_missing: "+key);merged.put(key,first.get(key));}
        merged.put("library_revision",revision);
        for(Parent p:parents){
            for(String key:OUTER)need(p.data.containsKey(key)&&identical(first.get(key),p.data.get(key)),"outer_contract_conflict: "+key);
            for(var entry:CityPlanningReference.map(p.data.get("documents")).entrySet()){
                need(entry.getKey() instanceof String,"document_key_not_string");putUnique(documents,(String)entry.getKey(),entry.getValue(),"document");}
            for(Object raw:CityPlanningReference.list(p.data.get("references"))){Map<?,?> chain=CityPlanningReference.map(raw);putUnique(references,CityPlanningReference.text(chain,"reference_id"),chain,"reference");}
        }
        merged.put("documents",documents);merged.put("references",new ArrayList<>(references.values()));
        // The real validator rejects duplicate city pairs even with different IDs; no rename.
        CityPlanningReference.heads(merged);
        List<Object> provenance=new ArrayList<>();
        for(Parent p:parents){
            CityPlanningReference.continuation(p.anchor,merged);
            provenance.add(Map.of("file",p.file.toString(),"file_sha256",p.fileSha,"anchor_file",p.anchorFile.toString(),
                "anchor_file_sha256",p.anchorSha,"canonical_library_sha256",p.anchor.get("library_sha256"),
                "library_revision",p.data.get("library_revision"),"continuation_validated",true));
        }
        merged.put("merge_provenance",Map.of("schema","city_planning_offline_merge_v1","prepared_as_of",asOf.toString(),
            "prepared_as_of_is_source_review_date",false,"new_source_read",false,"source_revalidation_claimed",false,
            "reference_ids_rewritten",false,"original_revision_chains_unchanged",true,"parents",provenance,
            "note","Offline union of validated supplied libraries; no new research or renewed review date. Parent statistics and unrelated top-level metadata are not copied."));
        for(Parent p:parents)CityPlanningReference.continuation(p.anchor,merged);
        need((Json.write(merged)+"\n").getBytes(StandardCharsets.UTF_8).length<=MAX_FILE,"merged_library_exceeds_loader_limit");
        need((Json.write(CityPlanningReference.anchor(merged))+"\n").getBytes(StandardCharsets.UTF_8).length<=MAX_ANCHOR,"merged_anchor_exceeds_loader_limit");
        for(Parent p:parents)unchanged(p);newTarget(output);
        // Existing CREATE_NEW preparation preserves partial outputs on I/O failure for audit;
        // this tool never deletes or overwrites them. Concurrent writers are not supported.
        CityPlanningReference.prepare(merged,output,asOf);
        Map<?,?> actual=CityPlanningReference.load(output);need(!actual.containsKey("load_status"),"merged_load_rejected: "+actual.get("load_reason"));
        need(CityPlanningReference.same(merged,actual),"merged_load_content_mismatch");
        for(Parent p:parents){CityPlanningReference.continuation(p.anchor,actual);unchanged(p);}
        return merged;
    }
    public static void main(String[] args)throws Exception {
        if(args.length==1&&"--help".equals(args[0])){System.out.println("MergeCityPlanningReferences NEW-output.json NEW-library-revision YYYY-MM-DD parent1.json parent2.json [...]. Offline only; existing target or anchor and all ID conflicts are rejected.");return;}
        need(args.length>=5,"Usage: MergeCityPlanningReferences NEW-output.json NEW-library-revision YYYY-MM-DD parent1.json parent2.json [...]");
        int revision;try{revision=Integer.parseInt(args[1]);}catch(NumberFormatException e){throw new IllegalArgumentException("integer_library_revision_required",e);}
        LocalDate date=CityPlanningReference.day(args[2]);List<Path> parents=new ArrayList<>();for(int i=3;i<args.length;i++)parents.add(Path.of(args[i]));
        Path output=normalized(Path.of(args[0]));Map<String,Object> result=merge(parents,output,revision,date);
        System.out.println(Json.write(Map.of("status","prepared_and_loaded","output",output.toString(),"anchor",anchorPath(output).toString(),
            "library_revision",revision,"parent_count",parents.size(),"reference_count",CityPlanningReference.list(result.get("references")).size(),
            "canonical_sha256",CityPlanningReference.hash(result),"source_revalidation_claimed",false,"production_configuration_changed",false)));
    }
    private MergeCityPlanningReferences(){}
}
