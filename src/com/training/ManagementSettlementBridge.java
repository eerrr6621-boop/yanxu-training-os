package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.training.ManagementSettlementAccounting.*;

/** Authenticated read-only M05 financial bridge. Browser input is filters plus a CAS token only. */
public final class ManagementSettlementBridge {
    private ManagementSettlementBridge() {}
    private static final String BASE="/api/management-settlement-reports";
    private static final Set<String> KEYS=Set.of("start","end","date_basis","organizations","rank_metric","hour_basis");
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private record Selection(Query query,SortedSet<String> organizations,Object expected) {}
    private record Snapshot(Report report,Map<String,Object> view) {}

    public static boolean handle(HttpExchange ex,Auth.Session supplied)throws Exception {
        String path=ex.getRequestURI().getPath();if(!path.equals(BASE)&&!path.equals(BASE+"/export"))return false;
        Auth.Session current=Auth.get(Api.token(ex));
        if(current==null||current!=supplied||Auth.current(current)==null)throw failure(401,"登录会话无效或已失效");
        if(!"GET".equals(ex.getRequestMethod()))throw failure(405,"仅支持只读查询");
        Map<String,String> query=strictQuery(ex);
        if(path.equals(BASE))Api.ok(ex,read(current,query));
        else {
            byte[] bytes=download(current,query);Query q=selection(query,true).query;
            Api.file(ex,bytes,ManagementSettlementWorkbook.CONTENT_TYPE,"settlement-"+q.dateBasis().name().toLowerCase(Locale.ROOT)+"-"+q.start().toString().replace("-","")+"-"+q.end().toString().replace("-","")+".xlsx");
        }
        return true;
    }
    public static Map<String,Object> read(Auth.Session session,Map<String,?> query)throws Exception{return snapshot(session,query,false).view;}
    public static byte[] download(Auth.Session session,Map<String,?> query)throws Exception {
        synchronized(Api.MUTATION_LOCK){
            Snapshot before=snapshot(session,query,true);
            if(!before.report.exportable())throw failure(409,"正式编码尚不完整，请先补齐缺失的讲师或课程编码");
            byte[] bytes;
            try{bytes=ManagementSettlementWorkbook.export(before.report,(String)before.view.get("snapshot_version"));}
            catch(IllegalArgumentException tooLarge){throw failure(409,"导出内容超过工作簿容量，请缩小日期或机构范围");}
            if(bytes.length>16*1024*1024)throw failure(409,"导出文件超过16MB，请缩小日期或机构范围");
            Snapshot after=snapshot(session,query,true);
            if(!Objects.equals(before.view.get("snapshot_version"),after.view.get("snapshot_version")))throw failure(409,"统计来源或权限已变化，请刷新后重试");
            return bytes;
        }
    }
    private static Snapshot snapshot(Auth.Session session,Map<String,?> raw,boolean exporting)throws Exception {
        synchronized(Api.MUTATION_LOCK){
            if(!Db.get().getAutoCommit())throw failure(409,"统计查询必须在独立的只读事务中执行");
            return Db.transaction(()->{
                OrganizationAccessStore.person(session);Selection selection=selection(raw,exporting);
                OrganizationAccess.Configuration config=OrganizationAccessStore.configuration();
                SortedSet<String> allowed=new TreeSet<>();
                for(OrganizationAccess.Organization org:config.organizations())if(OrganizationAccessStore.authorize(session,"reports.read",OrganizationAccess.Action.VIEW,org.organizationCode()).allowed())allowed.add(org.organizationCode());
                if(allowed.isEmpty())throw failure(403,"没有可查看统计的机构范围");
                SortedSet<String> selected=selection.organizations.isEmpty()?allowed:selection.organizations;
                if(!allowed.containsAll(selected))throw failure(403,"所选机构不在统计查看权限范围内");
                authorize(session,selected,exporting);
                if(exporting&&(!(selection.expected instanceof String value)||!value.matches("[0-9a-f]{64}")))throw failure(400,"导出需要当前统计快照版本");
                boolean canExport=true;
                for(String org:selected)canExport&=OrganizationAccessStore.authorize(session,"reports.export",OrganizationAccess.Action.EXPORT,org).allowed();
                List<Map<String,Object>> sources=new ArrayList<>();Set<Long> seen=new HashSet<>();
                for(String org:selected)for(Long id:DeliverySettlementIntegration.financialProjectIds(session,org)) {
                    if(sources.size()>=10000)throw failure(409,"项目来源超过查询上限，请缩小机构范围");
                    if(id==null||id<=0||!seen.add(id))throw failure(409,"项目存在重复或无效的机构来源");
                    Map<String,Object> source=DeliverySettlementIntegration.financialSource(session,id,exporting);
                    if(!org.equals(source.get("organization_code"))||!Objects.equals(Long.valueOf(id),numericId(source.get("project_id"))))throw failure(409,"财务来源与受理机构或项目不一致");
                    sources.add(source);
                }
                Report report;
                try {report=calculate(sources,selection.query,canExport);}
                catch(IllegalArgumentException e){throw failure(409,"财务来源需要核对："+e.getMessage());}
                Map<String,Object> view=report.toMap();
                enrichDisplay(view,allowed,config);
                view.put("selected_organizations",new ArrayList<>(selected));view.put("available_organizations",new ArrayList<>(allowed));
                view.put("permissions",map("read",true,"export",canExport));view.put("access_version",config.version());
                view.put("source_coverage","APPROVED_FROZEN_LEDGER_ONLY");
                view.put("coverage_note","仅统计可信项目中已核准冻结的财务账；未逐笔核对迁移的旧制记录不属于正式账，不能从历史费用推算。");
                if(sources.stream().anyMatch(source -> source.get("coding_versions") instanceof List<?> versions && !versions.isEmpty()))
                    view.put("coverage_note",view.get("coverage_note")+" 正式编码含另行明确确认的审计关联；金额、课时和支付仍取原冻结账，编码更正保留独立历史。");
                view.put("snapshot_version",digest(map("account_id",session.uid,"view",view)));
                authorize(session,selected,exporting);
                if(!config.version().equals(OrganizationAccessStore.configuration().version()))throw failure(409,"统计权限已变化，请重新查询");
                if(exporting&&!Objects.equals(selection.expected,view.get("snapshot_version")))throw failure(409,"统计来源或权限已变化，请刷新后重试");
                view.put("generated_at",Instant.now().toString());return new Snapshot(report,view);
            });
        }
    }
    /** Authorized UI-only labels. The immutable accounting Report and XLSX never receive these. */
    private static void enrichDisplay(Map<String,Object> view,SortedSet<String> allowed,OrganizationAccess.Configuration config)throws Exception {
        SortedSet<Long> ids=new TreeSet<>();
        for(Map<String,Object> r:rows(view.get("teachers"))){Long id=numericId(r.get("teacher_id"));if(id!=null)ids.add(id);}
        Map<Long,String> names=new HashMap<>();List<Long> ordered=new ArrayList<>(ids);
        for(int offset=0;offset<ordered.size();offset+=200){List<Long> part=ordered.subList(offset,Math.min(offset+200,ordered.size()));
            String placeholders=String.join(",",Collections.nCopies(part.size(),"?"));
            for(Map<String,Object> r:Db.query("SELECT id,name FROM teachers WHERE id IN ("+placeholders+")",part.toArray())){
                if(r.get("name") instanceof String name&&!name.isBlank())names.put(numericId(r.get("id")),name);
            }
        }
        for(String section:List.of("teachers","details"))for(Map<String,Object> r:rows(view.get(section))){Long id=numericId(r.get("teacher_id"));
            r.put("teacher_display_name",id==null?"讲师资料待完善":names.getOrDefault(id,"讲师记录#"+id+"（待完善）"));
        }
        List<Map<String,Object>> options=new ArrayList<>();
        Map<String,String> organizations=new HashMap<>();
        for(OrganizationAccess.Organization org:config.organizations())if(allowed.contains(org.organizationCode()))
            organizations.put(org.organizationCode(),org.displayName()==null?"机构资料待完善":org.displayName());
        for(String org:allowed)options.add(map("organization_code",org,"display_name",organizations.get(org)));
        for(String section:List.of("organizations","details"))for(Map<String,Object> row:rows(view.get(section)))
            row.put("organization_display_name",organizations.get(row.get("organization_code")));
        view.put("available_organization_options",options);
    }
    private static Long numericId(Object v){try{return v==null?null:new java.math.BigDecimal(v.toString()).longValueExact();}catch(Exception e){return null;}}
    private static void authorize(Auth.Session session,Set<String> selected,boolean exporting)throws Exception {
        OrganizationAccessStore.person(session);
        for(String org:selected)if(!OrganizationAccessStore.authorize(session,"reports.read",OrganizationAccess.Action.VIEW,org).allowed()
                ||exporting&&!OrganizationAccessStore.authorize(session,"reports.export",OrganizationAccess.Action.EXPORT,org).allowed())throw failure(403,"缺少所选机构的统计查看或导出权限");
    }
    private static Selection selection(Map<String,?> raw,boolean exporting)throws Api.ApiException {
        if(raw==null)throw failure(400,"需要查询条件");
        for(String key:raw.keySet())if(!KEYS.contains(key)&&!(exporting&&"snapshot_version".equals(key)))throw failure(400,"不接受查询字段："+key);
        try {
            LocalDate start,end;
            if(!raw.containsKey("start")&&!raw.containsKey("end")){start=LocalDate.now(ZONE).withDayOfMonth(1);end=start.withDayOfMonth(start.lengthOfMonth());}
            else{start=date(raw.get("start"));end=date(raw.get("end"));}
            DateBasis basis=DateBasis.valueOf(option(raw,"date_basis","TEACHING"));
            RankMetric rank=RankMetric.valueOf(option(raw,"rank_metric",basis==DateBasis.PAYMENT?"FEE":"HOURS"));
            String hour=option(raw,"hour_basis","ACTUAL");Query q=new Query(start,end,basis,rank,hour);
            SortedSet<String> orgs=new TreeSet<>();
            if(raw.containsKey("organizations")){
                if(!(raw.get("organizations") instanceof String s)||s.isBlank()||s.length()>10000)throw new IllegalArgumentException("机构筛选无效");
                for(String org:s.split(",",-1))if(!orgs.add(code(org,false)))throw new IllegalArgumentException("机构筛选重复");
            }
            return new Selection(q,orgs,raw.get("snapshot_version"));
        }catch(IllegalArgumentException e){throw failure(400,"统计条件无效："+e.getMessage());}
    }
    private static String option(Map<String,?>raw,String key,String fallback){if(!raw.containsKey(key))return fallback;if(!(raw.get(key) instanceof String s))throw new IllegalArgumentException(key+"须为文本");return s;}
    private static Map<String,String> strictQuery(HttpExchange ex)throws Api.ApiException {
        Map<String,String> out=new LinkedHashMap<>();String raw=ex.getRequestURI().getRawQuery();if(raw==null)return out;
        if(raw.length()>24000)throw failure(400,"查询参数过长");
        for(String part:raw.split("&",-1)){int at=part.indexOf('=');if(at<=0)throw failure(400,"查询参数格式无效");try{String key=URLDecoder.decode(part.substring(0,at),StandardCharsets.UTF_8),value=URLDecoder.decode(part.substring(at+1),StandardCharsets.UTF_8);if(out.putIfAbsent(key,value)!=null)throw failure(400,"查询参数不可重复");}catch(IllegalArgumentException e){throw failure(400,"查询参数编码无效");}}
        return out;
    }
    private static Object sorted(Object v){if(v instanceof Map<?,?>m){Map<String,Object>r=new TreeMap<>();m.forEach((k,x)->r.put(k.toString(),sorted(x)));return r;}if(v instanceof List<?>l)return l.stream().map(ManagementSettlementBridge::sorted).toList();return v;}
    private static String digest(Object v)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(sorted(v)).getBytes(StandardCharsets.UTF_8)));}
    private static Api.ApiException failure(int status,String message){return new Api.ApiException(status,message);}
}
