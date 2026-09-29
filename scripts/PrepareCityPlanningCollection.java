package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline preparation only. Child files and their anchors are never rewritten. */
public final class PrepareCityPlanningCollection {
    public static void main(String[] args)throws Exception {
        if(args.length==1&&"--help".equals(args[0])){System.out.println("PrepareCityPlanningCollection NEW-output.json COLLECTION_ID YYYY-MM-DD [--previous old-collection.json] member_id=child.json [...]");return;}
        if(args.length<4)throw new IllegalArgumentException("NEW-output.json COLLECTION_ID YYYY-MM-DD [--previous old-collection.json] member_id=child.json [...]");
        Path previous=null;Map<String,Path> members=new LinkedHashMap<>();
        for(int i=3;i<args.length;i++){if("--previous".equals(args[i])){if(previous!=null||++i>=args.length)throw new IllegalArgumentException("invalid_previous_option");previous=Path.of(args[i]);continue;}
            int split=args[i].indexOf('=');if(split<1||split==args[i].length()-1)throw new IllegalArgumentException("member_id_equals_path_required");String id=args[i].substring(0,split);if(members.putIfAbsent(id,Path.of(args[i].substring(split+1)))!=null)throw new IllegalArgumentException("duplicate_member_id");}
        LocalDate date=CityPlanningReference.day(args[2]);Path output=Path.of(args[0]);Map<String,Object> data=CityPlanningCollection.prepare(args[1],members,previous,output,date);
        CityPlanningReference.Index index=CityPlanningReference.collectionIndex(output,date);
        System.out.println(Json.write(Map.of("status","prepared_and_loaded","collection_id",data.get("collection_id"),"collection_revision",data.get("collection_revision"),"member_count",members.size(),"indexed_directions",index.directionCount(),"output",output.toAbsolutePath().normalize().toString(),"source_revalidation_claimed",false,"production_configuration_changed",false)));
    }
    private PrepareCityPlanningCollection(){}
}
