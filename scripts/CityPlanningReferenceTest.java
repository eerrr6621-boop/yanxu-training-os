package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Local-only integration and negative tests. Requires a private six-pair draft,
 * so no private evidence is checked into Git. Uses disposable outputs, never config. */
public final class CityPlanningReferenceTest {
    static int checks=0;
    static final LocalDate TODAY=LocalDate.of(2026,9,8);
    static Map<String,Object> m(Object x){return (Map<String,Object>)x;}
    static List<Object> l(Object x){return (List<Object>)x;}
    static Map<String,Object> copy(Map<?,?> x){return m(Json.parse(Json.write(x)));}
    static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Map<String,Object> chain(Map<String,Object> x,int i){return m(l(x.get("references")).get(i));}
    static Map<String,Object> snap(Map<String,Object> x,int i){return m(m(l(chain(x,i).get("revisions")).get(0)).get("snapshot"));}
    static void reseal(Map<String,Object> x){for(Object c:l(x.get("references"))){String prev="";for(Object raw:l(m(c).get("revisions"))){Map<String,Object> e=m(raw);e.put("previous_hash",prev);prev=CityPlanningReference.eventHash(e);e.put("event_sha256",prev);}}}
    static void docEdit(Map<String,Object> x,String id,Consumer<Map<String,Object>> action){Map<String,Object> d=m(m(x.get("documents")).get(id)),body=m(Json.parse(d.get("raw_json").toString()));action.accept(body);String raw=Json.write(body);d.put("raw_json",raw);d.put("raw_sha256",CityPlanningReference.rawHash(raw));}
    static void factEdit(Map<String,Object> x,String review,String from,String to,Consumer<Map<String,Object>> action){docEdit(x,review,r->{for(Object f:l(r.get("facts")))if(from.equals(m(f).get("from_city"))&&to.equals(m(f).get("to_city")))action.accept(m(f));});}
    static void both(Map<String,Object> x,String round,String from,String to,Consumer<Map<String,Object>> action){for(String role:List.of("A","B"))factEdit(x,round+"-"+role,from,to,action);}
    static void rejected(Map<String,Object> base,String name,Consumer<Map<String,Object>> action){Map<String,Object> d=copy(base);action.accept(d);reseal(d);try{CityPlanningReference.heads(d);throw new AssertionError("accepted "+name);}catch(IllegalArgumentException e){checks++;}}
    static Map<String,Object> lookup(Map<?,?> data,String a,String b,LocalDate date){String ap="",bp="";for(var e:RegionDirectory.CITIES.entrySet()){if(e.getValue().contains(a))ap=e.getKey();if(e.getValue().contains(b))bp=e.getKey();}return CityPlanningReference.lookup(data,ap,a,bp,b,date);}
    static void expectPending(Map<?,?> d,String a,String b,LocalDate date,String reason){Map<?,?> r=lookup(d,a,b,date);ok("planning_pending".equals(r.get("status"))&&reason.equals(r.get("reason")),reason+" "+r);}
    static void append(Map<String,Object> d,int index,String status){Map<String,Object> ch=chain(d,index),first=m(l(ch.get("revisions")).get(0));Map<String,Object> s=copy(m(first.get("snapshot")));s.put("version",2);s.put("status",status);Map<String,Object> e=new LinkedHashMap<>();e.put("snapshot",s);e.put("previous_hash",first.get("event_sha256"));e.put("change_reason","Test explicit later withdrawal");e.put("event_sha256",CityPlanningReference.eventHash(e));l(ch.get("revisions")).add(e);d.put("library_revision",2);}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Usage: CityPlanningReferenceTest private-draft.json NEW-output-directory");
        Map<String,Object> base=m(Json.parse(Files.readString(Path.of(args[0]))));Path dir=Path.of(args[1]);Files.createDirectories(dir);
        String previous=System.getProperty(CityPlanningReference.PROPERTY);System.setProperty(CityPlanningReference.PROPERTY,"");ok(CityPlanningReference.bundle().isEmpty(),"default unconfigured is empty");if(previous==null)System.clearProperty(CityPlanningReference.PROPERTY);else System.setProperty(CityPlanningReference.PROPERTY,previous);
        ok(CityPlanningReference.heads(base).size()==6,"six independently read pairs");
        Path prepared=dir.resolve("city-planning-six.private.json");CityPlanningReference.prepare(base,prepared,TODAY);Map<?,?> loaded=CityPlanningReference.load(prepared);ok(CityPlanningReference.SCHEMA.equals(loaded.get("version")),"actual prepared loader");
        System.setProperty(CityPlanningReference.PROPERTY,prepared.toString());ok(CityPlanningReference.bundle().containsKey("references"),"explicit local property reads separate library");if(previous==null)System.clearProperty(CityPlanningReference.PROPERTY);else System.setProperty(CityPlanningReference.PROPERTY,previous);
        List<Object> results=new ArrayList<>();for(Object raw:l(base.get("references"))){Map<?,?> s=CityPlanningReference.map(CityPlanningReference.map(CityPlanningReference.list(CityPlanningReference.map(raw).get("revisions")).get(0)).get("snapshot"));String a=CityPlanningReference.text(CityPlanningReference.map(s.get("from")),"city"),b=CityPlanningReference.text(CityPlanningReference.map(s.get("to")),"city");for(String[] direction:List.of(new String[]{a,b},new String[]{b,a})){Map<String,Object> r=lookup(loaded,direction[0],direction[1],TODAY);ok("planning_reference".equals(r.get("status")),"actual "+Arrays.toString(direction)+" "+r);ok(!r.containsKey("eligibility")&&!r.containsKey("score")&&Boolean.FALSE.equals(r.get("time_score_applicable"))&&Boolean.FALSE.equals(r.get("air_fallback_trigger"))&&Boolean.FALSE.equals(r.get("rail_exclusion_complete")),"no strict/scoring/flight promotion");ok(!Json.write(r).contains("reference_value_minutes_internal")&&!Json.write(r).contains("lower_bound_minutes\":"),"no minute-bound output");results.add(Map.of("from",direction[0],"to",direction[1],"output",r));}}
        ok("approximate".equals(m(lookup(loaded,"襄阳","宜昌",TODAY).get("outbound")).get("precision")),"approximate kept");
        ok("nominal_hour".equals(m(lookup(loaded,"武汉","长沙",TODAY).get("outbound")).get("precision")),"hour kept nominal");
        ok("1–2小时档".equals(lookup(loaded,"襄阳","宜昌",TODAY).get("planning_time_band")),"110/111-minute original approximate reference band");
        ok("planning_1_2h".equals(lookup(loaded,"襄阳","宜昌",TODAY).get("planning_band_id")),"stable band id from unbuffered reference");
        ok("苏州站".equals(m(lookup(loaded,"上海","苏州",TODAY).get("inbound")).get("from_endpoint"))&&"苏州".equals(m(m(lookup(loaded,"上海","苏州",TODAY).get("inbound")).get("from")).get("city")),"explicit return endpoint and city");
        ok(l(m(lookup(loaded,"天门","武汉",TODAY).get("outbound")).get("read_disagreement_fields")).contains("preparation_status"),"readiness disagreement retained");
        ok("unresolved_between_reads".equals(m(lookup(loaded,"西安","十堰",TODAY).get("outbound")).get("directness")),"directness disagreement retained");
        ok(Boolean.TRUE.equals(m(m(m(snap(base,4).get("inbound")).get("source_policy")).get("external_restrictions")).get("direct_article_republication_restricted")),"direct article republication restriction retained");
        expectPending(loaded,"武汉","上海",TODAY,"city_pair_unobserved");
        expectPending(loaded,"襄阳","宜昌",LocalDate.of(2026,9,30),"source_or_review_stale");
        expectPending(loaded,"武汉","郑州",LocalDate.of(2026,10,15),"source_or_review_stale");
        expectPending(loaded,"苏州","上海",LocalDate.of(2026,12,8),"source_or_review_stale");
        expectPending(Map.of(),"武汉","长沙",TODAY,"planning_library_not_configured");
        for(int minutes:List.of(209,210,227,239)){ok(CityPlanningReference.band(minutes,70).equals("3–4小时档"),"unbuffered outbound below cutoff");ok(CityPlanningReference.band(70,minutes).equals("3–4小时档"),"unbuffered return below cutoff");}ok(CityPlanningReference.band(240,70).isEmpty(),"exact240 pending");ok(CityPlanningReference.band(70,240).isEmpty(),"return cutoff independent");ok(CityPlanningReference.band(300,70).isEmpty(),"long not exclusion");ok(CityPlanningReference.band(30,30).equals("不超过1小时档"),"first coarse bucket");
        rejected(base,"schema",d->d.put("version","public-city-travel-reference-v1"));
        rejected(base,"precise gate",d->d.put("strict_runtime_admission",true));
        rejected(base,"government license",d->d.put("license_claimed",true));
        rejected(base,"buffer tamper",d->m(d.get("policy")).put("buffer_minutes",0));
        rejected(base,"empty city",d->m(snap(d,0).get("from")).put("id",""));
        rejected(base,"wrong city id",d->m(snap(d,0).get("from")).put("id","C168"));
        rejected(base,"swapped direction",d->{Map<String,Object>s=snap(d,0);Object a=s.get("outbound");s.put("outbound",s.get("inbound"));s.put("inbound",a);});
        rejected(base,"mirrored direction",d->snap(d,0).put("inbound",snap(d,0).get("outbound")));
        rejected(base,"duplicate pair",d->{Map<String,Object> c=copy(chain(d,0));c.put("reference_id","different-id");m(m(l(c.get("revisions")).get(0)).get("snapshot")).put("reference_id","different-id");l(d.get("references")).add(c);});
        rejected(base,"source external restriction",d->m(m(m(snap(d,0).get("outbound")).get("source_policy")).get("external_restrictions")).put("minimal_fact_reuse_prohibited",true));
        rejected(base,"source research only",d->m(m(m(snap(d,0).get("outbound")).get("source_policy")).get("external_restrictions")).put("source_research_only",true));
        rejected(base,"automated source ban",d->m(m(m(snap(d,0).get("outbound")).get("source_policy")).get("external_restrictions")).put("automation_prohibited",true));
        rejected(base,"staging flag alone insufficient",d->{d.put("runtime_enabled",false);snap(d,0).put("inbound",Map.of("approved",true));});
        rejected(base,"raw sha",d->m(m(d.get("documents")).get("r1-A")).put("raw_sha256","0".repeat(64)));
        rejected(base,"source bytes",d->docEdit(d,"r1-SRC01",s->s.put("url","https://example.org/not-the-source")));
        rejected(base,"source embedded restriction",d->docEdit(d,"r1-SRC01",s->s.put("source_research_only",true)));
        rejected(base,"original source revoked",d->docEdit(d,"r1-SRC01",s->s.put("status","revoked")));
        rejected(base,"read event absent",d->docEdit(d,"r1-B",s->s.put("read_events",List.of())));
        rejected(base,"same reader",d->docEdit(d,"r1-B",s->m(s.get("reviewer")).put("agent_id","/root/transport_reference_round")));
        rejected(base,"B saw A",d->docEdit(d,"r1-B",s->m(s.get("reviewer")).put("prior_A_result_access",true)));
        rejected(base,"false blind claim",d->docEdit(d,"r1-B",s->m(s.get("reviewer")).put("fresh_blind_claimed",true)));
        rejected(base,"missing source review",d->docEdit(d,"r1-B",s->s.put("source_reviews",List.of())));
        rejected(base,"one minute core difference",d->factEdit(d,"r1-B","苏州","上海",f->m(f.get("canonical")).put("calculated_minutes",35)));
        rejected(base,"two readers same wrong arithmetic",d->both(d,"r1","苏州","上海",f->m(f.get("canonical")).put("calculated_minutes",35)));
        rejected(base,"overnight fabricated",d->both(d,"r1","苏州","上海",f->m(f.get("canonical")).put("arrival_day_offset",1)));
        rejected(base,"departure picked as arrival",d->both(d,"r1","苏州","上海",f->{m(f.get("canonical")).put("arrival_clock","06:35");m(f.get("spans")).put("arrival",m(f.get("spans")).get("departure"));}));
        rejected(base,"different service",d->both(d,"r1","苏州","上海",f->m(f.get("canonical")).put("service_id","C3874")));
        rejected(base,"mix clocks across same paragraph services",d->{Map<?,?> source=CityPlanningReference.doc(d,"r1-SRC01");String unit=CityPlanningReference.units(source).get("U04");int at=unit.indexOf("22:44");both(d,"r1","上海","苏州",f->{m(f.get("canonical")).put("arrival_clock","22:44");m(f.get("canonical")).put("calculated_minutes",26);m(f.get("spans")).put("arrival",List.of(Map.of("unit_id","U04","start",at,"end",at+5,"text","22:44")));});});
        rejected(base,"span changed",d->factEdit(d,"r1-A","苏州","上海",f->m(l(m(f.get("spans")).get("from")).get(0)).put("start",41)));
        rejected(base,"approx to reported",d->both(d,"r1","襄阳","宜昌",f->{m(f.get("canonical")).put("duration_kind","reported_minute");m(f.get("canonical")).put("reported_minutes",110);m(f.get("canonical")).put("nominal_value",null);m(f.get("canonical")).put("nominal_unit",null);}));
        rejected(base,"hour to exact minute",d->both(d,"r2","武汉","长沙",f->{m(f.get("canonical")).put("duration_kind","reported_minute");m(f.get("canonical")).put("reported_minutes",60);m(f.get("canonical")).put("nominal_value",null);m(f.get("canonical")).put("nominal_unit",null);}));
        rejected(base,"non ISO review day",d->snap(d,0).put("reviewed_on","2026-9-8"));
        rejected(base,"impossible review day",d->snap(d,0).put("reviewed_on","2026-02-30"));
        rejected(base,"planned service",d->both(d,"r2","武汉","长沙",f->m(f.get("canonical")).put("service_state","future_plan")));
        rejected(base,"temporary scope",d->both(d,"r1","苏州","上海",f->m(f.get("canonical")).put("service_state","temporary_summer_service")));
        rejected(base,"source date silently renewed",d->both(d,"r1","襄阳","宜昌",f->m(f.get("canonical")).put("published_on","2026-09-08")));
        rejected(base,"review silently renewed without new reads",d->{Map<String,Object>s=snap(d,0);s.put("reviewed_on","2026-09-09");for(String leg:List.of("outbound","inbound"))m(m(s.get(leg)).get("source_policy")).put("checked_on","2026-09-09");});
        Map<String,Object> withdrawn=copy(base);append(withdrawn,0,"revoked");expectPending(withdrawn,"苏州","上海",TODAY,"reference_revoked");
        CityPlanningReference.continuation(CityPlanningReference.anchor(base),withdrawn);checks++;
        try{CityPlanningReference.continuation(CityPlanningReference.anchor(withdrawn),base);throw new AssertionError("rollback");}catch(IllegalArgumentException expected){checks++;}
        rejected(base,"prior chain modified",d->{append(d,0,"revoked");m(m(l(chain(d,0).get("revisions")).get(1)).get("snapshot")).put("version",3);});
        Path noAnchor=dir.resolve("no-anchor.json");Files.writeString(noAnchor,Json.write(base),StandardOpenOption.CREATE_NEW);ok(CityPlanningReference.load(noAnchor).containsKey("load_status"),"missing anchor closed");
        Path altered=dir.resolve("altered.json");CityPlanningReference.prepare(base,altered,TODAY);Map<String,Object> changed=copy(base);changed.put("library_revision",2);Files.writeString(altered,Json.write(changed),StandardOpenOption.TRUNCATE_EXISTING);ok(CityPlanningReference.load(altered).containsKey("load_status"),"modified file against anchor closed");
        try{CityPlanningReference.prepare(base,prepared,TODAY);throw new AssertionError("overwrite original");}catch(IllegalArgumentException expected){checks++;}
        Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("checks_passed",checks);receipt.put("as_of",TODAY.toString());receipt.put("pairs",6);receipt.put("directions",12);receipt.put("prepared_file",prepared.toString());receipt.put("prepared_raw_sha256",CityPlanningReference.rawHash(Files.readString(prepared)));receipt.put("production_changed",false);receipt.put("integration_enabled",false);receipt.put("results",results);Files.writeString(dir.resolve("validation.json"),Json.write(receipt)+"\n",StandardOpenOption.CREATE_NEW);System.out.println(Json.write(Map.of("checks_passed",checks,"pairs",6,"directions",12,"prepared_file",prepared.toString())));
    }
}
