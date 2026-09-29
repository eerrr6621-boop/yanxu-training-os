package com.training;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static com.training.CuratedCityDurationTest.*;

/** Synthetic fixtures only. Literal names and invented readers do not prove a route. */
public final class PublicUiStopTableTest {
    static int checks;
    static void ok(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void rejects(Runnable work,String reason){try{work.run();throw new AssertionError("accepted invalid: "+reason);}catch(IllegalArgumentException ex){ok(ex.getMessage().contains(reason),reason+" got "+ex.getMessage());}}
    static Map<String,Object> unit(String id,String value){return m("unit_id",id,"text",value,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(value));}
    static Map<String,Object> capture(String query,String header,String date,List<List<String>> cells){
        List<Object> units=new ArrayList<>();units.add(unit("query",query));units.add(unit("date",date));units.add(unit("heading",header));
        List<String> columns=new ArrayList<>();int c=0;for(String name:List.of("站序","车站","车次","出发时间","到达时间","历时")){String id="column"+(c++);columns.add(id);units.add(unit(id,name));}
        List<Object> rows=new ArrayList<>();for(int i=0;i<cells.size();i++){List<String> ids=new ArrayList<>();for(int j=0;j<cells.get(i).size();j++){String id="r"+i+"c"+j;ids.add(id);units.add(unit(id,cells.get(i).get(j)));}rows.add(m("cell_units",ids));}
        return m("source_id","synthetic-table-"+query,"source_type",PublicUiStopTable.SOURCE_TYPE,"url","https://hzfw.12306.cn/zgzfw/resources/web/skcx.html","published_on",null,
            "date_basis",m("kind",PublicUiCitySample.DATE_BASIS,"query_date",date,"service_date",date),
            "sample_scope","selected_service_sample_not_fastest_typical_or_upper_bound","duration_qualifier","general_reported","synthetic_only",true,
            "units",units,"table_capture",m("schema",PublicUiStopTable.LAYOUT,"query_train_unit","query","service_date_unit","date","heading_unit","heading","column_units",columns,"rows",rows));
    }
    static Map<String,Object> fixture(boolean back){return back?
        capture("G124","G125次列车 (高速,有空调) , 始发站：滁州 , 终到站：南京南 , 全程共有：2个停靠站",TODAY.toString(),List.of(
            List.of("01","滁州","G125","10:00","----","----"),List.of("02","南京南","G125","----","11:05","01:05当日到达"))):
        capture("G123","G123次列车 (高速,有空调) , 始发站：南京南 , 终到站：徐州东 , 全程共有：4个停靠站",TODAY.toString(),List.of(
            List.of("01","南京南","G123","08:00","----","----"),List.of("02","滁州","G123","09:05","09:00","01:00当日到达"),
            List.of("03","蚌埠南","G123","10:05","10:00","02:00当日到达"),List.of("04","徐州东","G123","----","11:00","03:00当日到达")));}
    static Map<String,Object> role(String unit,String raw,int start,int end){return m("unit_id",unit,"start",start,"end",end,"text",raw.substring(start,end));}
    static Map<String,Object> full(Map<String,String> units,String unit){return role(unit,units.get(unit),0,units.get(unit).length());}
    static CuratedCityDuration.Direction direction(boolean back){
        Map<String,Object> source=fixture(back);var table=PublicUiStopTable.parse(source);var from=table.stops().get(0);var to=table.stops().get(1);
        Map<String,Object> fromCity=back?city("安徽","滁州"):city("江苏","南京"),toCity=back?city("江苏","南京"):city("安徽","滁州");
        Map<String,Object> duration=clocks(table.units().get(from.cellUnits().get(3)),table.units().get(to.cellUnits().get(4)),0,to.arrival()-from.departure());duration.put("service_id",table.service());
        Map<String,Object> canonical=m("verification_method",PublicUiStopTable.METHOD,"source_id",source.get("source_id"),"from_registry_id",fromCity.get("id"),"to_registry_id",toCity.get("id"),
            "from_endpoint",from.station(),"to_endpoint",to.station(),"endpoint_scope",m("from","named_city_station","to","named_city_station"),"mapping_basis","literal_city_prefix","transport_mode","rail","route_scope","station_service_pair",
            "published_on",null,"date_basis",copy(source.get("date_basis")),"effective_on",TODAY.toString(),"effective_until",null,"service_state","observed_public_ui_service_sample","status","active","duration",duration);
        var u=table.units();Map<String,Object> spans=m("from",List.of(full(u,from.cellUnits().get(1))),"to",List.of(full(u,to.cellUnits().get(1))),
            "departure",List.of(full(u,from.cellUnits().get(3))),"arrival",List.of(full(u,to.cellUnits().get(4))),"service",List.of(role("heading",u.get("heading"),0,table.service().length())),
            "context",List.of(full(u,"heading")),"service_date",List.of(full(u,"date")),"query_service",List.of(full(u,"query")));
        Map<String,Object> fact=m("from_city",fromCity.get("city"),"to_city",toCity.get("city"),"canonical",canonical,"spans",spans,"rationale","Synthetic topology fixture, not actual reader or geography verification.");
        return new CuratedCityDuration.Direction(source,fact,copy(fact),fromCity,toCity,TODAY);
    }
    static void editCell(Map<String,Object> source,String id,String value){for(Object raw:(List<?>)source.get("units")){var u=mutable(raw);if(id.equals(u.get("unit_id"))){u.put("text",value);u.put("text_sha256",CityPlanningReference.rawHash(value));return;}}throw new AssertionError("missing cell");}
    static void badCell(String id,String value,String reason){var data=fixture(false);editCell(data,id,value);rejects(()->PublicUiStopTable.parse(data),reason);}
    static void badSource(Consumer<Map<String,Object>> edit,String reason){var data=fixture(false);edit.accept(data);rejects(()->PublicUiStopTable.parse(data),reason);}
    static Map<String,Object> annotations(Path file,Map<String,Object> data,Path collection)throws Exception{
        var event=mutable(((List<?>)mutable(((List<?>)data.get("references")).get(0)).get("revisions")).get(0));var snapshot=mutable(event.get("snapshot"));List<Object> rows=new ArrayList<>();
        for(String side:List.of("outbound","inbound")){var direction=direction(side.equals("inbound"));var ref=mutable(snapshot.get(side));var fact=direction.readA();var source=direction.source();List<Object> evidence=new ArrayList<>();
            for(String role:List.of("from","departure","to","arrival"))evidence.add(copy(((List<?>)mutable(fact.get("spans")).get(role)).get(0)));
            rows.add(m("row_id","synthetic-stop-"+side,"member_id","synthetic-stop","library_file",file.toString(),"library_file_sha256",CityPlanningReference.rawHash(Files.readString(file)),"library_anchor_sha256",CityPlanningReference.rawHash(Files.readString(Path.of(file+".integrity.json"))),
                "reference_id",snapshot.get("reference_id"),"reference_version",snapshot.get("version"),"reference_event_sha256",event.get("event_sha256"),"direction",side,"from_registry_id",direction.from().get("id"),"to_registry_id",direction.to().get("id"),"from_city",direction.from().get("city"),"to_city",direction.to().get("city"),
                "source_document",ref.get("source_document"),"source_sha256",mutable(mutable(data.get("documents")).get(ref.get("source_document"))).get("raw_sha256"),"source_url",source.get("url"),"source_published_on",null,"source_qualifier","general_reported","precision","same_service_clocks","date_basis",copy(source.get("date_basis")),"evidence",evidence,"publication_context",null,"note","Synthetic v2 cell-binding test only"));
        }
        return m("version",CityPlanningPresentation.VERSION_V2,"presentation_revision",1,"purpose","presentation_only","created_on",TODAY.toString(),"annotation_draft_sha256","a".repeat(64),
            "review",m("status","approved","reviewer_a","synthetic-A","reviewer_b","synthetic-B","mode","informed_retained_evidence_annotation_crosscheck","reviewed_on",TODAY.toString(),"record_locators",List.of("Synthetic test only")),"collection_file",collection.toString(),"collection_file_sha256",CityPlanningReference.rawHash(Files.readString(collection)),"publication_context",Map.of(),"rows",rows);
    }
    static CityPlanningPresentation.Catalog view(Path dir,String name,Map<String,Object> annotation)throws Exception{Path file=dir.resolve(name+".json");Files.writeString(file,Json.write(annotation)+"\n",StandardOpenOption.CREATE_NEW);Files.writeString(Path.of(file+".integrity.json"),Json.write(CityPlanningPresentation.anchor(annotation))+"\n",StandardOpenOption.CREATE_NEW);return CityPlanningPresentation.load(file,CityPlanningPresentation.VERSION_V2);}
    public static void main(String[] args)throws Exception{
        var source=fixture(false);String unchanged=Json.write(source);var parsed=PublicUiStopTable.parse(source);
        ok(PublicUiStopTable.allSegments(parsed).size()==6,"all forward segments without reverse fabrication");
        ok(PublicUiStopTable.segment(parsed,"滁州","徐州东").minutes()==115,"depart upstream, arrive downstream; not origin180 or elapsed difference120");
        ok(PublicUiStopTable.segment(parsed,"南京南","滁州").minutes()==60,"initial departure and arrival");
        ok(Json.write(source).equals(unchanged),"source unchanged");
        rejects(()->PublicUiStopTable.segment(parsed,"徐州东","南京南"),"selected_segment_not_forward");
        rejects(()->PublicUiStopTable.segment(parsed,"南京南","未知站"),"selected_segment_not_forward");
        var sparse=copy(source);var rows=(List<?>)mutable(sparse.get("table_capture")).get("rows");mutable(sparse.get("table_capture")).put("rows",List.of(rows.get(1),rows.get(3)));
        ok(PublicUiStopTable.parse(sparse).stops().size()==2,"sparse selected rows retain original sequence numbers");
        ok(PublicUiStopTable.allSegments(PublicUiStopTable.parse(sparse)).get(0).minutes()==115,"sparse same-service arithmetic");
        for(String url:List.of("http://hzfw.12306.cn/zgzfw/resources/web/skcx.html","https://evil.invalid/zgzfw/resources/web/skcx.html","https://hzfw.12306.cn/other","https://hzfw.12306.cn:443/zgzfw/resources/web/skcx.html","https://u@hzfw.12306.cn/zgzfw/resources/web/skcx.html","https://hzfw.12306.cn/zgzfw/resources/web/skcx.html#x"))badSource(s->s.put("url",url),"source_page_invalid");
        badCell("column3","到达时间","column_role_or_order_invalid");badCell("column4","出发时间","column_role_or_order_invalid");
        badCell("r1c2","G999","row_service_mismatch");badCell("r1c0","01","sequence_order_invalid");badCell("r1c0","2","sequence_format_invalid");
        badCell("r1c1","南京南","station_missing_or_duplicate");badCell("r1c3","24:01","clock_invalid");badCell("r1c3","09:60","clock_invalid");
        badCell("r1c3","08:59","departure_before_arrival");badCell("r1c5","01:00次日到达","elapsed_day_invalid");
        badCell("r1c5","01:01当日到达","origin_elapsed_inconsistent");badCell("r1c5","12:00当日到达","origin_elapsed_inconsistent");
        badCell("r0c4","07:55","initial_row_invalid");badCell("r3c3","11:02","terminal_row_invalid");badCell("r1c3","----","departure_missing");
        badCell("query","K123","query_service_invalid");badCell("r0c1","南京北","initial_row_invalid");badCell("r3c1","徐州西","terminal_row_invalid");
        badCell("heading","G123次列车 (高速,有空调) , 始发站：南京南 , 终到站：徐州东 , 全程共有：3个停靠站","selected_row_count_invalid");
        badSource(s->{var cap=mutable(s.get("table_capture"));cap.put("column_units",List.of("column0","column1","column2","column3","column4","column4"));},"cell_missing_or_reused");
        var out=direction(false);var back=direction(true);var result=CuratedCityDuration.directionAfterEnvelopeValidation(back);
        ok(result.get("query_service_id").equals("G124")&&result.get("displayed_service_id").equals("G125"),"query/displayed labels preserved separately");
        ok(Boolean.FALSE.equals(result.get("query_display_equivalence_claimed")),"no invented train alias");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(both(back,f->dur(f).put("service_id","G124"))),"canonical_service_mismatch");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(both(out,f->dur(f).put("calculated_minutes",61))),"calculated_duration_mismatch");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(both(out,f->mutable(f.get("spans")).put("arrival",List.of(full(parsed.units(),"r2c4"))))),"selected_arrival_cell_mismatch");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(both(out,f->mutable(f.get("spans")).put("segment",List.of(full(parsed.units(),"heading"))))),"fields_missing_or_unknown");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(source(out,s->s.put("source_type",PublicUiCitySample.SOURCE_TYPE))),"ordinary_public_page_required");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(both(out,f->c(f).put("verification_method",PublicUiCitySample.METHOD))),"source_method_mismatch");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(source(out,s->s.put("access_restricted",true))),"original_source_restriction");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(source(out,s->editCell(s,"date",TODAY.plusDays(1).toString()))),"visible_date_mismatch");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(both(out,f->mutable(c(f).get("endpoint_scope")).put("from","county"))),"county_or_unknown_endpoint_scope");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(change(out,f->dur(f).put("calculated_minutes",61),f->{})),"core_read_disagreement");
        // Collections bind canonical paths. macOS exposes /var via /private/var;
        // construct the annotation from that same identity, not the temp alias.
        var data=CuratedPlanningIntegrationTest.bundle(out,back);Path dir=(args.length==0?Files.createTempDirectory("public-stop-table-test-"):Files.createDirectory(Path.of(args[0]))).toRealPath();Path file=dir.resolve("synthetic.json");
        CityPlanningReference.prepare(data,file,TODAY);var loaded=CityPlanningReference.load(file);ok(!loaded.containsKey("load_status"),"protected prepare/load");
        for(boolean reverse:List.of(false,true)){var ref=CityPlanningReference.lookup(loaded,reverse?"安徽":"江苏",reverse?"滁州":"南京",reverse?"江苏":"安徽",reverse?"南京":"滁州",TODAY);
            ok("planning_reference".equals(ref.get("status")),"both requests use protected planning only");ok(PublicUiStopTable.METHOD.equals(ref.get("verification_method")),"new method preserved");
            for(String key:List.of("transport_verified","rail_exclusion_complete","air_fallback_trigger","time_score_applicable"))ok(Boolean.FALSE.equals(ref.get(key)),"no unsupported stronger claim: "+key);
        }
        var late=CityPlanningReference.lookup(loaded,"江苏","南京","安徽","滁州",TODAY.plusDays(91));ok("source_or_review_stale".equals(late.get("reason")),"expired sample pending, not permanent guarantee");ok(Boolean.FALSE.equals(late.get("air_fallback_trigger")),"expired is not air evidence");
        var boundary=CityPlanningReference.lookup(loaded,"江苏","南京","安徽","滁州",TODAY.plusDays(90));ok("planning_reference".equals(boundary.get("status")),"90 day review boundary remains valid");
        ok(!CityPlanningSelection.underConfiguredLimit(boundary,65)&&CityPlanningSelection.underConfiguredLimit(boundary,66),"strict less-than cutoff uses slower actual direction without fake buffer");
        Path collection=dir.resolve("collection.json");CityPlanningCollection.prepare("synthetic-stop-table",Map.of("synthetic-stop",file),null,collection,TODAY);
        var annotation=annotations(file,data,collection);var shown=CityPlanningReference.collectionIndex(collection,TODAY,view(dir,"view",annotation));
        for(boolean reverse:List.of(false,true)){var displayed=shown.lookup(reverse?"安徽":"江苏",reverse?"滁州":"南京",reverse?"江苏":"安徽",reverse?"南京":"滁州");
            ok("ready".equals(mutable(displayed.get("presentation")).get("status")),"new stop-table v2 display bound in both request directions: "+Json.write(displayed.get("presentation")));
            var without=new LinkedHashMap<>(displayed);without.remove("presentation");ok(CityPlanningReference.same(without,CityPlanningReference.collectionIndex(collection,TODAY).lookup(reverse?"安徽":"江苏",reverse?"滁州":"南京",reverse?"江苏":"安徽",reverse?"南京":"滁州")),"display leaves all planning facts and selection unchanged");
        }
        var pathAlias=copy(annotation);
        String aliasedFile=dir.resolve("..").resolve(dir.getFileName()).resolve(file.getFileName()).toString();
        ok(Files.isSameFile(Path.of(aliasedFile),file),"alias fixture addresses the same physical file");
        for(Object raw:(List<?>)pathAlias.get("rows"))mutable(raw).put("library_file",aliasedFile);
        var aliasShown=CityPlanningReference.collectionIndex(collection,TODAY,view(dir,"noncanonical-view",pathAlias)).lookup("江苏","南京","安徽","滁州");
        ok("presentation_library_binding_mismatch".equals(mutable(aliasShown.get("presentation")).get("reason")),"noncanonical annotation path still rejected; production binding not relaxed");
        var wrongView=copy(annotation);mutable(((List<?>)wrongView.get("rows")).get(0)).put("source_sha256","0".repeat(64));var badShown=CityPlanningReference.collectionIndex(collection,TODAY,view(dir,"view-bad-source",wrongView)).lookup("江苏","南京","安徽","滁州");ok("pending".equals(mutable(badShown.get("presentation")).get("status")),"new view cannot bypass source SHA");
        var sameReader=copy(data);CuratedPlanningIntegrationTest.editDoc(sameReader,"B",v->mutable(v.get("reviewer")).put("agent_id","synthetic-agent-A"));rejects(()->CityPlanningReference.anchor(sameReader),"second_independent_reader_missing");
        var noRead=copy(data);CuratedPlanningIntegrationTest.editDoc(noRead,"B",v->v.put("read_events",List.of()));rejects(()->CityPlanningReference.anchor(noRead),"original_read_event_missing");
        System.out.println("Public UI stop table: "+checks+" checks PASS; synthetic protected prepare/load, no live data or production changes.");
    }
}
