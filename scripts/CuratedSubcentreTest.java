package com.training;

import java.nio.file.*;
import java.util.*;
import static com.training.CuratedCityDurationTest.*;

/** Synthetic agreeing scope annotations; does not infer geography from city prefixes. */
public final class CuratedSubcentreTest {
    static CuratedCityDuration.Direction scoped(boolean reverse){
        var d=fixture(reverse,scalar("reported_minutes",reverse?"52分钟":"55分钟",reverse?52:55),reverse?"上海虹桥站":"南京副中心站",reverse?"南京副中心":"上海虹桥站");
        return both(d,f->mutable(c(f).get("endpoint_scope")).put(reverse?"to":"from","urban_subcentre_station"));
    }
    public static void main(String[] args)throws Exception{
        var out=scoped(false);var back=scoped(true);String source=Json.write(out.source());
        var result=run(out,back);ready(result,"documented urban subcentre kept separate");
        check(((Map<?,?>)((Map<?,?>)result.get("outbound")).get("endpoint_scope")).get("from").equals("urban_subcentre_station"),"not relabeled main urban");
        check(Boolean.TRUE.equals(result.get("endpoint_difference")),"literal return name variation retained");
        for(Object item:(List<?>)result.get("endpoint_notices"))check(Boolean.FALSE.equals(((Map<?,?>)item).get("transfer_time_estimated")),"no city transfer estimate");
        for(String invalid:List.of("county","unknown","unknown_connection","suburb_assumed"))pending(run(both(out,f->mutable(c(f).get("endpoint_scope")).put("from",invalid)),back),"county_or_unknown_endpoint_scope");
        pending(run(change(out,f->mutable(c(f).get("endpoint_scope")).put("from","main_urban_station"),f->{}),back),"core_read_disagreement");
        pending(run(both(out,f->c(f).put("from_endpoint","南京")),back),"nonliteral_or_mismatched_endpoint");
        pending(run(both(out,f->c(f).put("from_endpoint","燕郊站")),back),"nonliteral_or_mismatched_endpoint");
        pending(run(both(out,f->c(f).put("service_state","planned")),back),"not_active_operating_reference");
        pending(run(both(out,f->c(f).put("effective_on","2026-09-09")),back),"not_yet_effective");
        CuratedPlanningIntegrationTest.root=Files.createTempDirectory("curated-subcentre-");
        var data=CuratedPlanningIntegrationTest.bundle(out,back);var publicResult=CuratedPlanningIntegrationTest.ready(data);
        check(((Map<?,?>)((Map<?,?>)publicResult.get("outbound")).get("endpoint_scope")).get("from").equals("urban_subcentre_station"),"public scope retained");
        check(CityPlanningSelection.underConfiguredLimit(publicResult,56)&&!CityPlanningSelection.underConfiguredLimit(publicResult,55),"unbuffered strict numeric cutoff unchanged");
        check(CuratedPlanningIntegrationTest.lookup(data,TODAY.plusDays(91)).get("status").equals("planning_pending"),"expiry unchanged");
        check(CuratedPlanningIntegrationTest.lookup(CuratedPlanningIntegrationTest.revoked(data),TODAY).get("reason").equals("reference_revoked"),"revocation unchanged");
        check(Json.write(out.source()).equals(source),"source not rewritten");
        System.out.println("Curated subcentre synthetic: "+checks+" scope assertions + "+CuratedPlanningIntegrationTest.checks+" protected-envelope assertions passed");
    }
}
