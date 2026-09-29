package com.training;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Explicit local-only adapter. A private Catalog, never a supplied response Map, is the
 * selection capability. Trusted callers retain pins separately; hashes are not signatures
 * or language proofs. The v1 two-source and v2 single-baseline limits are pilot boundaries, not a
 * nationwide design. Only an explicit server-side pinned configuration can enable the
 * business hook; there is no default path, environment, collection, UI or strict hook. */
final class ContextSharedPlanningAdapter {
    static final String VERSION="context-shared-planning-library-v1", ANCHOR="context-shared-planning-integrity-v1";
    static final String KIND="context_roundtrip_shared_v2", POLICY="context_shared_prebid_opt_in_v1";
    static final String BASELINE_VERSION="context-baseline-planning-library-v2", BASELINE_ANCHOR="context-baseline-planning-integrity-v2";
    static final String BASELINE_KIND="context_current_baseline_shared_v2", BASELINE_POLICY="context_shared_prebid_opt_in_v2";
    static final String BASELINE_CONTEXT="current_baseline_bidirectional_comparison_v1";
    enum OptIn { OFF, CONTEXT_SHARED_PREBID_V1, CURRENT_BASELINE_PREBID_V2 }
    static boolean ownsPolicy(Object value){return POLICY.equals(value)||BASELINE_POLICY.equals(value);}
    record Pins(Path sourceFixture,String sourceSha,Path rootReview,String rootReviewSha) {}
    static final String CONFIG_FILE_PROPERTY="dispatch.city.planning.context.config.file", CONFIG_SHA_PROPERTY="dispatch.city.planning.context.config.sha256";
    static final String CONFIG_VERSION="context-request-config-v1";
    /** A request snapshot, not a process/session cache or caller-supplied source Map. */
    static final class ConfiguredRequest {
        private final boolean requested;private final RequestCatalog catalog;private final LocalDate asOf;private final String reason;
        private ConfiguredRequest(boolean requested,RequestCatalog catalog,LocalDate asOf,String reason){this.requested=requested;this.catalog=catalog;this.asOf=asOf;this.reason=reason;}
        boolean requested(){return requested;}RequestCatalog catalog(){return catalog;}
        Map<String,Object> summary(){return requested?Map.of("status",reason.isEmpty()?"ready":"pending","as_of",asOf.toString(),"reason",reason):Map.of();}
    }
    private record ConfigRole(Path file,String fileSha,String anchorSha,Pins pins) {
        Catalog open(LocalDate day,OptIn mode){return ContextSharedPlanningAdapter.open(file,fileSha,anchorSha,pins,day,mode);}
    }
    private static Path configuredPath(Map<?,?> row,String key){
        Path p=Path.of(text(row,key));need(p.isAbsolute()&&p.normalize().equals(p),"configuration_path");return p;
    }
    private static ConfigRole configRole(Object raw){
        Map<?,?> row=map(raw);keys(row,"library_file","library_sha256","anchor_sha256","source_fixture","source_sha256","root_review","root_review_sha256");
        for(String key:List.of("library_sha256","anchor_sha256","source_sha256","root_review_sha256"))digest(text(row,key));
        return new ConfigRole(configuredPath(row,"library_file"),text(row,"library_sha256"),text(row,"anchor_sha256"),
                new Pins(configuredPath(row,"source_fixture"),text(row,"source_sha256"),configuredPath(row,"root_review"),text(row,"root_review_sha256")));
    }
    /** Trusted JVM settings only. Both roles are compulsory and keep their original policy.
     * All exceptions are reduced to fixed reason codes before reaching an HTTP response. */
    static ConfiguredRequest configured(LocalDate day){
        Objects.requireNonNull(day,"reference day");String file=System.getProperty(CONFIG_FILE_PROPERTY,""),sha=System.getProperty(CONFIG_SHA_PROPERTY,"");
        if(file.isBlank()&&sha.isBlank())return new ConfiguredRequest(false,null,day,"");
        String reason="context_configuration_incomplete";
        if(!file.isBlank()&&!sha.isBlank())try{
            Map<?,?> config=map(Json.parse(read(Path.of(file),sha,16*1024)));keys(config,"version","context_shared_v1","current_baseline_v2");
            need(CONFIG_VERSION.equals(config.get("version")),"configuration_version");
            ConfigRole first=configRole(config.get("context_shared_v1")),second=configRole(config.get("current_baseline_v2"));
            Catalog a=first.open(day,OptIn.CONTEXT_SHARED_PREBID_V1),b=second.open(day,OptIn.CURRENT_BASELINE_PREBID_V2);
            RequestCatalog routed=RequestCatalog.combine(day,List.of(a,b));
            return new ConfiguredRequest(true,routed,day,routed.valid()?"":"context_component_verification_pending");
        }catch(Exception invalid){reason="context_configuration_invalid";}
        return new ConfiguredRequest(true,RequestCatalog.combine(day,List.of()),day,reason);
    }
    private record Evidence(Map<?,?> packet,String review,Pins pins,boolean baseline) {}
    private record Validated(Map<String,Map<?,?>> heads,Map<String,Map<String,Object>> values) {}
    private static final Map<String,Map<?,?>> remembered=new HashMap<>();
    private static final Map<Path,String> rememberedPaths=new HashMap<>();
    private static void need(boolean b,String why){CityPlanningReference.need(b,"context_"+why);}
    private static Map<?,?> map(Object x){return CityPlanningReference.map(x);}
    private static List<?> list(Object x){return CityPlanningReference.list(x);}
    private static String text(Map<?,?> x,String k){return CityPlanningReference.text(x,k);}
    private static int num(Object x){return CityPlanningReference.number(x);}
    private static LocalDate day(Object x){return CityPlanningReference.day(x);}
    private static String hash(Object x){return CityPlanningReference.hash(x);}
    private static void keys(Map<?,?> x,String... k){need(x.keySet().equals(Set.of(k)),"unknown_or_missing_fields");}
    private static Map<String,Object> without(Map<?,?> x,String... keys){Map<String,Object> r=new LinkedHashMap<>();x.forEach((k,v)->r.put(k.toString(),v));for(String k:keys)r.remove(k);return r;}
    private static void digest(String x){need(x.matches("[a-f0-9]{64}"),"invalid_pin");}
    private static String read(Path p,String sha,int max)throws Exception{
        digest(sha);need(p!=null&&p.isAbsolute()&&p.normalize().equals(p)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)>0&&Files.size(p)<=max,"file_path_or_size");
        String raw=Files.readString(p);need(CityPlanningReference.rawHash(raw).equals(sha),"pinned_file_mismatch");return raw;
    }
    private static Evidence evidence(Pins p,boolean baseline)throws Exception{
        need(p!=null,"pins_missing");Map<?,?> packet=map(Json.parse(read(p.sourceFixture(),p.sourceSha(),1024*1024)));
        need((baseline?"private-context-baseline-source-fixture-draft-v1":"private-context-shared-source-fixture-v1").equals(packet.get("schema")),"source_packet_version");
        need(hash(without(packet,"source_structure_sha256")).equals(packet.get("source_structure_sha256")),"source_packet_structure_hash");
        need(Boolean.FALSE.equals(packet.get("runtime_enabled"))&&num(packet.get("admitted_pair_increment"))==0,"source_packet_scope");
        need(!list(packet.get("sources")).isEmpty()&&list(packet.get("sources")).size()<=(baseline?1:2),"bounded_source_count");
        String review=read(p.rootReview(),p.rootReviewSha(),64*1024);
        need(baseline?review.contains("Approval is limited to one context-based city planning reference")&&review.contains(p.sourceSha())&&review.contains("not future service"):
            review.contains("Approve these drafts' semantics")&&review.contains("context-inferred city planning reference only"),"review_scope_missing");
        return new Evidence(packet,review,p,baseline);
    }
    private static Map<?,?> span(Map<?,?> structure,Map<String,String> units,String role){
        Map<?,?> s=CityPlanningReference.only(list(structure.get("spans")),r->role.equals(r.get("role")),"context_span_missing");
        keys(s,"role","unit_id","start","end","text","offset_unit");String raw=units.get(text(s,"unit_id"));int a=num(s.get("start")),b=num(s.get("end"));
        need(raw!=null&&a<b&&b<=raw.length()&&"utf16".equals(s.get("offset_unit"))&&raw.substring(a,b).equals(s.get("text")),"span_mismatch");
        for(int edge:new int[]{a,b})need(edge==0||edge==raw.length()||!Character.isHighSurrogate(raw.charAt(edge-1))||!Character.isLowSurrogate(raw.charAt(edge)),"split_surrogate");return s;
    }
    private static String parent(Map<?,?> source,Map<?,?> span){return text(CityPlanningReference.only(list(source.get("units")),u->u.get("unit_id").equals(span.get("unit_id")),"context_unit_missing"),"parent_segment");}
    private static void sameUnit(Map<?,?> a,Map<?,?> b){need(a.get("unit_id").equals(b.get("unit_id")),"cross_unit_role_splice");}
    private static void roles(Map<?,?> structure,Set<String> expected){Set<String> found=new HashSet<>();for(Object raw:list(structure.get("spans")))need(found.add(text(map(raw),"role")),"duplicate_role");need(found.equals(expected),"unexpected_roles");}


    /** Narrow, reviewed comparison grammar with parameterized city names and hour values.
     * It never treats the future operand, arrows generally, or arbitrary project prose as a fact. */
    private static void baseline(Map<?,?> s,Map<?,?> c,Map<?,?> structure,Map<String,String> units,Map<?,?> a,Map<?,?> b){
        keys(structure,"context_type","actual_source_segments","relation","source_same_document_binding_required","full_comparison_sentence_required","editorial_joined_sentence_created",
            "temporal_reference_resolution","rail_context_resolution","city_pair_mapping","bidirectional_symbol","baseline_is_current_by_comparison","future_duration_excluded","duration_role_order","spans","context_structure_sha256");
        need(BASELINE_CONTEXT.equals(c.get("context_type"))&&BASELINE_CONTEXT.equals(structure.get("context_type")),"baseline_context_type");
        need(Boolean.FALSE.equals(c.get("literal_roundtrip_wording_present"))&&Boolean.TRUE.equals(c.get("source_period_unknown"))&&
            "reported_existing_city_travel_at_publication_not_future_project_service".equals(c.get("baseline_scope")),"baseline_scope");
        need(CityPlanningReference.same(c.get("registry_binding"),List.of(a,b)),"baseline_registry_binding");
        for(String f:List.of("source_same_document_binding_required","full_comparison_sentence_required","baseline_is_current_by_comparison","future_duration_excluded"))need(Boolean.TRUE.equals(structure.get(f)),"baseline_context_scope");
        for(String f:List.of("relation","temporal_reference_resolution","rail_context_resolution","city_pair_mapping"))need(!text(structure,f).isBlank(),"baseline_context_explanation");
        need("⇋".equals(structure.get("bidirectional_symbol"))&&list(structure.get("duration_role_order")).equals(List.of("comparison_from","baseline_duration","comparison_to","future_duration")),"baseline_role_order");
        roles(structure,Set.of("context_city_a","context_city_b","rail_mode","future_scope","city_a","bidirectional_symbol","city_b","travel_measure","future_auxiliary","comparison_from","baseline_duration","comparison_to","future_duration"));
        Map<String,Map<?,?>> sp=new LinkedHashMap<>();for(Object raw:list(structure.get("spans"))){String role=text(map(raw),"role");sp.put(role,span(structure,units,role));}
        List<String> ordered=List.of("future_scope","city_a","bidirectional_symbol","city_b","travel_measure","future_auxiliary","comparison_from","baseline_duration","comparison_to","future_duration");
        Map<?,?> first=sp.get("future_scope"),previous=null;for(String role:ordered){Map<?,?> current=sp.get(role);sameUnit(first,current);if(previous!=null)need(num(previous.get("end"))<=num(current.get("start")),"baseline_span_order");previous=current;}
        for(var literal:Map.of("future_scope","项目建成后","bidirectional_symbol","⇋","travel_measure","通行时间","future_auxiliary","将","comparison_from","从","comparison_to","压缩至","rail_mode","高铁").entrySet())need(literal.getValue().equals(sp.get(literal.getKey()).get("text")),"baseline_literal_"+literal.getKey());
        need(a.get("city").equals(sp.get("city_a").get("text"))&&b.get("city").equals(sp.get("city_b").get("text"))&&a.get("city").equals(sp.get("context_city_a").get("text"))&&b.get("city").equals(sp.get("context_city_b").get("text")),"baseline_city_pair");
        sameUnit(sp.get("rail_mode"),sp.get("context_city_a"));sameUnit(sp.get("context_city_a"),sp.get("context_city_b"));
        need(num(sp.get("rail_mode").get("end"))==num(sp.get("context_city_a").get("start"))&&num(sp.get("context_city_a").get("end"))<num(sp.get("context_city_b").get("start")),"baseline_rail_pair_order");
        String railText=units.get(text(sp.get("rail_mode"),"unit_id"));need(railText.substring(num(sp.get("context_city_a").get("end")),num(sp.get("context_city_b").get("start"))).equals("至"),"baseline_rail_city_join");
        String comparison=units.get(text(first,"unit_id")),token=text(sp.get("baseline_duration"),"text"),future=text(sp.get("future_duration"),"text");
        String prefix="项目建成后，"+a.get("city")+"⇋"+b.get("city")+"通行时间将从"+token+"压缩至"+future;
        need(num(first.get("start"))==0&&comparison.startsWith(prefix)&&num(sp.get("future_duration").get("end"))==prefix.length()&&comparison.endsWith("。")&&
            comparison.indexOf('。')==comparison.length()-1&&(comparison.substring(prefix.length()).equals("。")||comparison.substring(prefix.length()).startsWith("，")),"baseline_complete_comparison");
        String railParent=parent(s,sp.get("rail_mode")),comparisonParent=parent(s,first);need(!railParent.isBlank()&&!comparisonParent.isBlank()&&!railParent.equals(comparisonParent),"baseline_original_paragraphs");
        List<?> segments=list(structure.get("actual_source_segments"));need(segments.size()==2,"baseline_segment_count");
        for(String parent:List.of(railParent,comparisonParent)){Map<?,?> segment=CityPlanningReference.only(segments,r->parent.equals(r.get("id")),"baseline_segment");need(!text(segment,"locator").isBlank(),"baseline_segment_locator");}
        Map<?,?> observation=map(c.get("shared_observation"));keys(observation,"reference_minutes","precision","qualifier","original_duration_text","source_scalar");
        need("nominal_hour".equals(observation.get("precision"))&&"reported_current_baseline".equals(observation.get("qualifier"))&&token.equals(observation.get("original_duration_text")),"baseline_precision_qualifier");
        Map<?,?> scalar=map(observation.get("source_scalar"));need("nominal_hour".equals(scalar.get("kind"))&&token.equals(scalar.get("token")),"baseline_original_hour_scalar");
        CuratedCityDuration.Duration parsed=CuratedCityDuration.scalarDuration(Map.of("spans",Map.of("duration",List.of(without(sp.get("baseline_duration"),"role")))),scalar,units);
        need(parsed.value()>0&&parsed.value()==num(observation.get("reference_minutes")),"baseline_duration_recalculation");
        // Parse the excluded operand only to check the reduction; it is never returned as a reference.
        need(future.matches("[0-9]+小时左右"),"baseline_future_token");
        int futureMinutes=CityPlanningReference.literalMinutes(future);need(futureMinutes>0&&futureMinutes<parsed.value(),"baseline_not_a_reduction");
        need("displayed_article_publication".equals(s.get("date_basis"))&&!text(s,"source_type").isBlank()&&!text(s,"attributed_source").isBlank(),"baseline_source_metadata");
        String header=text(s,"published_on")+" "+text(s,"published_time_local")+" "+text(s,"attributed_source");need(units.values().stream().anyMatch(header::equals),"baseline_publication_attribution_binding");
    }

    /** Mechanical binding after separately pinned informed root/child AI semantic review; does not infer
     * arbitrary Chinese aliases or conclude that each leg actually took the shared value. */
    private static Map<String,Object> source(Evidence e,Map<?,?> snapshot){
        String sid=text(snapshot,"source_id");Map<?,?> s=CityPlanningReference.only(list(e.packet().get("sources")),r->sid.equals(r.get("source_id")),"context_source_missing");
        CityPlanningReference.originalRestrictions(s);CityPlanningReference.url(text(s,"url"));
        need(hash(without(s,"source_document_sha256","semantic_draft","prior_root_original_reads")).equals(s.get("source_document_sha256")),"source_record_hash");
        for(String field:List.of("source_document_sha256","context_structure_sha256","canonical_sha256"))digest(text(snapshot,field));
        need(s.get("source_document_sha256").equals(snapshot.get("source_document_sha256")),"snapshot_source_binding");
        Map<?,?> c=map(s.get("semantic_draft")),structure=map(s.get("context_structure"));
        if(e.baseline())keys(c,"fact_kind","context_type","pair","registry_binding","endpoint_scope","stations","shared_observation","direction_semantics","trip_interpretation","independent_direction_observations","literal_roundtrip_wording_present","literal_single_trip_wording_present","interpretation","baseline_scope","prohibited_inferences","source_period_unknown","transport_verified","rail_exclusion_complete","air_fallback_trigger","draft_approval","canonical_sha256","source_id","source_document_sha256","context_structure_sha256");
        else keys(c,"fact_kind","pair","registry_binding","endpoint_scope","stations","shared_observation","direction_semantics","trip_interpretation","independent_direction_observations","literal_single_trip_wording_present","interpretation","prohibited_inferences","source_period_unknown","transport_verified","rail_exclusion_complete","air_fallback_trigger","draft_approval","canonical_sha256","source_id","source_document_sha256","context_structure_sha256");
        need(hash(without(c,"canonical_sha256")).equals(c.get("canonical_sha256"))&&c.get("canonical_sha256").equals(snapshot.get("canonical_sha256")),"canonical_hash");
        need(hash(without(structure,"context_structure_sha256")).equals(structure.get("context_structure_sha256"))&&structure.get("context_structure_sha256").equals(snapshot.get("context_structure_sha256")),"structure_hash");
        need(sid.equals(c.get("source_id"))&&s.get("source_document_sha256").equals(c.get("source_document_sha256"))&&structure.get("context_structure_sha256").equals(c.get("context_structure_sha256")),"draft_source_binding");
        need(e.review().contains((e.baseline()?"Approved canonical ":sid+" canonical")+text(c,"canonical_sha256")),"exact_draft_not_approved");
        need((e.baseline()?BASELINE_KIND:KIND).equals(c.get("fact_kind"))&&"city_summary".equals(c.get("endpoint_scope"))&&list(c.get("stations")).size()==2&&list(c.get("stations")).stream().allMatch(Objects::isNull),"unknown_station_not_preserved");
        need((e.baseline()?"bidirectional_symbol_single_shared_baseline":"roundtrip_context_single_shared_statement").equals(c.get("direction_semantics"))&&(e.baseline()?"contextual_city_travel_reference_not_literal_single_trip":"contextual_leg_reference_not_literal_single_trip").equals(c.get("trip_interpretation")),"inference_semantics");
        need(num(c.get("independent_direction_observations"))==0&&Boolean.FALSE.equals(c.get("literal_single_trip_wording_present")),"not_two_direction_observations");
        for(String f:List.of("transport_verified","rail_exclusion_complete","air_fallback_trigger"))need(Boolean.FALSE.equals(c.get(f)),"unsupported_capability");
        need(Boolean.FALSE.equals(structure.get("editorial_joined_sentence_created")),"invented_continuous_sentence");
        Map<?,?> a=map(snapshot.get("from")),b=map(snapshot.get("to"));CityPlanningReference.city(a);CityPlanningReference.city(b);
        List<?> pair=list(c.get("pair"));need(pair.size()==2,"pair_count");
        for(int i=0;i<2;i++){Map<?,?> city=i==0?a:b,original=map(pair.get(i));keys(city,"id","province","city");keys(original,"province","city");need(original.get("province").equals(city.get("province"))&&original.get("city").equals(city.get("city")),"registry_pair_mismatch");}
        need(!a.get("id").equals(b.get("id")),"same_city");
        Map<String,String> units=CityPlanningReference.units(s);for(Object raw:list(structure.get("spans")))span(structure,units,text(map(raw),"role"));
        Map<?,?> observation=map(c.get("shared_observation"));
        if(e.baseline())baseline(s,c,structure,units,a,b);
        else {
        Map<?,?> from=span(structure,units,"city_a"),to=span(structure,units,"city_b"),roundtrip=span(structure,units,"roundtrip"),rail=span(structure,units,"rail_mode"),duration=span(structure,units,"duration");
        need(a.get("city").equals(from.get("text"))&&b.get("city").equals(to.get("text"))&&"往返".equals(roundtrip.get("text"))&&"高铁".equals(rail.get("text")),"literal_role_binding");
        need(!parent(s,rail).isBlank()&&parent(s,rail).equals(parent(s,duration))&&parent(s,roundtrip).equals(parent(s,duration)),"rail_roundtrip_parent_mismatch");
        keys(observation,"reference_minutes","precision","qualifier","original_duration_text");
        String qualifier=text(observation,"qualifier");
        if(structure.containsKey("alias")){
            roles(structure,Set.of("rail_mode","pair_alias","roundtrip","qualifier","duration","city_a","city_b"));
            need(Boolean.TRUE.equals(structure.get("source_same_document_binding_required"))&&list(structure.get("actual_source_segments")).size()==2,"alias_context_scope");
            Map<?,?> alias=span(structure,units,"pair_alias"),q=span(structure,units,"qualifier");sameUnit(alias,roundtrip);sameUnit(alias,duration);sameUnit(q,duration);sameUnit(from,to);
            need(alias.get("text").equals(structure.get("alias"))&&"最快".equals(q.get("text"))&&"reported_fastest".equals(qualifier),"alias_or_fastest_binding");
            List<?> targets=list(structure.get("alias_targets"));need(targets.size()==2,"alias_targets");for(int i=0;i<2;i++){Map<?,?> t=map(targets.get(i)),city=i==0?a:b;keys(t,"province","city","original_token");need(t.get("province").equals(city.get("province"))&&t.get("city").equals(city.get("city"))&&t.get("original_token").equals(city.get("city")),"alias_wrong_city");}
        }else{
            roles(structure,Set.of("past_scope","roundtrip","city_a","city_b","current_scope","rail_mode","duration"));
            need(Boolean.TRUE.equals(structure.get("source_same_paragraph_binding_required"))&&list(structure.get("actual_source_segments")).size()==1&&!text(structure,"temporal_reference_resolution").isBlank(),"temporal_context_scope");
            Map<?,?> past=span(structure,units,"past_scope"),current=span(structure,units,"current_scope");sameUnit(past,from);sameUnit(from,to);sameUnit(to,roundtrip);sameUnit(current,rail);sameUnit(rail,duration);
            need("以前".equals(past.get("text"))&&"现在".equals(current.get("text"))&&parent(s,past).equals(parent(s,current))&&"general_reported".equals(qualifier),"past_current_binding");
        }
        String precision=text(observation,"precision"),token=text(observation,"original_duration_text");need(Set.of("reported_minutes","approximate").contains(precision),"precision_unsupported");
        Map<String,Object> scalar=new LinkedHashMap<>();scalar.put("kind",precision);scalar.put("value",observation.get("reference_minutes"));scalar.put("unit","minute");scalar.put("token",token);
        Map<String,Object> fact=Map.of("spans",Map.of("duration",List.of(without(duration,"role"))));
        CuratedCityDuration.Duration parsed=CuratedCityDuration.scalarDuration(fact,scalar,units);need(parsed.value()==num(observation.get("reference_minutes")),"duration_recalculation");
        }
        LocalDate published=day(s.get("published_on")),reviewed=day(snapshot.get("reviewed_on"));need(!reviewed.isBefore(published)&&reviewed.equals(day(map(s.get("access")).get("checked_on")))&&e.review().contains(reviewed.toString()),"review_date_binding");
        need("original_public_page_read".equals(map(s.get("access")).get("status"))&&!text(map(s.get("access")),"result_locator").isBlank(),"original_read_missing");
        need(!list(s.get("prior_root_original_reads")).isEmpty(),"root_original_read_missing");for(Object raw:list(s.get("prior_root_original_reads"))){Map<?,?> r=map(raw);need(Boolean.TRUE.equals(r.get("informed_original_source_crosscheck"))&&!text(r,"locator").isBlank(),"root_informed_read_missing");}
        need("no_explicit_minimum_fact_or_automation_prohibition_observed_in_read_scope".equals(map(s.get("policy_review")).get("state"))&&Boolean.FALSE.equals(map(s.get("policy_review")).get("permission_grant_claimed")),"source_scope_restricted");
        CityPlanningReference.sourcePolicy(map(snapshot.get("source_policy")),reviewed);
        need(s.get("effective_on")==null&&s.get("effective_until")==null,"unsupported_effective_period");
        LocalDate due=published.plusDays(CityPlanningReference.SOURCE_DAYS);if(reviewed.plusDays(CityPlanningReference.REVIEW_DAYS).isBefore(due))due=reviewed.plusDays(CityPlanningReference.REVIEW_DAYS);
        Map<String,Object> result=new LinkedHashMap<>();result.put("reference_kind",e.baseline()?BASELINE_KIND:KIND);result.put("city_a",a);result.put("city_b",b);result.put("shared_observation",observation);
        result.put("endpoint_scope","city_summary");result.put("stations",Arrays.asList(null,null));result.put("direction_semantics",c.get("direction_semantics"));result.put("trip_interpretation",c.get("trip_interpretation"));result.put("independent_direction_observations",0);
        result.put("source_url",s.get("url"));result.put("source_type",s.get("source_type"));result.put("attributed_source",s.get("attributed_source"));result.put("source_published_on",published.toString());result.put("reviewed_on",reviewed.toString());result.put("review_due_on",due.toString());result.put("effective_on",null);result.put("effective_until",null);
        result.put("source_document_sha256",s.get("source_document_sha256"));result.put("context_structure_sha256",structure.get("context_structure_sha256"));result.put("canonical_sha256",c.get("canonical_sha256"));result.put("root_review_sha256",e.pins().rootReviewSha());
        for(String f:List.of("transport_verified","strict_eligibility","value_is_proven_travel_upper_bound","rail_exclusion_complete","air_fallback_trigger","time_score_applicable","semantic_annotations_are_language_proof"))result.put(f,false);
        if(e.baseline()){result.put("context_type",c.get("context_type"));result.put("baseline_scope",c.get("baseline_scope"));result.put("literal_roundtrip_wording_present",false);result.put("literal_single_trip_wording_present",false);}
        return freeze(result);
    }
    private static Validated validate(Map<?,?> data,Evidence e){
        keys(data,"version","library_id","library_revision","purpose","policy","source_fixture_sha256","root_review_sha256","references");
        need((e.baseline()?BASELINE_VERSION:VERSION).equals(data.get("version"))&&text(data,"library_id").matches("[A-Za-z0-9_.:-]{1,100}")&&num(data.get("library_revision"))>0,"library_version");
        need("local_prebid_context_shared_tests_only".equals(data.get("purpose"))&&(e.baseline()?BASELINE_POLICY:POLICY).equals(data.get("policy")),"library_purpose");
        need(e.pins().sourceSha().equals(data.get("source_fixture_sha256"))&&e.pins().rootReviewSha().equals(data.get("root_review_sha256")),"library_source_pins");
        List<?> chains=list(data.get("references"));need(!chains.isEmpty()&&chains.size()<=(e.baseline()?1:2),"reference_count");Map<String,Map<?,?>> heads=new LinkedHashMap<>();Map<String,Map<String,Object>> values=new LinkedHashMap<>();Set<String> pairs=new HashSet<>();
        for(Object raw:chains){Map<?,?> chain=map(raw);keys(chain,"reference_id","revisions");String id=text(chain,"reference_id");need(id.matches("[A-Za-z0-9_.:-]{1,100}")&&!heads.containsKey(id),"duplicate_reference");List<?> events=list(chain.get("revisions"));need(!events.isEmpty()&&events.size()<=256,"history_count");
            String previous="";int revision=0;Map<?,?> first=Map.of(),last=Map.of();LocalDate previousReview=null;Map<String,Object> value=Map.of();
            for(Object re:events){Map<?,?> event=map(re);keys(event,"snapshot","previous_hash","change_reason","event_sha256");Map<?,?> s=map(event.get("snapshot"));
                keys(s,"reference_id","version","reference_kind","from","to","status","reviewed_on","source_id","source_document_sha256","context_structure_sha256","canonical_sha256","source_policy");
                need(id.equals(s.get("reference_id"))&&num(s.get("version"))==++revision&&(e.baseline()?BASELINE_KIND:KIND).equals(s.get("reference_kind")),"snapshot_version");
                need(previous.equals(event.get("previous_hash"))&&!text(event,"change_reason").isBlank()&&CityPlanningReference.eventHash(event).equals(event.get("event_sha256")),"history_hash");
                need(Set.of("active","revoked","superseded").contains(text(s,"status")),"status");
                LocalDate reviewed=day(s.get("reviewed_on"));need(previousReview==null||!reviewed.isBefore(previousReview),"review_rollback");
                if(revision==1)first=s;else{for(String f:List.of("from","to","source_id","source_document_sha256","context_structure_sha256","canonical_sha256"))need(CityPlanningReference.same(first.get(f),s.get(f)),"history_evidence_changed");need(!"revoked".equals(map(last.get("snapshot")).get("status"))||"revoked".equals(s.get("status")),"revocation_reactivated");}
                value=source(e,s);previous=text(event,"event_sha256");previousReview=reviewed;last=event;
            }
            Map<?,?> head=map(last.get("snapshot"));String key=pair(map(head.get("from")),map(head.get("to")));need(pairs.add(key),"duplicate_city_pair");heads.put(id,last);
            Map<String,Object> v=new LinkedHashMap<>(value);v.put("reference_id",id);v.put("reference_version",head.get("version"));v.put("reference_status",head.get("status"));v.put("reference_event_sha256",last.get("event_sha256"));values.put(key,freeze(v));
        }return new Validated(heads,values);
    }
    private static Map<String,Object> anchor(Map<?,?> data,Validated v){Map<String,Object> heads=new TreeMap<>();v.heads().forEach((id,e)->heads.put(id,Map.of("version",map(e.get("snapshot")).get("version"),"event_sha256",e.get("event_sha256"))));return Map.of("version",BASELINE_VERSION.equals(data.get("version"))?BASELINE_ANCHOR:ANCHOR,"library_id",data.get("library_id"),"library_revision",data.get("library_revision"),"library_sha256",hash(data),"heads",heads);}
    static Map<String,Object> anchorForPreparation(Map<?,?> data,Pins p)throws Exception{return anchorForPreparation(data,p,OptIn.CONTEXT_SHARED_PREBID_V1);}
    static Map<String,Object> anchorForPreparation(Map<?,?> data,Pins p,OptIn mode)throws Exception{need(mode==OptIn.CONTEXT_SHARED_PREBID_V1||mode==OptIn.CURRENT_BASELINE_PREBID_V2,"preparation_opt_in_required");return anchor(data,validate(data,evidence(p,mode==OptIn.CURRENT_BASELINE_PREBID_V2)));}
    private static String pair(Map<?,?> a,Map<?,?> b){List<String> ids=new ArrayList<>(List.of(text(a,"id"),text(b,"id")));Collections.sort(ids);return String.join(":",ids);}
    @SuppressWarnings("unchecked") private static Map<String,Object> freeze(Map<String,Object> data){return (Map<String,Object>)immutable(data);}
    private static Object immutable(Object x){if(x instanceof Map<?,?> m){Map<String,Object> r=new LinkedHashMap<>();m.forEach((k,v)->r.put(k.toString(),immutable(v)));return Collections.unmodifiableMap(r);}if(x instanceof List<?> l){List<Object> r=new ArrayList<>();for(Object v:l)r.add(immutable(v));return Collections.unmodifiableList(r);}return x;}
    /** ONE request/asOf snapshot. Never cache across days, requests or user sessions;
     * callers must open again with that request's current date and independently held pins. */
    static final class Catalog {
        private final LocalDate asOf;private final Map<String,Map<String,Object>> values;private final String reason,policy;
        private Catalog(LocalDate day,Map<String,Map<String,Object>> values,String reason,boolean baseline){this.asOf=day;this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values));this.reason=reason;this.policy=baseline?BASELINE_POLICY:POLICY;}
        boolean valid(){return reason.isEmpty();}String reason(){return reason;}int sharedCount(){return values.size();}int independentDirectionCount(){return 0;}
        boolean matchesAsOf(LocalDate requestDate){return requestDate!=null&&requestDate.equals(asOf);}
        Map<String,Object> lookup(String fp,String fc,String tp,String tc){if(!valid())return Map.of();String a=CityPlanningReference.registryId(fp,fc),b=CityPlanningReference.registryId(tp,tc);if(a.isBlank()||b.isBlank()||a.equals(b))return Map.of();List<String> ids=new ArrayList<>(List.of(a,b));Collections.sort(ids);return values.getOrDefault(String.join(":",ids),Map.of());}
        Map<String,Object> select(String fp,String fc,String tp,String tc,int userLimit){
            Map<String,Object> ref=lookup(fp,fc,tp,tc);String why=reason;boolean match=false;
            if(why.isEmpty()){if(ref.isEmpty())why="city_pair_unobserved";else if(userLimit<=0||userLimit>240)why="invalid_planning_limit";else if(!"active".equals(ref.get("reference_status")))why="reference_"+ref.get("reference_status");else{why=CityPlanningReference.timing(ref,asOf);if(why.isEmpty()){match=num(map(ref.get("shared_observation")).get("reference_minutes"))<userLimit;if(!match)why="shared_reference_not_below_limit";}}}
            // The stored reference is already deeply immutable. Preserve its identity
            // across decisions instead of copying one shared fact for every teacher.
            Map<String,Object> r=new LinkedHashMap<>();r.put("policy",policy);r.put("planning_pool_eligible",match);r.put("reason",why);r.put("reference",ref);r.put("strict_eligibility",false);r.put("transport_verified",false);r.put("rail_exclusion_complete",false);r.put("air_fallback_trigger",false);r.put("time_score_applicable",false);return Collections.unmodifiableMap(r);
        }
    }

    /** Request-local routing of already verified capabilities, never a merged source/policy.
     * Invalid components disable only this additive context path. Known expired/revoked rows
     * stay indexed, so a second component cannot silently replace them with a preferable value. */
    static final class RequestCatalog {
        private record Route(Catalog owner,Map<String,Object> reference,String fingerprint) {}
        private final LocalDate asOf;private final Map<String,Route> routes;private final Set<String> conflicts;private final String reason;
        private RequestCatalog(LocalDate day,Map<String,Route> routes,Set<String> conflicts,String reason){
            this.asOf=day;this.routes=Collections.unmodifiableMap(new LinkedHashMap<>(routes));this.conflicts=Set.copyOf(conflicts);this.reason=reason;
        }
        static RequestCatalog combine(LocalDate day,List<Catalog> components){
            try{
                need(day!=null&&components!=null&&!components.isEmpty()&&components.size()<=2,"request_component_count");
                Map<String,Route> routes=new LinkedHashMap<>();Set<String> conflicts=new HashSet<>();
                for(Catalog owner:components){
                    need(owner!=null&&owner.valid(),"request_component_invalid");need(owner.matchesAsOf(day),"request_component_date");
                    for(var entry:owner.values.entrySet()){
                        Map<String,Object> ref=entry.getValue();String key=pair(map(ref.get("city_a")),map(ref.get("city_b")));
                        need(key.equals(entry.getKey()),"request_pair_binding");String fingerprint=hash(Map.of("policy",owner.policy,"reference",ref));
                        Route prior=routes.putIfAbsent(key,new Route(owner,ref,fingerprint));
                        if(prior!=null&&!prior.fingerprint().equals(fingerprint))conflicts.add(key);
                    }
                }
                need(routes.size()<=3,"request_pair_count");return new RequestCatalog(day,routes,conflicts,"");
            }catch(RuntimeException invalid){return new RequestCatalog(day,Map.of(),Set.of(),Objects.toString(invalid.getMessage(),"context_request_invalid"));}
        }
        boolean valid(){return reason.isEmpty();}String reason(){return reason;}boolean matchesAsOf(LocalDate day){return day!=null&&day.equals(asOf);}
        int pairCount(){return routes.size();}int conflictCount(){return conflicts.size();}int independentDirectionCount(){return 0;}
        private String key(String fp,String fc,String tp,String tc){
            String a=CityPlanningReference.registryId(fp,fc),b=CityPlanningReference.registryId(tp,tc);if(a.isBlank()||b.isBlank()||a.equals(b))return "";
            List<String> ids=new ArrayList<>(List.of(a,b));Collections.sort(ids);return String.join(":",ids);
        }
        Map<String,Object> lookup(String fp,String fc,String tp,String tc){String key=key(fp,fc,tp,tc);Route route=routes.get(key);return valid()&&route!=null&&!conflicts.contains(key)?route.reference():Map.of();}
        private Map<String,Object> pending(String why){
            // No invented combined policy or affirmative reference on a routing failure.
            return Map.of("planning_pool_eligible",false,"reason",why,"reference",Map.of(),"strict_eligibility",false,"transport_verified",false,"rail_exclusion_complete",false,"air_fallback_trigger",false,"time_score_applicable",false);
        }
        Map<String,Object> select(String fp,String fc,String tp,String tc,int limit){
            if(!valid())return pending(reason);String key=key(fp,fc,tp,tc);if(conflicts.contains(key))return pending("context_pair_conflict");
            Route route=routes.get(key);return route==null?pending("city_pair_unobserved"):route.owner().select(fp,fc,tp,tc,limit);
        }
    }

    /** No automatic reads: the caller must supply every immutable pin AND opt in. */
    static synchronized Catalog open(Path file,String fileSha,String anchorSha,Pins pins,LocalDate today,OptIn optIn){
        boolean baseline=optIn==OptIn.CURRENT_BASELINE_PREBID_V2;
        if(!baseline&&optIn!=OptIn.CONTEXT_SHARED_PREBID_V1)return new Catalog(today,Map.of(),"context_policy_disabled",false);
        try{need(today!=null,"today_missing");Map<?,?> data=map(Json.parse(read(file,fileSha,2*1024*1024))),stored=map(Json.parse(read(Path.of(file+".integrity.json"),anchorSha,256*1024)));
            Validated valid=validate(data,evidence(pins,baseline));Map<String,Object> expected=anchor(data,valid);need(CityPlanningReference.same(expected,stored),"anchor_mismatch");
            String id=text(data,"library_id");if(rememberedPaths.containsKey(file))need(id.equals(rememberedPaths.get(file)),"path_identity_changed");
            if(remembered.containsKey(id))CityPlanningReference.continuation(remembered.get(id),data);
            remembered.put(id,expected);rememberedPaths.put(file,id);return new Catalog(today,valid.values(),"",baseline);
        }catch(Exception error){return new Catalog(today,Map.of(),Objects.toString(error.getMessage(),"context_library_invalid"),baseline);}
    }
    private ContextSharedPlanningAdapter(){}
}
