package com.training;

import java.util.*;

/** Real coverage/constraint chain, fixed exposed synthetic pairs; no model. */
public final class RequirementTopicIntegrationTest {
    private static int checks,cases;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static Map<String,Object> assess(String query,String source) {
        Map<String,Object> candidate=new LinkedHashMap<>();candidate.put("professional_score",50.0);candidate.put("_rule_relevant",true);
        candidate.put("gaps",new ArrayList<String>());candidate.put("semantic",Map.of("similarity",.999,"evidence_complete",true));
        new RequirementCoverage(query).assess(candidate,source,true);return candidate;
    }
    private static Map<String,Object> expect(boolean admitted,String query,String source,String reason) {
        cases++;Map<String,Object> result=assess(query,source);
        check(Boolean.TRUE.equals(result.get("_admitted"))==admitted,reason+" "+Json.write(result));return result;
    }
    private static String event(String course,String actor,String role,String completion,String audience) {
        return "课程名称："+course+"\n授课人："+actor+"\n授课角色："+role+
                (completion.isEmpty()?"":"\n完成状态："+completion)+(audience.isEmpty()?"":"\n培训对象："+audience);
    }
    public static void main(String[] args) {
        for(String topic:List.of("陶瓷釉料调配","无人机螺旋桨养护","冷藏药品入库复核")) {
            String history="培训主题："+topic+"；必须有这门课的经历";
            String own=event(topic,"本人","独立主讲","已完成","设备管理员");
            Map<String,Object> result=expect(true,history,own,"single bound history course supports actual complete event");
            check(new RequirementCoverage(history).required.keySet().stream().noneMatch(k->k.contains("这门课")),"no spurious pronoun topic");
            check(((List<?>)result.get("requirement_coverage")).stream().map(r->(Map<?,?>)r).anyMatch(r->Objects.toString(r.get("criterion")).startsWith("课程授课经历：")&&r.containsKey("source_event")),"history criterion carries source event identity");
            expect(false,history,event(topic,"本人","主讲","","设备管理员"),"course capability is not history");
            expect(false,history,event(topic,"同事","独立主讲","已完成","设备管理员"),"colleague completion not own history");
            expect(false,history,event(topic,"本人","助教","已完成","设备管理员"),"completed assistance not completed teaching");
            expect(false,history,event(topic,"本人","主讲","计划中","设备管理员"),"future plan not completed teaching");
            String target=history+"\n培训对象：设备管理员";
            expect(true,target,own,"audience and history on same course event");
            expect(true,target,event(topic,"本人","独立主讲","已完成","财务审核员"),"course history plus future audience does not imply same-audience history");
            expect(true,target,event(topic,"本人","独立主讲","已完成","财务审核员")+"\n\n"+event(topic,"本人","主讲","","设备管理员"),"completed course history remains sufficient without borrowing another event audience");
            String independent="必须本人独立讲完《"+topic+"》并有课堂记录";
            expect(true,independent,own,"personal complete independent modifiers use one event");
            expect(false,independent,event(topic,"本人","主讲","已完成","设备管理员"),"completion without independent lead insufficient");
            expect(false,independent,event(topic,"本人","主讲","已完成","")+"\n\n"+event(topic,"本人","独立主讲","",""),"independence and completion cannot combine separate events");
            expect(false,history+"；还必须满足特殊资质与课堂活动要求",own,"unknown mandatory follow-up retained after consumed topic masking");
            expect(false,history+"；必须有这门课至少三次授课经历",own,"unknown numeric phrasing not silently consumed by history");
            expect(true,"培训主题："+topic+"\n培训目标：学员在培训后能够独立完成操作",event(topic,"本人","主讲","",""),"future learning goal does not force prior history");
            for(String syntax:List.of("需要本人已有独立主讲这门课程的已完成课堂记录", "请提供本人已经独立讲授了这一主题的课堂记录", "要求本人有已经独立完成该主题教学的课堂经历", "讲师本人须已独立完成这门课程的教学")) {
                String wrapped="补充要求：培训内容："+topic+"\n"+syntax;
                expect(true,wrapped,own,"request/history wrappers preserve same course and source offsets");
                expect(false,wrapped,event(topic,"同事","独立主讲","已完成","设备管理员"),"request wrapper cannot admit someone else's event");
            }
            for(String syntax:List.of("本人已有独立面向该对象讲完这门课的经历", "请提供本人为上述对象独立讲完该课程的记录")) {
                String targeted="补充要求：培训主题："+topic+"\n授课对象：设备管理员\n师资要求："+syntax;
                expect(true,targeted,own,"explicit audience reference binds source field and course event");
                expect(false,targeted,event(topic,"本人","独立主讲","已完成","财务审核员"),"audience reference cannot be merely consumed as syntax");
                expect(false,"培训主题："+topic+"\n"+syntax,own,"missing audience antecedent is not guessed");
            }
        }
        String a="陶瓷釉料调配",b="冷藏药品入库复核";
        String both="培训主题：《"+a+"》和《"+b+"》；必须已教过《"+a+"》";
        String aDone=event(a,"本人","主讲","已完成","设备管理员"),bCatalog=event(b,"本人","主讲","","设备管理员");
        expect(true,both,aDone+"\n\n"+bCatalog,"named history condition does not leak to second course");
        expect(false,both,event(a,"本人","主讲","","设备管理员")+"\n\n"+event(b,"本人","主讲","已完成","设备管理员"),"second course history cannot satisfy first course condition");
        expect(true,both+"\n培训对象：设备管理员",aDone+"\n\n"+bCatalog,"per-course history scoping survives common audience check");
        expect(true,both+"\n培训对象：设备管理员",aDone+"\n\n"+event(b,"本人","主讲","","其他受众"),"common future audience is context; each course retains its explicit history condition");
        String plural="培训主题：《"+a+"》和《"+b+"》；上述两门课程均须有实际授课记录";
        expect(true,plural,aDone+"\n\n"+event(b,"本人","主讲","已完成",""),"explicit plural history satisfied separately for both course events");
        expect(false,plural,aDone+"\n\n"+bCatalog,"plural does not borrow history across topics");
        expect(false,"培训主题：《"+a+"》和《"+b+"》；必须有这门课的授课经历",aDone+"\n\n"+event(b,"本人","主讲","已完成",""),"ambiguous singular condition remains pending even with both histories");
        expect(true,"培训主题：领导力；必须有这门课的经历","2025年领导力课程由本人主讲，学员由项目助理联络。","existing facet passive fact contract preserved");
        expect(true,"主题：仓储盘点；必须有本人实际授课记录；要求远程授课","本人实际主讲远程仓储盘点课程。","partial narrative never erases established legacy mode/history");
        System.out.println("Requirement topic integration: "+cases+" fixed cases, "+checks+" assertions passed");
    }
}
