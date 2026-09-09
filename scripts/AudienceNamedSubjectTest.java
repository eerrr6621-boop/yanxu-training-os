package com.training;
import java.util.*;

/** Additive fixed regression for the observed named-course subject omission. */
public final class AudienceNamedSubjectTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static String event(String title,String audience) {
        return "课程名称："+title+"\n授课人：本人\n授课角色：独立主讲\n完成状态：已完成"+(audience==null?"":"\n培训对象："+audience);
    }
    static Map<String,Object> assess(String query,String text) {
        Map<String,Object> c=new LinkedHashMap<>();c.put("professional_score",0.0);c.put("_rule_relevant",true);c.put("gaps",new ArrayList<String>());
        new RequirementCoverage(query).assess(c,text,false);return c;
    }
    static boolean admitted(String query,String text){return Boolean.TRUE.equals(assess(query,text).get("_admitted"));}
    public static void main(String[] args) {
        String title="样品封口编号",audience="样品接收员",base="培训主题：《"+title+"》";
        String named=base+"\n《"+title+"》必须适配"+audience;
        for(String receiver:Arrays.asList(audience,null,"设备维护员")) {
            String source=event(title,receiver);
            check(admitted(named,source)==Objects.equals(receiver,audience),"fixed named subject supported/missing/different audience");
            check(!admitted(named+"且满足尚未定义资格",source),"fixed named unknown qualification suffix");
            check(admitted(base+"\n课程必须适配"+audience,source)==Objects.equals(receiver,audience),"fixed ordinary subject control");
            check(admitted("培训主题：《课程必须适配编号》",event("课程必须适配编号",receiver)),"fixed title-internal modal is literal");
        }
        String all=event(title,audience)+"\n\n"+event("试管标签颜色",audience);
        check(!admitted("培训主题：《"+title+"》和《试管标签颜色》\n《"+title+"》必须适配"+audience,all),"multi-course target is pending, not globally reassigned");
        check(!admitted(base+"\n《试管标签颜色》必须适配"+audience,all),"mismatched named subject cannot redefine requested course");
        check(!admitted(base+"\n《"+title+"必须适配"+audience,event(title,audience)),"unclosed named subject remains pending");
        check(!admitted(named+"且持有指定认证",event(title,audience)),"unknown suffix without a negative token stays pending");
        check(!admitted(base+"\n《"+title+"》必须满足指定标准",event(title,audience)),"named unknown mandatory predicate remains pending");
        check(!admitted(named+"（",event(title,audience)),"unclosed target qualifier is pending");
        check(admitted("《课程必须适配编号》",event("课程必须适配编号",null)),"standalone complete literal title has no external directive");
        System.out.println("Audience named subject: "+checks+" assertions passed");
    }
}
