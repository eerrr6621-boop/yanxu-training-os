package com.training;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static com.training.CuratedCityDurationTest.*;

/** Synthetic protected-loader cases, plus optional hash-pinned saved-cell replay.
 * Replaying saved cells is NOT an independent reading or a live-city admission. */
public final class PublicUiPopupTableTest {
    static int checks;
    static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void rejects(Runnable r,String why){try{r.run();throw new AssertionError("accepted "+why);}catch(IllegalArgumentException ex){ok(ex.getMessage().contains(why),why+": "+ex.getMessage());}}
    static Map<String,Object> capture(String service,String heading,String queryDay,String serviceDay,List<?> cells,Map<?,?> anchor){
        List<Object> units=new ArrayList<>();units.add(PublicUiStopTableTest.unit("query",service));units.add(PublicUiStopTableTest.unit("date",serviceDay));units.add(PublicUiStopTableTest.unit("heading",heading));
        List<String> columns=new ArrayList<>();int c=0;for(String label:List.of("站序","站名","到站时间","出发时间","停留时间")){String id="column"+(c++);columns.add(id);units.add(PublicUiStopTableTest.unit(id,label));}
        List<Object> rows=new ArrayList<>();for(int i=0;i<cells.size();i++){var row=(List<?>)cells.get(i);List<String> ids=new ArrayList<>();for(int j=0;j<row.size();j++){String id="r"+i+"c"+j;ids.add(id);units.add(PublicUiStopTableTest.unit(id,(String)row.get(j)));}rows.add(m("cell_units",ids));}
        Map<String,Object> anchorUnits=new LinkedHashMap<>();for(String role:List.of("from","to","departure","arrival","elapsed","arrival_day")){String id="anchor_"+role;anchorUnits.put(role,id);units.add(PublicUiStopTableTest.unit(id,(String)anchor.get(role)));}
        return m("source_id","popup-"+service,"source_type",PublicUiPopupTable.SOURCE_TYPE,"url","https://kyfw.12306.cn/otn/leftTicket/init?preserve=observed-query","published_on",null,
            "date_basis",m("kind",PublicUiCitySample.DATE_BASIS_V2,"query_date",queryDay,"service_date",serviceDay),"sample_scope","selected_service_sample_not_fastest_typical_or_upper_bound","duration_qualifier","general_reported",
            "units",units,"table_capture",m("schema",PublicUiPopupTable.LAYOUT,"query_train_unit","query","service_date_unit","date","heading_unit","heading","column_units",columns,"rows",rows,"query_anchor_units",anchorUnits));
    }
    static Map<String,Object> fixture(boolean back){
        var data=back?capture("G322","G322次\n滁州-->南京南\n高速\n有空调",TODAY.toString(),TODAY.plusDays(1).toString(),List.of(List.of("01","滁州","----","11:00","----"),List.of("02","南京南","12:05","12:05","----")),m("from","滁州","to","南京南","departure","11:00","arrival","12:05","elapsed","01:05","arrival_day","当日到达")):
            capture("G321","G321次\n南京南-->徐州东\n高速\n有空调",TODAY.toString(),TODAY.plusDays(1).toString(),List.of(List.of("01","南京南","----","08:00","----"),List.of("02","滁州","09:00","09:05","5分钟"),List.of("04","蚌埠南","10:00","10:05","5分钟"),List.of("05","徐州东","11:00","11:00","----")),m("from","南京南","to","蚌埠南","departure","08:00","arrival","10:00","elapsed","02:00","arrival_day","当日到达"));
        data.put("synthetic_only",true);return data;
    }
    static CuratedCityDuration.Direction direction(boolean back){
        var source=fixture(back);var table=PublicUiPopupTable.parse(source);var from=table.stops().get(0);var to=table.stops().get(1);
        var a=back?city("安徽","滁州"):city("江苏","南京");var b=back?city("江苏","南京"):city("安徽","滁州");
        var d=clocks(table.units().get(from.cellUnits().get(3)),table.units().get(to.cellUnits().get(2)),0,to.arrival()-from.departure());d.put("service_id",table.service());
        var canonical=m("verification_method",PublicUiPopupTable.METHOD,"source_id",source.get("source_id"),"from_registry_id",a.get("id"),"to_registry_id",b.get("id"),"from_endpoint",from.station(),"to_endpoint",to.station(),
            "endpoint_scope",m("from","named_city_station","to","named_city_station"),"mapping_basis","literal_city_prefix","transport_mode","rail","route_scope","station_service_pair","published_on",null,"date_basis",copy(source.get("date_basis")),
            "effective_on",TODAY.plusDays(1).toString(),"effective_until",null,"service_state","observed_public_ui_service_sample","status","active","duration",d);
        var u=table.units();var spans=m("from",List.of(PublicUiStopTableTest.full(u,from.cellUnits().get(1))),"to",List.of(PublicUiStopTableTest.full(u,to.cellUnits().get(1))),
            "departure",List.of(PublicUiStopTableTest.full(u,from.cellUnits().get(3))),"arrival",List.of(PublicUiStopTableTest.full(u,to.cellUnits().get(2))),"service",List.of(PublicUiStopTableTest.role("heading",u.get("heading"),0,table.service().length())),
            "context",List.of(PublicUiStopTableTest.full(u,"heading")),"service_date",List.of(PublicUiStopTableTest.full(u,"date")),"query_service",List.of(PublicUiStopTableTest.full(u,"query")));
        var fact=m("from_city",a.get("city"),"to_city",b.get("city"),"canonical",canonical,"spans",spans,"rationale","Synthetic test only, not an actual source or city assertion.");
        return new CuratedCityDuration.Direction(source,fact,copy(fact),a,b,TODAY);
    }
    static void bad(Consumer<Map<String,Object>> edit,String why){var s=fixture(false);edit.accept(s);rejects(()->PublicUiPopupTable.parse(s),why);}
    static void badCell(String id,String value,String why){bad(s->PublicUiStopTableTest.editCell(s,id,value),why);}
    static CuratedCityDuration.Direction returnMinutes(int minutes){
        var old=direction(true);String arrival=String.format(Locale.ROOT,"%02d:%02d",11+minutes/60,minutes%60),elapsed=String.format(Locale.ROOT,"%02d:%02d",minutes/60,minutes%60);
        var s=copy(old.source());for(String id:List.of("r1c2","r1c3","anchor_arrival"))PublicUiStopTableTest.editCell(s,id,arrival);PublicUiStopTableTest.editCell(s,"anchor_elapsed",elapsed);
        var a=copy(old.readA());var b=copy(old.readB());for(var f:List.of(a,b)){dur(f).put("arrival_clock",arrival);dur(f).put("calculated_minutes",minutes);mutable(f.get("spans")).put("arrival",List.of(PublicUiStopTableTest.full(PublicUiPopupTable.parse(s).units(),"r1c2")));}
        return new CuratedCityDuration.Direction(s,a,b,old.from(),old.to(),old.reviewedOn());
    }
    static Map<String,Object> annotations(Path file,Map<String,Object> data,Path collection)throws Exception{
        var view=PublicUiStopTableTest.annotations(file,data,collection);
        for(Object raw:(List<?>)view.get("rows")){
            var row=mutable(raw);String side=(String)row.get("direction");var direction=direction(side.equals("inbound"));var source=direction.source();var fact=direction.readA();
            List<Object> evidence=new ArrayList<>();for(String role:List.of("from","departure","to","arrival"))evidence.add(copy(((List<?>)mutable(fact.get("spans")).get(role)).get(0)));
            row.put("source_url",source.get("url"));row.put("date_basis",copy(source.get("date_basis")));row.put("evidence",evidence);row.put("note","Synthetic popup cell binding only");
        }
        return view;
    }
    static Map<String,Object> replay(Path input,String expected)throws Exception{
        String raw=Files.readString(input);ok(CityPlanningReference.rawHash(raw).equals(expected),"actual cells expected SHA");var data=mutable(Json.parse(raw));
        ok("public_rail_popup_observation_v1".equals(data.get("schema"))&&"first_read_only".equals(data.get("review_status"))&&Boolean.FALSE.equals(data.get("production_adopted")),"retained first-reader scope");
        List<Object> samples=new ArrayList<>();int excluded=0,rows=0;
        for(Object value:(List<?>)data.get("tables")){
            var table=mutable(value);var a=mutable(table.get("query_segment"));
            var source=capture((String)table.get("queried_train"),(String)table.get("heading"),(String)data.get("observed_on"),(String)table.get("service_date"),(List<?>)table.get("rows"),m("from",a.get("from_station"),"to",a.get("to_station"),"departure",a.get("departure"),"arrival",a.get("arrival"),"elapsed",a.get("elapsed"),"arrival_day",a.get("arrival_day")));
            source.put("url",data.get("source_url"));var parsed=PublicUiPopupTable.parse(source);PublicUiCitySample.referenceDate(source);rows+=parsed.stops().size();
            ok(parsed.service().equals(table.get("displayed_train")),"original displayed train retained");
            for(int i=0;i<parsed.stops().size()-1;i++)for(int j=i+1;j<parsed.stops().size();j++){
                var from=parsed.stops().get(i);var to=parsed.stops().get(j);
                if(i<parsed.anchorStart()||j>parsed.anchorEnd()){rejects(()->PublicUiPopupTable.segment(parsed,from.station(),to.station()),"selected_segment_outside_same_day_anchor");excluded++;}
                else{var s=PublicUiPopupTable.segment(parsed,from.station(),to.station());samples.add(m("table_id",table.get("id"),"from_station",from.station(),"to_station",to.station(),"minutes",s.minutes()));}
            }
        }
        ok(samples.size()==688&&excluded==103&&rows==96,"full six-table saved-cells inventory");
        return m("schema","popup_adapter_saved_cell_replay_v1","input_sha256",expected,"rows",rows,"directed_samples",samples,"outside_anchor_rejected",excluded,"new_city_admissions",0,"independent_read_claimed",false,"production_changed",false);
    }
    public static void main(String[] args)throws Exception{
        Path dir=(args.length==0?Files.createTempDirectory("popup-ui-test-"):Files.createDirectory(Path.of(args[0]))).toRealPath();var source=fixture(false);String raw=Json.write(source);var table=PublicUiPopupTable.parse(source);
        ok(table.stops().get(2).sequence()==4,"sparse sequence not filled");ok(PublicUiPopupTable.allSegments(table).size()==3,"only anchored subset emitted");
        ok(PublicUiPopupTable.segment(table,"滁州","蚌埠南").minutes()==55,"depart-to-arrive excludes upstream dwell");ok(raw.equals(Json.write(source)),"raw source unchanged");
        rejects(()->PublicUiPopupTable.segment(table,"滁州","徐州东"),"selected_segment_outside_same_day_anchor");rejects(()->PublicUiPopupTable.segment(table,"蚌埠南","滁州"),"selected_segment_not_forward");
        for(String url:List.of("https://kyfw.12306.cn.evil.invalid/otn/leftTicket/init","http://kyfw.12306.cn/otn/leftTicket/init","https://u@kyfw.12306.cn/otn/leftTicket/init","https://kyfw.12306.cn:443/otn/leftTicket/init","https://kyfw.12306.cn/otn/leftTicket/init#x","https://hzfw.12306.cn/zgzfw/resources/web/skcx.html"))bad(s->s.put("url",url),"source_page_invalid");
        badCell("query","G322","heading_service_mismatch");badCell("column2","出发时间","column_role_or_order_invalid");badCell("r1c0","01","sequence_order_invalid");badCell("r1c0","2","sequence_format_invalid");
        badCell("r1c1","南京南","station_missing_or_duplicate");badCell("r0c2","07:59","origin_roles_invalid");badCell("r1c2","24:00","clock_invalid");badCell("r1c3","08:59","overnight_or_nonmonotonic_unsupported");badCell("r1c4","4分钟","dwell_clock_mismatch");
        badCell("r3c3","11:01","terminal_roles_invalid");badCell("r3c1","徐州北","heading_endpoint_mismatch");badCell("anchor_arrival_day","次日到达","same_day_query_anchor_required");badCell("anchor_to","未知","query_anchor_order_invalid");
        badCell("anchor_departure","08:01","query_anchor_clock_mismatch");badCell("anchor_elapsed","02:01","query_anchor_elapsed_mismatch");
        bad(s->mutable(s.get("table_capture")).put("query_train_unit","heading"),"cell_missing_or_reused");
        var out=direction(false);var back=direction(true);var bundle=CuratedPlanningIntegrationTest.bundle(out,back);Path path=dir.resolve("synthetic.json");CityPlanningReference.prepare(bundle,path,TODAY);var loaded=CityPlanningReference.load(path);ok(!loaded.containsKey("load_status"),"protected loader supports popup");List<Object> rendering=new ArrayList<>();
        for(boolean reverse:List.of(false,true)){
            var fit=CityPlanningReference.lookup(loaded,reverse?"安徽":"江苏",reverse?"滁州":"南京",reverse?"江苏":"安徽",reverse?"南京":"滁州",TODAY);ok("planning_reference".equals(fit.get("status")),"both request directions available as planning only");
            rendering.add(m("kind","popup_table","days",1,"reverse",reverse,"fit",m("planning_included",true,"planning_reference",fit)));
            for(String flag:List.of("transport_verified","time_score_applicable","air_fallback_trigger","rail_exclusion_complete"))ok(Boolean.FALSE.equals(fit.get(flag)),"no stronger claim "+flag);
            ok(!CityPlanningSelection.underConfiguredLimit(fit,65)&&CityPlanningSelection.underConfiguredLimit(fit,66),"strict limit uses slower direction");
        }
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(both(out,f->dur(f).put("calculated_minutes",61))),"calculated_duration_mismatch");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(both(out,f->mutable(f.get("spans")).put("arrival",List.of(PublicUiStopTableTest.full(table.units(),"r1c3"))))),"selected_arrival_cell_mismatch");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(source(out,s->s.put("access_restricted",true))),"original_source_restriction");
        rejects(()->CuratedCityDuration.directionAfterEnvelopeValidation(both(out,f->c(f).put("verification_method",PublicUiStopTable.METHOD))),"source_method_mismatch");
        var sameReader=copy(bundle);CuratedPlanningIntegrationTest.editDoc(sameReader,"B",v->mutable(v.get("reviewer")).put("agent_id","synthetic-agent-A"));rejects(()->CityPlanningReference.anchor(sameReader),"second_independent_reader_missing");
        var noRead=copy(bundle);CuratedPlanningIntegrationTest.editDoc(noRead,"B",v->v.put("read_events",List.of()));rejects(()->CityPlanningReference.anchor(noRead),"original_read_event_missing");
        var late=CityPlanningReference.lookup(loaded,"江苏","南京","安徽","滁州",TODAY.plusDays(91));ok("source_or_review_stale".equals(late.get("reason")),"query-age expiry retained");ok(Boolean.FALSE.equals(late.get("air_fallback_trigger")),"expiry cannot trigger air");
        for(int minutes:List.of(239,240,241)){
            var boundary=CuratedPlanningIntegrationTest.bundle(out,returnMinutes(minutes));Path f=dir.resolve("boundary-"+minutes+".json");CityPlanningReference.prepare(boundary,f,TODAY);
            var selected=CityPlanningReference.lookup(CityPlanningReference.load(f),"江苏","南京","安徽","滁州",TODAY);
            ok((minutes<240?"planning_reference":"planning_pending").equals(selected.get("status")),"240-minute boundary, back="+minutes);ok(Boolean.FALSE.equals(selected.get("air_fallback_trigger")),"slow sample not complete rail exclusion");
        }
        Path collection=dir.resolve("collection.json");CityPlanningCollection.prepare("synthetic-popup",Map.of("synthetic-stop",path),null,collection,TODAY);
        var annotations=annotations(path,bundle,collection);var view=PublicUiStopTableTest.view(dir,"popup-display",annotations);
        for(boolean reverse:List.of(false,true)){
            var plain=CityPlanningReference.collectionIndex(collection,TODAY).lookup(reverse?"安徽":"江苏",reverse?"滁州":"南京",reverse?"江苏":"安徽",reverse?"南京":"滁州");
            var shown=CityPlanningReference.collectionIndex(collection,TODAY,view).lookup(reverse?"安徽":"江苏",reverse?"滁州":"南京",reverse?"江苏":"安徽",reverse?"南京":"滁州");
            ok("ready".equals(mutable(shown.get("presentation")).get("status")),"source-bound popup presentation ready in both directions");
            var stripped=new LinkedHashMap<>(shown);stripped.remove("presentation");ok(CityPlanningReference.same(stripped,plain),"presentation does not change selection");
            rendering.add(m("kind","popup_table","days",1,"reverse",reverse,"fit",m("planning_included",true,"planning_reference",shown)));
        }
        var wrong=copy(annotations);mutable(((List<?>)wrong.get("rows")).get(0)).put("source_sha256","0".repeat(64));var wrongView=PublicUiStopTableTest.view(dir,"wrong-popup-display",wrong);
        var badDisplay=CityPlanningReference.collectionIndex(collection,TODAY,wrongView).lookup("江苏","南京","安徽","滁州");ok("pending".equals(mutable(badDisplay.get("presentation")).get("status")),"source hash mismatch fails closed");
        Files.writeString(dir.resolve("renderable-samples.json"),Json.write(m("schema","synthetic_ui_date_rendering_v1","synthetic_only",true,"samples",rendering))+"\n",StandardOpenOption.CREATE_NEW);
        if(args.length==3)Files.writeString(dir.resolve("saved-cell-replay.json"),Json.write(replay(Path.of(args[1]),args[2]))+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("Public UI popup table: "+checks+" checks PASS; no real-city admission or production change.");
    }
}
