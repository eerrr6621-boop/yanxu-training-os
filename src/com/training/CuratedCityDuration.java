package com.training;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Pure, conditional duration arithmetic AFTER the existing protected envelope validation.
 * Reader-selected roles/scope are trusted semantic annotations, not NLP proofs or identity
 * authentication. This class loads nothing; CityPlanningReference supplies the protected envelope.
 * A raw Map pair is NEVER evidence that the enclosing source/reviews/version were verified. */
final class CuratedCityDuration {
    static final String METHOD="dual_review_curated_bounded_fact_v1";
    static boolean method(Object value){return METHOD.equals(value)||PublicUiCitySample.method(value);}
    static final int BUFFER=30, LIMIT=240;
    record Direction(Map<?,?> source,Map<?,?> readA,Map<?,?> readB,Map<?,?> from,Map<?,?> to,LocalDate reviewedOn){}
    record Span(String unit,int start,int end,String text){}
    record Duration(int value,String kind,Map<?,?> raw){}
    private static final Set<String> ACTIVE=Set.of("reported_operating","regular_diagram_reference","retrospective_rail_reference");
    private static final Set<String> CORE=Set.of("verification_method","source_id","from_registry_id","to_registry_id",
        "from_endpoint","to_endpoint","endpoint_scope","mapping_basis","transport_mode","route_scope",
        "published_on","effective_on","effective_until","service_state","status","duration");
    private static final String NUM="(?:(?:[0-9]+)小时)?(?:(?:[0-9]+)分(?:钟)?)?";
    private static final Pattern CLOCK=Pattern.compile("[0-9]{1,2}[:：∶][0-9]{2}");
    private static final Pattern SERVICE=Pattern.compile("[GDCZS][0-9]{1,5}(?:[/／][0-9]{1,5})?");
    private static void need(boolean b,String reason){CityPlanningReference.need(b,reason);}
    private static Map<?,?> map(Object x){return CityPlanningReference.map(x);}
    private static String text(Map<?,?> x,String k){return CityPlanningReference.text(x,k);}
    private static int number(Object x){return CityPlanningReference.number(x);}
    private static LocalDate day(Object x){return CityPlanningReference.day(x);}
    private static void keys(Map<?,?> x,Set<String> expected){need(x.keySet().equals(expected),"curated_fields_missing_or_unknown");}
    private static Map<String,Object> m(Object...items){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<items.length;i+=2)r.put((String)items[i],items[i+1]);return r;}
    private static boolean bool(Map<?,?> x,String key){need(x.get(key) instanceof Boolean,"curated_boundary_missing");return (Boolean)x.get(key);}
    private static Object immutable(Object x){if(x instanceof Map<?,?> v){Map<Object,Object> c=new LinkedHashMap<>();v.forEach((k,value)->c.put(k,immutable(value)));return Collections.unmodifiableMap(c);}if(x instanceof List<?> v){List<Object> c=new ArrayList<>();v.forEach(value->c.add(immutable(value)));return Collections.unmodifiableList(c);}return x;}
    @SuppressWarnings("unchecked") private static Map<String,Object> frozen(Map<String,Object> x){return (Map<String,Object>)immutable(x);}

    /** Single role token/segment, with reversible UTF-16 offsets in the unchanged source unit. */
    static Span span(Map<?,?> fact,Map<String,String> units,String role){
        List<?> rows=CityPlanningReference.list(map(fact.get("spans")).get(role));need(rows.size()==1,"curated_single_"+role+"_span_required");
        Map<?,?> s=map(rows.get(0));String unit=text(s,"unit_id"),raw=units.get(unit);int a=number(s.get("start")),b=number(s.get("end"));
        need(raw!=null&&a<b&&b<=raw.length()&&raw.substring(a,b).equals(s.get("text")),"curated_span_mismatch");return new Span(unit,a,b,(String)s.get("text"));
    }
    static void inside(Span token,Span segment){need(token.unit.equals(segment.unit)&&token.start>=segment.start&&token.end<=segment.end,"curated_cross_segment_binding");}
    private static int literal(String token){
        Matcher p=Pattern.compile("^(?:([0-9]+)小时)?(?:([0-9]+)分(?:钟)?)?$").matcher(token);
        need(p.matches()&&(p.group(1)!=null||p.group(2)!=null),"curated_duration_token_invalid");
        long value=(p.group(1)==null?0:Long.parseLong(p.group(1))*60L)+(p.group(2)==null?0:Long.parseLong(p.group(2)));
        need(value>0&&value<100000,"curated_duration_value_invalid");return (int)value;
    }
    /** A bounded notation, not general Chinese-number interpretation. Keep half-hours
     * as nominal units; never relabel colloquial summaries as measured minutes. */
    private static int halfHours(String token){
        if(Set.of("半小时","半个小时").contains(token))return 1;
        Matcher match=Pattern.compile("^([1-9一二两三四五六七八九])(?:个半小时|小时半)$").matcher(token);
        need(match.matches(),"curated_half_hour_token_invalid");
        char digit=match.group(1).charAt(0);
        int hours=digit>='1'&&digit<='9'?digit-'0':switch(digit){case '一'->1;case '二','两'->2;case '三'->3;case '四'->4;case '五'->5;case '六'->6;case '七'->7;case '八'->8;case '九'->9;default->throw new IllegalArgumentException("curated_half_hour_token_invalid");};
        return hours*2+1;
    }
    private static void fullHalfHourToken(Span selected,Map<String,String> units){
        String raw=units.get(selected.unit),numeralOrUnit="0123456789零〇一二两三四五六七八九十百千万亿个半小时分点又多";
        for(int direction:new int[]{-1,1}){
            int edge=direction<0?selected.start-1:selected.end,spaces=0;
            while(edge>=0&&edge<raw.length()&&(Character.isWhitespace(raw.charAt(edge))||Character.isSpaceChar(raw.charAt(edge)))){
                need(++spaces<=32,"curated_incomplete_half_hour_token");edge+=direction;
            }
            if(edge<0||edge>=raw.length())continue;
            char neighbour=raw.charAt(edge);
            need(numeralOrUnit.indexOf(neighbour)<0,"curated_incomplete_half_hour_token");
            // Qualifiers separated by Unicode whitespace must not become invisible.
            need(direction<0?"约于过到足少多".indexOf(neighbour)<0:"左上以内下至到—–~～-".indexOf(neighbour)<0,
                "curated_qualifier_outside_selected_token");
        }
    }
    /** Local qualifier affinity only, not interpretation of a sentence's meaning. */
    private static void fullQualifier(Span s,Map<String,String> units){
        String raw=units.get(s.unit),before=raw.substring(Math.max(0,s.start-8),s.start),after=raw.substring(s.end,Math.min(raw.length(),s.end+4));
        need(!before.matches("(?s).*(?:约|大约|约为|大约为|不到|少于|低于|不足|不超过|至多|最多|至少|超过|大于)\\s*$")
            &&!after.matches("^(?:左右|上下|以内|以下|以上|至|到|—|–|~|～|-).*$"),"curated_qualifier_outside_selected_token");
    }
    private static int clock(String token){need(CLOCK.matcher(token).matches(),"curated_clock_token_invalid");String[] p=token.split("[:：∶]");int h=Integer.parseInt(p[0]),m=Integer.parseInt(p[1]);need(h<24&&m<60,"curated_clock_invalid");return h*60+m;}

    /** A complete station clock token may list arrival/departure as HH:mm/mm
     * or HH:mm/HH:mm. Roles are still annotated by the protected source readers;
     * this only checks the local notation and never invents an hour/day rollover. */
    private static int sourceClock(Span selected,Map<String,String> units,boolean departure){
        String raw=units.get(selected.unit),token=selected.text;
        String clockChars="0123456789:：∶/／";
        need((selected.start==0||clockChars.indexOf(raw.charAt(selected.start-1))<0)
            &&(selected.end==raw.length()||clockChars.indexOf(raw.charAt(selected.end))<0),"curated_incomplete_clock_token");
        // Reject a clipped half even when whitespace hides its adjacent slash.
        // This bounded neighbour check does not accept new clock syntax or alter source.
        for(int direction:new int[]{-1,1}){
            int edge=direction<0?selected.start-1:selected.end,spaces=0;
            while(edge>=0&&edge<raw.length()&&(Character.isWhitespace(raw.charAt(edge))||Character.isSpaceChar(raw.charAt(edge)))){
                need(++spaces<=32,"curated_incomplete_clock_token"); // Longer unexamined padding cannot bypass the boundary.
                edge+=direction;
            }
            need(edge<0||edge>=raw.length()||"/／".indexOf(raw.charAt(edge))<0,"curated_incomplete_clock_token");
        }
        String[] pair=token.split("[/／]",-1);
        if(pair.length==1)return clock(token);
        need(pair.length==2,"curated_clock_pair_invalid");
        int arrival=clock(pair[0]),leave;
        if(pair[1].matches("[0-9]{2}")){
            int minute=Integer.parseInt(pair[1]);need(minute<60,"curated_clock_invalid");
            leave=(arrival/60)*60+minute;
        }else leave=clock(pair[1]);
        need(leave>=arrival,"curated_clock_pair_rollover_not_explicit");
        return departure?leave:arrival;
    }

    private static Duration duration(Map<?,?> fact,Map<?,?> c,Map<String,String> units,Map<?,?> source){
        Map<?,?> d=map(c.get("duration"));String kind=text(d,"kind");int value;
        if("same_service_clocks".equals(kind)){
            keys(d,Set.of("kind","service_id","departure_clock","arrival_clock","arrival_day_offset","calculated_minutes"));
            need("station_service_pair".equals(c.get("route_scope")),"curated_clock_scope_invalid");
            if(PublicUiStopTable.method(c.get("verification_method")))return new Duration(PublicUiStopTable.duration(source,fact,c),kind,d);
            if(PublicUiPopupTable.method(c.get("verification_method")))return new Duration(PublicUiPopupTable.duration(source,fact,c),kind,d);
            Span segment=span(fact,units,"segment"),from=span(fact,units,"from"),to=span(fact,units,"to"),service=span(fact,units,"service"),dep=span(fact,units,"departure"),arr=span(fact,units,"arrival");
            for(Span token:List.of(from,to,service,dep,arr))inside(token,segment);
            need(SERVICE.matcher(text(d,"service_id")).matches()&&service.text.equals(d.get("service_id")),"curated_service_token_mismatch");
            Matcher services=SERVICE.matcher(segment.text);int count=0;while(services.find()){count++;need(services.group().equals(service.text),"curated_multiple_services_in_segment");}need(count==1,"curated_unique_service_required");
            need(dep.start<arr.start&&from.start<to.start,"curated_role_source_order_invalid");
            int departure=sourceClock(dep,units,true),arrival=sourceClock(arr,units,false);
            need(departure==clock(text(d,"departure_clock"))&&arrival==clock(text(d,"arrival_clock")),"curated_clock_source_mismatch");
            int offset=number(d.get("arrival_day_offset"));need(offset<=1,"curated_day_offset_unsupported");
            List<?> daySpans=CityPlanningReference.list(map(fact.get("spans")).get("arrival_day"));
            if(offset==1){Span day=span(fact,units,"arrival_day");inside(day,segment);need(Set.of("次日","翌日","第二天").contains(day.text)&&day.start>=dep.end&&day.end<=arr.start,"curated_explicit_next_day_required");}
            else{need(daySpans.isEmpty()&&!Pattern.compile("次日|翌日|第二天|跨日|跨夜|\\+[1-9]天").matcher(segment.text).find(),"curated_unrecorded_day_offset");}
            value=arrival+1440*offset-departure;need(value>0&&value==number(d.get("calculated_minutes")),"curated_clock_duration_mismatch");
        }else return scalarDuration(fact,d,units);
        return new Duration(value,kind,d);
    }

    /** Source-token arithmetic only: no directional or envelope interpretation. */
    static Duration scalarDuration(Map<?,?> fact,Map<?,?> d,Map<String,String> units){
        String kind=text(d,"kind");int value;
            Span selected=span(fact,units,"duration");String token=text(d,"token");need(selected.text.equals(token),"curated_duration_span_not_full_token");fullQualifier(selected,units);
            for(String role:List.of("departure","arrival","arrival_day","service","segment"))need(CityPlanningReference.list(map(fact.get("spans")).get(role)).isEmpty(),"curated_scalar_clock_role_mixed");
            switch(kind){
                case "reported_minutes" -> {keys(d,Set.of("kind","value","unit","token"));need("minute".equals(d.get("unit"))&&token.contains("分"),"curated_reported_precision_invalid");value=literal(token);need(value==number(d.get("value")),"curated_reported_value_mismatch");}
                case "nominal_hour" -> {keys(d,Set.of("kind","value","unit","token"));need("hour".equals(d.get("unit"))&&token.matches("[0-9]+小时"),"curated_nominal_precision_invalid");value=literal(token);need(value==60L*number(d.get("value")),"curated_nominal_value_mismatch");}
                case "nominal_half_hour" -> {keys(d,Set.of("kind","value","unit","token"));need("half_hour".equals(d.get("unit")),"curated_half_hour_unit_invalid");fullHalfHourToken(selected,units);int halves=halfHours(token);need(halves==number(d.get("value")),"curated_half_hour_value_mismatch");value=halves*30;}
                case "approximate" -> {keys(d,Set.of("kind","value","unit","token"));need("minute".equals(d.get("unit")),"curated_approximate_unit_invalid");String clean=token.replaceFirst("^(?:大约|约)","").replaceFirst("(?:左右|上下)$","");need(!clean.equals(token),"curated_approximate_marker_missing");if(clean.contains("半")){fullHalfHourToken(selected,units);value=halfHours(clean)*30;}else value=literal(clean);need(value==number(d.get("value")),"curated_approximate_value_mismatch");}
                case "upper_bound" -> {keys(d,Set.of("kind","upper","unit","upper_inclusive","token"));need("minute".equals(d.get("unit")),"curated_bound_unit_invalid");Matcher p=Pattern.compile("^(?<prefix>不到|少于|低于|不足|不超过|至多|最多)?(?<value>"+NUM+")(?<suffix>以内|以下)?$").matcher(token);need(p.matches(),"curated_upper_token_invalid");String prefix=p.group("prefix"),suffix=p.group("suffix");need((prefix==null)!=(suffix==null),"curated_upper_operator_ambiguous");boolean inclusive=suffix!=null||Set.of("不超过","至多","最多").contains(Objects.toString(prefix,""));need(bool(d,"upper_inclusive")==inclusive,"curated_upper_boundary_mismatch");value=literal(p.group("value"));need(value==number(d.get("upper")),"curated_upper_value_mismatch");}
                case "bounded_range" -> {keys(d,Set.of("kind","lower","upper","unit","lower_inclusive","upper_inclusive","token"));need("minute".equals(d.get("unit")),"curated_range_unit_invalid");Matcher p=Pattern.compile("^(?<lp>大于|超过|不少于|至少)?(?<lower>"+NUM+")(?:至|到|~|～|—|–|-)(?<up>小于|不足|不超过|至多)?(?<upper>"+NUM+")$").matcher(token);need(p.matches(),"curated_range_token_invalid");int lower=literal(p.group("lower"));value=literal(p.group("upper"));boolean li=!Set.of("大于","超过").contains(Objects.toString(p.group("lp"),"")),ui=!Set.of("小于","不足").contains(Objects.toString(p.group("up"),""));need(bool(d,"lower_inclusive")==li&&bool(d,"upper_inclusive")==ui,"curated_range_boundary_mismatch");need(lower<value&&lower==number(d.get("lower"))&&value==number(d.get("upper")),"curated_range_value_mismatch");}
                default -> throw new IllegalArgumentException("curated_duration_kind_unsupported");
            }
        return new Duration(value,kind,d);
    }

    /** Historical structural validation only. No clock/date-of-use decision: expired original
     * facts must remain verifiable when an outer revision later revokes the reference. */
    static Map<String,Object> directionAfterEnvelopeValidation(Direction d){
        need(d!=null&&d.reviewedOn!=null,"curated_context_missing");CityPlanningReference.city(d.from);CityPlanningReference.city(d.to);
        need(!d.from.get("id").equals(d.to.get("id")),"curated_same_city_not_cross_city");
        CityPlanningReference.originalRestrictions(d.source);CityPlanningReference.url(text(d.source,"url"));
        Map<?,?> a=map(d.readA.get("canonical")),b=map(d.readB.get("canonical"));boolean ui=PublicUiCitySample.method(a.get("verification_method"));
        Set<String> fields=new HashSet<>(CORE);if(ui)fields.add("date_basis");keys(a,fields);keys(b,fields);
        need(ui==PublicUiCitySample.source(d.source),"curated_source_method_mismatch");
        LocalDate published=ui?PublicUiCitySample.referenceDate(d.source):day(d.source.get("published_on"));need(!d.reviewedOn.isBefore(published),"curated_review_before_publication");
        LocalDate due=published.plusDays(CityPlanningReference.SOURCE_DAYS);if(d.reviewedOn.plusDays(CityPlanningReference.REVIEW_DAYS).isBefore(due))due=d.reviewedOn.plusDays(CityPlanningReference.REVIEW_DAYS);
        if(ui)due=PublicUiCitySample.reviewDueDate(d.source,d.reviewedOn);
        need(CityPlanningReference.same(a,b),"curated_core_read_disagreement");
        need(method(a.get("verification_method")),"curated_verification_method_mismatch");
        need(text(d.source,"source_id").equals(a.get("source_id"))&&!text(d.source,"source_id").isBlank(),"curated_source_id_mismatch");
        need("rail".equals(a.get("transport_mode"))&&Set.of("city_summary","station_corridor","station_service_pair").contains(text(a,"route_scope")),"curated_mode_or_scope_unsupported");
        need("active".equals(a.get("status"))&&(ui?"observed_public_ui_service_sample".equals(a.get("service_state")):ACTIVE.contains(text(a,"service_state"))),"curated_not_active_operating_reference");
        need(ui?a.get("published_on")==null:published.equals(day(a.get("published_on"))),"curated_source_date_mismatch");
        if(a.get("effective_on")!=null)day(a.get("effective_on"));
        if("regular_diagram_reference".equals(a.get("service_state")))need(a.get("effective_on")!=null,"curated_diagram_effective_date_missing");
        if(a.get("effective_until")!=null){LocalDate end=day(a.get("effective_until"));need(a.get("effective_on")!=null&&!end.isBefore(day(a.get("effective_on"))),"curated_effective_period_ended_or_invalid");}
        Map<?,?> scopes=map(a.get("endpoint_scope"));keys(scopes,Set.of("from","to"));
        need("literal_city_prefix".equals(a.get("mapping_basis")),"curated_external_station_mapping_not_supported");
        Map<String,String> units=CityPlanningReference.units(d.source);Duration value=null;
        if(ui)PublicUiCitySample.validate(d,a,units);
        for(Map<?,?> fact:List.of(d.readA,d.readB)){
            need(text(d.from,"city").equals(fact.get("from_city"))&&text(d.to,"city").equals(fact.get("to_city")),"curated_fact_direction_mismatch");
            need(!text(fact,"rationale").isBlank(),"curated_reader_rationale_missing");span(fact,units,"context");
            for(String side:List.of("from","to")){
                Map<?,?> city=side.equals("from")?d.from:d.to;String name=text(city,"city"),endpoint=text(a,side+"_endpoint"),scope=text(scopes,side);
                need(text(city,"id").equals(a.get(side+"_registry_id")),"curated_registry_direction_mismatch");
                // Scope is an agreeing source-reader annotation, not a deduction from
                // a station name. A documented urban subcentre stays separately typed.
                need(ui?"named_city_station".equals(scope):Set.of("city_summary","main_urban_station","urban_subcentre_station").contains(scope),"curated_county_or_unknown_endpoint_scope");
                // Only the exact UI source/method validated above may retain a station
                // label equal to its city (e.g. 济南). Do not invent a suffix or infer scope.
                boolean sameNameUiStation=ui&&"named_city_station".equals(scope)&&endpoint.equals(name);
                need("city_summary".equals(scope)?endpoint.equals(name):
                    sameNameUiStation||(endpoint.startsWith(name)&&endpoint.length()>name.length()),"curated_nonliteral_or_mismatched_endpoint");
                need(span(fact,units,side).text.equals(endpoint),"curated_endpoint_span_mismatch");
            }
            Duration current=duration(fact,a,units,d.source);need(value==null||value.value==current.value,"curated_recomputed_read_disagreement");value=current;
        }
        Map<String,Object> result=m("from",d.from,"to",d.to,"from_endpoint",a.get("from_endpoint"),"to_endpoint",a.get("to_endpoint"),"endpoint_scope",scopes,
            "source_url",d.source.get("url"),"source_published_on",ui?null:published.toString(),"review_due_on",due.toString(),"reviewed_on",d.reviewedOn.toString(),"precision",value.kind,
            "scope",a.get("route_scope"),"service_state",a.get("service_state"),"effective_on",a.get("effective_on"),"effective_until",a.get("effective_until"),"verification_method",a.get("verification_method"),
            "source_duration",value.raw,"reference_value_minutes_internal",value.value,"policy_adjusted_minutes_internal",value.value+BUFFER,
            "policy_value_is_proven_travel_upper_bound",false,"semantic_basis","two_agreeing_review_annotations_not_machine_language_proof");
        if(ui)PublicUiCitySample.decorate(result,a,d.source);return frozen(result);
    }

    private static Map<String,Object> direction(Direction d,LocalDate today){
        need(d!=null&&d.reviewedOn!=null&&today!=null,"curated_context_missing");
        LocalDate published=PublicUiCitySample.method(map(d.readA.get("canonical")).get("verification_method"))?PublicUiCitySample.referenceDate(d.source):day(d.source.get("published_on"));need(!published.isAfter(today)&&!d.reviewedOn.isAfter(today),"curated_future_source_or_review");
        LocalDate due=published.plusDays(CityPlanningReference.SOURCE_DAYS);if(d.reviewedOn.plusDays(CityPlanningReference.REVIEW_DAYS).isBefore(due))due=d.reviewedOn.plusDays(CityPlanningReference.REVIEW_DAYS);
        if(PublicUiCitySample.method(map(d.readA.get("canonical")).get("verification_method")))due=PublicUiCitySample.reviewDueDate(d.source,d.reviewedOn);
        need(!today.isAfter(due)&&!d.reviewedOn.isBefore(published),"curated_source_or_review_stale");
        Map<String,Object> value=directionAfterEnvelopeValidation(d);
        if(!PublicUiCitySample.separateDates(value)&&value.get("effective_on")!=null)need(!day(value.get("effective_on")).isAfter(today),"curated_not_yet_effective");
        if(value.get("effective_until")!=null)need(!today.isAfter(day(value.get("effective_until"))),"curated_effective_period_ended_or_invalid");
        return value;
    }

    /** Notices only, after two directional validations. This never grants admission. */
    static Map<String,Object> endpointNoticesAfterValidation(Map<?,?> out,Map<?,?> back){
        need(CityPlanningReference.same(out.get("from"),back.get("to"))&&CityPlanningReference.same(out.get("to"),back.get("from")),"curated_two_directions_not_inverse");
        List<Map<String,Object>> notices=new ArrayList<>();boolean specificityMixed=false;
        for(String side:List.of("from","to")){
            String opposite=side.equals("from")?"to":"from";
            boolean different=!Objects.equals(out.get(side+"_endpoint"),back.get(opposite+"_endpoint"));
            boolean mixed=!Objects.equals(map(out.get("endpoint_scope")).get(side),map(back.get("endpoint_scope")).get(opposite));
            specificityMixed|=mixed;
            if(different||mixed)notices.add(m("city",out.get(side),"outbound_role",side,"inbound_role",opposite,
                "outbound_endpoint",out.get(side+"_endpoint"),"inbound_endpoint",back.get(opposite+"_endpoint"),
                "endpoint_difference",different,"endpoint_specificity_mixed",mixed,"transfer_time_estimated",false));
        }
        return frozen(m("endpoint_difference",!notices.isEmpty(),"endpoint_specificity_mixed",specificityMixed,"endpoint_notices",notices));
    }

    /** Caller MUST first validate existing source/review/manifest/integrity/version/revocation
     * envelope. This function is not a substitute or a new self-signed admission endpoint.
     * Independent output status intentionally cannot be consumed as existing planning admission. */
    static Map<String,Object> pairAfterEnvelopeValidation(Direction outbound,Direction inbound,LocalDate today){
        Map<String,Object> result=m("status","curated_planning_pending","planning_state","planning_pending","purpose","prebid_city_planning_only",
            "verification_method",METHOD,"requires_validated_envelope",true,"envelope_verified_by_this_component",false,
            "policy_buffer_minutes",BUFFER,"buffer_applied_to_selection",false,"buffer_is_connection_or_door_to_door_time",false,"transport_verified",false,
            "time_score_applicable",false,"air_fallback_trigger",false,"rail_exclusion_complete",false,"strict_eligibility",false);
        try{
            Map<String,Object> out=direction(outbound,today),back=direction(inbound,today);
            need(Objects.equals(out.get("verification_method"),back.get("verification_method")),"curated_mixed_direction_methods_pending");
            result.put("verification_method",out.get("verification_method"));
            need(CityPlanningReference.same(outbound.from,inbound.to)&&CityPlanningReference.same(outbound.to,inbound.from),"curated_two_directions_not_inverse");
            // Each direction already passed independent city/scope/span checks. Different
            // validated urban stations are useful city references, not a transfer itinerary.
            result.putAll(endpointNoticesAfterValidation(out,back));
            result.put("outbound",out);result.put("inbound",back);
            Map<String,Object> selection=CityPlanningReference.selectionReference(out,back);
            result.put("selection_reference",selection);
            if(!CityPlanningReference.selectionUnder(selection,LIMIT)){result.put("reason","curated_reference_boundary_pending");return frozen(result);}
            result.put("status","curated_planning_values");result.put("planning_state","planning_reference");
            result.put("planning_band_id",CityPlanningReference.selectionBandId(selection));
            result.put("reason","two_direction_city_reference_values");
        }catch(RuntimeException error){result.put("reason",Objects.toString(error.getMessage(),"curated_invalid_input"));}
        return frozen(result);
    }
    private CuratedCityDuration(){}
}
