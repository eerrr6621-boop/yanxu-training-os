package com.training;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Offline nationwide evidence-coverage audit. No teacher, organization, HTTP or database input.
 * Compile outside the application output. The production resolver alone decides eligibility.
 * Enumerating a pending route does NOT mean its timetable has been researched.
 */
public final class TransportCoverageAudit {
    record City(String province, String city) {
        String key() { return province + "/" + city; }
        Map<String,Object> json() { return m("province", province, "city", city); }
    }
    record Edge(City from, City to) {}
    static final class Inputs {
        final List<Object> rails = new ArrayList<>();
        final Map<String,List<Object>> transport = new LinkedHashMap<>();
        final Map<Edge,List<Object>> railIndex = new HashMap<>();
        final Map<String,Map<Edge,List<Object>>> transportIndex = new LinkedHashMap<>();
        final List<Map<String,Object>> sources = new ArrayList<>();
        final Set<String> seenRails = new HashSet<>();
        int ignoredUnknownCityRows, duplicateRailRows;
        Inputs() {
            for (String mode : List.of("rail_checks", "air_checks", "flights")) {
                transport.put(mode, new ArrayList<>()); transportIndex.put(mode, new HashMap<>());
            }
        }
        void rail(Object row) {
            if (!(row instanceof Map<?,?> map)) throw new IllegalArgumentException("Rail row must be an object");
            Edge edge = edge(map);
            if (edge == null) { ignoredUnknownCityRows++; return; }
            if (!seenRails.add(Json.write(row))) { duplicateRailRows++; return; }
            rails.add(row); railIndex.computeIfAbsent(edge, k -> new ArrayList<>()).add(row);
        }
        void transport(String type, Object row) {
            if (!(row instanceof Map<?,?> map)) throw new IllegalArgumentException("Transport row must be an object");
            Edge edge = edge(map);
            if (edge == null) { ignoredUnknownCityRows++; return; }
            transport.get(type).add(row); transportIndex.get(type).computeIfAbsent(edge,k -> new ArrayList<>()).add(row);
        }
        void read(Path path, boolean matrix, boolean research) throws Exception {
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) throw new IllegalArgumentException("Expected regular non-symlink input: " + path);
            if (Files.size(path) > 16L*1024*1024) throw new IllegalArgumentException("Audit input exceeds 16 MiB limit");
            byte[] bytes = Files.readAllBytes(path);
            Object parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?,?> data)) throw new IllegalArgumentException("Input root must be an object");
            String version = Objects.toString(data.get("version"), "");
            if (matrix) {
                if (!"transport-matrix-v1".equals(version)) throw new IllegalArgumentException("Unsupported transport version");
                for (Object row : TravelMatrix.list(data,"rail_directions")) rail(row);
                for (String type : transport.keySet()) for (Object row : TravelMatrix.list(data,type)) transport(type,row);
            } else {
                if (!("rail-timetable-v1".equals(version) || research && version.startsWith("transport-") && version.contains("research")))
                    throw new IllegalArgumentException("Unsupported rail/research version: " + version);
                boolean found=false;
                if (data.get("directions") instanceof List<?> rows) { for (Object row : rows) rail(row); found=true; }
                if (research && data.get("cities") instanceof List<?> cityRows) {
                    for (Object rawCity : cityRows) {
                        if (!(rawCity instanceof Map<?,?> city)) throw new IllegalArgumentException("Research city must be an object");
                        if (city.get("directions") instanceof List<?> rows) for (Object row : rows) rail(row);
                    }
                    found=true;
                }
                if (!found) throw new IllegalArgumentException("Missing directions array or research cities[].directions");
            }
            sources.add(m("file",path.getFileName().toString(),"kind",matrix?"transport":research?"research_batch":"rail_table",
                "sha256",sha(bytes),"bytes",bytes.length,"version",version));
        }
        List<Object> pairRails(City from, City to) {
            List<Object> rows = new ArrayList<>(railIndex.getOrDefault(new Edge(from,to),List.of()));
            rows.addAll(railIndex.getOrDefault(new Edge(to,from),List.of())); return rows;
        }
        Map<String,Object> pairTransport(City from, City to) {
            Map<String,Object> data = new LinkedHashMap<>();
            for (String type : transport.keySet()) {
                List<Object> rows = new ArrayList<>(transportIndex.get(type).getOrDefault(new Edge(from,to),List.of()));
                rows.addAll(transportIndex.get(type).getOrDefault(new Edge(to,from),List.of())); data.put(type,rows);
            }
            return data;
        }
    }
    static Map<String,Object> m(Object... values) {
        Map<String,Object> r=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2) r.put((String)values[i],values[i+1]); return r;
    }
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static Edge edge(Map<?,?> row) {
        City f=new City(RailTimetable.value(row,"from_province"),RailTimetable.value(row,"from_city"));
        City t=new City(RailTimetable.value(row,"to_province"),RailTimetable.value(row,"to_city"));
        return RegionDirectory.known(f.province,f.city)&&RegionDirectory.known(t.province,t.city)?new Edge(f,t):null;
    }
    static DispatchPreference preference(City destination) {
        return DispatchPreference.from(m("training_province",destination.province,"training_city",destination.city,
            "training_mode","线下","training_period","下午"),null);
    }
    // Production APIs use these two keys for a location. This is not a teacher record.
    static Map<String,Object> originLocation(City source) { return m("base_province",source.province,"base_city",source.city); }
    static boolean pending(String status) { return status.startsWith("pending_"); }
    static String safeUrl(Object raw) {
        try {
            URI u=URI.create(Objects.toString(raw,""));
            if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null) return null;
            // Do not copy provider keys, query parameters or fragments into the audit.
            return new URI(u.getScheme(),null,u.getHost(),u.getPort(),u.getPath(),null,null).toString();
        } catch(Exception invalid) { return null; }
    }
    static Map<String,Object> safeReference(Map<?,?> raw) {
        Map<String,Object> safe=new LinkedHashMap<>();
        for(String field:List.of("train_no","flight_no","from_station","to_station","from_airport_code","to_airport_code",
            "departure_time","arrival_time","arrival_day_offset","rail_minutes","flight_minutes","observed_on",
            "source_service_date","source_date_status","reference_recheck_after","minimum_minutes","no_service","scope","coverage_complete"))
            if(raw.containsKey(field)) safe.put(field,raw.get(field));
        safe.put("source_url",safeUrl(raw.get("source_url"))); return safe;
    }
    static boolean invalidDate(Map<?,?> r,LocalDate today) {
        try {
            LocalDate observed=LocalDate.parse(RailTimetable.value(r,"observed_on"));
            if(observed.isAfter(today)||observed.plusDays(30).isBefore(today)) return true;
            String s=RailTimetable.value(r,"source_service_date");
            return !s.isEmpty()&&(LocalDate.parse(s).plusDays(30).isBefore(today)||LocalDate.parse(s).isAfter(observed.plusDays(30)));
        } catch(Exception invalid) { return true; }
    }
    static Map<String,Object> inspectDirection(List<Object> rows,City from,City to,LocalDate today) {
        Map<String,Object> selected=RailTimetable.direction(rows,from.province,from.city,to.province,to.city,today);
        int invalidDates=0; for(Object raw:rows) if(raw instanceof Map<?,?> row&&invalidDate(row,today)) invalidDates++;
        boolean unverifiedOnly=!rows.isEmpty()&&rows.stream().allMatch(raw->raw instanceof Map<?,?> row&&
            (!"observed_timetable".equals(row.get("status"))||TravelMatrix.list(row,"samples").isEmpty()));
        String state=selected!=null?"current_sample":rows.isEmpty()?"missing":unverifiedOnly?"unverified_source":"invalid_or_expired_sample";
        Map<String,Object> inspection=m("status",state,"raw_record_count",rows.size(),"invalid_or_expired_date_records",invalidDates);
        if(selected!=null) inspection.put("selected_reference",safeReference(selected));
        else if(!rows.isEmpty()) {
            List<Map<String,Object>> rejected=new ArrayList<>();
            for(Object raw:rows) if(raw instanceof Map<?,?> row) {
                // Retain dates of rejected sources, not a fabricated fresh observation date.
                rejected.add(m("source_url",safeUrl(row.get("source_url")),"observed_on",row.get("observed_on"),
                    "source_service_date",row.get("source_service_date"),"date_rejected",invalidDate(row,today)));
                if(rejected.size()==3) break;
            }
            inspection.put("rejected_source_examples",rejected);
        }
        return inspection;
    }
    static Map<String,Object> auditRoute(Inputs in,City from,City to,DispatchPreference pref,LocalDate today,boolean fullScanCrossCheck) {
        List<Object> rails=in.pairRails(from,to); Map<String,Object> transport=in.pairTransport(from,to);
        Map<String,Object> resolved=TravelMatrix.resolve(rails,transport,originLocation(from),pref,today);
        if(fullScanCrossCheck) {
            Map<String,Object> baseline=TravelMatrix.resolve(in.rails,in.transport,originLocation(from),pref,today);
            if(!Json.write(canonical(baseline)).equals(Json.write(canonical(resolved)))) throw new IllegalStateException("Indexed resolver mismatch: "+from.key()+" -> "+to.key());
        }
        String productionStatus=Objects.toString(resolved.get("status"),"");
        String eligible=Objects.toString(resolved.get("eligibility"),"");
        if(from.equals(to)) {
            if(!"same_city".equals(productionStatus)) throw new IllegalStateException("Production same-city mismatch");
            return m("origin",from.json(),"status","same_city","production_status",productionStatus,"travel_date_verified",false,
                "reason_codes",List.of("same_city_only_local_commute_unverified"));
        }
        List<Object> outRows=in.railIndex.getOrDefault(new Edge(from,to),List.of());
        List<Object> backRows=in.railIndex.getOrDefault(new Edge(to,from),List.of());
        Map<String,Object> out=inspectDirection(outRows,from,to,today),back=inspectDirection(backRows,to,from,today);
        boolean outValid=out.containsKey("selected_reference"),backValid=back.containsKey("selected_reference");
        List<String> reasons=new ArrayList<>();
        String status="rail".equals(eligible)?"rail_reference_eligible":"air".equals(eligible)?"air_reference_eligible":
            "outside".equals(eligible)?"outside_verified":"pending_missing";
        Map<?,?> outCheck=TravelMatrix.checked(transport,"rail",from.province,from.city,to.province,to.city,today);
        Map<?,?> backCheck=TravelMatrix.checked(transport,"rail",to.province,to.city,from.province,from.city,today);
        if(pending(status)) {
            if(outRows.isEmpty()) reasons.add("outbound_rail_source_missing");
            if(backRows.isEmpty()) reasons.add("return_rail_source_missing");
            if(((Number)out.get("invalid_or_expired_date_records")).intValue()>0&&!outValid) reasons.add("outbound_rail_date_invalid_or_expired");
            if(((Number)back.get("invalid_or_expired_date_records")).intValue()>0&&!backValid) reasons.add("return_rail_date_invalid_or_expired");
            if(!outRows.isEmpty()&&!outValid&&((Number)out.get("invalid_or_expired_date_records")).intValue()==0)
                reasons.add("unverified_source".equals(out.get("status"))?"outbound_rail_source_unverified":"outbound_rail_sample_rejected");
            if(!backRows.isEmpty()&&!backValid&&((Number)back.get("invalid_or_expired_date_records")).intValue()==0)
                reasons.add("unverified_source".equals(back.get("status"))?"return_rail_source_unverified":"return_rail_sample_rejected");
            boolean slow=false;
            for(Map<String,Object> d:List.of(out,back)) if(d.get("selected_reference") instanceof Map<?,?> witness&&RailTimetable.minute(witness.get("rail_minutes"))>=240) slow=true;
            if(outValid!=backValid) status="pending_oneway";
            else if(reasons.stream().anyMatch(x->x.contains("date_invalid_or_expired"))) status="pending_stale";
            else if(!outValid&&!backValid&&("invalid_or_expired_sample".equals(out.get("status"))||"invalid_or_expired_sample".equals(back.get("status")))) status="pending_invalid_evidence";
            if(slow) { status="pending_slow_sample"; reasons.add("slow_sample_does_not_prove_fast_service_absent"); }
            if(outCheck==null||backCheck==null) reasons.add("complete_bidirectional_rail_exclusion_not_established");
            if(!TravelMatrix.list(transport,"rail_checks").isEmpty()&&!slow) status="pending_incomplete_exclusion";
            if(outCheck!=null&&backCheck!=null) {
                reasons.add("production_resolver_did_not_accept_complete_rail_and_air_evidence"); status="pending_incomplete_exclusion";
            }
            if(NearbySelection.maximumNearbyMinutes()==0 || outValid&&backValid&&!slow&&"rail_time_reference".equals(productionStatus)) {
                status="pending_policy_limit"; reasons.add("administrator_cross_city_limit_blocks_admission");
            }
        } else reasons.add(status.equals("rail_reference_eligible")?"two_current_rail_samples_under_limit":
            status.equals("air_reference_eligible")?"production_rail_exclusion_and_two_current_nonstop_air_samples":"production_complete_rail_and_air_checks_outside_limits");
        Map<String,Object> result=m("origin",from.json(),"status",status,"production_status",productionStatus,
            "production_eligibility",eligible,"travel_date_verified",false,"reason_codes",reasons);
        if(!outRows.isEmpty()||!backRows.isEmpty()) result.put("rail_evidence",m("outbound",out,"return",back));
        if(outCheck!=null||backCheck!=null) {
            Map<String,Object> checks=new LinkedHashMap<>();
            if(outCheck!=null) checks.put("outbound",safeReference(outCheck));
            if(backCheck!=null) checks.put("return",safeReference(backCheck)); result.put("complete_rail_checks",checks);
        }
        if("air_reference_eligible".equals(status)) result.put("air_evidence",m("outbound",safeReference((Map<?,?>)resolved.get("outbound")),"return",safeReference((Map<?,?>)resolved.get("return"))));
        if("outside_verified".equals(status)) {
            Map<String,Object> checks=new LinkedHashMap<>();
            Map<?,?> a=TravelMatrix.checked(transport,"air",from.province,from.city,to.province,to.city,today),b=TravelMatrix.checked(transport,"air",to.province,to.city,from.province,from.city,today);
            if(a!=null) checks.put("outbound",safeReference(a)); if(b!=null) checks.put("return",safeReference(b)); result.put("complete_air_checks",checks);
        }
        return result;
    }
    static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) { Map<String,Object> out=new TreeMap<>(); map.forEach((k,v)->out.put(String.valueOf(k),canonical(v))); return out; }
        if(value instanceof List<?> list) return list.stream().map(TransportCoverageAudit::canonical).toList(); return value;
    }
    static int queuePriority(String status) {
        return switch(status) { case "pending_oneway"->1; case "pending_stale"->2; case "pending_slow_sample"->3;
            case "pending_incomplete_exclusion"->4; case "pending_invalid_evidence"->5; case "pending_missing"->6; default->7; };
    }
    static Path newPrivateOutput(String text) throws Exception {
        Path requested=Paths.get(text).toAbsolutePath().normalize();
        if(Files.exists(requested,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Output must be new; refusing to overwrite");
        Path parent=requested.getParent(); if(parent==null||!Files.isDirectory(parent)) throw new IllegalArgumentException("Output parent must already exist");
        Path real=parent.toRealPath().resolve(requested.getFileName());
        boolean privateTemp=real.startsWith(Paths.get(System.getProperty("java.io.tmpdir")).toRealPath())||real.startsWith(Paths.get("/private/tmp"));
        boolean privateWorkspace=false; for(Path component:real) if(component.toString().equals(".codex-tmp")) privateWorkspace=true;
        if(!privateTemp&&!privateWorkspace) throw new IllegalArgumentException("Output must be inside a private temp directory or .codex-tmp, never web/");
        for(Path component:real) if(Set.of("web","public","dist","static").contains(component.toString())) throw new IllegalArgumentException("Public output directory is forbidden");
        return Files.createDirectory(real);
    }
    static void write(Path file,Object data) throws Exception { Files.writeString(file,Json.write(data)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW); }
    static void verifyCatalog(City dest,List<Map<String,Object>> routes,LocalDate today) {
        Map<String,Object> catalog=DispatchPriority.catalog(dest.province,dest.city,"",today);
        Map<String,String> expected=new HashMap<>();
        for(Object raw:(List<?>)catalog.get("priorities")) if(raw instanceof Map<?,?> row) {
            String key=row.get("province")+"/"+row.get("city");
            expected.put(key,key.equals(dest.key())?"same_city":"air".equals(row.get("eligibility"))?"air_reference_eligible":"rail_reference_eligible");
        }
        for(Object raw:(List<?>)catalog.get("pending_routes")) if(raw instanceof Map<?,?> row) expected.put(row.get("province")+"/"+row.get("city"),"pending");
        for(Object raw:(List<?>)catalog.get("outside_nearby_routes")) if(raw instanceof Map<?,?> row) expected.put(row.get("province")+"/"+row.get("city"),"outside_verified");
        if(expected.size()!=routes.size()) throw new IllegalStateException("DispatchPriority catalog universe mismatch");
        for(Map<String,Object> route:routes) {
            Map<?,?> city=(Map<?,?>)route.get("origin"); String actual=(String)route.get("status"); if(pending(actual)) actual="pending";
            if(!actual.equals(expected.get(city.get("province")+"/"+city.get("city")))) throw new IllegalStateException("DispatchPriority catalog eligibility mismatch");
        }
    }
    public static void main(String[] args) throws Exception {
        Map<String,List<String>> options=new LinkedHashMap<>();
        Set<String> allowed=Set.of("--rail-file","--research-dir","--transport-file","--output-dir","--today","--verify-catalog-city","--cross-check-all");
        for(int i=0;i<args.length;i++) {
            if(!allowed.contains(args[i])) throw new IllegalArgumentException("Unknown option: "+args[i]);
            String key=args[i]; String value=key.equals("--cross-check-all")?"true":++i<args.length?args[i]:null;
            if(value==null||value.startsWith("--")) throw new IllegalArgumentException("Missing option value"); options.computeIfAbsent(key,k->new ArrayList<>()).add(value);
        }
        if(!options.containsKey("--rail-file")||!options.containsKey("--output-dir")) throw new IllegalArgumentException("Required: --rail-file PATH --output-dir NEW_PRIVATE_DIRECTORY");
        for(String one:List.of("--rail-file","--transport-file","--output-dir","--today","--cross-check-all")) if(options.getOrDefault(one,List.of()).size()>1) throw new IllegalArgumentException("Duplicate single option: "+one);
        boolean research=options.containsKey("--research-dir");
        if(research&&options.containsKey("--verify-catalog-city")) throw new IllegalArgumentException("Catalog parity requires an already merged rail input (no research-dir)");
        LocalDate today=options.containsKey("--today")?LocalDate.parse(options.get("--today").get(0)):RailTimetable.today();
        long started=System.nanoTime(); Inputs in=new Inputs(); Path railFile=Paths.get(options.get("--rail-file").get(0)).toAbsolutePath(); in.read(railFile,false,false);
        if(options.containsKey("--transport-file")) in.read(Paths.get(options.get("--transport-file").get(0)),true,false);
        for(String directory:options.getOrDefault("--research-dir",List.of())) {
            Path dir=Paths.get(directory); if(!Files.isDirectory(dir)||Files.isSymbolicLink(dir)) throw new IllegalArgumentException("Research directory is invalid");
            try(var files=Files.list(dir)) { for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList()) in.read(file,false,true); }
        }
        List<City> cities=new ArrayList<>(); RegionDirectory.CITIES.forEach((p,cs)->cs.forEach(c->cities.add(new City(p,c))));
        if(new HashSet<>(cities).size()!=cities.size()) throw new IllegalStateException("Duplicate directory city key");
        int n=cities.size(); String[][] statuses=new String[n][n]; Set<String> verify=new HashSet<>(options.getOrDefault("--verify-catalog-city",List.of()));
        for(String city:verify) if(cities.stream().noneMatch(c->c.key().equals(city))) throw new IllegalArgumentException("Unknown verification city: "+city);
        if(!verify.isEmpty()) {
            if(Files.size(railFile)>1024*1024) throw new IllegalArgumentException("Production RailTimetable file limit exceeded for parity check");
            System.setProperty("dispatch.timetable.file",railFile.toString());
            System.setProperty("dispatch.transport.file",options.getOrDefault("--transport-file",List.of(railFile.resolveSibling(".absent-audit-transport.json").toString())).get(0));
            System.setProperty("dispatch.transport.dir","");
            System.setProperty("dispatch.organizations.file",railFile.resolveSibling(".absent-audit-organizations.json").toString());
        }
        Path output=newPrivateOutput(options.get("--output-dir").get(0));
        Map<String,Integer> totals=new TreeMap<>(); List<Map<String,Object>> destinations=new ArrayList<>();
        for(int destination=0;destination<n;destination++) {
            City to=cities.get(destination); DispatchPreference pref=preference(to); List<Map<String,Object>> routes=new ArrayList<>(),queue=new ArrayList<>(); Map<String,Integer> counts=new TreeMap<>();
            for(int origin=0;origin<n;origin++) {
                City from=cities.get(origin); Map<String,Object> route=auditRoute(in,from,to,pref,today,options.containsKey("--cross-check-all")); String status=(String)route.get("status");
                statuses[origin][destination]=status; routes.add(route); counts.merge(status,1,Integer::sum); totals.merge(status,1,Integer::sum);
                if(pending(status)) queue.add(m("origin",from.json(),"status",status,"work_priority",queuePriority(status)));
            }
            queue.sort(Comparator.comparingInt(row->((Number)row.get("work_priority")).intValue()));
            if(verify.contains(to.key())) verifyCatalog(to,routes,today);
            String filename=to.province+"-"+to.city+".json";
            write(output.resolve(filename),m("version","transport-coverage-audit-city-v1","audited_on",today.toString(),"destination",to.json(),
                "usage","PRIVATE_AUDIT_ONLY","commercial_permission_validated",false,"business_import_allowed",false,
                "universe_city_count",n,"enumerated_route_count",routes.size(),"cross_city_route_count",n-1,"status_counts",counts,
                "pending_cross_city_count",queue.size(),"all_city_evidence_resolved",queue.isEmpty(),"enumeration_is_timetable_verification",false,
                "travel_date_verified",false,"routes",routes,"next_verification_queue",queue));
            destinations.add(m("destination",to.json(),"file",filename,"status_counts",counts,"pending_cross_city_count",queue.size(),
                "next_route",queue.isEmpty()?null:queue.get(0)));
        }
        Map<String,Integer> unordered=new TreeMap<>();
        for(int a=0;a<n;a++) for(int b=a+1;b<n;b++) {
            String first=statuses[a][b],second=statuses[b][a];
            String key=pending(first)||pending(second)?"pending":first.equals(second)?first:"mixed_resolved"; unordered.merge(key,1,Integer::sum);
        }
        int pendingDirected=totals.entrySet().stream().filter(e->pending(e.getKey())).mapToInt(Map.Entry::getValue).sum();
        Map<String,Object> classes=new LinkedHashMap<>();
        for(String name:List.of("RegionDirectory","RailTimetable","TravelMatrix","DispatchPreference","NearbySelection","DispatchPriority","TransportCoverageAudit"))
            try(InputStream stream=TransportCoverageAudit.class.getResourceAsStream("/com/training/"+name+".class")) { if(stream!=null) classes.put(name,sha(stream.readAllBytes())); }
        Map<String,Object> summary=m("version","transport-coverage-audit-v1","audited_on",today.toString(),"generated_at",OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString(),
            "usage","PRIVATE_AUDIT_ONLY","commercial_permission_validated",false,"business_import_allowed",false,
            "input_scope","RegionDirectory nationwide city cross product; no teacher or organization input","directory_city_count",n,
            "directed_cross_city_universe",n*(n-1),"unordered_cross_city_universe",n*(n-1)/2,"same_city_count",n,
            "enumerated_total_routes",n*n,"status_counts",totals,"unordered_pair_status_counts",unordered,"pending_directed_routes",pendingDirected,
            "reference_resolved_directed_routes",n*(n-1)-pendingDirected,"pending_unordered_pairs",unordered.getOrDefault("pending",0),
            "reference_resolved_unordered_pairs",n*(n-1)/2-unordered.getOrDefault("pending",0),"all_city_evidence_resolved",pendingDirected==0,
            "enumeration_is_timetable_verification",false,"human_research_performed_by_this_script",false,"travel_date_verified",false,
            "maximum_nearby_minutes",NearbySelection.maximumNearbyMinutes(),"rail_limit_exclusive",TravelMatrix.RAIL_LIMIT,"air_limit_exclusive",TravelMatrix.AIR_LIMIT,
            "unique_rail_source_rows",in.rails.size(),"duplicate_rail_rows_skipped",in.duplicateRailRows,"ignored_unknown_city_rows",in.ignoredUnknownCityRows,
            "source_inputs",in.sources,"executed_class_sha256",classes,"indexed_full_scan_cross_check",options.containsKey("--cross-check-all"),
            "dispatch_catalog_parity_cities",new ArrayList<>(verify),"elapsed_seconds",(System.nanoTime()-started)/1e9,"destinations",destinations,
            "notice","Technical resolver checks only; no commercial permission is established and this audit cannot be imported into business. Pending enumeration is not research; references are not travel-day guarantees. Directory coverage is the current product directory, not a claim of every national locality.");
        write(output.resolve("summary.json"),summary);
        System.out.println(Json.write(m("status","completed","output_directory",output.toString(),"cities",n,"directed_cross_city_universe",n*(n-1),
            "unordered_cross_city_universe",n*(n-1)/2,"reference_resolved_unordered_pairs",summary.get("reference_resolved_unordered_pairs"),
            "pending_unordered_pairs",summary.get("pending_unordered_pairs"),"elapsed_seconds",summary.get("elapsed_seconds"))));
    }
}
