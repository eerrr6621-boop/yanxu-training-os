package com.training;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Research work queue only. Name matches are suggestions, never geographical proof. */
public final class PrepareStationCityReview {
    static void need(boolean ok,String reason){if(!ok)throw new IllegalArgumentException(reason);}
    static Map<?,?> map(Object x){need(x instanceof Map<?,?>,"object_required");return (Map<?,?>)x;}
    static List<?> list(Object x){need(x instanceof List<?>,"list_required");return (List<?>)x;}
    static String string(Object x){need(x instanceof String&&!((String)x).isBlank(),"text_required");return (String)x;}
    static int integer(Object x){need(x instanceof Number,"integer_required");double d=((Number)x).doubleValue();need(Double.isFinite(d)&&d==Math.rint(d)&&d>=0&&d<=Integer.MAX_VALUE,"integer_required");return (int)d;}
    static Map<String,Object> obj(Object... a){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<a.length;i+=2)out.put((String)a[i],a[i+1]);return out;}
    static List<Map<String,Object>> suggestions(String station,Map<String,Set<String>> directory){
        List<Map<String,Object>> out=new ArrayList<>();
        directory.forEach((province,cities)->cities.forEach(city->{
            if(station.startsWith(city))out.add(obj("province",province,"city",city,"basis","literal_name_only","geography_verified",false));
        }));
        return out;
    }
    static boolean under(List<?> samples,String from,String to){
        boolean found=false;
        for(Object raw:samples){Map<?,?> row=map(raw);int minutes=integer(row.get("minutes"));
            need(minutes>0&&minutes<1440,"invalid_same_day_minutes");
            need(from.equals(row.get("from_station"))&&to.equals(row.get("to_station")),"sample_direction_mismatch");
            found|=minutes<240;
        }
        return found;
    }
    static Map<String,Object> build(Map<?,?> input,Map<String,Set<String>> directory){
        need("whole_corridor_pending_research_v1".equals(input.get("schema")),"unsupported_input");
        need(integer(input.get("new_city_admissions"))==0&&Boolean.FALSE.equals(input.get("production_changed")),"pending_research_only");
        List<?> rawPairs=list(input.get("station_pairs"));Set<List<String>> seen=new HashSet<>();
        Map<String,Integer> priority=new TreeMap<>();List<Object> queue=new ArrayList<>();int named=0,bothCount=0;
        for(Object raw:rawPairs){Map<?,?> pair=map(raw);List<?> endpoints=list(pair.get("stations"));need(endpoints.size()==2,"two_stations_required");
            String a=string(endpoints.get(0)),b=string(endpoints.get(1));need(!a.equals(b),"same_station_pair");
            List<String> key=new ArrayList<>(List.of(a,b));Collections.sort(key);need(seen.add(key),"duplicate_station_pair");
            need(Boolean.FALSE.equals(pair.get("city_admitted"))&&Boolean.FALSE.equals(pair.get("air_fallback_proven")),"pending_pair_only");
            List<?> forward=list(pair.get("forward_samples")),reverse=list(pair.get("reverse_samples"));
            need(!forward.isEmpty()||!reverse.isEmpty(),"empty_pair");
            boolean f=under(forward,a,b),r=under(reverse,b,a),both=!forward.isEmpty()&&!reverse.isEmpty();
            need(Boolean.valueOf(both).equals(pair.get("observed_both_directions")),"direction_flag_mismatch");
            need(Boolean.valueOf(f&&r).equals(pair.get("both_directions_have_under240_sample")),"threshold_flag_mismatch");
            if(both)bothCount++;priority.putIfAbsent(a,0);priority.putIfAbsent(b,0);
            if(!(f&&r))continue;
            priority.merge(a,1,Integer::sum);priority.merge(b,1,Integer::sum);
            var sa=suggestions(a,directory);var sb=suggestions(b,directory);
            boolean unique=sa.size()==1&&sb.size()==1;if(unique)named++;
            queue.add(obj("stations",List.of(a,b),"station_name_suggestions",List.of(sa,sb),
                "review_state",unique?"name_candidates_need_scope_review":"explicit_city_mapping_needed",
                "original_pair_index",queueIndex(rawPairs,raw),"city_admitted",false,"air_fallback_proven",false));
        }
        need(integer(input.get("unordered_station_pairs"))==seen.size(),"pair_count_mismatch");
        need(integer(input.get("distinct_station_names"))==priority.size(),"station_count_mismatch");
        need(integer(input.get("bidirectional_station_pairs"))==bothCount,"bidirectional_count_mismatch");
        need(integer(input.get("both_direction_under240_station_pairs"))==queue.size(),"threshold_count_mismatch");
        List<Map<String,Object>> stations=new ArrayList<>();
        priority.forEach((station,count)->stations.add(obj("station",station,"affected_under240_pairs",count,
            "name_suggestions",suggestions(station,directory),"geography_verified",false,"second_reader_verified",false)));
        stations.sort(Comparator.<Map<String,Object>>comparingInt(x->-(Integer)x.get("affected_under240_pairs")).thenComparing(x->(String)x.get("station")));
        return obj("schema","station_city_review_work_queue_v1","purpose","research_review_only_not_recommendation_priority",
            "station_count",stations.size(),"bidirectional_under240_pairs",queue.size(),"pairs_with_two_unique_name_suggestions",named,
            "pairs_needing_explicit_mapping",queue.size()-named,"stations",stations,"pairs",queue,
            "warning","Literal names do not prove city membership or urban scope. County and subcentre stations require explicit review. Never estimate transfers or promote unknown rail to air.",
            "new_city_admissions",0,"production_changed",false);
    }
    static int queueIndex(List<?> all,Object row){return all.indexOf(row);}
    static Path privateNewOutput(Path requested)throws Exception{
        Path absolute=requested.toAbsolutePath().normalize();
        Path resolved=absolute.getParent().toRealPath().resolve(absolute.getFileName());
        // Check both spelling and the real parent: a private-looking symlink
        // must not place research-only records in a served public directory.
        for(Path path:List.of(absolute,resolved))for(Path part:path)
            need(!Set.of("web","public","dist","static","config").contains(part.toString()),"private_output_only");
        need(!Files.exists(resolved,LinkOption.NOFOLLOW_LINKS),"new_output_required");
        return resolved;
    }
    public static void main(String[] args)throws Exception{
        need(args.length==3,"usage: input.json expected_sha256 new_output.json");
        Path input=Path.of(args[0]),output=privateNewOutput(Path.of(args[2]));
        need(Files.size(input)<=16*1024*1024,"input_too_large");byte[] bytes=Files.readAllBytes(input);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));need(hash.equals(args[1]),"input_hash_mismatch");
        var result=build(map(Json.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8))),RegionDirectory.CITIES);
        result.put("input_pin",obj("file",input.toAbsolutePath().normalize().toString(),"sha256",hash));
        Files.writeString(output,Json.write(result)+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println(Json.write(obj("status","PASS_pending_queue_only","stations",result.get("station_count"),
            "under240_pairs",result.get("bidirectional_under240_pairs"),"unique_name_suggestions",result.get("pairs_with_two_unique_name_suggestions"),
            "explicit_mapping_needed",result.get("pairs_needing_explicit_mapping"),"new_city_admissions",0)));
    }
}
