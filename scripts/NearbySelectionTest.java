package com.training;
import java.util.*;
import java.time.*;

/** Constructed fixtures; no real teachers, ticket data or production mutations. */
public final class NearbySelectionTest {
    static int checks;
    static void check(boolean yes,String message) { if(!yes) throw new AssertionError(message);checks++; }
    static Map<String,Object> candidate(long id,int score,boolean local,Integer minutes) {
        Map<String,Object> fit=new LinkedHashMap<>();
        fit.put("local_priority",local);
        if(minutes!=null) fit.put("route_reference",Map.of("status","reference_available","upper_minutes",minutes,"return_upper_minutes",minutes));
        return new LinkedHashMap<>(Map.of("teacher_id",id,"recommendation_score",score,"dispatch_fit",fit));
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> selected(Map<String,Object> result) { return (List<Map<String,Object>>)result.get("selected"); }
    static Map<String,Object> assess(String query,String text) {
        Map<String,Object> c=new LinkedHashMap<>();
        c.put("professional_score",70.0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage(query).assess(c,text,false); return c;
    }
    public static void main(String[] args) {
        DispatchPreference pref=DispatchPreference.from(Map.of("training_province","安徽","training_city","合肥","training_mode","线下","training_period","下午"),null);
        List<Map<String,Object>> rows=List.of(candidate(1,50,true,null),candidate(2,40,true,null),candidate(3,30,true,null),candidate(4,100,false,60));
        Map<String,Object> result=NearbySelection.select(rows,pref,3);
        check(selected(result).size()==3 && selected(result).stream().anyMatch(r->r.get("teacher_id").equals(4L)),"Same-city quota does not hide a qualified rail city");
        rows=List.of(candidate(1,90,true,null),candidate(2,60,true,null),candidate(3,95,false,120),candidate(4,85,false,120),candidate(5,100,false,180));
        result=NearbySelection.select(rows,pref,3);
        check(result.get("pool_size").equals(5),"All sub-four-hour cities admitted, not just first full band");
        check(selected(result).stream().map(r->r.get("teacher_id")).toList().equals(List.of(5L,3L,1L)),"Entire rail pool ranked by skill");
        check(selected(NearbySelection.select(List.of(candidate(1,80,false,239)),pref,3)).size()==1,"239 minutes qualifies");
        check(selected(NearbySelection.select(List.of(candidate(1,80,false,240)),pref,3)).isEmpty(),"Strict less-than-four-hours excludes 240 minutes");
        rows=List.of(candidate(1,90,true,null),candidate(2,80,true,null),candidate(3,80,true,null),candidate(4,80,true,null));
        result=NearbySelection.select(rows,pref,3);
        check(selected(result).size()==4 && result.get("ties_included").equals(1),"All third-place ties retained");
        List<Map<String,Object>> levels=new ArrayList<>();
        String[] labels={"","讲师","高级讲师","特级讲师","特聘讲师"};
        for(int i=0;i<labels.length;i++) {
            Map<String,Object> row=candidate(i+1,80,true,null);row.put("teacher_level",labels[i]);row.put("model_score",80);levels.add(row);
        }
        result=NearbySelection.select(levels,pref,3);
        check(selected(result).size()==5,"Level cannot discard any third-place model-score tie");
        check(selected(result).stream().map(r->r.get("teacher_id")).toList().equals(List.of(5L,4L,3L,2L,1L)),"All four human levels descend; missing stays last");
        levels.get(0).put("recommendation_score",81);
        levels.get(0).put("model_score",81);
        check(selected(NearbySelection.select(levels,pref,3)).get(0).get("teacher_id").equals(1L),"Higher model score beats any title level");
        check(TeacherLevel.rank(null)==0 && TeacherLevel.rank("副教授")==0,"No inference from titles or missing values");
        Map<String,Object> geofake=candidate(999,100,false,null);
        ((Map<String,Object>)geofake.get("dispatch_fit")).put("city_priority",Map.of("status","preset_available","tier",1,"priority_score",99));
        check(selected(NearbySelection.select(List.of(geofake),pref,3)).isEmpty(),"Geographic distances cannot enter rail-time catchment");
        check(NearbySelection.priorityScore(120)==85 && NearbySelection.priorityScore(121)==70 && NearbySelection.priorityScore(180)==70 && NearbySelection.priorityScore(181)==55,"Time-band score boundaries");
        rows=List.of(candidate(1,90,true,null),candidate(2,80,false,null));
        result=NearbySelection.select(rows,pref,3);
        check(selected(result).size()==1 && ((List<?>)result.get("travel_pending")).size()==1,"Unknown travel is never fake nearby");
        Map<String,Object> uncertain=candidate(1,90,false,50);
        ((Map<String,Object>)uncertain.get("dispatch_fit")).put("departure_uncertain",true);
        check(selected(NearbySelection.select(List.of(uncertain),pref,3)).isEmpty(),"Adjacent location uncertainty blocks assumed residence travel");
        Map<String,Object> longReturn=candidate(2,100,false,60);
        ((Map<String,Object>)longReturn.get("dispatch_fit")).put("route_reference",Map.of("status","reference_available","upper_minutes",60,"return_upper_minutes",500));
        check(selected(NearbySelection.select(List.of(longReturn),pref,3)).isEmpty(),"Long return cannot count as nearby or pad three");
        check(selected(NearbySelection.select(List.of(candidate(1,100,false,2880)),pref,3)).isEmpty(),"No unlimited catchment to fill quota");
        DispatchPreference online=DispatchPreference.from(Map.of("training_mode","线上"),null);
        check(selected(NearbySelection.select(rows,online,3)).size()==2,"Online has no location penalty");
        for(String text:List.of("担任亲子财商课程助教","参与亲子财商课程筹备","未主讲过亲子财商课程","从未主讲亲子财商课程","亲子财商讲师（筹）"))
            check(!Boolean.TRUE.equals(assess("培训主题：亲子财商",text).get("_admitted")),"Role or negation not promoted: "+text);
        for(String separator:List.of("和","与","、","；",";","\n"))
            check(!Boolean.TRUE.equals(assess("培训主题：亲子财商"+separator+"篮球教学","主讲亲子财商课程").get("_admitted")),"Unknown mandatory topic not silently satisfied: "+separator);
        check(!Boolean.TRUE.equals(assess("培训主题：亲子财商","参加亲子财商课程培训并取得结业证书").get("_admitted")),"Attendance certificate is not a teaching capability claim");
        check(!Boolean.TRUE.equals(assess("培训主题：亲子财商和金融反诈","未授课课程\n亲子财商\n金融反诈").get("_admitted")),"Negative heading applies to child entries");
        check(!ProfessionalEvidence.isTeachingRecord("拟开设少儿财商课程","授课风采\n服务礼仪授课\n未来规划\n拟开设少儿财商课程"),"Future plan cannot inherit past teaching gallery");
        check(!Boolean.TRUE.equals(assess("培训内容：亲子财商\n师资要求：必须会金融反诈","主讲亲子财商课程").get("_admitted")),"Hard teacher requirements preserved");
        check(ProfessionalEvidence.positive("在无锡开展网点无障碍服务培训，拥有教学经验").contains("无锡"),"No spurious city negation");
        for(double similarity:List.of(.61,.63)) {
            Map<String,Object> c=new LinkedHashMap<>();
            c.put("professional_score",20.0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
            c.put("semantic",Map.of("status","ready","similarity",similarity,"evidence",List.of("主讲银行客户服务")));
            new RequirementCoverage("补充要求：银行客户服务").assess(c,"主讲银行客户服务",true);
            check(Boolean.TRUE.equals(c.get("_admitted")),"Exact supported claim cannot disappear at model threshold "+similarity);
            check(c.get("model_score").equals(Math.round(100*similarity)),"Model is sole displayed content score");
        }
        Map<String,Object> futureAudience=assess("补充要求：银行客户服务\n授课对象：新任管理员","主讲银行客户服务，授课对象：资深主管");
        check(Boolean.TRUE.equals(futureAudience.get("_admitted")),"Supplement topic plus future audience does not impose unstated historical audience");
        check(futureAudience.get("gaps").toString().contains("对象适配待确认"),"Future audience retained as confirmation rather than certified adaptation");
        check(!Boolean.TRUE.equals(assess("补充要求：银行客户服务\n必须有实际授课记录","主讲课程：银行客户服务").get("_admitted")),"Supplement wrapper cannot bypass personal teaching history");
        check(!ProfessionalEvidence.units("本人参与主讲财商课程").isEmpty(),"Own co-teaching is not attendance");
        check(ProfessionalEvidence.units("参加李老师主讲的财商课程培训").isEmpty(),"Other teacher's role does not become own teaching");
        check(!Boolean.TRUE.equals(assess("必须有实际授课经历，培训主题：亲子财商","主讲课程：亲子财商").get("_admitted")),"Course catalog alone is not past teaching");
        String date=LocalDate.now().plusDays(5).toString();
        Map<String,Object> route=new LinkedHashMap<>(Map.of("from_province","江苏","from_city","南京","to_province","安徽","to_city","合肥","kind","reviewed_reference","source","https://example.test/gray-fixture","checked_on",LocalDate.now().toString(),"valid_from",date,"valid_until",date));
        route.put("outbound_door_to_door_upper_minutes",150);route.put("return_door_to_door_upper_minutes",180);route.put("includes_transfers_and_local_connections",true);
        route.put("transport_mode","high_speed_rail");route.put("rail_priority_checked",true);
        route.put("outbound_rail_minutes",90);route.put("return_rail_minutes",120);
        route.put("outbound_connection_minutes",60);route.put("return_connection_minutes",60);
        route.put("from_station","虚构测试甲站");route.put("to_station","虚构测试乙站");
        Map<String,Object> teacher=Map.of("base_province","江苏","base_city","南京");
        check(NearbySelection.lookup(List.of(route),teacher,pref,date).get("status").equals("reference_available"),"Applicable paired reference");
        Map<String,Object> nonRail=new LinkedHashMap<>(route);nonRail.put("transport_mode","straight_line_estimate");
        check(!NearbySelection.lookup(List.of(nonRail),teacher,pref,date).get("status").equals("reference_available"),"Straight-line-derived minutes rejected");
        Map<String,Object> missingConnection=new LinkedHashMap<>(route);missingConnection.remove("outbound_connection_minutes");
        check(!NearbySelection.lookup(List.of(missingConnection),teacher,pref,date).get("status").equals("reference_available"),"No fabricated connection times");
        Map<String,Object> fastOutLongBack=new LinkedHashMap<>(route);fastOutLongBack.put("outbound_door_to_door_upper_minutes",151);fastOutLongBack.put("return_door_to_door_upper_minutes",500);
        check(NearbySelection.lookup(List.of(fastOutLongBack,route),teacher,pref,date).get("return_upper_minutes").equals(180),"Compare both directions, not only fastest outward train");
        DispatchPreference morning=DispatchPreference.from(Map.of("training_province","安徽","training_city","合肥","training_mode","线下","training_period","上午"),null);
        check(!NearbySelection.lookup(List.of(route),teacher,morning,date).get("status").equals("reference_available"),"Prior-day travel must be in validity window");
        route.put("kind","historical_reference");
        check(!NearbySelection.lookup(List.of(route),teacher,pref,date).get("status").equals("reference_available"),"History not silently promoted");
        route.put("kind","reviewed_reference");route.put("outbound_door_to_door_upper_minutes",0);
        check(!NearbySelection.lookup(List.of(route),teacher,pref,date).get("status").equals("reference_available"),"Unknown duration is not zero");
        System.out.println("Nearby catchment / same-score / evidence tests: "+checks+" passed");
    }
}
