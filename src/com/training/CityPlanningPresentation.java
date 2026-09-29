package com.training;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Optional, local display annotations. Never a duration source, admission policy or
 * source reader. An agreeing hash-bound annotation is a review assertion, not NLP proof. */
final class CityPlanningPresentation {
    static final String VERSION="city-planning-presentation-v1", ANCHOR="city-planning-presentation-integrity-v1";
    static final String VERSION_V2="city-planning-presentation-v2";
    private static final String INSTITUTION_TYPE="institution_recruitment_campus_location_description_not_rail_operator";
    static final String VERSION_PROPERTY="dispatch.city.planning.presentation.version", FILE_PROPERTY="dispatch.city.planning.presentation.file";
    private static final Set<String> QUALIFIERS=Set.of("reported_fastest","general_reported","unspecified");
    private static final Set<String> PRECISIONS=Set.of("reported_minute","reported_minutes","approximate","nominal_hour","nominal_half_hour","computed_same_service","same_service_clocks","upper_bound","bounded_range");
    private static final Map<Path,Map<?,?>> remembered=new HashMap<>();
    private static void need(boolean b,String why){CityPlanningReference.need(b,why);}
    private static Map<?,?> map(Object x){return CityPlanningReference.map(x);}
    private static String text(Map<?,?> m,String k){return CityPlanningReference.text(m,k);}
    private static void digest(Object x){need(x instanceof String&&((String)x).matches("[a-f0-9]{64}"),"presentation_invalid_hash");}
    private static void keys(Map<?,?> m,String... keys){need(m.keySet().equals(Set.of(keys)),"presentation_fields_invalid");}
    private static int number(Object x){return CityPlanningReference.number(x);}
    private static String key(Map<?,?> row){return text(row,"library_file_sha256")+":"+text(row,"reference_id")+":"+text(row,"from_registry_id")+":"+text(row,"to_registry_id");}
    private static Object freeze(Object x){if(x instanceof Map<?,?> m){Map<String,Object> copy=new LinkedHashMap<>();m.forEach((k,v)->copy.put(k.toString(),freeze(v)));return Collections.unmodifiableMap(copy);}if(x instanceof List<?> l)return l.stream().map(CityPlanningPresentation::freeze).toList();return x;}
    @SuppressWarnings("unchecked") private static Map<String,Object> frozen(Map<String,Object> m){return (Map<String,Object>)freeze(m);}
    record Binding(String file,String fileHash,String anchorHash,String reason) {}
    /** Additional optional display binding only; source loader already validated this data.
     * Re-read bytes must match that exact snapshot, so a concurrent replacement cannot bind. */
    static Binding binding(Path path,Map<?,?> validated){try{
        Path p=path.toAbsolutePath().normalize(),a=Path.of(p+".integrity.json");
        need(Files.size(p)<=16*1024*1024&&Files.size(a)<=1024*1024,"presentation_binding_size_invalid");
        String raw=Files.readString(p),anchor=Files.readString(a);
        need(CityPlanningReference.same(Json.parse(raw),validated)&&CityPlanningReference.same(Json.parse(anchor),CityPlanningReference.anchor(validated)),"presentation_snapshot_changed");
        return new Binding(p.toString(),CityPlanningReference.rawHash(raw),CityPlanningReference.rawHash(anchor),"");
    }catch(Exception e){return new Binding("","","",Objects.toString(e.getMessage(),"presentation_binding_unavailable"));}}
    static Map<String,Object> anchor(Map<?,?> data){validate(data);return Map.of("version",ANCHOR,"presentation_revision",data.get("presentation_revision"),"presentation_sha256",CityPlanningReference.hash(data));}
    private static Map<String,Map<?,?>> validate(Map<?,?> data){
        keys(data,"version","presentation_revision","purpose","created_on","review","annotation_draft_sha256","collection_file","collection_file_sha256","publication_context","rows");
        need((VERSION.equals(data.get("version"))||VERSION_V2.equals(data.get("version")))&&"presentation_only".equals(data.get("purpose"))&&number(data.get("presentation_revision"))>0,"presentation_version_invalid");
        LocalDate created=CityPlanningReference.day(data.get("created_on"));Map<?,?> review=map(data.get("review"));
        keys(review,"status","reviewer_a","reviewer_b","mode","reviewed_on","record_locators");
        need("approved".equals(review.get("status"))&&"informed_retained_evidence_annotation_crosscheck".equals(review.get("mode"))&&!text(review,"reviewer_a").isBlank()&&!text(review,"reviewer_b").isBlank()&&!review.get("reviewer_a").equals(review.get("reviewer_b")),"presentation_review_pending_or_invalid");
        need(!CityPlanningReference.day(review.get("reviewed_on")).isBefore(created),"presentation_review_date_invalid");
        digest(data.get("annotation_draft_sha256"));need(!CityPlanningReference.list(review.get("record_locators")).isEmpty(),"presentation_review_locators_missing");
        for(Object locator:CityPlanningReference.list(review.get("record_locators")))need(locator instanceof String&&!((String)locator).isBlank(),"presentation_review_locator_invalid");
        digest(data.get("collection_file_sha256"));need(Path.of(text(data,"collection_file")).isAbsolute(),"presentation_collection_path_invalid");
        for(var e:map(data.get("publication_context")).entrySet()){
            Map<?,?> c=map(e.getValue());keys(c,"url","published_on","excerpt","read_locator","scope");
            CityPlanningReference.url(text(c,"url"));CityPlanningReference.day(c.get("published_on"));
            need(!text(c,"excerpt").isBlank()&&text(c,"excerpt").length()<=120&&!text(c,"read_locator").isBlank()&&!text(c,"scope").isBlank(),"presentation_context_invalid");
        }
        List<?> rows=CityPlanningReference.list(data.get("rows"));need(!rows.isEmpty()&&rows.size()<=512,"presentation_row_count_invalid");
        Map<String,Map<?,?>> result=new LinkedHashMap<>();Set<String> ids=new HashSet<>();
        for(Object raw:rows){Map<?,?> r=map(raw);
            Set<String> fields=new HashSet<>(Set.of("row_id","member_id","library_file","library_file_sha256","library_anchor_sha256","reference_id","reference_version","reference_event_sha256","direction","from_registry_id","to_registry_id","from_city","to_city","source_document","source_sha256","source_url","source_published_on","source_qualifier","precision","evidence","publication_context","note"));
            if(VERSION_V2.equals(data.get("version"))&&r.containsKey("source_context")){
                fields.add("source_context");need("institution_location".equals(r.get("source_context")),"presentation_source_context_unknown");
            }
            boolean uiDate=VERSION_V2.equals(data.get("version"))&&r.containsKey("date_basis");
            if(uiDate){fields.add("date_basis");PublicUiCitySample.dateBasis(map(r.get("date_basis")));need(r.get("source_published_on")==null&&r.get("publication_context")==null&&!r.containsKey("source_context")&&"general_reported".equals(r.get("source_qualifier"))&&"same_service_clocks".equals(r.get("precision")),"presentation_ui_sample_fields_invalid");}
            need(r.keySet().equals(fields),"presentation_fields_invalid");
            for(String k:List.of("library_file_sha256","library_anchor_sha256","reference_event_sha256","source_sha256"))digest(r.get(k));
            need(Path.of(text(r,"library_file")).isAbsolute()&&number(r.get("reference_version"))>0&&!text(r,"source_document").isBlank()&&!text(r,"member_id").isBlank(),"presentation_binding_invalid");
            need(Set.of("outbound","inbound").contains(text(r,"direction"))&&!text(r,"from_registry_id").equals(text(r,"to_registry_id"))&&text(r,"from_registry_id").matches("C[0-9]{3}")&&text(r,"to_registry_id").matches("C[0-9]{3}"),"presentation_direction_invalid");
            need(QUALIFIERS.contains(r.get("source_qualifier"))&&PRECISIONS.contains(r.get("precision")),"presentation_qualifier_or_precision_invalid");
            for(String field:List.of("row_id","member_id","reference_id"))need(text(r,field).matches("[A-Za-z0-9_.:-]{1,120}"),"presentation_identifier_invalid");
            need(ids.add(text(r,"row_id"))&&result.putIfAbsent(key(r),r)==null,"presentation_duplicate_or_conflicting_direction");
            CityPlanningReference.url(text(r,"source_url"));if(!uiDate)CityPlanningReference.day(r.get("source_published_on"));
            List<?> spans=CityPlanningReference.list(r.get("evidence"));need(!spans.isEmpty()&&spans.size()<=4,"presentation_evidence_missing");
            for(Object s:spans){Map<?,?> span=map(s);keys(span,"unit_id","start","end","text");need(!text(span,"unit_id").isBlank()&&!text(span,"text").isBlank()&&text(span,"text").length()<=120&&number(span.get("start"))<number(span.get("end")),"presentation_evidence_invalid");}
            if(r.get("publication_context")!=null)need(map(data.get("publication_context")).containsKey(r.get("publication_context")),"presentation_context_missing");
        }return result;
    }
    static Catalog configured(){String version=System.getProperty(VERSION_PROPERTY,"");if(version.isBlank())return null;String file=System.getProperty(FILE_PROPERTY,"");try{return load(file.isBlank()?null:Path.of(file),version);}catch(RuntimeException invalid){return new Catalog(Map.of(),Map.of(),"presentation_configured_path_invalid");}}
    static synchronized Catalog load(Path path,String version){
        if(!VERSION.equals(version)&&!VERSION_V2.equals(version))return new Catalog(Map.of(),Map.of(),"presentation_version_unsupported");
        try{need(path!=null,"presentation_file_not_configured");Path p=path.toAbsolutePath().normalize(),a=Path.of(p+".integrity.json");
            need(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)<=2*1024*1024&&Files.isRegularFile(a,LinkOption.NOFOLLOW_LINKS)&&Files.size(a)<=65536,"presentation_file_or_anchor_invalid");
            Map<?,?> data=map(Json.parse(Files.readString(p))),stored=map(Json.parse(Files.readString(a)));Map<String,Map<?,?>> rows=validate(data);
            need(version.equals(data.get("version")),"presentation_requested_version_mismatch");
            need(CityPlanningReference.same(anchor(data),stored),"presentation_anchor_mismatch");
            if(remembered.containsKey(p)){Map<?,?> old=remembered.get(p);int before=number(old.get("presentation_revision")),after=number(data.get("presentation_revision"));need(after>=before,"presentation_rollback");if(after==before)need(CityPlanningReference.same(old,stored),"presentation_same_version_changed");}
            remembered.put(p,stored);return new Catalog(data,rows,"");
        }catch(Exception e){return new Catalog(Map.of(),Map.of(),Objects.toString(e.getMessage(),"presentation_invalid"));}
    }
    static final class Catalog {
        private final Map<?,?> data;private final Map<String,Map<?,?>> rows;private final String reason;
        private Catalog(Map<?,?> data,Map<String,Map<?,?>> rows,String reason){this.data=(Map<?,?>)freeze(data);this.rows=Map.copyOf(rows);this.reason=reason;}
        String reason(){return reason;}
        Catalog forCollection(Path path,Map<?,?> collection){if(!reason.isEmpty())return this;try{
            Path p=path.toAbsolutePath().normalize();need(p.toString().equals(data.get("collection_file"))&&Files.size(p)<=4*1024*1024,"presentation_collection_binding_mismatch");String raw=Files.readString(p);
            need(CityPlanningReference.rawHash(raw).equals(data.get("collection_file_sha256"))&&CityPlanningReference.same(Json.parse(raw),collection),"presentation_collection_hash_mismatch");return this;
        }catch(Exception e){return new Catalog(Map.of(),Map.of(),Objects.toString(e.getMessage(),"presentation_collection_binding_invalid"));}}
        Map<String,Object> decorate(Map<?,?> library,Binding binding,Map<String,Object> original,LocalDate today){
            // Shared/pending/strict results remain byte-for-byte equivalent and gain no legs.
            if(!"planning_reference".equals(original.get("status")))return original;
            Map<String,Object> result=new LinkedHashMap<>(original),view=new LinkedHashMap<>();
            view.put("version",data.isEmpty()?VERSION:data.get("version"));view.put("purpose","presentation_only");view.put("affects_selection",false);
            try{need(reason.isEmpty(),reason);need(binding!=null&&binding.reason().isEmpty(),binding==null?"presentation_binding_missing":binding.reason());
                need(!CityPlanningReference.day(data.get("created_on")).isAfter(today)&&!CityPlanningReference.day(map(data.get("review")).get("reviewed_on")).isAfter(today),"presentation_future_review");
                view.put("outbound",leg(library,binding,original,"outbound"));view.put("inbound",leg(library,binding,original,"inbound"));
                view.put("status","ready");view.put("revision",data.get("presentation_revision"));
            }catch(RuntimeException e){view.remove("outbound");view.remove("inbound");view.put("status","pending");view.put("reason",Objects.toString(e.getMessage(),"presentation_invalid"));}
            result.put("presentation",frozen(view));return result;
        }
        private Map<String,Object> leg(Map<?,?> library,Binding binding,Map<?,?> original,String side){
            Map<?,?> leg=map(original.get(side)),from=map(leg.get("from")),to=map(leg.get("to"));
            String key=binding.fileHash()+":"+text(original,"reference_id")+":"+text(from,"id")+":"+text(to,"id");Map<?,?> row=rows.get(key);
            need(row!=null,"presentation_direction_not_annotated");
            if(original.containsKey("collection_member_id"))need(Objects.equals(original.get("collection_member_id"),row.get("member_id")),"presentation_member_mismatch");
            need(binding.file().equals(row.get("library_file"))&&binding.anchorHash().equals(row.get("library_anchor_sha256")),"presentation_library_binding_mismatch");
            need(Objects.equals(from.get("city"),row.get("from_city"))&&Objects.equals(to.get("city"),row.get("to_city"))&&Objects.equals(leg.get("source_document"),row.get("source_document"))&&Objects.equals(leg.get("source_url"),row.get("source_url"))&&Objects.equals(leg.get("source_published_on"),row.get("source_published_on")),"presentation_source_or_direction_mismatch");
            need(Objects.equals(leg.get("precision"),row.get("precision")),"presentation_precision_conflict");
            Map<?,?> event=CityPlanningReference.heads(library).get(text(row,"reference_id")),snapshot=map(event==null?null:event.get("snapshot"));
            need(event!=null&&Objects.equals(event.get("event_sha256"),row.get("reference_event_sha256"))&&number(snapshot.get("version"))==number(row.get("reference_version")),"presentation_reference_version_mismatch");
            Map<?,?> ref=map(snapshot.get(text(row,"direction")));
            need(Objects.equals(ref.get("from_registry_id"),from.get("id"))&&Objects.equals(ref.get("to_registry_id"),to.get("id"))&&Objects.equals(ref.get("source_document"),row.get("source_document")),"presentation_reference_direction_mismatch");
            Map<?,?> sourceEntry=map(map(library.get("documents")).get(text(row,"source_document")));
            need(Objects.equals(sourceEntry.get("raw_sha256"),row.get("source_sha256")),"presentation_source_hash_mismatch");
            Map<?,?> source=CityPlanningReference.doc(library,text(row,"source_document"));Map<String,String> units=CityPlanningReference.units(source);boolean fastestEvidence=false;
            boolean uiSample=PublicUiCitySample.method(leg.get("verification_method"));
            need(uiSample==row.containsKey("date_basis"),"presentation_ui_sample_date_basis_required");
            if(uiSample){Map<?,?> basis=map(row.get("date_basis"));PublicUiCitySample.referenceDate(source);need(VERSION_V2.equals(data.get("version"))&&PublicUiCitySample.source(source)&&PublicUiCitySample.sourceType(leg.get("verification_method")).equals(leg.get("source_type"))&&PublicUiCitySample.method(original.get("verification_method"))&&CityPlanningReference.same(basis,source.get("date_basis"))&&Objects.equals(basis.get("kind"),leg.get("source_date_basis"))&&Objects.equals(basis.get("query_date"),leg.get("source_query_date"))&&Objects.equals(basis.get("service_date"),leg.get("source_service_date"))&&leg.get("source_published_on")==null&&"general_reported".equals(row.get("source_qualifier"))&&"same_service_clocks".equals(leg.get("precision")),"presentation_ui_sample_source_binding_invalid");for(String endpoint:List.of("from","to"))need("named_city_station".equals(map(leg.get("endpoint_scope")).get(endpoint)),"presentation_ui_sample_station_scope_invalid");}
            for(Object raw:CityPlanningReference.list(row.get("evidence"))){Map<?,?> s=map(raw);String unit=units.get(text(s,"unit_id"));int a=number(s.get("start")),b=number(s.get("end"));need(unit!=null&&a<b&&b<=unit.length()&&unit.substring(a,b).equals(s.get("text")),"presentation_span_mismatch");fastestEvidence|=text(s,"text").contains("最快");}
            if(row.get("publication_context")!=null){Map<?,?> context=map(map(data.get("publication_context")).get(row.get("publication_context")));need(Objects.equals(context.get("url"),source.get("url"))&&Objects.equals(context.get("published_on"),source.get("published_on")),"presentation_context_source_mismatch");fastestEvidence|=text(context,"excerpt").contains("最快");}
            String qualifier=text(row,"source_qualifier"),declared=text(source,"duration_qualifier");
            need(!"reported_fastest".equals(qualifier)||fastestEvidence,"presentation_fastest_evidence_missing");
            if(!declared.isBlank()){
                String expected=Set.of("reported_fastest_running_reference","reported_approximate_not_fastest").contains(declared)?("reported_fastest_running_reference".equals(declared)?"reported_fastest":"general_reported"):
                    VERSION_V2.equals(data.get("version"))&&Set.of("reported_fastest","general_reported").contains(declared)?declared:"";
                need(!expected.isEmpty(),"presentation_source_qualifier_unknown");need(expected.equals(qualifier),"presentation_source_qualifier_conflict");
            }
            boolean institutional=VERSION_V2.equals(data.get("version"))&&INSTITUTION_TYPE.equals(source.get("source_type"));
            if(institutional)need("institution_location".equals(row.get("source_context")),"presentation_institution_context_missing");
            if(row.containsKey("source_context"))need(institutional&&"general_reported".equals(qualifier),"presentation_institution_source_type_mismatch");
            Map<?,?> selection=map(map(original.get("selection_reference")).get(side));int minutes=number(selection.get("reference_minutes"));String precision=text(leg,"precision");
            need(minutes>0&&Objects.equals(selection.get("precision"),precision),"presentation_duration_mismatch");
            Map<String,Object> duration=new LinkedHashMap<>();duration.put("kind",precision);
            if(Set.of("upper_bound","bounded_range").contains(precision)){for(var e:map(leg.get("source_duration")).entrySet())if(Set.of("kind","lower","upper","unit","lower_inclusive","upper_inclusive").contains(e.getKey()))duration.put(e.getKey().toString(),e.getValue());}
            else{int factor="nominal_hour".equals(precision)?60:"nominal_half_hour".equals(precision)?30:1;need(minutes%factor==0,"presentation_nominal_precision_conflict");duration.put("unit",factor==60?"hour":factor==30?"half_hour":"minute");duration.put("value",minutes/factor);}
            Map<String,Object> displayed=new LinkedHashMap<>(Map.of("source_qualifier",qualifier,"precision",precision,"duration",duration,"from_registry_id",from.get("id"),"to_registry_id",to.get("id"),"source_document",leg.get("source_document"),"annotation_id",row.get("row_id")));
            if(institutional)displayed.put("source_context","institution_location");
            if(uiSample)displayed.put("date_basis",row.get("date_basis"));
            return frozen(displayed);
        }
    }
    private CityPlanningPresentation(){}
}
