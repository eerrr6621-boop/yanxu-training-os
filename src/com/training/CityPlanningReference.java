package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import java.util.function.Supplier;

/** Trusted-backend, independently read public facts for pre-bid city planning only.
 * Does not replace strict TravelMatrix evidence. A separately labelled pre-bid
 * planning admission may consume its snapshot; no network or ticket claim.
 * Digests detect edits against a separately retained anchor; they authenticate no reader. */
final class CityPlanningReference {
    static final String SCHEMA="city-planning-reference-v1", ANCHOR="city-planning-integrity-v1";
    static final String SHARED_SCHEMA="city-planning-reference-v2";
    static final String PROPERTY="dispatch.city.planning.references.file", ENV="YANXU_CITY_PLANNING_REFERENCE_FILE";
    static final int BUFFER_MINUTES=30, REVIEW_DAYS=90, SOURCE_DAYS=365;
    private static final Map<Path,Map<?,?>> remembered=new HashMap<>();
    // Scope exists only while constructing ONE request snapshot or a diagnostic run.
    // No source bytes, parsed reviews, results or memo entries survive in this ThreadLocal.
    private static final ThreadLocal<Work> work=new ThreadLocal<>();
    private static final class Work {
        final boolean memoize;
        long contentFileReads,fileJsonParses,sourceDocumentParses,fullValidations,factValidations,sharedFactValidations;
        final IdentityHashMap<Map<?,?>,Map<String,Map<?,?>>> documents=new IdentityHashMap<>();
        final IdentityHashMap<Map<?,?>,Map<String,Map<?,?>>> heads=new IdentityHashMap<>();
        final IdentityHashMap<Map<?,?>,Map<String,Map<String,Object>>> facts=new IdentityHashMap<>();
        Work(boolean memoize){this.memoize=memoize;}
        Map<String,Long> metrics(){return Map.of("content_file_reads",contentFileReads,"file_json_parses",fileJsonParses,
            "source_document_parses",sourceDocumentParses,"full_library_validations",fullValidations,"direction_fact_validations",factValidations,"shared_fact_validations",sharedFactValidations);}
    }
    /** Actual call-site counters, not inferred I/O or production resource telemetry. */
    record Observation<T>(T value,Map<String,Long> metrics){}
    static <T> Observation<T> observe(Supplier<T> action){need(work.get()==null,"nested_measurement_not_supported");Work current=new Work(false);work.set(current);try{return new Observation<>(action.get(),current.metrics());}finally{work.remove();}}
    private static final List<String> CORE=List.of("from_registry_id","to_registry_id","from_endpoint","to_endpoint",
        "transport_mode","route_scope","service_id","duration_kind","reported_minutes","calculated_minutes",
        "nominal_value","nominal_unit","departure_clock","arrival_clock","arrival_day_offset","published_on",
        "effective_on","effective_until","service_state","mapping_basis");
    private static final Set<String> ACTIVE=Set.of("reported_operating","regular_diagram_reference","retrospective_rail_reference");
    private static final List<String> RESTRICTIONS=List.of("minimal_fact_extraction_prohibited","minimal_fact_reuse_prohibited",
        "source_research_only","automation_prohibited","access_restricted");
    static Map<?,?> map(Object x){return CityTravelReference.map(x);}
    static List<?> list(Object x){return CityTravelReference.list(x);}
    static String text(Map<?,?> x,String k){return CityTravelReference.text(x,k);}
    static void need(boolean x,String message){CityTravelReference.require(x,message);}
    static String hash(Object x){return CityTravelReference.sha256(x);}
    static String rawHash(String x){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static boolean same(Object a,Object b){return hash(a).equals(hash(b));}
    static int number(Object x){need(x instanceof Number,"integer_required");double d=((Number)x).doubleValue();need(Double.isFinite(d)&&d>=0&&d<100000&&d==(int)d,"invalid_integer");return (int)d;}
    static LocalDate day(Object x){String s=Objects.toString(x,"");need(s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),"ISO_date_required");try{LocalDate d=LocalDate.parse(s);need(d.toString().equals(s),"invalid_date");return d;}catch(DateTimeException e){throw new IllegalArgumentException("invalid_ISO_date",e);}}
    static void url(String value){java.net.URI u=java.net.URI.create(value);need("https".equals(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null,"invalid_source_url");}
    static String registryId(String province,String city){int i=0;for(var e:RegionDirectory.CITIES.entrySet())for(String c:e.getValue()){i++;if(e.getKey().equals(province)&&c.equals(city))return String.format(Locale.ROOT,"C%03d",i);}return "";}
    static void city(Map<?,?> c){String p=text(c,"province"),n=text(c,"city"),id=text(c,"id");need(!id.isBlank()&&RegionDirectory.known(p,n)&&id.equals(registryId(p,n)),"unknown_or_mismatched_city");}
    static Map<?,?> doc(Map<?,?> data,String id){Work current=work.get();Map<String,Map<?,?>> memo=current!=null&&current.memoize?current.documents.computeIfAbsent(data,k->new HashMap<>()):null;if(memo!=null&&memo.containsKey(id))return memo.get(id);
        Map<?,?> entry=map(map(data.get("documents")).get(id));String raw=text(entry,"raw_json");need(!raw.isBlank()&&rawHash(raw).equals(entry.get("raw_sha256")),"document_hash_mismatch");if(current!=null)current.sourceDocumentParses++;Map<?,?> parsed=map(Json.parse(raw));if(memo!=null)memo.put(id,parsed);return parsed;}
    static Map<?,?> only(List<?> values,java.util.function.Predicate<Map<?,?>> filter,String error){Map<?,?> found=null;for(Object x:values){Map<?,?> m=map(x);if(filter.test(m)){need(found==null,"ambiguous_"+error);found=m;}}need(found!=null,error);return found;}

    static void sourcePolicy(Map<?,?> policy,LocalDate reviewed){
        need("independently_curated_public_facts".equals(policy.get("use_basis"))&&Boolean.FALSE.equals(policy.get("license_claimed")),"source_license_claim_not_allowed");
        need("no_explicit_minimal_fact_prohibition_observed".equals(policy.get("minimal_fact_review")),"source_restriction_unresolved");
        Map<?,?> flags=map(policy.get("external_restrictions"));for(String key:RESTRICTIONS)need(Boolean.FALSE.equals(flags.get(key)),"source_restriction_"+key);
        // A ban on whole-article republication is recorded, never converted to a permission claim.
        need(flags.get("direct_article_republication_restricted") instanceof Boolean,"republication_scope_missing");
        need(Boolean.FALSE.equals(policy.get("article_republication_allowed")),"article_republication_not_supported");
        need(day(policy.get("checked_on")).equals(reviewed)&&!text(policy,"scope_note").isBlank(),"source_policy_review_missing");
        need(!list(policy.get("checks")).isEmpty(),"source_policy_checks_missing");
        for(Object raw:list(policy.get("checks"))){Map<?,?> check=map(raw);url(text(check,"url"));need(!text(check,"record_locator").isBlank()&&!text(check,"observation").isBlank(),"source_policy_check_incomplete");}
    }

    static void originalRestrictions(Map<?,?> source){
        for(String key:RESTRICTIONS)need(!Boolean.TRUE.equals(source.get(key))&&!Boolean.TRUE.equals(map(source.get("external_restrictions")).get(key)),"original_source_restriction_"+key);
        need(!Set.of("restricted","research_only","prohibited","revoked","withdrawn","suspended","expired","superseded").contains(text(source,"reuse_state")),"original_source_reuse_restricted");
        need(!Set.of("revoked","withdrawn","suspended","cancelled","superseded").contains(text(source,"status")),"source_withdrawn");
        // runtime_enabled / private_only in an earlier preparation packet are workflow state,
        // not external permission. Explicit source flags above always win over a new policy row.
    }

    static Map<?,?> review(Map<?,?> data,Map<?,?> ref,Map<?,?> source,String role){
        String id=text(ref,"review_"+role.toLowerCase(Locale.ROOT));Map<?,?> review=doc(data,id),actor=map(review.get("reviewer"));
        need(role.equals(actor.get("role"))&&"assistant".equals(actor.get("actor_type")),"reader_role_invalid");
        need(!text(actor,"agent_id").isBlank()&&!text(actor,"run_id").isBlank(),"reader_execution_missing");
        need(actor.get("background_context_prior_research") instanceof Boolean,"reader_background_missing");
        boolean informed=Boolean.TRUE.equals(actor.get("prior_A_result_access"));
        if(informed)need("B".equals(role)&&"informed_original_source_crosscheck".equals(actor.get("review_mode"))&&
            Boolean.FALSE.equals(actor.get("fresh_blind_claimed"))&&!Boolean.TRUE.equals(actor.get("blind_fresh_context"))&&!Boolean.TRUE.equals(actor.get("blind_fresh")),"reader_informed_mode_invalid");
        else need(Boolean.FALSE.equals(actor.get("prior_A_result_access"))&&!"informed_original_source_crosscheck".equals(actor.get("review_mode")),"reader_A_result_exposure");
        if(Boolean.TRUE.equals(actor.get("background_context_prior_research")))need(!Boolean.TRUE.equals(actor.get("fresh_blind_claimed"))&&!Boolean.TRUE.equals(actor.get("blind_fresh_context")),"false_fresh_blind_claim");
        String packet=text(ref,"packet_document");need(text(map(map(data.get("documents")).get(packet)),"raw_sha256").equals(review.get("source_packet_sha256")),"reader_packet_mismatch");
        String sid=text(source,"source_id");
        int reads=0;for(Object raw:list(review.get("read_events"))){Map<?,?> observed=map(raw);if(!sid.equals(observed.get("source_id")))continue;reads++;
            need("original_read".equals(observed.get("result"))&&PublicUiCitySample.readUrlMatches(source,ref,review,observed.get("url"))&&!text(observed,"record_locator").isBlank(),"original_read_event_invalid");
            if(observed.get("recorded_at")!=null)Instant.parse(text(observed,"recorded_at")); // Older honest null seconds remain null.
        }need(reads>0,"original_read_event_missing");
        Map<?,?> scope=only(list(review.get("source_reviews")),e->sid.equals(e.get("source_id")),"source_review_missing");
        need("no_explicit_prohibition_observed".equals(scope.get("reuse_state"))&&!text(scope,"rationale").isBlank(),"original_source_restriction");
        return review;
    }
    static LocalDate readDay(Map<?,?> review,String sourceId,Map<?,?> packet){LocalDate last=null;for(Object raw:list(review.get("read_events"))){Map<?,?> event=map(raw);if(sourceId.equals(event.get("source_id"))&&event.get("recorded_at")!=null){LocalDate date=Instant.parse(text(event,"recorded_at")).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();if(last==null||date.isAfter(last))last=date;}}return last==null?day(packet.get("created_on")):last;}

    static Map<String,String> units(Map<?,?> source){Map<String,String> units=new LinkedHashMap<>();for(Object raw:list(source.get("units"))){Map<?,?> u=map(raw);String id=text(u,"unit_id"),s=text(u,"text");need(!id.isBlank()&&!s.isBlank()&&"utf16".equals(u.get("offset_unit"))&&rawHash(s).equals(u.get("text_sha256"))&&units.putIfAbsent(id,s)==null,"source_unit_invalid");}need(!units.isEmpty(),"source_units_missing");return units;}
    static String spans(Map<?,?> fact,Map<String,String> units,String role){StringBuilder out=new StringBuilder();for(Object raw:list(map(fact.get("spans")).get(role))){Map<?,?> s=map(raw);String original=units.get(text(s,"unit_id"));int a=number(s.get("start")),b=number(s.get("end"));need(original!=null&&a<b&&b<=original.length()&&original.substring(a,b).equals(s.get("text")),"source_span_mismatch");out.append(s.get("text")).append('\n');}return out.toString();}
    static int clock(String s){need(s.matches("[0-2][0-9]:[0-5][0-9]"),"clock_format_invalid");return LocalTime.parse(s).getHour()*60+LocalTime.parse(s).getMinute();}
    static boolean clockSeen(Map<?,?> fact,Map<String,String> units,String role,String value){
        int m=clock(value);String token="0?"+(m/60)+":"+String.format(Locale.ROOT,"%02d",m%60);
        Pattern clock=Pattern.compile(token),withRole=Pattern.compile(token+(role.equals("departure")?"(?:开|出发|发车)":"(?:到|抵达)"));
        for(Object raw:list(map(fact.get("spans")).get(role))){Map<?,?> span=map(raw);String original=units.get(text(span,"unit_id"));int a=number(span.get("start")),b=number(span.get("end"));
            // Readers may select just the clock. Resolve its immediately adjacent role from
            // the retained source, without editing their span to make it agree.
            if(clock.matcher(text(span,"text")).lookingAt()&&withRole.matcher(original.substring(a,Math.min(original.length(),b+3))).lookingAt())return true;
        }return false;
    }
    static int literalMinutes(String s){Matcher m=Pattern.compile("(?:(\\d+)小时)?(?:(\\d+)分(?:钟)?)?").matcher(s);int result=-1;while(m.find()){if(m.group(1)==null&&m.group(2)==null)continue;int n=(m.group(1)==null?0:Integer.parseInt(m.group(1))*60)+(m.group(2)==null?0:Integer.parseInt(m.group(2)));need(result==-1,"multiple_duration_values");result=n;}need(result>0,"duration_value_missing");return result;}
    static boolean adjacent(Map<?,?> fact,Map<String,String> units,String left,String right,String gap){
        for(Object la:list(map(fact.get("spans")).get(left)))for(Object ra:list(map(fact.get("spans")).get(right))){Map<?,?> l=map(la),r=map(ra);if(!Objects.equals(l.get("unit_id"),r.get("unit_id")))continue;int a=number(l.get("end")),b=number(r.get("start"));if(b>=a&&units.get(text(l,"unit_id")).substring(a,b).matches(gap))return true;}return false;
    }

    static int validateReadFact(Map<?,?> fact,Map<?,?> source,Map<?,?> from,Map<?,?> to){
        need(text(from,"city").equals(fact.get("from_city"))&&text(to,"city").equals(fact.get("to_city")),"fact_direction_mismatch");
        Map<?,?> c=map(fact.get("canonical"));need(text(from,"id").equals(c.get("from_registry_id"))&&text(to,"id").equals(c.get("to_registry_id")),"fact_city_id_mismatch");
        need("rail".equals(c.get("transport_mode"))&&Set.of("city_summary","station_corridor","station_service_pair").contains(text(c,"route_scope")),"not_rail_city_reference");
        need(ACTIVE.contains(text(c,"service_state")),"future_temporary_unknown_or_stopped_service");
        need(Set.of("fact_confirmed","needs_review").contains(text(c,"preparation_status")),"read_fact_not_confirmed");
        need(Set.of("same_service","explicit_direct","not_explicit").contains(text(c,"directness")),"directness_unknown");
        need(day(c.get("published_on")).equals(day(source.get("published_on"))),"source_date_mismatch");
        if(c.get("effective_on")!=null)day(c.get("effective_on"));if(c.get("effective_until")!=null){LocalDate end=day(c.get("effective_until"));need(c.get("effective_on")!=null&&!end.isBefore(day(c.get("effective_on"))),"effective_period_invalid");}
        if("regular_diagram_reference".equals(c.get("service_state")))need(c.get("effective_on")!=null,"diagram_effective_date_missing");
        Map<String,String> u=units(source);Map<String,String> selected=new LinkedHashMap<>();
        for(String role:List.of("from","to","duration","departure","arrival","service","context"))selected.put(role,spans(fact,u,role));
        need(!selected.get("context").isBlank()&&!text(fact,"rationale").isBlank(),"context_review_missing");
        for(String role:List.of("from","to")){String endpoint=text(c,role+"_endpoint"),name=text(role.equals("from")?from:to,"city");need(!endpoint.isBlank()&&selected.get(role).contains(endpoint),"endpoint_span_missing");
            String basis=text(c,"mapping_basis");need("literal_city_prefix".equals(basis)?endpoint.startsWith(name):"explicit_city_context".equals(basis)&&endpoint.equals(name),"station_city_mapping_unconfirmed");}
        String kind=text(c,"duration_kind"),duration=text(c,"duration_text");int value;
        if("computed_same_service".equals(kind)){
            need(c.get("duration_text")==null&&c.get("reported_minutes")==null&&c.get("nominal_value")==null&&c.get("nominal_unit")==null,"computed_precision_mismatch");
            need("same_service".equals(c.get("directness"))&&"station_service_pair".equals(c.get("route_scope"))&&!text(c,"service_id").isBlank()&&selected.get("service").contains(text(c,"service_id")),"same_service_binding_missing");
            Integer chainValue=PublicStopChain.planning(fact,u);
            if(chainValue==null){
                need(clockSeen(fact,u,"departure",text(c,"departure_clock"))&&clockSeen(fact,u,"arrival",text(c,"arrival_clock")),"departure_arrival_role_mismatch");
                // These are local station/clock cells, not an article-level language grammar.
                // Same paragraph alone must never allow mixing clocks of different trains.
                need(adjacent(fact,u,"from","departure","\\s*[（(]\\s*")&&adjacent(fact,u,"departure","to","(?:开|出发|发车)?[）)]至")&&adjacent(fact,u,"to","arrival","\\s*[（(]\\s*")&&adjacent(fact,u,"arrival","service","(?:到|抵达)?[）)]\\s*"),"clock_station_service_binding_mismatch");
            }
            int offset=number(c.get("arrival_day_offset"));need(offset==0,"overnight_not_supported_without_explicit_day_binding");
            value=clock(text(c,"arrival_clock"))-clock(text(c,"departure_clock"));need(value>0&&value==number(c.get("calculated_minutes")),"clock_duration_mismatch");
            need(chainValue==null||chainValue==value,"stop_chain_calculated_minutes_mismatch");
            Set<String> common=new HashSet<>();for(String role:List.of("from","to","departure","arrival","service")){Set<String> ids=new HashSet<>();for(Object s:list(map(fact.get("spans")).get(role)))ids.add(text(map(s),"unit_id"));if(common.isEmpty()&&role.equals("from"))common.addAll(ids);else common.retainAll(ids);}need(!common.isEmpty(),"same_service_unit_missing");
        }else{
            need(!duration.isBlank()&&selected.get("duration").contains(duration)&&c.get("departure_clock")==null&&c.get("arrival_clock")==null&&c.get("arrival_day_offset")==null&&c.get("calculated_minutes")==null,"duration_precision_mismatch");
            value=literalMinutes(duration);
            if("reported_minute".equals(kind))need(!duration.contains("约")&&duration.contains("分")&&value==number(c.get("reported_minutes"))&&c.get("nominal_value")==null&&c.get("nominal_unit")==null,"reported_minute_mismatch");
            else if("nominal_hour".equals(kind))need(!duration.contains("约")&&!duration.contains("分")&&duration.contains("小时")&&c.get("reported_minutes")==null&&"hour".equals(c.get("nominal_unit"))&&value==60*number(c.get("nominal_value")),"nominal_hour_mismatch");
            else if("approximate".equals(kind))need(duration.contains("约")&&c.get("reported_minutes")==null&&"minute".equals(c.get("nominal_unit"))&&value==number(c.get("nominal_value")),"approximate_precision_mismatch");
            else throw new IllegalArgumentException("unsupported_duration_precision");
        }
        return value;
    }

    private record ReadEnvelope(Map<?,?> source,Map<?,?> a,Map<?,?> b){}
    private static ReadEnvelope readEnvelope(Map<?,?> data,Map<?,?> ref,LocalDate reviewed){
        Map<?,?> packet=doc(data,text(ref,"packet_document")),source=doc(data,text(ref,"source_document"));String sid=text(source,"source_id");url(text(source,"url"));originalRestrictions(source);
        Map<?,?> row=only(list(packet.get("sources")),s->sid.equals(s.get("source_id")),"source_not_in_packet");
        need(text(source,"url").equals(row.get("url"))&&text(map(map(data.get("documents")).get(text(ref,"source_document"))),"raw_sha256").equals(row.get("file_sha256")),"source_packet_mismatch");
        sourcePolicy(map(ref.get("source_policy")),reviewed);
        Map<?,?> a=review(data,ref,source,"A"),b=review(data,ref,source,"B");
        LocalDate aDay=readDay(a,sid,packet),bDay=readDay(b,sid,packet);need(reviewed.equals(aDay.isAfter(bDay)?aDay:bDay),"review_date_not_bound_to_read_events");
        Map<?,?> ar=map(a.get("reviewer")),br=map(b.get("reviewer"));need(!Objects.equals(ar.get("agent_id"),br.get("agent_id"))&&!Objects.equals(ar.get("run_id"),br.get("run_id")),"second_independent_reader_missing");
        PublicUiCitySample.validateQueryObservation(source,a,b,packet);
        return new ReadEnvelope(source,a,b);
    }

    static Map<String,Object> fact(Map<?,?> data,Map<?,?> ref,Map<?,?> from,Map<?,?> to,LocalDate reviewed){
        need(text(from,"id").equals(ref.get("from_registry_id"))&&text(to,"id").equals(ref.get("to_registry_id")),"leg_direction_mismatch");
        Work current=work.get();Map<String,Map<String,Object>> memo=current!=null&&current.memoize?current.facts.computeIfAbsent(data,k->new HashMap<>()):null;
        String memoKey=memo==null?"":text(from,"id")+":"+text(to,"id")+":"+reviewed+":"+hash(ref);
        if(memo!=null&&memo.containsKey(memoKey))return new LinkedHashMap<>(memo.get(memoKey));if(current!=null)current.factValidations++;
        ReadEnvelope envelope=readEnvelope(data,ref,reviewed);Map<?,?> source=envelope.source(),a=envelope.a(),b=envelope.b();
        String sid=text(source,"source_id");Map<?,?> ar=map(a.get("reviewer")),br=map(b.get("reviewer"));
        java.util.function.Predicate<Map<?,?>> match=f->sid.equals(f.get("source_id"))&&text(from,"city").equals(f.get("from_city"))&&text(to,"city").equals(f.get("to_city"));
        Map<?,?> af=only(list(a.get("facts")),match,"reader_fact_missing"),bf=only(list(b.get("facts")),match,"reader_fact_missing");
        Map<?,?> ac=map(af.get("canonical")),bc=map(bf.get("canonical"));
        if(ac.containsKey("verification_method")||bc.containsKey("verification_method")){
            need(CuratedCityDuration.method(ac.get("verification_method"))&&Objects.equals(ac.get("verification_method"),bc.get("verification_method")),"curated_verification_method_mismatch");
            Map<String,Object> result=new LinkedHashMap<>(CuratedCityDuration.directionAfterEnvelopeValidation(new CuratedCityDuration.Direction(source,af,bf,from,to,reviewed)));
            result.put("read_differences",Map.of());result.put("readiness",Map.of("A",ac.get("status"),"B",bc.get("status")));
            result.put("directness","same_service_clocks".equals(result.get("precision"))?"same_service":"not_explicit");
            result.put("source_document",ref.get("source_document"));result.put("reader_background",Map.of("A",ar,"B",br));
            informedNotice(result,br);if(memo!=null)memo.put(memoKey,new LinkedHashMap<>(result));return result;
        }
        for(String field:CORE)need(same(ac.get(field),bc.get(field)),"read_core_disagreement_"+field);
        int minutes=validateReadFact(af,source,from,to);need(minutes==validateReadFact(bf,source,from,to),"read_duration_disagreement");
        Map<String,Object> differences=new LinkedHashMap<>();Set<Object> keys=new LinkedHashSet<>(ac.keySet());keys.addAll(bc.keySet());for(Object key:keys)if(!same(ac.get(key),bc.get(key))){Map<String,Object> difference=new LinkedHashMap<>();difference.put("A",ac.get(key));difference.put("B",bc.get(key));differences.put(key.toString(),difference);}
        LocalDate published=day(source.get("published_on")),due=reviewed.plusDays(REVIEW_DAYS);if(published.plusDays(SOURCE_DAYS).isBefore(due))due=published.plusDays(SOURCE_DAYS);
        Map<String,Object> result=new LinkedHashMap<>();result.put("from",from);result.put("to",to);result.put("from_endpoint",ac.get("from_endpoint"));result.put("to_endpoint",ac.get("to_endpoint"));result.put("reference_value_minutes_internal",minutes);result.put("source_url",source.get("url"));result.put("source_published_on",published.toString());result.put("review_due_on",due.toString());result.put("reviewed_on",reviewed.toString());result.put("precision",ac.get("duration_kind"));result.put("scope",ac.get("route_scope"));result.put("service_state",ac.get("service_state"));result.put("effective_on",ac.get("effective_on"));result.put("effective_until",ac.get("effective_until"));result.put("read_differences",differences);result.put("readiness",Map.of("A",ac.get("preparation_status"),"B",bc.get("preparation_status")));result.put("directness",Objects.equals(ac.get("directness"),bc.get("directness"))?ac.get("directness"):"unresolved_between_reads");result.put("source_document",ref.get("source_document"));result.put("reader_background",Map.of("A",ar,"B",br));informedNotice(result,br);if(memo!=null)memo.put(memoKey,new LinkedHashMap<>(result));return result;
    }

    static void informedNotice(Map<String,Object> result,Map<?,?> readerB){if(Boolean.TRUE.equals(readerB.get("prior_A_result_access"))){result.put("review_mode","informed_original_source_crosscheck");result.put("informed_source_crosscheck",true);result.put("review_notice","第二核读者已见首读结果，另核原页；不是盲核或两独立来源。");}}

    static boolean sharedSnapshot(Map<?,?> snapshot){return SharedCityDuration.KIND.equals(snapshot.get("reference_kind"));}
    private static void snapshotKind(Map<?,?> data,Map<?,?> snapshot){
        if(SHARED_SCHEMA.equals(data.get("version"))){
            need(sharedSnapshot(snapshot)&&snapshot.keySet().equals(Set.of("reference_id","version","reference_kind","from","to","status","reviewed_on","shared_reference")),"shared_snapshot_fields_invalid");
        }else need(!snapshot.containsKey("reference_kind")&&!snapshot.containsKey("shared_reference"),"shared_requires_v2_library");
    }
    private static Map<String,Object> sharedFact(Map<?,?> data,Map<?,?> snapshot){
        Map<?,?> ref=map(snapshot.get("shared_reference"));
        need(ref.keySet().equals(Set.of("packet_document","source_document","review_a","review_b","source_policy")),"shared_reference_fields_invalid");
        LocalDate reviewed=day(snapshot.get("reviewed_on"));Map<?,?> from=map(snapshot.get("from")),to=map(snapshot.get("to"));
        Work current=work.get();Map<String,Map<String,Object>> memo=current!=null&&current.memoize?current.facts.computeIfAbsent(data,k->new HashMap<>()):null;
        String key="shared:"+hash(snapshot);if(memo!=null&&memo.containsKey(key))return new LinkedHashMap<>(memo.get(key));if(current!=null)current.sharedFactValidations++;
        ReadEnvelope envelope=readEnvelope(data,ref,reviewed);String sid=text(envelope.source(),"source_id");
        java.util.function.Predicate<Map<?,?>> match=f->sid.equals(f.get("source_id"))&&from.get("city").equals(f.get("city_a"))&&to.get("city").equals(f.get("city_b"));
        Map<?,?> a=only(list(envelope.a().get("shared_facts")),match,"shared_reader_fact_missing"),b=only(list(envelope.b().get("shared_facts")),match,"shared_reader_fact_missing");
        Map<String,Object> value=SharedCityDuration.afterEnvelopeValidation(new SharedCityDuration.Shared(envelope.source(),a,b,from,to,reviewed));
        value.put("source_document",ref.get("source_document"));value.put("reader_background",Map.of("A",map(envelope.a().get("reviewer")),"B",map(envelope.b().get("reviewer"))));
        informedNotice(value,map(envelope.b().get("reviewer")));if(memo!=null)memo.put(key,new LinkedHashMap<>(value));return value;
    }

    static String eventHash(Map<?,?> event){return hash(Map.of("snapshot",map(event.get("snapshot")),"previous_hash",text(event,"previous_hash"),"change_reason",text(event,"change_reason")));}
    static Map<String,Map<?,?>> heads(Map<?,?> data){
        Work current=work.get();if(current!=null&&current.memoize&&current.heads.containsKey(data))return current.heads.get(data);if(current!=null)current.fullValidations++;
        need(Set.of(SCHEMA,SHARED_SCHEMA).contains(text(data,"version"))&&number(data.get("library_revision"))>0,"invalid_library_version");
        need("independently_curated_public_facts".equals(data.get("use_basis"))&&Boolean.FALSE.equals(data.get("license_claimed")),"library_use_basis_invalid");
        need(Boolean.TRUE.equals(data.get("local_planning_import_allowed"))&&Boolean.FALSE.equals(data.get("article_republication_allowed")),"not_local_planning_file");
        need(Boolean.FALSE.equals(data.get("strict_runtime_admission"))&&"prebid_city_planning_only".equals(data.get("purpose")),"strict_admission_not_supported");
        need(number(map(data.get("policy")).get("buffer_minutes"))==BUFFER_MINUTES&&"planning_assumption_not_source_error_bound".equals(map(data.get("policy")).get("buffer_basis")),"planning_policy_invalid");
        need(!map(data.get("documents")).isEmpty()&&map(data.get("documents")).size()<=10000,"document_count_invalid");
        for(Object key:map(data.get("documents")).keySet())doc(data,key.toString());
        Map<String,Map<?,?>> out=new LinkedHashMap<>();Set<String> pairs=new HashSet<>();
        for(Object raw:list(data.get("references"))){Map<?,?> chain=map(raw);String id=text(chain,"reference_id");need(id.matches("[A-Za-z0-9_.:-]{1,120}")&&!out.containsKey(id),"duplicate_reference_id");String previous="";int version=0;Map<?,?> first=Map.of(),last=Map.of();LocalDate prior=null;
            for(Object re:list(chain.get("revisions"))){Map<?,?> event=map(re),s=map(event.get("snapshot"));need(id.equals(s.get("reference_id"))&&number(s.get("version"))==++version,"reference_revision_invalid");need(previous.equals(event.get("previous_hash"))&&!text(event,"change_reason").isBlank()&&eventHash(event).equals(event.get("event_sha256")),"reference_chain_mismatch");
                snapshotKind(data,s);city(map(s.get("from")));city(map(s.get("to")));need(!same(s.get("from"),s.get("to")),"cross_city_pair_required");
                LocalDate reviewed=day(s.get("reviewed_on"));need(prior==null||!reviewed.isBefore(prior),"review_date_rollback");
                need(Set.of("active","revoked","superseded").contains(text(s,"status")),"reference_status_invalid");
                if(version==1)first=s;else need(same(first.get("from"),s.get("from"))&&same(first.get("to"),s.get("to")),"reference_cities_changed");
                // Every revision retains its original factual evidence, including revoked heads.
                if(sharedSnapshot(s))sharedFact(data,s);else{
                    fact(data,map(s.get("outbound")),map(s.get("from")),map(s.get("to")),reviewed);
                    fact(data,map(s.get("inbound")),map(s.get("to")),map(s.get("from")),reviewed);
                }
                previous=text(event,"event_sha256");prior=reviewed;last=event;
            }
            need(version>0,"empty_reference_history");Map<?,?> end=map(last.get("snapshot"));List<String> cities=new ArrayList<>(List.of(text(map(end.get("from")),"id"),text(map(end.get("to")),"id")));Collections.sort(cities);need(pairs.add(String.join(":",cities)),"duplicate_city_pair");out.put(id,last);
        }
        need(!out.isEmpty(),"empty_planning_library");if(current!=null&&current.memoize)current.heads.put(data,out);return out;
    }

    static Map<String,Object> anchor(Map<?,?> data){Map<String,Object> hs=new TreeMap<>();for(var e:heads(data).entrySet())hs.put(e.getKey(),Map.of("version",map(e.getValue().get("snapshot")).get("version"),"event_sha256",e.getValue().get("event_sha256")));return Map.of("version",ANCHOR,"library_revision",data.get("library_revision"),"library_sha256",hash(data),"heads",hs);}
    static void continuation(Map<?,?> old,Map<?,?> data){need(number(data.get("library_revision"))>=number(old.get("library_revision")),"library_rollback");if(number(data.get("library_revision"))==number(old.get("library_revision")))need(hash(data).equals(old.get("library_sha256")),"same_version_changed");for(var e:map(old.get("heads")).entrySet()){Map<?,?> chain=only(list(data.get("references")),r->e.getKey().equals(r.get("reference_id")),"reference_history_removed");Map<?,?> previous=map(e.getValue());int v=number(previous.get("version"));List<?> revisions=list(chain.get("revisions"));need(revisions.size()>=v&&Objects.equals(map(revisions.get(v-1)).get("event_sha256"),previous.get("event_sha256")),"reference_history_rewritten");}}
    static Map<?,?> unavailable(String reason){return Map.of("load_status","planning_library_unavailable","load_reason",reason);}
    static synchronized Map<?,?> bundle(){String file=System.getProperty(PROPERTY,System.getenv().getOrDefault(ENV,""));return file.isBlank()?Map.of():load(Path.of(file));}
    // Package-private raw binding for collection members; the single-library path is unchanged.
    static Map<?,?> readBoundJson(Path path,String expectedSha)throws java.io.IOException{Work current=work.get();if(current!=null)current.contentFileReads++;String raw=Files.readString(path);if(expectedSha!=null)need(rawHash(raw).equals(expectedSha),"planning_pinned_file_mismatch");if(current!=null)current.fileJsonParses++;return map(Json.parse(raw));}
    static synchronized Map<?,?> load(Path path){return loadPinned(path,null,null);}
    static synchronized Map<?,?> loadPinned(Path path,String fileSha,String anchorSha){try{Path p=path.toAbsolutePath().normalize(),a=Path.of(p+".integrity.json");need(Files.isRegularFile(p)&&Files.size(p)<=16*1024*1024&&Files.isRegularFile(a)&&Files.size(a)<=1024*1024,"planning_file_or_anchor_missing");Map<?,?> data=readBoundJson(p,fileSha),stored=readBoundJson(a,anchorSha);need(same(anchor(data),stored),"planning_anchor_mismatch");if(remembered.containsKey(p))continuation(remembered.get(p),data);remembered.put(p,stored);return data;}catch(Exception e){return unavailable(e.getMessage()==null?"invalid_planning_library":e.getMessage());}}

    private record CityPair(String fromProvince,String fromCity,String toProvince,String toCity){}
    private static String unorderedPair(Map<?,?> a,Map<?,?> b){List<String> ids=new ArrayList<>(List.of(text(a,"id"),text(b,"id")));Collections.sort(ids);return String.join(":",ids);}
    private static String unorderedPair(String fp,String fc,String tp,String tc){List<String> ids=new ArrayList<>(List.of(registryId(fp,fc),registryId(tp,tc)));Collections.sort(ids);return String.join(":",ids);}
    /** Request-scoped output snapshot. Never store this in a process/user/session cache. */
    static final class Index {
        private final LocalDate asOf;
        private final Map<CityPair,Map<String,Object>> directions;
        private final Map<String,Map<String,Object>> sharedPairs;
        private final Map<String,Object> defaultResult;
        private final boolean validLibrary;
        private final Map<String,Long> metrics;
        private Index(LocalDate date,Map<CityPair,Map<String,Object>> rows,Map<String,Object> missing,boolean valid,Map<String,Long> counters){this(date,rows,Map.of(),missing,valid,counters);}
        private Index(LocalDate date,Map<CityPair,Map<String,Object>> rows,Map<String,Map<String,Object>> shared,Map<String,Object> missing,boolean valid,Map<String,Long> counters){asOf=date;directions=Collections.unmodifiableMap(new LinkedHashMap<>(rows));sharedPairs=Collections.unmodifiableMap(new LinkedHashMap<>(shared));defaultResult=freezeMap(missing);validLibrary=valid;metrics=Map.copyOf(counters);}
        LocalDate asOf(){return asOf;}
        int directionCount(){return directions.size();}
        int sharedPairCount(){return sharedPairs.size();}
        Map<String,Long> buildMetrics(){return metrics;}
        Map<String,Object> lookup(String fp,String fc,String tp,String tc){
            if(!validLibrary)return defaultResult;
            if(!RegionDirectory.known(fp,fc)||!RegionDirectory.known(tp,tc))return freezeMap(pending("city_pending"));
            Map<String,Object> directional=directions.get(new CityPair(fp,fc,tp,tc));
            if(directional!=null)return directional;
            return sharedPairs.isEmpty()?defaultResult:sharedPairs.getOrDefault(unorderedPair(fp,fc,tp,tc),defaultResult);
        }
    }
    private static Object freeze(Object value){if(value instanceof Map<?,?>){Map<String,Object> result=new LinkedHashMap<>();for(var e:map(value).entrySet())result.put(e.getKey().toString(),freeze(e.getValue()));return Collections.unmodifiableMap(result);}if(value instanceof List<?>){List<Object> result=new ArrayList<>();for(Object item:list(value))result.add(freeze(item));return Collections.unmodifiableList(result);}return value;}
    @SuppressWarnings("unchecked") private static Map<String,Object> freezeMap(Map<String,Object> value){return (Map<String,Object>)freeze(value);}
    /** Exactly one file/anchor validation per request; all original parsing is discarded
     * after prebuilding immutable directional results. Next request starts from disk again. */
    static Index index(LocalDate today){return index(today,null);}
    /** Explicit optional presentation only. The original entry point never reads its settings. */
    static Index index(LocalDate today,CityPlanningPresentation.Catalog presentation){Objects.requireNonNull(today,"today");String file=System.getProperty(PROPERTY,System.getenv().getOrDefault(ENV,"")),collection=System.getProperty(CityPlanningCollection.PROPERTY,System.getenv().getOrDefault(CityPlanningCollection.ENV,""));
        if(!file.isBlank()&&!collection.isBlank())return new Index(today,Map.of(),pending("planning_configuration_conflict"),false,new Work(true).metrics());
        return !collection.isBlank()?collectionIndex(Path.of(collection),today,presentation):index(file.isBlank()?null:Path.of(file),today,presentation);}
    static Index index(Path path,LocalDate today){return index(path,today,null);}
    static Index index(Path path,LocalDate today,CityPlanningPresentation.Catalog presentation){Objects.requireNonNull(today,"today");need(work.get()==null,"nested_snapshot_build_not_supported");Work current=new Work(true);work.set(current);
        try{Map<?,?> data=path==null?Map.of():load(path);Map<CityPair,Map<String,Object>> rows=new LinkedHashMap<>();Map<String,Map<String,Object>> shared=new LinkedHashMap<>();
            if(data.isEmpty())return new Index(today,rows,pending("planning_library_not_configured"),false,current.metrics());
            if(data.containsKey("load_status"))return new Index(today,rows,pending(text(data,"load_reason")),false,current.metrics());
            CityPlanningPresentation.Binding binding=presentation==null?null:CityPlanningPresentation.binding(path,data);
            for(Map<?,?> event:heads(data).values()){Map<?,?> snapshot=map(event.get("snapshot")),a=map(snapshot.get("from")),b=map(snapshot.get("to"));
                if(sharedSnapshot(snapshot)){shared.put(unorderedPair(a,b),freezeMap(lookup(data,text(a,"province"),text(a,"city"),text(b,"province"),text(b,"city"),today)));continue;}
                for(boolean forward:List.of(true,false)){Map<?,?> from=forward?a:b,to=forward?b:a;CityPair key=new CityPair(text(from,"province"),text(from,"city"),text(to,"province"),text(to,"city"));
                    Map<String,Object> result=lookup(data,key.fromProvince(),key.fromCity(),key.toProvince(),key.toCity(),today);
                    rows.put(key,freezeMap(presentation==null?result:presentation.decorate(data,binding,result,today)));
                }}
            return new Index(today,rows,shared,pending("city_pair_unobserved"),true,current.metrics());
        }finally{work.remove();}
    }

    /** One request, separate source namespaces. A duplicate pair is never overwritten,
     * and a failed member cannot disappear behind a healthy sibling library. */
    static Index collectionIndex(Path path,LocalDate today){return collectionIndex(path,today,null);}
    static Index collectionIndex(Path path,LocalDate today,CityPlanningPresentation.Catalog presentation){Objects.requireNonNull(today,"today");need(work.get()==null,"nested_snapshot_build_not_supported");Work current=new Work(true);work.set(current);
        try{
            CityPlanningCollection.Loaded collection=CityPlanningCollection.load(path,today);
            if(!collection.valid())return new Index(today,Map.of(),pending(collection.reason()),false,current.metrics());
            CityPlanningPresentation.Catalog view=presentation==null?null:presentation.forCollection(path,collection.data());
            Map<String,CityPlanningPresentation.Binding> bindings=new LinkedHashMap<>();
            if(view!=null)for(var member:collection.members())bindings.put(member.id(),CityPlanningPresentation.binding(member.file(),member.library()));
            Map<String,List<Map<String,Object>>> owners=new LinkedHashMap<>();
            Map<String,CityPlanningCollection.Member> members=new LinkedHashMap<>();
            for(var member:collection.members()){members.put(member.id(),member);for(var entry:heads(member.library()).entrySet()){
                Map<?,?> snapshot=map(entry.getValue().get("snapshot")),from=map(snapshot.get("from")),to=map(snapshot.get("to"));
                List<String> ids=new ArrayList<>(List.of(text(from,"id"),text(to,"id")));Collections.sort(ids);
                owners.computeIfAbsent(String.join(":",ids),k->new ArrayList<>()).add(Map.of("member_id",member.id(),"reference_id",entry.getKey(),"snapshot",snapshot));
            }}
            Map<CityPair,Map<String,Object>> rows=new LinkedHashMap<>();Map<String,Map<String,Object>> shared=new LinkedHashMap<>();
            for(List<Map<String,Object>> matches:owners.values()){
                Map<?,?> snapshot=map(matches.get(0).get("snapshot")),a=map(snapshot.get("from")),b=map(snapshot.get("to"));
                boolean duplicate=matches.size()>1,withdrawn=matches.stream().anyMatch(m->!"active".equals(map(m.get("snapshot")).get("status")));
                boolean onlyShared=matches.stream().allMatch(m->sharedSnapshot(map(m.get("snapshot"))));
                for(boolean forward:List.of(true,false)){Map<?,?> from=forward?a:b,to=forward?b:a;CityPair key=new CityPair(text(from,"province"),text(from,"city"),text(to,"province"),text(to,"city"));Map<String,Object> result;
                    if(duplicate){result=pending(withdrawn?"collection_cross_library_revocation_conflict":"collection_duplicate_city_pair");result.put("conflicting_members",matches.stream().map(m->Map.of("member_id",m.get("member_id"),"reference_id",m.get("reference_id"),"status",map(m.get("snapshot")).get("status"))).toList());}
                    else{String memberId=text(matches.get(0),"member_id");result=lookup(members.get(memberId).library(),key.fromProvince(),key.fromCity(),key.toProvince(),key.toCity(),today);result.put("collection_member_id",memberId);
                        if(view!=null)result=view.decorate(members.get(memberId).library(),bindings.get(memberId),result,today);}
                    result.put("collection_id",collection.data().get("collection_id"));result.put("collection_revision",collection.data().get("collection_revision"));
                    if(onlyShared){shared.put(unorderedPair(a,b),freezeMap(result));break;}else rows.put(key,freezeMap(result));
                }
            }return new Index(today,rows,shared,pending("city_pair_unobserved"),true,current.metrics());
        }catch(RuntimeException error){return new Index(today,Map.of(),pending(Objects.toString(error.getMessage(),"collection_invalid")),false,current.metrics());}
        finally{work.remove();}
    }

    /** Explicit local preparation only. Caller supplies independently read raw documents;
     * no boolean promotes old research. New paths only; never rewrites sealed inputs/config. */
    static void prepare(Map<?,?> data,Path target,LocalDate today)throws java.io.IOException{
        Map<String,Map<?,?>> all=heads(data);for(Map<?,?> e:all.values()){Map<?,?> s=map(e.get("snapshot"));need(!day(s.get("reviewed_on")).isAfter(today),"future_review_date");for(String leg:sharedSnapshot(s)?List.of("shared_reference"):List.of("outbound","inbound")){Map<?,?> ref=map(s.get(leg)),source=doc(data,text(ref,"source_document"));LocalDate date=PublicUiCitySample.source(source)?PublicUiCitySample.referenceDate(source):day(source.get("published_on"));need(!date.isAfter(today),"future_source_date");}}
        Path p=target.toAbsolutePath().normalize(),a=Path.of(p+".integrity.json");need(!Files.exists(p)&&!Files.exists(a),"prepare_requires_new_paths");
        Files.writeString(p,Json.write(data)+"\n",StandardOpenOption.CREATE_NEW);Files.writeString(a,Json.write(anchor(data))+"\n",StandardOpenOption.CREATE_NEW);
    }

    static String timing(Map<?,?> f,LocalDate today){
        LocalDate date=PublicUiCitySample.method(f.get("verification_method"))?PublicUiCitySample.timingDate(f):day(f.get("source_published_on"));
        if(day(f.get("reviewed_on")).isAfter(today)||date.isAfter(today))return "future_metadata";
        if(today.isAfter(day(f.get("review_due_on"))))return "source_or_review_stale";
        if(!PublicUiCitySample.separateDates(f)&&f.get("effective_on")!=null&&today.isBefore(day(f.get("effective_on"))))return "not_yet_effective";
        if(f.get("effective_until")!=null&&today.isAfter(day(f.get("effective_until"))))return "source_period_ended";
        return "";
    }
    static final String SELECTION_BASIS="unbuffered_city_reference_v1";
    private static final Set<String> REFERENCE_PRECISIONS=Set.of("reported_minute","reported_minutes","nominal_hour","nominal_half_hour","approximate","computed_same_service","same_service_clocks","upper_bound","bounded_range");
    static String bandName(String id){return switch(id){case "planning_0_1h"->"不超过1小时档";case "planning_1_2h"->"1–2小时档";case "planning_2_3h"->"2–3小时档";case "planning_3_4h"->"3–4小时档";default->"";};}
    private static String bandForValue(int value){return value<=60?"planning_0_1h":value<=120?"planning_1_2h":value<=180?"planning_2_3h":"planning_3_4h";}
    static String band(int a,int b){return bandName(bandId(a,b));}
    static String bandId(int a,int b){need(a>0&&b>0,"positive_reference_required");int value=Math.max(a,b);return value>=240?"planning_range_pending":bandForValue(value);}
    /** A typed planning comparison, not a measured journey or ability score. The old
     * 30-minute reminder remains in historical envelopes, but must not tighten a
     * user-requested city-to-city rail cutoff from four hours to three and a half. */
    static Map<String,Object> selectionLeg(Map<?,?> fact){
        Map<?,?> duration=map(fact.get("source_duration"));String precision=text(fact,"precision");
        need(REFERENCE_PRECISIONS.contains(precision),"selection_precision_unsupported");
        boolean bound=Set.of("upper_bound","bounded_range").contains(precision);
        boolean exclusive=bound&&Boolean.FALSE.equals(duration.get("upper_inclusive"));
        if(bound)need(duration.get("upper_inclusive") instanceof Boolean,"selection_boundary_missing");
        return Map.of("reference_minutes",number(fact.get("reference_value_minutes_internal")),"unit","minute","precision",precision,"upper_exclusive",exclusive);
    }
    static Map<String,Object> selectionReference(Map<?,?> outward,Map<?,?> returning){return Map.of("basis",SELECTION_BASIS,"buffer_applied",false,"confirmed_itinerary",false,"outbound",selectionLeg(outward),"inbound",selectionLeg(returning));}
    static boolean selectionLegUnder(Map<?,?> leg,int maximum){
        need(leg.keySet().equals(Set.of("reference_minutes","unit","precision","upper_exclusive")),"selection_fields_invalid");
        String precision=text(leg,"precision");need("minute".equals(leg.get("unit"))&&REFERENCE_PRECISIONS.contains(precision)&&leg.get("upper_exclusive") instanceof Boolean,"selection_value_invalid");
        int value=number(leg.get("reference_minutes"));need(value>0,"positive_reference_required");boolean exclusive=Boolean.TRUE.equals(leg.get("upper_exclusive"));
        need(!exclusive||Set.of("upper_bound","bounded_range").contains(precision),"selection_false_exclusive_boundary");
        return value<maximum||value==maximum&&exclusive;
    }
    static boolean selectionUnder(Map<?,?> reference,int maximum){try{
        if(maximum<=0||maximum>240)return false;
        need(reference.keySet().equals(Set.of("basis","buffer_applied","confirmed_itinerary","outbound","inbound"))&&SELECTION_BASIS.equals(reference.get("basis"))&&Boolean.FALSE.equals(reference.get("buffer_applied"))&&Boolean.FALSE.equals(reference.get("confirmed_itinerary")),"selection_basis_invalid");
        // Validate both legs even when the first does not qualify.
        boolean out=selectionLegUnder(map(reference.get("outbound")),maximum),back=selectionLegUnder(map(reference.get("inbound")),maximum);return out&&back;
    }catch(RuntimeException invalid){return false;}}
    static String selectionBandId(Map<?,?> reference){if(!selectionUnder(reference,240))return "planning_range_pending";return bandForValue(Math.max(number(map(reference.get("outbound")).get("reference_minutes")),number(map(reference.get("inbound")).get("reference_minutes"))));}
    static Map<String,Object> pending(String reason){Map<String,Object> r=new LinkedHashMap<>();r.put("status","planning_pending");r.put("label","范围待核");r.put("reason",reason);r.put("time_score_applicable",false);r.put("transport_verified",false);r.put("air_fallback_trigger",false);r.put("rail_exclusion_complete",false);return r;}
    private static Map<String,Object> sharedLookup(Map<?,?> data,Map<?,?> snapshot,LocalDate today){
        Map<String,Object> r=pending("");r.put("reference_kind",SharedCityDuration.KIND);r.put("strict_eligibility",false);
        r.put("purpose","prebid_city_planning_only");r.put("reference_id",snapshot.get("reference_id"));r.put("reference_version",snapshot.get("version"));r.put("library_revision",data.get("library_revision"));
        if(!"active".equals(snapshot.get("status"))){r.put("reason","reference_"+snapshot.get("status"));return r;}
        Map<String,Object> value=sharedFact(data,snapshot);String reason=timing(value,today);if(!reason.isBlank()){r.put("reason",reason);return r;}
        r.put("status",SharedCityDuration.STATUS);r.put("label","往返列车单程共享参考，出行待核");r.put("shared_reference",value);
        r.put("protected_envelope_validated",true);r.put("semantic_annotations_are_language_proof",false);
        r.put("notice","仅一份公开单程共享摘要，不是两条独立方向观测；约数和城市副中心端点保留，市内接驳另核。仅按独立投标前规划策略判断，不是严格行程准入。");return r;
    }
    static Map<String,Object> lookup(Map<?,?> data,String fromProvince,String fromCity,String toProvince,String toCity,LocalDate today){
        if(data.isEmpty())return pending("planning_library_not_configured");if(data.containsKey("load_status"))return pending(text(data,"load_reason"));
        if(!RegionDirectory.known(fromProvince,fromCity)||!RegionDirectory.known(toProvince,toCity))return pending("city_pending");
        try{for(Map<?,?> event:heads(data).values()){Map<?,?> s=map(event.get("snapshot")),from=map(s.get("from")),to=map(s.get("to"));boolean forward=fromProvince.equals(from.get("province"))&&fromCity.equals(from.get("city"))&&toProvince.equals(to.get("province"))&&toCity.equals(to.get("city"));boolean back=fromProvince.equals(to.get("province"))&&fromCity.equals(to.get("city"))&&toProvince.equals(from.get("province"))&&toCity.equals(from.get("city"));if(!forward&&!back)continue;
                if(sharedSnapshot(s))return sharedLookup(data,s,today);
                if(!"active".equals(s.get("status")))return pending("reference_"+s.get("status"));LocalDate reviewed=day(s.get("reviewed_on"));Map<String,Object> a=fact(data,map(s.get(forward?"outbound":"inbound")),forward?from:to,forward?to:from,reviewed),b=fact(data,map(s.get(forward?"inbound":"outbound")),forward?to:from,forward?from:to,reviewed);
                for(Map<?,?> f:List.of(a,b)){String reason=timing(f,today);if(!reason.isBlank())return pending(reason);}
                boolean curatedA=CuratedCityDuration.method(a.get("verification_method")),curatedB=CuratedCityDuration.method(b.get("verification_method"));
                if(curatedA!=curatedB||!Objects.equals(a.get("verification_method"),b.get("verification_method")))return pending("curated_mixed_direction_methods_pending");
                Map<String,Object> selection=selectionReference(a,b);String bandId=selectionBandId(selection),band=bandName(bandId);
                if(band.isEmpty())return pending("planning_boundary_or_longer_reference");
                Map<String,Object> r=pending("");r.put("status","planning_reference");r.put("label","参考范围内，出行待核");r.put("reference_id",s.get("reference_id"));r.put("reference_version",s.get("version"));r.put("library_revision",data.get("library_revision"));r.put("planning_time_band",band);r.put("planning_band_id",bandId);r.put("selection_reference",selection);
                r.put("policy",Map.of("buffer_minutes",BUFFER_MINUTES,"buffer_applied_to_selection",false,"basis",SELECTION_BASIS,"notice","按双向城际铁路参考用时初筛，不叠加历史30分钟提醒。候乘与市内接驳另行确认；约数仍是约数，不代表具体行程已确认。"));
                if(curatedA){r.put("verification_method",a.get("verification_method"));r.put("protected_envelope_validated",true);r.put("semantic_annotations_are_language_proof",false);r.putAll(CuratedCityDuration.endpointNoticesAfterValidation(a,b));}
                // No inferred scalar/score, service IDs or source quotes. Curated values keep
                // their typed precision/bounds, never as strict eligibility or scoring inputs.
                for(Map<String,Object> f:List.of(a,b)){f.remove("reference_value_minutes_internal");f.remove("policy_adjusted_minutes_internal");
                    if(f.get("source_duration") instanceof Map<?,?> duration){Map<String,Object> typed=new LinkedHashMap<>();for(var entry:duration.entrySet())if(!Set.of("token","service_id","departure_clock","arrival_clock").contains(entry.getKey()))typed.put(entry.getKey().toString(),entry.getValue());f.put("source_duration",typed);}
                    f.put("read_disagreement_fields",new ArrayList<>(map(f.remove("read_differences")).keySet()));}r.put("outbound",a);r.put("inbound",b);r.put("purpose","prebid_city_planning_only");return r;
            }return pending("city_pair_unobserved");
        }catch(Exception e){return pending(e.getMessage()==null?"invalid_planning_library":e.getMessage());}
    }
}
