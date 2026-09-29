package com.training;

import java.math.BigDecimal;
import java.util.*;

/** Read-only deletion guard; the host owns authentication and the actual teacher deletion. */
public final class DeliverySettlementReferences {
    private DeliverySettlementReferences() {}
    private static final long MAX_RECORDS=20000,MAX_CHARS=16L*1024*1024;
    private static final String INVALID="课酬引用历史暂时无法完整核实，不能删除讲师；请使用出库保留档案";
    private static final String REFERENCED="该讲师已有开发、旧账或课酬历史记录，不能删除；请使用出库保留历史档案";
    private static final Set<String> STATES=Set.of("DRAFT","SUBMITTED","RETURNED","APPROVED","CONFIRMED","WITHDRAWN");
    private static final Set<String> KINDS=Set.of("DEVELOPMENT","MIGRATION","ADJUSTMENT","WAIVER");

    /** Invoke from the existing authorized teacher-delete branch while holding Api.MUTATION_LOCK. */
    public static void guardTeacherDeletion(long teacherId) throws Exception {
        if(teacherId<1||teacherId>9007199254740991L)throw new Api.ApiException(400,"讲师编号无效");
        synchronized(Api.MUTATION_LOCK) {
            try {
                capacity();
                Map<String,Map<String,Object>> heads=new LinkedHashMap<>();
                Map<String,String> currentJson=new HashMap<>();
                for(Map<String,Object> row:Db.query("SELECT case_code,project_id,organization_code,version,state,kind,payload FROM m05_cases ORDER BY case_code")) {
                    Map<String,Object> payload=casePayload(row,true);
                    String key=string(row.get("case_code"));
                    heads.put(key,row);currentJson.put(key,row.get("payload").toString());
                    referenced(payload,teacherId,0);
                }
                Map<String,Long> versions=new HashMap<>();
                Map<String,String> lastJson=new HashMap<>();
                // Replaced, returned and withdrawn drafts retain their original teacher references here.
                for(Map<String,Object> revision:Db.query("SELECT case_code,version,payload FROM m05_case_revisions ORDER BY case_code,version")) {
                    String key=string(revision.get("case_code"));Map<String,Object> head=heads.get(key);
                    if(head==null)invalid();
                    long version=id(revision.get("version"));
                    if(version!=versions.getOrDefault(key,0L)+1)invalid();
                    Map<String,Object> payload=casePayload(revision,false);
                    if(id(payload.get("project_id"))!=id(head.get("project_id"))
                            ||!Objects.equals(payload.get("organization_code"),head.get("organization_code"))
                            ||!Objects.equals(payload.get("kind"),head.get("kind")))invalid();
                    referenced(payload,teacherId,0);
                    versions.put(key,version);lastJson.put(key,revision.get("payload").toString());
                }
                for(var entry:heads.entrySet())if(!Objects.equals(versions.get(entry.getKey()),id(entry.getValue().get("version")))
                        ||!Objects.equals(lastJson.get(entry.getKey()),currentJson.get(entry.getKey())))invalid();

                long orphan=count(Db.one("SELECT COUNT(*) AS n FROM m05_financial_entries e LEFT JOIN m05_financial_chains c ON c.chain_code=e.chain_code WHERE c.chain_code IS NULL").get("n"));
                if(orphan!=0)invalid();
                // The existing Ledger reader validates all frozen entries, including old corrections.
                for(Map<String,Object> row:Db.query("SELECT chain_code FROM m05_financial_chains ORDER BY chain_code")) {
                    Map<String,Object> head=DeliverySettlementLedger.head(string(row.get("chain_code")));
                    if(head==null)invalid();
                    if(id(head.get("teacher_id"))==teacherId)throw new Api.ApiException(409,REFERENCED);
                }
            } catch(Api.ApiException denied) {
                if(REFERENCED.equals(denied.getMessage()))throw denied;
                throw new Api.ApiException(409,INVALID);
            } catch(Exception malformed) {
                throw new Api.ApiException(409,INVALID);
            }
        }
    }

    private static void capacity() throws Exception {
        long records=0,chars=0;
        for(String table:List.of("m05_cases","m05_case_revisions","m05_financial_chains","m05_financial_entries","m05_payment_events")) {
            Map<String,Object> extent=Db.one("SELECT COUNT(*) AS n,COALESCE(SUM(CHAR_LENGTH(payload)),0) AS chars FROM "+table);
            records+=count(extent.get("n"));chars+=count(extent.get("chars"));
            if(records>MAX_RECORDS||chars>MAX_CHARS)invalid();
        }
    }
    private static Map<String,Object> casePayload(Map<String,Object> row,boolean current) throws Exception {
        Map<String,Object> data=object(Json.parse(string(row.get("payload"))));
        if(!Objects.equals(data.get("case_code"),row.get("case_code"))||id(data.get("version"))!=id(row.get("version"))
                ||!STATES.contains(data.get("state"))||!KINDS.contains(data.get("kind")))invalid();
        id(data.get("project_id"));string(data.get("organization_code"));object(data.get("context"));object(data.get("last_event"));
        if(current&&(id(data.get("project_id"))!=id(row.get("project_id"))
                ||!Objects.equals(data.get("organization_code"),row.get("organization_code"))
                ||!Objects.equals(data.get("state"),row.get("state"))||!Objects.equals(data.get("kind"),row.get("kind"))))invalid();
        businessPayload(string(data.get("kind")),object(data.get("payload")));
        return data;
    }
    private static void businessPayload(String kind,Map<String,Object> payload) throws Exception {
        switch(kind) {
            case "DEVELOPMENT" -> {
                if(!payload.containsKey("lead_teacher_id")||!(payload.get("allocations") instanceof List<?>))invalid();
                for(Object member:(List<?>)payload.get("allocations"))id(object(member).get("teacher_id"));
            }
            case "MIGRATION" -> {if(!payload.containsKey("teacher_id"))invalid();}
            case "ADJUSTMENT" -> {
                if(!payload.containsKey("chain_code")||!payload.containsKey("development"))invalid();
                if(payload.get("development")!=null)businessPayload("DEVELOPMENT",object(payload.get("development")));
            }
            case "WAIVER" -> {if(!payload.containsKey("dispatch_id"))invalid();}
            default -> invalid();
        }
    }
    private static void referenced(Object value,long teacher,int depth) throws Exception {
        if(depth>100)invalid();
        if(value instanceof Map<?,?> map) {
            for(var field:map.entrySet()) {
                if(Set.of("teacher_id","lead_teacher_id").contains(field.getKey())&&field.getValue()!=null
                        &&id(field.getValue())==teacher)throw new Api.ApiException(409,REFERENCED);
                referenced(field.getValue(),teacher,depth+1);
            }
        } else if(value instanceof List<?> list)for(Object item:list)referenced(item,teacher,depth+1);
    }
    private static Map<String,Object> object(Object value) throws Exception {
        if(!(value instanceof Map<?,?>))invalid();Map<String,Object> result=new LinkedHashMap<>();
        for(var field:((Map<?,?>)value).entrySet()){if(!(field.getKey() instanceof String))invalid();result.put((String)field.getKey(),field.getValue());}return result;
    }
    private static String string(Object value) throws Exception {if(!(value instanceof String text)||text.isEmpty())invalid();return value.toString();}
    private static long id(Object value) throws Exception {long result=count(value);if(result<1||result>9007199254740991L)invalid();return result;}
    private static long count(Object value) throws Exception {
        if(!(value instanceof Number)&&!(value instanceof String text&&text.matches("[0-9]{1,16}")))invalid();
        long result=new BigDecimal(value.toString()).longValueExact();if(result<0)invalid();return result;
    }
    private static void invalid() throws Api.ApiException {throw new Api.ApiException(409,INVALID);}
}
