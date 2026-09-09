package com.training;

import java.util.*;

/** Grammar-role regression; unrelated synthetic domains, no model or fixtures. */
public final class RequirementNarrativeTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Map<String,Object> assess(String query,String evidence) {
        Map<String,Object> candidate=new LinkedHashMap<>();candidate.put("professional_score",50.0);
        candidate.put("_rule_relevant",true);candidate.put("gaps",new ArrayList<String>());
        new RequirementCoverage(query).assess(candidate,evidence,true);return candidate;
    }
    static String event(String topic,String audience,String role,String state) {
        return "课程名称："+topic+"\n授课人：本人\n授课角色："+role+"\n完成状态："+state+"\n培训对象："+audience+"\n授课形式：面授";
    }
    public static void main(String[] args) {
        for(String topic:List.of("机柜标识与线缆登记","图书修补记录复核","陶泥含水量控制")) {
            String audience="刚入职的接线员";
            String declaration="服务站要给"+audience+"补一堂"+topic+"课";
            String history="请找以前确实为这类新人上完过这门面授课、整堂由自己讲的老师";
            String query=declaration+"。"+history+"；单位办过班不能代替他本人的经历。";
            RequirementTopic.Result parsed=RequirementTopic.parse(query);
            check(parsed.courses().size()==1,"one narrative course: "+parsed.toMap());
            RequirementTopic.Course c=parsed.courses().get(0);
            check(c.canonicalAnchor().equals(topic),"literal compound course retains conjunction");
            check(c.requiresHistory()&&c.historyConditions().get(0).independent(),"past whole self-teaching bound");
            check(c.historyConditions().get(0).audienceBindings().get(0).text().equals(audience),"audience anaphor binds exact original audience");
            check(c.historyConditions().stream().flatMap(h->h.audienceBindings().stream()).anyMatch(s->s.text().equals(audience)),"explicit historical audience remains bound to the course history contract");
            check(!new RequirementConstraints(query).unknownExclusion,"known organization substitution is enforced history condition");
            Map<String,Object> yes=assess(query,event(topic,audience,"独立主讲","已完成"));
            check(Boolean.TRUE.equals(yes.get("_admitted")),"matching literal complete event admitted: "+Json.write(yes));
            check(Boolean.TRUE.equals(assess(query,event(topic,"新入职接线员","独立主讲","已完成")).get("_admitted")),"new-hire grammatical variant keeps same actual job");
            check(!Boolean.TRUE.equals(assess(query,event(topic,"新入职财务专员","独立主讲","已完成")).get("_admitted")),"new-hire normalisation never erases actual job");
            for(String[] invalid:List.of(new String[]{"其他学员","独立主讲","已完成"},new String[]{audience,"助教","已完成"},new String[]{audience,"独立主讲","计划中"},new String[]{audience,"主讲","已完成"}))
                check(!Boolean.TRUE.equals(assess(query,event(topic,invalid[0],invalid[1],invalid[2])).get("_admitted")),"wrong role/state/receiver or missing independent fact cannot pass");
            String recruit="这次招聘的是给设备管理员讲"+topic+"的面授老师。要有自己完整带完同主题课堂的既往经历，单有写教案的成果还不够。";
            RequirementCoverage r=new RequirementCoverage(recruit);
            check(r.topicContract.courses().size()==1&&r.topicContract.courses().get(0).canonicalAnchor().equals(topic),"recruitment request course binding");
            check(!r.required.containsKey("岗位招聘"),"hiring teacher is not hiring curriculum");
            check(r.requiresTeachingHistory,"preparation not enough keeps actual history");
            check(Boolean.TRUE.equals(assess(recruit,event(topic,"设备管理员","独立主讲","已完成")).get("_admitted")),"recruitment with supported course passed");
            String explicitTopic="这次招聘的是给人事专员讲招聘面试的面授老师";
            check(new RequirementCoverage(explicitTopic).required.containsKey("岗位招聘"),"actual hiring curriculum still preserved");
            check(RequirementTopic.parse("不要给设备管理员讲"+topic+"的老师").courses().isEmpty(),"negated declaration not promoted");
            check(RequirementTopic.parse("计划明年由本人讲完这门课").courses().isEmpty(),"no antecedent is not guessed");
            String ambiguous="培训主题：《"+topic+"》和《另一门技能》；"+history;
            check(RequirementTopic.parse(ambiguous).courses().stream().noneMatch(RequirementTopic.Course::requiresHistory),"singular history with two courses remains unresolved");
            String mismatch="服务站要给资深主管补一堂"+topic+"课。"+history;
            check(RequirementTopic.parse(mismatch).courses().stream().noneMatch(RequirementTopic.Course::requiresHistory),"newcomer reference cannot silently bind senior audience");
            for(RequirementTopic.Consumption item:parsed.consumedSpans())check(query.substring(item.span().start(),item.span().end()).equals(item.span().text()),"all consumptions source-bound");
        }
        check(RequirementNarrative.declaration("本次要给设备管理员讲《红线管理》和《事故复盘》的老师")==null,"multiple quoted courses not collapsed to one");
        check(RequirementNarrative.declaration("这是合同摘录给甲方安排付款的老师")==null,"arbitrary prose not declared training intent");
        check(RequirementNarrative.declaration("必须持证才要给设备管理员讲机柜标识的老师")==null,"credential prefix not consumed as request decoration");
        check(RequirementNarrative.declaration("至少教过三场才要给设备管理员讲机柜标识的老师")==null,"numeric history prefix stays unresolved");
        check(!Boolean.TRUE.equals(assess("我们需要给甲方安排付款的老师",event("付款","甲方","独立主讲","已完成")).get("_admitted")),"administrative task is not a teaching course");
        check(RequirementNarrative.declaration("我们需要给甲方安排一次付款的老师")==null,"non-teaching occurrence classifier does not prove a course");
        check(RequirementNarrative.declaration("我们需要给设备管理员安排一堂线路排查的老师")!=null,"explicit lesson classifier provides teaching object");
        String hiring="我们招聘给新员工讲竹编收口的老师";
        check(!new RequirementCoverage(hiring).required.containsKey("岗位招聘"),"actor recruitment with requester remains outside course topic");
        check(Boolean.TRUE.equals(assess(hiring,event("竹编收口","新员工","独立主讲","已完成")).get("_admitted")),"explicit teaching verb in recruitment still works");
        for(String restriction:List.of("只要给飞行员授过课的老师","唯一条件是给飞行员授过课","仅用于飞行员课堂的培训经历","老师只要给飞行员授过课的")) {
            String restricted="需要给新员工上压力管理课；"+restriction;
            Map<String,Object> outcome=assess(restricted,event("压力管理","新员工","独立主讲","已完成"));
            check(!Boolean.TRUE.equals(outcome.get("_admitted")),"unbound exclusive audience history prevents admission");
            check(new RequirementCoverage(restricted).uncoveredConjunction,"unknown exclusive clause is enforced, not only saved");
        }
        check(RequirementConstraints.knownExclusiveDelivery("只能中文授课"),"complete known delivery requirement remains routed");
        check(!RequirementConstraints.knownExclusiveDelivery("只能中文授课且教过飞行员"),"partial delivery feature cannot consume extra audience history");
        String staged="给新员工安排岗前课，重点练服务礼仪";
        RequirementCoverage stageCoverage=new RequirementCoverage(staged);
        check(stageCoverage.required.containsKey("服务与商务礼仪"),"actual topic after training stage retained");
        check(stageCoverage.required.keySet().stream().noneMatch(k->k.contains("岗前")),"training stage not invented as professional content");
        check(stageCoverage.constraints.audienceIntent.contexts.stream().anyMatch(c->c.audience().equals("新员工")),"stage classification retains future audience context");
        check(new RequirementCoverage("给新员工安排岗前课").uncoveredConjunction,"training stage alone still needs actual content");
        check(RequirementTopic.parse("给新员工安排《岗前》课").courses().get(0).canonicalAnchor().equals("岗前"),"explicit named course remains literal even when title resembles stage");
        System.out.println("Requirement narrative: "+checks+" assertions passed");
    }
}
