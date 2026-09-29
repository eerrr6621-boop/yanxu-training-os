package com.training;

import java.util.*;

/** Host connects the reviewed M07 adapter once at startup. No client can install a provider. */
public final class TrainingSummariesFeedbackSource {
    private TrainingSummariesFeedbackSource() {}
    public interface ReviewedProvider {
        Map<String,Object> capture(Auth.Session session,long project,String organization)throws Exception;
        void validate(Object snapshot)throws Exception;
    }
    private static volatile ReviewedProvider provider;
    public static void connect(ReviewedProvider reviewedProvider) {
        synchronized(Api.MUTATION_LOCK){if(provider!=null)throw new IllegalStateException("M07总结来源已经连接");provider=Objects.requireNonNull(reviewedProvider);}
    }
    static boolean connected(){return provider!=null;}
    static boolean canRead(Auth.Session session,String organization)throws Exception {
        OrganizationAccessStore.person(session);return OrganizationAccessStore.authorize(session,"survey.read",OrganizationAccess.Action.VIEW,organization).allowed();
    }
    static Map<String,Object> capture(Auth.Session session,long project,String organization)throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK))throw new IllegalStateException("总结来源须在业务锁内读取");
        ReviewedProvider p=provider;if(p==null)return legacy();if(!canRead(session,organization))return hidden();
        Map<String,Object> out=p.capture(session,project,organization);p.validate(out);Map<String,Object> copied=object(Json.parse(TrainingSummariesWorkflow.canonical(out)));
        if("AVAILABLE".equals(copied.get("status"))){Map<String,Object> value=object(copied.get("value"));if(!Objects.equals(value.get("organization_code"),organization)||!(value.get("project_id") instanceof Number n)||new java.math.BigDecimal(n.toString()).longValueExact()!=project)throw new Api.ApiException(409,"问卷汇总的项目来源不一致");}
        return copied;
    }
    static void validate(Object snapshot)throws Exception {
        if(TrainingSummariesWorkflow.canonical(snapshot).equals(TrainingSummariesWorkflow.canonical(legacy()))||TrainingSummariesWorkflow.canonical(snapshot).equals(TrainingSummariesWorkflow.canonical(hidden())))return;
        if(provider==null)throw new Api.ApiException(503,"已复核问卷来源适配器未连接");provider.validate(snapshot);
    }
    static Map<String,Object> project(Map<String,Object> sources,boolean visible) {
        if(!visible&&!TrainingSummariesWorkflow.canonical(sources.get("feedback")).equals(TrainingSummariesWorkflow.canonical(legacy()))){sources.put("feedback",hidden());sources.put("source_version",null);sources.put("visibility",sources.containsKey("visibility")?"DELIVERY_AND_FEEDBACK_HIDDEN":"FEEDBACK_HIDDEN");}
        return sources;
    }
    static Map<String,Object> legacy(){return TrainingSummariesWorkflow.map("status","UNAVAILABLE","reason","M07_PREVIEW_ONLY","value",null,"policyConfirmed",false,"canCommit",false,"historyAvailable",false);}
    static Map<String,Object> hidden(){return TrainingSummariesWorkflow.map("status","UNAVAILABLE","reason","SURVEY_READ_REQUIRED","value",null);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object v){if(!(v instanceof Map<?,?>))throw new IllegalArgumentException("问卷来源格式错误");return (Map<String,Object>)v;}
}
