package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Compile independently checked public facts; never fetches, approves,
 * deploys or alters existing libraries. Input metadata is an explicit curation record. */
public final class PublicCityReferencePrepare {
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) {return (Map<String,Object>)value;}
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("input-spec.json NEW-output.json YYYY-MM-DD");
        Path input=Path.of(args[0]),output=Path.of(args[1]);LocalDate today=LocalDate.parse(args[2]);
        if(Files.size(input)>4*1024*1024)throw new IllegalArgumentException("input too large");
        Map<String,Object> spec=map(Json.parse(Files.readString(input))),bundle=new LinkedHashMap<>(spec);
        if(!PublicTransportEvidence.schema(bundle))throw new IllegalArgumentException("public schema required");
        Map<String,String> hashes=new LinkedHashMap<>();
        for(Object raw:CityTravelReference.list(bundle.get("evidence_snapshots"))) {
            Map<String,Object> evidence=map(raw);String id=Objects.toString(evidence.get("evidence_id"),"");
            if(id.isBlank()||hashes.containsKey(id))throw new IllegalArgumentException("duplicate/missing evidence id");
            String hash=CityTravelReference.sha256(evidence.get("content"));hashes.put(id,hash);evidence.put("sha256",hash);
        }
        List<Object> references=new ArrayList<>();
        for(Object raw:CityTravelReference.list(spec.get("references"))) {
            Map<String,Object> ref=map(raw);bind(ref,hashes);
            Map<String,Object> event=new LinkedHashMap<>();event.put("snapshot",ref);event.put("previous_hash","");
            event.put("change_reason","首次整理公开城市通达事实；非实时行程、非人工认证");
            event.put("event_sha256",CityTravelReference.eventHash(event));
            references.add(Map.of("reference_id",ref.get("reference_id"),"revisions",List.of(event)));
        }
        bundle.put("references",references);List<Object> results=new ArrayList<>();
        for(Map<?,?> event:CityTravelReference.heads(bundle).values()) {
            Map<?,?> ref=CityTravelReference.map(event.get("snapshot"));
            for(boolean reverse:List.of(false,true)) {
                String fp=CityTravelReference.text(ref,reverse?"to_province":"from_province"),fc=CityTravelReference.text(ref,reverse?"to_city":"from_city");
                String tp=CityTravelReference.text(ref,reverse?"from_province":"to_province"),tc=CityTravelReference.text(ref,reverse?"from_city":"to_city");
                Map<String,Object> result=CityTravelReference.lookup(bundle,fp,fc,tp,tc,today);
                if(!List.of("rail","air").contains(Objects.toString(result.get("eligibility"),"")))
                    throw new IllegalArgumentException("reference not accepted: "+Json.write(result));
                results.add(result);
            }
        }
        if(results.isEmpty())throw new IllegalArgumentException("no accepted reference");
        Files.writeString(output,Json.write(CityTravelReference.canonical(bundle)),StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(output,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        System.out.println(Json.write(Map.of("canonical_sha256",CityTravelReference.sha256(bundle),"direction_checks",results,"output",output.toString(),"production_changed",false)));
    }
    static void bind(Object value,Map<String,String> hashes) {
        if(value instanceof Map<?,?>) {
            Map<String,Object> row=map(value);
            if(row.containsKey("evidence_id")) {
                String hash=hashes.get(row.get("evidence_id"));if(hash==null)throw new IllegalArgumentException("unknown evidence link");
                row.put("source_sha256",hash);
            }
            for(Object child:row.values())bind(child,hashes);
        } else if(value instanceof List<?>)for(Object child:(List<?>)value)bind(child,hashes);
    }
}
