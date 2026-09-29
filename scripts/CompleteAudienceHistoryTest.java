package com.training;
import java.util.*;

/** Regression discovered in exposed development replay; no model or accuracy claim. */
public final class CompleteAudienceHistoryTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static String event(String course,String audience,String actor,String completion){return "课程名称："+course+"\n授课人："+actor+"\n授课角色：独立主讲\n完成状态："+completion+"\n培训对象："+audience;}
    static boolean admitted(String query,String text){Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());new RequirementCoverage(query).assess(c,text,false);return Boolean.TRUE.equals(c.get("_admitted"));}
    public static void main(String[] args) {
        for(String[] pair:List.of(new String[]{"社区物品借还卡填写","老年志愿者"},new String[]{"展柜编号复核","展厅志愿者"})) {
            String course=pair[0],audience=pair[1],base="培训主题："+course+"\n参训对象："+audience;
            String source=event(course,audience,"本人","已完成");
            for(String head:List.of("需有本人面向","必须有自己给","要求具备本人为")) {
                String query=base+"\n补充要求："+head+audience+"完整授课的经历。";
                check(admitted(query,source),"supported closed history clause: "+head);
                check(!admitted(query,event(course,"设备管理员","本人","已完成")),"explicit historical audience cannot be replaced");
                check(!admitted(query,event(course,audience,"同事","已完成")),"other teacher remains insufficient");
                check(!admitted(query,event(course,audience,"本人","尚未开课")),"future course is not completed history");
                check(!admitted(query,event("其他独立课程",audience,"本人","已完成")+"\n\n"+event(course,"设备管理员","本人","已完成")),"no cross-course audience borrowing");
                check(!admitted(query+"并且持有尚未定义的资格。",source),"unparsed extra qualification is not dropped");
            }
            check(!admitted("培训主题：《"+course+"》和《另一课程》\n需有本人面向"+audience+"完整授课的经历",source+"\n\n"+event("另一课程",audience,"本人","已完成")),"ambiguous multiple-course history stays pending");
        }
        System.out.println("Complete audience history: "+checks+" assertions passed");
    }
}
