package com.training;
import java.util.*;

/** Source grammar regression: a conjunction does not erase a closed past-teaching clause. */
public final class CoordinatedAudienceHistoryTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static String event(String course,String audience,String actor,String completion){return "课程名称："+course+"\n授课人："+actor+"\n授课角色：主讲\n完成状态："+completion+"\n培训对象："+audience;}
    static boolean admitted(String q,String text){Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());new RequirementCoverage(q).assess(c,text,false);return Boolean.TRUE.equals(c.get("_admitted"));}
    public static void main(String[] args) {
        for(String[] pair:List.of(new String[]{"窑炉巡检","车间班组长"},new String[]{"展签归档","展厅志愿者"})) {
            String course=pair[0],audience=pair[1],source=event(course,audience,"本人","已完成");
            String base="培训主题："+course+"\n培训对象："+audience+"\n必须有实际授课记录，";
            for(String conjunction:List.of("并且","并","且","同时"))for(String modal:List.of("必须","须","需要")) {
                String condition=conjunction+modal+"以前给这类学员讲完这门课",query=base+condition;
                check(admitted(query,source),"supported compound history: "+condition);
                check(!admitted(query,event(course,"后勤管理员","本人","已完成")),"historical audience remains hard");
                check(!admitted(query,event(course,audience,"同事","已完成")),"no other-actor borrowing");
                check(!admitted(query,event(course,audience,"本人","尚未开课")),"future plan is not history");
                check(!admitted(query,event(course,"后勤管理员","本人","已完成")+"\n\n"+event("其他课程",audience,"本人","已完成")),"no cross-course audience borrowing");
                RequirementTopic.Result parsed=RequirementTopic.parse(query);
                check(parsed.courses().get(0).historyConditions().stream().anyMatch(h->h.span().text().equals(condition)&&h.completed()&&h.personal()&&h.audienceBindings().size()==1),"full conjunction clause retained with audience binding");
                check(!admitted(query+"且持有尚未定义的资格",source),"unknown suffix not trimmed");
            }
            for(String modifier:List.of("如果","除非","或者","不必","不得")) {
                RequirementTopic.Result parsed=RequirementTopic.parse(base+modifier+"以前给这类学员讲完这门课");
                check(parsed.unparsedClauses().stream().anyMatch(u->u.span().text().startsWith(modifier)),"conditional/disjunctive/negative clause not converted to positive history");
            }
            check(!admitted("培训主题：《"+course+"》和《另一课程》\n培训对象："+audience+"\n并且必须以前给这类学员讲完这门课",source+"\n\n"+event("另一课程",audience,"本人","已完成")),"ambiguous singular course reference stays pending");
            check(!admitted("培训主题："+course+"\n培训对象："+audience+"\n参训对象：后勤管理员\n并且必须以前给这类学员讲完这门课",source),"ambiguous audience reference stays pending");
            check(admitted(base+"并且必须以前把这门课讲完",source),"post-object variant retains complete past-teaching semantics");
        }
        System.out.println("Coordinated audience history: "+checks+" assertions passed");
    }
}
