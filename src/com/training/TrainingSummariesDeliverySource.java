package com.training;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** M08 source schema and response projection. Persisted source bytes are never derived from a hidden view. */
public final class TrainingSummariesDeliverySource {
    private TrainingSummariesDeliverySource() {}
    private static final String POLICY="M08-M05-SOURCE-20260923-1";
    private static final Set<String> COUNTS=Set.of("dispatch_count","reviewed_completed_count","pending_count","unverified_completed_count","rejected_count","total_dispatch_count","fact_count","missing_fact_count","verified_count");
    private static final Set<String> HOURS=Set.of("estimated","planned","actual","payable");
    private static final Map<String,Object> COVERAGE=Map.of("estimated_planned","ALL_EFFECTIVE_DISPATCHES","actual_payable","REVIEWED_COMPLETED_AS_OF_DATE","unknown","NULL_PROPAGATES","hour_unit","CLASS45");

    static boolean canRead(Auth.Session session,String org) throws Exception {
        requireLock();OrganizationAccessStore.person(session);
        return OrganizationAccessStore.authorize(session,"delivery.read",OrganizationAccess.Action.VIEW,org).allowed();
    }
    static Map<String,Object> capture(Auth.Session session,long project,String org) throws Exception {
        requireLock();
        if(!canRead(session,org))return unavailable("DELIVERY_READ_REQUIRED");
        Map<String,Object> source=DeliverySettlementIntegration.summaryDeliverySource(session,project);
        validate(source);return source;
    }
    /** Works on a freshly parsed response object, never on persisted bytes; masks the outer fingerprint too. */
    static Map<String,Object> project(String sourceJson,boolean visible) {
        Map<String,Object> out=object(Json.parse(sourceJson));
        if(!visible){out.put("delivery",unavailable("DELIVERY_READ_REQUIRED"));out.put("source_version",null);out.put("visibility","DELIVERY_HIDDEN");}
        return out;
    }
    static Map<String,Object> unavailable(String reason) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("status","UNAVAILABLE");out.put("reason",reason);out.put("value",null);return out;
    }
    /** Strict allowlist for both legacy and newly persisted delivery snapshots. */
    static void validate(Object value) throws Api.ApiException {
        try {
            Map<String,Object> source=object(value);
            if("UNAVAILABLE".equals(source.get("status"))) {
                if(!source.keySet().equals(Set.of("status","reason","value"))||source.get("value")!=null
                        ||!Set.of("M05_SNAPSHOT_NOT_CONNECTED","DELIVERY_READ_REQUIRED").contains(source.get("reason")))invalid();
                return;
            }
            if(!source.keySet().equals(Set.of("status","reason","policy_version","source_version","as_of_date","value","coverage"))
                    ||!"AVAILABLE".equals(source.get("status"))||!POLICY.equals(source.get("policy_version"))
                    ||!(source.get("source_version") instanceof String digest)||!digest.matches("[a-f0-9]{64}")
                    ||!COVERAGE.equals(object(source.get("coverage"))))invalid();
            if(!(source.get("as_of_date") instanceof String date)||!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))invalid();
            LocalDate.parse((String)source.get("as_of_date"));
            Map<String,Object> facts=object(source.get("value"));Set<String> expected=new HashSet<>(COUNTS);expected.addAll(HOURS);
            if(!facts.keySet().equals(expected))invalid();
            for(String count:COUNTS)count(facts,count);
            for(String key:HOURS) {
                Object quantity=facts.get(key);
                if(quantity!=null&&(!(quantity instanceof String s)||!s.matches("[0-9]{1,27}(?:\\.[0-9]{1,8})?")))invalid();
            }
            long total=count(facts,"total_dispatch_count"),effective=count(facts,"dispatch_count"),rejected=count(facts,"rejected_count"),done=count(facts,"reviewed_completed_count");
            if(total!=effective+rejected||effective!=done+count(facts,"pending_count")+count(facts,"unverified_completed_count")
                    ||effective!=count(facts,"fact_count")+count(facts,"missing_fact_count")||count(facts,"verified_count")>count(facts,"fact_count")||done>count(facts,"verified_count"))invalid();
            if(!Objects.equals(source.get("reason"),total==0?"NO_DISPATCHES":null))invalid();
            if(count(facts,"missing_fact_count")>0&&(facts.get("estimated")!=null||facts.get("planned")!=null))invalid();
            if(count(facts,"unverified_completed_count")>0&&(facts.get("actual")!=null||facts.get("payable")!=null))invalid();
            if(effective==0)for(String key:HOURS)if(facts.get(key)==null||new BigDecimal((String)facts.get(key)).signum()!=0)invalid();
        } catch(RuntimeException invalid){throw new Api.ApiException(409,"总结授课来源快照无效");}
    }
    private static long count(Map<String,Object> facts,String key) throws Api.ApiException {
        Object value=facts.get(key);if(!(value instanceof Number))invalid();
        long number=new BigDecimal(value.toString()).longValueExact();if(number<0||number>1000)invalid();return number;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { if(!(value instanceof Map<?,?>))throw new IllegalArgumentException();return (Map<String,Object>)value; }
    private static void requireLock(){if(!Thread.holdsLock(Api.MUTATION_LOCK))throw new IllegalStateException("总结来源须在业务锁内取得");}
    private static void invalid() throws Api.ApiException {throw new Api.ApiException(409,"总结授课来源快照无效");}
}
