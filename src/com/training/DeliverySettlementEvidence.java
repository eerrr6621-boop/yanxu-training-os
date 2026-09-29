package com.training;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** User-readable evidence and receipt notes, immutably bound to the authorized business action. */
public final class DeliverySettlementEvidence {
    private DeliverySettlementEvidence() {}
    private static final Set<String> FIELDS=Set.of("evidence_code","project_id","organization_code","source_code","kind","descriptor","note","reference","actor_code","account_id","created_at");

    public static void init() throws SQLException {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit())throw new SQLException("依据登记初始化不可嵌套事务");
            Db.exec("CREATE TABLE IF NOT EXISTS m05_evidence_records(evidence_code VARCHAR(96) PRIMARY KEY,project_id BIGINT NOT NULL REFERENCES projects(id),organization_code VARCHAR(96) NOT NULL,source_code VARCHAR(96) NOT NULL,kind VARCHAR(96) NOT NULL,payload CLOB NOT NULL)");
        }
    }

    /** Call only after the host has authorized the exact source and business resource, after idempotency lookup. */
    public static String register(Auth.Session session,long project,String org,String source,String kind,Map<String,Object> descriptor,Object proof) throws Exception {
        if(Db.get().getAutoCommit()||!Thread.holdsLock(Api.MUTATION_LOCK))fail(409,"依据登记必须在已授权的业务事务内进行");
        OrganizationAccess.Person actor=OrganizationAccessStore.person(session);
        identity(project,org,source,kind);Map<String,Object> input=proof(proof);checkedDescriptor(descriptor);
        String code="M05-EV-"+UUID.randomUUID();Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("evidence_code",code);payload.put("project_id",project);payload.put("organization_code",org);payload.put("source_code",source);payload.put("kind",kind);
        payload.put("descriptor",Json.parse(canonical(descriptor)));payload.put("note",input.get("note"));payload.put("reference",input.get("reference"));
        payload.put("actor_code",actor.personCode());payload.put("account_id",session.uid);payload.put("created_at",Instant.now().toString());
        Db.exec("INSERT INTO m05_evidence_records(evidence_code,project_id,organization_code,source_code,kind,payload) VALUES(?,?,?,?,?,?)",code,project,org,source,kind,Json.write(payload));return code;
    }

    /** A client may reuse an existing code only for this exact, server-computed descriptor. */
    public static Map<String,Object> resolve(String code,long project,String org,String source,String kind,Map<String,Object> descriptor) throws Exception {
        identity(project,org,source,kind);checkedDescriptor(descriptor);Map<String,Object> record=record(code,project,org);
        if(!source.equals(record.get("source_code"))||!kind.equals(record.get("kind"))||!canonical(descriptor).equals(canonical(object(record.get("descriptor")))))
            fail(409,"所选依据与当前业务、事实版本或内容不一致，请重新填写说明和凭证");
        return record;
    }

    /** The host checks current read scope first. Returned records preserve the original author and text. */
    public static Map<String,Object> view(Collection<String> references,long project,String org) throws Exception {
        if(references==null||references.size()>100)fail(400,"依据引用数量无效");Map<String,Object> out=new LinkedHashMap<>();
        for(String code:references)out.put(code,record(code,project,org));return out;
    }

    /** Organization settings apply across projects; only their registered SETTINGS evidence crosses that boundary. */
    public static Map<String,Object> viewSetting(String code,String org) throws Exception {
        technical(code);technical(org);
        Map<String,Object> row=Db.one("SELECT project_id FROM m05_evidence_records WHERE evidence_code=? AND organization_code=? AND kind='SETTINGS'",code,org);
        if(row==null)fail(409,"执行设置依据尚未登记或不属于当前机构");return record(code,number(row.get("project_id")),org);
    }

    private static Map<String,Object> record(String code,long project,String org) throws Exception {
        technical(code);if(project<1)fail(400,"项目编号无效");technical(org);
        Map<String,Object> row=Db.one("SELECT evidence_code,project_id,organization_code,source_code,kind,CHAR_LENGTH(payload) AS chars FROM m05_evidence_records WHERE evidence_code=?",code);
        if(row==null)fail(409,"依据编号尚未登记，请填写实际说明和凭证");
        if(number(row.get("project_id"))!=project||!org.equals(row.get("organization_code")))fail(409,"所选依据不属于当前项目和机构");
        if(number(row.get("chars"))>20000)fail(409,"冻结依据内容超出核验容量");
        try {
            Map<String,Object> stored=Db.one("SELECT payload FROM m05_evidence_records WHERE evidence_code=?",code);
            Map<String,Object> payload=object(Json.parse(stored.get("payload").toString()));
            if(!payload.keySet().equals(FIELDS)||!code.equals(payload.get("evidence_code"))||number(payload.get("project_id"))!=project||!org.equals(payload.get("organization_code"))
                    ||!Objects.equals(row.get("source_code"),payload.get("source_code"))||!Objects.equals(row.get("kind"),payload.get("kind")))fail(409,"冻结依据记录与索引不一致");
            technical(payload.get("source_code"));technical(payload.get("kind"));technical(payload.get("actor_code"));
            if(number(payload.get("account_id"))<1)fail(409,"冻结依据缺少登记账号");
            if(!(payload.get("created_at") instanceof String text)||text.length()>40||Instant.parse(text).isAfter(Instant.now()))fail(409,"冻结依据登记时间无效");
            Map<String,Object> original=new LinkedHashMap<>();original.put("note",payload.get("note"));original.put("reference",payload.get("reference"));
            Map<String,Object> normalized=proof(original);if(!Objects.equals(normalized.get("note"),payload.get("note"))||!Objects.equals(normalized.get("reference"),payload.get("reference")))fail(409,"冻结依据说明格式无效");
            checkedDescriptor(object(payload.get("descriptor")));return payload;
        } catch(Api.ApiException invalid) {if(invalid.code==409)throw invalid;throw new Api.ApiException(409,"冻结依据内容无法核实");}
          catch(RuntimeException invalid) {throw new Api.ApiException(409,"冻结依据内容无法核实");}
    }
    private static Map<String,Object> proof(Object raw) throws Exception {
        Map<String,Object> proof;
        if(raw instanceof String note) {proof=new LinkedHashMap<>();proof.put("note",note);}
        else proof=object(raw);
        if(!Set.of("note","reference").containsAll(proof.keySet())||!proof.containsKey("note"))fail(400,"依据须包含说明，可另填凭证定位信息");
        Object rawNote=proof.get("note"),rawReference=proof.get("reference");
        if(!(rawNote instanceof String note)||note.isBlank()||note.length()>2000)fail(400,"依据说明不能为空，且不得超过2000字");
        if(rawReference!=null&&(!(rawReference instanceof String reference)||reference.length()>500))fail(400,"凭证定位信息须为不超过500字的文字");
        Map<String,Object> result=new LinkedHashMap<>();result.put("note",rawNote.toString().trim());
        result.put("reference",rawReference==null||rawReference.toString().isBlank()?null:rawReference.toString().trim());return result;
    }
    private static void identity(long project,String org,String source,String kind) throws Exception {if(project<1||project>9007199254740991L)fail(400,"项目编号无效");technical(org);technical(source);technical(kind);}
    private static void checkedDescriptor(Map<String,Object> descriptor) throws Exception {if(descriptor==null||descriptor.isEmpty()||canonical(descriptor).length()>12000)fail(400,"依据业务描述无效");}
    private static void technical(Object code) throws Exception {if(!(code instanceof String text)||!text.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}"))fail(400,"依据引用编号格式无效");}
    private static long number(Object value) {return new BigDecimal(value.toString()).longValueExact();}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws Exception {if(!(value instanceof Map<?,?> map))fail(400,"依据须为JSON对象");return (Map<String,Object>)value;}
    private static String canonical(Object value) {return Json.write(sorted(value));}
    private static Object sorted(Object value) {if(value instanceof Number number)return new BigDecimal(number.toString()).stripTrailingZeros();if(value instanceof Map<?,?> map){Map<String,Object> result=new TreeMap<>();map.forEach((k,v)->result.put(k.toString(),sorted(v)));return result;}if(value instanceof List<?> list)return list.stream().map(DeliverySettlementEvidence::sorted).toList();return value;}
    private static void fail(int status,String message) throws Api.ApiException {throw new Api.ApiException(status,message);}
}
