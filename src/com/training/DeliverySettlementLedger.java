package com.training;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Append-only accrual and cash evidence. Methods run inside the host's authorized transaction. */
public final class DeliverySettlementLedger {
    private DeliverySettlementLedger() {}
    private static final int MAX_EVENTS=10000;
    private static final long MAX_CHARS=8L*1024*1024;
    private static final Set<String> TOTAL_FIELDS=Set.of("activity","source_record_id","source_fact_revision","source_code","service_date","organization_code","teacher_id","teacher_code","system_teacher_code","course_id","course_code","amount","hours","policy_version","rate_version","payroll_month");
    private static final Set<String> ENTRY_META=Set.of("entry_code","chain_code","previous_entry_code","kind","evidence_code","correction_group_code","confirmed_at","currency","data_mode","hours_before","hours_after");
    private static final Set<String> OPEN_KINDS=Set.of("CONFIRMED","LEGACY_OPENING");
    private static final Set<String> HOUR_KEYS=Set.of("ESTIMATED","PLANNED","ACTUAL","PAYABLE");
    private static final Set<String> HEAD_FIELDS=Set.of("chain_code","version","current_entry_code","current_service_date","current_amount","activity","teacher_id","total","payment_count","recorded_paid_amount");
    private static final Set<String> PAYMENT_FIELDS=Set.of("entry_code","chain_code","accrual_entry_codes","kind","payment_date","amount","currency","evidence_code","recorded_at","actor_code","account_id","historical");
    private record Checked(Map<String,Object> row,Map<String,Object> head,List<Map<String,Object>> accruals,List<Map<String,Object>> payments,BigDecimal paid) {}
    static void init() throws SQLException {
        Db.exec("CREATE TABLE IF NOT EXISTS m05_financial_chains(chain_code VARCHAR(96) PRIMARY KEY,project_id BIGINT NOT NULL REFERENCES projects(id),organization_code VARCHAR(96) NOT NULL,version BIGINT NOT NULL,payload CLOB NOT NULL)");
        Db.exec("CREATE TABLE IF NOT EXISTS m05_financial_entries(entry_code VARCHAR(96) PRIMARY KEY,chain_code VARCHAR(96) NOT NULL REFERENCES m05_financial_chains(chain_code),sequence_no BIGINT NOT NULL,payload CLOB NOT NULL,UNIQUE(chain_code,sequence_no))");
        Db.exec("CREATE TABLE IF NOT EXISTS m05_payment_events(entry_code VARCHAR(96) PRIMARY KEY,chain_code VARCHAR(96) NOT NULL REFERENCES m05_financial_chains(chain_code),sequence_no BIGINT NOT NULL,payload CLOB NOT NULL,UNIQUE(chain_code,sequence_no))");
    }
    static Map<String,Object> head(String code) throws Exception {
        Checked checked=checked(code);return checked==null?null:copy(checked.head());
    }
    static Map<String,Object> open(long project,String org,String chain,Map<String,Object> total,String entryCode,String kind,String evidence) throws Exception {
        transaction(); if(head(chain)!=null)fail("财务链已存在，请提交更正");
        validateTotal(total);technical(chain,false);technical(entryCode,false);technical(evidence,false);technical(org,false);
        if(project<1||!org.equals(total.get("organization_code"))||!OPEN_KINDS.contains(kind))fail("财务链来源或首笔类型无效");
        if("LEGACY_OPENING".equals(kind)!= "LEGACY".equals(total.get("activity")))fail("旧制迁移首笔与酬金类型不一致");
        Map<String,Object> h=new LinkedHashMap<>();h.put("chain_code",chain);h.put("version",1L);h.put("current_entry_code",entryCode);
        h.put("current_service_date",total.get("service_date"));h.put("current_amount",money(total.get("amount")).toPlainString());
        h.put("activity",total.get("activity"));h.put("teacher_id",total.get("teacher_id"));h.put("total",copy(total));
        h.put("payment_count",0L);h.put("recorded_paid_amount","0.00");
        Db.exec("INSERT INTO m05_financial_chains VALUES(?,?,?,?,?)",chain,project,org,1L,Json.write(h));
        Map<String,Object> e=entry(total,entryCode,chain,null,kind,evidence,null,zeroHours(),map(total.get("hours")));writeEntry(chain,1,e);return copy(h);
    }
    static Map<String,Object> replace(String chain,String expected,Map<String,Object> total,String entryCode,String evidence) throws Exception {
        transaction();validateTotal(total);technical(entryCode,false);technical(evidence,false);Map<String,Object> h=required(chain);if(!Objects.equals(expected,h.get("current_entry_code")))fail("财务依据已更新，请刷新");
        Map<String,Object> before=map(h.get("total"));
        if(!sameId(before.get("teacher_id"),total.get("teacher_id"))||!Objects.equals(before.get("activity"),total.get("activity"))||!Objects.equals(before.get("organization_code"),total.get("organization_code")))fail("更正不能转移讲师或酬金类型，请分别办理冲回和新申报");
        long seq=count("m05_financial_entries",chain);String previous=expected;
        if(Objects.equals(before.get("service_date"),total.get("service_date"))) {
            Map<String,Object> delta=copy(total);delta.put("amount",money(total.get("amount")).subtract(money(before.get("amount"))).toPlainString());delta.put("hours",hours(map(total.get("hours")),map(before.get("hours")),false));
            writeEntry(chain,seq+1,entry(delta,entryCode,chain,previous,"ADJUSTMENT",evidence,null,map(before.get("hours")),map(total.get("hours"))));
        } else {
            String group="M05-CROSS-"+UUID.randomUUID(),reverse="M05-REV-"+UUID.randomUUID();Map<String,Object> undo=copy(before);
            undo.put("amount",money(before.get("amount")).negate().toPlainString());undo.put("hours",hours(map(before.get("hours")),null,true));
            writeEntry(chain,seq+1,entry(undo,reverse,chain,previous,"REVERSAL",evidence,group,map(before.get("hours")),zeroHours()));
            writeEntry(chain,seq+2,entry(total,entryCode,chain,reverse,"REBOOK",evidence,group,zeroHours(),map(total.get("hours"))));
        }
        long version=number(h.get("version"));h.put("version",version+1);h.put("current_entry_code",entryCode);h.put("current_service_date",total.get("service_date"));h.put("current_amount",money(total.get("amount")).toPlainString());h.put("total",copy(total));
        cas("UPDATE m05_financial_chains SET version=?,payload=? WHERE chain_code=? AND version=?",version+1,Json.write(h),chain,version);return copy(h);
    }
    static Map<String,Object> payment(String chain,String expected,String date,String amount,String evidence,String actor,long account,boolean historical) throws Exception {
        transaction();technical(evidence,false);technical(actor,false);if(account<1)fail("缺少支付登记账号");
        Checked current=checked(chain);if(current==null)fail("尚未核准应付金额");Map<String,Object> h=copy(current.head());
        if(!Objects.equals(expected,h.get("current_entry_code")))fail("支付依据已更新，请刷新");
        LocalDate day;try{day=LocalDate.parse(date);}catch(Exception bad){throw new Api.ApiException(400,"支付日期无效");}
        if(day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))fail("不能登记未来的实际支付日期");
        BigDecimal value=money(amount),paid=current.paid(),balance=money(h.get("current_amount")).subtract(paid);
        if(value.signum()==0||value.signum()!=balance.signum()||value.abs().compareTo(balance.abs())>0)fail("本次付款或退款金额须与未结余额方向一致，且不能超过余额");
        Map<String,Object> last=Db.one("SELECT payload FROM m05_financial_entries WHERE entry_code=?",expected);
        if(last==null)fail("缺少支付依据");Map<String,Object> entry=json(last.get("payload"));
        if(historical&&(!"LEGACY_OPENING".equals(entry.get("kind"))||!"LEGACY".equals(entry.get("activity"))))fail("历史付款只能随旧制核对迁移登记");
        if(!historical&&day.isBefore(Instant.parse(entry.get("confirmed_at").toString()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()))fail("实际付款或退款日期不能早于本次核准；历史款须通过迁移核对登记");
        Map<String,Object> p=new LinkedHashMap<>();p.put("entry_code","M05-PAY-"+UUID.randomUUID());p.put("chain_code",chain);p.put("accrual_entry_codes",List.of(expected));
        p.put("kind",value.signum()>0?"PAYMENT":"REFUND");p.put("payment_date",day.toString());p.put("amount",value.toPlainString());p.put("currency","CNY");p.put("evidence_code",evidence);p.put("recorded_at",Instant.now().toString());p.put("actor_code",actor);p.put("account_id",account);p.put("historical",historical);
        long paymentCount=number(h.get("payment_count"));
        Db.exec("INSERT INTO m05_payment_events VALUES(?,?,?,?)",p.get("entry_code"),chain,paymentCount+1,Json.write(p));
        h.put("payment_count",paymentCount+1);h.put("recorded_paid_amount",paid.add(value).toPlainString());
        // The full prior payload also compares the previous cash count and paid amount, not just accrual version.
        cas("UPDATE m05_financial_chains SET payload=? WHERE chain_code=? AND version=? AND payload=?",Json.write(h),chain,number(h.get("version")),current.row().get("payload"));return p;
    }
    static Map<String,Object> balance(String chain) throws Exception {
        Checked checked=checked(chain);return checked==null?null:balance(checked);
    }
    private static Map<String,Object> balance(Checked checked) {
        Map<String,Object> out=new LinkedHashMap<>(checked.head());out.remove("total");out.remove("payment_count");out.remove("recorded_paid_amount");
        BigDecimal paid=checked.paid(),due=new BigDecimal(checked.head().get("current_amount").toString()).subtract(paid);
        out.put("paid_amount",paid.toPlainString());out.put("balance",due.toPlainString());return out;
    }
    static List<Map<String,Object>> entries(String table,String chain) throws Exception {
        if(!Set.of("m05_financial_entries","m05_payment_events").contains(table))throw new IllegalArgumentException("Unknown ledger table");
        Checked checked=checked(chain);return checked==null?List.of():table.equals("m05_financial_entries")?checked.accruals():checked.payments();
    }
    static Map<String,Object> project(long project,String org) throws Exception {
        List<Map<String,Object>> accruals=new ArrayList<>(),payments=new ArrayList<>(),heads=new ArrayList<>();
        Map<String,Object> extent=Db.one("SELECT COUNT(*) AS n,COALESCE(SUM(CHAR_LENGTH(payload)),0) AS chars FROM m05_financial_chains WHERE project_id=?",project);
        long chars=number(extent.get("chars")),n=number(extent.get("n"));
        for(String table:List.of("m05_financial_entries","m05_payment_events")) {
            Map<String,Object> part=Db.one("SELECT COUNT(*) AS n,COALESCE(SUM(CHAR_LENGTH(e.payload)),0) AS chars FROM "+table+" e JOIN m05_financial_chains c ON c.chain_code=e.chain_code WHERE c.project_id=?",project);
            n+=number(part.get("n"));chars+=number(part.get("chars"));
        }
        capacity(n,chars);
        for(Map<String,Object> row:Db.query("SELECT chain_code,organization_code FROM m05_financial_chains WHERE project_id=? ORDER BY chain_code",project)) {
            if(!org.equals(row.get("organization_code")))fail("财务链组织来源不一致");String chain=row.get("chain_code").toString();
            Checked checked=checked(chain);if(checked==null||number(checked.row().get("project_id"))!=project)fail("财务链项目来源不一致");
            accruals.addAll(checked.accruals());payments.addAll(checked.payments());heads.add(balance(checked));
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("schema_version","M05-FINANCIAL-1");out.put("project_id",project);out.put("organization_code",org);out.put("accrual_entries",accruals);out.put("payment_entries",payments);out.put("chain_heads",heads);
        out.put("source_version",digest(Json.write(out)));return out;
    }

    /** Validate frozen data structurally; no present-day rules, prices, grades or identities are replayed. */
    private static Checked checked(String chain) throws Exception {
        try {
            technical(chain,false);
            Map<String,Object> extent=Db.one("SELECT COUNT(*) AS n,COALESCE(SUM(CHAR_LENGTH(payload)),0) AS chars FROM m05_financial_chains WHERE chain_code=?",chain);
            long count=number(extent.get("n")),chars=number(extent.get("chars"));
            for(String table:List.of("m05_financial_entries","m05_payment_events")) {
                Map<String,Object> part=Db.one("SELECT COUNT(*) AS n,COALESCE(SUM(CHAR_LENGTH(payload)),0) AS chars FROM "+table+" WHERE chain_code=?",chain);
                count+=number(part.get("n"));chars+=number(part.get("chars"));
            }
            capacity(count,chars);
            Map<String,Object> row=Db.one("SELECT * FROM m05_financial_chains WHERE chain_code=?",chain);
            if(row==null) {if(count!=0)fail("财务链缺少总账记录");return null;}
            Map<String,Object> h=json(row.get("payload"));exact(h,HEAD_FIELDS);
            if(!chain.equals(h.get("chain_code"))||!sameId(row.get("version"),h.get("version"))||number(h.get("version"))<1||number(row.get("project_id"))<1)fail("财务链版本或身份不一致");
            Map<String,Object> total=map(h.get("total"));validateTotal(total);
            if(!row.get("organization_code").equals(total.get("organization_code"))||!h.get("activity").equals(total.get("activity"))
                    ||!sameId(h.get("teacher_id"),total.get("teacher_id"))||!h.get("current_service_date").equals(total.get("service_date"))
                    ||money(h.get("current_amount")).compareTo(money(total.get("amount")))!=0)fail("财务链头与冻结总额不一致");
            List<Map<String,Object>> entries=stored("m05_financial_entries",chain),payments=stored("m05_payment_events",chain);
            if(entries.isEmpty())fail("财务链缺少原始分录");
            BigDecimal accrued=new BigDecimal("0.00");String prior=null,service=null,group=null;long version=0;
            Instant lastTime=null;Map<String,Object> lastTotal=null,lastHours=null;Set<String> codes=new HashSet<>(),groups=new HashSet<>();
            Map<String,Map<String,Object>> byCode=new HashMap<>();Map<String,Integer> positions=new HashMap<>();Map<String,BigDecimal> totalsAtEntry=new HashMap<>();
            Set<String> entryFields=new HashSet<>(TOTAL_FIELDS);entryFields.addAll(ENTRY_META);
            for(int index=0;index<entries.size();index++) {
                Map<String,Object> entry=entries.get(index);exact(entry,entryFields);String code=technical(entry.get("entry_code"),false),kind=technical(entry.get("kind"),false);
                if(!chain.equals(entry.get("chain_code"))||!Objects.equals(prior,entry.get("previous_entry_code"))||!codes.add(code))fail("财务分录不连续或编号重复");
                if(!"CNY".equals(entry.get("currency"))||!"CONFIGURED".equals(entry.get("data_mode")))fail("财务分录币种或模式无效");
                technical(entry.get("evidence_code"),false);Instant when=instant(entry.get("confirmed_at"));
                if(lastTime!=null&&when.isBefore(lastTime))fail("财务分录时间不连续");lastTime=when;
                Map<String,Object> base=base(entry);validateTotalFields(base,true);
                if(!Objects.equals(total.get("organization_code"),entry.get("organization_code"))||!Objects.equals(total.get("activity"),entry.get("activity"))||!sameId(total.get("teacher_id"),entry.get("teacher_id")))fail("财务分录不能转移机构、讲师或酬金类型");
                BigDecimal value=money(entry.get("amount"));String date=entry.get("service_date").toString();Map<String,Object> entryHours=map(entry.get("hours")),beforeHours=map(entry.get("hours_before")),afterHours=map(entry.get("hours_after"));
                validateHourState(beforeHours);validateHourState(afterHours);
                if(!sameHours(beforeHours,index==0?zeroHours():lastHours))fail("课时变更前状态与上笔完整状态不一致");
                if(!sameHours(entryHours,hours(afterHours,beforeHours,false)))fail("课时差额与变更前后完整状态不一致");
                if(index==0) {
                    if(!OPEN_KINDS.contains(kind)||entry.get("correction_group_code")!=null||value.signum()<0)fail("财务首笔类型或金额无效");
                    if("LEGACY_OPENING".equals(kind)!= "LEGACY".equals(entry.get("activity")))fail("旧制迁移首笔与酬金类型不一致");
                    version=1;service=date;
                } else if("ADJUSTMENT".equals(kind)) {
                    if(group!=null||entry.get("correction_group_code")!=null||!date.equals(service))fail("同日调整日期或分组无效");
                    version++;
                } else if("REVERSAL".equals(kind)) {
                    String currentGroup=technical(entry.get("correction_group_code"),false);
                    if(group!=null||!groups.add(currentGroup)||!date.equals(service)||value.compareTo(accrued.negate())!=0)fail("跨日冲回未完整对应原业务总额");
                    if(!sameHours(afterHours,zeroHours()))fail("跨日冲回后原业务日期课时必须为零");
                    group=currentGroup;
                } else if("REBOOK".equals(kind)) {
                    if(group==null||!group.equals(entry.get("correction_group_code"))||date.equals(service)||value.signum()<0||accrued.signum()!=0)fail("跨日重记未成对对应冲回记录");
                    if(!Objects.equals(entries.get(index-1).get("evidence_code"),entry.get("evidence_code")))fail("跨日冲回与重记依据不一致");
                    group=null;service=date;version++;
                } else fail("财务分录类型或顺序无效");
                lastHours=afterHours;
                accrued=accrued.add(value);if(accrued.signum()<0)fail("累计应付金额不能为负");
                totalsAtEntry.put(code,accrued);byCode.put(code,entry);positions.put(code,index);prior=code;lastTotal=base;
            }
            if(group!=null||!Objects.equals(prior,h.get("current_entry_code"))||version!=number(h.get("version"))||accrued.compareTo(money(h.get("current_amount")))!=0||!Objects.equals(service,h.get("current_service_date")))fail("财务分录与当前总账不一致");
            for(String key:TOTAL_FIELDS)if(!Set.of("amount","hours").contains(key)&&!canonical(total.get(key)).equals(canonical(lastTotal.get(key))))fail("财务总账的冻结来源与最新分录不一致");
            if(!sameHours(lastHours,map(total.get("hours"))))fail("财务总账课时与最新完整冻结状态不一致");
            BigDecimal paid=new BigDecimal("0.00");Instant previousPayment=null;int latestAccrual=-1,lastPaymentReference=-1;
            for(Map<String,Object> payment:payments) {
                exact(payment,PAYMENT_FIELDS);String paymentCode=technical(payment.get("entry_code"),false);
                if(!chain.equals(payment.get("chain_code"))||!codes.add(paymentCode)||!"CNY".equals(payment.get("currency")))fail("支付记录身份、编号或币种不一致");
                technical(payment.get("actor_code"),false);technical(payment.get("evidence_code"),false);if(number(payment.get("account_id"))<1)fail("支付记录缺少账号");
                if(!(payment.get("historical") instanceof Boolean))fail("支付记录历史标志无效");
                Instant recorded=instant(payment.get("recorded_at"));if(previousPayment!=null&&recorded.isBefore(previousPayment))fail("支付登记顺序无效");previousPayment=recorded;
                if(!(payment.get("accrual_entry_codes") instanceof List<?> links)||links.size()!=1)fail("支付记录须对应唯一核准依据");
                List<?> links=(List<?>)payment.get("accrual_entry_codes");String linked=technical(links.get(0),false);Map<String,Object> reference=byCode.get(linked);
                if(reference==null||"REVERSAL".equals(reference.get("kind"))||instant(reference.get("confirmed_at")).isAfter(recorded))fail("支付记录对应的核准依据无效");
                if(Boolean.TRUE.equals(payment.get("historical"))&&(!"LEGACY_OPENING".equals(reference.get("kind"))||!"LEGACY".equals(reference.get("activity"))))fail("历史付款没有旧制迁移依据");
                while(latestAccrual+1<entries.size()&&!instant(entries.get(latestAccrual+1).get("confirmed_at")).isAfter(recorded))latestAccrual++;
                String latest=latestAccrual<0?null:entries.get(latestAccrual).get("entry_code").toString();
                int referenceIndex=positions.get(linked);
                if(referenceIndex<lastPaymentReference||(!linked.equals(latest)
                        &&(referenceIndex+1>=entries.size()||!instant(entries.get(referenceIndex+1).get("confirmed_at")).equals(recorded))))
                    fail("支付记录未绑定登记时的当前核准依据");
                lastPaymentReference=referenceIndex;
                LocalDate day=day(payment.get("payment_date"));if(day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))fail("支付记录使用未来日期");
                if(!Boolean.TRUE.equals(payment.get("historical"))&&day.isBefore(instant(reference.get("confirmed_at")).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()))fail("支付日期早于核准依据");
                BigDecimal value=money(payment.get("amount")),due=totalsAtEntry.get(linked).subtract(paid);
                if(value.signum()==0||!(value.signum()>0?"PAYMENT":"REFUND").equals(payment.get("kind"))||value.signum()!=due.signum()||value.abs().compareTo(due.abs())>0)fail("支付或退款方向、金额与登记时余额不一致");
                paid=paid.add(value);
            }
            if(number(h.get("payment_count"))!=payments.size()||paid.compareTo(money(h.get("recorded_paid_amount")))!=0)fail("支付事件数量或已付金额与总账锚点不一致");
            return new Checked(row,h,entries,payments,paid);
        } catch(Api.ApiException invalid) {if(invalid.code==409)throw invalid;throw new Api.ApiException(409,"冻结财务记录格式或内容无法核实");}
          catch(RuntimeException invalid) {throw new Api.ApiException(409,"冻结财务记录格式或内容无法核实");}
    }
    private static List<Map<String,Object>> stored(String table,String chain) throws Exception {
        List<Map<String,Object>> result=new ArrayList<>();long sequence=0;
        for(Map<String,Object> row:Db.query("SELECT * FROM "+table+" WHERE chain_code=? ORDER BY sequence_no",chain)) {
            Map<String,Object> payload=json(row.get("payload"));
            if(number(row.get("sequence_no"))!=++sequence||!chain.equals(payload.get("chain_code"))||!Objects.equals(row.get("entry_code"),payload.get("entry_code")))fail("财务事件表与冻结记录头不一致");
            result.add(payload);
        }
        return result;
    }
    private static void capacity(long events,long chars) throws Exception {if(events<0||chars<0||events>MAX_EVENTS||chars>MAX_CHARS)fail("财务历史超过核验容量，请联系管理员核对");}
    private static Map<String,Object> base(Map<String,Object> entry) {Map<String,Object> result=new LinkedHashMap<>();for(String key:TOTAL_FIELDS)result.put(key,entry.get(key));return result;}
    private static Map<String,Object> zeroHours() {Map<String,Object> result=new LinkedHashMap<>();for(String key:HOUR_KEYS)result.put(key,"0");return result;}
    private static void validateHourState(Map<String,Object> state) throws Exception {
        exact(state,HOUR_KEYS);for(Object value:state.values())if(value!=null){if(!(value instanceof String text)||!text.matches("[0-9]{1,24}(?:\\.[0-9]{1,8})?"))fail("完整课时状态须为非负十进制文本或未知");}
    }
    private static boolean sameHours(Map<String,Object> left,Map<String,Object> right) {for(String key:HOUR_KEYS)if(!sameDecimal(left.get(key),right.get(key)))return false;return true;}
    private static boolean sameDecimal(Object left,Object right) {return left==null||right==null?left==right:new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()))==0;}
    private static void exact(Map<String,Object> map,Set<String> fields) throws Exception {if(!map.keySet().equals(fields))fail("冻结财务记录字段不完整或含未知字段");}
    private static String technical(Object value,boolean nullable) throws Exception {if(value==null&&nullable)return null;if(!(value instanceof String text)||!text.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}"))fail("财务记录编码无效");return (String)value;}
    private static LocalDate day(Object value) throws Exception {if(!(value instanceof String text)||!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))fail("财务日期格式无效");return LocalDate.parse(value.toString());}
    private static Instant instant(Object value) throws Exception {if(!(value instanceof String)||value.toString().length()>40)fail("财务登记时间格式无效");Instant result=Instant.parse(value.toString());if(result.isAfter(Instant.now()))fail("财务登记时间不能在未来");return result;}
    private static String canonical(Object value) {return Json.write(normalize(value));}
    private static Object normalize(Object value) {if(value instanceof Number n)return new BigDecimal(n.toString()).stripTrailingZeros();if(value instanceof Map<?,?> m){Map<String,Object> result=new TreeMap<>();m.forEach((k,v)->result.put(k.toString(),normalize(v)));return result;}if(value instanceof List<?> l)return l.stream().map(DeliverySettlementLedger::normalize).toList();return value;}
    private static Map<String,Object> entry(Map<String,Object> total,String code,String chain,String previous,String kind,String evidence,String group,Map<String,Object> before,Map<String,Object> after) throws Exception {
        Map<String,Object> e=copy(total);e.put("hours_before",copy(before));e.put("hours_after",copy(after));e.put("entry_code",code);e.put("chain_code",chain);e.put("previous_entry_code",previous);e.put("kind",kind);e.put("evidence_code",evidence);e.put("correction_group_code",group);e.put("confirmed_at",Instant.now().toString());e.put("currency","CNY");e.put("data_mode","CONFIGURED");e.put("amount",money(total.get("amount")).toPlainString());return e;
    }
    private static void writeEntry(String chain,long sequence,Map<String,Object> e) throws Exception {Db.exec("INSERT INTO m05_financial_entries VALUES(?,?,?,?)",e.get("entry_code"),chain,sequence,Json.write(e));}
    private static BigDecimal paid(String chain) throws Exception {Checked checked=checked(chain);return checked==null?new BigDecimal("0.00"):checked.paid();}
    private static long count(String table,String chain) throws Exception {return number(Db.one("SELECT COUNT(*) AS n FROM "+table+" WHERE chain_code=?",chain).get("n"));}
    private static Map<String,Object> required(String chain) throws Exception {Map<String,Object> h=head(chain);if(h==null)fail("尚未核准应付金额");return h;}
    private static void validateTotal(Map<String,Object> total) throws Exception {validateTotalFields(total,false);}
    private static void validateTotalFields(Map<String,Object> total,boolean signed) throws Exception {
        exact(total,TOTAL_FIELDS);if(!signed&&money(total.get("amount")).signum()<0)fail("更正后的总应付金额不能为负");money(total.get("amount"));day(total.get("service_date"));
        if(!Set.of("TEACHING","SOLO_DEVELOPMENT","JOINT_DEVELOPMENT","LEGACY").contains(total.get("activity")))fail("酬金类型无效");
        technical(total.get("organization_code"),false);technical(total.get("source_code"),false);technical(total.get("system_teacher_code"),false);
        if(number(total.get("teacher_id"))<1||!("TEACHER-"+number(total.get("teacher_id"))).equals(total.get("system_teacher_code")))fail("讲师身份无法核实");
        for(String key:List.of("source_record_id","source_fact_revision","course_id"))if(total.get(key)!=null&&number(total.get(key))<1)fail("财务来源编号无效");
        for(String key:List.of("teacher_code","course_code"))technical(total.get(key),true);
        for(String key:List.of("policy_version","rate_version"))technical(total.get(key),false);
        if(total.get("payroll_month")!=null){if(!(total.get("payroll_month") instanceof String month)||!month.matches("[0-9]{4}-[0-9]{2}"))fail("工资月份无效");YearMonth.parse(total.get("payroll_month").toString());}
        Map<String,Object> hours=map(total.get("hours"));exact(hours,HOUR_KEYS);
        for(Object value:hours.values())if(value!=null){if(!(value instanceof String text)||!text.matches("-?[0-9]{1,24}(?:\\.[0-9]{1,8})?"))fail("课时须为冻结十进制文本");if(!signed&&new BigDecimal(value.toString()).signum()<0)fail("总课时不能为负");}
    }
    private static Map<String,Object> hours(Map<String,Object> current,Map<String,Object> previous,boolean negative) throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();for(String k:List.of("ESTIMATED","PLANNED","ACTUAL","PAYABLE")){Object c=current.get(k),p=previous==null?null:previous.get(k);out.put(k,c==null||(previous!=null&&p==null)?null:(negative?new BigDecimal(c.toString()).negate():previous==null?new BigDecimal(c.toString()):new BigDecimal(c.toString()).subtract(new BigDecimal(p.toString()))).toPlainString());}return out;
    }
    static BigDecimal money(Object value) throws Exception {if(!(value instanceof String s)||!s.matches("-?[0-9]{1,24}(?:\\.[0-9]{1,2})?"))throw new Api.ApiException(400,"金额须为最多两位小数的十进制文本");return new BigDecimal(s).setScale(2,RoundingMode.UNNECESSARY);}
    private static void transaction() throws Exception {if(Db.get().getAutoCommit()||!Thread.holdsLock(Api.MUTATION_LOCK))throw new IllegalStateException("Financial write requires host transaction and lock");}
    private static void cas(String sql,Object... values) throws Exception {try(PreparedStatement p=Db.get().prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);if(p.executeUpdate()!=1)fail("财务版本已变化");}}
    private static long number(Object v){return new BigDecimal(v.toString()).longValueExact();}
    private static boolean sameId(Object left,Object right){return left==null||right==null?left==right:number(left)==number(right);}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object v) throws Exception {if(!(v instanceof Map<?,?>))throw new Api.ApiException(409,"财务记录格式无效");return (Map<String,Object>)v;}
    private static Map<String,Object> json(Object v) throws Exception{return map(Json.parse(v.toString()));}
    private static Map<String,Object> copy(Map<String,Object> v) throws Exception{return json(Json.write(v));}
    private static String digest(String v){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void fail(String message) throws Api.ApiException {throw new Api.ApiException(409,message);}
}
