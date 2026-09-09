package com.training;

import java.nio.file.*;
import java.util.*;
import static com.training.CuratedCityDurationTest.*;

/** Invented rows only. Exact city/station labels never establish geography or reader identity. */
public final class PublicUiNamedStationTest {
    static int checks;
    static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static Map<String,Object> role(String unit,String raw,String token){
        Map<String,Object> value=span(raw,token);value.put("unit_id",unit);return value;
    }
    static Map<String,Object> unit(String id,String raw){return m("unit_id",id,"text",raw,"offset_unit","utf16","text_sha256",CityPlanningReference.rawHash(raw));}
    static CuratedCityDuration.Direction sample(boolean back,String fromEndpoint,String toEndpoint,String service){
        String from=back?"青岛":"济南",to=back?"济南":"青岛",sid=back?"synthetic-ui-return":"synthetic-ui-out";
        String dep=back?"09:00":"08:00",arr=back?"11:00":"10:00",elapsed="02:00";
        String raw=String.join("\n",service,fromEndpoint,toEndpoint,dep,arr,elapsed,"当日到达");
        String heading=from+" --> "+to+"（合成测试，非真实查询）",date=TODAY.toString();
        Map<String,Object> fromCity=city("山东",from),toCity=city("山东",to);
        Map<String,Object> basis=m("kind",PublicUiCitySample.DATE_BASIS,"query_date",date,"service_date",date);
        Map<String,Object> source=m("source_id",sid,"source_type",PublicUiCitySample.SOURCE_TYPE,
            "url","https://kyfw.12306.cn/otn/leftTicket/init","published_on",null,"date_basis",basis,
            "sample_scope","selected_service_sample_not_fastest_typical_or_upper_bound","duration_qualifier","general_reported",
            "synthetic_only",true,"units",List.of(unit("row",raw),unit("heading",heading),unit("date",date)));
        Map<String,Object> duration=clocks(dep,arr,0,120);duration.put("service_id",service);
        Map<String,Object> canonical=m("verification_method",PublicUiCitySample.METHOD,"source_id",sid,
            "from_registry_id",fromCity.get("id"),"to_registry_id",toCity.get("id"),"from_endpoint",fromEndpoint,"to_endpoint",toEndpoint,
            "endpoint_scope",m("from","named_city_station","to","named_city_station"),"mapping_basis","literal_city_prefix",
            "transport_mode","rail","route_scope","station_service_pair","published_on",null,"date_basis",copy(basis),
            "effective_on",date,"effective_until",null,"service_state","observed_public_ui_service_sample","status","active","duration",duration);
        Map<String,Object> spans=m("from",List.of(role("row",raw,fromEndpoint)),"to",List.of(role("row",raw,toEndpoint)),
            "service",List.of(role("row",raw,service)),"departure",List.of(role("row",raw,dep)),"arrival",List.of(role("row",raw,arr)),
            "duration",List.of(role("row",raw,elapsed)),"displayed_arrival_day",List.of(role("row",raw,"当日到达")),
            "segment",List.of(role("row",raw,raw)),"context",List.of(role("heading",heading,heading)),"service_date",List.of(role("date",date,date)));
        Map<String,Object> fact=m("from_city",from,"to_city",to,"canonical",canonical,"spans",spans,
            "rationale","Synthetic agreeing station-role annotations, not actual source reading or a geography proof.");
        return new CuratedCityDuration.Direction(source,fact,copy(fact),fromCity,toCity,TODAY);
    }
    static CuratedCityDuration.Direction sample(boolean back){return sample(back,back?"青岛":"济南",back?"济南":"青岛",back?"G124":"G123");}
    static void reject(CuratedCityDuration.Direction d,String reason){
        String actual="";try{CuratedCityDuration.directionAfterEnvelopeValidation(d);}catch(IllegalArgumentException e){actual=e.getMessage();}
        ok(actual.contains(reason),"expected "+reason+", actual="+actual);
    }
    static void rejectEnvelope(Map<String,Object> data,String reason){
        String actual="";try{CityPlanningReference.anchor(data);}catch(IllegalArgumentException e){actual=e.getMessage();}
        ok(actual.contains(reason),"envelope expected "+reason+", actual="+actual);
    }
    public static void main(String[] args)throws Exception{
        var out=sample(false);var back=sample(true);
        var data=CuratedPlanningIntegrationTest.bundle(out,back);String before=Json.write(data);
        // This first call is intentionally an outer-envelope regression, not just raw-Map arithmetic.
        Path dir=args.length==0?Files.createTempDirectory("ui-named-station-synthetic-"):Files.createDirectory(Path.of(args[0]));
        Path file=dir.resolve("synthetic-only.json");CityPlanningReference.prepare(data,file,TODAY);
        var loaded=CityPlanningReference.load(file);ok(!loaded.containsKey("load_status"),"protected synthetic pair loads");
        for(boolean reverse:List.of(false,true)){
            String from=reverse?"青岛":"济南",to=reverse?"济南":"青岛";
            var result=CityPlanningReference.lookup(loaded,"山东",from,"山东",to,TODAY);
            ok("planning_reference".equals(result.get("status")),"both directions are planning-only");
            ok(PublicUiCitySample.METHOD.equals(result.get("verification_method")),"exact UI method retained");
            ok(Boolean.TRUE.equals(result.get("protected_envelope_validated")),"outer envelope validated");
            var leg=mutable(result.get("outbound"));
            ok(from.equals(leg.get("from_endpoint"))&&to.equals(leg.get("to_endpoint")),"original no-suffix page station labels retained");
            ok("named_city_station".equals(mutable(leg.get("endpoint_scope")).get("from")),"never relabelled city_summary");
            for(String flag:List.of("transport_verified","time_score_applicable","air_fallback_trigger","rail_exclusion_complete"))ok(Boolean.FALSE.equals(result.get(flag)),"no stronger claim "+flag);
        }
        ok(before.equals(Json.write(data))&&Files.readString(file).equals(before+"\n"),"original input and persisted bytes retained");
        // Json.parse represents numbers as doubles; compare parsed structure canonically,
        // and retain the exact nested source/review raw_json strings separately.
        ok(CityPlanningReference.same(data,loaded)&&Json.write(data.get("documents")).equals(Json.write(loaded.get("documents"))),"source/review raw bytes and hash/version structure retained");
        for(String prefix:List.of("G","D","C"))ok(PublicUiCitySample.METHOD.equals(CuratedCityDuration.directionAfterEnvelopeValidation(sample(false,"济南","青岛",prefix+"123")).get("verification_method")),"same-name G/D/C "+prefix);
        ok("济南西".equals(CuratedCityDuration.directionAfterEnvelopeValidation(sample(false,"济南西","青岛北","G123")).get("from_endpoint")),"existing prefixed stations unchanged");
        for(String side:List.of("from","to"))for(String scope:List.of("city_summary","main_urban_station","urban_subcentre_station","airport","metro","county","unknown","unknown_connection"))
            reject(both(out,f->mutable(c(f).get("endpoint_scope")).put(side,scope)),"county_or_unknown_endpoint_scope");
        reject(source(out,s->s.put("source_type","ordinary_news")),"source_method_mismatch");
        reject(source(out,s->s.remove("source_type")),"source_method_mismatch");
        for(String method:List.of(CuratedCityDuration.METHOD,PublicUiCitySample.METHOD+"_extra","unknown"))
            reject(both(out,f->{c(f).put("verification_method",method);c(f).remove("date_basis");}),"source_method_mismatch");
        reject(source(out,s->s.put("url","https://example.invalid/otn/leftTicket/init")),"ordinary_public_page_required");
        reject(source(out,s->s.put("access_restricted",true)),"original_source_restriction");
        for(String mode:List.of("airport","air","metro"))reject(both(out,f->c(f).put("transport_mode",mode)),"mode_or_scope_unsupported");
        reject(sample(false,"泰安","青岛","G123"),"nonliteral_or_mismatched_endpoint");
        reject(sample(false,"济南","烟台","G123"),"nonliteral_or_mismatched_endpoint");
        reject(both(out,f->c(f).put("mapping_basis","unknown")),"external_station_mapping_not_supported");
        reject(both(out,f->c(f).put("to_registry_id",c(f).get("from_registry_id"))),"registry_direction_mismatch");
        for(String service:List.of("Z123","S123","K123"))reject(sample(false,"济南","青岛",service),"high_speed_service_required");
        reject(both(out,f->dur(f).put("calculated_minutes",121)),"displayed_elapsed_mismatch");
        reject(both(out,f->mutable(c(f).get("date_basis")).put("service_date","2026-09-09")),"method_or_date_basis_mismatch");
        reject(both(out,f->c(f).put("from_endpoint","济南站")),"endpoint_span_mismatch");
        reject(change(out,f->c(f).put("from_endpoint","济南站"),f->{}),"core_read_disagreement");
        var old=fixture(false,clocks("08:00","10:00",0,120),"南京","上海");
        for(String scope:List.of("main_urban_station","urban_subcentre_station"))
            reject(both(old,f->mutable(c(f).get("endpoint_scope")).put("from",scope)),"nonliteral_or_mismatched_endpoint");
        reject(both(old,f->mutable(c(f).get("endpoint_scope")).put("from","named_city_station")),"county_or_unknown_endpoint_scope");
        ok("南京南站".equals(CuratedCityDuration.directionAfterEnvelopeValidation(fixture(false,clocks("08:00","10:00",0,120))).get("from_endpoint")),"legacy station suffix still works");
        ok("南京".equals(CuratedCityDuration.directionAfterEnvelopeValidation(fixture(false,scalar("reported_minutes","90分钟",90))).get("from_endpoint")),"legacy explicit city_summary unchanged");
        var tamper=copy(data);CuratedPlanningIntegrationTest.editDoc(tamper,"B",r->r.put("read_events",List.of()));rejectEnvelope(tamper,"original_read_event_missing");
        tamper=copy(data);CuratedPlanningIntegrationTest.editDoc(tamper,"B",r->mutable(r.get("reviewer")).put("agent_id","synthetic-agent-A"));rejectEnvelope(tamper,"second_independent_reader_missing");
        tamper=copy(data);mutable(mutable(tamper.get("documents")).get("A")).put("raw_sha256","0".repeat(64));rejectEnvelope(tamper,"document_hash_mismatch");
        System.out.println("Public UI same-name station: "+checks+" checks PASS; synthetic protected prepare/load only; no source collection or production changes.");
    }
}
