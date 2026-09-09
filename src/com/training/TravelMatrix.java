package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Evidence-based rail/air coverage. Unknown data is neither a long route nor no service. */
final class TravelMatrix {
    static final int RAIL_LIMIT=240, AIR_LIMIT=180;
    private static String fingerprint="";
    private static Map<?,?> cached=Map.of();
    static synchronized Map<?,?> bundle() {
        return bundle("","");
    }
    static synchronized Map<?,?> bundle(String province,String city) {
        try {
            String setting=System.getProperty("dispatch.transport.file",System.getenv().getOrDefault("YANXU_TRANSPORT_MATRIX_FILE","config/transport-matrix.local.json"));
            String directory=System.getProperty("dispatch.transport.dir",System.getenv().getOrDefault("YANXU_TRANSPORT_MATRIX_DIR",""));
            if(!directory.isBlank()&&RegionDirectory.known(province,city)) setting=Paths.get(directory,province+"-"+city+".json").toString();
            if(setting.isBlank()) return Map.of();
            Path path=Paths.get(setting);long size=Files.size(path);
            if(size>4*1024*1024) return Map.of();
            String stamp=path.toAbsolutePath()+":"+size+":"+Files.getLastModifiedTime(path);
            if(!stamp.equals(fingerprint)) {
                Object parsed=Json.parse(Files.readString(path));
                if(!(parsed instanceof Map<?,?>)||!"transport-matrix-v1".equals(((Map<?,?>)parsed).get("version"))) return Map.of();
                cached=(Map<?,?>)parsed;fingerprint=stamp;
            }
            return cached;
        } catch(Exception missing) { return Map.of(); }
    }
    static List<?> list(Map<?,?> data,String key) { return data.get(key) instanceof List<?>?(List<?>)data.get(key):List.of(); }
    static boolean matches(Map<?,?> r,String fp,String fc,String tp,String tc) {
        return fp.equals(r.get("from_province"))&&fc.equals(r.get("from_city"))&&tp.equals(r.get("to_province"))&&tc.equals(r.get("to_city"));
    }
    static boolean currentSource(Map<?,?> r,LocalDate today) {
        try {
            LocalDate observed=LocalDate.parse(RailTimetable.value(r,"observed_on")),service=LocalDate.parse(RailTimetable.value(r,"source_service_date"));
            java.net.URI url=java.net.URI.create(RailTimetable.value(r,"source_url"));
            return !observed.isAfter(today)&&!observed.plusDays(30).isBefore(today)&&!service.plusDays(30).isBefore(today)&&!service.isAfter(observed.plusDays(30))&&
                "https".equals(url.getScheme())&&url.getHost()!=null&&url.getUserInfo()==null;
        } catch(Exception invalid) { return false; }
    }
    // A complete provider result is required to prove the absence of a qualifying route.
    // A slow sampled train/flight, empty search result or failed request is not such proof.
    static Map<?,?> checked(Map<?,?> data,String mode,String fp,String fc,String tp,String tc,LocalDate today) {
        String scope=mode.equals("rail")?"all_city_stations_and_rail_itineraries":"all_city_airports_nonstop_services";
        Map<?,?> selected=null;
        for(Object raw:list(data,mode+"_checks")) if(raw instanceof Map<?,?>) {
            Map<?,?> r=(Map<?,?>)raw;
            if(RailTimetable.businessUseAllowed(data)&&RailTimetable.businessUseAllowed(r)&&matches(r,fp,fc,tp,tc)&&currentSource(r,today)&&Boolean.TRUE.equals(r.get("coverage_complete"))&&scope.equals(r.get("scope"))&&
                Boolean.TRUE.equals(r.get("usage_authorized"))) {
                try {
                    boolean absent=Boolean.TRUE.equals(r.get("no_service"));
                    if(!absent) RailTimetable.minute(r.get("minimum_minutes"));
                    if(selected!=null&&(absent!=Boolean.TRUE.equals(selected.get("no_service"))||!absent&&RailTimetable.minute(r.get("minimum_minutes"))!=RailTimetable.minute(selected.get("minimum_minutes")))) return null;
                    selected=r;
                }
                catch(Exception invalid) { /* Incomplete outcome remains unknown. */ }
            }
        }
        return selected;
    }
    static boolean beyond(Map<?,?> check,int limit) {
        return check!=null&&(Boolean.TRUE.equals(check.get("no_service"))||RailTimetable.minute(check.get("minimum_minutes"))>=limit);
    }
    static Map<String,Object> flight(Map<?,?> data,String fp,String fc,String tp,String tc,LocalDate today) {
        Map<String,Object> best=null;
        for(Object raw:list(data,"flights")) if(raw instanceof Map<?,?>) {
            Map<?,?> r=(Map<?,?>)raw;
            try {
                if(!RailTimetable.businessUseAllowed(data)||!RailTimetable.businessUseAllowed(r)||!matches(r,fp,fc,tp,tc)||!currentSource(r,today)||!Boolean.TRUE.equals(r.get("airport_city_mapping_checked"))||
                    !Boolean.TRUE.equals(r.get("usage_authorized"))||!Boolean.TRUE.equals(r.get("nonstop"))) continue;
                if(!"passenger".equals(r.get("service_type"))||!"air".equals(r.get("service_mode"))||!Boolean.FALSE.equals(r.get("cancelled"))||!"Asia/Shanghai".equals(r.get("timezone"))) continue;
                if(!(r.get("stops") instanceof Number)||((Number)r.get("stops")).doubleValue()!=0) continue;
                if(!RailTimetable.value(r,"flight_no").matches("[A-Z0-9]{2}\\d{3,4}")||
                    !RailTimetable.value(r,"from_airport_code").matches("[A-Z]{3}")||!RailTimetable.value(r,"to_airport_code").matches("[A-Z]{3}")||
                    r.get("from_airport_code").equals(r.get("to_airport_code"))) continue;
                String dep=RailTimetable.value(r,"departure_time"),arr=RailTimetable.value(r,"arrival_time");
                if(!dep.matches("\\d{2}:\\d{2}")||!arr.matches("\\d{2}:\\d{2}")||!(r.get("arrival_day_offset") instanceof Number)) continue;
                double offset=((Number)r.get("arrival_day_offset")).doubleValue();
                int minutes=RailTimetable.minute(r.get("flight_minutes"));
                if(offset<0||offset>2||offset!=(int)offset||LocalTime.parse(arr).toSecondOfDay()/60+(int)offset*1440-LocalTime.parse(dep).toSecondOfDay()/60!=minutes) continue;
                if(best!=null&&((Number)best.get("flight_minutes")).intValue()<=minutes) continue;
                best=new LinkedHashMap<>();
                for(String key:List.of("flight_no","from_airport_code","to_airport_code","departure_time","arrival_time","arrival_day_offset","flight_minutes","source_url","source_service_date","observed_on")) best.put(key,r.get(key));
            } catch(Exception invalid) { /* No estimated duration. */ }
        }
        return best;
    }
    static boolean conflicts(Map<?,?> check,Map<String,Object> witness) {
        return check!=null&&witness!=null&&(Boolean.TRUE.equals(check.get("no_service"))||RailTimetable.minute(check.get("minimum_minutes"))>((Number)witness.get("rail_minutes")).intValue());
    }
    static boolean flightConflicts(Map<?,?> check,Map<String,Object> witness) {
        return check!=null&&witness!=null&&(Boolean.TRUE.equals(check.get("no_service"))||RailTimetable.minute(check.get("minimum_minutes"))>((Number)witness.get("flight_minutes")).intValue());
    }
    static Map<String,Object> resolve(List<?> rails,Map<?,?> data,Map<String,Object> teacher,DispatchPreference pref,LocalDate today) {
        return resolve(rails,data,Map.of(),teacher,pref,today);
    }
    static Map<String,Object> resolve(List<?> rails,Map<?,?> data,Map<?,?> cityReferences,Map<String,Object> teacher,DispatchPreference pref,LocalDate today) {
        return CityTravelReference.apply(resolveRecent(rails,data,teacher,pref,today),rails,data,cityReferences,teacher,pref,today);
    }
    static Map<String,Object> resolve(List<?> rails,Map<?,?> data,Map<?,?> cityReferences,Map<?,?> publicReferences,Map<String,Object> teacher,DispatchPreference pref,LocalDate today) {
        // An unconfigured public source must preserve the original contract exactly.
        if(publicReferences.isEmpty())return resolve(rails,data,cityReferences,teacher,pref,today);
        Map<String,Object> recent=resolveRecent(rails,data,teacher,pref,today);
        if(!pref.localPreferenceActive()||NearbySelection.maximumNearbyMinutes()==0||
            "same_city".equals(recent.get("status"))||"not_required".equals(recent.get("status")))return recent;
        String fp=RailTimetable.value(teacher,"base_province"),fc=RailTimetable.value(teacher,"base_city");
        // Kept local to this cross-library resolver: individual library validation
        // and the legacy no-public path still have one owner in CityTravelReference.
        class Comparison {
            final List<Map<String,Object>> audit=new ArrayList<>();
            boolean eligible(Map<?,?> value) {return List.of("rail","air").contains(Objects.toString(value.get("eligibility"),""));}
            boolean forbidden(Map<?,?> row) {
                if(!RailTimetable.businessUseAllowed(row)||Boolean.TRUE.equals(row.get("cancelled")))return true;
                if(!PublicTransportEvidence.sourceStateAllowed(row))return true;
                for(String key:List.of("status","review_status","source_status","service_state"))
                    if(List.of("revoked","withdrawn","suspended","cancelled","rejected").contains(Objects.toString(row.get(key),"").toLowerCase(Locale.ROOT)))return true;
                return false;
            }
            boolean hardFailure(Map<?,?> bundle,Map<?,?> view) {
                if(bundle.isEmpty())return false;
                if(bundle.containsKey("load_status")||forbidden(bundle))return true;
                String reason=Objects.toString(view.get("review_reason"),"");
                if(reason.contains("hash")||reason.contains("integrity")||reason.contains("rollback")||reason.startsWith("duplicate_")||
                    reason.startsWith("broken_")||reason.equals("conflicting_reference_ids")||reason.equals("reference_minutes_not_supported")||
                    reason.equals("evidence_direction_mismatch")||reason.equals("evidence_mode_mismatch")||
                    reason.equals("reference_duration_bounds_not_supported")||reason.equals("city_declared_bounds_mismatch")||
                    reason.equals("typed_bounds_legacy_minutes_forbidden")||reason.equals("typed_duration_output_mismatch"))return true;
                try {
                    for(Map<?,?> event:CityTravelReference.heads(bundle).values()) {
                        Map<?,?> ref=CityTravelReference.map(event.get("snapshot"));
                        if(!CityTravelReference.paired(ref,fp,fc,pref.province,pref.city))continue;
                        if(forbidden(ref))return true;
                        List<Map<?,?>> links=new ArrayList<>();
                        for(String direction:List.of("outbound","return")) {
                            links.add(CityTravelReference.map(ref.get(direction)));
                            links.add(CityTravelReference.map(CityTravelReference.map(ref.get("rail_exclusion")).get(direction)));
                        }
                        for(Map<?,?> link:links) {
                            int matches=0;
                            for(Object raw:CityTravelReference.list(bundle.get("evidence_snapshots"))) {
                                Map<?,?> item=CityTravelReference.map(raw);
                                if(!link.containsKey("evidence_id")||!Objects.equals(link.get("evidence_id"),item.get("evidence_id")))continue;
                                if(++matches>1)return true;
                                Map<?,?> content=CityTravelReference.map(item.get("content"));
                                if(forbidden(content))return true;
                                // A pending review can legitimately lack a source, but cannot
                                // suppress a mismatch in a source digest already declared.
                                if(link.containsKey("source_sha256")||item.containsKey("sha256")) {
                                    String hash=Objects.toString(link.get("source_sha256"),"");
                                    if(!hash.matches("[a-f0-9]{64}")||!hash.equals(item.get("sha256"))||!hash.equals(CityTravelReference.sha256(content)))return true;
                                }
                            }
                        }
                    }
                } catch(Exception invalidIntegrity) {return true;}
                return false;
            }
            void retain(String source,Map<?,?> value) {
                if(!value.isEmpty())audit.add(new LinkedHashMap<>(Map.of("source",source,"reference",value)));
            }
            Map<String,Object> waiting(String reason) {
                Map<String,Object> result=new LinkedHashMap<>(CityTravelReference.reviewPending(Map.of(),reason));
                result.put("reference_comparison",audit);
                result.put("message","城市交通参考存在撤销、完整性问题或尚未解释的来源冲突，待核实后再推荐；保留各份资料，不自动选择较有利的时长。");return result;
            }
            LocalDate legDate(Map<?,?> reference,String direction) {
                Map<?,?> leg=CityTravelReference.map(reference.get(direction));
                // Rereading a January announcement in September is not a
                // September observation of transport service. Keep research_on
                // unchanged for audit; compare the announcement's original date.
                if("public_city_reference".equals(reference.get("reference_basis")))
                    return LocalDate.parse(Objects.toString(leg.get("source_published_on"),""));
                return LocalDate.parse(Objects.toString(leg.get("research_on"),Objects.toString(leg.get("observed_on"),"")));
            }
            Map<String,Object> chronology(Map<String,Object> reference) {
                if(!"public_city_reference".equals(reference.get("reference_basis")))return reference;
                Map<String,Object> dated=new LinkedHashMap<>(reference);
                for(String direction:List.of("outbound","return")) {
                    Map<String,Object> leg=new LinkedHashMap<>();CityTravelReference.map(reference.get(direction)).forEach((k,v)->leg.put(k.toString(),v));
                    leg.put("research_on",legDate(reference,direction).toString());dated.put(direction,leg);
                }
                List<Map<String,Object>> exclusions=new ArrayList<>();
                for(Object raw:CityTravelReference.list(reference.get("rail_exclusion"))) {
                    Map<?,?> proof=CityTravelReference.map(raw);Map<String,Object> datedProof=new LinkedHashMap<>();proof.forEach((k,v)->datedProof.put(k.toString(),v));
                    datedProof.put("research_on",LocalDate.parse(Objects.toString(proof.get("source_published_on"),"")).toString());exclusions.add(datedProof);
                }
                if(!exclusions.isEmpty())dated.put("rail_exclusion",exclusions);
                return dated;
            }
            boolean sameMinutes(Map<?,?> a,Map<?,?> b) {
                return CityTravelReference.sameDuration(CityTravelReference.routeDuration(a,"outbound"),CityTravelReference.routeDuration(b,"outbound"))&&
                    CityTravelReference.sameDuration(CityTravelReference.routeDuration(a,"return"),CityTravelReference.routeDuration(b,"return"));
            }
            boolean strictlyNewerPair(Map<?,?> a,Map<?,?> b) {
                try {
                    LocalDate ao=legDate(a,"outbound"),ar=legDate(a,"return"),bo=legDate(b,"outbound"),br=legDate(b,"return");
                    return !ao.isBefore(bo)&&!ar.isBefore(br)&&(ao.isAfter(bo)||ar.isAfter(br));
                } catch(Exception missingDate) {return false;}
            }
        }
        Comparison compare=new Comparison();
        Map<String,Object> reviewed=CityTravelReference.lookup(cityReferences,fp,fc,pref.province,pref.city,today);
        Map<String,Object> published=CityTravelReference.lookup(publicReferences,fp,fc,pref.province,pref.city,today);
        compare.retain("reviewed_city_library",reviewed);compare.retain("public_city_library",published);
        if(compare.hardFailure(cityReferences,reviewed)||compare.hardFailure(publicReferences,published))
            return compare.waiting("cross_library_revocation_or_integrity_failure");
        // A validated opposite threshold is a conflict, not an empty search and
        // not proof that no faster station/route exists. Never turn it into outside.
        boolean threshold=List.of(reviewed,published).stream().anyMatch(v->List.of("reference_not_within_limit","reference_bounds_do_not_prove_limit").contains(Objects.toString(v.get("review_reason"),"")));
        if(threshold&&(compare.eligible(reviewed)||compare.eligible(published)||compare.eligible(recent)))
            return compare.waiting("cross_library_threshold_conflict");
        List<Map<String,Object>> candidates=new ArrayList<>();
        if(compare.eligible(recent)) {
            Map<String,Object> chronology=CityTravelReference.apply(recent,rails,data,publicReferences,teacher,pref,today);
            compare.retain("recent_observations",chronology);
            if(!compare.eligible(chronology))return compare.waiting("newer_transport_evidence_requires_review");
            candidates.add(chronology);
        }
        boolean newerRaw=false;
        for(Map<String,Object> view:List.of(reviewed,published))if(compare.eligible(view)) {
            try {
                if(CityTravelReference.newerEvidence(rails,data,compare.chronology(view),fp,fc,pref.province,pref.city,today))newerRaw=true;
                else candidates.add(view);
            } catch(Exception missingPublicPublicationDate) {newerRaw=true;}
        }
        if(candidates.isEmpty()) {
            if(newerRaw)return compare.waiting("newer_transport_evidence_requires_review");
            if("outside".equals(recent.get("eligibility")))return recent;
            Map<String,Object> pending=!published.isEmpty()?published:!reviewed.isEmpty()?reviewed:recent;
            Map<String,Object> result=new LinkedHashMap<>(pending);result.put("reference_comparison",compare.audit);return result;
        }
        if("outside".equals(recent.get("eligibility"))||candidates.stream().map(v->v.get("eligibility")).distinct().count()>1)
            return compare.waiting("cross_library_transport_scope_conflict");
        // Choose an intact pair only when both directions are no older and at
        // least one is newer. Same-day differing values or crossed freshness are
        // unresolved. Equal values use stable recent/private/public order.
        Map<String,Object> chosen=null;
        for(Map<String,Object> candidate:candidates) {
            boolean explainsAll=true;
            for(Map<String,Object> other:candidates)if(!compare.sameMinutes(candidate,other)&&!compare.strictlyNewerPair(candidate,other)) {explainsAll=false;break;}
            if(explainsAll&&(chosen==null||compare.strictlyNewerPair(candidate,chosen)))chosen=candidate;
        }
        if(chosen==null)return compare.waiting("cross_library_same_date_or_directional_conflict");
        Map<String,Object> result=new LinkedHashMap<>(chosen);result.put("reference_comparison",compare.audit);
        result.put("reference_resolution",candidates.size()==1?"only_qualifying_reference":"newest_complete_pair_or_equal_values_stable_source_order");
        result.put("reference_comparison_date_basis","public_source_published_on; reviewed_research_on; actual_observation_observed_on");
        return result;
    }
    private static Map<String,Object> resolveRecent(List<?> rails,Map<?,?> data,Map<String,Object> teacher,DispatchPreference pref,LocalDate today) {
        if(RailTimetable.businessUseAllowed(data)&&!list(data,"rail_directions").isEmpty()) { List<Object> combined=new ArrayList<>(rails);combined.addAll(list(data,"rail_directions"));rails=combined; }
        Map<String,Object> rail=RailTimetable.lookup(rails,teacher,pref,today);
        if("not_required".equals(rail.get("status"))||"same_city".equals(rail.get("status"))) return rail;
        int maximum=NearbySelection.maximumNearbyMinutes();
        if(maximum==0) return pending(rail,"管理员已暂停跨城自动推荐（包括航空）；交通资料不因此视为已核对");
        String fp=RailTimetable.value(teacher,"base_province"),fc=RailTimetable.value(teacher,"base_city");
        if(!RegionDirectory.known(fp,fc)||!RegionDirectory.known(pref.province,pref.city)) return pending(rail,"省市名称待核对，不推断交通范围");
        if("rail_time_reference".equals(rail.get("status"))&&Math.max(RailTimetable.minute(rail.get("outbound_reference_minutes")),RailTimetable.minute(rail.get("return_reference_minutes")))<RAIL_LIMIT) {
            if(Math.max(RailTimetable.minute(rail.get("outbound_reference_minutes")),RailTimetable.minute(rail.get("return_reference_minutes")))>=maximum) return pending(rail,"已知铁路参考超过管理员设置的当前比较范围，不等于铁路超过4小时，也不据此启用航空");
            Map<String,Object> result=new LinkedHashMap<>(rail);result.put("eligibility","rail");return result;
        }
        Map<?,?> outCheck=checked(data,"rail",fp,fc,pref.province,pref.city,today),backCheck=checked(data,"rail",pref.province,pref.city,fp,fc,today);
        if(outCheck==null||backCheck==null||Boolean.TRUE.equals(outCheck.get("no_service"))||Boolean.TRUE.equals(backCheck.get("no_service"))||!beyond(outCheck,RAIL_LIMIT)&&!beyond(backCheck,RAIL_LIMIT)||
            conflicts(outCheck,RailTimetable.direction(rails,fp,fc,pref.province,pref.city,today))||conflicts(backCheck,RailTimetable.direction(rails,pref.province,pref.city,fp,fc,today)))
            return pending(rail,"铁路范围尚未完整核对；慢车样本、未查到或旧数据都不能证明高铁超出4小时，暂不自动转航空");
        Map<String,Object> out=flight(data,fp,fc,pref.province,pref.city,today),back=flight(data,pref.province,pref.city,fp,fc,today);
        if(out!=null&&back!=null&&Math.max(((Number)out.get("flight_minutes")).intValue(),((Number)back.get("flight_minutes")).intValue())<AIR_LIMIT) {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("status","air_time_reference");result.put("eligibility","air");result.put("outbound",out);result.put("return",back);
            result.put("outbound_reference_minutes",out.get("flight_minutes"));result.put("return_reference_minutes",back.get("flight_minutes"));
            result.put("rail_check_sources",List.of(outCheck.get("source_url"),backCheck.get("source_url")));
            result.put("transport_verified",false);result.put("travel_date_verified",false);
            result.put("message","铁路完整核对未满足4小时条件；直飞计划航段小于3小时，不含机场接驳、值机安检，也不保证授课日开行或按时到场。");return result;
        }
        Map<?,?> outAir=checked(data,"air",fp,fc,pref.province,pref.city,today),backAir=checked(data,"air",pref.province,pref.city,fp,fc,today);
        if(outAir!=null&&backAir!=null&&!flightConflicts(outAir,out)&&!flightConflicts(backAir,back)&&(beyond(outAir,AIR_LIMIT)||beyond(backAir,AIR_LIMIT)))
            return Map.of("status","outside_transport_limits","eligibility","outside","message","已完整核对的铁路与直飞时长不满足当前范围；不是无交通可达的结论");
        return pending(rail,"铁路已完整核对，不满足4小时条件；尚缺完整、近期的双向直飞3小时内依据");
    }
    static Map<String,Object> pending(Map<String,Object> rail,String message) {
        Map<String,Object> result=new LinkedHashMap<>(rail);result.put("eligibility","unknown");result.put("message",message);return result;
    }
    private TravelMatrix() {}
}
