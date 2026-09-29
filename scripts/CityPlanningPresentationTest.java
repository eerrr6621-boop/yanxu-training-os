package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static com.training.CuratedPlanningIntegrationTest.*;

/** Synthetic display/trust-boundary tests. --actual is explicit read-only public-fixture regression. */
public final class CityPlanningPresentationTest {
    static int checks,sequence;static Path work;static final LocalDate DAY=LocalDate.of(2026,9,8);
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void equal(Object a,Object b,String why){check(CityPlanningReference.same(a,b),why+": "+a+" != "+b);}
    static String sha(Path p)throws Exception{return CityPlanningReference.rawHash(Files.readString(p));}
    static Map<String,Object> read(Path p)throws Exception{return m(Json.parse(Files.readString(p)));}
    static Map<String,Object> noView(Map<String,Object> v){Map<String,Object> copy=new LinkedHashMap<>(v);copy.remove("presentation");return copy;}
    static Object withoutViews(Object v){if(v instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();map.forEach((k,value)->{if(!k.equals("presentation"))result.put(k.toString(),withoutViews(value));});return result;}if(v instanceof List<?> list)return list.stream().map(CityPlanningPresentationTest::withoutViews).toList();return v;}
    static Map<String,Object> q(CityPlanningReference.Index index){return index.lookup("江苏","南京","上海","上海");}
    static Map<String,Object> q(CityPlanningReference.Index index,Map<?,?> from,Map<?,?> to){return index.lookup(from.get("province").toString(),from.get("city").toString(),to.get("province").toString(),to.get("city").toString());}
    static Map<String,Object> row(Map<String,Object> data,int n){return m(l(data.get("rows")).get(n));}
    static Path save(Map<String,Object> data,boolean badAnchor)throws Exception{
        Path p=work.resolve("annotations-"+(++sequence)+".json");Files.writeString(p,Json.write(data)+"\n",StandardOpenOption.CREATE_NEW);
        Map<String,Object> anchor=obj("version",CityPlanningPresentation.ANCHOR,"presentation_revision",data.get("presentation_revision"),"presentation_sha256",badAnchor?"0".repeat(64):CityPlanningReference.hash(data));
        Files.writeString(Path.of(p+".integrity.json"),Json.write(anchor)+"\n",StandardOpenOption.CREATE_NEW);return p;
    }
    static Map<String,Object> annotation(Path file,Map<String,Object> library,Path collection)throws Exception{
        Map<String,Object> s=snapshot(library);List<Object> rows=new ArrayList<>();Map<String,Object> contexts=new LinkedHashMap<>();
        for(String side:List.of("outbound","inbound")){Map<String,Object> ref=m(s.get(side)),source=doc(library,(String)ref.get("source_document")),a=doc(library,(String)ref.get("review_a"));
            Map<String,Object> fact=l(a.get("facts")).stream().map(CuratedPlanningIntegrationTest::m).filter(f->source.get("source_id").equals(f.get("source_id"))&&m(f.get("canonical")).get("from_registry_id").equals(ref.get("from_registry_id"))).findFirst().orElseThrow();
            Map<String,Object> c=m(fact.get("canonical"));String unit=(String)m(l(m(fact.get("spans")).get("duration")).get(0)).get("unit_id");Map<String,Object> span=copy(m(l(m(fact.get("spans")).get("duration")).get(0)));
            Map<?,?> from=m(s.get(side.equals("outbound")?"from":"to")),to=m(s.get(side.equals("outbound")?"to":"from"));String context=side.equals("outbound")?"synthetic-fastest":null;
            if(context!=null)contexts.put(context,obj("url",source.get("url"),"published_on",source.get("published_on"),"excerpt","合成测试：以最快运行时间计算","read_locator","SYNTHETIC, no real original reading","scope","Synthetic out direction only"));
            rows.add(obj("row_id","synthetic-"+side,"member_id","synthetic","library_file",file.toString(),"library_file_sha256",sha(file),"library_anchor_sha256",sha(Path.of(file+".integrity.json")),
                "reference_id",s.get("reference_id"),"reference_version",s.get("version"),"reference_event_sha256",m(l(m(l(library.get("references")).get(0)).get("revisions")).get(0)).get("event_sha256"),"direction",side,
                "from_registry_id",from.get("id"),"to_registry_id",to.get("id"),"from_city",from.get("city"),"to_city",to.get("city"),"source_document",ref.get("source_document"),"source_sha256",m(m(library.get("documents")).get(ref.get("source_document"))).get("raw_sha256"),"source_url",source.get("url"),"source_published_on",source.get("published_on"),
                "source_qualifier",context==null?"general_reported":"reported_fastest","precision",m(c.get("duration")).get("kind"),"evidence",new ArrayList<>(List.of(span)),"publication_context",context,"note","Synthetic annotation only; not a source reading"));
        }
        return obj("version",CityPlanningPresentation.VERSION,"presentation_revision",1,"purpose","presentation_only","created_on",DAY.toString(),"annotation_draft_sha256","a".repeat(64),"review",obj("status","approved","reviewer_a","synthetic-A","reviewer_b","synthetic-B","mode","informed_retained_evidence_annotation_crosscheck","reviewed_on",DAY.toString(),"record_locators",List.of("Synthetic test only")),"collection_file",collection.toString(),"collection_file_sha256",sha(collection),"publication_context",contexts,"rows",rows);
    }
    static Map<String,Object> fit(Map<String,Object> planning){return obj("local_priority",false,"planning_reference",planning,"route_reference",obj("status","rail_reference_pending","eligibility","unknown","missing_directions",List.of("Synthetic missing strict proof; no railway exclusion evidence")));}
    static void rejectedView(Path library,Map<String,Object> annotation,Consumer<Map<String,Object>> edit,String why)throws Exception{
        Map<String,Object> bad=copy(annotation);edit.accept(bad);var catalog=CityPlanningPresentation.load(save(bad,false),CityPlanningPresentation.VERSION);
        Map<String,Object> value=q(CityPlanningReference.index(library,DAY,catalog));equal(m(value.get("presentation")).get("status"),"pending",why);equal(noView(value),q(CityPlanningReference.index(library,DAY)),why+" cannot change original result");
    }
    static void synthetic()throws Exception{
        work=Files.createTempDirectory("city-presentation-synthetic-");
        var library=bundle(scalar(false,"approximate","约90分钟",90),scalar(true,"nominal_hour","1小时",1));Path file=work.resolve("library.json");CityPlanningReference.prepare(library,file,DAY);
        Path collection=work.resolve("collection.json");CityPlanningCollection.prepare("synthetic-display",Map.of("synthetic",file),null,collection,DAY);
        Map<String,Object> annotations=annotation(file,library,collection);Path viewFile=save(annotations,false);var view=CityPlanningPresentation.load(viewFile,CityPlanningPresentation.VERSION);equal(view.reason(),"","valid annotation loads");
        var original=q(CityPlanningReference.index(file,DAY));var displayed=q(CityPlanningReference.index(file,DAY,view));equal(noView(displayed),original,"default full output unchanged");
        Map<String,Object> shown=m(displayed.get("presentation"));equal(shown.get("status"),"ready","explicit v1 displays");equal(m(shown.get("outbound")).get("source_qualifier"),"reported_fastest","fastest independent axis");equal(m(shown.get("outbound")).get("precision"),"approximate","approximate stays approximate");equal(m(m(shown.get("inbound")).get("duration")).get("unit"),"hour","nominal return unit retained");
        Map<String,Object> reverse=CityPlanningReference.index(file,DAY,view).lookup("上海","上海","江苏","南京");equal(m(m(reverse.get("presentation")).get("outbound")).get("source_qualifier"),"general_reported","reverse uses own annotation");
        equal(noView(q(CityPlanningReference.collectionIndex(collection,DAY,view))),q(CityPlanningReference.collectionIndex(collection,DAY)),"collection old fields unchanged");
        DispatchPreference pref=DispatchPreference.from(Map.of("training_province","上海","training_city","上海","training_mode","线下"),null);
        for(int cutoff:List.of(40,41,90,91,240)){equal(CityPlanningSelection.include(fit(displayed),pref,cutoff),CityPlanningSelection.include(fit(original),pref,cutoff),"display never changes selection "+cutoff);}
        check(CityPlanningSelection.include(fit(displayed),pref,91)&&!CityPlanningSelection.include(fit(displayed),pref,90),"synthetic selection is non-vacuous at90/91");
        String limit=System.getProperty("dispatch.nearby.max.minutes");try{System.setProperty("dispatch.nearby.max.minutes","91");List<Map<String,Object>> beforeRows=new ArrayList<>(),afterRows=new ArrayList<>();int[] scores={100,90,80,80,70};String[] levels={"讲师","讲师","讲师","高级讲师","特级讲师"};
            for(int i=0;i<scores.length;i++){beforeRows.add(obj("teacher_id",i+1L,"ranking_score",scores[i],"teacher_level",levels[i],"dispatch_fit",fit(copy(original))));afterRows.add(obj("teacher_id",i+1L,"ranking_score",scores[i],"teacher_level",levels[i],"dispatch_fit",fit(copy(displayed))));}
            Map<String,Object> beforeRank=NearbySelection.select(beforeRows,pref,3),afterRank=NearbySelection.select(afterRows,pref,3);equal(withoutViews(afterRank),beforeRank,"complete selection ranking unchanged after stripping display only");equal(l(afterRank.get("selected")).stream().map(CuratedPlanningIntegrationTest::m).map(r->r.get("teacher_id")).toList(),List.of(1L,2L,4L,3L),"whole near pool then match level and last-place ties");
        }finally{if(limit==null)System.clearProperty("dispatch.nearby.max.minutes");else System.setProperty("dispatch.nearby.max.minutes",limit);}
        try{m(shown.get("outbound")).put("source_qualifier","evil");throw new AssertionError("mutable display");}catch(UnsupportedOperationException expected){checks++;}
        rejectedView(file,annotations,d->row(d,0).put("source_qualifier","__proto__"),"unknown qualifier closed");
        rejectedView(file,annotations,d->l(d.get("rows")).add(copy(row(d,0))),"duplicate/conflicting direction closed");
        rejectedView(file,annotations,d->row(d,0).put("direction","inbound"),"wrong actual direction closed");
        for(String field:List.of("library_file_sha256","library_anchor_sha256","reference_event_sha256","source_sha256"))rejectedView(file,annotations,d->row(d,0).put(field,"0".repeat(64)),field+" closed");
        rejectedView(file,annotations,d->row(d,0).put("source_document","other-source"),"wrong source doc closed");
        rejectedView(file,annotations,d->row(d,0).put("precision","reported_minutes"),"precision cannot be relabelled");
        rejectedView(file,annotations,d->row(d,0).put("from_registry_id",row(d,1).get("from_registry_id")),"wrong registry closed");
        rejectedView(file,annotations,d->row(d,0).put("reference_version",2),"wrong version closed");
        rejectedView(file,annotations,d->m(l(row(d,0).get("evidence")).get(0)).put("text","invented"),"span mismatch closed");
        rejectedView(file,annotations,d->m(m(d.get("publication_context")).get("synthetic-fastest")).put("url","https://example.invalid/other"),"external context source binding");
        rejectedView(file,annotations,d->{row(d,0).put("publication_context",null);},"no invented fastest from number/rationale");
        rejectedView(file,annotations,d->l(d.get("rows")).remove(1),"one missing annotation closes both display legs");
        rejectedView(file,annotations,d->m(d.get("review")).put("status","revoked"),"revoked annotations fail closed");
        rejectedView(file,annotations,d->m(d.get("review")).put("reviewer_b","synthetic-A"),"no fake second annotation reader");
        rejectedView(file,annotations,d->m(d.get("review")).put("reviewed_on","2026-09-09"),"future review cannot show now");
        var badAnchor=CityPlanningPresentation.load(save(annotations,true),CityPlanningPresentation.VERSION);check(badAnchor.reason().contains("anchor_mismatch"),"sidecar anchor checked");
        Path rollback=save(annotations,false);CityPlanningPresentation.load(rollback,CityPlanningPresentation.VERSION);Map<String,Object> newer=copy(annotations);newer.put("presentation_revision",2);
        Files.writeString(rollback,Json.write(newer)+"\n",StandardOpenOption.TRUNCATE_EXISTING);Files.writeString(Path.of(rollback+".integrity.json"),Json.write(CityPlanningPresentation.anchor(newer))+"\n",StandardOpenOption.TRUNCATE_EXISTING);equal(CityPlanningPresentation.load(rollback,CityPlanningPresentation.VERSION).reason(),"","synthetic new presentation revision loads");
        Files.writeString(rollback,Json.write(annotations)+"\n",StandardOpenOption.TRUNCATE_EXISTING);Files.writeString(Path.of(rollback+".integrity.json"),Json.write(CityPlanningPresentation.anchor(annotations))+"\n",StandardOpenOption.TRUNCATE_EXISTING);check(CityPlanningPresentation.load(rollback,CityPlanningPresentation.VERSION).reason().contains("rollback"),"same path cannot roll back sidecar revision");
        equal(m(q(CityPlanningReference.index(file,DAY,CityPlanningPresentation.load(null,"unknown"))).get("presentation")).get("status"),"pending","unknown version closed without file read");
        Map<String,Object> unknown=copy(annotations);row(unknown,0).put("source_qualifier","unspecified");row(unknown,0).put("publication_context",null);var unknownResult=q(CityPlanningReference.index(file,DAY,CityPlanningPresentation.load(save(unknown,false),CityPlanningPresentation.VERSION)));equal(m(m(unknownResult.get("presentation")).get("outbound")).get("source_qualifier"),"unspecified","unknown stays unknown not fastest");
        Map<String,Object> wrongCollection=copy(annotations);wrongCollection.put("collection_file_sha256","0".repeat(64));equal(m(q(CityPlanningReference.collectionIndex(collection,DAY,CityPlanningPresentation.load(save(wrongCollection,false),CityPlanningPresentation.VERSION))).get("presentation")).get("status"),"pending","collection hash closed");
        var stale=CityPlanningReference.index(file,DAY.plusDays(91),view);equal(q(stale),q(CityPlanningReference.index(file,DAY.plusDays(91))),"stale source remains exact old pending without display");
        String old=System.getProperty(CityPlanningPresentation.VERSION_PROPERTY),oldPath=System.getProperty(CityPlanningPresentation.FILE_PROPERTY);try{System.clearProperty(CityPlanningPresentation.VERSION_PROPERTY);check(CityPlanningPresentation.configured()==null,"default display disabled");System.setProperty(CityPlanningPresentation.VERSION_PROPERTY,"unknown");check(CityPlanningPresentation.configured().reason().contains("unsupported"),"configured unknown version closed");System.setProperty(CityPlanningPresentation.VERSION_PROPERTY,CityPlanningPresentation.VERSION);System.setProperty(CityPlanningPresentation.FILE_PROPERTY,"\u0000");check(CityPlanningPresentation.configured().reason().contains("path_invalid"),"malformed optional path cannot break old recommendation");}finally{if(old==null)System.clearProperty(CityPlanningPresentation.VERSION_PROPERTY);else System.setProperty(CityPlanningPresentation.VERSION_PROPERTY,old);if(oldPath==null)System.clearProperty(CityPlanningPresentation.FILE_PROPERTY);else System.setProperty(CityPlanningPresentation.FILE_PROPERTY,oldPath);}
        System.out.println("City planning presentation synthetic: "+checks+" assertions passed; no model, real people, network or production; "+work);
    }
    static void actual(String[] args)throws Exception{
        Path sidecar=Path.of(args[1]),baselinePath=Path.of(args[2]),strictFile=Path.of(args[3]),output=Path.of(args[4]);Files.createDirectory(output);
        Map<String,Object> baseline=read(baselinePath);Path collection=Path.of((String)baseline.get("collection_file"));Map<Path,String> pins=new LinkedHashMap<>();
        for(var e:m(baseline.get("input_pins")).entrySet()){Path p=Path.of(e.getKey());equal(sha(p),e.getValue(),"baseline input unchanged before test "+p.getFileName());pins.put(p,sha(p));}pins.put(collection,sha(collection));pins.put(Path.of(collection+".integrity.json"),sha(Path.of(collection+".integrity.json")));pins.put(sidecar,sha(sidecar));pins.put(Path.of(sidecar+".integrity.json"),sha(Path.of(sidecar+".integrity.json")));
        var catalog=CityPlanningPresentation.load(sidecar,CityPlanningPresentation.VERSION);equal(catalog.reason(),"","approved actual sidecar loads");var old=CityPlanningReference.collectionIndex(collection,DAY);var current=CityPlanningReference.collectionIndex(collection,DAY,catalog);equal(current.directionCount(),36,"36 real directions");equal(current.sharedPairCount(),1,"one shared, no duplicated observations");
        Map<String,String> settings=new LinkedHashMap<>();for(String key:List.of(CityPlanningReference.PROPERTY,CityPlanningCollection.PROPERTY,CityPlanningPresentation.VERSION_PROPERTY,CityPlanningPresentation.FILE_PROPERTY))settings.put(key,System.getProperty(key));
        try{System.setProperty(CityPlanningReference.PROPERTY,"");System.setProperty(CityPlanningCollection.PROPERTY,collection.toString());System.setProperty(CityPlanningPresentation.VERSION_PROPERTY,CityPlanningPresentation.VERSION);System.setProperty(CityPlanningPresentation.FILE_PROPERTY,sidecar.toString());
            var configured=CityPlanningReference.index(DAY,CityPlanningPresentation.configured());var stillLegacy=CityPlanningReference.index(DAY);
            for(Object raw:l(baseline.get("planning_outputs"))){Map<String,Object> saved=m(raw),from=m(saved.get("from")),to=m(saved.get("to"));equal(q(configured,from,to),q(current,from,to),"actual explicitly configured business entry parity");equal(q(stillLegacy,from,to),q(old,from,to),"legacy entry remains legacy despite optional settings");}
        }finally{for(var e:settings.entrySet())if(e.getValue()==null)System.clearProperty(e.getKey());else System.setProperty(e.getKey(),e.getValue());}
        List<Object> outputs=new ArrayList<>();int directions=0,shared=0;
        for(Object raw:l(baseline.get("planning_outputs"))){Map<String,Object> saved=m(raw),from=m(saved.get("from")),to=m(saved.get("to")),legacy=q(old,from,to),value=q(current,from,to);equal(legacy,saved.get("reference"),"actual old complete output equals saved revision5");equal(noView(value),legacy,"removing presentation restores every old field");
            if(Boolean.TRUE.equals(saved.get("shared"))){shared++;equal(value,legacy,"shared unchanged including no presentation");check(value==q(current,to,from),"shared inverse remains same object");}
            else{directions++;equal(m(value.get("presentation")).get("status"),"ready","actual display ready "+from.get("city")+" to "+to.get("city"));for(int cutoff:List.of(40,41,240))equal(CityPlanningSelection.underConfiguredLimit(value,cutoff),CityPlanningSelection.underConfiguredLimit(legacy,cutoff),"actual cutoff unchanged");}
            outputs.add(obj("from",from,"to",to,"shared",saved.get("shared"),"reference",value));
        }
        equal(directions,36,"all36 annotated");equal(shared,1,"shared count unchanged");
        for(LocalDate day:List.of(LocalDate.of(2026,9,20),LocalDate.of(2026,9,21))){var before=CityPlanningReference.collectionIndex(collection,day);var after=CityPlanningReference.collectionIndex(collection,day,catalog);for(Object raw:l(baseline.get("planning_outputs"))){Map<String,Object> saved=m(raw),from=m(saved.get("from")),to=m(saved.get("to")),value=q(after,from,to);equal(noView(value),q(before,from,to),"actual dated old output unchanged "+day);if("shanghai_changzhou".equals(saved.get("member")))equal(value.containsKey("presentation"),day.getDayOfMonth()==20,"expired Shanghai-Changzhou has no affirmative display");}}
        String oldFile=System.getProperty("dispatch.public.city.references.file"),oldDir=System.getProperty("dispatch.public.city.references.dir");Map<?,?> strict;
        try{System.setProperty("dispatch.public.city.references.file",strictFile.toString());System.setProperty("dispatch.public.city.references.dir","");strict=CityTravelReference.publicBundle("北京","北京");}finally{if(oldFile==null)System.clearProperty("dispatch.public.city.references.file");else System.setProperty("dispatch.public.city.references.file",oldFile);if(oldDir==null)System.clearProperty("dispatch.public.city.references.dir");else System.setProperty("dispatch.public.city.references.dir",oldDir);}
        int strictDirections=0;for(Object raw:l(baseline.get("strict_outputs"))){Map<String,Object> saved=m(raw),leg=m(m(saved.get("result")).get("outbound"));Map<String,Object> now=CityTravelReference.lookup(strict,(String)leg.get("from_province"),(String)leg.get("from_city"),(String)leg.get("to_province"),(String)leg.get("to_city"),DAY);equal(now,saved.get("result"),"strict whole output unchanged");strictDirections++;}equal(strictDirections,38,"strict19 pair outputs unchanged");
        for(var e:pins.entrySet())equal(sha(e.getKey()),e.getValue(),"all pinned files unchanged after actual read-only regression");
        Files.writeString(output.resolve("actual-result.json"),Json.write(obj("checks",checks,"directional_display_rows",directions,"shared_objects_unchanged",shared,"strict_directions_unchanged",strictDirections,"aggregate_distinct_pairs",baseline.get("aggregate_distinct_pairs"),"planning_outputs",outputs,"default_old_outputs_unchanged",true,"selection_policy_changed",false,"production_changed",false,"model_called",false,"input_pins",pins.entrySet().stream().map(e->obj("file",e.getKey().toString(),"sha256",e.getValue())).toList()))+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("Actual presentation: "+checks+" assertions passed;36 directions displayed; shared1+strict38 unchanged;38 distinct references unchanged; "+output);
    }
    public static void main(String[] args)throws Exception{
        if(args.length==2&&args[0].equals("--anchor")){System.out.println(Json.write(CityPlanningPresentation.anchor(read(Path.of(args[1])))));return;}
        if(args.length==5&&args[0].equals("--actual")){actual(args);return;}
        check(args.length==0,"no implicit actual source access");synthetic();
    }
}
