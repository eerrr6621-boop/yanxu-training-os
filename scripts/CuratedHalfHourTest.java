package com.training;

import java.nio.file.*;
import java.util.*;
import static com.training.CuratedCityDurationTest.*;

/** Synthetic notation and envelope regressions; no real route validation claimed. */
public final class CuratedHalfHourTest {
    static Map<String,Object> half(String token,int count){return m("kind","nominal_half_hour","value",count,"unit","half_hour","token",token);}
    static CuratedCityDuration.Direction clipped(String sourceToken,String selected,int count){
        var d=fixture(false,half(sourceToken,count));
        return both(d,f->{dur(f).put("token",selected);mutable(f.get("spans")).put("duration",List.of(span(raw(d),selected)));});
    }
    public static void main(String[] args)throws Exception{
        var back=fixture(true,scalar("reported_minutes","100分钟",100));
        for(var entry:Map.ofEntries(Map.entry("半小时",1),Map.entry("半个小时",1),Map.entry("一个半小时",3),Map.entry("两个半小时",5),Map.entry("二个半小时",5),Map.entry("2个半小时",5),Map.entry("三小时半",7),Map.entry("3小时半",7)).entrySet()){
            var d=fixture(false,half(entry.getKey(),entry.getValue()));String original=Json.write(d.source());
            var result=run(d,back);ready(result,"nominal half-hour: "+entry.getKey());
            Map<?,?> leg=(Map<?,?>)result.get("outbound"),typed=(Map<?,?>)leg.get("source_duration");
            check(leg.get("precision").equals("nominal_half_hour"),"never measured precision");
            check(typed.get("value").equals(entry.getValue())&&typed.get("unit").equals("half_hour"),"original half-hour units retained");
            check(leg.get("reference_value_minutes_internal").equals(entry.getValue()*30),"mechanical minutes only");
            check(Boolean.FALSE.equals(leg.get("policy_value_is_proven_travel_upper_bound")),"nominal is not proven upper bound");
            check(Json.write(d.source()).equals(original),"source unchanged");
        }
        for(String token:List.of("四个半小时","九小时半"))pending(run(fixture(false,half(token,token.startsWith("四")?9:19)),back),"reference_boundary_pending");
        for(String token:List.of("约两个半小时","大约两个半小时","两个半小时左右","两个半小时上下","约两个半小时左右")){
            var result=run(fixture(false,scalar("approximate",token,150)),back);ready(result,"approximate half-hour");
            check(((Map<?,?>)result.get("outbound")).get("precision").equals("approximate"),"approx marker remains distinct");
        }
        pending(run(fixture(false,half("两个半小时",4)),back),"half_hour_value_mismatch");
        pending(run(both(fixture(false,half("两个半小时",5)),f->dur(f).put("unit","minute")),back),"half_hour_unit_invalid");
        pending(run(both(fixture(false,half("两个半小时",5)),f->dur(f).put("value",5.5)),back),"invalid_integer");
        pending(run(fixture(false,scalar("reported_minutes","两个半小时",150)),back),"reported_precision_invalid");
        pending(run(fixture(false,scalar("nominal_hour","两个半小时",2)),back),"nominal_precision_invalid");
        pending(run(fixture(false,scalar("approximate","两个半小时",150)),back),"approximate_marker_missing");
        for(String token:List.of("十二个半小时","二点五小时","2.5小时","两小时","零个半小时","两个小时半","两个半小时多"))pending(run(fixture(false,half(token,5)),back),"half_hour_token_invalid");
        for(String source:List.of("两个半小时","十二个半小时","两\u3000个半小时"))pending(run(clipped(source,"半小时",1),back),"incomplete_half_hour_token");
        pending(run(clipped("十二个半小时","二个半小时",5),back),"incomplete_half_hour_token");
        for(String pad:List.of(""," ","\u3000","\u00a0","\t")){
            pending(run(clipped("约"+pad+"两个半小时","两个半小时",5),back),"qualifier_outside_selected_token");
            pending(run(clipped("两个半小时"+pad+"左右","两个半小时",5),back),"qualifier_outside_selected_token");
        }
        pending(run(clipped("约"+" ".repeat(33)+"两个半小时","两个半小时",5),back),"incomplete_half_hour_token");
        var nominal=fixture(false,half("两个半小时",5));
        pending(run(change(nominal,f->dur(f).put("value",6),f->{}),back),"core_read_disagreement");
        CuratedPlanningIntegrationTest.root=Files.createTempDirectory("curated-half-hour-");
        var data=CuratedPlanningIntegrationTest.bundle(nominal,back);
        var publicResult=CuratedPlanningIntegrationTest.ready(data);
        Map<?,?> typed=(Map<?,?>)((Map<?,?>)publicResult.get("outbound")).get("source_duration");
        check(typed.get("kind").equals("nominal_half_hour")&&typed.get("unit").equals("half_hour"),"public type survives envelope");
        check(CityPlanningSelection.underConfiguredLimit(publicResult,151),"nominal150 below151");
        check(!CityPlanningSelection.underConfiguredLimit(publicResult,150),"nominal150 is not exclusive upper150");
        check(CuratedPlanningIntegrationTest.lookup(data,TODAY.plusDays(91)).get("status").equals("planning_pending"),"expiry unchanged");
        check(CuratedPlanningIntegrationTest.lookup(CuratedPlanningIntegrationTest.revoked(data),TODAY).get("reason").equals("reference_revoked"),"revocation unchanged");
        System.out.println("Curated half-hour synthetic: "+checks+" arithmetic assertions + "+CuratedPlanningIntegrationTest.checks+" protected-envelope assertions passed");
    }
}
