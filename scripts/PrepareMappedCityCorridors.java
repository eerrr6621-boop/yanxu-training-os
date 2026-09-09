package com.training;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Research-only city grouping; explicit observed mappings never grant admission. */
public final class PrepareMappedCityCorridors {
    static void need(boolean ok,String why){if(!ok)throw new IllegalArgumentException(why);}
    static Map<?,?> map(Object x){need(x instanceof Map<?,?>,"object_required");return (Map<?,?>)x;}
    static List<?> list(Object x){need(x instanceof List<?>,"list_required");return (List<?>)x;}
    static String text(Object x){need(x instanceof String&&!((String)x).isBlank(),"text_required");return (String)x;}
    static Map<String,Object> obj(Object... kv){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    static int minutes(Object x){need(x instanceof Number,"minutes_required");double n=((Number)x).doubleValue();need(Double.isFinite(n)&&n==Math.rint(n)&&n>0&&n<1440,"invalid_minutes");return (int)n;}
    static List<String> city(Map<?,?> m){return List.of(text(m.get("province")),text(m.get("city")));}
    static String key(List<String> c){return String.join("/",c);}
    static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}

    static Map<String,Object> build(Map<?,?> corridors,Map<?,?> observation,Map<String,Set<String>> directory) {
        // Reuse the station-level checker, including counts, both directions,
        // strict threshold flags and pending-only controls, before regrouping.
        PrepareStationCityReview.build(corridors,directory);
        need("station_city_observation_v1".equals(observation.get("schema")),"mapping_schema");
        need("first_read_only".equals(observation.get("review_status"))&&Boolean.FALSE.equals(observation.get("production_adopted")),"research_mapping_only");
        Set<String> sources=new HashSet<>();
        for(Object raw:list(observation.get("sources"))) {
            var s=map(raw);need(sources.add(text(s.get("id"))),"duplicate_source");
            String url=text(s.get("url"));need(url.startsWith("https://")||url.startsWith("http://"),"source_url_required");
            need(Boolean.TRUE.equals(s.get("actual_original_page_read")),"unread_source");
        }
        Map<String,Map<?,?>> stations=new LinkedHashMap<>();
        for(Object raw:list(observation.get("stations"))) {
            var s=map(raw);String name=text(s.get("station"));List<String> c=city(s);
            need(directory.containsKey(c.get(0))&&directory.get(c.get(0)).contains(c.get(1)),"unregistered_city");
            need("first_read_only".equals(s.get("review_status"))&&"named_city_station".equals(s.get("scope")),"unknown_mapping_scope");
            List<?> ids=list(s.get("source_ids"));need(!ids.isEmpty()&&ids.stream().allMatch(sources::contains),"mapping_source_missing");
            text(s.get("basis"));need(stations.putIfAbsent(name,s)==null,"duplicate_station_mapping");
        }
        Map<List<String>,List<Map<String,Object>>> groups=new TreeMap<>(Comparator.comparing(x->String.join("\u0000",x)));
        List<Object> unmapped=new ArrayList<>();int all=0,mapped=0,intra=0;
        List<?> pairs=list(corridors.get("station_pairs"));
        for(int index=0;index<pairs.size();index++) {
            Map<?,?> pair=map(pairs.get(index));
            for(String direction:List.of("forward_samples","reverse_samples"))for(Object raw:list(pair.get(direction))) {
                Map<?,?> sample=map(raw);String from=text(sample.get("from_station")),to=text(sample.get("to_station"));
                int duration=minutes(sample.get("minutes"));all++;
                if(!stations.containsKey(from)||!stations.containsKey(to)) {
                    unmapped.add(obj("pair_index",index,"direction",direction,"from_station",from,"to_station",to));continue;
                }
                List<String> a=city(stations.get(from)),b=city(stations.get(to));mapped++;
                if(a.equals(b)){intra++;continue;}
                List<String> k=new ArrayList<>(List.of(key(a),key(b)));Collections.sort(k);
                var row=obj("from_city",a,"to_city",b,"from_station",from,"to_station",to,"minutes",duration,
                    "original_pair_index",index,"original_direction",direction,"source_sample",raw,
                    "from_mapping",stations.get(from),"to_mapping",stations.get(to));
                groups.computeIfAbsent(List.copyOf(k),v->new ArrayList<>()).add(row);
            }
        }
        List<Object> result=new ArrayList<>();int both=0,under=0;
        for(var entry:groups.entrySet()) {
            String a=entry.getKey().get(0),b=entry.getKey().get(1);List<Object> f=new ArrayList<>(),r=new ArrayList<>();
            Set<String> as=new TreeSet<>(),bs=new TreeSet<>();boolean fu=false,ru=false;
            for(var row:entry.getValue()) {
                boolean forward=key(city(stations.get((String)row.get("from_station")))).equals(a);
                (forward?f:r).add(row);if(forward)fu|=(Integer)row.get("minutes")<240;else ru|=(Integer)row.get("minutes")<240;
                as.add((String)row.get(forward?"from_station":"to_station"));bs.add((String)row.get(forward?"to_station":"from_station"));
            }
            if(!f.isEmpty()&&!r.isEmpty())both++;if(fu&&ru)under++;
            result.add(obj("cities",List.of(a,b),"forward_samples",f,"reverse_samples",r,
                "both_directions_have_under240_sample",fu&&ru,"station_names",List.of(as,bs),
                "different_stations_within_city",as.size()>1||bs.size()>1,"review_status","needs_independent_source_and_mapping_review",
                "city_admitted",false,"air_fallback_proven",false,"transfer_time_estimated",false));
        }
        return obj("schema","mapped_city_corridor_research_v1","all_directed_samples",all,"mapped_directed_samples",mapped,
            "unmapped_directed_samples",unmapped.size(),"intra_city_directed_samples",intra,"city_pairs",result,
            "bidirectional_city_pairs",both,"under240_city_candidates",under,"unmapped_queue",unmapped,
            "new_city_admissions",0,"production_changed",false,"warning","City grouping is provisional research only. No mirrored direction, station equivalence, transfer, shortest-time, railway-exclusion or authorization claim.");
    }
    public static void main(String[] args)throws Exception {
        need(args.length==5,"usage: corridors.json sha256 mapping.json sha256 NEW-private-output.json");
        Map<?,?>[] inputs=new Map<?,?>[2];Map<String,Object> pins=new LinkedHashMap<>();
        for(int i=0;i<2;i++) {
            Path p=Path.of(args[i*2]);need(Files.size(p)<=16*1024*1024,"input_too_large");byte[] bytes=Files.readAllBytes(p);
            need(hash(bytes).equals(args[i*2+1]),"input_hash_mismatch");inputs[i]=map(Json.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)));
            pins.put(p.toAbsolutePath().normalize().toString(),hash(bytes));
        }
        Path output=PrepareStationCityReview.privateNewOutput(Path.of(args[4]));
        var result=build(inputs[0],inputs[1],RegionDirectory.CITIES);result.put("input_pins",pins);
        Files.writeString(output,Json.write(result)+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println(Json.write(obj("under240_city_candidates",result.get("under240_city_candidates"),"new_city_admissions",0,"output_sha256",hash(Files.readAllBytes(output)))));
    }
}
