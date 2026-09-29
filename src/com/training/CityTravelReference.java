package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Explicitly reviewed city research, separate from date-specific timetable observations.
 * No network, routing estimates, automatic approval, or ticket-availability inference. */
final class CityTravelReference {
    static final int DEFAULT_REVIEW_DAYS=90, MAX_REVIEW_DAYS=180, BOUNDARY_MARGIN_MINUTES=10;
    static final String SCHEMA="city-travel-reference-v1";
    private static String cachedStamp="";
    private static Map<?,?> cached=Map.of();
    private static final Map<Path,Map<?,?>> rememberedAnchors=new HashMap<>();
    static String text(Map<?,?> row,String key) { return Objects.toString(row.get(key),""); }
    static Map<?,?> map(Object value) { return value instanceof Map<?,?>?(Map<?,?>)value:Map.of(); }
    static List<?> list(Object value) { return value instanceof List<?>?(List<?>)value:List.of(); }
    static void require(boolean condition,String reason) { if(!condition) throw new IllegalArgumentException(reason); }
    static int integer(Object raw) {
        require(raw instanceof Number,"integer_required");
        Number value=(Number)raw;int number=value.intValue();
        require(Double.isFinite(value.doubleValue())&&number>=1&&number==value.doubleValue(),"invalid_integer");return number;
    }
    static Object canonical(Object value) {
        if(value instanceof Map<?,?>) {
            TreeMap<String,Object> sorted=new TreeMap<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
                require(entry.getKey() instanceof String,"non_string_key");sorted.put((String)entry.getKey(),canonical(entry.getValue()));
            }
            return sorted;
        }
        if(value instanceof List<?>) { List<Object> result=new ArrayList<>();for(Object item:(List<?>)value)result.add(canonical(item));return result; }
        if(value instanceof Number) {
            double number=((Number)value).doubleValue();require(Double.isFinite(number),"non_finite_number");
            if(number==((Number)value).longValue()) return ((Number)value).longValue();
        }
        return value;
    }
    static String sha256(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(canonical(value)).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String eventHash(Map<?,?> event) {
        Map<String,Object> payload=new LinkedHashMap<>();
        for(String field:List.of("snapshot","previous_hash","change_reason"))payload.put(field,event.get(field));
        return sha256(payload);
    }
    static boolean authorized(Map<?,?> row) {
        return Boolean.TRUE.equals(row.get("usage_authorized"))&&Boolean.TRUE.equals(row.get("business_import_allowed"))&&
            RailTimetable.businessUseAllowed(row)&&!text(row,"authorization_ref").isBlank();
    }
    static LocalDate date(Map<?,?> row,String field,LocalDate today) {
        LocalDate value=LocalDate.parse(text(row,field));require(!value.isAfter(today),"future_"+field);return value;
    }
    static void source(Map<?,?> row) {
        java.net.URI uri=java.net.URI.create(text(row,"source_url"));
        require("https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null,"invalid_source_url");
        require(!text(row,"source_provider").isBlank()&&!text(row,"original_content").isBlank(),"missing_source_content");
    }
    // A hash chain detects accidental edits against its retained head. It is not a signature or proof of a human identity.
    static Map<String,Map<?,?>> heads(Map<?,?> bundle) {
        boolean publicFacts=PublicTransportEvidence.schema(bundle);
        require(SCHEMA.equals(bundle.get("version"))||publicFacts,"wrong_schema");integer(bundle.get("library_revision"));
        require(publicFacts?PublicTransportEvidence.usable(bundle):authorized(bundle),"library_usage_unconfirmed");
        Map<String,Map<?,?>> heads=new LinkedHashMap<>();
        for(Object raw:list(bundle.get("references"))) {
            Map<?,?> chain=map(raw);String id=text(chain,"reference_id");
            require(id.matches("[A-Za-z0-9_.:-]{1,120}")&&!heads.containsKey(id),"duplicate_or_invalid_reference_id");
            String previous="";int version=0;Map<?,?> last=Map.of(),first=Map.of();LocalDate lastReview=null;
            for(Object rawEvent:list(chain.get("revisions"))) {
                Map<?,?> event=map(rawEvent),snapshot=map(event.get("snapshot"));
                require(id.equals(snapshot.get("reference_id"))&&integer(snapshot.get("version"))==++version,"broken_revision_sequence");
                if(version==1)first=snapshot;
                else for(String field:List.of("from_province","from_city","to_province","to_city"))require(Objects.equals(snapshot.get(field),first.get(field)),"reference_city_pair_changed");
                require(previous.equals(event.get("previous_hash"))&&!text(event,"change_reason").isBlank(),"broken_audit_chain");
                require(eventHash(event).equals(event.get("event_sha256")),"audit_hash_mismatch");
                LocalDate reviewed=LocalDate.parse(text(snapshot,"reviewed_on"));
                require(lastReview==null||!reviewed.isBefore(lastReview),"review_date_rollback");
                previous=text(event,"event_sha256");last=event;lastReview=reviewed;
            }
            require(version>0,"empty_revision_history");heads.put(id,last);
        }
        return heads;
    }
    static Map<?,?> unavailable(String reason) { return Map.of("load_status","reference_library_unavailable","load_reason",reason); }
    static synchronized Map<?,?> bundle(String province,String city) {
        String file=System.getProperty("dispatch.city.references.file",System.getenv().getOrDefault("YANXU_CITY_TRAVEL_REFERENCE_FILE",""));
        String directory=System.getProperty("dispatch.city.references.dir",System.getenv().getOrDefault("YANXU_CITY_TRAVEL_REFERENCE_DIR",""));
        if(!directory.isBlank()) {
            if(!RegionDirectory.known(province,city))return unavailable("destination_region_pending");
            file=Paths.get(directory,province+"-"+city+".json").toString();
        }
        if(file.isBlank())return Map.of();
        Map<?,?> result=load(file);
        return SCHEMA.equals(result.get("version"))||result.containsKey("load_status")?result:unavailable("not_reviewed_reference_schema");
    }
    static synchronized Map<?,?> publicBundle(String province,String city) {
        String file=System.getProperty("dispatch.public.city.references.file",System.getenv().getOrDefault("YANXU_PUBLIC_CITY_TRAVEL_REFERENCE_FILE",""));
        String directory=System.getProperty("dispatch.public.city.references.dir",System.getenv().getOrDefault("YANXU_PUBLIC_CITY_TRAVEL_REFERENCE_DIR",""));
        if(!directory.isBlank()) {
            if(!RegionDirectory.known(province,city))return unavailable("destination_region_pending");
            file=Paths.get(directory,province+"-"+city+".json").toString();
        }
        if(file.isBlank())return Map.of();
        Map<?,?> result=load(file);
        return PublicTransportEvidence.schema(result)||result.containsKey("load_status")?result:unavailable("not_public_reference_schema");
    }
    private static Map<?,?> load(String file) {
        try {
            Path path=Paths.get(file).toAbsolutePath().normalize();long size=Files.size(path);
            require(size>0&&size<=32*1024*1024,"reference_file_size");
            Path anchor=path.resolveSibling(path.getFileName()+".integrity.json");
            String stamp=path+":"+size+":"+Files.getLastModifiedTime(path)+":"+(Files.exists(anchor)?Files.getLastModifiedTime(anchor):"missing");
            if(stamp.equals(cachedStamp))return cached;
            Object parsed=Json.parse(Files.readString(path));require(parsed instanceof Map<?,?>,"invalid_library");
            Map<?,?> data=(Map<?,?>)parsed;Map<String,Map<?,?>> currentHeads=heads(data);
            String digest=sha256(data);int revision=integer(data.get("library_revision"));
            if(Files.exists(anchor))require(Files.size(anchor)<=4*1024*1024,"integrity_file_size");
            Map<?,?> previous=Files.exists(anchor)?map(Json.parse(Files.readString(anchor))):Map.of();
            Map<?,?> memory=rememberedAnchors.get(path);
            if(memory!=null)require(!previous.isEmpty()&&integer(previous.get("library_revision"))>=integer(memory.get("library_revision")),"integrity_anchor_removed_or_rolled_back");
            if(!previous.isEmpty()) {
                int oldRevision=integer(previous.get("library_revision"));
                require(revision>=oldRevision,"library_revision_rollback");
                require(revision!=oldRevision||digest.equals(previous.get("library_sha256")),"same_revision_changed");
                for(Map.Entry<?,?> old:map(previous.get("heads")).entrySet()) {
                    Map<?,?> remembered=map(old.getValue());Map<?,?> head=currentHeads.get(old.getKey());
                    require(head!=null,"reference_removed_without_revocation");
                    Map<?,?> chain=list(data.get("references")).stream().map(CityTravelReference::map).filter(r->Objects.equals(r.get("reference_id"),old.getKey())).findFirst().orElse(Map.of());
                    int version=integer(remembered.get("version"));List<?> events=list(chain.get("revisions"));
                    require(events.size()>=version&&Objects.equals(map(events.get(version-1)).get("event_sha256"),remembered.get("event_sha256")),"reference_history_rollback_or_changed");
                }
            }
            if(previous.isEmpty()||revision>integer(previous.get("library_revision"))) {
                Map<String,Object> retained=new LinkedHashMap<>();
                currentHeads.forEach((id,event)->retained.put(id,Map.of("version",map(event.get("snapshot")).get("version"),"event_sha256",event.get("event_sha256"))));
                Map<String,Object> record=Map.of("version","city-reference-integrity-v1","library_revision",revision,"library_sha256",digest,"heads",retained);
                Path temporary=Files.createTempFile(path.getParent(),".city-reference-integrity-",".tmp");
                try {
                    Files.writeString(temporary,Json.write(record));
                    try { Files.setPosixFilePermissions(temporary,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                    catch(UnsupportedOperationException notPosix) { /* Host ACL applies. */ }
                    Files.move(temporary,anchor,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
                previous=record;
            }
            rememberedAnchors.put(path,previous);
            cached=data;cachedStamp=path+":"+size+":"+Files.getLastModifiedTime(path)+":"+Files.getLastModifiedTime(anchor);return data;
        } catch(Exception invalid) { return unavailable(invalid instanceof IllegalArgumentException?Objects.toString(invalid.getMessage(),"invalid_reference_library"):"reference_library_io_unavailable"); }
    }
    static Map<?,?> evidence(Map<?,?> bundle,Map<?,?> leg,Map<?,?> reference,String mode,boolean reverse,LocalDate today) {
        String id=text(leg,"evidence_id"),hash=text(leg,"source_sha256");Map<?,?> found=null;
        for(Object raw:list(bundle.get("evidence_snapshots"))) {
            Map<?,?> item=map(raw);
            if(id.equals(item.get("evidence_id"))) { require(found==null,"duplicate_evidence_id");found=item; }
        }
        require(found!=null&&!id.isBlank(),"missing_evidence");
        Map<?,?> content=map(found.get("content"));
        require(hash.matches("[a-f0-9]{64}")&&hash.equals(found.get("sha256"))&&hash.equals(sha256(content)),"evidence_hash_mismatch");
        if(PublicTransportEvidence.schema(bundle)) PublicTransportEvidence.evidence(content,today);
        else {
            require(authorized(content),"evidence_usage_unconfirmed");source(content);
            require("authorized_schedule_research".equals(content.get("evidence_type")),"not_schedule_research");
        }
        require(mode.equals(content.get("mode")),"evidence_mode_mismatch");
        String fp=text(reference,reverse?"to_province":"from_province"),fc=text(reference,reverse?"to_city":"from_city");
        String tp=text(reference,reverse?"from_province":"to_province"),tc=text(reference,reverse?"from_city":"to_city");
        require(TravelMatrix.matches(content,fp,fc,tp,tc),"evidence_direction_mismatch");
        LocalDate research=date(content,"research_on",today),reviewed=date(reference,"reviewed_on",today);
        require(!research.isAfter(reviewed)&&ChronoUnit.DAYS.between(research,reviewed)<=30,"old_evidence_cannot_be_reapproved_without_new_research");
        require(Boolean.TRUE.equals(content.get("city_mapping_checked")),"city_mapping_pending");
        return content;
    }
    static Map<String,Object> leg(Map<?,?> bundle,Map<?,?> leg,Map<?,?> reference,String mode,boolean reverse,LocalDate today) {
        Map<?,?> content=evidence(bundle,leg,reference,mode,reverse,today);
        boolean typed=PublicCityBounds.DURATION_BASIS.equals(content.get("duration_basis"));
        Integer minutes=null;PublicCityBounds.Duration duration=null;
        if(typed) {
            require(PublicTransportEvidence.schema(bundle)&&mode.equals("rail"),"typed_bounds_only_public_rail");
            require(!leg.containsKey("minutes")&&!leg.containsKey("reference_minutes"),"typed_bounds_legacy_minutes_forbidden");
            duration=PublicCityBounds.validateEvidence(content,today);
            require(canonical(duration.toMap()).equals(canonical(leg.get("duration_bounds"))),"reference_duration_bounds_not_supported");
        } else {
            minutes=RailTimetable.minute(leg.get("minutes"));
            require(minutes==RailTimetable.minute(content.get("reference_minutes")),"reference_minutes_not_supported");
            require((mode.equals("rail")?"city_station_to_station":"city_airport_to_airport_nonstop").equals(content.get("scope")),"wrong_reference_scope");
        }
        if(mode.equals("air"))require(Boolean.TRUE.equals(content.get("nonstop"))&&"passenger".equals(content.get("service_type")),"not_nonstop_passenger_reference");
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("from_province","from_city","to_province","to_city","source_url","source_provider","research_on","scope","source_published_on","effective_from","from_endpoint","to_endpoint","service_state","schedule_basis","direction_basis","duration_applies_to"))
            if(content.containsKey(key))result.put(key,content.get(key));
        result.put("source_sha256",leg.get("source_sha256"));result.put("evidence_id",leg.get("evidence_id"));
        if(typed) {
            result.put("duration_bounds",duration.toMap());result.put("duration_basis",PublicCityBounds.DURATION_BASIS);
            for(String key:List.of("scope_note","language_policy","publisher_kind","attribution_basis","attribution_text"))
                if(content.containsKey(key))result.put(key,content.get(key));
        } else result.put("reference_minutes",minutes);
        result.put("observed_on",content.get("research_on"));return result;
    }
    static PublicCityBounds.Duration legDuration(Map<?,?> leg) {
        if(leg.containsKey("duration_bounds")) {
            Map<?,?> stored=map(leg.get("duration_bounds"));
            PublicCityBounds.Duration value=PublicCityBounds.fromStored(stored);
            require(canonical(value.toMap()).equals(canonical(stored)),"typed_duration_output_mismatch");return value;
        }
        return PublicCityBounds.duration(RailTimetable.minute(leg.get("reference_minutes"))+"分钟");
    }
    static boolean typed(Map<?,?> route) {
        return "typed_city_bounds".equals(route.get("duration_representation"))||route.containsKey("outbound_duration")||route.containsKey("return_duration");
    }
    static PublicCityBounds.Duration routeDuration(Map<?,?> route,String direction) {
        if(typed(route)) {
            Object expected=route.get(direction.equals("outbound")?"outbound_duration":"return_duration");
            PublicCityBounds.Duration value=legDuration(map(route.get(direction)));
            require(canonical(value.toMap()).equals(canonical(expected)),"typed_duration_output_mismatch");return value;
        }
        return PublicCityBounds.duration(RailTimetable.minute(route.get(direction.equals("outbound")?"outbound_reference_minutes":"return_reference_minutes"))+"分钟");
    }
    static boolean strictlyUnder(Map<?,?> route,int limit) {
        try {return PublicCityBounds.bothStrictlyUnder(routeDuration(route,"outbound"),routeDuration(route,"return"),limit);}
        catch(RuntimeException invalid){return false;}
    }
    static boolean sameDuration(PublicCityBounds.Duration a,PublicCityBounds.Duration b) {
        return Objects.equals(a.lowerMinutes,b.lowerMinutes)&&Objects.equals(a.upperMinutes,b.upperMinutes)&&
            a.lowerInclusive==b.lowerInclusive&&a.upperInclusive==b.upperInclusive;
    }
    static LocalDate validateReference(Map<?,?> reference,LocalDate today) {
        require(authorized(reference),"reference_usage_unconfirmed");
        require("approved".equals(reference.get("review_status")),"reference_"+text(reference,"review_status"));
        require("human".equals(reference.get("reviewer_type")),"human_review_required");
        require(text(reference,"reviewer_id").matches("[A-Za-z0-9_.:-]{1,120}")&&!text(reference,"review_method").isBlank(),"reviewer_or_method_missing");
        require("pre_bid_city_catchment".equals(reference.get("applicable_scope")),"unsupported_application_scope");
        LocalDate reviewed=date(reference,"reviewed_on",today),research=date(reference,"research_on",today);
        require(!research.isAfter(reviewed),"research_after_review");
        LocalDate due=text(reference,"review_due_on").isEmpty()?reviewed.plusDays(DEFAULT_REVIEW_DAYS):LocalDate.parse(text(reference,"review_due_on"));
        require(due.isAfter(reviewed)&&!due.isAfter(reviewed.plusDays(MAX_REVIEW_DAYS)),"invalid_review_interval");
        require(!due.isBefore(today),"review_overdue");return due;
    }
    static Map<String,Object> result(Map<?,?> bundle,Map<?,?> event,boolean reverse,LocalDate today) {
        Map<?,?> reference=map(event.get("snapshot"));boolean publicFacts=PublicTransportEvidence.schema(bundle);
        LocalDate due=publicFacts?PublicTransportEvidence.reference(reference,today):validateReference(reference,today);
        String mode=text(reference,"mode");require(mode.equals("rail")||mode.equals("air"),"invalid_reference_mode");
        Map<String,Object> forward=leg(bundle,map(reference.get("outbound")),reference,mode,false,today);
        Map<String,Object> backward=leg(bundle,map(reference.get("return")),reference,mode,true,today);
        LocalDate actualResearch=LocalDate.parse(text(forward,"research_on")),backResearch=LocalDate.parse(text(backward,"research_on"));
        if(backResearch.isAfter(actualResearch))actualResearch=backResearch;
        int limit=mode.equals("rail")?TravelMatrix.RAIL_LIMIT:TravelMatrix.AIR_LIMIT;
        boolean typed=forward.containsKey("duration_bounds")||backward.containsKey("duration_bounds");
        PublicCityBounds.Duration outDuration=legDuration(forward),backDuration=legDuration(backward);
        require(PublicCityBounds.bothStrictlyUnder(outDuration,backDuration,limit),typed?"reference_bounds_do_not_prove_limit":"reference_not_within_limit");
        if(!publicFacts)require(PublicCityBounds.bothStrictlyUnder(outDuration,backDuration,limit-BOUNDARY_MARGIN_MINUTES),"near_threshold_review_required");
        if(mode.equals("rail"))require(PublicCityBounds.bothStrictlyUnder(outDuration,backDuration,NearbySelection.maximumNearbyMinutes()),"administrator_range_pending");
        List<Map<String,Object>> railExclusion=new ArrayList<>();
        if(mode.equals("air")) {
            boolean beyond=false;
            for(boolean returning:List.of(false,true)) {
                Map<?,?> link=map(map(reference.get("rail_exclusion")).get(returning?"return":"outbound"));
                Map<?,?> proof=evidence(bundle,link,reference,"rail",returning,today);
                require(Boolean.TRUE.equals(proof.get("coverage_complete"))&&"all_city_stations_and_rail_itineraries".equals(proof.get("scope")),"rail_exclusion_incomplete");
                require(Boolean.FALSE.equals(proof.get("no_service")),"no_service_not_long_duration");
                int minimum=RailTimetable.minute(proof.get("minimum_minutes"));beyond|=minimum>=TravelMatrix.RAIL_LIMIT;
                LocalDate proofResearch=LocalDate.parse(text(proof,"research_on"));if(proofResearch.isAfter(actualResearch))actualResearch=proofResearch;
                if(!publicFacts)require(Math.abs(minimum-TravelMatrix.RAIL_LIMIT)>BOUNDARY_MARGIN_MINUTES,"rail_exclusion_near_threshold_review_required");
                Map<String,Object> retainedProof=new LinkedHashMap<>();
                for(String field:List.of("from_province","from_city","to_province","to_city","source_url","research_on","source_published_on","effective_from"))
                    if(proof.containsKey(field))retainedProof.put(field,proof.get(field));
                retainedProof.put("source_sha256",link.get("source_sha256"));retainedProof.put("minimum_minutes",minimum);railExclusion.add(retainedProof);
            }
            require(beyond,"rail_exclusion_not_established");
        }
        require(actualResearch.toString().equals(reference.get("research_on")),"research_date_not_evidence_date");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("status","city_transport_reference");result.put("reference_basis",publicFacts?"public_city_reference":"reviewed_city_reference");result.put("eligibility",mode);
        result.put("outbound",reverse?backward:forward);result.put("return",reverse?forward:backward);
        if(typed) {
            result.put("duration_representation","typed_city_bounds");
            result.put("outbound_duration",(reverse?backDuration:outDuration).toMap());result.put("return_duration",(reverse?outDuration:backDuration).toMap());
            result.put("time_score_applicable",false);result.put("rail_exclusion_complete",false);
        } else {
            result.put("outbound_reference_minutes",(reverse?backward:forward).get("reference_minutes"));
            result.put("return_reference_minutes",(reverse?forward:backward).get("reference_minutes"));
        }
        for(String key:List.of("reference_id","version","research_on","reviewed_on","reviewer_type","review_method","applicable_scope","review_status"))result.put(key,reference.get(key));
        result.put("library_revision",bundle.get("library_revision"));result.put("audit_head_sha256",event.get("event_sha256"));result.put("review_due_on",due.toString());
        result.put("transport_verified",false);result.put("travel_date_verified",false);result.put("local_connections_status","pending");
        if(!railExclusion.isEmpty())result.put("rail_exclusion",railExclusion);
        if(publicFacts) {
            result.put("near_threshold",!PublicCityBounds.bothStrictlyUnder(outDuration,backDuration,limit-BOUNDARY_MARGIN_MINUTES));
            result.put("license_claimed",false);result.put("human_reviewed",false);
            result.put("message",typed?
                "根据公开交通发布逐向核对的城市时间范围，用于就近初选；上界和口语区间不是实测分钟，保留原短语、推断说明和发布日，不保证具体班次、门到门时间或授课日行程。":
                "根据公开交通发布逐向核对的城市通达参考，用于投标前就近初选；保留原发布日和车站，不是具体班次、实时票务或人工认证，出行前仍需确认接驳和实际行程。");
        } else result.put("message","经人工审核的城市交通参考，用于投标前初选；按原研究与审核日期定期复核，不代表任意授课日开行、有票或可按时到场。");return result;
    }
    static boolean paired(Map<?,?> row,String fp,String fc,String tp,String tc) {
        return TravelMatrix.matches(row,fp,fc,tp,tc)||TravelMatrix.matches(row,tp,tc,fp,fc);
    }
    static Map<String,Object> reviewPending(Map<?,?> reference,String reason) {
        Map<String,Object> metadata=new LinkedHashMap<>();
        for(String key:List.of("reference_id","version","review_status","research_on","reviewed_on","review_due_on","mode","outbound","return"))
            if(reference.containsKey(key))metadata.put(key,reference.get(key));
        metadata.put("eligibility","unknown");
        return Map.of("status","city_reference_pending","eligibility","unknown","city_reference_history",metadata,
            "review_reason",reason,"message","城市交通长期参考待核实，不自动纳入推荐；保留原研究与审核记录供复核。");
    }
    static Map<String,Object> lookup(Map<?,?> bundle,String fp,String fc,String tp,String tc,LocalDate today) {
        if(bundle.isEmpty())return Map.of();
        if(!RegionDirectory.known(fp,fc)||!RegionDirectory.known(tp,tc))return reviewPending(Map.of(),"region_pending");
        try {
            Map<?,?> chosen=null;
            for(Map<?,?> event:heads(bundle).values()) {
                Map<?,?> reference=map(event.get("snapshot"));
                if(paired(reference,fp,fc,tp,tc)) { require(chosen==null,"conflicting_reference_ids");chosen=event; }
            }
            if(chosen==null)return Map.of();
            Map<?,?> reference=map(chosen.get("snapshot"));
            try { return result(bundle,chosen,!TravelMatrix.matches(reference,fp,fc,tp,tc),today); }
            catch(Exception invalid) { return reviewPending(reference,Objects.toString(invalid.getMessage(),"reference_validation_failed")); }
        } catch(Exception invalid) { return reviewPending(Map.of(),Objects.toString(invalid.getMessage(),"reference_library_invalid")); }
    }
    static boolean newerEvidence(List<?> rails,Map<?,?> data,Map<String,Object> reference,String fp,String fc,String tp,String tc,LocalDate today) {
        List<Map.Entry<String,Object>> all=new ArrayList<>();
        for(Object row:rails)all.add(new AbstractMap.SimpleImmutableEntry<>("rail",row));
        for(String key:List.of("rail_directions","rail_checks","flights","air_checks"))for(Object row:TravelMatrix.list(data,key))
            all.add(new AbstractMap.SimpleImmutableEntry<>(key.startsWith("rail")?"rail":"air",row));
        for(Map.Entry<String,Object> entry:all) if(entry.getValue() instanceof Map<?,?>) {
            Map<?,?> row=(Map<?,?>)entry.getValue();
            if(!paired(row,fp,fc,tp,tc))continue;
            if("rail".equals(reference.get("eligibility"))&&entry.getKey().equals("air"))continue;
            String direction=TravelMatrix.matches(row,fp,fc,tp,tc)?"outbound":"return";
            LocalDate baseline=LocalDate.parse(text(map(reference.get(direction)),"research_on"));
            if("air".equals(reference.get("eligibility"))&&entry.getKey().equals("rail"))for(Object proof:list(reference.get("rail_exclusion"))) {
                Map<?,?> p=map(proof);
                if(TravelMatrix.matches(row,text(p,"from_province"),text(p,"from_city"),text(p,"to_province"),text(p,"to_city")))baseline=LocalDate.parse(text(p,"research_on"));
            }
            try {
                LocalDate observed=LocalDate.parse(text(row,"observed_on"));
                if(observed.isAfter(today))return true;
                if(!observed.isAfter(today)&&observed.isAfter(baseline))return true;
                if(!RailTimetable.businessUseAllowed(row)&&!observed.isAfter(today)&&!observed.isBefore(baseline))return true;
                if((Boolean.TRUE.equals(row.get("no_service"))||List.of("revoked","withdrawn","suspended").contains(text(row,"status")))&&!observed.isAfter(today)&&!observed.isBefore(baseline))return true;
            } catch(Exception missingDate) { return true; }
        }
        return false;
    }
    static Map<String,Object> apply(Map<String,Object> recent,List<?> rails,Map<?,?> transport,Map<?,?> library,Map<String,Object> teacher,DispatchPreference pref,LocalDate today) {
        if(!pref.localPreferenceActive()||NearbySelection.maximumNearbyMinutes()==0||library.isEmpty())return recent;
        String fp=text(teacher,"base_province"),fc=text(teacher,"base_city");
        // A newer qualifying 239/179-minute observation can pass its original strict rule even if a long-term record needs review.
        if("same_city".equals(recent.get("status"))||"outside".equals(recent.get("eligibility")))return recent;
        if(List.of("rail","air").contains(Objects.toString(recent.get("eligibility"),""))) {
            // A freshest contradictory/single-leg observation cannot be hidden by an older fast sample either.
            Map<String,Object> chronology=new LinkedHashMap<>();
            for(String key:List.of("outbound","return")) {
                Map<String,Object> datedLeg=new LinkedHashMap<>();datedLeg.put("research_on",map(recent.get(key)).get("observed_on"));chronology.put(key,datedLeg);
            }
            chronology.put("eligibility",recent.get("eligibility"));
            chronology.put("research_on",map(chronology.get("outbound")).get("research_on"));
            if(newerEvidence(rails,transport,chronology,fp,fc,pref.province,pref.city,today))return reviewPending(Map.of(),"newer_transport_evidence_requires_review");
            return recent;
        }
        Map<String,Object> reference=lookup(library,fp,fc,pref.province,pref.city,today);
        if(reference.isEmpty())return recent;
        if(!List.of("rail","air").contains(Objects.toString(reference.get("eligibility"),""))) {
            Map<String,Object> pending=new LinkedHashMap<>(recent);pending.putAll(reference);return pending;
        }
        if(newerEvidence(rails,transport,reference,fp,fc,pref.province,pref.city,today)) {
            Map<String,Object> pending=new LinkedHashMap<>(recent);
            pending.putAll(reviewPending(reference,"newer_transport_evidence_requires_review"));return pending;
        }
        return reference;
    }
    private CityTravelReference() {}
}
