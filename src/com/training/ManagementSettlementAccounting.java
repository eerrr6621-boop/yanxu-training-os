package com.training;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Pure projection of complete, server-owned M05 frozen ledgers. Never prices or rounds. */
public final class ManagementSettlementAccounting {
    private ManagementSettlementAccounting() {}
    public static final String VERSION = "M06-SETTLEMENT-1";
    public static final List<String> HOURS = List.of("ESTIMATED", "PLANNED", "ACTUAL", "PAYABLE");
    public enum DateBasis { TEACHING, PAYMENT }
    public enum RankMetric { HOURS, FEE }
    public record Query(LocalDate start, LocalDate end, DateBasis dateBasis, RankMetric rankMetric, String hourBasis) {
        public Query {
            if (start == null || end == null || start.isAfter(end) || dateBasis == null || rankMetric == null || !HOURS.contains(hourBasis))
                throw new IllegalArgumentException("统计筛选无效");
            if (dateBasis == DateBasis.PAYMENT && rankMetric != RankMetric.FEE) throw new IllegalArgumentException("支付日期仅支持金额排名，课时不分摊到支付流水");
        }
        boolean contains(LocalDate d) { return !d.isBefore(start) && !d.isAfter(end); }
    }
    /** Constructor is private; a workbook cannot be made from arbitrary browser JSON. */
    public static final class Report {
        private final Map<String,Object> view;
        private Report(Map<String,Object> view) { this.view = freezeMap(view); }
        public Map<String,Object> toMap() { return copyMap(view); }
        public boolean exportable() { return Boolean.TRUE.equals(view.get("export_available")); }
    }
    private record Entry(String code, String chain, String previous, String kind, String activity,
                         LocalDate date, Long teacherId, String teacherCode, Long courseId, String courseCode,
                         BigDecimal amount, Map<String,BigDecimal> hours, Map<String,BigDecimal> before, Map<String,BigDecimal> after, String group, String org, long project) {}
    private record Payment(String code, String chain, String kind, LocalDate date, BigDecimal amount,
                           List<String> refs, String org, long project) {}
    private static final class Chain {
        final Map<String,Entry> entries = new LinkedHashMap<>();
        final List<Payment> payments = new ArrayList<>();
        final List<Entry> ordered = new ArrayList<>();
        Map<String,Object> head;
    }

    public static Report calculate(List<Map<String,Object>> sources, Query q, boolean canExport) {
        Objects.requireNonNull(q); if (sources == null || sources.size() > 10000) throw invalid("来源项目数量无效");
        Set<Long> projects = new HashSet<>(); Set<String> unique = new HashSet<>();
        Map<String,Long> codingCourseScopes = new HashMap<>();
        Map<String,String> formalTeacher = new HashMap<>(), formalCourse = new HashMap<>();
        List<Map<String,Object>> details = new ArrayList<>(), gaps = new ArrayList<>(), versions = new ArrayList<>();
        int count = 0;
        for (Map<String,Object> src : sources) {
            if (!"M05-FINANCIAL-1".equals(src.get("schema_version"))) throw invalid("财务来源版本不支持");
            long project = id(src.get("project_id"), false); String org = code(src.get("organization_code"), false);
            if (!projects.add(project)) throw invalid("来源项目重复");
            String version = code(src.get("source_version"), false);
            versions.add(map("project_id", project, "organization_code", org, "source_version", version));
            Map<String,Chain> chains = new TreeMap<>();
            List<Map<String,Object>> accrual = rows(src.get("accrual_entries"));
            List<Map<String,Object>> cash = rows(src.get("payment_entries"));
            count += accrual.size() + cash.size(); if (count > 50000) throw invalid("财务来源超过50000条，请核对范围");
            for (Map<String,Object> row : accrual) {
                Entry e = entry(row, org, project);
                if (!unique.add(e.code)) throw invalid("分录编号重复");
                chains.computeIfAbsent(e.chain, ignored -> new Chain()).entries.put(e.code, e);
            }
            for (Map<String,Object> row : cash) {
                Payment p = payment(row, org, project);
                if (!unique.add(p.code)) throw invalid("支付或分录编号重复");
                Chain chain = chains.get(p.chain); if (chain == null) throw invalid("支付缺少应计账链");
                chain.payments.add(p);
            }
            for (Map<String,Object> h : rows(src.get("chain_heads"))) {
                Chain chain = chains.get(code(h.get("chain_code"), false));
                if (chain == null || chain.head != null) throw invalid("账链控制总额缺失或重复");
                chain.head = h;
            }
            if (src.containsKey("coding_versions")) validateCoding(rows(src.get("coding_versions")), chains, codingCourseScopes, formalTeacher);
            for (Chain chain : chains.values()) {
                validate(chain);
                if (q.dateBasis == DateBasis.TEACHING) {
                    Map<LocalDate,Entry> latest = new HashMap<>();
                    for (Entry e : chain.ordered) latest.put(e.date,e);
                    for (Entry e : chain.ordered) if (q.contains(e.date)) details.add(detail(e, null, latest.get(e.date)==e));
                } else {
                    for (Payment p : chain.payments) if (q.contains(p.date)) details.add(detail(identity(chain, p), p, false));
                }
            }
        }
        details.sort(Comparator.comparing((Map<String,Object> r) -> (String)r.get("date")).thenComparing(r -> (String)r.get("entry_code")));
        versions.sort(Comparator.comparingLong(r -> ((Number)r.get("project_id")).longValue()));
        Acc total = new Acc(); Map<String,Acc> orgTotals = new TreeMap<>(), teacherTotals = new TreeMap<>(), activities = new TreeMap<>();
        Map<String,Map<String,Object>> teachers = new TreeMap<>();
        Map<String,SortedSet<String>> teacherCodes = new TreeMap<>();
        for (Map<String,Object> d : details) {
            total.add(d); orgTotals.computeIfAbsent((String)d.get("organization_code"), ignored -> new Acc()).add(d);
            activities.computeIfAbsent((String)d.get("activity"), ignored -> new Acc()).add(d);
            String key = teacherKey(d);
            teacherTotals.computeIfAbsent(key, ignored -> new Acc()).add(d);
            teachers.putIfAbsent(key, map("teacher_id", d.get("teacher_id")));
            SortedSet<String> codes = teacherCodes.computeIfAbsent(key, ignored -> new TreeSet<>());
            if (d.get("teacher_code") != null) codes.add(d.get("teacher_code").toString());
            // Missing codes on any selected frozen row block coded export, even if a later row has codes.
            List<String> fields = new ArrayList<>();
            if (d.get("teacher_code") == null) fields.add("teacher_code");
            if (d.get("course_code") == null) fields.add("course_code");
            if (!fields.isEmpty()) gaps.add(map("entry_code", d.get("entry_code"), "organization_code", d.get("organization_code"), "missing_fields", fields));
            checkMapping(formalTeacher, d.get("teacher_code"), d.get("teacher_id"));
            checkMapping(formalCourse, d.get("course_code"), d.get("course_id"));

        }
        boolean payment = q.dateBasis == DateBasis.PAYMENT;
        List<Map<String,Object>> ranking = new ArrayList<>();
        for (String key : teacherTotals.keySet()) {
            Map<String,Object> r = teachers.get(key); r.putAll(teacherTotals.get(key).result(payment));
            SortedSet<String> codes = teacherCodes.get(key); r.put("teacher_codes", new ArrayList<>(codes)); r.put("teacher_code", codes.size()==1?codes.first():null);
            Object value = q.rankMetric == RankMetric.FEE ? r.get("amount") : obj(obj(r.get("hours")).get(q.hourBasis)).get("total");
            r.put("rank_value", value); r.put("rank", null); r.put("_key", key); ranking.add(r);
        }
        ranking.sort((a,b) -> {
            Object av = a.get("rank_value"), bv = b.get("rank_value");
            int c = av == null ? bv == null ? 0 : 1 : bv == null ? -1 : new BigDecimal(bv.toString()).compareTo(new BigDecimal(av.toString()));
            return c == 0 ? a.get("_key").toString().compareTo(b.get("_key").toString()) : c;
        });
        BigDecimal previous = null; int rank = 0;
        for (int i=0;i<ranking.size();i++) {
            Map<String,Object> r = ranking.get(i); Object v = r.get("rank_value");
            if (v != null) { BigDecimal n = new BigDecimal(v.toString()); if (previous == null || n.compareTo(previous) != 0) rank = i+1; r.put("rank", rank); previous=n; }
            r.remove("_key");
        }
        List<Map<String,Object>> organizations = new ArrayList<>(), activityTotals = new ArrayList<>();
        orgTotals.forEach((org, a) -> { Map<String,Object> r=map("organization_code", org); r.putAll(a.result(payment)); organizations.add(r); });
        activities.forEach((act, a) -> { Map<String,Object> r=map("activity", act); r.putAll(a.result(payment)); activityTotals.add(r); });
        return new Report(map("schema_version", VERSION, "start", q.start.toString(), "end", q.end.toString(),
                "date_basis", q.dateBasis.name(), "rank_metric", q.rankMetric.name(), "hour_basis", q.hourBasis,
                "currency", "CNY", "hours_applicable", !payment, "totals", total.result(payment),
                "activity_totals", activityTotals, "teachers", ranking, "organizations", organizations,
                "details", details, "code_gaps", gaps, "source_versions", versions,
                "export_available", canExport && gaps.isEmpty(), "can_export", canExport,
                "rounding_rule", "M05_FROZEN_CNY_2_HALF_UP_THEN_SUM", "tie_rule", "COMPETITION"));
    }

    /** Audited projection identity is separate from immutable money/hour facts. */
    private static void validateCoding(List<Map<String,Object>> versions, Map<String,Chain> chains,
                                       Map<String,Long> courseScopes, Map<String,String> teacherIds) {
        Set<String> seen = new HashSet<>();
        for (Map<String,Object> version : versions) {
            String chainCode=code(version.get("chain_code"),false);
            Chain chain=chains.get(chainCode);
            if(chain==null || !seen.add(chainCode))throw invalid("编码审计的账链不存在或重复");
            long scope=id(version.get("catalog_scope_id"),false), teacher=id(version.get("teacher_id"),false);
            String teacherCode=code(version.get("teacher_code"),false), courseCode=code(version.get("course_code"),false);
            for(Entry entry:chain.entries.values()) {
                if(!Objects.equals(entry.teacherId,teacher) || !teacherCode.equals(entry.teacherCode) || !courseCode.equals(entry.courseCode))
                    throw invalid("编码审计与账链完整投影不一致");
            }
            checkMapping(teacherIds,teacherCode,teacher);
            Long previous=courseScopes.putIfAbsent(courseCode,scope);
            if(previous!=null && previous.longValue()!=scope)throw invalid("相同正式课程编码对应不同目录，请先核实编码");
        }
    }

    private static Entry entry(Map<String,Object> r, String org, long project) {
        String kind=code(r.get("kind"), false), activity=code(r.get("activity"), false);
        if (!Set.of("CONFIRMED","ADJUSTMENT","REVERSAL","REBOOK","LEGACY_OPENING").contains(kind)
                || !Set.of("TEACHING","SOLO_DEVELOPMENT","JOINT_DEVELOPMENT","LEGACY").contains(activity)
                || !"CNY".equals(r.get("currency")) || !"CONFIGURED".equals(r.get("data_mode"))) throw invalid("非正式财务分录");
        code(r.get("source_code"), false); code(r.get("policy_version"), false); code(r.get("rate_version"), false); code(r.get("evidence_code"), false);
        instant(r.get("confirmed_at"));
        Map<String,Object> h=obj(r.get("hours")); if (!h.keySet().equals(new HashSet<>(HOURS))) throw invalid("四类课时字段不完整");
        Map<String,BigDecimal> hours=new LinkedHashMap<>(); HOURS.forEach(k -> hours.put(k, quantity(h.get(k))));
        return new Entry(code(r.get("entry_code"), false), code(r.get("chain_code"), false), code(r.get("previous_entry_code"), true),
                kind, activity, date(r.get("service_date")), nullableId(r.get("teacher_id")), code(r.get("teacher_code"), true),
                nullableId(r.get("course_id")), code(r.get("course_code"), true), money(r.get("amount")), hours, hourState(r.get("hours_before")), hourState(r.get("hours_after")),
                code(r.get("correction_group_code"), true), org, project);
    }
    private static Map<String,BigDecimal> hourState(Object value) {
        Map<String,Object> raw=obj(value);if(!raw.keySet().equals(new HashSet<>(HOURS)))throw invalid("完整四类课时状态缺失");
        Map<String,BigDecimal> result=new LinkedHashMap<>();HOURS.forEach(k -> result.put(k,quantity(raw.get(k))));return result;
    }
    private static Payment payment(Map<String,Object> r, String org, long project) {
        String kind=code(r.get("kind"), false); BigDecimal amount=money(r.get("amount"));
        if (!"CNY".equals(r.get("currency")) || !("PAYMENT".equals(kind) && amount.signum()>0 || "REFUND".equals(kind) && amount.signum()<0)) throw invalid("支付或退款金额符号无效");
        code(r.get("evidence_code"),false); instant(r.get("recorded_at"));
        if (!(r.get("historical") instanceof Boolean)) throw invalid("历史支付标识缺失");
        List<String> refs=new ArrayList<>(); Object raw=r.get("accrual_entry_codes");
        if (!(raw instanceof List<?> l) || l.isEmpty() || l.size()>50000) throw invalid("支付引用缺失");
        for(Object v:l) {String ref=code(v,false);if(refs.contains(ref))throw invalid("支付引用重复");refs.add(ref);}
        return new Payment(code(r.get("entry_code"),false),code(r.get("chain_code"),false),kind,date(r.get("payment_date")),amount,List.copyOf(refs),org,project);
    }
    private static void validate(Chain c) {
        if(c.head==null)throw invalid("缺少账链控制总额");
        Map<String,Entry> next=new HashMap<>();Entry root=null;
        for(Entry e:c.entries.values()) {
            if(e.previous==null) {if(root!=null || !Set.of("CONFIRMED","LEGACY_OPENING").contains(e.kind))throw invalid("账链必须只有一条起始分录");root=e;}
            else if(!c.entries.containsKey(e.previous) || next.putIfAbsent(e.previous,e)!=null || Set.of("CONFIRMED","LEGACY_OPENING").contains(e.kind))throw invalid("账链断裂或分叉");
        }
        if(root==null)throw invalid("账链起始分录缺失");
        Set<String> visited=new HashSet<>(), groups=new HashSet<>();BigDecimal amount=BigDecimal.ZERO;Entry current=root, last=null;
        Map<String,BigDecimal> hours=new LinkedHashMap<>(); HOURS.forEach(k -> hours.put(k, BigDecimal.ZERO));
        while(current!=null) {
            Entry e=current; if(!visited.add(e.code))throw invalid("账链成环");
            c.ordered.add(e);
            for(String k:HOURS){
                if(!equal(e.before.get(k),hours.get(k)))throw invalid("调整前课时与前笔完整状态不符");
                BigDecimal before=e.before.get(k),after=e.after.get(k);
                BigDecimal delta=before==null||after==null?null:after.subtract(before);
                if(after!=null&&after.signum()<0 || !equal(e.hours.get(k),delta))throw invalid("调整前后课时与差额不一致");
            }
            if(!root.activity.equals(e.activity) || !Objects.equals(root.teacherId,e.teacherId))throw invalid("账链讲师或业务类别改变");
            if(!Set.of("REVERSAL","REBOOK").contains(e.kind) && e.group!=null)throw invalid("非跨日分录不应携带冲回分组");
            if((e.kind.equals("CONFIRMED") || e.kind.equals("LEGACY_OPENING") || e.kind.equals("REBOOK")) && (e.amount.signum()<0 || e.hours.values().stream().anyMatch(v -> v!=null && v.signum()<0)))throw invalid("起始或重记数值不能为负");
            if(e.kind.equals("REVERSAL")) {
                Entry n=next.get(e.code);
                if(e.group==null || !groups.add(e.group) || n==null || !n.kind.equals("REBOOK") || !e.group.equals(n.group) || e.amount.compareTo(amount.negate())!=0 || last==null || !e.date.equals(last.date))throw invalid("跨日期更正须完整冲回并重记");
                for(String k:HOURS)if(!equal(e.after.get(k),BigDecimal.ZERO))throw invalid("原日期完整冲回后课时必须明确归零");
            } else if(e.kind.equals("REBOOK")) {
                if(last==null || !last.kind.equals("REVERSAL") || !Objects.equals(last.group,e.group) || last.date.equals(e.date))throw invalid("重记缺少配对冲回");

            } else if(last!=null && !e.date.equals(last.date)) throw invalid("差额不能直接改归属日期");
            amount=amount.add(e.amount); if(amount.precision()>64)throw invalid("金额合计超限");
            for(String k:HOURS)hours.put(k,e.after.get(k));
            if(!e.kind.equals("REVERSAL") && (amount.signum()<0 || hours.values().stream().anyMatch(v -> v!=null && v.signum()<0)))throw invalid("账链累计不能为负");
            last=e;current=next.get(e.code);
        }
        if(visited.size()!=c.entries.size())throw invalid("账链不完整");
        Map<String,Object> h=c.head;
        if(!last.code.equals(h.get("current_entry_code")) || !last.date.equals(date(h.get("current_service_date")))
                || !last.activity.equals(h.get("activity")) || !Objects.equals(last.teacherId,nullableId(h.get("teacher_id")))
                || amount.compareTo(money(h.get("current_amount")))!=0)throw invalid("应计控制总额或当前分录不一致");
        BigDecimal paid=BigDecimal.ZERO;
        for(Payment p:c.payments) {identity(c,p);paid=paid.add(p.amount);}
        if(paid.compareTo(money(h.get("paid_amount")))!=0 || amount.subtract(paid).compareTo(money(h.get("balance")))!=0)throw invalid("支付或余额控制总额不一致");
    }
    private static boolean sameIdentity(Entry a,Entry b) {
        return a.activity.equals(b.activity) && Objects.equals(a.teacherId,b.teacherId) && Objects.equals(a.courseId,b.courseId)
                && Objects.equals(a.teacherCode,b.teacherCode) && Objects.equals(a.courseCode,b.courseCode);
    }
    private static Entry identity(Chain c,Payment p) {
        Entry identity=null;
        for(String ref:p.refs) {Entry e=c.entries.get(ref);if(e==null || !e.chain.equals(p.chain) || identity!=null && !sameIdentity(identity,e))throw invalid("支付引用不属于同一冻结业务身份");identity=e;}
        return identity;
    }
    private static Map<String,Object> detail(Entry e,Payment p,boolean counted) {
        Map<String,Object> hours=new LinkedHashMap<>(), before=new LinkedHashMap<>(), after=new LinkedHashMap<>();
        HOURS.forEach(k -> {hours.put(k,p==null?plain(e.hours.get(k)):null);before.put(k,p==null?plain(e.before.get(k)):null);after.put(k,p==null?plain(e.after.get(k)):null);});
        return map("entry_code",p==null?e.code:p.code,"chain_code",e.chain,"previous_entry_code",p==null?e.previous:null,
                "kind",p==null?e.kind:p.kind,"activity",e.activity,"date",(p==null?e.date:p.date).toString(),
                "organization_code",e.org,"project_id",e.project,"teacher_id",e.teacherId,"teacher_code",e.teacherCode,
                "course_id",e.courseId,"course_code",e.courseCode,"amount",(p==null?e.amount:p.amount).toPlainString(),
                "hours",hours,"hours_before",before,"hours_after",after,"hours_counted",counted,"accrual_entry_codes",p==null?List.of(e.code):p.refs);
    }
    private static final class Acc {
        BigDecimal amount=BigDecimal.ZERO.setScale(2), positive=amount, negative=amount;
        int count; final Map<String,BigDecimal> hours=new LinkedHashMap<>(); final Map<String,Integer> missing=new LinkedHashMap<>();
        Acc(){HOURS.forEach(k->{hours.put(k,BigDecimal.ZERO);missing.put(k,0);});}
        void add(Map<String,Object> d){count++;BigDecimal n=money(d.get("amount"));amount=amount.add(n);if(n.signum()>=0)positive=positive.add(n);else negative=negative.add(n);
            if(!Boolean.TRUE.equals(d.get("hours_counted")))return;
            for(String k:HOURS){BigDecimal v=quantity(obj(d.get("hours_after")).get(k));if(v==null)missing.put(k,missing.get(k)+1);else hours.put(k,hours.get(k).add(v));}}
        Map<String,Object> result(boolean payment){Map<String,Object> h=new LinkedHashMap<>();for(String k:HOURS)h.put(k,map("total",payment||missing.get(k)>0?null:plain(hours.get(k)),"known_subtotal",payment?null:plain(hours.get(k)),"missing_count",payment?null:missing.get(k),"applicable",!payment));
            return map("amount",amount.toPlainString(),"positive_amount",positive.toPlainString(),"negative_amount",negative.toPlainString(),"entry_count",count,"hours",h);}
    }
    private static String teacherKey(Map<String,Object>d){return d.get("teacher_id")!=null?"I:"+d.get("teacher_id"):d.get("teacher_code")!=null?"C:"+d.get("teacher_code"):"U:"+d.get("project_id")+":"+d.get("chain_code");}
    private static void checkMapping(Map<String,String> seen,Object code,Object id){if(code!=null&&id!=null){String old=seen.putIfAbsent(code.toString(),id.toString());if(old!=null&&!old.equals(id.toString()))throw invalid("正式编码映射冲突");}}
    private static boolean equal(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null&&a.compareTo(b)==0;}
    static BigDecimal money(Object v){if(!(v instanceof String s)||!s.matches("-?(0|[1-9][0-9]{0,59})\\.[0-9]{2}"))throw invalid("必须使用冻结的人民币两位金额");return new BigDecimal(s);}
    static BigDecimal quantity(Object v){if(v==null)return null;if(!(v instanceof String s)||!s.matches("-?(0|[1-9][0-9]{0,39})(\\.[0-9]{1,8})?"))throw invalid("课时数值无效");return new BigDecimal(s);}
    private static String plain(BigDecimal v){return v==null?null:v.toPlainString();}
    static LocalDate date(Object v){try{if(!(v instanceof String s)||!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();return LocalDate.parse(s);}catch(Exception e){throw invalid("日期无效");}}
    private static void instant(Object v){try{java.time.Instant.parse((String)v);}catch(Exception e){throw invalid("财务时间依据无效");}}
    static String code(Object v,boolean nullable){if(v==null&&nullable)return null;if(!(v instanceof String s)||!s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}"))throw invalid("财务编码无效");return s;}
    private static Long nullableId(Object v){return v==null?null:id(v,false);}
    private static long id(Object v,boolean ignored){try{if(!(v instanceof Number)&&!(v instanceof String))throw new IllegalArgumentException();long n=new BigDecimal(v.toString()).longValueExact();if(n<=0||n>9007199254740991L)throw new IllegalArgumentException();return n;}catch(Exception e){throw invalid("来源系统标识无效");}}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object v){if(!(v instanceof Map<?,?>))throw invalid("财务对象无效");return (Map<String,Object>)v;}
    static List<Map<String,Object>> rows(Object v){if(!(v instanceof List<?> l)||l.size()>50000)throw invalid("财务列表无效");List<Map<String,Object>> out=new ArrayList<>();for(Object x:l)out.add(obj(x));return out;}
    static Map<String,Object> map(Object... pairs){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
    @SuppressWarnings("unchecked") private static Object copy(Object v,boolean freeze){if(v instanceof Map<?,?> m){Map<String,Object> r=new LinkedHashMap<>();m.forEach((k,x)->r.put((String)k,copy(x,freeze)));return freeze?Collections.unmodifiableMap(r):r;}if(v instanceof List<?> l){List<Object> r=new ArrayList<>();l.forEach(x->r.add(copy(x,freeze)));return freeze?Collections.unmodifiableList(r):r;}return v;}
    @SuppressWarnings("unchecked") private static Map<String,Object> copyMap(Map<String,Object>v){return (Map<String,Object>)copy(v,false);}
    @SuppressWarnings("unchecked") private static Map<String,Object> freezeMap(Map<String,Object>v){return (Map<String,Object>)copy(v,true);}
}
