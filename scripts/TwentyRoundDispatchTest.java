package com.training;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Rounds 11..20 of the twenty-round audit. ALL people, timetables, grants and
 * completeness claims constructed here are SYNTHETIC TEST FIXTURES.
 * Calls production NearbySelection / TravelMatrix / RailTimetable directly.
 * No network requests, listeners, database access or real route verification.
 * Compile separately; run with --output /absolute/private/result.json if desired.
 */
public final class TwentyRoundDispatchTest {
    static final LocalDate DAY=LocalDate.of(2026,9,7);
    static final String[] LEVELS={"", "讲师", "高级讲师", "特级讲师", "特聘讲师"};
    static final City NJ=new City("江苏","南京"), BJ=new City("北京","北京"), HF=new City("安徽","合肥"), SZ=new City("江苏","苏州");
    static final List<City> CITIES=new ArrayList<>();
    static { RegionDirectory.CITIES.forEach((p,cs)->cs.forEach(c->CITIES.add(new City(p,c)))); }
    static Path work;
    static record City(String province,String city) {}
    interface TestBody { void run(Round r) throws Exception; }
    static final class Round {
        final int number; final String name; final long seed; final Random random;
        final long started=System.nanoTime(); final MessageDigest digest;
        long assertions,failed,scenarios,candidates; final List<Map<String,Object>> failures=new ArrayList<>();
        Round(int n,String title,long s) throws Exception { number=n;name=title;seed=s;random=new Random(s);digest=MessageDigest.getInstance("SHA-256"); }
        void input(Object value) { scenarios++;digest.update((Json.write(canonical(value))+"\n").getBytes(StandardCharsets.UTF_8)); }
        void check(boolean ok,String label) { check(ok,label,null,null); }
        void check(boolean ok,String label,Object expected,Object actual) {
            assertions++; if(!ok) { failed++;if(failures.size()<40) failures.add(m("assertion",label,"expected",expected,"actual",actual)); }
        }
        Map<String,Object> result() {
            return m("round",number,"scenario",name,"seed",seed,"synthetic_data",true,
                "scenario_count",scenarios,"candidate_count",candidates,"assertion_count",assertions,
                "failed_count",failed,"passed_count",assertions-failed,"elapsed_ms",(System.nanoTime()-started)/1_000_000.0,
                "input_digest_sha256",hex(digest.digest()),"failure_examples",failures,"status",failed==0?"passed":"failed");
        }
    }
    static Map<String,Object> m(Object... values) {
        Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;
    }
    static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) { Map<String,Object> sorted=new TreeMap<>();map.forEach((k,v)->sorted.put(String.valueOf(k),canonical(v)));return sorted; }
        if(value instanceof List<?> list)return list.stream().map(TwentyRoundDispatchTest::canonical).toList();
        if(value instanceof Double d&&!Double.isFinite(d))return "__NONFINITE_"+d+"__";
        return value;
    }
    static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    static int n(Object value) { return ((Number)value).intValue(); }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Map<String,Object> value,String key) { return (List<Map<String,Object>>)value.get(key); }
    static List<Long> ids(List<Map<String,Object>> rows) { return rows.stream().map(c->((Number)c.get("teacher_id")).longValue()).toList(); }
    static DispatchPreference pref(City destination) { return DispatchPreference.from(m("training_province",destination.province,"training_city",destination.city,"training_mode","线下","training_period","下午"),null); }
    static Map<String,Object> teacher(City origin) { return m("base_province",origin.province,"base_city",origin.city); }
    static Map<String,Object> base(City from,City to,LocalDate day) {
        return m("from_province",from.province,"from_city",from.city,"to_province",to.province,"to_city",to.city,
            "source_url","https://example.test/SYNTHETIC-NOT-REAL-TRANSPORT/"+from.city+"-"+to.city,
            "source_provider","合成测试来源，不是真实交通核验","source_service_date",day.toString(),"observed_on",day.toString(),
            "usage_authorized",true,"synthetic_test_fixture",true);
    }
    static Map<String,Object> rail(City from,City to,int minutes,LocalDate day) {
        Map<String,Object> row=base(from,to,day); row.put("status","observed_timetable");
        row.put("samples",List.of(m("train_no","G1001","from_station","【合成】"+from.city+"起点站","to_station","【合成】"+to.city+"终点站",
            "departure_time","08:00","arrival_time",LocalTime.of(8,0).plusMinutes(minutes).toString(),"arrival_day_offset",(480+minutes)/1440,
            "rail_minutes",minutes,"station_city_mapping_checked",true,"synthetic_test_fixture",true)));return row;
    }
    static List<Map<String,Object>> rails(City from,City to,int out,int back) { return List.of(rail(from,to,out,DAY),rail(to,from,back,DAY)); }
    static Map<String,Object> checkRow(City from,City to,String mode,int minutes) {
        Map<String,Object> row=base(from,to,DAY);row.put("scope",mode.equals("rail")?"all_city_stations_and_rail_itineraries":"all_city_airports_nonstop_services");
        row.put("coverage_complete",true);row.put("minimum_minutes",minutes);return row;
    }
    static Map<String,Object> flight(City from,City to,int minutes) {
        Map<String,Object> row=base(from,to,DAY);row.putAll(m("flight_no","ZZ9999","from_airport_code","AAA","to_airport_code","BBB",
            "departure_time","08:00","arrival_time",LocalTime.of(8,0).plusMinutes(minutes).toString(),"arrival_day_offset",(480+minutes)/1440,
            "flight_minutes",minutes,"nonstop",true,"stops",0,"airport_city_mapping_checked",true,"service_type","passenger",
            "service_mode","air","cancelled",false,"timezone","Asia/Shanghai"));return row;
    }
    static Map<String,Object> airData(City from,City to,int out,int back) {
        return m("version","transport-matrix-v1","synthetic_test_fixture",true,
            "rail_checks",new ArrayList<>(List.of(checkRow(from,to,"rail",300),checkRow(to,from,"rail",310))),
            "flights",new ArrayList<>(List.of(flight(from,to,out),flight(to,from,back))));
    }
    static Map<String,Object> resolve(City from,City to,List<?> rail,Map<?,?> data) { return TravelMatrix.resolve(rail,data,teacher(from),pref(to),DAY); }
    static Map<String,Object> railRoute(int out,int back) { return resolve(NJ,BJ,rails(NJ,BJ,out,back),Map.of()); }
    static Map<String,Object> airRoute(int out,int back) { return resolve(NJ,BJ,List.of(),airData(NJ,BJ,out,back)); }
    static Map<String,Object> candidate(long id,Integer score,String level,Map<String,Object> route,boolean local,int fallback) {
        return m("teacher_id",id,"name","【合成压力测试】候选"+id,"synthetic_test_fixture",true,
            "model_score",score,"recommendation_score",score,"ranking_score",fallback,"teacher_level",level,
            "dispatch_fit",m("local_priority",local,"route_reference",route));
    }
    static Map<String,Object> c(long id,int score,Map<String,Object> route) { return candidate(id,score,"讲师",route,false,score); }
    static int level(Map<String,Object> c) { return Arrays.asList(LEVELS).indexOf(c.get("teacher_level")); }
    static boolean air(Map<String,Object> c) { return "air_time_reference".equals(((Map<?,?>)((Map<?,?>)c.get("dispatch_fit")).get("route_reference")).get("status")); }
    static double score(Map<String,Object> c,boolean model) { return ((Number)c.get(model?"model_score":"ranking_score")).doubleValue(); }
    // Independent oracle: supplied admissible pool is derived from each scenario,
    // not from production priority tiers or production comparator helpers.
    static List<Long> expected(List<Map<String,Object>> admissible,boolean model) {
        List<Map<String,Object>> sorted=new ArrayList<>(admissible);
        sorted.sort((a,b)-> { int transport=Boolean.compare(air(a),air(b));if(transport!=0)return transport;
            int s=Double.compare(score(b,model),score(a,model));if(s!=0)return s;
            int l=Integer.compare(level(b),level(a));return l!=0?l:Long.compare(((Number)a.get("teacher_id")).longValue(),((Number)b.get("teacher_id")).longValue()); });
        if(sorted.size()<3)return ids(sorted);
        Map<String,Object> boundary=sorted.get(2);List<Long> out=new ArrayList<>();
        for(Map<String,Object> c:sorted) if(!air(c)&&air(boundary)||air(c)==air(boundary)&&score(c,model)>=score(boundary,model))out.add(((Number)c.get("teacher_id")).longValue());
        return out;
    }
    static void selectedEquals(Round r,Map<String,Object> actual,List<Long> expected,String label) {
        r.check(ids(rows(actual,"selected")).equals(expected),label,expected,ids(rows(actual,"selected")));
        r.check(n(actual.get("ties_included"))==Math.max(0,expected.size()-3),label+" tie count",Math.max(0,expected.size()-3),actual.get("ties_included"));
    }

    static void railBoundaries(Round r) {
        int[] boundaries={1,59,119,120,121,179,180,181,238,239,240,241,360};
        for(int out:boundaries)for(int back:boundaries) {
            r.input(List.of(out,back));Map<String,Object> route=railRoute(out,back);boolean qualifies=Math.max(out,back)<240;
            r.check((qualifies?"rail":"unknown").equals(route.get("eligibility")),"strict rail boundary",qualifies?"rail":"unknown",route.get("eligibility"));
            Map<String,Object> selection=NearbySelection.select(List.of(c(1,r.random.nextInt(101),route)),pref(BJ),3);r.candidates++;
            r.check(rows(selection,"selected").size()==(qualifies?1:0),"selection agrees with both rail directions");
            r.check(!Boolean.TRUE.equals(route.get("transport_verified"))&&!Boolean.TRUE.equals(route.get("travel_date_verified")),"synthetic rail never becomes actual transport verification");
        }
        for(int i=0;i<250;i++) {
            int out=1+r.random.nextInt(500),back=1+r.random.nextInt(500);r.input(List.of(out,back));
            r.check((Math.max(out,back)<240?"rail":"unknown").equals(railRoute(out,back).get("eligibility")),"random asymmetric rail bound");
        }
    }
    static void airBoundaries(Round r) {
        int[] bounds={1,59,90,178,179,180,181,239};
        for(int out:bounds)for(int back:bounds) {
            r.input(List.of(out,back));Map<String,Object> route=airRoute(out,back);boolean qualifies=Math.max(out,back)<180;
            r.check((qualifies?"air":"unknown").equals(route.get("eligibility")),"strict air boundary",qualifies?"air":"unknown",route.get("eligibility"));
            r.check(rows(NearbySelection.select(List.of(c(1,99,route)),pref(BJ),3),"selected").size()==(qualifies?1:0),"air bound enforced again in selection");r.candidates++;
            r.check(!Boolean.TRUE.equals(route.get("transport_verified"))&&!Boolean.TRUE.equals(route.get("travel_date_verified")),"sample is never a verified course flight");
        }
        for(int i=0;i<150;i++) {
            int duration=1+r.random.nextInt(179);Map<String,Object> data=airData(NJ,BJ,duration,duration);
            data.put("rail_checks",List.of(checkRow(NJ,BJ,"rail",240),checkRow(BJ,NJ,"rail",240)));
            r.input(List.of("exact-rail-cutoff",duration));r.check("air".equals(resolve(NJ,BJ,List.of(),data).get("eligibility")),"complete rail 240 fails strict rail inclusion and permits tested air alternative");
            data.put("flights",List.of(flight(NJ,BJ,duration)));r.check("unknown".equals(resolve(NJ,BJ,List.of(),data).get("eligibility")),"one-way air cannot invent reverse");
        }
    }
    static void allCities(Round r) {
        for(int iteration=0;iteration<24;iteration++) {
            City destination=CITIES.get(r.random.nextInt(CITIES.size()));List<Map<String,Object>> cs=new ArrayList<>();
            long id=1;int far=0;
            for(City from:CITIES) {
                boolean local=from.equals(destination);int minutes=60+r.random.nextInt(180);Map<String,Object> route;
                if(local)route=resolve(from,destination,List.of(),Map.of());else route=resolve(from,destination,rails(from,destination,minutes,minutes),Map.of());
                int score=local?1:r.random.nextInt(90);if(!local&&minutes>180&&far++==0)score=100;
                cs.add(candidate(id++,score,LEVELS[r.random.nextInt(LEVELS.length)],route,local,score));
            }
            Collections.shuffle(cs,r.random);r.input(cs);r.candidates+=cs.size();Map<String,Object> selection=NearbySelection.select(cs,pref(destination),3);
            r.check(cs.size()==371,"all current directory cities included in synthetic universe");
            r.check(n(selection.get("pool_size"))==371,"full four-hour candidate pool cannot stop when near tier has three",371,selection.get("pool_size"));
            selectedEquals(r,selection,expected(cs,true),"all-city complete-pool ranking "+iteration);
            r.check(rows(selection,"travel_pending").isEmpty(),"every supplied synthetic sub-four-hour route was considered");
            r.check(n(rows(selection,"selected").get(0).get("model_score"))==100,"higher-scoring far tier outranks local lower score");
        }
    }
    static void railBeforeAir(Round r) {
        Map<String,Object> rail=railRoute(220,239),air=airRoute(179,150),unknown=railRoute(300,310);
        for(int iteration=0;iteration<260;iteration++) {
            int railCount=r.random.nextInt(7),airCount=1+r.random.nextInt(12),unknownCount=r.random.nextInt(5);long id=1;
            List<Map<String,Object>> cs=new ArrayList<>(),railCs=new ArrayList<>(),airCs=new ArrayList<>();
            for(int i=0;i<railCount;i++){Map<String,Object> c=c(id++,10+r.random.nextInt(20),rail);cs.add(c);railCs.add(c);}
            for(int i=0;i<airCount;i++){Map<String,Object> c=c(id++,95,air);c.put("teacher_level",LEVELS[r.random.nextInt(5)]);cs.add(c);airCs.add(c);}
            for(int i=0;i<unknownCount;i++)cs.add(c(id++,100,unknown));Collections.shuffle(cs,r.random);r.input(cs);r.candidates+=cs.size();
            List<Map<String,Object>> admitted=new ArrayList<>(railCs);if(railCount<3)admitted.addAll(airCs);
            Map<String,Object> result=NearbySelection.select(cs,pref(BJ),3);selectedEquals(r,result,expected(admitted,true),"rail seats before stronger air scores "+iteration);
            r.check(rows(result,"travel_pending").size()==unknownCount,"unknown routes never fill quota");
            r.check(rows(result,"air_candidates").size()==airCount,"air alternatives remain separately visible");
            if(railCount>=3)r.check(rows(result,"selected").stream().noneMatch(TwentyRoundDispatchTest::air),"sufficient rail pool excludes air from top-three competition");
        }
    }
    static void scoreLevelTies(Round r) {
        Map<String,Object> route=railRoute(125,239);
        for(int iteration=0;iteration<320;iteration++) {
            List<Map<String,Object>> cs=new ArrayList<>();int count=3+r.random.nextInt(97);
            for(int i=0;i<count;i++)cs.add(candidate(i+1,iteration%8==0?80:70+r.random.nextInt(26),LEVELS[r.random.nextInt(5)],route,false,r.random.nextInt(101)));
            Collections.shuffle(cs,r.random);r.input(cs);r.candidates+=cs.size();Map<String,Object> result=NearbySelection.select(cs,pref(BJ),3);
            selectedEquals(r,result,expected(cs,true),"model-only order, four levels and third-score ties "+iteration);
            if(iteration%8==0)r.check(rows(result,"selected").size()==count,"level tie-break does not truncate all third-score ties");
            r.check("model_only".equals(result.get("scoring_mode")),"contradictory fallback scores ignored when model exists");
        }
    }
    static void unknownContradictions(Round r) {
        for(int iteration=0;iteration<288;iteration++) {
            int variant=iteration%16;Map<String,Object> data=airData(NJ,BJ,70+r.random.nextInt(100),90);List<?> rail=List.of();
            Map<String,Object> out=checkRow(NJ,BJ,"rail",300),back=checkRow(BJ,NJ,"rail",310);
            switch(variant) {
                case 0->data.remove("rail_checks");
                case 1->{data.remove("rail_checks");rail=rails(NJ,BJ,300,310);}
                case 2->{out.put("coverage_complete",false);data.put("rail_checks",List.of(out,back));}
                case 3->{out.put("usage_authorized",false);data.put("rail_checks",List.of(out,back));}
                case 4->{out.put("scope","selected_fastest_sample");data.put("rail_checks",List.of(out,back));}
                case 5->data.put("rail_checks",List.of(out));
                case 6->{out.put("no_service",true);data.put("rail_checks",List.of(out,back));}
                case 7->{out.put("source_service_date",DAY.minusDays(31).toString());data.put("rail_checks",List.of(out,back));}
                case 8->{out.put("observed_on",DAY.plusDays(1).toString());data.put("rail_checks",List.of(out,back));}
                case 9->{List<Map<String,Object>> a=new ArrayList<>(List.of(out,checkRow(NJ,BJ,"rail",100),back));Collections.shuffle(a,r.random);data.put("rail_checks",a);}
                case 10->{Map<String,Object> absent=checkRow(NJ,BJ,"rail",300);absent.put("no_service",true);data.put("rail_checks",List.of(out,absent,back));}
                case 11->rail=rails(NJ,BJ,200,400);
                case 12->rail=rails(NJ,BJ,400,200);
                case 13->{out.put("source_service_date",null);data.put("rail_checks",List.of(out,back));}
                case 14->{out.put("minimum_minutes",0);data.put("rail_checks",List.of(out,back));}
                case 15->{out.put("to_city","合肥");data.put("rail_checks",List.of(out,back));}
            }
            r.input(List.of(variant,data,rail));Map<String,Object> route=resolve(NJ,BJ,rail,data);
            r.check("unknown".equals(route.get("eligibility")),"incomplete/contradictory evidence not promoted to rail exclusion variant="+variant,"unknown",route.get("eligibility"));
            r.check(rows(NearbySelection.select(List.of(c(1,100,route)),pref(BJ),3),"selected").isEmpty(),"unknown cannot become aviation eligibility");r.candidates++;
        }
        for(int i=0;i<80;i++) {
            int min=1+r.random.nextInt(239);Map<String,Object> data=airData(NJ,BJ,90,90);r.input(List.of("positive-rail-witness",min));
            r.check("rail".equals(resolve(NJ,BJ,rails(NJ,BJ,min,min),data).get("eligibility")),"known qualifying train defeats contradictory negative rail summary");
            Map<String,Object> out=checkRow(NJ,BJ,"air",220);out.put("no_service",true);
            data.put("air_checks",List.of(out,checkRow(BJ,NJ,"air",210)));data.put("flights",List.of(flight(NJ,BJ,90)));
            r.check("unknown".equals(resolve(NJ,BJ,List.of(),data).get("eligibility")),"short flight witness prevents false outside conclusion");
            data.put("flights",List.of());
            r.check("outside".equals(resolve(NJ,BJ,List.of(),data).get("eligibility")),"only complete non-conflicting negative proofs may establish outside policy");
        }
    }
    static void sourceIntegrity(Round r) {
        List<Map<String,Object>> mutations=List.of(m("stops",1),m("nonstop",false),m("airport_city_mapping_checked",false),m("usage_authorized",false),
            m("service_type","cargo"),m("service_mode","surface"),m("cancelled",true),m("timezone","UTC"),m("flight_no","invalid"),m("from_airport_code","?"),
            m("to_airport_code","AAA"),m("departure_time","08:00:01"),m("arrival_time","99:99"),m("arrival_day_offset",0.5),m("flight_minutes",0),
            m("flight_minutes",90.5),m("flight_minutes","90"),m("flight_minutes",Double.NaN),m("source_url","http://example.test/insecure"),
            m("source_url","https://user:pass@example.test/private"),m("source_url","javascript:alert(1)"),m("observed_on",DAY.minusDays(31).toString()),
            m("observed_on",DAY.plusDays(1).toString()),m("source_service_date",DAY.minusDays(31).toString()),m("source_service_date",DAY.plusDays(31).toString()),
            m("source_service_date",null),m("from_city","合肥"),m("to_province","江苏"));
        for(int iteration=0;iteration<12;iteration++)for(Map<String,Object> mutation:mutations) {
            int duration=30+r.random.nextInt(140);Map<String,Object> bad=flight(NJ,BJ,duration);bad.putAll(mutation);Map<String,Object> data=airData(NJ,BJ,duration,100);
            data.put("flights",List.of(bad,flight(BJ,NJ,100)));r.input(List.of(iteration,mutation,duration));
            r.check("unknown".equals(resolve(NJ,BJ,List.of(),data).get("eligibility")),"bad source/date/nonstop/time cannot qualify: "+mutation);
            data.put("flights",List.of(bad,flight(NJ,BJ,duration),flight(BJ,NJ,100)));
            r.check("air".equals(resolve(NJ,BJ,List.of(),data).get("eligibility")),"bad first row cannot hide later valid independent flight");
        }
        for(int age:List.of(29,30,31)) {
            Map<String,Object> data=airData(NJ,BJ,90,90);for(Map<String,Object> row:rows(data,"flights"))row.put("observed_on",DAY.minusDays(age).toString());
            r.input(List.of("observation-boundary",age));r.check((age<=30?"air":"unknown").equals(resolve(NJ,BJ,List.of(),data).get("eligibility")),"observation expiry inclusive day30");
        }
    }
    static void controls(Round r) {
        String before=System.getProperty("dispatch.nearby.max.minutes");
        try {
            for(String control:List.of("0","-1","invalid","180","240","999"))for(int iteration=0;iteration<45;iteration++) {
                System.setProperty("dispatch.nearby.max.minutes",control);int limit=control.equals("180")?180:control.equals("240")||control.equals("999")?240:0;
                int duration=1+r.random.nextInt(239);Map<String,Object> data=airData(NJ,BJ,90,90);r.input(List.of(control,duration));
                Map<String,Object> route=resolve(NJ,BJ,rails(NJ,BJ,duration,duration),data);
                r.check((duration<limit?"rail":"unknown").equals(route.get("eligibility")),"administrator rail catchment checked before air fallback");
                r.check(rows(NearbySelection.select(List.of(c(1,99,route)),pref(BJ),3),"selected").size()==(duration<limit?1:0),"selector respects same administrator limit");r.candidates++;
                Map<String,Object> same=resolve(BJ,BJ,List.of(),data);r.check("same_city".equals(same.get("status")),"cross-city pause preserves same-city initial selection");
                if(limit==0)r.check("unknown".equals(resolve(NJ,BJ,List.of(),data).get("eligibility")),"pause also closes airline control path");
                for(DispatchPreference bypass:List.of(DispatchPreference.from(m("training_mode","线上"),null),DispatchPreference.from(m("training_province","北京","training_city","北京","training_mode","线下","prefer_local",false),null))) {
                    Map<String,Object> unrestricted=TravelMatrix.resolve(rails(NJ,BJ,300,300),data,teacher(NJ),bypass,DAY);
                    r.check("not_required".equals(unrestricted.get("status")),"online / explicit preference-off does not consult transport eligibility");
                    Map<String,Object> selection=NearbySelection.select(List.of(c(1,10,unrestricted),c(2,99,unrestricted),c(3,90,unrestricted),c(4,80,unrestricted)),bypass,3);
                    r.candidates+=4;selectedEquals(r,selection,List.of(2L,3L,4L),"disabled location ranking is pure model order");
                }
            }
        } finally { restore("dispatch.nearby.max.minutes",before); }
    }
    static void scoringPools(Round r) {
        Map<String,Object> rail=railRoute(200,239),air=airRoute(100,110);
        for(int iteration=0;iteration<250;iteration++) {
            int count=1+r.random.nextInt(12),airCount=r.random.nextInt(6);List<Map<String,Object>> railCs=new ArrayList<>(),airCs=new ArrayList<>();long id=1;
            for(int i=0;i<count;i++)railCs.add(candidate(id++,r.random.nextBoolean()?null:50+r.random.nextInt(50),LEVELS[r.random.nextInt(5)],rail,false,95+r.random.nextInt(6)));
            for(int i=0;i<airCount;i++)airCs.add(candidate(id++,r.random.nextBoolean()?null:50+r.random.nextInt(50),LEVELS[r.random.nextInt(5)],air,false,95+r.random.nextInt(6)));
            List<Map<String,Object>> all=new ArrayList<>(railCs);all.addAll(airCs);Collections.shuffle(all,r.random);r.input(all);r.candidates+=all.size();
            List<Map<String,Object>> pool=new ArrayList<>(railCs);if(count<3)pool.addAll(airCs);int catchment=pool.size();
            boolean model=pool.stream().anyMatch(c->c.get("model_score")!=null);int missing=model?(int)pool.stream().filter(c->c.get("model_score")==null).count():0;
            boolean waitingRail=model&&railCs.stream().anyMatch(c->c.get("model_score")==null);
            if(model)pool.removeIf(c->c.get("model_score")==null||waitingRail&&air(c));
            Map<String,Object> result=NearbySelection.select(all,pref(BJ),3);selectedEquals(r,result,expected(pool,model),"unscored cannot displace model or bypass rail priority "+iteration);
            r.check(rows(result,"scoring_pending").size()==missing,"separate missing-model denominator",missing,rows(result,"scoring_pending").size());
            r.check(n(result.get("pool_size"))==catchment&&n(result.get("ranked_pool_size"))==pool.size(),"catchment and rankable denominators distinguished");
            r.check((model?"model_only":"unscored_evidence_fallback").equals(result.get("scoring_mode")),"all-unscored fallback labelled rather than called model scores");
        }
    }
    static void writeFixture(Path path,Object value) throws Exception { Files.writeString(path,Json.write(value)+"\n",StandardCharsets.UTF_8); }
    static Map<String,Object> cityBundle(City destination,int duration) {
        LocalDate today=RailTimetable.today();return m("version","transport-matrix-v1","synthetic_test_fixture",true,"fixture_marker",destination.province+"/"+destination.city,
            "rail_directions",List.of(rail(SZ,destination,duration,today),rail(destination,SZ,duration,today)));
    }
    static void filesAndCityIsolation(Round r) throws Exception {
        Path directory=Files.createTempDirectory(work,"synthetic-city-matrices-");City[] destinations={HF,BJ,new City("浙江","杭州"),new City("上海","上海")};int[] durations={77,119,180,239};
        Map<String,String> saved=new LinkedHashMap<>();for(String key:List.of("dispatch.transport.file","dispatch.transport.dir","dispatch.timetable.file","dispatch.organizations.file"))saved.put(key,System.getProperty(key));
        try {
            Path fallback=directory.resolve("fallback.json");writeFixture(fallback,cityBundle(BJ,211));
            System.setProperty("dispatch.transport.file",fallback.toString());System.setProperty("dispatch.transport.dir",directory.toString());
            System.setProperty("dispatch.timetable.file","");System.setProperty("dispatch.organizations.file","");
            for(int i=0;i<destinations.length;i++)writeFixture(directory.resolve(destinations[i].province+"-"+destinations[i].city+".json"),cityBundle(destinations[i],durations[i]));
            for(int iteration=0;iteration<180;iteration++) {
                int index=r.random.nextInt(destinations.length);City destination=destinations[index];r.input(List.of(iteration,index,durations[index]));
                Map<?,?> loaded=TravelMatrix.bundle(destination.province,destination.city);r.check((destination.province+"/"+destination.city).equals(loaded.get("fixture_marker")),"per-city cache exact target key");
                Map<String,Object> route=NearbySelection.route(teacher(SZ),pref(destination),DAY.toString());
                r.check("rail".equals(route.get("eligibility"))&&n(route.get("outbound_reference_minutes"))==durations[index],"production route entry reads target city matrix without mixing files");
                Map<String,Object> catalog=DispatchPriority.catalog(destination.province,destination.city,"",RailTimetable.today());
                r.check(n(catalog.get("enumerated_count"))==371&&n(catalog.get("resolved_count"))==2&&n(catalog.get("unknown_count"))==369,"one synthetic route does not claim full 371-city evidence coverage");
                r.check(Boolean.FALSE.equals(catalog.get("transport_scope_complete")),"enumeration never becomes actual route completeness");
            }
            ExecutorService executor=Executors.newFixedThreadPool(6);
            try {
                List<Callable<Boolean>> calls=new ArrayList<>();for(int i=0;i<240;i++) {int index=r.random.nextInt(destinations.length);r.input(List.of("concurrent-city",i,index));calls.add(()->{
                    City destination=destinations[index];Map<?,?> loaded=TravelMatrix.bundle(destination.province,destination.city);
                    Map<String,Object> route=TravelMatrix.resolve(List.of(),loaded,teacher(SZ),pref(destination),RailTimetable.today());
                    return (destination.province+"/"+destination.city).equals(loaded.get("fixture_marker"))&&"rail".equals(route.get("eligibility"))&&n(route.get("outbound_reference_minutes"))==durations[index];
                });}
                for(Future<Boolean> result:executor.invokeAll(calls))r.check(result.get(),"concurrent per-city cache does not cross-contaminate");
            } finally { executor.shutdownNow(); }
            r.input("missing-per-city-file-must-not-fall-back");r.check(TravelMatrix.bundle("广东","广州").isEmpty(),"missing city file does not borrow a generic other-city bundle");
            Path invalid=directory.resolve("广东-广州.json");writeFixture(invalid,m("version","WRONG","fixture_marker","invalid"));r.check(TravelMatrix.bundle("广东","广州").isEmpty(),"invalid version cannot resurrect previous cached city");
            Files.writeString(invalid,"{\"version\":",StandardCharsets.UTF_8);r.check(TravelMatrix.bundle("广东","广州").isEmpty(),"truncated JSON remains empty, not previous cache");
            Files.writeString(invalid," ".repeat(4*1024*1024+1),StandardCharsets.UTF_8);r.check(TravelMatrix.bundle("广东","广州").isEmpty(),"oversize local bundle rejected");
            System.setProperty("dispatch.transport.dir","");r.check("北京/北京".equals(TravelMatrix.bundle().get("fixture_marker")),"explicit generic-file control supported");
            System.setProperty("dispatch.transport.file","");r.check(TravelMatrix.bundle().isEmpty(),"disabled file does not leak cache");
            System.setProperty("dispatch.transport.dir",directory.toString());writeFixture(directory.resolve("安徽-合肥.json"),cityBundle(HF,188));
            Map<String,Object> updated=NearbySelection.route(teacher(SZ),pref(HF),DAY.toString());r.check(n(updated.get("outbound_reference_minutes"))==188,"changed target file refreshes cache");
        } finally { saved.forEach(TwentyRoundDispatchTest::restore); }
    }
    static void restore(String property,String old) { if(old==null)System.clearProperty(property);else System.setProperty(property,old); }
    static Map<String,Object> hashes(Path root) throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();for(String name:List.of("NearbySelection","TravelMatrix","RailTimetable","DispatchPriority","DispatchPreference","RegionDirectory","TeacherLevel")) {
            Map<String,Object> hash=new LinkedHashMap<>();Path source=root.resolve("src/com/training/"+name+".java");
            if(Files.isRegularFile(source))hash.put("source_sha256",hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
            try(InputStream in=TwentyRoundDispatchTest.class.getResourceAsStream("/com/training/"+name+".class")){if(in!=null)hash.put("executed_class_sha256",hex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));}
            result.put(name,hash);
        }return result;
    }
    public static void main(String[] args) throws Exception {
        Path output=null,root=Paths.get("").toAbsolutePath();work=null;
        for(int i=0;i<args.length;i++)switch(args[i]) {
            case "--output"->output=Paths.get(args[++i]);case "--workdir"->work=Paths.get(args[++i]);case "--repo"->root=Paths.get(args[++i]);
            default->throw new IllegalArgumentException("Unknown argument: "+args[i]);
        }
        if(output!=null&&!output.isAbsolute()||work!=null&&!work.isAbsolute()||!root.isAbsolute())throw new IllegalArgumentException("Output/work/repo paths must be absolute");
        if(work==null)work=Files.createTempDirectory("yanxu-synthetic-dispatch-stress-");else Files.createDirectories(work);
        String before=System.getProperty("dispatch.nearby.max.minutes");System.setProperty("dispatch.nearby.max.minutes","240");
        Map<String,Object> sourceHashes=hashes(root);long started=System.nanoTime();List<Map<String,Object>> rounds=new ArrayList<>();
        String[] titles={"双向铁路239/240及非对称边界","双向直飞179/180及铁路240边界","371城市全池不早停压力","铁路先行与航空补位","量化分/四等级/第三名全并列","未知/缺失/矛盾来源不能当超时","来源日期与航段完整性破坏","管理员入口/线上/地区关闭控制","未评分独立池与整体降级","多城市矩阵/缓存/并发隔离"};
        TestBody[] bodies={TwentyRoundDispatchTest::railBoundaries,TwentyRoundDispatchTest::airBoundaries,TwentyRoundDispatchTest::allCities,TwentyRoundDispatchTest::railBeforeAir,TwentyRoundDispatchTest::scoreLevelTies,TwentyRoundDispatchTest::unknownContradictions,TwentyRoundDispatchTest::sourceIntegrity,TwentyRoundDispatchTest::controls,TwentyRoundDispatchTest::scoringPools,TwentyRoundDispatchTest::filesAndCityIsolation};
        try {
            for(int i=0;i<bodies.length;i++) {
                Round round=new Round(i+11,titles[i],202609070011L+i*104729L);
                try { bodies[i].run(round); }catch(Throwable error){round.check(false,"unexpected round exception",null,error.getClass().getName()+": "+error.getMessage());}
                Map<String,Object> result=round.result();rounds.add(result);System.err.println(Json.write(m("round",i+11,"assertion_count",round.assertions,"failed_count",round.failed,"elapsed_ms",result.get("elapsed_ms"))));
            }
        } finally { restore("dispatch.nearby.max.minutes",before); }
        long total=rounds.stream().mapToLong(v->((Number)v.get("assertion_count")).longValue()).sum(),failures=rounds.stream().mapToLong(v->((Number)v.get("failed_count")).longValue()).sum();
        Path testSource=root.resolve("scripts/TwentyRoundDispatchTest.java");
        String testHash=Files.isRegularFile(testSource)?hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(testSource))):null;
        Map<String,Object> report=m("schema_version","synthetic-dispatch-stress-v1","suite","twenty-round-audit-transport-rounds-11-20","synthetic_data",true,
            "real_route_accuracy_measured",false,"scope_note","合成候选/车次/航班/授权与覆盖声明仅用于程序行为测试；不证明真实交通可达、授权或全国覆盖。未访问网络或业务数据库。",
            "assertion_metric_note","分母为本套程序执行的逻辑断言，不是真实路线数量、召回率或准确率。",
            "fixed_logic_test_day",DAY.toString(),"file_entry_test_observed_on",RailTimetable.today().toString(),"generated_at_utc",Instant.now().toString(),
            "java_version",System.getProperty("java.version"),"repository",root.toString(),"test_source_sha256",testHash,
            "executed_production_code_source",NearbySelection.class.getProtectionDomain().getCodeSource().getLocation().toString(),
            "production_hashes",sourceHashes,"private_fixture_directory",work.toString(),
            "round_count",rounds.size(),"assertion_count",total,"failed_count",failures,"passed_count",total-failures,
            "elapsed_ms",(System.nanoTime()-started)/1_000_000.0,"rounds",rounds,"status",failures==0?"passed":"failed");
        String json=Json.write(report)+"\n";if(output!=null) { Files.createDirectories(output.getParent());Files.writeString(output,json,StandardCharsets.UTF_8); }
        System.out.print(json);if(failures>0)System.exit(1);
    }
}
